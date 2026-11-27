package com.pwnall.health

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil

/** Everything we need to persist between samples to never double-count. */
data class StepState(
    val bootId: Long,
    val lastCumulative: Long,
    /** Timestamp of the last counter increase, in nanos since boot. */
    val lastEventNanos: Long = 0,
    /** Uncredited start of a possible walking bout. */
    val pendingSteps: Long = 0,
    val boutStartedNanos: Long = 0,
    val boutLastNanos: Long = 0,
    val boutConfirmed: Boolean = false,
    /** Start of the persisted rate-limiter window. */
    val rateWindowStartedNanos: Long = -1,
    /** Counter value at [rateWindowStartedNanos]. */
    val rateWindowStartCounter: Long = -1,
    /** Day containing the first pending steps, empty for legacy/in-memory callers. */
    val pendingDay: String = "",
    /** Part of [pendingSteps] that belongs to [pendingDay]. */
    val pendingDaySteps: Long = 0,
)

enum class StepVerdict {
    BASELINE,
    DUPLICATE,
    COUNTER_RESET,
    REBOOT_BASELINE,
    NON_MONOTONIC_TIME,
    IMPOSSIBLE_RATE,
    NO_GAIT,
    BOUT_PENDING,
    CREDITED,
}

/** New state to persist, the raw sensor increase, and the part safe to credit. */
data class StepUpdate(
    val state: StepState,
    val rawDelta: Long,
    val creditedDelta: Long,
    val verdict: StepVerdict,
    /** Event-day split; needed when a pending bout is confirmed just after midnight. */
    val creditedByDay: Map<String, Long> = emptyMap(),
)

/**
 * Turns `Sensor.TYPE_STEP_COUNTER`'s cumulative-since-boot value into a delta.
 *
 * Pure Kotlin on purpose: this is the whole correctness surface of the app, and it is
 * exhaustively unit-testable on the JVM (see StepAccumulatorTest).
 *
 * Re-feeding the same [rawCounter] yields a delta of 0, which is what makes service restarts,
 * process death and duplicate listener registrations harmless.
 */
object StepAccumulator {

    /** Wall-clock jitter (NTP corrections, drift) tolerated before two bootIds mean two boots. */
    private const val BOOT_ID_TOLERANCE_MS = 60_000L

    private const val NANOS_PER_MILLI = 1_000_000L

    /** 300 steps/min is deliberately generous, but nine steps in a second cannot pass. */
    private const val MAX_STEP_HZ = 5.0

    /** Sensor delivery can bunch two boundary events into the same timing bucket. */
    private const val RATE_BURST_ALLOWANCE = 1L

    /** Bound the cumulative rate, instead of granting a fresh allowance to every callback. */
    private const val RATE_WINDOW_NANOS = 2_000_000_000L

    /**
     * A pause longer than this starts a new walking bout.
     *
     * Sized for how the hardware actually reports, not for how walking feels: a batching sensor hub
     * flushes `TYPE_STEP_COUNTER` every few tens of seconds, so two events inside one continuous
     * walk can be far apart. At 2.5 s every flush would begin a fresh bout, every bout would stay
     * PENDING and almost nothing would ever be credited — 900 steps arriving and 5 counted. The ≥
     * [MIN_BOUT_STEPS] rule, not this gap, is what keeps isolated handling out.
     */
    private const val BOUT_GAP_NANOS = 30_000_000_000L

    /**
     * A rewind no sane within-boot sensor timeline can produce, so the stored clock is the lie.
     *
     * Some drivers stamp `TYPE_STEP_COUNTER` with CLOCK_REALTIME epoch nanos instead of nanos since
     * boot. One such event drags the stored clock decades forward, and because the clock is never
     * rewound every honest event after it reads as non-monotonic — the pedometer stays muted until
     * a reboot. An hour is far past any real batching flush and far short of that glitch.
     */
    private const val CLOCK_REBASE_NANOS = 3_600_000_000_000L

