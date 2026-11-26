package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Malformed and edge-of-range input to the SIG profile parsers: the bounds-checked reader every
 * clinical profile shares, the two IEEE 11073 decimal formats those profiles carry their numbers
 * in, and the four sport and glucose decoders under reserved flag bits, octets above 0x7F,
 * counters at full scale and fields whose legitimate value is zero.
 */
class BleParsingEdgeCasesTest {
    private val arrival = LocalDateTime.of(2026, 9, 4, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** +INFINITY, NaN, NRes, RFU and -INFINITY, as the spec table prints them: whole words. */
    private val reservedSfloatWords = 0x07FE..0x0802
    private val reservedFloatWords = 0x007F_FFFEL..0x0080_0002L

    /**
     * The decoder promises what the spec table prints and its KDoc states: five reserved wire
     * values, each with a zero exponent. Masking the exponent away to catch a non-conforming
     * +INFINITY at 0xF7FE would also throw away 0xE7FE, a legal 20.46 kPa cuff reading, and the
     * only caller drops a null with no trace — so the whole word is what is matched.
     */
    @Test
    fun `only the five reserved SFLOAT words are unavailable`() {
        for (word in reservedSfloatWords) {
            assertNull("SFLOAT 0x${word.toString(16)}", Ieee11073.decodeSfloat(word))
        }
        for (exponent in 0x1..0xF) {
            for (word in reservedSfloatWords) {
                val raw = (exponent shl 12) or word
                assertNotNull("SFLOAT 0x${raw.toString(16)}", Ieee11073.decodeSfloat(raw))
            }
        }
        assertEquals(204.6, Ieee11073.decodeSfloat(0xF7FE)!!, 1e-9)
        assertEquals(20.46, Ieee11073.decodeSfloat(0xE7FE)!!, 1e-9)
    }

    /**
     * The same contract on the 32-bit form, which is the one the thermometer puts on the wire. HTS
     * names the constants, so a reserved mantissa carried at some other exponent is a number this
     * decoder is entitled to take at face value rather than a fifth way of saying "no reading".
     */
    @Test
    fun `only the five reserved FLOAT words are unavailable`() {
        for (word in reservedFloatWords) {
            assertNull("FLOAT 0x${word.toString(16)}", Ieee11073.decodeFloat(word))
        }
        for (exponent in 0x01..0xFF) {
            for (word in reservedFloatWords) {
                val raw = (exponent.toLong() shl 24) or word
                assertNotNull("FLOAT 0x${raw.toString(16)}", Ieee11073.decodeFloat(raw))
            }
        }
        assertEquals(838_860.7, Ieee11073.decodeFloat(0xFF7F_FFFFL)!!, 1e-6)
    }

    /**
     * Zero mg/dL and zero kilograms are readings a real instrument sends, and the reserved band
     * begins one bit away from them. A decoder that folds "unavailable" and "zero" together loses
     * the first sample after a scale is tared.
     */
    @Test
    fun `zero is a measurement, not an absent value`() {
        assertEquals(0.0, Ieee11073.decodeSfloat(0x0000)!!, 1e-12)
        assertEquals(0.0, Ieee11073.decodeFloat(0x0000_0000L)!!, 1e-12)
        // A zero mantissa scaled by any exponent is still zero, and still a reading.
        assertEquals(0.0, Ieee11073.decodeSfloat(0xF000)!!, 1e-12)
        assertEquals(0.0, Ieee11073.decodeFloat(0xFF00_0000L)!!, 1e-12)
    }

    /**
     * The largest and smallest mantissas the spec leaves usable sit one step outside +INFINITY and
     * -INFINITY. They are exactly what a saturating sensor reports, so an off-by-one in the
     * reserved range costs the two readings that matter most.
     */
    @Test
    fun `the mantissas either side of the reserved band are ordinary numbers`() {
        assertEquals(2045.0, Ieee11073.decodeSfloat(0x07FD)!!, 1e-9)
        assertEquals(-2045.0, Ieee11073.decodeSfloat(0x0803)!!, 1e-9)
        assertEquals(8_388_605.0, Ieee11073.decodeFloat(0x007F_FFFDL)!!, 1e-9)
        assertEquals(-8_388_605.0, Ieee11073.decodeFloat(0x0080_0003L)!!, 1e-9)
    }

    /**
     * decodeFloat takes a Long precisely because a caller that assembles four octets into an Int
     * and widens it hands over a negative number. Losing the mask turns the exponent nibble into
     * nonsense and the reserved-value test never matches again.
     */
    @Test
    fun `a FLOAT word that arrived negative decodes the same as its unsigned twin`() {
        // 0xFFFFFFFF: mantissa 0xFFFFFF = -1, exponent 0xFF = -1.
        assertEquals(-0.1, Ieee11073.decodeFloat(-1L)!!, 1e-12)
        assertEquals(-0.1, Ieee11073.decodeFloat(0xFFFF_FFFFL)!!, 1e-12)
        assertEquals(Ieee11073.decodeFloat(0xFF00_016CL), Ieee11073.decodeFloat(-16_776_852L))
    }

    /**
     * Kotlin's Byte is signed. One missing mask, or an Int-wide u32, turns a full-scale cuff
     * pressure or a four-billion-count cumulative field negative for all five profiles that share
     * this one reader.
     */
    @Test
    fun `every width of the shared reader reads a full-scale octet as unsigned`() {
        val packet = BluetoothMedicalPacket(ByteArray(10) { 0xFF.toByte() })
        assertEquals(255, packet.u8())
        assertEquals(65_535, packet.u16())
        assertEquals(16_777_215, packet.u24())
        assertEquals(4_294_967_295L, packet.u32())
        assertTrue("ten octets written, and the reader did not consume all of them", packet.exhausted)
    }

    /**
     * A notification arriving one octet short must raise the type every caller catches. An
     * ArrayIndexOutOfBoundsException escapes that catch and kills the BLE callback thread, which
     * ends the whole sync rather than one reading.
     */
    @Test
    fun `a clipped packet raises the typed malformed signal, never an index error`() {
        val malformed = MalformedBluetoothMedicalPacket::class.java
        assertThrows(malformed) { BluetoothMedicalPacket(ByteArray(0)).u8() }
        assertThrows(malformed) { BluetoothMedicalPacket(bytes(0x01)).u16() }
        assertThrows(malformed) { BluetoothMedicalPacket(bytes(0x01, 0x02)).u24() }
        assertThrows(malformed) { BluetoothMedicalPacket(bytes(0x01, 0x02, 0x03)).u32() }
        assertThrows(malformed) { BluetoothMedicalPacket(bytes(0x01)).sfloat() }
        assertThrows(malformed) { BluetoothMedicalPacket(bytes(0x01, 0x02, 0x03)).float() }
        // A seven-octet Date Time clipped to six: 2026-09-04 11:59 with the seconds cut off.
        assertThrows(malformed) {
            BluetoothMedicalPacket(bytes(0xEA, 0x07, 0x09, 0x04, 0x0B, 0x3B))
                .deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC)
        }
    }

