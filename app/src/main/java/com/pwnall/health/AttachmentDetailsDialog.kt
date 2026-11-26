package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * What a file is, when it was taken and what it shows, asked in place under the file's own row.
 *
 * A section of the open course or visit form rather than a dialog on top of it with a menu popping
 * out of the dialog in turn: a section stays in that form's scroll, the row being described stays
 * in view above the questions, and Save or Cancel folds them away again.
 */
@Composable
internal fun AttachmentDetailsForm(
    file: MedicalAttachment,
    onDismiss: () -> Unit,
    onSave: (MedicalAttachment) -> Unit,
) {
    var kindName by rememberSaveable(file.uri) { mutableStateOf(file.kind.name) }
    var description by rememberSaveable(file.uri) { mutableStateOf(file.description) }
    var day by rememberSaveable(file.uri) { mutableStateOf(file.studyDay) }
    var pickingDay by rememberSaveable(file.uri) { mutableStateOf(false) }
    val kind = AttachmentKind.valueOf(kindName)
    val picked = day.toLocalDateOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Why a file's name does not settle its type is worth reading once, so it waits behind the
        // dot beside the question rather than standing over the fields as a paragraph.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AttachmentKindField(kind, onKind = { kindName = it.name }, modifier = Modifier.weight(1f))
            HelpDot(title = stringResource(R.string.attachment_details)) {
                Text(stringResource(R.string.attachment_details_hint))
            }
        }
        OutlinedTextField(
            value = description,
            // Held to the length a place's name is: the description is a cell of the doctor's sheet.
            onValueChange = { description = it.take(PLACE_MAX_CHARS) },
            label = { Text(stringResource(R.string.attachment_description)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Picked, not typed. A field that took `2026-09-27` as text and nothing else would refuse the
        // same date in the digits an Arabic or a Hindi keyboard types, and make everyone else spell a
        // date out with hyphens on a keyboard of letters. The calendar is
        // the one the rest of the app asks for a day with, and it reads any locale's digits itself.
        FieldButton(
            label = stringResource(R.string.attachment_study_day),
            // A day a file written elsewhere gave in some other shape is shown, and kept, as it stands.
            value = picked?.formatLongDay() ?: day.ifBlank { stringResource(R.string.history_no_date) },
            icon = Icons.Outlined.CalendarMonth,
            onClick = { pickingDay = true },
        )
        // The date is optional, and a calendar has no way to answer "none".
        if (day.isNotBlank()) {
            TextButton(onClick = { day = "" }) { Text(stringResource(R.string.attachment_study_day_clear)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            TextButton(
                onClick = { onSave(file.copy(kind = kind, description = description.trim(), studyDay = day)) },
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
    if (pickingDay) {
        StudyDayDialog(picked, onDismiss = { pickingDay = false }) {
            day = it.toString()
            pickingDay = false
        }
    }
}

/**
 * The day of an examination, which is as likely to be years back as last week: the medical
 * history's own picker, with its years ([historyYearRange]) and its rule that nothing in a history
 * is still to come ([historyDayAllowed]). Borrowing the years alone would leave every later day of
 * this year open, and an X-ray dated next month would be saved and printed on the doctor's sheet.
 * With no day chosen yet it opens on today.
 */
@Composable
private fun StudyDayDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = rememberToday()
    HistoryDayDialog(initial ?: today, allowed = { historyDayAllowed(it, today) }, onDismiss, onPick)
}

/**
 * The kind as a labelled field with its list hanging off it, the shape [PwnTickField] uses.
 *
 * A bare button showing "CT" would be heard as "CT, button": no name for the question, no word that a
 * list opens, and nothing in the list saying which answer is the current one. The text field
 * brings the label; the menu anchor brings expanded and collapsed; the tick and the selected state
 * mark the answer already given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentKindField(kind: AttachmentKind, onKind: (AttachmentKind) -> Unit, modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = choosing, onExpandedChange = { choosing = it }, modifier = modifier) {
        OutlinedTextField(
            value = stringResource(kind.titleRes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.m3_editor_attachment_kind)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = choosing) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            AttachmentKind.entries.forEach { option ->
                val current = option == kind
                DropdownMenuItem(
                    text = { Text(stringResource(option.titleRes)) },
                    onClick = {
                        onKind(option)
                        choosing = false
                    },
                    // At the end, as the app's own ticked rows carry it, so every word in the list
                    // starts at the same margin whichever of them is the current one.
                    trailingIcon = if (current) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = current },
                )
            }
        }
    }
}
