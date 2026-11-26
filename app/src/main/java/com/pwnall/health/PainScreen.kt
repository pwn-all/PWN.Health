package com.pwnall.health

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** How many days the summary's body and the list of most-hurt areas look back. */
private const val PAIN_SUMMARY_DAYS = 30L

/** How long the saved or deleted flash stands before the page goes back to where it came from. */
private const val PAIN_SAVED_HOLD_MS = 700L

/**
 * How faint an area chosen from the list is lit at its fewest days. It rises with its days on the
 * scale of the most-hurt area of all, painted or chosen, so it is full colour only where it is that
 * area too.
 */
private const val PAIN_HEAT_FLOOR = 0.35f

/**
 * How strongly an area chosen from the list on [count] days is lit, when the most-hurt area of all hurt
 * on [most]: from [PAIN_HEAT_FLOOR] up to full colour and never past it, for the list's days are counted
 * through [litOn] — a first-map "Knee" lights the back of the knee too — and can come to more than the
 * named areas' most. Nothing at all with no day counted, rather than a strength divided by nought.
 */
internal fun painHeatStrength(count: Int, most: Int): Float =
    if (most <= 0) 0f else (PAIN_HEAT_FLOOR + (1f - PAIN_HEAT_FLOOR) * count / most).coerceAtMost(1f)

/** How many of the most-hurt areas are named under the body. */
private const val PAIN_TOP_AREAS = 5

/** "Knee · left" — a chip's name for an area on a side, the person's own side. */
internal fun Resources.painArea(area: RegionSide): String =
    sided(area, R.string.pain_region_side, R.string.pain_side_left, R.string.pain_side_right)

/** "Knee (L)" — the same name, short enough for a one-line row. */
internal fun Resources.painAreaShort(area: RegionSide): String =
    sided(area, R.string.pain_region_side_short, R.string.pain_side_left_short, R.string.pain_side_right_short)

/** "Knee (L): 3 days". */
private fun Resources.painAreaDays(area: RegionSide, days: Int): String =
    getQuantityString(R.plurals.pain_area_days, days, painAreaShort(area), days)

private fun Resources.sided(area: RegionSide, format: Int, left: Int, right: Int): String {
    val name = getString(area.region.labelRes)
    return when (area.side) {
        BodySide.CENTRE -> name
        BodySide.LEFT -> getString(format, name, getString(left))
        BodySide.RIGHT -> getString(format, name, getString(right))
    }
}

/** The day list's line for a pain row, in the language showing now. */
@Composable
internal fun JournalEntry.painDetails(): String {
    val resources = LocalResources.current
    val report = remember(measurementPayloadJson) { decodePainPayload(measurementPayloadJson) }
    return painSummaryLine(
        report,
        area = resources::painAreaShort,
        score = { resources.getString(R.string.pain_nrs_value, it) },
        word = { resources.getString(it.shortRes) },
        none = resources.getString(R.string.pain_none_entry),
        comma = resources.getString(R.string.list_comma),
    )
}

/** "Front of the body" or "Back of the body": the workout map's own words for its two figures. */
internal fun viewName(view: PainView): Int =
    if (view == PainView.FRONT) R.string.muscle_map_front else R.string.muscle_map_back

/**
 * What the summary page draws, worked out off the main thread and swapped in whole. [loaded] is false
 * only for the empty stand-in shown until the journal is first read: the analyses are not handed an
 * empty journal, which they would read as half a year with nothing answered, and today's "no pain" is
 * not offered on its word ([painNoneControl]).
 */
private data class PainOverview(
    val loaded: Boolean = false,
    val journal: List<JournalEntry> = emptyList(),
    val recent: List<JournalEntry> = emptyList(),
    val days: Map<RegionSide, Int> = emptyMap(),
    val heat: PainHeat = PainHeat(emptyList(), emptyMap()),
    val todayNone: Boolean = false,
    val todayPain: Boolean = false,
)

/**
 * The pain page: the last month's paintings on the body as they were painted, the areas chosen from
 * the list lit by their days and the most-hurt areas named by theirs, the entries themselves, and
 * the two answers a day can be given. No form — logging opens its own page, and an
 * entry opens the editor.
 *
 * [analysisCards] is where the analyses of the record go ([PainInsightsCards]). It is handed the last
 * half year of the journal: the patterns read ninety days, and the months before them show where an
 * episode or a start inside them began and how long an area has been recorded.
 *
 * Every entry there has ever been is behind the history icon in the top bar ([PainHistoryPage]). The
 * history takes this page's place in the same window, and an entry opened from it takes the
 * history's, each under a top bar whose arrow goes back one page, as the sex-life page's history does.
 */
@Composable
fun PainScreen(
    modifier: Modifier,
    profile: UserProfile,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    analysisCards: @Composable ColumnScope.(journal: List<JournalEntry>) -> Unit = {},
) {
    var page by rememberSaveable(stateSaver = PAIN_PAGE_SAVER) { mutableStateOf(PainPage.MAIN) }
    var openEntry by rememberSaveable { mutableStateOf<Long?>(null) }
    // Held above the pages, which leave composition while another is open: back from the history,
    // the page is where it was left.
    val mainScroll = rememberScrollState()
    val back: () -> Unit = { page = if (page == PainPage.ENTRY) PainPage.HISTORY else PainPage.MAIN }
    BackHandler(enabled = page != PainPage.MAIN, onBack = back)

    when (page) {
        PainPage.MAIN -> PainMainPage(
            modifier = modifier,
            profile = profile,
            scroll = mainScroll,
            onBack = onBack,
            onAdd = onAdd,
            onEdit = onEdit,
            onHistory = { page = PainPage.HISTORY },
            analysisCards = analysisCards,
        )

        PainPage.HISTORY -> PainHistoryPage(modifier, onBack = back) { id ->
            openEntry = id
            page = PainPage.ENTRY
        }

        PainPage.ENTRY -> {
            val id = openEntry
            if (id == null) {
                LaunchedEffect(Unit) { page = PainPage.HISTORY }
            } else {
                PainHistoryEntryPage(modifier, profile, id, onBack = back) {
                    // Only this entry's own page gives way to the history, never wherever the person
                    // has gone in the moment the saved flash stood.
                    if (page == PainPage.ENTRY && openEntry == id) page = PainPage.HISTORY
                }
            }
        }
    }
}

/** The pain screen's own pages: the summary, the history, and one entry opened from the history. */
private enum class PainPage { MAIN, HISTORY, ENTRY }

private val PAIN_PAGE_SAVER: Saver<PainPage, String> = Saver(
    save = { it.name },
    restore = { name -> PainPage.entries.firstOrNull { it.name == name } ?: PainPage.MAIN },
)

