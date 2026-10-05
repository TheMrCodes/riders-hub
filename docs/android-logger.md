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

The manifest contains the companion/Bluetooth permissions plus:

- `BLUETOOTH_SCAN`, marked `neverForLocation`
- `BLUETOOTH_CONNECT`
- `REQUEST_COMPANION_RUN_IN_BACKGROUND`
- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`
- `POST_NOTIFICATIONS`, for the user-requested low-battery warning
- `INTERNET`, for the optional self-hosted API
- coarse, fine, and background location, requested only for opt-in ride GPS
- foreground-service and location foreground-service permission for continuous ride fixes

It contains no storage, boot, wake-lock, or exact-alarm permission. Backups are
disabled and data-extraction rules exclude all app data. Cleartext networking is
permitted for user-selected self-hosted servers only after explicit confirmation.

Android may require the device-wide Location Services switch during companion
device discovery. This is a system scanner prerequisite; the app is not granted
a location permission and does not receive coordinates.

## Optional self-hosted API

The Device panel contains a self-hosted API section. For HTTPS servers it loads
`GET /v1/auth/config`, opens the configured OIDC provider in the browser with
Authorization Code + PKCE, and exchanges the access token at
`POST /v1/devices/registrations`. Only the resulting source-bound device credential is persisted;
the base URL and credential are protected using a non-exportable Android Keystore AES-GCM key.
Discovery at
`GET /v1/capabilities` enables metrics, realtime, and analytics independently. Redirects are
disabled for API HTTP requests.

Metrics snapshots run at ten-second cadence while the ride process is active;
lifecycle events are retained in SQLite and retried with their original identity.
The realtime socket sends `stream.opened` followed only by latest-frame telemetry
at a maximum of one hertz. Analytics uploads complete RHA packages after a
separate consent disclosure and retain destination-scoped acknowledgements.

GPS is available only when advertised by realtime capability and requires
precise plus background access. Its foreground service starts at the first valid
ride frame, continues through reconnect grace, and writes full `location_fix`
records into the ride-owned serialized log. See [the public contracts](api/implementer-guide.md).

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
never used in decoded API payloads.

## JSONL schema

Every line is one JSON object with this common envelope:

| Field | Meaning |
| --- | --- |
| `seq` | Monotonic record number within the file |
| `type` | Event type listed below |
| `wall_time` | UTC ISO-8601 timestamp |
| `elapsed_realtime_ns` | Monotonic Android boot-time timestamp |

For schema versions 2 through 5, the first line is `session_start`. Each technical GATT
connection is bracketed by `connection_segment_start` and
`connection_segment_end`. The final `session_end` is appended only after the
two-minute reconnect grace expires. Existing schema-version-1 captures remain
valid and unchanged. Schema 3 adds the explicit child-limiter command events.
Schema 4 adds frame-aligned battery estimation diagnostics. Schema 5 changes writer ownership
from a BLE segment to the logical ride and adds versioned `location_fix` records. Connection starts also
include `telemetry_schema_version`, so an older session resumed after an app update
can identify the new per-frame format without rewriting its original start record.

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
| `telemetry_frame` | raw frame, decoded fields, unknown ranges, CRCs, validity, post-frame estimated battery and diagnostic context |
| `location_fix` | schema version, coordinates, altitude/accuracy/motion/bearing metadata when available, provider, mock flag, fix and receipt clocks |
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

### Frame-aligned battery estimates (schema 4)

Every `telemetry_frame` contains `estimated_battery_percent` and a
`battery_estimation` object. The estimate is the full-precision result produced
while processing that exact frame, before publishing its UI state. It is not a
periodically sampled value or the previous frame's estimate. Raw BLE fields and
the estimate share the same `seq`, `frame_number`, `wall_time`, and
`elapsed_realtime_ns`. The two clocks are captured at decoded-frame handling entry;
the same wall clock and monotonic milliseconds are passed into ride integration.
They describe phone reception/processing, not an unavailable board sensor clock.

The diagnostic object records:

- `schema_version` (2) and `algorithm` (`persistent-observer-v2`).
- `status`: `initialized`, `updated`, `held_discontinuity`,
  `propagated_inferred_gap`, `invalid_crc`,
  `invalid_board_telemetry`, `no_active_ride`, or `processing_error`.
- `estimated_percent`, `previous_percent`, `previous_reference_percent`, and
  the reference actually used (`reference_percent`).
- `candidate_percent`, `candidate_seconds`, `observation_confirmed`,
  `settled_seconds`, `settled_voltage_v`, and `settled_observation_used`.
- `reacquiring_used` for the current update and `reacquiring_after` for the next.
- `elapsed_seconds`, `interval_distance_km`, `speed_bucket_kmh`,
  `base_percent_per_km`, and `applied_percent_per_km`.
- `estimated_gap_speed_bucket_distances_km`, `inferred_gap_distance_km`,
  `inferred_gap_base_percent_per_km`, `inferred_gap_applied_percent_per_km`,
  and `inferred_gap_consumption_percent` for a reconstructed driving transition.
- `consumption_percent` and signed `observation_correction_percent`: for a
  retained valid estimate, previous minus consumption plus correction equals
  the new estimate.
- `learning_before` and `learning_after`: immutable snapshots of the correction
  factor, learned fallback rate, accepted-window count and last learning error.

A valid first frame after a gap does not count offline time as observation time.
If the odometer gap is below 2 km, has no charge-sized battery change, and a
board consumption model exists, the estimator advances through that observed
distance using the frozen inferred speed profile. The transition applies no
observation correction and is archived as `propagated_inferred_gap`. With no
supported model, a hard gap, or a charge-associated gap, the estimate is held.
Invalid or unprocessed frames record null estimated values and
an explicit status; they do not reuse a stale estimate and label it as current.
Unexpected processing errors preserve the raw frame with `processing_error` and
`battery_processing_error_type` (exception class only), then retain normal error
propagation. Missing rates are null, not zero consumption-rate measurements.

The existing archive stores original JSONL bytes, so working partitions and sealed
`.rha` packages retain all these fields without a binary archive format change.
Final `session_end` still records the final estimate. Older captures do not contain
these new per-frame estimates and are not backfilled. Diagnostics are stored in
the normal private telemetry archive; no separate analytics upload is introduced.

### Android emulator telemetry checks

Debug phone builds include `EmulatorTelemetryReceiver`, protected by
`android.permission.DUMP` and additionally restricted to Android emulator hardware
(`ranchu` or `goldfish`). It is absent from release manifests. It feeds synthetic
20+5-byte chunks through the normal notification logger, decoder, estimator and
archive path without opening a GATT connection. This verifies application handling,
not the Bluetooth radio or Companion Device presence callbacks.

With the debug app open in a phone emulator, explicit ADB broadcasts can start a
synthetic session and send frames. The built-in board addresses and label are
synthetic; the optional `board=2` selects a second isolated test board.

```sh
adb -s emulator-5554 shell am broadcast -n at.themrcodes.ridershub/.EmulatorTelemetryReceiver --es command start
adb -s emulator-5554 shell am broadcast -n at.themrcodes.ridershub/.EmulatorTelemetryReceiver --es command frames --ei count 40 --ei battery_percent 60 --ef speed_kmh 18 --ef voltage_v 44 --ei load_raw 7
adb -s emulator-5554 shell run-as at.themrcodes.ridershub cat files/emulator-telemetry-status.json
adb -s emulator-5554 shell am broadcast -n at.themrcodes.ridershub/.EmulatorTelemetryReceiver --es command stop
```

Frames run at approximately 100 ms intervals. Wait for `batch_complete` before
starting another batch. `invalid_crc=true` tests rejection; `voltage_v=0` tests
unavailable board telemetry. `stop` flushes the log and preserves reconnect grace.
`finalize` also advances the finalization check past that grace period for testing
the normal archive path immediately. Keep emulator captures separate from actual
ride analysis. The inlet does not inject a learned consumption rate; a new test
board initially has no consumption model, just like a new real board.

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
  active window instead of being discarded. A battery increase of at least ten
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
Range and Rides; detailed model state is not part of the metrics or realtime contract.

- Charger state is not exposed by the known protocol. Live observation windows
  follow the per-board continuity detector described below, independently of
  ride boundaries. Charge inference requires a sustained rise of at least ten
  percentage points.
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

Lifetime age is shown separately from **observed charges**, in estimated
full-battery equivalents. For each board, the qualified windows determine an
empirical full-charge distance: `100 × recorded km / depleted percentage
points`. Lifetime odometer divided by that distance estimates lifetime full
cycles; the earliest retained starting odometer estimates cycles before retained
history. Unrecorded gaps and history removed by retention remain included in the
lifetime odometer. Observed recharge event counts are never added to this total:
a partial top-up is not a full cycle. Capacity observations also expose their
estimated lifetime cycle position for later comparisons.

This is a distance-based proxy, not measured energy throughput or a prediction
of remaining battery life. It assumes the original battery and a historical
riding profile similar to this board's recorded mix. Recalibration can revise
historical cycle positions. Without qualified consumption data, complete finite
odometer endpoints, or a nondecreasing counter, lifetime age remains unknown.
The estimate is derived from the existing durable per-board summaries, so it
survives restart without storing another mutable counter. Legacy charge windows
with inferred rises below ten points are excluded from capacity fitting and the
visible charge count; stored summaries and archives remain unchanged.

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
statistics, API payloads and cadence, URL validation, and telemetry
archive recovery. Current verification uses the complete JVM test task, Android
lint, and a debug assembly. Device testing has also covered registration,
encrypted webhook delivery, BLE reconnection, and current Android adaptive-icon
behavior.

Build and static compatibility checks do not replace physical background
appear/disappear testing across Android versions and OEM power-management
implementations. Battery longevity is prospective and model-based; its quality
will improve only as representative charge observations accumulate.


### Continuous estimated battery percentage

The current-trip battery display uses a separate, locally estimated percentage,
marked **Est. battery** on the phone and with `~` on the watch. The raw remote
percentage is still retained in telemetry, warnings, and calibration inputs.

Each board remembers its own estimate and last confirmed reference independently
of rides. A new ride resumes that state; only a board without saved state initializes
from its first valid reading. Legacy ride estimates seed the state only for the
same board. Restarting the app preserves the estimate without integrating unseen
time or carrying over observation-confirmation timers.

A changed reading must persist for three seconds of contiguous telemetry before
it becomes the reference. Consumption interpolates within 2.5 percentage points
below that reference, tapering smoothly over the last point of this allowance.
It cannot keep draining the estimate farther while observations disagree. This
allowance is a conservative display policy, not a verified rounding rule or an
accuracy bound on the actual charge.

Outside reference +/-2.5 points, observations smoothly correct toward the nearest
edge. Normal correction has a five-second time constant and a limit of one point
per second. Three seconds of low raw input (absolute value <=50) and voltage within
0.2 V of a fixed recent sample shorten the time constant to three seconds. These
heuristics affect correction speed only; raw input is not a verified current
measurement, and voltage is not converted directly into charge.

After a disconnect/restart, a confirmed reading can correct obsolete saved state
faster: one-second time constant, limited to ten points per second, until within
2.6 points of the reference. This handles charging or use while disconnected
without snapping on the first packet. A large difference can temporarily remain
during confirmation and catch-up. Within the interpolation band, repeated readings
do not pull the estimate to its centre. Missing rates prevent consumption prediction
but not correction. Upward correction is not a measurement of recuperated energy.
Raw percentages remain available for warnings and telemetry.

Each board retains a separate adaptive model. Fully observed intervals between
downward transitions teach an empirical percentage-per-kilometre rate and a
correction factor for the existing speed-bucket model. The first partial plateau
is excluded. The observer must first confirm low controller input and voltage
stability; the transition must then remain reliable for another three seconds
before it can form a learning boundary. Distance boundaries remain at the original transition.
A rise or a gap discards the unfinished interval. Intervals need at least five
points of observed depletion and plausible distance/consumption. A completed
interval adjusts learned rates gradually (5% weight for the new observation).
One observation can request at most a 2x upward base-model correction, and a
standalone learned fallback rate remains inactive below three accepted intervals.
The saved model also retains its pre-correction prediction error. Those
parameters are initial safeguards, not a promise of monotonically improving
real-world accuracy. Sustained sag can still look like depletion in this protocol.

Only adjacent valid board observations at most two seconds apart advance the
prediction or learning window. Disconnects, invalid frames, and process recreation
reset coverage and the monotonic time anchor. A supported sub-2 km odometer gap
can advance the estimate through its inferred speed profile without counting
offline time as observation time; other gaps hold it. Estimated state and
learned parameters persist independently per board across rides and restarts. Completed
ride summaries and `session_end` retain the final estimated percentage. Existing
summaries without the optional field continue to use raw percentage. Older
smoothing recovery targets are ignored. Versioned raw-frame replay can reconstruct
the estimator state and learned parameters without modifying the archive.

Remaining range uses this same battery estimate instead of applying another raw
percentage/voltage blend. Consumption fitting and active-trip speed weighting
are unchanged in this increment; battery interpolation alone does not fix every
source of range variation. Existing calibration history is retained.

Legacy ride-summary fallback uses a ten-point endpoint threshold. On an idle
startup, stale analytics/model versions rebuild from verified local archives
using the continuous per-board detector. See historical analytics migration below.

### Replay archived rides in the Android emulator

The debug app can replay local `.rha` archives through the notification decoder,
ride store, battery observer, learning, logging, and live UI. Use the standard
library Python driver from the repository root:

```sh
# Build and install the debug APK on an already running emulator.
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# Inspect anonymous session indices; nothing is sent to the emulator.
python3 tools/replay_archive.py riders-hub-telemetry/archive --list

