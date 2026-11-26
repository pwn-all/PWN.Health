package com.pwnall.health

import android.content.Context
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How long the deleted flash stands before the page goes where it goes next. */
private const val CHAT_FLASH_HOLD_MS = 700L

/** The age every page the questions come from is written for. */
private const val ADULT_AGE = 18

/** The days of pain the chat looks back over for "the same pain?". */
private const val PAIN_MONTH_DAYS = 29L

/**
 * How far back the chats the chat reads go: a medicine's first weeks, which what its course was said
 * to be for is read back over; a line said once a month reads the last thirty days of them.
 */
internal const val RECENT_CHAT_DAYS = MED_START_WINDOW_DAYS

/** The chat's own pages: the chat, the history of chats, and one past chat read as it was said. */
internal enum class SymptomPage { CHAT, HISTORY, PAST }

private val SYMPTOM_PAGE_SAVER: Saver<SymptomPage, String> = Saver(
    save = { it.name },
    restore = { name -> SymptomPage.entries.firstOrNull { it.name == name } ?: SymptomPage.CHAT },
)

/** A deletion whose flash stands ([ChatPages]): the page it was made on, and whether it was the chat page's chat. */
private data class Deletion(val from: SymptomPage, val wasLive: Boolean)

private val DELETION_SAVER: Saver<Deletion?, String> = Saver(
    save = { deletion -> deletion?.let { "${it.from.name}|${it.wasLive}" }.orEmpty() },
    restore = { saved ->
        SymptomPage.entries.firstOrNull { it.name == saved.substringBefore('|') }
            ?.let { Deletion(it, saved.substringAfter('|') == "true") }
    },
)

/** The history page's saved state among the pages' ([ChatPages]). */
private const val HISTORY_STATE = "history"

/** The chat page's saved state among the pages': its own for each chat the page started afresh with. */
private fun chatStateKey(generation: Int) = "chat:$generation"

/**
 * Where a past chat comes from: a stored chat by its key, scrolled to the thread whose record stands
 * at [Chat.focusAt]; or a row of the journal read alone — an entry saved without a chat, a one-topic
 * chat kept inside its own row, or a row whose chat was deleted.
 */
internal sealed interface PastSource {
    data class Chat(val key: String, val focusAt: Long? = null) : PastSource

    data class Row(val id: Long) : PastSource
}

private val PAST_SOURCE_SAVER: Saver<PastSource?, String> =
    Saver(save = { pastSourceText(it) }, restore = ::pastSourceOf)

internal fun pastSourceText(source: PastSource?): String = when (source) {
    is PastSource.Chat -> "C|${source.key}|${source.focusAt ?: ""}"
    is PastSource.Row -> "R|${source.id}"
    null -> ""
}

/**
 * A past chat's saved state among the pages' ([ChatPages]), its own for each chat read: where it was read
 * to is kept while the history stands in its place, as the chat page's and the history's own are.
 */
private fun pastStateKey(source: PastSource?) = "past:" + pastSourceText(source)

internal fun pastSourceOf(saved: String): PastSource? {
    val parts = saved.split('|')
    return when (parts.firstOrNull()) {
        "C" -> parts.getOrNull(1)?.let { PastSource.Chat(it, parts.getOrNull(2)?.toLongOrNull()) }
        "R" -> parts.getOrNull(1)?.toLongOrNull()?.let { PastSource.Row(it) }
        else -> null
    }
}

/**
 * What the page opens on: a new chat, a stored one carried on — at the thread whose record stands at
 * [Live.focusAt], for a row of the day list — or one read as it was said.
 */
internal sealed interface Opened {
    data object New : Opened

    data class Live(val key: String, val focusAt: Long? = null) : Opened

    data class Past(val source: PastSource) : Opened
}

private val OPENED_SAVER: Saver<Opened?, String> = Saver(save = { openedText(it) }, restore = ::openedOf)

/** What the page opened on as its saved state keeps it ([OPENED_SAVER]); [openedOf] reads it back. */
internal fun openedText(opened: Opened?): String = when (opened) {
    Opened.New -> "N"
    is Opened.Live -> "L|${opened.key}|${opened.focusAt ?: ""}"
    is Opened.Past -> "P" + pastSourceText(opened.source)
    null -> ""
}

internal fun openedOf(saved: String): Opened? = when {
    saved == "N" -> Opened.New

    saved.startsWith("L|") -> saved.split('|').let { parts ->
        Opened.Live(parts.getOrElse(1) { "" }, parts.getOrNull(2)?.toLongOrNull())
    }

    saved.startsWith("P") -> pastSourceOf(saved.removePrefix("P"))?.let { Opened.Past(it) }

    else -> null
}

/**
 * Where back leads from [page] among the chat's pages: from a past chat the history opened, to the history;
 * from the history, to the page the pages were opened on — the chat, or the past chat read. Null is back
 * out of the chat's pages altogether, which the system's back does by itself ([ChatPages]).
 */
internal fun pageAfterBack(page: SymptomPage, fromHistory: Boolean, startsLive: Boolean): SymptomPage? = when {
    page == SymptomPage.PAST && fromHistory -> SymptomPage.HISTORY
    page == SymptomPage.HISTORY -> if (startsLive) SymptomPage.CHAT else SymptomPage.PAST
    else -> null
}

/** What the pages do once a deletion's flash has stood ([deletionMove]). */
internal enum class DeletionMove { STAY, AFRESH, HISTORY, DONE }

/**
 * What the pages do once "Deleted" has stood, the chat deleted on page [from] while [page] stands now. A
 * page gone elsewhere meanwhile stays where it is, only a new chat taking the deleted live one's place
 * ([wasLive]); a past chat the history opened gives way to the history; a chat opened from a row of the
 * day list ([entered]) closes the chat; any other gives way to a new chat.
 */
internal fun deletionMove(
    page: SymptomPage,
    from: SymptomPage,
    wasLive: Boolean,
    fromHistory: Boolean,
    entered: Boolean,
): DeletionMove = when {
    page != from -> if (wasLive) DeletionMove.AFRESH else DeletionMove.STAY
    from == SymptomPage.PAST && fromHistory -> DeletionMove.HISTORY
    entered -> DeletionMove.DONE
    else -> DeletionMove.AFRESH
}

/**
 * What a symptom row from the day list opens: the stored chat it was told in — carried on when it was
 * begun today and has not ended, else read, scrolled to that row — or, for a row saved without a chat,
 * one holding a one-topic chat of its own, or one whose chat was deleted, the row read alone.
 */
private suspend fun StepsDb.opened(entry: JournalEntry?, today: LocalDate, zone: ZoneId): Opened {
    entry ?: return Opened.New
    val key = chatKeyOf(entry)
    val row = key?.let { dao().chat(it) }
    val log = row?.payload?.let(::decodeChatLog)
    return when {
        key == null || row == null || log == null -> Opened.Past(PastSource.Row(entry.id))
        continuesLive(row, log, today, zone) -> Opened.Live(key, entry.occurredAt)
        else -> Opened.Past(PastSource.Chat(key, entry.occurredAt))
    }
}

/**
 * What the host lets the chat do: add a visit to the plans, open breathing or lab import, open a
 * record — a course among them where [opensCourses].
 */
private class ChatHosts(
    val onBook: ((VisitDraft) -> Unit)?,
    val onBreathing: (() -> Unit)?,
    val onLabs: (() -> Unit)?,
    val onOpenRecord: ((ChatRecordRef) -> Unit)?,
    val onJournal: ((ChatJournalEvent) -> Unit)?,
    val onLabDocument: ((String) -> Unit)?,
    val opensCourses: Boolean,
)

/**
 * Symptoms, worries, a pain, a mood, a medicine — told as a chat: one question at a time, each topic
 * a thread, and the records it notes filed through their own pages' doors. The chat's history is
 * behind the clock in the top bar, which takes this page's place in the same window. A past chat
 * opens from there as it was said, to read or delete; one begun today and left unfinished carries on.
 *
 * [entry] is a symptom row opened from the day list or the calendar. [day] is the day a new chat is
 * filed under when it is told for a day gone by. [onBook] adds a doctor's visit to the medicine tab's
 * plans; [onBreathing] opens that page; [onLabs] opens the lab editor with photo/PDF import and
 * review; [onOpenRecord] opens a record a card names, a course only where [opensCourses]. Any of
 * them null is something the chat does not offer.
 */
@Composable
internal fun SymptomChatScreen(
    modifier: Modifier,
    profile: UserProfile,
    entry: JournalEntry?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    day: String? = null,
    onBook: ((VisitDraft) -> Unit)? = null,
    onBreathing: (() -> Unit)? = null,
    onLabs: (() -> Unit)? = null,
    onOpenRecord: ((ChatRecordRef) -> Unit)? = null,
    onJournal: ((ChatJournalEvent) -> Unit)? = null,
    onLabDocument: ((String) -> Unit)? = null,
    opensCourses: Boolean = true,
) {
    // Wherever the chat stands, its panel closes only by the back arrow or the system back, never a pull,
    // and the window fits the keyboard rather than pan the page up under it.
    HoldPanelOpen()
    KeyboardResizesChat()
    val app = LocalContext.current.applicationContext
    // Opened once, the home circle's dot and rings are done.
    LaunchedEffect(Unit) { app.getSharedPreferences("home", Context.MODE_PRIVATE).edit { putBoolean(CHAT_SEEN, true) } }
    val db = remember(app) { StepsDb.get(app) }
    val today = rememberToday()
    val zone = rememberZone()
    var opened by rememberSaveable(entry?.id, stateSaver = OPENED_SAVER) {
        mutableStateOf(if (entry == null) Opened.New else null)
    }
    LaunchedEffect(entry?.id) { if (opened == null) opened = db.opened(entry, today, zone) }
    val first = opened
    if (first == null) {
        Column(modifier.fillMaxSize()) {
            PwnTopBar(stringResource(R.string.symptom_chat_title), onBack)
        }
        return
    }
    val hosts = ChatHosts(onBook, onBreathing, onLabs, onOpenRecord, onJournal, onLabDocument, opensCourses)
    ChatPages(modifier, profile, entry, first, onBack, onDone, day, hosts)
}

/**
 * The three pages, and where back and a deletion lead between them. The chat and the history keep
 * their saved state while another page stands in their place, as a rotation keeps it: back from the
 * history the chat has the words not yet sent, what was changed on its cards and the sitting it was
 * in, and back from a chat it opened the history stands where it was scrolled to. So does the past chat
 * the pages were opened with, come back to from the history where it was read to.
 */
