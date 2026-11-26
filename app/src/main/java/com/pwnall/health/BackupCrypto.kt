package com.pwnall.health

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup under a password: the JSON that [LocalBackup] writes, sealed a mebibyte at a time.
 *
 * The file opens with the ASCII `PWNFITENC`, one version byte (1), a 16-byte random salt, the PBKDF2
 * iteration count as a big-endian int and an 8-byte random nonce prefix — 38 bytes in all. Chunks
 * follow: a big-endian int holding the plaintext length, then AES-256-GCM output over that many
 * bytes, 16 bytes longer for its tag. Chunk `i` is sealed under the 12-byte nonce `prefix ++ i` with
 * `i ++ flag` as additional data, where `flag` is one byte, 1 on the last chunk and 0 before it.
 * Every chunk but the last holds exactly [CHUNK_BYTES], so the reader takes the first shorter one as
 * the last — an empty one, when the plaintext ended on a boundary — and the flag under the tag holds
 * it to that reading.
 *
 * Chunks rather than one GCM call because a year of tracks is tens of megabytes, and both providers
 * this app meets hold a GCM message whole until its tag has checked out: one message would put the
 * entire file on the heap on the way in. The counter in the nonce and the flag in the tag pin each
 * chunk to its place, so a file with a chunk dropped, repeated or swapped in from another backup
 * fails its tag instead of reading as a shorter, different history, and a file that stops before the
 * flagged chunk is reported truncated rather than imported as far as it goes.
 *
 * The header is not in the additional data and need not be: the salt and the rounds make the key and
 * the prefix makes the nonces, so a header with any of them changed fails the first tag, and a version
 * byte other than 1 is refused before a key is derived. A second layout that reads chunks differently
 * under the same key would have to put its version byte in the additional data, or a file of one could
 * be read as the other.
 *
 * The key is PBKDF2-HMAC-SHA256 over the password's UTF-8 in Unicode's composed form, NFC. The
 * iteration count travels in the header so a later build can raise [ITERATIONS] and still open every
 * file written before it; it is capped on the way in, since a forged header could otherwise hold the
 * phone in the derivation for minutes.
 * PBKDF2 and not Argon2id or scrypt, which resist a graphics card better: neither is in the
 * platform's providers, and a library brought in to seal one file is more to trust than it saves.
 */
internal object BackupCrypto {
    /** The nine tag bytes and the version byte. A caller peeking at a file needs this many. */
    const val MAGIC_LENGTH = 10

    /** Plaintext per chunk. The reader and the writer each hold about two of these. */
    const val CHUNK_BYTES = 1024 * 1024

    /**
     * PBKDF2 rounds for files written by this build: OWASP's figure for PBKDF2-HMAC-SHA256, a wait
     * a person exporting a backup sits through once and a person guessing passwords at every guess.
     * The file is made to leave the phone, so guessing offline is the one attack there is, and these
     * rounds and the password's length are all that prices it. A file sealed under fewer rounds says
     * so in its header and still opens.
     */
    const val ITERATIONS = 600_000

    /**
     * The shortest password the export is sealed under. One character under any number of rounds is a
     * file that says protected and is not.
     */
    const val MIN_PASSWORD_LENGTH = 8

    private val random = SecureRandom()

    /**
     * Whether [head], the first bytes of a file, opens an encrypted backup.
     *
     * Only the tag is consulted, not the version byte after it: a file from a later layout is still
     * an encrypted backup, and [decrypting] says so with [UnsupportedBackupVersionException] rather
     * than leaving the JSON reader to choke on ciphertext.
     */
    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * A stream that seals whatever is written to it into [sink]. The header goes out at once and the
     * flagged last chunk on close, which closes [sink] as well.
     *
     * The password is copied for the derivation and the copy wiped afterwards; the caller's array is
     * left as it was, since the same array is usually about to confirm the export.
     */
    fun encrypting(password: CharArray, sink: OutputStream): OutputStream {
        requirePassword(password)
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        sink.write(MAGIC + FORMAT_VERSION + salt + bigEndian(ITERATIONS) + noncePrefix)
        return EncryptingStream(key, noncePrefix, sink)
    }

    /**
     * A stream that serves the plaintext out of [source]. The header is read and the key derived
     * before this returns, so a wrong password surfaces at the first byte asked for — which the
     * import takes before it applies anything — instead of somewhere in the middle of a transaction.
     * Closing the stream closes [source]; until then [source] stays the caller's.
     */
    fun decrypting(password: CharArray, source: InputStream): InputStream {
        requirePassword(password)
        val header = readHeader(source)
        val key = deriveKey(password, header.salt, header.iterations)
        return DecryptingStream(key, header.noncePrefix, source)
    }
}

