package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The row a tap on Save writes — see [journalEntry] — and which form a workout gets and when a
 * correction may be saved ([workoutTyped], [savedRowLoaded]).
 *
 * The rule: a form writes what it shows, and what it does not show it keeps.
 */
class EditorJournalEntryTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 11, 3)
    private val bench = ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }
    private val plank = ACTIVITY_CATALOG.first { it.code == "PLANK" }

    /** The form as it opens on [kind]: nothing typed, the wheels where a profile puts them. */
    private fun form(kind: String, mode: String = "WEIGHT") = EditorForm(
        kind = kind,
        note = "",
        food = FoodState(),
        chatFoods = emptyList(),
        foodPicked = null,
        activityCode = bench.code,
        spec = bench,
        specTitle = "Bench press",
        typedWorkout = false,
        duration = "",
        sets = "",
        reps = "",
        load = "",
        loadUnitFactor = 1.0,
        workoutCalories = 0.0,
        workoutSetsJson = "",
        machineKcal = null,
        machinePayload = "",
        measurementMode = mode,
        measurementTitle = "Measurement",
        weightKg = 72.0,
        heightCm = 178,
        heartRateBpm = 72,
        sleepStartMinute = 23 * 60,
        sleepEndMinute = 7 * 60,
        girthCm = emptyMap(),
        touchedGirth = emptySet(),
        measurementSource = "MANUAL",
        bodyFatPercent = null,
    )

    private fun at(date: LocalDate, hour: Int): Long = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** A saved row with something in every column a form could drop. */
    private fun saved(kind: String) = JournalEntry(
        id = 41,
        day = "2026-10-30",
        occurredAt = at(LocalDate.of(2026, 10, 30), 7),
        kind = kind,
        title = "As it was",
        subtitle = "before",
        calories = 0.0,
        measurementWeightKg = 81.5,
        measurementHeightCm = 183,
        measurementHeartRateBpm = 58,
        sleepStartAt = at(LocalDate.of(2026, 10, 29), 23),
        sleepEndAt = at(LocalDate.of(2026, 10, 30), 7),
        measurementSource = "HEALTH_CONNECT",
        measurementPayloadJson = PRESSURE,
        moodScore = 4,
        energyScore = 2,
        breathCycles = 12,
        bodyFatPercent = 18.5,
        healthConnectRecordKey = "com.example.scale:41",
        caffeineMg = 80.0,
    )

    private fun EditorForm.saves(previous: JournalEntry?) = journalEntry(previous, day, LocalTime.NOON, zone)

    private fun JournalEntry.column(name: String): Any? =
        JournalEntry::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun JournalEntry.readings(): Map<String, Any?> = mapOf(
        "weight" to measurementWeightKg,
        "height" to measurementHeightCm,
        "pulse" to measurementHeartRateBpm,
        "night" to (sleepStartAt to sleepEndAt),
    )

    private fun JournalEntry.girth(vital: Vital): Double = JSONObject(measurementPayloadJson!!).getDouble(vital.key)

    /**
     * Every form, opened on a saved row and saved without a touch: the columns no form shows come
     * back as they were, and so does every reading the open tab does not own. Left out of the save,
     * they would be reset by every save: a scale's body fat, a mood's score and the payload that is
     * the whole of a blood pressure.
     */
    @Test
    fun aSaveKeepsWhatItsFormDoesNotShow() {
        val forms = listOf("EAT" to "WEIGHT", "ACTIVITY" to "WEIGHT") + TABS.keys.map { "MEASUREMENT" to it }
        forms.forEach { (kind, mode) ->
            val before = saved(kind)
            val after = form(kind, mode).saves(before)
            val said = "$kind/$mode"

            assertEquals(said, before.id, after.id)
            KEPT.forEach { column ->
                assertNotNull("the fixture leaves $column empty", before.column(column))
                assertEquals("$said keeps $column", before.column(column), after.column(column))
            }
            // A workout's payload is its own sets or a machine's frame; every other form keeps it.
            if (kind != "ACTIVITY") assertEquals(said, before.measurementPayloadJson, after.measurementPayloadJson)
            val owned = TABS.getValue(mode).takeIf { kind == "MEASUREMENT" }.orEmpty()
            assertEquals(
                "$said keeps the readings its tab does not own",
                before.readings().filterKeys { it !in owned },
                after.readings().filterKeys { it !in owned },
            )
        }
    }

    /**
     * Every column of the row is filed: written by a form, kept from the saved row, or stamped by
     * the write door. A column added to [JournalEntry] fails here until it is given a place in
     * [journalEntry] and in one of these lists — left out of both, every correction would reset it.
     */
    @Test
    fun everyColumnOfTheRowIsFiled() {
        val columns = JournalEntry::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        // The two the write door stamps: when the row was last changed, and the clock it was written on.
        assertEquals(WRITTEN + KEPT + "editedAt" + "zoneOffsetSeconds", columns)
    }

    /**
     * The body row onboarding writes holds a weight and a height. The Pulse tab over it must not
     * rewrite it as a 72 bpm pulse nobody measured, which would take the two readings out of Room and
     * Health Connect.
     */
    @Test
    fun thePulseTabOverTheBodyRowLeavesItsWeightAndHeight() {
        val body = saved("MEASUREMENT").copy(measurementHeartRateBpm = null, sleepStartAt = null, sleepEndAt = null)

        val after = form("MEASUREMENT", "HEART_RATE").saves(body)

        assertEquals(81.5, after.measurementWeightKg!!, 0.0)
        assertEquals(183, after.measurementHeightCm)
        assertEquals(72, after.measurementHeartRateBpm)
    }

    @Test
    fun aNewRowIsOfTheDayItIsRecordedFor() {
        val row = form("MEASUREMENT", "WEIGHT").saves(null)

        assertEquals(0L, row.id)
        assertEquals("2026-11-03", row.day)
        assertEquals(at(day, 12), row.occurredAt)
        assertEquals("Measurement", row.title)
        assertEquals(72.0, row.measurementWeightKg!!, 0.0)
        assertNull(row.measurementHeightCm)
        assertEquals("MANUAL", row.measurementSource)
    }

    /** A night belongs to the morning: a new one ends on the day recorded, a corrected one where it ended. */
    @Test
    fun aNightIsFiledUnderTheDayItEndedOn() {
        val night = form("MEASUREMENT", "SLEEP").saves(null)

        assertEquals(at(day.minusDays(1), 23), night.sleepStartAt)
        assertEquals(at(day, 7), night.sleepEndAt)
        assertEquals("2026-11-03", night.day)
        assertEquals(night.sleepEndAt, night.occurredAt)

        val corrected = form("MEASUREMENT", "SLEEP").copy(sleepEndMinute = 8 * 60).saves(saved("MEASUREMENT"))

        assertEquals(at(LocalDate.of(2026, 10, 30), 8), corrected.sleepEndAt)
        assertEquals("2026-10-30", corrected.day)
        assertEquals(81.5, corrected.measurementWeightKg!!, 0.0)
    }

    /**
     * A saved tape is corrected in the form that wrote it. The girths nobody moved stay as they
     * were stored — the wheel shows whole centimetres, and an imported 82.5 is not its to round.
     */
    @Test
    fun aCorrectedTapeKeepsTheGirthsNobodyMoved() {
        val tape = saved("MEASUREMENT").copy(
            measurementPayloadJson = """{"schema":1,"waistCircumferenceCm":82.5,"hipCircumferenceCm":96}""",
            bodyFatPercent = null,
        )
        val opened = form("MEASUREMENT", "GIRTH").copy(girthCm = tape.girthCm())

        val noted = opened.copy(note = "after a run").saves(tape)
        val hipMoved = opened.copy(girthCm = opened.girthCm + (Vital.HIP to 98), touchedGirth = setOf(Vital.HIP))
        val moved = hipMoved.saves(tape)

        assertEquals("after a run", noted.note)
        assertEquals(82.5, noted.girth(Vital.WAIST), 0.0)
        assertEquals(96.0, noted.girth(Vital.HIP), 0.0)
        assertEquals(82.5, moved.girth(Vital.WAIST), 0.0)
        assertEquals(98.0, moved.girth(Vital.HIP), 0.0)
        assertEquals(81.5, moved.measurementWeightKg!!, 0.0)
    }

    /**
     * A workout written down after it happened has no session to take its sets from: what was typed
     * is the record. In a session's own form the same boxes are the last set's, and a hold has none.
     */
    @Test
    fun aWorkoutThatIsOverIsSavedAsItWasTyped() {
        val boxes = form("ACTIVITY").copy(
            activityCode = plank.code,
            spec = plank,
            specTitle = "Plank",
            duration = "40",
            sets = "3",
            reps = "10",
            load = "50",
        )

        val told = boxes.copy(typedWorkout = true).saves(null)
        val run = boxes.saves(null)

        assertEquals("Plank", told.title)
        assertEquals(40.0, told.durationMinutes!!, 0.0)
        assertEquals(3, told.sets)
        assertEquals(10, told.reps)
        assertEquals(50.0, told.loadKg!!, 0.0)
        assertEquals(plank.met, told.met!!, 0.0)
        assertNull(run.sets)
        assertNull(run.reps)
        assertNull(run.loadKg)
    }

    /**
     * A synced session is drawn with a catalogue exercise it is not, and none of that exercise may
     * become the row: its code is what keeps it out of the export that would duplicate it.
     */
    @Test
    fun anImportedSessionKeepsWhatItArrivedWith() {
        val synced = saved("ACTIVITY").copy(
            title = "Morning Run",
            calories = 310.0,
            activityCode = "HEALTH_CONNECT",
            met = 9.8,
            durationMinutes = 30.0,
            reps = 4_200,
            measurementPayloadJson = null,
        )

        val after = form("ACTIVITY").copy(
            activityCode = "HEALTH_CONNECT",
            spec = plank,
            specTitle = "Plank",
            typedWorkout = true,
            duration = "32",
            sets = "3",
            reps = "10",
            workoutCalories = 95.0,
        ).saves(synced)

        assertEquals("HEALTH_CONNECT", after.activityCode)
        assertEquals("Morning Run", after.title)
        assertEquals("before", after.subtitle)
        assertEquals(310.0, after.calories, 0.0)
        assertEquals(9.8, after.met!!, 0.0)
        assertNull(after.sets)
        assertEquals(4_200, after.reps)
        assertEquals(32.0, after.durationMinutes!!, 0.0)
        assertEquals("HEALTH_CONNECT", after.measurementSource)
    }

    /** A meal is stored for the portion, whichever basis its figures were typed on. */
    @Test
    fun aCorrectedMealIsWrittenForItsPortion() {
        val soup = FoodState(title = " Soup ", serving = "300", protein = "2", fat = "1", carbs = "5")

        val meal = form("EAT").copy(food = soup, note = " late ").saves(saved("EAT"))

        assertEquals("Soup", meal.title)
        assertEquals("late", meal.note)
        assertEquals(300.0, meal.servingG, 0.0)
        assertEquals(6.0, meal.proteinG, 1e-9)
        assertEquals(soup.portionCalories, meal.calories, 0.0)
        assertTrue(meal.enteredPer100g)
        assertNull(meal.fiberG)
        assertNull(meal.foodSource)
        assertNull(meal.activityCode)
    }

    /**
     * A session times its sets between two taps, which for a workout that is already over is a few
     * seconds: yesterday's forty minutes of bench press would be saved as twenty seconds. What is over is
     * typed — a new workout for another day, and a saved row that holds no sets to correct.
     */
    @Test
    fun aWorkoutThatIsOverIsTypedAndNotRun() {
        val typedRow = saved("ACTIVITY").copy(measurementPayloadJson = null)
        val sessionRow = typedRow.copy(measurementPayloadJson = """{"sets":[{"reps":"10"}]}""")

        assertFalse(workoutTyped(reported = false, counted = false, anotherDay = false, saved = null))
        assertTrue(workoutTyped(reported = false, counted = false, anotherDay = true, saved = null))
        assertTrue(workoutTyped(reported = false, counted = false, anotherDay = false, saved = typedRow))
        // A session's row is corrected set by set, and the phone-counted exercises have their own form.
        assertFalse(workoutTyped(reported = false, counted = false, anotherDay = false, saved = sessionRow))
        assertFalse(workoutTyped(reported = false, counted = true, anotherDay = true, saved = typedRow))
        // What the chat was told is typed whatever the exercise.
        assertTrue(workoutTyped(reported = true, counted = true, anotherDay = false, saved = null))
        assertFalse(workoutTyped(reported = false, counted = false, anotherDay = false, saved = saved("EAT")))
    }

    /**
     * "No sets" is asked of what the row's payload holds: a console's frame and a sensor's figures
     * stand in that column too, and neither may reopen as an empty session with no Save. An imported
     * session is typed whatever the fallback exercise the form is handed for it would be.
     */
    @Test
    fun aMachinesRideAndAnImportedSessionAreTypedToo() {
        val row = saved("ACTIVITY")
        val machineRow = row.copy(measurementPayloadJson = """{"machineType":"BIKE","watts":[120,140]}""")
        val sensorRow = row.copy(measurementPayloadJson = """{"schema":1,"cadence":[80,82]}""")
        val sessionRow = row.copy(measurementPayloadJson = """{"sets":[{"reps":10}]}""")

        assertTrue(workoutTyped(reported = false, counted = false, anotherDay = false, saved = machineRow))
        assertTrue(workoutTyped(reported = false, counted = false, anotherDay = false, saved = sensorRow))
        assertFalse(workoutTyped(reported = false, counted = false, anotherDay = false, saved = sessionRow))
        assertTrue(
            workoutTyped(reported = false, counted = true, anotherDay = false, saved = sessionRow, imported = true),
        )
    }

    /**
     * The frame is what tells the re-costing pass the energy was the console's ([hasMachineEnergy]).
     * A count typed over a frame that reported none would be re-costed as the MET estimate at the next
     * weigh-in, and an emptied box would leave an estimate that the pass then never touched.
     */
    @Test
    fun aMachinesFrameSaysWhetherItsRowsEnergyWasMeasured() {
        val ride = form("ACTIVITY").copy(
            duration = "30",
            workoutCalories = 180.0,
            typedWorkout = true,
            machinePayload = """{"schema":1,"machineType":"INDOOR_BIKE","powerWatts":140}""",
        )

        val typed = ride.copy(machineKcal = 230).saves(null)
        val emptied = ride.copy(
            machinePayload = """{"schema":1,"machineType":"INDOOR_BIKE","totalEnergyKcal":250}""",
        ).saves(null)

        assertEquals(230.0, typed.calories, 0.0)
        assertTrue(typed.hasMachineEnergy())
        assertEquals(140, JSONObject(typed.measurementPayloadJson!!).getInt("powerWatts"))
        assertEquals(180.0, emptied.calories, 0.0)
        assertFalse(emptied.hasMachineEnergy())
    }

    /** Every girth a ruler offers is one its row reads back: a hip of 35 saved is not dropped. */
    @Test
    fun everyGirthARulerOffersIsReadBack() {
        GIRTH_VITAL_ORDER.forEach { vital ->
            girthRulerCm(vital).forEach { cm ->
                val payload = girthPayload(null, mapOf(vital to cm), setOf(vital))
                val tape = saved("MEASUREMENT").copy(measurementPayloadJson = payload)
                assertEquals("${vital.name} $cm", cm, tape.girthCm()[vital])
            }
        }
    }

    /**
     * The fields come back from a recreation before the row does. A Save in between would find nothing
     * to correct and write the correction as a new row dated today.
     */
    @Test
    fun aCorrectionWaitsForTheRowItCorrects() {
        assertTrue(savedRowLoaded(entryId = null, loaded = null))
        assertFalse(savedRowLoaded(entryId = 41L, loaded = null))
        assertTrue(savedRowLoaded(entryId = 41L, loaded = saved("EAT")))
    }

    private companion object {
        const val PRESSURE = """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}"""

        /** The measurement form's tabs, the body row's "BOTH" among them, and the readings each owns. */
        val TABS = mapOf(
            "WEIGHT" to setOf("weight"),
            "HEIGHT" to setOf("height"),
            "BOTH" to setOf("weight", "height"),
            "HEART_RATE" to setOf("pulse"),
            "SLEEP" to setOf("night"),
            "GIRTH" to emptySet<String>(),
        )

        /** The columns no form on this screen shows: a save hands them back as it found them. */
        val KEPT = setOf(
            "healthConnectRecordKey",
            "moodScore",
            "energyScore",
            "breathCycles",
            "bodyFatPercent",
            "caffeineMg",
        )

        /** The columns some form writes; the readings and the payload only by the form that shows them. */
        val WRITTEN = setOf(
            "id", "day", "occurredAt", "kind", "title", "subtitle", "note", "calories",
            "proteinG", "fatG", "carbsG", "servingG", "fiberG", "sugarG", "saturatedFatG", "sodiumMg",
            "cholesterolMg", "enteredPer100g", "foodSource",
            "activityCode", "met", "durationMinutes", "sets", "reps", "loadKg",
            "measurementWeightKg", "measurementHeightCm", "measurementHeartRateBpm", "sleepStartAt",
            "sleepEndAt", "measurementSource", "measurementPayloadJson",
        )
    }
}
