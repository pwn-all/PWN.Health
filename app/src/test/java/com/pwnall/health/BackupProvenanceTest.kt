package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupProvenanceTest {
    private val days = listOf("2026-07-27", "2026-07-28")

    /** Schema 11 is what this app writes now; 12 is a newer app's file and refused before a row is read. */
    @Test
    fun everyBackupSchemaEverWrittenRemainsReadable() {
        assertEquals(
            listOf(false) + List(11) { true } + false,
            (0..12).map(::isSupportedBackupSchema),
        )
    }

    @Test
    fun schemaOneConservativelyMarksEveryImportedDay() {
        assertEquals(
            days.toSet(),
            legacyStepDaysForBackupImport(
                schema = 1,
                importedDays = days,
                encodedLegacyDays = emptySet(),
            ),
        )
    }

    @Test
    fun schemaTwoRestoresOnlyRecordedMarkers() {
        assertEquals(
            setOf(days.first()),
            legacyStepDaysForBackupImport(
                schema = 2,
                importedDays = days,
                encodedLegacyDays = setOf(days.first()),
            ),
        )
    }

    @Test
    fun schemaThreeKeepsPhoneProvenanceRulesUnchanged() {
        assertEquals(
            setOf(days.last()),
            legacyStepDaysForBackupImport(
                schema = 3,
                importedDays = days,
                encodedLegacyDays = setOf(days.last()),
            ),
        )
    }

    @Test
    fun schemaTwoWithoutMarkerListFailsClosed() {
        assertEquals(
            days.toSet(),
            legacyStepDaysForBackupImport(
                schema = 2,
                importedDays = days,
                encodedLegacyDays = null,
            ),
        )
    }

    @Test
    fun markerCannotSuppressAnUnrelatedExistingDay() {
        assertEquals(
            emptySet<String>(),
            legacyStepDaysForBackupImport(
                schema = 2,
                importedDays = days,
                encodedLegacyDays = setOf("2026-07-29"),
            ),
        )
    }
}
