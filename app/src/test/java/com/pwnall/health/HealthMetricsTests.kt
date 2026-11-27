package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BodyCompositionTest {
    @Test
    fun anOrdinaryReadingLandsInAPlausibleRange() {
        val percent = bodyFatPercent(
            impedanceOhms = 500,
            weightKg = 80.0,
            heightCm = 180,
            ageYears = 30,
            male = true,
        )!!

        assertTrue("was $percent", percent in 5.0..40.0)
    }

    @Test
    fun theSameBodyReadsHigherForAWoman() {
        val male = bodyFatPercent(500, 70.0, 170, 30, male = true)!!
        val female = bodyFatPercent(500, 70.0, 170, 30, male = false)!!

        assertTrue("male $male female $female", female > male)
    }

    @Test
    fun moreImpedanceMeansMoreFat() {
        val lean = bodyFatPercent(400, 80.0, 180, 30, male = true)!!
        val higher = bodyFatPercent(600, 80.0, 180, 30, male = true)!!

        assertTrue("$lean vs $higher", higher > lean)
    }

    @Test
    fun nonsenseInputsGetNoNumberRatherThanABadOne() {
        assertNull(bodyFatPercent(0, 80.0, 180, 30, true))
        assertNull(bodyFatPercent(5_000, 80.0, 180, 30, true))
        assertNull(bodyFatPercent(500, 5.0, 180, 30, true))
        assertNull(bodyFatPercent(500, 80.0, 10, 30, true))
        assertNull(bodyFatPercent(500, 80.0, 180, 200, true))
    }

    @Test
    fun fatMassFollowsThePercentage() {
        assertEquals(16.0, fatMassKg(80.0, 20.0), 0.001)
        assertEquals(0.0, fatMassKg(-80.0, 20.0), 0.001)
    }
}

class RouteSplitsTest {
    /**
     * Metres per degree of latitude on the same sphere the route code uses (R = 6 371 km). Taking
     * the WGS84 figure instead puts the fixture 0.1% away from what the app measures, which is
     * enough to leave a "1 km" leg one metre short of closing its split.
     */
    private val metresPerDegree = 6_371_000.0 * Math.PI / 180.0

    private fun point(index: Int, metresNorth: Double, secondsIn: Long) = TrackPointEntity(
        trackId = 1,
        timestamp = secondsIn * 1_000,
        lat = metresNorth / metresPerDegree,
        lon = 0.0,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1f,
        provider = "gps",
        accepted = true,
        id = index.toLong(),
        // Splits measure from the same anchor the header does, and neither credits an unclassified fix.
        movementType = RouteMovement.WALKING.name,
    )

    @Test
    fun aShortWalkIsOnePartialSplit() {
        val splits = routeSplits(listOf(point(1, 0.0, 0), point(2, 400.0, 400)))

        assertEquals(1, splits.size)
        assertEquals(400.0, splits.single().metres, 1.0)
        assertEquals(400L, splits.single().seconds)
        assertTrue(splits.single().partial)
    }

    @Test
    fun aKilometreClosesItsOwnSplit() {
        val splits = routeSplits(listOf(point(1, 0.0, 0), point(2, 1_000.0, 600)))

        assertEquals(1, splits.size)
        assertEquals(1_000.0, splits.single().metres, 1.0)
        assertEquals(600L, splits.single().seconds)
        assertTrue(!splits.single().partial)
    }

    @Test
    fun aKilometreAcrossTheAntimeridianIsStillOneKilometre() {
        // Half a kilometre either side of the 180th meridian: the seam must not read as a lap of
        // the planet and cut the walk into forty thousand splits.
        val half = 500.0 / metresPerDegree
        val splits = routeSplits(
            listOf(
                point(1, 0.0, 0).copy(lon = 180.0 - half),
                point(2, 0.0, 600).copy(lon = -180.0 + half),
            ),
        )

        assertEquals(1, splits.size)
        assertEquals(1_000.0, splits.single().metres, 1.0)
        assertEquals(600L, splits.single().seconds)
    }

