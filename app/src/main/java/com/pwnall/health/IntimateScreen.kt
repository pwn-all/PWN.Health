package com.pwnall.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Sexual activity, on its own screen.
 *
 * Kept apart from the bathroom for the obvious reason: they are not the same subject, and a list
 * that puts them one above the other is a list nobody wants to scroll past in company. It is also
 * the screen with the age gate, so having its own door makes that gate mean something.
 *
 * A partnered act reaches Health Connect as a sexual-activity record with its protection. The
 * ticks and anything logged as masturbation stay on the device — that record carries protection
 * and nothing else.
 *
 * A man is also asked about his mornings here, and only here: the check-in sits behind the same age
 * gate as the acts, and nothing about it reaches the home screen, a widget or a notification.
 *
 * The page is two sections, the morning and the acts, each a card under its own heading. The acts'
 * history and the charts of what the record went with are pages of their own, opened from the
 * acts' heading and the top bar; each takes this page's place in the same window, under a top bar
 * whose arrow brings the page back, as the Work page's history does.
 */
@Composable
fun IntimateScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { StepsDb.get(context) }
    val profile by remember { db.dao().profile() }.collectAsStateWithLifecycle(null)
    val today = rememberToday()
    var page by rememberSaveable(stateSaver = INTIMATE_PAGE_SAVER) { mutableStateOf(IntimatePage.MAIN) }
    var openAct by rememberSaveable { mutableStateOf<Long?>(null) }
    var status by rememberStatusLine()
    // The failure card of a change or a deletion an act's page could not write, over the page that
    // is up when it fails: a change is taken as the page leaves too, and the card follows it back.
    var failure by remember { mutableStateOf<String?>(null) }
    // Held above the pages, which leave composition while another is open: back from the history
    // or the charts, the page is where it was left.
    val mainScroll = rememberScrollState()
    val back: () -> Unit = { page = if (page == IntimatePage.ACT) IntimatePage.HISTORY else IntimatePage.MAIN }
    BackHandler(enabled = page != IntimatePage.MAIN, onBack = back)

    when (page) {
        IntimatePage.MAIN -> IntimateMainPage(
            modifier = modifier,
            db = db,
            sex = profile?.sex,
            scroll = mainScroll,
            onBack = onBack,
            onCharts = { page = IntimatePage.CHARTS },
            onHistory = { page = IntimatePage.HISTORY },
        )

        IntimatePage.HISTORY -> Box(modifier.fillMaxSize()) {
            IntimateHistoryPage(status, back) { id ->
                openAct = id
                page = IntimatePage.ACT
            }
            PwnFailureFlash(failure) { failure = null }
        }

        IntimatePage.ACT -> Box(modifier.fillMaxSize()) {
            val id = openAct
            if (id == null) {
                LaunchedEffect(Unit) { page = IntimatePage.HISTORY }
            } else {
                IntimateActPage(
                    entryId = id,
                    sex = profile?.sex,
                    onBack = back,
                    onDone = { word ->
                        status = word
                        // It can come late, from a write the page's leaving did not cancel: only this
                        // act's own page gives way to the history, never wherever the person has gone.
                        if (page == IntimatePage.ACT && openAct == id) page = IntimatePage.HISTORY
                    },
                    onFailed = { failure = it },
                )
            }
            PwnFailureFlash(failure) { failure = null }
        }

        IntimatePage.CHARTS -> Column(modifier.fillMaxSize()) {
            PwnTopBar(stringResource(R.string.intimate_charts_title), back)
            profile?.let {
                IntimateChartsPage(Modifier.weight(1f), it.sex, alcohol = habitsOffered(it.birthDate, today))
            }
        }
    }
}