    /** Isolated handling motions must not become steps. */
    private const val MIN_BOUT_STEPS = 3L
    private const val MIN_BOUT_DURATION_NANOS = 400_000_000L

    // One early return per rejected sensor delta: the rejection list is the algorithm, and every
    // branch here is one way a real sensor lies. Splitting it would hide the order they apply in.
    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    fun accumulate(
        prev: StepState?,
        rawCounter: Long,
        bootId: Long,
        eventTimestampNanos: Long,
        gaitEvidence: Boolean,
        day: String = "",
        doubtful: Boolean = false,
    ): StepUpdate {
        // First sample ever: adopt the counter as baseline, credit nothing. The steps it already
        // holds were taken before the app existed.
        if (prev == null) {
            return StepUpdate(
                baseline(bootId, rawCounter, eventTimestampNanos),
                0,
                0,
                StepVerdict.BASELINE,
            )
        }

        // A reboot destroys both the sensor timeline and our evidence window. Treat the first
        // value as a baseline: crediting the entire since-boot value here would create a large,
        // unverified burst on the current day.
        if (isReboot(prev, rawCounter, bootId, eventTimestampNanos)) {
            return StepUpdate(
                baseline(bootId, rawCounter, eventTimestampNanos),
                rawCounter,
                0,
                StepVerdict.REBOOT_BASELINE,
            )
        }

        // Counter went backwards without a reboot: a sensor glitch. Rebase, credit nothing —
        // crediting rawCounter here could inject hundreds of thousands of steps.
        if (rawCounter < prev.lastCumulative) {
            return StepUpdate(
                baseline(bootId, rawCounter, eventTimestampNanos),
                0,
                0,
                StepVerdict.COUNTER_RESET,
            )
        }

        val rawDelta = rawCounter - prev.lastCumulative
        if (rawDelta == 0L) {
            return StepUpdate(prev.copy(bootId = bootId), 0, 0, StepVerdict.DUPLICATE)
        }

        // Advance the counter and the clock but keep the walk in progress. Wiping the bout on every
        // rejected event would mean one unclassifiable window in the middle of a walk sends the next
        // event back to the start of a fresh bout, so a real walk never accumulates enough to be
        // confirmed. Only a broken timeline — a reboot, a counter reset, or a clock from another
        // base — justifies forgetting it.
        if (eventTimestampNanos < prev.lastEventNanos) {
            // A rewind this large is not the event lying, it is the stored clock: one epoch-stamped
            // callback parked the clock decades ahead and every honest event since has read as
            // non-monotonic. Re-anchor on the arriving event so the mute lasts one event, not the
            // whole boot session. Written as a subtraction on the stored side because the
            // difference overflows for a wildly negative stamp.
            if (eventTimestampNanos < prev.lastEventNanos - CLOCK_REBASE_NANOS) {
                return StepUpdate(
                    baseline(bootId, rawCounter, eventTimestampNanos),
                    rawDelta,
                    0,
                    StepVerdict.NON_MONOTONIC_TIME,
                )
            }
            // Otherwise consume the raw counter so it cannot reappear, but never rewind the trusted
            // clock or its rate anchor. Rewinding would make the next normal event look artificially old
            // and let a malformed callback poison every decision after it.
            val state = prev.copy(bootId = bootId, lastCumulative = rawCounter)
            return StepUpdate(state, rawDelta, 0, StepVerdict.NON_MONOTONIC_TIME)
        }

        val storedAnchorIsUsable =
            prev.rateWindowStartedNanos >= 0 &&
                prev.rateWindowStartCounter >= 0 &&
                prev.rateWindowStartedNanos <= prev.lastEventNanos &&
                prev.rateWindowStartCounter <= prev.lastCumulative
        val storedWindowStarted = if (storedAnchorIsUsable) {
            prev.rateWindowStartedNanos
        } else {
            prev.lastEventNanos
        }
        val storedWindowCounter = if (storedAnchorIsUsable) {
            prev.rateWindowStartCounter
        } else {
            prev.lastCumulative
        }
        // Roll only after the previous timestamp group is complete. Equal-timestamp callbacks are
        // one sensor-hub flush and must share a cumulative budget; rolling on its first callback
        // would recreate the unlimited same-timestamp loophole.
        val rollRateWindow = eventTimestampNanos > prev.lastEventNanos &&
            prev.lastEventNanos - storedWindowStarted >= RATE_WINDOW_NANOS
        val rateWindowStarted = if (rollRateWindow) prev.lastEventNanos else storedWindowStarted
        val rateWindowCounter = if (rollRateWindow) prev.lastCumulative else storedWindowCounter

        val carried = prev.copy(
            bootId = bootId,
            lastCumulative = rawCounter,
            lastEventNanos = eventTimestampNanos,
            rateWindowStartedNanos = rateWindowStarted,
            rateWindowStartCounter = rateWindowCounter,
        )

        val elapsedSeconds = (eventTimestampNanos - rateWindowStarted) / 1e9
        val deltaInRateWindow = rawCounter - rateWindowCounter
        val maximumPlausible =
            ceil(elapsedSeconds * MAX_STEP_HZ).toLong() + RATE_BURST_ALLOWANCE
        if (deltaInRateWindow > maximumPlausible) {
            return StepUpdate(carried, rawDelta, 0, StepVerdict.IMPOSSIBLE_RATE)
        }
        if (!gaitEvidence) {
            // A stride the gate could not confirm ([MotionReading.stepsDoubtful]) inside a walk it has
            // just confirmed: the stairs climbed while the phone went into a pocket. It does not carry
            // the walk on — only gait evidence does — so the doubt lasts one bout gap past the last
            // confirmed step, and the same movement with no walk around it stays out.
            val insideWalk = prev.boutConfirmed &&
                prev.boutLastNanos > 0 &&
                eventTimestampNanos - prev.boutLastNanos in 0..BOUT_GAP_NANOS
            return if (doubtful && insideWalk) {
                StepUpdate(carried, rawDelta, rawDelta, StepVerdict.CREDITED, creditForDay(day, rawDelta))
            } else {
                StepUpdate(carried, rawDelta, 0, StepVerdict.NO_GAIT)
            }
        }

        val cleared = StepState(
            bootId = bootId,
            lastCumulative = rawCounter,
            lastEventNanos = eventTimestampNanos,
            rateWindowStartedNanos = rateWindowStarted,
            rateWindowStartCounter = rateWindowCounter,
        )

        // From zero, not from one: two events out of the same flush share a timestamp and are the
        // same walk, so a gap of exactly nothing continues the bout rather than starting one.
        val continuingBout = prev.boutLastNanos > 0 &&
            eventTimestampNanos - prev.boutLastNanos in 0..BOUT_GAP_NANOS
        if (!continuingBout) {
            val state = cleared.copy(
                pendingSteps = rawDelta,
                boutStartedNanos = eventTimestampNanos,
                boutLastNanos = eventTimestampNanos,
                pendingDay = day,
                pendingDaySteps = rawDelta.takeIf { day.isNotEmpty() } ?: 0,
            )
            // A coarse sensor can deliver a complete short walk in one cumulative jump. It cannot
            // satisfy the duration check until another callback exists; when that callback is far
            // enough away to prove a different bout, settle the old batch instead of overwriting it.
            val expired = prev.pendingSteps.takeIf { it >= MIN_BOUT_STEPS } ?: 0
            return StepUpdate(
                state = state,
                rawDelta = rawDelta,
                creditedDelta = expired,
                verdict = if (expired > 0) StepVerdict.CREDITED else StepVerdict.BOUT_PENDING,
                // Only when the batch is settled. A per-day map is credited on its own, so
                // emitting one for a batch too small to confirm would post exactly the handling
                // motion [MIN_BOUT_STEPS] exists to reject, and the day would outrun the total.
                creditedByDay = if (expired > 0) confirmedBoutCredits(prev, rawDelta = 0, day) else emptyMap(),
            )
        }

        if (prev.boutConfirmed) {
            val state = cleared.copy(
                boutStartedNanos = prev.boutStartedNanos,
                boutLastNanos = eventTimestampNanos,
                boutConfirmed = true,
            )
            return StepUpdate(
                state,
                rawDelta,
                rawDelta,
                StepVerdict.CREDITED,
                creditForDay(day, rawDelta),
            )
        }

        val pending = prev.pendingSteps + rawDelta
        val boutDuration = eventTimestampNanos - prev.boutStartedNanos
        return if (pending >= MIN_BOUT_STEPS && boutDuration >= MIN_BOUT_DURATION_NANOS) {
            val state = cleared.copy(
                boutStartedNanos = prev.boutStartedNanos,
                boutLastNanos = eventTimestampNanos,
                boutConfirmed = true,
            )
            StepUpdate(
                state,
                rawDelta,
                pending,
                StepVerdict.CREDITED,
                confirmedBoutCredits(prev, rawDelta, day),
            )
        } else {
            val firstDay = prev.pendingDay.ifEmpty { day }
            val firstDaySteps = when {
                firstDay.isEmpty() -> 0
                prev.pendingDay.isEmpty() -> pending
                prev.pendingDay == day -> prev.pendingDaySteps + rawDelta
                else -> prev.pendingDaySteps
            }
            val state = cleared.copy(
                pendingSteps = pending,
                boutStartedNanos = prev.boutStartedNanos,
                boutLastNanos = eventTimestampNanos,
                pendingDay = firstDay,
                pendingDaySteps = firstDaySteps,
            )
            StepUpdate(state, rawDelta, 0, StepVerdict.BOUT_PENDING)
        }
    }

