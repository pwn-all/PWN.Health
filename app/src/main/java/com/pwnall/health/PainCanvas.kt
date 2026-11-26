package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt

/** How far in a pinch may go. Five times shows a knuckle at the size a finger paints it. */
internal const val PAIN_ZOOM_MIN = 1f
internal const val PAIN_ZOOM_MAX = 5f

/**
 * Where the figure is looked at from: how far in, and how far across, in screen pixels.
 *
 * Plain arithmetic over [PlateFit], so the round trip and the clamping have tests: [toScreen] puts a
 * figure point where the frame shows it, [toFigure] takes a touch back to the figure. Pan is held
 * so the figure never leaves the view — zoomed out it stays centred, zoomed in it always fills the
 * frame on an axis it overflows.
 */
internal data class PainCamera(val zoom: Float = PAIN_ZOOM_MIN, val pan: Offset = Offset.Zero) {
    fun toScreen(point: Offset, fit: PlateFit): Offset = fit.place(point) * zoom + pan

    fun toFigure(screen: Offset, fit: PlateFit): Offset = fit.unplace((screen - pan) / zoom)

    /**
     * A pinch and a drag at once: zoom by [zoomBy] about [centroid], keeping the figure point under
     * it under it, then move by [panBy].
     */
    fun transformed(centroid: Offset, panBy: Offset, zoomBy: Float, fit: PlateFit, frame: Size): PainCamera {
        val next = (zoom * zoomBy).coerceIn(PAIN_ZOOM_MIN, PAIN_ZOOM_MAX)
        val factor = next / zoom
        return PainCamera(next, centroid - (centroid - pan) * factor + panBy).clamped(fit, frame)
    }

    /** This camera with its zoom inside 1..5 and its pan keeping the figure in the [frame]. */
    fun clamped(fit: PlateFit, frame: Size): PainCamera {
        val next = zoom.coerceIn(PAIN_ZOOM_MIN, PAIN_ZOOM_MAX)
        fun axis(pan: Float, origin: Float, extent: Float, window: Float): Float {
            val shown = extent * next
            return if (shown <= window) {
                (window - shown) / 2f - origin * next
            } else {
                pan.coerceIn(window - (origin + extent) * next, -origin * next)
            }
        }
        return PainCamera(
            next,
            Offset(
                axis(pan.x, fit.origin.x, fit.drawn.width, frame.width),
                axis(pan.y, fit.origin.y, fit.drawn.height, frame.height),
            ),
        )
    }
}

/** The figure — the plate, top to foot — fitted into a frame. */
internal fun figureFit(imageWidth: Float, imageHeight: Float, frame: Size) = PlateFit(
    Rect(0f, FIGURE_TOP * imageHeight, imageWidth, FIGURE_BOTTOM * imageHeight),
    imageWidth,
    imageHeight,
    frame,
)

/**
 * The brush: 28dp across on the screen whatever the zoom. Its radius is stored in plate heights, so
 * a stroke painted zoomed in is a finer line on the body than one painted with the figure whole.
 */
private val PAIN_BRUSH = 28.dp

/** A finger's move shorter than this adds no point; a stroke is a path, not a trail of samples. */
private val PAIN_STEP = 2.dp

/** How thick each of the highlighter's two edges is on the screen. */
private val PAIN_INK_EDGE = 1.5.dp

/**
 * The painting being made: its strokes, which plate is up, the camera, and what one finger does.
 *
 * Undo steps back through whole snapshots of the stroke list, so a Clear is one step like any
 * stroke and comes back with one tap — which is why Clear asks nothing — and a stroke goes with
 * what its caller changed for it ([alsoUndo]). The snapshots last only as long as the page is up:
 * a rotation keeps the painting and not them ([PAIN_DRAWING_SAVER]), and a Clear made before it
 * stands.
 */
@Stable
internal class PainDrawing(initial: List<PainStroke>, view: PainView) {
    val strokes = mutableStateListOf<PainStroke>().apply { addAll(initial) }
    private val history = mutableStateListOf<PainUndoStep>()

