package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The training machines at the edges of their own wire formats: FTMS records at the two widths the
 * spec revisions disagree about, the Keiser broadcast at the ends of its fields, and the PM5's
 * three independently emitted characteristics meeting in one accumulator.
 */
class MachineProtocolsEdgeCasesTest {

    /**
     * A record one byte longer than any layout its Flags field allows is rubbish on the wire, and
     * the exact-fit rule is what says so. A decoder that stopped at "everything I needed was
     * there" would half-read it and still hand back a confident measurement — which is the whole
     * failure the exact-fit design exists to prevent, tested elsewhere only from the short side.
     */
    @Test
    fun aRecordLongerThanItsOwnFlagsAllowIsRefused() {
        mapOf(
            BluetoothFitnessMachine.treadmillDataUuid to fullTreadmillPacket(paceOctets = 2),
            BluetoothFitnessMachine.indoorBikeDataUuid to fullBikePacket(resistanceOctets = 2),
            BluetoothFitnessMachine.rowerDataUuid to rowerPrimary(strokeCount = 500),
        ).forEach { (uuid, valid) ->
            assertNotNull("$uuid must parse as it stands", BluetoothFitnessMachine.parse(uuid, valid))
            assertNull(
                "$uuid with one byte of trailing rubbish",
                BluetoothFitnessMachine.parse(uuid, valid + byteArrayOf(0)),
            )
        }
    }

    /**
     * Every other bike fixture sets a sparse handful of flags, so nothing walks all thirteen
     * optional fields in one pass. One wrong skip width — average power is a sint16, the metabolic
     * equivalent one octet, energy five bytes — shifts every field behind it, and the exact-fit
     * check would then compensate by choosing the other resistance width and reporting a workout
     * that is wrong in every number rather than refusing the packet.
     */
    @Test
    fun anIndoorBikeRecordWithEveryFlagDecodesUnderExactlyOneResistanceWidth() {
        assertEquals(29, fullBikePacket(resistanceOctets = 1).size)
        assertEquals(30, fullBikePacket(resistanceOctets = 2).size)

        listOf(1, 2).forEach { resistanceOctets ->
            val result = parsedBike(fullBikePacket(resistanceOctets))
            assertEquals("resistance width $resistanceOctets", 25.34, result.speedKph!!, 1e-9)
            assertEquals(90.0, result.cadenceRpm!!, 1e-9)
            assertEquals(12_345, result.totalDistanceMeters)
            assertEquals(75.0, result.resistanceLevel!!, 1e-9)
            assertEquals(245, result.powerWatts)
            assertEquals(321, result.totalEnergyKcal)
            assertEquals(148, result.heartRateBpm)
            assertEquals(900, result.elapsedSeconds)
        }
    }

    /**
     * The same walk for the treadmill, where the elevation-gain flag adds two uint16 in one branch
     * and the power flag a sint16 followed by a power field. Neither is exercised anywhere together
     * with the pace-width ambiguity, and a width mistake in either is precisely what makes the
     * wrong pace layout look like the right one.
     */
    @Test
    fun aTreadmillRecordWithEveryFlagDecodesUnderExactlyOnePaceWidth() {
        assertEquals(34, fullTreadmillPacket(paceOctets = 1).size)
        assertEquals(36, fullTreadmillPacket(paceOctets = 2).size)

        listOf(1, 2).forEach { paceOctets ->
            val result = parsedTreadmill(fullTreadmillPacket(paceOctets))
            assertEquals("pace width $paceOctets", 10.0, result.speedKph!!, 1e-9)
            assertEquals(2_000, result.totalDistanceMeters)
            assertEquals(2.5, result.inclinationPercent!!, 1e-9)
            assertEquals(150, result.totalEnergyKcal)
            assertEquals(140, result.heartRateBpm)
            assertEquals(600, result.elapsedSeconds)
            assertEquals(200, result.powerWatts)
        }
    }

