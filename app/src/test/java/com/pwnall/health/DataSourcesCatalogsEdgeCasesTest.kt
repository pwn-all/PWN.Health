package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The edges of the sources screen: who a row says wrote it, what a day counted twice is worth, and
 * the invariants that keep a card from naming two winners or the same writer twice.
 */
class DataSourcesCatalogsEdgeCasesTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01")

    private fun row(
        day: String,
        at: Long,
        kind: String = "MEASUREMENT",
        weightKg: Double? = null,
        heartRateBpm: Int? = null,
        payload: String? = null,
        sleepStartAt: Long? = null,
        source: String? = null,
        recordKey: String? = null,
    ) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = kind,
        title = "row",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementHeartRateBpm = heartRateBpm,
        sleepStartAt = sleepStartAt,
        measurementSource = source,
        measurementPayloadJson = payload,
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

    private fun List<MetricSources>.steps() = metric(R.string.steps)!!

    /**
     * The daily heart-rate marker is the one import marker that names no package: naming the
     * wearable that won the day would make the day re-import whenever the winner changed. Read as a package
     * anyway, the card asks the package manager to name an app called "heart", fails, and prints
     * the bare word beside the person's pulse.
     */
    @Test
    fun aDailyHeartRateMarkerNamesNoInstalledApp() {
        val pulse = sources(
            entries = listOf(
                row(
                    "2026-09-01",
                    1_756_700_000_000L,
                    heartRateBpm = 62,
                    source = SOURCE_HEALTH_CONNECT,
                    recordKey = dailyHeartRateMarker(LocalDate.parse("2026-09-01")),
                ),
            ),
        ).metric(Vital.HEART_RATE.titleRes)!!

        val share = pulse.shares.single()
        assertEquals("source was ${share.source}", SOURCE_HEALTH_CONNECT, share.source)
        assertFalse("named an installed app called ${share.source}", share.installedApp)
    }

    /**
     * Backup.kt reads healthConnectRecordKey straight out of the restore JSON, so any string at all
     * can reach the writer lookup. A key that is not an import marker names nobody, and presenting
     * it as an installed package sends the package manager after a name that does not exist.
     */
    @Test
    fun aRecordKeyThatNamesNoPackageIsNotAnInstalledApp() {
        val weight = sources(
            entries = listOf(
                row("2026-09-01", 1_000L, weightKg = 70.0, source = SOURCE_HEALTH_CONNECT, recordKey = "abc"),
                row(
                    "2026-09-02",
                    2_000L,
                    weightKg = 69.5,
                    source = SOURCE_HEALTH_CONNECT,
                    recordKey = HEALTH_IMPORT_MARKER_PREFIX + ":abc",
                ),
            ),
        ).metric(R.string.weight)!!

        assertEquals("shares were ${weight.shares.map(SourceShare::source)}", 1, weight.shares.size)
        assertEquals(SOURCE_HEALTH_CONNECT, weight.shares.single().source)
        assertEquals(2L, weight.shares.single().amount)
        assertFalse(weight.shares.single().installedApp)
    }

    /**
     * A cuff publishing its pressure record and the phone's own row of the same squeeze land on one
     * millisecond after an import. Crediting both to whichever came last hides one device from the
     * screen whose whole subject is which device wrote what, and doubles the other's count.
     */
    @Test
    fun twoCuffsWritingInTheSameMillisecondBothKeepAShare() {
        val pressure = sources(
            entries = listOf(
                row(
                    "2026-09-01",
                    1_000L,
                    payload = """{"systolicMmhg":121,"diastolicMmhg":79}""",
                    source = "BLUETOOTH_BPS",
                ),
                row(
                    "2026-09-01",
                    1_000L,
                    payload = """{"systolicMmhg":130,"diastolicMmhg":85}""",
                    source = SOURCE_MANUAL,
                ),
            ),
        ).metric(Vital.BLOOD_PRESSURE.titleRes)!!

        assertEquals("shares were ${pressure.shares.map(SourceShare::source)}", 2, pressure.shares.size)
        assertEquals(listOf(1L, 1L), pressure.shares.map(SourceShare::amount))
        assertEquals(1, pressure.shares.count(SourceShare::winner))
        // The card prints the first row at the newest instant, so that is the writer being believed.
        assertEquals("BLUETOOTH_BPS", pressure.shares.single(SourceShare::winner).source)
    }

    /**
     * An oximeter that publishes its saturation and the glass of water logged at the same instant
     * are two rows, and the water is nobody's vital. Dropping it from "Other records" because some
     * other row shares its millisecond leaves it on no card at all.
     */
    @Test
    fun aRowOfAnotherKindSharingAnInstantWithAReadingIsStillCounted() {
        val metrics = sources(
            entries = listOf(
                row("2026-09-01", 5_000L, payload = """{"spo2Percent":97}""", source = "BLUETOOTH_PLXS"),
                row("2026-09-01", 5_000L, kind = "WATER", source = SOURCE_MANUAL),
            ),
        )

        val other = metrics.metric(R.string.sources_metric_other)
        assertNotNull("the water row is on no card at all", other)
        assertEquals(SOURCE_MANUAL, other!!.shares.single().source)
        assertEquals(1L, other.shares.single().amount)
        assertEquals(1L, metrics.metric(Vital.OXYGEN_SATURATION.titleRes)!!.shares.single().amount)
    }

    /**
     * A stored row of zero steps is a day the source was carried in a pocket that never moved, not
     * a day it fed. Four separate filters have to agree on that, and the absence test in the suite
     * next door passes empty lists, which exercises none of them.
     */
    @Test
    fun allZeroStepRowsLeaveTheStepsMetricAbsentRatherThanEmpty() {
        val metrics = sources(
            local = listOf(DaySteps("2026-09-01", 0)),
            external = listOf(ExternalDaySteps("2026-09-01", 0)),
            externalOrigins = listOf(ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 0)),
            ble = listOf(DirectBleDaySteps("colmi:AA", "2026-09-01", 0)),
        )

        assertEquals("metrics were ${metrics.map(MetricSources::titleRes)}", emptyList<MetricSources>(), metrics)
    }

    /**
     * Two sources counting a day identically must not let the day change hands between two openings
     * of the screen, so the tie goes to the first source in gathering order — the phone.
     */
    @Test
    fun aDayTwoSourcesCountedIdenticallyGoesToThePhone() {
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 5_000)),
            external = listOf(ExternalDaySteps("2026-09-01", 5_000)),
        ).steps()

        assertEquals(listOf(5_000L, 5_000L), steps.shares.map(SourceShare::amount))
        assertEquals(1, steps.shares.count(SourceShare::winner))
        assertEquals(SOURCE_PHONE, steps.shares.single(SourceShare::winner).source)
    }

    /** One day each is a tie on day count too, and it is settled the same way, every time. */
    @Test
    fun oneDayEachIsBrokenDeterministicallyAndNotByTheBiggerTotal() {
        val local = listOf(DaySteps("2026-09-02", 5_000))
        val ble = listOf(DirectBleDaySteps("colmi:AA", "2026-09-01", 9_000, sourceLabel = "R02"))

        val first = sources(local = local, ble = ble).steps()
        assertEquals(1, first.shares.count(SourceShare::winner))
        assertEquals(SOURCE_PHONE, first.shares.single(SourceShare::winner).source)
        assertEquals(first.shares, sources(local = local, ble = ble).steps().shares)
    }

    /**
     * An app that gave nothing did not feed the day, so the day is still the one app's day and says
     * so under that app's name rather than reverting to the anonymous row the screen exists to end.
     */
    @Test
    fun anAppShareOfZeroDoesNotTurnASoleDayIntoAJointOne() {
        val steps = sources(
            external = listOf(ExternalDaySteps("2026-09-01", 9_000)),
            externalOrigins = listOf(
                ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 9_000),
                ExternalOriginDaySteps("com.sec.android.app.shealth", "2026-09-01", 0),
            ),
        ).steps()

        assertEquals("shares were ${steps.shares.map(SourceShare::source)}", 1, steps.shares.size)
        val fitbit = steps.shares.single()
        assertEquals("com.fitbit.FitbitMobile", fitbit.source)
        assertEquals(9_000L, fitbit.amount)
        assertTrue(fitbit.installedApp)
        assertTrue(fitbit.winner)
    }

    /**
     * One share, bigger than the deduplicated total, because the person's own source priority
     * dropped that app's records. A row marked "shown" beside 10,000 when the app counts with 1,000
     * is the exact lie this screen was built to catch.
     */
    @Test
    fun aLoneAppShareThatExceedsTheDaysTotalDoesNotTakeTheDay() {
        val steps = sources(
            external = listOf(ExternalDaySteps("2026-09-01", 1_000)),
            externalOrigins = listOf(ExternalOriginDaySteps("com.low.priority", "2026-09-01", 10_000)),
        ).steps()

        val platform = steps.shares.single { it.source == SOURCE_HEALTH_CONNECT }
        assertEquals(1_000L, platform.amount)
        assertTrue(platform.winner)
        assertFalse(steps.shares.single { it.source == "com.low.priority" }.winner)
    }

    /**
     * The origins table is provenance for a total, never a total of its own: an app share on a day
     * with no deduplicated figure beside it would put steps on the screen that no other screen in
     * the app counts.
     */
    @Test
    fun anOriginShareOnADayWithNoTotalCreatesNoSource() {
        val steps = sources(
            external = listOf(ExternalDaySteps("2026-09-01", 9_000)),
            externalOrigins = listOf(
                ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-01", 9_000),
                ExternalOriginDaySteps("com.strava", "2026-08-15", 4_000),
            ),
        ).steps()

        assertEquals("shares were ${steps.shares.map(SourceShare::source)}", 1, steps.shares.size)
        assertEquals("com.fitbit.FitbitMobile", steps.shares.single().source)
        assertEquals(9_000L, steps.shares.single().amount)
    }

    /** lastDay is the newest day a source gave, not the last row the query happened to return. */
    @Test
    fun aSourcesLastDayIsItsNewestDayWhateverOrderTheRowsArriveIn() {
        val steps = sources(
            local = listOf(
                DaySteps("2026-09-03", 4_000),
                DaySteps("2026-09-01", 6_000),
                DaySteps("2026-09-02", 5_000),
            ),
        ).steps()

        assertEquals("2026-09-03", steps.shares.single().lastDay)
        assertEquals(15_000L, steps.shares.single().amount)
    }

    /**
     * The point of lastDay is that a device that stopped can be seen to have stopped. A stored zero
     * is not something the source gave, so it must not keep dating the row forward.
     */
    @Test
    fun aSourceThatStoppedIsDatedByItsLastDayThatCounted() {
        val steps = sources(
            local = listOf(DaySteps("2026-09-01", 5_000), DaySteps("2026-09-05", 0)),
        ).steps()

        assertEquals("2026-09-01", steps.shares.single().lastDay)
        assertEquals(5_000L, steps.shares.single().amount)
    }

    /**
     * The label is a display name captured at sync time and never the identity. A sync that failed
     * to read the ring's name must not leave a nameless row the person cannot identify.
     */
    @Test
    fun aWearableKeepsTheNameItOnceGaveWhenALaterSyncSendsNone() {
        val steps = sources(
            ble = listOf(
                DirectBleDaySteps("colmi:AA", "2026-09-01", 7_000, sourceLabel = "R02"),
                DirectBleDaySteps("colmi:AA", "2026-09-02", 8_000, sourceLabel = null),
            ),
        ).steps()

        assertEquals("R02", steps.shares.single().label)
        assertEquals(15_000L, steps.shares.single().amount)
    }

    /**
     * The invariants that hold the screen together over a corpus with something in every metric: a
     * card with no winner names nobody, a card with two contradicts itself, and one writer listed
     * twice under one card is a grouping that came apart.
     */
    @Test
    fun everyMetricNamesOneWinnerOverDistinctSourcesBiggestFirst() {
        val metrics = mixedCorpus()

        assertEquals("metrics were ${metrics.map(MetricSources::titleRes)}", 7, metrics.size)
        metrics.forEach { metric ->
            val where = "metric ${metric.titleRes} with ${metric.shares.map(SourceShare::source)}"
            assertTrue(where, metric.shares.isNotEmpty())
            assertEquals(where, 1, metric.shares.count(SourceShare::winner))
            assertEquals(where, metric.shares.size, metric.shares.map(SourceShare::source).toSet().size)
            val amounts = metric.shares.map(SourceShare::amount)
            assertEquals(where, amounts.sortedDescending(), amounts)
        }
        assertEquals(metrics.size, metrics.map(MetricSources::titleRes).toSet().size)
    }

    private fun mixedCorpus(): List<MetricSources> = sources(
        entries = listOf(
            row("2026-09-01", 1_000L, weightKg = 70.0, source = SOURCE_MANUAL),
            row("2026-09-01", 2_000L, sleepStartAt = 1_500L, source = SOURCE_MANUAL),
            row("2026-09-01", 3_000L, kind = "ACTIVITY", source = "BLUETOOTH_FTMS"),
            row("2026-09-01", 4_000L, kind = "EAT", source = SOURCE_MANUAL),
            row("2026-09-01", 5_000L, payload = """{"systolicMmhg":121,"diastolicMmhg":79}""", source = "BLE"),
            row("2026-09-01", 6_000L, kind = "WATER", source = SOURCE_MANUAL),
        ),
        local = listOf(DaySteps("2026-09-01", 4_000)),
        external = listOf(ExternalDaySteps("2026-09-01", 9_000), ExternalDaySteps("2026-09-02", 7_000)),
        externalOrigins = listOf(
            ExternalOriginDaySteps("com.fitbit.FitbitMobile", "2026-09-02", 7_000),
            ExternalOriginDaySteps("com.a.tracker", "2026-09-01", 5_000),
            ExternalOriginDaySteps("com.b.tracker", "2026-09-01", 5_000),
        ),
        ble = listOf(DirectBleDaySteps("colmi:AA", "2026-09-01", 6_000, sourceLabel = "R02")),
    )
}
