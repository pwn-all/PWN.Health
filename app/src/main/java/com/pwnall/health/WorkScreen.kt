package com.pwnall.health

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * How far back the Work page itself looks: the whole of this week, a session begun the night before
 * it, and the sessions the timer stopped by itself, whose cards stay up for this long.
 */
private const val WORK_WINDOW_DAYS = 20L

/**
 * How far back the history lists: this week and the twenty-five before it, half a year of sessions
 * read by kind so the rest of those months' rows are never loaded. Whole weeks, because every week
 * is listed under a heading carrying its net time, and the oldest one cut off at a day inside it
 * would print a total for less than the week it names. Older sessions are still in the journal, and
 * the history says so when there are any.
 */
private const val WORK_HISTORY_WEEKS = 26

/** The days a past session can be typed onto. */
private const val WORK_TYPED_DAYS = 7L

/** Where a typed session starts before it is moved: an ordinary working day. */
private const val WORK_TYPED_START = 9 * 60
private const val WORK_TYPED_END = 17 * 60

/** How often the session page reads the clock again, for its "ends in the future" check. */
private const val WORK_FORM_CLOCK_MS = 30_000L

/** The long-day setting's range, in whole hours. */
private val WORK_LONG_DAY_HOURS = (6..16).toList()

/**
 * The Work screen's own pages. The charts, the history and the settings are each a page of their
 * own, opened from the top bar; the edit page is one session, reached from a capped card or a history
 * row, and goes back to whichever of the two it was opened from.
 */
private enum class WorkPage { MAIN, CHARTS, HISTORY, SETTINGS, EDIT }

private val WORK_PAGE_SAVER: Saver<WorkPage, String> =
    Saver(save = { it.name }, restore = { name -> WorkPage.entries.firstOrNull { it.name == name } ?: WorkPage.MAIN })

/**
 * The work timer: its state and running time, today and this week, and the controls — pinned under
 * the scroll, the way the route recorder pins its own, because a timer is started and stopped in a
 * hurry.
 *
 * A summary rather than a form. What the hours went with is on the charts page, the sessions
 * themselves on the history page and the two settings on a page of their own, all opened from the
 * top bar; a session is changed on a page of its own, reached from its row, and nothing unfolds
 * inside a list. The charts are handed the two settings — the usual days off and where a long day
 * starts — so a change to either reads through at once, and read their own weeks of the journal: the
 * screen holds only the work rows of the history's half year. [alcohol] is whether the profile is
 * offered the alcohol diary, so whether the drinks are set beside the hours at all.
 */
