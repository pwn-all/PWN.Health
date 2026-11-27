package com.pwnall.health

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** The phone's own step counter, which writes no [JournalEntry.measurementSource] of its own. */
internal const val SOURCE_PHONE = "PHONE_SENSOR"

/** Health Connect's deduplicated step total, which names no single app behind it. */
internal const val SOURCE_HEALTH_CONNECT = "HEALTH_CONNECT"

/** What a row that names no source at all was: somebody typed it here. */
internal const val SOURCE_MANUAL = "MANUAL"

/**
 * One writer's share of one metric.
 *
 * [source] is identity, never a label: a package name, a Bluetooth device's source id, or one of
 * the tokens above. [label] is a name the device gave for itself, and [installedApp] says the
 * package manager is the one that can name this source; a share with neither is named from its
 * token by [vitalSourceLabel].
 */
data class SourceShare(
    val source: String,
    val label: String?,
    val installedApp: Boolean,
    /** Steps when the metric counts steps, rows written for every other metric. */
    val amount: Long,
    /** ISO day of the newest thing this source gave, so a source that stopped can be seen to have. */
    val lastDay: String,
    /** The share the figure on screen comes from. */
    val winner: Boolean,
)

/** One metric and everything that writes into it, biggest contributor first. */
data class MetricSources(
    @StringRes val titleRes: Int,
    @DrawableRes val art: Int,
    /** [SourceShare.amount] is a step count rather than a number of rows. */
    val inSteps: Boolean,
    val shares: List<SourceShare>,
)

/** One thing one source wrote, reduced to what deciding a share needs. */
private data class Written(
    val source: String,
    val installedApp: Boolean,
    val day: String,
    val at: Long,
)

/**
 * One day as one source reported it.
 *
 * [steps] is what that source gave and is the figure printed beside its name. [carries] is the
 * figure it stands for when the day is decided, and the two part company on exactly one kind of
 * row: an app that fed one of Health Connect's days without being the whole of it. The day's total
 * there is the platform's deduplicated figure over every app at once, and it belongs to the Health
 * Connect row that prints it; the apps under it carry nothing, because a phone counting 8,000 must
 * not take a day from a watch's 9,000 by being weighed against the 3,000 a second app put into it.
 */
private data class StepDay(
    val source: String,
    val label: String?,
    val day: String,
    val steps: Long,
    val carries: Long = steps,
    /** Whether [source] is a package name the package manager can put a name to. */
    val installedApp: Boolean = false,
)

/**
 * Who writes each metric, how much each of them gave, and whose figure is the one on screen.
 *
 * The question this answers is the one Health Connect leaves open: it deduplicates Activity and
 * Sleep aggregates and nothing else, so a person with a watch, a phone and a food app has no way
 * to find out which of them the number in front of them came from — or that two of them wrote it
 * twice. Everything here is read out of what this app already stored; nothing is asked of Health
 * Connect, which is why the screen works with the network and the provider both gone.
 *
 * Winning means two different things, because merging does. Steps are merged by taking the day's
 * highest single count and never a sum ([effectiveStepTotal]), so the step winner is the source
 * that carried the most days. Every other metric keeps every row it is given — two apps writing
 * the same weight leave two rows — so the winner there is whoever wrote the newest one, which is
 * the row the cards actually print.
 *
 * A metric nothing has written is absent rather than empty, and metrics with the most writers come
 * first: a single source is the uninteresting case, and the whole point of the screen is the ones
 * where several disagree.
 */
fun dataSources(
    entries: List<JournalEntry>,
    localDays: List<DaySteps>,
    externalDays: List<ExternalDaySteps>,
    externalOriginDays: List<ExternalOriginDaySteps>,
    bleDays: List<DirectBleDaySteps>,
    profile: UserProfile,
): List<MetricSources> {
    // Which rows hold which instrument vital, decided row by row rather than by instant: two
    // devices can write inside one millisecond, and a row of another kind can share it with them.
    val holders = INSTRUMENT_VITALS.associateWith { vital -> entries.filter { it.holds(vital, profile) } }
    val readingRows = holders.values.flatten().toSet()
    val metrics = buildList {
        addNotNull(stepSources(localDays, externalDays, externalOriginDays, bleDays))
        addNotNull(journalMetric(R.string.weight, R.drawable.ic_art_weight, entries, ::isWeight))
        addNotNull(journalMetric(R.string.measurement_sleep_title, R.drawable.ic_art_sleep, entries, ::isSleep))
        addNotNull(journalMetric(R.string.home_workouts, R.drawable.ic_art_sport, entries, ::isWorkout))
        addNotNull(journalMetric(R.string.calendar_food, R.drawable.ic_art_food, entries, ::isFood))
        INSTRUMENT_VITALS.forEach { vital ->
            addNotNull(metricOf(vital.titleRes, vital.art, holders[vital].orEmpty().map(::writtenBy)))
        }
        addNotNull(
            journalMetric(R.string.sources_metric_other, R.drawable.ic_art_storage, entries) {
                !isWeight(it) && !isSleep(it) && !isWorkout(it) && !isFood(it) && it !in readingRows
            },
        )
    }
    return metrics.sortedWith(
        compareByDescending<MetricSources> { it.shares.size }
            .thenByDescending { it.shares.sumOf(SourceShare::amount) },
    )
}

