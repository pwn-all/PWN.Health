package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a picked file does that its format never promised: the encoding a spreadsheet saved it in,
 * the decimal comma of the language it was saved in, a JSON `null`, the same record stated twice,
 * a value that never ends, a file that stops before its end — and the words this app gives a meal
 * the file states only as a number.
 */
class ImportedFileQuirksTest {
    private val zone = ZoneId.of("Europe/Prague")

    private val utf8Mark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun workoutText(bytes: ByteArray) = workoutExportText { ByteArrayInputStream(bytes) }!!

    private fun exports(bytes: ByteArray, name: String) =
        readHealthExports({ ByteArrayInputStream(bytes) }, name, zone, imperial = false)

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

    // ─── encodings ──────────────────────────────────────────────────────────────────────────────

    /** The mark sits in the first cell of a file with no header, and must not make the first date no date. */
    @Test
    fun aHeaderlessWgerFileBehindAByteOrderMarkIsRead() {
        val weighings = utf8Mark + "2022-11-03,83.40\n2022-11-04,83.10\n".toByteArray()
        val file = readWorkoutExport(workoutText(weighings), zone, false)

        assertEquals(WorkoutExportApp.WGER, file?.app)
        assertEquals(2, file?.weights?.size)
    }

    /** A spreadsheet's "Unicode text" is UTF-16 and tab-separated; read as UTF-8, every cell would be nothing. */
    @Test
    fun aUtf16FileIsReadRowByRow() {
        val table = "Date\tWeight\r\n2022-11-03\t83.40\r\n"
        val marked = byteArrayOf(-1, -2) + table.toByteArray(Charsets.UTF_16LE)

        listOf(marked, table.toByteArray(Charsets.UTF_16LE), table.toByteArray(Charsets.UTF_16BE)).forEach {
            val file = readWorkoutExport(workoutText(it), zone, false)
            assertEquals(83.40, file?.weights?.single()?.kg ?: 0.0, 0.001)
        }
    }

    @Test
    fun theEncodingIsReadOffTheFirstTwoBytes() {
        assertEquals("Date", (byteArrayOf(-1, -2) + "Date".toByteArray(Charsets.UTF_16LE)).exportText())
        assertEquals("Date", (byteArrayOf(-2, -1) + "Date".toByteArray(Charsets.UTF_16BE)).exportText())
        assertEquals("Date", (utf8Mark + "Date".toByteArray()).exportText())
        assertEquals("Žďár", "Žďár".toByteArray().exportText())
        assertEquals("", ByteArray(0).exportText())
    }

    // ─── the decimal comma ──────────────────────────────────────────────────────────────────────

