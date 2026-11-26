package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The report end to end: journal rows in, a card's arguments out.
 *
 * [InsightsTest] holds the arithmetic. This holds what the named hypotheses do with it — which day
 * a night is set against, and which figures the sentence is handed — over a record whose answer is
 * known before the engine runs: the window's days go round in threes, a low day, a middling one and
 * a high one, and the mood goes round with them at 3, 4 and 5.
 */
class InsightFindingsTest {
    private val today: LocalDate = LocalDate.of(2026, 6, 30)
    private val from: LocalDate = today.minusDays(INSIGHT_WINDOW_DAYS - 1L)
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1990-06-15")

    /** One row for each day of the window, told which turn of the three the day is. */
    private fun <T> eachDay(row: (day: LocalDate, turn: Int) -> T): List<T> =
        (0 until INSIGHT_WINDOW_DAYS).map { row(from.plusDays(it.toLong()), it % 3) }

    private fun moods() = eachDay { day, turn ->
        JournalEntry(
            day = day.toString(),
            occurredAt = 0L,
            kind = KIND_MOOD,
            title = "Mood",
            calories = 0.0,
            moodScore = 3 + turn,
        )
    }

    /** A night as every writer of one files it: under the day the sleeper woke on. */
    private fun night(wokeOn: LocalDate, minutes: Int): JournalEntry {
        val end = wokeOn.atTime(7, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return JournalEntry(
            day = wokeOn.toString(),
            occurredAt = end,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = end - minutes * 60_000L,
            sleepEndAt = end,
        )
    }

    /**
     * The two levels and the difference between them, as the card's sentence prints them in English:
     * handed over as figures ([Decimal]), so that each language writes its own decimal mark.
     */
    private fun Advice.levels(): List<String> = listOf(args[1], args[3], args[4]).map { (it as Decimal).value.pretty() }

    /**
     * What is measured is the rating with the usual 4 taken out of it — minus one, nought and one —
     * and the card must not print those: "you rated your mood 1 out of 5 … you walked less, -1".
     */
    @Test
    fun theCardPrintsTheRatingsThatWereGiven() {
        val steps = eachDay { day, turn -> DaySteps(day.toString(), 4_000L + 4_000L * turn) }

        val card = insightReport(steps, moods(), profile, today).findings.single()

        assertEquals("insight-steps-mood-male", card.id)
        assertEquals(Counted(40, R.plurals.count_days), card.args[0])
        assertEquals(listOf<Any>("5", "3", "2"), card.levels())
    }

    /**
     * Six hours, seven and a half and nine, and the mood of the day each night ended on at 3, 4 and
     * 5. The night before a day's mood is that day's own row; read a day back, the long nights would
     * be set against the days after the short ones and the link would come out the other way round.
     */
    @Test
    fun aNightIsSetAgainstTheDayItEndedOn() {
        val nights = eachDay { day, turn -> night(day, 360 + 90 * turn) }

        val card = insightReport(emptyList(), moods() + nights, profile, today).findings.single()

        assertEquals("insight-sleep-mood-male", card.id)
        assertEquals(listOf<Any>("5", "3", "2"), card.levels())
    }

    /**
     * Drinking is a yes or a no, so it is split by the value and not cut into thirds: three heavy
     * evenings, each before a day rated 3, are three days after drinking, under the fifteen-day floor,
     * and no card. Cut into thirds they would be forty "days after drinking", thirty-seven of them sober.
     * The same pattern kept up every third evening is thirty-nine such days and the card it earns.
     */
    @Test
    fun threeEveningsOfDrinkingAreTooFewToSpeakOf() {
        fun evenings(indices: List<Int>) = indices.map { index ->
            JournalEntry(
                day = from.plusDays(index.toLong()).toString(),
                occurredAt = 0L,
                kind = KIND_ALCOHOL,
                title = "Wine",
                calories = 0.0,
                servingG = 40.0,
            )
        }
        // Each a day before a turn that rates 3.
        val beforeLowDays = (2 until INSIGHT_WINDOW_DAYS step 3).toList()

        val three = insightReport(emptyList(), moods() + evenings(beforeLowDays.take(3)), profile, today)
        assertTrue(three.findings.isEmpty())

        val card = insightReport(emptyList(), moods() + evenings(beforeLowDays), profile, today).findings.single()
        assertEquals("insight-alcohol-mood-male", card.id)
        assertEquals(Counted(39, R.plurals.count_days), card.args[0])
    }

    /**
     * The age gate decides what is asked, not only which screens exist: under sixteen intimacy is
     * not set against mood, under eighteen drinking is not, and the count of comparisons the screen
     * says aloud is the count that was made. A restored backup can bring the rows at any age.
     *
     * The count has the weather card's comparison in it, air pressure against mood: it is measured
     * on another page and is the sixth, which the screen owns up to as well.
     */
    @Test
    fun whatTheAgeGateRemovesIsNotAskedAbout() {
        fun tested(birthDate: String) =
            insightReport(emptyList(), emptyList(), profile.copy(birthDate = birthDate), today).tested

        assertTrue(insightReport(emptyList(), emptyList(), profile, today).findings.isEmpty())
        assertEquals(6, tested("1990-06-15"))
        assertEquals(5, tested("2009-06-30"))
        assertEquals(4, tested("2013-06-30"))
    }

    /**
     * And that comparison answers to the correction the list is filtered by. Six comparisons at a
     * tenth: the first of them stands at one in sixty and no further, whatever the others came to.
     */
    @Test
    fun aComparisonMadeElsewhereIsHeldToTheListsOwnLine() {
        assertTrue(standsAmongAllComparisons(0.016))
        assertFalse(standsAmongAllComparisons(0.017))
        assertFalse("one in twenty, the line it used to be judged by alone", standsAmongAllComparisons(0.05))
    }

    /**
     * The reverse lag loses a day of the window, so it is the one that falls under ninety paired
     * days first — and a reverse that was never measured does not count as one that found nothing,
     * which would put "the same order appeared" on the card unchecked.
     */
    @Test
    fun aReverseThatWasNeverMeasuredClearsNoDirection() {
        fun reverse(contrast: InsightContrast?) = InsightLink(
            lag = InsightLag.OUTCOME_YESTERDAY,
            tau = null,
            contrast = contrast,
            pairedDays = 89,
            coverage = 0.74,
            effectiveN = 60,
            confidence = if (contrast == null) InsightConfidence.NOT_ENOUGH else InsightConfidence.EARLY,
        )
        fun range(lo: Double, hi: Double) = InsightContrast(
            highDays = 30,
            lowDays = 30,
            highMean = 4.0,
            lowMean = 3.5,
            lo = lo,
            hi = hi,
            highShown = 4.0,
            lowShown = 3.5,
            chance = 0.5,
        )

        assertFalse(reverseRuledOut(reverse(null)))
        assertTrue(reverseRuledOut(reverse(range(-0.2, 0.4))))
        assertFalse(reverseRuledOut(reverse(range(0.1, 0.9))))
    }
}
