package com.pwnall.health

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SmokingRooms
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared wellness palette. Semantic warning/success colours stay separate from brand accents. */
internal val PwnInkRaw = Color(0xFF172337)
internal val PwnMutedRaw = Color(0xFF5C677A)
internal val PwnIndigoRaw = Color(0xFF7094EE)
internal val PwnIndigoSoftRaw = Color(0xFFEDF2FC)
internal val PwnOrangeRaw = Color(0xFF92AFEF)
internal val PwnOrangeSoftRaw = Color(0xFFF0F4FC)
internal val PwnGreenRaw = Color(0xFF217844)
internal val PwnGreenDarkRaw = Color(0xFF4FC17E)
internal val PwnPurpleRaw = Color(0xFF7094EE)
internal val PwnPurpleSoftRaw = Color(0xFFD9E4FA)
internal val PwnBlueRaw = Color(0xFF91B0F1)
internal val PwnBlueSoftRaw = Color(0xFFEBF1FD)
internal val PwnBorderRaw = Color(0xFFE8EDF8)
internal val PwnCanvasRaw = Color(0xFFFCFDFE)

internal val PwnInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val PwnMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val PwnIndigo: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val PwnIndigoDark: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
internal val PwnAccentInk: Color @Composable get() = darkOr(PwnMuted, PwnInk)
internal val PwnIndigoSoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val PwnOrange: Color @Composable get() = MaterialTheme.colorScheme.tertiary
internal val PwnOrangeSoft: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
internal val PwnGreen: Color @Composable get() = statusInk(PwnGreenDarkRaw, PwnGreenRaw)
internal val PwnGreenSoft: Color @Composable get() = darkOr(Color(0xFF1E3D2B), Color(0xFFE8F6EE))
internal val PwnPurple: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val PwnPurpleSoft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
internal val PwnBlue: Color @Composable get() = darkOr(Color(0xFF78B2FB), PwnBlueRaw)

/**
 * [PwnBlue] at text weight: the blue a figure or a label is printed in, where [PwnBlue] itself is
 * a colour things are filled with.
 *
 * The daylight blue is a fill and measures 2.17:1 on white, far under the 4.5:1 that text owes.
 * Deepened at the same hue it is 5.44:1, which is what this is. Two places draw in it: a lab
 * reading outside its band and the fertile window's caption on the ring. DesignContrastTest holds
 * it to its grounds.
 */
internal val PwnBlueInk: Color @Composable get() = statusInk(PwnBlueInkDarkRaw, PwnBlueInkLightRaw)
internal val PwnBlueInkLightRaw = Color(0xFF3667C0)
internal val PwnBlueInkDarkRaw = Color(0xFF78B2FB)
internal val PwnSelectionColor: Color
    @Composable get() = if (ThemeColorRole.MAIN_ACCENT in LocalThemePalette.current.overrides) {
        MaterialTheme.colorScheme.primary
    } else {
        PwnBlueInk
    }
private val PwnSelectionContent: Color
    @Composable get() = if (ThemeColorRole.MAIN_ACCENT in LocalThemePalette.current.overrides) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        PwnCanvas
    }
internal val PwnBlueSoft: Color @Composable get() = darkOr(Color(0xFF1D2C43), PwnBlueSoftRaw)

/** Status tones printed as text on cards: each clears 4.5:1 on every surface tier (DesignContrastTest). */
internal val PwnBleedLightRaw = Color(0xFFB82843)
internal val PwnBleedDarkRaw = Color(0xFFF08A98)
internal val PwnWarnLightRaw = Color(0xFF955300)
internal val PwnWarnDarkRaw = Color(0xFFEFB57E)
internal val PwnBleed: Color @Composable get() = statusInk(PwnBleedDarkRaw, PwnBleedLightRaw)
internal val PwnBorder: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val PwnControlEdge: Color @Composable get() = MaterialTheme.colorScheme.outline
internal val PwnCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
internal val PwnWarn: Color @Composable get() = statusInk(PwnWarnDarkRaw, PwnWarnLightRaw)
internal val PwnSurfaceElevated: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val PwnGradientColors: List<Color>
    @Composable get() = if (
        ThemeColorRole.MAIN_ACCENT in LocalThemePalette.current.overrides ||
        ThemeColorRole.SECONDARY in LocalThemePalette.current.overrides
    ) {
        val scheme = MaterialTheme.colorScheme
        listOf(scheme.primary, lerp(scheme.primary, scheme.secondary, 0.5f), scheme.secondary)
    } else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        listOf(Color(0xFF78B2FB), Color(0xFF7094EE), Color(0xFF92AFEF))
    } else {
        listOf(Color(0xFF91B0F1), Color(0xFF7094EE), Color(0xFF7094EE))
    }

@Composable
internal fun darkOr(dark: Color, light: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light

/**
 * [darkOr] for a tone printed as text: the built-in palettes are measured for it (DesignContrastTest),
 * and a custom one gets it drawn legible on its own grounds by [statusTone].
 */
@Composable
private fun statusInk(dark: Color, light: Color): Color {
    if (LocalThemePalette.current.overrides.isEmpty()) return darkOr(dark, light)
    val scheme = MaterialTheme.colorScheme
    return remember(dark, light, scheme) { statusTone(dark, light, scheme) }
}

internal val PwnLightColors = lightColorScheme(
    // Illustration accents stay pastel; semantic accents also carry text on light surfaces.
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = PwnIndigoSoftRaw,
    onPrimaryContainer = PwnInkRaw,
    secondary = Color(0xFF475D87),
    onSecondary = Color.White,
    secondaryContainer = PwnPurpleSoftRaw,
    onSecondaryContainer = PwnInkRaw,
    tertiary = Color(0xFF535B87),
    onTertiary = Color.White,
    tertiaryContainer = PwnOrangeSoftRaw,
    onTertiaryContainer = PwnInkRaw,
    background = PwnCanvasRaw,
    onBackground = PwnInkRaw,
    surface = Color(0xFFFFFFFF),
    onSurface = PwnInkRaw,
    surfaceVariant = PwnIndigoSoftRaw,
    onSurfaceVariant = PwnMutedRaw,
    outline = Color(0xFF727C8F),
    // Separators and hairlines, a step deeper than the pale secondaryContainer: at that tone a
    // divider would measure 1.10:1 on a card and read as nothing at all. DesignContrastTest
    // keeps it at 1.3:1 or more on every surface tier, about where Material's baseline hairline
    // sits on its own cards (1.39:1).
    outlineVariant = Color(0xFFBFCADF),
    surfaceDim = Color(0xFFEBF1FD),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = PwnCanvasRaw,
    surfaceContainer = PwnIndigoSoftRaw,
    surfaceContainerHigh = Color(0xFFE8EEF9),
    surfaceContainerHighest = Color(0xFFE1E8F5),
    inverseSurface = Color(0xFF1D2C43),
    inverseOnSurface = Color(0xFFF4F5FA),
    inversePrimary = Color(0xFF7094EE),
    scrim = Color(0xFF0E1626),
    surfaceTint = Color(0xFF315DA8),
)

internal val PwnDarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF0E1626),
    primaryContainer = Color(0xFF283B59),
    onPrimaryContainer = Color(0xFFF4F5FA),
    secondary = Color(0xFF92AFEF),
    onSecondary = Color(0xFF0E1626),
    secondaryContainer = Color(0xFF283B59),
    onSecondaryContainer = Color(0xFFF4F5FA),
    tertiary = Color(0xFF92AFEF),
    onTertiary = Color(0xFF0E1626),
    tertiaryContainer = Color(0xFF283B59),
    onTertiaryContainer = Color(0xFFF4F5FA),
    background = Color(0xFF0E1626),
    onBackground = Color(0xFFF4F5FA),
    surface = Color(0xFF141E31),
    onSurface = Color(0xFFF4F5FA),
    surfaceVariant = Color(0xFF283B59),
    onSurfaceVariant = Color(0xFFA5B3CB),
    outline = Color(0xFF8997AF),
    outlineVariant = Color(0xFF47628B),
    surfaceDim = Color(0xFF0E1626),
    surfaceBright = Color(0xFF1D2C43),
    surfaceContainerLowest = Color(0xFF0E1626),
    surfaceContainerLow = Color(0xFF141E31),
    surfaceContainer = Color(0xFF1D2C43),
    surfaceContainerHigh = Color(0xFF24334A),
    surfaceContainerHighest = Color(0xFF283B59),
    inverseSurface = Color(0xFFF4F5FA),
    inverseOnSurface = Color(0xFF0E1626),
    inversePrimary = Color(0xFF315DA8),
    scrim = Color(0xFF0E1626),
    surfaceTint = Color(0xFFAEC6FF),
)

