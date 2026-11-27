package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepAccumulatorTest {

    private val boot = 1_780_000_000_000L
    private val nextBoot = boot + 3_600_000L

    private fun confirmedState(
        raw: Long = 100,
        timeNanos: Long = 1_000_000_000L,
    ) = StepState(
        bootId = boot,
        lastCumulative = raw,
        lastEventNanos = timeNanos,
        boutStartedNanos = timeNanos - 2_000_000_000L,
        boutLastNanos = timeNanos,
        boutConfirmed = true,
    )

    /**
     * Steps the gate doubted — a walker's energy, no clean rhythm — inside a walk it had just confirmed
     * are the stairs climbed while the phone went into a pocket. The same doubt with no walk around it,
     * or a bout gap after the last confirmed step, stays out; and a doubt never carries the walk on.
     */
    @Test
    fun `doubted steps count inside a confirmed walk and nowhere else`() {
        val walking = confirmedState(raw = 100, timeNanos = 10_000_000_000L)

        val doubted = StepAccumulator.accumulate(
            walking,
            rawCounter = 102,
            bootId = boot,
            eventTimestampNanos = 15_000_000_000L,
            gaitEvidence = false,
            doubtful = true,
        )
        assertEquals(2L, doubted.creditedDelta)
        assertEquals(StepVerdict.CREDITED, doubted.verdict)
        assertEquals(10_000_000_000L, doubted.state.boutLastNanos)

        // Thirty seconds and a nanosecond after the last confirmed step, the doubt is a veto again.
        val late = StepAccumulator.accumulate(
            doubted.state,
            rawCounter = 104,
            bootId = boot,
            eventTimestampNanos = 40_000_000_001L,
            gaitEvidence = false,
            doubtful = true,
        )
        assertEquals(0L, late.creditedDelta)
        assertEquals(StepVerdict.NO_GAIT, late.verdict)

        // A veto that is not a doubt stands mid-walk, and a doubt with no confirmed walk stands too.
        val vetoed = StepAccumulator.accumulate(walking, 102, boot, 15_000_000_000L, gaitEvidence = false)
        assertEquals(StepVerdict.NO_GAIT, vetoed.verdict)
        val alone = StepAccumulator.accumulate(
            walking.copy(boutConfirmed = false),
            rawCounter = 102,
            bootId = boot,
            eventTimestampNanos = 15_000_000_000L,
            gaitEvidence = false,
            doubtful = true,
        )
        assertEquals(StepVerdict.NO_GAIT, alone.verdict)
    }

    /**
     * A batching sensor hub flushes several step events at once and stamps them all with the same
     * timestamp. Treating equal timestamps as a clock running backwards would discard everything
     * after the first event in each flush — 126 of the 209 steps in a recorded 200-step walk, before
     * the gate had any say in it.
     */
    @Test
    fun `two events out of the same flush are both counted`() {
        val flushNanos = 3_000_000_000L
        val first = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 102,
            bootId = boot,
            eventTimestampNanos = flushNanos,
            gaitEvidence = true,
        )
        assertEquals(2L, first.creditedDelta)
        assertEquals(StepVerdict.CREDITED, first.verdict)

        val second = StepAccumulator.accumulate(
            first.state,
            rawCounter = 104,
            bootId = boot,
            eventTimestampNanos = flushNanos,
            gaitEvidence = true,
        )
        assertEquals(2L, second.creditedDelta)
        assertEquals(StepVerdict.CREDITED, second.verdict)
    }

    /**
     * Events sharing a timestamp imply an infinite rate, so the plausibility check keeps its hands
     * off them: otherwise it would throw away 160 of 208 steps — the very ones a timestamp rule
     * would take.
     */
    @Test
    fun `a flush is not judged for speed it never claimed`() {
        val flushNanos = 3_000_000_000L
        val first = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = flushNanos,
            gaitEvidence = true,
        )
        val second = StepAccumulator.accumulate(
            first.state,
            rawCounter = 110,
            bootId = boot,
            eventTimestampNanos = flushNanos,
            gaitEvidence = true,
        )
        assertEquals(9L, second.rawDelta)
        assertEquals(9L, second.creditedDelta)
        assertEquals(StepVerdict.CREDITED, second.verdict)
    }

    /** A walk that dips out of classification for one window carries on being the same walk. */
    @Test
    fun `a bout survives an unclassifiable window in the middle of it`() {
        val rejected = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 102,
            bootId = boot,
            eventTimestampNanos = 2_000_000_000L,
            gaitEvidence = false,
        )
        assertEquals(0L, rejected.creditedDelta)

        val resumed = StepAccumulator.accumulate(
            rejected.state,
            rawCounter = 104,
            bootId = boot,
            eventTimestampNanos = 3_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(2L, resumed.creditedDelta)
        assertEquals(StepVerdict.CREDITED, resumed.verdict)
    }

    @Test
    fun `first sample adopts baseline and credits nothing`() {
        val update = StepAccumulator.accumulate(
            null,
            rawCounter = 12_345,
            bootId = boot,
            eventTimestampNanos = 1_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.BASELINE, update.verdict)
        assertEquals(12_345L, update.state.lastCumulative)
    }

    @Test
    fun `three periodic gait steps confirm a bout together`() {
        var state = StepAccumulator.accumulate(
            null,
            100,
            boot,
            1_000_000_000L,
            true,
        ).state

        val first = StepAccumulator.accumulate(state, 101, boot, 1_500_000_000L, true)
        state = first.state
        val second = StepAccumulator.accumulate(state, 102, boot, 2_000_000_000L, true)
        state = second.state
        val third = StepAccumulator.accumulate(state, 103, boot, 2_500_000_000L, true)

        assertEquals(0L, first.creditedDelta)
        assertEquals(0L, second.creditedDelta)
        assertEquals(3L, third.creditedDelta)
        assertTrue(third.state.boutConfirmed)
    }

    /**
     * A batching pedometer flushes the counter every few tens of seconds, so consecutive events
     * inside one continuous walk are far apart. With a bout gap shorter than that interval every
     * flush would start a new bout, every bout would stay pending, and 900 steps would turn into a
     * handful.
     */
    @Test
    fun `a pedometer that reports every ten seconds still credits the walk`() {
        var state = StepAccumulator.accumulate(null, 0, boot, 1_000_000_000L, true).state
        val credited = mutableListOf<Long>()
        var counter = 0L
        var nanos = 1_000_000_000L

        repeat(6) {
            counter += 15
            nanos += 10_000_000_000L
            val update = StepAccumulator.accumulate(state, counter, boot, nanos, true)
            state = update.state
            credited += update.creditedDelta
        }

        // The first flush waits for confirmation, then it is credited together with the second.
        assertEquals(listOf(0L, 30L, 15L, 15L, 15L, 15L), credited)
        assertEquals(90L, credited.sum())
    }

    @Test
    fun `one coarse flush is settled when the next separate bout begins`() {
        var state = StepAccumulator.accumulate(null, 0, boot, 1_000_000_000L, true).state
        val shortWalk = StepAccumulator.accumulate(
            state,
            rawCounter = 15,
            bootId = boot,
            eventTimestampNanos = 11_000_000_000L,
            gaitEvidence = true,
            day = "2026-08-18",
        )
        state = shortWalk.state

        val nextWalk = StepAccumulator.accumulate(
            state,
            rawCounter = 16,
            bootId = boot,
            eventTimestampNanos = 42_000_000_000L,
            gaitEvidence = true,
            day = "2026-08-18",
        )

        assertEquals(0L, shortWalk.creditedDelta)
        assertEquals(15L, nextWalk.creditedDelta)
        assertEquals(mapOf("2026-08-18" to 15L), nextWalk.creditedByDay)
        assertEquals(1L, nextWalk.state.pendingSteps)
    }

    @Test
    fun `an expired pair remains rejected as handling motion`() {
        var state = StepAccumulator.accumulate(null, 0, boot, 1_000_000_000L, true).state
        state = StepAccumulator.accumulate(state, 2, boot, 2_000_000_000L, true, "2026-08-18").state

        val nextBout = StepAccumulator.accumulate(state, 3, boot, 33_000_000_000L, true, "2026-08-18")

        assertEquals(0L, nextBout.creditedDelta)
        // The day is credited from this map alone, so it has to stay as empty as the total.
        assertEquals(emptyMap<String, Long>(), nextBout.creditedByDay)
        assertEquals(1L, nextBout.state.pendingSteps)
    }

    @Test
    fun `a pause long enough to be a different walk starts a new bout`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = 1_000_000_000L + 31_000_000_000L,
            gaitEvidence = true,
        )

        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.BOUT_PENDING, update.verdict)
    }

    @Test
    fun `an isolated pair of steps is still not a walk`() {
        var state = StepAccumulator.accumulate(null, 100, boot, 1_000_000_000L, true).state
        val first = StepAccumulator.accumulate(state, 101, boot, 2_000_000_000L, true)
        state = first.state
        val second = StepAccumulator.accumulate(state, 102, boot, 3_000_000_000L, true)

        assertEquals(0L, first.creditedDelta)
        assertEquals(0L, second.creditedDelta)
        assertFalse(second.state.boutConfirmed)
    }

    @Test
    fun `confirmed bout credits subsequent steps immediately`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = 1_500_000_000L,
            gaitEvidence = true,
        )
        assertEquals(1L, update.creditedDelta)
        assertEquals(StepVerdict.CREDITED, update.verdict)
    }

    @Test
    fun `nine steps in one second are rejected as impossible`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 109,
            bootId = boot,
            eventTimestampNanos = 2_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(9L, update.rawDelta)
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.IMPOSSIBLE_RATE, update.verdict)
        // The implausible reading is refused; the walk it interrupted is not. Ending the bout here
        // would send the next good event back to the start of a fresh, unconfirmed bout — and a
        // 200-step walk could credit nothing at all.
        assertTrue(update.state.boutConfirmed)
    }

    @Test
    fun `plausible sensor batch inside rate limit is retained`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 110,
            bootId = boot,
            eventTimestampNanos = 3_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(10L, update.creditedDelta)
    }

    /**
     * What keeps isolated handling out is the three-step minimum, not the length of the gap. Two
     * increases separated by a pause stay pending however the pause is timed; a rule leaning on a
     * 2.5 s gap would also throw away a slow walk.
     */
    @Test
    fun `isolated handling movements never confirm a bout`() {
        val baseline = StepAccumulator.accumulate(
            null,
            50,
            boot,
            1_000_000_000L,
            true,
        ).state
        val first = StepAccumulator.accumulate(
            baseline,
            51,
            boot,
            1_500_000_000L,
            true,
        )
        val afterPause = StepAccumulator.accumulate(
            first.state,
            52,
            boot,
            5_000_000_000L,
            true,
        )

        assertEquals(0L, first.creditedDelta + afterPause.creditedDelta)
        assertEquals(2L, afterPause.state.pendingSteps)
        assertFalse(afterPause.state.boutConfirmed)
    }

    @Test
    fun `counter increase without gait evidence is rejected`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = 1_500_000_000L,
            gaitEvidence = false,
        )
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.NO_GAIT, update.verdict)
        // Credits nothing, but the walk in progress survives an unclassifiable window.
        assertTrue(update.state.boutConfirmed)
    }

    @Test
    fun `duplicate reading credits nothing and keeps timing anchor`() {
        val previous = confirmedState()
        val update = StepAccumulator.accumulate(
            previous,
            100,
            boot,
            2_000_000_000L,
            true,
        )
        assertEquals(0L, update.rawDelta)
        assertEquals(previous.lastEventNanos, update.state.lastEventNanos)
    }

    @Test
    fun `process death replay of persisted value remains idempotent`() {
        val persisted = StepAccumulator.accumulate(
            confirmedState(),
            101,
            boot,
            1_500_000_000L,
            true,
        ).state
        val replay = StepAccumulator.accumulate(
            persisted,
            101,
            boot,
            1_500_000_000L,
            true,
        )
        assertEquals(0L, replay.creditedDelta)
        assertEquals(StepVerdict.DUPLICATE, replay.verdict)
    }

    @Test
    fun `reboot establishes a new baseline instead of injecting history`() {
        val update = StepAccumulator.accumulate(
            confirmedState(raw = 45_000),
            rawCounter = 320,
            bootId = nextBoot,
            eventTimestampNanos = 5_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.REBOOT_BASELINE, update.verdict)
        assertEquals(nextBoot, update.state.bootId)
    }

    @Test
    fun `counter going backwards without reboot rebases safely`() {
        val update = StepAccumulator.accumulate(
            confirmedState(raw = 45_000),
            rawCounter = 44_000,
            bootId = boot,
            eventTimestampNanos = 2_000_000_000L,
            gaitEvidence = true,
        )
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.COUNTER_RESET, update.verdict)
        assertEquals(44_000L, update.state.lastCumulative)
    }

    @Test
    fun `small boot clock drift is not treated as reboot`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot + 12_000,
            eventTimestampNanos = 1_500_000_000L,
            gaitEvidence = true,
        )
        assertEquals(1L, update.creditedDelta)
    }

    /**
     * bootId is the wall clock minus the uptime, so setting the clock moves it without a reboot —
     * an NTP correction, or a border crossing with automatic time. The device never stopped, and
     * neither did the walk it was in the middle of.
     */
    @Test
    fun `a clock correction during the same boot keeps counting`() {
        val upThreeHours = 3 * 60 * 60 * 1_000_000_000L
        val update = StepAccumulator.accumulate(
            confirmedState(timeNanos = upThreeHours),
            rawCounter = 101,
            bootId = boot + 300_000L,
            eventTimestampNanos = upThreeHours + 1_000_000_000L,
            gaitEvidence = true,
        )

        assertEquals(1L, update.creditedDelta)
        assertEquals(StepVerdict.CREDITED, update.verdict)
        assertEquals(boot + 300_000L, update.state.bootId)
    }

    @Test
    fun `a clock set backwards during the same boot keeps counting`() {
        val upThreeHours = 3 * 60 * 60 * 1_000_000_000L
        val update = StepAccumulator.accumulate(
            confirmedState(timeNanos = upThreeHours),
            rawCounter = 101,
            bootId = boot - 300_000L,
            eventTimestampNanos = upThreeHours + 1_000_000_000L,
            gaitEvidence = true,
        )

        assertEquals(1L, update.creditedDelta)
        assertEquals(boot - 300_000L, update.state.bootId)
    }

    /** A counter that kept climbing is not proof of the same boot: only the shift decides. */
    @Test
    fun `a boot shift longer than the session uptime is still a reboot`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 320,
            bootId = nextBoot,
            eventTimestampNanos = 5_000_000_000L,
            gaitEvidence = true,
        )

        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.REBOOT_BASELINE, update.verdict)
    }

    @Test
    fun `non monotonic sensor timestamp cannot create steps`() {
        val update = StepAccumulator.accumulate(
            confirmedState(),
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = 900_000_000L,
            gaitEvidence = true,
        )
        assertEquals(0L, update.creditedDelta)
        assertEquals(StepVerdict.NON_MONOTONIC_TIME, update.verdict)
        assertEquals(1_000_000_000L, update.state.lastEventNanos)
    }

    @Test
    fun `many individually small deltas share one cumulative rate budget`() {
        var state = confirmedState(raw = 100, timeNanos = 1_000_000_000L)
        val verdicts = mutableListOf<StepVerdict>()

        repeat(12) { index ->
            val update = StepAccumulator.accumulate(
                state,
                rawCounter = 101L + index,
                bootId = boot,
                eventTimestampNanos = 1_100_000_000L + index * 100_000_000L,
                gaitEvidence = true,
            )
            state = update.state
            verdicts += update.verdict
        }

        assertTrue(verdicts.take(2).all { it == StepVerdict.CREDITED })
        assertTrue(verdicts.drop(2).any { it == StepVerdict.IMPOSSIBLE_RATE })
    }

    @Test
    fun `equal timestamp flush is bounded by time since the persisted anchor`() {
        var state = confirmedState(raw = 100, timeNanos = 1_000_000_000L)
        var last = StepAccumulator.accumulate(state, 101, boot, 1_100_000_000L, true)
        state = last.state
        last = StepAccumulator.accumulate(state, 102, boot, 1_100_000_000L, true)
        state = last.state
        last = StepAccumulator.accumulate(state, 103, boot, 1_100_000_000L, true)

        assertEquals(StepVerdict.IMPOSSIBLE_RATE, last.verdict)
        assertEquals(0L, last.creditedDelta)
    }

    @Test
    fun `zero-valued boot baseline still persists its rate anchor`() {
        var state = StepAccumulator.accumulate(null, 0, boot, 0, true).state
        state = StepAccumulator.accumulate(state, 1, boot, 100_000_000L, true).state
        state = StepAccumulator.accumulate(state, 2, boot, 100_000_000L, true).state
        val excessive = StepAccumulator.accumulate(state, 3, boot, 100_000_000L, true)

        assertEquals(StepVerdict.IMPOSSIBLE_RATE, excessive.verdict)
    }

    @Test
    fun `pending bout confirmed after midnight credits each event day`() {
        val firstDay = "2026-07-30"
        val secondDay = "2026-07-31"
        var state = StepAccumulator.accumulate(
            null,
            rawCounter = 100,
            bootId = boot,
            eventTimestampNanos = 1_000_000_000L,
            gaitEvidence = true,
            day = firstDay,
        ).state
        state = StepAccumulator.accumulate(
            state,
            rawCounter = 101,
            bootId = boot,
            eventTimestampNanos = 2_000_000_000L,
            gaitEvidence = true,
            day = firstDay,
        ).state
        state = StepAccumulator.accumulate(
            state,
            rawCounter = 102,
            bootId = boot,
            eventTimestampNanos = 2_500_000_000L,
            gaitEvidence = true,
            day = secondDay,
        ).state
        val confirmed = StepAccumulator.accumulate(
            state,
            rawCounter = 103,
            bootId = boot,
            eventTimestampNanos = 3_000_000_000L,
            gaitEvidence = true,
            day = secondDay,
        )

        assertEquals(3L, confirmed.creditedDelta)
        assertEquals(mapOf(firstDay to 1L, secondDay to 2L), confirmed.creditedByDay)
    }
}
