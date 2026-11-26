package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * A record the app published itself is read back after a reinstall. If it kept its Health Connect
 * provenance the export would refuse to republish it and the stale sweep would then delete it, so
 * the restored copy has to come back as an ordinary local event.
 */
class HealthConnectAdoptionTest {
    private val marker = "Health Connect:com.pwnall.health:abc-123"

    /** The tag this journal's records carry; see journalGeneration. */
    private val journal = "a1b2c3d4"

    @Test
    fun ownMeasurementLosesItsHealthConnectProvenance() {
        val restored = JournalEntry(
            day = "2026-08-01",
            occurredAt = 1_754_000_000_000,
            kind = "MEASUREMENT",
            title = "Body measurement",
            calories = 0.0,
            measurementWeightKg = 81.4,
            measurementSource = "HEALTH_CONNECT",
            healthConnectRecordKey = marker,
        ).adopted()

        assertNull(restored.measurementSource)
        assertNull(restored.healthConnectRecordKey)
        assertEquals(81.4, restored.measurementWeightKg!!, 0.001)
    }

    @Test
    fun ownWorkoutBecomesLocalAgain() {
        val restored = JournalEntry(
            day = "2026-08-01",
            occurredAt = 1_754_000_000_000,
            kind = "ACTIVITY",
            title = "Synced workout",
            calories = 240.0,
            activityCode = "HEALTH_CONNECT",
            met = 5.0,
            durationMinutes = 45.0,
            healthConnectRecordKey = marker,
        ).adopted()

        assertNull(restored.activityCode)
        assertEquals(240.0, restored.calories, 0.001)
    }

    @Test
    fun aNoteWrittenByHandSurvives() {
        val restored = JournalEntry(
            day = "2026-08-01",
            occurredAt = 1_754_000_000_000,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            note = "slept badly",
            healthConnectRecordKey = marker,
        ).adopted()

        assertEquals("slept badly", restored.note)
    }

    // The "still local" test behind adoption: a client id names a journal row, but after a
    // reinstall Room numbers rows from 1 again, so the id alone proves nothing — the times decide.

    private val now: Instant = Instant.ofEpochMilli(1_754_100_000_000)

    private fun measurement(occurredAt: Long) = JournalEntry(
        day = "2026-08-01",
        occurredAt = occurredAt,
        kind = "MEASUREMENT",
        title = "Body measurement",
        calories = 0.0,
        measurementWeightKg = 81.4,
    )

    @Test
    fun aRowIsStillLocalWhenItsTimesAgree() {
        val local = measurement(1_754_000_000_000)
        val imported = measurement(1_754_000_000_000)

        assertTrue(publishedCopyOf(local, imported, now))
    }

    /** The previous install's weight row must not be mistaken for the new install's first entry. */
    @Test
    fun aReinstallIdCollisionIsAdoptedNotSkipped() {
        val newInstallFirstEntry = measurement(1_754_000_000_000)
        val previousInstallWeight = measurement(1_700_000_000_000)

        assertFalse(publishedCopyOf(newInstallFirstEntry, previousInstallWeight, now))
    }

    /**
     * A manual workout exports the interval ending at its timestamp and imports on that interval's
     * start — the same event, seen from its two ends.
     */
    @Test
    fun aManualWorkoutMatchesThroughItsInterval() {
        val local = JournalEntry(
            day = "2026-08-01",
            occurredAt = 1_754_000_000_000,
            kind = "ACTIVITY",
            title = "Rowing",
            calories = 240.0,
            met = 5.0,
            durationMinutes = 45.0,
        )
        val imported = local.copy(occurredAt = 1_754_000_000_000 - 45 * 60_000)

        assertTrue(publishedCopyOf(local, imported, now))
        // A different session's start does not pass for this one's.
        assertFalse(publishedCopyOf(local, local.copy(occurredAt = 1_753_000_000_000), now))
    }

    // A row another app owns stays that app's: a value corrected there is read into the copy this
    // journal already holds, instead of standing at whatever the first sync happened to see.

    private val foreignMarker = "Health Connect:com.other.scale:rec-9"

    private fun importedWater(millilitres: Double) = JournalEntry(
        day = "2026-08-01",
        occurredAt = 1_754_000_000_000,
        kind = KIND_WATER,
        title = "Water",
        calories = 0.0,
        servingG = millilitres,
        measurementSource = "HEALTH_CONNECT",
        healthConnectRecordKey = foreignMarker,
    )

    @Test
    fun aCorrectionInTheSourceAppRewritesTheRowItWasImportedInto() {
        val local = importedWater(200.0).copy(id = 42)

        val refreshed = local.refreshedFromImport(importedWater(330.0))!!

        assertEquals(42L, refreshed.id)
        assertEquals(330.0, refreshed.servingG, 0.001)
        assertEquals(foreignMarker, refreshed.healthConnectRecordKey)
    }