/** The summary itself: the body, the two answers a day can be given, the analyses and the month's entries. */
@Composable
private fun PainMainPage(
    modifier: Modifier,
    profile: UserProfile,
    scroll: ScrollState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onHistory: () -> Unit,
    analysisCards: @Composable ColumnScope.(journal: List<JournalEntry>) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember(context) { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val quickLog = rememberQuickLog()
    val overview by remember(today) {
        db.dao().journalBetween(today.minusDays(ANALYSIS_DAYS - 1).toString(), "9999-12-31")
            .map { journal ->
                val from = today.minusDays(PAIN_SUMMARY_DAYS - 1).toString()
                val recent = journal.filter { it.kind == KIND_PAIN && it.day >= from }
                val todays = recent.filter { it.day == today.toString() }
                PainOverview(
                    loaded = true,
                    journal = journal,
                    recent = recent,
                    days = painDaysByArea(recent),
                    heat = painHeat(recent),
                    // A pain logged after the morning's "no pain" is the answer that stands.
                    todayNone = today.toString() in painFreeDays(todays),
                    todayPain = todays.any { !decodePainPayload(it.measurementPayloadJson).none },
                )
            }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(PainOverview())
    val noneAdded = quickLogAdded(stringResource(R.string.pain_none_entry))

    val historyLabel = stringResource(R.string.pain_history_title)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PwnTopBar(stringResource(R.string.pain_title), onBack) {
                IconButton(onClick = onHistory, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(Icons.Outlined.History, historyLabel)
                }
            }
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .panelVerticalScroll(scroll)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                PainHeatCard(overview.days, overview.heat, profile.sex)
                PainPrimaryButton(stringResource(R.string.pain_log), onClick = onAdd)
                when (painNoneControl(overview.loaded, overview.todayNone, overview.todayPain)) {
                    PainNoneControl.HIDDEN -> Unit

                    PainNoneControl.ANSWERED -> Text(
                        stringResource(R.string.pain_none_answered),
                        Modifier.fillMaxWidth(),
                        color = PwnMuted,
                    )

                    PainNoneControl.OFFERED -> OutlinedButton(
                        onClick = {
                            val entry = painEntry(
                                title = resources.getString(R.string.pain_title),
                                report = PainReport(none = true),
                                day = today,
                                today = today,
                                now = System.currentTimeMillis(),
                            )
                            quickLog.log(scope, "none", noneAdded) { db.saveJournalEvent(entry) }
                        },
                        enabled = !quickLog.locked("none"),
                        modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                        border = painOutline(!quickLog.locked("none")),
                    ) { Text(stringResource(R.string.pain_none_today)) }
                }
                if (overview.loaded) analysisCards(overview.journal)
                if (overview.recent.isNotEmpty()) {
                    PwnSectionTitle(stringResource(R.string.pain_recent))
                    PwnCard(Modifier.fillMaxWidth()) {
                        Column {
                            overview.recent.forEach { entry -> PainRecentRow(entry) { onEdit(entry.id) } }
                        }
                    }
                }
                DataNoteLink(R.string.pain_local_only)
                Spacer(Modifier.height(12.dp))
            }
        }
        QuickLogFlash(quickLog)
    }
}

/**
 * What the analyses slot is handed: half a year. The cards read ninety days, and the months before
 * them say whether a pain in them had been recorded for more than three months, and where an episode
 * running into them began.
 */
private const val ANALYSIS_DAYS = 180L

/**
 * The two views side by side and the most-hurt areas named under them, by how many days they hurt.
 *
 * The body shows the month's paintings as they were painted ([PainHeat.marks]), stronger where more
 * entries painted the same place, and never the whole of an area a stroke fell on: a dab on the right
 * of the lower back is not shown as the whole lower back. An area chosen from the list, with nothing
 * painted, is lit whole by the days it was chosen on, on the scale the named areas are counted by.
 */
