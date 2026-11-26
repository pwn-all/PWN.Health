package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BangleJsOfflineProtocolTest {
    @Test
    fun parsesCanonicalSnapshot() {
        val result = parseSnapshot(
            """{"v":1,"type":"health","ts":1785513600000,"steps":12345,"hr":72,"battery":87}""",
        )

        assertEquals(1_785_513_600_000L, result.timestampMillis)
        assertEquals(12_345, result.steps)
        assertEquals(72, result.heartRateBpm)
        assertEquals(87, result.batteryPercent)
    }

    @Test
    fun acceptsWhitespaceFieldOrderAndUnavailableHeartRate() {
        val result = parseSnapshot(
            """ { "battery": 0, "hr": null, "steps": 0, "ts": 946684800000, "type": "health", "v": 1 } """,
        )

        assertNull(result.heartRateBpm)
        assertEquals(0, result.steps)
        assertEquals(0, result.batteryPercent)
    }

    @Test
    fun incrementallyFramesSplitAndCoalescedNotifications() {
        val decoder = BangleJsLineDecoder()
        val first =
            """{"v":1,"type":"health","ts":1785513600000,"steps":1,"hr":null,"battery":2}""" + "\r"
        val second = """{"v":1,"type":"health","ts":1785513601000,"steps":3,"hr":80,"battery":4}"""

        assertTrue(decoder.feed(first.take(31).toByteArray()).isEmpty())
        val results = decoder.feed(
            (first.drop(31) + "\n" + second + "\n").toByteArray(),
        )

        assertEquals(2, results.size)
        assertEquals(1, (results[0] as BangleJsFrameResult.Snapshot).value.steps)
        assertEquals(3, (results[1] as BangleJsFrameResult.Snapshot).value.steps)
        assertNull(decoder.endOfStream())
    }

    @Test
    fun rejectsSchemaChangesAndDuplicates() {
        assertRejected(
            """{"v":2,"type":"health","ts":1785513600000,"steps":1,"hr":null,"battery":2}""",
            BangleJsRejectReason.UNSUPPORTED_VERSION,
        )
        assertRejected(
            """{"v":1,"type":"activity","ts":1785513600000,"steps":1,"hr":null,"battery":2}""",
            BangleJsRejectReason.UNSUPPORTED_MESSAGE_TYPE,
        )
        assertRejected(
            """{"v":1,"type":"health","ts":1785513600000,"steps":1,"hr":null}""",
            BangleJsRejectReason.MISSING_FIELD,
        )
        assertRejected(
            """{"v":1,"type":"health","ts":1785513600000,"steps":1,"hr":null,"battery":2,"x":0}""",
            BangleJsRejectReason.UNKNOWN_FIELD,
        )
        assertRejected(
            """{"v":1,"v":1,"type":"health","ts":1785513600000,"steps":1,"hr":null,"battery":2}""",
            BangleJsRejectReason.DUPLICATE_FIELD,
        )
    }

    @Test
    fun rejectsNonJsonNonFiniteAndNonIntegerNumbers() {
        val template = """{"v":1,"type":"health","ts":1785513600000,"steps":VALUE,"hr":null,"battery":2}"""
        assertRejected(template.replace("VALUE", "NaN"), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(template.replace("VALUE", "Infinity"), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(template.replace("VALUE", "1.0"), BangleJsRejectReason.INVALID_FIELD_TYPE)
        assertRejected(template.replace("VALUE", "1e2"), BangleJsRejectReason.INVALID_FIELD_TYPE)
        assertRejected(template.replace("VALUE", "01"), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(template.replace("VALUE", "true"), BangleJsRejectReason.INVALID_FIELD_TYPE)
    }

    @Test
    fun rejectsEveryFieldOutsideItsProtocolRange() {
        assertRangeRejected(ts = 946_684_799_999L)
        assertRangeRejected(ts = 4_102_444_800_001L)
        assertRangeRejected(steps = -1)
        assertRangeRejected(steps = 1_000_001)
        assertRangeRejected(hr = "19")
        assertRangeRejected(hr = "251")
        assertRangeRejected(battery = -1)
        assertRangeRejected(battery = 101)
    }

    @Test
    fun rejectsMalformedJsonAndRawUnicode() {
        assertRejected("{}", BangleJsRejectReason.MISSING_FIELD)
        assertRejected("{", BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(
            """{"v":1,"type":"health","ts":1785513600000,"steps":1,"hr":null,"battery":2,}""",
            BangleJsRejectReason.MALFORMED_JSON,
        )
        assertRejected(
            """{"v":1,"type":"heälth","ts":1785513600000,"steps":1,"hr":null,"battery":2}""",
            BangleJsRejectReason.NON_ASCII,
        )
    }

    @Test
    fun boundsFramingAndRecoversAtNextNewline() {
        val decoder = BangleJsLineDecoder()
        val tooLong = "x".repeat(BangleJsOfflineProtocol.MAX_FRAME_BYTES + 1)
        val valid =
            """{"v":1,"type":"health","ts":1785513600000,"steps":7,"hr":null,"battery":8}""" + "\n"

        val results = decoder.feed((tooLong + "\n" + valid).toByteArray())

        assertEquals(BangleJsRejectReason.FRAME_TOO_LONG, rejectedReason(results[0]))
        assertEquals(7, (results[1] as BangleJsFrameResult.Snapshot).value.steps)

        assertTrue(decoder.feed(byteArrayOf('{'.code.toByte(), 0xC3.toByte())).isEmpty())
        assertEquals(
            BangleJsRejectReason.NON_ASCII,
            rejectedReason(decoder.feed("\n".toByteArray()).single()),
        )
        assertTrue(decoder.feed("partial".toByteArray()).isEmpty())
        assertEquals(BangleJsRejectReason.TRUNCATED_FRAME, rejectedReason(decoder.endOfStream()!!))
    }

    @Test
    fun rejectsOversizedInputChunkWithoutAllocatingFrameResults() {
        val decoder = BangleJsLineDecoder()
        val result = decoder.feed(ByteArray(BangleJsOfflineProtocol.MAX_INPUT_CHUNK_BYTES + 1)).single()

        assertEquals(BangleJsRejectReason.INPUT_CHUNK_TOO_LONG, rejectedReason(result))
        assertNull(decoder.endOfStream())
    }

    @Test
    fun exposesOnlyTheFixedVersionedRequestAndOfficialNusUuids() {
        assertArrayEquals("PWN_HEALTH 1\n".toByteArray(), BangleJsOfflineProtocol.snapshotRequest())
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BangleJsOfflineProtocol.serviceUuid.toString(),
        )
        assertEquals(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            BangleJsOfflineProtocol.receiveUuid.toString(),
        )
        assertEquals(
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            BangleJsOfflineProtocol.transmitUuid.toString(),
        )
    }

    private fun assertRangeRejected(
        ts: Long = 1_785_513_600_000L,
        steps: Int = 1,
        hr: String = "null",
        battery: Int = 2,
    ) {
        assertRejected(
            """{"v":1,"type":"health","ts":$ts,"steps":$steps,"hr":$hr,"battery":$battery}""",
            BangleJsRejectReason.VALUE_OUT_OF_RANGE,
        )
    }

    private fun parseSnapshot(line: String): BangleJsHealthSnapshot =
        (BangleJsOfflineProtocol.parseLine(line) as BangleJsFrameResult.Snapshot).value

    private fun assertRejected(line: String, expected: BangleJsRejectReason) {
        assertEquals(expected, rejectedReason(BangleJsOfflineProtocol.parseLine(line)))
    }

    private fun rejectedReason(result: BangleJsFrameResult): BangleJsRejectReason =
        (result as BangleJsFrameResult.Rejected).reason
}