    /**
     * Ten 365-day years is 315,360,000,000 ms, which overflows a signed Int. If the constant is
     * ever rewritten with Int literals the window collapses and every device timestamp is thrown
     * away in silence — a probe at nine and eleven years survives that, this one does not.
     */
    @Test
    fun `the clock window reaches exactly ten 365-day years back`() {
        val tenYearsMs = 315_360_000_000L
        assertTrue("the documented edge is inclusive", isPlausibleDeviceTime(arrival - tenYearsMs, arrival))
        assertFalse("one millisecond past it", isPlausibleDeviceTime(arrival - tenYearsMs - 1, arrival))
        val edge = LocalDateTime.ofInstant(Instant.ofEpochMilli(arrival - tenYearsMs), ZoneOffset.UTC)
        assertTrue("the wall-clock overload uses the same window", edge.isPlausibleDeviceTime(arrival, ZoneOffset.UTC))
    }

    /**
     * Five minutes ahead is the allowance for two clocks that disagree about the minute. This edge
     * decides whether a reading is dated by the cuff or by its arrival, and a strict-versus-
     * inclusive slip is invisible at the four- and six-minute probes.
     */
    @Test
    fun `the clock window reaches exactly five minutes ahead`() {
        assertTrue("the documented edge is inclusive", isPlausibleDeviceTime(arrival + 300_000L, arrival))
        assertFalse("one millisecond past it", isPlausibleDeviceTime(arrival + 300_001L, arrival))
    }

