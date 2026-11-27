package com.pwnall.health

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What happens between tapping "one beer" and believing it was recorded.
 *
 * Two things, and they are the same thing seen from either side. The person needs to see that the
 * tap landed — a line of text appearing somewhere below the fold is not seeing it — and the app
 * needs the second tap not to become a second beer. So the confirmation is a card in the middle of
 * the screen with a green tick, and for as long as it takes to read it, the button that was pressed
 * is shut.
 *
 * That button and no other. A screen of tiles is a screen of separate decisions, and closing all of
 * them because one of them is being written turns one tap on the beer into three seconds of a dead
 * page — the cigarette beside it was never in any danger of being logged twice. So every caller
 * names its button: [log] takes the key, [locked] answers for that key alone.
 *
 * Three seconds, the same window [DUPLICATE_TAP_WINDOW_MS] guards water and cigarettes with at the
 * data layer. This is the visible half of that rule: the guard below still catches a widget tap or
 * a screen that was a moment stale, and this stops the finger before it gets there.
 */
class QuickLogState internal constructor(private val notSaved: String) {
    internal var message by mutableStateOf<String?>(null)

    /** The failure card's words, up until it is tapped away or the next tap replaces it. */
    internal var failure by mutableStateOf<String?>(null)

    /** The keys whose buttons are shut — snapshot-backed, so releasing one redraws that button. */
    private val shut = mutableStateListOf<String>()

    // The ticks are counted rather than compared, because two of them can overlap — two tiles can be
    // tapped in the same breath — and the first write finishing must not take down the confirmation
    // the second one has just put up; two taps can carry the same wording. Only an answer that changes
    // the card counts: a repeat the data layer swallowed, counted at its tap, would take the other
    // button's tick down at once, seconds before its window closed.
    private var flashes = 0

    /** Whether this one button is shut. Pass the key its tap logs with, as `!quickLog.locked(k)`. */
    fun locked(key: String): Boolean = key in shut

    /**
     * Shuts this button and shows the tick, runs the write, and holds both for the window.
     *
     * A tap while shut does nothing at all — not queued, not swallowed silently later. The write
     * itself is whatever the screen already did; this only decides when the next one may happen.
     * The key is whatever tells two buttons on the screen apart — the drink, the product, the slot
     * — and the same string has to reach [locked] for the button's `enabled` to follow it.
     *
     * The button shuts before the write rather than after it, because the second tap arrives while
     * the first one is still in the database. The tick waits for the write — a few milliseconds —
     * and a write that throws puts up the failure card instead, so a disk that is full or a key the
     * Keystore will not give says so where the eye is, rather than flashing "Added" over a row that
     * was never written. A write that answers `false` — the data layer's own duplicate guard
     * swallowed it as a repeat — shows nothing: the first tap already confirmed it. Any other answer,
     * including no answer at all, counts as stored.
     */
    fun log(scope: CoroutineScope, key: String, confirmation: String, write: suspend () -> Any?) {
        if (locked(key)) return
        shut += key
        failure = null
        scope.launch {
            // The button opens however this ends, a cancellation the write throws included, or it
            // would stay shut for as long as the page stood.
            try {
                // The write is seen through even when the page goes under it — a tab tapped straight
                // after the beer — as the app's other single writes are ([saveJournalEvent]): the tap
                // landed, so the row lands, and only the tick and the window are let go. A cancellation
                // the write itself throws still travels: swallowing it would keep the coroutine
                // running for a caller that is gone.
                val outcome = runCatching { withContext(NonCancellable) { write() } }
                    .onFailure { if (it is CancellationException) throw it }
                val stored = outcome.isSuccess && outcome.getOrNull() != false
                // A failure takes another button's tick down too: the two cards stand in one place.
                if (stored || outcome.isFailure) {
                    val flash = ++flashes
                    message = confirmation.takeIf { stored }
                    if (outcome.isFailure) failure = notSaved
                    if (stored) delay(DUPLICATE_TAP_WINDOW_MS)
                    if (flashes == flash) message = null
                }
            } finally {
                shut -= key
            }
        }
    }
}

/**
 * `remember`, not `rememberSaveable`: a rotation mid-write hands the buttons straight back.
 * Surviving it would take a Saver for the shut keys and a timer to see out whatever was left
 * of each interrupted window, and none of that is what actually stops a second row being written:
 * [isDuplicateTap] at the data layer does, for the rotated screen and the widget alike.
 */
@Composable
fun rememberQuickLog(): QuickLogState {
    val notSaved = stringResource(R.string.quick_log_not_saved)
    return remember { QuickLogState(notSaved) }
}

/**
 * The confirmation itself: a tick, a word, and nothing to dismiss.
 *
 * Placed over the screen rather than under it: a status line at the bottom is out of sight, since
 * the eye is on the button that was just pressed, which is where this appears.
 */
@Composable
fun QuickLogFlash(state: QuickLogState, modifier: Modifier = Modifier) {
    PwnActionFlash(state.message, modifier)
    PwnFailureFlash(state.failure) { state.failure = null }
}

