package com.pwnall.health

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlin.math.roundToInt

@Composable
internal fun CalendarHero(
    month: YearMonth,
    selected: LocalDate,
    viewMode: Int,
    expanded: Boolean,
    yearly: Boolean,
    onViewMode: (Int) -> Unit,
    onExpand: () -> Unit,
    onMove: (Int) -> Unit,
    onSelect: (LocalDate) -> Unit,
    today: LocalDate,
) {
    val locale = LocalLocale.current.platformLocale
    val ink = heroInk()
    val monthTitle = remember(locale) {
        localizedDatePattern(locale, "yMMMM", "LLLL yyyy")
    }
    // Flat rather than a gradient: a left-to-right run under a heading that is otherwise one colour
    // reads as a second, unrelated palette.
    Column(
        Modifier.fillMaxWidth().background(heroBand).padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            stringResource(R.string.calendar_title),
            Modifier.padding(horizontal = 24.dp).semantics { heading() },
            color = ink,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(PwnCorners.largeIncreased))
                    .clickable(enabled = viewMode == 0, role = Role.Button, onClick = onExpand).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (yearly) {
                        month.year.toString()
                    } else {
                        month.format(monthTitle).replaceFirstChar(Char::uppercase)
                    },
                    Modifier.weight(1f).semantics { heading() },
                    color = ink,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (viewMode == 0) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        stringResource(
                            if (expanded) R.string.calendar_collapse_month else R.string.calendar_expand_month,
                        ),
                        tint = ink,
                    )
                }
            }
            // Named for the step they take. "Back" is also the name of leaving the screen, and in
            // the by-year charts the step is a year, not a month.
            IconButton(onClick = { onMove(-1) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    stringResource(if (yearly) R.string.m3_cal_previous_year else R.string.m3_cal_previous_month),
                    tint = ink,
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = calendarCanMoveForward(month, today, yearly)) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    stringResource(if (yearly) R.string.m3_cal_next_year else R.string.m3_cal_next_month),
                    tint = ink.copy(alpha = if (calendarCanMoveForward(month, today, yearly)) 1f else 0.3f),
                )
            }
        }
        if (viewMode == 0) {
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (expanded) {
                    val firstDay = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
                    val blanks = firstDayOfWeekBlanks(month.atDay(1).dayOfWeek, firstDay)
                    val cells = List<LocalDate?>(blanks) { null } + (1..month.lengthOfMonth()).map(month::atDay)
                    // A narrow inset, because the seven columns share whatever width is left and a
                    // day is a finger's target: at the wider inset a phone's day would come out under the
                    // 48dp every target is owed. The weekday row stands inside the same inset, so the
                    // columns stay aligned under their names.
                    Column(Modifier.padding(horizontal = MONTH_GRID_INSET)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (offset in 0..6) {
                                Text(
                                    firstDay.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale),
                                    Modifier.weight(1f),
                                    color = ink,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                (week + List(7 - week.size) { null }).forEach { date ->
                                    if (date == null) {
                                        Spacer(Modifier.weight(1f).height(54.dp))
                                    } else {
                                        HeroDate(date, selected, today, true, Modifier.weight(1f), onSelect)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val state = rememberLazyListState()
                    LaunchedEffect(month, selected) { state.scrollToItem((selected.dayOfMonth - 3).coerceAtLeast(0)) }
                    LazyRow(
                        state = state,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items((1..month.lengthOfMonth()).map(month::atDay), key = { it.toString() }) { date ->
                            HeroDate(date, selected, today, false, Modifier.width(64.dp), onSelect)
                        }
                    }
                }
            }
        }
        val labels = listOf(R.string.calendar_schedule, R.string.calendar_charts, R.string.calendar_vitals)
            .map { stringResource(it) }
        val tabStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Three equal thirds while every name fits its third on one line; past that — a long
            // word at a large text size — the row scrolls sideways, each tab as wide as its name,
            // the way Material's scrollable tab row does, rather than breaking a word in two.
            val third = (maxWidth - VIEW_TAB_EDGE * 2 - VIEW_TAB_GAP * (labels.size - 1)) / labels.size
            val fits = remember(labels, tabStyle, third, density) {
                val room = with(density) { (third - VIEW_TAB_SIDE * 2).roundToPx() }
                labels.all { measurer.measure(it, tabStyle, softWrap = false, maxLines = 1).size.width <= room }
            }
            Row(
                Modifier.fillMaxWidth()
                    .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(horizontal = VIEW_TAB_EDGE)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(VIEW_TAB_GAP),
            ) {
                labels.forEachIndexed { index, label ->
                    val open = viewMode == index
                    val shape = RoundedCornerShape(PwnCorners.large)
                    Box(
                        Modifier.then(if (fits) Modifier.weight(1f) else Modifier)
                            .clip(shape)
                            .background(if (open) heroChosen() else Color.Transparent)
                            .border(1.dp, if (open) heroChosenEdge() else Color.Transparent, shape)
                            .selectable(selected = open, role = Role.Tab) { onViewMode(index) }
                            .padding(horizontal = VIEW_TAB_SIDE, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (open) heroChosenInk() else ink,
                            style = tabStyle,
                            fontWeight = if (open) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The band the calendar opens with.
 *
 * By day it is the theme's primary, and what is written on it is the primary's own ink — the pair
 * every filled button in the app already stands on, so a custom accent brings an ink worked out for
 * it rather than a white that was only ever checked against the stock blue. A pale gradient blue
 * would put that white at 2.17:1, under the 4.5:1 the header's title and weekdays owe; the stock
 * primary carries it at 6.44:1.
 *
 * By night it is not the primary: a light blue that works as a stripe on a near-white page becomes
 * the brightest thing on the screen when the page around it is near-black. The deep indigo it
 * turns to at night carries the night ink at 11.60:1.
 *
 * [CalendarScreen] paints this behind the agenda panel's rounded top corners — the two notches
 * right below the header show whatever is back there, and a different blue in them would read as a
 * seam across the join.
 */
internal val heroBand: Color
    @Composable get() = darkOr(HeroNightBand, MaterialTheme.colorScheme.primary)

/** The night band and the ink written on it: a fixed pair, since the band does not follow the theme. */
private val HeroNightBand = Color(0xFF1E3160)
private val HeroNightInk = Color(0xFFF4F5FA)

/** What is written on the calendar's header: the band's own ink, never a guess from the page. */
@Composable
private fun heroInk(): Color = darkOr(HeroNightInk, MaterialTheme.colorScheme.onPrimary)

/**
 * The chosen day and the chosen view, which stand on the band as cards.
 *
 * By day that card is the app's surface on the primary. By night the band is the dark one, so the
 * card cannot also be dark, and it takes the night primary instead with the primary's own ink on
 * it. Both are scheme pairs, so a custom theme's card keeps an ink that was worked out for it.
 */
@Composable
private fun heroChosen(): Color =
    darkOr(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface)

@Composable
private fun heroChosenInk(): Color =
    darkOr(MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.onSurface)

/**
 * An edge for the chosen card, drawn only where its fill does not stand clear of the band on its
 * own. A custom accent can bring the band within a shade of the card — a pale accent under a white
 * card — and then the chosen day would be marked by nothing a low-vision eye can find; the header's
 * ink draws its outline instead. On the stock palette the fill clears the 3:1 a marker owes and no
 * edge is drawn.
 */
@Composable
private fun heroChosenEdge(): Color =
    if (contrastRatio(heroChosen(), heroBand) >= CHOSEN_EDGE_CONTRAST) Color.Transparent else heroInk()

private const val CHOSEN_EDGE_CONTRAST = 3f

/** The expanded month's side inset: narrow, so seven days across a phone each keep a finger's width. */
private val MONTH_GRID_INSET = 8.dp

@Composable
private fun HeroDate(
    date: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    compact: Boolean,
    modifier: Modifier,
    onSelect: (LocalDate) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val chosen = date == selectedDate
    val enabled = !date.isAfter(today)
    val ink = heroInk()
    // The open day is a card standing on the header, which is what every other chosen thing in this
    // app is, rather than the header's own ink worn thin: thinned ink would be a smudge belonging to no
    // colour in the palette, and the only mark of which day was open. On the card the figure
    // turns over to the card's own ink, and the dot under it and the bolder weight say "chosen" in a
    // second way that does not depend on telling two colours apart.
    val face = if (chosen) heroChosenInk() else ink.copy(alpha = if (enabled) 1f else 0.3f)
    val fullDate = remember(locale) { localizedDatePattern(locale, "yMMMMEEEEd", "EEEE d MMMM yyyy") }
    val label = date.format(fullDate)
    val shape = RoundedCornerShape(PwnCorners.largeIncreased)
    Column(
        modifier.clip(shape)
            .background(if (chosen) heroChosen() else Color.Transparent)
            .border(1.dp, if (chosen) heroChosenEdge() else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button) { onSelect(date) }
            .semantics {
                selected = chosen
                contentDescription = label
            }
            .padding(vertical = if (compact) 8.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!compact) {
            Text(
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                color = face,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            date.dayOfMonth.toString(),
            color = face,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
        )
        Box(Modifier.size(5.dp).background(if (chosen) face else Color.Transparent, CircleShape))
    }
}

/** One line of a day: a row, a workout session's rows ([foldedSessions]), or a visit. */
private data class AgendaItem(
    val at: Long,
    val entries: List<JournalEntry> = emptyList(),
    val visit: Appointment? = null,
) {
    val entry: JournalEntry? get() = entries.firstOrNull()
}

/**
 * A day's rows as the calendar lists them: each on its own, except a workout session's ([session]),
 * which are one workout, in the order they were done.
 */
internal fun List<JournalEntry>.foldedSessions(): List<List<JournalEntry>> =
    filter { it.session == null }.map(::listOf) +
        filter { it.session != null }.groupBy { it.session }.values.map { rows -> rows.sortedBy { it.occurredAt } }

@Composable
internal fun CalendarAgenda(
    entries: List<JournalEntry>,
    visits: List<Appointment>,
    imperial: Boolean,
    onEdit: (Long) -> Unit,
    onOpenVisit: (Long) -> Unit,
    onDeleteEntries: (List<JournalEntry>) -> Unit,
    onDeleteVisit: (Appointment) -> Unit,
) {
    val events = (
        entries.foldedSessions().map { AgendaItem(it.first().occurredAt, entries = it) } +
            visits.map { AgendaItem(it.atMs, visit = it) }
        )
        .sortedBy { it.at }
    if (events.isEmpty()) {
        PwnCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.calendar_empty), Modifier.padding(24.dp), color = PwnMuted)
        }
    }
    val clockColumn = clockColumnWidth(events.map { eventClock(it.at) })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        events.forEach { item ->
            val entry = item.entry
            val visit = item.visit
            val tint = entry?.visual()?.tint ?: PwnIndigo
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.width(clockColumn).padding(top = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(eventClock(item.at), style = MaterialTheme.typography.labelMedium, color = PwnMuted)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.size(7.dp).background(tint, CircleShape))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(1.dp).height(32.dp).background(PwnBorder))
                }
                // One ground for every row, and the colour kept where it reads. The kind's own tint at a
                // tenth would resolve to the same blue-grey for every kind but one — and make the meal the
                // single pink card in a blue day, filed under a pink dot, as if a meal were a different sort
                // of record. The dot beside it carries the tint, and the drawn glyph carries it at full
                // strength; a tinted card would add only a stripe of difference with no meaning.
                Surface(
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(PwnCorners.extraLarge),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    val session = entry?.session
                    if (session != null) {
                        key(session) { SessionItem(session, item.entries, imperial, onEdit, onDeleteEntries) }
                    } else {
                        AgendaRow(entry, visit, imperial, onEdit, onOpenVisit, onDeleteEntries, onDeleteVisit)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(
    entry: JournalEntry?,
    visit: Appointment?,
    imperial: Boolean,
    onEdit: (Long) -> Unit,
    onOpenVisit: (Long) -> Unit,
    onDeleteEntries: (List<JournalEntry>) -> Unit,
    onDeleteVisit: (Appointment) -> Unit,
) {
    Row(
        Modifier.clickable(role = Role.Button) {
            if (entry != null) onEdit(entry.id) else visit?.let { onOpenVisit(it.id) }
        }
            .padding(start = 18.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry != null) {
            EntryGlyph(entry)
        } else if (visit != null) {
            Image(
                themePainterResource(specialtyArt(visit.specialty)),
                stringResource(R.string.visit_title),
                Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry?.shownTitle(LocalResources.current)
                    ?: visit?.let { it.doctor.ifBlank { specialtyLabel(it.specialty) } }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val details = if (entry?.kind == KIND_EAT) {
                stringResource(
                    R.string.entry_food_details,
                    entry.proteinG.shown(),
                    entry.fatG.shown(),
                    entry.carbsG.shown(),
                )
            } else {
                entry?.shortDetails(imperial) ?: visit?.place.orEmpty()
            }
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
            if (entry?.kind == KIND_EAT) {
                Text(
                    stringResource(R.string.burn_kcal, entry.calories.wholeKcal()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        DeleteButton {
            if (entry != null) onDeleteEntries(listOf(entry)) else visit?.let(onDeleteVisit)
        }
    }
}

/**
 * A workout session as one workout: "Freestyle #12", then how many exercises, the energy and the time
 * it took, rests between exercises included. A tap unfolds its exercises, each opening as its own row
 * does; the bin takes the whole session.
 */
@Composable
private fun SessionItem(
    session: String,
    rows: List<JournalEntry>,
    imperial: Boolean,
    onEdit: (Long) -> Unit,
    onDeleteEntries: (List<JournalEntry>) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val done = rows.filter { it.kind == KIND_ACTIVITY }
    val details = listOf(
        pluralStringResource(R.plurals.routines_exercises, done.size, done.size),
        stringResource(R.string.entry_energy_burned, done.sumOf { it.calories }),
        durationText(resources, (done.sumOf { it.durationMinutes ?: 0.0 } * 60).roundToInt()),
    ).joinToString(" · ")
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.clickable(role = Role.Button) { open = !open }
                .semantics {
                    if (open) {
                        collapse {
                            open = false
                            true
                        }
                    } else {
                        expand {
                            open = true
                            true
                        }
                    }
                }
                .padding(start = 18.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryGlyph(rows.first())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(details, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = PwnMuted,
            )
            DeleteButton { onDeleteEntries(rows) }
        }
        if (open) {
            rows.forEach { row ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onEdit(row.id) }
                        .padding(start = 66.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(row.shownTitle(resources), style = MaterialTheme.typography.bodyMedium)
                    row.shortDetails(imperial).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryGlyph(entry: JournalEntry) {
    val visual = entry.visual()
    PwnGlyph(
        visual.art,
        visual.icon,
        visual.background,
        stringResource(calendarEntryFilter(entry).title),
        Modifier.size(38.dp),
    )
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
        Icon(
            Icons.Outlined.DeleteOutline,
            stringResource(R.string.common_delete),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun CalendarEventFilter(filter: CalendarFilter, onSelect: (CalendarFilter) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        // The menu's state is said as the pair of actions a screen reader offers on it: expand
        // while it is shut, collapse while it is open.
        androidx.compose.material3.OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.semantics {
                if (open) {
                    collapse {
                        open = false
                        true
                    }
                } else {
                    expand {
                        open = true
                        true
                    }
                }
            },
        ) {
            Icon(
                Icons.Outlined.FilterList,
                stringResource(R.string.events_filter),
                Modifier.size(20.dp),
                tint = PwnMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(filter.title))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CalendarFilter.entries.forEach { choice ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(choice.title)) },
                    leadingIcon = { Image(themePainterResource(choice.art), null, Modifier.size(28.dp)) },
                    trailingIcon = { if (choice == filter) Icon(Icons.Outlined.Check, null) },
                    onClick = {
                        onSelect(choice)
                        open = false
                    },
                    // The check mark is drawn only; the choice in force is also said as selected.
                    modifier = Modifier.semantics { selected = choice == filter },
                )
            }
        }
    }
}

/**
 * How wide the agenda's clock column stands: the widest time on the day, and never narrower than
 * [CLOCK_COLUMN]. Fixed, a large text size would break "12:30" across two lines in the middle of
 * the number; measured, every row of the day shares one width, so the timeline still runs straight.
 */
@Composable
private fun clockColumnWidth(clocks: List<String>): androidx.compose.ui.unit.Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val widest = remember(clocks, style, density) {
        with(density) { (clocks.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toDp() }
    }
    return maxOf(widest, CLOCK_COLUMN)
}

private val CLOCK_COLUMN = 46.dp

/** The view tabs' inset from the band's edges, the gap between two tabs, and a tab's own side padding. */
private val VIEW_TAB_EDGE = 20.dp
private val VIEW_TAB_GAP = 6.dp
private val VIEW_TAB_SIDE = 12.dp
