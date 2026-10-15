# Wear changelog

Watch releases are versioned independently from the phone. Supported pairings
are recorded in [the compatibility map](../docs/release-compatibility.json) and
included in the distribution notes for both apps.

## Unreleased

## 0.7.4-wear

- Label signed Race power as estimated draw or regeneration, with explicit
  accessibility descriptions and no negative-zero watt readout.

## 0.7.3-wear

### Changed

- Keep the existing Wear version name when releasing phone `0.7.4` fixes.
  The companion supports phone `0.7.4` through the shared telemetry and race
  contracts. The phone's archive recovery and Riders Profile changes do not
  require a corresponding watch UI change.
- Version names and build codes can now advance independently for watch releases.
