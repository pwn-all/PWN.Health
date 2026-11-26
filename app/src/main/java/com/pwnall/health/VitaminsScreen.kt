package com.pwnall.health

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The way to the vitamin table from inside the meal flow, provided where the routes are.
 *
 * Ambient rather than a parameter for the reason [LocalAllergyAnswer] is: the door is on the food
 * hub, a page of the entry editor, which has no other reason to know this route exists. Null where
 * nothing provides it — a preview, a test — and then the hub draws no door, the way a screen draws
 * none for a callback it was not handed.
 */
internal val LocalOpenVitamins = compositionLocalOf<(() -> Unit)?> { null }

/** The windows the page offers, in days. Both reach [FOOD_MICROS_MIN_DAYS] counted days if logged. */
private val VITAMIN_PERIODS = listOf(30, 90)

/**
 * Vitamins and minerals against the daily figures for this person, over the last month or quarter:
 * the report's own table ([nutrientTable]), read over both halves — the meals and the doses —
 * because on the phone there is nothing to leave off.
 *
 * Every line says what it rests on, as the sheet does, and the page says nothing the sheet would
 * not. Where there is nothing to read yet it says what would fill it, rather than printing eighteen
 * rows of nothing.
 */
@Composable
internal fun VitaminsScreen(modifier: Modifier, profile: UserProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val today = rememberToday()
    var days by rememberSaveable { mutableIntStateOf(VITAMIN_PERIODS.first()) }
    val from = today.minusDays(days - 1L)
    // Each list rides with the first day it was read from: the collected state keeps the last
    // window's rows until the new query answers, and a quarter is not to be read off a month.
    val journal by remember(dao, from, today) {
        dao.journalBetween(from.toString(), today.toString()).map { from to it }
    }.collectAsStateWithLifecycle<Pair<LocalDate, List<JournalEntry>>?>(null)
    val medications by remember(dao) { dao.medications() }.collectAsStateWithLifecycle(emptyList())
    // Off the composition thread, as the report is built: the catalogue's figures are read in on
    // the first ask, and every meal of a quarter is matched against them.
    //
    // Emptied first, whatever changed: produceState keeps its last value across new keys, so a
    // switch to the quarter would go on showing the month's figures under the quarter's name until
    // the new table is ready.
    val table by produceState<NutrientTable?>(null, journal, medications, profile, from, today) {
        value = null
        val entries = journal?.takeIf { it.first == from }?.second ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val now = LocalTime.now()
            nutrientTable(
                entries,
                medications,
                profile,
                from,
                today,
                today,
                now.hour * 60 + now.minute,
                ZoneId.systemDefault(),
                includeFood = true,
                includeSupplements = true,
            )
        }
    }
    Column(
        modifier.fillMaxSize()
            .panelVerticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = LocalBottomBarInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitaminsHero(onBack)
        // Two short answers to one question, so the segmented control: the window in force is a
        // filled pill, not a tint, and a screen reader hears one chosen out of a pair.
        PwnSegmentedControl(
            options = VITAMIN_PERIODS.map { pluralStringResource(R.plurals.vitamins_period, it, it) to null },
            selected = VITAMIN_PERIODS.indexOf(days),
            onSelected = { days = VITAMIN_PERIODS[it] },
        )
        val shown = table
        when {
            // Being counted: said here, where the table will stand, rather than by an empty page.
            shown == null -> LinearProgressIndicator(Modifier.fillMaxWidth())

            shown.empty -> PwnCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vitamins_nothing_yet), Modifier.padding(18.dp))
            }

            else -> NutrientTableCard(shown)
        }
    }
}

/**
 * The page's own head, as the report's is drawn: the way back in a disc, the title large, and the
 * drawing beside it — a page of its own rather than a section of another.
 */
@Composable
private fun VitaminsHero(onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.size(4.dp))
        Box(
            // The disc stays the report's 46dp; what a finger reaches grows to the 48dp minimum.
            Modifier.minimumInteractiveComponentSize()
                .size(46.dp)
                .background(PwnSurfaceElevated, CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.common_back),
                tint = PwnMuted,
            )
        }
        val title = stringResource(R.string.report_part_micronutrients)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The question mark follows the title's last word rather than standing out beside the
            // drawing, so it reads as belonging to the words it explains.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false).semantics { heading() },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                // What the table is read from, and what its figures cannot say, is worth reading
                // once; it sits behind the question mark instead of standing open over the figures
                // on every visit. The page always reads both halves, so the caveat is the one for
                // meals and doses together.
                HelpDot(title) {
                    Text(stringResource(R.string.vitamins_intro))
                    Text(stringResource(micronutrientCaveatRes(food = true, supplements = true)))
                }
            }
            Image(themePainterResource(R.drawable.ic_art_fruit), contentDescription = null, Modifier.size(96.dp))
        }
    }
}

/** The rows in one card, with the food's days over them. */
@Composable
private fun NutrientTableCard(table: NutrientTable) {
    val resources = LocalResources.current
    Text(
        foodLoggedText(table).resolve(resources),
        color = PwnMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    PwnCard(Modifier.fillMaxWidth()) {
        Column {
            table.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = PwnBorder)
                NutrientLine(row, table.foodIncluded)
            }
        }
    }
}

/**
 * One nutrient: its name and what may be said about it, the daily figure and the upper levels, and
 * the food and the doses each with the days behind them — the sheet's three lines, in the same words.
 */
@Composable
private fun NutrientLine(row: NutrientRow, foodIncluded: Boolean) {
    val resources = LocalResources.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(row.nutrient.titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        row.statusText()?.let {
            Text(it.resolve(resources), color = PwnAccentInk, fontWeight = FontWeight.Medium)
        }
        Text(row.figuresText().resolve(resources), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        if (foodIncluded) {
            NutrientSource(
                stringResource(R.string.nutrient_from_food),
                row.foodText().resolve(resources),
                row.foodNote()?.resolve(resources),
            )
        }
        row.supplements?.let { window ->
            NutrientSource(
                stringResource(R.string.nutrient_from_supplements),
                window.markedText(row.nutrient).resolve(resources),
                window.markedNote().resolve(resources),
            )
        }
    }
}

/** Where some of it came from, the figure on the right, and the counts it stands on under both. */
@Composable
private fun NutrientSource(label: String, value: String, note: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
    note?.let { Text(it, color = PwnMuted, style = MaterialTheme.typography.bodySmall) }
}
