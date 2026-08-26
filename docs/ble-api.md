# Backfire remote BLE API

This document describes the BLE interface exposed by a Backfire remote while
it is connected to a board. It is not an official Backfire specification.
Results apply directly to the tested Backfire G3 and a remote advertising with
the `BF_` prefix; other remote and board generations may differ.

Android version 0.3 implements only the two exact child-limiter commands as a
hidden, manually confirmed experimental control; it never sends them
automatically. Merely subscribing to notifications writes the standard
Bluetooth CCCD, which is transport setup rather than a Backfire command.

## Evidence labels

- **Live**: observed on the physical G3/remote and, for displayed values,
  compared with the remote screen.
- **Research**: derived from protocol reverse engineering, data gathering, and
  local consistency checks.
- **Community**: corroborated by public reverse engineering on another Backfire
  model.
- **Inference**: the best explanation of observations, but not yet proven.

## Endpoint and connection

The phone connects to the **remote**, not directly to the skateboard ESC. The
remote must already be receiving board data. Riders Hub intentionally limits
association to BLE device names beginning with `BF_`.

The app calls `connectGatt(..., autoConnect=false)`, discovers services, and
enables notifications. It contains no pairing prompt, application-layer
authentication, challenge, encryption, session key, or handshake. The tested
remote accepted the same read-only flow. This does not prove that every remote
revision exposes the same access policy.

## GATT surface

| Purpose | UUID | Observed properties | Evidence |
| --- | --- | --- | --- |
| Application UART service | `0000f1f0-0000-1000-8000-00805f9b34fb` | service | Live, Research |
| App to remote (`TXD`) | `0000f1f1-0000-1000-8000-00805f9b34fb` | write, write without response | Live, Research |
| Remote to app (`RXD`) | `0000f1f2-0000-1000-8000-00805f9b34fb` | notify | Live, Research |
| Client Characteristic Configuration | `00002902-0000-1000-8000-00805f9b34fb` | notification configuration | Live, Research |
| BLE-module AT service | `0000f2f0-0000-1000-8000-00805f9b34fb` | service | Live only |
| AT send | `0000f2f1-0000-1000-8000-00805f9b34fb` | write, write without response | Live only |
| AT response | `0000f2f2-0000-1000-8000-00805f9b34fb` | notify | Live only |

Riders Hub uses only the F1 service. F2 is probably a BLE-module management
interface, but its command set and safety properties are unknown. Writes could
change radio configuration, identity, or persistent state, so it should not be
probed speculatively. On the tested remote, only F1F2 has the notify property
and CCCD.

## Telemetry transport

F1F2 emits a 25-byte status frame as a 20-byte notification followed by a
5-byte notification. Multi-byte data fields are big-endian. The two-byte CRC is
little-endian.

Live captures consistently delivered 20-byte and 5-byte pieces. Riders Hub uses
header and length validation, incremental buffering, and CRC-16/MODBUS to
reassemble frames and resynchronize safely.

### Frame layout

| Offset | Size | Meaning | Encoding / scale | Evidence |
| --- | ---: | --- | --- | --- |
| 0 | 1 | Header | `0xAC` | Live, Community |
| 1 | 1 | Unknown | seen as `0x00` | Live |
| 2 | 1 | Total frame length | `0x19` = 25 | Live, Community |
| 3 | 1 | Unknown | seen as `0x01` | Live |
| 4 | 1 | Mode/status | see below | Live, Research |
| 5 | 1 | Board battery | percent | Live, Research |
| 6..7 | 2 | Speed candidate A | unsigned BE / 1000 km/h | Research, Community |
| 8..9 | 2 | Speed candidate B | unsigned BE / 1000 km/h | Research, Community |
| 10..11 | 2 | Pack voltage | unsigned BE mV / 1000 V | Live, Community |
| 12..13 | 2 | Load/current candidate | signed BE raw; possible 0.1 A scale, not confirmed | Live, Inference |
| 14..15 | 2 | Unknown | `00 00` in all 615 active-board frames | Live |
| 16..17 | 2 | Last trip | unsigned BE / 10 km | Live, Research |
| 18..20 | 3 | Odometer | unsigned BE / 10 km | Live, Research |
| 21..22 | 2 | Unknown | seen as `80 00` | Live |
| 23..24 | 2 | Checksum | CRC-16/MODBUS over bytes 0..22, low byte first | Live, Community |

