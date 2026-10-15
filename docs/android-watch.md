# Android watch architecture

This document records the lifecycle and display decisions behind the Riders Hub
Wear OS companion. It is an architecture note for maintainers, not an install
guide.

## Context

The phone remains the source of truth for the board connection, ride lifecycle,
and telemetry. The watch is a non-standalone display: it receives a deliberately
small Data Layer payload and does not scan for the board, integrate distance, or
record a second ride.

The watch therefore has two separate responsibilities:

1. Keep an active ride easy to return to from Wear OS surfaces.
2. Present useful ride information after the interactive screen timeout without
   wasting battery or showing fast-changing values as though they were live.

Those responsibilities do not require continuous work on the watch.

## Foreground execution decision

### Decision

Riders Hub uses a ride-scoped Wear OS **Ongoing Activity**, but does **not** run
a foreground service on the watch.

The distinction is intentional. `WearOngoingActivity` posts a low-priority,
ongoing notification and attaches `OngoingActivity` metadata. That makes the
ride visible on supported Wear OS surfaces and provides a direct way back to
the dashboard. A notification categorized as a service is still not a
foreground service: the Wear manifest declares no foreground-service
permission or foreground-service type, and the app never calls
`startForegroundService()` or `startForeground()`.

The Ongoing Activity exists only while telemetry describes a recent active
ride:

- `CONNECTING`, `LIVE`, and `RECONNECTING` states are eligible;
- `STANDBY`, missing telemetry, and telemetry older than two minutes are not;
- notification updates are limited to once per minute;
- the notification has a 150-second timeout as a final stale-state safeguard.

### Why a foreground service was rejected

A foreground service is appropriate when the watch itself must continuously
perform user-noticeable work while its UI is not visible. Riders Hub currently
has no such work:

- the phone owns BLE communication and ride recording;
- watch updates arrive through the event-driven Wear Data Layer;
- the watch does not poll, scan, use location, sample sensors, or run a timer to
  calculate ride state;
- ambient rendering is scheduled by Wear OS rather than by an app-owned loop.

A foreground service would therefore add process lifetime, permissions,
service-type policy, a continuously running component, and additional power
cost without improving the accuracy of the ride record. Android also restricts
when foreground services may be started from the background, which makes one a
poor substitute for a correctly registered Data Layer listener.

The Ongoing Activity is not treated as process-lifetime protection. Wear OS may
still stop the app process. Reliability instead comes from reconstructible
state:

```text
phone publishes telemetry
        |
filtered Data Layer listener receives the matching path
        |
newest payload is persisted and offered to the UI
        |
Ongoing Activity is created, refreshed, or cancelled

process recreation
        |
restore persisted payload + bootstrap the newest Data Item
        |
discard an older bootstrap result if a newer listener update already arrived
```

This design also lets a phone update reach the listener while the dashboard is
backgrounded. The watch stores only the latest privacy-minimized payload, not a
second telemetry history.

### Optional automatic trip opening

The side settings page includes **Auto open**, enabled by default. The watch
publishes this one boolean setting to the paired phone through a separate Data
Layer path. When enabled, the phone requests the watch dashboard on the first
CRC-valid `LIVE` frame of each new logical ride using AndroidX
`RemoteActivityHelper` and the app's browsable `ridershub://ride` deep link.

The phone stores only a truncated hash of the local session reference to ensure
that one ride cannot open the dashboard twice. It also marks the first live
frame as seen while Auto open is disabled, so enabling the setting during a ride
applies to the next ride instead of interrupting the current one.

Remote opening is an initial convenience, not lifecycle protection. If the
watch is unreachable or the platform declines the request, telemetry still
arrives normally and the Ongoing Activity remains the reliable, one-tap return
surface. No foreground service is introduced.

### When to revisit the decision

A real foreground service should be reconsidered if a future watch feature must
perform continuous work independently of the phone, such as watch-side board
communication, sensor or location recording, or another user-started operation
whose correctness depends on execution while the activity is closed. That
change must define an appropriate foreground-service type, permissions, start
eligibility, stop condition, notification, and failure recovery. Keeping the
screen visible by itself is not sufficient justification.

