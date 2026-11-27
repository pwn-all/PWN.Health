package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * What the cycle screen is allowed to offer on a given day.
 *
 * Asking one question alone — is there a start without an end — and offering "log a period
 * start" whenever the answer is no would put the button, on the day a woman marked her period
 * finished, on a day the same screen draws as a bleeding day, and pressing it does real damage:
 * [cyclePeriods] gives each start the first end before the next start, so a start dated inside an
 * existing period takes that period's end for itself. A ten-day period becomes an orphaned start
 * and a one-day period, from one tap on a button the app recommends.
 */
class CycleActionTest {

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun day(text: String) = LocalDate.parse(text)

    /** The record the guard is for: a period closed today. */
    private val closedToday = listOf(
        entry(KIND_CYCLE, "2026-07-09"),
        entry(KIND_CYCLE_END, "2026-07-14"),
        entry(KIND_CYCLE, "2026-08-06"),
        entry(KIND_CYCLE_END, "2026-08-11"),
        entry(KIND_CYCLE, "2026-09-06"),
        entry(KIND_CYCLE_END, "2026-09-15"),
    )

    @Test
    fun theDayAPeriodWasClosedOffersToReopenItRatherThanToStartOne() {
        val action = cycleAction(closedToday, day("2026-09-15"))

        assertEquals(CycleAction.Reopen(CyclePeriod(day("2026-09-06"), day("2026-09-15"))), action)
    }

    /** Every day inside the period, not only its last. */
    @Test
    fun anyDayInsideAClosedPeriodOffersTheSame() {
        listOf("2026-09-06", "2026-09-10", "2026-09-15").forEach { inside ->
            val action = cycleAction(closedToday, day(inside))
            assertEquals(inside, CycleAction.Reopen(CyclePeriod(day("2026-09-06"), day("2026-09-15"))), action)
        }
    }

    /** The day after it ends, the ordinary offer is back. */
    @Test
    fun theDayAfterAPeriodEndsOffersAStart() {
        assertEquals(CycleAction.Start, cycleAction(closedToday, day("2026-09-16")))
    }

    /** A start with no end is a period running, whatever else is on file. */
    @Test
    fun aStartWithoutAnEndOffersToFinishIt() {
        val running = closedToday.dropLast(1)

        assertEquals(CycleAction.Finish(day("2026-09-06")), cycleAction(running, day("2026-09-10")))
    }

    /**
     * A start dated in the future — a mistyped year, or an import from a phone whose clock is ahead
     * — is not a period running today, and must not turn the button into "end the period".
     */
    @Test
    fun aStartDatedAheadIsNotARunningPeriod() {
        val typo = listOf(entry(KIND_CYCLE, "2026-12-20"))

        assertEquals(CycleAction.Start, cycleAction(typo, day("2026-09-15")))
    }

    /** Nothing on file at all: there is a period to begin. */
    @Test
    fun anEmptyRecordOffersAStart() {
        assertEquals(CycleAction.Start, cycleAction(emptyList(), day("2026-09-15")))
    }

    /**
     * The damage the guard exists to prevent, spelled out: with a start added on the day the
     * September period ended, that period loses its end and a one-day period takes its place.
     *
     * This is the arithmetic, not the guard — it holds [cyclePeriods] to the behaviour that makes
     * the guard necessary, so that a later change to the pairing rule cannot quietly make the
     * guard look pointless while leaving the screen offering the tap.
     */
    @Test
    fun aStartInsideAPeriodWouldStealItsEnd() {
        val damaged = closedToday + entry(KIND_CYCLE, "2026-09-15")

        val periods = cyclePeriods(damaged)
        assertEquals(
            listOf(
                CyclePeriod(day("2026-07-09"), day("2026-07-14")),
                CyclePeriod(day("2026-08-06"), day("2026-08-11")),
                CyclePeriod(day("2026-09-15"), day("2026-09-15")),
            ),
            periods,
        )
        assertEquals("the ten-day period is gone", 3, periods.size)
    }

