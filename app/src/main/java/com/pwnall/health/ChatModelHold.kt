package com.pwnall.health

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

// The chat page's hold on its model: the reader taken while
// the page stands on screen and given back as it goes or the app stops — the process's readers keep
// it over a rotation — and the line under the bar that says a model reads the messages, standing only
// while one does.

/**
 * What the chat page holds of its model: the [reader] to read a message with — while its model
 * loads too, a read waiting on the load within its second; none with no model in the app,
 * with a smoke-test model, or after a failure — never for a slow phone — when the chat is as
 * it is without one — whether the chat [routes] by its readings rather than keeping them
 * in the shadow, and the [languages] enabled for routing, once it has loaded
 * ([ChatModelState.Ready]): none while it loads, nothing being known of them yet.
 */
internal class ChatModelHold(
    val reader: ChatModelReader?,
    val routes: Boolean,
    val languages: Set<String> = emptySet(),
) {
    /**
     * Whether the chat uses a model now, the line under the bar saying so ([ChatModelLine]): one that
     * reads — loading still, since a message sent meanwhile is read once it has loaded, so the line
     * stands before the first message is sent (the AI Act's article 50(5)) — its readings routed by: in
     * the shadow nothing it reads is used, and a build for everyone never reads in it ([ChatModelMode.build]).
     */
    val on: Boolean get() = reader != null && routes

    /**
     * Whether the model reads in full what is written in [locale]'s language: it is [on], and routing
     * is enabled for that language — what the chat's "?" then says of how it reads ([chatAboutRes]). In
     * another language it adds its safety questions alone, and the chat says what it says without one.
     */
    fun readsInFull(locale: Locale): Boolean = on && readerLanguage(locale) in languages
}

/**
 * [locale]'s language as the reader's language head names it ("ru", "en", "zh"…): its language tag's
 * first part, which is "id" for Indonesian however old a name Android keeps for it ("in").
 */
internal fun readerLanguage(locale: Locale): String = locale.toLanguageTag().substringBefore('-').lowercase(Locale.ROOT)

/**
 * What the chat's "?" says the chat is: that the app's lists of words and an AI
 * model on this phone read the messages to choose among its fixed questions, that the model can be
 * wrong and only a tap answers, when [modelReads] — a model reads in full here ([ChatModelHold.readsInFull])
 * or read something of this chat so ([ChatLog.readExamined]); else that it looks for a few words only, as
 * the chat says without a model.
 */
@StringRes
internal fun chatAboutRes(modelReads: Boolean): Int =
    if (modelReads) R.string.symptom_chat_about_model else R.string.symptom_chat_about

/**
 * What the page holds of [reader] in [state] — null while it loads — under [mode]: the reader while it
 * loads and once it is ready, none once it is unavailable; its enabled routing languages once ready.
 */
internal fun chatModelHold(reader: ChatModelReader?, state: ChatModelState?, mode: ChatModelMode): ChatModelHold =
    ChatModelHold(
        reader.takeIf { state !is ChatModelState.Unavailable },
        mode.routes,
        (state as? ChatModelState.Ready)?.languages.orEmpty(),
    )

/**
 * The chat's model for as long as the page stands, from the process's readers ([ChatModelReaders]),
 * taken as the page is first composed — so its first frame has it, its line standing at once, never
 * sliding in after a rotation — and given back as the page goes: its state worked out again whenever
 * the model turns itself off ([ChatModelReader.stopped]), so the reader and the line go the moment it
 * does — a read past its second leaves the model as it was. With no model in the build at all, or its
 * switch off, no reader is asked for: nothing of a model is made, opened or read on any opening.
 *
 * The app stopped gives it back as well — Home, the screen off, another app over this one: the page
 * stays composed behind them, in a process that counts steps for days, and would keep the session's
 * 100 MB there with it. Back within the readers' linger the page has the same reader, loaded as it
 * was; later a new one, loading as on an opening.
 */
@Composable
internal fun rememberChatModel(): ChatModelHold {
    if (!BuildConfig.CHAT_MODEL_SHIPS || !ChatModelMode.build.reads) return NO_MODEL
    val context = LocalContext.current
    val readers = remember(context) { ChatModelReaders.of(context) }
    val held = remember(readers) { HeldReader(readers) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, held) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> held.giveBack()
                Lifecycle.Event.ON_START -> held.takeAgain()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val reader = held.reader
    val stopped by reader.stopped.collectAsStateWithLifecycle()
    // Per reader: one taken after a stop starts from its own state, loading, never from the last one's.
    val state by key(reader) { produceState(reader.stateNow(), stopped) { value = reader.state() } }
    return chatModelHold(reader, state, ChatModelMode.build)
}

/** The page's hold with no model in the build: no reader, no line. */
private val NO_MODEL = ChatModelHold(null, routes = false)

/**
 * [readers]' reader, taken as the composition first remembers it and given back once it forgets it —
 * and for as long as the app is stopped, the page still composed ([giveBack], [takeAgain]).
 */
internal class HeldReader(private val readers: ChatModelReaders) : RememberObserver {
    /** The reader the page reads with: the one held now, or while the app is stopped the one given back. */
    var reader by mutableStateOf(readers.acquire())
        private set
    private var given = false

    override fun onRemembered() = Unit

    override fun onForgotten() = giveBack()

    override fun onAbandoned() = giveBack()

    /** The app has stopped or the page has gone: given back once, however many of the two say so. */
    fun giveBack() {
        if (given) return
        given = true
        readers.release(reader)
    }

    /**
     * The app is on screen again: the reader given back is taken again — the same one within the
     * readers' linger, a new one after it. Nothing is taken twice by the start a lifecycle replays to
     * an observer added while the app is on screen.
     */
    fun takeAgain() {
        if (!given) return
        given = false
        reader = readers.acquire()
    }
}

/**
 * The line under the chat's bar while its model is [on] (the AI Act's article 50(1), by the
 * first message): one short line, that a model on this phone reads the messages — coming and going
 * with the model, never shifting the list at a jump — and behind its "?" what the model does and does
 * not: that it chooses which of the app's fixed questions and buttons to show, may add one of the
 * app's fixed safety questions and never remove one — it takes part in choosing that question, and
 * the words say so — what it reads, and what is kept of it. An explanation is never a paragraph.
 */
@Composable
internal fun ChatModelLine(on: Boolean) {
    InlineReveal(on) {
        val status = stringResource(R.string.symptom_chat_model_status)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status, Modifier.weight(1f), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            HelpDot(status) {
                Text(stringResource(R.string.symptom_chat_model_line))
                Text(stringResource(R.string.symptom_chat_model_more))
            }
        }
    }
}
