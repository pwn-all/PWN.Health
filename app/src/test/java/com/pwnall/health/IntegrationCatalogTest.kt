package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class IntegrationCatalogTest {
    @Test
    fun parsesStandardMetricWeight() {
        val result = BluetoothWeightScale.parse(
            byteArrayOf(0x00, 0x80.toByte(), 0x3E),
            fallbackOccurredAt = 1234L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(80.0, result.weightKg, 0.001)
        assertEquals(1234L, result.occurredAt)
        assertNull(result.heightCm)
    }

    @Test
    fun parsesTimestampUserBmiAndHeight() {
        val result = BluetoothWeightScale.parse(
            byteArrayOf(
                0x0E, 0xB0.toByte(), 0x36, // 70.0 kg
                0xEA.toByte(), 0x07, 0x07, 0x1E, 0x0A, 0x14, 0x1E, // 2026-07-30 10:20:30
                0x03, // user
                0xF0.toByte(), 0x00, // BMI 24.0
                0xD6.toByte(), 0x06, // 175.0 cm
            ),
            fallbackOccurredAt = 1_785_406_830_000L + 60_000L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(70.0, result.weightKg, 0.001)
        assertEquals(175, result.heightCm)
        assertEquals(24.0, result.bmi!!, 0.001)
        assertEquals(3, result.userId)
        assertEquals(1_785_406_830_000L, result.occurredAt)
        assertEquals(true, result.deviceClockSet)
    }

    /**
     * 2000-01-01 is a valid Date Time and the factory default of a clock nobody set; a weighing
     * stamped with it is dated on arrival instead, and the flag says why.
     */
    @Test
    fun aScaleWithAnUnsetClockIsDatedOnArrival() {
        val arrival = 1_785_406_830_000L
        val result = BluetoothWeightScale.parse(
            byteArrayOf(0x02, 0xB0.toByte(), 0x36, 0xD0.toByte(), 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(70.0, result.weightKg, 0.001)
        assertEquals(arrival, result.occurredAt)
        assertEquals(false, result.deviceClockSet)
        assertNull(BluetoothWeightScale.parse(byteArrayOf(0x00, 0x80.toByte(), 0x3E))!!.deviceClockSet)
    }

    /** 66.9 in is 169.9 cm; truncation would make it 169 and a 175.5 cm reading 175. */
    @Test
    fun heightRoundsToTheNearestCentimetre() {
        val imperial = BluetoothWeightScale.parse(
            byteArrayOf(0x09, 0xE5.toByte(), 0x44, 0xF0.toByte(), 0x00, 0x9D.toByte(), 0x02), // 66.9 in
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(170, imperial.heightCm)

        val metric = BluetoothWeightScale.parse(
            byteArrayOf(0x08, 0xB0.toByte(), 0x36, 0xF0.toByte(), 0x00, 0xDB.toByte(), 0x06), // 175.5 cm
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(176, metric.heightCm)
    }

    @Test
    fun parsesImperialWeight() {
        val result = BluetoothWeightScale.parse(
            byteArrayOf(0x01, 0xE5.toByte(), 0x44),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(80.0, result.weightKg, 0.05)
    }

    @Test
    fun parsesXiaomiMiScaleV2StableAdvertisement() {
        val result = XiaomiMiScaleV2.parse(
            byteArrayOf(
                0x02, 0x26, // timestamp, impedance, weight, Xiaomi stable flag
                0xB2.toByte(), 0x07, 0x05, 0x04, 0x0F, 0x02, 0x01,
                0xAC.toByte(), 0x01, // 428 ohm
                0x86.toByte(), 0x42, // 85.15 kg
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(85.15, result.weightKg, 0.001)
        assertEquals(428, result.impedanceOhms)
        assertTrue(result.isStable)
        assertEquals("Xiaomi Mi Body Composition Scale v2", result.sourceModel)
    }

    @Test
    fun parsesXiaomiMiScaleV1StableAdvertisement() {
        val result = XiaomiMiScaleV1.parse(
            byteArrayOf(
                0x22, 0x9E.toByte(), 0x43, // 86.55 kg, timestamp, Xiaomi stable
                0xE5.toByte(), 0x07, 0x04, 0x0B, 0x10, 0x13, 0x01,
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(86.55, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertEquals("Xiaomi Mi Smart Scale v1", result.sourceModel)
    }

    @Test
    fun marksXiaomiMiScaleV1WeightRemovedPacketAsUnstable() {
        val result = XiaomiMiScaleV1.parse(
            byteArrayOf(
                0xA2.toByte(), 0x20, 0x44, // 87.2 kg, stable bit + weight removed bit
                0xB2.toByte(), 0x07, 0x01, 0x01, 0x0A, 0x1A, 0x15,
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(87.2, result.weightKg, 0.001)
        assertTrue(!result.isStable)
    }

    /**
     * A Mi Scale v2 advertisement is always thirteen bytes, the two impedance bytes included: a packet
     * trimmed to fit a flag-driven parser would certify the very bug it is meant to guard.
     */
    @Test
    fun keepsXiaomiUnstableMeasurementMarkedAsUnstable() {
        val result = XiaomiMiScaleV2.parse(
            byteArrayOf(
                0x02, 0x04, // kilograms, no stabilized bit
                0xB2.toByte(), 0x07, 0x01, 0x01, 0x12, 0x10, 0x1A,
                0x00, 0x00, // impedance not measured yet
                0xA8.toByte(), 0x52, // 105.8 kg
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(105.8, result.weightKg, 0.001)
        assertTrue(!result.isStable)
        assertNull(result.impedanceOhms)
    }

    /** Stabilized but the person has already stepped off: the reading is stale, not final. */
    @Test
    fun treatsXiaomiWeightRemovedAsUnstable() {
        val result = XiaomiMiScaleV2.parse(
            byteArrayOf(
                0x02, 0xA0.toByte(), // stabilized bit and weight-removed bit together
                0xB2.toByte(), 0x07, 0x01, 0x01, 0x12, 0x10, 0x1A,
                0x00, 0x00,
                0xA8.toByte(), 0x52,
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(105.8, result.weightKg, 0.001)
        assertTrue(!result.isStable)
    }

    /**
     * The scale advertises the stable weight before it has measured the impedance, and bit 1 of the
     * second control byte says which frame is which. Without it the two bytes are not a
     * measurement, whatever they hold, and they stay out of the body-fat estimate.
     */
    @Test
    fun ignoresXiaomiImpedanceBytesTheScaleHasNotVouchedFor() {
        val result = XiaomiMiScaleV2.parse(
            byteArrayOf(
                0x02, 0x20, // kilograms; stabilized, impedance bit clear
                0xB2.toByte(), 0x07, 0x05, 0x04, 0x0F, 0x02, 0x01,
                0xAC.toByte(), 0x01, // would read as 428 ohm
                0x86.toByte(), 0x42, // 85.15 kg
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertTrue(result.isStable)
        assertEquals(85.15, result.weightKg, 0.001)
        assertNull(result.impedanceOhms)
    }

    /** Anything but thirteen bytes is some other device's advertisement. */
    @Test
    fun rejectsXiaomiPacketsOfTheWrongLength() {
        val real = byteArrayOf(
            0x02, 0x26,
            0xB2.toByte(), 0x07, 0x05, 0x04, 0x0F, 0x02, 0x01,
            0xAC.toByte(), 0x01,
            0x86.toByte(), 0x42,
        )
        assertNull(XiaomiMiScaleV2.parse(real + 0x00, zoneId = ZoneOffset.UTC))
        assertNull(XiaomiMiScaleV2.parse(real.copyOf(12), zoneId = ZoneOffset.UTC))
    }

    /** A scale whose clock was never set sends a zero date. That is not a reason to lose the weight. */
    @Test
    fun anUnsetClockDoesNotDiscardTheWeighing() {
        val fallback = 1_600_000_000_000L
        val result = BluetoothWeightScale.parse(
            byteArrayOf(
                0x02, 0xB0.toByte(), 0x36, // kilograms with a timestamp
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // year 0, month 0, day 0
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(70.0, result.weightKg, 0.01)
        assertEquals(fallback, result.occurredAt)
    }

    /**
     * The other half of "clock never set": year zero with a plausible month and day. LocalDateTime
     * accepts it, so unchecked the weighing would be filed two millennia back instead of at arrival time.
     */
    @Test
    fun aZeroYearWithAPlausibleDayAlsoFallsBackToArrivalTime() {
        val fallback = 1_600_000_000_000L
        val standard = BluetoothWeightScale.parse(
            byteArrayOf(
                0x02, 0xB0.toByte(), 0x36, // kilograms with a timestamp
                0x00, 0x00, 0x01, 0x01, 0x0A, 0x14, 0x1E, // year 0, 1 January 10:20:30
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(fallback, standard.occurredAt)

        val xiaomi = XiaomiMiScaleV2.parse(
            byteArrayOf(
                0x02, 0x26, // timestamp, impedance, weight, Xiaomi stable flag
                0x00, 0x00, 0x01, 0x01, 0x0F, 0x02, 0x01, // year 0
                0xAC.toByte(), 0x01, // 428 ohm
                0x86.toByte(), 0x42, // 85.15 kg
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(fallback, xiaomi.occurredAt)
    }

    /** 0xFF is the defined "unknown user", not user number 255. */
    @Test
    fun anUnknownUserIsNotUserTwoHundredAndFiftyFive() {
        val result = BluetoothWeightScale.parse(
            byteArrayOf(0x04, 0xB0.toByte(), 0x36, 0xFF.toByte()),
            zoneId = ZoneOffset.UTC,
        )!!

        assertNull(result.userId)
    }

    @Test
    fun selectsXiaomiAdapterOnlyForKnownNameAndService() {
        assertEquals(
            setOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            XiaomiMiScaleV2.advertisedServiceUuids,
        )
        // Both halves of the name rule, on the real predicate rather than
        // `assertTrue("MIBFS".startsWith("MIBFS", ignoreCase = true))`, which asserts a property of
        // the standard library and runs no app code at all.
        assertTrue(
            XiaomiMiScaleV2.supports(
                "mibfs",
                listOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            ),
        )
        assertTrue(
            XiaomiMiScaleV2.supports(
                "MIBCS",
                listOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            ),
        )
        assertTrue(
            !XiaomiMiScaleV2.supports(
                "Other scale",
                listOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            ),
        )
        assertTrue(
            !XiaomiMiScaleV2.supports(
                null,
                listOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            ),
        )
    }

    @Test
    fun rejectsFailedOrIncompleteMeasurements() {
        assertNull(BluetoothWeightScale.parse(byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte())))
        assertNull(BluetoothWeightScale.parse(byteArrayOf(0x02, 0x80.toByte(), 0x3E)))
        assertNull(BluetoothWeightScale.parse(byteArrayOf(0x00, 0x80.toByte(), 0x3E, 0x00)))
        assertNull(BluetoothWeightScale.parse(byteArrayOf(0x20, 0x80.toByte(), 0x3E)))
    }

    /**
     * Advertisement bytes come from whatever device is in range, so a clipped packet has to return
     * null instead of reaching past the end of the array.
     */
    @Test
    fun everyClippedAdvertisementIsRejected() {
        val standard = byteArrayOf(
            0x0E, 0xB0.toByte(), 0x36,
            0xEA.toByte(), 0x07, 0x07, 0x1E, 0x0A, 0x14, 0x1E,
            0x03,
            0xF0.toByte(), 0x00,
            0xD6.toByte(), 0x06,
        )
        val v1 = byteArrayOf(
            0x22, 0x9E.toByte(), 0x43,
            0xE5.toByte(), 0x07, 0x04, 0x0B, 0x10, 0x13, 0x01,
        )
        val v2 = byteArrayOf(
            0x02, 0x26,
            0xB2.toByte(), 0x07, 0x05, 0x04, 0x0F, 0x02, 0x01,
            0xAC.toByte(), 0x01,
            0x86.toByte(), 0x42,
        )

        for (size in 0 until standard.size) {
            assertNull(
                "standard scale clipped to $size bytes",
                BluetoothWeightScale.parse(standard.copyOf(size), zoneId = ZoneOffset.UTC),
            )
        }
        for (size in 0 until v1.size) {
            assertNull(
                "Mi Scale v1 clipped to $size bytes",
                XiaomiMiScaleV1.parse(v1.copyOf(size), zoneId = ZoneOffset.UTC),
            )
        }
        for (size in 0 until v2.size) {
            assertNull(
                "Mi Scale v2 clipped to $size bytes",
                XiaomiMiScaleV2.parse(v2.copyOf(size), zoneId = ZoneOffset.UTC),
            )
        }
    }

    @Test
    fun weightScaleIsRecognisedByItsService() {
        assertTrue(
            BluetoothWeightScale.supports(null, listOf(BluetoothWeightScale.serviceUuid)),
        )
    }

    /** SIG measurements arrive through GATT; only known Xiaomi formats are parsed from adverts. */
    @Test
    fun advertisementParsingIsOptInPerProtocol() {
        assertTrue(!BluetoothWeightScale.parsesAdvertisementData)
        assertTrue(XiaomiMiScaleV1.parsesAdvertisementData)
        assertTrue(XiaomiMiScaleV2.parsesAdvertisementData)
    }

    /** UUIDs embedded in Service Data are not duplicated in Android's service UUID list. */
    @Test
    fun recognisesServiceDataOnlyAdvertisements() {
        assertEquals(
            linkedSetOf(BluetoothHeartRate.serviceUuid, BluetoothWeightScale.bodyCompositionServiceUuid),
            mergedAdvertisedServiceUuids(
                listOf(BluetoothHeartRate.serviceUuid),
                listOf(BluetoothWeightScale.bodyCompositionServiceUuid),
            ),
        )
    }
}