    /**
     * The ramp angle follows the inclination and is read inside an `also` on a nullable receiver.
     * The one sentinel fixture there is marks both fields unavailable, so a decoder that skipped
     * the ramp whenever the inclination was absent would still pass it — and would then be two
     * bytes behind on every treadmill that reports a real incline and no ramp.
     */
    @Test
    fun anUnavailableRampDoesNotHideTheInclinationBesideIt() {
        val followedByEnergy = packet(
            le16(0x0089), // no instantaneous speed; inclination and energy
            le16(100), // 10.0 % incline
            le16(0x7FFF), // ramp angle unavailable
            le16(150) + le16(600) + intArrayOf(10), // 150 kcal
        )
        val result = parsedTreadmill(followedByEnergy)

        assertEquals(10.0, result.inclinationPercent!!, 1e-9)
        assertEquals("the ramp was consumed, so the energy is where it should be", 150, result.totalEnergyKcal)

        val inclineGoneRampReal = packet(le16(0x0009), le16(0x7FFF), le16(0))
        assertNull(parsedTreadmill(inclineGoneRampReal).inclinationPercent)
    }

    /**
     * A treadmill set to a decline reports a negative inclination, and the revision FTMS 1.0
     * shipped with sends resistance as a signed word. The sign extension is written by hand and
     * every fixture in the tree is positive, so a missing one would put a 6 553.5 % incline on the
     * summary of a downhill walk without failing anything.
     */
    @Test
    fun aDeclineAndASignedResistanceKeepTheirSign() {
        val decline = packet(le16(0x0009), le16(0xFF9C), le16(0))
        assertEquals(-10.0, parsedTreadmill(decline).inclinationPercent!!, 1e-9)

        val signed = packet(le16(0x0021), le16(0xFFFF))
        assertEquals(-1.0, parsedBike(signed).resistanceLevel!!, 1e-9)

        // The current supplement's single octet is unsigned, where the same 0xFF is 255.
        val unsigned = packet(le16(0x0021), intArrayOf(0xFF))
        assertEquals(255.0, parsedBike(unsigned).resistanceLevel!!, 1e-9)
    }

    /**
     * At a small ATT_MTU a rower splits its record the way a treadmill does, and the only split
     * test in the tree is a treadmill — which has neither a stroke rate nor a stroke count. The
     * merge lists eleven fields by hand; dropping the stroke count from that list would make a
     * rower's stroke total blink to nothing on every second notification.
     */
    @Test
    fun aSplitRowerRecordKeepsTheStrokeCountFromItsFirstHalf() {
        val accumulator = FitnessMachineAccumulator()
        accumulator.update(parsedRower(rowerPrimary(strokeCount = 500)))
        val merged = accumulator.update(parsedRower(rowerContinuation()))

        assertFalse(merged.moreData)
        assertEquals(30.0, merged.strokeRateSpm!!, 1e-9)
        assertEquals(500, merged.strokeCount)
        assertEquals(200, merged.powerWatts)
        assertEquals(100, merged.totalEnergyKcal)
        assertEquals(150, merged.heartRateBpm)

        val later = accumulator.update(parsedRower(rowerPrimary(strokeCount = 520)))
        assertEquals(520, later.strokeCount)
        assertEquals("calories must not blink off between halves", 100, later.totalEnergyKcal)
        assertEquals(150, later.heartRateBpm)
    }

    /**
     * The accumulator outlives a reconnect, so a continuation left over from the bike can meet the
     * first frame of the treadmill beside it. The type check is the only thing standing between one
     * machine's distance and another machine's workout.
     */
    @Test
    fun aContinuationNeverLendsItsNumbersToAnotherMachine() {
        val accumulator = FitnessMachineAccumulator()
        accumulator.update(parsedBike(bikePrimary()))
        val merged = accumulator.update(parsedTreadmill(treadmillContinuation()))

        assertEquals(FitnessMachineType.TREADMILL, merged.type)
        assertFalse(merged.moreData)
        assertEquals(150, merged.totalEnergyKcal)
        assertEquals(140, merged.heartRateBpm)
        assertNull("a bike's distance is not a treadmill's", merged.totalDistanceMeters)
        assertNull(merged.powerWatts)

        val bikeAgain = accumulator.update(parsedBike(bikePrimary()))
        assertNull("nor is a treadmill's energy a bike's", bikeAgain.totalEnergyKcal)
        assertEquals(245, bikeAgain.powerWatts)
    }