@Composable
private fun ChatPages(
    modifier: Modifier,
    profile: UserProfile,
    entry: JournalEntry?,
    opened: Opened,
    onBack: () -> Unit,
    onDone: () -> Unit,
    day: String?,
    hosts: ChatHosts,
) {
    val today = rememberToday()
    val zone = rememberZone()
    val startsLive = opened !is Opened.Past
    val firstPast = (opened as? Opened.Past)?.source
    val firstLive = opened as? Opened.Live
    val touchExploration = rememberTouchExploration()
    var page by rememberSaveable(stateSaver = SYMPTOM_PAGE_SAVER) {
        mutableStateOf(if (startsLive) SymptomPage.CHAT else SymptomPage.PAST)
    }
    var past by rememberSaveable(stateSaver = PAST_SOURCE_SAVER) { mutableStateOf(firstPast) }
    var fromHistory by rememberSaveable { mutableStateOf(false) }
    // The chat the chat page carries on with — a new chat's own from its first write, so the history
    // and back bring it again — and a count that starts the page afresh in its place.
    var liveKey by rememberSaveable { mutableStateOf((opened as? Opened.Live)?.key) }
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()

    // Nothing of the chat the page showed is kept once it is deleted or another takes its place.
    fun forgetChat() = holder.removeState(chatStateKey(generation))
    var status by rememberStatusLine()
    // Whether a screen reader is told the flash: always for a deletion, an Undo or a Save again, which no
    // turn says; never for a record filed, whose card the turn that follows the write announces.
    var spoken by remember { mutableStateOf(true) }
    // A write that failed says so on the card that stays until it is tapped away.
    var failure by remember { mutableStateOf<String?>(null) }
    val deletedText = stringResource(R.string.work_flash_deleted)
    var deleted by rememberSaveable(stateSaver = DELETION_SAVER) { mutableStateOf<Deletion?>(null) }

    val back: () -> Unit = {
        when (pageAfterBack(page, fromHistory, startsLive)) {
            SymptomPage.HISTORY -> {
                // Left by back, a chat the history opened is read from its place again the next time; the
                // one the pages were opened with keeps where it was read to while the history stands.
                holder.removeState(pastStateKey(past))
                page = SymptomPage.HISTORY
            }

            // Left by back, the history opens at its top the next time; left for a chat it opened, it is
            // come back to where it stood.
            SymptomPage.CHAT -> {
                holder.removeState(HISTORY_STATE)
                page = SymptomPage.CHAT
            }

            SymptomPage.PAST -> {
                holder.removeState(HISTORY_STATE)
                past = firstPast
                fromHistory = false
                page = SymptomPage.PAST
            }

            null -> onBack()
        }
    }
    BackHandler(enabled = pageAfterBack(page, fromHistory, startsLive) != null, onBack = back)

    // "Deleted" stands a moment before the page moves on. The deletion can land after the person has
    // gone elsewhere, and then only the page it was made from gives way, never where they went. The move
    // is the pages' saved state, not a coroutine of theirs: a rotation in that moment would cut it off
    // and bring the deleted chat's page back, every write of it dropped.
    fun afterDelete(from: SymptomPage, wasLive: Boolean) {
        spoken = true
        status = deletedText
        deleted = Deletion(from, wasLive)
    }
    LaunchedEffect(deleted) {
        val made = deleted ?: return@LaunchedEffect
        delay(CHAT_FLASH_HOLD_MS)
        if (made.wasLive) {
            liveKey = null
            forgetChat()
        }
        when (deletionMove(page, made.from, made.wasLive, fromHistory, entered = entry != null)) {
            DeletionMove.STAY -> Unit
            DeletionMove.AFRESH -> generation++
            DeletionMove.HISTORY -> page = SymptomPage.HISTORY
            DeletionMove.DONE -> onDone()
        }
        deleted = null
    }

    Box(modifier.fillMaxSize()) {
        when (page) {
            SymptomPage.CHAT -> key(generation) {
                // A write that lands after this chat gave way to another does not claim the page.
                val chatGeneration = generation
                holder.SaveableStateProvider(chatStateKey(chatGeneration)) {
                    SymptomLiveChat(
                        profile = profile,
                        chatKey = liveKey,
                        // The thread a row of the day list was told in, the first time only.
                        focusAt = firstLive?.focusAt?.takeIf { generation == 0 && liveKey == firstLive.key },
                        day = day,
                        pages = LivePages(
                            onBack = back,
                            onHistory = { page = SymptomPage.HISTORY },
                            onStarted = { started -> if (generation == chatGeneration) liveKey = started },
                            onDeleted = { afterDelete(SymptomPage.CHAT, wasLive = true) },
                            onFlash = { text, say ->
                                spoken = say
                                status = text
                            },
                            onFailure = { failure = it },
                            onResume = { other ->
                                forgetChat()
                                liveKey = other
                                generation++
                            },
                        ),
                        hosts = hosts,
                    )
                }
            }

            SymptomPage.HISTORY -> holder.SaveableStateProvider(HISTORY_STATE) {
                SymptomChatHistoryPage(onBack = back) { row ->
                    val log = decodeChatLog(row.payload) ?: ChatLog()
                    if (continuesLive(row, log, today, zone)) {
                        // The chat the chat page has is come back to as it was left; another starts the
                        // page afresh.
                        if (row.key != liveKey) {
                            forgetChat()
                            liveKey = row.key
                            generation++
                        }
                        page = SymptomPage.CHAT
                    } else {
                        past = PastSource.Chat(row.key)
                        fromHistory = true
                        page = SymptomPage.PAST
                    }
                }
            }

            SymptomPage.PAST -> key(past) {
                val source = past
                holder.SaveableStateProvider(pastStateKey(source)) {
                    SymptomPastChat(
                        profile = profile,
                        source = source,
                        onBack = back,
                        onHistory = { page = SymptomPage.HISTORY },
                        onDeleted = {
                            holder.removeState(pastStateKey(source))
                            afterDelete(SymptomPage.PAST, wasLive = (source as? PastSource.Chat)?.key == liveKey)
                        },
                        onFailed = { failure = it },
                        onOpenRecord = hosts.onOpenRecord,
                        opensCourses = hosts.opensCourses,
                    )
                }
            }
        }
        // Under a screen reader a record's flash says nothing: its card stands in the turn that follows
        // the write, and that turn is announced. A deletion, Undo and Save again are said: no turn says them.
        PwnActionFlash(status, announce = spoken || !touchExploration)
        PwnFailureFlash(failure) { failure = null }
    }
}

/** Whether a screen reader explores the screen by touch: read once per page, as the chat's other choices are. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true
    }
}

/**
 * The window fits the keyboard while the chat stands — resized for it, set for the chat alone and put
 * back as it goes ([ChatKeyboardMode]). Left to itself the system picks: on a Pixel 9a it pans the
 * whole window up by the keyboard's height, and the bar and the bot's newest words leave the screen. On
 * this edge-to-edge window the mode pans nothing and reports the keyboard as an inset, which the chat
 * takes once.
 */
@Composable
private fun KeyboardResizesChat() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val mode = ChatKeyboardMode(window.attributes.softInputMode, window::setSoftInputMode)
        val again = ViewTreeObserver.OnWindowFocusChangeListener { focused -> mode.focused(focused) }
        mode.start()
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(again)
        onDispose {
            window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(again)
            mode.stop()
        }
    }
}

/**
 * The keyboard mode of the window the chat stands in, the window's own mode being [before]: the chat's
 * ([fittingKeyboard]) while it stands, said again whenever the window has the focus back, and the
 * window's own again as it goes ([keyboardAfterChat]). [apply] is `Window.setSoftInputMode`. Neither
 * carries the forward-navigation bit [before] holds when the chat is the first screen after a start
 * or a restore: said again with resize on a focus regained, before the view root reads and clears it
 * (`ViewRootImpl.handleWindowFocusChanged`), it is the platform's sign to show the keyboard by itself
 * over a focused field.
 */
internal class ChatKeyboardMode(private val before: Int, private val apply: (Int) -> Unit) {
    private val own = fittingKeyboard(before)

    /** The chat comes: the window fits the keyboard. */
    fun start() = apply(own)

    /**
     * The window has the focus back — the app brought back, for one. For a window whose activity
     * declares no mode the system chooses pan or resize afresh each time a view of it is shown or
     * hidden, as the app sent away and brought back is, and it chooses pan for a window with no view
     * that scrolls, which a window of Compose is: the chat's mode is said again before a keyboard can
     * open over it.
     */
    fun focused(focused: Boolean) {
        if (focused) apply(own)
    }

    /** The chat goes: the window's own mode again. */
    fun stop() = apply(keyboardAfterChat(before))
}

/** [mode] with the keyboard resizing the window, its keyboard-state bits kept and nothing else ([ChatKeyboardMode]). */
// The platform's own name for it; Android 11 deprecated the constant in favour of the inset an
// edge-to-edge window already gets, and the mode is what stops the pan on the window this app has.
@Suppress("DEPRECATION")
internal fun fittingKeyboard(mode: Int): Int =
    (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

/**
 * The mode the window goes back to as the chat goes, its own having been [before], with its
 * keyboard-state bits and nothing else ([ChatKeyboardMode]): that mode where it names how the keyboard
 * moves the window; where it names none — the activity declares none, and the platform chooses for
 * itself, pan for a window with no scrolling view shown (`ViewRootImpl.performTraversals`) — pan, said
 * outright. Whether pan is what the window had before the chat only a device shows: `dumpsys window`
 * `sim=` on a fresh process before the chat is first opened, against `sim=` after it is left.
 * [before] as it was would put back nothing: the platform writes no mode of 0
 * (`Window.setSoftInputMode`), and a window told a mode that names no movement keeps the one it last
 * had (`ViewRootImpl.setLayoutParams`) — the chat's resize, for every screen after it.
 */
internal fun keyboardAfterChat(before: Int): Int {
    val state = before and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
    val adjust = before and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
    return state or if (adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
        adjust
    } else {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }
}

/**
 * Holds the picture a return from a panel shows over this page until the page is [ready] — so the
 * person sees the chat as they left it, then the same chat live, never an empty list in between.
 */
@Composable
private fun HoldReturnUntil(ready: Boolean) {
    val hold = LocalReturnHold.current ?: return
    DisposableEffect(hold, ready) {
        if (ready) {
            onDispose { }
        } else {
            hold.take()
            onDispose { hold.release() }
        }
    }
}

/**
 * When each live chat's page was last left while the app went on — a record opened over it, its own
 * history — for the page that comes back to it ([sittingOnReturn]). Process-wide, as [ChatLocks] is: that
 * page is a new composition with the old one's saved state, which the host keeps while a record stands
 * over the chat and the chat's own pages while its history does ([ChatPages]).
 */
private object ChatAway {
    private val left = ConcurrentHashMap<String, Long>()

    fun leave(key: String, at: Long) {
        left[key] = at
    }

    fun back(key: String): Long? = left.remove(key)
}

/** Where the live chat hands control back to its pages. */
private class LivePages(
    val onBack: () -> Unit,
    val onHistory: () -> Unit,
    val onStarted: (String) -> Unit,
    val onDeleted: () -> Unit,
    /** The centre flash's words, and whether a screen reader is told them. */
    val onFlash: (String, Boolean) -> Unit,
    /** What a write that failed says, on the card that stays; null once a later one is kept, which takes it down. */
    val onFailure: (String?) -> Unit,
    val onResume: (String) -> Unit,
)

/**
 * The chat being had: a new one, or [chatKey] carried on once its row has been read back. [chatKey]
 * is read as the chat opens: a new chat's first write ([LivePages.onStarted]) does not open it again.
 */
@Composable
private fun SymptomLiveChat(
    profile: UserProfile,
    chatKey: String?,
    focusAt: Long?,
    day: String?,
    pages: LivePages,
    hosts: ChatHosts,
) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val opened = remember { chatKey }
    val loaded by produceState<Pair<Boolean, ChatRow?>>(false to null, opened) {
        value = true to opened?.let { db.dao().chat(it) }
    }
    val (ready, row) = loaded
    HoldReturnUntil(ready)
    if (!ready) {
        Column(Modifier.fillMaxSize()) { PwnTopBar(stringResource(R.string.symptom_chat_title), pages.onBack) }
        return
    }
    SymptomConversation(db, profile, row, ChatOpening(day, focusAt), pages, hosts)
}

/** How a live chat was opened: the day a new one is filed under, the thread a row of the day list names. */
private class ChatOpening(val day: String?, val focusAt: Long?)

