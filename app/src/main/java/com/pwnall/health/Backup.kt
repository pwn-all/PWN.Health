package com.pwnall.health

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import androidx.annotation.StringRes
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * The schema a backup is stamped with, raised only for what an older app cannot read safely.
 *
 * The reader skips members it does not know and merges the ones it does, so a member an older
 * reader can do without arrives under the current number: `externalStepDays` and `installId` came
 * under 7. A member an older reader does not survive quietly raises it, because a file that loses
 * its photographs in silence is worse than a file that is refused: an older app says
 * "unsupported version" and stops. Files of every schema from 1 on import here.
 *
 * Schema 8 is raised for three things a file of 7 can already carry: the progress photographs,
 * base64 rows an older reader skips with `skipValue` and drops without a word; the sealed
 * `PWNFITENC` container, which an older reader shows as a raw parse error rather than as a version
 * it does not know; and the `installId` whose absence changes which tombstones are believed.
 *
 * Schema 9 adds the chats. An older app would skip the member with `skipValue` and lose every
 * conversation without a word, and with it the only place a chat's words are kept, so it is refused
 * instead.
 *
 * Schema 10 adds the photographs taken on a walk. An older app would skip those base64 rows the
 * same way and bring every route back without its pictures, saying nothing, so it is refused too.
 *
 * Schema 11 adds the photographs of lab sheets. A panel's row names its sheet by a `content://`
 * reference into the app's own folder; without the folder in the file, a restore would bring the
 * row back pointing at nothing, and the report for the doctor would fail on it. An older app would
 * skip the member and lose every sheet the same quiet way, so it is refused.
 */
private const val BACKUP_SCHEMA = 11

/** Points are read in pages because the table grows at roughly a row a second while tracking. */
private const val TRACK_POINT_PAGE = 2_000

/**
 * What an export holds — or, after an import, what the merge changed, so that a second import of one
 * file says zero: [days] counts the days the file brought or raised the total of ([changedDays]),
 * [journalEntries] the rows it added.
 */
data class BackupSummary(
    val days: Int,
    val journalEntries: Int,
    /** Pictures whose file could not take its name once the rows had committed; their rows were not written. */
    val photosLost: Int = 0,
    /**
     * Entries the file held that were deleted here on purpose, and so stayed out ([withoutTombstoned]).
     * Said beside the count, or "0 records" is all a person learns who imported a copy to get one back.
     */
    val heldBack: Int = 0,
    /**
     * Journal rows the file held whose day does not read as one, and so stayed out ([withReadableDays]).
     * Counted for the same reason: a row left out without a word is a history that came back short.
     */
    val undated: Int = 0,
    /**
     * Work sessions the file held that share worked time with one here, and so stayed out
     * ([withoutOverlappingWork]). Counted because one may hold an hour the session here does not.
     */
    val overlapping: Int = 0,
)

class UnsupportedBackupVersionException : IllegalArgumentException()

/**
 * Which sentence a failed import is reported with.
 *
 * A file that stops early, and one that is not what this app writes, are said in the reader's own
 * language: a copy cut short on its way out of a cloud folder is the commonest damaged file there
 * is, and "truncated backup" inside a translated sentence would tell most people nothing. What the reader
 * itself refuses — a member missing, a number that is not one, a token out of place — is a damaged
 * or foreign file whichever class reports it. Anything else, a full disk or a provider that went
 * away, keeps the general line, which ends in the system's own words.
 */
@StringRes
internal fun importFailureRes(failure: Throwable): Int = when (failure) {
    is WrongBackupPasswordException -> R.string.profile_import_wrong_password

    is UnsupportedBackupVersionException -> R.string.profile_import_unsupported

    is TruncatedBackupException, is EOFException -> R.string.profile_import_truncated

    is JSONException, is MalformedJsonException, is DateTimeException,
    is IllegalArgumentException, is IllegalStateException,
    -> R.string.profile_import_damaged

    else -> R.string.profile_import_error
}

/** [importFailureRes] as text; the two sentences that end in the failure's own words are given them. */
internal fun importFailureText(resources: Resources, failure: Throwable): String =
    when (val sentence = importFailureRes(failure)) {
        R.string.profile_import_damaged, R.string.profile_import_error ->
            resources.getString(sentence, failure.message ?: failure.javaClass.simpleName)

        else -> resources.getString(sentence)
    }

/**
 * The lines an import's summary ends with: one when the file held entries deleted here, one when it
 * held rows whose day could not be read, one when it held work sessions overlapping one here, and
 * nothing where it held none of them.
 */
internal fun Resources.heldBackLine(summary: BackupSummary): String =
    (if (summary.heldBack > 0) "\n" + getString(R.string.backup_held_back, summary.heldBack) else "") +
        (if (summary.undated > 0) "\n" + getString(R.string.backup_undated, summary.undated) else "") +
        (if (summary.overlapping > 0) "\n" + getString(R.string.backup_overlapping_work, summary.overlapping) else "")

/**
 * What the export's password field says under itself: that the password is still too short, or
 * nothing. Said on the field, as a repeat that differs is — a button that stays grey does not say
 * what it is waiting for.
 */
internal fun exportPasswordError(resources: Resources, password: String): String? =
    resources.getString(R.string.profile_password_short, BackupCrypto.MIN_PASSWORD_LENGTH)
        .takeIf { exportPasswordTooShort(password) }

object LocalBackup {
    /**
     * One transaction around every read, including the paged one that runs while the file is being
     * written: a recording started between two of them would otherwise put points in the file whose
     * track is not, and the foreign key would refuse the whole restore years later.
     *
     * The same transaction lasts for as long as [output] takes to accept the rows, so the caller
     * hands over something local — a file in the cache — and copies it to wherever the backup is
     * going afterwards. A document on a cloud provider takes as long as its upload, and Room's
     * write lock held for that long is the pedometer's inserts and a route's start waiting on the
     * network. The pictures are written after the transaction, from the rows read inside it: their
     * bytes are files beside the database, and encoding a year of them as base64 would hold the same
     * lock for as long as that took.
     *
     * A read transaction would not spare the writers what wait is left: over an open helper, as
     * here, Room 2.8.5 runs every transaction, a reader's too, one at a time on its transaction
     * thread, and SQLCipher begins a read-only one as an exclusive one.
     *
     * [installId] names the installation whose row ids the file's tombstones carry. [photos],
     * [medicinePhotos], [walkPhotos], [routinePhotos] and [labCaptures] are the directories the
     * progress pictures, the courses' photographs, the ones taken on a walk, the templates' pictures
     * and the lab sheets' photographs are read from; without one, that kind is left out of the file.
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Every nullable field; a directory per kind of picture.
    suspend fun export(
        db: StepsDb,
        output: OutputStream,
        photos: File?,
        medicinePhotos: File?,
        walkPhotos: File?,
        installId: String,
        routinePhotos: File? = null,
        labCaptures: File? = null,
    ): BackupSummary = output.bufferedWriter().use { writer ->
        // Written a table and a row at a time: the whole database rendered as one string is more
        // than the heap holds once a phone has a year of tracks behind it.
        val pictured = db.withTransaction {
            val dao = db.dao()
            val profile = dao.profileNow()
            // `days` can contain pre-v15 totals of unknown origin. Keep their UI history together with
            // the provenance marker that prevents them from being exported as phone-owned data.
            val days = dao.allLocalDays()
            val directBleDays = dao.allDirectBleDays()
            val legacyStepDays = dao.allLegacyStepDays().map { it.day }
            val journal = dao.allJournal()
            val tracks = dao.allTracks()
            writer.write("{\"schema\":$BACKUP_SCHEMA")
            writer.write(",\"exportedAt\":${System.currentTimeMillis()}")
            writer.write(",\"installId\":${JSONObject.quote(installId)}")
            writer.write(",\"profile\":${profile?.toJson() ?: JSONObject.NULL}")
            writer.write(",\"legacyStepDays\":${JSONArray(legacyStepDays)}")
            writer.writeTable("days", days, DaySteps::toJson)
            writer.writeTable("appOpenDays", dao.allAppOpenDays(), AppOpenDay::toJson)
            writer.writeTable("externalStepDays", dao.allExternalDays(), ExternalDaySteps::toJson)
            writer.writeTable("externalStepOriginDays", dao.allExternalOriginDays(), ExternalOriginDaySteps::toJson)
            writer.writeTable("directBleStepDays", directBleDays, DirectBleDaySteps::toJson)
            writer.writeTable("journal", journal, JournalEntry::toJson)
            writer.writeTable("tracks", tracks, TrackEntity::toJson)
            writer.writeTrackPoints(dao)
            writer.writeTable("appointments", dao.allAppointments(), Appointment::toJson)
            writer.writeTable("routineExercises", dao.allRoutineExercisesNow(), RoutineExercise::toJson)
            writer.writeTable("hourlySteps", dao.allHourSteps(), HourSteps::toJson)
            writer.writeTable("barometerDays", dao.allBarometerDays(), BarometerDay::toJson)
            writer.writeTable("journalTombstones", dao.allTombstones(), JournalTombstone::toJson)
            writer.writeTable("favoriteFoods", dao.allFavoriteFoods(), FavoriteFood::toJson)
            writer.writeTable("medicalHistory", dao.allMedicalHistory(), MedicalHistoryRow::toJson)
            writer.writeTable("chats", dao.allChats(), ChatRow::toJson)
            writer.writeTable("savedDishes", dao.allSavedDishes(), SavedDish::toJson)
            PicturedRows(
                BackupSummary(days.size, journal.size),
                dao.allTrackPhotos(),
                // The two tables that hold a plan rather than a measurement — these and the visits
                // above. Without them a restore would return every reading and silently drop the
                // prescription that explains half of them.
                dao.allMedications(),
                dao.allRoutinesNow(),
                dao.allProgressPhotos(),
                labCaptureNames(journal),
            )
        }
        // Still after the routes and their points, which is all the reader asks of their order. A
        // picture whose file has gone since its row was read is left out, as one already gone is.
        writer.writeTrackPhotos(pictured.trackPhotos, walkPhotos)
        writer.writeTable("medications", pictured.medications) { it.toJson().withPhotoBytes(it.photo, medicinePhotos) }
        writer.writeTable("routines", pictured.routines) { it.toJson().withPhotoBytes(it.photo, routinePhotos) }
        writer.writeProgressPhotos(pictured.progressPhotos, photos)
        writer.writeLabCaptures(pictured.labCaptures, labCaptures)
        writer.write("}")
        pictured.summary
    }

    /**
     * The profile out of a backup, and nothing else.
     *
     * Its own action because it is the one table a merge cannot decide. Every other table either
     * accumulates, so the larger figure wins, or is a row that is present or absent. A profile is a
     * set of current answers with no record of when each was given, so restoring it is a
     * replacement — and replacement is a thing to be asked for, not a side effect of importing a
     * journal. Returns false when the file carries no profile.
     */
    suspend fun importProfile(db: StepsDb, input: InputStream): Boolean {
        val profile = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            reader.readSchemaFirst()
            var restored: UserProfile? = null
            while (reader.hasNext()) {
                if (reader.nextName() == "profile") {
                    restored = reader.nextNullableObject { it.toProfile().plausible() }
                } else {
                    reader.skipValue()
                }
            }
            reader.finishBackup()
            restored
        } ?: return false
        db.saveProfile(profile)
        return true
    }

    /**
     * [photos] is where restored progress photographs are written; without one they are read past,
     * because a row naming a file that does not exist is worse than no row. [medicinePhotos] is the
     * same for the courses' photographs, and a course restored without one comes back without its
     * picture; [walkPhotos] for the photographs taken on a walk, without which a route comes back
     * without them; [routinePhotos] for the templates' pictures; [labCaptures] for the lab sheets'
     * photographs, whose rows keep pointing at the names the sheets come back under. [installId] is
     * this installation's, against which the file's own is checked before a tombstone naming a row
     * by its id is believed: see [importableTombstones].
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Every optional list; a directory per kind of picture.
    suspend fun import(
        db: StepsDb,
        input: InputStream,
        photos: File?,
        medicinePhotos: File?,
        walkPhotos: File?,
        installId: String,
        routinePhotos: File? = null,
        labCaptures: File? = null,
    ): BackupSummary {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        // Photographs are parked beside their final names while the rows are in flight, and take
        // those names only once every row has committed: a transaction that rolls back — a tag
        // that fails on the last chunk, a malformed row — must leave no picture behind on disk
        // that no row will ever point at. See [importProgressPhotos].
        val staged = mutableListOf<StagedPhoto>()
        var committed = false
        val summary = try {
            importStream(
                db,
                reader,
                PhotoDirs(photos, medicinePhotos, walkPhotos, routinePhotos, labCaptures),
                staged,
                installId,
            ).also { committed = true }
        } finally {
            if (!committed) staged.forEach { it.part.delete() }
        }
        val photosLost = restoreStagedPhotos(db.dao(), staged)
        // Restored courses ring at their hours and the widgets show the restored figures now, rather
        // than after whatever the next write happens to be: the recalculation ends with both hooks.
        db.recalculateJournal()
        return summary.copy(photosLost = photosLost)
    }
}

/**
 * Opens the object and takes the schema, which every file this app has ever written puts first.
 *
 * First so that a file from a newer version is refused before a single row of it is applied, and so
 * that neither reader has to carry "have I seen the schema yet" through its whole loop.
 */