@Composable
internal fun PwnFitTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val preferences = rememberThemePreferences()
    val palette = preferences.palette(darkTheme)
    val colorScheme = palette.colorScheme(darkTheme)
    SystemBarIcons(colorScheme.background)
    CompositionLocalProvider(
        LocalThemePreferences provides preferences,
        LocalThemePalette provides palette,
        LocalThemeIconColors provides palette.iconColors(darkTheme),
        LocalAccentIconColors provides palette.accentIconColors(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PwnTypography,
            shapes = PwnShapes,
            content = content,
        )
    }
}

/**
 * Dark status and navigation icons over a light page, light ones over a dark page.
 *
 * The activities go edge to edge with the automatic bar style, which picks the icons from the
 * system's night mode. A custom theme can put a dark background into the light scheme or a light
 * one into the dark scheme, and the icons then vanish into the page behind them, so the choice is
 * made here from the background that is actually drawn. A window that still fits the system
 * windows is left alone: its bars are painted in the window's own colour rather than over the
 * page, and the icons have to answer that colour instead. Such a window shows no status bar inset,
 * since its content is not laid out under the bar.
 */
@Composable
private fun SystemBarIcons(background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val underBars = WindowInsets.statusBars.getTop(LocalDensity.current) > 0
    val lightPage = background.luminance() >= LIGHT_PAGE_LUMINANCE
    SideEffect {
        val controller = view.context.findActivity()?.window?.insetsController
        if (underBars && controller != null) {
            val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (lightPage) both else 0, both)
        }
    }
}

/** The same split [darkOr] draws the palette by, so the bars agree with the page. */
private const val LIGHT_PAGE_LUMINANCE = 0.5f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Raised controls use a small shadow; ordinary cards use tonal containment instead. */
@Composable
internal fun Modifier.pwnShadow(shape: Shape, elevation: Dp = PWN_SHADOW): Modifier =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        this
    } else {
        shadow(elevation, shape, clip = false, ambientColor = PwnShadowTint, spotColor = PwnShadowTint)
    }

/** Pale, and blue rather than neutral, so it sits in the same family as the page under it. */
private val PwnShadowTint = Color(0x2698B7EE)

internal val PWN_SHADOW = PwnElevation.level1

/**
 * The one promise the app makes about where the record lives, said wherever the record is about to
 * be handed somewhere: on the front of the profile, and under a report on its way out of the phone.
 *
 * Tinted rather than white, because it is a reassurance and not a row of the page it sits on.
 */
@Composable
internal fun PwnLocalDataCard() {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.extraLarge),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
    ) {
        Row(
            Modifier.padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PwnSpacing.large),
        ) {
            Image(
                themePainterResource(R.drawable.ic_art_security),
                contentDescription = null,
                modifier = Modifier.size(PwnDimensions.glyphSize),
            )
            Column {
                Text(
                    stringResource(R.string.profile_local_card_title),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.profile_local_card_body),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f),
                )
            }
        }
    }
}

/**
 * A door out of a page: a tall pill a little narrower than the page it stands on, the drawing of
 * what it does beside the words.
 *
 * Taller than a Material button's own 40dp, because a button of this kind is the point of the page
 * rather than a control inside a form, and it is the last thing a thumb reaches on it. Written once
 * here rather than per page, so that two pages that do the same thing — hand a file to somebody —
 * do not look like two different apps.
 */
@Composable
internal fun ColumnScope.PwnWideButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tonal: Boolean = false,
) {
    // The cap comes before the fraction: on a phone the button keeps its share of the column, and
    // only a pane wider than the cap stops it growing.
    val modifier = Modifier.widthIn(max = PwnDimensions.maxButtonWidth)
        .fillMaxWidth(WIDE_BUTTON_WIDTH)
        .align(Alignment.CenterHorizontally)
        .heightIn(min = PwnDimensions.mediumButtonHeight)
    val padding = PaddingValues(horizontal = PwnDimensions.mediumButtonPadding, vertical = 16.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(PwnDimensions.mediumButtonIcon))
        Spacer(Modifier.size(PwnDimensions.buttonGap))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
    // The round Medium button squares its corners under the finger and springs back when it lifts.
    val interaction = remember { MutableInteractionSource() }
    val shape = rememberPressedShape(interaction, rest = PwnDimensions.mediumButtonHeight / 2)
    if (tonal) {
        FilledTonalButton(
            onClick,
            modifier,
            enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = interaction,
            content = content,
        )
    } else {
        Button(
            onClick,
            modifier,
            enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = interaction,
            content = content,
        )
    }
}

/** How much of its column a [PwnWideButton] takes. */
private const val WIDE_BUTTON_WIDTH = 0.94f

/**
 * Material 3 Expressive's pressed shape: corners that tighten while a finger is down and spring
 * back when it lifts, played again on every press and never on their own. [rest] is the control's
 * own radius and [pressed] the one it tightens to; pass the same [interactionSource] the control
 * is given, since that is where the press is reported.
 */
@Composable
internal fun rememberPressedShape(
    interactionSource: InteractionSource,
    rest: Dp,
    pressed: Dp = PwnCorners.medium,
): Shape {
    val down by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (down) pressed else rest,
        PwnMotion.fastSpatial(),
        label = "pressed corner",
    )
    return RoundedCornerShape(corner)
}

/**
 * The state that Slider(value = …), deprecated in Material3 1.5, keeps inside: rebuilt for a new
 * [range] and set back to [value] on every composition, so the thumb shows what the caller holds —
 * a value changed elsewhere moves it, and a drag the caller does not take does not.
 */
@Composable
internal fun rememberSyncedSliderState(value: Float, range: ClosedFloatingPointRange<Float>): SliderState =
    remember(range) { SliderState(value, trackRange = range) }.also { it.value = value }

/**
 * What BasicTextField(value = …), deprecated in Compose 1.13, keeps inside, for the fields built
 * on [TextFieldState]: the caller holds the text as a String. Give the field [state] and
 * [handOff] both. Every edit the person makes is handed to the caller the moment it is made, so a
 * keyboard's Done that follows a keystroke finds it already held; whatever the
 * caller then holds is what the field shows, so a refused keystroke goes away, a comma folded to a
 * dot shows as the dot, and a figure set by a button or a loaded record replaces the text.
 */
