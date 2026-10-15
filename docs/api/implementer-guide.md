# Riders Hub API v1 implementer guide

Implement `GET /v1/capabilities` and at least one advertised data channel. Require a device bearer
credential on every data request, including the WebSocket upgrade. Deploy data endpoints behind
HTTPS when possible. Clients reject redirects so credentials cannot be forwarded to another
origin.

The base URL may contain a path prefix. Append the documented `v1` paths to it. A successful
discovery response is still invalid unless the top-level contract version, stable server ID, and
at least one individually valid capability are present. Ignore unknown fields. An invalid optional
capability must not disable valid sibling capabilities.

## OIDC device enrollment

OIDC enrollment is optional. When enabled, publish unauthenticated `GET /v1/auth/config` and
bearer-authenticated `POST /v1/devices/registrations`. The native Android client performs browser
Authorization Code + PKCE with the fixed redirect URI
`at.themrcodes.ridershub:/oauth2redirect`; the API never receives an authorization code, refresh
token, user password, or browser session.

Validate the registration access token's signature, exact issuer, expiry, audience
`ridershub-api`, `azp` equal to the configured Android public-client ID, and exact scope
`ridershub.device.register`. No role is required. Bind the issued device credential to the random
client `source_id` and verified OIDC subject, store only its digest, and reject cross-source data.

## Metrics

Treat a snapshot as complete: an absent optional measurement is unavailable. Deduplicate lifecycle
events by `message_id`, and use `(source_id, sequence)` for ordering. A delayed lifecycle event is
history and must not overwrite state derived from a higher sequence. Every `2xx` acknowledges an
upload; return `202` only after durable acceptance.

`trip_distance_km` is the board remote's authoritative trip counter. GPS is route context and must
not be substituted for it.

## Realtime

Select WebSocket subprotocol `riders-hub.realtime.v1`. The first application message on every
connection is `stream.opened`; all later messages are `telemetry`. The server defines no incoming
application messages. Socket closure does not mean that the ride ended. The client buffers every
valid frame and flushes in five-second batches. For v1 compatibility each frame remains a separate
`telemetry` WebSocket message, in observation order with its original timestamp. Servers must accept
these bursts; there is no new array envelope. One fresh location is attached only to the final frame
in each batch, retaining its own `fix_at` timestamp (it does not describe all earlier frames).

GPS is requested once, two seconds before a nonempty batch is due, with one update and a 1.5-second
timeout. No fix means the batch sends without location; GPS never delays the deadline. Pending
frames are cleared when the five-second remote-disconnect tracking grace expires,
on configuration reset, or ride finalization and are not replayed. The realtime socket
stays open without tracking during the remaining trip reconnect grace (two minutes).
Reconnecting during the same trip resumes that socket and stream sequence; finalization
closes it. Transport failures still reconnect with backoff.
Both handoff and send buffers are bounded to 2,048 frames; overflow resets the live stream and reports
an error instead of growing indefinitely. A flush is limited to 1 MiB of outgoing buffered data.
These are live delivery bounds, not guaranteed delivery: original frames remain in the local archive.

## Analytics replacement

An RHA file can be a complete package of a non-final logical partition. `final=false` describes the
partition, not file validity. Generation is the partition entry count. Validate the RHA container,
path identity, generation, logical SHA-256, and unchanged prefix before atomically replacing stored
content.

| Incoming upload | Required result |
| --- | --- |
| Same logical content and finality | `2xx` no-op |
| Newer generation with unchanged prefix | `2xx`, atomic replacement |
| Same content promoted snapshot to final | `2xx` |
| Identical retry of final | `2xx` no-op |
| Older, divergent, or changed final content | `409`, preserve existing |

Compressed bytes and their digest may differ for logically equal packages because packaging
metadata can differ. Establish logical equality with `Riders-Hub-Uncompressed-Digest` and verified
uncompressed contents. Do not use CRC alone.

Repository examples are synthetic. A real archive may contain stable device identifiers, exact
activity times, raw frames, diagnostics, and precise location records.