private fun JsonReader.readSchemaFirst(): Int {
    beginObject()
    if (!hasNext() || nextName() != "schema") throw UnsupportedBackupVersionException()
    val schema = nextInt()
    if (!isSupportedBackupSchema(schema)) throw UnsupportedBackupVersionException()
    return schema
}

/** Reading through EOF also authenticates an encrypted backup's empty final chunk. */
private fun JsonReader.finishBackup() {
    endObject()
    require(peek() == JsonToken.END_DOCUMENT) { "trailing data in backup" }
}

/**
 * Reads the backup one member at a time. Only bounded metadata tables stay in memory; track points
 * go straight to Room in [TRACK_POINT_PAGE] batches, and the dedup index holds one track at a time,
 * so nothing here scales with the file. Files written by this app always put `schema` first and
 * parents before their children.
 *
 * The caller stages the document locally first: this runs in one transaction, and reading a cloud
 * provider from inside it would hold the database open for as long as the network takes.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private suspend fun importStream(
    db: StepsDb,
    reader: JsonReader,
    dirs: PhotoDirs,
    staged: MutableList<StagedPhoto>,
    localInstallId: String,
): BackupSummary = db.withTransaction {
    val dao = db.dao()
    var fileInstallId: String? = null
    var days = emptyList<DaySteps>()
    var encodedLegacyDays: List<String>? = null
    var journal = emptyList<JournalEntry>()
    var undated = 0
    var medicationIds = emptyMap<Long, Long>()
    var routines = emptyList<JSONObject>()
    var exercises = emptyList<RoutineExercise>()
    var trackPlan: TrackImportPlan? = null

    // Whether the file is this very installation's: its ids are then this database's ids. The
    // header member is read before any table, in every file this app has written.
    fun sameInstall() = fileInstallId?.let { it == localInstallId } == true

    val schema = reader.readSchemaFirst()
    reader.use {
        while (it.hasNext()) {
            when (val name = it.nextName()) {
                "exportedAt" -> it.skipValue()

                "installId" -> fileInstallId = it.nextString()

                // Read past, never applied. The profile is one snapshot with no timestamps in it,
                // so a merge cannot tell which of two values is the newer one, and a restore into a
                // populated install would silently replace settings nobody asked it to touch.
                // [LocalBackup.importProfile] is where somebody asks for exactly that.
                "profile" -> it.skipValue()

                "legacyStepDays" -> encodedLegacyDays = it.nextStrings()

                "days" -> days = it.nextObjects(JSONObject::toDay)

                "appOpenDays" -> dao.restoreAppOpenDays(it.nextObjects(JSONObject::toAppOpenDay))

                // Health Connect's own totals, as this install last read them. Kept because a
                // restore may land on a phone whose Health Connect has none of it — a new device, a
                // provider that is not installed, a source app that is gone — and without them the
                // day totals lose whatever the watch contributed.
                "externalStepDays" -> {
                    val merged = mergeExternalDays(
                        dao.allExternalDays(),
                        it.nextObjects(JSONObject::toExternalDay),
                    )
                    if (merged.isNotEmpty()) dao.putExternalDays(merged)
                }

                // Who those totals came from. Carried for the same reason and merged by the same
                // rule; a reader that has never heard of this member skips it, which is what an
                // older app does with every table added since schema 7.
                "externalStepOriginDays" -> {
                    val merged = mergeExternalOriginDays(
                        dao.allExternalOriginDays(),
                        it.nextObjects(JSONObject::toExternalOriginDay),
                    )
                    if (merged.isNotEmpty()) dao.putExternalOriginDays(merged)
                }

                "directBleStepDays" -> {
                    val merged = mergeDirectBleDays(dao.allDirectBleDays(), it.nextObjects(JSONObject::toDirectBleDay))
                    if (merged.isNotEmpty()) dao.putDirectBleDays(merged)
                }

                "journal" -> {
                    val rows = it.nextObjects(JSONObject::toJournal)
                    journal = withReadableDays(rows)
                    undated = rows.size - journal.size
                }

                "tracks" -> trackPlan = prepareTrackImport(dao, it.nextObjects(JSONObject::toTrack))

                "trackPoints" -> importTrackPoints(
                    it,
                    dao,
                    trackPlan ?: throw IllegalArgumentException("trackPoints precede tracks"),
                )

                "trackPhotos" -> importTrackPhotos(
                    it,
                    dao,
                    trackPlan ?: throw IllegalArgumentException("trackPhotos precede tracks"),
                    dirs.walk,
                    staged,
                )

                "medications" -> medicationIds = importMedications(it, dao, dirs.medicines, staged, sameInstall())

                "appointments" -> importAppointments(dao, it.nextObjects(JSONObject::toAppointment), sameInstall())

                "routines" -> routines = it.nextObjects { row -> row }

                "routineExercises" -> exercises = it.nextObjects(JSONObject::toRoutineExercise)

                "hourlySteps" -> {
                    val merged = mergeHourSteps(dao.allHourSteps(), it.nextObjects(JSONObject::toHourSteps))
                    if (merged.isNotEmpty()) dao.putHours(merged)
                }

                "barometerDays" -> {
                    val merged = mergeBarometerDays(
                        dao.allBarometerDays(),
                        it.nextObjects(JSONObject::toBarometerDay),
                    )
                    if (merged.isNotEmpty()) dao.saveBarometerDays(merged)
                }

                "journalTombstones" -> {
                    val tombstones = importableTombstones(
                        it.nextObjects(JSONObject::toJournalTombstone),
                        fileInstallId,
                        localInstallId,
                    )
                    if (tombstones.isNotEmpty()) dao.saveTombstones(tombstones)
                }

                "savedDishes" -> {
                    val dishes = newerImportedRows(
                        dao.allSavedDishes(),
                        it.nextObjects(JSONObject::toSavedDish),
                        SavedDish::key,
                        SavedDish::createdAt,
                    )
                    if (dishes.isNotEmpty()) dao.saveSavedDishes(dishes)
                }

                "medicalHistory" -> {
                    // A line deleted here on purpose stays deleted: [deleteMedicalHistory] left its tombstone.
                    val records = withoutDeletedHistory(
                        newerImportedRows(
                            dao.allMedicalHistory(),
                            it.nextObjects(JSONObject::toMedicalHistoryRow),
                            MedicalHistoryRow::key,
                            MedicalHistoryRow::updatedAt,
                        ),
                        dao.allTombstones().mapTo(HashSet()) { tombstone -> tombstone.recordKey },
                    )
                    if (records.isNotEmpty()) dao.saveMedicalHistoryRows(records)
                }

                "chats" -> {
                    // Links inside a chat are moments, never ids, so the renumbering below leaves
                    // every one of them pointing at the record it named.
                    val chats = newerImportedRows(
                        dao.allChats(),
                        it.nextObjects(JSONObject::toChatRow),
                        ChatRow::key,
                        ChatRow::updatedAt,
                    )
                    if (chats.isNotEmpty()) dao.saveChats(chats)
                }

                "favoriteFoods" -> {
                    val favorites = newerImportedRows(
                        dao.allFavoriteFoods(),
                        it.nextObjects(JSONObject::toFavoriteFood),
                        FavoriteFood::key,
                        FavoriteFood::createdAt,
                    )
                    if (favorites.isNotEmpty()) dao.saveFavoriteFoods(favorites)
                }

                "progressPhotos" -> importProgressPhotos(it, dao, dirs.progress, staged)

                "labCaptures" -> importLabCaptures(it, dirs.labCaptures, staged)

                else -> it.skipValue()
            }
        }
        it.finishBackup()
    }
    val localDays = dao.allLocalDays()
    // The hours are in by now, merged hour by hour: see [withHourTotals].
    val mergedDays = withHourTotals(mergeDaySteps(localDays, days), dao.allHourSteps())
    mergedDays.forEach { dao.put(it) }
    val legacy = legacyStepDaysForBackupImport(schema, days.map(DaySteps::day), encodedLegacyDays)
    if (legacy.isNotEmpty()) dao.putLegacyStepDays(legacy.map(::LegacyStepDay))
    // The templates first: a plan in the journal names its template by id, and the id is this
    // database's only once the template is in.
    val routineIds = importRoutines(dao, routines, exercises, dirs.routines, staged, sameInstall())
    val added = importJournal(dao, journal, medicationIds, routineIds, sameInstall())
    BackupSummary(
        changedDays(localDays, mergedDays),
        added.inserted,
        heldBack = added.heldBack,
        undated = undated,
        overlapping = added.overlapping,
    )
}

/**
 * What a backup's rows mean for a database that may already hold some of them: [inserts] is the
 * content this install does not have yet, and [matched] maps a backed-up id to the local row that
 * already carries the same content.
 */
internal data class BackupMerge<T>(val inserts: List<T>, val matched: Map<Long, Long>)

internal fun mergeDaySteps(existing: List<DaySteps>, imported: List<DaySteps>): List<DaySteps> {
    val local = existing.associateBy(DaySteps::day)
    return imported.map { row -> row.copy(steps = maxOf(row.steps, local[row.day]?.steps ?: 0)) }
}

/**
 * [merged] raised, day by day, to what its [hours] add up to once the file's hours are merged in.
 *
 * The counter credits a day's total and its hour together, so on one phone the two agree. Two
 * phones that each counted part of a day — a move to a new one at lunchtime — do not: the hours
 * merge hour by hour, each from the phone that counted it, while the total takes the larger part
 * alone, and the day would come back short of its own hours.
 */
internal fun withHourTotals(merged: List<DaySteps>, hours: List<HourSteps>): List<DaySteps> {
    val counted = hours.groupingBy(HourSteps::day).fold(0L) { total, hour -> total + hour.steps }
    return merged.map { row -> row.copy(steps = maxOf(row.steps, counted[row.day] ?: 0L)) }
}

/**
 * How many of [merged], the rows [mergeDaySteps] answered, say something [existing] does not: a day
 * new to this phone, or a total the file raised. Counting every day in the file would have a second
 * import of one file report its four hundred days again, having changed none.
 */
internal fun changedDays(existing: List<DaySteps>, merged: List<DaySteps>): Int {
    val local = existing.associateBy(DaySteps::day)
    return merged.count { it.steps != local[it.day]?.steps }
}

/** Same rule as [mergeDaySteps]: one number per day, and the larger one saw more of the day. */
internal fun mergeExternalDays(
    existing: List<ExternalDaySteps>,
    imported: List<ExternalDaySteps>,
): List<ExternalDaySteps> {
    val local = existing.associateBy(ExternalDaySteps::day)
    return imported.map { row -> row.copy(steps = maxOf(row.steps, local[row.day]?.steps ?: 0)) }
}

/** The same rule again, one number per app and day: the larger figure saw more of the day. */
internal fun mergeExternalOriginDays(
    existing: List<ExternalOriginDaySteps>,
    imported: List<ExternalOriginDaySteps>,
): List<ExternalOriginDaySteps> {
    val local = existing.associateBy { it.packageName to it.day }
    return imported.map { row ->
        row.copy(steps = maxOf(row.steps, local[row.packageName to row.day]?.steps ?: 0))
    }
}

internal fun mergeHourSteps(existing: List<HourSteps>, imported: List<HourSteps>): List<HourSteps> {
    val local = existing.associateBy { it.day to it.hour }
    return imported.map { row ->
        row.copy(steps = maxOf(row.steps, local[row.day to row.hour]?.steps ?: 0))
    }
}

