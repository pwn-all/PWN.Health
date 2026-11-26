package com.pwnall.health

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The Work charts page's cards, under its chart ([WorkChartsPage]): the counts of evenings, worked
// days off, runs and short gaps, which have no week-by-week shape to draw, and long work days beside
// the evening, night and day after, which the weekly lines cannot show. Every number is read off
// [workSummary] and [workDayComparisons]; the cards draw it as bars, and what the bars leave out is
// behind each card's "?".

/**
 * The cards read the thirteen weeks to yesterday: twelve whole weeks at least, whichever day the week
 * starts on. The day in progress is left out until it is over — it can still become a long day, an
 * evening's work or the end of a run — and with it, on the last day of a week, the week in progress,
 * whose hours would otherwise stand as a short week among whole ones.
 */
internal const val WORK_INSIGHT_DAYS = 91L

/**
 * The journal the cards are handed reaches further back than the weeks they read: a day off early in
 * the thirteen weeks is a day off only once the timer is known to have been in use before it, and a
 * journal cut at the window's first day would make every day before the first session inside it
 * unknown.
 */
internal const val WORK_JOURNAL_DAYS = 2 * WORK_INSIGHT_DAYS

/** The chart and the week counts wait for this many counted weeks. */
internal const val WORK_MIN_COUNTED_WEEKS = 4

/** The rests between work days are counted from this many pairs, the stretches from this many sessions. */
internal const val WORK_MIN_GAP_PAIRS = 10
internal const val WORK_MIN_SESSIONS = 10

/**
 * What the Work charts page's cards show, worked out in one pass off the main thread. The hours'
 * chart draws [WorkSummary.weeks] as they come, oldest first.
 */
internal data class WorkInsights(val summary: WorkSummary, val comparisons: WorkDayComparisons) {
    val enoughWeeks: Boolean get() = summary.weeks.size >= WORK_MIN_COUNTED_WEEKS
}

/** The thirteen weeks to yesterday the cards and the charts read. */
internal fun workInsightWindow(today: LocalDate): ClosedRange<LocalDate> =
    today.minusDays(WORK_INSIGHT_DAYS)..today.minusDays(1)

internal fun workInsights(
    journal: List<JournalEntry>,
    today: LocalDate,
    zone: ZoneId,
    daysOff: Set<DayOfWeek>,
    longDayMinutes: Int,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): WorkInsights {
    val window = workInsightWindow(today)
    return WorkInsights(
        workSummary(journal, window, zone, daysOff, longDayMinutes, firstDayOfWeek),
        workDayComparisons(journal, window, zone, longDayMinutes),
    )
}

/** "41 h": a week's hours, whole. */
internal fun Resources.weekHours(hours: Double): String = getString(R.string.insight_hours_value, hours.roundToInt())

/**
 * A chart's row of bars as one target rather than one per bar, for the Work page's week of days.
 *
 * A tap picks the bar under it — the gap beside a bar counts as that bar's — and a second tap on it
 * lets go; a drag along the row runs the pick across the bars. Thirteen weeks in a phone's card leave
 * each bar's own column narrower than a fingertip, with gaps between them, so the row is the target.
 * Arrow keys step the pick for a keyboard or a D-pad, and in a right-to-left layout both the taps and
 * the keys follow the row, which runs the other way.
 * [picked] and [onPick] are indices into the bars, in the order [content] lays them out.
 */
