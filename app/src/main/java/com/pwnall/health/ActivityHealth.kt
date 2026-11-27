package com.pwnall.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val TRACK_DISTANCE_CLIENT_ID_PREFIX = "pwnfit-track-distance:"
private const val TRACK_ELEVATION_CLIENT_ID_PREFIX = "pwnfit-track-elevation:"
private const val TRACK_SPEED_CLIENT_ID_PREFIX = "pwnfit-track-speed:"
private const val SESSION_DISTANCE_CLIENT_ID_PREFIX = "pwnfit-journal-distance:"
private const val POWER_CLIENT_ID_PREFIX = "pwnfit-journal-power:"
private const val STEPS_CADENCE_CLIENT_ID_PREFIX = "pwnfit-journal-stepcadence:"
private const val BIKE_CADENCE_CLIENT_ID_PREFIX = "pwnfit-journal-bikecadence:"
private const val ACTIVE_ENERGY_CLIENT_ID_PREFIX = "pwnfit-journal-activekcal:"
private const val TOTAL_ENERGY_CLIENT_ID_PREFIX = "pwnfit-day-totalkcal:"

/** Matches the step export: a month back is what a re-synced phone needs to look complete. */
private const val ENERGY_EXPORT_LOOKBACK_DAYS = 30L

/**
 * Limitation: a route is one fix a second, and Health Connect has no use for ten thousand speed
 * samples, so every nth point is taken instead.
 */
private const val MAX_SPEED_SAMPLES = 500