/** The language the chat speaks, as its words are read: they are read in English and Russian always. */
private fun chatLanguage(locale: Locale): ChatLanguage = when (locale.language) {
    "en" -> ChatLanguage.EN
    "ru" -> ChatLanguage.RU
    else -> ChatLanguage.OTHER
}

/**
 * Everything chat [key] knows besides its words: loaded as it opens, after every act that filed, and on
 * coming back.
 */
private suspend fun StepsDb.chatEnv(key: String, day: LocalDate, past: Boolean, now: Long): ChatEnv {
    val dao = dao()
    val through = chatEnvThrough(day, past, now, ZoneId.systemDefault()).toString()
    return ChatEnv(
        recentSymptoms = dao.journalOfKindBetween(KIND_SYMPTOM, day.minusDays(EARLIER_CHAT_DAYS).toString(), through)
            .first(),
        painMonth = dao.journalOfKindBetween(KIND_PAIN, day.minusDays(PAIN_MONTH_DAYS).toString(), through).first(),
        lastMood = if (past) null else dao.moodNear(now),
        courses = dao.allMedications(),
        // A dose of yesterday asks "more than it says?" beside the doses of the 24 hours round it, which reach
        // the day before; a dose in a chat of an earlier day, the day after ([ChatEnv.dayJournal]).
        dayJournal = (-2L..1L).flatMap { dao.journalOnNow(day.plusDays(it).toString()) },
        recentChats = dao.chatsSince(now - RECENT_CHAT_DAYS * 24 * 60 * 60 * 1_000L)
            .map { RecentChat(it.payload, it.startedAt, current = it.key == key) },
    )
}

/**
 * The last day a chat's surroundings read the symptoms and pains of: a chat of an earlier day, its own;
 * a live one, the day of [now] when that is later — its records are filed under the day they are made,
 * and a chat carried on past midnight saw none of the new day's.
 */
internal fun chatEnvThrough(day: LocalDate, past: Boolean, now: Long, zone: ZoneId): LocalDate =
    if (past) day else maxOf(day, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())

/** The journal's snapshot a symptom thread takes with it ([ChatSetting.snapshot]): [chatDay] and the day before. */
private suspend fun StepsDb.chatSnapshot(chatDay: LocalDate, profile: UserProfile): SymptomContext {
    val rows = dao().journalBetween(chatDay.minusDays(1).toString(), chatDay.toString()).first()
    val steps = dao().day(chatDay.toString()).first()?.steps
    return withContext(Dispatchers.Default) {
        symptomContext(rows, chatDay, LocalDate.now(), System.currentTimeMillis(), steps, profile)
    }
}

/** Another chat begun today and left before its end, which the opening offers to carry on. */
private suspend fun StepsDb.resumableChat(today: LocalDate, zone: ZoneId, except: String): String? {
    val since = today.atStartOfDay(zone).toInstant().toEpochMilli()
    return dao().chatsSince(since).firstOrNull { row ->
        val log = decodeChatLog(row.payload)
        row.key != except && log != null && log.threads.isNotEmpty() && continuesLive(row, log, today, zone)
    }?.key
}

/** A card's record as it stands now: the row or course its link of chat [chatKey] names, and how the link resolved. */
private suspend fun StepsDb.cardRecord(link: FiledLink, chatKey: String): CardRecord {
    val resolved = resolveLink(link, chatKey)
    val found = when (resolved) {
        is Resolved.Found -> resolved.record
        is Resolved.Changed -> resolved.record
        Resolved.Gone -> null
    }
    return CardRecord(link, (found as? FoundRecord.Row)?.entry, (found as? FoundRecord.Course)?.course, resolved)
}

/** The journal's snapshot kept across a rotation as the payload part it is saved as. */
private val CONTEXT_SAVER: Saver<SymptomContext?, String> = Saver(
    save = { context -> context?.let { encodeSymptomPayload(SymptomReport(context = it)) }.orEmpty() },
    restore = { saved -> decodeSymptomPayload(saved).context },
)

