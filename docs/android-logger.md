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

The manifest contains six permissions:

- `BLUETOOTH_SCAN`, marked `neverForLocation`
- `BLUETOOTH_CONNECT`
- `REQUEST_COMPANION_RUN_IN_BACKGROUND`
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`
- `POST_NOTIFICATIONS`, for the user-requested low-battery warning
- `INTERNET`, used only when the optional Home Assistant export is enabled

It contains no location, storage, boot, wake-lock, exact-alarm, or
foreground-service permission. Backups are disabled and data-extraction rules
exclude all app data. The app permits cleartext networking so a user-selected
local Home Assistant instance can use its common `http://*.local:8123` address,
but input validation rejects plain HTTP for non-local hosts.

Android may require the device-wide Location Services switch during companion
device discovery. This is a system scanner prerequisite; the app is not granted
a location permission and does not receive coordinates.

## Optional Home Assistant export

The Device panel contains an off-by-default Home Assistant section. Its setup
flow is intentionally split into two states:

1. Enable the section.
2. In the Home Assistant Companion app, navigate to **Sidebar → Profile →
   Security → Long-Lived Access Tokens → Create Token**, then copy the token.
3. Enter the Home Assistant base URL and token and select **Connect**.
4. Riders Hub sends one authenticated `POST` to
   `/api/mobile_app/registrations`, then registers five telemetry entities
   through the returned webhook.
5. After success, the base URL and token disappear and are not persisted. The
   webhook URL remains editable while the Home Assistant encryption key stays
   hidden. Home Assistant may initially supply a Nabu Casa cloudhook, a remote
   URL, or a local webhook; a user-managed HTTPS proxy such as
   `https://ha.example.com/api/webhook/…` can replace it.

The registration uses a generated app-local UUID rather than a hardware or
Android device identifier. The registered device is generically named
**Riders Hub / Android device**. The retained webhook URL is a bearer credential.
It and the Home Assistant encryption key are encrypted with a non-exportable
Android Keystore key before being stored in private preferences excluded from
backup and device transfer. The long-lived token is held only for the
registration request and is never logged or written to preferences.

Registration declares `supports_encryption: true`. Riders Hub uses Home
Assistant's XSalsa20-Poly1305 SecretBox wire format for every webhook data
payload and decrypts encrypted sensor responses before accepting a delivery as
successful. The outer operation type and routing metadata are not hidden by
this application-level encryption.
An existing registration created by an older Riders Hub build is upgraded with
Home Assistant's one-time `enable_encryption` command before further telemetry
is sent. If that key cannot be retained, delivery stays paused and the UI asks
the user to reconnect instead of falling back to plaintext.

The integration exposes:

- board battery percentage, also delivered immediately when its integer value
  changes;
- estimated remaining range in kilometres;
- current logical-trip distance, delivered with battery and estimated range
  every ten seconds while in use, alongside battery changes, and once more at
  ride end;
- the last update timestamp;
- an in-use binary sensor plus `riders_hub_trip_started` and
  `riders_hub_trip_ended` events.

Home Assistant may also create the standard device tracker that accompanies a
`mobile_app` registration. Riders Hub has no location permission and never
submits coordinates, so this tracker remains without location data and is not
one of the five telemetry entities above.

It does not send speed, voltage, odometer, mode, remote name/address, ride ID,
raw frames, or logs. Turning the section off pauses delivery without forgetting
the connection. **Disconnect** forgets the local webhook; removing the mobile
app registration from Home Assistant remains a separate action in Home
Assistant because its webhook API has no registration-deletion command.

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

Each active logical session creates one temporary UTF-8 JSONL file beneath:

```text
/storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry/
```

List and pull all sessions from an authorized development device:

```bash
adb shell ls -l /storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry
adb pull /storage/emulated/0/Android/data/at.themrcodes.ridershub/files/telemetry ./riders-hub-telemetry
```

After `session_end` is written, Riders Hub appends the exact JSONL bytes to the
current binary working partition beneath `telemetry/archive/`. Working files use
generic sequenced names such as `telemetry-000001.rhp`; device names and
addresses are not copied into filenames. Existing completed entries are never
rewritten. A single working partition belongs to one remote and stores its MAC
once in the partition header.

When the working partition reaches the 10 MiB target, or the connected remote
changes, its complete contents are compressed as one unit and finalized under
the same sequence, for example `telemetry-000001.rhp` becomes the immutable
analytics package `telemetry-000001.rha`. The next write starts
`telemetry-000002.rhp`. A single unusually large session may make a working
partition exceed the target before it is finalized.

The JSONL source is deleted only after its appended entry has been read back and
its byte length, record count, and checksums verified. If that append finalizes
the partition, the complete compressed package is also expanded and verified
first. An incomplete trailing binary entry is truncated and ignored; all earlier
completed entries remain valid. An incomplete, malformed, or mismatched source
JSONL remains in place. On startup the app retries finalized JSONL files left by
an older version or an interrupted cleanup. Temporary active logs are never
migrated.

