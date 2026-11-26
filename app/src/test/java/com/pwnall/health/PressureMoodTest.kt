package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

/**
 * The weather card, which reads the air pressure against the mood through the same engine and the
 * same gates as every other measured link in the app.
 *
 * A rule of its own — a Pearson coefficient over a month, a link from ten paired days at 0.3 — would
 * not do: two unrelated series clear that about one month in three, and the card would then print a
 * direction and the coefficient.
 */
class PressureMoodTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 31)

    private fun pressureDay(back: Int, hPa: Double, samples: Long = 24) = BarometerDay(
        day = today.minusDays(back.toLong()).toString(),
        pressureSumHpa = hPa * samples,
        pressureSamples = samples,
    )

    private fun moodDay(back: Int, score: Int) = JournalEntry(
        day = today.minusDays(back.toLong()).toString(),
        occurredAt = back.toLong(),
        kind = KIND_MOOD,
        title = "Mood",
        calories = 0.0,
        moodScore = score,
    )

    private fun series(values: List<Double>) =
        DailySeries(values.mapIndexed { index, value -> today.plusDays(index.toLong()) to value }.toMap())

    /** A fortnight of mood rising with the pressure, day for day: a count, not "Higher pressure, better days". */
    @Test
    fun aFortnightThatMovesTogetherIsACountOfDaysAndNotAFinding() {
        val reading = pressureMoodReading(
            days = (0 until 14).map { pressureDay(it, 995.0 + it) },
            moods = (0 until 14).map { moodDay(it, 1 + it * 4 / 13) },
            today = today,
        )

        assertEquals(14, reading.pairedDays)
        assertNull(reading.contrast)
        assertEquals(PressureMoodLine.NEED_DAYS, pressureMoodLine(reading))
    }

    /**
     * A plain coefficient's fault, measured: a hundred full windows of weather and of moods drawn
     * apart from each other, the weather drifting as weather does, with fixed seeds. A coefficient at
     * 0.3 finds a link in about one month of three; under the engine's gates a direction on more than
     * one window in twenty would be the card telling people what their own record does not hold.
     */
    @Test
    fun weatherAndMoodsDrawnApartAlmostNeverReadAsALink() {
        val windows = 100
        val directions = (1L..windows).count { seed ->
            val random = Random(seed)
            var weather = 0.0
            val days = (0 until INSIGHT_WINDOW_DAYS).map { back ->
                weather = 0.7 * weather + random.nextGaussian() * 6.0
                pressureDay(back, 1013.0 + weather)
            }
            val moods = (0 until INSIGHT_WINDOW_DAYS).map { back -> moodDay(back, 1 + random.nextInt(5)) }
            val reading = pressureMoodReading(days, moods, today)

            assertTrue("window $seed", reading.pairedDays >= INSIGHT_GATES.minPairedDays)
            pressureMoodLine(reading) in setOf(PressureMoodLine.HIGH_BETTER, PressureMoodLine.HIGH_WORSE)
        }

        assertTrue("$directions of $windows unrelated windows read as a link", directions * 20 <= windows)
    }

    /** A mean over a handful of readings is where the phone happened to be, not the day's weather. */
    @Test
    fun aDayWithAHandfulOfReadingsIsNotADayOfWeather() {
        val reading = pressureMoodReading(
            days = (0 until 14).map { pressureDay(it, 1000.0 + it, samples = if (it < 4) 6 else 5) },
            moods = (0 until 14).map { moodDay(it, 3) },
            today = today,
        )

        assertEquals(4, reading.pairedDays)
    }

    /** Only the engine's own window is read, and only the days that carry both. */
    @Test
    fun daysOutsideTheWindowAndDaysWithoutAPairAreNotCounted() {
        val reading = pressureMoodReading(
            days = listOf(pressureDay(0, 1000.0), pressureDay(1, 1004.0), pressureDay(INSIGHT_WINDOW_DAYS, 990.0)),
            moods = listOf(moodDay(0, 4), moodDay(2, 2), moodDay(INSIGHT_WINDOW_DAYS, 5)),
            today = today,
        )

        assertEquals(1, reading.pairedDays)
    }

    /**
     * The line is the engine's verdict and nothing more: a counter under ninety paired days, a
     * direction for a difference whose range leaves zero out, and "no link" for the rest. The three
     * series are the ones the engine's own tests measure.
     */
    @Test
    fun theLineSaysWhatTheEngineFoundAndNoMore() {
        val sawtooth = series((0 until 200).map { (it % 10).toDouble() })
        val follows = (0 until 200).map { if (it % 10 >= 5) 4.0 + (it % 2) else 2.0 + (it % 2) }

        val thin = insightLink(
            series((0 until 40).map { it.toDouble() }),
            series((0 until 40).map { (it % 5 + 1).toDouble() }),
            InsightLag.SAME_DAY,
            windowDays = 120,
            seed = 1L,
        )
        val better = insightLink(sawtooth, series(follows), InsightLag.SAME_DAY, windowDays = 200, seed = 7L)
        val mirrored = series(follows.map { 6.0 - it })
        val worse = insightLink(sawtooth, mirrored, InsightLag.SAME_DAY, windowDays = 200, seed = 7L)
        val unrelated = insightLink(
            series((0 until 200).map { ((it * 37) % 101).toDouble() }),
            series((0 until 200).map { ((it * 53) % 5 + 1).toDouble() }),
            InsightLag.SAME_DAY,
            windowDays = 200,
            seed = 11L,
        )

        assertEquals(PressureMoodLine.NEED_DAYS, pressureMoodLine(thin))
        assertEquals(PressureMoodLine.HIGH_BETTER, pressureMoodLine(better))
        assertEquals(PressureMoodLine.HIGH_WORSE, pressureMoodLine(worse))
        assertEquals(PressureMoodLine.NONE, pressureMoodLine(unrelated))
        // The same difference at a chance the app's comparisons with mood, taken together, do not
        // let through: its range is clear of zero, and judged alone it would read as a direction.
        val alone = better.copy(contrast = better.contrast?.copy(chance = 0.03))
        assertEquals(PressureMoodLine.NONE, pressureMoodLine(alone))
    }
}
