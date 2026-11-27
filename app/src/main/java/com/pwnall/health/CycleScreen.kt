package com.pwnall.health

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.roundToInt

/**
 * The cycle, as one page about today.
 *
 * One screen rather than tabs: a window to look at the cycle over, where she is in it, the picture,
 * the days around today, and the one or two things there are to say about today.
 *
 * A local page rather than an [AppScreen] of its own, for the reason [MedicineScreen] keeps its own:
 * a sub-page of a detail screen is not somewhere the bottom bar or a widget can land, and putting it
 * in the app's trail means a restored back stack can open it with nothing behind it.
 */
private enum class CyclePage { MAIN, FIRST_RUN, CALENDAR }

private val CYCLE_PAGE_SAVER: androidx.compose.runtime.saveable.Saver<CyclePage, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { it.name },
        restore = { runCatching { CyclePage.valueOf(it) }.getOrNull() ?: CyclePage.MAIN },
    )

/**
 * A day or none: the day the answers below are about — today, or a past day chosen on the week
 * strip — and the start of a period whose close is being asked about.
 */
private val DAY_SAVER: androidx.compose.runtime.saveable.Saver<LocalDate?, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { it?.toString() ?: "" },
        restore = { it.toLocalDateOrNull() },
    )

/** The period the question after a quick close is about, as its two days. */
private val PERIOD_SAVER: androidx.compose.runtime.saveable.Saver<CyclePeriod?, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { period -> period?.let { "${it.start}/${it.end}" } ?: "" },
        restore = { text ->
            val days = text.split('/').mapNotNull { it.toLocalDateOrNull() }
            days.takeIf { it.size == 2 }?.let { CyclePeriod(it[0], it[1]) }
        },
    )

