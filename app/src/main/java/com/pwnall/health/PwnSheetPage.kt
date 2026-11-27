package com.pwnall.health

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The shell every page below the five tabs wears: a panel that rises over the screen it was opened
 * from, rounded where it meets the status bar, with a handle at the top and the page's own content
 * under it.
 *
 * One shape for everything opened from inside the app, rather than a full-screen swap: a tab is a
 * place the app stands, and everything else is something held open over it and then let go of. The
 * panel says which of the two is happening — it comes up from the bottom, it leaves a strip of the
 * page it covers, and the same downward pull that shuts the report's doctor picker shuts it.
 *
 * That pull is deliberately not the one a scroll gives you for free: the swipe that carries a list
 * back to its top does not also throw the page away. The content itself says where it is standing —
 * see [SwipeCloseGesture] — so shutting a page that has been scrolled takes a second swipe, made on
 * purpose, from a page now resting at its top. The handle and fixed headers follow the same rule.
 */
@Composable
internal fun PwnSheetPage(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    backdrop: (@Composable () -> Unit)? = null,
    /**
     * The window's side insets, for a panel standing directly in an edge-to-edge window: the
     * navigation buttons and a camera cutout sit on a side edge in landscape. A panel opened inside
     * a page that is already clear of them leaves this at zero.
     */
    sides: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    var closing by remember { mutableStateOf(false) }
    // Hidden on the first composition and shown a frame later: the page is already composed by the
    // time it is first drawn, so without a state that starts at hidden there is nothing to rise
    // from and the entrance would have no frames of its own. Except on the way back: a panel
    // composed as the one above it falls away was already standing in the picture that fall
    // uncovered, and rising again from the foot of the window would play its entrance twice.
    val standing = LocalArrivingBack.current
    val appearance = remember { MutableTransitionState(standing) }
    LaunchedEffect(closing) { appearance.targetState = !closing }
    // A swipe has already carried the panel off the bottom by the time it says so, so the fall has
    // nothing left to play: the gesture is its own closing animation.
    var swiped by remember { mutableStateOf(false) }
    // How far the panel has been pulled down, shared with whatever is drawn behind it.
    val pull = remember { PullProgress() }
    // How far a predictive back gesture has drawn the panel in, 0 when none is under way.
    val backPull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scrolls = remember { PanelScrollState() }
    RegisterChildPanel(scrolls)
    // [onClose] takes the page out of composition, so calling it from the handle would leave the
    // exit without a frame to play in. It waits here until the panel has finished falling.
    LaunchedEffect(closing, appearance.isIdle, appearance.currentState) {
        if (closing && appearance.isIdle && !appearance.currentState) {
            close()
            // Put the pull back. It lives here, outside the gesture's modifier, so the backdrop can
            // read it, and so it outlives the panel's composition — a host that keeps this very
            // instance and hands it the next page would otherwise draw that page a screen-height
            // below the window.
            pull.distance.floatValue = 0f
            backPull.snapTo(0f)
            // Let go of the latch. Usually this composable is gone a frame later and nobody reads
            // it again — but a host whose pages are all panels (the medicine pages, the meal flow)
            // keeps this very instance and hands it the next page, and a latch left down would
            // hold that page hidden for good.
            closing = false
            swiped = false
        }
    }
    // Raised for as long as this panel is composed, which includes the frames it spends falling:
    // the bar coming back while the page is still on its way out is the flicker this avoids.
    val panels = LocalPanels.current
    DisposableEffect(panels) {
        panels?.let { it.open++ }
        onDispose { panels?.let { it.open-- } }
    }
    // The page behind comes back into focus on the way out however the panel is being let go of.
    // The pull carries a swipe; this carries the back arrow, the system back and the handle, none
    // of which move the pull at all — without it the page behind would stay blurred until the
    // panel was gone and then snap sharp with nothing moving.
    //
    // On the fall's own spring, so it measures how much of the panel has gone. The quick effects
    // spring runs well ahead of the panel: the page behind would be sharp when a quarter of the
    // panel had fallen, and a home card would shut over a page with no blur left on it.
    val exit by animateFloatAsState(
        if (closing) 1f else 0f,
        PwnMotion.spatial(),
        label = "backdrop",
    )
    val beginClose: () -> Unit = { if (!closing) closing = true }
    val swipeClose: () -> Unit = {
        swiped = true
        beginClose()
    }
    // Registered after the one MainActivity holds, so it is offered the press first. Off once the exit is running, or
    // the back that follows [onClose] would arrive here again and the page would never leave.
    //
    // A predictive back gesture draws the panel in toward its foot as the edge is dragged; let go
    // without committing and it springs back, commit and it falls the way every close does. A back
    // that arrives with no gesture — the top bar's arrow, a key — has no progress
    // and closes straight away.
    PredictiveBackHandler(enabled = !closing) { events ->
        try {
            events.collect { backPull.snapTo(it.progress) }
            beginClose()
        } catch (cancelled: CancellationException) {
            scope.launch { backPull.animateTo(0f, PwnMotion.spatial()) }
            throw cancelled
        }
    }
    Box(modifier.fillMaxSize()) {
        // The page this one was opened from, where the host has one to give. It is drawn blurred
        // and under the scrim for as long as the panel sits still, and comes back into focus as
        // the panel is pulled off it — so the last part of the gesture is already a view of the
        // page being returned to rather than a hole where one used to be.
        //
        // The blur is a render effect set inside a `graphicsLayer` block rather than
        // `Modifier.blur`: the block re-runs on draw when [PullProgress] changes, so a sixty-frame
        // drag re-applies one effect per frame instead of recomposing the page underneath sixty
        // times. Decal rather than Clamp at the edges, or the blur drags the outermost pixels
        // outward and the page grows a smeared border the width of the radius.
        //
        // It is a full-screen effect for as long as the panel is open, including while the panel's
        // own content is being scrolled over it, and that is the price of the backdrop. Over a
        // [BackdropPhoto] it is cheap: the picture never changes, so the blurred layer is drawn once
        // and reused until the pull moves it.
        backdrop?.let { behind ->
            Box(
                Modifier.fillMaxSize()
                    // It is scenery. Left as it is, a reader would walk the panel's rows and then
                    // the same rows again from the page behind it, and a finger in the 10dp strip
                    // the panel does not cover would press a row it cannot see.
                    .clearAndSetSemantics { }
                    // Nor may a keyboard reach it: Tab and the arrow keys would otherwise walk out of
                    // the panel into rows drawn blurred behind it, and Enter would press them. Focus
                    // is refused at the group's door, so nothing inside can be focused by moving.
                    .focusProperties { onEnter = { cancelFocusChange() } }
                    .focusGroup()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                    .graphicsLayer {
                        val radius = BACKDROP_BLUR.toPx() * (1f - maxOf(cleared(pull.fraction), exit))
                        renderEffect = if (radius > MIN_BLUR_PX) BlurEffect(radius, radius, TileMode.Decal) else null
                    },
            ) { behind() }
        }
        AnimatedVisibility(
            visibleState = appearance,
            enter = fadeIn(PwnMotion.effects()),
            exit = fadeOut(PwnMotion.effects()),
        ) {
            // The strip of the screen the panel does not cover. It is a scrim rather than a gap so
            // that what shows above the panel reads as something behind it. It lifts with the pull
            // on the same curve as the blur: by the end of the gesture the page behind is neither
            // blurred nor darkened, and the panel is the only thing still moving.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - maxOf(cleared(pull.fraction), exit) }
                    .background(PwnSheetScrim),
            )
        }
        AnimatedVisibility(
            visibleState = appearance,
            modifier = Modifier.align(Alignment.TopCenter),
            // Rising, the panel is travelling onto its own resting edge, and the spatial spring's
            // overshoot would lift that edge off the foot of the window for a moment and open a
            // band of scrim beneath it. The same stiffness without the bounce lands it flush. The
            // fall keeps the bounce: it happens past the bottom of the window, out of sight.
            enter = slideInVertically(settleSpring()) { it },
            exit = if (swiped) {
                ExitTransition.None
            } else {
                slideOutVertically(PwnMotion.spatial()) { it }
            },
            label = "sheet page",
        ) {
            Surface(
                // The sides before the width cap: in a narrow window the panel stops short of the
                // side bar or the cutout, and in a wide one it is centred in what is left.
                Modifier.fillMaxHeight().padding(sides).widthIn(max = PwnDimensions.maxPaneWidth).fillMaxWidth()
                    // The room above the panel is handed in rather than read from the window: a
                    // page opened inside another page is already clear of the status bar, and a
                    // second reading of that inset would push it a bar's height further down.
                    .padding(top = topInset + SHEET_PEEK)
                    .graphicsLayer {
                        val scale = 1f - BACK_SHRINK * backPull.value
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    // The pull before the clip, so the corners travel with it. The other way round
                    // the frame stays where it is and the page slides out from under its own edge.
                    // Not while a page inside holds the panel open (the chat): its back arrow and the
                    // system back are the only ways out of it then.
                    .swipeDownToClose(
                        swipeClose,
                        pull,
                        enabled = { scrolls.closesBySwipe },
                    ) {
                        scrolls.canStartClose
                    }
                    .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // A page holding the panel open (the chat) shows no handle to pull: the room stays.
                    SheetHandle(shown = !scrolls.swipeCloseHeld)
                    // No bar stands over a panel, so nothing inside one reserves room for one.
                    // Said here rather than by the app screen: said there, the page drawn behind
                    // this panel would be re-laid out without the inset it was measured with, and
                    // a front page scrolled to its end would lose the last of its scroll.
                    CompositionLocalProvider(
                        LocalBottomBarInset provides 0.dp,
                        LocalPanelScrollState provides scrolls,
                    ) { content() }
                }
            }
        }
    }
}

