package com.pwnall.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ExerciseCompletionGoal
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.PlannedExerciseBlock
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PlannedExerciseStep
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A routine someone put on a date. The journal row points back at the template it came from. */
const val KIND_PLAN = "PLAN"

/**
 * An exercise of a template session that was never done — begun not at all, or every set of it
 * skipped. A row of its own rather than an ACTIVITY of zero minutes, so the journal can say it was not
 * done while every count of workouts, every export and every report passes it by.
 */
const val KIND_NOT_DONE = "NOT_DONE"

/** Payload key holding the routine id a plan was made from. */
const val PLAN_ROUTINE_KEY = "routineId"

internal const val MINDFULNESS_CLIENT_ID_PREFIX = "pwnfit-journal-mindful:"
private const val PLAN_CLIENT_ID_PREFIX = "pwnfit-journal-plan:"

/**
 * The two sessions published here: a breathing practice that already happened, and a workout that
 * has not happened yet.
 *
 * The mindfulness record is still marked experimental in connect-client 1.1.0. The risk is one
 * rename at the next upgrade, against a practice log that otherwise goes nowhere.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal suspend fun exportSessionRecords(
    context: Context,
    db: StepsDb,
    client: HealthConnectClient,
    now: Instant,
    localEvents: List<JournalEntry>,
    zone: ZoneId,
    version: Long,
) {
    // Each type is gated on this Health Connect knowing it — see [supportsFeature]. A type it does
    // not know is neither written nor swept: the sweep reads the same unknown type back and would
    // throw the same way.
    if (client.supportsFeature(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)) {
        val mindfulnessRecords = localEvents.healthRecords { entry ->
            if (entry.kind != KIND_BREATHING) return@healthRecords null
            val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
            MindfulnessSessionRecord(
                startTime = start,
                startZoneOffset = entry.offsetAt(start, zone),
                endTime = end,
                endZoneOffset = entry.offsetAt(end, zone),
                metadata = Metadata.manualEntry(
                    clientRecordId = publishedClientId(context, MINDFULNESS_CLIENT_ID_PREFIX, entry.id),
                    clientRecordVersion = version,
                ),
                mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING,
                title = context.getString(R.string.breathing_title),
            )
        }
        client.insertChanged(mindfulnessRecords)
        removeStalePwnFitRecords(
            context,
            client,
            now,
            MindfulnessSessionRecord::class,
            MINDFULNESS_CLIENT_ID_PREFIX,
            mindfulnessRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
        )
    }
    if (client.supportsFeature(HealthConnectFeatures.FEATURE_PLANNED_EXERCISE)) {
        val planRecords = plannedSessionRecords(db, localEvents, version, journalGeneration(context))
        client.insertChanged(planRecords)
        removeStalePwnFitRecords(
            context,
            client,
            // The sweep only reads back from the instant it is given, and plans live up to a year
            // ahead: swept from today, a deleted future plan would stay published until its date.
            now.plus(Duration.ofDays(PLAN_IMPORT_FUTURE_DAYS)),
            PlannedExerciseSessionRecord::class,
            PLAN_CLIENT_ID_PREFIX,
            planRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
            // A day holds more than one plan, each of them at the day's noon: the name tells them apart.
            span = { Triple(it.startTime, it.endTime, it.title) },
        )
    }
}

/**
 * Whether the Health Connect on this phone knows a record type. The platform module gained the
 * mindfulness and planned-exercise records in later extension releases, and connect-client 1.1.0
 * answers a read or a write of a type the module lacks with an IllegalArgumentException, not an
 * empty page — which would take the whole synchronisation down on a de-Googled Android 14 before the
 * export ever ran, so such a phone would never write a single record.
 */
internal fun HealthConnectClient.supportsFeature(feature: Int): Boolean =
    features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

