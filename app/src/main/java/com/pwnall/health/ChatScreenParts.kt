package com.pwnall.health

import android.content.res.Resources
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

// The chat's pieces as they are drawn: the bot's bubbles and cards, the person's bubbles, the dots,
// the saved record's card, the replies the core draws and the composer. The screen around them —
// the pages, the state, the clock, the filer — is SymptomChatScreen.kt's.

/** One callback per kind of reply; only those that commit write anything ([SymptomChatScreen]). */
// One callback per kind of reply (15; detekt's constructorThreshold is 11): a bundle would only move the list.
@Suppress("LongParameterList")
internal class ReplyActions(
    /** A topic chip tapped. */
    val onTopic: (TopicChoice) -> Unit,
    /** "Physically", "Emotionally" or "Both" tapped under the unwell question: the menu narrowed, nothing answered. */
    val onNarrow: (ChatNarrow) -> Unit,
    /** "Carry on the last chat": another chat begun today. */
    val onResume: (String) -> Unit,
    /** "That is all for now". */
    val onEnd: () -> Unit,
    /** "Another topic": the start the chat chose left, for the thread of this id. */
    val onExit: (Int) -> Unit,
    /** One answer of a question that takes one. */
    val onTap: (SymptomStep, SymptomOption) -> Unit,
    /** A tick of a question that takes several, for the clock. */
    val onTick: (SymptomStep, SymptomOption) -> Unit,
    /** The chips a "keep" chip takes at once. */
    val onKeep: (SymptomStep, List<SymptomOption>) -> Unit,
    val onSeverity: (SymptomSeverity) -> Unit,
    /** A value: taken on the tap for a question of one, waiting for the clock otherwise. */
    val onValue: (SymptomStep, String) -> Unit,
    /** The painting so far, and the areas added and struck off by hand; nothing is taken until Send. */
    val onPaint: (SymptomStep, List<PainStroke>, List<String>, List<String>) -> Unit,
    val onSend: (SymptomStep) -> Unit,
    /** Words typed as the answer. */
    val onText: (SymptomStep, String) -> Unit,
    /** A finger on the map, the dial or a ruler: the clock waits. */
    val onHeld: (Boolean) -> Unit,
    /** A chip that opens a record; never an answer. */
    val onOpen: (ChatRecordRef) -> Unit,
)

/** What a card does while the chat is live: Undo, and Save again while the thread still wants the record. */
internal class CardActions(
    val onUndo: (Int, String) -> Unit,
    val onSaveAgain: (Int, String) -> Unit,
    val canSaveAgain: (Int, String) -> Boolean,
)

/**
 * What the chat's offers do; null is an offer the host does not make. [onBook] takes the thread's id.
 * [journal] is what a record card does ([JournalCard]); a card has its fields and buttons only with it.
 */
internal class ChatOffers(
    val onBook: ((Int) -> Unit)?,
    val onBreathing: (() -> Unit)?,
    val journal: JournalActions? = null,
)

/**
 * A record as a card draws it now: the row or the course its link names, with how the link resolved;
 * for a row opened alone from the day list, the row itself and no link.
 */
internal class CardRecord(
    val link: FiledLink?,
    val entry: JournalEntry?,
    val course: Medication?,
    val resolved: Resolved?,
)

/** Everything a bot turn needs to draw its cards and offers. */
internal class TurnInputs(
    val log: ChatLog,
    val setting: ChatSetting,
    val imperial: Boolean,
    val today: LocalDate,
    /** The card of a thread's record of a role, as it is now; null draws no card. */
    val record: (Int, String) -> CardRecord?,
    /** Open on a card, live or past; null where the host opens nothing. */
    val open: ((ChatRecordRef) -> Unit)?,
    /** Undo and Save again while the chat is live; null in the past. */
    val cards: CardActions?,
    val offers: ChatOffers?,
    val busy: Boolean,
    /** Whether [open] opens a course too: not where the medicine tab cannot stand over the chat's host. */
    val opensCourses: Boolean,
)

/** [text] in the language showing: its arguments rendered, times in [zone]. */
// A line has a word or two of arguments: the copy the spread makes is nothing.
@Suppress("SpreadOperator")
internal fun Resources.render(text: ChatText, locale: Locale, zone: ZoneId): String =
    if (text.args.isEmpty()) {
        getString(text.res)
    } else {
        getString(text.res, *text.args.map { renderArg(it, locale, zone) }.toTypedArray())
    }

private fun Resources.renderArg(arg: PieceArg, locale: Locale, zone: ZoneId): String = when (arg) {
    is PieceArg.Text -> arg.s

    is PieceArg.Res -> getString(arg.id)

    is PieceArg.Clock -> clockText(arg.minuteOfDay)

    is PieceArg.Moment -> Instant.ofEpochMilli(arg.epochMs).atZone(zone).let { clockText(it.hour * 60 + it.minute) }

    is PieceArg.Day -> runCatching {
        LocalDate.parse(arg.iso)
    }.getOrNull()?.let { painDayText(arg.pattern, locale, it) }
        ?: arg.iso

    is PieceArg.Plural -> getQuantityString(arg.res, arg.count, arg.count)

    is PieceArg.Joined -> arg.parts.joinToString(getString(arg.separatorRes)) { renderArg(it, locale, zone) }

    is PieceArg.Listed -> arg.parts.joinToString(getString(R.string.list_comma)) { renderArg(it, locale, zone) }

    is PieceArg.Areas ->
        arg.codes.mapNotNull(RegionSide::parse).joinToString(getString(R.string.list_comma)) { painArea(it) }
}

/**
 * The person's side in the language showing: their words, "That is all for now", or the answer to
 * the step from the final report — the topic's own words for it where it has some, else the chosen
 * options' labels, and "—" for an empty answer.
 */
internal fun Resources.personText(inputs: TurnInputs, item: ChatItem.Person, locale: Locale): String {
    val index = inputs.log.threads.indexOfFirst { it.id == item.thread }
    val step = item.step
    val text = when {
        item.text != null -> item.text
        item.end -> getString(R.string.symptom_chat_that_is_all)
        item.narrowed != null -> getString(item.narrowed.labelRes)
        index < 0 || step == null -> null
        else -> answerWords(inputs, index, step, locale)
    }
    return text.orEmpty().ifEmpty { getString(R.string.editor_extra_blank) }
}

/** The answer to [step] of the thread at [index] in words: its topic's own, else its options' labels. */
private fun Resources.answerWords(inputs: TurnInputs, index: Int, step: SymptomStep, locale: Locale): String? {
    val state = inputs.log.threadState(index, inputs.setting)
    if (step.kind.holds == StepHolds.OPTIONS) state.report.values[step]?.let { return it }
    val worded = if (step == SymptomStep.SYMPTOM) {
        topicWords(inputs.log.threads[index], locale, inputs.setting.zone)
    } else {
        stepScript(state, step).answerText(state, step)?.let { render(it, locale, inputs.setting.zone) }
    }
    return worded ?: when (step) {
        SymptomStep.SYMPTOM -> state.report.symptom?.let { getString(it.labelRes) }
        SymptomStep.SEVERITY -> state.report.severity?.let { getString(it.labelRes) }
        else -> state.report.answers[step]?.joinToString(getString(R.string.list_comma)) { getString(it.labelRes) }
    }
}

