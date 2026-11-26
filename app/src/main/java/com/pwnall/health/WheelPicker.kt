package com.pwnall.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * [formatter] is deliberately not `@Composable`: it runs once per visible row inside the wheel's
 * `LazyColumn`, and a composable lambda there would open a recomposition scope per row and force
 * itself into the middle of the parameter list, where Compose wants the trailing slot free for the
 * event callback. Callers that need a string resource read it before the call and capture it.
 */
@Composable
internal fun WheelPickerCard(
    label: String,
    value: Int,
    values: List<Int>,
    formatter: (Int) -> String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val formatted = formatter(value)
    // The description replaces the card's own text for a screen reader, so it has to carry the
    // label too: two of these cards side by side otherwise announce two bare numbers.
    val description = androidx.compose.ui.res.stringResource(
        R.string.picker_value_description,
        label,
        formatted,
    )
    PickerCard(label, formatted, description, onClick = { open = true }, modifier = modifier)
    if (open) {
        WheelPickerDialog(
            label = label,
            initialValue = value,
            values = values,
            formatter = formatter,
            onDismiss = { open = false },
            onConfirm = {
                onValue(it)
                open = false
            },
        )
    }
}

@Composable
internal fun ClockTimePickerCard(
    label: String,
    minuteOfDay: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val safeMinute = minuteOfDay.coerceIn(0, 1_439)
    val formatted = clockText(safeMinute)
    val description = androidx.compose.ui.res.stringResource(
        R.string.picker_value_description,
        label,
        formatted,
    )
    PickerCard(label, formatted, description, onClick = { open = true }, modifier = modifier)
    if (open) {
        ClockTimePickerDialog(
            label = label,
            initialMinuteOfDay = safeMinute,
            onDismiss = { open = false },
            onConfirm = {
                onValue(it)
                open = false
            },
        )
    }
}

/**
 * The face both picker cards share: the label over the value, and the chevron that says a tap opens
 * a choice. The chevron is the icon the app's other choice fields carry, not a text glyph whose look
 * depends on which font has it.
 */
