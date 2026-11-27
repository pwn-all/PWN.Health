package com.pwnall.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private data class CapturedRouteSample(
    val location: Location,
    /** The fix's time on the route's clock, which never runs back ([routeFixClock]); not the fix's own. */
    val timestamp: Long,
    val reading: MotionReading,
    val pressureHpa: Float?,
    /** Uncalibrated: the barometric offset is applied when the point is persisted, not here. */
    val barometricAltitudeM: Float?,
    val ambientTemperatureC: Float?,
    val gpsAltitudeM: Double?,
    /** See [TrackPointEntity.altitudeMsl]; decided per fix by [preferredAltitude]. */
    val altitudeMsl: Boolean,
) {
    /**
     * [token] is the sample's key in the service's held-sample map, and a counter rather than the
     * fix's time: a fix redelivered with the same elapsedRealtimeNanos would overwrite the one held
     * under that time, and when the filter resolved both, the second would find nothing left to persist.
     */
    fun toRouteFix(token: Long) = RouteFix(
        token = token,
        timestampNanos = location.elapsedRealtimeNanos,
        lat = location.latitude,
        lon = location.longitude,
        accuracyM = location.accuracy.toDouble(),
        reportedSpeedMps = location.speed.takeIf { location.hasSpeed() }?.toDouble(),
        speedAccuracyMps = location.speedAccuracyMetersPerSecond
            .takeIf { location.hasSpeedAccuracy() }
            ?.toDouble(),
        motion = reading.motion,
    )

    /** The accepted fix as [RouteTally] books it, where the filter put it and with the altitude as calibrated. */
    fun toTalliedFix(resolution: RouteFixResolution, calibratedAltitudeM: Float?) = TalliedFix(
        position = RoutePosition(
            lat = resolution.correctedLat ?: location.latitude,
            lon = resolution.correctedLon ?: location.longitude,
            timeMs = timestamp,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        ),
        accuracyM = location.accuracy.toDouble(),
        reportedSpeedMps = location.speed.takeIf { location.hasSpeed() }?.toDouble(),
        speedAccuracyMps = location.speedAccuracyMetersPerSecond
            .takeIf { location.hasSpeedAccuracy() }
            ?.toDouble(),
        reading = reading,
        stepsCounted = SensorFeed.stepsNear(location.elapsedRealtimeNanos),
        elevationM = calibratedAltitudeM?.toDouble() ?: gpsAltitudeM,
        barometric = calibratedAltitudeM != null,
        verticalAccuracyM = location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
    )

    /**
     * The row the fix is stored as, with the barometric altitude as calibrated when it was resolved and,
     * once accepted, how it was booked ([leg]). [segmentStart] marks the first fix of a new stretch of
     * line; see [TrackPointEntity.segmentStart].
     */
    fun toTrackPoint(
        track: Long,
        resolution: RouteFixResolution,
        leg: TalliedLeg?,
        calibratedAltitudeM: Float?,
        environmentSpike: Boolean,
        segmentStart: Boolean,
    ) = TrackPointEntity(
        trackId = track,
        timestamp = timestamp,
        elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        lat = location.latitude,
        lon = location.longitude,
        correctedLat = resolution.correctedLat,
        correctedLon = resolution.correctedLon,
        altitudeM = gpsAltitudeM,
        verticalAccuracyM = location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
        accuracyM = if (location.hasAccuracy()) location.accuracy else null,
        speedMps = leg?.speedMps?.toFloat(),
        speedAccuracyMps = location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
        pressureHpa = pressureHpa,
        barometricAltitudeM = calibratedAltitudeM,
        ambientTemperatureC = ambientTemperatureC,
        movementType = (leg?.movement ?: RouteMovement.UNKNOWN).name,
        cadenceHz = reading.cadenceHz,
        motionStd = reading.std,
        environmentSpike = environmentSpike,
        provider = location.provider ?: "?",
        accepted = resolution.accepted,
        filterReason = resolution.reason.name,
        altitudeMsl = altitudeMsl,
        segmentStart = segmentStart,
    )
}

/**
 * Owns the only step-sensor listener that credits steps to a day. The UI observes the database;
 * [SetTracker] also listens to the counter while a set runs, for the set's own count, and never
 * writes a day. Two listeners crediting TYPE_STEP_COUNTER is the classic double-count bug.
 *
 * Also runs the accelerometer that feeds [MotionGate], and — while a track is recording — the
 * location updates. `health` and `location` foreground service types are both exempt from the
 * Android 15+ 6 h dataSync timeout, so this can run for a whole hike.
 */
class StepService : Service(), SensorEventListener, LocationListenerCompat {

    // Single-threaded so batched sensor events are applied in arrival order. Out-of-order
    // application would look like a counter reset and silently drop steps.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writes = Dispatchers.IO.limitedParallelism(1)

    private val scope = CoroutineScope(SupervisorJob() + writes)

    /**
     * Route writes that have to outlive the service: onDestroy cancels [scope], and stopping a
     * track is the only write in the app that takes a route out of the recording state — one
     * cancelled there leaves a recording nothing can ever end. Shares [writes] with [scope] so the
     * two stay in arrival order.
     */
    private val durable = CoroutineScope(SupervisorJob() + writes)