# Replay a selected excerpt at normal speed and wait for completion.
python3 tools/replay_archive.py riders-hub-telemetry/archive \
  --serial emulator-5554 --session 1 --offset 60 --duration 120 --wait

# Browse faster; omit --wait to return while playback continues in the app.
python3 tools/replay_archive.py riders-hub-telemetry/archive \
  --serial emulator-5554 --session 1 --speed 5

# Stop early and finalize the replay ride.
python3 tools/replay_archive.py --serial emulator-5554 --stop
```

Set `ANDROID_HOME` for your SDK as needed, or provide `--adb`. The driver accepts
one archive file or a directory, validates archive and entry checksums, orders
sessions chronologically, and defaults to the session with the most frames.
Selection offsets and durations are in recorded seconds. It sends only frame
bytes and relative delays to a fixed private file in the emulator app. Source
activity timestamps, Bluetooth identities, and archive metadata are not imported.
Real trip/odometer and telemetry values in the bytes remain local, including in
the emulator's newly generated logs; never commit these artifacts.

Playback uses a synthetic board slot (`--board 3` by default). Different slots
keep learned battery state separate; repeated use of a slot continues its
persisted state. Slots 1 and 2 are reserved for the synthetic smoke checks.
Starting another replay finalizes the previous emulator session. Completion
also finalizes the ride, making it available to history and charge-window
analysis. No app data is cleared and no historical estimator state is restored
from the source archive.

At `--speed 1`, original frame cadence is preserved approximately. Long gaps or
monotonic-clock resets become three-second discontinuities; original connection
events and offline waiting are not recreated. Frames are delivered in chunks of
at most 20 bytes through the real decoder. The app uses its current clock and
recomputes its estimates. Accelerated replay changes integrated distance,
settling and learning relative to the original ride; use it to inspect UI values,
not to score estimator accuracy or reproduce historical state exactly.

The receiver is debug-only, requires the system/ADB DUMP permission, and rejects
physical hardware. The driver also verifies the explicit emulator target.
Reader/selection regression tests use synthetic fixtures:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
```