@Composable
fun WorkScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    alcohol: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val db = remember { StepsDb.get(app) }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val zone = rememberZone()
    val initial = remember { WorkClock.snapshot(app) }
    val clock by remember { WorkClock.flow(app) }.collectAsStateWithLifecycle(initial)
    val since = remember(today, locale) {
        workHistorySince(today, WeekFields.of(locale).firstDayOfWeek, WORK_HISTORY_WEEKS)
    }
    val work by remember(since) {
        db.dao().journalOfKindBetween(KIND_WORK, since.toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    // The Work page itself needs only its last three weeks, and it is drawn again every second while
    // the timer runs: the half year is left to the history.
    val recent = remember(work, today) {
        val from = today.minusDays(WORK_WINDOW_DAYS).toString()
        work.filter { it.day >= from }
    }
    // Read out of the rows' payloads once a change of rows, not once a tick of the second hand.
    val recentSegments = remember(recent) { recent.flatMap(::workSegmentsOf) }
    val capped = remember(recent) { recent.filter { workFlag(it, WORK_CAPPED) } }
    val session = clock.session

    // A ticking Text rather than a chronometer view: the second hand runs only while the session
    // does, and a paused one stands still at its net time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.state, session.changedAt) {
        now = System.currentTimeMillis()
        while (session.state == WorkState.RUNNING) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        WorkClock.settle(app)
        // A reboot's broadcast can be held back for minutes on some builds; opening the screen puts
        // the workplace check's alarm back on the clock without waiting for it.
        WorkPlaceChecks.reschedule(app)
    }

    var status by rememberStatusLine()
    var page by rememberSaveable(stateSaver = WORK_PAGE_SAVER) { mutableStateOf(WorkPage.MAIN) }
    // Where a session's page goes back to: the Work page, or the history it was opened from.
    var editFrom by rememberSaveable(stateSaver = WORK_PAGE_SAVER) { mutableStateOf(WorkPage.MAIN) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Held above the pages rather than inside the Work page, which leaves composition while one of
    // them is open: back from the charts, the history or the settings, the page is where it was left.
    val mainScroll = rememberScrollState()
    // Every visit to the history starts at its newest week; coming back to it from one of its
    // sessions finds the list where it was left.
    var historyVisit by rememberSaveable { mutableIntStateOf(0) }
    val historyScroll = rememberSaveable(historyVisit, saver = ScrollState.Saver) { ScrollState(0) }
    val back: () -> Unit = { page = if (page == WorkPage.EDIT) editFrom else WorkPage.MAIN }
    BackHandler(enabled = page != WorkPage.MAIN, onBack = back)
    val openSession: (Long?) -> Unit = { id ->
        editFrom = if (page == WorkPage.HISTORY) WorkPage.HISTORY else WorkPage.MAIN
        editingId = id
        page = WorkPage.EDIT
    }

    if (page != WorkPage.MAIN) {
        // Each page takes the Work page's place in the Work page's own window, under the same top
        // bar, and its back arrow brings the Work page back. A panel of their own would make each
        // a window opened inside a window: a second handle and a second rounded edge, under a
        // dimmed strip of the first.
        Box(modifier.fillMaxSize()) {
            when (page) {
                WorkPage.CHARTS -> Column(Modifier.fillMaxSize()) {
                    PwnTopBar(stringResource(R.string.intimate_charts_title), back)
                    WorkChartsPage(Modifier.weight(1f), clock.daysOff, clock.longDayMinutes, alcohol)
                }

                WorkPage.HISTORY -> WorkHistoryPage(work, since, historyScroll, status, back, openSession)

                WorkPage.SETTINGS -> WorkSettingsPage(
                    clock = clock,
                    onBack = back,
                    onDaysOff = { WorkClock.setDaysOff(app, it) },
                    onLongDay = { WorkClock.setLongDayMinutes(app, it) },
                )

                WorkPage.EDIT -> WorkSessionPage(
                    entryId = editingId,
                    today = today,
                    work = work,
                    live = session,
                    onBack = back,
                    onDone = { word ->
                        status = word
                        page = editFrom
                    },
                )

                WorkPage.MAIN -> Unit
            }
        }
        return
    }

    val byDay = workMinutesByDay(emptyList(), zone, recentSegments + session.liveSegments(now))
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }

    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.work_title), onBack) {
            IconButton(
                onClick = { page = WorkPage.CHARTS },
                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.intimate_charts_title))
            }
            IconButton(
                onClick = {
                    historyVisit++
                    page = WorkPage.HISTORY
                },
                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
            ) {
                Icon(
                    Icons.Outlined.History,
                    stringResource(R.string.work_history_title),
                )
            }
            IconButton(onClick = {
                page = WorkPage.SETTINGS
            }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.Outlined.Settings,
                    stringResource(R.string.work_settings_title),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(mainScroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WorkStatusCard(session, now, zone)
                capped.forEach { row ->
                    WorkCappedCard(row, dayFormat, zone) { openSession(row.id) }
                }
                WorkWeekCard(byDay, today, locale, clock)
                Spacer(Modifier.height(8.dp))
            }
            PwnActionFlash(status)
        }
        WorkControls(session.state) { from, to ->
            scope.launch {
                // A tap the person saw land is not half applied because the screen was left.
                val step = withContext(NonCancellable) { WorkClock.act(app, from, to) }
                if (step.moved) status = workFlashWord(resources, from, to, step)
            }
        }
    }
}