/**
 * What a session covered and how hard it was: distance, climb, speed, power, cadence, and the two
 * energy figures.
 *
 * VO2 max is absent because nothing here measures it: it needs a protocol the app does not run.
 * Floors are measured — [FloorTracker] counts them all day — but they belong to a day rather
 * than to a session, and this function exports sessions.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun exportActivityRecords(
    context: Context,
    db: StepsDb,
    client: HealthConnectClient,
    now: Instant,
    localEvents: List<JournalEntry>,
    profile: UserProfile?,
    zone: ZoneId,
    version: Long,
) {
    val sessions = localEvents.filter { it.kind == KIND_ACTIVITY }
    val tracks = db.dao().allTracks().filter { it.endedAt != null }
    // Every route's records are named for the sweeps below; only these routes' are written.
    val owedRoutes = routesOwedToHealthConnect(tracks, publishedRoutes).mapTo(HashSet()) { it.id }

    val activeEnergyRecords = sessions.healthRecords { entry ->
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        val kcal = entry.calories.takeIf { it > 0.0 } ?: return@healthRecords null
        ActiveCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            energy = Energy.kilocalories(kcal),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, ACTIVE_ENERGY_CLIENT_ID_PREFIX, entry.id),
                clientRecordVersion = version,
            ),
        )
    }
    // A machine or a foot pod counts its own metres; the phone's GPS track is exported separately
    // below, so the two never describe the same movement twice.
    val sessionDistanceRecords = sessions.healthRecords { entry ->
        val metres = entry.payloadValue("totalDistanceMeters")?.takeIf { it > 0.0 }
            ?: return@healthRecords null
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        DistanceRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            distance = Length.meters(metres),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, SESSION_DISTANCE_CLIENT_ID_PREFIX, entry.id),
                clientRecordVersion = version,
            ),
        )
    }
    val powerRecords = sessions.healthRecords { entry ->
        val watts = entry.payloadValue("powerWatts")?.takeIf { it > 0.0 } ?: return@healthRecords null
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        PowerRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            samples = listOf(PowerRecord.Sample(end, Power.watts(watts))),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, POWER_CLIENT_ID_PREFIX, entry.id),
                clientRecordVersion = version,
            ),
        )
    }
    val stepsCadenceRecords = sessions.healthRecords { entry ->
        val spm = entry.payloadValue("cadenceSpm")?.takeIf { it > 0.0 } ?: return@healthRecords null
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        StepsCadenceRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            samples = listOf(StepsCadenceRecord.Sample(end, spm)),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, STEPS_CADENCE_CLIENT_ID_PREFIX, entry.id),
                clientRecordVersion = version,
            ),
        )
    }
    val bikeCadenceRecords = sessions.healthRecords { entry ->
        val rpm = entry.payloadValue("cadenceRpm")?.takeIf { it > 0.0 } ?: return@healthRecords null
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        CyclingPedalingCadenceRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            samples = listOf(CyclingPedalingCadenceRecord.Sample(end, rpm)),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, BIKE_CADENCE_CLIENT_ID_PREFIX, entry.id),
                clientRecordVersion = version,
            ),
        )
    }

    val trackDistanceRecords = tracks.healthRecords { track ->
        val metres = track.distanceMeters.takeIf { it > 0.0 } ?: return@healthRecords null
        val start = Instant.ofEpochMilli(track.startedAt)
        val end = Instant.ofEpochMilli(track.endedAt ?: return@healthRecords null)
        if (end <= start) return@healthRecords null
        DistanceRecord(
            startTime = start,
            startZoneOffset = track.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = track.offsetAt(end, zone),
            distance = Length.meters(metres),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, TRACK_DISTANCE_CLIENT_ID_PREFIX, track.id),
                clientRecordVersion = version,
            ),
        )
    }
    val trackElevationRecords = tracks.healthRecords { track ->
        val climb = track.elevationGainM.takeIf { it > 0.0 } ?: return@healthRecords null
        val start = Instant.ofEpochMilli(track.startedAt)
        val end = Instant.ofEpochMilli(track.endedAt ?: return@healthRecords null)
        if (end <= start) return@healthRecords null
        ElevationGainedRecord(
            startTime = start,
            startZoneOffset = track.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = track.offsetAt(end, zone),
            elevation = Length.meters(climb),
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, TRACK_ELEVATION_CLIENT_ID_PREFIX, track.id),
                clientRecordVersion = version,
            ),
        )
    }
    val trackSpeedRecords = tracks.filter { it.id in owedRoutes }.healthRecords { track ->
        // One thinned query per track rather than every point of every track at once: the table
        // grows by a row a second, and loading it whole to keep 500 samples would run out of heap after a
        // year of walks — on every save, since every save mirrors. And only for a route that is
        // owed: the query is the cost, and the sweep needs a route's name, not its samples.
        val samples = db.dao().trackSpeedSamples(track.id, MAX_SPEED_SAMPLES).mapNotNull { sample ->
            val speed = sample.speedMps.toDouble().takeIf { it >= 0.0 } ?: return@mapNotNull null
            SpeedRecord.Sample(Instant.ofEpochMilli(sample.timestamp), Velocity.metersPerSecond(speed))
        }
        if (samples.isEmpty()) return@healthRecords null
        val start = samples.first().time
        val end = samples.last().time.takeIf { it > start } ?: start.plusSeconds(1)
        SpeedRecord(
            startTime = start,
            startZoneOffset = track.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = track.offsetAt(end, zone),
            samples = samples,
            metadata = Metadata.manualEntry(
                clientRecordId = publishedClientId(context, TRACK_SPEED_CLIENT_ID_PREFIX, track.id),
                clientRecordVersion = version,
            ),
        )
    }

    val totalEnergyRecords = dailyTotalEnergyRecords(db, localEvents, profile, now, zone, version)

    client.insertChanged(activeEnergyRecords)
    client.insertChanged(sessionDistanceRecords)
    client.insertChanged(powerRecords)
    client.insertChanged(stepsCadenceRecords)
    client.insertChanged(bikeCadenceRecords)
    client.insertIfAllowed(trackDistanceRecords.ofRoutes(owedRoutes))
    client.insertIfAllowed(trackElevationRecords.ofRoutes(owedRoutes))
    client.insertIfAllowed(trackSpeedRecords)
    // Only now, with all three through: a route whose records were not taken is still owed.
    publishedRoutes.keys.retainAll(tracks.mapTo(HashSet()) { it.id })
    tracks.filter { it.id in owedRoutes }.forEach { publishedRoutes[it.id] = it.hashCode() }
    client.insertChanged(totalEnergyRecords)

    removeStalePwnFitRecords(
        context,
        client,
        now,
        ActiveCaloriesBurnedRecord::class,
        ACTIVE_ENERGY_CLIENT_ID_PREFIX,
        activeEnergyRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        TotalCaloriesBurnedRecord::class,
        TOTAL_ENERGY_CLIENT_ID_PREFIX,
        totalEnergyRecords.mapNotNull { it.metadata.clientRecordId }.toSet() +
            keptEnergyHistoryIds(db, now, zone),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        DistanceRecord::class,
        SESSION_DISTANCE_CLIENT_ID_PREFIX,
        sessionDistanceRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        DistanceRecord::class,
        TRACK_DISTANCE_CLIENT_ID_PREFIX,
        trackDistanceRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        ElevationGainedRecord::class,
        TRACK_ELEVATION_CLIENT_ID_PREFIX,
        trackElevationRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        SpeedRecord::class,
        TRACK_SPEED_CLIENT_ID_PREFIX,
        // Every finished route's name, built or not: a route without a sample has no record to keep.
        tracks.mapTo(HashSet()) { publishedClientId(context, TRACK_SPEED_CLIENT_ID_PREFIX, it.id) },
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        PowerRecord::class,
        POWER_CLIENT_ID_PREFIX,
        powerRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        StepsCadenceRecord::class,
        STEPS_CADENCE_CLIENT_ID_PREFIX,
        stepsCadenceRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        CyclingPedalingCadenceRecord::class,
        BIKE_CADENCE_CLIENT_ID_PREFIX,
        bikeCadenceRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        span = { it.startTime to it.endTime },
    )
}

/**
 * What this process last published of each finished route, as the row's id and its hash: the
 * routes' counterpart of the journal's `publishedRows` in HealthSync.kt, emptied with it when the
 * journal's tag or the grants change, and read and written under the same lock.
 *
 * Without it, a route's distance, its climb and five hundred of its speeds would be written again
 * at every return to the app, for every route ever recorded and with a query of each one's fixes,
 * and every other app would be told each time that all of it had changed. The rest of this export's
 * records are written only when they have changed as well, by their content ([insertChanged]); a
 * route is kept by its row so that the query of its fixes is spared too.
 */