internal class SyncedText(value: String, var onValueChange: (String) -> Unit) {
    // The cursor starts before the first character, not after the last, as in BasicTextField(value = …).
    val state = TextFieldState(value, TextRange.Zero)

    /** The text the caller was last handed or last answered with. */
    var handed by mutableStateOf(value)
        private set

    val handOff = InputTransformation { hand(asCharSequence().toString()) }

    private fun hand(typed: String) {
        if (typed != handed) {
            handed = typed
            onValueChange(typed)
        }
    }

    /**
     * Puts the caller's [value] in the field, unless the field holds a change the caller has not
     * heard of: an undo or redo, which comes past [handOff], is handed on first.
     *
     * Like BasicTextField(value = …), this puts a caller's text in place keeping the cursor's offset,
     * cut down to the new length. What differs: the keyboard's word in progress ends here, where that
     * field restarts the keyboard, which mostly does the same; a refused keystroke can be drawn for a
     * frame before it goes; and undo forgets what came before, so it can never bring back text the
     * caller replaced or a refused one.
     */
    fun follow(value: String) {
        val shown = state.text.toString()
        when {
            shown == value -> handed = value

            shown != handed -> hand(shown)

            else -> {
                val cursor = state.selection
                state.edit {
                    replace(0, length, value)
                    selection =
                        TextRange(cursor.start.coerceAtMost(value.length), cursor.end.coerceAtMost(value.length))
                }
                state.undoState.clearHistory()
                handed = value
            }
        }
    }
}

/**
 * A [SyncedText] for this field. A field that can come to stand for another item without leaving
 * the composition — a row of a list that is not keyed — passes that item's identity as [keys], so
 * it starts afresh rather than with another item's cursor and history.
 */
@Composable
internal fun rememberSyncedText(value: String, onValueChange: (String) -> Unit, vararg keys: Any?): SyncedText {
    val synced = remember(*keys) { SyncedText(value, onValueChange) }
    // Read here rather than only in the effect: a keystroke the caller refuses changes nothing the
    // caller holds, and it is these two changing that brings the field round to put its text back.
    val settled = synced.state.text.contentEquals(value) && synced.handed == value
    SideEffect {
        synced.onValueChange = onValueChange
        if (!settled) synced.follow(value)
    }
    return synced
}

@Composable
internal fun PwnCard(
    modifier: Modifier = Modifier,
    containerColor: Color = PwnSurfaceElevated,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    // Tonal surfaces distinguish groups without placing a shadow around every block.
    val elevation = CardDefaults.cardElevation(defaultElevation = PwnElevation.level0)
    if (onClick == null) {
        Card(
            modifier = modifier,
            // Shared prominent container shape, also used by the app's sheets.
            shape = RoundedCornerShape(PwnCorners.extraLarge),
            colors = colors,
            elevation = elevation,
        ) { content() }
    } else {
        // A card that opens something answers the press the way the app's buttons do, but only a
        // step: a large block of the page squaring off hard under a thumb would read as a jolt.
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = rememberPressedShape(interaction, PwnCorners.extraLarge, PwnCorners.largeIncreased),
            colors = colors,
            elevation = elevation,
            interactionSource = interaction,
        ) { content() }
    }
}

/**
 * Material's outlined button with an edge that can be seen on this palette.
 *
 * Left on defaults the edge is outlineVariant, a hairline meant to sit a step off its ground, and
 * its label the muted grey: next to a filled button it reads as loose words rather than a second
 * button. Here the edge is `outline`, which DesignContrastTest holds to 3:1 on every surface tier,
 * and the label is the accent, as in Material's baseline outlined button. Disabled, the edge fades
 * to Material's disabled outline, so the two states differ in more than the label.
 */
@Composable
internal fun PwnOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary),
        border = BorderStroke(
            1.dp,
            if (enabled) scheme.outline else scheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA),
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
internal fun PwnIconBubble(
    icon: ImageVector,
    tint: Color,
    background: Color,
    description: String?,
    modifier: Modifier = Modifier,
) {
    // The soft tokens are pale by construction: right on a white card, a glaring white disc on a
    // dark one. In dark mode the bubble is its own tint dropped to a whisper instead — and a tint
    // that is itself dark (the raw indigo family) is lifted toward white first, or it sinks into
    // the disc it tints. Already-light tints pass through, so theme-resolved accents stay as the
    // scheme mixed them.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glyph = if (dark && tint.luminance() < DARK_GLYPH_FLOOR) {
        lerp(tint, MaterialTheme.colorScheme.onSurface, DARK_GLYPH_LIFT)
    } else {
        tint
    }
    val fill = if (dark) glyph.copy(alpha = 0.20f) else background
    Box(
        modifier.size(PwnDimensions.glyphSize).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(PwnDimensions.glyphIconSize), tint = glyph)
    }
}

/**
 * A thing's picture: the drawn set's illustration where the set has one, the line icon in its
 * tinted disc where it does not.
 *
 * The two are mixed on purpose. The set is forty-odd drawings, not a complete icon font, and a
 * screen that held out for a drawing on every row would have none at all — so a row takes the
 * drawing when there is one and keeps its line icon when there is not.
 */
@Composable
internal fun PwnGlyph(
    art: Int?,
    icon: ImageVector,
    background: Color,
    description: String?,
    modifier: Modifier = Modifier,
) {
    if (art == null) {
        // Every drawing in the app wears the navigation bar's resting ink; a kind's own tint is
        // left to the disc behind it.
        PwnIconBubble(icon, PwnMuted, background, description, modifier)
    } else {
        Image(themePainterResource(art), description, modifier.size(PwnDimensions.glyphSize))
    }
}

/** Below this luminance a glyph disappears into a dark disc; the lift carries it back out. */
private const val DARK_GLYPH_FLOOR = 0.35f
private const val DARK_GLYPH_LIFT = 0.45f

internal data class EntryVisual(
    val icon: ImageVector,
    val tint: Color,
    val background: Color,
    /** The drawn set's illustration for this kind, where it has one. */
    val art: Int? = null,
)

/**
 * A lookup rather than a chain of `kind ==` branches: this is a dispatch table, and written as
 * one it neither grows a branch per journal kind nor re-compares the same string ten times.
 * A kind missing from here falls through to [measurementVisual].
 */
