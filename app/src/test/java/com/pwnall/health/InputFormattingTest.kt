package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every numeric field in onboarding and the entry editor is filtered through these three
 * functions, so a wrong edge case shows up as an unenterable weight or a mangled set count.
 */
class InputFormattingTest {
    @Test
    fun wholeNumbersKeepAtMostFourDigits() {
        assertEquals("1990", "1990".digitsOnly())
        assertEquals("1990", "19 90".digitsOnly())
        assertEquals("1990", "1990abc".digitsOnly())
        assertEquals("1234", "123456".digitsOnly())
        assertEquals("", "abc".digitsOnly())
    }

    @Test
    fun decimalInputAcceptsCommaAsSeparator() {
        assertEquals("72.5", "72,5".decimalInput())
    }

    @Test
    fun decimalInputKeepsOneSeparatorAndTwoDecimals() {
        assertEquals("7.25", "7.2.5".decimalInput())
        assertEquals("72.56", "72.5678".decimalInput())
        assertEquals(".5", ".5".decimalInput())
        assertEquals("72.", "72.".decimalInput())
    }

    @Test
    fun decimalInputDropsLettersAndCapsWholeNumbers() {
        assertEquals("72", "72kg".decimalInput())
        assertEquals("1234567", "12345678".decimalInput())
    }

    /** A separator does not lift the cap, which would let absurd magnitudes into every field. */
    @Test
    fun decimalInputCapsTheWholePartEvenWithASeparator() {
        assertEquals("1234567.5", "123456789012345.5".decimalInput())
        assertEquals("1234567.", "12345678.".decimalInput())
    }

    /** A fully suppressed thyroid prints three decimals, and TSH's accepted range starts there. */
    @Test
    fun labDecimalInputKeepsTheThirdDecimalOfASuppressedTsh() {
        assertEquals("0.005", "0.005".labDecimalInput())
        assertEquals("0.005", "0,005".labDecimalInput())
        assertEquals("0.005", "0.0058".labDecimalInput())
        assertTrue("0.005".labDecimalInput().toDouble() in LabMarker.TSH.valid)
    }

    @Test
    fun labDecimalInputKeepsTheRestOfTheGuardsTheGramFieldsHave() {
        assertEquals("7.25", "7.2.5".labDecimalInput())
        assertEquals("72", "72mIU".labDecimalInput())
        assertEquals("1234567.5", "123456789012345.5".labDecimalInput())
    }

    @Test
    fun sleepClockStaysOnOneLine() {
        assertEquals("8:30", formatSleepClock(8 * 60 + 30))
        assertEquals("0:00", formatSleepClock(0))
        assertEquals("0:07", formatSleepClock(7))
        assertEquals("7:00", formatSleepClock(7 * 60))
        assertEquals("25:00", formatSleepClock(25 * 60))
        assertEquals("0:00", formatSleepClock(-90))
    }

    /** Truncated inches would lose up to a whole inch, so 180 cm would read as 5′10″ next to a rounded weight. */
    @Test
    fun imperialHeightRoundsToTheNearestInch() {
        assertEquals("5′ 11″", imperialHeight(180))
        assertEquals("5′ 0″", imperialHeight(152))
        assertEquals("6′ 0″", imperialHeight(183))
        assertEquals("5′ 7″", imperialHeight(170))
    }

    /**
     * The Arabic decimal separator is not a comma and not a dot, and the filter translates it rather
     * than deleting it: deleted, 1٫5 would arrive at the save path as 15, an order of magnitude out and
     * inside every range check the app has. The fullwidth pair comes from the same CJK keyboards
     * that already sent the fullwidth digits this filter folds.
     */
    @Test
    fun everyLocaleSeparatorBecomesTheDotTheSavePathParses() {
        assertEquals("1.5", "1\u066B5".decimalInput())
        assertEquals("72.5", "72\uFF0E5".decimalInput())
        assertEquals("72.5", "72\uFF0C5".decimalInput())
        assertEquals("1.5", "١٫٥".decimalInput())
        assertEquals("0.005", "0\u066B005".labDecimalInput())
    }

    @Test
    fun prettyDropsTrailingZeroAndKeepsOneDecimal() {
        assertEquals("70", 70.0.pretty())
        assertEquals("0", 0.0.pretty())
        assertEquals("1234", 1234.0.pretty())
        assertEquals("72.5", 72.5.pretty())
        assertEquals("12.3", 12.34.pretty())
        assertEquals("-3", (-3.0).pretty())
    }
}