@Composable
private fun PainHeatCard(days: Map<RegionSide, Int>, heat: PainHeat, sex: String) {
    val resources = LocalResources.current
    val most = days.values.maxOrNull() ?: 0
    val lit = heat.listedDays.mapValues { (_, count) -> painHeatStrength(count, most) }
    val ranked = days.entries.sortedWith(
        compareByDescending<Map.Entry<RegionSide, Int>> { it.value }.thenBy(REGION_SIDE_ORDER) { it.key },
    )
    // Cut like the cards under it on the same page, not a size smaller.
    Surface(shape = RoundedCornerShape(PwnCorners.extraLarge), color = musclePanel()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PainView.entries.forEach { view ->
                    val side = stringResource(viewName(view))
                    val shown = ranked.filter { entry -> painSpokenOn(view, entry.key) }
                        .map { (area, count) -> resources.painAreaDays(area, count) }
                    val description = listOf(side).plus(shown).joinToString(". ")
                    PainFigure(
                        view,
                        sex,
                        lit,
                        Modifier.weight(1f).height(280.dp).semantics { contentDescription = description },
                        marks = heat.marks,
                    )
                }
            }
            Text(
                stringResource(if (days.isEmpty()) R.string.pain_map_empty else R.string.pain_summary_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ranked.take(PAIN_TOP_AREAS).forEach { (area, count) ->
                Text(
                    resources.painAreaDays(area, count),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/**
 * Which of the two figures says [area] to a screen reader: the one it is drawn on, on either map —
 * through its own patch, or the patches of the areas carved out of it where a map has none of its own
 * ([litOn]) — so a knee counted from the first map's entries is said with both figures, where it had
 * a patch on each; and the front for an area drawn on neither — the head's, which the plates stop
 * short of, the fingers', the sole's — so no area is left unspoken.
 */
internal fun painSpokenOn(view: PainView, area: RegionSide): Boolean {
    val on = PainView.entries.filter { other ->
        PainMapVersion.entries.any { map ->
            val shown = area.litOn(map)
            sidePatches(other, map).any { it.area in shown }
        }
    }
    return if (on.isEmpty()) view == PainView.FRONT else view in on
}

/**
 * One entry in the list: its glyph, what it says, and the day it was told on. Not named after the
 * decoded row it shows, [PainRow], which shares the package.
 */
@Composable
private fun PainRecentRow(entry: JournalEntry, onClick: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val visual = entry.visual()
    val date = remember(entry.day, locale) {
        runCatching { painDayText("dMMM", locale, LocalDate.parse(entry.day)) }.getOrDefault(entry.day)
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PwnGlyph(visual.art, visual.icon, visual.background, null, Modifier.size(40.dp))
        Text(
            entry.painDetails(),
            Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(date, color = PwnMuted)
    }
}

/** The door on the profile's health section, cut like the blood tests' beside it. */
@Composable
internal fun PainDoorCard(onClick: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PwnGlyph(R.drawable.ic_art_pain, PainIcon, PwnBlueSoft, null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pain_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.pain_door_body),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Drawn rather than typed: a "›" character would join the card's spoken name.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
        }
    }
}

/**
 * A date as [locale] orders and spells it — the day before the month in Britain, after it in the
 * US — from a [skeleton] that only names the fields: "dMMM", or "EEEdMMM" with the weekday. In the
 * digits the language writes, which java.time's formatter does not use; a skeleton with a time in
 * it names the hour as "H", the 24-hour clock every time in the app is on ([clockText]).
 */
internal fun skeletonDateText(skeleton: String, locale: Locale, epochMillis: Long, zone: ZoneId): String {
    val format = android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale)
    format.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
    return format.format(Date(epochMillis))
}

/** [skeletonDateText] for a day of the record, taken at its start in the phone's own time zone. */
internal fun painDayText(skeleton: String, locale: Locale, day: LocalDate): String {
    val zone = ZoneId.systemDefault()
    return skeletonDateText(skeleton, locale, day.atStartOfDay(zone).toInstant().toEpochMilli(), zone)
}

/** The day, the month and the time, on the 24-hour clock the whole app keeps: see [clockText]. */
internal const val PAIN_MOMENT_SKELETON = "dMMMHm"

/**
 * How long a pain that has gone lasted: five minutes to three days, in steps that get coarser as
 * they get longer — nobody knows a two-day pain to the five minutes.
 */
internal val PAIN_DURATIONS: List<Int> =
    (5..55 step 5).toList() + (60..720 step 30).toList() + (780..4_320 step 60).toList()

private const val DEFAULT_LASTED_MINUTES = 60

/** Where the 1–10 ruler's needle rests while nothing is set: the middle of the scale. */
internal const val DEFAULT_NRS = 5

internal fun durationLabel(resources: Resources, minutes: Int): String = when {
    minutes < 60 -> resources.getString(R.string.pain_minutes, minutes)
    minutes % 60 == 0 -> resources.getString(R.string.pain_hours, minutes / 60)
    else -> resources.getString(R.string.pain_hours_minutes, minutes / 60, minutes % 60)
}

/**
 * The page a pain is painted on — a new one, or [entry] opened again to correct it.
 *
 * The canvas takes about half the height and consumes its own touches; everything else is in the
 * page's scroll beneath it, so the page scrolls only where it is dragged outside the body. Saving
 * flashes and goes back. Correcting an entry keeps its id and the moment it was told; its day list
 * row reads the new answers.
 *
 * A new entry asks nothing until an area is marked: the questions are about a place, and a page of
 * them over an empty body is a form to wade through before the one answer that matters. Once one is
 * marked, an area logged in the month before asks first whether this is that pain ([PainSameCard]).
 * A new one gets every question, empty. The same one does not ask when it started — it started with
 * the earlier one — and asks whether it feels different: if not, its words and when it was first
 * noticed are carried over rather than chosen again; if so, the words come back prefilled to change.
 * Either way the rating starts at the earlier one's.
 *
 * The questions come one at a time, as the period log asks a day's ([painProgress]): the one being
 * put under the answers already given, each a row a tap reopens in its place. There is no "Next" and
 * no "Skip": a choice is taken on the tap, and the words and the two rulers three seconds after the
 * last change to them ([AutoTakeClock]); nothing is passed over by being left alone — first-noticed
 * waits for its tap, "No clear moment" among its answers. Save waits for every answer, and while it
 * does, the line under it says what is still to answer. Correcting an entry opens with every answer
 * a row and Save open — a question an entry left blank shows a dash rather than being forced —
 * and a "no pain today" opened again is asked as a new pain once an area is marked.
 *
 * [day] is the day a new entry is filed under when it is being logged for a day gone by; a report
 * for today carries the moment it was made.
 */
@Composable
internal fun PainEntryPage(
    modifier: Modifier,
    profile: UserProfile,
    entry: JournalEntry?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    day: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember(context) { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val initial = remember(entry) { decodePainPayload(entry?.measurementPayloadJson) }
    // The map the entry is painted on, for good ([painEntryMap]): an entry opened again its own, so a
    // painting of the first map is hit-tested, listed and saved on the first map; a new entry, and a
    // "no pain" answer opened again, the current one.
    val map = painEntryMap(initial)
    val reportDay = remember(entry, day, today) {
        (entry?.day ?: day)?.let { it.toLocalDateOrNull() } ?: today
    }
    val drawing = rememberPainDrawing(initial.strokes)
    val key = entry?.id
    val lists = remember(initial) { painPageLists(initial) }
    var added by rememberSaveable(key) { mutableStateOf(lists.first.map { it.code }) }
    var removed by rememberSaveable(key) { mutableStateOf(lists.second.map { it.code }) }
    var nrs by rememberSaveable(key) { mutableStateOf(initial.nrs) }
    var types by rememberSaveable(key) { mutableStateOf(initial.types.map { it.name }) }
    var onset by rememberSaveable(key) { mutableStateOf(initial.onset?.name) }
    var gone by rememberSaveable(key) { mutableStateOf(entry?.durationMinutes != null) }
    var lasted by rememberSaveable(key) {
        mutableIntStateOf(entry?.durationMinutes?.toInt()?.let(::nearestDuration) ?: DEFAULT_LASTED_MINUTES)
    }
    var noticed by rememberSaveable(key) { mutableStateOf(initial.noticed?.name) }
    var painkiller by rememberSaveable(key) { mutableStateOf(initial.painkiller) }
    var note by rememberSaveable(key) { mutableStateOf(entry?.note.orEmpty()) }
    // Which questions are answered, and the one held open: an entry opened again has answered them.
    var flow by rememberSaveable(key, stateSaver = PAIN_FLOW_SAVER) {
        mutableStateOf(painFlowFor(initial.takeIf { entry != null }, gone = entry?.durationMinutes != null))
    }
    var areaPicker by rememberSaveable { mutableStateOf(false) }
    // The questions changed and not yet taken, and how many changes there have been: each one starts
    // the three seconds before the answer is taken again ([AutoTakeClock]). A set, not the last one:
    // reopening another answer while one is pending leaves the pending one's change standing, and the
    // page coming back to it takes it as given rather than waiting for a touch that already came.
    var touchedSteps by rememberSaveable(key) { mutableStateOf(emptySet<String>()) }
    var touches by remember { mutableIntStateOf(0) }
    // A finger resting on a ruler mid-drag changes nothing, and is not three seconds without action.
    var rulerHeld by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by rememberStatusLine()
    var failure by remember { mutableStateOf<String?>(null) }

    val painted by remember { derivedStateOf { paintedRegions(drawing.strokes.toList(), map) } }
    val tooMuch by remember { derivedStateOf { painPaintingTooMuch(drawing.strokes.toList()) } }
    val marked = remember(painted, added, removed) {
        painMarkedAreas(painted, added.mapNotNull(RegionSide::parse), removed.mapNotNull(RegionSide::parse))
    }
    val chosenOnset = PainOnset.entries.firstOrNull { it.name == onset }

    // The month of pain entries a new one is looked up against, read once and decoded once off the
    // main thread: a live query would find the entry being saved and ask about it again during the
    // saved flash, and the lookup runs again on every stroke that reaches a new area.
    val painRows by produceState<List<PainRow>?>(null, entry, reportDay) {
        if (entry == null) {
            val from = reportDay.minusDays(PAIN_EARLIER_DAYS - 1).toString()
            val rows = db.dao().journalBetween(from, reportDay.toString()).first()
            value = withContext(Dispatchers.Default) { rows.filter { it.kind == KIND_PAIN }.map { PainRow(it) } }
        }
    }
    val earlier = remember(marked, painRows) { painRows?.let { earlierPainAmong(marked, it, reportDay, entry?.id) } }
    // "The same pain?" and "does it feel different?", and the earlier entry they were answered about:
    // an answer stands only for that entry.
    var answeredFor by rememberSaveable(key) { mutableStateOf<Long?>(null) }
    var samePain by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    var feelsChanged by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    val same = samePain.takeIf { earlier != null && earlier.entry.id == answeredFor }
    val changed = feelsChanged.takeIf { same == true }
    val questions = painQuestions(
        editing = initial.takeIf { entry != null },
        marked = marked.isNotEmpty(),
        rowsLoaded = painRows != null,
        earlier = earlier?.report,
        same = same,
        changed = changed,
    )
    val progress = painProgress(questions, flow, gone)
    val wait = painSaveWait(
        marked = marked.isNotEmpty(),
        card = painCardWait(found = entry == null && earlier != null, same = same, changed = changed),
        questions = questions,
        flow = flow,
        gone = gone,
    )

    fun touch(step: PainStep) {
        touchedSteps = touchedSteps + step.name
        touches++
    }

    fun take(step: PainStep) {
        touchedSteps = touchedSteps - step.name
        flow = flow.answered(step)
    }

    // The thin line under a question that settles rather than ends with a tap, filling over the three
    // seconds before its answer is taken. It stands still by itself under the area picker or a "?",
    // which take the window's focus, and under a finger on a ruler by [rulerHeld].
    val clock: @Composable (PainStep, Boolean) -> Unit = { step, holds ->
        val touched = step.name in touchedSteps
        AutoTakeClock(
            armed = autoTakeArmed(step.answerKind, holds, touched, answered = step in flow.done),
            restart = touches,
            onTaken = { take(step) },
            held = rulerHeld,
        )
    }

    fun forgetAnswers() {
        // What "the same pain" filled in belonged to that pain.
        if (samePain == true) {
            nrs = null
            types = emptyList()
            flow = flow.forget(PainStep.TYPES, PainStep.INTENSITY)
        }
        answeredFor = null
        samePain = null
        feelsChanged = null
        touchedSteps = emptySet()
    }

    fun answerSame(match: PainRow, isSame: Boolean) {
        if (same == isSame) return
        forgetAnswers()
        answeredFor = match.entry.id
        samePain = isSame
        if (isSame) {
            // The earlier rating and words are where the ruler and the chips start, not answers
            // given today: the two questions are put again, already filled in.
            nrs = match.report.nrs
            types = match.report.types.map { it.name }
            flow = flow.forget(PainStep.TYPES, PainStep.INTENSITY)
        }
    }

    // Other areas can find another earlier entry, or none: an answer about the last one no longer
    // stands. Not before the month has been read, or a page brought back would lose its answers.
    LaunchedEffect(earlier?.entry?.id, painRows == null) {
        if (painRows != null && earlier?.entry?.id != answeredFor) forgetAnswers()
    }

    fun remove(area: RegionSide) {
        added = added - area.code
        if (area in painted) removed = (removed + area.code).distinct()
    }

    fun toggle(area: RegionSide) {
        if (area in marked) {
            remove(area)
        } else {
            removed = removed - area.code
            if (area !in painted) added = (added + area.code).distinct()
        }
    }

    /**
     * What an answer row says was answered, in the language showing now; a dash for a question the
     * entry left blank.
     */
    fun answerOf(step: PainStep): String = when (step) {
        PainStep.TYPES -> types.mapNotNull { name -> PainType.entries.firstOrNull { it.name == name } }
            .joinToString(resources.getString(R.string.list_comma)) { resources.getString(it.shortRes) }

        PainStep.ONSET -> chosenOnset?.let { resources.getString(it.labelRes) }.orEmpty()

        PainStep.INTENSITY -> nrs?.let { resources.getString(R.string.pain_nrs_value, it) }.orEmpty()

        PainStep.NOTICED -> resources.getString(
            PainNoticed.entries.firstOrNull { it.name == noticed }?.labelRes ?: R.string.pain_noticed_skipped,
        )

        PainStep.STATUS -> resources.getString(if (gone) R.string.pain_gone else R.string.pain_still_hurts)

        PainStep.LASTED -> durationLabel(resources, lasted)

        PainStep.PAINKILLER -> resources.getString(if (painkiller) R.string.common_yes else R.string.common_no)
    }.ifEmpty { resources.getString(R.string.editor_extra_blank) }

    val clearedText = stringResource(R.string.pain_cleared)
    val deletedText = stringResource(R.string.work_flash_deleted)
    val notSavedText = stringResource(R.string.quick_log_not_saved)
    val notDeletedText = stringResource(R.string.pain_not_deleted)
    val savedText = if (entry == null) {
        quickLogAdded(stringResource(R.string.pain_title))
    } else {
        stringResource(R.string.pain_saved)
    }
    val side = stringResource(viewName(drawing.view))
    val description = if (marked.isEmpty()) {
        stringResource(R.string.pain_canvas_nothing, side)
    } else {
        val areas = marked.joinToString(resources.getString(R.string.list_comma)) { resources.painArea(it) }
        stringResource(R.string.pain_canvas_marked, side, areas)
    }

    fun save() {
        // "Just now" is the moment of the report, and a report for a day gone by has only that
        // day's midday to give. Every other answer is a stretch of days rather than a moment — no
        // clock time is asked for — and the soreness rule reads it as the stretch it is.
        val now = System.currentTimeMillis()
        val reportedAt = entry?.occurredAt ?: if (reportDay == today) now else dayInstant(reportDay.toString())
        val answers = PainReport(
            regions = marked.toList(),
            types = types.mapNotNull { name -> PainType.entries.firstOrNull { it.name == name } },
            nrs = nrs,
            onset = chosenOnset,
            lastedMinutes = lasted.takeIf { gone },
            noticed = PainNoticed.entries.firstOrNull { it.name == noticed },
            painkiller = painkiller,
            strokes = drawing.strokes.toList(),
            map = map,
        )
        val report = painReportToSave(answers, reportedAt, earlier, same, changed, initial.takeIf { entry != null })
        val row = painEntry(
            title = resources.getString(R.string.pain_title),
            report = report,
            day = reportDay,
            today = today,
            now = now,
            note = note,
            previous = entry,
        )
        saving = true
        failure = null
        scope.launch {
            // Not cut off by a back press mid-transaction: the page's scope goes with the page. A write
            // that throws — a full disk, a key the Keystore will not give — is said on the failure card,
            // as the quick-log tiles say it, and Save comes back to try again; uncaught, it would end the app.
            val written = withContext(NonCancellable) {
                savePainRow(
                    row,
                    report,
                    write = { db.saveJournalEvent(it) },
                    sameDay = { db.dao().journalOnNow(it) },
                    delete = { db.deleteJournalEvent(it) },
                )
            }
            if (written.isSuccess) {
                status = savedText
                delay(PAIN_SAVED_HOLD_MS)
                onDone()
            } else {
                failure = notSavedText
                saving = false
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val canvasHeight = maxHeight * 0.55f
        Column(Modifier.fillMaxSize()) {
            PwnTopBar(stringResource(R.string.pain_title), onBack) {
                entry?.let { row ->
                    IconButton(
                        onClick = {
                            saving = true
                            failure = null
                            scope.launch {
                                val deleted = withContext(NonCancellable) {
                                    runCatching { db.deleteJournalEvent(row.id) }
                                }
                                if (deleted.isFailure) {
                                    // Said as a failed save is, with the entry still open to try again.
                                    failure = notDeletedText
                                    saving = false
                                } else {
                                    // Said in the middle of the page before it closes, as a save is and
                                    // as the work page says its own: one tap on this erases the entry,
                                    // and it should not go without a word.
                                    status = deletedText
                                    delay(PAIN_SAVED_HOLD_MS)
                                    onDone()
                                }
                            }
                        },
                        enabled = !saving,
                    ) {
                        // A drawing ignores the colour the button dims its content to when it is off,
                        // so it fades itself by Material's disabled opacity.
                        Image(
                            themePainterResource(R.drawable.ic_art_delete),
                            stringResource(R.string.common_delete),
                            Modifier.size(26.dp).alpha(if (saving) DISABLED_ALPHA else 1f),
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .panelVerticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PwnSegmentedControl(
                    options = listOf(
                        stringResource(R.string.pain_view_front) to null,
                        stringResource(R.string.pain_view_back) to null,
                    ),
                    selected = drawing.view.ordinal,
                    onSelected = { drawing.show(PainView.entries[it]) },
                )
                PainCanvas(
                    drawing = drawing,
                    sex = profile.sex,
                    lit = painLitAreas(marked, painted),
                    description = description,
                    // A stroke over an area struck off the list marks it again: painting over it is
                    // as plain an answer as a tap would be. Its Undo strikes the area off again.
                    onStroke = { stroke ->
                        val back = removed.intersect(paintedRegions(listOf(stroke), map).map { it.code }.toSet())
                        removed = removed - back
                        drawing.alsoUndo { removed = (removed + back).distinct() }
                    },
                    onCleared = { status = clearedText },
                    onListAreas = { areaPicker = true },
                    // Never shorter than the column of buttons down its edge: in a short window —
                    // a phone held sideways — the last of them, the one-finger move, would be squeezed
                    // under its target size. The canvas is in the scroll, so a floor costs nothing.
                    modifier = Modifier.fillMaxWidth().height(maxOf(canvasHeight, PAIN_CANVAS_MIN_HEIGHT)),
                    map = map,
                )
                Text(
                    stringResource(R.string.pain_paint_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
                // Said as soon as it is true, while an Undo still mends it, and in the words a chat
                // says it in under its own map: past the caps the row keeps the areas, not the drawing.
                if (tooMuch) {
                    Text(
                        stringResource(R.string.pain_chat_map_too_much),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
                PainAreas(marked, onOpenList = { areaPicker = true }, onRemove = ::remove)
                if (entry == null && earlier != null) {
                    PainSameCard(
                        earlier = earlier,
                        marked = marked,
                        same = same,
                        changed = changed,
                        onSame = { answerSame(earlier, it) },
                        onChanged = { feelsChanged = it },
                    )
                }
                entry?.let { row -> initial.same?.let { PainSameAsCard(it, row.day) } }
                if (painNeedsSafetyLine(marked, nrs)) PainSafetyLine()
                // One question at a time, as the cycle page asks a day's: the answers given stand
                // as rows in the order they were asked, and a tap on one puts it again in its place.
                progress.shown.forEach { step ->
                    val carried = earlier?.report?.takeIf { step in questions.carried }
                    when {
                        step == progress.asking -> PainAskedInView(step, follow = flow.done.isNotEmpty()) {
                            when (step) {
                                PainStep.TYPES -> PainAsked {
                                    // Words carried from the same pain are where the question starts,
                                    // not an answer; the list opened and closed again keeps them as
                                    // they are, as a tap on the ruler keeps its number.
                                    val keep = { touch(PainStep.TYPES) }.takeIf {
                                        painTypesKeepOffered(types, PainStep.TYPES.name in touchedSteps, flow.done)
                                    }
                                    PainTypes(types, onKeep = keep) { chosen ->
                                        types = chosen
                                        touch(PainStep.TYPES)
                                        flow = flow.typesChosen(chosen.size)
                                    }
                                    // Stands still while the list is open, which takes the window's
                                    // focus, and runs from the moment it closes.
                                    clock(PainStep.TYPES, types.isNotEmpty())
                                }

                                // The app's own selector, the list behind a tap: choosing from it is
                                // the answer. An entry's answer that is not offered ([PainOnset.offered]) stays
                                // in the list it is shown from, so the entry opened again reads as saved.
                                PainStep.ONSET -> PainAsked {
                                    val choices = painOnsetChoices(chosenOnset)
                                    val question = stringResource(R.string.pain_onset)
                                    PwnChoiceField(
                                        label = question,
                                        options = choices.map { stringResource(it.labelRes) },
                                        selected = choices.indexOf(chosenOnset),
                                        onSelected = {
                                            onset = choices[it].name
                                            take(PainStep.ONSET)
                                        },
                                        // A heading, as every other question here is, so the one being
                                        // put can be found among the answers above it; the field's own
                                        // label is silent, so the heading says the question itself.
                                        modifier = Modifier.semantics(mergeDescendants = true) {
                                            heading()
                                            contentDescription = question
                                        },
                                    )
                                }

                                PainStep.INTENSITY -> PainIntensity(
                                    nrs,
                                    onNrs = {
                                        nrs = it
                                        touch(PainStep.INTENSITY)
                                        flow = flow.stay(PainStep.INTENSITY)
                                    },
                                    onHeld = { rulerHeld = it },
                                ) { clock(PainStep.INTENSITY, nrs != null) }

                                // A tap is the answer, and nothing passes the question over: "No
                                // clear moment" is there for someone who cannot say.
                                PainStep.NOTICED -> PainAsked {
                                    PainChoice(stringResource(R.string.pain_noticed_title)) {
                                        PainNoticed.entries.forEach { choice ->
                                            PainAnswerChip(
                                                selected = noticed == choice.name,
                                                onClick = {
                                                    if (noticed == choice.name) {
                                                        // The chosen answer tapped again takes it
                                                        // back, and the question waits for another.
                                                        noticed = null
                                                        flow = flow.takenBack(PainStep.NOTICED)
                                                    } else {
                                                        noticed = choice.name
                                                        take(PainStep.NOTICED)
                                                    }
                                                },
                                                label = stringResource(choice.labelRes),
                                            )
                                        }
                                    }
                                }

                                PainStep.STATUS -> PainAsked {
                                    // Neither chip is lit until one is chosen: an untouched "still hurts"
                                    // would be an answer nobody gave.
                                    val answered = PainStep.STATUS in flow.done
                                    PainChoice(stringResource(R.string.pain_status)) {
                                        PainAnswerChip(
                                            selected = answered && !gone,
                                            onClick = {
                                                gone = false
                                                flow = flow.status(gone = false)
                                                // Still there takes back how long it lasted, and with
                                                // it the change to the ruler: asked again, the length
                                                // waits for a touch of its own.
                                                touchedSteps = touchedSteps - PainStep.LASTED.name
                                            },
                                            label = stringResource(R.string.pain_still_hurts),
                                        )
                                        PainAnswerChip(
                                            selected = answered && gone,
                                            onClick = {
                                                gone = true
                                                flow = flow.status(gone = true)
                                            },
                                            label = stringResource(R.string.pain_gone),
                                        )
                                    }
                                }

                                PainStep.LASTED -> PainAsked {
                                    PainLastedRuler(
                                        lasted = lasted,
                                        onLasted = {
                                            lasted = it
                                            touch(PainStep.LASTED)
                                            flow = flow.stay(PainStep.LASTED)
                                        },
                                        onHeld = { rulerHeld = it },
                                        label = stringResource(R.string.pain_lasted),
                                    ) { clock(PainStep.LASTED, true) }
                                }

                                PainStep.PAINKILLER -> PainAsked {
                                    val answered = PainStep.PAINKILLER in flow.done
                                    PainChoice(stringResource(R.string.pain_painkiller_question)) {
                                        listOf(true to R.string.common_yes, false to R.string.common_no)
                                            .forEach { (took, label) ->
                                                PainAnswerChip(
                                                    selected = answered && painkiller == took,
                                                    onClick = {
                                                        painkiller = took
                                                        flow = flow.answered(PainStep.PAINKILLER)
                                                    },
                                                    label = stringResource(label),
                                                )
                                            }
                                    }
                                }
                            }
                        }

                        carried != null -> PwnAnswerRow(
                            question = null,
                            answer = stringResource(R.string.pain_same_carried, resources.carriedAnswer(step, carried)),
                            container = PwnSurfaceElevated,
                        )

                        else -> PwnAnswerRow(
                            question = stringResource(step.rowQuestion()),
                            answer = answerOf(step),
                            container = PwnSurfaceElevated,
                        ) {
                            flow = flow.stay(step)
                        }
                    }
                }
                // The note is the last thing, and optional: it comes once the answers are in, and
                // stays for anyone who has started one and on an entry being corrected, whose
                // answers are in even while one of them is reopened. A "no pain today" opened again
                // has none: it waits for them, as a new entry does.
                val correcting = entry != null && !initial.none
                if (progress.canSave || note.isNotEmpty() || correcting) NoteField(note, { note = it })
                entry?.let { PainSorenessCard(it) }
                PainPrimaryButton(stringResource(R.string.common_save), ::save, enabled = progress.canSave && !saving)
                // Save off says why, in one line: six questions in, it would read as a button that did nothing.
                wait?.let { PainSaveWaitLine(it) }
                DataNoteLink(R.string.pain_local_only)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (areaPicker) PainAreaPicker(marked, map, onToggle = ::toggle, onDismiss = { areaPicker = false })
        PwnActionFlash(status)
        PwnFailureFlash(failure) { failure = null }
    }
}

/**
 * [onDown] true from a finger's press on the control to its lift, whether the control consumes the
 * drag or not: the three-second clock stands still while a finger rests on a ruler ([AutoTakeClock]).
 */
internal fun Modifier.whileFingerDown(onDown: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onDown(true)
        try {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
        } finally {
            onDown(false)
        }
    }
}

/** A stored duration put back on the ruler at the nearest step it offers. */
private fun nearestDuration(minutes: Int): Int = PAIN_DURATIONS.minBy { kotlin.math.abs(it - minutes) }

/**
 * How long a pain that has gone lasted, on the app's ruler, under [label] (none where the question
 * is asked above it) and its length in large type. [onLasted] is every change, and a tap that stays a
 * tap — or a screen reader's double tap — gives the length the needle stands on: without one, nothing
 * the ruler shows untouched is taken for the answer. The ruler's own actions stay, so it is still set
 * by steps too. [onHeld] says while a finger rests on it, which stops the clock under it ([content]).
 */
@Composable
internal fun PainLastedRuler(
    lasted: Int,
    onLasted: (Int) -> Unit,
    onHeld: (Boolean) -> Unit,
    label: String?,
    content: @Composable () -> Unit,
) {
    val resources = LocalResources.current
    val recordLasted = stringResource(R.string.pain_nrs_record, durationLabel(resources, lasted))
    val record by rememberUpdatedState(onLasted)
    val shown by rememberUpdatedState(lasted)
    label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = PwnMuted) }
    Text(
        durationLabel(resources, lasted),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    RulerPicker(
        values = PAIN_DURATIONS,
        value = lasted,
        onValue = onLasted,
        label = { durationLabel(resources, it) },
        modifier = Modifier.whileFingerDown(onHeld)
            .pointerInput(Unit) { detectTapGestures { record(shown) } }
            .semantics {
                onClick(label = recordLasted) {
                    record(shown)
                    true
                }
            },
    )
    content()
}

/**
 * A question over a row of chips that wraps. The question is a heading to a screen reader, so the one
 * being put can be found among the answers above it. [single] when it takes one answer of several:
 * the chips are then one group of [PainAnswerChip]s, where a set of chips takes any of them.
 */
@Composable
internal fun PainChoice(
    label: String,
    modifier: Modifier = Modifier,
    single: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = PwnMuted,
        )
        FlowRow(
            if (single) Modifier.selectableGroup() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

/** One answer to a question that takes one: the shared choice chip, spoken as a radio button. */
@Composable
internal fun PainAnswerChip(selected: Boolean, onClick: () -> Unit, label: String) {
    PwnChoiceChip(selected, onClick, label)
}

/**
 * The six words, up to [PAIN_TYPES_MAX] of them, as enum names, in the app's list of ticks: once two
 * are ticked the other four are off until one is taken away. [onKeep], while the words are carried
 * from the same pain, is called when the list is opened and closed again — the person has looked at
 * them and kept them — and a screen reader finds it on the question's heading as well.
 */
@Composable
private fun PainTypes(types: List<String>, onKeep: (() -> Unit)?, onTypes: (List<String>) -> Unit) {
    val question = stringResource(R.string.pain_types)
    val words = PainType.entries.filter { it.name in types }.map { stringResource(it.labelRes) }
    val keepLabel = stringResource(R.string.pain_nrs_record, words.joinToString(stringResource(R.string.list_comma)))
    val keep by rememberUpdatedState(onKeep)
    PwnTickField(
        label = question,
        options = PainType.entries.map { stringResource(it.labelRes) },
        selected = PainType.entries.indices.filter { PainType.entries[it].name in types }.toSet(),
        onToggle = { index ->
            val name = PainType.entries[index].name
            onTypes(if (name in types) types - name else types + name)
        },
        optionEnabled = { index -> painTypeOffered(types, PainType.entries[index].name) },
        onClosed = { keep?.invoke() },
        // A heading, as every other question here is, so the one being put can be found among the
        // answers above it; the field's own label is silent, so the heading says the question.
        modifier = Modifier.semantics(mergeDescendants = true) {
            heading()
            contentDescription = question
            if (onKeep != null) {
                customActions = listOf(
                    CustomAccessibilityAction(keepLabel) {
                        keep?.invoke()
                        true
                    },
                )
            }
        },
    )
}

/** The question being put, on a card of its own so it stands apart from the answers above it. */
@Composable
internal fun PainAsked(content: @Composable ColumnScope.() -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The question being asked, brought into view each time the asking moves on: it opens under the
 * rows of the answers already given, and after a few of them that is below the bottom of the screen.
 * Not the first question — that one comes the moment an area is marked, and pulling the page away
 * from the body then would take the canvas from under a finger still painting.
 */
@Composable
private fun PainAskedInView(step: PainStep, follow: Boolean, content: @Composable () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(step) {
        if (follow) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Box(Modifier.bringIntoViewRequester(requester)) { content() }
}

/**
 * The one line under a Save that is off: what is still to answer, the question in the words it is
 * asked in. An error rather than an explanation, so it stands open on the page.
 */
@Composable
private fun PainSaveWaitLine(wait: PainSaveWait) {
    val question = when (wait.wait) {
        PainWait.AREA -> null
        PainWait.SAME -> R.string.pain_same_question
        PainWait.CHANGED -> R.string.pain_same_changed_question
        PainWait.ANSWER -> wait.step?.waitQuestion()
    }
    Text(
        question?.let { stringResource(R.string.pain_save_needs_answer, stringResource(it)) }
            ?: stringResource(R.string.pain_save_needs_area),
        // Announced as it appears and as its question changes: nothing else tells a screen
        // reader's user why Save went grey, or what would light it.
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** The question Save waits for, as it is asked: the painkiller's as the question, not its row's statement. */
private fun PainStep.waitQuestion(): Int =
    if (this == PainStep.PAINKILLER) R.string.pain_painkiller_question else rowQuestion()

/**
 * The page's one filled action — log a pain, or save it — at the Medium button's height, and taller
 * when its words wrap at a large font size rather than cut in half by a fixed one. Its corners square
 * under the finger and spring back, the pressed shape the app's wide buttons have.
 */
@Composable
internal fun PainPrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) PwnCorners.medium else PwnDimensions.mediumButtonHeight / 2,
        PwnMotion.fastSpatial(),
        label = "button corner",
    )
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
    ) { Text(label) }
}

/**
 * An outlined button's edge in the outline colour. Material draws it in outlineVariant, a tonal
 * hairline that all but vanishes on this palette's page and cards, where `outline` clears 3:1 on
 * both; switched off, it fades as Material's own does.
 */
@Composable
private fun painOutline(enabled: Boolean): BorderStroke = BorderStroke(
    1.dp,
    if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
    },
)

/** Material's opacities for switched-off content and for a switched-off outline. */
private const val DISABLED_ALPHA = 0.38f
private const val DISABLED_OUTLINE_ALPHA = 0.12f

/** What an answer row says was asked: the question without the hint it carries while it is put. */
private fun PainStep.rowQuestion(): Int = when (this) {
    PainStep.TYPES -> R.string.pain_types_title
    PainStep.ONSET -> R.string.pain_onset
    PainStep.INTENSITY -> R.string.pain_intensity
    PainStep.NOTICED -> R.string.pain_noticed_title
    PainStep.STATUS -> R.string.pain_status
    PainStep.LASTED -> R.string.pain_lasted
    PainStep.PAINKILLER -> R.string.pain_painkiller
}

/**
 * What the earlier entry answered that is carried over rather than asked: its words, or when it was
 * first noticed, each as a phrase that reads on its own — "aching, tight", "noticed after a workout".
 */
internal fun Resources.carriedAnswer(step: PainStep, earlier: PainReport): String = when (step) {
    PainStep.TYPES -> earlier.types.joinToString(getString(R.string.list_comma)) { getString(it.shortRes) }
    PainStep.NOTICED -> earlier.noticed?.let { getString(it.shortRes) }.orEmpty()
    else -> ""
}

/** The answered questions and the one held open, kept across a rotation as step names, open first. */
private val PAIN_FLOW_SAVER = listSaver<PainFlow, String>(
    save = { listOf(it.open?.name.orEmpty()) + it.done.map(PainStep::name) },
    restore = { saved ->
        PainFlow(
            done = saved.drop(1).map(PainStep::valueOf).toSet(),
            open = saved.first().takeIf(String::isNotEmpty)?.let(PainStep::valueOf),
        )
    },
)

/**
 * The short day with its weekday, in the order the language writes one, rather than "Thu 18 Sep" in
 * every language, which is the wrong order in several of them.
 */
private const val PAIN_DAY_SKELETON = "EEEdMMM"

/**
 * An area marked again within the month: the earlier entry — the areas of it that the ones marked now
 * found it by, as it named them ([painSharedAreas]), its day, its words and rating — and whether this
 * is that pain. Asked before anything else, since the answer decides what else is asked. If it is the
 * same pain, the card asks whether it feels different; what is carried over from the earlier entry
 * instead of being asked again stands among the answers below.
 */
@Composable
private fun PainSameCard(
    earlier: PainRow,
    marked: Set<RegionSide>,
    same: Boolean?,
    changed: Boolean?,
    onSame: (Boolean) -> Unit,
    onChanged: (Boolean) -> Unit,
) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val found = remember(resources, earlier, marked, locale) { resources.painSameFoundText(earlier, marked, locale) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(found, style = MaterialTheme.typography.bodyLarge)
            PainChoice(stringResource(R.string.pain_same_question)) {
                PainAnswerChip(
                    selected = same == true,
                    onClick = { onSame(true) },
                    label = stringResource(R.string.pain_same_yes),
                )
                PainAnswerChip(
                    selected = same == false,
                    onClick = { onSame(false) },
                    label = stringResource(R.string.pain_same_new),
                )
            }
            if (same == true) {
                PainChoice(stringResource(R.string.pain_same_changed_question)) {
                    PainAnswerChip(
                        selected = changed == false,
                        onClick = { onChanged(false) },
                        label = stringResource(R.string.pain_same_unchanged),
                    )
                    PainAnswerChip(
                        selected = changed == true,
                        onClick = { onChanged(true) },
                        label = stringResource(R.string.pain_same_changed),
                    )
                }
            }
        }
    }
}

/**
 * The sentence an earlier pain is asked about in — "Knee · left: logged on Thu 18 Sep — aching, 6/10."
 * — the areas of it that [marked] finds it by, as it named them ([painSharedAreas]), its day, its words
 * and rating; without words or a rating the day alone.
 */
internal fun Resources.painSameFoundText(earlier: PainRow, marked: Set<RegionSide>, locale: Locale): String {
    val report = earlier.report
    val earlierDay = earlier.entry.day
    val day = runCatching { painDayText(PAIN_DAY_SKELETON, locale, LocalDate.parse(earlierDay)) }
        .getOrDefault(earlierDay)
    val areas = painSharedAreas(report, marked).joinToString(getString(R.string.list_comma)) { painArea(it) }
    val words = report.types.joinToString(getString(R.string.list_comma)) { getString(it.shortRes) }
    val details = listOfNotNull(
        words.takeIf { it.isNotEmpty() },
        report.nrs?.let { getString(R.string.pain_nrs_value, it) },
    ).joinToString(getString(R.string.list_comma))
    return if (details.isEmpty()) {
        getString(R.string.pain_same_found_bare, areas, day)
    } else {
        getString(R.string.pain_same_found, areas, day, details)
    }
}

/**
 * On an entry opened again that follows up an earlier one: the day that pain was first reported, as
 * the history prints it — with its year when that is not the year of the entry's own [entryDay].
 */
@Composable
private fun PainSameAsCard(firstAt: Long, entryDay: String) {
    val locale = LocalLocale.current.platformLocale
    val day = remember(firstAt, entryDay, locale) {
        val zone = ZoneId.systemDefault()
        skeletonDateText(painFirstReportSkeleton(firstAt, entryDay, zone), locale, firstAt, zone)
    }
    PwnCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.pain_same_as, day),
            Modifier.padding(PwnDimensions.cardPadding),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * The areas the entry will record, as chips a tap removes, and "Add area", which opens the whole list
 * over the page ([PainAreaPicker]) — a modal with a search, since every area on every side is a list
 * too long to unfold in the page. The list is also how an entry is made without painting at all,
 * which is the only way a screen reader or a switch can make one.
 */
@Composable
internal fun PainAreas(marked: Set<RegionSide>, onOpenList: () -> Unit, onRemove: (RegionSide) -> Unit) {
    val resources = LocalResources.current
    val title = stringResource(R.string.pain_areas)
    val addArea = stringResource(R.string.pain_add_area)
    val removeArea = stringResource(R.string.m3_pain_remove_area)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Where the picture ends, at the palm and at the neck, is behind the dot beside the label
        // rather than printed under the list.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
            HelpDot(title) {
                Text(stringResource(R.string.pain_hand_note))
                Text(stringResource(R.string.pain_head_note))
            }
        }
        if (marked.isEmpty()) {
            Text(
                stringResource(R.string.pain_areas_none),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            marked.forEach { area ->
                val name = resources.painArea(area)
                PwnFilterChip(
                    selected = true,
                    onClick = { onRemove(area) },
                    label = name,
                    // To a screen reader, an area on the entry with a remove action: as Material's
                    // chip it would be a ticked checkbox whose double tap made it vanish.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = name
                        role = Role.Button
                        onClick(label = removeArea) {
                            onRemove(area)
                            true
                        }
                    },
                    trailingIcon = Icons.Outlined.Close,
                )
            }
            PwnFilterChip(
                selected = false,
                onClick = onOpenList,
                label = addArea,
                // A button that opens the list: as a chip it would be a checkbox.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = addArea
                    role = Role.Button
                    onClick {
                        onOpenList()
                        true
                    }
                },
                leadingIcon = Icons.Outlined.Add,
            )
        }
    }
}

/**
 * Every area on every side the entry's [map] offers ([painAreaChoices]), named as the page names
 * them — "Front of the thigh · left" — in a dialog cut like the food catalogue's: a search field over
 * the list, and Close. An area already on the entry that the map does not offer — "Abdomen", which
 * the second map offers as its six parts — stays in the list, ticked, so it can be taken off.
 *
 * What is typed is looked for in the area's name in the language showing and in its English words
 * ([painAreaSearchNames]), case and accents folded as the lab sheet reader folds a name
 * ([painAreasMatching]); every word typed has to be in the name, so "thigh left" finds the one thigh.
 * A tick marks the areas already on the entry, and a tap marks an area or takes it off — the page
 * behind changes as it is tapped, and Close goes back to it.
 *
 * The English words are the area's own names rather than its English label read through a second
 * locale: reading another locale's strings takes a Configuration.setLocale, which lint refuses
 * (AppBundleLocaleChanges), and a bundle from Play holds no Russian strings on an English phone to
 * read. So a page in Russian finds an area by its English words too, but a page in English does not
 * find one by its Russian name, and an English word the area's own name lacks — "middle",
 * "buttock", "lower leg" — finds nothing.
 */
@Composable
private fun PainAreaPicker(
    marked: Set<RegionSide>,
    map: PainMapVersion,
    onToggle: (RegionSide) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pain_add_area)) },
        text = { PainAreaList(marked, map, onToggle, PAIN_AREA_PICKER_HEIGHT) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/**
 * The list of areas itself, as the dialog above and the chat's map hold it: a search field over every
 * area the entry's [map] offers ([painAreaChoices]), each a row a tap ticks or takes off ([onToggle]),
 * no taller than [maxHeight], where it scrolls.
 */
@Composable
internal fun PainAreaList(
    marked: Set<RegionSide>,
    map: PainMapVersion,
    onToggle: (RegionSide) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    val areas = remember(map, marked) { painAreaChoices(map, marked) }
    val names = remember(resources, areas) {
        areas.associateWith { area -> painAreaSearchNames(area) { resources.painArea(it) } }
    }
    val shown = remember(names, query) { painAreasMatching(names, query) }
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(PAIN_AREA_QUERY_MAX) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.pain_area_search)) },
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.pain_area_search_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = RegionSide::code) { area ->
                    PainAreaRow(resources.painArea(area), area in marked) { onToggle(area) }
                }
            }
        }
    }
}

