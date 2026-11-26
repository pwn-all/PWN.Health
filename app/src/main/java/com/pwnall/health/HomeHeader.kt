package com.pwnall.health

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/** Morning from five, afternoon from noon, evening from six, and night from eleven round to five. */
internal fun greetingForHour(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.home_good_morning
    in 12..17 -> R.string.home_good_afternoon
    in 18..22 -> R.string.home_good_evening
    else -> R.string.home_good_night
}

/** The greeting and encouragement, with the notices and the annual level on the right. */
@Composable
internal fun HomeGreeting(xp: AnnualXp, unread: Int, onNotices: () -> Unit, onLevels: () -> Unit) {
    // Read again every minute: a screen left open across noon says good afternoon on its own. Only
    // while it can be seen, and at once on the way back to it, rather than through a night in the
    // background the step service keeps the process for.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hour by produceState(LocalTime.now().hour, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalTime.now().hour
                delay(60_000)
            }
        }
    }
    Row(
        // The same air over the greeting that the profile page keeps over its own title: the two
        // are the top of a tab each, and so start the same distance below the clock.
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A second line preserves the greeting at large font sizes beside the fixed controls.
            Text(
                stringResource(greetingForHour(hour)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 28.sp, stepSize = 1.sp),
            )
            // One short line a day, in turn: the hour read above recomposes this at midnight too.
            val lines = stringArrayResource(R.array.home_motivations)
            Text(
                lines[LocalDate.now().dayOfYear % lines.size],
                color = PwnMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The bell sits inside the greeting row because the home screen has no top bar of its own
        // to hang it from, and beside the level is where the blood work page puts it too.
        NoticeBell(
            unread = unread > 0,
            contentDescription = stringResource(
                if (unread > 0) R.string.home_notices_unread else R.string.home_notices_open,
            ),
            onOpen = onNotices,
        )
        LevelBadge(xp, onLevels)
    }
}

/**
 * The notices this screen has already shown, by key.
 *
 * Not the single rising journal-row watermark the blood work keeps ([LabNoticeLog]): a home notice
 * is not a journal row at all — a cycle nudge is derived, advice carries a string id, a dose is a
 * course and a slot on a day. A set of keys is the only thing that can hold all three, and it is
 * pruned to what is on screen every time the shade opens, so it cannot grow without bound.
 */
internal object HomeNoticeLog {
    private const val PREFS = "home_notices"
    private const val SEEN = "seen_keys"

    fun seen(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(SEEN, emptySet()).orEmpty()

    /** Marks everything on screen read, and forgets every key that is no longer among them. */
    fun markSeen(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(SEEN, keys) }
    }
}

/**
 * The keys the home screen files its advice notices under, one for each of [advice] and in its
 * order. An advice is named by what it says — its topic and its own id — and not by where it
 * stands in the list. The list grows and shrinks through the day: the two nutrition lines at its
 * head come and go with the hour and with what has been eaten. A key that carried the position
 * would move every advice below a line that left up one, match nothing in the seen-set and light
 * the bell again over notices already read.
 *
 * The count at the end is only for two advices sharing an id, which none does: the second of them
 * would get ":2", so that no two notices ever share a key.
 */
internal fun adviceNoticeKeys(advice: List<Advice>): List<String> {
    val met = HashMap<String, Int>()
    return advice.map { item ->
        val name = "advice:${item.topic.name}:${item.id}"
        val nth = (met[name] ?: 0) + 1
        met[name] = nth
        if (nth == 1) name else "$name:$nth"
    }
}

/**
 * One thing the home screen has to say under the cards: what it is about, the sentence itself,
 * and a line under that — the cycle's next date, the advice's detail, the word for what a tap does.
 */