/** An answer as plain words: its value, or its options' labels; "—" when there is none. */
internal fun Resources.plainAnswer(step: SymptomStep, report: SymptomReport): String {
    val words = report.values[step]
        ?: report.answers[step]?.joinToString(getString(R.string.list_comma)) { getString(it.labelRes) }
    return words.orEmpty().ifEmpty { getString(R.string.editor_extra_blank) }
}

/** A topic chosen through a chip that named a mention: the mention's own words ("Pain: knee"). */
private fun Resources.topicWords(thread: ChatThread, locale: Locale, zone: ZoneId): String? {
    val mention = thread.from?.let(::mentionOfKey) ?: return null
    return mention.topic.script.mentionLabel(mention)?.let { render(it, locale, zone) }
}

/** The mention a thread's `from` names, when it names one: "PAIN:KNEE", "FEVER", "DOSE:нурофен:MISSED". */
internal fun mentionOfKey(key: String): TopicMention? {
    val topic = Symptom.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return null
    val detail = key.substringAfter(':', "")
    return TopicMention(topic, detail.removeSuffix(MENTION_MISSED), detail.endsWith(MENTION_MISSED))
}

/** A topic chip's words: its own label, a mention's, or the topic's name. */
@Composable
internal fun topicChipLabel(choice: TopicChoice): String {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    choice.labelRes?.let { return stringResource(it) }
    val mention = choice.mention?.let { choice.topic.script.mentionLabel(it) }
    return mention?.let { resources.render(it, locale, ZoneId.systemDefault()) }
        ?: stringResource(choice.topic.labelRes)
}

/** How many characters a turn says, for how long its dots stand: each piece as it is drawn. */
internal fun Resources.turnLength(turn: BotTurn, state: ChatState?, locale: Locale): Int = turn.pieces.sumOf { piece ->
    when (piece) {
        is BotPiece.Line -> getString(piece.line.textRes).length

        is BotPiece.Ask -> askText(piece, state, locale).length

        is BotPiece.Alert -> (
            piece.text?.let { render(it, locale, ZoneId.systemDefault()) }
                ?: getString(piece.alert.textRes)
            ).length

        is BotPiece.Said -> piece.text.length

        is BotPiece.Note -> render(piece.text, locale, ZoneId.systemDefault()).length

        is BotPiece.ForDay -> getString(R.string.symptom_chat_for_day, painDayText("dMMMM", locale, piece.day)).length

        is BotPiece.Saved -> getString(R.string.symptom_chat_saved, "").length

        is PainSameFound ->
            painSameFoundLine(piece, state?.thread?.env?.painMonth.orEmpty(), locale, ZoneId.systemDefault()).length

        is PainCarried -> painCarriedLine(piece).length

        is BotPiece.Journal -> getString(piece.event.type.titleRes).length

        BotPiece.Facts, is BotPiece.Offer -> 0
    }
}

/** The question as it is asked: its script's words with their arguments, the earlier chat's day, or its resource. */
internal fun Resources.askText(piece: BotPiece.Ask, state: ChatState?, locale: Locale): String {
    val text = piece.text
    val day = piece.day
    return when {
        text != null -> render(text, locale, ZoneId.systemDefault())

        piece.step == SymptomStep.SAME && day != null ->
            getString(R.string.symptom_ask_same, painDayText("dMMMM", locale, day))

        piece.step == SymptomStep.SAME -> getString(R.string.symptom_step_same)

        else -> getString(state?.let { stepScript(it, piece.step).question(it, piece.step) } ?: piece.step.labelRes)
    }
}

/**
 * "Dizziness · Yesterday · Moderate": what [thread] said, as [report] tells it, for a visit's note. A
 * topic of a record of its own is named by its title ("Mood", "Pain · Lower back"), never by the
 * menu's words for it ("Note my mood").
 */
internal fun Resources.summaryLine(
    thread: ChatThread,
    report: SymptomReport,
    earlier: EarlierChat?,
    locale: Locale,
): String {
    val start = if (report.has(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE) && earlier != null) {
        getString(R.string.symptom_chat_same_since, painDayText("dMMM", locale, earlier.day))
    } else {
        report.answers[SymptomStep.ONSET]?.firstOrNull()?.let { getString(it.labelRes) }
    }
    val topic = report.symptom?.let { symptom ->
        if (symptom.record == ChatRecord.SYMPTOM) getString(symptom.labelRes) else symptom.script.title(this, thread)
    }
    return listOfNotNull(topic, start, report.severity?.let { getString(it.labelRes) }).joinToString(" · ")
}

/**
 * One bot turn: its pieces stacked, soft bubbles on the start side, safety lines at the column's
 * width, the journal's card where it came, a record's card where it was saved. [appear]: the turn has
 * just arrived after the dots — its words fade in and rise [CHAT_RISE] together, all its pieces at
 * once, a line to call emergency services only fading in; never with animations off.
 */
@Composable
internal fun BotTurnView(
    turn: BotTurn,
    state: ChatState?,
    inputs: TurnInputs,
    appear: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rise = with(LocalDensity.current) { CHAT_RISE.roundToPx() }
    val visible =
        remember(turn) { MutableTransitionState(!appear || !context.animationsOn()).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visible,
        modifier = modifier,
        enter = if (turn.urgent()) {
            fadeIn(PwnMotion.effects())
        } else {
            fadeIn(PwnMotion.effects()) + slideInVertically(PwnMotion.spatial()) { rise }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            turn.pieces.forEach { piece -> BotPieceView(piece, state, inputs) }
        }
    }
}

@Composable
private fun BotPieceView(piece: BotPiece, state: ChatState?, inputs: TurnInputs) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val zone = inputs.setting.zone
    when (piece) {
        is BotPiece.Line -> when {
            piece.line.safety -> SymptomSafetyCard(stringResource(piece.line.textRes), urgent = true) {
                piece.line.sourceRes?.let { Text(stringResource(it)) }
            }

            // Advice that stands on its own source, as a red flag's line does: the plain card and its "?".
            piece.sourceRes != null || piece.line.sourceRes != null ->
                SymptomSafetyCard(stringResource(piece.line.textRes), urgent = false) {
                    Text(stringResource(piece.sourceRes ?: checkNotNull(piece.line.sourceRes)))
                }

            else -> BotBubble(stringResource(piece.line.textRes))
        }

        is BotPiece.Ask -> BotBubble(resources.askText(piece, state, locale), heading = true, help = piece.help)

        is BotPiece.Alert -> SymptomAlertLine(
            piece.alert,
            piece.sourceRes,
            piece.text?.let {
                resources.render(it, locale, zone)
            },
        )

        BotPiece.Facts -> {
            val topic = state?.report?.symptom
            val snapshot = state?.report?.context
            if (topic != null && snapshot != null) {
                SymptomContextCard(snapshot, topic, inputs.setting.day, inputs.today, inputs.imperial)
            }
        }

        is BotPiece.Offer -> inputs.offers?.let { OfferButton(piece, it) }

        is BotPiece.Said -> BotBubble(piece.text)

        is BotPiece.Saved -> inputs.record(piece.thread, piece.role)?.let { SavedRecordCard(piece, it, inputs) }

        is BotPiece.ForDay -> BotBubble(
            stringResource(R.string.symptom_chat_for_day, painDayText("dMMMM", locale, piece.day)),
        )

        is BotPiece.Note -> {
            val text = resources.render(piece.text, locale, zone)
            if (piece.safety || piece.sourceRes != null) {
                SymptomSafetyCard(text, urgent = piece.safety) { piece.sourceRes?.let { Text(stringResource(it)) } }
            } else {
                BotBubble(text)
            }
        }

        is PainSameFound -> PainSameFoundBubble(piece, inputs)

        is PainCarried -> PainCarriedBubble(piece)

        is BotPiece.Journal -> JournalCard(piece, inputs)
    }
}