    /** The stroke under the finger, in figure units, until the finger lifts. */
    val current = mutableStateListOf<Offset>()
    var view by mutableStateOf(view)
    var camera by mutableStateOf(PainCamera())

    /** One finger moves the figure instead of painting, for anyone who cannot pinch. */
    var moving by mutableStateOf(false)

    /** A finger is on the figure: from its press to its lift. Whatever scrolls the page waits for it. */
    var touching by mutableStateOf(false)

    /**
     * Where the figure is to be framed once the canvas knows its size, in figure units: applied once
     * ([painFraming]) and then cleared, so the person's own zoom is never undone by it.
     */
    var focus by mutableStateOf<Rect?>(null)
    val canUndo: Boolean get() = history.isNotEmpty()

    fun commit(stroke: PainStroke) {
        history += PainUndoStep(strokes.toList())
        strokes += stroke
    }

    /**
     * Has the Undo of the stroke just committed also run [restore]: what its caller changed beside the
     * painting for it, called from [PainCanvas]'s `onStroke`. A stroke over an area struck off the list
     * marks it again; taken back without that, it would leave the area marked that the person had struck off.
     */
    fun alsoUndo(restore: () -> Unit) {
        history.lastOrNull()?.restore = restore
    }

    fun undo() {
        val step = history.removeLastOrNull() ?: return
        strokes.clear()
        strokes += step.strokes
        step.restore()
    }

    /** Whether [stroke] is on the figure as it is shown: on the view that is up, and on the plate. */
    fun showing(stroke: PainStroke): Boolean = stroke.view == view && paintedOnPlate(stroke)

    /**
     * Takes what the figure shows ([showing]) and leaves the other view's strokes, which the person
     * cannot see go: a Clear on the front would otherwise take the back's painting with it.
     */
    fun clear() {
        if (strokes.none { showing(it) }) return
        history += PainUndoStep(strokes.toList())
        strokes.removeAll { showing(it) }
    }

    /**
     * Turns the figure to [next]. A stroke in progress is dropped: its points are places on the plate
     * it was begun on, and left standing they would be drawn on the other one and joined to the next
     * touch there — the person's right side painted as their left.
     */
    fun show(next: PainView) {
        current.clear()
        view = next
        camera = PainCamera()
    }
}

/** One step Undo takes back: the strokes before it, and what its caller changed beside them. */
private class PainUndoStep(val strokes: List<PainStroke>) {
    var restore: () -> Unit = {}
}

/**
 * Kept across a rotation as plain numbers: a view, a radius, then the points, per stroke. The
 * payload's own writer is not used for this because it gives up past its cap, and a turned phone
 * must not lose the painting it is holding.
 */
internal val PAIN_DRAWING_SAVER = Saver<PainDrawing, List<Any>>(
    save = { drawing ->
        listOf<Any>(drawing.view.ordinal) + drawing.strokes.map { stroke ->
            floatArrayOf(stroke.view.ordinal.toFloat(), stroke.radius) +
                stroke.points.flatMap { listOf(it.x, it.y) }.toFloatArray()
        }
    },
    restore = { saved ->
        val strokes = saved.drop(1).filterIsInstance<FloatArray>().map { flat ->
            PainStroke(
                PainView.entries[flat[0].toInt()],
                flat[1],
                (2 until flat.size step 2).map { Offset(flat[it], flat[it + 1]) },
            )
        }
        PainDrawing(strokes, PainView.entries[saved.first() as Int])
    },
)

@Composable
internal fun rememberPainDrawing(initial: List<PainStroke>): PainDrawing =
    rememberSaveable(saver = PAIN_DRAWING_SAVER) {
        PainDrawing(initial, initial.firstOrNull()?.view ?: PainView.FRONT)
    }

/**
 * A painting kept for [key], begun from [initial] on [view] and framed on [focus] once shown: a new
 * [key] — another thread's map — is a painting of its own, never the last one's. Kept across a
 * rotation as the page's is, which loses the zoom and does not frame it again.
 */
