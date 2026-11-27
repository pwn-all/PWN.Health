package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repeated export password is marked only once it has stopped being the password being typed,
 * and the export waits for a password long enough to be worth sealing a file with.
 */
class ExportPasswordTest {
    /** One character must not seal a file that says protected: eight, not blank, and the same twice. */
    @Test
    fun theExportWaitsForEightCharactersTypedTheSameTwice() {
        assertFalse(exportPasswordReady("abc", "abc"))
        assertFalse(exportPasswordReady("1234567", "1234567"))
        assertFalse(exportPasswordReady("        ", "        "))
        assertFalse(exportPasswordReady("12345678", "12345679"))
        assertTrue(exportPasswordReady("12345678", "12345678"))
    }

    /** The field says so once something is typed, not over a field nobody has touched. */
    @Test
    fun aPasswordBegunAndStillShortIsMarkedAndAnEmptyOneIsNot() {
        assertFalse(exportPasswordTooShort(""))
        assertTrue(exportPasswordTooShort("abc"))
        assertFalse(exportPasswordTooShort("12345678"))
    }

    @Test
    fun anEmptyOrUnfinishedRepeatIsNotAMistake() {
        assertFalse(passwordsDiverge("secret", ""))
        assertFalse(passwordsDiverge("secret", "sec"))
        assertFalse(passwordsDiverge("secret", "secret"))
    }

    @Test
    fun aRepeatThatDiffersOrRunsLongerIsMarked() {
        assertTrue(passwordsDiverge("secret", "sex"))
        assertTrue(passwordsDiverge("secret", "secrets"))
        assertTrue(passwordsDiverge("", "a"))
    }
}
