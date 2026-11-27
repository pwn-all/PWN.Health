package com.pwnall.health

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The readings the figure, dose and cycle widgets paint, apart from the widgets that paint them. */
class FigureWidgetsTest {
    private val hour = 3_600_000L
    private val today = LocalDate.of(2026, 11, 8)

    private fun sleep(startAt: Long, endAt: Long, source: String? = null) = JournalEntry(
        day = today.toString(),
        occurredAt = endAt,
        kind = "MEASUREMENT",
        title = "sleep",
        calories = 0.0,
        sleepStartAt = startAt,
        sleepEndAt = endAt,
        measurementSource = source,
    )

    private fun weighing(kg: Double, at: Long) = JournalEntry(
        day = today.toString(),
        occurredAt = at,
        kind = "MEASUREMENT",
        title = "weight",
        calories = 0.0,
        measurementWeightKg = kg,
    )

    @Test
    fun `a measured night beats the phone's estimate, and of two measured the longer wins`() {
        val estimate = sleep(0, 8 * hour, PHONE_ESTIMATE)
        val nap = sleep(14 * hour, 15 * hour)
        val night = sleep(hour, 7 * hour)
        assertEquals(Night(hour, 7 * hour, measured = true), lastNight(listOf(estimate, nap, night)))
        assertEquals(Night(0, 8 * hour, measured = false), lastNight(listOf(estimate)))
        assertNull(lastNight(listOf(weighing(70.0, hour))))
        assertEquals(360, Night(hour, 7 * hour, measured = true).minutes)
    }

    @Test
    fun `the weight reading is the latest weighing and its distance from the first in the window`() {
        val rows = listOf(weighing(72.4, 30 * hour), weighing(73.0, hour), weighing(72.8, 10 * hour))
        assertEquals(WeightFigure(72.4, 72.4 - 73.0), weightReading(rows))
        assertEquals(WeightFigure(73.0, null), weightReading(listOf(weighing(73.0, hour))))
        assertNull(weightReading(emptyList()))
    }

    @Test
    fun `the week is seven days oldest first with uncounted days as zero`() {
        val days = listOf(DaySteps(today.toString(), 4_000), DaySteps(today.minusDays(6).toString(), 9_000))
        assertEquals(listOf(9_000L, 0L, 0L, 0L, 0L, 0L, 4_000L), weekSteps(days, today))
        assertEquals(WEEK_WIDGET_DAYS, weekSteps(emptyList(), today).size)
    }

    @Test
    fun `a bar is its share of the busiest day and never thinner than the floor`() {
        assertEquals(36.dp, weekBarHeight(8_000, 8_000))
        assertEquals(18.dp, weekBarHeight(4_000, 8_000))
        assertEquals(3.dp, weekBarHeight(0, 8_000))
        assertEquals(3.dp, weekBarHeight(0, 0))
    }

    @Test
    fun `the streak counts back from today or yesterday and dies a day after that`() {
        fun opens(vararg daysAgo: Long) = daysAgo.map { AppOpenDay(today.minusDays(it).toString()) }
        assertEquals(3, openingStreak(opens(0, 1, 2), today))
        assertEquals(2, openingStreak(opens(1, 2, 5), today))
        assertEquals(0, openingStreak(opens(2, 3), today))
        assertEquals(0, openingStreak(emptyList(), today))
        // A day not yet come counts for nothing.
        assertEquals(1, openingStreak(opens(0, -1), today))
    }

    @Test
    fun `the next dose is the earliest unmarked, so a late one comes before one still to come`() {
        fun dose(minute: Int, takenAt: Long? = null) = PlannedDose(
            medicationId = minute.toLong(),
            name = "m$minute",
            dose = "1",
            food = MedFood.ANY,
            slot = "s$minute",
            minuteOfDay = minute,
            onWaking = false,
            takenAtMs = takenAt,
        )
        val doses = listOf(dose(20 * 60), dose(8 * 60, takenAt = 1L), dose(14 * 60))
        assertEquals(14 * 60, nextDose(doses)?.minuteOfDay)
        assertEquals(DoseFace.AllTaken, doseFace(listOf(dose(8 * 60, takenAt = 1L))))
        assertEquals(DoseFace.NoCourses, doseFace(emptyList()))
        assertEquals(DoseFace.Next(doses[2]), doseFace(doses))
    }

    /**
     * The line is one line, cut at its end, and the dose is the person's own words: behind them the
     * time would be the first thing lost, on a widget there to say when. Every language puts the time
     * first, and a course with no dose written shows the time with no separator left hanging.
     */
    @Test
    fun `the dose widget's line gives the time before the dose, in every language`() {
        val template = Regex("""<string name="widget_dose_at">([^<]*)</string>""")
        val folders = File("src/main/res").listFiles { file -> file.name.startsWith("values") }.orEmpty()
            .filter { File(it, "widgets.xml").exists() }
        assertEquals(16, folders.size)
        folders.forEach { folder ->
            val text = template.find(File(folder, "widgets.xml").readText())?.groupValues?.get(1)
            assertTrue("${folder.name}: $text", text.orEmpty().startsWith("%2\$s") && "%1\$s" in text.orEmpty())
        }
        val resources = NamedResources()
        assertEquals("widget_dose_at[2 capsules|08:00]", doseCaption(resources, "2 capsules", "08:00"))
        assertEquals("08:00", doseCaption(resources, " ", "08:00"))
    }