@Composable
@Suppress("LongMethod")
fun CycleScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    val quickLog = rememberQuickLog()
    // Null until the journal has been read, which an empty list could not say: empty is a record
    // with nothing in it, and that is the page asking when the last period began. Only the kinds
    // this screen reads, up to and including today — see [CYCLE_SCREEN_KINDS].
    val loaded by remember(today) {
        db.dao().journalOfKindsBefore(CYCLE_SCREEN_KINDS, today.plusDays(1).toString())
    }.collectAsStateWithLifecycle<List<JournalEntry>?>(null)
    val entries = loaded.orEmpty()
    val profile by remember { db.dao().profile() }.collectAsStateWithLifecycle(null)

    val summary = remember(entries, today) { cycleSummary(entries, today) }
    val facts = remember(entries, summary, today) { CycleFacts(entries, summary, today) }
    val phase = remember(facts) { facts.phase() }
    val action = remember(entries, today) { cycleAction(entries, today) }
    val ageYears = profile?.birthDate?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ageOn(today.toString(), it) }.getOrNull() }

    // The acts filed from this page are the intimate screen's own rows, so they stand behind that
    // screen's gate ([AgeGate]): where it is shut this page has no buttons for them, lists none and
    // its calendar draws none. The question about desire stands behind it with them — it is asked
    // of her sex life, not of her cycle — and so does its line on the plot. Shut while the profile
    // is still being read, as the gate itself is.
    val acts = sexLogOffered(profile?.birthDate, today)

    var page by rememberSaveable(stateSaver = CYCLE_PAGE_SAVER) { mutableStateOf(CyclePage.MAIN) }
    var symptomDay by rememberSaveable(stateSaver = DAY_SAVER) { mutableStateOf<LocalDate?>(null) }
    var window by rememberSaveable { mutableIntStateOf(0) }
    // The dialogs outlive a turn of the phone, which recreates the activity: "That was quick — or
    // a slip?" is asked once, and one lost to a rotation would never have been asked at all.
    var asking by rememberSaveable { mutableStateOf(false) }
    var closing by rememberSaveable(stateSaver = DAY_SAVER) { mutableStateOf<LocalDate?>(null) }
    var checking by rememberSaveable(stateSaver = PERIOD_SAVER) { mutableStateOf<CyclePeriod?>(null) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    val deleted = stringResource(R.string.intimate_flash_deleted)

    BackHandler(enabled = page != CyclePage.MAIN) { page = CyclePage.MAIN }
    // The calendar's backdrop: this page as it stood when the calendar was opened over it.
    val behind = rememberBackdropShot()
    // Above the pages that stand in for this one, so that back from the calendar the page is where
    // it was left rather than at its top.
    val scroll = rememberScrollState()

    // Nothing but the ground until the journal has been read. Starting from an empty list, every
    // opening would first draw the page for a record with no period in it — with a Save that
    // would file a start a fortnight back over the record still being read.
    if (loaded == null) {
        Box(modifier.fillMaxSize())
    } else if (page == CyclePage.CALENDAR) {
        // The two pages behind this one are held open over it as panels — see [PwnPageHost].
        PwnSheetPage(onClose = { page = CyclePage.MAIN }, modifier = modifier, backdrop = behind.backdrop) {
            CycleCalendarPage(
                modifier = Modifier,
                facts = facts,
                today = today,
                acts = acts,
                onBack = { page = CyclePage.MAIN },
                onAddPast = { page = CyclePage.FIRST_RUN },
                onPick = { day ->
                    symptomDay = day.takeIf { it != today }
                    page = CyclePage.MAIN
                },
            )
        }
    }
    if (loaded == null || page == CyclePage.CALENDAR) return

    // Nothing has ever been logged: there is no day of the cycle, no length and no prediction, so
    // there is nothing for the page above to be about. One question instead of five blanks. The
    // same page, asked for from the calendar, files a period that was missed at the time.
    if (summary.lastStart == null || page == CyclePage.FIRST_RUN) {
        // A panel where it was asked for from the page behind it; the page itself where there is
        // no page behind it yet, because nothing has ever been logged.
        PwnPageHost(
            sheet = summary.lastStart != null,
            onClose = { page = CyclePage.MAIN },
            modifier = modifier,
        ) { pageBase ->
            FirstCyclePage(
                modifier = pageBase,
                saving = quickLog.locked(FIRST_CYCLE_KEY),
                past = summary.lastStart != null,
                fits = { start, end -> pastPeriodFits(entries, start, end, today) },
                onBack = { if (summary.lastStart == null) onBack() else page = CyclePage.MAIN },
                onConfirm = { start, end ->
                    page = CyclePage.MAIN
                    quickLog.log(scope, FIRST_CYCLE_KEY, resources.getString(R.string.quick_log_added)) {
                        db.logRememberedPeriod(resources, start, end)
                    }
                },
            )
        }
        return
    }

    Column(modifier.fillMaxSize().background(CycleSheet)) {
        CycleTopBar(action, !quickLog.locked(PERIOD_KEY), onBack) { asking = true }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().panelVerticalScroll(scroll)) {
                // The hero: everything about today, drawn straight onto the violet, with no cards
                // between the figures and the ground.
                Column(
                    Modifier.fillMaxWidth().background(CycleViolet).padding(horizontal = 20.dp),
                ) {
                    CycleWindowStrip(window) { window = it }
                    Spacer(Modifier.height(22.dp))
                    CycleHeadline(
                        facts = facts,
                        phase = phase,
                        summary = summary,
                        today = today,
                        ageYears = ageYears,
                        onExplain = { explaining = true },
                    )
                    Spacer(Modifier.height(18.dp))
                    // The plot re-forms rather than cutting: a span is a change of scale, and a
                    // chart that swaps one set of lines for another in a single frame reads as a
                    // different chart each time. It runs once per tap and then stands still.
                    AnimatedContent(
                        targetState = window,
                        transitionSpec = {
                            (fadeIn(PwnMotion.effects()) + scaleIn(PwnMotion.spatial(), CHART_FROM))
                                .togetherWith(
                                    fadeOut(PwnMotion.effects()) +
                                        scaleOut(PwnMotion.spatial(), CHART_TO),
                                )
                        },
                        label = "cycle window",
                    ) { chosen ->
                        val chart = remember(facts, phase, chosen, acts) {
                            facts.chart(phase, CycleWindow.entries[chosen], acts)
                        }
                        CycleChart(chart)
                    }
                    Spacer(Modifier.height(22.dp))
                }
                // The sheet's two corners turn over the violet rather than over what is under the
                // sheet, so the strip they turn in has to be drawn as violet with the sheet laid on it.
                Box(Modifier.fillMaxWidth().height(CYCLE_SHEET_RADIUS).background(CycleViolet)) {
                    Box(
                        Modifier.fillMaxSize().background(
                            CycleSheet,
                            RoundedCornerShape(
                                topStart = CYCLE_SHEET_RADIUS,
                                topEnd = CYCLE_SHEET_RADIUS,
                            ),
                        ),
                    )
                }
                Column(
                    Modifier.fillMaxWidth().background(CycleSheet),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    // The card stands nearer the edges than the rest of the sheet: its week is
                    // seven targets side by side, and every dp of inset is taken from them.
                    CyclePhaseCard(
                        facts = facts,
                        phase = phase,
                        summary = summary,
                        today = today,
                        chosen = symptomDay ?: today,
                        onOpenCalendar = { behind.open { page = CyclePage.CALENDAR } },
                        modifier = Modifier.padding(horizontal = PHASE_CARD_INSET),
                    ) { day ->
                        symptomDay = day.takeIf { it != today }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        CycleTodayBlock(
                            facts = facts,
                            today = today,
                            day = symptomDay ?: today,
                            acts = acts,
                            onPick = { section, log, day ->
                                quickLog.log(scope, log.name, resources.getString(log.titleRes)) {
                                    db.logCycleLog(cycleLogEntry(resources, log, day.toString()), section)
                                }
                            },
                            onClear = { section, day ->
                                quickLog.log(scope, "clear${section.name}", deleted) {
                                    db.clearCycleSection(section, day.toString())
                                }
                            },
                        )
                        // Spotting is bleeding outside a period, so a period day does not offer it:
                        // its flow is the question above. A button of its own rather than a rung of
                        // that ladder — [CycleScale] says why it is a kind of bleeding, not an amount.
                        val spottingDay = symptomDay ?: today
                        if (facts.periodOn(spottingDay) == null) {
                            HorizontalDivider(color = CycleCell)
                            CycleSpottingRow(
                                logged = remember(facts, spottingDay) { facts.spottingOn(spottingDay) },
                                shut = quickLog.locked(CycleLog.SPOTTING.name),
                                onLog = { confirmation ->
                                    quickLog.log(scope, CycleLog.SPOTTING.name, confirmation) {
                                        db.logCycleLog(
                                            cycleLogEntry(resources, CycleLog.SPOTTING, spottingDay.toString()),
                                            CycleSection.FLOW,
                                        )
                                    }
                                },
                                onRemove = { id ->
                                    quickLog.log(scope, "remove$id", deleted) {
                                        db.deleteJournalEvent(id)
                                        writeLocalHealthIfAvailable(context, db)
                                    }
                                },
                            )
                        }
                        // Not drawn at all under the age gate, rule and heading included: a heading
                        // over nothing is the closed door [AgeGate] says a child must not be shown.
                        if (acts) {
                            HorizontalDivider(color = CycleCell)
                            CycleIntimateRow(
                                facts = facts,
                                day = symptomDay ?: today,
                                quickLog = quickLog,
                                onLog = { log, confirmation ->
                                    val on = symptomDay ?: today
                                    // Twice in a day is twice, not a correction — so this does not
                                    // replace the way an answer does. What it does refuse is the same
                                    // act twice in three seconds, which is one tap that bounced.
                                    quickLog.log(scope, log?.name ?: MASTURBATION_KEY, confirmation) {
                                        db.logIntimacy(
                                            if (log == null) {
                                                masturbationEntry(resources, on, emptyList())
                                            } else {
                                                cycleLogEntry(resources, log, on.toString())
                                            },
                                            log,
                                            LocalDate.now(),
                                        )
                                    }
                                },
                                onRemove = { id ->
                                    // The next exchange withdraws the act from Health Connect, and the
                                    // tombstone keeps it from adopting the act back: the mirror after it
                                    // publishes the edits still owed and withdraws nothing
                                    // ([writeLocalHealthIfAvailable]).
                                    quickLog.log(scope, "remove$id", deleted) {
                                        db.deleteJournalEvent(id)
                                        writeLocalHealthIfAvailable(context, db)
                                    }
                                },
                            )
                        }
                        // What reaches Health Connect and what does not, the acts named only where
                        // the page offers them.
                        if (acts) {
                            DataNoteLink(R.string.cycle_health_connect_note, R.string.cycle_health_connect_note_acts)
                        } else {
                            DataNoteLink(R.string.cycle_health_connect_note)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            QuickLogFlash(quickLog, Modifier.matchParentSize())
        }
    }

    if (explaining) {
        NormVerdictDialog(facts, phase, summary, today, ageYears) { explaining = false }
    }

    if (asking) {
        PeriodSwitchDialog(
            action = action,
            today = today,
            onDismiss = { asking = false },
            onConfirm = {
                asking = false
                when (val chosen = action) {
                    is CycleAction.Finish -> {
                        val period = CyclePeriod(chosen.start, today)
                        val days = ChronoUnit.DAYS.between(chosen.start, today).toInt() + 1
                        if (days > PERIOD_OPEN_DAYS) {
                            closing = chosen.start
                        } else {
                            quickLog.log(scope, PERIOD_KEY, resources.getString(R.string.cycle_period_end)) {
                                db.logCycleEnd(cycleEndEntry(resources, today.toString()))
                            }
                            if (days <= PERIOD_SHORT_DAYS) checking = period
                        }
                    }

                    is CycleAction.Reopen ->
                        quickLog.log(scope, PERIOD_KEY, resources.getString(R.string.cycle_reopen_title)) {
                            db.reopenCyclePeriod(chosen.period.end.toString())
                        }

                    CycleAction.Start -> {
                        val word = resources.getString(
                            R.string.quick_log_added_named,
                            resources.getString(R.string.cycle_legend_period),
                        )
                        quickLog.log(scope, PERIOD_KEY, word) {
                            db.startOrResumeCycle(cycleEntry(resources, today.toString()), today)
                        }
                    }
                }
            },
        )
    }
    checking?.let { period ->
        QuickPeriodDialog(period) { real ->
            checking = null
            // Through the quick log, as every write here is: a deletion that throws says so on the
            // failure card rather than taking the app down. Its own key, since the record button's
            // is still shut from the close this answers.
            if (!real) {
                quickLog.log(scope, "removePeriod", deleted) {
                    db.deleteCyclePeriod(period.start.toString(), period.end.toString())
                }
            }
        }
    }
    closing?.let { start ->
        ClosePeriodDialog(
            start = start,
            onDismiss = { closing = null },
            onConfirm = { from, to ->
                closing = null
                quickLog.log(scope, PERIOD_KEY, resources.getString(R.string.cycle_period_end)) {
                    db.logCyclePeriod(
                        start = cycleEntry(resources, from.toString()),
                        end = cycleEndEntry(resources, to.toString()),
                        previousStartDay = start.toString(),
                    )
                }
            },
        )
    }
}

/**
 * The bar over the violet: a thin back chevron, the title, and the record button — white on violet
 * rather than the app's own dark-on-white bar.
 */
@Composable
private fun CycleTopBar(
    action: CycleAction,
    enabled: Boolean,
    onBack: () -> Unit,
    onRecord: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(CycleViolet)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.common_back),
                tint = CycleOnViolet,
            )
        }
        Text(
            stringResource(R.string.cycle_title),
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CycleOnViolet,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        PeriodRecordButton(action, enabled, onRecord)
    }
}

/**
 * The record button, drawn rather than shipped: a filled disc while a period can begin, a rounded
 * square while one is running — the pair a phone's own camera uses, and the one control nobody has
 * to be taught. White on the violet, with its own faint disc behind it so it reads as a button.
 */
