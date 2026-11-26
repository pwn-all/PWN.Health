package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each shortcut's cell is its whole tap target, so the grid the widget picks decides how large a
 * target a finger gets. A widget with room for the icons at full pitch keeps the layout it always
 * had; one that does not is laid out for the largest cells rather than as a single row of slivers.
 */
class MultiWidgetGridTest {
    @Test
    fun aWidgetWithRoomKeepsAsManyColumnsAsTheWidthTakes() {
        // Four columns at full pitch across and two rows down hold all six.
        assertEquals(4, multiWidgetColumns(width = 230f, height = 160f, slots = 6))
    }

    @Test
    fun aShortWidgetWithHeightForTwoRowsUsesThem() {
        // Two columns across at full pitch and one row down: six in one row were cells a sixth of
        // the width, where two rows of three are cells close to square.
        assertEquals(3, multiWidgetColumns(width = 130f, height = 90f, slots = 6))
    }

    @Test
    fun aWidgetOneRowHighStaysOneRow() {
        // Two rows would halve the cells' height below what one row of six leaves their width.
        assertEquals(6, multiWidgetColumns(width = 230f, height = 54f, slots = 6))
    }
}
