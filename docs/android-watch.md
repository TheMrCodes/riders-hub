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

- `LIVE` and `RECONNECTING` states are eligible;
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

- current trip kilometres, displayed prominently;
- board battery;
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

The ambient layout uses a black background, muted text, and only the three
essential metrics. On devices that request burn-in protection, the complete
layout moves through four positions using small two-dp offsets. This prevents
the same pixels from remaining continuously lit without making the values jump
noticeably around the round display.

Ambient mode deliberately has no app-owned per-second clock. Wear OS calls the
ambient update callback, and the app renders one new frame from the most recent
buffered payload.

### Optional live screen

When **Live screen** is enabled, Riders Hub applies `FLAG_KEEP_SCREEN_ON` only
while telemetry still represents a recent active ride. The flag is cleared when
the ride enters standby, telemetry becomes stale, the setting is disabled, or
the composable leaves the activity. It is a window behavior, not a wake lock or
foreground service.

This option preserves the real-time dashboard for riders who prefer it, but its
higher display power use is why it is not the default. Once the active-ride
condition ends, the app no longer prevents the system from entering ambient
mode.

## Implementation boundaries

The decisions above rely on these components:

- `MainActivity` attaches the ambient lifecycle, gates visible telemetry to
  ambient ticks, applies burn-in offsets, and owns the optional keep-screen-on
  flag.
- `WearTelemetryListenerService` receives only the Riders Hub telemetry Data
  Layer path while the activity is backgrounded.
- `WearTelemetryPersistence` stores and restores the newest payload and
  prevents older bootstrap data from replacing it.
- `WearOngoingActivity` owns the recent-ride eligibility and notification
  lifecycle.

Unit tests should continue to cover ambient buffering, burn-in offset rotation,
recent-ride eligibility, keep-screen-on gating, persistence round-trips, and
newer-payload precedence. Synthetic telemetry remains a debug-only validation
facility and must not be present in release artifacts.

## Platform references

- [Display ongoing activities](https://developer.android.com/training/wearables/notifications/ongoing-activity)
- [Always-on apps and system ambient mode](https://developer.android.com/training/wearables/always-on)
- [Foreground services overview](https://developer.android.com/develop/background-work/services/fgs)
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
