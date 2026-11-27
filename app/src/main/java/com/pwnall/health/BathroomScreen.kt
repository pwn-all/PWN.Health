package com.pwnall.health

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/**
 * The bathroom, on its own screen.
 *
 * Not a list on the habits screen beside alcohol, tobacco and sexual activity, which would put four
 * unrelated private things in one list. They are separate questions asked at separate moments —
 * nobody logs a cigarette and a bowel movement in the same sitting — so they are separate screens.
 *
 * The two questions here are one question with two ends, and the screen draws them that way: a
 * single card cut in half by a hairline, the bowel movement on one side and urination on the
 * other, each carrying the day's count. Four squares in a grid would say these were four unrelated
 * things and make the reader find the heading above them to learn otherwise; a card divided down
 * the middle says which end is being answered before a word of it is read.
 *
 * Tapping a half opens the rest of it underneath rather than over it — arrows for which of the
 * three a bowel movement was, nothing to choose on the urination side, and under either of them
 * what the visit felt like, folded away because on most visits there is nothing to say. A window
 * over the screen would hide the half that opened it and take the scroll hostage for a decision
 * that takes two seconds.
 *
 * Nothing here reaches Health Connect: it has no record type for any of it, the sensations least
 * of all.
 */
@Composable
fun BathroomScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val quickLog = rememberQuickLog()
    val journal by remember(today) {
        db.dao().journalBetween(today.minusDays(HABIT_WINDOW_DAYS - 1L).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    val day = remember(journal, today) { bathroomDay(journal, today) }
    val week = remember(journal, today) { bathroomWeek(journal, today) }
    // Which half is open, and what has been ticked under it. The ticks belong to the visit being
    // filed rather than to the screen: they go in with it and are gone the moment it is filed.
    var open by remember { mutableStateOf<BathroomHalf?>(null) }
    var notes by remember { mutableStateOf(emptySet<BathroomNote>()) }

    Column(modifier.fillMaxSize()) {
        val title = stringResource(R.string.bathroom_title)
        // Why a visit is worth a tap: once, behind the question mark in the bar.
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.bathroom_subtitle)) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                BathroomHalves(day, open) { half ->
                    // A second tap on the half that is already lit closes it again, exactly as a
                    // smoking tile does, and the ticks close with it: what one visit felt like is not
                    // an answer about the next one.
                    open = if (open == half) null else half
                    notes = emptySet()
                }
                open?.let { half ->
                    BathroomChooser(
                        half = half,
                        quickLog = quickLog,
                        notes = notes,
                        onToggle = { note -> notes = if (note in notes) notes - note else notes + note },
                    ) { kind, confirmation ->
                        // Taken out of the ticks here rather than inside the write: the card is
                        // emptied two lines below and the write runs after that.
                        val felt = BathroomNote.entries.filter { it in notes }
                        quickLog.log(scope, bathroomKey(kind), confirmation) {
                            // The day is read at the tap, not at composition: a screen left open
                            // across midnight would file this visit under yesterday.
                            val now = LocalDate.now()
                            if (kind == null) {
                                // Through logUrination rather than a plain save: the button
                                // shuts behind the confirmation, but the same three-second window
                                // guards the widgets, and a second visit logged by a bounced touch is
                                // invisible afterwards.
                                db.logUrination(urinationEntry(resources, now, felt))
                            } else {
                                db.saveJournalEvent(stoolEntry(resources, kind, now, felt))
                            }
                        }
                        open = null
                        notes = emptySet()
                    }
                }

                if (week.looseStools > 0) {
                    // The words are the same either side of the line, so the colour is not left to say
                    // alone that the week has crossed it: the line is set a step heavier as well.
                    val noted = week.looseStools >= LOOSE_WEEK_NOTE
                    Text(
                        pluralStringResource(R.plurals.stool_loose_week, week.looseStools, week.looseStools),
                        color = if (noted) PwnWarn else PwnMuted,
                        fontWeight = if (noted) FontWeight.SemiBold else null,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.urination_hint),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )

                DataNoteLink(R.string.bathroom_disclaimer)
                Spacer(Modifier.height(12.dp))
            }
            QuickLogFlash(quickLog)
        }
    }
}