/** The page itself: the top bar with the charts, the morning for a man, and the acts for everyone. */
@Composable
private fun IntimateMainPage(
    modifier: Modifier,
    db: StepsDb,
    sex: String?,
    scroll: ScrollState,
    onBack: () -> Unit,
    onCharts: () -> Unit,
    onHistory: () -> Unit,
) {
    // The acts capture no day here: their write reads the date when it is filed, which is what a
    // screen left open across midnight needs. The morning check-in keeps its own.
    val quickLog = rememberQuickLog()
    val chartsLabel = stringResource(R.string.intimate_charts_title)
    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.sexual_title), onBack) {
            IconButton(onClick = onCharts, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.AutoMirrored.Outlined.ShowChart, chartsLabel)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(scroll).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                // First, above the acts: it is the one question here asked every day, and it is
                // answered on waking, when the acts below have nothing to say. Air under it, more
                // than the page's own spacing, so the two sections read as two.
                if (morningCheckOffered(sex)) {
                    MorningCheckIn(db, quickLog)
                    Spacer(Modifier.height(SECTION_AIR))
                }

                // No age check here, and no sentence explaining one. Below [SEX_LOG_MIN_AGE]
                // this screen has no door on the add sheet and no route into it: naming the age it
                // opens at is how an age screen teaches a child what to type next time.
                ActsSection(db, quickLog, sex, onHistory)

                DataNoteLink(R.string.sexual_note)
                Spacer(Modifier.height(12.dp))
            }
            QuickLogFlash(quickLog)
        }
    }
}

/**
 * The acts: three tiles, and once one is tapped, "Anything to add?" with the ticks. The act is filed
 * [AUTO_TAKE_MS] after the last touch — the tap or a tick — with whatever is ticked then, or at once
 * when the page is left before that ([AutoTakeClock]); the marked tile tapped again takes it back,
 * and another tile switches to that act. The history of every act sits behind the icon in the
 * section's corner.
 */
@Composable
private fun ActsSection(db: StepsDb, quickLog: QuickLogState, sex: String?, onHistory: () -> Unit) {
    val resources = LocalResources.current
    // The act may be taken as the page leaves, after this section's own scope is gone.
    val scope = rememberAutoTakeScope()
    var pending by rememberSaveable { mutableStateOf<IntimateAct?>(null) }
    var notes by rememberSaveable { mutableStateOf(emptySet<SexNote>()) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    // The moment of the last change, which the answer is filed at rather than the moment the clock
    // runs out: a window that loses its focus without the app stopping — the other half of a split
    // screen — holds the clock for as long as it is away.
    var changedAt by rememberSaveable { mutableLongStateOf(0L) }
    val confirmations = IntimateAct.entries.associateWith { quickLogAdded(stringResource(it.titleRes)) }
    val historyLabel = stringResource(R.string.intimate_history_title)
    // "Anything to add?" opens under the tiles, past the bottom of a phone's screen, and three seconds
    // are not long enough to find it there: the page brings it up the moment a tile is tapped.
    val addition = remember { BringIntoViewRequester() }
    LaunchedEffect(pending) {
        if (pending != null) {
            // A frame for the question to be laid out, so there is something to bring into view.
            withFrameNanos {}
            addition.bringIntoView()
        }
    }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(stringResource(R.string.intimate_acts_title)) {
                IconButton(onClick = onHistory, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(Icons.Outlined.History, historyLabel)
                }
            }
            // Three squares rather than three rows: what is being chosen between is the kind of
            // act, and a shield beside a pair of hearts says which is which before the label does.
            PwnTileGrid(IntimateAct.entries.size) { index ->
                val act = IntimateAct.entries[index]
                PwnTile(
                    art = act.art,
                    title = stringResource(act.titleRes),
                    detail = stringResource(
                        if (act.reachesHealthConnect) R.string.sexual_synced else R.string.sexual_local_only,
                    ),
                    // One cooldown per tile: filing a protected act leaves the two beside it open.
                    enabled = !quickLog.locked(act.key),
                    selected = pending == act,
                ) {
                    val next = pendingActAfterTap(pending, act)
                    if (next == null) notes = emptySet()
                    pending = next
                    revision++
                    changedAt = System.currentTimeMillis()
                }
            }
            AutoTakeClock(
                armed = pending?.let { !quickLog.locked(it.key) } == true,
                restart = revision,
                takeOnLeave = true,
                onTaken = {
                    val act = pending ?: return@AutoTakeClock
                    val ticked = SexNote.entries.filter { it in notes }
                    val at = changedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    pending = null
                    notes = emptySet()
                    quickLog.log(scope, act.key, confirmations.getValue(act)) {
                        // The day is the last change's, not the composition's: a screen left open
                        // across midnight would file this entry under yesterday.
                        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
                        // Through the guarded write, not the bare one: the tile's own lock is
                        // `remember`, so a rotation inside the three seconds hands it back and a
                        // second row would be a second act in every count the body advice draws
                        // its bands from.
                        val entry = actEntry(resources, act, day, ticked).copy(occurredAt = at)
                        val filed = db.logIntimacy(entry, act.log, LocalDate.now())
                        // The ticks come back on a swallowed act, so it can be filed again with them.
                        if (!filed) notes = notes + ticked
                        filed
                    }
                },
            )
            pending?.let {
                Text(stringResource(R.string.intimate_anything_to_add), style = MaterialTheme.typography.titleSmall)
                // Folded away behind the one line that says what is ticked: on most acts nothing
                // is, and six open rows are six rows of nothing.
                val offered = sexNotesFor(sex)
                PwnTickField(
                    label = stringResource(R.string.sexual_notes_title),
                    options = offered.map { note -> stringResource(note.titleRes) },
                    selected = offered.indices.filter { i -> offered[i] in notes }.toSet(),
                    onToggle = { index ->
                        val note = offered[index]
                        notes = if (note in notes) notes - note else notes + note
                        revision++
                        changedAt = System.currentTimeMillis()
                    },
                    // The field rather than the question: brought up to the screen's bottom edge, the
                    // field has its question over it in view too.
                    modifier = Modifier.bringIntoViewRequester(addition),
                )
            }
        }
    }
}

