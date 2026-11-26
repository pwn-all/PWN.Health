package com.pwnall.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun ThemeSettingsSection() {
    val preferences = LocalThemePreferences.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Opens on the palette the app is drawn in now: opened on the light one at night, an edit
    // would change only the preview and nothing around it.
    val darkNow = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(darkNow) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val palette = preferences.palette(dark)
    val expandedState = stringResource(R.string.a11y_state_expanded)
    val collapsedState = stringResource(R.string.a11y_state_collapsed)
    PwnCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    // On the header itself, open or shut shows only in the chevron, which a screen
                    // reader does not see: the state is said in words, and the header offers to
                    // open or close the section as a named action.
                    .semantics {
                        stateDescription = if (expanded) expandedState else collapsedState
                        if (expanded) {
                            collapse {
                                expanded = false
                                true
                            }
                        } else {
                            expand {
                                expanded = true
                                true
                            }
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // How the palettes are kept is read once, behind the question mark, rather than
                    // under the light-and-dark switch every time the card is opened.
                    HelpTitle(stringResource(R.string.theme_title)) {
                        Text(stringResource(R.string.theme_saved_note))
                    }
                    Text(
                        stringResource(R.string.theme_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PwnMuted,
                )
            }
            if (expanded) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PwnSegmentedControl(
                        options = listOf(
                            stringResource(R.string.theme_light) to null,
                            stringResource(R.string.theme_dark) to null,
                        ),
                        selected = if (dark) 1 else 0,
                        onSelected = { dark = it == 1 },
                    )
                    ThemePreview(palette, dark)
                    ThemeColorRole.entries.forEach { role ->
                        if (role == ThemeColorRole.ICON) {
                            HorizontalDivider()
                            Text(stringResource(R.string.theme_icons), style = MaterialTheme.typography.titleSmall)
                        }
                        ThemeColorRow(role, palette.color(role, dark)) { editing = role.name }
                    }
                    TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.theme_reset))
                    }
                }
            }
        }
    }
    editing?.let { name ->
        val role = ThemeColorRole.valueOf(name)
        ThemeColorPicker(
            role = role,
            initialColor = palette.color(role, dark),
            onDismiss = { editing = null },
            onSave = { color, updateAll ->
                preferences.save(dark, palette.edit(role, color, dark, updateAll))
                editing = null
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.theme_reset)) },
            text = {
                Text(stringResource(if (dark) R.string.theme_reset_dark_body else R.string.theme_reset_light_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    preferences.save(dark, ThemePalette())
                    confirmReset = false
                }) { Text(stringResource(R.string.theme_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun ThemeColorRow(role: ThemeColorRole, color: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(Color(color), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(role.label), style = MaterialTheme.typography.bodyLarge)
            Text(themeHex(color), style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        }
    }
}

@Composable
private fun ThemePreview(palette: ThemePalette, dark: Boolean) {
    CompositionLocalProvider(LocalThemeIconColors provides palette.iconColors(dark)) {
        MaterialTheme(colorScheme = palette.colorScheme(dark)) {
            val scheme = MaterialTheme.colorScheme
            Surface(
                color = scheme.background,
                shape = RoundedCornerShape(PwnCorners.large),
                border = BorderStroke(1.dp, scheme.outline),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.theme_preview), color = scheme.onBackground)
                    Surface(color = scheme.surface, shape = RoundedCornerShape(PwnCorners.medium)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Image(themePainterResource(R.drawable.ic_art_home), null, Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.theme_text), color = scheme.onSurface)
                                Text(
                                    stringResource(R.string.theme_muted_text),
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                            Surface(
                                color = color,
                                shape = RoundedCornerShape(PwnCorners.small),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Aa", Modifier.padding(10.dp), color = themeContentColor(color))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorPicker(
    role: ThemeColorRole,
    initialColor: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Boolean) -> Unit,
) {
    val initialHsv = remember(initialColor) { themeHsv(initialColor) }
    var hue by rememberSaveable(role) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(role) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable(role) { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable(role) { mutableStateOf(themeHex(initialColor)) }
    // An explicit choice for this edit only; reopening a picker always starts with Update All off.
    var updateAll by rememberSaveable(role) { mutableStateOf(false) }
    val parsed = parseThemeHex(hex)
    fun updateHex() {
        hex = themeHex(Color.hsv(hue, saturation, brightness).toArgb())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(role.label)) },
        text = {
            Column(
                Modifier.panelVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeColorWheel(hue, saturation, brightness) { selectedHue, selectedSaturation ->
                    hue = selectedHue
                    saturation = selectedSaturation
                    updateHex()
                }
                ThemeColorSlider(
                    label = stringResource(R.string.theme_brightness),
                    value = brightness,
                    colors = listOf(Color.Black, Color.hsv(hue, saturation, 1f)),
                    onValueChange = {
                        brightness = it
                        updateHex()
                    },
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        parseThemeHex(value)?.let { color ->
                            val hsv = themeHsv(color)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    label = { Text(stringResource(R.string.theme_hex)) },
                    supportingText = { if (parsed == null) Text(stringResource(R.string.theme_hex_error)) },
                    isError = parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (role == ThemeColorRole.MAIN_ACCENT) {
                    PwnSwitchRow(
                        title = stringResource(R.string.theme_update_all),
                        body = stringResource(R.string.theme_update_all_body),
                        checked = updateAll,
                        onCheckedChange = { updateAll = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let { onSave(it, updateAll) } }, enabled = parsed != null) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The wheel chooses hue and saturation; this is its only additional colour adjustment. */
@Composable
private fun ThemeColorSlider(
    label: String,
    value: Float,
    colors: List<Color>,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    // The slider runs from its start edge, which is the right in Arabic; the track under it is a
    // plain left-to-right gradient, so it is turned round to keep the dark end under the low values.
    val stops = if (LocalLayoutDirection.current == LayoutDirection.Rtl) colors.reversed() else colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(8.dp)
                    .background(Brush.horizontalGradient(stops), CircleShape),
            )
            Slider(
                state = rememberSyncedSliderState(value, range),
                onValueChange = onValueChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}