/**
 * The live chat. Every change of the log is committed through the router ([settledLog]) and kept by
 * the filer in one act with the records it files ([ChatFiling]) — a topic chosen, an answer, a
 * message, an answer taken back, the end, the note — so there is no Save, and back, Home, a rotation
 * or the process being killed lose nothing. On every open it files once what a killed process never
 * committed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymptomConversation(
    db: StepsDb,
    profile: UserProfile,
    row: ChatRow?,
    opening: ChatOpening,
    pages: LivePages,
    hosts: ChatHosts,
) {
    val day = opening.day
    val focusAt = opening.focusAt
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // Written as the page leaves too, and not cut off inside a transaction when it has gone.
    val scope = rememberAutoTakeScope()
    // A read that only leads to another page goes with this one: found after the chat was left, the
    // record would open over wherever the person had gone.
    val pageScope = rememberCoroutineScope()
    val today = rememberToday()
    // Read again as the zone changes ([rememberZone]), as the threads read theirs each time
    // ([MedicineClock.zone]): a page kept across a flight would file its records under the day of the zone
    // it was opened in, and the threads would reckon them in the zone landed in.
    val zone = rememberZone()
    val chatKey = rememberSaveable { row?.key ?: UUID.randomUUID().toString() }
    val chatDayText = rememberSaveable { row?.day ?: day ?: today.toString() }
    val chatDay = remember(chatDayText) { chatDayText.toLocalDateOrNull() ?: today }
    var log by rememberSaveable(stateSaver = CHAT_LOG_SAVER) {
        mutableStateOf(row?.payload?.let(::decodeChatLog) ?: ChatLog(past = chatDay != today))
    }
    var sittingFrom by rememberSaveable { mutableIntStateOf(log.threads.size) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The questions ticked and not yet taken, a count of the touches for the clock, and the ones the
    // pencil reopened, which hold an answer the person gave and so are taken again unless touched.
    var touched by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var reopened by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var touches by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var flashed by rememberSaveable { mutableStateOf(flashedAtOpen(log)) }
    var noteDraft by rememberSaveable { mutableStateOf(log.note) }
    // What the person changed on the record cards before Add, by card ([journalDraftKey]); the cards
    // being saved now, which a second tap must not write again.
    var journalDrafts by rememberSaveable(stateSaver = JOURNAL_DRAFTS_SAVER) {
        mutableStateOf(emptyMap<String, JournalDraft>())
    }
    var cardsAdding by remember { mutableStateOf(emptySet<String>()) }
    var menuOpen by rememberSaveable { mutableStateOf<Int?>(null) }
    var stored by rememberSaveable { mutableStateOf(row != null) }
    var startedAt by rememberSaveable { mutableStateOf(row?.startedAt) }
    var busy by remember { mutableIntStateOf(0) }
    var envVersion by remember { mutableIntStateOf(0) }
    var snapshot by rememberSaveable(stateSaver = CONTEXT_SAVER) { mutableStateOf<SymptomContext?>(null) }
    var snapshotTaken by rememberSaveable { mutableStateOf(false) }

    // The chat's surroundings as they are now, the reminders' switches among them.
    suspend fun readEnv(past: Boolean): ChatEnv =
        db.chatEnv(chatKey, chatDay, past, System.currentTimeMillis()).copy(gates = readReminderGates(context, db))
    val env by produceState<ChatEnv?>(null, chatDay, envVersion) { value = readEnv(log.past) }
    val resumeKey by produceState<String?>(null) { value = db.resumableChat(today, zone, except = chatKey) }
    val filing = remember(chatKey) { ChatFiling(RoomChatStore(db), chatKey) }
    val touchExploration = rememberTouchExploration()
    // The chat's model: reads what is sent once, as it is sent, off the main
    // thread and within a second. Its reads on their way are the process's ([ChatReadQueue]), so a
    // rotation neither loses one nor keeps it twice: [reading] while one the turn waits for is on its
    // way, the dots standing; [readFrom], when the words it was kept for were sent. What waits for a
    // reading is the chat's own ([ChatLog.held]), so every page of it — and its row — knows.
    val model = rememberChatModel()
    val reads = ChatReadQueue.process
    val allPending by reads.pending.collectAsStateWithLifecycle()
    val pendingReads = allPending[chatKey].orEmpty()
    val reading = pendingReads.any { it.holds }
    var readFrom by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(chatDay) {
        if (!snapshotTaken) {
            snapshot = db.chatSnapshot(chatDay, profile)
            snapshotTaken = true
        }
    }

    val setting = ChatSetting(
        female = profile.sex == "FEMALE",
        adult = ageOn(today.toString(), profile.birthDate) >= ADULT_AGE,
        day = chatDay,
        past = log.past,
        now = now,
        chatMoment = if (log.past) chatDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else now,
        snapshot = snapshot,
        env = env ?: ChatEnv(),
        zone = zone,
        sittingFrom = sittingFrom,
        language = chatLanguage(locale),
        resumeKey = resumeKey,
        journal = journalTypesOffered(profile, today),
    )
    val ready = env != null && snapshotTaken
    HoldReturnUntil(ready)
    val told = remember(log) { log.told() }
    // The live turn as it was put up — the log it answers and whether it arrived new — and how much of
    // the chat the bot has answered on screen; a rotation, a return or a reopening shows the rest at rest.
    var shown by remember { mutableStateOf<ShownTurn?>(null) }
    var shownCount by rememberSaveable { mutableIntStateOf(if (row != null) said(log) else -1) }
    // The list is brought to its end after the person's own act even when they had scrolled up.
    var revealOwed by remember { mutableStateOf(false) }

    fun filingContext(sent: ChatLog) = FilingContext(
        FilingClock(System.currentTimeMillis(), LocalDate.now(zone), zone),
        chatDay,
        sent.past,
        chatKey,
        sent,
        setting.env,
        resources::getString,
    )

    fun flashFirsts(result: FilingResult) {
        result.firsts.forEach { (threadId, link) ->
            val thread = log.threads.firstOrNull { it.id == threadId } ?: return@forEach
            val name = "$threadId/${link.role}"
            if (thread.topic?.script?.flashAt != FlashAt.FILE || name in flashed) return@forEach
            flashed = flashed + name
            if (recordFlashAllowed(log.told(), setting, shown?.turn, thread, link)) {
                pages.onFlash(
                    thread.topic?.script?.flashText(resources, link, FlashEvent.FILED)
                        ?: resources.getString(R.string.quick_log_added_named, link.title),
                    false,
                )
            }
        }
    }

    // A record answered again after a rewind is rewritten where it stands, and the flash says "Saved".
    fun flashRewrites(sent: ChatLog, result: FilingResult) {
        val script = sent.threads.lastOrNull()?.topic?.script ?: return
        rewriteFlashes(log.told(), setting, shown?.turn, sent, result).forEach { link ->
            val text = script.flashText(resources, link, FlashEvent.REWRITTEN)
            pages.onFlash(text ?: resources.getString(R.string.work_flash_saved), false)
        }
    }

    // [then], once the filer's result is taken — the database's newer copy among it.
    fun persist(sent: ChatLog, then: (() -> Unit)? = null) {
        if (sent.threads.isEmpty() && sent.attachments.isEmpty() && !stored) return
        busy++
        scope.launch {
            val outcome = runCatchingCancellable { filing.persist(sent, filingContext(sent)) }
            busy--
            // A write that failed is said, never passed over in silence ([filingFailed]).
            pages.onFailure(resources.getString(R.string.quick_log_not_saved).takeIf { filingFailed(outcome) })
            val result = outcome.getOrNull() ?: return@launch
            log = result.newer?.let { log.adopting(it) } ?: log.withLinks(result)
            stored = sent.threads.isNotEmpty() || sent.attachments.isNotEmpty()
            if (startedAt == null) startedAt = System.currentTimeMillis()
            pages.onStarted(chatKey)
            flashFirsts(result)
            if (result.newer == null) flashRewrites(sent, result)
            envVersion++
            then?.invoke()
        }
    }

    // [quiet]: a reading joining after its turn, which the person did nothing for — the list stays where
    // it is, and the moment the turns are counted from with it.
    fun commit(next: ChatLog, quiet: Boolean = false) {
        if (!quiet) now = System.currentTimeMillis()
        val settled = settledLog(next, setting.copy(now = now))
        log = settled
        if (!quiet) revealOwed = true
        persist(settled)
    }

    // A file picked while the page was gone — the process killed under the picker — comes back as the
    // picker is registered again, before the chat's surroundings are read: kept in the log as it is,
    // nothing settled or filed against an empty [ChatEnv], and written with the open's own write.
    val attachmentActions = rememberAttachmentActions(log.attachments) { next ->
        if (ready) commit(log.copy(attachments = next)) else log = log.copy(attachments = next)
    }

    /**
     * [sent], written where [context] says, read by the model in the process's queue — the dots
     * standing while the turn [holds] for it — and kept by whichever page shows this chat when it is
     * done ([keepRead]). Null, nothing read, with no model to read it.
     */
    fun startRead(sent: SentWords, context: ChatReaderContext, holds: Boolean): PendingRead? {
        val reader = model.reader ?: return null
        val routes = model.routes
        val past = log.past
        return reads.start(chatKey, sent, holds, System.currentTimeMillis()) {
            reader.decide(sent.text, context, chatDay, past, routes)
        }
    }

    /**
     * What a read of this chat came to, kept in the log as it stands now ([readDone]): a message's
     * reading where it stands — its thread routed now, with its reading or without one, if the turn
     * waited for it — words typed taken, the note's reading under it. A reading the turn did
     * not wait for joins quietly.
     */
    fun keepRead(pending: PendingRead, read: ChatRead?) {
        if (pending.holds) readFrom = pending.since
        log.readDone(pending.sent, read, setting)?.let { commit(it, quiet = !pending.holds) }
    }

    // How words sent now are read ([readPlan]): the turn waits for the reading with a model whose
    // readings are used, unless the rules find in them what never waits — where help is, the line to
    // call, a question the thread's words bring — which are read all the same, after, as
    // everything is in the shadow.
    fun planFor(text: String, typed: Boolean = false): ReadPlan =
        readPlan(text, log, setting, model.reader != null, model.routes, typed)

    // Where the chat stands for the reader, worked out only when there is a model to read with.
    fun contextNow(): ChatReaderContext? = model.reader?.let { log.readerContext(setting) }

    // A message is kept at once and its reading after it, on the message itself: the transcript has
    // the person's words while the model reads them, and the bot's turn — the thread's route among
    // it — waits for both, the chat holding the message for its reading ([ChatHeld.Message]).
    fun send(text: String) {
        log.typedChoiceLast(text, setting)?.let {
            commit(it)
            return
        }
        val context = contextNow()
        val written = log.wrote(text)
        val place = written.lastMessagePlace()
        if (written == log || place == null) return
        val holds = context != null && planFor(text) == ReadPlan.HOLD
        val read = context?.let { startRead(SentWords.Message(text, place), it, holds) }
        commit(if (read?.holds == true) written.copy(held = ChatHeld.Message(place)) else written)
    }

    // Words typed as an answer are read before they are taken, the chat holding them meanwhile
    // ([ChatHeld.Typed]): a flag the reading raised makes them a message, whose question comes first
    // ([typedLast]). Words the rules find urgent are taken at once and, made a message, read after it.
    fun typed(step: SymptomStep, text: String) {
        val context = contextNow()
        if (context != null && planFor(text, typed = true) == ReadPlan.HOLD) {
            startRead(SentWords.Typed(text, step), context, holds = true)?.let {
                commit(log.copy(held = ChatHeld.Typed(step, text)))
                return
            }
        }
        val before = log.messageCount()
        val next = log.typedLast(step, text, setting)
        commit(next)
        val place = next.lastMessagePlace()?.takeIf { next.messageCount() > before } ?: return
        context?.let { startRead(SentWords.Message(text, place), it, holds = false) }
    }

    // The note is kept as it is taken, and read after: a flag of its reading puts where help is under it.
    fun noted(text: String) {
        commit(log.noted(text))
        startRead(SentWords.Note(text), ChatReaderContext(ChatReaderWhere.NOTE), holds = false)
    }

    // The reads of this chat on their way, kept here once done — or once past their second, as none —
    // the oldest first, whichever page of the chat stands then, the rotated one included; kept through
    // the page as it is when each comes back.
    val keepLatest by rememberUpdatedState<(PendingRead, ChatRead?) -> Unit> { pending, read ->
        keepRead(pending, read)
    }
    LaunchedEffect(pendingReads.firstOrNull(), ready) {
        val next = pendingReads.firstOrNull() ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val read = reads.resultOf(next)
        if (reads.take(chatKey, next)) keepLatest(next, read)
    }

    // On every open — a chat carried on, a rotation, the process back from death — the links the
    // database holds are taken, and what a killed process never filed is filed: a file picked before
    // the page was ready too, in a chat of files alone as well.
    LaunchedEffect(ready) { if (ready && (log.threads.isNotEmpty() || log.attachments.isNotEmpty())) persist(log) }
    // What the chat holds for a reading no read on its way will bring — the process that read it died,
    // a page gone for good kept it in the chat as stored: the database's copy taken first, and what is
    // still held then let go as the rules read it — the message routed, the words typed taken.
    val heldAlone = ready && log.held != null && pendingReads.none { log.holdsFor(it) }
    LaunchedEffect(heldAlone) {
        if (!heldAlone) return@LaunchedEffect
        persist(log) {
            val waiting = reads.pending.value[chatKey].orEmpty()
            if (waiting.none { log.holdsFor(it) }) log.released(setting)?.let { commit(it) }
        }
    }

    // The painting not yet sent goes with the chat as the page is left — back, another page — as it
    // does as the app stops; never on a rotation, whose saved state brings it back as it was. Called
    // through its latest self, so the write carries what the page knows now. Not before the page is
    // ready: nothing is painted until then, and that write would file against an empty [ChatEnv].
    val activity = LocalActivity.current
    val keepDraft by rememberUpdatedState { if (ready && log.threads.lastOrNull()?.draft != null) persist(log) }
    // The reads of this chat still on their way as the page goes for good, not for a rotation — the
    // note read as it leaves among them, taken once every leaving has had its say: each kept, once it
    // is done and no page of the chat stands, in the chat as the database holds it ([ChatFiling.update]),
    // never in this page's copy — a page brought back from its saved state takes them itself.
    //
    // A page left before it was ready — a rotation, then back at once — knows an empty [ChatEnv] and no
    // snapshot: a reading settled and filed against them is routed with no course and no earlier record,
    // and the act takes back what the thread then seems not to want. Its surroundings are read here
    // first; waiting for [ready] instead would lose a reading the turn did not hold for, the page gone.
    val keepReads by rememberUpdatedState {
        val at = setting
        val loaded = ready
        scope.launch {
            val waiting = reads.pending.value[chatKey].orEmpty()
            if (waiting.isEmpty()) return@launch
            // Not read — the database failing — nothing is taken: the reads wait for the chat's next page.
            val around = if (loaded) {
                at
            } else {
                runCatchingCancellable {
                    at.copy(env = readEnv(at.past), snapshot = at.snapshot ?: db.chatSnapshot(chatDay, profile))
                }.getOrNull() ?: return@launch
            }
            waiting.forEach { pending ->
                val read = reads.resultOf(pending)
                if (!reads.takeAlone(chatKey, pending)) return@forEach
                val settle = around.copy(now = System.currentTimeMillis())
                runCatchingCancellable {
                    filing.update(
                        { stored -> stored.readDone(pending.sent, read, settle)?.let { settledLog(it, settle) } },
                        { sent -> filingContext(sent).copy(env = settle.env) },
                    )
                }
            }
        }
    }
    // A chat that is over and left keeps no earlier state of a record it changed: nothing can put it
    // back once the page is gone (the privacy policy says so).
    // One that failed is said, and sealed at the next leaving of the chat. One that went through clears
    // nothing: the flash standing then is the last write's of the page, which this seal did not redo.
    val sealIfOver by rememberUpdatedState {
        if (log.end != null) {
            scope.launch {
                val outcome = runCatchingCancellable { filing.seal() }
                if (outcome.isFailure) pages.onFailure(resources.getString(R.string.quick_log_not_saved))
            }
        }
    }
    // Back after a quarter of an hour or more — the app stopped, the process killed meanwhile, a record
    // or the chat's history opened over the page — is a new sitting, whose chest is asked about again.
    // When the app stopped, or the page gave way to its history, is kept with the page's state, so the
    // process back from death knows it too.
    var awayAt by rememberSaveable { mutableStateOf<Long?>(null) }
    fun cameBack(since: Long?) {
        sittingFrom = sittingOnReturn(sittingFrom, log.threads.size, since, System.currentTimeMillis())
    }
    LaunchedEffect(Unit) { cameBack(ChatAway.back(chatKey)) }
    // A page of the chat stands while this one does: a page gone for good takes no read of it meanwhile.
    DisposableEffect(Unit) {
        reads.opened(chatKey)
        onDispose {
            reads.closed(chatKey)
            if (activity?.isChangingConfigurations != true) {
                ChatAway.leave(chatKey, System.currentTimeMillis())
                keepDraft()
                keepReads()
                sealIfOver()
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // The registry plays ON_START to an observer as it is added, while the page's surroundings are
        // being read for its open already: they are read again only for a start after a stop, or every
        // open would read them twice.
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The painting not yet sent is kept with the chat, so it is there when the page is back.
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    awayAt = System.currentTimeMillis()
                    keepDraft()
                }

                Lifecycle.Event.ON_START -> {
                    cameBack(awayAt)
                    awayAt = null
                    if (stopped) envVersion++
                }

                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The live turn: for a turn the screen has not put up yet, the dots while it is worked out and held,
    // then its words, which arrive; any change that adds no turn shows the script's turn at rest at once.
    val animated = remember(context) { context.animationsOn() }
    // The turn whose dots are up, by its count — saved, so one caught mid-hold by a rotation or the
    // process dying comes back at rest, never held again — and when they end, fixed once in this
    // composition, so the snapshot and the records reloading under them never start them over.
    var holding by rememberSaveable { mutableIntStateOf(-1) }
    var holdEnds by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // The sitting is among the keys: the turn is the script's for the sitting, and one put up before the
    // page was left would stand on when it came back to a new one — the chest not asked about again until
    // something was sent.
    LaunchedEffect(told, ready, setting.env, setting.snapshot, setting.sittingFrom, reading) {
        // Nothing is worked out while a message is read: the turn answers the message with its reading.
        if (!ready || reading) return@LaunchedEffect
        val count = said(told)
        val fresh = count > shownCount && !caughtMidHold(holding, holdEnds?.first, count)
        // The dots stood through the reading already: the hold counts from the message.
        val started = readFrom ?: System.currentTimeMillis()
        readFrom = null
        val turn = liveTurn(told, setting)
        if (fresh) {
            val ends = holdEnds?.takeIf { it.first == count }?.second ?: run {
                val state = told.threads.lastIndex.takeIf { it >= 0 }?.let { told.threadState(it, setting) }
                val chars = resources.turnLength(turn, state, locale)
                started + liveHoldMs(chars, animated, turn.urgent(), touchExploration)
            }
            holdEnds = count to ends
            holding = count
            delay((ends - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        shown = ShownTurn(told, turn, fresh)
        shownCount = count
        // A symptom's close, once: the centre flash says its entry is kept — never over a line to call,
        // after an emergency or after any answer about suicide in the chat, where it would read as "done".
        told.threads.filter { it.ended && it.topic?.script?.flashAt == FlashAt.CLOSE }.forEach { thread ->
            val link = thread.filed.firstOrNull { it.role.isEmpty() && it.state == LinkState.FILED } ?: return@forEach
            val name = "${thread.id}/"
            if (name in flashed) return@forEach
            flashed = flashed + name
            if (recordFlashAllowed(told, setting, turn, thread, link)) {
                pages.onFlash(resources.getString(R.string.quick_log_added_named, link.title), false)
            }
        }
    }
    val items = remember(told, setting) { if (ready) chatTranscript(told, setting, live = true) else emptyList() }
    val due = ready && said(told) > shownCount && !caughtMidHold(holding, holdEnds?.first, said(told))
    val heldWords = (told.held as? ChatHeld.Typed)?.text
    val live = liveShown(items, shown, told, due, touchExploration, reading = reading, typed = heldWords)
    val liveReply = live.turn?.reply

    val links = remember(told) { told.threads.flatMap { thread -> thread.filed.map { thread.id to it } } }
    val records by produceState(emptyMap<Pair<Int, String>, CardRecord>(), links, envVersion) {
        value = links.associate { (thread, link) -> (thread to link.role) to db.cardRecord(link, chatKey) }
    }

    fun undoOrSave(thread: Int, role: String, again: Boolean) {
        busy++
        scope.launch {
            val ctx = filingContext(log)
            val outcome = runCatchingCancellable {
                if (again) filing.saveAgain(thread, role, ctx) else filing.undo(thread, role, ctx)
            }
            busy--
            // An act that threw changed nothing and says so. An Undo has its own words for it: "Not
            // saved" is what the card of a record Undo took back says.
            val failed = if (again) R.string.quick_log_not_saved else R.string.symptom_chat_record_not_undone
            pages.onFailure(resources.getString(failed).takeIf { outcome.isFailure })
            val result = outcome.getOrNull() ?: return@launch
            log = log.withLinks(result)
            envVersion++
            val link = result.links[thread]?.firstOrNull { it.role == role } ?: return@launch
            // Not standing now, the record is said when an answer changed files it anew ([flashedAtOpen]);
            // saved again, it is said here, and not a second time by its thread's close.
            val name = "$thread/$role"
            flashed = if (again && link.state == LinkState.FILED) flashed + name else flashed - name
            val own = log.threads.firstOrNull { it.id == thread } ?: return@launch
            val script = own.topic?.script
            val event = if (again) FlashEvent.SAVED_AGAIN else FlashEvent.UNDONE
            val core = if (again) {
                resources.getString(R.string.quick_log_added_named, link.title)
            } else {
                resources.getString(R.string.work_flash_deleted)
            }
            if (cardFlashAllowed(log.told(), setting, shown?.turn, own, link, again)) {
                pages.onFlash(script?.flashText(resources, link, event) ?: core, true)
            }
        }
    }

    // A record card's form, filled in, with the day the card shows and none of the message's time words
    // left to count it again; marked opened, as nothing tells the chat whether it was saved there.
    fun openCardForm(card: BotPiece.Journal, draft: JournalDraft) {
        val open = hosts.onJournal ?: return
        val base = cardBaseDay(log.past, chatDay, today)
        val day = journalDay(card.event, draft, base, today)
        val event = if (day == null) {
            card.event.copy(sourceDay = base.toString())
        } else {
            card.event.copy(
                sourceDay = day.toString(),
                arguments = card.event.arguments.filterNot {
                    it.role == "TIME"
                },
            )
        }
        if (log.markOf(card) == null) commit(log.marked(card.thread, card.key, CardMark.Opened), quiet = true)
        open(event)
    }

    // Add: water, a cup of the drink the words named, or a meal of a catalogue food picked, saved from
    // the card once — at the day's own moment ([dayInstant]), kept apart from any row of its kind there,
    // in one act with the chat's mark for it ([ChatFiling.addCardRow]) — flashed, and on to Health
    // Connect; any other record's form opened. Saved, the mark is committed on the page's own chat as
    // well, which may have moved on while the act ran; not saved, it says so and the card stays.
    fun addCard(card: BotPiece.Journal, draft: JournalDraft) {
        val key = journalDraftKey(card)
        if (log.markOf(card) != null || key in cardsAdding) return
        val base = cardBaseDay(log.past, chatDay, today)
        val day = journalDay(card.event, draft, base, today) ?: base
        val row = cardRow(
            card.event,
            draft,
            day,
            water = { millilitres, onDay -> waterEntry(resources, millilitres, onDay) },
            cup = { drink, millilitres, onDay ->
                cupEntry(resources.getString(drink.titleRes), drink, millilitres, onDay, occurredAt = 0L)
            },
        )
        if (row == null) {
            openCardForm(card, draft)
            return
        }
        val amount = card.event.prefillDrink()?.let { card.event.millilitres(draft)?.toDouble() } ?: row.servingG
        cardsAdding = cardsAdding + key
        busy++
        val tapped = log
        val settle = setting.copy(now = now)
        scope.launch {
            var added: CardMark.Added? = null
            val outcome = runCatchingCancellable {
                filing.addCardRow(
                    row.copy(occurredAt = dayInstant(row.day)),
                    backDated = day != today,
                    marked = { id, at ->
                        // A black cup's serving is its milligrams; the card keeps the millilitres it showed.
                        val saved = CardMark.Added(row.kind, id, at, row.title, amount).also { added = it }
                        settledLog(tapped.marked(card.thread, card.key, saved), settle)
                    },
                    ctx = { sent -> filingContext(sent) },
                )
            }
            busy--
            cardsAdding = cardsAdding - key
            pages.onFailure(resources.getString(R.string.quick_log_not_saved).takeIf { filingFailed(outcome) })
            val result = outcome.getOrNull() ?: return@launch
            val mark = added?.takeIf { result.newer == null }
            if (mark == null) {
                result.newer?.let { log = log.adopting(it) }
                return@launch
            }
            commit(log.marked(card.thread, card.key, mark), quiet = true)
            journalDrafts = journalDrafts - key
            if (recordFlashAllowed(log.told(), setting, shown?.turn)) {
                pages.onFlash(resources.getString(R.string.quick_log_added_named, row.title), true)
            }
            writeLocalHealthIfAvailable(context, db)
        }
    }

    val journalActions = hosts.onJournal?.let {
        JournalActions(
            draftOf = { card -> journalDrafts[journalDraftKey(card)] ?: JournalDraft() },
            onDraft = { card, draft -> journalDrafts = journalDrafts + (journalDraftKey(card) to draft) },
            onAdd = ::addCard,
            onDecline = { card -> commit(log.marked(card.thread, card.key, CardMark.Declined), quiet = true) },
            onReopen = { card -> openCardForm(card, journalDrafts[journalDraftKey(card)] ?: JournalDraft()) },
        )
    }

    val inputs = TurnInputs(
        log = told,
        setting = setting,
        imperial = profile.imperial,
        today = today,
        record = { thread, role -> records[thread to role] },
        open = hosts.onOpenRecord,
        cards = CardActions(
            onUndo = { thread, role -> undoOrSave(thread, role, again = false) },
            onSaveAgain = { thread, role -> undoOrSave(thread, role, again = true) },
            canSaveAgain = { thread, role ->
                val own = log.threads.firstOrNull { it.id == thread }
                own != null && canSaveAgain(ScriptPlans, own, role, filingContext(log))
            },
        ),
        offers = ChatOffers(
            onBook = hosts.onBook?.let { book ->
                { threadId ->
                    val index =
                        told.threads.indexOfFirst { it.id == threadId }.takeIf { it >= 0 } ?: told.threads.lastIndex
                    val state = told.threadState(index, setting)
                    val summary = resources.summaryLine(told.threads[index], state.report, state.earlier, locale)
                    val date = painDayText("dMMM", locale, chatDay)
                    book(
                        VisitDraft(
                            Checkup.GP.name,
                            resources.getString(R.string.symptom_chat_visit_note, date, summary),
                        ),
                    )
                }
            },
            onBreathing = hosts.onBreathing,
            journal = journalActions,
        ),
        // Nothing on a card is done while the turn waits for a reading, as nothing is sent then.
        busy = busy > 0 || reading,
        opensCourses = hosts.opensCourses,
    )

    // Once per change of the chat or its surroundings, as the transcript is, not on every recomposition.
    val lastState = remember(told, setting) {
        told.threads.lastIndex.takeIf { it >= 0 }?.let { told.threadState(it, setting) }
    }

    // The body map of a pain thread whose area question is open: its own item of the list, its state
    // held here, where a message, the dots or a rotation never take it. It comes once its question
    // has been shown, and stays — folding away — until it has gone after Send.
    val areaThread = remember(told, setting, ready) { if (ready) areaQuestionThread(told, setting) else null }
    val areaReply = remember(areaThread, lastState) { areaThread?.let { lastState?.let(::painAreaReply) } }
    var mapFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var mapReply by remember { mutableStateOf<ChatReply?>(null) }
    LaunchedEffect(liveReply, live.dots, areaThread) {
        val asked = liveReply is ChatReply.AreaChips || liveReply is ChatReply.BodyMap
        if (!live.dots && areaThread != null && asked) mapFor = areaThread
    }
    LaunchedEffect(areaReply) { areaReply?.let { mapReply = it } }
    val map = rememberChatMapState(mapFor) {
        mapStart(log.threads.firstOrNull { it.id == mapFor }, lastState, areaReply ?: mapReply)
    }
    // An answer is taken once, by the thread whose question its reply was drawn for ([awaits]): one that
    // lands after the chat took it — a second tap before the page has drawn the first, "Turn on" again
    // while the reminders are being turned on — answers nothing.
    val askedThread = log.threads.lastOrNull()?.id
    fun answerOnce(step: SymptomStep, change: (SymptomReport) -> SymptomReport) {
        if (log.awaits(askedThread, step)) commit(log.answeredLast(setting, change = change))
    }
    val actions = ReplyActions(
        onTopic = { choice ->
            menuOpen = null
            commit(log.choseTopic(choice, setting))
        },
        onNarrow = { narrow ->
            menuOpen = null
            val next = log.narrowed(narrow, setting)
            if (next != log) commit(next)
        },
        onResume = pages.onResume,
        onEnd = { commit(log.endedByPerson()) },
        onExit = { thread -> commit(log.leftRoute(thread)) },
        onTap = { step, option -> answerOnce(step) { it.tapped(step, option, lastState?.earlier) } },
        onTick = { step, option ->
            if (log.awaits(askedThread, step)) {
                // A list carried from the same pain stands ticked until touched: the first touch starts from it.
                val preset = (liveReply as? ChatReply.Choose)?.takeIf { it.step == step }?.preset.orEmpty()
                val start = log.threads.lastOrNull()?.report?.answers?.get(step)
                val seeded = if (start == null && preset.isNotEmpty()) {
                    log.updateLast { it.copy(answers = it.answers + (step to preset)) }
                } else {
                    log
                }
                val (next, taken) = seeded.tickedLast(step, option, setting)
                if (taken) {
                    commit(next)
                } else {
                    log = next
                    touched = touched + step.name
                    touches++
                }
            }
        },
        onKeep = { step, options ->
            answerOnce(step) {
                it.copy(
                    answers = it.answers + (step to options),
                    values = if (step.kind.holds == StepHolds.OPTIONS) it.values - step else it.values,
                ).answered(step)
            }
        },
        onSeverity = { severity -> answerOnce(SymptomStep.SEVERITY) { it.rated(severity) } },
        onValue = { step, value ->
            if (step.kind.answer == AnswerKind.TAP) {
                answerOnce(step) { it.valued(step, value).answered(step) }
            } else if (log.awaits(askedThread, step)) {
                log = log.updateLast { it.valued(step, value) }
                touched = touched + step.name
                touches++
            }
        },
        // The map's painting, and the areas it records, wait for Send — or, for the head's chips with
        // the canvas folded, for the clock — and are kept with the chat as the page stops. Only while
        // the map's own question is asked: not from a map still folding away after its Send.
        onPaint = { step, strokes, added, removed ->
            log.mapPainted(mapFor, strokes, added, removed, setting)?.let { painted ->
                log = painted
                touched = touched + step.name
                touches++
            }
        },
        onSend = { _ -> log.mapSent(mapFor, setting)?.let { commit(it) } },
        // Words typed for a question no longer waiting are a message: never lost, never a second answer.
        onText = { step, text -> if (log.awaits(askedThread, step)) typed(step, text) else send(text) },
        onHeld = { held = it },
        // A record named by its moment alone — "Open that entry" — is found by this chat's link to it.
        onOpen = { ref ->
            if (ref.id != null) {
                hosts.onOpenRecord?.invoke(ref)
            } else {
                pageScope.launch { db.openedRef(ref, log, chatKey)?.let { hosts.onOpenRecord?.invoke(it) } }
            }
        },
    )

    // The question the clock waits on now, whatever the screen shows over it — the dots after a
    // message, a turn being worked out — so its ticks and its clock carry on under them.
    // The head's chips with the canvas folded are taken by the clock, as a question of several answers.
    val waiting = remember(told, ready, setting.sittingFrom) { if (ready) waitingStep(told, setting) else null }
        ?: SymptomStep.PAIN_AREA.takeIf { areaThread != null && mapTakenByClock(map, areaReply) }
    fun take(step: SymptomStep) {
        touched = touched - step.name
        reopened = reopened - step.name
        val next = log.takenLast(step, setting)
        if (next != log) commit(next)
    }

    // The rows already on screen: one at or past this place arrives when it is first composed, and
    // reports itself; a chat carried on, a rotation, a return or the process back from death shows
    // everything there at rest. A rewind lowers it, so the answer given again arrives again.
    var seen by rememberSaveable { mutableIntStateOf(if (row == null) 0 else UNSEEN) }
    val arrivedBelow = if (seen == UNSEEN) items.size else seen
    SideEffect { if (seen == UNSEEN && ready) seen = items.size }

    val pencils = remember(told) { pencilThreads(told) }
    val onEdit: (ChatItem.Person) -> Unit = { item ->
        items.indexOf(item).takeIf { it >= 0 }?.let { at -> seen = minOf(arrivedBelow, at) }
        if (item.end) {
            commit(log.unended())
        } else {
            val thread = log.threads.firstOrNull { it.id == item.thread }
            val turn = thread?.report?.chat?.turns?.getOrNull(item.position) as? ChatTurn.Asked
            if (turn != null) {
                if (turn.step.kind.answer == AnswerKind.SETTLE) reopened = reopened + turn.step.name
                touched = emptySet()
                commit(log.rewoundLog(item.thread, item.position))
            }
        }
    }

    val ended = told.end != null

    // Typing: the composer has the focus and the keyboard is up. The whole reply area — chips, the
    // map, a ruler — leaves the screen and a screen reader's tree until the keyboard goes down, when
    // the focus is given up and it comes back as it was; a clock armed under it waits meanwhile.
    var composerFocused by remember { mutableStateOf(false) }
    val imeUp = WindowInsets.isImeVisible
    val typingMessage = composerFocused && imeUp && !ended
    val focusManager = LocalFocusManager.current
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeWasUp && !imeUp && composerFocused) focusManager.clearFocus()
        imeWasUp = imeUp
    }
    // The reply and the map drawn in already, by their items' keys: drawn in again only once hidden by
    // typing, never when scrolled back into view or brought back by a rotation.
    var replySeen by rememberSaveable { mutableStateOf(if (row == null) null else UNSEEN_KEY) }
    var mapSeen by rememberSaveable { mutableStateOf(if (row == null) null else UNSEEN_KEY) }
    LaunchedEffect(typingMessage) {
        if (typingMessage) {
            replySeen = null
            mapSeen = null
        }
    }
    val reply = live.turn?.reply?.takeIf { !live.dots && !typingMessage && replyDrawn(it) }
    val replyItem = remember(told, setting, ready) { if (ready) replyKey(told, setting) else null }
    // A tall widget taken — the mood's dial — folds away as the sent map does: the reply last
    // drawn of a kind that folds stays in its item, closing, once the chat has moved past its question;
    // typing hides it at once, as it hides every reply.
    var folding by remember { mutableStateOf<Pair<String, ChatReply>?>(null) }
    LaunchedEffect(reply, replyItem, typingMessage) {
        when {
            typingMessage -> folding = null
            reply != null && replyItem != null -> folding = (replyItem to reply).takeIf { reply.foldsAway }
        }
    }
    val current = reply?.let { now -> replyItem?.let { it to now } }
    val leaving = folding?.takeIf { current == null && it.first != replyItem && !typingMessage }
    // The map, the list or the head's chips unfolded under the area chips: the person's own act, so what
    // unfolded is brought into view — as far as the question above it lets it.
    val unfolded = Triple(map.canvas, map.list, map.head)
    LaunchedEffect(unfolded) { if (unfolded.toList().any { it }) revealOwed = true }
    // Whether a model read something of this chat in a language its exam was passed in, once per log.
    val readExamined = remember(log) { log.readExamined() }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            onBack = pages.onBack,
            // The moment goes with the page's saved state: [ChatAway] does not outlive the process, and a
            // page brought back to an old sitting would not ask about the chest again.
            onHistory = {
                awayAt = System.currentTimeMillis()
                pages.onHistory()
            },
            onDelete = if (stored) {
                {
                    busy++
                    scope.launch {
                        // Not deleted, the chat stays as it was and Delete can be tapped again.
                        runCatchingCancellable { filing.deleteChat() }
                            .onSuccess { pages.onDeleted() }
                            .onFailure {
                                busy--
                                pages.onFailure(resources.getString(R.string.pain_not_deleted))
                            }
                    }
                }
            } else {
                null
            },
            busy = busy > 0,
            // In a model's words while one reads in full in the app's language, or read in this chat so.
            about = chatAboutRes(model.readsInFull(locale) || readExamined),
        )
        // That a model reads the messages, under the bar, for as long as one does.
        ChatModelLine(model.on)
        // Below the bar, everything stands on the keyboard: the window is told to fit it
        // ([KeyboardResizesChat]) and this column takes its height once — less the navigation bar the
        // panel already stands clear of — so the list's viewport shrinks and the composer sits on it.
        Column(Modifier.weight(1f).windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            val pinned = remember(told) { safetyPinned(told) }
            InlineReveal(pinned) { PinnedSafetyLine() }
            BoxWithConstraints(Modifier.weight(1f)) {
                // The map's height comes from the tallest the list has been at this width: the keyboard,
                // which shrinks the list, never shrinks the map under a finger.
                val tallest = remember(maxWidth) { mutableStateOf(maxHeight) }
                LaunchedEffect(maxHeight) { if (maxHeight > tallest.value) tallest.value = maxHeight }
                val mapHeight = chatMapHeight(tallest.value, maxWidth)
                if (!ready) return@BoxWithConstraints
                // Placed at once where it opens — the end, or the thread a row of the day list was told in —
                // and only then brought along by the changes.
                val opensAt = remember { liveFocusRow(told, items, focusAt) }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = opensAt)
                RegisterPanelScroll(listState)
                ChatScrolling(
                    listState = listState,
                    changes = ScrollChanges(
                        items.size,
                        live,
                        reply != null,
                        mapFor,
                        typingMessage,
                        unfolded,
                        log.attachments,
                        attachmentActions.editingUri,
                    ),
                    animated = animated,
                    atEnd = opensAt == items.size,
                    // Never while a finger is on the map, a ruler or the dial: the list would move under it.
                    held = { held || map.drawing.touching },
                    ownAct = { revealOwed.also { revealOwed = false } },
                    // A newly attached file opens its form below the replies; bring that into view too.
                    anchor = {
                        if (attachmentActions.editingUri != null) {
                            listState.layoutInfo.totalItemsCount - 1
                        } else {
                            items.size
                        }
                    },
                )
                ChatList(
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                    stamp = chatStamp(startedAt.takeIf { stored }, chatDay, zone, locale),
                ) {
                    itemsIndexed(items, key = { index, _ -> index }) { index, item ->
                        val row = Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = if (animated) PwnMotion.spatial() else null,
                            fadeOutSpec = if (animated) PwnMotion.effects() else null,
                        )
                        val arrives = animated && index >= arrivedBelow
                        if (index == items.lastIndex) {
                            LiveRow(live, lastState, inputs, animated, arrives, row)
                            SideEffect { if (!live.dots && index >= seen && seen != UNSEEN) seen = index + 1 }
                        } else {
                            // No answer is taken back while the turn waits for a reading of what was just sent.
                            val editable = !reading &&
                                item is ChatItem.Person && (item.end || (item.step != null && item.thread in pencils))
                            ChatItemView(
                                item,
                                inputs,
                                onEdit = onEdit.takeIf { editable },
                                modifier = row,
                                arrives = arrives,
                            )
                            SideEffect { if (index >= seen && seen != UNSEEN) seen = index + 1 }
                        }
                    }
                    (current ?: leaving)?.let { (key, drawn) ->
                        item(key = key) {
                            // Only the topics' reply has the menu to unfold: no other works it out.
                            val menu = if (drawn is ChatReply.Topics) chatMenu(told, setting) else emptyList()
                            val body: @Composable () -> Unit = {
                                ReplyReveal(arrive = animated && replySeen != UNSEEN_KEY && key != replySeen) {
                                    ChatReplyView(
                                        drawn,
                                        ReplyInputs(
                                            thread = log.threads.lastOrNull()?.id ?: -1,
                                            report = log.threads.lastOrNull()?.report ?: SymptomReport(),
                                            menu = menu,
                                            menuOpen = menuOpen == told.threads.size,
                                            onMenuOpen = { menuOpen = told.threads.size },
                                            map = map.takeIf { mapFor != null },
                                        ),
                                        actions,
                                    )
                                }
                            }
                            if (drawn.foldsAway) {
                                FoldingReply(
                                    visible = current != null,
                                    animated = animated,
                                    onGone = { if (folding?.first == key) folding = null },
                                    content = body,
                                )
                            } else {
                                body()
                            }
                            SideEffect { replySeen = key }
                        }
                    }
                    mapFor?.takeUnless { typingMessage }?.let { thread ->
                        val key = "map:$thread"
                        item(key = key) {
                            PainMapItem(
                                visible = areaThread == thread,
                                arrive = animated && mapSeen != UNSEEN_KEY && key != mapSeen,
                                animated = animated,
                                onGone = { if (mapFor == thread && areaThread != thread) mapFor = null },
                            ) {
                                (areaReply ?: mapReply)?.let { ChatPainMap(map, it, profile.sex, mapHeight, actions) }
                            }
                            SideEffect { mapSeen = key }
                        }
                    }
                    if (ended && live.turn != null && !live.dots) {
                        item(key = "note") {
                            ChatNote(noteDraft, log.noteCards(noteDraft), onNote = { noteDraft = it })
                            AutoTakeClock(
                                armed = noteDraft != log.note,
                                restart = noteDraft,
                                takeOnLeave = true,
                                onTaken = { noted(noteDraft) },
                            )
                        }
                    }
                    if (ended || log.attachments.isNotBlank()) {
                        item(key = "attachments") {
                            AttachmentsField(
                                value = log.attachments,
                                onImportLabs = hosts.onLabDocument,
                                showAddButton = ended,
                                actions = attachmentActions,
                                onChange = { commit(log.copy(attachments = it)) },
                            )
                        }
                    }
                }
            }
            // The clock of the question waiting, outside the list: an item scrolled away, the dots after a
            // message or a turn being worked out leave it running, and only the page leaving — or the app —
            // takes what is ticked. It draws at the list's foot, under the chips the list keeps in view, and
            // waits while typing hides them: nothing is taken that the person cannot see.
            AutoTakeClock(
                armed = waiting != null &&
                    autoTakeArmed(
                        AnswerKind.SETTLE,
                        holds = log.threads.lastOrNull()?.report?.holdsAnswer(waiting) == true,
                        touched = waiting.name in touched,
                        answered = waiting.name in reopened,
                    ),
                restart = waiting to touches,
                onTaken = { waiting?.let { take(it) } },
                modifier = Modifier.padding(horizontal = 16.dp),
                held = held || typingMessage,
                takeOnLeave = true,
            )
            InlineReveal(!ended) {
                val ask = liveReply as? ChatReply.TextAsk
                ChatComposer(
                    enabled = !live.dots && live.turn != null,
                    ask = ask,
                    focus = ask != null && ask.suggestions.isEmpty() && !touchExploration,
                    onFocus = { composerFocused = it },
                    onAttach = attachmentActions.onAdd,
                    onLabs = hosts.onLabs,
                ) { text -> if (ask != null) actions.onText(ask.step, text) else send(text) }
            }
        }
    }
}

