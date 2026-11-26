package com.pwnall.health

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Locally stored total for one day. New rows are phone-owned; [LegacyStepDay] protects ambiguous
 * pre-v15 rows that may already contain an imported wearable total.
 */
@Entity(tableName = "days")
data class DaySteps(
    @PrimaryKey val day: String, // ISO local date, e.g. "2026-07-29"
    val steps: Long,
)

/** One foreground opening per local date, also carried in a backup. */
@Entity(tableName = "app_open_days")
data class AppOpenDay(
    @PrimaryKey val day: String,
)

/**
 * Steps credited within one hour of one day — the shape of the day, not just its total.
 *
 * Only the phone's own counter fills this: Health Connect and BLE peers report daily totals with no
 * time inside them, so an hour here is always "what this phone counted", and the day's grand total
 * can legitimately exceed the sum of its hours.
 */
@Entity(tableName = "hourly_steps", primaryKeys = ["day", "hour"])
data class HourSteps(
    val day: String,
    /** Local hour of day, 0..23. */
    val hour: Int,
    val steps: Long,
)

/** Deduplicated Health Connect total, refreshed by sync and never incremented by the phone. */
@Entity(tableName = "external_step_days")
data class ExternalDaySteps(
    @PrimaryKey val day: String,
    val steps: Long,
)

/**
 * What one app behind Health Connect gave on one day, beside the deduplicated total above.
 *
 * Provenance and nothing else: no step arithmetic reads this table, and [ExternalDaySteps] stays
 * the one figure the app counts with. It exists because that figure names nobody — a person with a
 * watch, a phone and a fitness app is shown one number and told only that Health Connect had it —
 * and the sources screen is the screen that answers "which of them".
 *
 * [steps] is Health Connect's own aggregate over this package alone, asked for a second time with
 * the origin filter narrowed to it, rather than a sum of raw records: one app can publish a day's
 * walk as a session and again as a daily total, and only the platform knows which of its own
 * records overlap. Two packages' figures are therefore not addable — they can describe the same
 * steps twice, which is the screen's whole subject and what its note already says out loud.
 */
@Entity(tableName = "external_step_origin_days", primaryKeys = ["packageName", "day"])
data class ExternalOriginDaySteps(
    val packageName: String,
    val day: String,
    val steps: Long,
)

/**
 * A daily total from one named source that is not Health Connect.
 *
 * The table is called `direct_ble_step_days` after a Bluetooth ring, one of the things that fill
 * it, and keeps that name because renaming a Room table costs a migration to say nothing new.
 * What it actually holds is more general: a day, a count, and a stable name for whoever counted.
 * The other filler is the file import, whose rows are a service's own export — see
 * [HealthExportApp.sourceId] — and they are stored here rather than in [ExternalDaySteps] because
 * that one is Health Connect's deduplicated total and is rewritten from scratch on every sync,
 * which would throw an imported history away the next time the wizard runs.
 *
 * [sourceId] is the stable, adapter-owned provenance key (for example a protocol plus device
 * identity), not a Health Connect package. Several sources may report the same [day]; display
 * queries deliberately take their maximum because those totals can describe overlapping steps.
 */
@Entity(
    tableName = "direct_ble_step_days",
    primaryKeys = ["sourceId", "day"],
    indices = [Index(value = ["day"])],
)
data class DirectBleDaySteps(
    val sourceId: String,
    val day: String,
    val steps: Long,
    /** Human-readable model/name captured at sync time; never used as the stable identity. */
    val sourceLabel: String? = null,
    val updatedAt: Long = 0,
)

/**
 * What the barometer saw on one day: how much of it was climbed, and how heavy the air was.
 *
 * The pressure is kept as a sum and a count rather than a mean so a new reading is an addition
 * instead of a re-weighting, and because the count is itself worth seeing — a mean over four
 * samples is not a day's weather. [pressureSamples] is deliberately sparse; the sensor delivers
 * hundreds of thousands of readings a day and one every few minutes describes the weather just as
 * well.
 */
@Entity(tableName = "barometer_days")
data class BarometerDay(
    @PrimaryKey val day: String,
    /** Metres of climb the day accumulated, credited a storey at a time by [FloorTracker]. */
    val ascentM: Double = 0.0,
    val pressureSumHpa: Double = 0.0,
    val pressureSamples: Long = 0,
)

/** Existing pre-v15 rows whose source cannot be proven safe for Health Connect re-export. */
@Entity(tableName = "legacy_step_days")
data class LegacyStepDay(
    @PrimaryKey val day: String,
)

@Entity(tableName = "profile")
data class UserProfile(
    @PrimaryKey val id: Int = 0,
    val sex: String,
    val weightKg: Double,
    val heightCm: Int,
    /**
     * Date of birth as the age screen took it, ISO-8601: `1990-04-17`.
     *
     * A whole date rather than a year, because this is what decides whether the
     * app offers alcohol, tobacco or sexual logging at all — and a year on its own hands all three
     * over on the 1st of January, up to eleven months early, to exactly the person the gate is
     * there for. Read through [ageOn]; [birthYear] is the shorthand for the arithmetic that only
     * ever wanted a year.
     */
    val birthDate: String,
    val unitSystem: String = "METRIC",
    val activityLevel: String = "MODERATE",
    val dailyStepGoal: Long = 8_000,
    val automaticSleepDetection: Boolean = false,
    /** MAINTAIN, LOSE or GAIN — shifts the day's energy budget. */
    val energyGoal: String = "MAINTAIN",
    val remindersEnabled: Boolean = true,
    /**
     * What the person said about their own allergies, as [allergyAnswerOf] spells it: empty for a
     * profile that was never asked, `NOT_TESTED`, `NONE`, or the names of the allergens reported.
     *
     * One column rather than two because the three answers that are not a list have to be unable
     * to disagree with the list — a status column saying "none known" beside a set holding milk is
     * a state the parser would then have to arbitrate, and nothing should have to.
     */
    val allergies: String = "",
)

/** The age a birth date nothing can read is assumed to carry: an adult, rather than year zero. */
private const val ASSUMED_AGE_WHEN_BIRTH_DATE_UNREADABLE = 40

/**
 * The year out of [UserProfile.birthDate], for the arithmetic that is only accurate to one.
 *
 * Mifflin–St Jeor, the step-goal band and the percentile cohort all read a year, because a year is
 * as fine as any of them resolves; asking them to carry a date would change no number they print.
 * What must not read this is an age gate — that is [ageOn]'s job, and the difference between the
 * two is the eleven months this property cannot see.
 *
 * A date this cannot read is answered with a year that stands for an adult, not with zero. Blank is
 * what a backup carrying neither a date nor a positive year restores as, and the profile migration
 * writes `0000-12-31` for a legacy row whose year column was 0 — and zero here means "about two
 * thousand years old" to every reader, of which only some clamp from above: the resting-energy
 * equation would come back at about minus eight thousand kcal and take the day's whole food budget
 * with it, and a connected exercise machine would be told the same absurd age. The age the fallback implies
 * moves with whichever year the caller subtracts it from, so it is a plausible adult rather than a
 * promise of exactly forty; a screen that must say the age is unknown asks [birthDateKnown], which
 * reads the date itself.
 */
val UserProfile.birthYear: Int
    get() = birthDate.take(4).toIntOrNull()?.takeIf { it > 0 }
        ?: (LocalDate.now().year - ASSUMED_AGE_WHEN_BIRTH_DATE_UNREADABLE)

/**
 * The three oldest kinds of journal row: a meal, a workout, and a measurement of the body — a
 * weight, a height, a pulse, a night's sleep.
 *
 * The other kinds each have a constant beside their feature ([KIND_WATER], [KIND_MED] and the
 * rest); these three have theirs here, because written out as text a slip in one place
 * compiles and reads as "some other kind". The queries below spell the stored value inside
 * their SQL, and JournalKindTest holds the three names to it.
 */
const val KIND_EAT = "EAT"
const val KIND_ACTIVITY = "ACTIVITY"
const val KIND_MEASUREMENT = "MEASUREMENT"

@Entity(
    tableName = "journal",
    // The last three serve the idempotency probes every import runs once per incoming record, and
    // the newest-first measurement reads the app root keeps open. [healthConnectRecordKey] is the
    // one every incoming record asks about twice — [StepsDao.hasImportedEntry] and
    // [StepsDao.importedEntry] — and without its index each would read the whole journal. [note] is
    // indexed by nothing: the one query that names it — the journal search — asks with a leading
    // wildcard, which no B-tree index can answer, so an index there would cost every write and
    // serve nothing.
    indices = [
        Index(value = ["day", "occurredAt"]),
        Index(value = ["occurredAt"]),
        Index(value = ["healthConnectRecordKey"]),
        Index(value = ["kind", "measurementSource"]),
    ],
)
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val occurredAt: Long,
    val kind: String,
    val title: String,
    /** Qualifier under the name — "Запечённая", "обезжиренный". Empty when there is nothing to add. */
    val subtitle: String = "",
    val calories: Double,
    /**
     * Macros and energy are always stored **for the whole portion**, whichever basis they were typed
     * on. Everything that reads a journal row — the day's totals, the week summary, the Health
     * Connect export — wants the portion figure, and deriving it once at entry keeps every one of
     * those readers unchanged. [enteredPer100g] records only which column the editor should put the
     * cursor back in.
     */
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
    val servingG: Double = 0.0,
    /** Optional nutrients, null when the user never opened the "additional information" sheet. */
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    /** Saturated fat, the fraction of [fatG] the lipid guidance is actually written about. */
    val saturatedFatG: Double? = null,
    val sodiumMg: Double? = null,
    val cholesterolMg: Double? = null,
    /** True when the figures above were typed per 100 g and scaled up, rather than typed directly. */
    val enteredPer100g: Boolean = false,
    val activityCode: String? = null,
    val met: Double? = null,
    val durationMinutes: Double? = null,
    val sets: Int? = null,
    val reps: Int? = null,
    val loadKg: Double? = null,
    val measurementWeightKg: Double? = null,
    val measurementHeightCm: Int? = null,
    val measurementHeartRateBpm: Int? = null,
    val sleepStartAt: Long? = null,
    val sleepEndAt: Long? = null,
    val measurementSource: String? = null,
    /**
     * Versioned, source-native fields for measurements that do not yet have first-class journal
     * columns (blood pressure, SpO2, temperature, glucose and extended body composition). Keeping
     * the JSON beside the human-readable note preserves units and optional SIG fields without a
     * schema migration for every newly adopted Bluetooth profile.
     */
    val measurementPayloadJson: String? = null,
    /** 1..5 on the [Mood] scale for kind = "MOOD". How pleasant, which is one of two questions. */
    val moodScore: Int? = null,
    /**
     * 1..5 on the [Energy] scale for kind = "MOOD", where it was asked and answered.
     *
     * Its own column because it is its own dimension. Activity raises how energetic somebody feels
     * and how pleasant the day was, and *lowers* how calm they feel (Olfermann 2026, IJBNPA 23:70,
     * n=65): those two move in opposite directions, and a single ladder holding both averages them
     * into each other. Null on every row where it was not asked or was left alone — it is a second
     * tap, not a second obligation.
     */
    val energyScore: Int? = null,
    /** Completed breath cycles for kind = "BREATHING". */
    val breathCycles: Int? = null,
    /** Estimated from a scale's impedance reading, never measured directly. */
    val bodyFatPercent: Double? = null,
    /**
     * The Health Connect record this row was imported from — the origin package and the record id,
     * exactly as the sync spells them.
     *
     * Its own column because it is identity, not prose. Not in [note], which the editor offers as a
     * free field: one word typed into an imported row's note would break every lookup that matches
     * on it, so the next sync would import the same foreign record a second time and the tombstone
     * meant to remember the deletion would be filed under a name nothing would ask for again.
     * Null on everything this app logged itself.
     */
    val healthConnectRecordKey: String? = null,
    /**
     * Caffeine on a row that is not itself a cup — an energy drink, a coffee logged with its milk.
     *
     * [KIND_CAFFEINE] keeps its milligrams in [servingG], where the whole row is the caffeine. A
     * meal cannot: there [servingG] is the portion in grams, so an imported record carrying both
     * food and caffeine keeps the caffeine here, the one place it can go. Null on everything that
     * states no caffeine, which is not the same as stating none.
     */
    val caffeineMg: Double? = null,
    /**
     * The catalogue rows a meal was built from and how much of each, as [encodeDish] writes a saved
     * dish: `id=grams`, pipe separated. Null on a meal typed from nothing, read off a label or
     * imported — and on one whose name was edited after the pick, which is no longer that row.
     *
     * A pointer rather than a copy of the vitamins, so that a catalogue row matched to USDA later
     * reaches the meals already eaten, the way a saved dish is recomputed from the catalogue.
     */
    val foodSource: String? = null,
    val note: String = "",
    /**
     * When a person last changed this row by hand, or null for a row nobody has edited. Set by the
     * one write door every page saves through, [writeJournalEvent], for a row that already had an
     * id. Read by the Health Connect import: a row that came from another app is refreshed from its
     * record on every sync, and without this a correction typed into it would be undone within minutes.
     */
    val editedAt: Long? = null,
    /**
     * The offset from UTC, in seconds, of the clock [occurredAt] was written on: stamped by the write
     * door ([filedOffsetSeconds]), taken from the record for a row Health Connect brought, and null
     * where nothing says — a file's row, one from a meter — which is read as the phone's own zone.
     *
     * Health Connect keeps an offset beside every instant, and it is the hour other apps show.
     * Worked out from the zone the phone is in at the exchange instead, a full publication after a
     * move would rewrite the hour of every record there is: dinner at eight in Los Angeles would
     * become seven in the morning in Moscow, and a restore would then file it under the next day.
     */
    val zoneOffsetSeconds: Int? = null,
)

/**
 * The offset the write door files a row under: the one it already had for as long as its moment is
 * the one it had — a corrected title is not a row moved — and otherwise that of [zone], the phone's,
 * at the row's moment, which is the clock the person read that moment on.
 */
internal fun filedOffsetSeconds(previous: JournalEntry?, entry: JournalEntry, zone: ZoneId): Int? =
    if (previous != null && previous.occurredAt == entry.occurredAt) {
        previous.zoneOffsetSeconds
    } else {
        zone.rules.getOffset(Instant.ofEpochMilli(entry.occurredAt)).totalSeconds
    }

/**
 * That a journal row was deleted, kept after the row itself is gone.
 *
 * Health Connect outlives the journal. A record this app published, or one it imported from another
 * app, is still sitting there when the local row is deleted, and the next sync reads it as history
 * worth restoring — which would bring a deleted event back. The row is the only thing left
 * that can tell "never seen here" from "deleted on purpose", and the deletion promise in the privacy
 * policy rests on the difference.
 *
 * [recordKey] is what the sync recognises a record by: [journalRecordKey] over the row id for a
 * record this app published, and the import marker for a row that came from another app. Both are
 * written by [StepsDb.deleteJournalEvent]; see [journalTombstoneKeys].
 *
 * A deleted route leaves one here too, under [routeTombstoneKey] and written by [deleteWalk], and so
 * does a deleted line of the medical history, under [medicalHistoryTombstoneKey] and written by
 * [deleteMedicalHistory]: those are there for the backup's merge alone, and the sync never asks.
 */
@Entity(tableName = "journal_tombstones")
data class JournalTombstone(
    @PrimaryKey val recordKey: String,
    val deletedAt: Long,
)

/**
 * One line of the medical history a person writes down: a diagnosis, an operation, a vaccination, a
 * pregnancy or a contraceptive. What each column means for each kind is written on [HistoryKind].
 */
@Entity(tableName = "medical_history")
data class MedicalHistoryRow(
    /**
     * A random key made where the row is, not an autoincrement.
     *
     * The same reason [FavoriteFood] has one: a backup carried to a second phone has to be able to
     * say whether the row it holds is the one already there, and two installs both counting from 1
     * cannot answer that.
     */
    @PrimaryKey val key: String,
    /** A [HistoryKind] name. An unknown one is dropped on read rather than guessed at. */
    val kind: String,
    /**
     * What it is called: free text for a diagnosis, an operation or a vaccine, and the code of a
     * [HistoryOption] for the two kinds that are a choice rather than a name.
     */
    val title: String,
    /** Who said so, which arm, which clinic — whatever the person wrote in the second field. */
    val detail: String = "",
    /** ISO days, empty where unknown. A record with no date at all is still a record. */
    val startDay: String = "",
    val endDay: String = "",
    val note: String = "",
    /** Newest wins when a backup meets a row this install already has. */
    val updatedAt: Long,
    /**
     * When the answer this row gives was given: the moment it was first written down, and again
     * whenever its name, its option or either of its days was changed. A correction of the note or
     * of the second field leaves it alone.
     *
     * Apart from [updatedAt] because that one moves with every save, as a merge needs it to, and an
     * undated answer of a kind that has a current one is placed by the day it was given on
     * ([ofHistoryKind]): a typing error fixed in last year's "not pregnant" would otherwise make it
     * this week's answer, in front of the one actually given since.
     */
    val answeredAt: Long = updatedAt,
)

