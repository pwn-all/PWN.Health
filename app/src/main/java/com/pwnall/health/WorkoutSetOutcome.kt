package com.pwnall.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The question a stopped set is closed with, and the correction when the answer is no.
 *
 * The correction stays in this card rather than opening a window over the session: the session is
 * itself a section of the open exercise form, and a sub-form inside a form stays in its scroll. The
 * card names itself as a pane, so a screen reader says the question when it appears under the set
 * instead of leaving it to be found; keyboard focus goes to its first answer for the same reason.
 */
@Composable
internal fun WorkoutSetOutcome(index: Int, reps: String, onConfirm: (String) -> Unit) {
    var correcting by rememberSaveable(index) { mutableStateOf(false) }
    var actual by rememberSaveable(index) { mutableStateOf("") }
    val question = stringResource(R.string.workout_did_it)
    PwnCard(Modifier.fillMaxWidth().semantics { paneTitle = question }, containerColor = PwnIndigoSoft) {
        Column(
            Modifier.padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (correcting) stringResource(R.string.workout_actual_reps) else question,
                style = MaterialTheme.typography.titleLarge,
            )
            if (correcting) {
                RepsCorrection(
                    actual = actual,
                    onActual = { actual = it },
                    onSave = { onConfirm(actual.toInt().toString()) },
                    onCancel = { correcting = false },
                )
            } else {
                OutcomeAnswers(onYes = { onConfirm(reps) }, onNo = { correcting = true })
            }
        }
    }
}

@Composable
private fun OutcomeAnswers(onYes: () -> Unit, onNo: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { yes.requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onYes, modifier = Modifier.weight(1f).focusRequester(yes)) {
            Text(stringResource(R.string.answer_yes))
        }
        // An outline edge rather than Material's default tonal hairline, which all but vanishes
        // on this card's ground.
        OutlinedButton(
            onClick = onNo,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(stringResource(R.string.answer_no))
        }
    }
}

/**
 * The reps actually done, typed where the question was. The field takes the cursor and the number
 * keyboard as it appears, and the keyboard's own Done key saves once there is a number to save.
 */
@Composable
private fun RepsCorrection(actual: String, onActual: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val valid = actual.toIntOrNull() != null
    OutlinedTextField(
        value = actual,
        onValueChange = { onActual(it.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(R.string.editor_reps)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (valid) onSave() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(field),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        TextButton(onClick = onSave, enabled = valid) { Text(stringResource(R.string.common_save)) }
    }
}
