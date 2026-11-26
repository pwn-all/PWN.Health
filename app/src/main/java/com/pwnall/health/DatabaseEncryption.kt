package com.pwnall.health

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The database's file name. Room and the conversion here have to agree on it. */
internal const val DATABASE_FILE = "steps.db"

/** The switch's four positions. The two pending ones are a wish written down for the next start. */
internal enum class EncryptionState { OFF, ON, PENDING_ON, PENDING_OFF }

/** Whether the switch reads as on: the file is encrypted, or will be at the next start. */
internal val EncryptionState.wantsCipher: Boolean
    get() = this == EncryptionState.ON || this == EncryptionState.PENDING_ON

/** Whether a restart is what stands between the switch and the file. */
internal val EncryptionState.pending: Boolean
    get() = this == EncryptionState.PENDING_ON || this == EncryptionState.PENDING_OFF

/**
 * What the first sixteen bytes of the file say it is. Anything that is not SQLite's own header is
 * taken as ciphered.
 */
internal enum class FileHeader { ABSENT, PLAIN, CIPHERED }

/**
 * What this phone's Keystore said when asked for the key: it unwrapped; there is none here that
 * does — no blob, or another phone's; or the Keystore would not answer, which is no verdict on the
 * blob at all and must not be read as one.
 */
internal enum class KeyAccess { UNWRAPS, NONE, UNAVAILABLE }

/**
 * [DatabaseKey.passphraseOrNull]'s answer as a [KeyAccess]: null is a key that is not this phone's,
 * a failure a Keystore that would not say.
 */
internal fun Result<ByteArray?>.access(): KeyAccess = when {
    isFailure -> KeyAccess.UNAVAILABLE
    getOrNull() == null -> KeyAccess.NONE
    else -> KeyAccess.UNWRAPS
}

/** How many starts in a row the Keystore may refuse to answer before the file is set aside instead. */
internal const val MAX_KEYSTORE_REFUSALS = 3

/**
 * Whether one more refused start, after [refusalsSoFar], is the one that sets the record aside. The
 * count comes out of the preferences, where a restored or hand-edited file can leave any Int at all,
 * so the limit is reached by subtraction rather than by adding one to the count: `refusalsSoFar + 1`
 * wraps to Int.MIN_VALUE at the top of the range and would answer "keep waiting" for ever, which is
 * an app that never starts again — the one outcome the count of refused starts exists to avoid.
 */
internal fun refusalLocksAside(refusalsSoFar: Int): Boolean = refusalsSoFar >= MAX_KEYSTORE_REFUSALS - 1

/**
 * How far apart two refusals have to be for both to be counted. A Keystore slow to wake after boot
 * is silent for seconds, and in those seconds the app is started over and over: by each boot
 * receiver, by the step service, and by the system bringing back whichever of them the refusal
 * killed. A minute between counted refusals makes three of them two minutes of silence at the least.
 */
internal const val KEYSTORE_REFUSAL_INTERVAL_MS = 60_000L

/**
 * Whether a refusal is a refused start of its own, to be counted against the record, rather than a
 * silence already counted and heard again. [StepsDb.get] keeps nothing of an open that threw, so
 * every later caller in the process asks the Keystore afresh — the activity's onResume a moment
 * after its onCreate, a widget, a receiver that catches the failure and lives on — and counted one
 * by one they would spend the three starts in a single launch. So a process is counted once
 * ([countedThisProcess]), and not at all within [KEYSTORE_REFUSAL_INTERVAL_MS] of the refusal
 * counted last. [lastCountedAtMs] is wall-clock time out of the preferences, and only as far as the
 * interval is it believed: one further off on either side — long ago, or ahead of a clock since set
 * back, or whatever a restored or hand-edited file holds — is counted, because waiting for the
 * clock to catch up with it would be a dead key keeping the app from starting all that while.
 */
internal fun refusalCounts(countedThisProcess: Boolean, lastCountedAtMs: Long, nowMs: Long): Boolean {
    val longAgo = lastCountedAtMs <= nowMs - KEYSTORE_REFUSAL_INTERVAL_MS
    val aheadOfTheClock = lastCountedAtMs >= nowMs + KEYSTORE_REFUSAL_INTERVAL_MS
    return !countedThisProcess && (longAgo || aheadOfTheClock)
}

/**
 * Whether [failure], thrown by opening the original under the key it was believed to be under, is a
 * verdict on the file — no database under that key, or a corrupt one — rather than the disk failing
 * at that moment. Opening a file in WAL mode folds the log back, which is a write, so a full disk or
 * a failing I/O surfaces here too; taking that for a wrong key would hand a healthy encrypted file to
 * the plain helper, which quarantines whatever it cannot read.
 */
internal fun sourceUnreadable(failure: Throwable): Boolean =
    failure is SQLiteNotADatabaseException || failure is SQLiteDatabaseCorruptException

/**
 * Why the last start could not make the change asked of it, as the line under the switch says it:
 * [text] is the clause `db_encrypt_error` ends with. Kept by name rather than as the exception,
 * whose message would print in English whatever language the phone reads, and as often as not with
 * the path of a file in it.
 */
