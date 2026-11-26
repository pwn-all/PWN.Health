package com.pwnall.health

import androidx.annotation.StringRes
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Material's date picker in a dialog, answered as the calendar day it shows. The picker works in
 * UTC — a day is the midnight that starts it, see [toPickerMillis] — so the date it shows is the
 * date it means, and [onPick] gets it as a [LocalDate]. The [confirm] button lights only once a day
 * is chosen. Each page sets up [state] with its own years and selectable days.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalDateDialog(
    state: DatePickerState,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    @StringRes confirm: Int = R.string.common_ok,
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** This day as the picker's own figure for it: its midnight in UTC, in milliseconds. */
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