    /**
     * Spotting is offered on a day outside a period and on none inside one, where flow is asked
     * instead; and the row it shows, the one its × takes off, is the latest of the day.
     */
    @Test
    fun spottingIsOfferedOutsideAPeriodAndShowsTheLatestRow() {
        fun spotting(id: Long, at: Long) = JournalEntry(
            id = id,
            day = "2026-09-28",
            occurredAt = at,
            kind = KIND_CYCLE_LOG,
            title = "Spotting",
            calories = 0.0,
            measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"SPOTTING"}""",
        )
        val today = day("2026-09-28")
        val journal = closedToday + spotting(7, 1L) + spotting(9, 2L)
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)

        assertEquals(null, facts.periodOn(today))
        assertEquals(9L, facts.spottingOn(today))
        assertEquals(null, facts.spottingOn(day("2026-09-27")))
        assertEquals(day("2026-09-06"), facts.periodOn(day("2026-09-10"))?.first)
    }

    /**
     * A day chosen on the week strip is asked its own questions, not today's. Following today's
     * phase, on the 28th, with the period long over, the 10th would be asked about mucus and desire
     * — no flow to fill in, and a flow already written not shown — and during a period a day of
     * mid-cycle could be given nothing but bleeding.
     */
    @Test
    fun aChosenDayIsAskedItsOwnQuestionsAndNotTodays() {
        val bleeding = listOf(CycleScale.FLOW, CycleScale.PAIN)
        val otherwise = listOf(CycleScale.MUCUS, CycleScale.DESIRE, CycleScale.OVULATION)

        val after = day("2026-09-28")
        val over = CycleFacts(closedToday, cycleSummary(closedToday, after), after)
        assertTrue("today is no day of a period", over.phase() !is CyclePhase.Menstruation)
        assertEquals(otherwise, over.questionsOn(after, acts = true))
        assertEquals("a missed day of the period", bleeding, over.questionsOn(day("2026-09-10"), acts = true))

        val inside = day("2026-09-10")
        val running = CycleFacts(closedToday, cycleSummary(closedToday, inside), inside)
        assertTrue("today is a day of a period", running.phase() is CyclePhase.Menstruation)
        assertEquals(bleeding, running.questionsOn(inside, acts = true))
        assertEquals("a day between two periods", otherwise, running.questionsOn(day("2026-08-25"), acts = true))
    }

    /**
     * A period remembered afterwards is filed only on days clear of every period on record. The
     * record button writes today and nothing else, so without this a missed start stays missed — and
     * a start dropped among another period's days is the damage [aStartInsideAPeriodWouldStealItsEnd]
     * spells out, so the page that files it is told which days are free.
     */
    @Test
    fun aPastPeriodIsFiledOnlyClearOfThePeriodsOnRecord() {
        val today = day("2026-09-28")
        // The October of the year before, well ahead of everything on file, and the gap in August.
        assertTrue(pastPeriodFits(closedToday, day("2025-10-03"), day("2025-10-07"), today))
        assertTrue(pastPeriodFits(closedToday, day("2026-08-20"), day("2026-08-24"), today))

        assertFalse("inside one", pastPeriodFits(closedToday, day("2026-08-08"), day("2026-08-09"), today))
        assertFalse("over one", pastPeriodFits(closedToday, day("2026-08-01"), day("2026-08-20"), today))
        assertFalse("ending on a start", pastPeriodFits(closedToday, day("2026-08-02"), day("2026-08-06"), today))
        // The day before a start and the day after an end are that same period carrying on.
        assertFalse("up against one", pastPeriodFits(closedToday, day("2026-08-01"), day("2026-08-05"), today))
        assertFalse("straight after one", pastPeriodFits(closedToday, day("2026-08-12"), day("2026-08-15"), today))
        assertTrue("a clear day between", pastPeriodFits(closedToday, day("2026-08-13"), day("2026-08-15"), today))

        assertFalse("backwards", pastPeriodFits(closedToday, day("2026-08-24"), day("2026-08-20"), today))
        assertFalse("ahead of today", pastPeriodFits(closedToday, day("2026-09-27"), day("2026-09-30"), today))
    }

    /**
     * A start without its end holds every day from itself to today, so nothing is filed after it;
     * and a period she says is still running reaches to today, so it cannot be laid over another.
     * The first ask of all, on a record with nothing in it, is free on any day that has happened.
     */
    @Test
    fun aRunningPeriodHoldsItsDaysAndTheFirstAskIsFree() {
        val today = day("2026-09-10")
        val running = closedToday.dropLast(1)

        assertFalse(pastPeriodFits(running, day("2026-09-08"), day("2026-09-09"), today))
        assertTrue("before it began", pastPeriodFits(running, day("2026-08-20"), day("2026-08-24"), today))
        val later = day("2026-09-28")
        assertFalse("still running, across July", pastPeriodFits(closedToday, day("2026-07-01"), null, later))
        assertTrue("still running, after them all", pastPeriodFits(closedToday, day("2026-09-25"), null, later))

        assertTrue(pastPeriodFits(emptyList(), day("2026-08-27"), day("2026-08-31"), today))
        assertTrue(pastPeriodFits(emptyList(), day("2026-09-08"), null, today))
    }

    /**
     * The start and the end of a remembered period are one write, from the cycle screen's page and
     * from the home screen's dialog alike. Filed one after the other, a process that died between
     * them would leave the start on the record as a period running ever since, and the dialog could
     * file its end behind a start the record had turned down, closing another period. Pinned in the source
     * because nothing on the JVM opens the database.
     */
    @Test
    fun aRememberedPeriodIsFiledAsOneWriteFromBothPlaces() {
        val text = File("src/main/java/com/pwnall/health/CycleLogPeriod.kt").readText()
        val filing = text.substringAfter("fun StepsDb.logRememberedPeriod(").substringBefore("\n}\n")
        val start = filing.indexOf("logCycleStart(")

        assertTrue("inside a transaction", filing.indexOf("withTransaction {") in 0 until start)
        assertTrue("the end after the start, and only behind it", filing.indexOf("if (started) end?.let") > start)
        val call = "db.logRememberedPeriod(resources, start, end)"
        val screen = File("src/main/java/com/pwnall/health/CycleScreen.kt").readText()
        assertTrue("the page", call in screen.substringAfter("FirstCyclePage(").substringBefore("CycleTopBar("))
        val home = File("src/main/java/com/pwnall/health/HomeScreen.kt").readText()
        val dialog = home.substringAfter("FirstCycleDialog(").substringBefore("ClosePeriodDialog(")
        assertTrue("and the dialog", call in dialog)
    }

    /**
     * Closing a period left open is held to the record as filing a missed one is: the start may be
     * moved, but not into or against another period, and the end not past today. The period's own
     * start is not in its way, and nor is an older start never closed — it is not the one running.
     */
    @Test
    fun aPeriodIsClosedOnlyOnDaysClearOfTheOthers() {
        val today = day("2026-10-20")
        val marks = listOf(
            entry(KIND_CYCLE, "2026-09-01"),
            entry(KIND_CYCLE_END, "2026-09-05"),
            entry(KIND_CYCLE, "2026-10-01"),
        )
        val open = day("2026-10-01")
        fun fits(from: String, to: String, record: List<JournalEntry> = marks) =
            closingPeriodFits(record, open, day(from), day(to), today)

        assertTrue("as it stands", fits("2026-10-01", "2026-10-06"))
        assertTrue("moved a few days", fits("2026-09-28", "2026-10-03"))
        assertFalse("into the period before", fits("2026-09-04", "2026-10-03"))
        assertFalse("against it", fits("2026-09-06", "2026-10-03"))
        assertFalse("an end not yet come", fits("2026-10-01", "2026-10-21"))
        val orphan = marks + entry(KIND_CYCLE, "2026-09-15")
        assertTrue("an older start left open", fits("2026-10-01", "2026-10-06", orphan))
    }

    /**
     * The end starts where her periods usually end, not on today: the close is only asked for once
     * a period has been open longer than one lasts — the case where today is the one day it did not
     * end on.
     */
    @Test
    fun theCloseStartsTheEndWhereHerPeriodsUsuallyEnd() {
        val start = day("2026-10-01")

        assertEquals(day("2026-10-06"), closingEndGuess(start, 6, day("2026-10-20")))
        assertEquals("before any is closed", day("2026-10-05"), closingEndGuess(start, null, day("2026-10-20")))
        assertEquals("never after today", day("2026-10-03"), closingEndGuess(start, 6, day("2026-10-03")))
    }

    /**
     * Each question of each day is given a slider of its own. One slider for them all would keep the
     * finger's last position across questions that open on the same middle index: after "Light" the
     * question about pain would open on "No pain", an answer nobody gave. Pinned in the source because
     * the guard is one `key` inside a composable: nothing on the JVM would notice it gone.
     */
    @Test
    fun everyQuestionIsGivenItsOwnSlider() {
        val text = File("src/main/java/com/pwnall/health/CycleScreen.kt").readText()
        val block = text.substringAfter("private fun CycleTodayBlock(").substringBefore("\n}\n")

        assertTrue(Regex("""key\(day, scale\) \{\s+CycleStopSlider\(""").containsMatchIn(block))
        assertEquals("and is composed nowhere else", 1, Regex("""(?<!fun )CycleStopSlider\(""").findAll(text).count())
    }

    /**
     * Until the journal has been read the screen draws neither of its pages. Starting from an empty
     * list, which is a record with no period in it, every opening would first draw the page that
     * asks when the last period began, its Save ready to file a start over the record being read.
     * Pinned in the source because the wait is one `if` inside a composable.
     */
    @Test
    fun theScreenWaitsForTheJournalBeforeItDrawsAPage() {
        val text = File("src/main/java/com/pwnall/health/CycleScreen.kt").readText()
        val screen = text.substringAfter("fun CycleScreen(").substringBefore("\n}\n")

        assertTrue(
            "the journal starts unread rather than empty",
            "collectAsStateWithLifecycle<List<JournalEntry>?>(null)" in screen,
        )
        val wait = screen.indexOf("if (loaded == null) {")
        assertTrue("the wait is there", wait >= 0)
        assertTrue("before the calendar", wait < screen.indexOf("CycleCalendarPage("))
        assertTrue("and before the first-run page", wait < screen.indexOf("FirstCyclePage("))
    }
}
