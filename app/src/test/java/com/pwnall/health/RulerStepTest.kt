package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step behind the rulers' increase and decrease actions — the only way to set a weight or a
 * height without a drag, so a screen reader depends on it reporting honestly.
 */
class RulerStepTest {
    private val weights = (400..1_400).toList()

    @Test
    fun aStepMovesToTheNeighbouringValue() {
        var chosen: Int? = null
        assertTrue(stepRuler(weights, weights.indexOf(750), 1) { chosen = it })
        assertEquals(751, chosen)
        assertTrue(stepRuler(weights, weights.indexOf(750), -1) { chosen = it })
        assertEquals(749, chosen)
    }

    @Test
    fun aStepPastEitherEndChangesNothing() {
        var chosen: Int? = null
        assertFalse(stepRuler(weights, 0, -1) { chosen = it })
        assertFalse(stepRuler(weights, weights.lastIndex, 1) { chosen = it })
        assertNull(chosen)
    }
}
