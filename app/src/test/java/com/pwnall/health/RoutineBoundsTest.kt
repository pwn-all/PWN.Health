package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What a session and a template are held to where nothing else holds them: a phone left lying between
 * two taps, a file from somebody else's phone, a back swipe in the middle of a workout, and a plan made
 * before its template was renamed.
 */
class RoutineBoundsTest {
    private val squat = RoutineExercise(routineId = 1, position = 0, activityCode = "SQUATS", durationMinutes = 10.0)
    private val press = squat.copy(activityCode = "BENCH_PRESS")

    @Test
    fun aRestLeftRunningOvernightCountsForAQuarterOfAnHour() {
        // One set of bench press done at 40 seconds, then the phone left on the bench until morning.
        val rested = FreestyleState(
            sets = listOf(FreeSet("BENCH_PRESS", 40, 10, 60.0)),
            code = "BENCH_PRESS",
            sessionStart = 0,
            setEnd = 40_000,
        )
        val morning = 40_000 + 12 * 3_600_000L

        // Finish in the morning, or the next set: the rest is a rest, not twelve hours of lifting.
        assertEquals(MAX_COUNTED_REST_SECONDS, rested.finalSets(morning, counted = true, unitFactor = 1.0).last().rest)
        assertEquals(MAX_COUNTED_REST_SECONDS, rested.beginning(morning).sets.last().rest)
        // A rest that was one is counted to the second.
        assertEquals(90, rested.finalSets(130_000, counted = true, unitFactor = 1.0).last().rest)
        assertEquals(90, rested.beginning(130_000).sets.last().rest)
        // A set of reps left running as long is held the same way; a walk is as long as it was.
        val running = FreestyleState(code = "BENCH_PRESS", sessionStart = 0, setStart = 0)
        assertEquals(MAX_COUNTED_REST_SECONDS, running.stopping(morning, counted = true, unitFactor = 1.0).entering)
        assertEquals(43_240, running.stopping(morning, counted = false, unitFactor = 1.0).sets.single().seconds)
    }

    @Test
    fun aStretchThatWaitsForATapCountsForItsPlanOrAQuarterOfAnHour() {
        val night = 12 * 3_600_000L
        // A set of ten reps, half a minute by the estimate, and the rest after it until the next set.
        assertEquals(
            MAX_COUNTED_REST_SECONDS,
            RoutinePhase(PhaseKind.EXERCISE, seconds = 30, manual = true).countedSeconds(night),
        )
        assertEquals(75, RoutinePhase(PhaseKind.EXERCISE, seconds = 30, manual = true).countedSeconds(75_400))
        // A rest planned longer than that is given what it was planned at.
        assertEquals(1_200, RoutinePhase(PhaseKind.REST, seconds = 1_200, manual = true).countedSeconds(night))
        // A stretch the clock ends is not this rule's: an hour's run counts for the fifty minutes it ran.
        assertEquals(3_000, RoutinePhase(PhaseKind.EXERCISE, seconds = 3_600).countedSeconds(3_000_000))
    }

    @Test
    fun aPauseKeepsWhatAStretchRanPastItsEstimate() {
        val set = RoutinePhase(PhaseKind.EXERCISE, seconds = 30, manual = true)
        // Paused 45 seconds into a set estimated at 30: the pause holds the 45, however long it lasts.
        val paused = stretchRan(pausedRan = -1, startedAt = 10_000, at = 55_000)
        assertEquals(45_000L, paused)
        assertEquals(45, set.countedSeconds(stretchRan(paused, startedAt = 10_000, at = 500_000)))
        // Continue moves the start to where the 45 seconds have just run; 15 more make a minute.
        val resumedAt = 500_000L
        val startedAt = resumedAt - paused
        assertEquals(60, set.countedSeconds(stretchRan(pausedRan = -1, startedAt, at = resumedAt + 15_000)))
        // A clock that reads before the start has run nothing.
        assertEquals(0L, stretchRan(pausedRan = -1, startedAt = 10_000, at = 9_000))
    }