/** An offer as a button under the turn, its drawing in the resting slate; nothing accented. */
@Composable
private fun OfferButton(piece: BotPiece.Offer, offers: ChatOffers) {
    val (label, art, action) = when (piece.offer) {
        ChatOffer.BOOK_VISIT -> Triple(
            R.string.symptom_chat_book,
            R.drawable.ic_art_doctor,
            offers.onBook?.let { book -> { book(piece.thread) } },
        )

        ChatOffer.BREATHING -> Triple(R.string.symptom_chat_breathing, R.drawable.ic_art_breathing, offers.onBreathing)
    }
    val onClick = action ?: return
    PwnOutlinedButton(onClick = onClick) {
        Image(themePainterResource(art), null, Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(label))
    }
}

/**
 * A record the chat saved, one card for every kind: its drawing, "Here is what is saved: …" from the
 * record as it is now, a muted second line, where it stands if it is no longer as filed, and Open,
 * Undo or Save again — Undo and Save again only while the chat is live. One traversal group.
 */
@Composable
internal fun SavedRecordCard(piece: BotPiece.Saved, record: CardRecord, inputs: TurnInputs) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val link = record.link
    val thread = inputs.log.threads.firstOrNull { it.id == piece.thread }
    val script = thread?.topic?.script ?: SymptomScript
    val kind = link?.kind ?: record.entry?.kind ?: KIND_SYMPTOM
    val visual = kindVisual(if (kind == LINK_COURSE) KIND_MED else kind)
    val state = link?.let { cardState(it, record.resolved) } ?: LinkState.FILED
    val summary = script.savedSummary(resources, record.entry, record.course, piece.role, state)
    // A record no longer there — undone, refused, deleted elsewhere — is named by the title it was
    // written with: nothing is saved to say. Nor is a row an Undo of this chat's change put back, or
    // one a refused write found in its place: what stands there is not this card's record.
    val there = (record.entry != null || record.course != null) && state !in NOT_SAVED
    val detail = script.savedDetail(resources, record.entry, record.course, piece.role)?.takeIf { there }
    val own = link?.takeIf { state != LinkState.FILED }?.let { filed ->
        thread?.let { script.stateLine(it, filed.copy(state = state)) }
    }
    val ownText = own?.let { resources.render(it, locale, inputs.setting.zone) }
    // The topic's own line where it has one, else the core's.
    val stateText = link?.let { ownText ?: cardStateRes(state, it.err)?.let { line -> resources.getString(line) } }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PwnGlyph(visual.art, visual.icon, PwnIndigoSoft, null, Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (there) stringResource(R.string.symptom_chat_saved, summary) else link?.title.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
                    stateText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
                }
            }
            CardButtons(piece, record, kind, state, summary, inputs)
        }
    }
}

/** Where a link's record stands, as the card says it: as the database says now, else as last filed. */
private fun cardState(link: FiledLink, resolved: Resolved?): LinkState = when {
    link.state != LinkState.FILED -> link.state
    resolved is Resolved.Changed -> LinkState.CHANGED
    resolved == Resolved.Gone -> LinkState.GONE
    else -> LinkState.FILED
}

/** The links whose record is not this chat's as filed: Undo took it back, or the door refused it. */
private val NOT_SAVED = setOf(LinkState.UNDONE, LinkState.REFUSED)

/**
 * The core's state line of a card, by its resource: none while the record stands as filed — unless its
 * last change was not saved, [unbuilt] ([FiledLink.err]). Without it, when the act that was to
 * rewrite it could not build it, the card would go on saying "Here is what is saved" over the old
 * answers, and only the flash, gone at a tap, would say that anything had failed.
 */
internal fun cardStateRes(state: LinkState, unbuilt: Boolean): Int? = when (state) {
    LinkState.FILED -> R.string.symptom_chat_record_change_not_saved.takeIf { unbuilt }
    LinkState.GONE -> R.string.work_flash_deleted
    LinkState.CHANGED -> R.string.symptom_chat_record_changed
    LinkState.SUPERSEDED -> R.string.symptom_chat_record_superseded
    LinkState.UNDONE, LinkState.REFUSED -> R.string.symptom_chat_record_not_saved
}

/** The record a card opens, as it stands now. */
private fun refOf(record: CardRecord): ChatRecordRef? =
    record.entry?.let { ChatRecordRef(it.kind, it.occurredAt, it.id) }
        ?: record.course?.let { ChatRecordRef(LINK_COURSE, it.createdAt, it.id) }

/**
 * A card's buttons: Open where the record can be opened ([cardOpens]), Undo while it is filed, and Save
 * again for one not saved or deleted elsewhere while the thread still wants it (never a course deleted
 * elsewhere). None while a write of the chat is in flight.
 */
@Composable
private fun CardButtons(
    piece: BotPiece.Saved,
    record: CardRecord,
    kind: String,
    state: LinkState,
    summary: String,
    inputs: TurnInputs,
) {
    val link = record.link
    val ref = refOf(record)
    val open = inputs.open?.takeIf { ref != null && cardOpens(kind, state, inputs.opensCourses) }
    val actions = inputs.cards
    // Undo only where it can do something: a record the chat made, or a change it can put back.
    val undo = actions?.takeIf { link != null && state == LinkState.FILED && (link.made || link.prior != null) }
    val again = actions?.takeIf {
        link != null && state in SAVABLE && !(state == LinkState.GONE && kind == LINK_COURSE) &&
            it.canSaveAgain(piece.thread, piece.role)
    }
    if (open == null && undo == null && again == null) return
    val openLabel = stringResource(R.string.symptom_chat_record_open_label, summary)
    val undoLabel = stringResource(R.string.symptom_chat_record_undo_label, summary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        open?.let { action ->
            PwnOutlinedButton(
                onClick = { ref?.let(action) },
                modifier = Modifier.semantics {
                    onClick(label = openLabel) {
                        ref?.let(action)
                        true
                    }
                },
            ) { Text(stringResource(R.string.history_open)) }
        }
        undo?.let { action ->
            PwnOutlinedButton(
                onClick = { action.onUndo(piece.thread, piece.role) },
                enabled = !inputs.busy,
                modifier = Modifier.semantics {
                    onClick(label = undoLabel) {
                        action.onUndo(piece.thread, piece.role)
                        true
                    }
                },
            ) { Text(stringResource(R.string.med_dose_undo)) }
        }
        again?.let { action ->
            PwnOutlinedButton(onClick = { action.onSaveAgain(piece.thread, piece.role) }, enabled = !inputs.busy) {
                Text(stringResource(R.string.symptom_chat_record_again))
            }
        }
    }
}

