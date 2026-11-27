package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the ruler's scale labels stand, in pixels at one pixel a dp. A label sits under its own
 * tick, pushed inward only to stay whole on the row, and is left out once its tick is further off
 * the strip than the label is wide — so a wide spacing can never print a far value under a near tick.
 */
class RulerLabelTest {
    @Test
    fun theValueAtTheNeedleIsCentredUnderIt() {
        assertEquals(130, rulerLabelLeft(rowWidth = 300, labelWidth = 40, tickOffset = 0f))
    }

    @Test
    fun aNeighbourStandsUnderItsOwnTickAtTheDefaultSpacing() {
        // Ten ticks of 9 dp either side of a 330 dp strip's needle.
        assertEquals(165 + 90 - 12, rulerLabelLeft(330, 24, 90f))
        assertEquals(165 - 90 - 12, rulerLabelLeft(330, 24, -90f))
    }

    @Test
    fun anOuterNeighbourJustOffTheStripKeepsItsLabelAtTheEdge() {
        // Twenty ticks of 9 dp is 180 dp, fifteen past the edge of a 330 dp strip: the label stays,
        // whole, against the edge.
        assertEquals(330 - 24, rulerLabelLeft(330, 24, 180f))
        assertEquals(0, rulerLabelLeft(330, 24, -180f))
    }

    @Test
    fun aNeighbourFarOffTheStripIsLeftOut() {
        // The long-day ruler at 26 dp a tick: at 6 h, "16 h" is ten ticks away, 260 dp, far past a
        // 363 dp strip's edge. Spread evenly it would stand under the 9 h tick; here it is not printed.
        assertNull(rulerLabelLeft(363, 36, 260f))
        assertNull(rulerLabelLeft(363, 36, -260f))
        // On a tablet's strip the 16 h tick is on screen, and its label stands under it.
        assertEquals(300 + 260 - 18, rulerLabelLeft(600, 36, 260f))
    }

    @Test
    fun aLabelWiderThanTheRowStartsAtItsLeftEdge() {
        assertEquals(0, rulerLabelLeft(20, 40, 0f))
    }
}