/**
 * The one way this app says a thing was done. Every "Saved", "Changed", "Copied", "Exported" and
 * "Deleted" in the app is this card, over the middle of the screen, in whatever word names what
 * the person just did.
 *
 * Nothing prints the word as a line of coloured text at the foot of a form: [rememberStatusLine]
 * holds it and takes it back down after the window, and this card shows it. The reason is the
 * same one written above [QuickLogFlash]: after a tap the eye is on the control that was tapped,
 * and a sentence that appears three hundred pixels below it, at the end of a scroll, is a sentence
 * written where nobody is looking — or below the fold, where it cannot be seen at all without
 * scrolling to find the news.
 *
 * The animation is deliberately short. It is a confirmation of something already finished, not a
 * transition to wait through: [FLASH_IN_MS] in, [FLASH_OUT_MS] out, growing from nine tenths so it
 * reads as appearing rather than as sliding in, and leaving faster than it arrives so it never
 * feels like something to sit through.
 *
 * [announce] false keeps the word from a screen reader: a page that already says the same thing in
 * words it announces itself — the chat, whose card for the record stands in the turn that follows the
 * write — would otherwise have it spoken twice.
 */
@Composable
fun PwnActionFlash(message: String?, modifier: Modifier = Modifier, announce: Boolean = true) {
    // The word outlives the message by the length of the exit: the message is already null while
    // the card fades out, and reading it there would empty the card to the tick alone, so it would
    // shrink sideways as it went. A plain holder rather than state, because writing state here would be
    // a write during composition; the message changing is what recomposes this anyway.
    val lastWord = remember { arrayOfNulls<String>(1) }
    if (message != null) lastWord[0] = message
    val shown = message ?: lastWord[0]
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(FLASH_IN_MS)) +
                scaleIn(tween(FLASH_IN_MS), initialScale = FLASH_START_SCALE),
            exit = fadeOut(tween(FLASH_OUT_MS)) +
                scaleOut(tween(FLASH_OUT_MS), targetScale = FLASH_START_SCALE),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = PwnGreen,
                        modifier = Modifier.size(26.dp),
                    )
                    // The tick is the whole confirmation, and a tick is nothing to a screen
                    // reader: the card takes no focus, so without a live region it would be announced
                    // zero times in the three seconds it is up. Polite rather than Assertive
                    // because nothing waits on it — the row is already written — and it is the
                    // register the pulse card's status text and the device search already use.
                    Text(
                        shown.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = if (announce) {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/**
 * A failure, where [PwnActionFlash] puts a confirmation: the same card over the middle of the page,
 * with the error's mark and colour in place of the green tick. It does not take itself down — an
 * export that was not written or a file that would not open is read, not glimpsed — so it stays
 * until it is tapped away or the next attempt replaces it.
 */
@Composable
internal fun PwnFailureFlash(message: String?, onDismiss: () -> Unit) {
    val close = stringResource(R.string.common_close)
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = message,
            transitionSpec = {
                (fadeIn(PwnMotion.effects()) + scaleIn(PwnMotion.effects(), initialScale = FLASH_START_SCALE))
                    .togetherWith(fadeOut(PwnMotion.effects()) + scaleOut(PwnMotion.effects(), FLASH_START_SCALE))
            },
            contentAlignment = Alignment.Center,
            label = "failure",
        ) { shown ->
            if (shown != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = PwnElevation.level3,
                ) {
                    Row(
                        Modifier.clickable(onClickLabel = close, role = Role.Button, onClick = onDismiss)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            shown,
                            Modifier.politeLiveRegion(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** "Added", the one word every quick log confirms with, in whatever language is showing. */
@Composable
fun quickLogAdded(): String = stringResource(R.string.quick_log_added)

/** A confirmation that names what landed — "Beer · added" — for screens with several buttons. */
@Composable
fun quickLogAdded(what: String): String = stringResource(R.string.quick_log_added_named, what)

/**
 * A status line that takes itself back down.
 *
 * The screens that write one — "Saved", "Nothing to import" — hold it here, so it clears after the
 * same three seconds the tick above is shown for rather than standing under the section until the
 * page is left, long after it means anything. Read it as `var status by rememberStatusLine()` and
 * assign `status =` as to a plain var; a new message restarts the count rather than inheriting
 * what was left of the old one's.
 */
@Composable
fun rememberStatusLine(): MutableState<String?> {
    val status = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status.value) {
        if (status.value != null) {
            delay(DUPLICATE_TAP_WINDOW_MS)
            status.value = null
        }
    }
    return status
}

/**
 * The card grows from nine tenths, which reads as appearing rather than as sliding in; the failure
 * card beside it does the same.
 */
private const val FLASH_START_SCALE = 0.9f

/** Smooth, and brisk with it: long enough to read as a movement, short enough not to be waited on. */
private const val FLASH_IN_MS = 180

/** Out faster than in — a confirmation should get out of the way of whatever is read next. */
private const val FLASH_OUT_MS = 140
