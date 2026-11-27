package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * What the recording service decides on its own: whether the line runs on from the last stored fix
 * when a restart picks a route back up, which class a leg of it is credited to, what time a fix is
 * stored under and how it is booked, whether a stop may be sent to it at all, whether it has anything
 * left to stay up for, how often it asks for a fix, and what the ongoing notification's title says of
 * a route, in every language.
 */
class RouteRecordingTest {
    private val start = 1_785_400_000_000L
    private val now = start + 3_600_000
    private val boot = 5_000_000_000_000L
    private val metresPerDegree = EARTH_RADIUS_M * PI / 180.0

    /** A walker's fix [metresNorth] of the start, [seconds] into the route, as [RouteTally.add] takes one. */
    private fun walking(metresNorth: Double, seconds: Long) = TalliedFix(
        position = RoutePosition(
            lat = 55.75 + metresNorth / metresPerDegree,
            lon = 37.62,
            timeMs = start + seconds * 1_000,
            elapsedRealtimeNanos = boot + seconds * 1_000_000_000,
        ),
        accuracyM = 5.0,
        reportedSpeedMps = 1.4,
        speedAccuracyMps = 0.5,
        reading = MotionReading(motion = Motion.WALKING, std = 2.5f, cadenceHz = 1.8f, periodicity = 0.6f),
        stepsCounted = true,
        elevationM = null,
        barometric = false,
        verticalAccuracyM = null,
    )

    /**
     * Only the first fix keeps its own time; after it the boot clock, which never steps, says how much
     * later each one is. The fix's own clock stepping back an hour leaves the line in order, and a
     * cached fix handed over late is stored at the latest time without dating the next by its age.
     */
    @Test
    fun theRouteClockNeverRunsBack() {
        val first = routeFixClock(null, start, boot)
        assertEquals(RouteClock(start, boot), first)

        val second = routeFixClock(first, start - 3_600_000 + 2_000, boot + 2_000_000_000)
        assertEquals(RouteClock(start + 2_000, boot + 2_000_000_000), second)

        val cached = routeFixClock(second, start - 60_000, boot - 60_000_000_000)
        assertEquals(second, cached)
        assertEquals(start + 4_000, routeFixClock(cached, start - 3_596_000, boot + 4_000_000_000).timeMs)
    }

    /** Across a reboot the two boot clocks say nothing of each other: a fix is only never stored earlier. */
    @Test
    fun acrossARebootTheRouteClockOnlyNeverGoesBack() {
        val storedOnAnEarlierBoot = RouteClock(start, 0L)

        assertEquals(start, routeFixClock(storedOnAnEarlierBoot, start - 1_000, 3_000_000_000).timeMs)
        assertEquals(
            RouteClock(start + 90_000, 3_000_000_000),
            routeFixClock(storedOnAnEarlierBoot, start + 90_000, 3_000_000_000),
        )
    }

    /** A walk at 1.4 m/s: the first fix is the anchor, and every leg after it is walking distance. */
    @Test
    fun aWalksLegsAreCreditedToWalking() {
        val tally = RouteTally()

        val legs = listOf(walking(0.0, 0), walking(7.0, 5), walking(14.0, 10)).map(tally::add)

        assertEquals(List(3) { RouteMovement.WALKING }, legs.map(TalliedLeg::movement))
        assertEquals(14.0, tally.totals.meters, 1e-6)
        assertEquals(14.0, tally.totals.walkingMeters, 1e-6)
        assertEquals(0.0, tally.totals.vehicleMeters, 0.0)
        assertEquals(3, tally.totals.points)
        assertTrue(tally.lineRunsOn)
    }

