package com.pwnall.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A plain copy of everything into a folder of the person's choosing, once a day, the last seven kept
 * — and beside them the last seven of a record that was lost, when the database had to start over.
 *
 * Plain on purpose, and not as a shortcut. The sealed export derives its key from a password that is
 * never stored — that is the whole of the promise on the switch beside it — so nothing here could
 * seal a file the same way at three in the morning without keeping the password around. Sealing to
 * a public key would need no secret kept, but it is a second file format, a key pair to guard and
 * a second password to forget, for copies whose whole use is to be readable on the day
 * the phone is gone. So the copies stay plain, the settings row says so where the switch is, and
 * the database-encryption switch says that it does not cover them. The folder comes through the
 * system's tree picker and is held by a persistable grant: the app has no storage permission and
 * needs none.
 *
 * The keys live in the app's preferences ([backupPrefs]), which a device-to-device transfer
 * carries; the grant it does not. A phone set up that way arrives with the switch on and a folder
 * it cannot reach, the first run fails, and the profile's front page says so
 * ([backupNeedsAttention]) with the way to choose the folder again one tap behind it.
 */
internal object AutoBackup {
    private const val WORK_NAME = "auto-backup"
    private const val KEY_FOLDER = "autoBackupFolder"
    private const val KEY_AUTO_LAST_MS = "autoBackupLastMs"
    private const val KEY_AUTO_ERROR = "autoBackupError"

    /** Stamped by every export that made it across — the two pickers' and the worker's alike. */
    private const val KEY_LAST_EXPORT_MS = "lastExportMs"

    /** The days the record began again, each written as a copy's name writes its day. */
    private const val KEY_STARTED_OVER = "autoBackupStartedOver"

    /** Daily copies kept in the folder. A week covers a phone left in a drawer over a holiday. */
    internal const val KEEP = 7

    /** Past this many days without any copy the profile's front page starts to say so. */
    internal const val STALE_DAYS = 30L

    private const val URI_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun folder(context: Context): Uri? = backupPrefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** When the last file of any kind was written, or null when none ever was. */
    fun lastExportMs(context: Context): Long? =
        backupPrefs(context).getLong(KEY_LAST_EXPORT_MS, 0L).takeIf { it > 0L }

    fun markExported(context: Context) {
        backupPrefs(context).edit { putLong(KEY_LAST_EXPORT_MS, System.currentTimeMillis()) }
    }

    /** The days a database started over on this installation, for [autoBackupsToPrune]. */
    fun startedOverDays(context: Context): Set<String> =
        backupPrefs(context).getStringSet(KEY_STARTED_OVER, null).orEmpty()

    /**
     * Called where a database starts over ([forgetInstallId]): damaged, or sealed by a key this
     * phone no longer has. The folder and its grant outlive the record, so the worker goes on
     * copying the empty one that follows, and the rotation has to know where the old one ended.
     */
    fun markStartedOver(context: Context) {
        val days = startedOverDays(context) + LocalDate.now().toString()
        backupPrefs(context).edit { putStringSet(KEY_STARTED_OVER, days) }
    }

    fun state(context: Context): AutoBackupState {
        val prefs = backupPrefs(context)
        return AutoBackupState(
            folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse),
            lastCopyMs = prefs.getLong(KEY_AUTO_LAST_MS, 0L).takeIf { it > 0L },
            error = prefs.getString(KEY_AUTO_ERROR, null),
        )
    }

    fun enable(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, URI_FLAGS)
        backupPrefs(context).edit {
            putString(KEY_FOLDER, tree.toString())
            remove(KEY_AUTO_LAST_MS)
            remove(KEY_AUTO_ERROR)
        }
        schedule(context)
    }

    /**
     * Takes the folder's grant again, which the system stamps as taken now. It keeps 512 persisted
     * grants an app holds and drops the oldest for each one taken past that, and every file attached
     * to a row holds one: with enough of them the folder's, taken once on the day it was chosen, would be
     * the first to go, and the copies would stop. Renewed before each copy, it is never the oldest for
     * long. A grant already gone fails here, as the copy would have.
     */
    fun renewGrant(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, URI_FLAGS)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        // The grant may already be gone — the folder deleted, its provider uninstalled — and that
        // is no reason to keep the setting pointing at it.
        folder(context)?.let { tree ->
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, URI_FLAGS) }
        }
        backupPrefs(context).edit {
            remove(KEY_FOLDER)
            remove(KEY_AUTO_LAST_MS)
            remove(KEY_AUTO_ERROR)
        }
    }

    /**
     * Called on every resume. Under KEEP it costs nothing while the work is there, and it puts the
     * work back after anything that emptied WorkManager's own table while the folder stayed in the
     * preferences — the same reason the reminders re-enqueue themselves there.
     */
    fun reschedule(context: Context) {
        if (folder(context) != null) schedule(context)
    }

    /** The worker's runs, so the settings row can re-read the result when one has finished. */
    fun runs(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(WORK_NAME)

    /**
     * What the folder is called, for the row under the switch. A provider that will not answer —
     * the grant revoked since — leaves the tail of the tree id, which is at least where it was.
     */
    fun folderName(context: Context, tree: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val named = runCatching {
            context.contentResolver.query(document, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return named?.takeIf { it.isNotBlank() }
            ?: tree.lastPathSegment?.substringAfterLast(':')
            ?: tree.toString()
    }

    internal fun recordRun(context: Context, error: String?) {
        backupPrefs(context).edit {
            if (error == null) {
                putLong(KEY_AUTO_LAST_MS, System.currentTimeMillis())
                remove(KEY_AUTO_ERROR)
            } else {
                putString(KEY_AUTO_ERROR, error)
            }
        }
    }

    private fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build(),
        )
    }
}

