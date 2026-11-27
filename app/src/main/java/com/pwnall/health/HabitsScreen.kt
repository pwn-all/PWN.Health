package com.pwnall.health

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Alcohol: a tile per drink, and behind each the pour it actually was — its size and its strength.
 *
 * Tobacco has a screen of its own ([SmokingScreen]). The two answer the same question for a blood
 * panel but they are not the same habit, and one screen of nine near-identical rows is a screen
 * nobody reads: the door on the add sheet says which of the two it opens, and each screen lays out
 * its own products as pictures rather than as a list.
 *
 * A tile opens its drink's card under the grid, as a hookah or a snus tile does on [SmokingScreen]:
 * not every beer is half a litre at 5%, and a glass of wine is whatever was poured. The week's count,
 * why any of this is asked and where it is kept are behind the question mark in the bar, one rule
 * apart, rather than in grey lines under the tiles.
 *
 * Nothing here goes to Health Connect: it has no record type for alcohol.
 */
@Composable
fun AlcoholScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val journal by remember(today) {
        dao.journalBetween(today.minusDays(HABIT_WINDOW_DAYS - 1L).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    val week = remember(journal, today) { habitWeek(journal, today) }
    val day = remember(journal, today) { habitDay(journal, today) }
    val quickLog = rememberQuickLog()
    var chooser by remember { mutableStateOf<AlcoholDrink?>(null) }

    Column(modifier.fillMaxSize()) {
        val title = stringResource(R.string.alcohol_title)
        PwnTopBar(title, onBack) {
            HelpDot(title) {
                AlcoholWeek(week)
                HorizontalDivider(color = PwnBorder)
                Text(stringResource(R.string.habits_subtitle))
                HorizontalDivider(color = PwnBorder)
                Text(stringResource(R.string.habits_disclaimer))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                HabitHeader(today = stringResource(R.string.alcohol_today, day.standardDrinks.shown()))
                val drinks = AlcoholDrink.entries
                PwnTileGrid(drinks.size) { index ->
                    val drink = drinks[index]
                    PwnTile(
                        art = drink.art(),
                        title = stringResource(drink.titleRes),
                        detail = stringResource(drink.detailRes),
                        // Like a hookah tile: it opens its card and files nothing, so it holds no
                        // cooldown; the Add inside the card carries this drink's.
                        expanded = chooser == drink,
                    ) {
                        chooser = if (chooser == drink) null else drink
                    }
                }
                chooser?.let { drink ->
                    AlcoholChooser(drink, quickLog) { ml, abvTenths, confirmation ->
                        quickLog.log(scope, drink.name, confirmation) {
                            // The day is read at the tap, not at composition: a screen left open across
                            // midnight would file this drink under yesterday. The guarded write rather
                            // than a bare save: a tap that arrives twice answers false and the tick
                            // comes straight back down, instead of claiming a drink nobody had.
                            db.logAlcohol(alcoholEntry(resources, drink, LocalDate.now(), ml, abvTenths))
                        }
                        chooser = null
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            QuickLogFlash(quickLog)
        }
    }
}

/** The week against the reporting line, and on how many days; set heavier and in the warning colour past it. */
@Composable
private fun AlcoholWeek(week: HabitWeek) {
    val overWeek = week.ethanolG >= ALCOHOL_WEEKLY_G
    Text(
        pluralStringResource(
            R.plurals.alcohol_week,
            week.shownStandardDrinks,
            week.shownStandardDrinks,
            (ALCOHOL_WEEKLY_G / STANDARD_DRINK_G).roundToInt(),
        ),
        color = if (overWeek) PwnWarn else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (overWeek) FontWeight.SemiBold else FontWeight.Medium,
    )
    if (week.drinkingDays > 0) {
        Text(pluralStringResource(R.plurals.habit_days, week.drinkingDays, week.drinkingDays))
    }
}

/**
 * What a drink tile opens: the glass between two arrows, the size typed or stepped, the strength on
 * a ruler, and an Add that files the pour.
 *
 * The arrows step a beer through its three bottles and everything else by 25 ml; a size typed into
 * the box steps from there to its neighbour. The glass is drawn at three sizes — small, medium,
 * large — and the word under it says which, so the picture changes when the pour crosses a line
 * rather than on every 25 ml.
 */
@Composable
private fun AlcoholChooser(
    drink: AlcoholDrink,
    quickLog: QuickLogState,
    onAdd: (ml: Int, abvTenths: Int, confirmation: String) -> Unit,
) {
    var typed by remember(drink) { mutableStateOf(drink.ml.toString()) }
    var abvTenths by remember(drink) { mutableIntStateOf(drink.abvTenths) }
    val ml = typed.toIntOrNull()?.takeIf { it in 1..MAX_POUR_ML }
    val size = drink.sizeOf(ml ?: drink.ml)
    val artSize by animateDpAsState(
        BOWL_SIZES[size.ordinal],
        animationSpec = PwnMotion.spatial(),
        label = "drinkArt",
    )
    val resources = LocalResources.current
    val label = stringResource(drink.titleRes)
    val confirmation = quickLogAdded(label)
    // The card opens under the tiles, past the bottom of a phone's screen with its ruler and button.
    val card = remember { BringIntoViewRequester() }
    LaunchedEffect(drink) {
        withFrameNanos {}
        card.bringIntoView()
    }
    PwnCard(Modifier.fillMaxWidth().bringIntoViewRequester(card)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val from = ml ?: drink.ml
            ArrowStep(
                art = drink.art(),
                artSize = artSize,
                smaller = stringResource(R.string.ruler_decrease),
                larger = stringResource(R.string.ruler_increase),
                onSmaller = drink.stepMl(from, up = false)?.let { stop -> { typed = stop.toString() } },
                onLarger = drink.stepMl(from, up = true)?.let { stop -> { typed = stop.toString() } },
            )
            // Focus stays on the arrow after a tap, so the size it landed on is announced from here.
            Text(
                "$label · ${stringResource(size.titleRes)}",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            ValueBox(
                value = typed,
                unit = stringResource(R.string.unit_ml),
                label = stringResource(R.string.workout_volume),
                modifier = Modifier.width(compactBoxWidth(typed)),
                onValue = { typed = it.digitsOnly() },
            )
            val strength = stringResource(R.string.alcohol_strength)
            Text(strength, style = MaterialTheme.typography.labelLarge, color = PwnMuted)
            RulerPicker(
                values = drink.abvRangeTenths.step(ABV_STEP_TENTHS).toList(),
                value = abvTenths,
                onValue = { abvTenths = it },
                label = {
                    resources.getString(
                        R.string.alcohol_abv_value,
                        (it / 10.0).pretty(resources.configuration.locales[0]),
                    )
                },
                major = { it % 50 == 0 },
                name = strength,
            )
            ml?.let {
                val grams = drink.pouredG(it, abvTenths)
                Text(
                    stringResource(
                        R.string.alcohol_entry_value,
                        "${grams.roundToInt()} ${stringResource(R.string.unit_grams)}",
                        (grams / STANDARD_DRINK_G).shown(),
                    ),
                    color = PwnMuted,
                )
            }
            Button(
                onClick = { ml?.let { onAdd(it, abvTenths, confirmation) } },
                // An empty box or a slip past a bottle and a half files nothing, and says so by greying.
                enabled = ml != null && !quickLog.locked(drink.name),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_add))
            }
        }
    }
}

/** Half a percent: finer than any label prints a strength, and a beer still lands on its 4.5 or 5.5. */
private const val ABV_STEP_TENTHS = 5

/**
 * Tobacco: one tap per cigarette or heated stick, and for the two things that are sold in sizes and
 * strengths, one tap to open the switch and one to file what it settled on.
 *
 * The sibling of [AlcoholScreen] and built the same way, with one difference. Three hookah bowls
 * and three snus tins as six more tiles would be a grid of eight near-identical pictures told apart
 * by the last word of a label; each is one tile instead, and the choice behind it — with the
 * sentence that explains the choice — opens under the grid, where it is read once and closed.
 */
@Composable
fun SmokingScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val journal by remember(today) {
        dao.journalBetween(today.minusDays(HABIT_WINDOW_DAYS - 1L).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    val week = remember(journal, today) { habitWeek(journal, today) }
    val day = remember(journal, today) { habitDay(journal, today) }
    val quickLog = rememberQuickLog()
    var chooser by remember { mutableStateOf<TobaccoGroup?>(null) }

    Column(modifier.fillMaxSize()) {
        val title = stringResource(R.string.smoking_title)
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.habits_subtitle)) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                HabitHeader(today = stringResource(R.string.smoking_today, day.tobaccoG.shown()))
                // The two that are one tap, and after them the two that are a tap and a question.
                val groups = TobaccoGroup.entries
                PwnTileGrid(SINGLE_TOBACCO.size + groups.size) { index ->
                    val product = SINGLE_TOBACCO.getOrNull(index)
                    if (product != null) {
                        val label = stringResource(product.titleRes)
                        val confirmation = quickLogAdded(label)
                        PwnTile(
                            art = product.art(),
                            title = label,
                            detail = stringResource(product.detailRes),
                            enabled = !quickLog.locked(product.name),
                        ) {
                            quickLog.log(scope, product.name, confirmation) {
                                // Guarded by portion, as a drink is: the same product twice inside the
                                // window is the tap that bounced, and the stick beside the cigarette is
                                // another entry — its tile was never shut, so its tap has to land.
                                db.logSmoking(smokingEntry(resources, product, LocalDate.now()))
                            }
                        }
                    } else {
                        val group = groups[index - SINGLE_TOBACCO.size]
                        PwnTile(
                            art = group.art(),
                            title = stringResource(group.titleRes),
                            detail = stringResource(group.detailRes),
                            // It opens and closes a chooser rather than picking an answer, so it says
                            // expanded or collapsed, not selected.
                            expanded = chooser == group,
                        ) {
                            // A group tile files nothing by itself. It opens the switch below the grid,
                            // and a second tap on the tile that is already lit closes it again. Which is
                            // also why it holds no cooldown key: there is no write of its own to shut it
                            // for, and the Add button inside the card carries the lock for whichever of
                            // the three the arrows settle on.
                            chooser = if (chooser == group) null else group
                        }
                    }
                }
                chooser?.let { group ->
                    TobaccoChooser(group, quickLog) { product, confirmation ->
                        quickLog.log(scope, product.name, confirmation) {
                            db.logSmoking(smokingEntry(resources, product, LocalDate.now()))
                        }
                        chooser = null
                    }
                }
                Text(
                    pluralStringResource(
                        R.plurals.smoking_week,
                        week.cigaretteEquivalents.roundToInt(),
                        // To the tenth, as the day's line above prints it: one heated stick's 0.3 g
                        // rounded to a whole gram would read as a week of none.
                        week.tobaccoG.shown(),
                        week.cigaretteEquivalents.roundToInt(),
                    ),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                DataNoteLink(R.string.habits_disclaimer)
                Spacer(Modifier.height(12.dp))
            }
            QuickLogFlash(quickLog)
        }
    }
}