/**
 * A page that is a panel where it was opened from inside the app, and the page itself where it is
 * one of the five places the app stands.
 *
 * The question is asked at every screen that holds pages of its own — the tabs, the medicine
 * pages, the profile's sections — and answering it here keeps the answer, and the panel, the same
 * everywhere.
 */
@Composable
internal fun PwnPageHost(
    sheet: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    backdrop: (@Composable () -> Unit)? = null,
    sides: PaddingValues = PaddingValues(),
    content: @Composable (Modifier) -> Unit,
) {
    if (sheet) {
        // The panel wears the insets the page would have worn — it is the thing standing in the
        // window now — and hands the page inside it a clean modifier.
        //
        // [backdrop] is the page this one was opened from, and the host has to hand it over: a
        // panel cannot go and fetch it. Capturing it here does not work — a GraphicsLayer's
        // recording holds handles to its children rather than their pixels, and by the time the panel
        // is up those children are drawing the panel's own page, so the picture would come back as a
        // blurred copy of the page in front of it. A host with nothing cheap to put behind the panel
        // passes nothing, and gets the plain scrim.
        PwnSheetPage(onClose, modifier, topInset, backdrop, sides) { content(Modifier) }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            content(modifier.widthIn(max = PwnDimensions.maxPaneWidth))
        }
    }
}

