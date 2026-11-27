package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The two widgets about a course of something: the next dose of the day, which one tap marks taken,
 * and the day of the cycle. Both name what they show — a medicine, a period — on a home screen that
 * sits outside the app lock; §10 of the privacy policy says so, and the cycle widget's provider is
 * switched off for a profile without a cycle (applyHabitWidgetVisibility).
 */

// --- the next dose ------------------------------------------------------------------------------

private val DOSE_MEDICATION_KEY = ActionParameters.Key<Long>("doseMedication")
private val DOSE_SLOT_KEY = ActionParameters.Key<String>("doseSlot")
private val DOSE_DAY_KEY = ActionParameters.Key<String>("doseDay")

/** The dose to take next: the earliest still unmarked, so one running late comes before one to come. */
internal fun nextDose(doses: List<PlannedDose>): PlannedDose? =
    doses.filterNot(PlannedDose::taken).minByOrNull(PlannedDose::minuteOfDay)

/**
 * The day a tapped Taken marks: the one its face was drawn for, [drawnFor], while that is [today] or
 * the day before. A face stands until its next redraw — up to half an hour past midnight — and a day
 * read from the clock at the tap would mark today's dose of the same slot instead of the one the face
 * showed: the late evening tablet would stay missed, and the next evening's reminder would never ring. Null
 * for a face older than that, one ahead of a clock since set back, or one that names no day: nothing
 * is marked, and the redraw that follows shows what there is to mark.
 */
internal fun tappedDoseDay(drawnFor: String?, today: LocalDate): LocalDate? =
    drawnFor.toLocalDateOrNull()?.takeIf { it == today || it == today.minusDays(1) }

/** What the dose widget has to say today. */
internal sealed interface DoseFace {
    /** No course asks for anything today. */
    data object NoCourses : DoseFace

    /** Every dose of the day is marked. */
    data object AllTaken : DoseFace

    /** The one to take next. */
    data class Next(val dose: PlannedDose) : DoseFace
}

internal fun doseFace(doses: List<PlannedDose>): DoseFace = when {
    doses.isEmpty() -> DoseFace.NoCourses
    else -> nextDose(doses)?.let(DoseFace::Next) ?: DoseFace.AllTaken
}

/** The day's doses as the medicine tab plans them: every course active today, marked from the journal. */
internal suspend fun todaysDoses(db: StepsDb, today: LocalDate): List<PlannedDose> {
    val journal = db.dao().journalOnNow(today.toString())
    return plannedDoses(db.dao().allMedications(), journal, today, wakeMinuteOf(journal, today, ZoneId.systemDefault()))
}

class DoseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StepsDb.get(context)
        // Read inside the content ([provideReading]), the day with the doses read for it: a Taken
        // tapped while Glance still held the face would leave the same dose and its button standing.
        provideReading(
            read = { LocalDate.now().let { today -> doseFace(todaysDoses(db, today)) to today } },
        ) { (face, today) ->
            GlanceTheme {
                Row(
                    GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(WIDGET_CORNER)
                        .padding(WIDGET_FRAME)
                        .clickable(actionStartActivity(openAppIntent(context, WidgetRoute.MEDICINE))),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Column(GlanceModifier.defaultWeight()) { DoseLines(context.resources, face) }
                    if (face is DoseFace.Next) TakenButton(context, face.dose, today)
                }
            }
        }
    }
}

/**
 * The line under the medicine's name: when, then how much. The time stands first because the line
 * is one line and is cut at its end: the dose is the person's own words, as long as they wrote
 * them, and behind them the time would be the first thing lost — on a widget there to say when. A
 * course with no dose written shows the time alone, with no separator left hanging after it.
 */
internal fun doseCaption(resources: Resources, dose: String, time: String): String =
    if (dose.isBlank()) time else resources.getString(R.string.widget_dose_at, dose, time)

/**
 * When the dose is due, said as the tab, the reminder and the home page say it. A dose on waking is
 * "On waking": its minute is the morning's wake time, or eight o'clock standing in for one nobody
 * recorded, and printed as "08:00" it would read as if the course named it.
 */
internal fun doseTimeText(resources: Resources, dose: PlannedDose): String =
    if (dose.onWaking) resources.getString(R.string.med_dose_on_waking) else clockText(dose.minuteOfDay)

/** The two lines: what to take next and when, or that there is nothing to. */
@Composable
private fun DoseLines(resources: Resources, face: DoseFace) {
    when (face) {
        DoseFace.NoCourses, DoseFace.AllTaken -> {
            Text(resources.getString(R.string.med_title), style = valueStyle(size = 18))
            Text(
                resources.getString(
                    if (face == DoseFace.NoCourses) R.string.widget_dose_none else R.string.widget_dose_done,
                ),
                style = captionStyle(),
            )
        }

        is DoseFace.Next -> {
            Text(
                resources.getString(R.string.widget_dose_next, face.dose.name),
                style = valueStyle(size = 18),
                maxLines = 1,
            )
            Text(
                doseCaption(resources, face.dose.dose, doseTimeText(resources, face.dose)),
                style = captionStyle(),
                maxLines = 1,
            )
        }
    }
}

/**
 * The one tap that writes: padded to the 48dp target the track widget's Stop measured, and carrying
 * the course, the slot and the [day] it was drawn for, so a face left standing across midnight or
 * after a redraw cannot mark another dose.
 */
