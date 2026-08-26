# Android capture analysis — 2026-08-25

This report analyzes the first-day capture from an Android 16 deployment
device. The source telemetry is retained locally and intentionally excluded
from Git.

## Integrity and scope

| Measure | Result |
| --- | ---: |
| JSONL session files | 19 |
| JSON records | 4,211 |
| Raw F1F2 notifications | 2,665 |
| Reconstructed telemetry frames | 1,331 |
| CRC-invalid reconstructed frames | 0 |
| Decoder discard records | 0 |
| Capture span | 2026-08-25 12:31:51–16:10:30 UTC |

All lines parse as JSON. Every file has both `session_start` and `session_end`.
The normal transport remains a 20-byte notification followed by a 5-byte
notification, at a median frame interval of 102.4 ms (about 9.77 Hz); the 95th
percentile within sessions is 120.7 ms.

Three sessions end with an incomplete raw tail: one 15-byte fragment and two
20-byte fragments, 55 bytes total. Those bytes remain in `notification`
records but cannot form CRC-checkable frames. The current decoder does not emit
its buffered tail as a `decoder_discard` when a session closes.

## Session groups

The first 716 frames have zero pack voltage, zero trip/odometer, and a battery
byte of 100 while the mode byte changes between Eco and Sport. This is the
remote-visible state without usable board telemetry, not a ride.

The remaining 615 frames contain live G3 data: Sport mode, battery byte 20,
39.281–41.944 V, trip 0.1–0.2 km, and odometer 2355.9–2356.0 km. Of these, 299
frames have speed above 0.5 km/h.

## Partial ride segment

The logger did not capture the start of the ride. Its first moving frame is
already 25.440 km/h, so the dataset contains only the final portion:

| Measure | Result |
| --- | ---: |
| Moving span | 18:05:01.293–18:05:46.094 CEST (44.800 s) |
| Maximum displayed speed | 25.584 km/h |
| Maximum timestamp | 18:05:01.725 CEST |
| Distance integrated across contiguous samples | 0.138 km |
| Distance including inter-session gaps up to 3 s | 0.149 km |
| Trip counter | 0.1 → 0.2 km |
| Odometer | 2355.9 → 2356.0 km |
| Board battery byte | 20 throughout |
| Mode | Sport throughout |

The trip and odometer are quantized to 0.1 km, so their increments corroborate
the integrated distance but do not independently establish an exact 0.1 km
ride length.

## Two speed words

The moving capture validates that both speed words carry live values:

- Pearson correlation between candidates A and B: 0.99882.
- Median absolute difference: 0.144 km/h.
- Maximum absolute difference: 1.736 km/h.
- A was greater in 138 moving frames, B in 151, and they were equal in 10.
- Candidate maxima were 25.548 and 25.584 km/h respectively.
- `B - A` has essentially no relationship to acceleration (correlation
  -0.0042), which argues against a simple older/newer sample pair.

The data is consistent with two motor/ESC or wheel-speed estimates, but does
not identify left versus right. A straight-line run followed by deliberate
left/right turns is the safest discriminating experiment. The displayed-speed
rule remains `max(A, B)` as found in the vendor app.

## Bytes 12–15

Bytes 12–13 behave as one signed big-endian load/current candidate:

- Active-board range: -17 to 496 raw units.
- Stationary baseline: usually 7.
- Correlation with pack voltage: -0.905 across active frames and -0.881 while
  moving.
- The maximum 496 occurs during acceleration and voltage sag; the single
  negative sample is compatible with a brief regenerative/braking value.
- A linear voltage-versus-raw fit implies about 37.9 mΩ if one raw unit is
  0.1 A, versus 378.5 mΩ at 0.01 A. The former is more physically plausible
  for a loaded skateboard pack, but this is an inference rather than a scale
  measurement.

The best current interpretation is therefore `load_raw_i16_be`, probably pack
or aggregate motor current with a possible 0.1 A scale. It should not yet be
published as amperes. Confirm it against an independent current/power meter or
a controlled unloaded-versus-loaded run first.

Bytes 14–15 are `00 00` in all 615 active-board frames. Bytes 1 and 3 remain
`00` and `01`; bytes 21–22 remain `80 00`.

## Android lifecycle finding

The app created 19 presence sessions and enabled notifications 18 times. Every
session ended as `companion_disappeared`, normally after about 10–12 seconds.
Five sessions also report GATT status 8 and one reports status 133. The repeated
teardown/reconnect cycle creates short gaps and is not the intended continuous,
low-power ride behavior.

The strongest hypothesis is that address-based BLE presence observation loses
the remote when it stops advertising during the GATT connection. Closing GATT
makes it advertise again, causing a new presence callback and another cycle.
This must be verified before changing the lifecycle. The next app revision
should test Android 16's `DevicePresenceEvent`/association-based observation
and keep an established telemetry connection independent of advertisement
loss. Until that is fixed, the logger is useful for protocol sampling but
cannot be claimed to record every whole ride.

## Next controlled captures

1. Fix and verify uninterrupted background connection lifecycle first.
2. Record a straight-line accelerate/coast/brake sequence to test bytes 12–13.
3. Add deliberate left and right turns to distinguish the speed candidates.
4. Capture Eco, Sport, Turbo, and child-limit states separately.
5. Compare bytes 12–13 against an independent current or power measurement.