/**
 * [ref] with the id of the row this chat's link names at its moment — the record "Open that entry"
 * points at, found as the card finds its own; null when it is no longer there.
 */
private suspend fun StepsDb.openedRef(ref: ChatRecordRef, log: ChatLog, chatKey: String): ChatRecordRef? {
    val link = log.threads.flatMap { it.filed }
        .firstOrNull { it.kind == ref.kind && it.at == ref.at && it.state == LinkState.FILED } ?: return null
    val row = ((resolveLink(link, chatKey) as? Resolved.Found)?.record as? FoundRecord.Row)?.entry ?: return null
    return ref.copy(id = row.id)
}

/**
 * Whether the turn of [count] had its dots up when the page was last put away — a rotation, the process
 * dying — so it comes back at rest: the dots were held for it ([holding]), and not in this composition
 * ([heldHere]: the count whose hold this one fixed).
 */
internal fun caughtMidHold(holding: Int, heldHere: Int?, count: Int): Boolean = holding == count && heldHere != count

/**
 * Whether an answer to [step], given on a reply drawn while thread [thread] asked it, is still to be
 * taken: [thread] is the chat's last thread and still open, and [step] has no turn in it — a thread
 * asks only what has none ([nextChatStep]). So an answer lands once: the second of two taps, or one that
 * comes back after the chat moved on — "Turn on", which throws the switch first — is neither a second
 * turn for its question nor an answer in the thread the chat started after it.
 */
