package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The work history's weeks. The borders under test: a week starts on the locale's first day, so the
 * same Sunday falls in different weeks for Monday and Sunday locales; a night shift is listed once,
 * under the week it began in; the newest week and the newest session come first; the heading adds up
 * whole net minutes as the rows print them, a capped session included; rows of other kinds and rows
 * whose day cannot be read are not listed. The window starts on a week's first day, so the oldest
 * week is whole, and a session's page keeps the session's own day on offer.
 */
class WorkHistoryTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun d(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun work(day: LocalDate, fromHour: Int, toHour: Int, toMinute: Int = 0, id: Long = 0L): JournalEntry =
        workEntry(listOf(WorkSegment(at(day, fromHour), at(day, toHour, toMinute))), "Work", zone)!!.copy(id = id)

    /** Monday 14 September 2026 to Sunday 27 September: two whole weeks. */
    private val monday = d(9, 14)

    @Test
    fun weeksAreNewestFirstAndSessionsNewestFirstInsideThem() {
        val rows = listOf(
            work(monday, 9, 17, id = 1),
            work(monday.plusDays(7), 9, 12, id = 2),
            work(monday.plusDays(2), 8, 16, 30, id = 3),
            work(monday.plusDays(9), 10, 11, id = 4),
        )
        val weeks = workHistoryWeeks(rows, DayOfWeek.MONDAY)
        assertEquals(listOf(monday.plusDays(7), monday), weeks.map { it.start })
        assertEquals(listOf(4L, 2L), weeks[0].sessions.map { it.id })
        assertEquals(listOf(3L, 1L), weeks[1].sessions.map { it.id })
        assertEquals(3 * 60 + 60, weeks[0].netMinutes)
        assertEquals(8 * 60 + 8 * 60 + 30, weeks[1].netMinutes)
    }

    @Test
    fun theWeekStartsOnTheLocalesFirstDay() {
        val sunday = monday.plusDays(6)
        val rows = listOf(work(monday, 9, 10), work(sunday, 9, 10))
        // Monday first: one week, from the 14th.
        assertEquals(listOf(monday), workHistoryWeeks(rows, DayOfWeek.MONDAY).map { it.start })
        // Sunday first: the Sunday opens the next week, the Monday belongs to the one from the 13th.
        assertEquals(listOf(sunday, monday.minusDays(1)), workHistoryWeeks(rows, DayOfWeek.SUNDAY).map { it.start })
    }

    @Test
    fun aNightShiftAcrossTheWeeksEndIsListedOnceUnderTheWeekItBeganIn() {
        val sunday = monday.plusDays(6)
        val shift = workEntry(listOf(WorkSegment(at(sunday, 22), at(sunday.plusDays(1), 6))), "Work", zone)!!
        val weeks = workHistoryWeeks(listOf(shift), DayOfWeek.MONDAY)
        assertEquals(1, weeks.size)
        assertEquals(monday, weeks.single().start)
        assertEquals(8 * 60, weeks.single().netMinutes)
    }

    @Test
    fun theHeadingAddsUpTheRowsAsTheyPrint() {
        // An imported row carries whatever minutes it came with. 432.9 prints as 7 h 12 min, so two
        // of them are 14 h 24 min under the heading too — adding the fractions first would say 14 h 25.
        fun imported(day: LocalDate) = JournalEntry(
            day = day.toString(),
            occurredAt = at(day, 8),
            kind = KIND_WORK,
            title = "Work",
            calories = 0.0,
            durationMinutes = 432.9,
        )
        val week = workHistoryWeeks(listOf(imported(monday), imported(monday.plusDays(1))), DayOfWeek.MONDAY)
        assertEquals(2 * (7 * 60 + 12), week.single().netMinutes)
    }

    @Test
    fun aCappedSessionCountsInItsWeekAsItDoesOnTheWorkPage() {
        val day = work(monday, 9, 17)
        val capped = workEntry(
            listOf(WorkSegment(at(monday.plusDays(2), 6), at(monday.plusDays(2), 22))),
            "Work",
            zone,
            capped = true,
        )!!
        val week = workHistoryWeeks(listOf(day, capped), DayOfWeek.MONDAY).single()
        assertEquals(2, week.sessions.size)
        assertTrue(capped in week.sessions)
        assertEquals(8 * 60 + 16 * 60, week.netMinutes)
        // The Work page's "this week" is the same rows split by day, the capped one's hours included.
        assertEquals(week.netMinutes.toDouble(), workMinutesByDay(listOf(day, capped), zone).values.sum(), 1e-9)
    }

    @Test
    fun otherKindsAndUnreadableDaysAreNotListed() {
        val water = JournalEntry(
            day = monday.toString(),
            occurredAt = at(monday, 12),
            kind = "WATER",
            title = "Water",
            calories = 0.0,
        )
        val broken = work(monday, 9, 10).copy(day = "not a day")
        assertEquals(emptyList<WorkHistoryWeek>(), workHistoryWeeks(listOf(water, broken), DayOfWeek.MONDAY))
    }

    @Test
    fun theWindowStartsOnTheFirstDayOfAWeekSoTheOldestWeekIsWhole() {
        val thursday = monday.plusDays(10)
        // Monday first: this week from the 21st and twenty-five before it.
        val since = workHistorySince(thursday, DayOfWeek.MONDAY, 26)
        assertEquals(DayOfWeek.MONDAY, since.dayOfWeek)
        assertEquals(monday.plusDays(7).minusWeeks(25), since)
        // Sunday first: from a Sunday, a day earlier.
        assertEquals(monday.plusDays(6).minusWeeks(25), workHistorySince(thursday, DayOfWeek.SUNDAY, 26))
        // Today the week's first day: the window is exactly twenty-six weeks.
        assertEquals(monday.minusWeeks(25), workHistorySince(monday, DayOfWeek.MONDAY, 26))
        // A session on the window's first day heads the oldest week, dated that same day.
        val oldest = workHistoryWeeks(listOf(work(since, 9, 10)), DayOfWeek.MONDAY).single()
        assertEquals(since, oldest.start)
    }

    @Test
    fun aSessionsPageKeepsItsOwnDayOnOfferAfterAnotherIsChosen() {
        val today = monday.plusDays(10)
        val march = d(3, 30)
        val choices = workDayChoices(today, original = march, chosen = today)
        assertTrue(march in choices)
        assertEquals((0L..6L).map { today.minusDays(it) } + march, choices)
        // A new session has no day of its own: the last week, and a day picked outside it stays too.
        assertEquals(7, workDayChoices(today, original = null, chosen = today).size)
        assertEquals(march, workDayChoices(today, original = null, chosen = march).last())
        // A session from this week is offered once.
        assertEquals(7, workDayChoices(today, original = today.minusDays(2), chosen = today).size)
    }

    /**
     * A row whose stretch nobody could have worked — ten weeks in one go — has no segments to open
     * on, so its page opens on the day the row carries, not on today; a session with its stretches
     * opens on the day the first began, and a new one has no day of its own.
     */
    @Test
    fun aRowWhoseStretchesWereThrownOutOpensOnItsOwnDay() {
        val march = d(3, 30)
        val absurd = work(march, 9, 17).copy(measurementPayloadJson = null, durationMinutes = 100_000.0)
        assertTrue(workSegmentsOf(absurd).isEmpty())
        assertEquals(march, workOwnDay(absurd, workSegmentsOf(absurd), zone))

        val night = work(march, 22, 23)
        assertEquals(march, workOwnDay(night.copy(day = "2026-01-01"), workSegmentsOf(night), zone))
        assertEquals(null, workOwnDay(null, emptyList(), zone))
        assertEquals(null, workOwnDay(absurd.copy(day = "not a day"), emptyList(), zone))
    }

    @Test
    fun aRowWithoutAPayloadStillCountsItsMinutes() {
        val imported = JournalEntry(
            day = monday.toString(),
            occurredAt = at(monday, 9),
            kind = KIND_WORK,
            title = "Work",
            calories = 0.0,
            durationMinutes = 95.0,
        )
        assertEquals(95, workHistoryWeeks(listOf(imported), DayOfWeek.MONDAY).single().netMinutes)
    }

    /**
     * The dial snaps to five minutes, a screen reader moves it a quarter of an hour at a time and a
     * keyboard not at all, so the page also sets both clocks to the minute, as the sleep form does.
     * Pinned in the source because the pair is two calls inside a composable: nothing on the JVM would
     * notice the wheels gone.
     */
    @Test
    fun aSessionsPageSetsBothClocksToTheMinuteBesideTheDial() {
        val text = File("src/main/java/com/pwnall/health/WorkScreen.kt").readText()
        val form = text.substringAfter("private fun WorkSessionForm(").substringBefore("\n}\n")

        assertTrue("the dial", "SleepDial(" in form)
        assertEquals("a wheel each", 2, Regex("""ClockTimePickerCard\(""").findAll(form).count())
        assertTrue("the start", "onValue = { startMinute = it }" in form)
        assertTrue("the end", "onValue = { endMinute = it }" in form)
    }

    /**
     * The same holds for every file that draws the dial, the sleep form's and the next one's alike: a
     * dial alone sets a time a screen reader reaches a quarter of an hour at a time and a keyboard never.
     */
    @Test
    fun everyFileThatDrawsTheDialOffersTheClocksToTheMinuteToo() {
        val call = Regex("""(?<!fun )\bSleepDial\(""")
        val drawing = File("src/main/java/com/pwnall/health").listFiles { file -> file.extension == "kt" }.orEmpty()
            .filter { call.containsMatchIn(it.readText()) }
            .map { it.name to it.readText() }

        val names = drawing.map { it.first }
        assertTrue(names.toString(), names.containsAll(listOf("EntryEditorScreen.kt", "WorkScreen.kt")))
        assertEquals(emptyList<String>(), drawing.filterNot { "ClockTimePickerCard(" in it.second }.map { it.first })
    }
}
