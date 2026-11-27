package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The rows a new session is written as — see [sessionRowsOf]: a superset's two exercises, and a
 * session that moved on to another one, each a row of its own that does not overlap the next.
 */
class SessionRowsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val bench = ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }
    private val curl = ACTIVITY_CATALOG.first { it.code == "BICEPS_CURL" }

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The row the form builds for the session's own exercise, as it does before Save splits it. */
    private fun formRow(occurredAt: Long, day: String) = JournalEntry(
        id = 0,
        day = day,
        occurredAt = occurredAt,
        kind = "ACTIVITY",
        title = "Bench press",
        calories = 40.0,
        activityCode = bench.code,
        met = bench.met,
        durationMinutes = 1.0,
        sets = 2,
        reps = 20,
        loadKg = 45.36,
        healthConnectRecordKey = "com.example.watch:9",
    )

    /** Two rounds: bench at 100 and curls at 50, in the profile's unit, a minute's rest after each round. */
    private val superset = List(2) {
        listOf(
            PlannedSet(reps = "10", load = "100", completed = true, actualSeconds = 30),
            PlannedSet(
                reps = "12",
                load = "50",
                completed = true,
                actualSeconds = 40,
                actualRestSeconds = 60,
                second = true,
            ),
        )
    }.flatten()

    private fun JournalEntry.split(plan: List<PlannedSet>, partner: ActivitySpec?, loadUnitFactor: Double = 1.0) =
        sessionRowsOf(
            plan = plan,
            spec = bench,
            partner = partner,
            loadUnitFactor = loadUnitFactor,
            bodyKg = 80.0,
            restingKcalPerHour = 70.0,
            titleOf = { it.code },
            zone = zone,
        )

    private fun JournalEntry.firstLoadKg(): Double =
        JSONObject(measurementPayloadJson!!).getJSONArray("sets").getJSONObject(0).getDouble("loadKg")

    /**
     * The curls end at the finish and the bench ends where they began: run backwards from their
     * stamps the two halves meet and do not overlap. Each names the other on the day's list, and
     * only the form's own row may stand for a record a watch sent.
     */
    @Test
    fun aSupersetIsTwoRowsOneAfterTheOther() {
        val finish = at(LocalDate.of(2026, 11, 3), 18, 0)

        val (rowA, rowB) = formRow(finish, "2026-11-03").split(superset, curl)

        assertEquals(finish, rowB.occurredAt)
        assertEquals(200.0 / 60, rowB.durationMinutes!!, 1e-9)
        assertEquals(finish - 200_000, rowA.occurredAt)
        assertEquals(curl.code, rowB.activityCode)
        assertEquals(curl.code, rowB.title)
        assertEquals(curl.met, rowB.met!!, 0.0)
        assertEquals(2, rowB.sets)
        assertEquals(24, rowB.reps)
        assertTrue(rowB.calories > 0)
        assertEquals(0L, rowB.id)
        assertNull(rowB.healthConnectRecordKey)
        assertEquals(curl.code, supersetPartnerCode(rowA.measurementPayloadJson))
        assertEquals(bench.code, supersetPartnerCode(rowB.measurementPayloadJson))
        // Each half's payload holds its own sets and no others.
        assertEquals(2, decodeSetsFromJson(rowA.measurementPayloadJson).size)
        assertTrue(decodeSetsFromJson(rowB.measurementPayloadJson).all { it.second })
    }

    /** Pounds in the boxes, kilograms in the rows: the second half is divided as the first one is. */
    @Test
    fun bothHalvesOfAnImperialSupersetAreStoredInKilograms() {
        val rows = formRow(at(LocalDate.of(2026, 11, 3), 18, 0), "2026-11-03")
            .split(superset, curl, loadUnitFactor = POUNDS_IN_A_KILOGRAM)

        assertEquals(100 / POUNDS_IN_A_KILOGRAM, rows[0].firstLoadKg(), 1e-9)
        assertEquals(50 / POUNDS_IN_A_KILOGRAM, rows[1].firstLoadKg(), 1e-9)
        assertEquals(50 / POUNDS_IN_A_KILOGRAM, rows[1].loadKg!!, 1e-9)
    }

    /** A pair finished just after midnight began the day before: the first half is filed under it. */
    @Test
    fun theFirstHalfOfAPairAcrossMidnightIsOfThePreviousDay() {
        val finish = at(LocalDate.of(2026, 11, 4), 0, 1)

        val (rowA, rowB) = formRow(finish, "2026-11-04").split(superset, curl)

        assertEquals("2026-11-03", rowA.day)
        assertEquals("2026-11-04", rowB.day)
    }

    /**
     * Bench, then curls taken up as a further exercise: a row each, back to back, the later at the
     * finish. Not a pair, so neither says it was paired with anything.
     */
    @Test
    fun aSessionThatMovedOnIsARowPerExercise() {
        val finish = at(LocalDate.of(2026, 11, 3), 18, 0)
        val plan = listOf(
            PlannedSet(reps = "10", load = "100", completed = true, actualSeconds = 30, actualRestSeconds = 90),
            PlannedSet(reps = "12", load = "20", completed = true, actualSeconds = 60, exerciseCode = curl.code),
            PlannedSet(reps = "12", load = "20", exerciseCode = curl.code),
        )

        val (benchRow, curlRow) = formRow(finish, "2026-11-03").split(plan, partner = null)

        assertEquals(listOf(bench.code, curl.code), listOf(benchRow.activityCode, curlRow.activityCode))
        assertEquals(finish, curlRow.occurredAt)
        assertEquals(finish - 60_000, benchRow.occurredAt)
        assertEquals(1, curlRow.sets)
        assertEquals(20.0, curlRow.loadKg!!, 1e-9)
        assertEquals(100.0, benchRow.loadKg!!, 1e-9)
        assertNull(supersetPartnerCode(benchRow.measurementPayloadJson))
        assertNull(supersetPartnerCode(curlRow.measurementPayloadJson))
        assertNull(benchRow.healthConnectRecordKey)
    }

    /**
     * Bench, curls, then the bench again: three rows in the order done and back to back, the return a
     * row of its own rather than folded into the first stretch, each keeping the rest it ended on.
     */
    @Test
    fun aReturnToTheFirstExerciseIsARowOfItsOwn() {
        val finish = at(LocalDate.of(2026, 11, 3), 18, 0)
        val plan = listOf(
            PlannedSet(reps = "10", load = "100", completed = true, actualSeconds = 30, actualRestSeconds = 60),
            PlannedSet(
                reps = "12",
                load = "20",
                completed = true,
                actualSeconds = 40,
                actualRestSeconds = 20,
                exerciseCode = curl.code,
            ),
            PlannedSet(reps = "8", load = "100", completed = true, actualSeconds = 30, exerciseCode = bench.code),
        )

        val rows = formRow(finish, "2026-11-03").split(plan, partner = null)

        assertEquals(listOf(bench.code, curl.code, bench.code), rows.map { it.activityCode })
        assertEquals(listOf(finish - 90_000, finish - 30_000, finish), rows.map { it.occurredAt })
        assertEquals(listOf(10, 12, 8), rows.map { it.reps })
        assertEquals(60, decodeSetsFromJson(rows[0].measurementPayloadJson).single().actualRestSeconds)
        assertTrue(rows.all { decodeSetsFromJson(it.measurementPayloadJson).single().exerciseCode == null })
    }

    /** One exercise, or a pair whose second half was never done: the form's row is the whole of it. */
    @Test
    fun aSessionOfOneExerciseIsTheFormsOwnRow() {
        val row = formRow(at(LocalDate.of(2026, 11, 3), 18, 0), "2026-11-03")
        val benchOnly = superset.filterNot { it.second }

        assertSame(row, row.split(benchOnly, partner = null).single())
        assertSame(row, row.split(benchOnly + PlannedSet(second = true), curl).single())
    }
}