internal fun ChatLog.awaits(thread: Int?, step: SymptomStep): Boolean {
    val last = threads.lastOrNull()?.takeIf { it.id == thread && !it.ended }
    return end == null && last != null && step !in last.report.chat?.askedSteps().orEmpty()
}

/**
 * Whether a write of the chat is to be said to have failed, [outcome] being what the filer's act came
 * to: it threw, so nothing of it is kept; or the filer's own act threw and it kept the chat alone
 * ([FilingResult.failed]), a record it was to file — a new one among them, which has no link to mark
 * — not filed; or a record that stands was not rebuilt, its builder having thrown ([FiledLink.err]).
 * Each is tried again only by a later act. Not a chat deleted meanwhile (null), whose writes write
 * nothing on purpose; nor the database's newer copy handed back, of which this act wrote nothing.
 */
internal fun filingFailed(outcome: Result<FilingResult?>): Boolean {
    val result = outcome.getOrNull()?.takeIf { it.newer == null }
    val unbuilt = result?.links?.values.orEmpty().flatten().any { it.err && it.state == LinkState.FILED }
    return outcome.isFailure || result?.failed == true || unbuilt
}

/**
 * The records of [log] whose first write the page takes as said already as it opens, by thread and role:
 * every one the chat filed — not one a door refused, never written, nor one taken back by Undo. Either
 * filed once an answer changed is a write the filer counts as a first ([FilingResult.firsts]) and the
 * centre flash says, as it does on the page that refused or took it back; counted said, it would come
 * back to the journal in silence.
 */
