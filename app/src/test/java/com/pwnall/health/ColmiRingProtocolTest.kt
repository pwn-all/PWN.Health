package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ColmiRingProtocolTest {
    /** Yesterday, today and tomorrow are the ring's day seen from either side of midnight or a zone. */
    @Test
    fun onlyDaysWithinOneOfThePhonesAreBelieved() {
        val today = LocalDate.of(2026, 9, 4)
        fun sample(date: LocalDate) = ColmiSportSample(
            startedAtLocal = date.atTime(8, 0),
            intervalIndex = 32,
            steps = 1,
            distanceMeters = 1,
            calorieCounterRaw = 1,
            newCalorieProtocol = true,
        )
        val samples = listOf(
            sample(LocalDate.of(2000, 1, 1)),
            sample(today.minusDays(2)),
            sample(today.minusDays(1)),
            sample(today),
            sample(today.plusDays(1)),
            sample(today.plusDays(2)),
        )

        assertEquals(
            listOf(today.minusDays(1), today, today.plusDays(1)),
            samples.withinADayOf(today).map { it.startedAtLocal.toLocalDate() },
        )
    }

    @Test
    fun identifiesOnlyTheDocumentedModelNamesWithTheExpectedService() {
        val service = setOf(BluetoothColmiRing.serviceUuid)

        assertEquals(ColmiRingModel.R02, BluetoothColmiRing.identifyModel("R02_341C", service))
        assertEquals(ColmiRingModel.R06, BluetoothColmiRing.identifyModel("r06_ab12", service))
        assertEquals(ColmiRingModel.R10, BluetoothColmiRing.identifyModel("R10_000F", service))
        assertNull(BluetoothColmiRing.identifyModel("R03_341C", service))
        assertNull(BluetoothColmiRing.identifyModel("QRing", service))
        assertNull(BluetoothColmiRing.identifyModel("R02_341C", setOf(UUID.randomUUID())))
    }

    @Test
    fun buildsBoundedReadOnlyRequestsWithEightBitChecksums() {
        assertArrayEquals(frame(0x03), BluetoothColmiRing.batteryRequest())
        assertArrayEquals(
            frame(0x43, 2, 0x0F, 0x00, 0x5F, 0x01),
            BluetoothColmiRing.sportHistoryRequest(dayOffset = 2),
        )
        assertArrayEquals(
            frame(0x69, 1, 1),
            BluetoothColmiRing.liveRequest(ColmiLiveMetric.HEART_RATE, ColmiLiveAction.START),
        )
        assertArrayEquals(
            frame(0x69, 3, 3),
            BluetoothColmiRing.liveRequest(
                ColmiLiveMetric.BLOOD_OXYGEN,
                ColmiLiveAction.CONTINUE,
            ),
        )
        assertArrayEquals(
            frame(0x6A, 3, 0, 0),
            BluetoothColmiRing.liveRequest(ColmiLiveMetric.BLOOD_OXYGEN, ColmiLiveAction.STOP),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesHistoryOffsetsThatDoNotFitTheWireField() {
        BluetoothColmiRing.sportHistoryRequest(dayOffset = 256)
    }

    @Test
    fun parsesBatteryAndRejectsCorruptionOrImpossibleValues() {
        assertEquals(
            ColmiBatteryStatus(percent = 64, charging = false),
            BluetoothColmiRing.parseBattery(frame(0x03, 64, 0)),
        )
        assertEquals(
            ColmiBatteryStatus(percent = 100, charging = true),
            BluetoothColmiRing.parseBattery(frame(0x03, 100, 1)),
        )
        assertNull(BluetoothColmiRing.parseBattery(frame(0x03, 101, 0)))
        assertNull(BluetoothColmiRing.parseBattery(frame(0x03, 50, 2)))
        assertNull(BluetoothColmiRing.parseBattery(frame(0x83, 50, 0)))
        assertNull(BluetoothColmiRing.parseBattery(frame(0x04, 50, 0)))
        assertNull(BluetoothColmiRing.parseBattery(ByteArray(15)))

        val corrupt = frame(0x03, 80, 0).apply { this[2] = 1 }
        assertNull(BluetoothColmiRing.parseBattery(corrupt))
    }

    @Test
    fun parsesOnlyTheTwoSensorReadingsWeCanHonestlyExpose() {
        assertEquals(
            ColmiLiveResponse.Reading(ColmiLiveMetric.HEART_RATE, 78),
            BluetoothColmiRing.parseLive(frame(0x69, 1, 0, 78)),
        )
        assertEquals(
            ColmiLiveResponse.Reading(ColmiLiveMetric.BLOOD_OXYGEN, 97),
            BluetoothColmiRing.parseLive(frame(0x69, 3, 0, 97)),
        )
        assertEquals(
            ColmiLiveResponse.DeviceError(ColmiLiveMetric.HEART_RATE, 4),
            BluetoothColmiRing.parseLive(frame(0x69, 1, 4, 0)),
        )

        // Vendor-defined BP/glucose/stress estimates are deliberately not part of this API.
        assertNull(BluetoothColmiRing.parseLive(frame(0x69, 2, 0, 120)))
        assertNull(BluetoothColmiRing.parseLive(frame(0xE9, 1, 0, 78)))
    }

    @Test
    fun assemblesValidatedQuarterHourStepHistory() {
        val assembler = ColmiSportHistoryAssembler()
        assertEquals(
            ColmiSportHistoryResult.AwaitingMore,
            assembler.accept(frame(0x43, 0xF0, 2, 1)),
        )
        assertEquals(
            ColmiSportHistoryResult.AwaitingMore,
            assembler.accept(
                sportRecord(
                    yearBcd = 0x26,
                    monthBcd = 0x07,
                    dayBcd = 0x31,
                    interval = 32,
                    index = 0,
                    count = 2,
                    calories = 17,
                    steps = 321,
                    distance = 211,
                ),
            ),
        )

        val completed = assembler.accept(
            sportRecord(
                yearBcd = 0x26,
                monthBcd = 0x07,
                dayBcd = 0x31,
                interval = 33,
                index = 1,
                count = 2,
                calories = 23,
                steps = 456,
                distance = 302,
            ),
        ) as ColmiSportHistoryResult.Complete

        assertEquals(2, completed.samples.size)
        assertEquals(LocalDateTime.of(2026, 7, 31, 8, 0), completed.samples[0].startedAtLocal)
        assertEquals(321, completed.samples[0].steps)
        assertEquals(211, completed.samples[0].distanceMeters)
        assertEquals(17, completed.samples[0].calorieCounterRaw)
        assertTrue(completed.samples[0].newCalorieProtocol)
        assertEquals(LocalDateTime.of(2026, 7, 31, 8, 15), completed.samples[1].startedAtLocal)
    }

    @Test
    fun handlesNoDataAndResetsAfterARejectedSequence() {
        val assembler = ColmiSportHistoryAssembler()
        assertEquals(ColmiSportHistoryResult.NoData, assembler.accept(frame(0x43, 0xFF)))

        assertEquals(
            ColmiSportHistoryResult.AwaitingMore,
            assembler.accept(frame(0x43, 0xF0, 2, 0)),
        )
        val outOfOrder = assembler.accept(
            sportRecord(0x26, 0x07, 0x31, 10, index = 1, count = 2, calories = 0, steps = 1, distance = 1),
        )
        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            outOfOrder,
        )

        // A rejection clears the partial batch, so a fresh no-data response is accepted.
        assertEquals(ColmiSportHistoryResult.NoData, assembler.accept(frame(0x43, 0xFF)))
    }

    @Test
    fun rejectsBadBcdBadIntervalsAndBadChecksums() {
        val assembler = ColmiSportHistoryAssembler()
        assembler.accept(frame(0x43, 0xF0, 1, 0))
        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_RECORD),
            assembler.accept(
                sportRecord(0x2A, 0x07, 0x31, 0, 0, 1, 0, 1, 1),
            ),
        )

        assembler.accept(frame(0x43, 0xF0, 1, 0))
        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_RECORD),
            assembler.accept(
                sportRecord(0x26, 0x07, 0x31, 96, 0, 1, 0, 1, 1),
            ),
        )

        assembler.accept(frame(0x43, 0xF0, 1, 0))
        val corrupt = sportRecord(0x26, 0x07, 0x31, 0, 0, 1, 0, 1, 1)
            .apply { this[15] = (this[15].toInt() + 1).toByte() }
        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_FRAME),
            assembler.accept(corrupt),
        )
    }

    @Test
    fun everyClippedSportFrameIsRejected() {
        val valid = frame(0x43, 0xF0, 1, 0)
        for (size in valid.indices) {
            val assembler = ColmiSportHistoryAssembler()
            assertEquals(
                "clipped to $size bytes",
                ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_FRAME),
                assembler.accept(valid.copyOf(size)),
            )
        }
    }

    private fun sportRecord(
        yearBcd: Int,
        monthBcd: Int,
        dayBcd: Int,
        interval: Int,
        index: Int,
        count: Int,
        calories: Int,
        steps: Int,
        distance: Int,
    ): ByteArray = frame(
        0x43,
        yearBcd,
        monthBcd,
        dayBcd,
        interval,
        index,
        count,
        calories and 0xFF,
        calories ushr 8,
        steps and 0xFF,
        steps ushr 8,
        distance and 0xFF,
        distance ushr 8,
    )

    private fun frame(command: Int, vararg data: Int): ByteArray = ByteArray(16).also { result ->
        result[0] = command.toByte()
        data.forEachIndexed { index, value -> result[index + 1] = value.toByte() }
        result[15] = (result.take(15).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
    }
}
