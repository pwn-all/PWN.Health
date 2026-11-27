package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every time in the app is on the 24-hour clock ([clockText] says why). A place that asks the phone
 * instead writes "8:00 PM" beside a reminder's "20:00", and is caught here. Should the app ever follow
 * the phone, that is one rule for every clock and the wheels, and this test is where it is turned over.
 */
class ClockStyleTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun noScreenAsksThePhoneWhichClockItKeeps() {
        // A scan that read no file finds nothing either.
        assertTrue(sources.size > 50)
        // The two calls that ask, and a date skeleton with the twelve-hour "h" in it.
        val asked = Regex("""is24HourFormat\(|getTimeFormat\(|"[yMLdE]*h+m+"""")
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { asked.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    /**
     * DateUtils prints the phone's clock when asked for a time, and the flag that would say which one
     * is deprecated in favour of asking the phone. A date with a time goes through a skeleton that
     * names the hour as "H".
     */
    @Test
    fun noPlatformFormatterIsLeftToChooseTheClock() {
        assertEquals(emptyList<String>(), sources.filter { "FORMAT_SHOW_TIME" in it.readText() }.map { it.name })
        assertEquals("08:05", clockText(8 * 60 + 5))
        assertEquals("20:00", clockText(20 * 60))
        assertEquals("dMMMHm", PAIN_MOMENT_SKELETON)
    }
}