    /**
     * Notifications begin wherever the machine happens to be in its cycle, so the first frame after
     * subscribing is as likely to be a continuation as a first half. Nothing may be invented for
     * the half that never arrived, and More Data is an artefact of the wire that must not reach the
     * screen as a field of its own.
     */
    @Test
    fun theFirstFrameAfterConnectingMayBeAContinuation() {
        val continuation = packet(
            le16(0x0581), // More Data; energy, heart rate, elapsed time
            le16(150) + le16(600) + intArrayOf(10), // 150 kcal
            intArrayOf(140), // 140 bpm
            le16(600), // 600 s
        )
        val result = FitnessMachineAccumulator().update(parsedTreadmill(continuation))

        assertFalse("More Data must not escape the accumulator", result.moreData)
        assertNull(result.speedKph)
        assertNull(result.totalDistanceMeters)
        assertNull(result.inclinationPercent)
        assertEquals(150, result.totalEnergyKcal)
        assertEquals(140, result.heartRateBpm)
        assertEquals(600, result.elapsedSeconds)
    }

    /**
     * FTMS says "unavailable" with a sentinel rather than by leaving a field out, so a machine that
     * has stopped reporting power says something a continuation that simply never carries power
     * does not. The carry-forward that keeps calories from blinking must not also resurrect a value
     * the machine has just declared gone; the two rules were designed apart and meet only here.
     */
    @Test
    fun aFieldDeclaredUnavailableIsNotResurrectedFromTheHalfBeforeIt() {
        val accumulator = FitnessMachineAccumulator()
        assertEquals(245, accumulator.update(parsedBike(bikePrimary())).powerWatts)

        val continuation = packet(le16(0x0101), le16(150) + le16(600) + intArrayOf(10))
        assertEquals(245, accumulator.update(parsedBike(continuation)).powerWatts)

        val refreshed = accumulator.update(parsedBike(bikePrimary(power = 0x7FFF)))
        assertNull("the machine said the reading is gone", refreshed.powerWatts)
        assertEquals(150, refreshed.totalEnergyKcal)
    }

    /**
     * The scanner lists a bike by [BluetoothKeiserM3i.isBroadcast] and records a ride by
     * [BluetoothKeiserM3i.parse]. A console that has finished keeps advertising the ride that just
     * ended, so if the scan gate ever picked up the data-type check the bike would drop out of the
     * device picker the moment the rider stopped pedalling.
     */
    @Test
    fun aConsoleReplayingAFinishedRideIsStillAConsole() {
        listOf(0xFF, 0x01, 0xE4).forEach { dataType ->
            assertTrue("data type $dataType", BluetoothKeiserM3i.isBroadcast(keiserPacket(dataType = dataType)))
        }
        assertNull("but it is not a ride", BluetoothKeiserM3i.parse(keiserPacket(dataType = 0xFF)))

        assertFalse(BluetoothKeiserM3i.isBroadcast(null))
        assertFalse(BluetoothKeiserM3i.isBroadcast(keiserPacket().copyOf(15)))
        assertFalse(BluetoothKeiserM3i.isBroadcast(keiserPacket().also { it[0] = 0x07 }))
        assertFalse(BluetoothKeiserM3i.isBroadcast(keiserPacket(minor = 0x2A)))
        assertFalse(BluetoothKeiserM3i.isBroadcast(keiserPacket().also { it[3] = 0xC9.toByte() }))
    }

    /**
     * The gear byte was appended in 6.21. A 6.20 console whose advertisement is padded to
     * seventeen bytes has a seventeenth byte that is not a gear, and reading it invents a
     * resistance the rider never set. The existing old-firmware fixture is truncated to sixteen
     * bytes, so the length check alone satisfies it and the version never decides anything.
     */
    @Test
    fun theGearByteBelongsToFirmware621AndLater() {
        assertNull(BluetoothKeiserM3i.parse(keiserPacket(minor = 0x20))!!.resistanceLevel)
        assertEquals(8.0, BluetoothKeiserM3i.parse(keiserPacket(minor = 0x21))!!.resistanceLevel!!, 1e-9)
        assertNull("gear 0 is no gear", BluetoothKeiserM3i.parse(keiserPacket(gear = 0))!!.resistanceLevel)
    }

