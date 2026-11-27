package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The age page as a question with no answer in it, and the page counter it sits on.
 *
 * The wheels open on a blank row each. Opened on the day twenty-five years back with Next lit,
 * tapping straight through would record an adult — the populated age screen Play's families review
 * names — and open everything [AgeGateTest]'s gates close. These pin what a blank row means, and
 * that nothing the wheels do on their own puts a date there or takes one away.
 */
class OnboardingAgeTest {
    private val today = LocalDate.parse("2026-08-25")

    @Test
    fun wheelsNobodyHasTurnedAddUpToNoDate() {
        assertNull(pickedBirthDate(BIRTH_UNSET, BIRTH_UNSET, BIRTH_UNSET))
    }

    @Test
    fun oneWheelLeftBlankIsStillNoDate() {
        assertNull(pickedBirthDate(BIRTH_UNSET, 3, 2))
        assertNull(pickedBirthDate(2014, BIRTH_UNSET, 2))
        assertNull(pickedBirthDate(2014, 3, BIRTH_UNSET))

        assertEquals(LocalDate.parse("2014-03-02"), pickedBirthDate(2014, 3, 2))
    }

    /** A flick that barely leaves the blank row lands on an age the gates open nothing for. */
    @Test
    fun theYearBesideTheBlankRowIsThisOne() {
        val years = birthYears(today)

        assertEquals(BIRTH_UNSET, years.last())
        assertEquals(today.year, years[years.lastIndex - 1])
        assertEquals(1900, years.first())
        val youngest = pickedBirthDate(today.year, 1, 1).toString()
        assertFalse(sexLogOffered(youngest, today))
        assertFalse(habitsOffered(youngest, today))
    }

    @Test
    fun aBlankMonthOrYearDoesNotCutTheDayWheelShort() {
        assertEquals(31, lastBirthDay(BIRTH_UNSET, BIRTH_UNSET, today))
        assertEquals(31, lastBirthDay(2014, BIRTH_UNSET, today))
        // The 29th of February can be turned to before its year is.
        assertEquals(29, lastBirthDay(BIRTH_UNSET, 2, today))
        assertEquals(29, lastBirthDay(2012, 2, today))
        assertEquals(28, lastBirthDay(2014, 2, today))
    }

    @Test
    fun nobodyWasBornAfterToday() {
        assertEquals(25, lastBirthDay(2026, 8, today))
        assertEquals(31, lastBirthDay(2026, 7, today))
        assertEquals(31, lastBirthDay(2025, 8, today))
    }

    /**
     * The rows the wheels were turned to outlive a year wheel that passes this year, and what Next
     * commits is what the wheels show then: never a day after today, the 29th of February only in a
     * leap year, and the kept 31st of December back again once the year moves on.
     */
    @Test
    fun theDateNextCommitsIsTheOneTheWheelsShow() {
        assertEquals(today, shownBirthDate(2026, 12, 31, today))
        assertEquals(LocalDate.parse("2026-07-31"), shownBirthDate(2026, 7, 31, today))
        assertEquals(LocalDate.parse("2025-12-31"), shownBirthDate(2025, 12, 31, today))
        assertEquals(LocalDate.parse("2024-02-29"), shownBirthDate(2024, 2, 31, today))
        assertEquals(LocalDate.parse("2023-02-28"), shownBirthDate(2023, 2, 29, today))
        assertNull(shownBirthDate(2026, BIRTH_UNSET, 31, today))
        assertNull(shownBirthDate(BIRTH_UNSET, 12, 31, today))
    }

    @Test
    fun noRowsTheWheelsCanBeTurnedToAddUpToATomorrow() {
        for (year in listOf(2023, 2024, 2025, 2026)) {
            assertEquals(if (year == today.year) 8 else 12, lastBirthMonth(year, today))
            for (month in BIRTH_UNSET..12) {
                for (day in BIRTH_UNSET..31) {
                    val shown = shownBirthDate(year, month, day, today) ?: continue
                    assertFalse("$year-$month-$day", shown.isAfter(today))
                }
            }
        }
    }

    /**
     * The 31st of December is on the wheels and the year wheel leaves its blank row, which stands
     * beside this year: for as long as it passes 2026 the month wheel ends on August and the day
     * wheel on today's 25th, and each reports the row it was cut back to.
     */
    @Test
    fun aWheelCutShortInPassingDoesNotTrimWhatWasTurnedTo() {
        assertEquals(31, keptBirthRow(reported = 25, kept = 31, last = 25))
        assertEquals(12, keptBirthRow(reported = 8, kept = 12, last = 8))
        // February on the way to March: the 31st is still what the person turned to.
        assertEquals(31, keptBirthRow(reported = 29, kept = 31, last = 29))
    }

    @Test
    fun aRowTurnedToByHandIsAlwaysTaken() {
        assertEquals(7, keptBirthRow(reported = 7, kept = BIRTH_UNSET, last = 31))
        assertEquals(24, keptBirthRow(reported = 24, kept = 31, last = 25))
        assertEquals(BIRTH_UNSET, keptBirthRow(reported = BIRTH_UNSET, kept = 7, last = 31))
        // Away from the last row and back to it, on a wheel that is not cut short at all.
        assertEquals(25, keptBirthRow(reported = 25, kept = 24, last = 25))
    }

    /**
     * The header's arrow can still be tapped while the header fades out over the greeting. A second
     * tap there must not count down to page −1, which would be drawn as the finish with Start on it.
     */
    @Test
    fun backNeverCountsBelowTheGreeting() {
        assertEquals(1, pageBefore(2))
        assertEquals(0, pageBefore(1))
        assertEquals(0, pageBefore(0))
    }

    /** The three wheels stand as the language writes a date, not day, month, year in all sixteen. */
    @Test
    fun theWheelsStandInTheOrderTheLanguageWritesADateIn() {
        assertEquals(listOf('M', 'd', 'y'), birthWheelOrder("MMM d, y"))
        assertEquals(listOf('y', 'M', 'd'), birthWheelOrder("y年M月d日"))
        assertEquals(listOf('y', 'M', 'd'), birthWheelOrder("y년 MMM d일"))
        assertEquals(listOf('d', 'M', 'y'), birthWheelOrder("d MMM y"))
        // A standalone month is a month, and the words between the fields are not fields.
        assertEquals(listOf('d', 'M', 'y'), birthWheelOrder("d LLL y"))
        assertEquals(listOf('d', 'M', 'y'), birthWheelOrder("d 'de' MMM 'de' y"))
        // A pattern that could not be read leaves day, month, year.
        assertEquals(listOf('d', 'M', 'y'), birthWheelOrder(""))
        assertEquals(listOf('d', 'M', 'y'), birthWheelOrder("MMM y"))
    }
}
