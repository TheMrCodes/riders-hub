# Riders Hub Android app

Riders Hub is a flight recorder and local dashboard for electric skateboards.
Its current hardware adapter supports the Backfire BLE interface documented in
[`ble-api.md`](ble-api.md). Normal operation is notification-only. Version 0.3
also contains a hidden, manually confirmed control for two exact child-limiter
commands identified during protocol research; it never sends a command
automatically.

## Lifecycle and power model

Version 0.6 supports Android 14 and newer (`minSdk` 34, `targetSdk` and
`compileSdk` 36). Long-term BLE detection is delegated to Android's Companion
Device Manager on every supported version:

```text
one-time BF_* association
        |
Android 14/15: address observation + AssociationInfo callbacks
Android 16+: association-ID observation + DevicePresenceEvent callbacks
        |
BLE appeared / BT connected -> CompanionDeviceService -> GATT F1F2 -> JSONL
        |
all transports gone -> close connection segment -> stop GATT work
        |
reconnect within 2 min -> append another segment to the same logical ride
        |
grace expires -> one-shot alarm appends session_end and stores track summary
```

API 36 presence types are isolated in API-36-only classes. Android 16+ uses
`ObservingDevicePresenceRequest.setAssociationId()` and
`onDevicePresenceEvent(DevicePresenceEvent)`. Android 14/15 registers the
associated BLE address and receives the API-33
`onDeviceAppeared(AssociationInfo)` / `onDeviceDisappeared(AssociationInfo)`
callbacks. SDK-qualified boolean resources enable exactly one primary
`CompanionDeviceService`, preventing Android 14/15 from loading classes whose
method signatures contain API 36 types.

Android 16 can distinguish BLE advertising and Bluetooth-link events, so that
service tracks those transports separately. Android 14/15 provides a combined
nearby/gone signal; the existing GATT state and 12-second settle window protect
the active capture when callback and connection state arrive out of order.

The app does not keep an activity, periodic job, custom BLE scan, wake lock, or
permanent foreground notification running. It also avoids RSSI polling, MTU
negotiation, and high-priority connection mode. GATT setup has explicit
deadlines for connecting, service discovery, and F1F2 notification
subscription. A missing callback or rejected CCCD request closes that GATT
client and starts a fresh, bounded retry. Retries back off from 2 to 60 seconds.
A 12-second transport-settle window absorbs callback ordering. Ride
finalization uses `AlarmManager.setAndAllowWhileIdle`; it does not retain a
process, job, or wake lock during the two-minute grace period.

Live dashboard state is written at most once per second, ride summaries at most
once per five seconds, and raw output flushes every 100 lines or five seconds.
The Compose dashboard polls only while the activity is visible.

The dashboard's **Current Trip** distance is integrated locally from speed and
time so short reconnects remain part of one logical session. Its **Moving**
counter includes only intervals where the board is moving, rather than all
wall-clock time since the session began. The large total under **Rides** is the
separate odometer reported by the remote. Its last valid value persists across
log files, service restarts, and reconnects, and is cleared when a different
remote is paired.

## Permissions and privacy

The manifest contains five permissions:

- `BLUETOOTH_SCAN`, marked `neverForLocation`
- `BLUETOOTH_CONNECT`
- `REQUEST_COMPANION_RUN_IN_BACKGROUND`
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`
- `POST_NOTIFICATIONS`, for the user-requested low-battery warning

It contains no Internet, location, storage, boot, wake-lock, exact-alarm, or
foreground-service permission. Backups are disabled, cleartext traffic is
disabled, and data-extraction rules exclude all app data.

Android may require the device-wide Location Services switch during companion
device discovery. This is a system scanner prerequisite; the app is not granted
a location permission and does not receive coordinates.

## Build and install

Use JDK 17 or newer with an Android SDK containing API 36:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `at.themrcodes.ridershub`. The APK installs on API
34 and newer while retaining API 36 as its compile/target level. Compose is
pinned to the API-36-compatible 1.11 BOM line rather than the API-37-requiring
1.12 line.

## One-time association

1. Turn on the board and remote and make sure the remote advertises as `BF_*`.
2. Turn on Bluetooth and Location Services on the Android device.
3. Open **Riders Hub** and tap **Pair supported remote**.
4. Grant **Nearby devices**, **Notifications**, and accept the remote in
   Android's chooser.
5. Confirm that the monitor reports presence observation as armed. Android
   14/15 reports address observation; Android 16+ reports association-ID
   observation.

After this, the UI need not remain open. Android persists the association and
presence observation. A different phone requires its own association.

## Log location and extraction

Each logical use session creates one UTF-8 JSONL file beneath:

```text
/storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry/
```

List and pull all sessions from an authorized development device:

```bash
adb shell ls -l /storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry
adb pull /storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry ./riders-hub-telemetry
```

Uninstalling the app removes this app-specific directory. Pull logs before an
uninstall or **Clear storage** operation.

## JSONL schema

Every line is one JSON object with this common envelope:

| Field | Meaning |
| --- | --- |
| `seq` | Monotonic record number within the file |
| `type` | Event type listed below |
| `wall_time` | UTC ISO-8601 timestamp |
| `elapsed_realtime_ns` | Monotonic Android boot-time timestamp |

For schema versions 2 and 3, the first line is `session_start`. Each technical GATT
connection is bracketed by `connection_segment_start` and
`connection_segment_end`. The final `session_end` is appended only after the
two-minute reconnect grace expires. Existing schema-version-1 captures remain
valid and unchanged. Schema 3 adds the explicit child-limiter command events.

| Event | Important event-specific fields |
| --- | --- |
| `session_start` | schema/app version, logical session ID, device, write policy, grace |
| `connection_segment_start` | session ID, segment number, resumed flag |
| `presence_appeared` | associated BLE address |
| `gatt_connect_attempt` | attempt, address, `auto_connect`, transport |
| `gatt_stage_changed` | current setup phase and its deadline |
| `gatt_stage_timeout` | setup phase that stopped producing Android callbacks |
| `gatt_connection_state` | numeric status and connection state |
| `service_discovery_requested` | whether Android accepted discovery |
| `gatt_services_discovered` | services, characteristics, properties, descriptors |
| `notification_setup` | F1F2 local/CCCD setup state |
| `cccd_write_requested` / `gatt_descriptor_write` | Android and GATT results |
| `telemetry_listening` | notification subscription is active |
| `notification` | characteristic, byte count, exact raw `value_hex` chunk |
| `decoder_discard` | reason and exact bytes discarded during resynchronization |
| `telemetry_frame` | raw frame, decoded fields, unknown ranges, CRCs and validity |
| `gatt_reconnect_scheduled` | reason, attempt, delay |
| `stale_gatt_callback_ignored` | late callback from a GATT client already replaced by a retry |
| `protocol_error` / `fatal_error` | nonrecoverable connection problem |
| `application_command_requested` | confirmed user request, operation, exact bytes, evidence level |
| `application_command_write_result` | Android GATT completion status; not treated as device confirmation |
| `application_command_confirmed_by_telemetry` | requested state observed in a valid mode/status frame |
| `application_command_rejected` / `application_command_not_confirmed` | local rejection or eight-second timeout |
| `connection_segment_end` | reason and per-connection notification/frame/CRC totals |
| `session_end` | logical-ride distance, time, battery, voltage, frames and segments |

A typical decoded record has this shape (values are illustrative):

```json
{"seq":8,"type":"telemetry_frame","wall_time":"2026-08-25T10:00:00Z","elapsed_realtime_ns":123456789,"frame_number":1,"raw_hex":"ac 00 19 ...","frame_length":25,"mode_code":2,"mode":"Sport","board_battery_percent":20,"speed_candidates_kmh":[0,0],"speed_kmh":0,"pack_voltage_v":41.545,"load_raw_signed_be":7,"trip_km":0,"odometer_km":2355.8,"unknown":{"byte_1":"00","byte_3":"01","bytes_12_15":"00 07 00 00","bytes_21_22":"80 00"},"crc_expected":53215,"crc_actual":53215,"crc_valid":true}
```

`notification` preserves original BLE chunk boundaries and `telemetry_frame`
preserves reconstructed bytes. Later analysis can revisit unknown fields without
depending on the current decoder's interpretation.

## Track summaries and range calibration

A finalized session becomes a visible track after either 0.02 km of integrated
distance or 10 seconds of motion. Smaller remote-on/off captures keep their raw
JSONL but do not clutter ride history.

- Distance uses trapezoidal integration of decoded speed and only CRC-valid
  adjacent frames separated by at most two seconds. Unknown gaps are not
  guessed.
- Motion time uses those safe intervals with a 1 km/h threshold.
- Stationary voltage points are sampled at 0.5 km/h or below when percentage
  changes or every ten minutes.
- A personal voltage-versus-percentage regression can resolve useful depletion
  below the telemetry field's one-percent resolution.
- The estimate remains **Collecting data** until at least 1 km and 2% useful
  depletion exist. It is **Calibrated** only after at least 5 km and 10%;
  between those thresholds it is labelled **Provisional**.
- Efficiencies outside 0.03–1.5 km per percentage point are rejected rather
  than displayed.

This is an empirical estimate for the captured rider, board, route,
temperature, tires, and riding style—not a guaranteed safe range.

## Battery warnings

With notification permission granted, the app sends one board warning per
logical session when `board_battery_percent <= 20` and the frame also contains
a plausible live pack voltage. The known G3 telemetry/API does not expose a
separately identified remote-battery field, so remote battery is shown as **Not
decoded** and no remote warning is fabricated. Raw captures should identify
that field before such an alert is added.

## Hidden child-limiter control

Tap **Version 0.6.0** seven times at the bottom of **App** on the **Device** page to
unlock **Experimental remote control**. Unlock state is local to the app. The
switch remains disabled until the remote exposes F1F1, F1F2 subscription is
ready, and a valid telemetry mode has been decoded.

Every change opens a safety confirmation. The board must be stationary and its
wheels clear. Disabling the limiter can restore full board speed. The app then
sends exactly one of these F1F1 writes with Android's acknowledged/default GATT
write type:

| Requested state | Bytes |
| --- | --- |
| Enable | `AC A0 01 08 21` |
| Disable | `AC A0 00 C9 E1` |

A successful `onCharacteristicWrite` means only that the GATT write completed.
The UI continues to show a pending state until a CRC-valid telemetry frame
reports byte 4 as `0x81` for enabled or a different mode for disabled. It times
out after eight seconds without claiming success. Only one command may be in
flight. The commands are protocol-research findings but remain unverified on
the physical G3 until the user deliberately tests them; implementation, build,
and installation do not send either command.

The child limiter's exact speed ceiling is not present in the known telemetry
frame or command. It should be measured in a controlled wheels-clear test rather
than assumed to be 10 km/h.

## Current verification boundary

- Twenty-five JVM tests pass: six protocol, four session-continuity, three
  range-estimator, three GATT-deadline, three Bluetooth-address, three
  board-data-validity, and three presence-compatibility tests.
- The debug APK assembles and Android lint completes with zero errors.
- Version 0.1 was installed and associated on the Nothing `A059`, Android
  16/API 36. Nineteen presence-triggered JSONL sessions were pulled; all 1,331
  reconstructed frames passed CRC validation.
- That capture exposed the deprecated address-observation lifecycle defect in
  [`capture-analysis-2026-08-25.md`](capture-analysis-2026-08-25.md). Later
  versions replace that lifecycle and have been deployed during UI work.
  Version 0.5.4 adds GATT timeout recovery, current-runtime presence state,
  canonical Android Bluetooth-address handling, and recovery from a companion
  service rebind without a replayed appearance event. It was installed in place
  on the Android 16 Nothing A059 and reached F1F2 listening after automatically
  recovering one transient GATT status 133. The verification capture contains
  645 CRC-valid frames; nonzero board fields still require a board-powered
  check. Supporting protocol and capture evidence is recorded in the BLE API
  notes and capture analysis.
- Version 0.6.0 clean-builds as a minSdk-34 APK, and Android lint reports no
  unguarded API-above-34 calls. Its packaged resources contain the expected
  default legacy and `v36` modern service selection. The Android 16 target went
  offline before this build could be installed, so both that regression and a
  physical Android 14 background appear/disappear test remain pending; build
  and static compatibility are not substitutes for OEM-specific lifecycle
  testing.
