package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a file does not say outright and the readers have to settle for it: which zone a clock with
 * no offset was read in — settled by naming the row by that clock, so that the same file opened
 * after a move names the same rows — and which unit a bare figure counts in.
 */
class ImportZonesAndUnitsTest {
    private val prague = ZoneId.of("Europe/Prague")
    private val newYork = ZoneId.of("America/New_York")

    private fun markersIn(where: ZoneId, name: String, text: String) =
        readHealthExport(text.trimIndent().toByteArray(), name, where, imperial = false)!!
            .rows(where, testTitles()).entries.map { it.healthConnectRecordKey.orEmpty() }

    // ─── zones ──────────────────────────────────────────────────────────────────────────────────

    /** Withings dates a weighing, a pressure and a body fat by a clock with no zone beside it. */
    @Test
    fun withingsRowsAreNamedTheSameInEveryZone() {
        val files = listOf(
            "weight.csv" to
                """
                Date,"Weight (kg)","Fat mass (kg)","Bone mass (kg)","Muscle mass (kg)","Hydration (kg)",Comments
                "2025-03-30 12:09:03",73.5,19.9,3.7,46.9,30.5,
                """,
            "bp.csv" to
                """
                Date,"Heart rate",Systolic,Diastolic,Comments
                "2025-03-03 15:35:37",63,128,80,
                """,
            "other.csv" to
                """
                type,date,value
                FAT_PERCENT,"2025-03-30 12:09:03",23
                """,
        )

        files.forEach { (name, text) ->
            assertEquals(name, markersIn(prague, name, text), markersIn(newYork, name, text))
        }
        val (weightFile, pressureFile) = files
        assertEquals(
            listOf("File import:Withings:weight:2025-03-30T120903"),
            markersIn(prague, weightFile.first, weightFile.second),
        )
        // A pressure and the pulse the cuff took with it.
        assertEquals(2, markersIn(prague, pressureFile.first, pressureFile.second).size)
    }

    /** The pressure in Cronometer's long table comes from a day and a clock, like the rows around it. */
    @Test
    fun aCronometerPressureIsNamedTheSameInEveryZone() {
        val biometrics = """
            Day,Time,Metric,Unit,Amount
            2026-03-01,08:15,Blood Pressure,mmHg,120/80
        """

        val here = markersIn(prague, "biometrics.csv", biometrics)

        assertEquals(here, markersIn(newYork, "biometrics.csv", biometrics))
        assertEquals(listOf("File import:Cronometer:blood_pressure:2026-03-01T081500"), here)
    }

    /** A Samsung row with no offset beside it is a wall clock; one with its offset is the instant it states. */
    @Test
    fun aSamsungRowWithNoOffsetIsNamedByItsWallClock() {
        val name = "com.samsung.health.weight.20251024070440.csv"
        val weights = """
            com.samsung.health.weight,7006003,4
            start_time,weight,time_offset,body_fat,muscle_mass,total_body_water,pkg_name,datauuid
            2024-07-04 12:51:00.592,72.0,,24.6,30.2,40.1,com.sec.android.app.shealth,a
            2024-07-05 12:51:00.592,72.5,UTC+0200,24.6,30.2,40.1,com.sec.android.app.shealth,b
        """

        val here = markersIn(prague, name, weights)

        assertEquals(here, markersIn(newYork, name, weights))
        assertEquals("File import:Samsung Health:weight:2024-07-04T125100", here.first())
        assertTrue(here.last().substringAfterLast(':').all(Char::isDigit))
    }

    /** wger states its offset, so its weighing is one instant everywhere; a bare date is a wall clock. */
    @Test
    fun aWgerWeighingIsNamedTheSameInEveryZone() {
        fun weighingsIn(where: ZoneId, text: String) =
            importedJournalEntries(readWorkoutExport(text, where, false)!!, where, false, "Weight")
                .map { it.healthConnectRecordKey }
        val stated = "Date,Weight\n2026-01-02 06:11:00+00:00,83.40\n"
        val bare = "Date,Weight\n2022-11-03,83.40\n"

        assertEquals(weighingsIn(prague, stated), weighingsIn(newYork, stated))
        assertEquals(weighingsIn(prague, bare), weighingsIn(newYork, bare))
        assertEquals(listOf("File import:wger:2022-11-03T120000:weight"), weighingsIn(prague, bare))
    }