/**
 * The act marked after a tap on [tapped]: that act, or none when it was the one already marked —
 * the second tap takes the first back.
 */
internal fun pendingActAfterTap(pending: IntimateAct?, tapped: IntimateAct): IntimateAct? =
    tapped.takeUnless { it == pending }

/** A section's heading inside its card, read as a heading, with its one action in the right corner. */
@Composable
private fun SectionHeading(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

/**
 * The morning check-in: the question for today — or for a past morning picked on the strip beneath
 * it — and the last [MORNING_STRIP_DAYS] answers.
 *
 * A past morning can be answered because a morning forgotten until the evening is still worth more
 * than a month's recall; the answer carries its own day and is marked as given later. The strip
 * draws what was answered and leaves the rest blank — no streak, no gap marked as missed: a sign of
 * the body counted every day is exactly the kind of record that turns into something to worry about.
 */
@Composable
private fun MorningCheckIn(db: StepsDb, quickLog: QuickLogState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // A morning may be taken as the page leaves, after this card's own scope is gone.
    val scope = rememberAutoTakeScope()
    val today = rememberToday()
    val zone = ZoneId.systemDefault()
    val from = today.minusDays(MORNING_STRIP_DAYS - 1L)
    val recent by remember(from, today) { db.dao().journalBetween(from.toString(), today.toString()) }
        .collectAsStateWithLifecycle(emptyList())
    val checks = remember(recent) { morningChecksByDay(recent) }
    // A past morning picked on the strip; null is today, and so is a pick that midnight has since
    // turned into today or pushed off the strip.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val day = picked?.let { it.toLocalDateOrNull() }
        ?.takeIf { it.isBefore(today) && !it.isBefore(from) }
        ?: today
    // When the phone was first picked up after that night, read the way the sleep estimate reads
    // it; null without usage access or with no night to find. Held with the day it was read for:
    // produceState keeps the old value while it reads the new day's, and another morning's
    // pick-up offered as this one's could be filed with it.
    val phoneRead by produceState<Pair<LocalDate, Long?>?>(null, day) { value = day to phoneWakeOn(context, day) }
    val phone = phoneRead?.takeIf { it.first == day }?.second
    val saved = stringResource(R.string.sexual_morning_saved)
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val question = stringResource(R.string.sexual_morning_question)
            SectionHeading(
                if (day == today) stringResource(R.string.sexual_morning_this_morning) else day.format(dayFormat),
            ) {
                // What the research can and cannot say about one morning, and where the answer is
                // kept: read once, behind the question it is about, rather than on every visit.
                HelpDot(question) {
                    Text(stringResource(R.string.sexual_morning_background))
                    Text(
                        stringResource(R.string.sexual_morning_background_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = PwnMuted,
                    )
                    Text(stringResource(R.string.sexual_morning_local))
                }
            }
            // Keyed on the day, so what was being set for one morning never carries over to another.
            key(day) {
                MorningQuestion(
                    day = day,
                    today = today,
                    stored = checks[day.toString()],
                    // Not the sleep estimate's own night: its end is the phone's reading, which
                    // the phone waking above has already judged — a screen nobody touched, or the
                    // unlock that opened the app, is no record of a night either.
                    known = MorningKnown(
                        recordedWakeAt(
                            recent.filter {
                                it.measurementSource != PHONE_ESTIMATE
                            },
                            day,
                        ),
                        phone,
                    ),
                    usualMinute = usualWakeMinute(checks.values, zone),
                    locked = quickLog.locked(MORNING_LOG_KEY),
                ) { check ->
                    // One key for all four answers: a morning has one answer, and a second filing
                    // inside the window would only be the first one bouncing.
                    quickLog.log(scope, MORNING_LOG_KEY, saved) {
                        db.logMorningCheck(morningCheckEntry(resources, day, check))
                        // Back to today once a past morning is answered — unless another morning
                        // has been picked since, which is what took this one off the screen.
                        if (picked == day.toString()) picked = null
                    }
                }
            }
            HorizontalDivider(color = PwnBorder)
            MorningStrip(from, today, checks, selected = day) { picked = it.takeIf { d -> d != today }?.toString() }
        }
    }
}

