package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The wearables at the edges of their own wire formats: the ring's step-history batches when a
 * notification is lost, repeated or reordered, and the watch's line framing and JSON grammar at the
 * bounds each of them documents.
 */
class MachineProtocolsEdgeCasesExtraTest {

    /**
     * One quarter-hour notification lost in the air. The batch it belonged to must be abandoned
     * whole — the record that did arrive is not a day — and it must be abandoned with the partial
     * samples cleared, or the next sync's batch would complete carrying an orphan from this one.
     */
    @Test
    fun aDroppedRecordAbortsTheBatchAndLeavesNothingBehind() {
        val assembler = ColmiSportHistoryAssembler()
        assembler.accept(header(records = 3))
        assembler.accept(record(index = 0, count = 3, interval = 30, steps = 999))

        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            assembler.accept(record(index = 2, count = 3, interval = 32)),
        )

        assembler.accept(header(records = 2))
        assembler.accept(record(index = 0, count = 2, interval = 40, steps = 11))
        val completed = assembler.accept(record(index = 1, count = 2, interval = 41, steps = 22))

        assertEquals(
            "the aborted batch's record must not reappear in the next one",
            listOf(11, 22),
            (completed as ColmiSportHistoryResult.Complete).samples.map { it.steps },
        )
    }

    /**
     * A retransmission is the one out-of-order frame whose index, count, checksum and timestamp are
     * all internally consistent, and a record carrying another batch's total is the one whose
     * checksum is right for a payload nobody asked for. Only the two halves of the sequence guard
     * tell either apart; accepting a retransmission would count a quarter hour of walking twice.
     */
    @Test
    fun aRetransmissionAndAStrayBatchesRecordAreBothRefused() {
        val retransmitted = ColmiSportHistoryAssembler()
        retransmitted.accept(header(records = 2))
        retransmitted.accept(record(index = 0, count = 2, interval = 30, steps = 500))
        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            retransmitted.accept(record(index = 0, count = 2, interval = 30, steps = 500)),
        )

        val wrongTotal = ColmiSportHistoryAssembler()
        wrongTotal.accept(header(records = 2))
        assertEquals(
            "a record that expects three siblings belongs to another response",
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            wrongTotal.accept(record(index = 0, count = 3, interval = 30)),
        )
    }

    /**
     * A day holds one sample per quarter-hour bucket and they arrive in order. The same bucket
     * twice would be counted twice, and a bucket earlier than the one before it means the
     * notifications were reordered; the check that says so is written as a double negative, which
     * is exactly where equal-versus-earlier goes wrong.
     */
    @Test
    fun quarterHourBucketsThatRepeatOrRunBackwardsAreRefused() {
        val repeated = ColmiSportHistoryAssembler()
        repeated.accept(header(records = 2))
        repeated.accept(record(index = 0, count = 2, interval = 32))
        assertEquals(
            "the same quarter hour twice",
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            repeated.accept(record(index = 1, count = 2, interval = 32)),
        )

        val backwards = ColmiSportHistoryAssembler()
        backwards.accept(header(records = 2))
        backwards.accept(record(index = 0, count = 2, interval = 33))
        assertEquals(
            "a bucket earlier than the one before it",
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.OUT_OF_SEQUENCE),
            backwards.accept(record(index = 1, count = 2, interval = 32)),
        )
    }

    /**
     * The BCD reader only checks that each nibble is a digit, so 30 February passes it as readily
     * as a real date and nothing but the calendar itself stops it. A record filed under a date that
     * does not exist lands on whatever day survives, and the MAX merge of daily steps keeps it.
     */
    @Test
    fun bcdDigitsThatDoNotMakeACalendarDateAreRefused() {
        listOf(
            Triple(0x26, 0x02, 0x30), // 30 February
            Triple(0x26, 0x00, 0x15), // month zero
            Triple(0x26, 0x07, 0x00), // day zero
            Triple(0x26, 0x13, 0x01), // month thirteen
            Triple(0x25, 0x02, 0x29), // 2025 is not a leap year
            Triple(0x26, 0x02, 0x29), // and neither is 2026
        ).forEach { (year, month, day) ->
            val assembler = ColmiSportHistoryAssembler()
            assembler.accept(header(records = 1))
            assertEquals(
                "year $year month $month day $day",
                ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_RECORD),
                assembler.accept(record(0, 1, 32, year, month, day)),
            )
        }

        val leapDay = ColmiSportHistoryAssembler()
        leapDay.accept(header(records = 1))
        val completed = leapDay.accept(record(0, 1, 32, 0x24, 0x02, 0x29))
        assertEquals(
            LocalDate.of(2024, 2, 29),
            (completed as ColmiSportHistoryResult.Complete).samples.single().startedAtLocal.toLocalDate(),
        )
    }

    /**
     * 0 and 95 are the first and last buckets of the day, and the only intervals the other tests
     * use are 32 and 33 — the middle of the morning. An hour taken as the interval divided by four
     * must land 95 on 23:45 rather than rolling the last quarter hour of a walk into tomorrow.
     */
    @Test
    fun theFirstAndLastBucketsOfTheDayStayOnTheirOwnDay() {
        val last = singleSample(interval = 95)
        assertEquals(LocalDateTime.of(2026, 7, 31, 23, 45), last.startedAtLocal)
        assertEquals(95, last.intervalIndex)

        val first = singleSample(interval = 0)
        assertEquals(LocalDateTime.of(2026, 7, 31, 0, 0), first.startedAtLocal)
        assertEquals(0, first.intervalIndex)
    }

    /**
     * Every frame this app can send is sixteen bytes ending in the low eight bits of the sum of the
     * fifteen before it. Three hand-written frames are pinned elsewhere, all with sums well under 256;
     * a day offset of 255 takes the sum to 433 and is also the value one past the range's other
     * end, which the other tests refuse at the top and never ask about at the bottom.
     */
    @Test
    fun everyRequestIsSixteenBytesEndingInItsOwnEightBitSum() {
        val requests = buildList {
            add(BluetoothColmiRing.batteryRequest())
            for (offset in 0..255) add(BluetoothColmiRing.sportHistoryRequest(offset))
            for (metric in ColmiLiveMetric.entries) {
                for (action in ColmiLiveAction.entries) {
                    add(BluetoothColmiRing.liveRequest(metric, action))
                }
            }
        }

        requests.forEachIndexed { index, request ->
            assertEquals("request $index", 16, request.size)
            val sum = (0 until 15).sumOf { request[it].toInt() and 0xFF } and 0xFF
            assertEquals("checksum of request $index", sum, request[15].toInt() and 0xFF)
        }

        // 0x43 + 0xFF + 0x0F + 0x00 + 0x5F + 0x01 is 433, whose low byte is 0xB1.
        assertArrayEquals(frame(0x43, 0xFF, 0x0F, 0x00, 0x5F, 0x01), BluetoothColmiRing.sportHistoryRequest(255))
        assertEquals(0xB1, BluetoothColmiRing.sportHistoryRequest(255)[15].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { BluetoothColmiRing.sportHistoryRequest(-1) }
    }

    /**
     * The frame decoder masks the command with 0x7F, so a ring reporting an error arrives at the
     * assembler looking like a perfectly well-formed history frame. Its data bytes are an error
     * report, and reading them as a header would start a batch the ring never sent.
     */
    @Test
    fun aRingReportingAnErrorDoesNotStartABatch() {
        val assembler = ColmiSportHistoryAssembler()

        assertEquals(
            ColmiSportHistoryResult.Rejected(ColmiHistoryRejectReason.INVALID_FRAME),
            assembler.accept(frame(0xC3, 0xF0, 2, 1)),
        )
        // Still waiting for a header rather than for the two records that frame claimed.
        assertEquals(ColmiSportHistoryResult.NoData, assembler.accept(frame(0x43, 0xFF)))
    }

    /**
     * A dropped notification rejects the batch, and a retry is the ordinary next step. The ring's
     * battery was read once in a handshake that has already happened; sending the session back for
     * it would leave a sync that can never finish while one frame in each batch goes missing.
     */
    @Test
    fun aRejectedBatchDoesNotSendTheSyncBackToItsHandshake() {
        val session = session()
        session.accept(frame(0x03, 77, 0))
        session.accept(header(records = 2))

        assertEquals(
            ColmiSyncResult.Rejected("history_out_of_sequence"),
            session.accept(record(index = 1, count = 2, interval = 32)),
        )

        session.accept(header(records = 1))
        val complete = session.accept(record(index = 0, count = 1, interval = 33, steps = 321))

        val snapshot = (complete as ColmiSyncResult.Complete).snapshot
        assertEquals(321, snapshot.samples.single().steps)
        assertEquals("the battery read survives the retry", 77, snapshot.battery.percent)
    }

    /**
     * The handshake is not optional, and a snapshot that has been handed over is finished. A history
     * frame before the handshake is a stray the ring sent unprompted: set aside, not taken for the
     * battery's answer, which is still waited for. One after the snapshot is refused.
     */
    @Test
    fun packetsArrivingInTheWrongStageOfTheSyncAreRefused() {
        val beforeHandshake = session()
        assertEquals(
            "a history frame is not a battery reading",
            ColmiSyncResult.AwaitingMore,
            beforeHandshake.accept(header(records = 1)),
        )
        assertTrue(beforeHandshake.accept(frame(0x03, 77, 0)) is ColmiSyncResult.RequestHistory)

        val finished = session(ColmiRingModel.R10)
        finished.accept(frame(0x03, 50, 1))
        assertTrue(finished.accept(frame(0x43, 0xFF)) is ColmiSyncResult.Complete)
        assertEquals(ColmiSyncResult.Rejected("unexpected_packet"), finished.accept(frame(0x43, 0xFF)))
        assertEquals(
            "a finished snapshot must not reopen",
            ColmiSyncResult.Rejected("unexpected_packet"),
            finished.accept(record(index = 0, count = 1, interval = 32)),
        )
    }

    /**
     * The scanner gates on the advertised name alone, before any service has been discovered. The
     * pattern is anchored, and a regex loosened to a search would list every device whose name
     * happens to contain something ring-shaped as a ring the app can talk to.
     */
    @Test
    fun theScanTimeNameGateIsAnchoredAtBothEnds() {
        assertEquals(ColmiRingModel.R02, BluetoothColmiRing.identifyName("R02_341C"))
        assertEquals(ColmiRingModel.R06, BluetoothColmiRing.identifyName("r06_ab12"))
        assertEquals(ColmiRingModel.R10, BluetoothColmiRing.identifyName("R10_000F"))

        listOf(null, "", "R03_341C", "R02_341G", "R02_341", "R02_341CD", " R02_341C", "R02_341C ", "xR02_341C")
            .forEach { assertNull("'$it' is not a ring", BluetoothColmiRing.identifyName(it)) }
    }

    /** A ring flat on the charger reports zero per cent, which is a reading and not a missing one. */
    @Test
    fun aFlatRingOnTheChargerStillReportsItsBattery() {
        assertEquals(ColmiBatteryStatus(0, true), BluetoothColmiRing.parseBattery(frame(0x03, 0, 1)))
        assertEquals(ColmiBatteryStatus(0, false), BluetoothColmiRing.parseBattery(frame(0x03, 0, 0)))
    }

    /**
     * Elsewhere every protocol range is asserted only from outside: 19 and 251 bpm, 101 %, -1 steps,
     * and a timestamp one millisecond past each end. A range written with `until` instead of `..`
     * would refuse every maximum a real watch can send and no other test would notice.
     */
    @Test
    fun everyBangleRangeAcceptsItsOwnEndpoints() {
        assertEquals(4_102_444_800_000L, snapshot(ts = "4102444800000").timestampMillis)
        assertEquals(1_000_000, snapshot(steps = "1000000").steps)
        assertEquals(20, snapshot(hr = "20").heartRateBpm)
        assertEquals(250, snapshot(hr = "250").heartRateBpm)
        assertEquals(100, snapshot(battery = "100").batteryPercent)
    }

    /** A living wearer's heart never stops; the watch says "no reading" with null, not with zero. */
    @Test
    fun aHeartRateOfZeroIsNotAReading() {
        assertRejected(line(hr = "0"), BangleJsRejectReason.VALUE_OUT_OF_RANGE)
    }

    /**
     * The bundled watch app writes '\n' only and detaches the Espruino console, so a trailing CR
     * is leniency the decoder allows rather than a framing anything here produces. The budget it
     * is lenient about counts every buffered byte, CR included — the same rule the watch keeps,
     * where onBluetoothData bounds commandBuffer with the CR inside it and finishCommand strips
     * the CR only afterwards. So the maximum decodes under LF, and one byte less decodes under
     * CR LF; the maximum plus a CR is over the budget and is bounded, not decoded.
     */
    @Test
    fun aFullLengthFrameDecodesAndACarriageReturnCostsOneOfItsBytes() {
        val expected = BangleJsFrameResult.Snapshot(
            BangleJsHealthSnapshot(
                timestampMillis = 1_785_513_600_000L,
                steps = 7,
                heartRateBpm = null,
                batteryPercent = 8,
            ),
        )
        val maximum = BangleJsOfflineProtocol.MAX_FRAME_BYTES

        assertEquals("terminated with LF", expected, decodeSingly(paddedTo(maximum) + "\n"))
        assertEquals("terminated with CR LF", expected, decodeSingly(paddedTo(maximum - 1) + "\r\n"))
        assertEquals(
            "the CR of a CR LF spends one of the frame's own bytes",
            BangleJsFrameResult.Rejected(BangleJsRejectReason.FRAME_TOO_LONG),
            decodeSingly(paddedTo(maximum) + "\r\n"),
        )
    }

    /**
     * A number can be well outside the protocol without being outside JSON, and a number can be
     * outside JSON in a dozen small ways. Neither may reach a BLE callback as an exception, and a
     * twenty-digit step count must not come back silently truncated to whatever fits.
     */
    @Test
    fun numbersOutsideTheGrammarAreRejectedRatherThanThrown() {
        assertRejected(line(steps = "99999999999999999999"), BangleJsRejectReason.VALUE_OUT_OF_RANGE)
        listOf("+1", "-", "1.", ".5", "1e", "0x10").forEach {
            assertRejected(line(steps = it), BangleJsRejectReason.MALFORMED_JSON)
        }
        assertEquals("negative zero is zero", 0, snapshot(steps = "-0").steps)
    }

    /**
     * A frame that arrives through the decoder can never carry one of these, because the decoder
     * refuses any byte over 0x7F before the parser sees it — so the escape reader is only ever
     * exercised by a direct call, which is how it rots. A half-written surrogate has to be a
     * rejection: not a crash out of a callback, and not a broken string that compares equal to
     * nothing.
     */
    @Test
    fun unicodeEscapesDecodeAndHalfWrittenSurrogatesAreRefused() {
        assertEquals(7, snapshotOf(line(type = """heal\u0074h""")).steps)

        assertRejected(line(type = """\ud800"""), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(line(type = """\udc00"""), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(line(type = """\u00"""), BangleJsRejectReason.MALFORMED_JSON)
        assertRejected(line(type = """\ud83d\ude00"""), BangleJsRejectReason.UNSUPPORTED_MESSAGE_TYPE)
    }

    /**
     * A watch that prints a blank line between records — or a firmware that ends one with a stray
     * newline — must not stall the reader for the records that follow it.
     */
    @Test
    fun blankLinesDoNotStallTheStream() {
        val decoder = BangleJsLineDecoder()
        val stream = line(steps = "1") + "\n\n\n" + line(steps = "2") + "\n"

        val results = decoder.feed(stream.toByteArray(Charsets.US_ASCII))

        assertEquals("was $results", 4, results.size)
        assertEquals(1, (results[0] as BangleJsFrameResult.Snapshot).value.steps)
        assertEquals(BangleJsRejectReason.EMPTY_FRAME, reasonOf(results[1]))
        assertEquals(BangleJsRejectReason.EMPTY_FRAME, reasonOf(results[2]))
        assertEquals(2, (results[3] as BangleJsFrameResult.Snapshot).value.steps)
        assertNull(decoder.endOfStream())
    }

    /**
     * A byte the watch should never have sent condemns the frame it lands in. When the link then
     * drops before the next newline, the reason reported is the one that condemned it rather than
     * the truncation that followed — and it is reported once, because a cleared decoder has nothing
     * left to say about a frame it already gave up on.
     */
    @Test
    fun endOfStreamReportsWhyTheFrameWasDroppedAndSaysItOnce() {
        val decoder = BangleJsLineDecoder()
        val condemned = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 'x'.code.toByte())

        assertTrue(decoder.feed(condemned).isEmpty())
        assertEquals(BangleJsRejectReason.NON_ASCII, reasonOf(decoder.endOfStream()!!))
        assertNull(decoder.endOfStream())
    }

    /**
     * The request hardcodes its version digit while the parser reads a constant, so the two are
     * free to drift apart: a watch asked for version 1 by a parser that has moved on to 2 has every
     * reply it sends rejected as unsupported, and the literals pinned elsewhere keep on passing.
     */
    @Test
    fun theSnapshotRequestAsksForTheVersionTheParserAccepts() {
        assertEquals(
            "PWN_HEALTH ${BangleJsOfflineProtocol.VERSION}\n",
            String(BangleJsOfflineProtocol.snapshotRequest(), Charsets.US_ASCII),
        )
    }

    /**
     * Both wearables speak over Nordic UART, so their two characteristics are the same two UUIDs
     * and only the service tells them apart. It reads like a copy-paste mistake, which is why it is
     * written down: routing a notification by characteristic alone would feed a ring's step history
     * to the watch's JSON parser.
     */
    @Test
    fun theRingAndTheWatchShareCharacteristicsAndDifferOnlyByService() {
        assertEquals(BluetoothColmiRing.writeUuid, BangleJsOfflineProtocol.receiveUuid)
        assertEquals(BluetoothColmiRing.notifyUuid, BangleJsOfflineProtocol.transmitUuid)
        assertNotEquals(BluetoothColmiRing.serviceUuid, BangleJsOfflineProtocol.serviceUuid)
    }

    private val syncClock = LocalDateTime.of(2026, 7, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(model: ColmiRingModel = ColmiRingModel.R02) =
        ColmiSyncSession(model, "ring", clock = { syncClock }, zoneId = ZoneOffset.UTC)

    private fun singleSample(interval: Int): ColmiSportSample {
        val assembler = ColmiSportHistoryAssembler()
        assembler.accept(header(records = 1))
        val completed = assembler.accept(record(index = 0, count = 1, interval = interval))
        return (completed as ColmiSportHistoryResult.Complete).samples.single()
    }

    private fun header(records: Int, calorieProtocol: Int = 1): ByteArray =
        frame(0x43, 0xF0, records, calorieProtocol)

    private fun record(
        index: Int,
        count: Int,
        interval: Int,
        yearBcd: Int = 0x26,
        monthBcd: Int = 0x07,
        dayBcd: Int = 0x31,
        steps: Int = 100,
    ): ByteArray = frame(
        0x43,
        yearBcd,
        monthBcd,
        dayBcd,
        interval,
        index,
        count,
        0, // calorie counter, low
        0, // calorie counter, high
        steps and 0xFF,
        steps ushr 8,
        0, // distance, low
        0, // distance, high
    )

    private fun frame(command: Int, vararg data: Int): ByteArray = ByteArray(16).also { packet ->
        packet[0] = command.toByte()
        data.forEachIndexed { index, value -> packet[index + 1] = value.toByte() }
        packet[15] = ((0 until 15).sumOf { packet[it].toInt() and 0xFF } and 0xFF).toByte()
    }

    private fun line(
        version: String = "1",
        type: String = "health",
        ts: String = "1785513600000",
        steps: String = "7",
        hr: String = "null",
        battery: String = "8",
    ): String =
        """{"v":$version,"type":"$type","ts":$ts,"steps":$steps,"hr":$hr,"battery":$battery}"""

    /** The canonical frame padded with the whitespace JSON allows, to an exact byte length. */
    private fun paddedTo(length: Int): String {
        val body = line().dropLast(1)
        return body + " ".repeat(length - body.length - 1) + "}"
    }

    private fun decodeSingly(frame: String): BangleJsFrameResult =
        BangleJsLineDecoder().feed(frame.toByteArray(Charsets.US_ASCII)).single()

    private fun snapshot(
        ts: String = "1785513600000",
        steps: String = "7",
        hr: String = "null",
        battery: String = "8",
    ): BangleJsHealthSnapshot = snapshotOf(line(ts = ts, steps = steps, hr = hr, battery = battery))

    private fun snapshotOf(line: String): BangleJsHealthSnapshot =
        (BangleJsOfflineProtocol.parseLine(line) as BangleJsFrameResult.Snapshot).value

    private fun assertRejected(line: String, expected: BangleJsRejectReason) {
        assertEquals(line, expected, reasonOf(BangleJsOfflineProtocol.parseLine(line)))
    }

    private fun reasonOf(result: BangleJsFrameResult): BangleJsRejectReason =
        (result as BangleJsFrameResult.Rejected).reason
}