@Composable
private fun PeriodRecordButton(action: CycleAction, enabled: Boolean, onClick: () -> Unit) {
    val running = action is CycleAction.Finish
    val description = stringResource(
        when (action) {
            is CycleAction.Finish -> R.string.cycle_finish
            is CycleAction.Reopen -> R.string.cycle_reopen_title
            CycleAction.Start -> R.string.cycle_add
        },
    )
    // A white disc with the mark inside it in the bleeding colour: on a phone's own camera the
    // red dot is the one that starts and the red square the one that stops, and the pair needs no
    // label. On the violet a white-on-white mark would have neither.
    //
    // Both are drawn, so the button's own dimming does not reach them and they fade with it here:
    // otherwise, while a tap is still being written, the inert button would look as ready as ever.
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val disc = CycleOnViolet.copy(alpha = alpha)
    val mark = PwnBleed.copy(alpha = alpha)
    // The dot turns into the square rather than being swapped for it, once per change of state:
    // the one control on the screen that starts and stops something shows the change happening.
    val morph by animateFloatAsState(if (running) 1f else 0f, PwnMotion.spatial(), label = "record mark")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) PRESSED_SCALE else 1f,
        PwnMotion.fastSpatial(),
        label = "record press",
    )
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction) {
        Canvas(
            Modifier.size(30.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .semantics { contentDescription = description },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(disc, radius)
            // From the dot, a circle as wide as 1.24 radii, to the square, 0.74 radii a side with
            // corners of 0.3 of that side — one rounded rectangle whose side and corners travel.
            val side = radius * (DOT_SIDE + (SQUARE_SIDE - DOT_SIDE) * morph)
            val corner = (side / 2f + (side * SQUARE_CORNER - side / 2f) * morph).coerceIn(0f, side / 2f)
            drawRoundRect(
                color = mark,
                topLeft = Offset(radius - side / 2f, radius - side / 2f),
                size = androidx.compose.ui.geometry.Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
private const val PRESSED_SCALE = 0.9f
private const val DOT_SIDE = 1.24f
private const val SQUARE_SIDE = 0.74f
private const val SQUARE_CORNER = 0.3f

/**
 * The three spans: one translucent track the width of the screen, the chosen one a white pill with
 * the violet printed on it.
 *
 * Its own control rather than [PwnSegmentedControl] because that one is the app's — an outlined box
 * on white — and on the violet it would disappear. The behaviour is the same: one choice, said to a
 * screen reader as a group of tabs.
 */
@Composable
private fun CycleWindowStrip(selected: Int, onSelected: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(CycleVioletTrack, CircleShape)
            .padding(STRIP_INSET)
            .selectableGroup(),
    ) {
        CycleWindow.entries.forEachIndexed { index, window ->
            val active = index == selected
            // The pill slides its colour in rather than jumping, once per tap.
            val fill by animateColorAsState(
                if (active) CycleOnViolet else CycleOnViolet.copy(alpha = 0f),
                PwnMotion.effects(),
                label = "window pill",
            )
            val ink by animateColorAsState(
                if (active) CycleViolet else CycleOnViolet,
                PwnMotion.effects(),
                label = "window ink",
            )
            // The glyph on the pill is a letter or two; what a screen reader says is the span.
            val spoken = window.spokenName()
            Box(
                Modifier.weight(1f)
                    // The target is the segment itself: the strip's inset lies outside it and does
                    // not count towards the 48dp a finger is owed.
                    .heightIn(min = PwnDimensions.minimumTarget)
                    .background(fill, CircleShape)
                    .selectable(selected = active, role = Role.Tab) { onSelected(index) }
                    .semantics { contentDescription = spoken }
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(window.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A span as it is said aloud: "1 week", "4 weeks", "3 months" — never the one-letter glyph. */
@Composable
private fun CycleWindow.spokenName(): String = when (this) {
    CycleWindow.WEEK -> pluralStringResource(R.plurals.m3_cal_count_weeks, 1, 1)
    CycleWindow.MONTH -> pluralStringResource(R.plurals.m3_cal_count_weeks, MONTH_WEEKS, MONTH_WEEKS)
    CycleWindow.QUARTER -> stringResource(R.string.report_months_3)
}

/** The "month" span is twenty-eight days, which is four weeks and not a calendar month. */
private const val MONTH_WEEKS = 4

private val STRIP_INSET = 5.dp

/**
 * Where she is, in one line: the day of the cycle over it, the sentence with the state as a white
 * pill, and what the record has to say under it.
 *
 * The sentence shrinks to fit rather than wrapping. "Ваша менструация сейчас" is half again as long
 * as the English "Your fertility is", and a pill that drops to a second line stops reading as the
 * end of the sentence it belongs to.
 *
 * The estimate's caveat is not a paragraph. The "?" beside the pill opens everything behind the
 * word — what it was compared with, and that a calendar estimate is not an observation. One line of
 * it stays under the word wherever the word is the calendar's own ([Fertility.estimated]): with the
 * whole caveat behind the "?", «Your fertility right now is: Low» would stand on the page as a fact,
 * which is the one reading here somebody could act on. Anything a published figure has to say stays
 * on the page whether or not she taps it.
 */
@Composable
private fun CycleHeadline(
    facts: CycleFacts,
    phase: CyclePhase,
    summary: CycleSummary,
    today: LocalDate,
    ageYears: Int?,
    onExplain: () -> Unit,
) {
    val dayMonth = rememberDayMonth()
    val fertility = remember(facts) { facts.fertility() }
    val flags = remember(summary, phase, ageYears) { cycleFlags(summary, today, phase, ageYears) }
    val state = phase.stateWord(facts, today, fertility)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary.dayOfCycle?.let {
            Text(
                stringResource(R.string.cycle_day, it),
                style = MaterialTheme.typography.bodyLarge,
                color = CycleOnVioletMuted,
            )
        }
        if (state != null) {
            val headline = stringResource(
                if (phase is CyclePhase.Menstruation) {
                    R.string.cycle_headline_period
                } else {
                    R.string.cycle_headline_fertility
                },
            )
            // The sentence and the word for today sit on one line, the headline shrinking to fit
            // beside it. Where that cannot be done — a phone set to 150% text, where the chip alone
            // takes half the row — the chip drops to its own line and the sentence takes the width.
            // Held in the row whatever happens, it would be cut to "Ваша", and merely allowed to wrap
            // it would break "менструация" across two lines; neither is a sentence about her body.
            var wrapped by remember(headline) { mutableStateOf(false) }
            if (wrapped) {
                CycleHeadlineText(headline, HEADLINE_WRAP_LINES, Modifier.fillMaxWidth()) {}
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CycleStateChip(stringResource(state))
                    CycleHelpDot(onExplain)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CycleHeadlineText(headline, 1, Modifier.weight(1f)) { wrapped = true }
                    Spacer(Modifier.width(10.dp))
                    CycleStateChip(stringResource(state))
                    CycleHelpDot(onExplain)
                }
            }
        }
        if (phase !is CyclePhase.Menstruation && fertility?.estimated == true) {
            Text(
                stringResource(R.string.cycle_fertile_caveat),
                style = MaterialTheme.typography.bodyMedium,
                color = CycleOnVioletMuted,
            )
        }
        // Said on every day, including the days she is bleeding. "When is the next one" is the
        // question this screen is opened with, and a period is no reason to leave it unanswered.
        summary.nextExpected?.let { expected ->
            val away = ChronoUnit.DAYS.between(today, expected).toInt()
            if (away >= 0) {
                Text(
                    stringResource(
                        R.string.cycle_headline_next,
                        pluralStringResource(R.plurals.count_days, away, away),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = CycleOnVioletMuted,
                )
            }
        }
        flags.forEach { flag ->
            Text(
                flagLine(flag, dayMonth),
                style = MaterialTheme.typography.bodyLarge,
                color = CycleOnViolet,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * How far the plot re-forms when the span changes. The scale rides the app's spatial spring and the
 * fade its effects spring, so this moves like every other change of place in the app.
 */
private const val CHART_FROM = 0.94f
private const val CHART_TO = 1.05f

/**
 * The sentence over the plot, at the largest size that fits the room it is given.
 *
 * [onOverflow] fires when even the smallest step could not hold it, which is the caller's signal to
 * give it the whole width instead of a share of the row.
 */
@Composable
private fun CycleHeadlineText(
    text: String,
    maxLines: Int,
    modifier: Modifier,
    onOverflow: () -> Unit,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            color = CycleOnViolet,
            fontWeight = FontWeight.Bold,
        ),
        onTextLayout = { if (it.hasVisualOverflow) onOverflow() },
        maxLines = maxLines,
        autoSize = TextAutoSize.StepBased(
            minFontSize = HEADLINE_MIN,
            maxFontSize = HEADLINE_MAX,
            stepSize = 0.5.sp,
        ),
    )
}

/** How far the headline may wrap once the row has been proved not to hold it. */
private const val HEADLINE_WRAP_LINES = 3

/** How far it may shrink first, before the chip is moved off its line. */
private val HEADLINE_MIN = 15.sp
private val HEADLINE_MAX = 28.sp

/** The word in the chip: what she is bleeding, or how fertile the estimate makes today. */
@Composable
private fun CyclePhase.stateWord(facts: CycleFacts, today: LocalDate, fertility: Fertility?): Int? =
    if (this is CyclePhase.Menstruation) {
        when (facts.rungOn(today, CycleScale.FLOW)) {
            1 -> R.string.cycle_state_light
            2 -> R.string.cycle_state_medium
            3 -> R.string.cycle_state_heavy
            else -> null
        }
    } else {
        when (fertility?.level) {
            FertilityLevel.LOW -> R.string.cycle_fertility_low
            FertilityLevel.RISING -> R.string.cycle_fertility_rising
            FertilityLevel.HIGH -> R.string.cycle_fertility_high
            FertilityLevel.PEAK -> R.string.cycle_fertility_peak
            null -> null
        }
    }

/** The state itself: a white pill with the violet printed on it. */
@Composable
private fun CycleStateChip(text: String) {
    Surface(shape = RoundedCornerShape(CHIP_RADIUS), color = CycleOnViolet) {
        Text(
            text,
            Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = CycleViolet,
            maxLines = 1,
        )
    }
}

private val CHIP_RADIUS = PwnCorners.medium

/**
 * And the circle beside it, which opens the explanation behind the word.
 *
 * The drawn ring stays small; the target around it is the full 48dp a finger is owed, and its slack
 * either side of the ring is also the gap between the ring and the chip, so the row keeps its look.
 */
@Composable
private fun CycleHelpDot(onClick: () -> Unit) {
    val label = stringResource(R.string.cycle_verdict_open)
    Box(
        Modifier.minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(HELP_DOT).border(1.5.dp, CycleOnViolet, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CycleOnViolet,
            )
        }
    }
}

private val HELP_DOT = 30.dp

/**
 * Where the phase runs to, the seven days around today, and the one hint worth printing, on a
 * lavender card.
 *
 * The strip is a way into a past day as well as a picture of one: every day that has happened opens
 * the symptoms page for itself.
 *
 * The heading and the hint keep the card's inset; the week runs edge to edge inside it, because
 * seven days share whatever width the card has and each of them is a finger's target.
 */
@Composable
private fun CyclePhaseCard(
    facts: CycleFacts,
    phase: CyclePhase,
    summary: CycleSummary,
    today: LocalDate,
    chosen: LocalDate,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDay: (LocalDate) -> Unit,
) {
    val dayMonth = rememberDayMonth()
    val locale = LocalLocale.current.platformLocale
    val dayMonthYear = remember(locale) { localizedDatePattern(locale, "yMMMd", "d MMM yyyy") }
    val range = when (phase) {
        is CyclePhase.Menstruation -> phase.end?.let { phase.start..it }
        is CyclePhase.Fertile -> phase.window
        else -> null
    }
    // A phase the record cannot place, on this page, is a record gone quiet and not an empty one —
    // the empty one is asked its first question instead of being shown this card. "No cycle
    // recorded" over a year of her cycles would be false; the day the record stops is what there is to
    // say, with its year, since it is at least two cycles back and may be in a year gone by.
    val title = summary.lastStart?.takeIf { phase == CyclePhase.Unknown }
        ?.let { stringResource(R.string.cycle_nudge_late, it.format(dayMonthYear)) }
        ?: stringResource(phase.titleRes())

    Column(
        modifier.fillMaxWidth()
            .background(CycleCard, RoundedCornerShape(CARD_RADIUS))
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A flowing row, so on a phone with large text the calendar and the dates drop to a line
        // of their own rather than squeezing the name of the phase into two letters. At ordinary
        // sizes the name still sits at the left and the rest at the right, as one row.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = CARD_INSET),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = PwnMuted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenCalendar) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = stringResource(R.string.cycle_calendar_title),
                        tint = CycleAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                range?.let {
                    Spacer(Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(CHIP_RADIUS), color = CycleCell) {
                        Text(
                            stringResource(
                                R.string.cycle_first_range,
                                it.start.format(dayMonth),
                                it.endInclusive.format(dayMonth),
                            ),
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = CycleCellInk,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        CycleWeekRow(facts, today, chosen, onOpenDay)
        // The dot belongs to the sentence, not to the card: on a record with nothing to say it
        // would otherwise sit under the week on its own, a bullet in front of nothing.
        cyclePhaseHint(facts, phase, summary, today)?.let { hint ->
            Row(Modifier.padding(horizontal = CARD_INSET), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(CycleAccent, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
            }
        }
    }
}

private val CARD_RADIUS = PwnCorners.extraLarge
private val CARD_INSET = 18.dp
private const val DAYS_IN_WEEK = 7

/** How far the phase card stands from the sheet's edges, nearer than the rest of the sheet. */
private val PHASE_CARD_INSET = 12.dp

/** What the card above the strip is called, which is the phase and nothing cleverer. */
private fun CyclePhase.titleRes(): Int = when (this) {
    is CyclePhase.Menstruation -> R.string.cycle_legend_period
    is CyclePhase.Fertile -> R.string.cycle_legend_fertile
    is CyclePhase.Ordinary -> R.string.cycle_phase_ordinary
    CyclePhase.Unknown -> R.string.cycle_phase_unknown
}

/**
 * The seven days of this week, each one whole column — the weekday over it included — as one
 * target, and each said aloud as its full date with what the record holds for it.
 *
 * The weekday is the locale's short name while all seven fit a column, and the narrow one when they
 * do not, since at a large text size a short name can outgrow its column and be cut to its first
 * letters. The full name is in what a screen reader says either way.
 */
@Composable
private fun CycleWeekRow(facts: CycleFacts, today: LocalDate, chosen: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val start = remember(today, firstDay) {
        var day = today
        while (day.dayOfWeek != firstDay) day = day.minusDays(1)
        day
    }
    val days = remember(start) { (0 until DAYS_IN_WEEK).map { start.plusDays(it.toLong()) } }
    val bleeding = remember(facts) { recordedBleedingDays(facts.entries) }
    // Spotting between periods is one of those days and is not a day of a period: it is said by
    // its own name and wears the calendar's open dot in place of the drop.
    val spotting = remember(facts, days) { days.filter { facts.isSpottingDay(it) }.toSet() }
    val fullDate = remember(locale) { localizedDatePattern(locale, "MMMMEEEEd", "EEEE d MMMM") }
    val todayWord = stringResource(R.string.home_today)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val shortNames = remember(days, locale) { days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) } }
    val widestShort = remember(shortNames, style, density) {
        with(density) { shortNames.maxOf { measurer.measure(it, style).size.width }.toDp() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val column = maxWidth / DAYS_IN_WEEK - WEEK_CELL_INSET * 2
        val names = if (widestShort <= column) {
            shortNames
        } else {
            days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            days.forEachIndexed { index, day ->
                val bled = day in bleeding
                val spoken = listOfNotNull(
                    day.format(fullDate),
                    todayWord.takeIf { day == today },
                    bleedingWord(bled, day in spotting)?.let { stringResource(it) },
                ).joinToString(stringResource(R.string.list_comma))
                CycleWeekDay(
                    day = day,
                    weekday = names[index],
                    spoken = spoken,
                    chosen = day == chosen,
                    bleeding = bled,
                    spotting = day in spotting,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenDay(day) }.takeIf { !day.isAfter(today) },
                )
            }
        }
    }
}

@Composable
private fun CycleWeekDay(
    day: LocalDate,
    weekday: String,
    spoken: String,
    chosen: Boolean,
    bleeding: Boolean,
    spotting: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    Column(
        modifier
            .heightIn(min = PwnDimensions.minimumTarget)
            .clip(RoundedCornerShape(CELL_RADIUS))
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                selected = chosen
            }
            .padding(horizontal = WEEK_CELL_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(weekday, style = MaterialTheme.typography.labelSmall, color = PwnMuted, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        // The chosen day's cell stands taller than the rest: the one day being pointed at is a
        // different size and not only a different colour. Both heights are floors, so a large text
        // size grows the cell instead of cutting the figure.
        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = if (chosen) CELL_TODAY else CELL)
                .background(if (chosen) CycleAccent else CycleCell, RoundedCornerShape(CELL_RADIUS)),
            contentAlignment = Alignment.Center,
        ) {
            val ink = when {
                chosen -> CycleSheet
                bleeding -> PwnBleed
                else -> CycleCellInk
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
                // A bleeding day carries the calendar's drop under its figure, so it is marked by a
                // shape and not only by the colour of the number — and keeps it when chosen, where
                // the colour gives way to the chosen cell's own ink. Spotting carries the
                // calendar's open dot instead: it is bleeding, and it is not a day of a period.
                if (bleeding) {
                    Canvas(Modifier.size(width = BLEED_MARK, height = BLEED_MARK_ROW)) {
                        val unit = BLEED_MARK.toPx()
                        val centre = Offset(size.width / 2f, size.height - unit / 2f)
                        if (spotting) drawSpot(centre, unit, ink) else drawDroplet(centre, unit, ink)
                    }
                }
            }
        }
    }
}

private val CELL = 46.dp
private val CELL_TODAY = 56.dp
private val CELL_RADIUS = PwnCorners.large

/** The air either side of a day's cell, which is half the gap between two drawn cells. */
private val WEEK_CELL_INSET = 2.dp

/** The drop under a bleeding day: as wide as the calendar's, and a row tall enough for its point. */
private val BLEED_MARK = 7.dp
private val BLEED_MARK_ROW = 11.dp

/**
 * What the day is asked, one question at a time, and what it has already answered.
 *
 * On a day of a period: how much, and then how much it hurts. On any other day: what she is seeing,
 * how much she wants to, and an ovulation test if she ran one. Which of the two is the chosen day's
 * own answer and not today's ([questionsOn]), so a missed day of a period is still asked about its
 * bleeding once the period is over. The next unanswered question is the one on the slider; the
 * answers already in stand under it as rows she can reopen. This is the whole of how symptoms are
 * recorded, with no separate page to reach.
 *
 * The heading names the question and the large word is the answer to it. Without the heading the
 * screen would print the same word twice, once large and once in the row of rungs, and read as
 * though it were saying two different things.
 *
 * [acts] is the age gate's answer, and what it takes away here is the question about desire.
 *
 * An answer reopened can be taken back as well as changed ([onClear]). Every rung of a ladder is an
 * answer, so a slider brushed in passing could only ever be corrected to another one — and an
 * ovulation test filed that way moves the fertility word and goes to Health Connect.
 */
@Composable
private fun CycleTodayBlock(
    facts: CycleFacts,
    today: LocalDate,
    day: LocalDate,
    acts: Boolean,
    onPick: (CycleSection, CycleLog, LocalDate) -> Unit,
    onClear: (CycleSection, LocalDate) -> Unit,
) {
    val questions = remember(facts, day, acts) { facts.questionsOn(day, acts) }
    val answers = questions.associateWith { facts.rungOn(day, it) }
    var editing by remember(day) { mutableStateOf<CycleScale?>(null) }
    val asking = editing ?: questions.firstOrNull { answers[it] == null }
    val dayMonth = rememberDayMonth()

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (day != today) {
            Text(
                stringResource(R.string.cycle_symptoms_on, day.format(dayMonth)),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
        }
        asking?.let { scale ->
            // A slider of its own for each question of each day. One slider handed the next question
            // would keep what the finger had last done on it: two unanswered ladders open on the same
            // middle index, so nothing tells it to forget, and "Period pain" would open on "No pain"
            // after a light flow and "Ovulation test" on "Positive" after a strong desire.
            key(day, scale) {
                CycleStopSlider(
                    question = stringResource(scale.titleRes()),
                    labels = scale.logs.map { stringResource(it.titleRes) },
                    // Nothing chosen yet opens in the middle rather than at one end: an untouched
                    // control resting on "none" is an answer she did not give.
                    selected = (answers[scale] ?: ((scale.logs.size + 1) / 2)) - 1,
                    onSelected = {
                        editing = null
                        onPick(scale.section(), scale.logs[it], day)
                    },
                )
            }
            if (scale == editing && answers[scale] != null) {
                TextButton(
                    onClick = {
                        editing = null
                        onClear(scale.section(), day)
                    },
                ) { Text(stringResource(R.string.cycle_answer_clear)) }
            }
        }
        questions.forEach { scale ->
            val rung = answers[scale] ?: return@forEach
            if (scale == asking) return@forEach
            PwnAnswerRow(
                question = stringResource(scale.titleRes()),
                answer = stringResource(scale.logs[rung - 1].titleRes),
                container = CycleCard,
            ) { editing = scale }
        }
    }
}

/**
 * A short ladder chosen on a slider, with the chosen rung named large above it and every rung named
 * under it, and a ring for a thumb.
 *
 * The thumb follows the finger, but the answer is only taken when the finger comes off: a slider's
 * onValueChange fires on every pixel of a drag, and this one's caller writes a journal row, so
 * dragging across four rungs would file, and delete, dozens of records on the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CycleStopSlider(
    question: String,
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val stops = labels.size
    if (stops <= 1) return
    var dragging by remember(selected) { mutableStateOf<Int?>(null) }
    val shown = (dragging ?: selected).coerceIn(0, stops - 1)
    val accent = CycleAccent
    val sheet = CycleSheet
    val rest = CycleCell

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The question, so the large word under it reads as its answer rather than as a second
        // thing being said. Without it the screen would print "Умеренная" over a row whose third rung
        // also reads "Умеренная", and the two would look like different claims.
        Text(
            question,
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Crossfade(shown, animationSpec = PwnMotion.effects(), label = question) {
            Text(
                labels[it.coerceIn(0, stops - 1)],
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        val state = remember(stops) {
            SliderState(value = shown.toFloat(), steps = stops - 2, trackRange = 0f..(stops - 1).toFloat())
        }
        state.value = shown.toFloat()
        Slider(
            state = state,
            onValueChange = { state.value = it },
            // The rung is read off the slider itself. A screen reader's adjust sets the value and
            // finishes in the same call, before the collector below has seen the new value — reading
            // the collector's copy would file the rung before the one announced, or nothing at all.
            onValueChangeFinished = { onSelected(state.value.roundToInt().coerceIn(0, stops - 1)) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = question
                stateDescription = labels[shown]
            },
            colors = SliderDefaults.colors(
                activeTrackColor = accent,
                inactiveTrackColor = CycleCell,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            // The thumb is a ring, not Material's bar: a white disc with the violet
            // drawn round it, which is the one part of this control a finger is meant to find.
            thumb = {
                Box(
                    Modifier.size(THUMB)
                        .background(sheet, CircleShape)
                        .border(THUMB_RING, accent, CircleShape),
                )
            },
            // And its own track. Material's leaves a gap either side of the thumb and draws a stop
            // dot at each end; through a ring thumb those would show as two white slits cut across the
            // line. This is one rounded line with the ring standing on it.
            track = { sliderState ->
                val fraction = if (sliderState.trackRange.endInclusive > sliderState.trackRange.start) {
                    (sliderState.value - sliderState.trackRange.start) /
                        (sliderState.trackRange.endInclusive - sliderState.trackRange.start)
                } else {
                    0f
                }
                Canvas(Modifier.fillMaxWidth().height(TRACK)) {
                    val y = size.height / 2f
                    val cap = StrokeCap.Round
                    drawLine(rest, Offset(0f, y), Offset(size.width, y), size.height, cap = cap)
                    if (fraction > 0f) {
                        drawLine(
                            accent,
                            Offset(0f, y),
                            Offset(size.width * fraction.coerceIn(0f, 1f), y),
                            size.height,
                            cap = cap,
                        )
                    }
                }
            },
        )
        LaunchedEffect(state) {
            snapshotFlow { state.value }.collect { dragging = it.roundToInt().coerceIn(0, stops - 1) }
        }
        Spacer(Modifier.height(10.dp))
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.bodyMedium
        val density = LocalDensity.current
        // What the rungs need if every word is to stay a word. On a phone with large text five of
        // them do not fit across a screen, and split evenly they would break mid-word — "Крем" over
        // "ообр". Given the width they need, the row scrolls instead, the way the plot does.
        val needed = remember(labels, style, density) {
            with(density) {
                labels.sumOf { measurer.measure(it, style).size.width + STOP_LABEL_PAD.roundToPx() }.toDp()
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fits = needed <= maxWidth
            Row(
                Modifier.fillMaxWidth()
                    .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .selectableGroup(),
            ) {
                labels.forEachIndexed { index, text ->
                    CycleStopLabel(
                        text = text,
                        chosen = index == shown,
                        accent = accent,
                        // Evenly, so each word stands under its own stop on the track — until they
                        // no longer fit, when standing under the stop costs the word itself.
                        modifier = if (fits) Modifier.weight(1f) else Modifier,
                    ) {
                        dragging = index
                        onSelected(index)
                    }
                }
            }
        }
    }
}

/** One rung of the ladder, named under the track and tappable as its own answer. */
@Composable
private fun CycleStopLabel(
    text: String,
    chosen: Boolean,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            // 14dp either side of a 20sp line box clears the 48dp a finger is owed; a Text with a
            // selectable on it is given no minimum of its own.
            .padding(vertical = 14.dp, horizontal = STOP_LABEL_PAD / 2),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = if (chosen) accent else PwnMuted,
        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
    )
}

private val STOP_LABEL_PAD = 12.dp

private val THUMB = 26.dp
private val THUMB_RING = 4.dp
private val TRACK = 6.dp

/** The section a ladder's answers are filed under, which is what the replace rule works on. */
private fun CycleScale.section(): CycleSection = when (this) {
    CycleScale.FLOW -> CycleSection.FLOW
    CycleScale.PAIN -> CycleSection.PAIN
    CycleScale.MUCUS -> CycleSection.MUCUS
    CycleScale.DESIRE -> CycleSection.DESIRE
    CycleScale.OVULATION -> CycleSection.OVULATION
}

/**
 * Below the rule: the act, if there was one. Three buttons and no ceremony — this is logged in
 * passing, and the solo one keeps its own journal kind so it never reaches Health Connect.
 *
 * Composed only for a profile [sexLogOffered] offers the log to: its one call stands behind that
 * answer, and [CycleAgeGateTest] holds it there.
 */
@Composable
private fun CycleIntimateRow(
    facts: CycleFacts,
    day: LocalDate,
    quickLog: QuickLogState,
    onLog: (CycleLog?, String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val logged = remember(facts, day) { facts.intimacyOn(day) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.cycle_intimate_title),
            style = MaterialTheme.typography.titleMedium,
            color = PwnMuted,
        )
        val acts = listOf(
            CycleLog.SEX_PROTECTED to stringResource(R.string.cycle_sex_protected),
            CycleLog.SEX_UNPROTECTED to stringResource(R.string.cycle_sex_unprotected),
            null to stringResource(R.string.sexual_masturbation),
        )
        val longestWord = longestWordWidth(acts.map { it.second })
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Three abreast while every word of every label fits its third of the row; stacked when
            // one does not. A word too long for its button would be broken in the middle, and a label
            // held to two lines would lose its last fragment altogether — the whole of what the button says.
            val third = (maxWidth - ACT_GAP * 2) / acts.size - ACT_PADDING * 2
            if (longestWord <= third) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(ACT_GAP),
                ) {
                    acts.forEach { (log, label) ->
                        CycleActButton(label, quickLog.locked(log?.name ?: MASTURBATION_KEY), Modifier.weight(1f)) {
                            onLog(log, it)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ACT_GAP)) {
                    acts.forEach { (log, label) ->
                        CycleActButton(label, quickLog.locked(log?.name ?: MASTURBATION_KEY), Modifier) {
                            onLog(log, it)
                        }
                    }
                }
            }
        }
        logged.forEach { (id, log) ->
            CycleLoggedRow(stringResource(log?.titleRes ?: R.string.sexual_masturbation)) { onRemove(id) }
        }
    }
}

/**
 * Bleeding on a day outside a period: one button, and once pressed the row it left, with its ×. A
 * day holds one spotting — [logCycleLog] replaces the day's bleeding answer — so the button gives
 * way to the row rather than offering a second.
 */
@Composable
private fun CycleSpottingRow(logged: Long?, shut: Boolean, onLog: (String) -> Unit, onRemove: (Long) -> Unit) {
    val label = stringResource(R.string.cycle_spotting)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.cycle_section_flow),
            style = MaterialTheme.typography.titleMedium,
            color = PwnMuted,
        )
        if (logged == null) {
            CycleActButton(label, shut, Modifier, onLog)
        } else {
            CycleLoggedRow(label) { onRemove(logged) }
        }
    }
}

/** A row this page logged, named in the language showing, and the × that takes it off again. */
@Composable
private fun CycleLoggedRow(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(CycleCard, RoundedCornerShape(CHIP_RADIUS))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onRemove, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_delete),
            )
        }
    }
}

