package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The file is whatever somebody picked. A damaged cell costs its own figure or its own row and not
 * the import, a file too large for the heap is not read whole to find out it is the wrong kind, and
 * what was not read is said rather than passed over.
 */
class ImportTrustBoundaryTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val now = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun rows(vararg records: HealthRecord) =
        HealthImportFile(HealthExportApp.CRONOMETER, records.toList()).rows(zone, testTitles(), now)

    private val hevyHeader = "\"title\",\"start_time\",\"end_time\",\"description\",\"exercise_title\"," +
        "\"superset_id\",\"exercise_notes\",\"set_index\",\"set_type\",\"weight_kg\",\"reps\",\"distance_km\"," +
        "\"duration_seconds\",\"rpe\""

    // ─── figures and moments ────────────────────────────────────────────────────────────────────

    /** `NaN` in the calories column costs that figure and not the import; `1e999` stays out of the day's total. */
    @Test
    fun aCellThatIsNoNumberCostsItsOwnFigure() {
        val servings = """
            Day,Time,Group,Food Name,Amount,Energy (kcal),Protein (g)
            2026-03-01,08:00,Breakfast,Porridge,100.00 g,NaN,1e999
            2026-03-01,13:00,Lunch,Soup,300.00 g,210,9
        """.trimIndent()
        val file = readHealthExport(servings.toByteArray(), "servings.csv", zone, imperial = false)!!

        val meals = file.rows(zone, testTitles(), now).entries

        assertEquals(listOf("Porridge", "Soup"), meals.map(JournalEntry::title))
        assertEquals(listOf(0.0, 210.0), meals.map(JournalEntry::calories))
        assertEquals(listOf(0.0, 9.0), meals.map(JournalEntry::proteinG))
    }

    /** A stamp from no history — the 1970 a nought becomes, a year nobody will see — is no row. */
    @Test
    fun aMomentNoHistoryHoldsIsNotARow() {
        val kept = WeightRecord(at("2026-03-01T07:00:00Z"), 82.0)

        val landed = rows(
            kept,
            WeightRecord(1_000L, 82.0),
            WeightRecord(Long.MAX_VALUE, 82.0),
            WeightRecord(now + 3 * 86_400_000L, 82.0),
            WeightRecord(at("2026-03-02T07:00:00Z"), Double.NaN),
            SleepRecord(1_000L, at("2026-03-02T05:30:00Z")),
            StepDayRecord(LocalDate.parse("1970-01-01"), 4_000),
            StepDayRecord(LocalDate.parse("2026-03-01"), Long.MAX_VALUE),
            StepDayRecord(LocalDate.parse("2026-03-02"), 4_000),
        )

        assertEquals(listOf(kept.atMs), landed.entries.map(JournalEntry::occurredAt))
        // The three weighings dated outside any history are counted, for the import to say so.
        assertEquals(3, landed.undated)
        assertEquals(listOf("2026-03-02"), landed.stepDays.map(DirectBleDaySteps::day))
    }

    /** A quote that never closes makes one cell of the rest of a file, and that cell may be the row's name. */
    @Test
    fun aNameIsCutBeforeItIsATitleAndAMarker() {
        val endless = "x".repeat(5_000)

        val meal = rows(NutritionRecord(at("2026-03-01T07:00:00Z"), endless, endless, 100.0)).entries.single()
        val session = rows(WorkoutRecord(at("2026-03-01T07:00:00Z"), endless, 600)).entries.single()

        assertEquals(IMPORT_TEXT_LIMIT, meal.title.length)
        assertEquals(IMPORT_TEXT_LIMIT, session.title.length)
        assertTrue(meal.healthConnectRecordKey!!.length < 3 * IMPORT_TEXT_LIMIT)
        assertTrue(session.healthConnectRecordKey!!.length < 2 * IMPORT_TEXT_LIMIT)
    }

    /** A length, a pulse or an energy no session has is read as not stated; the session still lands. */
    @Test
    fun aSessionKeepsOnlyTheFiguresASessionCanHave() {
        val row = rows(
            WorkoutRecord(
                startMs = at("2026-03-01T07:00:00Z"),
                name = "Run",
                seconds = Int.MAX_VALUE,
                distanceM = Double.POSITIVE_INFINITY,
                calories = Double.NaN,
                heartRateBpm = Int.MAX_VALUE,
            ),
        ).entries.single()

        assertNull(row.durationMinutes)
        assertNull(row.measurementHeartRateBpm)
        assertEquals("", row.subtitle)
        assertEquals(0.0, row.calories, 0.0)
        // No energy stated, so the recalculation estimates it as it does for any such session.
        assertNotNull(row.met)
    }

    /** The same for a table of sets: `1e999` reps is the largest an Int holds once it is a number. */
    @Test
    fun aSetKeepsOnlyTheFiguresASetCanHave() {
        val hevy = listOf(
            hevyHeader,
            "Push,\"5 Dec 2025, 11:37\",\"5 Dec 2025, 12:40\",,Bench Press,,,0,normal,80,1e999,,,",
            "Push,\"5 Dec 2025, 11:37\",\"5 Dec 2025, 12:40\",,Bench Press,,,1,normal,Infinity,8,,,",
        ).joinToString("\n")

        val file = readWorkoutExport(hevy, zone, imperial = false)!!
        val row = importedJournalEntries(file, zone, imperial = false, weightTitle = "Weight").single()

        assertEquals(2, row.sets)
        assertEquals(8, row.reps)
        assertEquals(80.0, row.loadKg!!, 0.001)
    }

    // ─── what was not read ──────────────────────────────────────────────────────────────────────

    /** A file whose dates no shape reads is counted as rows lost, not reported as a history with nothing in it. */
    @Test
    fun rowsLostToTheirDatesAreCounted() {
        val lost = (0..3).map { "Push,the fifth of December,later that day,,Bench Press,,,$it,normal,80,5,,," }
        val kept = "Push,\"5 Dec 2025, 11:37\",later that day,,Bench Press,,,4,normal,80,5,,,"

        val file = readWorkoutExport((listOf(hevyHeader) + lost + kept).joinToString("\n"), zone, imperial = false)!!

        assertEquals(WorkoutExportApp.HEVY, file.app)
        // The end of a session is a stamp its row can do without, and is not a fifth.
        assertEquals(1, file.sets.size)
        assertEquals(4, file.undated)
    }

    /** The same count for a health export. A clock no shape reads costs nothing: the row lands at midday. */
    @Test
    fun aHealthExportCountsItsUndatedRowsToo() {
        val servings = """
            Day,Time,Group,Food Name,Amount,Energy (kcal)
            2026-03-01,half past eight,Breakfast,Porridge,100.00 g,310
            the first of March,08:00,Breakfast,Porridge,100.00 g,310
        """.trimIndent()

        val exports = readHealthExports({ ByteArrayInputStream(servings.toByteArray()) }, "servings.csv", zone, false)

        assertEquals(1, exports.files.single().records.size)
        assertEquals(1, exports.undated)
        assertFalse(exports.cutShort)
    }

    // ─── what is held ───────────────────────────────────────────────────────────────────────────

    /** A Nike archive is one TCX per run with a point for every second; only what is read is held. */
    @Test
    fun aTcxIsHeldOnlyAsFarAsItsSamples() {
        val tcx = "<TrainingCenterDatabase><Lap StartTime=\"2021-03-07T12:25:02Z\"><DistanceMeters>5012.4" +
            "</DistanceMeters><Track><Trackpoint><DistanceMeters>3.1</DistanceMeters></Trackpoint></Track></Lap>"

        assertTrue(tcx.beforeTheTrack().endsWith("<DistanceMeters>5012.4</DistanceMeters>"))
        assertEquals("no track at all", "no track at all".beforeTheTrack())
    }

    /** A loose `export.xml` is not read whole and split into cells before its own reader sees it. */
    @Test
    fun aFileThatCannotBeATableOfSetsIsNotReadToFindOut() {
        var bytesRead = 0
        val xml = ("<?xml version=\"1.0\"?><HealthData>" + " ".repeat(1_000_000)).toByteArray()
        val counted = object : InputStream() {
            private val source = ByteArrayInputStream(xml)

            override fun read(): Int = source.read().also { if (it >= 0) bytesRead += 1 }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                source.read(b, off, len).also { if (it > 0) bytesRead += it }
        }

        assertNull(workoutExportText { counted })
        assertTrue(bytesRead <= 64)
    }

    /** A CSV header may sit behind a byte-order mark and a space; XML, JSON and an archive open otherwise. */
    @Test
    fun theFirstCharacterSaysWhetherAFileCanBeATable() {
        val mark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        assertTrue((mark + " Title,Date,Duration".toByteArray()).mayBeASetTable())
        assertTrue("\"title\",\"start_time\"".toByteArray().mayBeASetTable())
        assertFalse((mark + "  [{\"calendarDate\":1}]".toByteArray()).mayBeASetTable())
        assertFalse("{\"operationalData\":{}}".toByteArray().mayBeASetTable())
        assertFalse("<?xml version=\"1.0\"?>".toByteArray().mayBeASetTable())
        assertFalse("PK".toByteArray().mayBeASetTable())
        assertFalse(ByteArray(0).mayBeASetTable())
    }
}
