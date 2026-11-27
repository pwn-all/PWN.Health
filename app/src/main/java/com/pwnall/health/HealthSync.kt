package com.pwnall.health

import android.app.ActivityManager
import android.health.connect.DeviceDataSource
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.reflect.KClass

private const val STEP_EXPORT_LOOKBACK_DAYS = 30L

private const val SYNC_PREFS = "health_sync"

/** Set once the first exchange's restore has run; until then the mirror and the sweeps must stay quiet. */
private const val KEY_RESTORE_DONE = "restoreDone"

/** When this install first synced. Anything older was published by a journal it no longer has. */
private const val KEY_ADOPTED_AT_MS = "adoptedAtMs"

/** The last version a record was published under; [publishVersion] says why it is kept. */
private const val KEY_RECORD_VERSION = "recordVersion"

/** What Health Connect last named this phone's own step counter; [currentDeviceStepPackageName] says why. */
private const val KEY_DEVICE_STEP_PACKAGE = "deviceStepPackage"

/** The tag this journal's records carry in Health Connect; [journalGeneration] says why. */
private const val KEY_JOURNAL_GENERATION = "journalGeneration"

/**
 * Set from the moment imported sessions may be standing without the details their source measured
 * until [importActivityDetails] has read them, so an exchange that fails in between still owes the
 * read to the next one; [owesSessionDetails] says when that moment is.
 */
private const val KEY_SESSION_DETAILS_OWED = "sessionDetailsOwed"

/**
 * Set from the moment an exchange may write its first imported row until the recalculation that
 * follows the import has run ([owesRecalculation]).
 */
private const val KEY_RECALCULATION_OWED = "recalculationOwed"

/**
 * When the last exchange that succeeded was asked for — its start, not its end; [recordedHealthSync]
 * says why. Moves only on success, so a failure is retried at the next launch.
 */
private const val KEY_LAST_AUTO_SYNC_MS = "lastAutoSyncMs"

/**
 * What the Health Connect card reports about the last exchange. The attempt is stamped before the
 * work starts, whether or not it finishes; the counts and the error describe how it ended, and the
 * error is cleared by the next success so the card never shows a failure that has been recovered.
 */
private const val KEY_LAST_SYNC_ATTEMPT_MS = "lastSyncAttemptMs"
private const val KEY_LAST_SYNC_IMPORTED = "lastSyncImported"
private const val KEY_LAST_SYNC_EXPORTED = "lastSyncExported"
private const val KEY_LAST_SYNC_RECORDS = "lastSyncRecords"
private const val KEY_LAST_SYNC_WORKOUTS = "lastSyncWorkouts"
private const val KEY_LAST_SYNC_STEP_DAYS = "lastSyncStepDays"

/** Whether the last exchange was refused the step read; the one zero the card must explain. */
private const val KEY_LAST_SYNC_STEPS_BLOCKED = "lastSyncStepsBlocked"

/** What was allowed when the last exchange started, so a new grant can be noticed and acted on. */
private const val KEY_LAST_SYNC_GRANTS = "lastSyncGrants"

/** Whether the last exchange had step days to publish and was refused. */
private const val KEY_LAST_SYNC_STEPS_WRITE_BLOCKED = "lastSyncStepsWriteBlocked"

/** The app Health Connect gives records for and counts in no total; null when there is none. */
private const val KEY_LAST_SYNC_STEPS_UNCOUNTED = "lastSyncStepsUncounted"
private const val KEY_LAST_SYNC_ERROR = "lastSyncError"

/**
 * What kind of failure [KEY_LAST_SYNC_ERROR] was, as a [SyncFailureKind] name. Kept beside the raw
 * text rather than instead of it: the text is the lead for a diagnosis, the kind is what the card
 * can say in the person's language. A failure stored without one reads as the generic kind.
 */
private const val KEY_LAST_SYNC_ERROR_KIND = "lastSyncErrorKind"

/**
 * How stale the last exchange has to be before opening the app runs another.
 *
 * Five minutes: the moment a person opens this app is the moment the watch's night and the food
 * app's lunch should already be in it, and a figure a day old is the thing the sync exists to
 * prevent. It is a floor rather than a schedule — nothing polls, and the only thing that ever asks
 * is a launch or a return to the foreground. What the floor stops is a rotation, a permission
 * dialog or a walk to the next screen and back re-running the whole two-way exchange.
 */
private val AUTO_SYNC_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * How long an exchange that ran into Health Connect's request quota is left alone. The platform
 * counts the quota over a quarter of an hour, so an attempt made sooner spends its calls on being
 * refused again, from the first record type on. "Try again" and a newly allowed type do not wait.
 */
private val RATE_LIMIT_BACKOFF: Duration = Duration.ofMinutes(15)
private const val HEALTH_WRITE_BATCH_SIZE = 500

/** Two syncs interleaved would each pass the other's dedup checks and import everything twice. */
private val healthSyncMutex = Mutex()

/**
 * How long one exchange, and one mirror, may hold [healthSyncMutex] before it is let go.
 *
 * The exchange and the mirror make their calls to Health Connect under that lock, and only the one
 * raw platform call ([readCurrentDeviceStepPackageName]) has a limit of its own. Without this one,
 * a call the provider never answered would hold the lock until the process died, and every exchange
 * and mirror after it would queue behind it with the card's "Try again" greyed out. The client's
 * calls end when their coroutine is cancelled, so a limit hands the lock back, and the card says
 * Health Connect did not answer. What an exchange cut short had finished stays done: each type
 * keeps its token as its read completes.
 *
 * Limitation: one limit covers the whole exchange rather than each call, so a hung call holds the
 * lock up to ten minutes, and a restore longer than that is finished by the next exchange.
 */
private val EXCHANGE_TIMEOUT: Duration = Duration.ofMinutes(10)
private val MIRROR_TIMEOUT: Duration = Duration.ofMinutes(1)

/**
 * Where the exchange actually runs. Every caller is an activity's lifecycle scope or a composition,
 * and both die at a rotation, which would cancel a 365-day import midway and start it over on the
 * next resume. The work is launched here and only joined from the caller, so the caller's
 * cancellation stops its wait and nothing else; the mutex above keeps two launches in line.
 */
private val healthSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Whether Health Connect still answers a process of this importance with every app's records.
 *
 * Outliving the activity has a price. Health Connect serves another app's data only to an app in
 * the foreground: once this one has left it, a read comes back narrowed to the records this app
 * wrote — with no error, so it looks exactly like "nobody else recorded anything" — while an
 * aggregate or a change feed is refused outright. Visible is the nearest importance to where the
 * platform draws that line — it judges by the uid's state, which a process cannot ask for — and a
 * foreground service of this app's own, the step counter's, keeps the process above it.
 */
internal fun readsEveryApp(importance: Int): Boolean =
    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE

/**
 * Ends an exchange that has left the foreground, before it acts on a read that may have been
 * narrowed: an empty answer would wipe the steps other apps counted, and a change token kept over
 * it would skip their history for good. Thrown, so the exchange is written down as failed and the
 * next return to the app runs it again instead of waiting the floor out.
 */
private fun requireEveryAppReadable() {
    val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
    check(readsEveryApp(state.importance)) { "The app left the foreground during the exchange" }
}

/** Reinstall restore reaches back further than the export window: it runs once and reads only. */
private const val STEP_RESTORE_LOOKBACK_DAYS = 365L
internal const val HEALTH_IMPORT_DAYS = 365L

/** Heart rate is sampled continuously, so a year of foreign records is not worth reading. */
private const val HEART_RATE_IMPORT_DAYS = 30L

/**
 * Where the days the last exchange found another app's heart rate on are kept, one ISO date a line,
 * so the next one can tell a day whose samples have since been deleted from a day Health Connect
 * never held ([heartDaysLost]). Read and written by [HealthImport] alone, under [healthSyncMutex].
 *
 * In the folder no transfer reaches, as the install id is ([installId]). Kept with the preferences
 * they would travel to a new phone, whose Health Connect holds none of those samples, and every
 * day's rate the transfer had just carried over would be taken for deleted. Kept in memory only,
 * they would miss samples deleted while the process was not running, and leave the day's row
 * standing.
 */
private fun heartDaysFile(context: android.content.Context) = java.io.File(context.noBackupFilesDir, HEART_DAYS_FILE)

private const val HEART_DAYS_FILE = "health-connect-heart-days"

/** The days [file] holds; none when it is missing or cannot be read, which loses nothing but one look. */
internal fun heartDaysOfferedIn(file: java.io.File): Set<LocalDate> =
    runCatching { file.readLines().mapNotNullTo(mutableSetOf()) { it.toLocalDateOrNull() } }.getOrDefault(emptySet())

/** Writes [days] down for the next exchange; a disk that will not take them leaves the last ones. */
internal fun keepHeartDaysOffered(file: java.io.File, days: Set<LocalDate>) {
    runCatching { file.writeText(days.sorted().joinToString("\n")) }
}

/**
 * What the record key of every imported row begins with. A row deleted here is remembered under its
 * whole key, which is why the prefix is shared with [journalTombstoneKeys] rather than spelled
 * twice. It is a column of its own and never the note: see [JournalEntry.healthConnectRecordKey].
 */
internal const val HEALTH_IMPORT_MARKER_PREFIX = "Health Connect:"

private const val STEP_CLIENT_ID_PREFIX = "steps-local-day:"
private const val HEART_RATE_CLIENT_ID_PREFIX = "pwnfit-journal-heart:"
private const val SLEEP_CLIENT_ID_PREFIX = "pwnfit-journal-sleep:"
private const val WEIGHT_CLIENT_ID_PREFIX = "pwnfit-journal-weight:"
private const val HEIGHT_CLIENT_ID_PREFIX = "pwnfit-journal-height:"
private const val EXERCISE_CLIENT_ID_PREFIX = "pwnfit-journal-exercise:"
private const val HYDRATION_CLIENT_ID_PREFIX = "pwnfit-journal-water:"
private const val NUTRITION_CLIENT_ID_PREFIX = "pwnfit-journal-food:"
private const val BLOOD_PRESSURE_CLIENT_ID_PREFIX = "pwnfit-journal-pressure:"
private const val OXYGEN_CLIENT_ID_PREFIX = "pwnfit-journal-spo2:"
private const val TEMPERATURE_CLIENT_ID_PREFIX = "pwnfit-journal-temperature:"
private const val GLUCOSE_CLIENT_ID_PREFIX = "pwnfit-journal-glucose:"
private const val BODY_FAT_CLIENT_ID_PREFIX = "pwnfit-journal-bodyfat:"

/** The factor the lab history already reads a mass-concentration glucose meter with. */
private const val MG_DL_PER_MMOL_L = 18.016

/**
 * The published records [HealthImport] reads back into the journal.
 *
 * Only for these can a stale sweep tell "the person deleted this" from "this install has never
 * seen it". The exports with no import to restore them are left out: the planned session, and the
 * track and session detail records and the day's energy built in ActivityHealth.kt. A plan points
 * at a routine only this install's database holds, and nothing reads the others back, so after a
 * reinstall the copy in Health Connect is the only one left in existence — for those the sweep may
 * withdraw what this install published itself, and the twin of a record a row here has published
 * again over the same stretch of time ([removeStalePwnFitRecords]), and nothing else.
 */
private val RESTORED_CLIENT_ID_PREFIXES = setOf(
    WEIGHT_CLIENT_ID_PREFIX,
    HEIGHT_CLIENT_ID_PREFIX,
    HEART_RATE_CLIENT_ID_PREFIX,
    SLEEP_CLIENT_ID_PREFIX,
    EXERCISE_CLIENT_ID_PREFIX,
    HYDRATION_CLIENT_ID_PREFIX,
    NUTRITION_CLIENT_ID_PREFIX,
    BLOOD_PRESSURE_CLIENT_ID_PREFIX,
    OXYGEN_CLIENT_ID_PREFIX,
    TEMPERATURE_CLIENT_ID_PREFIX,
    GLUCOSE_CLIENT_ID_PREFIX,
    BODY_FAT_CLIENT_ID_PREFIX,
    PERIOD_CLIENT_ID_PREFIX,
    CYCLE_CLIENT_ID_PREFIX,
    BMR_CLIENT_ID_PREFIX,
    BODY_WATER_CLIENT_ID_PREFIX,
    MINDFULNESS_CLIENT_ID_PREFIX,
)

/**
 * Every type the app both reads and writes: what its own screens and devices produce is published,
 * and what another app recorded is imported, so switching to this app carries the history along
 * instead of stranding it. The rationale screen explains each one; [HealthRationaleTest] holds its
 * table to this list and to [HEALTH_WRITE_ONLY_TYPES].
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal val HEALTH_RECORD_TYPES: List<KClass<out Record>> = listOf(
    WeightRecord::class,
    HeightRecord::class,
    HeartRateRecord::class,
    SleepSessionRecord::class,
    ExerciseSessionRecord::class,
    StepsRecord::class,
    HydrationRecord::class,
    NutritionRecord::class,
    BloodPressureRecord::class,
    BloodGlucoseRecord::class,
    OxygenSaturationRecord::class,
    BodyTemperatureRecord::class,
    BodyFatRecord::class,
    // The cycle.
    MenstruationPeriodRecord::class,
    MenstruationFlowRecord::class,
    IntermenstrualBleedingRecord::class,
    OvulationTestRecord::class,
    CervicalMucusRecord::class,
    SexualActivityRecord::class,
    // Body values a scale reports and the resting rate the app computes from them.
    BasalMetabolicRateRecord::class,
    BodyWaterMassRecord::class,
    // What a session cost. The two cadences are written, never read, but Health Connect asks for
    // the steps' and the sessions' own read permissions for them, so they cannot be write-only.
    ActiveCaloriesBurnedRecord::class,
    StepsCadenceRecord::class,
    CyclingPedalingCadenceRecord::class,
    // A breathing practice that happened, and a workout that has not yet.
    MindfulnessSessionRecord::class,
    PlannedExerciseSessionRecord::class,
)

/**
 * Write only, on purpose. The day's total and a route's speed are derived here from sources the app
 * already imports — the total from resting rate, steps and sessions, speed from a GPS track's own
 * points — so reading someone else's derivation would only double-count what the sources already
 * said. A session's distance, climb and power go out with the app's own workouts and routes, and no
 * screen shows another app's ([importActivityDetails] reads only the energy).
 */
internal val HEALTH_WRITE_ONLY_TYPES: List<KClass<out Record>> = listOf(
    TotalCaloriesBurnedRecord::class,
    SpeedRecord::class,
    DistanceRecord::class,
    ElevationGainedRecord::class,
    PowerRecord::class,
)

/**
 * Read only, on purpose: a watch or a ring works both out for itself, usually overnight, and this
 * app computes neither — a strap's variability is one sitting's figure, not the nightly one these
 * records carry — so there is nothing of its own to write. Each is a card on the vitals screen,
 * beside the person's own four-week baseline.
 */
internal val HEALTH_READ_ONLY_TYPES: List<KClass<out Record>> = listOf(
    RestingHeartRateRecord::class,
    HeartRateVariabilityRmssdRecord::class,
)

internal val HEALTH_PERMISSIONS: Set<String> = HEALTH_RECORD_TYPES.flatMapTo(mutableSetOf()) {
    listOf(HealthPermission.getReadPermission(it), HealthPermission.getWritePermission(it))
} + HEALTH_WRITE_ONLY_TYPES.map { HealthPermission.getWritePermission(it) } +
    HEALTH_READ_ONLY_TYPES.map { HealthPermission.getReadPermission(it) } +
    // Without the history grant Health Connect serves another app's records from no earlier than 30
    // days before this app's first grant, which is no way to change apps. The import asks for its
    // year all the same, counting on the platform to narrow that read rather than refuse it, though
    // its documentation speaks of an error; which of the two a phone without the grant does is
    // untested. healthPermissionsKnownTo drops it on platforms that have never heard of it.
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

/**
 * The subset of the request this device's Health Connect actually defines.
 *
 * Record types live in the platform, not in the client library: mindfulness and planned exercise
 * arrived years after the rest, and an older Health Connect asked for a permission it has never
 * heard of turns down the whole request rather than the one type, which leaves the app looking
 * unconnected. Anything it does define is still asked for.
 */
internal fun healthPermissionsKnownTo(context: android.content.Context): Set<String> =
    HEALTH_PERMISSIONS.filterTo(mutableSetOf()) {
        runCatching { context.packageManager.getPermissionInfo(it, 0) }.isSuccess
        // Nothing recognised means the lookup itself failed, not that Health Connect is empty.
    }.ifEmpty { HEALTH_PERMISSIONS }

/**
 * Health Connect's own settings screen on Android 14 and above, where it is part of the platform.
 *
 * A literal rather than `HealthConnectManager.ACTION_HEALTH_HOME_SETTINGS`, whose class is not on
 * the compile classpath at this minSdk; the string is the platform's and does not move.
 */
internal const val ACTION_HEALTH_HOME_SETTINGS = "android.health.connect.action.HEALTH_HOME_SETTINGS"

/** What Health Connect already knows about the body, used to fill the onboarding pickers. */
internal data class HealthConnectBody(val weightKg: Double?, val heightCm: Int?)

/**
 * The request with sexual activity taken out, for somebody the age gate does not offer it to.
 *
 * [SEX_LOG_MIN_AGE] does not lock that screen, it removes it, and a grant with nothing behind it is
 * the excessive access a health-apps declaration is turned down for. The cycle is not in here: the
 * app offers that at every age.
 */
internal fun withoutIntimateTypes(permissions: Set<String>): Set<String> = permissions - setOf(
    HealthPermission.getReadPermission(SexualActivityRecord::class),
    HealthPermission.getWritePermission(SexualActivityRecord::class),
)

internal data class HealthSyncResult(
    val weightKg: Double?,
    /** The records of the journal's own rows Health Connect took, of the twelve types a row builds alone. */
    val exportedJournalRecords: Int,
    val importedHealthRecords: Int,
    val importedWorkouts: Int,
    val exportedStepDays: Int,
    val externallyCoveredStepDays: Int,
    val importedStepDays: Int,
    /** False when Health Connect refused the step read; the card says so rather than reporting zeros. */
    val stepsReadable: Boolean = true,
    /** False when it refused the step write; the card says so rather than counting an export nobody took. */
    val stepsWritable: Boolean = true,
    /** The app whose records Health Connect holds and counts nowhere, when there is one. */
    val uncountedStepSource: String? = null,
) {
    /**
     * What the card's status line counts: rows that crossed, in each direction. A day another app
     * already covered crossed nowhere, and the weight is a reading rather than a count.
     */
    val importedCount: Int get() = importedHealthRecords + importedWorkouts + importedStepDays
    val exportedCount: Int get() = exportedJournalRecords + exportedStepDays
}

/** What the Health Connect card says about the last exchange, as the sync preferences remember it. */
internal data class HealthSyncStatus(
    val lastAttemptMs: Long,
    val lastSuccessMs: Long,
    val imported: Int,
    val exported: Int,
    /** The imported total, broken down the way the import page reports it. */
    val records: Int = 0,
    val workouts: Int = 0,
    val stepDays: Int = 0,
    /** The last exchange reached Health Connect but was not allowed to read steps. */
    val stepsBlocked: Boolean = false,
    /** It had step days to publish and was not allowed to write them. */
    val stepsWriteBlocked: Boolean = false,
    /** The app Health Connect counts in nothing, if the last exchange found one. */
    val uncountedStepSource: String? = null,
    /** Set while the most recent attempt failed; the next success clears it. */
    val error: String?,
    /** What kind of failure [error] was; null when none was stored with it. */
    val errorKind: SyncFailureKind? = null,
)

/** The failures the card can explain, each in words the person can act on. */
internal enum class SyncFailureKind {
    /** Health Connect refused a read or a write this app was not allowed to make. */
    PERMISSION,

    /** Health Connect did not answer, or answered that it could not serve the call. */
    UNAVAILABLE,

    /** Health Connect's own request quota ran out; waiting is the whole remedy. */
    RATE_LIMITED,
    OTHER,
}

/**
 * The kind of [failure], read from its type and, for the quota, from the platform's message: the
 * client hands an exhausted quota over as one of the same exception types as other remote failures.
 *
 * So it hands over a write too large to take — "Records chunk size exceeded the max chunk limit",
 * "Record size exceeded the single record size limit" — which the platform files under its rate
 * limiter. That one is no quota: waiting does not shrink the write, and the steps after it may well
 * go through. Nor is it a Health Connect that did not answer.
 */
internal fun syncFailureKind(failure: Throwable): SyncFailureKind {
    val message = failure.message.orEmpty().lowercase(java.util.Locale.ROOT)
    return when {
        failure is SecurityException -> SyncFailureKind.PERMISSION
        "size exceeded" in message -> SyncFailureKind.OTHER
        "quota" in message || "rate limit" in message -> SyncFailureKind.RATE_LIMITED
        failure.isRemoteFailure() -> SyncFailureKind.UNAVAILABLE
        else -> SyncFailureKind.OTHER
    }
}

/** The types the client turns a Health Connect that could not serve the call into. */
private fun Throwable.isRemoteFailure(): Boolean =
    this is android.os.RemoteException || this is java.io.IOException || this is IllegalStateException

