package com.pwnall.health

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The hand-written migrations, executed where every commit is checked.
 *
 * [RoomSchemaTest] reads the migrations as text, and MigrationTest runs them on a device, which is
 * a run somebody has to remember: `check` and CI execute no statement of it. So the chain that
 * carries a whole record from one version to the next runs here on SQLite itself, the build
 * Robolectric carries, through the app's own [StepsDb.builder]: a database made at each exported
 * version from that version's schema file is opened as the current one. Room runs every step in
 * between and then holds each table, column, default, index and foreign key against the entities,
 * and a step that differs fails the open with "Migration didn't properly handle", here and not on
 * the phone of whoever updates first.
 *
 * What stays with the device: the SQLite of a real Android, Room's own helper that also compares
 * each step with the next schema file, and the rows of each single step. Versions 2 to 24 have no
 * exported schema and stay with [RoomSchemaTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MigrationRunTest {
    private val context = RuntimeEnvironment.getApplication()
    private val current = StepsDb.ALL_MIGRATIONS.last().endVersion

    @Test
    fun everyExportedVersionOpensAsTheCurrentOne() {
        val versions = exportedVersions()
        // Every version from the first exported one to the one the last migration reaches.
        assertEquals((versions.first()..current).toList(), versions)
        assertTrue("$versions", versions.size > 20)
        for (version in versions) {
            val name = "from-$version.db"
            createAt(name, version)
            val db = StepsDb.builder(context, name).build()
            try {
                assertEquals(current, db.openHelper.writableDatabase.version)
            } catch (failure: IllegalStateException) {
                throw AssertionError("a database at version $version does not open: ${failure.message}", failure)
            } finally {
                db.close()
            }
        }
    }

    /**
     * The oldest exported record with rows in it, read back through the DAO: the entity's row and
     * not only the table's. 25→26 moves the Health Connect marker out of the note, 26→27 rewrites
     * the profile through a `printf`, 27→28 gives a course its attachments; and every step since
     * that only adds a column leaves the rows written before it reading that column as nothing
     * said: an allergy never asked about, a route that never paused, a template of no supersets.
     */
    @Test
    fun aVersion25RecordComesThroughEveryStepWithItsRows() = runBlocking {
        createAt("aged.db", 25) { db ->
            db.aPersonTheirMealAndTheirCourse()
            db.aWalkAndATemplate()
        }
        val db = StepsDb.builder(context, "aged.db").build()
        try {
            val profile = checkNotNull(db.dao().profileNow()) { "no profile came through the open" }
            assertEquals("1990-12-31", profile.birthDate)
            assertEquals(61.5, profile.weightKg, 0.0)
            val row = db.dao().journalOnNow("2026-09-05").single()
            assertEquals(listOf(HC_MARKER, "", "Eggs"), listOf(row.healthConnectRecordKey, row.note, row.title))
            assertEquals(listOf("Metformin" to ""), db.dao().allMedications().map { it.name to it.attachments })
            // The columns added since, on rows that were there before them.
            assertEquals("", profile.allergies)
            val added = listOf(row.energyScore, row.foodSource, row.editedAt, row.zoneOffsetSeconds)
            assertEquals(listOf(null, null, null, null), added)
            assertEquals(listOf("" to ""), db.dao().allMedications().map { it.contents to it.photo })
            val track = db.dao().allTracks().single()
            assertEquals(
                listOf(4_210.5, "", null, 0L, null),
                listOf(track.distanceMeters, track.name, track.pausedAt, track.pausedMillis, track.zoneOffsetSeconds),
            )
            assertEquals(listOf(false), db.dao().trackPointsNow(track.id).map { it.segmentStart })
            val routine = db.dao().allRoutinesNow().single()
            assertEquals(
                listOf("Legs", "", "", 0),
                listOf(routine.name, routine.description, routine.photo, routine.days),
            )
            val exercise = db.dao().allRoutineExercisesNow().single()
            assertEquals(
                listOf("SQUATS", 3, null, 0, ""),
                exercise.run { listOf(activityCode, sets, superset, restSeconds, setsJson) },
            )
            assertEquals(current, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** Rows as version 25 kept them: the profile by birth year, the Health Connect marker in a note. */
    private fun SQLiteDatabase.aPersonTheirMealAndTheirCourse() {
        insert(
            "profile",
            "id" to 0,
            "sex" to "FEMALE",
            "weightKg" to 61.5,
            "heightCm" to 168,
            "birthYear" to 1990,
            "unitSystem" to "METRIC",
            "activityLevel" to "MODERATE",
            "dailyStepGoal" to 8_000,
            "automaticSleepDetection" to 1,
            "energyGoal" to "MAINTAIN",
            "remindersEnabled" to 1,
        )
        insert(
            "journal",
            "day" to "2026-09-05",
            "occurredAt" to 1_757_000_000_000L,
            "kind" to "FOOD",
            "title" to "Eggs",
            "subtitle" to "boiled",
            "calories" to 155.0,
            "proteinG" to 13.0,
            "fatG" to 11.0,
            "carbsG" to 1.1,
            "servingG" to 100.0,
            "enteredPer100g" to 1,
            "note" to HC_MARKER,
        )
        insert(
            "medications",
            "name" to "Metformin",
            "dose" to "500 mg",
            "schedule" to "DAILY",
            "times" to "08:00,20:00",
            "foodRelation" to "WITH_FOOD",
            "startDay" to "2026-01-05",
            "note" to "",
            "createdAt" to 1_767_600_000_000L,
        )
    }

    /** A route of one point and a template of one exercise, from before a route could pause or a set rest. */
    private fun SQLiteDatabase.aWalkAndATemplate() {
        insert(
            "tracks",
            "id" to 1,
            "startedAt" to 1L,
            "distanceMeters" to 4_210.5,
            "points" to 1,
            "walkingDistanceMeters" to 4_210.5,
            "runningDistanceMeters" to 0.0,
            "vehicleDistanceMeters" to 0.0,
            "stoppedMillis" to 0,
            "elevationGainM" to 0.0,
            "elevationLossM" to 0.0,
            "maxSpeedMps" to 0.0,
        )
        insert(
            "trackpoints",
            "trackId" to 1,
            "timestamp" to 1L,
            "elapsedRealtimeNanos" to 1L,
            "lat" to 55.75,
            "lon" to 37.62,
            "movementType" to "WALKING",
            "cadenceHz" to 1.8,
            "motionStd" to 0.1,
            "environmentSpike" to 0,
            "provider" to "gps",
            "accepted" to 1,
            "filterReason" to "",
        )
        insert("routines", "id" to 1, "name" to "Legs", "createdAt" to 1L)
        insert(
            "routine_exercises",
            "routineId" to 1,
            "position" to 0,
            "activityCode" to "SQUATS",
            "durationMinutes" to 5.0,
            "sets" to 3,
        )
    }

    private fun exportedVersions(): List<Int> = SCHEMAS.listFiles().orEmpty()
        .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()

    /**
     * A database file [name] as Room made it at [version]: the tables and indexes of that version's
     * exported schema, the table Room keeps its identity in, and the version stamp. [fill] writes
     * rows before it is closed.
     */
    private fun createAt(name: String, version: Int, fill: (SQLiteDatabase) -> Unit = {}) {
        val schema = JSONObject(File(SCHEMAS, "$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getJSONArray("entities").objects().forEach { entity ->
                val table = entity.getString("tableName")
                val statements = listOf(entity.getString("createSql")) +
                    entity.optJSONArray("indices")?.objects().orEmpty().map { it.getString("createSql") }
                statements.forEach { db.execSQL(it.replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            (0 until setup.length()).forEach { db.execSQL(setup.getString(it)) }
            fill(db)
            db.version = version
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    /** One row with exactly these columns; anything unnamed keeps the table's default, NULL included. */
    private fun SQLiteDatabase.insert(table: String, vararg columns: Pair<String, Any?>) {
        val names = columns.joinToString { "`${it.first}`" }
        val marks = columns.joinToString { "?" }
        execSQL("INSERT INTO `$table` ($names) VALUES ($marks)", columns.map { it.second }.toTypedArray())
    }

    private companion object {
        val SCHEMAS = File("schemas/com.pwnall.health.StepsDb")
        const val HC_MARKER = "Health Connect:com.google.android.apps.fitness/abc-123"
    }
}