    private val db by lazy { StepsDb.get(this) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    // maybeNotify runs on both the service scope and the main thread, and notification() reads
    // dayTotal, trackId and the route's distance from whichever one got there — so those, and
    // [RouteTally.totals], are volatile.
    @Volatile
    private var lastNotifiedAt = 0L

    @Volatile
    private var dayTotal = 0L

    // Only ever touched on [writes]: the step path on [scope] and the fix path on [durable] share
    // that one thread, so the throttle needs no lock. A time for each of the two, since each redraws
    // widgets of its own: under one, a route's fixes would take every turn and the step widgets none.
    private val widgetsRedrawnAt = HashMap<String, Long>()

    // On [writes] too: the loop that writes the steps decided with the screen off; see [watchSteps].
    private var stepWatch: Job? = null

    // True while the service scope is reading the interrupted track back. A stop or a start clears
    // it, which is how the restore learns it was superseded before it installs anything.
    @Volatile
    private var restoring = false

    // The recording state below belongs to the main thread: restoreTrack reads the database on the
    // service scope but installs every field through the main executor.
    @Volatile
    private var trackId: Long? = null

    // The row behind [trackId], completed on the service scope. Until then [trackId] is
    // PENDING_TRACK_ID — so everything that asks "is a route recording" still says yes — and a point
    // written meanwhile waits here for its id instead of being dropped.
    private var trackRow = CompletableDeferred<Long>()

    // Read once per track, with the row: the notification prints the distance in the profile's units.
    @Volatile
    private var trackImperial = false

    // The route in hand stands paused: its fixes still arrive, and none of them is stored. Volatile
    // because the notification, which says so, is built on whichever thread posts it.
    @Volatile
    private var trackPaused = false

    // A pause or a resume that came while an interrupted route was still being read back. The read
    // may have finished before it was written, so the install takes this over the row it got.
    private var pauseRequested: Boolean? = null

    // The write that closes the route a stop let go of, for a restore to wait for; see [restoreTrack].
    // The main thread's, like the rest of the recording state.
    private var routeWrite: Job? = null

    // Next key for [capturedRouteSamples]; see [CapturedRouteSample.toRouteFix].
    private var routeFixCount = 0L

    // The time the last fix of the route in hand was stored under; see [routeFixClock].
    private var routeClock: RouteClock? = null
    private val routeTally = RouteTally()
    private val routeCorrelation = RouteCorrelationFilter()
    private val capturedRouteSamples = mutableMapOf<Long, CapturedRouteSample>()

    private var latestPressureHpa: Float? = null
    private var rawBarometricAltitudeM: Float? = null
    private var barometricOffsetM: Float? = null
    private var latestTemperatureC: Float? = null
    private var previousPointPressureHpa: Float? = null
    private var previousPointTemperatureC: Float? = null

    // In memory only. A process death loses the climb in progress — at most one storey —
    // and every storey already credited is in the database. Persisting the anchor would be a row
    // written five times a second to protect three metres.
    private val floors = FloorTracker()
    private var lastPressureMeanAt = 0L

    override fun onCreate() {
        super.onCreate()
        scope.holdAwakeWhileTracing(power)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START_TRACK -> startTrack()

            ACTION_STOP_TRACK -> stopTrack()

            ACTION_PAUSE_TRACK, ACTION_RESUME_TRACK -> pauseOrResume(action == ACTION_PAUSE_TRACK)

            // A reboot is not a reason to pick a route back up: the phone was off, the gap in it is
            // unbridgeable, and a service started from BOOT_COMPLETED is denied while-in-use
            // location anyway. So is one started after an update of the app, which comes here too.
            // The pedometer below is the whole point of starting here.
            ACTION_BOOT -> Unit

            // No action: plain start, or a START_STICKY restart. Pick up an interrupted track.
            //
            // Resolving the id is a database read and onStartCommand runs on the main thread, so it
            // happens on the service scope. The "nothing to do" stopSelf, which needs the id, lives at
            // the end of the restore: taken here, synchronously, it would tear the resume down at its
            // first suspension point on a pedometer-less phone.
            else -> if (trackId == null && !restoring) {
                // A pause or a resume asked of an earlier restore, one superseded or with nothing to
                // install, is not this one's to apply.
                pauseRequested = null
                restoring = true
                restoreTrack()
            }
        }

        dropRouteClosedElsewhere()
        pushForeground()

        val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // No route in hand and no steps this phone can count: nothing to keep a foreground service
        // alive for ([serviceHasWork]). Asked of what the service can do, not of the type it could
        // declare: Android 13 lets a pedometer declare none, so a route stopped there without the
        // physical activity permission would leave the notification standing over a count of nought.
        // Only here, after pushForeground: a command can come through startForegroundService, and a
        // service brought up that way and stopped before it has called startForeground is an
        // exception the platform throws in the app. stopTrack does not stop it.
        if (!serviceHasWork(trackId != null || restoring, counter != null, hasStepPermission())) {
            stopSelf()
            return START_NOT_STICKY
        }
        readDayTotal()
        // Re-registering an already-registered listener is harmless: the accumulator turns a
        // repeated counter value into a delta of 0.
        if (counter != null) sensors.registerListener(this, counter, SENSOR_RATE_US, BATCH_LATENCY_US)
        // Route classification also needs gait evidence on phones without a hardware pedometer.
        if (counter != null || trackId != null) registerAccelerometer()
        registerPressure()
        if (trackId != null) registerTemperature()
        return START_STICKY
    }