/** The sentence the card puts after "Last attempt failed:". */
@StringRes
internal fun SyncFailureKind?.reasonRes(): Int = when (this) {
    SyncFailureKind.PERMISSION -> R.string.m3_sync_failed_permission
    SyncFailureKind.UNAVAILABLE -> R.string.m3_sync_failed_unavailable
    SyncFailureKind.RATE_LIMITED -> R.string.m3_sync_failed_rate_limited
    SyncFailureKind.OTHER, null -> R.string.m3_sync_failed_other
}

internal fun healthSyncStatus(context: android.content.Context): HealthSyncStatus {
    val prefs = syncPrefs(context)
    return HealthSyncStatus(
        lastAttemptMs = prefs.getLong(KEY_LAST_SYNC_ATTEMPT_MS, 0L),
        lastSuccessMs = prefs.getLong(KEY_LAST_AUTO_SYNC_MS, 0L),
        imported = prefs.getInt(KEY_LAST_SYNC_IMPORTED, 0),
        exported = prefs.getInt(KEY_LAST_SYNC_EXPORTED, 0),
        records = prefs.getInt(KEY_LAST_SYNC_RECORDS, 0),
        workouts = prefs.getInt(KEY_LAST_SYNC_WORKOUTS, 0),
        stepDays = prefs.getInt(KEY_LAST_SYNC_STEP_DAYS, 0),
        stepsBlocked = prefs.getBoolean(KEY_LAST_SYNC_STEPS_BLOCKED, false),
        stepsWriteBlocked = prefs.getBoolean(KEY_LAST_SYNC_STEPS_WRITE_BLOCKED, false),
        uncountedStepSource = prefs.getString(KEY_LAST_SYNC_STEPS_UNCOUNTED, null),
        error = prefs.getString(KEY_LAST_SYNC_ERROR, null),
        errorKind = prefs.getString(KEY_LAST_SYNC_ERROR_KIND, null)?.let { stored ->
            SyncFailureKind.entries.firstOrNull { it.name == stored }
        },
    )
}

/**
 * The status the last exchange of this process left, for a card that is on screen while one
 * finishes. Null until then; the card reads the preferences itself in the meantime.
 */
internal val healthSyncStatusState: MutableState<HealthSyncStatus?> = mutableStateOf(null)

/**
 * Whether an exchange is under way anywhere in the process, for the card's "Try again". The card's
 * own memory of its tap dies with a rotation while the job it started does not, and a button that
 * came back enabled would queue a second full exchange behind the first. Counted rather than
 * flagged, because app launch starts two callers at once and the first to finish must not read as
 * "done".
 */
internal val healthSyncRunningState: MutableState<Boolean> = mutableStateOf(false)
private val healthSyncsInFlight = InFlightFlag { healthSyncRunningState.value = it }

/**
 * A count of things under way and the flag it is read as, moved under one lock.
 *
 * Moved under one lock because two exchanges ending together — app launch starts two — could
 * otherwise each take their reading of the count and then write the flag in the other order, which
 * would leave "running" standing over a count of zero and the card's "Try again" disabled until
 * some later exchange happened to end alone; a start crossing an end could leave the opposite.
 */
internal class InFlightFlag(private val publish: (Boolean) -> Unit) {
    private var count = 0

    @Synchronized
    fun move(delta: Int) {
        count += delta
        publish(count > 0)
    }
}

private data class StepSyncResult(
    val exportedDays: Int,
    val externallyCoveredDays: Int,
    val importedDays: Int,
    /**
     * False when Health Connect would not let the step read happen. Zeros alone cannot say that: a
     * phone whose steps nobody else records answers with zeros too, and an exchange written down as
     * a success either way would leave a person whose day says 0 nothing to read.
     */
    val readable: Boolean = true,
    /** False when there were days to publish and Health Connect refused to take them. */
    val writable: Boolean = true,
    /** An app Health Connect hands us records for and counts in nothing — this one included. */
    val uncountedSource: String? = null,
)

/**
 * What the screen says about a Health Connect it cannot use.
 *
 * Two states, two sentences. On Android 13 and below Health Connect is a Play app, and one too old
 * for this client is a tap away from working — docs/DESIGN.md §3.1 asks that the update be offered,
 * not that the provider be declared missing along with the phones that genuinely have none.
 */
internal fun syncUnavailableText(sdkStatus: Int): Int =
    if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
        R.string.sync_update_required
    } else {
        R.string.sync_unavailable
    }

/**
 * The exchange proper; the caller holds [healthSyncMutex], having decided under it that one is due.
 * One that has not ended by [EXCHANGE_TIMEOUT] fails as a Health Connect that did not answer.
 */
private suspend fun syncHealthConnect(
    context: android.content.Context,
    db: StepsDb,
    profile: UserProfile,
    client: HealthConnectClient,
): HealthSyncResult {
    val finished = withTimeoutOrNull(EXCHANGE_TIMEOUT.toMillis()) {
        // Paging, per-sample aggregation and building a record per journal row are the caller's
        // thread otherwise, and every caller is the main one.
        withContext(Dispatchers.Default) {
            syncHealthConnectLocked(context, db, profile, client)
        }
    }
    return checkNotNull(finished) { "Health Connect did not answer within ${EXCHANGE_TIMEOUT.toMinutes()} minutes" }
}

/**
 * The restore has run: from here the mirror may publish and the sweeps withdraw, because the history
 * is back in the journal and a record of an earlier journal that no row answers for is one its row
 * was deleted from, not one the restore has yet to reach.
 *
 * Written between the import and the export, not after the exchange. Written after it, the first
 * exchange of a reinstall would publish every restored row under this journal's tag and sweep
 * nothing, so Health Connect would hold each record twice — the earlier journal's and this one's —
 * until the next.
 *
 * The adoption instant is written once and never moved; an install that has none yet takes this
 * exchange's. Left at the epoch, an install would read every record of its own as history to
 * restore — so a row it deleted while Health Connect was out of reach would come back on the next
 * sync, for as long as the install lived. [startedAt] is the exchange's start, ahead of anything it
 * wrote.
 */
private fun markRestoreDone(prefs: android.content.SharedPreferences, startedAt: Instant) {
    prefs.edit {
        putBoolean(KEY_RESTORE_DONE, true)
        if (prefs.getLong(KEY_ADOPTED_AT_MS, 0L) == 0L) putLong(KEY_ADOPTED_AT_MS, startedAt.toEpochMilli())
    }
}

private fun syncPrefs(context: android.content.Context) =
    context.getSharedPreferences(SYNC_PREFS, android.content.Context.MODE_PRIVATE)

/**
 * Forgets everything the exchange knew, for a database that is starting over inside the same
 * install: one quarantined as corrupt, or set aside because no key here opens it.
 *
 * The preferences outlive the journal they describe. Left as they were they would tell the next
 * exchange that this was the same install with every row deleted: the mirror would publish at once,
 * the import would refuse to adopt records "this install wrote itself", and the sweep would
 * withdraw them from Health Connect — the one store that still holds the history, emptied at the
 * moment the database is lost. Emptied instead, the preferences read as a reinstall's do: nothing
 * is published or swept before the restore has run, the app's own records are adopted back, and
 * with no change tokens the window is read whole. The journal's tag goes with the rest
 * ([journalGeneration]): the fresh database numbers its rows from 1 again, and under the old tag
 * they would be published over the records of the rows that were lost.
 *
 * Committed rather than applied, as the flags written beside the moved file are: the database has
 * already gone, and a process that died before a queued write landed would open the fresh one with
 * the old answers.
 */
internal fun forgetHealthSyncState(context: android.content.Context) {
    synchronized(generationLock) {
        syncPrefs(context).edit(commit = true) { clear() }
        cachedGeneration = null
    }
}

private val generationLock = Any()

/** The tag as last read, so that building a record for every row does not ask the preferences for each. */
@Volatile
private var cachedGeneration: String? = null

/** Long enough that two journals of one person never share a tag, short enough to read in a record id. */
private const val GENERATION_LENGTH = 8

/**
 * The tag that tells this journal's records in Health Connect from an earlier journal's.
 *
 * A record is known there by this app's package, its type and its client record id. Room numbers a
 * fresh journal from 1 again, so with an id that ended in nothing but the number of the row, the
 * rows a reinstall writes first — the onboarding weight, then every restored row in turn — would be
 * published under numbers the previous install's records already hold, and each one would replace
 * an unrelated record: a weigh-in from three years ago, a route's distance, a plan. Whatever the
 * restore does not read back, which is everything older than its window and every type it never
 * reads, has no other copy. So the id names the journal as well ([publishedClientId]), and a number
 * is read as a row only under this journal's own tag ([publishedRowId]).
 *
 * Made the first time it is asked for and kept with the adoption instant, for as long: a transfer
 * to a new phone carries it over beside the database whose rows it names, and
 * [forgetHealthSyncState] drops it when a database starts over. Committed, because records are
 * published under it in the same breath.
 */
internal fun journalGeneration(context: android.content.Context): String =
    cachedGeneration ?: synchronized(generationLock) {
        val prefs = syncPrefs(context)
        val tag = prefs.getString(KEY_JOURNAL_GENERATION, null) ?: newJournalGeneration(prefs)
        tag.also { cachedGeneration = it }
    }

private fun newJournalGeneration(prefs: android.content.SharedPreferences): String {
    val tag = java.util.UUID.randomUUID().toString().take(GENERATION_LENGTH)
    prefs.edit(commit = true) { putString(KEY_JOURNAL_GENERATION, tag) }
    return tag
}

/**
 * The exchange, started quietly from app launch and from every return to the foreground.
 *
 * A fresh install grants Health Connect access during onboarding and has no sync screen to open, so
 * this is the only place the 365-day import and the export run. The first launch with a saved
 * profile pulls the history back, and every later launch and return to the foreground repeats the
 * exchange, no oftener than [AUTO_SYNC_INTERVAL]; [force] is the card's "Try again", which does not
 * wait the floor out. The work runs in [healthSyncScope] and is only awaited here, so a caller that
 * dies with a rotation leaves it running; how it ended is written to the sync preferences and to
 * [healthSyncStatusState] for a card that is watching.
 */
internal suspend fun autoSyncHealthConnectIfDue(
    context: android.content.Context,
    db: StepsDb,
    profile: UserProfile,
    force: Boolean = false,
) {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
    // The application context, because the job outlives the activity that asked for it.
    val app = context.applicationContext
    val client = HealthConnectClient.getOrCreate(app)
    val now = System.currentTimeMillis()
    val prefs = syncPrefs(app)
    val granted = runCatching { client.permissionController.getGrantedPermissions() }
    val refusedToAnswer = granted.exceptionOrNull()
    if (refusedToAnswer is CancellationException) throw refusedToAnswer
    val allowed = granted.getOrDefault(emptySet())
    // What was allowed has just changed, and that is its own reason to exchange. Waiting the floor
    // out here would make allowing steps look like it had done nothing: a person returning from
    // Health Connect's own settings page — or from the sheet this app raises — would meet a screen
    // that will not try again for another five minutes, and the number they came back for would
    // stay 0.
    val grants = allowed.grantSignature()
    val grantsChanged = refusedToAnswer == null && grants != prefs.getString(KEY_LAST_SYNC_GRANTS, null)
    val lastSuccessMs = prefs.getLong(KEY_LAST_AUTO_SYNC_MS, 0L)
    if (!healthSyncDue(force, grantsChanged, lastSuccessMs, now, rateLimitedAt(prefs))) return
    when {
        // A query that failed is not a refusal. Collapsing the two would send this function home
        // without stamping an attempt, so a phone whose Health Connect would not answer would look
        // exactly like one whose owner had allowed nothing: "not synced yet", no error, nothing to
        // act on.
        refusedToAnswer != null -> recordSyncQueryFailure(app, now, refusedToAnswer)

        allowed.isNotEmpty() ->
            healthSyncScope.launch { recordedHealthSync(app, db, profile, client, now, force, grants) }.join()
    }
}

/** What was allowed, in a form two exchanges can be compared by without keeping the whole list. */
private fun Set<String>.grantSignature(): String = "$size:${sorted().joinToString(",").hashCode()}"

/**
 * Whether an exchange should run now. Three reasons; the newly-allowed one keeps the app from
 * sitting on its hands for five minutes after a person grants the very permission they came for.
 *
 * A failure is retried at the next return to the app, without waiting the floor out — but for one
 * kind. [rateLimitedAtMs] is when the last attempt started, if Health Connect refused it for quota:
 * running the whole exchange again at every return to the app would spend its first read on the
 * very quota that had just run out, so that one waits [RATE_LIMIT_BACKOFF]. A stamp from a clock
 * that has since been set back holds nothing up.
 */
internal fun healthSyncDue(
    force: Boolean,
    grantsChanged: Boolean,
    lastSuccessMs: Long,
    now: Long,
    rateLimitedAtMs: Long? = null,
): Boolean {
    val flooredOut = now - lastSuccessMs >= AUTO_SYNC_INTERVAL.toMillis()
    val quotaRested = rateLimitedAtMs == null || now - rateLimitedAtMs !in 0 until RATE_LIMIT_BACKOFF.toMillis()
    return force || grantsChanged || (flooredOut && quotaRested)
}

/** When the last attempt started, if it ended refused for quota; null when it ended any other way. */
private fun rateLimitedAt(prefs: android.content.SharedPreferences): Long? =
    prefs.getLong(KEY_LAST_SYNC_ATTEMPT_MS, 0L).takeIf {
        prefs.getString(KEY_LAST_SYNC_ERROR_KIND, null) == SyncFailureKind.RATE_LIMITED.name
    }

/** The attempt nobody could make: stamped like any other failure, so the card has something to show. */
private fun recordSyncQueryFailure(
    context: android.content.Context,
    now: Long,
    failure: Throwable,
) {
    syncPrefs(context).edit {
        putLong(KEY_LAST_SYNC_ATTEMPT_MS, now)
        putString(KEY_LAST_SYNC_ERROR, failure.describe())
        putString(KEY_LAST_SYNC_ERROR_KIND, syncFailureKind(failure).name)
    }
    healthSyncStatusState.value = healthSyncStatus(context)
}

/**
 * Whether the exchange is still due, asked again under the lock. The floor counts from the last
 * exchange that succeeded: a failure leaves that stamp alone, so the next launch retries instead of
 * waiting the floor out — unless it was the quota that failed it, as [healthSyncDue] says. Asked
 * under the lock, so the second of two callers queued together does not run straight into the
 * refusal the first has just been given.
 *
 * The grants are compared here too, against what the exchange before ran under. Compared only
 * outside, both callers app launch fires would find the same new grant before either had written it
 * down, and each would force an exchange of its own past this check.
 */
private fun syncDue(prefs: android.content.SharedPreferences, now: Long, force: Boolean, grants: String): Boolean =
    healthSyncDue(
        force = force,
        grantsChanged = grants != prefs.getString(KEY_LAST_SYNC_GRANTS, null),
        lastSuccessMs = prefs.getLong(KEY_LAST_AUTO_SYNC_MS, 0L),
        now = now,
        rateLimitedAtMs = rateLimitedAt(prefs),
    )

/**
 * One exchange, and what the card is left to say about it. [startedAt] is the caller's clock, read
 * before it queued for the mutex: an exchange that waited behind another still counts from when it
 * was asked for, so a long import does not make the queue behind it due again. [force] is the card's
 * "Try again"; [grants] is what was allowed when it was asked for ([grantSignature]).
 */
private suspend fun recordedHealthSync(
    context: android.content.Context,
    db: StepsDb,
    profile: UserProfile,
    client: HealthConnectClient,
    startedAt: Long,
    force: Boolean,
    grants: String,
) {
    healthSyncsInFlight.move(1)
    try {
        healthSyncMutex.withLock {
            val prefs = syncPrefs(context)
            // Asked again under the lock. App launch starts the exchange twice, from the activity's
            // resume and from the composition's first frame, and both pass the check outside before
            // either has run; without this the second would repeat the 365-day pass the first has
            // just made.
            if (!syncDue(prefs, startedAt, force, grants)) return@withLock
            prefs.edit {
                putLong(KEY_LAST_SYNC_ATTEMPT_MS, startedAt)
                // Before the exchange, under the lock, so the caller queued behind this one finds the
                // grant already acted on. A failed exchange still retries: that is the success stamp's business.
                putString(KEY_LAST_SYNC_GRANTS, grants)
            }
            // The error is what the card shows until the next attempt goes through.
            runCatchingCancellable { syncHealthConnect(context, db, profile, client) }
                .onSuccess { result ->
                    prefs.edit {
                        putLong(KEY_LAST_AUTO_SYNC_MS, startedAt)
                        putInt(KEY_LAST_SYNC_IMPORTED, result.importedCount)
                        putInt(KEY_LAST_SYNC_EXPORTED, result.exportedCount)
                        putInt(KEY_LAST_SYNC_RECORDS, result.importedHealthRecords)
                        putInt(KEY_LAST_SYNC_WORKOUTS, result.importedWorkouts)
                        putInt(KEY_LAST_SYNC_STEP_DAYS, result.importedStepDays)
                        putBoolean(KEY_LAST_SYNC_STEPS_BLOCKED, !result.stepsReadable)
                        putBoolean(KEY_LAST_SYNC_STEPS_WRITE_BLOCKED, !result.stepsWritable)
                        result.uncountedStepSource
                            ?.let { putString(KEY_LAST_SYNC_STEPS_UNCOUNTED, it) }
                            ?: remove(KEY_LAST_SYNC_STEPS_UNCOUNTED)
                        remove(KEY_LAST_SYNC_ERROR)
                        remove(KEY_LAST_SYNC_ERROR_KIND)
                    }
                }
                .onFailure {
                    prefs.edit {
                        putString(KEY_LAST_SYNC_ERROR, it.describe())
                        putString(KEY_LAST_SYNC_ERROR_KIND, syncFailureKind(it).name)
                    }
                }
            healthSyncStatusState.value = healthSyncStatus(context)
        }
    } finally {
        healthSyncsInFlight.move(-1)
    }
}

/**
 * The class and the message, as much of either as the error has: a bare class name is still a lead.
 * R8 leaves the platform's and the JDK's class names alone, and those are what Health Connect's
 * client and this file throw; a class of this app's own would print renamed in a release, and that
 * release's mapping file reads it back.
 */
private fun Throwable.describe(): String =
    listOfNotNull(this::class.simpleName, message?.takeIf { it.isNotBlank() })
        .joinToString(": ")
        .ifEmpty { javaClass.name }

