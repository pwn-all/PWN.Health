package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The shapes the key takes on its way between the Keystore, the preferences and SQLCipher. The
 * Keystore itself is the phone's: DatabaseEncryptionDeviceTest, in app/src/androidTest, makes a key
 * and has it unwrapped again on a device, and nothing does in `check`. What is here is the two
 * encodings on either side of it, which a byte out of place in would make every database on every
 * phone unreadable at the next start.
 */
class DatabaseKeyTest {
    /**
     * SQLCipher reads `x'…'` around 64 hex digits as the key itself, bypassing the derivation a
     * passphrase would go through on every connection. The spelling has to be exact: one character
     * off and the same bytes are a passphrase, and the file opens under a different key.
     */
    @Test
    fun theKeyIsSpelledTheWaySqlcipherReadsARawOne() {
        val key = ByteArray(32) { it.toByte() }

        val spelled = rawKeyPassphrase(key).toString(Charsets.US_ASCII)

        assertEquals(2 + 64 + 1, spelled.length)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", spelled)
    }

    @Test
    fun highBytesAreSpelledAsTwoDigitsToo() {
        assertEquals("x'ff80'", rawKeyPassphrase(byteArrayOf(-1, -128)).toString(Charsets.US_ASCII))
    }

    /** The nonce goes first because its length is fixed; the sealed bytes take whatever is left. */
    @Test
    fun theWrappedBlobComesBackInItsTwoHalves() {
        val iv = ByteArray(12) { (it + 1).toByte() }
        val sealed = ByteArray(48) { (200 - it).toByte() }

        val unpacked = unpackWrapped(packWrapped(iv, sealed))

        assertNotNull(unpacked)
        assertArrayEquals(iv, unpacked!!.first)
        assertArrayEquals(sealed, unpacked.second)
    }

    /** Anything that is not base64 of at least a nonce and a tag is not a key, and says so with null. */
    @Test
    fun aBlobThatCannotHoldAKeyIsRefused() {
        assertNull(unpackWrapped("not base64 at all!"))
        assertNull(unpackWrapped(""))
        assertNull(unpackWrapped(packWrapped(ByteArray(12), ByteArray(16))))
        assertNotNull(unpackWrapped(packWrapped(ByteArray(12), ByteArray(17))))
    }

    /**
     * How a file is encrypted — the page size, the key derivation, the HMAC — is the defaults of the
     * SQLCipher that wrote it, and nothing in the app pins them. A major release that changes them, as
     * 4 did after 3, reads a healthy file of the last one as no database, and the quarantine moves it
     * aside as damaged. So the library stays on the major release the files are written in: a bump
     * past it has to bring `PRAGMA cipher_compatibility` in the open hook, or `cipher_migrate`, with
     * a device test that opens a file the old one wrote — and moves this line with them.
     */
    @Test
    fun sqlcipherStaysOnTheMajorReleaseItsFilesWereWrittenIn() {
        val script = File("build.gradle.kts").readText()
        val major = Regex(""""net\.zetetic:sqlcipher-android:([0-9]+)\.""").find(script)?.groupValues?.get(1)
        assertEquals("4", major)
    }
}
