package com.pwnall.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What a person says about their own allergies, and what the app may do with the answer.
 *
 * The vocabulary is the fourteen substances Annex II of Regulation (EU) No 1169/2011 requires a
 * food label to declare — read from the consolidated text of 2025-04-01 at
 * <https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX%3A02011R1169-20250401>. It is
 * the list used because it is the list a label is written against: the point of holding it is to
 * be able to match it to a packet, and a vocabulary nobody prints on packaging could not be.
 *
 * It is therefore a **food** list. Reactions to medicines, insect stings, latex or pollen are real
 * allergies and are not here; the app has nowhere to record them, the report says so, and the
 * documentation lists it as a gap rather than pretending the fourteen are exhaustive.
 *
 * Nothing here is a diagnosis or a test result. Every answer is what the person said about
 * themselves, and everything that prints one says so.
 */
enum class Allergen(val titleRes: Int) {
    /** Wheat, spelt, khorasan, rye, barley, oats and their hybrids. */
    GLUTEN(R.string.allergen_gluten),
    CRUSTACEANS(R.string.allergen_crustaceans),
    EGGS(R.string.allergen_eggs),
    FISH(R.string.allergen_fish),
    PEANUTS(R.string.allergen_peanuts),
    SOY(R.string.allergen_soy),
    MILK(R.string.allergen_milk),

    /**
     * The eight the regulation names: almond, hazelnut, walnut, cashew, pecan, Brazil nut,
     * pistachio and macadamia.
     *
     * Not "anything called a nut". A coconut, a chestnut, a nutmeg and a butternut squash are none
     * of them on the list, and a matcher that read the word would put three of them there.
     */
    TREE_NUTS(R.string.allergen_tree_nuts),
    CELERY(R.string.allergen_celery),
    MUSTARD(R.string.allergen_mustard),
    SESAME(R.string.allergen_sesame),

    /** Above 10 mg/kg or 10 mg/l as total SO₂, which is what the regulation sets the line at. */
    SULPHITES(R.string.allergen_sulphites),
    LUPIN(R.string.allergen_lupin),
    MOLLUSCS(R.string.allergen_molluscs),
}

/**
 * The answer itself, in the four states it can honestly be in.
 *
 * [NotTested] and [NoneKnown] are different answers and the difference is the whole reason this
 * exists: "I have never been checked" is a statement about what nobody knows, and "I react to
 * nothing I am aware of" is a statement about the person. A summary handed to a doctor prints them
 * differently, and one of them is why the app can offer to put a test on the check-ups page.
 *
 * [Unanswered] belongs to profiles made before this question existed. It is not a fifth kind of
 * allergy; it is the app admitting it never asked.
 */
sealed interface AllergyAnswer {
    data object Unanswered : AllergyAnswer

    data object NotTested : AllergyAnswer

    data object NoneKnown : AllergyAnswer

    data class Known(val allergens: Set<Allergen>) : AllergyAnswer
}

/** What [AllergyAnswer.NotTested] is spelled as inside the column. */
private const val NOT_TESTED = "NOT_TESTED"

/** And [AllergyAnswer.NoneKnown]. Neither can collide with an [Allergen] name. */
private const val NONE_KNOWN = "NONE"

/**
 * The answer as the profile column holds it: one string, because a set of at most fourteen names
 * is not a table, and because the three states that are not a set have to live in the same column
 * or the two could disagree.
 *
 * A name the parser does not know is dropped rather than guessed at — a column written by a newer
 * version, or edited by hand in a backup, must not be able to invent an allergy.
 */
fun allergyAnswerOf(stored: String?): AllergyAnswer {
    val text = stored?.trim().orEmpty()
    return when {
        text.isEmpty() -> AllergyAnswer.Unanswered

        text == NOT_TESTED -> AllergyAnswer.NotTested

        text == NONE_KNOWN -> AllergyAnswer.NoneKnown

        else -> text.split(',')
            .mapNotNull { name -> Allergen.entries.firstOrNull { it.name == name.trim() } }
            .toSet()
            .let { if (it.isEmpty()) AllergyAnswer.Unanswered else AllergyAnswer.Known(it) }
    }
}

