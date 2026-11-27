package com.pwnall.health

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The home screen widgets draw the duotone icons straight from their colour resources: Glance and
 * RemoteViews have no themePainterResource to recolour them, and on the quick entry widget the icon
 * is the only visible label a button has. So the resource inks are what the widget shows, and they
 * owe the same contrast the app's own icons do.
 */
class WidgetIconInkTest {
    @Test
    fun widgetInksAreTheAppsDefaultIconColours() {
        for (dark in listOf(false, true)) {
            val inks = inks(dark)
            fun default(role: ThemeColorRole) = Color(defaultThemeColor(role, dark))
            assertEquals(default(ThemeColorRole.ICON), inks.getValue("art_ink"))
            assertEquals(default(ThemeColorRole.ICON), inks.getValue("art_duo_ink"))
            assertEquals(default(ThemeColorRole.ICON_MID), inks.getValue("art_duo_mid"))
            assertEquals(default(ThemeColorRole.ICON_LIGHT), inks.getValue("art_duo_tile"))
        }
    }

    /** themePainterResource finds each duotone role by its colour, so two roles sharing one merge. */
    @Test
    fun theThreeDuotoneRolesStayDistinctInEachTheme() {
        for (dark in listOf(false, true)) {
            val inks = inks(dark)
            val roles = listOf("art_duo_ink", "art_duo_mid", "art_duo_tile").map(inks::getValue)
            assertEquals("dark=$dark", roles.size, roles.toSet().size)
        }
    }

    /**
     * The widget ground comes from the launcher's palette, not the app's: the grounds here are the
     * pure extremes and Material's baseline surfaces, which a dynamic palette stays close to.
     */
    @Test
    fun theFigureTonesClearGraphicContrastOnWidgetGrounds() {
        for (dark in listOf(false, true)) {
            val inks = inks(dark)
            val grounds = if (dark) {
                listOf(Color.Black, Color(0xFF1C1B1F))
            } else {
                listOf(Color.White, Color(0xFFFFFBFE))
            }
            for (name in listOf("art_ink", "art_duo_ink", "art_duo_mid")) {
                for (ground in grounds) {
                    val ratio = contrastRatio(inks.getValue(name), ground)
                    assertTrue("$name on $ground (dark=$dark) is $ratio:1", ratio >= GRAPHIC_CONTRAST)
                }
            }
        }
    }

    private fun inks(dark: Boolean): Map<String, Color> {
        val folder = if (dark) "values-night" else "values"
        return COLOR.findAll(File("src/main/res/$folder/icons.xml").readText())
            .associate { it.groupValues[1] to Color(it.groupValues[2].toLong(HEX).toInt()) }
    }

    private companion object {
        val COLOR = Regex("""<color name="(art_[a-z_]+)">#([0-9A-Fa-f]{8})</color>""")
        const val HEX = 16
    }
}
