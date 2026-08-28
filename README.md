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
- Local ride archives that preserve decoded telemetry and original BLE frames
  in compact, verified files.
- Battery Longevity tracking with a speed-normalized full-charge estimate,
  observed charge windows, and an adaptive daily-to-yearly capacity chart.
- Optional Home Assistant export for battery, estimated range, current-trip
  distance, last update, and in-use state—never live speed.
- A Wear OS companion dashboard for live speed, board battery, trip distance,
  estimated remaining range, and ride mode, with clearly subdued last-known
  values while disconnected.

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

The Backfire G3 is the only board tested so far, which is why the UI displays
`G3`; Riders Hub should also work with boards using second-generation or newer
black-plastic remotes with the index-finger opening, but those combinations
remain unverified. The BLE transport and core telemetry fields are also
corroborated by community work on a Zealot S, but other board and remote
revisions may differ.

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

**Range** estimates the kilometres remaining at the current battery level. Its
bar shows how much useful ride data supports the estimate—not battery level or
distance. An estimate appears after 1 km and 2% battery use and becomes
**Ready** after 5 km and 10%. Speed buckets help adapt it to the current riding
style.

**Battery Longevity** compares speed-normalized kilometres per full charge over
inferred charging cycles. It needs at least 0.5 km and 5% battery use for a data
point. The chart starts daily; pinch to group by week, month, or year, and tap a
bar to inspect it more closely. Local voltage statistics support future
analysis, but these estimates are not a measured capacity or battery-health
diagnosis.

## Local telemetry archives

After each ride, Riders Hub moves the complete log into a local archive. Archive
files are compressed and verified at roughly 10 MiB or when the paired remote
changes; existing rides are never overwritten. Interrupted final writes are
ignored without losing earlier entries.

Archives contain ride data and a device identifier, so exported files are
sensitive. See the [Android app guide](docs/android-logger.md#rhprha-archive-format-version-1)
for the technical format.

## Home Assistant

The optional Home Assistant connection uses its official `mobile_app` webhook
interface. In **Device → Home Assistant**:

1. Enable Home Assistant export.
2. In the Home Assistant Companion app, navigate to **Sidebar → Profile →
   Security → Long-Lived Access Tokens → Create Token**, then copy the token.
3. Enter the Home Assistant URL and token, then tap **Connect**. They are used
   once for registration and are not saved.
4. Keep the supplied webhook URL or replace it with a Nabu Casa cloudhook, local
   URL, or HTTPS proxy such as `https://ha.example.com/api/webhook/…`.

Riders Hub creates entities for battery, estimated range, trip distance, last
update, and in-use state. During a ride it updates every ten seconds, sends
battery changes immediately, and emits start/end events. Payloads and stored
webhook credentials are encrypted; remote URLs require HTTPS, while HTTP is
accepted only on a trusted local network. Home Assistant may also create an
empty device tracker, but Riders Hub never supplies location data.

**Disconnect** removes the local webhook credentials. Remove the integration in
Home Assistant separately if it is no longer needed.

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

The optional Wear OS companion receives only connection state, live speed,
board battery, current trip distance, the phone's aggregate estimated remaining
range, ride mode, and the update time through Google's private paired-device
Data Layer. Bluetooth addresses, remote names, odometer totals, ride history,
locations, and raw telemetry are not sent to the watch.

Exported logs can contain ride data and a stable Bluetooth address. Local
captures, signing material, and build outputs are ignored by Git; review any
file carefully before forcing it into a public commit.

## Requirements

- Android 14 or newer
- Wear OS 3 or newer for the optional watch companion
- A compatible board and Bluetooth remote
- JDK 17 or newer for development
- Android SDK API 36 for building

## Build and install

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

The application ID is `at.themrcodes.ridershub`. Installation, association,
lifecycle details, log extraction, and the JSONL schema are documented in the
[Android app guide](docs/android-logger.md).

The watch build is a non-standalone companion and uses the same application ID
as the phone build. For Google Play distribution, publish both form-factor
artifacts under the same listing and signing key. Modern Wear OS installs its
artifact through Play on the compatible watch; a directly sideloaded phone APK
does not embed or automatically sideload the watch APK.

### Install on a watch from Android Studio

1. Open the repository root in Android Studio and let the Gradle sync finish.
2. On the watch, enable **ADB debugging** and **Wireless debugging** under
   **Developer options**. The watch and workstation must be on the same Wi-Fi
   network.
3. Pair the watch with the workstation. When pairing manually, use the address
   under **Pair new device** only with `adb pair`. Then use the separate address
   on the main **Wireless debugging** screen with `adb connect`.
4. Select the shared **Riders Hub - Watch** run configuration and the watch in
   Android Studio's target-device menu, then click **Run**.

Android Studio builds `:wear:assembleDebug`, installs the watch APK, and starts
the launcher activity. To test real phone-to-watch data, install `:app` on the
paired phone from the same checkout so both debug APKs use the same signing
certificate. Wear OS 3 and newer does not support ADB debugging through the
phone's Bluetooth connection; deployment requires a direct Wi-Fi or supported
USB ADB connection to the watch.

During an active ride, the watch posts a low-priority Ongoing Activity after
notification permission is granted. This keeps the dashboard available through
the system's second inactivity timeout. Ambient mode redraws only on the Wear OS
minute tick, buffers faster phone updates between ticks, and shows only current
trip kilometres, board battery, and estimated kilometres remaining. Devices
that request burn-in protection receive a small four-position layout shift.

### Wear emulator telemetry

The debug Wear APK includes an ADB-only synthetic telemetry receiver. It is
protected by Android's `DUMP` permission and is not compiled into release
artifacts. With the debug APK running on a watch emulator, inject a live sample:

```bash
adb -s WATCH_SERIAL shell am broadcast \
  -n at.themrcodes.ridershub/at.themrcodes.ridershub.wear.SyntheticTelemetryReceiver \
  -a at.themrcodes.ridershub.wear.DEBUG_TELEMETRY \
  --es connection LIVE \
  --ef speed_kmh 24.5 \
  --ei battery_percent 78 \
  --ef trip_km 4.25 \
  --ef estimated_range_km 12.75 \
  --es mode SPORT
```

Use `RECONNECTING` for `connection` to verify retained-but-dimmed values, add
`--el age_ms 60000` to a `LIVE` sample to verify the stale-update state, or
send `--ez clear true` to restore the waiting-for-phone state.

## Repository layout

- [`app/`](app/) contains the Android application, resources, and JVM tests.
- [`wear/`](wear/) contains the Wear OS dashboard.
- [`wear-shared/`](wear-shared/) contains the versioned, privacy-minimized
  phone-to-watch telemetry contract.
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