    /**
     * Two and a half kilometres in 250 seconds the phone did not see — a metro ride — and then a fix at
     * a walker's Doppler speed: the leg goes to the vehicle total, not to the walk.
     */
    @Test
    fun aLegNoWalkCoversIsNotCreditedToTheWalk() {
        val tally = RouteTally()
        tally.add(walking(0.0, 0))
        tally.add(walking(7.0, 5))

        val leg = tally.add(walking(2_507.0, 255))

        assertEquals(RouteMovement.VEHICLE, leg.movement)
        assertEquals(7.0, tally.totals.walkingMeters, 1e-6)
        assertEquals(2_500.0, tally.totals.vehicleMeters, 1e-6)
        assertEquals(2_507.0, tally.totals.meters, 1e-6)
    }

    /**
     * A broken line — a pause, or a stretch the filter gave up on — counts nothing across the gap: the
     * first fix after it is a new anchor, the totals stand, and the walk counts again from there.
     */
    @Test
    fun nothingIsCountedAcrossABrokenLine() {
        val tally = RouteTally()
        tally.add(walking(0.0, 0))
        tally.add(walking(7.0, 5))

        tally.breakLine()
        assertFalse(tally.lineRunsOn)
        tally.add(walking(507.0, 305))
        assertEquals(7.0, tally.totals.meters, 1e-6)

        tally.add(walking(514.0, 310))
        assertEquals(14.0, tally.totals.meters, 1e-6)
        assertEquals(4, tally.totals.points)
    }

    /** [foregroundTypes] with the two permissions by short names: [steps] and [place]. */
    private fun types(android14: Boolean, steps: Boolean, route: Boolean, place: Boolean) =
        foregroundTypes(android14, stepPermission = steps, route = route, locationPermission = place)

    /** A route that never paused runs its line on across a restart of the same boot. */
    @Test
    fun aRouteThatNeverPausedRunsOnAcrossARestart() {
        val never = TrackEntity(startedAt = start)

        assertFalse(restartBreaksLine(never, lastFixAgeMs = 5_000, resumedMeanwhile = false))
        assertFalse(restartBreaksLine(never, lastFixAgeMs = 600_000, resumedMeanwhile = false))
    }

    /**
     * Paused, resumed, and the process gone before the first fix after: the last fix stored is the one
     * from before the pause, minutes old, and the line must not run on from it. A last fix within the
     * filter's own gap limit was the last before the process died, and the line runs on.
     */
    @Test
    fun aRouteThatHasPausedStartsAfreshFromAnOldLastFix() {
        val resumed = TrackEntity(startedAt = start, pausedMillis = 300_000)

        assertTrue(restartBreaksLine(resumed, lastFixAgeMs = 600_000, resumedMeanwhile = false))
        assertTrue(restartBreaksLine(resumed, lastFixAgeMs = 15_001, resumedMeanwhile = false))
        assertFalse(restartBreaksLine(resumed, lastFixAgeMs = 15_000, resumedMeanwhile = false))
        assertFalse(restartBreaksLine(resumed, lastFixAgeMs = 5_000, resumedMeanwhile = false))
    }

    /**
     * A route whose row stands paused has a pause between its last fix and now, however fresh that fix:
     * paused five seconds ago, a second after its last fix, it never runs the line on from before the
     * pause — the row alone says so, with or without a resume that came while it was read back.
     */
    @Test
    fun aRouteStandingPausedNeverRunsItsLineOn() {
        val paused = TrackEntity(startedAt = start, pausedAt = now - 5_000)

        assertTrue(restartBreaksLine(paused, lastFixAgeMs = 6_000, resumedMeanwhile = false))
        assertTrue(restartBreaksLine(paused, lastFixAgeMs = 6_000, resumedMeanwhile = true))
        assertTrue(restartBreaksLine(paused, lastFixAgeMs = 120_000, resumedMeanwhile = false))
    }

    /**
     * A resume that came while the route was read back has a pause behind it even when the read found
     * the row going again — the pause already closed into pausedMillis — and the last fix fresh.
     */
    @Test
    fun aResumeWhileTheRouteIsReadBackBreaksTheLine() {
        val going = TrackEntity(startedAt = start, pausedMillis = 4_000)

        assertTrue(restartBreaksLine(going, lastFixAgeMs = 6_000, resumedMeanwhile = true))
        assertFalse(restartBreaksLine(going, lastFixAgeMs = 6_000, resumedMeanwhile = false))
    }

