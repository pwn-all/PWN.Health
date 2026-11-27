package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readings this app already stores, read back the way the screen will read them.
 *
 * Plain JUnit on purpose: nothing behind [Vital] touches an Android type, and a vitals model that
 * needed a device to be checked would be a model whose bands nobody ever checked.
 */
class VitalsTest {
    private fun row(
        payload: String? = null,
        heartRateBpm: Int? = null,
        bodyFatPercent: Double? = null,
        source: String? = null,
        at: Long = 0L,
        day: String = "2026-09-04",
    ) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = "MEASUREMENT",
        title = "measurement",
        calories = 0.0,
        measurementHeartRateBpm = heartRateBpm,
        measurementSource = source,
        measurementPayloadJson = payload,
        bodyFatPercent = bodyFatPercent,
    )

    /**
     * Whose body the readings came from: the sex a band may depend on, and the birth date the age
     * on the day of a reading is worked out from. Born in 1990, so every default row is an adult's.
     */
    private fun profile(sex: String = "MALE", birthDate: String = "1990-01-01") = UserProfile(
        sex = sex,
        weightKg = 70.0,
        heightCm = 175,
        birthDate = birthDate,
    )

    /** The shape [JournalEntry.measurementPayloadJson] holds: a schema number, then the fields. */
    private fun payload(vararg fields: Pair<String, Any>): String =
        fields.joinToString(separator = ",", prefix = """{"schema":1,""", postfix = "}") {
            """"${it.first}":${it.second}"""
        }

    /** What a thermometer writes: the reading, and — when the device says so — where it read. */
    private fun temperatureRow(celsius: Double, site: TemperatureType? = null): JournalEntry {
        val fields = mutableListOf<Pair<String, Any>>("temperatureCelsius" to celsius)
        site?.let { fields += "type" to "\"${it.name}\"" }
        return row(payload = payload(*fields.toTypedArray()))
    }

    /** A pressure written the wrong way round, for the pair checks below. */
    private fun pressureRow(systolic: Double, diastolic: Double) =
        row(payload = payload("systolicMmhg" to systolic, "diastolicMmhg" to diastolic))

    // --- a pair that could not have been measured ------------------------------------------------

    /**
     * The diastolic is the pressure between beats and is below the systolic by construction. With
     * both halves gated only against their own plausible ranges and nothing checking the one relation
     * that makes them one squeeze of one cuff, 90/150 would arrive, be graded FAR_ABOVE on its
     * diastolic and go into the mean and the highest as a reading nobody can have taken.
     */
    @Test
    fun aPressureWithItsHalvesTheWrongWayRoundIsNotAReading() {
        val series = vitalSeries(listOf(pressureRow(90.0, 150.0)), Vital.BLOOD_PRESSURE, profile())

        assertTrue(series.isEmpty())
    }

    /** Equal halves are the same impossibility with no gap between them. */
    @Test
    fun aPressureWhoseHalvesAreEqualIsNotAReading() {
        val series = vitalSeries(listOf(pressureRow(120.0, 120.0)), Vital.BLOOD_PRESSURE, profile())

        assertTrue(series.isEmpty())
    }

    /** And the ordinary pair still survives, verdict and all. */
    @Test
    fun anOrdinaryPressureIsStillAReading() {
        val series = vitalSeries(listOf(pressureRow(128.0, 84.0)), Vital.BLOOD_PRESSURE, profile())

        assertEquals(1, series.size)
        assertEquals(128.0, series.first().value, 1e-9)
        assertEquals(84.0, series.first().secondary!!, 1e-9)
        assertEquals(LabStatus.NORMAL, series.first().status)
    }

    // --- a site this app cannot place ------------------------------------------------------------

    /**
     * Health Connect names WRIST and FOREHEAD, which the Bluetooth profile's [TemperatureType] does
     * not. Reading a name it cannot place as "no site at all" would put the core fever threshold back over
     * exactly the peripheral readings the site rule exists to keep it off.
     */
    @Test
    fun aSiteThisAppCannotPlaceWithholdsTheVerdict() {
        val row = row(payload = payload("temperatureCelsius" to 37.6, "type" to "\"WRIST\""))

        val series = vitalSeries(listOf(row), Vital.BODY_TEMPERATURE, profile())

        assertEquals(1, series.size)
        assertNull(series.first().status)
        assertNull(series.first().site)
        assertEquals("WRIST", series.first().siteName)
    }

    /** The same name in another case is the same armpit, and is still not graded. */
    @Test
    fun aSiteNamedInLowerCaseIsStillTheSameSite() {
        val row = row(payload = payload("temperatureCelsius" to 37.6, "type" to "\"armpit\""))

        val series = vitalSeries(listOf(row), Vital.BODY_TEMPERATURE, profile())

        assertEquals(TemperatureType.ARMPIT, series.first().site)
        assertNull(series.first().status)
    }

    /** A thermometer that named nothing is graded against the band. */
    @Test
    fun aThermometerThatNamedNothingIsStillGraded() {
        val series = vitalSeries(listOf(temperatureRow(37.6)), Vital.BODY_TEMPERATURE, profile())

        assertEquals(LabStatus.NORMAL, series.first().status)
        assertNull(series.first().siteName)
    }

    // --- an age nobody knows ---------------------------------------------------------------------

    /**
     * [ageOn] answers zero for anything it cannot parse, which reads as a newborn and therefore as a
     * child — so a profile with no birth date would have its adult bands withheld and be told the range
     * "is not applied under 18", a statement about an age nobody knows. A restored backup gets here.
     */
    @Test
    fun anUnparsableBirthDateIsNotAnAge() {
        assertTrue(birthDateKnown("1990-04-17"))
        assertTrue(!birthDateKnown(""))
        assertTrue(!birthDateKnown("1990"))
        assertTrue(!birthDateKnown("17 April 1990"))
    }

    private fun statusOf(
        vital: Vital,
        value: Double,
        secondary: Double? = null,
        sex: String = "MALE",
        ageYears: Int = 36,
        site: TemperatureType? = null,
    ) = vital.status(value, secondary, sex, ageYears, site?.name)

    // --- where a reading comes from --------------------------------------------------------------

    /** The pulse a chest strap, the editor or a Health Connect import saved has no payload at all. */
    @Test
    fun heartRateIsReadFromItsColumn() {
        val series = vitalSeries(listOf(row(heartRateBpm = 58)), Vital.HEART_RATE, profile())

        assertEquals(1, series.size)
        assertEquals(58.0, series.first().value, 1e-9)
        assertEquals(LabStatus.BELOW, series.first().status)
    }

    /** A cuff and an oximeter fill the payload key instead, and leave the column to the caller. */
    @Test
    fun heartRateIsReadFromThePayloadWhenThereIsNoColumn() {
        val entry = row(payload = payload("spo2Percent" to 97, "pulseRateBpm" to 72))

        val series = vitalSeries(listOf(entry), Vital.HEART_RATE, profile())

        assertEquals(1, series.size)
        assertEquals(72.0, series.first().value, 1e-9)
    }

    /** Both filled is the ordinary case for a cuff; the column is the one the rest of the app uses. */
    @Test
    fun theColumnWinsWhenBothArePresent() {
        val entry = row(payload = payload("pulseRateBpm" to 61), heartRateBpm = 70)

        assertEquals(70.0, vitalSeries(listOf(entry), Vital.HEART_RATE, profile()).first().value, 1e-9)
    }

    /** Health Connect imports a body-fat percentage into the column and writes no payload for it. */
    @Test
    fun bodyFatIsReadFromEitherPlace() {
        val fromColumn = vitalSeries(listOf(row(bodyFatPercent = 22.0)), Vital.BODY_FAT, profile())
        val fromPayload = vitalSeries(
            listOf(row(payload = payload("bodyFatPercent" to 22.0))),
            Vital.BODY_FAT,
            profile(),
        )

        assertEquals(22.0, fromColumn.first().value, 1e-9)
        assertEquals(22.0, fromPayload.first().value, 1e-9)
    }

    /** A meal, a walk, a weight: most of the journal has no payload, and none of it is a vital. */
    @Test
    fun aRowWithoutAPayloadIsNotAReading() {
        assertTrue(vitalSeries(listOf(row()), Vital.OXYGEN_SATURATION, profile()).isEmpty())
        assertTrue(vitalHistory(listOf(row()), profile()).isEmpty())
    }

    /** A truncated or non-numeric payload yields nothing rather than a number nobody measured. */
    @Test
    fun aMalformedPayloadIsNotAReading() {
        val truncated = row(payload = """{"schema":1,"spo2Percent":""")
        val quoted = row(payload = """{"schema":1,"spo2Percent":"ninety-seven"}""")
        val nonsense = row(payload = "this is not json")

        listOf(truncated, quoted, nonsense).forEach { entry ->
            assertTrue(vitalSeries(listOf(entry), Vital.OXYGEN_SATURATION, profile()).isEmpty())
        }
    }

    /**
     * The guard against a key that only looks like ours: an oximeter writes the fast and slow
     * averages beside the plain one, and reading `fastSpo2Percent` as the saturation would report a
     * different number under the right name.
     */
    @Test
    fun aLongerKeyEndingInTheSameWordIsNotTheReading() {
        val entry = row(payload = payload("fastSpo2Percent" to 91, "slowSpo2Percent" to 93))

        assertTrue(vitalSeries(listOf(entry), Vital.OXYGEN_SATURATION, profile()).isEmpty())
    }

    /** Outside the plausible range the row is a parse artefact, not a measurement of anyone. */
    @Test
    fun anImplausibleReadingIsDropped() {
        val entry = row(payload = payload("spo2Percent" to 12))

        assertTrue(vitalSeries(listOf(entry), Vital.OXYGEN_SATURATION, profile()).isEmpty())
    }

    @Test
    fun theSeriesRunsOldestToNewest() {
        val entries = listOf(
            row(heartRateBpm = 70, at = 300L),
            row(heartRateBpm = 62, at = 100L),
            row(heartRateBpm = 66, at = 200L),
        )

        val values = vitalSeries(entries, Vital.HEART_RATE, profile()).map { it.value }

        assertEquals(listOf(62.0, 66.0, 70.0), values)
    }

    /** Glucose stays with the lab panel that already owns it, bands and all. */
    @Test
    fun glucoseIsNotAVital() {
        val keys = Vital.entries.flatMap { listOfNotNull(it.key, it.secondaryKey) }

        assertTrue(LabMarker.GLUCOSE.key !in keys)
        assertTrue("concentrationMgDl" !in keys)
    }

    /** Two vitals reading one key would mean one reading printed twice under two names. */
    @Test
    fun everyVitalHasItsOwnKey() {
        val keys = Vital.entries.flatMap { listOfNotNull(it.key, it.secondaryKey) }

        assertEquals(keys.size, keys.distinct().size)
    }

    // --- a workout is not a rest -----------------------------------------------------------------

    /**
     * The end of a workout saves the fitness machine's last beat as a measurement row of its own.
     * Read against a resting range it grades above it, and it drags the mean and the highest this
     * screen prints as though they described rest — so the row is not in the series at all.
     */
    @Test
    fun aFitnessMachinesPulseIsNotInTheRestingSeries() {
        val entries = listOf(
            row(heartRateBpm = 165, source = "BLUETOOTH_FTMS", at = 100L),
            row(heartRateBpm = 172, source = "BLUETOOTH_CONCEPT2_PM5", at = 200L),
            row(heartRateBpm = 64, source = "BLUETOOTH_STRAP", at = 300L),
        )

        val stats = requireNotNull(vitalStats(vitalSeries(entries, Vital.HEART_RATE, profile())))

        assertEquals(1, stats.count)
        assertEquals(64.0, stats.mean, 1e-9)
        assertEquals(64.0, stats.highest.value, 1e-9)
    }

    /**
     * And the honest other half: a wearable's one sample a day says nothing about what the body was
     * doing, so it is still read. Nothing in the row could tell it apart from a resting pulse.
     */
    @Test
    fun aWearablesDailySampleIsStillRead() {
        val entries = listOf(row(heartRateBpm = 120, source = "HEALTH_CONNECT"))

        assertEquals(1, vitalSeries(entries, Vital.HEART_RATE, profile()).size)
    }

    // --- statistics ------------------------------------------------------------------------------

    @Test
    fun statisticsCoverTheWholeSeries() {
        val entries = listOf(
            row(heartRateBpm = 60, at = 100L),
            row(heartRateBpm = 100, at = 300L),
            row(heartRateBpm = 80, at = 200L),
        )

        val stats = requireNotNull(vitalStats(vitalSeries(entries, Vital.HEART_RATE, profile())))

        assertEquals(Vital.HEART_RATE, stats.vital)
        assertEquals(100.0, stats.latest.value, 1e-9)
        assertEquals(300L, stats.latest.occurredAt)
        assertEquals(60.0, stats.lowest.value, 1e-9)
        assertEquals(100.0, stats.highest.value, 1e-9)
        assertEquals(80.0, stats.mean, 1e-9)
        assertNull(stats.meanSecondary)
        assertEquals(3, stats.count)
    }

    /** The count is on the record because a mean over two readings is not a trend. */
    @Test
    fun statisticsOverNothingAreNothing() {
        assertNull(vitalStats(emptyList()))
    }

    // --- blood pressure is one reading -----------------------------------------------------------

    @Test
    fun bloodPressureKeepsItsPairTogether() {
        val entry = row(payload = payload("systolicMmhg" to 128, "diastolicMmhg" to 84))

        val reading = vitalSeries(listOf(entry), Vital.BLOOD_PRESSURE, profile()).single()

        assertEquals(128.0, reading.value, 1e-9)
        assertEquals(84.0, requireNotNull(reading.secondary), 1e-9)
        assertTrue(Vital.BLOOD_PRESSURE.paired)
    }

    /** Half a pressure has nothing to be printed over, so it is not a reading of anything. */
    @Test
    fun aSystolicWithoutItsDiastolicIsNotAReading() {
        val entry = row(payload = payload("systolicMmhg" to 128))

        assertTrue(vitalSeries(listOf(entry), Vital.BLOOD_PRESSURE, profile()).isEmpty())
    }

    /**
     * Both halves averaged over the very same rows. The lowest and the highest stay whole readings,
     * so the pair the screen prints is a pair that was measured at one moment.
     */
    @Test
    fun bothHalvesAreAveragedOverTheSameReadings() {
        val entries = listOf(
            row(payload = payload("systolicMmhg" to 120, "diastolicMmhg" to 80), at = 100L),
            row(payload = payload("systolicMmhg" to 136, "diastolicMmhg" to 88), at = 200L),
        )

        val stats = requireNotNull(vitalStats(vitalSeries(entries, Vital.BLOOD_PRESSURE, profile())))

        assertEquals(128.0, stats.mean, 1e-9)
        assertEquals(84.0, requireNotNull(stats.meanSecondary), 1e-9)
        assertEquals(120.0, stats.lowest.value, 1e-9)
        assertEquals(80.0, requireNotNull(stats.lowest.secondary), 1e-9)
        assertEquals(136.0, stats.highest.value, 1e-9)
        assertEquals(88.0, requireNotNull(stats.highest.secondary), 1e-9)
    }

    /**
     * The reading the export cannot publish is still the reading the person most needs to see:
     * Health Connect refuses a systolic over 200, and this screen does not.
     */
    @Test
    fun aPressureTooHighForHealthConnectIsStillShown() {
        val entry = row(payload = payload("systolicMmhg" to 214, "diastolicMmhg" to 121))

        val reading = vitalSeries(listOf(entry), Vital.BLOOD_PRESSURE, profile()).single()

        assertEquals(214.0, reading.value, 1e-9)
        assertEquals(LabStatus.FAR_ABOVE, reading.status)
    }

    /** Both classifications grade a pressure by whichever half is worse. That is the high side. */
    @Test
    fun aPressureIsGradedByItsMorePressingHalf() {
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BLOOD_PRESSURE, 139.0, 89.0))
        // The NHS's "90/60 or lower": the printed edge is itself low, as the source says.
        assertEquals(LabStatus.BELOW, statusOf(Vital.BLOOD_PRESSURE, 90.0, 60.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BLOOD_PRESSURE, 91.0, 61.0))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BLOOD_PRESSURE, 140.0, 89.0))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BLOOD_PRESSURE, 139.0, 90.0))
        assertEquals(LabStatus.FAR_ABOVE, statusOf(Vital.BLOOD_PRESSURE, 180.0, 89.0))
        assertEquals(LabStatus.FAR_ABOVE, statusOf(Vital.BLOOD_PRESSURE, 139.0, 110.0))
    }

    /**
     * The low side is not read that way, because nobody publishes it that way: the NHS's 90/60 is
     * one paired figure and there is no diastolic hypotension threshold on its own. A low diastolic
     * under an ordinary systolic is common, healthy and the shape of a wide pulse pressure — and
     * read symmetrically it would paint 118/58 as a reading outside its band.
     */
    @Test
    fun anOrdinaryPressureWithALowDiastolicIsNotLowBloodPressure() {
        assertNull(Vital.BLOOD_PRESSURE.secondaryBand?.low)
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BLOOD_PRESSURE, 118.0, 58.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BLOOD_PRESSURE, 125.0, 59.0))
        // 91 is the first systolic inside the band: the NHS's 90 is itself "90/60 or lower".
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BLOOD_PRESSURE, 91.0, 45.0))
        // The systolic the NHS names still reads low, whatever the diastolic under it is doing.
        assertEquals(LabStatus.BELOW, statusOf(Vital.BLOOD_PRESSURE, 89.0, 59.0))
        assertEquals(LabStatus.BELOW, statusOf(Vital.BLOOD_PRESSURE, 89.0, 72.0))
    }

    // --- units -----------------------------------------------------------------------------------

    /**
     * The Body Composition Service reports basal metabolism in kilojoules, and printing that figure
     * beside a "kcal" label would overstate a day's resting burn by a factor of four.
     */
    @Test
    fun basalMetabolismArrivesInKilojoulesAndIsReadInKilocalories() {
        val entry = row(payload = payload("basalMetabolismKj" to 7000.0))

        val value = vitalSeries(listOf(entry), Vital.BASAL_METABOLISM, profile()).single().value

        assertEquals(1673.04, value, 0.01)
        assertEquals(R.string.unit_kcal, Vital.BASAL_METABOLISM.unitRes)
    }

    // --- bands -----------------------------------------------------------------------------------

    /** AHA: "a normal resting heart rate for adults ranges from 60 to 100 bpm", so 100 is inside. */
    @Test
    fun heartRateFollowsTheRestingRange() {
        assertEquals(LabStatus.BELOW, statusOf(Vital.HEART_RATE, 59.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.HEART_RATE, 60.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.HEART_RATE, 100.0))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.HEART_RATE, 101.0))
    }

    /** WHO: 95 to 100% at sea level, and under 90% is the level its manual calls an emergency. */
    @Test
    fun oxygenSaturationFollowsTheWhoThresholds() {
        assertEquals(LabStatus.NORMAL, statusOf(Vital.OXYGEN_SATURATION, 95.0))
        assertEquals(LabStatus.BELOW, statusOf(Vital.OXYGEN_SATURATION, 94.0))
        assertEquals(LabStatus.BELOW, statusOf(Vital.OXYGEN_SATURATION, 90.0))
        assertEquals(LabStatus.FAR_BELOW, statusOf(Vital.OXYGEN_SATURATION, 89.0))
    }

    /** Fever from 38.0 °C; hypothermia under 35.0 °C, its moderate stage under 32.0 °C. */
    @Test
    fun temperatureFollowsFeverAndHypothermia() {
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_TEMPERATURE, 37.9))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BODY_TEMPERATURE, 38.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_TEMPERATURE, 35.0))
        assertEquals(LabStatus.BELOW, statusOf(Vital.BODY_TEMPERATURE, 34.9))
        assertEquals(LabStatus.BELOW, statusOf(Vital.BODY_TEMPERATURE, 32.0))
        assertEquals(LabStatus.FAR_BELOW, statusOf(Vital.BODY_TEMPERATURE, 31.9))
    }

    /**
     * The split that makes a unisex body-fat band a wrong number: 30% is above the cut-off for a
     * man and inside the band for a woman. AACE's obesity guidance is "greater than" 25 and 35, and
     * a scale decodes tenths, so the first value outside is 25.1 and 35.1.
     */
    @Test
    fun bodyFatIsReadAgainstADifferentBandForWomen() {
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BODY_FAT, 30.0, sex = "MALE"))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 30.0, sex = "FEMALE"))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 25.0, sex = "MALE"))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BODY_FAT, 25.1, sex = "MALE"))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 35.0, sex = "FEMALE"))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BODY_FAT, 35.1, sex = "FEMALE"))
    }

    /**
     * ACSM's 3% and 12% are essential fat — the level a body cannot run below — and not the bottom
     * of a healthy range, so they are not edges here. Printed as one they would have told a man at
     * 4% that he was inside the normal band, which is the direction a wrong verdict must not go.
     */
    @Test
    fun bodyFatHasNoFloorBecauseNobodyPublishedOne() {
        assertNull(Vital.BODY_FAT.bandFor("MALE", 36)?.low)
        assertNull(Vital.BODY_FAT.bandFor("FEMALE", 36)?.low)
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 4.0, sex = "MALE"))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 2.9, sex = "MALE"))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.BODY_FAT, 11.0, sex = "FEMALE"))
    }

    /** The sex-specific band travels into the stored reading, not just into a helper. */
    @Test
    fun theStoredReadingCarriesTheBandOfWhoseBodyItIs() {
        val entries = listOf(row(bodyFatPercent = 30.0))

        assertEquals(LabStatus.ABOVE, vitalSeries(entries, Vital.BODY_FAT, profile()).single().status)
        assertEquals(
            LabStatus.NORMAL,
            vitalSeries(entries, Vital.BODY_FAT, profile(sex = "FEMALE")).single().status,
        )
    }

    // --- the edge is outside, the printed top is inside -------------------------------------------

    /** What the card prints as the top of a range: one step under the first value outside it. */
    @Test
    fun theCardIsGivenTheLastValueInsideTheBand() {
        assertEquals(100.0, Vital.HEART_RATE.insideTop(101.0), 1e-9)
        assertEquals(25.0, Vital.BODY_FAT.insideTop(25.1), 1e-9)
        assertEquals(35.0, Vital.BODY_FAT.insideTop(35.1), 1e-9)
        assertEquals(37.9, Vital.BODY_TEMPERATURE.insideTop(38.0), 1e-9)
        assertEquals(139.0, Vital.BLOOD_PRESSURE.insideTop(140.0), 1e-9)
    }

    /**
     * And the promise that keeps the two in step: every edge grades outside, and the figure printed
     * under it grades inside. A card naming a top the band itself calls abnormal would be one
     * screen grading against one number while showing another.
     */
    @Test
    fun everyPrintedTopIsInsideTheBandAndEveryEdgeIsNot() {
        Vital.entries.forEach { vital ->
            val high = vital.bandFor("MALE", 36)?.high ?: return@forEach
            // 80 is an ordinary diastolic, so on a pressure the systolic under test is what grades.
            assertEquals(vital.name, LabStatus.NORMAL, statusOf(vital, vital.insideTop(high), 80.0))
            assertEquals(vital.name, LabStatus.ABOVE, statusOf(vital, high, 80.0))
        }
    }

    // --- where the thermometer read --------------------------------------------------------------

    /**
     * An armpit runs below core by a figure published as a range, so 37.6 there is a fever the
     * 38.0 band would have called normal. Nothing is added to the reading to make it gradable: the
     * number and the site are shown, and the verdict is left blank.
     */
    @Test
    fun anArmpitTemperatureIsShownWithNoVerdict() {
        val reading = vitalSeries(
            listOf(temperatureRow(37.6, TemperatureType.ARMPIT)),
            Vital.BODY_TEMPERATURE,
            profile(),
        ).single()

        assertEquals(37.6, reading.value, 1e-9)
        assertEquals(TemperatureType.ARMPIT, reading.site)
        assertNull(reading.status)
    }

    /** The site is used, not merely carried: the same number in the mouth is graded as it was. */
    @Test
    fun aMouthTemperatureIsStillGraded() {
        val entries = listOf(temperatureRow(38.1, TemperatureType.MOUTH))

        val reading = vitalSeries(entries, Vital.BODY_TEMPERATURE, profile()).single()

        assertEquals(TemperatureType.MOUTH, reading.site)
        assertEquals(LabStatus.ABOVE, reading.status)
    }

    /** A thermometer that names no site is the case the band is made for. */
    @Test
    fun aThermometerThatNamesNoSiteIsStillGraded() {
        val reading = vitalSeries(listOf(temperatureRow(38.1)), Vital.BODY_TEMPERATURE, profile()).single()

        assertNull(reading.site)
        assertEquals(LabStatus.ABOVE, reading.status)
    }

    /** The armpit, the ear lobe, a finger and a toe are off the core scale; the rest are on it. */
    @Test
    fun onlyTheSitesOnTheCoreScaleAreGraded() {
        val graded = TemperatureType.entries.filter { temperatureBandApplies(it.name) }

        assertEquals(
            listOf(
                TemperatureType.BODY,
                TemperatureType.GASTROINTESTINAL_TRACT,
                TemperatureType.MOUTH,
                TemperatureType.RECTUM,
                TemperatureType.TYMPANUM,
            ),
            graded,
        )
        assertTrue(temperatureBandApplies(null))
    }

    /** Every site a device can send has a word of its own, or the card prints a reading half-said. */
    @Test
    fun everySiteAThermometerCanNameCanBePrinted() {
        val labels = TemperatureType.entries.map { it.labelRes }

        assertEquals(TemperatureType.entries.size, labels.distinct().size)
        assertTrue(labels.none { it == 0 })
    }

    /** No other vital carries a site, and no other vital is silenced by one. */
    @Test
    fun onlyATemperatureHasASite() {
        val entry = row(payload = payload("spo2Percent" to 97, "type" to "\"ARMPIT\""))

        assertNull(vitalSeries(listOf(entry), Vital.OXYGEN_SATURATION, profile()).single().site)
        assertEquals(
            LabStatus.NORMAL,
            vitalSeries(listOf(entry), Vital.OXYGEN_SATURATION, profile()).single().status,
        )
    }

    // --- the bands are adult ones ----------------------------------------------------------------

    /**
     * A child's blood pressure is a percentile of age, sex and height: a ten-year-old at 118/78 is
     * stage 1 hypertensive by AAP 2017 and sits comfortably inside the adult band. The number is
     * shown; the adult verdict is not.
     */
    @Test
    fun anAdultBandIsNotReadOverAChild() {
        val entry = row(payload = payload("systolicMmhg" to 118, "diastolicMmhg" to 78))

        val child = vitalSeries(
            listOf(entry),
            Vital.BLOOD_PRESSURE,
            profile(birthDate = "2016-01-01"),
        ).single()
        val adult = vitalSeries(listOf(entry), Vital.BLOOD_PRESSURE, profile()).single()

        assertEquals(118.0, child.value, 1e-9)
        assertNull(child.status)
        assertNull(Vital.BLOOD_PRESSURE.bandFor("MALE", 10))
        assertEquals(LabStatus.NORMAL, adult.status)
    }

    /** The three published for adults are withheld; the two published for everyone are not. */
    @Test
    fun onlyTheAdultBandsAreWithheldFromAChild() {
        assertNull(statusOf(Vital.HEART_RATE, 55.0, ageYears = 10))
        assertNull(statusOf(Vital.BODY_FAT, 30.0, ageYears = 10))
        assertEquals(LabStatus.FAR_BELOW, statusOf(Vital.OXYGEN_SATURATION, 89.0, ageYears = 10))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.BODY_TEMPERATURE, 38.5, ageYears = 10))
        // The waist joins them: WHO's cut-offs were derived in adults, and stretching them over a
        // twelve-year-old would be grading a body the numbers were never about.
        assertEquals(
            listOf(Vital.HEART_RATE, Vital.RESTING_HEART_RATE, Vital.BLOOD_PRESSURE, Vital.BODY_FAT, Vital.WAIST),
            Vital.entries.filter(Vital::adultOnly),
        )
    }

    /** The age is the age on the day of the reading, the way a lab panel is read back. */
    @Test
    fun theAgeIsTheOneOnTheDayOfTheReading() {
        val entries = listOf(
            row(heartRateBpm = 110, day = "2026-05-31", at = 100L),
            row(heartRateBpm = 110, day = "2026-06-01", at = 200L),
        )

        val readings = vitalSeries(entries, Vital.HEART_RATE, profile(birthDate = "2008-06-01"))

        assertNull(readings.first().status)
        assertEquals(LabStatus.ABOVE, readings.last().status)
    }

    // --- what nobody published -------------------------------------------------------------------

    @Test
    fun everyBandedVitalNamesWhoPublishedIt() {
        Vital.entries.filter(Vital::banded).forEach { vital ->
            assertNotNull("${vital.name} has a band and must name its source", vital.sourceRes)
        }
    }

    /**
     * The other half of the same promise: where no body publishes a range for the figure a scale
     * reports, there is no band, no source and no verdict — only the number and its history.
     */
    @Test
    fun aVitalWithNoPublishedBandSaysNothingAboutTheReading() {
        val unbanded = listOf(Vital.MUSCLE_MASS, Vital.BODY_WATER, Vital.BASAL_METABOLISM)

        unbanded.forEach { vital ->
            assertNull(vital.name, vital.sourceRes)
            assertNull(vital.name, vital.bandFor("MALE", 36))
            assertNull(vital.name, statusOf(vital, vital.valid.start))
            assertNull(vital.name, statusOf(vital, vital.valid.endInclusive))
        }
    }

    // --- the scale a figure is read on ------------------------------------------------------------

    /**
     * Three vitals are stored in a unit an imperial profile does not read, and the other four are
     * the same figure in both systems. Nothing here touches what is stored: this is the last thing
     * that happens to a number before it becomes text.
     */
    @Test
    fun threeVitalsAreReadOnADifferentScaleAndTheRestAreNot() {
        assertEquals(98.6, Vital.BODY_TEMPERATURE.displayValue(37.0, imperial = true), 1e-9)
        assertEquals(37.0, Vital.BODY_TEMPERATURE.displayValue(37.0, imperial = false), 1e-9)
        assertEquals(imperialWeight(32.5), Vital.MUSCLE_MASS.displayValue(32.5, imperial = true), 1e-9)
        assertEquals(imperialWeight(41.0), Vital.BODY_WATER.displayValue(41.0, imperial = true), 1e-9)

        assertEquals(R.string.vitals_unit_fahrenheit, Vital.BODY_TEMPERATURE.displayUnitRes(true))
        assertEquals(R.string.vitals_unit_celsius, Vital.BODY_TEMPERATURE.displayUnitRes(false))
        assertEquals(R.string.unit_lb, Vital.MUSCLE_MASS.displayUnitRes(true))
        assertEquals(R.string.unit_lb, Vital.BODY_WATER.displayUnitRes(true))
        assertEquals(R.string.unit_kg, Vital.MUSCLE_MASS.displayUnitRes(false))

        // A beat, a millimetre of mercury and a percent are the same figure in both systems, and
        // the kilocalorie is what the country that asked for miles counts its food in too.
        listOf(Vital.HEART_RATE, Vital.BLOOD_PRESSURE, Vital.OXYGEN_SATURATION, Vital.BODY_FAT, Vital.BASAL_METABOLISM)
            .forEach { vital ->
                assertEquals(vital.name, 42.0, vital.displayValue(42.0, imperial = true), 1e-9)
                assertEquals(vital.name, vital.unitRes, vital.displayUnitRes(true))
            }
    }

    /**
     * The floor that keeps a printed figure on the same side of a band as the verdict beside it,
     * over every tenth a thermometer can report.
     *
     * `BigDecimal(37.9)` is 37.89999999999999857 — the exact binary expansion — and floors to 37.8,
     * which would print a fever of 37.9 a tenth lower than it was measured, as it would eighty of
     * these two hundred and one values. And the hundredth that this floor exists for still goes
     * down: 25.05 is inside a band whose first value outside is 25.1, and printing it as 25.1 would
     * put a figure the app graded NORMAL above a range the next line says ends at 25.
     */
    @Test
    fun everyTenthAThermometerCanReportSurvivesTheFloor() {
        (250..450).forEach { tenths ->
            val celsius = tenths / 10.0
            assertEquals("$celsius", celsius, Vital.BODY_TEMPERATURE.atStep(celsius), 1e-9)
        }
        assertEquals(25.0, Vital.BODY_FAT.atStep(25.05), 1e-9)
        assertEquals(25.1, Vital.BODY_FAT.atStep(25.1), 1e-9)
        // A whole-number band is floored whole, and a figure nobody banded is left as measured.
        assertEquals(100.0, Vital.HEART_RATE.atStep(100.9), 1e-9)
        assertEquals(32.55, Vital.MUSCLE_MASS.atStep(32.55), 1e-9)
    }
    // --- what a tape measure writes ----------------------------------------------------------------

    /**
     * A wheel nobody touched writes nothing.
     *
     * The three rulers open on a plain 80 or 95 so there is something to drag; saving those as
     * measurements would put numbers in the record that no tape ever read, and a waist typed today
     * would arrive beside a chest that was never measured.
     */
    @Test
    fun onlyTheGirthSomebodyMeasuredIsWrittenDown() {
        val payload = girthPayload(
            existing = null,
            girths = mapOf(Vital.WAIST to 82, Vital.HIP to 95, Vital.CHEST to 95),
            touched = setOf(Vital.WAIST),
        )!!

        assertTrue(payload.contains(Vital.WAIST.key))
        assertFalse(payload.contains(Vital.HIP.key))
        assertFalse(payload.contains(Vital.CHEST.key))
    }

    /** Nothing measured at all leaves the row's payload exactly as it was. */
    @Test
    fun aFormNobodyTouchedLeavesThePayloadAlone() {
        val pressure = """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}"""

        assertEquals(
            pressure,
            girthPayload(pressure, mapOf(Vital.WAIST to 80), touched = emptySet()),
        )
        assertNull(girthPayload(null, mapOf(Vital.WAIST to 80), touched = emptySet()))
    }

    /**
     * A girth is written into the row the reading shares, not over it.
     *
     * A pressure and a waist can be recorded in the same minute, and an editor that showed only one
     * of them may not delete the other — the rule this screen's save already follows for columns.
     */
    @Test
    fun aGirthJoinsWhateverTheRowAlreadyCarried() {
        val payload = girthPayload(
            existing = """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}""",
            girths = mapOf(Vital.WAIST to 82),
            touched = setOf(Vital.WAIST),
        )!!
        val entry = row(payload = payload)

        assertEquals(mapOf(Vital.WAIST to 82), entry.girthCm())
        assertEquals(118.0, vitalSeries(listOf(entry), Vital.BLOOD_PRESSURE, profile()).last().value, 0.001)
    }

    /**
     * A correction keeps the girths measured with it.
     *
     * Reopening a measurement to move the waist may not drop the hips that were taken at the same
     * time: a key the row already holds stays as it was stored, and only a wheel that was moved
     * writes over it.
     */
    @Test
    fun correctingOneGirthKeepsTheOthersTheRowAlreadyHeld() {
        val first = girthPayload(
            null,
            mapOf(Vital.WAIST to 82, Vital.HIP to 96),
            touched = setOf(Vital.WAIST, Vital.HIP),
        )!!

        val corrected = girthPayload(first, mapOf(Vital.WAIST to 84, Vital.HIP to 96), setOf(Vital.WAIST))!!

        assertEquals(mapOf(Vital.WAIST to 84, Vital.HIP to 96), row(payload = corrected).girthCm())
    }

    /** The waist is read against WHO's published cut-offs, and the two sexes have their own. */
    @Test
    fun theWaistIsGradedAgainstTheBandForThatBody() {
        assertEquals(LabStatus.NORMAL, statusOf(Vital.WAIST, 93.0))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.WAIST, 94.0))
        assertEquals(LabStatus.FAR_ABOVE, statusOf(Vital.WAIST, 102.0))
        assertEquals(LabStatus.NORMAL, statusOf(Vital.WAIST, 79.0, sex = "FEMALE"))
        assertEquals(LabStatus.ABOVE, statusOf(Vital.WAIST, 80.0, sex = "FEMALE"))
        // An adult band over a child is withheld, like every other adult band here.
        assertNull(statusOf(Vital.WAIST, 100.0, ageYears = 12))
        // And the two nobody published a threshold for carry no verdict at any size.
        assertNull(statusOf(Vital.HIP, 140.0))
        assertNull(statusOf(Vital.CHEST, 140.0))
        assertNull(statusOf(Vital.HRV, 12.0))
    }

    // --- a resting rate and a person's own usual -------------------------------------------------

    /**
     * A watch's resting rate is its own series: mixed into the pulse, a 52 measured asleep and a 96
     * sampled on the stairs would average into a rest neither of them was.
     */
    @Test
    fun aRestingRateIsItsOwnSeriesAndNotAPulse() {
        val rows = listOf(
            row(payload = payload("restingHeartRateBpm" to 52), at = 1L),
            row(heartRateBpm = 96, at = 2L),
        )

        val resting = vitalSeries(rows, Vital.RESTING_HEART_RATE, profile())
        val pulse = vitalSeries(rows, Vital.HEART_RATE, profile())

        assertEquals(listOf(52.0), resting.map(VitalReading::value))
        assertEquals(listOf(96.0), pulse.map(VitalReading::value))
        assertEquals(LabStatus.BELOW, resting.single().status)
        assertNull(statusOf(Vital.RESTING_HEART_RATE, 52.0, ageYears = 15))
    }

    private fun reading(vital: Vital, value: Double, day: String) = VitalReading(
        vital = vital,
        value = value,
        secondary = null,
        occurredAt = java.time.LocalDate.parse(day).toEpochDay() * 86_400_000L,
        day = day,
        site = null,
        siteName = null,
        status = null,
    )

    /** The median of the four weeks before the latest day, the latest day itself left out. */
    @Test
    fun theBaselineIsTheMedianOfTheFourWeeksBeforeTheLatestDay() {
        val readings = listOf(
            reading(Vital.HRV, 200.0, "2026-09-01"), // more than 28 days back: outside
            reading(Vital.HRV, 40.0, "2026-10-10"),
            reading(Vital.HRV, 44.0, "2026-10-12"),
            reading(Vital.HRV, 90.0, "2026-10-14"), // one wild night does not move a median
            reading(Vital.HRV, 46.0, "2026-10-20"),
            reading(Vital.HRV, 48.0, "2026-10-25"),
            reading(Vital.HRV, 10.0, "2026-11-01"), // same day as the latest: excluded
            reading(Vital.HRV, 38.0, "2026-11-01").copy(occurredAt = Long.MAX_VALUE),
        )

        val baseline = vitalBaseline(readings)

        assertNotNull(baseline)
        assertEquals(46.0, baseline!!.median, 1e-9)
        assertEquals(5, baseline.count)
        assertEquals(-8.0, baseline.delta, 1e-9)
    }

    /** Four nights are not enough to call anything usual; an even count takes the middle pair. */
    @Test
    fun theBaselineNeedsFiveReadingsAndAveragesTheMiddlePair() {
        val four = listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04")
            .mapIndexed { i, d -> reading(Vital.RESTING_HEART_RATE, 50.0 + i, d) } +
            reading(Vital.RESTING_HEART_RATE, 60.0, "2026-10-10")
        assertNull(vitalBaseline(four))

        val six = four.dropLast(1) + listOf(
            reading(Vital.RESTING_HEART_RATE, 55.0, "2026-10-05"),
            reading(Vital.RESTING_HEART_RATE, 57.0, "2026-10-06"),
            reading(Vital.RESTING_HEART_RATE, 60.0, "2026-10-10"),
        )
        // 50, 51, 52, 53, 55, 57: the middle pair is 52 and 53.
        assertEquals(52.5, vitalBaseline(six)!!.median, 1e-9)
        assertNull(vitalBaseline(emptyList()))
    }

    /** Only the two whose meaning is the person's own trend carry a baseline. */
    @Test
    fun onlyRestingRateAndVariabilityCarryABaseline() {
        assertEquals(setOf(Vital.RESTING_HEART_RATE, Vital.HRV), BASELINE_VITALS)
    }
}