Riders Hub displays `max(candidate_a, candidate_b)` while retaining fractional
km/h. In 299 moving G3 frames the candidates correlate at 0.99882, differ by a
median 0.144 km/h and a maximum 1.736 km/h, and neither is consistently
greater. This supports two motor/ESC or wheel estimates more than a duplicate
field, but left/right identity is not established.

Bytes 12..13 range from -17 to 496 as a signed big-endian value. They sit near
7 while stationary, rise under load, and correlate strongly and inversely with
pack voltage (-0.905 across active-board frames). A 0.1 A scale would imply a
plausible approximately 38 mΩ voltage/load slope; this is evidence for a
pack/aggregate-current field, not a confirmed unit. Keep it as raw signed data
until compared with an independent current measurement. See
[`capture-analysis-2026-08-25.md`](capture-analysis-2026-08-25.md).

No controlled observation or public source has identified the remaining
unknown fields. Riders Hub keeps their raw bytes available for future research.

### Mode/status values

| Value | Meaning | Evidence |
| ---: | --- | --- |
| `0x00` | Off | Community; not live-confirmed on G3 |
| `0x01` | Eco | Community; not live-confirmed on G3 |
| `0x02` | Sport | Live, Research |
| `0x03` | Turbo | Community; not live-confirmed on G3 |
| `0x81` | Child speed limiter active | Research; command not sent during this work |

The `0x81` value is currently treated as a whole status value, not as a
separately decoded bit flag. It may mask the underlying ride mode while
limiting is active.

### Confirmed G3 sample

```text
ac 00 19 01 02 14 00 00 00 00 a2 49 00 07 00 00 00 00 00 5c 06 80 00 df cf
```

This decodes to Sport, 20% board battery, 0 km/h, 41.545 V, 0.0 km last trip,
2355.8 km odometer, and a valid CRC. The user confirmed that the decoded stats
matched the physical remote.

## Application command format

Protocol research identified two child speed-limiter messages. Both are written
to F1F1.

```text
AC A0 <state> <crc-low> <crc-high>
```

The checksum is CRC-16/MODBUS over the first three bytes, initialized to
`0xFFFF`, appended low byte first.

| Operation | Complete bytes | CRC over payload | Evidence |
| --- | --- | --- | --- |
| Disable child speed limit | `AC A0 00 C9 E1` | `0xE1C9` | Research + local CRC test |
| Enable child speed limit | `AC A0 01 08 21` | `0x2108` | Research + local CRC test |

These commands pass local CRC validation but have **not been live-tested or
sent**. Riders Hub uses the characteristic's acknowledged/default write
behavior, watches `onCharacteristicWrite` for GATT completion, and requires a
later telemetry frame to confirm the requested state.

## Current documented interface extent

| Capability | Status |
| --- | --- |
| Receive board battery, speed, trip, odometer, and mode/status | Confirmed |
| Receive pack voltage | Confirmed by live and community work |
| Enable/disable child speed limiter | Exact messages documented; not sent |
| Discover the board model from BLE | No field identified |
| Change Eco/Sport/Turbo mode | No command documented |
| Throttle, brake, reverse, cruise, or power control | No command documented |
| Change acceleration/braking curves or unrestricted top speed | No command documented |
| Reset trip/odometer | No command documented |
| Read remote battery separately | No field identified |
| Firmware update / OTA / DFU | No UUID, command, or workflow documented |
| F2 module administration | Exposed live but intentionally unprobed |

“No command documented” does not prove that the firmware lacks undocumented
commands.

## Safety posture

- Keep drive wheels clear of the ground for any future write experiment.
- Capture before and after state, change one byte/feature at a time, and never
  fuzz either write characteristic.
- Do not probe F2 without identifying the BLE module and obtaining its exact AT
  command manual.
- Prefer notification-only telemetry. Riders Hub exposes no general board-write
  API. Its Android control is restricted to two exact, confirmed-user
  child-limiter states and logs every attempt.
- Treat unknown status bytes as opaque until controlled physical-state changes
  correlate them; do not assign meanings from one frame.

## Open questions

1. Are bytes 12..13 pack current, aggregate motor current, or another load
   estimate, and is the scale 0.1 A?
2. Are the two speed words the left and right motors/wheels?
3. Does `0x81` replace the ride-mode value or combine a limiter flag with mode?
4. What do bytes 1, 3, 14..15, and 21..22 encode, and where are temperature,
   remote battery, and fault state represented?
5. Which BLE module implements F2, and is its AT surface locked or persistent?
