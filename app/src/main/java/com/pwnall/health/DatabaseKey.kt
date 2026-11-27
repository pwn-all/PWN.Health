package com.pwnall.health

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key `steps.db` is encrypted under, and where it is kept.
 *
 * The secret SQLCipher is handed is 32 random bytes made once on this phone. They cannot sit beside
 * the file they protect — a copy of the storage would carry both — so they are wrapped by an AES-GCM
 * key in the Android Keystore, which keeps it and never gives it out, and only the wrapped blob is
 * written to the preferences. Nobody is asked for anything on the way in: the app lock is a separate
 * switch, and a database that needed a fingerprint to open would stop the step service writing at
 * three in the morning.
 *
 * What that buys is a copy of the storage that cannot be read away from the phone, and no more, and
 * the switch's text and §12 of the privacy policy must not promise more. The Keystore key is bound
 * to no screen lock and no unlock ([keyOrCreate]), so software running as root on the phone itself
 * can ask for it the way the app does, and the 32 bytes are in this process's memory for as long as
 * the database is open. Nor is "hardware" claimed anywhere: no StrongBox is asked for and no key is
 * checked for being hardware-backed, so on a phone whose Keystore is software the key is too.
 *
 * The same fact cuts the other way, and it is the reason the switch is off by default: the Keystore
 * key exists on no other phone, so the blob a device-to-device transfer copies across unwraps to
 * nothing there, and [passphraseOrNull] says so with null. [DatabaseEncryption] then moves the
 * unreadable file aside instead of treating it as corrupt.
 *
 * Two kinds of failure come out of the Keystore, and they call for opposite answers, so
 * [passphraseOrNull] keeps them apart. A blob the key here does not open — no key under the alias,
 * or a tag that does not check — is another phone's, and null is the verdict. A Keystore that would
 * not answer at all — a `KeyStoreException` or a `ProviderException`, which the system is known to
 * produce in the first seconds after boot, when the step service the boot receiver started is the
 * first thing in the process to ask — is no verdict on the blob, and it is thrown as
 * [KeystoreUnavailableException] rather than folded into null. Folded, it would have a healthy
 * database set aside as another phone's over a hiccup, and a fresh file opened in its place for the
 * service to start writing into: the one path on which the record forks with nothing to bring back.
 *
 * The 32 bytes go to SQLCipher spelled as its raw-key form — `x'…'` around 64 hex digits — rather
 * than as a passphrase. A passphrase is run through PBKDF2 on every connection Room opens, a quarter
 * of a second each on a phone, for no gain when the input is already random; and spelled out in
 * ASCII the same text binds cleanly as the `KEY` of an `ATTACH` during conversion.
 */
