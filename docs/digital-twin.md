# Board digital twin

The board digital twin is a state estimator over incomplete, quantized and sometimes
gapped remote telemetry. It must preserve what was observed, represent what was
inferred and leave genuinely unresolved quantities explicit.

## Implementation status

The current foundation retains measured, inferred and unattributed distance,
models the remote battery value as an interval, advances SOC across supported
sub-2 km inferred gaps, and prevents unsupported bucket fits from affecting the
forecast. The current speed model is a rolling-plus-aerodynamic quadratic,
`consumption(v) = rolling + aerodynamic * v^2`. At each completed trip, the board
refits both non-negative coefficients from its entire supported history; one trip
never becomes a direct parameter update. No fixed aerodynamic share or growth
multiplier supplies the curve. Aggregate normalization conserves the accepted
weighted depletion exactly. Buckets without sufficient calibration support cannot
steer the shape, although the fitted physical curve still supplies their provisional
rate. Per-frame archive diagnostics retain every applied gap transition and the
quadratic fit evidence.

Riders Profile shows only speed buckets with at least 5 km of measured riding;
inferred distance does not meet this threshold. Energy use additionally requires
measured calibration exposure, a contributing depletion window and a usable rate
away from the model limits. Sparse buckets are omitted at both ends and within the
range. Displayed distance shares total 100% across the supported buckets; the
stored distance ledger and forecast inputs retain all observations.
The tabs sit below the chart. Both chart views share the taller natural content
height. Battery detail rows appear in a separate Energy statistics section below
the chart and tabs, independent of the selected tab, so switching tabs does not
move the controls or the following section.

Derived state is a versioned projection of the immutable telemetry archive. A
replay always starts with empty board state and processes verified frames in
chronological delivery order. It never seeds the candidate model from a prior
battery estimate, correction factor, range window, charge cycle or bucket fit.
The replay manifest records the projection version, model version and SHA-256
fingerprint of the exact JSONL source records.

Persistent SOC uncertainty, bucket-rate uncertainty, usable-capacity state,
voltage-curve learning, recuperation learning and the archive backward smoother
below are the next estimator layers. They are design requirements, not claims
about the current build.

The first usable-capacity foundation is now durable and board-scoped. A new board,
and a legacy SOC state upgraded to the current schema, starts at an explicit
**346 Wh prior** with zero qualified observations and status `prior`. This is an
initialization value, not a claim that the installed battery is stock or that its
capacity was measured. The state retains its estimate, observation count and
supported depletion. Its robust update contract rejects observations below 20
percentage points; three qualified observations covering at least 100 cumulative
percentage points promote `learning` to `tracking`. Live raw-load telemetry does
not yet supply those observations because its current scale is unverified, and
recuperation remains excluded from SOC and capacity learning.

The signed raw-load zero is also board-scoped. Before calibration, absolute raw
values from `-10` through `10` are treated as idle. After five continuous stationary
seconds, the estimator begins accepting samples from that bootstrap band; 50 samples
set the initial zero to their median and the noise band from three median absolute
deviations. Subsequent stationary samples update the zero slowly. Moving frames and
charge candidates cannot become zero-calibration evidence. Diagnostics retain the
raw input, effective input, learned zero, deadband, sample count and calibration
status. This removes an assumed raw value of `7` without turning ordinary idle noise
into consumption or recuperation.

## Model iteration and migration

The model version and stored-projection version are independent. Changing a
formula or learning rule changes the model version and causes the archive to be
replayed. Changing the shape of stored projections changes the projection
version. Either change produces a complete candidate snapshot before any live
state is replaced.

The iteration loop is:

1. define the candidate transition and observation formulas;
2. replay every available raw frame from empty state;
3. compare its report with the previous projection and fixed chronological
   validation metrics;
4. reject or revise implausible parameters;
5. atomically install the candidate only after archive validation and replay
   complete successfully.