    /**
     * A moved [bootId] is evidence of a reboot only when the boot timeline moved with it.
     *
     * bootId is wall clock minus uptime, so setting the clock — an NTP correction, a timezone
     * change with automatic time, a device-management push — shifts it while the device keeps
     * running. A reboot cannot leave the sensor clock and the counter both running forward, and it
     * moves bootId on by at least the uptime this session had already reached, so a shift smaller
     * than that uptime is the clock being set and its steps are real.
     */
    private fun isReboot(
        prev: StepState,
        rawCounter: Long,
        bootId: Long,
        eventTimestampNanos: Long,
    ): Boolean {
        val shiftMs = abs(bootId - prev.bootId)
        if (shiftMs <= BOOT_ID_TOLERANCE_MS) return false
        val clockWasSet = eventTimestampNanos >= prev.lastEventNanos &&
            rawCounter >= prev.lastCumulative &&
            shiftMs < prev.lastEventNanos / NANOS_PER_MILLI
        return !clockWasSet
    }

    private fun baseline(bootId: Long, rawCounter: Long, timestampNanos: Long) = StepState(
        bootId = bootId,
        lastCumulative = rawCounter,
        lastEventNanos = timestampNanos,
        rateWindowStartedNanos = timestampNanos,
        rateWindowStartCounter = rawCounter,
    )

