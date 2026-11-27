package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Where the photographs taken on a walk are kept: the app's own files, like progress photographs. */
internal const val WALK_PHOTO_DIR = "walk-photos"

internal fun walkPhotoDir(context: Context): File = File(context.filesDir, WALK_PHOTO_DIR)

/**
 * The name a walk is given the moment its recording starts: one of `walk_names`, in the language
 * the app speaks then, and stored as text so it does not change when the language does.
 */
internal fun randomWalkName(resources: Resources): String = resources.getStringArray(R.array.walk_names).random()

/**
 * What the walk is called. A route with no stored name gets one picked by the second it started, so
 * in one language it reads the same each time it is opened — in the app's current language, since
 * the name is not stored. The start rather than the row's id, which a backup's restore numbers
 * afresh.
 */
internal fun TrackEntity.title(resources: Resources): String = name.ifBlank {
    val names = resources.getStringArray(R.array.walk_names)
    names[Math.floorMod(startedAt / 1000, names.size.toLong()).toInt()]
}

/**
 * Deletes a finished walk for good: its row and, by their cascades, every fix and every photograph's
 * row; then the photographs' files, which only those rows named, and whatever copy a share left in
 * the cache. A walk still recording is left alone — the service is writing into it — and so is one
 * already gone.
 *
 * A route is the one record here that says where its owner was, minute by minute. Like
 * [deleteJournalEvent] it leaves a tombstone, under [routeTombstoneKey]: a backup written before
 * the deletion still holds the walk, and the next import would bring it back, fixes, photographs
 * and all ([withoutDeletedRoutes]). What was published to Health Connect from the walk — its
 * distance, climb and speeds — needs no word from here: the next exchange withdraws every such
 * record no route claims any more.
 *
 * Not cancellable once begun: the walk's page closes the moment the row is gone, and the files have
 * to go with it.
 */
internal suspend fun StepsDb.deleteWalk(context: Context, track: TrackEntity) = withContext(NonCancellable) {
    val photos = withTransaction {
        val dao = dao()
        // Read before the row goes: the cascade takes the photographs' rows with it.
        val taken = dao.allTrackPhotos().filter { it.trackId == track.id }
        if (dao.deleteTrack(track.id) == 0) {
            null
        } else {
            dao.saveTombstone(JournalTombstone(routeTombstoneKey(track.startedAt), System.currentTimeMillis()))
            taken
        }
    }
    if (photos != null) {
        withContext(Dispatchers.IO) {
            photos.forEach { File(walkPhotoDir(context), it.file).delete() }
            File(File(context.cacheDir, ROUTE_SHARE_DIR), track.id.toString()).deleteRecursively()
        }
    }
}

/**
 * How a deleted route is named once its row is gone: by the moment its recording started. That is
 * what a backup's merge knows a route by on every phone — the row's id is numbered afresh by each
 * restore — and it shares no prefix with the names a journal row leaves ([journalTombstoneKeys]).
 */
internal fun routeTombstoneKey(startedAt: Long): String = "$ROUTE_TOMBSTONE_PREFIX$startedAt"

/** What a deleted route's key begins with; a backup's import knows by it a key every phone reads alike. */
internal const val ROUTE_TOMBSTONE_PREFIX = "track:"

/**
 * A backup's routes less the ones deleted here on purpose, [tombstoned] holding every tombstone's
 * key: an import must not bring those back. Their fixes and photographs stay out with them, since
 * the import takes neither for a route it did not take.
 */
internal fun withoutDeletedRoutes(tracks: List<TrackEntity>, tombstoned: Set<String>): List<TrackEntity> =
    tracks.filterNot { routeTombstoneKey(it.startedAt) in tombstoned }