/**
 * The one thing a group tile opens: a picture with an arrow either side of it, and an Add that
 * files whichever of the three the arrows have landed on.
 *
 * A card under the grid rather than a window over it, which is how every choice-inside-a-choice
 * works here — a dialog hides the tile that opened it and takes the screen's scroll hostage for a
 * decision that is made in about two seconds.
 *
 * The bowl grows as the arrows move because size is the entire difference between the three: small,
 * medium and large are not names, they are how much is packed in, and a switch that showed the same
 * picture three times would be three identical states with a word changing under them. A pouch is a
 * pouch whichever tin it came from, so it holds still; what changes there is the nicotine, and the
 * two lines under the picture are where that is read.
 *
 * [onAdd] is handed both the product the arrows settled on and the words to flash over it, because
 * the name of that product is a string only this card has resolved.
 */
@Composable
private fun TobaccoChooser(
    group: TobaccoGroup,
    quickLog: QuickLogState,
    onAdd: (TobaccoProduct, String) -> Unit,
) {
    // The middle one to start: the ordinary bowl and the ordinary tin, with either neighbour one
    // arrow away. Keyed to the group so that opening the other tile does not inherit this arrow.
    var index by remember(group) { mutableIntStateOf(group.members.size / 2) }
    val member = group.members[index]
    val art = group.art()
    val artSize by animateDpAsState(
        if (group.grows) BOWL_SIZES[index] else POUCH_SIZE,
        animationSpec = PwnMotion.spatial(),
        label = "tobaccoArt",
    )
    val label = stringResource(member.titleRes)
    val confirmation = quickLogAdded(label)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.smoking_pick), style = MaterialTheme.typography.titleMedium)
            // Mirrored arrows, so in Arabic the smaller one still points to where it lies, and named
            // for what they change: a bowl's size, but a tin's nicotine, since every pouch is one size.
            val smaller = if (group.grows) R.string.smoking_bowl_smaller else R.string.smoking_less_nicotine
            val larger = if (group.grows) R.string.smoking_bowl_larger else R.string.smoking_more_nicotine
            ArrowStep(
                art = art,
                artSize = artSize,
                smaller = stringResource(smaller),
                larger = stringResource(larger),
                onSmaller = { index -= 1 }.takeIf { index > 0 },
                onLarger = { index += 1 }.takeIf { index < group.members.lastIndex },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Focus stays on the arrow after a tap, so the member it landed on is announced
                // from here.
                Text(
                    label,
                    Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                // How this kind of tobacco is counted, beside the name rather than under the figure.
                HelpDot(label) { Text(stringResource(group.hintRes)) }
            }
            Text(stringResource(member.detailRes), color = PwnMuted)
            Button(
                onClick = { onAdd(member, confirmation) },
                // The same key the parent logs this member under, so the button that filed a large
                // bowl is the one that waits, and the arrows still reach the other two.
                enabled = !quickLog.locked(member.name),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_add))
            }
        }
    }
}