internal fun flashedAtOpen(log: ChatLog): Set<String> = log.threads.flatMap { thread ->
    thread.filed
        .filter { it.state != LinkState.REFUSED && it.state != LinkState.UNDONE }
        .map { "${thread.id}/${it.role}" }
}.toSet()

/** How much of the chat the bot has answered: its turns, thread by thread, and the end. */
private fun said(log: ChatLog): Int =
    log.threads.sumOf { it.report.chat?.turns?.size ?: 0 } + log.threads.size + (if (log.end != null) 1 else 0)

/**
 * What changes the live chat's list: its rows, the live row, the reply, the map, typing, and what of
 * the map is unfolded under the area chips ([ChatScrolling]).
 */
private data class ScrollChanges(
    val rows: Int,
    val live: LiveShown,
    val reply: Boolean,
    val map: Int?,
    val typing: Boolean,
    val unfolded: Triple<Boolean, Boolean, Boolean>,
    val attachments: String,
    val attachmentEditing: String?,
)

/** The live turn as it was put up: the log it answers, the turn, and whether it was new to the screen then. */
internal data class ShownTurn(val log: ChatLog, val turn: BotTurn, val fresh: Boolean)

/**
 * What the live row shows: [turn], arriving when [fresh], or the dots; [typed], words typed as the answer to
 * [turn]'s question while the model reads them — the question stays, the words under it as the
 * person's, then the dots.
 */
internal data class LiveShown(val turn: BotTurn?, val fresh: Boolean, val dots: Boolean, val typed: String? = null)

/**
 * What the live row shows for [told]: while the model is [reading] what was just sent, the dots, as the
 * turn waits for its reading — under the question still asked and the words
 * [typed] as its answer, when those are what is read; the turn put up for this very log, arriving if it
 * was new; while a turn the screen has not put up yet is [due], the dots — though a line to call
 * emergency services comes at once without them, unless a screen reader needs their lead
 * ([LIVE_REGION_LEAD_MS]); else the script's own turn for the log, the transcript's last, at rest —
 * never dots for "not worked out yet".
 */
@Suppress("LongParameterList") // The live row's every input, each from its own state of the page.
internal fun liveShown(
    items: List<ChatItem>,
    shown: ShownTurn?,
    told: ChatLog,
    due: Boolean,
    touchExploration: Boolean,
    reading: Boolean = false,
    typed: String? = null,
): LiveShown {
    if (reading && typed != null) {
        val asked = shown?.takeIf { it.log == told }?.turn ?: (items.lastOrNull() as? ChatItem.Bot)?.turn
        return LiveShown(asked, fresh = false, dots = true, typed = typed)
    }
    if (reading) return LiveShown(null, fresh = true, dots = true)
    shown?.takeIf { it.log == told }?.let { return LiveShown(it.turn, it.fresh, dots = false) }
    val scripted = (items.lastOrNull() as? ChatItem.Bot)?.turn
    return when {
        scripted == null -> LiveShown(null, fresh = false, dots = false)
        !due -> LiveShown(scripted, fresh = false, dots = false)
        scripted.urgent() && !touchExploration -> LiveShown(scripted, fresh = true, dots = false)
        else -> LiveShown(null, fresh = true, dots = true)
    }
}

/**
 * The row of the list the live chat opens on: the first of the thread whose record stands at
 * [focusAt] — a row of the day list opened — else the live turn, from which the list fills to its end.
 * The stamp stands first in the list.
 */
internal fun liveFocusRow(log: ChatLog, items: List<ChatItem>, focusAt: Long?): Int {
    val thread = focusAt?.let { at -> log.threads.firstOrNull { thread -> thread.filed.any { it.at == at } }?.id }
    val index = thread?.let { id ->
        items.indexOfFirst { (it as? ChatItem.Bot)?.thread == id || (it as? ChatItem.Person)?.thread == id }
    } ?: -1
    return if (index < 0) items.size else index + 1
}

/** A watermark not set yet: a chat carried on shows everything there at its open at rest. */
private const val UNSEEN = -1

/** A reply's or the map's key not set yet, for the same reason. */
private const val UNSEEN_KEY = ""

/**
 * The live row: one region a screen reader is told about — politely, or at once for a line to call
 * emergency services — standing through the dots and then the words, so a turn arriving is a change
 * inside a region already there. Its reply is an item of its own under it, outside the region; the
 * dots are silent. The words rise in when they [arrives] new.
 */
@Composable
private fun LiveRow(
    live: LiveShown,
    state: ChatState?,
    inputs: TurnInputs,
    animated: Boolean,
    arrives: Boolean,
    modifier: Modifier = Modifier,
) {
    val turn = live.turn
    val mode = if (turn?.urgent() == true) LiveRegionMode.Assertive else LiveRegionMode.Polite
    Box(modifier.fillMaxWidth().semantics { liveRegion = mode }) {
        when {
            // Words typed as the answer while the model reads them: the question, the words, the dots.
            live.typed != null && turn != null -> Column(verticalArrangement = Arrangement.spacedBy(LIST_GAP)) {
                BotTurnView(turn, state, inputs, appear = false)
                PersonBubble(live.typed, onEdit = null)
                TypingDots(animated)
            }

            live.dots || turn == null -> TypingDots(animated)

            else -> BotTurnView(turn, state, inputs, appear = arrives && live.fresh)
        }
    }
}

/**
 * A past chat, read as it was said: no pencils, no answers to give, no offers; its cards open what they
 * name, and the files it was given stand under it to be opened ([PastChatFiles]).
 */
