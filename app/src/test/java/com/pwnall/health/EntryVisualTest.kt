package com.pwnall.health

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.WaterDrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every kind the journal can hold needs its own branch: a mood or breathing entry that fell through
 * to the weight icon would show a pair of scales next to "😊 Good".
 */
class EntryVisualTest {
    private fun entry(kind: String) = JournalEntry(
        day = "2026-07-31",
        occurredAt = 0,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    @Test
    fun moodIsNotTheScales() {
        assertEquals(Icons.Outlined.Mood, entry(KIND_MOOD).visual().icon)
    }

    @Test
    fun breathingIsNotTheScales() {
        assertEquals(Icons.Outlined.SelfImprovement, entry(KIND_BREATHING).visual().icon)
    }

    @Test
    fun waterKeepsItsDrop() {
        assertEquals(Icons.Outlined.WaterDrop, entry(KIND_WATER).visual().icon)
    }

    /**
     * Every kind the journal actually stores, not a hand-picked five — a short list would let caffeine,
     * the three cycle kinds and a planned session all fall through to the scales unnoticed.
     * "MEASUREMENT" is deliberately absent: the scales are its correct answer, covered below.
     */
    @Test
    fun everyKnownKindHasItsOwnIcon() {
        val kinds = listOf(
            KIND_WATER,
            KIND_MOOD,
            KIND_BREATHING,
            KIND_CAFFEINE,
            KIND_CYCLE,
            KIND_CYCLE_END,
            KIND_CYCLE_LOG,
            KIND_MASTURBATION,
            KIND_MORNING_CHECK,
            KIND_PLAN,
            KIND_NOT_DONE,
            KIND_MED,
            KIND_WORK,
            KIND_PAIN,
            KIND_SYMPTOM,
            "EAT",
            "ACTIVITY",
        )
        kinds.forEach { kind ->
            assertNotEquals(kind, Icons.Outlined.MonitorWeight, entry(kind).visual().icon)
        }
    }

    @Test
    fun aWeightMeasurementStillGetsTheScales() {
        val weight = entry("MEASUREMENT").copy(measurementWeightKg = 80.0)
        assertEquals(Icons.Outlined.MonitorWeight, weight.visual().icon)
    }
}