/**
 * One conversation of the chat: everything the person wrote, every question asked and answered, and
 * the records it filed, named by moment. Not a journal row, so it never makes its day kept, never
 * shows in the day list and is never read as a measurement, a mood or a pain: the records it files
 * are ordinary rows of their own kinds, written through their own pages' doors ([chatAct]).
 *
 * [key] is a random UUID made on the phone before the first write. [day] is the day the chat is
 * filed under: the calendar's day it was opened for, or the day of its first write. [startedAt] is
 * the wall clock of the first write (also for a chat filed for an earlier day); [updatedAt] the last
 * write, and the newer copy wins when a backup meets this one ([newerImportedRows]).
 */
@Entity(tableName = "chats")
data class ChatRow(
    @PrimaryKey val key: String,
    val day: String,
    val startedAt: Long,
    val updatedAt: Long,
    /** The conversation, written by hand in one fixed order ([encodeChatLog]). */
    val payload: String,
)

/**
 * A food someone marked to keep, with its figures copied rather than pointed at.
 *
 * A snapshot and not a catalogue id on purpose: the things worth marking are not only the ten
 * thousand rows the catalogue already knows. A dish assembled by hand, a packet read off its label,
 * a meal typed from nothing — none of those exist anywhere to point at, and a favourite that could
 * only hold catalogue rows would refuse exactly the foods that were most work to enter.
 *
 * [key] is the catalogue id where the food came from one, and otherwise the folded title behind
 * [FAVORITE_NAME_PREFIX], which no id can begin with ([favoriteKey]), so
 * marking the same thing twice replaces it rather than stacking up. Every figure here is for the
 * whole portion, as [JournalEntry] stores them, so filling the form back in is a copy and not a
 * conversion.
 */
@Entity(tableName = "favorite_foods")
data class FavoriteFood(
    @PrimaryKey val key: String,
    val title: String,
    val subtitle: String = "",
    val servingG: Double = 0.0,
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val saturatedFatG: Double? = null,
    val sodiumMg: Double? = null,
    val cholesterolMg: Double? = null,
    val createdAt: Long,
)

/**
 * A dish somebody assembled out of products and gave a name to.
 *
 * The journal keeps what a meal was worth, never what it was made of — the ingredient breakdown is
 * summed into one row and thrown away — so a dish built once could only be logged again by picking
 * its products over from scratch. This keeps the recipe rather than the reckoning: catalogue ids
 * and grams, in the order they were chosen, so the dish comes back exactly as it was saved and its
 * figures are recomputed from the catalogue rather than trusted from a snapshot.
 *
 * Deliberately not a [FavoriteFood]. A favourite is a photograph of a finished food's numbers; this
 * is the list those numbers came from, and only this can be reopened and adjusted.
 */
@Entity(tableName = "saved_dishes")
data class SavedDish(
    /** The folded name, so saving the same dish twice replaces it instead of stacking up. */
    @PrimaryKey val key: String,
    val name: String,
    /** `id=grams` per ingredient, pipe separated — the shape the dish templates already use. */
    val items: String,
    val createdAt: Long,
)

/**
 * A photograph of the person, taken the day they stepped on the scale.
 *
 * The number says how much; the picture says where. It is kept in the app's own files directory
 * rather than the gallery — a body photograph is the last thing to leave lying in a shared album —
 * and keyed by the day the way the weigh-in is, not by the weigh-in's row: a weight corrected or
 * deleted later should not take the photograph with it. The row holds a file name only; the bytes
 * live beside the database and travel in the backup, because a file name on another phone names
 * nothing.
 */
@Entity(tableName = "progress_photos", indices = [Index("day")])
data class ProgressPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val takenAt: Long,
    /** The file's name inside the progress directory; the directory is the phone's to choose. */
    val file: String,
)

/**
 * A medicine someone was told to take, and the instruction that came with it.
 *
 * The course is the plan; a dose actually taken is a [JournalEntry] of kind [KIND_MED] pointing back
 * at this row. Keeping the two apart is what lets a schedule be corrected without rewriting history,
 * and what puts every marked dose in the day list, the calendar and the backup for free.
 *
 * [times] is a comma-separated list of minutes from midnight rather than a table of its own: a
 * course has two or three of them, they are always read together, and a join buys nothing.
 */
@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /**
     * Free text, as the box prints it: "1 tablet", "10 mg". Nothing is computed from it; the symptom chat
     * only reads a whole count of tablets in it, to ask whether more were taken ([MedicineWords.overdose]).
     */
    val dose: String = "",
    /** [MedSchedule] name: at the times below, or whenever the person gets up. */
    val schedule: String = "CLOCK",
    val times: String = "",
    /** [MedFood] name — the one prescription instruction this app can act on. */
    val foodRelation: String = "ANY",
    val startDay: String,
    /** Null for an open-ended course, which is what a long-term prescription is. */
    val endDay: String? = null,
    val note: String = "",
    /**
     * Files the person attached — a photograph of the prescription, a scan, a letter. Stored as
     * newline-separated content URIs whose read permission this app has taken persistably: the
     * files stay where their owner put them and are never copied into the app's own storage.
     */
    val attachments: String = "",
    /**
     * What one dose holds, where the person wrote it down: `VITAMIN_D=50;MAGNESIUM=200`, each figure
     * in its [Nutrient]'s own unit. Empty for a course nobody described this way, which is not
     * "contains nothing" — and [dose] beside it stays the free text the box prints.
     */
    val contents: String = "",
    val createdAt: Long,
    /**
     * The photograph of the box or the blister, by file name inside the app's own medicines
     * directory ([medicinePhotoDir]); empty for a course nobody photographed. A copy the app keeps,
     * unlike [attachments]: it is the picture the course is recognised by, and it has to be there
     * on the morning a dose is due whatever has happened to the gallery since.
     */
    val photo: String = "",
)

/**
 * A visit to a doctor: one ahead of time is an appointment, one marked [doneAt] is a record of having
 * been. The same row does both jobs, because the second is what the first becomes.
 *
 * [specialty] holds a [Checkup] name or [VISIT_SPECIALTY_OTHER], which is what lets a marked visit
 * wind the clock on the matching routine examination without a second table to keep in step.
 */
@Entity(tableName = "appointments")
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val specialty: String,
    val doctor: String = "",
    val place: String = "",
    val atMs: Long,
    val note: String = "",
    /** Days before the visit to say something. Zero means on the day itself. */
    val remindDaysBefore: Int = 1,
    val doneAt: Long? = null,
    /** Written when the reminder went out, so it goes out once even across a restart. */
    val remindedAt: Long? = null,
    /** As [Medication.attachments]: what the visit came home with, referred to rather than copied. */
    val attachments: String = "",
)

/**
 * A saved workout: a name, a picture, the days it is for and an ordered list of exercises.
 *
 * Templates exist because the same session gets logged over and over, and typing it out each time is
 * how a training diary dies in week two. Starting one writes ordinary journal entries, editable like
 * any other. A date it is planned for is a journal row too, of kind [KIND_PLAN], which names the
 * template by id and goes when the template is deleted.
 */
@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** A line or two under the name; empty for a template nobody described. */
    val description: String = "",
    /**
     * The template's picture, by file name inside the app's own templates directory
     * ([routinePhotoDir]); empty for none. Kept as a medicine's photograph is, and for the same
     * reason: the gallery it was picked from can lose it.
     */
    val photo: String = "",
    /** The days of the week it is for, Monday as the lowest bit ([routineBit]); 0 for none chosen. */
    val days: Int = 0,
)

@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(
            entity = Routine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["routineId", "position"])],
)
data class RoutineExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    /** Position in the session, so the order survives editing. */
    val position: Int,
    val activityCode: String,
    val durationMinutes: Double,
    val sets: Int? = null,
    val reps: Int? = null,
    val loadKg: Double? = null,
    /**
     * The superset this exercise is done in, the same number on each of its exercises, which stand
     * next to one another; null for an exercise done on its own.
     */
    val superset: Int? = null,
    /** The rest after it: after the last exercise of a superset, the rest after the whole round. */
    val restSeconds: Int = 0,
    /**
     * Each set's own figures and the rest after it ([routineSets]): reps and kilograms for a counted
     * exercise, `[{"reps":10,"loadKg":20.0,"rest":60}, …]` — 10 × 20 kg, then 10 × 25, then 7 × 30 —
     * or seconds for a timed one, `[{"seconds":330,"rest":30}, …]`. Empty only for an exercise with
     * no figures per set, which [sets], [reps], [loadKg] and [durationMinutes]
     * alone describe.
     */
    val setsJson: String = "",
)

/** Single-row table holding the accumulator state. */
@Entity(tableName = "state")
data class StateRow(
    @PrimaryKey val id: Int = 0,
    val bootId: Long,
    val lastCumulative: Long,
    val lastEventNanos: Long = 0,
    val pendingSteps: Long = 0,
    val boutStartedNanos: Long = 0,
    val boutLastNanos: Long = 0,
    val boutConfirmed: Boolean = false,
    val rateWindowStartedNanos: Long = -1,
    val rateWindowStartCounter: Long = -1,
    val pendingDay: String = "",
    val pendingDaySteps: Long = 0,
    /** Wall clock of the last credited step: the only thing that can answer "how long still?". */
    val lastStepAtMs: Long = 0,
)

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val points: Int = 0,
    val walkingDistanceMeters: Double = 0.0,
    val runningDistanceMeters: Double = 0.0,
    val vehicleDistanceMeters: Double = 0.0,
    val stoppedMillis: Long = 0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    /**
     * What the walk is called: one of the light-hearted names in `walk_names`, drawn when the
     * recording starts, in the language the app spoke then. Empty on a route recorded without a
     * name; [title] picks one for those by when the walk started, so in one language such a walk
     * reads the same each time — in the app's current language, since it is not stored.
     */
    val name: String = "",
    /**
     * When the recording was paused, while it is; null while it records and once it has ended. A
     * paused route keeps listening for its position but stores no fix, and its clock stands still;
     * see [movingMillis].
     */
    val pausedAt: Long? = null,
    /** How long the recording stood paused before [pausedAt], summed over every pause it had. */
    val pausedMillis: Long = 0,
    /**
     * The offset from UTC, in seconds, of the clock the recording started on: the hour Health Connect
     * is told the walk was taken at ([offsetAt]). Null where nothing says, read as the phone's zone.
     */
    val zoneOffsetSeconds: Int? = null,
)

/** The offset a route's records are published with: its own, and otherwise the phone's [zone] at [time]. */
internal fun TrackEntity.offsetAt(time: Instant, zone: ZoneId): ZoneOffset =
    zoneOffsetSeconds?.let(ZoneOffset::ofTotalSeconds) ?: zone.rules.getOffset(time)

/**
 * How long the route was walked: from its start to its end — or to [now] while it records — less
 * the time it stood paused, the pause still open included.
 */
internal fun TrackEntity.movingMillis(now: Long): Long {
    val until = endedAt ?: now
    val open = pausedAt?.let { (until - it).coerceAtLeast(0) } ?: 0
    return (until - startedAt - pausedMillis - open).coerceAtLeast(0)
}

@Entity(
    tableName = "trackpoints",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "timestamp"])],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val timestamp: Long,
    val elapsedRealtimeNanos: Long = 0,
    /** Original GNSS measurement; never overwritten by filtering. */
    val lat: Double,
    val lon: Double,
    /** Coordinates used for distance and display after correlation. */
    val correctedLat: Double? = null,
    val correctedLon: Double? = null,
    val altitudeM: Double?,
    val verticalAccuracyM: Float? = null,
    val accuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float? = null,
    val pressureHpa: Float? = null,
    val barometricAltitudeM: Float? = null,
    val ambientTemperatureC: Float? = null,
    val movementType: String = RouteMovement.UNKNOWN.name,
    val cadenceHz: Float = 0f,
    val motionStd: Float = 0f,
    val environmentSpike: Boolean = false,
    val provider: String,
    /** Raw fixes are kept even when filtered out, so a bad filter can be re-run offline. */
    val accepted: Boolean,
    val filterReason: String = "",
    /**
     * Whether [altitudeM] is height above mean sea level rather than above the WGS84 ellipsoid — all
     * a raw fix carries, and up to a hundred metres from the sea. Android 14's fused provider adds
     * the sea-level figure; the barometer is calibrated to whichever one the track got, and the GPX
     * export says which.
     */
    val altitudeMsl: Boolean = false,
    /**
     * The first fix after a pause, or after a stretch the phone did not see and the correlation filter
     * re-anchored past: the line is not drawn, and no distance is counted, from the fix before it — the
     * walker may have gone anywhere meanwhile.
     */
    val segmentStart: Boolean = false,
)

/**
 * A photograph taken during a recording, and the spot it was taken from in enough detail to stand
 * there again: where (the route's own accepted fix nearest the shutter, or the phone's own last
 * known position when that is nearer the moment), which way the camera looked (compass bearing from
 * true north), how far it tilted up or down, and how the phone was turned in the hands. Each reading
 * is null when the phone could not give it — no fix near the moment, no compass or nowhere to read
 * true north from, or no sensor event heard while the camera app was in front.
 *
 * The bytes live in the app's own files under [WALK_PHOTO_DIR], named after [takenAt] like progress
 * photographs; the row holds the file name only. Only the row goes with its route, by the cascade
 * below; [deleteWalk], which deletes a route, deletes its files under [WALK_PHOTO_DIR] with it.
 */
@Entity(
    tableName = "track_photos",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "takenAt"])],
)
data class TrackPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** The shutter's moment, from the photograph's own EXIF stamp when the camera app wrote one. */
    val takenAt: Long,
    /** The file's name inside the walk photo directory. */
    val file: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Double? = null,
    /** How far off the fix the position came from could be, in metres. */
    val accuracyM: Float? = null,
    /** Which way the back camera looked: degrees clockwise from true north, 0 up to 360. */
    val bearingDeg: Float? = null,
    /** The rotation sensor's own estimate of how far off [bearingDeg] may be, in degrees. */
    val bearingAccuracyDeg: Float? = null,
    /** How far the camera pointed above (+) or below (−) the horizon, in degrees. */
    val pitchDeg: Float? = null,
    /**
     * How the phone was turned about the camera's axis: 0 upright in portrait, 90 on its side with
     * its top to the left, −90 with its top to the right, ±180 upside down. Null with the camera aimed
     * nearly straight down or up, where the turn cannot be told.
     */
    val rollDeg: Float? = null,
)

