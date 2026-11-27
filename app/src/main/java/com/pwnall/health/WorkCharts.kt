package com.pwnall.health

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.max

// The Work charts: hours a week as bars, and what was recorded beside them in the same weeks as smooth
// lines over the same bars, one switch under the chart for each, so the chart holds as many lines as
// the person asks for and no more. Opened from the Work screen's top bar and shown nowhere else. Every
// figure is read off [workChartsData]; the drawings only place it.
//
// A chart without enough to show is not drawn at all, and a page with nothing in its weeks says only
// that. Under the chart stand the two cards the weekly lines do not cover, drawn as bars too: the
// counts of evenings, days off, runs and rests, which have no week to draw, and long work days set
// beside the evening, the night and the day after, which a week's mean cannot show.

/**
 * The page under the Work screen's top bar. [daysOff] and [longDayMinutes] are the person's two
 * settings, read through at once; [alcohol] is whether the profile is offered the alcohol diary at
 * all: the drinks are not set beside work for anyone the app does not ask about drinks.
 *
 * The page scrolls itself and centres its empty line in whatever height it is given, so [modifier]
 * must hand it the bounded room under the top bar — the screen body's `weight(1f)` — and never a
 * scrolling column.
 */
@Composable
internal fun WorkChartsPage(modifier: Modifier, daysOff: Set<DayOfWeek>, longDayMinutes: Int, alcohol: Boolean) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val today = rememberToday()
    val zone = rememberZone()
    // The week starts where the Work page's own bars start it: on the locale's first day.
    val firstDayOfWeek = WeekFields.of(LocalLocale.current.platformLocale).firstDayOfWeek
    val journal by remember(today) {
        db.dao().journalBetween(today.minusDays(WORK_JOURNAL_DAYS - 1).toString(), today.toString())
    }.collectAsStateWithLifecycle<List<JournalEntry>?>(null)
    val data by produceState<WorkChartsData?>(null, journal, daysOff, longDayMinutes, today, alcohol, firstDayOfWeek) {
        val rows = journal ?: return@produceState
        value = withContext(Dispatchers.Default) {
            workChartsData(rows, today, zone, daysOff, longDayMinutes, alcohol, firstDayOfWeek)
        }
    }
    val shown = data
    when {
        shown == null -> Box(modifier.fillMaxSize())

        shown.empty -> ChartsEmptyNotice(modifier)

        else -> Column(
            modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val locale = LocalLocale.current.platformLocale
            val longDay = stringResource(R.string.work_long_day_value, longDayMinutes / 60)
            Spacer(Modifier.height(8.dp))
            if (shown.hoursShown) HoursChartCard(shown)
            WorkCountsCard(shown.insights, longDay, locale)
            LongWorkDaysCard(shown.insights.comparisons, longDay, alcohol, locale)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The counted weeks' hours as bars from the floor, a week not counted left empty, with the person's
 * own median as a dashed rule across them in the card's ink. Nothing is coloured as too much or too
 * little, and no line is drawn at anybody else's limit: the bands are counted behind the "?".
 *
 * Over the bars, each comparison that has enough to show is a smooth line from a centre rule, its
 * distance from the person's own average — above it in a week higher than the average, below it in a
 * week lower — switched on and off by its own chip under the chart, the first of them on to begin
 * with. Each line is scaled to its widest swing, never to less than its [minSwing], in its own units:
 * a line that strays a little from the average would otherwise fill the plot like one that strays a
 * lot. The bars grow from the floor as the page opens and a switched line rises out of the centre
 * rule, both again on each visit or tap and never in a loop.
 */
@Composable
private fun HoursChartCard(data: WorkChartsData) {
    val median = data.insights.summary.medianWeekHours ?: return
    val title = stringResource(R.string.insight_work_weeks_title)
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // One bit for each outcome whose line is drawn, as [WorkOutcome.ordinal] numbers them.
    var on by rememberSaveable { mutableIntStateOf(data.pairs.firstOrNull()?.outcome?.bit ?: 0) }
    val names = data.pairs.map { stringResource(it.outcome.nameRes()) }
    val colours = data.pairs.map { it.outcome.colour() }
    // Read only while drawing, so a rising line redraws the chart without recomposing the card.
    val reveal = data.pairs.map { pair ->
        key(pair.outcome) {
            animateFloatAsState(if (on and pair.outcome.bit != 0) 1f else 0f, LINE_REVEAL, label = "work line")
        }
    }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, BAR_GROW) }
    val barName = stringResource(R.string.work_charts_legend_hours)
    val spoken = remember(data, on, names, resources, locale) {
        spokenChart(resources, locale, title, data.weeks) { index ->
            val lines = data.pairs.indices
                .filter { on and data.pairs[it].outcome.bit != 0 }
                .map { names[it] to data.pairs[it].line.fromAverage[index] }
            spokenWorkWeek(resources, data.hours[index], barName, lines)
        }
    }
    val top = data.hoursTop
    val barEdge = PwnPurple
    val bar = weekBarFill()
    val ink = PwnInk
    val centre = PwnMuted
    val halo = PwnSurfaceElevated
    PatternCard(title, help = { HoursHelp(data) }) {
        WeekChart(data.weeks, spoken, listOf(resources.weekHours(top), resources.weekHours(0.0))) { plot ->
            val heights = data.hours.map { it?.let { hours -> (hours / top).toFloat() } }
            drawWeekBars(plot, heights, bar, barEdge, grow.value)
            drawCentreLine(plot.rect, plot.rect.bottom - (median / top).toFloat() * plot.rect.height, ink)
            val middle = plot.rect.center.y
            val shownMost = reveal.maxOfOrNull { it.value } ?: 0f
            if (shownMost > 0f) drawCentreLine(plot.rect, middle, centre.copy(alpha = shownMost))
            val reach = plot.rect.height / 2f * SWING_REACH
            data.pairs.forEachIndexed { index, pair ->
                val rise = reveal[index].value
                if (rise <= 0f) return@forEachIndexed
                val offsets = pair.line.fromAverage
                val swing = max(offsets.maxOf { abs(it ?: 0.0) }, pair.outcome.minSwing)
                val ys = offsets.map { it?.let { off -> middle - (off / swing).toFloat() * reach * rise } }
                drawSmoothWeekLine(plot, ys, colours[index].copy(alpha = rise), halo.copy(alpha = rise))
            }
        }
        ChartLegend {
            LegendEntry(barName) { drawLegendBar(bar, barEdge) }
            LegendEntry(stringResource(R.string.work_charts_legend_median)) { drawLegendStroke(ink, dashed = true) }
            if (on != 0) {
                LegendEntry(stringResource(R.string.intimate_charts_legend_average)) {
                    drawLegendStroke(centre, dashed = true)
                }
            }
        }
        if (data.pairs.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                data.pairs.forEachIndexed { index, pair ->
                    LineSwitch(names[index], colours[index], on and pair.outcome.bit != 0) {
                        on = on xor pair.outcome.bit
                    }
                }
            }
        }
    }
}

