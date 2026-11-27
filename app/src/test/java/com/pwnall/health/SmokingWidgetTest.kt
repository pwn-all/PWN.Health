package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/** The count the cigarette widget prints under its "+ Cigarette". */
class SmokingWidgetTest {
    private fun row(kind: String, grams: Double) = JournalEntry(
        day = "2026-10-03",
        occurredAt = 0L,
        kind = kind,
        title = "",
        calories = 0.0,
        servingG = grams,
    )

    /**
     * Two cigarettes, a medium hookah and a heated stick are two under the button that adds a
     * cigarette, not the seventeen and a bit their grams make over a cigarette's; a glass of water
     * filed under the same figure in millilitres is not tobacco at all.
     */
    @Test
    fun theCountUnderTheButtonIsTheDaysCigarettesAlone() {
        val cigarette = row(KIND_SMOKING, TobaccoProduct.CIGARETTE.tobaccoG)
        val journal = listOf(
            cigarette,
            cigarette,
            row(KIND_SMOKING, TobaccoProduct.HOOKAH_MEDIUM.tobaccoG),
            row(KIND_SMOKING, TobaccoProduct.HEATED.tobaccoG),
            row(KIND_WATER, TobaccoProduct.CIGARETTE.tobaccoG),
        )

        assertEquals(2, cigarettesIn(journal))
    }
}