## Ambient display decision

### Decision

System-managed ambient mode is the default active-ride display after the normal
interactive timeout. A user can instead enable **Live screen** from the watch's
side settings page, but that higher-power mode is opt-in.

Ambient mode fits the information available to the watch. Wear OS normally
offers an ambient update about once per minute, so the ambient screen contains
only values that remain useful at that cadence:

- current local time, using the same 24-hour clock as the interactive screen;
- current trip kilometres, displayed prominently;
- estimated board battery, falling back to the reported percentage;
- estimated kilometres remaining.

Speed, connection state, and ride mode stay on the interactive screen. Speed is
misleading when it cannot redraw in real time, while connection state and mode
are not important enough to justify more lit pixels or additional burn-in risk.

### Update and stale-data behavior

Incoming phone telemetry is still accepted and persisted while ambient mode is
active, but it is buffered for display. The visible Compose state changes only
from the system ambient tick. Leaving ambient mode immediately publishes the
latest buffered value to the interactive screen.

This keeps rendering aligned with the system's low-power cadence and avoids
redrawing the ambient UI for every phone update. The Ongoing Activity follows
the newest received telemetry rather than the minute-delayed ambient frame, so
its lifetime can still end promptly when the ride stops or becomes stale.

### Power and burn-in safeguards

The ambient layout uses a black background, muted text, the clock, and three
essential ride metrics. On devices that request burn-in protection, the complete
layout moves through four positions using small two-dp offsets. This prevents
the same pixels from remaining continuously lit without making the values jump
noticeably around the round display.

Ambient mode deliberately has no app-owned per-second clock. Wear OS calls the
ambient update callback, and the app renders one new frame from the most recent
buffered payload and the current local clock, even when no telemetry arrives.

### Optional live screen

The display setting cycles **Ambient → Live screen → Race → Ambient** with each
tap. The selected mode survives restarts; the previous keep-awake setting migrates
to Ambient or Live screen. These are watch display choices, independent of the
board's riding mode. The settings swipe page and Auto open switch remain available.

When **Live screen** or **Race** is enabled, Riders Hub applies `FLAG_KEEP_SCREEN_ON` only
while telemetry still represents a recent active ride. The flag is cleared when
the ride enters standby, telemetry becomes stale, the setting is disabled, or
the composable leaves the activity. It is a window behavior, not a wake lock or
foreground service.

This option preserves the real-time dashboard for riders who prefer it, but its
higher display power use is why it is not the default. Once the active-ride
condition ends, the app no longer prevents the system from entering ambient
mode.

### Race dashboard

Race shows an open-bottom perimeter power marker, large central speed and a
compact battery dial beneath it. Range and trip distance remain on the other
dashboard modes, not Race. The small battery dial uses estimated charge
with reported charge as fallback, and its red warning zone follows the phone's
configured low-battery threshold. Battery percentages have no approximation prefix.
The local clock is centered at the top with the live dot just to its left, as in
the other interactive modes. System ambient uses the existing
low-power trip/clock layout even when Race is selected.

Zero power points left (180 degrees). Positive draw travels clockwise through
240 degrees up to 2000 W; estimated recovery travels counter-clockwise up to 60 degrees
at -500 W. This leaves a 60-degree bottom opening for the signed watt readout
(for example, `+420 W`, without an `EST` prefix). The marker is clamped at the
dial endpoints while the numerical reading retains out-of-range values. Scalar interpolation crosses zero
and changes the marker from red to green for estimated recovery. The separate
label reads `DRAW EST.` or `REGEN EST.`; accessibility announces estimated
consumption or recovery explicitly. Sub-watt values rounding to zero show `0 W`.

Power is a **provisional command-based estimate**, computed for every usable BLE
frame. The raw input is a remote command, not a battery-current measurement.
Positive input retains the existing drive-display scale:
`voltage × effective raw input × 0.020497265957721715`.
Negative input uses a separate motor-braking envelope:

