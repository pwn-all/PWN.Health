package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The two gates the age screen feeds, and the arithmetic under them.
 *
 * Worth a test of its own because every one of these is a wrong answer somebody ships: a birthday
 * that has not happened yet counted as though it had, an unreadable date read as an old one, a
 * future date subtracted into a large age. All three fail open — towards showing a child alcohol,
 * tobacco or a sexual-activity form — which is the only direction that matters here.
 */
class AgeGateTest {
    private val today = LocalDate.parse("2026-08-25")

    @Test
    fun ageCountsWholeYearsNotYearNumbers() {
        // Same year of birth, eleven months apart: one has had the birthday, one has not.
        assertEquals(13, ageOn("2026-08-25", "2013-08-25"))
        assertEquals(12, ageOn("2026-08-25", "2013-08-26"))
        assertEquals(12, ageOn("2026-08-25", "2013-12-31"))
    }

    @Test
    fun anUnreadableOrImpossibleDateIsNoAgeAtAll() {
        assertEquals(0, ageOn("2026-08-25", ""))
        assertEquals(0, ageOn("2026-08-25", "1998"))
        assertEquals(0, ageOn("not a day", "1998-01-20"))
        // Born tomorrow: a negative span, which must not come back as a large positive one.
        assertEquals(0, ageOn("2026-08-25", "2026-08-26"))
    }

    @Test
    fun sexualLoggingOpensOnTheSixteenthBirthdayAndNotBefore() {
        assertTrue(sexLogOffered("2010-08-25", today))
        assertTrue(sexLogOffered("1990-01-01", today))
        assertFalse(sexLogOffered("2010-08-26", today))
        assertFalse(sexLogOffered(null, today))
        assertFalse(sexLogOffered("", today))
    }

    @Test
    fun alcoholAndTobaccoOpenOnTheEighteenthBirthdayAndNotBefore() {
        assertTrue(habitsOffered("2008-08-25", today))
        assertFalse(habitsOffered("2008-08-26", today))
        assertFalse(habitsOffered(null, today))
        // A child under both gates: old enough for neither.
        assertFalse(habitsOffered("2014-03-02", today))
        assertFalse(sexLogOffered("2014-03-02", today))
    }
}