### Continuous board calibration and information gaps

Live calibration, charge-window collection, and transient battery observation/
learning trackers now follow each board across UI rides. Opening or finalizing
a ride does not itself reset a consumption window or infer charging.
The per-board continuity checkpoint is durable; the battery estimate and learned
parameters continue to use their existing persistence. Transient estimator
confirmation restarts conservatively after a process restart.

The detector identifies missing valid samples (over two seconds), backward
clock intervals, invalid telemetry, odometer resets, and implausible counter
jumps. Frames are processed in delivery order. Equal millisecond timestamps preserve
observations and windows, adding zero elapsed time; they are not gaps. Logged
event sequences and frame numbers record processing order, while timestamps
supply duration. The odometer remains total-distance evidence, including unobserved
kilometres. Its 0.1 km resolution is allowed when checking adjacent counter
increments. Speed integration supplies the finer speed-bucket profile only
across observed intervals; arrival speed is never assigned to missing distance.
An interruption splits an unfinished range-calibration window when at least
2 km of odometer change or 10 battery percentage points of change occurred while
telemetry was missing (both thresholds inclusive). Smaller changes preserve the
window, even if an endpoint was moving, without integrating speed or confirmation
time across the pause. Known sub-threshold odometer distance is retained and its speed mix is estimated
from the board's common measured distribution, as described below. Measured
coverage remains distinct from coverage that includes inferred riding speed. Clock/counter discontinuities and missing older checkpoint
battery values remain conservative split cases. The board's learned model and
overall charge history remain intact.