internal val publishedRoutes = HashMap<Long, Int>()

/** The finished routes whose records have to be written: the ones [published] does not hold as they stand now. */
internal fun routesOwedToHealthConnect(tracks: List<TrackEntity>, published: Map<Long, Int>): List<TrackEntity> =
    tracks.filter { published[it.id] != it.hashCode() }

/** The records among these of a route in [owed]: a record's client id ends in its row's number. */
private fun <T : Record> List<T>.ofRoutes(owed: Set<Long>): List<T> =
    filter { it.metadata.clientRecordId?.substringAfterLast(':')?.toLongOrNull() in owed }

/**
 * One record a day: resting metabolism plus what the steps and the logged sessions cost — the home
 * screen's sum, at the weight in force when the day ended ([StepsDao.weightInEffectBefore]), or the
 * profile row's own where nothing was weighed before it. That is the weight the sessions inside the
 * total were already costed at ([recalculatedActivityTimeline]). Counted at [profile]'s — the
 * latest weighing — one trip to the scales would move every day of the month in Health Connect.
 * A day gone by is exported whole; today's record ends at this moment and carries only the part of
 * the day it covers, so the figure grows until midnight and then stops.
 */
internal suspend fun dailyTotalEnergyRecords(
    db: StepsDb,
    localEvents: List<JournalEntry>,
    profile: UserProfile?,
    now: Instant,
    zone: ZoneId,
    version: Long,
): List<TotalCaloriesBurnedRecord> {
    val owner = profile ?: return emptyList()
    val storedWeightKg = db.dao().profileNow()?.weightKg ?: owner.weightKg
    val from = now.atZone(zone).toLocalDate().minusDays(ENERGY_EXPORT_LOOKBACK_DAYS)
    val stepsByDay = db.dao().allDays()
        .filter { it.day.toLocalDateOrNull()?.let { day -> day >= from } == true }
    return stepsByDay.mapNotNull { day ->
        val date = day.day.toLocalDateOrNull() ?: return@mapNotNull null
        val start = date.atStartOfDay(zone).toInstant()
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
        val end = midnight.coerceAtMost(now)
        if (end <= start) return@mapNotNull null
        val weightKg = db.dao().weightInEffectBefore(end.toEpochMilli()) ?: storedWeightKg
        val total = dayTotalCalories(
            profile = owner.copy(weightKg = weightKg),
            year = date.year,
            sessions = localEvents.filter { it.kind == KIND_ACTIVITY && it.day == day.day },
            steps = day.steps,
            // A day is not always 24 hours long: the offset changes under it twice a year.
            coveredShare = Duration.between(start, end).toMillis().toDouble() /
                Duration.between(start, midnight).toMillis(),
        )
        if (total <= 0.0) return@mapNotNull null
        TotalCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            energy = Energy.kilocalories(total),
            metadata = Metadata.manualEntry(
                clientRecordId = TOTAL_ENERGY_CLIENT_ID_PREFIX + day.day,
                clientRecordVersion = version,
            ),
        )
    }
}