    @Test
    fun aSourceEditAcrossMidnightMovesTheExistingRow() {
        val local = importedWater(200.0).copy(id = 42)
        val moved = importedWater(330.0).copy(
            day = "2026-08-02",
            occurredAt = local.occurredAt + 86_400_000,
        )

        val refreshed = local.refreshedFromImport(moved)!!

        assertEquals(42L, refreshed.id)
        assertEquals("2026-08-02", refreshed.day)
        assertEquals(moved.occurredAt, refreshed.occurredAt)
    }

    @Test
    fun aRecordNobodyTouchedIsNotWrittenAgain() {
        val local = importedWater(200.0).copy(id = 42)

        assertNull(local.refreshedFromImport(importedWater(200.0)))
    }

    /** A figure the person corrected by hand is theirs; the source's next version does not undo it. */
    @Test
    fun aRowCorrectedByHandIsNotRewrittenByItsSource() {
        val corrected = importedWater(250.0).copy(id = 42, editedAt = 1_754_000_000_000)

        assertNull(corrected.refreshedFromImport(importedWater(330.0)))
    }

    private val importedSession = JournalEntry(
        id = 7,
        day = "2026-08-01",
        occurredAt = 1_754_000_000_000,
        kind = "ACTIVITY",
        title = "Synced workout",
        calories = 268.0,
        activityCode = "HEALTH_CONNECT",
        met = 5.0,
        durationMinutes = 45.0,
        healthConnectRecordKey = foreignMarker,
    )

    /**
     * A session's energy is recalculated from the weight in force at its time, which the import
     * knows nothing about: compared against the import's own guess every sync would rewrite the row
     * and the recalculation would put it straight back.
     */
    @Test
    fun anImportedSessionKeepsTheEnergyTheJournalRecalculated() {
        val reread = importedSession.copy(id = 0, calories = 301.0)

        assertNull(importedSession.refreshedFromImport(reread))
        // A session the source app has since corrected still comes through.
        val corrected = importedSession.refreshedFromImport(reread.copy(durationMinutes = 50.0))!!
        assertEquals(50.0, corrected.durationMinutes!!, 0.001)
        assertEquals(268.0, corrected.calories, 0.001)
    }

    // One imported heart rate per day, whoever wrote the sample that won the day.

    private val day: LocalDate = LocalDate.parse("2026-08-01")

    @Test
    fun aDayHoldsOneImportedHeartRateWhateverSourceWonIt() {
        assertEquals("Health Connect:heart:2026-08-01", dailyHeartRateMarker(day))
        assertTrue(isDailyHeartRateMarker(dailyHeartRateMarker(day), day))
        // The watch in the morning and its companion app in the evening are the same day's rate.
        assertTrue(isDailyHeartRateMarker("Health Connect:com.watch.companion:heart:2026-08-01", day))
        assertFalse(isDailyHeartRateMarker("Health Connect:com.watch:heart:2026-07-31", day))
        assertFalse(isDailyHeartRateMarker("Health Connect:com.watch:abc-123", day))
        assertFalse(isDailyHeartRateMarker("", day))
        assertFalse(isDailyHeartRateMarker(null, day))
    }

    @Test
    fun aLaterSyncRefreshesTheDaysHeartRate() {
        val standing = measurement(1_754_000_000_000).copy(
            id = 8,
            measurementHeartRateBpm = 60,
            healthConnectRecordKey = dailyHeartRateMarker(day),
        )

        val refreshed = refreshedDailyHeartRate(standing, standing.occurredAt + 60_000, 72)!!

        assertEquals(8L, refreshed.id)
        assertEquals(72, refreshed.measurementHeartRateBpm)
        assertNull(refreshedDailyHeartRate(refreshed, standing.occurredAt, 55))
    }

    @Test
    fun aCaffeineOnlyNutritionRecordReturnsToTheCaffeineJournal() {
        val imported = importedNutrition(
            caffeineGrams = 0.095,
            calories = null,
            proteinG = null,
            fatG = null,
            carbsG = null,
        )

        assertEquals(KIND_CAFFEINE, imported.kind)
        assertEquals(95.0, imported.caffeineMg, 0.001)
    }

    @Test
    fun `a meal that turns out to be a cup of coffee stops being a meal`() {
        // The record is filed as EAT, as a sync that read no caffeine-only records left it.
        val standing = importedWater(0.0).copy(id = 7, kind = "EAT", title = "Coffee")
        val cup = standing.copy(kind = KIND_CAFFEINE, servingG = 95.0)

        val refreshed = standing.refreshedFromImport(cup)!!

        assertEquals(7L, refreshed.id)
        assertEquals(KIND_CAFFEINE, refreshed.kind)
        assertEquals(95.0, refreshed.servingG, 0.001)
    }

    @Test
    fun caffeineOnARealMealDoesNotTurnTheMealIntoACup() {
        val imported = importedNutrition(0.04, 350.0, 12.0, 8.0, 44.0)

        assertEquals("EAT", imported.kind)
        // Not the serving: on a meal that column is the portion in grams.
        assertEquals(0.0, imported.caffeineMg, 0.0)
        // Kept all the same, beside the portion, so the day's caffeine counts the energy drink.
        assertEquals(40.0, imported.mealCaffeineMg!!, 0.001)
    }