/** The most a search for an area takes: longer than any area's name. */
private const val PAIN_AREA_QUERY_MAX = 60

/** The dialog's list stops here and scrolls, as the food catalogue's does. */
private val PAIN_AREA_PICKER_HEIGHT = 560.dp

/**
 * One area in the list: its name, and a tick when it is on the entry — the choice field's own row,
 * spoken as a checkbox, since any number of areas can be ticked. A copy of PwnDesign's private
 * ChoiceRow with toggleable for its selectable, kept here rather than widening that one.
 */
@Composable
private fun PainAreaRow(name: String, marked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.minimumTarget)
            .toggleable(value = marked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            Modifier.weight(1f),
            color = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
        )
        // An empty slot keeps every row's name on one margin.
        Box(Modifier.size(20.dp)) {
            if (marked) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The line for the chest, and for a severe head pain: what can be urgent, never what it is.
 *
 * It comes onto the page above the questions while a screen reader's focus is elsewhere — in the list
 * of areas, or on the ruler that reached eight — so its text is a live region, as the flash of a saved
 * entry is ([PwnActionFlash]): spoken when it appears, not found only by whoever swipes back up to it.
 */
@Composable
private fun PainSafetyLine() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(R.string.pain_safety),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The 1–10 scale, "on average today", on the app's ruler, under [label] — none where the question is
 * asked above it, as in a chat. Nothing is recorded until the ruler is moved or tapped: a number the
 * person never chose would read in the record exactly like one they did. Until then the needle rests
 * on [resting] — 5, or in a chat the earlier rating of the same pain or the person's own number — but
 * a screen reader hears "not set", and a tap — or a double tap from a screen reader — records the
 * number under the needle, after which the ruler speaks as usual.
 * A tap or a double tap on a number already set — the earlier pain's, where the ruler starts for the
 * same pain — records it again, as the person's own. The ruler moves on every pixel of a drag, so no
 * one position of it can be the moment the answer is given: [content], the three-second clock, takes
 * it after the last, and [onHeld] says while a finger rests on the ruler, which stops it.
 */
@Composable
internal fun PainIntensity(
    nrs: Int?,
    onNrs: (Int) -> Unit,
    onHeld: (Boolean) -> Unit,
    label: String? = stringResource(R.string.pain_intensity),
    resting: Int = DEFAULT_NRS,
    content: @Composable () -> Unit,
) {
    val unset = stringResource(R.string.pain_nrs_unset)
    val setLabel = stringResource(R.string.pain_nrs_record, stringResource(R.string.pain_nrs_value, nrs ?: resting))
    val record by rememberUpdatedState(onNrs)
    val shown by rememberUpdatedState(nrs)
    val held by rememberUpdatedState(onHeld)
    val rest by rememberUpdatedState(resting)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            label?.let { Text(it, color = PwnMuted) }
            Text(
                nrs?.let { stringResource(R.string.pain_nrs_value, it) } ?: "–",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            // A drag is the ruler's own and consumed there, so only a tap that stayed a tap reaches
            // this; it records what the needle shows.
            val tap = Modifier.whileFingerDown { held(it) }
                .pointerInput(Unit) { detectTapGestures { record(shown ?: rest) } }
            val untouched = if (nrs == null) {
                tap
                    .clearAndSetSemantics {
                        contentDescription = unset
                        onClick(label = setLabel) {
                            record(resting)
                            true
                        }
                    }
            } else {
                // The ruler's own actions stay beside the one that confirms the number as it is.
                tap.semantics {
                    onClick(label = setLabel) {
                        record(nrs)
                        true
                    }
                }
            }
            RulerPicker(
                values = PAIN_NRS_RANGE.toList(),
                value = nrs ?: resting,
                onValue = onNrs,
                label = { it.toString() },
                modifier = untouched,
                spacing = 26.dp,
                // Tall on 5 and 10, as on any scale that counts in fives; the ruler's own rule counts
                // from the first tick, which on a scale from 1 is 1 and 6.
                major = { it % 5 == 0 },
            )
            // Laid out left to right in every language, as the ruler above is: its 1 is at the left
            // and its 10 at the right even in Arabic, and a row that mirrored would put "mild" under the 10.
            // The words themselves still read in their own direction.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val anchor = MaterialTheme.typography.labelSmall
                    Text(stringResource(R.string.pain_nrs_mild), style = anchor, color = PwnMuted)
                    Text(stringResource(R.string.pain_nrs_unbearable), style = anchor, color = PwnMuted)
                }
            }
            content()
        }
    }
}