@Dao
interface StepsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordAppOpen(row: AppOpenDay)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restoreAppOpenDays(rows: List<AppOpenDay>)

    @Query("SELECT * FROM app_open_days ORDER BY day")
    fun appOpenDays(): Flow<List<AppOpenDay>>

    @Query("SELECT * FROM app_open_days ORDER BY day")
    suspend fun allAppOpenDays(): List<AppOpenDay>

    /** UI totals choose the stronger source; phone and wearable totals can overlap, so never add. */
    @Query(
        """
        SELECT day, MAX(steps) AS steps FROM (
            SELECT day, steps FROM days WHERE day >= :from
            UNION ALL
            SELECT day, steps FROM external_step_days WHERE day >= :from
            UNION ALL
            SELECT day, MAX(steps) AS steps FROM direct_ble_step_days
                WHERE day >= :from GROUP BY day
        ) GROUP BY day ORDER BY day
        """,
    )
    fun daysSince(from: String): Flow<List<DaySteps>>

    @Query(
        """
        SELECT day, MAX(steps) AS steps FROM (
            SELECT day, steps FROM days WHERE day BETWEEN :from AND :to
            UNION ALL
            SELECT day, steps FROM external_step_days WHERE day BETWEEN :from AND :to
            UNION ALL
            SELECT day, MAX(steps) AS steps FROM direct_ble_step_days
                WHERE day BETWEEN :from AND :to GROUP BY day
        ) GROUP BY day ORDER BY day
        """,
    )
    fun daysBetween(from: String, to: String): Flow<List<DaySteps>>

    @Query(
        """
        SELECT day, MAX(steps) AS steps FROM (
            SELECT day, steps FROM days
            UNION ALL
            SELECT day, steps FROM external_step_days
            UNION ALL
            SELECT day, MAX(steps) AS steps FROM direct_ble_step_days GROUP BY day
        ) GROUP BY day ORDER BY day
        """,
    )
    suspend fun allDays(): List<DaySteps>

    /** Rows updated by the accumulator; Health Connect export also checks [LegacyStepDay]. */
    @Query("SELECT * FROM days ORDER BY day")
    suspend fun allLocalDays(): List<DaySteps>

    @Query(
        """
        SELECT day, MAX(steps) AS steps FROM (
            SELECT day, steps FROM days WHERE day = :day
            UNION ALL
            SELECT day, steps FROM external_step_days WHERE day = :day
            UNION ALL
            SELECT day, MAX(steps) AS steps FROM direct_ble_step_days
                WHERE day = :day GROUP BY day
        ) GROUP BY day
        """,
    )
    fun day(day: String): Flow<DaySteps?>

    @Query(
        """
        SELECT MAX(steps) FROM (
            SELECT steps FROM days WHERE day = :day
            UNION ALL
            SELECT steps FROM external_step_days WHERE day = :day
            UNION ALL
            SELECT MAX(steps) AS steps FROM direct_ble_step_days WHERE day = :day
        )
        """,
    )
    suspend fun stepsOn(day: String): Long?

    @Query("SELECT steps FROM days WHERE day = :day")
    suspend fun localStepsOn(day: String): Long?

    @Query("UPDATE days SET steps = steps + :delta WHERE day = :day")
    suspend fun bump(day: String, delta: Long): Int

    @Upsert
    suspend fun put(row: DaySteps)

    @Query("UPDATE hourly_steps SET steps = steps + :delta WHERE day = :day AND hour = :hour")
    suspend fun bumpHour(day: String, hour: Int, delta: Long): Int

    @Upsert
    suspend fun putHour(row: HourSteps)

    @Upsert
    suspend fun putHours(rows: List<HourSteps>)

    @Query("SELECT * FROM hourly_steps WHERE day = :day ORDER BY hour")
    fun hoursOn(day: String): Flow<List<HourSteps>>

    @Query("SELECT * FROM hourly_steps ORDER BY day, hour")
    suspend fun allHourSteps(): List<HourSteps>

    @Query("DELETE FROM external_step_days WHERE day BETWEEN :from AND :to")
    suspend fun deleteExternalDaysBetween(from: String, to: String)

    @Upsert
    suspend fun putExternalDays(rows: List<ExternalDaySteps>)

    /** For the backup. Never fed back to Health Connect: only [allLocalDays] is this phone's own. */
    @Query("SELECT * FROM external_step_days ORDER BY day")
    suspend fun allExternalDays(): List<ExternalDaySteps>

    @Query("DELETE FROM external_step_origin_days WHERE day BETWEEN :from AND :to")
    suspend fun deleteExternalOriginDaysBetween(from: String, to: String)

    @Upsert
    suspend fun putExternalOriginDays(rows: List<ExternalOriginDaySteps>)

    /**
     * Every app's own share of the Health Connect days, for the sources screen and the backup.
     *
     * Ordered by package inside the day so that two apps that gave a day exactly the same total are
     * ranked the same way at every opening of the screen; a tie that changed hands between two
     * readings would make the winner look like noise.
     */
    @Query("SELECT * FROM external_step_origin_days ORDER BY day, packageName")
    suspend fun allExternalOriginDays(): List<ExternalOriginDaySteps>

    @Upsert
    suspend fun putDirectBleDays(rows: List<DirectBleDaySteps>)

    /** Full provenance-preserving export; one row per source and day. */
    @Query("SELECT * FROM direct_ble_step_days ORDER BY day, sourceId")
    suspend fun allDirectBleDays(): List<DirectBleDaySteps>

    @Query("SELECT * FROM legacy_step_days ORDER BY day")
    suspend fun allLegacyStepDays(): List<LegacyStepDay>

    @Query("SELECT * FROM legacy_step_days WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun legacyStepDaysBetween(from: String, to: String): List<LegacyStepDay>

    @Upsert
    suspend fun putLegacyStepDays(rows: List<LegacyStepDay>)

    @Query("SELECT * FROM barometer_days WHERE day = :day")
    suspend fun barometerDay(day: String): BarometerDay?

    @Query("SELECT * FROM barometer_days WHERE day BETWEEN :from AND :to ORDER BY day")
    fun barometerDaysBetween(from: String, to: String): Flow<List<BarometerDay>>

    @Upsert
    suspend fun saveBarometerDay(row: BarometerDay)

    @Query("SELECT * FROM barometer_days ORDER BY day")
    suspend fun allBarometerDays(): List<BarometerDay>

    @Upsert
    suspend fun saveBarometerDays(rows: List<BarometerDay>)

    @Query("SELECT * FROM profile WHERE id = 0")
    fun profile(): Flow<UserProfile?>

    @Query("SELECT * FROM profile WHERE id = 0")
    suspend fun profileNow(): UserProfile?

    @Upsert
    suspend fun saveProfile(profile: UserProfile)

    @Query("SELECT * FROM journal WHERE day = :day ORDER BY occurredAt DESC")
    fun journalOn(day: String): Flow<List<JournalEntry>>

    /** The last few readings of one kind, for the history card under the measurement editor. */
    @Query(
        """
        SELECT * FROM journal
        WHERE kind = 'MEASUREMENT'
          AND (
            (:mode = 'WEIGHT' AND measurementWeightKg IS NOT NULL)
            OR (:mode = 'HEIGHT' AND measurementHeightCm IS NOT NULL)
            OR (:mode = 'HEART_RATE' AND measurementHeartRateBpm IS NOT NULL)
            OR (:mode = 'SLEEP' AND sleepStartAt IS NOT NULL AND sleepEndAt IS NOT NULL)
            -- A tape measure has no column of its own; its three readings live in the payload,
            -- and LIKE is how a query reaches them. The parser decides what a match really holds.
            OR (:mode = 'GIRTH' AND measurementPayloadJson LIKE '%Circumference%')
          )
        ORDER BY occurredAt DESC
        LIMIT :limit
        """,
    )
    fun recentMeasurements(mode: String, limit: Int): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal WHERE day BETWEEN :from AND :to ORDER BY occurredAt DESC")
    fun journalBetween(from: String, to: String): Flow<List<JournalEntry>>

    /**
     * One kind's rows over a range of days, newest first. The Work screen's history reaches half a
     * year back, and every other row of those months would be read only to be thrown away.
     */
    @Query("SELECT * FROM journal WHERE kind = :kind AND day BETWEEN :from AND :to ORDER BY occurredAt DESC")
    fun journalOfKindBetween(kind: String, from: String, to: String): Flow<List<JournalEntry>>

    /**
     * The rows of [kinds] dated before [before]: the few kinds whose whole history a screen reads —
     * cycle starts and ends, lab panels — without the years of meals and drinks around them.
     */
    @Query("SELECT * FROM journal WHERE kind IN (:kinds) AND day < :before ORDER BY occurredAt DESC")
    fun journalOfKindsBefore(kinds: List<String>, before: String): Flow<List<JournalEntry>>

    /**
     * The workout sessions named "[name] #…" so far, for the next one's number ([nextSessionNumber]).
     * LIKE reads a `%` or `_` in the name as a wildcard and folds ASCII case, so a few sessions of
     * other names can come back too; [nextSessionNumber] holds each title to the name exactly.
     */
    @Query(
        """
        SELECT DISTINCT subtitle FROM journal
        WHERE kind IN ('ACTIVITY', 'NOT_DONE') AND subtitle LIKE :name || ' #%'
        """,
    )
    suspend fun sessionTitles(name: String): List<String>

    /** Whether any row of [kind] lies before [day]: what a list that stops at a window has left out. */
    @Query("SELECT EXISTS(SELECT 1 FROM journal WHERE kind = :kind AND day < :day)")
    fun hasJournalBefore(kind: String, day: String): Flow<Boolean>

    /**
     * The newest measured weight and height, which override the onboarding baseline from their
     * timestamp forward.
     *
     * One scalar each rather than the whole journal: the app root needs exactly these two numbers,
     * and collecting every row ever written to read them off the front of the list would hold it in
     * composition state for the process lifetime and re-materialise it on every save.
     *
     * The weight is the newest one inside [PLAUSIBLE_KG], the gate [recalculatedActivityTimeline]
     * and the Health Connect export already put on a weighing. A connected scale's reading is kept
     * down to 2 kg, and without the gate a child weighed on it would become the profile's weight.
     *
     * Both are read off MEASUREMENT rows only, as the timeline reads them ([weightInEffectBefore]): a
     * row the editor moved to another kind keeps the figure it carried, and the profile would then
     * count with a weight no activity was costed at. The kind also gives the query an index to look
     * in: a height is written down a few times a year, and without the index every change to the
     * journal would walk back to the last one through every row since.
     */
    @Query(
        "SELECT measurementWeightKg FROM journal " +
            "WHERE measurementWeightKg BETWEEN $MIN_PLAUSIBLE_KG AND $MAX_PLAUSIBLE_KG " +
            "AND kind = 'MEASUREMENT' ORDER BY occurredAt DESC LIMIT 1",
    )
    fun latestMeasuredWeightKg(): Flow<Double?>

    @Query(
        "SELECT measurementHeightCm FROM journal " +
            "WHERE measurementHeightCm IS NOT NULL AND kind = 'MEASUREMENT' ORDER BY occurredAt DESC LIMIT 1",
    )
    fun latestMeasuredHeightCm(): Flow<Int?>

    @Query("SELECT * FROM journal ORDER BY occurredAt, id")
    suspend fun allJournal(): List<JournalEntry>

    /** Every row from [occurredAt] on, oldest first. */
    @Query("SELECT * FROM journal WHERE occurredAt >= :occurredAt ORDER BY occurredAt, id")
    suspend fun journalSince(occurredAt: Long): List<JournalEntry>

    /**
     * The tail of the timeline a change can still reach, in the order the recalculation walks it:
     * the weighings [recalculatedActivityTimeline] carries forward and the activities it costs. Every
     * other kind passes through it untouched, and with a history imported they are most of what a
     * full pass would read into memory — the meals, the drinks, the nights, the readings of a watch.
     */
    @Query(
        "SELECT * FROM journal WHERE occurredAt >= :occurredAt AND kind IN ('MEASUREMENT', 'ACTIVITY') " +
            "ORDER BY occurredAt, id",
    )
    suspend fun activityTimelineSince(occurredAt: Long): List<JournalEntry>

    /**
     * The weight in force just before [occurredAt], which is where a bounded recalculation has to
     * start. Both filters are the ones [recalculatedActivityTimeline] applies — it moves the weight
     * only on a MEASUREMENT row and only for a reading inside the range — so a bounded pass and a
     * full one cannot disagree about where they start from.
     */
    @Query(
        "SELECT measurementWeightKg FROM journal WHERE kind = 'MEASUREMENT' " +
            "AND measurementWeightKg BETWEEN $MIN_PLAUSIBLE_KG AND $MAX_PLAUSIBLE_KG " +
            "AND occurredAt < :occurredAt ORDER BY occurredAt DESC, id DESC LIMIT 1",
    )
    suspend fun weightInEffectBefore(occurredAt: Long): Double?

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun journalEntry(id: Long): JournalEntry?

    @Query(
        "SELECT * FROM journal WHERE day = :day AND kind = 'MEASUREMENT' " +
            "AND measurementSource = 'PHONE_USAGE_ESTIMATE' LIMIT 1",
    )
    suspend fun estimatedSleepOn(day: String): JournalEntry?

    /** The rows the mirror is about to publish, named one by one; see [editedJournalRows]. */
    @Query("SELECT * FROM journal WHERE id IN (:ids) ORDER BY occurredAt, id")
    suspend fun journalRows(ids: Collection<Long>): List<JournalEntry>

    /** Newest identical portion today, so a bounced tap can be told from a second glass. */
    @Query(
        "SELECT MAX(occurredAt) FROM journal WHERE kind = :kind AND day = :day " +
            "AND servingG = :servingG",
    )
    suspend fun lastPortionAtMs(kind: String, day: String, servingG: Double): Long?

    @Query("SELECT MAX(occurredAt) FROM journal WHERE kind = :kind AND day = :day")
    suspend fun lastEntryAtMs(kind: String, day: String): Long?

    /**
     * Newest entry inside a window of time, for a cooldown that has to survive midnight. Closed on
     * the far side too: a row stamped further ahead than the cooldown looks is not an entry of the
     * window at all, and as the newest on file it would hide the real ones before it ([lastMoodNear]).
     */
    @Query(
        "SELECT MAX(occurredAt) FROM journal WHERE kind = :kind " +
            "AND occurredAt > :sinceMs AND occurredAt < :untilMs",
    )
    suspend fun lastEntryBetweenMs(kind: String, sinceMs: Long, untilMs: Long): Long?

    @Query("SELECT COUNT(*) FROM journal WHERE day = :day")
    suspend fun journalCountOn(day: String): Int

    // Four bare counts for the diagnostics report: how big the database is, and nothing of what
    // is in it.
    @Query("SELECT COUNT(*) FROM journal")
    suspend fun journalCount(): Int

    @Query("SELECT COUNT(*) FROM days")
    suspend fun stepDayCount(): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Query("SELECT COUNT(*) FROM progress_photos")
    suspend fun progressPhotoCount(): Int

    @Query("SELECT * FROM journal WHERE day = :day ORDER BY occurredAt DESC")
    suspend fun journalOnNow(day: String): List<JournalEntry>

    /**
     * The cycle marks, read once rather than watched. [logCycleStart] needs to know which periods
     * are already on file before it writes, and a Flow cannot answer inside a transaction.
     */
    @Query("SELECT * FROM journal WHERE kind IN ('CYCLE', 'CYCLE_END') ORDER BY day")
    suspend fun cycleMarksNow(): List<JournalEntry>

    /**
     * Distinct recent meals, newest first: the shortcut that makes a food diary survive week two.
     *
     * A cup with milk from the caffeine dialog ([isCaffeineCup], the same pair of columns) is left
     * out: a meal repeated from here is saved without caffeine, so a daily cappuccino repeated from
     * the top of this list would lose its milligrams. The dialog is where it is logged again.
     */
    @Query(
        "SELECT * FROM journal WHERE kind = 'EAT' AND id IN " +
            "(SELECT MAX(id) FROM journal WHERE kind = 'EAT' " +
            "AND NOT (caffeineMg IS NOT NULL AND foodSource LIKE '$CAPPUCCINO_FOOD_ID=%' " +
            "AND foodSource NOT LIKE '%|%') GROUP BY title) " +
            "ORDER BY occurredAt DESC LIMIT :limit",
    )
    fun recentMeals(limit: Int = 12): Flow<List<JournalEntry>>

    /** The medical history, newest start first; a row nobody dated comes last. */
    @Query("SELECT * FROM medical_history ORDER BY startDay DESC, updatedAt DESC")
    fun medicalHistory(): Flow<List<MedicalHistoryRow>>

    @Query("SELECT * FROM medical_history ORDER BY startDay DESC, updatedAt DESC")
    suspend fun allMedicalHistory(): List<MedicalHistoryRow>

    @Upsert
    suspend fun saveMedicalHistoryRow(row: MedicalHistoryRow)

    @Upsert
    suspend fun saveMedicalHistoryRows(rows: List<MedicalHistoryRow>)

    /** The row alone, with no tombstone: a line a person deletes goes through [deleteMedicalHistory]. */
    @Query("DELETE FROM medical_history WHERE key = :key")
    suspend fun deleteMedicalHistoryRow(key: String)

    /** Every chat, newest first: by its first write, then by key for two begun in one instant. */
    @Query("SELECT * FROM chats ORDER BY startedAt DESC, key DESC")
    fun chats(): Flow<List<ChatRow>>

    @Query("SELECT * FROM chats ORDER BY startedAt DESC, key DESC")
    suspend fun allChats(): List<ChatRow>

    /** The chats begun since [sinceMs], for the lines a chat says at most once a week or a month. */
    @Query("SELECT * FROM chats WHERE startedAt >= :sinceMs ORDER BY startedAt DESC, key DESC")
    suspend fun chatsSince(sinceMs: Long): List<ChatRow>

    @Query("SELECT * FROM chats WHERE key = :key")
    suspend fun chat(key: String): ChatRow?

    @Upsert
    suspend fun saveChat(row: ChatRow)

    @Upsert
    suspend fun saveChats(rows: List<ChatRow>)

    @Query("DELETE FROM chats WHERE key = :key")
    suspend fun deleteChat(key: String)

    /**
     * Rows a chat filed are found again by the two things a restore keeps: their kind and their
     * moment. Every row on that moment, so the one the chat wrote can be told from another by its
     * fingerprint ([rowSig]) and an ambiguous moment is never taken for the chat's own.
     */
    @Query("SELECT * FROM journal WHERE kind = :kind AND occurredAt = :at ORDER BY id")
    suspend fun journalAt(kind: String, at: Long): List<JournalEntry>

    /**
     * The newest row of [kind] strictly between [sinceMs] and [untilMs]: the mood cooldown's row
     * itself ([moodNear]), where [lastEntryBetweenMs] gives only its moment. Closed on the far side
     * for the same reason as that one.
     */
    @Query(
        "SELECT * FROM journal WHERE kind = :kind AND occurredAt > :sinceMs AND occurredAt < :untilMs " +
            "ORDER BY occurredAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestEntryBetween(kind: String, sinceMs: Long, untilMs: Long): JournalEntry?

    /** The courses written down at [createdAt]: a chat's course is found again by that moment, never by its id. */
    @Query("SELECT * FROM medications WHERE createdAt = :createdAt ORDER BY id")
    suspend fun medicationsCreatedAt(createdAt: Long): List<Medication>

    /** The foods marked to keep, newest mark first. */
    @Query("SELECT * FROM favorite_foods ORDER BY createdAt DESC")
    fun favoriteFoods(): Flow<List<FavoriteFood>>

    @Query("SELECT * FROM favorite_foods ORDER BY createdAt DESC")
    suspend fun allFavoriteFoods(): List<FavoriteFood>

    @Upsert
    suspend fun saveFavoriteFood(row: FavoriteFood)

    @Upsert
    suspend fun saveFavoriteFoods(rows: List<FavoriteFood>)

    @Query("DELETE FROM favorite_foods WHERE key = :key")
    suspend fun deleteFavoriteFood(key: String)

    /** The dishes somebody built and named, newest first. */
    @Query("SELECT * FROM saved_dishes ORDER BY createdAt DESC")
    fun savedDishes(): Flow<List<SavedDish>>

    @Query("SELECT * FROM saved_dishes ORDER BY createdAt DESC")
    suspend fun allSavedDishes(): List<SavedDish>

    @Upsert
    suspend fun saveSavedDish(row: SavedDish)

    @Upsert
    suspend fun saveSavedDishes(rows: List<SavedDish>)

    @Query("DELETE FROM saved_dishes WHERE key = :key")
    suspend fun deleteSavedDish(key: String)

    /** One day's progress photographs, in the order they were taken. */
    @Query("SELECT * FROM progress_photos WHERE day = :day ORDER BY takenAt")
    fun progressPhotos(day: String): Flow<List<ProgressPhoto>>

    @Query("SELECT * FROM progress_photos ORDER BY takenAt")
    suspend fun allProgressPhotos(): List<ProgressPhoto>

    /** Every photograph, newest first: the gallery is about the whole record, not about one day. */
    @Query("SELECT * FROM progress_photos ORDER BY takenAt DESC")
    fun progressPhotoGallery(): Flow<List<ProgressPhoto>>

    @Upsert
    suspend fun saveProgressPhoto(row: ProgressPhoto)

    @Query("DELETE FROM progress_photos WHERE id = :id")
    suspend fun deleteProgressPhoto(id: Long)

    /** Every row of these exercises, oldest first: what records and the editor's "Last time" are read from. */
    @Query(
        "SELECT * FROM journal WHERE kind = 'ACTIVITY' AND activityCode IN (:codes) ORDER BY occurredAt",
    )
    suspend fun activitiesOf(codes: List<String>): List<JournalEntry>

    /**
     * The exercise codes this person actually logs, most first.
     *
     * The catalogue is thirty-five long and alphabetical, which means the four things somebody
     * does every week are scattered through it. What they have done before is the best guess at
     * what they are about to do.
     */
    @Query(
        "SELECT activityCode FROM journal WHERE kind = 'ACTIVITY' AND activityCode IS NOT NULL " +
            "GROUP BY activityCode ORDER BY COUNT(*) DESC, MAX(occurredAt) DESC LIMIT :limit",
    )
    fun mostUsedActivities(limit: Int): Flow<List<String>>

    /** The row id an insert gave the row; -1 when the upsert found one to update instead. */
    @Upsert
    suspend fun upsertJournalEntry(entry: JournalEntry): Long

    @Upsert
    suspend fun upsertJournalEntries(entries: List<JournalEntry>)

    /**
     * Writes one journal row and remembers which row it was.
     *
     * The remembering is here rather than in the write helpers below because the helpers are not
     * the only writers: the routine that logs a saved session, the onboarding measurement and the
     * activity details the sync fills in all reach the journal through this method and through
     * nothing else. A row the mirror was never told about would simply wait for the next exchange,
     * which is a slower correctness than this costs. See [editedJournalRows].
     *
     * Answers the row's id — the one Room numbered for a new row, the entry's own for one rewritten —
     * for a writer that goes on rewriting the row it began, as the symptom chat does.
     */
    suspend fun saveJournalEntry(entry: JournalEntry): Long {
        val inserted = upsertJournalEntry(entry)
        val id = if (inserted > 0L) inserted else entry.id
        rememberEditedJournalRow(id)
        return id
    }

    /**
     * The same for a batch. Every caller hands rows that already exist — the recalculated
     * activities — so their ids are the rows'; a row with no id yet is one Room is about to number,
     * and the single-row path above is what every writer of a new row takes.
     */
    suspend fun saveJournalEntries(entries: List<JournalEntry>) {
        upsertJournalEntries(entries)
        entries.forEach { if (it.id != 0L) rememberEditedJournalRow(it.id) }
    }

    /**
     * Insert, not upsert: a restored row's id belongs to the install that exported it. Nothing is
     * remembered for the mirror either — a restore is a whole journal arriving at once, not an
     * edit, and the exchange that follows publishes it.
     */
    @Insert
    suspend fun insertJournalEntries(entries: List<JournalEntry>)

    @Query("DELETE FROM journal WHERE id = :id")
    suspend fun deleteJournalEntry(id: Long)

    @Upsert
    suspend fun saveTombstone(row: JournalTombstone)

    @Upsert
    suspend fun saveTombstones(rows: List<JournalTombstone>)

    @Query("SELECT * FROM journal_tombstones ORDER BY recordKey")
    suspend fun allTombstones(): List<JournalTombstone>

    /** Whether the row a Health Connect record stands for was deleted here. */
    @Query("SELECT EXISTS(SELECT 1 FROM journal_tombstones WHERE recordKey = :recordKey)")
    suspend fun isTombstoned(recordKey: String): Boolean

    /** The same question for a day's rate, which may also be filed under a longer, legacy marker. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM journal_tombstones WHERE recordKey = :recordKey " +
            "OR (recordKey LIKE 'Health Connect:%' AND recordKey LIKE '%:heart:' || :day))",
    )
    suspend fun isDailyHeartRateTombstoned(recordKey: String, day: String): Boolean

    /** Every sleep record filed under a night, whatever wrote it. */
    @Query(
        "SELECT * FROM journal WHERE day = :day " +
            "AND sleepStartAt IS NOT NULL AND sleepEndAt IS NOT NULL ORDER BY occurredAt",
    )
    suspend fun sleepOn(day: String): List<JournalEntry>

    /** Import marker across every kind: water and meals carry one too. */
    @Query("SELECT EXISTS(SELECT 1 FROM journal WHERE healthConnectRecordKey = :marker)")
    suspend fun hasImportedEntry(marker: String): Boolean

    /** Marker identity is global; an owner edit may move its event onto another local day. */
    @Query("SELECT * FROM journal WHERE healthConnectRecordKey = :marker LIMIT 1")
    suspend fun importedEntry(marker: String): JournalEntry?

    /**
     * The id and marker of every row a Health Connect record was imported into and nobody has
     * corrected since: a correction is the person's own figure now, and stays when its source
     * withdraws the record. Read once for all of a feed's deletions ([importedRowsOf]). A deletion
     * names no origin, so the marker's index cannot find its rows, and asked an id at a time the
     * journal would be read through once for every deletion.
     */
    @Query(
        "SELECT id, healthConnectRecordKey AS marker FROM journal " +
            "WHERE healthConnectRecordKey LIKE 'Health Connect:%' AND editedAt IS NULL",
    )
    suspend fun uneditedImportMarkers(): List<ImportMarker>

    /** The rows of a kind standing within a moment of an instant, whatever wrote them. */
    @Query("SELECT * FROM journal WHERE kind = :kind AND occurredAt BETWEEN :fromMs AND :toMs")
    suspend fun journalAround(kind: String, fromMs: Long, toMs: Long): List<JournalEntry>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM journal WHERE kind = 'MEASUREMENT' " +
            "AND measurementSource = :source AND measurementPayloadJson = :payload)",
    )
    suspend fun hasDirectMeasurement(source: String, payload: String): Boolean

    @Query("SELECT * FROM medications ORDER BY createdAt")
    fun medications(): Flow<List<Medication>>

    @Query("SELECT * FROM medications ORDER BY createdAt")
    suspend fun allMedications(): List<Medication>

    @Upsert
    suspend fun saveMedication(row: Medication)

    /** Returns the id this database gave the course, which its restored doses have to point at. */
    @Insert
    suspend fun insertMedication(row: Medication): Long

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: Long)

    /** A restored course's photograph, named once its file has landed: never a name of nothing. */
    @Query("UPDATE medications SET photo = :photo WHERE id = :id")
    suspend fun setMedicationPhoto(id: Long, photo: String)

    @Query("SELECT * FROM appointments ORDER BY atMs")
    fun appointments(): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments ORDER BY atMs")
    suspend fun allAppointments(): List<Appointment>

    @Upsert
    suspend fun saveAppointment(row: Appointment)

    @Insert
    suspend fun insertAppointment(row: Appointment)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun deleteAppointment(id: Long)

    @Query("SELECT * FROM routines ORDER BY createdAt DESC")
    fun routines(): Flow<List<Routine>>

    @Query("SELECT * FROM routines ORDER BY createdAt")
    suspend fun allRoutinesNow(): List<Routine>

    @Query("SELECT * FROM routine_exercises ORDER BY routineId, position")
    fun allRoutineExercises(): Flow<List<RoutineExercise>>

    @Query("SELECT * FROM routine_exercises ORDER BY routineId, position")
    suspend fun allRoutineExercisesNow(): List<RoutineExercise>

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY position")
    suspend fun routineExercises(routineId: Long): List<RoutineExercise>

    @Insert
    suspend fun insertRoutine(routine: Routine): Long

    @Update
    suspend fun updateRoutine(routine: Routine)

    /** A restored template's picture, named once its file has landed, as a course's is. */
    @Query("UPDATE routines SET photo = :photo WHERE id = :id")
    suspend fun setRoutinePhoto(id: Long, photo: String)

    @Insert
    suspend fun insertRoutineExercise(exercise: RoutineExercise)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    /** Every exercise of a template, before an edited template's are written over them. */
    @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
    suspend fun deleteRoutineExercises(routineId: Long)

    @Query("SELECT * FROM state WHERE id = 0")
    suspend fun state(): StateRow?

    @Query("SELECT lastStepAtMs FROM state WHERE id = 0")
    suspend fun lastStepAtMs(): Long?

    @Upsert
    suspend fun saveState(row: StateRow)

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert
    suspend fun insertPoint(point: TrackPointEntity)

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun activeTrack(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeTrackNow(): TrackEntity?

    /**
     * The latest twenty routes, newest first: what the map, the track page's info and the day's
     * motion read. Not the history — its page reads [allTracks], or a walk older than the twentieth
     * would have no way back to it.
     */
    @Query("SELECT * FROM tracks ORDER BY startedAt DESC LIMIT 20")
    fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY startedAt")
    suspend fun allTracks(): List<TrackEntity>

    @Upsert
    suspend fun saveTracks(tracks: List<TrackEntity>)

    /**
     * The speed of one track's accepted fixes, thinned in SQL to at most [limit] evenly spaced rows.
     * The Health Connect export wants a sketch of the pace, not the second-by-second record, and
     * drawing that sketch from every point of every track would not fit in memory after a year.
     */
    @Query(
        """
        SELECT timestamp, speedMps FROM (
            SELECT timestamp, speedMps,
                ROW_NUMBER() OVER (ORDER BY timestamp) - 1 AS rn,
                COUNT(*) OVER () AS total
            FROM trackpoints
            WHERE trackId = :trackId AND accepted = 1 AND speedMps IS NOT NULL
        )
        WHERE rn % MAX(1, (total + :limit - 1) / :limit) = 0
        ORDER BY timestamp
        """,
    )
    suspend fun trackSpeedSamples(trackId: Long, limit: Int): List<TrackSpeedSample>

    /** Keyset-paged: OFFSET repeatedly scans the whole prefix of this unbounded table. */
    @Query("SELECT * FROM trackpoints WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun trackPointPageAfter(afterId: Long, limit: Int): List<TrackPointEntity>

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM trackpoints WHERE trackId = :trackId ORDER BY timestamp")
    fun trackPoints(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM trackpoints WHERE trackId = :trackId ORDER BY timestamp")
    suspend fun trackPointsNow(trackId: Long): List<TrackPointEntity>

    @Query(
        "SELECT * FROM trackpoints WHERE trackId = :trackId AND accepted = 1 " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun recentAcceptedPoints(trackId: Long, limit: Int): List<TrackPointEntity>

    @Query(
        """
        UPDATE tracks SET
            distanceMeters = :meters,
            points = :points,
            walkingDistanceMeters = :walkingMeters,
            runningDistanceMeters = :runningMeters,
            vehicleDistanceMeters = :vehicleMeters,
            stoppedMillis = :stoppedMillis,
            elevationGainM = :elevationGainM,
            elevationLossM = :elevationLossM,
            maxSpeedMps = :maxSpeedMps
        WHERE id = :trackId
        """,
    )
    suspend fun updateTrack(
        trackId: Long,
        meters: Double,
        points: Int,
        walkingMeters: Double,
        runningMeters: Double,
        vehicleMeters: Double,
        stoppedMillis: Long,
        elevationGainM: Double,
        elevationLossM: Double,
        maxSpeedMps: Double,
    )

    /**
     * Ends a route; a pause still open when it ends is closed at the same moment and counted. A
     * route already ended keeps the moment it ended at: a second stop — the service's, of a route
     * the widget closed past it — is not a later end.
     */
    @Query(
        """
        UPDATE tracks SET
            endedAt = :endedAt,
            pausedMillis = pausedMillis + CASE WHEN pausedAt IS NULL THEN 0 ELSE MAX(0, :endedAt - pausedAt) END,
            pausedAt = NULL
        WHERE id = :trackId AND endedAt IS NULL
        """,
    )
    suspend fun endTrack(trackId: Long, endedAt: Long)

    /** Pauses a recording route; a route already paused or ended keeps its own moment. */
    @Query("UPDATE tracks SET pausedAt = :at WHERE id = :trackId AND pausedAt IS NULL AND endedAt IS NULL")
    suspend fun pauseTrack(trackId: Long, at: Long)

    /** Resumes a paused route, adding the pause to the time it stood still. */
    @Query(
        """
        UPDATE tracks SET
            pausedMillis = pausedMillis + MAX(0, :at - pausedAt),
            pausedAt = NULL
        WHERE id = :trackId AND pausedAt IS NOT NULL
        """,
    )
    suspend fun resumeTrack(trackId: Long, at: Long)

    /** One walk, for its own page; null once it is gone. */
    @Query("SELECT * FROM tracks WHERE id = :trackId")
    fun track(trackId: Long): Flow<TrackEntity?>

    /**
     * The accepted fix closest in time to [at] — where a photograph was taken from. Ordered on the
     * distance in time rather than bracketed, because a fix may land on either side of the shutter.
     */
    @Query(
        "SELECT * FROM trackpoints WHERE trackId = :trackId AND accepted = 1 " +
            "ORDER BY ABS(timestamp - :at) LIMIT 1",
    )
    suspend fun nearestAcceptedPoint(trackId: Long, at: Long): TrackPointEntity?

    /** One walk's photographs, in the order they were taken — the order their numbers follow. */
    @Query("SELECT * FROM track_photos WHERE trackId = :trackId ORDER BY takenAt")
    fun trackPhotos(trackId: Long): Flow<List<TrackPhotoEntity>>

    @Query("SELECT * FROM track_photos ORDER BY takenAt")
    suspend fun allTrackPhotos(): List<TrackPhotoEntity>

    @Upsert
    suspend fun saveTrackPhoto(row: TrackPhotoEntity): Long

    @Upsert
    suspend fun saveTrackPhotos(rows: List<TrackPhotoEntity>)

    @Query("DELETE FROM track_photos WHERE id = :id")
    suspend fun deleteTrackPhoto(id: Long)

    /**
     * Deletes a finished route and says how many rows went: one, or none when there is no such
     * route or it is still recording — the service is writing into that one. Its fixes and its
     * photographs' rows go with it by their cascades; the photographs' files and the tombstone are
     * [deleteWalk]'s to see to, which is the only caller.
     */
    @Query("DELETE FROM tracks WHERE id = :trackId AND endedAt IS NOT NULL")
    suspend fun deleteTrack(trackId: Long): Int

    // The import wizard's mirror of what other apps have written into the journal. Flows, because
    // the page is open while an import writes and should show the rows as they land.

    /** Every row the Health Connect import filed, whichever app wrote the record behind it. */
    @Query("SELECT COUNT(*) FROM journal WHERE healthConnectRecordKey IS NOT NULL")
    fun importedJournalCount(): Flow<Int>

    /** The earliest day an imported row stands on: how far back the history has actually reached. */
    @Query("SELECT MIN(day) FROM journal WHERE healthConnectRecordKey IS NOT NULL")
    fun oldestImportedDay(): Flow<String?>

    /** The imported rows by kind, largest kind first, for a breakdown the page names kind by kind. */
    @Query(
        "SELECT kind, COUNT(*) AS count FROM journal WHERE healthConnectRecordKey IS NOT NULL " +
            "GROUP BY kind ORDER BY count DESC, kind",
    )
    fun importedKindCounts(): Flow<List<ImportedKindCount>>

    /**
     * The markers of every row read out of another logger's export file.
     *
     * Read before an import writes anything, so that opening the same file twice — or a file whose
     * months overlap one already read — adds nothing the second time. A marker names the session's
     * instant and the exercise, which is the same name on every phone the file is opened on.
     */
    @Query("SELECT healthConnectRecordKey FROM journal WHERE healthConnectRecordKey LIKE :prefix || '%'")
    suspend fun fileImportMarkers(prefix: String): List<String>

    /**
     * The rows themselves whose marker begins with [prefix]: what one service's files have put in
     * the journal, or what the exchange brought from one app, for each road to ask what the other
     * has already covered ([FiledFacts], [sessionsTheSetsReplace]).
     */
    @Query("SELECT * FROM journal WHERE healthConnectRecordKey LIKE :prefix || '%'")
    suspend fun rowsMarked(prefix: String): List<JournalEntry>
}

/** A journal row's id and its import marker, without the rest of the row. */
data class ImportMarker(val id: Long, val marker: String)

/**
 * The ids of the rows the Health Connect records [recordIds] were imported into and nobody has
 * corrected, all of them out of one pass over the journal ([StepsDao.uneditedImportMarkers]).
 */
internal suspend fun StepsDao.importedRowsOf(recordIds: Set<String>): List<Long> =
    uneditedImportMarkers().filter { markedRecordId(it.marker) in recordIds }.map(ImportMarker::id)

/**
 * One SQLite statement's worth of bound variables, which is what the mirror reads these rows with.
 * Past it the mirror has fallen far enough behind that reading the journal whole is the cheaper
 * question, and the exchange is what asks it; the set stops growing rather than becoming a second
 * copy of the journal in memory.
 */
private const val MAX_EDITED_JOURNAL_ROWS = 500

/**
 * The journal rows written since the mirror last published, so it can publish those instead of
 * reading the journal whole after every edit.
 *
 * In memory rather than in a table or a preferences file, because nothing here has to survive the
 * process: a row remembered and never published is published by the next exchange, which runs at
 * every launch and every return to the foreground and no oftener than every five minutes.
 * Durability would buy that much and cost a write to disk inside the transaction that writes the
 * row; a table would cost a migration and a second thing to keep in step with the journal.
 */
private val editedRows = ConcurrentHashMap.newKeySet<Long>()

/** Called from [StepsDao.saveJournalEntry], which is where every journal write passes. */
internal fun rememberEditedJournalRow(id: Long) {
    if (id > 0L && editedRows.size < MAX_EDITED_JOURNAL_ROWS) editedRows.add(id)
}

/** What the mirror is about to publish. A snapshot: an edit made meanwhile stays pending. */
internal fun editedJournalRows(): Set<Long> = editedRows.toSet()

/** Called once those rows are published — by the mirror, and by the exchange that republished all. */
internal fun forgetEditedJournalRows(ids: Set<Long>) {
    editedRows.removeAll(ids)
}

@Database(
    entities = [
        DaySteps::class,
        HourSteps::class,
        ExternalDaySteps::class,
        ExternalOriginDaySteps::class,
        DirectBleDaySteps::class,
        BarometerDay::class,
        LegacyStepDay::class,
        UserProfile::class,
        JournalEntry::class,
        StateRow::class,
        TrackEntity::class,
        TrackPointEntity::class,
        Routine::class,
        RoutineExercise::class,
        Medication::class,
        Appointment::class,
        JournalTombstone::class,
        FavoriteFood::class,
        MedicalHistoryRow::class,
        SavedDish::class,
        ProgressPhoto::class,
        AppOpenDay::class,
        ChatRow::class,
        TrackPhotoEntity::class,
    ],
    version = 48,
    // Committed under app/schemas, read by [RoomSchemaTest] and, from 25 on, executed on a device by
    // MigrationTest in app/src/androidTest: the hand-written migrations are otherwise verified by
    // nothing at all.
    exportSchema = true,
)
abstract class StepsDb : RoomDatabase() {
    abstract fun dao(): StepsDao

    internal var reminderApp: Application? = null

    companion object {
        @Volatile
        private var instance: StepsDb? = null

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `profile` (
                        `id` INTEGER NOT NULL,
                        `sex` TEXT NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `heightCm` INTEGER NOT NULL,
                        `birthYear` INTEGER NOT NULL,
                        `activityLevel` TEXT NOT NULL,
                        `dailyStepGoal` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `journal` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `day` TEXT NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `calories` REAL NOT NULL,
                        `proteinG` REAL NOT NULL,
                        `fatG` REAL NOT NULL,
                        `carbsG` REAL NOT NULL,
                        `servingG` REAL NOT NULL,
                        `activityCode` TEXT,
                        `met` REAL,
                        `durationMinutes` REAL,
                        `sets` INTEGER,
                        `reps` INTEGER,
                        `loadKg` REAL,
                        `note` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_day_occurredAt` " +
                        "ON `journal` (`day`, `occurredAt`)",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `unitSystem` TEXT NOT NULL DEFAULT 'METRIC'",
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `measurementWeightKg` REAL")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `measurementHeightCm` INTEGER")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `measurementSource` TEXT")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `measurementHeartRateBpm` INTEGER")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `sleepStartAt` INTEGER")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `sleepEndAt` INTEGER")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `automaticSleepDetection` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `walkingDistanceMeters` " +
                        "REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `runningDistanceMeters` " +
                        "REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `vehicleDistanceMeters` " +
                        "REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `stoppedMillis` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `elevationGainM` REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `elevationLossM` REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `maxSpeedMps` REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `elapsedRealtimeNanos` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `verticalAccuracyM` REAL")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `speedAccuracyMps` REAL")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `pressureHpa` REAL")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `barometricAltitudeM` REAL")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `ambientTemperatureC` REAL")
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `movementType` " +
                        "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `cadenceHz` REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `motionStd` REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `environmentSpike` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `lastEventNanos` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `pendingSteps` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `boutStartedNanos` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `boutLastNanos` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `boutConfirmed` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `correctedLat` REAL")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `correctedLon` REAL")
                db.execSQL(
                    "ALTER TABLE `trackpoints` ADD COLUMN `filterReason` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `moodScore` INTEGER")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `breathCycles` INTEGER")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `energyGoal` TEXT NOT NULL DEFAULT 'MAINTAIN'",
                )
                db.execSQL(
                    "ALTER TABLE `profile` ADD COLUMN `remindersEnabled` INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `lastStepAtMs` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `bodyFatPercent` REAL")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `rateWindowStartedNanos` " +
                        "INTEGER NOT NULL DEFAULT -1",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `rateWindowStartCounter` " +
                        "INTEGER NOT NULL DEFAULT -1",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `pendingDay` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `state` ADD COLUMN `pendingDaySteps` INTEGER NOT NULL DEFAULT 0",
                )
                // Existing installations already have a trustworthy last event/counter pair. Use
                // it as the first cumulative rate anchor instead of treating zero as boot history.
                db.execSQL(
                    "UPDATE `state` SET `rateWindowStartedNanos` = `lastEventNanos`, " +
                        "`rateWindowStartCounter` = `lastCumulative`",
                )
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `external_step_days` (
                        `day` TEXT NOT NULL,
                        `steps` INTEGER NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `legacy_step_days` (
                        `day` TEXT NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent(),
                )
                // v14 cannot tell phone-owned rows from wearable totals imported by older syncs.
                // Preserve their UI history, but conservatively prevent every existing row from
                // being fed back into Health Connect as a new phone record.
                db.execSQL(
                    "INSERT OR IGNORE INTO `legacy_step_days` (`day`) SELECT `day` FROM `days`",
                )
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `measurementPayloadJson` TEXT")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `direct_ble_step_days` (
                        `sourceId` TEXT NOT NULL,
                        `day` TEXT NOT NULL,
                        `steps` INTEGER NOT NULL,
                        `sourceLabel` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceId`, `day`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_direct_ble_step_days_day` " +
                        "ON `direct_ble_step_days` (`day`)",
                )
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routine_exercises` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `routineId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `activityCode` TEXT NOT NULL,
                        `durationMinutes` REAL NOT NULL,
                        `sets` INTEGER,
                        `reps` INTEGER,
                        `loadKg` REAL,
                        FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routine_exercises_routineId_position` " +
                        "ON `routine_exercises` (`routineId`, `position`)",
                )
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `barometer_days` (
                        `day` TEXT NOT NULL,
                        `ascentM` REAL NOT NULL,
                        `pressureSumHpa` REAL NOT NULL,
                        `pressureSamples` INTEGER NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nothing to backfill: the daily totals that already exist carry no time inside
                // them, so history starts empty and fills from the next step counted.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hourly_steps` (
                        `day` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `steps` INTEGER NOT NULL,
                        PRIMARY KEY(`day`, `hour`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nothing to backfill. Existing meals were typed for the portion, which is what
                // enteredPer100g = 0 means, and every new column is optional.
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `subtitle` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `fiberG` REAL")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `sugarG` REAL")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `sodiumMg` REAL")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `cholesterolMg` REAL")
                db.execSQL(
                    "ALTER TABLE `journal` ADD COLUMN `enteredPer100g` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nothing to backfill: no meal recorded before this knew its saturated fat, and
                // guessing a share of total fat would invent the very number the column exists for.
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `saturatedFatG` REAL")
            }
        }

        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dose` TEXT NOT NULL,
                        `schedule` TEXT NOT NULL,
                        `times` TEXT NOT NULL,
                        `foodRelation` TEXT NOT NULL,
                        `startDay` TEXT NOT NULL,
                        `endDay` TEXT,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `appointments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `specialty` TEXT NOT NULL,
                        `doctor` TEXT NOT NULL,
                        `place` TEXT NOT NULL,
                        `atMs` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `remindDaysBefore` INTEGER NOT NULL,
                        `doneAt` INTEGER,
                        `remindedAt` INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_occurredAt` " +
                        "ON `journal` (`occurredAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_note` ON `journal` (`note`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_kind_measurementSource` " +
                        "ON `journal` (`kind`, `measurementSource`)",
                )
            }
        }

        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nothing to backfill: what an earlier version deleted left no trace, and a row
                // invented here would withdraw a Health Connect record nobody asked to lose.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `journal_tombstones` (
                        `recordKey` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`recordKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `healthConnectRecordKey` TEXT")
                // Nothing to backfill: a row that never held caffeine states nothing about it, and
                // a cup keeps its milligrams in servingG where every reader already looks.
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `caffeineMg` REAL")
                // The note *was* the marker, so freeze it as identity exactly as every lookup
                // already read it: a row that matched its record yesterday still matches today,
                // and a tombstone written under the old spelling still names the same row.
                db.execSQL(
                    "UPDATE `journal` SET `healthConnectRecordKey` = `note` " +
                        "WHERE `note` LIKE 'Health Connect:%'",
                )
                // Then give the field back. Only where none of it is the person's: a marker is a
                // package and a record id, and neither can hold a space, so a note carrying one
                // past the prefix is something somebody typed and this is not entitled to drop it.
                // Past the prefix, not anywhere in the note — "Health Connect:" has a space of its
                // own, and asking about the whole string would clear nothing at all.
                db.execSQL(
                    "UPDATE `journal` SET `note` = '' WHERE `healthConnectRecordKey` IS NOT NULL " +
                        "AND substr(`note`, length('Health Connect:') + 1) NOT LIKE '% %'",
                )
            }
        }

        /**
         * Somewhere to keep the foods worth marking.
         *
         * A table of its own rather than a flag on [JournalEntry], because a favourite is not an
         * eating: marking porridge is a statement about porridge, not about a morning, and hanging
         * it on one logged row would lose the mark when that row is corrected or deleted. The
         * figures are copied in so a dish built by hand or a packet read off its label can be
         * marked too — see [FavoriteFood].
         */

        internal val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_foods` (
                        `key` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL,
                        `servingG` REAL NOT NULL,
                        `calories` REAL NOT NULL,
                        `proteinG` REAL NOT NULL,
                        `fatG` REAL NOT NULL,
                        `carbsG` REAL NOT NULL,
                        `fiberG` REAL,
                        `sugarG` REAL,
                        `saturatedFatG` REAL,
                        `sodiumMg` REAL,
                        `cholesterolMg` REAL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Somewhere to keep a dish's recipe.
         *
         * The meal it produces is already a journal row; what had no home was the list it was
         * summed from. One text column rather than a child table, because nothing ever queries an
         * ingredient on its own — the list is read and written whole, by the one screen that
         * assembles it.
         */
        internal val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_dishes` (
                        `key` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `items` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Somewhere to list the progress photographs. The pictures are files; this is the index
         * that says which day each belongs to, so the weight form can show a day's photographs
         * beside its number and the backup can carry them. Indexed by day because that is the
         * only way they are ever asked for.
         */
        internal val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `progress_photos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `day` TEXT NOT NULL,
                        `takenAt` INTEGER NOT NULL,
                        `file` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_photos_day` ON `progress_photos` (`day`)")
            }
        }

        /**
         * The datum of a fix's altitude. Every altitude stored before this column was the WGS84
         * ellipsoidal height a raw fix carries, which is what the default says of them.
         */
        internal val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `altitudeMsl` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Somewhere to keep who Health Connect's step days came from. A table of its own rather
         * than a column on `external_step_days`, because that row is one deduplicated total and
         * this is one row per app that contributed to it; nothing is added to the old table and no
         * step arithmetic changes. A phone upgrading arrives with it empty, and the sources screen
         * says "Health Connect" for those days exactly as it did before, until the next sync fills
         * it in.
         */
        internal val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_step_origin_days` (" +
                        "`packageName` TEXT NOT NULL, `day` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`packageName`, `day`))",
                )
            }
        }

        /**
         * The index moves to the column the import actually asks about.
         *
         * Every incoming Health Connect record asks the journal twice whether it is already here,
         * by [JournalEntry.healthConnectRecordKey], and both questions read every row of the table
         * to answer. The column indexed instead was [JournalEntry.note], which held the import
         * marker until 25→26 gave it a column of its own. The journal search still reads that
         * column, but with a leading wildcard, which an index cannot answer — so the index was a
         * write's worth of work per row in exchange for nothing.
         */
        internal val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_healthConnectRecordKey` " +
                        "ON `journal` (`healthConnectRecordKey`)",
                )
                db.execSQL("DROP INDEX IF EXISTS `index_journal_note`")
            }
        }

        /**
         * Somewhere to keep the prescription. Two text columns holding newline-separated content
         * URIs — no table of its own, because an attachment has no life apart from the row it
         * hangs on and nothing ever queries them except that row.
         */
        internal val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `medications` ADD COLUMN `attachments` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `appointments` ADD COLUMN `attachments` TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The whole table is rewritten because a column is leaving, and SQLite only learned
                // to drop one in 3.35 — Android 14. This app runs from 33, so the rename-copy-drop
                // dance is the only spelling every supported phone understands.
                //
                // A stored year says nothing about the day, so the backfill picks the last one:
                // 1990 becomes 1990-12-31, which makes an existing profile up to a year younger
                // than it is. That is the direction to be wrong in — it can only ever hold a
                // feature back for a few months, never open one early — and it costs the energy
                // equation nothing, which reads [birthYear] and gets the same 1990 either way.
                db.execSQL("ALTER TABLE `profile` RENAME TO `profile_old`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `profile` (
                        `id` INTEGER NOT NULL,
                        `sex` TEXT NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `heightCm` INTEGER NOT NULL,
                        `birthDate` TEXT NOT NULL,
                        `unitSystem` TEXT NOT NULL,
                        `activityLevel` TEXT NOT NULL,
                        `dailyStepGoal` INTEGER NOT NULL,
                        `automaticSleepDetection` INTEGER NOT NULL,
                        `energyGoal` TEXT NOT NULL,
                        `remindersEnabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `profile`
                    SELECT `id`, `sex`, `weightKg`, `heightCm`,
                        printf('%04d-12-31', `birthYear`), `unitSystem`, `activityLevel`,
                        `dailyStepGoal`, `automaticSleepDetection`, `energyGoal`, `remindersEnabled`
                    FROM `profile_old`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `profile_old`")
            }
        }

        /**
         * A line of the medical history remembers when its answer was given, apart from when the line
         * was last saved. For every line written before, the last save is the only moment there is.
         * And a journal row remembers the offset of the clock it was written on; no row written
         * before says, and each is read as the phone's own zone, as all of them were.
         */
        internal val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `medical_history` ADD COLUMN `answeredAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `medical_history` SET `answeredAt` = `updatedAt`")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `zoneOffsetSeconds` INTEGER")
            }
        }

        /**
         * A route remembers the offset of the clock it was recorded on, as a journal row has since 47.
         * No route recorded before says, and each is read as the phone's own zone, as all of them were.
         */
        internal val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `zoneOffsetSeconds` INTEGER")
            }
        }

        /** A journal row remembers when a person last changed it; every row written before has not been. */
        internal val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `editedAt` INTEGER")
            }
        }

        /**
         * A counted exercise of a template keeps each set's own reps and load rather than one figure
         * for all of them. Every exercise written before has its single figures and nothing here.
         */
        internal val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `routine_exercises` ADD COLUMN `setsJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * A template gets a picture, a few words and the days it is for; its exercises can be done
         * as supersets, with a rest after each. Every template written before has none of it.
         */
        internal val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `photo` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `days` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `routine_exercises` ADD COLUMN `superset` INTEGER")
                db.execSQL("ALTER TABLE `routine_exercises` ADD COLUMN `restSeconds` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * A recording can pause. A route remembers when it was paused and how long it stood still in
         * all, and the first fix after a pause says so, so that neither the line nor the distance runs
         * across the gap. Every route recorded before has never paused.
         */
        internal val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `pausedAt` INTEGER")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `pausedMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trackpoints` ADD COLUMN `segmentStart` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Walks get names and photographs. A name is drawn when a recording starts, so every route
         * saved before has none and [TrackEntity.title] picks one for it by the second it started; a
         * photograph keeps the spot it was taken from — position, compass bearing, tilt and how the
         * phone was held — beside its file name, and goes when its route goes.
         */
        internal val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_photos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` INTEGER NOT NULL,
                        `takenAt` INTEGER NOT NULL,
                        `file` TEXT NOT NULL,
                        `lat` REAL,
                        `lon` REAL,
                        `altitudeM` REAL,
                        `accuracyM` REAL,
                        `bearingDeg` REAL,
                        `bearingAccuracyDeg` REAL,
                        `pitchDeg` REAL,
                        `rollDeg` REAL,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_photos_trackId_takenAt` " +
                        "ON `track_photos` (`trackId`, `takenAt`)",
                )
            }
        }

        /**
         * Somewhere to keep a chat that files records of several kinds: its words, its questions and
         * its answers, and which records it made, named by kind and moment. The conversation is not
         * a journal row, so it marks no day kept and no reader of the journal ever counts it; the
         * records it files are ordinary rows of their own kinds. A random key rather than an
         * autoincrement, so that a backup carried to another phone can tell its chats from the ones
         * already there.
         */
        internal val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chats` (
                        `key` TEXT NOT NULL,
                        `day` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** The photograph a course may carry; every course written before this migration has none. */
        internal val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `medications` ADD COLUMN `photo` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Two pointers the vitamin table reads: what a supplement course holds per dose, and which
         * catalogue rows a meal came from. Both empty on every row written before, which reads as
         * "not described" and never as "none".
         */
        internal val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `medications` ADD COLUMN `contents` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `foodSource` TEXT")
            }
        }

        internal val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `journal` ADD COLUMN `energyScore` INTEGER")
            }
        }

        /**
         * Somewhere to write down what happened before the app was installed.
         *
         * Diagnoses, operations, vaccinations, pregnancy and contraception were the five answers a
         * patient summary asks for that this app had nowhere to hold, so its report printed the
         * headings empty and said it does not record them. One table with a kind column rather than
         * five tables: every one of them is a name, a date or two and a note, nothing queries them
         * apart, and five tables would be five migrations for the same shape. What the columns mean
         * for each kind is written on [HistoryKind].
         */
        internal val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medical_history` (
                        `key` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `startDay` TEXT NOT NULL,
                        `endDay` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** The allergy answer the onboarding asks for; a profile from before this migration has not been asked. */
        internal val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profile` ADD COLUMN `allergies` TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_open_days` (
                        `day` TEXT NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Every migration, in the order the builder is handed them. One list rather than a vararg
         * call, so that the app's own open path and MigrationTest — which executes these on a
         * device — cannot register different chains: a migration written above but left off this
         * list runs in neither, and [RoomSchemaTest] reads the list to make sure none is.
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
            MIGRATION_46_47,
            MIGRATION_47_48,
        )

        /**
         * The builder the app opens its database with, the file's [name] aside. MigrationTest opens
         * a file it has aged to version 25 through this same builder, so the open path it proves is
         * the one a phone takes and not a test's reconstruction of it. [passphrase] is what
         * [DatabaseEncryption.reconcile] handed back for the file — null for a plain one, which is
         * also what the test's files are.
         */
        @Suppress("SpreadOperator") // addMigrations takes varargs; one reference a migration, copied once per open.
        internal fun builder(
            context: Context,
            name: String,
            passphrase: ByteArray? = null,
        ): RoomDatabase.Builder<StepsDb> = Room
            .databaseBuilder(context.applicationContext, StepsDb::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            // Pre-v2 development databases are not release-compatible, and only they may be
            // dropped: named here rather than globally so a missing migration between shipped
            // versions fails at open time instead of silently wiping a person's health record.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .openHelperFactory(quarantineCorruptDatabase(openHelperFor(passphrase)))

        // A hand-rolled singleton rather than a DI framework: the database is the only thing
        // to inject.
        fun get(context: Context): StepsDb = instance ?: synchronized(this) {
            instance ?: open(context.applicationContext).also {
                widgetApp = context.applicationContext as? Application
                it.reminderApp = context.applicationContext as? Application
                instance = it
            }
        }

        /**
         * Reconciles the file with the encryption switch, then builds Room over it. The order is the
         * point: a pending conversion rewrites the file, and the one moment nothing in the process
         * can be holding it open is before the first Room instance exists — which is why this runs
         * under [get]'s lock and nowhere else. The answer says which helper the file now wants.
         */
        private fun open(app: Context): StepsDb {
            val passphrase = DatabaseEncryption.reconcile(app, app.getDatabasePath(DATABASE_FILE))
            return builder(app, DATABASE_FILE, passphrase)
                .build()
        }
    }
}