internal object DatabaseKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pwnfit-db"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_WRAPPED = "wrappedKey"
    private const val KEY_BYTES = 32
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    /**
     * Whether a wrapped key was ever written here — this phone's or another's.
     *
     * Nothing removes it, and that is on purpose. With the switch turned off again it still opens the
     * copies kept under it, a record set aside ([SetAside.OWN]) and the original a conversion keeps
     * until the copy has opened. Another phone's is written over by [ensurePassphrase] the next time a
     * key is made, and only then: removed along with the locked copy it came with, it could by that
     * time be this phone's own key to the record in use. What it costs a start over a plain file is
     * the database opened off the main thread: [DatabaseEncryption.reconcile] wakes the Keystore only
     * where its answer decides something.
     */
    fun exists(context: Context): Boolean = dbPrefs(context).contains(KEY_WRAPPED)

    /**
     * The passphrase to open the database with, or null when there is none this phone can unwrap:
     * nothing stored, a blob another phone's key sealed, or a Keystore holding no key under the
     * alias. Throws [KeystoreUnavailableException] when the Keystore would not say either way.
     */
    fun passphraseOrNull(context: Context): ByteArray? {
        val (iv, sealed) = dbPrefs(context).getString(KEY_WRAPPED, null)?.let(::unpackWrapped) ?: return null
        val key = runCatching { existingKey() }.getOrElse { throw KeystoreUnavailableException(it) } ?: return null
        val raw = runCatching {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(sealed)
            }
        }.getOrElse { failure ->
            if (!blobDoesNotFit(failure)) throw KeystoreUnavailableException(failure)
            null
        }
        return raw?.takeIf { it.size == KEY_BYTES }?.let(::rawKeyPassphrase)
    }

    /**
     * The passphrase, made and wrapped now if this phone has none it can open. A blob that does not
     * unwrap — one carried over from another phone — is written over, there being no reading it.
     * Whatever the Keystore throws on the way comes out of here; the caller decides what a wish
     * that cannot be granted becomes.
     */
    fun ensurePassphrase(context: Context): ByteArray {
        passphraseOrNull(context)?.let { return it }
        val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyOrCreate()) }
        val sealed = cipher.doFinal(raw)
        // Committed and checked before the key is handed out: a passphrase whose wrapped copy never
        // reached the disk would seal a file no later start could open.
        val stored = dbPrefs(context).edit().putString(KEY_WRAPPED, packWrapped(cipher.iv, sealed)).commit()
        check(stored) { "the wrapped key could not be stored" }
        return rawKeyPassphrase(raw)
    }

    /**
     * The key under the alias, or null when the Keystore has none. Asked through getKey and never
     * through containsAlias: on Keystore2 — Android 12 and later, every phone this app runs on —
     * containsAlias swallows every failure but "not found" and answers false, which would read a
     * Keystore that is down as a key that is not here. getKey returns null for exactly the missing
     * key and throws for everything else, and that distinction is what [passphraseOrNull] rests on.
     */
    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    /**
     * No authentication bound to the key, on purpose: it is used by the step service in the
     * background and by the widgets from the launcher, neither of which has a person in front of it.
     */
    private fun keyOrCreate(): SecretKey = existingKey()
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            generateKey()
        }
}

/**
 * The Keystore did not answer, so nothing here says whether the wrapped key is this phone's. Out of
 * [StepsDb.get] this means the start fails and the next one asks again — the one outcome that leaves
 * an encrypted record exactly where it is. The message names the Keystore's own failure for whoever
 * reads a stack trace; the switch says it in the phone's language ([EncryptionFailure.KEYSTORE]).
 */
internal class KeystoreUnavailableException(cause: Throwable) : IllegalStateException(
    "the key store did not answer: ${cause.javaClass.simpleName}${cause.message?.let { " — $it" }.orEmpty()}",
    cause,
)

/**
 * Whether [failure], thrown while unwrapping, is a verdict on the blob — the key here is not the
 * one that sealed it, or that key is gone for good — as opposed to a Keystore that could not be
 * asked just now. GCM's tag check failing is the whole of the first kind; everything else is the
 * second, and is handled as a hiccup rather than as a fact about the file.
 */
internal fun blobDoesNotFit(failure: Throwable): Boolean =
    failure is AEADBadTagException || failure is KeyPermanentlyInvalidatedException

/** The one preferences file the database's own settings live in: the switch, the wrapped key, the last failure. */
internal fun dbPrefs(context: Context): SharedPreferences = context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)

private const val DB_PREFS = "db"

/** GCM's nonce, the length the Keystore produces. */
private const val IV_BYTES = 12

/** GCM's tag; a blob shorter than nonce and tag together sealed nothing. */
private const val GCM_TAG_BYTES = 16

/** SQLCipher's raw-key spelling of [key] — `x'` + hex + `'` — as the ASCII bytes SQLCipher takes. */
internal fun rawKeyPassphrase(key: ByteArray): ByteArray =
    key.joinToString("", prefix = "x'", postfix = "'") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)

/** The nonce and the sealed bytes as one base64 string, the nonce first since its length is fixed. */
internal fun packWrapped(iv: ByteArray, sealed: ByteArray): String = Base64.getEncoder().encodeToString(iv + sealed)

/** The two halves back, or null for anything that is not base64 holding at least a nonce and a tag. */
internal fun unpackWrapped(blob: String): Pair<ByteArray, ByteArray>? {
    val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
    if (bytes.size <= IV_BYTES + GCM_TAG_BYTES) return null
    return bytes.copyOfRange(0, IV_BYTES) to bytes.copyOfRange(IV_BYTES, bytes.size)
}
