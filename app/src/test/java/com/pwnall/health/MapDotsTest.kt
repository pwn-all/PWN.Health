package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class MapDotsTest {
    @Test
    fun theLatticesSpacingStaysWithinAnOctaveOfTheDotSpacing() {
        val spacingPx = 18f
        for (scale in listOf(3_000.0, 4.5e4, 7.7e5, 2.0e7, 4.0e7)) {
            val level = dotLevel(scale, spacingPx)
            val onScreen = 2.0.pow(-level) * scale
            assertTrue("$onScreen px at $scale", onScreen >= spacingPx && onScreen < 2 * spacingPx)
            assertEquals(2.0.pow(-level) / 4, groundTexel(level), 0.0)
        }
    }

    /**
     * The anchor is a lattice point on an even row, so every finer lattice — half the spacing, a
     * quarter — holds it on an even row too, and the shader's rows shift where the ground's do.
     */
    @Test
    fun theAnchorIsALatticePointOnAnEvenRow() {
        val spacing = 2.0.pow(-17)
        val rowHeight = spacing * sqrt(3.0) / 2
        val anchor = latticeAnchor(mercatorX(16.49), mercatorY(48.83), spacing)
        val row = anchor.y / rowHeight
        assertEquals(round(row), row, 1e-6)
        assertEquals(0.0, abs(round(row)) % 2, 0.0)
        assertEquals(round(anchor.x / spacing), anchor.x / spacing, 1e-6)
        // Near the point asked about: within a spacing across and two rows up or down.
        assertTrue(abs(anchor.x - mercatorX(16.49)) <= spacing)
        assertTrue(abs(anchor.y - mercatorY(48.83)) <= 2 * rowHeight)
    }

    /** A wood's edge runs between the middles of its cells, not along their sides, so it has no steps. */
    @Test
    fun aWoodsEdgeIsWeighedBetweenItsCells() {
        val pair = booleanArrayOf(true, false)
        assertEquals(1.0, forestShare(pair, 1, 2, 0.0, 0.0), 1e-9)
        assertEquals(0.5, forestShare(pair, 1, 2, 0.0, 0.5), 1e-9)
        assertEquals(0.0, forestShare(pair, 1, 2, 0.0, 1.0), 1e-9)
        // A lone cell is forest at its middle and a quarter forest at its corners.
        val lone = BooleanArray(9).also { it[4] = true }
        assertEquals(1.0, forestShare(lone, 3, 3, 1.0, 1.0), 1e-9)
        assertEquals(0.25, forestShare(lone, 3, 3, 0.5, 0.5), 1e-9)
    }

    /**
     * At 420 dpi the walk page's closest zoom stands 3.7% above the next lattice level down, and a
     * pinch's every twist would cross it both ways, classifying the ground again each time. So a
     * picture serves its own level and the next coarser one; a finer lattice is classified again.
     */
    @Test
    fun aPinchInATwistAtTheClosestZoomKeepsTheGround() {
        val spacingPx = 7f * 2.625f
        val closest = MapCamera().closest
        assertEquals(21, dotLevel(closest, spacingPx))
        assertEquals(20, dotLevel(closest * 0.96, spacingPx))
        assertTrue(servesLattice(21, 21))
        assertTrue(servesLattice(21, 20))
        assertFalse(servesLattice(20, 21))
        assertFalse(servesLattice(21, 19))
    }
}
