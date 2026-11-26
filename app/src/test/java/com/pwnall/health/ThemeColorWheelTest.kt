package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorWheelTest {
    @Test
    fun cardinalAnglesMatchTheDrawnSweepGradient() {
        assertEquals(0f, themeWheelSelection(100f, 0f, 100f).first, 0.001f)
        assertEquals(90f, themeWheelSelection(0f, 100f, 100f).first, 0.001f)
        assertEquals(180f, themeWheelSelection(-100f, 0f, 100f).first, 0.001f)
        assertEquals(270f, themeWheelSelection(0f, -100f, 100f).first, 0.001f)
    }

    @Test
    fun saturationRunsAcrossTheRingAndClampsWhenDraggingPastItsEdges() {
        assertEquals(0f, themeWheelSelection(55f, 0f, 100f).second, 0.001f)
        assertEquals(0.5f, themeWheelSelection(77.5f, 0f, 100f).second, 0.001f)
        assertEquals(1f, themeWheelSelection(100f, 0f, 100f).second, 0.001f)
        assertEquals(0f, themeWheelSelection(30f, 0f, 100f).second, 0.001f)
        assertEquals(1f, themeWheelSelection(120f, 0f, 100f).second, 0.001f)
    }

    @Test
    fun changingWheelSizeKeepsTheSelectedColor() {
        val small = themeWheelSelection(60f, 60f, 100f)
        val large = themeWheelSelection(120f, 120f, 200f)
        assertEquals(small.first, large.first, 0.001f)
        assertEquals(small.second, large.second, 0.001f)
    }
}
