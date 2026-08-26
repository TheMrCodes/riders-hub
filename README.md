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
- Current-trip distance, moving time, top speed, and recent ride summaries.
- Automatic background detection through Android's Companion Device Manager,
  without a permanent foreground service or continuous custom scanning.
- Short-disconnect continuity: reconnecting within two minutes resumes the
  same logical ride and telemetry log.
- A low-board-battery notification at 20% or below.
- Local JSONL telemetry logs with decoded values and original BLE frame data.
- A monochrome, dot-matrix-inspired dashboard with no account or cloud service.

A separate remote-battery field has not been identified, so Riders Hub shows
that value as unavailable instead of presenting an inferred reading.

## Design inspiration

Riders Hub's visual language is inspired by Nothing's product design: a
high-contrast monochrome palette, dot-matrix typography, generous open space,
modular information, and restrained red accents for state and warnings. It is
an independent interpretation and is not affiliated with or endorsed by
Nothing Technology.

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

## Privacy

Riders Hub has no Internet, location, storage, boot, wake-lock, exact-alarm, or
foreground-service permission. Ride history and raw telemetry remain on the
device in the app-specific external directory. Android may still require the
device-wide Location Services switch for companion-device discovery, but the
app is not granted location access and does not receive coordinates.

Exported logs can contain ride data and a stable Bluetooth address. Local
captures, signing material, and build outputs are ignored by Git; review any
file carefully before forcing it into a public commit.

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

Riders Hub is available under the [MIT License](LICENSE).
