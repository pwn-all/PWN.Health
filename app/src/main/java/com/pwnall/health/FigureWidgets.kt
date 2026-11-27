package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/*
 * The figure widgets: one number a day worth seeing without opening anything — last night's sleep,
 * the latest weight, today's energy, the week's steps and the level — each on the frame the steps
 * widget set, a figure over a caption. None of them writes. The energy widget's tap opens the meal
 * form and the level's the levels page, because those are the places the figure sends a person; the
 * rest open the app. Every reading is a pure function; the widget only paints it.
 */

// --- last night ---------------------------------------------------------------------------------

/** One night: when it began and ended, and whether a device measured it or the phone guessed it. */
internal data class Night(val startAt: Long, val endAt: Long, val measured: Boolean) {
    val minutes: Int get() = ((endAt - startAt).coerceAtLeast(0L) / 60_000L).toInt()
}

/**
 * The night to show among the sleep rows of one waking day: a measured night over the phone's
 * estimate, and of two measured the longer — a strap's whole night rather than the nap it also saw.
 */
internal fun lastNight(rows: List<JournalEntry>): Night? = rows
    .mapNotNull { row ->
        row.sleepStartAt?.let { start ->
            row.sleepEndAt?.let { end -> Night(start, end, row.measurementSource != PHONE_ESTIMATE) }
        }
    }
    .maxWithOrNull(compareBy<Night>({ it.measured }, { it.minutes }))

/** The wall-clock time of [epochMs] in the phone's zone, as the app prints every clock time. */
internal fun clockAt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    return clockText(time.hour * 60 + time.minute)
}

class SleepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(read = { lastNight(dao.sleepOn(LocalDate.now().toString())) }) { night ->
            WidgetFrame(onClick = openAppIntent(context)) {
                if (night == null) {
                    Text(context.getString(R.string.insight_no_value), style = valueStyle())
                    Text(context.getString(R.string.widget_sleep_none), style = captionStyle())
                } else {
                    Text(workDurationText(context.resources, night.minutes), style = valueStyle())
                    Text(
                        context.getString(R.string.widget_sleep_caption, clockAt(night.startAt), clockAt(night.endAt)),
                        style = captionStyle(),
                    )
                }
            }
        }
    }
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

// --- latest weight ------------------------------------------------------------------------------

/** How far back the weight widget looks for the weighing it measures the latest against. */
internal const val WEIGHT_WINDOW_DAYS = 30L

/** The latest weighing and how far it is from the first in the window; [changeKg] is null with one. */
internal data class WeightFigure(val kg: Double, val changeKg: Double?)

internal fun weightReading(rows: List<JournalEntry>): WeightFigure? {
    val weighed = rows.mapNotNull { row ->
        row.measurementWeightKg?.let { it to row.occurredAt }
    }.sortedBy { it.second }
    val latest = weighed.lastOrNull()?.first ?: return null
    return WeightFigure(latest, (latest - weighed.first().first).takeIf { weighed.size > 1 })
}

/**
 * "72.4 kg", or in pounds for a profile that reads in them — the day list's own two spellings, in the
 * digits and decimal mark of the phone's language, as the day list prints them ("72,4 kg").
 */
internal fun weightText(resources: Resources, kg: Double, imperial: Boolean): String {
    val locale = resources.configuration.locales[0]
    return if (imperial) {
        "${imperialWeight(kg).pretty(locale)} ${resources.getString(R.string.unit_lb)}"
    } else {
        resources.getString(R.string.measurement_weight_value, kg.pretty(locale))
    }
}

/** The caption under the weight: the change over the window, signed, or that there was none. */
internal fun weightCaption(resources: Resources, reading: WeightFigure, imperial: Boolean): String {
    val change = reading.changeKg ?: return resources.getString(R.string.widget_weight_caption)
    if (abs(change) < WEIGHT_STEADY_KG) return resources.getString(R.string.widget_weight_steady)
    val sign = if (change < 0) "−" else "+"
    return resources.getString(R.string.widget_weight_change, sign + weightText(resources, abs(change), imperial))
}

