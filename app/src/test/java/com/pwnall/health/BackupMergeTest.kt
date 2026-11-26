package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two properties a restore has to hold at once, and which pull in opposite directions.
 *
 * A backup carries the ids of the install that wrote it, and those ids restart at 1 on every fresh
 * install: merging by id silently replaces whatever local rows happen to share them. Inserting
 * everything as new rows instead is only safe if a second import of the same file recognises what
 * it already put there, which is what the content key below is for.
 */
class BackupMergeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tapped = 1_800_000_000_000L

    private fun entry(id: Long, occurredAt: Long, title: String) = JournalEntry(
        id = id,
        day = "2026-08-10",
        occurredAt = occurredAt,
        kind = KIND_WATER,
        title = title,
        calories = 0.0,
        servingG = 220.0,
    )

    @Test
    fun rowsTheDatabaseDoesNotHaveAreInsertedRatherThanMergedOntoLocalIds() {
        val local = listOf(entry(1, tapped, "Вода"), entry(2, tapped + 60_000, "Вода"))
        val backup = listOf(
            entry(1, tapped + 3_600_000, "Вода"),
            entry(2, tapped + 7_200_000, "Вода"),
        )

        val merge = backupMerge(local, backup, JournalEntry::id, key = ::journalKey)

        assertEquals(backup, merge.inserts)
        assertTrue(merge.matched.isEmpty())
    }

    /**
     * A file this installation signed names its rows by the ids this database gave them, and ids are
     * never reused: the row under that id is the same row, corrected since or not, and is not put
     * back as a second one. The kind is held to as well — a database started over under an old
     * signature hands the same numbers to unrelated rows.
     */
    @Test
    fun `a file of this install's own matches a corrected row by its id`() {
        val local = listOf(
            entry(1, tapped, "Water, corrected"),
            entry(2, tapped + 60_000, "Вода").copy(kind = KIND_LABS),
        )
        val backup = listOf(entry(1, tapped, "Вода"), entry(2, tapped + 60_000, "Вода"))

        val merge = backupMerge(
            local,
            backup,
            JournalEntry::id,
            byId = true,
            sameRow = { here, filed -> here.kind == filed.kind },
            key = ::journalKey,
        )

        assertEquals(listOf(backup[1]), merge.inserts)
        assertEquals(mapOf(1L to 1L), merge.matched)
    }

    /**
     * The row of another kind that [backupMerge] would not take for the file's is inserted, and under
     * the file's id it would collide with the one standing here, and the table's key would refuse it
     * and with it the whole import. A free id is kept, as is the rule for a file of this install's own, and goes
     * in first: numbered before it, the fresh row would have been given 3.
     */
    @Test
    fun `a row of this install's own whose id is taken here is numbered afresh`() {
        val here = listOf(entry(2, tapped, "Вода").copy(kind = KIND_LABS))
        val filed = listOf(entry(2, tapped + 60_000, "Вода"), entry(3, tapped + 120_000, "Чай"))

        assertEquals(listOf(3L, 0L), journalInserts(filed, here, sameInstall = true).map(JournalEntry::id))
        assertEquals(listOf(0L, 0L), journalInserts(filed, here, sameInstall = false).map(JournalEntry::id))
    }

    /** Importing the same file twice must add nothing the second time. */
    @Test
    fun aSecondImportOfTheSameFileInsertsNothing() {
        val backup = listOf(entry(7, tapped, "Вода"), entry(8, tapped + 60_000, "Чай"))
        val stored = backup.mapIndexed { index, row -> row.copy(id = index + 100L) }

        val merge = backupMerge(stored, backup, JournalEntry::id, key = ::journalKey)

        assertTrue(merge.inserts.isEmpty())
        assertEquals(mapOf(7L to 100L, 8L to 101L), merge.matched)
    }

    /** A course that is already here keeps its local id, so its restored doses can point at it. */
    @Test
    fun anAlreadyStoredParentReportsTheIdItHasHere() {
        val stored = listOf(Medication(id = 42, name = "Metformin", startDay = "2026-01-01", createdAt = 5))
        val backup = listOf(Medication(id = 1, name = "Metformin", startDay = "2026-01-01", createdAt = 5))

        val merge = backupMerge(stored, backup, Medication::id) {
            listOf(it.name, it.startDay, it.createdAt)
        }

        assertTrue(merge.inserts.isEmpty())
        assertEquals(mapOf(1L to 42L), merge.matched)
    }

    @Test
    fun aRestoredDoseFollowsItsCourseToTheIdThisDatabaseGaveIt() {
        assertEquals(
            """{"medicationId":42,"slot":"WAKE"}""",
            remappedDosePayload("""{"medicationId":1,"slot":"WAKE"}""", mapOf(1L to 42L)),
        )
    }

    /** A payload naming a course the file did not carry ends up pointing at no course at all. */
    @Test
    fun anUnknownCourseReferencePointsAtNoCourse() {
        assertEquals(
            """{"medicationId":0,"slot":"480"}""",
            remappedDosePayload("""{"medicationId":9,"slot":"480"}""", mapOf(1L to 42L)),
        )
        assertNull(remappedDosePayload(null, mapOf(1L to 42L)))
    }

    /**
     * A plan follows its template to the id this database gave it, and one whose template the file
     * did not carry points at none — never at whichever template here holds the old number, whose
     * deletion would take the plan along.
     */
    @Test
    fun aRestoredPlanFollowsItsTemplateAndAnUnknownOnePointsAtNone() {
        assertEquals("""{"routineId":7}""", remappedPlanPayload("""{"routineId":2}""", mapOf(2L to 7L)))
        assertEquals("""{"routineId":0}""", remappedPlanPayload("""{"routineId":5}""", mapOf(2L to 7L)))
        // Another app's plan carries no template to follow.
        assertNull(remappedPlanPayload(null, mapOf(2L to 7L)))
    }

    /**
     * The id answers "is this file this database's own", and a phone set up from another by Android's
     * transfer stops being the phone that wrote the old one's files as soon as both are used: each
     * gives the next id to a row of its own. So the id is kept where no transfer reaches, and a
     * folder holding none — a fresh install, a phone a transfer has just set up — gets a new one.
     */
    @Test
    fun `an installation keeps its name and a phone set up by transfer gets its own`() {
        val oldPhone = File(folder.newFolder("old"), "install-id")
        val newPhone = File(folder.newFolder("new"), "install-id")

        val name = installIdIn(oldPhone)

        assertEquals(name, installIdIn(oldPhone))
        assertNotEquals(name, installIdIn(newPhone))
        // Which folder it is only a phone shows, so the wiring is pinned in the source: the one a
        // transfer leaves out, for the reading and for the forgetting alike.
        val source = File("src/main/java/com/pwnall/health/Backup.kt").readText()
        assertTrue("installIdIn(File(context.noBackupFilesDir, INSTALL_ID_FILE))" in source)
        assertTrue("File(context.noBackupFilesDir, INSTALL_ID_FILE).delete()" in source)
    }

    /** Why the name may not be shared: under it two phones' unrelated rows of one id are one row. */
    @Test
    fun `under one name an unrelated row of the same id is dropped as already here`() {
        val here = listOf(entry(7, tapped, "Вода"))
        val there = listOf(entry(7, tapped + 3_600_000, "Чай"))
        val sameKind = { held: JournalEntry, filed: JournalEntry -> held.kind == filed.kind }

        val shared = backupMerge(here, there, JournalEntry::id, byId = true, sameRow = sameKind, key = ::journalKey)
        val apart = backupMerge(here, there, JournalEntry::id, key = ::journalKey)

        assertEquals(emptyList<JournalEntry>(), shared.inserts)
        assertEquals(there, apart.inserts)
    }

    /**
     * The summary after an import says what changed. Counting every day the file carried, the same
     * file imported a second time would report all its days again, having changed none.
     */
    @Test
    fun `only a day the file brought or raised counts as changed`() {
        val here = listOf(DaySteps("2026-08-10", 10_000), DaySteps("2026-08-11", 4_000))
        val file = listOf(DaySteps("2026-08-10", 5_000), DaySteps("2026-08-11", 6_000), DaySteps("2026-08-12", 0))
        val merged = mergeDaySteps(here, file)

        assertEquals(2, changedDays(here, merged))
        // What the first import wrote is what the second finds here.
        assertEquals(0, changedDays(merged, mergeDaySteps(merged, file)))
    }

    @Test
    fun cumulativeBackupRowsNeverReduceNewerLocalTotals() {
        assertEquals(
            listOf(DaySteps("2026-08-10", 10_000)),
            mergeDaySteps(
                existing = listOf(DaySteps("2026-08-10", 10_000)),
                imported = listOf(DaySteps("2026-08-10", 5_000)),
            ),
        )
        assertEquals(
            listOf(HourSteps("2026-08-10", 9, 900)),
            mergeHourSteps(
                existing = listOf(HourSteps("2026-08-10", 9, 900)),
                imported = listOf(HourSteps("2026-08-10", 9, 400)),
            ),
        )
    }

    @Test
    fun directBleMergePrefersFreshnessThenTheGreaterCumulativeTotal() {
        val local = DirectBleDaySteps("ring", "2026-08-10", 8_000, updatedAt = 20)
        val stale = local.copy(steps = 10_000, updatedAt = 10)
        val tied = local.copy(steps = 9_000)

        assertEquals(listOf(local), mergeDirectBleDays(listOf(local), listOf(stale)))
        assertEquals(listOf(tied), mergeDirectBleDays(listOf(local), listOf(tied)))
    }

    @Test
    fun barometerMergeKeepsTheDenserPressureSeriesAndLargestAscent() {
        val local = BarometerDay("2026-08-10", ascentM = 20.0, pressureSumHpa = 2_000.0, pressureSamples = 2)
        val backup = BarometerDay("2026-08-10", ascentM = 30.0, pressureSumHpa = 1_000.0, pressureSamples = 1)

        assertEquals(
            listOf(local.copy(ascentM = 30.0)),
            mergeBarometerDays(listOf(local), listOf(backup)),
        )
    }

    @Test
    fun `the fuller recording keeps its summary when both installs hold the walk`() {
        val standing = TrackEntity(id = 3, startedAt = tapped, distanceMeters = 400.0, points = 40)
        val fromFile = standing.copy(id = 99, distanceMeters = 1_200.0, points = 130)

        // The file watched more of the route, so its figures win — under the local row's id.
        assertEquals(
            listOf(fromFile.copy(id = 3)),
            fullerMatchedTracks(listOf(standing), listOf(fromFile), mapOf(99L to 3L)),
        )
        // A shorter copy, and a tie, leave the row exactly as it stands.
        assertEquals(
            emptyList<TrackEntity>(),
            fullerMatchedTracks(listOf(standing), listOf(fromFile.copy(points = 40)), mapOf(99L to 3L)),
        )
    }

    /**
     * A template the import brought comes with the file's exercises, under the id it was given
     * here. One already here keeps its own: saving a template writes every row anew, so a bench
     * press changed from 3 × 10 to 4 × 8 no longer equals the file's, and an import of the older
     * file would put the old one back beside it at the same place in the session.
     */
    @Test
    fun onlyATemplateTheImportBroughtTakesTheFilesExercises() {
        val press = RoutineExercise(id = 4, routineId = 10, position = 0, activityCode = "BENCH", durationMinutes = 0.0)
        val row = press.copy(id = 5, routineId = 11, activityCode = "ROW")

        // Template 10 was matched to one already here; template 11 was inserted as 77.
        assertEquals(
            listOf(row.copy(id = 0, routineId = 77)),
            restoredRoutineExercises(listOf(press, row), inserted = mapOf(11L to 77L)),
        )
        assertEquals(emptyList<RoutineExercise>(), restoredRoutineExercises(listOf(press, row), emptyMap()))
    }

    @Test
    fun childKeysIgnoreInstallSpecificIdsAndParents() {
        val point = TrackPointEntity(
            id = 1,
            trackId = 10,
            timestamp = tapped,
            elapsedRealtimeNanos = 5,
            lat = 25.0,
            lon = 55.0,
            altitudeM = null,
            accuracyM = null,
            speedMps = null,
            provider = "gps",
            accepted = true,
        )
        assertEquals(trackPointKey(point), trackPointKey(point.copy(id = 99, trackId = 77)))
    }

    /**
     * A day's Health Connect total is one number per day, and the larger of two saw more of it —
     * the same rule the phone's own days merge under.
     */
    @Test
    fun theFullerHealthConnectTotalWinsPerDay() {
        val local = listOf(ExternalDaySteps("2026-08-10", 9_000), ExternalDaySteps("2026-08-11", 400))
        val backup = listOf(ExternalDaySteps("2026-08-10", 2_000), ExternalDaySteps("2026-08-12", 700))

        assertEquals(
            listOf(ExternalDaySteps("2026-08-10", 9_000), ExternalDaySteps("2026-08-12", 700)),
            mergeExternalDays(local, backup),
        )
    }

    /**
     * The same rule again, one figure per app and day. The shares are provenance rather than
     * arithmetic, so the merge cannot be the whole-row pick the direct BLE days use: two installs
     * that both saw a day keep the fuller answer for each app separately.
     */
    @Test
    fun theFullerShareWinsPerAppAndDay() {
        val local = listOf(
            ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-08-10", 9_000),
            ExternalOriginDaySteps("com.sec.android.app.shealth", "2026-08-10", 300),
        )
        val backup = listOf(
            ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-08-10", 2_000),
            ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-08-12", 700),
        )

        assertEquals(
            listOf(
                ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-08-10", 9_000),
                ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-08-12", 700),
            ),
            mergeExternalOriginDays(local, backup),
        )
    }

    /**
     * Every table is either written to the backup or deliberately left out, and the deliberate ones
     * are listed here with the reason. A table in neither list, `external_step_days` say, would be
     * dropped by every restore without a word, and with it whatever a watch had contributed to a day.
     */
    @Test
    fun everyTableIsEitherBackedUpOrDeliberatelyNot() {
        val entities = Regex("""entities = \[([\s\S]*?)]""")
            .find(File("src/main/java/com/pwnall/health/Data.kt").readText())
            ?.groupValues?.get(1)
            ?: error("no @Database entity list in Data.kt")
        val backup = File("src/main/java/com/pwnall/health/Backup.kt").readText()
        val missing = Regex("""(\w+)::class""").findAll(entities)
            .map { it.groupValues[1] }
            .filterNot { it in NOT_BACKED_UP }
            .filterNot { Regex("""\b$it\b""").containsMatchIn(backup) }
            .toList()

        assertEquals(emptyList<String>(), missing)
    }

    /**
     * A route still recording on the phone that exports must not be one still recording on the phone
     * that restores: an open row there is a recording the service resumes, location and all.
     */
    @Test
    fun anOpenTrackIsExportedClosed() {
        val open = TrackEntity(id = 7, startedAt = 1_000L)
        val closed = TrackEntity(id = 8, startedAt = 1_000L, endedAt = 2_000L)

        assertEquals(5_000L, open.toJson(exportedAt = 5_000L).getLong("endedAt"))
        assertEquals(2_000L, closed.toJson(exportedAt = 5_000L).getLong("endedAt"))
    }

    /**
     * `journal:<id>` names a row by the id this database gave it, and another phone's file names its
     * own rows: applied here, its `journal:15` would block the adoption of this install's fifteenth
     * row's Health Connect record, and the stale sweep would then withdraw the record — gone from both
     * stores.
     */
    @Test
    fun `row tombstones are believed only from a file this installation wrote`() {
        val rows = listOf(
            JournalTombstone(journalRecordKey(15), tapped),
            JournalTombstone("${HEALTH_IMPORT_MARKER_PREFIX}com.x:uuid", tapped),
        )
        val markers = rows.drop(1)

        assertEquals(rows, importableTombstones(rows, "install-a", "install-a"))
        assertEquals(markers, importableTombstones(rows, "install-b", "install-a"))
        // A file whose header carries no id: nothing says whose rows it names.
        assertEquals(markers, importableTombstones(rows, null, "install-a"))
    }

    /**
     * A deleted route is named by the moment its recording started, which is the same on every phone
     * — it is what the merge knows a route by — so its tombstone is believed from any file, as a
     * marker is. Left behind, the route would come back on the second phone from the first backup
     * written before the deletion.
     */
    @Test
    fun `a deleted route's tombstone is believed from another installation's file`() {
        val route = JournalTombstone(routeTombstoneKey(5_000), tapped)
        val rows = listOf(JournalTombstone(journalRecordKey(15), tapped), route)

        assertEquals(listOf(route), importableTombstones(rows, "install-b", "install-a"))
        assertEquals(listOf(route), importableTombstones(rows, null, "install-a"))
        // So is a row read out of an export file: its key states the file's own fact and no phone's id.
        val set = JournalTombstone("${FILE_IMPORT_MARKER_PREFIX}Hevy:2026-03-20T180000:Squat", tapped)
        assertEquals(listOf(set), importableTombstones(rows.take(1) + set, "install-b", "install-a"))
    }

    /**
     * A row is found through its day by every page there is. Inserted, one whose day is not a date
     * would be counted as restored and then stand where nothing shows it or can delete it.
     */
    @Test
    fun `a row whose day is not a date stays out of the restore`() {
        val kept = entry(1, tapped, "Kept")
        val torn = entry(3, tapped, "Torn").copy(day = "2026-13")
        val rows = listOf(kept, entry(2, tapped, "No day").copy(day = ""), torn)

        assertEquals(listOf(kept), withReadableDays(rows))
    }

    /**
     * A row this installation deleted on purpose is named by its id in the tombstones, and a file
     * this installation signed carries that id, so the row stays deleted; a row from elsewhere is
     * named by its marker. Another installation's 15 is whichever row was fifteenth there, so from
     * its file only the marker is believed.
     */
    @Test
    fun `a file of this install's own does not resurrect a row deleted here`() {
        val marker = "${HEALTH_IMPORT_MARKER_PREFIX}com.x:uuid"
        val deleted = entry(15, tapped, "Deleted")
        val kept = entry(16, tapped + 1, "Kept")
        val marked = entry(17, tapped + 2, "Marked").copy(healthConnectRecordKey = marker)
        val rows = listOf(deleted, kept, marked)
        val tombstoned = setOf(journalRecordKey(15), marker)

        assertEquals(listOf(kept), withoutTombstoned(rows, tombstoned, sameInstall = true))
        assertEquals(listOf(deleted, kept), withoutTombstoned(rows, tombstoned, sameInstall = false))
        assertEquals(rows, withoutTombstoned(rows, emptySet(), sameInstall = true))
    }

    /** A favourite corrected after the backup was written keeps the correction; the rest take the file's. */
    @Test
    fun `a favourite corrected since the backup is not put back`() {
        val corrected = FavoriteFood(key = "usda:1", title = "Молоко", calories = 60.0, createdAt = tapped + 1)
        val stale = corrected.copy(calories = 64.0, createdAt = tapped)
        val other = FavoriteFood(key = "usda:2", title = "Хлеб", calories = 250.0, createdAt = tapped)

        assertEquals(
            listOf(other),
            newerImportedRows(listOf(corrected), listOf(stale, other), FavoriteFood::key, FavoriteFood::createdAt),
        )
        // The file's copy is the later one, or the same moment: it replaces the row, as an upsert does.
        assertEquals(
            listOf(corrected),
            newerImportedRows(listOf(stale), listOf(corrected), FavoriteFood::key, FavoriteFood::createdAt),
        )
        assertEquals(
            listOf(stale),
            newerImportedRows(listOf(stale), listOf(stale), FavoriteFood::key, FavoriteFood::createdAt),
        )
    }

    @Test
    fun `a dish changed since the backup keeps its new recipe`() {
        val kept = SavedDish(key = "борщ", name = "Борщ", items = "1=200|2=50", createdAt = tapped + 1)
        val stale = kept.copy(items = "1=200", createdAt = tapped)

        assertEquals(
            emptyList<SavedDish>(),
            newerImportedRows(listOf(kept), listOf(stale), SavedDish::key, SavedDish::createdAt),
        )
    }

    /** The scans a course or a visit came home with go through the backup with the row. */
    @Test
    fun `a course and a visit keep their attachments through the file`() {
        val course = Medication(
            id = 3,
            name = "Metformin",
            startDay = "2026-01-01",
            attachments = "content://a\ncontent://b",
            createdAt = 5,
        )
        val visit = Appointment(id = 4, specialty = "GP", atMs = tapped, attachments = "content://c")

        assertEquals(course, course.toJson().toMedication())
        assertEquals(visit, visit.toJson().toAppointment())
    }

    /**
     * Every column of every backed-up table is written by its `toJson`. The table check above sees
     * tables and not columns: a column such as `attachments`, on the courses and the visits, left out
     * of both writers would restore a course with no scans.
     */
    @Test
    fun everyColumnOfABackedUpTableIsWritten() {
        val data = File("src/main/java/com/pwnall/health/Data.kt").readText()
        val backup = File("src/main/java/com/pwnall/health/Backup.kt").readText()
        val entities = Regex("""entities = \[([\s\S]*?)]""").find(data)?.groupValues?.get(1)
            ?: error("no @Database entity list in Data.kt")
        val missing = Regex("""(\w+)::class""").findAll(entities)
            .map { it.groupValues[1] }
            .filterNot { it in NOT_BACKED_UP || it in NOT_WRITTEN_AS_ROWS }
            .flatMap { entity ->
                val columns = Regex("""data class $entity\(([\s\S]*?)\n\)""").find(data)?.groupValues?.get(1)
                    ?: error("no data class $entity in Data.kt")
                val writer = Regex("""fun $entity\.toJson\([^)]*\)[\s\S]*?\n\n""").find(backup)?.value
                    ?: error("no $entity.toJson in Backup.kt")
                val keys = Regex("""\.put\("(\w+)"""").findAll(writer).map { it.groupValues[1] }.toSet()
                // A val or a var behind any annotations, `@ColumnInfo(defaultValue = "(0)")` among them,
                // and as many as the class has: a parameter the pattern did not read would drop out unseen.
                val names = PARAMETER.findAll(columns).map { it.groupValues[1] }.toList()
                assertEquals(entity, Regex("""\bva[lr] \w+\s*:""").findAll(columns).count(), names.size)
                names.map { "$entity.$it" }
                    .filterNot { it.substringAfter('.') in keys || it in NOT_A_COLUMN_OF_THE_FILE }
            }
            .toList()

        assertEquals(emptyList<String>(), missing)
    }

    /**
     * A file of schema 6 or earlier kept the Health Connect key in the note. The key is taken from
     * there and the note emptied as the migration to schema 26 emptied it — unless somebody typed
     * past the marker, which a package and a record id never do.
     */
    @Test
    fun `a legacy marker leaves the note as the migration left it`() {
        val marker = "${HEALTH_IMPORT_MARKER_PREFIX}com.x:uuid"
        val legacy = entry(1, tapped, "Вес").copy(note = marker)

        assertEquals(legacy.copy(healthConnectRecordKey = marker, note = ""), legacy.withKeyTakenFromNote())
        val typed = legacy.copy(note = "$marker and a word of my own")
        assertEquals(typed.copy(healthConnectRecordKey = typed.note), typed.withKeyTakenFromNote())
        // Schema 7 carries the key in a field of its own; whatever the note says then is the person's.
        val current = legacy.copy(healthConnectRecordKey = marker, note = "Health Connect: my remark")
        assertEquals(current, current.withKeyTakenFromNote())
        assertEquals(legacy.copy(note = "plain"), legacy.copy(note = "plain").withKeyTakenFromNote())
    }
}

/** One parameter of an entity's constructor: its annotations, parentheses within theirs included, then val or var. */
private val PARAMETER = Regex("""(?m)^\s*(?:@\w+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*va[lr] (\w+)\s*:""")

/**
 * The pedometer's own place in the hardware counter: a boot id, the last cumulative reading, the
 * bout being confirmed. It describes one device during one boot, and restoring another phone's copy
 * would rebase the accumulator against a counter that never produced it — a day of steps credited
 * or dropped at the first sample after the import. Nothing in it is a measurement: everything it
 * has ever produced is already in `days` and `hourly_steps`.
 */

private val NOT_BACKED_UP = setOf("StateRow")

/** Written as a bare list of days under `legacyStepDays`, not as a table of rows. */
private val NOT_WRITTEN_AS_ROWS = setOf("LegacyStepDay")

/**
 * Columns a writer leaves out on purpose: the profile's fixed id, which names the one row there is,
 * and a photograph's autoincrement id — a progress picture's or a walk's — which the file replaces
 * with the picture itself.
 */
private val NOT_A_COLUMN_OF_THE_FILE = setOf("UserProfile.id", "ProgressPhoto.id", "TrackPhotoEntity.id")