// One branch per Health Connect record type.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private suspend fun syncHealthConnectLocked(
    context: android.content.Context,
    db: StepsDb,
    profile: UserProfile,
    client: HealthConnectClient,
): HealthSyncResult {
    val now = Instant.now()
    val imports = HealthImport(context, db, client, now)
    // The day's step total goes first, so a nutrition record the platform chokes on in one of the
    // reads below cannot cost the one number the home screen shows. Nothing above it here reads or
    // writes a step table, and the step restore it performs is inside it, still ahead of its own
    // export.
    val stepSync = syncStepDays(context, db, client, now)
    // Reading comes before writing. Export deletes the app's own Health Connect rows that no local
    // event claims any more, and after a reinstall no local event claims anything: exporting first
    // would wipe the very history this sync exists to bring back.
    val weight = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.before(now),
                ascendingOrder = false,
                pageSize = 100,
            ),
        ).records
    }.onFailure { if (it is CancellationException) throw it }
        .getOrDefault(emptyList())
        .firstOrNull { it.metadata.dataOrigin.packageName != context.packageName }
        ?.weight?.inKilograms?.takeIf { it in PLAUSIBLE_KG }

    // Written down before the first row can land, and taken back once the recalculation has run:
    // see [owesRecalculation].
    val recalculationOwed = syncPrefs(context).getBoolean(KEY_RECALCULATION_OWED, false)
    syncPrefs(context).edit { putBoolean(KEY_RECALCULATION_OWED, true) }
    var importedHealthRecords = 0
    importedHealthRecords += imports.read(WeightRecord::class) { record, marker ->
        record.weight.inKilograms.takeIf { it in PLAUSIBLE_KG }?.let {
            measurementEntry(context, record.time, R.string.measurement_log_title, marker, record.zoneOffset)
                .copy(measurementWeightKg = it)
        }
    }
    importedHealthRecords += imports.read(HeightRecord::class) { record, marker ->
        (record.height.inMeters * 100.0).roundToInt().takeIf { it in 50..250 }?.let {
            measurementEntry(context, record.time, R.string.measurement_height_title, marker, record.zoneOffset)
                .copy(measurementHeightCm = it)
        }
    }
    importedHealthRecords += imports.readOwnHeartRate()
    importedHealthRecords += imports.readForeignDailyHeartRate()
    importedHealthRecords += imports.read(RestingHeartRateRecord::class) { record, marker ->
        record.beatsPerMinute.toDouble().takeIf { it in Vital.RESTING_HEART_RATE.valid }?.let { bpm ->
            vitalEntry(context, record.time, marker, Vital.RESTING_HEART_RATE, bpm, record.zoneOffset)
        }
    }
    importedHealthRecords += imports.read(HeartRateVariabilityRmssdRecord::class) { record, marker ->
        record.heartRateVariabilityMillis.takeIf { it in Vital.HRV.valid }?.let { ms ->
            vitalEntry(context, record.time, marker, Vital.HRV, ms, record.zoneOffset)
        }
    }
    importedHealthRecords += imports.readOwnSleep()
    importedHealthRecords += imports.readNightlySleep()
    importedHealthRecords += imports.read(HydrationRecord::class) { record, marker ->
        record.volume.inMilliliters.takeIf { it > 0.0 }?.let {
            measurementEntry(context, record.startTime, R.string.water_title, marker, record.startZoneOffset)
                .copy(kind = KIND_WATER, servingG = it)
        }
    }
    importedHealthRecords += imports.read(NutritionRecord::class) { record, marker ->
        val stated = statesMealNutrition(
            caffeineGrams = record.caffeine?.inGrams,
            calories = record.energy?.inKilocalories,
            proteinG = record.protein?.inGrams,
            fatG = record.totalFat?.inGrams,
            carbsG = record.totalCarbohydrate?.inGrams,
        )
        if (!stated) return@read null
        val nutrition = importedNutrition(
            caffeineGrams = record.caffeine?.inGrams,
            calories = record.energy?.inKilocalories,
            proteinG = record.protein?.inGrams,
            fatG = record.totalFat?.inGrams,
            carbsG = record.totalCarbohydrate?.inGrams,
        )
        val titleRes = if (nutrition.kind == KIND_CAFFEINE) R.string.caffeine_title else R.string.add_meal
        measurementEntry(context, record.startTime, titleRes, marker, record.startZoneOffset).copy(
            kind = nutrition.kind,
            title = record.name?.takeIf { it.isNotBlank() } ?: context.getString(titleRes),
            calories = nutrition.calories,
            proteinG = nutrition.proteinG,
            fatG = nutrition.fatG,
            carbsG = nutrition.carbsG,
            servingG = nutrition.caffeineMg,
            caffeineMg = nutrition.mealCaffeineMg,
            // Read back as the export writes them, so a meal restored after a reinstall keeps them.
            fiberG = record.dietaryFiber?.inGrams,
            sugarG = record.sugar?.inGrams,
            saturatedFatG = record.saturatedFat?.inGrams,
            sodiumMg = record.sodium?.inMilligrams,
            cholesterolMg = record.cholesterol?.inMilligrams,
        )
    }
    importedHealthRecords += imports.read(BloodPressureRecord::class) { record, marker ->
        val systolic = record.systolic.inMillimetersOfMercury
        val diastolic = record.diastolic.inMillimetersOfMercury
        val titleRes = R.string.direct_bluetooth_blood_pressure_title
        measurementEntry(context, record.time, titleRes, marker, record.zoneOffset)
            .copy(
                title = context.getString(titleRes) +
                    " ${systolic.roundToInt()}/${diastolic.roundToInt()}",
                measurementPayloadJson = JSONObject()
                    .put("schema", 1)
                    .put("systolicMmhg", systolic)
                    .put("diastolicMmhg", diastolic)
                    .toString(),
            )
    }
    importedHealthRecords += imports.read(OxygenSaturationRecord::class) { record, marker ->
        val percent = record.percentage.value
        val titleRes = R.string.direct_bluetooth_pulse_oximeter_title
        measurementEntry(context, record.time, titleRes, marker, record.zoneOffset)
            .copy(
                title = context.getString(titleRes) +
                    " ${percent.roundToInt()}%",
                measurementPayloadJson = JSONObject()
                    .put("schema", 1).put("spo2Percent", percent).toString(),
            )
    }
    importedHealthRecords += imports.read(BodyTemperatureRecord::class) { record, marker ->
        val celsius = record.temperature.inCelsius
        val titleRes = R.string.direct_bluetooth_temperature_title
        measurementEntry(context, record.time, titleRes, marker, record.zoneOffset)
            .copy(
                title = context.getString(titleRes) +
                    " ${celsius.pretty()}",
                measurementPayloadJson = JSONObject()
                    .put("schema", 1)
                    .put("temperatureCelsius", celsius)
                    .putTemperatureSite(record.measurementLocation)
                    .toString(),
            )
    }
    importedHealthRecords += imports.read(BloodGlucoseRecord::class) { record, marker ->
        val mmol = record.level.inMillimolesPerLiter
        // The record's relation to a meal decides which band the labs screen reads the figure
        // against; dropped, a fasting 7.5 mmol/L would be judged as a value after lunch.
        val fasting = record.relationToMeal == BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        val titleRes = R.string.direct_bluetooth_glucose_title
        measurementEntry(context, record.time, titleRes, marker, record.zoneOffset).copy(
            title = context.getString(titleRes) + " ${mmol.pretty()}",
            measurementPayloadJson = JSONObject()
                .put("schema", 1)
                .put("concentrationMmolL", mmol)
                .apply { if (fasting) put(FASTING_KEY, true) }
                .toString(),
        )
    }
    importedHealthRecords += imports.read(BodyFatRecord::class) { record, marker ->
        val titleRes = R.string.direct_bluetooth_body_composition_title
        record.percentage.value.takeIf { it in 1.0..80.0 }?.let {
            measurementEntry(context, record.time, titleRes, marker, record.zoneOffset)
                .copy(bodyFatPercent = it)
        }
    }
    val syncState = syncPrefs(context)
    val sessionsReadWhole = imports.readsWhole(ExerciseSessionRecord::class)
    // Written down before the read as well as after it. A whole read saves its rows a page at a
    // time, and one that fails on a later page has left sessions standing that the next exchange
    // will not count as new.
    if (sessionsReadWhole) syncState.edit { putBoolean(KEY_SESSION_DETAILS_OWED, true) }
    val importedWorkouts = imports.read(ExerciseSessionRecord::class) { session, marker ->
        if (session.endTime <= session.startTime) {
            null
        } else {
            val minutes = Duration.between(session.startTime, session.endTime)
                .toMillis().coerceAtLeast(60_000) / 60_000.0
            val ownRecord = session.metadata.dataOrigin.packageName == context.packageName
            val loggedAt = importedSessionInstant(session.startTime, session.endTime, ownRecord)
            // The offset of the end the row is filed at, so the day is the one that end was lived on.
            val loggedOffset = if (ownRecord) session.endZoneOffset else session.startZoneOffset
            val filedAs = importedSessionKind(session.exerciseType, ownRecord)
            JournalEntry(
                day = importedDay(loggedAt, loggedOffset).toString(),
                occurredAt = loggedAt.toEpochMilli(),
                kind = KIND_ACTIVITY,
                title = session.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.activity_synced),
                // The export writes the row's note into the session, so a reinstall reads it back
                // from there.
                note = session.notes.orEmpty(),
                calories = netActivityCalories(
                    filedAs.met,
                    profile.restingKcalPerHour(weight ?: profile.weightKg),
                    minutes,
                ),
                activityCode = filedAs.activityCode,
                met = filedAs.met,
                durationMinutes = minutes,
                // Named as every other row the exchange brings in is ([measurementEntry]): left
                // empty, a session from a watch would read as one typed here to whatever asked the
                // column alone.
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = marker,
                zoneOffsetSeconds = loggedOffset?.totalSeconds,
            )
        }
    }
    // Only when a session may be standing without its details. They are a year of six record types
    // from every app, too much to read at every return to the app for as long as one imported
    // session stands in the journal. Decided here and written down at once, ahead of the imports
    // below, any of which may end the exchange.
    val detailsOwed = owesSessionDetails(
        sessionsReadWhole,
        importedWorkouts,
        owedBefore = syncState.getBoolean(KEY_SESSION_DETAILS_OWED, false),
    )
    if (detailsOwed) syncState.edit { putBoolean(KEY_SESSION_DETAILS_OWED, true) }
    importedHealthRecords += importCycleRecords(context, db, imports, profile, now)
    importedHealthRecords += importBodyRecords(context, imports)
    importedHealthRecords += importSessionRecords(context, imports, now)
    // After the sessions are standing, so the detail records have rows to land on. A read of them
    // that fails stays owed and is reported once the export has run: ending the exchange here would
    // let a source whose records it cannot get through keep every later exchange from publishing or
    // withdrawing anything.
    val details = ExchangeSteps()
    if (detailsOwed) {
        details.attempt {
            importActivityDetails(db, client, now)
            syncState.edit { remove(KEY_SESSION_DETAILS_OWED) }
        }
    }
    // Only when the import wrote or removed a row: nothing else in an exchange moves what the
    // recalculation works out, and it reads the journal whole to find that out. Nor is it the
    // journal's upkeep: a phone without Health Connect never runs it. An exchange that never
    // reached this line still owes it, whatever this one counted ([owesRecalculation]).
    val journalMoved = importedHealthRecords + importedWorkouts + imports.removedRows > 0
    if (owesRecalculation(journalMoved, recalculationOwed)) db.recalculateJournal()
    syncState.edit { remove(KEY_RECALCULATION_OWED) }

    markRestoreDone(syncState, now)
    val exportedJournalRecords = exportLocalHealth(context, db, client, now)
    details.rethrowFirst()
    return HealthSyncResult(
        weightKg = weight,
        exportedJournalRecords = exportedJournalRecords,
        importedHealthRecords = importedHealthRecords,
        importedWorkouts = importedWorkouts,
        exportedStepDays = stepSync.exportedDays,
        externallyCoveredStepDays = stepSync.externallyCoveredDays,
        importedStepDays = stepSync.importedDays,
        stepsReadable = stepSync.readable,
        stepsWritable = stepSync.writable,
        uncountedStepSource = stepSync.uncountedSource,
    )
}

/**
 * One record type at a time, from Health Connect into the journal.
 *
 * Rows an earlier install published are adopted rather than skipped. After a reinstall the local
 * database is empty while Health Connect still holds the history, and reading those rows back is
 * the only way to recover it; an adopted row loses its Health Connect provenance so the next export
 * republishes it as a local event, under this journal's own tag ([journalGeneration]), and the
 * sweep after that withdraws the earlier journal's copy. A row whose client id still matches a
 * live local event, or that lands on an instant a local event already occupies, is that event's
 * own copy and is left alone — but for the cycle's rows, where one moment holds several events and
 * only the row that says the same thing is the record's own ([standsFor]), and for a moment the
 * earlier journal published more than once ([restoresAnotherRow]).
 * What this install wrote itself is past adopting ([adoptablePublishedRecord]): nothing claiming it
 * now is a deletion made while Health Connect was out of reach, and the export's sweep in the same
 * run withdraws the record instead of the import taking it back in.
 *
 * A row deleted here is never taken back in, whoever wrote it: [JournalTombstone] outlives the row
 * and every record it stands for is skipped. For a record of ours the export's sweep withdraws it in
 * the same run, so the deletion reaches both stores; a record another app owns can only be refused,
 * because Health Connect lets no app delete what it did not write.
 *
 * Most types are read for what changed, not for the year. The first exchange reads the whole window
 * and keeps the change token Health Connect issued just before it; every later one hands the token
 * back and receives the records upserted or deleted since, in pages, through the same mapping and
 * the same [place] the full read uses. The window still bounds what a changed record may put in the
 * journal: a row dated before its start is history the window has left behind and is dropped, as
 * [ImportWindow] says — a source app rewriting its history would otherwise pour years of records,
 * and plans nobody trains from, into a journal that never sweeps an imported row. A row dated after
 * the exchange's snapshot of now is kept, because the feed has moved past it and would never offer
 * it again, and a full read would have met it on a later day. A record another app deleted takes
 * the row it was imported into with it — and leaves no tombstone, because the deletion was theirs
 * and a record they write again is history this journal has never refused. A record of this app's
 * own in the feed is the last export coming back and is passed over. A token unused for thirty days
 * is one Health Connect has forgotten: the answer says so, the window is read whole once more and a
 * fresh token kept. An update reads whole once too, and asks the token it retires for the deletions
 * that read cannot see ([retireChangeTokensIfAppChanged]). A reinstall or a cleared store has no
 * tokens and reads whole. Sleep and heart rate never take a token —
 * [readNightlySleep] and [readForeignDailyHeartRate] choose among a whole window's records, and
 * learn of a deletion at the source in ways of their own ([forgetDeletedNights],
 * [forgetVanishedHeartRates]) — and the step-day totals are aggregated in `syncStepDays`; see
 * [AGGREGATED_IMPORT_TYPES].
 *
 * Limitation: a withdrawal that failed is retried only by the next sync finding the record
 * unclaimed, so a deletion made while Health Connect is unreachable is mirrored late rather than at
 * once.
 */
