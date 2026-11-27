package com.pwnall.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The rules that decide what a reinstall may throw away.
 *
 * A fresh install meets a Health Connect that still holds years of this app's own records against a
 * journal that holds nothing, and the export ends by withdrawing every record no local event claims.
 * Read literally that is the whole history, deleted from the only store still holding it.
 */
class HealthConnectRestoreTest {
    private val adoptedAt: Instant = Instant.ofEpochMilli(1_754_000_000_000)

    /** Weight, sleep, meals: the journal knows what it deleted, so the sweep may act on it. */
    @Test
    fun aTypeTheSyncReadsBackIsWithdrawnHoweverOldItIs() {
        val published = adoptedAt.minusMillis(400L * 24 * 3_600_000)

        assertTrue(withdrawablePublishedRecord(restoredType = true, published, adoptedAt))
    }

    /**
     * Periods, mindfulness, body composition: nothing imports them, so an empty journal is this
     * install never having seen them rather than the person having deleted them.
     */
    @Test
    fun aTypeNothingReadsBackKeepsWhatThePreviousInstallPublished() {
        val published = adoptedAt.minusMillis(1)

        assertFalse(withdrawablePublishedRecord(restoredType = false, published, adoptedAt))
    }

    /**
     * A route or a plan restored from a backup is published again under this journal's tag, over the
     * very stretch of time the earlier journal's record covers. That record is then not the only
     * copy of anything: left standing, it would have every app that adds distances up count the walk
     * twice.
     */
    @Test
    fun aTypeNothingReadsBackGivesUpTheTwinOfARecordPublishedAgain() {
        val published = adoptedAt.minusMillis(1)

        assertTrue(withdrawablePublishedRecord(restoredType = false, published, adoptedAt, republished = true))
        val walk = 1_000L..2_000L
        val earlier = SweptRecord("old", "$route:a1:1", published, span = walk)
        val again = SweptRecord("new", "$route:b2:1", adoptedAt.plusMillis(1), span = walk)
        val elsewhere = SweptRecord("other", "$route:a1:2", published, span = 3_000L..4_000L)

        assertEquals(
            listOf("old"),
            staleRecordIds(
                listOf(earlier, again, elsewhere),
                emptyList(),
                restoredType = false,
                adoptedAt,
                route,
                desiredIds = setOf("$route:b2:1"),
            ),
        )
    }

    // The sweep's whole choice, a case at a time: what an empty spot in the journal may withdraw.

    private val route = "pwnfit-route"
    private val weight = "pwnfit-journal-weight:"
    private val yearAgo: Instant = adoptedAt.minusMillis(400L * 24 * 3_600_000)

    private fun stale(
        within: List<SweptRecord>,
        beyond: List<SweptRecord> = emptyList(),
        restoredType: Boolean = true,
        adopted: Instant = adoptedAt,
        desired: Set<String> = emptySet(),
    ) = staleRecordIds(within, beyond, restoredType, adopted, weight, desired)

    /** A row still standing keeps its record, and a record of another family or app is never ours to name. */
    @Test
    fun theSweepWithdrawsOnlyUnclaimedRecordsOfItsOwnFamily() {
        val kept = SweptRecord("1", "${weight}a1:1", yearAgo)
        val deleted = SweptRecord("2", "${weight}a1:2", yearAgo)
        val height = SweptRecord("3", "pwnfit-journal-height:a1:3", yearAgo)
        val foreign = SweptRecord("4", null, yearAgo)

        assertEquals(listOf("2"), stale(listOf(kept, deleted, height, foreign), desired = setOf("${weight}a1:1")))
    }