    @Test
    fun theBoundaryIsInterpolatedInsideTheSegmentThatCrossesIt() {
        // One 2 km leg over 20 minutes: the first kilometre must be half of it, not all of it.
        val splits = routeSplits(listOf(point(1, 0.0, 0), point(2, 2_000.0, 1_200)))

        assertEquals(2, splits.size)
        assertEquals(600L, splits[0].seconds)
        assertEquals(600L, splits[1].seconds)
        assertTrue(!splits[1].partial)
    }

    @Test
    fun anExactKilometreLeavesNoPhantomSplitBehindIt() {
        val splits = routeSplits(listOf(point(1, 0.0, 0), point(2, 3_000.0, 1_800)))

        assertEquals(3, splits.size)
        assertTrue(splits.none { it.partial })
    }

    @Test
    fun rejectedFixesAndSinglePointsProduceNothing() {
        assertTrue(routeSplits(emptyList()).isEmpty())
        assertTrue(routeSplits(listOf(point(1, 0.0, 0))).isEmpty())
        assertTrue(
            routeSplits(
                listOf(point(1, 0.0, 0), point(2, 500.0, 300)).map { it.copy(accepted = false) },
            ).isEmpty(),
        )
    }

    @Test
    fun paceIsSecondsPerKilometreEvenForAPartialSplit() {
        val partial = RouteSplit(index = 1, metres = 500.0, seconds = 300, partial = true)

        assertEquals(600L, partial.paceSeconds)
        assertEquals("10:00", formatPace(partial.paceSeconds))
        assertEquals("—", formatPace(-5))
    }
}

class SleepScoreTest {
    private fun night(day: String, bedHour: Int, minutes: Int): JournalEntry {
        val start = LocalDate.parse(day).atTime(bedHour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return JournalEntry(
            day = day,
            occurredAt = start,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = start,
            sleepEndAt = start + minutes * 60_000L,
        )
    }

    @Test
    fun noNightsIsNotAScore() {
        assertEquals(SleepSummary(0, 0, 0, 0, 0), sleepSummary(emptyList()))
    }

    @Test
    fun eightHoursOnASchedulePracticallyMaxesTheScore() {
        val summary = sleepSummary(
            listOf(night("2026-07-28", 23, 480), night("2026-07-29", 23, 480)),
            ZoneOffset.UTC,
        )

        assertEquals(480, summary.averageMinutes)
        assertEquals(0, summary.bedtimeSpreadMinutes)
        assertEquals(0, summary.debtMinutes)
        assertEquals(100, summary.score)
    }

    @Test
    fun shortNightsAccumulateDebt() {
        val summary = sleepSummary(
            listOf(night("2026-07-28", 23, 400), night("2026-07-29", 23, 420)),
            ZoneOffset.UTC,
        )

        assertEquals(140, summary.debtMinutes)
        assertTrue(summary.score < 100)
    }

    @Test
    fun anIrregularScheduleCostsPointsEvenWithEnoughSleep() {
        val steady = sleepSummary(
            listOf(night("2026-07-28", 23, 480), night("2026-07-29", 23, 480)),
            ZoneOffset.UTC,
        )
        val scattered = sleepSummary(
            listOf(night("2026-07-28", 21, 480), night("2026-07-29", 3, 480)),
            ZoneOffset.UTC,
        )

        assertEquals(steady.averageMinutes, scattered.averageMinutes)
        assertTrue("${scattered.score} vs ${steady.score}", scattered.score < steady.score)
    }

    @Test
    fun midnightIsNotTwentyThreeHoursFromElevenFifty() {
        assertEquals(20, bedtimeDriftMinutes(23 * 60 + 50, 10))
        assertEquals(0, bedtimeDriftMinutes(60, 60))
    }

    @Test
    fun impossibleIntervalsAreIgnored() {
        val broken = night("2026-07-28", 23, 480).copy(sleepEndAt = null)

        assertEquals(0, sleepSummary(listOf(broken), ZoneOffset.UTC).nights)
    }
}

class HeartRateServiceTest {
    @Test
    fun aByteWideMeasurementIsTheCommonCase() {
        val measurement = BluetoothHeartRate.parse(byteArrayOf(0x00, 72))!!

        assertEquals(72, measurement.bpm)
        assertNull(measurement.contact)
        assertTrue(measurement.rrIntervalsMs.isEmpty())
    }

