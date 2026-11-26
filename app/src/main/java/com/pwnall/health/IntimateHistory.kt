package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Every act in the journal, newest first: by the moment it was filed, then by id for two filed on
 * the same instant — an act typed for an earlier day carries that day's midday ([dayInstant]), and
 * two of them share it.
 */
internal fun intimateHistory(rows: List<JournalEntry>): List<JournalEntry> = rows
    .filter { it.intimateAct() != null }
    .sortedWith(compareByDescending<JournalEntry> { it.occurredAt }.thenByDescending { it.id })

/**
 * What saving an edited act does: the row to write, and the id of a row to delete first, if any.
 *
 * Health Connect knows a published act by its client record id, which is the row's id, and takes a
 * row written again under that id as the same record changed — the sync upserts it. So an act that
 * stays in the store it was in keeps its row, its id and its provenance, whatever else changes:
 * protected to unprotected, the ticks, the day, the time. An act that moves between stores cannot:
 * a partnered act re-filed as solo has to be withdrawn from Health Connect, which only a deletion's
 * tombstone does (at the next sync), and a solo act re-filed as partnered is a record Health Connect
 * has never had. Both are written as a new row in place of the old one.
 *
 * An act another app published keeps that app's marker while it stays in its store: the record is
 * theirs, and only this journal's copy of it changes.
 */
internal data class ActRevision(val write: JournalEntry, val replaces: Long?)

/**
 * [previous] as the edit page leaves it: what [filed] — the row a tile would file for the act, its
 * ticks and its day ([actEntry]) — says the act is, at [occurredAt]. The kind, the title, the ticks
 * under it and the payload are the tile's, so an edited act is spelt as a new one is; the row's id,
 * its note and its provenance are [previous]'s while it stays in its store. Pure, so which edits keep
 * the row has a test; [reviseAct] carries it out.
 */
internal fun revisedAct(previous: JournalEntry, filed: JournalEntry, occurredAt: Long): ActRevision {
    val stays = previous.intimateAct()?.reachesHealthConnect == filed.intimateAct()?.reachesHealthConnect
    val rebuilt = previous.copy(
        day = filed.day,
        occurredAt = occurredAt,
        kind = filed.kind,
        title = filed.title,
        subtitle = filed.subtitle,
        measurementPayloadJson = filed.measurementPayloadJson,
    )
    return if (stays) {
        ActRevision(rebuilt, replaces = null)
    } else {
        ActRevision(rebuilt.copy(id = 0L).adopted(), replaces = previous.id)
    }
}

/**
 * The instant an edited act is filed at: [minute] on [day]'s wall clock, and never later than now —
 * an act still to come is not one to record.
 */
internal fun actInstant(day: LocalDate, minute: Int, zone: ZoneId, now: Long): Long =
    day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli().coerceAtMost(now)

/**
 * The stamp an act with no time of its own carries on [day]: the day's midday, as [dayInstant] stamps
 * an act filed for an earlier day — or, on a today whose midday is still to come, its first moment,
 * since nothing is filed later than now. Neither is a time anybody gave, and [untimedAct] reads both
 * back as none: an act with no time moved to another day stays an act with no time.
 */
