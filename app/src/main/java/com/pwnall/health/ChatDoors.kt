package com.pwnall.health

// The doors a chat's records go through: each one its own page's path into the database — a pain
// clears its day's "no pain" answers, a first mood waits out the cooldown, a dose is marked once a
// slot, a course is inserted and then rewritten under its id. Pure over [ChatDbPort], so the JVM
// tests run the very code a phone does; Data.kt implements the port over the DAO inside one act's
// transaction ([chatAct]).

/** What the doors need from the database. */
internal interface ChatDbPort {
    suspend fun chat(key: String): ChatRow?

    suspend fun saveChat(row: ChatRow)

    suspend fun deleteChat(key: String)

    /** Every row of [kind] on the moment [at]. */
    suspend fun journalAt(kind: String, at: Long): List<JournalEntry>

    suspend fun journalOnNow(day: String): List<JournalEntry>

    suspend fun latestEntryBetween(kind: String, sinceMs: Long, untilMs: Long): JournalEntry?

    /** The row written as every page writes one, its activity recalculation included; its id. */
    suspend fun writeJournal(row: JournalEntry): Long

    /** The row deleted as every page deletes one: the row, its tombstones, the recalculation. */
    suspend fun deleteJournal(id: Long)

    suspend fun medicationsCreatedAt(createdAt: Long): List<Medication>

    /** The course of [id], if the database holds one. */
    suspend fun medication(id: Long): Medication?

    suspend fun insertMedication(row: Medication): Long

    suspend fun saveMedication(row: Medication)

    /** The row now; its photograph after the act has committed ([ChatDoors.leftoverPhotos]). */
    suspend fun deleteMedication(row: Medication)
}

/** What a door did with a write: the row's id, or the page's own refusal and what stood in the way. */
internal sealed interface DoorResult {
    data class Written(val id: Long) : DoorResult

    data class Refused(val blockingAt: Long?, val blockingScore: Int?) : DoorResult
}

/** A record found again: a journal row, or a course. */
internal sealed interface FoundRecord {
    val id: Long

    data class Row(val entry: JournalEntry) : FoundRecord {
        override val id: Long get() = entry.id
    }

    data class Course(val course: Medication) : FoundRecord {
        override val id: Long get() = course.id
    }
}

/** A link resolved against the database ([ChatDoors.resolve]). */
internal sealed interface Resolved {
    /** Its record, as the chat wrote it or as only the chat edits it. */
    data class Found(val record: FoundRecord) : Resolved

    /** One row on its moment, and not the one the chat wrote: edited on its own page. */
    data class Changed(val record: FoundRecord) : Resolved

    /** None on its moment, or more than one it cannot be told from. */
    data object Gone : Resolved
}

/** How far a moment is stepped to find a free one before the act gives up. */
private const val FREE_MOMENT_STEPS = 999

/**
 * The rows that change what the medicine alarms say: a meal (taken with food), a dose, a course, a
 * night's sleep — as [saveJournalEvent] decides for every other writer.
 */
private fun JournalEntry.touchesMedicine(): Boolean = kind == KIND_EAT || kind == KIND_MED || sleepEndAt != null

/**
 * The doors over [port], one instance per act. They remember what the act touched, so the alarms and
 * the widgets are redrawn once, after commit, and only when something they show changed.
 */
internal class ChatDoors(val port: ChatDbPort) {
    /** Whether any dose, meal, sleep or course was written or deleted: the alarms are refreshed after commit. */
    var medicineTouched = false
        private set

    /** Whether any record at all was written or deleted: the widgets are redrawn after commit. */
    var recordsTouched = false
        private set

    private val photos = mutableListOf<String>()

    /** The photographs of the courses this act deleted, to delete once it has committed. */
    val leftoverPhotos: List<String> get() = photos.toList()

