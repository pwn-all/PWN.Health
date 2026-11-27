package com.pwnall.health

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun BurnBreakdown(today: HomeToday) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    PwnCard(Modifier.fillMaxWidth(), containerColor = PwnPurple.copy(alpha = 0.12f)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpTitle(stringResource(R.string.burn_total_so_far)) {
                Text(stringResource(R.string.burn_total_note))
            }
            Text(
                stringResource(R.string.burn_kcal, (today.movedKcal + today.standbyKcal).wholeKcal()),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val sources = stringResource(R.string.burn_sources)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sources,
                    Modifier.weight(1f, fill = false).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                // Why a walk logged as a workout is not counted twice: read once, not every visit.
                HelpDot(sources) { Text(stringResource(R.string.burn_overlap_note)) }
            }
            EnergyRow(stringResource(R.string.steps), today.walkingKcal)
            EnergyRow(stringResource(R.string.home_workouts), (today.movedKcal - today.walkingKcal).coerceAtLeast(0.0))
            HorizontalDivider(color = PwnBorder)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.burn_rest_so_far),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.burn_kcal, today.standbyKcal.wholeKcal()),
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = {
                        showExplanation = true
                    }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            stringResource(R.string.burn_explanation_title),
                        )
                    }
                }
                Text(
                    stringResource(R.string.burn_daily_estimate, today.restingForecast.wholeKcal()),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = PwnMuted) },
            title = { Text(stringResource(R.string.burn_explanation_title)) },
            text = {
                Column(
                    Modifier.panelVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.burn_daily_estimate, today.restingForecast.wholeKcal()))
                    Text(stringResource(R.string.burn_explanation_body))
                }
            },
            confirmButton = {
                TextButton(onClick = { showExplanation = false }) { Text(stringResource(R.string.common_done)) }
            },
            containerColor = PwnSurfaceElevated,
        )
    }
}