@Composable
internal fun rememberPainDrawing(
    key: Any?,
    initial: List<PainStroke>,
    view: PainView,
    focus: Rect? = null,
): PainDrawing =
    rememberSaveable(key, saver = PAIN_DRAWING_SAVER) { PainDrawing(initial, view).also { it.focus = focus } }

/**
 * The camera that shows [target], in figure units, filling [frame] as far as [maxZoom] lets it —
 * never past [PAIN_ZOOM_MAX] — centred on it, and kept to the figure as every camera is.
 */
internal fun painFraming(target: Rect, fit: PlateFit, frame: Size, maxZoom: Float = PAIN_FRAMING_ZOOM): PainCamera {
    val topLeft = fit.place(target.topLeft)
    val bottomRight = fit.place(target.bottomRight)
    val width = (bottomRight.x - topLeft.x).coerceAtLeast(1f)
    val height = (bottomRight.y - topLeft.y).coerceAtLeast(1f)
    val zoom = minOf(frame.width / width, frame.height / height)
        .coerceIn(PAIN_ZOOM_MIN, minOf(maxZoom, PAIN_ZOOM_MAX).coerceAtLeast(PAIN_ZOOM_MIN))
    val centre = Offset((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f)
    return PainCamera(zoom, Offset(frame.width / 2f, frame.height / 2f) - centre * zoom).clamped(fit, frame)
}

/** How far a map framed on a part of the body zooms in at most: the part in view, with the body around it. */
internal const val PAIN_FRAMING_ZOOM = 2.5f

/** The framing asked for ([PainDrawing.focus]), applied on a surface of [size] showing [plate], then cleared. */
private fun PainDrawing.applyFocus(plate: ImageBitmap, size: IntSize) {
    val target = focus ?: return
    val frame = Size(size.width.toFloat(), size.height.toFloat())
    camera = painFraming(target, figureFit(plate.width.toFloat(), plate.height.toFloat(), frame), frame)
    focus = null
}

/*
 * The highlighter: a translucent amber fill inside two opaque edges, one dark and one pale.
 *
 * A stroke lies over two very different grounds — the drained body (plate grey at 42% on the panel,
 * #C0C2C5 on average by day, #43484D at night) and, over an area chosen from the list, the plate in
 * full colour, a red photograph (#D86A60 on average) with shadows near black and highlights near
 * white. No single edge clears 3:1 on all of them: an edge of #7A4F00, for one, is 4.0:1 on the
 * average grey but 2.1:1 on the average red. So the edge is two colours, the way a two-colour focus
 * ring is (WCAG technique C40): #2B1C00 and #FFF8E1, 15.6:1 against each other, which leaves one of
 * the two at 3:1 or more against anything. Measured over every opaque pixel of the four plates, the
 * better of the two is never under 3.95:1 — on the grey body in either theme and on the lit plate
 * alike. The outer edge is the one that stands out against the idle body: dark by day (4.9:1 at
 * worst, 9.1:1 typically), pale at night (3.9:1 and 9.0:1).
 *
 * The fill is a highlighter's, #FFC107 by day and #FFEB3B at night at 45%: it shows the plate
 * through and is not what marks the stroke's shape — the edges are.
 */
@Composable
private fun painInkFill(): Color = darkOr(Color(0xFFFFEB3B), Color(0xFFFFC107))

private val PAIN_INK_DARK = Color(0xFF2B1C00)
private val PAIN_INK_PALE = Color(0xFFFFF8E1)

/** The outer edge, which meets the idle body: whichever of the two stands out on this theme's grey. */
@Composable
private fun painInkOuter(): Color = darkOr(PAIN_INK_PALE, PAIN_INK_DARK)

/** And the inner one, which meets the outer edge and the fill. */
@Composable
private fun painInkInner(): Color = darkOr(PAIN_INK_DARK, PAIN_INK_PALE)

/** How much of the fill the plate shows through: a highlighter's, not a marker's. */
private const val PAIN_INK_ALPHA = 0.45f

/**
 * How strongly one entry's painting shows on the summary's body: a third of full colour for one, so
 * an area painted on three entries shows at about three quarters and one painted on five at nine
 * tenths. One entry starts where the areas lit by their days beside it start.
 */
internal const val PAIN_MARK_ALPHA = 0.35f

/**
 * One view of the body with its areas lit and its paintings shown.
 *
 * [lit] maps an area to how strongly it is shown, 1 for full colour: the workout technique, the plate
 * drawn grey and then in colour through the lit patches — the patches of [map], the map the figure is
 * drawn on, and for an area that map has carved up whole, the patches of its parts ([litOn]). [marks]
 * are paintings, one list of strokes per entry, shown as the plate in colour through the strokes
 * themselves — exactly where they were painted, never the whole of the area they fell on — each entry
 * at [PAIN_MARK_ALPHA], so a place painted on more entries shows stronger.
 */
@Composable
internal fun PainFigure(
    view: PainView,
    sex: String,
    lit: Map<RegionSide, Float>,
    modifier: Modifier = Modifier,
    camera: PainCamera = PainCamera(),
    marks: List<List<PainStroke>> = emptyList(),
    map: PainMapVersion = PainMapVersion.CURRENT,
) {
    val plate = ImageBitmap.imageResource(bodyPlateRes(sex, view == PainView.FRONT))
    val grey = remember { bodyPlateGrey() }
    Canvas(modifier) {
        val fit = figureFit(plate.width.toFloat(), plate.height.toFloat(), size)
        withTransform({
            translate(camera.pan.x, camera.pan.y)
            scale(camera.zoom, camera.zoom, Offset.Zero)
        }) {
            drawPainBody(plate, grey, view, fit, lit, map)
            drawPainMarks(plate, view, fit, marks)
        }
    }
}

private fun DrawScope.drawPainBody(
    plate: ImageBitmap,
    grey: ColorFilter,
    view: PainView,
    fit: PlateFit,
    lit: Map<RegionSide, Float>,
    map: PainMapVersion,
) {
    val strength = litStrengths(lit, map)
    val patches = sidePatches(view, map).filter { it.area in strength }
    // Full colour through one mask, the workout map's own two passes; anything fainter — the
    // summary's days — gets a pass of its own at its own strength.
    val (full, faint) = patches.partition { strength.getValue(it.area) >= 1f }
    val mask = full.takeIf { it.isNotEmpty() }?.let { patchPath(it, fit) }
    val pass: (ColorFilter?, Float) -> Unit = { filter, alpha -> drawWholePlate(plate, fit, filter, alpha) }
    drawLitPlate(grey, mask, pass)
    faint.forEach { patch -> clipPath(patchPath(listOf(patch), fit)) { pass(null, strength.getValue(patch.area)) } }
}

/**
 * How strongly each area of [map] is lit, from [lit]: each area lit through the areas that show it on
 * [map] ([litOn]) — an area [map] carved up whole through its parts — and an area lit twice over, by
 * itself and by the parent carved into it, at the stronger of the two. Nothing for an area at nought.
 */
internal fun litStrengths(lit: Map<RegionSide, Float>, map: PainMapVersion): Map<RegionSide, Float> {
    val strength = HashMap<RegionSide, Float>()
    lit.filterValues { it > 0f }.forEach { (area, value) ->
        area.litOn(map).forEach { shown -> strength[shown] = maxOf(strength[shown] ?: 0f, value) }
    }
    return strength
}

/** The plate in its frame, whole: one pass of the body. */
private fun DrawScope.drawWholePlate(
    plate: ImageBitmap,
    fit: PlateFit,
    filter: ColorFilter?,
    alpha: Float,
    blend: BlendMode = BlendMode.SrcOver,
) = drawImage(
    image = plate,
    srcOffset = IntOffset.Zero,
    srcSize = IntSize(plate.width, plate.height),
    dstOffset = fit.place(Offset.Zero).round(),
    dstSize = IntSize((fit.imageWidth * fit.scale).roundToInt(), (fit.imageHeight * fit.scale).roundToInt()),
    alpha = alpha,
    colorFilter = filter,
    blendMode = blend,
    filterQuality = FilterQuality.High,
)

/**
 * Each entry's strokes on [view] as a layer of its own over the plate: the strokes laid down opaque,
 * the plate drawn into them in colour, and the layer put down at [PAIN_MARK_ALPHA]. Within one entry
 * crossing strokes do not darken; across entries they do. The layer is the plate's own rectangle, so
 * a stroke painted on the body that runs up above the plate is cut off at the neck; one painted
 * wholly above it, on the head, is not among [marks] ([painShownStrokes]).
 */
private fun DrawScope.drawPainMarks(plate: ImageBitmap, view: PainView, fit: PlateFit, marks: List<List<PainStroke>>) {
    val shown = marks.map { entry -> entry.filter { it.view == view } }.filter { it.isNotEmpty() }
    if (shown.isEmpty()) return
    val bounds = Rect(fit.place(Offset.Zero), fit.place(Offset(1f, 1f)))
    val layer = Paint().apply { alpha = PAIN_MARK_ALPHA }
    val perHeight = fit.scale * fit.imageHeight
    shown.forEach { strokes ->
        drawContext.canvas.saveLayer(bounds, layer)
        strokes.forEach { drawStroke(it, fit, it.radius * perHeight, Color.Black, BlendMode.SrcOver) }
        drawWholePlate(plate, fit, null, 1f, BlendMode.SrcIn)
        drawContext.canvas.restore()
    }
}

private fun patchPath(patches: List<SidePatch>, fit: PlateFit): Path = Path().apply {
    patches.forEach { addPath(polygon(it.points.map(fit::place))) }
}

/**
 * The painting surface: the figure, the strokes over it, and the four small controls in its corner.
 *
 * What the figure shows is what the person painted: the strokes, and nothing more. The areas a
 * painting marks are named in the list and the description, but never lit — a dab on the right of
 * the lower back marks the loin, and lighting all of it would show pain the person never painted.
 * Only [lit] is lit whole: the areas chosen by name from the list with nothing painted on them
 * ([painLitAreas]). The figure is [map]'s, the map the entry is painted on ([PainReport.map]).
 *
 * One pointer handler owns every touch from the moment it lands — consumed at once, not after a
 * slop — so the app-wide sideways swipe, the pull that closes a panel and the page's own scroll
 * never see a stroke. One finger paints. A second finger drops the stroke in progress (the first
 * finger of every pinch lands before the second, and would otherwise leave a dab) and turns the
 * rest of the gesture into a pinch and a pan. [PainDrawing.moving] makes one finger pan.
 *
 * To a screen reader the surface is one node that says what is marked, and double-tapping it opens
 * the list of areas ([onListAreas]). Without a click of its own, a double tap on a node with nothing
 * to click is sent on as a touch at its centre — a dab on the belly nobody chose.
 *
 * [commitAs] is what a finished touch is kept as — the page keeps it as painted; the chat keeps a tap
 * as a dab sized to the areas under the finger — and null keeps nothing: nothing is drawn and
 * [onStroke] is not called. The touch is handed to it as the payload stores a stroke ([quantised]), so
 * no later reader of the saved row sees another stroke than the one the areas were read off here. A
 * [PainDrawing.focus] is framed once the surface knows its size.
 */
@Composable
internal fun PainCanvas(
    drawing: PainDrawing,
    sex: String,
    lit: Map<RegionSide, Float>,
    description: String,
    onStroke: (PainStroke) -> Unit,
    onCleared: () -> Unit,
    onListAreas: () -> Unit,
    modifier: Modifier = Modifier,
    map: PainMapVersion = PainMapVersion.CURRENT,
    commitAs: (PainStroke) -> PainStroke? = { it },
) {
    val strokeDone by rememberUpdatedState(onStroke)
    val kept by rememberUpdatedState(commitAs)
    val listLabel = stringResource(R.string.pain_add_area)
    val plate = ImageBitmap.imageResource(bodyPlateRes(sex, drawing.view == PainView.FRONT))
    val density = LocalDensity.current
    val brushRadius = with(density) { PAIN_BRUSH.toPx() } / 2f
    val step = with(density) { PAIN_STEP.toPx() }
    val edge = with(density) { PAIN_INK_EDGE.toPx() }
    val fill = painInkFill()
    val outer = painInkOuter()
    val inner = painInkInner()
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    // A framing asked for once the surface already has its size: applied as soon as it is asked.
    LaunchedEffect(drawing.focus, frameSize, plate) {
        if (frameSize != IntSize.Zero) drawing.applyFocus(plate, frameSize)
    }
    Box(modifier) {
        Box(
            Modifier.matchParentSize()
                .clipToBounds()
                // In the layout pass, so a map that opens framed never shows the whole body first.
                .onSizeChanged { size ->
                    frameSize = size
                    drawing.applyFocus(plate, size)
                }
                .background(musclePanel())
                .semantics {
                    contentDescription = description
                    onClick(label = listLabel) {
                        onListAreas()
                        true
                    }
                }
                .pointerInput(plate) {
                    awaitEachGesture {
                        val frame = Size(size.width.toFloat(), size.height.toFloat())
                        val fit = figureFit(plate.width.toFloat(), plate.height.toFloat(), frame)
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        drawing.touching = true
                        var transforming = drawing.moving
                        var last = down.position
                        if (!transforming) drawing.current += drawing.camera.toFigure(down.position, fit)
                        // Let go however the gesture ends — lifted, or cut off when the view is switched
                        // under a finger — or a chat would wait for a finger that is no longer there. The
                        // stroke in progress goes with a gesture cut off, for the same reason: nothing
                        // after the cut runs, and its points would stand as a stroke no Undo reaches.
                        val points = try {
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (!transforming && pressed > 1) {
                                    transforming = true
                                    drawing.current.clear()
                                }
                                if (transforming && pressed > 0) {
                                    drawing.camera = drawing.camera.transformed(
                                        event.calculateCentroid(useCurrent = true),
                                        event.calculatePan(),
                                        event.calculateZoom(),
                                        fit,
                                        frame,
                                    )
                                } else if (!transforming) {
                                    event.changes.firstOrNull { it.id == down.id && it.pressed }
                                        ?.takeIf { (it.position - last).getDistance() >= step }
                                        ?.let {
                                            last = it.position
                                            drawing.current += drawing.camera.toFigure(it.position, fit)
                                        }
                                }
                                event.changes.forEach { it.consume() }
                            } while (pressed > 0)
                            drawing.current.toList()
                        } finally {
                            drawing.touching = false
                            drawing.current.clear()
                        }
                        if (!transforming && points.isNotEmpty()) {
                            val radius = brushRadius / (fit.scale * fit.imageHeight * drawing.camera.zoom)
                            // In the payload's own thousandths from the start, so the areas read off
                            // the stroke now are the areas the saved row's readers read off it.
                            val painted = quantised(PainStroke(drawing.view, radius, simplifyStroke(points)))
                            // What the page's caller keeps of a touch — the chat a tap as a dab, none
                            // for a tap off the body — is what is committed and drawn.
                            kept(painted)?.let { stroke ->
                                drawing.commit(stroke)
                                strokeDone(stroke)
                            }
                        }
                    }
                },
        ) {
            PainFigure(
                drawing.view,
                sex,
                lit,
                Modifier.fillMaxSize(),
                drawing.camera,
                map = map,
            )
            // Its own layer, so the strokes are composed with each other before they meet the body:
            // where two cross, the highlighter does not darken.
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val fit = figureFit(plate.width.toFloat(), plate.height.toFloat(), size)
                val camera = drawing.camera
                // Plate pixels a plate height spans on the screen before the camera's zoom.
                val perHeight = fit.scale * fit.imageHeight
                val painting = drawing.current.toList()
                // Not the strokes an entry holds above the plate, on the head: a dab there
                // reaches over the top edge and would print a cap of ink on the neck, and the Clear
                // below would not count it. They are kept and saved back untouched.
                val shown = drawing.strokes.filter(drawing::showing) +
                    listOfNotNull(
                        painting.takeIf { it.isNotEmpty() }
                            ?.let { PainStroke(drawing.view, brushRadius / (perHeight * camera.zoom), it) },
                    )
                val translucent = fill.copy(alpha = PAIN_INK_ALPHA)
                withTransform({
                    translate(camera.pan.x, camera.pan.y)
                    scale(camera.zoom, camera.zoom, Offset.Zero)
                }) {
                    // Every outer edge first, then every inner one, then every fill, the fills
                    // replacing what is under them inside the layer: the edges ring the painted
                    // shape as a whole, never a stroke inside it.
                    val ring = edge / camera.zoom
                    shown.forEach { drawStroke(it, fit, it.radius * perHeight + 2 * ring, outer, BlendMode.SrcOver) }
                    shown.forEach { drawStroke(it, fit, it.radius * perHeight + ring, inner, BlendMode.SrcOver) }
                    shown.forEach { drawStroke(it, fit, it.radius * perHeight, translucent, BlendMode.Src) }
                }
            }
        }
        PainControls(
            drawing,
            onClear = {
                drawing.clear()
                onCleared()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(PAIN_CONTROLS_INSET),
        )
    }
}

