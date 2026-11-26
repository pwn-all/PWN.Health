package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthRecordsTest {
    private fun row(at: Long, vararg sets: Pair<Int, Double>, code: String = "BENCH_PRESS") = JournalEntry(
        day = "2026-11-01",
        occurredAt = at,
        kind = "ACTIVITY",
        title = "Bench",
        calories = 0.0,
        activityCode = code,
        measurementPayloadJson = sets.map { (reps, kg) ->
            PlannedSet(reps = reps.toString(), load = if (kg > 0) kg.toString() else "", completed = true)
        }.encodeToJson(),
    )

    private fun set(reps: Int, kg: Double) = StrengthSet(kg, reps, 0L)

    // --- the estimate ----------------------------------------------------------------------------

    @Test
    fun epleyIsTheWeightForOneRepAndGrowsWithReps() {
        assertEquals(100.0, epleyE1rm(100.0, 1)!!, 1e-9)
        assertEquals(116.6667, epleyE1rm(100.0, 5)!!, 1e-3)
        assertEquals(140.0, epleyE1rm(100.0, 12)!!, 1e-9)
    }

    /** Past a dozen reps every formula drifts; no estimate beats a confident wrong one. */
    @Test
    fun noEstimateWithoutWeightOrPastTwelveReps() {
        assertNull(epleyE1rm(100.0, 13))
        assertNull(epleyE1rm(0.0, 5))
        assertNull(epleyE1rm(100.0, 0))
    }

    // --- reading the journal ---------------------------------------------------------------------

    @Test
    fun aSessionRowIsReadSetBySet() {
        val sets = strengthSetsOf(row(1L, 5 to 100.0, 3 to 110.0))
        assertEquals(listOf(5 to 100.0, 3 to 110.0), sets.map { it.reps to it.loadKg })
    }

    /** The plain form keeps sets × reps at one weight in its columns: that many identical sets. */
    @Test
    fun aFormRowIsThatManyIdenticalSets() {
        val form = JournalEntry(
            day = "2026-11-01",
            occurredAt = 5L,
            kind = "ACTIVITY",
            title = "Squat",
            calories = 0.0,
            activityCode = "SQUAT",
            sets = 3,
            reps = 8,
            loadKg = 80.0,
        )
        assertEquals(List(3) { 8 to 80.0 }, strengthSetsOf(form).map { it.reps to it.loadKg })
    }

    @Test
    fun theRecordsAreTheLargestFiguresAndTiesGoToTheFirst() {
        val records = exerciseRecords(
            listOf(
                row(1L, 5 to 100.0, 5 to 100.0),
                row(2L, 1 to 120.0),
                row(3L, 8 to 100.0),
                row(4L, 1 to 120.0),
            ),
        )
        assertNotNull(records)
        assertEquals(120.0, records!!.heaviest!!.loadKg, 1e-9)
        assertEquals(2L, records.heaviest.occurredAt)
        // 8 × 100 is 126.7 by Epley, above the 120 single.
        assertEquals(126.6667, records.e1rmKg!!, 1e-3)
        assertEquals(1_000.0, records.bestVolumeKg, 1e-9)
        assertNull(records.mostReps)
    }

    // --- what a session breaks -------------------------------------------------------------------

    /** With no history every set would be a record, and a badge on everything says nothing. */
    @Test
    fun aFirstSessionBreaksNothing() {
        assertEquals(emptyList<BrokenRecord>(), brokenRecords("BENCH_PRESS", null, listOf(set(5, 100.0))))
        assertEquals(emptySet<Int>(), recordSetIndices(null, listOf(set(5, 100.0))))
    }

    @Test
    fun aHeavierSetBreaksTheEstimateAndTheWeightAndIsTheSetMarked() {
        val before = exerciseRecords(listOf(row(1L, 5 to 100.0, 5 to 100.0)))
        val session = listOf(set(5, 100.0), set(5, 105.0), set(3, 105.0))

        val kinds = brokenRecords("BENCH_PRESS", before, session).map { it.kind }.toSet()

        assertEquals(setOf(RecordKind.E1RM, RecordKind.HEAVIEST, RecordKind.VOLUME), kinds)
        // The first set at the new weight holds both the estimate and the weight.
        assertEquals(setOf(1), recordSetIndices(before, session))
    }

    /** The same weight typed again is not a record, and neither is a rounding step above it. */
    @Test
    fun matchingTheOldBestIsNotARecord() {
        val before = exerciseRecords(listOf(row(1L, 5 to 100.0)))
        assertEquals(emptyList<BrokenRecord>(), brokenRecords("BENCH_PRESS", before, listOf(set(5, 100.0))))
        assertEquals(emptyList<BrokenRecord>(), brokenRecords("BENCH_PRESS", before, listOf(set(5, 100.004))))
    }

    /**
     * An imperial row stores exact kilograms — 135 lb is 61.235 kg — and the records read that
     * number. Read through the one-decimal text of a set box it would be 61.2 kg, 134.9 lb, and the
     * same 135 lb lifted a week later would be a record over itself.
     */
    @Test
    fun theSameWeightInPoundsIsNotARecordOverItself() {
        val lastWeek = row(1L).copy(
            measurementPayloadJson = listOf(PlannedSet(reps = "5", load = "135", completed = true))
                .encodeToJson(loadUnitFactor = POUNDS_IN_A_KILOGRAM),
        )
        val before = exerciseRecords(listOf(lastWeek))

        assertEquals(135.0, before!!.heaviest!!.loadKg * POUNDS_IN_A_KILOGRAM, 1e-6)
        assertEquals(
            emptyList<BrokenRecord>(),
            brokenRecords("BENCH_PRESS", before, listOf(set(5, 135 / POUNDS_IN_A_KILOGRAM))),
        )
    }

    /** A set answered with nought reps was an attempt at the weight: no record, and no badge on it. */
    @Test
    fun aWeightThatDidNotGoUpIsNotTheHeaviest() {
        val before = exerciseRecords(listOf(row(1L, 5 to 100.0)))
        val session = listOf(set(0, 120.0), set(5, 105.0))

        assertEquals(emptyList<BrokenRecord>(), brokenRecords("BENCH_PRESS", before, listOf(set(0, 120.0))))
        val heaviest = brokenRecords("BENCH_PRESS", before, session).single { it.kind == RecordKind.HEAVIEST }
        assertEquals(105.0, heaviest.value, 1e-9)
        assertEquals(setOf(1), recordSetIndices(before, session))
    }

    /** Without added weight the record is the most reps in a set. */
    @Test
    fun bodyweightSetsKeepARepsRecord() {
        val before = exerciseRecords(listOf(row(1L, 10 to 0.0, 12 to 0.0, code = "PULL_UP")))
        val session = listOf(set(11, 0.0), set(13, 0.0))

        val broken = brokenRecords("PULL_UP", before, session)

        assertEquals(listOf(RecordKind.REPS), broken.map { it.kind })
        assertEquals(13.0, broken.single().value, 1e-9)
        assertEquals(12.0, broken.single().was, 1e-9)
        assertEquals(setOf(1), recordSetIndices(before, session))
    }

    /** More total weight on lighter sets is a volume record and marks no single set. */
    @Test
    fun volumeAloneMarksNoSet() {
        val before = exerciseRecords(listOf(row(1L, 5 to 100.0)))
        val session = listOf(set(5, 80.0), set(5, 80.0))

        assertEquals(listOf(RecordKind.VOLUME), brokenRecords("BENCH_PRESS", before, session).map { it.kind })
        assertTrue(recordSetIndices(before, session).isEmpty())
    }

    /**
     * A session that came back to the exercise after another is saved as a row for each stretch of it,
     * and the journal's volume is one row's. Summed over both stretches, the summary would announce a
     * record no row held, and the next session would be measured against the old figure all the same.
     */
    @Test
    fun aVolumeRecordIsOneRowsWhenTheSessionCameBackToTheExercise() {
        val before = exerciseRecords(listOf(row(1L, 5 to 100.0, 5 to 100.0, 5 to 100.0)))
        val first = List(3) { set(5, 100.0) }
        val again = List(2) { set(5, 100.0) }
        val longer = List(4) { set(5, 100.0) }

        val whole = brokenRecords("BENCH_PRESS", before, first + again)
        val byRow = brokenRecords("BENCH_PRESS", before, first + again, listOf(first, again))
        val volume = brokenRecords("BENCH_PRESS", before, again + longer, listOf(again, longer)).single()

        assertEquals(listOf(RecordKind.VOLUME), whole.map { it.kind })
        assertEquals(emptyList<BrokenRecord>(), byRow)
        assertEquals(RecordKind.VOLUME, volume.kind)
        assertEquals(2_000.0, volume.value, 1e-9)
        assertEquals(1_500.0, volume.was, 1e-9)
    }
}
