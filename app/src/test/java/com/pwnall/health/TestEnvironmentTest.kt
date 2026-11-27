package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unit tests run in Auckland's time zone and in Turkish (app/build.gradle.kts), not in the UTC
 * and the C locale of the machines that run them, where an offset of nought and a dotless "i" that
 * never appears let a test pass on an assumption a phone does not share. Read from the properties the
 * JVM was started with: Robolectric sets the default locale of each of its tests to its own.
 */
class TestEnvironmentTest {
    @Test
    fun theSuiteRunsAwayFromUtcAndInTurkish() {
        assertEquals("Pacific/Auckland", System.getProperty("user.timezone"))
        assertEquals("tr", System.getProperty("user.language"))
        assertEquals("TR", System.getProperty("user.country"))
    }
}
