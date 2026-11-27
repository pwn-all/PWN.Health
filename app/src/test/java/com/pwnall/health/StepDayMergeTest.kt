package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StepDayMergeTest {
    private val monday = LocalDate.parse("2026-07-27")
    private val tuesday = LocalDate.parse("2026-07-28")

    @Test
    fun aWatchAheadOfThePhoneIsImported() {
        // Health Connect has 10,000 steps, and the app must not show 0.
        val merge = mergeStepDays(
            local = mapOf(monday to 0L),
            externalTotals = mapOf(monday to 10_000L),
        )

        assertEquals(mapOf(monday to 10_000L), merge.external)
        assertEquals(mapOf(monday to 10_000L), merge.imports)
        assertTrue(merge.exports.isEmpty())
    }

    @Test
    fun aDayMissingLocallyIsStillImported() {
        val merge = mergeStepDays(
            local = emptyMap(),
            externalTotals = mapOf(monday to 7_500L),
        )

        assertEquals(mapOf(monday to 7_500L), merge.imports)
        assertEquals(listOf(monday), merge.covered)
    }

    @Test
    fun thePhoneKeepsItsOwnHigherCountAndGoesOnPublishingIt() {
        val merge = mergeStepDays(
            local = mapOf(monday to 12_000L),
            externalTotals = mapOf(monday to 9_000L),
        )

        assertTrue(merge.imports.isEmpty())
        assertEquals(mapOf(monday to 9_000L), merge.external)
        // Nobody owns a day they counted less of. Were presence alone ownership, the withdrawal
        // that followed would delete the fuller record and leave the thinner one standing.
        assertTrue(merge.covered.isEmpty())
        assertEquals(listOf(monday), merge.exports)
    }

    @Test
    fun anEqualCountChangesNothing() {
        val merge = mergeStepDays(
            local = mapOf(monday to 9_000L),
            externalTotals = mapOf(monday to 9_000L),
        )

        assertTrue(merge.imports.isEmpty())
        // Equal is covered: they hold everything this phone holds, so there is nothing to add.
        assertEquals(listOf(monday), merge.covered)
        assertTrue(merge.exports.isEmpty())
    }

    /**
     * Another app writing a handful of steps this morning must not make the whole day somebody
     * else's: the day would be withdrawn from Health Connect and never re-published, so a phone that
     * had counted four and a half thousand would offer nothing to anything reading after it — a
     * second install of this same app included.
     */
    @Test
    fun aTokenCountFromAnotherAppCannotTakeTheDay() {
        val merge = mergeStepDays(
            local = mapOf(monday to 4_602L),
            externalTotals = mapOf(monday to 12L),
        )

        assertTrue(merge.covered.isEmpty())
        assertTrue(merge.imports.isEmpty())
        assertEquals(listOf(monday), merge.exports)
    }

    @Test
    fun aDayNobodyElseReportedIsExported() {
        val merge = mergeStepDays(
            local = mapOf(monday to 4_000L, tuesday to 6_000L),
            externalTotals = mapOf(tuesday to 8_000L),
        )

        assertEquals(listOf(monday), merge.exports)
        assertEquals(listOf(tuesday), merge.covered)
    }

    @Test
    fun anEmptyLocalDayIsNeitherExportedNorWithdrawn() {
        val merge = mergeStepDays(
            local = mapOf(monday to 0L),
            externalTotals = emptyMap(),
        )

        assertTrue(merge.exports.isEmpty())
        assertTrue(merge.covered.isEmpty())
    }

    @Test
    fun invalidExternalTotalsDoNotClaimTheDay() {
        val merge = mergeStepDays(
            local = mapOf(monday to 500L),
            externalTotals = mapOf(monday to 0L, tuesday to -1L),
        )

        assertTrue(merge.imports.isEmpty())
        assertTrue(merge.external.isEmpty())
        assertTrue(merge.covered.isEmpty())
        assertEquals(listOf(monday), merge.exports)
    }

    @Test
    fun resultsAreSortedByDay() {
        val merge = mergeStepDays(
            local = mapOf(tuesday to 1_000L, monday to 1_000L),
            externalTotals = emptyMap(),
        )

        assertEquals(listOf(monday, tuesday), merge.exports)
    }

    /**
     * The floor is for repetition, not for news. Granting the step permission and coming back must
     * not land on a screen that will not exchange again for five minutes: the number the person had
     * just allowed would stay at zero and look like a refusal that had not taken.
     */
    @Test
    fun aChangedGrantIsItsOwnReasonToExchange() {
        val minute = 60_000L
        val now = 10 * minute

        assertTrue(healthSyncDue(force = false, grantsChanged = true, lastSuccessMs = now, now = now))
        assertTrue(healthSyncDue(force = true, grantsChanged = false, lastSuccessMs = now, now = now))
        // Nothing new and a minute old: the floor holds.
        assertFalse(healthSyncDue(force = false, grantsChanged = false, lastSuccessMs = now - minute, now = now))
        // Nothing new and six minutes old: the floor has passed.
        assertTrue(healthSyncDue(force = false, grantsChanged = false, lastSuccessMs = now - 6 * minute, now = now))
    }

    @Test
    fun localAndExternalTotalsAreNeverAddedTogether() {
        assertEquals(10_000L, effectiveStepTotal(local = 6_000L, external = 10_000L))
        assertEquals(12_000L, effectiveStepTotal(local = 12_000L, external = 9_000L))
        assertEquals(6_000L, effectiveStepTotal(local = 6_000L, external = null))
    }

    @Test
    fun directBleSourcesKeepTheirProvenanceAndAreNeverAdded() {
        assertEquals(
            11_000L,
            effectiveStepTotal(
                local = 6_000L,
                external = 8_000L,
                directBleSources = listOf(7_500L, 11_000L),
            ),
        )
        assertEquals(
            12_000L,
            effectiveStepTotal(
                local = 12_000L,
                external = 8_000L,
                directBleSources = listOf(7_500L, 11_000L),
            ),
        )
    }

    @Test
    fun legacyDaysRemainVisibleButAreNotExported() {
        val candidates = listOf(monday, tuesday)

        assertEquals(listOf(tuesday), exportableStepDays(candidates, setOf(monday)))
        assertEquals(8_000L, effectiveStepTotal(local = 8_000L, external = null))
    }

    /**
     * The measured state: an app writes, Health Connect hands over its records, and every total it
     * computes leaves that app out because the person never added it as a source. The app can only
     * be named, never worked around — the totals are the platform's to give.
     */
    @Test
    fun anAppWhoseRecordsAreHereAndWhoseTotalIsEmptyIsNamed() {
        val watch = "com.example.watch"

        assertEquals(
            listOf(watch),
            uncountedStepOrigins(
                rawDaysByOrigin = mapOf(watch to setOf(monday, tuesday)),
                answered = setOf(watch),
                counted = emptySet(),
            ),
        )
    }

    /** One app being counted says nothing about another; a joint total would hide the second. */
    @Test
    fun aCountedAppDoesNotShieldAnUncountedOne() {
        val counted = "com.example.watch"
        val ignored = "com.example.tracker"

        assertEquals(
            listOf(ignored),
            uncountedStepOrigins(
                rawDaysByOrigin = mapOf(counted to setOf(monday, tuesday), ignored to setOf(monday, tuesday)),
                answered = setOf(counted, ignored),
                counted = setOf(counted),
            ),
        )
    }

    /** A rate-limited aggregate is not evidence: an unasked question accuses nobody. */
    @Test
    fun anAggregateThatFailedAccusesNobody() {
        val watch = "com.example.watch"

        assertTrue(uncountedStepOrigins(mapOf(watch to setOf(monday, tuesday)), emptySet(), emptySet()).isEmpty())
        // One day can sit outside the buckets that were asked for; two cannot.
        assertTrue(uncountedStepOrigins(mapOf(watch to setOf(monday)), setOf(watch), emptySet()).isEmpty())
    }

    @Test
    fun phoneOwnedHealthConnectOriginsAreNotExternal() {
        val ownPackage = "com.pwnall.health"
        val deviceSpn = "com.android.healthconnect.phone.private-id"

        assertFalse(isExternalStepOrigin(ownPackage, ownPackage, deviceSpn))
        assertFalse(isExternalStepOrigin("android", ownPackage, deviceSpn))
        assertFalse(isExternalStepOrigin(deviceSpn, ownPackage, deviceSpn))
        assertTrue(isExternalStepOrigin("com.vendor.wearable", ownPackage, deviceSpn))
    }
}
