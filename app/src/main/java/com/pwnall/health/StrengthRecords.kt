package com.pwnall.health

import org.json.JSONObject

/**
 * Personal records for an exercise of sets and reps, from the journal rows it has already written.
 *
 * Four records, each a plain fact about one set or one row: the heaviest weight moved for at least
 * one rep, the best estimated one-rep maximum, the most reps in a set done without added weight,
 * and the most weight lifted across one row (a session's sets of the exercise, reps × weight).
 * Nothing here is a judgement about the person; a record is the largest number in their own log.
 *
 * Weights are kilograms throughout, the unit the journal stores — in a row's columns and in the
 * sets of its payload alike, whichever unit the profile that wrote them was showing (see
 * [encodeToJson]).
 */
internal data class StrengthSet(val loadKg: Double, val reps: Int, val occurredAt: Long)

internal data class ExerciseRecords(
    val heaviest: StrengthSet?,
    val bestE1rm: StrengthSet?,
    val mostReps: StrengthSet?,
    val bestVolumeKg: Double,
) {
    val e1rmKg: Double? get() = bestE1rm?.let { epleyE1rm(it.loadKg, it.reps) }
}

internal enum class RecordKind { E1RM, HEAVIEST, REPS, VOLUME }

/** One record a session broke: which, the new figure and the one it beat. Volume and weights in kg. */
internal data class BrokenRecord(val code: String, val kind: RecordKind, val value: Double, val was: Double)

/**
 * The Epley estimate of the weight that could be lifted once, or null where it means little.
 *
 * w × (1 + reps ⁄ 30), and for a single rep the weight itself. Every one-rep formula drifts apart
 * from the others and from what people actually lift past about a dozen reps, so a set longer than
 * [E1RM_MAX_REPS] gives no estimate rather than a confident wrong one.
 */
internal fun epleyE1rm(loadKg: Double, reps: Int): Double? = when {
    loadKg <= 0.0 || reps < 1 || reps > E1RM_MAX_REPS -> null
    reps == 1 -> loadKg
    else -> loadKg * (1.0 + reps / 30.0)
}

internal const val E1RM_MAX_REPS = 12

/**
 * The sets one journal row holds, in kilograms. A row a session, a template, Freestyle or an import
 * wrote keeps each set in its payload; a row typed into the plain form keeps sets × reps at one
 * weight in its columns, and is read as that many identical sets.
 */
internal fun strengthSetsOf(entry: JournalEntry): List<StrengthSet> {
    val stored = payloadSets(entry).filter { it.reps > 0 }
    val reps = entry.reps ?: 0
    return when {
        stored.isNotEmpty() -> stored

        reps <= 0 -> emptyList()

        else -> {
            val one = StrengthSet(entry.loadKg?.coerceAtLeast(0.0) ?: 0.0, reps, entry.occurredAt)
            List((entry.sets ?: 1).coerceIn(1, MAX_FORM_SETS)) { one }
        }
    }
}

/**
 * The payload's sets as the numbers it stores. Not through [decodeSetsFromJson], whose load is the
 * text of a set box, to one decimal: 135 lb is 61.235 kg in the payload, would come back as 61.2 kg,
 * and the same 135 lb lifted again would then stand more than a rounding step above its own record.
 */
