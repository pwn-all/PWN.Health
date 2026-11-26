package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edges of the year's medal ladder and of the widget row that survives in preferences: a total
 * that reaches a rung exactly, one step short of it, and a row written by a version of the app that
 * does not exist yet.
 */
class AggregationEdgeCasesTest {
    private fun days(vararg pairs: Pair<String, Long>) = pairs.map { DaySteps(it.first, it.second) }

    private fun rung(all: List<Achievement>, threshold: Long) = all.single { it.threshold == threshold }

    // -----------------------------------------------------------------------------------------
    // The medal ladder
    // -----------------------------------------------------------------------------------------

    /** A rung is reached when the total reaches it: 100 000 steps is 100 000 steps. */
    @Test
    fun aMedalIsWonTheDayTheTotalReachesItExactly() {
        val all = stepAchievements(days("2026-01-01" to 40_000L, "2026-01-02" to 60_000L), 2026)

        assertTrue(rung(all, 100_000L).earned)
        assertEquals(1f, rung(all, 100_000L).progress, 1e-6f)
        assertEquals("2026-01-02", rung(all, 100_000L).earnedOnDay)
    }

    /**
     * A full ring beside a locked badge is the profile congratulating somebody a step early. The
     * ring may only close when the medal is actually won, and this is the single input where the
     * difference is visible.
     */
    @Test
    fun oneStepShortIsALockedBadgeAndARingThatIsNotYetFull() {
        val medal = rung(stepAchievements(days("2026-03-01" to 99_999L), 2026), 100_000L)

        assertFalse(medal.earned)
        assertNull(medal.earnedOnDay)
        assertTrue("was ${medal.progress}", medal.progress < 1f)
        assertEquals(0.99999f, medal.progress, 1e-5f)
    }

    /**
     * A day off, a hike, a restored backup landing in one row: one day can cross three rungs. A
     * loop that stopped at the first crossing would leave the other two saying "earned" with no
     * day to name, and the profile would show a medal nobody can date.
     */
    @Test
    fun oneDayCanCrossThreeMedalsAndAllThreeAreDatedToIt() {
        val all = stepAchievements(days("2026-03-05" to 1_100_000L), 2026)

        listOf(100_000L, 500_000L, 1_000_000L).forEach { threshold ->
            assertTrue("$threshold", rung(all, threshold).earned)
            assertEquals("$threshold", "2026-03-05", rung(all, threshold).earnedOnDay)
        }
        assertFalse(rung(all, 3_000_000L).earned)
        assertNull(rung(all, 3_000_000L).earnedOnDay)
        assertEquals(1_100_000f / 3_000_000f, rung(all, 3_000_000L).progress, 1e-5f)
        assertEquals(0.22f, rung(all, 5_000_000L).progress, 1e-5f)
    }

    /**
     * A phone that writes a row every night ends the year with a 0-step row last. Dating a medal
     * by the last row standing above the threshold rather than by the row that crossed it would
     * re-date every medal to today, every day.
     */
    @Test
    fun aDayWithNoStepsOnItCannotBeTheDayAMedalWasWon() {
        val all = stepAchievements(days("2026-01-01" to 100_000L, "2026-01-02" to 0L), 2026)

        assertEquals("2026-01-01", rung(all, 100_000L).earnedOnDay)
    }

    /**
     * The five medals are one ladder, and the profile draws it as one: everything below an earned
     * rung is earned, nothing above an unearned one is, the rings never fill further up than they
     * do lower down, and a badge that names the day it was won is a badge that is held.
     */
    @Test
    fun theLadderReadsAsOneLadderAtEveryTotal() {
        listOf(0L, 99_999L, 100_000L, 750_000L, 3_000_000L, 9_000_000L).forEach { total ->
            val all = stepAchievements(days("2026-06-06" to total), 2026)
            assertEquals(STEP_MEDALS, all.map(Achievement::threshold))
            all.zipWithNext { lower, higher ->
                assertTrue("$total: $lower then $higher", lower.earned || !higher.earned)
                assertTrue("$total: $lower then $higher", lower.progress >= higher.progress)
            }
            all.forEach { medal ->
                assertTrue("$total: $medal", medal.earnedOnDay == null || medal.earned)
                assertTrue("$total: $medal", medal.progress in 0f..1f)
            }
        }
    }