/**
 * A tag that did not check out. GCM cannot tell a wrong password from a changed file, and the app
 * should not pretend to: either way, this file does not open with this password.
 */
class WrongBackupPasswordException(cause: Throwable? = null) : IllegalArgumentException(cause)

/**
 * A file that stops before its end: the copy or the download that brought it was cut short. A type
 * of its own so that the import can say so in the reader's language ([importFailureRes]).
 */
class TruncatedBackupException : IllegalStateException("truncated backup")

/**
 * Whether the export may be sealed under [password]: long enough to be worth the rounds spent on
 * it, and typed the same twice. Opening a file asks for no length — one sealed under a shorter
 * password by an earlier build must still open.
 */
internal fun exportPasswordReady(password: String, repeat: String): Boolean =
    password.isNotBlank() && password.length >= BackupCrypto.MIN_PASSWORD_LENGTH && password == repeat

/** Whether [password] has been begun and is still too short to seal with, which the field says under itself. */
internal fun exportPasswordTooShort(password: String): Boolean =
    password.isNotEmpty() && password.length < BackupCrypto.MIN_PASSWORD_LENGTH

/** The tag [BackupCrypto.isEncrypted] looks for. The version byte follows it. */
private val MAGIC = "PWNFITENC".toByteArray(Charsets.US_ASCII)

/** The one layout this build writes and reads; a header carrying another is refused as too new. */
private const val FORMAT_VERSION: Byte = 1

private const val SALT_BYTES = 16
private const val NONCE_PREFIX_BYTES = 8
private const val INT_BYTES = 4
private const val KEY_BITS = 256
private const val TAG_BITS = 128
private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS

/** The most a header may ask for: enough to outlive [BackupCrypto.ITERATIONS] being raised many times. */
private const val MAX_ITERATIONS = 5_000_000

private const val KDF = "PBKDF2WithHmacSHA256"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val LAST_CHUNK: Byte = 1
private const val MORE_CHUNKS: Byte = 0
private const val BYTE_MASK = 0xFF

/** Blank is refused at the door: a file sealed under nothing would look protected and be nothing of the kind. */
private fun requirePassword(password: CharArray) {
    require(password.any { !it.isWhitespace() }) { "blank password" }
}

/**
 * PBKDF2 over the password's UTF-8, which is what both the JVM's and Android's providers feed it, so
 * a file sealed on the phone opens on the test JVM and the other way round.
 *
 * Composed first (Unicode's NFC): an `é` is one character from one keyboard and an `e` with an accent
 * after it from another, the same password to the person typing it and different bytes to PBKDF2, and
 * a file sealed on one phone would answer "wrong password" on the next. The composed characters are wiped
 * as the caller's are; the String the normalizer hands them over in cannot be, and the text field the
 * password was typed into holds one regardless.
 */
private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
    val composed = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toCharArray()
    val spec = PBEKeySpec(composed, salt, iterations, KEY_BITS)
    composed.fill(Char(0))
    val bytes = try {
        SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
    return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
}

private fun bigEndian(value: Int): ByteArray = ByteBuffer.allocate(INT_BYTES).putInt(value).array()

private fun bigEndianInt(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).int

/** What the header carries that the reader needs; the version byte has been checked by then. */
private class Header(val salt: ByteArray, val iterations: Int, val noncePrefix: ByteArray)

/**
 * Everything before the first chunk. A plain file is refused as one, a later layout as too new, and
 * a header that stops early or asks for an absurd number of rounds as damaged — none of them ever
 * reaches the key derivation.
 */
private fun readHeader(source: InputStream): Header {
    val magic = source.readExactly(BackupCrypto.MAGIC_LENGTH)
    require(BackupCrypto.isEncrypted(magic)) { "not an encrypted backup" }
    if (magic[MAGIC.size] != FORMAT_VERSION) throw UnsupportedBackupVersionException()
    val salt = source.readExactly(SALT_BYTES)
    val iterations = bigEndianInt(source.readExactly(INT_BYTES))
    check(iterations in 1..MAX_ITERATIONS) { "corrupt backup" }
    val noncePrefix = source.readExactly(NONCE_PREFIX_BYTES)
    return Header(salt, iterations, noncePrefix)
}

private fun InputStream.readExactly(count: Int): ByteArray = ByteArray(count).also { readExactly(it, count) }

/**
 * [count] bytes into [into], or the truncation error. A header or a chunk that stops early is a file
 * that stops early, and the error names it so the caller can say so instead of "wrong password".
 */
private fun InputStream.readExactly(into: ByteArray, count: Int) {
    var filled = 0
    while (filled < count) {
        val got = read(into, filled, count - filled)
        if (got < 0) throw TruncatedBackupException()
        filled += got
    }
}

