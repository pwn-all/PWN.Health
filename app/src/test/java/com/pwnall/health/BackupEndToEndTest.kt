package com.pwnall.health

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * A whole record through a real export and a real import, for the orchestration no merge test on
 * lists sees: the order the members are read in, a dose following its course and a plan its template
 * to the ids this database gave them, a template's exercises, a walk's fixes, the chats, the
 * favourites and a deleted walk's tombstone — and what a second import, or a file of this very
 * installation's after an edit, does to all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BackupEndToEndTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var source: StepsDb
    private lateinit var target: StepsDb

    @Before
    fun open() {
        source = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    }

    @After
    fun close() {
        source.close()
        target.close()
    }

    private fun entry(kind: String, title: String, at: Long, payload: String) = JournalEntry(
        day = DAY,
        occurredAt = at,
        kind = kind,
        title = title,
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    private fun fix(trackId: Long, at: Long) = TrackPointEntity(
        trackId = trackId,
        timestamp = at,
        lat = 55.75,
        lon = 37.61,
        altitudeM = null,
        accuracyM = null,
        speedMps = null,
        provider = "gps",
        accepted = true,
    )

    /**
     * The source's record: a course and a dose of it, a template with an exercise and a plan of it,
     * a walk with three fixes, a chat, a favourite, and the tombstone of a walk deleted the day before.
     */
    private suspend fun fillSource() {
        val dao = source.dao()
        val course = dao.insertMedication(Medication(name = "Metformin", startDay = DAY, createdAt = 5))
        val routine = dao.insertRoutine(Routine(name = "Legs", createdAt = 7))
        dao.insertRoutineExercise(
            RoutineExercise(routineId = routine, position = 0, activityCode = "SQUAT", durationMinutes = 10.0),
        )
        dao.insertJournalEntries(
            listOf(
                entry(KIND_MED, "Metformin", AT, """{"$MED_COURSE_KEY":$course,"slot":"WAKE"}"""),
                entry(KIND_PLAN, "Legs", AT + MINUTE, """{"$PLAN_ROUTINE_KEY":$routine}"""),
            ),
        )
        val track = dao.insertTrack(TrackEntity(startedAt = AT, endedAt = AT + 3 * MINUTE, points = 3))
        dao.insertPoints((0..2).map { fix(track, AT + it * MINUTE) })
        dao.saveChats(listOf(ChatRow(key = "chat-1", day = DAY, startedAt = AT, updatedAt = AT, payload = "[]")))
        dao.saveFavoriteFoods(listOf(FavoriteFood(key = "oats", title = "Oats", calories = 370.0, createdAt = AT)))
        dao.saveTombstones(listOf(JournalTombstone(routeTombstoneKey(AT - DAY_MS), deletedAt = AT)))
    }

    /** A course and a template of the target's own, so that the source's come back under other ids. */
    private suspend fun fillTarget() {
        target.dao().insertMedication(Medication(name = "Aspirin", startDay = DAY, createdAt = 1))
        target.dao().insertRoutine(Routine(name = "Arms", createdAt = 2))
    }

    private suspend fun exported(): ByteArray =
        ByteArrayOutputStream().also { LocalBackup.export(source, it, null, null, null, SOURCE) }.toByteArray()

    private suspend fun StepsDb.restore(bytes: ByteArray, install: String) =
        LocalBackup.import(this, bytes.inputStream(), null, null, null, install)

    /** Every table this file touches, in the order the DAO lists it. */
    private suspend fun StepsDb.snapshot(): List<Any> {
        val dao = dao()
        val tracks = dao.allTracks()
        return listOf(
            dao.allJournal(),
            dao.allMedications(),
            dao.allRoutinesNow(),
            dao.allRoutineExercisesNow(),
            tracks,
            tracks.flatMap { dao.trackPointsNow(it.id) },
            dao.allChats(),
            dao.allFavoriteFoods(),
            dao.allTombstones(),
        )
    }

    /** That the dose and the plan in [db] point at its own Metformin and Legs, and the exercise is the template's. */
    private suspend fun assertReferencesFollowTheirRows(db: StepsDb) {
        val dao = db.dao()
        val course = dao.allMedications().single { it.name == "Metformin" }
        val routine = dao.allRoutinesNow().single { it.name == "Legs" }
        val journal = dao.allJournal().associateBy(JournalEntry::kind)
        val dose = JSONObject(checkNotNull(journal.getValue(KIND_MED).measurementPayloadJson))
        val plan = JSONObject(checkNotNull(journal.getValue(KIND_PLAN).measurementPayloadJson))

        assertEquals(course.id, dose.getLong(MED_COURSE_KEY))
        assertEquals(routine.id, plan.getLong(PLAN_ROUTINE_KEY))
        val exercises = dao.allRoutineExercisesNow().filter { it.routineId == routine.id }
        assertEquals(listOf("SQUAT"), exercises.map { it.activityCode })
    }

    /**
     * Onto another installation that has a course and a template of its own: every row arrives, and
     * every reference follows its row to the id it was given here, not to whatever held the old one.
     * A second import of the same file changes nothing.
     */
    @Test
    fun aWholeRecordCrossesToAnotherInstallationAndASecondImportAddsNothing() = runBlocking {
        fillSource()
        fillTarget()
        val bytes = exported()

        target.restore(bytes, TARGET)

        val dao = target.dao()
        assertEquals(listOf("Aspirin", "Metformin"), dao.allMedications().map { it.name }.sorted())
        assertEquals(listOf("Arms", "Legs"), dao.allRoutinesNow().map { it.name }.sorted())
        assertReferencesFollowTheirRows(target)
        val walk = dao.allTracks().single()
        assertEquals(AT, walk.startedAt)
        assertEquals(listOf(AT, AT + MINUTE, AT + 2 * MINUTE), dao.trackPointsNow(walk.id).map { it.timestamp })
        assertEquals(source.dao().allChats(), dao.allChats())
        assertEquals(source.dao().allFavoriteFoods(), dao.allFavoriteFoods())
        assertEquals(source.dao().allTombstones(), dao.allTombstones())

        val once = target.snapshot()
        target.restore(bytes, TARGET)

        assertEquals(once, target.snapshot())
    }

    /** This installation's own file, after a course was renamed and a dose corrected: no old copy comes back. */
    @Test
    fun anOwnFileAfterAnEditPutsNoOldCopyBesideTheCorrection() = runBlocking {
        fillSource()
        val bytes = exported()
        val dao = source.dao()
        dao.saveMedication(dao.allMedications().single().copy(name = "Metformin XR"))
        val dose = dao.allJournal().single { it.kind == KIND_MED }
        dao.upsertJournalEntry(dose.copy(title = "Metformin XR", occurredAt = dose.occurredAt + MINUTE))
        val edited = source.snapshot()

        source.restore(bytes, SOURCE)

        assertEquals(edited, source.snapshot())
    }

    /**
     * A file signed with this installation's name — no secret, and a database started over under it
     * hands the same numbers out again — whose course, template and dose sit under ids rows of
     * another kind hold here. Each id is believed only for the row it described: the unrelated rows
     * stay, the file's come in beside them, and the dose and the plan follow the file's own.
     */
    @Test
    fun aFileUnderThisInstallationsNameLosesNoRowToAnUnrelatedOneOfItsId() = runBlocking {
        fillSource()
        fillTarget()
        target.dao().insertJournalEntries(listOf(entry(KIND_WATER, "Вода", AT - MINUTE, "{}")))
        val aspirin = target.dao().allMedications().single()

        target.restore(exported(), SOURCE)

        val dao = target.dao()
        assertEquals(listOf("Aspirin", "Metformin"), dao.allMedications().map { it.name }.sorted())
        assertEquals(aspirin, dao.allMedications().single { it.name == "Aspirin" })
        assertEquals(listOf("Arms", "Legs"), dao.allRoutinesNow().map { it.name }.sorted())
        assertEquals(listOf(KIND_MED, KIND_PLAN, KIND_WATER), dao.allJournal().map { it.kind }.sorted())
        assertReferencesFollowTheirRows(target)
    }

    private companion object {
        const val SOURCE = "end-to-end-source"
        const val TARGET = "end-to-end-target"
        const val DAY = "2026-11-18"
        const val AT = 1_795_000_000_000L
        const val MINUTE = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE
    }
}
