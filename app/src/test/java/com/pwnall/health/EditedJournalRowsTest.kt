package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The list of rows the after-edit mirror owes Health Connect.
 *
 * It exists so that saving a glass of water does not read the whole journal. What it must not do is
 * lose a row: anything it forgets is published only by the next full exchange, and anything it
 * forgets *early* — before the publishing has been through — is published by nothing until then.
 */
class EditedJournalRowsTest {
    @Before
    @After
    fun clear() = forgetEditedJournalRows(editedJournalRows())

    @Test
    fun aRowIsRememberedUntilItIsPublished() {
        rememberEditedJournalRow(7)
        rememberEditedJournalRow(9)

        val owed = editedJournalRows()
        assertEquals(setOf(7L, 9L), owed)
        // Reading is not taking: a mirror that fails leaves the rows for the next one.
        assertEquals(setOf(7L, 9L), editedJournalRows())

        forgetEditedJournalRows(owed)
        assertEquals(emptySet<Long>(), editedJournalRows())
    }

    /** An edit made while the mirror is publishing is not covered by it and has to survive it. */
    @Test
    fun onlyTheRowsThatWerePublishedAreForgotten() {
        rememberEditedJournalRow(1)
        val publishing = editedJournalRows()
        rememberEditedJournalRow(2)

        forgetEditedJournalRows(publishing)

        assertEquals(setOf(2L), editedJournalRows())
    }

    /** A row Room has not numbered yet is nobody the mirror could read back. */
    @Test
    fun anUnnumberedRowIsNotRemembered() {
        rememberEditedJournalRow(0)

        assertEquals(emptySet<Long>(), editedJournalRows())
    }

    /**
     * Past the cap the set stops growing rather than becoming a second copy of the journal: the
     * mirror reads these rows in one statement, and the exchange is what publishes the rest.
     */
    @Test
    fun theSetStopsGrowingRatherThanGrowingWithoutEnd() {
        repeat(600) { rememberEditedJournalRow(it + 1L) }

        val owed = editedJournalRows()
        assertEquals(500, owed.size)
        assertTrue("nothing but real row ids", owed.all { it in 1L..600L })
    }
}