```text
brake_fraction = clamp(-effective_raw_input / 670, 0, 1)
mechanical_braking_W = 480 N × brake_fraction × min(speed_mps, 6 m/s)
estimated_recovered_W = 0.40 × mechanical_braking_W
display_W = -estimated_recovered_W
```

The force/power limits are rounded provisional parameters from the force-model
experiment, not manufacturer ratings. Recovery efficiency is an explicit 40%
assumption, not a measured coefficient. At standstill, braking reports zero
recovery; neutral input does not infer regeneration from slowing or voltage
rebound. The existing board-specific raw-zero/noise calibration supplies effective
input. Invalid CRC, voltage, input or speed leaves power unavailable. Estimates
use only the current frame and existing zero calibration; controller delay,
battery charge acceptance and electrical losses are not independently observed.
The drive and recovery branches remain display diagnostics and supply no SOC,
capacity or range credit.

The original telemetry v3 and auto-open settings v1 remain unchanged. Separate
version-1 payloads use `/riders-hub/display-mode` (watch preference) and
`/riders-hub/race-sample` (durable sample, at most every two seconds). Connected
watches selecting Race also receive `/riders-hub/race-live` messages normally every
250 ms. Transitions into or out of displayed recovery bypass that interval when
transport is available. Messages contain sample time, speed, signed estimated watts and the warning
threshold. Messages update memory only; one in-flight message per watch prevents
queues; an event wholly inside a busy or disconnected interval can still be missed.
The two-second sample supplies reconnect/process-restart fallback. After
three seconds without a fresh sample the marker returns to zero in grey, speed
and power show placeholders, and the dashboard identifies stale power data.

## Implementation boundaries

The decisions above rely on these components:

- `MainActivity` attaches the ambient lifecycle, gates visible telemetry to
  ambient ticks, applies burn-in offsets, and owns the optional keep-screen-on
  flag.
- `WearTelemetryListenerService` receives the Riders Hub telemetry and Race
  Data Items, plus the transient Race message path.
- `WearRaceRepository` keeps fast updates in memory, persists only durable
  samples, and rejects older deliveries. Source timestamps and a monotonic
  receipt timeout prevent stale speed/power, including when device clocks differ.
- `WearTelemetryPersistence` stores and restores the newest payload and
  prevents older bootstrap data from replacing it.
- `WearOngoingActivity` owns the recent-ride eligibility and notification
  lifecycle.
- `WearSettingsPublisher` sends the Auto open preference to the phone, and the
  phone's `WearSettingsListenerService` stores it locally.
- `WearRemoteLauncher` recognizes the first valid live frame once per ride and
  requests the dashboard through the platform remote-activity API.

The dashboard and settings remain two horizontally swiped pages. They use fixed,
non-scrolling layouts without a page-indicator overlay. The interactive dashboard
shows a bare 24-hour clock at the top and keeps connection status at the bottom,
leaving the ride metrics unobstructed in the center.

Unit tests should continue to cover ambient buffering, burn-in offset rotation,
recent-ride eligibility, keep-screen-on gating, persistence round-trips, and
newer-payload precedence. Synthetic telemetry remains a debug-only validation
facility and must not be present in release artifacts.

## Platform references

- [Display ongoing activities](https://developer.android.com/training/wearables/notifications/ongoing-activity)
- [Always-on apps and system ambient mode](https://developer.android.com/training/wearables/always-on)
- [Foreground services overview](https://developer.android.com/develop/background-work/services/fgs)
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [RemoteActivityHelper](https://developer.android.com/reference/androidx/wear/remote/interactions/RemoteActivityHelper)


### Estimated battery transport

Telemetry schema 3 adds an optional continuous battery estimate while preserving
raw board battery percentage. New watches also read schemas 1 and 2, showing
raw battery when no estimate is available. Update both phone and watch for the
new payload; older watch builds reject schema 3. The watch does not run a second
battery model. Ambient updates still follow the system callback cadence.
