package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import java.time.ZoneId
import java.util.Locale

// The pain thread's own replies and bubbles: the area chips the words proposed, the in-chat body map
// with its list and the head's chips — its own item of the list, its state held by the conversation —
// the 1–10 and "how long did it last" rulers, the sent painting as the person's bubble, and the lines
// of "the same pain?". Each reuses the pain page's own pieces; none is a dialog.

/**
 * What a thread's map opens with: its painting and the areas added and struck off by hand, the view
 * it is on, and where it is framed ([painFrameTarget]; none for the whole body).
 */
internal data class MapStart(
    val strokes: List<PainStroke>,
    val added: List<String>,
    val removed: List<String>,
    val view: PainView,
    val focus: Rect?,
)

/**
 * The map's start for [thread], asked with [reply]: the painting not yet sent ([ChatThread.draft]);
 * the answer sent before, when the area is asked again — its codes split back beside its painting
 * ([chatMapLists]); for the pain taken up after the chest's questions, what the pain thread before
 * them marked; else an empty body on the view the words point at, framed on the part they named.
 */
internal fun mapStart(thread: ChatThread?, state: ChatState?, reply: ChatReply?): MapStart {
    val frame = (reply as? ChatReply.BodyMap)?.frame ?: (reply as? ChatReply.AreaChips)?.frame
    val view = frame?.view ?: PainView.FRONT
    val draft = thread?.draft
    val own = thread?.report?.takeIf { it.strokes.isNotEmpty() || !it.values[SymptomStep.PAIN_AREA].isNullOrEmpty() }
    val source = own ?: state?.thread?.resumed?.takeIf { state.thread.from?.startsWith(ROUTE_RESUME) == true }
    return when {
        draft != null -> {
            val drawn = draft.strokes.firstOrNull()?.view ?: view
            MapStart(draft.strokes, draft.added, draft.removed, drawn, null)
        }

        source != null -> {
            val (added, removed) = chatMapLists(source.values[SymptomStep.PAIN_AREA], source.strokes)
            MapStart(source.strokes, added, removed, source.strokes.firstOrNull()?.view ?: view, null)
        }

        else -> {
            val framed = (reply as? ChatReply.BodyMap)?.takeIf { !it.head }?.frame?.let { painFrameTarget(it) }
            MapStart(emptyList(), emptyList(), emptyList(), view, framed)
        }
    }
}

/**
 * The body map of the one thread whose area question is open, held by the conversation and never by
 * the list's item, which a rotation, a message or the dots would take away: the painting, the areas
 * added and struck off by hand, and what is unfolded — the canvas, the list of areas, the head's
 * chips. [missed] is a touch that marked nothing, which the hint under the map answers.
 */
@Stable
internal class ChatMapState(
    val thread: Int,
    val drawing: PainDrawing,
    added: MutableState<List<String>>,
    removed: MutableState<List<String>>,
    canvas: MutableState<Boolean>,
    list: MutableState<Boolean>,
    head: MutableState<Boolean>,
    framed: MutableState<Boolean>,
    missed: MutableState<Boolean>,
) {
    var added by added
    var removed by removed
    var canvas by canvas
    var list by list
    var head by head
    private var framed by framed
    var missed by missed

    /** What the painting marks, read the chat's way ([chatMarkedAreas]). */
    val painted: Set<RegionSide> by derivedStateOf { chatMarkedAreas(drawing.strokes.toList()) }

    /** What the map records: the painting's areas, less what was struck off, plus what was added. */
    val marked: Set<RegionSide> by derivedStateOf {
        painMarkedAreas(painted, this.added.mapNotNull(RegionSide::parse), this.removed.mapNotNull(RegionSide::parse))
    }

    /** [area] taken off: added by hand, it goes; painted, it is struck off — the page's own rule. */
    fun remove(area: RegionSide) {
        added = added - area.code
        if (area in painted) removed = (removed + area.code).distinct()
    }

    /** [area] ticked or taken off, as a row of the page's list does it. */
    fun toggle(area: RegionSide) {
        if (area in marked) {
            remove(area)
        } else {
            removed = removed - area.code
            if (area !in painted) added = (added + area.code).distinct()
        }
    }

    /** Several areas at once — a chip's, both sides — marked together, or taken off together when all are. */
    fun toggleAll(areas: List<RegionSide>) {
        if (areas.all { it in marked }) areas.forEach(::remove) else areas.filter { it !in marked }.forEach(::toggle)
    }

    /** "Show on the body": the canvas unfolded, framed once on the part of the body the words named. */
    fun unfold(frame: PainFrame?) {
        canvas = true
        if (framed || frame == null) return
        framed = true
        if (drawing.view != frame.view) drawing.show(frame.view)
        drawing.focus = painFrameTarget(frame)
    }
}