// Limitation: the first moment is the day's edge, which midday is there to stay off — read in another
// time zone it falls on the day before and shows a time. The "no time" flag doses carry ([statesNoTime], which
// untimedAct reads already) is not written into an act's payload.
internal fun untimedInstant(day: LocalDate, zone: ZoneId, now: Long): Long {
    val noon = day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    return if (noon <= now) noon else day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Whether [row] carries no time of its own: an act filed for an earlier day is stamped with that
 * day's midday ([dayInstant]), and one moved to a today before its midday with the day's first moment
 * ([untimedInstant]) — times nobody gave. An act whose time was set to exactly noon or midnight reads
 * the same; the two cannot be told apart, and showing no time for it says nothing false. A row whose
 * payload says its time was not said ([statesNoTime]) carries none wherever it stands.
 */
internal fun untimedAct(row: JournalEntry, zone: ZoneId): Boolean {
    if (statesNoTime(row)) return true
    val day = row.day.toLocalDateOrNull() ?: return false
    return row.occurredAt == day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli() ||
        row.occurredAt == day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Carries out an [ActRevision]: the old row's deletion, for its tombstone, and the write, as one. */
internal suspend fun StepsDb.reviseAct(revision: ActRevision) {
    withTransaction {
        revision.replaces?.let { deleteJournalEvent(it) }
        saveJournalEvent(revision.write)
    }
}

/**
 * The history page: every act, newest first, with its day, its time, what it was and what was
 * ticked. A row opens the act on a page of its own; nothing unfolds in the list. It takes the
 * sex-life page's place under the same kind of top bar, as the Work page's history does.
 */
@Composable
internal fun IntimateHistoryPage(status: String?, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val locale = LocalLocale.current.platformLocale
    val zone = rememberZone()
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMdy", "EEE d MMM y") }
    val acts by remember(db) {
        combine(
            db.dao().journalOfKindBetween(KIND_CYCLE_LOG, HISTORY_FROM, HISTORY_TO),
            db.dao().journalOfKindBetween(KIND_MASTURBATION, HISTORY_FROM, HISTORY_TO),
        ) { cycle, solo -> intimateHistory(cycle + solo) }
    }.collectAsStateWithLifecycle<List<JournalEntry>?>(null)
    val shown = acts
    // Only the rows in view are drawn: the history is every act there has ever been, and a column
    // of them all would compose every card before the page's first frame. The panel's pull to close
    // is told where the list stands — the chats' history does the same.
    val listState = rememberLazyListState()
    RegisterPanelScroll(listState)
    Column(Modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.intimate_history_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // The foot under the page's last row: 18dp, and the 20dp around the list.
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 38.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (shown != null && shown.isEmpty()) {
                    item { Text(stringResource(R.string.intimate_history_empty), color = PwnMuted) }
                }
                items(shown.orEmpty(), key = { it.id }) { row ->
                    IntimateActRow(row, dayFormat, zone) { onOpen(row.id) }
                }
            }
            PwnActionFlash(status)
        }
    }
}

/**
 * When [row] was, as the history prints it: its day, and its time when it has one of its own. The
 * pain history prints its entries' the same way: a pain filed for a day gone by carries that day's
 * midday too.
 */
internal fun actWhen(row: JournalEntry, dayFormat: DateTimeFormatter, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(row.occurredAt).atZone(zone)
    val day = row.day.toLocalDateOrNull() ?: at.toLocalDate()
    // An act filed for an earlier day has no time anybody gave it: the day alone, not "12:00".
    val time = if (untimedAct(row, zone)) "" else " · ${clockText(at.hour * 60 + at.minute)}"
    return day.format(dayFormat) + time
}

/** One act of the history: its drawing, what it was, when, and the ticks it carried. */
@Composable
private fun IntimateActRow(row: JournalEntry, dayFormat: DateTimeFormatter, zone: ZoneId, onOpen: () -> Unit) {
    val act = row.intimateAct() ?: return
    val notes = sexNotesOf(row.measurementPayloadJson).map { stringResource(it.titleRes) }
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PwnGlyph(act.art, Icons.Outlined.Circle, PwnIndigoSoft, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(act.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(actWhen(row, dayFormat, zone), style = MaterialTheme.typography.bodyMedium)
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(stringResource(R.string.list_comma)),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
            }
            // Drawing only, and mirrored with the layout: a "›" typed as text would point the same way in
            // Arabic, against the reading order the row opens in.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
        }
    }
}

/**
 * One act on a page of its own, read fresh from the journal so an edit starts from the row as it
 * stands. An act that is gone by the time the page opens — deleted from the calendar meanwhile —
 * leaves the bar and nothing to change.
 *
 * [onDone] has the word for a change or a deletion that went through; [onFailed] the failure card's,
 * for one that did not — handed up, since a change is taken as the page leaves as well.
 */