    /**
     * [wanted], or for a row of a chat filed for an earlier day ([backDated]) the millisecond after
     * it — so it never stands at the 12:00:00.000 every page gives such a row — and then the first
     * millisecond on which no row of [kind] stands, nor one a link of the chat names ([named]): a
     * record deleted elsewhere leaves its moment free, and another record there would read as its own
     * on the old card. A row is told from another by its moment, so two of one kind never share one.
     */
    suspend fun freeMoment(kind: String, wanted: Long, backDated: Boolean, named: Set<Long> = emptySet()): Long {
        val start = if (backDated) wanted + 1 else wanted
        return (0..FREE_MOMENT_STEPS).map { start + it }
            .firstOrNull { it !in named && port.journalAt(kind, it).isEmpty() }
            ?: error("no free moment of $kind within $FREE_MOMENT_STEPS ms of $start")
    }

    /** [wanted], or the first millisecond after it at which no course was written down. */
    suspend fun freeCourseMoment(wanted: Long): Long =
        (0..FREE_MOMENT_STEPS).map { wanted + it }.firstOrNull { port.medicationsCreatedAt(it).isEmpty() }
            ?: error("no free course moment within $FREE_MOMENT_STEPS ms of $wanted")

    /**
     * [row] through [door], as its own page writes it. [first]: the record is new; a rewrite of the
     * chat's own row never trips the door's first-write rule (its own mood would be inside its own
     * cooldown).
     */
    suspend fun write(row: JournalEntry, door: JournalDoor, first: Boolean): DoorResult = when (door) {
        JournalDoor.PLAIN -> DoorResult.Written(writeRow(row))

        // The pain page clears the day's "no pain today" answers on every save: a pain row overrules
        // them, and two answers that disagree are never left standing side by side.
        JournalDoor.PAIN -> {
            val id = writeRow(row)
            noPainRowsOverruled(decodePainPayload(row.measurementPayloadJson), port.journalOnNow(row.day))
                .forEach { delete(it) }
            DoorResult.Written(id)
        }

        JournalDoor.MOOD -> {
            val last = if (first) port.moodNear(row.occurredAt) else null
            if (last != null && moodCooldownLeftMs(last.occurredAt, row.occurredAt) > 0L) {
                DoorResult.Refused(last.occurredAt, last.moodScore)
            } else {
                DoorResult.Written(writeRow(row))
            }
        }

        // The dose's slot is marked once a day, first write or rewrite, and as the Medicine tab settles
        // the day ([settledSlotRefusal]): a rewound dose moved onto a slot another mark already holds is
        // refused too, and the caller takes the old row back.
        JournalDoor.DOSE -> {
            val sameDay = port.journalOnNow(row.day)
            val refusal = markedDoseOf(row, sameDay)?.let { DoorResult.Refused(it.occurredAt, null) }
                ?: settledSlotRefusal(row, sameDay)
            refusal ?: DoorResult.Written(writeRow(row))
        }
    }

    /**
     * The refusal of a course's dose [row] new on its slot — a first mark, or one moved to another
     * slot or day — as the Medicine tab settles that day ([plannedDoses]): a slot the course does not
     * ask for that day is none to mark, and one the tab shows taken — by a mark an edit of the course's
     * times left over, which it pairs with the nearest open slot — is refused with that mark's moment,
     * or the mark would make another dose read as taken. Null when the slot is open; for a dose of no
     * course; for a mark rewritten on its own slot; and for a course the database does not hold, which
     * only its key can be checked for ([markedDoseOf]).
     */
    private suspend fun settledSlotRefusal(row: JournalEntry, sameDay: List<JournalEntry>): DoorResult.Refused? {
        val key = medSlotOf(row)?.takeUnless { isOneOffDose(row) } ?: return null
        if (sameDay.any { it.id == row.id && medSlotOf(it) == key }) return null
        val course = port.medication(key.first) ?: return null
        val day = row.day.toLocalDateOrNull() ?: return null
        val dose = plannedDoses(listOf(course), sameDay.filter { it.id != row.id }, day)
            .firstOrNull { it.slot == key.second }
        return when {
            dose == null -> DoorResult.Refused(null, null)
            dose.taken -> DoorResult.Refused(dose.takenAtMs, null)
            else -> null
        }
    }

    /** [row] written under its own id, through no door: a change to a row already there, or putting it back. */
    suspend fun writeRow(row: JournalEntry): Long {
        recordsTouched = true
        if (row.touchesMedicine()) medicineTouched = true
        return port.writeJournal(row)
    }