/**
 * The map of thread [thread] (none open: any key), begun from [start] the first time it is asked
 * for that thread and kept across a rotation; another thread's map is a new one.
 */
@Composable
internal fun rememberChatMapState(thread: Int?, start: () -> MapStart): ChatMapState {
    val begun = remember(thread) { start() }
    val drawing = rememberPainDrawing(key = thread, initial = begun.strokes, view = begun.view, focus = begun.focus)
    val added = rememberSaveable(thread) { mutableStateOf(begun.added) }
    val removed = rememberSaveable(thread) { mutableStateOf(begun.removed) }
    val canvas = rememberSaveable(thread) { mutableStateOf(begun.strokes.isNotEmpty()) }
    val list = rememberSaveable(thread) { mutableStateOf(false) }
    val head = rememberSaveable(thread) { mutableStateOf(false) }
    val framed = rememberSaveable(thread) { mutableStateOf(begun.focus != null) }
    val missed = rememberSaveable(thread) { mutableStateOf(false) }
    return remember(drawing, thread) {
        ChatMapState(thread ?: -1, drawing, added, removed, canvas, list, head, framed, missed)
    }
}

/** Whether the canvas of [map] is out: unfolded by hand, or the question is the map itself (not the head's chips). */
internal fun mapCanvasOpen(map: ChatMapState, reply: ChatReply?): Boolean =
    map.canvas || (reply is ChatReply.BodyMap && !reply.head)

/**
 * Whether the area question is the head's chips with the canvas folded: then the conversation's clock
 * takes the ticks, three seconds after the last, as a question of several answers.
 */
internal fun mapTakenByClock(map: ChatMapState, reply: ChatReply?): Boolean =
    reply is ChatReply.BodyMap && reply.head && !map.canvas && !map.list

/**
 * How tall the in-chat map is drawn: about half of the tallest the list has been at this width — so
 * the keyboard never shrinks it — no wider a figure than leaves the controls their column, and room
 * above and below to scroll the chat by; never shorter than the column of its buttons.
 */
internal fun chatMapHeight(tallest: Dp, width: Dp): Dp {
    val row = width - LIST_SIDES
    val byViewport = tallest * MAP_SHARE
    val byWidth = (row - CONTROLS_ROOM) / PLATE_ASPECT
    val byRoom = tallest - SCROLL_ROOM
    return minOf(byViewport, byWidth, byRoom).coerceAtLeast(PAIN_CANVAS_MIN_HEIGHT)
}

private const val MAP_SHARE = 0.55f
private val LIST_SIDES = 32.dp
private val CONTROLS_ROOM = 108.dp
private val SCROLL_ROOM = 160.dp

/**
 * The map's own item of the list: drawn in as every reply is when it [arrive]s, but without the rise
 * ([ReplyReveal]) — never slide a painter that can be touched — and out, once sent, folding as a form's
 * section does while the person's bubble comes in, as every reply that folds away does ([FoldingReply]):
 * taking no touch and saying nothing as it goes. [onGone] when it has gone.
 */
@Composable
internal fun PainMapItem(
    visible: Boolean,
    arrive: Boolean,
    animated: Boolean,
    onGone: () -> Unit,
    content: @Composable () -> Unit,
) {
    FoldingReply(visible, animated, onGone) { ReplyReveal(arrive, rise = false) { content() } }
}

/**
 * The area chips ([ChatReply.AreaChips]): a tap is the answer — the both-sides chip answers both —
 * and beside them, never among the answers, "Show on the body" (not for a place the plates do not
 * show) and "Choose from the list". Once either is unfolded, the chips mark and unmark their areas on
 * the map, and the map's Send answers with what is marked.
 */