/**
 * The helper under the quarantine: SQLCipher's when [DatabaseEncryption.reconcile] handed back a
 * key, the framework's otherwise. Chosen once, from the reconciled answer — never from the switch's
 * own position, which may be a wish for a start that has not happened yet.
 */
private fun openHelperFor(passphrase: ByteArray?): SupportSQLiteOpenHelper.Factory =
    passphrase?.let(::CipherOpenHelperFactory) ?: FrameworkSQLiteOpenHelperFactory()

/**
 * Room's answer to a corrupt database file is to delete it, and there is nothing behind it: the
 * only other copy of a person's health record is an export they may never have taken, and an
 * automatic backup this app deliberately does not send anywhere. So the file is moved aside before
 * that happens. Recovery is unchanged — Room still opens a fresh database and the app still starts
 * — but `steps.db.corrupt` is on disk afterwards, and `sqlite3 .recover` usually reads most of a
 * damaged file back out of it. The profile page says that it is there, saves it out to a file of
 * its owner's choosing — private storage being out of their reach — and deletes it when told to
 * ([SetAside.DAMAGED]).
 *
 * The same wrapper stands over both helpers — the framework's for a plain file, SQLCipher's for an
 * encrypted one — since the file is worth the same either way; [base] is whichever the file wants.
 *
 * The helper under it is never allowed to lose data on recovery, whatever Room asks for. One that
 * is allowed deletes the file itself when an open fails — the framework's does, in sqlite-framework
 * 2.7.0 — and that delete goes past [SupportSQLiteOpenHelper.Callback.onCorruption], and so past
 * the quarantine. Room 2.8.5 asks for false; the flag is set here so that no later Room can ask
 * otherwise.
 */