/**
 * A line's switch: a chip with the line's own colour drawn in it, so the chip is the line's key too.
 * Filled while the line is drawn; the chip says "selected" to a screen reader.
 */
@Composable
private fun LineSwitch(name: String, colour: Color, on: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = on,
        onClick = onToggle,
        label = { Text(name) },
        leadingIcon = {
            Canvas(Modifier.size(width = SWITCH_SWATCH_WIDTH, height = FilterChipDefaults.IconSize)) {
                drawLegendStroke(colour)
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = scheme.outline),
    )
}

/**
 * Everything the chart's face leaves out: the median and the bands in words, how the bars and the
 * lines are drawn and their floors, the research behind the hours and behind each line that has
 * enough to show, and that two things side by side are not a cause.
 */
@Composable
private fun HoursHelp(data: WorkChartsData) {
    val resources = LocalResources.current
    val summary = data.insights.summary
    summary.medianWeekHours?.let { median ->
        summary.weekHoursRange?.let { range ->
            PatternNote(
                stringResource(
                    R.string.insight_work_weeks_median,
                    resources.weekHours(median),
                    resources.weekHours(range.start),
                    resources.weekHours(range.endInclusive),
                ),
            )
        }
    }
    PatternNote(
        stringResource(
            R.string.insight_work_weeks_bands,
            WORK_WEEK_48_HOURS.toInt(),
            summary.weeksAtLeast48,
            WORK_WEEK_55_HOURS.toInt(),
            summary.weeksAtLeast55,
            summary.weeks.size,
        ),
    )
    PatternNote(stringResource(R.string.insight_work_weeks_median_line))
    PatternNote(stringResource(R.string.insight_work_weeks_note))
    PatternNote(stringResource(R.string.work_charts_gap_note))
    if (data.pairs.isNotEmpty()) {
        PatternNote(stringResource(R.string.work_charts_pair_note))
        PatternNote(pluralStringResource(R.plurals.work_charts_line_rule, WEEK_MIN_READINGS, WEEK_MIN_READINGS))
    }
    if (data.pairs.any { it.outcome == WorkOutcome.ALCOHOL }) {
        PatternNote(
            stringResource(
                R.string.intimate_charts_drinks_note,
                stringResource(R.string.insight_grams_value, STANDARD_DRINK_G.toInt().toString()),
            ),
        )
    }
    ShowRule(WORK_MIN_COUNTED_WEEKS)
    PatternResearch(
        stringResource(R.string.insight_work_weeks_research),
        stringResource(R.string.insight_work_weeks_source),
    )
    PatternResearch(
        stringResource(R.string.insight_work_recall_research),
        stringResource(R.string.insight_work_recall_source),
    )
    data.pairs.forEach { pair ->
        val (research, source) = pair.outcome.research()
        PatternResearch(stringResource(research), stringResource(source))
    }
    if (data.pairs.isNotEmpty()) PatternDisclaimer()
}

