package com.pwnall.health

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The trip out to a file and back, at the two granularities a hand-written round trip never covers:
 * the table and the column.
 *
 * The first three tests read Backup.kt itself. A table given a writer and no reader falls into the
 * importer's `else -> skipValue()` arm, and a column put by a writer and never asked for by its
 * reader is read past the same way: in both cases the rows leave the phone in full and are dropped
 * on the way back in, with no error anywhere. A test written by hand cannot catch that, because it
 * is written by the same person, in the same hour, as the writer that forgot its reader.
 */
class BackupRoundTripEdgeCasesTest {
    private val tapped = 1_800_000_000_000L

    /** Each row writer in Backup.kt and the reader that has to ask back for everything it put. */
    private val writersAndReaders = mapOf(
        "ProgressPhoto" to "toProgressPhoto",
        "UserProfile" to "toProfile",
        "DaySteps" to "toDay",
        "AppOpenDay" to "toAppOpenDay",
        "ExternalDaySteps" to "toExternalDay",
        "ExternalOriginDaySteps" to "toExternalOriginDay",
        "DirectBleDaySteps" to "toDirectBleDay",
        "JournalEntry" to "toJournal",
        "Medication" to "toMedication",
        "Appointment" to "toAppointment",
        "Routine" to "toRoutine",
        "RoutineExercise" to "toRoutineExercise",
        "HourSteps" to "toHourSteps",
        "BarometerDay" to "toBarometerDay",
        "JournalTombstone" to "toJournalTombstone",
        "SavedDish" to "toSavedDish",
        "FavoriteFood" to "toFavoriteFood",
        "MedicalHistoryRow" to "toMedicalHistoryRow",
        "ChatRow" to "toChatRow",
        "TrackEntity" to "toTrack",
        "TrackPointEntity" to "toTrackPoint",
        "TrackPhotoEntity" to "toTrackPhoto",
    )

    /**
     * Keys a row's own reader is right not to ask for. A photograph's name is derived from the
     * moment it was taken rather than read, so that `../databases/steps.db` cannot be a name; its
     * bytes are taken by the streaming photo importer, which never builds the row's JSON at all;
     * and a profile's birth date is completed from either spelling by `birthDateOf`. A walk's
     * photographs travel the way the progress pictures do.
     */
    private val readElsewhere = setOf(
        "ProgressPhoto.file",
        "ProgressPhoto.jpeg",
        "TrackPhotoEntity.file",
        "TrackPhotoEntity.jpeg",
        "UserProfile.birthDate",
    )

    private fun backupSource(): String = File("src/main/java/com/pwnall/health/Backup.kt").readText()