private val KIND_VISUALS: Map<String, EntryVisual> = mapOf(
    KIND_WATER to EntryVisual(Icons.Outlined.WaterDrop, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_water),
    KIND_MOOD to EntryVisual(Icons.Outlined.Mood, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_mood),
    KIND_BREATHING to
        EntryVisual(Icons.Outlined.SelfImprovement, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_breathing),
    // LocalCafe is the same cup the home screen's caffeine tile uses.
    KIND_CAFFEINE to EntryVisual(Icons.Outlined.LocalCafe, PwnOrangeRaw, PwnOrangeSoftRaw, R.drawable.ic_art_coffee),
    // One icon for all three cycle kinds on purpose: start, end and log are the same topic.
    KIND_CYCLE to EntryVisual(Icons.Outlined.Bloodtype, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_cycle),
    KIND_CYCLE_END to EntryVisual(Icons.Outlined.Bloodtype, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_cycle),
    KIND_CYCLE_LOG to EntryVisual(Icons.Outlined.Bloodtype, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_cycle),
    KIND_MASTURBATION to
        EntryVisual(Icons.Outlined.FavoriteBorder, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_sex),
    KIND_MORNING_CHECK to
        EntryVisual(Icons.Outlined.FavoriteBorder, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_sex),
    KIND_PLAN to
        EntryVisual(Icons.AutoMirrored.Outlined.EventNote, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_schedule),
    KIND_LABS to EntryVisual(Icons.Outlined.Science, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_blood),
    KIND_ALCOHOL to EntryVisual(Icons.Outlined.LocalBar, PwnOrangeRaw, PwnOrangeSoftRaw, R.drawable.ic_art_alcohol),
    KIND_SMOKING to EntryVisual(Icons.Outlined.SmokingRooms, PwnMutedRaw, PwnBorderRaw, R.drawable.ic_art_smoking),
    KIND_STOOL to EntryVisual(Icons.Outlined.Wc, PwnMutedRaw, PwnBorderRaw, R.drawable.ic_art_toilet),
    // A visit out has its own drawing; the toilet stays on the stool rows, where it answers
    // all three of the kinds that share the [KIND_STOOL] row.
    KIND_URINATION to
        EntryVisual(Icons.Outlined.WaterDrop, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_urination),
    KIND_MED to EntryVisual(Icons.Outlined.Medication, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_doctor),
    KIND_WORK to EntryVisual(Icons.Outlined.Work, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_time),
    KIND_PAIN to EntryVisual(PainIcon, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_pain),
    KIND_SYMPTOM to EntryVisual(SymptomIcon, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_doctor),
    KIND_EAT to EntryVisual(Icons.Outlined.Restaurant, PwnOrangeRaw, PwnOrangeSoftRaw, R.drawable.ic_art_food),
    KIND_ACTIVITY to EntryVisual(Icons.Outlined.FitnessCenter, PwnPurpleRaw, PwnPurpleSoftRaw, R.drawable.ic_art_sport),
    // The workout's own drawing, greyed: the same exercise, not done.
    KIND_NOT_DONE to EntryVisual(Icons.Outlined.FitnessCenter, PwnMutedRaw, PwnBorderRaw, R.drawable.ic_art_sport),
)

internal fun JournalEntry.visual(): EntryVisual = KIND_VISUALS[kind] ?: measurementVisual()

/**
 * A kind's glyph where there is no row to ask — an import's count, a chat's saved record, a chat in
 * the history. The table is keyed by a row, so a bare row stands in.
 */
internal fun kindVisual(kind: String): EntryVisual =
    JournalEntry(day = "", occurredAt = 0L, kind = kind, title = "", calories = 0.0).visual()

/** A MEASUREMENT says what it is by which column is filled, not by its [JournalEntry.kind]. */
private fun JournalEntry.measurementVisual(): EntryVisual = when {
    sleepStartAt != null ->
        EntryVisual(Icons.Outlined.Bedtime, PwnBlueRaw, PwnBlueSoftRaw, R.drawable.ic_art_sleep)

    measurementHeartRateBpm != null ->
        EntryVisual(Icons.Outlined.FavoriteBorder, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_pulse)

    measurementHeightCm != null ->
        EntryVisual(Icons.Outlined.Height, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_height)

    else ->
        EntryVisual(Icons.Outlined.MonitorWeight, PwnIndigoRaw, PwnIndigoSoftRaw, R.drawable.ic_art_weight)
}

@Composable
internal fun PwnSectionTitle(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // A heading to a screen reader as well as to the eye, so a long page can be skimmed by
        // jumping from one section to the next.
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        trailing?.let {
            Text(it, color = PwnAccentInk, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Back, what this page is, and — where the page carries them — the actions it offers on the right.
 * The title is centred on the page whatever stands beside it: the side with less on it is given the
 * width of the other, so nothing on the right leaves as much room as one button would take, and two
 * buttons there do not push the title half a button to the left of the pages opened from them. The
 * one exception is a title the centred gap cannot hold, which [titleSlot] lets use the slack.
 *
 * It is the first thing in a full-screen page, above the page's scroll rather than inside it, so
 * the arrow is reachable however far down the content has been read. The pages that hide the
 * bottom bar all wear it, because on those the system gesture and an undocumented swipe would
 * otherwise be the only ways out. It is Material's small top app bar in height,
 * which holds a title wrapped onto its second line at the default text size; only a larger text
 * size makes the bar taller.
 */
@Composable
internal fun PwnTopBar(title: String, onBack: () -> Unit, content: (@Composable () -> Unit)? = null) {
    Layout(
        content = {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back),
                )
            }
            Text(
                title,
                // The page's name is its first heading, so a screen reader can jump by headings.
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = TOP_BAR_TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) { content?.invoke() }
        },
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.topBarHeight)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val back = measurables[0].measure(loose)
        val trailing = measurables[2].measure(loose)
        val side = maxOf(back.width, trailing.width)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            2 * side + measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        }
        val (start, slot) = titleSlot(measurables[1], width, back.width, trailing.width)
        val text = measurables[1].measure(Constraints.fixedWidth(slot))
        val height = maxOf(constraints.minHeight, back.height, text.height, trailing.height)
        // Relative: in Arabic the arrow stands on the right.
        layout(width, height) {
            back.placeRelative(0, (height - back.height) / 2)
            text.placeRelative(start, (height - text.height) / 2)
            trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
        }
    }
}

/**
 * Where the top bar's title starts and how wide it is.
 *
 * Centred between two sides of equal width, unless that gap cannot hold it: a word wider than the
 * gap would break inside itself, and a title longer than [TOP_BAR_TITLE_LINES] lines of the gap
 * would be cut. Either happens with a long title beside two actions, or at a large font size. Then
 * the title takes everything between the arrow and whatever stands on the other side: a little off
 * centre, and the width centring held back is spent on the words. A title that fits is never moved.
 */
private fun titleSlot(title: IntrinsicMeasurable, width: Int, start: Int, end: Int): Pair<Int, Int> {
    val side = maxOf(start, end)
    val centred = (width - 2 * side).coerceAtLeast(0)
    val longestWord = title.minIntrinsicWidth(Constraints.Infinity)
    val oneLine = title.maxIntrinsicWidth(Constraints.Infinity)
    val fits = longestWord <= centred && oneLine <= centred * TOP_BAR_TITLE_LINES
    return if (fits) side to centred else start to (width - start - end).coerceAtLeast(0)
}

private const val TOP_BAR_TITLE_LINES = 2

/**
 * A choice that does not fit in a row of buttons: the answer on one line, and the list behind a tap.
 *
 * [PwnSegmentedControl] splits the width evenly between its options, so three or four of them leave
 * each label a third or a quarter of the screen. "Просто отслеживать" in ninety density-independent
 * pixels breaks after "отслеживат", and a control that hyphenates its own options mid-word looks
 * broken however carefully it is coloured. Two short options are a segmented control; anything
 * longer is this.
 *
 * [details], when given, is one line per option explaining it. They belong in the list, where the
 * person is choosing, rather than under the field where only the chosen one can be read.
 *
 * [searchLabel], when given, puts a search field over the list, for a list too long to read down:
 * the rows narrow to the ones holding every word typed, by [choiceMatches]. A tap on a row is still
 * the answer, as it is without one. What was typed is forgotten when the list closes. [searchAlso]
 * is one string per option of other words it is found by and never shows: a doctor goes by more
 * names than the one on the row.
 *
 * [placeholder] is what the field says while [selected] is no option at all: a question nobody
 * has answered yet, which the field must not answer for the person.
 */
