package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A panel's rows: how many were tested and how many came back positive, the positive ones, and the
 * rest one tap away. A panel is mostly zeros, and thirty lines of them would bury the two that are
 * not.
 */
@Composable
internal fun AllergyPanelRows(rows: List<AllergenResult>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val positive = rows.filter(AllergenResult::positive)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.allergy_panel_count, rows.size, positive.size),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        (if (all) rows else positive).forEach { AllergenLine(it) }
        if (!all && rows.size > positive.size) {
            TextButton(onClick = { all = true }) { Text(stringResource(R.string.allergy_panel_show_all, rows.size)) }
        }
    }
}

@Composable
private fun AllergenLine(row: AllergenResult) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val weight = if (row.positive) FontWeight.SemiBold else FontWeight.Normal
        Text(row.code, Modifier.width(CODE_WIDTH), fontWeight = weight)
        Text(row.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = weight)
        Text(
            stringResource(
                R.string.allergy_panel_class_value,
                row.igeClass,
                row.figureText(LocalLocale.current.platformLocale),
            ),
            color = if (row.positive) MaterialTheme.colorScheme.onSurface else PwnMuted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = weight,
        )
    }
}

private val CODE_WIDTH = 56.dp

/** The results page's panels, newest first, each under the day it was drawn. */
@Composable
internal fun AllergyPanelsCard(panels: List<Pair<String, List<AllergenResult>>>) {
    if (panels.isEmpty()) return
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.allergy_panels_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            panels.forEachIndexed { index, (day, rows) ->
                key(index, day) {
                    Text(labDayText(day), fontWeight = FontWeight.SemiBold)
                    AllergyPanelRows(rows)
                }
            }
        }
    }
}

/**
 * The foods of the profile's fourteen that came back positive and the profile does not list yet,
 * offered — ticked, for the person to untick — and written only on the button. Sensitised is not
 * allergic, and the question mark says so; the answer stays the person's.
 */
@Composable
internal fun AllergySuggestion(rows: List<AllergenResult>, answer: AllergyAnswer, onAdd: (Set<Allergen>) -> Unit) {
    val evidence = rows.filter(AllergenResult::positive).groupBy { it.foodAllergen() }
    val offered = Allergen.entries.filter { it in evidence && it !in answer.allergens() }
    if (offered.isEmpty()) return
    var ticked by remember(offered) { mutableStateOf(offered.toSet()) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = stringResource(R.string.allergy_panel_suggest_title)
            HelpTitle(title, Modifier.semantics { heading() }) {
                Text(stringResource(R.string.allergy_panel_suggest_note))
            }
            offered.forEach { allergen ->
                val found = evidence[allergen].orEmpty().map {
                    val figure = it.figureText(LocalLocale.current.platformLocale)
                    "${it.code} · " + stringResource(R.string.allergy_panel_class_value, it.igeClass, figure)
                }
                PwnTickRow(
                    label = stringResource(allergen.titleRes),
                    checked = allergen in ticked,
                    onToggle = { ticked = if (allergen in ticked) ticked - allergen else ticked + allergen },
                    detail = found.joinToString(),
                )
            }
            Button(
                onClick = { onAdd(ticked) },
                enabled = ticked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
            ) { Text(stringResource(R.string.allergy_panel_suggest_add)) }
        }
    }
}