internal fun mergeDirectBleDays(
    existing: List<DirectBleDaySteps>,
    imported: List<DirectBleDaySteps>,
): List<DirectBleDaySteps> {
    val local = existing.associateBy { it.sourceId to it.day }
    return imported.map { row ->
        val held = local[row.sourceId to row.day] ?: return@map row
        when {
            held.updatedAt > row.updatedAt -> held
            held.updatedAt < row.updatedAt -> row
            held.steps >= row.steps -> held
            else -> row
        }
    }
}

internal fun mergeBarometerDays(
    existing: List<BarometerDay>,
    imported: List<BarometerDay>,
): List<BarometerDay> {
    val local = existing.associateBy(BarometerDay::day)
    return imported.map { row ->
        val held = local[row.day] ?: return@map row
        val pressure = if (held.pressureSamples >= row.pressureSamples) held else row
        pressure.copy(ascentM = maxOf(held.ascentM, row.ascentM))
    }
}

/**
 * The favourites or dishes in the file, less the ones a newer row already stands for here.
 *
 * Both tables are keyed by content — the catalogue id, the folded name — and stamped when saved,
 * and saving under a key that exists replaces the row, so of two rows under one key the later stamp
 * is the correction. The upsert alone would let the file's copy win regardless, and importing an old
 * backup would quietly put back the figures somebody had since fixed. A tie goes to the file.
 */
internal fun <T> newerImportedRows(
    existing: List<T>,
    imported: List<T>,
    key: (T) -> String,
    createdAt: (T) -> Long,
): List<T> {
    val local = existing.associateBy(key)
    return imported.filter { row -> (local[key(row)]?.let(createdAt) ?: Long.MIN_VALUE) <= createdAt(row) }
}

/**
 * The tombstones in a file this database may believe.
 *
 * A key of the `journal:<id>` kind names a row by the id this database gave it, and ids restart at 1
 * on every install. Read out of another phone's file, `journal:15` describes whichever row is the
 * fifteenth here — and the sync trusts that key to mean "deleted on purpose": it would refuse to
 * adopt that row's own Health Connect record, then sweep the record as stale, and the reading would
 * be gone from both stores. So a key naming a row is applied only when the file was written by this
 * very installation, which is what [fileInstallId] says; a file from before the id existed says
 * nothing, and is trusted with none of them. A marker key names a record by the id Health Connect
 * gave it, which is the same on every phone, so those always travel. So does a deleted route's
 * ([routeTombstoneKey]): the moment a recording started is what every phone's merge knows the route
 * by, and without its tombstone the route would come back on the next phone from any older file.
 * And so does the key of a row read out of an export file ([importMarker]), which names the service,
 * the fact and its wall clock and nothing of the phone that read it: left behind, the row deleted on
 * one phone would come back on the next from the same export. A deleted line of the medical history
 * travels too ([medicalHistoryTombstoneKey]): its key was made at random where the line was written,
 * so it names that line on every phone, and without it the line would come back from any older file.
 */
internal fun importableTombstones(
    imported: List<JournalTombstone>,
    fileInstallId: String?,
    localInstallId: String,
): List<JournalTombstone> {
    if (fileInstallId != null && fileInstallId == localInstallId) return imported
    return imported.filter { tombstone -> PORTABLE_TOMBSTONE_PREFIXES.any { tombstone.recordKey.startsWith(it) } }
}

/** What the keys that name the same thing on every installation begin with. */
private val PORTABLE_TOMBSTONE_PREFIXES = listOf(
    HEALTH_IMPORT_MARKER_PREFIX,
    FILE_IMPORT_MARKER_PREFIX,
    ROUTE_TOMBSTONE_PREFIX,
    MEDICAL_HISTORY_TOMBSTONE_PREFIX,
)

/**
 * The name this installation signs its backups with, so a file of its own can be told from a
 * neighbour's: a random id made once. A fresh install gets a fresh one, since its rows are numbered
 * from 1 again.
 *
 * So does a phone set up from another by a device-to-device transfer, which is why the id is kept
 * in the one folder no transfer reaches rather than in the preferences, which move with the
 * database. An id that moved would be right for the phone that took over and wrong once both went
 * on being used: each would give the same new ids to rows of its own under the one name, and a file
 * from either would be matched by id against the other's unrelated rows — an entry dropped as
 * already here, a dose moved to another course, a tombstone believed against a row it never
 * described, an insert under an id already taken that fails the whole import. Under two names the
 * old phone's files merge into the new one by content, as any other phone's do.
 */
@Synchronized
internal fun installId(context: Context): String = installIdIn(File(context.noBackupFilesDir, INSTALL_ID_FILE))

/**
 * The id [file] holds, or a new one written there. A disk that will not take it is no reason to
 * fail an export: the id is answered all the same, the next call makes another, and a file signed
 * with a name nothing recognises is merged by content, which is the careful way.
 */
internal fun installIdIn(file: File): String =
    runCatching { file.readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty)
        ?: UUID.randomUUID().toString().also { id -> runCatching { file.writeText(id) } }

/**
 * Forgets the name, for a database that is starting over: one set aside because no key here opens
 * it, or one quarantined as corrupt. The fresh database hands out ids from 1 again, and a file
 * signed with the old name would have its `journal:<id>` tombstones believed against rows they
 * never described — the very collision [importableTombstones] exists to refuse.
 *
 * What the Health Connect exchange remembers goes with it ([forgetHealthSyncState]). It describes
 * the same journal, and every place that starts a database over comes through here, so the two
 * cannot be forgotten apart. For the same reason the daily copy is told here: the copies made up to
 * today are the last of the record that was lost ([AutoBackup.markStartedOver]).
 */
internal fun forgetInstallId(context: Context) {
    File(context.noBackupFilesDir, INSTALL_ID_FILE).delete()
    forgetHealthSyncState(context)
    AutoBackup.markStartedOver(context)
}

/** The one preferences file [AutoBackup]'s keys live in. The install id is not among them: see [installId]. */
internal fun backupPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

private const val BACKUP_PREFS = "backup"
private const val INSTALL_ID_FILE = "install-id"

/**
 * The backup written into the cache first and copied to the document only afterwards.
 *
 * [LocalBackup.export] renders its rows inside one Room transaction, and that transaction lasts as
 * long as its stream takes to accept them. Handed the document's own stream, it would last as long as
 * the upload when the document lives on a cloud provider, and every write in the app — the pedometer's
 * inserts, a route's start — would wait on the network behind the write lock. The cache is local, so the
 * lock is held for the rendering alone; the copy that follows holds nothing. A failure removes the
 * spool, and the document only where this call is what spoiled it: the empty one the picker just
 * made, or the half that got across once the document was opened for writing. A document that
 * still holds yesterday's copy, because the failure came before it was touched, keeps it.
 *
 * The spool is opened for reading before a byte of it is rendered, held open until the copy is done,
 * and its name removed the moment both ends are open. A file removed while it is held open is still
 * whole for whoever holds it, and gone with the last of them, so the record never lies in the cache
 * under a name: a plain export is the whole of it in plain text, and the database-encryption switch
 * promises that what can be read off the storage is noise. Named, it would stay there whenever the
 * process died mid-export — the nightly worker stopped, the app swiped away — until a fresh start
 * swept it an hour later at the soonest ([sweepExportLeftovers]).
 *
 * Every door out of the app comes through here — the plain export, the sealed one, the automatic
 * copy — so this is where a copy that made it across is put on record, and the "last backup" line
 * cannot disagree with what the pickers wrote.
 */
internal suspend fun exportSpooled(
    context: Context,
    db: StepsDb,
    uri: Uri,
    password: CharArray?,
    generation: String? = null,
): BackupSummary = withContext(Dispatchers.IO) {
    spooledExport(
        context.cacheDir,
        render = { raw ->
            val sink = if (password == null) raw else BackupCrypto.encrypting(password, raw)
            sink.use {
                LocalBackup.export(
                    db,
                    it,
                    progressPhotoDir(context),
                    medicinePhotoDir(context),
                    walkPhotoDir(context),
                    generation ?: installId(context),
                    routinePhotoDir(context),
                    labCaptureDir(context),
                )
            }
        },
        open = { context.contentResolver.openOutputStream(uri, "wt")!! },
        documentIsEmpty = { documentIsEmpty(context, uri) },
        discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
    ).also { AutoBackup.markExported(context) }
}

/**
 * [exportSpooled]'s steps in their order, apart from the provider they act on: [render] into a spool
 * in [spoolDir] that has no name by the time a byte reaches it, then [open] the document and copy
 * the spool across. A failure removes the document ([discard]) once it was opened, or when it is
 * still empty ([documentIsEmpty]); a discard the provider refuses does not hide that failure.
 */
internal suspend fun <T> spooledExport(
    spoolDir: File,
    render: suspend (OutputStream) -> T,
    open: () -> OutputStream,
    documentIsEmpty: () -> Boolean,
    discard: () -> Unit,
): T {
    var spool: File? = null
    var opened = false
    return runCatching {
        val cached = File.createTempFile("backup", ".out", spoolDir).also { spool = it }
        // The writer first, then the reader: both then hold the one file, whatever becomes of its name.
        cached.outputStream().use { raw ->
            cached.inputStream().use { rendered ->
                cached.delete()
                val summary = render(raw)
                open().use { document ->
                    // Only now is the document no longer what it was: a provider that refuses to
                    // open it has left yesterday's copy in it, and that copy is not to be deleted.
                    opened = true
                    rendered.copyTo(document)
                }
                summary
            }
        }
    }.onFailure {
        if (opened || documentIsEmpty()) runCatching { discard() }
    }.also { spool?.delete() }.getOrThrow()
}

/** Whether the document holds nothing yet; one that cannot be read is left as it is. */
private fun documentIsEmpty(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.read() == -1 } ?: false
}.getOrDefault(false)

/**
 * Splits [imported] against [existing] by content rather than by primary key.
 *
 * Autoincrement ids restart at 1 on every install, so merging by id overwrites unrelated local
 * rows; merging by content leaves them alone and still makes a second import of the same file a
 * no-op, which is all that carrying the ids would buy.
 *
 * A file this installation signed is the exception, [byId]: its ids are the ids this database
 * gave, and they are never reused, so the row here under the file's id is the same row — corrected
 * since, perhaps, which is exactly when its content no longer matches and a second copy of the
 * old version would otherwise be put back beside the correction. [sameRow] is one more thing the
 * two must agree on before the id is believed; a database started over under an old signature
 * hands the same numbers to unrelated rows, and a kind that differs gives that away.
 */
internal fun <T> backupMerge(
    existing: List<T>,
    imported: List<T>,
    id: (T) -> Long,
    byId: Boolean = false,
    sameRow: (here: T, filed: T) -> Boolean = { _, _ -> true },
    key: (T) -> Any,
): BackupMerge<T> {
    val local = existing.associateBy(key)
    val ownIds = if (byId) existing.associateBy(id) else emptyMap()
    val inserts = mutableListOf<T>()
    val matched = mutableMapOf<Long, Long>()
    imported.forEach { row ->
        val match = ownIds[id(row)]?.takeIf { sameRow(it, row) } ?: local[key(row)]
        if (match == null) inserts += row else matched[id(row)] = id(match)
    }
    return BackupMerge(inserts, matched)
}

// A row's identity across installs: the record it was imported from, or the moment it describes and
// what it says about it. Two foreign records of one instant reach the journal as two rows the
// content key cannot tell apart — the import keys them by their marker alone — and a marker is the
// same on every phone, so a marked row is named by it here too. A String never equals a List, so
// the two families cannot collide.
internal fun journalKey(entry: JournalEntry): Any =
    entry.healthConnectRecordKey
        ?: listOf(entry.occurredAt, entry.kind, entry.title, entry.measurementPayloadJson)

private fun medicationKey(row: Medication): Any = listOf(row.name, row.startDay, row.createdAt)

private fun appointmentKey(row: Appointment): Any = listOf(row.specialty, row.atMs, row.doctor)

private fun routineKey(row: Routine): Any = listOf(row.name, row.createdAt)

/**
 * Returns the course each backed-up prescription ended up under, whether restored or already here.
 *
 * Streamed as the progress photographs are: a row may carry its picture, and every course ever
 * kept, the ended ones too, is in the file. Each picture's bytes go to a `.part` beside where it
 * will live the moment its row is read, so the heap holds one at a time; only the rows themselves
 * are kept for the merge.
 *
 * A picture is parked in [staged] under a name no picture here holds yet, and the course is given
 * that name only once the file has it (see [restoredMedicinePhotoName] for which courses get one).
 * A course whose picture cannot come with it — no bytes in the file, or nowhere to put them —
 * comes back without one rather than naming a file that is not there. A part that is not wanted,
 * or that a failure leaves unstaged, is deleted here; parts left by a process that died
 * mid-import are swept before the next one begins.
 */
