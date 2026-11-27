package com.pwnall.health

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The camera button's action while a walk records: the phone's own camera app takes the picture,
 * and the walk keeps it together with the spot it was taken from.
 *
 * No CAMERA permission, as everywhere else in the app — the camera app writes one file in the cache
 * and a shrunk copy is kept. What this adds is the spot. Where comes from the route itself, its
 * accepted fix nearest the shutter; which way the lens looked, how far it tilted and how the phone
 * was turned come from the rotation sensor, which [WalkCompass] listens to only while the camera
 * app is in front. A picture picked from the gallery carries none of that, so there is no gallery
 * door here: on a phone with no camera app the button says so instead.
 *
 * [trackId] is the walk being recorded, or null when none is, and the action then does nothing.
 * [onSaved] gets the word to confirm a photograph kept; [onFailed] the word for one that could not be,
 * or for a phone with no camera app to take it. A shot not taken says nothing.
 */
@Composable
internal fun rememberWalkPhotoCapture(
    trackId: Long?,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.walk_photo_saved)
    val failed = stringResource(R.string.walk_photo_failed)
    val noCamera = stringResource(R.string.walk_photo_no_camera)
    // Which walk the shot is for and when the camera opened, saved rather than remembered: the
    // camera is another activity and the process can die behind it. The answer is then delivered to
    // a screen composed afresh, before the walk being recorded has been read back from the database.
    // The moment also names the shot's own file in the cache, so it is saved with it.
    var shotTrack by rememberSaveable { mutableStateOf<Long?>(null) }
    var shotOpenedAt by rememberSaveable { mutableLongStateOf(0L) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val poses = WalkCompass.stop()
        val track = shotTrack
        val openedAt = shotOpenedAt
        shotTrack = null
        // An answer with no shot waiting has no file of its own here, and the moment still saved is
        // the last shot's, whose keep may not have finished with its file.
        if (track != null) {
            if (taken) {
                scope.launch {
                    if (keepWalkPhoto(context, track, openedAt, poses)) onSaved(saved) else onFailed(failed)
                }
            } else {
                walkCaptureFile(sharedCacheDir(context), openedAt).delete()
            }
        }
    }
    return {
        val now = System.currentTimeMillis()
        // A second tap while the camera app is still on its way up would open it twice, and both
        // answers would come back to the one shot. Held off only for a duplicate tap's window, so an
        // answer that never comes back from the camera cannot leave the button dead.
        if (trackId != null && !isDuplicateTap(shotOpenedAt.takeIf { shotTrack != null }, now)) {
            shotTrack = trackId
            shotOpenedAt = now
            val capture = walkCaptureUri(context, walkCaptureFile(sharedCacheDir(context), now))
            WalkCompass.start(context)
            // A phone with no camera app throws on launch. The gallery does not stand in for it here
            // as it does for a meal: a picked picture has no spot to keep.
            runCatching { camera.launch(capture) }.onFailure {
                WalkCompass.stop()
                shotTrack = null
                onFailed(noCamera)
            }
        }
    }
}

/**
 * The camera at the foot of the track screen's map, beside the recording control: a raised disc as
 * tall as that control, so the two read as one row rather than a button with something stuck to it.
 * Drawn whether or not a walk is open, and greyed out until one is — [enabled] — since a photograph
 * belongs to a walk; a paused one takes them too.
 */
@Composable
internal fun WalkPhotoButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OverlayDisc(
        Icons.Outlined.PhotoCamera,
        stringResource(R.string.walk_photo_take),
        onClick,
        modifier,
        size = RECORD_SIZE,
        enabled = enabled,
    )
}

/**
 * Keeps the photograph the camera app wrote into the cache: shrunk into [walkPhotoDir], with a row
 * saying when it was taken and from where. True once the row is written.
 *
 * Not cancellable, because the screen can close while it runs and a photograph that was taken is
 * kept regardless; off the main thread, because it decodes a full-size camera picture. [openedAt]
 * is when the camera app was opened, which names the shot's own file in the cache — the only one
 * this reads and deletes, since the next shot can be taken while this one waits on the database —
 * and [poses] is what [WalkCompass] heard while it was in front.
 */