A charge requires a rise of at least ten percentage points above the local
reference for thirty seconds of continuously observed telemetry. It can be
detected during the same connection or across rides. Offline time never counts
toward confirmation. A smaller rise or decrease that persists at the same reported value for thirty
seconds becomes the new reference. This prevents brief downward sag and separate
settled five-point rebounds from creating a charge against an old minimum. Pending charge evidence is withheld from consumption
fitting; confirmation starts a new charge window. These remain inferred events,
not direct charger measurements.

Each valid frame's `battery_estimation.continuity` object (schema 2) separates
the timing/validity interruption reason from the calibration-gap reason. It also
records odometer and battery increments, profiled and unprofiled interval distance,
pending-charge duration, and confirmed recharge increase. Ride summaries remain navigation/history units;
they are no longer appended a second time to live charge-cycle totals.
Old stored summaries are retained and the summary-based migration fallback
remains available for data predating live continuity collection.


### Estimated speed profiles for missing distance

For retained odometer gaps, the app allocates distance across the same 5 km/h
buckets using the board's most recent 100 km of measured ride profiles available
before that gap. Allocation is distance-weighted: a 1 km gap with a 60/40 profile
adds estimated distances of 0.6 km and 0.4 km. Different boards are filtered before
selecting history. With no suitable measured history, the distribution stays
unknown. Charge-associated or discarded gaps are not assigned across their
boundaries.