    /**
     * The first accepted fix the line has nothing to run on from starts a stretch: after a pause, or
     * after a restart that broke the line. Mid-stretch, a fix runs the line on; a rejected fix leaves
     * the start to the next accepted one; and the route's very first fix starts no stretch.
     */
    @Test
    fun theFirstAcceptedFixWithNothingBeforeItStartsAStretch() {
        assertTrue(startsStretch(accepted = true, lineRunsOn = false, routeFixes = 12))
        assertFalse(startsStretch(accepted = true, lineRunsOn = true, routeFixes = 12))
        assertFalse(startsStretch(accepted = false, lineRunsOn = false, routeFixes = 12))
        assertFalse(startsStretch(accepted = true, lineRunsOn = false, routeFixes = 0))
    }

    /**
     * A metro ride the phone did not see, and a walker's fix at the far end of it: two and a half
     * kilometres in a little over four minutes is no walk and no run, whatever the fix was classified
     * as, so the leg is a ride. A run's ceiling is higher: a kilometre in those minutes is still a run.
     */
    @Test
    fun aLegNoWalkCoversIsCreditedAsARide() {
        assertEquals(RouteMovement.VEHICLE, legMovement(RouteMovement.WALKING, legMeters = 2_500.0, legSeconds = 250.0))
        assertEquals(RouteMovement.VEHICLE, legMovement(RouteMovement.RUNNING, legMeters = 2_500.0, legSeconds = 250.0))
        assertEquals(RouteMovement.RUNNING, legMovement(RouteMovement.RUNNING, legMeters = 1_000.0, legSeconds = 250.0))
        // Five minutes at 7 km/h is 583 m, and the quality gate's 25 m of error comes on top of it.
        assertEquals(RouteMovement.WALKING, legMovement(RouteMovement.WALKING, legMeters = 608.0, legSeconds = 300.0))
        assertEquals(RouteMovement.VEHICLE, legMovement(RouteMovement.WALKING, legMeters = 609.0, legSeconds = 300.0))
    }

    /**
     * An ordinary leg keeps the class of its fix: a walker's few metres between two fixes, a jump of the
     * receiver's noise inside one second, a quarter of an hour at a walking pace held through a stretch
     * nothing classified. A class with no ceiling, or with nothing to credit, is never touched.
     */
    @Test
    fun anOrdinaryLegKeepsTheClassOfItsFix() {
        assertEquals(RouteMovement.WALKING, legMovement(RouteMovement.WALKING, legMeters = 3.0, legSeconds = 2.0))
        assertEquals(RouteMovement.WALKING, legMovement(RouteMovement.WALKING, legMeters = 20.0, legSeconds = 1.0))
        assertEquals(RouteMovement.WALKING, legMovement(RouteMovement.WALKING, legMeters = 1_500.0, legSeconds = 900.0))
        assertEquals(RouteMovement.WALKING, legMovement(RouteMovement.WALKING, legMeters = 0.0, legSeconds = 0.0))
        assertEquals(RouteMovement.RUNNING, legMovement(RouteMovement.RUNNING, legMeters = 12.0, legSeconds = 2.0))
        listOf(RouteMovement.VEHICLE, RouteMovement.STOPPED, RouteMovement.UNKNOWN).forEach { movement ->
            assertEquals(movement, legMovement(movement, legMeters = 2_500.0, legSeconds = 1.0))
        }
    }