internal fun quarantineCorruptDatabase(
    base: SupportSQLiteOpenHelper.Factory,
) = object : SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)

            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) {
                room.onOpen(db)
                // A file that opened under Room is a conversion that worked: the copy kept against a
                // crash between the two renames has done its job.
                db.path?.let(DatabaseEncryption::opened)
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                db.path?.let { path ->
                    // What Room does next is delete whatever still stands under the name. A file
                    // that would not move aside is not left to that: the start fails instead, and
                    // the record is where it was for the next one.
                    check(quarantineDatabaseFiles(path)) { "the damaged database could not be set aside" }
                    // The fresh database numbers its rows from 1: the old backups' names for rows
                    // must not be believed against it.
                    forgetInstallId(configuration.context)
                }
                // Then whatever Room would have done on its own, so the app still recovers.
                room.onCorruption(db)
            }
        }
        return base.create(
            SupportSQLiteOpenHelper.Configuration(
                configuration.context,
                configuration.name,
                guarded,
                configuration.useNoBackupDirectory,
                allowDataLossOnRecovery = false,
            ),
        )
    }
}

/**
 * Moves a damaged database out of Room's way, under a name no earlier one holds ([freeName]): a
 * second damaged file is no reason to lose the first, which on a failing disk is the one with the
 * record in it. The write-ahead log goes too — without it the kept copy is missing the writes that
 * never got merged. So does the original a conversion of this start kept against a crash mid-swap:
 * it is now the only whole copy, and the fresh database opened next would be the "successful open"
 * that lets it go ([DatabaseEncryption.opened]), so it moves beside the quarantined file.
 *
 * Answers whether the file is gone from [path]. One that would not move is still the record, and
 * no caller goes on from there to an open that deletes it or writes over it.
 */