@Composable
internal fun PainAreaChipsReply(reply: ChatReply.AreaChips, inputs: ReplyInputs, actions: ReplyActions) {
    val resources = LocalResources.current
    val map = inputs.map?.takeIf { it.thread == inputs.thread }
    val toggles = map != null && (map.canvas || map.list)
    val given = inputs.report.values[reply.step].orEmpty()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReplyRow(single = !toggles) {
            reply.choices.forEach { choice ->
                val label = resources.areaChoiceLabel(choice)
                if (map != null && toggles) {
                    PwnFilterChip(
                        selected = choice.areas.all { it in map.marked },
                        onClick = { map.toggleAll(choice.areas) },
                        label = label,
                    )
                } else {
                    val codes = choice.areas.joinToString(",") { it.code }
                    PainAnswerChip(given == codes, { actions.onValue(reply.step, codes) }, label)
                }
            }
        }
        if (map != null) {
            ReplyRow(single = false) {
                if (reply.frame != null && !map.canvas) {
                    PwnOutlinedButton(onClick = { map.unfold(reply.frame) }) {
                        Icon(Icons.Outlined.Accessibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.pain_chat_show_on_body))
                    }
                }
                if (!map.list) {
                    PwnOutlinedButton(onClick = { map.list = true }) {
                        Text(stringResource(R.string.pain_chat_choose_list))
                    }
                }
            }
        }
    }
}

/** An area chip's words: "Knee · right", or for both sides "Knee · both sides". */
internal fun Resources.areaChoiceLabel(choice: AreaChoice): String {
    val region = choice.areas.firstOrNull()?.region
    return if (choice.both && region != null) {
        getString(R.string.pain_chat_both_sides, getString(region.labelRes))
    } else {
        choice.areas.joinToString(getString(R.string.list_comma)) { painArea(it) }
    }
}

/** Areas only the list can hold, offered beside the map as chips: no plate shows the fingers or the soles. */
private val UNDRAWN_EXTRAS = listOf(PainRegion.FINGERS, PainRegion.SOLE)

/** Whether some view of the current map paints [region]. */
private fun drawable(region: PainRegion): Boolean =
    PainView.entries.any { view -> sidePatches(view, PainMapVersion.CURRENT).any { it.area.region == region } }

/**
 * The in-chat body map, its own item of the list: the view, the painter — a tap a dab of
 * the areas under the finger, a stroke as painted — the areas it records, the list of every area,
 * chips for the places no plate shows and the head's chips, and Send, on only while something is
 * marked. The head's chips alone, with the canvas folded, are taken by the clock. Every change is
 * handed to the conversation, which keeps it with the chat as the page stops.
 */
@Composable
internal fun ChatPainMap(map: ChatMapState, reply: ChatReply, sex: String, height: Dp, actions: ReplyActions) {
    val resources = LocalResources.current
    val headQuestion = reply is ChatReply.BodyMap && reply.head
    val canvasOpen = mapCanvasOpen(map, reply)
    val headOpen = map.head || headQuestion
    val step = SymptomStep.PAIN_AREA
    val paint by rememberUpdatedState(actions.onPaint)
    val hold by rememberUpdatedState(actions.onHeld)
    LaunchedEffect(map) {
        snapshotFlow { Triple(map.drawing.strokes.toList(), map.added, map.removed) }
            .drop(1)
            .collect { (strokes, added, removed) -> paint(step, strokes, added, removed) }
    }
    LaunchedEffect(map) { snapshotFlow { map.drawing.touching }.collect { hold(it) } }
    // A map that leaves under a finger — folded away, hidden by typing — never leaves the clock held:
    // the lift it waited for goes with it.
    DisposableEffect(Unit) { onDispose { hold(false) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InlineReveal(canvasOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PwnSegmentedControl(
                    options = PainView.entries.map { stringResource(viewLabel(it)) to null },
                    selected = map.drawing.view.ordinal,
                    onSelected = { map.drawing.show(PainView.entries[it]) },
                )
                MapCanvas(map, sex, height, resources)
                PainAreas(map.marked, onOpenList = { map.list = true }, onRemove = map::remove)
            }
        }
        InlineReveal(map.list) { PainAreaList(map.marked, PainMapVersion.CURRENT, map::toggle, height) }
        if (canvasOpen) MapExtraChips(map, headQuestion)
        InlineReveal(headOpen) { PainHeadChips((reply as? ChatReply.BodyMap)?.headChips.orEmpty(), map) }
        if (headQuestion && !canvasOpen) {
            ReplyRow(single = false) {
                PwnOutlinedButton(onClick = { map.unfold(null) }) {
                    Icon(Icons.Outlined.Accessibility, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.pain_chat_show_on_body))
                }
            }
        }
        if (canvasOpen || map.list) {
            MapFootRow(map) {
                paint(step, map.drawing.strokes.toList(), map.added, map.removed)
                actions.onSend(step)
            }
        }
    }
}