    private fun creditForDay(day: String, steps: Long): Map<String, Long> =
        if (day.isEmpty() || steps <= 0) emptyMap() else mapOf(day to steps)

    private fun confirmedBoutCredits(
        prev: StepState,
        rawDelta: Long,
        day: String,
    ): Map<String, Long> {
        if (day.isEmpty()) return emptyMap()
        val firstDay = prev.pendingDay
        val firstDaySteps = prev.pendingDaySteps.coerceIn(0, prev.pendingSteps)
        if (firstDay.isEmpty() || firstDay == day || firstDaySteps == 0L) {
            return mapOf(day to prev.pendingSteps + rawDelta)
        }
        return mapOf(
            firstDay to firstDaySteps,
            day to prev.pendingSteps - firstDaySteps + rawDelta,
        ).filterValues { it > 0 }
    }
}

/** One `TYPE_STEP_COUNTER` reading, with what was known about it the moment it arrived. */
data class StepReading(
    val rawCounter: Long,
    val bootId: Long,
    /** The day the event happened on, by [SensorEventClock.day]. */
    val day: String,
    val eventTimestampNanos: Long,
    /** The motion gate's verdict; see [StepAccumulator.accumulate]. */
    val gaitEvidence: Boolean,
    val doubtful: Boolean,
    /** Wall clock at delivery: the hour a credit is filed under, and the time of the last step. */
    val deliveredAtMs: Long,
)