internal class HealthImport(
    private val context: android.content.Context,
    private val db: StepsDb,
    private val client: HealthConnectClient,
    private val now: Instant,
) {
    private val ownPackageName = context.packageName

    /** The adoption instant, and one change token per type that is read for what changed. */
    private val prefs = syncPrefs(context)
    private val adoptedAt = Instant.ofEpochMilli(prefs.getLong(KEY_ADOPTED_AT_MS, 0L))
    private val generation = journalGeneration(context)

    /** How many records each published moment has brought home so far; see [restoresAnotherRow]. */
    private val broughtHome = mutableMapOf<PublishedMoment, Int>()

    /** What each service's export files have covered: read once an exchange, for a service a new record names. */
    private val filed = HashMap<String, FiledFacts>()

    /** Rows this import took out of the journal because their source withdrew the record. */
    var removedRows = 0
        private set

    init {
        // An update reads the year whole once, so a mapping it changed reaches the rows imported
        // before it; see retireChangeTokensIfAppChanged.
        retireChangeTokensIfAppChanged(prefs, appVersion(context).code)
    }

    /** The year behind now: what every type reads unless its caller reaches elsewhere. */
    private val yearWindow = ImportWindow(now.minus(Duration.ofDays(HEALTH_IMPORT_DAYS)), now)
    private val range = yearWindow.filter()

    /** Whether [recordType] is about to be read as a whole window rather than for what changed. */
    fun readsWhole(recordType: KClass<out Record>): Boolean {
        val token = prefs.getString(changesTokenKey(recordType), null)
        return importPlan(recordType, originFiltered = false, storedToken = token) is ImportPlan.Full
    }

    suspend fun <T : Record> read(
        recordType: KClass<T>,
        window: ImportWindow = yearWindow,
        origins: Set<DataOrigin> = emptySet(),
        toEntry: (T, String) -> JournalEntry?,
    ): Int = readEach(recordType, window, origins) { record, marker ->
        listOfNotNull(toEntry(record, marker))
    }

    /**
     * The general shape of [read]: one record, any number of rows. A period is a start and an end;
     * each produced row must carry its own marker — the first one the plain marker, every other one
     * a suffixed variant — because every guard in [place] runs per row.
     *
     * Whether the type is read whole or only for what changed is decided here, from the token the
     * last exchange kept, so a caller states only how a record becomes rows.
     */
    suspend fun <T : Record> readEach(
        recordType: KClass<T>,
        window: ImportWindow = yearWindow,
        origins: Set<DataOrigin> = emptySet(),
        toEntries: suspend (T, String) -> List<JournalEntry>,
    ): Int {
        if (!supportsRecordType(recordType)) return 0
        val tokenKey = changesTokenKey(recordType)
        val plan = importPlan(recordType, origins.isNotEmpty(), prefs.getString(tokenKey, null))
        return when (plan) {
            is ImportPlan.Incremental -> readChanged(recordType, plan.token, tokenKey, window, toEntries)
            is ImportPlan.Full -> readWindow(recordType, plan.seedToken, tokenKey, window, origins, toEntries)
        }
    }

    /**
     * Only what changed since the token. A token Health Connect has forgotten sends the type back
     * through the whole window, which issues a fresh one; a grant since withdrawn leaves the token
     * where it is, so the changes are not lost to the day the grant returns.
     */
    private suspend fun <T : Record> readChanged(
        recordType: KClass<T>,
        token: String,
        tokenKey: String,
        window: ImportWindow,
        toEntries: suspend (T, String) -> List<JournalEntry>,
    ): Int {
        val feed = readChangeFeed(token) { changesPage(it) }
        return when (feed) {
            ChangeFeed.Denied -> 0

            ChangeFeed.Expired -> {
                prefs.edit { remove(tokenKey) }
                readWindow(
                    recordType = recordType,
                    seedToken = true,
                    tokenKey = tokenKey,
                    window = window,
                    origins = emptySet(),
                    toEntries = toEntries,
                )
            }

            is ChangeFeed.Changes -> {
                val imported = applyChanges(recordType, feed.changes, window, toEntries)
                prefs.edit { putString(tokenKey, feed.nextToken) }
                imported
            }
        }
    }

    /** One page of a type's feed, in the shape [readChangeFeed] follows. */
    private suspend fun changesPage(token: String): ChangesPage = client.getChanges(token).let {
        ChangesPage(it.changes, it.nextChangesToken, hasMore = it.hasMore, expired = it.changesTokenExpired)
    }

    /**
     * The whole window, page by page. The token is asked for before the read and kept only after
     * it, so a record written between the two is met by both — [place] does the same thing twice —
     * and a read that was refused leaves no token that would let the next exchange skip the window.
     * Nor does one that ran after the app had left the foreground, where Health Connect hands over
     * this app's own records and calls it the whole window ([requireEveryAppReadable]).
     */
    @Suppress("SwallowedException")
    private suspend fun <T : Record> readWindow(
        recordType: KClass<T>,
        seedToken: Boolean,
        tokenKey: String,
        window: ImportWindow,
        origins: Set<DataOrigin>,
        toEntries: suspend (T, String) -> List<JournalEntry>,
    ): Int {
        val token = if (seedToken) changesTokenFor(client, recordType) else null
        // After the fresh token is issued, so no deletion falls between the two feeds, and before
        // the read, so a record deleted and since written again is met by the pages below.
        if (seedToken) forgetDeletedSinceRetired(tokenKey)
        var imported = 0
        var pageToken: String? = null
        do {
            // A type the person did not allow reading imports nothing; the other types still do.
            val response = try {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = window.filter(),
                        dataOriginFilter = origins,
                        ascendingOrder = true,
                        pageSize = 1_000,
                        pageToken = pageToken,
                    ),
                )
            } catch (denied: SecurityException) {
                return imported
            }
            // Mapped first — a mapping may ask Health Connect for more, which must not happen under
            // the database's write lock — then placed in one transaction: one commit a page rather
            // than one a record, and one re-read for every Flow over the journal rather than a thousand.
            val rows = response.records.flatMap { record -> toEntries(record, markerFor(record)).map { record to it } }
            imported += db.withTransaction { rows.count { (record, entry) -> place(record, entry) } }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        if (token != null) {
            requireEveryAppReadable()
            prefs.edit {
                putString(tokenKey, token)
                remove(retiredTokenKey(tokenKey))
            }
        }
        return imported
    }

    /**
     * The deletions a whole read cannot see, asked of the token an update retired.
     *
     * Only the deletions are taken: the read that follows meets everything that was written. The
     * token moves on rather than away, so a read that fails after this resumes from here, and it
     * goes when the fresh token is kept. A grant since withdrawn leaves it waiting, as [readChanged]
     * leaves a live one; a token Health Connect has forgotten has nothing left to say.
     */
    private suspend fun forgetDeletedSinceRetired(tokenKey: String) {
        val retiredKey = retiredTokenKey(tokenKey)
        val retired = prefs.getString(retiredKey, null) ?: return
        when (val feed = readChangeFeed(retired) { changesPage(it) }) {
            ChangeFeed.Denied -> Unit

            ChangeFeed.Expired -> prefs.edit { remove(retiredKey) }

            is ChangeFeed.Changes -> {
                // In one commit, as [applyChanges] places a feed: the nights go this way on every
                // exchange, and a commit per deletion would be one re-read of every Flow over the
                // journal for each.
                val deleted = feed.changes.filterIsInstance<DeletionChange>().map { it.recordId }
                db.withTransaction { forgetDeleted(deleted) }
                prefs.edit { putString(retiredKey, feed.nextToken) }
            }
        }
    }

    /**
     * The changes in their order: each upsert through the caller's mapping, each deletion to
     * [forgetDeleted].
     *
     * But for a record of this app's own, which is the last export coming back: the feed returns
     * every record the exchange or the mirror wrote as changed. The row each was built from is in
     * the journal already, or was deleted from it on purpose, so placing it would cost three
     * lookups a record and could only do harm — the merge would put a body value back into the
     * measurement it had just been removed from. A reinstall's restore is a whole read and never
     * comes through here.
     *
     * Mapped first and placed in one transaction, as [readWindow] places a page: a mapping may ask
     * Health Connect for more, which must not happen under the database's write lock, and a commit
     * per change would be one re-read of every Flow over the journal for each record another app
     * had touched. Deletions side by side are one step, one pass over the journal for the lot
     * ([forgetDeleted]), and the step keeps their place among the upserts.
     */
    private suspend fun <T : Record> applyChanges(
        recordType: KClass<T>,
        changes: List<Change>,
        window: ImportWindow,
        toEntries: suspend (T, String) -> List<JournalEntry>,
    ): Int {
        val steps = mutableListOf<suspend () -> Int>()
        // The ids the last step forgets, for as long as no upsert has been placed after it.
        var deletions: MutableList<String>? = null
        for (change in changes) {
            when (change) {
                is UpsertionChange -> {
                    val ownExport = change.record.metadata.dataOrigin.packageName == ownPackageName
                    if (!ownExport) {
                        steps.add(placeChanged(recordType, change.record, window, toEntries))
                        deletions = null
                    }
                }

                is DeletionChange -> {
                    if (deletions == null) {
                        val run = mutableListOf<String>()
                        steps.add {
                            forgetDeleted(run)
                            0
                        }
                        deletions = run
                    }
                    deletions.add(change.recordId)
                }
            }
        }
        return if (steps.isEmpty()) 0 else db.withTransaction { steps.sumOf { it() } }
    }

    /**
     * One upserted record, through the same mapping and the same guards as a record the window
     * read, as the step that places it. A row dated before the window's start is history it has
     * left behind and is dropped, so a source app rewriting years of records cannot put them all in
     * the journal at once; a row dated after the exchange's snapshot of now is kept, because the feed
     * has moved past it and a full read would have met it on a later day — see [ImportWindow].
     */
    private suspend fun <T : Record> placeChanged(
        recordType: KClass<T>,
        changed: Record,
        window: ImportWindow,
        toEntries: suspend (T, String) -> List<JournalEntry>,
    ): suspend () -> Int {
        val record = recordType.java.takeIf { it.isInstance(changed) }?.cast(changed) ?: return { 0 }
        val rows = toEntries(record, markerFor(record)).filterNot { window.leftBehind(it.occurredAt) }
        return { rows.count { place(record, it) } }
    }

    /**
     * The rows the records deleted at their source were imported into, removed — and without a
     * tombstone. A tombstone says "deleted here", which this was not: the deletion is the source
     * app's, and a record it later writes again, under a new id, is history this journal has never
     * refused. A row that was adopted has no marker any more and is left alone: it is this install's
     * own event. So is a row corrected by hand, whose figure is the person's own now.
     */
    private suspend fun forgetDeleted(recordIds: List<String>) {
        if (recordIds.isEmpty()) return
        db.dao().importedRowsOf(recordIds.toSet()).forEach { forget(it) }
    }

    private suspend fun forget(rowId: Long) {
        db.dao().deleteJournalEntry(rowId)
        removedRows++
    }

    /**
     * The same for the night, whose type is never read by a token ([AGGREGATED_IMPORT_TYPES]): no
     * feed would tell this journal that a session has been deleted at its source, and the night
     * would stand for good. It keeps a token all the same, the way an update leaves one behind —
     * retired from the start, asked for the deletions and for nothing else — so a night goes when
     * Health Connect says its session has, and never for merely not being among the records read.
     */
    private suspend fun forgetDeletedNights() {
        val tokenKey = changesTokenKey(SleepSessionRecord::class)
        val retiredKey = retiredTokenKey(tokenKey)
        if (prefs.getString(retiredKey, null) == null) {
            val issued = changesTokenFor(client, SleepSessionRecord::class) ?: return
            prefs.edit { putString(retiredKey, issued) }
        }
        forgetDeletedSinceRetired(tokenKey)
    }

    /**
     * The day's heart rate is filed under its day and names no record, so no feed can say it has
     * gone: the days that have lost every sample since the last exchange looked ([heartDaysLost])
     * lose their row.
     *
     * Checked only once something is about to go for being absent: read after the app had left the
     * foreground, the window holds this app's records and nobody else's ([requireEveryAppReadable]).
     */
    private suspend fun forgetVanishedHeartRates(lost: Set<LocalDate>) {
        val vanished = vanishedDailyHeartRates(lost.flatMap { db.dao().journalOnNow(it.toString()) }, lost)
        if (vanished.isEmpty()) return
        requireEveryAppReadable()
        vanished.forEach { forget(it.id) }
    }

    /**
     * Whether this Health Connect knows the type at all. Mindfulness and planned-exercise records
     * arrived in later platform releases, and connect-client answers a read of a type the module
     * lacks with an IllegalArgumentException rather than an empty page — one that would end the
     * whole synchronisation on a de-Googled Android 14, before the export and the restore flag it
     * gates, so such a phone would never write a single record. The export side gates itself the
     * same way.
     */
    @OptIn(ExperimentalMindfulnessSessionApi::class)
    private fun supportsRecordType(recordType: KClass<*>): Boolean = when (recordType) {
        MindfulnessSessionRecord::class -> client.supportsFeature(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
        PlannedExerciseSessionRecord::class -> client.supportsFeature(HealthConnectFeatures.FEATURE_PLANNED_EXERCISE)
        else -> true
    }

    private fun markerFor(record: Record): String =
        "$HEALTH_IMPORT_MARKER_PREFIX${record.metadata.dataOrigin.packageName}:${record.metadata.id}"

    private suspend fun <T : Record> store(record: T, toEntry: (T, String) -> JournalEntry?): Boolean {
        val entry = toEntry(record, markerFor(record)) ?: return false
        return place(record, entry)
    }

    // One guard per reason a row is already accounted for. The row's key is its marker.
    @Suppress("ReturnCount")
    private suspend fun place(record: Record, entry: JournalEntry): Boolean {
        val origin = record.metadata.dataOrigin.packageName
        val marker = entry.healthConnectRecordKey ?: return false
        // The row this record was imported into was deleted here. Nothing about the record says so
        // — only the tombstone does — and reading it back in is the resurrection it exists to stop.
        if (db.dao().isTombstoned(marker)) return false
        val alreadyImported = db.dao().hasImportedEntry(marker)
        if (origin != ownPackageName) {
            if (alreadyImported) return refreshImported(entry, marker)
            // The same history by the other road: the service's own export file brought it first.
            if (broughtByAFile(entry)) return false
            db.dao().saveJournalEntry(entry)
            return true
        }
        if (alreadyImported) return false
        // Two ways an event of ours is already standing here: the record still names its row —
        // which only a record of this journal can ([publishedRowId]) — or a row occupies its
        // instant. Both go through the merge, which acts only on a body value the standing event
        // does not hold and leaves everything else exactly as it was. A cycle row that says
        // something else is not the record's own ([standsFor]), and a moment an earlier journal
        // published twice comes home as two rows ([restoresAnotherRow]).
        val alreadyStanding = stillLocal(record.metadata.clientRecordId, entry) || standsAlready(record, entry)
        if (alreadyStanding) return mergeBodyValues(entry)
        // Nothing stands for a row this install published itself: the event was deleted while
        // Health Connect could not be reached, and taking it back in would undo that deletion. The
        // export's sweep withdraws the record instead. An install with no adoption instant on
        // record yet reaches the same conclusion from the tombstone the deletion left.
        if (deletedHere(record.metadata.clientRecordId, entry)) return false
        if (!adoptablePublishedRecord(record.metadata.lastModifiedTime, adoptedAt)) return false
        db.dao().saveJournalEntry(entry.adopted())
        return true
    }

    /** Whether a file of the service whose app published [entry] has already covered it ([FiledFacts]). */
    private suspend fun broughtByAFile(entry: JournalEntry): Boolean {
        val service = entry.publishingService() ?: return false
        val facts = filed.getOrPut(service) {
            FiledFacts(db.dao().rowsMarked("$FILE_IMPORT_MARKER_PREFIX$service:"))
        }
        return facts.cover(entry)
    }

    /** Whether a row on the record's own moment already answers for it; counted, as [restoresAnotherRow] asks. */
    private suspend fun standsAlready(record: Record, entry: JournalEntry): Boolean {
        val standing = db.dao().journalAt(entry.kind, entry.occurredAt).count { it.standsFor(entry) }
        val moment = PublishedMoment(
            // The id without the row's number: the family and the journal that published it.
            journal = record.metadata.clientRecordId?.substringBeforeLast(':').orEmpty(),
            kind = entry.kind,
            occurredAt = entry.occurredAt,
            said = entry.cycleLog(),
        )
        val seen = broughtHome[moment] ?: 0
        broughtHome[moment] = seen + 1
        return !restoresAnotherRow(standing, seen, restoring = adoptedAt == Instant.EPOCH)
    }

    /**
     * Keeps a row imported from another app in step with the app that owns it.
     *
     * Health Connect keeps a record's id across an edit, so the marker matches again and the value
     * this journal read the first time would otherwise stand forever. The copy is found by the
     * marker it was filed under and rewritten from the record, which is the owner's figure and not
     * this app's. A record whose time has moved to another day, and one the source app has deleted,
     * are past what a marker alone can find.
     */
    private suspend fun refreshImported(imported: JournalEntry, marker: String): Boolean {
        val local = db.dao().importedEntry(marker)
        val refreshed = local?.refreshedFromImport(imported) ?: return false
        db.dao().saveJournalEntry(refreshed)
        return true
    }

    /**
     * What a record does when a local event already stands on its instant.
     *
     * That event is normally the one that published it, and the record is dropped. But one event
     * publishes several body records at once — onboarding writes weight with height, a scale adds
     * body fat — and each of them comes back on its own. Restoring only the first would leave the
     * rest missing from the journal, which the export would then read as "deleted" and sweep out of
     * Health Connect as well, so a value the event does not hold is merged back into it instead.
     */
    private suspend fun mergeBodyValues(imported: JournalEntry): Boolean {
        val carried = imported.bodyValueColumns()
        // Found by its instant and not through its day: the event may have been filed under another
        // day than this record works out to, when its records left in more than one zone.
        val local = if (carried.isEmpty()) {
            null
        } else {
            db.dao().journalAt(imported.kind, imported.occurredAt).firstOrNull()
        }
        if (local == null || local.bodyValueColumns().containsAll(carried)) return false
        db.dao().saveJournalEntry(local.withBodyValuesFrom(imported))
        return true
    }

    /**
     * One sleep record per night — the longest one offered.
     *
     * A single night reaches Health Connect several times over: a watch writes its own session, the
     * app it pairs with writes another, and a phone that guessed from screen use writes a third.
     * Imported one for one they would become four overlapping entries for the same night —
     * 21:05→6:25 next to 23:54→4:42 — and no reading of that list is the truth. The longest span
     * wins because the segmented ones are pieces of it, and a night that already carries a measured
     * record is left alone — unless that record is an earlier reading of the winner, or an import
     * the winner has outgrown ([sleepRowsToReplace]). A phone-usage guess loses to anything
     * measured and is removed when one arrives. This app's own records are not candidates:
     * [readOwnSleep] brings those back after a reinstall.
     */
    @Suppress("SwallowedException")
    suspend fun readNightlySleep(): Int {
        // Before the nights are read, so a night whose session was deleted can take another.
        forgetDeletedNights()
        val perNight = mutableMapOf<LocalDate, SleepSessionRecord>()
        var pageToken: String? = null
        do {
            // Sleep the person did not allow reading imports nothing; the other types still do.
            val response = try {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = range,
                        ascendingOrder = true,
                        pageSize = 1_000,
                        pageToken = pageToken,
                    ),
                )
            } catch (denied: SecurityException) {
                return 0
            }
            response.records.forEach { session ->
                if (session.endTime <= session.startTime) return@forEach
                // A phone-usage guess this app exported can run longer than the watch's measured
                // night and would win the night from it, so the measured sleep would never come in.
                if (session.metadata.dataOrigin.packageName == ownPackageName) return@forEach
                // The morning it ended on where it was slept, not where the phone has since been taken.
                val night = importedDay(session.endTime, session.endZoneOffset)
                val known = perNight[night]
                if (known == null || session.duration() > known.duration()) perNight[night] = session
            }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return perNight.count { (night, session) -> storeNightSleep(night, session) }
    }

    private suspend fun storeNightSleep(night: LocalDate, session: SleepSessionRecord): Boolean {
        val recorded = db.dao().sleepOn(night.toString())
        val replaced = sleepRowsToReplace(
            recorded,
            markerFor(session),
            session.startTime.toEpochMilli(),
            session.endTime.toEpochMilli(),
        ) ?: return false
        val imported = store(session) { record, marker ->
            measurementEntry(context, record.endTime, R.string.measurement_sleep_title, marker, record.endZoneOffset)
                .copy(
                    sleepStartAt = record.startTime.toEpochMilli(),
                    sleepEndAt = record.endTime.toEpochMilli(),
                )
        }
        if (imported) replaced.forEach { db.dao().deleteJournalEntry(it.id) }
        return imported
    }

    /** Own heart-rate rows carry one sample each, so they restore one for one after a reinstall. */
    suspend fun readOwnHeartRate(): Int = read(
        recordType = HeartRateRecord::class,
        origins = setOf(DataOrigin(ownPackageName)),
    ) { record, marker ->
        val sample = record.samples.singleOrNull() ?: return@read null
        val titleRes = R.string.measurement_heart_rate_title
        sample.beatsPerMinute.toInt().takeIf { it in 30..250 }?.let {
            measurementEntry(context, sample.time, titleRes, marker, record.startZoneOffset)
                .copy(measurementHeartRateBpm = it)
        }
    }

    /**
     * Own sleep rows, one per record, back after a reinstall. Read before [readNightlySleep], so a
     * night this app had already recorded is a night that one leaves alone; otherwise a row of ours
     * shorter than a watch's session for the same night would never be restored, and the second
     * synchronisation would sweep the published copy — the last one — from Health Connect.
     */
    suspend fun readOwnSleep(): Int = read(
        recordType = SleepSessionRecord::class,
        origins = setOf(DataOrigin(ownPackageName)),
    ) { record, marker ->
        if (record.endTime <= record.startTime) {
            null
        } else {
            val titleRes = R.string.measurement_sleep_title
            measurementEntry(context, record.endTime, titleRes, marker, record.endZoneOffset).copy(
                sleepStartAt = record.startTime.toEpochMilli(),
                sleepEndAt = record.endTime.toEpochMilli(),
                note = record.notes.orEmpty(),
                // Still a guess once it is home, which [adopted] keeps: restored as a night typed
                // in, it would stand against every measured night after it ([sleepRowsToReplace]).
                measurementSource = if (record.metadata.isPhoneSleepEstimate()) PHONE_ESTIMATE else "HEALTH_CONNECT",
            )
        }
    }

    /**
     * One foreign heart rate per day, keyed by the day so repeated syncs do not pile up copies. A
     * worn wearable writes a sample every few seconds and the journal is a day view, so per-minute
     * history is not imported. The day's row follows the latest sample each exchange finds
     * ([refreshedDailyHeartRate]), so it settles on the last reading of the day.
     */
    @Suppress("SwallowedException")
    suspend fun readForeignDailyHeartRate(): Int {
        val since = now.minus(Duration.ofDays(HEART_RATE_IMPORT_DAYS))
        val perDay = mutableMapOf<LocalDate, Pair<HeartRateRecord.Sample, ZoneOffset?>>()
        var pageToken: String? = null
        do {
            // Heart rate the person did not allow reading imports nothing; the rest still does.
            val response = try {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(since, now),
                        ascendingOrder = true,
                        pageSize = 1_000,
                        pageToken = pageToken,
                    ),
                )
            } catch (denied: SecurityException) {
                return 0
            }
            response.records
                .filter { it.metadata.dataOrigin.packageName != ownPackageName }
                .forEach { keepLatestPerDay(it, perDay) }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        // The day the window opens in is read only in part, so its silence says nothing — and with each
        // record filed on its own clock, that is the latest day the opening instant falls on anywhere.
        val firstDay = importedDay(since, ZoneOffset.MAX)
        val offered = heartDaysFile(context)
        forgetVanishedHeartRates(heartDaysLost(heartDaysOfferedIn(offered), perDay.keys, firstDay))
        keepHeartDaysOffered(offered, perDay.keys)
        return perDay.count { (day, latest) -> storeDailyHeartRate(day, latest.first, latest.second) }
    }

    /**
     * Each sample under the day it was lived on where it was recorded ([importedDay]), as every
     * other import files a record. Worked out in the phone's zone at the exchange, a whole read
     * after a flight would move the evening's samples onto the next day, and a day left with none
     * would lose its row.
     */
    private fun keepLatestPerDay(
        record: HeartRateRecord,
        perDay: MutableMap<LocalDate, Pair<HeartRateRecord.Sample, ZoneOffset?>>,
    ) {
        record.samples.forEach { sample ->
            val day = importedDay(sample.time, record.startZoneOffset)
            val known = perDay[day]?.first
            if (known == null || sample.time > known.time) perDay[day] = sample to record.startZoneOffset
        }
    }

    private suspend fun storeDailyHeartRate(
        day: LocalDate,
        sample: HeartRateRecord.Sample,
        offset: ZoneOffset?,
    ): Boolean {
        val bpm = sample.beatsPerMinute.toInt().takeIf { it in 30..250 } ?: return false
        val marker = dailyHeartRateMarker(day)
        // This day's rate was deleted here; it is filed under the day, so it would otherwise be
        // imported again on the next sync and again after every deletion.
        // Either spelling: the standing-row lookup below also accepts the package-qualified marker
        // some stored rows carry, so the deletion check has to reach just as far.
        if (db.dao().isDailyHeartRateTombstoned(marker, day.toString())) return false
        // Whichever wearable won the day: the day itself is what may hold one of these.
        val standing = db.dao().journalOnNow(day.toString()).firstOrNull {
            isDailyHeartRateMarker(it.healthConnectRecordKey, day)
        }
        if (standing != null) {
            val refreshed = refreshedDailyHeartRate(
                standing,
                occurredAt = sample.time.toEpochMilli(),
                bpm = bpm,
            ) ?: return false
            db.dao().saveJournalEntry(refreshed)
            return true
        }
        db.dao().saveJournalEntry(
            measurementEntry(context, sample.time, R.string.measurement_heart_rate_title, marker, offset)
                .copy(measurementHeartRateBpm = bpm),
        )
        return true
    }

    /**
     * True when the local event that published this row is still in the journal.
     *
     * The id alone is no proof after a reinstall: Room numbers a fresh journal from 1 again, so the
     * previous install's "pwnfit-journal-weight:1" would match whatever the new install wrote first
     * — which is exactly the onboarding measurement — and the old weight would be skipped as
     * "still local" forever. So the id is read only under this journal's tag ([publishedRowId]),
     * and the times are still held to agree, which costs nothing — and on the midday the cycle's
     * rows of an earlier day all share, so is what the row says ([standsFor]).
     */
    private suspend fun stillLocal(clientRecordId: String?, imported: JournalEntry): Boolean {
        val id = publishedRowId(clientRecordId, generation) ?: return false
        val local = db.dao().journalEntry(id) ?: return false
        return publishedCopyOf(local, imported, now) && local.standsFor(imported)
    }

    /** Whether the local row this record of ours was published from has since been deleted here. */
    private suspend fun deletedHere(clientRecordId: String?, entry: JournalEntry): Boolean {
        val key = publishedRecordTombstoneKey(clientRecordId, generation, entry.kind, entry.day) ?: return false
        return db.dao().isTombstoned(key)
    }
}

/**
 * The client record id of the record a row is published as: the family's prefix, the journal's tag
 * ([journalGeneration]) and the row's number, in that order, so that a number another journal gave
 * out names another record. A record keyed on a day — the steps, the day's energy, a period by the
 * day it began — carries no tag: a day is the same day in every journal.
 */
internal fun publishedClientId(prefix: String, generation: String, rowId: Long): String = "$prefix$generation:$rowId"

/** The same for an exporter that holds a context and no tag. */
internal fun publishedClientId(context: android.content.Context, prefix: String, rowId: Long): String =
    publishedClientId(prefix, journalGeneration(context), rowId)

/**
 * The row of the journal tagged [generation] that a client record id of ours was written for, which
 * is what makes one deleted row nameable in both stores. Null for a record of an earlier journal:
 * its number says nothing about any row here, and reading it as one would let a tombstone left for
 * this journal's row 7 refuse the previous journal's record 7 its way home.
 */
internal fun publishedRowId(clientRecordId: String?, generation: String): Long? =
    clientRecordId
        ?.takeIf { it.substringBeforeLast(':').endsWith(":$generation") }
        ?.substringAfterLast(':')
        ?.toLongOrNull()

/**
 * The tombstone a deletion here would have left for this record of ours, or null when nothing here
 * can be its grave.
 *
 * Every client record id but the period's ends in the id of the row it was published from. The
 * period's ends in its start day — one record for two rows, carrying the id of neither — so
 * [publishedRowId] reads nothing out of it, and without a grave of its own a period start or end
 * deleted here would be adopted back and re-exported on every sync for good. For that record the
 * grave is the one [periodTombstoneKey] digs: the row's own kind and day, which the record's
 * interval names as surely as an id would, and names for the end row as well as the start. A record
 * of an earlier journal has no grave by its number either, whatever this journal has deleted under
 * the same one.
 */
internal fun publishedRecordTombstoneKey(
    clientRecordId: String?,
    generation: String,
    kind: String,
    day: String,
): String? = publishedRowId(clientRecordId, generation)?.let(::journalRecordKey) ?: periodTombstoneKey(kind, day)

/**
 * Whether [imported] reads like [local]'s own Health Connect copy rather than an id collision.
 *
 * Point records come back on the instant they were exported at, so the times match exactly. A
 * session comes back somewhere inside the interval [JournalEntry.sessionInterval] published, and
 * which end that is depends on the row: a workout typed in afterwards is timestamped at its end, a
 * live Bluetooth one at its start, and an interval still running when it was exported was cut off
 * wherever that export found it. Anywhere within the interval is the same event.
 */
internal fun publishedCopyOf(local: JournalEntry, imported: JournalEntry, now: Instant): Boolean {
    val session = local.sessionInterval(now)
    val withinSession = session != null &&
        imported.occurredAt in session.first.toEpochMilli()..session.second.toEpochMilli()
    return local.occurredAt == imported.occurredAt || withinSession
}