internal enum class EncryptionFailure(@StringRes val text: Int) {
    KEYSTORE(R.string.db_failure_keystore),
    NO_KEY(R.string.db_failure_no_key),
    STORAGE(R.string.db_failure_storage),
    FILES(R.string.db_failure_files),
}

/**
 * The words for [failure]: the Keystore that would not answer or would not make a key, a disk that
 * filled up — a conversion writes a whole second copy of the record beside the first — and anything
 * else the files did. A failure that has a verdict of its own, a copy no key here opens, is named
 * where it is found ([EncryptionFailure.NO_KEY]), not read off an exception.
 */
internal fun encryptionFailureOf(failure: Throwable): EncryptionFailure = when (failure) {
    is KeystoreUnavailableException, is GeneralSecurityException, is ProviderException -> EncryptionFailure.KEYSTORE
    is SQLiteFullException -> EncryptionFailure.STORAGE
    else -> EncryptionFailure.FILES
}

/** What [DatabaseEncryption.reconcile] does before Room is allowed near the file. */
internal enum class EncryptionPlan { OPEN_PLAIN, OPEN_CIPHERED, ENCRYPT, DECRYPT, LOCK_ASIDE, REFUSE }

/**
 * The decision, from what the switch asks, what the file is and what the Keystore said about the
 * key. The file's header outranks the switch: a switch left at PENDING_ON by a process that died
 * between the swap and the note of it is looking at an encrypted file already, and converting again
 * would fail on it. A ciphered file with no key that fits is nobody's to convert, whatever the
 * switch says, and is moved aside instead — but only on the Keystore's word that the key is not
 * here. A Keystore that would not answer is refused: nothing is opened, the start fails, and the
 * next one asks again. The difference is the whole record — set aside on a hiccup, it would be
 * forked by the fresh file the step service began writing into a moment later, with no way back.
 *
 * Only PENDING_OFF takes the cipher off a file: that is somebody having moved the switch. A ciphered
 * file under OFF is left by none of the app's own starts, each of which settles the switch at what
 * the file is; it is what a switch that cannot be read leaves, an unreadable one reading as OFF,
 * beside a key that can. Taken for a wish, it would decrypt the record without anybody asking. It
 * is opened as it is instead, and the switch settles at ON.
 */
internal fun encryptionPlan(state: EncryptionState, header: FileHeader, key: KeyAccess): EncryptionPlan =
    when (header) {
        FileHeader.ABSENT -> if (state.wantsCipher) EncryptionPlan.OPEN_CIPHERED else EncryptionPlan.OPEN_PLAIN

        FileHeader.PLAIN -> if (state.wantsCipher) EncryptionPlan.ENCRYPT else EncryptionPlan.OPEN_PLAIN

        FileHeader.CIPHERED -> when (key) {
            KeyAccess.UNAVAILABLE -> EncryptionPlan.REFUSE

            KeyAccess.NONE -> EncryptionPlan.LOCK_ASIDE

            KeyAccess.UNWRAPS ->
                if (state == EncryptionState.PENDING_OFF) EncryptionPlan.DECRYPT else EncryptionPlan.OPEN_CIPHERED
        }
    }

/** The position after the switch is moved to [wanted]: a wish for the next start, or a wish withdrawn. */
internal fun requestedState(current: EncryptionState, wanted: Boolean): EncryptionState = when (current) {
    EncryptionState.OFF -> if (wanted) EncryptionState.PENDING_ON else current
    EncryptionState.PENDING_ON -> if (wanted) current else EncryptionState.OFF
    EncryptionState.ON -> if (wanted) current else EncryptionState.PENDING_OFF
    EncryptionState.PENDING_OFF -> if (wanted) EncryptionState.ON else current
}

/** The sixteen bytes every plaintext SQLite file begins with: the fifteen of the name, then a NUL. */
private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0.toByte()

/**
 * Whether [head], the first bytes of a file, opens a plaintext SQLite database. An encrypted one
 * begins with its salt.
 */
internal fun isPlainSqliteHeader(head: ByteArray): Boolean =
    head.size >= SQLITE_HEADER.size && SQLITE_HEADER.indices.all { head[it] == SQLITE_HEADER[it] }

/**
 * An empty file counts as absent: SQLite treats it as a database with nothing in it, and either
 * helper can start from one.
 */
internal fun fileHeader(file: File): FileHeader {
    if (!file.exists() || file.length() == 0L) return FileHeader.ABSENT
    val head = ByteArray(SQLITE_HEADER.size)
    val read = file.inputStream().use { it.read(head) }
    return if (read == head.size && isPlainSqliteHeader(head)) FileHeader.PLAIN else FileHeader.CIPHERED
}

/** The file the export is written into, beside the original, until it has been checked. */
private const val CONVERTING = ".converting"

/** The plaintext original, kept until the encrypted copy has been opened once. */
private const val PRE_ENCRYPT = ".pre-encrypt.bak"

/** The encrypted original, kept until the plaintext copy has been opened once. */
private const val PRE_DECRYPT = ".pre-decrypt.bak"

