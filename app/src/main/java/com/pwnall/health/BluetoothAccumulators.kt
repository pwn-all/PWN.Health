package com.pwnall.health

/**
 * Pairs the two consecutive indications used when a BCS value does not fit in one ATT payload.
 *
 * A first half whose second never comes — the link dropped between them — goes with the connection,
 * and is not handed over alone as [GlucoseRecordAccumulator.drainPending] hands over a glucose
 * reading: a glucose measurement is whole without its context, while the first indication of a body
 * composition can be its body fat and nothing else, and the scale never had the second confirmed.
 */
internal class BodyCompositionAccumulator {
    private var pending: BodyCompositionMeasurement? = null

    fun update(measurement: BodyCompositionMeasurement): BodyCompositionMeasurement? {
        if (!measurement.multiplePacket) {
            pending = null
            return measurement
        }
        val first = pending
        if (first == null || !first.matches(measurement)) {
            pending = measurement
            return null
        }
        pending = null
        return first.merge(measurement).copy(multiplePacket = false)
    }

    private fun BodyCompositionMeasurement.matches(other: BodyCompositionMeasurement): Boolean =
        // BCS 1.0.1: timestamp and User ID are first-indication-only, while the mandatory Body Fat
        // Percentage is repeated in both indications. Requiring those identity fields in part two
        // would reject every conforming multipart measurement.
        other.timestamp == null &&
            !other.userIdPresent &&
            bodyFatPercent == other.bodyFatPercent &&
            imperialSource == other.imperialSource &&
            compatible(basalMetabolismKj, other.basalMetabolismKj) &&
            compatible(musclePercent, other.musclePercent) &&
            compatible(muscleMassKg, other.muscleMassKg) &&
            compatible(fatFreeMassKg, other.fatFreeMassKg) &&
            compatible(softLeanMassKg, other.softLeanMassKg) &&
            compatible(bodyWaterMassKg, other.bodyWaterMassKg) &&
            compatible(impedanceOhms, other.impedanceOhms) &&
            compatible(weightKg, other.weightKg) &&
            compatible(heightCm, other.heightCm)

    private fun compatible(first: Number?, second: Number?): Boolean =
        first == null || second == null || first.toDouble() == second.toDouble()

    private fun BodyCompositionMeasurement.merge(
        other: BodyCompositionMeasurement,
    ): BodyCompositionMeasurement = copy(
        basalMetabolismKj = basalMetabolismKj ?: other.basalMetabolismKj,
        musclePercent = musclePercent ?: other.musclePercent,
        muscleMassKg = muscleMassKg ?: other.muscleMassKg,
        fatFreeMassKg = fatFreeMassKg ?: other.fatFreeMassKg,
        softLeanMassKg = softLeanMassKg ?: other.softLeanMassKg,
        bodyWaterMassKg = bodyWaterMassKg ?: other.bodyWaterMassKg,
        impedanceOhms = impedanceOhms ?: other.impedanceOhms,
        weightKg = weightKg ?: other.weightKg,
        heightCm = heightCm ?: other.heightCm,
    )
}

/** Matches optional GLS context notifications by their protocol sequence number. */
internal class GlucoseRecordAccumulator {
    private val waitingForContext = linkedMapOf<Int, GlucoseMeasurement>()
    private var lastTimeOffsetMinutes: Int? = null

    val hasPendingContext: Boolean get() = waitingForContext.isNotEmpty()

    fun reset() {
        waitingForContext.clear()
        lastTimeOffsetMinutes = null
    }

    fun update(measurement: GlucoseMeasurement): GlucoseRecord? {
        measurement.timeOffsetMinutes?.let { lastTimeOffsetMinutes = it }
        val normalized = if (measurement.timeOffsetMinutes == null && lastTimeOffsetMinutes != null) {
            measurement.copy(timeOffsetMinutes = lastTimeOffsetMinutes)
        } else {
            measurement
        }
        if (!normalized.contextFollows) return GlucoseRecord(normalized, null)
        waitingForContext[normalized.sequenceNumber] = normalized
        // A meter that sends its whole history before any context pushes the oldest measurement out
        // of the buffer. Its context can no longer be paired, but the reading itself is a reading:
        // it is handed over without one rather than dropped on the floor.
        var evicted: GlucoseMeasurement? = null
        while (waitingForContext.size > MAX_PENDING_RECORDS) {
            evicted = waitingForContext.remove(waitingForContext.keys.first())
        }
        return evicted?.let { GlucoseRecord(it, null) }
    }

    fun update(context: GlucoseMeasurementContext): GlucoseRecord? {
        val measurement = waitingForContext.remove(context.sequenceNumber) ?: return null
        return BluetoothGlucose.matchContext(measurement, context)
    }

    /**
     * The RACP procedure is over and nothing more is coming. A measurement still waiting for its
     * context is a reading whose context the meter never sent; it goes out without one, exactly as
     * a measurement pushed out of the buffer does, rather than voiding the whole transfer over it.
     */
    fun drainPending(): List<GlucoseRecord> {
        val orphaned = waitingForContext.values.map { GlucoseRecord(it, null) }
        waitingForContext.clear()
        return orphaned
    }

    private companion object {
        const val MAX_PENDING_RECORDS = 8
    }
}