@Composable
private fun EnergyRow(label: String, kcal: Double) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.burn_kcal, kcal.wholeKcal()), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FoodHistory(entries: List<JournalEntry>, onEdit: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.food_history),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (entries.isEmpty()) Text(stringResource(R.string.food_history_empty), color = PwnMuted)
        entries.sortedByDescending { it.occurredAt }.forEach { entry ->
            PwnCard(Modifier.fillMaxWidth(), onClick = { onEdit(entry.id) }) {
                // Tighter down the page than the card's usual eighteen, and tightest under the
                // macro line: its cells are full-size tap targets and carry their own air above and
                // below the letters, so the card gives that much back rather than stacking a band
                // of nothing on top of it. A note under the letters gets the ordinary margin.
                Column(
                    Modifier.padding(horizontal = 18.dp)
                        .padding(top = 10.dp, bottom = if (entry.note.isNotBlank()) 10.dp else 3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            themePainterResource(rememberFoodArt(entry.title)),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(entry.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            eventClock(entry.occurredAt),
                            color = PwnMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    FoodFigures(entry)
                    if (entry.note.isNotBlank()) {
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The energy and the macros on one line. The kcal figure is the headline and is measured before
 * the strip beside it, so a long "1 050 kcal" at a large font scale takes its width out of the
 * macros rather than losing its own end; the strip answers a squeeze by wrapping a cell onto a
 * second line, which is the one way of running short of room that does not drop a digit in
 * silence.
 */
@Composable
private fun FoodFigures(entry: JournalEntry) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Notes never replace macros: these are nutrients for the recorded portion.
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MacroCell(
                stringResource(R.string.food_macro_letter_protein),
                stringResource(R.string.editor_protein),
                stringResource(R.string.dish_build_grams, entry.proteinG.shown()),
            )
            MacroCell(
                stringResource(R.string.food_macro_letter_fat),
                stringResource(R.string.editor_fat),
                stringResource(R.string.dish_build_grams, entry.fatG.shown()),
            )
            MacroCell(
                stringResource(R.string.food_macro_letter_carbs),
                stringResource(R.string.editor_carbs),
                stringResource(R.string.dish_build_grams, entry.carbsG.shown()),
            )
        }
        Text(
            stringResource(R.string.burn_kcal, entry.calories.wholeKcal()),
            color = PwnAccentInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One macronutrient: the letter it is abbreviated to and the grams behind it. A letter is not a
 * word — nobody has to already know that C here is not calories — so the letter carries the broken
 * underline that means an explanation is available, and a tap says it in full. Long-press says it
 * too, because that is what TooltipBox brings on its own, but a long press is not an affordance
 * anyone discovers from a dashed line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroCell(letter: String, name: String, grams: String) {
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    // The palette entries are composable getters, and the draw lambda runs on the draw pass, where
    // there is no composition left to read one from.
    val dashes = PwnMuted
    val spoken = "$name $grams"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(name) } },
        state = tip,
    ) {
        Row(
            // The full minimum target in height. The cells sit side by side inside a card that is
            // a target of its own, so a short one would be a small target between larger ones — easy to
            // miss into the card and open the entry instead. The card pays for the extra height
            // out of its own padding (FoodHistory), so the row does not turn into a band of air.
            Modifier.heightIn(min = PwnDimensions.minimumTarget)
                .clip(RoundedCornerShape(PwnCorners.medium))
                .clickable(role = Role.Button) { scope.launch { tip.show() } }
                .padding(horizontal = 4.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Where the glyph actually sits, rather than where its line box ends: a line hung off
            // the bottom of the box falls through the descender gap and reads as a mark under the
            // row instead of as an underline, and the gap grows with the font scale.
            var baseline by remember { mutableFloatStateOf(0f) }
            Text(
                letter,
                Modifier.dashedUnderline(dashes) { baseline },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                onTextLayout = { baseline = it.firstBaseline },
            )
            Text(grams, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The broken line under an abbreviation. It is drawn rather than set as a TextDecoration because
 * Compose only underlines solid, and a solid line under a letter in a card of figures reads as a
 * link to another page instead of as a word with a meaning behind it.
 */
private fun Modifier.dashedUnderline(colour: Color, baseline: () -> Float) = drawBehind {
    val stroke = MACRO_DASH_STROKE.toPx()
    val dash = MACRO_DASH.toPx()
    val step = dash + MACRO_DASH_GAP.toPx()
    val y = baseline() + MACRO_DASH_DROP.toPx()
    // Laid out mark by mark and centred, rather than handed to dashPathEffect across the letter's
    // whole advance width. A dash pattern run from the left edge of that width drops its first
    // mark and leaves the rest sitting eight pixels to the right of the letter, under the space
    // between it and the figure — which reads as a stray tick rather than as an underline.
    val marks = ((size.width + MACRO_DASH_GAP.toPx()) / step).toInt().coerceAtLeast(MACRO_DASH_MIN)
    var x = (size.width - (marks * step - MACRO_DASH_GAP.toPx())) / 2f
    repeat(marks) {
        drawLine(colour, Offset(x, y), Offset(x + dash, y), strokeWidth = stroke)
        x += step
    }
}

/**
 * One dash under a macro letter, and the gap after it.
 *
 * Shorter than the dash the charts are ruled with: a single letter is about four of these wide, and
 * at the charts' length the line under it comes out as one stroke sitting off to one side, which
 * reads as a stray mark rather than as a line that has been broken on purpose.
 */
private val MACRO_DASH = 2.dp

/** The air after each mark. */
private val MACRO_DASH_GAP = 2.dp

/** Two marks, or one mark and a gap is all a narrow letter gets and it stops reading as a line. */
private const val MACRO_DASH_MIN = 3

/** The weight of that line — a hair under the letter, not a rule drawn across the row. */
private val MACRO_DASH_STROKE = 1.dp

/** How far under the baseline the line hangs: clear of the letter, still plainly attached to it. */
private val MACRO_DASH_DROP = 3.dp

internal fun eventClock(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * The wall clock, read again every half-minute, for a card whose last line ends at "now". Read once
 * per composition instead, a page left open would keep the time it was opened at — and after the
 * hour turned, an hour that had finished would still end at that stale minute.
 */
@Composable
private fun tickingNow(): java.time.LocalDateTime {
    val now by androidx.compose.runtime.produceState(java.time.LocalDateTime.now()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = java.time.LocalDateTime.now()
        }
    }
    return now
}

/** Today's phone activity, from midnight through the current partial hour. */
@Composable
internal fun StepsActivityHistory(todayFace: MetricFace) {
    val now = tickingNow()
    var selectedHour by rememberSaveable(now.toLocalDate().toString()) { mutableIntStateOf(-1) }
    val hours = todayFace.hours.take(now.hour + 1)
    val clock = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    PerformancePanel(
        title = stringResource(R.string.steps_hourly_title),
        interval = "00:00–$clock",
        values = hours.map { it.toLong() },
        labels = hours.indices.map(::hourText),
        selectedIndex = if (selectedHour < 0) {
            hours.indices.maxByOrNull { hours[it] } ?: 0
        } else {
            selectedHour.coerceAtMost(now.hour)
        },
        onSelect = { selectedHour = it },
        help = if (todayFace.hoursPartial) stringResource(R.string.home_hourly_partial) else null,
    )
}

@Composable
internal fun StepsActivityIntervals(todayFace: MetricFace) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val now = tickingNow()
    val hours = todayFace.hours.take(now.hour + 1)
    val clock = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    // In the button's own colour and with a chevron that turns, so it reads as something that
    // opens rather than as a line of ink; the state is said aloud as well as drawn.
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.disclosureSemantics(expanded, disclosureState(expanded)) { expanded = it },
    ) {
        Text(stringResource(R.string.activity_times))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
            tint = PwnMuted,
        )
    }
    if (!expanded) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.activity_times),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (hours.none { it > 0 }) Text(stringResource(R.string.activity_times_empty), color = PwnMuted)
        hours.forEachIndexed { hour, steps ->
            if (steps > 0) {
                val end = if (hour == now.hour) clock else hourText(hour + 1)
                Row(
                    Modifier.fillMaxWidth().background(
                        PwnIndigoSoft,
                        RoundedCornerShape(PwnCorners.largeIncreased),
                    ).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left to right whatever the language: two clock times and a dash hold no
                    // letter to set a direction, and an Arabic paragraph reverses them.
                    Text(
                        "${hourText(hour)}–$end",
                        color = PwnMuted,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    )
                    Text(
                        stringResource(
                            R.string.home_peak_hour,
                            steps.toLong().formatted(),
                            stringResource(R.string.steps_unit),
                        ),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