/**
 * True for the one composition that lands back on a page from a panel that has just fallen off it:
 * every panel composed in it was standing in the picture the fall uncovered — see [PwnSheetPage].
 */
internal val LocalArrivingBack = compositionLocalOf { false }

/**
 * A photograph of the app as it stands, taken the moment before a panel opens over it.
 *
 * A panel's backdrop is the page it was opened from, coming back into focus as the panel is pulled
 * off it. Where that page can be kept composed behind the panel for nothing — the profile's front
 * page, a medicine page, a step of the meal flow — its host hands it over alive. Everywhere else it
 * cannot: a screen the app has navigated away from runs its flows and tickers for as long as it is
 * composed, its back handlers are offered the press meant to shut the panel, and composed again
 * under its own saved-state key while it is leaving, it reads back what it remembered before it
 * had saved it. With nothing drawn behind those panels, a pull would uncover a white window
 * instead of the page being returned to.
 *
 * They get a picture of it instead, taken inside the very call that opens the panel. A picture runs
 * nothing and answers nothing, and it is exactly what the page looked like when it was left, which
 * is all a pull has to show. It has to be taken then and not a frame later: the layer's recording
 * holds its children's render nodes rather than their pixels, and once the panel is composed those
 * children draw the panel's page. Rendered while they still draw the old one, the pixels are the
 * old page's.
 */