/** The same answer on its way back into the column, in the enum's own order so it is stable. */
fun AllergyAnswer.stored(): String = when (this) {
    AllergyAnswer.Unanswered -> ""
    AllergyAnswer.NotTested -> NOT_TESTED
    AllergyAnswer.NoneKnown -> NONE_KNOWN
    is AllergyAnswer.Known -> Allergen.entries.filter { it in allergens }.joinToString(",") { it.name }
}

/** The allergens this person reported, which is empty for every answer that is not a list of them. */
fun AllergyAnswer.allergens(): Set<Allergen> = (this as? AllergyAnswer.Known)?.allergens.orEmpty()

/** The profile's own answer, read through the one parser. */
fun UserProfile.allergyAnswer(): AllergyAnswer = allergyAnswerOf(allergies)

/** The answer with [allergen] ticked or unticked; unticking the last one leaves the question unanswered. */
fun AllergyAnswer.toggled(allergen: Allergen): AllergyAnswer {
    val next = allergens().let { if (allergen in it) it - allergen else it + allergen }
    return if (next.isEmpty()) AllergyAnswer.Unanswered else AllergyAnswer.Known(next)
}

/**
 * The fourteen, behind a panel rather than standing open on the page.
 *
 * A wall of fourteen tick boxes would be what the question looks like to everybody who reacts to
 * none of them — which is most people — with the two answers that are not a list above it,
 * competing with it. The list lives where the report's own long choices live, in
 * [PwnPickerSheet], and the page holds one row saying what has been chosen.
 *
 * Ticking writes through immediately rather than into a draft: this is one answer about one
 * person, not a set of sections being assembled, and the button under it is a way out rather than
 * a commitment. Unticking the last one puts the question back to unanswered, which is how somebody
 * who ticked by mistake gets back to saying "none that I know of".
 */
@Composable
internal fun AllergyPickerSheet(answer: AllergyAnswer, onAnswer: (AllergyAnswer) -> Unit, onDismiss: () -> Unit) {
    // Kept here as well as written through, from the answer the panel opened on: the profile writes
    // what it is handed whole, and a second tick before the database has handed the first one back
    // would be worked out from an answer without it, and write it away.
    var ticked by remember { mutableStateOf(answer) }
    PwnPickerSheet(
        title = stringResource(R.string.allergy_title),
        subtitle = stringResource(R.string.allergy_pick),
        onDismiss = onDismiss,
        action = {
            Button(
                onClick = onDismiss,
                // A floor rather than a height: at a large font size the word outgrows 52 dp, and a
                // fixed height would cut it in half.
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(PwnCorners.extraLarge),
            ) { Text(stringResource(R.string.common_done)) }
        },
    ) {
        Column {
            Allergen.entries.forEach { allergen ->
                PwnTickRow(
                    label = stringResource(allergen.titleRes),
                    checked = allergen in ticked.allergens(),
                    onToggle = {
                        ticked = ticked.toggled(allergen)
                        onAnswer(ticked)
                    },
                )
            }
            Text(
                stringResource(R.string.allergy_not_exhaustive),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * The row that opens it, and the whole of what the page shows about the fourteen.
 *
 * What it says is the answer itself — the allergens in the order the regulation lists them, or the
 * invitation where there are none — because a row that said "Allergens ›" would make a person open
 * a panel to find out what they had already told it.
 */
@Composable
internal fun AllergyPickerRow(answer: AllergyAnswer, onOpen: () -> Unit) {
    val chosen = answer.allergens()
    // map, not joinToString: the transform of a non-inline call cannot hold a composable, and an
    // allergen's own name is one.
    val names = Allergen.entries.filter { it in chosen }.map { stringResource(it.titleRes) }
    ProfileMenuRow(
        art = null,
        icon = Icons.Outlined.Checklist,
        background = PwnBlueSoft,
        title = names.joinToString().ifEmpty { stringResource(R.string.allergy_pick) },
        subtitle = if (chosen.isEmpty()) {
            null
        } else {
            pluralStringResource(R.plurals.allergy_chosen_count, chosen.size, chosen.size)
        },
        divider = false,
        onClick = onOpen,
    )
}
