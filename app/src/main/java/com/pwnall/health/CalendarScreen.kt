package com.pwnall.health

import android.content.res.Resources
import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToLong

@Composable
fun CalendarScreen(
    modifier: Modifier,
    initialDay: String?,
    profile: UserProfile,
    onEdit: (Long) -> Unit,
    onAdd: (String) -> Unit,
    onOpenVisit: (Long) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val dayMonthYear = remember(locale) { localizedDatePattern(locale, "yMMMMd", "d MMMM yyyy") }
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val today = rememberToday()
    val initialDate = remember(initialDay) { (initialDay?.let(LocalDate::parse) ?: today).coerceAtMost(today) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(initialDate).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var viewMode by rememberSaveable { mutableIntStateOf(0) }
    var expandedMonth by rememberSaveable { mutableStateOf(false) }
    var chartMode by rememberSaveable { mutableIntStateOf(0) }
    var filterName by rememberSaveable { mutableStateOf(CalendarFilter.ALL.name) }
    val filter = CalendarFilter.entries.firstOrNull { it.name == filterName } ?: CalendarFilter.ALL
    val month = YearMonth.parse(monthText).coerceAtMost(YearMonth.from(today))
    val selected = LocalDate.parse(selectedText).coerceAtMost(today)
    // Every tab but the grid asks the by-month/by-year question, so every tab but the grid answers
    // it with its range: a vitals card headed with a year and fed one month of readings would be a
    // heading that lies about the numbers under it.
    val yearly = viewMode != 0 && chartMode == 1
    // Both flows are keyed on the range rather than on the month, because the by-year charts want
    // the whole year at once: switching to them has to re-subscribe, not spread thirty days across
    // twelve columns.
    val from = if (yearly) month.withMonth(1).atDay(1) else month.atDay(1)
    val to = (if (yearly) month.withMonth(12).atEndOfMonth() else month.atEndOfMonth()).coerceAtMost(today)
    val days by remember(from, to) { dao.daysBetween(from.toString(), to.toString()) }
        .collectAsStateWithLifecycle(emptyList())
    val stored by remember(from, to) { dao.journalBetween(from.toString(), to.toString()) }
        .collectAsStateWithLifecycle(emptyList())
    // The age gate takes the sexual diary and the answers about desire off this list as it takes
    // them off the cycle page ([behindSexGate]): a row a restored backup brought is not listed, and
    // so not opened in the editor either, for a profile the app does not offer that log to.
    val acts = sexLogOffered(profile.birthDate, today)
    val journal = remember(stored, acts) { stored.shownTo(acts) }
    val scope = rememberCoroutineScope()
    val appointments by remember { dao.appointments() }.collectAsStateWithLifecycle(emptyList())
    var deleteEntries by remember { mutableStateOf(emptyList<JournalEntry>()) }
    var deleteVisit by remember { mutableStateOf<Appointment?>(null) }

    val byDay = remember(days) { days.associateBy { it.day } }
    val entriesByDay = remember(journal) { journal.groupBy(JournalEntry::day) }
    Column(
        modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background).padding(bottom = LocalBottomBarInset.current),
    ) {
        CalendarHero(
            month = month,
            selected = selected,
            viewMode = viewMode,
            expanded = expandedMonth,
            yearly = yearly,
            onViewMode = { viewMode = it },
            onExpand = { expandedMonth = !expandedMonth },
            onMove = { delta ->
                val next = (if (yearly) month.plusYears(delta.toLong()) else month.plusMonths(delta.toLong()))
                    .coerceAtMost(YearMonth.from(today))
                monthText = next.toString()
                selectedText = calendarMovedSelection(selected, next, today).toString()
            },
            onSelect = {
                if (!it.isAfter(today)) {
                    selectedText = it.toString()
                    expandedMonth = false
                }
            },
            today = today,
        )
        // What shows through the two notches either side of the rounded top: the hero's own flat
        // colour, so the join reads as one surface rather than as a seam between two blues.
        Surface(
            Modifier.fillMaxWidth().background(heroBand),
            shape = RoundedCornerShape(
                topStart = PwnCorners.extraLargeIncreased,
                topEnd = PwnCorners.extraLargeIncreased,
            ),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (viewMode == 0) {
                    val selectedEntries = entriesByDay[selected.toString()].orEmpty()
                    CalendarEventFilter(filter) { filterName = it.name }
                    // Sorted by day once for each change of the records or the filter, rather than filtering
                    // every record of the month once for each of its days, and again on every recomposition —
                    // which the step count alone brings round.
                    val shownByDay = remember(journal, filter) {
                        journal.filter { filter == CalendarFilter.ALL || calendarEntryFilter(it) == filter }
                            .groupBy(JournalEntry::day)
                    }
                    val visitsByDay = remember(appointments, filter) {
                        if (filter == CalendarFilter.ALL || filter == CalendarFilter.VISITS) {
                            appointments.groupBy {
                                Instant.ofEpochMilli(it.atMs).atZone(ZoneId.systemDefault()).toLocalDate()
                            }
                        } else {
                            emptyMap()
                        }
                    }
                    val agendaDates = if (expandedMonth) {
                        calendarAgendaDates(shownByDay.keys.toList(), visitsByDay.keys.toList(), month, today)
                    } else {
                        listOf(selected)
                    }
                    if (agendaDates.isEmpty()) Text(stringResource(R.string.events_no_matches), color = PwnMuted)
                    agendaDates.forEach { date ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    date.dayOfWeek.getDisplayName(
                                        TextStyle.FULL,
                                        locale,
                                    ).replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    date.format(dayMonthYear),
                                    color = PwnMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!expandedMonth) {
                                androidx.compose.material3.FilledTonalButton(onClick = { onAdd(date.toString()) }) {
                                    Text(stringResource(R.string.calendar_add_event))
                                }
                            }
                        }
                        CalendarAgenda(
                            shownByDay[date.toString()].orEmpty(),
                            visitsByDay[date].orEmpty(),
                            profile.imperial,
                            onEdit,
                            onOpenVisit,
                            { deleteEntries = it },
                            { deleteVisit = it },
                        )
                    }
                    if (!expandedMonth) {
                        Text(
                            stringResource(R.string.calendar_day_summary),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        DailySummary(
                            steps = byDay[selected.toString()]?.steps ?: 0,
                            calories = burnedOn(selected.toString(), byDay, entriesByDay, profile),
                            // A session's exercises are one workout.
                            workouts = selectedEntries.filter { it.kind == KIND_ACTIVITY }
                                .distinctBy { it.session ?: it.id }
                                .size,
                            foodCalories = eatenOn(selected.toString(), entriesByDay),
                            sleepMinutes = selectedEntries.filter { it.sleepStartAt != null && it.sleepEndAt != null }
                                .sumOf { (it.sleepEndAt!! - it.sleepStartAt!!).coerceAtLeast(0) / 60_000L }.toInt(),
                            waterMl = selectedEntries.filter { it.kind == KIND_WATER }.sumOf { it.servingG }.toInt(),
                            imperial = profile.imperial,
                        )
                    }
                } else {
                    PwnSegmentedControl(
                        options = listOf<Pair<String, ImageVector?>>(
                            stringResource(R.string.calendar_by_month) to null,
                            stringResource(R.string.calendar_by_year) to null,
                        ),
                        selected = chartMode,
                        onSelected = { chartMode = it },
                    )
                    when {
                        viewMode == 2 -> VitalSection(journal, profile)
                        chartMode == 0 -> MonthCharts(month, byDay, entriesByDay, profile)
                        else -> YearCharts(month.year, byDay, entriesByDay, profile)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    deleteVisit?.let { visit ->
        DestructiveDialog(
            title = stringResource(R.string.delete_record_title),
            body = visit.doctor.ifBlank { visit.place },
            onDismiss = { deleteVisit = null },
            onConfirm = {
                // Confirmed is confirmed: a tab switch that cancels this scope must not undo it.
                scope.launch { withContext(NonCancellable) { dao.deleteAppointment(visit.id) } }
                deleteVisit = null
            },
        )
    }

    deleteEntries.firstOrNull()?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntries = emptyList() },
            title = { Text(stringResource(R.string.delete_record_title)) },
            // A session goes as the one workout it is listed as.
            text = { Text(entry.session ?: entry.shownTitle(LocalResources.current)) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = deleteEntries.map { it.id }
                    // Leaving the screen cancels this scope, and a session cut off halfway would be
                    // half a workout. The next exchange withdraws the rows from Health Connect, and their
                    // tombstones keep it from adopting them back: the mirror publishes the edits still
                    // owed and withdraws nothing.
                    scope.launch {
                        withContext(NonCancellable) {
                            ids.forEach { db.deleteJournalEvent(it) }
                            writeLocalHealthIfAvailable(context, db)
                        }
                    }
                    deleteEntries = emptyList()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntries = emptyList() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Leading empty cells for the locale's first weekday. */
internal fun firstDayOfWeekBlanks(firstOfMonth: DayOfWeek, firstDay: DayOfWeek): Int =
    (firstOfMonth.value - firstDay.value + 7) % 7

@Composable
private fun DailySummary(
    steps: Long,
    calories: Double,
    workouts: Int,
    foodCalories: Double,
    waterMl: Int,
    sleepMinutes: Int,
    imperial: Boolean,
) {
    val kcal = stringResource(R.string.unit_kcal)
    // The two energy tiles both print "kcal", and the picture above each is all that told them
    // apart — a picture a screen reader has no words for. Each tile is read as one sentence that
    // names what it counts.
    val values = listOf(
        DayReadout(R.drawable.ic_art_steps, steps.formatted(), stringResource(R.string.steps)),
        DayReadout(R.drawable.ic_art_calories, calories.shown(), kcal, stringResource(R.string.calendar_burned)),
        DayReadout(R.drawable.ic_art_sport, workouts.toString(), stringResource(R.string.home_workouts)),
        DayReadout(R.drawable.ic_art_food, foodCalories.shown(), kcal, stringResource(R.string.calendar_food)),
        DayReadout(
            R.drawable.ic_art_water,
            formatVolumeNumber(waterMl.toDouble(), imperial, LocalLocale.current.platformLocale),
            stringResource(volumeUnitRes(imperial)),
            stringResource(R.string.water_title),
        ),
        DayReadout(R.drawable.ic_art_sleep, formatSleepClock(sleepMinutes), stringResource(R.string.sleep)),
    )
    PwnCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
            // Three to a line while the widest figure and the widest label, each on one line, fit a
            // third of the card; fewer when they do not. Six across a phone would leave "1 240" and
            // "Тренировки" a fifth of the width each, and at a large text size even a third would ellipsise
            // the label that names the figure — so the line gives way before the label does.
            val perRow = summaryPerRow(values, maxWidth)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                values.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { DailyValue(it, Modifier.weight(1f)) }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * One of the day's figures: its picture, the number, the word printed under it, and — where that
 * word is only a unit — the name a screen reader says in front of the number.
 */
private data class DayReadout(val art: Int, val value: String, val label: String, val name: String? = null) {
    val spoken: String get() = listOfNotNull(name, value, label).joinToString(" ")
}

/** How many of the day's readouts stand on one line at most. */
private const val SUMMARY_PER_ROW = 3

/** The air either side of one readout. */
private val DAILY_VALUE_PAD = 3.dp

/** The most readouts a line of [width] holds with every figure and every label whole. */
@Composable
private fun summaryPerRow(values: List<DayReadout>, width: Dp): Int {
    val measurer = rememberTextMeasurer()
    val valueStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val widest = remember(values, valueStyle, labelStyle, density) {
        val pixels = values.maxOf {
            maxOf(measurer.measure(it.value, valueStyle).size.width, measurer.measure(it.label, labelStyle).size.width)
        }
        with(density) { pixels.toDp() }
    }
    // Never fewer than two to a line: one tile a line would turn six figures into a page of scrolling,
    // and at two a label that still outgrows its half wraps onto its second line.
    return (SUMMARY_PER_ROW downTo MIN_SUMMARY_PER_ROW).firstOrNull { width / it - DAILY_VALUE_PAD * 2 >= widest }
        ?: MIN_SUMMARY_PER_ROW
}

@Composable
private fun DailyValue(readout: DayReadout, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = DAILY_VALUE_PAD).clearAndSetSemantics { contentDescription = readout.spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(themePainterResource(readout.art), null, Modifier.size(30.dp))
        Text(readout.value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Two lines for a label of two words; an ellipsis rather than a cut through a letter if a
        // single word still outgrows a whole line of its own.
        Text(
            readout.label,
            color = PwnMuted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What a day cost in energy.
 *
 * Not a plain sum: a logged walk and the pedometer both saw the same outing, and [movedCalories] is
 * where that overlap is settled.
 */
private fun burnedOn(
    day: String,
    byDay: Map<String, DaySteps>,
    entriesByDay: Map<String, List<JournalEntry>>,
    profile: UserProfile,
): Double = movedCalories(
    entriesByDay[day].orEmpty().filter { it.kind == KIND_ACTIVITY },
    stepCalories(byDay[day]?.steps ?: 0, profile.weightKg, profile.heightCm),
)

/** What the day's food came to, which is a plain sum: two meals are two meals. */
private fun eatenOn(day: String, entriesByDay: Map<String, List<JournalEntry>>): Double =
    entriesByDay[day].orEmpty().filter { it.kind == KIND_EAT }.sumOf(JournalEntry::calories)

/** The month's days as the keys the step and journal maps are built on. */
private fun YearMonth.dayKeys(): List<String> = (1..lengthOfMonth()).map { atDay(it).toString() }

/**
 * The month a day at a time: what was walked, and what was burned against what was eaten.
 */
@Composable
private fun MonthCharts(
    month: YearMonth,
    byDay: Map<String, DaySteps>,
    entriesByDay: Map<String, List<JournalEntry>>,
    profile: UserProfile,
) {
    val keys = month.dayKeys()
    // Every day is named, because the whole plot is what a screen reader is read. The axis under
    // the bars keeps only the 1st and every fifth after it: a number under all thirty-one is a
    // grey band rather than an axis, and nobody counts bars to find the 17th.
    val slots = (1..month.lengthOfMonth()).map { it.toString() }
    val labels = slots.mapIndexed { index, day -> day.takeIf { index == 0 || (index + 1) % 5 == 0 } }
    val burned = keys.map { burnedOn(it, byDay, entriesByDay, profile) }
    val food = keys.map { eatenOn(it, entriesByDay) }
    StepsBarChart(
        title = stringResource(R.string.calendar_steps_chart),
        values = keys.map { byDay[it]?.steps ?: 0L },
        labels = labels,
        slots = slots,
    )
    CalorieLineChart(
        title = stringResource(R.string.calendar_calories_title),
        burned = burned,
        food = food,
        labels = labels,
        slots = slots,
        burnedFigure = burned.sum(),
        foodFigure = food.sum(),
    )
}

/**
 * The year a month at a time. Steps are totals, because a month's walking is a quantity; calories
 * are averages, because a 30-day month next to a 31-day one is not a comparison anyone means to
 * make.
 *
 * The divisor is the days of the month that have actually happened. December divided by 31 in the
 * middle of the year would report an average nobody lived, and the current month is the one place
 * where that would be visible every single day.
 */
@Composable
private fun YearCharts(
    year: Int,
    byDay: Map<String, DaySteps>,
    entriesByDay: Map<String, List<JournalEntry>>,
    profile: UserProfile,
) {
    val locale = LocalLocale.current.platformLocale
    val today = rememberToday()
    val months = (1..MONTHS_IN_YEAR).map { YearMonth.of(year, it) }
    val labels = months.map { it.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale) }
    val elapsed = calendarElapsedDays(months, today)
    val burned = months.map { m -> m.dayKeys().sumOf { burnedOn(it, byDay, entriesByDay, profile) } }
    val food = months.map { m -> m.dayKeys().sumOf { eatenOn(it, entriesByDay) } }
    val lived = elapsed.sum().coerceAtLeast(1)
    StepsBarChart(
        title = stringResource(R.string.calendar_steps_year_chart),
        values = months.map { m -> m.dayKeys().sumOf { byDay[it]?.steps ?: 0L } },
        labels = labels,
        slots = labels,
    )
    CalorieLineChart(
        title = stringResource(R.string.calendar_avg_kcal),
        burned = burned.zip(elapsed) { total, days -> if (days == 0) 0.0 else total / days },
        food = food.zip(elapsed) { total, days -> if (days == 0) 0.0 else total / days },
        labels = labels,
        slots = labels,
        burnedFigure = burned.sum() / lived,
        foodFigure = food.sum() / lived,
    )
}

/** How tall a chart stands, axis labels included. */
private val CHART_HEIGHT = 152.dp

/** The air between the plot and the numbers around it. */
private val AXIS_GAP = 6.dp

/** As wide as a bar is allowed to get, so twelve months do not come out as twelve slabs. */
private val BAR_MAX_WIDTH = 14.dp

/**
 * Steps as one bar per slot — a day of the month, or a month of the year — under the figure they
 * add up to.
 *
 * A slot with nothing counted keeps a faint stub instead of a gap, because a chart with holes in it
 * reads as a chart that broke rather than as days that were not walked.
 */
@Composable
private fun StepsBarChart(title: String, values: List<Long>, labels: List<String?>, slots: List<String>) {
    val bars = values.map(Long::toDouble)
    val gridlines = chartGridlines(bars.maxOrNull() ?: 0.0)
    val ceiling = gridlines.last().takeIf { it > 0.0 } ?: 1.0
    val bar = PwnIndigo
    val grid = PwnBorder
    val numbers = compactNumbers()
    val yLabels = axisText(gridlines.map { numbers.format(it.roundToLong()) })
    val xLabels = axisText(labels)
    val spoken = spokenSteps(LocalResources.current, slots, values)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        values.sum().formatted(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.calendar_chart_total),
                        color = PwnMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).semantics { contentDescription = spoken }) {
                val plot = drawChartFrame(yLabels, xLabels, grid)
                val slot = plot.width / bars.size
                val width = (slot - 2.dp.toPx()).coerceIn(1.dp.toPx(), BAR_MAX_WIDTH.toPx())
                bars.forEachIndexed { index, value ->
                    val height = ((value / ceiling) * plot.height).toFloat().coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        if (value == 0.0) bar.copy(alpha = 0.12f) else bar,
                        Offset(plot.left + slot * index + (slot - width) / 2f, plot.bottom - height),
                        Size(width, height),
                        CornerRadius(width / 2f, width / 2f),
                    )
                }
            }
        }
    }
}

/**
 * Energy in and out over the same slots the bars stand in.
 *
 * Two lines rather than two rows of bars: the question a person brings to this card is which of the
 * two is above the other, and bars answer "how much" one column at a time instead.
 */
@Composable
private fun CalorieLineChart(
    title: String,
    burned: List<Double>,
    food: List<Double>,
    labels: List<String?>,
    slots: List<String>,
    burnedFigure: Double,
    foodFigure: Double,
) {
    val peak = maxOf(burned.maxOrNull() ?: 0.0, food.maxOrNull() ?: 0.0)
    val gridlines = chartGridlines(peak)
    val ceiling = gridlines.last().takeIf { it > 0.0 } ?: 1.0
    val burnedColor = PwnIndigo
    // Food is told from burned by its stroke, not by its colour. The palette's second accent is a
    // slate blue, and against the primary it measures 1.01:1 by day — the two curves would be one
    // curve to anyone reading colour. So food is drawn dashed with open dots,
    // and the legend beside the figures draws the same dash.
    val foodColor = PwnOrange
    val ground = PwnSurfaceElevated
    val grid = PwnBorder
    val numbers = compactNumbers()
    val yLabels = axisText(gridlines.map { numbers.format(it.roundToLong()) })
    val xLabels = axisText(labels)
    val spoken = spokenEnergy(LocalResources.current, slots, burned, food)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SeriesFigure(stringResource(R.string.calendar_burned), burnedFigure, burnedColor, dashed = false)
                    SeriesFigure(stringResource(R.string.calendar_food), foodFigure, foodColor, dashed = true)
                }
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT).semantics { contentDescription = spoken }) {
                val plot = drawChartFrame(yLabels, xLabels, grid)
                // Food first: the two curves cross, and the one being compared against — what the
                // body spent — is the one that should stay readable where they overlap.
                drawSeries(food, ceiling, plot, foodColor, hollowOn = ground)
                drawSeries(burned, ceiling, plot, burnedColor, hollowOn = null)
            }
        }
    }
}

