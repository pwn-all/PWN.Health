package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * What a sealed backup promises: the bytes come back exactly, a wrong password or a touched file is
 * refused rather than half-read, and a file that stops early is reported as stopping early.
 *
 * The costly part of every case is the key derivation, so the two blobs the negative cases pick at
 * are sealed once for the whole class rather than once per test.
 */
class BackupCryptoTest {
    @Test
    fun `a short text comes back byte for byte`() {
        assertArrayEquals(SHORT_TEXT, unseal(SHORT_SEALED, PASSWORD))
    }

    /** The JSON writer wraps the stream in a buffered writer and closes that; this is the real path. */
    @Test
    fun `text written through a buffered writer comes back the same`() {
        val sink = ByteArrayOutputStream()
        BackupCrypto.encrypting(PASSWORD, sink).bufferedWriter().use { it.write(String(SHORT_TEXT)) }

        assertArrayEquals(SHORT_TEXT, unseal(sink.toByteArray(), PASSWORD))
    }

    @Test
    fun `single-byte reads see the same bytes and then the end`() {
        BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(SHORT_SEALED)).use { stream ->
            val bytes = generateSequence { stream.read().takeIf { it >= 0 } }.map(Int::toByte).toList()

            assertArrayEquals(SHORT_TEXT, bytes.toByteArray())
            assertEquals(-1, stream.read())
        }
    }

    /** Reads of 7 and 1 000 bytes in turn never line up with a chunk edge, so every partial path runs. */
    @Test
    fun `three mebibytes cross two chunk boundaries and survive odd-sized reads`() {
        val out = ByteArrayOutputStream(LARGE.size)
        BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(LARGE_SEALED)).use { stream ->
            val buffer = ByteArray(LONG_READ)
            var want = SHORT_READ
            while (true) {
                val got = stream.read(buffer, 0, want)
                if (got < 0) break
                out.write(buffer, 0, got)
                want = if (want == SHORT_READ) LONG_READ else SHORT_READ
            }
        }

        assertArrayEquals(LARGE, out.toByteArray())
    }

    /** A plaintext that ends on a chunk boundary still ends with a chunk that says so, empty. */
    @Test
    fun `a text exactly one chunk long ends with an empty last chunk`() {
        val exact = Random(7).nextBytes(BackupCrypto.CHUNK_BYTES)
        val sealed = seal(exact, PASSWORD)

        assertEquals(HEADER_BYTES + chunkBytes(BackupCrypto.CHUNK_BYTES) + chunkBytes(0), sealed.size)
        assertArrayEquals(exact, unseal(sealed, PASSWORD))
    }

    @Test
    fun `nothing seals to a file that opens to nothing`() {
        val sealed = seal(ByteArray(0), PASSWORD)

        assertEquals(HEADER_BYTES + chunkBytes(0), sealed.size)
        assertArrayEquals(ByteArray(0), unseal(sealed, PASSWORD))
    }

    /** Opening the stream derives the key; the first byte asked for is where a wrong password shows. */
    @Test
    fun `a wrong password is refused at the first read`() {
        val stream = BackupCrypto.decrypting("passwort".toCharArray(), ByteArrayInputStream(SHORT_SEALED))

        assertThrows(WrongBackupPasswordException::class.java) { stream.read() }
    }

    @Test
    fun `one flipped byte in the ciphertext reads as a wrong password`() {
        val touched = SHORT_SEALED.copyOf()
        val middle = HEADER_BYTES + LENGTH_BYTES + SHORT_TEXT.size / 2
        touched[middle] = (touched[middle].toInt() xor 1).toByte()

        assertThrows(WrongBackupPasswordException::class.java) { unseal(touched, PASSWORD) }
    }

    /** The chunk that checked out is served in full; the touched one after it is still refused. */
    @Test
    fun `a flipped byte in a later chunk is refused once the good chunk before it was served`() {
        val touched = LARGE_SEALED.copyOf()
        val middle = touched.size / 2
        touched[middle] = (touched[middle].toInt() xor 1).toByte()
        val served = ByteArrayOutputStream()

        BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(touched)).use { stream ->
            assertThrows(WrongBackupPasswordException::class.java) { stream.copyTo(served) }
        }

        assertEquals(BackupCrypto.CHUNK_BYTES, served.size())
    }

    @Test
    fun `a file cut short is reported truncated instead of read as far as it goes`() {
        val cut = SHORT_SEALED.copyOf(SHORT_SEALED.size - CUT_BYTES)
        val stream = BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(cut))

        val thrown = assertThrows(IllegalStateException::class.java) { stream.readBytes() }

        assertEquals("truncated backup", thrown.message)
    }

    /**
     * The last 20 bytes of a three-chunk file are its empty last chunk. Without them the file ends on
     * a boundary, clean to look at, and the reader must still refuse to call it complete.
     */
    @Test
    fun `a file cut at a chunk boundary is truncated too, after every whole chunk was served`() {
        val cut = LARGE_SEALED.copyOf(LARGE_SEALED.size - chunkBytes(0))
        val served = ByteArrayOutputStream()

        BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(cut)).use { stream ->
            val thrown = assertThrows(IllegalStateException::class.java) { stream.copyTo(served) }
            assertEquals("truncated backup", thrown.message)
        }

        assertArrayEquals(LARGE, served.toByteArray())
    }

    /** Salt and nonce prefix are drawn fresh, so two exports of one database never share a byte stream. */
    @Test
    fun `sealing the same text twice gives different files that both open`() {
        val again = seal(SHORT_TEXT, PASSWORD)

        assertFalse(SHORT_SEALED.contentEquals(again))
        assertArrayEquals(SHORT_TEXT, unseal(SHORT_SEALED, PASSWORD))
        assertArrayEquals(SHORT_TEXT, unseal(again, PASSWORD))
    }

    @Test
    fun `a sealed file is told from a plain one by its first bytes`() {
        val head = SHORT_SEALED.copyOf(BackupCrypto.MAGIC_LENGTH)

        assertArrayEquals("PWNFITENC".toByteArray() + byteArrayOf(1), head)
        assertTrue(BackupCrypto.isEncrypted(head))
        assertTrue(BackupCrypto.isEncrypted(SHORT_SEALED))
        assertFalse(BackupCrypto.isEncrypted("""{"schema":7""".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
    }

    @Test
    fun `a password in Cyrillic and emoji opens what it sealed`() {
        val password = "пароль🔐".toCharArray()

        assertArrayEquals(SHORT_TEXT, unseal(seal(SHORT_TEXT, password), password))
    }

    @Test
    fun `a blank password is refused before anything is written or read`() {
        val sink = ByteArrayOutputStream()

        val sealing = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypting("  ".toCharArray(), sink)
        }
        val opening = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypting(CharArray(0), ByteArrayInputStream(SHORT_SEALED))
        }

        assertEquals("blank password", sealing.message)
        assertEquals("blank password", opening.message)
        assertEquals(0, sink.size())
    }

    @Test
    fun `a plain file handed to the reader is refused as one, not as a wrong password`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream("""{"schema":7,"days":[]}""".toByteArray()))
        }

        assertEquals("not an encrypted backup", thrown.message)
    }

    /** A later build may change the layout behind the same tag; this one must say so rather than guess. */
    @Test
    fun `a version byte this build does not know is refused as too new`() {
        val newer = SHORT_SEALED.copyOf().also { it[BackupCrypto.MAGIC_LENGTH - 1] = 2 }

        assertThrows(UnsupportedBackupVersionException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(newer))
        }
    }

    /** A header asking for two billion rounds would hold the phone for an hour; it is refused unread. */
    @Test
    fun `an absurd iteration count in the header is refused before the key is derived`() {
        val greedy = SHORT_SEALED.copyOf().apply {
            fill(GREEDY_BYTE, ITERATIONS_OFFSET, ITERATIONS_OFFSET + LENGTH_BYTES)
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(greedy))
        }

        assertEquals("corrupt backup", thrown.message)
    }

    /**
     * The reader takes the round count from the file, not from [BackupCrypto.ITERATIONS], so a later
     * build can raise the constant. Seen from outside: a header rewritten to fewer rounds derives a
     * different key, and the file that opened a moment ago no longer does.
     */
    @Test
    fun `the iteration count is taken from the header`() {
        val fewer = SHORT_SEALED.copyOf().also {
            val rounds = ByteArray(LENGTH_BYTES)
            rounds[LENGTH_BYTES - 1] = 1
            rounds.copyInto(it, ITERATIONS_OFFSET)
        }

        assertThrows(WrongBackupPasswordException::class.java) { unseal(fewer, PASSWORD) }
    }

    /**
     * The count written into a new file is the price of one guess at its password, and the file is
     * made to leave the phone. OWASP's figure for PBKDF2-HMAC-SHA256 is the floor: a build that
     * lowers the constant fails here rather than in somebody's leaked backup.
     */
    @Test
    fun `a new file asks for no fewer rounds than OWASP does`() {
        val rounds = ByteBuffer.wrap(SHORT_SEALED, ITERATIONS_OFFSET, LENGTH_BYTES).int

        assertEquals(BackupCrypto.ITERATIONS, rounds)
        assertTrue("$rounds rounds", rounds >= OWASP_PBKDF2_SHA256_ROUNDS)
    }

    /**
     * One password typed on two keyboards: one composes the accent into its letter, the other sends
     * it after the letter. To the person typing they are the same password, and the file opens with
     * either.
     */
    @Test
    fun `a password opens the file whichever way its accents were typed`() {
        val composed = "caf\u00e9 au lait".toCharArray()
        val decomposed = "cafe\u0301 au lait".toCharArray()

        assertArrayEquals(SHORT_TEXT, unseal(seal(SHORT_TEXT, composed), decomposed))
    }
}

