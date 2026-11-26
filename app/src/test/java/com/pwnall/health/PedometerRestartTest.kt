package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing listens to the step counter after the phone restarts, nor after an update replaces the
 * app's package and ends its service with it. BootReceiver starts the service again after both. It
 * acts only on the broadcasts it names and hears only the ones the manifest's filter lists, so the two
 * lists have to be one.
 */
class PedometerRestartTest {
    private val filter: List<String> = File("src/main/AndroidManifest.xml").readText()
        .substringAfter("android:name=\".BootReceiver\"", "")
        .substringBefore("</receiver>")
        .let { receiver -> ACTION.findAll(receiver).map { it.groupValues[1] }.toList() }

    @Test
    fun theReceiverActsOnARebootAndOnAnUpdateOfTheApp() {
        assertTrue(restartsPedometer("android.intent.action.BOOT_COMPLETED"))
        assertTrue(restartsPedometer("android.intent.action.MY_PACKAGE_REPLACED"))
        // Another app's update, and a broadcast that names nothing.
        assertFalse(restartsPedometer("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(restartsPedometer(null))
    }

    /**
     * The filter names the update beside the reboot: with the reboot alone, after an update the
     * pedometer would stand still until the app was next opened, and the steps of all that time would
     * arrive as one reading.
     */
    @Test
    fun theManifestDeliversExactlyWhatTheReceiverActsOn() {
        assertEquals(
            listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            filter,
        )
        assertEquals(emptyList<String>(), filter.filterNot(::restartsPedometer))
    }

    private companion object {
        val ACTION = Regex("""<action android:name="([^"]+)"""")
    }
}