/**
 * Whether a card of a record of [kind], standing [state], offers Open: wherever the record is there —
 * never a symptom's, which would open this chat, nor a course's where the host opens no course
 * ([opensCourses]: the record editor, which no tab stands over and where its Open would do nothing).
 */
internal fun cardOpens(kind: String, state: LinkState, opensCourses: Boolean): Boolean =
    kind != KIND_SYMPTOM && state in OPENABLE && (kind != LINK_COURSE || opensCourses)

/** The states in which a card's record is there to open. */
private val OPENABLE = setOf(LinkState.FILED, LinkState.CHANGED, LinkState.SUPERSEDED)

/** The states a card offers Save again from. */
private val SAVABLE = setOf(LinkState.UNDONE, LinkState.REFUSED, LinkState.GONE)

internal val BOT_SHAPE = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
internal val PERSON_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)

/** The room left beside a bubble, so the two sides read as two. */
internal val BUBBLE_GUTTER = 48.dp

/**
 * The bot's bubble, on the start side; a question is a heading, so a screen reader can jump to it,
 * and what it means is behind the "?" beside it ([help]) — never printed as a paragraph.
 */
@Composable
internal fun BotBubble(text: String, heading: Boolean = false, help: ChatText? = null) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    Box(Modifier.fillMaxWidth().padding(end = BUBBLE_GUTTER), contentAlignment = Alignment.TopStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = BOT_SHAPE, color = PwnSurfaceElevated, modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp).then(
                        if (heading) Modifier.semantics { heading() } else Modifier,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            help?.let { HelpDot(text) { Text(resources.render(it, locale, ZoneId.systemDefault())) } }
        }
    }
}

/** The person's bubble, on the end side; an answer in a live chat carries the pencil that takes it back. */
@Composable
internal fun PersonBubble(text: String, onEdit: (() -> Unit)?, modifier: Modifier = Modifier) {
    val editLabel = stringResource(R.string.common_edit)
    Box(modifier.fillMaxWidth().padding(start = BUBBLE_GUTTER), contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = PERSON_SHAPE,
            color = PwnIndigoSoft,
            modifier = onEdit?.let { Modifier.clickable(role = Role.Button, onClickLabel = editLabel, onClick = it) }
                ?: Modifier,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (onEdit != null) Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = PwnMuted)
            }
        }
    }
}

/**
 * One item of a transcript: a bot turn as it was, or the person's side — drawn by the topic's own
 * file for an answer it draws itself (the mood's face), with the pencil where [onEdit] allows. A
 * person's side that [arrives] — just sent, never one scrolled back into view or brought back by a
 * rotation — grows from its tail ([PersonArrival]).
 */
@Composable
internal fun ChatItemView(
    item: ChatItem,
    inputs: TurnInputs,
    onEdit: ((ChatItem.Person) -> Unit)?,
    modifier: Modifier = Modifier,
    arrives: Boolean = false,
) {
    when (item) {
        is ChatItem.Bot -> {
            val index = inputs.log.threads.indexOfFirst { it.id == item.thread }
            val state = if (index >= 0) inputs.log.threadState(index, inputs.setting) else null
            BotTurnView(item.turn, state, inputs, appear = false, modifier = modifier)
        }

        is ChatItem.Person -> PersonArrival(arrives, modifier) { PersonSide(item, inputs, onEdit) }
    }
}

/**
 * The person's message arriving: it grows from nine tenths out of its tail corner — the top of the
 * end side, mirrored for right-to-left — fades in and rises [CHAT_SEND_RISE] from where it was sent,
 * the composer or the chips on the same side; at rest unless it [arrives].
 */
@Composable
private fun PersonArrival(arrives: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val rise = with(LocalDensity.current) { CHAT_SEND_RISE.roundToPx() }
    val arrival = remember { MutableTransitionState(!arrives).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = arrival,
        modifier = modifier,
        enter = fadeIn(PwnMotion.effects()) +
            scaleIn(PwnMotion.spatial(), CHAT_ARRIVAL_SCALE, TransformOrigin(if (rtl) 0f else 1f, 0f)) +
            slideInVertically(PwnMotion.spatial()) { rise },
    ) { content() }
}

/** The person's side of [item]: their words, the topic's own drawing of an answer, or its words in a bubble. */
@Composable
private fun PersonSide(item: ChatItem.Person, inputs: TurnInputs, onEdit: ((ChatItem.Person) -> Unit)?) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val edit = onEdit?.let { { it(item) } }
    val index = inputs.log.threads.indexOfFirst { it.id == item.thread }
    val thread = inputs.log.threads.getOrNull(index)
    val step = item.step
    val script = thread?.topic?.script
    // The topics that draw an answer themselves ([TopicScript.bubbleSteps]); any other is the core's bubble.
    when {
        step != null && script == PainScript && step in script.bubbleSteps ->
            PainAnswerBubble(step, inputs.log.threadState(index, inputs.setting), edit)

        step != null && script != null && step in script.bubbleSteps && script == MoodScript ->
            MoodAnswerBubble(step, thread.report, edit)

        else -> PersonBubble(resources.personText(inputs, item, locale), edit)
    }
}

private const val DOT_COUNT = 3
private const val DOT_STAGGER_NANOS = 160_000_000L
private const val DOT_PERIOD_NANOS = 1_000_000_000L
private const val DOT_MIN_ALPHA = 0.45f

/** How long one turn of the dots may run at most: the longest any turn is held for. */
private const val DOTS_LONGEST_MS = 1_800L

/**
 * "Thinking": three dots rising and falling one after another in a small bubble, on the frame clock
 * and read in the draw phase only, so nothing recomposes on a frame. The loop ends with the turn — it
 * never runs longer than the longest a turn is held — and with animations off the dots stand still.
 * Silent to a screen reader: an announcement before every turn would be noise.
 */
@Composable
internal fun TypingDots(animated: Boolean) {
    var phase by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        var now = start
        while (now - start < DOTS_LONGEST_MS * 1_000_000L) {
            now = withFrameNanos { it }
            phase = now - start
        }
    }
    val ink = PwnMuted
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        Surface(shape = BOT_SHAPE, color = PwnSurfaceElevated, modifier = Modifier.clearAndSetSemantics {}) {
            Canvas(Modifier.size(width = 56.dp, height = 36.dp)) {
                val radius = 3.5.dp.toPx()
                val gap = 5.dp.toPx()
                val rise = 4.dp.toPx()
                val width = DOT_COUNT * radius * 2 + (DOT_COUNT - 1) * gap
                val left = (size.width - width) / 2 + radius
                repeat(DOT_COUNT) { index ->
                    val t = ((phase - index * DOT_STAGGER_NANOS).mod(DOT_PERIOD_NANOS)).toFloat() / DOT_PERIOD_NANOS
                    val lift = if (animated) max(0f, sin(2 * PI * t).toFloat()) else 0f
                    drawCircle(
                        color = ink.copy(alpha = DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * lift),
                        radius = radius,
                        center = Offset(left + index * (radius * 2 + gap), size.height / 2 - rise * lift),
                    )
                }
            }
        }
    }
}

