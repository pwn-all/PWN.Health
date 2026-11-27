package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The redraws the widgets ask the clock for. A write redraws only the widgets it changed, so a face
 * that changes with the time alone — every "today" at midnight, the mood faces at the end of a pause
 * — would stand until the launcher's half-hourly update unless the moment itself is asked for.
 */
class WidgetClockTest {
    private val prague = ZoneId.of("Europe/Prague")

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The start of the next day where the phone is, from any hour of this one. */
    @Test
    fun midnightIsTheStartOfTheNextLocalDay() {
        assertEquals(at("2026-07-10T22:00:00Z"), nextMidnightMs(at("2026-07-10T10:00:00Z"), prague))
        // A quarter past midnight is already the new day: the redraw that comes then asks for the next.
        assertEquals(at("2026-07-11T22:00:00Z"), nextMidnightMs(at("2026-07-10T22:15:00Z"), prague))
        assertEquals(at("2026-07-11T22:00:00Z"), nextMidnightMs(at("2026-07-10T22:00:00Z"), prague))
    }

    /** A day the clocks change on is not twenty-four hours long, and its end is still its end. */
    @Test
    fun aDayTheClocksChangeOnEndsAtItsOwnMidnight() {
        // 25 October 2026: twenty-five hours in Prague.
        assertEquals(at("2026-10-25T23:00:00Z"), nextMidnightMs(at("2026-10-24T22:30:00Z"), prague))
    }

    /** One alarm a kind: the action tells two apart, and gives the kind back; none is every widget. */
    @Test
    fun theActionCarriesTheKindThereAndBack() {
        assertEquals(KIND_MOOD, widgetClockKind(widgetClockAction(KIND_MOOD)))
        assertNull(widgetClockKind(widgetClockAction(null)))
        assertTrue(widgetClockAction(null) != widgetClockAction(KIND_MOOD))
    }

    /** Asked for wherever the widgets are redrawn, and again after a reboot, which drops every alarm. */
    @Test
    fun midnightIsAskedForAtEveryRedrawAndAfterAReboot() {
        val widgets = File("src/main/java/com/pwnall/health/Widgets.kt").readText()
        val redraw = widgets.substringAfter("internal suspend fun refreshWidgets(").substringBefore("\n}\n")
        val boot = File("src/main/java/com/pwnall/health/StepService.kt").readText()
            .substringAfter("class BootReceiver : BroadcastReceiver() {").substringBefore("\n}\n")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue("WidgetClock.armMidnight(context)" in redraw)
        assertTrue("WidgetClock.armMidnight(context)" in boot)
        assertTrue("""<receiver android:name=".WidgetClockReceiver" android:exported="false" />""" in manifest)
    }
}