/** One of the three acts: a pale button filled to its row's height, the label wrapping as it needs. */
@Composable
private fun CycleActButton(label: String, shut: Boolean, modifier: Modifier, onLog: (String) -> Unit) {
    val confirmation = quickLogAdded(label)
    Box(
        modifier.fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = PwnDimensions.minimumTarget)
            .background(CycleCell, RoundedCornerShape(CHIP_RADIUS))
            .clickable(enabled = !shut, role = Role.Button) { onLog(confirmation) }
            .padding(vertical = 14.dp, horizontal = ACT_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (shut) PwnMuted else CycleCellInk,
            textAlign = TextAlign.Center,
        )
    }
}

/** The widest single word among [labels], as the act buttons print it. */
@Composable
private fun longestWordWidth(labels: List<String>): androidx.compose.ui.unit.Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    return remember(labels, style, density) {
        val words = labels.flatMap { it.split(' ', '\u00A0') }.filter { it.isNotBlank() }
        with(density) { (words.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toDp() }
    }
}

private val ACT_GAP = 8.dp
private val ACT_PADDING = 4.dp

/** The solo act has no [CycleLog] of its own, so the quick log needs a key for it. */
internal const val MASTURBATION_KEY = "masturbation"

/** The record button in the bar, whichever of its three things it is about to do. */
private const val PERIOD_KEY = "period"

