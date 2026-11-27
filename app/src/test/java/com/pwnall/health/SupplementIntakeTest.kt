package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What a supplement course says one dose holds, and what the marked doses add up to.
 *
 * The rule every case here holds to: a dose not marked was not taken, and a day that asked for
 * nothing recorded nothing — so "not asked" is an absent day, never a zero, and "asked, not marked"
 * is a zero, never an absent day. The sheet a doctor reads is built on the difference.
 */
class SupplementIntakeTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 10)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun course(
        id: Long = 1,
        contents: String = "VITAMIN_D=25",
        schedule: MedSchedule = MedSchedule.CLOCK,
        times: String = "480",
        start: LocalDate = today.minusDays(5),
        createdAt: Long = 0,
    ) = Medication(
        id = id,
        name = "Vitamin D3",
        dose = "1 capsule",
        schedule = schedule.name,
        times = times,
        startDay = start.toString(),
        contents = contents,
        createdAt = createdAt,
    )

    private fun minutesOn(day: LocalDate, minute: Int): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    /** The row the screen writes when the dose is ticked, built by the same factory. */
    private fun marked(medication: Medication, day: LocalDate, id: Long, minute: Int = 490): JournalEntry {
        val dose = plannedDoses(listOf(medication), emptyList(), day).first()
        return medTakenEntry(dose, day, minutesOn(day, minute)).copy(id = id)
    }

    /** A mark that names the course and the slot and holds nothing of its own. */
    private fun oldMark(course: Long, slot: String, day: LocalDate, id: Long) = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = minutesOn(day, 490),
        kind = KIND_MED,
        title = "Vitamin D3",
        calories = 0.0,
        measurementPayloadJson = """{"$MED_COURSE_KEY":$course,"$MED_SLOT_KEY":"$slot"}""",
    )

    private fun intake(
        medications: List<Medication>,
        journal: List<JournalEntry>,
        from: LocalDate = today.minusDays(2),
        to: LocalDate = today.minusDays(1),
        nowMinute: Int = 24 * 60,
    ) = supplementIntake(medications, journal, from, to, today, nowMinute, zone)

    @Test
    fun anUnmarkedDoseWasAskedForAndNothingWasTaken() {
        val days = intake(listOf(course()), emptyList())[Nutrient.VITAMIN_D]!!

        assertEquals(listOf(today.minusDays(2), today.minusDays(1)), days.keys.toList())
        assertEquals(NutrientDay(marked = 0.0, planned = 25.0), days[today.minusDays(1)])
    }

    /**
     * A course entered with a start a week back was asking nothing before the day it was written
     * down, and nothing before its first day: those days are missing from the map, not zeros in it.
     */
    @Test
    fun aDayNothingAskedAboutIsAbsentRatherThanZero() {
        val written = course(start = today.minusDays(4), createdAt = minutesOn(today.minusDays(2), 7 * 60))
        val days = intake(listOf(written), emptyList(), from = today.minusDays(6))[Nutrient.VITAMIN_D]!!

        assertEquals(listOf(today.minusDays(2), today.minusDays(1)), days.keys.toList())
        // Written down at nine in the evening, the course still asked for that morning's capsule —
        // the day counts whole, as the day list shows it — and an unmarked dose is a zero.
        val evening = course(createdAt = minutesOn(today.minusDays(1), 21 * 60))
        val eveningDays = intake(listOf(evening), emptyList(), from = today.minusDays(1))[Nutrient.VITAMIN_D]!!
        assertEquals(mapOf(today.minusDays(1) to NutrientDay(marked = 0.0, planned = 25.0)), eveningDays)
    }

    /**
     * Raising the dose next month must not double the doses already taken: each mark carries what
     * it held, and only the doses marked after the edit count the new figure.
     */
    @Test
    fun aMarkKeepsWhatTheDoseHeldWhenTheCourseIsChangedAfterIt() {
        val before = course(contents = "VITAMIN_D=25")
        val first = marked(before, today.minusDays(2), id = 1)
        assertTrue(first.measurementPayloadJson!!.contains("\"$MED_CONTENTS_KEY\":\"VITAMIN_D=25\""))

        val after = before.copy(contents = "VITAMIN_D=50")
        val second = marked(after, today.minusDays(1), id = 2)
        val days = intake(listOf(after), listOf(first, second))[Nutrient.VITAMIN_D]!!

        assertEquals(NutrientDay(25.0, 25.0), days[today.minusDays(2)])
        assertEquals(NutrientDay(50.0, 50.0), days[today.minusDays(1)])
    }

    /** A mark that holds nothing of its own reads what the course holds now. */
    @Test
    fun anOlderMarkReadsWhatTheCourseHoldsNow() {
        val days = intake(
            listOf(course(contents = "VITAMIN_D=50")),
            listOf(oldMark(1, "480", today.minusDays(1), id = 1)),
            from = today.minusDays(1),
        )[Nutrient.VITAMIN_D]!!

        assertEquals(NutrientDay(50.0, 50.0), days.values.single())
    }

    /** A dose tapped twice is one dose: the chart already settles a slot once, and so does this. */
    @Test
    fun aSecondMarkOfTheSameDoseCountsOnce() {
        val day = today.minusDays(1)
        val twice = listOf(marked(course(), day, id = 1), marked(course(), day, id = 2, minute = 495))
        val days = intake(listOf(course()), twice, from = day)[Nutrient.VITAMIN_D]!!

        assertEquals(NutrientDay(25.0, 25.0), days.values.single())
    }

    /** A course taken on waking asks once a day, whenever the night ended. */
    @Test
    fun aWakingCourseAsksForOneDoseADay() {
        val waking = course(contents = "MAGNESIUM=200", schedule = MedSchedule.WAKE, times = "")
        val journal = listOf(marked(waking, today.minusDays(2), id = 1))
        val days = intake(listOf(waking), journal, from = today.minusDays(3))[Nutrient.MAGNESIUM]!!

        assertEquals(3, days.size)
        assertEquals(listOf(0.0, 200.0, 0.0), days.values.map(NutrientDay::marked))
        assertTrue(days.values.all { it.planned == 200.0 })
    }

    /** Today's dose says nothing until its time comes, unless it was marked early. */
    @Test
    fun todaysDoseJoinsOnceItsTimeComes() {
        val morning = intake(listOf(course()), emptyList(), from = today, to = today, nowMinute = 7 * 60)
        assertTrue(morning.isEmpty())

        val later = intake(listOf(course()), emptyList(), from = today, to = today, nowMinute = 9 * 60)
        assertEquals(NutrientDay(0.0, 25.0), later[Nutrient.VITAMIN_D]!![today])

        val early = listOf(marked(course(), today, id = 1, minute = 7 * 60 - 10))
        val ahead = intake(listOf(course()), early, from = today, to = today, nowMinute = 7 * 60)
        assertEquals(NutrientDay(25.0, 25.0), ahead[Nutrient.VITAMIN_D]!![today])
        // And tomorrow, which has asked for nothing yet, is not a day of zeros.
        assertTrue(intake(listOf(course()), emptyList(), from = today.plusDays(1), to = today.plusDays(1)).isEmpty())
    }

    /** Without its course a mark settles no dose — the chart ignores it, and so does the intake. */
    @Test
    fun aMarkWhoseCourseWasDeletedCountsForNothing() {
        val orphan = marked(course(id = 7), today.minusDays(1), id = 1)
        assertTrue(intake(listOf(course(contents = "")), listOf(orphan)).isEmpty())
    }

    /** Two courses holding the same nutrient add up; a course that names nothing adds nothing. */
    @Test
    fun coursesThatHoldTheSameNutrientAddUp() {
        val multi = course(id = 2, contents = "VITAMIN_D=10;ZINC=15")
        val metformin = course(id = 3, contents = "")
        val result = intake(listOf(course(), multi, metformin), emptyList(), from = today.minusDays(1))

        assertEquals(setOf(Nutrient.VITAMIN_D, Nutrient.ZINC), result.keys)
        assertEquals(35.0, result[Nutrient.VITAMIN_D]!!.values.single().planned, 0.0)
        assertEquals(15.0, result[Nutrient.ZINC]!!.values.single().planned, 0.0)
    }

    /**
     * The adherence chart and the intake divide by the same doses, including today's half day: a
     * sheet that scored one set and added up the vitamins of another could contradict itself.
     */
    @Test
    fun theIntakeCountsTheDosesTheChartCounts() {
        val twice = course(times = "480,1200", start = today.minusDays(2))
        val journal = listOf(marked(twice, today.minusDays(1), id = 1))
        val chart = medAdherence(listOf(twice), journal, today, days = 3, nowMinute = 9 * 60, zone = zone)
        val days = supplementIntake(listOf(twice), journal, today.minusDays(2), today, today, 9 * 60, zone)
            .getValue(Nutrient.VITAMIN_D)

        assertEquals(chart.map(MedDay::planned), days.values.map { (it.planned / 25.0).toInt() })
        assertEquals(chart.map(MedDay::taken), days.values.map { (it.marked / 25.0).toInt() })
    }

    @Test
    fun theWindowAveragesOverTheDaysThatAsked() {
        val journal = (2..4).map { back -> marked(course(), today.minusDays(back.toLong()), id = back.toLong()) }
        val intake = intake(listOf(course()), journal, from = today.minusDays(10), to = today.minusDays(1))
        val window = supplementWindow(intake).getValue(Nutrient.VITAMIN_D)

        // The course started five days back: five days asked, three of them marked.
        assertEquals(5, window.askedDays)
        assertEquals(75.0 / 5, window.meanMarked, 1e-9)
        assertEquals(0.6, window.markedShare, 1e-9)
    }

    /** A course nobody described writes no copy of what a dose holds into the mark. */
    @Test
    fun aDoseWithNothingDescribedCarriesNoCopy() {
        val entry = marked(course(contents = ""), today, id = 1)
        assertFalse(entry.measurementPayloadJson!!.contains(MED_CONTENTS_KEY))
        assertEquals(1L to "480", medSlotOf(entry))
    }

    /** A quote out of a hand-edited backup must not end up inside the payload's JSON. */
    @Test
    fun theCopyKeepsOnlyTheCharactersTheFormatUses() {
        val entry = marked(course(contents = "VITAMIN_D=50\",\"x"), today, id = 1)
        assertTrue(entry.measurementPayloadJson!!.endsWith(",\"$MED_CONTENTS_KEY\":\"VITAMIN_D=50\"}"))
    }

    @Test
    fun internationalUnitsAreStoredInTheNutrientsOwnUnit() {
        assertEquals(50.0, canonicalAmount("2000", LabelUnit.VITAMIN_D_IU)!!, 0.0)
        assertEquals(268.0, canonicalAmount("400", LabelUnit.VITAMIN_E_IU_NATURAL)!!, 0.0)
        // Synthetic E is kept at its whole mass, which is what both upper levels count.
        assertEquals(360.0, canonicalAmount("400", LabelUnit.VITAMIN_E_IU_SYNTHETIC)!!, 0.0)
        assertEquals(12.5, canonicalAmount("12.5", null)!!, 0.0)
        // Decimal arithmetic: the double product of 3 × 0.3 is 0.8999999999999999.
        assertEquals(
            "VITAMIN_A=0.9",
            contentsOfLines(listOf(ContentLine(Nutrient.VITAMIN_A, "3", LabelUnit.VITAMIN_A_IU))),
        )
        // Nothing typed, a lone separator and a zero are all "nothing to store".
        assertNull(canonicalAmount("", null))
        assertNull(canonicalAmount(".", LabelUnit.VITAMIN_D_IU))
        assertNull(canonicalAmount("0", null))
    }

    @Test
    fun aPresetAddsItsLinesAndLeavesTypedOnesAlone() {
        assertEquals(
            listOf(ContentLine(Nutrient.VITAMIN_D, "25")),
            emptyList<ContentLine>().withPreset(SupplementPreset.VITAMIN_D3),
        )

        val typed = listOf(ContentLine(Nutrient.VITAMIN_D, "2000", LabelUnit.VITAMIN_D_IU))
        val multi = typed.withPreset(SupplementPreset.MULTIVITAMIN)
        assertEquals(typed.single(), multi.first())
        assertEquals(1, multi.count { it.nutrient == Nutrient.VITAMIN_D })
        // A multivitamin invents nothing: every line it adds waits for the label.
        assertTrue(multi.drop(1).all { it.typed.isEmpty() })
        assertEquals("VITAMIN_D=50", contentsOfLines(multi))
        assertEquals("CREATINE=5", contentsOfLines(emptyList<ContentLine>().withPreset(SupplementPreset.CREATINE)))
    }

    /** An entry a newer build named survives this build reopening and saving the course. */
    @Test
    fun whatThisBuildCannotNameIsWrittenBackAsItCame() {
        val stored = "VITAMIN_D=50;MAGNESIUM=200;VITAMIN_Q=3"
        val lines = contentLinesOf(stored)

        assertEquals(listOf(Nutrient.VITAMIN_D, Nutrient.MAGNESIUM), lines.map(ContentLine::nutrient))
        assertEquals("VITAMIN_Q=3", unnamedContentsOf(stored))
        assertEquals(stored, contentsOfLines(lines, unnamedContentsOf(stored)))
        assertEquals("", unnamedContentsOf("junk;VITAMIN_D=50"))
    }

    /** A figure typed in IU has to come back as IU after a rotation, not be reread as micrograms. */
    @Test
    fun theFormLinesSurviveARotation() {
        val lines = listOf(
            ContentLine(Nutrient.VITAMIN_D, "2000", LabelUnit.VITAMIN_D_IU),
            ContentLine(Nutrient.IRON, ""),
            ContentLine(Nutrient.MAGNESIUM, "12.5"),
        )
        assertEquals(lines, restoredContentLines(savedContentLines(lines)))
        assertTrue(restoredContentLines(savedContentLines(emptyList())).isEmpty())
    }

    /** Tapping a unit chip keeps the amount: 25 µg of D read as IU is 1 000 IU, not 25. */
    @Test
    fun switchingTheUnitRewritesTheFigureAndKeepsTheAmount() {
        val preset = ContentLine(Nutrient.VITAMIN_D, "25")
        val inIu = preset.inUnit(LabelUnit.VITAMIN_D_IU)
        assertEquals("1000", inIu.typed)
        assertEquals(25.0, inIu.amount!!, 0.0)
        val back = inIu.inUnit(null)
        assertEquals("25", back.typed)
        assertEquals(25.0, back.amount!!, 0.0)
        // An empty line only changes its unit.
        assertEquals(
            ContentLine(Nutrient.VITAMIN_D, "", LabelUnit.VITAMIN_D_IU),
            ContentLine(Nutrient.VITAMIN_D).inUnit(LabelUnit.VITAMIN_D_IU),
        )
        // A stored figure is never finer than the line can hold again.
        assertEquals(8.27, canonicalAmount("12.34", LabelUnit.VITAMIN_E_IU_NATURAL)!!, 0.0)
    }
}
