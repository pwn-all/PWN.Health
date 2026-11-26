package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate

// What the symptom chat (SymptomChatScreen.kt) draws from an entry beside its bubbles: the day list's
// line, the card of what the journal held, and the safety lines.

/**
 * The day list's line under a symptom row's title, in the language showing now. Every list that
 * prints it prints the title above it and leaves a blank line out, so a payload it cannot read
 * gives no line rather than the title a second time.
 */
@Composable
internal fun JournalEntry.symptomDetails(): String {
    val resources = LocalResources.current
    val report = remember(measurementPayloadJson) { decodeSymptomPayload(measurementPayloadJson) }
    return symptomSummaryLine(
        report,
        // The title as the list prints it, the topic read again in the language showing: the name is
        // then never printed twice, in two languages or in one.
        shownTitle(resources),
        name = { resources.getString(it.labelRes) },
        severity = { resources.getString(it.labelRes) },
    )
}

/**
 * What the journal held when the symptom was told: figures only, each with the moment it is counted
 * from, and what they are — a record, not a cause — behind the "?" beside the title.
 */
@Composable
internal fun SymptomContextCard(
    context: SymptomContext,
    symptom: Symptom,
    day: LocalDate,
    today: LocalDate,
    imperial: Boolean,
) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // By the day the entry is filed under, not by whether its figures were counted from a moment:
    // an entry told last Tuesday about last Tuesday, opened again today, was not logged today.
    val title = if (day == today) {
        stringResource(R.string.symptom_context_today)
    } else {
        val date = remember(day, locale) { painDayText("dMMM", locale, day) }
        stringResource(R.string.symptom_context_day, date)
    }
    val lines = context.present(symptom.facts).map { resources.symptomFactLine(it, context, imperial) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HelpTitle(title) { Text(stringResource(R.string.symptom_context_help)) }
            if (lines.isEmpty()) {
                Text(stringResource(R.string.symptom_context_none), color = PwnMuted)
            } else {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** "35 min", "2 h", "2 h 10 min": the pain page's own durations. */
private fun Resources.symptomSpan(minutes: Int): String = when {
    minutes < 60 -> getString(R.string.pain_minutes, minutes)
    minutes % 60 == 0 -> getString(R.string.pain_hours, minutes / 60)
    else -> getString(R.string.pain_hours_minutes, minutes / 60, minutes % 60)
}

/**
 * One figure's line, printed the way its own screen prints it: water through [waterOfTargetText],
 * a temperature through [readingText] in the profile's scale, a workout by the catalogue's name.
 * Only called for a fact [SymptomContext.present] found, so every figure it reads is there.
 */
private fun Resources.symptomFactLine(fact: SymptomFact, context: SymptomContext, imperial: Boolean): String =
    when (fact) {
        // Only ever on a card told the same day: an earlier day's has no moment to count back from.
        SymptomFact.LAST_MEAL -> getString(
            R.string.symptom_fact_meal,
            context.mealTitle,
            context.mealKcal.orNought().toLong().formatted(),
            symptomSpan(context.mealMinutesBefore.orNought()),
        )

        SymptomFact.FOOD -> getString(
            R.string.symptom_fact_food,
            context.foodKcal.orNought().toLong().formatted(),
            context.foodTargetKcal.orNought().toLong().formatted(),
        )

        SymptomFact.STEPS -> getString(R.string.symptom_fact_steps, context.steps.orNought().formatted())

        SymptomFact.WORKOUT -> workoutLine(context)

        SymptomFact.WATER -> getString(
            R.string.symptom_fact_water,
            waterOfTargetText(this, context.waterMl.orNought(), context.waterTargetMl.orNought(), imperial),
        )

        SymptomFact.ALCOHOL -> getString(
            R.string.symptom_fact_alcohol,
            context.standardDrinks.orNought().pretty(configuration.locales[0]),
        )

        SymptomFact.CAFFEINE -> getString(
            R.string.symptom_fact_caffeine,
            getString(R.string.caffeine_entry_value, context.caffeineMg.orNought()),
        )

        SymptomFact.SLEEP -> context.sleepMinutes.orNought().let {
            getString(R.string.symptom_fact_sleep, getString(R.string.measurement_sleep_value, it / 60, it % 60))
        }

        SymptomFact.BLOOD_PRESSURE -> getString(
            R.string.symptom_fact_pressure,
            "${context.systolic}/${context.diastolic}",
            symptomSpan(context.pressureBefore.orNought()),
        )

        SymptomFact.PULSE -> getString(
            R.string.symptom_fact_pulse,
            context.pulseBpm.toString(),
            symptomSpan(context.pulseBefore.orNought()),
        )

        SymptomFact.TEMPERATURE -> getString(
            R.string.symptom_fact_temperature,
            readingText(
                Vital.BODY_TEMPERATURE,
                context.temperatureC.orNought(),
                null,
                imperial,
                configuration.locales[0],
            ),
            getString(temperatureUnitRes(imperial)),
            symptomSpan(context.temperatureBefore.orNought()),
        )

        SymptomFact.STOOLS -> getString(
            R.string.symptom_fact_stools,
            context.stools.orNought().toString(),
            context.looseStools.orNought().toString(),
        )
    }

/**
 * A figure [SymptomContext.present] has already found, read without a branch of its own at every
 * use: the line it is printed in never reaches the nought.
 */
private fun Int?.orNought(): Int = this ?: 0

private fun Long?.orNought(): Long = this ?: 0L

private fun Double?.orNought(): Double = this ?: 0.0

/**
 * The last workout and when it ended, the day's last one on an earlier day, or that there was none.
 * Its length follows the name only when it has one: a strength set or a superset partner carries no
 * minutes, and a dash in their place would read as a figure that failed to load.
 */
private fun Resources.workoutLine(context: SymptomContext): String {
    val name = ACTIVITY_CATALOG.firstOrNull { it.code == context.workoutCode }?.let { getString(it.titleRes) }
        ?: context.workoutTitle
    val named = name?.let {
        listOfNotNull(it, context.workoutMinutes?.let(::symptomSpan)).joinToString(getString(R.string.list_comma))
    }
    return when {
        named == null -> getString(R.string.symptom_fact_no_workout)

        context.workoutEndedBefore != null ->
            getString(R.string.symptom_fact_workout, named, symptomSpan(context.workoutEndedBefore))

        else -> getString(R.string.symptom_fact_workout_day, named)
    }
}

/**
 * A line an answer calls for, where the answer is: the ones that send the person to a doctor or to
 * emergency services on the error colour, as the pain page's safety line is, the kind line on a plain
 * card. Where the line comes from is behind the "?".
 */
@Composable
internal fun SymptomAlertLine(alert: SymptomAlert, sourceRes: Int, text: String? = null) {
    SymptomSafetyCard(text ?: stringResource(alert.textRes), urgent = alert.urgency != SymptomUrgency.SELF_CARE) {
        if (alert == SymptomAlert.SELF_INDUCED) Text(stringResource(R.string.symptom_self_induced_help))
        Text(stringResource(sourceRes))
    }
}

/**
 * The card a safety line stands on: a warning on the error colour when [urgent], a plain card with
 * an "i" otherwise, and [content] — where the line comes from — behind the "?" at its end.
 */
@Composable
internal fun SymptomSafetyCard(text: String, urgent: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val container = if (urgent) MaterialTheme.colorScheme.errorContainer else PwnSurfaceElevated
    val ink = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Surface(shape = MaterialTheme.shapes.large, color = container) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (urgent) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null, tint = ink)
            Text(
                text,
                Modifier.weight(1f).padding(vertical = 10.dp),
                color = ink,
                style = MaterialTheme.typography.bodyMedium,
            )
            HelpDot(stringResource(R.string.symptom_sources), content)
        }
    }
}