    /**
     * The widget's Stop reaches the service through a foreground start, which a service already in the
     * foreground owes nothing and a new one has to answer by declaring a type. With the route closed
     * only `health` is left, and Android 14 gives it only with the step permission: that one stop is
     * settled without the service, and every other goes to it.
     */
    @Test
    fun aStopNoServiceCouldAnswerIsNotSentToOne() {
        assertFalse(stopGoesToService(serviceInForeground = false, android14 = true, stepPermission = false))
        assertTrue(stopGoesToService(serviceInForeground = true, android14 = true, stepPermission = false))
        assertTrue(stopGoesToService(serviceInForeground = false, android14 = true, stepPermission = true))
        assertTrue(stopGoesToService(serviceInForeground = false, android14 = false, stepPermission = false))
    }

    /**
     * A stop comes through startForegroundService, and a service stopped before its startForeground is
     * an exception the platform throws in the app, so stopTrack must not stop a pedometer-less service
     * itself, ahead of the pushForeground that follows it in onStartCommand.
     */
    @Test
    fun theServiceDeclaresItselfBeforeItStops() {
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val command = source.substringAfter("override fun onStartCommand(").substringBefore("\n    }\n")
        val stop = source.substringAfter("private fun stopTrack() {").substringBefore("\n    }\n")

        assertFalse("stopTrack stops the service itself", "stopSelf()" in stop)
        assertTrue("pushForeground()" in command)
        assertTrue(command.indexOf("pushForeground()") < command.indexOf("stopSelf()"))
    }

    /**
     * A pause, a resume and a stop from the track screen are plain starts: the screen is in front, so
     * they are allowed, and they leave the service owing Android no startForeground. One brought up
     * for a route a restart had left open has, on Android 14 with neither the location nor the physical
     * activity permission, no type to declare — and a service stopped without one after a foreground
     * start is closed along with the app. Only a start of a recording, which always has `location` to
     * declare, is still sent as a foreground start.
     */
    @Test
    fun theScreenSendsOnlyAStartAsAForegroundStart() {
        val screen = File("src/main/java/com/pwnall/health/TrackScreen.kt").readText()

        assertTrue("sendToService(StepService.ACTION_START_TRACK)" in screen)
        listOf("ACTION_STOP_TRACK", "ACTION_PAUSE_TRACK", "ACTION_RESUME_TRACK").forEach { action ->
            assertFalse(action, "sendToService(StepService.$action)" in screen)
            assertTrue(action, "setAction(StepService.$action)" in screen)
        }
    }

    /**
     * The service stays up for a route in hand or for steps it can count, and counting takes the
     * permission as well as the counter. Asked of the foreground type instead, Android 13 — where a
     * pedometer declares none — would keep a service that could count nothing under "0 steps today"
     * once its route was stopped.
     */
    @Test
    fun theServiceStaysOnlyForARouteOrForStepsItCanCount() {
        assertTrue(serviceHasWork(route = false, stepCounter = true, stepPermission = true))
        assertFalse(serviceHasWork(route = false, stepCounter = true, stepPermission = false))
        assertFalse(serviceHasWork(route = false, stepCounter = false, stepPermission = true))
        assertFalse(serviceHasWork(route = false, stepCounter = false, stepPermission = false))
        listOf(true, false).forEach { counter ->
            listOf(true, false).forEach { permission ->
                assertTrue(serviceHasWork(route = true, stepCounter = counter, stepPermission = permission))
            }
        }
    }

    /**
     * The types the service declares, over the versions, the two permissions and whether a route is in
     * hand or being read back: `health` for a pedometer only on Android 14 with the step permission,
     * `location` only for a route with the location permission. Android 14 with neither leaves nothing,
     * which is the service's cue that it may declare nothing at all.
     */
    @Test
    fun theServiceDeclaresTheTypesItsPermissionsAndItsRouteGiveIt() {
        val health = types(android14 = true, steps = true, route = false, place = true)
        val location = types(android14 = true, steps = false, route = true, place = true)
        assertNotEquals(0, health)
        assertNotEquals(0, location)
        assertEquals(0, health and location)

        assertEquals(health or location, types(android14 = true, steps = true, route = true, place = true))
        assertEquals(health, types(android14 = true, steps = true, route = true, place = false))
        assertEquals(0, types(android14 = true, steps = false, route = true, place = false))
        assertEquals(0, types(android14 = true, steps = false, route = false, place = true))
        // Android 13 has no `health` to give: a pedometer there declares an empty mask, which it may.
        assertEquals(0, types(android14 = false, steps = true, route = false, place = true))
        assertEquals(location, types(android14 = false, steps = true, route = true, place = true))
    }