    /**
     * Bit 15 of the distance word chooses the unit; the other fifteen bits are the magnitude. Both
     * of Keiser's published sample parsers read the whole word as the magnitude, and the fixture in
     * the tree accumulates 0.2 km — far too little for the flag to show up in the number. At full
     * scale it is the difference between 3 276.7 km and 6 553.5 km.
     */
    @Test
    fun theDistanceUnitFlagNeverLeaksIntoTheMagnitude() {
        assertEquals(3_276_700, distanceOf(0xFFFF))
        assertEquals(5_273_337, distanceOf(0x7FFF))
        assertEquals(0, distanceOf(0x8000))
        assertEquals(0, distanceOf(0x0000))
        // A tenth of a kilometre is a whole number of metres, so 32.3 km is 32 300 m and nothing
        // else. Scaling through a double and cutting the remainder off loses the last one.
        assertEquals(32_300, distanceOf(0x8000 or 323))
    }

    /**
     * Bytes 12 and 13 are a minutes byte and a seconds byte, not a 16-bit second count. The only
     * elapsed time pinned anywhere is 1:00, which is the reading where those two interpretations
     * are hardest to tell apart.
     */
    @Test
    fun elapsedTimeIsAMinuteByteAndASecondByte() {
        assertEquals(0, elapsedOf(minutes = 0, seconds = 0))
        assertEquals(59, elapsedOf(minutes = 0, seconds = 59))
        assertEquals(119, elapsedOf(minutes = 1, seconds = 59))
        assertEquals(3_599, elapsedOf(minutes = 59, seconds = 59))
    }

    /**
     * A BikeErg's stroke rate is the rider's cadence and a rower's is not. The workout card falls
     * back from cadence to stroke rate, so a measurement carrying both would print "28 rpm" over a
     * rowing session; the two fields are split by a takeIf/takeUnless pair that is trivial to
     * invert and that no other test looks at.
     */
    @Test
    fun aBikeErgsStrokeRateIsACadenceAndARowersIsNot() {
        val bike = Concept2Pm5Accumulator().update(statusOne(strokeRate = 28, ergType = 192))
        assertEquals(FitnessMachineType.INDOOR_BIKE, bike.type)
        assertEquals(28.0, bike.cadenceRpm!!, 1e-9)
        assertNull("a bike reports rpm, not strokes", bike.strokeRateSpm)

        listOf(0, 128).forEach { ergType ->
            val erg = Concept2Pm5Accumulator().update(statusOne(strokeRate = 28, ergType = ergType))
            assertEquals("erg type $ergType", FitnessMachineType.ROWER, erg.type)
            assertEquals(28.0, erg.strokeRateSpm!!, 1e-9)
            assertNull("erg type $ergType", erg.cadenceRpm)
        }
    }

    /**
     * Only Additional Status 1 carries a heart rate, and the flag beside it is what distinguishes
     * "the strap is gone" from "this packet is not about heart rate". The three characteristics are
     * emitted independently, so a General Status packet lands between every pair of Status 1
     * packets and must leave the reading exactly where it was.
     */
    @Test
    fun aGeneralStatusPacketDoesNotClearAHeartRate() {
        val accumulator = Concept2Pm5Accumulator()
        val first = accumulator.update(statusOne(strokeRate = 24, ergType = 0, heartRate = 151))
        assertEquals(151, first.heartRateBpm)

        val afterGeneral = accumulator.update(generalStatus(centiseconds = 6_000, decimetres = 5_000))
        assertEquals("a packet with no heart-rate field says nothing about it", 151, afterGeneral.heartRateBpm)
        assertEquals(500, afterGeneral.totalDistanceMeters)

        val afterStatusTwo = accumulator.update(statusTwo(watts = 210, kcal = 84))
        assertEquals(151, afterStatusTwo.heartRateBpm)
        assertEquals(210, afterStatusTwo.powerWatts)
    }