/**
 * Whether this row, found on the moment a record of ours came back to as [imported], can be the
 * event that record was published from.
 *
 * Any row can — but for the cycle's. Whatever the cycle screen files against an earlier day stands
 * on that day's midday ([dayInstant]) under one kind: a flow, a mucus reading, a test, an act, each
 * of which left for Health Connect as a record of its own. Taking the first of them restored for
 * all the rest would drop the others after a reinstall, and the sweep of the next exchange would
 * then withdraw their records — the last copy there was. So a cycle row stands only for the record
 * that says what it says. Rows that say the same thing on one moment are told apart by count
 * ([restoresAnotherRow]), since nothing in a row names its record.
 */
internal fun JournalEntry.standsFor(imported: JournalEntry): Boolean {
    val said = imported.cycleLog() ?: return true
    return cycleLog() == said
}

/**
 * One moment as one journal published it: the client record id without the row's number — the
 * family and the journal's tag — and the kind, the instant and the cycle reading its row carries.
 */
internal data class PublishedMoment(val journal: String, val kind: String, val occurredAt: Long, val said: CycleLog?)

/**
 * Whether a record of ours, come back to a moment where [standing] rows already answer for records
 * like it, is one more event than they are.
 *
 * One row cannot answer for every record on its moment, because rows that say the same thing do
 * share one: an act logged twice against an earlier day stands twice on that day's midday
 * ([dayInstant]), and so do the closing exercises of two template sessions logged for one day. Were
 * one row to answer for all, after a reinstall the first record would come home and the rest would
 * be dropped as its copies — and the next exchange's sweep would withdraw them from Health Connect,
 * the last copy there was. So the records are counted: the journal that published them wrote one
 * record per row, and when it has already brought [seenFromItsJournal] of them home, as many rows
 * are spoken for. Counted by the journal, because the copies two installs published of one event
 * are still one event.
 *
 * Only while this install is [restoring] — until its first exchange has gone through. After that a
 * second record on a standing row's moment is likelier the copy of a row deleted here than an event
 * never seen, and taking it in would bring the deleted row back; any standing row settles it then.
 */
internal fun restoresAnotherRow(standing: Int, seenFromItsJournal: Int, restoring: Boolean): Boolean =
    standing <= if (restoring) seenFromItsJournal else 0

/**
 * A measurement row holding one [Vital] in its payload, titled the way a cuff's or an oximeter's
 * imported row is — the name and the figure — so the journal line reads without opening it.
 */
internal fun vitalEntry(
    context: android.content.Context,
    time: Instant,
    marker: String,
    vital: Vital,
    value: Double,
    offset: ZoneOffset? = null,
) = measurementEntry(context, time, vital.titleRes, marker, offset).copy(
    title = context.getString(vital.titleRes) + " ${value.roundToInt()} " + context.getString(vital.unitRes),
    measurementPayloadJson = JSONObject().put("schema", 1).put(vital.key, value).toString(),
)

/**
 * The day an imported record is filed under: the one it was on where it was recorded.
 *
 * A record carries the [offset] its event was lived in. Read in the zone the phone is in at the
 * exchange instead, a dinner logged at eight in the evening in Los Angeles would land on the next
 * day once the phone was in Moscow, and each whole read would work the day out afresh. A record
 * that states no offset has only the phone's [zone] to go by.
 */
internal fun importedDay(time: Instant, offset: ZoneOffset?, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    time.atZone(offset ?: zone).toLocalDate()

internal fun measurementEntry(
    context: android.content.Context,
    time: Instant,
    titleRes: Int,
    marker: String,
    offset: ZoneOffset? = null,
) = JournalEntry(
    day = importedDay(time, offset).toString(),
    occurredAt = time.toEpochMilli(),
    kind = KIND_MEASUREMENT,
    title = context.getString(titleRes),
    calories = 0.0,
    measurementSource = "HEALTH_CONNECT",
    healthConnectRecordKey = marker,
    zoneOffsetSeconds = offset?.totalSeconds,
)

/**
 * The offset a row's record is published with: that of the clock the row was written on, where the
 * row holds one ([JournalEntry.zoneOffsetSeconds]), and otherwise the phone's own [zone] at [time].
 */
internal fun JournalEntry.offsetAt(time: Instant, zone: ZoneId): ZoneOffset =
    zoneOffsetSeconds?.let(ZoneOffset::ofTotalSeconds) ?: zone.rules.getOffset(time)

/**
 * What a day's imported heart rate is filed under: the day, and nothing else.
 *
 * Naming the wearable whose sample won the day would import a day a second time whenever its winner
 * changed between syncs — the watch in the morning, its companion app in the evening. Rows filed
 * under the package-qualified spelling end in the same day and are recognised by
 * [isDailyHeartRateMarker].
 */
internal fun dailyHeartRateMarker(day: LocalDate): String = "Health Connect:heart:$day"

internal fun refreshedDailyHeartRate(
    existing: JournalEntry,
    occurredAt: Long,
    bpm: Int,
): JournalEntry? = existing.copy(
    occurredAt = occurredAt,
    measurementHeartRateBpm = bpm,
).takeIf { occurredAt > existing.occurredAt && it != existing }

internal data class ImportedNutrition(
    val kind: String,
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    /** Milligrams as the serving of a [KIND_CAFFEINE] row; zero on a meal. */
    val caffeineMg: Double,
    /** Milligrams beside a meal's portion, null when the record states no caffeine. */
    val mealCaffeineMg: Double? = null,
)

/**
 * Whether a nutrition record says anything a journal row can hold: energy, a macro, or caffeine.
 *
 * Another app can file a vitamin tablet as a NutritionRecord with none of those set. Read as a meal
 * it would become a row of nought kilocalories: one more meal on the day, and on a day with nothing
 * else logged, a day of eating nothing. A record that states its energy as zero — a diet cola —
 * still says something and is still read.
 */
internal fun statesMealNutrition(
    caffeineGrams: Double?,
    calories: Double?,
    proteinG: Double?,
    fatG: Double?,
    carbsG: Double?,
): Boolean = listOf(caffeineGrams, calories, proteinG, fatG, carbsG).any { it != null }

internal fun importedNutrition(
    caffeineGrams: Double?,
    calories: Double?,
    proteinG: Double?,
    fatG: Double?,
    carbsG: Double?,
): ImportedNutrition {
    val energy = calories ?: 0.0
    val protein = proteinG ?: 0.0
    val fat = fatG ?: 0.0
    val carbs = carbsG ?: 0.0
    val caffeineOnly = (caffeineGrams ?: 0.0) > 0.0 &&
        energy == 0.0 && protein == 0.0 && fat == 0.0 && carbs == 0.0
    val milligrams = (caffeineGrams ?: 0.0) * 1_000.0
    return ImportedNutrition(
        kind = if (caffeineOnly) KIND_CAFFEINE else KIND_EAT,
        calories = energy,
        proteinG = protein,
        fatG = fat,
        carbsG = carbs,
        // A cup carries its milligrams as the serving, because that is the whole row. A meal that
        // also states caffeine — an energy drink, a coffee logged with its milk — carries them
        // beside its portion instead, and the day counts both.
        caffeineMg = if (caffeineOnly) milligrams else 0.0,
        mealCaffeineMg = milligrams.takeIf { !caffeineOnly && it > 0.0 },
    )
}

internal fun isDailyHeartRateMarker(recordKey: String?, day: LocalDate): Boolean =
    recordKey != null &&
        recordKey.startsWith(HEALTH_IMPORT_MARKER_PREFIX) &&
        recordKey.endsWith(":heart:$day")

private fun SleepSessionRecord.duration(): Long = endTime.toEpochMilli() - startTime.toEpochMilli()

/**
 * What a measured night does to the records already filed under it.
 *
 * Null means it does nothing: the night already holds a measurement, and a second one would only
 * make the pair of them unreadable. Otherwise the returned guesses — nights estimated from screen
 * use — are the ones the measurement replaces.
 */
internal fun sleepGuessesToReplace(recorded: List<JournalEntry>): List<JournalEntry>? {
    val guesses = recorded.filter { it.measurementSource == "PHONE_USAGE_ESTIMATE" }
    return guesses.takeIf { recorded.size == guesses.size }
}

/**
 * The same question for a session Health Connect holds, which goes on changing after it is read.
 *
 * A night is imported by the first exchange that finds it, often before it is over: the 23:54→04:42
 * an exchange met at a quarter to five is 23:54→07:30 by breakfast, or the watch files the whole
 * night as a longer session beside the first. "The night already holds a measurement" would then
 * keep the first reading for good. So a night whose row came from this very record — [marker] —
 * lets the record through when it runs [startMs]→[endMs] and the row does not, to be refreshed in
 * place the way any other imported row is, and an imported row nobody has corrected gives way to a
 * longer session the way a guess does. A row typed or corrected by hand, one of this app's own and
 * one a file brought still settle the night.
 */
internal fun sleepRowsToReplace(
    recorded: List<JournalEntry>,
    marker: String,
    startMs: Long,
    endMs: Long,
): List<JournalEntry>? {
    val own = recorded.firstOrNull { it.healthConnectRecordKey == marker }
    if (own != null) {
        // Nothing to do for a night that has not moved: a year of them is read on every exchange.
        return if (own.sleepStartAt == startMs && own.sleepEndAt == endMs) null else emptyList()
    }
    val (outgrown, others) = recorded.partition { it.outgrownImport(endMs - startMs) }
    return sleepGuessesToReplace(others)?.plus(outgrown)
}

/** An untouched row from Health Connect whose night is shorter than the one now offered. */
private fun JournalEntry.outgrownImport(durationMs: Long): Boolean {
    val start = sleepStartAt ?: return false
    val end = sleepEndAt ?: return false
    return editedAt == null && end - start < durationMs &&
        healthConnectRecordKey?.startsWith(HEALTH_IMPORT_MARKER_PREFIX) == true
}

/**
 * The days whose heart-rate samples have been deleted at their source: the ones the exchange
 * [before] found another app's samples on and this one finds none on. A day Health Connect was
 * never seen to hold is not among them — a rate a backup or a transfer brought from another phone
 * says nothing about this one's store. Nor is [windowOpens], the day the read began part-way
 * through, or any day before it: a day read in part, or not at all, has not been asked about.
 */
internal fun heartDaysLost(before: Set<LocalDate>, now: Set<LocalDate>, windowOpens: LocalDate): Set<LocalDate> =
    before.filterTo(mutableSetOf()) { it > windowOpens && it !in now }

/**
 * The rows among [standing] that are the imported rate of one of the [lost] days. A rate corrected
 * by hand is the person's own figure and stays, as it does when a change feed reports a record
 * deleted; a rate typed in, or measured by a strap, carries no day marker and is never among them.
 */
internal fun vanishedDailyHeartRates(standing: List<JournalEntry>, lost: Set<LocalDate>): List<JournalEntry> =
    standing.filter { row ->
        val day = row.day.toLocalDateOrNull()
        day != null && day in lost && row.editedAt == null && isDailyHeartRateMarker(row.healthConnectRecordKey, day)
    }

/**
 * Whether the details of imported sessions have to be read in this exchange: when its read of the
 * sessions is of the whole window, when it brought a session in or changed one, or when an earlier
 * exchange was left owing the read. Otherwise no session is standing that the last read of the
 * details did not see, and the journal is not read for sessions to ask about.
 *
 * Limitation: details their source writes after an exchange has already imported the session wait
 * for the next session to arrive, which asks again for every session of the last two days
 * ([owesMeasuredEnergy]).
 */
internal fun owesSessionDetails(sessionsReadWhole: Boolean, importedSessions: Int, owedBefore: Boolean): Boolean =
    sessionsReadWhole || importedSessions > 0 || owedBefore

/**
 * Whether the exchange ends its import with the recalculation: when it wrote or removed a row
 * ([journalMoved]), or when an earlier exchange may have and never got that far ([owedBefore]).
 *
 * The imports keep their change tokens a page at a time, so an exchange cut off in the middle — an
 * error, or the process ended with none written down — has rows standing that the next one is not
 * offered again and counts none of. Asked of the count alone, that next exchange would skip the
 * recalculation, and the imported sessions would keep the energy of the weight they were read under
 * and the medicine alarms the hours of the meals before them, until some later import happened to
 * move a row. The last failure on record cannot stand in for this: it says nothing of a process
 * that was simply killed.
 */
internal fun owesRecalculation(journalMoved: Boolean, owedBefore: Boolean): Boolean = journalMoved || owedBefore

/**
 * When an imported session is filed, in the journal's own terms.
 *
 * A workout entered by hand is written down once it is over, and [JournalEntry.sessionInterval]
 * runs it backwards from that moment. One of ours read back after a reinstall becomes a local row
 * and is published again from it, so it has to come home stamped at its end: filed at its start,
 * every reinstall would export it one full duration further into the past. A foreign session is
 * never republished and keeps the start it was recorded on.
 */
internal fun importedSessionInstant(startTime: Instant, endTime: Instant, ownRecord: Boolean): Instant =
    if (ownRecord) endTime else startTime

/**
 * Every payload key a body record leaves for Health Connect under, mapped to the keys whose
 * presence means the row already accounts for that value. One journal row — a scale reading, a
 * pulse oximeter's spot check — publishes several records at one instant, and each comes back on
 * its own after a reinstall; whichever record adopts the row first, the others must be able to
 * merge their value back in, or the export reads the missing value as deleted and the sweep takes
 * the last copy with it.
 *
 * The aliases are what keeps that merge from rewriting rows that were never lost: a mg/dL meter
 * row holds the same glucose the export republished in mmol. Rewriting such a row would change the
 * payload string that is the device history's identity ([StepsDao.hasDirectMeasurement]) and
 * re-import every stored reading on the next connection. The resting rate is special-cased in
 * [bodyValueColumns] rather than listed here: a weight column already accounts for it, because
 * the export derives the record from the weight — and grafting the derived figure onto such a row
 * would freeze it against profile corrections and lock a hand-entered weight out of its editor
 * form. Only a scale reading that reported a measured rate without any weight needs the record
 * merged back, or a reinstall's sweep would delete the only copy with nothing replacing it.
 */
private val EXPORTED_BODY_PAYLOAD_KEYS = mapOf(
    "systolicMmhg" to listOf("systolicMmhg"),
    "diastolicMmhg" to listOf("diastolicMmhg"),
    "spo2Percent" to listOf("spo2Percent"),
    "temperatureCelsius" to listOf("temperatureCelsius"),
    "concentrationMmolL" to listOf("concentrationMmolL", "concentrationMgDl"),
    "concentrationMgDl" to listOf("concentrationMgDl", "concentrationMmolL"),
    "bodyWaterMassKg" to listOf("bodyWaterMassKg"),
)

/**
 * The body values one measurement holds at once.
 *
 * Each of them leaves for Health Connect as a record of its own and comes back on its own, so what
 * a returning record restores is the value the local event of that instant is still missing.
 */
internal fun JournalEntry.bodyValueColumns(): Set<String> = buildSet {
    if (measurementWeightKg != null) add("weight")
    if (measurementHeightCm != null) add("height")
    if (bodyFatPercent != null) add("bodyFat")
    if (measurementHeartRateBpm != null) add("heartRate")
    // A weight is as good as the rate itself: the export derives the record from it.
    if (measurementWeightKg != null || payloadValue("basalMetabolismKj") != null) {
        add("basalMetabolismKj")
    }
    EXPORTED_BODY_PAYLOAD_KEYS.forEach { (key, sources) ->
        if (sources.any { payloadValue(it) != null }) add(key)
    }
}

/** Takes the body values [other] carries and this row has none of; what is already here wins. */
internal fun JournalEntry.withBodyValuesFrom(other: JournalEntry) = copy(
    measurementWeightKg = measurementWeightKg ?: other.measurementWeightKg,
    measurementHeightCm = measurementHeightCm ?: other.measurementHeightCm,
    bodyFatPercent = bodyFatPercent ?: other.bodyFatPercent,
    measurementHeartRateBpm = measurementHeartRateBpm ?: other.measurementHeartRateBpm,
    measurementPayloadJson = mergedPayload(measurementPayloadJson, other.measurementPayloadJson),
)

/** [imported]'s keys folded into [local]; a key both sides hold keeps the local figure. */
private fun mergedPayload(local: String?, imported: String?): String? {
    if (imported == null) return local
    if (local == null) return imported
    return runCatching {
        val merged = JSONObject(local)
        val extra = JSONObject(imported)
        extra.keys().forEach { key -> if (!merged.has(key)) merged.put(key, extra.get(key)) }
        merged.toString()
    }.getOrDefault(local)
}

/**
 * The imported record again, under the id the row that holds it already has; null when nothing
 * about it has changed and the write can be skipped.
 *
 * A session keeps the energy the journal recalculated for it: the import can only guess that back
 * from the same MET and duration, and [recalculateJournal] would undo the guess on the same sync.
 */
internal fun JournalEntry.refreshedFromImport(imported: JournalEntry): JournalEntry? {
    // A row a person corrected by hand is theirs. Refreshing it would put the source's figure back
    // within minutes of the correction, with "Saved" having been shown and nothing else said.
    if (editedAt != null) return null
    // Only what the record itself carries may move. Health Connect has no opinion about the portion
    // weight or the subtitle someone typed into the row it was imported into, and taking the import
    // wholesale would reset every column it does not write — a hydration record is millilitres and
    // nothing else, but the row it lands in can hold a meal's worth of typed detail. An unset
    // number arrives here as null, or as zero on the columns that cannot be null, and either way it
    // means the record is not speaking about that column.
    return copy(
        // The record type decides the kind, and a caffeine-only Nutrition record is not a meal,
        // whatever its row was filed as. Without this the row stays EAT for ever and the milligrams
        // land in the portion-weight column, which is grams.
        kind = imported.kind,
        day = imported.day,
        occurredAt = imported.occurredAt,
        title = imported.title.ifEmpty { title },
        subtitle = imported.subtitle.ifEmpty { subtitle },
        calories = if (kind == KIND_ACTIVITY) calories else stated(imported.calories, calories),
    )
        .withNutritionFrom(imported)
        .withSessionFrom(imported)
        .withMeasurementsFrom(imported)
        .takeIf { it != this }
}

/** Zero is how a record that does not measure food arrives, so it means "unsaid", not "none". */
private fun stated(fresh: Double, local: Double) = if (fresh == 0.0) local else fresh

private fun JournalEntry.withNutritionFrom(imported: JournalEntry) = copy(
    caffeineMg = imported.caffeineMg ?: caffeineMg,
    proteinG = stated(imported.proteinG, proteinG),
    fatG = stated(imported.fatG, fatG),
    carbsG = stated(imported.carbsG, carbsG),
    servingG = stated(imported.servingG, servingG),
    fiberG = imported.fiberG ?: fiberG,
    sugarG = imported.sugarG ?: sugarG,
    saturatedFatG = imported.saturatedFatG ?: saturatedFatG,
    sodiumMg = imported.sodiumMg ?: sodiumMg,
    cholesterolMg = imported.cholesterolMg ?: cholesterolMg,
)

private fun JournalEntry.withSessionFrom(imported: JournalEntry) = copy(
    met = imported.met ?: met,
    durationMinutes = imported.durationMinutes ?: durationMinutes,
    sets = imported.sets ?: sets,
    reps = imported.reps ?: reps,
    loadKg = imported.loadKg ?: loadKg,
)

private fun JournalEntry.withMeasurementsFrom(imported: JournalEntry) = copy(
    measurementWeightKg = imported.measurementWeightKg ?: measurementWeightKg,
    measurementHeightCm = imported.measurementHeightCm ?: measurementHeightCm,
    measurementHeartRateBpm = imported.measurementHeartRateBpm ?: measurementHeartRateBpm,
    sleepStartAt = imported.sleepStartAt ?: sleepStartAt,
    sleepEndAt = imported.sleepEndAt ?: sleepEndAt,
    measurementPayloadJson = imported.measurementPayloadJson ?: measurementPayloadJson,
    moodScore = imported.moodScore ?: moodScore,
    breathCycles = imported.breathCycles ?: breathCycles,
    bodyFatPercent = imported.bodyFatPercent ?: bodyFatPercent,
)

/** A row we published ourselves comes back as a local event, free to be edited and republished. */
internal fun JournalEntry.adopted() = copy(
    measurementSource = measurementSource?.takeIf { it != "HEALTH_CONNECT" },
    activityCode = activityCode?.takeIf { !it.startsWith("HEALTH_CONNECT") },
    healthConnectRecordKey = null,
)

/**
 * Builds one batch, leaving out the rows Health Connect will not take.
 *
 * Every record type checks its own fields as it is constructed, and the ranges it accepts are
 * narrower than the ones a person can genuinely record — a hypertensive crisis reads 215 over 120.
 * Thrown from here, one such row would take the rest of the export and all of its stale sweeps
 * with it, on this run and on every later one, until the row is found and deleted by hand.
 */
internal inline fun <E, T : Record> List<E>.healthRecords(
    build: (E) -> T?,
): List<T> = mapNotNull { entry -> runCatching { build(entry) }.getOrNull() }

/**
 * One Health Connect type's worth of what the journal states, and how a sweep would name the rest.
 *
 * The pair is deliberately inseparable. A sweep withdraws every record of ours in its range whose
 * client id is not among the ones just built, so the list it is held against has to be the list
 * built from a reading of the whole journal — hold it against a shorter one and it withdraws the
 * records of rows that are still standing, from a store this app cannot undelete. Keeping the two
 * in one object is what stops them being handed to each other by mistake: a family built from a
 * handful of rows, as the exchange builds one for the rows it has to write and the mirror for the
 * rows just edited, is published and never swept.
 */
private class RecordFamily<T : Record>(
    private val type: KClass<T>,
    val prefix: String,
    val records: List<T>,
) {
    suspend fun publish(client: HealthConnectClient) = client.insertIfAllowed(records)

    suspend fun sweep(context: android.content.Context, client: HealthConnectClient, now: Instant) =
        removeStalePwnFitRecords(
            context,
            client,
            now,
            type,
            prefix,
            records.mapNotNull { it.metadata.clientRecordId }.toSet(),
        )
}

/**
 * Whether a journal row is this app's own to publish.
 *
 * The key first: it is what the import filed the row under, and no field this app offers can write
 * it. Judged on the two provenance fields alone, an imported session whose activity was re-picked
 * would pass and come back as a second copy of somebody else's workout.
 */
private fun locallyOwnedJournalRow(entry: JournalEntry): Boolean =
    entry.healthConnectRecordKey == null &&
        entry.measurementSource != "HEALTH_CONNECT" &&
        entry.activityCode?.startsWith("HEALTH_CONNECT") != true

/**
 * How a row's record says it was taken.
 *
 * Only a row typed in says "entered by hand"; a cuff's reading, a scale's weight and the night the
 * phone guessed from its screen each say how they were taken, because Health Connect exists to tell
 * other apps a figure typed in from one an instrument or the phone took by itself. The guess is the
 * one record of ours the phone takes by itself, which is also how it is known again after a
 * reinstall ([isPhoneSleepEstimate]); an instrument's goes as from a device of no named kind, which
 * the journal does not keep. A pulse the phone's own sensor took while the person held still for it
 * is the phone recording on request ([SOURCE_PHONE]).
 */
internal fun publishedMetadata(entry: JournalEntry, clientRecordId: String, version: Long): Metadata {
    val source = entry.measurementSource.orEmpty()
    val phone = Device(type = Device.TYPE_PHONE)
    val instrument = Device(type = Device.TYPE_UNKNOWN)
    return when {
        source == SOURCE_PHONE -> Metadata.activelyRecorded(phone, clientRecordId, version)
        source == PHONE_ESTIMATE -> Metadata.autoRecorded(phone, clientRecordId, version)
        source.startsWith("BLUETOOTH") -> Metadata.autoRecorded(instrument, clientRecordId, version)
        else -> Metadata.manualEntry(clientRecordId, version)
    }
}

/** Whether a sleep record of ours is the phone's guess from screen use; [publishedMetadata] marks it so. */
internal fun Metadata.isPhoneSleepEstimate(): Boolean =
    recordingMethod == Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED && device?.type == Device.TYPE_PHONE

/**
 * An optional nutrient as Health Connect will take it, or nothing. Its ceiling is 100 kg for fibre,
 * sugar and saturated fat and 100 g for sodium and cholesterol — 100,000 in the unit the journal keeps
 * each in — and one figure past it would refuse the whole meal rather than the figure.
 */
internal fun Double?.nutrientFigure(): Double? = this?.takeIf { it in 0.0..MOST_OF_A_NUTRIENT }

private const val MOST_OF_A_NUTRIENT = 100_000.0

/**
 * The version a record written at [now] is published under: the clock, or one past the [last]
 * version written when the clock has since been set back. Health Connect keeps the copy of a client
 * record id with the higher version and answers an insert of a lower one as a success, so an edit
 * published after the clock went back an hour would vanish without a word.
 */
internal fun recordVersion(now: Long, last: Long): Long = maxOf(now, last + 1)

/** [recordVersion] for a write about to be made, kept so the next one is above it whatever the clock says. */
private fun publishVersion(context: android.content.Context, now: Instant): Long {
    val prefs = syncPrefs(context)
    // Every write runs under [healthSyncMutex], so nothing moves the stored version between the two.
    val version = recordVersion(now.toEpochMilli(), prefs.getLong(KEY_RECORD_VERSION, 0L))
    prefs.edit { putLong(KEY_RECORD_VERSION, version) }
    return version
}

/**
 * What [localEvents] state, as records, one family per Health Connect type. Stable client IDs make
 * repeated syncs idempotent, while a monotonically increasing [version] ([publishVersion]) lets an
 * edited event replace its previous copy.
 *
 * Nothing here reads the database or writes to Health Connect, which is what lets the exchange and
 * the after-edit mirror share it: the first hands it the whole journal, the second the handful of
 * rows somebody has just touched, and a row says the same thing about itself either way.
 */
// One branch per Health Connect record type.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun wellnessRecords(
    context: android.content.Context,
    localEvents: List<JournalEntry>,
    now: Instant,
    version: Long,
): List<RecordFamily<*>> {
    val zone = ZoneId.systemDefault()
    val generation = journalGeneration(context)
    fun metadata(entry: JournalEntry, prefix: String) =
        publishedMetadata(entry, publishedClientId(prefix, generation, entry.id), version)
    val weightRecords = localEvents.healthRecords { entry ->
        val weight = entry.measurementWeightKg?.takeIf { it in PLAUSIBLE_KG }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        WeightRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            weight = Mass.kilograms(weight),
            metadata = metadata(entry, WEIGHT_CLIENT_ID_PREFIX),
        )
    }
    val heightRecords = localEvents.healthRecords { entry ->
        val height = entry.measurementHeightCm?.takeIf { it in 50..250 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        HeightRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            height = Length.meters(height / 100.0),
            metadata = metadata(entry, HEIGHT_CLIENT_ID_PREFIX),
        )
    }
    val heartRecords = localEvents.healthRecords { entry ->
        val bpm = entry.measurementHeartRateBpm?.takeIf { it in 30..250 }
            ?: return@healthRecords null
        val sampleTime = Instant.ofEpochMilli(entry.occurredAt)
        val start = sampleTime.minusMillis(1)
        val end = sampleTime.plusMillis(1)
        HeartRateRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            samples = listOf(HeartRateRecord.Sample(sampleTime, bpm.toLong())),
            metadata = metadata(entry, HEART_RATE_CLIENT_ID_PREFIX),
        )
    }
    val sleepRecords = localEvents.healthRecords { entry ->
        val start = entry.sleepStartAt?.let(Instant::ofEpochMilli) ?: return@healthRecords null
        val end = entry.sleepEndAt?.let(Instant::ofEpochMilli) ?: return@healthRecords null
        if (end <= start || end > now.plus(Duration.ofMinutes(1))) return@healthRecords null
        SleepSessionRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            metadata = metadata(entry, SLEEP_CLIENT_ID_PREFIX),
            title = context.getString(R.string.measurement_sleep_title),
            notes = entry.note.takeIf { it.isNotBlank() },
        )
    }
    val exerciseRecords = localEvents.healthRecords { entry ->
        if (entry.kind != KIND_ACTIVITY) return@healthRecords null
        val (start, end) = entry.sessionInterval(now) ?: return@healthRecords null
        ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = end,
            endZoneOffset = entry.offsetAt(end, zone),
            metadata = metadata(entry, EXERCISE_CLIENT_ID_PREFIX),
            exerciseType = entry.healthConnectExerciseType(),
            title = entry.title,
            notes = entry.note.takeIf { it.isNotBlank() },
        )
    }

    val hydrationRecords = localEvents.healthRecords { entry ->
        if (entry.kind != KIND_WATER) return@healthRecords null
        val millilitres = entry.servingG.takeIf { it > 0.0 } ?: return@healthRecords null
        val start = Instant.ofEpochMilli(entry.occurredAt)
        HydrationRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = start.plusSeconds(1),
            endZoneOffset = entry.offsetAt(start, zone),
            volume = Volume.milliliters(millilitres),
            metadata = metadata(entry, HYDRATION_CLIENT_ID_PREFIX),
        )
    }
    // A cup of coffee is a nutrition record carrying only caffeine — the same client-id prefix as
    // food, because the journal ids behind it are unique across kinds and the stale sweep follows.
    val nutritionRecords = localEvents.healthRecords { entry ->
        if (entry.kind != KIND_EAT && entry.kind != KIND_CAFFEINE) return@healthRecords null
        val start = Instant.ofEpochMilli(entry.occurredAt)
        NutritionRecord(
            startTime = start,
            startZoneOffset = entry.offsetAt(start, zone),
            endTime = start.plusSeconds(1),
            endZoneOffset = entry.offsetAt(start, zone),
            energy = Energy.kilocalories(max(0.0, entry.calories)),
            protein = Mass.grams(max(0.0, entry.proteinG)),
            totalFat = Mass.grams(max(0.0, entry.fatG)),
            totalCarbohydrate = Mass.grams(max(0.0, entry.carbsG)),
            caffeine = Mass.grams(caffeineGrams(caffeineMgOf(entry) ?: 0.0)).takeIf {
                entry.kind == KIND_CAFFEINE ||
                    (entry.caffeineMg ?: 0.0) > 0.0
            },
            dietaryFiber = entry.fiberG.nutrientFigure()?.let { Mass.grams(it) },
            sugar = entry.sugarG.nutrientFigure()?.let { Mass.grams(it) },
            saturatedFat = entry.saturatedFatG.nutrientFigure()?.let { Mass.grams(it) },
            sodium = entry.sodiumMg.nutrientFigure()?.let { Mass.milligrams(it) },
            cholesterol = entry.cholesterolMg.nutrientFigure()?.let { Mass.milligrams(it) },
            name = entry.title,
            metadata = metadata(entry, NUTRITION_CLIENT_ID_PREFIX),
        )
    }
    // The ranges are Health Connect's own; a cuff reading beyond them stays in the journal only.
    val bloodPressureRecords = localEvents.healthRecords { entry ->
        val systolic = entry.payloadValue("systolicMmhg")?.takeIf { it in 20.0..200.0 }
            ?: return@healthRecords null
        val diastolic = entry.payloadValue("diastolicMmhg")?.takeIf { it in 10.0..180.0 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        BloodPressureRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            systolic = Pressure.millimetersOfMercury(systolic),
            diastolic = Pressure.millimetersOfMercury(diastolic),
            metadata = metadata(entry, BLOOD_PRESSURE_CLIENT_ID_PREFIX),
        )
    }
    val oxygenRecords = localEvents.healthRecords { entry ->
        val percent = entry.payloadValue("spo2Percent")?.takeIf { it in 50.0..100.0 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        OxygenSaturationRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            percentage = Percentage(percent),
            metadata = metadata(entry, OXYGEN_CLIENT_ID_PREFIX),
        )
    }
    val temperatureRecords = localEvents.healthRecords { entry ->
        val celsius = entry.payloadValue("temperatureCelsius")?.takeIf { it in 25.0..45.0 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        BodyTemperatureRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            temperature = Temperature.celsius(celsius),
            measurementLocation = healthConnectTemperatureLocation(entry.temperatureSiteName()),
            metadata = metadata(entry, TEMPERATURE_CLIENT_ID_PREFIX),
        )
    }
    val glucoseRecords = localEvents.healthRecords { entry ->
        val mmol = entry.glucoseMmolL()?.takeIf { it in 0.5..50.0 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        BloodGlucoseRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            level = BloodGlucose.millimolesPerLiter(mmol),
            relationToMeal = if (entry.labFasting()) {
                BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
            } else {
                BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
            },
            metadata = metadata(entry, GLUCOSE_CLIENT_ID_PREFIX),
        )
    }
    val bodyFatRecords = localEvents.healthRecords { entry ->
        val percent = entry.bodyFatPercent?.takeIf { it in 1.0..80.0 } ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        BodyFatRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            percentage = Percentage(percent),
            metadata = metadata(entry, BODY_FAT_CLIENT_ID_PREFIX),
        )
    }

    return listOf(
        RecordFamily(WeightRecord::class, WEIGHT_CLIENT_ID_PREFIX, weightRecords),
        RecordFamily(HeightRecord::class, HEIGHT_CLIENT_ID_PREFIX, heightRecords),
        RecordFamily(HeartRateRecord::class, HEART_RATE_CLIENT_ID_PREFIX, heartRecords),
        RecordFamily(SleepSessionRecord::class, SLEEP_CLIENT_ID_PREFIX, sleepRecords),
        RecordFamily(ExerciseSessionRecord::class, EXERCISE_CLIENT_ID_PREFIX, exerciseRecords),
        RecordFamily(HydrationRecord::class, HYDRATION_CLIENT_ID_PREFIX, hydrationRecords),
        RecordFamily(NutritionRecord::class, NUTRITION_CLIENT_ID_PREFIX, nutritionRecords),
        RecordFamily(BloodPressureRecord::class, BLOOD_PRESSURE_CLIENT_ID_PREFIX, bloodPressureRecords),
        RecordFamily(OxygenSaturationRecord::class, OXYGEN_CLIENT_ID_PREFIX, oxygenRecords),
        RecordFamily(BodyTemperatureRecord::class, TEMPERATURE_CLIENT_ID_PREFIX, temperatureRecords),
        RecordFamily(BloodGlucoseRecord::class, GLUCOSE_CLIENT_ID_PREFIX, glucoseRecords),
        RecordFamily(BodyFatRecord::class, BODY_FAT_CLIENT_ID_PREFIX, bodyFatRecords),
    )
}