/**
 * The bars in words, because a canvas publishes nothing a screen reader can reach: without this a
 * whole year of a person's walking is simply absent from the page.
 *
 * Every slot that carries something is read out rather than a summary of them — someone who cannot
 * see the plot has no second way to the numbers, and "the best day was the 14th" is the conclusion
 * a chart is drawn for, not the chart. Empty slots are left out: they are drawn as a faint stub
 * exactly so they need not be counted, and twenty spoken zeroes would bury the days that were
 * walked.
 *
 * Only the plot is mute. The title above it and the figure beside it are ordinary text, which a
 * screen reader reaches on its own, so neither is repeated here.
 */
private fun spokenSteps(resources: Resources, slots: List<String>, values: List<Long>): String =
    slots.zip(values)
        .filter { (_, steps) -> steps > 0L }
        .joinToString { (slot, steps) ->
            resources.getString(R.string.calendar_chart_reading, slot, steps.formatted())
        }
        .ifEmpty { resources.getString(R.string.calendar_chart_nothing) }

/**
 * The same for the two energy curves, one slot at a time rather than one curve at a time: which of
 * the two was above the other on a given day is the whole question the card is asked, and two
 * separate recitations of thirty-one numbers leave the reader to align them by memory.
 */
private fun spokenEnergy(
    resources: Resources,
    slots: List<String>,
    burned: List<Double>,
    food: List<Double>,
): String = slots.indices
    .mapNotNull { index ->
        val spent = burned.getOrElse(index) { 0.0 }
        val eaten = food.getOrElse(index) { 0.0 }
        if (spent <= 0.0 && eaten <= 0.0) {
            null
        } else {
            resources.getString(
                R.string.calendar_chart_reading_energy,
                slots[index],
                spent.roundToLong().formatted(),
                eaten.roundToLong().formatted(),
            )
        }
    }
    .joinToString()
    .ifEmpty { resources.getString(R.string.calendar_chart_nothing) }