@Composable
internal fun PickableBarRow(
    count: Int,
    picked: Int?,
    onPick: (Int?) -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Read when a gesture lands rather than when the gesture handlers were set up: those outlive
    // any number of recompositions, and a pick made since would otherwise be toggled against a
    // stale one.
    val current by rememberUpdatedState(picked)
    val pick by rememberUpdatedState(onPick)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    fun barAt(x: Float, width: Int): Int {
        val slot = (x / width * count).toInt().coerceIn(0, count - 1)
        return if (rtl) count - 1 - slot else slot
    }
    Row(
        modifier
            // The row has no look of its own to change, so focus is shown by a ring round it.
            .then(if (focused) Modifier.border(2.dp, ring, RoundedCornerShape(PwnCorners.small)) else Modifier)
            .pointerInput(count, rtl) {
                detectTapGestures { offset -> pick(barAt(offset.x, size.width).takeIf { it != current }) }
            }
            .pointerInput(count, rtl) {
                detectHorizontalDragGestures(onDragStart = { pick(barAt(it.x, size.width)) }) { change, _ ->
                    change.consume()
                    pick(barAt(change.position.x, size.width))
                }
            }
            .onKeyEvent { event ->
                val step = barStep(event, rtl)
                if (step != 0) pick(steppedBar(current, step, count))
                step != 0
            }
            .focusable(interactionSource = interaction),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
        content = content,
    )
}

/** Which way an arrow key moves the pick along the bars: +1, -1, or 0 for any other key. */
private fun barStep(event: KeyEvent, rtl: Boolean): Int = when {
    event.type != KeyEventType.KeyDown -> 0
    event.key == Key.DirectionRight -> if (rtl) -1 else 1
    event.key == Key.DirectionLeft -> if (rtl) 1 else -1
    else -> 0
}

/** The bar one [step] on from [from]; with none picked, the first bar the arrow points into. */
internal fun steppedBar(from: Int?, step: Int, count: Int): Int =
    from?.let { (it + step).coerceIn(0, count - 1) } ?: if (step > 0) 0 else count - 1

/**
 * One bar of a chart whose bars can be picked: [fraction] of its column, never less than [stub], so
 * an empty column still reads as one. The picked bar is outlined in ink rather than the others
 * being faded: faded, they would fall under the 3:1 a chart's marks owe the card, and an outline
 * marks the pick by shape as well as by colour.
 */
@Composable
internal fun ChartBar(fraction: Float, stub: Float, color: Color, picked: Boolean) {
    val shape = RoundedCornerShape(PwnCorners.small)
    val ink = PwnInk
    Box(
        Modifier.fillMaxWidth()
            .fillMaxHeight(fraction.coerceIn(stub, 1f))
            .clip(shape)
            .background(color)
            .then(if (picked) Modifier.border(2.dp, ink, shape) else Modifier),
    )
}

/**
 * The counts with no week to draw, each a track filled to its share — long days and evenings of the
 * work days, worked days off of those known, weeks without a day off, short rests of the pairs of work
 * days and long stretches of the timed sessions — and the longest run as a strip of its days. A count
 * still under its floor is left off the face and its floor said behind the "?".
 */