private suspend fun importMedications(
    reader: JsonReader,
    dao: StepsDao,
    dir: File?,
    staged: MutableList<StagedPhoto>,
    sameInstall: Boolean,
): Map<Long, Long> {
    dir?.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
    val imported = mutableListOf<Medication>()
    // The backed-up course's id → its picture, already out of the heap and on disk.
    val parts = mutableMapOf<Long, File>()
    try {
        reader.beginArray()
        while (reader.hasNext()) {
            val json = reader.nextObject()
            val row = json.toMedication()
            imported += row
            val jpeg = json.optString("jpeg").takeIf(String::isNotEmpty)
            if (dir == null || jpeg == null || row.photo.isEmpty()) continue
            dir.mkdirs()
            val part = File(dir, "course-${imported.size}$PART_SUFFIX")
            part.writeBytes(Base64.getDecoder().decode(jpeg))
            parts.put(row.id, part)?.delete()
        }
        reader.endArray()
        val existing = dao.allMedications()
        // The moment a course was written down survives every edit of it, so a course here under the
        // file's id but of another moment is an unrelated one, and the file's goes in beside it.
        val merge = backupMerge(
            existing,
            imported,
            Medication::id,
            byId = sameInstall,
            sameRow = { here, filed -> here.createdAt == filed.createdAt },
            key = ::medicationKey,
        )
        val ids = merge.matched.toMutableMap()
        val taken = existing.mapTo(HashSet(), Medication::id)
        // A row of this install's own comes back under its own id: the doses that point at it, and
        // the Health Connect record published under it, then find it where they left it.
        insertIds(merge.inserts, Medication::id, taken, sameInstall).forEach { (row, id) ->
            ids[row.id] = dao.insertMedication(row.copy(id = id, photo = ""))
        }
        if (dir == null) return ids
        val here = existing.associateBy(Medication::id)
        val held = existing.map(Medication::photo).toMutableSet()
        val pictured = mutableSetOf<Long>()
        imported.forEach { row ->
            val part = parts[row.id] ?: return@forEach
            val courseId = ids.getValue(row.id)
            if (courseId in pictured) return@forEach
            val name = restoredMedicinePhotoName(
                backup = row.photo,
                here = here[courseId]?.photo,
                lands = { File(dir, it).isFile },
                taken = { it in held || File(dir, it).exists() },
            ) ?: return@forEach
            held += name
            pictured += courseId
            staged += StagedPhoto(row = null, part = part, target = File(dir, name), courseId = courseId)
        }
        return ids
    } finally {
        val kept = staged.mapTo(mutableSetOf(), StagedPhoto::part)
        parts.values.filterNot(kept::contains).forEach(File::delete)
    }
}

/**
 * How many rows the file added to the journal, and how many more it held that stayed out: deleted
 * here ([withoutTombstoned]), or work overlapping a session here ([withoutOverlappingWork]).
 */
private class JournalAdded(val inserted: Int, val heldBack: Int, val overlapping: Int)

private suspend fun importJournal(
    dao: StepsDao,
    imported: List<JournalEntry>,
    medicationIds: Map<Long, Long>,
    routineIds: Map<Long, Long>,
    sameInstall: Boolean,
): JournalAdded {
    val pointed = imported.map { entry ->
        // Only a marked dose points at a course. A glucose reading carries a medicationId of its
        // own — the GATT field — and rewriting that would change what the meter measured. A plan
        // points at its template the same way.
        when (entry.kind) {
            KIND_MED -> entry.copy(
                measurementPayloadJson = remappedDosePayload(entry.measurementPayloadJson, medicationIds),
            )

            KIND_PLAN -> entry.copy(
                measurementPayloadJson = remappedPlanPayload(entry.measurementPayloadJson, routineIds),
            )

            else -> entry
        }
    }
    val here = dao.allJournal()
    val merge = backupMerge(
        here,
        pointed,
        JournalEntry::id,
        byId = sameInstall,
        sameRow = { held, filed -> held.kind == filed.kind },
        key = ::journalKey,
    )
    val tombstoned = dao.allTombstones().mapTo(HashSet()) { it.recordKey }
    val kept = withoutTombstoned(merge.inserts, tombstoned, sameInstall)
    val inserts = withoutOverlappingWork(kept, here)
    if (inserts.isNotEmpty()) dao.insertJournalEntries(journalInserts(inserts, here, sameInstall))
    return JournalAdded(inserts.size, merge.inserts.size - kept.size, kept.size - inserts.size)
}

/**
 * [rows] as they are inserted: under their own ids where the file is this install's ([sameInstall])
 * — the record each was published to Health Connect as is named by that id, and comes back as the
 * same record rather than a new one — and numbered afresh otherwise.
 *
 * Afresh as well where a row [here] already holds the id. [backupMerge] believes an id only for a row
 * of the same kind, so a row of another kind under it is an unrelated one — a database started over
 * under an old signature, a file that claims this install's name, which is no secret — and the insert
 * under a taken id would fail the whole import on the table's key.
 */
internal fun journalInserts(
    rows: List<JournalEntry>,
    here: List<JournalEntry>,
    sameInstall: Boolean,
): List<JournalEntry> = insertIds(rows, JournalEntry::id, here.mapTo(HashSet(), JournalEntry::id), sameInstall)
    .map { (row, id) -> row.copy(id = id) }

/**
 * Each of [rows] with the id it is inserted under, in the order to insert them: its own where the file
 * is this install's ([sameInstall]) and nothing [taken] here holds it, 0 to be numbered afresh
 * otherwise. The rows keeping their own go in first: a fresh number is the one after the highest in
 * use, and handed out before a later row's own id went in, it could be that id, and the import would
 * fail on the table's key.
 */
private fun <T> insertIds(rows: List<T>, id: (T) -> Long, taken: Set<Long>, sameInstall: Boolean): List<Pair<T, Long>> =
    rows.map { it to if (sameInstall && id(it) !in taken) id(it) else 0L }.sortedBy { it.second == 0L }

/**
 * The file's new rows less the work sessions that share worked time with one here.
 *
 * The editor refuses such a session ([workSaveProblem]), because every per-day total would count the
 * shared time twice, and a restore would go round it. A file from another installation is merged by
 * content, so a session corrected here since that file was written is not the row the file holds,
 * and the old version would come back beside the correction. Only what is here counts: a file's own
 * sessions were kept apart by the app that wrote it.
 */
internal fun withoutOverlappingWork(rows: List<JournalEntry>, here: List<JournalEntry>): List<JournalEntry> {
    val worked = here.filter { it.kind == KIND_WORK }.flatMap(::workSegmentsOf)
    return rows.filterNot { row ->
        row.kind == KIND_WORK &&
            workSegmentsOf(row).any { mine -> worked.any { mine.from < it.to && it.from < mine.to } }
    }
}

/**
 * The file's journal rows whose day reads as a day.
 *
 * Every page finds a row through its day: the calendar, the day list and the report all ask for a
 * date. A row filed under anything else — a damaged cell, a file another program wrote — would be
 * inserted all the same and then stand in the database where no page shows it and nothing can
 * delete it, counted as restored. This app writes the day itself, so a file of its own loses nothing.
 */
internal fun withReadableDays(rows: List<JournalEntry>): List<JournalEntry> =
    rows.filter { it.day.toLocalDateOrNull() != null }

/**
 * The file's new rows less the ones deleted here on purpose: a backup must not resurrect them.
 *
 * A row from elsewhere is named by its marker, the same guard the Health Connect import applies. A
 * row this installation wrote is named by its id, which is what [deleteJournalEvent] leaves behind
 * — and the ids are never reused, so in a file this very installation signed ([sameInstall]) a row
 * still carrying id 15 after row 15 was deleted here is the older copy of that row, not a new one.
 * Another installation's 15 is whichever row was fifteenth there, and says nothing.
 */
internal fun withoutTombstoned(
    rows: List<JournalEntry>,
    tombstoned: Set<String>,
    sameInstall: Boolean,
): List<JournalEntry> = rows.filterNot { entry ->
    entry.healthConnectRecordKey?.let { it in tombstoned } == true ||
        (sameInstall && journalRecordKey(entry.id) in tombstoned)
}

private suspend fun importAppointments(dao: StepsDao, imported: List<Appointment>, sameInstall: Boolean) {
    backupMerge(dao.allAppointments(), imported, Appointment::id, byId = sameInstall, key = ::appointmentKey)
        .inserts
        .forEach { dao.insertAppointment(if (sameInstall) it else it.copy(id = 0)) }
}

/**
 * The local id each track in the file was given here, and the keys of one track's stored points.
 *
 * One track's worth, not every matched track's: the point table has no bound and a restore onto the
 * install that wrote the file matches all of it, so holding every key at once would trade the heap
 * the streaming reader just gave back. A file records one track at a time, so the walk through
 * `trackPoints` changes track a handful of times, and re-reading a track that comes back later is
 * correct anyway — the rows already inserted are in the same transaction.
 */
private class TrackImportPlan(val ids: Map<Long, Long>) {
    var cachedTrack = -1L
    var cachedKeys = mutableSetOf<Any>()
}

private suspend fun prepareTrackImport(
    dao: StepsDao,
    tracks: List<TrackEntity>,
): TrackImportPlan {
    val local = dao.allTracks()
    // A route deleted here on purpose stays deleted: [deleteWalk] left its start as a tombstone, and
    // a file written before that must not bring it back. Its fixes and its photographs stay out
    // with it, since neither is taken for a route this plan holds no id for.
    val kept = withoutDeletedRoutes(tracks, dao.allTombstones().mapTo(HashSet()) { it.recordKey })
    val merge = backupMerge(local, kept, TrackEntity::id) { it.startedAt }
    val ids = merge.matched.toMutableMap()
    merge.inserts.forEach { ids[it.id] = dao.insertTrack(it.copy(id = 0)) }
    reconcileMatchedTracks(dao, local, tracks, merge.matched)
    return TrackImportPlan(ids)
}

/**
 * The summary of a recording both installs hold, when the file's copy saw more of it.
 *
 * Points are merged into a track that was already here, but distance, elevation, the category
 * splits and the maximum speed are not in those rows: they come from the live filter and its
 * classifier as the walk happened, and neither keeps enough state in the table to be recomputed
 * afterwards. So the summary is chosen rather than recalculated, on the one figure that says which
 * copy watched more of the route — [TrackEntity.points], the count of fixes the filter accepted.
 * A tie leaves the local row alone, and a merge that adds nothing changes nothing.
 */
private suspend fun reconcileMatchedTracks(
    dao: StepsDao,
    local: List<TrackEntity>,
    imported: List<TrackEntity>,
    matched: Map<Long, Long>,
) {
    val fuller = fullerMatchedTracks(local, imported, matched)
    if (fuller.isNotEmpty()) dao.saveTracks(fuller)
}

internal fun fullerMatchedTracks(
    local: List<TrackEntity>,
    imported: List<TrackEntity>,
    matched: Map<Long, Long>,
): List<TrackEntity> {
    val here = local.associateBy(TrackEntity::id)
    return imported.mapNotNull { file ->
        val standing = matched[file.id]?.let(here::get) ?: return@mapNotNull null
        file.copy(id = standing.id).takeIf { file.points > standing.points }
    }
}

private suspend fun importTrackPoints(
    reader: JsonReader,
    dao: StepsDao,
    plan: TrackImportPlan,
) {
    val batch = ArrayList<TrackPointEntity>(TRACK_POINT_PAGE)
    suspend fun flush() {
        if (batch.isNotEmpty()) {
            dao.insertPoints(batch)
            batch.clear()
        }
    }
    reader.beginArray()
    while (reader.hasNext()) {
        val point = reader.nextObject().toTrackPoint()
        val localId = plan.ids[point.trackId]
        if (localId != null) {
            if (localId != plan.cachedTrack) {
                // Buffered rows have to reach the table before its keys are read back, or a point
                // still waiting in the batch would not be seen as one this import already took.
                flush()
                plan.cachedTrack = localId
                plan.cachedKeys = dao.trackPointsNow(localId).mapTo(mutableSetOf(), ::trackPointKey)
            }
            if (plan.cachedKeys.add(trackPointKey(point))) {
                batch += point.copy(id = 0, trackId = localId)
            }
        }
        if (batch.size >= TRACK_POINT_PAGE) flush()
    }
    reader.endArray()
    flush()
}