/**
 * One series in a chart's header: a stroke of the line it is drawn as, what it is, and what it
 * came to. The swatch is the line itself — solid or dashed — rather than a dot of its colour, so the
 * key can be matched to the plot without telling two blues apart.
 */
@Composable
private fun SeriesFigure(name: String, value: Double, color: Color, dashed: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(width = SWATCH_WIDTH, height = 7.dp)) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = if (dashed) foodDashes() else null,
                )
            }
            Text(name, color = PwnMuted, style = MaterialTheme.typography.labelMedium)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(value.roundToLong().formatted(), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.unit_kcal),
                color = PwnMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Axis numbers written short — 20K rather than 20,000 — because the gutter they stand in is as wide
 * as the widest of them, and the locale's own shortening is the only one that is right in all
 * fifteen languages the app is translated into.
 *
 * ICU's formatter rather than [java.text.NumberFormat]'s: the JDK grew a compact instance in 12,
 * Android's copy of java.text did not, and this is the same shortening every other app on the
 * phone uses.
 */
@Composable
internal fun compactNumbers(): CompactDecimalFormat {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) {
        CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
            .apply { maximumFractionDigits = 1 }
    }
}

/**
 * The axis text laid out before the canvas opens.
 *
 * A canvas can only draw text that has already been measured, and measuring is also how the plot
 * knows where to start: the gutter is the widest number, not a margin picked to look about right.
 * A null slot is one the axis deliberately leaves unlabelled.
 */
