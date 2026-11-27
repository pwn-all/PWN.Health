package com.pwnall.health

/** Every schema this app has ever written, 11 included; a higher number is a newer app's file. */
internal fun isSupportedBackupSchema(schema: Int): Boolean = schema in 1..11

/**
 * Restores step provenance without guessing. Schema 1 never recorded it, so every imported day is
 * legacy. Schemas 2 and later preserve their explicit subset; a missing list is treated like schema 1
 * because accidentally re-exporting wearable totals is worse than suppressing a local day.
 */
internal fun legacyStepDaysForBackupImport(
    schema: Int,
    importedDays: Collection<String>,
    encodedLegacyDays: Collection<String>?,
): Set<String> {
    val imported = importedDays.toSet()
    if (schema == 1 || encodedLegacyDays == null) return imported
    return encodedLegacyDays.filterTo(mutableSetOf()) { it in imported }
}