internal suspend fun keepWalkPhoto(
    context: Context,
    trackId: Long,
    openedAt: Long,
    poses: List<CameraPose>,
): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    val capture = walkCaptureFile(sharedCacheDir(context), openedAt)
    val dao = StepsDb.get(context).dao()
    runCatching {
        // Read out of the camera's own file before anything else is done with it: the copy that is
        // kept is decoded and written again, and carries no EXIF at all.
        val takenAt = shutterMoment(
            exif = captureStamp(capture),
            modified = capture.lastModified().takeIf { it > 0 },
            openedAt = openedAt,
            now = System.currentTimeMillis(),
        )
        val fix = dao.nearestAcceptedPoint(trackId, takenAt)
        // A paused route stores no fixes, so during a pause — the walker may be going on — or before
        // the first fix the route's nearest one can be well behind; whichever of it and the phone's
        // own position is nearer the shutter marks the spot.
        val phone = phonePlace(context, takenAt)
        val fixMarks = fixWins(fix?.let { abs(it.timestamp - takenAt) }, phone?.second)
        val dir = walkPhotoDir(context).apply { mkdirs() }
        // The moment is the name, as with progress photographs. Should a file already hold it, the
        // next free millisecond names the file and stamps the row alike.
        val stamp = generateSequence(takenAt) { it + 1 }.first { !File(dir, walkPhotoName(it)).exists() }
        val file = File(dir, walkPhotoName(stamp))
        writeShrunkJpeg(context, walkCaptureUri(context, capture), file, WALK_PHOTO_SIDE)
        val row = walkPhotoRow(
            trackId = trackId,
            takenAt = stamp,
            fix = fix.takeIf { fixMarks },
            pose = nearestPose(poses, takenAt),
            declinationDeg = declinationFor(context, fix, takenAt),
            here = phone?.first.takeIf { !fixMarks },
        )
        // When the row cannot be written — the walk deleted while the camera was open — the picture
        // goes too, rather than staying behind with nothing pointing at it.
        runCatching { dao.saveTrackPhoto(row) }.onFailure { file.delete() }.getOrThrow()
    }.onFailure { Log.w(TAG, "cannot keep the walk photograph", it) }
        .also { capture.delete() }
        .isSuccess
}

/**
 * The row for a photograph taken at [takenAt] on walk [trackId].
 *
 * [fix] is the route's accepted fix nearest the shutter. It gives the position only when it lies
 * within [FIX_WINDOW_MS] of the shutter — further off, the walker could have been anywhere in
 * between — and it gives it the way the route's own page reads it: the corrected coordinates where
 * correlation set them, the barometer's altitude where there was one.
 *
 * [declinationDeg] turns the compass's magnetic bearing into a true one. It may come from any fix of
 * the walk, near the shutter or not, or from where the phone last knew itself to be before the walk
 * had a fix at all, since the angle between the two norths changes over distances a walk does not
 * cover. Without it the bearing is left out rather than kept off by an unknown angle, and its
 * accuracy goes with it.
 *
 * [here] is where the phone itself knew it was, for a photograph the route has no fix near: taken
 * during a pause, which takes no fixes, or before the route's first. It is used only when [fix]
 * gives no position.
 */
internal fun walkPhotoRow(
    trackId: Long,
    takenAt: Long,
    fix: TrackPointEntity?,
    pose: CameraPose?,
    declinationDeg: Float?,
    here: PhotoPlace? = null,
): TrackPhotoEntity {
    val near = fix?.takeIf { abs(it.timestamp - takenAt) <= FIX_WINDOW_MS }
    val bearing = pose?.bearingDeg?.let { magnetic -> declinationDeg?.let { trueBearing(magnetic, it) } }
    return TrackPhotoEntity(
        trackId = trackId,
        takenAt = takenAt,
        file = walkPhotoName(takenAt),
        lat = near?.let { it.correctedLat ?: it.lat } ?: here?.lat,
        lon = near?.let { it.correctedLon ?: it.lon } ?: here?.lon,
        altitudeM = if (near != null) near.barometricAltitudeM?.toDouble() ?: near.altitudeM else here?.altitudeM,
        accuracyM = if (near != null) near.accuracyM else here?.accuracyM,
        bearingDeg = bearing,
        bearingAccuracyDeg = pose?.bearingAccuracyDeg?.takeIf { bearing != null },
        pitchDeg = pose?.pitchDeg,
        rollDeg = pose?.rollDeg,
    )
}

