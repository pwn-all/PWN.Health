package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The age gate on the cycle screen.
 *
 * The screen files the intimate screen's own rows — the two partnered acts and the solo one — so
 * the gate stands on this page as well as on [IntimateScreen]'s door: this is the main page of the
 * one feature a thirteen-year-old with a cycle opens every day. What the gate decides is held here
 * in two halves: what the calendar is given to draw, which is arithmetic, and where the buttons are
 * composed, which only the source can show.
 */
class CycleAgeGateTest {
    private val today = LocalDate.parse("2026-11-15")

    private fun entry(kind: String, day: String, payload: String? = null) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    /** A period, how heavy one day of it was, and two acts — which a restored backup can hold at any age. */
    private val journal = listOf(
        entry(KIND_CYCLE, "2026-11-02"),
        entry(KIND_CYCLE_LOG, "2026-11-03", """{"cycle":"FLOW_MEDIUM"}"""),
        entry(KIND_CYCLE_END, "2026-11-06"),
        entry(KIND_CYCLE_LOG, "2026-11-10", """{"cycle":"SEX_UNPROTECTED"}"""),
        entry(KIND_MASTURBATION, "2026-11-12"),
    )

    private fun month(acts: Boolean) = CycleFacts(journal, cycleSummary(journal, today), today)
        .calendarMonth(YearMonth.of(2026, 11), DayOfWeek.MONDAY, acts)

    /** Thirteen: the acts in the journal are on no square, so no heart is drawn and none is spoken. */
    @Test
    fun theCalendarCarriesNoActForAProfileUnderTheGate() {
        val acts = sexLogOffered("2013-05-01", today)

        assertFalse(acts)
        assertTrue(month(acts).none { it.intimacy })
    }

    /** Sixteen and over: both acts stand on their own days, the solo one included. */
    @Test
    fun theCalendarCarriesTheActsOnceTheGateIsOpen() {
        val acts = sexLogOffered("2010-11-15", today)

        assertTrue(acts)
        assertEquals(
            listOf(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-12")),
            month(acts).filter { it.intimacy }.map { it.date },
        )
    }

    /** The gate takes the acts off the month and nothing else: her period and its flow are the same record. */
    @Test
    fun theGateRemovesNothingButTheActs() {
        val open = month(acts = true)
        val shut = month(acts = false)

        assertEquals(open.map { it.copy(intimacy = false) }, shut)
        assertEquals(2, shut.single { it.date == LocalDate.parse("2026-11-03") }.flow)
        assertTrue(shut.single { it.date == LocalDate.parse("2026-11-03") }.bleeding)
    }

    /**
     * Desire is asked of her sex life, so it stands behind the same gate: under it a day between
     * periods is asked what she is seeing and about a test, and a day of a period is asked its usual
     * questions.
     */
    @Test
    fun desireIsNotAskedUnderTheGate() {
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        val inPeriod = LocalDate.parse("2026-11-03")

        assertEquals(listOf(CycleScale.MUCUS, CycleScale.OVULATION), facts.questionsOn(today, acts = false))
        assertEquals(
            listOf(CycleScale.MUCUS, CycleScale.DESIRE, CycleScale.OVULATION),
            facts.questionsOn(today, acts = true),
        )
        assertEquals(facts.questionsOn(inPeriod, acts = true), facts.questionsOn(inPeriod, acts = false))
    }

    /** Nor drawn: an answer a restored backup brought with it has no line on the plot under the gate. */
    @Test
    fun thePlotDrawsNoDesireUnderTheGate() {
        val restored = journal + entry(KIND_CYCLE_LOG, today.toString(), """{"cycle":"DESIRE_HIGH"}""")
        val facts = CycleFacts(restored, cycleSummary(restored, today), today)
        fun ladders(acts: Boolean) = facts.chart(facts.phase(), CycleWindow.WEEK, acts).series.map { it.scale }

        assertTrue(CycleScale.DESIRE in ladders(acts = true))
        assertFalse(CycleScale.DESIRE in ladders(acts = false))
    }

    /**
     * Off the cycle page the same rows are still in the journal, and the journal is listed day by
     * day on the calendar: under the gate the diary and the answers about desire are not handed to
     * it, her period and its flow are, and nothing is taken from a profile of age.
     */
    @Test
    fun theJournalIsListedWithoutWhatTheGateRemoves() {
        val restored = journal +
            entry(KIND_CYCLE_LOG, "2026-11-11", """{"cycle":"DESIRE_HIGH"}""") +
            entry(KIND_MORNING_CHECK, "2026-11-13")

        assertEquals(journal.take(3), restored.shownTo(acts = false))
        assertEquals(restored, restored.shownTo(acts = true))
        val rungs = CycleScale.DESIRE.logs.map { entry(KIND_CYCLE_LOG, "2026-11-11", """{"cycle":"$it"}""") }
        assertTrue("every rung of the ladder, \"none\" included", rungs.all { it.behindSexGate() })

        val calendar = File("src/main/java/com/pwnall/health/CalendarScreen.kt").readText()
        assertTrue("the calendar reads the gate", "val acts = sexLogOffered(profile.birthDate, today)" in calendar)
        assertTrue("and lists what it leaves", "stored.shownTo(acts)" in calendar)
    }

    /** The page hands the gate's answer to the questions and to the plot, as it does to the calendar. */
    @Test
    fun theScreenAsksAndPlotsDesireOnlyBehindTheGate() {
        val text = File("src/main/java/com/pwnall/health/CycleScreen.kt").readText()

        assertTrue("the questions are told", "facts.questionsOn(day, acts)" in text)
        assertTrue("and the plot", "facts.chart(phase, CycleWindow.entries[chosen], acts)" in text)
    }

    /**
     * The buttons that file an act are composed only behind [sexLogOffered], rule and heading with
     * them, and the calendar is handed the same answer. Pinned in the source because the decision
     * is one `if` inside a composable: nothing on the JVM would notice it gone.
     */
    @Test
    fun theScreenComposesTheActsOnlyBehindTheGate() {
        val text = File("src/main/java/com/pwnall/health/CycleScreen.kt").readText()
        val screen = text.substringAfter("fun CycleScreen(").substringBefore("\n}\n")

        assertTrue("the gate is read", "val acts = sexLogOffered(profile?.birthDate, today)" in screen)
        val gated = Regex("""if \(acts\) \{\s+HorizontalDivider\(color = CycleCell\)\s+CycleIntimateRow\(""")
        assertTrue("the row stands behind it", gated.containsMatchIn(screen))
        assertEquals("and is composed nowhere else", 1, Regex("""(?<!fun )CycleIntimateRow\(""").findAll(text).count())
        val calendar = screen.substringAfter("CycleCalendarPage(").substringBefore("onBack")
        // At the start of its own line: "facts = facts," ends in nearly the same letters.
        assertTrue("the calendar is told", Regex("""\n\s+acts = acts,""").containsMatchIn(calendar))
    }
}