/** What the phone and the sleep record already know of one morning's waking. */
private data class MorningKnown(val recorded: Long?, val phone: Long?)

/**
 * One morning's question, or its answer with the pencil that opens it again.
 *
 * The four answers stand in one row. A tap on one marks it and opens, under it, what woke him and
 * when: the waking is offered from the phone's first use after the night, then the night's sleep
 * record, then — for today — the moment of answering, and the line under the time says which. A
 * past morning with none of those asks for the time, and the morning waits for it. The whole is
 * filed [AUTO_TAKE_MS] after the last change, with no button: the answer tapped, the waker chosen,
 * the time set — each restarts the clock, and an open picker holds it. Leaving the morning before
 * then — the page, another morning on the strip, or the app — files it as it stands ([AutoTakeClock]),
 * at the moment of its last change. A time still being turned in an open picker is not part of what
 * stands: leaving the app with the picker open files the morning without it.
 */
@Composable
private fun ColumnScope.MorningQuestion(
    day: LocalDate,
    today: LocalDate,
    stored: MorningCheck?,
    known: MorningKnown,
    usualMinute: Int,
    locked: Boolean,
    onAnswer: (MorningCheck) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val locale = LocalLocale.current.platformLocale
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }
    var changing by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<MorningErection?>(null) }
    var wokeBy by rememberSaveable { mutableStateOf<WokeBy?>(null) }
    var typedMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    // The moment of the last change, which the answer is filed at rather than the moment the clock
    // runs out: a window that loses its focus without the app stopping — the other half of a split
    // screen — holds the clock for as long as it is away.
    var changedAt by rememberSaveable { mutableLongStateOf(0L) }

    // A reopened morning whose answer was tapped off again shows its stored answer again: what
    // stands is still that one, and the pencil is the way back in.
    if (stored != null && (!changing || pending == null)) {
        PwnAnswerRow(
            question = null,
            answer = stringResource(
                R.string.sexual_morning_answered,
                if (day == today) stringResource(R.string.sexual_morning_today) else day.format(dayFormat),
                stringResource(stored.answer.titleRes).lowercase(locale),
                wakeClock(stored.wokeAt, zone),
            ),
            container = MaterialTheme.colorScheme.surface,
        ) {
            changing = true
            pending = stored.answer
            wokeBy = stored.wokeBy
            typedMinute = null
            revision = 0
        }
        return
    }

    Text(stringResource(R.string.sexual_morning_question), style = MaterialTheme.typography.titleSmall)
    MorningAnswerTiles(selected = pending, enabled = !locked) { answer ->
        pending = answer.takeUnless { it == pending }
        revision++
        changedAt = System.currentTimeMillis()
    }
    val now = ZonedDateTime.now(zone)
    // What the morning would be filed at untouched; null is a past morning with nothing to offer.
    val offered = morningWake(day, now, null, stored, known.recorded, known.phone)
    val wake = morningWake(day, now, typedMinute, stored, known.recorded, known.phone)
    AutoTakeClock(
        restart = revision,
        takeOnLeave = true,
        // Only an answer the person gave — the pencil reopens the stored one untouched — and only
        // once the waking is one: a past morning's time is never taken from the picker's start.
        armed = revision > 0 && pending != null && wake != null && !locked,
        onTaken = {
            val answer = pending ?: return@AutoTakeClock
            // "Now" is the moment of answering — the last change — not of drawing the screen, nor of
            // the clock running out.
            val at = Instant.ofEpochMilli(changedAt.takeIf { it > 0L } ?: System.currentTimeMillis()).atZone(zone)
            val filed = morningWake(day, at, typedMinute, stored, known.recorded, known.phone)
                ?: return@AutoTakeClock
            onAnswer(
                MorningCheck(
                    answer,
                    filed.at,
                    filed.source,
                    wokeBy,
                    answeredLater(stored, answer, day, at.toLocalDate()),
                    answeredAt = at.toInstant().toEpochMilli(),
                    fromPhone = filed.fromPhone,
                ),
            )
            changing = false
            pending = null
            typedMinute = null
            revision = 0
        },
    )
    if (pending == null) return

    val unset = stringResource(R.string.intimate_woke_by_unset)
    PwnChoiceField(
        label = stringResource(R.string.sexual_morning_woke_by),
        options = listOf(unset) + WokeBy.entries.map { stringResource(it.titleRes) },
        selected = wokeBy?.let { it.ordinal + 1 } ?: 0,
        onSelected = { index ->
            wokeBy = WokeBy.entries.getOrNull(index - 1)
            revision++
            changedAt = System.currentTimeMillis()
        },
    )
    if (offered == null) Text(stringResource(R.string.sexual_morning_woke_ask))
    val offeredMinute = offered?.let { morningMinute(it.at, zone) }
    ClockTimePickerCard(
        label = stringResource(R.string.intimate_woke_at),
        minuteOfDay = typedMinute ?: offeredMinute ?: usualMinute,
        onValue = { minute ->
            // Back on the time the morning offered is no time set at all: its source stays as it was.
            typedMinute = minute.takeUnless { it == offeredMinute }
            revision++
            changedAt = System.currentTimeMillis()
        },
        modifier = Modifier.fillMaxWidth(),
    )
    wake?.let { Text(wakeLine(it, zone), color = PwnMuted, style = MaterialTheme.typography.bodySmall) }
}