    @Test
    fun `a meal that states no caffeine says so with nothing rather than with zero`() {
        val imported = importedNutrition(null, 350.0, 12.0, 8.0, 44.0)

        assertEquals("EAT", imported.kind)
        assertNull(imported.mealCaffeineMg)
    }

    @Test
    fun healthConnectProvenanceSurvivesEditingEveryJournalKind() {
        assertEquals("HEALTH_CONNECT", editedMeasurementSource("HEALTH_CONNECT", "EAT", "MANUAL"))
        assertEquals("HEALTH_CONNECT", editedMeasurementSource("HEALTH_CONNECT", KIND_WATER, "MANUAL"))
        assertNull(editedMeasurementSource("MANUAL", "EAT", "MANUAL"))
        assertEquals("MANUAL", editedMeasurementSource("PHONE_USAGE_ESTIMATE", "MEASUREMENT", "PHONE_SENSOR"))
        // What the measurement form shows is what it saves: provenance does not ride through its edit.
        assertEquals("MANUAL", editedMeasurementSource("HEALTH_CONNECT", "MEASUREMENT", "MANUAL"))
    }

    @Test
    fun healthWritesAreBoundedIntoStableBatches() {
        val batches = healthWriteBatches((1..1_201).toList())

        assertEquals(listOf(500, 500, 201), batches.map { it.size })
        assertEquals((1..1_201).toList(), batches.flatten())
    }

    // What a deleted row leaves behind. Health Connect still holds its copy, so the next sync has to
    // be able to name it — by the id every client record id of ours ends in, and by the marker a row
    // imported from another app was filed under.

    // The 25 -> 26 backfill, as SQL: the key is frozen from the note, and the note is only cleared
    // where nothing in it was typed by a person. A marker is a package and a record id, and neither
    // can hold a space.
    private fun backfilled(note: String): Pair<String?, String> {
        val key = note.takeIf { it.startsWith(HEALTH_IMPORT_MARKER_PREFIX) }
        // Past the prefix: "Health Connect:" carries a space itself, so asking about the whole
        // string matches nothing and the clause does nothing at all.
        val typedInto = key != null && ' ' in note.removePrefix(HEALTH_IMPORT_MARKER_PREFIX)
        return key to if (key != null && !typedInto) "" else note
    }

    @Test
    fun `the backfill frees the note only where none of it was typed`() {
        assertEquals(foreignMarker to "", backfilled(foreignMarker))
        // Somebody typed into an imported row. The key is still frozen from the note's marker, and
        // their words are not this migration's to throw away.
        val touched = "$foreignMarker и ещё заметка"
        assertEquals(touched to touched, backfilled(touched))
        // A row this app logged itself keeps its note and gains no key.
        assertEquals(null to "slept badly", backfilled("slept badly"))
    }

    @Test
    fun aDeletedRowIsRememberedByItsIdAndByTheMarkerItCameInBy() {
        assertEquals(listOf("journal:7", foreignMarker), journalTombstoneKeys(7, foreignMarker))
        // A row this app logged itself has no record key, so its id is all there is to remember.
        assertEquals(listOf("journal:7"), journalTombstoneKeys(7, null))
    }

    @Test
    fun aRecordWePublishedIsMatchedToTheRowItWasWrittenFor() {
        // Every record type this row was published as names the same tombstone.
        assertEquals("journal:7", journalRecordKey(publishedRowId("pwnfit-journal-weight:$journal:7", journal)!!))
        assertEquals("journal:7", journalRecordKey(publishedRowId("pwnfit-journal-sleep:$journal:7", journal)!!))
        // Another app's record id is not ours to read a journal id out of.
        assertNull(publishedRowId("rec-9", journal))
        assertNull(publishedRowId(null, journal))
    }

    /**
     * The period's record is keyed on its start day and carries no row id, so an id lookup finds
     * nothing and a deleted start or end would come back on every sync. Each of its two rows is found by
     * its own day — the end by the end day, not by the start the record is named after.
     */
    @Test
    fun aPeriodRecordIsMatchedToTheDayOfWhicheverRowItIsStandingFor() {
        val record = "pwnfit-journal-period:2026-08-01"
        val weighIn = "pwnfit-journal-weight:$journal:7"

        assertEquals("period:2026-08-01", publishedRecordTombstoneKey(record, journal, KIND_CYCLE, "2026-08-01"))
        assertEquals(
            "period-end:2026-08-05",
            publishedRecordTombstoneKey(record, journal, KIND_CYCLE_END, "2026-08-05"),
        )
        // An id-keyed record still goes by its id, whatever the row's kind.
        assertEquals("journal:7", publishedRecordTombstoneKey(weighIn, journal, "MEASUREMENT", "2026-08-01"))
        // Another app's record id names no grave here.
        assertNull(publishedRecordTombstoneKey("rec-9", journal, "MEASUREMENT", "2026-08-01"))
    }
}