If `coa_format_versions` advertises version 1, also implement the independent charger-file PUT.
Validate `Content-Digest`, the record count, sequential JSONL records, matching generated
`charger_id`, and `session_start`/`session_end` boundaries before durable acceptance. Deduplicate
by `(source_id, file_id)`: identical bytes are a `2xx` no-op; different bytes for an existing key
are `409` and must not replace prior evidence. COA files contain exact observation times, relay
decisions and allowlisted power/protection evidence, so store them under the same explicit raw
analytics consent and access controls as RHA while keeping their storage and delivery identity
separate.

## Contract checks

Run `python3 -m unittest discover -s tools -p 'test_*.py'` for offline payload,
capability-combination, lifecycle-field, timestamp, and archive checks. These tests
require PyYAML and jsonschema. Run `python3 tools/validate_api_contract.py --specifications`
to additionally validate against upstream OpenAPI and AsyncAPI specification
schemas (network access required). Android regression tests cover durable journal
reopening, consent isolation, concurrent BLE/GPS writes, and realtime transport races.

The client retains delivery progress separately for each URL and authenticated
server identity. Key rotation preserves that progress. Permanent archive conflicts
remain blocked until a deliberate resync; retries and reconnects do not clear them.

## Phone motion recording

`POST /v1/motion/batches` is an independent channel advertised as
`capabilities.motion` with `schema_version: 1`, `max_batch_samples: 4096`, and
`max_upload_bytes` (1 MiB in the reference configuration). It is separate from
realtime WebSocket frames, GPS, ride archives, and charger observations. The
Android client requires at least 4096 samples and 1 MiB per request to enable
this version. An absent or unsupported motion capability leaves other channels
usable. Authentication uses the existing source-bound device credential.

Phone accelerometer and gyroscope listeners request 50 Hz (20,000 microseconds),
but hardware can deliver faster callbacks. The phone retains at most one sample
per sensor every 20 ms using monotonic sensor timestamps, including across batch
boundaries. Faster callbacks are dropped; axes are not averaged and samples are
not invented. Actual retained rates can be lower than 50 Hz. Normal batches
flush every five seconds; a full 4096-sample buffer flushes earlier, and stopping
records a final partial batch. Sensors that are absent or fail registration are
omitted from `sensors`; no samples are invented.

Each batch has immutable `batch_id`, `source_id`, `board_id`, `ride_id`,
`stream_id`, and positive stream `sequence`. A new recording session creates a
new stream ID. `started_at` anchors the stream to UTC; each sample's `elapsed_ns`
is measured from the same Android monotonic clock origin, preserving timing
across wall-clock adjustments. Times increase strictly within each sensor's
samples, but readings from different sensors can arrive interleaved.

Axes use Android's fixed device coordinate system, independent of screen
rotation: X right, Y toward the top, Z out of the screen in natural orientation.
Acceleration is in m/s² **including gravity**; gyroscope readings are in rad/s.
The payload declares these units as `m/s2` and `rad/s`. These describe phone
motion, not automatically board acceleration or world-relative orientation.
The `accuracy` field preserves Android's -1 through 3 accuracy status.

The reference server validates required fields, finite axes, sensor names,
per-sensor ordering, sample counts, and body size before storing the exact bytes
in `ridershub_motion_batches`. Both `(source_id, batch_id)` and
`(source_id, stream_id, sequence)` are unique. A byte-identical retry returns
`204` after commit; conflicting data returns `409` without replacement. No
server command, WebSocket response, or relay action is defined by this endpoint.

Android's **Record and share phone motion** switch is off by default. Consent
is scoped to the API URL and authenticated server identity, independently of
GPS and raw-archive sharing. A foreground notification identifies recording
and lets the user stop it. Sensors, batching, persistence, and uploads run off
the UI thread. A short renewable CPU wake lock supports screen-off collection
and is released when recording stops. Sensor collection and live sending require a current remote GATT connection
with usable board telemetry, or its five-second disconnect grace. After that grace
tracking pauses while the trip and realtime socket remain open for reconnection. Reconnecting creates a fresh sensor window; old
GPS callbacks and motion-service starts cannot reuse it. API disablement, consent
withdrawal, finalization, and loss of capability also stop the relevant sensors.