@Composable
internal fun WorkCountsCard(insights: WorkInsights, longDay: String, locale: Locale) {
    val summary = insights.summary
    val grow = rememberChartGrow()
    val gapHours = WORK_SHORT_GAP_HOURS.toInt()
    val stretchHours = WORK_LONG_STRETCH_HOURS.toInt()
    val enoughGaps = summary.gapPairs >= WORK_MIN_GAP_PAIRS
    val enoughSessions = summary.timedSessions >= WORK_MIN_SESSIONS
    PatternCard(
        stringResource(R.string.insight_work_counts_title),
        help = {
            PatternNote(stringResource(R.string.insight_work_counts_days, summary.workDays, longDay, summary.longDays))
            if (!enoughGaps) {
                PatternNote(
                    stringResource(
                        R.string.insight_work_counts_gaps_need,
                        gapHours,
                        summary.gapPairs,
                        WORK_MIN_GAP_PAIRS,
                    ),
                )
            }
            if (!enoughSessions) {
                PatternNote(
                    stringResource(
                        R.string.insight_work_counts_stretches_need,
                        stretchHours,
                        summary.timedSessions,
                        WORK_MIN_SESSIONS,
                    ),
                )
            }
            PatternNote(stringResource(R.string.insight_work_counts_pauses))
            if (summary.typedSessions > 0) {
                PatternNote(stringResource(R.string.insight_work_counts_typed, summary.typedSessions))
            }
            if (summary.cappedSessions > 0) {
                PatternNote(stringResource(R.string.insight_work_counts_capped, summary.cappedSessions))
            }
            PatternResearch(
                stringResource(R.string.insight_work_rest_research),
                stringResource(R.string.insight_work_rest_source),
            )
        },
    ) {
        CountMeter(stringResource(R.string.report_work_long_days), summary.longDays, summary.workDays, grow)
        if (insights.enoughWeeks) {
            CountMeter(stringResource(R.string.report_work_evenings), summary.eveningDays, summary.workDays, grow)
            CountMeter(
                stringResource(R.string.report_work_days_off),
                summary.usualDaysOff.days,
                summary.usualDaysOff.of,
                grow,
            )
            CountMeter(
                stringResource(R.string.report_work_weeks_no_day_off),
                summary.weeksWithoutADayOff,
                summary.weeks.size,
                grow,
            )
            summary.longestRun?.let { RunStrip(it, locale, grow) }
        } else {
            PatternNote(stringResource(R.string.insight_work_weeks_need, summary.weeks.size, WORK_MIN_COUNTED_WEEKS))
        }
        if (enoughGaps) {
            CountMeter(
                stringResource(R.string.report_work_short_rests, gapHours),
                summary.shortGaps,
                summary.gapPairs,
                grow,
            )
        }
        if (enoughSessions) {
            CountMeter(
                stringResource(R.string.report_work_long_stretches, stretchHours),
                summary.longStretchSessions,
                summary.timedSessions,
                grow,
            )
        }
    }
}

/**
 * Long work days beside the other work days and the days off: for each of mood that evening, mood the
 * next day, wound up, sleep the next night and, for anyone asked about drinks, evenings with a drink,
 * three bars in the key's colours from a zero start, so their lengths compare as the figures do, and
 * the verdict under them. The counts and the rules are behind the "?".
 */
@Composable
internal fun LongWorkDaysCard(found: WorkDayComparisons, longDay: String, alcohol: Boolean, locale: Locale) {
    val resources = LocalResources.current
    val grow = rememberChartGrow()
    val mood = { mean: Double -> resources.getString(R.string.insight_mood_value, oneDecimal(mean, locale)) }
    val sleep = { mean: Double -> workDurationText(resources, mean.roundToInt()) }
    val grams = { mean: Double? ->
        mean?.let { resources.getString(R.string.insight_grams_value, oneDecimal(it, locale)) }
    }
    val groups = workDayGroups()
    PatternCard(
        stringResource(R.string.insight_work_long_title),
        help = {
            PatternNote(
                stringResource(
                    R.string.insight_work_long_counts,
                    longDay,
                    found.longDays,
                    found.otherDays,
                    found.daysOffKnown,
                ),
            )
            PatternNote(
                pluralStringResource(R.plurals.insight_rule_means, CONTRAST_MIN_DAYS, CONTRAST_MIN_DAYS, CARD_POINTS),
            )
            if (alcohol && found.longDays + found.otherDays > 0) {
                PatternNote(stringResource(R.string.insight_rule_drinks))
            }
            PatternResearch(
                stringResource(R.string.insight_work_research),
                stringResource(R.string.insight_work_source),
            )
            PatternDisclaimer()
        },
    ) {
        if (found.longDays + found.otherDays == 0) {
            PatternNote(stringResource(R.string.insight_work_long_none))
            return@PatternCard
        }
        val off = found.daysOff
        ChartLegend {
            groups.forEach { (name, colour) -> LegendEntry(name) { drawLegendPill(colour) } }
        }
        PatternHeading(stringResource(R.string.insight_work_mood_evening))
        MeanBars(groups, found.moodSameEvening, off.mood, MOOD_TOP, mood, grow)
        VerdictLine(found.moodSameEvening, contrastLine(found.moodSameEvening), R.string.insight_notable_same_days)
        PatternHeading(stringResource(R.string.insight_work_mood_next))
        MeanBars(groups, found.moodNextDay.forward, off.moodNextDay, MOOD_TOP, mood, grow)
        VerdictLine(found.moodNextDay.forward, contrastLine(found.moodNextDay))
        PatternHeading(stringResource(R.string.insight_work_wound_up))
        ShareBars(groups, found.woundUp, off.woundUp, grow)
        VerdictLine(found.woundUp, contrastLine(found.woundUp), R.string.insight_notable_same_days)
        PatternHeading(stringResource(R.string.insight_work_sleep_next))
        val nights = found.sleepNextNight.forward
        // Sleep has no top of its own: the longest of the three means fills the row.
        val longest = listOfNotNull(nights.exposed.mean, nights.other.mean, off.sleepNextNight.mean).maxOrNull()
        MeanBars(groups, nights, off.sleepNextNight, longest ?: 1.0, sleep, grow)
        VerdictLine(nights, contrastLine(found.sleepNextNight))
        if (alcohol) {
            PatternHeading(stringResource(R.string.insight_work_drinks))
            ShareBars(groups, found.drinkEvenings, off.drinkEvenings, grow)
            val none = stringResource(R.string.insight_no_value)
            PatternNote(
                stringResource(
                    R.string.insight_work_grams,
                    grams(found.drinkGrams.exposed.mean) ?: none,
                    grams(found.drinkGrams.other.mean) ?: none,
                ),
            )
            VerdictLine(found.drinkEvenings, drinksLine(found), R.string.insight_notable_same_days)
        }
    }
}