@Composable
private fun axisText(texts: List<String?>): List<TextLayoutResult?> {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = PwnMuted)
    return texts.map { text -> text?.let { measurer.measure(it, style) } }
}

/**
 * The dashed rulings, the numbers up the left and the ticks under the plot, drawn before the data
 * so the data sits over them. What comes back is the rectangle the series themselves get.
 */
private fun DrawScope.drawChartFrame(
    yLabels: List<TextLayoutResult?>,
    xLabels: List<TextLayoutResult?>,
    grid: Color,
): Rect {
    val gap = AXIS_GAP.toPx()
    val gutter = yLabels.mapNotNull { it?.size?.width }.maxOrNull()?.toFloat() ?: 0f
    val footer = xLabels.mapNotNull { it?.size?.height }.maxOrNull()?.toFloat() ?: 0f
    // The top ruling carries a label centred on it, so half of that label lives above the plot.
    val crown = (yLabels.lastOrNull()?.size?.height ?: 0) / 2f
    val plot = Rect(gutter + gap, crown, size.width, size.height - footer - gap)
    val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val bands = (yLabels.size - 1).coerceAtLeast(1)
    yLabels.forEachIndexed { index, label ->
        val y = plot.bottom - plot.height * index / bands
        drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), 1f, pathEffect = dashes)
        label?.let { drawText(it, topLeft = Offset(plot.left - gap - it.size.width, y - it.size.height / 2f)) }
    }
    val slot = plot.width / xLabels.size
    // A label that would touch the one before it is dropped rather than drawn into it: twelve
    // months under a plot narrowed by its own gutter would run "AugSep" together as one word.
    var occupied = plot.left - gap
    xLabels.forEachIndexed { index, label ->
        label?.let {
            val left = plot.left + slot * (index + 0.5f) - it.size.width / 2f
            if (left >= occupied + gap) {
                drawText(it, topLeft = Offset(left, plot.bottom + gap))
                occupied = left + it.size.width
            }
        }
    }
    return plot
}

