# Storage reminders

After 14 days without a recorded ride, Riders Hub asks whether to activate
storage mode. The question repeats around 10:00 local time each day until
answered. **No** dismisses it for that inactivity period. The next real ride
rearms the inactivity timer. Closing a notification or dismissing the dialog
does not count as an answer.

Accepting shows a storage progress card at the top of Home. It is completely
absent outside storage mode. The card covers setup, weekly observations,
estimated storage loss, the next charge check-in, and overdue charging.

## Fresh observations

Turn on the board and its paired remote near the phone. A battery observation
requires valid live board telemetry, a stationary board, and the same reported
percentage for 30 continuous seconds. Invalid frames, motion, and gaps over
two seconds restart that confirmation window. Stored ride replay and manual
acknowledgement cannot supply an observation.

The first observation is the starting percentage. The next two are requested
at weekly intervals. A missed check-in repeats daily around 10:00 until a fresh
sample arrives. Late check-ins use their actual elapsed time; they do not
fabricate missed weekly observations.

## Charging schedule

Three weekly-spaced, non-increasing observations with at least a five-percentage-
point total drop allow a straight-line fit in percentage points per day.
Flat, rising, or insufficiently changing observations keep weekly check-ins
active. Battery percentage is coarse telemetry; this is an estimate of storage
loss, not a measurement of energy or a guaranteed date when the battery dies.

The estimated remaining time is the latest observed percentage divided by the
daily loss rate. The charge reminder is due after half that remaining time.
For example, synthetic readings of 60%, 55%, and 50%, seven days apart, produce
a reminder 35 days after the third observation.

The app evaluates the schedule weekly and also schedules the charging deadline,
so a deadline between weekly evaluations is not skipped. Once due, charging
reminders repeat daily. An uncharged check-in cannot postpone an existing
deadline. A fresh reading at or below the configured low-battery threshold
brings charging forward, including while learning.

A sustained rise of **at least 10 percentage points** above the last storage
observation confirms a charge. This rejects a single five-point rebound.
The charge starts a new observation segment and recalculates the halfway
deadline using the learned loss rate; the upward jump is excluded from fitting.
A charge that still leaves the battery below the low-battery threshold keeps
the charge reminder due. Before an initial rate has been learned, a charge
restarts the three-observation calibration.

## Leaving storage and delivery

More than 0.1 km of contiguous live ride motion ends storage automatically and
cancels its reminders. Brief stationary checks and retained remote trip
counters do not qualify. Changing the associated board resets storage state.

State and notification deduplication survive process restarts. Local inexact
alarms are rescheduled after reboot, app replacement, and clock/timezone changes;
10:00 follows the local calendar across daylight-saving changes. Android power
restrictions may delay delivery, and force-stopping the app prevents delivery
until it is opened again. The card flags blocked notifications.

Reminder state stays local and is not added to ride archives or API uploads.
The feature does not operate the charger relay. Phone unit tests cover the
policy, persistence, notification delivery and cancellation, telemetry
validation, and local-time scheduling. Emulator UI checks use synthetic data;
physical-board winter discharge and long-term Android delivery remain unverified.