    /**
     * Beyond the year the restore reads, a previous install's record stays — the journal never saw it —
     * and this install's own goes, for its row was here and is gone. Two records on one instant are
     * two records: each is judged by its own id.
     */
    @Test
    fun beyondTheRestoresReachOnlyWhatThisInstallWroteIsWithdrawn() {
        val previousInstall = SweptRecord("old", "${weight}a1:1", adoptedAt.minusMillis(1))
        val thisInstall = SweptRecord("mine", "${weight}b2:1", adoptedAt.plusMillis(1))
        val sameMoment = SweptRecord("mine-too", "${weight}b2:2", adoptedAt.plusMillis(1))

        assertEquals(
            listOf("mine", "mine-too"),
            stale(emptyList(), beyond = listOf(previousInstall, thisInstall, sameMoment)),
        )
        // No adoption instant on record: nothing that old can be told apart, so nothing goes.
        assertTrue(stale(emptyList(), beyond = listOf(thisInstall), adopted = Instant.EPOCH).isEmpty())
    }

    /** A type nothing imports keeps the previous install's history at any age; a record on the edge goes once. */
    @Test
    fun aTypeNothingReadsBackKeepsHistoryAndARecordReadTwiceIsNamedOnce() {
        val history = SweptRecord("old", "${weight}a1:1", adoptedAt.minusMillis(1))
        val mine = SweptRecord("mine", "${weight}b2:1", adoptedAt)

        assertEquals(listOf("mine"), stale(listOf(history, mine), restoredType = false))
        assertEquals(listOf("mine"), stale(listOf(mine), beyond = listOf(mine)))
    }

    @Test
    fun aTypeNothingReadsBackStillWithdrawsWhatThisInstallWroteItself() {
        val published = adoptedAt.plusMillis(1)

        assertTrue(withdrawablePublishedRecord(restoredType = false, published, adoptedAt))
        assertTrue(withdrawablePublishedRecord(restoredType = false, adoptedAt, adoptedAt))
    }

    /** An install that had already synced before the adoption instant was recorded keeps its sweeps. */
    @Test
    fun anInstallWithoutARecordedAdoptionSweepsEverythingAsBefore() {
        val published = Instant.ofEpochMilli(1_700_000_000_000)

        assertTrue(withdrawablePublishedRecord(restoredType = false, published, Instant.EPOCH))
    }

    // And the same instant decides what may come back in, so that a deletion stays deleted.

    /**
     * A row this install wrote itself was standing in the journal when it was written. Nothing
     * claiming it now is a deletion made while Health Connect was unreachable — reading it back
     * would undo it, and the sweep in the same sync withdraws the record instead.
     */
    @Test
    fun aRecordThisInstallPublishedIsNotReadBackIntoTheJournal() {
        assertFalse(adoptablePublishedRecord(adoptedAt.plusMillis(1), adoptedAt))
        assertFalse(adoptablePublishedRecord(adoptedAt, adoptedAt))
    }

    @Test
    fun whatAnEarlierInstallLeftBehindIsStillRestored() {
        assertTrue(adoptablePublishedRecord(adoptedAt.minusMillis(1), adoptedAt))
    }

    /** The sync that restores a reinstall runs before there is an adoption instant to compare to. */
    @Test
    fun aFirstSyncRestoresEverythingItFinds() {
        val published = Instant.ofEpochMilli(1_700_000_000_000)

        assertTrue(adoptablePublishedRecord(published, Instant.EPOCH))
    }

    // Older than the restore reaches back, the journal's silence is not a deletion — unless this
    // install wrote the record, and so once held the row.

    /**
     * A weigh-in or an act deleted more than a year after its date is withdrawn too: a sweep of the
     * last year only would leave it in Health Connect for good, and nothing else withdraws a record.
     */
    @Test
    fun aRecordOlderThanTheRestoreReachesIsWithdrawnWhenThisInstallWroteIt() {
        assertTrue(withdrawableBeyondRestore(adoptedAt.plusMillis(1), adoptedAt))
        assertTrue(withdrawableBeyondRestore(adoptedAt, adoptedAt))
    }

    /** What an earlier install left that far back is a year this journal has never seen. */
    @Test
    fun aRecordOlderThanTheRestoreReachesStaysWhenAnEarlierInstallWroteIt() {
        assertFalse(withdrawableBeyondRestore(adoptedAt.minusMillis(1), adoptedAt))
    }