internal class BackdropCamera(internal val layer: GraphicsLayer, private val scope: CoroutineScope) {
    /** Where the photographed box stands in the window, so a picture lines up in a smaller box. */
    internal var origin = Offset.Zero

    /** Photographs the app now, then hands the picture to [open], which is what changes the screen. */
    fun shoot(open: (ImageBitmap?) -> Unit) {
        // Undispatched, so the picture is taken inside this very call: from API 28 the layer renders
        // into a hardware bitmap without suspending, before anything has been recomposed. A box not
        // drawn yet has nothing to photograph, and a picture that fails is a panel with no backdrop,
        // never a panel that does not open.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            open(if (layer.size == IntSize.Zero) null else runCatching { layer.toImageBitmap() }.getOrNull())
        }
    }
}

/**
 * The picture of what leaving the standing screen lands on, where the app has one: the add tab's
 * last step falls away onto it rather than onto anything inside the tab.
 */
internal val LocalReturnPhoto = compositionLocalOf<ImageBitmap?> { null }

/** The camera over the whole app, where a host has one to lend; null in a preview or a test. */
internal val LocalBackdropCamera = staticCompositionLocalOf<BackdropCamera?> { null }

@Composable
internal fun rememberBackdropCamera(): BackdropCamera {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    return remember(layer, scope) { BackdropCamera(layer, scope) }
}

/** What this box draws, recorded where [camera] can photograph it. */
internal fun Modifier.filmedBy(camera: BackdropCamera): Modifier =
    onGloballyPositioned { camera.origin = it.positionInWindow() }
        .drawWithContent {
            camera.layer.record { this@drawWithContent.drawContent() }
            drawLayer(camera.layer)
        }

/**
 * The picture one host's panel stands over. [open] takes it the moment before the host's own change
 * of page, which it then runs; [backdrop] is what the panel's backdrop slot is handed.
 */
internal class BackdropShot(private val camera: BackdropCamera?) {
    private var photo by mutableStateOf<ImageBitmap?>(null)

    fun open(change: () -> Unit) {
        if (camera == null) {
            change()
        } else {
            camera.shoot { taken ->
                photo = taken
                change()
            }
        }
    }

    val backdrop: (@Composable () -> Unit)?
        get() = photo?.let { taken ->
            {
                // A screenful of pixels, let go when the panel's backdrop slot leaves rather than
                // when the host page does: the page may stay for an hour after the panel is closed.
                DisposableEffect(taken) { onDispose { if (photo === taken) photo = null } }
                BackdropPhoto(taken)
            }
        }
}

@Composable
internal fun rememberBackdropShot(): BackdropShot {
    val camera = LocalBackdropCamera.current
    return remember(camera) { BackdropShot(camera) }
}

/**
 * A picture from [LocalBackdropCamera], laid exactly where it was taken whatever box holds it: a
 * panel opened inside another panel stands lower in the window than the app the picture is of.
 */
