package com.pwnall.health

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A raw `clickable` tells a screen reader nothing about what a tap on it does: TalkBack reads it as
 * plain text. The meal flow's doors, a pain entry, a progress picture and a chart's cell each say they
 * are a button, as the calendar's and the medicine page's rows do; read from the source, because the
 * role is one argument a refactor drops without anything else changing.
 */
class ClickableRoleTest {
    @Test
    fun everyTapTargetOfTheseScreensIsAnnouncedAsAButton() {
        FILES.forEach { name ->
            val source = File("src/main/java/com/pwnall/health/$name.kt").readText()
            Regex("""\.clickable\s*([({])""").findAll(source).forEach { call ->
                val opened = call.range.last
                val arguments = if (source[opened] == '(') source.substring(opened, closing(source, opened)) else ""
                val line = source.substring(0, opened).count { it == '\n' } + 1
                assertTrue("$name.kt:$line", "role = " in arguments)
            }
        }
    }

    /** Where the bracket opened at [from] closes. */
    private fun closing(source: String, from: Int): Int {
        var depth = 0
        var at = from
        do {
            when (source[at]) {
                '(' -> depth++
                ')' -> depth--
            }
            at++
        } while (depth > 0)
        return at
    }

    private companion object {
        val FILES = listOf(
            "FoodFlow",
            "PainScreen",
            "WorkoutSession",
            "RoutineFlow",
            "ProgressPhotos",
            "MetricHistory",
            "OnboardingChrome",
            "PwnDesign",
        )
    }
}