/**
 * A picture between two arrows, the step both chooser cards are made of; an arrow with nothing past
 * it is handed null and greys.
 */
@Composable
private fun ArrowStep(
    art: Int,
    artSize: Dp,
    smaller: String,
    larger: String,
    onSmaller: (() -> Unit)?,
    onLarger: (() -> Unit)?,
) {
    val colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSmaller?.invoke() }, enabled = onSmaller != null, colors = colors) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, smaller)
        }
        // The space is always as tall as the largest picture, whatever is drawn in it: a card that
        // grew and shrank with the picture would slide the Add button out from under a thumb that is
        // on its way down to it.
        Box(Modifier.weight(1f).height(BOWL_SIZES.last()), contentAlignment = Alignment.Center) {
            Image(themePainterResource(art), null, Modifier.size(artSize))
        }
        IconButton(onClick = { onLarger?.invoke() }, enabled = onLarger != null, colors = colors) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, larger)
        }
    }
}

/** Three sizes for the three bowls, far enough apart that the step is seen and not just measured. */
private val BOWL_SIZES = listOf(48.dp, 68.dp, 88.dp)

/** One size for every pouch, the middle of the three: nothing about a tin's strength is bigger. */
private val POUCH_SIZE = 68.dp

/**
 * The card's title, where it has one, and today's figure.
 *
 * It carries no question mark of its own: the only row that needs explaining is the hookah, and that
 * sentence sits in the switch the hookah tile opens — beside the choice it is about, rather than
 * above a grid as a mark that is pressed once and never again.
 */
