package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The reader for the files Hevy, Strong, FitNotes, wger and Lyfta export.
 *
 * Every sample below is the real header of a real export — each one was read either out of a
 * published file or out of the exporting app's own decompiled writer — with the rows cut down to
 * the case being tested. That matters more here than in most tests: the whole feature is a claim
 * about somebody else's file format, and a test written against a header this app invented would
 * pass forever while the import read nothing.
 */
class WorkoutImportTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun read(text: String, imperial: Boolean = false) =
        readWorkoutExport(text, zone, imperial)

    private fun entries(text: String, imperial: Boolean = false) =
        read(text, imperial)?.let { importedJournalEntries(it, zone, imperial, "Weight") }.orEmpty()

    // ─── the CSV underneath ─────────────────────────────────────────────────────────────────────

    /** A comma inside a quoted title is the row's, not the reader's; a naive split loses a column. */
    @Test
    fun aQuotedCommaStaysInsideItsCell() {
        val cells = csvRecords("\"Morning Workout, easy\",2026-07-13,55.000\n", ',').single()
        assertEquals(listOf("Morning Workout, easy", "2026-07-13", "55.000"), cells)
    }

    /** Strong's Android writer wraps a note without escaping what is inside it. */
    @Test
    fun aBareQuoteInsideACellIsACharacterAndNotAQuote() {
        val cells = csvRecords("a,5\" deficit,b\n", ',').single()
        assertEquals(listOf("a", "5\" deficit", "b"), cells)
    }

    @Test
    fun aDoubledQuoteIsOneQuote() {
        val cells = csvRecords("\"belt on, \"\"touch and go\"\"\",3\n", ',').single()
        assertEquals(listOf("belt on, \"touch and go\"", "3"), cells)
    }

    /** A quoted cell may hold the line break; the record does not end until the quote does. */
    @Test
    fun aNewlineInsideAQuotedCellDoesNotEndTheRecord() {
        assertEquals(1, csvRecords("\"Each leg\nSlow eccentric\",30.0\n", ',').size)
    }

    /** Strong ships both; a European spreadsheet turns either into the other. */
    @Test
    fun theDelimiterIsAskedForRatherThanAssumed() {
        assertEquals(';', csvDelimiter("Date;Workout Name;Exercise Name;Set Order;Weight\n"))
        assertEquals(',', csvDelimiter("Date,Workout Name,Duration,Exercise Name,Set Order\n"))
    }

    /** Lyfta's header really does begin with a space, in two unrelated real exports. */
    @Test
    fun aHeaderIsKnownByItsLettersAlone() {
        assertEquals("title", " Title".headerKey())
        assertEquals("supersetid", "\"Superset id\"".headerKey())
        assertEquals("weightkg", "Weight (kg)".headerKey())
        assertEquals("setindex", "set_index".headerKey())
    }

    // ─── Hevy ───────────────────────────────────────────────────────────────────────────────────

    private val hevy = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Bench Press (Barbell)",,"",0,"warmup",40,10,,,
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Bench Press (Barbell)",,"",1,"normal",80,5,,,8
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Bench Press (Barbell)",,"",2,"failure",85,4,,,9.5
        "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Pull Up",,"",0,"normal",,8,,,
    """.trimIndent()

    /**
     * The file states the wall clock and no zone, so the instant the phone makes of it depends on
     * where the phone is. The marker names the wall clock: read in Prague or in New York, the same
     * set is the same row, and a monthly export brought along after a move lands nothing twice.
     */
    @Test
    fun aMarkerNamesTheWallClockTheFileStatesWhateverZoneReadsIt() {
        val newYork = ZoneId.of("America/New_York")
        fun readIn(where: ZoneId) =
            importedJournalEntries(readWorkoutExport(hevy, where, false)!!, where, false, "Weight")
        val prague = readIn(zone)
        val away = readIn(newYork)

        assertEquals(prague.map { it.healthConnectRecordKey }, away.map { it.healthConnectRecordKey })
        assertTrue(prague.first().healthConnectRecordKey!!.contains(":2025-12-05T113700:"))
        assertTrue(prague.first().occurredAt != away.first().occurredAt)
    }

    /** A row whose name holds the instant is recognised under its wall-clock name in the zone it was read in. */
    @Test
    fun aRowFiledUnderTheInstantIsStillHeld() {
        val utc = ZoneId.of("UTC")

        assertEquals(
            "File import:Hevy:1970-01-01T000000:Bench Press (Barbell)",
            "File import:Hevy:0:Bench Press (Barbell)".withWallClockStamp(utc),
        )
        assertEquals(
            "File import:wger:1970-01-02T000000:weight",
            "File import:wger:86400000:weight".withWallClockStamp(utc),
        )
        assertNull("File import:Hevy:2025-12-05T113700:Bench Press (Barbell)".withWallClockStamp(utc))
        assertNull("Health Connect:com.x:uuid".withWallClockStamp(utc))
    }

    @Test
    fun hevyIsRecognisedAndItsSessionSpansStartToEnd() {
        val file = assertNotNull(read(hevy)).let { read(hevy)!! }
        assertEquals(WorkoutExportApp.HEVY, file.app)
        assertEquals(4, file.sets.size)
        // 11:37 to 12:55 is seventy-eight minutes, and every row of the session repeats both.
        assertEquals(78 * 60, file.sets.first().sessionSeconds)
    }

    @Test
    fun hevyTurnsOneExercisePerSessionIntoOneRow() {
        val rows = entries(hevy)
        assertEquals(listOf("Bench Press (Barbell)", "Pull Up"), rows.map(JournalEntry::title))
        assertEquals("BENCH_PRESS", rows.first().activityCode)
        assertEquals("PULLUPS", rows.last().activityCode)
    }

    /** The warm-up is in the payload and out of the columns a week-on-week comparison reads. */
    @Test
    fun hevyKeepsTheWarmUpOutOfTheWorkingNumbers() {
        val bench = entries(hevy).first()
        assertEquals(2, bench.sets)
        assertEquals(9, bench.reps)
        assertEquals(82.5, bench.loadKg!!, 0.001)
        assertEquals(3, decodeSetsFromJson(bench.measurementPayloadJson).size)
    }

    /** An RPE is a RIR counted from the other end, and the halves round. */
    @Test
    fun hevyReadsEffortAsRepsInReserve() {
        assertEquals(2, rirFromRpe(8.0))
        // Half a rep in reserve is recorded as one, not as none: the halves round up.
        assertEquals(1, rirFromRpe(9.5))
        assertEquals(4, rirFromRpe(6.0))
        assertEquals(4, rirFromRpe(1.0))
        assertNull(rirFromRpe(0.0))
    }

    /** The unit is in the column name, and the two settings move independently. */
    @Test
    fun hevyReadsPoundsOutOfTheColumnName() {
        val pounds = hevy.replace("weight_kg", "weight_lbs")
        // The second set reads 80 in the file, and eighty pounds is 36.3 kg.
        assertEquals(36.287, read(pounds)!!.sets[1].loadKg!!, 0.01)
    }

    /** The American rendering puts a comma inside the timestamp, which is why quoting matters. */
    @Test
    fun hevyReadsTheTwelveHourTimestampToo() {
        val american = hevy
            .replace("\"5 Dec 2025, 11:37\"", "\"Jun 19, 2026, 9:40 AM\"")
            .replace("\"5 Dec 2025, 12:55\"", "\"Jun 19, 2026, 10:45 AM\"")
        val file = read(american)!!
        assertEquals(4, file.sets.size)
        assertEquals(65 * 60, file.sets.first().sessionSeconds)
    }

    /**
     * Hevy renders its timestamps through the phone's own locale, so the month is written in the
     * exporter's language. Read as English alone, every row of such a file fails — and a file whose
     * every row fails does not look broken, it looks like an empty history.
     */
    @Test
    fun hevyReadsAMonthWrittenInTheExportersOwnLanguage() {
        // Several of these carry a full stop, which is how the abbreviation is actually written in
        // those languages — the French `janv.` in a real export is the same shape.
        listOf("dic", "Ara", "Dez.", "déc.", "дек.", "dez.", "gru", "pro", "Des").forEach { month ->
            val translated = hevy.replace("Dec", month)
            assertEquals(month, 4, read(translated)?.sets?.size)
        }
    }

    // ─── Strong ─────────────────────────────────────────────────────────────────────────────────

    /** The iOS export: comma, twelve columns, no unit anywhere, and a written duration. */
    private val strongIos = """
        Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
        2024-05-31 12:24:39,"Legs V1",1h 5m,"Squat (Barbell)",1,60.0,8,0,0,"","",7
        2024-05-31 12:24:39,"Legs V1",1h 5m,"Squat (Barbell)",2,80.0,5,0,0,,,8
        2024-05-31 12:24:39,"Legs V1",1h 5m,"Plank",1,0,0,0,60,"",,
    """.trimIndent()

    /** The Android export: semicolon, a unit column of its own, duration last. */
    private val strongAndroid = """
        Date;Workout Name;Exercise Name;Set Order;Weight;Weight Unit;Reps;RPE;Distance;Distance Unit;Seconds;Notes;Workout Notes;Workout Duration
        2026-06-06 18:39:56;"Upper body";"Bench Press (Barbell)";1;60;kg;8;;;;0;"";"";1h 15m
        2026-06-06 18:39:56;"Upper body";"Bench Press (Barbell)";2;70;kg;5;8;;;0;;;1h 15m
        2026-06-06 18:39:56;"Upper body";"Pull Up";1;;;8;;;;0;"";;1h 15m
    """.trimIndent()

    /** The newest layout: every cell quoted, the unit in the heading, duration in seconds. */
    private val strongQuoted = """
        "Workout #";"Date";"Workout Name";"Duration (sec)";"Exercise Name";"Set Order";"Weight (kg)";"Reps";"RPE";"Distance (meters)";"Seconds";"Notes";"Workout Notes"
        "1";"2025-02-24 15:10:32";"Chest and Triceps";"2176";"Bench Press (Barbell)";"W";"40.0";"10";"";"";"";"";""
        "1";"2025-02-24 15:10:32";"Chest and Triceps";"2176";"Bench Press (Barbell)";"1";"55.0";"10";"";"";"";"";""
        "1";"2025-02-24 15:10:32";"Chest and Triceps";"2176";"Bench Press (Barbell)";"Rest Timer";"";"";"";"";"120.0";"";""
        "1";"2025-02-24 15:10:32";"Chest and Triceps";"2176";"Incline Bench Press (Dumbbell)";"Note";"";"";"";"";"";"Pair with Y";""
        "1";"2025-02-24 15:10:32";"Chest and Triceps";"2176";"Incline Bench Press (Dumbbell)";"1";"17.5";"10";"";"";"";"";""
    """.trimIndent()

    @Test
    fun allFourStrongExportsAreOneFormat() {
        listOf(strongIos, strongAndroid, strongQuoted).forEach {
            assertEquals(WorkoutExportApp.STRONG, read(it)!!.app)
        }
    }

    @Test
    fun strongSplitsTheWrittenSessionLengthBetweenItsExercises() {
        val rows = entries(strongIos)
        // Sixty-five minutes over three sets: two of them are the squat, one the plank.
        assertEquals(65.0 * 2 / 3, rows.first { it.title.startsWith("Squat") }.durationMinutes!!, 0.01)
        assertEquals(65.0 / 3, rows.first { it.title == "Plank" }.durationMinutes!!, 0.01)
    }

    /** No unit anywhere in the iOS file, so it is read in the unit this app is set to. */
    @Test
    fun strongReadsAnUnmarkedLoadInTheReadersOwnUnit() {
        assertEquals(60.0, read(strongIos)!!.sets.first().loadKg!!, 0.001)
        assertEquals(27.215, read(strongIos, imperial = true)!!.sets.first().loadKg!!, 0.01)
    }

    /** The Android unit column is a translated word, so a Russian export still reads as kilograms. */
    @Test
    fun strongReadsATranslatedUnitColumn() {
        val russian = strongAndroid.replace(";kg;", ";кг;")
        assertEquals(60.0, read(russian, imperial = true)!!.sets.first().loadKg!!, 0.001)
        val pounds = strongAndroid.replace(";kg;", ";lbs;")
        assertEquals(27.215, read(pounds)!!.sets.first().loadKg!!, 0.01)
    }

    /**
     * A rest timer and a plank are the same numbers — no weight, no reps, some seconds — so only
     * the set-order column tells them apart, and half of some real files are rest timers.
     */
    @Test
    fun strongDropsTheRowsThatAreNotSets() {
        val file = read(strongQuoted)!!
        assertEquals(3, file.sets.size)
        assertTrue(file.sets.none { it.exercise.isBlank() })
        assertEquals(listOf(true, false, false), file.sets.map(ImportedSet::warmup))
    }

    @Test
    fun strongReadsTheUnitOutOfAQuotedHeadingAndTheDurationInSeconds() {
        val file = read(strongQuoted)!!
        assertEquals(40.0, file.sets.first().loadKg!!, 0.001)
        assertEquals(2_176, file.sets.first().sessionSeconds)
    }

    /** A session nobody tapped Finish on is exported as the time since it started. */
    @Test
    fun anImpossibleSessionLengthIsNotBelieved() {
        val forgotten = strongIos.replace("1h 5m", "264h 1m")
        // Three sets fall back to a minute and a half each rather than to eighty-eight hours.
        assertEquals(1.5 * 2, entries(forgotten).first().durationMinutes!!, 0.01)
    }

    /**
     * A real published row, and a nasty one: a runaway duration of 1,223 hours was written with a
     * thousands separator and no quotes, so it split into two cells. The row still has exactly
     * twelve fields, so counting them proves nothing — everything from the duration rightward has
     * simply shifted one place, and the exercise name has landed in the set-order column. Asking
     * whether the set order names a set is what catches it.
     */
    @Test
    fun aRowTornInHalfByAThousandsSeparatorIsRefused() {
        val torn = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
            2023-08-15 20:37:28,"Evening Workout",1,223h 29m,"Deadlift (Barbell)",1,235.0,3,0,0,"",
        """.trimIndent()
        assertEquals(emptyList<ImportedSet>(), read(torn)!!.sets)
    }

    /** Strong's Android writer quotes a note without escaping it, so a typed newline goes through raw. */
    @Test
    fun aNoteWithARealNewlineDoesNotEndTheRow() {
        val multiline = strongAndroid.replace(
            """;"";"";1h 15m""",
            ";\"felt strong\ncoming back\";\"\";1h 15m",
        )
        assertEquals(3, read(multiline)!!.sets.size)
    }

    @Test
    fun aWrittenSpanIsReadInEveryShapeStrongWrites() {
        assertEquals(3_900, spanSeconds("1h 5m"))
        assertEquals(2_880, spanSeconds("48m"))
        assertEquals(8, spanSeconds("8s"))
        assertEquals(4_500, spanSeconds("1ч 15м"))
        assertEquals(2_176, spanSeconds("2176"))
        assertNull(spanSeconds(""))
    }

    // ─── FitNotes ───────────────────────────────────────────────────────────────────────────────

    private val fitNotes = """
        Date,Exercise,Category,Weight,Weight Unit,Reps,Distance,Distance Unit,Time
        2024-03-18,Barbell Squat,Legs,100.0,kgs,5,,,
        2024-03-18,Barbell Squat,Legs,140.0,lbs,3,,,
        2024-03-18,Plank,Abs,0.0,kgs,,,,0:01:30
        2024-03-19,Incline Treadmill,Cardio,,,,1.58,km,0:20:00
    """.trimIndent()

    @Test
    fun fitNotesIsRecognisedAndItsDayIsItsSession() {
        val file = read(fitNotes)!!
        assertEquals(WorkoutExportApp.FITNOTES, file.app)
        assertEquals(4, file.sets.size)
        assertNull(file.sets.first().sessionSeconds)
    }

    /** FitNotes hangs the unit off the exercise, so one file legitimately mixes the two. */
    @Test
    fun fitNotesReadsTheUnitRowByRow() {
        val file = read(fitNotes)!!
        assertEquals(100.0, file.sets[0].loadKg!!, 0.001)
        assertEquals(63.503, file.sets[1].loadKg!!, 0.01)
    }

    @Test
    fun fitNotesReadsAHeldSetAndACardioRow() {
        val rows = entries(fitNotes)
        assertEquals(1.5, rows.first { it.title == "Plank" }.durationMinutes!!, 0.001)
        val run = rows.first { it.title == "Incline Treadmill" }
        assertEquals(20.0, run.durationMinutes!!, 0.001)
        assertEquals("RUN", run.activityCode)
        assertTrue(run.subtitle.endsWith("1.6 km"))
    }

    /** A file with a date and no clock must not file its rows on the edge of the day. */
    @Test
    fun aDatelessFileLandsAtMiddayAndNotAtMidnight() {
        val day = entries(fitNotes).first()
        assertEquals("2024-03-18", day.day)
        assertEquals(12, java.time.Instant.ofEpochMilli(day.occurredAt).atZone(zone).hour)
    }

    /**
     * "FitNotes" is two apps by two developers. The iOS one writes eleven columns, puts kilograms
     * and pounds in two columns of their own with no unit column at all, offers seven distance
     * units rather than four, and may leave the hours off a duration.
     */
    private val fitNotesIos = """
        Date,Exercise,Category,Weight (kg),Weight (lbs),Reps,Distance,Distance Unit,Time,Notes,Kind
        2024-03-18,Barbell Squat,Legs,100.0,,5,,,,"",
        2024-03-18,Bench Press,Chest,,225.0,3,,,,"",
        2024-03-19,Rowing,Cardio,,,,500,yd,23:59,"",
    """.trimIndent()

    @Test
    fun theOtherFitNotesIsReadToo() {
        val file = read(fitNotesIos)!!
        assertEquals(WorkoutExportApp.FITNOTES, file.app)
        assertEquals(3, file.sets.size)
        assertEquals(100.0, file.sets[0].loadKg!!, 0.001)
        assertEquals(102.058, file.sets[1].loadKg!!, 0.01)
        // 500 yards, and a duration written without its hours.
        assertEquals(457.2, file.sets[2].distanceM!!, 0.1)
        assertEquals(23 * 60 + 59, file.sets[2].seconds)
    }

    // ─── Lyfta ──────────────────────────────────────────────────────────────────────────────────

    private val lyfta = """
         Title,Date,Duration,Exercise,"Superset id",Weight,Reps,Distance,Time,"Set Type"
        "Morning Workout, easy","2026-07-13 07:30:19",01:07:22,"Bench Press",,20.000,12,null,null,WARMUP_SET
        "Morning Workout, easy","2026-07-13 07:30:19",01:07:22,"Bench Press",,55.000,10,null,null,NORMAL_SET
        Back,"2026-07-15 17:12:04",00:52:10,Deadlift,,60.000,8,0,0,NORMAL_SET
    """.trimIndent()

    @Test
    fun lyftaIsRecognisedThroughItsLeadingSpaceAndItsNullCells() {
        val file = read(lyfta)!!
        assertEquals(WorkoutExportApp.LYFTA, file.app)
        assertEquals(3, file.sets.size)
        assertEquals(67 * 60 + 22, file.sets.first().sessionSeconds)
        assertEquals("Morning Workout, easy", file.sets.first().workoutTitle)
    }

    /**
     * Absence has three spellings in one Lyfta file — nothing, `null`, and a bare `0` — and the
     * last must not be read as a set that was held for no time at all.
     */
    @Test
    fun lyftaReadsAllThreeSpellingsOfAnEmptyCell() {
        assertTrue(read(lyfta)!!.sets.all { it.seconds == null })
    }

    /** Nothing in a Lyfta file says what unit its loads are in. */
    @Test
    fun lyftaReadsItsUnmarkedLoadsInTheReadersOwnUnit() {
        assertEquals(55.0, read(lyfta)!!.sets[1].loadKg!!, 0.001)
        assertEquals(24.947, read(lyfta, imperial = true)!!.sets[1].loadKg!!, 0.01)
    }

    // ─── wger ───────────────────────────────────────────────────────────────────────────────────

    /** wger writes a Python datetime with a real offset, and translates both column names. */
    @Test
    fun wgerWeightsAreReadThroughTheirTranslatedHeader() {
        val file = read("Datum,Gewicht\n2026-01-02 06:11:00+00:00,83.40\n2026-02-05 07:41:12.483920+00:00,81.55\n")!!
        assertEquals(WorkoutExportApp.WGER, file.app)
        assertEquals(listOf(83.40, 81.55), file.weights.map { it.kg })
        assertTrue(file.sets.isEmpty())
    }

    /** The offset is read rather than assumed: 06:11 UTC is 07:11 in Prague, still the same day. */
    @Test
    fun wgerWeightsBecomeMeasurementRows() {
        val rows = entries("Date,Weight\n2026-01-02 06:11:00+00:00,83.40\n")
        val row = rows.single()
        assertEquals("MEASUREMENT", row.kind)
        assertEquals("2026-01-02", row.day)
        assertEquals(83.40, row.measurementWeightKg!!, 0.001)
        assertEquals("wger", row.measurementSource)
    }

    @Test
    fun theLegacyWgerExportHasNoClockAtAll() {
        val file = read("Date,Weight\n2022-11-03,83.40\n")!!
        assertEquals(1, file.weights.size)
    }

    /** Two columns of something that is not a weight is not a wger export. */
    @Test
    fun aTwoColumnFileOfSomethingElseIsRefused() {
        assertNull(read("Date,Steps\n2026-01-02,8412\n"))
        assertNull(read("Date,Weight\n2026-01-02,83.40\nnot a date,nonsense\n"))
        // A resting pulse a day sits inside a person's weight, but wger writes no weight without its
        // two places, so a column of whole numbers — or of any other precision — is not one of its.
        assertNull(read("Date,Resting heart rate\n2026-01-02,62\n2026-01-03,64\n"))
        assertNull(read("Date,HRV\n2026-01-02 06:11:00+00:00,48.5\n"))
        assertNull(read("Date,Weight\n2026-01-02,83.4\n"))
    }

    // ─── what the reader will not do ────────────────────────────────────────────────────────────

    @Test
    fun aFileNoFormatClaimsIsRefusedRatherThanReadAsEmpty() {
        assertNull(read("some,other,file\n1,2,3\n"))
        assertNull(read(""))
    }

    /** The import is a button somebody will press twice. */
    @Test
    fun theSameFileNamesTheSameRowsEveryTimeItIsRead() {
        assertEquals(
            entries(hevy).mapNotNull {
                it.healthConnectRecordKey
            },
            entries(hevy).mapNotNull { it.healthConnectRecordKey },
        )
        assertTrue(entries(hevy).all { it.healthConnectRecordKey!!.startsWith(FILE_IMPORT_MARKER_PREFIX) })
        // Distinct rows must not share a name, or the second would be skipped as already held.
        val keys = entries(hevy).map { it.healthConnectRecordKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    /** An imported row is somebody else's record, and this app does not republish it. */
    @Test
    fun importedRowsAreNotThisAppsToPublish() {
        assertTrue(entries(hevy).none { it.healthConnectRecordKey == null })
    }

    // ─── naming the exercise ────────────────────────────────────────────────────────────────────

    /**
     * The order of the rules is the whole of the logic, and these are the pairs that would go wrong
     * if a general rule were asked before the specific one it contains.
     */
    @Test
    fun theCompoundNameWinsOverTheMovementInsideIt() {
        assertEquals("LUNGES", activityCodeFor("Bulgarian Split Squat"))
        assertEquals("SQUATS", activityCodeFor("Squat (Barbell)"))
        assertEquals("SHOULDER_PRESS_MACHINE", activityCodeFor("Shoulder Press (Machine)"))
        assertEquals("OVERHEAD_PRESS", activityCodeFor("Shoulder Press (Dumbbell)"))
        assertEquals("SEATED_ROW", activityCodeFor("Lever Narrow Grip Seated Row"))
        assertEquals("ROWING_MACHINE", activityCodeFor("Rowing Machine"))
        assertEquals("BARBELL_ROW", activityCodeFor("Bent Over Row (Barbell)"))
        assertEquals("LAT_PULLDOWN_CLOSE", activityCodeFor("Close Grip Lat Pulldown"))
        assertEquals("LAT_PULLDOWN", activityCodeFor("Lat Pulldown (Cable)"))
        assertEquals("REVERSE_FLY", activityCodeFor("Reverse Pec Deck"))
        assertEquals("REVERSE_FLY", activityCodeFor("Rear Delt Reverse Fly (Machine)"))
        assertEquals("PEC_DECK", activityCodeFor("Pec Deck (Machine)"))
        assertEquals("BAR_LEG_RAISE", activityCodeFor("Dip Station Leg Raise"))
        assertEquals("BAR_LEG_RAISE", activityCodeFor("Captain's Chair Knee Raise"))
        assertEquals("ABS", activityCodeFor("Hanging Leg Raise"))
        assertEquals("BICYCLE_CRUNCH", activityCodeFor("Bicycle Crunch"))
        assertEquals("ABS", activityCodeFor("Crunch"))
        assertEquals("DIPS", activityCodeFor("Parallel Bar Dips"))
    }

    /** Opening the legs and closing them are two machines and two sets of muscles. */
    @Test
    fun abductionAndAdductionAreTwoExercises() {
        assertEquals("HIP_ABDUCTION", activityCodeFor("Hip Abductor (Machine)"))
        assertEquals("HIP_ADDUCTION", activityCodeFor("Hip Adductor (Machine)"))
    }

    /** "Box jump" is not boxing, and a throw is not a row. */
    @Test
    fun aWordInsideAnotherWordIsNotAMatch() {
        assertEquals(FALLBACK_ACTIVITY_CODE, activityCodeFor("Box Jump"))
        assertEquals("SQUATS", activityCodeFor("Box Squat"))
        assertEquals("BOXING", activityCodeFor("Boxing"))
        assertEquals(FALLBACK_ACTIVITY_CODE, activityCodeFor("Med Ball Throw"))
        assertEquals("BARBELL_ROW", activityCodeFor("Bent Over Row"))
    }

    /** A rule may still be the opening of a word, so that one line catches every ending of it. */
    @Test
    fun aRuleMayMatchTheStartOfAWord() {
        assertEquals("HIKING", activityCodeFor("Hiking"))
        assertEquals("HIKING", activityCodeFor("Hike"))
        assertEquals("DIPS", activityCodeFor("Dips"))
        assertEquals("BICEPS_CURL", activityCodeFor("Hammer Curls"))
    }

    /**
     * The session exports name a whole session in one word — Strava's "Run" and "Ride", Apple's
     * "Rowing" — and unclaimed it would be filed as strength training: a run costed at a lifting MET and
     * counted on top of the day's steps, a row on the water drawn as a barbell row.
     */
    @Test
    fun aSessionNamedInOneWordIsThatActivity() {
        assertEquals("RUN", activityCodeFor("Run"))
        assertEquals("RUN", activityCodeFor("Morning Run"))
        assertEquals("RUN", activityCodeFor("Fat man run"))
        assertEquals("RUN", activityCodeFor("Running"))
        assertEquals("CYCLING", activityCodeFor("Ride"))
        assertEquals("CYCLING", activityCodeFor("Afternoon Ride"))
        assertEquals("ROWING_MACHINE", activityCodeFor("Rowing"))
        assertEquals("ROWING_MACHINE", activityCodeFor("Rowing (Machine)"))
        // The lift keeps its own name.
        assertEquals("BARBELL_ROW", activityCodeFor("Barbell Row"))
    }

    /** A name nobody recognises keeps its own title and is filed as resistance training. */
    @Test
    fun anUnknownExerciseIsFiledHonestly() {
        assertEquals(FALLBACK_ACTIVITY_CODE, activityCodeFor("Zercher Carry"))
        val rows = importedJournalEntries(
            WorkoutImportFile(
                WorkoutExportApp.HEVY,
                listOf(
                    ImportedSet(
                        workoutTitle = "Odd day",
                        startedAtMs = 1_800_000_000_000L,
                        exercise = "Zercher Carry",
                        reps = 5,
                        loadKg = 100.0,
                        seconds = null,
                        distanceM = null,
                        rir = null,
                        warmup = false,
                        sessionSeconds = null,
                    ),
                ),
            ),
            zone,
            imperial = false,
            weightTitle = "Weight",
        )
        assertEquals("Zercher Carry", rows.single().title)
        assertEquals(FALLBACK_ACTIVITY_CODE, rows.single().activityCode)
    }

    /** Every code the rules can produce has to be an exercise the app actually has. */
    @Test
    fun everyRuleNamesAnExerciseInTheCatalogue() {
        val codes = ACTIVITY_CATALOG.map { it.code }.toSet()
        val named = listOf(
            "Bench Press", "Pull Up", "Push Up", "Dip", "Plank", "Crunch", "Burpee", "Jumping Jack",
            "Jump Rope", "Treadmill", "Walking", "Hiking", "Stairmaster", "Elliptical", "Cycling",
            "Swimming", "Yoga", "Pilates", "Stretching", "Boxing", "Basketball", "Football",
            "Tennis", "Zumba", "HIIT", "Calisthenics", "Deadlift", "Squat", "Lunge", "Biceps Curl",
            "Triceps Pushdown", "French Press", "Close Grip Bench", "Lateral Raise", "Leg Press",
            "Leg Extension", "Leg Curl", "Calf Raise", "Hip Thrust", "Hip Abduction",
            "Back Extension", "Pec Deck", "Cable Crossover", "Chest Press", "Overhead Press",
            "Seated Row", "Rowing Machine", "Lat Pulldown", "Close Grip Lat Pulldown",
            "Shoulder Press (Machine)", "Zercher Carry",
        ).map(::activityCodeFor)
        assertEquals(emptyList<String>(), named.filterNot { it in codes })
    }

    // ─── the sets the journal stores ────────────────────────────────────────────────────────────

    /**
     * The payload is written by the app's own encoder, so an imported session reads back through
     * the editor exactly as a logged one does — and on an imperial profile the loads come back in
     * pounds rather than being shown as kilograms wearing a pound label.
     */
    @Test
    fun theSetsReadBackThroughTheAppsOwnEditor() {
        val pounds = 2.2046226218
        val bench = entries(hevy, imperial = true).first()
        val sets = decodeSetsFromJson(bench.measurementPayloadJson, pounds)
        assertEquals(3, sets.size)
        assertEquals("10", sets.first().reps)
        assertEquals(40.0 * pounds, sets.first().load.toDouble(), 0.05)
        assertEquals(80.0 * pounds, sets[1].load.toDouble(), 0.05)
        // The flat column stays in kilograms whatever the profile is showing.
        assertEquals(82.5, bench.loadKg!!, 0.01)
    }

    @Test
    fun anImportedWorkoutCarriesTheMetItsEnergyIsWorkedOutFrom() {
        val bench = entries(hevy).first()
        assertEquals(ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }.met, bench.met!!, 0.001)
        // Energy itself is left for the pass over the measurement timeline after the file lands.
        assertEquals(0.0, bench.calories, 0.001)
    }
}