private const val PAIN_CONTROL_COUNT = 4
private val PAIN_CONTROL_GAP = 4.dp
private val PAIN_CONTROLS_INSET = 6.dp

/**
 * The least height the canvas can be drawn at and still stand its column of buttons at full target
 * size: a column handed less gives its last button — the one-finger move — only what is left over.
 */
internal val PAIN_CANVAS_MIN_HEIGHT =
    PwnDimensions.minimumTarget * PAIN_CONTROL_COUNT + PAIN_CONTROL_GAP * (PAIN_CONTROL_COUNT - 1) +
        PAIN_CONTROLS_INSET * 2

/** One stroke, round at both ends and at every turn, [halfWidth] either side of its path. */
private fun DrawScope.drawStroke(
    stroke: PainStroke,
    fit: PlateFit,
    halfWidth: Float,
    color: Color,
    blend: BlendMode,
) {
    val points = stroke.points.map(fit::place)
    if (points.size == 1) {
        drawCircle(color, halfWidth, points[0], blendMode = blend)
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path,
        color,
        style = Stroke(width = halfWidth * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = blend,
    )
}

/**
 * Undo, clear, back to the whole figure, and one finger to move — the map's own small buttons, in a
 * column down the edge beside the figure, where a row across the top would sit over its shoulders.
 */
@Composable
private fun PainControls(drawing: PainDrawing, onClear: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PAIN_CONTROL_GAP)) {
        FilledTonalIconButton(onClick = drawing::undo, enabled = drawing.canUndo) {
            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.pain_undo), tint = PwnMuted)
        }
        // Open only over what Clear takes, the strokes the figure shows: an entry's strokes above the
        // plate, or the other view's, would leave it seeming to do nothing.
        FilledTonalIconButton(onClick = onClear, enabled = drawing.strokes.any(drawing::showing)) {
            Icon(Icons.Outlined.LayersClear, stringResource(R.string.pain_clear), tint = PwnMuted)
        }
        FilledTonalIconButton(
            onClick = { drawing.camera = PainCamera() },
            enabled = drawing.camera.zoom > PAIN_ZOOM_MIN,
        ) {
            Icon(Icons.Outlined.FitScreen, stringResource(R.string.pain_reset_zoom), tint = PwnMuted)
        }
        FilledTonalIconToggleButton(checked = drawing.moving, onCheckedChange = { drawing.moving = it }) {
            Icon(Icons.Outlined.PanTool, stringResource(R.string.pain_move))
        }
    }
}