/**
 * A database waiting beside the one in use, by what its owner can still do about it — which is what
 * the card on the profile says, and of the first of these when more than one kind is waiting. Each
 * can be let go of, and none is touched until its owner says so. The kind is the file's name
 * ([suffix]) and nothing else, so it is given and changed by the rename that moves the file, with no
 * note beside it to fall out of step. [title], [body] and [action] are the card's words for it.
 */
internal enum class SetAside(
    val suffix: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
) {
    /**
     * A database this phone's own key opens, set aside whole: the Keystore would not answer for the
     * key when it was to be opened, or its owner has since opened the other record in its place. It
     * can be opened again in place of the one in use ([DatabaseEncryption.requestReturn]).
     */
    OWN(".aside", R.string.db_own_title, R.string.db_own_body, R.string.db_own_open),

    /** A database no key on this phone opens: only an export brings that record back. */
    LOCKED(".locked", R.string.db_locked_title, R.string.db_locked_body, R.string.db_locked_import),

    /**
     * Where the quarantine puts a file SQLite could not read; the kept original of a copy that
     * failed goes beside it. It can be saved out for whoever reads it with other tools.
     */
    DAMAGED(".corrupt", R.string.db_damaged_title, R.string.db_damaged_body, R.string.db_damaged_save),
}

/** A database is three files once written to; the log holds the writes the main file is missing. */
private val DATABASE_PIECES = listOf("", "-wal", "-shm")

/** Renames a database with its log and index. True when every piece that existed moved. */
internal fun moveDatabaseFiles(from: String, to: String): Boolean = DATABASE_PIECES
    .map { File(from + it) to File(to + it) }
    .filter { (source, _) -> source.exists() }
    .all { (source, target) -> source.renameTo(target) }

/**
 * Puts back a swap the process died in the middle of. The swap is two renames — the original out to
 * its `.bak`, the converted copy in under the real name — and a death between them leaves no
 * `steps.db`, or an empty one, which is the same thing to [fileHeader] and has to be the same thing
 * here: [DatabaseEncryption.reconcile] asks the two of them about the one file, one line after the
 * other, and a recovery that read a zero-byte placeholder as a database would step over the whole
 * record beside it and leave the `.bak` for the next open to sweep. The `.bak` is whole, its log
 * having gone with it, so it comes back and the conversion is simply made again; a copy that never
 * finished is removed either way.
 *
 * Two `.bak` files can be lying there at once — a `.pre-encrypt.bak` outlives its own conversion when
 * the process dies before Room's first query — and the one that comes back is the one written last,
 * which is the one this swap set aside: a `.bak` is made by a rename, so its mtime stays at the
 * moment its own conversion checkpointed the live database. The names are in no meaningful order,
 * and picking the first of them would restore a months-old record over the current one.
 */
internal fun recoverInterruptedSwap(dbFile: File) {
    val kept = listOf(PRE_ENCRYPT, PRE_DECRYPT).map { dbFile.path + it }
        .filter { File(it).exists() }
        .maxByOrNull { File(it).lastModified() }
    if (kept != null && fileHeader(dbFile) == FileHeader.ABSENT) {
        // Every piece of the placeholder goes, not just the main name: an aborted create leaves a
        // -wal and a -shm too, and a log belonging to no database, left beside the restored one, is
        // a log SQLite would try on it.
        DATABASE_PIECES.forEach { File(dbFile.path + it).delete() }
        moveDatabaseFiles(kept, dbFile.path)
    }
    File(dbFile.path + CONVERTING).delete()
    // A hot journal left beside a copy that never finished: harmless to the fresh target it would
    // roll back onto, and swept so that nothing of the attempt stays behind.
    File(dbFile.path + CONVERTING + "-journal").delete()
}

/**
 * The name a file is set aside under: [name] — `steps.db.locked`, say — or `.locked.2`, `.locked.3`
 * and so on when one is waiting there already. A rename over it would take the earlier copy with
 * it, and a second file set aside is no reason to lose the first.
 */
internal fun freeName(name: String): String = generateSequence(1) { it + 1 }
    .map { if (it == 1) name else "$name.$it" }
    .first { !File(it).exists() }

/** Every copy of [kind] beside [dbFile], logs and indexes included, in whatever order the disk lists them. */
internal fun setAsideCopies(dbFile: File, kind: SetAside): List<File> =
    dbFile.parentFile?.listFiles { file -> file.name.startsWith(dbFile.name + kind.suffix) }.orEmpty().toList()

/** The kind of copy waiting beside [dbFile] that the card speaks of: the first there is one of, or null. */
internal fun setAsideKind(dbFile: File): SetAside? =
    SetAside.entries.firstOrNull { setAsideCopies(dbFile, it).isNotEmpty() }

/**
 * The record a return brings back: of this phone's own copies set aside, the one written last. There
 * is one, as a rule. Two are what a return leaves that died between its renames — the record that was
 * in use a moment ago beside the one asked for, with nothing under the live name — and the next
 * start, the wish still standing, brings back the newer of them and keeps the other for the same ask.
 *
 * Limitation: with three or more copies only the newest two change places.
 */
internal fun ownCopyToReturn(dbFile: File): File? = setAsideCopies(dbFile, SetAside.OWN)
    .filter { copy -> DATABASE_PIECES.none { it.isNotEmpty() && copy.name.endsWith(it) } }
    .maxByOrNull { it.lastModified() }