internal fun quarantineDatabaseFiles(path: String): Boolean {
    val aside = freeName(path + SetAside.DAMAGED.suffix)
    moveDatabaseFiles(path, aside)
    DatabaseEncryption.keepOriginalBesideCorrupt(path, aside)
    return !File(path).exists()
}

/**
 * The application the database was opened with, kept only to redraw the widgets. Typed [Application]
 * rather than [Context] on purpose: it lives as long as the process, so there is nothing here to
 * leak, and the type is what says so.
 */
@Volatile
private var widgetApp: Application? = null

/**
 * Redraws the home-screen widgets after a write they show.
 *
 * Never from inside a transaction: the redraw reads the database back, and from within one it
 * would either read a snapshot without the row that caused it or hold the write connection through
 * five launcher updates. A caller holding its own transaction around one of the writes below is
 * the widget code itself, which redraws when it commits.
 */
internal suspend fun StepsDb.refreshWidgetsAfterWrite(kind: String? = null) {
    if (inTransaction()) return
    widgetApp?.let { refreshWidgets(it, kind) }
}

/**
 * Saves the profile and redraws every widget, for the saves that change what one shows: the step
 * goal, the units, the energy budget, and the sex and age that decide which tiles the multi widget
 * offers ([offeredWidgetSlots]). Saved through the DAO alone, a profile answered at onboarding or
 * restored from a backup would leave the widgets drawn for the one before it until their half-hourly
 * redraw.
 */
suspend fun StepsDb.saveProfile(profile: UserProfile) {
    dao().saveProfile(profile)
    refreshWidgetsAfterWrite()
}

/**
 * Writes what the pedometer decided since its last write ([StepLedger.take]) in one transaction, so a
 * torn write can never cause a recount, and answers [StepWrite.day]'s total. That total is the
 * deduplicated phone/wearable one the UI shows, not the local row alone: the foreground notification
 * should agree with the screen.
 */
suspend fun StepsDb.writeSteps(write: StepWrite): Long = withTransaction {
    val dao = dao()
    val state = write.state
    dao.saveState(
        StateRow(
            bootId = state.bootId,
            lastCumulative = state.lastCumulative,
            lastEventNanos = state.lastEventNanos,
            pendingSteps = state.pendingSteps,
            boutStartedNanos = state.boutStartedNanos,
            boutLastNanos = state.boutLastNanos,
            boutConfirmed = state.boutConfirmed,
            rateWindowStartedNanos = state.rateWindowStartedNanos,
            rateWindowStartCounter = state.rateWindowStartCounter,
            pendingDay = state.pendingDay,
            pendingDaySteps = state.pendingDaySteps,
            lastStepAtMs = write.lastStepAtMs,
        ),
    )
    write.credits.forEach { (key, steps) ->
        val (day, hour) = key
        if (dao.bump(day, steps) == 0) dao.put(DaySteps(day, steps))
        if (dao.bumpHour(day, hour, steps) == 0) dao.putHour(HourSteps(day, hour, steps))
    }
    dao.stepsOn(write.day) ?: 0L
}

/** A ledger that starts from the state the last write left. */
suspend fun StepsDb.stepLedger(): StepLedger {
    val stored = dao().state()
    val state = stored?.let {
        StepState(
            bootId = it.bootId,
            lastCumulative = it.lastCumulative,
            lastEventNanos = it.lastEventNanos,
            pendingSteps = it.pendingSteps,
            boutStartedNanos = it.boutStartedNanos,
            boutLastNanos = it.boutLastNanos,
            boutConfirmed = it.boutConfirmed,
            rateWindowStartedNanos = it.rateWindowStartedNanos,
            rateWindowStartCounter = it.rateWindowStartCounter,
            pendingDay = it.pendingDay,
            pendingDaySteps = it.pendingDaySteps,
        )
    }
    return StepLedger(state, stored?.lastStepAtMs ?: 0L)
}

/**
 * Saves an event and recalculates every later activity against the measurement timeline.
 * Profile weight is the immutable onboarding baseline; measurement events override it from their
 * timestamp forward. Answers the row's id ([StepsDao.saveJournalEntry]).
 *
 * Not cancellable once begun. Most callers launch it in a page's own scope, and a back press before
 * the transaction returns must not drop a reading the screen has already confirmed; the tap landed,
 * so the row lands, and only what the page would have done afterwards is let go.
 */
