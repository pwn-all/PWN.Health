package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The one door a picked file goes through, against a real database: what the card promises —
 * opening the same file twice adds nothing — is a question of what the journal already holds, and
 * the pure tests of the markers cannot ask it. Nor can they ask what Health Connect, a hand and the
 * screen-use guess put on the same days, or whether a write that fails halfway leaves half a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class HealthImportDatabaseTest {
    private lateinit var db: StepsDb
    private val zone = ZoneId.of("Europe/Prague")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun local(stamp: String) = LocalDateTime.parse(stamp).atZone(zone).toInstant().toEpochMilli()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun importFile(bytes: ByteArray, name: String = "export.zip") =
        db.importExportedFile({ ByteArrayInputStream(bytes) }, name, zone, imperial = false, titles = testTitles())

    private suspend fun insert(vararg rows: JournalEntry) = db.dao().insertJournalEntries(rows.toList())

    /** In the alphabet's order, which is not a question of when an exercise was filed. */
    private suspend fun titles() = db.dao().allJournal().map(JournalEntry::title).sorted()

    private suspend fun tombstones() = db.dao().allTombstones().map(JournalTombstone::recordKey)

    /** Two days of a Garmin archive: a step count on the first, a resting pulse on each. */
    private val garminDays = zip(
        "DI_CONNECT/DI-Connect-Aggregator/UDSFile_2025-12-01_2025-12-31.json" to
            """
            [{"calendarDate":"2025-12-24","totalSteps":7767,"restingHeartRate":52},
             {"calendarDate":"2025-12-25","totalSteps":0,"currentDayRestingHeartRate":54}]
            """,
    )

    /** Two weighings, by a clock with no zone beside it. */
    private val withingsWeights = """
        Date,"Weight (kg)","Fat mass (kg)","Bone mass (kg)","Muscle mass (kg)","Hydration (kg)",Comments
        "2025-03-30 12:09:03",73.5,19.9,3.7,46.9,30.5,
        "2025-03-31 12:09:03",73.2,19.8,3.7,46.8,30.4,
    """.trimIndent().toByteArray()

    private val hevy = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Bench Press (Barbell)",,"",1,"normal",80,5,,,8
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Pull Up",,"",0,"normal",,8,,,
    """.trimIndent().toByteArray()

    /** A night Fitbit's export states, ending on the 12th of April in Prague. */
    private val fitbitNight = zip(
        "Takeout/Fitbit/Health Fitness Data_GoogleData/UserSleeps_2026-04-01.csv" to
            """
            sleep_id,sleep_type,start_utc_offset,sleep_start,end_utc_offset,sleep_end,data_source,algorithm_version
            1111111111111111111,STAGES,+02:00,2026-04-11 21:30:00+0000,+02:00,2026-04-12 05:15:00+0000,DERIVED,sleep_session_v2
            """,
    )

    private fun night(source: String, marker: String? = null) = JournalEntry(
        day = "2026-04-12",
        occurredAt = at("2026-04-12T05:00:00Z"),
        kind = KIND_MEASUREMENT,
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = at("2026-04-11T21:00:00Z"),
        sleepEndAt = at("2026-04-12T05:00:00Z"),
        measurementSource = source,
        healthConnectRecordKey = marker,
    )

    // ─── the same file twice ────────────────────────────────────────────────────────────────────

    /** The second run finds every row already here; the day's step count is replaced, not added. */
    @Test
    fun theSameArchiveOpenedTwiceAddsNothingTheSecondTime() = runBlocking {
        val first = importFile(garminDays)
        val second = importFile(garminDays)

        assertEquals(HealthExportApp.GARMIN.label, first.label)
        assertEquals(listOf(2, 0, 1), listOf(first.added, first.alreadyHeld, first.stepDays))
        assertEquals(listOf(0, 2, 1), listOf(second.added, second.alreadyHeld, second.stepDays))
        assertEquals(2, db.dao().allJournal().size)
        assertEquals(listOf(7_767L), db.dao().allDirectBleDays().map(DirectBleDaySteps::steps))
    }

    /** A row imported once and then deleted on purpose stays deleted through every later import. */
    @Test
    fun aDeletedImportedRowDoesNotComeBack() = runBlocking {
        importFile(garminDays)
        val deleted = db.dao().allJournal().first()

        db.deleteJournalEvent(deleted.id)
        val again = importFile(garminDays)

        assertEquals(0, again.added)
        assertEquals(2, again.alreadyHeld)
        assertEquals(1, db.dao().allJournal().size)
        assertTrue(checkNotNull(deleted.healthConnectRecordKey) in tombstones())
    }

    /** Two devices stating one weighing at one instant are one row, or the second run would skip both. */
    @Test
    fun oneFactStatedTwiceInTheFileLandsOnce() = runBlocking {
        val weights = zip(
            "Takeout/Fitbit/Physical Activity_GoogleData/weight.csv" to
                """
                timestamp,weight grams,data source
                2026-03-19T18:21:01Z,82400,Fitbit App
                2026-03-19T18:21:01Z,82400,Aria Air
                """,
        )

        val outcome = importFile(weights)

        assertEquals(listOf(1, 1), listOf(outcome.added, outcome.alreadyHeld))
        assertEquals(1, db.dao().allJournal().size)
    }

    /** A history filed before the markers named the wall clock is the same history under its old name. */
    @Test
    fun aRowFiledUnderTheOldInstantMarkerIsAlreadyHere() = runBlocking {
        insert(
            JournalEntry(
                day = "2025-03-30",
                occurredAt = local("2025-03-30T12:09:03"),
                kind = KIND_MEASUREMENT,
                title = "Body measurement",
                calories = 0.0,
                measurementWeightKg = 73.5,
                measurementSource = HealthExportApp.WITHINGS.label,
                healthConnectRecordKey = "File import:Withings:weight:${local("2025-03-30T12:09:03")}",
            ),
        )

        val outcome = importFile(withingsWeights, "weight.csv")

        assertEquals(HealthExportApp.WITHINGS.label, outcome.label)
        assertEquals(1, outcome.added)
        assertEquals(1, outcome.alreadyHeld)
        assertEquals(listOf("2025-03-30", "2025-03-31"), db.dao().allJournal().map(JournalEntry::day))
    }

    /**
     * A table of sets goes to the set reader and lands once, also against a row filed under the
     * instant the session started at; a second run adds nothing.
     */
    @Test
    fun aTrainingLogIsReadAsOneAndIsIdempotentToo() = runBlocking {
        val started = local("2025-12-05T11:37:00")
        insert(
            JournalEntry(
                day = "2025-12-05",
                occurredAt = started,
                kind = KIND_ACTIVITY,
                title = "Bench Press (Barbell)",
                calories = 0.0,
                measurementSource = WorkoutExportApp.HEVY.label,
                healthConnectRecordKey = "${FILE_IMPORT_MARKER_PREFIX}Hevy:$started:Bench Press (Barbell)",
            ),
        )

        val first = importFile(hevy, "workouts.csv")
        val second = importFile(hevy, "workouts.csv")

        assertEquals(WorkoutExportApp.HEVY.label, first.label)
        assertEquals(listOf(1, 1, 0), listOf(first.added, first.alreadyHeld, first.stepDays))
        assertEquals(listOf(0, 2), listOf(second.added, second.alreadyHeld))
        assertEquals(listOf("Bench Press (Barbell)", "Pull Up"), titles())
    }

    /** A write that fails halfway leaves nothing of the file: neither its rows nor its step days. */
    @Test
    fun anImportThatFailsHalfwayLeavesNothingBehind() = runBlocking {
        // The step days are written after the rows, in the same transaction; refusing them fails the run there.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_steps BEFORE INSERT ON direct_ble_step_days " +
                "BEGIN SELECT RAISE(ABORT, 'disk full'); END",
        )

        val run = runCatching { importFile(garminDays) }

        assertTrue(run.isFailure)
        assertEquals(emptyList<JournalEntry>(), db.dao().allJournal())
        assertEquals(emptyList<DirectBleDaySteps>(), db.dao().allDirectBleDays())
    }

    // ─── what other roads brought ───────────────────────────────────────────────────────────────

    /** A night Health Connect already brought from the watch's own app is not written a second time. */
    @Test
    fun aMeasuredNightAlreadyHereKeepsItsDay() = runBlocking {
        insert(night("HEALTH_CONNECT", "Health Connect:com.fitbit.FitbitMobile:x"))

        val outcome = importFile(fitbitNight)

        assertEquals(0, outcome.added)
        assertEquals(1, outcome.alreadyHeld)
        assertEquals(listOf("HEALTH_CONNECT"), db.dao().sleepOn("2026-04-12").map(JournalEntry::measurementSource))
    }

    /** A night guessed from screen use gives way to the measured one, and leaves its tombstone. */
    @Test
    fun aGuessedNightGivesWayToTheImportedOne() = runBlocking {
        insert(night("PHONE_USAGE_ESTIMATE"))
        val guess = db.dao().allJournal().single()

        val outcome = importFile(fitbitNight)

        assertEquals(1, outcome.added)
        assertEquals(
            listOf(HealthExportApp.FITBIT.label),
            db.dao().sleepOn("2026-04-12").map(JournalEntry::measurementSource),
        )
        assertTrue(journalRecordKey(guess.id) in tombstones())
    }

    /** A day holds one period start: the one tapped on the cycle screen stays, and the file adds the end. */
    @Test
    fun aPeriodStartAlreadyOnTheDayIsNotAddedAgain() = runBlocking {
        insert(
            JournalEntry(
                day = "2026-03-01",
                occurredAt = local("2026-03-01T09:00:00"),
                kind = KIND_CYCLE,
                title = "Period",
                calories = 0.0,
            ),
        )
        val cycles = zip(
            "Takeout/Fitbit/Menstrual Health/menstrual_health_cycles.csv" to
                """
                id,cycle_start_date,cycle_end_date,ovulation_start_date,ovulation_end_date,ovulation_source,period_start_date,period_end_date,period_source,fertile_start_date,fertile_end_date,fertile_source
                1,2026-03-01,2026-03-28,,,,2026-03-01,2026-03-05,manual,,,
                """,
        )

        val outcome = importFile(cycles)

        assertEquals(listOf(1, 1), listOf(outcome.added, outcome.alreadyHeld))
        assertEquals(
            listOf(KIND_CYCLE to "2026-03-01", KIND_CYCLE_END to "2026-03-05"),
            db.dao().cycleMarksNow().map { it.kind to it.day },
        )
    }

    /** A weighing day the scales' own app already published is left to it; the day after is the file's. */
    @Test
    fun aDayHealthConnectBroughtFromTheSameServiceIsLeftToIt() = runBlocking {
        insert(
            JournalEntry(
                day = "2025-03-30",
                occurredAt = local("2025-03-30T12:09:00"),
                kind = KIND_MEASUREMENT,
                title = "Weight",
                calories = 0.0,
                measurementWeightKg = 73.5,
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = "Health Connect:com.withings.wiscale2:abc",
            ),
        )

        val outcome = importFile(withingsWeights, "weight.csv")

        assertEquals(listOf(1, 1), listOf(outcome.added, outcome.alreadyHeld))
        assertEquals(1, db.dao().allJournal().count { it.measurementSource == HealthExportApp.WITHINGS.label })
    }

    /** The logger's own session for a day the file states set by set gives way to the sets. */
    @Test
    fun aLoggersPublishedSessionGivesWayToItsSets() = runBlocking {
        insert(
            JournalEntry(
                day = "2025-12-05",
                occurredAt = at("2025-12-05T10:37:00Z"),
                kind = KIND_ACTIVITY,
                title = "Upper A",
                calories = 300.0,
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = "Health Connect:com.hevy:s1",
            ),
        )

        importFile(hevy, "workouts.csv")

        assertEquals(listOf("Bench Press (Barbell)", "Pull Up"), titles())
        assertTrue("Health Connect:com.hevy:s1" in tombstones())
    }

    /**
     * A feed's deletions are matched together against one read of the journal: every row each
     * deleted record was imported into, a period's end hung off its marker among them, and none that
     * was corrected by hand, adopted, read out of a file or imported from a record still standing.
     */
    @Test
    fun theRowsOfAFeedsDeletionsAreFoundInOneRead() = runBlocking {
        val dao = db.dao()
        suspend fun row(marker: String?, editedAt: Long? = null) =
            dao.saveJournalEntry(night("HEALTH_CONNECT", marker).copy(editedAt = editedAt))
        val weighing = row("Health Connect:com.example.scale:r1")
        val periodEnd = row("Health Connect:com.example.cycle:r2#end")
        row("Health Connect:com.example.scale:r3")
        row("Health Connect:com.example.scale:r1", editedAt = 1L)
        row(null)
        row("Garmin Connect:r1")

        assertEquals(listOf(weighing, periodEnd), dao.importedRowsOf(setOf("r1", "r2")).sorted())
    }
}
