package com.pwnall.health

import java.time.LocalDate

data class StepDayMerge(
    /** Deduplicated Health Connect totals to persist separately from the phone accumulator. */
    val external: Map<LocalDate, Long>,
    /** Days where another source counted more steps than this phone did; value is that total. */
    val imports: Map<LocalDate, Long>,
    /** Days only this phone knows about, so they are worth publishing. */
    val exports: List<LocalDate>,
    /** Days another source counts at least as fully; anything this app published there is withdrawn. */
    val covered: List<LocalDate>,
)

/**
 * Decides, per day, which direction step data has to move.
 *
 * A watch worn all day counts more than a phone left on a desk, so the higher count wins and is
 * imported. Days nobody else reported are exported. Days someone else reported are left to them.
 *
 * [externalTotals] must already be deduplicated by Health Connect's aggregate API. They are kept
 * separate from [local]: the effective total is the greater value, never their sum.
 *
 * A day counts as covered only when the other source counted at least as much. Mere presence would
 * be a way to lose a day: any app that wrote twelve steps this morning would make the day somebody
 * else's, so this phone's four and a half thousand would be neither published nor kept published —
 * the withdrawal deleting the record that already held them. Two installs of this same app on one
 * phone, which is what a package rename leaves behind, could hold each other at zero that way.
 */
fun mergeStepDays(
    local: Map<LocalDate, Long>,
    externalTotals: Map<LocalDate, Long>,
): StepDayMerge {
    val external = externalTotals.filterValues { it > 0L }
    val covered = external.filter { (day, count) -> count >= (local[day] ?: 0L) }.keys.sorted()
    return StepDayMerge(
        external = external,
        imports = external.filter { (day, count) -> count > (local[day] ?: 0L) },
        exports = local.filter { (day, steps) -> steps > 0L && day !in covered }.keys.sorted(),
        covered = covered,
    )
}

/** Resolves the display total without ever adding overlapping phone and wearable sources. */
fun effectiveStepTotal(
    local: Long?,
    external: Long?,
    directBleSources: Iterable<Long> = emptyList(),
): Long = maxOf(
    local ?: 0L,
    external ?: 0L,
    directBleSources.maxOrNull() ?: 0L,
)

/** Legacy rows remain visible locally but cannot be proven safe to publish back to Health Connect. */
fun exportableStepDays(
    candidates: List<LocalDate>,
    legacyDays: Set<LocalDate>,
): List<LocalDate> = candidates.filterNot { it in legacyDays }

/** Phone-owned Health Connect origins must not feed the external wearable total back to us. */
fun isExternalStepOrigin(
    packageName: String,
    ownPackageName: String,
    currentDevicePackageName: String?,
): Boolean = packageName != ownPackageName &&
    packageName != "android" &&
    packageName != currentDevicePackageName

/**
 * Apps Health Connect hands over records for and then leaves out of every total it computes.
 *
 * A writer the person has never put on the Activity source list still writes, and readRecords still
 * returns what it wrote, but every aggregate answers zero for it — including one filtered to that
 * app alone. Health Connect's own screen says so in as many words: a source off the list "will stop
 * it from contributing to totals, but it will still have write permissions". Two installs of this
 * app, both holding a record for the same day, each read the other's records and import nothing
 * until one is added to the list by hand.
 *
 * [answered] must leave out any package whose own aggregate call failed. A question never put is not
 * an app being left out of the totals. Two days of records rather than one, because a record at
 * either edge of the window can fall outside the buckets that were asked for.
 *
 * Provenance, never arithmetic: no count off a raw record is read here, only the dates.
 */
fun uncountedStepOrigins(
    rawDaysByOrigin: Map<String, Set<LocalDate>>,
    answered: Set<String>,
    counted: Set<String>,
): List<String> = rawDaysByOrigin
    .filterKeys { it in answered && it !in counted }
    .filterValues { it.size >= 2 }
    .keys
    .sorted()