/** The switch's state as the preferences hold it: the folder, the last copy that worked, the last failure. */
internal data class AutoBackupState(val folder: Uri?, val lastCopyMs: Long?, val error: String?)

private const val AUTO_PREFIX = "pwn-health-auto-"
private const val AUTO_SUFFIX = ".json"

// Both prefixes: `pwn-fit-auto-` is what earlier versions of the app named their copies, and a
// folder picked under them still holds those. Recognising them is what keeps them in the rotation
// instead of leaving a permanent, growing pile of files nothing ever prunes.
private const val UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val AUTO_NAME = Regex(
    """pwn-(?:fit|health)-auto-([0-9]{4}-[0-9]{2}-[0-9]{2})(?:-($UUID_TEXT)-([0-9]+)-$UUID_TEXT)?\.json""",
)

/** The date and generation identify the rotation. The timestamp and nonce give every attempt a fresh document. */
internal fun autoBackupName(day: LocalDate, generation: String, at: Long, nonce: UUID): String =
    "$AUTO_PREFIX$day-$generation-$at-$nonce$AUTO_SUFFIX"

/**
 * Which of the folder's files to remove: the automatic copies past the newest [keep], newest by
 * name since the name is the day. Anything not named the way the worker names its files is not
 * its to delete — the person's own exports may well live in the same folder.
 *
 * The newest [keep] are counted apart on each side of a day in [startedOver], a day the record
 * began again. The worker goes on copying the empty record that follows, and counted together,
 * seven nights of that would remove every copy of the record that was lost — the copies the folder was
 * chosen for. Those stay until the person removes them; the copies made since rotate among
 * themselves.
 */
internal fun autoBackupsToPrune(
    names: Collection<String>,
    keep: Int = AutoBackup.KEEP,
    startedOver: Collection<String> = emptyList(),
    written: String? = null,
): List<String> {
    val current = written?.let(AUTO_NAME::matchEntire)?.groupValues?.get(2)
    val lastReset = startedOver.maxOrNull()
    val copies = names.distinct().mapNotNull(AUTO_NAME::matchEntire)
    return copies.groupBy { copy ->
        val (day, generation) = copy.destructured
        generation.ifEmpty {
            current?.takeIf { lastReset == null || day > lastReset }
                ?: startedOver.count { it < day }.toString()
        }
    }.flatMap { (generation, copiesInGeneration) ->
        // A different installation can be writing its own copy in this folder.
        if (current != null && generation != current && copiesInGeneration.first().groupValues[2].isNotEmpty()) {
            return@flatMap emptyList()
        }
        val newest = copiesInGeneration.sortedWith(
            compareByDescending<MatchResult> { it.value == written }
                .thenByDescending { it.groupValues[1] }
                .thenByDescending { it.groupValues[3].toLongOrNull() ?: 0L },
        )
        val kept = newest.distinctBy { it.groupValues[1] }.take(keep).toSet()
        newest.filter { it !in kept }.map { it.value }
    }
}

/**
 * Whole calendar days since the last export, or null when there has never been one. Calendar days
 * rather than 24-hour stretches: a copy made last evening is "yesterday" all of today, not "today"
 * until the same hour comes round.
 */
internal fun backupAgeDays(lastExportMs: Long?, today: LocalDate, zone: ZoneId): Long? {
    val last = lastExportMs ?: return null
    val day = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(day, today).coerceAtLeast(0L)
}

/** Whether the last copy of any kind is too old to count on: none ever, or none for over [AutoBackup.STALE_DAYS]. */
internal fun backupIsStale(ageDays: Long?): Boolean = ageDays == null || ageDays > AutoBackup.STALE_DAYS