    /**
     * The LocalDateTime overload exists only to apply a zone. If it ever compared the wall clock
     * against the phone's wall clock instead of converting to an instant, both directions would
     * pass and a device an hour ahead would be believed.
     */
    @Test
    fun `a device wall clock is judged in the zone it is given`() {
        val wall = LocalDateTime.of(2026, 9, 4, 12, 0)
        assertTrue("the same instant as arrival", wall.isPlausibleDeviceTime(arrival, ZoneOffset.UTC))
        // In -01:00 that wall clock is 13:00Z, an hour past arrival and past the five-minute allowance.
        assertFalse("an hour ahead of arrival", wall.isPlausibleDeviceTime(arrival, ZoneOffset.ofHours(-1)))
        // In +01:00 it is 11:00Z, an hour behind, which the ten-year allowance covers.
        assertTrue("an hour behind arrival", wall.isPlausibleDeviceTime(arrival, ZoneOffset.ofHours(1)))
    }

    /**
     * Calendar validity is delegated to java.time and caught as DateTimeException. A hand-rolled
     * day-of-month check breaks on 29 February and nowhere else, and the suite's only impossible
     * date is 31 February, which even a naive check rejects.
     */
    @Test
    fun `a leap day is dated and 29 February of a common year is refused`() {
        val leapDay = BluetoothMedicalPacket(bytes(0xE8, 0x07, 0x02, 0x1D, 0x0C, 0x00, 0x00))
        assertEquals(
            LocalDateTime.of(2024, 2, 29, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            leapDay.deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC),
        )
        val impossible = BluetoothMedicalPacket(bytes(0xEA, 0x07, 0x02, 0x1D, 0x0C, 0x00, 0x00))
        assertNull("2026-02-29 does not exist", impossible.deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC))
    }

    /**
     * The calendar guard screens only the year, month and day; the hour, minute and second reach
     * LocalDateTime.of unchecked. A garbled octet must cost the timestamp, not the measurement and
     * not the callback thread.
     */
    @Test
    fun `every out-of-range clock field returns null rather than throwing`() {
        val garbled = listOf(
            "hour 24" to bytes(0xEA, 0x07, 0x09, 0x04, 0x18, 0x00, 0x00),
            "minute 60" to bytes(0xEA, 0x07, 0x09, 0x04, 0x0C, 0x3C, 0x00),
            "second 60" to bytes(0xEA, 0x07, 0x09, 0x04, 0x0C, 0x00, 0x3C),
            "month 13" to bytes(0xEA, 0x07, 0x0D, 0x04, 0x0C, 0x00, 0x00),
            "day 32" to bytes(0xEA, 0x07, 0x09, 0x20, 0x0C, 0x00, 0x00),
        )
        garbled.forEach { (what, payload) ->
            assertNull(
                what,
                BluetoothMedicalPacket(payload).deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC),
            )
        }
    }

    /**
     * Bluetooth Date Time carries no zone, so the phone's is applied to a wall clock that, on the
     * morning the clocks go forward, never happened. java.time shifts a gap forward by the offset
     * change: 02:30 becomes 03:30 EDT. A cuff reading taken during the changeover has to land on
     * the right day rather than be discarded.
     */
    @Test
    fun `a reading inside the spring-forward gap is dated deterministically`() {
        val newYork = ZoneId.of("America/New_York")
        val gapArrival = Instant.parse("2026-03-08T08:00:00Z").toEpochMilli()
        // 2026-03-08 02:30:00 local; New York starts daylight time at 02:00 that morning.
        val gap = bytes(0xEA, 0x07, 0x03, 0x08, 0x02, 0x1E, 0x00)
        val dated = BluetoothMedicalPacket(gap).deviceDateTimeEpochMillis(gapArrival, newYork)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), dated)
        assertEquals(
            "the same wire bytes must always give the same instant",
            dated,
            BluetoothMedicalPacket(gap).deviceDateTimeEpochMillis(gapArrival, newYork),
        )
    }

    /**
     * The hour that happens twice is where an import loses idempotence: the same payload re-read
     * must yield the same instant or Health Connect's clientRecordId upsert stops matching and the
     * user gets a second, differently timed record. java.time takes the earlier offset, EDT.
     */
    @Test
    fun `a reading inside the fall-back overlap picks the earlier offset`() {
        val newYork = ZoneId.of("America/New_York")
        val overlapArrival = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        // 2026-11-01 01:30:00 local occurs at 05:30Z (EDT) and again at 06:30Z (EST).
        val overlap = bytes(0xEA, 0x07, 0x0B, 0x01, 0x01, 0x1E, 0x00)
        val dated = BluetoothMedicalPacket(overlap).deviceDateTimeEpochMillis(overlapArrival, newYork)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), dated)
        assertEquals(
            "a re-import of the same payload must not move the reading",
            dated,
            BluetoothMedicalPacket(overlap).deviceDateTimeEpochMillis(overlapArrival, newYork),
        )
    }

    /**
     * RSC 1.1 reserves flag bits 3-7, and this parser ignores them on purpose. It accumulates
     * `offset` only from the flags it knows and refuses the record unless that total is the whole
     * payload, so a future flag that carries a field always lengthens the record and is caught
     * there; a flag that carries none cannot move an offset. Refusing the bit instead would cost a
     * whole treadmill session's speed and cadence to one junk bit of vendor firmware, silently.
     */
    @Test
    fun `a reserved running flag bit is ignored and the length still governs`() {
        for (bit in 3..7) {
            val flags = 1 shl bit
            val record = BluetoothRunningSpeedCadence.parse(bytes(flags, 0x00, 0x03, 0xAA))
            assertNotNull("RSC flags 0x${flags.toString(16)}", record)
            assertEquals(3.0, record!!.speedMps, 1e-9)
            assertEquals(170, record.cadenceSpm)
            assertNull("no field was claimed", record.strideLengthCm)
            assertNull("no field was claimed", record.totalDistanceMeters)
        }
        // Every bit set: both defined optional fields are present and the length is exactly right.
        val everything = BluetoothRunningSpeedCadence.parse(
            bytes(0xFF, 0x00, 0x04, 0xB4, 0x76, 0x00, 0x31, 0xD4, 0x00, 0x00),
        )
        assertNotNull("every flag bit set", everything)
        assertEquals(4.0, everything!!.speedMps, 1e-9)
        assertEquals(180, everything.cadenceSpm)
        assertEquals(118, everything.strideLengthCm)
        assertEquals(5432.1, everything.totalDistanceMeters!!, 1e-9)
        assertTrue("bit 2 says the pod calls this running", everything.running)
        // The length check is what actually guards the offsets: one unaccounted octet is refused,
        // reserved bit or not.
        assertNull(
            "a trailing octet no flag accounts for",
            BluetoothRunningSpeedCadence.parse(bytes(0x08, 0x00, 0x03, 0xAA, 0x00)),
        )
    }

    /**
     * Every other speed in the suite has a low octet below 0x80 and every cadence is below 128, so
     * a signed read of either would have gone unnoticed. Sign-extending the speed's low octet gives
     * -0.5 m/s, and a cadence of -1 makes any "cadence > 0" gate read a sprint as a standstill.
     */
    @Test
    fun `running speed and cadence octets are read unsigned`() {
        // Speed raw 0x0180 = 384 units of 1/256 m/s.
        val fast = BluetoothRunningSpeedCadence.parse(bytes(0x00, 0x80, 0x01, 0x64))!!
        assertEquals(1.5, fast.speedMps, 1e-9)
        assertEquals(5.4, fast.speedKph, 1e-9)
        assertEquals(100, fast.cadenceSpm)

        // Speed raw 0x0100 = 1 m/s; the cadence octet is a uint8 at full scale.
        val implausible = BluetoothRunningSpeedCadence.parse(bytes(0x00, 0x00, 0x01, 0xFF))!!
        assertEquals(1.0, implausible.speedMps, 1e-9)
        assertEquals(255, implausible.cadenceSpm)
    }

    /**
     * A foot pod's lifetime odometer is the field likeliest to reach full uint32 scale, and u32 is
     * assembled from two 16-bit halves: shifting the high half before widening it wraps the total
     * negative. The suite's long-run fixture stops at a million tenths, which never touches the
     * top bit.
     */
    @Test
    fun `a lifetime odometer at full uint32 scale stays positive`() {
        val result = BluetoothRunningSpeedCadence.parse(
            bytes(0x02, 0x00, 0x02, 0x64, 0xFF, 0xFF, 0xFF, 0xFF),
        )!!
        val distance = result.totalDistanceMeters!!

        assertTrue("was $distance", distance > 0.0)
        assertEquals(429_496_729.5, distance, 1e-6)
        assertEquals(2.0, result.speedMps, 1e-9)
        assertNull(result.strideLengthCm)
    }

    /**
     * Stride length alone is the one flag combination where the optional cursor advances by two and
     * then has to stop. The suite covers no optional fields, distance only, and both — an advance
     * written as +4 for stride survives all three of those and fails only here.
     */
    @Test
    fun `a stride-length-only record decodes at the right offsets`() {
        val result = BluetoothRunningSpeedCadence.parse(bytes(0x01, 0x00, 0x02, 0x64, 0xFF, 0xFF))!!

        assertEquals(65_535, result.strideLengthCm)
        assertNull(result.totalDistanceMeters)
        assertEquals(2.0, result.speedMps, 1e-9)
        assertEquals(100, result.cadenceSpm)
        assertFalse(result.running)
    }

    /**
     * Raw zero means the rider is putting everything on the other pedal, which a one-sided meter
     * really does emit. The reference is derived from the balance being non-null, so a refactor
     * that folds zero into "absent" erases the reading and silently nulls the reference with it.
     */
    @Test
    fun `a pedal balance of exactly zero keeps its reference`() {
        val result = BluetoothCyclingPower.parse(bytes(0x03, 0x00, 0x64, 0x00, 0x00))!!

        assertEquals(0.0, result.pedalPowerBalancePercent!!, 0.0)
        assertEquals(CyclingPowerPedalReference.LEFT, result.pedalPowerBalanceReference)
        assertEquals(100, result.instantaneousPowerWatts)
    }

    /**
     * The first notification after a torque meter powers up carries an accumulated total of zero.
     * Losing the source along with it changes how the caller integrates the counter, because wheel
     * torque and crank torque are not the same quantity.
     */
    @Test
    fun `an accumulated torque of exactly zero keeps its source`() {
        val crank = BluetoothCyclingPower.parse(bytes(0x0C, 0x00, 0x64, 0x00, 0x00, 0x00))!!
        assertEquals(0.0, crank.accumulatedTorqueNewtonMeters!!, 0.0)
        assertEquals(CyclingPowerTorqueSource.CRANK, crank.accumulatedTorqueSource)

        val wheel = BluetoothCyclingPower.parse(bytes(0x04, 0x00, 0x64, 0x00, 0x00, 0x00))!!
        assertEquals(0.0, wheel.accumulatedTorqueNewtonMeters!!, 0.0)
        assertEquals(CyclingPowerTorqueSource.WHEEL, wheel.accumulatedTorqueSource)
    }

    /**
     * The one bit-packed pair in the package: the maximum angle occupies bits 0-11 and the minimum
     * bits 12-23 of a single u24. The suite's fixture is 0x123ABC, whose top bit is clear, so a
     * missing mask on the maximum or a signed shift on the minimum passes it unnoticed.
     */
    @Test
    fun `extreme angles at full scale split into two 12-bit fields`() {
        val bothFull = BluetoothCyclingPower.parse(bytes(0x00, 0x01, 0x00, 0x00, 0xFF, 0xFF, 0xFF))!!
        assertEquals(4_095, bothFull.maximumAngleDegrees)
        assertEquals(4_095, bothFull.minimumAngleDegrees)

        // Packed 0xFFF000: maximum zero, minimum full scale — an asymmetry no symmetric mistake in
        // the mask or the shift can reproduce.
        val minimumOnly = BluetoothCyclingPower.parse(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0xF0, 0xFF))!!
        assertEquals(0, minimumOnly.maximumAngleDegrees)
        assertEquals(4_095, minimumOnly.minimumAngleDegrees)
    }

    /**
     * CPM defines flag bits 0-12 and reserves 13-15. The suite rejects 0x2000 but never asserts
     * that the highest defined bit is still accepted, so a mask widened to 0xF000 would pass it.
     * Bit 15 is also where reading the flags' second octet as a signed byte changes the answer.
     */
    @Test
    fun `the cycling power RFU boundary sits between bit 12 and bit 13`() {
        val highestDefined = BluetoothCyclingPower.parse(bytes(0x00, 0x10, 0x00, 0x00))!!
        assertTrue("offset compensation is bit 12", highestDefined.offsetCompensationRequired)
        assertEquals(0, highestDefined.instantaneousPowerWatts)

        for (bit in 13..15) {
            val flags = 1 shl bit
            assertNull(
                "CPM flags 0x${flags.toString(16)}",
                BluetoothCyclingPower.parse(bytes(0x00, flags shr 8, 0x00, 0x00)),
            )
        }
    }

    /**
     * A freshly reset wheel sensor sends zero revolutions at tick zero. This parser says "field
     * absent" with null and "field present" with a number; if the two ever collapse, the first
     * distance delta after a reset is computed against nothing and comes out enormous.
     */
    @Test
    fun `an all-zero CSC wheel record decodes to zero, not to nothing`() {
        val result = BluetoothCyclingSpeedCadence.parse(bytes(0x01, 0, 0, 0, 0, 0, 0))!!

        assertEquals(0L, result.cumulativeWheelRevolutions)
        assertEquals(0, result.lastWheelEventTimeTicks)
        assertNull(result.cumulativeCrankRevolutions)
        assertNull(result.lastCrankEventTimeTicks)
    }

    /**
     * 0x81 as a signed byte is -127, and the suite's only RFU probe is 0x04, whose sign bit is
     * clear. A record whose flags cannot be trusted must not have its payload read at all, however
     * plausible the length looks.
     */
    @Test
    fun `a CSC flags octet with the high bit set is read unsigned and refused`() {
        // Reserved bit 7 beside a wheel-data bit, with a wheel payload of exactly the right length.
        assertNull("0x81", BluetoothCyclingSpeedCadence.parse(bytes(0x81, 0, 0, 0, 0, 0, 0)))
        // Bit 7 alone: no data flag either, so there is nothing to report even were it legal.
        assertNull("0x80", BluetoothCyclingSpeedCadence.parse(bytes(0x80)))
        // Every bit set, with wheel and crank payloads both present.
        assertNull("0xFF", BluetoothCyclingSpeedCadence.parse(bytes(0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    /**
     * GLS 1.0.1 fixes the Response Code response at exactly four octets. The empty notification is
     * the one that matters: the size guard is all that stands between it and an index error on the
     * GATT callback thread, which would end the whole sync rather than one reply.
     */
    @Test
    fun `a RACP report response of the wrong length is refused`() {
        assertNull("empty notification", BluetoothGlucoseRacp.parseReportCompletion(ByteArray(0)))
        assertNull(
            "a meter that pads its reply to five octets",
            BluetoothGlucoseRacp.parseReportCompletion(bytes(0x06, 0x00, 0x01, 0x01, 0x00)),
        )
    }

    /**
     * The operator is the one octet of the four that no other assertion varies. The spec
     * requires the Null operator on a Response Code response; anything else belongs to a procedure
     * this app never issued, and reading its result as ours reports someone else's success.
     */
    @Test
    fun `a RACP response whose operator is not Null is refused`() {
        for (operator in 1..6) {
            assertNull(
                "operator $operator",
                BluetoothGlucoseRacp.parseReportCompletion(bytes(0x06, operator, 0x01, 0x01)),
            )
        }
    }

    /**
     * A meter that will not run Report Stored Records answers 0x02; one that fails an abort answers
     * 0x05. Success and No Records are matched ahead of the range, so 0x02 is the lowest code the
     * range branch ever sees — turning it into null shows a parse failure instead of the reason the
     * sync brought nothing back.
     */
    @Test
    fun `every defined RACP refusal reaches the caller as its own code`() {
        // 6 is No Records rather than an error, and 8 and 9 are asserted elsewhere.
        for (code in listOf(2, 3, 4, 5, 7)) {
            assertEquals(
                "response code $code",
                GlucoseRacpCompletion.Error(code),
                BluetoothGlucoseRacp.parseReportCompletion(bytes(0x06, 0x00, 0x01, code)),
            )
        }
    }

    /**
     * A wrong base UUID, or a short id off by one digit — 0x2A5B and 0x2A53 differ by one — means
     * the app never discovers the sensor at all, and there is no parse error to point at. These
     * nine values are asserted nowhere else.
     */
    @Test
    fun `the SIG service and characteristic UUIDs are the assigned ones and all distinct`() {
        // A 16-bit id sits in the first group of the SIG base UUID, zero padded to four digits.
        assertEquals("00000001-0000-1000-8000-00805f9b34fb", bluetoothUuid(0x0001).toString())

        val assigned = listOf(
            "00001818" to BluetoothCyclingPower.serviceUuid,
            "00002a63" to BluetoothCyclingPower.measurementUuid,
            "00002a65" to BluetoothCyclingPower.featureUuid,
            "00001816" to BluetoothCyclingSpeedCadence.serviceUuid,
            "00002a5b" to BluetoothCyclingSpeedCadence.measurementUuid,
            "00002a5c" to BluetoothCyclingSpeedCadence.featureUuid,
            "00001814" to BluetoothRunningSpeedCadence.serviceUuid,
            "00002a53" to BluetoothRunningSpeedCadence.measurementUuid,
            "00002a54" to BluetoothRunningSpeedCadence.featureUuid,
        )
        assigned.forEach { (shortId, uuid) ->
            assertEquals("$shortId-0000-1000-8000-00805f9b34fb", uuid.toString())
        }
        assertEquals("nine ids must give nine UUIDs", 9, assigned.map { it.second }.toSet().size)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