    /**
     * The page comes back after two minutes behind another app, its session having stood at the start
     * of getting ready: the clock runs it on through every timed stretch — ready, a minute's run, half
     * a minute's rest — each begun where the last ran out, and stops at the set of reps, which waits
     * for its "Complete set" however long it is left.
     */
    @Test
    fun theClockCatchesUpThroughTimedStretchesAndStopsAtASetThatWaits() {
        val phases = listOf(
            RoutinePhase(PhaseKind.READY, 5),
            RoutinePhase(PhaseKind.EXERCISE, 60),
            RoutinePhase(PhaseKind.REST, 30),
            RoutinePhase(PhaseKind.EXERCISE, 30, manual = true),
            RoutinePhase(PhaseKind.REST, 60),
        )

        assertEquals(0 to 0L, clockCaughtUp(phases, phase = 0, startedAt = 0, now = 4_999))
        assertEquals(1 to 5_000L, clockCaughtUp(phases, phase = 0, startedAt = 0, now = 5_000))
        assertEquals(3 to 95_000L, clockCaughtUp(phases, phase = 0, startedAt = 0, now = 120_000))
        assertEquals(3 to 95_000L, clockCaughtUp(phases, phase = 3, startedAt = 95_000, now = 12 * 3_600_000L))
        // Past the last timed stretch is past the end: the session is over.
        assertEquals(phases.size to 330_000L, clockCaughtUp(phases, phase = 4, startedAt = 270_000, now = 400_000))
    }

    /**
     * The editor's steps through a turn of the phone: a superset with its rest, a lift planned set by
     * set, rows with every optional figure missing, and the empty superset just added and not yet
     * filled come back exactly as they went.
     */
    @Test
    fun theEditorsStepsComeBackFromTheirSavedStateAsTheyWent() {
        val sets = routineSetsJson(listOf(RoutineSet(10, 60.0, restSeconds = 90), RoutineSet(seconds = 45)))
        val blocks = listOf(
            RoutineBlock(listOf(squat.copy(sets = 3, reps = 10, loadKg = 82.5, setsJson = sets)), restSeconds = 90),
            RoutineBlock(listOf(press.copy(superset = 1), press.copy(activityCode = "PULLUPS")), superset = true),
            RoutineBlock(emptyList(), superset = true, restSeconds = 60),
        )

        val saved = with(BLOCKS_SAVER) { SaverScope { true }.save(blocks) }

        assertEquals(blocks, BLOCKS_SAVER.restore(checkNotNull(saved)))
    }

    @Test
    fun leavingASessionIsAskedAboutOnceThereIsWorkToLose() {
        val phases = listOf(
            RoutinePhase(PhaseKind.READY, 5),
            RoutinePhase(PhaseKind.EXERCISE, 30, manual = true),
            RoutinePhase(PhaseKind.REST, 60, manual = true),
            RoutinePhase(PhaseKind.EXERCISE, 45),
        )
        val nothing = IntArray(phases.size)
        val unheld = BooleanArray(phases.size)

        // Getting ready is no work, and the first set is from its first second.
        assertFalse(sessionHasWork(phases, nothing, unheld, at = 0, ran = 4))
        assertFalse(sessionHasWork(phases, nothing, unheld, at = 1, ran = 0))
        assertTrue(sessionHasWork(phases, nothing, unheld, at = 1, ran = 1))
        // A set of reps skipped and said not to be done leaves nothing behind it; one said done does.
        val skipped = intArrayOf(5, 20, 0, 0)
        assertFalse(sessionHasWork(phases, skipped, unheld, at = 2, ran = 30))
        assertTrue(sessionHasWork(phases, skipped, booleanArrayOf(false, true, false, false), at = 2, ran = 30))
        // A timed set counts for any time it was held, as the journal counts it.
        assertTrue(sessionHasWork(phases, intArrayOf(5, 20, 60, 3), unheld, at = phases.size, ran = 0))
    }