    // --- sensors -------------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                SensorFeed.add(
                    Accel(event.timestamp, event.values[0], event.values[1], event.values[2]),
                    power.isInteractive,
                    SystemClock.elapsedRealtimeNanos(),
                )

            Sensor.TYPE_PRESSURE -> {
                latestPressureHpa = event.values.firstOrNull()
                rawBarometricAltitudeM = latestPressureHpa?.let {
                    SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it)
                }
                rawBarometricAltitudeM?.let { recordBarometer(event.timestamp, it.toDouble()) }
            }

            Sensor.TYPE_AMBIENT_TEMPERATURE ->
                latestTemperatureC = event.values.firstOrNull()

            Sensor.TYPE_STEP_COUNTER -> {
                val rawCounter = event.values[0].toLong()
                // Read now, not in the launch below: the framework reuses one SensorEvent per
                // sensor, so by the time the write runs it holds a later event's timestamp. Read
                // late, a flushed batch would reach the accumulator as twenty steps in no time at
                // all, and the rate limit would throw away 71 of a recorded 571-step walk.
                val eventTimestampNanos = event.timestamp
                val deliveryElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                val deliveryEpochMillis = System.currentTimeMillis()
                // Wall-clock time of boot: stable within a boot session, jumps on reboot.
                val bootId = deliveryEpochMillis - deliveryElapsedRealtimeNanos / 1_000_000L
                val decision = SensorFeed.decisionAt(eventTimestampNanos)
                val reading = StepReading(
                    rawCounter = rawCounter,
                    bootId = bootId,
                    day = SensorEventClock.day(deliveryEpochMillis, deliveryElapsedRealtimeNanos, eventTimestampNanos),
                    eventTimestampNanos = eventTimestampNanos,
                    gaitEvidence = decision.reading.stepsAllowed,
                    doubtful = decision.reading.stepsDoubtful,
                    deliveredAtMs = deliveryEpochMillis,
                )
                val screenOn = power.isInteractive
                scope.launch {
                    val update = Pedometer.decide(db, reading, deliveryElapsedRealtimeNanos / NANOS_PER_MILLI)
                    SensorFeed.onSteps(
                        rawCounter = rawCounter,
                        rawDelta = update.rawDelta,
                        creditedDelta = update.creditedDelta,
                        verdict = update.verdict,
                        timestampNanos = eventTimestampNanos,
                        decision = decision,
                        deliveryElapsedRealtimeNanos = deliveryElapsedRealtimeNanos,
                    )
                    // Written at once while a count can be seen, and a minute at a time while the
                    // screen is off: a transaction for every event would be some 1 800 an hour of walking.
                    if (screenOn || Pedometer.due(SystemClock.elapsedRealtime())) writeSteps() else watchSteps()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * The day's climb and the day's weather, from the same reading.
     *
     * Only a completed storey is written as it happens. The pressure mean is a weather figure and
     * sampling it every [PRESSURE_MEAN_INTERVAL_MS] is a couple of hundred rows a day instead of a
     * couple of hundred thousand — the difference between the two means is far smaller than the
     * difference between a phone in a pocket and a phone on a table.
     */
    private fun recordBarometer(timestampNanos: Long, altitudeM: Double) {
        // The gate's verdict for the instant the pressure was measured, not for now: the barometer
        // is batched and its samples can be two seconds old, which is a fifth of a flight of stairs.
        val motion = SensorFeed.readingAt(timestampNanos).motion
        val walking = motion == Motion.WALKING || motion == Motion.RUNNING
        val climbM = floors.add(timestampNanos, altitudeM, walking)
        val now = SystemClock.elapsedRealtime()
        val dueForMean = now - lastPressureMeanAt >= PRESSURE_MEAN_INTERVAL_MS
        val meanSample = latestPressureHpa?.takeIf { dueForMean }?.toDouble()
        if (climbM <= 0.0 && meanSample == null) return
        if (meanSample != null) lastPressureMeanAt = now
        val day = SensorEventClock.day(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtimeNanos(),
            timestampNanos,
        )
        scope.launch { db.addBarometerSample(day, climbM, meanSample) }
    }

    // --- track recording -----------------------------------------------------------------------

    private fun startTrack() {
        if (trackId != null) return
        restoring = false
        // The row is inserted on the service scope, not here. onStartCommand runs on the main thread,
        // and a one-row insert there waits behind whatever holds Room's write lock — a backup export,
        // for one — which is an ANR, and past startForeground's window when the start came through
        // TrackStartActivity. The type is declared first, the way a restore declares it before its id
        // exists, and the id catches up with the first fix.
        val row = CompletableDeferred<Long>()
        trackRow = row
        trackId = PENDING_TRACK_ID
        trackPaused = false
        routeTally.totals = RouteTotals()
        routeClock = null
        startSegment()
        resetEnvironment()
        registerAccelerometer()
        registerTemperature()
        // Claim the location foreground-service type *before* asking for fixes.
        pushForeground()
        requestLocation()
        // Durable, like every other route write: a row that never appears leaves the fixes waiting
        // on it with nowhere to go.
        durable.launch {
            val started = System.currentTimeMillis()
            db.dao().activeTrackNow()?.let { db.dao().endTrack(it.id, started) }
            val id = db.dao().insertTrack(
                TrackEntity(
                    startedAt = started,
                    name = randomWalkName(resources),
                    zoneOffsetSeconds = ZoneId.systemDefault().rules.getOffset(
                        Instant.ofEpochMilli(started),
                    ).totalSeconds,
                ),
            )
            row.complete(id)
            val imperial = db.dao().profileNow()?.imperial ?: false
            ContextCompat.getMainExecutor(this@StepService).execute {
                // A fresh start may have got here first, and the row is then its own; so may a stop,
                // which lets go of the row it was handed ([stopTrack]). Either way the id is no
                // longer the recording's.
                if (trackRow === row) {
                    trackId = id
                    trackImperial = imperial
                }
            }
            // The widget's recording face is the open row, and nothing else redraws it before the
            // first fix is accepted — which indoors can be never.
            refreshWidgets(applicationContext, WIDGETS_OF_ROUTE)
        }
    }

    // Reads an interrupted recording back on the service scope and hands it to the thread that owns
    // it. Nothing is acted on before the hand-off: a start or a stop delivered while the reads were
    // in flight clears `restoring`, and from that moment it owns the recording state, not this
    // answer. That covers the "nothing to resume" stopSelf too — decided on the scope it would kill
    // a track that ACTION_START_TRACK inserted while the query was still running.
    //
    // A stop's own write is waited for first. A read does not wait for a writer: begun while that
    // endTrack still stood behind Room's write lock — a backup being exported — it would find the
    // route open, and the service would take up a recording then closed under it: fixes written into a
    // finished route, GPS on, and a Start that did nothing because a route was "in hand". [stop] is
    // that write, taken where this is called — on the main thread, which owns it — and not inside.
    private fun restoreTrack(stop: Job? = routeWrite) = scope.launch {
        stop?.join()
        val active = db.dao().activeTrackNow()
        val recentPoints = active?.let { db.dao().recentAcceptedPoints(it.id, 2).reversed() }.orEmpty()
        val imperial = db.dao().profileNow()?.imperial ?: false
        ContextCompat.getMainExecutor(this@StepService).execute {
            if (!restoring) return@execute
            restoring = false
            if (active != null) {
                installTrack(active, recentPoints)
                trackImperial = imperial
                registerAccelerometer()
                registerTemperature()
                // Paused or not: a paused route keeps its fixes coming, at a pause's own pace
                // ([routeFixIntervalMs]), so a photograph taken in the pause can be placed, and
                // stores none of them.
                requestLocation()
                pushForeground()
                return@execute
            }
            // Nothing to resume and no steps to count: nothing left to keep a foreground service
            // alive, as onStartCommand decides it for a command.
            val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (!serviceHasWork(route = false, counter != null, hasStepPermission())) stopSelf()
        }
    }

    // Restores every field of a recording interrupted by a process death.
    private fun installTrack(active: TrackEntity, recentPoints: List<TrackPointEntity>) {
        trackId = active.id
        trackRow = CompletableDeferred(active.id)
        // A resume that came while the route was read back has a pause behind it, whether the read
        // found the row still paused or already going again.
        val resumedMeanwhile = pauseRequested == false
        trackPaused = pauseRequested ?: (active.pausedAt != null)
        pauseRequested = null
        routeTally.totals = active.routeTotals()
        startSegment()
        // The route's clock runs on from its last stored fix: on this boot's clock, or, across a
        // reboot, at least never back past it.
        routeClock = recentPoints.lastOrNull()?.let {
            RouteClock(it.timestamp, if (it.isFromCurrentBoot()) it.elapsedRealtimeNanos else 0L)
        }
        val last = recentPoints.lastOrNull()?.takeIf(TrackPointEntity::isFromCurrentBoot)
        // The barometer keeps the datum it was pinned to for the whole route, whichever stretch.
        if (last == null) resetEnvironment() else last.barometricOffsetM()?.let { barometricOffsetM = it }
        // The line runs on from the last fix only when this boot took it and no pause lies, or may lie,
        // between it and now; otherwise the next fix starts a stretch of its own, as after a pause.
        val point = last?.takeUnless { restartBreaksLine(active, it.ageMillis(), resumedMeanwhile) } ?: return
        routeTally.runOnFrom(
            point.toRoutePosition(),
            point.barometricAltitudeM?.toDouble() ?: point.altitudeM,
            point.movement(),
        )
        previousPointPressureHpa = point.pressureHpa
        previousPointTemperatureC = point.ambientTemperatureC
        if (recentPoints.all(TrackPointEntity::isFromCurrentBoot)) {
            routeCorrelation.restore(recentPoints.map(TrackPointEntity::toRouteFix))
        }
    }

    // A route closed past the service is let go of at the next command, whatever the command. The
    // widget's Stop closes the row itself where no service stands in the foreground; tapped while a
    // service just started was reading that row back, it would leave the service recording into a finished
    // route — GPS on, and a Start that did nothing because a route was "in hand". The row is the
    // truth: no longer open, the recording is over. It ends as a Stop ends it — the fixes let go, the
    // notification redrawn, a service with nothing left to do stopped — and the stop's own write
    // finds the row already ended and leaves its moment alone ([StepsDao.endTrack]).
    private fun dropRouteClosedElsewhere(id: Long? = trackId) = scope.launch {
        if (!routeLetGo(id, db.dao().activeTrackNow()?.id)) return@launch
        // Dropped by a service destroyed since the read, which onDestroy's cancelling of [scope] marks:
        // run there, the command would register the sensors again on an instance that nothing goes
        // on to unregister them from.
        mainExecutor.execute {
            if (scope.coroutineContext.job.isActive && trackId == id) onStartCommand(Intent(ACTION_STOP_TRACK), 0, 0)
        }
    }

    private fun stopTrack() {
        val id = trackId
        val row = trackRow
        restoring = false
        removeLocationUpdates()
        if (id != null) routeCorrelation.flush().forEach { resolveRouteFix(id, it) }
        trackId = null
        // Let go of once the fix the filter held has been sent to it. Kept, a row still being
        // inserted would have its id installed when it arrived ([startTrack]): the stopped route would
        // be in hand again, its distance in the notification and the next Start ignored, until a
        // command found the row closed.
        trackRow = CompletableDeferred()
        startSegment()
        unregisterTemperature()
        // A stop can also reach a service created after the recording was interrupted, whose sole
        // record of it is the row — or one whose row is still being inserted, see [startTrack].
        routeWrite = durable.launch {
            db.dao().routeRow(id, row)?.let { db.dao().endTrack(it, System.currentTimeMillis()) }
            // The widget's recording face is the open row, so it comes down only once the row is
            // closed — and nothing else redraws it after a route that credited no steps.
            refreshWidgets(applicationContext, WIDGETS_OF_ROUTE)
        }
    }

    /**
     * Pauses the recording or takes it up again. Paused, the route's sensors go on and so do the
     * location updates, at a pause's slower pace ([routeFixIntervalMs]): the position Android holds
     * stays current for a photograph taken in the pause, which takes the phone's own, and nothing is
     * stored, counted or drawn. Taken up again, the fixes come as often as before and the line starts
     * a stretch of its own with the route's totals kept. The row keeps the pause, so a restart reads
     * it back.
     */
    private fun pauseOrResume(pause: Boolean) {
        val id = trackId
        val row = trackRow
        val at = System.currentTimeMillis()
        when {
            // A route still being read back after a restart is installed the way this asks.
            id == null -> {
                pauseRequested = pause
                if (!restoring) {
                    restoring = true
                    restoreTrack()
                }
            }

            trackPaused == pause -> Unit

            pause -> {
                trackPaused = true
                // The fix the filter is holding was taken before the pause, and is settled as a stop
                // settles it rather than judged against the first fix after.
                routeCorrelation.flush().forEach { resolveRouteFix(id, it) }
            }

            else -> {
                trackPaused = false
                startSegment()
            }
        }
        // Asked again at the pace the route now keeps: under the same listener the request replaces
        // the one made before. A route still being read back asks when it is installed.
        if (id != null) requestLocation()
        // Durable for the reason a stop is: the row is all a restart reads the pause back from. The
        // track widget's face is drawn from that row, so it is redrawn once the row is written: a
        // paused route stores no fix, and the fix path's redraw would not come.
        durable.launch {
            val track = db.dao().routeRow(id, row) ?: return@launch
            if (pause) db.dao().pauseTrack(track, at) else db.dao().resumeTrack(track, at)
            refreshWidgets(applicationContext, WIDGETS_OF_ROUTE)
        }
    }

    /** Forgets where the line was: the next fix is measured from nothing, as a route's first is. */
    private fun startSegment() {
        breakLine()
        routeCorrelation.reset()
        capturedRouteSamples.clear()
    }

    /**
     * [startSegment] for a break the correlation filter made itself, at a fix it re-anchored on: the
     * filter has already started again from that fix, and the samples it has yet to resolve stay.
     */
    private fun breakLine() {
        routeTally.breakLine()
        previousPointPressureHpa = null
        previousPointTemperatureC = null
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    private fun requestLocation() {
        if (!hasLocationPermission()) return
        LocationManagerCompat.requestLocationUpdates(
            locations,
            locations.routeProvider(),
            routeLocationRequest(trackPaused),
            ContextCompat.getMainExecutor(this),
            this,
        )
    }

    override fun onLocationChanged(location: Location) {
        // Paused, the fixes keep coming and none is stored: the walker may go anywhere meanwhile,
        // and the line starts again at the first fix after the resume.
        val id = trackId?.takeUnless { trackPaused } ?: return
        val raw = Location(location)
        val reading = SensorFeed.readingAt(raw.elapsedRealtimeNanos)
        val (gpsAltitude, altitudeMsl) = preferredAltitude(raw)
        val qualityRejection = qualityRejection(raw)
        if (qualityRejection == null) calibrateBarometer(gpsAltitude)
        // Dated in the order the fixes arrive, not the one the filter resolves them in: a rejected fix
        // is stored at once, while the one before it may still be held.
        val clock = routeFixClock(routeClock, raw.time, raw.elapsedRealtimeNanos).also { routeClock = it }

        val sample = CapturedRouteSample(
            location = raw,
            timestamp = clock.timeMs,
            reading = reading,
            pressureHpa = latestPressureHpa,
            barometricAltitudeM = rawBarometricAltitudeM,
            ambientTemperatureC = latestTemperatureC,
            gpsAltitudeM = gpsAltitude,
            altitudeMsl = altitudeMsl,
        )

        val fix = sample.toRouteFix(++routeFixCount)
        if (qualityRejection != null) {
            persistResolvedPoint(
                id,
                sample,
                RouteFixResolution(fix, accepted = false, reason = qualityRejection),
            )
            return
        }

        capturedRouteSamples[fix.token] = sample
        routeCorrelation.offer(fix).forEach { resolveRouteFix(id, it) }
    }

    /**
     * Pins the standard-atmosphere barometric altitude to a GPS altitude once per track. Only a fix
     * that passed the quality gate may do it: the provider's first callback is its cached last known
     * position, which can be hours old and kilometres away, and the offset is never recomputed.
     */
    private fun calibrateBarometer(gpsAltitudeM: Double?) {
        if (barometricOffsetM != null) return
        val rawAltitude = rawBarometricAltitudeM ?: return
        val gpsAltitude = gpsAltitudeM ?: return
        barometricOffsetM = gpsAltitude.toFloat() - rawAltitude
        routeTally.dropElevation()
    }

    private fun resolveRouteFix(track: Long, resolution: RouteFixResolution) {
        val sample = capturedRouteSamples.remove(resolution.fix.token) ?: return
        persistResolvedPoint(track, sample, resolution)
    }

    // GPS filtering, telemetry and persistence belong to one database transaction. The accounting of
    // an accepted fix is [RouteTally]'s, over plain values a test can reach.
    private fun persistResolvedPoint(
        track: Long,
        sample: CapturedRouteSample,
        resolution: RouteFixResolution,
    ) {
        // Calibrated here rather than at capture: the correlation filter can hold a fix back, and a
        // calibration that lands in between must reach the held one too or the chain steps by it.
        val barometricAltitudeM = sample.barometricAltitudeM?.let { it + (barometricOffsetM ?: 0f) }
        val accepted = resolution.accepted
        // The filter gave up an anchor the walker had long left — a metro ride it never saw — and
        // started again at this fix. So does the line, as after a pause: nothing is drawn, counted
        // or climbed across the stretch in between.
        if (resolution.reason == RouteFixReason.REANCHORED) breakLine()
        val segmentStart = startsStretch(
            accepted,
            lineRunsOn = routeTally.lineRunsOn,
            routeFixes = routeTally.totals.points,
        )
        val leg = if (accepted) routeTally.add(sample.toTalliedFix(resolution, barometricAltitudeM)) else null
        val spike = pressureJumped(sample.pressureHpa, previousPointPressureHpa) ||
            temperatureJumped(sample.ambientTemperatureC, previousPointTemperatureC)
        val point = sample.toTrackPoint(track, resolution, leg, barometricAltitudeM, spike, segmentStart)
        if (accepted) {
            previousPointPressureHpa = sample.pressureHpa
            previousPointTemperatureC = sample.ambientTemperatureC
        }
        val totals = routeTally.totals
        val row = trackRow
        // Durable for the same reason as endTrack: stopTrack flushes the held fix and then may stop
        // the service, and a route truncated by its own teardown is not recoverable.
        durable.launch {
            // The row of a track that has just started may still be on its way; see [startTrack].
            val id = if (track == PENDING_TRACK_ID) row.await() else track
            db.withTransaction {
                db.dao().insertPoint(point.copy(trackId = id))
                if (accepted) {
                    db.dao().updateTrack(
                        trackId = id,
                        meters = totals.meters,
                        points = totals.points,
                        walkingMeters = totals.walkingMeters,
                        runningMeters = totals.runningMeters,
                        vehicleMeters = totals.vehicleMeters,
                        stoppedMillis = totals.stoppedMillis,
                        elevationGainM = totals.elevationGainM,
                        elevationLossM = totals.elevationLossM,
                        maxSpeedMps = totals.maxSpeedMps,
                    )
                }
            }
            // The widget reads the row this transaction just wrote. Redrawn only from the step path,
            // it would freeze at 0.0 km for a ride or a drive and never move off the recording face.
            if (accepted) maybeRefreshWidgets(WIDGETS_OF_ROUTE)
        }
        if (accepted) maybeNotify()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Unbatched on purpose. The gate classifies a step against the accelerometer window that covers
     * it, so buffering the accelerometer while the pedometer flushes separately leaves the gate with
     * no window at the moment it is asked — which would read as "no gait" and discard the steps.
     */
    private fun registerAccelerometer() {
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, ACCEL_RATE_US)
        }
    }

    /**
     * On for as long as the service is, not just while a track records: floors are climbed on the
     * way to the kitchen too. Batched and at the slow rate, it costs a fraction of the 25 Hz
     * accelerometer that is already running beside it.
     */
    private fun registerPressure() {
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensors.registerListener(this, it, SENSOR_RATE_US, BATCH_LATENCY_US)
        }
    }

    /** Track-only: nothing outside a route reads the ambient temperature. */
    private fun registerTemperature() {
        sensors.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sensors.registerListener(this, it, SENSOR_RATE_US, BATCH_LATENCY_US)
        }
    }

    private fun unregisterTemperature() {
        sensors.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sensors.unregisterListener(this, it)
        }
        if (sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.unregisterListener(this, it)
            }
        }
        resetEnvironment()
    }

    private fun resetEnvironment() {
        latestPressureHpa = null
        rawBarometricAltitudeM = null
        barometricOffsetM = null
        latestTemperatureC = null
        previousPointPressureHpa = null
        previousPointTemperatureC = null
    }

    // --- lifecycle & notification --------------------------------------------------------------

    /**
     * Android 14 made the `health` foreground-service type conditional on actually holding one of
     * the body-sensing permissions at runtime — for this app, ACTIVITY_RECOGNITION — and throws
     * SecurityException otherwise. Claiming it unconditionally would crash the process for anyone who
     * dismissed that prompt and then started a track, and again on every START_STICKY restart.
     */
    private fun pushForeground() {
        val android14 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        // [restoring] counts as a route: the id is read from the database on the service scope, so
        // a START_STICKY restart reaches here before it exists. Declaring the type only for the id
        // we already have would leave a pedometer-less phone with no type at all, and the stopSelf
        // below would kill the service the restart existed to let finish the route.
        val types = foregroundTypes(
            android14 = android14,
            stepPermission = hasStepPermission(),
            route = trackId != null || restoring,
            locationPermission = hasLocationPermission(),
        )
        // Nothing left to declare, or `location` refused: see [startForegroundDegrading].
        inForeground = startForegroundDegrading(NotificationIds.STEPS, notification(), types, android14)
        if (!inForeground) stopSelf()
    }

    private fun removeLocationUpdates() {
        try {
            LocationManagerCompat.removeUpdates(locations, this)
        } catch (_: SecurityException) {
            // The user can revoke location while recording. There is then nothing left to remove.
        }
    }

    private suspend fun writeSteps() {
        val written = Pedometer.write(db) ?: return
        dayTotal = written.dayTotal
        maybeNotify()
        // A widget showing a half-hour-old number reads as broken, but a refresh asks each widget
        // class that shows steps for an update — four of them, see widgetsShowing — each reading
        // the database for itself, and the asking is done on this one thread: unthrottled it queues
        // ahead of the next step's transaction.
        if (written.credited) maybeRefreshWidgets(WIDGETS_OF_STEPS)
    }

    // The total is moved by a written step and by nothing else, so a start that did not read it
    // would put the last one back under "steps today": yesterday's, when the app was opened after
    // midnight before the day's first step, and nought in a service the system had made anew. Read
    // again at every start, it is the figure the home screen and the widget show.
    private fun readDayTotal() = scope.launch {
        dayTotal = Pedometer.dayTotal(db, LocalDate.now().toString())
        // Not from a service on its way out: the notification would outlive it.
        if (inForeground) maybeNotify()
    }

    // Steps decided with the screen off wait in the ledger until they are a minute old, or until
    // moments after the screen comes on: a phone taken out of a pocket shows the walk that was in it.
    private fun watchSteps() {
        if (stepWatch?.isActive == true) return
        stepWatch = scope.launch {
            while (Pedometer.unwritten()) {
                delay(STEP_WATCH_MS)
                if (power.isInteractive || Pedometer.due(SystemClock.elapsedRealtime())) writeSteps()
            }
        }
    }

    private fun maybeNotify() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < NOTIFY_INTERVAL_MS) return
        lastNotifiedAt = now
        notifications.notify(NotificationIds.STEPS, notification())
    }

    // Only the widgets [kind] can have changed: asking every class on every round would ask most
    // of the fourteen for nothing, at each fix of a route however long it ran.
    private suspend fun maybeRefreshWidgets(kind: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - (widgetsRedrawnAt[kind] ?: 0L) < WIDGET_REFRESH_INTERVAL_MS) return
        widgetsRedrawnAt[kind] = now
        refreshWidgets(this, kind)
    }

    override fun onDestroy() {
        inForeground = false
        // A restore whose reads finished just before the scope below is cancelled has already posted
        // its install to the main thread, behind this; cleared, it installs nothing, rather than
        // registering listeners on a service that is gone and that no onDestroy will unregister.
        restoring = false
        sensors.unregisterListener(this)
        removeLocationUpdates()
        // What the ledger still holds is written on [durable], which nothing cancels. The database
        // alone: a notification posted after this would outlive the service it belongs to.
        durable.launch { Pedometer.write(db) }
        scope.cancel()
        super.onDestroy()
    }

    // Not bound: the UI reads the database, not the service.
    override fun onBind(intent: Intent?): IBinder? = null

    // The distance in the profile's units, like the screen: a notification saying km over a card
    // saying mi would be the same number in two languages.
    private fun notification(): Notification {
        val distance = if (trackId != null) formatDistance(resources, routeTally.totals.meters, trackImperial) else null
        return stepsNotification(dayTotal, distance, trackPaused)
    }

    companion object {
        const val ACTION_START_TRACK = "com.pwnall.health.START_TRACK"
        const val ACTION_STOP_TRACK = "com.pwnall.health.STOP_TRACK"
        const val ACTION_PAUSE_TRACK = "com.pwnall.health.PAUSE_TRACK"
        const val ACTION_RESUME_TRACK = "com.pwnall.health.RESUME_TRACK"
        const val ACTION_BOOT = "com.pwnall.health.BOOT"

        /**
         * Whether the service of this process stands declared as a foreground one. A command sent
         * through startForegroundService is owed nothing more by a service already in the
         * foreground, and a startForeground by a new one; see [stopGoesToService].
         */
        @Volatile
        internal var inForeground = false
            private set

        /** 25 Hz: enough to resolve a 3 Hz running cadence with margin, cheap enough to leave on. */
        private const val ACCEL_RATE_US = 40_000
        private const val SENSOR_RATE_US = SensorManager.SENSOR_DELAY_NORMAL

        /**
         * Hardware batching saves wakeups. Kept short because the motion gate classifies steps
         * against the accelerometer window they arrived with.
         */
        private const val BATCH_LATENCY_US = 2_000_000

        private const val NOTIFY_INTERVAL_MS = 5_000L

        /** Same budget as the notification: a walk credits steps faster than a widget can redraw. */
        private const val WIDGET_REFRESH_INTERVAL_MS = 5_000L

        /** How often one pressure reading is kept for the day's mean. Weather does not move faster. */
        private const val PRESSURE_MEAN_INTERVAL_MS = 10 * 60 * 1_000L
    }
}