/** The view's name on its segment: the pain page's own words. */
private fun viewLabel(view: PainView): Int =
    if (view == PainView.FRONT) R.string.pain_view_front else R.string.pain_view_back

/** The painter, keyed to its thread: its gestures hold the drawing they began with. */
@Composable
private fun MapCanvas(map: ChatMapState, sex: String, height: Dp, resources: Resources) {
    val side = stringResource(viewName(map.drawing.view))
    val marked = map.marked
    val comma = stringResource(R.string.list_comma)
    val description = if (marked.isEmpty()) {
        stringResource(R.string.pain_canvas_nothing, side)
    } else {
        stringResource(R.string.pain_canvas_marked, side, marked.joinToString(comma) { resources.painArea(it) })
    }
    key(map.thread) {
        PainCanvas(
            drawing = map.drawing,
            sex = sex,
            lit = painLitAreas(marked, map.painted),
            description = description,
            // A stroke over an area struck off the list marks it again, as on the page, and its Undo
            // strikes the area off again ([PainDrawing.alsoUndo]).
            onStroke = { stroke ->
                val back = map.removed.intersect(chatMarkedAreas(listOf(stroke)).map { it.code }.toSet())
                map.removed = map.removed - back
                map.drawing.alsoUndo { map.removed = (map.removed + back).distinct() }
            },
            onCleared = {},
            onListAreas = { map.list = true },
            modifier = Modifier.fillMaxWidth().height(height),
            map = PainMapVersion.CURRENT,
            commitAs = { stroke ->
                chatStroke(stroke).also { kept ->
                    map.missed = kept == null || chatMarkedAreas(listOf(kept)).isEmpty()
                }
            },
        )
    }
}

/**
 * Chips for the places no plate shows: the head and face, the fingers and the soles of the feet, each on its side.
 */
@Composable
private fun MapExtraChips(map: ChatMapState, headQuestion: Boolean) {
    val resources = LocalResources.current
    val extras = UNDRAWN_EXTRAS.filterNot(::drawable).flatMap { region ->
        listOf(RegionSide(region, BodySide.RIGHT), RegionSide(region, BodySide.LEFT))
    }
    ReplyRow(single = false) {
        if (!headQuestion && HEAD_CHIPS.none { drawable(it.region) }) {
            PwnFilterChip(
                selected = map.head,
                onClick = { map.head = !map.head },
                label = stringResource(R.string.pain_chat_head),
            )
        }
        extras.forEach { area ->
            PwnFilterChip(
                selected = area in map.marked,
                onClick = { map.toggle(area) },
                label = resources.painArea(area),
            )
        }
    }
}

/**
 * The head's chips, the places the words named first, none ticked until the person ticks it: the
 * forehead, the temples, the top, the back of the head and the jaw on each side.
 */
@Composable
internal fun PainHeadChips(named: List<RegionSide>, map: ChatMapState) {
    val resources = LocalResources.current
    val chips = (named + HEAD_CHIPS).distinct()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        chips.forEach { area ->
            PwnFilterChip(
                selected = area in map.marked,
                onClick = { map.toggle(area) },
                label = resources.painArea(area),
            )
        }
    }
}