Uninstalling the app removes this app-specific directory, including archives.
Pull the complete `telemetry/` directory before an uninstall or **Clear storage**
operation. These files contain private device and activity telemetry even though
the partition filenames themselves are generic.

### RHP/RHA archive format version 1

All integers are big-endian. An append-only `.rhp` working partition starts with
a fixed 24-byte `RHP1` header:

| Field | Size | Meaning |
| --- | ---: | --- |
| `RHP1` magic | 4 bytes | Working-partition marker |
| format version | 4 bytes | Partition schema version |
| header length | 4 bytes | `24` for version 1 |
| sequence | 4 bytes | Monotonic partition index |
| remote MAC | 6 bytes | Remote associated with every entry in this partition |
| reserved | 2 bytes | Reserved for future metadata |

The MAC appears once per partition, not once per entry. The header is followed
by direct, uncompressed session appends. Each append contains:

| Field | Size | Meaning |
| --- | ---: | --- |
| `RHE1` magic | 4 bytes | Start of one session entry |
| format version | 2 bytes | Entry schema version |
| header length | 2 bytes | `48` for version 1 |
| entry ID | 16 bytes | Truncated SHA-256 of the local session ID |
| payload length | 8 bytes | Exact original JSONL byte count and payload boundary |
| record count | 4 bytes | Number of JSONL objects |
| source CRC32 | 4 bytes | Integrity check over the original JSONL bytes |
| archived at | 8 bytes | UTC epoch milliseconds when the entry was appended |
| payload | variable | One complete original JSONL session, stored unchanged |

Every payload ends with a fixed 32-byte `RHC1` completion footer. `RHC1` simply
means **Riders Hub Commit, version 1**: it repeats the entry ID and committed
header-plus-payload length, then stores a CRC32 over those bytes. It is not a
second archive and contains no remote MAC. Its purpose is to distinguish a fully
written append from bytes left when a write stops midway. Such an incomplete
tail is removed before the next append.

A finalized `.rha` file starts with a fixed 56-byte `RHA1` package header. It
contains the format version, header length, unchanged partition sequence, the
single remote MAC, seal time as UTC epoch milliseconds, original partition
length, compressed payload length, entry count, and CRC32 of the complete
uncompressed `.rhp` bytes. The remainder is one DEFLATE stream containing that
whole partition. The package is expanded and scanned after creation; once
verified it becomes the immutable representation of that sequence and the next
sequence is used for new entries. The MAC remains private local telemetry and is
never used in the filename or a Home Assistant payload.

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
| `session_end` | logical-ride distance, 5 km/h speed-bucket distances, time, battery, voltage, frames and segments |

A typical decoded record has this shape (values are illustrative):