/**
 * Roughly what a recorded walk burned on top of rest: each stretch on foot costed by its own speed,
 * the walker's height and weight, and the slope it went up or came down.
 *
 * - Walking on the level is Ludlow and Weyand's height–weight–speed equation (J Appl Physiol 2016):
 *   VO₂ = 3.85 + 5.97·v²/h ml/kg/min, v in m/s and h the height in metres. The same speed is cheaper
 *   on longer legs, and the cost climbs with the square of the pace.
 * - Running on the level is the ACSM running equation, VO₂ = 3.5 + 0.2·v ml/kg/min, v in m/min.
 * - The slope is added per metre: walking by the grade term of the Load Carriage Decision Aid
 *   (Looney et al., Med Sci Sports Exerc 2019), which charges a climb, gives some back on a gentle
 *   descent and charges a steep one again; running by Minetti's cost on a gradient (J Appl Physiol
 *   2002) less his cost on the level.
 * - The person's own rest, [restingKcalPerHour], comes back out of every second, as
 *   [netActivityCalories] takes it out of a workout: the day's resting figure already holds it.
 *
 * The slope is read over [SLOPE_WINDOW_M] of ground at a time, and on past it until the rise clears
 * the instrument's own step ([elevationStepThresholdM]), from the smoothed altitude the climb is
 * counted from ([smoothedElevationM]). A stretch that ends under that step — at a pause, a ride or
 * the end of the walk — reads as level ground, so an altimeter's wander is not charged as a hill,
 * while a long gentle slope still is. A ride, a stop and movement not yet told apart cost nothing,
 * and neither does a pause.
 *
 * Limitation: the profile's weight today, not the weight on the day of an old walk; the journal's
 * timeline would have to be read for that.
 */
internal fun walkCalories(
    points: List<TrackPointEntity>,
    weightKg: Double,
    heightCm: Int,
    restingKcalPerHour: Double,
): Double {
    if (weightKg <= 0.0) return 0.0
    val heightM = if (heightCm > 0) heightCm / CM_PER_M else DEFAULT_HEIGHT_M
    val cost = WalkCost(weightKg, heightM, max(0.0, restingKcalPerHour) / SECONDS_PER_HOUR)
    points.forEachIndexed { index, point ->
        // The stretch before a pause ends at the height it stood at; see [WalkCost.climbTo].
        val joins = joinsPrevious(points, index)
        if (!joins) cost.close(point)
        cost.climbTo(point, joins)
        if (joins) cost.stride(points[index - 1], point)
    }
    points.lastOrNull()?.let(cost::close)
    return cost.total
}

/**
 * [walkCalories]'s running sum, and the stretch of ground it is reading the slope over: what that
 * stretch cost on the level so far, and how much of it was walked and how much run.
 */
private class WalkCost(private val weightKg: Double, private val heightM: Double, private val restPerSecond: Double) {
    var total = 0.0
        private set
    private var elevation: Double? = null
    private var from: Double? = null
    private var level = 0.0
    private var walked = 0.0
    private var ran = 0.0

    /**
     * The smoothed altitude at [point]. After a pause it starts afresh rather than [joins] the mean:
     * the walker may come back at any height, and a mean catching up would be charged as a climb.
     */
    fun climbTo(point: TrackPointEntity, joins: Boolean) {
        val altitude = point.barometricAltitudeM?.toDouble() ?: point.altitudeM
        elevation = smoothedElevationM(altitude, elevation.takeIf { joins })
        if (!joins) from = elevation
    }

    /** The stride from [before] into [point] on the level; anything but a walk or a run ends the stretch. */
    fun stride(before: TrackPointEntity, point: TrackPointEntity) {
        val running = point.movementType == RouteMovement.RUNNING.name
        if (!running && point.movementType != RouteMovement.WALKING.name) {
            close(point)
            return
        }
        val seconds = max(0L, point.timestamp - before.timestamp) / MILLIS_PER_SECOND
        val speed = max(0.0, point.speedMps?.toDouble() ?: 0.0)
        val vo2 = if (running) runningVo2(speed) else walkingVo2(min(speed, MAX_WALKING_MPS), heightM)
        level += (vo2 * weightKg * KCAL_PER_ML_O2 / SECONDS_PER_MINUTE - restPerSecond) * seconds
        if (running) ran += speed * seconds else walked += speed * seconds
        // A rise still under the instrument's step reads on over the next metres rather than being
        // dropped: a steady 2 % never clears a barometer's 1.5 m in fifty, and a hill of it would
        // read as level.
        // A stretch begun with no height yet has nothing to rise from, and closes to start from one.
        val cleared = from == null || abs(riseSoFar()) >= stepOf(point)
        if (walked + ran >= SLOPE_WINDOW_M && cleared) close(point)
    }

