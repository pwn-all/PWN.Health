package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ColmiSyncSessionTest {
    /** A phone clock on the day the fixture's records carry, so the sync believes them. */
    private val syncClock = LocalDateTime.of(2026, 7, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /**
     * A never-synced ring says 2000-01-01, and this app does not set its time. A day that far from
     * the phone's is the ring's clock, not a day, and a sync made of nothing else says so instead of
     * saving an empty one; a stray record among real ones is simply left out.
     */
    @Test
    fun daysTheRingDatesAQuarterCenturyBackAreNotDays() {
        val unset = ColmiSyncSession(ColmiRingModel.R02, "source", clock = { syncClock }, zoneId = ZoneOffset.UTC)
        unset.accept(frame(0x03, 84, 0))
        assertEquals(ColmiSyncResult.AwaitingMore, unset.accept(frame(0x43, 0xF0, 1, 1)))
        assertEquals(
            ColmiSyncResult.Rejected("history_clock_unset"),
            unset.accept(frame(0x43, 0x00, 0x01, 0x01, 32, 0, 1, 17, 0, 65, 1, 211, 0)),
        )

        val mixed = ColmiSyncSession(ColmiRingModel.R02, "source", clock = { syncClock }, zoneId = ZoneOffset.UTC)
        mixed.accept(frame(0x03, 84, 0))
        mixed.accept(frame(0x43, 0xF0, 2, 1))
        mixed.accept(frame(0x43, 0x00, 0x01, 0x01, 32, 0, 2, 17, 0, 65, 1, 211, 0))
        val complete = mixed.accept(
            frame(0x43, 0x26, 0x07, 0x31, 33, 1, 2, 23, 0, 200, 1, 46, 1),
        ) as ColmiSyncResult.Complete
        assertEquals(listOf(456), complete.snapshot.samples.map { it.steps })
    }

    @Test
    fun requiresBatteryHandshakeBeforeCompletingTodayHistory() {
        val session = ColmiSyncSession(ColmiRingModel.R02, "source", clock = { syncClock }, zoneId = ZoneOffset.UTC)
        assertEquals(0x03, session.firstRequest()[0].toInt() and 0xFF)

        val next = session.accept(frame(0x03, 84, 0)) as ColmiSyncResult.RequestHistory
        assertEquals(84, next.battery.percent)
        assertEquals(0x43, next.payload[0].toInt() and 0xFF)

        assertEquals(ColmiSyncResult.AwaitingMore, session.accept(frame(0x43, 0xF0, 1, 1)))
        val complete = session.accept(
            frame(
                0x43,
                0x26,
                0x07,
                0x31,
                32,
                0,
                1,
                17,
                0,
                65,
                1,
                211,
                0,
            ),
        ) as ColmiSyncResult.Complete

        assertEquals(syncClock, complete.snapshot.syncedAt)
        assertEquals(321, complete.snapshot.samples.single().steps)
        assertEquals(84, complete.snapshot.battery.percent)
    }

    @Test
    fun noDataIsAValidCompletedSyncButMalformedHandshakeIsNot() {
        val complete = ColmiSyncSession(ColmiRingModel.R10, "source")
        complete.accept(frame(0x03, 50, 1))
        val result = complete.accept(frame(0x43, 0xFF)) as ColmiSyncResult.Complete
        assertTrue(result.snapshot.samples.isEmpty())

        val rejected = ColmiSyncSession(ColmiRingModel.R06, "source")
            .accept(frame(0x03, 150, 0))
        assertEquals(ColmiSyncResult.Rejected("battery_packet"), rejected)
    }

    /**
     * A ring speaks unprompted as well. A well-formed frame of another command in the middle of the
     * exchange is not its answer, and it does not fail the sync; a broken frame does.
     */
    @Test
    fun aFrameTheRingSendsUnpromptedIsSetAsideMidSync() {
        val session = ColmiSyncSession(ColmiRingModel.R02, "source", clock = { syncClock }, zoneId = ZoneOffset.UTC)
        assertEquals(ColmiSyncResult.AwaitingMore, session.accept(frame(0x73, 0x12)))
        assertTrue(session.accept(frame(0x03, 84, 0)) is ColmiSyncResult.RequestHistory)
        assertEquals(ColmiSyncResult.AwaitingMore, session.accept(frame(0x43, 0xF0, 1, 1)))
        assertEquals(ColmiSyncResult.AwaitingMore, session.accept(frame(0x73, 0x12)))
        assertEquals(ColmiSyncResult.AwaitingMore, session.accept(frame(0x03, 84, 0)))
        val complete = session.accept(
            frame(0x43, 0x26, 0x07, 0x31, 32, 0, 1, 17, 0, 65, 1, 211, 0),
        ) as ColmiSyncResult.Complete
        assertEquals(321, complete.snapshot.samples.single().steps)

        val broken = frame(0x73, 0x12).also { it[15] = (it[15] + 1).toByte() }
        assertEquals(
            ColmiSyncResult.Rejected("battery_packet"),
            ColmiSyncSession(ColmiRingModel.R02, "source").accept(broken),
        )
    }

    @Test
    fun sourceKeyIsStableAndDoesNotContainTheBluetoothAddress() {
        val first = colmiSourceId("AA:BB:CC:DD:EE:FF", ColmiRingModel.R02)
        val repeated = colmiSourceId("AA:BB:CC:DD:EE:FF", ColmiRingModel.R02)
        val anotherModel = colmiSourceId("AA:BB:CC:DD:EE:FF", ColmiRingModel.R10)

        assertEquals(first, repeated)
        assertNotEquals(first, anotherModel)
        assertTrue(!first.contains("AA:BB"))
    }

    /**
     * The digest is a primary key of direct_ble_step_days: a day already synced is only found again
     * while this exact string comes back out for the same ring — on this install, after an upgrade,
     * and on the phone a backup was restored to. Nothing install-specific may enter it, which is
     * why there is no salt here and why this is the literal the database already holds.
     */
    @Test
    fun theRingKeyIsTheOneAlreadyInTheDatabase() {
        assertEquals(
            "COLMI_R02_5d04e9dd729e5b70",
            colmiSourceId("AA:BB:CC:DD:EE:FF", ColmiRingModel.R02),
        )
    }

    /**
     * A meter, a scale or a monitor says nothing about itself that another of its make does not
     * also say, so this is the only thing a saved reading can be traced by.
     */
    @Test
    fun aDeviceKeyIsStablePerInstallAndCarriesNoAddress() {
        val salt = "e1f2a3b4"
        val first = directSourceId("AA:BB:CC:DD:EE:FF", salt)

        assertEquals(first, directSourceId("AA:BB:CC:DD:EE:FF", salt))
        assertNotEquals(first, directSourceId("AA:BB:CC:DD:EE:F0", salt))
        assertNotEquals(first, directSourceId("AA:BB:CC:DD:EE:FF", "9a8b7c6d"))
        assertTrue(!first.contains("AA:BB"))
    }

    private fun frame(command: Int, vararg data: Int): ByteArray = ByteArray(16).also { packet ->
        packet[0] = command.toByte()
        data.forEachIndexed { index, value -> packet[index + 1] = value.toByte() }
        packet[15] = (0 until 15).sumOf { packet[it].toInt() and 0xFF }.toByte()
    }
}
