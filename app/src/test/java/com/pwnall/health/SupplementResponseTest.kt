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
 * A course read beside the marker its label can move: which results are its before and after, what
 * state that leaves it in, and what else differed between the two.
 *
 * The scenario the block was written for runs through several cases here: panels on 2024-03-12 and
 * 2026-06-20, vitamin D3 from 2025-11-01, iron from 2026-02-01 to 2026-05-01, and a report made on
 * 2026-09-24 whose six months start after the first panel.
 */
class SupplementResponseTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val zone: ZoneId = ZoneOffset.UTC
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-05-10")

    private val spring2024: LocalDate = LocalDate.of(2024, 3, 12)
    private val summer2026: LocalDate = LocalDate.of(2026, 6, 20)
    private val d3Start: LocalDate = LocalDate.of(2025, 11, 1)

    private fun panel(day: LocalDate, values: Map<LabMarker, Double>, id: Long, method: String = "") = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(values, fasting = true, method = method),
    )

    private fun course(
        id: Long = 1,
        contents: String = "VITAMIN_D=50",
        start: LocalDate = d3Start,
        end: LocalDate? = null,
        times: String = "480",
        name: String = "Vitamin D3",
    ) = Medication(
        id = id,
        name = name,
        dose = "1 capsule",
        times = times,
        startDay = start.toString(),
        endDay = end?.toString(),
        contents = contents,
        createdAt = 0,
    )

    /** The row the course page writes when a dose is ticked. */
    private fun marked(medication: Medication, day: LocalDate, id: Long): JournalEntry {
        val dose = plannedDoses(listOf(medication), emptyList(), day).first()
        return medTakenEntry(dose, day, day.atTime(8, 10).toInstant(ZoneOffset.UTC).toEpochMilli()).copy(id = id)
    }

    private fun responses(
        course: Medication,
        journal: List<JournalEntry>,
        courses: List<Medication> = listOf(course),
        periodFrom: LocalDate? = today.minusMonths(6),
        until: LocalDate = today,
    ): List<SupplementResponse> = supplementResponses(
        course,
        ResponseRecord(courses, labHistory(journal, man), journal, until, zone = zone),
        periodFrom,
    )

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun twoVitaminDPanels() = listOf(
        panel(spring2024, mapOf(LabMarker.VITAMIN_D to 38.0), id = 1),
        panel(summer2026, mapOf(LabMarker.VITAMIN_D to 82.0), id = 2),
    )

    /**
     * The scenario above: the earlier panel is two years old and outside the report's six months, and
     * is still the one the course is read against. What else differed is said — the age of the
     * earlier result, that it is outside the period, and the months — and nothing says which way.
     */
    @Test
    fun aCourseBetweenA2024AndA2026PanelIsComparedAndSaysWhatElseDiffered() {
        val response = responses(course(), twoVitaminDPanels()).single()

        assertEquals(SupplementPairing.VITAMIN_D, response.pairing)
        assertEquals(ResponseState.COMPARED, response.state)
        assertEquals(spring2024.toString(), response.before?.day)
        assertEquals(summer2026.toString(), response.after?.day)
        assertEquals(231, response.daysIntoCourse)
        assertTrue(ResponseFlag.BEFORE_OUTSIDE_PERIOD in response.flags)
        assertTrue(ResponseFlag.BEFORE_OLDER_THAN_A_YEAR in response.flags)
        assertTrue(ResponseFlag.SEASONS_DIFFER in response.flags)
        assertFalse(ResponseFlag.COURSE_ENDED_BEFORE_DRAW in response.flags)

        val lines = response.responseLines(man.sex, imperial = false)
        assertEquals(
            res(
                R.string.supplement_sentence,
                res(
                    R.string.supplement_compared,
                    "38",
                    res(R.string.report_value_unit, "82", res(R.string.labs_unit_nmol)),
                    ReportText.Day(spring2024),
                    ReportText.Day(summer2026),
                    ReportText.Quantity(R.plurals.supplement_days_since_start, 231),
                ),
            ),
            lines.first(),
        )
        assertTrue(
            lines.contains(
                res(R.string.supplement_flag_seasons, ReportText.Day(spring2024), ReportText.Day(summer2026)),
            ),
        )
        assertTrue(lines.contains(res(R.string.supplement_flag_before_period)))
    }

    /** Two samples in the same quarter of the year are not flagged, whichever hemisphere it is. */
    @Test
    fun samplesInOneQuarterOfTheYearAreNotFlaggedForTheSeason() {
        val journal = listOf(
            panel(LocalDate.of(2025, 12, 5), mapOf(LabMarker.VITAMIN_D to 40.0), id = 1),
            panel(LocalDate.of(2026, 2, 20), mapOf(LabMarker.VITAMIN_D to 70.0), id = 2),
        )
        val response = responses(course(start = LocalDate.of(2025, 12, 10)), journal).single()

        assertEquals(ResponseState.COMPARED, response.state)
        assertFalse(ResponseFlag.SEASONS_DIFFER in response.flags)
    }

    /** Vitamin D is read from 56 days on a course: day 55 is too early, day 56 is the course's. */
    @Test
    fun day55IsTooEarlyAndDay56IsCompared() {
        val start = LocalDate.of(2026, 5, 1)
        fun stateAt(days: Long): SupplementResponse {
            val journal = listOf(
                panel(start.minusDays(10), mapOf(LabMarker.VITAMIN_D to 40.0), id = 1),
                panel(start.plusDays(days), mapOf(LabMarker.VITAMIN_D to 60.0), id = 2),
            )
            return responses(course(start = start), journal).single()
        }

        val early = stateAt(55)
        assertEquals(ResponseState.TOO_EARLY, early.state)
        assertEquals(55, early.daysIntoCourse)
        // The line under it dates the wait from the course's own start.
        assertTrue(
            early.responseLines(man.sex, false).contains(
                res(
                    R.string.supplement_steady_vitamin_d,
                    ReportText.Day(start.plusDays(56)),
                    ReportText.Day(start.plusDays(84)),
                ),
            ),
        )
        assertEquals(ResponseState.COMPARED, stateAt(56).state)
    }

    /**
     * No result since the course began says so, and when a result on a steady dose would reflect it —
     * information, not an instruction: no line asks for a test.
     */
    @Test
    fun noResultSinceTheStartIsWaiting() {
        val start = LocalDate.of(2026, 8, 1)
        val journal = listOf(panel(LocalDate.of(2026, 3, 1), mapOf(LabMarker.VITAMIN_D to 45.0), id = 1))
        val response = responses(course(start = start), journal).single()

        assertEquals(ResponseState.WAITING, response.state)
        assertNull(response.after)
        assertEquals(
            listOf(
                res(R.string.supplement_waiting, res(LabMarker.VITAMIN_D.titleRes), ReportText.Day(start)),
                res(
                    R.string.supplement_steady_vitamin_d,
                    ReportText.Day(start.plusDays(56)),
                    ReportText.Day(start.plusDays(84)),
                ),
            ),
            response.responseLines(man.sex, false),
        )
    }

    /** A course that ended before its informative day never reached a steady dose; the wait is not dated. */
    @Test
    fun aCourseEndedBeforeItsInformativeDayIsWaitingWithoutADate() {
        val start = LocalDate.of(2026, 7, 1)
        val response = responses(course(start = start, end = start.plusDays(20)), emptyList()).single()

        assertEquals(ResponseState.WAITING, response.state)
        assertEquals(1, response.responseLines(man.sex, false).size)
    }

    @Test
    fun aResultWithNothingBeforeTheCourseIsNoBefore() {
        val response = responses(
            course(),
            listOf(panel(summer2026, mapOf(LabMarker.VITAMIN_D to 82.0), id = 2)),
        ).single()

        assertEquals(ResponseState.NO_BEFORE, response.state)
        assertNull(response.before)
        assertTrue(response.responseLines(man.sex, false).contains(res(R.string.supplement_no_before)))
    }

    /**
     * A course nobody ever marked a dose of was asked for doses and none were ticked; that is "not
     * marked", which the course carries, and not a count of nought taken.
     */
    @Test
    fun unmarkedDosesAreNotMarkedRatherThanNought() {
        val response = responses(course(), twoVitaminDPanels()).single()

        assertFalse(response.everMarked)
        assertEquals(0, response.marked?.taken)
        assertTrue((response.marked?.planned ?: 0) > 0)

        val marks = listOf(marked(course(), summer2026.minusDays(1), id = 10), marked(course(), summer2026, id = 11))
        val ticked = responses(course(), twoVitaminDPanels() + marks).single()
        assertTrue(ticked.everMarked)
        assertEquals(2, ticked.marked?.taken)
        assertEquals(d3Start, ticked.countedFrom)
        assertEquals(summer2026, ticked.countedTo)
    }

    /** A course nobody described by what it holds is not read from its name, and gets no amount. */
    @Test
    fun aCourseWithoutContentsHasNoPairAndNoAmount() {
        assertTrue(responses(course(contents = ""), twoVitaminDPanels()).isEmpty())

        // A course whose label is written but whose times are not has no amount a day to state.
        val noTimes = responses(course(times = ""), twoVitaminDPanels()).single()
        assertNull(noTimes.perDay)
        assertTrue(ResponseFlag.AMOUNT_NOT_COMPUTED in noTimes.flags)
        assertTrue(noTimes.responseLines(man.sex, false).contains(res(R.string.supplement_amount_not_computed)))
    }

    /** A D3 capsule and a multivitamin taken twice a day over the same months add up. */
    @Test
    fun overlappingVitaminDSourcesAddUp() {
        val d3 = course(contents = "VITAMIN_D=25")
        val multi = course(
            id = 2,
            name = "Multivitamin",
            contents = "VITAMIN_D=10;IRON=14",
            start = d3Start.plusMonths(2),
            times = "480,1200",
        )
        val response = responses(d3, twoVitaminDPanels(), courses = listOf(d3, multi)).single()

        assertEquals(25.0, response.perDay!!, 1e-9)
        assertEquals(listOf(multi), response.overlapping)
        assertEquals(45.0, response.combinedPerDay!!, 1e-9)
    }

    /**
     * Iron from February to May, ferritin in June: the result is the course's, and the fifty days
     * between its end and the sample are said.
     */
    @Test
    fun aCourseThatEndedBeforeTheResultIsFlagged() {
        val iron = course(contents = "IRON=65", start = LocalDate.of(2026, 2, 1), end = LocalDate.of(2026, 5, 1))
        val journal = listOf(
            panel(LocalDate.of(2026, 1, 15), mapOf(LabMarker.FERRITIN to 22.0), id = 1),
            panel(summer2026, mapOf(LabMarker.FERRITIN to 41.0), id = 2),
        )
        val ferritin = responses(iron, journal).single { it.pairing == SupplementPairing.IRON_FERRITIN }

        assertEquals(ResponseState.COMPARED, ferritin.state)
        assertTrue(ResponseFlag.COURSE_ENDED_BEFORE_DRAW in ferritin.flags)
        assertEquals(50, ferritin.endedDaysBefore)
        assertTrue(
            ferritin.responseLines(man.sex, false)
                .contains(res(R.string.supplement_flag_ended, ReportText.Quantity(R.plurals.count_days, 50))),
        )
    }

    /** A raised CRP on the ferritin's own sheet is said beside it; on another sheet it is not. */
    @Test
    fun aRaisedCrpOnTheSameSheetFlagsTheFerritin() {
        val iron = course(contents = "IRON=65", start = LocalDate.of(2026, 2, 1))
        val before = panel(LocalDate.of(2026, 1, 15), mapOf(LabMarker.FERRITIN to 22.0), id = 1)
        val inflamed = panel(summer2026, mapOf(LabMarker.FERRITIN to 90.0, LabMarker.CRP to 14.0), id = 2)
        fun ferritin(journal: List<JournalEntry>) =
            responses(iron, journal).single { it.pairing == SupplementPairing.IRON_FERRITIN }

        assertTrue(ResponseFlag.INFLAMMATION_ON_SHEET in ferritin(listOf(before, inflamed)).flags)

        val calm = panel(summer2026, mapOf(LabMarker.FERRITIN to 90.0, LabMarker.CRP to 1.0), id = 2)
        assertFalse(ResponseFlag.INFLAMMATION_ON_SHEET in ferritin(listOf(before, calm)).flags)
    }

    /**
     * Transferrin saturation and serum folate follow the last doses, so they are shown beside the
     * course and never compared — and a dose marked on the day of the sample is said.
     */
    @Test
    fun aFolateDrawnOnTheDayOfAMarkedDoseIsShownBesideTheCourse() {
        val folic = course(contents = "FOLIC_ACID=400", start = LocalDate.of(2026, 4, 1))
        val drawDay = LocalDate.of(2026, 6, 2)
        val journal = listOf(
            panel(LocalDate.of(2026, 3, 1), mapOf(LabMarker.FOLATE to 12.0), id = 1),
            panel(drawDay, mapOf(LabMarker.FOLATE to 30.0), id = 2),
            marked(folic, drawDay, id = 3),
        )
        val response = responses(folic, journal).single()

        assertEquals(ResponseState.CONTEXT_ONLY, response.state)
        assertTrue(ResponseFlag.DOSE_ON_DRAW_DAY in response.flags)
        assertTrue(response.responseLines(man.sex, false).contains(res(R.string.supplement_context_folate)))
        // Beside the course, never set against the earlier result.
        val first = response.responseLines(man.sex, false).first() as ReportText.Res
        assertTrue(first.args.none { it is ReportText.Res && it.id == R.string.supplement_compared })

        val unmarkedDay = responses(folic, journal.dropLast(1)).single()
        assertFalse(ResponseFlag.DOSE_ON_DRAW_DAY in unmarkedDay.flags)
    }

    /** A context pair with no result at all has nothing to say, and is left out. */
    @Test
    fun aContextPairWithNoResultIsLeftOut() {
        val b12 = course(contents = "VITAMIN_B12=1000")

        assertTrue(responses(b12, twoVitaminDPanels()).isEmpty())
    }

    /** One sheet naming its laboratory and the other naming none are not taken for the same method. */
    @Test
    fun resultsThatDoNotNameTheSameLaboratoryAreFlagged() {
        val named = listOf(
            panel(spring2024, mapOf(LabMarker.VITAMIN_D to 38.0), id = 1, method = "Invitro"),
            panel(summer2026, mapOf(LabMarker.VITAMIN_D to 82.0), id = 2),
        )
        assertTrue(ResponseFlag.DIFFERENT_METHOD in responses(course(), named).single().flags)

        val same = listOf(
            panel(spring2024, mapOf(LabMarker.VITAMIN_D to 38.0), id = 1, method = "Invitro"),
            panel(summer2026, mapOf(LabMarker.VITAMIN_D to 82.0), id = 2, method = "invitro "),
        )
        assertFalse(ResponseFlag.DIFFERENT_METHOD in responses(course(), same).single().flags)
    }

    /** An earlier result drawn while another vitamin D course ran is a dose change, and is said to be. */
    @Test
    fun anEarlierResultOnAnotherCourseOfTheSameNutrientIsFlagged() {
        val older = course(id = 2, name = "Old D", contents = "VITAMIN_D=10", start = LocalDate.of(2023, 1, 1))
        val response = responses(course(), twoVitaminDPanels(), courses = listOf(course(), older)).single()

        assertTrue(ResponseFlag.BEFORE_ON_SAME_NUTRIENT in response.flags)
    }

    /**
     * An amount printed beside a level it is over reads over it, and an amount that is not nought
     * never reads as nought; everything else prints at the app's one decimal.
     */
    @Test
    fun anAmountIsPrintedOnItsOwnSideOfTheLevel() {
        fun shown(amount: Double, above: Double = 0.0) =
            (nutrientAmountText(Nutrient.VITAMIN_D, amount, above) as ReportText.Res).args.first()

        assertEquals("100.04", shown(100.04, above = 100.0))
        assertEquals("100.1", shown(100.06, above = 100.0))
        assertEquals("100.001", shown(100.0000001, above = 100.0))
        assertEquals("125", shown(125.0, above = 100.0))
        assertEquals("0.03", shown(0.03))
        assertEquals("0.001", shown(0.0000004))
        assertEquals("12.3", shown(12.34))
        assertEquals("0", shown(0.0))
    }

    /**
     * The course page asks with no period: nothing is "before the period" there, and a course starting
     * after the day asked about has nothing to say yet.
     */
    @Test
    fun withoutAPeriodNothingIsBeforeIt() {
        val response = responses(course(), twoVitaminDPanels(), periodFrom = null).single()
        assertFalse(ResponseFlag.BEFORE_OUTSIDE_PERIOD in response.flags)

        assertTrue(responses(course(start = today.plusDays(3)), twoVitaminDPanels()).isEmpty())
    }
}