    /** The stretch up to [point]: its level cost and what its slope adds or gives back, never below nothing. */
    fun close(point: TrackPointEntity) {
        val rise = riseSoFar()
        val ground = walked + ran
        val step = stepOf(point)
        val grade = if (ground > 0.0 && abs(rise) >= step) (rise / ground).coerceIn(-MAX_GRADE, MAX_GRADE) else 0.0
        val slope = weightKg * (walked * walkingSlopeJoules(grade) + ran * runningSlopeJoules(grade))
        total += max(0.0, level + slope / JOULES_PER_KCAL)
        level = 0.0
        walked = 0.0
        ran = 0.0
        from = elevation
    }

    /** How far the smoothed altitude has gone up since the stretch began; down is less than nothing. */
    private fun riseSoFar(): Double = elevation?.let { now -> from?.let { now - it } } ?: 0.0

    /** The least rise [point]'s instrument can tell from its own wander: [elevationStepThresholdM]. */
    private fun stepOf(point: TrackPointEntity): Double =
        elevationStepThresholdM(point.barometricAltitudeM != null, point.verticalAccuracyM)
}

/** Ludlow and Weyand's height–weight–speed equation: gross ml of oxygen per kg a minute, on the level. */
private fun walkingVo2(speedMps: Double, heightM: Double): Double = 3.85 + 5.97 * speedMps * speedMps / heightM

/** The ACSM running equation, gross ml of oxygen per kg a minute on the level; v × 60 is metres a minute. */
private fun runningVo2(speedMps: Double): Double = 3.5 + 0.2 * speedMps * SECONDS_PER_MINUTE

/**
 * The Load Carriage Decision Aid's grade term, joules per kg for each metre walked at [grade] (a
 * fraction; the equation takes per cent). Cheapest about 10 % down, dearer again past it.
 *
 * Looney et al., Med Sci Sports Exerc 51(9):1954–60 (2019): on top of the level cost, walking at S m/s
 * up G % costs 0.34·S·G·(1 − 1.05^(1 − 1.1^(G + 32))) W/kg, here divided by S. The inner base is 1.1,
 * as the same authors print the equation again (equation 3, doi:10.1249/MSS.0000000000002833, 2022).
 */
private fun walkingSlopeJoules(grade: Double): Double {
    val percent = grade * PER_CENT
    return 0.34 * percent * (1 - 1.05.pow(1 - 1.1.pow(percent + 32)))
}

/** Minetti's cost of running at [grade] less his cost on the level, joules per kg for each metre. */
private fun runningSlopeJoules(grade: Double): Double =
    grade * (19.5 + grade * (46.3 + grade * (-43.3 + grade * (-30.4 + grade * 155.4))))

/** The least ground the slope is read over: long enough for the altitude's wander to stay under its step. */
private const val SLOPE_WINDOW_M = 50.0

/** The steepest ground the slope equations were measured on, Minetti's ±45 %. */
private const val MAX_GRADE = 0.45

/**
 * Past this a walk is a run; a pace the classifier still calls walking is a speed glitch, and the
 * walking equation's square would make one second of it a sprint's cost.
 */
private const val MAX_WALKING_MPS = 2.5

/** A height to cost the stride by when the profile has none. */
private const val DEFAULT_HEIGHT_M = 1.7

/** About five kilocalories for each litre of oxygen, the figure the ACSM equations are used with. */
private const val KCAL_PER_ML_O2 = 0.005

private const val JOULES_PER_KCAL = 4_184.0
private const val CM_PER_M = 100.0
private const val SECONDS_PER_MINUTE = 60.0
private const val SECONDS_PER_HOUR = 3_600.0
private const val MILLIS_PER_SECOND = 1_000.0