```json
{"seq":8,"type":"telemetry_frame","wall_time":"2030-01-01T10:00:00Z","elapsed_realtime_ns":123456789,"frame_number":1,"raw_hex":"ac 00 19 ...","frame_length":25,"mode_code":2,"mode":"Sport","board_battery_percent":20,"speed_candidates_kmh":[0,0],"speed_kmh":0,"pack_voltage_v":41.545,"load_raw_signed_be":7,"trip_km":0,"odometer_km":123.4,"unknown":{"byte_1":"00","byte_3":"01","bytes_12_15":"00 07 00 00","bytes_21_22":"80 00"},"crc_expected":53215,"crc_actual":53215,"crc_valid":true}
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
- Each integrated interval is assigned by its average speed to a durable 5 km/h
  bucket (`0` means 0–<5 km/h, `5` means 5–<10 km/h, and so on). Each track
  stores the kilometres travelled in every observed bucket.
- Motion time uses those safe intervals with a 1 km/h threshold.
- Stationary voltage points are sampled at 0.5 km/h or below when percentage
  changes or every ten minutes.
- Range calibration is board-scoped and accumulates distance across rides until
  the rounded board battery has fallen by at least five percentage points. A
  short ride with no displayed percentage change therefore remains part of the
  active window instead of being discarded. A battery increase of at least five
  points at the start of a new logical ride resets an incomplete window as an
  inferred recharge; voltage rebound during one ride does not.
- Completed 5% windows form equations relating battery depletion to distance in
  each speed bucket. A regularized non-negative model expresses each bucket as
  battery percentage consumed per 100 km. This is a percentage-based empirical
  measure, not measured Wh or battery capacity.
- The common riding profile is the distance-weighted 5 km/h bucket distribution
  of the newest 100 km. The remaining-range estimate weights the learned bucket
  consumption rates by that profile between rides. During an active ride, the
  forecast instead uses that ride's accumulated bucket mix so it can adapt to
  the current pace. The oldest boundary ride and depletion window are
  proportionally trimmed when the rolling window crosses 100 km.
- Ride retention is distance-based: enough newest tracks to cover 100 km are
  kept, with a 1,000-track safety cap. Existing history is assigned to a board
  and used to seed 5% windows only when that association is unambiguous.
- The estimate remains **Collecting data** until the first usable 5% window and
  at least 1 km exist. It is **Calibrated** after at least 20 km and 10% observed
  depletion; between those thresholds it is labelled **Provisional**.
- The bar below the Range value visualizes estimator readiness, not battery or
  remaining distance. Before an estimate exists, distance progress toward 1 km
  and depletion progress toward 5% each contribute half. Afterwards, observed
  distance up to 100 km contributes 60% and depletion up to 20% contributes 40%.
- Efficiencies outside 0.03–1.5 km per percentage point are rejected rather
  than displayed.

This is an empirical estimate for the captured rider, board, route,
temperature, tires, and riding style—not a guaranteed safe range.

### Charge-cycle analysis foundation

Riders Hub also keeps local, durable charge-to-charge observation summaries for
range-retention analysis. The **Battery Longevity** section appears between
Range and Rides; none of this data is exported to Home Assistant.

- Charger state is not exposed by the known protocol. A new observation window
  is therefore marked as **inferred** when the next recorded track starts at
  least five battery percentage points above the preceding track's final value.
- Each window retains its first, last, minimum, and maximum battery readings;
  resting-voltage endpoints and extrema; recorded distance and motion time; the
  accumulated 5 km/h speed-bucket profile; odometer endpoints; and ride count.
- The odometer span is kept separately from app-integrated distance. A future
  estimator can reject a window when unrecorded riding makes its speed profile
  incomplete instead of treating missing kilometres as low energy use.
- Windows are separated by a local pseudonymous board key so observations from
  different paired devices cannot be combined. Raw addresses are not copied
  into the charge-cycle summaries.
- Up to 500 completed windows are retained independently of the shorter visible
  ride history. Collection starts prospectively; older ride summaries are not
  backfilled because they do not contain a durable board key.
- During a valid board session, one voltage correlation sample is taken every
  ten seconds. Samples are compacted within each ride by battery percentage,
  5 km/h speed bucket, and resting/moving state before they are written to a
  local SQLite history and associated with the inferred charge window.
- The compact history retains voltage, speed, and the still-unidentified signed
  load field as counts, sums, squared sums, cross-product sums, and extrema.
  This supports later variance, covariance, regression, and voltage-sag studies
  without retaining every roughly 120 ms BLE notification in the database.
- Observation timestamps, odometer/distance bounds, and ride/cycle identifiers
  preserve evolution over time. The database contains only the pseudonymous
  local board key, never the raw Bluetooth address, and is not exported.

These summaries are sufficient statistics for fitting battery depletion per
speed bucket over successive time windows and comparing every window against a
fixed reference riding profile. They deliberately do not claim measured Ah/Wh
capacity or definitive battery state of health.

The visible estimator accepts a charge window only after at least 0.5 km, 5%
depletion, and 90% coverage by recorded speed buckets. It fits depletion per
kilometre for each observed 5 km/h bucket, constructs one reference riding
profile, and translates every accepted window into the full-charge range that
window would provide under that same profile. This prevents a change in riding
speed alone from being presented as battery degradation. The headline is a
depletion-weighted average of the latest three usable observations; it remains
explicitly provisional until multiple observations exist.

The capacity chart starts with daily buckets and always uses zero as its minimum
and the all-time observed high as its maximum. Pinching inward groups the data
by week, month, and then year. Tapping a grouped bar drills into that interval at
the next finer time unit; tapping a daily bar selects its value. Each time bar is
a depletion-weighted average when it contains more than one observation.

## Battery warnings

With notification permission granted, the app sends one board warning per
logical ride when `board_battery_percent` is at or below the configured warning
threshold and the frame also contains a plausible live pack voltage. When the
ride is finalized after the reconnect grace period, the final valid board frame
can produce a second recharge reminder if no board warning was delivered in the
previous two hours. The known G3 telemetry/API does not expose a separately
identified remote-battery field, so remote battery is shown as **Not decoded**
and no remote warning is fabricated. Raw captures should identify that field
before such an alert is added.

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

The debug app is covered by focused JVM tests for protocol decoding, lifecycle
continuity, range and longevity estimation, charge-window tracking, voltage
statistics, Home Assistant payloads and cadence, URL validation, and telemetry
archive recovery. Current verification uses the complete JVM test task, Android
lint, and a debug assembly. Device testing has also covered registration,
encrypted webhook delivery, BLE reconnection, and current Android adaptive-icon
behavior.

Build and static compatibility checks do not replace physical background
appear/disappear testing across Android versions and OEM power-management
implementations. Battery longevity is prospective and model-based; its quality
will improve only as representative charge observations accumulate.
