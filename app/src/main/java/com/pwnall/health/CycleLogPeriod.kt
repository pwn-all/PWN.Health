package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the setup dialog offers as a period length before anything has been recorded. */
private const val TYPICAL_PERIOD_DAYS = 5

/**
 * Where the end of a period left open since [start] is put before she corrects it: as long as her
 * closed periods usually run ([usualDays]), or [TYPICAL_PERIOD_DAYS] before there are any, and
 * never after [today].
 *
 * Not today: the close is asked for because the period has been open longer than one lasts, which
 * is the case where today is the one day it did not end on.
 */
internal fun closingEndGuess(start: LocalDate, usualDays: Int?, today: LocalDate): LocalDate =
    minOf(start.plusDays((usualDays ?: TYPICAL_PERIOD_DAYS) - 1L), today)

/**
 * Files a period remembered afterwards — its start, and its end where she gave one — as one write,
 * from the cycle screen's own page and from the home screen's dialog alike. Answers whether the
 * start was taken.
 *
 * One write for the pair: a start whose end was lost to a process that died between the two stands
 * on the record as a period running ever since. And a start the record turns down files no end
 * either: an end on its own closes whatever start stands before it, another period's. Not cut off
 * by a page that closes meanwhile.
 */
internal suspend fun StepsDb.logRememberedPeriod(
    resources: Resources,
    start: LocalDate,
    end: LocalDate?,
): Boolean = withContext(NonCancellable) {
    val filed = withTransaction {
        val started = logCycleStart(cycleEntry(resources, start.toString()))
        if (started) end?.let { logCycleEnd(cycleEndEntry(resources, it.toString())) }
        started
    }
    // Neither write redraws the widgets from inside a transaction.
    if (filed) refreshWidgetsAfterWrite()
    filed
}

/**
 * Takes one question's answer off a day: the rows of [section] on [day], which are what
 * [StepsDb.logCycleLog] would replace, each deleted for its tombstone, in one transaction. Answers
 * whether there was anything to take.
 */
internal suspend fun StepsDb.clearCycleSection(section: CycleSection, day: String): Boolean {
    val cleared = withTransaction {
        val rows = dao().journalOnNow(day).filter { it.cycleLog() in section.logs }
        rows.forEach { deleteJournalEvent(it.id) }
        rows.isNotEmpty()
    }
    if (cleared) refreshWidgetsAfterWrite()
    return cleared
}

/**
 * The one question that has to be asked before this screen can say anything: when did the last
 * period start, and how long did it run.
 *
 * Asked on the first open rather than after a month of use, because a woman installing a health app
 * knows the date today and is guessing by October. It writes an ordinary start and an ordinary end
 * on the days she names — nothing about the row says it was entered late, because nothing about it
 * is different.
 *
 * A length that would run past today is written as a start alone: the period is still going, and
 * the screen's own "Period finished" button is what closes it on the day it actually finishes.
 * Guessing an end in the future would publish a forecast to Health Connect as a measurement.
 */