/**
 * Whether the profile's front page should say something about backups: the last copy is stale, or
 * the daily one is switched on and its last run failed ([autoError], [AutoBackupState.error]).
 *
 * Said only under the switch, two pages in, a failed run would go unseen. The worker fails the same
 * way every night — a grant revoked, a card taken out, a phone set up by transfer — and somebody who
 * switched the copy on has no reason to look there again: the front page would keep quiet for the
 * thirty days a recent export still covered, and then suggest switching on the copy that was on.
 */
internal fun backupNeedsAttention(ageDays: Long?, autoError: String?): Boolean =
    autoError != null || backupIsStale(ageDays)

/**
 * The line the database-encryption switch owes somebody who also has the daily copy on, or null.
 *
 * The switch promises that reading the storage yields noise, and the copies are the same record in
 * plain text in a folder of the shared storage, or with a cloud provider. Each switch says its own
 * half; this line says the two together.
 */
@StringRes
internal fun plainCopyWarning(databaseEncrypted: Boolean, dailyCopyOn: Boolean): Int? =
    R.string.db_encrypt_daily_copy_plain.takeIf { databaseEncrypted && dailyCopyOn }

internal class AutoBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /**
     * Always [Result.success]. A folder whose grant was revoked fails the same way at every retry,
     * and WorkManager's backoff on a periodic job is a phone woken over and over for a folder that
     * is not coming back. The failure is written down where the settings row reads it instead.
     */
    override suspend fun doWork(): Result {
        val context = applicationContext
        val tree = AutoBackup.folder(context) ?: return Result.success()
        runCatching { writeAutomaticCopy(context, tree, LocalDate.now()) }
            .onSuccess { AutoBackup.recordRun(context, null) }
            .onFailure {
                if (it is CancellationException) throw it
                AutoBackup.recordRun(
                    context,
                    autoBackupFailureText(it, context.getString(R.string.backup_auto_folder_gone)),
                )
            }
        return Result.success()
    }
}

/** A row's worth of a folder listing: what to open it by, and what the person sees it called. */
private class DocumentChild(val id: String, val name: String)

/**
 * Publish a fresh document before pruning. A database reset in the same day starts its own
 * rotation, so the new record cannot replace the old record's last good copy.
 */
private suspend fun writeAutomaticCopy(context: Context, tree: Uri, today: LocalDate) {
    AutoBackup.renewGrant(context, tree)
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val db = StepsDb.get(context)
    // Opening Room can reset the database and its installation id.
    db.dao().profileNow()
    val generation = installId(context)
    val name = autoBackupName(today, generation, System.currentTimeMillis().coerceAtLeast(0L), UUID.randomUUID())
    rotateAutomaticCopy<Uri>(
        children = listChildren(context, tree).associate { it.name to it.id },
        name = name,
        startedOver = AutoBackup.startedOverDays(context),
        create = {
            DocumentsContract.createDocument(resolver, parent, "application/json", name)
                ?: throw FileNotFoundException(context.getString(R.string.backup_auto_folder_gone))
        },
        export = { target -> exportSpooled(context, db, target, null, generation) },
        delete = { id ->
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        },
    )
}

/**
 * [writeAutomaticCopy]'s steps in their order, apart from the folder they act on. [children] is the
 * folder's listing, a name to the id it is opened by. [create] makes the next document.
 *
 * The copy is written before anything is pruned, and a copy that failed prunes nothing: the old
 * copies are then all there is. A stale file that will not go — a provider that refuses the delete,
 * a file somebody has open — is left for the next day's pruning rather than reported as a failed
 * backup under a copy that succeeded.
 */
internal suspend fun <D> rotateAutomaticCopy(
    children: Map<String, String>,
    name: String,
    startedOver: Set<String>,
    create: () -> D,
    export: suspend (D) -> Unit,
    delete: (id: String) -> Unit,
) {
    export(create())
    autoBackupsToPrune(children.keys + name, startedOver = startedOver, written = name).forEach { stale ->
        children[stale]?.let { id -> runCatching { delete(id) } }
    }
}

private fun listChildren(context: Context, tree: Uri): List<DocumentChild> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )
    return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(DocumentChild(cursor.getString(0), cursor.getString(1)))
        }
    } ?: throw FileNotFoundException(context.getString(R.string.backup_auto_folder_gone))
}

/**
 * The line under the switch. A revoked grant arrives as a SecurityException whose message is the
 * platform's, so it is said in the app's words; anything else is repeated as it came.
 */
internal fun autoBackupFailureText(failure: Throwable, folderGone: String): String =
    if (failure is SecurityException) folderGone else failure.message ?: failure.javaClass.simpleName