    @Test
    fun theFirstFlagBitSwitchesToSixteenBits() {
        val measurement = BluetoothHeartRate.parse(byteArrayOf(0x01, 0x2C, 0x01))!!

        assertEquals(300, measurement.bpm)
    }

    @Test
    fun contactIsOnlyReportedWhenTheSensorSupportsIt() {
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x02, 72))!!.contact)
        assertEquals(false, BluetoothHeartRate.parse(byteArrayOf(0x04, 72))!!.contact)
        assertEquals(true, BluetoothHeartRate.parse(byteArrayOf(0x06, 72))!!.contact)
    }

    @Test
    fun energyAndRrIntervalsAreDecodedInOrder() {
        // flags 0x18: energy present + RR present. Energy 500 kJ, then two RR values.
        val measurement = BluetoothHeartRate.parse(
            byteArrayOf(0x18, 60, 0xF4.toByte(), 0x01, 0x00, 0x04, 0x00, 0x03),
        )!!

        assertEquals(60, measurement.bpm)
        assertEquals(500, measurement.energyExpendedKj)
        assertEquals(listOf(1_000, 750), measurement.rrIntervalsMs)
    }

    @Test
    fun clippedAndImpossiblePacketsAreRejected() {
        assertNull(BluetoothHeartRate.parse(byteArrayOf()))
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x00)))
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x01, 0x2C)))
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x08, 60, 0x01)))
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x00, 60, 0x01)))
        assertNull(BluetoothHeartRate.parse(byteArrayOf(0x10, 60, 0x01)))
    }

    /**
     * A strap lying on the table reports zero with the contact bit cleared. That is a reading, not
     * an impossible value to throw away: any RR intervals riding in the same notification would go
     * with it, and they are the only thing HRV can be built from.
     */
    @Test
    fun zeroBpmWithNoSkinContactIsAReadingNotAnError() {
        val measurement = BluetoothHeartRate.parse(byteArrayOf(0x04, 0))!!

        assertEquals(0, measurement.bpm)
        assertEquals(false, measurement.contact)
    }

    /**
     * RR intervals arrive in 1/1024 s. Truncating the conversion would shorten every one of them, and
     * a one-sided error does not cancel out across a session the way rounding does.
     */
    @Test
    fun rrIntervalsAreRoundedNotTruncated() {
        // 900/1024 s = 878.906 ms.
        val measurement = BluetoothHeartRate.parse(byteArrayOf(0x10, 60, 0x84.toByte(), 0x03))!!

        assertEquals(listOf(879), measurement.rrIntervalsMs)
    }
}

class CycleTest {
    private val today = LocalDate.parse("2026-07-31")

    private fun start(day: String) = JournalEntry(
        day = day,
        occurredAt = 0,
        kind = KIND_CYCLE,
        title = "Cycle",
        calories = 0.0,
    )

    @Test
    fun oneEntryPredictsNothing() {
        val summary = cycleSummary(listOf(start("2026-07-20")), today)

        assertEquals(LocalDate.parse("2026-07-20"), summary.lastStart)
        assertEquals(12, summary.dayOfCycle)
        assertNull(summary.nextExpected)
        assertEquals(0, summary.recordedCycles)
    }

    @Test
    fun twoEntriesGiveThePersonsOwnLength() {
        val summary = cycleSummary(listOf(start("2026-06-01"), start("2026-07-01")), today)

        assertEquals(30, summary.averageLengthDays)
        assertEquals(LocalDate.parse("2026-07-31"), summary.nextExpected)
        assertEquals(1, summary.recordedCycles)
    }

    @Test
    fun animplausibleGapIsDroppedRatherThanAveragedIn() {
        val summary = cycleSummary(
            listOf(start("2026-01-01"), start("2026-06-01"), start("2026-07-01")),
            today,
        )

        assertEquals(30, summary.averageLengthDays)
        assertEquals(1, summary.recordedCycles)
    }

    @Test
    fun noEntriesFallsBackToTheTextbookNumberAndPredictsNothing() {
        val summary = cycleSummary(emptyList(), today)

        assertEquals(DEFAULT_CYCLE_DAYS, summary.averageLengthDays)
        assertNull(summary.lastStart)
        assertNull(summary.nextExpected)
        assertNull(summary.dayOfCycle)
    }
}