    private fun writtenKeys(source: String, entity: String): Set<String> {
        val body = Regex("""fun $entity\.toJson\([\s\S]*?\n\n""").find(source)?.value
            ?: error("no $entity.toJson in Backup.kt")
        return Regex("""\.put\("(\w+)",""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun askedKeys(source: String, reader: String): Set<String> {
        val body = Regex("""fun JSONObject\.$reader\([\s\S]*?\n\n""").find(source)?.value
            ?: error("no JSONObject.$reader in Backup.kt")
        return Regex("""\w+\("(\w+)"[,)]""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun water(id: Long, servingG: Double, recordKey: String?) = JournalEntry(
        id = id,
        day = "2026-08-10",
        occurredAt = tapped,
        kind = KIND_WATER,
        title = "Вода",
        calories = 0.0,
        servingG = servingG,
        healthConnectRecordKey = recordKey,
    )

    /**
     * A member such as `externalStepDays` written with no branch to read it would have every restore
     * silently throw a watch's contribution away. A table test that only looks for the entity's name
     * anywhere in Backup.kt is satisfied by a writer alone; this asks the two halves to name the same
     * tables.
     */
    @Test
    fun everyTableTheExporterWritesIsReadBackByTheImporter() {
        val source = backupSource()
        val tables = Regex("""writeTable\(\s*"(\w+)",""").findAll(source).map { it.groupValues[1] }
        val inline = Regex("""\\"(\w+)\\":""").findAll(source).map { it.groupValues[1] }
        // `schema` is the one member consumed before the loop, by readSchemaFirst.
        val written = (tables + inline).toSet() - "schema"
        val read = Regex("""(?m)^\s+"(\w+)" ->""").findAll(source).map { it.groupValues[1] }.toSet()

        assertTrue("only found $written", written.size >= 20)
        assertEquals("exported with no reader", emptySet<String>(), written - read)
        assertEquals("read but never exported", emptySet<String>(), read - written)
    }

    /**
     * A column such as `attachments`, on the courses and the visits, left out of both writers would
     * bring a restored prescription back without its scans. That half is pinned in BackupMergeTest;
     * this is the other half, where a key is written and no reader ever asks for it.
     */
    @Test
    fun everyKeyAWriterPutsIsAskedForByItsReader() {
        val source = backupSource()
        val dropped = writersAndReaders.flatMap { (entity, reader) ->
            val asked = askedKeys(source, reader)
            writtenKeys(source, entity)
                .filterNot { it in asked || "$entity.$it" in readElsewhere }
                .map { "$entity.$it" }
        }
        val keys = writersAndReaders.keys.sumOf { writtenKeys(source, it).size }

        assertTrue("only found $keys keys", keys >= 150)
        assertEquals(emptyList<String>(), dropped)
    }

    /** A writer added without an entry above would be checked by nothing at all. */
    @Test
    fun everyRowWriterInTheFileIsPairedWithAReader() {
        val writers = Regex("""fun (\w+)\.toJson\(""").findAll(backupSource())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals("unpaired", emptySet<String>(), writers - writersAndReaders.keys)
        assertEquals("named but absent", emptySet<String>(), writersAndReaders.keys - writers)
    }

    /**
     * The number stamped on every file has to be the highest the reader takes. Raising it without
     * touching BackupProvenance.kt leaves every test here green and makes every backup this build
     * writes unimportable — by this build included.
     */
    @Test
    fun theSchemaStampedOnEveryFileIsTheHighestTheImporterAccepts() {
        val stamped = Regex("""const val BACKUP_SCHEMA = (\d+)""").find(backupSource())
            ?.groupValues?.get(1)?.toInt()
            ?: error("no BACKUP_SCHEMA in Backup.kt")

        assertTrue("writes $stamped", isSupportedBackupSchema(stamped))
        assertFalse("accepts ${stamped + 1}", isSupportedBackupSchema(stamped + 1))
        assertTrue("refuses the first schema ever written", isSupportedBackupSchema(1))
    }

    /**
     * The shape a schema number promises: the members of a file, and the keys each row writer puts.
     * The tests above catch a writer without its reader, but not the number left where it was: a
     * member or a column added under 11 is read past in silence by an app that reads 11, which is
     * how the progress photographs and the chats would have been lost (see BACKUP_SCHEMA's KDoc).
     * Changing the shape is changing this text, and the line above it asks whether the number rose.
     */
    @Test
    fun theFileHasTheShapeItsSchemaNumberStandsFor() {
        val source = backupSource()
        val stamped = Regex("""const val BACKUP_SCHEMA = (\d+)""").find(source)?.groupValues?.get(1)
        val tables = Regex("""writeTable\(\s*"(\w+)",""").findAll(source).map { it.groupValues[1] }
        val inline = Regex("""\\"(\w+)\\":""").findAll(source).map { it.groupValues[1] }
        val written = mapOf("file" to (tables + inline).toSortedSet()) +
            writersAndReaders.keys.associateWith { writtenKeys(source, it).toSortedSet() }
        val promised = SCHEMA_11_SHAPE.trimIndent().split(Regex("""\s+(?=\w+:)""")).associate { line ->
            line.substringBefore(":") to line.substringAfter(":").split(Regex("""\s+""")).filter(String::isNotEmpty)
        }

        assertEquals("BACKUP_SCHEMA moved: write down the new schema's shape here", "11", stamped)
        assertEquals(
            "the shape changed under schema 11: raise BACKUP_SCHEMA and isSupportedBackupSchema first",
            promised.toSortedMap(),
            written.mapValues { it.value.toList() }.toSortedMap(),
        )
    }

    /**
     * Two glasses logged into another app at the same instant — 200 ml and 250 ml — reached phone
     * A's journal through Health Connect; phone B had adopted only the first. Restoring A's file
     * onto B has to bring the second: a row this database has never held is not one it already has,
     * and dropping it is the one thing a backup exists to prevent.
     */
    @Test
    fun `two records of one moment differing only in how much was drunk are both restored`() {
        val first = water(id = 1, servingG = 200.0, recordKey = "${HEALTH_IMPORT_MARKER_PREFIX}com.x:aaa")
        val second = water(id = 2, servingG = 250.0, recordKey = "${HEALTH_IMPORT_MARKER_PREFIX}com.x:bbb")

        val merge = backupMerge(listOf(first), listOf(first, second), JournalEntry::id, key = ::journalKey)

        assertEquals("inserts ${merge.inserts}", listOf(second), merge.inserts)
        assertEquals(mapOf(1L to 1L), merge.matched)
    }

    /**
     * Every course id in a restored dose has to name a course in this database or no course at all.
     * A payload that never mentions one is prose to this function and comes back untouched.
     */
    @Test
    fun `a course reference is rewritten wherever it appears and nowhere else`() {
        val unrelated = """{"slot":"WAKE"}"""

        assertEquals(unrelated, remappedDosePayload(unrelated, mapOf(1L to 42L)))
        assertEquals(
            """{"medicationId":42,"previous":{"medicationId":42}}""",
            remappedDosePayload("""{"medicationId":1,"previous":{"medicationId":1}}""", mapOf(1L to 42L)),
        )
        // A file written by a JSON printer that spaces its members out is still a file to restore.
        assertEquals(
            """{"medicationId" : 42}""",
            remappedDosePayload("""{"medicationId" : 1}""", mapOf(1L to 42L)),
        )
    }

    /**
     * Files of every schema [isSupportedBackupSchema] takes import here, and the only thing making
     * that true is the opt* on each young column. One of them tightened into a get* refuses every
     * file written before that column existed, and the refusal reads to its owner as "this file
     * cannot be read".
     */
    @Test
    fun `a file written before a column existed imports with the data class's own defaults`() {
        val course = JSONObject()
            .put("id", 1)
            .put("name", "Metformin")
            .put("startDay", "2026-01-01")
            .toMedication()
        val visit = JSONObject().put("id", 2).put("atMs", tapped).toAppointment()

        assertEquals(Medication(id = 1, name = "Metformin", startDay = "2026-01-01", createdAt = 0), course)
        assertEquals(Appointment(id = 2, specialty = VISIT_SPECIALTY_OTHER, atMs = tapped), visit)
    }

    /**
     * A quoted number is a number: a hand-edited file, or one a third-party tool wrote, has to
     * import rather than be refused as damaged. Nothing in this repo pins the coercion, which lives
     * in the reader rather than in the app.
     */
    @Test
    fun `a number that arrived quoted is still a number`() {
        val course = JSONObject()
            .put("id", "3")
            .put("name", "Metformin")
            .put("startDay", "2026-01-01")
            .put("createdAt", "5")
            .toMedication()
        val visit = JSONObject()
            .put("id", "4")
            .put("atMs", "1800000000000")
            .put("remindDaysBefore", "2")
            .toAppointment()

        assertEquals(3L, course.id)
        assertEquals(5L, course.createdAt)
        assertEquals(4L, visit.id)
        assertEquals(tapped, visit.atMs)
        assertEquals(2, visit.remindDaysBefore)
    }

    /**
     * A quoted number is a number, so a quoted `1e999` is one too: an infinity, which SQLite stores
     * and JSONObject then refuses to write — and every later export of the phone that imported it
     * would end there. A figure the row cannot do without refuses the file, one with a default
     * takes the default, one that may be absent is absent; and a float column is kept from the
     * infinity a number too large for it becomes.
     */
    @Test
    fun `a number that is not finite never reaches a row`() {
        fun drink() = JSONObject()
            .put("id", 1)
            .put("day", "2026-08-10")
            .put("occurredAt", tapped)
            .put("kind", KIND_WATER)
            .put("title", "Water")
            .put("calories", 0)

        val read = drink().put("servingG", "1e999").put("fiberG", "1e999").put("caffeineMg", "NaN").toJournal()
        val photo = JSONObject().put("trackId", 3).put("takenAt", tapped).put("accuracyM", 1e300).toTrackPhoto()

        assertEquals(0.0, read.servingG, 0.0)
        assertNull(read.fiberG)
        assertNull(read.caffeineMg)
        assertNull(photo.accuracyM)
        assertThrows(IllegalArgumentException::class.java) { drink().put("calories", "1e999").toJournal() }
    }

    /**
     * Forward compatibility inside a row is what lets the schema number stay where it is while
     * columns are added. A reader that enumerated the object's members instead of asking for the
     * ones it wants would refuse a later build's file as unreadable rather than as newer.
     */
    @Test
    fun `a member from a newer app inside a row is read past rather than refused`() {
        val course = Medication(id = 1, name = "Metformin", startDay = "2026-01-01", createdAt = 5)

        val restored = course.toJson().put("pharmacy", "Ригла").put("refillCount", 3).toMedication()

        assertEquals(course, restored)
    }

    /**
     * A truncated row must arrive as one refusal the person can act on, not as a database of half
     * rows: id 0 is the sentinel that means "give me a new row", and a dose pointed at course 0 is
     * a dose pointed at nothing.
     */
    @Test
    fun `a row without the fields its reader requires is refused rather than half-restored`() {
        assertThrows(JSONException::class.java) { JSONObject().put("name", "Metformin").toMedication() }
        assertThrows(JSONException::class.java) { JSONObject().put("specialty", "GP").toAppointment() }
    }

    /**
     * A prescription with no end and a visit not yet attended. JSONObject.put drops the member
     * rather than storing a null, so absent and null have to read the same: getting it wrong turns
     * an open-ended course into one that ended, and an appointment still to come into one attended.
     */
    @Test
    fun `an open-ended course and a visit not yet attended survive the file`() {
        val course = Medication(
            id = 3,
            name = "Метформин",
            startDay = "2026-01-01",
            endDay = null,
            createdAt = 5,
        )
        val visit = Appointment(id = 4, specialty = "GP", atMs = tapped, doneAt = null, remindedAt = null)

        val restoredCourse = course.toJson().toMedication()
        val restoredVisit = visit.toJson().toAppointment()

        assertNull("endDay ${restoredCourse.endDay}", restoredCourse.endDay)
        assertNull("doneAt ${restoredVisit.doneAt}", restoredVisit.doneAt)
        assertNull("remindedAt ${restoredVisit.remindedAt}", restoredVisit.remindedAt)
        assertEquals(course, restoredCourse)
        assertEquals(visit, restoredVisit)
    }

    /**
     * A chat goes out whole and comes back whole, and of two copies of one chat — the one on this
     * phone and the one in the file, both under its key — the one written later stands, as a
     * medical-history row's does: an old backup must not put back a conversation that has gone on
     * since, and a newer one must bring back what was said after this copy was made.
     */
    @Test
    fun `a chat comes back whole and the copy written later wins`() {
        val here = ChatRow("k-1", day = "2026-10-05", startedAt = tapped, updatedAt = tapped + 10, payload = "{}")
        val older = here.copy(updatedAt = tapped + 5, payload = """{"rev":1}""")
        val newer = here.copy(updatedAt = tapped + 20, payload = """{"rev":9}""")
        val another = ChatRow("k-2", day = "2026-10-04", startedAt = 1L, updatedAt = 2L, payload = """{"x":"«ё»"}""")

        assertEquals(here, here.toJson().toChatRow())
        assertEquals(another, another.toJson().toChatRow())
        assertEquals(
            listOf(newer, another),
            newerImportedRows(listOf(here), listOf(older, newer, another), ChatRow::key, ChatRow::updatedAt),
        )
        // A file with no `updatedAt` takes the moment the chat began.
        val bare = JSONObject().put("key", "k-3").put("day", "2026-10-01").put("startedAt", 7L).put("payload", "{}")
        assertEquals(7L, bare.toChatRow().updatedAt)
        assertThrows(JSONException::class.java) { JSONObject().put("key", "k-4").toChatRow() }
    }

    /**
     * The match map is built against a list read earlier in the transaction, so it can name a row
     * the caller no longer holds. Writing the file's summary under that id would put a route in the
     * history with a distance, an ascent and not one fix behind it.
     */
    @Test
    fun `a matched track whose local row has gone is not written back under its id`() {
        val fromFile = TrackEntity(id = 99, startedAt = tapped, distanceMeters = 1_200.0, points = 130)

        val fuller = fullerMatchedTracks(emptyList(), listOf(fromFile), mapOf(99L to 3L))

        assertEquals("resurrected $fuller", emptyList<TrackEntity>(), fuller)
    }

    /**
     * One damaged measurement must not take a whole history with it. The export renders inside the
     * database's own transaction and exportSpooled deletes the document behind a failure, so a row
     * that will not render is a person whose backup never writes and who is told only that it did
     * not — with no way to find the row or to get past it.
     */
    @Test
    fun `a recording whose top speed is not a number still renders into the file`() {
        val damaged = TrackEntity(id = 1, startedAt = tapped, maxSpeedMps = Double.NaN)

        val row = damaged.toJson(exportedAt = tapped + 1_000)

        assertTrue("maxSpeedMps ${row.optDouble("maxSpeedMps", 0.0)}", row.optDouble("maxSpeedMps", 0.0).isFinite())
    }

    private companion object {
        /** What a schema 11 file holds; each name before a colon is a row writer, or the file itself. */
        const val SCHEMA_11_SHAPE = """
            file: appOpenDays appointments barometerDays chats days directBleStepDays exportedAt
                externalStepDays externalStepOriginDays favoriteFoods hourlySteps installId journal
                journalTombstones labCaptures legacyStepDays medicalHistory medications profile progressPhotos
                routineExercises routines savedDishes schema trackPhotos trackPoints tracks
            AppOpenDay: day
            Appointment: atMs attachments doctor doneAt id note place remindDaysBefore remindedAt specialty
            BarometerDay: ascentM day pressureSamples pressureSumHpa
            ChatRow: day key payload startedAt updatedAt
            DaySteps: day steps
            DirectBleDaySteps: day sourceId sourceLabel steps updatedAt
            ExternalDaySteps: day steps
            ExternalOriginDaySteps: day packageName steps
            FavoriteFood: calories carbsG cholesterolMg createdAt fatG fiberG key proteinG saturatedFatG
                servingG sodiumMg subtitle sugarG title
            HourSteps: day hour steps
            JournalEntry: activityCode bodyFatPercent breathCycles caffeineMg calories carbsG cholesterolMg day
                durationMinutes editedAt energyScore enteredPer100g fatG fiberG foodSource
                healthConnectRecordKey id kind loadKg measurementHeartRateBpm measurementHeightCm
                measurementPayloadJson measurementSource measurementWeightKg met moodScore note occurredAt
                proteinG reps saturatedFatG servingG sets sleepEndAt sleepStartAt sodiumMg subtitle sugarG title
                zoneOffsetSeconds
            JournalTombstone: deletedAt recordKey
            MedicalHistoryRow: answeredAt detail endDay key kind note startDay title updatedAt
            Medication: attachments contents createdAt dose endDay foodRelation id name note photo schedule
                startDay times
            ProgressPhoto: day file jpeg takenAt
            Routine: createdAt days description id name photo
            RoutineExercise: activityCode durationMinutes id loadKg position reps restSeconds routineId sets
                setsJson superset
            SavedDish: createdAt items key name
            TrackEntity: distanceMeters elevationGainM elevationLossM endedAt id maxSpeedMps name pausedAt
                pausedMillis points runningDistanceMeters startedAt stoppedMillis vehicleDistanceMeters
                walkingDistanceMeters zoneOffsetSeconds
            TrackPhotoEntity: accuracyM altitudeM bearingAccuracyDeg bearingDeg file jpeg lat lon pitchDeg
                rollDeg takenAt trackId
            TrackPointEntity: accepted accuracyM altitudeM altitudeMsl ambientTemperatureC barometricAltitudeM
                cadenceHz correctedLat correctedLon elapsedRealtimeNanos environmentSpike filterReason id lat
                lon motionStd movementType pressureHpa provider segmentStart speedAccuracyMps speedMps timestamp
                trackId verticalAccuracyM
            UserProfile: activityLevel allergies automaticSleepDetection birthDate dailyStepGoal energyGoal
                heightCm remindersEnabled sex unitSystem weightKg
        """
    }
}
