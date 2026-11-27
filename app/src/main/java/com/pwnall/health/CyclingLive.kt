package com.pwnall.health

data class CyclingLiveMeasurement(
    val powerWatts: Int? = null,
    val cadenceRpm: Double? = null,
    val cumulativeWheelRevolutions: Long? = null,
    val cumulativeCrankRevolutions: Int? = null,
)

/** Derives cadence only from two sensor events; wheel circumference is never guessed. */
internal class CyclingMeasurementAccumulator {
    private var live = CyclingLiveMeasurement()
    private var previousCscCrank: CounterEvent? = null
    private var previousPowerCrank: CounterEvent? = null

    fun update(measurement: CyclingSpeedCadenceMeasurement): CyclingLiveMeasurement {
        val current = measurement.crankEvent()
        val cadence = cadence(previousCscCrank, current)
        if (current != null) previousCscCrank = current.repeating(previousCscCrank)
        live = live.copy(
            cadenceRpm = cadence ?: live.cadenceRpm,
            cumulativeWheelRevolutions = measurement.cumulativeWheelRevolutions
                ?: live.cumulativeWheelRevolutions,
            cumulativeCrankRevolutions = measurement.cumulativeCrankRevolutions
                ?: live.cumulativeCrankRevolutions,
        )
        return live
    }

    fun update(measurement: CyclingPowerMeasurement): CyclingLiveMeasurement {
        val current = measurement.crankEvent()
        val cadence = cadence(previousPowerCrank, current)
        if (current != null) previousPowerCrank = current.repeating(previousPowerCrank)
        live = live.copy(
            powerWatts = measurement.instantaneousPowerWatts,
            cadenceRpm = cadence ?: live.cadenceRpm,
            cumulativeWheelRevolutions = measurement.cumulativeWheelRevolutions
                ?: live.cumulativeWheelRevolutions,
            cumulativeCrankRevolutions = measurement.cumulativeCrankRevolutions
                ?: live.cumulativeCrankRevolutions,
        )
        return live
    }

    private fun CyclingSpeedCadenceMeasurement.crankEvent(): CounterEvent? {
        val revolutions = cumulativeCrankRevolutions ?: return null
        val ticks = lastCrankEventTimeTicks ?: return null
        return CounterEvent(revolutions, ticks)
    }

    private fun CyclingPowerMeasurement.crankEvent(): CounterEvent? {
        val revolutions = cumulativeCrankRevolutions ?: return null
        val ticks = lastCrankEventTimeTicks ?: return null
        return CounterEvent(revolutions, ticks)
    }

    private fun cadence(previous: CounterEvent?, current: CounterEvent?): Double? {
        if (previous == null || current == null) return null
        val revolutionDelta = unsigned16Delta(current.revolutions, previous.revolutions)
        val tickDelta = unsigned16Delta(current.ticks, previous.ticks)
        // A sensor resends its last crank event until a new one happens, so an identical event says
        // nothing about the current cadence — only a run of them means the rider has stopped.
        if (revolutionDelta == 0 && tickDelta == 0) {
            return if (previous.repeats >= STOPPED_AFTER_REPEATS) 0.0 else null
        }
        if (tickDelta == 0) return null
        if (revolutionDelta == 0) return 0.0
        return (revolutionDelta * 60.0 * CRANK_TICKS_PER_SECOND / tickDelta)
            .takeIf { it in 0.0..MAX_CADENCE_RPM }
    }

    private fun CounterEvent.repeating(previous: CounterEvent?): CounterEvent =
        if (previous != null && revolutions == previous.revolutions && ticks == previous.ticks) {
            copy(repeats = previous.repeats + 1)
        } else {
            this
        }

    private fun unsigned16Delta(current: Int, previous: Int): Int =
        (current - previous) and UINT16_MASK

    /** [repeats] counts how many times the sensor has already resent this same event. */
    private data class CounterEvent(val revolutions: Int, val ticks: Int, val repeats: Int = 0)

    private companion object {
        const val CRANK_TICKS_PER_SECOND = 1_024
        const val UINT16_MASK = 0xFFFF
        const val MAX_CADENCE_RPM = 300.0
        const val STOPPED_AFTER_REPEATS = 3
    }
}
