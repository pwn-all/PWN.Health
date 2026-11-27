package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Correcting a row that was logged in one tap.
 *
 * A glass of water, a beer, a cigarette, a face on the mood scale — none of these was typed, so
 * none of them has a form to reopen. What each one has is a choice, and the honest way to correct
 * it is to offer that same choice again with the option already selected. The row keeps its id and
 * its timestamp: this is a correction of what happened, not a new event at the moment of noticing.
 *
 * Kinds with no choice to re-make get [simpleOptions] = empty and the editor shows a read-only
 * card with a delete button instead. That is not a stub. There is genuinely nothing in a visit to
 * the toilet a person could correct except whether it happened, and a lab panel or a finished
 * breathing practice belongs to the screen that measured it, not to a text field here.
 */
internal data class SimpleOption(
    /** What gets stored, and what [rebuiltSimpleEntry] reads back — an enum name or a number. */
    val value: String,
    val label: String,
    val detail: String?,
    /**
     * What a screen reader says in place of [label], where the label leads with something drawn
     * rather than said: the mood faces are emoji, and a reader would spell out each face's Unicode name
     * before the word it only illustrates.
     */
    val spoken: String? = null,
)

/**
 * The options this row can be re-chosen among, most-likely first, or empty when it holds no choice.
 *
 * [imperial] reaches only the glasses of water, and what is stored stays millilitres either way.
 * These chips offer the very portions the home screen offers, so a person whose day's water is
 * counted in fluid ounces everywhere else is not asked to recognise one in millilitres here.
 *
 * Caffeine is deliberately absent. It is two choices at once — which drink and which cup — so
 * re-offering it would mean rebuilding the home screen's caffeine dialog here; delete and re-log is
 * two taps.
 */
@Composable
internal fun simpleOptions(kind: String, imperial: Boolean): List<SimpleOption> = when (kind) {
    KIND_WATER -> WATER_PORTIONS_ML.map {
        SimpleOption(it.toString(), formatVolume(LocalResources.current, it, imperial), null)
    }

    KIND_ALCOHOL -> AlcoholDrink.entries.map {
        SimpleOption(it.name, stringResource(it.titleRes), stringResource(it.detailRes))
    }

    KIND_SMOKING -> TobaccoProduct.entries.map {
        SimpleOption(it.name, stringResource(it.titleRes), stringResource(it.detailRes))
    }

    KIND_STOOL -> StoolKind.entries.map {
        SimpleOption(it.name, stringResource(it.titleRes), stringResource(it.detailRes))
    }

    KIND_MOOD -> Mood.entries.map {
        val title = stringResource(it.titleRes)
        SimpleOption(it.name, "${it.emoji} $title", null, spoken = title)
    }

    KIND_MORNING_CHECK -> MorningErection.entries.map { SimpleOption(it.name, stringResource(it.titleRes), null) }

    else -> emptyList()
}

/**
 * Which option a stored row was logged with, or "" when the kind offers none.
 *
 * Alcohol and tobacco are recognised by their gram figure rather than by their subtitle: the
 * subtitle holds the translated word, so a row logged in one language and reopened in another
 * would match nothing. The gram figures are distinct within each enum, which is what makes this
 * work — a check the tests hold to, because adding a drink that ties an existing one would
 * silently break the match rather than fail to compile. They are the drinks' standard pours: a pour
 * of another size or strength holds grams no option carries, and matches none — see [rechoosable].
 */
internal fun simpleValueOf(entry: JournalEntry?): String = when (entry?.kind) {
    null -> ""
    KIND_WATER -> entry.servingG.toInt().toString()
    KIND_ALCOHOL -> AlcoholDrink.entries.firstOrNull { it.ethanolG == entry.servingG }?.name.orEmpty()
    KIND_SMOKING -> TobaccoProduct.entries.firstOrNull { it.tobaccoG == entry.servingG }?.name.orEmpty()
    KIND_STOOL -> stoolKindOf(entry)?.name.orEmpty()
    KIND_MOOD -> Mood.fromScore(entry.moodScore)?.name.orEmpty()
    KIND_MORNING_CHECK -> morningCheckOf(entry.measurementPayloadJson)?.answer?.name.orEmpty()
    else -> ""
}

/**
 * Whether [entry] can be offered its kind's options again: only where it was logged as one of them.
 *
 * A beer of 330 ml, a wine at 14% — a pour of its own — matches no option ([simpleValueOf]), and it
 * would stand under chips none of which is on, with a Save that could not light; a chip tapped there
 * would rebuild the row at that drink's standard pour, the very size the person had changed. The chips
 * cannot say a pour, so such a row is shown as it is, with its bin.
 */
internal fun rechoosable(entry: JournalEntry?): Boolean = simpleValueOf(entry).isNotEmpty()