@Composable
internal fun FirstCycleDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate?) -> Unit) {
    val today = rememberToday()
    val dayMonth = rememberDayMonth()
    // A month back covers the current cycle and the one before it, which is as far as a date
    // recalled rather than recorded is worth taking. Saveable, as the home screen keeps the dialog
    // itself, so turning the phone on its side keeps the day she picked instead of the default.
    var start by rememberSaveable { mutableStateOf(today.minusDays(DEFAULT_CYCLE_DAYS.toLong() / 2)) }
    var length by rememberSaveable { mutableIntStateOf(TYPICAL_PERIOD_DAYS) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val end = start.plusDays(length - 1L).takeIf { !it.isAfter(today) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_first_title)) },
        text = {
            Column(
                Modifier.panelVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.cycle_first_body))
                OutlinedButton({ picking = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cycle_first_started, start.format(dayMonth)))
                }
                Text(
                    stringResource(R.string.cycle_first_length),
                    style = MaterialTheme.typography.labelMedium,
                    color = PwnMuted,
                )
                RulerPicker(
                    values = PLAUSIBLE_PERIOD_DAYS.toList(),
                    value = length,
                    onValue = { length = it },
                    label = { it.toString() },
                )
                Text(
                    if (end == null) {
                        stringResource(R.string.cycle_first_still_running)
                    } else {
                        stringResource(R.string.cycle_first_range, start.format(dayMonth), end.format(dayMonth))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    if (picking) {
        PastDateDialog(
            initial = start,
            onDismiss = { picking = false },
            onPick = {
                start = it
                picking = false
            },
        )
    }
}

/**
 * The one question that has to be answered before the cycle screen can say anything, as a page.
 *
 * A page rather than a dialog: on a phone with nothing recorded this is not an interruption to the
 * screen, it *is* the screen — behind a dialog there would be a headline with no day, a plot with
 * no line and a week strip with nothing on it, which is four blanks each needing its own
 * explanation. One question with room to answer it instead.
 *
 * A length that would run past today is filed as a start alone: the period is still going, and the
 * record button is what closes it on the day it actually finishes. Guessing an end in the future
 * would publish a forecast to Health Connect as a measurement.
 *
 * The same two questions file a period that was never logged, on a record that already holds
 * others ([past], opened from the calendar): the record button only ever writes today, so without
 * this page one missed start would stay missed. There [fits] is what keeps the days she names off a
 * period already on file ([pastPeriodFits]) — the page says so and holds its Save until they are
 * clear.
 */
@Composable
internal fun FirstCyclePage(
    modifier: Modifier,
    saving: Boolean,
    past: Boolean,
    fits: (LocalDate, LocalDate?) -> Boolean,
    onBack: () -> Unit,
    onConfirm: (LocalDate, LocalDate?) -> Unit,
) {
    val today = rememberToday()
    val dayMonth = rememberDayMonthLong()
    // Saveable, as the dialog's are, so a rotation keeps the days she has moved.
    var start by rememberSaveable { mutableStateOf(today.minusDays(DEFAULT_CYCLE_DAYS.toLong() / 2)) }
    var length by rememberSaveable { mutableIntStateOf(TYPICAL_PERIOD_DAYS) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val end = start.plusDays(length - 1L).takeIf { !it.isAfter(today) }
    val free = fits(start, end)

    // The cycle screen paints its own violet under the status bar, so [MainActivity] hands it the
    // bottom inset only. A page of its own on plain white has to put the top one back, or its bar
    // sits under the clock.
    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(if (past) R.string.cycle_past_title else R.string.cycle_first_title), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // The paragraph is the first ask's: "two answers and the cycle screen starts working"
            // is not true of a screen that has been working for months.
            if (!past) {
                Text(
                    stringResource(R.string.cycle_first_body),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PwnMuted,
                )
                Spacer(Modifier.height(28.dp))
            }

            // Two questions, each named over its own control, rather than a button reading
            // "Started 3 Sep" and a ruler an inch tall: those say nothing of which question they
            // answer, and the ruler is too small to aim at.
            Text(
                stringResource(R.string.cycle_first_started_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(CycleCard, RoundedCornerShape(CYCLE_CTA_RADIUS))
                    .clickable(role = Role.Button) { picking = true }
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        start.format(dayMonth),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = CycleAccent,
                    )
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = CycleAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.cycle_first_length),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.count_days, length, length),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = CycleAccent,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            RulerPicker(
                values = PLAUSIBLE_PERIOD_DAYS.toList(),
                value = length,
                onValue = { length = it },
                label = { it.toString() },
                accent = CycleAccent,
                height = 104.dp,
                spacing = 16.dp,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    !free -> stringResource(R.string.cycle_past_taken)
                    end == null -> stringResource(R.string.cycle_first_still_running)
                    else -> stringResource(R.string.cycle_first_range, start.format(dayMonth), end.format(dayMonth))
                },
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (free) PwnMuted else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            DataNoteLink(R.string.cycle_health_connect_note)
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Button(
                onClick = { onConfirm(start, end) },
                enabled = !saving && free,
                modifier = Modifier.fillMaxWidth().heightIn(min = CYCLE_CTA_HEIGHT),
                shape = RoundedCornerShape(CYCLE_CTA_RADIUS),
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
            }
        }
    }

    if (picking) {
        PastDateDialog(
            initial = start,
            onDismiss = { picking = false },
            onPick = {
                start = it
                picking = false
            },
        )
    }
}
