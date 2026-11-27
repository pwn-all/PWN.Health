package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the pattern cards choose to say. The statistics themselves are PainStatsTest's, WorkStatsTest's
 * and MorningStatsTest's; here: which verdict line a comparison gets — "on the days after" only when
 * its reverse was weighed and found quiet — the windows each card reads, the cards shown or left out,
 * and the research line a profile is given.
 */
class PatternCardsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private val today: LocalDate = LocalDate.of(2026, 6, 30)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun share(verdict: ContrastVerdict) =
        DayShareContrast(DayShare(3, 12), DayShare(4, 30), null, CONTRAST_MIN_DAYS, verdict)

    private fun mean(verdict: ContrastVerdict) =
        DayMeanContrast(DayMean(11, 3.0), DayMean(25, 3.6), null, CONTRAST_MIN_DAYS, verdict)

    private fun kept(day: LocalDate) =
        JournalEntry(day = day.toString(), occurredAt = at(day, 12), kind = "WATER", title = "Water", calories = 0.0)

    private fun pain(day: LocalDate, vararg areas: RegionSide) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(
            PainReport(regions = areas.toList(), types = listOf(PainType.ACHING)),
        ),
    )

    private fun session(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 18),
        kind = "ACTIVITY",
        title = "Leg curl",
        calories = 0.0,
        activityCode = "LEG_CURL",
    )

    private fun work(day: LocalDate) = workEntry(listOf(WorkSegment(at(day, 9), at(day, 17))), "Work", zone)!!

    private val thigh = RegionSide(PainRegion.THIGH_FRONT, BodySide.LEFT)
    private val lowerBack = RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE)
    private val head = RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE)

    @Test
    fun aComparisonOnTheSameDaysIsWordedByItsVerdictAlone() {
        assertEquals(ContrastLine.NOT_ENOUGH, contrastLine(share(ContrastVerdict.NOT_ENOUGH_DAYS)))
        assertEquals(ContrastLine.NO_CLEAR_DIFFERENCE, contrastLine(share(ContrastVerdict.NO_CLEAR_DIFFERENCE)))
        assertEquals(ContrastLine.NOTABLE, contrastLine(share(ContrastVerdict.NOTABLE)))
        assertEquals(ContrastLine.NOTABLE, contrastLine(mean(ContrastVerdict.NOTABLE)))
    }

    @Test
    fun onTheDaysAfterNeedsTheReverseWeighedAndQuiet() {
        val notable = share(ContrastVerdict.NOTABLE)
        assertEquals(
            ContrastLine.NOTABLE_DAYS_AFTER,
            contrastLine(LaggedContrast(notable, share(ContrastVerdict.NO_CLEAR_DIFFERENCE))),
        )
        // The reverse showed it too: the two went together, in no order the record can show.
        assertEquals(
            ContrastLine.NOTABLE_NO_ORDER,
            contrastLine(LaggedContrast(notable, share(ContrastVerdict.NOTABLE))),
        )
        // A reverse never weighed cannot clear a direction, and is not said to have shown it either.
        assertEquals(
            ContrastLine.NOTABLE_REVERSE_UNCHECKED,
            contrastLine(LaggedContrast(notable, share(ContrastVerdict.NOT_ENOUGH_DAYS))),
        )
        // Without a forward finding the reverse does not matter.
        assertEquals(
            ContrastLine.NO_CLEAR_DIFFERENCE,
            contrastLine(LaggedContrast(share(ContrastVerdict.NO_CLEAR_DIFFERENCE), share(ContrastVerdict.NOTABLE))),
        )
        assertEquals(
            ContrastLine.NOT_ENOUGH,
            contrastLine(LaggedContrast(mean(ContrastVerdict.NOT_ENOUGH_DAYS), mean(ContrastVerdict.NOTABLE))),
        )
    }

    @Test
    fun numbersAreWrittenAsTheLocaleWritesThem() {
        assertEquals("4", oneDecimal(4.0, Locale.UK))
        assertEquals("4.5", oneDecimal(4.5, Locale.UK))
        assertEquals("4,5", oneDecimal(4.5, Locale.GERMANY))
        assertEquals("3,912", wholeNumber(3_911.6, Locale.UK))
    }

    @Test
    fun theFloorLineCountsTheDaysEachGroupHeld() {
        assertEquals(12 to 30, share(ContrastVerdict.NOT_ENOUGH_DAYS).groupSizes)
        assertEquals(11 to 25, mean(ContrastVerdict.NOT_ENOUGH_DAYS).groupSizes)
        assertEquals(15, CARD_POINTS)
    }

    @Test
    fun eveningsWithADrinkAreNotableWhenEitherTheShareOrTheGramsAre() {
        fun comparisons(evenings: DayShareContrast, grams: DayMeanContrast): WorkDayComparisons {
            val none = DayMean(0, null)
            return WorkDayComparisons(
                longDays = 12,
                otherDays = 30,
                daysOffKnown = 8,
                moodSameEvening = mean(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                moodNextDay = LaggedContrast(
                    mean(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                    mean(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                ),
                woundUp = share(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                sleepNextNight = LaggedContrast(
                    mean(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                    mean(ContrastVerdict.NO_CLEAR_DIFFERENCE),
                ),
                drinkEvenings = evenings,
                drinkGrams = grams,
                daysOff = WorkDaysOff(none, none, DayShare(0, 0), none, DayShare(0, 0), none),
            )
        }
        assertEquals(
            ContrastLine.NOTABLE,
            drinksLine(comparisons(share(ContrastVerdict.NO_CLEAR_DIFFERENCE), mean(ContrastVerdict.NOTABLE))),
        )
        assertEquals(
            ContrastLine.NO_CLEAR_DIFFERENCE,
            drinksLine(
                comparisons(share(ContrastVerdict.NO_CLEAR_DIFFERENCE), mean(ContrastVerdict.NO_CLEAR_DIFFERENCE)),
            ),
        )
        assertEquals(
            ContrastLine.NOT_ENOUGH,
            drinksLine(comparisons(share(ContrastVerdict.NOT_ENOUGH_DAYS), mean(ContrastVerdict.NOT_ENOUGH_DAYS))),
        )
    }

    @Test
    fun whereAndHowOftenCountsTheLastFourWeeksAndOnlyTheGroupsThatHurt() {
        val journal = (0L until 40L).map { kept(today.minusDays(it)) } +
            pain(today.minusDays(3), head) +
            // Before the four weeks: not in the summary, and no group of its own.
            pain(today.minusDays(PAIN_WHERE_DAYS), lowerBack)
        val insights = painInsights(journal, emptyList(), today, WORK_LONG_DAY_DEFAULT_MINUTES, zone)

        assertEquals(PAIN_WHERE_DAYS.toInt(), insights.answeredDays)
        assertEquals(listOf(PainGroup.HEAD), insights.summaries.map { it.group })
        assertEquals(1, insights.summaries.single().painDays)
        assertTrue(insights.enoughAnswered)
        assertFalse(insights.thin)
    }

    @Test
    fun aFewAnsweredDaysAreTooFewAndAThinMonthIsMarked() {
        fun answered(days: Long) =
            painInsights((0L until days).map { kept(today.minusDays(it)) }, emptyList(), today, 600, zone)

        assertFalse(answered(PAIN_WHERE_MIN_ANSWERED - 1L).enoughAnswered)
        assertTrue(answered(PAIN_WHERE_MIN_ANSWERED.toLong()).enoughAnswered)
        // Six in ten of twenty-eight is 16.8: sixteen days is thin, seventeen is not.
        assertTrue(answered(16).thin)
        assertFalse(answered(17).thin)
    }

    @Test
    fun painAfterAWorkoutIsListedOnceHoweverManyGroupsItsEntryMarked() {
        val sessionDay = today.minusDays(10)
        val journal = (0L until 20L).map { kept(today.minusDays(it)) } +
            session(sessionDay) +
            // The thigh is over the legs, the lower back over the back: two groups, one entry.
            pain(sessionDay.plusDays(1), thigh, lowerBack)
        val insights = painInsights(journal, emptyList(), today, 600, zone)

        val listed = insights.afterWorkouts.single()
        assertEquals(sessionDay.plusDays(1), listed.episode.first)
        assertNotNull(listed.episode.session)
        // Listed under the group the leg session trained, not the first group in the list.
        assertEquals(PainGroup.HIPS_LEGS, listed.group)
        assertEquals(listOf(Muscle.LEGS), insights.trained.map { it.muscle })
    }

    /**
     * One entry marks the thigh and the lower back the day after a leg session. The thigh is gone the
     * next day; the lower back goes on for ten. The card judges the soreness by the thigh — the area
     * the session trained — and not by how long the lower back was recorded.
     */
    @Test
    fun painAfterAWorkoutIsJudgedByTheAreaTheWorkoutTrained() {
        val sessionDay = today.minusDays(20)
        val journal = (0L until 30L).map { kept(today.minusDays(it)) } +
            session(sessionDay) +
            pain(sessionDay.plusDays(1), thigh, lowerBack) +
            (2L..10L).map { pain(sessionDay.plusDays(it), lowerBack) }
        val listed = painInsights(journal, emptyList(), today, 600, zone).afterWorkouts.single()

        assertEquals(PainGroup.HIPS_LEGS, listed.group)
        assertTrue(listed.trained)
        assertEquals(sessionDay.plusDays(1), listed.episode.last)
        assertTrue(listed.episode.fitsSoreness)
    }

    @Test
    fun onlyTheLatestPainsAfterAWorkoutAreListedNewestFirst() {
        val journal = (0L until 80L).map { kept(today.minusDays(it)) } +
            (1..6).flatMap { week ->
                val day = today.minusDays(week * 10L)
                listOf(session(day), pain(day.plusDays(1), thigh))
            }
        val listed = painInsights(journal, emptyList(), today, 600, zone).afterWorkouts

        assertEquals(PAIN_RECENT_AFTER_TRAINING, listed.size)
        assertEquals(listed.sortedByDescending { it.episode.first }, listed)
        assertEquals(today.minusDays(9), listed.first().episode.first)
    }

    @Test
    fun theFixedQuestionsAreAskedOfTheirOwnAreasOnly() {
        val insights = painInsights(listOf(kept(today)), emptyList(), today, 600, zone)

        assertEquals(listOf(PainGroup.LOWER_BACK, PainGroup.NECK_SHOULDERS), insights.movement.map { it.first })
        assertEquals(listOf(PainGroup.HEAD, PainGroup.NECK_SHOULDERS), insights.tension.map { it.first })
        assertTrue(insights.trained.isEmpty())
    }

    @Test
    fun workDaysAndPainIsShownOnlyOnceTheTimerWasUsed() {
        val without = painInsights(listOf(kept(today)), emptyList(), today, 600, zone)
        val with = painInsights(listOf(kept(today), work(today.minusDays(2))), emptyList(), today, 600, zone)

        assertNull(without.work)
        assertEquals(WORK_PAIN_GROUPS, with.work?.map { it.first })
    }

    @Test
    fun theWorkCardsReadThirteenWeeksToYesterdayAndDrawTheWeeksOldestFirst() {
        val journal = (0L..WORK_INSIGHT_DAYS + 1).map { work(today.minusDays(it)) }
        val insights = workInsights(journal, today, zone, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 600)
        val weeks = insights.summary.weeks

        // Today is still going and day 92 back is outside; days 1..91 are in.
        assertEquals(WORK_INSIGHT_DAYS.toInt(), insights.summary.workDays)
        assertFalse(today in insights.summary.dailyMinutes)
        // The chart draws the weeks as they come: oldest on the left.
        assertEquals(weeks.map { it.monday }.sorted(), weeks.map { it.monday })
        assertTrue(weeks.first().monday < weeks.last().monday)
        assertTrue(weeks.size >= 12)
        assertTrue(insights.enoughWeeks)
    }

    /** On a Sunday the week in progress is not a counted week yet: its Sunday is not over. */
    @Test
    fun aWeekIsCountedOnlyOnceItsSundayIsOver() {
        val sunday = LocalDate.of(2026, 6, 28)
        val journal = (0L..WORK_INSIGHT_DAYS + 1).map { work(sunday.minusDays(it)) }
        fun lastWeek(on: LocalDate) = workInsights(journal, on, zone, emptySet(), 600).summary.weeks.last().monday

        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 6, 15), lastWeek(sunday))
        assertEquals(LocalDate.of(2026, 6, 22), lastWeek(sunday.plusDays(1)))
    }

    @Test
    fun fewerThanFourCountedWeeksAreNotEnough() {
        val journal = (0L until 20L).map { work(today.minusDays(it)) }
        val insights = workInsights(journal, today, zone, emptySet(), 600)

        assertTrue(insights.summary.weeks.size < WORK_MIN_COUNTED_WEEKS)
        assertFalse(insights.enoughWeeks)
    }

    @Test
    fun soloAndPartneredCarriesTheResearchAboutTheProfilesOwnSex() {
        val male = R.string.insight_solo_research_male to R.string.insight_solo_source_male
        val female = R.string.insight_solo_research_female to R.string.insight_solo_source_female

        assertEquals(listOf(male), soloResearch("MALE"))
        assertEquals(listOf(female), soloResearch("FEMALE"))
        assertEquals(listOf(male, female), soloResearch(null))
    }
}