/**
 * When the shutter fell: the camera's own stamp, else the moment its file was last written, else
 * now — and whichever it is, no earlier than the camera was opened and no later than now.
 *
 * A camera app whose clock or zone disagrees with the phone's would otherwise pin the photograph to
 * some other stretch of the route. The slack before the opening, [SHUTTER_SLACK_MS], covers a stamp
 * cut to the whole second and a camera clock running a little apart from this one.
 */
internal fun shutterMoment(exif: Long?, modified: Long?, openedAt: Long, now: Long): Long {
    val earliest = openedAt - SHUTTER_SLACK_MS
    // A clock set back while the camera was open leaves no window at all; now is then the one moment
    // still known to be right.
    if (earliest > now) return now
    return (exif ?: modified ?: now).coerceIn(earliest, now)
}

/**
 * EXIF's three fields for one moment — the wall-clock stamp, its fraction of a second and its offset
 * from UTC — as milliseconds, or null when the stamp is not a date at all.
 *
 * The fraction is digits after an implied decimal point, so "5" is half a second and "123456" is cut
 * to the millisecond. A camera that wrote no offset wrote the wall clock of wherever it was, which
 * is read against [zone], the phone's, as [exifTakenAt] reads a picked picture.
 */
internal fun exifMoment(
    stamp: String,
    subSecond: String?,
    offset: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    val whole = exifTakenAt(stamp.trim(), exifOffset(offset) ?: zone) ?: return null
    return whole + exifFractionMillis(subSecond)
}

private fun exifOffset(text: String?): ZoneOffset? =
    text?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }

private fun exifFractionMillis(text: String?): Long {
    val digits = text?.trim().orEmpty()
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return 0
    return digits.take(MILLIS_DIGITS).padEnd(MILLIS_DIGITS, '0').toLong()
}

/** The shutter's moment as the camera app wrote it into [file], or null where it wrote none. Blocking. */
private fun captureStamp(file: File): Long? = runCatching {
    val exif = ExifInterface(file)
    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { stamp ->
        exifMoment(
            stamp,
            exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        )
    }
}.getOrNull()

/**
 * How far magnetic north lies east of true north where a photograph taken at [at] was taken, in
 * degrees: read at the walk's accepted fix nearest the shutter, [fix], and else — the first shots of
 * a walk, before the satellites answer — wherever the phone last knew itself to be, which is near
 * enough for an angle that changes over hundreds of kilometres. Null with neither.
 */
private fun declinationFor(context: Context, fix: TrackPointEntity?, at: Long): Float? =
    if (fix != null) {
        declinationAt(fix.correctedLat ?: fix.lat, fix.correctedLon ?: fix.lon, fix.altitudeM, at)
    } else {
        lastKnownLocation(context)?.let {
            declinationAt(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else null, at)
        }
    }

/** How far magnetic north lies east of true north at a place and moment, in degrees. */
internal fun declinationAt(lat: Double, lon: Double, altitudeM: Double?, at: Long): Float =
    GeomagneticField(lat.toFloat(), lon.toFloat(), (altitudeM ?: 0.0).toFloat(), at).declination

/**
 * Where Android last knew the phone to be, without asking for a fix: the platform's fused provider's
 * last position, else the satellites', else the network's. The route records under the location
 * grant, but a grant taken back since, or a provider the phone lacks, is no place rather than a lost
 * shot. Only a position from the last hour counts: one held from before a flight with location off
 * would give another country's declination, and a direction left out beats a wrong one. While a
 * route records, the providers answer the app's own requests, so a fresh one is there even before
 * the route has accepted any.
 */
@SuppressLint("MissingPermission") // guarded by hasApproximateLocation()
private fun lastKnownLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null || !context.hasApproximateLocation()) return null
    val now = SystemClock.elapsedRealtimeNanos()
    return LAST_KNOWN_PROVIDERS.firstNotNullOfOrNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            ?.takeIf { lastKnownIsFresh(now - it.elapsedRealtimeNanos) }
    }
}

/** A position the phone held on its own, outside the route's fixes. */
internal data class PhotoPlace(val lat: Double, val lon: Double, val altitudeM: Double?, val accuracyM: Float?)

/**
 * Where the phone last knew itself to be at [takenAt], and how far from the shutter that position
 * was taken, if a provider holds one from within [FIX_WINDOW_MS] of it that is close enough to mark
 * a spot by. A recording keeps listening through a pause, twice a minute ([routeFixIntervalMs]), and
 * the track screen every couple of seconds until the camera opens, so this is a position from the
 * last half-minute at most.
 */
