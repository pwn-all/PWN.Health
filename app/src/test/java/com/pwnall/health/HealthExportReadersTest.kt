package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The readers for the eleven services whose export is a file rather than an XML stream.
 *
 * Every header line below is a real one. Where a header was only ever seen in somebody else's
 * parser rather than in an export, the reader does not read that column at all and there is no test
 * for it — a test written against a column this app invented would pass forever while the import
 * read nothing, which is exactly the failure this whole file exists to prevent.
 */
class HealthExportReadersTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
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

    private fun read(vararg entries: Pair<String, String>, imperial: Boolean = false) =
        readHealthExport(archive(*entries), "export.zip", zone, imperial)

    private fun loose(name: String, text: String, imperial: Boolean = false) =
        readHealthExport(text.trimIndent().toByteArray(), name, zone, imperial)

    private inline fun <reified T> HealthImportFile?.of(): List<T> =
        this?.records.orEmpty().filterIsInstance<T>()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun local(stamp: String) =
        java.time.LocalDateTime.parse(stamp).atZone(zone).toInstant().toEpochMilli()

    // ─── MyFitnessPal ───────────────────────────────────────────────────────────────────────────

    /** The real header of a real export, plus the optional Time column half of them carry. */
    private val mfpNutrition = "File-Export-2024-01-01-to-2024-12-31/Nutrition-Summary-2024-01-01-to-2024-12-31.csv" to
        """
        Date,Meal,Calories,Fat (g),Saturated Fat,Polyunsaturated Fat,Monounsaturated Fat,Trans Fat,Cholesterol,Sodium (mg),Potassium,Carbohydrates (g),Fiber,Sugar,Protein (g),Vitamin A,Vitamin C,Calcium,Iron,Note
        2024-07-02,Breakfast,518.0,29.0,7.9,0.0,0.0,0.0,0.0,574.5,740.0,36.7,2.1,14.1,27.6,0.0,0.0,0.0,0.0,
        """

    private val mfpExercise = "File-Export-2024-01-01-to-2024-12-31/Exercise-Summary-2024-01-01-to-2024-12-31.csv" to
        """
        Date,Exercise,Type,Exercise Calories,Exercise Minutes,Sets,Reps Per Set,Pounds,Steps,Note
        2024-07-03,"""" + "\"\"Walking, 3.5 mph, brisk pace\"\"" + """",Cardio,178.0,18,,,,,
        2024-07-04,Health Connect calorie adjustment,Cardio,53.0,1,,,,8452,
        """

    /** A file claimed by its marker and found not to be JSON is counted, not passed over in silence. */
    @Test
    fun `a claimed document that is not JSON is counted as skipped`() {
        val archive = archive("garmin/summarizedActivities.json" to """[{"summarizedActivitiesExport": [""")

        val exports = readHealthExports({ archive.inputStream() }, "export.zip", zone, imperial = false)

        assertEquals(1, exports.skipped)
        assertEquals(listOf(HealthExportApp.GARMIN), exports.files.map { it.app })
        assertTrue(exports.files.single().records.isEmpty())
    }

    /** An archive the walk gave up on is said to be cut short, not reported as read whole. */
    @Test
    fun `an archive the walk gave up on is said to be cut short`() {
        val archive = archive(*(0..ENTRY_LIMIT).map { "$it.txt" to "" }.toTypedArray())

        val exports = readHealthExports({ archive.inputStream() }, "export.zip", zone, imperial = false)

        assertTrue(exports.cutShort)
        // Not as a file skipped for its size: nothing in it was too large, there was too much of it.
        assertEquals(0, exports.skipped)
        assertTrue(exports.files.isEmpty())
    }

    @Test
    fun myFitnessPalIsRecognisedAndItsMealsBecomeFood() {
        val file = read(mfpNutrition)

        assertEquals(HealthExportApp.MYFITNESSPAL, file?.app)
        val meal = file.of<NutritionRecord>().single()
        assertEquals("Breakfast", meal.title)
        assertEquals(518.0, meal.calories, 0.01)
        assertEquals(27.6, meal.proteinG, 0.01)
        assertEquals(29.0, meal.fatG, 0.01)
        assertEquals(36.7, meal.carbsG, 0.01)
        assertEquals(574.5, meal.sodiumMg!!, 0.01)
    }

    /**
     * An account set to kilojoules exports `Kilojoules` where a kilocalorie account exports
     * `Calories`. Matched on the first spelling alone, every such diary imports as nought.
     */
    @Test
    fun aKilojouleAccountIsReadAndConverted() {
        val file = read(
            "File-Export-a-to-b/Nutrition-Summary-a-to-b.csv" to
                """
                Date,Meal,Time,Kilojoules,Fat (g),Carbohydrates (g),Protein (g)
                2024-07-02,Breakfast,8:30 AM,2167,29.0,36.7,27.6
                """,
        )

        assertEquals(HealthExportApp.MYFITNESSPAL, file?.app)
        assertEquals(518.0, file.of<NutritionRecord>().single().calories, 1.0)
    }

    /**
     * The file states a date and a clock and no zone, so the instant depends on where the phone is;
     * the marker names the wall clock, and the same meal read in Prague or in New York is one row.
     */
    @Test
    fun aMealFromADateAndAClockIsNamedTheSameInEveryZone() {
        val newYork = ZoneId.of("America/New_York")
        fun markersIn(where: ZoneId) = readHealthExport(archive(mfpNutrition), "export.zip", where, false)!!
            .rows(where, testTitles()).entries.map { it.healthConnectRecordKey }

        assertEquals(markersIn(zone), markersIn(newYork))
        assertTrue(markersIn(zone).single()!!.endsWith(":2024-07-02T120000"))
    }

    /** A row filed under the epoch instant is recognised under its wall-clock name, in the zone it was read in. */
    @Test
    fun aHealthRowFiledUnderTheInstantIsStillHeld() {
        val utc = ZoneId.of("UTC")

        assertEquals(
            "File import:MyFitnessPal:eat:Breakfast:Eggs:1970-01-01T000000",
            "File import:MyFitnessPal:eat:Breakfast:Eggs:0".withWallClockStampLast(utc),
        )
        assertNull("File import:MyFitnessPal:eat:Breakfast:Eggs:1970-01-01T000000".withWallClockStampLast(utc))
        assertNull("File import:Hevy:0:Bench Press".withWallClockStampLast(utc))
        assertNull("Health Connect:com.x:uuid".withWallClockStampLast(utc))
    }

    /** The optional Time column sits between Meal and the energy, and places the row in the day. */
    @Test
    fun theOptionalTimeColumnPlacesTheMeal() {
        val file = read(
            "File-Export-a-to-b/Nutrition-Summary-a-to-b.csv" to
                """
                Date,Meal,Time,Calories,Fat (g),Carbohydrates (g),Protein (g)
                2024-07-02,Breakfast,8:30 AM,518,29.0,36.7,27.6
                """,
        )

        assertEquals(local("2024-07-02T08:30:00"), file.of<NutritionRecord>().single().atMs)
    }

    /**
     * The exercise names keep literal quote characters after correct CSV unescaping — two thousand
     * of two and a half thousand rows in one real export — and the adjustment rows are not workouts.
     */
    @Test
    fun theQuotesComeOffAndTheAdjustmentRowIsNotAWorkout() {
        val file = read(mfpExercise)

        val workout = file.of<WorkoutRecord>().single()
        assertEquals("Walking, 3.5 mph, brisk pace", workout.name)
        assertEquals(18 * 60, workout.seconds)
        assertEquals(178.0, workout.calories!!, 0.01)
    }

    /**
     * The steps on those adjustment rows came *out* of Health Connect. Importing them into an app
     * that reads Health Connect would be importing a number from itself.
     */
    @Test
    fun theStepsColumnIsNeverImported() {
        assertTrue(read(mfpExercise).of<StepDayRecord>().isEmpty())
    }

    /** Every one of the three files is optional; a person may have exported one of them. */
    @Test
    fun oneFileOnItsOwnIsStillMyFitnessPal() {
        assertEquals(HealthExportApp.MYFITNESSPAL, read(mfpExercise)?.app)
    }

    /**
     * The weight file states no unit anywhere, and the exercise file names it in a column heading.
     * Getting it wrong is not a rounding error but a history 2.2 times too heavy.
     */
    @Test
    fun theWeightUnitIsBorrowedFromTheExerciseFile() {
        val pounds = read(
            "File-Export-a-to-b/Measurement-Summary-a-to-b.csv" to
                """
                Date,Weight
                2024-07-02,195.0
                """,
            mfpExercise,
        )

        assertEquals(88.45, pounds.of<WeightRecord>().single().kg, 0.01)
    }

    @Test
    fun withoutThatColumnTheProfilesOwnUnitDecides() {
        val metric = read(
            "File-Export-a-to-b/Measurement-Summary-a-to-b.csv" to
                """
                Date,Waist,Weight
                2024-07-02,,85.5
                """,
        )

        assertEquals(85.5, metric.of<WeightRecord>().single().kg, 0.01)
    }

    /**
     * MyFitnessPal writes its `Note` column unquoted, so a note holding a line break splits one
     * record into two and the second begins with the rest of a sentence where the date should be.
     */
    @Test
    fun aNoteThatBrokeItsRowDoesNotBecomeAMeal() {
        val file = read(
            "File-Export-a-to-b/Nutrition-Summary-a-to-b.csv" to
                """
                Date,Meal,Calories,Fat (g),Carbohydrates (g),Protein (g),Note
                2024-07-02,Breakfast,518,29.0,36.7,27.6,felt hungry
                all morning,so ate again,0,0,0,0,
                """,
        )

        assertEquals(listOf("Breakfast"), file.of<NutritionRecord>().map(NutritionRecord::title))
    }

    // ─── Cronometer ─────────────────────────────────────────────────────────────────────────────

    /** `Day,Time,Metric,Unit,Amount` — the long table, and the unit is data rather than a heading. */
    @Test
    fun cronometerBiometricsAreReadRowByRow() {
        val file = read(
            "biometrics.csv" to
                """
                Day,Time,Metric,Unit,Amount
                2026-07-20,07:00,Weight,kg,82.4
                2026-07-20,07:01,Weight (Withings),lbs,181.7
                2026-07-21,09:15,Blood Pressure,mmHg,120/80
                2026-07-21,09:16,Heart Rate,bpm,58
                2026-07-21,09:17,Oxygen Saturation,%,97
                2026-07-21,09:18,Body Fat Percentage,%,18.4
                2026-07-21,09:19,Waist,cm,86
                """,
        )

        assertEquals(HealthExportApp.CRONOMETER, file?.app)
        val weights = file.of<WeightRecord>().sortedBy(WeightRecord::atMs)
        assertEquals(82.4, weights.first().kg, 0.01)
        // 181.7 pounds, because the unit is a cell of this row rather than a heading of the file.
        assertEquals(82.42, weights.last().kg, 0.01)
        val pressure = file.of<VitalRecord>().first { it.vital == Vital.BLOOD_PRESSURE }
        assertEquals(120.0, pressure.value, 0.01)
        assertEquals(80.0, pressure.secondary!!, 0.01)
        assertEquals(58.0, file.of<VitalRecord>().first { it.vital == Vital.HEART_RATE }.value, 0.01)
        assertEquals(97.0, file.of<VitalRecord>().first { it.vital == Vital.OXYGEN_SATURATION }.value, 0.01)
        assertEquals(18.4, file.of<VitalRecord>().first { it.vital == Vital.BODY_FAT }.value, 0.01)
        // A waist has no column in this journal and is skipped rather than guessed into one.
        assertEquals(4, file.of<VitalRecord>().size)
    }

    /** The clock is written twelve-hour in some exports and twenty-four in others. */
    @Test
    fun bothOfCronometersClocksAreRead() {
        val file = read(
            "biometrics.csv" to
                """
                Day,Time,Metric,Unit,Amount
                2026-07-20,07:00,Weight,kg,82.4
                2026-07-21,9:59 AM,Weight,kg,82.0
                2026-07-22,,Weight,kg,81.5
                """,
        )

        val weights = file.of<WeightRecord>().sortedBy(WeightRecord::atMs)
        assertEquals(local("2026-07-20T07:00:00"), weights[0].atMs)
        assertEquals(local("2026-07-21T09:59:00"), weights[1].atMs)
        // No clock at all: filed at midday, where being an hour out cannot move the day.
        assertEquals(local("2026-07-22T12:00:00"), weights[2].atMs)
    }

    /** `Amount` is one free-text cell; only the portions actually stated in grams are a mass. */
    @Test
    fun cronometerServingsKeepTheirMacrosAndOnlyGramPortions() {
        val file = read(
            "servings.csv" to
                """
                Day,Time,Group,Food Name,Amount,Energy (kcal),Caffeine (mg),Carbs (g),Fiber (g),Sugars (g),Fat (g),Cholesterol (mg),Saturated (g),Sodium (mg),Protein (g)
                2026-07-20,9:59 AM,Breakfast,"Cheese Ravioli, with Tomato Sauce","1.00 cup, whole pieces",327.16,0,44.48,2.9,8.51,11.1,28.99,3.21,506.24,13.5
                2026-07-20,1:00 PM,Lunch,Chicken breast,150.00 g,248,0,0,0,0,5.4,90,1.5,74,46.5
                """,
        )

        assertEquals(HealthExportApp.CRONOMETER, file?.app)
        val servings = file.of<NutritionRecord>()
        assertEquals("Cheese Ravioli, with Tomato Sauce", servings.first().title)
        assertEquals("Breakfast", servings.first().meal)
        assertEquals(327.16, servings.first().calories, 0.01)
        assertEquals(0.0, servings.first().servingG, 0.01)
        assertEquals(150.0, servings.last().servingG, 0.01)
        assertEquals(46.5, servings.last().proteinG, 0.01)
    }

    /**
     * An energy-balance row synced from a device carries a negative burn and is not a workout;
     * imported as one it would be a session that burned a negative number of calories.
     */
    @Test
    fun aNegativeEnergyBalanceRowIsNotAWorkout() {
        val file = read(
            "exercises.csv" to
                """
                Day,Time,Exercise,Minutes,Calories Burned
                2026-07-20,7:00 AM,Running,32,410
                2026-07-20,,Active Energy Balance (Apple Health),0,-180
                """,
        )

        val workout = file.of<WorkoutRecord>().single()
        assertEquals("Running", workout.name)
        assertEquals(32 * 60, workout.seconds)
        assertEquals(410.0, workout.calories!!, 0.01)
    }

    /**
     * `dailysummary.csv` is the same food added up. Read alongside the servings it would put every
     * day's meals into the journal twice — once as what was eaten and once as the total of it.
     */
    @Test
    fun theDailyTotalsFileIsNotRead() {
        val file = read(
            "dailysummary.csv" to
                """
                Date,Energy (kcal),Protein (g),Carbs (g),Fat (g),Fiber (g),Completed
                2026-07-20,2100,120,220,70,28,false
                """,
        )

        assertNull(file)
    }

    // ─── Flo ────────────────────────────────────────────────────────────────────────────────────

    /**
     * `period_start_date` looks like a date and is not one: it is the user's local midnight written
     * as a UTC instant, so an Istanbul account exports the 31st of October as `2024-10-30 21:00`.
     * Taking the first ten characters moves every period in the history a day earlier.
     */
    @Test
    fun floDatesAreLocalMidnightsWrittenInUtc() {
        val file = loose(
            "flo.json",
            """
            {"operationalData":{"cycles":[
              {"id":"a","pregnant":false,"period_start_date":"2024-10-30 21:00:00.0","period_end_date":"2024-11-04 21:00:00.0"}
            ]},"sessionsData":{"installations":[{"time_zone":"Europe/Istanbul"}]}}
            """,
        )

        assertEquals(HealthExportApp.FLO, file?.app)
        assertEquals(
            listOf(LocalDate.parse("2024-10-31") to true, LocalDate.parse("2024-11-05") to false),
            file.of<PeriodRecord>().map { it.day to it.start },
        )
    }

    /** Without the session's zone the phone's own has to stand in, which is the ordinary case. */
    @Test
    fun withoutTheSessionsZoneThePhonesOwnIsUsed() {
        val file = loose(
            "flo.json",
            """
            {"operationalData":{"cycles":[
              {"id":"a","pregnant":false,"period_start_date":"2024-10-30 23:00:00.0","period_end_date":null}
            ]}}
            """,
        )

        assertEquals(listOf(LocalDate.parse("2024-10-31")), file.of<PeriodRecord>().map(PeriodRecord::day))
    }

    /** A pregnancy is a cycle row too, and a nine-month period is not a period. */
    @Test
    fun aPregnancyRowIsNotAPeriod() {
        val file = loose(
            "flo.json",
            """
            {"operationalData":{"cycles":[
              {"id":"a","pregnant":true,"period_start_date":"2024-01-01 00:00:00.0","period_end_date":"2024-09-30 00:00:00.0"}
            ]}}
            """,
        )

        assertTrue(file.of<PeriodRecord>().isEmpty())
    }

    // ─── Strava ─────────────────────────────────────────────────────────────────────────────────

    /** The real first block of a real English export, cut down to the columns that are read. */
    @Test
    fun stravaIsReadOutOfItsFirstBlock() {
        val file = read(
            "activities.csv" to
                """
                Activity ID,Activity Date,Activity Name,Activity Type,Activity Description,Elapsed Time,Distance,Max Heart Rate,Relative Effort,Commute,Activity Private Note,Activity Gear,Filename,Athlete Weight,Bike Weight,Elapsed Time,Moving Time,Distance,Calories,Average Heart Rate
                13178383456,"Dec 24, 2024, 1:57:27 AM",Fat man run,Run,,3638,4.82,,,false,,,activities/13178383456.gpx,,,3638.0,3201.0,4828.7,436.0,152
                """,
        )

        assertEquals(HealthExportApp.STRAVA, file?.app)
        val run = file.of<WorkoutRecord>().single()
        assertEquals("Fat man run", run.name)
        assertEquals(3_638, run.seconds)
        assertEquals(4_820.0, run.distanceM!!, 1.0)
        assertEquals(436.0, run.calories!!, 0.1)
        assertEquals(152, run.heartRateBpm)
        // The type rides beside the title, and the row is filed as the run it says it is.
        assertEquals("Run", run.type)
        assertEquals("RUN", file!!.rows(zone, testTitles()).entries.single().activityCode)
    }

    /**
     * Strava translates its headings and its activity types into the account's language, so nothing
     * can be matched by name. The file is known by the route path beside it, and read by position.
     */
    @Test
    fun aGermanStravaExportIsStillRead() {
        val file = read(
            "activities.csv" to
                """
                Aktivitäts-ID,Aktivitätsdatum,Name der Aktivität,Aktivitätsart,Beschreibung,Verstrichene Zeit,Distanz,Max. Herzfrequenz,Relative Anstrengung,Pendeln,Notiz,Ausrüstung,Dateiname
                11091356418,"02.04.2024, 14:05:15",Zugfahrt nach Hause,Radfahrt,,6465,"23,02",,,false,,,activities/11091356418.gpx
                """,
        )

        assertEquals(HealthExportApp.STRAVA, file?.app)
        val ride = file.of<WorkoutRecord>().single()
        assertEquals("Zugfahrt nach Hause", ride.name)
        assertEquals(6_465, ride.seconds)
        // `23,02` is a comma decimal point in a German export, not a thousands separator.
        assertEquals(23_020.0, ride.distanceM!!, 1.0)
    }

    /** `4,808` and `4.808` are both four thousand eight hundred and eight. */
    @Test
    fun aThousandsGroupIsNotAFraction() {
        val file = read(
            "activities.csv" to
                """
                Activity ID,Activity Date,Activity Name,Activity Type,Description,Elapsed Time,Distance,Max Heart Rate,X,Y,Z,W,Filename
                1,"Dec 24, 2024, 1:57:27 AM",Long ride,Ride,,7200,"1,234",,,,,,activities/1.gpx
                """,
        )

        assertEquals(1_234_000.0, file.of<WorkoutRecord>().single().distanceM!!, 1.0)
    }

    /** A loose CSV called `activities.csv` with no route path is left alone rather than guessed at. */
    @Test
    fun somebodyElsesActivitiesCsvIsNotClaimed() {
        assertNull(
            loose(
                "activities.csv",
                """
                name,date,note
                Reading,2026-01-01,two chapters
                """,
            ),
        )
    }

    // ─── Nike Run Club ──────────────────────────────────────────────────────────────────────────

    private val nikeTcx = """
        <?xml version='1.0' encoding='UTF-8'?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:nax="https://www.nike.com/xmlschemas/NikeActivityExtension/v1">
        <Activities><Activity Sport="Running"><Id>2021-03-07T12:25:02Z</Id>
        <Lap StartTime="2021-03-07T12:25:02Z"><TotalTimeSeconds>1395.313</TotalTimeSeconds><DistanceMeters>5012.4</DistanceMeters>
        <Calories>320</Calories><AverageHeartRateBpm><Value>148</Value></AverageHeartRateBpm>
        <MaximumHeartRateBpm><Value>176</Value></MaximumHeartRateBpm>
        <Track><Trackpoint><Time>2021-03-07T12:25:03Z</Time><DistanceMeters>3.1</DistanceMeters></Trackpoint>
        <Trackpoint><Time>2021-03-07T12:25:04Z</Time><DistanceMeters>3.4</DistanceMeters></Trackpoint></Track>
        </Lap></Activity></Activities></TrainingCenterDatabase>
    """

    /**
     * The lap summary, and nothing below it. Nike writes each trackpoint's distance as the
     * *increment* where the format says cumulative, so the last one is metres rather than the run.
     */
    @Test
    fun nikeReadsTheLapAndStopsAtTheTrack() {
        val file = read("00a774ac-5e39-4eb4-912c-622553ff2abc.tcx" to nikeTcx)

        assertEquals(HealthExportApp.NIKE_RUN_CLUB, file?.app)
        val run = file.of<WorkoutRecord>().single()
        assertEquals("Running", run.name)
        assertEquals(at("2021-03-07T12:25:02Z"), run.startMs)
        assertEquals(1_395, run.seconds)
        assertEquals(5_012.4, run.distanceM!!, 0.1)
        assertEquals(320.0, run.calories!!, 0.1)
        // The average and not the maximum, which is the next `Value` in the file.
        assertEquals(148, run.heartRateBpm)
    }

    /** The newer serialiser puts every scalar on its own line surrounded by whitespace. */
    @Test
    fun theNewerPrettyPrintedShapeReadsTheSame() {
        val file = read(
            "a.tcx" to
                """
                <TrainingCenterDatabase xmlns:nax="https://www.nike.com/xmlschemas/NikeActivityExtension/v1">
                  <Activities>
                    <Activity Sport="Running">
                      <Lap StartTime="2024-08-01T03:14:15Z">
                        <TotalTimeSeconds>
                          1395.313
                        </TotalTimeSeconds>
                        <Calories>
                          301
                        </Calories>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """,
        )

        val run = file.of<WorkoutRecord>().single()
        assertEquals(1_395, run.seconds)
        assertEquals(301.0, run.calories!!, 0.1)
        // A newer export states no lap distance at all, and none is invented from the samples.
        assertNull(run.distanceM)
    }

    // ─── Garmin ─────────────────────────────────────────────────────────────────────────────────

    /** Every root in a Garmin export is a bare array, and hydration is interleaved with the days. */
    @Test
    fun garminDailyStepsSkipTheHydrationRowsBesideThem() {
        val file = read(
            "DI_CONNECT/DI-Connect-Aggregator/UDSFile_2025-12-01_2025-12-31.json" to
                """
                [{"calendarDate":"2025-12-24","totalSteps":7767,"activeKilocalories":537.0,"restingHeartRate":52},
                 {"hydration":{"valueInML":0,"goalInML":2000}},
                 {"calendarDate":"2025-12-25","totalSteps":0,"currentDayRestingHeartRate":54}]
                """,
        )

        assertEquals(HealthExportApp.GARMIN, file?.app)
        assertEquals(
            listOf(LocalDate.parse("2025-12-24") to 7_767L),
            file.of<StepDayRecord>().map { it.day to it.steps },
        )
        assertEquals(listOf(52.0, 54.0), file.of<VitalRecord>().map(VitalRecord::value).sorted())
        // Both are the resting rate Garmin worked out, not readings of the day's pulse.
        assertEquals(setOf(Vital.RESTING_HEART_RATE), file.of<VitalRecord>().map(VitalRecord::vital).toSet())
    }

    /**
     * `sleepStartTimestampGMT` is written two incompatible ways across the years of one account's
     * own export: as epoch milliseconds, and as an ISO-like GMT string with no zone marker.
     */
    @Test
    fun bothOfGarminsSleepStampsAreRead() {
        val file = read(
            "DI_CONNECT/DI-Connect-Wellness/2019-01-01_2019-12-31_1_sleepData.json" to
                """
                [{"calendarDate":"2019-08-05","sleepStartTimestampGMT":"2019-08-04T20:28:00.0","sleepEndTimestampGMT":"2019-08-05T04:12:00.0"},
                 {"calendarDate":"2019-08-06","sleepStartTimestampGMT":1565035680000,"sleepEndTimestampGMT":1565063520000}]
                """,
        )

        val nights = file.of<SleepRecord>()
        assertEquals(2, nights.size)
        assertEquals(at("2019-08-04T20:28:00Z"), nights.first().startMs)
        assertEquals(1_565_035_680_000L, nights.last().startMs)
    }

    /** The activities file is an array of objects each holding one array of activities. */
    @Test
    fun garminActivitiesAreTwoArraysDeep() {
        val file = read(
            "DI_CONNECT/DI-Connect-Fitness/someone@example.com_0_summarizedActivities.json" to
                """
                [{"summarizedActivitiesExport":[
                  {"activityId":1,"name":"Morning Run","activityType":"running","startTimeGmt":1740000000000,
                   "duration":1830.0,"distance":5200.0,"calories":420.0,"avgHr":151},
                  {"activityId":2,"activityType":"indoor_cycling","startTimeGmt":1740100000000,"duration":2700.0}
                ]}]
                """,
        )

        val sessions = file.of<WorkoutRecord>()
        assertEquals(listOf("Morning Run", "indoor cycling"), sessions.map(WorkoutRecord::name))
        // Garmin's own word for the session rides beside the title, whatever the title says.
        assertEquals(listOf("running", "indoor cycling"), sessions.map(WorkoutRecord::type))
        assertEquals(1_830, sessions.first().seconds)
        assertEquals(5_200.0, sessions.first().distanceM!!, 0.1)
        assertEquals(151, sessions.first().heartRateBpm)
    }

    /**
     * The outer `weight` key holds an object whose own `weight` member is the grams; reading the
     * outer key as a number is the mistake this file exists to catch. Snapshots repeat, so the
     * instant is what makes a weighing distinct, and a nought means "not measured".
     */
    @Test
    fun garminWeightsAreNestedAndDeduplicated() {
        val file = read(
            "DI_CONNECT/DI-Connect-Wellness/1_userBioMetrics.json" to
                """
                [{"weight":{"weight":78400,"bodyFat":18.2,"bmi":23.9,"timestampGMT":"2025-03-11T06:41:00.0"},"metaData":{"calendarDate":"2025-03-11"},"userSetNullForWeight":false},
                 {"weight":{"weight":78400,"bodyFat":18.2,"bmi":23.9,"timestampGMT":"2025-03-11T06:41:00.0"},"metaData":{"calendarDate":"2025-03-12"},"userSetNullForWeight":false},
                 {"weight":{"weight":78100,"bodyFat":0.0,"timestampGMT":"2025-03-18T06:40:00.0"},"metaData":{"calendarDate":"2025-03-18"},"userSetNullForWeight":false}]
                """,
        )

        val weights = file.of<WeightRecord>()
        assertEquals(2, weights.size)
        assertEquals(78.4, weights.first().kg, 0.01)
        assertEquals(18.2, weights.first().bodyFatPercent!!, 0.01)
        assertNull(weights.last().bodyFatPercent)
    }

    // ─── the archive itself ─────────────────────────────────────────────────────────────────────

    /** Nothing in the archive belongs to anybody, and that is an answer rather than an empty import. */
    @Test
    fun anArchiveNobodyClaimsIsNotAnEmptyHistory() {
        assertNull(read("holiday/notes.txt" to "milk, bread"))
    }

    /**
     * The per-sample dumps are skipped by name before they are held or parsed. A Fitbit heart-rate
     * stream is gigabytes of the same telemetry the daily files beside it already state.
     */
    @Test
    fun theRawTelemetryIsSkippedRatherThanRead() {
        val file = read(
            "Takeout/Fitbit/Physical Activity_GoogleData/heart_rate_2026-03-26.csv" to
                """
                timestamp,beats per minute,data source
                2026-03-26T17:50:52Z,70.0,Inspire 3
                """,
        )

        assertNull(file)
    }

    /**
     * The version line Samsung writes above its header is dropped by counting *cells*, not commas.
     *
     * A Strava row states its date as `"Dec 24, 2024, 1:57:27 AM"` and its distance as `"1,234"` —
     * three commas that are not columns — which by raw separators would make the row wider than the
     * header above it and throw Strava's header away as though it were a Samsung prefix.
     */
    @Test
    fun aQuotedCommaDoesNotMakeAHeaderLookLikeAPrefix() {
        val file = read(
            "activities.csv" to
                """
                Activity ID,Activity Date,Activity Name,Activity Type,Description,Elapsed Time,Distance,Max Heart Rate
                1,"Dec 24, 2024, 1:57:27 AM",Long ride,Ride,,7200,"1,234",
                """,
        )

        assertEquals(HealthExportApp.STRAVA, file?.app)
        assertEquals("Long ride", file.of<WorkoutRecord>().single().name)
    }

    /** A file handed over on its own, unzipped on a desktop first, reads exactly as it would inside. */
    @Test
    fun aLooseFileReadsTheSameAsOneInsideAnArchive() {
        val inside = read("anything/biometrics.csv" to CRONOMETER_ONE_ROW)
        val alone = loose("biometrics.csv", CRONOMETER_ONE_ROW)

        assertNotNull(alone)
        assertEquals(inside?.app, alone?.app)
        assertEquals(inside.of<WeightRecord>().size, alone.of<WeightRecord>().size)
    }

    private companion object {
        const val CRONOMETER_ONE_ROW = """
            Day,Time,Metric,Unit,Amount
            2026-07-20,07:00,Weight,kg,82.4
        """
    }
}
