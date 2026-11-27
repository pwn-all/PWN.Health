package com.pwnall.health

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// The mood thread's own drawing: the Mood page's dial as a reply, and the face in the person's
// bubble. The dial is the page's own (MoodScreen.kt), at the chat's size: it rests where it starts,
// lights no answer and says "Not set yet" until it is touched, and the clock outside the list takes
// it three seconds after the last touch — never while a finger is on it.

/** The face's height in a chat: the page's 270 dp do not fit beside a conversation. */
private val CHAT_FACE_HEIGHT = 136.dp

/** The face's size in the person's bubble: the week strip's, beside the word. */
private val BUBBLE_FACE_SIZE = 28.dp

/**
 * The Mood page's dial as the answer to [ChatReply.MoodDial]: resting on [ChatReply.MoodDial.start] —
 * the mood being updated — or on the middle face, with nothing given until the person touches it. A
 * drag, a tap on a face's word (the one it rests on counts), a key or a screen reader's adjustment
 * gives a whole mood, held in the chat ([ReplyActions.onValue]) for the clock to take; a finger on the
 * slider holds the clock ([ReplyActions.onHeld]). Where the finger has it between two whole moods is
 * this widget's own, kept across a rotation.
 */
@Composable
internal fun MoodDialReply(reply: ChatReply.MoodDial, report: SymptomReport, actions: ReplyActions) {
    val step = reply.step
    val held = Mood.fromScore(report.values[step]?.toIntOrNull())
    val rest = Mood.fromScore(reply.start) ?: Mood.NEUTRAL
    // NaN: no finger has moved it here; the dial stands on the mood held, else where it rests.
    var dragged by rememberSaveable(step) { mutableFloatStateOf(Float.NaN) }
    val target = if (dragged.isNaN()) (held ?: rest).score.toFloat() else dragged
    val context = LocalContext.current
    val animated = remember(context) { context.animationsOn() }
    val shown by animateFloatAsState(
        target,
        if (animated) spring(dampingRatio = 1f, stiffness = 700f) else snap(),
        label = "chat mood",
    )
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val onHeld by rememberUpdatedState(actions.onHeld)
    LaunchedEffect(dragging) { onHeld(dragging) }
    // A dial that leaves mid-drag never leaves the clock held.
    DisposableEffect(Unit) { onDispose { onHeld(false) } }
    MoodDial(
        target = target,
        shown = shown,
        enabled = true,
        faceHeight = CHAT_FACE_HEIGHT,
        given = held != null || !dragged.isNaN(),
        compact = true,
        interactionSource = interaction,
    ) { value ->
        dragged = value
        // A drag passes between the faces; a whole one — its release, a tap, a key — is a mood given.
        val whole = value.roundToInt()
        if (value == whole.toFloat()) actions.onValue(step, whole.toString())
    }
}

/**
 * The person's answer to the dial, drawn by the mood thread itself ([TopicScript.bubbleSteps]): the
 * face, small and silent, beside its word. [onEdit]: the answer has the core's pencil, whose tap goes
 * on the bubble itself, as the core's own bubble takes it, so the gutter beside it takes no tap and the
 * ripple keeps the bubble's shape. Any other step, or a dial with no face, is the core's plain bubble,
 * its pencil with it: handed the tap alone, the whole row would take it and draw no pencil.
 */
@Composable
internal fun MoodAnswerBubble(step: SymptomStep, report: SymptomReport, onEdit: (() -> Unit)?) {
    val mood = Mood.fromScore(report.values[step]?.toIntOrNull()).takeIf { step == SymptomStep.MOOD_DIAL }
    if (mood == null) {
        PersonBubble(LocalResources.current.plainAnswer(step, report), onEdit)
        return
    }
    val editLabel = stringResource(R.string.common_edit)
    val pencil = onEdit?.let { Modifier.clickable(role = Role.Button, onClickLabel = editLabel, onClick = it) }
    Box(Modifier.fillMaxWidth().padding(start = BUBBLE_GUTTER), contentAlignment = Alignment.TopEnd) {
        Surface(shape = PERSON_SHAPE, color = PwnIndigoSoft, modifier = pencil ?: Modifier) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MorphingMoodFace(mood.score.toFloat(), Modifier.size(BUBBLE_FACE_SIZE).clearAndSetSemantics {})
                Text(
                    stringResource(mood.titleRes),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (onEdit != null) Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = PwnMuted)
            }
        }
    }
}