/**
 * One cipher, re-initialised for every chunk with the nonce and additional data that name its place:
 * the running index in both, and in the data the flag that says whether anything follows.
 */
private class ChunkCipher(private val key: SecretKey, private val noncePrefix: ByteArray) {
    private val cipher = Cipher.getInstance(TRANSFORMATION)

    /** Runs [length] bytes of [input] through the cipher into [output] and returns how many came out. */
    fun run(mode: Int, index: Int, last: Boolean, input: ByteArray, length: Int, output: ByteArray): Int {
        val counter = bigEndian(index)
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, noncePrefix + counter))
        cipher.updateAAD(counter + (if (last) LAST_CHUNK else MORE_CHUNKS))
        return cipher.doFinal(input, 0, length, output, 0)
    }
}

/**
 * Seals what it is given a chunk at a time. Nothing reaches the sink until a chunk is full or the
 * stream is closed, so a writer that dies halfway leaves a file the reader refuses as truncated,
 * never one that opens short.
 */
private class EncryptingStream(
    key: SecretKey,
    noncePrefix: ByteArray,
    private val sink: OutputStream,
) : OutputStream() {
    private val cipher = ChunkCipher(key, noncePrefix)
    private val plain = ByteArray(BackupCrypto.CHUNK_BYTES)
    private val sealed = ByteArray(BackupCrypto.CHUNK_BYTES + TAG_BYTES)
    private var filled = 0
    private var index = 0
    private var closed = false

    override fun write(b: Int) {
        check(!closed) { "stream closed" }
        plain[filled] = b.toByte()
        filled += 1
        if (filled == BackupCrypto.CHUNK_BYTES) seal(last = false)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "stream closed" }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val take = minOf(remaining, BackupCrypto.CHUNK_BYTES - filled)
            System.arraycopy(b, offset, plain, filled, take)
            filled += take
            offset += take
            remaining -= take
            if (filled == BackupCrypto.CHUNK_BYTES) seal(last = false)
        }
    }

    /** Pushes the chunks already sealed. A partial one waits: sealing it would end the file. */
    override fun flush() = sink.flush()

    /** The flagged last chunk, empty if the plaintext ended on a boundary, and then the sink. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            seal(last = true)
            sink.flush()
        } finally {
            sink.close()
        }
    }

    private fun seal(last: Boolean) {
        val length = cipher.run(Cipher.ENCRYPT_MODE, index, last, plain, filled, sealed)
        sink.write(bigEndian(filled))
        sink.write(sealed, 0, length)
        index += 1
        filled = 0
    }
}

/**
 * Serves the plaintext one opened chunk at a time. GCM releases nothing before the tag checks out,
 * so a byte that comes back from here is one the tag vouched for, and the tag failing anywhere is
 * the one signal the format has for a wrong password.
 */
private class DecryptingStream(
    key: SecretKey,
    noncePrefix: ByteArray,
    private val source: InputStream,
) : InputStream() {
    private val cipher = ChunkCipher(key, noncePrefix)
    private val sealed = ByteArray(BackupCrypto.CHUNK_BYTES + TAG_BYTES)

    // Sized for the ciphertext, not the plaintext it will hold: Android's provider estimates an AEAD
    // decrypt's output at the size of its input, and a smaller buffer costs a temporary copy of the
    // chunk at best and a ShortBufferException at worst.
    private val plain = ByteArray(BackupCrypto.CHUNK_BYTES + TAG_BYTES)
    private var limit = 0
    private var position = 0
    private var index = 0
    private var finished = false

    override fun read(): Int {
        if (!fill()) return -1
        val byte = plain[position].toInt() and BYTE_MASK
        position += 1
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, limit - position)
        System.arraycopy(plain, position, b, off, count)
        position += count
        return count
    }

    override fun close() = source.close()

    /** Opens chunks until a byte is waiting; false once the flagged chunk has been handed out. */
    private fun fill(): Boolean {
        while (position == limit && !finished) openNext()
        return position < limit
    }

    private fun openNext() {
        val declared = bigEndianInt(source.readExactly(INT_BYTES))
        check(declared in 0..BackupCrypto.CHUNK_BYTES) { "corrupt backup" }
        val last = declared < BackupCrypto.CHUNK_BYTES
        source.readExactly(sealed, declared + TAG_BYTES)
        limit = try {
            cipher.run(Cipher.DECRYPT_MODE, index, last, sealed, declared + TAG_BYTES, plain)
        } catch (e: BadPaddingException) {
            // AEADBadTagException is the subclass both providers actually throw.
            throw WrongBackupPasswordException(e)
        }
        require(!last || source.read() == -1) { "trailing data in backup" }
        position = 0
        index += 1
        finished = last
    }
}