/**
 * What this process last published of each journal row, as the row's id and its hash; and the
 * journal and the grants that was under, without which it describes nothing. Read and written by
 * [exportLocalHealth] alone, which runs under [healthSyncMutex].
 */
private val publishedRows = HashMap<Long, Int>()
private var publishedUnder: String? = null

/**
 * The rows whose records have to be written again: the ones [published] does not hold as they stand
 * now, and the ones the mirror was told of ([edited]) whatever their hash — a row changed and
 * changed back may have left its middle state in Health Connect.
 *
 * Writing every record of every row at every return to the app would make the exchange's cost grow
 * with the history, paid for a journal in which nothing had changed, and would tell every other app
 * each time that all of it had. A row is its record's whole source for the twelve types
 * [wellnessRecords] builds, so a row that has not changed since this process wrote its records is
 * not written again.
 *
 * Limitation: a record removed inside Health Connect itself — its data for this app deleted there —
 * is written back at the next start of the process, not at the next exchange.
 */
internal fun rowsOwedToHealthConnect(
    rows: List<JournalEntry>,
    published: Map<Long, Int>,
    edited: Set<Long>,
): List<JournalEntry> = rows.filter { it.id in edited || published[it.id] != it.hashCode() }

/**
 * Whether the records a row builds can no longer move with the clock. A session still running is
 * cut off at the moment of the export and a night ending in the future is not built at all, so a
 * row that reaches past [now] is not taken as published: it is built again next time.
 */
internal fun settledRow(entry: JournalEntry, now: Instant): Boolean {
    val sessionEnd = entry.occurredAt + ((entry.durationMinutes ?: 0.0) * 60_000).toLong()
    return maxOf(sessionEnd, entry.sleepEndAt ?: Long.MIN_VALUE) <= now.toEpochMilli()
}

/**
 * What this process last wrote under each client record id of the families exported beside the
 * journal's own twelve — the cycle, the body, a session's figures, the day's energy, the breathing
 * and the plans — as the record's content ([recordContent]). Emptied with [publishedRows],
 * read and written under the same lock, and an id the sweep withdraws is forgotten there.
 *
 * Without it those families would write every record they build at every exchange, and no row's
 * hash can stand in for theirs: a period is built from two rows, a day's energy from its steps,
 * sessions and weighings, a resting rate from the profile as well as the weighing, a plan from its
 * template. The record can.
 * A record removed inside Health Connect itself returns at the next start of the process, as a row's
 * does ([rowsOwedToHealthConnect]).
 */
internal val publishedRecords = HashMap<String, String>()

/**
 * A record's content without its metadata, whose version moves at every exchange ([publishVersion])
 * whether anything else has or not. Every record type of connect-client prints each of its fields,
 * the metadata among them, so the record's text less the metadata's is the rest of it.
 */
internal fun Record.recordContent(): String = toString().replaceFirst("metadata=$metadata", "")

/** The records among these that [published] does not hold as they stand now. */
internal fun <T : Record> recordsOwedToHealthConnect(records: List<T>, published: Map<String, String>): List<T> =
    records.filter { record -> record.metadata.clientRecordId?.let(published::get) != record.recordContent() }

/**
 * [insertIfAllowed] for the records among these that have changed since this process last wrote
 * them ([publishedRecords]). Every record is still built, and named to the sweep that follows.
 */
internal suspend fun HealthConnectClient.insertChanged(records: List<Record>): Boolean {
    val owed = recordsOwedToHealthConnect(records, publishedRecords)
    val taken = insertIfAllowed(owed)
    if (taken) {
        for (record in owed) {
            val id = record.metadata.clientRecordId ?: continue
            publishedRecords[id] = record.recordContent()
        }
    }
    return taken
}

/**
 * Steps of the exchange that are not to cost one another: each is run whatever became of the ones
 * before it, and the first failure is thrown once they have all had their turn.
 *
 * A failed insert that ended the export where it stood would leave every family after it
 * unpublished, and every sweep too, since they come last: one record type the platform chokes on,
 * exchange after exchange, would mean that nothing deleted here is withdrawn from Health Connect in
 * any type. A sweep is held against the records built, not the records written, so it loses nothing
 * by running after a failure. The failure is still the exchange's: the card reports it and the next
 * return to the app tries again.
 */
internal class ExchangeSteps {
    private var first: Throwable? = null

    suspend fun attempt(step: suspend () -> Unit) {
        if (first?.let(::spentQuota) == true) return
        runCatching { step() }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (first == null) first = failure
        }
    }

    fun rethrowFirst() {
        first?.let { throw it }
    }
}

/**
 * Whether [failure] is the quota running out — the one failure after which no further step is worth
 * taking, since every call would meet it again and be counted against it.
 */
internal fun spentQuota(failure: Throwable): Boolean = syncFailureKind(failure) == SyncFailureKind.RATE_LIMITED

/**
 * The exchange's export: the records of every row that has changed written, and every record of
 * ours that no row claims any more withdrawn.
 *
 * Reading the journal whole is the point rather than the cost. A sweep may only be held against the
 * records built from the same reading, and only a reading of everything can say that a record
 * belongs to no row at all — which is the one thing that carries a deletion across to the other
 * store. So every record is built, and the ones written are those of the rows
 * [rowsOwedToHealthConnect] names. The mirror after a single edit is [mirrorEditedJournalRows], and
 * it sweeps nothing for exactly this reason.
 *
 * Returns how many of the records written Health Connect took.
 */
private suspend fun exportLocalHealth(
    context: android.content.Context,
    db: StepsDb,
    client: HealthConnectClient,
    now: Instant,
): Int {
    // Read before the journal is, so that an edit made while this runs stays pending rather than
    // being forgotten by a pass that never saw it.
    val pending = editedJournalRows()
    val localEvents = db.dao().allJournal().filter(::locallyOwnedJournalRow)
    val version = publishVersion(context, now)
    val families = wellnessRecords(context, localEvents, now, version)
    val prefs = syncPrefs(context)
    val scope = journalGeneration(context) + "|" + prefs.getString(KEY_LAST_SYNC_GRANTS, null)
    // Nothing counts as published under another journal's tag, or under other grants — a type
    // allowed since is owed its whole history.
    if (publishedUnder != scope) {
        publishedRows.clear()
        publishedRoutes.clear()
        publishedRecords.clear()
    }
    val owed = rowsOwedToHealthConnect(localEvents, publishedRows, pending)
    val written = if (owed.size == localEvents.size) families else wellnessRecords(context, owed, now, version)
    val steps = ExchangeSteps()
    // What Health Connect took rather than what was built, as [insertIfAllowed] asks. Limitation:
    // what the cycle, body, activity and session exports write goes uncounted, because those
    // exports do not report what was taken.
    var accepted = 0
    written.forEach { family -> steps.attempt { if (family.publish(client)) accepted += family.records.size } }
    val profile = db.dao().profileNow()
    val zone = ZoneId.systemDefault()
    steps.attempt { exportCycleRecords(context, client, now, localEvents, zone, version) }
    steps.attempt { exportBodyRecords(context, client, now, localEvents, profile, zone, version) }
    steps.attempt {
        exportActivityRecords(context, db, client, now, localEvents, db.dao().profileInEffect(), zone, version)
    }
    steps.attempt { exportSessionRecords(context, db, client, now, localEvents, zone, version) }
    families.forEach { family -> steps.attempt { family.sweep(context, client, now) } }
    steps.rethrowFirst()
    // Only now, with every step through: a row whose record was not taken is still owed.
    publishedRows.keys.retainAll(localEvents.mapTo(HashSet()) { it.id })
    owed.filter { settledRow(it, now) }.forEach { publishedRows[it.id] = it.hashCode() }
    publishedUnder = scope
    // Every row that had changed has just been published, so nothing the mirror was holding is still owed.
    forgetEditedJournalRows(pending)
    return accepted
}

/**
 * The mirror after a local edit: the rows that were touched, published, and nothing else read or
 * withdrawn.
 *
 * Running [exportLocalHealth] instead would mean that saving a glass of water reads every row of
 * the journal and all thirty-six columns of each, builds the records for twelve types out of them
 * and then makes twelve paged reads back out of Health Connect to see what to withdraw — from
 * fifteen call sites, after every edit.
 *
 * What it gives up is the withdrawing. A sweep is safe only against a reading of the whole journal:
 * narrow the reading and "no row claims this record" starts meaning "no row I looked at", and the
 * sweep withdraws the records of rows that are still standing. So this one publishes and stops
 * there, and a deletion — along with the record families the aggregate exports build, where a day's
 * total or a period assembled from two rows cannot be told from a handful of rows either — reaches
 * Health Connect at the next exchange instead of at the edit. The exchange runs at every launch and
 * every return to the foreground, so that is minutes, and [JournalTombstone] is what keeps the
 * deletion from being read back in as history in the meantime.
 */
