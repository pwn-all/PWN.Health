package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recorded walk burned: by the pace, the walker's height and weight, and the slope, with the
 * person's own rest taken back out. An 80 kg walker of 180 cm resting at 74 kcal an hour throughout.
 */
class WalkCaloriesTest {
    /** A fix a second for [seconds] at [speed] m/s, the barometer rising [grade] of the ground covered. */
    private fun stretch(
        seconds: Int,
        speed: Double,
        grade: Double = 0.0,
        movement: RouteMovement = RouteMovement.WALKING,
        from: Long = 0,
        segmentStart: Boolean = false,
    ) = List(seconds + 1) { second ->
        TrackPointEntity(
            trackId = 1,
            timestamp = from + second * 1_000L,
            lat = 0.0,
            lon = 0.0,
            altitudeM = null,
            barometricAltitudeM = (300.0 + grade * speed * second).toFloat(),
            accuracyM = 5f,
            speedMps = speed.toFloat(),
            movementType = movement.name,
            provider = "gps",
            accepted = true,
            segmentStart = segmentStart && second == 0,
        )
    }

    private fun kcal(points: List<TrackPointEntity>, heightCm: Int = 180) = walkCalories(points, 80.0, heightCm, 74.0)

    /** One kilometre at [kmh] and [grade]. */
    private fun kilometre(kmh: Double, grade: Double = 0.0, movement: RouteMovement = RouteMovement.WALKING) =
        kcal(stretch((3_600 / kmh).toInt(), kmh / 3.6, grade, movement))

    @Test
    fun anHourAtFiveKilometresAnHourIsTheHeightWeightSpeedEquationLessRest() {
        // 3.85 + 5.97 × 1.389² / 1.8 = 10.25 ml/kg/min → 246 kcal gross, 74 of it rest.
        assertEquals(172.0, kcal(stretch(3_600, 5 / 3.6)), 1.0)
    }

    @Test
    fun theSamePaceIsCheaperOnLongerLegs() {
        val walk = stretch(1_800, 1.4)
        assertTrue(kcal(walk, heightCm = 190) < kcal(walk, heightCm = 160))
    }

    @Test
    fun aKilometreCostsMoreTheFasterItIsWalked() {
        assertTrue(kilometre(6.0) > kilometre(5.0))
        assertTrue(kilometre(5.0) > kilometre(4.0))
    }

    @Test
    fun aClimbCostsMoreAGentleDescentLessAndASteepOneMoreAgain() {
        val level = kilometre(5.0)
        val up = kilometre(5.0, grade = 0.10)
        val gentleDown = kilometre(5.0, grade = -0.10)
        val steepDown = kilometre(5.0, grade = -0.35)
        assertTrue("up $up, level $level", up > level * 2)
        assertTrue("gentle down $gentleDown, level $level", gentleDown < level * 0.6)
        assertTrue("steep down $steepDown, gentle $gentleDown", steepDown > gentleDown * 1.5)
    }

    /**
     * A steady 2 % rises a metre in each fifty, under the barometer's 1.5 m step: if each fifty
     * started again from where it ended, a whole hill of it would be charged as level ground.
     */
    @Test
    fun aGentleSlopeIsChargedThoughNoFiftyMetresOfItClearsTheStep() {
        val level = kilometre(5.0)
        val up = kilometre(5.0, grade = 0.02)
        val down = kilometre(5.0, grade = -0.02)
        assertTrue("up $up, level $level", up > level * 1.15)
        assertTrue("down $down, level $level", down < level * 0.9)
    }

    @Test
    fun aRunningKilometreCostsAboutOneKilocalorieAKilogram() {
        val run = kilometre(10.0, movement = RouteMovement.RUNNING)
        assertEquals(81.0, run, 2.0)
        assertTrue(run > kilometre(5.0) * 2)
    }

    @Test
    fun aRunUphillCostsMoreThanOnTheLevel() {
        assertTrue(kilometre(10.0, 0.08, RouteMovement.RUNNING) > kilometre(10.0, movement = RouteMovement.RUNNING))
    }

    @Test
    fun aRideAStopAndTheGapOfAPauseCostNothing() {
        assertEquals(0.0, kcal(stretch(600, 12.0, grade = 0.05, movement = RouteMovement.VEHICLE)), 1e-9)
        assertEquals(0.0, kcal(stretch(600, 0.0, movement = RouteMovement.STOPPED)), 1e-9)
        val before = stretch(600, 1.4)
        // Resumed an hour later and a hundred metres higher: neither the hour nor the climb is walked.
        val after = stretch(600, 1.4, from = 4_200_000, segmentStart = true).map {
            it.copy(barometricAltitudeM = it.barometricAltitudeM!! + 100f)
        }
        assertEquals(kcal(before) * 2, kcal(before + after), 0.5)
    }

    @Test
    fun noWeightNoEstimate() {
        assertEquals(0.0, walkCalories(stretch(600, 1.4), 0.0, 180, 74.0), 0.0)
    }
}
