package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What an imported fact becomes once it is in the journal.
 *
 * The readers are tested next door against real files; this is the other half — that a fact lands in
 * the row the app already uses for one of its own, that it is filed under a name the same file would
 * produce again, and that the numbers it cannot hold are refused rather than stored wrong.
 */
class HealthFileImportTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val titles = testTitles()

    private fun rows(vararg records: HealthRecord) =
        HealthImportFile(HealthExportApp.WITHINGS, records.toList()).rows(zone, titles)

    private fun entries(vararg records: HealthRecord) = rows(*records).entries

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    // ─── the rows ───────────────────────────────────────────────────────────────────────────────

    /** A weighing reads exactly as one the scales wrote: the same kind, the same column, the same title. */
    @Test
    fun aWeighingIsTheRowTheScalesAlreadyWrite() {
        val row = entries(WeightRecord(at("2026-03-01T07:30:00Z"), 82.4)).single()

        assertEquals("MEASUREMENT", row.kind)
        assertEquals("Body measurement", row.title)
        assertEquals("2026-03-01", row.day)
        assertEquals(82.4, row.measurementWeightKg!!, 0.001)
        assertEquals("Withings", row.measurementSource)
        assertEquals(0.0, row.calories, 0.001)
    }

    /** The extra figures a smart scale works out ride in the payload the vitals screen reads back. */
    @Test
    fun theBodyCompositionRidesInThePayload() {
        val row = entries(
            WeightRecord(
                atMs = at("2026-03-01T07:30:00Z"),
                kg = 82.4,
                bodyFatPercent = 18.2,
                muscleMassKg = 46.9,
                bodyWaterMassKg = 30.5,
            ),
        ).single()

        val payload = row.measurementPayloadJson.orEmpty()
        assertEquals(18.2, row.bodyFatPercent!!, 0.001)
        assertTrue(payload.contains(Vital.MUSCLE_MASS.key))
        assertTrue(payload.contains(Vital.BODY_WATER.key))
    }

    /** A weighing with nothing else measured carries no payload rather than an empty one. */
    @Test
    fun aPlainWeighingHasNoPayload() {
        assertNull(entries(WeightRecord(at("2026-03-01T07:30:00Z"), 82.4)).single().measurementPayloadJson)
    }

    /** A night is filed on the day it ended, where the app's own sleep rows and Health Connect's sort. */
    @Test
    fun aNightIsFiledAtItsEnd() {
        val row = entries(SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z"))).single()

        assertEquals("Sleep", row.title)
        assertEquals(at("2026-03-02T05:30:00Z"), row.occurredAt)
        assertEquals(at("2026-03-01T21:00:00Z"), row.sleepStartAt)
        assertEquals(at("2026-03-02T05:30:00Z"), row.sleepEndAt)
        assertEquals("2026-03-02", row.day)
    }

    /** Energy the file states is a measurement: no MET rides with it for the recalculation to replace it. */
    @Test
    fun statedWorkoutEnergyIsKeptOutOfTheRecalculation() {
        val run = WorkoutRecord(at("2026-03-01T07:30:00Z"), "Run", seconds = 1800, calories = 436.0)
        val stated = entries(run).single()
        val estimated = entries(WorkoutRecord(at("2026-03-01T07:30:00Z"), "Run", seconds = 1800)).single()

        assertEquals(436.0, stated.calories, 0.0)
        assertNull(stated.met)
        assertNotNull(estimated.met)
    }

    /** A pulse fills both the column the day list prints and the key the vitals screen charts. */
    @Test
    fun aPulseFillsItsColumnAndItsPayloadKey() {
        val row = entries(VitalRecord(at("2026-03-01T07:30:00Z"), Vital.HEART_RATE, 58.0)).single()

        assertEquals(58, row.measurementHeartRateBpm)
        assertTrue(row.measurementPayloadJson!!.contains(Vital.HEART_RATE.key))
    }

    /**
     * A resting rate is a vital of its own, under its own key and out of the pulse's column, which
     * is what the pulse series reads; with no column the figure rides in the title. It is filed
     * under the word a pulse is, the name the same day may already carry from an earlier import, and
     * the same export must not land beside itself.
     */
    @Test
    fun aRestingRateIsItsOwnVitalUnderTheNameThePulseHad() {
        val stamp = at("2026-03-01T07:30:00Z")
        val resting = entries(VitalRecord(stamp, Vital.RESTING_HEART_RATE, 52.4)).single()
        val pulse = entries(VitalRecord(stamp, Vital.HEART_RATE, 52.4)).single()

        val payload = resting.measurementPayloadJson.orEmpty()
        assertTrue(payload.contains("\"${Vital.RESTING_HEART_RATE.key}\":52.4"))
        assertFalse(payload.contains(Vital.HEART_RATE.key))
        assertNull(resting.measurementHeartRateBpm)
        assertEquals("RESTING_HEART_RATE 52", resting.title)
        assertEquals(pulse.healthConnectRecordKey, resting.healthConnectRecordKey)
    }

    @Test
    fun aPressureCarriesBothOfItsHalves() {
        val row = entries(VitalRecord(at("2026-03-01T07:30:00Z"), Vital.BLOOD_PRESSURE, 128.0, 80.0)).single()

        val payload = row.measurementPayloadJson.orEmpty()
        assertTrue(payload.contains("\"systolicMmhg\":128"))
        assertTrue(payload.contains("\"diastolicMmhg\":80"))
        // Only a blood pressure has a pulse column to leave empty, and it leaves it empty.
        assertNull(row.measurementHeartRateBpm)
    }

    /** Half a blood pressure is not a blood pressure, and is not stored as the half that survived. */
    @Test
    fun aPressureMissingItsDiastolicIsNotStored() {
        assertTrue(entries(VitalRecord(at("2026-03-01T07:30:00Z"), Vital.BLOOD_PRESSURE, 128.0)).isEmpty())
    }

    /**
     * A reading outside the vital's own range is an artefact — a cuff that errored to 0/0, a
     * saturation sentinel — and one that reached the journal would move an average and colour a card.
     */
    @Test
    fun anArtefactIsRefusedRatherThanGraded() {
        assertTrue(
            entries(
                VitalRecord(at("2026-03-01T07:30:00Z"), Vital.BLOOD_PRESSURE, 0.0, 0.0),
                VitalRecord(at("2026-03-01T07:31:00Z"), Vital.OXYGEN_SATURATION, 5.0),
                VitalRecord(at("2026-03-01T07:32:00Z"), Vital.HEART_RATE, 400.0),
            ).isEmpty(),
        )
    }

    /** Energy comes from the diary rather than from multiplying the macros back out. */
    @Test
    fun aMealKeepsTheEnergyItsOwnDiaryStated() {
        val row = entries(
            NutritionRecord(
                atMs = at("2026-03-01T07:30:00Z"),
                title = "Porridge",
                meal = "Breakfast",
                calories = 480.0,
                proteinG = 12.0,
                fatG = 9.0,
                carbsG = 80.0,
                servingG = 250.0,
            ),
        ).single()

        assertEquals("EAT", row.kind)
        assertEquals("Porridge", row.title)
        assertEquals("Breakfast", row.subtitle)
        assertEquals(480.0, row.calories, 0.001)
        assertEquals(250.0, row.servingG, 0.001)
    }

    /** A diary that logs a whole meal at once has nothing to name but the meal. */
    @Test
    fun aMealNamedAfterItselfHasNoSubtitle() {
        val row = entries(
            NutritionRecord(at("2026-03-01T07:30:00Z"), title = "Breakfast", meal = "Breakfast", calories = 480.0),
        ).single()

        assertEquals("Breakfast", row.title)
        assertEquals("", row.subtitle)
    }

    /** A session lands as an activity with the app's own catalogue behind it. */
    @Test
    fun aSessionBecomesAnActivityWithItsCatalogueEntry() {
        val row = entries(
            WorkoutRecord(
                startMs = at("2026-03-01T07:30:00Z"),
                name = "Morning Run",
                seconds = 1830,
                distanceM = 5200.0,
                calories = 420.0,
            ),
        ).single()

        assertEquals("ACTIVITY", row.kind)
        assertEquals("Morning Run", row.title)
        assertEquals(30.5, row.durationMinutes!!, 0.01)
        assertEquals(420.0, row.calories, 0.01)
        // A run and not the strength training an unrecognised name falls back to: filed there, its
        // energy would be counted on top of the steps of the same day.
        assertEquals("RUN", row.activityCode)
        // The file stated the energy, so no MET rides with it for the recalculation to overwrite.
        assertNull(row.met)
        // The one figure the journal has no column for goes where an imported session already puts it.
        assertEquals("5.2 km", row.subtitle)
    }

    /**
     * A title is whatever the person typed. The service's own word for the session is asked first,
     * the title only where that word names nothing the catalogue knows — and the word is no part of
     * the marker, so a session imported without the word is still the same row.
     */
    @Test
    fun theServicesOwnTypeIsAskedBeforeTheTitle() {
        fun session(name: String, type: String = "") =
            entries(WorkoutRecord(at("2026-03-01T07:30:00Z"), name, seconds = 1800, type = type)).single()

        assertEquals("RUN", session("Lunch break", "Run").activityCode)
        assertEquals("CYCLING", session("Zugfahrt nach Hause", "Ride").activityCode)
        assertEquals("SQUATS", session("Squats at home", "Workout").activityCode)
        assertEquals(FALLBACK_ACTIVITY_CODE, session("Zugfahrt nach Hause", "Radfahrt").activityCode)
        assertEquals("Lunch break", session("Lunch break", "Run").title)
        assertEquals(
            session("Lunch break").healthConnectRecordKey,
            session("Lunch break", "Run").healthConnectRecordKey,
        )
    }

    /** A treadmill's rounding is not a distance covered, and does not earn a subtitle. */
    @Test
    fun aTrivialDistanceIsNotPrinted() {
        val row = entries(WorkoutRecord(at("2026-03-01T07:30:00Z"), "Rowing", seconds = 600, distanceM = 40.0)).single()

        assertEquals("", row.subtitle)
    }

    /**
     * A session whose file states no energy is left at nought on purpose: what it burned depends on
     * what the person weighed at the time, and that is the walk `recalculateJournal` does afterwards.
     */
    @Test
    fun aSessionWithNoStatedEnergyIsLeftForTheRecalculation() {
        val row = entries(WorkoutRecord(at("2026-03-01T07:30:00Z"), "Walk", seconds = 1200)).single()

        assertEquals(0.0, row.calories, 0.001)
        assertNotNull(row.met)
    }

    /**
     * A period boundary is filed at midday. Midnight is the one hour where being an hour out moves
     * the row to another day, and a period start on the wrong day shifts every cycle length after it.
     */
    @Test
    fun aPeriodBoundaryIsFiledAtMidday() {
        val rows = entries(
            PeriodRecord(LocalDate.parse("2026-03-01"), start = true),
            PeriodRecord(LocalDate.parse("2026-03-05"), start = false),
        )

        assertEquals(listOf(KIND_CYCLE, KIND_CYCLE_END), rows.map(JournalEntry::kind))
        assertEquals(listOf("2026-03-01", "2026-03-05"), rows.map(JournalEntry::day))
        assertEquals(
            LocalDate.parse("2026-03-01").atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            rows.first().occurredAt,
        )
    }

    @Test
    fun waterAndCaffeineKeepTheirAmountsWhereTheAppKeepsThem() {
        val rows = entries(
            DrinkRecord(at("2026-03-01T07:30:00Z"), KIND_WATER, 1500.0),
            DrinkRecord(at("2026-03-01T08:30:00Z"), KIND_CAFFEINE, 80.0),
        )

        assertEquals(listOf(KIND_WATER, KIND_CAFFEINE), rows.map(JournalEntry::kind))
        assertEquals(listOf("Water", "Caffeine"), rows.map(JournalEntry::title))
        assertEquals(listOf(1500.0, 80.0), rows.map(JournalEntry::servingG))
    }

    // ─── the steps ──────────────────────────────────────────────────────────────────────────────

    /**
     * A day's steps are not a journal row. They go to the per-source table that is merged by taking
     * the day's highest single count, which is the only safe arithmetic between a phone and a watch.
     */
    @Test
    fun stepsGoToTheirOwnTableAndNotTheJournal() {
        val read = rows(StepDayRecord(LocalDate.parse("2026-03-01"), 9312))

        assertTrue(read.entries.isEmpty())
        val day = read.stepDays.single()
        assertEquals("2026-03-01", day.day)
        assertEquals(9_312L, day.steps)
        assertEquals("Withings", day.sourceLabel)
        assertEquals("${FILE_IMPORT_MARKER_PREFIX}Withings", day.sourceId)
    }

    /**
     * Several of these exports state the same day twice — a Samsung archive overlaps its own monthly
     * files. Two rows for one day and one source would not fit the table's own key.
     */
    @Test
    fun aDayStatedTwiceBecomesOneRowAtItsLargerCount() {
        val days = rows(
            StepDayRecord(LocalDate.parse("2026-03-01"), 4000),
            StepDayRecord(LocalDate.parse("2026-03-01"), 9312),
        ).stepDays

        assertEquals(1, days.size)
        assertEquals(9_312L, days.single().steps)
    }

    @Test
    fun aDayOfNoStepsIsNotARow() {
        assertTrue(rows(StepDayRecord(LocalDate.parse("2026-03-01"), 0)).stepDays.isEmpty())
    }

    // ─── the marker ─────────────────────────────────────────────────────────────────────────────

    /**
     * The marker is what makes the import idempotent, and it is built out of what the fact *is*
     * rather than where it sat in the file — an export taken a month later holds the same night with
     * another hundred rows above it.
     */
    @Test
    fun theSameFactCarriesTheSameMarkerTwice() {
        val once = entries(SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z")))
        val again = entries(
            SleepRecord(at("2026-02-01T21:00:00Z"), at("2026-02-02T05:00:00Z")),
            SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z")),
        )

        assertEquals(once.single().healthConnectRecordKey, again.last().healthConnectRecordKey)
    }

    /**
     * It shares its prefix with the workout import, which is what lets one "already here" query and
     * one tombstone cover both: a row imported and then deleted by hand stays deleted.
     */
    @Test
    fun everyMarkerIsUnderTheOneImportPrefix() {
        val markers = entries(
            SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z")),
            WeightRecord(at("2026-03-01T07:30:00Z"), 82.4),
            VitalRecord(at("2026-03-01T07:30:00Z"), Vital.HEART_RATE, 58.0),
            WorkoutRecord(at("2026-03-01T07:30:00Z"), "Walk", 1200),
            PeriodRecord(LocalDate.parse("2026-03-01"), start = true),
            DrinkRecord(at("2026-03-01T07:30:00Z"), KIND_WATER, 500.0),
            NutritionRecord(at("2026-03-01T07:30:00Z"), "Porridge", "Breakfast", 480.0),
        ).mapNotNull(JournalEntry::healthConnectRecordKey)

        assertEquals(7, markers.size)
        assertTrue(markers.all { it.startsWith(FILE_IMPORT_MARKER_PREFIX) })
        // Distinct, or one fact would silently stand in for another and the second would never land.
        assertEquals(markers.size, markers.toSet().size)
    }

    /**
     * A weighing and a pulse taken by the same scale in the same millisecond are two facts, and a
     * marker naming only the instant would file the second under the first and drop it.
     */
    @Test
    fun twoFactsAtOneInstantAreTwoMarkers() {
        val markers = entries(
            WeightRecord(at("2026-03-01T07:30:00Z"), 82.4),
            VitalRecord(at("2026-03-01T07:30:00Z"), Vital.HEART_RATE, 58.0),
            VitalRecord(at("2026-03-01T07:30:00Z"), Vital.BODY_FAT, 18.2),
        ).mapNotNull(JournalEntry::healthConnectRecordKey)

        assertEquals(3, markers.toSet().size)
    }

    /**
     * A diary holds the same yoghurt at breakfast and at supper on one day, and both at the same
     * stated minute where the diary files a whole meal at once.
     */
    @Test
    fun oneFoodInTwoMealsIsTwoRows() {
        val markers = entries(
            NutritionRecord(at("2026-03-01T00:00:00Z"), "Yoghurt", "Breakfast", 120.0),
            NutritionRecord(at("2026-03-01T00:00:00Z"), "Yoghurt", "Dinner", 120.0),
        ).mapNotNull(JournalEntry::healthConnectRecordKey)

        assertEquals(2, markers.toSet().size)
    }

    /**
     * A diary that keeps no clock files the whole day at midday, so two coffees under one breakfast
     * and every glass of the day's water would share a marker, and the import would keep the first
     * and call the rest already here. They are rows of their own, named the same way by a later export of the
     * day — and the first keeps the name it had, so what already landed is still recognised.
     */
    @Test
    fun aRepeatTheFileGaveNoInstantForIsARowOfItsOwn() {
        val noon = at("2026-03-01T11:00:00Z")
        val coffee = NutritionRecord(noon, "Coffee", "Breakfast", 2.0, wallClock = true)
        val glass = DrinkRecord(noon, KIND_WATER, 250.0, wallClock = true)
        val walk = WorkoutRecord(noon, "Walking, 3.5 mph", seconds = 1200, wallClock = true)
        fun markers(vararg records: HealthRecord) = entries(*records).map(JournalEntry::healthConnectRecordKey)

        val read = markers(coffee, coffee, glass, glass, glass, walk, walk)
        assertEquals(7, read.toSet().size)
        assertEquals(read, markers(coffee, coffee, glass, glass, glass, walk, walk))
        assertEquals(markers(coffee, glass, walk), listOf(read[0], read[2], read[5]))
    }

    /**
     * Where the file states the instant, two rows naming it are two sources recording one moment —
     * a watch and the app that copied its workout — and they keep one name for the import to fold.
     */
    @Test
    fun aRepeatAtAStatedInstantIsStillOneFact() {
        val run = WorkoutRecord(at("2026-03-01T07:30:00Z"), "Running", seconds = 1800)

        assertEquals(1, entries(run, run).map(JournalEntry::healthConnectRecordKey).toSet().size)
    }

    /** Two services that both hold the same night file it under two names, because it is two records. */
    @Test
    fun twoServicesHoldingOneNightAreNotOneRow() {
        val night = SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z"))
        val withings = HealthImportFile(HealthExportApp.WITHINGS, listOf(night)).rows(zone, titles)
        val oura = HealthImportFile(HealthExportApp.OURA, listOf(night)).rows(zone, titles)

        assertFalse(
            withings.entries.single().healthConnectRecordKey == oura.entries.single().healthConnectRecordKey,
        )
    }

    /** The source is named on the row, so the sources screen can say which of them wrote it. */
    @Test
    fun everyImportedRowNamesTheServiceThatWroteIt() {
        val row = HealthImportFile(
            HealthExportApp.APPLE_HEALTH,
            listOf(WeightRecord(at("2026-03-01T07:30:00Z"), 82.4)),
        ).rows(zone, titles).entries.single()

        assertEquals("Apple Health", row.measurementSource)
    }

    /** Every service names itself, and no two of them share a name the marker is built out of. */
    @Test
    fun theServiceNamesAreDistinct() {
        val labels = HealthExportApp.entries.map(HealthExportApp::label)

        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none(String::isBlank))
    }

    /** Every reader in the registry is for a different service, so the winner is never ambiguous. */
    @Test
    fun everyServiceHasAtMostOneReader() {
        val apps = HEALTH_EXPORT_READERS.map(HealthExportReader::app)

        assertEquals(apps.size, apps.toSet().size)
    }

    // ─── what the journal already holds ─────────────────────────────────────────────────────────

    private val night = SleepRecord(at("2026-03-01T21:00:00Z"), at("2026-03-02T05:30:00Z"))

    /** The same night as something other than a file import filed it: on its waking day, under no file marker. */
    private fun recordedNight(source: String) =
        entries(night).single().copy(id = 7, measurementSource = source, healthConnectRecordKey = null)

    /**
     * A marker knows only what a file filed. A night Health Connect brought, or one typed in by hand,
     * is found by its waking day instead, and the day's sleep is not counted twice. The weighing the
     * file states for the same morning is nobody's second night and still lands.
     */
    @Test
    fun aNightAlreadyMeasuredIsNotWrittenASecondTime() {
        val file = entries(night, WeightRecord(at("2026-03-02T06:00:00Z"), 82.4))

        listOf("HEALTH_CONNECT", "MANUAL").forEach { source ->
            val (fresh, guesses) = file.withoutWhatIsRecorded(listOf(recordedNight(source)))

            assertEquals(listOf("Body measurement"), fresh.map(JournalEntry::title))
            assertTrue(guesses.isEmpty())
        }
    }

    /** A night guessed from screen use is not a measurement: it gives way to the one the file states. */
    @Test
    fun aGuessedNightGivesWayToTheImportedOne() {
        val guess = recordedNight(PHONE_ESTIMATE)

        val (fresh, guesses) = entries(night).withoutWhatIsRecorded(listOf(guess))

        assertEquals(entries(night), fresh)
        assertEquals(listOf(guess), guesses)
    }

    /** A night and the nap after lunch end on one day and are both the file's; the guess goes once. */
    @Test
    fun aNightAndANapFromOneFileBothLand() {
        val nap = SleepRecord(at("2026-03-02T12:00:00Z"), at("2026-03-02T12:40:00Z"))
        val guess = recordedNight(PHONE_ESTIMATE)

        val (fresh, guesses) = entries(night, nap).withoutWhatIsRecorded(listOf(guess))

        assertEquals(2, fresh.size)
        assertEquals(listOf(guess), guesses)
    }

    /**
     * A day holds one period start and one end — the cycle screen's own rule — so a start already
     * tapped there keeps its row, and the end the file states for another day is still new.
     */
    @Test
    fun aPeriodDayAlreadyMarkedKeepsTheRowItHas() {
        val file = entries(
            PeriodRecord(LocalDate.parse("2026-03-01"), start = true),
            PeriodRecord(LocalDate.parse("2026-03-05"), start = false),
        )
        val tapped = file.first().copy(id = 3, measurementSource = null, healthConnectRecordKey = null)

        val (fresh, guesses) = file.withoutWhatIsRecorded(listOf(tapped))

        assertEquals(listOf(KIND_CYCLE_END), fresh.map(JournalEntry::kind))
        assertTrue(guesses.isEmpty())
    }

    /**
     * A file that brought only days of steps writes no row, so nothing that follows a row's write
     * redraws anything: the step, week, energy and level widgets would stand at the old count until
     * the pedometer next wrote. A run that added rows ends by redrawing them all, once the recalculation
     * inside its transaction has committed.
     */
    @Test
    fun aFileOfStepDaysAloneStillRedrawsTheWidgetsThatReadSteps() {
        fun outcome(added: Int, stepDays: Int) = HealthImportOutcome("Fitbit", added, alreadyHeld = 0, stepDays)

        assertTrue(outcome(added = 0, stepDays = 30).redrawsStepsAlone())
        assertFalse(outcome(added = 4, stepDays = 30).redrawsStepsAlone())
        assertFalse(outcome(added = 0, stepDays = 0).redrawsStepsAlone())
        val source = java.io.File("src/main/java/com/pwnall/health/HealthFileImport.kt").readText()
        assertTrue("outcome.redrawsStepsAlone() -> refreshWidgetsAfterWrite(WIDGETS_OF_STEPS)" in source)
    }
}
