package com.pwnall.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The records of [codes] as the journal held them before this screen, read once, or null while the
 * read is under way — a null shows no badge rather than a wrong one. [excludeId] is the row being
 * edited, when it is one.
 */
@Composable
internal fun rememberRecordsBefore(codes: List<String>, excludeId: Long? = null): Map<String, ExerciseRecords>? {
    val context = LocalContext.current
    var records by remember(codes, excludeId) { mutableStateOf<Map<String, ExerciseRecords>?>(null) }
    LaunchedEffect(codes, excludeId) {
        records = recordsByExercise(StepsDb.get(context).dao(), codes, excludeId)
    }
    return records
}

/** A weight in kilograms as this profile reads it: "102.5 kg" or "226 lb". */
@Composable
internal fun recordWeight(kg: Double, imperial: Boolean, whole: Boolean = false): String {
    val shown = if (imperial) imperialWeight(kg) else kg
    val figure = if (whole) shown.roundToInt().toString() else shown.roundToHundredth().shown()
    return "$figure ${stringResource(if (imperial) R.string.unit_lb else R.string.unit_kg)}"
}

/** The word a set wears when it holds a record it set this session. */
@Composable
internal fun RecordBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.strength_record_badge),
        modifier.background(
            PwnGreenSoft,
            RoundedCornerShape(PwnCorners.small),
        ).padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = PwnGreen,
    )
}

/**
 * The records a session broke, one line each: the exercise and which record on one side, the new
 * figure and the old one on the other. Nothing is drawn when nothing was broken — and nothing is ever
 * broken by an exercise's first session, which has no record to beat.
 */
@Composable
internal fun NewRecordsCard(broken: List<BrokenRecord>, imperial: Boolean, modifier: Modifier = Modifier) {
    if (broken.isEmpty()) return
    PwnCard(modifier.fillMaxWidth(), containerColor = PwnGreenSoft) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.strength_new_records),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PwnGreen,
            )
            broken.forEach { record ->
                val spec = ACTIVITY_CATALOG.firstOrNull { it.code == record.code }
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            spec?.let { stringResource(it.titleRes) } ?: record.code,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(record.kind.labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.strength_record_was,
                            recordFigure(record.kind, record.value, imperial),
                            recordFigure(record.kind, record.was, imperial),
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (broken.any { it.kind == RecordKind.E1RM }) {
                Text(
                    stringResource(R.string.strength_e1rm_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
        }
    }
}

/**
 * The exercise's best under the form's "last time": the heaviest set and the one-rep estimate it
 * gives, or the most reps for an exercise done without added weight. Nothing before a first set.
 */
@Composable
internal fun ExerciseRecordLine(code: String, imperial: Boolean, excludeId: Long?) {
    val records = rememberRecordsBefore(listOf(code), excludeId)?.get(code) ?: return
    val text = records.bestE1rm?.let { best ->
        val e1rm = records.e1rmKg ?: return@let null
        stringResource(
            R.string.strength_record_line,
            "${recordWeight(best.loadKg, imperial)} × ${best.reps}",
            recordWeight(e1rm, imperial, whole = true),
        )
    } ?: records.mostReps?.let {
        stringResource(
            R.string.strength_record_line_reps,
            pluralStringResource(R.plurals.count_repetitions, it.reps, it.reps),
        )
    } ?: return
    Spacer(Modifier.height(4.dp))
    Text(text, color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
}

private val RecordKind.labelRes: Int
    get() = when (this) {
        RecordKind.E1RM -> R.string.strength_record_e1rm
        RecordKind.HEAVIEST -> R.string.strength_record_heaviest
        RecordKind.REPS -> R.string.strength_record_reps
        RecordKind.VOLUME -> R.string.strength_record_volume
    }

@Composable
private fun recordFigure(kind: RecordKind, value: Double, imperial: Boolean): String = when (kind) {
    RecordKind.REPS -> value.roundToInt().toString()
    RecordKind.E1RM, RecordKind.VOLUME -> recordWeight(value, imperial, whole = true)
    RecordKind.HEAVIEST -> recordWeight(value, imperial)
}