@Composable
internal fun BackdropPhoto(photo: ImageBitmap) {
    val camera = LocalBackdropCamera.current
    var at by remember { mutableStateOf(Offset.Zero) }
    Spacer(
        Modifier.fillMaxSize()
            .onGloballyPositioned { at = it.positionInWindow() }
            .drawBehind {
                val origin = camera?.origin ?: at
                translate(origin.x - at.x, origin.y - at.y) { drawImage(photo) }
            },
    )
}

/**
 * The bar a thumb pulls the panel down by, and what says the panel can be pulled at all — not drawn
 * while a page holds the panel open, where no pull closes it; its row keeps its room, so nothing moves.
 */
@Composable
private fun SheetHandle(shown: Boolean) {
    Box(Modifier.fillMaxWidth().height(SHEET_HANDLE_ROW), contentAlignment = Alignment.Center) {
        if (!shown) return@Box
        Box(
            Modifier.width(SHEET_HANDLE_WIDTH)
                .height(SHEET_HANDLE_HEIGHT)
                // Decorative: the way out is the page's own back arrow, which a screen reader
                // already reaches, and a second unlabelled node in front of every page is noise.
                .clearAndSetSemantics { }
                // This bar is the only thing saying the page can be pulled shut, so it is held to
                // the 3:1 a meaningful graphic owes. The outline role is 4.13:1 on the panel in
                // daylight and 6.12:1 by night; the muted ink at half opacity would be 2.09:1 in
                // daylight.
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(SHEET_HANDLE_HEIGHT / 2)),
        )
    }
}

/**
 * How far out of focus the page behind a panel is drawn while the panel sits open.
 *
 * Large enough that nothing behind can be read — a page that can be read is a page that invites
 * being tapped, and it is not the one taking touches — and not so large that it stops being
 * recognisable as the page that is about to come back.
 */
private val BACKDROP_BLUR = 22.dp

/** Under this the effect costs a render pass and shows nothing, so it is dropped entirely. */
private const val MIN_BLUR_PX = 0.5f

/**
 * How much of the pull it takes to bring the page behind all the way back.
 *
 * Not the whole of it. Tied to the full travel, the page would only be in focus in the very last
 * frame, by which time the panel is off the bottom and nobody is looking at it any more. Cleared
 * three quarters of the way, the last part of the gesture is spent looking at the page being
 * returned to, with the panel still sliding off it — which is what the pull is for.
 */
private const val BACKDROP_CLEARED_BY = 0.75f

/** The pull, restretched so it reaches 1 at [BACKDROP_CLEARED_BY] rather than at the very end. */
private fun cleared(fraction: Float) = (fraction / BACKDROP_CLEARED_BY).coerceAtMost(1f)

/** The spatial spring's stiffness with no bounce, for movement that ends against an edge. */
internal fun <T> settleSpring() = spring<T>(Spring.DampingRatioNoBouncy, PwnMotion.spatial<T>().stiffness)

/** How much smaller the panel is drawn at the full length of a predictive back gesture. */
private const val BACK_SHRINK = 0.1f

/** How much of the screen behind the panel stays visible under the status bar. */
private val SHEET_PEEK = 10.dp
private val SHEET_CORNER = PwnCorners.extraLarge

/**
 * The handle's row at the top of every panel, and the bar drawn in it. Shared with the walk page,
 * whose map runs up over this row and whose own sheet over the map carries the same bar.
 */
internal val SHEET_HANDLE_ROW = 22.dp
internal val SHEET_HANDLE_WIDTH = 36.dp
internal val SHEET_HANDLE_HEIGHT = 4.dp

/**
 * The dim behind the panel. Black at a low alpha rather than a token, because it darkens whatever
 * the panel was opened over rather than colouring it, and it does the same by night as by day.
 */
private val PwnSheetScrim = Color.Black.copy(alpha = 0.32f)