/**
 * What the entry's timing says against the workouts before it — shown when a session of a group
 * under it came in the window, and worded as the rule of thumb it is, with its sources.
 */
@Composable
private fun PainSorenessCard(entry: JournalEntry) {
    val context = LocalContext.current
    val verdict by produceState<SorenessVerdict?>(null, entry) {
        value = withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            val day = entry.day.toLocalDateOrNull() ?: return@withContext null
            // The same pain as an earlier entry is judged by its first one, which can be further back.
            val first = decodePainPayload(entry.measurementPayloadJson).same
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val from = minOf(day, first ?: day).minusDays(SORENESS_LOOKBACK_DAYS)
            val journal = StepsDb.get(context).dao().journalSince(from.atStartOfDay(zone).toInstant().toEpochMilli())
            trainingSoreness(entry, journal, zone)
        }
    }
    val shown = verdict?.takeIf { it.fit != SorenessFit.NO_SESSION } ?: return
    val session = shown.session ?: return
    val name = session.shownTitle(LocalResources.current)
    // When the session ended rather than how many hours before the pain: most starts are answered
    // as a day, not a moment, and an exact gap would claim one.
    val locale = LocalLocale.current.platformLocale
    val ended = remember(session.occurredAt, locale) {
        skeletonDateText(PAIN_MOMENT_SKELETON, locale, session.occurredAt, ZoneId.systemDefault())
    }
    val title = stringResource(R.string.pain_soreness_title)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Where the rule of thumb comes from is behind the dot; the verdict stays on the card, and
            // so does the line about dark urine, which asks to be seen promptly and is not background.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                HelpDot(title) {
                    Footnote(
                        stringResource(R.string.pain_soreness_heuristic),
                        stringResource(R.string.pain_soreness_source),
                    )
                }
            }
            if (shown.fit == SorenessFit.FITS) {
                Text(stringResource(R.string.pain_soreness_fits, name, ended))
            } else {
                Text(stringResource(R.string.pain_soreness_not_typical, name, ended))
                SorenessReason.entries.filter { it in shown.reasons }.forEach {
                    Text("• " + stringResource(it.labelRes))
                }
            }
            Footnote(stringResource(R.string.pain_urine), stringResource(R.string.pain_urine_source))
        }
    }
}

/**
 * A few days before the entry's own, or before its first one's when it follows up an earlier pain:
 * the window reaches back three days before an onset.
 */
private const val SORENESS_LOOKBACK_DAYS = 10L

@Composable
private fun Footnote(text: String, source: String) {
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        Text(source, style = MaterialTheme.typography.labelSmall, color = PwnMuted)
    }
}