/**
 * The photographs taken on a walk, streamed and parked as the progress photographs are: the bytes
 * go to a `.part` beside where the picture will live, and the row waits in [staged] for the
 * transaction to commit. Each comes back beside the route this phone holds it under — [plan] knows
 * by now, because the member follows `trackPoints` in the file. One this phone already has is
 * skipped, so a second import adds nothing; so is one without bytes. With nowhere to put them, the
 * whole member is read past.
 *
 * Only what this phone held before the file was opened counts as had. Two rows of the file under
 * one walk and one moment are two pictures — the moment is the camera app's own stamp when it wrote
 * one, which may name the second and nothing finer — so both come back, the second under the next
 * free name. A row here whose file has gone is not had: it takes the first such picture back under
 * its own name, as a progress photograph's row does.
 */
private suspend fun importTrackPhotos(
    reader: JsonReader,
    dao: StepsDao,
    plan: TrackImportPlan,
    dir: File?,
    staged: MutableList<StagedPhoto>,
) {
    if (dir == null) {
        reader.skipValue()
        return
    }
    dir.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
    val here = dao.allTrackPhotos()
    val held = here.map { it.trackId to it.takenAt }.toSet()
    val lost = here.filterNot { File(dir, it.file).isFile }.associateBy { it.trackId to it.takenAt }.toMutableMap()
    val names = here.mapTo(mutableSetOf(), TrackPhotoEntity::file)

    // What a row that brought its bytes comes back as: a lost picture's own row, or one under a free name.
    fun landing(filed: TrackPhotoEntity): TrackPhotoEntity? =
        plan.ids[filed.trackId]?.let { lost.remove(it to filed.takenAt) }
            ?: restoredTrackPhoto(filed, plan.ids, held) { it in names || File(dir, it).exists() }
    reader.beginArray()
    while (reader.hasNext()) {
        val row = reader.nextObject()
        val jpeg = row.optString("jpeg")
        val photo = row.takeIf { jpeg.isNotEmpty() }?.let { landing(it.toTrackPhoto()) } ?: continue
        names += photo.file
        dir.mkdirs()
        val part = File(dir, photo.file + PART_SUFFIX)
        part.writeBytes(Base64.getDecoder().decode(jpeg))
        staged += StagedPhoto(row = null, part = part, target = File(dir, photo.file), trackPhoto = photo)
    }
    reader.endArray()
}

/**
 * What a backed-up walk photograph comes back as, or null when it stays out: its route is not in
 * the file, or this phone already has a photograph of that route at that moment. [trackIds] maps
 * the file's routes to the ids they have here, and [held] holds a route and a moment for every
 * photograph here. The name is the one [toTrackPhoto] derived from the moment, moved on to the next
 * free stamp — the rule a course's picture is restored under — when [taken] says a picture already
 * holds it: a restore never writes over another photograph.
 */
internal fun restoredTrackPhoto(
    photo: TrackPhotoEntity,
    trackIds: Map<Long, Long>,
    held: Set<Pair<Long, Long>>,
    taken: (String) -> Boolean,
): TrackPhotoEntity? = trackIds[photo.trackId]
    ?.takeUnless { trackId -> (trackId to photo.takenAt) in held }
    ?.let { trackId -> photo.copy(trackId = trackId, file = freeMedicinePhotoName(photo.file, taken)) }

/**
 * Templates are matched by name and moment. One this phone does not have yet comes with its picture,
 * parked beside its final name until the import has committed, as a course's is, and with its
 * exercises ([restoredRoutineExercises]); one already here keeps its own of both.
 *
 * Limitation: the pictures stay in the heap as text until the merge, all at once, which suits a
 * handful of templates; [importMedications] streams its pictures because a cabinet of courses can
 * hold hundreds.
 */
private suspend fun importRoutines(
    dao: StepsDao,
    rows: List<JSONObject>,
    exercises: List<RoutineExercise>,
    dir: File?,
    staged: MutableList<StagedPhoto>,
    sameInstall: Boolean,
): Map<Long, Long> {
    val routines = rows.map(JSONObject::toRoutine)
    val jpegs = rows.associate { it.getLong("id") to it.optString("jpeg") }
    val here = dao.allRoutinesNow()
    // As a course's: the moment a template was made survives its edits, and tells an unrelated one apart.
    val merge = backupMerge(
        here,
        routines,
        Routine::id,
        byId = sameInstall,
        sameRow = { held, filed -> held.createdAt == filed.createdAt },
        key = ::routineKey,
    )
    val ids = merge.matched.toMutableMap()
    val taken = here.mapTo(HashSet(), Routine::id)
    dir?.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
    insertIds(merge.inserts, Routine::id, taken, sameInstall).forEach { (row, wanted) ->
        val id = dao.insertRoutine(row.copy(id = wanted, photo = ""))
        ids[row.id] = id
        val jpeg = jpegs[row.id].orEmpty()
        if (dir == null || jpeg.isEmpty() || row.photo.isEmpty()) return@forEach
        dir.mkdirs()
        val part = File(dir, "routine-$id$PART_SUFFIX").apply { writeBytes(Base64.getDecoder().decode(jpeg)) }
        val name = freeMedicinePhotoName(row.photo) { taken ->
            File(dir, taken).exists() || staged.any { it.target == File(dir, taken) }
        }
        staged += StagedPhoto(row = null, part = part, target = File(dir, name), routineId = id)
    }
    val inserted = merge.inserts.associate { it.id to ids.getValue(it.id) }
    restoredRoutineExercises(exercises, inserted).forEach { dao.insertRoutineExercise(it) }
    return ids
}

/**
 * The file's exercises that go in, each under the id its template was given here: those of the
 * templates this import brought — [inserted], the file's id to the one here — and of no other.
 *
 * A template already here keeps its exercises as it keeps its picture. The editor writes every row
 * of a template anew at each save, so an exercise changed since the file was made no longer equals
 * the file's, and merging the two lists by content would put the old version back beside the
 * correction, and an exercise taken out back in.
 */
internal fun restoredRoutineExercises(
    exercises: List<RoutineExercise>,
    inserted: Map<Long, Long>,
): List<RoutineExercise> = exercises.mapNotNull { row ->
    inserted[row.routineId]?.let { row.copy(id = 0, routineId = it) }
}

internal fun trackPointKey(row: TrackPointEntity): Any = listOf(
    row.timestamp,
    row.lat,
    row.lon,
    row.elapsedRealtimeNanos,
)

/** The course reference inside a marked dose's payload, in the shape [medTakenEntry] writes it. */
private val DOSE_COURSE_REFERENCE = Regex("""("$MED_COURSE_KEY"$SPACE*:$SPACE*)([0-9]+)""")

/**
 * Points a restored dose at the course this database gave the imported prescription. Matched as
 * text rather than reparsed because org.json is a stub on the JVM these tests run on.
 */
internal fun remappedDosePayload(payload: String?, medicationIds: Map<Long, Long>): String? {
    if (payload == null) return null
    return DOSE_COURSE_REFERENCE.replace(payload) { match ->
        val restored = match.groupValues[2].toLongOrNull()?.let(medicationIds::get)
        // A course the file did not carry: kept as written, its id would name whatever local row
        // happens to hold it, so the dose is pointed at no course at all instead.
        "${match.groupValues[1]}${restored ?: 0L}"
    }
}

/**
 * A restored plan pointed at its template under the id this database gave the template. One whose
 * template the file did not carry points at none, as a dose does, rather than at whichever template
 * here happens to hold that id — deleting that one would take the plan with it.
 */
internal fun remappedPlanPayload(payload: String?, routineIds: Map<Long, Long>): String? {
    val json = payload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return payload
    if (!json.has(PLAN_ROUTINE_KEY)) return payload
    return json.put(PLAN_ROUTINE_KEY, routineIds[json.optLong(PLAN_ROUTINE_KEY)] ?: 0L).toString()
}

/**
 * Writes one `"name":[…]` member a row at a time, so the rendered table never exists as a whole.
 */
private fun <T> Writer.writeTable(name: String, rows: List<T>, toJson: (T) -> JSONObject) {
    write(",\"$name\":[")
    rows.forEachIndexed { index, row ->
        if (index > 0) write(",")
        write(toJson(row).toString())
    }
    write("]")
}

/**
 * The progress photographs themselves, not their names: a file name means nothing on another
 * phone, so the bytes ride in the row as base64. One row at a time like every other table — a
 * year of weekly pictures is tens of megabytes, which is fine to stream and not fine to hold. A
 * row whose file has gone is left out rather than restored as a picture of nothing.
 */
private fun Writer.writeProgressPhotos(rows: List<ProgressPhoto>, dir: File?) =
    writePhotoRows(",\"progressPhotos\":[", rows, dir, ProgressPhoto::file, ProgressPhoto::toJson)

/**
 * The photographs taken on a walk, bytes and all, the way the progress photographs travel. Written
 * after the points, so that the reader knows by then where each route of the file went; a row
 * whose file has gone is left out as a progress photograph's is.
 */
internal fun Writer.writeTrackPhotos(rows: List<TrackPhotoEntity>, dir: File?) =
    writePhotoRows(",\"trackPhotos\":[", rows, dir, TrackPhotoEntity::file, TrackPhotoEntity::toJson)

/**
 * The lab sheets' photographs [names], bytes and all. The rows that name them keep their
 * `content://` reference as it is: the name under the app's own authority is the same on the phone
 * the file comes back to, so a sheet restored under its name is found by the reference already in
 * the row. Only the files a panel still names travel; a capture nobody saved stays where it is, and
 * a named file that has gone is left out rather than restored as a picture of nothing.
 *
 * The row is written piece by piece, the bytes a slice at a time ([writeBase64]). A sheet is the
 * largest picture the app keeps ([LAB_SHEET_SIDE] on its longer edge, so that its small print stays
 * readable): read whole, encoded whole and rendered through a JSONObject it would stand in the heap
 * several times over — one sheet too many, and no export of this phone would finish.
 */
internal fun Writer.writeLabCaptures(names: Set<String>, dir: File?) {
    write(",\"labCaptures\":[")
    var written = 0
    names.forEach { name ->
        val file = dir?.let { File(it, name) }?.takeIf(File::isFile) ?: return@forEach
        if (written > 0) write(",")
        write("{${JSONObject.quote("file")}:${JSONObject.quote(name)},${JSONObject.quote("jpeg")}:\"")
        writeBase64(file)
        write("\"}")
        written += 1
    }
    write("]")
}

/**
 * [file] as base64 text, never more than a slice of it in the heap. Three bytes make four
 * characters, so a slice that is a multiple of three ends on a whole group, and only the last one
 * is padded: the slices read back as one text.
 */
private fun Writer.writeBase64(file: File) {
    val encoder = Base64.getEncoder()
    val slice = ByteArray(BASE64_SLICE_BYTES)
    file.inputStream().use { input ->
        while (true) {
            val read = input.readNBytes(slice, 0, slice.size)
            if (read <= 0) break
            write(encoder.encodeToString(if (read == slice.size) slice else slice.copyOf(read)))
        }
    }
}

/** Bytes of a file encoded at a time by [writeBase64]: a multiple of three, 64 KiB as text. */
private const val BASE64_SLICE_BYTES = 3 * 16 * 1024

/** The files in the app's own folder that the journal's lab panels name as their sheets. */
internal fun labCaptureNames(journal: List<JournalEntry>): Set<String> = journal
    .filter { it.kind == KIND_LABS }
    .flatMap { attachmentUris(it.labAttachments()) }
    .mapNotNullTo(LinkedHashSet(), ::labCaptureFileName)

/**
 * The lab sheets' photographs, parked as the progress photographs are and named once the file has
 * landed. One already here under its name is the same sheet — the names are made once, by the
 * camera, and never reused — and is left alone. A name the camera does not give is not one this app
 * wrote ([isRestorableLabCapture]); without bytes, or with nowhere to put them, a row is read past.
 *
 * A sheet is read whole on the way in — android.util.JsonReader hands a string over in one piece —
 * and that is a few megabytes, not the tens the camera wrote: a sheet is brought down to
 * [LAB_SHEET_SIDE] the moment it is taken ([shrinkLabCapture]).
 */
private fun importLabCaptures(reader: JsonReader, dir: File?, staged: MutableList<StagedPhoto>) {
    dir?.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
    reader.beginArray()
    while (reader.hasNext()) {
        stagedLabCapture(reader.nextObject(), dir, staged)?.let(staged::add)
    }
    reader.endArray()
}