    /**
     * Two rungs sharing a threshold or a label would draw two discs nobody can tell apart, and a
     * duplicate threshold makes every `single { }` lookup over this list throw rather than fail.
     */
    @Test
    fun theMedalLadderItselfIsWellFormed() {
        assertTrue("$STEP_MEDALS", STEP_MEDALS.all { it > 0L })
        assertEquals(STEP_MEDALS.sorted(), STEP_MEDALS)
        assertEquals("$STEP_MEDALS", STEP_MEDALS.size, STEP_MEDALS.toSet().size)
        val labels = STEP_MEDALS.map(::badgeLabel)
        assertEquals("$labels", STEP_MEDALS.size, labels.toSet().size)
        assertTrue("$labels", labels.none { it.isBlank() })
    }

    /**
     * Both boundaries of the shortening go untested by the shipped ladder, which is all round
     * numbers. The label is a single line under the 48.dp disc, so it has to stay short for every
     * rung the app can actually hand it — and the app only ever hands it a STEP_MEDALS threshold,
     * so that is the set asserted here rather than an invented one. A rung that is not a whole
     * thousand or million reads out in full ("1500k"), which is why adding one is a change to
     * badgeLabel and not just to the list.
     */
    @Test
    fun aBadgeLabelStaysShortOnBothSidesOfEveryBoundary() {
        assertEquals("999", badgeLabel(999L))
        assertEquals("1k", badgeLabel(1_000L))
        assertEquals("999k", badgeLabel(999_000L))
        assertEquals("1M", badgeLabel(1_000_000L))
        assertEquals("0", badgeLabel(0L))
        STEP_MEDALS.forEach { threshold ->
            val label = badgeLabel(threshold)
            assertTrue("$threshold reads as $label", label.length <= 4)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The widget's bottom row
    // -----------------------------------------------------------------------------------------

    /**
     * The row is the widget's only persisted state and it is written as the enum's own names.
     * Every three-slot row has to come back out of preferences exactly as it went in — the parser
     * filters on every read, and a filter that is not the identity on a row of known names would
     * quietly rewrite somebody's chosen buttons.
     */
    @Test
    fun everyThreeSlotRowParsesBackToItself() {
        val rows = WidgetSlot.entries.flatMap { first ->
            WidgetSlot.entries.flatMap { second ->
                WidgetSlot.entries.map { third -> listOf(first, second, third) }
            }
        }

        rows.forEach { row ->
            val stored = row.joinToString(",") { it.name }
            assertEquals(stored, row, widgetSlotsOf(stored))
        }
    }

    /** Restored configurations retain their count up to the six-icon limit. */
    @Test
    fun aLongRowKeepsTheFirstSixSlotsChosen() {
        assertEquals(
            listOf(
                WidgetSlot.MOOD,
                WidgetSlot.URINATION,
                WidgetSlot.CIGARETTE,
                WidgetSlot.WATER,
                WidgetSlot.FOOD,
                WidgetSlot.CAFFEINE,
            ),
            widgetSlotsOf("MOOD,URINATION,CIGARETTE,WATER,FOOD,CAFFEINE,EXERCISE"),
        )
    }

    @Test
    fun restoredRowsStayWithinTheSupportedCount() {
        val stored = listOf(null, "", ",", "WATER", "NOPE", "MOOD,URINATION,CIGARETTE,WATER,FOOD")
        stored.forEach { row ->
            assertTrue("stored as $row", widgetSlotsOf(row).size in 1..WIDGET_SLOT_COUNT)
        }
    }

    /** Unknown names are removed without adding shortcuts that were never selected. */
    @Test
    fun anUnreadableNameInTheMiddleOfARowLeavesNoHole() {
        assertEquals(listOf(WidgetSlot.MOOD, WidgetSlot.WATER), widgetSlotsOf("MOOD,SLEEP,WATER"))
        assertEquals(listOf(WidgetSlot.WATER, WidgetSlot.EXERCISE), widgetSlotsOf("WATER,,EXERCISE"))
    }

    /**
     * Preferences hand back "" as readily as null once a row has been saved and cleared, and
     * `"".split(',')` is one empty name rather than no names at all — a different path that has to
     * land in the same place, or the widget draws with no buttons on it.
     */
    @Test
    fun anEmptyStringStoredIsTheSameAsNothingStored() {
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf(""))
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf(",,"))
    }
}
