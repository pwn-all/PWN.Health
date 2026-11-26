package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `!!` in the app's own code, counted.
 *
 * detekt.yml configures UnsafeCallOnNullableType as though it held the main sources, but the rule
 * cannot fire: it has to know that the value in front of the `!!` can be null, which takes the types
 * resolved, and the detekt task here resolves none. Each `!!` counted below stands on a value the
 * lines around it have already shown to be there.
 *
 * This is the count that rule would keep. A new `!!` fails it, and the answer is to say what
 * happens when the value is missing; where that truly cannot be, the number below goes up in the
 * same commit, where the change shows it. One taken out lowers it.
 */
class NotNullAssertionTest {
    @Test
    fun noNotNullAssertionArrivesUncounted() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that read no file finds nothing either.
        assertTrue(sources.size > 50)
        val found = sources.associate { file ->
            // Code only: a comment may speak of `!!`, on a line of its own or after the code.
            val code = file.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            file.name to code.sumOf { line -> ASSERTION.findAll(line.substringBefore(" //")).count() }
        }.filterValues { it > 0 }
        assertEquals(COUNTED.toSortedMap(), found.toSortedMap())
    }

    private companion object {
        val ASSERTION = Regex("!!")

        /** The file and how many it holds. */
        val COUNTED = mapOf(
            "Backup.kt" to 1,
            "CalendarScreen.kt" to 2,
            "CycleChartView.kt" to 3,
            "HealthExportReaders.kt" to 2,
            "InsightSeries.kt" to 1,
            "IntimateChartSeries.kt" to 1,
            "MainActivity.kt" to 2,
            "MorningStats.kt" to 2,
            "ProfileScreen.kt" to 1,
            "SensorScreen.kt" to 2,
            "TrackPages.kt" to 2,
        )
    }
}
