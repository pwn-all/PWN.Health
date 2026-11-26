package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.InvalidKeyException
import java.security.ProviderException
import java.util.Base64
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * The decisions taken before Room is allowed near `steps.db`, at their edges: the whole plan table
 * rather than four cells of it, a swap interrupted with two kept originals lying beside it, a move
 * that half succeeds, and the shapes the key takes on its way through the preferences.
 *
 * Everything here runs on a start that is already going badly, and a wrong answer is measured in
 * whole health records rather than in wrong pixels: there is no second copy of this file.
 */
class SecurityPathsEdgeCasesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The sixteen bytes a plaintext SQLite file opens with: fifteen of name, then a NUL. */
    private val sqliteHeader = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

    /** A stale kept original, and the one an interrupted conversion actually set aside. */
    private val lastMonth = "the plaintext record of last month"
    private val rightNow = "the ciphered record as of now"

    private data class Cell(
        val state: EncryptionState,
        val header: FileHeader,
        val key: KeyAccess,
        val plan: EncryptionPlan,
    )

    /** Every combination of switch, file and Keystore answer, with the plan each one yields. */
    private fun table(headers: List<FileHeader> = FileHeader.entries): List<Cell> =
        EncryptionState.entries.flatMap { state ->
            headers.flatMap { header ->
                KeyAccess.entries.map { key -> Cell(state, header, key, encryptionPlan(state, header, key)) }
            }
        }

    /** A missing `steps.db` with two kept originals beside it: one left over, one this swap's own. */
    private fun twoKeptOriginals() {
        folder.newFile("steps.db.pre-encrypt.bak").apply {
            writeText(lastMonth)
            setLastModified(1_700_000_000_000L)
        }
        folder.newFile("steps.db.pre-decrypt.bak").apply {
            writeText(rightNow)
            setLastModified(1_780_000_000_000L)
        }
    }

    /**
     * `reconcile` turns DECRYPT into `requireNotNull(key.getOrNull())` and REFUSE into
     * `requireNotNull(key.exceptionOrNull())`, and sets a file aside only on the Keystore's word
     * that no key here fits. A plan paired with any other answer is not a wrongly handled file: it
     * is an exception thrown on every single start, with no switch left anywhere to reach.
     */
    @Test
    fun everyPlanArrivesWithTheKeystoreAnswerItDereferences() {
        val cells = table()

        cells.forEach { cell ->
            if (cell.plan == EncryptionPlan.DECRYPT) assertEquals("$cell", KeyAccess.UNWRAPS, cell.key)
            if (cell.plan == EncryptionPlan.REFUSE) assertEquals("$cell", KeyAccess.UNAVAILABLE, cell.key)
            if (cell.plan == EncryptionPlan.LOCK_ASIDE) assertEquals("$cell", KeyAccess.NONE, cell.key)
        }
        // And no branch of the when() is unreachable: each plan is what some real start does.
        assertEquals(EncryptionPlan.entries.toSet(), cells.map { it.plan }.toSet())
    }

    /**
     * A file this phone can read without any key at all. Whatever the Keystore says — and it is
     * known to say nothing in the first seconds after boot, when the step service asks first — such
     * a file may never be renamed out from under its owner as `.locked`, and may never stop the app
     * starting. The Keystore's answer must not enter the decision at all here.
     */
    @Test
    fun aFileThatNeedsNoKeyIsNeverSetAsideNorRefused() {
        val cells = table(listOf(FileHeader.PLAIN, FileHeader.ABSENT))
        val opens = setOf(EncryptionPlan.OPEN_PLAIN, EncryptionPlan.OPEN_CIPHERED, EncryptionPlan.ENCRYPT)

        assertEquals(24, cells.size)
        cells.forEach { cell -> assertTrue("$cell", cell.plan in opens) }
        cells.groupBy { it.state to it.header }.forEach { (situation, group) ->
            assertEquals("$situation answers ${group.map { it.plan }}", 1, group.map { it.plan }.distinct().size)
        }
    }

    /**
     * A power loss or a full disk leaves a file cut off mid-write, and the length it is cut off at
     * is exactly the boundary of the header read. Only an empty file is absent; anything shorter
     * than the sixteen bytes is not SQLite's own header and is read as ciphered — which is worth
     * pinning precisely because it is the answer that routes a truncated file of this phone's into
     * the device-transfer path rather than the quarantine.
     */
    @Test
    fun aFileShorterThanTheHeaderIsNotPlain() {
        val db = folder.root.resolve("steps.db")

        db.writeBytes(sqliteHeader.copyOf(15))
        assertEquals(FileHeader.CIPHERED, fileHeader(db))
        db.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertEquals(FileHeader.CIPHERED, fileHeader(db))
        db.writeBytes(sqliteHeader)
        assertEquals(FileHeader.PLAIN, fileHeader(db))
    }

    /**
     * `moveDatabaseFiles` promises exactly what its own line says — every piece that existed moved —
     * and not atomicity: a piece that will not move is left where it is, with the pieces before it
     * already at the new name. What the callers stand on is the false answer plus the record still
     * being under one of the two names. `lockAside` writes the failure under the switch and the file
     * keeps the `.locked` name nothing deletes; `convert` throws on the false answer into its own
     * recovery, which finds the half-moved file sitting at exactly the `.bak` name it looks for. A
     * rollback here would be worse, not better: it would put the kept original of a quarantined copy
     * back under the name the next successful open sweeps.
     */
    @Test
    fun aMoveThatFailsHalfwayReportsFailureAndKeepsTheRecord() {
        val db = folder.newFile("steps.db").apply { writeText("rows") }
        folder.newFile("steps.db-wal").writeText("writes not yet merged")
        folder.newFolder("steps.db.locked-wal").resolve("in the way").writeText("the rename cannot pass")

        val moved = moveDatabaseFiles(db.path, "${db.path}.locked")

        assertFalse("the log could not move, so the move did not succeed", moved)
        val record = listOf(db, folder.root.resolve("steps.db.locked")).filter { it.exists() }
        assertEquals("the record is under neither name", 1, record.size)
        assertEquals("rows", record.single().readText())
    }

    /**
     * What the numbering is for is an earlier readable database, and a `.locked-wal` whose main file
     * its owner deleted by hand is not one: a write-ahead log cannot be opened or read without the
     * database it belongs to, by this app or by `sqlite3`, and a `-shm` is scratch rebuilt on every
     * open. So neither of them reserves a name — `.locked` is handed out and the orphans are renamed
     * over — while a main file that is there moves the count along, which is the promise itself.
     */
    @Test
    fun anOrphanedLogDoesNotReserveALockedName() {
        val db = folder.root.resolve("steps.db")
        folder.newFile("steps.db.locked-wal").writeText("a log with no database of its own")
        folder.newFile("steps.db.locked-shm")

        assertEquals("${db.path}.locked", freeName("${db.path}.locked"))

        folder.newFile("steps.db.locked").writeText("the first transfer's")
        assertEquals("${db.path}.locked.2", freeName("${db.path}.locked"))
    }

    /**
     * Two kept originals, and only one of them can be the copy this interrupted swap set aside. A
     * `.pre-encrypt.bak` outlives its own conversion whenever a broadcast-started process is killed
     * before Room's first lazy query, and the decryption that is interrupted months later must put
     * back its own `.pre-decrypt.bak` — the record as of now — rather than the older name.
     */
    @Test
    fun anInterruptedSwapPutsBackTheCopyThatSwapSetAside() {
        val db = folder.root.resolve("steps.db")
        twoKeptOriginals()

        recoverInterruptedSwap(db)

        assertEquals(rightNow, db.readText())
    }

    /**
     * And whichever one comes back, the open that follows a minute later may not destroy the other:
     * `opened` deletes both `.bak` names unconditionally, so a recovery that restores the wrong one
     * is not a wrong start, it is the permanent loss of everything written since the conversion.
     */
    @Test
    fun theOpenThatFollowsARecoveryDestroysNoWholeRecord() {
        val db = folder.root.resolve("steps.db")
        twoKeptOriginals()

        recoverInterruptedSwap(db)
        DatabaseEncryption.opened(db.path)

        val left = folder.root.walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue("all that is left: $left", left.any { it.contains(rightNow) })
    }

    /**
     * `fileHeader` counts a zero-length file as no database, "SQLite treating it as a database with
     * nothing in it", and the recovery counts it so too: counted as a database, it would pass over
     * the whole record waiting beside it. An interrupted create, a restore that made the file before
     * writing it, or SQLCipher opening a missing source with CREATE_IF_NECESSARY all leave exactly
     * this, and the fresh database that follows would take the `.bak` with it.
     */
    @Test
    fun aZeroLengthFileIsNoDatabaseToTheRecoveryEither() {
        val db = folder.newFile("steps.db")
        folder.newFile("steps.db.pre-encrypt.bak").writeText(rightNow)

        recoverInterruptedSwap(db)

        assertEquals(rightNow, db.readText())
    }

    /**
     * Both of these run while a start is already failing, and both meet conventions that disagree:
     * `listFiles` answers null for a directory that is not there, `renameTo` answers false for a
     * file that is not. A throw here turns a recoverable corruption into a crash loop.
     */
    @Test
    fun theFailurePathsSurviveADirectoryThatHoldsNothing() {
        val absentDirectory = folder.root.resolve("gone/steps.db")

        assertEquals(emptyList<File>(), setAsideCopies(absentDirectory, SetAside.LOCKED))
        assertEquals(emptyList<File>(), setAsideCopies(folder.root.resolve("steps.db"), SetAside.LOCKED))
        quarantineDatabaseFiles(folder.root.resolve("steps.db").path)

        assertEquals(emptyList<String>(), folder.root.list()?.toList())
    }

    /**
     * The count of refused starts is read out of the preferences, where a bad merge, a restored
     * backup or a hand-edited file can leave any Int at all. Under the limit it waits; at or past
     * it, it gives up and sets the record aside — a counter that has somehow run away must not turn
     * "give up after three" into "never start again", which is what `refusalsSoFar + 1` overflowing
     * to Int.MIN_VALUE does.
     */
    @Test
    fun aRefusalCounterPastTheLimitStillSetsTheFileAside() {
        assertFalse("no start has been refused yet", refusalLocksAside(-1))
        assertTrue(refusalLocksAside(100))
        assertTrue(refusalLocksAside(Int.MAX_VALUE - 1))
        assertTrue("a counter at ${Int.MAX_VALUE} is not a Keystore having a hiccup", refusalLocksAside(Int.MAX_VALUE))
    }

    /**
     * AEADBadTagException is a subclass of BadPaddingException, so a check written the other way
     * round — `is BadPaddingException` — would still pass every test of the tag case while quietly
     * widening "this file belongs to another phone" to take in provider failures that say nothing
     * about the file. Those must refuse the start and ask again, not set the record aside.
     */
    @Test
    fun aPaddingFailureThatIsNotGcmsTagIsNoVerdictOnTheBlob() {
        assertTrue(blobDoesNotFit(AEADBadTagException("tag mismatch")))
        assertFalse(blobDoesNotFit(BadPaddingException("pad block corrupted")))
        assertFalse(blobDoesNotFit(IllegalBlockSizeException("Keystore operation failed")))
        assertFalse(blobDoesNotFit(InvalidKeyException("Keystore operation failed")))
    }

    /**
     * This message is what the stack trace of a start that failed before Room existed says; the line
     * under the encryption switch words the same failure in the phone's language. A cause with no
     * message of its own — which is how ProviderException usually arrives — must still name what
     * failed, and must not print the word "null" at somebody.
     */
    @Test
    fun theRefusalNamesTheKeystoreFailureEvenWhenItSaidNothing() {
        val silent = ProviderException()
        val spoken = ProviderException("Keystore operation failed")

        val fromSilent = KeystoreUnavailableException(silent).message.orEmpty()
        val fromSpoken = KeystoreUnavailableException(spoken).message.orEmpty()

        assertTrue(fromSilent, fromSilent.contains("ProviderException"))
        assertFalse(fromSilent, fromSilent.contains("null"))
        assertTrue(fromSilent, fromSilent.trim().endsWith("ProviderException"))
        assertTrue(fromSpoken, fromSpoken.contains("Keystore operation failed"))
        assertSame(silent, KeystoreUnavailableException(silent).cause)
    }

    /**
     * The stored blob is read on the first line of every start of a phone that has ever had the
     * switch on, and what is in the preferences is whatever a backup, a merge or a partial write
     * left there. Base64's basic decoder throws on the url-safe alphabet, on embedded whitespace
     * and on bad padding; a throw here is an app that will not open at all.
     */
    @Test
    fun nothingInTheStoredBlobIsAnExceptionRatherThanANull() {
        val blobs = listOf(
            "",
            "A",
            "====",
            "AAAA\n",
            "AA-_AAAA",
            "😀😀😀😀",
            " ",
            "not base64 at all".repeat(500),
        )

        blobs.forEach { blob -> assertNull("blob [${blob.take(16)}]", unpackWrapped(blob)) }
    }

    /**
     * And whenever a pair does come back, the first half is the nonce AES-GCM takes — 96 bits — and
     * the second half is everything else, whatever the blob's length. Splitting one byte off gives
     * two halves that unwrap to nothing, which reads as another phone's key.
     */
    @Test
    fun everyBlobThatDoesUnpackHandsBackATwelveByteNonce() {
        val blobs = listOf(
            packWrapped(ByteArray(12) { 7 }, ByteArray(17)),
            packWrapped(ByteArray(12), ByteArray(65_536) { it.toByte() }),
            Base64.getEncoder().encodeToString(ByteArray(29) { it.toByte() }),
        )

        blobs.forEach { blob ->
            val (iv, sealed) = requireNotNull(unpackWrapped(blob))
            val decoded = Base64.getDecoder().decode(blob).size
            assertEquals("blob of $decoded bytes", 12, iv.size)
            assertEquals("blob of $decoded bytes", decoded - 12, sealed.size)
        }
    }

    /**
     * SQLCipher reads `x'` + 64 hex digits + `'` as the key itself and anything else as a
     * passphrase to run through PBKDF2 — a different key, derived slowly, out of less entropy. The
     * spelling function checks no length, so only the caller's `size == KEY_BYTES` stands between a
     * short key and a database opened under something nobody intended.
     */
    @Test
    fun onlyAThirtyTwoByteKeyIsSpelledAsSqlCiphersRawKey() {
        val spelled = (0..64).map { size -> size to rawKeyPassphrase(ByteArray(size)).size }

        spelled.forEach { (size, length) -> assertEquals("$size bytes", 2 * size + 3, length) }
        assertEquals(listOf(32), spelled.filter { it.second == 67 }.map { it.first })
    }

    /**
     * The hex is written with String.format on the phone's own locale. Every byte has to come back
     * out of it exactly, on a phone set to any language: a single digit rendered in another script,
     * or a case flipped by a locale-sensitive upper-casing, is every database on that phone
     * unreadable at the next start with no way to tell what happened.
     */
    @Test
    fun theHexSpellingReadsBackToTheSameBytesWhateverThePhonesLanguage() {
        val key = ByteArray(32) { (it * 37 + 11).toByte() }
        val was = Locale.getDefault()
        val spelled = try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            rawKeyPassphrase(key).toString(Charsets.US_ASCII)
        } finally {
            Locale.setDefault(was)
        }

        val digits = spelled.removeSurrounding("x'", "'")
        assertTrue(spelled, digits.all { it in "0123456789abcdef" })
        assertArrayEquals(key, ByteArray(32) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
}
