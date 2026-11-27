package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * The decisions made about the file before Room is allowed near it, and the file operations a crash
 * can leave half done. The conversion itself needs SQLCipher's native library, which no JVM test
 * has: DatabaseEncryptionDeviceTest, in app/src/androidTest, runs it on a phone or an emulator, and
 * only there — `check` compiles that test and the release checklist asks for its run. What is here
 * is everything around the conversion that can go wrong on its own.
 */
class DatabaseEncryptionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sqliteHeader = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

    private val off = EncryptionState.OFF
    private val on = EncryptionState.ON
    private val pendingOn = EncryptionState.PENDING_ON
    private val pendingOff = EncryptionState.PENDING_OFF
    private val absent = FileHeader.ABSENT
    private val plain = FileHeader.PLAIN
    private val ciphered = FileHeader.CIPHERED
    private val unwraps = KeyAccess.UNWRAPS
    private val noKey = KeyAccess.NONE
    private val unavailable = KeyAccess.UNAVAILABLE

    /** The ordinary starts: a plain file under an off switch, a ciphered one under an on switch. */
    @Test
    fun aFileThatMatchesTheSwitchIsOpenedAsItIs() {
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(off, plain, noKey))
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(on, ciphered, unwraps))
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(off, absent, noKey))
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(on, absent, noKey))
    }

    /** The switch was moved and the process restarted: the file is rewritten under the other key. */
    @Test
    fun aPendingWishConvertsTheFile() {
        assertEquals(EncryptionPlan.ENCRYPT, encryptionPlan(pendingOn, plain, noKey))
        assertEquals(EncryptionPlan.ENCRYPT, encryptionPlan(pendingOn, plain, unwraps))
        assertEquals(EncryptionPlan.DECRYPT, encryptionPlan(pendingOff, ciphered, unwraps))
    }

    /**
     * The process died between the swap and the note of it: the switch still says pending, the file
     * is already what was asked for. Converting again would fail on it; it is opened instead.
     */
    @Test
    fun aWishAlreadyGrantedIsNotGrantedTwice() {
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(pendingOn, ciphered, unwraps))
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(pendingOff, plain, unwraps))
    }

    /**
     * A ciphered file whose key unwraps, under a switch that is at OFF without anybody having moved
     * it — one the preferences do not spell reads as OFF. Only a wish takes the cipher off; this
     * file is opened ciphered, not decrypted on the spot.
     */
    @Test
    fun onlyAWishTakesTheCipherOffAFile() {
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(off, ciphered, unwraps))
        assertEquals(EncryptionPlan.DECRYPT, encryptionPlan(pendingOff, ciphered, unwraps))
    }

    /** No file yet under a pending wish: nothing to convert, the helper for the wish creates it. */
    @Test
    fun aMissingFileIsCreatedUnderTheWishedKey() {
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(pendingOn, absent, noKey))
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(pendingOff, absent, unwraps))
    }

    /**
     * A ciphered file and the Keystore's word that no key here fits — the transfer brought the file
     * and the old phone kept the key. Whatever the switch says, this is nobody's to convert and not
     * corrupt either.
     */
    @Test
    fun aCipheredFileWithoutItsKeyIsLockedAsideWhateverTheSwitchSays() {
        EncryptionState.entries.forEach { state ->
            assertEquals(state.name, EncryptionPlan.LOCK_ASIDE, encryptionPlan(state, ciphered, noKey))
        }
    }

    /**
     * A ciphered file and a Keystore that would not answer. That is no verdict on the key, and a
     * healthy record must not be set aside on it: nothing is opened, and the next start asks again.
     */
    @Test
    fun aKeystoreThatWillNotAnswerIsRefusedNotLockedAside() {
        EncryptionState.entries.forEach { state ->
            assertEquals(state.name, EncryptionPlan.REFUSE, encryptionPlan(state, ciphered, unavailable))
        }
    }

    /**
     * A plain or missing file is readable without the Keystore, so its silence stops nothing here;
     * whether a key can be made for the wish is settled when the key is asked for.
     */
    @Test
    fun aKeystoreThatWillNotAnswerDoesNotStopAPlainFile() {
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(off, plain, unavailable))
        assertEquals(EncryptionPlan.ENCRYPT, encryptionPlan(pendingOn, plain, unavailable))
        assertEquals(EncryptionPlan.OPEN_PLAIN, encryptionPlan(off, absent, unavailable))
        assertEquals(EncryptionPlan.OPEN_CIPHERED, encryptionPlan(on, absent, unavailable))
    }

    /** The Keystore's three answers, read off what the unwrapping returned or threw. */
    @Test
    fun theKeystoresAnswerIsReadOffTheResult() {
        assertEquals(KeyAccess.UNWRAPS, Result.success<ByteArray?>(ByteArray(67)).access())
        assertEquals(KeyAccess.NONE, Result.success<ByteArray?>(null).access())
        assertEquals(KeyAccess.UNAVAILABLE, Result.failure<ByteArray?>(KeyStoreException("not ready")).access())
    }

    /**
     * GCM's tag failing is the key saying "not mine"; anything else the Keystore throws is the
     * Keystore not saying — the exceptions it is known to produce right after boot among them.
     */
    @Test
    fun aWrongKeyIsAVerdictAndAnUnansweredKeystoreIsNot() {
        assertTrue(blobDoesNotFit(AEADBadTagException("tag mismatch")))
        assertFalse(blobDoesNotFit(KeyStoreException("keystore not initialized")))
        assertFalse(blobDoesNotFit(ProviderException("Keystore operation failed")))
        assertFalse(blobDoesNotFit(UnrecoverableKeyException("Failed to obtain information about key")))
        assertFalse(blobDoesNotFit(IllegalStateException("not initialized")))
    }

    /** Moving the switch writes a wish; moving it back before the restart withdraws the wish. */
    @Test
    fun theSwitchWritesAWishAndCanTakeItBack() {
        assertEquals(EncryptionState.PENDING_ON, requestedState(EncryptionState.OFF, wanted = true))
        assertEquals(EncryptionState.OFF, requestedState(EncryptionState.PENDING_ON, wanted = false))
        assertEquals(EncryptionState.PENDING_OFF, requestedState(EncryptionState.ON, wanted = false))
        assertEquals(EncryptionState.ON, requestedState(EncryptionState.PENDING_OFF, wanted = true))
    }

    @Test
    fun askingForWhatIsAlreadyThereChangesNothing() {
        assertEquals(EncryptionState.OFF, requestedState(EncryptionState.OFF, wanted = false))
        assertEquals(EncryptionState.ON, requestedState(EncryptionState.ON, wanted = true))
        assertEquals(EncryptionState.PENDING_ON, requestedState(EncryptionState.PENDING_ON, wanted = true))
        assertEquals(EncryptionState.PENDING_OFF, requestedState(EncryptionState.PENDING_OFF, wanted = false))
    }

    /** The switch reads as on for both the file that is encrypted and the one about to be. */
    @Test
    fun theSwitchReadsTheWishAsWellAsTheFact() {
        assertTrue(EncryptionState.ON.wantsCipher)
        assertTrue(EncryptionState.PENDING_ON.wantsCipher)
        assertFalse(EncryptionState.OFF.wantsCipher)
        assertFalse(EncryptionState.PENDING_OFF.wantsCipher)
        assertTrue(EncryptionState.PENDING_ON.pending)
        assertTrue(EncryptionState.PENDING_OFF.pending)
        assertFalse(EncryptionState.ON.pending)
        assertFalse(EncryptionState.OFF.pending)
    }

    /** SQLite's own sixteen bytes say plain; an encrypted file begins with its random salt. */
    @Test
    fun theHeaderTellsAPlainFileFromACipheredOne() {
        assertTrue(isPlainSqliteHeader(sqliteHeader + ByteArray(84)))
        assertFalse(isPlainSqliteHeader(ByteArray(100) { (it * 37).toByte() }))
        assertFalse(isPlainSqliteHeader(sqliteHeader.copyOf(15)))
        assertFalse(isPlainSqliteHeader("SQLite format 3!".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun theFileHeaderIsReadOffTheDisk() {
        val db = folder.root.resolve("steps.db")
        assertEquals(FileHeader.ABSENT, fileHeader(db))
        db.writeBytes(ByteArray(0))
        assertEquals(FileHeader.ABSENT, fileHeader(db))
        db.writeBytes(sqliteHeader + ByteArray(4080))
        assertEquals(FileHeader.PLAIN, fileHeader(db))
        db.writeBytes(ByteArray(4096) { (it * 91 + 7).toByte() })
        assertEquals(FileHeader.CIPHERED, fileHeader(db))
    }

    /** A database is three files once written to; the log holds writes the main file does not. */
    @Test
    fun aDatabaseMovesWithItsLogAndIndex() {
        val db = folder.newFile("steps.db").apply { writeText("rows") }
        folder.newFile("steps.db-wal").writeText("writes not yet merged")
        folder.newFile("steps.db-shm")

        assertTrue(moveDatabaseFiles(db.path, "${db.path}.locked"))

        assertFalse(db.exists())
        assertEquals("rows", folder.root.resolve("steps.db.locked").readText())
        assertEquals("writes not yet merged", folder.root.resolve("steps.db.locked-wal").readText())
        assertTrue(folder.root.resolve("steps.db.locked-shm").exists())
    }

    /**
     * A second file no key here opens, with the first still waiting: it is numbered beside the
     * first, never renamed over it. And forgetting takes every piece of every one of them.
     */
    @Test
    fun aLockedFileNeverCoversAnEarlierOne() {
        val db = folder.root.resolve("steps.db")
        assertEquals("${db.path}.locked", freeName("${db.path}.locked"))
        folder.newFile("steps.db.locked").writeText("the first transfer's")
        folder.newFile("steps.db.locked-wal")
        assertEquals("${db.path}.locked.2", freeName("${db.path}.locked"))
        folder.newFile("steps.db.locked.2")
        assertEquals("${db.path}.locked.3", freeName("${db.path}.locked"))
        folder.newFile("steps.db.corrupt")

        assertEquals(
            setOf("steps.db.locked", "steps.db.locked-wal", "steps.db.locked.2"),
            setAsideCopies(db, SetAside.LOCKED).map { it.name }.toSet(),
        )
        assertEquals("the first transfer's", folder.root.resolve("steps.db.locked").readText())
    }

    /** The process died after the original went out to its .bak and before the copy came in. */
    @Test
    fun aSwapCutShortPutsTheOriginalBack() {
        val db = folder.root.resolve("steps.db")
        folder.newFile("steps.db.pre-encrypt.bak").writeText("the record")
        folder.newFile("steps.db.pre-encrypt.bak-wal").writeText("its log")
        folder.newFile("steps.db.converting").writeText("half a copy")

        recoverInterruptedSwap(db)

        assertEquals("the record", db.readText())
        assertEquals("its log", folder.root.resolve("steps.db-wal").readText())
        assertFalse(folder.root.resolve("steps.db.pre-encrypt.bak").exists())
        assertFalse(folder.root.resolve("steps.db.converting").exists())
    }

    /** The other direction's .bak comes back the same way. */
    @Test
    fun aDecryptionCutShortPutsTheOriginalBack() {
        val db = folder.root.resolve("steps.db")
        folder.newFile("steps.db.pre-decrypt.bak").writeText("the ciphered record")

        recoverInterruptedSwap(db)

        assertEquals("the ciphered record", db.readText())
    }

    /**
     * The process died after both renames: the file is the converted one and the .bak is the old
     * one. Nothing is put back — the header check takes it from here — but a copy that never
     * finished is still swept up.
     */
    @Test
    fun aCompletedSwapIsLeftAlone() {
        val db = folder.newFile("steps.db").apply { writeText("converted") }
        folder.newFile("steps.db.pre-encrypt.bak").writeText("original")
        folder.newFile("steps.db.converting").writeText("stale")

        recoverInterruptedSwap(db)

        assertEquals("converted", db.readText())
        assertEquals("original", folder.root.resolve("steps.db.pre-encrypt.bak").readText())
        assertFalse(folder.root.resolve("steps.db.converting").exists())
    }

    /** The first open under Room is what lets the .bak go, log and index included. */
    @Test
    fun aSuccessfulOpenLetsTheKeptOriginalGo() {
        val db = folder.newFile("steps.db")
        folder.newFile("steps.db.pre-encrypt.bak")
        folder.newFile("steps.db.pre-encrypt.bak-wal")
        folder.newFile("steps.db.pre-decrypt.bak")

        DatabaseEncryption.opened(db.path)

        assertTrue(db.exists())
        assertFalse(File("${db.path}.pre-encrypt.bak").exists())
        assertFalse(File("${db.path}.pre-encrypt.bak-wal").exists())
        assertFalse(File("${db.path}.pre-decrypt.bak").exists())
    }

    /**
     * The copy this start made was quarantined as corrupt: the kept original is the only whole
     * record, and the fresh file's first open must not be the one that sweeps it. It moves beside
     * the quarantined copy, where [DatabaseEncryption.opened] does not look.
     */
    @Test
    fun theKeptOriginalOfAQuarantinedCopyIsNotSwept() {
        val db = folder.root.resolve("steps.db")
        folder.newFile("steps.db.pre-encrypt.bak").writeText("the only plaintext")
        folder.newFile("steps.db.pre-encrypt.bak-wal").writeText("its log")

        DatabaseEncryption.keepOriginalBesideCorrupt(db.path, "${db.path}.corrupt")
        DatabaseEncryption.opened(db.path)

        assertFalse(folder.root.resolve("steps.db.pre-encrypt.bak").exists())
        assertEquals("the only plaintext", folder.root.resolve("steps.db.corrupt.pre-encrypt.bak").readText())
        assertEquals("its log", folder.root.resolve("steps.db.corrupt.pre-encrypt.bak-wal").readText())
    }

    /** With no .bak beside it, an ordinary corruption has nothing to keep and touches nothing. */
    @Test
    fun anOrdinaryCorruptionHasNoOriginalToKeep() {
        val db = folder.newFile("steps.db")
        DatabaseEncryption.keepOriginalBesideCorrupt(db.path, "${db.path}.corrupt")
        assertEquals(listOf("steps.db"), folder.root.list()?.toList())
    }

    /** The nonce goes first, its length being fixed; both halves come back exactly. */
    @Test
    fun theWrappedKeyPacksAndUnpacks() {
        val iv = ByteArray(12) { it.toByte() }
        val sealed = ByteArray(48) { (it * 3).toByte() }

        val (ivBack, sealedBack) = requireNotNull(unpackWrapped(packWrapped(iv, sealed)))

        assertArrayEquals(iv, ivBack)
        assertArrayEquals(sealed, sealedBack)
    }

    /** A blob that is not base64, or too short to hold a nonce and a tag, sealed nothing. */
    @Test
    fun aBlobThatSealedNothingUnpacksToNothing() {
        assertNull(unpackWrapped("not base64!"))
        assertNull(unpackWrapped(packWrapped(ByteArray(12), ByteArray(16))))
        assertNull(unpackWrapped(""))
    }

    /** SQLCipher's raw-key spelling: x, a quote, 64 hex digits, a quote — 67 ASCII bytes for 32. */
    @Test
    fun theRawKeyIsSpelledTheWaySqlCipherReadsIt() {
        val key = ByteArray(32) {
            when (it) {
                0 -> 0x0a
                31 -> 0xff.toByte()
                else -> 0
            }
        }
        val spelled = rawKeyPassphrase(key).toString(Charsets.US_ASCII)
        assertEquals(67, spelled.length)
        assertEquals("x'0a" + "00".repeat(30) + "ff'", spelled)
    }

    @Test
    fun theKeystoreIsRefusedTwiceAndTheFileSetAsideOnTheThirdSilence() {
        assertFalse(refusalLocksAside(0))
        assertFalse(refusalLocksAside(1))
        assertTrue(refusalLocksAside(2))
        assertEquals(3, MAX_KEYSTORE_REFUSALS)
    }

    /**
     * One silence of the Keystore is one refused start, however often it is heard. StepsDb.get
     * keeps nothing of an open that threw, so onResume asks again a moment after onCreate did, and
     * a process the refusal killed while the phone was booting is back within seconds; were every
     * ask counted, the three starts would be spent in one launch and a healthy record set aside.
     */
    @Test
    fun aSilenceHeardAgainIsNotAnotherRefusedStart() {
        val now = 1_800_000_000_000L
        val interval = KEYSTORE_REFUSAL_INTERVAL_MS

        assertTrue("the first refusal there has been", refusalCounts(false, 0L, now))
        assertFalse("a second caller in the same process", refusalCounts(true, now, now))
        assertFalse("the same process an hour later", refusalCounts(true, now - 60 * interval, now))
        assertFalse("a process restarted five seconds on", refusalCounts(false, now - 5_000L, now))
        assertFalse(refusalCounts(false, now - interval + 1, now))
        assertTrue("another start, a minute on", refusalCounts(false, now - interval, now))
    }

    /**
     * The time of the refusal counted last is wall-clock time read back out of the preferences. A
     * clock corrected by a few seconds is the same silence still; a stamp far ahead of the clock —
     * one set back since, a restored or hand-edited file — must not be waited for, or a key dead
     * for good would keep the app from starting until the clock had caught up with it.
     */
    @Test
    fun aRefusalStampTheClockCannotVouchForIsNotWaitedOut() {
        val now = 1_800_000_000_000L

        assertFalse("the clock stepped back five seconds", refusalCounts(false, now + 5_000L, now))
        assertTrue("the clock set back a day", refusalCounts(false, now + 86_400_000L, now))
        assertTrue(refusalCounts(false, Long.MAX_VALUE, now))
        assertTrue(refusalCounts(false, Long.MIN_VALUE, now))
    }

    @Test
    fun aDiskThatFailsWhileOpeningIsNotAVerdictOnTheFile() {
        assertFalse(sourceUnreadable(java.io.IOException("disk full")))
        assertFalse(sourceUnreadable(IllegalStateException("database is locked")))
    }
}
