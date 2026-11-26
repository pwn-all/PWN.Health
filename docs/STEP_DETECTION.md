# PWN.Health: step and motion detection

Ideas that are not built are listed in section 8.

## 1. Summary

Daily steps are taken from the system `TYPE_STEP_COUNTER`, and the accelerometer is used as a
second level of checking. The hardware counter usually runs in a low-power sensor hub and already
contains the manufacturer's gait model, which recounting accelerometer peaks in the background
would not.

The main properties of the pipeline:

- the 5 steps/s limit is cumulative and kept in the database, so it holds across a process
  restart;
- events with the same timestamp are one batched delivery and share one budget;
- an event with a timestamp from the past does not wind the algorithm's trusted clock back;
- the accelerometer decision is built only from a causal window, with no future or stale
  samples;
- the window size is checked by time as well as by the number of samples;
- a rhythm needs two observed periods, so a single matched pair in the autocorrelation cannot
  produce a "perfect" periodicity;
- steps from a batched delivery are attributed to the day of the event, and a pending bout
  confirmed after midnight is split across both days;
- local phone steps, the Health Connect total and direct sources are kept in separate tables;
- a constant GNSS offset does not confirm itself as a travelled stretch of the route;
- the diagnostic trace stores the event timestamp and the delivery timestamp separately.

The threshold values are a first empirical calibration; they are not validated across people,
ways of carrying the phone, phone models and transport (section 9).

