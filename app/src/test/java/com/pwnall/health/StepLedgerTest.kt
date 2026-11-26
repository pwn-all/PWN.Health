package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class StepLedgerTest {

    private val zone = ZoneOffset.UTC
    private val boot = 1_780_000_000_000L

    private fun reading(
        counter: Long,
        atNanos: Long,
        delivered: LocalDateTime,
        gait: Boolean = true,
    ) = StepReading(
        rawCounter = counter,
        bootId = boot,
        day = delivered.toLocalDate().toString(),
        eventTimestampNanos = atNanos,
        gaitEvidence = gait,
        doubtful = false,
        deliveredAtMs = delivered.toInstant(zone).toEpochMilli(),
    )

    /** A walk with a refused stretch and a repeated value in it, an event every two seconds. */
    private fun walk(): List<StepReading> {
        val start = LocalDateTime.of(2026, 10, 18, 9, 59, 30)
        return (0 until 40).map { i ->
            reading(
                counter = 1_000L + 3 * i + if (i > 20) 1 else 0,
                atNanos = 5_000_000_000_000L + i * 2_000_000_000L,
                delivered = start.plusSeconds(2L * i),
                gait = i !in 12..14,
            )
        } + reading(1_118, 5_078_000_000_000L, start.plusSeconds(78))
    }

    private fun MutableMap<Pair<String, Int>, Long>.addAll(write: StepWrite) =
        write.credits.forEach { (key, steps) -> merge(key, steps, Long::plus) }

    /**
     * A transaction per reading, and one write for the whole walk, have to leave the
     * same days, the same hours and the same state: batching moves when the steps are written, never
     * which of them count.
     */
    @Test
    fun `one write for a walk credits what a write per reading did`() {
        val each = StepLedger(null, 0L)
        val perReading = mutableMapOf<Pair<String, Int>, Long>()
        var lastEach: StepWrite? = null
        walk().forEachIndexed { i, reading ->
            each.add(reading, now = i.toLong(), zone)
            lastEach = each.take()?.also { perReading.addAll(it) }
        }

        val once = StepLedger(null, 0L)
        walk().forEachIndexed { i, reading -> once.add(reading, now = i.toLong(), zone) }
        assertEquals(0L, once.unwrittenSince)
        val write = requireNotNull(once.take())

        assertEquals(perReading, write.credits)
        assertEquals(lastEach?.state, write.state)
        assertEquals(lastEach?.lastStepAtMs, write.lastStepAtMs)
        // The walk crossed ten o'clock, and its steps are filed either side of it.
        assertEquals(setOf("2026-10-18" to 9, "2026-10-18" to 10), write.credits.keys)
        assertNull("nothing is left once written", once.take())
        assertNull(once.unwrittenSince)
    }

    /**
     * A bout that began before midnight and was confirmed after it files its first steps under the
     * last hour of the day before, as a per-reading write does; the rest under the hour it was
     * delivered in. The last step's time is the delivery of the last credited reading.
     */
    @Test
    fun `a bout across midnight files each day's share under its own hour`() {
        val ledger = StepLedger(null, 0L)
        val evening = LocalDateTime.of(2026, 10, 17, 23, 59, 50)
        ledger.add(reading(500, 9_000_000_000_000L, evening), now = 0L, zone)
        ledger.add(reading(502, 9_008_000_000_000L, evening.plusSeconds(8)), now = 8_000L, zone)
        val midnight = evening.plusSeconds(11)
        val confirmed = ledger.add(reading(506, 9_011_000_000_000L, midnight), now = 11_000L, zone)
        assertEquals(StepVerdict.CREDITED, confirmed.verdict)

        val write = requireNotNull(ledger.take())
        assertEquals(mapOf(("2026-10-17" to 23) to 2L, ("2026-10-18" to 0) to 4L), write.credits)
        assertEquals("2026-10-18", write.day)
        assertEquals(midnight.toInstant(zone).toEpochMilli(), write.lastStepAtMs)
    }
}
