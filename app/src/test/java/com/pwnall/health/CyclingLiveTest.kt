package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CyclingLiveTest {
    @Test
    fun derivesCadenceAcrossCounterAndClockRollover() {
        val accumulator = CyclingMeasurementAccumulator()
        val first = accumulator.update(
            CyclingSpeedCadenceMeasurement(
                cumulativeCrankRevolutions = 0xFFFF,
                lastCrankEventTimeTicks = 65_000,
            ),
        )
        assertNull(first.cadenceRpm)

        val second = accumulator.update(
            CyclingSpeedCadenceMeasurement(
                cumulativeCrankRevolutions = 1,
                lastCrankEventTimeTicks = 488,
            ),
        )

        assertEquals(120.0, second.cadenceRpm!!, 0.001)
    }

    @Test
    fun mergesPowerAndCscWithoutInventingWheelCircumference() {
        val accumulator = CyclingMeasurementAccumulator()
        accumulator.update(
            CyclingSpeedCadenceMeasurement(cumulativeWheelRevolutions = 42),
        )
        val live = accumulator.update(CyclingPowerMeasurement(instantaneousPowerWatts = 275))

        assertEquals(42L, live.cumulativeWheelRevolutions)
        assertEquals(275, live.powerWatts)
        assertNull(live.cadenceRpm)
    }

    @Test
    fun rejectsImpossibleCadenceButKeepsCurrentPower() {
        val accumulator = CyclingMeasurementAccumulator()
        accumulator.update(
            CyclingPowerMeasurement(
                instantaneousPowerWatts = 200,
                cumulativeCrankRevolutions = 10,
                lastCrankEventTimeTicks = 100,
            ),
        )
        val live = accumulator.update(
            CyclingPowerMeasurement(
                instantaneousPowerWatts = 210,
                cumulativeCrankRevolutions = 20,
                lastCrankEventTimeTicks = 110,
            ),
        )

        assertEquals(210, live.powerWatts)
        assertNull(live.cadenceRpm)
    }

    /**
     * A sensor notifies on a fixed interval and resends the last crank event when no new one fell
     * inside it. Reading that repeat as zero cadence would make the live readout flicker between the real
     * value and 0 several times a minute at any cadence below one revolution per interval.
     */
    @Test
    fun holdsCadenceWhileTheSameCrankEventIsResentAndZeroesItOnlyOnceTheRiderHasStopped() {
        val accumulator = CyclingMeasurementAccumulator()
        accumulator.update(crankEvent(10, 1_024))
        assertEquals(60.0, accumulator.update(crankEvent(11, 2_048)).cadenceRpm!!, 0.001)

        repeat(3) {
            assertEquals(60.0, accumulator.update(crankEvent(11, 2_048)).cadenceRpm!!, 0.001)
        }

        assertEquals(0.0, accumulator.update(crankEvent(11, 2_048)).cadenceRpm!!, 0.001)
    }

    private fun crankEvent(revolutions: Int, ticks: Int) = CyclingSpeedCadenceMeasurement(
        cumulativeCrankRevolutions = revolutions,
        lastCrankEventTimeTicks = ticks,
    )
}