    /**
     * The tab, the reminder and the home page say "On waking", and so does the widget, rather than the
     * eight o'clock standing in.
     */
    @Test
    fun `a dose on waking is On waking on the widget, not the hour that stands in for the wake time`() {
        val waking = PlannedDose(
            medicationId = 1,
            name = "m",
            dose = "1",
            food = MedFood.ANY,
            slot = MED_SLOT_WAKE,
            minuteOfDay = 8 * 60,
            onWaking = true,
        )
        val resources = NamedResources()
        assertEquals("med_dose_on_waking", doseTimeText(resources, waking))
        assertEquals("08:00", doseTimeText(resources, waking.copy(slot = "480", onWaking = false)))
    }

    /**
     * A record too old to give a day of the cycle is one gone quiet: the widget says since when, and
     * not "No cycle recorded" over it.
     */
    @Test
    fun `the cycle widget says since when a record has gone quiet, and no cycle only over no record`() {
        val resources = NamedResources()
        val iso = DateTimeFormatter.ISO_LOCAL_DATE
        fun lines(entries: List<JournalEntry>): Pair<String, String> {
            val summary = cycleSummary(entries, today)
            return cycleLines(resources, summary, CycleFacts(entries, summary, today).phase(), iso, iso)
        }
        val start = today.minusDays(200)
        val quiet = JournalEntry(day = start.toString(), occurredAt = 0, kind = KIND_CYCLE, title = "", calories = 0.0)

        assertEquals("cycle_nudge_late[$start]" to "cycle_title", lines(listOf(quiet)))
        assertEquals("cycle_phase_unknown" to "cycle_title", lines(emptyList()))
    }

    @Test
    fun `a Taken tapped after midnight marks the day its face was drawn for and not the new one`() {
        val yesterday = today.minusDays(1)
        assertEquals(yesterday, tappedDoseDay(yesterday.toString(), today))
        assertEquals(today, tappedDoseDay(today.toString(), today))
        // Older than that, ahead of a clock since set back, or naming no day: nothing is marked.
        assertNull(tappedDoseDay(today.minusDays(2).toString(), today))
        assertNull(tappedDoseDay(today.plusDays(1).toString(), today))
        assertNull(tappedDoseDay("yesterday", today))
        assertNull(tappedDoseDay(null, today))
        // The 22:00 tablet taken late and tapped at ten past midnight: filed under the face's day it
        // settles that evening's dose, and the evening to come still has its own — and its reminder.
        val course = Medication(id = 1, name = "m", times = "1320", startDay = yesterday.toString(), createdAt = 0)
        val shown = plannedDoses(listOf(course), emptyList(), yesterday).single()
        val mark = medTakenEntry(shown, yesterday, today.toEpochDay() * 24 * hour + hour / 6)
        assertEquals(listOf(true), plannedDoses(listOf(course), listOf(mark), yesterday).map(PlannedDose::taken))
        assertEquals(listOf(false), plannedDoses(listOf(course), listOf(mark), today).map(PlannedDose::taken))
    }

    /**
     * The level widget reads the journal kinds the year's XP counts and no others. A row of every kind
     * the app names counts the same with only those kinds left in, so an XP rule that comes to count
     * another kind fails here rather than leaving the widget a level behind the levels page.
     */
    @Test
    fun `the level widget reads every journal kind the year's XP counts`() {
        val declared = Regex("""const val KIND_\w+ = "(\w+)"""")
        val kinds = File("src/main/java/com/pwnall/health").listFiles().orEmpty()
            .flatMap { file -> declared.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .distinct()
        // A scan that found few has stopped reading what it guards.
        assertTrue(kinds.toString(), kinds.size > 15 && kinds.containsAll(XP_JOURNAL_KINDS))
        val journal = kinds.map { JournalEntry(day = "$today", occurredAt = 0, kind = it, title = it, calories = 0.0) }
        val zone = ZoneId.of("UTC")
        fun xp(rows: List<JournalEntry>) = annualXp(today, emptyList(), rows, emptyList(), emptyList(), zone)

        assertEquals(xp(journal), xp(journal.filter { it.kind in XP_JOURNAL_KINDS }))
        assertTrue(xp(journal).total > 0L)
    }

    /**
     * The energy widget's three figures are the food page's: the meals alone as eaten, an allowance of
     * the budget with movement added — a swim on top, and of a logged walk and the pedometer's steps
     * only the larger claim — and what is left of it. Counted from the profile with the latest weighing
     * laid over it; the stored row still holds the weight onboarding was given, and from it every
     * figure but the eaten one would come out different from the page's.
     */
    @Test
    fun `the energy widget counts the food page's day from the weighed profile`() {
        val stored = UserProfile(sex = "MALE", weightKg = 95.0, heightCm = 180, birthDate = "1990-04-17")
        val weighed = stored.inEffect(measuredWeightKg = 80.0, measuredHeightCm = null)
        fun row(kind: String, kcal: Double, code: String? = null) = JournalEntry(
            day = "$today",
            occurredAt = 0,
            kind = kind,
            title = kind,
            calories = kcal,
            activityCode = code,
        )
        val journal = listOf(
            row(KIND_EAT, 600.0),
            row(KIND_EAT, 300.0),
            row(KIND_ACTIVITY, 200.0, "SWIM"),
            row(KIND_ACTIVITY, 50.0, "WALK"),
            row(KIND_WATER, 0.0),
        )
        val walked = stepCalories(6_000, 80.0, 180)
        assertTrue("the steps outweigh the logged walk: $walked", walked > 50.0)

        val day = energyDay(weighed, journal, steps = 6_000, year = today.year)

        assertEquals(900.0, day.eatenKcal, 1e-9)
        assertEquals(energyBudget(weighed, today.year).targetKcal + 200.0 + walked, day.limitKcal, 1e-9)
        assertEquals(day.limitKcal - day.eatenKcal, day.remainingKcal, 1e-9)
        assertNotEquals(day.limitKcal, energyDay(stored, journal, steps = 6_000, year = today.year).limitKcal, 1.0)
    }
}