@Composable
private fun PickerCard(
    label: String,
    formatted: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(PwnCorners.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatted,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = PwnMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun ClockTimePickerDialog(
    label: String,
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hour by rememberSaveable(initialMinuteOfDay) {
        mutableIntStateOf(initialMinuteOfDay / 60)
    }
    var minute by rememberSaveable(initialMinuteOfDay) {
        mutableIntStateOf(initialMinuteOfDay % 60)
    }
    // One tick for both wheels, as the birth date's three share one: each wheel left to its own
    // builds a SoundPool for a sound only one of them is making.
    val tick = rememberRulerTick()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(androidx.compose.ui.res.stringResource(R.string.picker_change_title, label))
        },
        text = {
            // Hours, colon, minutes, left to right in every language, as a clock is read and as the
            // platform's own time pickers keep them. Mirrored in Arabic the pair would read
            // minutes first, and 08:30 would stand as 30:08. The title and buttons still mirror.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Wheel(
                            values = (0..23).toList(),
                            selected = hour,
                            formatter = { String.format(Locale.ROOT, "%02d", it) },
                            onSelected = { hour = it },
                            tick = tick,
                        )
                    }
                    Text(
                        ":",
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Box(Modifier.weight(1f)) {
                        Wheel(
                            values = (0..59).toList(),
                            selected = minute,
                            formatter = { String.format(Locale.ROOT, "%02d", it) },
                            onSelected = { minute = it },
                            tick = tick,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                Text(androidx.compose.ui.res.stringResource(R.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * A time of day as the app writes one: "08:05", twenty-four hours, two digits each, in ASCII digits.
 *
 * Every time on every screen is written this way, whatever the phone's own clock is set to: the
 * wheels that take a time turn through 0 to 23, and a reminder set on them for "20:00" must not be
 * listed as "8:00 PM" on the next page. A place that followed the phone's twelve hours would read
 * differently from everything around it on such a phone. Following the phone would be a rule too,
 * but one for all sixty-odd places and the wheels at once; ClockStyleTest keeps a single place from
 * choosing.
 */
internal fun clockText(minuteOfDay: Int): String {
    val safeMinute = minuteOfDay.coerceIn(0, 1_439)
    return String.format(Locale.ROOT, "%02d:%02d", safeMinute / 60, safeMinute % 60)
}

/**
 * The top of an hour as the clock above writes it: "08:00", and "24:00" for the end of the day's
 * last hour, which [clockText] cannot say because it stops at 23:59.
 *
 * In the same digits as every other clock in the app. A format that names no locale takes the digits
 * of the phone's language for the hour and for nothing else on the line: in Arabic a range would read
 * "٠٨:00–08:37".
 */
internal fun hourText(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

@Composable
internal fun WheelPickerDialog(
    label: String,
    initialValue: Int,
    values: List<Int>,
    formatter: (Int) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val safeValues = remember(values) { values.ifEmpty { listOf(initialValue) } }
    var value by rememberSaveable(initialValue, safeValues) {
        mutableIntStateOf(initialValue.takeIf { it in safeValues } ?: safeValues.first())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(androidx.compose.ui.res.stringResource(R.string.picker_change_title, label))
        },
        text = {
            Wheel(
                values = safeValues,
                selected = value,
                formatter = formatter,
                onSelected = { value = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(androidx.compose.ui.res.stringResource(R.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * The wheel itself, without the dialog around it. Onboarding puts it straight on the page — a
 * question whose whole content is one measurement has nothing to open a dialog from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Wheel(
    values: List<Int>,
    selected: Int,
    formatter: (Int) -> String,
    onSelected: (Int) -> Unit,
    /**
     * The tick a turning wheel makes. A page of several wheels hands them one: every
     * [rememberRulerTick] holds a SoundPool of its own, and a wheel that is rebuilt while another
     * turns — the day wheel, for each month passed — would build one and release one at every step.
     */
    tick: () -> Unit = rememberRulerTick(),
) {
    val itemHeight = 52.dp
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    // The effect below outlives a recomposition, so it reports to the latest callback rather than
    // to the one it started with.
    val report by rememberUpdatedState(onSelected)

    // Reported while the wheel is still moving, not only once it settles: Done stays tappable
    // during a fling, and reporting only on settle would commit the number the wheel had left rather
    // than the one under the highlight when the button was pressed.
    LaunchedEffect(state, values) {
        snapshotFlow {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
        }
            .distinctUntilChanged()
            .collect { centred ->
                centred?.let { values.getOrNull(it) }?.let {
                    report(it)
                    if (state.isScrollInProgress) tick()
                }
            }
    }

    Box(Modifier.fillMaxWidth().height(itemHeight * 5), contentAlignment = Alignment.Center) {
        // The band that frames the value. Its tonal fill is a step away from the dialog it sits on,
        // too slight to see in daylight, so an outline draws its edge: 3.61:1 on the dialog in
        // daylight and 4.32:1 by night, and more on the onboarding page.
        Surface(
            modifier = Modifier.fillMaxWidth().height(itemHeight),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
        // One choice among many, so a screen reader hears each row as an option and which one is
        // the value now, rather than a column of numbers each saying "double tap to activate".
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * 2),
            modifier = Modifier.fillMaxWidth().height(itemHeight * 5).selectableGroup(),
        ) {
            itemsIndexed(values) { index, item ->
                val chosen = item == selected
                Text(
                    text = formatter(item),
                    // `height` fixes min AND max, so a `.padding(vertical = 12.dp)` would hand the
                    // Text a 28dp-tall window. The selected row is titleLarge, whose line box is
                    // 28sp: exactly full at fontScale 1.0, and clipped from ~1.02 onwards, first
                    // the descenders of "82.5 kg" / "82,5 фунт", then the digits themselves by the
                    // 1.5 step. Centring instead of padding leaves the child the whole 52dp, so a
                    // 28sp line fits to fontScale ~1.85 linear and past 2.0 non-linear.
                    // wrapContentHeight sits INSIDE selectable so the row's touch target stays the
                    // full 52dp rather than shrinking to the height of the glyphs.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .selectable(selected = chosen, role = Role.RadioButton) {
                            onSelected(item)
                            scope.launch { state.animateScrollToItem(index) }
                        }
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    style = if (chosen) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (chosen) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        // A 16sp bodyLarge row on the AlertDialog's surfaceContainerHigh needs
                        // 4.5:1, and these rows are live, tappable values, so no incidental-text
                        // exemption applies. onSurfaceVariant faded to 65% composites to 2.55:1 in
                        // daylight, and at full alpha it leaves no fade to mark the row as not
                        // chosen. onSurface at the same 65% keeps that fade and measures 4.69:1 in
                        // daylight and 5.93:1 by night.
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    },
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
