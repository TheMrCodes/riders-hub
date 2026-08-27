# 🛹 Riders Hub

Riders Hub is an independent, open-source Android ride companion for electric
skateboards. It records board telemetry locally, presents current and past ride
information, and uses Android's companion-device support for low-power
background detection.

The first supported hardware integration is for compatible Backfire boards and
their `BF_*` remotes. The project is designed as Riders Hub—not as an official
Backfire application or a product tied to that brand.

## Independence and trademarks

Riders Hub is an independent community project. Its authors and contributors
are not affiliated with, associated with, sponsored by, or endorsed by
Backfire or Backfire Boards. Backfire does not provide support for this
software. Backfire and related product names and trademarks belong to their
respective owners.

## Features

- Board battery percentage, pack voltage, odometer, and a confidence-labelled
  remaining-range estimate.
- Current-trip distance, moving time, top speed, five-kilometre-per-hour speed
  buckets, and recent ride summaries.
- Automatic background detection through Android's Companion Device Manager,
  without a permanent foreground service or continuous custom scanning.
- Short-disconnect continuity: reconnecting within two minutes resumes the
  same logical ride and telemetry log.
- A low-board-battery notification at 20% or below.
- Lossless local telemetry archives with decoded values and original BLE frame
  data; finalized JSONL is appended to bounded working partitions, which become
  verified compressed analytics packages when full.
- Battery Longevity tracking with a speed-normalized full-charge estimate,
  observed charge windows, and an adaptive daily-to-yearly capacity chart.
- Optional Home Assistant export for battery, estimated range, current-trip
  distance, last update, and in-use state—never live speed.
- A monochrome, dot-matrix-inspired dashboard with no Riders Hub account or
  required cloud service.

A separate remote-battery field has not been identified, so Riders Hub shows
that value as unavailable instead of presenting an inferred reading.

## Design inspiration

Riders Hub's visual language is inspired by Nothing's product design: a
high-contrast monochrome palette, dot-matrix typography, generous open space,
modular information, and restrained red accents for state and warnings. It is
an independent interpretation and is not affiliated with or endorsed by
Nothing Technology.

The launcher uses the same longboard mark for its regular, round, and Android
themed-icon variants. The launch screen and system bars remain black so startup
matches the dashboard instead of flashing a light background.

## Supported hardware

The current adapter connects to the remote, not directly to the skateboard
ESC. The remote must already be connected to the board and advertising with a
name beginning with `BF_`.

The integration has been tested with a Backfire G3. The BLE transport and core
telemetry fields are also corroborated by community work on a Zealot S, but
other board and remote revisions may differ. Compatibility should not be
assumed until a model has been verified.

See the [BLE API notes](docs/ble-api.md) for the current protocol details,
confidence levels, and known unknowns.

## Safety

Riders Hub's normal operation subscribes to telemetry notifications from the
paired remote.

This project is experimental and is based on community and independent reverse
engineering. Keep the board's drive wheels clear of the ground during physical
testing.

## Background behavior

1. Associate a supported remote once through Android's system chooser.
2. Android watches for that device while Riders Hub is closed.
3. When the remote appears, the companion service connects and records
   telemetry.
4. When the remote leaves, BLE work stops. A reconnect within two minutes
   continues the same ride; otherwise Android finalizes it with a one-shot
   alarm.

Riders Hub does not keep an activity, periodic worker, custom BLE scanner, wake
lock, or permanent foreground notification running in the background.

## Range and battery longevity

The large number in **Range** is the estimated distance remaining at the
current battery level. The bar beneath it is estimator readiness—not another
distance or battery gauge. It fills as Riders Hub observes usable distance and
battery depletion: an estimate appears after at least 1 km and 2% depletion,
becomes **Ready** after 5 km and 10%, and continues gaining confidence up to 20
km and 30% depletion. Completed rides are split into 5 km/h speed buckets so
the estimate can reflect the speed profile of the current ride instead of
treating every kilometre as equally demanding.

**Battery Longevity** tracks a separate speed-normalized estimate of kilometres
per full charge. Because the known board protocol does not expose charger
state, Riders Hub infers a new charge observation when a later ride begins at
least five percentage points above the previous ending level. A capacity point
requires at least 0.5 km, 5% depletion, and sufficiently complete speed data;
the headline weights the latest three usable observations by their measured
depletion. The chart begins with daily bars. Pinch inward to group by week,
month, and year, or tap a grouped bar to inspect the next finer interval. Its
vertical scale always runs from zero to the all-time observed high.

These are empirical estimates for the recorded rider, board, routes,
temperature, tires, and riding style. They are not measured Ah/Wh capacity or a
definitive battery-health diagnosis. Voltage, speed, battery, and the currently
unidentified signed load field are retained locally as compact statistical
summaries for future correlation studies; they are not sent to Home Assistant.

## Local telemetry archives