private suspend fun mirrorEditedJournalRows(
    context: android.content.Context,
    db: StepsDb,
    client: HealthConnectClient,
    ids: Set<Long>,
    now: Instant,
) {
    val rows = db.dao().journalRows(ids).filter(::locallyOwnedJournalRow)
    if (rows.isEmpty()) return
    wellnessRecords(context, rows, now, publishVersion(context, now)).forEach { it.publish(client) }
}

/**
 * A glucose reading in the one unit Health Connect and the lab history both read.
 *
 * A meter that reports a mass concentration — the mg/dL half of the market — fills only its own
 * field, and a reading that never converts is a row nothing downstream can see.
 */
internal fun JournalEntry.glucoseMmolL(): Double? =
    (payloadValue("concentrationMmolL") ?: payloadValue("concentrationMgDl")?.div(MG_DL_PER_MMOL_L))
        // A panel marked urine or saliva with the glucose field filled is not a blood glucose, and
        // Health Connect only has that.
        ?.takeIf { labSpecimen()?.blood != false }

/**
 * The site a temperature was read at, under the same key the Bluetooth thermometer writes it to.
 *
 * The vitals screen refuses to read a body temperature against the 38 °C fever threshold when the
 * thermometer says it was taken somewhere peripheral — an armpit runs below core, so the core
 * threshold would call a real fever normal. That refusal reads one payload key, so the importer
 * writes the location to it: dropped, the same axillary reading arriving from another app would be
 * graded as though it came from the middle of the body.
 *
 * Written out against the library's own named constants rather than against the integers behind
 * them, because the numbers are the platform's to renumber. Its int-to-name map would say all this
 * in one line, but it is `@RestrictedTo` the library.
 *
 * Health Connect's list is longer than the Bluetooth profile's: FOREHEAD, WRIST, TEMPORAL_ARTERY
 * and VAGINA have no [TemperatureType]. Writing them anyway is the safe direction — a site the
 * reader cannot place withholds the verdict, where a site left unwritten would put the core fever
 * threshold back over a forehead. UNKNOWN is written as nothing at all, which is what it means.
 */
internal fun JSONObject.putTemperatureSite(location: Int): JSONObject = apply {
    val name = when (location) {
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT -> "ARMPIT"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR -> "EAR"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER -> "FINGER"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH -> "MOUTH"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM -> "RECTUM"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TOE -> "TOE"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD -> "FOREHEAD"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY -> "TEMPORAL_ARTERY"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_VAGINA -> "VAGINA"
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST -> "WRIST"
        else -> null
    }
    name?.let { put("type", it) }
}

/**
 * The site the export writes for the name under that key — [putTemperatureSite] run backwards.
 *
 * A record that left without its site would bring the axillary 37.6 °C the vitals screen refused to
 * grade back after a reinstall as a reading from nowhere in particular, graded against the core
 * band after all. The thermometer's list and Health Connect's overlap without matching: BODY and
 * the gastrointestinal tract have no constant and go as UNKNOWN, which the import reads back as no
 * site — the verdict those two get anyway. TYMPANUM goes as EAR, the one ear Health Connect has and
 * the one an ear thermometer reads, and comes home as EAR, where the reader withholds the verdict
 * rather than misplace it. The four names only a foreign import writes go back as themselves, so an
 * adopted row keeps the site it arrived with.
 */
// One branch per site Health Connect names.
@Suppress("CyclomaticComplexMethod")
internal fun healthConnectTemperatureLocation(siteName: String?): Int = when (siteName) {
    "ARMPIT" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT
    "EAR", "TYMPANUM" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR
    "FINGER" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER
    "MOUTH" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH
    "RECTUM" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM
    "TOE" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TOE
    "FOREHEAD" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD
    "TEMPORAL_ARTERY" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY
    "VAGINA" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_VAGINA
    "WRIST" -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST
    else -> BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_UNKNOWN
}

/** The site a thermometer row names, read from the key [putTemperatureSite] writes it back to. */
private fun JournalEntry.temperatureSiteName(): String? {
    val json = measurementPayloadJson ?: return null
    return runCatching { JSONObject(json).optString("type") }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Clinical Bluetooth profiles keep their native fields in JSON; export reads the ones HC knows. */
internal fun JournalEntry.payloadValue(key: String): Double? {
    val json = measurementPayloadJson ?: return null
    return runCatching { JSONObject(json).optDouble(key) }.getOrNull()?.takeIf { !it.isNaN() }
}

private fun JournalEntry.healthConnectExerciseType(): Int = healthConnectExerciseType(activityCode)

// One branch per exercise the catalogue offers.
@Suppress("CyclomaticComplexMethod")
internal fun healthConnectExerciseType(activityCode: String?): Int = when (activityCode) {
    "WALK" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING

    "RUN" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING

    "CYCLING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING

    "YOGA" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA

    "CALISTHENICS", "TRICEPS_BODY", "PUSHUPS", "PULLUPS", "DIPS", "ABS", "BICYCLE_CRUNCH", "BAR_LEG_RAISE",
    "SQUATS", "LUNGES", "BURPEES", "PLANK", "JUMPING_JACKS",
    -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS

    "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING

    "STAIRS" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING

    "ELLIPTICAL" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL

    "ROWING_MACHINE" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE

    "SWIMMING" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL

    "JUMP_ROPE" -> ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS

    "HIIT" -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING

    "BOXING" -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING

    "DANCING" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING

    "BASKETBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL

    // Every locale's word for this row is the one for soccer.
    "FOOTBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER

    "TENNIS" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS

    "PILATES" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES

    "STRETCHING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING

    "STRENGTH", "TRICEPS_DUMBBELL", "BENCH_PRESS", "OVERHEAD_PRESS", "BARBELL_ROW", "DEADLIFT",
    "BICEPS_CURL",
    -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING

    "BLUETOOTH_CSC_CPS" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING

    "BLUETOOTH_FTMS", "BLUETOOTH_CONCEPT2_PM5", "BLUETOOTH_RSCS" ->
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}

/**
 * The catalogue row a session type of ours comes home as — [healthConnectExerciseType] run
 * backwards. Where several rows publish as one type the general one is chosen: a bench press comes
 * back as strength training, which is all Health Connect was ever told. OTHER_WORKOUT is null, since
 * no row is that and the caller has a label of its own for it.
 */
// One branch per exercise type the catalogue publishes.
@Suppress("CyclomaticComplexMethod")
internal fun catalogueActivityCode(exerciseType: Int): String? = when (exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALK"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUN"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "CYCLING"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "CALISTHENICS"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "HIKING"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "STAIRS"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "ELLIPTICAL"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "ROWING_MACHINE"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING"
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "JUMP_ROPE"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "BOXING"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "DANCING"
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "BASKETBALL"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "FOOTBALL"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "TENNIS"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "PILATES"
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "STRETCHING"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH"
    else -> null
}

/** What an imported session is filed as, and the MET it is costed at. */
internal data class ImportedSessionKind(val activityCode: String, val met: Double)

/** The MET a session is costed at when nothing says what it was: a moderate hour, whatever it was. */
private const val IMPORTED_SESSION_MET = 5.0

/**
 * The row and the MET an imported session comes in under.
 *
 * One of ours goes back to the catalogue row it was published from, at that row's MET. Brought home
 * as HEALTH_CONNECT at 5 MET whatever it had been, it would lose even that label to [adopted], and
 * the next export would upsert the same client record id as OTHER_WORKOUT — the only copy left of a
 * run would become a workout of no kind, one reinstall at a time. Another app's session is not ours
 * to re-cost and keeps the import's own label; a step-typed one says so, because the pedometer
 * counted those very steps and movedCalories must let the two claims compete rather than add them.
 */
internal fun importedSessionKind(exerciseType: Int, ownRecord: Boolean): ImportedSessionKind {
    if (ownRecord) {
        val spec = catalogueActivityCode(exerciseType)?.let { code -> ACTIVITY_CATALOG.firstOrNull { it.code == code } }
        if (spec != null) return ImportedSessionKind(spec.code, spec.met)
    }
    val code = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        -> HEALTH_CONNECT_STEPS_CODE

        else -> "HEALTH_CONNECT"
    }
    return ImportedSessionKind(code, IMPORTED_SESSION_MET)
}

internal val DIRECT_LIVE_ACTIVITY_CODES = setOf(
    "BLUETOOTH_FTMS",
    "BLUETOOTH_CONCEPT2_PM5",
    "BLUETOOTH_RSCS",
    "BLUETOOTH_CSC_CPS",
    // An imported session runs forward from the start it was recorded on, and its energy is the
    // source app's figure — measured where [importActivityDetails] found calorie records, its
    // import-time estimate otherwise — never re-derived from this profile's weight.
    "HEALTH_CONNECT",
    HEALTH_CONNECT_STEPS_CODE,
)

/**
 * Best-effort immediate mirror used after a local health or exercise edit. Local saving never
 * depends on Health Connect: unavailable providers and missing permissions simply leave the event
 * queued for the next explicit sync.
 *
 * What it publishes is the rows written since it last ran — [editedJournalRows] — and not the
 * journal, which fifteen call sites would otherwise read after every glass of water. The set is
 * only forgotten once the publishing has been through, so a mirror that found no permission, or
 * that threw halfway, leaves the rows for the next one and, failing that, for the exchange. An
 * edit to a row from last month is in the set exactly as a row from this minute is: what is
 * narrowed is which rows are read, never how far back they may sit.
 *
 * Limitation: an edit landing in the instant between the publishing and the forgetting is forgotten
 * with it and waits for the exchange. Closing that would mean taking the rows out of the set before
 * they are published and putting them back when they are not, which trades a rare late publish for
 * a rare lost one.
 */
internal suspend fun writeLocalHealthIfAvailable(
    context: android.content.Context,
    db: StepsDb,
) {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
    // Not before the first full sync has run: the restore reads the store this would be writing
    // into, and what it finds there is taken for history. A row's record cannot land on a previous
    // install's — the id names the journal ([publishedClientId]) — so this is the order of things
    // rather than a protection. Until the exchange has happened once, edits wait.
    if (!syncPrefs(context).getBoolean(KEY_RESTORE_DONE, false)) return
    // Nothing owed is the common answer at a call site that fired for a step count or a track, and
    // it is worth an early no: what follows queues for the lock the whole exchange holds.
    if (editedJournalRows().isEmpty()) return
    val client = HealthConnectClient.getOrCreate(context)
    // Health Connect lets people allow one type and refuse another, so the mirror runs on whatever
    // was allowed. Each record type is checked again where it is written.
    val granted = runCatching {
        client.permissionController.getGrantedPermissions()
    }.getOrElse {
        // A query that failed leaves the rows owed; a caller that was cancelled is not told it returned.
        if (it is CancellationException) throw it
        return
    }
    if (granted.none { it.startsWith("android.permission.health.WRITE") }) return
    // Read again rather than above, and read once: an edit made while these checks ran belongs to
    // this pass, and only what was read here may be forgotten when it goes through.
    val edited = editedJournalRows()
    // Under the same lock as the full sync. The exchange's export ends in stale sweeps against the
    // journal it read when it started, and a mirror publishing rows into the middle of one would be
    // writing records that sweep has already decided nothing claims.
    //
    // In the sync's own scope, and not waited for. That lock is held for a whole exchange, and
    // waiting for it would leave the page that called — a lab sheet, a glucose meter's readings, a
    // routine just logged — with its button dead until the exchange ended, though its row is
    // already in Room. The rows stay owed until this has been through, so nothing is lost by
    // returning first; and a page that closes meanwhile does not take its mirror with it. A failure
    // leaves them owed for the next pass; a cancellation is let through, and ends the job as one —
    // so does a provider that never answers ([MIRROR_TIMEOUT]), which leaves them owed as well.
    val app = context.applicationContext
    healthSyncScope.launch {
        runCatching {
            healthSyncMutex.withLock {
                withTimeout(MIRROR_TIMEOUT.toMillis()) {
                    withContext(Dispatchers.Default) {
                        mirrorEditedJournalRows(app, db, client, edited, Instant.now())
                    }
                }
            }
        }.onSuccess { forgetEditedJournalRows(edited) }
            .onFailure { if (it is CancellationException) throw it }
    }
}

/**
 * Writes a batch unless that record type is switched off in Health Connect. Refusing one type is a
 * choice, not a failure: the rest of the export still goes.
 *
 * Returns whether the records were taken. A caller that reports what it exported has to count what
 * was accepted rather than what it built: counting what it built, the step export would say
 * "exported 1" for a whole afternoon while Health Connect refused every write, and nothing on the
 * phone would contradict it.
 */
@Suppress("SwallowedException")
internal suspend fun HealthConnectClient.insertIfAllowed(records: List<Record>): Boolean {
    if (records.isEmpty()) return true
    return try {
        healthWriteBatches(records).forEach { insertRecords(it) }
        true
    } catch (denied: SecurityException) {
        false
    }
}

internal fun <T> healthWriteBatches(records: List<T>): List<List<T>> =
    records.chunked(HEALTH_WRITE_BATCH_SIZE)

/** The same courtesy for removals: a type whose write was refused keeps its rows, the rest go. */
@Suppress("SwallowedException")
internal suspend fun <T : Record> HealthConnectClient.deleteIfAllowed(
    recordType: KClass<T>,
    recordIdsList: List<String> = emptyList(),
    clientRecordIdsList: List<String> = emptyList(),
) {
    if (recordIdsList.isEmpty() && clientRecordIdsList.isEmpty()) return
    try {
        deleteRecords(recordType, recordIdsList = recordIdsList, clientRecordIdsList = clientRecordIdsList)
    } catch (denied: SecurityException) {
        return
    }
}

/**
 * Withdraws the records of this type that no local event claims any more.
 *
 * Absence in the journal only means "deleted" while the journal is the fuller of the two stores.
 * A reinstall leaves it empty against a Health Connect that still holds everything, so nothing is
 * withdrawn before the restore has run once; older than the restore reaches back, only what this
 * install published itself may go ([withdrawableBeyondRestore]); and the same holds at any age for
 * a type the restore does not read at all.
 *
 * With one exception for such a type, where the caller hands over [span], the stretch of time a
 * record covers. Nothing reads these records back, so a route or a plan that returns by another
 * road — a backup restored after a reinstall, or into a database that started over — is published
 * again under this journal's tag while the earlier journal's record of it stands: two distances
 * over one walk, for every app that adds them up. An unclaimed record over the very stretch a
 * claimed one of its family covers is that twin, whoever wrote it, and goes.
 */
internal suspend fun <T : androidx.health.connect.client.records.Record> removeStalePwnFitRecords(
    context: android.content.Context,
    client: HealthConnectClient,
    now: Instant,
    recordType: kotlin.reflect.KClass<T>,
    prefix: String,
    desiredIds: Set<String>,
    span: ((T) -> Any)? = null,
) {
    val prefs = syncPrefs(context)
    if (!prefs.getBoolean(KEY_RESTORE_DONE, false)) return
    val adoptedAt = Instant.ofEpochMilli(prefs.getLong(KEY_ADOPTED_AT_MS, 0L))
    val restoredType = prefix in RESTORED_CLIENT_ID_PREFIXES
    val ownPackage = context.packageName
    // Reading back our own copies needs the read permission for the type; without it there is
    // nothing to reconcile and nothing to report.
    val withinReach = client.ownRecordsIn(ownPackage, recordType, staleSweepRange(now, restoredType)) ?: return
    val beyondReach = if (restoredType) {
        client.ownRecordsIn(ownPackage, recordType, beyondRestoreRange(now)).orEmpty()
    } else {
        emptyList()
    }
    val swept = { record: T ->
        SweptRecord(
            id = record.metadata.id,
            clientRecordId = record.metadata.clientRecordId,
            writtenAt = record.metadata.lastModifiedTime,
            span = span?.invoke(record),
        )
    }
    val stale = staleRecordIds(
        withinReach = withinReach.map(swept),
        beyondReach = beyondReach.map(swept),
        restoredType = restoredType,
        adoptedAt = adoptedAt,
        prefix = prefix,
        desiredIds = desiredIds,
    )
    // In the batches the writes go in: the first sweep to look beyond the restore's reach may find
    // years of deletions waiting, and one call naming them all is one the platform can turn down.
    healthWriteBatches(stale).forEach { client.deleteIfAllowed(recordType, recordIdsList = it) }
    // A record withdrawn is written again when its row returns saying the same: a period deleted and
    // marked again on the same days would otherwise read as published as it stands ([insertChanged]).
    val withdrawn = stale.toSet()
    (withinReach + beyondReach).filter { it.metadata.id in withdrawn }
        .forEach { record -> record.metadata.clientRecordId?.let(publishedRecords::remove) }
}

/**
 * One record of ours as the sweep weighs it: its Health Connect [id], the [clientRecordId] it was
 * published under, when it was [writtenAt], and the stretch of time it covers ([span]) where the
 * caller says it matters.
 */
internal class SweptRecord(
    val id: String,
    val clientRecordId: String?,
    val writtenAt: Instant,
    val span: Any? = null,
)

/**
 * The Health Connect ids [removeStalePwnFitRecords] withdraws: records of this [prefix] that no row
 * claims ([desiredIds]) and that the rules allow to go — [withdrawablePublishedRecord] for what lies
 * within the restore's reach, [withdrawableBeyondRestore] for what lies beyond it. Out here, where a
 * table can name every case, because this is the one place that decides what leaves the store a
 * reinstall restores from.
 */
internal fun staleRecordIds(
    withinReach: List<SweptRecord>,
    beyondReach: List<SweptRecord>,
    restoredType: Boolean,
    adoptedAt: Instant,
    prefix: String,
    desiredIds: Set<String>,
): List<String> {
    // What the records a row here stands behind cover: the read has just returned them too.
    val claimedSpans = withinReach.filter { it.clientRecordId in desiredIds }.mapNotNullTo(HashSet<Any>()) { it.span }
    return withinReach
        .filter {
            val republished = it.span != null && it.span in claimedSpans
            withdrawablePublishedRecord(restoredType, it.writtenAt, adoptedAt, republished)
        }
        .plus(beyondReach.filter { withdrawableBeyondRestore(it.writtenAt, adoptedAt) })
        .filter {
            val clientRecordId = it.clientRecordId
            clientRecordId != null &&
                clientRecordId.startsWith(prefix) &&
                clientRecordId !in desiredIds
        }
        .map { it.id }
        // Once each: a record lying on the edge between the two ranges may have been read by both.
        .distinct()
}

/**
 * Every record of ours of one type in [range], or null when reading the type back is not allowed.
 *
 * Every page of them, not the first: the sweep is the only thing that removes a record for an entry
 * the person deleted, and it can only remove what it has read. On a history longer than one page
 * the oldest rows are the ones that fall off the end — exactly the ones a person is most likely to
 * be tidying up — and a deletion that quietly did nothing looks identical to one that worked,
 * because nothing here reports back.
 */
@Suppress("SwallowedException")
private suspend fun <T : Record> HealthConnectClient.ownRecordsIn(
    ownPackage: String,
    recordType: KClass<T>,
    range: TimeRangeFilter,
): List<T>? {
    val records = mutableListOf<T>()
    var token: String? = null
    do {
        val page = try {
            readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = range,
                    dataOriginFilter = setOf(DataOrigin(ownPackage)),
                    ascendingOrder = true,
                    pageSize = 1_000,
                    pageToken = token,
                ),
            )
        } catch (denied: SecurityException) {
            return null
        }
        records += page.records
        token = page.pageToken
        // Empty as well as null, as every other paged read here ends: Health Connect's own guide
        // says the last page may carry "", and a loop waiting for null would then read it for
        // ever, the list growing and the exchange's lock never let go.
    } while (!token.isNullOrEmpty())
    return records
}

/**
 * How far back a sweep is allowed to read an empty spot in the journal as a deletion.
 *
 * A restore reaches [HEALTH_IMPORT_DAYS] back. Beyond that the journal knows nothing about a record
 * of ours, and reading its silence as a deletion would make a reinstall lose every year but the
 * last from both stores at once. What lies beyond is [beyondRestoreRange], under a rule of its own.
 */
private fun staleSweepRange(now: Instant, restoredType: Boolean): TimeRangeFilter {
    val until = now.plus(Duration.ofMinutes(2))
    return if (restoredType) {
        TimeRangeFilter.between(now.minus(Duration.ofDays(HEALTH_IMPORT_DAYS)), until)
    } else {
        TimeRangeFilter.before(until)
    }
}

/** Everything of a restored type that [staleSweepRange] stops short of. */
private fun beyondRestoreRange(now: Instant): TimeRangeFilter =
    TimeRangeFilter.before(now.minus(Duration.ofDays(HEALTH_IMPORT_DAYS)))

/**
 * Whether an unclaimed record of ours may be withdrawn.
 *
 * For a type the sync reads back, an empty spot in the journal is a deletion. For every other one
 * it is only ever the previous install's history, which no import will ever return: there, this
 * install may withdraw what it published itself and nothing else — but for a record a row here has
 * since [republished] over the same stretch of time, which is no longer the only copy of anything.
 */