/** The Save on the page that asks for the first date of all. */
private const val FIRST_CYCLE_KEY = "firstCycle"

/**
 * A flag in words, the same under the headline and in the dialog behind the "?". Every count of
 * days is the plural of its own number: "%d days" would read "1 days" in English and "3 dní" in Czech.
 */
@Composable
private fun flagLine(flag: CycleFlag, dayMonth: DateTimeFormatter): String = when (flag) {
    is CycleFlag.Prolonged -> stringResource(
        R.string.cycle_flag_prolonged,
        pluralStringResource(R.plurals.count_days, flag.days, flag.days),
        pluralStringResource(R.plurals.count_days, flag.threshold, flag.threshold),
    )

    is CycleFlag.Late -> stringResource(
        R.string.cycle_flag_late,
        pluralStringResource(R.plurals.count_days, flag.days, flag.days),
        flag.expected.format(dayMonth),
    )
}

/** One line of what the record says about today, never a word about whether it is healthy. */
@Composable
private fun verdictLine(verdict: NormVerdict): String = when (verdict) {
    is NormVerdict.Same ->
        pluralStringResource(R.plurals.cycle_verdict_same, verdict.compared, verdict.compared)

    is NormVerdict.Different -> pluralStringResource(
        if (verdict.higher) R.plurals.cycle_verdict_higher else R.plurals.cycle_verdict_lower,
        verdict.compared,
        verdict.compared,
    )

    // A record that has been every rung at this point has plenty of evidence and no usual in it, so
    // the span is the honest answer; "one more cycle and I will know" would not be.
    is NormVerdict.TooLittle -> verdict.band?.let {
        stringResource(
            R.string.cycle_verdict_band,
            stringResource(verdict.scale.logs[it.first - 1].titleRes),
            stringResource(verdict.scale.logs[it.last - 1].titleRes),
        )
    } ?: run {
        val missing = (verdict.needed - verdict.compared).coerceAtLeast(1)
        pluralStringResource(R.plurals.cycle_verdict_too_little, missing, missing)
    }
}

