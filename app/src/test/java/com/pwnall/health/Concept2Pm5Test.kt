package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Concept2Pm5Test {
    @Test
    fun parsesPublishedGeneralStatusLayout() {
        val payload = ByteArray(19).apply {
            putU24(0, 12_345) // 123.45 s
            putU24(3, 54_321) // 5432.1 m
            this[18] = 125
        }

        val result = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.generalStatusUuid,
            payload,
        )!!

        assertEquals(123.45, result.elapsedSeconds!!, 0.001)
        assertEquals(5_432.1, result.distanceMeters!!, 0.001)
        assertEquals(125, result.dragFactor)
    }

    @Test
    fun parsesAdditionalStatusAndMachineType() {
        val payload = ByteArray(17).apply {
            putU24(0, 6_000)
            putU16(3, 4_250)
            this[5] = 28
            this[6] = 151.toByte()
            this[16] = 192.toByte()
        }

        val result = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.additionalStatusOneUuid,
            payload,
        )!!

        assertEquals(4.25, result.speedMps!!, 0.001)
        assertEquals(28, result.strokeRateSpm)
        assertEquals(151, result.heartRateBpm)
        assertEquals(Concept2MachineKind.BIKEERG, result.machineKind)
    }

    @Test
    fun treatsInvalidHeartRateAsAbsent() {
        val payload = ByteArray(17).apply {
            this[6] = 0xFF.toByte()
            this[16] = 128.toByte()
        }

        val result = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.additionalStatusOneUuid,
            payload,
        )!!

        assertNull(result.heartRateBpm)
        assertEquals(Concept2MachineKind.SKIERG, result.machineKind)
    }

    @Test
    fun rejectsReservedMachineTypesAndClearsAStaleHeartRateSentinel() {
        val accumulator = Concept2Pm5Accumulator()
        accumulator.update(
            BluetoothConcept2Pm5.parse(
                BluetoothConcept2Pm5.additionalStatusOneUuid,
                ByteArray(17).apply {
                    this[6] = 80
                    this[16] = 0
                },
            )!!,
        )

        val cleared = accumulator.update(
            BluetoothConcept2Pm5.parse(
                BluetoothConcept2Pm5.additionalStatusOneUuid,
                ByteArray(17).apply {
                    this[6] = 0xFF.toByte()
                    this[16] = 100 // RFU in the published PM5 enum
                },
            )!!,
        )

        assertNull(cleared.heartRateBpm)
        assertEquals(FitnessMachineType.ROWER, cleared.type)
    }

    @Test
    fun parsesAdditionalStatusTwoAndCombinesPackets() {
        val accumulator = Concept2Pm5Accumulator()
        val general = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.generalStatusUuid,
            ByteArray(19).apply {
                putU24(0, 3_000)
                putU24(3, 1_234)
            },
        )!!
        accumulator.update(general)

        val status = BluetoothConcept2Pm5.parse(
            BluetoothConcept2Pm5.additionalStatusTwoUuid,
            ByteArray(20).apply {
                putU24(0, 3_000)
                putU16(4, 237)
                putU16(6, 42)
            },
        )!!
        val combined = accumulator.update(status)

        assertEquals(30, combined.elapsedSeconds)
        assertEquals(123, combined.totalDistanceMeters)
        assertEquals(237, combined.powerWatts)
        assertEquals(42, combined.totalEnergyKcal)
    }

    @Test
    fun rejectsUnknownCharacteristicAndEveryWrongLength() {
        assertNull(BluetoothConcept2Pm5.parse(bluetoothUuid(0x2A37), ByteArray(19)))
        for (size in 0..21) {
            if (size != 19) {
                assertNull(
                    BluetoothConcept2Pm5.parse(
                        BluetoothConcept2Pm5.generalStatusUuid,
                        ByteArray(size),
                    ),
                )
            }
            if (size != 17) {
                assertNull(
                    BluetoothConcept2Pm5.parse(
                        BluetoothConcept2Pm5.additionalStatusOneUuid,
                        ByteArray(size),
                    ),
                )
            }
            if (size != 20) {
                assertNull(
                    BluetoothConcept2Pm5.parse(
                        BluetoothConcept2Pm5.additionalStatusTwoUuid,
                        ByteArray(size),
                    ),
                )
            }
        }
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU24(offset: Int, value: Int) {
        putU16(offset, value)
        this[offset + 2] = (value ushr 16).toByte()
    }

    /**
     * The PM5 advertises the base of its UUID range; the rowing service is only found after
     * connecting. A scan that filters on the rowing service alone never lists an erg.
     */
    @Test
    fun theAdvertisedUuidIsTheBaseOfTheRange() {
        assertEquals("ce060000-43e5-11e4-916c-0800200c9a66", BluetoothConcept2Pm5.advertisedServiceUuid.toString())
        assertEquals("ce060030-43e5-11e4-916c-0800200c9a66", BluetoothConcept2Pm5.serviceUuid.toString())
    }
}