@Composable
private fun SymptomPastChat(
    profile: UserProfile,
    source: PastSource?,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onDeleted: () -> Unit,
    onFailed: (String) -> Unit,
    onOpenRecord: ((ChatRecordRef) -> Unit)?,
    opensCourses: Boolean,
) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val locale = LocalLocale.current.platformLocale
    val scope = rememberAutoTakeScope()
    val notDeleted = stringResource(R.string.pain_not_deleted)
    val today = rememberToday()
    val zone = rememberZone()
    var busy by remember { mutableStateOf(false) }
    val loaded by produceState<Pair<Boolean, PastChat?>>(false to null, source) {
        value = true to source?.let { db.pastChat(it, today, zone) }
    }
    val (read, past) = loaded
    // A stored chat that cannot be read — its words not a chat this app reads, its row gone meanwhile — says
    // so and keeps Delete: otherwise the page would be its top bar alone, and a chat the history listed could
    // not be taken out.
    val deletable = past != null || (read && source is PastSource.Chat)
    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            onBack = onBack,
            onHistory = onHistory,
            onDelete = if (deletable) {
                {
                    busy = true
                    scope.launch {
                        runCatchingCancellable {
                            when (source) {
                                is PastSource.Chat -> ChatFiling(RoomChatStore(db), source.key).deleteChat()
                                is PastSource.Row -> db.deleteJournalEvent(source.id)
                                null -> Unit
                            }
                        }.onSuccess { onDeleted() }.onFailure {
                            busy = false
                            onFailed(notDeleted)
                        }
                    }
                }
            } else {
                null
            },
            busy = busy,
            // As the chat read: in a model's words where a model read something of it.
            about = chatAboutRes(past?.log?.readExamined() == true),
        )
        if (past == null) {
            if (read) {
                Text(
                    stringResource(R.string.symptom_chat_unreadable),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = PwnMuted,
                )
            }
            return@Column
        }
        // Read-only from here on: what could put an earlier state back is no longer kept — written in the
        // scope that outlives the page, so a page left at once does not roll the seal back with it.
        LaunchedEffect(source) {
            val chat = source as? PastSource.Chat ?: return@LaunchedEffect
            scope.launch { ChatFiling(RoomChatStore(db), chat.key).seal() }
        }
        val setting = past.setting(profile, today, zone, locale)
        val records by produceState(emptyMap<Pair<Int, String>, CardRecord>(), past) {
            val key = (source as? PastSource.Chat)?.key.orEmpty()
            value = past.log.threads.flatMap { thread -> thread.filed.map { thread.id to it } }
                .associate { (thread, link) -> (thread to link.role) to db.cardRecord(link, key) }
        }
        val inputs = TurnInputs(
            log = past.log,
            setting = setting,
            imperial = profile.imperial,
            today = today,
            record = { thread, role -> records[thread to role] ?: past.row?.let { CardRecord(null, it, null, null) } },
            open = onOpenRecord,
            cards = null,
            offers = null,
            busy = true,
            opensCourses = opensCourses,
        )
        if (remember(past) { safetyPinned(past.log) }) PinnedSafetyLine()
        val items = remember(past, setting) { past.transcript(setting) }
        val focus = remember(items) { past.focusIndex(items) }
        val files = remember(past) { medicalAttachments(past.log.attachments) }
        ChatList(
            listState = rememberLazyListState(initialFirstVisibleItemIndex = focus),
            modifier = Modifier.weight(1f),
            stamp = chatStamp(past.startedAt, past.day, zone, locale),
        ) {
            itemsIndexed(items) { _, item -> ChatItemView(item, inputs, onEdit = null) }
            if (past.log.note.isNotBlank()) {
                item {
                    PersonBubble(past.log.note, onEdit = null)
                    // As the live chat drew it: the rules' card and the one the note's reading added.
                    NoteSafety(past.log.noteCards())
                }
            }
            // The chat keeps its files, and the grant to read them, for as long as it is kept: without
            // them here a file given to a chat could not be reached once the chat was over.
            if (files.isNotEmpty()) {
                item { PastChatFiles(files) }
            }
        }
    }
}

/**
 * A past chat as it is read: its log, the day it is filed under and when it began; for a row read
 * alone the row itself, and whether its chat was deleted — then "here is what was saved from it"
 * stands where a chat left early says so.
 */
private class PastChat(
    val log: ChatLog,
    val day: LocalDate,
    val startedAt: Long?,
    val env: ChatEnv,
    val row: JournalEntry?,
    val gone: Boolean,
    val focusAt: Long?,
) {
    fun setting(profile: UserProfile, today: LocalDate, zone: ZoneId, locale: Locale) = ChatSetting(
        female = profile.sex == "FEMALE",
        adult = ageOn(today.toString(), profile.birthDate) >= ADULT_AGE,
        day = day,
        past = log.past,
        now = startedAt ?: System.currentTimeMillis(),
        chatMoment = startedAt ?: System.currentTimeMillis(),
        snapshot = null,
        env = env,
        zone = zone,
        sittingFrom = 0,
        language = chatLanguage(locale),
        journal = journalTypesOffered(profile, today),
    )

    fun transcript(setting: ChatSetting): List<ChatItem> {
        val items = chatTranscript(log, setting, live = false)
        val last = (items.last() as? ChatItem.Bot)?.takeIf { gone } ?: return items
        val pieces =
            last.turn.pieces.filter { it != BotPiece.Line(ChatLine.LEFT_EARLY) } + BotPiece.Line(ChatLine.CHAT_GONE)
        return items.dropLast(1) + last.copy(turn = last.turn.copy(pieces = pieces))
    }

    /** The first item of the thread whose record stands at [focusAt]: where a row opened from the day list is. */
    fun focusIndex(items: List<ChatItem>): Int {
        val at = focusAt ?: return items.lastIndex.coerceAtLeast(0)
        val thread = log.threads.firstOrNull { thread -> thread.filed.any { it.at == at } }?.id
            ?: return items.lastIndex.coerceAtLeast(0)
        val index = items.indexOfFirst {
            (it as? ChatItem.Bot)?.thread == thread ||
                (it as? ChatItem.Person)?.thread == thread
        }
        // The stamp stands first in the list.
        return if (index < 0) items.lastIndex.coerceAtLeast(0) else index + 1
    }
}

/** A past chat read from where it is kept: the chats' table, or a row of the journal alone. */
private suspend fun StepsDb.pastChat(source: PastSource, today: LocalDate, zone: ZoneId): PastChat? = when (source) {
    is PastSource.Chat -> dao().chat(source.key)?.let { row ->
        val log = decodeChatLog(row.payload) ?: return@let null
        val day = row.day.toLocalDateOrNull() ?: today
        PastChat(
            log,
            day,
            row.startedAt,
            chatEnv(row.key, day, log.past, row.startedAt),
            null,
            gone = false,
            focusAt = source.focusAt,
        )
    }

    is PastSource.Row -> dao().journalEntry(source.id)?.takeIf { it.kind == KIND_SYMPTOM }?.let { row ->
        val report = decodeSymptomPayload(row.measurementPayloadJson)
        val gone = chatKeyOf(row) != null
        val told = when {
            gone -> replayedChat(report)
            report.chat != null -> report
            else -> report.copy(chat = synthesizedChat(report))
        }
        val day = row.day.toLocalDateOrNull() ?: today
        val log = ChatLog(threads = listOf(ChatThread(0, told)), next = 1, past = day != today)
        // A row of an earlier day stands at its midday, a time nobody told: the stamp says the day alone.
        val begun = row.occurredAt.takeUnless { untimedAct(row, zone) }
        val env = chatEnv(chatKeyOf(row).orEmpty(), day, past = true, now = row.occurredAt)
        PastChat(log, day, begun, env, row, gone, focusAt = null)
    }
}

/**
 * Whether the line to call when it may not be safe stands under the bar: for any feeling's thread, and
 * wherever suicide came up — in the words or in a reading's flag — once pinned in a chat it stays.
 */
private fun safetyPinned(log: ChatLog): Boolean = log.threads.any { thread ->
    val report = thread.report.asTold()
    val chat = report.chat ?: SymptomChat()
    val crisis = crisisIn(chat.words()) || (chat.routesByReadings() && SymptomStep.CRISIS_NOW in chat.readSteps())
    thread.topic?.mind == true || crisis || SymptomStep.CRISIS in chat.askedSteps() ||
        SymptomStep.CRISIS_NOW in chat.askedSteps() || medicineCrisisAsked(report)
}

/**
 * The bar: back, the history behind the clock, what the chat is behind the "?" — [about], in a model's
 * words where one reads ([chatAboutRes]) — and delete once it is kept.
 */
@Composable
private fun ChatTopBar(
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onDelete: (() -> Unit)?,
    busy: Boolean,
    @StringRes about: Int,
) {
    val title = stringResource(R.string.symptom_chat_title)
    PwnTopBar(title, onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHistory, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.Outlined.History, stringResource(R.string.symptom_chat_history))
            }
            HelpDot(title) {
                Text(stringResource(about))
                Text(stringResource(R.string.symptom_local_only))
            }
            onDelete?.let { delete ->
                IconButton(onClick = delete, enabled = !busy) {
                    // A drawing ignores the colour the button dims its content to when it is off, so it
                    // fades itself by Material's disabled opacity, as the pain page's does.
                    Image(
                        themePainterResource(R.drawable.ic_art_delete),
                        stringResource(R.string.common_delete),
                        Modifier.size(26.dp).alpha(if (busy) DISABLED_ALPHA else 1f),
                    )
                }
            }
        }
    }
}

/**
 * "If you feel you might not be safe, call emergency services" under the bar, for as long as the page
 * is open: a safety instruction, not an explanation, so it is printed, and kept in view however far
 * the chat has scrolled.
 */
@Composable
private fun PinnedSafetyLine() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.symptom_chat_safety),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Material's opacity for a control that is off. */
private const val DISABLED_ALPHA = 0.38f

/** The space between two rows of a chat's list, and between the parts of the live row. */
private val LIST_GAP = 14.dp

/** The scrolling chat, under a stamp of its day and time. No IME inset: the window resizes for the keyboard. */
@Composable
private fun ChatList(
    listState: LazyListState,
    modifier: Modifier,
    stamp: String,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(LIST_GAP),
    ) {
        item(key = "stamp") {
            Text(
                stamp,
                Modifier.fillMaxWidth(),
                color = PwnMuted,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
        content()
    }
}

/**
 * The chat's day, and the time it began when it began on that day: none before it is kept, and none for
 * a chat filed for a day gone by, which nobody told at that day's time.
 */
private fun chatStamp(startedAt: Long?, day: LocalDate, zone: ZoneId, locale: Locale): String {
    val date = painDayText("dMMMMy", locale, day)
    val at = startedAt?.let { Instant.ofEpochMilli(it).atZone(zone) }?.takeIf { it.toLocalDate() == day }
    return if (at == null) date else date + " · " + clockText(at.hour * 60 + at.minute)
}

/** The records of [thread] an act rewrote: filed before it and after, from answers that changed. */
internal fun rewrittenBy(thread: ChatThread, links: List<FiledLink>): List<FiledLink> = links.filter { link ->
    link.role != WORDS_ROLE &&
        link.state == LinkState.FILED &&
        thread.filed.any { it.role == link.role && it.state == LinkState.FILED && it.sig != link.sig }
}

/**
 * The records of [sent]'s last thread an act rewrote ([rewrittenBy]) that the centre flash says
 * "Saved" of: a topic that flashes at the write, and each record only where [recordFlashAllowed]
 * lets its flash stand in [log] — its topic's own rule too: no dose after a line to get advice now.
 */
internal fun rewriteFlashes(
    log: ChatLog,
    setting: ChatSetting,
    shown: BotTurn?,
    sent: ChatLog,
    result: FilingResult,
): List<FiledLink> {
    val thread = sent.threads.lastOrNull()?.takeIf { it.topic?.script?.flashAt == FlashAt.FILE }
        ?: return emptyList()
    return rewrittenBy(thread, result.links[thread.id].orEmpty()).filter { link ->
        recordFlashAllowed(log, setting, shown, thread, link)
    }
}

/**
 * Whether Undo or Save again ([again]) on [link]'s card of [thread] is said with the centre flash: an
 * Undo, or a Save again that filed the record again — and either only where [recordFlashAllowed] lets
 * a record's flash stand in [log], its topic's own rule too: no "added" for a dose saved again after a
 * line to get advice now.
 */
internal fun cardFlashAllowed(
    log: ChatLog,
    setting: ChatSetting,
    shown: BotTurn?,
    thread: ChatThread,
    link: FiledLink,
    again: Boolean,
): Boolean = (!again || link.state == LinkState.FILED) && recordFlashAllowed(log, setting, shown, thread, link)