suspend fun StepsDb.saveJournalEvent(entry: JournalEntry): Long = withContext(NonCancellable) {
    val id = writeJournalEvent(entry)
    if (entry.movesMedicineAlarms()) refreshMedicineAfterWrite()
    refreshWidgetsAfterWrite(entry.kind)
    id
}

/**
 * The rows that change what the medicine alarms say: a meal — a dose is taken with food, or before
 * it — a dose marked, a night's sleep. Asked of the row and not of the function that wrote it: a cup
 * with milk is a food row whichever dialog it came from ([cupEntry]).
 */
internal fun JournalEntry.movesMedicineAlarms(): Boolean = kind == KIND_EAT || kind == KIND_MED || sleepEndAt != null

/**
 * Run after commit so a meal immediately checks the medication against the saved record.
 *
 * A check that fails here is not the write failing: the row is in. Thrown on, it would reach the page
 * as a save that did not happen, and the tap that tried again would file the row twice. The doses are worked
 * out again by the alarm an earlier check booked, the hourly worker and the next write, so the
 * failure is logged, as the alarm's own receiver logs one, and the write answers that it was saved.
 * A cancelled caller is still cancelled.
 */
internal suspend fun StepsDb.refreshMedicineAfterWrite() {
    if (inTransaction()) return
    val app = reminderApp ?: return
    runCatching { MedicineAlarms.refresh(app, this) }
        .onFailure { if (it is CancellationException) throw it else Log.w("StepsDb", "the dose check failed", it) }
}

/**
 * A superset is one act of training, so it is one act of writing: both halves land, or neither does.
 *
 * Written in a single transaction rather than as two saves in a row, because a crash between them
 * would leave one exercise in the journal saying it was paired with another that is not there.
 */
suspend fun StepsDb.saveSupersetEvent(pair: List<JournalEntry>) {
    withTransaction { pair.forEach { writeJournalEvent(it) } }
    refreshWidgetsAfterWrite()
}

/**
 * The write on its own. The one-tap guards below call this rather than [saveJournalEvent]: their
 * own transaction already encloses it, and the widgets are redrawn once it has committed.
 */
private suspend fun StepsDb.writeJournalEvent(entry: JournalEntry): Long = withTransaction {
    val dao = dao()
    // An edit can move a measurement later in time, and everything from where it used to stand is
    // then out of date too, so the pass starts at the earlier of the two moments.
    val previous = if (entry.id == 0L) null else dao.journalEntry(entry.id)
    // A row saved under an id it already had is a row a person changed: the Health Connect import
    // leaves such a row alone rather than refreshing it from its source's record.
    val filed = entry.copy(zoneOffsetSeconds = filedOffsetSeconds(previous, entry, ZoneId.systemDefault()))
    val id = dao.saveJournalEntry(if (previous == null) filed else filed.copy(editedAt = System.currentTimeMillis()))
    if (entry.movesActivityTimeline() || previous?.movesActivityTimeline() == true) {
        recalculateActivityCalories(
            dao,
            minOf(entry.occurredAt, previous?.occurredAt ?: Long.MAX_VALUE),
        )
    }
    id
}

/**
 * Adds one barometer observation to a day: metres climbed, a pressure reading, or both.
 *
 * Read-modify-write in a transaction rather than an `UPDATE … SET x = x + ?`, because the row does
 * not exist on the first climb of the day and Room's `@Query` cannot insert one.
 */
suspend fun StepsDb.addBarometerSample(
    day: String,
    ascentM: Double,
    pressureHpa: Double?,
) = withTransaction {
    val dao = dao()
    val previous = dao.barometerDay(day) ?: BarometerDay(day)
    dao.saveBarometerDay(
        previous.copy(
            ascentM = previous.ascentM + ascentM,
            pressureSumHpa = previous.pressureSumHpa + (pressureHpa ?: 0.0),
            pressureSamples = previous.pressureSamples + if (pressureHpa != null) 1 else 0,
        ),
    )
}

/** Atomically records one direct-BLE history sync and its provenance journal entry. */
suspend fun StepsDb.saveDirectBleStepSync(
    rows: List<DirectBleDaySteps>,
    entry: JournalEntry,
) = withTransaction {
    val dao = dao()
    if (rows.isNotEmpty()) dao.putDirectBleDays(rows)
    val source = entry.measurementSource
    val payload = entry.measurementPayloadJson
    if (source == null || payload == null || !dao.hasDirectMeasurement(source, payload)) {
        dao.saveJournalEntry(entry)
        if (entry.movesActivityTimeline()) recalculateActivityCalories(dao, entry.occurredAt)
    }
}

/** Idempotent batch import for an RACP procedure that may return the same records again. */
suspend fun StepsDb.saveDirectBluetoothMeasurements(entries: List<JournalEntry>): Int =
    withTransaction {
        val dao = dao()
        var inserted = 0
        var since = Long.MAX_VALUE
        entries.forEach { entry ->
            val source = entry.measurementSource
            val payload = entry.measurementPayloadJson
            val known = source == null || payload == null ||
                dao.hasDirectMeasurement(source, payload) || dao.holdsReading(entry)
            if (!known) {
                dao.saveJournalEntry(entry)
                inserted += 1
                if (entry.movesActivityTimeline()) since = minOf(since, entry.occurredAt)
            }
        }
        if (since != Long.MAX_VALUE) recalculateActivityCalories(dao, since)
        inserted
    }

/** How far apart the two timestamps of one reading may sit: a whole second, to either side. */
private const val SAME_READING_WINDOW_MS = 1_000L

/**
 * The figure each device is for, and how close two copies of it have to be to be one figure —
 * the precision each is stored to. Glucose is read in mmol/L whichever unit the meter spoke, since
 * that is the unit its record came back in.
 */
private val DIRECT_READING_FIGURES: List<Pair<(JournalEntry) -> Double?, Double>> = listOf(
    JournalEntry::measurementWeightKg to 0.01,
    JournalEntry::glucoseMmolL to 0.01,
    { entry: JournalEntry -> entry.payloadValue("systolicMmhg") } to 0.5,
    { entry: JournalEntry -> entry.payloadValue("diastolicMmhg") } to 0.5,
    { entry: JournalEntry -> entry.payloadValue("spo2Percent") } to 0.5,
    { entry: JournalEntry -> entry.payloadValue("temperatureCelsius") } to 0.05,
)

/**
 * Whether [standing] is [reading] under another name.
 *
 * Matched on its source and its whole payload string, a device history would miss a row restored
 * from Health Connect, which has neither: no source, and a payload holding the one figure the record
 * carried. After a reinstall the meter's first report-all would then re-save every reading it had
 * ever sent, and the export would publish each copy under a fresh client record id. What the two rows do
 * share is the reading itself — the kind, the second it was taken in, and the figure the device is
 * for — and that is what they are compared on: every figure the reading carries has to stand in the
 * row, within the precision it is stored to. A reading carrying none of them is nobody's twin.
 */
internal fun sameDirectReading(standing: JournalEntry, reading: JournalEntry): Boolean {
    if (standing.kind != reading.kind) return false
    if (abs(standing.occurredAt - reading.occurredAt) > SAME_READING_WINDOW_MS) return false
    val carried = DIRECT_READING_FIGURES.mapNotNull { (figure, tolerance) ->
        figure(reading)?.let { Triple(figure, it, tolerance) }
    }
    return carried.isNotEmpty() && carried.all { (figure, value, tolerance) ->
        figure(standing)?.let { abs(it - value) <= tolerance } ?: false
    }
}

/** Whether a row already stands for this reading, whoever wrote it — see [sameDirectReading]. */
private suspend fun StepsDao.holdsReading(reading: JournalEntry): Boolean = journalAround(
    reading.kind,
    reading.occurredAt - SAME_READING_WINDOW_MS,
    reading.occurredAt + SAME_READING_WINDOW_MS,
).any { sameDirectReading(it, reading) }

/**
 * Logs a drink unless the same portion was already logged seconds ago. Returns false when the tap
 * was swallowed as a duplicate, so the caller can stay silent rather than claim a second glass.
 *
 * The probe runs inside the transaction that writes the row ([writeUnlessBounced]).
 */
suspend fun StepsDb.logWater(entry: JournalEntry): Boolean {
    val logged = writeUnlessBounced(entry) { lastPortionAtMs(KIND_WATER, entry.day, entry.servingG) }
    if (logged) refreshWidgetsAfterWrite(KIND_WATER)
    return logged
}

/**
 * The guard every one-tap logger here shares, each with its own probe: when [lastAtMs] says the same
 * tap last landed, read inside the transaction that writes the row — a widget tap and a screen tap
 * are separate coroutines, and outside it both would read "nothing logged yet" and both would
 * insert. Answers whether the row was written; the redraw after the commit is the caller's. Shared,
 * so a change reaches all five loggers at once.
 */
private suspend fun StepsDb.writeUnlessBounced(entry: JournalEntry, lastAtMs: suspend StepsDao.() -> Long?): Boolean =
    withTransaction {
        val bounced = isDuplicateTap(dao().lastAtMs(), entry.occurredAt)
        if (!bounced) writeJournalEvent(entry)
        !bounced
    }

/**
 * The same rule for a cup of coffee: one tap, one cup. The probe is the entry's own kind, because a
 * cup with milk is filed as food carrying caffeine (see [cupEntry]), and its bounce is that food row
 * twice rather than a cup.
 */
suspend fun StepsDb.logCaffeine(entry: JournalEntry): Boolean {
    val logged = writeUnlessBounced(entry) { lastPortionAtMs(entry.kind, entry.day, entry.servingG) }
    if (logged) {
        if (entry.movesMedicineAlarms()) refreshMedicineAfterWrite()
        cupRedrawKinds(entry).forEach { refreshWidgetsAfterWrite(it) }
    }
    return logged
}

/**
 * The kinds a logged cup redraws the widgets by ([widgetsShowing]): the day's drinks always, and the
 * kind of its own row beside them. A cup with milk is a food row — it moves the day's energy and the
 * year's XP, which the widgets drawn for a cup of black coffee do not show.
 */
internal fun cupRedrawKinds(entry: JournalEntry): Set<String> = setOf(KIND_CAFFEINE, entry.kind)

/** One tap, one visit: the rule the widgets need too, so it lives here and not in the screen. */
suspend fun StepsDb.logUrination(entry: JournalEntry): Boolean {
    val logged = writeUnlessBounced(entry) { lastEntryAtMs(KIND_URINATION, entry.day) }
    if (logged) refreshWidgetsAfterWrite(KIND_URINATION)
    return logged
}

/**
 * The same rule for a drink, and the probe is the portion rather than the kind: a beer followed by
 * a shot is two drinks a second apart on purpose, and only the same drink twice in three seconds is
 * the tap that bounced.
 */
suspend fun StepsDb.logAlcohol(entry: JournalEntry): Boolean {
    val logged = writeUnlessBounced(entry) { lastPortionAtMs(KIND_ALCOHOL, entry.day, entry.servingG) }
    if (logged) refreshWidgetsAfterWrite(KIND_ALCOHOL)
    return logged
}

/**
 * One tap, one of that product — and the probe is the portion, as a drink's is: every tobacco
 * product is filed under grams of its own, so a heated stick a second after a cigarette is two
 * entries on purpose, and only the same product twice inside the window is the tap that bounced.
 *
 * Not the kind: the screen shuts only the button that was pressed, so the tile beside it stays
 * open, its tap reaches this guard, and a probe on the kind would drop that entry without a word.
 * The cigarette widget's tap comes through here too ([logWidgetCigarette]).
 */
suspend fun StepsDb.logSmoking(entry: JournalEntry): Boolean {
    val logged = writeUnlessBounced(entry) { lastPortionAtMs(KIND_SMOKING, entry.day, entry.servingG) }
    if (logged) refreshWidgetsAfterWrite(KIND_SMOKING)
    return logged
}

/**
 * Logs a mood unless the cooldown is still running. The screen checks it too, but the screen's copy
 * of the journal can be a moment stale, and the widget has no state at all — the rule belongs where
 * both of them end up.
 */
suspend fun StepsDb.logMood(entry: JournalEntry): Boolean {
    val logged = withTransaction {
        val last = dao().lastMoodNear(entry.occurredAt)
        if (moodCooldownLeftMs(last, entry.occurredAt) > 0L) {
            false
        } else {
            writeJournalEvent(entry)
            true
        }
    }
    if (logged) refreshWidgetsAfterWrite(KIND_MOOD)
    return logged
}

/** A period starts once a day; a second tap on the same day is a slip, not a second period. */
suspend fun StepsDb.logCycleStart(entry: JournalEntry): Boolean {
    val logged = withTransaction {
        // Once a day, and never inside a period already on file. A start dated within one steals
        // that period's end — [cyclePeriods] gives each start the first end before the next start —
        // which turns a ten-day period into an orphaned start and a one-day period. The screens
        // do not offer it there, but a screen's copy of the journal can be a moment stale, which is
        // why [logMood]'s rule is here too. An import — Health Connect, a file, a backup — writes
        // its rows without coming through this.
        val day = runCatching { java.time.LocalDate.parse(entry.day) }.getOrNull()
        val inside = day != null && cyclePeriods(dao().cycleMarksNow())
            .any { !day.isBefore(it.start) && !day.isAfter(it.end) }
        if (dao().lastEntryAtMs(KIND_CYCLE, entry.day) != null || inside) {
            false
        } else {
            writeJournalEvent(entry)
            true
        }
    }
    if (logged) refreshWidgetsAfterWrite()
    return logged
}

/**
 * Begins a period, or rejoins the one that was closed a day or two ago.
 *
 * The app asks "has it started?" and it asks "is it over?", and a woman who answers yes to the
 * second on Tuesday evening and yes to the first on Wednesday morning has had one period with a
 * quiet day in it. Filed as two, that is a one-day period and a two-day period on record, and
 * [medianPeriodLength] — which every figure on the screen is drawn from — would read her periods as
 * one day long.
 *
 * So a start dated within [PERIOD_RESUME_DAYS] of an existing end deletes that end instead of
 * writing a new row. Deleted rather than blanked, for the tombstone [deleteJournalEvent] leaves: an
 * end that vanished only here would come home on the next Health Connect sync.
 */
suspend fun StepsDb.startOrResumeCycle(entry: JournalEntry, day: java.time.LocalDate): Boolean {
    val resumed = withTransaction {
        val end = resumedPeriodEnd(dao().cycleMarksNow(), day)
        if (end != null) {
            dao().journalOnNow(end.toString())
                .filter { it.kind == KIND_CYCLE_END }
                .forEach { deleteJournalEvent(it.id) }
            true
        } else {
            false
        }
    }
    if (resumed) {
        refreshWidgetsAfterWrite()
        return true
    }
    return logCycleStart(entry)
}

/**
 * One answer per cycle section per day: Medium chosen after Light corrects the day's bleeding
 * rather than filing a second one, and the same for an ovulation test and for mucus.
 *
 * The rows it replaces are deleted, not overwritten, because [deleteJournalEvent] is what leaves
 * the tombstone Health Connect needs — an overwrite in place would leave the Light record standing
 * in the other store, and the next sync would read it back as history to restore.
 *
 * Sexual activity is in no section and never reaches here from the cycle screen: twice in a day is
 * twice, not a correction.
 */
suspend fun StepsDb.logCycleLog(entry: JournalEntry, section: CycleSection) {
    val replaced = section.logs.toSet()
    // One transaction over the pair: half of a correction — the Light gone and no Medium in its
    // place — is worse than either whole outcome. The writes below join it rather than opening
    // their own, and their widget refresh sees it open and stands down until it has committed.
    withTransaction {
        dao().journalOnNow(entry.day)
            .filter { it.id != entry.id && it.cycleLog() in replaced }
            .forEach { deleteJournalEvent(it.id) }
        saveJournalEvent(entry)
    }
    refreshWidgetsAfterWrite()
}

/**
 * Files a finished period on the days the person confirmed, moving the start when she corrected it.
 *
 * A moved start is a new row on the new day and a deletion of the old one, for the same tombstone
 * reason as above. The move is only applied once the new start is actually in — [logCycleStart]
 * turns down a day that already holds one, or one inside an earlier period — and a start turned
 * down refuses the whole act: writing the end anyway would put the confirmation's "period
 * end" over a period whose start had quietly stayed where it was. Answers whether anything was filed.
 */
