package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * A date written with a fixed "d MMM" puts the day first in all sixteen languages: "17 9月" where a
 * Japanese reader expects "9月17日", "17 Sep" where an American one expects "Sep 17".
 * [localizedDatePattern] names the fields and lets the platform order them, and every screen uses it,
 * so one day is never written two ways on two screens. Run on Robolectric because the order comes from the platform's
 * own ICU, which a plain JVM test does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class LocalizedDatePatternTest {
    private val day = LocalDate.of(2026, 9, 17)

    @Test
    fun theLanguageOrdersItsOwnDate() {
        assertEquals("9月17日", localizedDatePattern(Locale.JAPAN, "dMMM", "d MMM").format(day))
        assertEquals("Sep 17", localizedDatePattern(Locale.US, "dMMM", "d MMM").format(day))
        assertEquals("Sep 17, 2026", localizedDatePattern(Locale.US, "yMMMd", "d MMM yyyy").format(day))
        assertEquals("Thu, Sep 17", localizedDatePattern(Locale.US, "EEEMMMd", "EEE d MMM").format(day))
        val long = { locale: Locale -> localizedDatePattern(locale, "yMMMMEEEd", "d MMMM yyyy, EEE").format(day) }
        assertEquals("Thu, September 17, 2026", long(Locale.US))
        assertTrue(long(Locale.JAPAN), long(Locale.JAPAN).startsWith("2026年9月17日"))
        // Where the day does come first, it still does.
        assertTrue(localizedDatePattern(Locale.forLanguageTag("ru"), "dMMM", "d MMM").format(day).startsWith("17 "))
    }

    /** The clock beside a date is the app's own, on twenty-four hours, whatever the pattern would have said. */
    @Test
    fun aDateWithATimeEndsInTheAppsClock() {
        val at = day.atTime(8, 5).atZone(ZoneId.of("Europe/Berlin"))
        assertEquals("Sep 17, 08:05", dayMonthClock(at, localizedDatePattern(Locale.US, "dMMM", "d MMM")))
        val evening = at.withHour(20).withMinute(0)
        assertEquals("9月17日, 20:00", dayMonthClock(evening, localizedDatePattern(Locale.JAPAN, "dMMM", "d MMM")))
    }

    /**
     * The doctor's sheet is the one place that writes a fixed order, and on purpose: its country's own
     * ([CountryProfile.datePattern]), with this fallback for a pattern that will not read.
     */
    @Test
    fun noScreenWritesTheDayFirstForEveryLanguage() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that reads no file finds nothing either.
        assertTrue(sources.size > 50)
        val fixed = Regex("""ofPattern\(\s*(?:if \([^)]*\) )?"[^"]*\b(?:d+ MMM|MMM+ d|MMM yy)""")
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { fixed.containsMatchIn(line) }
            }
        }
        assertEquals(listOf("ReportChartGeometry.kt"), found.map { it.substringBefore(':') })
    }
}
