package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The training machine's card beside the exercise form, and what the link's reports do to it. */
class WorkoutMachineTest {
    /**
     * Disconnect, or a result taken, puts the card away at once, and the link reports the drop a
     * moment later. Applied, that report would bring the card back with nothing on it but a second
     * Disconnect to press. A drop nobody asked for still shows, and so does a new link after a hang-up.
     */
    @Test
    fun aDropReportedAfterAHangUpLeavesTheCardAway() {
        assertTrue(hangUpEcho(DirectConnectionState.IDLE, DirectConnectionState.DISCONNECTED))
        assertFalse(hangUpEcho(DirectConnectionState.CONNECTED, DirectConnectionState.DISCONNECTED))
        assertFalse(hangUpEcho(DirectConnectionState.IDLE, DirectConnectionState.CONNECTING))
        assertFalse(hangUpEcho(DirectConnectionState.IDLE, DirectConnectionState.CONNECTED))
        assertFalse(hangUpEcho(DirectConnectionState.IDLE, DirectConnectionState.ERROR))
    }
}