/** One write: the accumulator's state, and the steps credited since the write before it. */
class StepWrite(
    val state: StepState,
    /** Wall clock of the last credited step: [StateRow.lastStepAtMs]. */
    val lastStepAtMs: Long,
    /** Steps by day, and by the hour of the day they were credited in. */
    val credits: Map<Pair<String, Int>, Long>,
    /** The last reading's day: the total the notification shows. */
    val day: String,
)

/**
 * What the accumulator has decided since its last write.
 *
 * Each reading is decided the moment it arrives, against the state the one before it left, and
 * [take] hands the state and the steps it credited over together, for one transaction: a write can
 * never carry the counter on without the steps it credited, nor credit steps the counter has not
 * passed.
 *
 * The hour is stamped when the steps are credited, not when they were taken: a batch the sensor held
 * back is filed under the hour it was delivered in. Over ordinary walking the two are the same; after
 * a long doze they are not, which is why the hourly row is a sketch of the day and the day total
 * remains the figure of record.
 */
class StepLedger(private var state: StepState?, private var lastStepAtMs: Long) {
    private val credits = LinkedHashMap<Pair<String, Int>, Long>()
    private var day = ""

    /** When the oldest decision not yet written was made, by the clock [add] is handed; null for none. */
    var unwrittenSince: Long? = null
        private set

    /**
     * Decides [reading], at [now] on whatever clock [unwrittenSince] is read against, filing its
     * credit under the hour of its delivery in [zone].
     */
    fun add(reading: StepReading, now: Long, zone: ZoneId): StepUpdate {
        val update = StepAccumulator.accumulate(
            state,
            reading.rawCounter,
            reading.bootId,
            reading.eventTimestampNanos,
            reading.gaitEvidence,
            reading.day,
            reading.doubtful,
        )
        state = update.state
        if (update.creditedDelta > 0) lastStepAtMs = reading.deliveredAtMs
        val credited = update.creditedByDay.ifEmpty {
            if (update.creditedDelta > 0) mapOf(reading.day to update.creditedDelta) else emptyMap()
        }
        val delivered = Instant.ofEpochMilli(reading.deliveredAtMs).atZone(zone)
        credited.forEach { (creditDay, steps) ->
            if (steps <= 0) return@forEach
            val key = creditDay to creditedHour(creditDay, delivered.toLocalDate().toString(), delivered.hour)
            credits[key] = (credits[key] ?: 0L) + steps
        }
        day = reading.day
        if (unwrittenSince == null) unwrittenSince = now
        return update
    }

    /** Everything decided since the last call, or null when nothing has been. */
    fun take(): StepWrite? {
        val current = state
        if (current == null || unwrittenSince == null) return null
        return StepWrite(current, lastStepAtMs, credits.toMap(), day).also {
            credits.clear()
            unwrittenSince = null
        }
    }
}

/**
 * The hour a day's credit lands in: the current one for today, and the day's last hour for the
 * earlier day a bout that straddled midnight also credits — those steps were taken before it ended,
 * never at the post-midnight hour the batch arrived in.
 *
 * A day *later* than today is the mirror of that: a bout whose first day was stored before the
 * clock moved backwards — a westward timezone change, or a stepped time correction — confirms
 * against a day the phone has since un-reached. Those steps belong in that day's first hour, never
 * its last, which is reserved for the earlier half of a straddling bout; filing them at 23:00 would
 * draw an evening walk on a day that has not happened.
 */
internal fun creditedHour(day: String, today: String, currentHour: Int): Int =
    when {
        day == today -> currentHour

        // ISO-8601 days compare lexicographically in calendar order, so this is "not reached yet".
        day > today -> 0

        else -> 23
    }