@Composable
internal fun HabitHeader(today: String, title: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.weight(1f))
        // The header is called straight into the screen's scrolling Column, not inside a PwnCard, so
        // this figure sits on the canvas #F9FAFC and not on white: the fill accent measures 4.41:1
        // there, under the 4.5:1 small text needs at 16sp bold. The accent at text weight measures
        // 5.20:1 on the same canvas, and in dark theme resolves back to the lifted primary, 10.02:1.
        Text(today, fontWeight = FontWeight.Bold, color = PwnAccentInk)
    }
}

/** A beer mug, a wine glass, the bottle the spirits come from, and the glass a shot is drunk in. */
private fun AlcoholDrink.art(): Int = when (this) {
    AlcoholDrink.BEER -> R.drawable.ic_art_beer
    AlcoholDrink.WINE -> R.drawable.ic_art_wine
    AlcoholDrink.SPIRIT -> R.drawable.ic_art_spirits
    AlcoholDrink.SHOT -> R.drawable.ic_art_shot
}

/**
 * The cigarette, the stick it is heated in, the pipe, and the open tin with its pouches in it.
 *
 * Two drawings serve three products each, which is the point of the groups: the bowls differ by how
 * much is packed into them and the chooser says so by how large it draws the pipe, and one tin's
 * pouches look exactly like another's — what separates those is the nicotine printed under the
 * picture, never the picture.
 */
private fun TobaccoProduct.art(): Int = when (this) {
    TobaccoProduct.CIGARETTE -> R.drawable.ic_art_cigarette
    TobaccoProduct.HEATED -> R.drawable.ic_art_heated_stick
    TobaccoProduct.HOOKAH_SMALL, TobaccoProduct.HOOKAH_MEDIUM, TobaccoProduct.HOOKAH_LARGE -> R.drawable.ic_art_hookah
    TobaccoProduct.SNUS_LITE, TobaccoProduct.SNUS_MEDIUM, TobaccoProduct.SNUS_EXTRA -> R.drawable.ic_art_snus
}

/** A group wears its members' picture, which is one picture: that is what makes it a group. */
private fun TobaccoGroup.art(): Int = members.first().art()
