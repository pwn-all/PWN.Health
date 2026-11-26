package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The reference ranges the cycle advice holds a record against, and the ages they apply at.
 *
 * Every assertion here is a published figure rather than a preference: FIGO 2018 System 1 for the
 * adult bands and for prolonged bleeding, ACOG Committee Opinion 651 for the years after menarche.
 * A wrong band here does not draw a chart badly — it tells a teenager to see a doctor about a body
 * that is doing what her own reference says it should.
 */
class CycleNormsTest {

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun day(text: String) = LocalDate.parse(text)

    /** Starts every [gap] days, [count] of them, ending shortly before [until]. */
    private fun cycles(count: Int, gap: Long, first: String) =
        (0 until count).map { entry(KIND_CYCLE, day(first).plusDays(gap * it).toString()) }

    @Test
    fun theAdolescentBandIsTwentyOneToFortyFive() {
        assertEquals(21..45, cycleLengthBand(14))
        assertEquals(21..45, cycleLengthBand(19))
        assertEquals(24..38, cycleLengthBand(20))
        assertEquals(24..38, cycleLengthBand(44))
        // A profile with no birth date reads as age zero; it gets the adult band rather than a
        // teenager's, because the adult band is the one that says less.
        assertEquals(24..38, cycleLengthBand(0))
    }

    /**
     * Forty-two-day cycles: ordinary in the years after menarche, worth a mention at thirty.
     */
    @Test
    fun aFortyTwoDayCycleIsOrdinaryAtFifteenAndNotAtThirty() {
        val journal = cycles(count = 5, gap = 42, first = "2026-01-05")
        val today = day("2026-07-20")

        assertNull("ACOG 651 calls this normal", cycleAdvice(journal, ageYears = 15, today = today))
        assertEquals("cycle-long", cycleAdvice(journal, ageYears = 30, today = today)?.id)
    }

    /** And the adolescent band has a far end of its own: 46 days is long at any age. */
    @Test
    fun theAdolescentBandStillHasEnds() {
        assertEquals(
            "cycle-long",
            cycleAdvice(cycles(5, 47, "2026-01-05"), ageYears = 15, today = day("2026-08-01"))?.id,
        )
        assertEquals(
            "cycle-short",
            cycleAdvice(cycles(5, 20, "2026-09-05"), ageYears = 15, today = day("2026-12-01"))?.id,
        )
    }

    /**
     * FIGO puts prolonged menstrual bleeding beyond eight days. Two of the last four, so that one
     * long period is not turned into a finding while a pattern is.
     */
    @Test
    fun twoProlongedPeriodsOfTheLastFourAreAFinding() {
        fun period(start: String, lengthDays: Long) = listOf(
            entry(KIND_CYCLE, start),
            entry(KIND_CYCLE_END, day(start).plusDays(lengthDays - 1).toString()),
        )

        val oneLong = period("2026-05-04", 5) + period("2026-06-01", 5) +
            period("2026-06-29", 5) + period("2026-07-27", 11)
        assertNull("one long period is a month, not a pattern", prolongedPeriodAdvice(oneLong))

        val twoLong = period("2026-05-04", 5) + period("2026-06-01", 9) +
            period("2026-06-29", 5) + period("2026-07-27", 11)
        val advice = prolongedPeriodAdvice(twoLong)
        assertEquals("period-long", advice?.id)
        assertEquals(AdviceLevel.CLINICIAN, advice?.level)
        // Two of four, the longest eleven days, against the eight-day figure — which travels as an
        // argument rather than as a number in the sentence, so the two cannot drift apart.
        assertEquals(
            listOf(2, 4, 11, PERIOD_LONG_DAYS),
            advice?.args?.filterIsInstance<Counted>()?.map { it.quantity },
        )
    }