Sealed batches are recorded in the private `motion_recordings.db` journal before
an immediate upload attempt while the remote is connected. Motion uploads,
including queued retries, pause after the five-second disconnect grace; partial batches remain local
until a subsequent connected window. Offline batches retry with the same identity
and bytes; neither credential rotation nor re-enrollment moves recordings to another
server. Acknowledged recordings remain local. Storage is capped at 512 MiB of
payloads; reaching the cap stops recording with a visible error instead of
silently deleting old recordings. Abrupt process death can lose the current
unsealed batch (up to five seconds), but sealed batches survive and retry after
delivery recovery. Network delays can exceed the normal five-second cadence.

All checked-in motion examples are synthetic. Real recordings contain precise
movement and activity timing; keep them private and out of Git.

### Reinstall-safe archive delivery

Before uploading a finalized RHA partition, send HEAD to its normal partition
URL with the entry count, final flag, and uncompressed SHA-256 digest headers.
HTTP 204 means an exact finalized logical partition is already durable for the
same OIDC issuer and subject, including archives uploaded by older installations.
Record the normal local delivery acknowledgement without sending the body.
HTTP 404 means no match; HTTP 405 permits fallback against older servers. Other
errors follow the normal retry/authentication policy. Responses are not cacheable.

Matching includes partition sequence and entry count. Compressed representations
may differ, but uncompressed bytes must match exactly. Mutable partitions do not
qualify. A verified PUT of matching final content from a new installation is also
a successful no-op; a transaction lock prevents simultaneous installs creating
duplicate copies. Existing source/partition conflicts still follow normal rules.
Reuse never crosses account boundaries, and archived bytes remain under their
original source identity. No existing rows are deleted or merged. This prevents
reupload of unchanged restored archives; it does not deduplicate repackaged or
partially overlapping rides by board/time, nor realtime, motion, or charger data.

### Canonical telemetry rows

`ridershub_telemetry_rows` is the row-level dataset for board telemetry. Its
unique key is `(oidc_issuer, oidc_subject, board_id, captured_seconds,
captured_nanos)`. Account identity comes from the authenticated source's device
registration, never from a user ID supplied in telemetry. Issuer scopes the OIDC
subject so unrelated identity providers cannot collide. Timestamps are parsed as
RFC 3339 instants; equivalent offsets match, while even a one-nanosecond difference
remains distinct. No timestamp rounding or fuzzy matching is applied.

Validated RHA uploads stream `telemetry_frame` JSONL records into this table.
Realtime uses `frame_at`; archived frames use `wall_time`. These are the same
capture instant in the phone. The API derives the archive board UUID from the
partition's remote address using the same stable mapping as the phone:
`local = "board_" + first_12_bytes(SHA256("riders-hub-board:" + lowercase_address))`
(encoded as lowercase hex), then Java-compatible UUID v3 over UTF-8
`"riders-hub-api-board:v1:" + local` (MD5 with UUID version/variant bits set).
This is an identity mapping, not an authentication or secrecy primitive.
New phone builds use this stable board UUID instead of per-installation random
export IDs; ride/source/stream IDs retain their existing lifetimes.

Shared measurements are stored once in the `frame` JSONB column, using one
common field vocabulary (`raw_hex`, `speed_kmh`, voltage, battery, CRC and other
decoded measurements when available). Hex bytes are normalized to lowercase
without separators. Transport-specific fields and diagnostics are stored in
`ridershub_telemetry_details`, keyed by canonical row and detail kind. Shared
fields are removed from those details. Duplicate keys create no second row;
existing field values win and later data can only fill missing fields. Legacy
`archive_payload` and `realtime_payload` blobs are migrated and cleared only after
the normalized row has been archived durably. The old columns remain nullable
for upgrade compatibility and failed-migration inspection.
Under this deliberately exact key, two distinct frames with the same board and
capture instant count as one canonical row. Original archives remain unchanged
for provenance and collision investigation. Non-frame diagnostics, motion sensor
batches, and charger observations retain their separate storage contracts.