/** How far the bot's words rise as they arrive after the dots. */
internal val CHAT_RISE = 12.dp

/** How far the person's message rises from where it was sent, the composer or the chips. */
internal val CHAT_SEND_RISE = 16.dp

/** The person's message grows from nine tenths, as the centre flash does: it reads as appearing. */
internal const val CHAT_ARRIVAL_SCALE = 0.9f

/** The reply is drawn in this long after the words, so the eye — and a screen reader — has the words first. */
internal const val CHAT_REPLY_LAG_MS = 90L

/** How far a reply rises as it is drawn in. */
private val CHAT_REPLY_RISE = 8.dp

/**
 * A reply — chips, the map, a ruler — laid out in full from its first frame and only drawn in: it
 * fades in and rises [CHAT_REPLY_RISE] ([rise]; the map does not, nothing that can be painted on
 * slides) on one value, [CHAT_REPLY_LAG_MS] after the words above it, so the list knows its height at
 * once and one scroll brings it into view. At rest unless it [arrive]s: never with animations off.
 */
@Composable
internal fun ReplyReveal(arrive: Boolean, rise: Boolean = true, content: @Composable () -> Unit) {
    val distance = with(LocalDensity.current) { CHAT_REPLY_RISE.toPx() }
    val shown = remember { Animatable(if (arrive) 0f else 1f) }
    LaunchedEffect(shown) {
        if (shown.value < 1f) {
            delay(CHAT_REPLY_LAG_MS)
            shown.animateTo(1f, PwnMotion.spatial())
        }
    }
    Box(
        Modifier.graphicsLayer {
            // The spatial spring overshoots past one: the fade stops at full.
            alpha = shown.value.coerceIn(0f, 1f)
            translationY = if (rise) (1f - shown.value) * distance else 0f
        },
    ) { content() }
}

/**
 * A reply that folds away once taken — a tall widget, the mood's dial, the sent map — instead
 * of leaving at once: composed on after its question was answered, shrinking and fading out, taking no
 * touch and saying nothing meanwhile, until it has gone ([onGone]). At once with animations off.
 */
@Composable
internal fun FoldingReply(visible: Boolean, animated: Boolean, onGone: () -> Unit, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(true) }
    shown.targetState = visible
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(shown.isIdle, shown.currentState) { if (shown.isIdle && !shown.currentState) gone() }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown,
        enter = EnterTransition.None,
        exit = if (animated) {
            shrinkVertically(
                PwnMotion.spatial(),
            ) + fadeOut(PwnMotion.effects())
        } else {
            ExitTransition.None
        },
    ) {
        Box(if (visible) Modifier else Modifier.clearAndSetSemantics { }) {
            content()
            // A cover laid over it, not a hand around it: the map's painter takes a touch whatever took
            // it first, so that no stroke is lost to a scroll, and only a touch that never reaches it
            // stays off it (else a tap on the folding map would paint into the answer already sent).
            if (!visible) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
                )
            }
        }
    }
}

/**
 * How far the chat scrolls to bring its newest row's foot to the end of the list — [end] pixels below
 * the viewport's top — never past the row's own top, [top]: a row taller than the list shows its top.
 * Forward only, and none when the foot already stands in view.
 */
internal fun tailDelta(end: Int, top: Int, bottom: Int): Int = maxOf(0, minOf(bottom - end, top))

/**
 * How far the chat scrolls when its viewport changes from [oldHeight] to [newHeight] — the keyboard
 * opening or closing, the line under the bar arriving, the composer leaving — so the end stays where
 * the person had it: nothing unless the newest row stood in view before (its foot [endOffset] below
 * the viewport's top, the row [newestHeight] tall); then its foot to the new end, just above the
 * composer, or, when it is taller than the new viewport, its top to the viewport's top. Null: leave
 * the list alone.
 */
internal fun endKeptInView(oldHeight: Int, newHeight: Int, endOffset: Int, newestHeight: Int): Int? {
    val top = endOffset - newestHeight
    if (oldHeight == newHeight || top >= oldHeight || endOffset <= 0) return null
    return if (newestHeight > newHeight) top else maxOf(0, endOffset - newHeight)
}

/** Whether the newest row of the list is in view: the person has the end of the chat before them. */
internal fun LazyListLayoutInfo.endInView(): Boolean =
    totalItemsCount == 0 || visibleItemsInfo.lastOrNull()?.index == totalItemsCount - 1

/**
 * How far the chat scrolls to bring its end — the foot of its last row, [bottom] pixels below the
 * viewport's top, null while that row is not composed yet — to the end of the list, [end], never so
 * far that the top of the bot's newest row, [anchorTop], leaves the viewport: a reply or a map taller
 * than the room under the question leaves the question in view. Forward only.
 */
internal fun revealDelta(end: Int, anchorTop: Int, bottom: Int?): Int =
    maxOf(0, minOf(bottom?.let { it - end } ?: Int.MAX_VALUE, anchorTop))

/**
 * Brings the end of the chat into view ([tailDelta]), never past the top of the row at [anchor] — the
 * bot's newest words, whose question stands over the reply ([revealDelta]): the anchor not composed
 * yet — below the fold — is scrolled to the top, a row below it by the distance its foot stands past
 * the end; with no anchor in the list, its last row. On the settle spring when [animated],
 * so it lands flush rather than bouncing.
 */
internal suspend fun LazyListState.revealTail(animated: Boolean, anchor: Int = -1) {
    when (val move = layoutInfo.tailMove(anchor)) {
        null -> Unit

        is TailMove.To -> if (animated) animateScrollToItem(move.index) else scrollToItem(move.index)

        is TailMove.By -> if (animated) {
            animateScrollBy(move.px.toFloat(), settleSpring())
        } else {
            scrollBy(move.px.toFloat())
        }
    }
}

/** How the end comes into view: by scrolling to a row not composed yet, or by a distance. */
private sealed interface TailMove {
    data class To(val index: Int) : TailMove

    data class By(val px: Int) : TailMove
}

/**
 * The move [revealTail] makes: to the [anchor] row when it is below the fold, so its top comes to the
 * top; else by the distance [revealDelta] or [tailDelta] gives for what is composed, or to the last
 * row when nothing of the end is; none when the end already stands in view.
 */
private fun LazyListLayoutInfo.tailMove(anchor: Int): TailMove? {
    val lastIndex = totalItemsCount - 1
    if (lastIndex < 0) return null
    val held = visibleItemsInfo.firstOrNull { it.index == anchor }
    val below = held == null && anchor in 0..lastIndex && anchor > (visibleItemsInfo.lastOrNull()?.index ?: -1)
    val delta = tailDistance(held)
    return when {
        below -> TailMove.To(anchor)
        delta == null -> TailMove.To(lastIndex)
        delta > 0 -> TailMove.By(delta)
        else -> null
    }
}