    suspend fun delete(row: JournalEntry) {
        recordsTouched = true
        if (row.touchesMedicine()) medicineTouched = true
        port.deleteJournal(row.id)
    }

    /** A new course, under the id the database gives it. */
    suspend fun insertCourse(row: Medication): Long {
        recordsTouched = true
        medicineTouched = true
        return port.insertMedication(row.copy(id = 0))
    }

    /** A course rewritten under its id. */
    suspend fun saveCourse(row: Medication) {
        recordsTouched = true
        medicineTouched = true
        port.saveMedication(row)
    }

    /** A course taken back: the row now, its photograph once the act has committed. */
    suspend fun deleteCourse(row: Medication) {
        recordsTouched = true
        medicineTouched = true
        port.deleteMedication(row)
        if (row.photo.isNotEmpty()) photos += row.photo
    }

    /**
     * Where [link]'s record stands. The rows on its moment are read, and the one whose fingerprint is
     * [FiledLink.wsig] is the chat's. None there: gone. One there that is not it: changed on its page
     * — except a symptom row, which only the chat edits, so it is the chat's own when it names this
     * chat, [chatKey]; one another chat filed on a moment this chat's row was deleted from is that
     * chat's, never this one's. Anything else cannot be told apart and reads as gone: an ambiguous row
     * is never deleted or rewritten.
     */
    suspend fun resolve(link: FiledLink, chatKey: String): Resolved =
        if (link.kind == LINK_COURSE) resolveCourse(link) else resolveRow(link, chatKey)

    private suspend fun resolveCourse(link: FiledLink): Resolved {
        val courses = port.medicationsCreatedAt(link.at)
        val mine = courses.filter { courseSig(it) == link.wsig }
        return when {
            mine.size == 1 -> Resolved.Found(FoundRecord.Course(mine.single()))
            courses.size == 1 && mine.isEmpty() -> Resolved.Changed(FoundRecord.Course(courses.single()))
            else -> Resolved.Gone
        }
    }

    private suspend fun resolveRow(link: FiledLink, chatKey: String): Resolved {
        val rows = port.journalAt(link.kind, link.at)
        val mine = rows.filter { rowSig(it) == link.wsig }
        val lone = rows.singleOrNull()
        return when {
            mine.size == 1 -> Resolved.Found(FoundRecord.Row(mine.single()))
            lone == null -> Resolved.Gone
            link.kind != KIND_SYMPTOM -> Resolved.Changed(FoundRecord.Row(lone))
            chatKeyOf(lone) == chatKey -> Resolved.Found(FoundRecord.Row(lone))
            else -> Resolved.Gone
        }
    }
}

/**
 * The fingerprint of a journal row as written: everything a page shows of it and lets the person
 * change. A row the chat wrote is told by it from another on the same moment, and from itself once
 * edited on its page.
 *
 * A dose's payload is read with its course's id as 0: a restore renumbers every course and rewrites
 * each dose to the new id ([remappedDosePayload]), which is no change anybody made, and the link
 * names its course by moment already ([FiledLink.course]).
 */
internal fun rowSig(row: JournalEntry): Int = listOf(
    row.day,
    row.title,
    row.subtitle,
    row.signedPayload(),
    row.note,
    row.durationMinutes,
    row.moodScore,
    row.energyScore,
).hashCode()

/** The payload as the fingerprint reads it: a dose's with its course's id as 0. */
private fun JournalEntry.signedPayload(): String? =
    if (kind == KIND_MED) remappedDosePayload(measurementPayloadJson, emptyMap()) else measurementPayloadJson

/**
 * The fingerprint of a course as written. A photograph or a file added on the course page is a
 * change like any other: an Undo would otherwise delete the person's own work there.
 */
internal fun courseSig(course: Medication): Int = listOf(
    course.name,
    course.dose,
    course.schedule,
    course.times,
    course.foodRelation,
    course.startDay,
    course.endDay,
    course.note,
    course.contents,
    course.attachments,
    course.photo,
).hashCode()