    /**
     * Refused `location` — a restart from the background has no while-in-use standing for it — the
     * service falls back to the pedometer alone: `health` on Android 14, the empty mask on 13. With no
     * `location` to drop there is nothing to fall back to, and on Android 14 an empty mask is not one.
     */
    @Test
    fun aRefusedLocationFallsBackToThePedometerWhereThereIsOne() {
        val health = types(android14 = true, steps = true, route = false, place = false)
        val location = types(android14 = false, steps = false, route = true, place = true)

        assertEquals(health, pedometerFallback(health or location, android14 = true))
        assertNull(pedometerFallback(location, android14 = true))
        assertEquals(0, pedometerFallback(location, android14 = false))
        assertNull(pedometerFallback(health, android14 = true))
        assertNull(pedometerFallback(0, android14 = false))
    }

    /**
     * A paused route stores no fix and asks for one far less often, yet often enough for the position
     * Android holds to place a photograph taken in the pause, which takes one of the last minute.
     */
    @Test
    fun aPausedRouteAsksForAFixFarLessOftenAndStillPlacesAPhotograph() {
        assertEquals(2_000L, routeFixIntervalMs(paused = false))
        assertTrue(routeFixIntervalMs(paused = true) >= 10 * routeFixIntervalMs(paused = false))
        assertTrue(routeFixIntervalMs(paused = true) <= FIX_WINDOW_MS / 2)
    }

    /**
     * A restore reads the open row, and a read does not wait for a writer: begun while a stop's
     * endTrack stands behind a backup's write lock, it would find the route open and take it up
     * again. So the restore waits for the stop's write before it reads.
     */
    @Test
    fun aRestoreWaitsForTheStopBeforeItReadsTheRow() {
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val stop = source.substringAfter("private fun stopTrack() {").substringBefore("\n    }\n")
        val restore = source.substringAfter("private fun restoreTrack(").substringBefore("\n    }\n")

        assertTrue("routeWrite = durable.launch" in stop)
        assertTrue(restore, restore.startsWith("stop: Job? = routeWrite)"))
        assertTrue("stop?.join()" in restore)
        assertTrue(restore.indexOf("stop?.join()") < restore.indexOf("activeTrackNow()"))
    }

    /**
     * The notification's "steps today" is a figure the service keeps, moved only by a step it writes,
     * so a start after midnight could put yesterday's back. Every command that leaves the service
     * running reads the day's total again.
     */
    @Test
    fun theDaysTotalIsReadAgainAtEveryStart() {
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val command = source.substringAfter("override fun onStartCommand(").substringBefore("\n    }\n")
        val read = source.substringAfter("private fun readDayTotal()").substringBefore("\n    }\n")

        assertTrue("readDayTotal()" in command)
        assertTrue(read, "dayTotal = Pedometer.dayTotal(db, LocalDate.now().toString())" in read)
    }

    /**
     * The widget's Stop closes the row itself where no service stands in the foreground. Tapped while
     * a service just started is reading that row back, it would leave the service recording into a
     * route already finished. Every command asks whether the route in hand is still the open one, and
     * the stop that follows leaves the moment the route ended at alone.
     */
    @Test
    fun aRouteClosedPastTheServiceIsLetGoOfAtTheNextCommand() {
        assertTrue(routeLetGo(held = 7, open = null))
        assertTrue(routeLetGo(held = 7, open = 8))
        assertFalse(routeLetGo(held = 7, open = 7))
        assertFalse(routeLetGo(held = null, open = 7))
        // A route whose row is still being inserted has no row that could have been closed.
        assertFalse(routeLetGo(held = 0, open = null))
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val command = source.substringAfter("override fun onStartCommand(").substringBefore("\n    }\n")
        val data = File("src/main/java/com/pwnall/health/Data.kt").readText()

        assertTrue("dropRouteClosedElsewhere()" in command)
        assertTrue("WHERE id = :trackId AND endedAt IS NULL" in data)
    }