    /**
     * Concept2's published ERG_MACHINE_TYPE enum, swept whole. Three hand-maintained integer sets
     * decide which family a session is filed under, and only 0, 100, 128 and 192 are exercised
     * elsewhere — none of them next to a boundary. 143 is the ski simulator and 144 is nothing; 207 is
     * the bike simulator and 208 is nothing; 224, 225 and 226 are the multi-erg trio.
     */
    @Test
    fun everyDocumentedErgTypeMapsToTheFamilyItsOwnEnumNames() {
        val rowers = setOf(0, 1, 2, 3, 5, 7, 8, 16, 17, 18, 19, 20, 32, 64, 224)
        val skiErgs = setOf(128, 143, 225)
        val bikeErgs = setOf(192, 193, 194, 207, 226)

        for (raw in 0..0xFF) {
            val expected = when (raw) {
                in rowers -> Concept2MachineKind.ROWER
                in skiErgs -> Concept2MachineKind.SKIERG
                in bikeErgs -> Concept2MachineKind.BIKEERG
                else -> Concept2MachineKind.UNKNOWN
            }
            assertEquals("erg type $raw", expected, statusOne(ergType = raw).machineKind)
        }
    }

    /**
     * The 24-bit and 16-bit readers are hand-rolled extensions on ByteArray, and every fixture uses
     * values small enough that the top byte's sign bit is clear. A missing mask there turns a
     * full-scale counter into a negative distance halfway through a long row.
     */
    @Test
    fun theWidestPm5CountersAreReadUnsigned() {
        val general = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.generalStatusUuid,
            ByteArray(19).also { for (index in 0..5) it[index] = 0xFF.toByte() },
        )!!
        assertEquals(167_772.15, general.elapsedSeconds!!, 1e-6)
        assertEquals(1_677_721.5, general.distanceMeters!!, 1e-6)

        assertEquals(65.535, statusOne(speedMillimetres = 65_535).speedMps!!, 1e-9)