/** How far the end stands past the list's end, never past the top of the anchor row [held]; null: no end composed. */
private fun LazyListLayoutInfo.tailDistance(held: LazyListItemInfo?): Int? {
    val last = visibleItemsInfo.lastOrNull()?.takeIf { it.index == totalItemsCount - 1 }
    val end = viewportEndOffset - afterContentPadding
    return when {
        held != null -> revealDelta(end, held.offset, last?.let { it.offset + it.size })
        last != null -> tailDelta(end, last.offset, last.offset + last.size)
        else -> null
    }
}

/**
 * The chat bringing its own end into view ([revealTail]), and what the viewport did meanwhile: a change
 * of its height under that scroll — the keyboard opening as a turn arrives — is not lost, the end is
 * brought into view again once the scroll is done (else the bot's newest words would be left a
 * keyboard's height under the composer).
 */
internal class ChatReveal {
    private var running = false
    private var missed = false

    /** The end brought into view by [once], and again while the viewport changed under it — never while [held]. */
    suspend fun bring(held: () -> Boolean, once: suspend () -> Unit) {
        running = true
        try {
            do {
                missed = false
                once()
            } while (missed && !held())
        } finally {
            running = false
        }
    }

    /**
     * The viewport changed height: whether the end is to be kept in view now ([endKeptInView]) — not while
     * the list is [scrolling]: under the chat's own reveal the change is noted for it, under the person's
     * own scroll it is theirs.
     */
    fun viewportChanged(scrolling: Boolean): Boolean {
        if (scrolling && running) missed = true
        return !scrolling
    }
}

/**
 * The live chat's scrolling. After each of [changes] — a row, the dots or the words, the reply, the
 * map, typing — and a frame for it to be laid out, the end is brought into view ([revealTail]) when
 * the person had it in view, or the change was their own act ([ownAct], taken once), never past the
 * top of the bot's newest row ([anchor], its index in the list); on the settle spring when [animated],
 * and never the first time: the list opened where it was placed. When the viewport changes height —
 * the keyboard, the line under the bar, the composer leaving — the end stays where the person had it
 * ([endKeptInView]), or, under the chat's own scroll, is brought into view again after it ([ChatReveal]).
 * Neither while [held]: a finger on the map, a ruler or the dial. [atEnd]: the list opened at its end,
 * not at a thread a row of the day list was told in.
 */
@Composable
internal fun ChatScrolling(
    listState: LazyListState,
    changes: Any,
    animated: Boolean,
    atEnd: Boolean,
    held: () -> Boolean,
    ownAct: () -> Boolean,
    anchor: () -> Int,
) {
    val holding by rememberUpdatedState(held)
    val owned by rememberUpdatedState(ownAct)
    val newest by rememberUpdatedState(anchor)
    // Whether the person has the end before them, as of the last time the list stopped moving.
    var stuck by remember { mutableStateOf(atEnd) }
    var settled by remember { mutableStateOf(false) }
    val reveal = remember { ChatReveal() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.drop(1).filter { !it }
            .collect { stuck = listState.layoutInfo.endInView() }
    }
    LaunchedEffect(changes) {
        withFrameNanos { }
        val own = owned()
        if (!settled) {
            settled = true
        } else if (!holding() && (stuck || own)) {
            reveal.bring({ holding() }) { listState.revealTail(animated, newest()) }
        }
    }
    LaunchedEffect(listState) {
        var before = -1
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
            val old = before
            before = height
            if (old < 0 || holding() || !reveal.viewportChanged(listState.isScrollInProgress)) return@collect
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
                ?: return@collect
            val end = info.viewportEndOffset - info.afterContentPadding
            val delta = endKeptInView(end - (height - old), end, last.offset + last.size, last.size)
            if (delta != null && delta != 0) listState.scrollBy(delta.toFloat())
        }
    }
}

/**
 * What a reply drawer is handed besides the reply: the thread asked, its answers so far, the menu behind "Show all
 * topics".
 */
internal class ReplyInputs(
    val thread: Int,
    val report: SymptomReport,
    val menu: List<TopicChoice>,
    val menuOpen: Boolean,
    val onMenuOpen: () -> Unit,
    /** The body map of the thread whose area question is open, which the area chips unfold. */
    val map: ChatMapState? = null,
)

/**
 * The answers the live turn waits for, on the end side under it, each kind drawn by the file that
 * owns it — the core's chips and composer here, the body map and the rulers in the pain thread's
 * file, the dial in the mood's, the medicine's own in the medicine's — with "Another topic" under any
 * the chat chose from the person's words.
 */
@Composable
internal fun ChatReplyView(reply: ChatReply, inputs: ReplyInputs, actions: ReplyActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (reply) {
            is ChatReply.Topics -> TopicsReply(reply, inputs, actions)

            is ChatReply.Choose -> ChooseReply(reply, inputs.report, actions)

            ChatReply.Severity -> SeverityReply(inputs.report, actions)

            is ChatReply.Values -> ValuesReply(reply, inputs.report, actions)

            is ChatReply.TextAsk -> SuggestionChips(reply.step, reply.suggestions, actions)

            ChatReply.End -> Unit

            is ChatReply.AreaChips -> PainAreaChipsReply(reply, inputs, actions)

            // The map is its own item of the list, under this one ([ChatPainMap]): a message, the
            // dots or a rotation never take it away with the turn.
            is ChatReply.BodyMap -> Unit

            is ChatReply.PainScale -> PainScaleReply(reply, inputs.report, actions)

            is ChatReply.PainLasted -> PainLastedReply(reply, inputs.report, actions)

            is ChatReply.MoodDial -> MoodDialReply(reply, inputs.report, actions)

            is ChatReply.MedicinePick -> MedicinePickReply(reply, inputs.report, actions)

            is ChatReply.TimesPick -> TimesPickReply(reply, inputs.report, actions)

            is ChatReply.ReminderGates -> ReminderGatesReply(reply, inputs.report, actions)
        }
        if (reply.exitShown) {
            ReplyRow(single = false) {
                PwnOutlinedButton(onClick = { actions.onExit(inputs.thread) }) {
                    Text(stringResource(R.string.symptom_chat_another_topic))
                }
            }
        }
    }
}

/**
 * Topic chips. The whole menu stands in its groups — what to note down, the body, feelings — each
 * under a muted heading that is not a heading to a screen reader, then "something else"; proposed
 * chips stand alone, with "Show all topics" to unfold the menu in place. "Carry on the last chat"
 * comes first, the chips that narrow the menu under the unwell question before the topics, "That is
 * all for now" last.
 */