@SuppressLint("MissingPermission") // guarded by hasApproximateLocation()
private fun phonePlace(context: Context, takenAt: Long): Pair<PhotoPlace, Long>? {
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null || !context.hasApproximateLocation()) return null
    return LAST_KNOWN_PROVIDERS.firstNotNullOfOrNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            ?.takeIf { placeStands(it.time - takenAt, if (it.hasAccuracy()) it.accuracy else null) }
            ?.let {
                PhotoPlace(
                    it.latitude,
                    it.longitude,
                    if (it.hasAltitude()) it.altitude else null,
                    if (it.hasAccuracy()) it.accuracy else null,
                ) to abs(it.time - takenAt)
            }
    }
}

/**
 * Whether the route's own fix, [fixOffsetMs] from the shutter, marks a photograph's spot rather than
 * the phone's position [phoneOffsetMs] from it: the route's fix only within [FIX_WINDOW_MS], and only
 * when it is at least as near the moment — during a pause the route's last fix may be far behind.
 */
internal fun fixWins(fixOffsetMs: Long?, phoneOffsetMs: Long?): Boolean = when {
    fixOffsetMs == null || fixOffsetMs > FIX_WINDOW_MS -> false
    phoneOffsetMs == null -> true
    else -> fixOffsetMs <= phoneOffsetMs
}

/**
 * Whether a position [offsetMs] from the shutter, [accuracyM] wide, may mark where a photograph was
 * taken: from the same minute, and no vaguer than a street.
 */
internal fun placeStands(offsetMs: Long, accuracyM: Float?): Boolean =
    abs(offsetMs) <= FIX_WINDOW_MS && accuracyM != null && accuracyM <= PLACE_ACCURACY_M

/** Whether a last known position [ageNanos] old may still stand for where the phone is now. */
internal fun lastKnownIsFresh(ageNanos: Long): Boolean = ageNanos in 0..LAST_KNOWN_MAX_AGE_NANOS

/**
 * The reading nearest [at], if one lies within [POSE_WINDOW_MS] of it; a tie goes to the earlier
 * one. Further off than that the phone may have been lowered or turned since, and no reading is
 * better than a wrong one.
 */
internal fun nearestPose(poses: List<CameraPose>, at: Long): CameraPose? =
    poses.minByOrNull { abs(it.at - at) }?.takeIf { abs(it.at - at) <= POSE_WINDOW_MS }

/**
 * One reading of which way the phone's back camera looked, at [at] on the wall clock. [bearingDeg]
 * is from magnetic north, since true north needs a position the reading does not have; it is null
 * from a sensor with no compass in it, and so is [bearingAccuracyDeg]. [rollDeg] is null with the
 * camera aimed nearly straight down or up, where how the phone was turned cannot be told.
 */
internal data class CameraPose(
    val at: Long,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val pitchDeg: Float,
    val rollDeg: Float?,
)

/**
 * Which way the back camera looked, from a rotation matrix as [SensorManager.getRotationMatrixFromVector]
 * fills it: row-major, turning a direction on the phone into one in the world — east, north, up.
 *
 * The camera looks out of the phone's back, along its −Z, so its direction in the world is the
 * matrix's third column negated: the bearing is that direction's heading, the pitch how far it rises
 * above the horizon. Pointed within [STEEP_DEGREES] of straight down or up, the camera's own heading
 * is the direction of a sliver and swings with every tremor, while the phone's long side lies nearly
 * level and holds still, so its heading stands in. Aimed at the ground, an upright phone's top tips
 * away from the person and points the way they face; aimed at the sky, the top tips back over them,
 * and it is the bottom that points ahead. That takes the phone upright, as it mostly is: held on its
 * side, such a shot's bearing is a quarter turn off. The roll is how far the phone has turned about
 * the camera's axis, read from how high its right edge and its top each stand — see [cameraRoll] —
 * and is left out altogether within [STEEP_DEGREES] of straight down or up, where both stand level
 * and the angle between them is the hand's tremor.
 *
 * [magnetic] false is a reading with no compass in it: its north is wherever the sensor started, so
 * the bearing is left out, while the tilt and the turn are as good as ever.
 */