@Composable
private fun WorkStatusCard(session: WorkSession, now: Long, zone: ZoneId) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(workStateWord(session.state)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (session.state == WorkState.RUNNING) MaterialTheme.colorScheme.primary else PwnMuted,
                )
                // What the timer counts, in every state: it is the running figure it explains.
                HelpDot(stringResource(R.string.work_title)) {
                    Text(stringResource(R.string.work_idle_hint))
                    // The one thing the timer does that no button says: a pause as long as a night
                    // is not carried on, so the Resume after it reads Started and counts from nothing.
                    Text(stringResource(R.string.work_pause_hint, WORK_PAUSE_MAX_MS / 3_600_000L))
                }
            }
            if (session.state != WorkState.IDLE) {
                Text(
                    stopwatchText(session.liveSegments(now).sumOf { it.length }),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                val started = Instant.ofEpochMilli(session.sessionStart).atZone(zone).toLocalTime()
                // Every closed segment ended in a pause: the ones since resumed, and the one standing.
                val pauses = session.closed.size
                Text(
                    listOfNotNull(
                        stringResource(R.string.work_since, clockText(started.hour * 60 + started.minute)),
                        pluralStringResource(R.plurals.work_pauses, pauses, pauses).takeIf { pauses > 0 },
                    ).joinToString(" · "),
                    color = PwnMuted,
                )
            }
        }
    }
}

/** A session the timer stopped by itself, until its real end is set. */
@Composable
private fun WorkCappedCard(entry: JournalEntry, dayFormat: DateTimeFormatter, zone: ZoneId, onOpen: () -> Unit) {
    val day = Instant.ofEpochMilli(entry.occurredAt).atZone(zone).format(dayFormat)
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.work_capped_card, WORK_CAP_MS / 3_600_000L, day))
            Text(
                stringResource(R.string.work_set_end),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun WorkWeekCard(byDay: Map<LocalDate, Double>, today: LocalDate, locale: Locale, clock: WorkClockState) {
    val resources = LocalResources.current
    val weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
    val days = (0L..6L).map { weekStart.plusDays(it) }
    val todayMinutes = (byDay[today] ?: 0.0).toInt()
    val weekMinutes = days.sumOf { byDay[it] ?: 0.0 }.toInt()
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Text(
                stringResource(R.string.work_today, workDurationText(resources, todayMinutes)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.work_week, workDurationText(resources, weekMinutes)), color = PwnMuted)
            Spacer(Modifier.height(8.dp))
            WorkWeekBars(days, byDay, today, clock, locale)
        }
    }
}

/**
 * This week, a bar a day. Scaled to the long day or the longest day, whichever is more, so a short
 * week is drawn as short rather than stretched to fill the card. Days off are named in the muted
 * colour and in italics, and said to be days off in the spoken description; nothing is coloured as
 * good or bad.
 */