@Composable
private fun TopicsReply(reply: ChatReply.Topics, inputs: ReplyInputs, actions: ReplyActions) {
    Column(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        reply.resume?.let { key ->
            PainAnswerChip(false, { actions.onResume(key) }, stringResource(R.string.symptom_chat_carry_on))
        }
        if (reply.all) {
            MenuChips(reply.choices, actions)
        } else {
            ReplyRow(single = false) {
                reply.narrow.forEach { narrow ->
                    PainAnswerChip(false, { actions.onNarrow(narrow) }, stringResource(narrow.labelRes))
                }
                reply.choices.forEach { choice ->
                    PainAnswerChip(false, { actions.onTopic(choice) }, topicChipLabel(choice))
                }
            }
            if (!reply.exit && inputs.menuOpen) MenuChips(inputs.menu, actions)
        }
        ReplyRow(single = false) {
            if (!reply.all && !reply.exit && !inputs.menuOpen) {
                PwnOutlinedButton(onClick = inputs.onMenuOpen) {
                    Text(stringResource(R.string.symptom_chat_all_topics))
                }
            }
            if (reply.end) {
                PwnOutlinedButton(onClick = actions.onEnd) { Text(stringResource(R.string.symptom_chat_that_is_all)) }
            }
        }
    }
}

/** The whole menu, group by group; a group with nothing left in it is not drawn. */
@Composable
private fun MenuChips(choices: List<TopicChoice>, actions: ReplyActions) {
    MENU_GROUPS.forEach { (heading, topics) ->
        val group = choices.filter { it.topic in topics }
        if (group.isNotEmpty()) {
            heading?.let { Text(stringResource(it), color = PwnMuted, style = MaterialTheme.typography.labelMedium) }
            ReplyRow(single = false) {
                group.forEach { choice -> PainAnswerChip(false, { actions.onTopic(choice) }, topicChipLabel(choice)) }
            }
        }
    }
}

/**
 * The options of a question: a single answer the tap gives, several ticked for the clock — at most
 * [ChatReply.Choose.limit], the others off once that many are ticked. A chip the person's words or an
 * earlier answer of this chat point at carries a small accent, its words ("Yesterday, from your
 * message") said only to a screen reader, on the chip itself; "keep" takes at once what was carried —
 * the answer given before, standing ticked, or what the message told, standing marked and unticked —
 * and an "Open" chip opens the record the question is about, answering nothing.
 */
@Composable
private fun ChooseReply(reply: ChatReply.Choose, report: SymptomReport, actions: ReplyActions) {
    val chosen = report.answers[reply.step] ?: reply.preset
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    ReplyRow(single = !reply.step.multi) {
        reply.options.forEach { option ->
            val label = stringResource(reply.labels[option] ?: option.labelRes)
            val markRes = when (option) {
                in reply.marked -> R.string.symptom_chat_mark_words
                in reply.chatMarked -> R.string.symptom_chat_mark_chat
                else -> null
            }
            val description = markRes?.let { stringResource(it, label) }
            val chip = description?.let { words -> Modifier.semantics { contentDescription = words } } ?: Modifier
            val accent = Icons.Outlined.FormatQuote.takeIf { markRes != null && option !in chosen }
            val full = reply.limit > 0 && chosen.size >= reply.limit && option !in chosen
            if (reply.step.multi) {
                PwnFilterChip(
                    selected = option in chosen,
                    onClick = { actions.onTick(reply.step, option) },
                    label = label,
                    modifier = chip,
                    enabled = !full,
                    leadingIcon = accent,
                )
            } else {
                val tap = { actions.onTap(reply.step, option) }
                PwnChoiceChip(option in chosen, tap, label, chip, leadingIcon = accent)
            }
        }
    }
    val keep = reply.keep
    val open = reply.openRef
    if (keep != null || open != null) {
        ReplyRow(single = false) {
            keep?.let { text ->
                PwnOutlinedButton(onClick = { actions.onKeep(reply.step, reply.preset.ifEmpty { reply.proposed }) }) {
                    Text(resources.render(text, locale, ZoneId.systemDefault()))
                }
            }
            open?.let { ref ->
                PwnOutlinedButton(onClick = { actions.onOpen(ref) }) {
                    Text(stringResource(R.string.pain_chat_open_that))
                }
            }
        }
    }
}

@Composable
private fun SeverityReply(report: SymptomReport, actions: ReplyActions) {
    ReplyRow(single = true) {
        SymptomSeverity.entries.forEach { choice ->
            PainAnswerChip(report.severity == choice, { actions.onSeverity(choice) }, stringResource(choice.labelRes))
        }
    }
}

/**
 * Chips whose answer is a value: one tapped is taken, or with several the values ticked are joined
 * and wait for the clock, an exclusive value standing alone. A chip with a picker opens the app's own
 * time or day picker, and the value picked is the answer.
 */
@Composable
private fun ValuesReply(reply: ChatReply.Values, report: SymptomReport, actions: ReplyActions) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val held = report.values[reply.step].orEmpty().split(',').filter { it.isNotEmpty() }
    var picking by rememberSaveable(reply.step) { mutableStateOf<String?>(null) }
    ReplyRow(single = !reply.multi) {
        reply.choices.forEach { chip ->
            val label = resources.render(chip.label, locale, ZoneId.systemDefault())
            val tap = {
                when {
                    chip.picker != null -> picking = chip.value
                    reply.multi -> actions.onValue(reply.step, toggledValue(held, chip.value, reply.exclusive))
                    else -> actions.onValue(reply.step, chip.value)
                }
            }
            if (reply.multi) {
                PwnFilterChip(selected = chip.value in held, onClick = tap, label = label)
            } else {
                PainAnswerChip(chip.value in held, tap, label)
            }
        }
    }
    val chip = reply.choices.firstOrNull { it.value == picking }
    when (chip?.picker) {
        ValuePicker.CLOCK -> ClockTimePickerDialog(
            label = resources.render(chip.label, locale, ZoneId.systemDefault()),
            initialMinuteOfDay = doseClockMinute(chip.value) ?: 0,
            onDismiss = { picking = null },
            onConfirm = { minute ->
                picking = null
                actions.onValue(reply.step, pickedDoseClock(chip.value, minute))
            },
        )

        ValuePicker.DAY -> DayPickerDialog(
            initial = chip.value.toLocalDateOrNull() ?: LocalDate.now(),
            onDismiss = { picking = null },
            onPick = { day ->
                picking = null
                actions.onValue(reply.step, day.toString())
            },
        )

        null -> Unit
    }
}

/** [held] with [value] ticked or taken away: an [exclusive] value stands alone, and goes when another is ticked. */
internal fun toggledValue(held: List<String>, value: String, exclusive: Set<String>): String = when {
    value in held -> held - value
    value in exclusive -> listOf(value)
    else -> held.filter { it !in exclusive } + value
}.joinToString(",")

/** The suggestions of a question typed into the composer, at the end side above it: a tap answers with that one. */
@Composable
private fun SuggestionChips(step: SymptomStep, suggestions: List<ValueChip>, actions: ReplyActions) {
    if (suggestions.isEmpty()) return
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    ReplyRow(single = true) {
        suggestions.forEach { chip ->
            PainAnswerChip(false, {
                actions.onValue(step, chip.value)
            }, resources.render(chip.label, locale, ZoneId.systemDefault()))
        }
    }
}