/**
 * A day the export no longer covers is history, not staleness: its published total keeps standing,
 * so the sweep may only withdraw what [dailyTotalEnergyRecords] was still asked to re-issue — by the
 * same [now], so the two cannot fall either side of a midnight.
 */
private suspend fun keptEnergyHistoryIds(db: StepsDb, now: Instant, zone: ZoneId): Set<String> {
    val from = now.atZone(zone).toLocalDate().minusDays(ENERGY_EXPORT_LOOKBACK_DAYS)
    return db.dao().allDays()
        .filter { it.day.toLocalDateOrNull()?.isBefore(from) == true }
        .map { TOTAL_ENERGY_CLIENT_ID_PREFIX + it.day }
        .toSet()
}

/**
 * What a day cost, by the home screen's formulas, for the [profile] handed in: the export's carries
 * the weight in force that day, where the screen reads the latest weighing over it.
 *
 * The movement part goes through [movedCalories]: a logged walk and the pedometer steps it was made
 * of are the same outing, and paying for both would export a day nobody lived. Resting energy is
 * spent by the clock rather than by the day, so a record that stops at the moment of the sync
 * carries only the share of it that has actually elapsed — [coveredShare], 1.0 for a day gone by.
 */
internal fun dayTotalCalories(
    profile: UserProfile,
    year: Int,
    sessions: List<JournalEntry>,
    steps: Long,
    coveredShare: Double,
): Double = restingCaloriesPerDay(profile, year) * coveredShare.coerceIn(0.0, 1.0) +
    movedCalories(sessions, stepCalories(steps, profile.weightKg, profile.heightCm))

/**
 * The clock a session ran on. A live Bluetooth workout is logged the moment it starts and grows
 * from there; everything entered by hand runs backwards from the moment it was written down.
 */
internal fun JournalEntry.sessionInterval(now: Instant): Pair<Instant, Instant>? {
    val minutes = durationMinutes?.takeIf { it > 0.0 } ?: return null
    val loggedAt = Instant.ofEpochMilli(occurredAt)
    val durationMillis = (minutes * 60_000).toLong().coerceAtLeast(1_000)
    val (start, end) = if (activityCode in DIRECT_LIVE_ACTIVITY_CODES) {
        loggedAt to loggedAt.plusMillis(durationMillis).coerceAtMost(now)
    } else {
        loggedAt.minusMillis(durationMillis) to loggedAt
    }
    return (start to end).takeIf { end > start }
}

