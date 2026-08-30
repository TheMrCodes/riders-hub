# Changelog

Notable changes to Riders Hub are documented here.

## Unreleased

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
