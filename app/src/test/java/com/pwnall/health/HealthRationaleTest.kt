package com.pwnall.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rationale screen is the app's answer to Play's "why does this app want each of these", and
 * Play reads a type the table does not mention as a grant with no reason. The manifest, the request
 * and the table are three lists of the same thing; [HealthPermissionManifestTest] ties the first
 * two together and this ties the third to them.
 */
class HealthRationaleTest {
    private val requested = (HEALTH_RECORD_TYPES + HEALTH_WRITE_ONLY_TYPES + HEALTH_READ_ONLY_TYPES).toSet()
    private val explained = HEALTH_RATIONALE_ROWS.flatMap { it.types }

    @Test
    fun everyRequestedTypeHasAReasonAndNothingElseIsExplained() {
        assertEquals(requested, explained.toSet())
    }

    @Test
    fun noTypeIsExplainedTwice() {
        val repeated = explained.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<Any>(), repeated)
    }

    @Test
    fun everyExplainedTypeHasAName() {
        val nameless = explained.filterNot { it in HEALTH_RECORD_TYPE_NAMES }
        assertEquals(emptyList<Any>(), nameless)
    }

    @Test
    fun theHistoryPermissionIsTheOneRowWithoutTypes() {
        val typeless = HEALTH_RATIONALE_ROWS.filter { it.types.isEmpty() }
        assertEquals(1, typeless.size)
        assertNotNull(typeless.single().title)
        assertTrue(HEALTH_RATIONALE_ROWS.filter { it.types.isNotEmpty() }.all { it.title == null })
    }

    /**
     * Another app's distance, climb and power are not read: no screen shows them, so asking to read
     * them would be a grant with no feature behind it. Write-only, their read
     * permissions stay out of the request ([theRequestIsReadAndWriteForEveryTypeOneWayForTheRestAndTheHistory]).
     */
    @Test
    fun aWorkoutsDistanceClimbAndPowerAreWrittenAndNeverRead() {
        assertTrue(
            HEALTH_WRITE_ONLY_TYPES.containsAll(
                listOf(DistanceRecord::class, ElevationGainedRecord::class, PowerRecord::class),
            ),
        )
    }

    @Test
    fun theWriteOnlyTypesShareTheRowThatSaysWhyTheyAreNotRead() {
        val rows = HEALTH_RATIONALE_ROWS.filter { row ->
            TotalCaloriesBurnedRecord::class in row.types || SpeedRecord::class in row.types
        }
        assertEquals(1, rows.size)
        assertEquals(R.string.health_rationale_purpose_derived, rows.single().purpose)
    }

    /**
     * The request is built from the lists of types, read and write, write-only and read-only. Not a
     * count of types times two: the period and the flow share one MENSTRUATION permission pair.
     */
    @Test
    fun theRequestIsReadAndWriteForEveryTypeOneWayForTheRestAndTheHistory() {
        val derived = HEALTH_RECORD_TYPES.flatMap {
            listOf(HealthPermission.getReadPermission(it), HealthPermission.getWritePermission(it))
        } + HEALTH_WRITE_ONLY_TYPES.map { HealthPermission.getWritePermission(it) } +
            HEALTH_READ_ONLY_TYPES.map { HealthPermission.getReadPermission(it) } +
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        assertEquals(derived.toSet(), HEALTH_PERMISSIONS)
        HEALTH_WRITE_ONLY_TYPES.forEach {
            assertFalse(HealthPermission.getReadPermission(it) in HEALTH_PERMISSIONS)
        }
        // A resting rate and a nightly variability are a watch's own derivations; nothing here
        // computes either, so asking to write them would be a grant with no feature behind it.
        HEALTH_READ_ONLY_TYPES.forEach {
            assertFalse(HealthPermission.getWritePermission(it) in HEALTH_PERMISSIONS)
        }
    }

    /**
     * Onboarding asks for the whole exchange, so what it leaves out is a promise made in words
     * in the privacy policy and on the Play form. It leaves out sexual activity for anyone the app
     * will not show that screen to — and nothing else: the cycle is offered at every age.
     */
    @Test
    fun theUnderageRequestDropsSexualActivityAndNothingElse() {
        val trimmed = withoutIntimateTypes(HEALTH_PERMISSIONS)
        val dropped = setOf(
            HealthPermission.getReadPermission(SexualActivityRecord::class),
            HealthPermission.getWritePermission(SexualActivityRecord::class),
        )
        assertEquals(dropped, HEALTH_PERMISSIONS - trimmed)
        assertTrue(HealthPermission.getReadPermission(MenstruationPeriodRecord::class) in trimmed)
    }

    @Test
    fun theStatusLineCountsWhatCrossedAndNotTheDaysAnotherAppCovered() {
        val result = HealthSyncResult(
            weightKg = 81.0,
            exportedJournalRecords = 5,
            importedHealthRecords = 10,
            importedWorkouts = 4,
            exportedStepDays = 5,
            externallyCoveredStepDays = 7,
            importedStepDays = 1,
        )
        assertEquals(15, result.importedCount)
        assertEquals(10, result.exportedCount)
    }
}