Realtime delivery receipts retain `(connection_id, stream_sequence)` and point
to the canonical row through `telemetry_id`; duplicate frames still advance the
session's durable sequence. New receipts do not copy the payload. The
`ridershub_realtime_sample_payloads` view resolves old or canonical payloads for
receipt-level diagnostics; use `ridershub_telemetry_rows` for deduplicated analysis.

Archive indexing and raw storage commit together. Up to 64 records (each at most
64 KiB) are batched; no whole archive or ride is loaded into RAM. Invalid recognized
frame timestamps or oversized JSONL records reject a new upload with HTTP 400 and
roll back all its writes. Integrity validation of the RHA/RHP container still
precedes indexing.

A background worker backfills existing registered-source archives and legacy
realtime receipts. It spools at most one archive per worker to disk, reads stored
chunks or legacy blobs in 64 KiB slices, and commits one archive at a time.
Row locks coordinate multiple pods. Digest markers make the worker restart-safe;
malformed historical records set `telemetry_index_error` without removing their
original data or blocking other archives. Shutdown cancels the current backfill
transaction; a later sweep resumes. Raw archives are never rewritten or deleted.

Historical realtime board UUIDs generated randomly by older phone builds cannot
be reliably mapped to the stable archive UUID without an explicit identity
mapping. They are indexed under their original board ID; no board identity is
inferred from coinciding timestamps. Such rows will not merge across differing
legacy board IDs. Restored archives do carry the stable board evidence and are
deduplicated across installations during backfill.

### Persistent frame archives and replay

Production sets `RIDERHUB_ARCHIVE_DIRECTORY=/archives/frames`. The optional
`service.archiveStorageGiB` deployment setting creates a retained `local-path`
ReadWriteOnce PVC mounted at `/archives`; Riders Hub requests 20 GiB. The
renderer keeps `/tmp` ephemeral, requires one replica, and uses a Recreate
rollout so replacement pods cannot write the same journal concurrently. The
volume is protected from automatic Argo pruning. This survives pod replacement
on its storage node; node-local storage is not an off-node backup.

Before acknowledging normalized rows, the API appends versioned JSONL archive
records and fsyncs the file. Records contain the trusted OIDC issuer/subject,
board ID, capture seconds/nanoseconds, common frame fields, and separate details.
Files rotate around 4 MiB, have private permissions and random names, and are
never cleaned by scratch-directory cleanup. Complete uploaded RHA bytes and
account-scoped manifests are also copied under `originals/`, checked against the
upload digest, synced and atomically renamed. Existing database archives are
exported there by the bounded background worker. No originals are deleted.

On startup the worker replays completed journal files from durable byte-offset
checkpoints, prioritizes export/indexing of existing archives, then migrates legacy
payloads. Replay commits at most 64 records per batch; legacy migration handles
32 rows per transaction. Both remain bounded and resume after interruption.
Replay and checkpoint advancement commit atomically; replayed duplicates are
harmless. Deleting/restoring the whole database also removes checkpoints, allowing
a full rebuild of the canonical frame table from these JSONL files. If only the
canonical rows are deliberately cleared, clear the replay checkpoints too before
recovery. Connection receipts and non-frame records are not reconstructed from
frame journals; non-frame upload records remain in the original RHA files.
An interrupted, unterminated final journal line is ignored because it was never
acknowledged. Corruption in a complete record stops replay for investigation.

The API fails startup if the configured archive directory is unwritable, and
archive-write failures prevent ingestion acknowledgement. Realtime now optionally
includes `raw_hex`; upgraded phones send it for every retained frame. Historical
realtime data without raw bytes remains partial until a matching archive supplies
those bytes. The server never fabricates missing raw frames.