    /** A Hevy file re-saved by a European spreadsheet writes its loads with a comma, quoted. */
    @Test
    fun aHevyLoadWithADecimalCommaIsALoad() {
        val hevy = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
            "Upper A","5 Dec 2025, 11:37","5 Dec 2025, 12:55","","Farmers Walk",,"",0,"normal","82,5",1,"1,2",,8
        """.trimIndent()

        val set = readWorkoutExport(hevy, zone, imperial = false)!!.sets.single()

        assertEquals(82.5, set.loadKg!!, 0.001)
        assertEquals(1_200.0, set.distanceM!!, 0.1)
    }

    /** Strong's Android export is the semicolon one, and a comma is its decimal mark in half of Europe. */
    @Test
    fun aStrongLoadWithADecimalCommaIsALoad() {
        val strong = """
            Date;Workout Name;Exercise Name;Set Order;Weight;Weight Unit;Reps;RPE;Distance;Distance Unit;Seconds;Notes;Workout Notes;Workout Duration
            2026-06-06 18:39:56;"Upper body";"Bench Press (Barbell)";1;82,5;kg;5;8;;;0;"";"";1h 15m
            2026-06-06 18:39:56;"Upper body";"Rowing (Machine)";1;;;;;1,5;km;0;"";"";1h 15m
        """.trimIndent()

        val (bench, row) = readWorkoutExport(strong, zone, imperial = false)!!.sets

        assertEquals(82.5, bench.loadKg!!, 0.001)
        assertEquals(1_500.0, row.distanceM!!, 0.1)
    }

    // ─── what a cell says ───────────────────────────────────────────────────────────────────────

    /** On a phone `optString` answers a JSON `null` with the four letters; the tests' library does not. */
    @Test
    fun aJsonNullIsNoText() {
        val json = JSONObject("""{"name":null,"brand":"Barilla"}""")

        assertEquals("", json.optText("name"))
        assertEquals("", json.optText("absent"))
        assertEquals("Barilla", json.optText("brand"))
    }

    /** Milliseconds between beats sit inside a pulse's range, and are not imported as a pulse. */
    @Test
    fun aHeartRateVariabilityIsNotAPulse() {
        val biometrics = """
            Day,Time,Metric,Unit,Amount
            2026-03-01,08:15,Heart Rate Variability,ms,48
            2026-03-01,08:15,Heart Rate,bpm,61
        """.trimIndent()

        val vitals = readHealthExport(biometrics.toByteArray(), "biometrics.csv", zone, imperial = false)!!
            .records.filterIsInstance<VitalRecord>()

        assertEquals(listOf(Vital.HEART_RATE to 61.0), vitals.map { it.vital to it.value })
    }

    /** Fitbit and Samsung number their meals; the row wears the reader's word, the marker one no language changes. */
    @Test
    fun aNumberedMealIsNamedInTheReadersLanguage() {
        val porridge = NutritionRecord(
            Instant.parse("2026-03-19T11:00:00Z").toEpochMilli(),
            title = "Porridge",
            meal = "",
            calories = 180.0,
            wallClock = true,
            numberedMeal = NumberedMeal.BREAKFAST,
        )

        val row = HealthImportFile(HealthExportApp.FITBIT, listOf(porridge)).rows(zone, testTitles()).entries.single()

        assertEquals("Meal BREAKFAST", row.subtitle)
        assertTrue(row.healthConnectRecordKey.orEmpty().contains(":eat:breakfast:Porridge:"))
    }

    // ─── Apple's one file ───────────────────────────────────────────────────────────────────────

    private val water = """type="HKQuantityTypeIdentifierDietaryWater" sourceName="MyFitnessPal" unit="mL""""

    /** A food's water is a record of the correlation and, in Apple's file, a record on its own as well. */
    @Test
    fun aDrinkStatedTwiceIsDrunkOnce() {
        val morning = """creationDate="2026-03-01 08:01:00 +0100" startDate="2026-03-01 08:00:00 +0100""""
        val noon = """creationDate="2026-03-01 12:01:00 +0100" startDate="2026-03-01 12:00:00 +0100""""
        val xml = """
            <HealthData locale="en_GB">
             <Record $water $morning value="250"/>
             <Correlation type="HKCorrelationTypeIdentifierFood" $morning>
              <Record $water $morning value="250"/>
             </Correlation>
             <Record $water $noon value="250"/>
            </HealthData>
        """.trimIndent()

        val drunk = readAppleHealthXml(StringReader(xml), zone).records.filterIsInstance<DrinkRecord>().single()

        assertEquals(500.0, drunk.amount, 0.1)
    }

    /** A loose `export.xml` whose copy stopped halfway is said to be cut short, not read as a shorter history. */
    @Test
    fun anAppleFileCutOffIsSaidToBeOne() {
        val whole = """
            <HealthData locale="en_GB">
             <Record type="HKQuantityTypeIdentifierBodyMass" unit="kg" startDate="2026-03-01 07:00:00 +0100" value="72"/>
             <Record type="HKQuantityTypeIdentifierBodyMass" unit="kg" startDate="2026-03-02 07:00:00 +0100" value="72.4"/>
            </HealthData>
        """.trimIndent()
        val cut = whole.substringBefore("</HealthData>")

        assertTrue(readAppleHealthXml(StringReader(whole), zone).whole)
        assertFalse(readAppleHealthXml(StringReader(cut), zone).whole)
        val partial = exports(cut.toByteArray(), "export.xml")
        assertTrue(partial.cutShort)
        assertEquals(2, partial.files.single().records.size)
        assertFalse(exports(whole.toByteArray(), "export.xml").cutShort)
    }

    /** A value that never ends is cut at the limit rather than gathered until the heap runs out. */
    @Test
    fun aValueThatNeverEndsIsCutAtTheLimit() {
        var value = ""
        var attributes = 0
        val endless = "<Record type=\"X\" value=\"" + "9".repeat(SCANNED_TEXT_LIMIT * 2)
        val crowded = "<Record " + (1..1_000).joinToString(" ") { "a$it=\"1\"" } + "/>"

        scanAppleHealth(StringReader(endless), setOf("Record")) { _, element ->
            value = element["value"].orEmpty()
            true
        }
        scanAppleHealth(StringReader(crowded), setOf("Record")) { _, element ->
            attributes = element.size
            true
        }

        assertEquals(SCANNED_TEXT_LIMIT, value.length)
        assertTrue(attributes in 1..64)
    }

    // ─── whose file it is ───────────────────────────────────────────────────────────────────────

    /** Any JSON with an `"error"` key is claimed by Fitbit's resting pulse; claiming is not holding. */
    @Test
    fun aServiceThatFoundNothingIsNotNamedBesideOneThatDid() {
        val garmin = "DI_CONNECT/DI-Connect-Fitness/summarizedActivities.json" to
            """
            [{"summarizedActivitiesExport":[{"name":"Run","activityType":"running",
              "startTimeGmt":1740000000000,"duration":1830.0}]}]
            """
        val stray = "DI_CONNECT/DI-Connect-User/status.json" to """{"error": null, "state": "done"}"""

        val both = exports(zip(garmin, stray), "export.zip")
        val alone = exports(zip(stray), "export.zip")

        assertEquals(listOf(HealthExportApp.GARMIN), both.files.map { it.app })
        // Where nothing was found at all, the file is still somebody's, for "held nothing" to be said of it.
        assertEquals(listOf(HealthExportApp.FITBIT), alone.files.map { it.app })
    }

    /**
     * A `.json.gz` inside an archive is passed over before it is opened, on purpose ([ArchiveEntry.text]):
     * past that gate every one of the thousands of gzipped routes in a Strava archive would be inflated
     * and read. The same JSON stored plain is Garmin's; gzipped, it is nobody's file and no skipped one.
     */
    @Test
    fun aGzippedJsonInsideAnArchiveIsPassedOverUnopened() {
        val activities = """
            [{"summarizedActivitiesExport":[{"name":"Run","activityType":"running",
              "startTimeGmt":1740000000000,"duration":1830.0}]}]
        """.trimIndent()
        val gzipped = ByteArrayOutputStream()
        GZIPOutputStream(gzipped).use { it.write(activities.toByteArray()) }
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("DI_CONNECT/DI-Connect-Fitness/x_summarizedActivities.json.gz"))
            zip.write(gzipped.toByteArray())
            zip.closeEntry()
        }

        val plain = exports(zip("DI-Connect-Fitness/x_summarizedActivities.json" to activities), "export.zip")
        val passedOver = exports(archive.toByteArray(), "export.zip")

        assertEquals(listOf(HealthExportApp.GARMIN), plain.files.map { it.app })
        assertTrue(passedOver.files.isEmpty())
        assertEquals(0, passedOver.skipped)
    }
}