/** Under the map: the hint after a touch that marked nothing, what happens past the drawing's caps, and Send. */
@Composable
private fun MapFootRow(map: ChatMapState, onSend: () -> Unit) {
    val strokes = map.drawing.strokes
    val tooMuch = painPaintingTooMuch(strokes.toList())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (map.missed) {
                Text(
                    stringResource(R.string.pain_chat_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
            if (tooMuch) {
                Text(
                    stringResource(R.string.pain_chat_map_too_much),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
        }
        FilledIconButton(onClick = onSend, enabled = map.marked.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.symptom_chat_send))
        }
    }
}

/**
 * The pain page's 1–10 ruler ([ChatReply.PainScale]), its question the bot's: the needle resting on
 * the same pain's earlier rating or on the person's own number — which the caption shows with its
 * mark — and nothing recorded until it is moved or tapped. The clock takes it, held while a finger
 * is on it.
 */
@Composable
internal fun PainScaleReply(reply: ChatReply.PainScale, report: SymptomReport, actions: ReplyActions) {
    val nrs = report.values[reply.step]?.toIntOrNull()
    val resting = reply.start ?: reply.rest ?: DEFAULT_NRS
    Box(Modifier.fillMaxWidth().padding(start = BUBBLE_GUTTER)) {
        PainIntensity(
            nrs = nrs,
            onNrs = { actions.onValue(reply.step, it.toString()) },
            onHeld = actions.onHeld,
            label = null,
            resting = resting,
        ) {
            val rest = reply.rest
            if (nrs == null && rest != null && reply.start == null) {
                WordsMark(stringResource(R.string.pain_nrs_value, rest))
            }
        }
    }
}

/** A value the person's words gave, with the mark's accent: its words said only to a screen reader. */
@Composable
private fun WordsMark(text: String) {
    val spoken = stringResource(R.string.symptom_chat_mark_words, text)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.FormatQuote, null, Modifier.size(16.dp), tint = PwnMuted)
        Text(text, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
    }
}

/**
 * How long the pain lasted ([ChatReply.PainLasted]), on the pain page's ruler: settled by the clock;
 * a tap, or a screen reader's double tap, takes the length under the needle.
 */
@Composable
internal fun PainLastedReply(reply: ChatReply.PainLasted, report: SymptomReport, actions: ReplyActions) {
    val lasted = report.values[reply.step]?.toIntOrNull() ?: reply.start
    Box(Modifier.fillMaxWidth().padding(start = BUBBLE_GUTTER)) {
        PwnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PainLastedRuler(
                    lasted = lasted,
                    onLasted = { actions.onValue(reply.step, it.toString()) },
                    onHeld = actions.onHeld,
                    label = null,
                ) {}
            }
        }
    }
}

/**
 * The person's answer to a step the pain thread draws itself ([PainScript.bubbleSteps]): the sent
 * painting as its figure with the areas under it; areas tapped from chips by their names; the
 * rating, how long it lasted, the red flags in the words of their family's list.
 */
@Composable
internal fun PainAnswerBubble(step: SymptomStep, state: ChatState, onEdit: (() -> Unit)?) {
    val resources = LocalResources.current
    val report = state.report
    if (step == SymptomStep.PAIN_AREA && report.strokes.isNotEmpty()) {
        val sex = if (state.female) "FEMALE" else ""
        PainFigureBubble(PainReport(regions = painThreadMarks(report), strokes = report.strokes), sex, onEdit)
        return
    }
    PersonBubble(resources.painAnswerWords(step, state), onEdit)
}

/** A pain step's answer in words, in the language showing; "—" when there is none. */
internal fun Resources.painAnswerWords(step: SymptomStep, state: ChatState): String {
    val report = state.report
    if (step.kind.holds == StepHolds.OPTIONS) report.values[step]?.let { return it }
    val comma = getString(R.string.list_comma)
    val words = when (step) {
        SymptomStep.PAIN_AREA -> painThreadMarks(report).joinToString(comma) { painArea(it) }

        SymptomStep.PAIN_NRS -> report.values[step]?.toIntOrNull()?.let { getString(R.string.pain_nrs_value, it) }

        SymptomStep.PAIN_LASTED -> report.values[step]?.toIntOrNull()?.let { durationLabel(this, it) }

        else -> {
            val labels = painChipLabels(state, step)
            report.answers[step]?.joinToString(comma) { getString(labels[it] ?: it.labelRes) }
        }
    }
    return words.orEmpty().ifEmpty { getString(R.string.editor_extra_blank) }
}

/** How far the sent painting's small figure zooms in at most on what was marked. */
private const val BUBBLE_ZOOM = 3f
private val BUBBLE_FIGURE_WIDTH = 80.dp
private val BUBBLE_FIGURE_HEIGHT = 170.dp