internal fun cameraPose(
    at: Long,
    rotation: FloatArray,
    magnetic: Boolean = true,
    headingAccuracyDeg: Float? = null,
): CameraPose {
    val east = -rotation[2].toDouble()
    val north = -rotation[5].toDouble()
    val up = -rotation[8].toDouble()
    val steep = hypot(east, north) < STEEP_LEVEL
    val heading = when {
        !steep -> atan2(east, north)
        up > 0.0 -> atan2(-rotation[1].toDouble(), -rotation[4].toDouble())
        else -> atan2(rotation[1].toDouble(), rotation[4].toDouble())
    }
    val pitch = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
    return CameraPose(
        at = at,
        bearingDeg = if (magnetic) normalisedBearing(Math.toDegrees(heading)) else null,
        bearingAccuracyDeg = headingAccuracyDeg?.takeIf { magnetic },
        pitchDeg = pitch.toFloat(),
        rollDeg = if (steep) null else cameraRoll(rotation[6].toDouble(), rotation[7].toDouble(), pitch),
    )
}

/**
 * How far the phone was turned about the camera's axis, from how high its right edge, [right], and
 * its top, [top], stand: 0 upright, 90 with the top to the left, −90 to the right, ±180 upside down.
 *
 * Aimed more than [COARSE_HOLD_PITCH_DEG] up or down, both lie close to level, and the top tips past
 * level in an ordinary shot of one's own feet taken just past straight down, or of the sky just past
 * straight up — read as an angle, upside down, though nobody turned the phone over to take it. There
 * the larger of the two says only which way the phone was held: portrait, or on its side the way the
 * right edge stands.
 */
private fun cameraRoll(right: Double, top: Double, pitchDeg: Double): Float = when {
    abs(pitchDeg) <= COARSE_HOLD_PITCH_DEG -> Math.toDegrees(atan2(right, top)).toFloat()
    abs(top) >= abs(right) -> 0f
    right > 0.0 -> QUARTER_TURN
    else -> -QUARTER_TURN
}

/**
 * The rotation sensor's own estimate of how far off its heading may be, in degrees, or null where it
 * makes none: the reading's fifth value, in radians, which is −1 when unknown and absent from some
 * sensors' readings altogether.
 */
internal fun headingAccuracyDeg(values: FloatArray): Float? =
    values.getOrNull(HEADING_ACCURACY_INDEX)?.takeIf { it >= 0f }?.let { Math.toDegrees(it.toDouble()).toFloat() }

/** A magnetic bearing turned to true north by the local [declinationDeg]. */
internal fun trueBearing(magneticDeg: Float, declinationDeg: Float): Float =
    normalisedBearing(magneticDeg.toDouble() + declinationDeg)

/** Any angle as a bearing: degrees clockwise from north, from 0 up to but not including 360. */
internal fun normalisedBearing(degrees: Double): Float {
    val turned = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
    // A hair under a full turn rounds up to 360 in a Float, which is north written the wrong way.
    return turned.toFloat().takeIf { it < FULL_TURN } ?: 0f
}

/**
 * The rotation sensor, listened to while the camera app is in front, for how the phone was held
 * when the shutter fell.
 *
 * Process-wide rather than part of the screen: the screen is stopped while the camera app is in
 * front, and may be destroyed and built again, and the readings have to outlast it. The phone goes
 * on delivering them meanwhile because a recording's foreground service keeps the app among those
 * still allowed to hear motion sensors. Everything here runs on the main thread — the screen calls in
 * from there, and the sensor delivers there when it is given no handler — so nothing needs a lock.
 *
 * [start] and [stop] bracket one shot. [LISTEN_LIMIT_MS] after the last start the listening stops
 * on its own, so an answer that never comes back from the camera cannot leave the sensor running
 * for the rest of the walk; what it heard waits for [stop] all the same.
 */
internal object WalkCompass {
    private val poses = ArrayDeque<CameraPose>()
    private var sensors: SensorManager? = null
    private var listener: SensorEventListener? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val limit = Runnable { unregister() }

    /** Listens from now on, or goes on listening with the limit moved back if it already is. */
    fun start(context: Context) {
        main.removeCallbacks(limit)
        main.postDelayed(limit, LISTEN_LIMIT_MS)
        if (listener == null) listen(context.applicationContext.getSystemService(SensorManager::class.java))
    }

    /** Stops listening and hands over what was heard, oldest first. */
    fun stop(): List<CameraPose> {
        unregister()
        return poses.toList().also { poses.clear() }
    }