internal fun withdrawablePublishedRecord(
    restoredType: Boolean,
    writtenAt: Instant,
    adoptedAt: Instant,
    republished: Boolean = false,
): Boolean = restoredType || republished || !writtenAt.isBefore(adoptedAt)

/**
 * The same question for a record dated before the restore reaches back to.
 *
 * The restore never read it, so the journal's silence alone is not a deletion: it may be a year
 * this install has never seen. A record this install wrote is another matter. The row was here to
 * be published, and every exchange builds the record of every row still standing, so nothing claiming
 * the record now means the row was deleted, or edited until it no longer builds one. Left standing,
 * a weigh-in or an act deleted here more than a year after its date would stay in Health Connect
 * for good, for every app allowed the type to read — where a deletion is promised to follow at the
 * next exchange. An install with no adoption instant on record cannot tell its own records from an
 * earlier install's, and withdraws nothing this old.
 */
internal fun withdrawableBeyondRestore(writtenAt: Instant, adoptedAt: Instant): Boolean =
    adoptedAt != Instant.EPOCH && !writtenAt.isBefore(adoptedAt)

/**
 * Whether an unclaimed record of ours is history to restore rather than something deleted.
 *
 * A row this install wrote itself was standing in the journal when it was written, so nothing
 * claiming it now is a deletion — one made while Health Connect happened to be out of reach, since
 * a deletion it can reach withdraws the record on the spot. Only what an earlier install left
 * behind is history this journal has never held. An install with no adoption instant on record yet
 * restores unconditionally.
 */
internal fun adoptablePublishedRecord(writtenAt: Instant, adoptedAt: Instant): Boolean =
    adoptedAt == Instant.EPOCH || writtenAt.isBefore(adoptedAt)

/**
 * What each external app gave on each day of the window, asked of Health Connect one package at a
 * time.
 *
 * The joint aggregate above names nobody: it is the platform's deduplicated figure over every
 * external origin at once, and a person with a watch, a phone and a fitness app is shown it without
 * being told which of them it came from. Narrowing the origin filter to a single package asks the
 * same question of that package alone, and the platform applies its own deduplication inside the
 * answer — which is why the counts are asked for rather than summed off the raw records read a
 * moment ago: one app can publish the same walk as a session and again as a daily total.
 *
 * The figures are provenance and never arithmetic. Two of them can describe the same steps twice,
 * so nothing adds them, and [ExternalDaySteps] stays the one total the app counts with.
 *
 * Nothing that happens in here may cost the exchange anything. These calls multiply the aggregate
 * requests of a sync by the number of writing apps, and Health Connect's own rate limiter answers
 * an excess of them with an exception; the joint total, the export that runs after this and the
 * whole rest of the exchange need no share at all and must not fall with one. So a package that
 * answers with anything at all costs its own rows and nothing else, and the screen says
 * "Health Connect" for those days.
 */
private suspend fun originStepDays(
    client: HealthConnectClient,
    origins: Set<DataOrigin>,
    firstDay: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
): List<OriginStepShare> = origins.map { origin ->
    runCatching {
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    firstDay.atStartOfDay(),
                    LocalDateTime.ofInstant(now, zone),
                ),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = setOf(origin),
            ),
        ).mapNotNull { bucket ->
            val day = bucket.startTime.toLocalDate()
            val count = bucket.result[StepsRecord.COUNT_TOTAL]?.coerceAtLeast(0L) ?: 0L
            if (count > 0L && day in firstDay..today) {
                ExternalOriginDaySteps(origin.packageName, day.toString(), count)
            } else {
                null
            }
        }
    }.onFailure { if (it is CancellationException) throw it }
        .fold(
            onSuccess = { days -> OriginStepShare(origin.packageName, answered = true, days = days) },
            // See above: a share is never worth an exchange. A cancellation is the exchange's own
            // answer, not this package's, and is rethrown above.
            onFailure = { OriginStepShare(origin.packageName, answered = false, days = emptyList()) },
        )
}

/**
 * One app's answer to the per-origin aggregate: whether it came at all, and what was in it.
 *
 * The difference matters. Health Connect answers "nothing" for an app the person has not put on its
 * source list, and it answers with an exception when it is rate-limited; only the first is the app
 * being left out of the totals, and the second must not look like it.
 */
private data class OriginStepShare(
    val packageName: String,
    val answered: Boolean,
    val days: List<ExternalOriginDaySteps>,
)

/**
 * Every step total Health Connect will hand over for the window, or null when it will not.
 *
 * Null is the distinction the caller cannot make from an empty map: a phone whose steps nobody
 * else records answers with nothing, and so does a phone whose owner never allowed the read. The
 * second must not be written down as a successful exchange reporting zero days.
 */
// The refusal itself is the whole of the information; a SecurityException from Health Connect
// carries no detail worth passing on, and the null it becomes is what the card reports.
@Suppress("SwallowedException")
private suspend fun externalStepTotals(
    context: android.content.Context,
    client: HealthConnectClient,
    now: Instant,
    zone: ZoneId,
    firstDay: LocalDate,
    today: LocalDate,
): ExternalStepTotals? = try {
    // Asked before anything is read, because the answer is what the card has to say when the day
    // stays at zero. A refusal also arrives as a SecurityException; this is the same fact named in
    // advance, and it costs one call in an exchange that already makes dozens. A query that fails
    // for its own reasons is not an answer, so the read goes ahead and finds out.
    val allowed = runCatching {
        HealthPermission.getReadPermission(StepsRecord::class) in
            client.permissionController.getGrantedPermissions()
    }.onFailure { if (it is CancellationException) throw it }.getOrDefault(true)
    if (!allowed) {
        null
    } else {
        val raw = externalStepOrigins(context, client, now, firstDay.atStartOfDay(zone).toInstant(), zone)
        val origins = raw.origins
        if (origins.isEmpty()) {
            ExternalStepTotals(origins, emptyMap())
        } else {
            val byDay = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        firstDay.atStartOfDay(),
                        LocalDateTime.ofInstant(now, zone),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = origins,
                ),
            ).mapNotNull { bucket ->
                val day = bucket.startTime.toLocalDate()
                val count = bucket.result[StepsRecord.COUNT_TOTAL]?.coerceAtLeast(0L) ?: 0L
                if (count > 0L && day in firstDay..today) day to count else null
            }.toMap()
            ExternalStepTotals(origins, byDay, raw.daysByOrigin)
        }
    }
} catch (denied: SecurityException) {
    // Steps the person did not allow reading sync nothing — but a refusal on this one type must
    // not take the rest of the exchange down with it, and must not read as "nobody counted a
    // thing" either: the caller clears the window's external rows before it repopulates them.
    null
}

/** What Health Connect answered for the window: who else wrote steps into it, and their day totals. */
private data class ExternalStepTotals(
    val origins: Set<DataOrigin>,
    val byDay: Map<LocalDate, Long>,
    val daysByOrigin: Map<String, Set<LocalDate>> = emptyMap(),
)

/** Which apps besides this one wrote steps into the window, and which days each of them wrote on. */
private data class ExternalStepRecords(
    val origins: Set<DataOrigin>,
    /** Days each app has a record on: evidence of who wrote, never a total. The counts stay unread. */
    val daysByOrigin: Map<String, Set<LocalDate>>,
)

/** Which apps besides this one wrote steps into the window; the totals themselves come from the aggregate. */
private suspend fun externalStepOrigins(
    context: android.content.Context,
    client: HealthConnectClient,
    now: Instant,
    rangeStart: Instant,
    zone: ZoneId,
): ExternalStepRecords {
    val currentDevicePackageName = currentDeviceStepPackageName(context)
    val origins = mutableSetOf<DataOrigin>()
    val daysByOrigin = mutableMapOf<String, MutableSet<LocalDate>>()
    var pageToken: String? = null
    do {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(rangeStart, now),
                ascendingOrder = true,
                pageSize = 1_000,
                pageToken = pageToken,
            ),
        )
        response.records
            .asSequence()
            .filter {
                it.count > 0 &&
                    isExternalStepOrigin(
                        packageName = it.metadata.dataOrigin.packageName,
                        ownPackageName = context.packageName,
                        currentDevicePackageName = currentDevicePackageName,
                    ) &&
                    it.endTime > it.startTime
            }
            .forEach { record ->
                origins += record.metadata.dataOrigin
                daysByOrigin.getOrPut(record.metadata.dataOrigin.packageName) { mutableSetOf() } +=
                    record.startTime.atZone(zone).toLocalDate()
            }
        pageToken = response.pageToken
    } while (!pageToken.isNullOrEmpty())
    return ExternalStepRecords(origins, daysByOrigin)
}

/**
 * Moves step data in both directions, one local day at a time.
 *
 * Reading: a day another source counted higher than this phone is imported, so a watch worn all day
 * wins over a phone left on a desk instead of the app showing 0 next to Health Connect's 10,000.
 * Writing: a day nobody else reported is published, and a day someone else took over withdraws the
 * record this app had published earlier. Rows with unknown provenance stay visible but are never
 * published as phone-owned data.
 *
 * Health Connect's aggregate API applies the user's source priority before producing a total. Raw
 * records are inspected only to discover the external origins that should participate; their
 * counts are never added here because several apps can publish the same wearable steps.
 */
// One branch per step source that already covers the day.
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "SwallowedException")
private suspend fun syncStepDays(
    context: android.content.Context,
    db: StepsDb,
    client: HealthConnectClient,
    now: Instant,
): StepSyncResult {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val firstDay = today.minusDays(STEP_EXPORT_LOOKBACK_DAYS - 1)
    val external = externalStepTotals(context, client, now, zone, firstDay, today)
        ?: return StepSyncResult(0, 0, 0, readable = false)
    // Before anything below is written from that answer. Read after the app had left the
    // foreground it names no other app, and the window's days from a watch would be cleared for it
    // and the phone's own published in their place.
    requireEveryAppReadable()
    val externalTotals = external.byDay
    val published = restoreOwnStepDays(context, db, client, now, today, firstDay)
    val localDays = db.dao().allLocalDays().mapNotNull { row ->
        val day = row.day.toLocalDateOrNull()
        if (day != null && day in firstDay..today) day to row.steps else null
    }.toMap()
    val legacyDays = db.dao()
        .legacyStepDaysBetween(firstDay.toString(), today.toString())
        .mapNotNullTo(mutableSetOf()) { it.day.toLocalDateOrNull() }
    val merge = mergeStepDays(localDays, externalTotals)
    val exportDays = exportableStepDays(merge.exports, legacyDays)
    // The same aggregate once more per app, so the sources screen can say which of them the day's
    // figure came from — asked of the platform rather than counted off the raw records read above,
    // because only it knows which of one app's own records overlap. It is also what catches an app
    // Health Connect hands us records for and counts in nothing: the answer comes back empty for a
    // writer the person never added to the Activity source list, and no total anywhere holds it.
    val shares = if (external.origins.isEmpty()) {
        emptyList()
    } else {
        originStepDays(client, external.origins, firstDay, today, now, zone)
    }
    val originTotals = shares.flatMap(OriginStepShare::days)
    val uncounted = uncountedStepOrigins(
        rawDaysByOrigin = external.daysByOrigin,
        answered = shares.filter(OriginStepShare::answered)
            .mapTo(mutableSetOf(), OriginStepShare::packageName),
        counted = originTotals.mapTo(mutableSetOf(), ExternalOriginDaySteps::packageName),
    )
    // This app's own invisibility first: it is the one the person can do something about today, and
    // the one a rename leaves behind.
    val uncountedSource = if (ownStepsUncounted(context, client, published, firstDay, now, zone)) {
        context.packageName
    } else {
        uncounted.firstOrNull { installedPackage(context, it) }
    }

    db.withTransaction {
        db.dao().deleteExternalDaysBetween(firstDay.toString(), today.toString())
        db.dao().deleteExternalOriginDaysBetween(firstDay.toString(), today.toString())
        if (merge.external.isNotEmpty()) {
            db.dao().putExternalDays(
                merge.external.map { (day, steps) -> ExternalDaySteps(day.toString(), steps) },
            )
            // Only the days the total beside them was kept for. An app's own figure on a day
            // Health Connect reported nothing for is a share of a total that is not there.
            val kept = merge.external.keys.mapTo(mutableSetOf(), LocalDate::toString)
            db.dao().putExternalOriginDays(originTotals.filter { it.day in kept })
        }
    }
    // Another source's total moves the day's count and writes no journal row, so nothing that
    // follows a row's write redraws the widgets that show it.
    db.refreshWidgetsAfterWrite(WIDGETS_OF_STEPS)

    // Never the running day. It is still being counted, and withdrawing what is published for it
    // races whatever else is writing today: on a phone carrying two installs of this app, each
    // withdrawal makes the other's next exchange see an uncovered day and republish, and the pair
    // can hold each other at nothing. A day that is over does not move again, so withdrawing it is
    // safe.
    val withdrawnDays = stepDaysToWithdraw(merge.covered + legacyDays, published.keys, today)
    client.deleteIfAllowed(
        StepsRecord::class,
        clientRecordIdsList = withdrawnDays.map { STEP_CLIENT_ID_PREFIX + it },
    )

    val device = Device(
        type = Device.TYPE_PHONE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
    )
    // The count itself would only ever replace a day upwards, so a day corrected down — a restored
    // backup — would keep the higher figure here for good.
    val version = publishVersion(context, now)
    // Through [healthRecords], as every other family is built: a day past the million steps a
    // record may hold — a backup edited by hand can carry one — would otherwise be thrown from
    // here, and every exchange would end at its first stage on it.
    val records = exportDays.healthRecords { day ->
        val steps = localDays[day] ?: return@healthRecords null
        val start = day.atStartOfDay(zone).toInstant()
        val end = if (day == today) now else day.plusDays(1).atStartOfDay(zone).toInstant()
        if (end <= start) return@healthRecords null
        StepsRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            count = steps,
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = STEP_CLIENT_ID_PREFIX + day,
                clientRecordVersion = version,
            ),
        )
    }
    val written = client.insertIfAllowed(records)

    return StepSyncResult(
        // What was accepted, not what was built.
        exportedDays = if (written) records.size else 0,
        externallyCoveredDays = merge.covered.size,
        importedDays = merge.imports.size,
        readable = true,
        writable = written,
        uncountedSource = uncountedSource,
    )
}

/**
 * The days whose published record is withdrawn: the ones another source has [takenOver], never the
 * running day, and only those this app has a record [published] for.
 *
 * Naming every taken-over day of the window at every exchange, a record there or not, would be a
 * wasted call on the platform's own Health Connect; the one Android 13 installs as an app is
 * documented to answer a delete of a record that does not exist with an exception, which would end
 * the exchange at its first stage, every time. What is published was read back a moment before
 * ([restoreOwnStepDays]), so nothing has to be named on trust.
 */
internal fun stepDaysToWithdraw(
    takenOver: Collection<LocalDate>,
    published: Set<LocalDate>,
    today: LocalDate,
): List<LocalDate> = takenOver.distinct().filter { it != today && it in published }

/**
 * Puts this app's own published step days back into the local table when it has none for them.
 *
 * The counter starts from zero after a reinstall, so without this the history the app itself wrote
 * to Health Connect would be visible everywhere except in the app that produced it. Today is left
 * out: the accumulator owns the running day and adding a total from a previous install underneath
 * it would count the morning twice.
 */
@Suppress("SwallowedException")
private suspend fun restoreOwnStepDays(
    context: android.content.Context,
    db: StepsDb,
    client: HealthConnectClient,
    now: Instant,
    today: LocalDate,
    firstDay: LocalDate,
): Map<LocalDate, Long> {
    val since = today.minusDays(STEP_RESTORE_LOOKBACK_DAYS).atStartOfDay(ZoneId.systemDefault())
    // What this app has published inside the aggregate's own window, for the visibility check
    // below. The restore reads a year; scoring a year against a thirty-day aggregate would accuse
    // Health Connect of dropping days it was never asked about.
    val published = mutableMapOf<LocalDate, Long>()
    var pageToken: String? = null
    do {
        // No step read permission, no restore — and no aborted sync either.
        val response = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(since.toInstant(), now),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                    ascendingOrder = true,
                    pageSize = 1_000,
                    pageToken = pageToken,
                ),
            )
        } catch (denied: SecurityException) {
            return published
        }
        response.records.forEach { record ->
            val clientRecordId = record.metadata.clientRecordId ?: return@forEach
            if (!clientRecordId.startsWith(STEP_CLIENT_ID_PREFIX)) return@forEach
            val day = runCatching {
                LocalDate.parse(clientRecordId.removePrefix(STEP_CLIENT_ID_PREFIX))
            }.getOrNull() ?: return@forEach
            if (record.count > 0L && day in firstDay..today) published[day] = record.count
            if (day >= today || record.count <= 0L) return@forEach
            if (db.dao().localStepsOn(day.toString()) != null) return@forEach
            db.dao().put(DaySteps(day.toString(), record.count))
        }
        pageToken = response.pageToken
    } while (!pageToken.isNullOrEmpty())
    return published
}

/**
 * Whether this install's own published days are missing from Health Connect's own totals.
 *
 * The records are there — [restoreOwnStepDays] has just read them back under this package's origin —
 * and the aggregate over that same origin and window answers nothing for any day they cover. That is
 * the platform saying this app is not one of the sources it counts: what it publishes shows in Health
 * Connect's entry list and in no total anywhere, including the totals every other app reads. Only the
 * person can change it, on the Activity source list.
 *
 * A running day alone claims nothing, and a failed call claims nothing at all: what this leads to is
 * an instruction to go and change a setting, so a false accusation costs more than the silence.
 */
private suspend fun ownStepsUncounted(
    context: android.content.Context,
    client: HealthConnectClient,
    published: Map<LocalDate, Long>,
    firstDay: LocalDate,
    now: Instant,
    zone: ZoneId,
): Boolean {
    val today = now.atZone(zone).toLocalDate()
    val completed = published.keys.filter { it < today }
    if (completed.isEmpty()) return false
    val counted = runCatching {
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    firstDay.atStartOfDay(),
                    LocalDateTime.ofInstant(now, zone),
                ),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = setOf(DataOrigin(context.packageName)),
            ),
        ).associate { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    // A call that failed accuses nobody; days it answered nothing for on every completed day this
    // app published are the platform declining to count this app at all.
    return counted != null && completed.all { (counted[it] ?: 0L) <= 0L }
}

/** An app that is gone cannot be put back on a list, so it is never the one named. */
private fun installedPackage(context: android.content.Context, packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess

/**
 * Health Connect's native phone counter is the same physical source as our SensorManager counter.
 * Recent Health Connect versions give it an app-scoped synthetic package name; older rows use
 * `android`.
 *
 * The name the platform gave last time stands in when asking fails — a timeout, an error. Without
 * it, that exchange would read this phone's own counter as another app: the month's external days
 * would be rewritten from the phone's steps counted without the motion gate, and the days they
 * "covered" would be withdrawn from Health Connect, until the next exchange put both back.
 *
 * Limitation: a phone whose first lookup fails has no earlier answer and still reads its counter as
 * another app's for that exchange.
 */
// The API-34 branch is explicit below; lint cannot retain that fact across the suspend call.
@android.annotation.SuppressLint("NewApi")
private suspend fun currentDeviceStepPackageName(context: android.content.Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return null
    }
    val extension = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    if (extension < 11) return null
    val answered = if (extension >= 22) {
        readCurrentDeviceStepPackageName(context)
    } else {
        readLegacyCurrentDeviceStepPackageName(context)
    }
    val prefs = syncPrefs(context)
    if (answered != null) prefs.edit { putString(KEY_DEVICE_STEP_PACKAGE, answered) }
    return answered ?: prefs.getString(KEY_DEVICE_STEP_PACKAGE, null)
}

/** Compatibility path for the zero-argument U-extension API omitted from newer SDK stubs. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun readLegacyCurrentDeviceStepPackageName(context: android.content.Context): String? {
    val manager = context.getSystemService(HealthConnectManager::class.java) ?: return null
    val sourceMethod = manager.javaClass.methods.firstOrNull {
        it.name == "getCurrentDeviceDataSource" && it.parameterCount == 0
    } ?: return null
    val source = runCatching { sourceMethod.invoke(manager) }.getOrNull() ?: return null
    val origin = runCatching {
        source.javaClass.getMethod("getDeviceDataOrigin").invoke(source)
    }.getOrNull() ?: return null
    return runCatching {
        origin.javaClass.getMethod("getPackageName").invoke(origin) as? String
    }.getOrNull()
}

/**
 * The whole sync holds [healthSyncMutex] while this waits, so it cannot wait forever: a platform
 * that answers neither callback would leave every later sync blocked until the process dies.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@RequiresExtension(extension = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, version = 22)
private suspend fun readCurrentDeviceStepPackageName(context: android.content.Context): String? {
    val manager = context.getSystemService(HealthConnectManager::class.java) ?: return null
    return withTimeoutOrNull(5_000L) {
        suspendCancellableCoroutine<String?> { continuation ->
            manager.getCurrentDeviceDataSource(
                context.mainExecutor,
                object : OutcomeReceiver<DeviceDataSource, HealthConnectException> {
                    override fun onResult(result: DeviceDataSource) {
                        continuation.resume(result.deviceDataOrigin.packageName)
                    }

                    override fun onError(error: HealthConnectException) {
                        continuation.resume(null)
                    }
                },
            )
        }
    }
}