/**
 * The four answers in one row, each its glyph over its name. Four abreast leave a phone's card about
 * sixty dp a tile, so the names share one size, set so the longest single word fits a tile's width —
 * a name wraps between its words, as "Didn’t notice" does. The size gives way by one step of the
 * theme's type scale at most ([fittedLabelStyle]), so a larger text setting is never undone; where
 * even that does not fit, the longest word breaks inside itself rather than shrink further.
 */
@Composable
private fun MorningAnswerTiles(selected: MorningErection?, enabled: Boolean, onPick: (MorningErection) -> Unit) {
    val labels = MorningErection.entries.map { stringResource(it.titleRes) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = (maxWidth - ANSWER_GAP * (labels.size - 1)) / labels.size
        val style = fittedLabelStyle(labels, tileWidth - ANSWER_TILE_PADDING * 2)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ANSWER_GAP),
        ) {
            MorningErection.entries.forEachIndexed { index, answer ->
                MorningAnswerTile(
                    label = labels[index],
                    icon = answer.icon,
                    style = style,
                    selected = selected == answer,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onPick(answer) }
            }
        }
    }
}

/** One of the four: lit as the app's tiles are lit, by a heavier accent edge as well as the fill. */
@Composable
private fun MorningAnswerTile(
    label: String,
    icon: ImageVector,
    style: TextStyle,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = PwnDimensions.minimumTarget),
        enabled = enabled,
        shape = RoundedCornerShape(PwnCorners.large),
        color = if (selected) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
    ) {
        Column(
            Modifier.padding(horizontal = ANSWER_TILE_PADDING, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (selected) scheme.primary else PwnBlueInk)
            Text(
                label,
                style = style,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * labelMedium, made smaller only as far as the longest word among [labels] needs to fit [room] on
 * one line, and never further than labelSmall, the theme's next size down, at the same text setting.
 * A fit with no floor would take a name at 200 % back to about the default size, which undoes what the
 * person set; past the floor the word breaks instead. Measured rather than left to the text's own
 * wrapping, which breaks a word that does not fit wherever it runs out of room — "Частич-ная" — and
 * to auto-sizing, which counts a word broken that way as fitting.
 */
@Composable
private fun fittedLabelStyle(labels: List<String>, room: Dp): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val roomPx = with(LocalDensity.current) { room.toPx() }
    // As a share of labelMedium, so it scales with the person's text setting as the label does.
    val floor = (MaterialTheme.typography.labelSmall.fontSize.value / base.fontSize.value).coerceAtMost(1f)
    // Measured at the lit tile's weight, the wider of the two a name is drawn in.
    val widest = remember(labels, base, measurer) {
        val lit = base.copy(fontWeight = FontWeight.SemiBold)
        labels.flatMap { it.split(' ') }.maxOfOrNull { word ->
            measurer.measure(word, lit, softWrap = false, maxLines = 1).size.width
        } ?: 0
    }
    return if (widest <= roomPx || widest == 0) {
        base
    } else {
        base.copy(fontSize = base.fontSize * maxOf(roomPx / widest, floor))
    }
}

/** "Woke at 07:12 · from the sleep record", saying where the time came from. */
@Composable
private fun wakeLine(wake: MorningWake, zone: ZoneId): String = stringResource(
    when {
        wake.isNow -> R.string.sexual_morning_woke_now
        wake.fromPhone -> R.string.intimate_woke_phone
        wake.source == WakeSource.SLEEP -> R.string.sexual_morning_woke_sleep
        else -> R.string.sexual_morning_woke_typed
    },
    wakeClock(wake.at, zone),
)

/**
 * The last fortnight of mornings, two weeks of seven: what was answered, and nothing where nothing
 * was. A tap on a morning opens its question above.
 */
@Composable
private fun MorningStrip(
    from: LocalDate,
    today: LocalDate,
    checks: Map<String, MorningCheck>,
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val weekday = remember(locale) { DateTimeFormatter.ofPattern("EEEEE", locale) }
    val spoken = remember(locale) { localizedDatePattern(locale, "EEEEMMMMd", "EEEE d MMMM") }
    Text(
        pluralStringResource(R.plurals.sexual_morning_recent, MORNING_STRIP_DAYS, MORNING_STRIP_DAYS),
        style = MaterialTheme.typography.titleSmall,
    )
    // Each morning is a target of its own, and seven of them at the full minimum width do not fit a
    // phone's card. Where squeezing would take them under it, the fortnight scrolls sideways
    // instead — both weeks together, so a day stays above the same weekday — and opens on today at
    // the end. A window wide enough keeps an even split.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fits = maxWidth >= PwnDimensions.minimumTarget * DAYS_A_WEEK + MORNING_DAY_GAP * (DAYS_A_WEEK - 1)
        val scroll = rememberScrollState()
        LaunchedEffect(fits) { if (!fits) scroll.scrollTo(scroll.maxValue) }
        Column(
            if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            List(MORNING_STRIP_DAYS) { from.plusDays(it.toLong()) }.chunked(DAYS_A_WEEK).forEach { week ->
                Row(
                    if (fits) Modifier.fillMaxWidth() else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(MORNING_DAY_GAP),
                ) {
                    week.forEach { day ->
                        MorningDay(
                            day = day,
                            spoken = day.format(spoken),
                            weekday = day.format(weekday),
                            isToday = day == today,
                            check = checks[day.toString()],
                            chosen = day == selected,
                            modifier = if (fits) Modifier.weight(1f) else Modifier.width(PwnDimensions.minimumTarget),
                        ) { onPick(day) }
                    }
                }
            }
        }
    }
}

/** One morning of the strip: its weekday, its date, and the answer given, if one was. */
@Composable
private fun MorningDay(
    day: LocalDate,
    spoken: String,
    weekday: String,
    isToday: Boolean,
    check: MorningCheck?,
    chosen: Boolean,
    modifier: Modifier,
    onPick: () -> Unit,
) {
    val shape = RoundedCornerShape(PwnCorners.medium)
    val description = if (check == null) {
        stringResource(R.string.sexual_morning_day_empty, spoken)
    } else {
        stringResource(R.string.sexual_morning_day_answer, spoken, stringResource(check.answer.titleRes))
    }
    Column(
        modifier
            .clip(shape)
            .then(if (chosen) Modifier.border(1.5.dp, PwnBlueInk, shape) else Modifier)
            .selectable(selected = chosen, role = Role.Button, onClick = onPick)
            .semantics { contentDescription = description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(weekday, style = MaterialTheme.typography.labelSmall, color = PwnMuted)
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                check?.let { Icon(it.answer.icon, null, Modifier.size(18.dp), tint = PwnBlueInk) }
            }
        }
    }
}

