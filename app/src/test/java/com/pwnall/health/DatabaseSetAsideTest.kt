package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The databases that wait beside the one in use, and the way back for the one that has one.
 *
 * A Keystore that refuses three starts has this phone's own record set aside under a name of its own,
 * not that of a file no key opens, which the profile would call another phone's and offer to delete,
 * and which nothing would try again when the Keystore answers. What is here is the file side of the way back, which
 * needs no SQLCipher: which copy is whose, what a start decides about one that was asked for, and
 * the exchange of names. The open under the key runs on a phone.
 */
class DatabaseSetAsideTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val db get() = folder.root.resolve("steps.db")

    /** The name alone says what a copy is, and the card speaks of the one most can be done about. */
    @Test
    fun theCardSpeaksOfThisPhonesOwnCopyFirst() {
        assertNull(setAsideKind(db))
        folder.newFile("steps.db")
        folder.newFile("steps.db-wal")
        folder.newFile("steps.db.pre-encrypt.bak")
        assertNull(setAsideKind(db))

        folder.newFile("steps.db.corrupt")
        assertEquals(SetAside.DAMAGED, setAsideKind(db))
        folder.newFile("steps.db.locked")
        assertEquals(SetAside.LOCKED, setAsideKind(db))
        folder.newFile("steps.db.aside")
        assertEquals(SetAside.OWN, setAsideKind(db))
    }

    /**
     * A plain copy needs no key. A ciphered one comes back only under a key that unwrapped; a
     * Keystore that would not answer is no verdict and the copy waits, as it did when it was set
     * aside; one with no key that fits has said the copy is not this phone's to open any more.
     */
    @Test
    fun aCopyComesBackOnlyUnderAKeyThatOpensIt() {
        KeyAccess.entries.forEach { key ->
            assertEquals(ReturnPlan.SWAP, returnPlan(FileHeader.PLAIN, key))
            assertEquals(ReturnPlan.SWAP, returnPlan(FileHeader.ABSENT, key))
        }
        assertEquals(ReturnPlan.SWAP, returnPlan(FileHeader.CIPHERED, KeyAccess.UNWRAPS))
        assertEquals(ReturnPlan.WAIT, returnPlan(FileHeader.CIPHERED, KeyAccess.UNAVAILABLE))
        assertEquals(ReturnPlan.LOCK, returnPlan(FileHeader.CIPHERED, KeyAccess.NONE))
    }

    /**
     * The record comes back under the live name with its log, and the one begun in the meantime
     * takes a set-aside name of its own: nothing is deleted by the exchange, and the same ask
     * brings the other back.
     */
    @Test
    fun theTwoRecordsChangePlacesAndBack() {
        folder.newFile("steps.db").writeText("begun in the meantime")
        folder.newFile("steps.db-wal").writeText("its log")
        folder.newFile("steps.db.aside").writeText("the record")
        folder.newFile("steps.db.aside-wal").writeText("the record's log")

        assertTrue(swapIn(db, requireNotNull(ownCopyToReturn(db))))

        assertEquals("the record", db.readText())
        assertEquals("the record's log", folder.root.resolve("steps.db-wal").readText())
        assertEquals("begun in the meantime", folder.root.resolve("steps.db.aside.2").readText())
        assertEquals("its log", folder.root.resolve("steps.db.aside.2-wal").readText())
        assertFalse(folder.root.resolve("steps.db.aside").exists())
        assertEquals(SetAside.OWN, setAsideKind(db))

        assertTrue(swapIn(db, requireNotNull(ownCopyToReturn(db))))

        assertEquals("begun in the meantime", db.readText())
        assertEquals("the record", folder.root.resolve("steps.db.aside").readText())
        assertEquals("the record's log", folder.root.resolve("steps.db.aside-wal").readText())
        assertFalse(folder.root.resolve("steps.db.aside.2").exists())
    }

    /**
     * A copy is a database, not its log: the log's later timestamp must not make it the file that
     * is opened. And of two copies — what a return leaves that died between its renames, with
     * nothing under the live name — the one written last comes back, the other staying beside it.
     */
    @Test
    fun theNewestCopyIsTheOneThatComesBack() {
        assertNull(ownCopyToReturn(db))
        val older = folder.newFile("steps.db.aside").apply { writeText("asked for") }
        val newer = folder.newFile("steps.db.aside.2").apply { writeText("in use a moment ago") }
        val log = folder.newFile("steps.db.aside-wal")
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)
        log.setLastModified(3_000_000L)

        val back = requireNotNull(ownCopyToReturn(db))
        assertEquals(newer, back)
        assertTrue(swapIn(db, back))

        assertEquals("in use a moment ago", db.readText())
        assertEquals("asked for", older.readText())
        assertEquals(listOf("steps.db", "steps.db.aside", "steps.db.aside-wal"), folder.root.list()?.sorted())
    }
}