Range and charge summaries persist `estimated_speed_bucket_distances_km`
separately from measured `speed_bucket_distances_km`. Existing summaries read
with an empty estimated map. Per-frame battery diagnostics include the exact
`estimated_gap_speed_bucket_distances_km` used at that arrival. The allocation
is frozen at observation time, so later rides do not leak into historical
predictions, and inferred buckets never become the source for the common
measured profile.

Range fitting uses the combined measured/inferred mix when it covers a window,
weighting that observation by its measured-distance fraction. A wholly inferred
window does not train per-speed costs. Total distance remains usable for the
aggregate rate. Longevity continues to require 90% measured coverage; accepted
observations can use the combined mix for normalization. Imputation does not
turn missing samples into measured evidence or relax that admission criterion.

The live battery twin also consumes a retained gap's observed distance. Its
expected use is the sum of each inferred bucket distance times that bucket's
supported rate. Buckets without a supported rate use the board aggregate; if
neither is available, energy use remains unresolved and the SOC estimate is held.
This state transition does not teach the bucket model from its own inferred mix.


### Reported battery maxima and range retention over kilometres

Each board now persists its highest valid reported percentage, the first
timestamp/odometer where that maximum appeared, its highest percentage sustained
for 30 seconds, and monthly reported maxima (up to 240 months). The all-time
maximum remains independent of monthly/history retention. Existing saved
charge-window maxima seed the all-time value without inventing their exact
occurrence time, odometer, or sustained-reading evidence. Invalid telemetry or
a gap in observations interrupts sustained-reading confirmation. These are
remote-scale diagnostics: an observed maximum of 80% is never automatically
mapped to true 100% charge. Monthly maxima may reflect partial charging as well
as changes in remote calibration.

The maxima remain in frame-aligned battery diagnostics; they are not shown in
the compact Battery Longevity section. Odometer values
are now attached to capacity observations, allowing a relative range-retention
trend once there are six distinct, completed, usable charge windows spanning at
least 60 days, with at least 500 km between the median odometers of the first
three and latest three observations. These are initial data-quantity gates,
not guarantees of accuracy. Active unfinished charge windows cannot establish
the trend.

Retention is the median normalized range of the latest three observations
divided by the median of the first three, expressed as a percentage. The model
also exposes signed change per 1,000 km of comparison distance; it does not
clamp improvements to zero or assume every change is degradation. Backward
odometer movement in the qualified observations withholds the trend.

This estimates relative speed-normalized usable-range change from the earliest
retained baseline. Temperature, terrain, load, remote-scale calibration, and
battery replacement can affect it; odometer alone cannot isolate cell ageing or
recover capacity lost before recording began. Existing durable charge summaries
supply the trend after restart. The reported-percentage maxima are stored
independently per board and do not change battery interpolation or capacity
normalization.

### Cumulative speed-bucket statistics

`RideStore.speedBucketStatistics(localBoardId)` and the selected board's
`RideStoreSnapshot.speedBucketStatistics` expose three cumulative views:

- Measured kilometres and percentage of measured distance in each 5 km/h bucket.
- Estimated battery percentage points per 100 km, the equivalent full-battery
  range, and the modeled consumption share across measured riding. Consumption
  shares are conditional on buckets with a fitted rate; unsupported buckets
  remain null. These values describe reported battery depletion, not measured Wh.
- Measured kilometres as a simple confidence proxy, with inferred gap kilometres
  separate. Completed-window measured/inferred support and contributing-window
  counts are available for deeper analysis. Fits at either bound are flagged.

The versioned `board_speed_bucket_stats_v1` preference is saved in the same edit
as ride, continuity, and active calibration state. Totals accumulate across rides
and recharges and survive rolling history retention. Weighted regression cross
products and targets preserve consumption evidence without retaining an unbounded
list of depletion windows. Mixed windows use their measured fraction as weight;
inferred-only or insufficiently covered windows cannot teach bucket costs.
Range forecasts, gap state transitions, and capacity normalization consume only
supported rates from this shared per-board model
and measured-distance distribution. The active trip can change the range forecast's
speed mix without refitting its consumption costs. The recent-100-km range fit and
charge-cycle fit remain fallbacks where cumulative rates are unavailable. A
full-battery equivalent still uses the remote's percentage scale.

On first use, each board is seeded once from its retained, board-identified ride
summaries and calibration windows, including an active ride. Duplicate retained
IDs are counted once; subsequent restarts do not repeat migration or count ride
finalization as additional distance. Unknown-board history is not pooled into
another board. The migration flag denotes a partial historical baseline: already
trimmed records and archives outside app storage are not silently reconstructed.
New measured distance comes from valid continuous-stream intervals; retained gap
allocations remain inferred, and unassignable retained distance is kept separately.
No confidence percentage is inferred from kilometres.


### Bucket statistics linked to analytics

The same board-scoped snapshot is passed to dashboard range, the live battery
observer's base consumption model, API telemetry/end-of-ride range,
and capacity normalization. Range remains available from persisted sufficient
statistics even after individual calibration windows are trimmed. Both range and
capacity results expose the backing statistics, including measured confidence
kilometres, inferred distance, support counts, and model-limit flags. Statistics
whose board ID does not match the selected board are ignored.

Gap allocation uses the accumulated measured distribution available before the
arrival's inferred kilometres are added; it falls back to retained measured ride
profiles for uninitialized boards. Inferred kilometres never enter this reference
distribution. Capacity still requires a confirmed charge observation and sufficient
measured coverage; shared bucket statistics cannot turn incomplete cycles into
capacity evidence. Odometer-derived cycle age continues to use observed cycle
consumption rather than a normalized speed-bucket range.

Per-frame `battery_estimation` diagnostics include `bucket_model_source`
(`cumulative`, `retained_windows`, or `unavailable`) and `bucket_model_evidence`.
The latter preserves the selected bucket's measured confidence kilometres,
inferred kilometres, calibration support, window count, and limit flag from the
model used for that frame. The existing base/applied rates retain the actual
consumption calculation, including the observer's learned correction. New completed
windows invalidate the cached base model immediately; otherwise the model and its
evidence refresh at the existing ten-second cadence. Invalid frames carry null
bucket evidence.

### Collapsible trip history

The Rides list keeps today and the previous six local calendar days visible at
the top. Older trips start collapsed into day, Monday-based week, month, or year
sections. A section splits into finer calendar groups only when every resulting
group contains at least five trips. Sparse adjacent years combine; an older
history with fewer than five trips remains accessible as one smaller section.
Unparseable timestamps remain accessible under Undated trips.

Section headers show trip count and summed recorded trip distance. Expanded rows
remain lazy list items, and expansion state survives activity recreation. All
retained trips are available, replacing the former 30-row presentation limit;
this does not change history retention or any range/capacity calculation.

### Historical analytics migration

The evidence and uncertainty contract for subsequent estimators is defined in
[Board digital twin](digital-twin.md).

Analytics projections rebuild in the background on an idle startup, after finalized
JSONL logs have been archived. `analytics_history_version = "4"` identifies the
stored projection shape and `analytics_model_version` identifies the formulas that
produced it. A change to either value replays available raw archives from empty
state. Fresh installations without archive data receive both markers without
replay. A failed or deferred rebuild retains the old data and can retry later.