    /** Every marker one archive entry gives, read through [where]; sorted, as a day's order is not the question. */
    private fun archiveMarkersIn(where: ZoneId, name: String, text: String): List<String> {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.trimIndent().toByteArray())
            zip.closeEntry()
        }
        return readHealthExports({ out.toByteArray().inputStream() }, "export.zip", where, imperial = false)
            .files.flatMap { it.rows(where, testTitles()).entries }
            .map { it.healthConnectRecordKey.orEmpty() }
            .sorted()
    }

    /**
     * One real file of every reader the tests above do not already move: a stamp that states its
     * zone names its instant, a day or a clock that does not names itself, and either way the file
     * opened after a move names the rows it named before. A reader added or changed without saying
     * which kind of stamp it read lands its whole history a second time, and is caught here.
     */
    @Test
    fun everyOtherReadersRowsAreNamedTheSameInEveryZone() {
        everyOtherReader.forEach { (name, text) ->
            val here = archiveMarkersIn(prague, name, text)
            assertTrue(name, here.isNotEmpty())
            assertEquals(name, here, archiveMarkersIn(newYork, name, text))
        }
    }

    private val everyOtherReader = listOf(
        "Takeout/Fitbit/Physical Activity_GoogleData/weight.csv" to
            """
            timestamp,weight grams,data source
            2026-03-19T18:21:01Z,82400,Fitbit App
            """,
        "Takeout/Fitbit/Health Fitness Data_GoogleData/UserSleeps_2026-04-01.csv" to
            """
            sleep_id,sleep_type,start_utc_offset,sleep_start,end_utc_offset,sleep_end,data_source,algorithm_version
            1,STAGES,-04:00,2026-04-15 08:00:00+0000,-04:00,2026-04-15 12:00:00+0000,DERIVED,sleep_session_v2
            """,
        "Takeout/Fitbit/Global Export Data/exercise-0.json" to
            """
            [{"logId":1,"activityName":"Walk","activityTypeId":90013,"duration":1320000,"startTime":"01/06/23 01:08:57"}]
            """,
        "Takeout/Fitbit/Global Export Data/water_logs-0.json" to
            """
            [{"id":104171868,"date":"02/05/10","waterAmount":22.0,"measurementUnit":"FL_OZ"}]
            """,
        "Takeout/Fitbit/Global Export Data/food_logs-0.json" to
            """
            [{"logId":1,"loggedFood":{"name":"Porridge","brand":"","calories":180,"mealTypeId":1},"logDate":"2026-03-19"}]
            """,
        "Takeout/Fitbit/Menstrual Health/menstrual_health_cycles.csv" to
            """
            id,cycle_start_date,cycle_end_date,ovulation_start_date,ovulation_end_date,ovulation_source,period_start_date,period_end_date,period_source,fertile_start_date,fertile_end_date,fertile_source
            1,2026-03-01,2026-03-28,,,,2026-03-01,2026-03-05,manual,,,
            """,
        "oura_trends.csv" to
            """
            date,Sleep Score,Sleep Timin Score,Total Sleep Duration,Total Bedtime ,Bedtime Start,Bedtime End,Average Resting Heart Rate,Lowest Resting Heart Rate,Average HRV,Temperature Deviation (°C),Steps,Equivalent Walking Distance
            2023-12-26,77,90,27150,30844,2023-12-25T23:03:01.000+02:00,2023-12-26T07:37:05.000+02:00,42.97,39.0,115,-0.07,7719,6003
            """,
        "DI_CONNECT/DI-Connect-Aggregator/UDSFile_2025-12-01_2025-12-31.json" to
            """
            [{"calendarDate":"2025-12-24","totalSteps":7767,"restingHeartRate":52}]
            """,
        "DI_CONNECT/DI-Connect-Wellness/2019-01-01_2019-12-31_1_sleepData.json" to
            """
            [{"calendarDate":"2019-08-05","sleepStartTimestampGMT":"2019-08-04T20:28:00.0","sleepEndTimestampGMT":"2019-08-05T04:12:00.0"}]
            """,
        "DI_CONNECT/DI-Connect-Fitness/someone@example.com_0_summarizedActivities.json" to
            """
            [{"summarizedActivitiesExport":[{"activityId":1,"name":"Morning Run","activityType":"running","startTimeGmt":1740000000000,"duration":1830.0}]}]
            """,
        "DI_CONNECT/DI-Connect-Wellness/1_userBioMetrics.json" to
            """
            [{"weight":{"weight":78400,"timestampGMT":"2025-03-11T06:41:00.0"},"metaData":{"calendarDate":"2025-03-11"},"userSetNullForWeight":false}]
            """,
        "activities.csv" to
            """
            Activity ID,Activity Date,Activity Name,Activity Type,Activity Description,Elapsed Time,Distance,Max Heart Rate,Relative Effort,Commute,Activity Private Note,Activity Gear,Filename,Athlete Weight,Bike Weight,Elapsed Time,Moving Time,Distance,Calories,Average Heart Rate
            13178383456,"Dec 24, 2024, 1:57:27 AM",Fat man run,Run,,3638,4.82,,,false,,,activities/13178383456.gpx,,,3638.0,3201.0,4828.7,436.0,152
            """,
        "00a774ac-5e39-4eb4-912c-622553ff2abc.tcx" to
            """
            <TrainingCenterDatabase xmlns:nax="https://www.nike.com/xmlschemas/NikeActivityExtension/v1">
            <Activities><Activity Sport="Running"><Lap StartTime="2021-03-07T12:25:02Z">
            <TotalTimeSeconds>1395.313</TotalTimeSeconds><Calories>320</Calories></Lap></Activity></Activities>
            </TrainingCenterDatabase>
            """,
        "servings.csv" to
            """
            Day,Time,Group,Food Name,Amount,Energy (kcal),Protein (g)
            2026-07-20,1:00 PM,Lunch,Chicken breast,150.00 g,248,46.5
            2026-07-21,,Dinner,Soup,300.00 g,210,9
            """,
        "exercises.csv" to
            """
            Day,Time,Exercise,Minutes,Calories Burned
            2026-07-20,7:00 AM,Running,32,410
            """,
        "flo.json" to
            """
            {"operationalData":{"cycles":[
              {"id":"a","pregnant":false,"period_start_date":"2024-10-30 21:00:00.0","period_end_date":"2024-11-04 21:00:00.0"}
            ]},"sessionsData":{"installations":[{"time_zone":"Europe/Istanbul"}]}}
            """,
        "Takeout/Fit/Daily activity metrics/Daily Activity Metrics.csv" to
            """
            Date,Move Minutes count,Calories (kcal),Distance (m),Heart Points,Heart Minutes,Average heart rate (bpm),Max heart rate (bpm),Min heart rate (bpm),Step count
            2026-03-19,72,2410.5,6842.1,31,22,68.4,142,52,9312
            """,
        "com.samsung.shealth.sleep.20251024070440.csv" to
            """
            com.samsung.shealth.sleep,7006003,11
            original_efficiency,has_sleep_data,com.samsung.health.sleep.start_time,com.samsung.health.sleep.end_time,com.samsung.health.sleep.time_offset,com.samsung.health.sleep.datauuid
            89.0,1,2019-09-30 23:06:30.484,2019-10-01 06:17:18.485,UTC+0530,a10b
            """,
        "com.samsung.shealth.exercise.20251024070440.csv" to
            """
            com.samsung.shealth.exercise,7006003,15
            total_calorie,title,com.samsung.health.exercise.start_time,com.samsung.health.exercise.end_time,com.samsung.health.exercise.time_offset,com.samsung.health.exercise.calorie,com.samsung.health.exercise.distance
            416.54092,Evening run,2019-10-07 15:02:16.380,2019-10-07 15:21:43.000,UTC+0530,416.375,4200.0
            """,
        "apple_health_export/export.xml" to
            """
            <HealthData locale="en_GB">
             <Record type="HKQuantityTypeIdentifierBodyMass" unit="kg" startDate="2026-03-01 07:00:00 +0100" value="72"/>
             <Record type="HKQuantityTypeIdentifierRestingHeartRate" unit="count/min" startDate="2026-03-01 07:00:00 +0100" value="54"/>
             <Record type="HKQuantityTypeIdentifierDietaryWater" unit="mL" startDate="2026-03-01 08:00:00 +0100" value="250"/>
            </HealthData>
            """,
    )

    // ─── units ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A Garmin file in milliseconds and centimetres is read as one. Asked figure by figure, the
     * two-minute session would stay thirty-three hours and the five-kilometre run five hundred and twenty.
     */
    @Test
    fun aGarminFileIsReadInTheUnitsItsMiddleSessionShows() {
        val fine = garminUnits(listOf(1_830_000.0 to 520_000.0, 120_000.0 to 30_000.0, 2_700_000.0 to null))

        assertEquals(1_000.0, fine.perSecond, 0.0)
        assertEquals(100.0, fine.perMetre, 0.0)

        // Seconds and metres, with one session nobody ended: the outlier does not turn the file.
        val plain = garminUnits(listOf(1_830.0 to 5_200.0, 2_700.0 to null, 400_000.0 to 9_000_000.0))

        assertEquals(1.0, plain.perSecond, 0.0)
        assertEquals(1.0, plain.perMetre, 0.0)
        assertEquals(1.0, garminUnits(emptyList()).perSecond, 0.0)
    }

    @Test
    fun aGarminRunInCentimetresIsNotAHundredTimesTooLong() {
        val activities = """
            [{"summarizedActivitiesExport":[{"name":"Run","activityType":"running","startTimeGmt":1740000000000,
              "duration":1830000.0,"distance":520000.0}]}]
        """.trimIndent()

        val run = readHealthExport(activities.toByteArray(), "summarizedActivities.json", prague, imperial = false)!!
            .records.filterIsInstance<WorkoutRecord>().single()

        assertEquals(1_830, run.seconds)
        assertEquals(5_200.0, run.distanceM!!, 0.1)
    }

    /**
     * Withings' `other.csv` names no unit for a temperature. One in Fahrenheit taken for Celsius
     * would be found to be no body's temperature and dropped without a word.
     */
    @Test
    fun aWithingsTemperatureInFahrenheitIsReadAsOne() {
        val other = """
            type,date,value
            TEMPERATURE,"2025-03-30 12:09:03",98.6
            BODY_TEMPERATURE,"2025-03-31 12:09:03",36.8
        """.trimIndent()

        val temperatures = readHealthExport(other.toByteArray(), "other.csv", prague, imperial = false)!!
            .records.filterIsInstance<VitalRecord>().map(VitalRecord::value)

        assertEquals(2, temperatures.size)
        assertEquals(37.0, temperatures[0], 0.01)
        assertEquals(36.8, temperatures[1], 0.01)
    }
}