private const val CHANNEL = "steps"
private const val MAX_ACCURACY_M = 25f

/** The fastest a walk and a run go, as the route classifier draws the two lines; see [legMovement]. */
private const val WALK_LEG_LIMIT_MPS = 7.0 / 3.6
private const val RUN_LEG_LIMIT_MPS = 25.0 / 3.6

private const val PRESSURE_SPIKE_HPA = 1.5f
private const val TEMPERATURE_SPIKE_C = 2.0f

/** A recording's id while its row is still being inserted; autoincrement never hands out 0. */
private const val PENDING_TRACK_ID = 0L

/**
 * Whether the route the service holds as [held] is one something else has closed: it has a row of
 * its own by now, and [open], the route the database holds open, is not it.
 */
internal fun routeLetGo(held: Long?, open: Long?): Boolean = held != null && held != PENDING_TRACK_ID && held != open

/**
 * The request code of the notification's tap while a route is in hand, apart from the plain tap's 0:
 * a PendingIntent is found again by its intent without the extras, so under one code the track
 * screen's tap and the plain one would hand back whichever was made first.
 */
private const val ROUTE_NOTIFICATION_REQUEST = 1

/**
 * The longest gap the correlation filter still predicts a fix across — its own limit — and so the
 * longest a restarted recording that has paused before runs its line on across.
 */