/** Under this much either way the scale has not moved: it is the rounding the day list prints to. */
private const val WEIGHT_STEADY_KG = 0.05

class WeightWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = {
                val today = LocalDate.now()
                val from = today.minusDays(WEIGHT_WINDOW_DAYS).toString()
                val rows = dao.journalOfKindBetween(KIND_MEASUREMENT, from, today.toString()).first()
                weightReading(rows) to (dao.profileNow()?.imperial == true)
            },
        ) { (reading, imperial) ->
            WidgetFrame(onClick = openAppIntent(context)) {
                if (reading == null) {
                    Text(context.getString(R.string.insight_no_value), style = valueStyle())
                    Text(context.getString(R.string.widget_weight_none), style = captionStyle())
                } else {
                    Text(weightText(context.resources, reading.kg, imperial), style = valueStyle())
                    Text(weightCaption(context.resources, reading, imperial), style = captionStyle())
                }
            }
        }
    }
}

class WeightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeightWidget()
}

// --- today's energy -----------------------------------------------------------------------------

/** Today's energy as the food page counts it: eaten, the allowance with movement added, and the rest. */
internal data class EnergyDay(val eatenKcal: Double, val limitKcal: Double, val remainingKcal: Double)

/**
 * The three figures the food page's budget panel prints, by the same arithmetic: the day's meals,
 * its activities and the pedometer's steps, against the sedentary budget of [profile]. They are the
 * page's own figures only for the profile the page reads, the stored one with the latest measured
 * weight and height laid over it ([profileInEffect]) — handed the stored row, the widget would count
 * from the weight onboarding was given and disagree with the page after the first weighing.
 */
internal fun energyDay(profile: UserProfile, journal: List<JournalEntry>, steps: Long, year: Int): EnergyDay {
    val eaten = journal.filter { it.kind == KIND_EAT }.sumOf { it.calories }
    val target = energyBudget(profile, year).targetKcal
    val walked = stepCalories(steps, profile.weightKg, profile.heightCm)
    val moved = movedCalories(journal.filter { it.kind == KIND_ACTIVITY }, walked)
    return EnergyDay(eaten, dailyFoodTarget(target, moved), remainingKcal(target, eaten, moved))
}

/** "of 2 520 kcal · 1 280 left", or how far over. */
internal fun energyCaption(resources: Resources, day: EnergyDay): String {
    val rest = if (day.remainingKcal >= 0) {
        resources.getString(R.string.widget_energy_left, day.remainingKcal.wholeKcal())
    } else {
        resources.getString(R.string.widget_energy_over, (-day.remainingKcal).wholeKcal())
    }
    return resources.getString(R.string.widget_energy_caption, day.limitKcal.wholeKcal(), rest)
}

class EnergyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = {
                val today = LocalDate.now()
                dao.profileInEffect()?.let { profile ->
                    val journal = dao.journalOnNow(today.toString())
                    energyDay(profile, journal, dao.stepsOn(today.toString()) ?: 0L, today.year)
                }
            },
        ) { day ->
            WidgetFrame(onClick = openAppIntent(context, WidgetRoute.FOOD)) {
                if (day == null) {
                    Text(context.getString(R.string.insight_no_value), style = valueStyle())
                    Text(context.getString(R.string.unit_kcal), style = captionStyle())
                } else {
                    Text(day.eatenKcal.wholeKcal(), style = valueStyle())
                    Text(energyCaption(context.resources, day), style = captionStyle())
                }
            }
        }
    }
}

class EnergyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EnergyWidget()
}

// --- the week's steps ---------------------------------------------------------------------------

/** How many days the week widget draws, today last. */
internal const val WEEK_WIDGET_DAYS = 7

