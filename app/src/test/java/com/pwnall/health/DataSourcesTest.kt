package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the data sources screen has to get right: whose figure is being shown, and that two sources
 * covering the same day are never added together.
 */
class DataSourcesTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01")

    private fun row(
        day: String,
        at: Long,
        kind: String = "MEASUREMENT",
        weightKg: Double? = null,
        source: String? = null,
        recordKey: String? = null,
    ) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = kind,
        title = "row",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementSource = source,
        healthConnectRecordKey = recordKey,
    )

    private fun sources(
        entries: List<JournalEntry> = emptyList(),
        local: List<DaySteps> = emptyList(),
        external: List<ExternalDaySteps> = emptyList(),
        externalOrigins: List<ExternalOriginDaySteps> = emptyList(),
        ble: List<DirectBleDaySteps> = emptyList(),
    ) = dataSources(entries, local, external, externalOrigins, ble, profile)

    private fun List<MetricSources>.metric(titleRes: Int) = firstOrNull { it.titleRes == titleRes }

    @Test
    fun aDayCountedByTwoSourcesIsNotSummed() {
        // The complaint the screen exists for: steps doubled at midnight with one device paired.
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 4_000)),
            external = listOf(ExternalDaySteps("2026-09-01", 9_000)),
        ).metric(R.string.steps)!!

        assertEquals(listOf(9_000L, 4_000L), steps.shares.map(SourceShare::amount))
        assertTrue(steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.winner)
        assertEquals(2, steps.shares.size)
    }

    @Test
    fun theStepWinnerIsTheSourceThatCarriedTheMostDaysNotTheBiggestTotal() {
        // One enormous day from a watch against two ordinary ones from the phone: the phone's
        // figure is what the app shows on two days out of three, so the phone is the winner.
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 8_000), DaySteps("2026-09-02", 8_000)),
            external = listOf(ExternalDaySteps("2026-09-03", 40_000)),
        ).metric(R.string.steps)!!

        assertTrue(steps.shares.single { it.source == SOURCE_PHONE }.winner)
        assertEquals(SOURCE_HEALTH_CONNECT, steps.shares.first().source)
    }

    /**
     * The whole point of the sources screen, on the one metric Health Connect aggregates itself.
     * One app accounts for the day entirely — its own figure is the day's figure — so the row is that
     * app's, under the name the package manager gives it, and "Health Connect" names nobody.
     */
    @Test
    fun aDayOneAppAccountsForIsNamedByThatApp() {
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 4_000)),
            external = listOf(ExternalDaySteps("2026-09-01", 9_000)),
            externalOrigins = listOf(ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 9_000)),
        ).metric(R.string.steps)!!

        val fitbit = steps.shares.single { it.source == "com.fitbit.FitbitMobile" }
        assertEquals(9_000L, fitbit.amount)
        assertTrue(fitbit.installedApp)
        assertTrue(fitbit.winner)
        assertTrue(steps.shares.none { it.source == SOURCE_HEALTH_CONNECT })
    }

    /**
     * A day several apps fed keeps its total under Health Connect's own name, with the apps listed
     * under it for what each of them gave.
     *
     * The total is deduplicated across them and belongs to none of them: printing 9,000 beside one
     * app's name would credit it with steps it did not report, and marking the app that gave 6,500
     * as "shown" would put the badge beside a number the rest of the app does not show. The phone's
     * 8,000 is weighed against the 9,000 actually being shown, so a second app's smaller share does
     * not hand it the day either.
     */
    @Test
    fun aDaySeveralAppsFedKeepsItsTotalUnderHealthConnect() {
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 8_000)),
            external = listOf(ExternalDaySteps("2026-09-01", 9_000)),
            externalOrigins = listOf(
                ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 6_500),
                ExternalOriginDaySteps("com.sec.android.app.shealth", "2026-09-01", 2_400),
            ),
        ).metric(R.string.steps)!!

        assertEquals(9_000L, steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.amount)
        assertTrue(steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.winner)
        assertEquals(6_500L, steps.shares.single { it.source == "com.fitbit.FitbitMobile" }.amount)
        assertEquals(2_400L, steps.shares.single { it.source == "com.sec.android.app.shealth" }.amount)
        assertFalse(steps.shares.single { it.source == SOURCE_PHONE }.winner)
        assertEquals(4, steps.shares.size)
    }

    /**
     * A share bigger than the day it belongs to is not a corruption: the joint total applies the
     * person's own source priority and drops the records of the app they ranked lower, so that
     * app's own aggregate can exceed the figure the app counts with. It still does not take the
     * day — the number being shown is the joint one, and the row carrying it says so.
     */
    @Test
    fun anAppWhoseRecordsThePriorityListDroppedDoesNotTakeTheDay() {
        val steps = sources(
            external = listOf(ExternalDaySteps("2026-09-01", 1_000)),
            externalOrigins = listOf(
                ExternalOriginDaySteps("com.high.priority", "2026-09-01", 1_000),
                ExternalOriginDaySteps("com.low.priority", "2026-09-01", 10_000),
            ),
        ).metric(R.string.steps)!!

        assertTrue(steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.winner)
        assertEquals(10_000L, steps.shares.single { it.source == "com.low.priority" }.amount)
        assertFalse(steps.shares.single { it.source == "com.low.priority" }.winner)
    }

    /**
     * Every day already in the table on the morning of the upgrade has no shares against it, and
     * reads exactly as it read before there were any: one row, under Health Connect's own name.
     */
    @Test
    fun aDayWithNoStoredSharesStaysUnderHealthConnectsOwnName() {
        val steps = sources(
            external = listOf(ExternalDaySteps("2026-08-01", 5_000), ExternalDaySteps("2026-09-01", 9_000)),
            externalOrigins = listOf(ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 9_000)),
        ).metric(R.string.steps)!!

        assertEquals(5_000L, steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.amount)
        assertEquals("2026-08-01", steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }.lastDay)
        assertEquals(9_000L, steps.shares.single { it.source == "com.fitbit.FitbitMobile" }.amount)
        assertEquals(2, steps.shares.size)
    }

    @Test
    fun aWearableIsNamedByItselfAndKeptApartFromHealthConnect() {
        val steps = sources(
            ble = listOf(DirectBleDaySteps("colmi:AA", "2026-09-01", 7_000, sourceLabel = "R02")),
            external = listOf(ExternalDaySteps("2026-09-01", 3_000)),
        ).metric(R.string.steps)!!

        val ring = steps.shares.single { it.source == "colmi:AA" }
        assertEquals("R02", ring.label)
        assertTrue(ring.winner)
        assertEquals(2, steps.shares.size)
    }

    @Test
    fun anImportedRowNamesTheAppBehindHealthConnectRatherThanHealthConnect() {
        val weight = sources(
            entries = listOf(
                row("2026-09-01", 1_000, weightKg = 70.0, source = "MANUAL"),
                row(
                    "2026-09-02",
                    2_000,
                    weightKg = 69.5,
                    source = "HEALTH_CONNECT",
                    recordKey = "${HEALTH_IMPORT_MARKER_PREFIX}com.example.scale:abc",
                ),
            ),
        ).metric(R.string.weight)!!

        val imported = weight.shares.single { it.source == "com.example.scale" }
        assertTrue(imported.installedApp)
        // The newest row is the one the cards print, so its writer is the one being believed.
        assertTrue(imported.winner)
        assertEquals("2026-09-02", imported.lastDay)
        assertFalse(weight.shares.single { it.source == SOURCE_MANUAL }.installedApp)
    }

    @Test
    fun aRowNoMetricClaimsIsCountedOnceUnderOtherRecords() {
        val metrics = sources(
            entries = listOf(
                row("2026-09-01", 1_000, kind = "WATER"),
                row("2026-09-01", 2_000, kind = "EAT"),
            ),
        )

        assertEquals(1L, metrics.metric(R.string.sources_metric_other)!!.shares.single().amount)
        assertEquals(1L, metrics.metric(R.string.calendar_food)!!.shares.single().amount)
    }

    @Test
    fun aMetricNothingHasWrittenIsAbsentRatherThanEmpty() {
        assertTrue(sources().isEmpty())
        assertNull(sources(local = listOf(DaySteps("2026-09-01", 100))).metric(R.string.weight))
    }

    @Test
    fun theMostContestedMetricComesFirst() {
        val metrics = sources(
            local = listOf(DaySteps("2026-09-01", 4_000)),
            entries = listOf(
                row("2026-09-01", 1_000, weightKg = 70.0, source = "MANUAL"),
                row("2026-09-02", 2_000, weightKg = 69.5, source = "BLUETOOTH_SCALE"),
            ),
        )

        assertEquals(R.string.weight, metrics.first().titleRes)
    }
}