private const val RESTART_GAP_LIMIT_MS = 15_000L

/** Older than this and the fix is a cached last-known position, not where the user is. */
private const val MAX_FIX_AGE_MS = 30_000L

/** As long as a recording may run ([SensorTrace.MAX_RECORDING_MS]), in case no sample comes to end it. */
private const val TRACE_WAKE_LOCK_MS = SensorTrace.MAX_RECORDING_MS

/**
 * Keeps the CPU awake while a sensor trace records, and only then.
 *
 * The accelerometer is a non-wake-up sensor: once the screen goes off and the CPU suspends, the
 * sensor hub keeps only its last few seconds. Without the lock, twenty leg raises recorded with the
 * phone in a pocket come back with the whole 28 s exercise missing while the pedometer's batch
 * arrives whole — the steps are in the file and the movement that made them is not. A trace is started on purpose
 * and stopped by hand, so the battery it costs is the price of the evidence.
 */
private fun CoroutineScope.holdAwakeWhileTracing(power: PowerManager) {
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pwnhealth:sensor-trace")
    // Not counted: the flow says recording or not, so one release undoes any number of acquires.
    lock.setReferenceCounted(false)
    launch {
        try {
            SensorTrace.state.map { it.recording }.distinctUntilChanged().collect { recording ->
                if (recording) lock.acquire(TRACE_WAKE_LOCK_MS) else lock.release()
            }
        } finally {
            // onDestroy cancels the scope; the lock must not outlive the service.
            lock.release()
        }
    }
}

