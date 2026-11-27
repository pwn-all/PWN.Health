package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * A history that reaches the journal by both roads: the service's own app has been publishing into
 * Health Connect for some months, and its export holds those months and the years before them.
 * The file's rows carry no record id and the exchange's rows carry nothing else, so without a rule
 * every meal, session and weighing of the months both cover would land twice.
 */
class HealthImportOverlapTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val fitbit = "com.fitbit.FitbitMobile"
    private val withings = "com.withings.wiscale2"

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun fromFile(app: HealthExportApp, vararg records: HealthRecord) =
        HealthImportFile(app, records.toList()).rows(zone, testTitles()).entries

    /** A row the exchange filed: the publishing app's package and the record's id are its marker. */
    private fun published(packageName: String, iso: String, filled: JournalEntry.() -> JournalEntry) =
        JournalEntry(
            day = iso.take(10),
            occurredAt = at(iso),
            kind = "MEASUREMENT",
            title = "From Health Connect",
            calories = 0.0,
            measurementSource = "HEALTH_CONNECT",
            healthConnectRecordKey = "Health Connect:$packageName:$iso",
        ).filled()

    /**
     * A day the app already published a weighing on is the exchange's; every other day is the file's
     * — the years before, and a day in between that the app never published.
     */
    @Test
    fun theDaysHealthConnectAlreadyBroughtAreLeftToIt() {
        val file = fromFile(
            HealthExportApp.FITBIT,
            WeightRecord(at("2025-01-10T07:00:00Z"), 84.0),
            WeightRecord(at("2026-03-01T07:00:00Z"), 82.4),
            WeightRecord(at("2026-03-10T07:00:00Z"), 82.2),
            WeightRecord(at("2026-03-20T07:00:00Z"), 82.0),
        )
        val journal = listOf(
            published(fitbit, "2026-03-01T07:00:00Z") { copy(measurementWeightKg = 82.4) },
            // The same morning as the file states it, a few minutes apart: the day is what is asked.
            published(fitbit, "2026-03-20T06:55:00Z") { copy(measurementWeightKg = 82.0) },
        )

        val fresh = file.withoutWhatHealthConnectBrought(journal)

        assertEquals(listOf("2025-01-10", "2026-03-10"), fresh.map(JournalEntry::day))
    }

    /** What the app publishes of one kind says nothing about another: the diary still lands whole. */
    @Test
    fun aKindTheAppDoesNotPublishIsReadFromTheFileWhole() {
        val file = fromFile(
            HealthExportApp.FITBIT,
            NutritionRecord(at("2026-03-20T12:00:00Z"), "Porridge", "Breakfast", 310.0),
            WorkoutRecord(at("2026-03-20T17:00:00Z"), "Run", 1_800),
        )
        val journal = listOf(
            published(fitbit, "2026-03-20T07:00:00Z") { copy(measurementWeightKg = 82.4) },
            published(fitbit, "2026-03-20T17:02:00Z") { copy(kind = "ACTIVITY") },
        )

        val fresh = file.withoutWhatHealthConnectBrought(journal)

        assertEquals(listOf("EAT"), fresh.map(JournalEntry::kind))
    }

    /** Another service's app, this app's own published copy and a row with no marker cover nothing. */
    @Test
    fun onlyTheSameServicesOwnAppAnswersForItsFile() {
        val file = fromFile(HealthExportApp.WITHINGS, WeightRecord(at("2026-03-20T07:00:00Z"), 82.0))
        val journal = listOf(
            published(fitbit, "2026-03-20T07:00:00Z") { copy(measurementWeightKg = 82.0) },
            published("com.pwnall.health", "2026-03-20T07:00:00Z") { copy(measurementWeightKg = 82.0) },
            published(withings, "2026-03-20T07:00:00Z") {
                copy(measurementWeightKg = 82.0, healthConnectRecordKey = null)
            },
        )

        assertEquals(file, file.withoutWhatHealthConnectBrought(journal))
        assertEquals(file, file.withoutWhatHealthConnectBrought(emptyList()))
    }

    /**
     * A reading is known by the figure it carries, because the two roads title it differently; and a
     * night is left to the rule that asks the waking day, whatever Health Connect holds.
     */
    @Test
    fun aReadingIsMatchedByItsFigureAndANightIsLeftToItsOwnRule() {
        val file = fromFile(
            HealthExportApp.WITHINGS,
            VitalRecord(at("2026-03-20T07:00:00Z"), Vital.BLOOD_PRESSURE, 128.0, 80.0),
            VitalRecord(at("2026-03-20T07:00:00Z"), Vital.BODY_FAT, 23.0),
            SleepRecord(at("2026-03-19T21:00:00Z"), at("2026-03-20T05:30:00Z")),
        )
        val pressure = """{"schema":1,"systolicMmhg":128.0,"diastolicMmhg":80.0}"""
        val journal = listOf(
            published(withings, "2026-03-20T07:00:00Z") { copy(measurementPayloadJson = pressure) },
            published(withings, "2026-03-20T05:30:00Z") { copy(sleepStartAt = 1, sleepEndAt = 2) },
        )

        val fresh = file.withoutWhatHealthConnectBrought(journal)

        assertEquals(listOf("BODY_FAT", "Sleep"), fresh.map(JournalEntry::title))
    }

    /**
     * The other order: the file was read first, and the exchange then brings the same months from
     * the service's own app. A day the file covered is left to it, a few minutes apart or not — and
     * nothing else is: a day the file states no weighing on, another service's record, another kind,
     * and whatever the service recorded after the file's last row, which no file will ever bring.
     */
    @Test
    fun whatAFileAlreadyBroughtIsNotFiledAgainByTheExchange() {
        val filed = FiledFacts(
            fromFile(
                HealthExportApp.FITBIT,
                WeightRecord(at("2026-03-01T07:00:00Z"), 82.4),
                WeightRecord(at("2026-03-20T07:00:00Z"), 82.0),
            ),
        )

        fun weighing(app: String, iso: String) = published(app, iso) { copy(measurementWeightKg = 82.0) }

        assertTrue(filed.cover(weighing(fitbit, "2026-03-01T06:55:00Z")))
        assertTrue(filed.cover(weighing(fitbit, "2026-03-20T07:00:00Z")))
        // The file's last row, as the record states it: the same minute with its seconds.
        assertTrue(filed.cover(weighing(fitbit, "2026-03-20T07:00:40Z")))
        assertFalse(filed.cover(weighing(fitbit, "2026-03-20T07:01:00Z")))
        assertFalse(filed.cover(weighing(fitbit, "2026-03-10T07:00:00Z")))
        // The evening of the day the export was taken, and the day after it.
        assertFalse(filed.cover(weighing(fitbit, "2026-03-20T19:00:00Z")))
        assertFalse(filed.cover(weighing(fitbit, "2026-03-21T07:00:00Z")))
        assertFalse(filed.cover(weighing(withings, "2026-03-01T07:00:00Z")))
        assertFalse(filed.cover(published(fitbit, "2026-03-01T07:00:00Z") { copy(kind = "ACTIVITY") }))
        assertFalse(FiledFacts(emptyList()).cover(weighing(fitbit, "2026-03-01T07:00:00Z")))
    }

    /**
     * A training logger publishes a workout as one session and its file states the same hour set by
     * set. Both standing, the day's training would count twice; the sets stay, whichever came first.
     */
    @Test
    fun aLoggersSessionGivesWayToTheSetsItsFileStates() {
        val sets = listOf(
            JournalEntry(
                day = "2026-03-20",
                occurredAt = at("2026-03-20T17:00:00Z"),
                kind = "ACTIVITY",
                title = "Squat",
                calories = 0.0,
                measurementSource = WorkoutExportApp.HEVY.label,
                healthConnectRecordKey = "${FILE_IMPORT_MARKER_PREFIX}Hevy:2026-03-20T180000:Squat",
            ),
        )
        val session = published("com.hevy", "2026-03-20T17:00:00Z") { copy(kind = "ACTIVITY") }
        val later = published("com.hevy", "2026-03-22T17:00:00Z") { copy(kind = "ACTIVITY") }

        // The file read after the exchange: the session of a day the file states goes, the other stays.
        assertEquals(listOf(session), sets.sessionsTheSetsReplace(listOf(session, later)))
        assertEquals(emptyList<JournalEntry>(), sets.sessionsTheSetsReplace(emptyList()))
        // A session corrected by hand is the person's own row and is not taken away.
        assertEquals(emptyList<JournalEntry>(), sets.sessionsTheSetsReplace(listOf(session.copy(editedAt = 1))))
        // The exchange after the file: that session is not filed, and one the file never saw is.
        assertTrue(FiledFacts(sets).cover(session))
        assertFalse(FiledFacts(sets).cover(later))
        assertEquals("Health Connect:com.hevy:", healthConnectMarkerOf(WorkoutExportApp.HEVY.label))
    }
}