/** The sheet one row of the member brings, parked beside its name, or null where it is not wanted here. */
private fun stagedLabCapture(row: JSONObject, dir: File?, staged: List<StagedPhoto>): StagedPhoto? {
    val name = row.optString("file")
    val jpeg = row.optString("jpeg")
    val target = dir
        ?.takeIf { jpeg.isNotEmpty() && isRestorableLabCapture(name) }
        ?.let { File(it, name) }
        ?.takeUnless { it.exists() || staged.any { parked -> parked.target == it } }
        ?: return null
    target.parentFile?.mkdirs()
    val part = File(target.path + PART_SUFFIX).apply { writeBytes(Base64.getDecoder().decode(jpeg)) }
    return StagedPhoto(row = null, part = part, target = target, labCapture = true)
}

/**
 * Whether [name], out of a backup's `labCaptures`, is a sheet the camera could have written: the whole
 * of a reference's name ([isLabCaptureName]) and in the form [newLabCapture] gives one, `lab-….jpg`.
 * The folder is one the provider serves and the archive for the doctor copies out, under whatever
 * type a name's extension claims, so a file there under a name of the file's own choosing — a page,
 * a document — is not to be put there by a restore.
 */
internal fun isRestorableLabCapture(name: String): Boolean =
    isLabCaptureName(name) && name.startsWith("lab-") && name.endsWith(".jpg")

/**
 * One member of photograph rows, each carrying its file's bytes as base64 under "jpeg". [opening] is
 * the member's name and bracket written out whole, where BackupRoundTripEdgeCasesTest can read it.
 */
private fun <T> Writer.writePhotoRows(
    opening: String,
    rows: List<T>,
    dir: File?,
    fileOf: (T) -> String,
    toJson: (T, String) -> JSONObject,
) {
    write(opening)
    var written = 0
    rows.forEach { row ->
        val name = fileOf(row)
        // A name with a directory in it is not one this app wrote; it is not read either.
        if (name != File(name).name) return@forEach
        val file = dir?.let { File(it, name) }?.takeIf(File::isFile) ?: return@forEach
        if (written > 0) write(",")
        write(toJson(row, Base64.getEncoder().encodeToString(file.readBytes())).toString())
        written += 1
    }
    write("]")
}

/**
 * Streamed rather than read into a list: each row carries a photograph, and a list of a year's
 * photographs is exactly what the heap should not hold. A picture this phone already has — a row of
 * the same file name, which is the moment it was taken, and its file — is skipped, so a second import
 * adds nothing; one that arrives without bytes, or with nowhere to put them, is skipped too. A row
 * here whose file has gone takes the file's picture back under its name, as a course does: it is
 * the same photograph, and skipped as one already here it would stay a picture of nothing however
 * often the file was restored.
 *
 * The bytes go to a `.part` file next to where the photograph will live, and the row goes into
 * [staged] rather than into the table: this runs inside the import's transaction, and a file
 * written here would outlive a rollback as an orphan nothing lists and nothing deletes. Parts
 * left by a process that died mid-import are swept before the next one begins.
 */
private suspend fun importProgressPhotos(
    reader: JsonReader,
    dao: StepsDao,
    dir: File?,
    staged: MutableList<StagedPhoto>,
) {
    dir?.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
    val here = dao.allProgressPhotos().associateBy(ProgressPhoto::file)
    val seen = mutableSetOf<String>()
    reader.beginArray()
    while (reader.hasNext()) {
        val row = reader.nextObject()
        val photo = row.toProgressPhoto()
        val jpeg = row.optString("jpeg").takeIf(String::isNotEmpty)
        if (dir == null || jpeg == null || !seen.add(photo.file)) continue
        val standing = here[photo.file]
        // A picture here with its file in place is had already.
        if (standing == null || !File(dir, photo.file).isFile) {
            dir.mkdirs()
            val part = File(dir, photo.file + PART_SUFFIX)
            part.writeBytes(Base64.getDecoder().decode(jpeg))
            staged += StagedPhoto(standing ?: photo, part, File(dir, photo.file))
        }
    }
    reader.endArray()
}

/**
 * A restored photograph between the transaction and its final name: a progress picture with the
 * [row] that waits for it, a walk's with its [trackPhoto] row, which waits the same way, the picture
 * of the template [routineId], or — all three unset — the picture of the course [courseId]. A course
 * and a template went in with the rest of their rows, and are named only once the file has landed.
 */
private class StagedPhoto(
    val row: ProgressPhoto?,
    val part: File,
    val target: File,
    val courseId: Long = 0L,
    val trackPhoto: TrackPhotoEntity? = null,
    val routineId: Long = 0L,
    /** A lab sheet's photograph: its row already names it, so landing is all it needs. */
    val labCapture: Boolean = false,
)

/** Where each kind of picture a backup carries is restored to; null leaves that kind out. */
private class PhotoDirs(
    val progress: File?,
    val medicines: File?,
    val walk: File?,
    val routines: File?,
    val labCaptures: File?,
)

/**
 * What [LocalBackup.export] read inside its transaction for the members it writes after it: the rows
 * that carry pictures, the lab sheets the journal names, and what the export holds.
 */
private class PicturedRows(
    val summary: BackupSummary,
    val trackPhotos: List<TrackPhotoEntity>,
    val medications: List<Medication>,
    val routines: List<Routine>,
    val progressPhotos: List<ProgressPhoto>,
    val labCaptures: Set<String>,
)

/** What a file still being written ends in; an import sweeps the ones a process that died left behind. */
internal const val PART_SUFFIX = ".part"

/**
 * The restored photographs a process that died mid-import left under [PART_SUFFIX], swept at a fresh
 * start. An import sweeps the folders it writes to as it begins, and nothing else would: a part is a
 * picture no row names and no screen shows, as large as the photographs the file carried, and it
 * would stay until a next import that may never come. Not while an import is running, whose parts are
 * waiting for their names — the process outlives the screen that began it — and only an hour-old
 * one, as the other sweeps of a fresh start keep.
 */
internal fun sweepRestoreParts(context: Context, now: Long = System.currentTimeMillis()) {
    if (backupImportRunning.value) return
    listOf(
        progressPhotoDir(context),
        medicinePhotoDir(context),
        walkPhotoDir(context),
        routinePhotoDir(context),
        labCaptureDir(context),
    ).forEach { dir ->
        dir.listFiles { file ->
            file.name.endsWith(PART_SUFFIX) && file.lastModified() < now - IN_FLIGHT_STALE_MS
        }?.forEach(File::delete)
    }
}

/**
 * The parked photographs take their names, and only then do the rows name them: a progress or a
 * walk photograph's row is written, a course is given its picture. None ever names a file that is
 * not there. Answers how many could not take their name — a full disk, a folder gone — so that the
 * summary can say so rather than let the picture vanish without a word.
 */
private suspend fun restoreStagedPhotos(dao: StepsDao, staged: List<StagedPhoto>): Int {
    var lost = 0
    staged.forEach { photo ->
        val named = photo.part.renameTo(photo.target)
        if (!named) photo.part.delete()
        when {
            !named -> lost += 1
            photo.labCapture -> Unit
            photo.row != null -> dao.saveProgressPhoto(photo.row)
            photo.trackPhoto != null -> dao.saveTrackPhoto(photo.trackPhoto)
            photo.routineId != 0L -> dao.setRoutinePhoto(photo.routineId, photo.target.name)
            else -> dao.setMedicationPhoto(photo.courseId, photo.target.name)
        }
    }
    return lost
}

private fun ProgressPhoto.toJson(jpeg: String) = JSONObject()
    .put("day", day)
    .put("takenAt", takenAt)
    .put("file", file)
    .put("jpeg", jpeg)

/**
 * A fresh row: the id belongs to the install that wrote the file, the day and the moment do not —
 * and neither does the file name. It is derived from the moment, never read from the file: a
 * backup is something somebody else may have written, and `../databases/steps.db` is a name too.
 */
private fun JSONObject.toProgressPhoto(): ProgressPhoto {
    val takenAt = getLong("takenAt")
    return ProgressPhoto(day = isoDay("day"), takenAt = takenAt, file = progressPhotoName(takenAt))
}

/** The one table with no natural bound: a page at a time, so a long history stays exportable. */
private suspend fun Writer.writeTrackPoints(dao: StepsDao) {
    write(",\"trackPoints\":[")
    var written = 0
    var afterId = -1L
    while (true) {
        val page = dao.trackPointPageAfter(afterId, TRACK_POINT_PAGE)
        if (page.isEmpty()) break
        page.forEach { point ->
            if (written > 0) write(",")
            write(point.toJson().toString())
            written += 1
            afterId = point.id
        }
    }
    write("]")
}

private fun UserProfile.toJson() = JSONObject()
    .put("sex", sex)
    .put("weightKg", weightKg)
    .put("heightCm", heightCm)
    .put("birthDate", birthDate)
    .put("unitSystem", unitSystem)
    .put("activityLevel", activityLevel)
    .put("dailyStepGoal", dailyStepGoal)
    .put("automaticSleepDetection", automaticSleepDetection)
    .put("energyGoal", energyGoal)
    .put("allergies", allergies)
    .put("remindersEnabled", remindersEnabled)

private fun JSONObject.toProfile() = UserProfile(
    sex = getString("sex"),
    weightKg = requiredDouble("weightKg"),
    heightCm = getInt("heightCm"),
    birthDate = birthDateOf(),
    unitSystem = optString("unitSystem", "METRIC"),
    activityLevel = optString("activityLevel", "MODERATE"),
    dailyStepGoal = optLong("dailyStepGoal", 8_000),
    automaticSleepDetection = optBoolean("automaticSleepDetection", false),
    // opt*, not get*: a backup written before these two were carried is still readable, and falls
    // back to the same defaults UserProfile declares. Without the pair, exporting and re-importing
    // would reset a LOSE/GAIN goal to MAINTAIN and switch reminders back on.
    energyGoal = optString("energyGoal", "MAINTAIN"),
    remindersEnabled = optBoolean("remindersEnabled", true),
    // Same reasoning, and one more: an older backup carries no answer, and "never asked" is
    // exactly what an empty column means. Restoring it as "none known" would be inventing one.
    allergies = optString("allergies", ""),
)

/**
 * The profile, refused where a figure is not one a person has. A restore replaces the profile
 * outright and every formula reads it, so a weight of nought or a height of three metres out of a
 * hand-edited file would stand in for the person's own until they noticed. The bounds are the ones the
 * editor and every reading of a body measurement keep ([PLAUSIBLE_KG], 50 to 250 cm), and a step
 * goal has to be one at all.
 */
internal fun UserProfile.plausible(): UserProfile = also {
    require(weightKg in PLAUSIBLE_KG) { "weightKg $weightKg" }
    require(heightCm in 50..250) { "heightCm $heightCm" }
    require(dailyStepGoal > 0) { "dailyStepGoal $dailyStepGoal" }
}

/**
 * The birth date out of an exported profile, from either spelling.
 *
 * Backups written before the age screen carry a year and no day. They are completed the way the
 * database migration completes one — the last day of that year — which can only hold an age-gated
 * feature back for a few months, never hand one over early. A backup carrying neither comes back
 * blank, and blank is an age of nought: the gates close rather than guess.
 */
private fun JSONObject.birthDateOf(): String = optString("birthDate").ifEmpty {
    optInt("birthYear").takeIf { it > 0 }?.let { LocalDate.of(it, 12, 31).toString() }.orEmpty()
}

private fun AppOpenDay.toJson() = JSONObject().put("day", day)
private fun JSONObject.toAppOpenDay() = AppOpenDay(isoDay("day"))

private fun DaySteps.toJson() = JSONObject().put("day", day).put("steps", steps)
private fun JSONObject.toDay() = DaySteps(isoDay("day"), getLong("steps"))

private fun ExternalDaySteps.toJson() = JSONObject().put("day", day).put("steps", steps)
private fun JSONObject.toExternalDay() = ExternalDaySteps(isoDay("day"), getLong("steps"))

private fun ExternalOriginDaySteps.toJson() = JSONObject()
    .put("packageName", packageName)
    .put("day", day)
    .put("steps", steps)

private fun JSONObject.toExternalOriginDay() = ExternalOriginDaySteps(
    packageName = getString("packageName"),
    day = isoDay("day"),
    steps = getLong("steps"),
)