@Composable
private fun WorkWeekBars(
    days: List<LocalDate>,
    byDay: Map<LocalDate, Double>,
    today: LocalDate,
    clock: WorkClockState,
    locale: Locale,
) {
    val resources = LocalResources.current
    val peak = maxOf(days.maxOf { byDay[it] ?: 0.0 }, clock.longDayMinutes.toDouble(), 1.0)
    val accent = MaterialTheme.colorScheme.primary
    val spoken = days.joinToString(resources.getString(R.string.list_comma)) { day ->
        val name = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        val worked = workDurationText(resources, (byDay[day] ?: 0.0).toInt())
        if (day.dayOfWeek in clock.daysOff) {
            resources.getString(R.string.m3_work_day_off_spoken, name, worked)
        } else {
            "$name $worked"
        }
    }
    // A tapped bar says what it stands for; tapped again, it lets go. The screen reader has every
    // day's figure in the description already, so this is for the eye only.
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Text(
            picked?.let {
                stringResource(
                    R.string.work_bar_value,
                    it.format(dayFormat),
                    workDurationText(
                        resources,
                        (
                            byDay[it]
                                ?: 0.0
                            ).toInt(),
                    ),
                )
            }.orEmpty(),
            // At least a line's height, so the bars do not jump when a pick fills it, and taller when
            // the font is: a fixed 24dp would cut the readout off once a large font makes its line
            // taller.
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
        PickableBarRow(
            count = days.size,
            picked = days.indexOf(picked).takeIf { it >= 0 },
            onPick = { index -> picked = index?.let(days::get) },
            gap = WORK_DAY_GAP,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) {
            days.forEach { day ->
                val minutes = byDay[day] ?: 0.0
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    ChartBar(
                        fraction = (minutes / peak).toFloat(),
                        // A day with nothing in it keeps a stub, so the week still reads as seven.
                        stub = 0.04f,
                        // The empty day's stub in the outline colour, which clears 3:1 on the card,
                        // rather than a pale wash of the bars' blue, which does not.
                        color = if (minutes > 0.0) accent else MaterialTheme.colorScheme.outline,
                        picked = picked == day,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        WorkDayLabels(days, today, clock.daysOff, locale)
    }
}

/**
 * The days' names under the bars: abbreviated, and cut to a letter only where an abbreviation does not
 * fit its seventh of the card — a large font or a long locale — rather than clipped mid-name. The
 * full names are in the chart's spoken description and in the tap readout. A usual day off is set
 * in italics as well as in the muted colour, so it is not told by colour alone.
 */
@Composable
private fun WorkDayLabels(days: List<LocalDate>, today: LocalDate, daysOff: Set<DayOfWeek>, locale: Locale) {
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val column = with(density) { ((maxWidth - WORK_DAY_GAP * (days.size - 1)) / days.size).roundToPx() }
        val short = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale) }
        // Measured in bold, the weight today's name is set in, so every name is held to the widest.
        val fits = remember(short, style, column, density) {
            short.all { measurer.measure(it, style.copy(fontWeight = FontWeight.Bold)).size.width <= column }
        }
        val names = if (fits) {
            short
        } else {
            days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WORK_DAY_GAP)) {
            days.forEachIndexed { index, day ->
                val off = day.dayOfWeek in daysOff
                Text(
                    names[index],
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    style = style,
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (off) FontStyle.Italic else FontStyle.Normal,
                    color = if (off) PwnMuted else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The space between two days, the same under the bars and under their names. */
private val WORK_DAY_GAP = 8.dp

@Composable
private fun WorkSessionRow(entry: JournalEntry, dayFormat: DateTimeFormatter, zone: ZoneId, onOpen: () -> Unit) {
    val segments = workSegmentsOf(entry)
    val start = Instant.ofEpochMilli(segments.firstOrNull()?.from ?: entry.occurredAt).atZone(zone)
    val end = Instant.ofEpochMilli(segments.lastOrNull()?.to ?: entry.occurredAt).atZone(zone)
    val range = stringResource(
        R.string.sleep_clock_summary,
        clockText(start.hour * 60 + start.minute),
        clockText(end.hour * 60 + end.minute),
    )
    val details = listOfNotNull(
        entry.workDetails(),
        stringResource(R.string.work_session_typed).takeIf { workFlag(entry, WORK_TYPED) },
        stringResource(R.string.work_session_capped).takeIf { workFlag(entry, WORK_CAPPED) },
    ).joinToString(" · ")
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${start.format(dayFormat)} · $range", style = MaterialTheme.typography.titleSmall)
                Text(details, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = PwnMuted,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Every session of the last [WORK_HISTORY_WEEKS] weeks, newest first under a heading a week that
 * carries the week's net time, and the way to type in a session that was never timed. A row opens
 * the session's own page; nothing unfolds in the list. Sessions older than the window are not read,
 * and the page says it stops there only when there are some.
 */
@Composable
private fun WorkHistoryPage(
    work: List<JournalEntry>,
    since: LocalDate,
    scroll: ScrollState,
    status: String?,
    onBack: () -> Unit,
    onOpen: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val db = remember { StepsDb.get(context.applicationContext) }
    val zone = rememberZone()
    val older by remember(since) { db.dao().hasJournalBefore(KIND_WORK, since.toString()) }
        .collectAsStateWithLifecycle(false)
    val weeks = remember(work, locale) { workHistoryWeeks(work, WeekFields.of(locale).firstDayOfWeek) }
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }
    val weekFormat = remember(locale) { localizedDatePattern(locale, "dMMM", "d MMM") }
    Column(Modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.work_history_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Above the weeks rather than under them: half a year of rows would put it a long
                // scroll away from the page it is on.
                OutlinedButton(onClick = { onOpen(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.work_add_past))
                }
                // Only when there are none at all: with older ones out of the window, the line under
                // the list says where it stops, and "no sessions yet" would be untrue.
                if (weeks.isEmpty() && !older) {
                    Text(stringResource(R.string.work_sessions_empty), color = PwnMuted)
                }
                weeks.forEach { week ->
                    WorkHistoryHeading(week, weekFormat)
                    week.sessions.forEach { row -> WorkSessionRow(row, dayFormat, zone) { onOpen(row.id) } }
                }
                if (older) {
                    Text(
                        pluralStringResource(R.plurals.work_history_window, WORK_HISTORY_WEEKS, WORK_HISTORY_WEEKS),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            PwnActionFlash(status)
        }
    }
}

/** "Week of 14 Sep" and the week's net time, read out as one heading. */
@Composable
private fun WorkHistoryHeading(week: WorkHistoryWeek, format: DateTimeFormatter) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.work_history_week, week.start.format(format)),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            workDurationText(LocalResources.current, week.netMinutes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The two settings the later comparisons read, on a page of their own, in numbered blocks as booking
 * a visit is ([StepCard]). Both are the app's own defaults and are said to be: weekends differ by
 * country, and a "long day" is a line the person draws, not a limit anybody set.
 *
 * The days off are the seven circles a workout template's days are picked on ([DayCircles]). The long
 * day is the page's one measured value, so its ruler is the large one the period length uses — the
 * number big over it, the strip tall and its ticks far enough apart to aim at — rather than the
 * default one, on which eleven hours would be a strip a hundred dp wide in the middle of the card,
 * each hour under a fingertip's width.
 *
 * Under them, in a build that declares location all the time, the automatic start by location
 * (WorkPlaceSettings.kt), which is off until it is switched on and confirms what it saves with the
 * page's own centre flash.
 */
@Composable
private fun WorkSettingsPage(
    clock: WorkClockState,
    onBack: () -> Unit,
    onDaysOff: (Set<DayOfWeek>) -> Unit,
    onLongDay: (Int) -> Unit,
) {
    val resources = LocalResources.current
    val hours = (clock.longDayMinutes / 60).coerceIn(WORK_LONG_DAY_HOURS.first(), WORK_LONG_DAY_HOURS.last())
    val title = stringResource(R.string.work_settings_page_title)
    var status by rememberStatusLine()
    // Only a build that declares location all the time offers the automatic start.
    val context = LocalContext.current
    val placeAvailable = remember { workPlaceAvailable(context) }
    Column(Modifier.fillMaxSize()) {
        // What the two settings are for is said once, for both, beside the page's name.
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.work_settings_note)) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize()
                    .panelVerticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepCard(1, stringResource(R.string.work_days_off)) {
                    DayCircles(clock.daysOff.sumOf { it.routineBit() }) { mask ->
                        onDaysOff(DayOfWeek.entries.filterTo(mutableSetOf()) { mask and it.routineBit() != 0 })
                    }
                }
                StepCard(2, stringResource(R.string.work_long_day)) {
                    Text(
                        stringResource(R.string.work_long_day_value, hours),
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    RulerPicker(
                        values = WORK_LONG_DAY_HOURS,
                        value = hours,
                        onValue = { onLongDay(it * 60) },
                        label = { resources.getString(R.string.work_long_day_value, it) },
                        height = 104.dp,
                        spacing = 26.dp,
                        // Tall on the even hours: the ruler's own rule, every fifth tick from the first,
                        // would stand 6, 11 and 16 up, which is no rhythm a clock has.
                        major = { it % 2 == 0 },
                    )
                }
                if (placeAvailable) {
                    StepCard(3, stringResource(R.string.work_place_title), help = { WorkPlaceHelp() }) {
                        WorkPlaceSection(onFlash = { status = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            PwnActionFlash(status)
        }
    }
}

/**
 * idle [Start]; running [Pause][Stop]; paused [Resume][Stop]. Stop keeps its place in both.
 *
 * Material's medium height with its 24dp icon, since these are started and stopped in a hurry. The
 * pair shares a line while both labels fit whole and stands one above the other once they do not: a
 * weighted item in a flow row is placed by its narrowest width, which for a label is its longest
 * word, so a large font or a long locale moves Stop down a line instead of breaking a word in two.
 */
@Composable
private fun WorkControls(state: WorkState, onTap: (WorkState, WorkState) -> Unit) {
    val hero = Modifier.heightIn(min = PwnDimensions.mediumButtonHeight)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        when (state) {
            WorkState.IDLE -> Button(
                onClick = { onTap(WorkState.IDLE, WorkState.RUNNING) },
                modifier = hero.weight(1f),
            ) { WorkControlLabel(R.drawable.ic_work_play, R.string.work_start) }

            WorkState.RUNNING -> {
                FilledTonalButton(
                    onClick = { onTap(WorkState.RUNNING, WorkState.PAUSED) },
                    modifier = hero.weight(1f),
                ) { WorkControlLabel(R.drawable.ic_work_pause, R.string.work_pause) }
                Button(
                    onClick = { onTap(WorkState.RUNNING, WorkState.IDLE) },
                    modifier = hero.weight(1f),
                ) { WorkControlLabel(R.drawable.ic_work_stop, R.string.work_stop) }
            }

            WorkState.PAUSED -> {
                FilledTonalButton(
                    onClick = { onTap(WorkState.PAUSED, WorkState.RUNNING) },
                    modifier = hero.weight(1f),
                ) { WorkControlLabel(R.drawable.ic_work_play, R.string.work_resume) }
                Button(
                    onClick = { onTap(WorkState.PAUSED, WorkState.IDLE) },
                    modifier = hero.weight(1f),
                ) { WorkControlLabel(R.drawable.ic_work_stop, R.string.work_stop) }
            }
        }
    }
}

@Composable
private fun WorkControlLabel(icon: Int, label: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(PwnDimensions.mediumButtonIcon))
    Spacer(Modifier.size(PwnDimensions.buttonGap))
    Text(stringResource(label))
}

/**
 * One session's page: the day it started, its start and end on the same dial the sleep form uses,
 * and Delete in the top bar. With no [entryId] it types in a session that was never timed, marked as
 * typed afterwards so a recalled day is never mistaken for a timed one.
 */
@Composable
private fun WorkSessionPage(
    entryId: Long?,
    today: LocalDate,
    work: List<JournalEntry>,
    live: WorkSession,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val db = remember { StepsDb.get(context.applicationContext) }
    val original by produceState<JournalEntry?>(null, entryId) {
        value = entryId?.let { db.dao().journalEntry(it) }
    }
    val title = stringResource(if (entryId == null) R.string.work_add_title else R.string.work_edit_title)
    if (entryId != null && original == null) {
        Column(Modifier.fillMaxSize()) { PwnTopBar(title, onBack) }
        return
    }
    key(original?.id) { WorkSessionForm(original, today, title, work, live, onBack, onDone) }
}

@Composable
private fun WorkSessionForm(
    original: JournalEntry?,
    today: LocalDate,
    title: String,
    work: List<JournalEntry>,
    live: WorkSession,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val db = remember { StepsDb.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val zone = rememberZone()
    val segments = remember(original) { original?.let(::workSegmentsOf).orEmpty() }
    val startAt = segments.firstOrNull()?.let { Instant.ofEpochMilli(it.from).atZone(zone) }
    val endAt = segments.lastOrNull()?.let { Instant.ofEpochMilli(it.to).atZone(zone) }
    val ownDay = remember(original) { workOwnDay(original, segments, zone) }
    var dayText by rememberSaveable { mutableStateOf((ownDay ?: today).toString()) }
    var startMinute by rememberSaveable {
        mutableIntStateOf(startAt?.let { it.hour * 60 + it.minute } ?: WORK_TYPED_START)
    }
    var endMinute by rememberSaveable { mutableIntStateOf(endAt?.let { it.hour * 60 + it.minute } ?: WORK_TYPED_END) }
    var busy by remember { mutableStateOf(false) }
    val day = LocalDate.parse(dayText)
    val (startMs, endMs) = workEpochRange(day, startMinute, endMinute, zone)
    val kept = if (original == null) listOf(WorkSegment(startMs, endMs)) else reshapeSegments(segments, startMs, endMs)
    val net = kept.sumOf { it.length }
    val pauses = (kept.size - 1).coerceAtLeast(0)
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }
    val offered = workDayChoices(today, ownDay, day)
    // Read again every so often, so an end that was still to come when the page opened is allowed
    // once it has passed, without the dial having to be touched.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(WORK_FORM_CLOCK_MS)
            value = System.currentTimeMillis()
        }
    }
    val problem = workSaveProblem(kept, original?.id, work, live, now)

    Column(Modifier.fillMaxSize()) {
        PwnTopBar(
            title,
            onBack,
            content = original?.let { entry ->
                {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                withContext(NonCancellable) { db.deleteJournalEvent(entry.id) }
                                onDone(resources.getString(R.string.work_flash_deleted))
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                    ) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete))
                    }
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.work_day_label), style = MaterialTheme.typography.labelLarge, color = PwnMuted)
            // One day to a session, so the chips are a group of radio buttons to a screen reader
            // rather than a row of boxes to tick.
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { option ->
                    PwnFilterChip(
                        selected = option == day,
                        onClick = { dayText = option.toString() },
                        label = option.format(dayFormat),
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            PwnCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(PwnDimensions.cardPadding).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SleepDial(
                        startMinute = startMinute,
                        endMinute = endMinute,
                        onStart = { startMinute = it },
                        onEnd = { endMinute = it },
                        words = DialWords(
                            caption = stringResource(R.string.work_dial_caption),
                            startLater = stringResource(R.string.work_dial_start_later),
                            startEarlier = stringResource(R.string.work_dial_start_earlier),
                            endLater = stringResource(R.string.work_dial_end_later),
                            endEarlier = stringResource(R.string.work_dial_end_earlier),
                        ),
                        startIcon = ImageVector.vectorResource(R.drawable.ic_work_play),
                        endIcon = ImageVector.vectorResource(R.drawable.ic_work_stop),
                    )
                    // The dial is the gesture, not the only door: it snaps to five minutes, a screen
                    // reader moves it a quarter of an hour at a time and a keyboard not at all, so both
                    // clocks are also set to the minute here, as the sleep form's wheels set a night.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ClockTimePickerCard(
                            label = stringResource(R.string.work_session_start),
                            minuteOfDay = startMinute,
                            onValue = { startMinute = it },
                            modifier = Modifier.weight(1f),
                        )
                        ClockTimePickerCard(
                            label = stringResource(R.string.work_session_end),
                            minuteOfDay = endMinute,
                            onValue = { endMinute = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                listOfNotNull(
                    stringResource(R.string.work_session_net, workDurationText(resources, (net / 60_000L).toInt())),
                    pluralStringResource(R.plurals.work_pauses, pauses, pauses).takeIf { pauses > 0 },
                ).joinToString(" · "),
                color = PwnMuted,
            )
            // Said before Save, not found out after it: the hours past the first day are not on
            // this page, and saving what the page shows would drop them without a word.
            if (workOutgrowsPage(segments)) {
                Text(
                    stringResource(R.string.work_save_long),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            problem?.let {
                Text(
                    stringResource(
                        when (it) {
                            WorkSaveProblem.FUTURE -> R.string.work_save_future
                            WorkSaveProblem.OVERLAP -> R.string.work_save_overlap
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                enabled = !busy && net >= WORK_MIN_ROW_MS && problem == null,
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(NonCancellable) {
                            // Setting the times by hand is setting the real end, so the cap mark
                            // goes; a typed session stays marked as typed however often it is moved.
                            val typed = original == null || workFlag(original, WORK_TYPED)
                            workEntry(kept, resources.getString(R.string.work_title), zone, typed = typed)
                                ?.let { entry -> original?.let { entry.copy(id = it.id, note = it.note) } ?: entry }
                                ?.let { db.saveJournalEvent(it) }
                        }
                        onDone(resources.getString(R.string.work_flash_saved))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

/** One week of the history: the day it starts on, its sessions newest first, and their net minutes. */
internal data class WorkHistoryWeek(val start: LocalDate, val sessions: List<JournalEntry>, val netMinutes: Int)

/**
 * The history's sessions by week, the newest week first and the newest session first inside each.
 * A week starts on [firstDayOfWeek], the locale's, as the Work page's week bars do.
 *
 * A session belongs to the week of the day its row carries — the day it started — so a night shift
 * across the end of a week is listed once, under the week it began in. The heading's total is the
 * rows under it added up as they print, whole net minutes each — a session the timer stopped at its
 * cap too, as the Work page's "this week" counts it: a heading that left out a row printed under it
 * would disagree with both, and the capped card on the Work page is where its real end is set.
 */
internal fun workHistoryWeeks(rows: List<JournalEntry>, firstDayOfWeek: DayOfWeek): List<WorkHistoryWeek> =
    rows.filter { it.kind == KIND_WORK }
        .mapNotNull { row -> row.localDay()?.let { it.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)) to row } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap(reverseOrder())
        .map { (start, sessions) ->
            WorkHistoryWeek(
                start = start,
                sessions = sessions.sortedByDescending { it.occurredAt },
                netMinutes = sessions.sumOf { (it.durationMinutes ?: 0.0).toInt() },
            )
        }

/**
 * The first day the history reads: the start of the week [weeks] weeks back counting this one, so
 * the oldest week listed is whole and its heading's total is the week's.
 */
internal fun workHistorySince(today: LocalDate, firstDayOfWeek: DayOfWeek, weeks: Int): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)).minusWeeks((weeks - 1).toLong())

/**
 * The day a session's page opens on and keeps on offer: the day its first stretch began, and for a
 * row whose stretches nobody could have worked — [workSegmentsOf] reads none of those — the day the
 * row itself carries. Such a row is listed under its own day to be opened and set right; a page that
 * opened on today would, saved as it stood, move a session of last March to this week. Null for a
 * new session, which has no day of its own yet.
 */
internal fun workOwnDay(original: JournalEntry?, segments: List<WorkSegment>, zone: ZoneId): LocalDate? =
    segments.firstOrNull()?.let { Instant.ofEpochMilli(it.from).atZone(zone).toLocalDate() } ?: original?.localDay()

/**
 * The days a session's page offers, newest first: the last [WORK_TYPED_DAYS], the day the session
 * started on ([original], none for a new one) and the one [chosen] now. The session's own day stays
 * on offer after another is tapped — the history opens sessions months old, and a slip onto today
 * must not leave leaving without saving the only way back.
 */
internal fun workDayChoices(today: LocalDate, original: LocalDate?, chosen: LocalDate): List<LocalDate> =
    ((0L until WORK_TYPED_DAYS).map { today.minusDays(it) } + listOfNotNull(original) + chosen)
        .distinct()
        .sortedDescending()

/**
 * The instants a day and two clock times name. The end is on the next day when it is earlier than
 * the start — a night shift — and each is built on the wall clock, so a day that gains or loses an
 * hour still lands on the times that were picked.
 */
internal fun workEpochRange(day: LocalDate, startMinute: Int, endMinute: Int, zone: ZoneId): Pair<Long, Long> {
    val start = startMinute.coerceIn(0, 1_439)
    val end = endMinute.coerceIn(0, 1_439)
    val endDay = if (end < start) day.plusDays(1) else day
    return day.atTime(start / 60, start % 60).atZone(zone).toInstant().toEpochMilli() to
        endDay.atTime(end / 60, end % 60).atZone(zone).toInstant().toEpochMilli()
}

/** "Stopped · 7 h 12 min", "Paused" — the word the flash says for a transition. */
internal fun workFlashWord(resources: Resources, from: WorkState, to: WorkState, step: WorkStep): String = when {
    to == WorkState.IDLE ->
        step.entry?.durationMinutes
            ?.let { resources.getString(R.string.work_flash_stopped, workDurationText(resources, it.toInt())) }
            ?: resources.getString(R.string.work_flash_too_short)

    to == WorkState.PAUSED -> resources.getString(R.string.work_flash_paused)

    // A session resumed has the stretch its pause closed. With none, the Resume came after a pause
    // too long to be one ([WORK_PAUSE_MAX_MS]) and began a new session: that is a start.
    from == WorkState.PAUSED && step.session.closed.isNotEmpty() -> resources.getString(R.string.work_flash_resumed)

    else -> resources.getString(R.string.work_flash_started)
}

internal fun workStateWord(state: WorkState): Int = when (state) {
    WorkState.IDLE -> R.string.work_state_idle
    WorkState.RUNNING -> R.string.work_state_running
    WorkState.PAUSED -> R.string.work_state_paused
}

/** "7 h 12 min", never "432 min". */
internal fun workDurationText(resources: Resources, minutes: Int): String =
    resources.getString(R.string.measurement_sleep_value, minutes / 60, minutes % 60)

/** "3 h 10", for the widget's one narrow line. */
internal fun workShortDuration(resources: Resources, minutes: Int): String =
    resources.getString(R.string.work_hours_short, minutes / 60, minutes % 60)

/** H:MM:SS, the way a stopwatch is read. */
internal fun stopwatchText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1_000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
}

/** The day list's line for a work row: "7 h 12 min · 2 pauses". */
@Composable
internal fun JournalEntry.workDetails(): String {
    val pauses = workPauseCount(this)
    return listOfNotNull(
        workDurationText(LocalResources.current, (durationMinutes ?: 0.0).toInt()),
        pluralStringResource(R.plurals.work_pauses, pauses, pauses).takeIf { pauses > 0 },
    ).joinToString(" · ")
}