suspend fun StepsDb.logCyclePeriod(start: JournalEntry, end: JournalEntry, previousStartDay: String): Boolean {
    val logged = withTransaction {
        if (start.day != previousStartDay) {
            if (!logCycleStart(start)) return@withTransaction false
            dao().journalOnNow(previousStartDay)
                .filter { it.kind == KIND_CYCLE }
                .forEach { deleteJournalEvent(it.id) }
        }
        logCycleEnd(end)
    }
    if (logged) refreshWidgetsAfterWrite()
    return logged
}

/**
 * Takes the end mark off a period, so a period closed too early is running again.
 *
 * The correction for "I marked it finished and it was not". Only the end goes: the start, and
 * everything recorded between the two, is hers and stays. Deleted rather than blanked, for the
 * tombstone — an end that vanished only here would come home on the next Health Connect sync.
 */
suspend fun StepsDb.reopenCyclePeriod(endDay: String) {
    withTransaction {
        dao().journalOnNow(endDay)
            .filter { it.kind == KIND_CYCLE_END }
            .forEach { deleteJournalEvent(it.id) }
    }
    refreshWidgetsAfterWrite()
}

/** And it ends once a day, for the same reason and by the same rule. */
suspend fun StepsDb.logCycleEnd(entry: JournalEntry): Boolean {
    val logged = withTransaction {
        if (dao().lastEntryAtMs(KIND_CYCLE_END, entry.day) != null) {
            false
        } else {
            writeJournalEvent(entry)
            true
        }
    }
    if (logged) refreshWidgetsAfterWrite()
    return logged
}

/**
 * Files an act, unless the same one was filed a moment ago.
 *
 * Twice in a day is twice, not a correction, so this does not replace the way [logCycleLog] does —
 * but twice in three seconds is one tap that bounced, and the cycle screen's three buttons sit under
 * a thumb that is already moving. The guard is the same one the drinks and the cigarettes carry, and
 * it is here rather than in the screen for the same reason: a widget tap is a broadcast of its own,
 * with no screen behind it to remember the last one.
 *
 * The match is on the act itself and not only on the kind: "protected" and "unprotected" are both
 * [KIND_CYCLE_LOG], and a flow or a mucus row written a second earlier is not this at all.
 *
 * [today] is the day whose rows [dayInstant] stamps with the moment itself. The caller hands it in
 * rather than this reading the clock, so a test can say which day it means: one that ran across
 * midnight would have nothing to say about the bounce at all.
 */
suspend fun StepsDb.logIntimacy(entry: JournalEntry, log: CycleLog?, today: LocalDate): Boolean {
    val logged = withTransaction {
        val last = dao().journalOnNow(entry.day)
            .filter { if (log == null) it.kind == KIND_MASTURBATION else it.cycleLog() == log }
            .maxOfOrNull { it.occurredAt }
        // Only a row stamped now can be a tap that bounced. An act filed against an earlier day
        // carries midday of that day ([dayInstant]), so two of them would sit zero milliseconds
        // apart and the second could never be filed at all.
        val bounced = entry.day == today.toString() && isDuplicateTap(last, entry.occurredAt)
        if (bounced) {
            false
        } else {
            writeJournalEvent(entry)
            true
        }
    }
    if (logged) refreshWidgetsAfterWrite()
    return logged
}

/**
 * Files a morning's check-in, replacing whatever that morning already held.
 *
 * A morning has one answer, so a second tap is a correction, not a second morning — unlike an act,
 * where twice is twice. It rewrites the day's row in place rather than deleting and adding: this
 * kind never reaches Health Connect, so there is no record in the other store for a tombstone to
 * speak for. Any further rows the day holds — a restored backup brings an old answer back beside a
 * changed one — are deleted in the same transaction, so the day is left with the one just given.
 */
suspend fun StepsDb.logMorningCheck(entry: JournalEntry) {
    withTransaction {
        val (written, surplus) = morningCheckReplacement(dao().journalOnNow(entry.day), entry)
        writeJournalEvent(written)
        surplus.forEach { deleteJournalEvent(it) }
    }
    refreshWidgetsAfterWrite()
}

/**
 * Takes a whole period back off the record: both marks, and the flow recorded inside it.
 *
 * For the one case that needs it — a start tapped by accident and closed the next day, which the
 * app asks about and she says was not a period at all. Nothing else takes the flow with the marks,
 * and this is deliberately not offered anywhere a finger can reach it by mistake: the calendar and
 * the entry editor delete one row, a start with its end ([periodEndsDeletedWith]).
 *
 * What goes is what the dialog asked about, the period ([isOfPeriod]). The rest of those days stays:
 * spotting, a test, the mucus, the pain and the desire she noticed, and what she recorded of her sex
 * life ([INTIMATE_LOGS]) all happened whatever the bleeding was, and "A slip — delete it" says
 * nothing of them.
 *
 * Deleted rather than overwritten, for the tombstone [deleteJournalEvent] leaves: a start that only
 * vanished from this database would come home on the next Health Connect sync as history to
 * restore, and the woman who said "this did not happen" would find it back.
 */
suspend fun StepsDb.deleteCyclePeriod(start: String, end: String) {
    // Read once, and before the transaction opens, rather than parsed again for every day.
    // The cycle page hands over two dates as text, so neither can fail to read.
    val first = LocalDate.parse(start)
    val last = LocalDate.parse(end)
    withTransaction {
        val days = generateSequence(first) { day -> day.plusDays(1).takeIf { !it.isAfter(last) } }
        days.forEach { day ->
            dao().journalOnNow(day.toString())
                .filter { it.isOfPeriod() }
                .forEach { deleteJournalEvent(it.id) }
        }
    }
    refreshWidgetsAfterWrite()
}

/**
 * Whether a row is part of the period itself, as [deleteCyclePeriod] takes it back: a mark of its
 * start or its end, or the flow of one of its days.
 */
internal fun JournalEntry.isOfPeriod(): Boolean =
    kind == KIND_CYCLE || kind == KIND_CYCLE_END || cycleLog() in PERIOD_FLOW

/** The flow a period is logged with. Spotting is bleeding outside one, and stays when a period goes. */
private val PERIOD_FLOW = setOf(CycleLog.FLOW_LIGHT, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_HEAVY)

/**
 * The row among [sameDay] that already marks [entry]'s course and slot, other than [entry] itself.
 * A payload this build cannot read cannot be matched against the day either, so it clashes with
 * nothing: the mark is written rather than silently swallowed.
 */
internal fun markedDoseOf(entry: JournalEntry, sameDay: List<JournalEntry>): JournalEntry? {
    val slot = medSlotOf(entry) ?: return null
    return sameDay.firstOrNull { it.id != entry.id && medSlotOf(it) == slot }
}

/**
 * One act of a chat: the records it files, rewrites or takes back, and the chat itself, as one. The
 * records go through the doors their own pages use ([ChatDoors]), and the widgets and the medicine
 * alarms are redrawn once it has committed — the alarms after any dose or course written, deleted or
 * put back, which no journal-write helper does for a course.
 */
internal suspend fun <T> StepsDb.chatAct(block: suspend (ChatDoors) -> T): T {
    val doors = ChatDoors(RoomChatPort(this))
    val result = withTransaction { block(doors) }
    if (doors.medicineTouched) refreshMedicineAfterWrite()
    if (doors.recordsTouched) refreshWidgetsAfterWrite()
    // A deleted course's photograph goes after the row, as the course page deletes it: a rolled-back
    // act must not have taken the picture of a course that is still there.
    val photos = doors.leftoverPhotos
    val app = reminderApp
    if (photos.isNotEmpty() && app != null) {
        withContext(Dispatchers.IO) { photos.forEach { deleteMedicinePhoto(app, it) } }
    }
    return result
}

/**
 * Where [link]'s record stands now, read outside any act: what a chat's card draws. Nothing is
 * written, so no transaction is opened and nothing is redrawn.
 */
internal suspend fun StepsDb.resolveLink(link: FiledLink, chatKey: String): Resolved =
    ChatDoors(RoomChatPort(this)).resolve(link, chatKey)

/** The chat's store over this database: every act one [chatAct]. */
internal class RoomChatStore(private val db: StepsDb) : ChatStore {
    override suspend fun <T> act(block: suspend (ChatDoors) -> T): T = db.chatAct(block)
}

/**
 * What [ChatDoors] reads and writes, over the DAO and the write helpers in this file, so a chat's
 * record takes the same path as one its own page writes: [writeJournalEvent] with its activity
 * recalculation, [deleteJournalEvent] with its tombstones. Their own refreshes stand down inside the
 * act's transaction and [chatAct] runs them once.
 */
private class RoomChatPort(private val db: StepsDb) : ChatDbPort {
    private val dao get() = db.dao()

    override suspend fun chat(key: String): ChatRow? = dao.chat(key)

    override suspend fun saveChat(row: ChatRow) = dao.saveChat(row)

    override suspend fun deleteChat(key: String) = dao.deleteChat(key)

    override suspend fun journalAt(kind: String, at: Long): List<JournalEntry> = dao.journalAt(kind, at)

    override suspend fun journalOnNow(day: String): List<JournalEntry> = dao.journalOnNow(day)

    override suspend fun latestEntryBetween(kind: String, sinceMs: Long, untilMs: Long): JournalEntry? =
        dao.latestEntryBetween(kind, sinceMs, untilMs)

    override suspend fun writeJournal(row: JournalEntry): Long = db.writeJournalEvent(row)

    override suspend fun deleteJournal(id: Long) = db.deleteJournalEvent(id)

    override suspend fun medicationsCreatedAt(createdAt: Long): List<Medication> = dao.medicationsCreatedAt(createdAt)

    override suspend fun medication(id: Long): Medication? = dao.allMedications().firstOrNull { it.id == id }

    override suspend fun insertMedication(row: Medication): Long = dao.insertMedication(row)

    override suspend fun saveMedication(row: Medication) = dao.saveMedication(row)

    override suspend fun deleteMedication(row: Medication) = dao.deleteMedication(row.id)
}

/**
 * How a row this app published is named once the row itself is gone: by its id, which is the tail of
 * every client record id the export writes for it.
 */
internal fun journalRecordKey(id: Long): String = "journal:$id"

/**
 * What the sync has to recognise a deleted row by.
 *
 * Always its id, for the records this app published from it. Also the import marker when the row
 * came from another app — those records carry someone else's client record id, and the marker in the
 * note is the only name both stores agree on. A period's start and end leave one name more, under
 * [periodTombstoneKey].
 */
internal fun journalTombstoneKeys(id: Long, recordKey: String?): List<String> =
    listOf(journalRecordKey(id)) + listOfNotNull(recordKey)

/**
 * How a period start or end is named once the row is gone: by its kind and its day.
 *
 * The record the export writes for a period is keyed on the start day and not on either row's id,
 * so [journalRecordKey] over the id names nothing the sync will ever meet, and a deleted start or
 * end would be adopted back from Health Connect on every sync. The day is a name both stores share: the
 * record's interval says which day each of its two rows falls on, and the cycle screen keeps one
 * row of each kind per day.
 */
internal fun periodTombstoneKey(kind: String, day: String): String? = when (kind) {
    KIND_CYCLE -> "period:$day"
    KIND_CYCLE_END -> "period-end:$day"
    else -> null
}

/**
 * How a deleted line of the medical history is named once its row is gone: by its key, which was
 * made at random where the line was written and so names it on every phone a backup reaches.
 */
internal fun medicalHistoryTombstoneKey(key: String): String = "$MEDICAL_HISTORY_TOMBSTONE_PREFIX$key"

internal const val MEDICAL_HISTORY_TOMBSTONE_PREFIX = "history:"

/**
 * Takes a line off the medical history and leaves its tombstone. A backup's merge takes every line
 * the file holds that this install has nothing newer for, and a line deleted here has nothing at
 * all: a diagnosis taken back, or a pregnancy marked by mistake, would come home from the first
 * older backup restored ([withoutDeletedHistory]).
 */
suspend fun StepsDb.deleteMedicalHistory(key: String) = withTransaction {
    dao().deleteMedicalHistoryRow(key)
    dao().saveTombstone(JournalTombstone(medicalHistoryTombstoneKey(key), System.currentTimeMillis()))
}

/** The lines of the medical history a backup holds, less those deleted here on purpose. */
internal fun withoutDeletedHistory(rows: List<MedicalHistoryRow>, tombstoned: Set<String>): List<MedicalHistoryRow> =
    rows.filterNot { medicalHistoryTombstoneKey(it.key) in tombstoned }

/**
 * Removes a row and leaves its tombstone. Not cancellable once begun, as [saveJournalEvent] is not.
 *
 * A period's start takes its end with it ([periodEndsDeletedWith]): the calendar and the entry
 * editor delete one row, and an end left alone would be a stray day of bleeding the record held
 * against every period filed around it. Typed, because it does that by calling itself.
 */
suspend fun StepsDb.deleteJournalEvent(id: Long): Unit = withContext(NonCancellable) {
    val removed = withTransaction {
        val dao = dao()
        val row = dao.journalEntry(id)
        row?.takeIf { it.kind == KIND_CYCLE }
            ?.let { periodEndsDeletedWith(it, dao.cycleMarksNow()) }
            ?.forEach { deleteJournalEvent(it.id) }
        dao.deleteJournalEntry(id)
        // Health Connect keeps its copy of the row after the row is gone. Without a tombstone the
        // next sync reads that copy back as history to restore, and the deletion undoes itself.
        val deletedAt = System.currentTimeMillis()
        val keys = journalTombstoneKeys(id, row?.healthConnectRecordKey) +
            listOfNotNull(row?.let { periodTombstoneKey(it.kind, it.day) })
        keys.forEach { dao.saveTombstone(JournalTombstone(it, deletedAt)) }
        // A row that could not be read is treated as one that mattered: recalculating costs time,
        // and leaving a stale estimate behind costs a wrong figure.
        if (row == null || row.movesActivityTimeline()) {
            recalculateActivityCalories(dao, row?.occurredAt ?: Long.MIN_VALUE)
        }
        row
    }
    refreshMedicineAfterWrite()
    refreshWidgetsAfterWrite()
    // A lab sheet's files go with it: the photograph the lab camera took for it, and the read grant
    // of a file picked from another app — each once no other row names it.
    if (removed?.kind == KIND_LABS) releaseAttachmentsAfterWrite(attachmentUris(removed.labAttachments()))
}

/**
 * Recalculates every activity against the measurement timeline: what a write of many rows ends with
 * — a backup merged, a file imported, an exchange with Health Connect. Those write through the DAO,
 * past [saveJournalEvent] and what it does after its commit, so it is done here, once for the lot:
 * the medicine alarms are worked out again — a night the watch brought moves a dose taken on waking
 * — and the widgets redrawn. Without this they would keep the figures from before an import until
 * the next write by hand, or the half-hourly redraw.
 */
suspend fun StepsDb.recalculateJournal() {
    withTransaction { recalculateActivityCalories(dao(), Long.MIN_VALUE) }
    refreshMedicineAfterWrite()
    refreshWidgetsAfterWrite()
}

/**
 * Whether a written or deleted row can change what [recalculatedActivityTimeline] produces: only a
 * weight measurement moves the timeline, and only an activity reads it. A glass of water, a mood or
 * a marked dose cannot, so none of them pays for a pass over the journal.
 */
private fun JournalEntry.movesActivityTimeline(): Boolean =
    kind == KIND_MEASUREMENT || kind == KIND_ACTIVITY

/**
 * Recalculates the activities at or after [since], starting from the weight in force just before
 * it. Rows earlier than that cannot have changed, so the whole journal does not have to be read
 * and compared on every write.
 */
private suspend fun recalculateActivityCalories(dao: StepsDao, since: Long) {
    val profile = dao.profileNow() ?: return
    val baselineWeightKg = dao.weightInEffectBefore(since) ?: profile.weightKg
    val original = dao.activityTimelineSince(since)
    val changed = recalculatedActivityTimeline(original, baselineWeightKg, profile)
        .zip(original)
        .mapNotNull { (updated, old) -> updated.takeIf { it != old } }
    if (changed.isNotEmpty()) dao.saveJournalEntries(changed)
}

/** One accepted fix's speed, as [StepsDao.trackSpeedSamples] returns it. */
data class TrackSpeedSample(val timestamp: Long, val speedMps: Float)
