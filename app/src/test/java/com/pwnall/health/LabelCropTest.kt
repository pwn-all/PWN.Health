package com.pwnall.health

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one piece of arithmetic behind the crop screen: a box drawn on a preview, applied to a
 * photograph thousands of pixels wider. Get the scale wrong and the reader is handed the wrong
 * corner of the packet.
 */
class LabelCropTest {
    @Test
    fun scalesTheDrawnBoxToThePhotograph() {
        val bounds = requireNotNull(
            cropBounds(Rect(100f, 200f, 300f, 500f), IntSize(1000, 2000), IntSize(3000, 4000)),
        )

        assertEquals(300, bounds.left)
        assertEquals(400, bounds.top)
        assertEquals(900, bounds.right)
        assertEquals(1000, bounds.bottom)
    }

    /** A drag runs in any direction, and the box it leaves is the same either way. */
    @Test
    fun aBoxDrawnBackwardsIsTheSameBox() {
        val forwards = cropBounds(Rect(100f, 200f, 300f, 500f), IntSize(1000, 2000), IntSize(3000, 4000))
        val backwards = cropBounds(Rect(300f, 500f, 100f, 200f), IntSize(1000, 2000), IntSize(3000, 4000))

        assertEquals(forwards, backwards)
    }

    /** A tap that slipped is not a box; the whole photograph goes to the reader instead. */
    @Test
    fun aFingerThatBarelyMovedMarksNothing() {
        assertNull(cropBounds(Rect(100f, 200f, 110f, 260f), IntSize(1000, 2000), IntSize(3000, 4000)))
        assertNull(cropBounds(Rect(100f, 200f, 300f, 500f), IntSize.Zero, IntSize(3000, 4000)))
    }

    /** A drag that ran off the preview stops at the photograph's edge, never past it. */
    @Test
    fun aBoxDraggedOffTheEdgeStaysInsideThePhotograph() {
        val bounds = requireNotNull(
            cropBounds(Rect(-50f, -80f, 1200f, 2400f), IntSize(1000, 2000), IntSize(3000, 4000)),
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(3000, bounds.right)
        assertEquals(4000, bounds.bottom)
    }
}
