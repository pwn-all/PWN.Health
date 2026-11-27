package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every pain entry in the journal, "no pain today" answers among them, newest first: by its day, then
 * the moment on it, then by id for two filed on the same instant — an entry filed for a day gone by
 * carries that day's midday ([dayInstant]), and two of them share it. Read once each, as [PainRow]s.
 */
internal fun painHistory(rows: List<JournalEntry>): List<PainRow> = rows
    .filter { it.kind == KIND_PAIN }
    .sortedWith(
        compareByDescending<JournalEntry> { it.day }.thenByDescending { it.occurredAt }.thenByDescending { it.id },
    )
    .map { PainRow(it) }

/**
 * The history's short line for one entry — "6/10 · started the day before · still hurting when
 * logged" — with each part only when the entry has it: the rating; when it started, counted back
 * from the entry ([PainOnset.sinceRes]), or for one that follows up an earlier pain the day that pain
 * was first reported ([sameAs]); and whether it still hurt then or how long it lasted, from
 * [lastedMinutes], the row's own duration. A "no pain today" has nothing to add to its name and day.
 */
internal fun painHistoryLine(
    report: PainReport,
    lastedMinutes: Int?,
    score: (Int) -> String,
    onset: (PainOnset) -> String,
    sameAs: (Long) -> String,
    stillHurts: String,
    lasted: (Int) -> String,
): String {
    if (report.none) return ""
    return listOfNotNull(
        report.nrs?.let(score),
        report.same?.let(sameAs) ?: report.onset?.let(onset),
        lastedMinutes?.let(lasted) ?: stillHurts,
    ).joinToString(" · ")
}

/**
 * The date skeleton for the day a follow-up's pain was first reported ([firstAt]): with its year when
 * that is not the year of the follow-up's own [day]. Every follow-up points at the first report, so
 * a pain followed up for a year would otherwise read as "same pain as on 3 Oct" under a row of the
 * next 3 October.
 */
internal fun painFirstReportSkeleton(firstAt: Long, day: String, zone: ZoneId): String {
    val sameYear = runCatching { LocalDate.parse(day).year }.getOrNull() ==
        Instant.ofEpochMilli(firstAt).atZone(zone).year
    return if (sameYear) "dMMM" else "dMMMy"
}

/**
 * The history page: every pain entry, newest first, with its areas, its day — and its time when it
 * has one of its own — and its rating, start and status in short. A row opens the entry on its own
 * page ([PainHistoryEntryPage]); nothing unfolds in the list. It takes the pain page's place under
 * the same kind of top bar, as the sex-life page's history does.
 */
@Composable
internal fun PainHistoryPage(modifier: Modifier, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val locale = LocalLocale.current.platformLocale
    val zone = rememberZone()
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMdy", "EEE d MMM y") }
    val entries by remember(db) {
        db.dao().journalOfKindBetween(KIND_PAIN, PAIN_HISTORY_FROM, PAIN_HISTORY_TO)
            .map(::painHistory)
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle<List<PainRow>?>(null)
    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.pain_history_title), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shown = entries
            if (shown != null && shown.isEmpty()) {
                Text(stringResource(R.string.pain_history_empty), color = PwnMuted)
            }
            shown.orEmpty().forEach { row ->
                key(row.entry.id) { PainHistoryRow(row, dayFormat, zone) { onOpen(row.entry.id) } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One entry of the history: its glyph, its areas, when it was told, and what it said in short. */
@Composable
private fun PainHistoryRow(row: PainRow, dayFormat: DateTimeFormatter, zone: ZoneId, onOpen: () -> Unit) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val visual = row.entry.visual()
    val report = row.report
    val title = if (report.none) {
        stringResource(R.string.pain_none_entry)
    } else {
        val comma = resources.getString(R.string.list_comma)
        val areas = report.regions.joinToString(comma) { resources.painAreaShort(it) }
        areas.ifEmpty { stringResource(R.string.pain_title) }
    }
    val facts = painHistoryLine(
        report,
        lastedMinutes = row.entry.durationMinutes?.toInt(),
        score = { resources.getString(R.string.pain_nrs_value, it) },
        onset = { resources.getString(it.sinceRes) },
        sameAs = { at ->
            val first = skeletonDateText(painFirstReportSkeleton(at, row.entry.day, zone), locale, at, zone)
            resources.getString(R.string.pain_history_same, first)
        },
        stillHurts = resources.getString(R.string.pain_history_still_hurts),
        lasted = { resources.getString(R.string.pain_history_lasted, durationLabel(resources, it)) },
    )
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PwnGlyph(visual.art, visual.icon, visual.background, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(actWhen(row.entry, dayFormat, zone), style = MaterialTheme.typography.bodyMedium)
                if (facts.isNotEmpty()) {
                    Text(facts, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                }
            }
            // Drawn rather than typed: a "›" character would join the card's spoken name.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
        }
    }
}

/**
 * One entry opened from the history on the page a pain is painted on ([PainEntryPage]), read fresh
 * from the journal so a correction starts from the row as it stands: every answer a row, its own Save,
 * and Delete in its top bar, each said by the flash in the middle before the history comes back. An
 * entry gone by the time the page opens — deleted from the calendar meanwhile — leaves the bar and
 * nothing to change.
 *
 * Nothing here reaches Health Connect, which has no record for pain: the entry is saved and deleted
 * in the journal alone, as everywhere else it is.
 */
@Composable
internal fun PainHistoryEntryPage(
    modifier: Modifier,
    profile: UserProfile,
    entryId: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val original by produceState<JournalEntry?>(null, entryId) { value = db.dao().journalEntry(entryId) }
    val shown = original?.takeIf { it.kind == KIND_PAIN }
    if (shown == null) {
        Column(modifier.fillMaxSize()) { PwnTopBar(stringResource(R.string.pain_title), onBack) }
        return
    }
    key(shown.id) { PainEntryPage(modifier, profile, entry = shown, onBack = onBack, onDone = onDone) }
}

/** The history reads every entry there is: the whole of the journal's range of days. */
private const val PAIN_HISTORY_FROM = "0000-01-01"
private const val PAIN_HISTORY_TO = "9999-12-31"