/**
 * A neutral glyph per answer, graded as the answers are: full, half, empty, and a question mark for
 * a morning nobody could say. The same colour for all four — none of them is a result to flag.
 */
private val MorningErection.icon: ImageVector
    get() = when (this) {
        MorningErection.FIRM -> Icons.Filled.Circle
        MorningErection.PARTLY -> Icons.Outlined.Contrast
        MorningErection.NONE -> Icons.Outlined.RadioButtonUnchecked
        MorningErection.DIDNT_NOTICE -> Icons.AutoMirrored.Outlined.HelpOutline
    }

/** The sex-life screen's own pages, the history and one act's page among them. */
private enum class IntimatePage { MAIN, HISTORY, ACT, CHARTS }

private val INTIMATE_PAGE_SAVER: Saver<IntimatePage, String> = Saver(
    save = { it.name },
    restore = { name -> IntimatePage.entries.firstOrNull { it.name == name } ?: IntimatePage.MAIN },
)

/** The one key every morning answer logs under. */
private const val MORNING_LOG_KEY = "morning"

/** How many mornings the strip shows: two weeks, today last. */
private const val MORNING_STRIP_DAYS = 14

private const val DAYS_A_WEEK = 7

/** The gap between two mornings of the strip. */
private val MORNING_DAY_GAP = 4.dp

/** Between the four answers of the morning's row. */
private val ANSWER_GAP = 6.dp

/** Inside one of the four, on either side of its name. */
private val ANSWER_TILE_PADDING = 4.dp

/** The air between the morning and the acts, over the page's own spacing. */
private val SECTION_AIR = 10.dp