    @Test
    fun aTemplateFileBringsAHundredExercisesANameOnOneLineAndNoFloors() {
        val many = List(MAX_FILE_EXERCISES + 50) { squat.copy(position = it) }
        val climbed = routineSetsJson(listOf(RoutineSet(reps = 10, floors = 1_000_000)))
        val wild = Routine(name = " Push\nday A\t", createdAt = 1L)

        val read = checkNotNull(readRoutineFile(routineFileText(wild, many, null)))

        assertEquals(MAX_FILE_EXERCISES, read.rows.size)
        // Cut, and said to be: the page is told how many the file listed. A file that fits says nothing.
        assertEquals(MAX_FILE_EXERCISES + 50, read.cutFrom)
        val fits = routineFileText(wild, many.take(MAX_FILE_EXERCISES), null)
        assertEquals(0, checkNotNull(readRoutineFile(fits)).cutFrom)
        assertEquals("Push day A", read.name)
        // The form's own name goes the same way, and a name of nothing but breaks is no name.
        assertEquals("Leg day", routineName("Leg\r\nday "))
        assertEquals("", routineName("\n\u0001"))
        // Cut at the box's sixty characters, a name does not end on the space the cut fell after.
        assertEquals("a".repeat(59), routineName("a".repeat(59) + " b"))
        // Floors are what the phone counted of a set done; a plan asks for none.
        val lift = checkNotNull(readRoutineFile(routineFileText(wild, listOf(press.copy(setsJson = climbed)), null)))
        assertEquals(listOf(RoutineSet(10)), routineSets(lift.rows.single()))
        // A name that kept a line break — an older template's — still numbers its sessions.
        assertEquals(2, nextSessionNumber(listOf(sessionTitle("Push\nday", 1)), "Push\nday"))
    }

    @Test
    fun aPlanAheadFollowsItsTemplateAndADateIsPlannedOnce() {
        val template = Routine(id = 3, name = "Push day", createdAt = 1L)
        val friday = LocalDate.of(2026, 10, 16)
        val ahead = planEntry(template, emptyList(), friday).copy(id = 1)
        val gone = planEntry(template, emptyList(), LocalDate.of(2026, 10, 10)).copy(id = 2)
        val another = planEntry(template.copy(id = 4, name = "Legs"), emptyList(), friday).copy(id = 3)
        val renamed = template.copy(name = "Upper A")
        val rows = listOf(squat.copy(durationMinutes = 25.0))

        val followed = plansFollowing(listOf(ahead, gone, another), renamed, rows, today = "2026-10-14")

        // The plan still ahead takes the new name and minutes; the day gone by and the other template's do not.
        assertEquals(listOf(ahead.copy(title = "Upper A", durationMinutes = 25.0)), followed)
        // Today's plan is ahead too, and one that already reads right is not written again.
        assertEquals(1, plansFollowing(listOf(ahead), renamed, rows, today = "2026-10-16").size)
        assertTrue(plansFollowing(followed, renamed, rows, today = "2026-10-14").isEmpty())

        // The same template on the same day is one plan; another template's, or another app's, is not it.
        assertTrue(listOf(another, ahead).holdsPlanOf(template.id))
        assertFalse(listOf(another).holdsPlanOf(template.id))
        assertFalse(listOf(ahead.copy(measurementPayloadJson = null)).holdsPlanOf(template.id))
    }

    /**
     * A file whose picture is not Base64 — cut short in a message, edited by hand — is still the
     * template it holds, taken without the picture, as one whose picture will not decode as an image
     * is.
     */
    @Test
    fun aTemplateFileWithAPictureThatWillNotReadIsTakenWithoutIt() {
        val text = JSONObject(routineFileText(Routine(name = "Legs", createdAt = 1L), listOf(squat), null))
            .put("jpeg", "not Base64 at all!")
            .toString()

        val read = checkNotNull(readRoutineFile(text))

        assertEquals("Legs", read.name)
        assertEquals(listOf("SQUATS"), read.rows.map { it.activityCode })
        assertNull(read.jpeg)
    }
}