/** Which end of the visit is being answered. The card is one control; this says which half of it. */
private enum class BathroomHalf { STOOL, URINATION }

/**
 * The card cut in half, the shape the food hub's two doors are drawn in.
 *
 * The counts sit on the halves rather than in headings above them: a heading reading "bowel
 * movement" over a tile reading "bowel movement" is the same words twice, and what is worth having
 * beside the picture is how many there have been since midnight. The open half is lit the way a
 * tile that has opened a chooser is lit everywhere else here, and — because that light is only
 * 1.14:1 against the half beside it — its name is set in the accent and a step heavier as well,
 * with the tap itself reported as a selection so a screen reader hears which half is answered.
 *
 * Neither half is ever shut while the confirmation is up. A tap here files nothing — it opens a
 * card whose Add button is the thing the three-second window closes.
 */
@Composable
private fun BathroomHalves(day: BathroomDay, open: BathroomHalf?, onHalf: (BathroomHalf) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        // Intrinsic height so the divider is exactly as tall as the taller half rather than
        // stretching the card or falling short of it.
        Row(Modifier.height(IntrinsicSize.Min)) {
            BathroomHalfTile(
                art = R.drawable.ic_art_stool_normal,
                title = stringResource(R.string.stool_title),
                today = pluralStringResource(R.plurals.bathroom_today, day.stools, day.stools),
                open = open == BathroomHalf.STOOL,
                modifier = Modifier.weight(1f),
            ) { onHalf(BathroomHalf.STOOL) }
            VerticalDivider(Modifier.padding(vertical = 14.dp), color = PwnBorder)
            BathroomHalfTile(
                art = R.drawable.ic_art_urination,
                title = stringResource(R.string.urination_title),
                today = pluralStringResource(R.plurals.bathroom_today, day.urinations, day.urinations),
                open = open == BathroomHalf.URINATION,
                modifier = Modifier.weight(1f),
            ) { onHalf(BathroomHalf.URINATION) }
        }
    }
}