@Composable
private fun TakenButton(context: Context, dose: PlannedDose, day: LocalDate) {
    Text(
        context.getString(R.string.widget_dose_taken),
        style = valueStyle(size = 16),
        modifier = GlanceModifier
            .padding(horizontal = 14.dp, vertical = 15.dp)
            .clickable(
                actionRunCallback<MarkDoseTakenAction>(
                    actionParametersOf(
                        DOSE_MEDICATION_KEY to dose.medicationId,
                        DOSE_SLOT_KEY to dose.slot,
                        DOSE_DAY_KEY to day.toString(),
                    ),
                ),
            ),
    )
}

class DoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DoseWidget()
}

/**
 * Marks the dose the face named as taken — the same row the medicine tab and the notification's
 * Taken write, through the same factory and the same door ([markDosesTaken]), whose check of the
 * alarms after the write takes down a reminder left with no dose to speak for. The day is the
 * face's own ([tappedDoseDay]) and never the clock's at the tap; that day's doses are read again
 * rather than trusted from the face, for a course deleted since. A dose marked in between — from
 * the notification, or by this same tap arriving twice — is the door's to refuse.
 */
class MarkDoseTakenAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medicationId = parameters[DOSE_MEDICATION_KEY] ?: return
        val slot = parameters[DOSE_SLOT_KEY] ?: return
        markTappedDose(StepsDb.get(context), medicationId, slot, parameters[DOSE_DAY_KEY], LocalDate.now())
        refreshWidgets(context, KIND_MED)
    }
}

/**
 * What [MarkDoseTakenAction] writes for a face drawn for [drawnFor] and tapped on [today]: the dose of
 * that course and slot on the face's day, while it is still planned and unmarked. Answers whether a
 * row was written. Apart from the callback so that a database in memory can be handed to it.
 */
internal suspend fun markTappedDose(
    db: StepsDb,
    medicationId: Long,
    slot: String,
    drawnFor: String?,
    today: LocalDate,
): Boolean {
    val day = tappedDoseDay(drawnFor, today)
    val dose = day?.let { drawn ->
        todaysDoses(db, drawn).firstOrNull { it.medicationId == medicationId && it.slot == slot && !it.taken }
    }
    return day != null && dose != null &&
        db.markDosesTaken(listOf(medTakenEntry(dose, day, System.currentTimeMillis()))) > 0
}

// --- the cycle ----------------------------------------------------------------------------------

private val CYCLE_KINDS = listOf(KIND_CYCLE, KIND_CYCLE_END, KIND_CYCLE_LOG)

/**
 * The cycle widget's two lines: the day of the cycle, and under it the period and its day or, on
 * any other day, when the next period is expected.
 *
 * The fertile days are not named here, and are read as the days around them. Wherever the app names
 * them it says beside them that they are an estimate from dates and not a method of contraception;
 * a 12sp line on a launcher has no room for that sentence. Without it a caption would stand as a
 * fact — for up to thirteen days running where her cycles vary by a week — and its absence on every
 * other day would read as the opposite fact, which is the one somebody would rely on. The page is
 * one tap away.
 *
 * A record too old to give a day of the cycle is one gone quiet and not an empty one, as the cycle
 * page says: the title is the day it stops, by [dayMonthYear], since that is at least two cycles back
 * and may be in a year gone by. "No cycle recorded" over a year of her cycles would be false.
 */
internal fun cycleLines(
    resources: Resources,
    summary: CycleSummary,
    phase: CyclePhase,
    dayMonth: DateTimeFormatter,
    dayMonthYear: DateTimeFormatter,
): Pair<String, String> {
    val title = summary.dayOfCycle?.let { resources.getString(R.string.cycle_day, it) }
        ?: summary.lastStart?.let { resources.getString(R.string.cycle_nudge_late, it.format(dayMonthYear)) }
        ?: resources.getString(R.string.cycle_phase_unknown)
    val caption = when (phase) {
        is CyclePhase.Menstruation -> resources.getString(R.string.widget_cycle_period, phase.dayOfPeriod)

        is CyclePhase.Fertile, is CyclePhase.Ordinary -> summary.nextExpected?.let {
            resources.getString(R.string.cycle_next, it.format(dayMonth))
        }
            ?: resources.getString(R.string.cycle_phase_ordinary)

        CyclePhase.Unknown -> resources.getString(R.string.cycle_title)
    }
    return title to caption
}

class CycleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = {
                val today = LocalDate.now()
                val entries = dao.journalOfKindsBefore(CYCLE_KINDS, today.plusDays(1).toString()).first()
                val summary = cycleSummary(entries, today)
                val phase = CycleFacts(entries, summary, today).phase()
                val locale = Locale.getDefault()
                val dayMonth = localizedDatePattern(locale, "dMMM", "d MMM")
                val dayMonthYear = localizedDatePattern(locale, "yMMMd", "d MMM yyyy")
                cycleLines(context.resources, summary, phase, dayMonth, dayMonthYear)
            },
        ) { (title, caption) ->
            WidgetFrame(onClick = openAppIntent(context, WidgetRoute.CYCLE)) {
                Text(title, style = valueStyle(size = 18))
                Text(caption, style = captionStyle(), maxLines = 1)
            }
        }
    }
}

class CycleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CycleWidget()
}