/** What a start does about the record set aside once its owner has asked for it back. */
internal enum class ReturnPlan { SWAP, WAIT, LOCK }

/**
 * The decision, from what the copy set aside is and what the Keystore said about the key. A plain
 * copy needs no key. A ciphered one comes back only under a key that unwrapped. A Keystore that
 * would not answer is no verdict here either ([encryptionPlan]): the copy waits where it is, and the
 * ask can be made again. A Keystore with no key that fits has given its verdict: the copy is no
 * longer this phone's to open, and it joins the locked ones, where the card says of it what is true.
 */
internal fun returnPlan(copy: FileHeader, key: KeyAccess): ReturnPlan = when {
    copy != FileHeader.CIPHERED || key == KeyAccess.UNWRAPS -> ReturnPlan.SWAP
    key == KeyAccess.UNAVAILABLE -> ReturnPlan.WAIT
    else -> ReturnPlan.LOCK
}

/**
 * Puts [copy] under the live name and the database in use aside in its turn, under a name of its
 * own, so that nothing is lost by the exchange and the same ask brings it back. True when the copy
 * stands under the live name. Whatever would not move, the record in use goes back under its name if
 * that name is still free — never renamed over a copy that half arrived.
 */
internal fun swapIn(dbFile: File, copy: File): Boolean {
    val aside = freeName(dbFile.path + SetAside.OWN.suffix)
    val swapped = moveDatabaseFiles(dbFile.path, aside) && moveDatabaseFiles(copy.path, dbFile.path)
    if (!swapped && !dbFile.exists()) moveDatabaseFiles(aside, dbFile.path)
    return swapped
}

