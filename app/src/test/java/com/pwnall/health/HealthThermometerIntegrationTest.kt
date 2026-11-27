package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HealthThermometerIntegrationTest {
    @Test
    fun decodesCelsiusTimestampAndBodyLocation() {
        val expectedTime = LocalDateTime.of(2026, 7, 31, 10, 20, 30)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val result = BluetoothHealthThermometer.parse(
            bytes(
                0x06,
                0x6C, 0x01, 0x00, 0xFF, // 36.4 C: mantissa 364, exponent -1
                0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
                0x02, // body (general)
            ),
            fallbackOccurredAt = expectedTime + 60_000L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(36.4, result.temperatureCelsius!!, 0.001)
        assertEquals(TemperatureUnit.CELSIUS, result.sourceUnit)
        assertEquals(TemperatureType.BODY, result.type)
        assertEquals(expectedTime, result.occurredAt)
        assertEquals(true, result.deviceClockSet)
    }

    /**
     * HTS 3.2: an ear thermometer publishes its site once in Temperature Type and leaves the flag
     * clear in every measurement. Without it the vitals card would grade 37.7 °C against the core band.
     */
    @Test
    fun aPublishedSiteFillsAMeasurementThatNamedNone() {
        assertEquals(TemperatureType.EAR, BluetoothHealthThermometer.parseTemperatureType(bytes(0x03)))
        assertNull(BluetoothHealthThermometer.parseTemperatureType(bytes(0x00)))
        assertNull(BluetoothHealthThermometer.parseTemperatureType(bytes(0x03, 0x00)))
        assertNull(BluetoothHealthThermometer.parseTemperatureType(bytes()))

        val unsited = BluetoothHealthThermometer.parse(
            bytes(0x00, 0x6C, 0x01, 0x00, 0xFF),
            fallbackOccurredAt = 1234L,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(TemperatureType.EAR, unsited.withDefaultType(TemperatureType.EAR).type)
        assertNull(unsited.withDefaultType(null).type)

        // A measurement that names its own site keeps it; the published one is only a default.
        val sited = unsited.copy(type = TemperatureType.MOUTH)
        assertEquals(TemperatureType.MOUTH, sited.withDefaultType(TemperatureType.EAR).type)
    }

    @Test
    fun convertsFahrenheitToCelsius() {
        val result = BluetoothHealthThermometer.parse(
            bytes(0x01, 0xDA, 0x03, 0x00, 0xFF), // 98.6 F
            fallbackOccurredAt = 1234L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(37.0, result.temperatureCelsius!!, 0.001)
        assertEquals(TemperatureUnit.FAHRENHEIT, result.sourceUnit)
        assertEquals(1234L, result.occurredAt)
        assertNull(result.type)
    }

    @Test
    fun allIeeeSpecialValuesAreUnavailable() {
        listOf(0x07FE, 0x07FF, 0x0800, 0x0801, 0x0802).forEach {
            assertNull("SFLOAT 0x${it.toString(16)}", Ieee11073.decodeSfloat(it))
        }
        listOf(0x007FFFFEL, 0x007FFFFFL, 0x00800000L, 0x00800001L, 0x00800002L).forEach {
            assertNull("FLOAT 0x${it.toString(16)}", Ieee11073.decodeFloat(it))
        }

        val result = BluetoothHealthThermometer.parse(
            bytes(0x00, 0xFF, 0xFF, 0x7F, 0x00), // FLOAT NaN, little endian
            zoneId = ZoneOffset.UTC,
        )!!
        assertNull(result.temperatureCelsius)
    }

    @Test
    fun decodesSignedMantissasAndExponents() {
        assertEquals(36.4, Ieee11073.decodeFloat(0xFF00016C)!!, 0.0001)
        assertEquals(-1.2, Ieee11073.decodeSfloat(0xF_FF4)!!, 0.0001)
    }

    @Test
    fun invalidTimestampUsesArrivalTime() {
        val fallback = 55_000L
        val result = BluetoothHealthThermometer.parse(
            bytes(
                0x02,
                0x6C, 0x01, 0x00, 0xFF,
                0xEA, 0x07, 0x02, 0x1F, 0x0A, 0x14, 0x1E, // February 31
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(fallback, result.occurredAt)
        assertEquals(36.4, result.temperatureCelsius!!, 0.001)
    }

    @Test
    fun rejectsRfuUnknownTypeAndWrongLengths() {
        assertNull(
            BluetoothHealthThermometer.parse(
                bytes(0x08, 0x6C, 0x01, 0x00, 0xFF),
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertNull(
            BluetoothHealthThermometer.parse(
                bytes(0x04, 0x6C, 0x01, 0x00, 0xFF, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertNull(
            BluetoothHealthThermometer.parse(
                bytes(0x00, 0x6C, 0x01, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertNull(
            BluetoothHealthThermometer.parse(
                bytes(0x00, 0x6C, 0x01, 0x00, 0xFF, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun everyClippedRecordIsRejected() {
        val full = bytes(
            0x06,
            0x6C, 0x01, 0x00, 0xFF,
            0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
            0x02,
        )
        for (size in 0 until full.size) {
            assertNull(
                "clipped to $size bytes",
                BluetoothHealthThermometer.parse(full.copyOf(size), zoneId = ZoneOffset.UTC),
            )
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
