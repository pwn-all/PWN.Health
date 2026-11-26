package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fitbit, Oura, Withings, Samsung Health and Google's own daily table.
 *
 * These four archives are the ones that hold a wearable's whole telemetry, and every test here is
 * about the same thing under different names: taking the day's figure and leaving the stream that
 * produced it where it is.
 */
class WearableExportReadersTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun read(vararg entries: Pair<String, String>, zone: ZoneId = this.zone): HealthImportFile? {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        return readHealthExport(out.toByteArray(), "export.zip", zone, imperial = false)
    }

    private inline fun <reified T> HealthImportFile?.of(): List<T> =
        this?.records.orEmpty().filterIsInstance<T>()

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    // ─── Fitbit ─────────────────────────────────────────────────────────────────────────────────

    /** `timestamp,steps,data source`, and a minute at a time — the heading really has a space in it. */
    @Test
    fun fitbitStepsAreTotalledPerDayAndPerDevice() {
        val file = read(
            "Takeout/Fitbit/Physical Activity_GoogleData/steps_2026-03-01.csv" to
                """
                timestamp,steps,data source
                2026-03-19T08:48:00Z,13,Inspire 3
                2026-03-19T09:10:00Z,87,Inspire 3
                2026-03-19T08:48:00Z,400,Pixel Watch
                2026-03-20T10:00:00Z,50,Inspire 3
                """,
        )

        assertEquals(HealthExportApp.FITBIT, file?.app)
        assertEquals(
            mapOf(LocalDate.parse("2026-03-19") to 400L, LocalDate.parse("2026-03-20") to 50L),
            file.of<StepDayRecord>().associate { it.day to it.steps },
        )
    }

    @Test
    fun fitbitJoinsOneTrackersDayAcrossFilesAndKeepsOnlyOneCopyOfEachSample() {
        val file = read(
            "steps_2026-03.csv" to """
                timestamp,steps,data source
                2026-03-31T23:57:00Z,36,Inspire 3
                2026-03-31T23:57:00Z,70,Pixel Watch
            """,
            "steps_2026-04.csv" to """
                timestamp,steps,data source
                2026-04-01T00:04:00Z,60,Inspire 3
                2026-03-31T23:57:00Z,35,Inspire 3
            """,
            zone = ZoneOffset.ofHours(2),
        )

        assertEquals(listOf(StepDayRecord(LocalDate.parse("2026-04-01"), 96L)), file.of<StepDayRecord>())
    }

    /** One weight file for the whole history, and the mass in grams with microseconds on the stamp. */
    @Test
    fun fitbitWeightsAreGrams() {
        val file = read(
            "Takeout/Fitbit/Physical Activity_GoogleData/weight.csv" to
                """
                timestamp,weight grams,data source
                2026-03-19T18:21:01.602062Z,47627,Fitbit App
                2026-03-20T18:21:01Z,82400,
                """,
        )

        assertEquals(listOf(47.627, 82.4), file.of<WeightRecord>().map { it.kg })
    }

    /**
     * The one file in the archive that states an offset, and it states it with a space where the
     * ISO shape wants a `T` and no colon inside the offset.
     */
    @Test
    fun fitbitSleepIsReadThroughItsOwnOffset() {
        val file = read(
            "Takeout/Fitbit/Health Fitness Data_GoogleData/UserSleeps_2026-04-01.csv" to
                """
                sleep_id,sleep_type,minutes_in_sleep_period,minutes_after_wake_up,minutes_to_fall_asleep,minutes_asleep,minutes_awake,minutes_longest_awakening,minutes_to_persistent_sleep,start_utc_offset,sleep_start,end_utc_offset,sleep_end,data_source,algorithm_version,sleep_created,sleep_last_updated
                1274863277733400080,STAGES,414,0,0,258,155,0,0,-04:00,2026-04-12 05:45:30+0000,-04:00,2026-04-12 12:39:30+0000,DERIVED,sleep_session_v1,2026-04-12 12:43:36+0000,2026-04-12 12:43:37+0000
                """,
        )

        val night = file.of<SleepRecord>().single()
        assertEquals(at("2026-04-12T05:45:30Z"), night.startMs)
        assertEquals(at("2026-04-12T12:39:30Z"), night.endMs)
    }

    /**
     * The resting pulse file holds every day of the account's life, and an unmeasured day is written
     * as a nought rather than left out. Read at face value, every day off the wrist is a pulse of 0.
     */
    @Test
    fun anUnmeasuredRestingPulseIsNotAPulseOfNought() {
        val file = read(
            "Takeout/Fitbit/Global Export Data/resting_heart_rate-2026-03-18.json" to
                """
                [{"dateTime":"03/18/26 00:00:00","value":{"date":null,"value":0.0,"error":0.0}},
                 {"dateTime":"03/19/26 00:00:00","value":{"date":"03/19/26","value":58.43,"error":6.5}}]
                """,
        )

        val pulse = file.of<VitalRecord>().single()
        // The resting rate, which is a vital of its own and not one more reading of the pulse.
        assertEquals(Vital.RESTING_HEART_RATE, pulse.vital)
        assertEquals(58.43, pulse.value, 0.01)
    }

    /**
     * `dateTime` in the resting-pulse file is the day at nought hours, and the daily saturation is
     * stamped the same way. Read as the UTC instants they spell, both would sit on the evening before
     * in New York. A history filed under those instants is known by the name the day has now.
     */
    @Test
    fun fitbitsDailyFiguresAreTheSameDayWestOfGreenwich() {
        val newYork = ZoneId.of("America/New_York")
        val file = read(
            "Takeout/Fitbit/Global Export Data/resting_heart_rate-2026-03-18.json" to
                """
                [{"dateTime":"03/19/26 00:00:00","value":{"date":"03/19/26","value":58.43,"error":6.5}}]
                """,
            "Takeout/Fitbit/Oxygen Saturation (SpO2)/Daily SpO2 - 2026-04-06.csv" to
                """
                timestamp,average_value,lower_bound,upper_bound
                2026-04-06T00:00:00Z,90.7,87.3,93.6
                """,
            zone = newYork,
        )

        val rows = file!!.rows(newYork, testTitles()).entries
        assertEquals(listOf("2026-03-19", "2026-04-06"), rows.map(JournalEntry::day).sorted())
        val filedUnderTheInstant = listOf(
            "File import:Fitbit:heart_rate:${at("2026-03-19T00:00:00Z")}",
            "File import:Fitbit:oxygen_saturation:${at("2026-04-06T00:00:00Z")}",
        )
        assertEquals(
            rows.map(JournalEntry::healthConnectRecordKey).toSet(),
            filedUnderTheInstant.map { it.withFitbitDayStamp() }.toSet(),
        )
        // Only a day's figure: not a stamp with a clock in it, another kind of row or another service.
        assertNull("File import:Fitbit:heart_rate:${at("2026-03-19T00:00:01Z")}".withFitbitDayStamp())
        assertNull("File import:Fitbit:heart_rate:2026-03-19T120000".withFitbitDayStamp())
        assertNull("File import:Fitbit:weight:${at("2026-03-19T00:00:00Z")}".withFitbitDayStamp())
        assertNull("File import:Withings:heart_rate:${at("2026-03-19T00:00:00Z")}".withFitbitDayStamp())
    }

    /**
     * Fitbit's `MM/DD/YY HH:MM:SS` is UTC, despite carrying neither a `Z` nor an offset.
     *
     * Proven by the archive against itself: a weighing's `logId` is epoch milliseconds, and
     * `1666223999000` — `2022-10-19T23:59:59Z` — sits in a record whose own date and time read
     * `10/19/22` and `23:59:59`. Read as local time this session would move by the phone's offset,
     * which for a day either side of midnight moves it to the wrong day.
     */
    @Test
    fun fitbitsOwnStampIsUtcAndNotLocalWallTime() {
        val file = read(
            "Takeout/Fitbit/Global Export Data/exercise-0.json" to
                """
                [{"logId":1,"activityName":"Walk","activityTypeId":90013,"duration":600000,
                  "startTime":"01/06/23 01:08:57"}]
                """,
        )

        assertEquals(at("2023-01-06T01:08:57Z"), file.of<WorkoutRecord>().single().startMs)
    }

    /** An American date on two digits of year. */
    @Test
    fun fitbitSessionsAndWaterAreRead() {
        val file = read(
            "Takeout/Fitbit/Global Export Data/exercise-0.json" to
                """
                [{"logId":76131899133,"activityName":"Walk","activityTypeId":90013,"averageHeartRate":97,
                  "calories":143,"distance":1.62,"distanceUnit":"Kilometer","duration":1320000,
                  "startTime":"01/06/23 01:08:57"}]
                """,
            "Takeout/Fitbit/Global Export Data/water_logs-0.json" to
                """
                [{"id":104171868,"date":"02/05/10","waterAmount":22.0,"measurementUnit":"FL_OZ"}]
                """,
        )

        val walk = file.of<WorkoutRecord>().single()
        assertEquals("Walk", walk.name)
        assertEquals(1_320, walk.seconds)
        assertEquals(1_620.0, walk.distanceM!!, 1.0)
        assertEquals(97, walk.heartRateBpm)
        assertEquals(650.6, file.of<DrinkRecord>().single().amount, 0.5)
    }

    /** The food log states a day and no clock, and half of Fitbit's database states no macros. */
    @Test
    fun fitbitFoodKeepsItsMealAndSurvivesMissingMacros() {
        val file = read(
            "Takeout/Fitbit/Global Export Data/food_logs-0.json" to
                """
                [{"logId":1,"loggedFood":{"name":"Porridge","brand":"","calories":180,"mealTypeId":1},"logDate":"2026-03-19"},
                 {"logId":2,"loggedFood":{"name":"Ravioli","brand":"Barilla","calories":0,"mealTypeId":5},
                  "nutritionalValues":{"calories":327,"fat":11.1,"carbs":44.5,"protein":13.5,"fiber":2.9,"sodium":506},
                  "logDate":"2026-03-19"}]
                """,
        )

        val food = file.of<NutritionRecord>()
        assertEquals(listOf("Porridge", "Barilla Ravioli"), food.map(NutritionRecord::title))
        assertEquals(listOf(NumberedMeal.BREAKFAST, NumberedMeal.DINNER), food.map(NutritionRecord::numberedMeal))
        assertEquals(180.0, food.first().calories, 0.1)
        assertEquals(0.0, food.first().proteinG, 0.1)
        assertEquals(327.0, food.last().calories, 0.1)
        assertEquals(13.5, food.last().proteinG, 0.1)
    }

    /**
     * Fitbit re-scores a night when its algorithm changes and keeps both readings, overlapping and
     * under different ids. They are one night; imported as two, nearly every night appears twice.
     */
    @Test
    fun aNightReScoredByASecondAlgorithmIsStillOneNight() {
        val file = read(
            "Takeout/Fitbit/Health Fitness Data_GoogleData/UserSleeps_2026-04-01.csv" to
                """
                sleep_id,sleep_type,start_utc_offset,sleep_start,end_utc_offset,sleep_end,data_source,algorithm_version
                4971719851132807736,STAGES,-04:00,2026-04-14 08:26:30+0000,-04:00,2026-04-14 13:41:30+0000,DERIVED,sleep_session_v1
                5320748961735403720,STAGES,-04:00,2026-04-14 08:38:00+0000,-04:00,2026-04-14 13:36:00+0000,DERIVED,sleep_session_v2
                1111111111111111111,STAGES,-04:00,2026-04-15 08:00:00+0000,-04:00,2026-04-15 12:00:00+0000,DERIVED,sleep_session_v2
                """,
        )

        val nights = file.of<SleepRecord>()
        assertEquals(2, nights.size)
        // The outer of the overlapping pair, which is when the person would say they went to bed.
        assertEquals(at("2026-04-14T08:26:30Z"), nights.first().startMs)
    }

    /** The daily saturation file, because a seventh of the minute rows are a clamped error value. */
    @Test
    fun fitbitSaturationComesFromTheDailyFile() {
        val file = read(
            "Takeout/Fitbit/Oxygen Saturation (SpO2)/Daily SpO2 - 2026-04-06.csv" to
                """
                timestamp,average_value,lower_bound,upper_bound
                2026-04-06T00:00:00Z,90.7,87.3,93.6
                """,
        )

        assertEquals(90.7, file.of<VitalRecord>().single().value, 0.01)
    }

    @Test
    fun fitbitCyclesBecomePeriodBoundaries() {
        val file = read(
            "Takeout/Fitbit/Menstrual Health/menstrual_health_cycles.csv" to
                """
                id,cycle_start_date,cycle_end_date,ovulation_start_date,ovulation_end_date,ovulation_source,period_start_date,period_end_date,period_source,fertile_start_date,fertile_end_date,fertile_source
                1,2026-03-01,2026-03-28,,,,2026-03-01,2026-03-05,manual,,,
                """,
        )

        assertEquals(
            listOf(LocalDate.parse("2026-03-01") to true, LocalDate.parse("2026-03-05") to false),
            file.of<PeriodRecord>().map { it.day to it.start },
        )
    }

    // ─── Oura ───────────────────────────────────────────────────────────────────────────────────

    /**
     * One row a day, and the columns come out in a different order in every export — so everything
     * is by name. `Sleep Timin Score` really is missing its `g`, in every export since 2021.
     */
    @Test
    fun ouraGivesANightAStepCountAndARestingPulse() {
        val file = read(
            "oura_2026-01-01_2026-07-01_trends.csv" to
                """
                date,Sleep Score,Sleep Timin Score,Total Sleep Duration,Total Bedtime ,Bedtime Start,Bedtime End,Average Resting Heart Rate,Lowest Resting Heart Rate,Average HRV,Temperature Deviation (°C),Steps,Equivalent Walking Distance
                2023-12-26,77,90,27150,30844,2023-12-25T23:03:01.000+02:00,2023-12-26T07:37:05.000+02:00,42.97,39.0,115,-0.07,7719,6003
                """,
        )

        assertEquals(HealthExportApp.OURA, file?.app)
        val night = file.of<SleepRecord>().single()
        assertEquals(at("2023-12-25T21:03:01Z"), night.startMs)
        assertEquals(at("2023-12-26T05:37:05Z"), night.endMs)
        assertEquals(7_719L, file.of<StepDayRecord>().single().steps)
        // The lowest resting figure, which is the one Oura's own screens lead with.
        assertEquals(39.0, file.of<VitalRecord>().single().value, 0.01)
        assertEquals(Vital.RESTING_HEART_RATE, file.of<VitalRecord>().single().vital)
    }

    /** The older export writes its bedtimes without milliseconds, and is most of anybody's history. */
    @Test
    fun theLegacyOuraStampIsReadToo() {
        val file = read(
            "oura_trends.csv" to
                """
                date,Sleep Score,Total Sleep Duration,Bedtime Start,Bedtime End,Average Resting Heart Rate,Steps
                2018-12-27,92,36390,2018-12-26T23:08:19+02:00,2018-12-27T09:35:19+02:00,72.23,4404
                """,
        )

        assertEquals(at("2018-12-26T21:08:19Z"), file.of<SleepRecord>().single().startMs)
    }

    /**
     * Oura states a temperature *deviation* from the wearer's own baseline — around a tenth of a
     * degree, signed. Stored as a body temperature it would be a hypothermic reading every night.
     */
    @Test
    fun theTemperatureDeviationIsNotABodyTemperature() {
        val file = read(
            "oura_trends.csv" to
                """
                date,Sleep Score,Total Sleep Duration,Bedtime Start,Bedtime End,Temperature Deviation (°C),Steps
                2023-12-26,77,27150,2023-12-25T23:03:01.000+02:00,2023-12-26T07:37:05.000+02:00,-0.07,7719
                """,
        )

        assertTrue(file.of<VitalRecord>().none { it.vital == Vital.BODY_TEMPERATURE })
    }

    // ─── Withings ───────────────────────────────────────────────────────────────────────────────

    /** Every mass is kilograms whatever the account displays: the `(kg)` survives translation. */
    @Test
    fun withingsWeightsAreReadByPosition() {
        val file = read(
            "weight.csv" to
                """
                Date,"Weight (kg)","Fat mass (kg)","Bone mass (kg)","Muscle mass (kg)","Hydration (kg)",Comments
                "2025-03-30 12:09:03",73.5,19.9,3.7,46.9,30.5,
                """,
        )

        assertEquals(HealthExportApp.WITHINGS, file?.app)
        val weighing = file.of<WeightRecord>().single()
        assertEquals(73.5, weighing.kg, 0.01)
        assertEquals(46.9, weighing.muscleMassKg!!, 0.01)
        assertEquals(30.5, weighing.bodyWaterMassKg!!, 0.01)
    }

    /** A German export translates every heading and moves no column. */
    @Test
    fun aGermanWithingsExportReadsTheSame() {
        val file = read(
            "weight.csv" to
                """
                Date,"Gewicht (kg)","Fettmasse (kg)","Knochenmasse (kg)","Muskelmasse (kg)","Wasseranteil (kg)",Kommentare
                "2025-03-30 12:09:03",73.5,19.9,3.7,46.9,30.5,
                """,
        )

        assertEquals(73.5, file.of<WeightRecord>().single().kg, 0.01)
    }

    /**
     * The same file on an American account states pounds in its heading, and the archive's own
     * README still says kilograms. Read as kilograms, the whole history lands 2.2 times too heavy.
     */
    @Test
    fun anImperialWithingsAccountIsNotReadAsKilograms() {
        val file = read(
            "weight.csv" to
                """
                Date,"Weight (lb)","Fat mass (lb)","Bone mass (lb)","Muscle mass (lb)","Hydration (lb)",Comments
                "2025-03-30 12:09:03",173.5,43.9,8.2,103.4,67.3,
                """,
        )

        val weighing = file.of<WeightRecord>().single()
        assertEquals(78.7, weighing.kg, 0.1)
        assertEquals(46.9, weighing.muscleMassKg!!, 0.1)
    }

    /**
     * A row of `bp.csv` can be a pulse with no pressure at all — that is a weighing on a scale that
     * also takes a pulse — so the two are separate readings rather than one with holes in it.
     */
    @Test
    fun aPulseWithoutAPressureIsStillAPulse() {
        val file = read(
            "bp.csv" to
                """
                Date,"Heart rate",Systolic,Diastolic,Comments
                "2025-03-03 15:35:37",63,128,80,
                "2025-03-21 09:39:37",73,,,
                """,
        )

        val pressure = file.of<VitalRecord>().filter { it.vital == Vital.BLOOD_PRESSURE }
        val pulses = file.of<VitalRecord>().filter { it.vital == Vital.HEART_RATE }
        assertEquals(1, pressure.size)
        assertEquals(128.0, pressure.single().value, 0.01)
        assertEquals(80.0, pressure.single().secondary!!, 0.01)
        assertEquals(listOf(63.0, 73.0), pulses.map(VitalRecord::value).sorted())
    }

    @Test
    fun withingsDailyStepsAndNightsAndBodyFat() {
        val file = read(
            "aggregates_steps.csv" to
                """
                date,value
                2025-03-30,10607
                """,
            "sleep.csv" to
                """
                from,to,"light (s)","deep (s)","rem (s)","awake (s)","wake up"
                2013-09-02T22:25:10-07:00,2013-09-03T08:20:14-07:00,17400,10140,0,8160,4
                """,
            "other.csv" to
                """
                type,date,value
                FAT_PERCENT,"2025-03-30 12:09:03",23
                SPO2_AUTO,"2025-03-30 12:09:03",0.97
                """,
        )

        assertEquals(10_607L, file.of<StepDayRecord>().single().steps)
        assertEquals(at("2013-09-03T05:25:10Z"), file.of<SleepRecord>().single().startMs)
        assertEquals(23.0, file.of<VitalRecord>().first { it.vital == Vital.BODY_FAT }.value, 0.01)
        // Withings has written a saturation both ways; only one of them is a percentage.
        assertEquals(97.0, file.of<VitalRecord>().first { it.vital == Vital.OXYGEN_SATURATION }.value, 0.01)
    }

    /** The thirty intraday series are the same walk run-length encoded, and one is twenty megabytes. */
    @Test
    fun theWithingsRawSeriesAreSkipped() {
        assertNull(
            read(
                "raw_tracker_steps.csv" to
                    """
                    start,duration,value
                    2025-03-31T05:38:48+02:00,[163],[72]
                    """,
            ),
        )
    }

    // ─── Samsung Health ─────────────────────────────────────────────────────────────────────────

    /**
     * Every Samsung file opens with a version line above its header, and every data row ends with a
     * trailing comma so it has one more field than the header has columns.
     */
    @Test
    fun samsungStepsSurviveTheVersionLineAndBothDayFormats() {
        val file = read(
            "com.samsung.shealth.step_daily_trend.20251024070440.csv" to
                """
                com.samsung.shealth.step_daily_trend,7006003,6
                binning_data,update_time,create_time,source_pkg_name,source_type,count,speed,distance,calorie,deviceuuid,pkg_name,datauuid,day_time
                a.json,2025-05-23 01:19:00.290,2025-05-23 01:19:00.290,com.sec.android.app.shealth,0,2438,2.89,1972.3,90.1,w4d,com.sec.android.app.shealth,d1,2025-05-23 00:00:00.000,
                b.json,2025-05-23 01:19:00.290,2025-05-23 01:19:00.290,com.samsung.android.wear,0,5120,2.89,4102.1,190.1,ab2,com.sec.android.app.shealth,d2,2025-05-23 00:00:00.000,
                c.json,2021-06-02 01:19:00.290,2021-06-02 01:19:00.290,com.sec.android.app.shealth,0,3100,2.89,2400.0,110.0,w4d,com.sec.android.app.shealth,d3,1622592000000,
                """,
        )

        assertEquals(HealthExportApp.SAMSUNG_HEALTH, file?.app)
        val days = file.of<StepDayRecord>().associate { it.day to it.steps }
        // Two devices counted the same day; the busier one is the day's answer, not their sum.
        assertEquals(5_120L, days[LocalDate.parse("2025-05-23")])
        assertEquals(3_100L, days[LocalDate.parse("2021-06-02")])
    }

    /**
     * Samsung merges its own devices for us and marks the result -2. Where those rows exist they
     * are the answer, and the per-device rows beside them are their parts — added in, a day counted
     * by a phone and a watch would come out as the sum of the two.
     */
    @Test
    fun samsungsOwnMergedRowWinsOverThePartsBesideIt() {
        val file = read(
            "com.samsung.shealth.step_daily_trend.20251024070440.csv" to
                """
                com.samsung.shealth.step_daily_trend,7006003,6
                binning_data,update_time,create_time,source_pkg_name,source_type,count,speed,distance,calorie,deviceuuid,pkg_name,datauuid,day_time
                a.json,2025-05-23 01:19:00.290,2025-05-23 01:19:00.290,com.sec.android.app.shealth,0,2438,2.89,1972.3,90.1,w4d,com.sec.android.app.shealth,d1,2025-05-23 00:00:00.000,
                b.json,2025-05-23 01:19:00.290,2025-05-23 01:19:00.290,com.samsung.android.wear,0,5120,2.89,4102.1,190.1,ab2,com.sec.android.app.shealth,d2,2025-05-23 00:00:00.000,
                c.json,2025-05-23 01:19:00.290,2025-05-23 01:19:00.290,com.sec.android.app.shealth,-2,6001,2.89,4800.0,210.0,VfS,com.sec.android.app.shealth,d3,2025-05-23 00:00:00.000,
                """,
        )

        assertEquals(6_001L, file.of<StepDayRecord>().single().steps)
    }

    /** A Samsung stamp is local wall clock with the offset spelled `UTC+0530` in a column beside it. */
    @Test
    fun samsungReadsItsOwnOffsetSpelling() {
        val file = read(
            "com.samsung.health.weight.20251024070440.csv" to
                """
                com.samsung.health.weight,7006003,4
                body_fat_mass,create_sh_ver,start_time,custom,height,weight,muscle_mass,modify_sh_ver,update_time,create_time,client_data_id,skeletal_muscle,fat_free_mass,client_data_ver,basal_metabolic_rate,time_offset,deviceuuid,skeletal_muscle_mass,comment,fat_free,pkg_name,body_fat,datauuid,vfa_level,total_body_water
                17.7,62670031,2024-07-04 12:51:00.592,,170.0,72.0,30.2,62670031,2024-07-04 12:51:00.646,2024-07-04 12:51:00.646,,39.4,54.2,,1541,UTC+0200,ooO,28.4,,75.3,com.sec.android.app.shealth,24.6,uuid,8,40.1,
                """,
        )

        val weighing = file.of<WeightRecord>().single()
        assertEquals(at("2024-07-04T10:51:00Z"), weighing.atMs)
        assertEquals(72.0, weighing.kg, 0.01)
        // `body_fat` is the percentage; `body_fat_mass` beside it is the kilograms of it.
        assertEquals(24.6, weighing.bodyFatPercent!!, 0.01)
        assertEquals(30.2, weighing.muscleMassKg!!, 0.01)
        assertEquals(40.1, weighing.bodyWaterMassKg!!, 0.01)
    }

    @Test
    fun samsungSleepAndFoodAreRead() {
        val file = read(
            "com.samsung.shealth.sleep.20251024070440.csv" to
                """
                com.samsung.shealth.sleep,7006003,11
                original_efficiency,has_sleep_data,com.samsung.health.sleep.start_time,com.samsung.health.sleep.end_time,com.samsung.health.sleep.time_offset,com.samsung.health.sleep.datauuid
                89.0,1,2019-09-30 23:06:30.484,2019-10-01 06:17:18.485,UTC+0530,a10b
                """,
            "com.samsung.health.food_intake.20251024070440.csv" to
                """
                com.samsung.health.food_intake,6302031,5
                create_sh_ver,start_time,amount,custom,modify_sh_ver,update_time,create_time,meal_type,client_data_id,name,unit,client_data_ver,calorie,time_offset,deviceuuid,comment,pkg_name,datauuid,food_info_id
                62900750,2025-01-28 18:11:09.529,500.0,,62900750,2025-01-28 18:11:52.244,2025-01-28 18:11:52.244,100001,,Slim Skimmed Milk(Nandini),g,120005,175.0,UTC+0530,EmO,,com.sec.android.app.shealth,340fc,f1
                """,
        )

        assertEquals(at("2019-09-30T17:36:30Z"), file.of<SleepRecord>().single().startMs)
        val food = file.of<NutritionRecord>().single()
        assertEquals("Slim Skimmed Milk(Nandini)", food.title)
        assertEquals(NumberedMeal.BREAKFAST, food.numberedMeal)
        assertEquals(175.0, food.calories, 0.1)
        assertEquals(500.0, food.servingG, 0.1)
    }

    /** A spot pulse and a ten-minute window share the file and state the figure in the same column. */
    @Test
    fun samsungPulsesAreRead() {
        val file = read(
            "com.samsung.shealth.tracker.heart_rate.20251024070440.csv" to
                """
                com.samsung.shealth.tracker.heart_rate,62910051,1
                source,tag_id,com.samsung.health.heart_rate.start_time,com.samsung.health.heart_rate.time_offset,com.samsung.health.heart_rate.max,com.samsung.health.heart_rate.min,com.samsung.health.heart_rate.heart_rate
                ,21313,2025-10-21 21:00:00.000,UTC+0200,88.0,73.0,79.0
                """,
        )

        assertEquals(79.0, file.of<VitalRecord>().single().value, 0.01)
    }

    /**
     * A watch measuring all day writes a window every ten minutes, and a row for each would be the
     * raw stream the import says it leaves behind. The day's windows are one figure, their average
     * at the day's midday; a reading taken by hand, which states no minimum or maximum, is its own.
     */
    @Test
    fun aDaysPulseWindowsAreOneFigureAndASpotReadingIsItsOwn() {
        val heading = "source,tag_id,com.samsung.health.heart_rate.start_time," +
            "com.samsung.health.heart_rate.time_offset,com.samsung.health.heart_rate.max," +
            "com.samsung.health.heart_rate.min,com.samsung.health.heart_rate.heart_rate"
        val windows = (0 until 144).map { n ->
            val clock = "${(n / 6).toString().padStart(2, '0')}:${(n % 6 * 10).toString().padStart(2, '0')}"
            ",21313,2025-10-21 $clock:00.000,UTC+0200,90.0,55.0,${if (n % 2 == 0) 60 else 80}.0"
        }
        val spot = ",21301,2025-10-21 08:05:00.000,UTC+0200,0.0,0.0,72.0"
        val file = read(
            "com.samsung.shealth.tracker.heart_rate.20251024070440.csv" to
                (listOf("com.samsung.shealth.tracker.heart_rate,62910051,1", heading) + windows + spot)
                    .joinToString("\n"),
        )

        val pulses = file.of<VitalRecord>()
        assertEquals(2, pulses.size)
        val day = pulses.single { it.wallClock }
        assertEquals(70.0, day.value, 0.01)
        assertEquals(LocalDate.parse("2025-10-21").atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), day.atMs)
        assertEquals(at("2025-10-21T06:05:00Z"), pulses.single { !it.wallClock }.atMs)
    }

    /**
     * `total_calorie` and the exercise's own `calorie` disagree on about a third of real rows, with
     * the first always the larger. The session's own column is the one the app itself shows.
     */
    @Test
    fun samsungTakesTheSessionsOwnEnergyRatherThanTheTotalBesideIt() {
        val file = read(
            "com.samsung.shealth.exercise.20251024070440.csv" to
                """
                com.samsung.shealth.exercise,7006003,15
                total_calorie,title,com.samsung.health.exercise.start_time,com.samsung.health.exercise.end_time,com.samsung.health.exercise.time_offset,com.samsung.health.exercise.calorie,com.samsung.health.exercise.distance
                416.54092,Evening run,2019-10-07 15:02:16.380,2019-10-07 15:21:43.000,UTC+0530,416.375,4200.0
                """,
        )

        val session = file.of<WorkoutRecord>().single()
        assertEquals("Evening run", session.name)
        assertEquals(416.375, session.calories!!, 0.001)
    }

    // ─── Google Health ──────────────────────────────────────────────────────────────────────────

    /** Google Fit's Takeout table, whose every column carries its unit in its heading. */
    @Test
    fun googlesDailyTableGivesStepsAndAPulse() {
        val file = read(
            "Takeout/Fit/Daily activity metrics/Daily Activity Metrics.csv" to
                """
                Date,Move Minutes count,Calories (kcal),Distance (m),Heart Points,Heart Minutes,Average heart rate (bpm),Max heart rate (bpm),Min heart rate (bpm),Step count
                2026-03-19,72,2410.5,6842.1,31,22,68.4,142,52,9312
                """,
        )

        assertEquals(HealthExportApp.GOOGLE_HEALTH, file?.app)
        assertEquals(9_312L, file.of<StepDayRecord>().single().steps)
        assertEquals(68.4, file.of<VitalRecord>().single().value, 0.01)
    }

    /**
     * The numeric `day_time` is the day as UTC midnight. Read through a zone west of Greenwich it
     * would still be the evening before, and an export opened in New York would put every day on the
     * one before.
     */
    @Test
    fun samsungsNumericDayIsTheSameDayWestOfGreenwich() {
        val file = read(
            "com.samsung.shealth.step_daily_trend.20251024070440.csv" to
                """
                com.samsung.shealth.step_daily_trend,7006003,6
                binning_data,update_time,create_time,source_pkg_name,source_type,count,speed,distance,calorie,deviceuuid,pkg_name,datauuid,day_time
                c.json,2021-06-02 01:19:00.290,2021-06-02 01:19:00.290,com.sec.android.app.shealth,0,3100,2.89,2400.0,110.0,w4d,com.sec.android.app.shealth,d3,1622592000000,
                """,
            zone = ZoneId.of("America/New_York"),
        )

        assertEquals(LocalDate.parse("2021-06-02"), file.of<StepDayRecord>().single().day)
    }

    /** Oura and Withings write an offset in place of the `Z`; it names the same instant. */
    @Test
    fun anOffsetStampIsTheInstantItNames() {
        assertEquals(at("2023-12-25T21:03:01Z"), tcxInstantMs("2023-12-25T23:03:01.000+02:00"))
        assertEquals(at("2013-09-03T05:25:10Z"), tcxInstantMs("2013-09-02T22:25:10-07:00"))
        assertEquals(at("2024-01-02T03:04:05Z"), tcxInstantMs("2024-01-02T03:04:05Z"))
        assertNull(tcxInstantMs("yesterday"))
    }
}