/** [files] under their own names in one zip: a damaged database is of use only with its log beside it. */
internal fun zipFiles(files: List<File>, out: OutputStream) = ZipOutputStream(out).use { zip ->
    files.forEach { file ->
        zip.putNextEntry(ZipEntry(file.name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}

/**
 * Encryption of the database at rest, and the one moment it can be changed.
 *
 * The switch on the settings page writes a wish — PENDING_ON or PENDING_OFF — and nothing else. The
 * file is rewritten by [reconcile], which [StepsDb.get] calls under its lock before the first Room
 * instance of the process exists, because that is the only point at which nothing can be holding
 * the file open: not a widget's write, not the step service's insert, not a Flow the profile page
 * is collecting. A conversion on a live connection would be a race with every one of them.
 *
 * The conversion itself is SQLCipher's own export: the original is opened, its log folded into it,
 * a second file attached under the other key and filled from the first, and the copy then opened on
 * its own and checked — `PRAGMA integrity_check`, the row count of every table, the schema version —
 * before it takes the original's name. The original is kept under a `.bak` name until the copy has
 * opened once under Room, and a process that dies between the two renames finds its file put back
 * at the next start. Whatever fails leaves the original where it was, the failure written under the
 * switch, and the switch back at the position the file is in.
 *
 * A file this phone cannot open — one a device-to-device transfer brought along with the wrapped key
 * of a phone that no longer exists — is not corrupt, and is not treated as such: it is moved aside
 * as `.locked`, the profile page says what it is, and the app starts empty until an export is
 * imported or the copy is let go of. That happens on the Keystore's word alone. A Keystore that
 * would not answer — as it may in the first seconds after boot, when the step service is the first
 * in the process to ask — gets no file set aside and no file opened: [reconcile] throws, the start
 * fails, and the next one asks again.
 *
 * Only a Keystore that goes on refusing has the file set aside, so that the app can start at all,
 * and then under a name of its own, `.aside`: it is this phone's record, whole, and its wrapped key
 * is still in the preferences. The profile page says so and offers to open it again, which is a wish
 * for the next start like the switch's ([requestReturn]): if the Keystore answers then, the record
 * comes back under the live name, and the one begun in the meantime is set aside in its place, to
 * be opened the same way or let go of ([SetAside]).
 *
 * Whoever calls [StepsDb.get] first does the work on their thread: a widget on the main one, the
 * step service on its own write thread, and the main activity — on a start with a key or a wish to
 * look at — off the main thread, before it composes anything. A conversion that outlives a
 * broadcast's ten seconds is killed with its half-made copy, which the next start sweeps up and
 * makes again. "Close the app" ends the process and opens nothing: the usual first caller after it is
 * the step service, brought back by START_STICKY, or the main activity when the person opens the
 * app before that.
 */
internal object DatabaseEncryption {
    private const val KEY_STATE = "encryption"
    private const val KEY_ERROR = "lastError"
    private const val KEY_RETURN = "returnOwnCopy"
    private const val KEY_REFUSALS = "keystoreRefusals"
    private const val KEY_REFUSED_AT = "keystoreRefusedAt"
    private const val SOURCE_FLAGS = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY

    /**
     * Whether a refusal has been counted in this process already ([refusalCounts]). Touched only by
     * [refuse], inside [reconcile] and so under [StepsDb.get]'s lock.
     */
    private var refusalCounted = false

    fun state(context: Context): EncryptionState = dbPrefs(context).getString(KEY_STATE, null)
        ?.let { name -> EncryptionState.entries.firstOrNull { it.name == name } }
        ?: EncryptionState.OFF

    /** Moves the switch. Nothing happens to the file here; that is the next start's, under [StepsDb.get]. */
    fun request(context: Context, wanted: Boolean): EncryptionState {
        val next = requestedState(state(context), wanted)
        dbPrefs(context).edit(commit = true) {
            putString(KEY_STATE, next.name)
            remove(KEY_ERROR)
        }
        return next
    }

    /**
     * Why the last start could not make the change asked of it, until the switch is moved again: the
     * clause for [EncryptionFailure], in the language the phone reads.
     */
    fun lastError(context: Context): String? = dbPrefs(context).getString(KEY_ERROR, null)
        ?.let { name -> EncryptionFailure.entries.firstOrNull { it.name == name } }
        ?.let { context.getString(it.text) }

    /** What is waiting beside the database in use, for the card on the profile; null for nothing. */
    fun setAside(context: Context): SetAside? = setAsideKind(context.getDatabasePath(DATABASE_FILE))

    /**
     * Whether a database no start has read is waiting on the disk — one no key here opens, or this
     * phone's own, set aside by [refuse] or by a return. Either may name files the record in use
     * does not, which is what the sweep of attachments asks before it lets any go.
     */
    fun lockedCopyPresent(context: Context): Boolean =
        setAside(context).let { it == SetAside.OWN || it == SetAside.LOCKED }

    /** Deletes every copy of [kind], logs and indexes included: its owner has decided what becomes of it. */
    fun forget(context: Context, kind: SetAside) {
        setAsideCopies(context.getDatabasePath(DATABASE_FILE), kind).forEach { it.delete() }
    }

    /**
     * Asks for this phone's own record, set aside, to be opened in place of the one in use. A wish
     * for the next start, like the switch's and for the same reason: the two files change places in
     * [reconcile], before anything can be holding either of them open.
     */
    fun requestReturn(context: Context) {
        dbPrefs(context).edit(commit = true) {
            putBoolean(KEY_RETURN, true)
            remove(KEY_ERROR)
        }
    }

    /** The damaged copies, logs included, zipped into [out]: the way such a file leaves private storage. */
    fun writeDamagedCopies(context: Context, out: OutputStream) =
        zipFiles(setAsideCopies(context.getDatabasePath(DATABASE_FILE), SetAside.DAMAGED), out)

    /**
     * Brings the file into line with the switch, and answers with the passphrase Room is to open it
     * under — null for a plaintext open. Called once per process, under [StepsDb.get]'s lock,
     * before the builder; see the class comment for why nowhere else will do. Throws, and opens
     * nothing, when the file is ciphered and the Keystore would not say whether the key is here —
     * and is then called again by every later caller in the process, there being no instance yet.
     */
    fun reconcile(context: Context, dbFile: File): ByteArray? {
        // An app that never turned the switch on has no key and nothing to look at. This is the
        // path almost every start takes, and it is also what leaves a damaged plain file to the
        // quarantine: only a phone that has held a key can be looking at ciphertext.
        if (state(context) == EncryptionState.OFF && !DatabaseKey.exists(context)) return null
        recoverInterruptedSwap(dbFile)
        // Asked for only where the answer decides something: a record asked back, a ciphered file
        // ([encryptionPlan] reads it for no other), a file to be opened or made under the cipher.
        // A phone that turned the switch off again keeps its key for the copies kept under it, and
        // a plain file that needs nothing of the Keystore is no reason to wake it at every start.
        val key by lazy { runCatching { DatabaseKey.passphraseOrNull(context) } }
        // Before the file is looked at: a record asked back takes the live name first, and the
        // switch with it, and the start goes on from there like any other.
        if (dbPrefs(context).getBoolean(KEY_RETURN, false)) bringBack(context, dbFile, key)
        val state = state(context)
        val header = fileHeader(dbFile)
        val access = if (header == FileHeader.CIPHERED) key.access() else KeyAccess.NONE
        val passphrase = when (encryptionPlan(state, header, access)) {
            EncryptionPlan.OPEN_PLAIN -> null
            EncryptionPlan.OPEN_CIPHERED -> cipherKey(context, key.getOrNull())
            EncryptionPlan.ENCRYPT -> cipherKey(context, key.getOrNull())?.let { encrypt(context, dbFile, it) }
            EncryptionPlan.DECRYPT -> decrypt(context, dbFile, requireNotNull(key.getOrNull()))
            EncryptionPlan.LOCK_ASIDE -> lockAside(context, dbFile, SetAside.LOCKED)
            EncryptionPlan.REFUSE -> refuse(context, dbFile, requireNotNull(key.exceptionOrNull()))
        }
        // Whatever was asked, the switch now shows what the file is: a pending wish that was
        // granted, or one that could not be, or one a dead process granted without writing it down.
        // A start that got this far also clears the count of starts the Keystore refused.
        val settled = if (passphrase == null) EncryptionState.OFF else EncryptionState.ON
        dbPrefs(context).edit(commit = true) {
            putString(KEY_STATE, settled.name)
            remove(KEY_REFUSALS)
            remove(KEY_REFUSED_AT)
        }
        return passphrase
    }

    /**
     * A Keystore that would not answer, counted. The first such starts are refused — nothing is
     * opened, the start fails, the next one asks again — because a hiccup while the phone is still
     * booting is the common case, and a record set aside for a hiccup is a record forked by the
     * fresh file the step service begins writing a moment later. A Keystore that refuses
     * [MAX_KEYSTORE_REFUSALS] starts in a row is not having a hiccup: the key blob is dead, as an
     * update can leave it, or the store is, and an app that cannot start at all is worse than a
     * record set aside: kept whole under a name that says it is this phone's own ([SetAside.OWN]),
     * the Keystore's refusal written under the switch. Should the Keystore come back, the card on
     * the profile is the way to it ([requestReturn]).
     *
     * It is the start that is counted and not the ask: a refusal [refusalCounts] does not take for
     * a start of its own is thrown back as it came, adding nothing to the count and setting nothing
     * aside, however high the count already stands.
     */
    private fun refuse(context: Context, dbFile: File, failure: Throwable): ByteArray? {
        val prefs = dbPrefs(context)
        val now = System.currentTimeMillis()
        if (!refusalCounts(refusalCounted, prefs.getLong(KEY_REFUSED_AT, 0L), now)) throw failure
        val refusals = prefs.getInt(KEY_REFUSALS, 0)
        if (!refusalLocksAside(refusals)) {
            refusalCounted = true
            prefs.edit(commit = true) {
                putInt(KEY_REFUSALS, refusals + 1)
                putLong(KEY_REFUSED_AT, now)
            }
            throw failure
        }
        recordError(context, EncryptionFailure.KEYSTORE)
        return lockAside(context, dbFile, SetAside.OWN)
    }

    /**
     * The record set aside, opened in place of the one in use because its owner asked
     * ([requestReturn]). One ask, one try: whatever comes of it, the wish is cleared — but last,
     * after the files have moved, so that a process that died between the two renames finds it still
     * standing and something to put under the live name ([ownCopyToReturn]). Why a copy did not come
     * back is written under the switch.
     */
    private fun bringBack(context: Context, dbFile: File, key: Result<ByteArray?>) {
        ownCopyToReturn(dbFile)?.let { copy -> returned(context, dbFile, copy, key) }?.let { recordError(context, it) }
        dbPrefs(context).edit(commit = true) { remove(KEY_RETURN) }
    }

    /** What [returnPlan] decided for [copy], done. Answers why it did not come back, or null when it did. */
    private fun returned(context: Context, dbFile: File, copy: File, key: Result<ByteArray?>): EncryptionFailure? =
        when (returnPlan(fileHeader(copy), key.access())) {
            ReturnPlan.SWAP -> swap(context, dbFile, copy, key.getOrNull())
            ReturnPlan.WAIT -> EncryptionFailure.KEYSTORE
            ReturnPlan.LOCK -> lock(dbFile, copy)
        }

    /**
     * The two files change places, once a ciphered [copy] has been read under the key — read-only,
     * so that a copy that stays is left as it was, and as far as its first page, which is where a key
     * that does not fit shows. One that is no database under that key is not this phone's to open
     * after all, and joins the locked ones; a disk that failed at that moment says nothing about the
     * file, which waits. A plain copy is not tried: the plain helper meets whatever it is, and keeps
     * what it cannot read. Afterwards the switch shows what the file under the live name is, so that
     * the start opens it as it is and converts nothing.
     */
    private fun swap(context: Context, dbFile: File, copy: File, passphrase: ByteArray?): EncryptionFailure? {
        val ciphered = fileHeader(copy) == FileHeader.CIPHERED
        val unread = if (ciphered) {
            runCatching { openReadOnly(copy, passphrase).use { it.count("SELECT count(*) FROM sqlite_master") } }
                .exceptionOrNull()
        } else {
            null
        }
        return when {
            unread != null -> if (sourceUnreadable(unread)) lock(dbFile, copy) else encryptionFailureOf(unread)

            // The record set aside could not take the live one's place.
            !swapIn(dbFile, copy) -> EncryptionFailure.FILES

            else -> {
                val settled = if (ciphered) EncryptionState.ON else EncryptionState.OFF
                dbPrefs(context).edit(commit = true) { putString(KEY_STATE, settled.name) }
                // Another record stands under the name: its rows are not the ones the backups know.
                forgetInstallId(context)
                null
            }
        }
    }

    /**
     * A copy no key here opens is no longer this phone's own, and takes the name that says so.
     * Answers the reason, for the line under the switch.
     */
    private fun lock(dbFile: File, copy: File): EncryptionFailure {
        moveDatabaseFiles(copy.path, freeName(dbFile.path + SetAside.LOCKED.suffix))
        return EncryptionFailure.NO_KEY
    }

    /** The converted file has opened under Room: the copy kept against a crash mid-swap has done its job. */
    fun opened(path: String) {
        listOf(PRE_ENCRYPT, PRE_DECRYPT).forEach { suffix ->
            DATABASE_PIECES.forEach { File(path + suffix + it).delete() }
        }
    }

    /**
     * The file at [path] was just quarantined as corrupt, under the name [aside]. If this start
     * converted it, the `.bak` beside it is the only whole record there is, and the fresh database
     * Room opens next must not be the open that lets it go: it moves beside the quarantined copy, as
     * `steps.db.corrupt.pre-encrypt.bak`, where nothing sweeps it and `sqlite3` reads it back.
     */
    fun keepOriginalBesideCorrupt(path: String, aside: String) {
        listOf(PRE_ENCRYPT, PRE_DECRYPT).forEach { suffix -> moveDatabaseFiles(path + suffix, aside + suffix) }
    }

    /**
     * The key a ciphered file is opened or made under: the one that unwrapped, or one made now. When
     * the Keystore will not make one — it would not answer, or generating or wrapping failed — the
     * answer is null with the reason written under the switch, and the file opens plain with the
     * switch settling at OFF. A wish that cannot be granted is withdrawn, not left crashing every
     * start with no switch left to reach; the person reads why and moves it again when they like.
     */
    private fun cipherKey(context: Context, unwrapped: ByteArray?): ByteArray? = unwrapped
        ?: runCatching { DatabaseKey.ensurePassphrase(context) }
            .onFailure { recordError(context, encryptionFailureOf(it)) }
            .getOrNull()

    private fun encrypt(context: Context, dbFile: File, passphrase: ByteArray): ByteArray? =
        if (convert(context, dbFile, from = null, to = passphrase, keepAs = PRE_ENCRYPT) == Conversion.DONE) {
            passphrase
        } else {
            null
        }

    /**
     * A source that would not open under the key is not the encrypted database it was taken for — a
     * plain file with a damaged header, or a file under some other key — and it is not opened under
     * the cipher either. The plain helper meets it, finds it unreadable and quarantines it as it
     * does any unreadable file, with the switch at OFF and a fresh plain file, rather than a fresh
     * encrypted one nobody asked for. A conversion that failed later leaves a readable encrypted
     * file, and that is what is opened.
     */
    private fun decrypt(context: Context, dbFile: File, passphrase: ByteArray): ByteArray? =
        when (convert(context, dbFile, from = passphrase, to = null, keepAs = PRE_DECRYPT)) {
            Conversion.DONE, Conversion.SOURCE_UNREADABLE -> null
            Conversion.FAILED -> passphrase
        }

    /**
     * Sets a file that cannot be opened now aside under a name of its own — never over an earlier
     * one — which says what [kind] it is: one no key here opens, or this phone's own that the
     * Keystore would not answer for. A rename that fails leaves the file to the plain helper, which
     * finds it unreadable and quarantines it as `.corrupt`: kept either way.
     */
    private fun lockAside(context: Context, dbFile: File, kind: SetAside): ByteArray? {
        if (moveDatabaseFiles(dbFile.path, freeName(dbFile.path + kind.suffix))) {
            // The fresh database that follows is a new installation as far as backups can tell.
            forgetInstallId(context)
        } else {
            recordError(context, EncryptionFailure.FILES)
        }
        return null
    }

    private fun recordError(context: Context, failure: EncryptionFailure) {
        dbPrefs(context).edit(commit = true) { putString(KEY_ERROR, failure.name) }
    }

    /**
     * How a conversion ended: the copy took the original's name; the original would not so much as
     * open under the key it was thought to be under; or it failed somewhere later, the original as
     * it was.
     */
    private enum class Conversion { DONE, SOURCE_UNREADABLE, FAILED }

    /** The original rewritten under the other key, or left exactly as it was. */
    private fun convert(context: Context, source: File, from: ByteArray?, to: ByteArray?, keepAs: String): Conversion {
        loadSqlCipher()
        val target = File(source.path + CONVERTING)
        target.delete()
        val db = runCatching { openSource(source, from) }.getOrElse { failure ->
            recordError(context, encryptionFailureOf(failure))
            return if (sourceUnreadable(failure)) Conversion.SOURCE_UNREADABLE else Conversion.FAILED
        }
        return runCatching {
            val shape = db.use { export(it, target, to) }
            verify(target, to, shape)
            check(moveDatabaseFiles(source.path, source.path + keepAs)) { "the original could not be set aside" }
            check(target.renameTo(source)) { "the converted copy could not take the original's name" }
        }.onFailure { failure ->
            // Wherever it stopped, the record is where it was — at worst under the .bak name, from
            // which it comes straight back — and the copy is not left lying about half made.
            recoverInterruptedSwap(source)
            recordError(context, encryptionFailureOf(failure))
        }.fold({ Conversion.DONE }, { Conversion.FAILED })
    }

    /**
     * The original, open under the key it is believed to be under. CREATE_IF_NECESSARY is for the
     * target, not the source: an ATTACH opens its file under the flags the main connection was
     * opened with, and without it the copy cannot be created. A wrong key, or a file that is no
     * database, fails here: opening reads the first page.
     */
    private fun openSource(source: File, key: ByteArray?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(source.path, key, null, SOURCE_FLAGS, null, null)

    /**
     * SQLCipher's export of the open [db] into [target], under [to]. Every committed write is folded
     * into the main file first: the export reads through the log regardless, but the `.bak` the
     * source becomes should be whole on its own. The version stamp is copied by hand, because
     * `sqlcipher_export` carries the schema and the rows and not the pragma Room reads first.
     */
    private fun export(db: SQLiteDatabase, target: File, to: ByteArray?): Shape {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val shape = db.shape()
        db.execSQL("ATTACH DATABASE ? AS target KEY ?", arrayOf(target.path, keyText(to)))
        db.rawQuery("SELECT sqlcipher_export('target')", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA target.user_version = ${shape.version}")
        db.execSQL("DETACH DATABASE target")
        return shape
    }

    /** [file] opened under [key] to be read and nothing more, so that it is left exactly as it was. */
    private fun openReadOnly(file: File, key: ByteArray?): SQLiteDatabase {
        loadSqlCipher()
        return SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READONLY, null, null)
    }

    /** The copy opened on its own, under the key it is meant for, and read against the original. */
    private fun verify(target: File, key: ByteArray?, expected: Shape) {
        openReadOnly(target, key).use { db ->
            val integrity = db.compileStatement("PRAGMA integrity_check").use { it.simpleQueryForString() }
            check(integrity == "ok") { "integrity check of the copy: $integrity" }
            val shape = db.shape()
            check(shape == expected) { "the copy holds $shape, the original $expected" }
        }
    }

    /**
     * What is compared across the conversion, to catch an export that stopped short: the rows of
     * every table the file holds, by name, and the version stamp. `sqlcipher_export` copies the
     * tables in the order they were made, so an export cut off early loses the newest first — and
     * which those are is read off the file, not written down here: a hand-picked list of the newest
     * tables would leave the next one a version adds out of the check. A table the export left out
     * altogether is missing from the copy's map, and that differs too.
     */
    private data class Shape(val rows: Map<String, Long>, val version: Int)

    private fun SQLiteDatabase.shape() = Shape(
        rows = tableNames().associateWith { count("SELECT count(*) FROM \"${it.replace("\"", "\"\"")}\"") },
        version = version,
    )

    private fun SQLiteDatabase.tableNames(): List<String> =
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SQLiteDatabase.count(sql: String): Long = compileStatement(sql).use { it.simpleQueryForLong() }

    /** SQLCipher's `KEY` for an attached file: the raw-key text, or the empty string that means plaintext. */
    private fun keyText(key: ByteArray?): String = key?.toString(Charsets.US_ASCII).orEmpty()
}

/**
 * The native library, loaded before any SQLCipher class is touched — the library does not load
 * itself, and its classes call into it from their static initialisers. Loading twice is a no-op.
 */
private fun loadSqlCipher() = System.loadLibrary("sqlcipher")

/**
 * Room's door to an encrypted file: SQLCipher's helper under Room's callback, the way the framework
 * factory puts the framework helper under it.
 *
 * SQLCipher ships a factory of its own and it is not used, because of what it does with corruption.
 * It hands its helper no error handler, so the library's default runs, and under a codec that
 * default returns without touching the file — the right thing for a library that cannot tell a
 * damaged file from a wrong key, and the wrong thing for this app, whose next start would meet the
 * same exception and the one after that too. Here the corruption goes to Room's callback, which the
 * quarantine in Data.kt wraps: the file is moved aside and a fresh one opened, exactly as for a
 * plain database. A key that does not fit never gets this far — [DatabaseEncryption.reconcile] has
 * already moved such a file aside as locked, not as corrupt.
 */
internal class CipherOpenHelperFactory(private val passphrase: ByteArray) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        loadSqlCipher()
        val callback = configuration.callback
        val corruption = DatabaseErrorHandler { db, _ -> callback.onCorruption(db) }
        val helper = object : SQLiteOpenHelper(
            configuration.context,
            configuration.name,
            passphrase,
            null,
            callback.version,
            0,
            corruption,
            null,
            false,
        ) {
            override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)

            override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
        }
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = helper.databaseName

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = helper.setWriteAheadLoggingEnabled(enabled)

            override val writableDatabase: SupportSQLiteDatabase get() = quarantining { helper.writableDatabase }

            override val readableDatabase: SupportSQLiteDatabase get() = quarantining { helper.readableDatabase }

            /**
             * SQLCipher hands a corrupt page to the error handler and opens again; a first page that
             * fails its HMAC it throws as [SQLiteNotADatabaseException] instead, and that would come
             * back at every start with the switch out of reach. Such a file goes the corrupt file's
             * way — moved aside by the quarantine's own hand, since there is no opened database to
             * hand the callback — and the open is made once more, on the fresh file that follows.
             * A file that would not move is not opened over: the exception goes on as it came.
             */
            private fun quarantining(open: () -> SupportSQLiteDatabase): SupportSQLiteDatabase =
                try {
                    open()
                } catch (notADatabase: SQLiteNotADatabaseException) {
                    val path = configuration.name?.let { configuration.context.getDatabasePath(it).path }
                    if (path == null || !quarantineDatabaseFiles(path)) throw notADatabase
                    forgetInstallId(configuration.context)
                    open()
                }

            override fun close() = helper.close()
        }
    }
}
