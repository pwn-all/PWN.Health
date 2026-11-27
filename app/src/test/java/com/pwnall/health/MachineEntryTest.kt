package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A training machine's numbers taken on the workouts page, where no exercise is chosen: the machine
 * says what it was ([machineEntry]), and the row is filed under the catalogue's exercise for it.
 */
class MachineEntryTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")

    private fun taken(type: FitnessMachineType, kph: Double?) = machineEntry(
        NamedResources(),
        profile,
        MachineResult(minutes = 30.0, kcal = null, note = "", payloadJson = "", type = type, kph = kph),
    )

    /**
     * A treadmill at a walking pace is a walk and at a running one a run; one that gave no speed is
     * taken for a run. A bike is a ride, and a rower — or a monitor that never says what it sits on,
     * most of which sit on rowers — is the rowing machine.
     */
    @Test
    fun eachMachineIsFiledUnderItsOwnExerciseAndATreadmillByItsPace() {
        assertEquals("WALK", taken(FitnessMachineType.TREADMILL, 5.0).activityCode)
        assertEquals("RUN", taken(FitnessMachineType.TREADMILL, 10.0).activityCode)
        assertEquals("RUN", taken(FitnessMachineType.TREADMILL, null).activityCode)
        assertEquals("CYCLING", taken(FitnessMachineType.INDOOR_BIKE, 25.0).activityCode)
        assertEquals("ROWING_MACHINE", taken(FitnessMachineType.ROWER, null).activityCode)
        assertEquals("ROWING_MACHINE", taken(FitnessMachineType.UNKNOWN, null).activityCode)
    }

    /** A console that sent no frame and counted no energy leaves the row the exercise's own estimate. */
    @Test
    fun aMachineWithoutAFrameOrAnEnergyCountIsEstimated() {
        val walk = taken(FitnessMachineType.TREADMILL, 5.0)

        assertNull(walk.measurementPayloadJson)
        assertTrue(walk.calories > 0.0)
        assertTrue(walk.calories < taken(FitnessMachineType.TREADMILL, 10.0).calories)
    }
}