/**
 * The three groups the long-days card compares, each with its name and its bars' colour: long work
 * days in the hours' own colour, other work days in the card's secondary ink, days off in green. All
 * three clear 3:1 on the card (DesignContrastTest).
 */
@Composable
private fun workDayGroups(): List<Pair<String, Color>> = listOf(
    stringResource(R.string.report_work_long_days) to PwnPurple,
    stringResource(R.string.work_charts_other_days) to PwnMuted,
    stringResource(R.string.work_charts_days_off) to PwnGreen,
)

/** A number's mean on long work days, on other work days and on days off, as three bars out of [top]. */
@Composable
private fun MeanBars(
    groups: List<Pair<String, Color>>,
    contrast: DayMeanContrast,
    daysOff: DayMean,
    top: Double,
    shown: (Double) -> String,
    grow: () -> Float,
) {
    listOf(contrast.exposed.mean, contrast.other.mean, daysOff.mean).forEachIndexed { index, mean ->
        val (name, colour) = groups[index]
        CompareBar(name, colour, mean?.let { it / top }, mean?.let(shown), grow)
    }
}

/** A share of long work days, of other work days and of days off, as three bars out of all of them. */
@Composable
private fun ShareBars(
    groups: List<Pair<String, Color>>,
    contrast: DayShareContrast,
    daysOff: DayShare,
    grow: () -> Float,
) {
    listOf(contrast.exposed, contrast.other, daysOff).forEachIndexed { index, share ->
        val (name, colour) = groups[index]
        val figure = if (share.of == 0) null else stringResource(R.string.insight_count_of, share.days, share.of)
        CompareBar(name, colour, share.share, figure, grow)
    }
}

/**
 * One group's bar: [fraction] of the row from its start, in the reading direction, and the [figure]
 * at its end — a dash and no bar for a group with nothing to show. A screen reader hears the group's
 * name and the figure as one line.
 */
@Composable
private fun CompareBar(name: String, colour: Color, fraction: Double?, figure: String?, grow: () -> Float) {
    val said = figure ?: stringResource(R.string.insight_no_value)
    val spoken = stringResource(R.string.intimate_charts_spoken_value, name, said)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.weight(1f).height(COMPARE_BAR_HEIGHT)) {
            // None at zero: a dot would read as a little, and the figure beside it says 0.
            fraction?.takeIf { it > 0.0 }?.let { drawPill(colour, it.toFloat() * grow()) }
        }
        Text(
            said,
            Modifier.widthIn(min = FIGURE_MIN_WIDTH),
            style = MaterialTheme.typography.labelLarge,
            color = if (figure == null) PwnMuted else PwnInk,
            textAlign = TextAlign.End,
        )
    }
}