    /**
     * A stop that comes while the route's row is still being inserted lets go of that row, once the
     * fix the filter held has been sent to it. Kept, the row's id would be installed when the insert
     * finished: the stopped route would be in hand again, and the next Start would do nothing.
     */
    @Test
    fun aStopLetsGoOfARowStillBeingInserted() {
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val stop = source.substringAfter("private fun stopTrack() {").substringBefore("\n    }\n")

        assertTrue(stop, "trackRow = CompletableDeferred()" in stop)
        assertTrue(stop.indexOf("resolveRouteFix(") < stop.indexOf("trackRow = CompletableDeferred()"))
    }

    /**
     * What a read on the service scope hands to the main thread is dropped by a service destroyed in
     * the meantime: a restore's install or a command to stop would register the sensors again on an
     * instance whose onDestroy has already unregistered them.
     */
    @Test
    fun nothingReachesTheMainThreadOfADestroyedService() {
        val source = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        val restore = source.substringAfter("private fun restoreTrack(").substringBefore("\n    }\n")
        val drop = source.substringAfter("private fun dropRouteClosedElsewhere(").substringBefore("\n    }\n")

        assertTrue(destroy, "restoring = false" in destroy)
        assertTrue(restore, "if (!restoring) return@execute" in restore)
        assertTrue(drop, "scope.coroutineContext.job.isActive" in drop)
    }

    @Test
    fun theTitleSaysWhetherTheRouteRecordsOrStandsPaused() {
        assertNull(routeNotificationTitle(route = false, paused = false))
        assertNull(routeNotificationTitle(route = false, paused = true))
        assertEquals(R.string.recording_distance, routeNotificationTitle(route = true, paused = false))
        assertEquals(R.string.track_recording_paused, routeNotificationTitle(route = true, paused = true))
    }

    /** The words as the English resources hold them, the distance after either. */
    @Test
    fun theTitlesSayPlainlyWhatTheRouteIsDoing() {
        fun english(file: String, name: String): String? {
            val text = File("src/main/res/values/$file").readText()
            return Regex("""<string name="$name">([^<]*)</string>""").find(text)?.groupValues?.get(1)
        }

        assertEquals("Recording — %1\$s", english("app.xml", "recording_distance"))
        assertEquals("Recording paused — %1\$s", english("track_recording.xml", "track_recording_paused"))
    }

    /**
     * Every language says the pause in its own words, on the notification and on the widget, as it says
     * the recording: an English one left in a translation switches the notification's language as the
     * route pauses, and lint's MissingTranslation is satisfied by it.
     */
    @Test
    fun everyLanguageSaysThePauseInItsOwnWords() {
        fun strings(file: File): Map<String, String> =
            Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }

        val english = strings(File("src/main/res/values/track_recording.xml"))
        val translations = File("src/main/res").listFiles { dir -> dir.name.startsWith("values-") }.orEmpty()
            .map { File(it, "track_recording.xml") }
            .filter(File::exists)

        assertEquals(setOf("track_recording_paused", "widget_track_paused"), english.keys)
        assertEquals("track_recording.xml in the fifteen translations", 15, translations.size)
        translations.forEach { file ->
            val shown = strings(file)
            assertEquals(file.path, english.keys, shown.keys)
            english.forEach { (name, words) -> assertNotEquals("$name in ${file.path}", words, shown[name]) }
            assertTrue(file.path, shown.getValue("track_recording_paused").contains("%1\$s"))
        }
    }
}
