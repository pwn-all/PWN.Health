package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * The devices page says what went wrong in words the person can act on, never the token or the
 * exception it was handed. These pin which words each failure gets.
 */
class DeviceStatusReasonTest {
    @Test
    fun aRadioThatIsOffIsTheLineThatSaysSo() {
        assertEquals(R.string.direct_bluetooth_disabled, directBluetoothErrorRes("bluetooth_off"))
    }

    /** What the manager says when a search or a link was asked for without the permission. */
    @Test
    fun aDoorTriedWithoutTheNearbyDevicesPermissionAsksForItRatherThanFailingGenerically() {
        assertEquals(R.string.device_picker_permission, directBluetoothErrorRes("permission"))
    }

    @Test
    fun everyPairingFailureIsOnePairingReasonEvenWhenItTimedOut() {
        listOf("bond", "bond_timeout", "glucose_bond", "glucose_bond_timeout").forEach {
            assertEquals(it, R.string.m3_sync_error_pairing, directBluetoothErrorRes(it))
        }
    }

    @Test
    fun aDeviceThatStoppedAnsweringIsToldApartFromOneThatRefused() {
        listOf(
            "connection_timeout",
            "glucose_racp_timeout",
            "colmi_history_timeout",
            "colmi_write",
            "colmi_response",
            "bangle_response_timeout",
            "link:8",
            "connect:133",
        ).forEach {
            assertEquals(it, R.string.m3_sync_error_no_answer, directBluetoothErrorRes(it))
        }
    }

    @Test
    fun aSearchThatCouldNotStartIsNotAConnectionFailure() {
        assertEquals(R.string.m3_sync_error_scan, directBluetoothErrorRes("scan:2"))
    }

    @Test
    fun anythingElseGetsTheGenericLineRatherThanItsToken() {
        listOf("characteristic", "colmi_identity", "bangle_malformed_json", "subscribe:5", "glucose_racp:4", "", null)
            .forEach { assertEquals(it.toString(), R.string.m3_sync_error_generic, directBluetoothErrorRes(it)) }
    }

    @Test
    fun healthConnectFailuresAreSortedIntoWhatThePersonCanDoAboutThem() {
        assertEquals(SyncFailureKind.PERMISSION, syncFailureKind(SecurityException("denied")))
        assertEquals(SyncFailureKind.UNAVAILABLE, syncFailureKind(IOException("binder died")))
        assertEquals(SyncFailureKind.UNAVAILABLE, syncFailureKind(IllegalStateException("service not bound")))
        assertEquals(
            SyncFailureKind.RATE_LIMITED,
            syncFailureKind(IOException("Rate limited request quota has been exceeded.")),
        )
        assertEquals(SyncFailureKind.OTHER, syncFailureKind(IllegalArgumentException("bad range")))
    }

    /**
     * Health Connect refuses a write too large to take through its rate limiter, in the same exception
     * types as a service that did not answer. Waiting does not shrink the write, so it is no quota, and
     * the card must not say Health Connect has not responded when it has.
     */
    @Test
    fun aWriteTooLargeToTakeIsNeitherTheQuotaNorAHealthConnectThatDidNotAnswer() {
        assertEquals(
            SyncFailureKind.OTHER,
            syncFailureKind(IllegalStateException("Records chunk size exceeded the max chunk limit: 5000000")),
        )
        assertEquals(
            SyncFailureKind.OTHER,
            syncFailureKind(IOException("Record size exceeded the single record size limit: 1000000")),
        )
        assertFalse(spentQuota(IllegalStateException("Records chunk size exceeded the max chunk limit")))
    }

    @Test
    fun aFailureStoredBeforeKindsExistedStillReadsAsASentence() {
        assertEquals(R.string.m3_sync_failed_other, null.reasonRes())
        assertEquals(R.string.m3_sync_failed_permission, SyncFailureKind.PERMISSION.reasonRes())
    }
}
