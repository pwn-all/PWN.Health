package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The painting as the canvas holds it while it is made: the part of a gesture a JVM test can reach. */
class PainDrawingTest {
    /**
     * A second finger on "Back" while the first is still painting the front cuts the gesture off. The
     * points under that finger are places on the front plate: left standing they would be drawn on
     * the back as a stroke no Undo reached, and joined to the next touch there.
     */
    @Test
    fun aStrokeInProgressDoesNotFollowTheFigureToItsOtherView() {
        val kept = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, 0.3f)))
        val drawing = PainDrawing(listOf(kept), PainView.FRONT)
        drawing.current += Offset(0.3f, 0.6f)
        drawing.current += Offset(0.31f, 0.62f)

        drawing.show(PainView.BACK)

        assertEquals(PainView.BACK, drawing.view)
        assertTrue(drawing.current.isEmpty())
        // Only the stroke in progress goes: what was finished stays, and nothing new is there to undo.
        assertEquals(listOf(kept), drawing.strokes.toList())
        assertFalse(drawing.canUndo)
    }

    /**
     * Clear takes what the figure shows: the back's painting, out of sight while the front is up,
     * stays, and so does a stroke above the plate, on the head no plate draws. Undo brings the cleared
     * strokes back in one step.
     */
    @Test
    fun clearTakesOnlyTheViewThatIsUp() {
        val front = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, 0.3f)))
        val head = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, -0.05f)))
        val back = PainStroke(PainView.BACK, 0.03f, listOf(Offset(0.5f, 0.6f)))
        val drawing = PainDrawing(listOf(front, head, back), PainView.FRONT)

        drawing.clear()

        assertEquals(listOf(head, back), drawing.strokes.toList())
        drawing.undo()
        assertEquals(listOf(front, head, back), drawing.strokes.toList())
        // Nothing painted on the view that is up: nothing to clear, and no step for Undo.
        val backUp = PainDrawing(listOf(front, head), PainView.BACK)
        backUp.clear()
        assertEquals(listOf(front, head), backUp.strokes.toList())
        assertFalse(backUp.canUndo)
    }

    /**
     * A stroke over an area struck off the list marks it again; its Undo puts the area back as struck
     * off ([PainDrawing.alsoUndo]). Each step undoes only what came with it, and a Clear nothing more.
     */
    @Test
    fun undoTakesBackWhatCameWithItsStroke() {
        val first = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, 0.3f)))
        val second = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.4f, 0.7f)))
        val drawing = PainDrawing(emptyList(), PainView.FRONT)
        var struckOff = emptyList<String>()

        drawing.commit(first)
        drawing.alsoUndo { struckOff = struckOff + "KNEE:L" }
        drawing.commit(second)
        drawing.clear()
        drawing.undo()
        drawing.undo()

        assertEquals(listOf(first), drawing.strokes.toList())
        assertEquals(emptyList<String>(), struckOff)
        drawing.undo()
        assertTrue(drawing.strokes.isEmpty())
        assertEquals(listOf("KNEE:L"), struckOff)
    }

    /**
     * A turned phone keeps the painting through [PAIN_DRAWING_SAVER]'s plain numbers: every stroke's
     * view, radius and points in their order, a one-point dab among them, and the view that was up —
     * past the payload's cap too, which is the saver's reason to be its own writer.
     */
    @Test
    fun aRotationKeepsThePaintingPastThePayloadsCap() {
        val strokes = List(PAIN_MAX_STROKES + 10) { index ->
            PainStroke(
                if (index % 3 == 0) PainView.BACK else PainView.FRONT,
                0.001f * (index + 1),
                List(index % 4 + 1) { point -> Offset(0.01f * index + 0.003f * point, 0.9f - 0.002f * point) },
            )
        }
        val drawing = PainDrawing(strokes, PainView.BACK)

        val saved = with(PAIN_DRAWING_SAVER) { SaverScope { true }.save(drawing) }
        val restored = checkNotNull(PAIN_DRAWING_SAVER.restore(checkNotNull(saved)))

        assertEquals(strokes, restored.strokes.toList())
        assertEquals(PainView.BACK, restored.view)
        val blank = with(PAIN_DRAWING_SAVER) { SaverScope { true }.save(PainDrawing(emptyList(), PainView.FRONT)) }
        val empty = checkNotNull(PAIN_DRAWING_SAVER.restore(checkNotNull(blank)))
        assertTrue(empty.strokes.isEmpty())
        assertEquals(PainView.FRONT, empty.view)
    }
}