private fun DirectBleDaySteps.toJson() = JSONObject()
    .put("sourceId", sourceId)
    .put("day", day)
    .put("steps", steps)
    .put("sourceLabel", sourceLabel)
    .put("updatedAt", updatedAt)

private fun JSONObject.toDirectBleDay() = DirectBleDaySteps(
    sourceId = getString("sourceId"),
    day = isoDay("day"),
    steps = getLong("steps"),
    sourceLabel = nullableString("sourceLabel"),
    updatedAt = optLong("updatedAt", 0),
)

private fun JournalEntry.toJson() = JSONObject()
    .put("id", id)
    .put("day", day)
    .put("occurredAt", occurredAt)
    .put("kind", kind)
    .put("title", title)
    .put("subtitle", subtitle)
    .put("calories", calories)
    .put("proteinG", proteinG)
    .put("fatG", fatG)
    .put("carbsG", carbsG)
    .put("servingG", servingG)
    .put("fiberG", fiberG)
    .put("sugarG", sugarG)
    .put("saturatedFatG", saturatedFatG)
    .put("sodiumMg", sodiumMg)
    .put("cholesterolMg", cholesterolMg)
    .put("enteredPer100g", enteredPer100g)
    .put("activityCode", activityCode)
    .put("met", met)
    .put("durationMinutes", durationMinutes)
    .put("sets", sets)
    .put("reps", reps)
    .put("loadKg", loadKg)
    .put("measurementWeightKg", measurementWeightKg)
    .put("measurementHeightCm", measurementHeightCm)
    .put("measurementHeartRateBpm", measurementHeartRateBpm)
    .put("sleepStartAt", sleepStartAt)
    .put("sleepEndAt", sleepEndAt)
    .put("measurementSource", measurementSource)
    .put("measurementPayloadJson", measurementPayloadJson)
    .put("moodScore", moodScore)
    .put("energyScore", energyScore)
    .put("breathCycles", breathCycles)
    .put("bodyFatPercent", bodyFatPercent)
    .put("healthConnectRecordKey", healthConnectRecordKey)
    .put("caffeineMg", caffeineMg)
    .put("foodSource", foodSource)
    .put("note", note)
    .put("editedAt", editedAt)
    .put("zoneOffsetSeconds", zoneOffsetSeconds)

internal fun JSONObject.toJournal() = JournalEntry(
    id = getLong("id"),
    day = getString("day"),
    occurredAt = getLong("occurredAt"),
    kind = getString("kind"),
    title = getString("title"),
    subtitle = optString("subtitle", ""),
    calories = requiredDouble("calories"),
    proteinG = optFinite("proteinG"),
    fatG = optFinite("fatG"),
    carbsG = optFinite("carbsG"),
    servingG = optFinite("servingG"),
    fiberG = nullableDouble("fiberG"),
    saturatedFatG = nullableDouble("saturatedFatG"),
    sugarG = nullableDouble("sugarG"),
    sodiumMg = nullableDouble("sodiumMg"),
    cholesterolMg = nullableDouble("cholesterolMg"),
    enteredPer100g = optBoolean("enteredPer100g", false),
    activityCode = nullableString("activityCode"),
    met = nullableDouble("met"),
    durationMinutes = nullableDouble("durationMinutes"),
    sets = nullableInt("sets"),
    reps = nullableInt("reps"),
    loadKg = nullableDouble("loadKg"),
    measurementWeightKg = nullableDouble("measurementWeightKg"),
    measurementHeightCm = nullableInt("measurementHeightCm"),
    measurementHeartRateBpm = nullableInt("measurementHeartRateBpm"),
    sleepStartAt = nullableLong("sleepStartAt"),
    sleepEndAt = nullableLong("sleepEndAt"),
    measurementSource = nullableString("measurementSource"),
    measurementPayloadJson = nullableString("measurementPayloadJson"),
    moodScore = nullableInt("moodScore"),
    energyScore = nullableInt("energyScore"),
    breathCycles = nullableInt("breathCycles"),
    bodyFatPercent = nullableDouble("bodyFatPercent"),
    healthConnectRecordKey = nullableString("healthConnectRecordKey"),
    caffeineMg = nullableDouble("caffeineMg"),
    foodSource = nullableString("foodSource"),
    note = optString("note", ""),
    editedAt = nullableLong("editedAt"),
    // An offset no clock keeps is a damaged cell, and the export would throw on it at every exchange.
    zoneOffsetSeconds = nullableInt("zoneOffsetSeconds")?.takeIf { it in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS },
).withKeyTakenFromNote()

/** Eighteen hours, the furthest from UTC an offset can be ([java.time.ZoneOffset]). */
private const val MAX_OFFSET_SECONDS = 18 * 60 * 60

/**
 * A row as a file of schema 6 or earlier carried it, brought to where the migration to schema 26
 * left the same row in the database.
 *
 * Those files kept the Health Connect key inside the note. Taking it from there keeps a restore of
 * an older file matching the same records it matched on the install that wrote it; left in the note
 * as well, the key would show in the editor as "Health Connect:com.x:uuid" where the
 * migration leaves an empty field. The note is given back on the migration's terms: a key is a
 * package and a record id, and neither can hold a space, so a note carrying one past the prefix is
 * something somebody typed and stays.
 */
internal fun JournalEntry.withKeyTakenFromNote(): JournalEntry {
    if (healthConnectRecordKey != null || !note.startsWith(HEALTH_IMPORT_MARKER_PREFIX)) return this
    val typed = ' ' in note.substring(HEALTH_IMPORT_MARKER_PREFIX.length)
    return copy(healthConnectRecordKey = note, note = if (typed) note else "")
}

// The attachments travel as the URIs they were granted as, which name the same files only on the
// phone that granted them — and that is the phone a restore of its own backup lands on.
internal fun Medication.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("dose", dose)
    .put("schedule", schedule)
    .put("times", times)
    .put("foodRelation", foodRelation)
    .put("startDay", startDay)
    .put("endDay", endDay)
    .put("note", note)
    .put("attachments", attachments)
    .put("contents", contents)
    .put("createdAt", createdAt)
    .put("photo", photo)

/**
 * A course's or a template's photograph itself beside its name, as a progress picture travels: the
 * name means nothing on another phone. A row whose file has gone is written without bytes, and
 * comes back without a picture rather than with a name pointing at nothing.
 */
private fun JSONObject.withPhotoBytes(photo: String, dir: File?): JSONObject {
    val file = dir?.takeIf { isMedicinePhotoName(photo) }?.let { File(it, photo) }?.takeIf(File::isFile)
    return if (file == null) this else put("jpeg", Base64.getEncoder().encodeToString(file.readBytes()))
}

/**
 * A photograph's name is believed only in the shape this app writes it, digits and `.jpg`: a backup
 * is something somebody else may have written, and `../databases/steps.db` is a name too.
 */
internal fun JSONObject.toMedication() = Medication(
    id = getLong("id"),
    name = getString("name"),
    dose = optString("dose", ""),
    schedule = optString("schedule", MedSchedule.CLOCK.name),
    times = optString("times", ""),
    foodRelation = optString("foodRelation", MedFood.ANY.name),
    startDay = isoDay("startDay"),
    endDay = nullableString("endDay")?.let { LocalDate.parse(it).toString() },
    note = optString("note", ""),
    attachments = optString("attachments", ""),
    contents = optString("contents", ""),
    createdAt = optLong("createdAt", 0),
    photo = optString("photo", "").takeIf(::isMedicinePhotoName).orEmpty(),
)

internal fun Appointment.toJson() = JSONObject()
    .put("id", id)
    .put("specialty", specialty)
    .put("doctor", doctor)
    .put("place", place)
    .put("atMs", atMs)
    .put("note", note)
    .put("remindDaysBefore", remindDaysBefore)
    .put("doneAt", doneAt)
    .put("remindedAt", remindedAt)
    .put("attachments", attachments)

internal fun JSONObject.toAppointment() = Appointment(
    id = getLong("id"),
    specialty = optString("specialty", VISIT_SPECIALTY_OTHER),
    doctor = optString("doctor", ""),
    place = optString("place", ""),
    atMs = getLong("atMs"),
    note = optString("note", ""),
    remindDaysBefore = optInt("remindDaysBefore", 1),
    doneAt = nullableLong("doneAt"),
    remindedAt = nullableLong("remindedAt"),
    attachments = optString("attachments", ""),
)

private fun Routine.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("createdAt", createdAt)
    .put("description", description)
    .put("photo", photo)
    .put("days", days)

/** A picture's name is believed only in the shape this app writes it, as a course's is. */
private fun JSONObject.toRoutine() = Routine(
    id = getLong("id"),
    name = getString("name"),
    createdAt = getLong("createdAt"),
    description = optString("description", ""),
    photo = optString("photo", "").takeIf(::isMedicinePhotoName).orEmpty(),
    days = optInt("days", 0) and ALL_ROUTINE_DAYS,
)

internal fun RoutineExercise.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("routineId", routineId)
    .put("position", position)
    .put("activityCode", activityCode)
    .put("durationMinutes", durationMinutes)
    .put("sets", sets)
    .put("reps", reps)
    .put("loadKg", loadKg)
    .put("superset", superset)
    .put("restSeconds", restSeconds)
    .put("setsJson", setsJson)

internal fun JSONObject.toRoutineExercise() = RoutineExercise(
    id = getLong("id"),
    routineId = getLong("routineId"),
    position = getInt("position"),
    activityCode = getString("activityCode"),
    durationMinutes = requiredDouble("durationMinutes"),
    sets = nullableInt("sets"),
    reps = nullableInt("reps"),
    loadKg = nullableDouble("loadKg"),
    superset = nullableInt("superset"),
    restSeconds = optInt("restSeconds", 0).coerceAtLeast(0),
    setsJson = optString("setsJson", ""),
)

private fun HourSteps.toJson() = JSONObject()
    .put("day", day)
    .put("hour", hour)
    .put("steps", steps)

private fun JSONObject.toHourSteps() = HourSteps(
    day = isoDay("day"),
    hour = getInt("hour"),
    steps = getLong("steps"),
)

private fun BarometerDay.toJson() = JSONObject()
    .put("day", day)
    .put("ascentM", ascentM)
    .put("pressureSumHpa", pressureSumHpa)
    .put("pressureSamples", pressureSamples)

private fun JSONObject.toBarometerDay() = BarometerDay(
    day = isoDay("day"),
    ascentM = optFinite("ascentM"),
    pressureSumHpa = optFinite("pressureSumHpa"),
    pressureSamples = optLong("pressureSamples", 0),
)

private fun JournalTombstone.toJson() = JSONObject()
    .put("recordKey", recordKey)
    .put("deletedAt", deletedAt)

private fun JSONObject.toJournalTombstone() = JournalTombstone(
    recordKey = getString("recordKey"),
    deletedAt = getLong("deletedAt"),
)

private fun SavedDish.toJson() = JSONObject()
    .put("key", key)
    .put("name", name)
    .put("items", items)
    .put("createdAt", createdAt)

private fun JSONObject.toSavedDish() = SavedDish(
    key = getString("key"),
    name = getString("name"),
    items = optString("items"),
    createdAt = getLong("createdAt"),
)

private fun MedicalHistoryRow.toJson() = JSONObject()
    .put("key", key)
    .put("kind", kind)
    .put("title", title)
    .put("detail", detail)
    .put("startDay", startDay)
    .put("endDay", endDay)
    .put("note", note)
    .put("updatedAt", updatedAt)
    .put("answeredAt", answeredAt)

private fun JSONObject.toMedicalHistoryRow() = MedicalHistoryRow(
    key = getString("key"),
    kind = getString("kind"),
    title = getString("title"),
    detail = optString("detail"),
    startDay = optString("startDay"),
    endDay = optString("endDay"),
    note = optString("note"),
    updatedAt = getLong("updatedAt"),
    // A file from before the answer had a moment of its own: the last save is the only one it states.
    answeredAt = optLong("answeredAt", getLong("updatedAt")),
)

internal fun ChatRow.toJson() = JSONObject()
    .put("key", key)
    .put("day", day)
    .put("startedAt", startedAt)
    .put("updatedAt", updatedAt)
    .put("payload", payload)

internal fun JSONObject.toChatRow() = ChatRow(
    key = getString("key"),
    day = isoDay("day"),
    startedAt = getLong("startedAt"),
    updatedAt = optLong("updatedAt", getLong("startedAt")),
    payload = getString("payload"),
)

