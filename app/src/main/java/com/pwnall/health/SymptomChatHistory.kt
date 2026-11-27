package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Whether [row]'s chat is carried on rather than read: it has not ended, and it was begun today —
 * whatever day it is filed under, so a chat for last Tuesday begun this morning is carried on today.
 */
internal fun continuesLive(row: ChatRow, log: ChatLog, today: LocalDate, zone: ZoneId): Boolean =
    log.end == null && Instant.ofEpochMilli(row.startedAt).atZone(zone).toLocalDate() == today

/**
 * A chat's name in the history: its first thread with a topic, by that topic's own title — for
 * "something else" the person's first words, never words of suicide — with "+N" for each other
 * thread with a topic; [untitled] for a chat of words alone.
 */
internal fun historyTitle(
    log: ChatLog,
    title: (ChatThread) -> String,
    untitled: String,
    more: (String, Int) -> String,
): String {
    val topical = log.threads.filter { it.topic != null }
    val first = topical.firstOrNull() ?: return untitled
    val named = title(first)
    return if (topical.size > 1) more(named, topical.size - 1) else named
}

/** The minute of the day a chat began, said only when it began on the day it is filed under. */
internal fun historyMinute(row: ChatRow, zone: ZoneId): Int? {
    val begun = Instant.ofEpochMilli(row.startedAt).atZone(zone)
    return (begun.hour * 60 + begun.minute).takeIf { begun.toLocalDate().toString() == row.day }
}

/** Whether the chat was left while a thread's questions were still open: "Not finished". */
internal fun historyUnfinished(log: ChatLog): Boolean = log.end == null && log.threads.lastOrNull()?.ended == false

/** What the chat saved that is still as it saved it, by the titles its records were written with. */
internal fun historySaved(log: ChatLog): List<String> =
    log.threads.flatMap { thread -> thread.filed.filter { it.state == LinkState.FILED }.map { it.title } }

/** The journal kind a thread's records are of, for the history's drawing. */
private fun ChatThread.recordKind(): String? = when (topic?.record) {
    null -> null
    ChatRecord.SYMPTOM -> KIND_SYMPTOM
    ChatRecord.PAIN -> KIND_PAIN
    ChatRecord.MOOD -> KIND_MOOD
    ChatRecord.DOSE, ChatRecord.COURSE -> KIND_MED
}

/**
 * Every chat, newest first, from the chats' own table. A row opens the chat on a page of its own;
 * nothing unfolds in the list. It takes the chat's place under the same kind of top bar, as the
 * sex-life page's history does. A row says what the chat was about, when, and that it was not
 * finished or what it saved; nothing else of it. An entry saved without a chat, or with a one-topic
 * chat kept inside its own row, is not here: it opens from the day list.
 */
@Composable
internal fun SymptomChatHistoryPage(onBack: () -> Unit, onOpen: (ChatRow) -> Unit) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val locale = LocalLocale.current.platformLocale
    val zone = rememberZone()
    val dayFormat = remember(locale) { localizedDatePattern(locale, "EEEMMMdy", "EEE d MMM y") }
    val chats by remember(db) { db.dao().chats() }.collectAsStateWithLifecycle<List<ChatRow>?>(null)
    val shown = chats
    // Only the rows in view are drawn: a row reads its chat's whole text to name it, and a column of
    // every chat ever had would draw and read them all before the page's first frame. The panel's pull
    // to close is told where the list stands.
    val listState = rememberLazyListState()
    RegisterPanelScroll(listState)
    Column(Modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.symptom_chat_history), onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            // The foot under the last row: 18dp, and the 20dp around the list.
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Nothing in the list until the chats are read: a list that has had no item keeps the
            // place a rotation brought back, or the way back from a chat opened here.
            if (shown != null && shown.isEmpty()) {
                item { Text(stringResource(R.string.symptom_chat_history_empty), color = PwnMuted) }
            }
            items(shown.orEmpty(), key = { it.key }) { row -> SymptomChatRow(row, dayFormat, zone) { onOpen(row) } }
        }
    }
}

/** When a chat was, as the history prints it: its day, and its time when it began on that day. */
private fun chatWhen(row: ChatRow, dayFormat: DateTimeFormatter, zone: ZoneId): String {
    val day = row.day.toLocalDateOrNull()
        ?: Instant.ofEpochMilli(row.startedAt).atZone(zone).toLocalDate()
    return day.format(dayFormat) + historyMinute(row, zone)?.let { " · ${clockText(it)}" }.orEmpty()
}

/** One chat of the history: its first record's drawing, what it was about, when, and how it stood. */
@Composable
private fun SymptomChatRow(row: ChatRow, dayFormat: DateTimeFormatter, zone: ZoneId, onOpen: () -> Unit) {
    val resources = LocalResources.current
    val log = remember(row.payload) { decodeChatLog(row.payload) ?: ChatLog() }
    val title = historyTitle(
        log,
        title = { thread -> checkNotNull(thread.topic).script.title(resources, thread) },
        untitled = stringResource(R.string.symptom_chat_title),
        more = { name, count -> resources.getString(R.string.symptom_chat_history_more, name, count) },
    )
    val saved = historySaved(log)
    val detail = when {
        historyUnfinished(log) -> stringResource(R.string.symptom_chat_unfinished)
        saved.isNotEmpty() -> stringResource(R.string.symptom_chat_history_saved, saved.joinToString(" · "))
        else -> ""
    }
    val visual = kindVisual(log.threads.firstNotNullOfOrNull { it.recordKind() } ?: KIND_SYMPTOM)
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PwnGlyph(visual.art, visual.icon, PwnIndigoSoft, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(chatWhen(row, dayFormat, zone), style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                }
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
