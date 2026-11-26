package com.pwnall.health

import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource

internal data class ThemeIconColors(val ink: Color, val mid: Color, val light: Color, val line: Color)

internal fun ThemePalette.iconColors(dark: Boolean): ThemeIconColors {
    val ink = Color(color(ThemeColorRole.ICON, dark))
    val mid = Color(color(ThemeColorRole.ICON_MID, dark))
    val light = Color(color(ThemeColorRole.ICON_LIGHT, dark))
    if (overrides.isEmpty()) return ThemeIconColors(ink, mid, light, ink)
    // The two tones that draw the figure are meaningful graphics and owe 3:1 to the surfaces the
    // icons stand on; the light tone is the cut-out inside the figure and keeps whatever was picked.
    val scheme = colorScheme(dark)
    val grounds = listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)
    val legibleInk = legibleOn(ink, grounds, GRAPHIC_CONTRAST)
    return ThemeIconColors(legibleInk, legibleOn(mid, grounds, GRAPHIC_CONTRAST), light, legibleInk)
}

internal val LocalThemeIconColors = staticCompositionLocalOf { ThemePalette().iconColors(false) }

/**
 * The set a chosen thing is drawn in — the navigation bar's current stop, a lit tile — and nothing
 * else: every other drawing wears [iconColors], the quiet set of the bar's resting stops. The main
 * accent inks the figure over a lighter tone of it; the cut-out stays the palette's own.
 */
internal fun ThemePalette.accentIconColors(dark: Boolean): ThemeIconColors {
    val scheme = colorScheme(dark)
    val ink = scheme.primary
    val mid = when {
        ThemeColorRole.MAIN_ACCENT in overrides -> lerp(ink, scheme.surface, ACCENT_MID_MIX)
        dark -> PwnDarkColors.secondary
        else -> DEFAULT_ACCENT_MID
    }
    val grounds = listOf(scheme.surface, scheme.surfaceContainer, scheme.secondaryContainer)
    return ThemeIconColors(
        ink,
        legibleOn(mid, grounds, GRAPHIC_CONTRAST),
        Color(color(ThemeColorRole.ICON_LIGHT, dark)),
        ink,
    )
}

private val DEFAULT_ACCENT_MID = Color(0xFF607BA8)
private const val ACCENT_MID_MIX = 0.3f

internal val LocalAccentIconColors = staticCompositionLocalOf { ThemePalette().accentIconColors(false) }

/** Recolour only the vector icon set. Photos, raster art and the report keep their own colours. */
@Composable
internal fun themePainterResource(@DrawableRes id: Int): Painter {
    val resources = LocalResources.current
    val isThemeIcon = remember(resources, id) {
        val value = TypedValue()
        resources.getValue(id, value, true)
        recolorsWithTheme(id, value.string)
    }
    if (!isThemeIcon) return painterResource(id)
    val original = ImageVector.vectorResource(id)
    val target = LocalThemeIconColors.current
    val line = colorResource(R.color.art_ink)
    val ink = colorResource(R.color.art_duo_ink)
    val mid = colorResource(R.color.art_duo_mid)
    val light = colorResource(R.color.art_duo_tile)
    val vector = remember(original, target, line, ink, mid, light) {
        recolorThemeVector(
            original,
            mapOf(line to target.line, ink to target.ink, mid to target.mid, light to target.light),
        )
    }
    return rememberVectorPainter(vector)
}

/**
 * Whether the drawable [id], stored at [path], is drawn in the theme's icon colours: any vector — a
 * file ending in .xml, the test [painterResource] itself makes before loading one — except the
 * report, which keeps its own. Not by the "ic_art_" name: a release build's resource optimisation is
 * free to collapse names, and nothing holds it to keeping them. Recolouring swaps only the icon set's
 * own inks, which no other drawable uses, so any other vector comes back as it was.
 */
internal fun recolorsWithTheme(@DrawableRes id: Int, path: CharSequence?): Boolean =
    id != R.drawable.ic_art_report && path?.endsWith(".xml") == true

internal fun recolorThemeVector(original: ImageVector, colors: Map<Color, Color>): ImageVector {
    val builder = ImageVector.Builder(
        name = original.name,
        defaultWidth = original.defaultWidth,
        defaultHeight = original.defaultHeight,
        viewportWidth = original.viewportWidth,
        viewportHeight = original.viewportHeight,
        tintColor = original.tintColor,
        tintBlendMode = original.tintBlendMode,
        autoMirror = original.autoMirror,
    )
    builder.copyThemeGroup(original.root, colors)
    return builder.build()
}

private fun Brush?.withThemeColor(colors: Map<Color, Color>): Brush? =
    if (this is SolidColor) SolidColor(colors[value] ?: value) else this

/** Preserve transforms, clipping, opacity, strokes and trim paths while replacing named inks. */
private fun ImageVector.Builder.copyThemeGroup(group: VectorGroup, colors: Map<Color, Color>) {
    addGroup(
        name = group.name,
        rotate = group.rotation,
        pivotX = group.pivotX,
        pivotY = group.pivotY,
        scaleX = group.scaleX,
        scaleY = group.scaleY,
        translationX = group.translationX,
        translationY = group.translationY,
        clipPathData = group.clipPathData,
    )
    for (node in group) {
        when (node) {
            is VectorGroup -> copyThemeGroup(node, colors)

            is VectorPath -> addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                name = node.name,
                fill = node.fill.withThemeColor(colors),
                fillAlpha = node.fillAlpha,
                stroke = node.stroke.withThemeColor(colors),
                strokeAlpha = node.strokeAlpha,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
                trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd,
                trimPathOffset = node.trimPathOffset,
            )
        }
    }
    clearGroup()
}