/**
 * One calorie series: a curve through its points with the ground under it tinted.
 *
 * The curve is a cubic through the midpoints between neighbours. Straight segments through a point
 * a day come out as a saw, and this is decoration over the same values rather than a claim about
 * the hours in between — which is also why only a year's twelve points get a dot each. Thirty-one
 * dots are a smear.
 *
 * A series given [hollowOn] is the second one: its curve is dashed and its dots are rings filled
 * with that ground, so the two lines differ in shape and not only in colour.
 */
private fun DrawScope.drawSeries(values: List<Double>, ceiling: Double, plot: Rect, color: Color, hollowOn: Color?) {
    if (values.isEmpty()) return
    val slot = plot.width / values.size
    fun x(index: Int) = plot.left + slot * (index + 0.5f)
    fun y(value: Double) = plot.bottom - ((value / ceiling) * plot.height).toFloat()
    val line = Path()
    values.forEachIndexed { index, value ->
        if (index == 0) {
            line.moveTo(x(index), y(value))
        } else {
            val midX = (x(index - 1) + x(index)) / 2f
            line.cubicTo(midX, y(values[index - 1]), midX, y(value), x(index), y(value))
        }
    }
    val under = Path().apply {
        addPath(line)
        lineTo(x(values.lastIndex), plot.bottom)
        lineTo(x(0), plot.bottom)
        close()
    }
    val tint = Brush.verticalGradient(
        listOf(color.copy(alpha = 0.22f), Color.Transparent),
        startY = plot.top,
        endY = plot.bottom,
    )
    drawPath(under, tint)
    val dashes = if (hollowOn != null) foodDashes() else null
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dashes))
    if (values.size <= MONTHS_IN_YEAR) {
        values.forEachIndexed { index, value ->
            val at = Offset(x(index), y(value))
            if (hollowOn == null) {
                drawCircle(color, 3.dp.toPx(), at)
            } else {
                drawCircle(hollowOn, 3.dp.toPx(), at)
                drawCircle(color, 3.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** The food curve's dash, shared with its swatch in the legend so the two are visibly one line. */
private fun DrawScope.foodDashes(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

/** How long a legend's stroke is: enough to show a dash and the gap after it. */
private val SWATCH_WIDTH = 16.dp

/** The months a year chart stands on, and the most points a line can still carry a dot each. */
private const val MONTHS_IN_YEAR = 12

/** Five numbers up the side, four bands between them. */
private const val GRIDLINE_BANDS = 4

/**
 * The steps an axis is allowed to climb in, within a decade. Five thousand at a time is read
 * without thinking; four thousand six hundred and nine is not.
 */
private val AXIS_STEPS = listOf(1.0, 2.0, 4.0, 5.0, 8.0, 10.0)

/**
 * The y-axis rulings for a chart whose tallest value is [peak]: zero up to a round number above it,
 * in [GRIDLINE_BANDS] equal steps.
 *
 * A chart's top line is a claim about its scale, and a claim of "18,437" hands the reader the
 * arithmetic the chart exists to save them. The ceiling is therefore lifted to the next round step
 * — 20,000, over four bands of 5,000 — and everything is measured against that instead.
 *
 * A range with nothing in it gets the baseline alone: four evenly spaced numbers over an empty
 * chart are a scale invented for data that does not exist.
 */
internal fun chartGridlines(peak: Double): List<Double> {
    if (peak <= 0.0) return listOf(0.0)
    val rough = peak / GRIDLINE_BANDS
    var decade = 1.0
    while (decade * 10.0 <= rough) {
        decade *= 10.0
    }
    while (decade > rough) {
        decade /= 10.0
    }
    val step = decade * AXIS_STEPS.first { it * decade >= rough }
    return (0..GRIDLINE_BANDS).map { it * step }
}

/**
 * The vitals tab: what a cuff, an oximeter, a thermometer or a scale wrote into this range.
 *
 * The cards themselves are [VitalCards], which owns its own heading, its own order and its own
 * trend. What is left for the calendar is the one thing only the calendar knows: that this tab was
 * asked for at all. [VitalCards] draws nothing whatsoever when the range holds no measurement — a
 * section title over nothing is furniture — and a tab that answers a tap with a blank page reads as
 * a screen that broke rather than as a month nobody measured anything in.
 *
 * So the question is asked here too. It is the same walk the cards run, behind the same kind of key,
 * which means it happens when the range changes rather than once a frame; one extra pass over a
 * month is the price of the sentence.
 *
 * The whole profile goes down rather than the sex alone: a band can depend on the birth date as
 * well — the adult ones are withheld from a child — so the cards need both halves of the person.
 */
@Composable
private fun VitalSection(journal: List<JournalEntry>, profile: UserProfile) {
    val measured = remember(journal, profile) { vitalHistory(journal, profile).isNotEmpty() }
    if (measured) {
        VitalCards(journal, profile, Modifier.fillMaxWidth())
    } else {
        PwnCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.vitals_empty),
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MIN_SUMMARY_PER_ROW = 2
