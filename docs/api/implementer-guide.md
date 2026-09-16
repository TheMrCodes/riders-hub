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
application messages. Socket closure does not mean that the ride ended. The client retains only the
latest eligible frame, sends at most once per second, clears it on disconnect, and never replays it.

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