/**
 * The one ongoing notification. Without a route it is the day's steps. While one records, or stands
 * paused, its title says which, with the distance so far, the day's steps go under it, and a tap
 * opens the track screen — where the recording is — rather than wherever the app was left, short of
 * closing a camera or a picker the app has open: the route goes without CLEAR_TOP, so it reaches a
 * MainActivity in front and leaves anything over one where it is.
 */
private fun Context.stepsNotification(dayTotal: Long, distance: String?, paused: Boolean): Notification {
    val steps = resources.getQuantityString(R.plurals.steps_today, dayTotal.toInt(), dayTotal)
    val title = routeNotificationTitle(route = distance != null, paused = paused)
    val open = PendingIntent.getActivity(
        this,
        if (title == null) 0 else ROUTE_NOTIFICATION_REQUEST,
        if (title == null) openAppIntent(this) else openAppIntent(this, WidgetRoute.TRACK, clearTop = false),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_steps)
        .setContentTitle(title?.let { getString(it, distance) } ?: steps)
        .apply { if (title != null) setContentText(steps) }
        .setContentIntent(open)
        .setOngoing(true)
        .setSilent(true)
        .build()
}

/**
 * What the ongoing notification's title says of a route — that it is recording, or that it stands
 * paused, each with the distance so far — or null without one, when the title is the day's steps.
 */
@StringRes
internal fun routeNotificationTitle(route: Boolean, paused: Boolean): Int? = when {
    !route -> null
    paused -> R.string.track_recording_paused
    else -> R.string.recording_distance
}

/**
 * Whether a fix starts a new stretch of line: an accepted one with no fix for the line to run on from,
 * on a route that already has some — the first after a pause, or after a restart that could not run
 * the line on. A rejected fix leaves that to the next accepted one, and the route's own first fix
 * starts the route, not a stretch.
 */
internal fun startsStretch(accepted: Boolean, lineRunsOn: Boolean, routeFixes: Int): Boolean =
    accepted && !lineRunsOn && routeFixes > 0

/**
 * Whether the service has anything to stay in the foreground for once a command is settled: a
 * [route] in hand, or one still being read back, or steps this phone can count — which takes the
 * counter and the physical activity permission the counter answers to.
 *
 * Not asked of the foreground type: Android 14 has none for a pedometer without the permission, so
 * there the two come to the same thing, but Android 13 lets a service declare none at all, and a
 * route stopped there without the permission would leave the ongoing notification standing over
 * "0 steps today", the accelerometer and the barometer running under it, for as long as the process
 * lived and again after each restart.
 */
internal fun serviceHasWork(route: Boolean, stepCounter: Boolean, stepPermission: Boolean): Boolean =
    route || (stepCounter && stepPermission)

/**
 * How often the route asks for a fix: every couple of seconds while it records, and twice a minute
 * while it stands [paused].
 *
 * A paused route stores no fix. What its fixes are still for is the position Android holds, which
 * places a photograph taken in the pause — with the camera in front, the track screen's own
 * listening has stopped — and that position may be a minute old ([FIX_WINDOW_MS]). At the walk's
 * own pace a pause left standing through a lunch would cost the battery what the walk did. Still at high
 * accuracy: a position the network guessed is too wide to mark a spot by.
 */
internal fun routeFixIntervalMs(paused: Boolean): Long = if (paused) PAUSED_FIX_INTERVAL_MS else FIX_INTERVAL_MS

private const val FIX_INTERVAL_MS = 2_000L
private const val PAUSED_FIX_INTERVAL_MS = 30_000L

/** The request a route listens under; fixes other apps cause are taken up to twice as often. */
private fun routeLocationRequest(paused: Boolean): LocationRequestCompat {
    val interval = routeFixIntervalMs(paused)
    return LocationRequestCompat.Builder(interval)
        .setMinUpdateIntervalMillis(interval / 2)
        .setMinUpdateDistanceMeters(0f) // filter in software, where we can see why
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        .build()
}

/** Platform fused provider (API 31+) needs no Play Services; GPS is the fallback. */
private fun LocationManager.routeProvider(): String =
    if (LocationManager.FUSED_PROVIDER in allProviders) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER

/**
 * Whether a recording picked back up after a restart starts a new stretch at its next fix rather than
 * run the line on from its last stored fix, taken [lastFixAgeMs] ago.
 *
 * A route whose row stands paused has a pause between that fix and now, however fresh the fix, and so
 * has one resumed while it was being read back ([resumedMeanwhile]), whichever of the resume and the
 * read reached the row first: the line never runs on across either. A paused route loses nothing by it,
 * since its resume starts a stretch anyway. Nothing records when a route was last resumed, so one going
 * again after a pause cannot tell a last fix taken just before the process died from one taken before a
 * pause whose first fix after never arrived. Older than the filter's own gap limit, the fix is taken for
 * the second: the line starts afresh, and nothing is counted across the gap. A route that never paused
 * runs on across any gap of the same boot, as it always has.
 */
internal fun restartBreaksLine(track: TrackEntity, lastFixAgeMs: Long, resumedMeanwhile: Boolean): Boolean =
    resumedMeanwhile || track.pausedAt != null || (track.pausedMillis > 0 && lastFixAgeMs > RESTART_GAP_LIMIT_MS)

/**
 * The class the leg from the distance anchor to a fix is credited to: the fix's own [movement], unless
 * the leg — [legMeters] in [legSeconds] — was covered faster than that class moves.
 *
 * A fix is classified by the slower of its Doppler and geometric speeds, and all the metres since the
 * anchor are credited with it. After a stretch the phone did not see — a metro ride — the first fix the
 * correlation filter confirms is a walker's, at a walker's Doppler speed, and the two and a half
 * kilometres behind it would go to the walking total, the splits and the calories. A leg no walk covers
 * (7 km/h), or no run (25 km/h), was ridden, whatever the fix at its end says.
 *
 * The worst error the quality gate lets in comes off the leg first: over the two seconds between
 * ordinary fixes, noise alone reads as a sprint.
 */
internal fun legMovement(movement: RouteMovement, legMeters: Double, legSeconds: Double): RouteMovement {
    val limitMps = when (movement) {
        RouteMovement.WALKING -> WALK_LEG_LIMIT_MPS
        RouteMovement.RUNNING -> RUN_LEG_LIMIT_MPS
        else -> return movement
    }
    val outrun = legMeters - MAX_ACCURACY_M > limitMps * legSeconds.coerceAtLeast(0.0)
    return if (outrun) RouteMovement.VEHICLE else movement
}

/**
 * The row a route write goes to: the recording's own, the one still being inserted, or — for a
 * service created after the recording was interrupted — the open one in the database.
 */
private suspend fun StepsDao.routeRow(id: Long?, row: Deferred<Long>): Long? = when (id) {
    null -> activeTrackNow()?.id
    PENDING_TRACK_ID -> row.await()
    else -> id
}

/**
 * The fix's altitude, and whether it is height above mean sea level. Android 14's fused provider
 * attaches a sea-level altitude beside the raw one; the raw one is height above the WGS84 ellipsoid,
 * up to a hundred metres from the sea, and the only figure older releases have. Whichever is taken,
 * the barometer is calibrated to it and every export inherits its datum.
 */
private fun preferredAltitude(location: Location): Pair<Double?, Boolean> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude() ->
        location.mslAltitudeMeters to true

    location.hasAltitude() -> location.altitude to false

    else -> null to false
}

/** Basic quality gate; trajectory consistency is handled by [RouteCorrelationFilter]. */
private fun qualityRejection(location: Location): RouteFixReason? {
    if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) return RouteFixReason.LOW_ACCURACY
    // On the first request the provider hands over its last known fix, which can be old and far
    // away. Accepting it draws a leg the user never walked.
    val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
    if (ageMs > MAX_FIX_AGE_MS) return RouteFixReason.STALE_FIX
    return null
}

private fun pressureJumped(current: Float?, previous: Float?): Boolean =
    current != null && previous != null && abs(current - previous) >= PRESSURE_SPIKE_HPA

private fun temperatureJumped(current: Float?, previous: Float?): Boolean =
    current != null && previous != null && abs(current - previous) >= TEMPERATURE_SPIKE_C

/**
 * Declares the foreground types, and falls back to a plain pedometer when `location` is refused.
 *
 * The location type is a standing granted per start, not per app. A service the boot receiver
 * brought up, killed and restarted by START_STICKY, arrives with `restoring` set and no while-in-use
 * standing for `location`, and Android 14 answers the type with a SecurityException. Uncaught, that
 * would crash the process, which START_STICKY would restart into the same line until the system gave
 * up, and the pedometer would be dead for the day. Without the type the service is a pedometer; a route, if
 * there is one, waits for the app to be opened. False means nothing may be declared at all.
 *
 * Nor may anything be declared when the platform refuses the start itself: a service the app brings
 * into the foreground from the background, which a START_STICKY restart is not exempt from, gets a
 * ForegroundServiceStartNotAllowedException, an IllegalStateException rather than a SecurityException.
 * A catch of the SecurityException alone would let it through, into the same crash and restart as a
 * refused type.
 */
private fun Service.startForegroundDegrading(
    id: Int,
    notification: Notification,
    types: Int,
    android14: Boolean,
): Boolean {
    // With neither permission on 14+ there is no type left to declare and startForeground would
    // throw, so there is nothing the service may legally do. On 33 an empty mask is correct for a
    // plain pedometer, which is why the check is version-gated rather than absolute.
    if (android14 && types == 0) return false
    // True once declared, false when the start is refused, null when the types are.
    fun declare(mask: Int): Boolean? = try {
        ServiceCompat.startForeground(this, id, notification, mask)
        true
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        false
    }
    return declare(types) ?: (pedometerFallback(types, android14)?.let(::declare) == true)
}

/**
 * The foreground types the service declares: `health` for a pedometer on Android 14 and later, where
 * it is given only with the physical activity permission and Android 13 has no such type to give,
 * and `location` for a [route] in hand or being read back, given only with the location permission.
 * Zero on Android 14 means nothing may be declared ([startForegroundDegrading]); on 13 it is a plain
 * pedometer's correct mask. The `health` constant is 34's, inlined as an int, and given only when
 * [android14] says the platform reads it, which lint cannot see in a parameter.
 */
@SuppressLint("InlinedApi")
internal fun foregroundTypes(
    android14: Boolean,
    stepPermission: Boolean,
    route: Boolean,
    locationPermission: Boolean,
): Int {
    val health = if (android14 && stepPermission) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
    val location = if (route && locationPermission) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    return health or location
}

/**
 * What to declare once [types] were refused for `location` ([startForegroundDegrading]): the
 * pedometer's types alone, or null when that is nothing new — there was no `location` to drop — or
 * nothing at all on Android 14, where an empty mask throws.
 */
internal fun pedometerFallback(types: Int, android14: Boolean): Int? {
    val pedometerOnly = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv()
    return pedometerOnly.takeIf { it != types && !(android14 && it == 0) }
}

/**
 * Whether the widget's Stop may be sent to the service, which means through startForegroundService:
 * the tap comes from the background, and no other start is open to it.
 *
 * A service already in the foreground owes that start nothing. A new one owes it a startForeground
 * before it stops, and once the route is closed the only type left to declare is `health`, which
 * Android 14 gives only with the step permission. Without it the service would stop having declared
 * nothing, and the platform answers that with an exception thrown in the app — on the Stop of a route
 * a restart or a reboot had left open, which is the one case where no service is running. That route
 * is closed without the service ([StopTrackAction]).
 */
