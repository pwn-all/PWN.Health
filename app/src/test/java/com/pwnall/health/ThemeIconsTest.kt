package com.pwnall.health

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconsTest {
    @Test
    fun recoloringPreservesGeometryClippingAndPathStyles() {
        val path = PathParser().parsePathString("M0,0 L12,0 L12,12 Z").toNodes()
        val original = ImageVector.Builder(
            name = "fixture",
            defaultWidth = 24.dp,
            defaultHeight = 30.dp,
            viewportWidth = 24f,
            viewportHeight = 30f,
            autoMirror = true,
        ).addGroup(
            name = "transformed",
            rotate = 15f,
            pivotX = 12f,
            pivotY = 15f,
            scaleX = 0.7f,
            scaleY = 0.8f,
            translationX = 2f,
            translationY = 3f,
            clipPathData = path,
        ).addPath(
            pathData = path,
            fill = SolidColor(Color.Red),
            fillAlpha = 0.6f,
            stroke = SolidColor(Color.Blue),
            strokeAlpha = 0.8f,
            strokeLineWidth = 2f,
            trimPathStart = 0.1f,
            trimPathEnd = 0.9f,
            trimPathOffset = 0.2f,
        ).clearGroup().build()
        val changed = recolorThemeVector(original, mapOf(Color.Red to Color.Green, Color.Blue to Color.Yellow))
        assertEquals(original.defaultWidth, changed.defaultWidth)
        assertEquals(original.defaultHeight, changed.defaultHeight)
        assertEquals(original.viewportWidth, changed.viewportWidth)
        assertEquals(original.viewportHeight, changed.viewportHeight)
        assertTrue(changed.autoMirror)
        val group = (changed.root[0] as VectorGroup)[0] as VectorGroup
        assertEquals("transformed", group.name)
        assertEquals(path, group.clipPathData)
        assertEquals(15f, group.rotation)
        assertEquals(0.7f, group.scaleX)
        assertEquals(0.8f, group.scaleY)
        assertEquals(2f, group.translationX)
        assertEquals(3f, group.translationY)
        val node = group[0] as VectorPath
        assertEquals(path, node.pathData)
        assertEquals(SolidColor(Color.Green), node.fill)
        assertEquals(SolidColor(Color.Yellow), node.stroke)
        assertEquals(0.6f, node.fillAlpha)
        assertEquals(0.8f, node.strokeAlpha)
        assertEquals(2f, node.strokeLineWidth)
        assertEquals(0.1f, node.trimPathStart)
        assertEquals(0.9f, node.trimPathEnd)
        assertEquals(0.2f, node.trimPathOffset)
    }

    @Test
    fun unrelatedColorsAndGradientsArePreserved() {
        val gradient = Brush.horizontalGradient(listOf(Color.Red, Color.Blue))
        val original = ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = emptyList(),
            fill = gradient,
            stroke = SolidColor(Color.Magenta),
        ).build()
        val changed = recolorThemeVector(original, mapOf(Color.Red to Color.Green))
        val node = (changed.root[0] as VectorGroup)[0] as VectorPath
        assertEquals(gradient, node.fill)
        assertEquals(SolidColor(Color.Magenta), node.stroke)
    }

    @Test
    fun aVectorTakesTheThemeWhateverTheReleaseBuildNamedItsFile() {
        assertTrue(recolorsWithTheme(R.drawable.ic_art_height, "res/drawable/ic_art_height.xml"))
        // Resource optimisation shortens the path, and may collapse the name, but keeps the extension.
        assertTrue(recolorsWithTheme(R.drawable.ic_art_height, "res/-3.xml"))
        assertFalse(recolorsWithTheme(R.drawable.ic_art_medal_1m, "res/drawable-nodpi/ic_art_medal_1m.webp"))
        assertFalse(recolorsWithTheme(R.drawable.ic_art_report, "res/drawable/ic_art_report.xml"))
    }
}