/** One side of it: the picture, what it is, and how many today. */
@Composable
private fun BathroomHalfTile(
    art: Int,
    title: String,
    today: String,
    open: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ground = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Column(
        // Ground under the click rather than over it, or it paints out the ripple that answers
        // the finger.
        modifier.fillMaxHeight()
            .background(ground)
            // Which half is open cannot be drawn in that ground alone: the ground
            // is 1.12:1 against the shut half by day and 1.48:1 at night — nowhere near the 3:1 a
            // control's state has to hold. An outline, the way a chosen PwnTile or segment gets
            // one, cannot rescue it here: both halves sit inside one PwnCard, and its 28dp
            // corners would cut the border off at the two of them each half touches. So the state
            // is said rather than only tinted — selectable carries it to a screen reader, and the
            // title below carries it in weight as well as hue.
            .selectable(selected = open, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(themePainterResource(art), null, Modifier.size(HALF_ART))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            // The accent ink on the lit ground: PwnAccentInk reads 14.04:1 on primaryContainer by
            // day and 5.33:1 at night, over the 4.5:1 this 14sp title needs. The shut half drops
            // one step of weight so the two states are still told apart with the hue ignored.
            color = if (open) PwnAccentInk else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (open) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(today, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
    }
}

/**
 * What a half opens: the picture, an arrow either side of it where there is something to choose,
 * the sensations folded under that, and an Add that files whatever it all settled on.
 *
 * A card below the halves rather than a window over them, which is how every choice-inside-a-choice
 * works here. The bowel side opens on [StoolKind.NORMAL] because that is what most visits are, so
 * the commonest answer is already under the thumb with the other two an arrow away. The urination
 * side has nothing to choose and so has no arrows, but keeps the picture at the same size and the
 * ticks in the same place: it is the same control with its question already answered.
 *
 * [onAdd] is handed the kind the arrows settled on — null on the urination side, which has none —
 * and the words to flash over the screen, because the name inside them is a string only this card
 * has resolved.
 */
@Composable
private fun BathroomChooser(
    half: BathroomHalf,
    quickLog: QuickLogState,
    notes: Set<BathroomNote>,
    onToggle: (BathroomNote) -> Unit,
    onAdd: (StoolKind?, String) -> Unit,
) {
    // Keyed to the half so that opening the other side does not inherit an arrow moved over here.
    var index by remember(half) { mutableIntStateOf(StoolKind.entries.indexOf(StoolKind.NORMAL)) }
    val kind = if (half == BathroomHalf.STOOL) StoolKind.entries[index] else null
    val art = kind?.art() ?: R.drawable.ic_art_urination
    val label = stringResource(kind?.titleRes ?: R.string.urination_title)
    val confirmation = quickLogAdded(label)
    val offered = BathroomNote.entries
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The same heading on both sides. On the urination side the question under it is
            // answered by the drawing itself, and a card that changed shape between the halves
            // would read as two controls where the one above it reads as one.
            Text(stringResource(R.string.bathroom_pick), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (kind != null) {
                    // Mirrored arrows, so in Arabic the earlier type still points to where it lies. Named
                    // for the type they step through rather than "Back", which is the top bar's arrow
                    // out of the screen.
                    IconButton(onClick = {
                        index--
                    }, enabled = index > 0, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            stringResource(R.string.bathroom_kind_previous),
                        )
                    }
                }
                // Centred in what is left over, so the drawing sits in the middle of the card
                // whether or not there are arrows beside it.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Image(themePainterResource(art), null, Modifier.size(CHOOSER_ART))
                }
                if (kind != null) {
                    IconButton(
                        onClick = {
                            index++
                        },
                        enabled = index < StoolKind.entries.lastIndex,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = PwnMuted,
                        ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            stringResource(R.string.bathroom_kind_next),
                        )
                    }
                }
            }
            // Focus stays on the arrow after a tap, so the type it landed on is announced from here.
            Text(
                label,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(kind?.detailRes ?: R.string.urination_detail), color = PwnMuted)
            // Folded behind the one line that says what is ticked: on most visits nothing is, and
            // six open rows would be six rows of nothing between the picture and the button.
            PwnTickField(
                label = stringResource(R.string.bathroom_notes_title),
                options = offered.map { stringResource(it.titleRes) },
                selected = offered.indices.filter { offered[it] in notes }.toSet(),
                onToggle = { onToggle(offered[it]) },
            )
            Button(
                onClick = { onAdd(kind, confirmation) },
                enabled = !quickLog.locked(bathroomKey(kind)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_add))
            }
        }
    }
}

/**
 * The Add button is one button, but its cooldown belongs to the answer it files: the three seconds
 * after a normal stool are no reason to refuse a loose one, and the urination side is not the bowel
 * side at all. Both ends of the lock — the write and the button's `enabled` — read the key here so
 * they cannot drift apart.
 */
private fun bathroomKey(kind: StoolKind?): String = kind?.name ?: "urination"

/** Loose stools in a week past which the line is worth colouring rather than just printing. */
private const val LOOSE_WEEK_NOTE = 3

/** Small on the card, where the drawing is a label; large in the chooser, where it is the answer. */
private val HALF_ART = 48.dp
private val CHOOSER_ART = 88.dp

/**
 * One drawing per answer, and each of the three is a shape rather than a portrait: what has to
 * cross the room in a glance is which of the three the visit was, and a screen somebody may open
 * with company in it is no place for a literal picture of any of them. The ordinary one fronts the
 * whole left half of the card as well — a half needs a face, and the answer most visits give is an
 * honest one to put on it. The toilet is the drawing at the top of this screen, where a picture of
 * the room rather than of the visit is exactly what a heading wants.
 */
private fun StoolKind.art(): Int = when (this) {
    StoolKind.NORMAL -> R.drawable.ic_art_stool_normal
    StoolKind.STRAINED -> R.drawable.ic_art_stool_strained
    StoolKind.LOOSE -> R.drawable.ic_art_stool_loose
}