@Composable
internal fun PwnChoiceField(
    label: String,
    options: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    searchLabel: String? = null,
    searchAlso: List<String> = emptyList(),
    placeholder: String = "",
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val answered = selected in options.indices
    val answer = if (answered) options[selected] else placeholder

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Silent here because the field says it: a label standing beside the control is not part
        // of the control's name, so the field would be announced as its answer alone — "Moderate,
        // button" — with nothing saying what was moderate.
        Text(
            label,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge,
            color = PwnMuted,
        )
        // The clickable Surface rather than a clickable modifier ahead of a plain one, so the
        // pressed and focused layer is clipped to the field's corners.
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = PwnDimensions.minimumTarget)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = answer
                },
            shape = RoundedCornerShape(PwnCorners.medium),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    answer,
                    Modifier.weight(1f),
                    color = if (answered) MaterialTheme.colorScheme.onSurface else PwnMuted,
                    fontWeight = if (answered) FontWeight.SemiBold else FontWeight.Normal,
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PwnMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                var typed by rememberSaveable { mutableStateOf("") }
                val shown = options.indices.filter {
                    searchLabel == null || choiceMatches(options[it], typed, searchAlso.getOrElse(it) { "" })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searchLabel != null) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it.take(CHOICE_SEARCH_MAX_CHARS) },
                            label = { Text(searchLabel) },
                            trailingIcon = { Icon(Icons.Outlined.Search, null, tint = PwnMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // The rows scroll under the search field rather than taking it off the top.
                    Column(
                        Modifier.weight(1f, fill = false).panelVerticalScroll(rememberScrollState()).selectableGroup(),
                    ) {
                        shown.forEach { index ->
                            ChoiceRow(
                                option = options[index],
                                detail = details.getOrNull(index),
                                chosen = index == selected,
                                onClick = {
                                    onSelected(index)
                                    open = false
                                },
                            )
                        }
                        // Said aloud as well as drawn: the rows going is silent, and a screen reader
                        // would hear only the letters typed, never that nothing was left.
                        if (shown.isEmpty()) {
                            Text(
                                stringResource(R.string.choice_search_none),
                                Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                color = PwnMuted,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * True when [option] and its unshown [also] words together hold every word of [typed], anywhere in
 * a word, with case, accents and punctuation ignored as [foldForSearch] ignores them: "невр" finds
 * "Невролог", "card" finds "Cardiologist" without the capital, and "дантист" finds "Стоматолог"
 * when [also] says so. Nothing typed matches everything.
 */
internal fun choiceMatches(option: String, typed: String, also: String = ""): Boolean {
    val folded = foldForSearch("$option $also")
    return foldForSearch(typed).split(' ').filter(String::isNotEmpty).all { it in folded }
}

/** Longer than any option's name: past it a search can only find nothing. */
private const val CHOICE_SEARCH_MAX_CHARS = 60

@Composable
private fun ChoiceRow(option: String, detail: String?, chosen: Boolean, onClick: () -> Unit) {
    Row(
        // The tick alone is silent to a screen reader; selectable is what announces the answer.
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.minimumTarget)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option,
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            }
        }
        // The tick marks the answer in place. An empty slot keeps every row's text on one margin.
        Box(Modifier.size(20.dp)) {
            if (chosen) {
                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * What happens to what this screen records, behind a question mark at the foot of it.
 *
 * Three or four paragraphs saying where the record goes. They are worth reading once and never
 * again, and the add screens they belong to are screens somebody opens to tap one thing, so they
 * are a question mark and a page when it is asked rather than grey type under the buttons.
 */
@Composable
internal fun DataNoteLink(vararg bodyRes: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.data_note_link)
    Row(
        // heightIn sits before clickable so the 48dp is the pointer area itself and not a box
        // drawn around it. A raw Row.clickable is given none of the minimum target that Material's
        // own components apply, and the 17dp glyph inside 10dp of padding would measure 37dp
        // tall against Android's 48dp floor.
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.minimumTarget)
            .clickable(role = Role.Button) { open = true }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            tint = PwnMuted,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(
                    Modifier.panelVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    bodyRes.forEach { body ->
                        Text(
                            stringResource(body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/**
 * A question mark in a circle beside what it explains, and the explanation in a window when asked.
 *
 * What a figure means, how it was counted and what research says about it is worth reading once.
 * Printed open under what it explains, it would be grey paragraphs on every visit, and the Work
 * page would read as a wall of them. Figures, verdicts and why something cannot be shown yet stay
 * on the page; only the explanation goes behind the circle, [title] naming what it is about.
 */
@Composable
internal fun HelpDot(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.common_what_is_this),
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.panelVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/**
 * A card's title with its explanation behind a [HelpDot] right after the words, rather than as a
 * paragraph under its figure.
 */
@Composable
internal fun HelpTitle(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), style = style)
        HelpDot(title, content)
    }
}

/**
 * A list of ticks behind one line, the way [PwnChoiceField] puts a list of answers behind one.
 *
 * Six ticks laid open take a card to themselves on a screen whose job is one tap, and they are
 * empty on most visits. Collapsed, the line says what has been ticked — which is the only part
 * worth seeing once the choosing is done.
 *
 * The ticks open in a menu when the line is tapped. A dialog would take the whole screen, and with
 * it the button that files the record — the one thing a person wants in view while ticking. Rows
 * opened in the column underneath would keep the form on screen but would not be a dropdown at
 * all: nothing would dismiss them except the field itself, so a tap anywhere else would leave them
 * hanging open. A menu is the shape that behaves. It hangs off the field, it is the field's
 * width, and the popup underneath it closes on a tap outside or on Back without this code owning
 * a single gesture — which is exactly what a column drawn in the page can never do.
 *
 * Ticking is not choosing: a tap on a row toggles it and nothing else, so the panel stays open
 * for the next one and closing stays the person's decision.
 *
 * [optionEnabled] says which rows can be ticked now — all of them unless told otherwise; a list that
 * takes at most two greys the rest once two are ticked, and a ticked row always stays open to be
 * taken off. [onClosed] hears the panel shut, however it was shut: for a question whose words were
 * carried over, opening the list and closing it again is the person looking at them and keeping them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PwnTickField(
    label: String,
    options: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionEnabled: (Int) -> Boolean = { true },
    onClosed: (() -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val closed by rememberUpdatedState(onClosed)
    fun close() {
        if (open) {
            open = false
            closed?.invoke()
        }
    }
    val chosen = options.filterIndexed { index, _ -> index in selected }
    val summary = chosen.joinToString(stringResource(R.string.list_comma))
        .ifEmpty { stringResource(R.string.common_none) }
    // One radius for the field and the panel that drops out of it: the two read as one object.
    val shape = RoundedCornerShape(PwnCorners.medium)
    // The anchor draws no state layer of its own, so a field reached by keyboard or Switch Access
    // would show nothing to say it had arrived. Focus deepens the edge, as Material's outlined fields do.
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Silent here because the field says it, as in [PwnChoiceField].
        Text(
            label,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge,
            color = PwnMuted,
        )
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (it) open = true else close() }) {
            Surface(
                // menuAnchor is what the panel measures and positions itself against, and it
                // brings the tap that opens and shuts it, plus the "expand"/"collapse" a screen
                // reader needs, so there is deliberately no clickable of our own here. The
                // not-editable flavour is the one for a field that is read rather than typed in.
                // focusable() because menuAnchor does not add one: it brings a key handler that
                // opens the list on Enter or the D-pad, and without something to hold focus that
                // handler can never run, which leaves the field unreachable by a hardware keyboard
                // or Switch Access. Material's own anchor is a text field, which is focusable by
                // being one; this is a Surface, which is not.
                // The anchor merges nothing by itself, so the ticked words would be a node apart
                // from the control that opens them; merged and named by the label, the field is read
                // as one thing — what it asks, then what has been ticked.
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = summary
                    }
                    .focusable(interactionSource = focus)
                    .fillMaxWidth()
                    .border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape,
                    ),
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        summary,
                        Modifier.weight(1f),
                        color = if (chosen.isEmpty()) PwnMuted else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (chosen.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                    )
                    Icon(
                        if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = PwnMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // The scope's own menu rather than the plain [DropdownMenu]. Two things come with it,
            // and both would be wrong without it. It clears FLAG_NOT_TOUCH_MODAL, so the tap that
            // dismisses the panel goes on through to whatever it landed on — with the plain menu
            // that tap is swallowed, and filing a record would take two presses of Add: one to shut
            // the list, one to mean it. And it positions with the provider its own height budget was
            // measured for, so the panel hangs off the field instead of being pinned to a window
            // edge across it when the list is long or the field sits low. It is the field's width
            // by default, so nothing here has to ask for that.
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = ::close,
                shape = shape,
                containerColor = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                options.forEachIndexed { index, option ->
                    // A rule between the rows: six lines of plain text with nothing between them
                    // read as one paragraph, and the tick at the end of each is the only thing
                    // saying where one answer stops and the next starts.
                    if (index > 0) HorizontalDivider(color = PwnBorder)
                    // The app's own ticked row rather than a menu item, inset to the margin a menu
                    // keeps for its text. It already announces itself as a toggle holding a state,
                    // which is what a row that can be ticked and unticked owes a screen reader; a
                    // menu item would announce a button that fires once.
                    PwnTickRow(
                        label = option,
                        checked = index in selected,
                        onToggle = { onToggle(index) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        enabled = optionEnabled(index),
                    )
                }
            }
        }
    }
}

/**
 * A row that can be ticked, for a list where more than one answer is allowed.
 *
 * The tick is the mark, not a box with a tick in it: a square outline on every row draws five
 * boxes where the reader is choosing between five words, and the words are the thing being read.
 * Same shape as the single-answer row above it, so a list of either kind reads the same way.
 * Switched off ([enabled] false), its words fade by Material's disabled opacity and a screen reader
 * hears it as unavailable.
 */
@Composable
internal fun PwnTickRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        // The tick alone is silent to a screen reader; toggleable is what announces the answer.
        modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = when {
                    checked -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = TICK_ROW_DISABLED_ALPHA)
                },
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            )
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
        }
        Box(Modifier.size(20.dp)) {
            if (checked) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Material's opacity for the words of a switched-off control. */
private const val TICK_ROW_DISABLED_ALPHA = 0.38f

/**
 * A switch that looks like something a thumb operates.
 *
 * Material's own colours draw the off switch as a pale track one tonal step from the card, with
 * its edge and its knob in pale tones on top of it: three near-invisible things stacked on each
 * other, and after dusk the knob matches its track outright. What a person sees is one dot
 * floating on a card — not a control, a decoration.
 *
 * WCAG 1.4.11 asks 3:1 of the boundary of a control and of whatever identifies its state. What is
 * used here clears that in both schemes, on the card the switches sit on and on the page the one
 * bare row sits on. DesignContrastTest holds these pairs, so a palette change that breaks one fails
 * there rather than here:
 *
 * | | light, on a card | light, on the page | dark, on a card |
 * | --- | --- | --- | --- |
 * | on — track [PwnBlueInk] | 4.67:1 | 5.34:1 | 5.80:1 |
 * | on — knob [PwnCanvas] on the track | 5.34:1 | 5.34:1 | 8.23:1 |
 * | off — edge and knob [PwnMuted] | 4.90:1 | 5.61:1 | 6.02:1 |
 *
 * The on state is the same fill the rest of the app already uses for "this is the answer given" —
 * see [PwnSegmentedControl], which carries the page colour on the same blue for the same reason.
 * The off state is drawn as an outline rather than a second pale pill: a switch that is off has
 * nothing to fill, and the edge is what says there is a control there at all, standing as clear of
 * the card as the on track does.
 *
 * Disabled keeps the same drawing at [DISABLED_ALPHA] rather than swapping in a paler colour, and
 * every colour the control draws is given rather than the six enabled ones: the disabled set does
 * not follow from them, so left alone it falls back to Material's own near-white, and the two
 * disabled states this app can actually reach — the app lock with no device credential, and "block
 * screenshots" forced on while the lock is on — come out as the blank the rest of this note is
 * about. The one still-checked of those two keeps its blue, which is the part that says it is on.
 * A disabled switch has to stay legible: one sits under a line explaining why it cannot be turned
 * on, and that sentence is about something a reader has to be able to find.
 */
@Composable
internal fun PwnSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val on = PwnSelectionColor
    val off = PwnMuted
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = PwnSelectionContent,
            checkedTrackColor = on,
            checkedBorderColor = on,
            uncheckedThumbColor = off,
            // The card shows through, so the switch sits on whatever it was placed on and the edge
            // is the whole of its shape. A filled off track would be a second pale rectangle to
            // tell apart from the card behind it, which is what Material's defaults draw.
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = off,
            disabledCheckedThumbColor = PwnSelectionContent,
            disabledCheckedTrackColor = on.copy(alpha = DISABLED_ALPHA),
            disabledCheckedBorderColor = on.copy(alpha = DISABLED_ALPHA),
            disabledUncheckedThumbColor = off.copy(alpha = DISABLED_ALPHA),
            disabledUncheckedTrackColor = Color.Transparent,
            disabledUncheckedBorderColor = off.copy(alpha = DISABLED_ALPHA),
        ),
    )
}

/**
 * What a switch is nearly always part of: a line of words saying what it does, and the switch at
 * the end of it.
 *
 * The whole row answers the tap. A switch is 52dp wide at the far edge of a full-width card, and
 * the sentence explaining it — the part a reader is actually looking at — would do nothing at all
 * when touched; on a settings page of six of them that is six small targets and a lot of dead text.
 * [toggleable] also gives the row one spoken node with a state, where a Row and a Switch beside it
 * are read out as two unrelated things.
 *
 * [enabled] false stops the row as well as the switch, so a disabled control cannot be worked
 * around by hitting the words instead.
 */
@Composable
internal fun PwnSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle? = null,
    /** What the setting is for, behind a [HelpDot] after the title rather than printed under it. */
    help: String? = null,
    /**
     * The card's own inset, taken here rather than by the caller's [modifier] so that it is applied
     * after the tap and not before it. Passed in from outside, the padding would sit between the
     * card and the touch area: the row would answer a tap on the words and ignore one on the 16dp
     * of card around them, and the ripple would come out as a rectangle inset from a rounded card.
     */
    contentPadding: PaddingValues = PaddingValues(),
) {
    Row(
        modifier
            .fillMaxWidth()
            // The switch inside hands its click to the row and so enforces no target of its own.
            .heightIn(min = PwnDimensions.minimumTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    style =
                    titleStyle ?: MaterialTheme.typography.titleMedium,
                )
                help?.let { HelpDot(title) { Text(it) } }
            }
            body?.let {
                Text(it, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Null rather than the row's own lambda: the row carries the click and the state, and a
        // switch that also took them would be announced twice and toggled twice by one tap.
        PwnSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Material's own disabled opacity, named once so the switch and anything after it agree. */
private const val DISABLED_ALPHA = 0.38f

/** Material's disabled outline: onSurface at this opacity, for a control drawn by its edge. */
private const val DISABLED_OUTLINE_ALPHA = 0.12f

/**
 * The shell every long choice in the app wears: a panel over the page, what it is at the top with
 * the way out beside it, the list in its own scroll, and — where there is something to confirm —
 * one button under it.
 *
 * Over the page rather than inside it because these lists are longer than the page they are opened
 * from, and a list that pushed the buttons under it off the bottom of a scroll would have to be
 * scrolled past on every visit by everybody who did not want it. The report's own pickers use it,
 * and so do the allergens: fourteen tick rows standing open on a page are a wall of boxes in front
 * of somebody who reacts to none of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PwnPickerSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    action: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val scrolls = remember { PanelScrollState() }
    RegisterChildPanel(scrolls)
    CompositionLocalProvider(LocalPanelScrollState provides scrolls) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = Modifier.swipeDownToClose(
                onDismiss,
                enabled = { scrolls.closesBySwipe },
            ) {
                scrolls.canStartClose
            },
            sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
            sheetGesturesEnabled = false,
            containerColor = PwnSurfaceElevated,
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(subtitle, color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                    ) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).panelVerticalScroll(rememberScrollState()),
                ) { content() }
                action?.invoke()
            }
        }
    }
}

/** Widths at which the grid takes another column. Read as: under the first, two tiles fit. */
private val TILE_BREAKPOINTS = listOf(340.dp, 520.dp)

/**
 * Square tiles laid across the width: a drawing, its name, and what one tap of it records.
 *
 * Stacked as full-width rows these read as a settings list — a column of near-identical lines
 * where the only thing telling them apart is a word. As tiles the drawing does that work, and the
 * row holds as many as the screen is wide enough for: two on a small phone, four on a tablet.
 */
@Composable
internal fun PwnTileGrid(count: Int, modifier: Modifier = Modifier, tile: @Composable (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = 2 + TILE_BREAKPOINTS.count { maxWidth >= it }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (first in 0 until count step columns) {
                val last = minOf(first + columns, count)
                // Measured to the tallest tile, so a detail that wraps onto a second line lengthens
                // its whole row rather than leaving one tile taller than its neighbours.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (index in first until last) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { tile(index) }
                    }
                    // A short last row keeps its tiles the width the full rows give them rather
                    // than stretching two of them across the screen.
                    repeat(columns - (last - first)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One tile of [PwnTileGrid].
 *
 * [art] is nullable for the same reason [PwnGlyph] exists: the drawn set does not cover every
 * choice a palette offers, and a tile without a drawing keeps a line icon in its disc rather than
 * borrowing a drawing that means something else.
 *
 * Most tiles log something in one tap and are plain buttons. [selected] not null makes the tile one
 * answer among several, announced as selected or not. [expanded] not null is for a tile that opens
 * something below the grid instead, announced as expanded or collapsed. A lit tile says so by more
 * than its tint — a heavier edge, a heavier name, and a tick on a chosen answer — because the tint
 * alone stands barely off the page.
 */
@Composable
internal fun PwnTile(
    art: Int?,
    title: String,
    detail: String?,
    icon: ImageVector = Icons.Outlined.Circle,
    enabled: Boolean = true,
    selected: Boolean? = null,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lit = selected == true || expanded == true
    val interaction = remember { MutableInteractionSource() }
    // A one-tap tile answers the press the way the app's buttons do.
    val shape = rememberPressedShape(interaction, PwnCorners.largeIncreased)
    val color = if (lit) scheme.primaryContainer else scheme.surface
    val border = tileBorder(lit, enabled)
    val modifier = Modifier.fillMaxWidth().fillMaxHeight().tileSemantics(selected, expanded, onClick)
    val content: @Composable () -> Unit = {
        TileContent(art, icon, title, detail, enabled, lit, ticked = selected == true && expanded == null)
    }
    if (selected != null && expanded == null) {
        Surface(
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = color,
            border = border,
            interactionSource = interaction,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = color,
            border = border,
            interactionSource = interaction,
            content = content,
        )
    }
}

/**
 * What a tile is to a screen reader. A selectable one is said by the selectable Surface itself; a
 * tile that opens something says whether it is open, and offers the one of expand or collapse
 * that applies; the rest are buttons.
 */
@Composable
private fun Modifier.tileSemantics(selected: Boolean?, expanded: Boolean?, onClick: () -> Unit): Modifier {
    if (expanded == null) {
        return if (selected == null) semantics { role = Role.Button } else this
    }
    val state = stringResource(if (expanded) R.string.a11y_state_expanded else R.string.a11y_state_collapsed)
    return semantics {
        role = Role.Button
        stateDescription = state
        if (expanded) {
            collapse {
                onClick()
                true
            }
        } else {
            expand {
                onClick()
                true
            }
        }
    }
}

/**
 * A lit tile's edge is the accent and twice the width, so it is told apart by shape as well as hue.
 * Disabled — the few seconds after a tap while it is shut — the edge fades as Material fades a
 * disabled outline, and a lit edge keeps its accent at the disabled opacity.
 */
@Composable
private fun tileBorder(lit: Boolean, enabled: Boolean): BorderStroke {
    val scheme = MaterialTheme.colorScheme
    val edge = if (lit) scheme.primary else scheme.outline
    val color = when {
        enabled -> edge
        lit -> edge.copy(alpha = DISABLED_ALPHA)
        else -> scheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
    }
    return BorderStroke(if (lit) 2.dp else 1.dp, color)
}

@Composable
private fun TileContent(
    art: Int?,
    icon: ImageVector,
    title: String,
    detail: String?,
    enabled: Boolean,
    lit: Boolean,
    ticked: Boolean,
) {
    val disabledInk = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A lit tile draws in the accent set, the way the bar's current stop does; every other
            // tile wears the app's quiet icon set.
            CompositionLocalProvider(
                LocalThemeIconColors provides if (lit) LocalAccentIconColors.current else LocalThemeIconColors.current,
            ) {
                PwnGlyph(
                    art,
                    icon,
                    PwnIndigoSoft,
                    null,
                    Modifier.size(48.dp).alpha(if (enabled) 1f else DISABLED_ALPHA),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledInk,
                textAlign = TextAlign.Center,
                maxLines = TILE_TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            // Room to wrap rather than a single line: the detail is what one tap records, or where
            // it goes, and cut short in a longer language or a larger font it would say neither.
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) PwnMuted else disabledInk,
                    textAlign = TextAlign.Center,
                    maxLines = TILE_DETAIL_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ticked) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
                modifier = Modifier.align(Alignment.TopEnd).padding(PwnSpacing.small).size(18.dp),
            )
        }
    }
}

private const val TILE_TITLE_LINES = 3
private const val TILE_DETAIL_LINES = 2

/**
 * Material's filter chip, with the cues its state is read by kept legible on this palette.
 *
 * Left on defaults the chip is outlined in outlineVariant, a tonal hairline that all but vanishes on
 * a card, and a chosen chip differs only by a secondaryContainer fill one tonal step off that card.
 * Here an unchosen chip is outlined in `outline` (3:1 on every surface tier, DesignContrastTest),
 * and a chosen one is filled and carries Material's check mark — the cue that does not depend on
 * colour. A chip with its own [leadingIcon], or a removable one with a [trailingIcon], keeps that
 * icon instead of the check.
 */
@Composable
internal fun PwnFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val leading = leadingIcon ?: Icons.Outlined.Check.takeIf { selected && trailingIcon == null }
    FilterChip(
        selected = selected,
        onClick = onClick,
        // Keep space inside the border when a long answer wraps onto several lines.
        label = { Text(label, modifier = Modifier.padding(vertical = PwnSpacing.small)) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leading?.let { { Icon(it, null, Modifier.size(FilterChipDefaults.IconSize)) } },
        trailingIcon = trailingIcon?.let { { Icon(it, null, Modifier.size(FilterChipDefaults.IconSize)) } },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = scheme.onSurfaceVariant,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer,
            selectedLeadingIconColor = scheme.onSecondaryContainer,
            selectedTrailingIconColor = scheme.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = scheme.outline,
        ),
    )
}

/**
 * One answer of a question that takes one, drawn as [PwnFilterChip] is but spoken as a choice.
 *
 * Material's filter chip is a checkbox to a screen reader, which is right where several answers
 * can stand together and wrong where picking one replaces the last: each answer would be announced
 * as a box of its own to tick, with nothing saying only one applies. This one is a radio button. The
 * row holding a set of them marks itself with selectableGroup, so the answers are counted as one
 * question. [leadingIcon] is a small muted accent drawn before the label while it is not chosen (a
 * chat's mark on the answer the person's words point at); chosen, it carries the tick instead.
 */
@Composable
internal fun PwnChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(PwnCorners.small)
    val ink = when {
        !enabled -> scheme.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    val edge = if (enabled) scheme.outline else scheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
    Row(
        modifier
            // Wrapped labels grow with their padding; the target around a short chip is 48dp.
            .minimumInteractiveComponentSize()
            .heightIn(min = FilterChipDefaults.Height)
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, edge, shape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(
                start = if (selected) PwnSpacing.small else PwnSpacing.large,
                top = PwnSpacing.small,
                end = PwnSpacing.large,
                bottom = PwnSpacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The chosen answer carries the same tick as a chosen filter chip: a cue beside the fill.
        if (selected) {
            Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize), tint = ink)
            Spacer(Modifier.width(PwnSpacing.small))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(FilterChipDefaults.IconSize), tint = PwnMuted)
            Spacer(Modifier.width(PwnSpacing.small))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/**
 * A row of answers to one question: a pale track with a filled pill standing on the answer given.
 *
 * The pill is a solid fill rather than a tinted panel, which would need an outline to be legible
 * at all. [PwnBlueInk] carries the page colour as its text: the pill stands 5.44:1 off the track in
 * daylight and 7.59:1 after dusk, and its word 5.34:1 and 8.23:1 on it — DesignContrastTest holds
 * both pairs. The answers not given stay in plain ink, with a hairline between neighbours so three
 * words do not read as one sentence.
 */
@Composable
internal fun PwnSegmentedControl(
    options: List<Pair<String, ImageVector?>>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = RoundedCornerShape(percent = 50)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = track,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // Which segment is on is drawn in colour and weight only, so the state has to be said as
        // well: selectable carries it to a screen reader, selectableGroup says the segments are one
        // choice rather than several buttons.
        Row(
            Modifier.selectableGroup().padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                val active = index == selected
                // Between two answers neither of which is given; beside the pill it would be a
                // second edge drawn against the first.
                if (index > 0 && !active && index - 1 != selected) {
                    Box(Modifier.width(1.dp).height(20.dp).background(PwnBorder))
                }
                Row(
                    Modifier.weight(1f)
                        // Clipped, so the pressed layer of a segment is the pill it would become
                        // rather than a rectangle across the rounded track.
                        .clip(track)
                        .background(if (active) PwnSelectionColor else Color.Transparent)
                        // A floor under the segment: its height is padding plus the taller of the
                        // 21dp icon and the label's line box, and the label is labelLarge (20dp
                        // line) rather than the inherited bodyLarge.
                        .heightIn(min = PwnDimensions.minimumTarget)
                        .selectable(selected = active, role = Role.Tab) { onSelected(index) }
                        // 8dp rather than 10dp of side padding: three segments with icons would leave
                        // the word 58.7dp on a 360dp screen.
                        .padding(vertical = 13.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    option.second?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = if (active) PwnSelectionContent else PwnMuted,
                            modifier = Modifier.size(21.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(7.dp))
                    }
                    Text(
                        option.first,
                        // labelLarge rather than the inherited bodyLarge: at 16sp the Russian
                        // "Показатели" wants 85.4dp where a three-way control has under 63dp to
                        // give, and it would break mid-word; at 14sp it asks 74.7dp and fits.
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) PwnSelectionContent else PwnInk,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RowScope.PwnCompactHealthCard(
    art: Int?,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    // The clickable Surface rather than a clickable modifier ahead of a plain one, so the pressed
    // layer is clipped to the card's corners; the role is said outright, since that Surface
    // announces no role of its own.
    Surface(
        onClick = onClick,
        // fillMaxHeight so a row measured at IntrinsicSize.Min gives both cards the taller height
        // rather than leaving the shorter description with a shorter card.
        modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.Button },
        shape = RoundedCornerShape(PwnCorners.largeIncreased),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
    ) {
        // The picture over the words rather than beside them. Two of these share a phone's width,
        // and side by side the glyph and the chevron would leave the title about ninety pixels — enough
        // for "Mood" and not for "Breathing", which would come out as "Breathi" over "ng".
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PwnGlyph(
                art,
                icon,
                MaterialTheme.colorScheme.surface,
                null,
                Modifier.size(44.dp),
            )
            // Wrapping rather than cut: it is the card's only name, and in a longer language or at
            // a large font size one line would cut it short. The row already evens the two cards'
            // heights, so a title on two lines costs its neighbour nothing.
            Text(
                title,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                subtitle,
                // Softened so the title leads, but no further than a 12sp line can stand: at 85%
                // it measures 9.02:1 light and 7.98:1 dark on the card's primaryContainer, and
                // DesignContrastTest holds it to 4.5:1.
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = COMPACT_SUBTITLE_ALPHA),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** How strongly a [PwnCompactHealthCard]'s description is drawn against its title's ink. */
internal const val COMPACT_SUBTITLE_ALPHA = 0.85f

internal val FoodIcon = Icons.Outlined.Restaurant
internal val ActivityIcon = Icons.Outlined.FitnessCenter
internal val WalkIcon = Icons.AutoMirrored.Outlined.DirectionsRun
internal val WeightIcon = Icons.Outlined.MonitorWeight
internal val HeightIcon = Icons.Outlined.Straighten
internal val HeartIcon = Icons.Outlined.FavoriteBorder
internal val SleepIcon = Icons.Outlined.Bedtime

/**
 * A section unfolding under the control that asked for it, rather than popping in — a form's, or a
 * chat's reply's: the list of areas under the body map, the map under the chips that offered it.
 *
 * It grows on the spatial spring and fades on the effects one, once for each open or shut and
 * never on its own. The spacing above the section belongs inside [content], so that it folds away
 * with the section instead of being left behind as a gap.
 */
@Composable
internal fun InlineReveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(PwnMotion.spatial()) + fadeIn(PwnMotion.effects()),
        exit = shrinkVertically(PwnMotion.spatial()) + fadeOut(PwnMotion.effects()),
    ) {
        content()
    }
}

/** A charts page with nothing yet to draw: the one sentence, centred, where the charts would be. */
@Composable
internal fun ChartsEmptyNotice(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.intimate_charts_empty),
            style = MaterialTheme.typography.titleMedium,
            color = PwnMuted,
            textAlign = TextAlign.Center,
        )
    }
}