The reader validates RHA packages and RHP entry checksums, commit records, record
order and board identity without repairing source files. It rejects incomplete
partitions, missing retained trips and uncovered legacy analytics intervals.
Frames retain delivery order within each session; sessions replay chronologically
and boards remain isolated. Replay first confirms sustained rises of at least ten
percentage points, then places the charge boundary at the first elevated frame so
confirmation time is retained in the new cycle. Missing intervals below 2 km retain
their odometer distance with an inferred speed profile; intervals at or above 2 km
split calibration evidence. Odometer distance spanning a confirmed charge remains
stored as known but unattributed distance because its speed mix and position relative
to the charge are not observable. Rebuilt charge cycles, depletion windows,
cumulative bucket statistics, continuity, reported maxima, adaptive-learning
parameters and the final continuous battery estimate are replaced together.
Saved trip totals remain presentation history and are not rewritten.

`analytics_replay_manifest_v1` binds each installed projection to its model
version and a SHA-256 fingerprint of the exact verified JSONL records, plus source
record/session/frame counts. Prior derived values are comparison inputs only; they
never seed replayed estimates. A model-only change can therefore use the same raw
frames as a deterministic data migration without changing the stored schema.

A private versioned `files/analytics-migration/before-v*-*.json` backup and
`comparison-v*-*.json` report precede replacement. The comparison filename includes
the model version so successive formula iterations remain inspectable. The worker rechecks that no ride
is active and preferences still match its input snapshot, then commits the derived
state and version marker together. Archive errors or concurrent changes cannot
silently apply a partial rebuild. These private diagnostics must not be published.

### Battery Longevity presentation

The range estimate sits beside Observed charges and Est. total charges. The latter
is rounded to whole equivalent cycles and stays gray below ten observed charges;
unavailable values show an em dash. The status sentence remains below this row.
Highest-battery, lifetime-explanation and range-trend text blocks are omitted.

The chart reserves eight positions, ending at the selected populated period and
filling from earlier populated periods at the same granularity. Missing periods
are skipped; insufficient history leaves blank positions on the left. Tapping
drills into the selected period's latest observation, while pinching inward groups
time without losing the anchor. Only the selected bar is red. Weighted capacity
aggregation and the historical maximum scale are retained.

### Riders Profile

Riders Profile sits between Range and Battery Longevity. A compact vertical bar
chart offers Distribution (percentage of measured distance) and Energy use
(estimated battery percentage points consumed per kilometre, displayed as % / km).
Empty buckets are omitted.
Two tabs sit below the histogram, with 48 dp touch targets and indicator lines
above their titles. The selected tab's line is red. Bars use grayscale only. Energy-use bars
are dimmed until their bucket has at least 1 km measured, positive measured
calibration support and a contributing window, a finite positive consumption
estimate, and no model-limit flag. Inferred distance does not satisfy this gate.
This is a presentation threshold, not a confidence probability, and does not
alter the underlying consumption estimator.
Each energy bar displays that bucket's estimated consumption with two decimal
places, independently of the current battery level or riding mix. Taller bars
mean more battery consumed per kilometre.
Model-limit results appear as unavailable rather than displaying the fit limit
as a consumption prediction.

## Shelly charger observation

The optional charger digital twin uses a second, role-tagged companion-device
association for a Shelly Plus Plug S Gen2. A fresh BLE appearance may surface an
in-app chip and notification, but neither path turns on the relay. Both open the
same confirmation dialog. A confirmed start uses `Switch.Set` for switch `0`
with `toggle_after=43200`; the app then verifies the reported output state.

Observed power at or above 10 W must remain fresh for 30 seconds before charging
is considered active. An output that does not develop active load within five
minutes is turned off. After active charging, completion requires 20 continuous
minutes of fresh observations at or below both 5 W and 10 percent of the recorded
peak. Observation gaps reset completion evidence. Protection errors request an
immediate best-effort OFF, and manual STOP also requires confirmation. These
rules use Shelly power evidence only and do not modify board-battery charge
inference or its inclusive ten-point, 30-second confirmation rule.

Each appearance or charging episode is retained locally as append-only Charger
Observation Archive (`COA v1`) JSONL. It contains generated export identities,
timestamps, allowlisted relay/power/protection evidence, decisions and outcomes;
it excludes BLE addresses, Wi-Fi details, Shelly source identifiers and raw RPC.
COA is not mixed into ride RHA files. A server can opt into uploads with
`coa_format_versions: [1]` and receives files at
`PUT /v1/analytics/charger-files/{source_id}/{file_id}` under the same explicit
raw-analytics consent, with independent durable acknowledgement state.
