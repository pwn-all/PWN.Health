package com.pwnall.health

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

// The bell and the pull-down panel behind it, shared by the blood work page and the home screen so
// the two screens cannot drift apart in how a shade comes down or how far a finger has to push it
// back up. What each screen puts *in* the panel stays with that screen.

internal const val SHADE_SCRIM = 0.32f
internal val SHADE_MAX_HEIGHT = 420.dp

/** How far up a finger has to travel in one step before the shade goes back. */
internal const val SHADE_DISMISS_DRAG = 6f

/** The red dot, on the bell and beside each notice. */
internal val NOTICE_DOT = 8.dp

/** The way into the shade, with the dot that says something in it has not been read. */
@Composable
internal fun NoticeBell(unread: Boolean, contentDescription: String, onOpen: () -> Unit) {
    IconButton(onClick = onOpen, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
        Box {
            Icon(
                Icons.Outlined.NotificationsNone,
                contentDescription,
            )
            if (unread) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .size(NOTICE_DOT)
                        // Said in the icon's own label: a second node here would announce the dot
                        // as a control of its own, which is not what it is.
                        .clearAndSetSemantics { }
                        .background(PwnBleed, CircleShape),
                )
            }
        }
    }
}

/**
 * The notices, pulled down over the page the way the phone's own shade comes down over an app.
 *
 * Down from the top rather than up from the bottom because that is where the bell is and that is
 * where a shade comes from; a swipe up sends it back, and so does a tap on the page behind it.
 *
 * [content] is everything under the title, inside the panel's own scroll — a list of notices, or the
 * one line that says there are none. The panel does not know which, which is what lets two screens
 * of entirely different content share it.
 */
@Composable
internal fun NoticeShade(
    visible: Boolean,
    title: String,
    onHide: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = visible, onBack = onHide)
    // The shade comes down on the same springs a panel rises on: the dim on the effects spring, the
    // panel itself on the spatial one. Each opening plays them once.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(PwnMotion.effects()),
        exit = fadeOut(PwnMotion.effects()),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The dim is its own layer behind the panel rather than the panel's parent: as a parent
            // every tap inside the panel — a notice row, the space beside the title — would have
            // reached its onClick on the way up and shut the shade. No ripple either: a screen-sized
            // button that flashes when anything in front of it is touched is not a control.
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = SHADE_SCRIM))
                    // The dim answers the shade's own gesture, and swallows every other drag on
                    // it. Left bare, a downward flick here would reach the panel underneath — the dim
                    // is drawn inside it — and throw away the page the shade was opened over.
                    .dragUpToHide(onHide)
                    // A tap target for a finger only. Left in, it would be a nameless node the size of
                    // the screen to a screen reader and one more stop to a keyboard, and both already
                    // have the shade's own Close button and Back. The clearing comes before the click
                    // in the chain; after it, the click's action would survive it.
                    .clearAndSetSemantics { }
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.common_close),
                        onClick = onHide,
                    ),
            )
            val focus = remember { FocusRequester() }
            // Sent into the shade when it opens, so a keyboard carries on from the notices rather
            // than from the page now dimmed behind them. Under touch nothing here takes focus, and
            // the request lands nowhere.
            LaunchedEffect(focus) { focus.requestFocus() }
            Surface(
                Modifier.fillMaxWidth()
                    // The slide on the fade's own transition. A second AnimatedVisibility here would
                    // come into the composition already visible, with the outer one, and so have
                    // nothing to enter from: the shade would fade in where it stood and never come down.
                    .animateEnterExit(
                        enter = slideInVertically(PwnMotion.spatial()) { -it },
                        exit = slideOutVertically(PwnMotion.spatial()) { -it },
                    )
                    // Before the cap, so the panel can never grow into the strip the floating
                    // bar stands on. In portrait it costs nothing — the shade wraps its
                    // notices well above the cap — and it only bites in a viewport short
                    // enough for the shade's own drag handle to land under the pill.
                    .padding(bottom = LocalBottomBarInset.current)
                    .heightIn(max = SHADE_MAX_HEIGHT)
                    .clip(
                        RoundedCornerShape(bottomStart = PwnCorners.extraLarge, bottomEnd = PwnCorners.extraLarge),
                    )
                    // A screen reader is told the shade has come down, by its title.
                    .semantics { paneTitle = title }
                    // Tab and the arrow keys stay among the notices while the shade is down
                    // rather than walking out into the page it covers. Back and Close let go.
                    .focusRequester(focus)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    // Nothing the shade is dragged with belongs to the page under it: without
                    // these two a pull downwards on the shade — by finger on the panel, or as
                    // scroll the list at its top could not use — lands in the panel's own
                    // nested scroll and takes the whole page under it away with it.
                    .nestedScroll(ShadeScrollWall)
                    .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> change.consume() } },
                color = PwnSurfaceElevated,
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The pull is on the head and on the handle rather than on the whole
                    // panel: the list under them scrolls, and a drag detector over a scrolling
                    // list never sees the gesture — the list has already claimed it.
                    Row(
                        Modifier.fillMaxWidth().dragUpToHide(onHide),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = onHide,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                        ) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).panelVerticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                    // The handle at the foot, where a shade is pushed back up from.
                    Box(
                        Modifier.fillMaxWidth().dragUpToHide(onHide),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The outline role, as on the panel's own handle: it says where the shade is
                        // pushed back from, so it is held to 3:1 — 3.61:1 on the shade in daylight,
                        // 4.32:1 by night.
                        Box(
                            Modifier.size(width = 36.dp, height = 4.dp)
                                .clearAndSetSemantics { }
                                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The end of the line for a scroll: whatever the shade's own list cannot use stops here.
 *
 * The page under the shade may be a panel, and a panel answers a downward scroll it is offered by
 * shutting itself — see [PwnSheetPage]. The shade is drawn inside that panel, so without a wall its
 * list would hand every unspent pixel straight to it.
 */
private val ShadeScrollWall = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** A push upwards, anywhere nothing else is listening for one. */
internal fun Modifier.dragUpToHide(onHide: () -> Unit): Modifier = pointerInput(onHide) {
    detectVerticalDragGestures { change, amount ->
        if (amount < -SHADE_DISMISS_DRAG) {
            change.consume()
            onHide()
        }
    }
}
