package com.pwnall.health

import android.content.res.Resources
import android.icu.text.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import kotlin.math.roundToInt

/** Today and the six preceding days. */
private const val MOOD_SCREEN_DAYS = 6L

/**
 * Mood logging, its seven-day reading, and the breathing practice that reading can suggest. One
 * screen because the three only make sense together: you log, you see the trend, you act on it.
 */
@Composable
fun MoodScreen(modifier: Modifier, onBreathing: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val moodSavedText = stringResource(R.string.mood_saved)
    val today = rememberToday()
    val entries by remember(today) {
        dao.journalBetween(today.minusDays(MOOD_SCREEN_DAYS).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())

    // A separate, longer window: seven days is a mood trend, and the weather is read over the four
    // months every measured link in the app is read over.
    val weatherFrom = today.minusDays(INSIGHT_WINDOW_DAYS - 1L).toString()
    val weatherMoods by remember(today) {
        dao.journalOfKindBetween(KIND_MOOD, weatherFrom, today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    val barometerDays by remember(today) {
        dao.barometerDaysBetween(weatherFrom, today.toString())
    }.collectAsStateWithLifecycle(emptyList())

    val summary = remember(entries) { moodSummary(entries.filter { it.kind == KIND_MOOD }) }
    // Off the composition thread, as the profile's patterns are: the engine walks four months of two
    // series and bootstraps two thousand resamples of them.
    val weather by produceState<InsightLink?>(null, weatherMoods, barometerDays, today) {
        value = withContext(Dispatchers.Default) { pressureMoodReading(barometerDays, weatherMoods, today) }
    }
    var status by rememberStatusLine()
    // A save that threw says so here, on the card the quick-log tiles use, until it is tapped away.
    var failure by remember { mutableStateOf<String?>(null) }
    val notSavedText = stringResource(R.string.quick_log_not_saved)
    var targetScore by rememberSaveable { mutableFloatStateOf(Mood.NEUTRAL.score.toFloat()) }
    val shownScore by animateFloatAsState(
        targetScore,
        spring(dampingRatio = 1f, stiffness = 700f),
        label = "mood transition",
    )
    val pending = Mood.fromScore(targetScore.roundToInt()) ?: Mood.NEUTRAL
    // Held by name rather than by ordinal, so a rotation does not hand back a different answer if
    // the scale is ever reordered.
    var energyName by rememberSaveable { mutableStateOf<String?>(null) }
    val energy = Energy.entries.firstOrNull { it.name == energyName }
    var saved by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The entry the data layer refused a save over, where the week above does not show it: one
    // stamped past midnight by a clock since put back is filed under tomorrow. Its moment puts the
    // countdown up, where the button would otherwise go on doing nothing.
    var refusedOver by remember { mutableStateOf<Long?>(null) }
    val lastMoodAt = (entries.filter { it.kind == KIND_MOOD }.map { it.occurredAt } + listOfNotNull(refusedOver))
        .maxOrNull()
    val cooldownLeft = moodCooldownLeftMs(lastMoodAt, now)

    // The countdown has to tick, otherwise the picker stays greyed out until the screen is
    // reopened and looks broken rather than deliberate. It ticks until the window after the newest
    // entry has run out, so one stamped ahead of the clock shuts the scale when the clock nears it —
    // on the minutes it prints, not every second ([moodCountdownWakeMs]).
    LaunchedEffect(lastMoodAt) {
        now = System.currentTimeMillis()
        while (lastMoodAt != null && now < lastMoodAt + MOOD_COOLDOWN_MS) {
            delay(moodCountdownWakeMs(moodCooldownLeftMs(lastMoodAt, now)))
            now = System.currentTimeMillis()
        }
    }

    Column(modifier.fillMaxSize()) {
        PwnTopBar(if (saved || cooldownLeft > 0L) stringResource(R.string.mood_title) else "", onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                if (!saved && cooldownLeft == 0L) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.mood_question), style = MaterialTheme.typography.titleLarge)
                        Text(
                            today.format(
                                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM),
                            ),
                            color = PwnMuted,
                        )
                        MoodDial(targetScore, shownScore, !saving) { targetScore = it }
                        EnergyQuestion(energy, !saving) { energyName = it?.name }
                        // Material 3 Expressive's pressed shape, as the app's wide buttons have it: the
                        // round Medium button squares its corners under the finger and springs back.
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val corner by animateDpAsState(
                            if (pressed) PwnCorners.medium else PwnDimensions.mediumButtonHeight / 2,
                            PwnMotion.fastSpatial(),
                            label = "button corner",
                        )
                        Button(
                            onClick = {
                                val mood = pending
                                val second = energy
                                saving = true
                                failure = null
                                scope.launch {
                                    try {
                                        // A write that throws — a full disk, a key the Keystore will not
                                        // give — is said on the failure card, as the quick-log tiles say
                                        // it, rather than end the process from a click handler.
                                        // Cancellation is the one failure that has to travel.
                                        runCatching {
                                            val entry = moodEntry(resources, mood, second, LocalDate.now())
                                            if (withContext(NonCancellable) { db.logMood(entry) }) {
                                                status = moodSavedText
                                                saved = true
                                            } else {
                                                refusedOver = dao.lastMoodNear(entry.occurredAt)
                                            }
                                        }.onFailure {
                                            if (it is CancellationException) throw it
                                            failure = notSavedText
                                        }
                                        now = System.currentTimeMillis()
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving,
                            // A floor rather than a height: a label that wraps at a large font size
                            // makes the button taller instead of being cut in half by it.
                            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = moodTint(shownScore),
                                contentColor = MoodButtonInk,
                            ),
                            shape = RoundedCornerShape(corner),
                            interactionSource = interaction,
                        ) { Text(stringResource(R.string.mood_save_action)) }
                    }
                } else {
                    if (cooldownLeft > 0L) {
                        val minutesLeft = moodPauseMinutes(cooldownLeft)
                        Text(
                            stringResource(R.string.mood_cooldown, minutesLeft / 60, minutesLeft % 60),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    PwnCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Where the bands come from is behind the dot beside the title; the week,
                            // the verdict and the figures stay on the card.
                            CardTitle(stringResource(R.string.mood_week_title)) {
                                Text(stringResource(R.string.mood_disclaimer))
                            }
                            MoodWeek(entries, today)
                            Text(
                                stringResource(
                                    if (summary.entries > 0 && summary.state == MoodState.NO_DATA) {
                                        R.string.mood_building_trend
                                    } else {
                                        summary.state.titleRes()
                                    },
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (summary.suggestBreathing) PwnWarn else PwnIndigo,
                            )
                            // Gated on the state, not the raw count: one or two entries still read NO_DATA,
                            // and an average printed under "No entries yet" contradicts the headline.
                            if (summary.state != MoodState.NO_DATA) {
                                Text(
                                    stringResource(
                                        R.string.mood_week_value,
                                        summary.average.shown(),
                                        // Rounded, as the doctor's sheet prints it: cut short, 28.9%
                                        // would read "28%" under a band that begins above 28.
                                        summary.wellbeingPercent.roundToInt(),
                                    ),
                                    color = PwnMuted,
                                )
                                Text(
                                    pluralStringResource(R.plurals.mood_entries, summary.entries, summary.entries),
                                    color = PwnMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    weather?.let { PressureMoodCard(it) }

                    // The practice has its own screen: it asks for two minutes of attention, and the week's
                    // mood chart sitting beside it competes for exactly that.
                    PwnCard(Modifier.fillMaxWidth(), onClick = onBreathing) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PwnGlyph(
                                R.drawable.ic_art_breathing,
                                Icons.Outlined.Air,
                                PwnPurpleSoft,
                                null,
                                Modifier.size(46.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.breathing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(summary.breathingHintRes()),
                                    color = PwnMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // Drawn rather than typed: a "›" character would join the card's spoken name.
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = PwnMuted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // The word the save left in [status] is shown here rather than under the button:
            // over the page, not inside its scroll, so it cannot be pushed below the fold by the
            // three cards under the picker or clipped by the card the button sits in.
            PwnActionFlash(status)
            PwnFailureFlash(failure) { failure = null }
        }
    }
}

/**
 * The second question, which nobody has to answer.
 *
 * Collapsed to a button until it is wanted, the way the note field is: a scale somebody has to
 * answer twice is a scale they stop answering, and the whole value of this one is that it is there
 * on the days it matters. It is a row of five rather than a second dial — a dial is the shape of
 * the main question, and this one should not look like a second main question.
 */
@Composable
private fun EnergyQuestion(selected: Energy?, enabled: Boolean, onSelect: (Energy?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open && selected == null) {
        androidx.compose.material3.TextButton(onClick = { open = true }, enabled = enabled) {
            Text(stringResource(R.string.energy_title))
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Why the question is asked, and that it may go unanswered, is behind the dot beside it
        // rather than a paragraph under the answers on every visit.
        val title = stringResource(R.string.energy_title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Measured after the dot, so a title that wraps at a large font size leaves it room.
            Text(
                title,
                Modifier.weight(1f, fill = false).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            HelpDot(title) { Text(stringResource(R.string.energy_optional)) }
        }
        AnswerRow(
            labels = Energy.entries.map { stringResource(it.titleRes) },
            selected = Energy.entries.indexOf(selected),
            enabled = enabled,
            // Tapping the chosen one again clears it, so an answer given by accident is not a
            // thing somebody has to leave the screen to undo.
            onSelect = { index ->
                val option = Energy.entries[index]
                onSelect(if (option == selected) null else option)
            },
        )
        selected?.let {
            Text(
                stringResource(it.descriptionRes),
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A row of answers to one question, one of them given (or none, at [selected] -1): each a target
 * the finger's size and a radio button to a screen reader.
 *
 * The given answer is drawn as a filled pill with its word in weight, as a segmented control marks
 * its segment. Told apart by colour alone, it would be a slightly darker word among five muted
 * ones, and on the second question nothing else on the screen would name the answer given. A cell the
 * finger can find stands at least the minimum target tall, and all five match the tallest, so a
 * word that wraps does not leave its neighbours shorter.
 *
 * Five answers share a phone's width, and a longer word — the Russian "Нейтральное" — can be wider
 * than its fifth of it. It is hyphenated where it breaks rather than split between two arbitrary
 * letters. Once the longest word is wider than [ANSWER_WORD_LINES] lines of its cell — at a large
 * font size — the answers stand one under another instead, each the page's width, so that no word
 * is chopped into syllables stacked down a narrow column.
 */
@Composable
private fun AnswerRow(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val style = MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Measured at the phone's own font size, in the weight a given answer is set in.
    val longestWord = remember(labels, style, density) {
        val given = style.copy(fontWeight = FontWeight.SemiBold)
        with(density) {
            labels.flatMap { it.split(' ') }.maxOf { measurer.measure(it, given).size.width }.toDp()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val line = maxWidth / labels.size - ANSWER_INSET * 2
        if (longestWord <= line * ANSWER_WORD_LINES) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    AnswerCell(label, index == selected, enabled, style, Modifier.weight(1f).fillMaxHeight()) {
                        onSelect(index)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    AnswerCell(label, index == selected, enabled, style, Modifier.fillMaxWidth()) { onSelect(index) }
                }
            }
        }
    }
}

/** One answer of an [AnswerRow], in a row of them or a column. */
@Composable
private fun AnswerCell(
    label: String,
    chosen: Boolean,
    enabled: Boolean,
    style: TextStyle,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val pill = RoundedCornerShape(percent = 50)
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        modifier
            .heightIn(min = PwnDimensions.minimumTarget)
            .background(if (chosen) scheme.primary else Color.Transparent, pill)
            .clip(pill)
            .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ANSWER_INSET, vertical = 6.dp)
            .wrapContentHeight(),
        textAlign = TextAlign.Center,
        style = style,
        color = if (chosen) scheme.onPrimary else PwnMuted,
        fontWeight = if (chosen) FontWeight.SemiBold else style.fontWeight,
    )
}

/** The room either side of an answer's word inside its pill. */
private val ANSWER_INSET = 4.dp

/** How many lines of its cell the longest word may fill, hyphenated once, before the answers stack. */
private const val ANSWER_WORD_LINES = 2

internal fun breathingEntry(resources: Resources, cycles: Int, seconds: Int, day: LocalDate) =
    JournalEntry(
        day = day.toString(),
        occurredAt = System.currentTimeMillis(),
        kind = KIND_BREATHING,
        title = resources.getString(R.string.breathing_title),
        calories = 0.0,
        durationMinutes = seconds / 60.0,
        breathCycles = cycles,
    )

/**
 * Four months of weather against four months of mood, as [pressureMoodReading] measures them.
 *
 * The reading is stated even when there is nothing to report, because "no link found" is the honest
 * and much more likely answer, and a card that appears only on a hit would turn coincidence into a
 * finding by never showing the misses.
 */
@Composable
private fun PressureMoodCard(link: InsightLink) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // What is compared, and that it is not a cause, is behind the dot, as the pattern cards
            // keep it.
            CardTitle(stringResource(R.string.pressure_mood_title)) {
                Text(stringResource(R.string.pressure_mood_disclaimer))
            }
            val line = pressureMoodLine(link)
            if (line == PressureMoodLine.NEED_DAYS) {
                val missingDays = INSIGHT_GATES.minPairedDays - link.pairedDays
                Text(
                    pluralStringResource(R.plurals.pressure_mood_need, missingDays, missingDays),
                    color = PwnMuted,
                )
            } else {
                val found = line != PressureMoodLine.NONE
                Text(
                    stringResource(
                        when (line) {
                            PressureMoodLine.HIGH_BETTER -> R.string.pressure_mood_high_better
                            PressureMoodLine.HIGH_WORSE -> R.string.pressure_mood_high_worse
                            else -> R.string.pressure_mood_none
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    // The text-weight blue, as the pattern cards' verdicts use: the fill blue is
                    // too pale for a 16sp line on the card in daylight.
                    color = if (found) PwnBlueInk else PwnMuted,
                )
                // The two levels in the figure the person wrote down, as the pattern cards print
                // them, in place of a coefficient: that one is read as a fact, and at this size it
                // is not one.
                link.contrast?.takeIf { found }?.let { contrast ->
                    Text(
                        stringResource(
                            R.string.pressure_mood_levels,
                            contrast.highShown.shown(),
                            contrast.lowShown.shown(),
                            pluralStringResource(R.plurals.count_days, link.pairedDays, link.pairedDays),
                        ),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** A card's title, a heading to a screen reader, with what the card rests on behind a [HelpDot]. */
@Composable
private fun CardTitle(title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        HelpDot(title, content)
    }
}

private fun moodTint(mood: Mood): Color = when (mood) {
    Mood.BAD -> Color(0xFF93A5BF)
    Mood.LOW -> Color(0xFF9AB8D8)
    Mood.NEUTRAL -> Color(0xFFB1B8C4)
    Mood.GOOD -> Color(0xFF99BCA5)
    Mood.FUN -> Color(0xFFFFD970)
}

/**
 * [moodTint] deepened at the same hue and saturation until it clears 3:1 on the daylight page: each
 * stop measures 3.30 to 3.33:1, and no blend between neighbours falls under 3.30:1. The pale tints
 * are the face's and the save button's fill; the slider's thumb is the one mark of where it stands,
 * and a tint there measures under 2.5:1, the neutral it opens on under 2:1.
 */
private fun moodInk(mood: Mood): Color = when (mood) {
    Mood.BAD -> Color(0xFF768DAE)
    Mood.LOW -> Color(0xFF608FC2)
    Mood.NEUTRAL -> Color(0xFF818CA0)
    Mood.GOOD -> Color(0xFF639775)
    Mood.FUN -> Color(0xFFB48400)
}

/** The face's own ink, on the save button's tinted fill: 6.39:1 at its lowest, on the deepest tint. */
private val MoodButtonInk = Color(0xFF122238)

private fun Mood.artRes(): Int = when (this) {
    Mood.BAD -> R.drawable.ic_mood_bad
    Mood.LOW -> R.drawable.ic_mood_nervous
    Mood.NEUTRAL -> R.drawable.ic_mood_neutral
    Mood.GOOD -> R.drawable.ic_mood_good
    Mood.FUN -> R.drawable.ic_mood_fun
}

private fun moodTint(score: Float): Color = moodBlend(score, ::moodTint)

private fun moodInk(score: Float): Color = moodBlend(score, ::moodInk)

private fun moodBlend(score: Float, stop: (Mood) -> Color): Color {
    val position = (score - 1f).coerceIn(0f, 4f)
    val index = position.toInt().coerceAtMost(3)
    return lerp(stop(Mood.entries[index]), stop(Mood.entries[index + 1]), position - index)
}

/**
 * The five faces and the dial under them: the page's, and the chat's ([MoodDialReply]) at a smaller
 * [faceHeight]. [given] false is a dial nobody has touched yet — the face rests where it starts,
 * no answer is lit and a screen reader hears "Not set yet" — since a face resting on Neutral is
 * where the control starts, not an answer. [compact] is the chat's: a smaller word, less room round
 * the description, and the face and the word silent to a screen reader, whose slider says the mood.
 * [interactionSource] lets a host see the slider dragged, to hold a clock while a finger is on it.
 */
@Composable
internal fun MoodDial(
    target: Float,
    shown: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    faceHeight: Dp = 270.dp,
    given: Boolean = true,
    compact: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onScore: (Float) -> Unit,
) {
    val mood = Mood.fromScore(target.roundToInt()) ?: Mood.NEUTRAL
    val moodName = stringResource(mood.titleRes)
    val unset = stringResource(R.string.pain_nrs_unset)
    // The value the last drag or tap left, read when it ends. [target] is the one the last
    // composition saw, and a tap sets the value and ends it in the same frame.
    var moved by remember { mutableFloatStateOf(target) }
    val ownSource = remember { MutableInteractionSource() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The pale tints after dusk, where each clears 7:1 on the dark page; their deepened inks in daylight.
    val mark = darkOr(moodTint(shown), moodInk(shown))
    val quiet = if (compact) Modifier.clearAndSetSemantics {} else Modifier
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        MorphingMoodFace(shown, Modifier.fillMaxWidth().height(faceHeight).then(quiet))
        androidx.compose.animation.Crossfade(mood, animationSpec = tween(180), label = "mood label") {
            Text(
                stringResource(it.titleRes),
                quiet,
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
        Slider(
            state = rememberSyncedSliderState(shown, 1f..5f),
            onValueChange = {
                moved = it
                onScore(it)
            },
            onValueChangeFinished = { onScore(moved.roundToInt().toFloat()) },
            enabled = enabled,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = mark,
                activeTrackColor = mark,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
            interactionSource = interactionSource ?: ownSource,
            // Out of the system's edge-swipe back, as the rulers are: the thumb at either end stands
            // in that strip, and a drag started there would close the page.
            modifier = Modifier.fillMaxWidth()
                .systemGestureExclusion()
                .onPreviewKeyEvent { event ->
                    // The slider's own keys move it by a sliver that the release then rounds straight
                    // back, so an arrow key could never leave the mood it was on. Here they step a
                    // whole mood instead.
                    val step = if (enabled) moodKeyStep(event.key, rtl) else 0
                    if (step != 0 && event.type == KeyEventType.KeyDown) {
                        onScore((target.roundToInt() + step).coerceIn(1, Mood.entries.size).toFloat())
                    }
                    step != 0
                }
                .semantics {
                    stateDescription = moodDialState(given, moodName, unset)
                    // Whole moods to a screen reader too: its swipes and volume keys would move the
                    // slider a fraction of a mood, which the rounding would undo. Set outside the slider,
                    // these stand over the slider's own.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        target.roundToInt().toFloat(),
                        1f..Mood.entries.size.toFloat(),
                        steps = Mood.entries.size - 2,
                    )
                    if (enabled) {
                        setProgress { value ->
                            onScore(value.roundToInt().coerceIn(1, Mood.entries.size).toFloat())
                            true
                        }
                    }
                },
        )
        AnswerRow(
            labels = Mood.entries.map { stringResource(it.titleRes) },
            selected = moodDialSelected(target, given),
            enabled = enabled,
            onSelect = { onScore(Mood.entries[it].score.toFloat()) },
        )
        androidx.compose.animation.Crossfade(mood, animationSpec = tween(180), label = "mood description") {
            Text(
                stringResource(it.descriptionRes),
                Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 28.dp),
                textAlign = TextAlign.Center,
                color = PwnMuted,
            )
        }
    }
}

/** The answer lit on a dial at [target]: the mood under it once [given], none before (-1). */
internal fun moodDialSelected(target: Float, given: Boolean): Int =
    if (given) Mood.entries.indexOf(Mood.fromScore(target.roundToInt()) ?: Mood.NEUTRAL) else -1

/** What a screen reader hears of the dial: the mood once [given], else that it is not set yet ([unset]). */
internal fun moodDialState(given: Boolean, moodName: String, unset: String): String = if (given) moodName else unset

/** How many moods a key moves the slider: up, and forward in the reading direction, is better. */
private fun moodKeyStep(key: Key, rtl: Boolean): Int = when (key) {
    Key.DirectionUp, Key.PageUp -> 1
    Key.DirectionDown, Key.PageDown -> -1
    Key.DirectionRight -> if (rtl) -1 else 1
    Key.DirectionLeft -> if (rtl) 1 else -1
    else -> 0
}

@Composable
private fun MoodWeek(entries: List<JournalEntry>, today: LocalDate) {
    // Read off the composition rather than Locale.getDefault(), which a recomposition does not see:
    // the strip's seven initials are the one thing here that a language change has to redraw.
    val locale = LocalResources.current.configuration.locales[0]
    val byDay = entries.filter { it.kind == KIND_MOOD && Mood.fromScore(it.moodScore) != null }.groupBy { it.day }
    val none = stringResource(R.string.common_none)
    val dayName = remember(locale) { DateFormat.getInstanceForSkeleton("EEEEMMMMd", locale) }
    val zone = rememberZone()
    Row(Modifier.fillMaxWidth()) {
        for (offset in MOOD_SCREEN_DAYS downTo 0L) {
            val day = today.minusDays(offset)
            val scores = byDay[day.toString()].orEmpty().mapNotNull { it.moodScore }
            val mood = if (scores.isEmpty()) null else Mood.fromScore(scores.average().roundToInt())
            val moodName = mood?.let { stringResource(it.titleRes) }
            // One stop a day, named in full: the narrow initials repeat within a week, and a day with
            // nothing logged would be read out as its dash.
            val date = dayName.format(Date.from(day.atStartOfDay(zone).toInstant()))
            val spoken = "$date, ${moodName ?: none}"
            Column(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (mood == null) {
                    Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) { Text("—", color = PwnMuted) }
                } else {
                    Image(
                        themePainterResource(mood.artRes()),
                        moodName,
                        Modifier.height(42.dp).fillMaxWidth(),
                    )
                }
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = PwnMuted,
                )
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, color = PwnMuted)
            }
        }
    }
}