Source on what the sensors are for: [Android `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR`](https://developer.android.com/reference/android/hardware/Sensor#TYPE_STEP_COUNTER).

## 2. The implemented step pipeline

### 2.1. Primary source and persisted state

`TYPE_STEP_COUNTER` reports a cumulative value since the device booted. `StepAccumulator` keeps
the last value, the boot id, the limiter's time anchors and the state of the current bout in a
`StepState`, which is stored in Room. The state and the daily and hourly totals are written in a
single transaction.

How an increase of the counter is processed (the `StepVerdict` in brackets):

1. the first value becomes the baseline and is not credited (`BASELINE`);
2. a repeat of the same value gives a zero delta (`DUPLICATE`);
3. after a device reboot a new baseline is created (`REBOOT_BASELINE`). The boot id is the wall
   clock minus the uptime; a shift of more than 60 seconds is a reboot unless the sensor clock and
   the counter both ran forward and the shift is smaller than the uptime so far, which is the wall
   clock being set;
4. if the counter goes backwards without a reboot, it is rebased without crediting
   (`COUNTER_RESET`);
5. a timestamp strictly in the past is rejected (`NON_MONOTONIC_TIME`), but the raw counter
   advances and the last trusted timestamp does not change; the exception is a rollback of more
   than an hour: then the stored clock is wrong (an event with epoch time instead of time since
   boot), the state takes a fresh baseline from the incoming event, and nothing is credited;
6. the delta goes through the cumulative rate limiter (`IMPOSSIBLE_RATE`);
7. accelerometer evidence against gait (section 3) rejects the delta (`NO_GAIT`), except for a
   "doubtful" window inside an already confirmed bout;
8. a new bout waits for confirmation by the bout filter (`BOUT_PENDING`);
9. a confirmed bout credits further deltas immediately (`CREDITED`).

Each event is decided as it arrives (`StepLedger`). With the screen on, the decision is written at
once; with the screen off, decisions wait and are written together once the oldest is a minute old
or within seconds of the screen coming on. The raw counter advances even on rejection, so a written
false delta cannot come back after a process restart and be credited a second time. A process
killed before a write loses the decisions, not the steps: the stored counter is older, and the next
event's delta carries those steps and is judged afresh.

### 2.2. Cumulative rate limiter

The limit is kept in the database state rather than computed separately for each callback:

```text
deltaWindow = rawCounter - counterAtWindowStart
allowed = ceil(secondsFromWindowStart * 5) + 1
```

The window lasts 2 seconds. It rolls over only after the previous timestamp group has ended. All
callbacks with the same `SensorEvent.timestamp` therefore share one cumulative budget. This
supports the ordinary batched delivery of the sensor hub, and neither a series of small deltas nor
many events with the same time gets around the limit.

The `+1` tolerance is there for an event on the boundary of a time bucket. If the cumulative
delta is above the limit, it is rejected in full: the app does not know which part of it is
real. The anchor and the counter are saved, so a rejected burst does not get a new budget on
each following callback within the same window.

### 2.3. Bout filter

The start of a new bout stays pending. It is confirmed when two conditions hold at the same
time:

- at least 3 steps have accumulated;
- at least 0.4 seconds have passed between the first and the confirming event.

A pause of more than 30 seconds starts a new bout. The gap is this large because the step counter
is often delivered in batches several or tens of seconds apart. Protection against a random pair
of movements comes from the minimum of 3 steps, not from a short timeout.

The exception to the second condition: a pending batch of at least 3 steps that never received
a second event is credited at the moment the next bout starts, that is after a pause of more
than 30 seconds. A coarse sensor may deliver a whole short walk as one cumulative jump.

A rejected `NO_GAIT`, an exceeded rate or a timestamp from the past does not destroy an already
confirmed bout, but the rejected delta itself does not go into it. The bout is reset on a real
break in the timeline: a reboot, a reset of the cumulative counter or a clock rollback of more than
an hour.

### 2.4. Event day and midnight

The day is not computed from the time the callback runs. The code subtracts the delivery delay
from the wall-clock time of receipt:

```text
eventEpoch = deliveryEpoch - (deliveryElapsedRealtime - eventTimestamp)
```

If the first pending steps happened before midnight and the bout was confirmed after it,
`creditedByDay` splits them across the two corresponding days in one transaction.

The hourly rows file a credit under the hour it was delivered in; the earlier day of a bout that
straddles midnight gets its share in its last hour. The day total stays the figure of record.

`TYPE_STEP_COUNTER` itself has a limitation: if one callback brought `rawDelta > 1`, the app has no
timestamps for each step inside that delta. In a rare batch across midnight, the whole delta is
attributed to the time of the counter event. It can be split exactly only with additional
`TYPE_STEP_DETECTOR` timestamps, which the app does not read (section 8).

The basis for converting the clock: Android defines `SensorEvent.timestamp` as the monotonic
time of the event on the same time base as `SystemClock.elapsedRealtimeNanos()`:
[SensorEvent.timestamp](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp).

## 3. The implemented accelerometer gate

`StepService` requests the accelerometer with a period of 40,000 µs (25 Hz) without batching,
while the phone has a step counter or a route is recording, so that the window covering a step
counter event already exists at the moment of the decision; the actual rate depends on the
device. `SENSOR_DELAY_NORMAL` with batching of up to 2 seconds is used for the step counter, the
barometer and, during a route, the temperature sensor. The Sensors screen registers its own
unbatched 25 Hz listener for its live view; `SensorFeed` drops samples whose timestamp it has
already seen. The algorithm does not rely on a fixed number of samples: the last 12 seconds are
kept in memory, and one decision uses no more than 6 seconds of history.

For a step counter event, a strictly causal window is chosen:

- only samples with `sample.timestamp <= event.timestamp`;
- no future live sample is substituted as a fallback;
- the end of the window must be no more than 0.2 seconds older than the event;
- after a gap between samples of more than 0.2 seconds, only the new continuous segment is
  used;
- classification needs at least 16 samples and 2.5 seconds of coverage.

If these conditions are not met, the result is `UNKNOWN`. It is not proof of walking, but it
deliberately does not veto the step counter that the platform has already filtered. That is, the
policy is neither fail-closed nor "the accelerometer confirmed gait", but "the auxiliary sensor did
not have enough data". `WALKING` and `RUNNING` allow the delta; `STILL`, `VEHICLE`, `SHAKING` and
`NON_GAIT` are negative evidence and block it.

One exception for `NON_GAIT` and `VEHICLE`: a window with the energy of a walking person
(raw std of at least 2.5 m/s²) and a rhythm in the gait band is treated as doubt rather than a
veto (`MotionReading.stepsDoubtful`). Its steps are credited if the bout is already confirmed
and no more than 30 seconds have passed since its last confirmed step: this is a staircase on
which the phone moves from the hand to a pocket. Such a window does not extend the bout, and
outside a confirmed bout the same movement is rejected as `NO_GAIT`.

This is a trade-off in favour of recall: fail-closed on `UNKNOWN` would lose real batched steps
whose accelerometer window has not been delivered yet. The price of the trade-off is that, when
the accelerometer is absent for a long time, a false trigger from the manufacturer's counter can
pass without a second check.

### 3.1. Features

The current classification uses:

1. the acceleration magnitude `sqrt(x² + y² + z²)`;
2. a moving average over 3 samples;
3. the raw standard deviation for `STILL`, `SHAKING` and the vehicle context;
4. the energy of the smoothed signal for the gait band;
5. a search for the maximum of the normalised autocorrelation in the range 0.5–4 Hz;
6. the shortest lag within 90% of the best correlation, so as not to pick a harmonic;
7. a requirement of at least two observed periods;
8. the phone's position from the direction of the mean gravity vector;
9. the screen state as additional context.

A phone in a pocket often sees one repetition per full stride, that is half the real step
frequency. So gait is accepted if the rhythm found, or double it, falls within the allowed
range.

### 3.2. Actual thresholds

| Feature | Implemented rule |
|---|---|
| sufficient window | at least 16 samples, 2.5 s of coverage, gap no more than 0.2 s |
| analysed history | up to 6 s; kept for up to 12 s |
| stillness | raw std below 0.35 m/s² |
| strong shaking | raw std above 12 m/s² |
| gait energy | smoothed std at least 0.8 m/s² |
| upper gait energy | raw std no more than 10 m/s² |
| gait rhythm | cadence or `cadence × 2` in the range 0.8–3.8 Hz |
| gait periodicity | at least 0.40 |
| running | direct cadence at least 2.35 Hz and a window no shorter than 5 s |
| phone lying flat, screen off | raw std below 1.5 m/s² rules out gait; a flat, screen-off window that is not gait is `VEHICLE` |
| doubt instead of veto | `NON_GAIT` or `VEHICLE` with raw std at least 2.5 m/s² and a rhythm in the gait band |

The 0.8 Hz bound keeps slow walking. The gait energy of 0.8 m/s² and the periodicity of 0.40 sit
between what a bus ride produces and what walking produces: road and engine vibration is periodic
too, so the energy floor is what keeps a ride out. They are a first empirical calibration, not
universal physiological norms. Every threshold is a field of `GateConfig`.

The algorithm takes the actual mean sample rate into account, but does not resample the irregular
signal onto a uniform time grid. Index-based autocorrelation assumes a roughly uniform stream
within the allowed gap.

## 4. Route: correlating GNSS and motion

GNSS takes no part in counting daily steps and is never added to the step counter. It is used
only while recording a route, for the line, the distance and the classes
`STOPPED/WALKING/RUNNING/VEHICLE`.

The basic quality gate in `StepService` rejects a fix without accuracy, with a horizontal accuracy
worse than 25 m or an age of more than 30 seconds. `RouteCorrelationFilter` then rejects a
timestamp that does not increase or a geometric speed above 70 m/s, and runs a filter with one
point of look-ahead on the remaining points:

1. a suspicious fix is held as pending;
2. the next point returning to the previous trajectory turns the pending fix into
   `RETURN_SPIKE`;
3. a continuation with consistent direction, speed and progress confirms real movement;
4. the last unconfirmed jump is kept as raw, but does not enter the line or the distance.

A continuation has to keep the direction of the jump (within a 60° cone) and show measurable
progress; a zero vector counts as a non-matching direction. A second point at the same offset
location is therefore not a continuation, and a constant GNSS offset stays out of the route until
real progress or a return. If Doppler speed is used for confirmation, it must agree with both
consecutive legs, not just one; above 43 km/h (12 m/s) a jump is confirmed only when Doppler
agrees. Slow real progress of 0.5–1 m at a time is not lost: it accumulates relative to the first
suspicious point and is confirmed after enough net/radial progress. When 15 fixes in a row have
been refused for at least a minute, and the current fix is further from the last confirmed one
than a walk covers in that time (after a metro ride, for example), the filter starts again at the
current fix (`REANCHORED`) and the line starts a new stretch.

Speed is smoothed by a median over three observations. A speed above 7 km/h does not become
walking. Running is a `RUNNING` window with a cadence of at least 2.35 Hz and a periodicity of
at least 0.35, or a stride at two consecutive fixes above 7 km/h with a median of at most 25 km/h;
the stride is periodic gait at any rhythm, or pedometer steps within 10 seconds of the fix while
the accelerometer window is `UNKNOWN` or reads a hard landing (`NON_GAIT` or `SHAKING` with raw
std of at least 2.5 m/s²). Without such evidence, sustained fast movement is transport. At walking
pace GPS can only stand in for missing (`UNKNOWN`) IMU evidence, not for a positive `NON_GAIT`
veto. A stop requires 15 seconds of a median speed of at most 2 km/h and movement within
max(2.5 m, 0.4 × accuracy), and is not declared while the pedometer counts steps that the
accelerometer reads as gait or cannot classify.

Distance is summed from an anchor: a fix has to move at least 3 m, or half its stated accuracy,
from it before its metres count, and a confirmed stop discards the wander. A leg covered faster
than a walk (7 km/h) or a run (25 km/h) allows, after taking off the 25 m the quality gate lets
in, is credited as transport whatever the fix at its end says.

The 7 km/h threshold is a product trade-off, not a physiological constant.
`Location.getSpeed()` is used when present and may reflect a GNSS Doppler estimate.

Source: [Android `Location`](https://developer.android.com/reference/android/location/Location).

## 5. Altitude and environment

Route recording uses additional, non-GPS signals:

- `TYPE_PRESSURE` gives the relative barometric altitude;
- the first usable GPS altitude sets the absolute offset;
- gain/loss is measured from an exponentially smoothed altitude and changes only after a
  difference of at least 1.5 m with the barometer, or at least 3 m (or the fix's vertical
  accuracy, if larger) on GPS altitude alone; nothing is booked during a confirmed stop;
- `TYPE_AMBIENT_TEMPERATURE` is stored only if the physical sensor exists;
- pressure changes of 1.5 hPa and temperature changes of 2 °C between points are flagged in the
  route telemetry.

The barometer does not change the number of steps. Besides the route's altitude profile, it
accumulates climbing on foot in whole floors of 3 m all day, not only while recording
(`FloorTracker` in `Barometer.kt`): a rise counts only if it is at least 3 m, completes within
60 seconds, is no faster than 1 m/s, and the motion gate saw walking in at least half of its
samples.

Sources:

- [Android `SensorManager.getAltitude`](https://developer.android.com/reference/android/hardware/SensorManager#getAltitude(float,%20float));
- [Android sensor types](https://developer.android.com/reference/android/hardware/Sensor).

## 6. Health Connect: correlating sources without double counting

Phone steps and external daily totals have separate tables and a provenance marker:

- `days` — the local accumulator (`hourly_steps` holds its hours);
- `external_step_days` — the aggregated external Health Connect total;
- `external_step_origin_days` — the share of each writing app in that total: one row per
  (package, day). Provenance only; no step arithmetic reads it;
- `direct_ble_step_days` — daily totals from direct Bluetooth sources and file imports, one row
  per (source, day);
- `legacy_step_days` — the dates of `days` rows that cannot be proven phone-owned (rows carried
  over from database versions before 15, and days restored from a backup without provenance);
  they stay visible but are never published back.

The UI shows the maximum of the local total, the Health Connect total and each direct source
(`effectiveStepTotal`), not the sum: the phone and the watch may have observed the same walk. During sync, raw `StepsRecord` entries are needed only to find the external origins.
The app's own package, the legacy origin `android` and the available SPN of the current phone
are excluded. Then `COUNT_TOTAL` is requested through the Health Connect daily aggregate, which
applies source priority and deduplication instead of adding up overlapping raw records by hand.

The same aggregate is requested again for each external origin separately
(`dataOriginFilter = setOf(origin)`) — these are the `external_step_origin_days` rows. They
cannot be computed from raw records, for the same reason that other apps' records are not added
up: one app publishes a walk both as a session and as a daily total, and only the platform knows
which of its own records overlap. The shares are not added to one another and play no part in
`effectiveStepTotal`; only the sources screen reads them, to name the app instead of the words
"Health Connect". A day one app accounts for entirely is shown under that app's name; a day
several apps fed stays Health Connect's, with the joint total, and lists the apps under it with
their own figures.

Sources: [Android Health Connect aggregate](https://developer.android.com/health-and-fitness/health-connect/aggregate-data),
[on-device steps and SPN](https://developer.android.com/health-and-fitness/health-connect/read-data#mobile-steps).

Sync refreshes the external totals for the last 30 days. A provably local day is published to
Health Connect unless another source counted as many steps for it or more (`mergeStepDays`); a
smaller external total does not hold the day back. For a "covered" day other than the current
one, the app's record is withdrawn. Legacy days are never published, and any record the app
published for them is withdrawn. After a reinstall, the days this app itself published over the
last year are read back into `days` where it has none, except today. A backup carries the totals
and the provenance markers; a backup without provenance (schema 1) marks all its days legacy on
restore.

Choosing `max` rather than adding is safe against double counting, but it is still a daily
heuristic: it cannot merge non-overlapping phone and watch intervals within a day.

## 7. Diagnostic trace v2

The trace CSV (schema version 2), recorded from the Sensors screen, stores the data needed for a
reproducible replay:

- the timestamp at which the sensor event occurred;
- a separate elapsed-realtime timestamp of its delivery to the app;
- motion features and the final gait verdict;
- the window source (`EVENT_WINDOW` or `INSUFFICIENT_DATA`);
- the window's start, end, number of samples, coverage and maximum gap;
- the raw counter, delta, credited delta and the accumulator's verdict;
- the schema version, the gate configuration, the full persisted `StepState` at the start and
  session metadata;
- correct CSV escaping of the metadata.

A recording keeps at most 200,000 rows (about 2 hours at 25 Hz; the newest rows are kept and the
dropped ones counted) and stops itself 3 hours after its first sample. `StepService` keeps the CPU
awake while a trace records. The trace lives in memory until it is saved where the user chooses.
The `StepState` in the trace is read from Room just before `SensorTrace.start()`, not in step with
the service's decisions, so a decision not yet written at that moment is missing from it.

## 8. Not built

- `TYPE_STEP_DETECTOR` timestamps, to match a counter delta to its gait window and split a batch
  across midnight exactly; the count would still come only from the counter.
- Gyroscope and rotation-vector features (telling gait from transport vibration and shaking,
  removing gravity in a world frame) and resampling onto a uniform time grid.
- Proximity and stationary/significant-motion sensors as carrying-position and stillness context.
- A confidence level with reasons stored with each decision instead of a Boolean.
- Fallback step counting from the accelerometer on a device without `TYPE_STEP_COUNTER`.

## 9. Limitations

- On a device without `TYPE_STEP_COUNTER` there is no step counting from the accelerometer.
- `UNKNOWN` trusts the platform counter without a second check.
- The thresholds are not adapted to the noise floor of a particular device.
- Autocorrelation assumes roughly uniform samples.
- The timestamp of a batch delta does not reveal the time of each individual step.
- A timezone change between the event and its delivery can complicate day attribution.
- The daily maximum of phone and wearable totals does not merge non-overlapping wear intervals.
- The GNSS filter reduces spikes, but does not remove every urban canyon case.
- Unit tests (`StepAccumulatorTest`, `MotionGateTest`, `RouteMotionTest`, `BarometerTest`,
  `SensorTraceTest`) check the logic, but do not measure false positives or negatives on a person.

Useful material for comparing approaches:

- [A Smartwatch Step-Counting App for Older Adults](https://aging.jmir.org/2022/3/e33845/);
- [Fourteen days free-living evaluation of an open-source algorithm](https://link.springer.com/article/10.1186/s42490-023-00071-9);
- [Apple Watch calibration](https://support.apple.com/en-au/105048);
- [OpenTracks](https://github.com/OpenTracksApp/OpenTracks).
