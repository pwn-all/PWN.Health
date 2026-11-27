package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The first screen's sixteen greetings, and which of them goes first. */
class OnboardingGreetingTest {
    /**
     * Indonesian is "in" to the resource folders and older runtimes and "id" to newer ones, and the
     * phone's language is whichever its runtime returns. Looked up as written, the greeting would
     * match one of the two and the other phone would open on "Hello".
     */
    @Test
    fun theIndonesianGreetingGoesFirstWhicheverCodeTheRuntimeGives() {
        assertEquals("Halo", greetingsFor(Locale.forLanguageTag("id").language).first())
        assertEquals("Halo", greetingsFor(Locale.forLanguageTag("in").language).first())
    }

    @Test
    fun aLanguageWithoutAGreetingOpensOnEnglishAndAllSixteenGoRound() {
        val greetings = greetingsFor("sw")
        assertEquals("Hello", greetings.first())
        assertEquals(16, greetings.toSet().size)
        assertEquals("Привет", greetingsFor("ru").first())
        assertEquals(greetings.toSet(), greetingsFor("ru").toSet())
    }
}
