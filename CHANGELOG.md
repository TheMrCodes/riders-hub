# Changelog

Notable changes to Riders Hub are documented here, with the newest version first.

The phone and Wear versions and build codes are independent and defined in
`gradle/libs.versions.toml`. Phone release notes are recorded here; watch release
notes are in [wear/CHANGELOG.md](wear/CHANGELOG.md). Keep upcoming changes under
`Unreleased`. Supported pairings are recorded in
[the compatibility map](docs/release-compatibility.json) and included in each
app's distribution notes.

## Unreleased

## 0.7.9

- Add storage-mode prompts after 14 inactive days and a Home progress card.
  Learn storage battery loss from three weekly observations, remind halfway
  to the estimated empty date, and repeat overdue reminders around 10:00 local
  time until a fresh sustained charge increase is observed. A recorded ride
  exceeding 0.1 km automatically ends storage mode.

- Send transitions into and out of estimated watch regeneration promptly instead
  of waiting for the normal Race update interval.

## 0.7.8

- Estimate instantaneous braking recovery for the watch from speed and a bounded
  motor-braking model, using an explicit provisional recovery efficiency.
  Stopped motors no longer report generated power from a brake command.

- Include raw frame bytes in realtime batches for shared archive/realtime storage
  and replay on the API.

- Keep exported board IDs stable across reinstalls so archive and realtime
  frames can share account/board/capture-time deduplication on the API.

- Skip reuploading unchanged finalized ride archives after reinstall by checking
  their hashes against archives already stored for the same signed-in account.

- Enforce a 50 Hz maximum recording rate per motion sensor, dropping faster
  hardware callbacks using monotonic timestamps across batch boundaries.

## 0.7.7

### Fixed

- Allow GPS to resume immediately after normal remote reconnection, and prevent
  a replaced GPS service from clearing or overwriting the current location fix.
- Keep motion stop actions isolated from realtime and other API uploads, including
  a stop requested before motion-service initialization finishes.
- Fix barometer timestamp overflow that prevented the first pressure sample from
  being recorded, and accept a fresh sample after restarting collection.
- Stop charger monitoring after a disconnect when relay output was last verified
  off. Bound other reconnect attempts to two minutes after the first failure,
  remove the ongoing notification when recovery ends, and wait for fresh charger
  presence instead of restarting from an unrelated companion-service rebind.

## 0.7.6

### Fixed

- Allow five seconds of GPS and phone motion tracking after remote disconnect,
  then pause tracking and sending while keeping the realtime API socket open.
  Reconnects resume on the same socket; trip finalization closes it.
  Reject stale callbacks after reconnects, cancel in-flight motion uploads, and
  retain unsent motion batches locally until a remote is connected again.
- Limit optional barometer collection to a live remote telemetry connection.

## 0.7.5

### Added

- Optional phone accelerometer and gyroscope recording during active rides,
  with a separate Motion API capability, local recording journal, five-second
  upload batches, and retry support. Enable **Record and share phone motion**
  in API settings after refreshing server capabilities. Recording requests 50 Hz
  per sensor and retains every valid delivered sample; actual timing depends on
  the phone. Motion sharing is off by default and scoped to the selected server.
- Persistent charger-monitoring and motion-recording notifications, with actions
  to stop monitoring or recording. Stopping charger monitoring does not switch
  the plug off.
- A Start charging action on the verified companion charger notification.
- A local archive ZIP export button in Settings with Android destination selection.

### Fixed

- Keep charger monitoring independent of transient companion-service lifecycle
  events; recreate failed Bluetooth connections with timeouts and retry backoff.
  Verify plug identity and relay state after reconnecting without replaying an
  uncertain ON command.
- Move charger RPC/archive work, realtime API preparation, and GPS processing
  off the UI thread. Reuse decrypted API identity while checking current consent
  and authorization before sending.
- Buffer every valid realtime board frame in five-second batches and request one
  fresh GPS fix shortly before sending, replacing continuous location updates.

### Notes

- Phone-only release; compatible with Wear **0.7.3-wear**.
- Charger archives require server support for COA v1. Motion recording requires
  the new motion capability. Refresh API capabilities after the server updates.
- At the requested sensor rate, motion payloads use about 8 MB per ten minutes.
  Uploaded recordings remain on the phone; reaching the 512 MiB local payload
  limit stops recording with an error. Interrupted processes can lose the
  current unsealed motion batch; sealed batches remain available for retry.

## 0.7.4

### Changed

- Phone and Wear use independent version names and build codes. This phone
  release supports Wear `0.7.3-wear`; phone-only fixes do not require a watch
  version bump.
- Riders Profile keeps the tabs below the chart and moves battery detail rows
  into a separate Energy statistics section underneath. Switching between
  Distribution and Energy use keeps the chart height stable.