/**
 * The row as it should now read, built by the same factory that wrote it in the first place.
 *
 * Going back through the factory rather than patching columns by hand is what keeps a corrected
 * row identical to a freshly logged one — the same title, the same derived grams, the same absent
 * calories. Identity, time and provenance are carried over from the original.
 *
 * Returns null when the value does not name an option this build knows, which is what a row
 * restored from a newer backup looks like; the caller leaves such a row alone.
 */
internal fun rebuiltSimpleEntry(
    resources: Resources,
    previous: JournalEntry,
    value: String,
): JournalEntry? {
    val day = previous.day.toLocalDateOrNull() ?: return null
    val fresh = when (previous.kind) {
        KIND_WATER -> value.toIntOrNull()?.let { waterEntry(resources, it, previous.day) }

        KIND_ALCOHOL -> AlcoholDrink.entries.firstOrNull { it.name == value }
            ?.let { alcoholEntry(resources, it, day) }

        KIND_SMOKING -> TobaccoProduct.entries.firstOrNull { it.name == value }
            ?.let { smokingEntry(resources, it, day) }

        KIND_STOOL -> StoolKind.entries.firstOrNull { it.name == value }
            ?.let { stoolEntry(resources, it, day) }

        // The face is what is re-chosen here; the tension the row was logged with stays, in its
        // column and in the note's second word, since a chat that asked it would otherwise lose it
        // to a correction of the face alone. What the mood was about stays too ([rebuiltFrom]).
        KIND_MOOD -> Mood.entries.firstOrNull { it.name == value }
            ?.let { moodEntry(resources, it, energy = Energy.fromScore(previous.energyScore), day = day) }

        // The waking, where it came from and what ended the night are not on the chooser, so they
        // are carried over here: [rebuiltFrom] keeps the old payload only when the new row has
        // none, and a morning's always has one.
        KIND_MORNING_CHECK -> rebuiltMorningCheck(
            resources.getString(R.string.sexual_morning_title),
            previous,
            MorningErection.entries.firstOrNull { it.name == value },
            LocalDate.now(),
            System.currentTimeMillis(),
        )

        else -> null
    }
    // Where the row came from survives the correction: losing the import key makes the next sync
    // import the same foreign record a second time. The factory's own note still wins wherever it
    // writes one — a mood stores its word there, and that word has to be the corrected mood's — so
    // only the kinds that leave it empty inherit.
    return fresh?.rebuiltFrom(previous)
}

/**
 * [previous]'s identity, time and provenance on a freshly built row, plus whatever the factory left
 * empty that the original still held. The bathroom factories take their symptoms as an argument
 * the chooser never has, so a stool corrected from one consistency to another would come back with
 * the blood and the pain it had been logged with wiped; the same silence would take a caffeine figure
 * or a body-fat percentage the day a kind carrying one becomes re-choosable.
 */
internal fun JournalEntry.rebuiltFrom(previous: JournalEntry): JournalEntry = copy(
    id = previous.id,
    occurredAt = previous.occurredAt,
    note = note.ifEmpty { previous.note },
    healthConnectRecordKey = previous.healthConnectRecordKey,
    measurementSource = previous.measurementSource,
    measurementPayloadJson = measurementPayloadJson ?: previous.measurementPayloadJson,
    caffeineMg = caffeineMg ?: previous.caffeineMg,
    bodyFatPercent = bodyFatPercent ?: previous.bodyFatPercent,
    moodScore = moodScore ?: previous.moodScore,
    breathCycles = breathCycles ?: previous.breathCycles,
)

/** The chooser itself: one chip per option, the one it was logged as already on. */
@Composable
internal fun SimpleEntryFields(
    options: List<SimpleOption>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.simple_entry_choice_title),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    PwnFilterChip(
                        selected = option.value == selected,
                        onClick = { onSelected(option.value) },
                        label = option.label,
                        modifier = option.spoken?.let { Modifier.semantics { contentDescription = it } } ?: Modifier,
                    )
                }
            }
            options.firstOrNull { it.value == selected }?.detail?.let {
                Text(it, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * A row there is nothing to correct on: what it says, and the offer to delete it.
 *
 * A visit to the toilet, a finished breathing practice, a planned session, a lab panel. Some of
 * these hold plenty — a panel is a dozen markers — but none of them holds anything this screen
 * could honestly let a person retype. The panel belongs to the labs screen that entered it, and a
 * practice is measured rather than typed. Saying so beats a form that pretends otherwise.
 */
@Composable
internal fun ReadOnlyEntryCard(entry: JournalEntry?, imperial: Boolean) {
    entry ?: return
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.shownTitle(LocalResources.current), style = MaterialTheme.typography.titleMedium)
            entry.shortDetails(imperial).takeIf { it.isNotBlank() }?.let { Text(it, color = PwnMuted) }
            Text(
                stringResource(R.string.simple_entry_fixed_body),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
    }
}