        val two = statusTwo(watts = 65_535, kcal = 65_535)
        assertEquals(65_535, two.averagePowerWatts)
        assertEquals(65_535, two.totalEnergyKcal)
    }

    private fun parsedBike(payload: ByteArray) =
        BluetoothFitnessMachine.parse(BluetoothFitnessMachine.indoorBikeDataUuid, payload)!!

    private fun parsedTreadmill(payload: ByteArray) =
        BluetoothFitnessMachine.parse(BluetoothFitnessMachine.treadmillDataUuid, payload)!!

    private fun parsedRower(payload: ByteArray) =
        BluetoothFitnessMachine.parse(BluetoothFitnessMachine.rowerDataUuid, payload)!!

    /** Flags 0x1FFE: bit 0 clear, so the instantaneous speed is present, and bits 1..12 all set. */
    private fun fullBikePacket(resistanceOctets: Int): ByteArray = packet(
        le16(0x1FFE),
        le16(2_534), // 25.34 km/h
        le16(2_400), // average speed
        le16(180), // 90 rpm, at the field's half-rpm resolution
        le16(170), // average cadence
        le24(12_345), // 12,345 m
        if (resistanceOctets == 1) intArrayOf(75) else le16(75),
        le16(245), // 245 W
        le16(230), // average power
        le16(321) + le16(1_200) + intArrayOf(20), // 321 kcal, per hour, per minute
        intArrayOf(148), // 148 bpm
        intArrayOf(11), // metabolic equivalent
        le16(900), // 900 s
        le16(300), // 300 s remaining
    )

    /** The same for the treadmill, whose ambiguous pair is the two pace fields. */
    private fun fullTreadmillPacket(paceOctets: Int): ByteArray = packet(
        le16(0x1FFE),
        le16(1_000), // 10.00 km/h
        le16(980), // average speed
        le24(2_000), // 2,000 m
        le16(25), // 2.5 % incline
        le16(0), // 0.0 degree ramp
        le16(12), // positive elevation gain
        le16(3), // negative elevation gain
        if (paceOctets == 1) intArrayOf(120) else le16(120),
        if (paceOctets == 1) intArrayOf(130) else le16(130),
        le16(150) + le16(600) + intArrayOf(10), // 150 kcal
        intArrayOf(140), // 140 bpm
        intArrayOf(9), // metabolic equivalent
        le16(600), // 600 s
        le16(120), // 120 s remaining
        le16(300), // 300 N on the belt
        le16(200), // 200 W
    )

    private fun bikePrimary(power: Int = 245): ByteArray = packet(
        le16(0x0050), // distance and instantaneous power; speed present
        le16(2_534),
        le24(12_345),
        le16(power),
    )

    private fun treadmillContinuation(): ByteArray = packet(
        le16(0x0181), // More Data; energy and heart rate
        le16(150) + le16(600) + intArrayOf(10),
        intArrayOf(140),
    )

    private fun rowerPrimary(strokeCount: Int): ByteArray = packet(
        le16(0x0024), // distance and instantaneous power; stroke rate and count present
        intArrayOf(60), // 30 strokes per minute, at the field's half-stroke resolution
        le16(strokeCount),
        le24(1_000),
        le16(200),
    )

    private fun rowerContinuation(): ByteArray = packet(
        le16(0x0301), // More Data; energy and heart rate
        le16(100) + le16(400) + intArrayOf(7), // 100 kcal
        intArrayOf(150), // 150 bpm
    )

    private fun keiserPacket(
        minor: Int = 0x24,
        dataType: Int = 0x00,
        minutes: Int = 1,
        seconds: Int = 0,
        distanceWord: Int = 0x8002,
        gear: Int? = 8,
    ): ByteArray {
        val body = packet(
            intArrayOf(0x06, minor, dataType, 0x00),
            le16(820), // 82.0 rpm
            le16(0), // no strap in range
            le16(52), // 52 W
            le16(2), // 2 kcal
            intArrayOf(minutes, seconds),
            le16(distanceWord),
        )
        return if (gear == null) body else body + gear.toByte()
    }

    private fun distanceOf(distanceWord: Int): Int? =
        BluetoothKeiserM3i.parse(keiserPacket(distanceWord = distanceWord))!!.totalDistanceMeters

    private fun elapsedOf(minutes: Int, seconds: Int): Int? =
        BluetoothKeiserM3i.parse(keiserPacket(minutes = minutes, seconds = seconds))!!.elapsedSeconds

    private fun statusOne(
        strokeRate: Int = 0,
        ergType: Int = 0,
        heartRate: Int = 0,
        speedMillimetres: Int = 0,
    ): Concept2Pm5Measurement = BluetoothConcept2Pm5.parse(
        BluetoothConcept2Pm5.additionalStatusOneUuid,
        ByteArray(17).also {
            it[3] = speedMillimetres.toByte()
            it[4] = (speedMillimetres ushr 8).toByte()
            it[5] = strokeRate.toByte()
            it[6] = heartRate.toByte()
            it[16] = ergType.toByte()
        },
    )!!

    private fun statusTwo(watts: Int, kcal: Int): Concept2Pm5Measurement = BluetoothConcept2Pm5.parse(
        BluetoothConcept2Pm5.additionalStatusTwoUuid,
        ByteArray(20).also {
            it[4] = watts.toByte()
            it[5] = (watts ushr 8).toByte()
            it[6] = kcal.toByte()
            it[7] = (kcal ushr 8).toByte()
        },
    )!!

    private fun generalStatus(centiseconds: Int, decimetres: Int): Concept2Pm5Measurement =
        BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.generalStatusUuid,
            ByteArray(19).also {
                it[0] = centiseconds.toByte()
                it[1] = (centiseconds ushr 8).toByte()
                it[2] = (centiseconds ushr 16).toByte()
                it[3] = decimetres.toByte()
                it[4] = (decimetres ushr 8).toByte()
                it[5] = (decimetres ushr 16).toByte()
            },
        )!!

    private fun le16(value: Int) = intArrayOf(value and 0xFF, (value ushr 8) and 0xFF)

    private fun le24(value: Int) = le16(value) + intArrayOf((value ushr 16) and 0xFF)

    private fun packet(vararg parts: IntArray): ByteArray {
        val octets = parts.flatMap { it.asList() }
        return ByteArray(octets.size) { octets[it].toByte() }
    }
}