/** One planned session per routine someone put on a date, with a block per exercise. */
private suspend fun plannedSessionRecords(
    db: StepsDb,
    localEvents: List<JournalEntry>,
    version: Long,
    generation: String,
): List<PlannedExerciseSessionRecord> {
    val plans = localEvents.filter { it.kind == KIND_PLAN }
    // Read before the records are built, not inside [healthRecords]: that catches whatever its block
    // throws, for a row Health Connect will not take, and a failed read is not such a row. Swallowed
    // there, a cancelled export would run on to its next suspension, and a read that failed would leave
    // its plan out of the list the sweep keeps, so the sweep would withdraw a plan that was still standing.
    val exercisesOf = plans.mapNotNull { it.planRoutineId() }.distinct()
        .associateWith { db.dao().routineExercises(it) }
    return plans
        .healthRecords { entry ->
            val day = entry.day.toLocalDateOrNull() ?: return@healthRecords null
            val routineId = entry.planRoutineId() ?: return@healthRecords null
            val exercises = exercisesOf[routineId].orEmpty().ifEmpty { return@healthRecords null }
            val blocks = exercises.map { exercise ->
                PlannedExerciseBlock(
                    repetitions = (exercise.sets ?: 1).coerceAtLeast(1),
                    steps = listOf(
                        PlannedExerciseStep(
                            exerciseType = healthConnectExerciseType(exercise.activityCode),
                            exercisePhase = PlannedExerciseStep.EXERCISE_PHASE_ACTIVE,
                            // Reps when the exercise counts them, minutes when it does not.
                            performanceTargets = emptyList(),
                            completionGoal = exercise.reps
                                ?.let { ExerciseCompletionGoal.RepetitionsGoal(it) }
                                ?: ExerciseCompletionGoal.DurationGoal(
                                    Duration.ofSeconds((exercise.durationMinutes * 60).toLong().coerceAtLeast(1)),
                                ),
                        ),
                    ),
                    description = null,
                )
            }
            PlannedExerciseSessionRecord(
                metadata = Metadata.manualEntry(
                    clientRecordId = publishedClientId(PLAN_CLIENT_ID_PREFIX, generation, entry.id),
                    clientRecordVersion = version,
                ),
                // The plan is for a day, not an hour: nobody picked a time, so none is published.
                startDate = day,
                duration = Duration.ofSeconds(
                    ((entry.durationMinutes ?: 0.0) * 60).toLong().coerceAtLeast(60),
                ),
                blocks = blocks,
                exerciseType = healthConnectExerciseType(exercises.first().activityCode),
                title = entry.title,
            )
        }
}

/** Which routine a plan row was built from, or null if the payload is missing or foreign. */
internal fun JournalEntry.planRoutineId(): Long? {
    val json = measurementPayloadJson ?: return null
    return runCatching { JSONObject(json).optLong(PLAN_ROUTINE_KEY) }.getOrNull()?.takeIf { it > 0 }
}

/**
 * The sessions back from Health Connect. A breathing practice comes home as the row the timer
 * would have written — sessions of any other mindfulness type are someone's meditation library,
 * not a breathing log, and stay where they are. A plan another app published becomes a titled
 * entry on its date; a plan of our own is never adopted back, because the routine it pointed at
 * lives only in the database that exported it.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal suspend fun importSessionRecords(context: Context, imports: HealthImport, now: Instant): Int {
    val zone = ZoneId.systemDefault()
    var imported = 0
    imported += imports.read(MindfulnessSessionRecord::class) { record, marker ->
        val breathing = record.mindfulnessSessionType ==
            MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING
        if (!breathing || record.endTime <= record.startTime) {
            null
        } else {
            val loggedAt = importedSessionInstant(
                startTime = record.startTime,
                endTime = record.endTime,
                ownRecord = record.metadata.dataOrigin.packageName == context.packageName,
            )
            val offset = if (loggedAt == record.endTime) record.endZoneOffset else record.startZoneOffset
            JournalEntry(
                day = importedDay(loggedAt, offset, zone).toString(),
                occurredAt = loggedAt.toEpochMilli(),
                kind = KIND_BREATHING,
                title = record.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.breathing_title),
                calories = 0.0,
                durationMinutes = Duration.between(record.startTime, record.endTime)
                    .toMillis().coerceAtLeast(1_000) / 60_000.0,
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = marker,
                zoneOffsetSeconds = offset?.totalSeconds,
            )
        }
    }
    // A plan is the one thing here that lives in the future, which the default import window ends.
    imported += imports.read(
        PlannedExerciseSessionRecord::class,
        window = ImportWindow(
            now.minus(Duration.ofDays(PLAN_IMPORT_PAST_DAYS)),
            now.plus(Duration.ofDays(PLAN_IMPORT_FUTURE_DAYS)),
        ),
    ) { record, marker ->
        val title = record.title?.takeIf { it.isNotBlank() }
        if (record.metadata.dataOrigin.packageName == context.packageName || title == null) {
            null
        } else {
            JournalEntry(
                day = importedDay(record.startTime, record.startZoneOffset, zone).toString(),
                occurredAt = record.startTime.toEpochMilli(),
                kind = KIND_PLAN,
                title = title,
                calories = 0.0,
                durationMinutes = Duration.between(record.startTime, record.endTime)
                    .toMinutes().toDouble().takeIf { it > 0.0 },
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = marker,
                zoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
            )
        }
    }
    return imported
}

/** A stale plan is history nobody trains from; a month back keeps the current week's context. */
private const val PLAN_IMPORT_PAST_DAYS = 30L
private const val PLAN_IMPORT_FUTURE_DAYS = 365L