/**
 * The vitals an instrument of its own writes, and so the ones two instruments can disagree
 * about: a strap and a watch both report variability, and a watch and a ring a resting rate.
 * Body composition is left out on purpose: fat, muscle, water and basal rate all come off the
 * same scale row as the weight above them, and four more cards saying "the scale" would bury the
 * ones where the answer is not obvious.
 */
private val INSTRUMENT_VITALS = listOf(
    Vital.HEART_RATE,
    Vital.RESTING_HEART_RATE,
    Vital.HRV,
    Vital.BLOOD_PRESSURE,
    Vital.OXYGEN_SATURATION,
    Vital.BODY_TEMPERATURE,
)

private val Vital.art: Int
    @DrawableRes get() = when (this) {
        Vital.BLOOD_PRESSURE -> R.drawable.ic_art_blood
        Vital.OXYGEN_SATURATION -> R.drawable.ic_art_lungs
        Vital.BODY_TEMPERATURE -> R.drawable.ic_art_doctor
        else -> R.drawable.ic_art_pulse
    }

private fun isWeight(entry: JournalEntry) = entry.measurementWeightKg != null

private fun isSleep(entry: JournalEntry) = entry.sleepStartAt != null

private fun isWorkout(entry: JournalEntry) = entry.kind == KIND_ACTIVITY

private fun isFood(entry: JournalEntry) = entry.kind == KIND_EAT

/**
 * Whether this row itself holds a reading of [vital] — the same test [vitalSeries] applies.
 *
 * Asked of one row at a time on purpose: an instant is not an identity. Instruments stamp their
 * rows from a device clock that counts whole seconds, so two of them read in the same second land
 * on the same millisecond, and a row of another kind can land there beside them.
 */
private fun JournalEntry.holds(vital: Vital, profile: UserProfile) =
    vitalSeries(listOf(this), vital, profile).isNotEmpty()

/**
 * What wrote a row, in the most specific terms the row can prove.
 *
 * An imported row names the package behind Health Connect rather than Health Connect itself: which
 * app wrote it is the whole question, and "Health Connect" as an answer is the one the platform
 * already gives. Its own [JournalEntry.measurementSource] says "HEALTH_CONNECT" and is the less
 * specific of the two.
 *
 * Only a key that is an import marker naming a package is read that way. [dailyHeartRateMarker]
 * files a day under "heart" and names no app on purpose, and a restored backup can carry any string
 * at all in the column; both of those are Health Connect and nothing more precise.
 */
private fun writtenBy(entry: JournalEntry): Written {
    val fromApp = entry.healthConnectRecordKey
        ?.takeIf { it.startsWith(HEALTH_IMPORT_MARKER_PREFIX) }
        ?.removePrefix(HEALTH_IMPORT_MARKER_PREFIX)
        ?.substringBefore(':')
        // A package name has at least two identifiers separated by a dot, which is what tells a
        // real writer from the daily heart-rate marker's "heart" and from a key that is no marker
        // at all. Neither of those names an app the package manager can be asked about.
        ?.takeIf { '.' in it }
    return Written(
        source = fromApp ?: entry.measurementSource?.takeIf { it.isNotBlank() } ?: SOURCE_MANUAL,
        installedApp = fromApp != null,
        day = entry.day,
        at = entry.occurredAt,
    )
}

private fun journalMetric(
    @StringRes titleRes: Int,
    @DrawableRes art: Int,
    entries: List<JournalEntry>,
    holds: (JournalEntry) -> Boolean,
): MetricSources? = metricOf(titleRes, art, entries.filter(holds).map(::writtenBy))

