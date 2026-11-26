package com.pwnall.health

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * What the blood work says, as opposed to where it is typed in.
 *
 * Reading a panel and entering one are two different visits: a person copies a sheet across once
 * and then comes back to this page for months afterwards to see whether a figure is moving. On one
 * screen, the form — twenty-odd fields behind seven folds — would stand between the results and
 * everything under them, so the form has a page of its own, reached from the button at the foot of
 * this one; this page is a card per marker, its own chart on each, and the bell that
 * says a result landed outside its band.
 */
@Composable
fun LabResultsScreen(modifier: Modifier, profile: UserProfile, onBack: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val today = rememberToday()
    val currentYear = today.year
    val imperial = profile.imperial
    var hiddenTopics by remember { mutableStateOf(AdviceLog.hidden(context)) }
    // Read off the main thread: any write anywhere in the app re-emits
    // the whole journal, and parsing every payload in composition is not free at a few thousand rows.
    val labs by remember(profile, today, hiddenTopics) {
        combine(
            dao.journalBetween(labWindowStart(today), "9999-12-31"),
            dao.daysSince(today.minusDays(ACTIVITY_WINDOW_DAYS - 1L).toString()),
        ) { journal, days ->
            val history = labHistory(journal, profile)
            val readings = history.values.mapNotNull(List<LabReading>::lastOrNull)
            LabResultsView(
                history = history,
                journal = journal,
                readings = readings,
                notices = labNotices(history),
                advice = labsSectionAdvice(readings, journal, days, profile, currentYear, today)
                    .filter { it.topic.name !in hiddenTopics },
                egfrNotCalculated = egfrNotCalculated(history),
                sheets = journal.filter { it.kind == KIND_LABS }
                    .sortedByDescending(JournalEntry::occurredAt)
                    .flatMap { entry -> medicalAttachments(entry.labAttachments()).map { entry.day to it } },
                panels = journal.sortedByDescending(JournalEntry::occurredAt).mapNotNull { entry ->
                    entry.allergyPanel().takeIf(List<AllergenResult>::isNotEmpty)?.let { entry.day to it }
                },
            )
        }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(LabResultsView())

    // Held rather than read on every frame: pulling the shade down writes the row it was pulled on,
    // and the dot has to go out on that write rather than on the next trip through this screen.
    var seenUntil by remember { mutableLongStateOf(LabNoticeLog.seenUntil(context)) }
    var shade by rememberSaveable { mutableStateOf(false) }
    // By name, so a rotation — the chart's own sideways view included — comes back to the same marker.
    var openMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var library by rememberSaveable { mutableStateOf(false) }
    val unread = unreadLabNotices(labs.notices, seenUntil)

    Box(modifier.fillMaxSize()) {
        // While the notice shade is down the page behind it is scenery for a screen reader.
        Column(Modifier.fillMaxSize().then(if (shade) Modifier.clearAndSetSemantics { } else Modifier)) {
            PwnTopBar(stringResource(R.string.labs_title), onBack) {
                NoticeBell(
                    unread = unread > 0,
                    contentDescription = stringResource(
                        if (unread > 0) R.string.labs_notices_unread else R.string.labs_notices_open,
                    ),
                ) {
                    shade = true
                    // The row the shade is about to show, not the clock: the mark is compared against
                    // journal row ids, and a wall-clock time is a number so large it would read as
                    // "everything, for ever" and put the dot out permanently.
                    val mark = labNoticeMark(labs.notices)
                    LabNoticeLog.markSeen(context, mark)
                    seenUntil = maxOf(seenUntil, mark)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize()
                        .panelVerticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = LocalBottomBarInset.current),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.labs_results_subtitle), color = PwnMuted)
                    if (labs.history.isEmpty()) {
                        // An ultrasound or an MRI is no figure, but it is a result: kept alone, it is
                        // the shelf of sheets that stands where the empty card would.
                        AllergyPanelsCard(labs.panels)
                        if (labs.sheets.isEmpty()) {
                            PwnCard(Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.labs_empty),
                                    Modifier.padding(PwnDimensions.cardPadding),
                                    color = PwnMuted,
                                )
                            }
                        } else {
                            SavedLabSheets(labs.sheets)
                        }
                    } else {
                        labs.history.forEach { (marker, readings) ->
                            LabMarkerCard(marker, readings, profile.sex, imperial, labs.journal) {
                                openMarker = marker.name
                            }
                        }
                        // Under the figures rather than over them: the cards are what the page is
                        // for, and three paragraphs of advice standing in front of them is a wall to
                        // scroll past on every visit.
                        labs.advice.forEach { one -> AdviceCard(one) { hiddenTopics = AdviceLog.hidden(context) } }
                        LabSummaryCards(labs.readings, ageOn(today.toString(), profile.birthDate))
                        AllergyPanelsCard(labs.panels)
                        SavedLabSheets(labs.sheets)
                        if (labs.egfrNotCalculated) {
                            Text(
                                stringResource(R.string.labs_egfr_pediatric),
                                color = PwnMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(
                        onClick = onAdd,
                        // A floor rather than a height, so a label that wraps at a large text size
                        // makes the button taller instead of being cut off.
                        modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                    ) { Text(stringResource(R.string.labs_add_title)) }
                    // The way into the reference reading, and the only way: it is never opened by
                    // a result, because a page of what-happens-next triggered by somebody's own
                    // figure is a different kind of software from this one. See [LAB_LIBRARY].
                    androidx.compose.material3.TextButton(
                        onClick = { library = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.lab_library_open)) }
                    DataNoteLink(R.string.labs_disclaimer)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        LabNoticeShade(labs.notices, profile.sex, imperial, shade) { shade = false }
    }

    if (library) {
        LabLibraryPage { library = false }
    }

    openMarker?.let { name ->
        val marker = LabMarker.entries.firstOrNull { it.name == name }
        val readings = marker?.let { labs.history[it] }.orEmpty()
        if (marker != null && readings.isNotEmpty()) {
            LabChartPage(marker, readings, profile.sex, imperial) { openMarker = null }
        }
    }
}

/** What the page draws, built away from the main thread and swapped in as one piece. */
private data class LabResultsView(
    val history: Map<LabMarker, List<LabReading>> = emptyMap(),
    /** The rows the readings came out of, which is also the diary the cards describe a draw by. */
    val journal: List<JournalEntry> = emptyList(),
    val readings: List<LabReading> = emptyList(),
    val notices: List<LabReading> = emptyList(),
    val advice: List<Advice> = emptyList(),
    val egfrNotCalculated: Boolean = false,
    val sheets: List<Pair<String, MedicalAttachment>> = emptyList(),
    /** Every allergy panel saved, newest first, by the day it was drawn. */
    val panels: List<Pair<String, List<AllergenResult>>> = emptyList(),
)

/**
 * One marker: what it is, where the last reading fell, the figure itself, and the line it sits on.
 *
 * The whole card opens the chart, rather than a chevron somewhere on it: the drawing is the thing
 * being tapped, and the card answers one tap anywhere on it.
 */
@Composable
private fun LabMarkerCard(
    marker: LabMarker,
    readings: List<LabReading>,
    sex: String,
    imperial: Boolean,
    journal: List<JournalEntry>,
    onOpen: () -> Unit,
) {
    val newest = readings.last()
    val band = marker.bandFor(sex, newest.ageYears, newest.fasting)
    // One precision for the figure, the band and the chart under them, so none of them is rounded
    // across an edge another prints — see [printPlaces].
    val places = remember(readings, sex, imperial) { marker.printPlaces(readings, sex, imperial) }
    val bounds = remember(readings) { labChartBounds(readings.map(LabReading::occurredAt)) }
    val window = remember(bounds) { LabChartWindow(bounds.startMs, bounds.endMs) }
    val openChart = stringResource(R.string.m3_labs_open_chart)
    PwnCard(
        // Named, so a screen reader says what the tap on the card does rather than a bare "activate".
        Modifier.fillMaxWidth().semantics {
            onClick(label = openChart) {
                onOpen()
                true
            }
        },
        onClick = onOpen,
    ) {
        Column(
            Modifier.padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(marker.titleRes),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                LabStatusBadge(newest.status, band)
            }
            // The unit stands on the figure's baseline, which holds at every text size where a fixed
            // nudge up from the bottom would line the two up at one size only.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    shownFigure(marker.printed(newest, sex, imperial, places)),
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                // A pH or a specific gravity has no unit, and a dash beside it would read as one.
                if (!marker.unitless) {
                    Text(
                        stringResource(marker.unitLabelRes(imperial)),
                        Modifier.alignByBaseline(),
                        color = PwnMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            // A marker with no band has nothing to print on this line, and printing an empty range
            // under the figure reads as a range nobody filled in rather than as one nobody draws.
            if (band != null) {
                Text(
                    stringResource(R.string.labs_norm, bandText(marker, band, imperial, places)),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // And a figure with no band says why: insulin is graded by nothing, and a child's figure
            // is not held to an adult band.
            if (band == null) {
                Text(
                    stringResource(marker.withoutBandRes),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // The laboratory's own range under the app's band, where the sheet printed one and it
            // was typed in: the two can disagree, and which of them a reading is outside is the
            // thing worth seeing.
            newest.reported?.let { reported ->
                Text(
                    stringResource(R.string.labs_reported_value, reportedText(marker, reported, imperial, places)),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Which band the figure above was read against, since that is the one thing about it
            // a reader cannot work out from the numbers — the circle beside the age under the cards
            // says how it is worked out.
            Text(
                stringResource(
                    R.string.labs_age,
                    pluralStringResource(R.plurals.labs_years, newest.ageYears, newest.ageYears),
                ),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            LabSeriesChart(
                marker = marker,
                readings = readings,
                band = band,
                sex = sex,
                imperial = imperial,
                places = places,
                window = window,
                modifier = Modifier.fillMaxWidth().height(CARD_CHART_HEIGHT),
            )
            LabDietNote(marker, readings, journal)
        }
    }
}

/**
 * What the diary held in the stretch of time this marker was formed over.
 *
 * Anchored on the draw rather than on today, and offered for the newest reading that fell outside
 * its band *whenever that was* — the case this is for is a panel from three years ago, which every
 * other food rule in the app is blind to.
 *
 * The figures are printed on the card; what they cannot say — that food written down beside a
 * result is not its cause — stands behind the question mark beside their heading, as every
 * explanation in the app does. The mark is a target of its own, drawn as one, so a tap on it does
 * not also open the chart the rest of the card opens.
 */
@Composable
private fun LabDietNote(marker: LabMarker, readings: List<LabReading>, journal: List<JournalEntry>) {
    val outside = remember(readings) { readings.lastOrNull(LabReading::outside) } ?: return
    val window by produceState<LabDietWindowResult?>(null, marker, outside.entryId, journal) {
        value = withContext(Dispatchers.Default) { foodBeforeDraw(journal, outside) }
    }
    val found = window ?: return
    val days = pluralStringResource(R.plurals.count_days, found.windowDays, found.windowDays)
    val logged = pluralStringResource(R.plurals.count_days, found.daysLogged, found.daysLogged)
    val drawn = labDayText(outside.day)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val title = stringResource(R.string.labs_diet_title, days, drawn)
        if (found.enough) {
            HelpTitle(title, style = MaterialTheme.typography.labelLarge) {
                Text(stringResource(R.string.labs_diet_note))
            }
        } else {
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        if (!found.enough) {
            // A refusal, written as one. No partial average, no estimate from six days, and no
            // nudge to fill the gap in afterwards: a backfilled meal carries a midday timestamp
            // and retrospective entry runs high.
            Text(
                stringResource(R.string.labs_diet_thin, logged, days),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            stringResource(R.string.labs_diet_logged, logged),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        found.readings.forEach { reading ->
            val line = stringResource(
                R.string.labs_diet_line,
                stringResource(reading.labelRes),
                reading.meanPerDay.shown(),
            )
            val usual = reading.usualPerDay?.let { stringResource(R.string.labs_diet_usual, it.shown()) }
            val reference = reading.referenceG?.let { stringResource(R.string.labs_diet_reference, it.shown()) }
            Text(
                listOfNotNull(line, usual, reference).joinToString(" "),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The two figures under the cards: how old, and how many readings are inside. */
@Composable
private fun LabSummaryCards(readings: List<LabReading>, ageNow: Int) {
    // One height for the two, whichever of them the circle made taller.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PwnCard(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(PwnDimensions.cardPadding)) {
                // Why the bands move with age sits behind the circle beside the age it is about,
                // rather than as a card of its own at the foot of the page on every visit.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.labs_current_age), Modifier.weight(1f), color = PwnMuted)
                    HelpDot(stringResource(R.string.labs_age_note_title)) {
                        Text(stringResource(R.string.labs_age_note_body))
                    }
                }
                Text(pluralStringResource(R.plurals.labs_years, ageNow, ageNow), fontWeight = FontWeight.Bold)
            }
        }
        // Over the readings that had a band, not over all of them: an insulin drawn after breakfast
        // passes nothing, and the card beside this one is asking for a fasting sample. With none
        // graded at all — a urinalysis on its own — there is nothing to count, and "0 of 0" in
        // green would be a pass for figures nobody had judged.
        val tally = labBandTally(readings)
        if (tally.graded == 0) return@Row
        PwnCard(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(PwnDimensions.cardPadding)) {
                Text(stringResource(R.string.labs_norm_status), color = PwnMuted)
                Text(
                    pluralStringResource(R.plurals.labs_in_band, tally.inside, tally.inside, tally.graded),
                    fontWeight = FontWeight.Bold,
                    // 16sp bold on the card's surfaceContainerHigh, so this is text at 4.5:1, not a
                    // graphic at 3:1. The palette's green measures 4.70:1 there and the warning ink
                    // 5.13:1; their night halves 5.62:1 and 7.03:1 on the night card.
                    color = if (tally.inside == tally.graded) PwnGreen else PwnWarn,
                )
            }
        }
    }
}

/** Where the reading fell, in the words the exported report uses and in its verdict's colour. */
@Composable
private fun LabStatusBadge(status: LabStatus?, band: LabBand?) {
    val verdict = status.reportVerdictRes(band) ?: return
    val ink = when (status) {
        LabStatus.FAR_ABOVE, LabStatus.FAR_BELOW -> PwnWarn

        LabStatus.ABOVE, LabStatus.BELOW -> PwnBlueInk

        // Green is a pass, and only a band closed on both sides can award one. Twenty of the
        // twenty-two markers have one edge, where the verdict reads "not above" — half a
        // statement, never painted as a pass, in the neutral ink.
        else -> if (band?.twoEdged == true) PwnGreen else PwnMuted
    }
    Text(
        stringResource(verdict),
        // Bounded, because the row's other half is the marker's name: unbounded, "Far above the
        // band" beside "Не-ЛПВП холестерин" takes what it likes and the name is squeezed to a
        // column of single letters.
        Modifier.widthIn(max = BADGE_MAX_WIDTH)
            .clip(RoundedCornerShape(50))
            .background(ink)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        // The page colour on a solid pill of the ink, the pairing the chart's newest-figure chip
        // uses. The ink on a pale tint of itself reads under 4.5:1 for every verdict in daylight;
        // solid, each of the four clears it in both themes.
        color = MaterialTheme.colorScheme.background,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The reference articles, one per panel, on a page of their own.
 *
 * Nothing on it is generated and nothing on it knows what the reader's own results were. That is
 * the point of it existing as a page rather than as a card under a figure — see [LAB_LIBRARY].
 */
@Composable
private fun LabLibraryPage(onBack: () -> Unit) {
    // A Dialog rather than [PwnSheetPage], which is the shell a page wears when the navigation host
    // puts it in the slot: composed inline inside this screen it draws *within* the page rather than
    // over it, and nothing appears. The chart beside this one reaches over the page the same way.
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PwnTopBar(stringResource(R.string.lab_library_title), onBack)
                Column(
                    Modifier.fillMaxSize()
                        .panelVerticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(stringResource(R.string.lab_library_intro), color = PwnMuted)
                    LAB_LIBRARY.forEach { article ->
                        PwnCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(PwnDimensions.cardPadding),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(article.panel.titleRes),
                                    Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(stringResource(article.bodyRes))
                                Text(
                                    stringResource(article.sourceRes),
                                    color = PwnMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** The blood work's own notices, in the shade every screen shares. */
@Composable
private fun LabNoticeShade(
    notices: List<LabReading>,
    sex: String,
    imperial: Boolean,
    visible: Boolean,
    onHide: () -> Unit,
) {
    NoticeShade(visible, stringResource(R.string.labs_notices_title), onHide) {
        if (notices.isEmpty()) {
            Text(stringResource(R.string.labs_notices_empty), color = PwnMuted)
        } else {
            notices.take(LAB_NOTICE_LIMIT).forEach { LabNoticeRow(it, sex, imperial) }
        }
    }
}

/** One notice: which marker, what it read, which side of the band it fell, and when it was drawn. */
@Composable
private fun LabNoticeRow(reading: LabReading, sex: String, imperial: Boolean) {
    val band = reading.marker.bandFor(sex, reading.ageYears, reading.fasting)
    val verdict = reading.status.reportVerdictRes(band)?.let { stringResource(it) }.orEmpty()
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.padding(top = 6.dp)
                .size(NOTICE_DOT)
                .clearAndSetSemantics { }
                .background(PwnBleed, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(reading.marker.titleRes), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    R.string.labs_notice_line,
                    shownFigure(
                        reading.marker.printed(
                            reading,
                            sex,
                            imperial,
                            reading.marker.printPlaces(listOf(reading), sex, imperial),
                        ),
                    ),
                    resources.getString(reading.marker.unitLabelRes(imperial)),
                    verdict,
                ),
                color = PwnMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(labDayText(reading.day), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * One marker's whole record, filling the screen.
 *
 * A chart is read along its time axis, and the long side of the phone is the one worth spending on
 * it — so the page offers to turn sideways, and gives the orientation back on the way out. Offered
 * rather than imposed: turning every phone the moment a chart opened would override a rotation lock
 * and a phone standing upright in a holder, and the chart reads in either direction. From Android 16 a
 * large screen ignores the request, and there the offer is not made ([labChartTurns]).
 *
 * A window of its own rather than a panel: "full screen" is the point, and a panel inside the page
 * that opened it can only ever be as big as that page.
 */
@Composable
private fun LabChartPage(
    marker: LabMarker,
    readings: List<LabReading>,
    sex: String,
    imperial: Boolean,
    onClose: () -> Unit,
) {
    val newest = readings.last()
    val band = marker.bandFor(sex, newest.ageYears, newest.fasting)
    val places = remember(readings, sex, imperial) { marker.printPlaces(readings, sex, imperial) }
    val bounds = remember(readings) { labChartBounds(readings.map(LabReading::occurredAt)) }
    var window by remember(bounds) { mutableStateOf(labChartOpening(bounds)) }
    var width by remember { mutableIntStateOf(1) }
    var sideways by rememberSaveable { mutableStateOf(false) }
    val turns = labChartTurns(Build.VERSION.SDK_INT, LocalConfiguration.current.smallestScreenWidthDp)
    val title = stringResource(marker.titleRes)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SidewaysWhileAsked(sideways)
        Surface(
            // A window of its own, so it is announced by its name as it opens.
            Modifier.fillMaxSize().semantics { paneTitle = title },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (marker.unitless) {
                                stringResource(
                                    R.string.labs_notice_line_bare,
                                    shownFigure(marker.printed(newest, sex, imperial, places)),
                                    labDayText(newest.day),
                                )
                            } else {
                                stringResource(
                                    R.string.labs_notice_line,
                                    shownFigure(marker.printed(newest, sex, imperial, places)),
                                    LocalResources.current.getString(marker.unitLabelRes(imperial)),
                                    labDayText(newest.day),
                                )
                            },
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (turns) {
                        // Filled while it holds the phone sideways, outlined while it does not.
                        OutlinedIconToggleButton(checked = sideways, onCheckedChange = { sideways = it }) {
                            Icon(Icons.Outlined.ScreenRotation, stringResource(R.string.m3_labs_chart_sideways))
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                    ) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                    }
                }
                LabSeriesChart(
                    marker = marker,
                    readings = readings,
                    band = band,
                    sex = sex,
                    imperial = imperial,
                    places = places,
                    window = window,
                    detailed = true,
                    modifier = Modifier.weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { width = it.width.coerceAtLeast(1) }
                        .pointerInput(bounds, width) {
                            // Measured against the whole page rather than the plot inside it: the
                            // figures up the side take a few per cent of the width, so the point
                            // held under a pinch drifts by that much. Naming the plot out here
                            // would mean measuring the axis twice, once to draw and once to touch.
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val focus = centroid.x / width
                                val moved = -(pan.x / width) * window.spanMs
                                val zoomed = labChartZoom(bounds, window, zoom, focus)
                                window = labChartPan(bounds, zoomed, moved.toLong())
                            }
                        },
                )
                LabChartControls(bounds, window) { window = it }
            }
        }
    }
}

/**
 * The chart's window moved by buttons as well as by fingers: earlier, further out, nearer in, later.
 *
 * A pinch takes two fingers and a pan a drag, and neither is open to a keyboard, a switch or a screen
 * reader — the page opens on the latest year, so without these the readings before it could not be
 * reached at all. Each button is off where its move would change nothing. Laid out left to right in
 * every language, because the time axis above them is drawn that way.
 */
@Composable
private fun LabChartControls(bounds: LabChartBounds, window: LabChartWindow, onWindow: (LabChartWindow) -> Unit) {
    val step = window.spanMs / 2L
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ChartMove(
                labChartPan(bounds, window, -step),
                window,
                Icons.Outlined.ChevronLeft,
                R.string.m3_labs_chart_earlier,
                onWindow,
            )
            ChartMove(
                labChartZoom(bounds, window, 1f / CHART_ZOOM_STEP, CHART_ZOOM_FOCUS),
                window,
                Icons.Outlined.ZoomOut,
                R.string.a11y_zoom_out,
                onWindow,
            )
            ChartMove(
                labChartZoom(bounds, window, CHART_ZOOM_STEP, CHART_ZOOM_FOCUS),
                window,
                Icons.Outlined.ZoomIn,
                R.string.a11y_zoom_in,
                onWindow,
            )
            ChartMove(
                labChartPan(bounds, window, step),
                window,
                Icons.Outlined.ChevronRight,
                R.string.m3_labs_chart_later,
                onWindow,
            )
        }
    }
}

/** One of [LabChartControls]' buttons: the window it would move to, off where that is where it is. */
@Composable
private fun ChartMove(
    next: LabChartWindow,
    window: LabChartWindow,
    icon: ImageVector,
    labelRes: Int,
    onWindow: (LabChartWindow) -> Unit,
) {
    IconButton(onClick = {
        onWindow(next)
    }, enabled = next != window, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
        Icon(icon, stringResource(labelRes))
    }
}

/** Sideways while the person has asked for it, and whatever the phone was doing before, afterwards. */
@Composable
private fun SidewaysWhileAsked(sideways: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, sideways) {
        if (activity != null && sideways) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            // Not while the turn it asked for is being made: the activity is torn down and rebuilt
            // on the way into landscape, and handing the orientation back in the middle of that is
            // how a page ends up flapping between the two.
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

/** A composable is handed a context, not an activity; this is the walk between them. */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The app's band for a marker as one phrase, on the scale the profile reads in.
 *
 * A band written only from the far edge still has an edge to print: glucose after food is read
 * against the random-plasma threshold alone, and "up to 11" is what that card means. The figures
 * are the last ones inside, not the edges themselves — the edge is the first value the card colours
 * red, and "up to 5.7" over a red 5.7% of HbA1c would be the range contradicting its own figure.
 */
@Composable
private fun bandText(marker: LabMarker, band: LabBand?, imperial: Boolean, places: Int): String {
    val (printedBottom, printedTop) = band?.let { marker.printedEdges(it, imperial, places) } ?: (null to null)
    val bottom = printedBottom?.let { shownFigure(it) }
    val top = printedTop?.let { shownFigure(it) }
    return when {
        bottom != null && top != null -> stringResource(R.string.labs_norm_range, bottom, top)

        top != null -> stringResource(R.string.labs_norm_up_to, top)

        bottom != null -> stringResource(R.string.labs_norm_from, bottom)

        // Insulin drawn after breakfast has no band at all, and the card should say that rather
        // than print a threshold the sample cannot be held to.
        else -> stringResource(R.string.labs_status_needs_fasting)
    }
}

/** The laboratory's printed range as one phrase, on the same scale and never coarser than it was typed. */
@Composable
private fun reportedText(marker: LabMarker, reported: ReportedRange, imperial: Boolean, places: Int): String {
    val low = reported.low?.let { shownFigure(marker.printed(it, imperial, places)) }
    val high = reported.high?.let { shownFigure(marker.printed(it, imperial, places)) }
    return when {
        low != null && high != null -> stringResource(R.string.labs_norm_range, low, high)
        high != null -> stringResource(R.string.labs_norm_up_to, high)
        low != null -> stringResource(R.string.labs_norm_from, low)
        else -> stringResource(R.string.vitals_no_band)
    }
}

/**
 * The files attached to the panels already saved.
 *
 * Read-only. Removing one belongs with the panel it hangs on, in the journal row that owns it —
 * this is the shelf, not the editor.
 */
@Composable
private fun SavedLabSheets(sheets: List<Pair<String, MedicalAttachment>>) {
    val context = LocalContext.current
    if (sheets.isEmpty()) return
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.attachments_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            sheets.forEach { (day, file) ->
                val uri = file.uri
                Row(
                    // The row is as tall as its 20dp paper-clip, which is 42% of the 48dp minimum,
                    // and the 10dp between rows is dead space rather than target. Nothing moves; the
                    // container is simply given the height a finger needs. A button to a screen
                    // reader, since it hands the file to another app.
                    Modifier.fillMaxWidth()
                        .heightIn(min = PwnDimensions.minimumTarget)
                        .clickable(role = Role.Button) { context.openAttachment(uri) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        tint = PwnMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        // Two lines before it gives up: the name is the one thing telling two sheets
                        // apart, and at a large text size one line of it says almost nothing.
                        Text(
                            labFileName(uri),
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
                    Text(labDayText(day), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** How far one press of a zoom button moves the window in or out, and the point it holds: the middle. */
private const val CHART_ZOOM_STEP = 1.5f
private const val CHART_ZOOM_FOCUS = 0.5f

/** How wide a badge may grow beside the marker's name. */
private val BADGE_MAX_WIDTH = 170.dp

/** Room for the card's chart: about a third of the card. */
private val CARD_CHART_HEIGHT = 150.dp
