package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

// The sex-life charts: the mornings, what was recorded beside them in the same weeks, and the acts,
// week by week over [INTIMATE_CHART_WEEKS] weeks. Opened from the Intimate screen's top bar and shown
// nowhere else. Every figure is read off [intimateChartsData]; the drawings only place it.
//
// A chart without enough to show is not drawn at all — no card saying "not enough yet" — and a page
// with none of them says only that. The mornings are never drawn on the acts' axes: they are one
// answer a day from a separate check-in, not a count of acts, and a line laid over the acts would be
// read as something the acts did to them.

/**
 * The page under the Intimate screen's top bar. [sex] decides whether the mornings are there at all
 * ([morningCheckOffered]) and which ticks are marked; [alcohol] is whether the profile is offered the
 * alcohol diary ([habitsOffered]).
 *
 * The page scrolls itself and centres its empty line in whatever height it is given, so [modifier]
 * must hand it the bounded room under the top bar — the screen body's `weight(1f)` — and never a
 * scrolling column.
 */
@Composable
internal fun IntimateChartsPage(modifier: Modifier, sex: String?, alcohol: Boolean) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val today = rememberToday()
    val mornings = morningCheckOffered(sex)
    val from = today.minusDays(INTIMATE_CHART_DAYS - 1).toString()
    // A day more of journal than the weeks hold: a file import files a night under the evening it
    // began, so the night that ended on the first morning is filed the day before it. Every series
    // reads its own weeks, so the extra day reaches only that night.
    val journal by remember(from, today) {
        db.dao().journalBetween(today.minusDays(INTIMATE_CHART_DAYS).toString(), today.toString())
    }.collectAsStateWithLifecycle<List<JournalEntry>?>(null)
    val steps by remember(from, today) { db.dao().daysBetween(from, today.toString()) }
        .collectAsStateWithLifecycle<List<DaySteps>?>(null)
    val data by produceState<IntimateChartsData?>(null, journal, steps, today, mornings, alcohol) {
        val rows = journal ?: return@produceState
        val days = steps ?: return@produceState
        value = withContext(Dispatchers.Default) { intimateChartsData(rows, days, today, mornings, alcohol) }
    }
    val shown = data
    when {
        shown == null -> Box(modifier.fillMaxSize())

        shown.empty -> ChartsEmptyNotice(modifier)

        else -> Column(
            modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            shown.mornings?.let { MorningsChartCard(it, shown.weeks) }
            shown.pairs.forEach { PairChartCard(it, shown.weeks) }
            shown.acts?.let { ActsChartCard(it, shown.weeks, sex) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The mornings alone: each week's share of firm or partly, against his own average. */
@Composable
private fun MorningsChartCard(mornings: MorningWeeks, weeks: List<ClosedRange<LocalDate>>) {
    val title = stringResource(R.string.insight_morning_title)
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val spoken = remember(mornings, weeks, resources, locale) {
        spokenChart(resources, locale, title, weeks) { index ->
            val share = mornings.shares[index]
            if (mornings.points[index] == null) {
                resources.getString(R.string.intimate_charts_spoken_gap)
            } else {
                resources.getString(R.string.insight_count_of, share.days, share.of)
            }
        }
    }
    val line = PwnBlueInk
    val average = PwnMuted
    PatternCard(
        title,
        help = {
            PatternNote(stringResource(R.string.insight_morning_note))
            MorningWeekRule()
            ShowRule(MORNING_CHART_MIN_WEEKS)
            PatternResearch(
                stringResource(R.string.insight_morning_research),
                stringResource(R.string.insight_morning_source),
            )
        },
    ) {
        val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
        WeekChart(weeks, spoken, listOf(percent.format(1), percent.format(0))) { plot ->
            fun y(share: Double) = plot.rect.bottom - (share * plot.rect.height).toFloat()
            mornings.average?.let { mean -> drawCentreLine(plot.rect, y(mean), average) }
            drawWeekLine(plot, mornings.points.map { it?.let(::y) }, line, dots = true)
        }
        ChartLegend {
            LegendEntry(stringResource(R.string.insight_solo_mornings)) { drawLegendStroke(line) }
            LegendEntry(stringResource(R.string.intimate_charts_legend_average)) {
                drawLegendStroke(average, dashed = true)
            }
        }
    }
}

/**
 * One comparison: the week's figure as a bar from the floor, and the mornings as one line drawn from
 * a centre line — above it in a week of more firm or partly mornings than his own average, below it
 * in a week of fewer, broken where a week has too few answers. Side by side in the same weeks and no
 * more: the question mark says so, with the research behind the comparison.
 */
@Composable
private fun PairChartCard(chart: MorningPairChart, weeks: List<ClosedRange<LocalDate>>) {
    val title = stringResource(chart.exposure.titleRes())
    val barName = stringResource(chart.exposure.legendRes())
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val spoken = remember(chart, weeks, resources, locale) {
        spokenChart(resources, locale, title, weeks) { index ->
            spokenPairWeek(resources, chart, index, barName, locale)
        }
    }
    val top = chart.bars.maxOf { it ?: 0.0 }
    PatternCard(title, help = { PairHelp(chart.exposure) }) {
        WeekPairChart(
            weeks = weeks,
            spoken = spoken,
            bars = chart.bars,
            top = top,
            axis = listOf(chart.exposure.printed(top, locale), chart.exposure.printed(0.0, locale)),
            line = chart.mornings,
            minSwing = MIN_SWING,
            barName = barName,
            lineName = stringResource(R.string.insight_solo_mornings),
        )
    }
}

/** What a comparison's question mark says: how it is drawn, its floors, the research, not a cause. */
@Composable
private fun PairHelp(exposure: MorningExposure) {
    PatternNote(stringResource(R.string.intimate_charts_pair_note))
    MorningWeekRule()
    PatternNote(pluralStringResource(R.plurals.intimate_charts_mean_rule, WEEK_MIN_READINGS, WEEK_MIN_READINGS))
    if (exposure == MorningExposure.ALCOHOL) {
        PatternNote(
            stringResource(
                R.string.intimate_charts_drinks_note,
                stringResource(R.string.insight_grams_value, STANDARD_DRINK_G.toInt().toString()),
            ),
        )
    }
    ShowRule(MORNING_CHART_MIN_WEEKS)
    if (exposure == MorningExposure.ALCOHOL) {
        PatternResearch(
            stringResource(R.string.insight_morning_alcohol_research),
            stringResource(R.string.insight_morning_alcohol_source),
        )
    }
    PatternDisclaimer()
}

/**
 * "Solo and partnered": acts a week as two lines joined week to week — broken only at a week with
 * nothing written down ([actsChart]) — partnered in the deep data blue and solo in the theme's
 * orange, each week that carried "little desire" marked by a dot on its line and each week that
 * carried "erection difficulty" by a dash — the mark on the line of the act it was ticked on, in
 * that line's colour. Erection difficulty is marked only for a profile offered that tick.
 */
@Composable
private fun ActsChartCard(chart: ActsChart, weeks: List<ClosedRange<LocalDate>>, sex: String?) {
    val title = stringResource(R.string.insight_solo_title)
    val erection = SexNote.ERECTION in sexNotesFor(sex)
    val desireName = stringResource(SexNote.LOW_DESIRE.titleRes)
    val erectionName = stringResource(SexNote.ERECTION.titleRes)
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val spoken = remember(chart, weeks, resources, locale, erection) {
        spokenChart(resources, locale, title, weeks) { index ->
            chart.weeks[index]?.let { spokenActsWeek(resources, it, erection) }
        }
    }
    // Partnered in the blue every data line in the app is drawn in, or in the main accent once a custom
    // palette overrides it, as a chosen control is; solo in the one orange the theme has, the warm ink
    // the medicine screen draws a partial day in. DesignContrastTest holds both to 4.5:1 on the card in
    // the default light and dark schemes; a custom surface colour is not checked against them.
    val partnered = PwnSelectionColor
    val solo = PwnWarn
    val halo = PwnSurfaceElevated
    // The marks' key in the muted ink: on the plot a mark takes its line's colour, so the key names
    // the shape and leaves the colour to the two lines above it.
    val markKey = PwnMuted
    val top = max(chart.weeks.maxOf { week -> week?.let { max(it.partnered, it.solo) } ?: 0 }, ACTS_MIN_TOP)
    PatternCard(
        title,
        help = {
            PatternNote(stringResource(R.string.intimate_charts_acts_note))
            PatternNote(stringResource(R.string.intimate_charts_desire_note, desireName))
            if (erection) PatternNote(stringResource(R.string.intimate_charts_erection_note, erectionName))
            ShowRule(ACTS_CHART_MIN_WEEKS)
            if (morningCheckOffered(sex)) PatternNote(stringResource(R.string.intimate_charts_acts_mornings_note))
            soloResearch(sex).forEach { (text, source) ->
                PatternResearch(stringResource(text), stringResource(source))
            }
        },
    ) {
        val counts = remember(locale) { NumberFormat.getIntegerInstance(locale) }
        WeekChart(weeks, spoken, listOf(counts.format(top), counts.format(0))) { plot ->
            fun y(count: Int) = plot.rect.bottom - count.toFloat() / top * plot.rect.height
            // In a week of as many acts of each kind the two points would coincide and the solo line,
            // drawn last, would hide the partnered one: half a line's width apart, both colours show.
            val nudge = CHART_LINE_WIDTH.toPx() / 2f
            fun tied(week: ActsWeek) = if (week.partnered == week.solo) nudge else 0f
            val partneredY = chart.weeks.map { week -> week?.let { y(it.partnered) + tied(it) } }
            val soloY = chart.weeks.map { week -> week?.let { y(it.solo) - tied(it) } }
            drawWeekLine(plot, partneredY, partnered, dots = false)
            drawWeekLine(plot, soloY, solo, dots = false)
            chart.weeks.forEachIndexed { index, week ->
                val onPartnered = partneredY[index]
                val onSolo = soloY[index]
                if (week == null || onPartnered == null || onSolo == null) return@forEachIndexed
                drawActsWeekMarks(week, plot.x(index), onPartnered, onSolo, erection, ActsInks(partnered, solo, halo))
            }
        }
        ChartLegend {
            LegendEntry(stringResource(R.string.insight_solo_partnered)) { drawLegendStroke(partnered) }
            LegendEntry(stringResource(R.string.insight_solo_solo)) { drawLegendStroke(solo) }
            LegendEntry(desireName) { drawDesireDot(center, markKey, halo) }
            if (erection) LegendEntry(erectionName) { drawDifficultyDash(center, markKey, halo) }
        }
    }
}

/** A week's mornings make a point from [MORNING_WEEK_MIN_ANSWERED] answers; fewer break the line. */
@Composable
private fun MorningWeekRule() {
    PatternNote(
        pluralStringResource(R.plurals.intimate_charts_week_rule, MORNING_WEEK_MIN_ANSWERED, MORNING_WEEK_MIN_ANSWERED),
    )
}

/** The acts chart's three inks: each line's, and the card's that rings every mark. */
private class ActsInks(val partnered: Color, val solo: Color, val halo: Color)

/**
 * One week's marks, each on its own line. Where both lines carry a mark in a week of as many acts of
 * each kind, the two would land on one spot, so they stand side by side instead, partnered on the left.
 */
private fun DrawScope.drawActsWeekMarks(
    week: ActsWeek,
    x: Float,
    onPartnered: Float,
    onSolo: Float,
    erection: Boolean,
    inks: ActsInks,
) {
    val partneredErection = if (erection) week.partneredErection else 0
    val soloErection = if (erection) week.soloErection else 0
    val partneredMarked = week.partneredLowDesire + partneredErection > 0
    val soloMarked = week.soloLowDesire + soloErection > 0
    val spread = if (week.partnered == week.solo && partneredMarked && soloMarked) MARK_SPREAD.toPx() else 0f
    drawTickMarks(
        Offset(x - spread, onPartnered),
        inks.partnered,
        inks.halo,
        week.partneredLowDesire,
        partneredErection,
    )
    drawTickMarks(Offset(x + spread, onSolo), inks.solo, inks.halo, week.soloLowDesire, soloErection)
}

/** A week's marks on its line: a dot for "little desire", and a dash for "erection difficulty" over it. */
private fun DrawScope.drawTickMarks(at: Offset, colour: Color, halo: Color, desire: Int, difficulty: Int) {
    if (desire > 0) drawDesireDot(at, colour, halo)
    if (difficulty > 0) {
        val lift = if (desire > 0) MARK_LIFT.toPx() else 0f
        drawDifficultyDash(Offset(at.x, at.y - lift), colour, halo)
    }
}

/** The dot, ringed in the card's own colour so it stands clear of the line it sits on. */
private fun DrawScope.drawDesireDot(at: Offset, colour: Color, halo: Color) {
    drawCircle(halo, MARK_DOT.toPx() + MARK_HALO.toPx(), at)
    drawCircle(colour, MARK_DOT.toPx(), at)
}

/** The dash: a stroke a little longer than the dot is wide, ringed the same way. */
private fun DrawScope.drawDifficultyDash(at: Offset, colour: Color, halo: Color) {
    val half = MARK_DASH.toPx() / 2f
    val start = Offset(at.x - half, at.y)
    val end = Offset(at.x + half, at.y)
    drawLine(halo, start, end, MARK_THICK.toPx() + MARK_HALO.toPx() * 2f, cap = StrokeCap.Round)
    drawLine(colour, start, end, MARK_THICK.toPx(), cap = StrokeCap.Round)
}

private fun spokenPairWeek(
    resources: Resources,
    chart: MorningPairChart,
    index: Int,
    barName: String,
    locale: Locale,
): String {
    val figure = chart.bars[index]?.let { chart.exposure.printed(it, locale) }
        ?: resources.getString(R.string.intimate_charts_spoken_none)
    val bar = resources.getString(R.string.intimate_charts_spoken_value, barName, figure)
    val off = chart.mornings[index]
    val mornings = resources.getString(
        when {
            off == null -> R.string.intimate_charts_spoken_gap
            off > 0.0 -> R.string.intimate_charts_spoken_above
            off < 0.0 -> R.string.intimate_charts_spoken_below
            else -> R.string.intimate_charts_spoken_at
        },
    )
    return resources.getString(R.string.intimate_charts_spoken_pair, bar, mornings)
}

private fun spokenActsWeek(resources: Resources, week: ActsWeek, erection: Boolean): String {
    val counts = resources.getString(
        R.string.intimate_charts_spoken_acts,
        week.partnered.toString(),
        week.solo.toString(),
    )
    val desire = resources.getString(SexNote.LOW_DESIRE.titleRes)
    val difficulty = resources.getString(SexNote.ERECTION.titleRes)
    val marks = listOfNotNull(
        resources.getString(R.string.insight_solo_tick_partnered, desire).takeIf { week.partneredLowDesire > 0 },
        resources.getString(R.string.insight_solo_tick_solo, desire).takeIf { week.soloLowDesire > 0 },
        resources.getString(R.string.insight_solo_tick_partnered, difficulty).takeIf {
            erection &&
                week.partneredErection > 0
        },
        resources.getString(R.string.insight_solo_tick_solo, difficulty).takeIf { erection && week.soloErection > 0 },
    )
    return marks.fold(counts) { said, mark -> resources.getString(R.string.intimate_charts_spoken_pair, said, mark) }
}

private fun MorningExposure.titleRes(): Int = when (this) {
    MorningExposure.NIGHT -> R.string.insight_morning_night_title
    MorningExposure.STEPS -> R.string.intimate_charts_walking_title
    MorningExposure.ALCOHOL -> R.string.intimate_charts_alcohol_title
}

private fun MorningExposure.legendRes(): Int = when (this) {
    MorningExposure.NIGHT -> R.string.intimate_charts_legend_night
    MorningExposure.STEPS -> R.string.intimate_charts_legend_steps
    MorningExposure.ALCOHOL -> R.string.intimate_charts_legend_drinks
}

/** A bar's figure as the axis and the screen reader say it: steps whole, hours and drinks to a decimal. */
private fun MorningExposure.printed(value: Double, locale: Locale): String = when (this) {
    MorningExposure.STEPS -> wholeNumber(value, locale)
    MorningExposure.NIGHT, MorningExposure.ALCOHOL -> oneDecimal(value, locale)
}

/** The smallest swing from the average the mornings' line is scaled to, in shares: ten points. */
private const val MIN_SWING = 0.1

/** The acts' axis tops out at no less than this, so one act in a week is not drawn at the ceiling. */
private const val ACTS_MIN_TOP = 2

private val MARK_DOT = 4.dp
private val MARK_DASH = 13.dp
private val MARK_THICK = 4.dp
private val MARK_HALO = 1.5.dp
private val MARK_LIFT = 9.dp
private val MARK_SPREAD = 8.dp

/**
 * The research line "Solo and partnered" carries, by the profile: in men, more masturbation went with
 * somewhat less partnered sex; in women, with more. A profile that is neither gets both, each saying
 * whom it is about.
 */
internal fun soloResearch(sex: String?): List<Pair<Int, Int>> {
    val male = R.string.insight_solo_research_male to R.string.insight_solo_source_male
    val female = R.string.insight_solo_research_female to R.string.insight_solo_source_female
    return when (sex) {
        SexAudience.MALE.name -> listOf(male)
        SexAudience.FEMALE.name -> listOf(female)
        else -> listOf(male, female)
    }
}