internal data class HomeNotice(
    val key: String,
    val label: String,
    val headline: String,
    val message: String,
    val art: Int,
    /**
     * Sorts above everything else and stays until it is answered rather than until it is read.
     *
     * It does not mean "cannot be refused": the first-cycle ask is pinned and still carries its
     * permanent dismissal, because an app that keeps asking after a no is an app whose card gets
     * swiped past without being read. A missed dose has no dismissal because it clears itself the
     * moment the dose is marked.
     */
    val pinned: Boolean = false,
    val onDismiss: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

/** The air on either side of the round art icon, so the text clears it rather than abutting it. */
private val NOTICE_ART_GAP = 10.dp

/** The same for the control on the right, which is a target to hit rather than a picture to read. */
private val NOTICE_CONTROL_GAP = 20.dp

/**
 * What is left of the card's own margin once the icons have taken their share of it on the outside.
 * Without this the ten and the twenty would stand on top of the sixteen every card on the page keeps
 * and the notice would sit visibly further in than its neighbours.
 */
private val NOTICE_CARD_PADDING = 16.dp - NOTICE_ART_GAP

/** An IconButton lays itself out at the minimum touch target whatever the glyph in it measures. */
private val NOTICE_TOUCH_TARGET = 48.dp

/** The close glyph drawn inside that target. */
private val NOTICE_DISMISS_GLYPH = 20.dp

/**
 * Half the difference between the dismiss button's touch target and its glyph is empty space the
 * button already carries, so padding it by the whole gap would stand the cross visibly further from
 * the text than the chevron stands; only the remainder is padding.
 */
private val NOTICE_DISMISS_PADDING = NOTICE_CONTROL_GAP - (NOTICE_TOUCH_TARGET - NOTICE_DISMISS_GLYPH) / 2

/** The air between the label, the headline and the sentence under it. */
private val NOTICE_LINE_GAP = 4.dp

/**
 * Two lines of headline and four of message.
 *
 * A shade scrolls, so a notice may stand taller than a pager page, and it has no height floor:
 * a list has no neighbouring page to be level with.
 */
private const val NOTICE_HEADLINE_LINES = 2
private const val NOTICE_MESSAGE_LINES = 4

/**
 * One notice in the shade: the round picture, what it is about, the sentence, and the control.
 *
 * Opening and dismissing are not alternatives: the row opens whatever has [HomeNotice.onClick], and
 * the cross dismisses whatever has [HomeNotice.onDismiss]. A pinned notice has to be openable
 * without also being swipeable past.
 */
@Composable
internal fun HomeNoticeRow(notice: HomeNotice, unread: Boolean) {
    // The card is the secondary container, and the muted grey every other card prints its small
    // lines in falls short of 4.5:1 on it in daylight. The container's own content colour carries
    // them instead; size and weight keep the label, headline and sentence apart.
    val secondaryInk = MaterialTheme.colorScheme.onSecondaryContainer
    PwnCard(Modifier.fillMaxWidth(), containerColor = PwnPurpleSoft, onClick = notice.onClick) {
        // Each gap is padding on the icon that asks for it rather than one arrangement spacing
        // shared by the row: the two add up, and the clearance the icons were given would have
        // shown on screen as that clearance plus another fourteen.
        Row(
            Modifier.padding(horizontal = NOTICE_CARD_PADDING, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.padding(horizontal = NOTICE_ART_GAP).size(48.dp)
                    .background(PwnSurfaceElevated, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(themePainterResource(notice.art), null, Modifier.size(30.dp))
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NOTICE_LINE_GAP),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (unread) {
                        Box(
                            // Said by the bell that brought the reader here; a second node would
                            // announce the dot as a control, which it is not.
                            Modifier.size(NOTICE_DOT).clearAndSetSemantics { }
                                .background(PwnBleed, CircleShape),
                        )
                    }
                    // As written and at the label role's own tracking: a line of capitals spaced
                    // wide is slower to read than the word, and weight marks it out just as well.
                    Text(
                        notice.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    notice.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = NOTICE_HEADLINE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                // What will not fit is one tap away: the row opens the same advice in a dialog that
                // prints the whole of it. A notice tall enough to hold every sentence is a page.
                Text(
                    notice.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryInk,
                    maxLines = NOTICE_MESSAGE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notice.onDismiss != null) {
                IconButton(
                    notice.onDismiss,
                    Modifier.padding(horizontal = NOTICE_DISMISS_PADDING),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    // Named for what it throws away: a bare "Close" beside the first-cycle ask
                    // would not say that the ask never comes back.
                    Icon(
                        Icons.Outlined.Close,
                        stringResource(R.string.home_notice_dismiss, notice.headline),
                        Modifier.size(NOTICE_DISMISS_GLYPH),
                    )
                }
            } else if (notice.onClick != null) {
                Box(
                    Modifier.padding(horizontal = NOTICE_CONTROL_GAP).size(34.dp)
                        .background(PwnSurfaceElevated, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(22.dp), tint = PwnMuted)
                }
            }
        }
    }
}

/** The height of every mark under the carousel, and the width of the ones not chosen. */
private val PAGER_DOT = 7.dp

/** The chosen card's mark, drawn long so it stands out by shape as well as by colour. */
private val PAGER_PILL = 20.dp

/**
 * Where the carousel stands: the centred card's mark drawn as a pill, the others as dots in the
 * outline colour at full strength, since a pale half-strength grey is lost on the page's gradient.
 *
 * The row is the one thing that says the position aloud. The pager's own page count is endless, so
 * a screen reader has no "two of three" to read off it; the row announces it politely each time a
 * swipe lands on another card.
 */
@Composable
internal fun HomePagerDots(count: Int, selected: Int) {
    val position = stringResource(R.string.home_card_position, selected + 1, count)
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = position
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val chosen = index == selected
            val width by animateDpAsState(
                if (chosen) PAGER_PILL else PAGER_DOT,
                PwnMotion.spatial(),
                label = "pagerMark",
            )
            Box(Modifier.size(width, PAGER_DOT).background(if (chosen) PwnIndigo else PwnControlEdge, CircleShape))
        }
    }
}
