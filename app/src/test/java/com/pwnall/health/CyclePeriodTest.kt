package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pairing starts with ends is what Health Connect's period record is built from, and the two kinds
 * arrive interleaved with every other journal row.
 */
class CyclePeriodTest {
    /** A zone whose clocks change, because the cap the export honours is measured on the clock. */
    private val zone = ZoneId.of("Europe/Berlin")

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = LocalDate.parse(day).toEpochDay() * 86_400_000,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    @Test
    fun aFinishedPeriodHasBothEnds() {
        val periods = cyclePeriods(
            listOf(
                entry(KIND_CYCLE, "2026-07-01"),
                entry(KIND_WATER, "2026-07-02"),
                entry(KIND_CYCLE_END, "2026-07-05"),
            ),
        )
        assertEquals(listOf(CyclePeriod(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"))), periods)
    }

    @Test
    fun aRunningPeriodIsNotPublishedYet() {
        assertTrue(cyclePeriods(listOf(entry(KIND_CYCLE, "2026-07-29"))).isEmpty())
    }

    /** An end belongs to the start before it, never to a later cycle that has not finished. */
    @Test
    fun anEndDoesNotJumpToTheNextCycle() {
        val periods = cyclePeriods(
            listOf(
                entry(KIND_CYCLE, "2026-06-01"),
                entry(KIND_CYCLE_END, "2026-06-05"),
                entry(KIND_CYCLE, "2026-06-29"),
            ),
        )
        assertEquals(1, periods.size)
        assertEquals(LocalDate.parse("2026-06-01"), periods.single().start)
    }

    /**
     * Health Connect throws on a period longer than 31 days, and the throw would abort the whole
     * export — cycle records first, then every body, activity and session record behind it. A
     * forgotten "period ended" tap must therefore never reach the record constructor.
     */
    @Test
    fun anImplausiblyLongPeriodIsLeftOutOfTheExport() {
        val journal = listOf(
            entry(KIND_CYCLE, "2026-01-01"),
            entry(KIND_CYCLE_END, "2026-02-10"),
            entry(KIND_CYCLE, "2026-03-01"),
            entry(KIND_CYCLE_END, "2026-03-06"),
        )

        assertEquals(2, cyclePeriods(journal).size)
        assertEquals(
            listOf(CyclePeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-06"))),
            exportablePeriods(journal, zone),
        )
    }

    /** The exported interval runs to the end of the last recorded day, which is the thirty-first. */
    @Test
    fun aPeriodRightOnTheCapIsStillExported() {
        val onTheCap = listOf(entry(KIND_CYCLE, "2026-01-01"), entry(KIND_CYCLE_END, "2026-01-31"))
        val overIt = listOf(entry(KIND_CYCLE, "2026-01-01"), entry(KIND_CYCLE_END, "2026-02-01"))

        assertEquals(1, exportablePeriods(onTheCap, zone).size)
        assertTrue(exportablePeriods(overIt, zone).isEmpty())
    }

    /**
     * The cap is thirty-one days on the clock, not thirty-one entries in a calendar. A period that
     * runs over the autumn change is an hour longer than the same span in July, and counting days
     * would let exactly that record reach the constructor and abort the export.
     */
    @Test
    fun theAutumnClockChangeCountsTowardsTheCap() {
        val overTheChange = listOf(entry(KIND_CYCLE, "2026-10-05"), entry(KIND_CYCLE_END, "2026-11-04"))
        val sameSpanInSummer = listOf(entry(KIND_CYCLE, "2026-06-05"), entry(KIND_CYCLE_END, "2026-07-05"))

        assertTrue(exportablePeriods(overTheChange, zone).isEmpty())
        assertEquals(1, exportablePeriods(sameSpanInSummer, zone).size)
    }

    /**
     * A period closed today ends at tomorrow's midnight, which has not happened: it is published as
     * running to the moment of the export, and the export after midnight publishes the day whole.
     */
    @Test
    fun aPeriodClosedTodayIsPublishedUpToNow() {
        val period = CyclePeriod(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"))
        val start = LocalDate.parse("2026-07-01").atStartOfDay(zone).toInstant()
        val midnight = LocalDate.parse("2026-07-06").atStartOfDay(zone).toInstant()
        val evening = midnight.minusSeconds(3 * 3600L)

        assertEquals(start to evening, period.publishedInterval(zone, evening))
        assertEquals(start to midnight, period.publishedInterval(zone, midnight.plusSeconds(60)))
        assertNull("nothing to publish before it began", period.publishedInterval(zone, start))
    }

    /**
     * Another app's period a day off one on record would take that one's end: its start falls inside
     * the period here, so checking only the day itself is not enough. It is left out whole; a period clear of the
     * record comes in, and the rows one record brought in before are not in its own way.
     */
    @Test
    fun aForeignPeriodTouchingOneOnRecordIsLeftOutWhole() {
        fun mark(kind: String, day: String, key: String? = null) = entry(kind, day).copy(healthConnectRecordKey = key)
        val today = LocalDate.parse("2026-10-01")
        val local = listOf(mark(KIND_CYCLE, "2026-09-01"), mark(KIND_CYCLE_END, "2026-09-05"))
        fun fits(start: String, end: String, marks: List<JournalEntry> = local) =
            importedPeriodFits(marks, LocalDate.parse(start), LocalDate.parse(end), "hc:app:1", today)

        assertFalse("a day later", fits("2026-09-02", "2026-09-06"))
        assertFalse("a day earlier", fits("2026-08-31", "2026-09-04"))
        assertTrue("clear of it", fits("2026-09-20", "2026-09-24"))
        assertFalse("a forecast", fits("2026-09-28", "2026-10-02"))
        val before = local + mark(KIND_CYCLE, "2026-09-20", "hc:app:1") +
            mark(KIND_CYCLE_END, "2026-09-24", "hc:app:1#end")
        assertTrue("moved by its own app", fits("2026-09-21", "2026-09-25", before))
    }

    /**
     * A 29.5-day history predicts thirty days, not twenty-nine: truncating the average would move every
     * prediction a day earlier than the record supports, every cycle.
     */
    @Test
    fun theAverageLengthIsRoundedRatherThanTruncated() {
        val starts = listOf("2026-04-01", "2026-04-30", "2026-05-30").map { entry(KIND_CYCLE, it) }

        val summary = cycleSummary(starts, LocalDate.parse("2026-06-10"))

        assertEquals(30, summary.averageLengthDays)
        assertEquals(LocalDate.parse("2026-06-29"), summary.nextExpected)
    }

    @Test
    fun anUnknownLogNameIsIgnoredRatherThanCrashing() {
        assertEquals(CycleLog.FLOW_HEAVY, cycleLogOf("FLOW_HEAVY"))
        assertEquals(null, cycleLogOf("FLOW_TORRENTIAL"))
        assertEquals(null, cycleLogOf(null))
    }

    private fun log(day: String, name: String) = entry(KIND_CYCLE_LOG, day)
        .copy(measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"$name"}""")

    /** The payload is what the calendar and the export both read; the title is only a label. */
    @Test
    fun theLogIsReadOutOfThePayloadRatherThanTheTitle() {
        assertEquals(CycleLog.FLOW_MEDIUM, log("2026-07-02", "FLOW_MEDIUM").cycleLog())
        assertEquals(null, log("2026-07-02", "FLOW_TORRENTIAL").cycleLog())
        assertEquals(null, entry(KIND_CYCLE_LOG, "2026-07-02").cycleLog())
        assertEquals(null, entry(KIND_WATER, "2026-07-02").cycleLog())
    }

    /**
     * The week strip's red drop. It marks the days somebody actually entered bleeding on — either
     * end of a period and any bleeding logged on a day — and nothing else on the cycle screen.
     */
    @Test
    fun onlyDaysWithBleedingOnThemGetTheDrop() {
        fun bled(vararg rows: JournalEntry) = recordedBleedingDays(rows.toList()).isNotEmpty()

        assertTrue(bled(entry(KIND_CYCLE, "2026-07-01")))
        assertTrue(bled(entry(KIND_CYCLE_END, "2026-07-05")))
        assertTrue(bled(log("2026-07-02", "FLOW_LIGHT")))
        assertTrue(bled(log("2026-07-14", "SPOTTING")))
        assertTrue(!bled(log("2026-07-14", "OVULATION_POSITIVE")))
        assertTrue(!bled(log("2026-07-14", "SEX_PROTECTED")))
        assertTrue(!bled(entry(KIND_WATER, "2026-07-03")))
        assertTrue(!bled())
    }

    /**
     * What makes "Medium after Light corrects the day" true: [StepsDb.logCycleLog] clears the
     * section it is writing into, so a log in two sections would be deleted by the other one, and a
     * sex log in any section would stop being a second act and start being a correction of the
     * first.
     */
    @Test
    fun eachLogBelongsToAtMostOneSectionAndSexToNone() {
        val sectioned = CycleSection.entries.flatMap { it.logs }
        assertEquals(sectioned.size, sectioned.toSet().size)
        assertTrue(CycleLog.SEX_PROTECTED !in sectioned)
        assertTrue(CycleLog.SEX_UNPROTECTED !in sectioned)
    }

    /**
     * The middle of an even number of periods is the mean of its two middle lengths. The upper one
     * alone would print on the doctor's sheet as "7 days · Median" for periods of three and seven
     * days, and as nine — past the line a long period is read against — for five and nine.
     */
    @Test
    fun theMedianOfAnEvenNumberOfPeriodsIsTheMeanOfItsMiddleTwo() {
        fun period(start: String, end: String) = listOf(entry(KIND_CYCLE, start), entry(KIND_CYCLE_END, end))
        val three = period("2026-05-04", "2026-05-06")
        val five = period("2026-06-01", "2026-06-05")
        val seven = period("2026-06-29", "2026-07-05")
        val nine = period("2026-07-27", "2026-08-04")

        assertEquals(5, medianPeriodLength(three + seven))
        assertEquals(7, medianPeriodLength(five + nine))
        // An odd count has a middle of its own, and half a day rounds up, as the average cycle does.
        assertEquals(5, medianPeriodLength(three + five + seven))
        assertEquals(6, medianPeriodLength(three + five + seven + nine))
        assertEquals(5, medianPeriodLength(five + period("2026-08-24", "2026-08-27")))
    }

    /**
     * A start deleted on its own takes its period's end with it, and nothing else: not another
     * period's end, not an end a second start on the same day still begins, and nothing for an end.
     */
    @Test
    fun aDeletedStartTakesItsOwnEndAndNothingElse() {
        val march = entry(KIND_CYCLE, "2026-03-01").copy(id = 1)
        val marchEnd = entry(KIND_CYCLE_END, "2026-03-05").copy(id = 2)
        val april = entry(KIND_CYCLE, "2026-03-29").copy(id = 3)
        val aprilEnd = entry(KIND_CYCLE_END, "2026-04-02").copy(id = 4)
        val running = entry(KIND_CYCLE, "2026-04-26").copy(id = 5)
        val marks = listOf(march, marchEnd, april, aprilEnd, running)
        val none = emptyList<JournalEntry>()

        assertEquals(listOf(marchEnd), periodEndsDeletedWith(march, marks))
        assertEquals(listOf(aprilEnd), periodEndsDeletedWith(april, marks))
        assertEquals("a period still running", none, periodEndsDeletedWith(running, marks))
        assertEquals("an end", none, periodEndsDeletedWith(marchEnd, marks))
        val twice = marks + entry(KIND_CYCLE, "2026-03-01").copy(id = 6)
        assertEquals("a second start that day", none, periodEndsDeletedWith(march, twice))
    }
}