/** The plates' height in pixels, which their width follows ([PLATE_ASPECT]): what a small figure is framed on. */
private const val PLATE_PIXELS_HIGH = 900f

/**
 * The sent painting as the person's bubble: one small figure for each view that shows a stroke or an
 * area chosen without painting, framed on what was marked there, the areas named under them. One
 * node to a screen reader, with the pencil's action where it has one.
 */
@Composable
internal fun PainFigureBubble(report: PainReport, sex: String, onEdit: (() -> Unit)?) {
    val resources = LocalResources.current
    val density = LocalDensity.current
    val shown = remember(report) { painShownStrokes(report) }
    val unshown = remember(report) { painUnshownAreas(report) }
    val views = PainView.entries.filter { view ->
        shown.any { it.view == view } || sidePatches(view, report.map).any { it.area in unshown }
    }
    val comma = stringResource(R.string.list_comma)
    val names = report.regions.joinToString(comma) { resources.painArea(it) }
    val description = stringResource(
        R.string.pain_canvas_marked,
        views.joinToString(comma) { resources.getString(viewName(it)) },
        names,
    )
    val editLabel = stringResource(R.string.common_edit)
    val frame = with(density) { Size(BUBBLE_FIGURE_WIDTH.toPx(), BUBBLE_FIGURE_HEIGHT.toPx()) }
    val fit = figureFit(PLATE_PIXELS_HIGH * PLATE_ASPECT, PLATE_PIXELS_HIGH, frame)
    Box(Modifier.fillMaxWidth().padding(start = BUBBLE_GUTTER), contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = PERSON_SHAPE,
            color = PwnIndigoSoft,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
                .then(
                    onEdit?.let { Modifier.clickable(role = Role.Button, onClickLabel = editLabel, onClick = it) }
                        ?: Modifier,
                ),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    views.forEach { view ->
                        val target = painMarksTarget(view, shown, unshown, report.map)
                        PainFigure(
                            view = view,
                            sex = sex,
                            lit = unshown.associateWith { 1f },
                            modifier = Modifier.size(BUBBLE_FIGURE_WIDTH, BUBBLE_FIGURE_HEIGHT),
                            camera = target?.let { painFraming(it, fit, frame, BUBBLE_ZOOM) } ?: PainCamera(),
                            marks = listOf(shown),
                            map = report.map,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        names,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (onEdit != null) Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = PwnMuted)
                }
            }
        }
    }
}

/**
 * "The same pain?"'s earlier pain in words: this chat's own, "already noted above"; one still in the
 * journal, the pain page's own sentence about it; one gone since, the day it was noted.
 */
internal fun Resources.painSameFoundLine(
    piece: PainSameFound,
    painMonth: List<JournalEntry>,
    locale: Locale,
    zone: ZoneId,
): String {
    if (piece.here) return getString(R.string.pain_chat_same_here)
    val row = painMonth.firstOrNull { it.kind == KIND_PAIN && it.occurredAt == piece.at }
    val marked = piece.areas.mapNotNull(RegionSide::parse).toSet()
    return if (row != null) {
        painSameFoundText(PainRow(row), marked, locale)
    } else {
        getString(R.string.pain_chat_same_gone, skeletonDateText(PAIN_DAY_DATE, locale, piece.at, zone))
    }
}

/** The day an earlier pain is named by: its weekday, day and month. */
private const val PAIN_DAY_DATE = "EEEdMMM"

/** "Carried over: aching, tight; noticed after a workout." */
internal fun Resources.painCarriedLine(piece: PainCarried): String {
    val earlier = PainReport(types = piece.types, noticed = piece.noticed)
    val parts = listOf(PainStep.TYPES, PainStep.NOTICED).map { carriedAnswer(it, earlier) }.filter { it.isNotEmpty() }
    return getString(R.string.pain_same_carried, parts.joinToString("; "))
}

/** A line of "the same pain?" as the bot's bubble. */
@Composable
internal fun PainSameFoundBubble(piece: PainSameFound, inputs: TurnInputs) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    BotBubble(resources.painSameFoundLine(piece, inputs.setting.env.painMonth, locale, inputs.setting.zone))
}

/** What the same pain carries from its first entry, as the bot's bubble. */
@Composable
internal fun PainCarriedBubble(piece: PainCarried) {
    BotBubble(LocalResources.current.painCarriedLine(piece))
}
