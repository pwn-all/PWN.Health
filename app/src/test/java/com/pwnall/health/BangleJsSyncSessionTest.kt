package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BangleJsSyncSessionTest {
    private val prague = ZoneId.of("Europe/Prague")

    /** 22:30 UTC on 31 July, which is already 1 August in Prague. */
    private val lateEvening = LocalDateTime.of(2026, 7, 31, 22, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(now: Long = lateEvening) = BangleJsSyncSession("BLE_watch", clock = { now }, zoneId = prague)

    private fun line(ts: Long = lateEvening, hr: String = "72") =
        """{"v":1,"type":"health","ts":$ts,"steps":12345,"hr":$hr,"battery":87}""" + "\n"

    @Test
    fun theRequestIsTheOneFixedAsciiLine() {
        assertArrayEquals("PWN_HEALTH 1\n".toByteArray(Charsets.US_ASCII), session().request())
    }

    /** The answer arrives in as many notifications as the link's MTU makes of it. */
    @Test
    fun anAnswerSplitAcrossNotificationsIsFiledUnderItsLocalDay() {
        val session = session()
        val bytes = line().toByteArray(Charsets.US_ASCII)

        assertEquals(BangleJsSyncResult.AwaitingMore, session.accept(bytes.copyOfRange(0, 20)))
        assertEquals(BangleJsSyncResult.AwaitingMore, session.accept(bytes.copyOfRange(20, 40)))
        val sync = (session.accept(bytes.copyOfRange(40, bytes.size)) as BangleJsSyncResult.Complete).sync

        assertEquals("BLE_watch", sync.sourceId)
        assertEquals(LocalDate.of(2026, 8, 1), sync.day)
        assertEquals(12_345, sync.health.steps)
        assertEquals(72, sync.health.heartRateBpm)
        assertEquals(87, sync.health.batteryPercent)
        assertEquals(lateEvening, sync.health.timestampMillis)
    }

    @Test
    fun aHeartRateTheWatchWasNotSureOfArrivesAsNone() {
        val sync = (session().accept(line(hr = "null").toByteArray()) as BangleJsSyncResult.Complete).sync
        assertNull(sync.health.heartRateBpm)
    }

    /** Other watch apps write to the same UART; their lines are not the answer and do not fail it. */
    @Test
    fun aBlankLineAndAnotherAppsObjectAreSetAside() {
        val session = session()
        assertEquals(
            BangleJsSyncResult.AwaitingMore,
            session.accept("\n{\"t\":\"status\",\"bat\":87,\"chg\":0}\n".toByteArray()),
        )
        assertTrue(session.accept(line().toByteArray()) is BangleJsSyncResult.Complete)
    }

    /**
     * A watch without the app running is the Espruino console, which echoes the request back. That,
     * and an answer of the wrong version, fail at once rather than waiting for the watchdog.
     */
    @Test
    fun aBrokenOrForeignAnswerFailsTheSync() {
        assertEquals(
            BangleJsSyncResult.Rejected("bangle_malformed_json"),
            session().accept(">PWN_HEALTH 1\r\n".toByteArray()),
        )
        assertEquals(
            BangleJsSyncResult.Rejected("bangle_unsupported_version"),
            session().accept(line().replace("\"v\":1", "\"v\":2").toByteArray()),
        )
        assertEquals(
            BangleJsSyncResult.Rejected("bangle_input_chunk_too_long"),
            session().accept(ByteArray(BangleJsOfflineProtocol.MAX_INPUT_CHUNK_BYTES + 1)),
        )
    }

    /** A watch whose clock is years off would file its count under a day nobody walked. */
    @Test
    fun aDayMoreThanOneAwayFromThePhonesIsTheWatchClock() {
        val unset = LocalDateTime.of(2000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            BangleJsSyncResult.Rejected("bangle_clock_unset"),
            session().accept(line(ts = unset).toByteArray()),
        )

        val dayBehind = lateEvening - 24L * 60 * 60 * 1_000
        val sync = (session().accept(line(ts = dayBehind).toByteArray()) as BangleJsSyncResult.Complete).sync
        assertEquals(LocalDate.of(2026, 7, 31), sync.day)
    }

    @Test
    fun theWatchIsRecognisedByTheExactNameEspruinoAdvertises() {
        assertTrue(BangleJsOfflineProtocol.isAdvertisedName("Bangle.js 7a3c"))
        assertTrue(BangleJsOfflineProtocol.isAdvertisedName("Bangle.js 7A3C"))
        listOf(null, "", "Bangle.js", "Bangle.js 7a3", "Bangle.js 7a3c2", "Bangle.js 7a3g", "Pixl.js 7a3c")
            .forEach { assertFalse("$it", BangleJsOfflineProtocol.isAdvertisedName(it)) }

        assertEquals(
            DirectDeviceKind.BANGLE_JS2,
            directDeviceKind(emptySet(), scale = null, colmiModel = null, bangleJs = true),
        )
        assertEquals(
            DirectDeviceKind.BANGLE_JS2,
            directDeviceKind(setOf(BluetoothHeartRate.serviceUuid), scale = null, colmiModel = null, bangleJs = true),
        )
    }
}