private val PASSWORD = "password".toCharArray()
private val SHORT_TEXT = """{"schema":7,"exportedAt":1800000000000,"profile":null,"days":[]}""".toByteArray()
private val SHORT_SEALED = seal(SHORT_TEXT, PASSWORD)

/** Three whole chunks: crosses the boundary twice and ends on the third, so the last chunk is empty. */
private val LARGE = Random(20_260_903).nextBytes(3 * BackupCrypto.CHUNK_BYTES)
private val LARGE_SEALED = seal(LARGE, PASSWORD)

/** The header as BackupCrypto lays it out: tag and version, salt, iterations, nonce prefix. */
private const val HEADER_BYTES = 10 + 16 + 4 + 8
private const val ITERATIONS_OFFSET = 10 + 16
private const val LENGTH_BYTES = 4
private const val TAG_BYTES = 16
private const val CUT_BYTES = 20
private const val SHORT_READ = 7
private const val LONG_READ = 1_000
private const val GREEDY_BYTE: Byte = 0x7F

/** OWASP's Password Storage Cheat Sheet, PBKDF2-HMAC-SHA256, as read in November 2026. */
private const val OWASP_PBKDF2_SHA256_ROUNDS = 600_000

/** What one chunk of [plain] bytes takes up in the file. */
private fun chunkBytes(plain: Int) = LENGTH_BYTES + plain + TAG_BYTES

private fun seal(plain: ByteArray, password: CharArray): ByteArray {
    val sink = ByteArrayOutputStream()
    BackupCrypto.encrypting(password, sink).use { it.write(plain) }
    return sink.toByteArray()
}

private fun unseal(sealed: ByteArray, password: CharArray): ByteArray =
    BackupCrypto.decrypting(password, ByteArrayInputStream(sealed)).use { it.readBytes() }