/** The evidence behind the chip: what was compared, against how many of her own cycles, and where. */
@Composable
private fun NormVerdictDialog(
    facts: CycleFacts,
    phase: CyclePhase,
    summary: CycleSummary,
    today: LocalDate,
    ageYears: Int?,
    onDismiss: () -> Unit,
) {
    val verdict = remember(facts, phase) { facts.normVerdict(phase) }
    val flags = remember(summary, phase, ageYears) { cycleFlags(summary, today, phase, ageYears) }
    val dayMonth = rememberDayMonth()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_verdict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The caveat in full, which the headline has no room for: it keeps one line of
                // it and the rest is here, which is what the "?" beside the word is for.
                // Printed on every day the word is drawn from a calendar rather than only on the
                // days a window happens to be open — "Низкая" is a reading, not a clearance.
                if (phase !is CyclePhase.Menstruation) {
                    Text(stringResource(R.string.cycle_fertile_note))
                }
                verdict?.let { Text(verdictLine(it)) }
                // Only where the band is a span. "Usually between Heavy and Heavy" is a sentence
                // with no information in it, and the line above already said she has been here.
                verdict?.band?.takeIf { it.first != it.last }?.let {
                    Text(
                        stringResource(
                            R.string.cycle_verdict_band,
                            stringResource(verdict.scale.logs[it.first - 1].titleRes),
                            stringResource(verdict.scale.logs[it.last - 1].titleRes),
                        ),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                flags.forEach { flag -> Text(flagLine(flag, dayMonth), color = PwnBleed) }
                // The one sentence this dialog must never be read without. The comparison is against
                // her own record and nothing else; it is not a judgement on her health, and the app
                // is in no position to make one.
                Text(
                    stringResource(R.string.cycle_verdict_note),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/**
 * The one sentence under the strip.
 *
 * Which one is a decision, not the first card off a list: most-specific-first within the phase — the
 * ovulation estimate while its day is ahead, how long her periods run during one, and otherwise the
 * readings of her record [patternHint] chooses among — and that order is the whole of why this is a
 * function and not a `.take(1)` of [cycleInsights] in the card above.
 */
@Composable
private fun cyclePhaseHint(
    facts: CycleFacts,
    phase: CyclePhase,
    summary: CycleSummary,
    today: LocalDate,
): String? {
    val dayMonth = rememberDayMonth()
    val insights = remember(facts) { cycleInsights(facts.entries, summary, today) }
    // The ovulation estimate first wherever the record supports one, and from day one rather than
    // only on the days the window happens to be open: a woman on day two is asking when the fertile
    // days are coming, and the answer is no use once they have arrived. Until that day has gone
    // by, and no longer — see [upcomingOvulation].
    val ovulation = remember(facts) { upcomingOvulation(summary, today) }
    return when {
        ovulation != null -> stringResource(R.string.cycle_hint_peak, ovulation.format(dayMonth))

        phase is CyclePhase.Menstruation -> insights.filterIsInstance<CycleInsight.PeriodLength>()
            .firstOrNull()
            ?.let {
                stringResource(
                    R.string.cycle_hint_period_length,
                    pluralStringResource(R.plurals.count_days, it.median, it.median),
                )
            }
            ?: patternHint(insights)

        // The expected day only while it is still ahead, the rule the headline's own countdown keeps:
        // on a record gone quiet "Next expected 29 Mar" would stand here in November.
        else -> patternHint(insights)
            ?: summary.nextExpected?.takeIf { !it.isBefore(today) }
                ?.let { stringResource(R.string.cycle_next, it.format(dayMonth)) }
    }
}

/**
 * The reading of her own record that is worth the line, in words. Which one is [patternInsight]'s
 * answer: the two patterns that need months of entries first, then whether her cycles hold still.
 *
 * The arithmetic is [cycleInsights] and its sentences, printed one at a time where there is room for
 * one.
 */
@Composable
private fun patternHint(insights: List<CycleInsight>): String? = when (val insight = patternInsight(insights)) {
    is CycleInsight.MoodPattern -> stringResource(
        if (insight.difference > 0) {
            R.string.cycle_insight_mood_follicular
        } else {
            R.string.cycle_insight_mood_luteal
        },
        pluralStringResource(R.plurals.count_days, insight.days, insight.days),
    )

    is CycleInsight.SleepPattern -> stringResource(
        if (insight.periodMinutes < insight.otherMinutes) {
            R.string.cycle_insight_sleep_shorter
        } else {
            R.string.cycle_insight_sleep_longer
        },
        kotlin.math.abs(insight.periodMinutes - insight.otherMinutes),
        pluralStringResource(R.plurals.count_nights, insight.nights, insight.nights),
    )

    is CycleInsight.Length -> stringResource(
        if (insight.regular) R.string.cycle_insight_regular else R.string.cycle_insight_varies,
    )

    else -> null
}

/**
 * "Has it started?" and "Is it over?" — the two questions the record button asks.
 *
 * Its own dialog rather than an immediate write, because the button sits in the corner a thumb
 * passes over and because both taps write a row that reaches Health Connect.
 */
@Composable
private fun PeriodSwitchDialog(
    action: CycleAction,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dayMonth = rememberDayMonthLong()
    val title = when (action) {
        is CycleAction.Reopen -> R.string.cycle_reopen_title
        is CycleAction.Finish -> R.string.cycle_finish_free_title
        CycleAction.Start -> R.string.cycle_start_title
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Text(
                when (val chosen = action) {
                    is CycleAction.Finish -> {
                        val days = ChronoUnit.DAYS.between(chosen.start, today).toInt() + 1
                        stringResource(
                            R.string.cycle_finish_body,
                            chosen.start.format(dayMonth),
                            today.format(dayMonth),
                            pluralStringResource(R.plurals.count_days, days, days),
                        )
                    }

                    is CycleAction.Reopen -> stringResource(
                        R.string.cycle_reopen_body,
                        chosen.period.end.format(dayMonth),
                    )

                    CycleAction.Start -> stringResource(R.string.cycle_start_body, today.format(dayMonth))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_no)) }
        },
    )
}

/**
 * "That was quick — or was it a slip?" — asked only when a period was closed at [PERIOD_SHORT_DAYS]
 * or less, and only once.
 *
 * Two days is the short end of ordinary and it is also exactly what an accidental tap produces the
 * day after a start; the record cannot tell them apart. So the app does not refuse it and does not
 * silently keep it: it asks once, with the dates in front of her, and takes her answer.
 */
@Composable
private fun QuickPeriodDialog(period: CyclePeriod, onAnswer: (Boolean) -> Unit) {
    val dayMonth = rememberDayMonth()
    val days = ChronoUnit.DAYS.between(period.start, period.end).toInt() + 1
    AlertDialog(
        onDismissRequest = { onAnswer(true) },
        title = { Text(stringResource(R.string.cycle_quick_title)) },
        text = {
            Text(
                stringResource(
                    R.string.cycle_short_body,
                    period.start.format(dayMonth),
                    period.end.format(dayMonth),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(pluralStringResource(R.plurals.cycle_quick_real, days, days))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(R.string.cycle_quick_accident), color = PwnBleed)
            }
        },
    )
}

/** The one big control the sub-pages share. The app's own button height and corner, not a new pill. */
internal val CYCLE_CTA_HEIGHT = 56.dp
internal val CYCLE_CTA_RADIUS = PwnCorners.large

/**
 * "1 August → 30 August. Correct?"
 *
 * Shown when a period was left open longer than one lasts, which is the case where the day the
 * button is pressed on is not the day the period ended. Both dates are open to correction, because
 * the tap that was forgotten is usually not the only thing that was: a period that started while
 * the app was closed was often marked a day late too.
 *
 * The end starts where her periods usually end ([closingEndGuess]), and the two days are held to
 * the record as the page for a missed period holds them ([closingPeriodFits]): where they touch
 * another period the dialog says so and keeps its Save. Left to the write, a start moved there
 * would be turned down after the dialog had gone, with nothing said at all.
 */
@Composable
internal fun ClosePeriodDialog(
    start: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val db = remember { StepsDb.get(context) }
    // Nothing is drawn until the marks are read, so the end does not move under her finger.
    val marks by produceState<List<JournalEntry>?>(null, db) { value = db.dao().cycleMarksNow() }
    val record = marks ?: return
    // The short month, and the two buttons share the width equally. Spelt in full, the second date
    // would be handed whatever the first had left and break "27 September" across three lines.
    val format = rememberDayMonth()
    val today = rememberToday()
    // Saveable, as both pages that open it keep the dialog itself, so turning the phone on its
    // side keeps the two days she has moved.
    var from by rememberSaveable { mutableStateOf(start) }
    var to by rememberSaveable { mutableStateOf(closingEndGuess(start, medianPeriodLength(record), today)) }
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val free = closingPeriodFits(record, start, from, to, today)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_close_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.cycle_close_body))
                PeriodEndsPicker(
                    from = from.format(format),
                    to = to.format(format),
                    onPickFrom = { picking = true },
                    onPickTo = { picking = false },
                )
                if (!free) Text(stringResource(R.string.cycle_past_taken), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(from, to) }, enabled = free) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    picking?.let { pickingStart ->
        PastDateDialog(
            initial = if (pickingStart) from else to,
            onDismiss = { picking = null },
            onPick = { picked ->
                // The interval may not run backwards, and dragging one end past the other is how a
                // person says they meant to move both.
                if (pickingStart) {
                    from = picked
                    if (to < from) to = from
                } else {
                    to = picked
                    if (from > to) from = to
                }
                picking = null
            },
        )
    }
}