    /** With no adoption instant on record there is no telling whose a record is, so none that old goes. */
    @Test
    fun anInstallWithoutARecordedAdoptionWithdrawsNothingOlderThanTheRestoreReaches() {
        assertFalse(withdrawableBeyondRestore(Instant.ofEpochMilli(1_700_000_000_000), Instant.EPOCH))
    }

    /** Whether the sweep reads that far back only a phone shows, so the second pass is pinned in the source. */
    @Test
    fun theSweepOfARestoredTypeLooksBeyondTheRestoresReachUnderItsOwnRule() {
        val sweep = File("src/main/java/com/pwnall/health/HealthSync.kt").readText()
            .substringAfter("> removeStalePwnFitRecords(").substringBefore("\n}\n")

        assertTrue(sweep, "ownRecordsIn(ownPackage, recordType, beyondRestoreRange(now))" in sweep)
        assertTrue(sweep, "beyondReach = beyondReach.map(swept)" in sweep)
    }

    // The cycle's rows of an earlier day all stand on that day's midday, under one kind.

    private val midday = 1_753_956_000_000

    private fun cycleRow(log: CycleLog) = JournalEntry(
        day = "2026-07-31",
        occurredAt = midday,
        kind = KIND_CYCLE_LOG,
        title = log.name,
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    /**
     * A flow, a mucus reading and an act filed the day after share a moment and a kind. The flow
     * comes back first; the others must not find "their" instant taken and be dropped as its copies,
     * or the second exchange would withdraw their records from the only store that still held them.
     */
    @Test
    fun aCycleRowStandsOnlyForTheRecordThatSaysWhatItSays() {
        val restoredFlow = cycleRow(CycleLog.FLOW_MEDIUM)

        assertFalse(restoredFlow.standsFor(cycleRow(CycleLog.MUCUS_CREAMY)))
        assertFalse(restoredFlow.standsFor(cycleRow(CycleLog.SEX_PROTECTED)))
        // The same flow coming back is the row's own copy, and is not taken in a second time.
        assertTrue(restoredFlow.standsFor(cycleRow(CycleLog.FLOW_MEDIUM)))
    }

    /** Everything else keeps the general rule: the row on the instant is the event, and a body value joins it. */
    @Test
    fun anyOtherRowOnTheInstantIsStillTheRecordsOwnEvent() {
        val restoredWeight = JournalEntry(
            day = "2026-07-31",
            occurredAt = midday,
            kind = "MEASUREMENT",
            title = "Body measurement",
            calories = 0.0,
            measurementWeightKg = 81.4,
        )
        val height = restoredWeight.copy(measurementWeightKg = null, measurementHeightCm = 181)

        assertTrue(restoredWeight.standsFor(height))
        // A cycle record whose client id happens to name this row after a reinstall is not its copy.
        assertFalse(restoredWeight.standsFor(cycleRow(CycleLog.FLOW_MEDIUM)))
    }

    // A database that starts over inside the same install has to meet these rules as a reinstall does.

    /** The text of function [name] in [file], from its name to the brace that closes it at the margin. */
    private fun function(file: String, name: String): String {
        val text = File("src/main/java/com/pwnall/health/$file").readText()
        assertTrue("fun $name( in $file", "fun $name(" in text)
        return text.substringAfter("fun $name(").substringBefore("\n}\n")
    }

    /**
     * A quarantined or set-aside database leaves the sync preferences behind, and what they say of
     * the old journal — restored, adopted then, read up to these tokens — would have the next exchange
     * withdraw from Health Connect every record the empty journal no longer claims. Whether the
     * preferences go only a phone shows, so the wiring is pinned in the source: the one call every
     * such start makes empties them, and an emptied adoption instant is the first sync's above.
     */
    @Test
    fun aDatabaseStartingOverForgetsWhatTheExchangeKnew() {
        val startingOver = function("Backup.kt", "forgetInstallId")
        assertTrue(startingOver, "forgetHealthSyncState(context)" in startingOver)
        val forgetting = function("HealthSync.kt", "forgetHealthSyncState")
        assertTrue(forgetting, "syncPrefs(context).edit(commit = true) { clear() }" in forgetting)
    }

    /**
     * The other end of the same wiring: every door through which a database starts over — set aside
     * because no key here opens it, or quarantined as corrupt, by Room's callback or by the open that
     * met a file that is no database — goes through that one call. A door that skipped it would leave
     * the exchange reading the empty journal as every row deleted.
     */
    @Test
    fun everyDatabaseSetAsideForgetsWhatTheExchangeKnew() {
        val encryption = File("src/main/java/com/pwnall/health/DatabaseEncryption.kt").readText()
        val lockAside = encryption.substringAfter("fun lockAside(", "").substringBefore("\n    }\n")
        assertTrue(lockAside, "forgetInstallId(context)" in lockAside)

        // What follows each quarantine of the file, up to the statement after its comment.
        val quarantines = listOf("Data.kt", "DatabaseEncryption.kt").flatMap { file ->
            val text = File("src/main/java/com/pwnall/health/$file").readText()
            Regex("""quarantineDatabaseFiles\(path\)""").findAll(text)
                .map { text.substring(it.range.first).take(400) }
                .toList()
        }
        assertEquals(2, quarantines.size)
        assertEquals(emptyList<String>(), quarantines.filterNot { "forgetInstallId(configuration.context)" in it })
    }

    // The other half of a reinstall: what comes back has to come back in the journal's own terms.

    private val now: Instant = Instant.ofEpochMilli(1_754_100_000_000)
    private val sessionStart: Instant = Instant.ofEpochMilli(1_754_000_000_000)
    private val sessionEnd: Instant = sessionStart.plusMillis(45 * 60_000)

    private fun adoptedWorkout(loggedAt: Instant) = JournalEntry(
        day = "2026-08-01",
        occurredAt = loggedAt.toEpochMilli(),
        kind = "ACTIVITY",
        title = "Rowing",
        calories = 240.0,
        activityCode = "HEALTH_CONNECT",
        met = 5.0,
        durationMinutes = 45.0,
        healthConnectRecordKey = "Health Connect:com.pwnall.health:abc-123",
    ).adopted()

    /**
     * The export runs a manual workout backwards from its timestamp, so a session of ours filed on
     * its start would be republished one duration earlier — and one duration earlier again at the
     * next reinstall.
     */
    @Test
    fun ourOwnWorkoutComesBackWhereTheExportWillPutItAgain() {
        val loggedAt = importedSessionInstant(sessionStart, sessionEnd, ownRecord = true)
        val interval = adoptedWorkout(loggedAt).sessionInterval(now)

        assertEquals(sessionStart, interval?.first)
        assertEquals(sessionEnd, interval?.second)
    }

    /** A foreign session is never published back and stays on the instant it was recorded on. */
    @Test
    fun aForeignWorkoutKeepsTheStartItWasRecordedOn() {
        assertEquals(sessionStart, importedSessionInstant(sessionStart, sessionEnd, ownRecord = false))
    }

    /**
     * A live Bluetooth workout is written down the moment it starts and exported forwards from
     * there, so its own copy comes home at the far end of the interval — and the row that
     * published it is still sitting in the journal at the near end. Read as a stranger, that copy
     * would be imported a second time and the workout would appear twice.
     */
    @Test
    fun aLiveWorkoutStillStandingIsRecognisedWhenItsCopyComesBackAtTheEnd() {
        val live = JournalEntry(
            day = "2026-08-01",
            occurredAt = sessionStart.toEpochMilli(),
            kind = "ACTIVITY",
            title = "Rowing",
            calories = 240.0,
            activityCode = "BLUETOOTH_FTMS",
            met = 5.0,
            durationMinutes = 45.0,
        )
        val imported = live.copy(
            occurredAt = importedSessionInstant(sessionStart, sessionEnd, ownRecord = true).toEpochMilli(),
        )

        assertTrue(publishedCopyOf(live, imported, now))
        // A session that ended before this one started is still a different session.
        assertFalse(publishedCopyOf(live, live.copy(occurredAt = sessionStart.toEpochMilli() - 1), now))
    }

    // And a measurement that left as three records has to come home as one row.

    private val bodyAt = 1_754_010_000_000

    @Test
    fun aScaleMeasurementNamesEveryValueItHolds() {
        val scale = JournalEntry(
            day = "2026-08-01",
            occurredAt = bodyAt,
            kind = "MEASUREMENT",
            title = "Body measurement",
            calories = 0.0,
            measurementWeightKg = 81.4,
            measurementHeightCm = 181,
            bodyFatPercent = 22.5,
        )

        // The resting rate rides along with the weight: the export derives its record from it, so
        // a weight-carrying row accounts for a returning BasalMetabolicRateRecord too — merging
        // the derived figure back onto it would only freeze it against profile corrections.
        assertEquals(
            setOf("weight", "height", "bodyFat", "basalMetabolismKj"),
            scale.bodyValueColumns(),
        )
        assertEquals(
            setOf("weight", "bodyFat", "basalMetabolismKj"),
            scale.copy(measurementHeightCm = null).bodyValueColumns(),
        )
        assertEquals(
            setOf("bodyFat"),
            scale.copy(measurementWeightKg = null, measurementHeightCm = null).bodyValueColumns(),
        )
    }

    /**
     * Weight is restored first and takes the instant; without the merge the height and body-fat
     * records that follow are dropped as "that event's own copy" and then swept out of Health
     * Connect for being unclaimed, which is the last copy of them there was.
     */
    @Test
    fun theValuesRestoredAfterTheFirstJoinTheRowAlreadyStandingOnTheInstant() {
        val restoredWeight = JournalEntry(
            day = "2026-08-01",
            occurredAt = bodyAt,
            kind = "MEASUREMENT",
            title = "Body measurement",
            calories = 0.0,
            measurementWeightKg = 81.4,
        )
        val restoredHeight = restoredWeight.copy(measurementWeightKg = null, measurementHeightCm = 181)
        val restoredBodyFat = restoredWeight.copy(measurementWeightKg = null, bodyFatPercent = 22.5)

        val merged = restoredWeight
            .withBodyValuesFrom(restoredHeight)
            .withBodyValuesFrom(restoredBodyFat)

        assertEquals(81.4, merged.measurementWeightKg!!, 0.001)
        assertEquals(181, merged.measurementHeightCm)
        assertEquals(22.5, merged.bodyFatPercent!!, 0.001)
    }

    /** A value the row already holds is not overwritten by the copy that comes back. */
    @Test
    fun aValueAlreadyInTheRowSurvivesTheMerge() {
        val local = JournalEntry(
            day = "2026-08-01",
            occurredAt = bodyAt,
            kind = "MEASUREMENT",
            title = "Body measurement",
            calories = 0.0,
            measurementWeightKg = 81.4,
        )
        val stale = local.copy(measurementWeightKg = 74.0, measurementHeightCm = 181)

        val merged = local.withBodyValuesFrom(stale)

        assertEquals(81.4, merged.measurementWeightKg!!, 0.001)
        assertEquals(181, merged.measurementHeightCm)
        assertNull(local.bodyFatPercent)
    }

    // A workout of ours has to come back as the row it left from, or the next export degrades it:
    // the same client record id, upserted as a workout of no kind.

    /** Every row publishes as some type, and that type has to come back as a row publishing the same. */
    @Test
    fun everyCatalogueRowComesHomeAsTheTypeItLeftAs() {
        ACTIVITY_CATALOG.forEach { spec ->
            val published = healthConnectExerciseType(spec.code)

            assertEquals(spec.code, published, healthConnectExerciseType(catalogueActivityCode(published)))
        }
    }

    @Test
    fun ourOwnSessionComesHomeAsItsRowAtThatRowsMet() {
        val run = ACTIVITY_CATALOG.first { it.code == "RUN" }

        val filedAs = importedSessionKind(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ownRecord = true)

        assertEquals(ImportedSessionKind("RUN", run.met), filedAs)
        // Adoption strips the import's own labels and has to leave a catalogue row standing.
        val restored = JournalEntry(
            day = "2026-08-01",
            occurredAt = sessionEnd.toEpochMilli(),
            kind = "ACTIVITY",
            title = "Run",
            calories = 300.0,
            activityCode = filedAs.activityCode,
            met = filedAs.met,
            durationMinutes = 45.0,
            healthConnectRecordKey = "Health Connect:com.pwnall.health:abc-123",
        ).adopted()
        assertEquals("RUN", restored.activityCode)
        assertNull(restored.healthConnectRecordKey)
    }

    /** A session of ours published as OTHER_WORKOUT has no row to go back to. */
    @Test
    fun aSessionOfNoKindKeepsTheImportsOwnLabel() {
        val filedAs = importedSessionKind(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, ownRecord = true)

        assertEquals(ImportedSessionKind("HEALTH_CONNECT", 5.0), filedAs)
    }

    /** Another app's session is not ours to re-cost; a step-typed one says so, so the pedometer's claim competes. */
    @Test
    fun aForeignSessionIsFiledUnderTheImportsLabels() {
        assertEquals(
            ImportedSessionKind(HEALTH_CONNECT_STEPS_CODE, 5.0),
            importedSessionKind(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ownRecord = false),
        )
        assertEquals(
            ImportedSessionKind("HEALTH_CONNECT", 5.0),
            importedSessionKind(ExerciseSessionRecord.EXERCISE_TYPE_BOXING, ownRecord = false),
        )
    }

    // A temperature leaves with the site it was taken at, or the reader grades an armpit as a core.

    /** Each site the thermometer can name, out to Health Connect and back in through the import. */
    @Test
    fun everyThermometerSiteComesHomeWhereTheReaderExpectsIt() {
        val expected = mapOf(
            TemperatureType.ARMPIT to "ARMPIT",
            TemperatureType.BODY to null,
            TemperatureType.EAR to "EAR",
            TemperatureType.FINGER to "FINGER",
            TemperatureType.GASTROINTESTINAL_TRACT to null,
            TemperatureType.MOUTH to "MOUTH",
            TemperatureType.RECTUM to "RECTUM",
            TemperatureType.TOE to "TOE",
            // The one ear Health Connect has; the reader withholds its verdict there rather than misplace it.
            TemperatureType.TYMPANUM to "EAR",
        )
        assertEquals(TemperatureType.entries.toSet(), expected.keys)

        TemperatureType.entries.forEach { site ->
            val restored = JSONObject().putTemperatureSite(healthConnectTemperatureLocation(site.name))

            assertEquals(site.name, expected.getValue(site), restored.optString("type").ifEmpty { null })
        }
    }

    /** Each site Health Connect names, into the payload and back out to the same constant. */
    @Test
    fun everySiteHealthConnectNamesSurvivesTheJournal() {
        val sites = listOf(
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TOE,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_VAGINA,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST,
        )
        sites.forEach { location ->
            val name = JSONObject().putTemperatureSite(location).getString("type")

            assertEquals(name, location, healthConnectTemperatureLocation(name))
        }
        // No site is written as nothing, and nothing is read back as no site.
        val unknown = BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_UNKNOWN
        assertFalse(JSONObject().putTemperatureSite(unknown).has("type"))
        assertEquals(unknown, healthConnectTemperatureLocation(null))
    }

    /** docs/DESIGN.md §3.1: a provider too old for this client is offered the update, not declared missing. */
    @Test
    fun aHealthConnectTooOldForThisClientIsAskedToUpdate() {
        assertEquals(
            R.string.sync_update_required,
            syncUnavailableText(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED),
        )
        assertEquals(R.string.sync_unavailable, syncUnavailableText(HealthConnectClient.SDK_UNAVAILABLE))
    }
}