/**
 * The package an imported row's marker names as its source. The marker is in
 * [JournalEntry.healthConnectRecordKey], where schema 26 moved it; read from the note, it would
 * match nothing, and no imported session would receive the details its source measured.
 */
internal fun JournalEntry.importOrigin(): String =
    healthConnectRecordKey.orEmpty().removePrefix(HEALTH_IMPORT_MARKER_PREFIX).substringBefore(':')

/** The payload key an imported session keeps the span its energy was last asked for under. */
private const val ENERGY_ASKED_KEY = "energyAskedFor"

/** How long after a session has ended its source may still be writing what it measured. */
private val LATE_DETAILS: Duration = Duration.ofDays(2)

private fun JournalEntry.sessionEndMs(): Long = occurredAt + ((durationMinutes ?: 0.0) * 60_000).toLong()

private fun JournalEntry.energySpan(): String = "$occurredAt-${sessionEndMs()}"

/**
 * Whether an imported session's energy is still to be asked for: it never was over the span the
 * session stands on now — it is new, or its source has moved it since — or the session ended so
 * recently that its source may not have written its figures yet.
 */
internal fun JournalEntry.owesMeasuredEnergy(now: Instant): Boolean {
    val asked = runCatching { JSONObject(measurementPayloadJson ?: "{}").optString(ENERGY_ASKED_KEY) }.getOrNull()
    return asked != energySpan() || sessionEndMs() > now.minus(LATE_DETAILS).toEpochMilli()
}

/**
 * The session with the energy its source measured over it, where that is a figure at all, and the
 * span it was asked for written down — answered or not, so the question is not put again.
 */
internal fun JournalEntry.withMeasuredEnergy(kcal: Double?): JournalEntry {
    val payload = measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    return copy(
        calories = kcal?.takeIf { it >= 1.0 } ?: calories,
        measurementPayloadJson = payload.put(ENERGY_ASKED_KEY, energySpan()).toString(),
    )
}

/**
 * The energy the source app measured for an imported session, in place of the MET guess —
 * [DIRECT_LIVE_ACTIVITY_CODES] keeps the recalculation off it. Sessions of our own are left alone,
 * since their energy was exported from the row itself and never left it, and so is a session a
 * person has corrected, whose figure is theirs now.
 *
 * Asked of Health Connect's aggregate, over the session's span and of its source alone, one session
 * at a time. Reading the records and adding them up by the share of each inside the session would
 * count twice a source that writes a total over the session beside its minute-by-minute records;
 * the aggregate counts each stretch of time once, as the step totals do. Only a session that
 * [owesMeasuredEnergy] is asked about, so a year of workouts is not asked about again each time
 * one more arrives.
 *
 * Only the energy: no screen shows the distance, power, cadence or climb of another app's session,
 * so they are not read.
 */
@Suppress("SwallowedException")
internal suspend fun importActivityDetails(db: StepsDb, client: HealthConnectClient, now: Instant) {
    val since = now.minus(Duration.ofDays(HEALTH_IMPORT_DAYS)).toEpochMilli()
    val owed = db.dao().allJournal().filter {
        it.kind == KIND_ACTIVITY && it.activityCode?.startsWith("HEALTH_CONNECT") == true &&
            it.healthConnectRecordKey != null && it.editedAt == null && (it.durationMinutes ?: 0.0) > 0.0 &&
            it.occurredAt >= since && it.owesMeasuredEnergy(now)
    }
    for (session in owed) {
        val kcal = try {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(session.occurredAt),
                        Instant.ofEpochMilli(session.sessionEndMs()),
                    ),
                    dataOriginFilter = setOf(DataOrigin(session.importOrigin())),
                ),
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        } catch (denied: SecurityException) {
            // Energy the person did not allow reading leaves every session at its estimate, still owed.
            return
        }
        val updated = session.withMeasuredEnergy(kcal)
        if (updated != session) db.dao().saveJournalEntry(updated)
    }
}