    /** Eight days is inside the range; nine is the first one outside it. */
    @Test
    fun eightDaysIsNotProlonged() {
        fun run(lengthDays: Long) = prolongedPeriodAdvice(
            (0 until 4).flatMap { index ->
                val start = day("2026-05-04").plusDays(28L * index)
                listOf(
                    entry(KIND_CYCLE, start.toString()),
                    entry(KIND_CYCLE_END, start.plusDays(lengthDays - 1).toString()),
                )
            },
        )

        assertNull(run(PERIOD_LONG_DAYS.toLong()))
        assertEquals("period-long", run(PERIOD_LONG_DAYS + 1L)?.id)
    }

    /**
     * Prolonged bleeding is measured from one period and must not sit behind the three cycles the
     * length statistics need: a woman two months into the app with two eleven-day periods is
     * exactly who the sentence is for.
     */
    @Test
    fun prolongedBleedingIsNotHeldBehindTheCycleCountGate() {
        val journal = listOf(
            entry(KIND_CYCLE, "2026-06-01"),
            entry(KIND_CYCLE_END, "2026-06-11"),
            entry(KIND_CYCLE, "2026-06-29"),
            entry(KIND_CYCLE_END, "2026-07-09"),
        )

        assertEquals(1, cycleSummary(journal, day("2026-07-20")).recordedCycles)
        assertEquals("period-long", cycleAdvice(journal, ageYears = 30, today = day("2026-07-20"))?.id)
    }

    /**
     * A period that ran for months is a forgotten tap, not a finding, and it must not be counted
     * either as a length or as evidence of one.
     */
    @Test
    fun anImplausiblyLongPeriodIsNotCountedAsProlonged() {
        val journal = listOf(
            entry(KIND_CYCLE, "2026-01-01"),
            entry(KIND_CYCLE_END, "2026-04-01"),
            entry(KIND_CYCLE, "2026-04-29"),
            entry(KIND_CYCLE_END, "2026-08-01"),
        )

        assertNull(prolongedPeriodAdvice(journal))
        assertNull(cycleSummary(journal, day("2026-08-20")).periodLengthDays)
    }

    /** Absent bleeding still outranks everything else the record could say. */
    @Test
    fun ninetyDaysWithoutABleedStillComesFirst() {
        val journal = listOf(
            entry(KIND_CYCLE, "2026-01-01"),
            entry(KIND_CYCLE_END, "2026-01-12"),
            entry(KIND_CYCLE, "2026-02-01"),
            entry(KIND_CYCLE_END, "2026-02-12"),
        )

        assertEquals("cycle-absent", cycleAdvice(journal, ageYears = 30, today = day("2026-06-01"))?.id)
    }

    /**
     * A start three years old would give a clinician's card counting 1,095 days, with the long cycles
     * behind it next in line. A year on, the record has stopped rather than the bleeding, or
     * the menopause has been reached: either way there is nothing current left to say.
     */
    @Test
    fun aStartAYearOldIsARecordThatStoppedRatherThanAnAbsenceToRaise() {
        val longCycles = cycles(4, 45, "2025-01-01")
        val last = day("2025-05-16")

        assertEquals("cycle-absent", cycleAdvice(longCycles, ageYears = 30, today = last.plusDays(364))?.id)
        assertNull(cycleAdvice(longCycles, ageYears = 30, today = last.plusDays(365)))
        assertNull(cycleAdvice(longCycles, ageYears = 30, today = last.plusDays(3 * 365)))
    }

    /** From 45 the menopause is among the reasons named; before it, never, since there it would soothe. */
    @Test
    fun theMenopauseIsNamedAmongTheReasonsFromFortyFive() {
        val journal = listOf(entry(KIND_CYCLE, "2026-02-01"))

        assertEquals(
            R.string.advice_cycle_absent_body,
            cycleAdvice(journal, ageYears = 44, today = day("2026-06-01"))?.bodyRes,
        )
        assertEquals(
            R.string.advice_cycle_absent_older_body,
            cycleAdvice(journal, ageYears = 45, today = day("2026-06-01"))?.bodyRes,
        )
    }
}