Raw archives therefore serve as the migration input for every reconstructable
field. Saved trip presentation remains separate because it contains UI history,
while its archive references are checked for coverage before replacement. If a
required archive is missing, corrupt or incomplete, the app retains the previous
derived state and retries later.

Model `digital-twin-replay-v8` adds the physics-informed frame model described in
[the logger notes](android-logger.md#physics-informed-frame-model). Replay feeds
the same aligned frames, archived `pressure_sample` records and window motion
work, so derived shape, recuperation and overheads are rebuilt from raw data.
Model `digital-twin-replay-v9` replays with the current rider and board profile,
so the depletion scale and learned pack-energy factor match the live model.

## Invariants

1. Raw archive records remain immutable evidence.
2. Odometer displacement remains distance evidence even when speed, battery use or
   charge timing is unavailable.
3. Measured speed profile, inferred speed profile and unattributed distance are
   separate totals. Their sum is never presented as entirely measured.
4. A reported battery value is a quantized observation, not the exact state of
   charge and not an exact five-point energy transfer.
5. A confirmed charge changes the energy state. It does not erase odometer distance
   observed across the same interval.
6. Learned parameters update gradually and retain uncertainty. A single unusual
   interval cannot redefine the board.
7. Fitted speed-bucket consumption must sum back to the accepted calibration
   depletion over the same weighted bucket exposure.

## Evidence model

| Evidence | Representation | Use |
| --- | --- | --- |
| Odometer delta | observed distance plus counter-quantization uncertainty | conserve total distance and detect discontinuities |
| Connected speed frames | time-integrated bucket proportions | allocate observed odometer distance across speed buckets |
| Gapped speed frames below 2 km | board-profile bucket distribution with uncertainty | allocate distance while marking every kilometre inferred |
| Gap at or above 2 km | known but unattributed odometer distance | preserve lifetime distance; exclude from consumption calibration |
| Charge-spanning gap | known but unattributed odometer distance | preserve distance; do not assign it to pre-charge or post-charge energy use |
| Reported battery | interval around the emitted percentage | constrain continuous state of charge without snapping |
| Resting voltage | noisy monotonic battery observation | strengthen the state update only under low input and stable voltage |
| Loaded voltage | operating-state evidence | learn voltage sag; do not convert directly to state of charge |
| Raw controller input | dimensionless drive/brake intent | explain load and possible recuperation without treating it as current |

For connected intervals, speed integration supplies proportions rather than the
authoritative total distance. If the integrated bucket distances are
`d_tilde[b]` and the odometer displacement is `d_odo`, reconcile them as:

```text
d[b] = d_odo * d_tilde[b] / sum(d_tilde)
```

This preserves the remote counter total while retaining the observed speed mix.
When no speed profile exists, distribute `d_odo` using the learned board profile
and retain the allocation as inferred evidence.

## State model

The target persistent state for one board is:

```text
SOC mean and uncertainty
usable-capacity scale and uncertainty
consumption rate and uncertainty for every 5 km/h bucket
recuperation correction and uncertainty
resting-voltage curve parameters
reported and settled maximum battery percentages
measured, inferred and unattributed distance ledgers
discrete mode: riding, idle, charge candidate, charged or gapped
```

The driving transition is expressed in percentage points because the protocol does
not expose current or battery energy:

```text
drive_use = distance * sum(profile[b] * consumption[b]) * capacity_scale
SOC_predicted = SOC_previous - drive_use + recuperation + charge_input
```

`recuperation` is bounded and learned only from braking-input observations that are
supported by subsequent battery or voltage evidence. `charge_input` remains latent
until a rise of at least ten percentage points stays elevated for 30 observed
seconds. Confirmation moves the boundary back to the first elevated observation.

State uncertainty grows with distance, unsupported buckets and gaps. Confirmed
low-input battery and resting-voltage observations reduce it. Loaded or rapidly
changing observations receive less weight.

## Battery observation model

An emitted percentage `p` initially represents an interval rather than a point:

```text
SOC observation interval = [p - 2.5, p + 2.5]
```

The bounds are clipped to 0–100 and widened when load or voltage instability shows
that the remote may be reporting a sagged value. Repeated stable observations make
the likelihood stronger, but do not force the estimate to the interval boundary.

The archive smoother learns the remote's actual switching bias and persistence per
board. Until that evidence exists, the symmetric interval is a conservative prior.

## Online filter and archive smoother

The target live app uses a small hybrid state-space filter:

- discrete logic handles confirmed charges and gap classes;
- a continuous filter propagates SOC and uncertainty;
- board parameters change slowly from accepted observations;
- unsupported speed buckets fall back to the board aggregate with wider uncertainty.

Archive replay performs a backward smoothing pass. It can reconsider the likely
speed mix of gaps and the location of a charge within an interval without rewriting
the raw observation. The current consumption model uses this physical form:

```text
raw_consumption(speed) = rolling + aerodynamic * speed^2
scale = weighted observed depletion / weighted raw consumption
consumption(speed) = scale * raw_consumption(speed)
```

`rolling` models speed-independent loss per kilometre and `aerodynamic` models the
speed-squared term. Trip completion solves a two-parameter non-negative weighted
least-squares fit from the cumulative window cross products and targets. The fit is
recomputed from all retained sufficient statistics instead of nudging a coefficient
from the newest ride. Only buckets that pass the existing measured-support gate
enter the shape objective; unsupported buckets receive an extrapolated rate but cannot
steer either coefficient. The reference speed used to condition the regression is
the evidence-weighted root-mean-square speed, so it is derived from observations and
does not impose a preferred curve.

If the unconstrained result asks for a negative coefficient, non-negative least
squares selects the best physical boundary solution. If the data cannot identify
two coefficients, the model uses a flat raw shape and lets aggregate normalization
learn its scale. Every published bucket rate stays non-negative and the reconstructed
rates sum back to the accepted weighted depletion. Diagnostics retain the applied
and unconstrained coefficients, coefficient standard errors, condition number,
R-squared, supported distance, and supported-bucket count so insufficient evidence
is visible instead of hidden by a fixed prior.

### Temporal continuity objective

A future continuously updated transition model must regularize the implied endpoint,
not the raw remaining value. If `r_t` is predicted distance remaining and `x_t` is
distance observed since the last battery transition, define `e_t = x_t + r_t`.
The corresponding time endpoint uses elapsed time plus predicted time remaining.
For adjacent states within one uninterrupted interval, the training objective adds:

```text
rho(z) = sqrt(1 + z^2) - 1
jump_loss = lambda * mean_equal_interval(
    rho((time_endpoint[t+1] - time_endpoint[t]) / 10 seconds) +
    rho((distance_endpoint[t+1] - distance_endpoint[t]) / 100 metres)
)
```

This pseudo-Huber total-variation term penalizes visible forecast jumps while still
allowing remaining time and distance to count down normally. Pair weights give each
interval equal influence, and pairs never cross a reported battery transition,
confirmed charge, hard gap or censored tail. It complements observation and forecast
error; it must not replace them or turn a constant forecast into an apparently good
model. The term is currently validated only in ignored local research artifacts and
is not part of the production estimator.

## Outputs and validation

The completed twin will expose a mean and uncertainty for continuous battery percentage and
remaining range. Bucket values are available only with their evidence distance and
uncertainty; low-quality values remain unavailable to range forecasting.

Validation uses chronological holdouts and measures:

- distance error to the next stable emitted battery transition;
- transition timing error, reported separately from distance error;
- coverage of the predicted SOC uncertainty interval;
- remaining-range error at trip end;
- stability of capacity and bucket parameters between archive rebuilds.

The target is not to reproduce every load-induced remote change. The target is a
persistent physical state whose uncertainty narrows as observations accumulate and
whose predictions improve as the next stable transition approaches.