/**
 * A week as the screen reader says it: the hours, or "no figure" for a week not counted, and where
 * each line drawn stood against its average. A week with none of these is left out.
 */
private fun spokenWorkWeek(
    resources: Resources,
    hours: Double?,
    barName: String,
    lines: List<Pair<String, Double?>>,
): String? {
    if (hours == null && lines.all { it.second == null }) return null
    val figure = hours?.let(resources::weekHours) ?: resources.getString(R.string.intimate_charts_spoken_none)
    val bar = resources.getString(R.string.intimate_charts_spoken_value, barName, figure)
    return lines.fold(bar) { said, (name, off) ->
        val line = resources.getString(
            when {
                off == null -> R.string.work_charts_spoken_gap
                off > 0.0 -> R.string.work_charts_spoken_above
                off < 0.0 -> R.string.work_charts_spoken_below
                else -> R.string.work_charts_spoken_at
            },
            name,
        )
        resources.getString(R.string.intimate_charts_spoken_pair, said, line)
    }
}

private val WorkOutcome.bit: Int get() = 1 shl ordinal

/** A line's name on its switch, the same short word the rest of the app uses for it. */
private fun WorkOutcome.nameRes(): Int = when (this) {
    WorkOutcome.MOOD -> R.string.mood_title
    WorkOutcome.WOUND_UP -> R.string.report_mood_wound_up
    WorkOutcome.SLEEP -> R.string.sleep
    WorkOutcome.ALCOHOL -> R.string.alcohol_title
}

/**
 * A line's colour: four the palette holds to 3:1 on the card by day and by night
 * (DesignContrastTest), each drawn on a card-coloured rim, so it reads where it crosses a bar too.
 */
@Composable
private fun WorkOutcome.colour(): Color = when (this) {
    WorkOutcome.MOOD -> PwnInk
    WorkOutcome.WOUND_UP -> PwnWarn
    WorkOutcome.SLEEP -> PwnBlueInk
    WorkOutcome.ALCOHOL -> PwnBleed
}

/** The research the doctor's sheet prints under the same pair, and its source. */
internal fun WorkOutcome.research(): Pair<Int, Int> = when (this) {
    WorkOutcome.MOOD -> R.string.report_changes_background_work_mood to R.string.report_changes_source_work_mood

    WorkOutcome.WOUND_UP ->
        R.string.report_changes_background_work_wound_up to R.string.report_changes_source_work_wound_up

    WorkOutcome.SLEEP -> R.string.report_changes_background_work_sleep to R.string.report_changes_source_work_sleep

    WorkOutcome.ALCOHOL ->
        R.string.report_changes_background_work_alcohol to R.string.report_changes_source_work_alcohol
}

/**
 * The smallest swing from the average a line is scaled to, in its own units: the change the doctor's
 * sheet itself calls a change — half a point of mood, half an hour of sleep, two standard drinks a
 * week — and ten points of a share, so a week a hair off the average is not drawn at the frame.
 */
internal val WorkOutcome.minSwing: Double
    get() = when (this) {
        WorkOutcome.MOOD -> MOOD_MIN_CHANGE
        WorkOutcome.WOUND_UP -> 0.1
        WorkOutcome.SLEEP -> SLEEP_MIN_CHANGE_MINUTES / 60.0
        WorkOutcome.ALCOHOL -> 2.0
    }

/** The bars rising from the floor as the page opens, and a switched line rising out of the centre rule. */
internal val BAR_GROW = tween<Float>(durationMillis = 650, easing = FastOutSlowInEasing)
private val LINE_REVEAL = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)

private val SWITCH_SWATCH_WIDTH = 18.dp