/** Chips on the end side, wrapping; [single] answers are one group to a screen reader. */
@Composable
internal fun ReplyRow(single: Boolean, content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().then(if (single) Modifier.selectableGroup() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * The paperclip in the message field: add a file, or open the lab editor's photo/PDF import.
 */
@Composable
private fun ChatAttachmentButton(enabled: Boolean, onAttach: () -> Unit, onLabs: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (onLabs == null) onAttach() else expanded = true },
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
        ) {
            Icon(Icons.Outlined.AttachFile, stringResource(R.string.attachments_add))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attachments_add)) },
                onClick = {
                    expanded = false
                    onAttach()
                },
            )
            onLabs?.let { open ->
                DropdownMenuItem(
                    text = {
                        Text("${stringResource(R.string.labs_title)} · ${stringResource(R.string.lab_import_pick)}")
                    },
                    onClick = {
                        expanded = false
                        open()
                    },
                )
            }
        }
    }
}

/**
 * The message field and its send button, at the foot of a live chat — and, while a question waits for
 * typed words ([ask]), the answer field: its hint and keyboard, Send answering. Sending is off while
 * the dots run; what was typed stays in the field until it can go. The field takes the keyboard only
 * for a typed question with no chip to tap instead, and never under touch exploration. A message sent
 * puts the keyboard away and gives up the focus, so the chips it hid while typing come back with the
 * bot's answer ([onFocus] says where the focus is).
 */
@Composable
internal fun ChatComposer(
    enabled: Boolean,
    ask: ChatReply.TextAsk?,
    focus: Boolean,
    onFocus: (Boolean) -> Unit,
    onAttach: () -> Unit,
    onLabs: (() -> Unit)?,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val max = ask?.max ?: SYMPTOM_TEXT_MAX
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(ask?.step, focus) { if (focus) runCatching { field.requestFocus() } }
    val send = {
        if (enabled && text.isNotBlank()) {
            // Sent as the log keeps them, trimmed: a reading's spans count from the first character read.
            onSend(text.trim())
            text = ""
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp + LocalBottomBarInset.current),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = typedUpTo(it, max) },
            modifier = Modifier.weight(1f).focusRequester(field).onFocusChanged { onFocus(it.isFocused) },
            placeholder = { Text(stringResource(ask?.hintRes ?: R.string.symptom_chat_hint)) },
            leadingIcon = { ChatAttachmentButton(enabled, onAttach, onLabs) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                keyboardType = if (ask?.keyboard == TextAskKeyboard.NUMBER) KeyboardType.Number else KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(
            onClick = { send() },
            enabled = enabled && text.isNotBlank(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.symptom_chat_send))
        }
    }
}

/**
 * [text] cut to [max] characters, never between the two halves of one character: an emoji pasted across
 * the limit would leave half of itself at the end, which is no character at all.
 */
internal fun typedUpTo(text: String, max: Int): String {
    val kept = text.take(max)
    return if (kept.length < text.length && kept.lastOrNull()?.isHighSurrogate() == true) kept.dropLast(1) else kept
}

/**
 * The note once the chat has ended: the collapsed "Add note" button that grows into a field, and
 * under it, if the words are of suicide, an emergency or too much of a medicine, where help is — the
 * note is read for that as every message is: [cards], by the rules and by the model's reading of the
 * note as it was kept ([noteCards]).
 */
@Composable
internal fun ChatNote(note: String, cards: List<Pair<Int, Int?>>, onNote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteField(note, onNote, hint = R.string.symptom_chat_note_hint)
        NoteSafety(cards)
    }
}

/** Where help is, under a note whose words call for it: each of its [cards] ([noteCards]). */
@Composable
internal fun NoteSafety(cards: List<Pair<Int, Int?>>) {
    cards.forEach { (text, source) ->
        SymptomSafetyCard(stringResource(text), urgent = true) {
            source?.let { Text(stringResource(it)) }
        }
    }
}

/**
 * The files a chat was given, under a past chat: each says what the person said of it and opens on a
 * tap, as it did while the chat was live. A past chat is read as it was, so nothing is added,
 * described or taken off here — [AttachmentsField]'s rows without the pencil and the cross.
 */
@Composable
internal fun PastChatFiles(files: List<MedicalAttachment>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.attachments_title), style = MaterialTheme.typography.labelLarge, color = PwnMuted)
        files.forEach { file ->
            // The name is the answer of the app the file lives in ([attachmentName]): asked once and off
            // the main thread, the last part of the path standing in until it comes.
            val name by produceState(file.uri.toUri().lastPathSegment ?: file.uri, file.uri) {
                value = withContext(Dispatchers.IO) { context.attachmentName(file.uri) }
            }
            // A button to a screen reader, named for what it does: the tap hands the file to another
            // app. The floor keeps the row a whole target where its two lines are short.
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = PwnDimensions.minimumTarget)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_open)) {
                        context.openAttachment(file.uri)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp), tint = PwnMuted)
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(stringResource(file.kind.titleRes), file.studyDay, file.description)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
            }
        }
    }
}

/**
 * The cards under the end note [text] — the note as kept, or as it is being written — live and in the
 * history alike: those its words call for, and those of its reading while it is the note read
 * ([ChatLog.noteRead], [noteSafety]), so a chat opened again shows the note as it was shown.
 */
internal fun ChatLog.noteCards(text: String = note): List<Pair<Int, Int?>> =
    noteSafety(text, noteRead?.takeIf { text.trim() == note })

/**
 * The safety cards a note calls for — each its words and where they come from — as a message's words
 * would: where help is for words of suicide; the line to call for an emergency's; for words of too
 * much of a medicine ([overdoseIn]), which no question can follow once the chat is over, to get advice
 * now. None for anything else. The rules' card first, as their words call for it; then the one
 * [read]'s flags call for, by the same order, when it is another — a flag only adds a card, never
 * puts one of the rules' words in its place.
 */
internal fun noteSafety(note: String, read: ChatRead?): List<Pair<Int, Int?>> {
    val rules = when {
        crisisIn(note) -> NOTE_CRISIS
        emergencyIn(note).isNotEmpty() -> NOTE_EMERGENCY
        overdoseIn(note) -> NOTE_TOO_MUCH
        else -> null
    }
    val flags = read?.takeIf { it.routes }?.safety.orEmpty()
    val flagged = when {
        SymptomStep.CRISIS_NOW in flags -> NOTE_CRISIS
        flags.any { it in EMERGENCY_STEPS } -> NOTE_EMERGENCY
        SymptomStep.MED_MAX_WORDS in flags -> NOTE_TOO_MUCH
        else -> null
    }
    return listOfNotNull(rules, flagged.takeIf { it != rules })
}

private val NOTE_CRISIS: Pair<Int, Int?> = ChatLine.CRISIS_SUPPORT.textRes to ChatLine.CRISIS_SUPPORT.sourceRes
private val NOTE_EMERGENCY: Pair<Int, Int?> = ChatLine.SAFETY.textRes to ChatLine.SAFETY.sourceRes
private val NOTE_TOO_MUCH: Pair<Int, Int?> = R.string.med_chat_alert_too_much to R.string.med_chat_source_too_much

/** The questions of the emergencies' words ([EmergencyWords]). */
private val EMERGENCY_STEPS: Set<SymptomStep> by lazy { EmergencyWords.entries.mapTo(HashSet()) { it.step } }
