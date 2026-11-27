package com.pwnall.health

import android.content.Context
import android.database.Cursor
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getStringOrNull
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hand-written migrations, executed.
 *
 * [RoomSchemaTest] reads the migration SQL and the exported schema and refuses a column that is in
 * one and not the other, which is what a JVM can do. What it cannot do is run one. A migration that
 * spells a type, a NOT NULL, a default, a primary key or an index differently from the entity opens
 * as "Migration didn't properly handle" on every phone that upgrades and on no fresh install —
 * which is what the developer, CI and the pre-launch report all are. Room's [MigrationTestHelper]
 * is the one thing that catches it: it builds a database at the old version from the exported
 * schema, runs the migration under test, and compares every table, column, index and foreign key
 * of the result with the schema of the new version.
 *
 * Only the steps from 25 on can be tested this way: there is no exported schema for versions 2
 * through 24, and a schema file written after the fact from what the migrations produce would only
 * prove the migrations agree with themselves. Those twenty-three steps stay with [RoomSchemaTest].
 *
 * A step that moves or rewrites what is stored gets a test of its own with rows in the tables it
 * touches, so a migration that keeps the shape but loses or bends the data — 26→27 rewrites the
 * whole profile table through a `printf` — fails on the row and not only on the validator. The
 * steps that only add a column or a table share one test, which holds each to the schema of the
 * version it reaches. Then the first ten as one chain, and last the app's own builder over a
 * database aged to 25, which is the path a phone takes.
 *
 * None of this runs in `check` or in CI: it needs a device, and `connectedSideloadDebugAndroidTest`
 * before a release is the release checklist's. What `check` does run is MigrationRunTest in the
 * unit tests, which opens a database of every exported version through the same builder on the
 * SQLite Robolectric carries, and reads a version-25 record's rows back at the end of the chain.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), StepsDb::class.java)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun from25To26TheHealthConnectMarkerLeavesTheNoteForTheKeyColumn() {
        val db = helper.createDatabase(NAME, 25)
        db.insertJournalRow(note = HC_MARKER)
        db.insertJournalRow(note = "$HC_MARKER felt fine")
        db.insertJournalRow(note = "Two eggs")
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 26, true, StepsDb.MIGRATION_25_26)

        val rows = migrated.rows("SELECT `healthConnectRecordKey`, `note`, `caffeineMg` FROM `journal` ORDER BY `id`") {
            Triple(it.getStringOrNull(0), it.getString(1), it.getDoubleOrNull(2))
        }
        assertEquals(
            listOf(
                // After the step a bare marker is identity, and the note it occupied is given back.
                Triple(HC_MARKER, "", null),
                // A marker somebody wrote after is still identity, but the writing is theirs to keep.
                Triple("$HC_MARKER felt fine", "$HC_MARKER felt fine", null),
                Triple(null, "Two eggs", null),
            ),
            rows,
        )
    }

    @Test
    fun from26To27AStoredBirthYearBecomesTheLastDayOfThatYear() {
        val db = helper.createDatabase(NAME, 26)
        db.insertProfileBornIn(1990)
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 27, true, StepsDb.MIGRATION_26_27)

        val (birthDate, weightKg, energyGoal) =
            migrated.one("SELECT `birthDate`, `weightKg`, `energyGoal` FROM `profile` WHERE `id` = 0") {
                Triple(it.getString(0), it.getDouble(1), it.getString(2))
            }
        assertEquals("1990-12-31", birthDate)
        assertEquals(61.5, weightKg, 0.0)
        assertEquals("MAINTAIN", energyGoal)
        assertFalse("the renamed copy has to go", migrated.has("table", "profile_old"))
    }

    @Test
    fun from27To28MedicationsAndAppointmentsGainEmptyAttachments() {
        val db = helper.createDatabase(NAME, 27)
        db.insertMedication()
        db.insert(
            "appointments",
            "specialty" to "Cardiology",
            "doctor" to "Dr Rao",
            "place" to "Room 4",
            "atMs" to 1_770_000_000_000L,
            "note" to "bring the ECG",
            "remindDaysBefore" to 1,
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 28, true, StepsDb.MIGRATION_27_28)

        val nameAndAttachments = { row: Cursor -> row.getString(0) to row.getString(1) }
        assertEquals(
            "Metformin" to "",
            migrated.one("SELECT `name`, `attachments` FROM `medications`", nameAndAttachments),
        )
        assertEquals(
            "Cardiology" to "",
            migrated.one("SELECT `specialty`, `attachments` FROM `appointments`", nameAndAttachments),
        )
    }

    @Test
    fun from28To29TheFavouriteFoodsTableArrivesAndTakesARow() {
        helper.createDatabase(NAME, 28).close()

        val migrated = helper.runMigrationsAndValidate(NAME, 29, true, StepsDb.MIGRATION_28_29)

        migrated.insert(
            "favorite_foods",
            "key" to "usda:11090",
            "title" to "Broccoli",
            "subtitle" to "raw",
            "servingG" to 91.0,
            "calories" to 31.0,
            "proteinG" to 2.6,
            "fatG" to 0.3,
            "carbsG" to 6.0,
            "createdAt" to 1L,
        )
        assertEquals(
            "Broccoli",
            migrated.one("SELECT `title` FROM `favorite_foods` WHERE `key` = 'usda:11090'") { it.getString(0) },
        )
    }

    @Test
    fun from29To30TheSavedDishesTableArrivesAndTakesARow() {
        helper.createDatabase(NAME, 29).close()

        val migrated = helper.runMigrationsAndValidate(NAME, 30, true, StepsDb.MIGRATION_29_30)

        migrated.insert("saved_dishes", "key" to "dish:1", "name" to "Porridge", "items" to "[]", "createdAt" to 1L)
        assertEquals("Porridge", migrated.one("SELECT `name` FROM `saved_dishes`") { it.getString(0) })
    }

    @Test
    fun from30To31TheProgressPhotosTableArrivesWithItsIndex() {
        helper.createDatabase(NAME, 30).close()

        val migrated = helper.runMigrationsAndValidate(NAME, 31, true, StepsDb.MIGRATION_30_31)

        assertTrue(migrated.has("table", "progress_photos"))
        assertTrue(migrated.has("index", "index_progress_photos_day"))
        migrated.insert("progress_photos", "day" to "2026-09-05", "takenAt" to 1L, "file" to "photos/1.jpg")
        val id = migrated.one("SELECT `id` FROM `progress_photos` WHERE `day` = '2026-09-05'") { it.getLong(0) }
        assertEquals(1L, id)
    }

    @Test
    fun from31To32AnExistingTrackPointReadsAnAltitudeDatumOfZero() {
        val db = helper.createDatabase(NAME, 31)
        db.insertTrackWithOnePoint()
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 32, true, StepsDb.MIGRATION_31_32)

        val (lat, altitudeMsl) =
            migrated.one("SELECT `lat`, `altitudeMsl` FROM `trackpoints`") { it.getDouble(0) to it.getLong(1) }
        assertEquals(55.75, lat, 0.0)
        assertEquals(0L, altitudeMsl)
    }

    /**
     * An index is not a column, so nothing about the row changes; what has to be true afterwards is
     * that the key every import asks by is indexed, that the note it moves out of is not,
     * and that the row is still there to be asked about.
     */
    @Test
    fun from32To33TheImportKeyIsIndexedAndTheNoteIsNot() {
        val db = helper.createDatabase(NAME, 32)
        db.insertJournalRow(note = "Two eggs")
        db.execSQL("UPDATE `journal` SET `healthConnectRecordKey` = ?", arrayOf(HC_MARKER))
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 33, true, StepsDb.MIGRATION_32_33)

        assertTrue(
            "the key both idempotency probes read is still unindexed",
            migrated.has("index", "index_journal_healthConnectRecordKey"),
        )
        assertFalse(
            "no query has named the note since the marker left it at 25→26",
            migrated.has("index", "index_journal_note"),
        )
        assertEquals(
            HC_MARKER to "Two eggs",
            migrated.one("SELECT `healthConnectRecordKey`, `note` FROM `journal`") {
                it.getString(0) to it.getString(1)
            },
        )
    }

    /**
     * The one new table, and the old one beside it untouched: the day's deduplicated total is what
     * the step arithmetic reads, and this migration must not have moved it.
     */
    @Test
    fun from33To34TheStepOriginTableArrivesEmptyBesideTheTotals() {
        val db = helper.createDatabase(NAME, 33)
        db.insert("external_step_days", "day" to "2026-09-05", "steps" to 9_000L)
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 34, true, StepsDb.MIGRATION_33_34)

        assertTrue("the origin table never arrived", migrated.has("table", "external_step_origin_days"))
        assertEquals(
            0L,
            migrated.one("SELECT COUNT(*) FROM `external_step_origin_days`") { it.getLong(0) },
        )
        assertEquals(9_000L, migrated.one("SELECT `steps` FROM `external_step_days`") { it.getLong(0) })
    }

    @Test
    fun from34To35OpeningHistoryStartsEmptyAndKeepsExistingData() {
        val db = helper.createDatabase(NAME, 34)
        db.insertJournalRow(note = "meal")
        db.close()
        val migrated = helper.runMigrationsAndValidate(NAME, 35, true, StepsDb.MIGRATION_34_35)
        assertEquals(0L, migrated.one("SELECT COUNT(*) FROM app_open_days") { it.getLong(0) })
        assertEquals(1L, migrated.one("SELECT COUNT(*) FROM journal") { it.getLong(0) })
        migrated.execSQL("INSERT OR IGNORE INTO app_open_days(day) VALUES ('2026-09-11')")
        migrated.execSQL("INSERT OR IGNORE INTO app_open_days(day) VALUES ('2026-09-11')")
        assertEquals(1L, migrated.one("SELECT COUNT(*) FROM app_open_days") { it.getLong(0) })
    }

    /** A course from before the photograph keeps every field and comes through with none. */
    @Test
    fun from39To40ACourseKeepsItsFieldsAndHasNoPhotograph() {
        val db = helper.createDatabase(NAME, 39)
        db.insert(
            "medications",
            "name" to "Metformin",
            "dose" to "500 mg",
            "schedule" to "CLOCK",
            "times" to "480",
            "foodRelation" to "WITH_FOOD",
            "startDay" to "2026-01-05",
            "note" to "",
            "attachments" to "",
            "contents" to "",
            "createdAt" to 1_767_600_000_000L,
        )
        db.close()
        val migrated = helper.runMigrationsAndValidate(NAME, 40, true, StepsDb.MIGRATION_39_40)
        assertEquals(
            "Metformin" to "",
            migrated.one("SELECT `name`, `photo` FROM `medications`") { it.getString(0) to it.getString(1) },
        )
    }

    /** The chats arrive empty beside a journal left as it was, and take a row as the entity writes it. */
    @Test
    fun from40To41TheChatsTableArrivesAndTakesARow() {
        val db = helper.createDatabase(NAME, 40)
        db.insertJournalRow(note = "meal")
        db.close()
        val migrated = helper.runMigrationsAndValidate(NAME, 41, true, StepsDb.MIGRATION_40_41)
        assertEquals(0L, migrated.one("SELECT COUNT(*) FROM chats") { it.getLong(0) })
        assertEquals("meal", migrated.one("SELECT `note` FROM `journal`") { it.getString(0) })
        migrated.insert(
            "chats",
            "key" to "0f8c2c1e",
            "day" to "2026-10-05",
            "startedAt" to 1_791_187_200_000L,
            "updatedAt" to 1_791_187_260_000L,
            "payload" to """{"schema":1,"rev":1,"threads":[],"next":0}""",
        )
        assertEquals(
            Triple("0f8c2c1e", "2026-10-05", """{"schema":1,"rev":1,"threads":[],"next":0}"""),
            migrated.one("SELECT `key`, `day`, `payload` FROM `chats`") {
                Triple(it.getString(0), it.getString(1), it.getString(2))
            },
        )
    }

    /**
     * A route recorded before walks had names keeps every figure and reads an empty name, which the
     * walk page fills by id; the photographs arrive with their index, take a row as the entity
     * writes it, and go when their route goes.
     */
    @Test
    fun from41To42ARouteKeepsItsFiguresAndItsPhotographsGoWithIt() {
        val db = helper.createDatabase(NAME, 41)
        db.insert(
            "tracks",
            "id" to 1,
            "startedAt" to 1_791_187_200_000L,
            "endedAt" to 1_791_190_800_000L,
            "distanceMeters" to 4_210.5,
            "points" to 1_650,
            "walkingDistanceMeters" to 4_000.0,
            "runningDistanceMeters" to 210.5,
            "vehicleDistanceMeters" to 0.0,
            "stoppedMillis" to 120_000L,
            "elevationGainM" to 38.0,
            "elevationLossM" to 36.5,
            "maxSpeedMps" to 3.4,
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 42, true, StepsDb.MIGRATION_41_42)

        assertEquals(
            listOf(1_791_190_800_000L, 4_210.5, 1_650L, ""),
            migrated.one("SELECT `endedAt`, `distanceMeters`, `points`, `name` FROM `tracks`") {
                listOf(it.getLong(0), it.getDouble(1), it.getLong(2), it.getString(3))
            },
        )
        assertTrue(migrated.has("index", "index_track_photos_trackId_takenAt"))
        migrated.insert(
            "track_photos",
            "trackId" to 1,
            "takenAt" to 1_791_188_000_000L,
            "file" to "1791188000000.jpg",
            "lat" to 55.751244,
            "lon" to 37.618423,
            "bearingDeg" to 271.5,
            "pitchDeg" to -7.25,
            "rollDeg" to 90.0,
        )
        assertEquals(
            listOf("1791188000000.jpg", 271.5, null),
            migrated.one("SELECT `file`, `bearingDeg`, `accuracyM` FROM `track_photos`") {
                listOf(it.getString(0), it.getDouble(1), it.getDoubleOrNull(2))
            },
        )
        // Foreign keys hold per connection: the app's own open turns them on, and so does this one.
        migrated.setForeignKeyConstraintsEnabled(true)
        migrated.execSQL("DELETE FROM `tracks` WHERE `id` = 1")
        assertEquals(0L, migrated.one("SELECT COUNT(*) FROM `track_photos`") { it.getLong(0) })
    }

    /**
     * A line of the medical history written before its answer had a moment of its own was last
     * saved when it was answered, as far as anything knows: the new column starts as the old one.
     * The journal gains the offset of the clock a row was written on, which no earlier row states.
     */
    @Test
    fun from46To47AHistoryLineWasAnsweredWhenItWasLastSaved() {
        val db = helper.createDatabase(NAME, 46)
        db.insert(
            "medical_history",
            "key" to "a-line",
            "kind" to "CONTRACEPTION",
            "title" to "NONE",
            "detail" to "",
            "startDay" to "",
            "endDay" to "",
            "note" to "",
            "updatedAt" to 1_791_187_200_000L,
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 47, true, StepsDb.MIGRATION_46_47)

        assertEquals(
            listOf(1_791_187_200_000L, 1_791_187_200_000L),
            migrated.one("SELECT `updatedAt`, `answeredAt` FROM `medical_history`") {
                listOf(it.getLong(0), it.getLong(1))
            },
        )
        assertEquals(0L, migrated.one("SELECT COUNT(`zoneOffsetSeconds`) FROM `journal`") { it.getLong(0) })
    }

    /**
     * The steps with no row to move: each adds a column with its default, or a table. What can go
     * wrong in one is the shape — a type, a NOT NULL or a default spelled differently from the
     * entity — and the helper holds each against the schema exported for the version it reaches.
     * A row written before them reads the new columns as nothing said; MigrationRunTest reads that
     * off a version-25 record in the unit tests.
     */
    @Test
    fun theStepsThatOnlyAddAColumnOrATableReachTheSchemaOfTheirVersion() {
        val adding = listOf(
            StepsDb.MIGRATION_35_36,
            StepsDb.MIGRATION_36_37,
            StepsDb.MIGRATION_37_38,
            StepsDb.MIGRATION_38_39,
            StepsDb.MIGRATION_42_43,
            StepsDb.MIGRATION_43_44,
            StepsDb.MIGRATION_44_45,
            StepsDb.MIGRATION_45_46,
            StepsDb.MIGRATION_47_48,
        )
        adding.forEach { step ->
            // createDatabase deletes the file of the step before, which is closed by then.
            helper.createDatabase(NAME, step.startVersion).close()
            helper.runMigrationsAndValidate(NAME, step.endVersion, true, step).close()
        }
    }

    @Test
    fun theTenStepsFrom25To35RunAsOneChainAndKeepTheRows() {
        val db = helper.createDatabase(NAME, 25)
        db.insertJournalRow(note = HC_MARKER)
        db.insertProfileBornIn(1990)
        db.insertMedication()
        db.insertTrackWithOnePoint()
        db.close()

        val migrated = helper.runMigrationsAndValidate(NAME, 35, true, *STEPS_FROM_25)

        assertEquals("1990-12-31", migrated.one("SELECT `birthDate` FROM `profile`") { it.getString(0) })
        assertEquals(HC_MARKER, migrated.one("SELECT `healthConnectRecordKey` FROM `journal`") { it.getString(0) })
        assertTrue("the import key never got its index", migrated.has("index", "index_journal_healthConnectRecordKey"))
        assertEquals("", migrated.one("SELECT `attachments` FROM `medications`") { it.getString(0) })
        assertEquals(0L, migrated.one("SELECT `altitudeMsl` FROM `trackpoints`") { it.getLong(0) })
        listOf(
            "favorite_foods",
            "saved_dishes",
            "progress_photos",
            "external_step_origin_days",
            "app_open_days",
        ).forEach {
            assertTrue("$it never arrived", migrated.has("table", it))
        }
    }

    /**
     * Not the helper's open but the app's: [StepsDb.builder] with every migration it registers, the
     * quarantine factory and the destructive fallback for pre-2 files, over a database aged to 25.
     * Read back through the DAO, because that is what proves the row is the entity's and not only
     * the table's.
     */
    @Test
    fun theAppsOwnBuilderCarriesAVersion25DatabaseToTheCurrentOne() {
        val db = helper.createDatabase(NAME, 25)
        db.insertProfileBornIn(1990)
        db.close()

        val opened = StepsDb.builder(context, NAME).build()
        helper.closeWhenFinished(opened)

        val profile = runBlocking { opened.dao().profileNow() } ?: error("no profile came through the open")
        assertEquals("1990-12-31", profile.birthDate)
        assertEquals(61.5, profile.weightKg, 0.0)
        // The version the last registered migration reaches, so this cannot go stale with the next one.
        assertEquals(StepsDb.ALL_MIGRATIONS.last().endVersion, opened.openHelper.readableDatabase.version)
    }

    private fun SupportSQLiteDatabase.insertJournalRow(note: String) = insert(
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
        "note" to note,
    )

    private fun SupportSQLiteDatabase.insertProfileBornIn(year: Int) = insert(
        "profile",
        "id" to 0,
        "sex" to "FEMALE",
        "weightKg" to 61.5,
        "heightCm" to 168,
        "birthYear" to year,
        "unitSystem" to "METRIC",
        "activityLevel" to "MODERATE",
        "dailyStepGoal" to 8_000,
        "automaticSleepDetection" to 1,
        "energyGoal" to "MAINTAIN",
        "remindersEnabled" to 1,
    )

    private fun SupportSQLiteDatabase.insertMedication() = insert(
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

    /** The parent row too: trackpoints carries a foreign key to tracks, and the point must be somebody's. */
    private fun SupportSQLiteDatabase.insertTrackWithOnePoint() {
        insert(
            "tracks",
            "id" to 1,
            "startedAt" to 1L,
            "distanceMeters" to 0.0,
            "points" to 1,
            "walkingDistanceMeters" to 0.0,
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
    }

    private companion object {
        /** Its own file, never `steps.db`: the app under test keeps whatever it has there. */
        const val NAME = "migration-test.db"
        const val HC_MARKER = "Health Connect:com.google.android.apps.fitness/abc-123"

        val STEPS_FROM_25: Array<Migration> = arrayOf(
            StepsDb.MIGRATION_25_26,
            StepsDb.MIGRATION_26_27,
            StepsDb.MIGRATION_27_28,
            StepsDb.MIGRATION_28_29,
            StepsDb.MIGRATION_29_30,
            StepsDb.MIGRATION_30_31,
            StepsDb.MIGRATION_31_32,
            StepsDb.MIGRATION_32_33,
            StepsDb.MIGRATION_33_34,
            StepsDb.MIGRATION_34_35,
        )
    }
}

/** One row with exactly these columns; anything unnamed keeps the table's default, NULL included. */
private fun SupportSQLiteDatabase.insert(table: String, vararg columns: Pair<String, Any?>) {
    val names = columns.joinToString { "`${it.first}`" }
    val marks = columns.joinToString { "?" }
    execSQL("INSERT INTO `$table` ($names) VALUES ($marks)", columns.map { it.second }.toTypedArray())
}

internal fun <T> SupportSQLiteDatabase.rows(sql: String, read: (Cursor) -> T): List<T> = query(sql).use { cursor ->
    buildList { while (cursor.moveToNext()) add(read(cursor)) }
}

/** The one row a query returns; none or a second is a failure with the query in it, not a silent pick. */
private fun <T> SupportSQLiteDatabase.one(sql: String, read: (Cursor) -> T): T {
    val found = rows(sql, read)
    assertEquals("rows for $sql", 1, found.size)
    return found.single()
}

private fun SupportSQLiteDatabase.has(type: String, name: String): Boolean =
    rows("SELECT `name` FROM `sqlite_master` WHERE `type` = '$type' AND `name` = '$name'") { it.getString(0) }
        .isNotEmpty()
