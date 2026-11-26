package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * A template's own writes over Room itself, on Robolectric so that every `check` runs them: a new one
 * saved twice by a page rebuilt in between is one template, a changed one keeps its id, its plans and
 * only its newest picture, a deleted one takes its plans and nothing else's, and a session lands in
 * the journal as it went.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class RoutineStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    private val squat = RoutineExercise(routineId = 0, position = 0, activityCode = "SQUATS", durationMinutes = 10.0)
    private val plank = squat.copy(activityCode = "PLANK")
    private val press = squat.copy(activityCode = "BENCH_PRESS")
    private val pull = squat.copy(activityCode = "PULLUPS")
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")

    @After
    fun close() = db.close()

    private suspend fun save(routine: Routine, exercise: RoutineExercise): Routine {
        val blocks = listOf(RoutineBlock(listOf(exercise)))
        return db.saveRoutine(context, routine, before = "", pendingPhoto = false, blocks = blocks)
    }

    /**
     * A turn of the phone while a new template is being written rebuilds the page without the answer,
     * and Save, ready again, would write a second template of the same name. The flow keeps the moment
     * it was opened, and the second save lands on the first.
     */
    @Test
    fun aNewTemplateSavedAgainAfterATurnOfThePhoneIsStillOne() = runBlocking {
        val routine = Routine(name = "Push day", createdAt = 1_794_000_000_000L)

        val first = save(routine, squat)
        val again = save(routine, plank)

        assertEquals(first.id, again.id)
        assertEquals(listOf(first.id), db.dao().allRoutinesNow().map { it.id })
        assertEquals(listOf("PLANK"), db.dao().routineExercises(first.id).map { it.activityCode })
        // Another flow, opened at another moment, is another template.
        save(routine.copy(createdAt = routine.createdAt + 1), squat)
        assertEquals(2, db.dao().allRoutinesNow().size)
    }

    /**
     * Two templates, each planned for a day gone by and a day to come, beside a plan Health Connect
     * brought, which names no template, and a session already logged from the first: deleting the
     * first takes its exercises and its own two plans, each leaving the tombstone a deleted row
     * leaves, and nothing else.
     */
    @Test
    fun aDeletedTemplateTakesItsOwnPlansAndNothingElse() = runBlocking {
        val dao = db.dao()
        val legs = dao.insertRoutine(Routine(name = "Legs", createdAt = 1L))
        val arms = dao.insertRoutine(Routine(name = "Arms", createdAt = 2L))
        dao.insertRoutineExercise(squat.copy(routineId = legs))
        dao.insertRoutineExercise(plank.copy(routineId = arms))
        val today = LocalDate.now()
        val plans = listOf(legs, arms).flatMap { id ->
            val routine = checkNotNull(dao.allRoutinesNow().find { it.id == id })
            listOf(today.minusDays(3), today.plusDays(3)).map { day ->
                dao.saveJournalEntry(planEntry(routine, dao.routineExercises(id), day))
            }
        }
        val synced = planEntry(Routine(name = "Synced", createdAt = 3L), emptyList(), today)
        val foreign = dao.saveJournalEntry(synced.copy(measurementPayloadJson = null))
        val logged = dao.saveJournalEntry(
            JournalEntry(
                day = today.toString(),
                occurredAt = 5L,
                kind = KIND_ACTIVITY,
                title = "Squats",
                subtitle = "Legs #1",
                calories = 50.0,
                activityCode = "SQUATS",
            ),
        )

        db.deleteRoutineWithPlans(context, checkNotNull(dao.allRoutinesNow().find { it.id == legs }))

        assertEquals(listOf(arms), dao.allRoutinesNow().map { it.id })
        assertTrue(dao.routineExercises(legs).isEmpty())
        assertEquals(1, dao.routineExercises(arms).size)
        val left = dao.journalAround(KIND_PLAN, Long.MIN_VALUE, Long.MAX_VALUE).map { it.id }.toSet()
        assertEquals(setOf(plans[2], plans[3], foreign), left)
        assertNotNull(dao.journalEntry(logged))
        val tombstones = dao.allTombstones().map { it.recordKey }
        assertTrue(tombstones.containsAll(listOf(journalRecordKey(plans[0]), journalRecordKey(plans[1]))))
        assertFalse(journalRecordKey(plans[2]) in tombstones)
    }

    /**
     * A template changed in the flow: its steps rewritten, a new picture picked, then the picture taken
     * away. The plans point at the template by its id, so the id stays; the rows are the new steps in
     * their order, a superset's number on each of its rows and each step's rest on its last; the date
     * ahead takes the new name; and a picture no row names any more is gone from the files.
     */
    @Test
    fun aChangedTemplateKeepsItsIdAndItsPlansAndOnlyTheNewestPicture() = runBlocking {
        val dao = db.dao()
        val photos = routinePhotoDir(context).apply { mkdirs() }
        val old = File(photos, "1000.jpg").apply { writeBytes(byteArrayOf(1)) }
        val first = save(Routine(name = "Legs", createdAt = 1L, photo = old.name), squat)
        val plan = dao.saveJournalEntry(planEntry(first, dao.routineExercises(first.id), LocalDate.now().plusDays(2)))
        val blocks = listOf(
            RoutineBlock(listOf(press, pull), superset = true, restSeconds = 60),
            RoutineBlock(listOf(plank), restSeconds = 90),
            RoutineBlock(listOf(squat), restSeconds = 30),
        )
        pendingRoutinePhoto(context).writeBytes(byteArrayOf(2))

        val renamed = first.copy(name = "Leg day")
        val changed = db.saveRoutine(context, renamed, before = old.name, pendingPhoto = true, blocks = blocks)

        assertEquals(listOf(first.id), dao.allRoutinesNow().map { it.id })
        val rows = dao.routineExercises(first.id)
        assertEquals(listOf("BENCH_PRESS", "PULLUPS", "PLANK", "SQUATS"), rows.map { it.activityCode })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
        assertEquals(listOf(0, 0, null, null), rows.map { it.superset })
        // The superset's rest on its second row, the plank's after it, and none after the last step.
        assertEquals(listOf(0, 60, 90, 0), rows.map { it.restSeconds })
        val planned = checkNotNull(dao.journalEntry(plan))
        assertEquals("Leg day" to first.id, planned.title to planned.planRoutineId())
        assertFalse(old.exists())
        assertFalse(pendingRoutinePhoto(context).exists())
        assertTrue(isMedicinePhotoName(changed.photo))
        assertEquals(listOf<Byte>(2), File(photos, changed.photo).readBytes().toList())

        db.saveRoutine(context, changed.copy(photo = ""), before = changed.photo, pendingPhoto = false, blocks = blocks)

        assertEquals("", dao.allRoutinesNow().single().photo)
        assertFalse(File(photos, changed.photo).exists())
    }

    /**
     * A session of three exercises as Finish writes it: a bench press done set by set in full, curls
     * cut short at two sets of three, the last of them six reps, and a plank never begun. Every row is
     * the session's — "Push day #1", and the next one "#2" — the press with its reps summed and its
     * weight the sets' mean, the curls with what was planned beside what was done, the plank a row
     * that says it was not done; and each costed as the pass after every save would cost it.
     */
    @Test
    fun aSessionLandsInTheJournalAsItWentUnderItsNameAndNumber() = runBlocking {
        val day = LocalDate.of(2026, 11, 14)
        val lifts = listOf(RoutineSet(10, 60.0, restSeconds = 60), RoutineSet(8, 70.0))
        val bench = press.copy(durationMinutes = routineSetsMinutes(lifts), setsJson = routineSetsJson(lifts))
        val curls = List(3) { RoutineSet(10, 20.0, restSeconds = 60) }
        val curlRow = squat.copy(
            activityCode = "BICEPS_CURL",
            durationMinutes = routineSetsMinutes(curls),
            setsJson = routineSetsJson(curls),
        )
        val short = plannedOutcome(curlRow).copy(done = listOf(SetDone(curls[0], 40, 60), SetDone(curls[1], 30, 0, 6)))
        val outcomes = listOf(plannedOutcome(bench), short, plannedOutcome(plank).copy(done = emptyList()))

        val logged = logSession(context, db, profile, outcomes, day = day, session = "Push day")
        val again = logSession(context, db, profile, listOf(plannedOutcome(bench)), day = day, session = "Push day")

        assertEquals(2 to 1, logged to again)
        val journal = db.dao().journalOnNow(day.toString())
        val first = journal.filter { it.subtitle == "Push day #1" }.sortedBy { it.occurredAt }
        assertEquals(listOf("BENCH_PRESS", "BICEPS_CURL", "PLANK"), first.map { it.activityCode })
        assertEquals(listOf(KIND_ACTIVITY, KIND_ACTIVITY, KIND_NOT_DONE), first.map { it.kind })
        assertEquals(listOf("BENCH_PRESS"), journal.filter { it.subtitle == "Push day #2" }.map { it.activityCode })
        val (pressed, curled) = first
        assertEquals(Triple(2, 18, 65.0), Triple(pressed.sets, pressed.reps, pressed.loadKg))
        assertFalse("plannedReps" in checkNotNull(pressed.measurementPayloadJson))
        val shortfall = JSONObject(checkNotNull(curled.measurementPayloadJson))
        assertEquals(30 to 16, shortfall.getInt("plannedReps") to shortfall.getInt("doneReps"))
        assertEquals(3 to 2, shortfall.getInt("plannedSets") to shortfall.getInt("doneSets"))
        val done = first.take(2)
        val costed = recalculatedActivityTimeline(done, baselineWeightKg = profile.weightKg, profile = profile)
        assertTrue(done.all { it.calories > 0.0 })
        assertEquals(costed.map { it.calories }, done.map { it.calories })
    }

    /**
     * Finish tapped and the page gone at once — a turn of the phone, a tab tapped — cancels the scope
     * the session is written from. Cancelled between its rows, it would leave a session cut short, or
     * none, with its energy and its XP; once begun, it is written whole.
     */
    @Test
    fun aSessionWhoseWriterIsCancelledAtOnceIsStillWrittenWhole() = runBlocking {
        val day = LocalDate.of(2026, 11, 14)
        val outcomes = listOf(squat, plank, press, pull, squat.copy(activityCode = "BICEPS_CURL"))
            .map(::plannedOutcome)

        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            logSession(context, db, profile, outcomes, day = day, session = "Full body")
        }
        writer.cancel()
        writer.join()

        val rows = db.dao().journalOnNow(day.toString()).sortedBy { it.occurredAt }
        assertEquals(
            listOf("SQUATS", "PLANK", "BENCH_PRESS", "PULLUPS", "BICEPS_CURL"),
            rows.map { it.activityCode },
        )
        assertTrue(rows.all { it.kind == KIND_ACTIVITY && it.subtitle == "Full body #1" })
    }
}
