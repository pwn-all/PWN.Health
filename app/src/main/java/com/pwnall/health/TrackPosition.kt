package com.pwnall.health

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Where the phone is, as the track screen's own listener last heard it: the dot on the map, the
 * view the map opens on before a walk, and the bars in the signal pill. Held in memory while the
 * screen is open and written nowhere — the route's own fixes are the service's to keep.
 * [elapsedNanos] is when the fix was taken, on the clock that counts from the phone's start and that
 * no change to the date or time moves.
 */
@Immutable
internal data class PhoneFix(val lat: Double, val lon: Double, val accuracyM: Float?, val elapsedNanos: Long)

/**
 * Listens for the phone's position while [listening] — the permission held and location on — and
 * only while the app is in front: the platform's fused provider where the phone has one, else the
 * satellites, every couple of seconds on the main thread. At high accuracy, because the bars are
 * read from the accuracy, and a fix the network guessed would only ever show one.
 *
 * Null until the first fix, and again once listening is no longer allowed, so a place from before
 * location went off is not drawn as where the phone is. The fix outlives the app going behind
 * another one: on the way back the dot stands where it was until the next fix moves it, and the
 * bars count its age meanwhile.
 */
@SuppressLint("MissingPermission") // asked for only while canRecordRoute() holds; see [listening]
@Composable
internal fun rememberPhoneFix(listening: Boolean): PhoneFix? {
    val context = LocalContext.current
    var fix by remember { mutableStateOf<PhoneFix?>(null) }
    LifecycleStartEffect(context, listening) {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!listening || manager == null) {
            fix = null
            return@LifecycleStartEffect onStopOrDispose { }
        }
        val listener = LocationListenerCompat { fix = it.toPhoneFix() }
        val provider = if (LocationManager.FUSED_PROVIDER in manager.allProviders) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        val request = LocationRequestCompat.Builder(POSITION_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        // A grant taken back between the screen's last look and this call throws; the screen then
        // shows no position until its next look turns the listening off.
        runCatching {
            LocationManagerCompat.requestLocationUpdates(
                manager,
                provider,
                request,
                ContextCompat.getMainExecutor(context),
                listener,
            )
        }
        onStopOrDispose { LocationManagerCompat.removeUpdates(manager, listener) }
    }
    return fix
}

private fun Location.toPhoneFix() =
    PhoneFix(latitude, longitude, accuracy.takeIf { hasAccuracy() }, elapsedRealtimeNanos)

/**
 * What the signal pill and the info page say about the position: its [level] in bars, the accuracy
 * of the fix it was read from, and whether there is anything to listen to at all.
 */
@Immutable
internal data class GpsSignal(
    val level: Int,
    val accuracyM: Float?,
    val locationOn: Boolean,
    val granted: Boolean,
) {
    /** Location switched off, or not granted: nothing is listened to, and the pill's mark says so. */
    val blocked: Boolean get() = !locationOn || !granted
}

/**
 * The bars a fix is worth: three within [GOOD_ACCURACY_M], two within [FAIR_ACCURACY_M], one for
 * anything coarser or a fix that gave no accuracy at all, and none for no fix in the last
 * [SIGNAL_STALE_MS] — [ageMillis] is null when there has been none. A fix younger than the clock it is
 * measured against, which is read every couple of seconds rather than continuously, is fresh.
 */
internal fun gpsSignalLevel(accuracyM: Float?, ageMillis: Long?): Int = when {
    ageMillis == null || ageMillis >= SIGNAL_STALE_MS -> 0
    accuracyM == null -> 1
    accuracyM <= GOOD_ACCURACY_M -> SIGNAL_BARS
    accuracyM <= FAIR_ACCURACY_M -> SIGNAL_BARS - 1
    else -> 1
}

/**
 * The square of ground [metres] across, centred on [lat] and [lon], in the map's Mercator units: what
 * the map frames of where the phone is. [origin] unwraps the longitude the way the route being drawn
 * unwraps its own — see [projectRoute] — so the square lands beside a line that crosses the
 * antimeridian rather than on the far side of the world.
 */
internal fun viewAround(lat: Double, lon: Double, origin: Double = lon, metres: Double = HERE_VIEW_METRES): Rect {
    val x = mercatorX(unwrapLongitude(lon, origin))
    val y = mercatorY(lat)
    val half = mapUnitsFor(metres / 2, y)
    return Rect((x - half).toFloat(), (y - half).toFloat(), (x + half).toFloat(), (y + half).toFloat())
}

/** The box that holds both, or whichever there is; null with neither. */
internal fun Rect?.spanning(other: Rect?): Rect? = when {
    this == null -> other
    other == null -> this
    else -> Rect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

/** The pill's bars, all three lit for a good fix. */
internal const val SIGNAL_BARS = 3

/** A fix good to this many metres lights every bar. */
internal const val GOOD_ACCURACY_M = 10f

/** A fix good to this many metres lights two. */
internal const val FAIR_ACCURACY_M = 25f

/** With no fix for this long the bars go out: the phone is searching. */
internal const val SIGNAL_STALE_MS = 10_000L

/** How often the screen asks for the position: the pace at which a walking dot looks alive. */
private const val POSITION_INTERVAL_MS = 2_000L

/**
 * The width of ground the map frames around the phone before a walk: a few streets either way. The
 * screen's camera zooms in no closer than [HERE_PIXELS_PER_UNIT], so only a screen wider than about
 * 1300 pixels near the equator shows more than this and its margin: under 600 m across a 1440-pixel
 * phone.
 */
internal const val HERE_VIEW_METRES = 500.0

/**
 * How close the track screen's camera may zoom, in pixels a map unit: about 0.4 m a pixel at the
 * equator, finer toward the poles. At the map's own limit, about a metre a pixel, a phone 1080
 * pixels wide would show some 1 km of ground at the equator rather than [HERE_VIEW_METRES].
 */
internal const val HERE_PIXELS_PER_UNIT = 1.0e8