/** The last [WEEK_WIDGET_DAYS] days' steps, oldest first, a day nothing was counted on as 0. */
internal fun weekSteps(days: List<DaySteps>, today: LocalDate): List<Long> {
    val byDay = days.associate { it.day to it.steps }
    return (WEEK_WIDGET_DAYS - 1 downTo 0).map { byDay[today.minusDays(it.toLong()).toString()] ?: 0L }
}

/** The height of a day's bar: its share of the busiest day, and never less than a sliver. */
internal fun weekBarHeight(steps: Long, most: Long, tallest: Dp = WEEK_BAR_HEIGHT, floor: Dp = WEEK_BAR_FLOOR): Dp =
    if (most <= 0L) floor else maxOf(floor, tallest * (steps.toFloat() / most))

private val WEEK_BAR_HEIGHT = 36.dp
private val WEEK_BAR_FLOOR = 3.dp
private val WEEK_BAR_WIDTH = 14.dp

class WeekWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = {
                val today = LocalDate.now()
                val from = today.minusDays(WEEK_WIDGET_DAYS - 1L).toString()
                weekSteps(dao.daysBetween(from, today.toString()).first(), today)
            },
        ) { steps ->
            WidgetFrame(onClick = openAppIntent(context)) {
                WeekBars(steps)
                Text(
                    context.getString(
                        R.string.widget_week_caption,
                        steps.sum().formatted(),
                        (steps.sum() / steps.size).formatted(),
                    ),
                    style = captionStyle(),
                )
            }
        }
    }
}

/** Seven bars against the busiest day, today's in the primary colour and the others behind it. */
@Composable
private fun WeekBars(steps: List<Long>) {
    val most = steps.max()
    Row(
        GlanceModifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Vertical.Bottom,
    ) {
        steps.forEachIndexed { index, count ->
            Column(
                GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.Bottom,
            ) {
                val color = if (index == steps.lastIndex) GlanceTheme.colors.primary else GlanceTheme.colors.secondary
                Box(
                    GlanceModifier.width(WEEK_BAR_WIDTH).height(weekBarHeight(count, most)).cornerRadius(3.dp)
                        .background(color),
                ) {}
            }
        }
    }
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

// --- the level ----------------------------------------------------------------------------------

/**
 * The journal kinds [annualXp] counts: food entries, and the days with an activity. The level widget
 * reads the year's rows of these alone rather than every row of the year, water, moods and doses
 * with them, at each redraw a walk's steps ask for.
 */
internal val XP_JOURNAL_KINDS = listOf(KIND_EAT, KIND_ACTIVITY)

/** This year's XP and the streak, read once the way the levels page reads them as they change. */
private suspend fun StepsDao.levelNow(today: LocalDate): Pair<LevelProgress, Int> {
    val start = today.withDayOfYear(1).toString()
    val opens = allAppOpenDays()
    val xp = annualXp(
        today = today,
        days = daysSince(start).first(),
        journal = XP_JOURNAL_KINDS.flatMap { journalOfKindBetween(it, start, today.toString()).first() },
        appointments = allAppointments(),
        openDays = opens,
        zone = ZoneId.systemDefault(),
    )
    return xp.progress to openingStreak(opens, today)
}

class LevelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        val resources = context.resources
        provideReading(read = { dao.levelNow(LocalDate.now()) }) { (progress, streak) ->
            WidgetFrame(onClick = openAppIntent(context, WidgetRoute.LEVELS)) {
                // The levels page's own words for the level and the XP, with the streak after them.
                Text(resources.getString(R.string.level_title, progress.level), style = valueStyle(size = 18))
                LinearProgressIndicator(
                    progress = progress.fraction,
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
                Text(
                    resources.getString(
                        R.string.level_xp_of,
                        progress.earned.formatted(),
                        progress.required.formatted(),
                    ) +
                        " · " + resources.getQuantityString(R.plurals.widget_level_streak, streak, streak),
                    style = captionStyle(),
                )
            }
        }
    }
}

class LevelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LevelWidget()
}