private fun metricOf(@StringRes titleRes: Int, @DrawableRes art: Int, written: List<Written>): MetricSources? {
    val newest = written.maxByOrNull(Written::at)?.source ?: return null
    val shares = written.groupBy(Written::source).map { (source, own) ->
        SourceShare(
            source = source,
            label = null,
            installedApp = own.first().installedApp,
            amount = own.size.toLong(),
            lastDay = own.maxOf(Written::day),
            winner = source == newest,
        )
    }
    return MetricSources(titleRes, art, inSteps = false, shares = shares.sortedByDescending(SourceShare::amount))
}

/**
 * The places a day's steps can come from, each with the days it carried.
 *
 * Ties go to the first source in the order they are gathered — the phone, then Health Connect,
 * then the wearables — which is the same order [effectiveStepTotal] would leave them in and keeps
 * a day counted identically by two sources from changing hands between two openings of the screen.
 */
private fun stepSources(
    local: List<DaySteps>,
    external: List<ExternalDaySteps>,
    externalOrigins: List<ExternalOriginDaySteps>,
    ble: List<DirectBleDaySteps>,
): MetricSources? {
    val days = local.filter { it.steps > 0 }.map { StepDay(SOURCE_PHONE, null, it.day, it.steps) } +
        healthConnectDays(external, externalOrigins) +
        ble.filter { it.steps > 0 }.map { StepDay(it.sourceId, it.sourceLabel, it.day, it.steps) }
    if (days.isEmpty()) return null
    val wins = days.groupBy(StepDay::day)
        .mapNotNull { (_, sameDay) -> sameDay.maxByOrNull(StepDay::carries)?.source }
        .groupingBy { it }
        .eachCount()
    val best = wins.maxByOrNull { it.value }?.key
    val shares = days.groupBy(StepDay::source).map { (source, own) ->
        SourceShare(
            source = source,
            label = own.firstNotNullOfOrNull(StepDay::label),
            installedApp = own.first().installedApp,
            amount = own.sumOf(StepDay::steps),
            lastDay = own.maxOf(StepDay::day),
            winner = source == best,
        )
    }
    return MetricSources(
        titleRes = R.string.steps,
        art = R.drawable.ic_art_steps,
        inSteps = true,
        shares = shares.sortedByDescending(SourceShare::amount),
    )
}

/**
 * Health Connect's step days, named by the apps that wrote them where this install can prove who
 * they were.
 *
 * The platform aggregates and deduplicates steps for the app, which is the whole reason a single
 * total exists — and the reason it names nobody. `syncStepDays` asks it a second time, once per
 * contributing package, and those shares are what lets a day be named at all.
 *
 * A day one app accounts for entirely — one share, and its figure is the day's figure — is that
 * app's day and says so under the app's own name. That is the ordinary case and the reason the
 * screen exists: the one watch app behind Health Connect, named.
 *
 * A day several apps fed keeps its Health Connect row carrying the joint total, with the apps
 * listed under it for what each of them gave. Nothing else would be true. The joint total is
 * deduplicated across the apps and is the figure the rest of the app counts with, so it belongs to
 * no single one of them; and an app's own figure can even exceed it, because the joint total
 * applies the person's own source priority and drops the records of the app they ranked lower. A
 * row marked "shown" beside a number that is not the one being shown would be this screen telling
 * the exact kind of lie it was built to catch.
 *
 * A day with no shares stored against it is Health Connect under its own name. That is not a rare
 * corner: it is every day synced before the share table existed, every day older than the thirty
 * the exchange looks back over, every day synced while the person had denied the step permission,
 * and every restored backup written by a build without the table.
 */
private fun healthConnectDays(
    external: List<ExternalDaySteps>,
    origins: List<ExternalOriginDaySteps>,
): List<StepDay> {
    val byDay = origins.filter { it.steps > 0 }.groupBy(ExternalOriginDaySteps::day)
    return external.filter { it.steps > 0 }.flatMap { day ->
        val shares = byDay[day.day].orEmpty()
        val sole = shares.singleOrNull()?.takeIf { it.steps == day.steps }
        when {
            sole != null -> listOf(StepDay(sole.packageName, null, day.day, day.steps, installedApp = true))

            shares.isEmpty() -> listOf(StepDay(SOURCE_HEALTH_CONNECT, null, day.day, day.steps))

            else -> listOf(StepDay(SOURCE_HEALTH_CONNECT, null, day.day, day.steps)) +
                shares.map { StepDay(it.packageName, null, day.day, it.steps, carries = 0L, installedApp = true) }
        }
    }
}

private fun <T> MutableList<T>.addNotNull(item: T?) {
    if (item != null) add(item)
}