/**
 * The period's two ends, each a button that reopens its own date.
 *
 * Side by side with an arrow between them while both dates fit whole; stacked, and each named in
 * words, once they do not — at a large text size the side-by-side pair would cut the date, which is
 * the only thing either button says. Either way a screen reader hears which end a button is, because
 * "27 Sep, button" does not say whether it starts the period or ends it.
 */
@Composable
private fun PeriodEndsPicker(from: String, to: String, onPickFrom: () -> Unit, onPickTo: () -> Unit) {
    val fromName = stringResource(R.string.cycle_first_started, from)
    val toName = stringResource(R.string.m3_cal_period_ended, to)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val widest = remember(from, to, style, density) {
        with(density) { maxOf(measurer.measure(from, style).size.width, measurer.measure(to, style).size.width).toDp() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // What one button of the pair needs: its date, the button's own padding either side, and
        // its share of the arrow and the gaps around it.
        val side = widest + PAIR_BUTTON_PADDING * 2
        val fits = side * 2 + PAIR_ARROW + PAIR_GAP * 2 <= maxWidth
        if (fits) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PAIR_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onPickFrom, Modifier.weight(1f).semantics { contentDescription = fromName }) {
                    Text(from)
                }
                // Mirrors with the layout, so in a right-to-left language it still points from the
                // start to the end; and it is drawing only, the two buttons already say which is which.
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    Modifier.size(PAIR_ARROW),
                )
                OutlinedButton(onPickTo, Modifier.weight(1f).semantics { contentDescription = toName }) {
                    Text(to)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PAIR_GAP)) {
                OutlinedButton(onPickFrom, Modifier.fillMaxWidth()) { Text(fromName, textAlign = TextAlign.Center) }
                OutlinedButton(onPickTo, Modifier.fillMaxWidth()) { Text(toName, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** The outlined button's own padding either side of its label, as Material draws it. */
private val PAIR_BUTTON_PADDING = 24.dp
private val PAIR_ARROW = 20.dp
private val PAIR_GAP = 6.dp

/** A day that has already happened. Neither end of a period can be in the future. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PastDateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = rememberToday()
    val todayUtc = remember(today) { today.toPickerMillis() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        // Last year is enough for a period that ran over New Year, but the day this opens on has to
        // be inside the range whatever it is: the picker throws on an initial date outside it, and
        // a start left open since the year before last is exactly the record this dialog exists for.
        yearRange = minOf(initial.year, today.year - 1)..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        },
    )
    LocalDateDialog(state, onDismiss, onPick)
}

/**
 * "17 сентября" rather than "17 сент." — the form a date takes inside a sentence.
 *
 * Short month names carry their own full stop in several languages, so a date placed before one of
 * the sentence's own comes out as "17 сент..". The abbreviation is right where space is tight — a
 * chip, a caption — and wrong in prose; this is the prose one.
 */
@Composable
internal fun rememberDayMonthLong(): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { localizedDatePattern(locale, "dMMMM", "d MMMM") }
}

/** Day and short month in the phone's language, which most of this file's dates are printed in. */
@Composable
internal fun rememberDayMonth(): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { localizedDatePattern(locale, "dMMM", "d MMM") }
}

/**
 * A date written in the order and punctuation of the phone's language.
 *
 * A fixed "d MMM" would put the day first in all sixteen languages, which in Japanese is "17 9月"
 * where a reader expects "9月17日", and in American English "17 Sep" where one expects "Sep 17".
 * [skeleton] names only the fields; the platform decides their order. A pattern java.time cannot
 * read falls back to [fallback] rather than taking the screen down with it.
 */
internal fun localizedDatePattern(locale: java.util.Locale, skeleton: String, fallback: String): DateTimeFormatter =
    runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale) }
        .getOrElse { DateTimeFormatter.ofPattern(fallback, locale) }

