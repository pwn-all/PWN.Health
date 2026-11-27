package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.ZoneId

/**
 * The reader for `export.xml`, which is the only road out of an iPhone.
 *
 * Every fragment below is the real shape of a real export — the attribute names are the ones the
 * published DTD declares, and the awkward ones (a non-breaking space written as a number, a
 * `WorkoutStatistics` child that is not in the DTD at all, a sleep value spelled the pre-watchOS-9
 * way) are here because they are in real files. That matters more than usual: the whole feature is
 * a claim about Apple's file, and a test written against XML this app invented would pass forever
 * while the import read nothing.
 */
class AppleHealthImportTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun read(xml: String) = readAppleHealthXml(StringReader(xml.trimIndent()), zone).records

    private inline fun <reified T> records(xml: String): List<T> = read(xml).filterIsInstance<T>()

    // ─── the scanner ────────────────────────────────────────────────────────────────────────────

    /**
     * The DOCTYPE Apple writes is an internal subset holding every element declaration, and its
     * `>` characters are inside it. Nothing in there is a record and nothing may become one.
     */
    @Test
    fun theDoctypeIsNotMistakenForRecords() {
        val tags = mutableListOf<String>()
        scanAppleHealth(
            StringReader(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE HealthData [
                <!ELEMENT HealthData (ExportDate,Me,(Record|Workout)*)>
                <!ATTLIST HealthData locale CDATA #REQUIRED>
                <!ELEMENT Record (MetadataEntry*)>
                ]>
                <HealthData locale="en_GB">
                 <Record type="X" startDate="2026-01-01 00:00:00 +0100" value="1"/>
                </HealthData>
                """.trimIndent(),
            ),
            setOf("Record"),
        ) { tag, _ ->
            tags += tag
            true
        }

        assertEquals(listOf("Record"), tags)
    }

    /**
     * The `device` attribute is one Objective-C description string with angle brackets, colons and
     * commas inside it. A scanner that ended the tag at the first `>` would lose the rest of it.
     */
    @Test
    fun anAngleBracketInsideAnAttributeDoesNotEndTheTag() {
        val found = mutableListOf<AppleElement>()
        scanAppleHealth(
            StringReader(
                """<Record type="HKQuantityTypeIdentifierStepCount" """ +
                    """device="&lt;&lt;HKDevice: 0x283&gt;, name:Apple Watch, model:Watch&gt;" """ +
                    """startDate="2026-01-01 08:00:00 +0100" value="12"/>""",
            ),
            setOf("Record"),
        ) { _, element ->
            found += element
            true
        }

        assertEquals("12", found.single()["value"])
        assertTrue(found.single()["device"]!!.contains("name:Apple Watch"))
    }

    /** An export carries `sourceName="Apple&#160;Watch"`: the space is a number. */
    @Test
    fun aNumericEntityBecomesItsCharacter() {
        val found = mutableListOf<AppleElement>()
        scanAppleHealth(
            StringReader("""<Record sourceName="Apple&#160;Watch" type="X" startDate="a"/>"""),
            setOf("Record"),
        ) { _, element ->
            found += element
            true
        }

        assertEquals("Apple Watch", found.single()["sourceName"])
    }

    /** `Wife &amp; kids walk` is a real workout name; the five named entities have to come back. */
    @Test
    fun theNamedEntitiesComeBack() {
        val found = mutableListOf<AppleElement>()
        scanAppleHealth(
            StringReader("""<Workout workoutActivityType="A &amp; B &lt;x&gt; &quot;y&quot;"/>"""),
            setOf("Workout"),
        ) { _, element ->
            found += element
            true
        }

        assertEquals("A & B <x> \"y\"", found.single()["workoutActivityType"])
    }

    /** A `<Record>` with `<MetadataEntry>` children must not have them read as records. */
    @Test
    fun childElementsAreSkippedRatherThanRead() {
        val weights = records<WeightRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBodyMass" unit="kg" startDate="2026-01-02 09:44:00 +0100" value="82">
              <MetadataEntry key="HKWasUserEntered" value="1"/>
            </Record>
            """,
        )

        assertEquals(1, weights.size)
        assertEquals(82.0, weights.single().kg, 0.001)
    }

    // ─── the stamp ──────────────────────────────────────────────────────────────────────────────

    /** A space instead of a `T`, and an offset with no colon: neither is ISO and both are Apple's. */
    @Test
    fun anAppleStampIsReadThroughItsOwnOffset() {
        assertEquals(
            java.time.Instant.parse("2016-01-02T08:44:00Z").toEpochMilli(),
            appleInstantMs("2016-01-02 09:44:00 +0100"),
        )
    }

    @Test
    fun anAbsentStampIsNothingRatherThanNow() {
        assertNull(appleInstantMs(null))
        assertNull(appleInstantMs(""))
        assertNull(appleInstantMs("2016-01-02T09:44:00+01:00"))
    }

    // ─── steps ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The iPhone in the pocket and the Watch on the wrist both write every step of the same walk,
     * and Apple exports both streams whole. Added together they would roughly double the day.
     */
    @Test
    fun twoDevicesCountingOneWalkAreNotAddedTogether() {
        val days = records<StepDayRecord>(
            """
            <Record type="HKQuantityTypeIdentifierStepCount" sourceName="iPhone" unit="count" startDate="2026-03-01 08:00:00 +0100" value="400"/>
            <Record type="HKQuantityTypeIdentifierStepCount" sourceName="iPhone" unit="count" startDate="2026-03-01 09:00:00 +0100" value="600"/>
            <Record type="HKQuantityTypeIdentifierStepCount" sourceName="Apple Watch" unit="count" startDate="2026-03-01 08:00:00 +0100" value="1500"/>
            """,
        )

        assertEquals(1, days.size)
        assertEquals(1_500L, days.single().steps)
    }

    @Test
    fun eachDayIsCountedOnItsOwn() {
        val days = records<StepDayRecord>(
            """
            <Record type="HKQuantityTypeIdentifierStepCount" sourceName="iPhone" unit="count" startDate="2026-03-01 08:00:00 +0100" value="400"/>
            <Record type="HKQuantityTypeIdentifierStepCount" sourceName="iPhone" unit="count" startDate="2026-03-02 08:00:00 +0100" value="900"/>
            """,
        )

        assertEquals(
            mapOf(
                java.time.LocalDate.parse("2026-03-01") to 400L,
                java.time.LocalDate.parse("2026-03-02") to 900L,
            ),
            days.associate { it.day to it.steps },
        )
    }

    // ─── sleep ──────────────────────────────────────────────────────────────────────────────────

    /** A watch writes a night as dozens of stage fragments; none of them is the night. */
    @Test
    fun theStageFragmentsOfOneNightBecomeOneNight() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepCore" startDate="2026-03-01 23:00:00 +0100" endDate="2026-03-02 01:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepDeep" startDate="2026-03-02 01:00:00 +0100" endDate="2026-03-02 03:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAwake" startDate="2026-03-02 03:00:00 +0100" endDate="2026-03-02 03:10:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepREM" startDate="2026-03-02 03:10:00 +0100" endDate="2026-03-02 06:30:00 +0100"/>
            """,
        )

        val night = nights.single()
        assertEquals(appleInstantMs("2026-03-01 23:00:00 +0100"), night.startMs)
        assertEquals(appleInstantMs("2026-03-02 06:30:00 +0100"), night.endMs)
    }

    /** The nap after lunch is a night of its own, which is what the gap rule is for. */
    @Test
    fun anAfternoonNapIsNotPartOfTheNightBeforeIt() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleep" startDate="2026-03-01 23:00:00 +0100" endDate="2026-03-02 06:30:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleep" startDate="2026-03-02 14:00:00 +0100" endDate="2026-03-02 15:00:00 +0100"/>
            """,
        )

        assertEquals(2, nights.size)
    }

    /** The pre-watchOS-9 spelling is a bare `Asleep`, and it is most of anybody's history. */
    @Test
    fun theOlderSpellingOfAsleepIsStillSleep() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleep" startDate="2018-03-01 23:00:00 +0100" endDate="2018-03-02 06:30:00 +0100"/>
            """,
        )

        assertEquals(1, nights.size)
    }

    /**
     * An iPhone records time in bed and a Watch records sleep, and both are exported. Counted as
     * two nights, one night's sleep would appear twice in the day list.
     */
    @Test
    fun timeInBedIsDroppedWhereTheWatchAlsoRecordedSleep() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisInBed" startDate="2026-03-01 22:30:00 +0100" endDate="2026-03-02 07:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepCore" startDate="2026-03-01 23:00:00 +0100" endDate="2026-03-02 06:30:00 +0100"/>
            """,
        )

        val night = nights.single()
        assertEquals(appleInstantMs("2026-03-01 23:00:00 +0100"), night.startMs)
    }

    /** The years before the watch keep their nights: time in bed goes only where the watch recorded that same night. */
    @Test
    fun timeInBedFromBeforeTheWatchIsStillANight() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisInBed" startDate="2016-03-01 22:30:00 +0100" endDate="2016-03-02 07:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisInBed" startDate="2026-03-01 22:30:00 +0100" endDate="2026-03-02 07:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepCore" startDate="2026-03-01 23:00:00 +0100" endDate="2026-03-02 06:30:00 +0100"/>
            """,
        )

        assertEquals(
            listOf(appleInstantMs("2016-03-01 22:30:00 +0100"), appleInstantMs("2026-03-01 23:00:00 +0100")),
            nights.map { it.startMs },
        )
    }

    /** An older iPhone with no watch recorded nothing else, and time in bed is then the night. */
    @Test
    fun timeInBedIsTheNightWhereNothingElseWasRecorded() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisInBed" startDate="2016-03-01 22:30:00 +0100" endDate="2016-03-02 07:00:00 +0100"/>
            """,
        )

        assertEquals(appleInstantMs("2016-03-01 22:30:00 +0100"), nights.single().startMs)
    }

    /** A ten-minute doze the watch noticed is not a night's sleep. */
    @Test
    fun aFewMinutesIsNotANight() {
        val nights = records<SleepRecord>(
            """
            <Record type="HKCategoryTypeIdentifierSleepAnalysis" value="HKCategoryValueSleepAnalysisAsleepCore" startDate="2026-03-01 14:00:00 +0100" endDate="2026-03-01 14:10:00 +0100"/>
            """,
        )

        assertTrue(nights.isEmpty())
    }

    // ─── blood pressure ─────────────────────────────────────────────────────────────────────────

    /** Apple writes one cuff reading as two sibling records that share only their instant. */
    @Test
    fun theTwoHalvesOfAPressureArePairedOnTheirInstant() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBloodPressureSystolic" sourceName="Omron" unit="mmHg" startDate="2026-03-01 08:00:00 +0100" value="128"/>
            <Record type="HKQuantityTypeIdentifierBloodPressureDiastolic" sourceName="Omron" unit="mmHg" startDate="2026-03-01 08:00:00 +0100" value="80"/>
            """,
        ).filter { it.vital == Vital.BLOOD_PRESSURE }

        assertEquals(128.0, vitals.single().value, 0.001)
        assertEquals(80.0, vitals.single().secondary!!, 0.001)
    }

    /** Half a blood pressure is not a blood pressure, and is not stored as the half that survived. */
    @Test
    fun aSystolicWithNoDiastolicIsNotAReading() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBloodPressureSystolic" sourceName="Omron" unit="mmHg" startDate="2026-03-01 08:00:00 +0100" value="128"/>
            """,
        )

        assertTrue(vitals.isEmpty())
    }

    /** Two cuffs writing inside one instant must not be paired across each other. */
    @Test
    fun twoInstrumentsAtOneInstantAreNotCrossPaired() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBloodPressureSystolic" sourceName="Omron" unit="mmHg" startDate="2026-03-01 08:00:00 +0100" value="128"/>
            <Record type="HKQuantityTypeIdentifierBloodPressureDiastolic" sourceName="Withings" unit="mmHg" startDate="2026-03-01 08:00:00 +0100" value="80"/>
            """,
        )

        assertTrue(vitals.isEmpty())
    }

    // ─── the units Apple states ─────────────────────────────────────────────────────────────────

    @Test
    fun aWeightInPoundsBecomesKilograms() {
        val weights = records<WeightRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBodyMass" unit="lb"
                    startDate="2026-03-01 08:00:00 +0100" value="180"/>
            """,
        )

        assertEquals(81.647, weights.single().kg, 0.01)
    }

    /**
     * HealthKit's own unit for a share of a body is the fraction, printed beside `unit="%"`; a
     * third-party oximeter writes the same unit beside a whole number.
     */
    @Test
    fun aShareWrittenAsAFractionIsReadAsAPercentage() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBodyFatPercentage" unit="%" startDate="2026-03-01 08:00:00 +0100" value="0.184"/>
            <Record type="HKQuantityTypeIdentifierOxygenSaturation" unit="%" startDate="2026-03-01 09:00:00 +0100" value="98"/>
            """,
        )

        assertEquals(18.4, vitals.first { it.vital == Vital.BODY_FAT }.value, 0.01)
        assertEquals(98.0, vitals.first { it.vital == Vital.OXYGEN_SATURATION }.value, 0.01)
    }

    @Test
    fun aTemperatureInFahrenheitBecomesCelsius() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierBodyTemperature" unit="degF"
                    startDate="2026-03-01 08:00:00 +0100" value="98.6"/>
            """,
        )

        assertEquals(37.0, vitals.single().value, 0.05)
    }

    /** A reading outside the vital's own range is an artefact and is dropped, not stored. */
    @Test
    fun anImpossibleReadingIsNotImported() {
        val vitals = records<VitalRecord>(
            """
            <Record type="HKQuantityTypeIdentifierRestingHeartRate" unit="count/min"
                    startDate="2026-03-01 08:00:00 +0100" value="0"/>
            """,
        )
        val entries = HealthImportFile(HealthExportApp.APPLE_HEALTH, vitals).rows(zone, testTitles()).entries

        assertTrue(entries.isEmpty())
    }

    // ─── drinks ─────────────────────────────────────────────────────────────────────────────────

    /** Fifty sips of water are one day's drinking, not fifty rows in a day list. */
    @Test
    fun theDaysWaterIsOneRow() {
        val drinks = records<DrinkRecord>(
            """
            <Record type="HKQuantityTypeIdentifierDietaryWater" unit="L" startDate="2026-03-01 08:00:00 +0100" value="0.25"/>
            <Record type="HKQuantityTypeIdentifierDietaryWater" unit="mL" startDate="2026-03-01 12:00:00 +0100" value="500"/>
            <Record type="HKQuantityTypeIdentifierDietaryCaffeine" unit="mg" startDate="2026-03-01 09:00:00 +0100" value="80"/>
            """,
        )

        assertEquals(750.0, drinks.first { it.kind == KIND_WATER }.amount, 0.01)
        assertEquals(80.0, drinks.first { it.kind == KIND_CAFFEINE }.amount, 0.01)
        // Filed at the first of the day's entries, so it sorts where the drinking started.
        assertEquals(appleInstantMs("2026-03-01 08:00:00 +0100"), drinks.first { it.kind == KIND_WATER }.atMs)
    }

    // ─── workouts ───────────────────────────────────────────────────────────────────────────────

    /** The older shape states everything in attributes, and the duration in decimal minutes. */
    @Test
    fun anOlderWorkoutIsReadOutOfItsAttributes() {
        val workouts = records<WorkoutRecord>(
            """
            <Workout workoutActivityType="HKWorkoutActivityTypeRunning" duration="31.5" durationUnit="min"
                     totalDistance="5.2" totalDistanceUnit="km" totalEnergyBurned="320" totalEnergyBurnedUnit="kcal"
                     startDate="2026-03-01 10:40:38 +0100" endDate="2026-03-01 11:12:22 +0100"/>
            """,
        )

        val run = workouts.single()
        assertEquals("Running", run.name)
        assertEquals(31 * 60 + 30, run.seconds)
        assertEquals(5_200.0, run.distanceM!!, 0.1)
        assertEquals(320.0, run.calories!!, 0.1)
    }

    /**
     * The modern shape leaves those attributes at nought and puts the real figures in children —
     * and spells kilocalories `Cal`. Read only from the attributes, every recent run imports as
     * nought kilometres and nought calories.
     */
    @Test
    fun aModernWorkoutTakesItsFiguresFromItsStatistics() {
        val workouts = records<WorkoutRecord>(
            """
            <Workout workoutActivityType="HKWorkoutActivityTypeRunning" duration="31.5" durationUnit="min"
                     totalDistance="0" totalDistanceUnit="km" totalEnergyBurned="0" totalEnergyBurnedUnit="kcal"
                     startDate="2026-03-01 10:40:38 +0100" endDate="2026-03-01 11:12:22 +0100">
              <WorkoutStatistics type="HKQuantityTypeIdentifierActiveEnergyBurned" startDate="2026-03-01 10:40:38 +0100" startDate="2026-03-01 11:12:22 +0100" sum="317.5" unit="Cal"/>
              <WorkoutStatistics type="HKQuantityTypeIdentifierDistanceWalkingRunning" sum="5.24" unit="km"/>
              <WorkoutStatistics type="HKQuantityTypeIdentifierHeartRate" average="148.2" unit="count/min"/>
            </Workout>
            """,
        )

        val run = workouts.single()
        assertEquals(317.5, run.calories!!, 0.1)
        assertEquals(5_240.0, run.distanceM!!, 1.0)
        assertEquals(148, run.heartRateBpm)
    }

    /** Statistics belong to the session they are inside, and never to the one before it. */
    @Test
    fun statisticsDoNotLeakIntoTheNextWorkout() {
        val workouts = records<WorkoutRecord>(
            """
            <Workout workoutActivityType="HKWorkoutActivityTypeRunning" duration="30" durationUnit="min" startDate="2026-03-01 10:00:00 +0100">
              <WorkoutStatistics type="HKQuantityTypeIdentifierActiveEnergyBurned" sum="300" unit="Cal"/>
            </Workout>
            <Workout workoutActivityType="HKWorkoutActivityTypeWalking" duration="20" durationUnit="min" startDate="2026-03-02 10:00:00 +0100"/>
            """,
        )

        assertEquals(300.0, workouts.first { it.name == "Running" }.calories!!, 0.1)
        assertNull(workouts.first { it.name == "Walking" }.calories)
    }

    /** `HKWorkoutActivityTypeHighIntensityIntervalTraining` is not a title anybody would print. */
    @Test
    fun anActivityTypeIsSplitIntoWords() {
        val workouts = records<WorkoutRecord>(
            """
            <Workout workoutActivityType="HKWorkoutActivityTypeHighIntensityIntervalTraining"
                     duration="20" durationUnit="min" startDate="2026-03-01 10:00:00 +0100"/>
            """,
        )

        assertEquals("High Intensity Interval Training", workouts.single().name)
    }

    // ─── the cycle ──────────────────────────────────────────────────────────────────────────────

    /** A run of flow days is one period; the metadata marking its first day is not always written. */
    @Test
    fun aRunOfFlowDaysBecomesOneStartAndOneEnd() {
        val periods = records<PeriodRecord>(
            """
            <Record type="HKCategoryTypeIdentifierMenstrualFlow" value="HKCategoryValueMenstrualFlowLight" startDate="2026-03-01 12:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierMenstrualFlow" value="HKCategoryValueMenstrualFlowHeavy" startDate="2026-03-02 12:00:00 +0100"/>
            <Record type="HKCategoryTypeIdentifierMenstrualFlow" value="HKCategoryValueMenstrualFlowMedium" startDate="2026-03-03 12:00:00 +0100"/>
            <Record type="HKQuantityTypeIdentifierStepCount" startDate="2026-03-09 08:00:00 +0100" value="120"/>
            """,
        )

        assertEquals(
            listOf(java.time.LocalDate.parse("2026-03-01") to true, java.time.LocalDate.parse("2026-03-03") to false),
            periods.sortedBy { it.day }.map { it.day to it.start },
        )
    }

    /**
     * An export taken on the second day of a period ends where the period has not: the two days of
     * flow are a start and no end, or next month's export would find a two-day period standing.
     */
    @Test
    fun aRunTheFileEndsOnIsStillOpen() {
        val flow = "<Record type=\"HKCategoryTypeIdentifierMenstrualFlow\" value=\"HKCategoryValueMenstrualFlowLight\""
        val periods = records<PeriodRecord>(
            """
            $flow startDate="2026-03-01 12:00:00 +0100"/>
            $flow startDate="2026-03-02 12:00:00 +0100"/>
            <Record type="HKQuantityTypeIdentifierStepCount" startDate="2026-03-03 08:00:00 +0100" value="120"/>
            """,
        )

        assertEquals(listOf(java.time.LocalDate.parse("2026-03-01") to true), periods.map { it.day to it.start })
        // A whole day with nothing logged after the run is what ends it.
        val days = setOf(java.time.LocalDate.parse("2026-03-01"), java.time.LocalDate.parse("2026-03-02"))
        assertEquals(1, periodRecords(days, java.time.LocalDate.parse("2026-03-03")).size)
        assertEquals(2, periodRecords(days, java.time.LocalDate.parse("2026-03-04")).size)
    }

    /** A watch samples the saturation dozens of times a day; the day's average is the one row. */
    @Test
    fun aDaysSaturationSamplesAreOneFigure() {
        val oxygen = "<Record type=\"HKQuantityTypeIdentifierOxygenSaturation\" unit=\"%\""
        val vitals = records<VitalRecord>(
            """
            $oxygen startDate="2026-03-01 02:00:00 +0100" value="94"/>
            $oxygen startDate="2026-03-01 03:00:00 +0100" value="96"/>
            $oxygen startDate="2026-03-01 04:00:00 +0100" value="20"/>
            $oxygen startDate="2026-03-02 02:00:00 +0100" value="98"/>
            """,
        )

        // The 20% is an artefact and is not averaged in.
        assertEquals(listOf(95.0, 98.0), vitals.map(VitalRecord::value).sorted())
        val rows = HealthImportFile(HealthExportApp.APPLE_HEALTH, vitals).rows(zone, testTitles()).entries
        assertEquals(setOf("2026-03-01", "2026-03-02"), rows.map(JournalEntry::day).toSet())
        assertTrue(rows.all { it.healthConnectRecordKey!!.endsWith("T120000") })
    }

    /** A record saying there was no flow is not a day of the period. */
    @Test
    fun aDayWithNoFlowIsNotAPeriodDay() {
        val periods = records<PeriodRecord>(
            """
            <Record type="HKCategoryTypeIdentifierMenstrualFlow"
                    value="HKCategoryValueMenstrualFlowNone" startDate="2026-03-01 12:00:00 +0100"/>
            """,
        )

        assertTrue(periods.isEmpty())
    }

    // ─── what is not read ───────────────────────────────────────────────────────────────────────

    /**
     * The raw heart-rate stream is a watch's transcript, not a history: an archive holds tens of
     * millions of these and a journal has nowhere to put one of them.
     */
    @Test
    fun theRawHeartRateStreamIsNotImported() {
        val records = read(
            """
            <Record type="HKQuantityTypeIdentifierHeartRate" unit="count/min" startDate="2026-03-01 08:00:01 +0100" value="71"/>
            <Record type="HKQuantityTypeIdentifierHeartRate" unit="count/min" startDate="2026-03-01 08:00:06 +0100" value="72"/>
            <Record type="HKQuantityTypeIdentifierRestingHeartRate" unit="count/min" startDate="2026-03-01 00:00:00 +0100" value="58"/>
            """,
        )

        assertEquals(1, records.size)
        assertEquals(58.0, (records.single() as VitalRecord).value, 0.01)
        // The resting rate Apple derives is the vital of that name, kept out of the pulse series.
        assertEquals(Vital.RESTING_HEART_RATE, (records.single() as VitalRecord).vital)
    }

    /** A type this app has no column for is skipped rather than guessed at. */
    @Test
    fun anUnknownTypeIsSkipped() {
        assertTrue(
            read(
                """
                <Record type="HKQuantityTypeIdentifierVO2Max" unit="mL/min·kg"
                        startDate="2026-03-01 08:00:00 +0100" value="42"/>
                """,
            ).isEmpty(),
        )
    }

    /** HealthKit keeps the underscores in its fluid ounce: eight `fl_oz_us` is a US cup, not 8 mL. */
    @Test
    fun waterInFluidOuncesIsReadAsFluidOunces() {
        val drinks = records<DrinkRecord>(
            """
            <Record type="HKQuantityTypeIdentifierDietaryWater" unit="fl_oz_us" startDate="2026-03-01 08:00:00 +0100" value="8"/>
            """,
        )

        assertEquals(236.59, drinks.single().amount, 0.01)
    }
}