    /**
     * The rotation vector where the phone has one. Android builds it from the gyroscope, the
     * accelerometer and the compass together, so a phone without a gyroscope may have none; the
     * geomagnetic rotation vector, from the accelerometer and the compass alone, then stands in. It
     * is noisier, but it has a north. Last comes the game rotation vector, which leaves the compass
     * out: the tilt and the turn without the bearing.
     */
    private fun listen(manager: SensorManager) {
        poses.clear()
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: return
        val heard = PoseListener(magnetic = sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (manager.registerListener(heard, sensor, SAMPLE_PERIOD_US)) {
            sensors = manager
            listener = heard
        }
    }

    private fun unregister() {
        main.removeCallbacks(limit)
        listener?.let { sensors?.unregisterListener(it) }
        sensors = null
        listener = null
    }

    /** Keeps [pose] and lets go of whatever lies more than [KEEP_MS] behind it. */
    private fun add(pose: CameraPose) {
        poses.addLast(pose)
        while (pose.at - poses.first().at > KEEP_MS) poses.removeFirst()
    }

    /** Each reading as a [CameraPose], stamped with the wall clock. */
    private class PoseListener(private val magnetic: Boolean) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Copied out at once, since the framework hands the same event back with the next
            // reading — and only the rotation itself, not the accuracy that follows it.
            val vector = event.values.copyOf(minOf(event.values.size, QUATERNION_SIZE))
            val rotation = FloatArray(ROTATION_MATRIX_SIZE)
            SensorManager.getRotationMatrixFromVector(rotation, vector)
            val at = SensorEventClock.epochMillis(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
                event.timestamp,
            )
            add(cameraPose(at, rotation, magnetic, headingAccuracyDeg(event.values)))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}

/**
 * The file in [dir] — the cache's shared folder ([sharedCacheDir]) — the camera app writes the shot
 * opened at [openedAt] into. Each shot has its own: a keep still saving one shot — it can wait on the
 * database while the daily backup holds it — would otherwise read the next one's picture, or delete
 * it from under the camera.
 */
internal fun walkCaptureFile(dir: File, openedAt: Long): File = File(dir, "$WALK_CAPTURE_PREFIX$openedAt.jpg")

/** What every walk camera file in the cache is named from, for [sweepExportLeftovers] to find. */
internal const val WALK_CAPTURE_PREFIX = "walk-capture-"

private fun walkCaptureUri(context: Context, capture: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.photos", capture)

private fun walkPhotoName(takenAt: Long): String = "$takenAt.jpg"

/** A walk's photograph is a view rather than a silhouette, and is kept at a full-HD screen's longer edge. */
internal const val WALK_PHOTO_SIDE = 1_920

/** How near the shutter the reading of how the phone was held has to be. */
internal const val POSE_WINDOW_MS = 3_000L

/** How near the shutter the fix has to be to stand for where the photograph was taken. */
internal const val FIX_WINDOW_MS = 60_000L

/** How far before the camera was opened the shutter's own stamp is still believed. */
internal const val SHUTTER_SLACK_MS = 2_000L

private const val TAG = "WalkPhotos"

/** How wide a position may be and still mark where a photograph was taken, in metres. */
internal const val PLACE_ACCURACY_M = 50f

/** How old a last known position may be and still give the declination: an hour. */
internal const val LAST_KNOWN_MAX_AGE_NANOS = 3_600_000_000_000L

/** Where [lastKnownLocation] asks, best first. */
private val LAST_KNOWN_PROVIDERS = listOf(
    LocationManager.FUSED_PROVIDER,
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)

/** Twenty readings a second: a phone is held still for a shot, and a faster rate would only repeat itself. */
private const val SAMPLE_PERIOD_US = 50_000

/** Kept behind the newest reading: the shutter is seldom further back than this from the answer. */
private const val KEEP_MS = 120_000L

/** How long the sensor is heard after the last shot began, whatever becomes of the shot. */
private const val LISTEN_LIMIT_MS = 300_000L

private const val STEEP_DEGREES = 10.0
private val STEEP_LEVEL = sin(Math.toRadians(STEEP_DEGREES))

/** Aimed further than this above or below the horizon, the hold is told only as portrait or on its side. */
private const val COARSE_HOLD_PITCH_DEG = 60.0
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360.0
private const val MILLIS_DIGITS = 3
private const val QUATERNION_SIZE = 4
private const val ROTATION_MATRIX_SIZE = 9
private const val HEADING_ACCURACY_INDEX = 4
