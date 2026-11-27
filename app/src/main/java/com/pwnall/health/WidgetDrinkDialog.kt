package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WidgetDrinkDialog(
    profile: UserProfile,
    kind: String,
    initialJournal: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val todayDate = rememberToday()
    val today = todayDate.toString()
    val seed =
        remember(initialJournal) { journalPrefill(initialJournal)?.takeIf { it.type == ChatJournalType.WATER_LOG } }
    val proposedDay = seed?.prefillDay(todayDate)?.toString() ?: today
    val journal by remember(proposedDay) { db.dao().journalOn(proposedDay) }.collectAsStateWithLifecycle(emptyList())
    val waterMl = journal.filter { it.kind == KIND_WATER }.sumOf { it.servingG }.toInt()
    val caffeineMg = dayCaffeineMg(journal).toInt()
    val imperial = profile.imperial
    val scope = rememberCoroutineScope()
    val showWaterDialog = kind == WidgetRoute.WATER
    val showCaffeineDialog = kind == WidgetRoute.CAFFEINE
    var amount by rememberSaveable(initialJournal) {
        mutableStateOf(seed?.prefillWaterMl()?.asWeightText().orEmpty())
    }
    var saving by remember { mutableStateOf(false) }
    fun saveWater(portion: Int) {
        if (saving) return
        saving = true
        scope.launch {
            withContext(NonCancellable) { db.logWater(waterEntry(resources, portion, proposedDay)) }
            onDismiss()
        }
    }
    if (showWaterDialog) {
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.water_title)) },
            text = {
                // The text slot does not scroll by itself: at a large font in a landscape window
                // the lower row of glasses would otherwise sit beyond the dialog's edge.
                Column(
                    Modifier.panelVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        waterOfTargetText(resources, waterMl, waterTargetMl(profile.weightKg), imperial),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (seed != null) {
                        Text(proposedDay, style = MaterialTheme.typography.labelMedium)
                        Text(seed.prefillNote(), style = MaterialTheme.typography.bodyMedium)
                        NumberField(amount, {
                            amount = it.decimalInput()
                        }, stringResource(R.string.water_title), stringResource(R.string.unit_ml), decimal = true)
                    }
                    // Four buttons in one row would wear the labels down to their digits; two rows of
                    // cards fit the words, and the drop grows with the glass it stands for.
                    WATER_PORTIONS_ML.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { portion ->
                                Surface(
                                    onClick = {
                                        if (seed == null) saveWater(portion) else amount = portion.toString()
                                    },
                                    shape = RoundedCornerShape(PwnCorners.medium),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(
                                        Modifier.padding(vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.WaterDrop,
                                            null,
                                            tint = PwnMuted,
                                            modifier = Modifier.size((16 + portion / 45).dp),
                                        )
                                        Text(
                                            formatVolume(resources, portion, imperial),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (seed != null) {
                    val value = amount.toDoubleOrNull()?.takeIf {
                        it.isFinite() && it in 1.0..Int.MAX_VALUE.toDouble() &&
                            it % 1 == 0.0
                    }
                    TextButton(onClick = { value?.let { saveWater(it.toInt()) } }, enabled = value != null && !saving) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showCaffeineDialog) {
        // Two steps in one dialog: pick the drink, then size its cup with the arrows. The raw
        // milligram field stays for anything with a printed label. Saveable, as the water amount is,
        // so a turn of the phone keeps the cup being sized and the milligrams being typed.
        var sizingDrink by rememberSaveable { mutableStateOf<CaffeineDrink?>(null) }
        var sizeIndex by rememberSaveable { mutableIntStateOf(CUP_SIZE_DEFAULT_INDEX) }
        var customCaffeineMg by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                onDismiss()
            },
            title = {
                // What the kilocalories on the tiles and the size count, and what they leave out,
                // sits behind the circle beside the title on both steps rather than under a figure.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (sizingDrink == null) R.string.caffeine_title else R.string.caffeine_size_title,
                        ),
                        Modifier.weight(1f, fill = false),
                    )
                    HelpDot(stringResource(R.string.caffeine_energy_help_title)) {
                        Text(stringResource(R.string.caffeine_energy_help_body))
                    }
                }
            },
            text = {
                val drink = sizingDrink
                if (drink == null) {
                    // The field and its OK button are the last children here, and an AlertDialog's
                    // text slot does not scroll: with the keyboard up the dialog shrinks, and
                    // unscrolled the two of them would be measured at zero height, leaving the
                    // person typing into a control that is no longer on screen, with no way to the
                    // button that commits it. Scrolling the slot is what keeps them; an imePadding
                    // here would subtract an inset the window has already resized itself by.
                    Column(
                        Modifier.panelVerticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.caffeine_value, caffeineMg, CAFFEINE_DAILY_LIMIT_MG),
                            color = if (caffeineMg > CAFFEINE_DAILY_LIMIT_MG) PwnWarn else PwnMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        CaffeineDrink.entries.chunked(2).forEach { row ->
                            // Intrinsic height, so a name that wraps onto a second line grows its
                            // neighbour with it and the pair stays level.
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { option ->
                                    Surface(
                                        onClick = {
                                            sizingDrink = option
                                            sizeIndex = CUP_SIZE_DEFAULT_INDEX
                                        },
                                        shape = RoundedCornerShape(PwnCorners.medium),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    ) {
                                        Column(
                                            Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.LocalCafe,
                                                null,
                                                tint = PwnMuted,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            // Two lines rather than one clipped mid-letter: at a
                                            // large font a long drink name does not fit half a
                                            // dialog.
                                            Text(
                                                stringResource(option.titleRes),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            // The muted token keeps this line secondary to the
                                            // drink's name, which is the whole point of the pair.
                                            // It reads 5.09:1 on this tile's primaryContainer by
                                            // day and 5.33:1 at night, over the 4.5:1 that 11sp
                                            // needs, so the distinction costs nothing. Both figures
                                            // are for the standard cup the size step starts on.
                                            Text(
                                                stringResource(
                                                    R.string.caffeine_drink_tile,
                                                    option.milligrams,
                                                    kcalForCup(option, STANDARD_CUP_ML).toLong().formatted(),
                                                ),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PwnMuted,
                                            )
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        // A fixed height of 44 would clip a Material field, which needs 56 for its
                        // own text, to the top half of the digits being typed. Both take the row's
                        // own height, so the field is whatever it needs and the button matches it.
                        Row(
                            Modifier.height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = customCaffeineMg,
                                onValueChange = { customCaffeineMg = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text(stringResource(R.string.unit_milligrams)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                // The corner the app's other boxed fields use (PwnChoiceField).
                                shape = RoundedCornerShape(PwnCorners.medium),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                // Enabled only for a figure above 0: drawn in the full enabled
                                // fill, an empty field or a typed 0 would make the tap do nothing
                                // at all, with nothing to say why.
                                enabled = customCaffeineMg.toIntOrNull()?.let { it > 0 } == true,
                                onClick = {
                                    customCaffeineMg.toIntOrNull()?.takeIf { it > 0 }?.let { mg ->
                                        scope.launch {
                                            withContext(NonCancellable) {
                                                db.logCaffeine(caffeineEntry(resources, mg, today))
                                            }
                                        }
                                        onDismiss()
                                    }
                                },
                                shape = RoundedCornerShape(PwnCorners.large),
                                modifier = Modifier.height(56.dp),
                            ) {
                                Text(stringResource(R.string.common_ok))
                            }
                        }
                    }
                } else {
                    val (label, millilitres) = CUP_SIZES[sizeIndex]
                    // Scrolls for the same reason as the drink list: a displaySmall size at a large
                    // font in a landscape window is taller than the dialog has room for.
                    Column(
                        Modifier.fillMaxWidth().panelVerticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(drink.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    sizeIndex--
                                },
                                enabled = sizeIndex > 0,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = PwnMuted,
                                ),
                            ) {
                                // Mirrored, so in Arabic the smaller cup still points to where it lies.
                                Icon(
                                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                    stringResource(R.string.caffeine_size_smaller),
                                )
                            }
                            // One node for the size, the volume and the caffeine, announced as one
                            // when an arrow changes them: focus stays on the arrow that was tapped.
                            Column(
                                Modifier.weight(1f).semantics(mergeDescendants = true) {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    formatVolume(resources, millilitres, imperial),
                                    color = PwnMuted,
                                )
                                Text(
                                    stringResource(
                                        R.string.caffeine_estimated,
                                        caffeineForCup(drink, millilitres),
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(
                                        R.string.caffeine_energy_estimated,
                                        kcalForCup(drink, millilitres).toLong().formatted(),
                                    ),
                                    color = PwnMuted,
                                )
                            }
                            IconButton(
                                onClick = { sizeIndex++ },
                                enabled = sizeIndex < CUP_SIZES.lastIndex,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    stringResource(R.string.caffeine_size_larger),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                sizingDrink?.let { drink ->
                    TextButton(onClick = {
                        val millilitres = CUP_SIZES[sizeIndex].second
                        scope.launch {
                            withContext(NonCancellable) {
                                db.logCaffeine(caffeineEntry(resources, drink, millilitres, today))
                            }
                        }
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.common_add))
                    }
                }
            },
            dismissButton = {
                if (sizingDrink == null) {
                    TextButton(onClick = {
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                } else {
                    TextButton(onClick = { sizingDrink = null }) {
                        Text(stringResource(R.string.common_back))
                    }
                }
            },
        )
    }
}