@Composable
internal fun IntimateActPage(
    entryId: Long,
    sex: String?,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val original by produceState<JournalEntry?>(null, entryId) { value = db.dao().journalEntry(entryId) }
    val shown = original?.takeIf { it.intimateAct() != null }
    if (shown == null) {
        Column(Modifier.fillMaxSize()) { PwnTopBar(stringResource(R.string.intimate_act_title), onBack) }
        return
    }
    key(shown.id) { IntimateActForm(db, shown, sex, onBack, onDone, onFailed) }
}

/**
 * What an act was, its ticks, its day and its time — each changed in place and the whole taken
 * [AUTO_TAKE_MS] after the last change, or as the page is left before that, with no Save to press;
 * Delete in the top bar. The day is offered as chips over the last week and the act's own day, as
 * the Work page offers a session's.
 */
@Composable
private fun IntimateActForm(
    db: StepsDb,
    original: JournalEntry,
    sex: String?,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // An edit taken as the page leaves is written after the page has gone; so is one the clock took
    // a moment before. Either is the person's change, and neither is cut off inside its transaction.
    val scope = rememberAutoTakeScope()
    val zone = rememberZone()
    val today = rememberToday()
    val startedAt = remember(original) { Instant.ofEpochMilli(original.occurredAt).atZone(zone) }
    val untimed = remember(original) { untimedAct(original, zone) }
    val originalDay = remember(original) { original.day.toLocalDateOrNull() }
    var act by rememberSaveable { mutableStateOf(original.intimateAct() ?: IntimateAct.PROTECTED) }
    var notes by rememberSaveable { mutableStateOf(sexNotesOf(original.measurementPayloadJson).toSet()) }
    var dayText by rememberSaveable { mutableStateOf((originalDay ?: startedAt.toLocalDate()).toString()) }
    var minute by rememberSaveable { mutableIntStateOf(startedAt.hour * 60 + startedAt.minute) }
    var timeSet by rememberSaveable { mutableStateOf(false) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // A write that throws is said on the failure card, and the bin and the form are given back, as
    // the quick log does it: a bare launch would let the exception take the app down.
    val failed: (Throwable) -> Unit = { failure ->
        if (failure is CancellationException) throw failure
        busy = false
        onFailed(resources.getString(R.string.quick_log_not_saved))
    }
    val day = LocalDate.parse(dayText)
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMd", "EEE d MMM") }
    // The profile's ticks, and any the act carries beyond them — a row restored from a backup made
    // under another profile — so an act is never reopened with a tick it holds out of sight.
    val offered = SexNote.entries.filter { it in sexNotesFor(sex) || it in notes }
    // An act another app wrote to Health Connect is that app's record: the journal keeps its copy in
    // step with it ([refreshedFromImport]), so a change made here would be undone at the next full
    // read, and the record itself can only be changed where it was made. It is shown as it stands,
    // and can still be deleted from this journal, which its tombstone keeps from coming back.
    val imported = original.healthConnectRecordKey?.startsWith(HEALTH_IMPORT_MARKER_PREFIX) == true

    // A time later than now on today is brought back to now where it is shown, so what is filed is
    // what the field says rather than a time it changed to unseen. Worked out from the time chosen,
    // which it never overwrites: a day tapped by mistake and tapped back leaves the time as it was.
    val shownMinute = run {
        val now = ZonedDateTime.now(zone)
        if (day == now.toLocalDate()) minOf(minute, now.hour * 60 + now.minute) else minute
    }

    Column(Modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.intimate_act_title), onBack) {
            IconButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        // The deletion reaches Health Connect at the next exchange, through its
                        // tombstone; the mirror publishes any other edit still owed, as the cycle
                        // screen does after its own deletions. The page is let go first: the mirror
                        // can wait on a sync under way, and the person is not kept waiting with it.
                        runCatchingCancellable { db.deleteJournalEvent(original.id) }
                            .onSuccess {
                                onDone(resources.getString(R.string.intimate_flash_deleted))
                                writeLocalHealthIfAvailable(app, db)
                            }
                            .onFailure(failed)
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
            ) { Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete)) }
        }
        if (imported) {
            Column(
                Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(act.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(actWhen(original, dayFormat, zone), style = MaterialTheme.typography.bodyMedium)
                notes.takeIf { it.isNotEmpty() }?.let { ticked ->
                    val comma = stringResource(R.string.list_comma)
                    val named = SexNote.entries.filter { it in ticked }.map { resources.getString(it.titleRes) }
                    Text(
                        named.joinToString(comma),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
                Text(
                    stringResource(R.string.provenance_imported),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
            return@Column
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.intimate_act_kind),
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
            PwnTileGrid(IntimateAct.entries.size) { index ->
                val option = IntimateAct.entries[index]
                PwnTile(
                    art = option.art,
                    title = stringResource(option.titleRes),
                    detail = stringResource(
                        if (option.reachesHealthConnect) R.string.sexual_synced else R.string.sexual_local_only,
                    ),
                    selected = option == act,
                ) {
                    if (option != act) {
                        act = option
                        revision++
                    }
                }
            }
            PwnTickField(
                label = stringResource(R.string.sexual_notes_title),
                options = offered.map { stringResource(it.titleRes) },
                selected = offered.indices.filter { offered[it] in notes }.toSet(),
                onToggle = { index ->
                    val note = offered[index]
                    notes = if (note in notes) notes - note else notes + note
                    revision++
                },
            )
            Text(
                stringResource(R.string.intimate_act_day),
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
            // One day to an act, so the chips are a group of radio buttons to a screen reader.
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                workDayChoices(today, originalDay, day).forEach { option ->
                    PwnFilterChip(
                        selected = option == day,
                        onClick = {
                            if (option != day) {
                                dayText = option.toString()
                                revision++
                            }
                        },
                        label = option.format(dayFormat),
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            ClockTimePickerCard(
                label = stringResource(R.string.intimate_act_time),
                // An act with no time shows the midday it was stamped with, said to be none below,
                // until a time is set; it keeps none on a new day ([untimedInstant]).
                minuteOfDay = if (untimed && !timeSet) minute else shownMinute,
                onValue = {
                    minute = it
                    timeSet = true
                    revision++
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // An act filed for an earlier day starts the picker at the midday it was stamped with,
            // which is not a time anybody gave; said so until a time is set. Left untouched, the act
            // keeps its midday stamp and stays without a time.
            if (untimed && !timeSet) {
                Text(
                    stringResource(R.string.intimate_act_time_unrecorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
            AutoTakeClock(armed = revision > 0 && !busy, restart = revision, takeOnLeave = true, onTaken = {
                // Taken as the page leaves, the line knows only what it last drew: a deletion
                // already under way since then is not to be followed by the edit.
                if (busy) return@AutoTakeClock
                busy = true
                val now = System.currentTimeMillis()
                // An act with no time, given none here, keeps none on the day it now stands on.
                val keepsNoTime = untimed && !timeSet
                val stamp = if (keepsNoTime) untimedInstant(day, zone, now) else actInstant(day, shownMinute, zone, now)
                val write = revisedAct(
                    previous = original,
                    filed = actEntry(resources, act, day, SexNote.entries.filter { it in notes }),
                    occurredAt = stamp,
                )
                // Nothing left to take: the saved state the app may be rebuilt from holds no change.
                revision = 0
                scope.launch {
                    runCatchingCancellable { db.reviseAct(write) }
                        .onSuccess {
                            onDone(resources.getString(R.string.intimate_flash_changed))
                            // Health Connect upserts the kept row by its client record id now rather
                            // than at the next exchange; a replaced partnered act's tombstone still
                            // waits for it. After the page is let go: the mirror can wait on a sync
                            // under way.
                            writeLocalHealthIfAvailable(app, db)
                        }
                        .onFailure(failed)
                }
            })
        }
    }
}

/** The history reads every act there is: the whole of the journal's range of days. */
private const val HISTORY_FROM = "0000-01-01"
private const val HISTORY_TO = "9999-12-31"