/** A count of a card: its name and "4 of 20" over a track filled to that share. */
@Composable
private fun CountMeter(label: String, count: Int, of: Int, grow: () -> Float) {
    val figure = stringResource(R.string.insight_count_of, count, of)
    val spoken = stringResource(R.string.intimate_charts_spoken_value, label, figure)
    val fill = PwnPurple
    val track = PwnBorder
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(figure, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(METER_HEIGHT)) {
            drawPill(track, 1f)
            if (count > 0 && of > 0) drawPill(fill, count.toFloat() / of * grow())
        }
    }
}

/**
 * The longest run of days with work as a strip of its days, one cell each — "at least" when it may
 * have gone on past a day nobody kept or the edge of the weeks — with its dates under it.
 */
@Composable
private fun RunStrip(run: WorkRun, locale: Locale, grow: () -> Float) {
    val label = stringResource(R.string.report_work_run)
    val days = run.days.toString()
    val figure = if (run.atLeast) stringResource(R.string.report_work_at_least, days) else days
    val dates = dayRangeText(run.from, run.to, locale)
    val spoken = stringResource(
        R.string.intimate_charts_spoken_value,
        label,
        stringResource(R.string.intimate_charts_spoken_pair, figure, dates),
    )
    val fill = PwnPurple
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(figure, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(METER_HEIGHT)) {
            // Each day a pitch of the row, the cell most of it, so even a run of the whole thirteen
            // weeks ends inside the row.
            val pitch = min(RUN_CELL_MAX.toPx() + RUN_GAP.toPx(), size.width / run.days)
            val cell = pitch - min(RUN_GAP.toPx(), pitch / 3f)
            repeat(ceil(run.days * grow()).toInt()) { day ->
                val start = day * pitch
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - start - cell else start
                drawRoundRect(
                    fill,
                    topLeft = Offset(left, 0f),
                    size = Size(cell, size.height),
                    cornerRadius = CornerRadius(min(cell, size.height) / 2f),
                )
            }
        }
        Text(dates, style = MaterialTheme.typography.labelSmall, color = PwnMuted)
    }
}

/** A rounded bar from the start of the row, [fraction] of its width, never thinner than it is tall. */
private fun DrawScope.drawPill(colour: Color, fraction: Float) {
    val width = max(size.height, size.width * fraction.coerceIn(0f, 1f))
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRoundRect(colour, Offset(left, 0f), Size(width, size.height), CornerRadius(size.height / 2f))
}

private fun DrawScope.drawLegendPill(colour: Color) {
    val height = size.height * LEGEND_PILL_SHARE
    drawRoundRect(
        colour,
        Offset(0f, (size.height - height) / 2f),
        Size(size.width, height),
        CornerRadius(height / 2f),
    )
}

/**
 * 0 to 1 as a card first shows, so its bars grow from their start together; once each time it opens.
 * Read only while drawing, so the growth redraws the bars without recomposing the card.
 */
@Composable
private fun rememberChartGrow(): () -> Float {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, BAR_GROW) }
    return remember(grow) { { grow.value } }
}

/** One verdict for the share of evenings with a drink and their mean grams, notable when either is. */
internal fun drinksLine(found: WorkDayComparisons): ContrastLine = if (found.drinksNotable) {
    ContrastLine.NOTABLE
} else {
    contrastLine(found.drinkEvenings)
}

/** The top of the mood scale, as "3.4 of 5" prints it. */
private const val MOOD_TOP = 5.0

/** How much of a legend swatch's height its pill takes. */
private const val LEGEND_PILL_SHARE = 0.7f

private val COMPARE_BAR_HEIGHT = 14.dp
private val METER_HEIGHT = 10.dp
private val FIGURE_MIN_WIDTH = 64.dp
private val RUN_CELL_MAX = 18.dp
private val RUN_GAP = 3.dp