private fun payloadSets(entry: JournalEntry): List<StrengthSet> = runCatching {
    val json = entry.measurementPayloadJson
    if (json.isNullOrBlank()) return@runCatching emptyList()
    val array = JSONObject(json).optJSONArray("sets") ?: return@runCatching emptyList()
    (0 until array.length()).map { i ->
        val set = array.getJSONObject(i)
        // A backup edited by hand can hold anything under the key; only a finite weight is one.
        val kg = set.optDouble("loadKg", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        StrengthSet(kg, set.optInt("reps"), entry.occurredAt)
    }
}.getOrDefault(emptyList())

/** The records in [rows], all of one exercise; null when no row holds a set of reps at all. */
internal fun exerciseRecords(rows: List<JournalEntry>): ExerciseRecords? {
    val perRow = rows.map(::strengthSetsOf).filter { it.isNotEmpty() }
    if (perRow.isEmpty()) return null
    return recordsOf(perRow)
}

private fun recordsOf(perRow: List<List<StrengthSet>>): ExerciseRecords {
    val sets = perRow.flatten()
    // Moved for at least one rep: a set answered with nought reps was an attempt at the weight, and
    // a session's own sets reach here with those still among them.
    val loaded = sets.filter { it.loadKg > 0.0 && it.reps > 0 }
    return ExerciseRecords(
        // Ties go to the earliest: the record is the first time that weight went up, not the latest.
        heaviest = loaded.minWithOrNull(compareByDescending<StrengthSet> { it.loadKg }.thenBy { it.occurredAt }),
        bestE1rm = loaded.filter { epleyE1rm(it.loadKg, it.reps) != null }
            .minWithOrNull(compareByDescending<StrengthSet> { epleyE1rm(it.loadKg, it.reps) }.thenBy { it.occurredAt }),
        mostReps = sets.filter { it.loadKg <= 0.0 }
            .minWithOrNull(compareByDescending<StrengthSet> { it.reps }.thenBy { it.occurredAt }),
        bestVolumeKg = perRow.maxOf { volumeKg(it) },
    )
}

private fun volumeKg(row: List<StrengthSet>): Double = row.sumOf { it.loadKg * it.reps }

/** The volume of the biggest of [rows], and none for no rows. */
private fun bestVolumeKg(rows: List<List<StrengthSet>>): Double = rows.maxOfOrNull(::volumeKg) ?: 0.0

/**
 * The records [session] breaks against [before]: every other row of the exercise in the journal as
 * [recordsByExercise] reads it, later ones included when the session is logged for an earlier day.
 *
 * Nothing is broken by the first session of an exercise: with no history every set would be a
 * record, and a badge on everything says nothing. A figure has to pass the old one by more than a
 * rounding step — a hundredth of a kilogram, a rep — to count.
 *
 * [rows] are the journal rows [session] is written as: one, unless the session came back to the
 * exercise after another, which writes each stretch of it as a row of its own. Volume is a row's, as
 * the records read back from the journal measure it — summed over the whole session, a record would
 * be announced that no row held, and the next session measured against one that was never kept.
 */
internal fun brokenRecords(
    code: String,
    before: ExerciseRecords?,
    session: List<StrengthSet>,
    rows: List<List<StrengthSet>> = listOf(session),
): List<BrokenRecord> {
    if (before == null || session.isEmpty()) return emptyList()
    val now = recordsOf(listOf(session))
    val volumeNow = bestVolumeKg(rows)
    return buildList {
        val e1rmNow = now.e1rmKg
        val e1rmBefore = before.e1rmKg
        if (e1rmNow != null && e1rmBefore != null && e1rmNow > e1rmBefore + WEIGHT_STEP) {
            add(BrokenRecord(code, RecordKind.E1RM, e1rmNow, e1rmBefore))
        }
        val heavyNow = now.heaviest?.loadKg
        val heavyBefore = before.heaviest?.loadKg
        if (heavyNow != null && heavyBefore != null && heavyNow > heavyBefore + WEIGHT_STEP) {
            add(BrokenRecord(code, RecordKind.HEAVIEST, heavyNow, heavyBefore))
        }
        val repsNow = now.mostReps?.reps
        val repsBefore = before.mostReps?.reps
        if (repsNow != null && repsBefore != null && repsNow > repsBefore) {
            add(BrokenRecord(code, RecordKind.REPS, repsNow.toDouble(), repsBefore.toDouble()))
        }
        if (before.bestVolumeKg > 0.0 && volumeNow > before.bestVolumeKg + VOLUME_STEP) {
            add(BrokenRecord(code, RecordKind.VOLUME, volumeNow, before.bestVolumeKg))
        }
    }
}

/**
 * Which of [session]'s sets hold a record it broke: the set with the new best estimate, the new
 * heaviest weight or the new most reps. Session volume belongs to no one set and marks none.
 */
internal fun recordSetIndices(before: ExerciseRecords?, session: List<StrengthSet>): Set<Int> {
    val kinds = brokenRecords("", before, session).map { it.kind }.toSet()
    if (kinds.isEmpty()) return emptySet()
    fun firstBest(selector: (StrengthSet) -> Double?): Int? {
        val best = session.mapNotNull(selector).maxOrNull() ?: return null
        return session.indexOfFirst { selector(it) == best }.takeIf { it >= 0 }
    }
    return buildSet {
        if (RecordKind.E1RM in kinds) firstBest { epleyE1rm(it.loadKg, it.reps) }?.let(::add)
        if (RecordKind.HEAVIEST in kinds) firstBest { s -> s.loadKg.takeIf { s.loadKg > 0 && s.reps > 0 } }?.let(::add)
        if (RecordKind.REPS in kinds) firstBest { s -> s.reps.toDouble().takeIf { s.loadKg <= 0.0 } }?.let(::add)
    }
}

/**
 * The records of each exercise in [codes] as the journal holds them now, read once — leaving out the
 * row [excludeId], so a saved workout opened again is not measured against itself.
 */
internal suspend fun recordsByExercise(
    dao: StepsDao,
    codes: Collection<String>,
    excludeId: Long? = null,
): Map<String, ExerciseRecords> {
    if (codes.isEmpty()) return emptyMap()
    return dao.activitiesOf(codes.distinct()).filter { it.id != excludeId }.groupBy { it.activityCode.orEmpty() }
        .mapNotNull { (code, rows) -> exerciseRecords(rows)?.let { code to it } }
        .toMap()
}

/** A hundredth of a kilogram: below it, two weights are the same weight typed twice. */
private const val WEIGHT_STEP = 0.01

/**
 * Half a kilogram of volume: one more rep with the lightest plate a gym hands out. Below it a "new"
 * volume is the same session's loads rounded differently, summed over every rep.
 */
private const val VOLUME_STEP = 0.5

/** A form row says how many sets; past this it is a typo, not a session. */
private const val MAX_FORM_SETS = 100