When a ride ends, its complete JSONL log is appended unchanged to an
append-only working partition. A partition is sealed when it reaches roughly
10 MiB or the paired remote changes, then compressed into an immutable,
checksummed analytics package; the next sequence becomes the new working
partition. Existing entries are never rotated away or overwritten. Interrupted
trailing writes are ignored while earlier completed entries remain usable.

The archive header stores one remote address for the whole partition so entries
from different remotes are never mixed. This identifier and the ride data stay
in the app-specific storage and must be treated as sensitive when exported. The
binary format and verification rules are documented in the
[Android app guide](docs/android-logger.md#rhprha-archive-format-version-1).

## Privacy

Riders Hub has Internet permission solely for its optional Home Assistant
integration. That integration is off by default and sends only board battery,
estimated range, current-trip distance, an in-use state, and update timestamps;
it never sends live speed, raw telemetry, the Bluetooth address, or ride logs.
The Home Assistant URL and long-lived token are used only during registration
and are not persisted. The returned webhook URL and application-level
encryption key are credentials. Riders Hub encrypts both at rest with Android
Keystore, stores only their encrypted forms in private app preferences, and
excludes them from backup and device transfer.

The app has no location, storage, boot, wake-lock, exact-alarm, or
foreground-service permission. Ride history and raw telemetry remain on the
device in the app-specific external directory. Android may still require the
device-wide Location Services switch for companion-device discovery, but the
app is not granted location access and does not receive coordinates.

Exported logs can contain ride data and a stable Bluetooth address. Local
captures, signing material, and build outputs are ignored by Git; review any
file carefully before forcing it into a public commit.

## Home Assistant

Home Assistant support uses the official `mobile_app` registration and webhook
interfaces. In **Device → Home Assistant**:

1. Enable Home Assistant export.
2. In the Home Assistant Companion app, navigate to **Sidebar → Profile →
   Security → Long-Lived Access Tokens → Create Token**, then copy the token.
3. Enter the Home Assistant base URL and token in Riders Hub, then tap
   **Connect**.
4. Riders Hub registers itself and five telemetry entities, enables Home
   Assistant's encrypted webhook protocol, securely stores the returned webhook
   URL and encryption key, and immediately forgets the base URL and token.
5. Once connected, optionally replace the webhook URL with the equivalent Nabu
   Casa cloudhook, local URL, or an HTTPS proxy such as
   `https://ha.example.com/api/webhook/…`.

While a ride is active, estimated range and current-trip distance are delivered
every ten seconds, with an immediate delivery whenever the integer battery
percentage changes. One final update is delivered at ride end; start/end
transitions are also sent as `riders_hub_trip_started` and
`riders_hub_trip_ended` events. Webhook data payloads and sensor-response bodies
are encrypted between Riders Hub and Home Assistant; routing metadata such as
the webhook URL and operation type remains visible to the transport. HTTPS is
still required for every remotely reachable address because the URL path is a
credential. Plain HTTP is accepted only for a private or `.local` address on a
trusted LAN.

Home Assistant may additionally create its standard mobile-app device tracker.
Riders Hub has no location permission and never supplies coordinates, so that
tracker remains without location data.

**Disconnect** removes the saved webhook from Riders Hub. Home Assistant does
not expose registration deletion through this webhook API, so remove the Riders
Hub entry in Home Assistant separately when you no longer want it there.

## Requirements

- Android 14 or newer
- A compatible board and Bluetooth remote
- JDK 17 or newer for development
- Android SDK API 36 for building

## Build and install

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `at.themrcodes.ridershub`. Installation, association,
lifecycle details, log extraction, and the JSONL schema are documented in the
[Android app guide](docs/android-logger.md).

## Repository layout

- [`app/`](app/) contains the Android application, resources, and JVM tests.
- [`gradle/`](gradle/) and the root Gradle files provide the reproducible build.
- [`docs/`](docs/) contains protocol evidence, lifecycle documentation, and
  capture analysis.

## Technical documentation

- [Android app, lifecycle, and log schema](docs/android-logger.md)
- [Backfire BLE API and evidence levels](docs/ble-api.md)
- [Moving telemetry capture analysis](docs/capture-analysis-2026-08-25.md)

## Acknowledgments

Special thanks to the community-maintained Swift project
[`djensenius/Backfire`](https://github.com/djensenius/Backfire) for documenting
the Backfire BLE interface. Its findings about the F1 transport and telemetry
frame fields provided important groundwork that was reused and independently
validated in Riders Hub. The project's
[protocol issue #26](https://github.com/djensenius/Backfire/issues/26) also
documents the 25-byte frame reassembly, multi-byte fields, odometer, voltage,
and CRC-16/MODBUS behavior.

Riders Hub is professionally vibe-coded: it is developed with AI assistance
under professional software-engineering oversight, but remains a side project.
It may contain bugs, incorrect assumptions, or security issues. Please review
it carefully, report problems responsibly, and—if you have the time—help make
it better. Contributions and support are always appreciated. ❤️

## License

Riders Hub is available under the [MIT License](LICENSE). Dependency licensing
is listed in [Third-party notices](THIRD_PARTY_NOTICES.md).