/**
 * "17 Sep, 08:05": the day and the month as [dayMonth] orders them, then the clock every screen writes.
 *
 * The time is [clockText]'s and not a field of the pattern. A skeleton that names an hour comes back
 * as "H:mm" in one language and "HH:mm" in the next, and on the twelve-hour clock where the phone is
 * set to it, which is nothing the rest of the app does.
 */
internal fun dayMonthClock(at: ZonedDateTime, dayMonth: DateTimeFormatter): String =
    "${at.format(dayMonth)}, ${clockText(at.hour * 60 + at.minute)}"

internal fun cycleEndEntry(resources: Resources, day: String) = JournalEntry(
    day = day,
    occurredAt = dayInstant(day),
    kind = KIND_CYCLE_END,
    title = resources.getString(R.string.cycle_period_end),
    calories = 0.0,
)

/**
 * The ticks ride in the payload under their own key, beside the log's name, so a restore reads
 * them back as the same four options rather than as a sentence, and the day list names them from
 * there in the language showing ([sexNotesText]); the subtitle keeps the words for what reads the
 * row outside the app.
 */
internal fun cycleLogEntry(
    resources: Resources,
    log: CycleLog,
    day: String,
    notes: List<SexNote> = emptyList(),
) = JournalEntry(
    day = day,
    occurredAt = dayInstant(day),
    kind = KIND_CYCLE_LOG,
    title = resources.getString(log.titleRes),
    subtitle = notes.joinToString(", ") { resources.getString(it.titleRes) },
    calories = 0.0,
    measurementPayloadJson = JSONObject()
        .put(CYCLE_PAYLOAD_KEY, log.name)
        .apply { if (notes.isNotEmpty()) put(CYCLE_NOTES_KEY, JSONArray(notes.map(SexNote::name))) }
        .toString(),
)