- Riders Profile requires at least 5 km of measured riding per displayed bucket,
  trimming speed buckets with insufficient measured distance from
  both ends of the chart and excluding unsupported interior buckets. Distance
  shares are normalized across the supported speeds; energy use also requires
  battery-calibration support.

### Added

- A **Replay local archive** button at the bottom of Settings restores missing
  trips and rebuilds Riders Profile from verified recordings already on the phone.
  Replay shows progress and a result, prevents duplicate trips, and preserves
  existing UI data if validation fails or a recording starts during recovery.

## 0.7.3

### Changed

- Phone builds use version name `0.7.3`; the watch companion uses `0.7.3-wear`
  so the two APKs are distinguishable in distribution listings.
- Both builds retain separate increasing revision codes and include the
  functionality documented under `0.7.3-test` below.
- The next revision uses phone build `500025` and Wear build `600021` while
  keeping the same version names. Phone Settings also shows the build revision
  so successive installations of the same app version can be distinguished.

## 0.7.3-test

### Added

- Added a non-standalone Wear OS companion dashboard for live speed, board
  battery, trip distance, ride mode, connection state, and stale-data handling.
- Added a versioned phone-to-watch Data Layer contract that excludes device
  identifiers, odometer totals, ride history, locations, and raw telemetry.
- Added a shared Android Studio watch run target and a debug-only ADB telemetry
  inlet for repeatable emulator testing; neither synthetic input nor sample
  ride data is present in release builds.
- Added a one-update-per-minute ambient ride dashboard with burn-in shifting,
  large trip distance, board battery, estimated range remaining, and a
  ride-scoped Ongoing Activity that keeps the dashboard visible.
- Added a swipe-accessible Wear display setting that switches active rides
  between the battery-friendly ambient dashboard and an always-awake live view.
- Made Wear telemetry survive process recreation and continue arriving while
  the dashboard is backgrounded through a filtered Data Layer listener.
- Added a persisted General setting for the shared dashboard and notification
  low-battery threshold, adjustable from 5% to 50% in five-point steps.
- Added a physics-informed frame model for battery use by speed. It learns
  rolling, aerodynamic and idle shape from aligned load frames and recuperation
  from pack-voltage response, and separates stop-and-go and hill overheads. Grade
  comes from the phone barometer when available and is otherwise inferred.
- Added a rider and board profile to General settings: board model (Backfire
  G3 by default, with published weight and pack energy for other models), rider
  weight, and optional board weight and battery energy overrides. The consumption
  model starts from these values and learns one pack-energy factor, shown as
  usable pack energy in the Riders Profile. The profile stays on the phone.

### Changed

- Centralized the app version and separate phone/Wear version codes in the
  Gradle version catalog. Both apps now use the same release version name.

### Fixed

- Riders Profile energy use no longer shows one identical value for every speed
  bucket: cruise costs follow the fitted speed curve, and nearly flat curves use
  extra precision instead of rounding to the same label.

## 0.6.0 - 2026-08-28

### Added

- Optional Home Assistant integration through its `mobile_app` webhook API,
  with encrypted battery, range, trip-distance, last-update, and in-use states
  plus trip start and end events.
- Home Assistant routing through a local webhook, Nabu Casa cloudhook, or a
  custom HTTPS proxy, including manual synchronization and visible delivery
  errors.
- Battery Longevity tracking with inferred charge cycles, speed-normalized
  full-charge estimates, an interactive daily-to-yearly chart, and compact
  voltage-correlation statistics for future analysis.
- Append-only local telemetry archives that preserve complete rides in
  verified compressed packages without overwriting earlier data.
- A redesigned longboard launcher icon, Android themed icon, and black launch
  screen.

### Changed

- Reworked Home Assistant settings with the enable switch in the section
  heading, automatic valid-webhook saving, a reset confirmation, compact
  connection statistics, and a loading Sync button.
- Home Assistant ride updates now run every ten seconds from the actual request
  start while battery changes, manual syncs, and trip transitions remain
  immediate.
- Added a telemetry-silence watchdog and one controlled recovery attempt while
  preserving the original two-minute logical-ride reconnect window.
- Added the tested `G3` model beside the paired remote name and kept dot-matrix
  headings anchored to the left on wider displays.
- Improved locale-safe formatting across dashboard values, dates, chart labels,
  URLs, protocol parsing, notifications, and range calculations.

### Fixed

- Corrected Home Assistant sensor updates that previously serialized the
  `data` array as a string.
- Prevented unencrypted webhook delivery and surfaced missing encryption keys
  as request errors.
- Ensured interrupted telemetry sessions eventually publish the final
  not-in-use state and trip-ended event instead of remaining active forever.
- Prevented delayed Home Assistant requests from shortening the intended
  ten-second periodic cadence.
- Fixed Battery Longevity chart labels under locales that use non-Latin digits.
- Fixed the dot-matrix app title shifting toward the center on large screens.