internal fun stopGoesToService(serviceInForeground: Boolean, android14: Boolean, stepPermission: Boolean): Boolean =
    serviceInForeground || !android14 || stepPermission

private fun TrackPointEntity.isFromCurrentBoot(): Boolean {
    if (elapsedRealtimeNanos <= 0) return false
    val pointBootWallMs = timestamp - elapsedRealtimeNanos / 1_000_000
    val currentBootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    return abs(pointBootWallMs - currentBootWallMs) <= 60_000L
}

/**
 * How long ago a fix of this boot ([isFromCurrentBoot]) was taken, on the clock that never jumps. Its
 * stored time comes from the fix rather than the phone, and the two clocks can differ by more than the
 * 15 s the line is judged by: the boot check itself allows them a minute.
 */
private fun TrackPointEntity.ageMillis(): Long =
    (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

/** Where a stored fix put the walker, for the anchors to be measured from again. */
private fun TrackPointEntity.toRoutePosition() =
    RoutePosition(correctedLat ?: lat, correctedLon ?: lon, timestamp, elapsedRealtimeNanos)

/** A stored fix as the correlation filter saw it, for the filter to pick up from after a restart. */
private fun TrackPointEntity.toRouteFix() = RouteFix(
    token = elapsedRealtimeNanos,
    timestampNanos = elapsedRealtimeNanos,
    lat = correctedLat ?: lat,
    lon = correctedLon ?: lon,
    accuracyM = accuracyM?.toDouble() ?: MAX_ACCURACY_M.toDouble(),
    reportedSpeedMps = speedMps?.toDouble(),
    speedAccuracyMps = speedAccuracyMps?.toDouble(),
    motion = movementType.toMotion(),
)

/**
 * The offset that pinned the barometer to the satellites' altitude when this fix was stored, worked
 * back from its pressure and the altitude it was stored with; null unless it carries both.
 */
private fun TrackPointEntity.barometricOffsetM(): Float? {
    val pressure = pressureHpa ?: return null
    val calibrated = barometricAltitudeM ?: return null
    return calibrated - SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
}

private fun String.toMotion(): Motion = when (this) {
    RouteMovement.WALKING.name -> Motion.WALKING
    RouteMovement.RUNNING.name -> Motion.RUNNING
    RouteMovement.VEHICLE.name -> Motion.VEHICLE
    RouteMovement.STOPPED.name -> Motion.STILL
    else -> Motion.UNKNOWN
}

/**
 * Starts the pedometer again after a reboot, and after an update of the app.
 *
 * The counter itself keeps running through a reboot — it restarts at zero and counts in the sensor
 * hub whether or not this app is alive — but the first reading after a reboot is only a baseline
 * (see [StepAccumulator]), so nothing is credited until something has read the counter once. Without
 * this receiver that first reading would happen the next time the person opened the app, and every
 * step between the restart and that moment would be given up.
 *
 * An update replaces the package, which ends the process, and START_STICKY does not bring a service
 * back from that. Without this receiver nothing would listen to the counter until the app was next
 * opened — hours or days after an update installed overnight — and the steps of all that time would
 * arrive as one reading, to be credited to a single day or to none.
 *
 * `health` is not one of the types Android 15 refuses to start from `BOOT_COMPLETED` — that list is
 * dataSync, camera, mediaPlayback, mediaProjection, phoneCall and microphone. `location` is not on
 * it either, but a service started here is denied while-in-use location, which is why
 * [StepService.ACTION_BOOT] deliberately does not resume a recording.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!restartsPedometer(intent.action)) return
        // A reboot drops every alarm, the widgets' midnight among them ([WidgetClock]).
        WidgetClock.armMidnight(context)
        // Both are the conditions the home screen starts the service under, and the service would
        // stop itself at once without them: `health` may only be claimed while ACTIVITY_RECOGNITION
        // is held, and a phone with no pedometer has nothing for this service to listen to.
        if (!context.hasStepPermission()) return
        if (context.getSystemService(SensorManager::class.java)
                ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null
        ) {
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StepService::class.java).setAction(StepService.ACTION_BOOT),
            )
        } catch (_: IllegalStateException) {
            // Give up until the app is next opened. Both broadcasts are among the platform's own
            // exemptions for starting a foreground service from the background, so this should not
            // happen — but some vendors rewrite exactly this part of the framework, and an exception
            // thrown here is a crash dialog on every boot, on a phone whose owner cannot do anything
            // about it.
        }
    }
}

/**
 * Whether [action] is one of the two broadcasts after which nothing listens to the step counter until
 * the service is started again: the phone has booted, or this app's package has been replaced by an
 * update. The receiver's filter in the manifest names the same two, and a test holds them together.
 */
internal fun restartsPedometer(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

/** How long a step decided with the screen off may wait to be written; see [StepService.watchSteps]. */
private const val STEP_WRITE_AFTER_MS = 60_000L

/** How often the waiting steps are looked at: how soon after the screen comes on they are written. */
private const val STEP_WATCH_MS = 5_000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The process's one [StepLedger], and the lock every decision and every write of it takes, so the
 * writes land in the order the decisions were made.
 *
 * Held for the process, as [SensorFeed] is, not by the service: a service started again while the
 * last one's final write is still on its way finds the same ledger, rather than reading back a state
 * that write is about to replace and counting its steps a second time.
 *
 * Limitation: steps decided with the screen off live here for up to [STEP_WRITE_AFTER_MS]. A process
 * killed meanwhile loses the decisions, not the steps: the state on disk is older, so the next
 * reading's delta carries them and is judged afresh — including steps the gate had refused, which may
 * then pass. A minute of awake time is the ceiling: the watch loop does not run while the processor
 * sleeps, so the write of a pocketed phone lands at its next wake-up.
 */
private object Pedometer {
    private val lock = Mutex()
    private var ledger: StepLedger? = null

    suspend fun decide(db: StepsDb, reading: StepReading, now: Long): StepUpdate = lock.withLock {
        val book = ledger ?: db.stepLedger().also { ledger = it }
        book.add(reading, now, ZoneId.systemDefault())
    }

    suspend fun due(now: Long): Boolean = lock.withLock {
        ledger?.unwrittenSince?.let { now - it >= STEP_WRITE_AFTER_MS } == true
    }

    suspend fun unwritten(): Boolean = lock.withLock { ledger?.unwrittenSince != null }

    /**
     * [day]'s total as the database holds it, the figure [write] answers with. Under the lock, so it
     * is read between two writes and never across one: a figure read while a write was on its way
     * would land after that write's and put the notification back by its steps.
     */
    suspend fun dayTotal(db: StepsDb, day: String): Long = lock.withLock { db.dao().stepsOn(day) ?: 0L }

    /**
     * Writes whatever is unwritten; null when nothing was. Whole once begun: the ledger let it go.
     *
     * The total it answers with is today's, which is what the notification calls it. A batch the
     * sensor hands over just after midnight is of the day it was walked on, and that day's total would
     * otherwise stand under "steps today" until the first step of the new one.
     */
    suspend fun write(db: StepsDb): StepsWritten? = lock.withLock {
        val write = ledger?.take() ?: return@withLock null
        val written = withContext(NonCancellable) { db.writeSteps(write) }
        val today = LocalDate.now().toString()
        val shown = if (write.day == today) written else db.dao().stepsOn(today) ?: 0L
        StepsWritten(shown, write.credits.isNotEmpty())
    }
}

private class StepsWritten(val dayTotal: Long, val credited: Boolean)