private fun FavoriteFood.toJson() = JSONObject()
    .put("key", key)
    .put("title", title)
    .put("subtitle", subtitle)
    .put("servingG", servingG)
    .put("calories", calories)
    .put("proteinG", proteinG)
    .put("fatG", fatG)
    .put("carbsG", carbsG)
    .put("fiberG", fiberG)
    .put("sugarG", sugarG)
    .put("saturatedFatG", saturatedFatG)
    .put("sodiumMg", sodiumMg)
    .put("cholesterolMg", cholesterolMg)
    .put("createdAt", createdAt)

private fun JSONObject.toFavoriteFood() = FavoriteFood(
    key = getString("key"),
    title = getString("title"),
    subtitle = optString("subtitle"),
    servingG = optFinite("servingG"),
    calories = optFinite("calories"),
    proteinG = optFinite("proteinG"),
    fatG = optFinite("fatG"),
    carbsG = optFinite("carbsG"),
    fiberG = nullableDouble("fiberG"),
    sugarG = nullableDouble("sugarG"),
    saturatedFatG = nullableDouble("saturatedFatG"),
    sodiumMg = nullableDouble("sodiumMg"),
    cholesterolMg = nullableDouble("cholesterolMg"),
    createdAt = getLong("createdAt"),
)

/**
 * A measurement that will not render, read as the nothing it says.
 *
 * JSONObject refuses a non-finite double outright, the export renders inside the database's own
 * transaction, and [exportSpooled] deletes the document behind a failure — so one such column ends
 * every backup this phone writes, on this run and on every later one, naming no row and offering no
 * way past it. SQLite keeps no NaN, but a quoted `1e999` or `Infinity` in a hand-edited or
 * third-party file reads out of `optDouble` as an infinity and is stored as one, which is why the
 * reader guards as well: the writer rescues a route already holding one, the reader is what stops
 * one arriving. Every table's reader takes its numbers through [optFinite], [requiredDouble],
 * [nullableDouble] or [nullableFloat] for that: one such figure in a journal row would end the
 * backups as surely as one in a route.
 */
private fun Double.finiteOrZero(): Double = takeIf { it.isFinite() } ?: 0.0

/** The same rescue for a reading that may be absent: one that will not render is read as absent. */
private fun Number?.finiteOrNull(): Double? = this?.toDouble()?.takeIf { it.isFinite() }

/**
 * A route still being recorded leaves the file closed at the moment of export. The row is open in
 * this database because this phone is writing it; on the phone that restores the file nothing is,
 * and an open row there is a recording the service resumes on its next start — location and all —
 * into a route that ended somewhere else.
 */
internal fun TrackEntity.toJson(exportedAt: Long = System.currentTimeMillis()) = JSONObject()
    .put("id", id)
    .put("startedAt", startedAt)
    .put("endedAt", endedAt ?: exportedAt)
    .put("distanceMeters", distanceMeters.finiteOrZero())
    .put("points", points)
    .put("walkingDistanceMeters", walkingDistanceMeters.finiteOrZero())
    .put("runningDistanceMeters", runningDistanceMeters.finiteOrZero())
    .put("vehicleDistanceMeters", vehicleDistanceMeters.finiteOrZero())
    .put("stoppedMillis", stoppedMillis)
    .put("elevationGainM", elevationGainM.finiteOrZero())
    .put("elevationLossM", elevationLossM.finiteOrZero())
    .put("maxSpeedMps", maxSpeedMps.finiteOrZero())
    .put("name", name)
    // Written closed, as endedAt is: a pause still open ends at the export and is counted, so the
    // route never arrives on another phone both ended and paused.
    .put("pausedAt", JSONObject.NULL)
    .put("pausedMillis", pausedMillis + (pausedAt?.let { (exportedAt - it).coerceAtLeast(0) } ?: 0))
    .put("zoneOffsetSeconds", zoneOffsetSeconds)

private fun JSONObject.toTrack() = TrackEntity(
    id = getLong("id"),
    startedAt = getLong("startedAt"),
    endedAt = nullableLong("endedAt"),
    distanceMeters = optFinite("distanceMeters"),
    points = optInt("points", 0),
    walkingDistanceMeters = optFinite("walkingDistanceMeters"),
    runningDistanceMeters = optFinite("runningDistanceMeters"),
    vehicleDistanceMeters = optFinite("vehicleDistanceMeters"),
    stoppedMillis = optLong("stoppedMillis", 0),
    elevationGainM = optFinite("elevationGainM"),
    elevationLossM = optFinite("elevationLossM"),
    maxSpeedMps = optFinite("maxSpeedMps"),
    name = optString("name", ""),
    pausedAt = nullableLong("pausedAt"),
    pausedMillis = optLong("pausedMillis", 0),
    zoneOffsetSeconds = nullableInt("zoneOffsetSeconds")?.takeIf { it in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS },
)

private fun TrackPointEntity.toJson() = JSONObject()
    .put("id", id)
    .put("trackId", trackId)
    .put("timestamp", timestamp)
    .put("elapsedRealtimeNanos", elapsedRealtimeNanos)
    .put("lat", lat)
    .put("lon", lon)
    .put("correctedLat", correctedLat)
    .put("correctedLon", correctedLon)
    .put("altitudeM", altitudeM)
    .put("verticalAccuracyM", verticalAccuracyM?.toDouble())
    .put("accuracyM", accuracyM?.toDouble())
    .put("speedMps", speedMps?.toDouble())
    .put("speedAccuracyMps", speedAccuracyMps?.toDouble())
    .put("pressureHpa", pressureHpa?.toDouble())
    .put("barometricAltitudeM", barometricAltitudeM?.toDouble())
    .put("ambientTemperatureC", ambientTemperatureC?.toDouble())
    .put("movementType", movementType)
    .put("cadenceHz", cadenceHz.toDouble())
    .put("motionStd", motionStd.toDouble())
    .put("environmentSpike", environmentSpike)
    .put("provider", provider)
    .put("accepted", accepted)
    .put("filterReason", filterReason)
    .put("altitudeMsl", altitudeMsl)
    .put("segmentStart", segmentStart)

private fun JSONObject.toTrackPoint() = TrackPointEntity(
    id = getLong("id"),
    trackId = getLong("trackId"),
    timestamp = getLong("timestamp"),
    elapsedRealtimeNanos = optLong("elapsedRealtimeNanos", 0),
    lat = requiredDouble("lat"),
    lon = requiredDouble("lon"),
    correctedLat = nullableDouble("correctedLat"),
    correctedLon = nullableDouble("correctedLon"),
    altitudeM = nullableDouble("altitudeM"),
    verticalAccuracyM = nullableFloat("verticalAccuracyM"),
    accuracyM = nullableFloat("accuracyM"),
    speedMps = nullableFloat("speedMps"),
    speedAccuracyMps = nullableFloat("speedAccuracyMps"),
    pressureHpa = nullableFloat("pressureHpa"),
    barometricAltitudeM = nullableFloat("barometricAltitudeM"),
    ambientTemperatureC = nullableFloat("ambientTemperatureC"),
    movementType = optString("movementType", RouteMovement.UNKNOWN.name),
    cadenceHz = nullableFloat("cadenceHz") ?: 0f,
    motionStd = nullableFloat("motionStd") ?: 0f,
    environmentSpike = optBoolean("environmentSpike", false),
    provider = getString("provider"),
    accepted = getBoolean("accepted"),
    filterReason = optString("filterReason", ""),
    altitudeMsl = optBoolean("altitudeMsl", false),
    segmentStart = optBoolean("segmentStart", false),
)

/**
 * Every column but the id, and the picture itself beside its name. A reading the phone could not
 * give is left out, which reads back as the null it was; so is one that will not render, for the
 * reason [finiteOrZero] gives — and a zero here would be a bearing due north, not an absence.
 */
internal fun TrackPhotoEntity.toJson(jpeg: String) = JSONObject()
    .put("trackId", trackId)
    .put("takenAt", takenAt)
    .put("file", file)
    .put("lat", lat.finiteOrNull())
    .put("lon", lon.finiteOrNull())
    .put("altitudeM", altitudeM.finiteOrNull())
    .put("accuracyM", accuracyM.finiteOrNull())
    .put("bearingDeg", bearingDeg.finiteOrNull())
    .put("bearingAccuracyDeg", bearingAccuracyDeg.finiteOrNull())
    .put("pitchDeg", pitchDeg.finiteOrNull())
    .put("rollDeg", rollDeg.finiteOrNull())
    .put("jpeg", jpeg)

/**
 * A fresh row, as a progress photograph's is: the id belongs to the install that wrote the file, and
 * so does the route's, which the importer replaces with the one this phone holds it under. The name
 * is derived from the moment, never read from the file — `../databases/steps.db` is a name too.
 */
internal fun JSONObject.toTrackPhoto(): TrackPhotoEntity {
    val takenAt = getLong("takenAt")
    return TrackPhotoEntity(
        trackId = getLong("trackId"),
        takenAt = takenAt,
        file = progressPhotoName(takenAt),
        lat = nullableDouble("lat"),
        lon = nullableDouble("lon"),
        altitudeM = nullableDouble("altitudeM"),
        accuracyM = nullableFloat("accuracyM"),
        bearingDeg = nullableFloat("bearingDeg"),
        bearingAccuracyDeg = nullableFloat("bearingAccuracyDeg"),
        pitchDeg = nullableFloat("pitchDeg"),
        rollDeg = nullableFloat("rollDeg"),
    )
}

private fun <T> JsonReader.nextObjects(transform: (JSONObject) -> T): List<T> {
    val rows = mutableListOf<T>()
    beginArray()
    while (hasNext()) rows += transform(nextObject())
    endArray()
    return rows
}

private fun JsonReader.nextStrings(): List<String> {
    val rows = mutableListOf<String>()
    beginArray()
    while (hasNext()) rows += nextString()
    endArray()
    return rows
}

private fun <T> JsonReader.nextNullableObject(transform: (JSONObject) -> T): T? =
    if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else {
        transform(nextObject())
    }

private fun JsonReader.nextObject(): JSONObject {
    val value = JSONObject()
    beginObject()
    while (hasNext()) value.put(nextName(), nextJsonValue())
    endObject()
    return value
}

private fun JsonReader.nextJsonValue(): Any = when (peek()) {
    JsonToken.BEGIN_OBJECT -> nextObject()

    JsonToken.BEGIN_ARRAY -> {
        val value = JSONArray()
        beginArray()
        while (hasNext()) value.put(nextJsonValue())
        endArray()
        value
    }

    JsonToken.STRING -> nextString()

    JsonToken.NUMBER -> nextString().let { it.toLongOrNull() ?: it.toDouble() }

    JsonToken.BOOLEAN -> nextBoolean()

    JsonToken.NULL -> {
        nextNull()
        JSONObject.NULL
    }

    else -> throw IllegalArgumentException("Unexpected JSON token ${peek()}")
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

/**
 * A day as every table keys its days, `2026-09-05`. One that does not read as a day refuses the file,
 * as a missing column does, and [importFailureRes] calls it damaged: stored as it stood, it would be
 * a row no page can place and nothing can delete, and a screen that took its day at its word would fall over.
 * The journal's rows are sorted out apart and counted ([withReadableDays]).
 */
private fun JSONObject.isoDay(name: String): String = LocalDate.parse(getString(name)).toString()

/**
 * A number the row cannot do without. One that is not finite is refused as a missing one is, and the
 * file with it, for the reason [finiteOrZero] gives: a zero in its place would be a meal of no
 * energy, or a fix in the Gulf of Guinea.
 */
private fun JSONObject.requiredDouble(name: String): Double =
    getDouble(name).also { require(it.isFinite()) { "$name is not a finite number" } }

/** A number that reads as nought when the file has none, and when what it has is not finite. */
private fun JSONObject.optFinite(name: String): Double = optDouble(name, 0.0).finiteOrZero()

/** A number that may be absent; one that is not finite is read as absent ([finiteOrZero]). */
private fun JSONObject.nullableDouble(name: String): Double? =
    if (isNull(name)) null else getDouble(name).takeIf { it.isFinite() }

/** The same for a column kept as a float, which a number too large for one would reach as an infinity. */
private fun JSONObject.nullableFloat(name: String): Float? =
    nullableDouble(name)?.toFloat()?.takeIf { it.isFinite() }

private fun JSONObject.nullableInt(name: String): Int? =
    if (isNull(name)) null else getInt(name)

private fun JSONObject.nullableLong(name: String): Long? =
    if (isNull(name)) null else getLong(name)
