package com.pwnall.health

import kotlin.math.exp

/**
 * One storey, in metres. Building codes put a residential floor between 2.7 m and 3.3 m, and the
 * wristbands that report "floors" have settled on three, so a figure from this app is comparable
 * with the one a person is used to seeing.
 */
const val METRES_PER_FLOOR = 3.0

/** Whole storeys only: 6.9 m of climb is two floors, not two and a bit. */
fun floorsClimbed(ascentM: Double): Int = (ascentM / METRES_PER_FLOOR).toInt()

/**
 * Counts the climb in a stream of barometric altitudes.
 *
 * Altitude rather than pressure, because the conversion is `SensorManager.getAltitude` and this
 * class has to run in a unit test. Its absolute value is wrong by whatever the weather is doing —
 * the standard atmosphere is a fiction — but the *difference* over a minute is a real measurement,
 * and a difference is all a floor is.
 *
 * Telling a climb from the weather is one half of the problem, and telling it from a ride is the
 * other. Air pressure wanders several metres' worth over an afternoon, so a rise counts only when
 * it arrives at the speed a person climbs at: complete within [Config.maxClimbSeconds] and no
 * faster than [Config.maxClimbRateMps]. And a lift, an escalator or a car up a hill raises the
 * phone exactly as a staircase does, so the barometer alone can never separate them — what
 * separates them is that stairs are *walked* up. The accelerometer sees that and the barometer
 * cannot, which is why every sample arrives with the motion gate's verdict attached.
 */
class FloorTracker(private val config: Config = Config()) {

    /**
     * Starting points, not measurements. A barometer reads a metre or two differently between
     * phones and a stairwell door swinging shut is worth several metres of pressure on its own —
     * record a climb you counted yourself and retune here rather than editing the logic.
     */
    data class Config(
        /** Nothing shorter than a storey is a floor, and nothing shorter survives the weather. */
        val minClimbM: Double = METRES_PER_FLOOR,
        /**
         * Faster than a person climbs stairs. This is the guard against the barometer itself — a
         * pocket closing around the phone, a door swinging shut in a stairwell — which arrives as
         * ten metres inside a second. Riding is handled by [minGaitShare] instead, because a slow
         * lift moves at a perfectly human speed.
         */
        val maxClimbRateMps: Double = 1.0,
        /**
         * How much of a climb has to look like walking.
         *
         * Not all of it: the same climb contains the landing between two flights and the pause at
         * the top, and the smoothing lag puts the credit a second or so after the last step. Half
         * is comfortably above what standing in a lift produces, which is none.
         */
        val minGaitShare: Double = 0.5,
        /**
         * How long a climb may take before its starting height is treated as weather rather than a
         * landing. This is the drift guard: the anchor is never allowed to stand still long enough
         * for the afternoon to move it a storey.
         */
        val maxClimbSeconds: Double = 60.0,
        /**
         * The time constant of the exponential smoothing on the raw altitude — a barometer is noisy
         * at the metre — and zero for none. A time and not a share of each sample: at the 5 Hz
         * `SENSOR_DELAY_NORMAL` delivers, a sample moves the mean a fifth of the way, as a fixed fifth
         * would; but under a fixed fifth a sensor that delivers once a second would follow a step in the
         * pressure over five seconds, which is the pace of a staircase, and the first reading after the
         * processor had slept through the night's weather would be followed the same way.
         */
        val smoothingSeconds: Double = 0.9,
    )

    private var smoothedM: Double? = null
    private var smoothedAtNanos = 0L
    private var anchorM = 0.0
    private var anchorAtNanos = 0L
    private var samplesSinceAnchor = 0
    private var gaitSamplesSinceAnchor = 0

    /**
     * Metres of genuine climb this sample completed — zero for the many samples that complete none.
     *
     * [timestampNanos] is `SensorEvent.timestamp` and [altitudeM] the barometric altitude for it.
     * [walking] is the motion gate's verdict for the same instant: the body was taking strides, as
     * opposed to standing in something that was moving on its own.
     *
     * A non-finite [altitudeM] is not a reading and is ignored outright: it is neither smoothed nor
     * counted towards the anchor's tallies, so the share of the next climb that looks like walking
     * is still measured over samples that happened.
     */
    fun add(timestampNanos: Long, altitudeM: Double, walking: Boolean): Double {
        // The filter below feeds its own previous value back in, so a single non-finite reading —
        // getAltitude answers NaN for a non-positive pressure — would make every later altitude
        // non-finite too, and this tracker lives as long as the service: no floor would ever be
        // credited again.
        if (!altitudeM.isFinite()) return 0.0
        val previous = smoothedM
        val current = if (previous == null) {
            altitudeM
        } else {
            previous + followed(timestampNanos - smoothedAtNanos) * (altitudeM - previous)
        }
        smoothedM = current
        smoothedAtNanos = timestampNanos
        if (previous == null) {
            anchor(current, timestampNanos)
            return 0.0
        }
        samplesSinceAnchor += 1
        if (walking) gaitSamplesSinceAnchor += 1

        val rise = current - anchorM
        val seconds = (timestampNanos - anchorAtNanos) / 1e9
        return when {
            // Going down: the low point is where the next climb starts from.
            rise <= 0.0 -> {
                anchor(current, timestampNanos)
                0.0
            }

            rise >= config.minClimbM -> {
                val credited = if (climbed(rise, seconds)) rise else 0.0
                anchor(current, timestampNanos)
                credited
            }

            // The anchor has stood still long enough that what moved it is the weather.
            seconds > config.maxClimbSeconds -> {
                anchor(current, timestampNanos)
                0.0
            }

            else -> 0.0
        }
    }

    /** The share of the way to a reading [elapsedNanos] after the last one that the smoothed altitude moves. */
    private fun followed(elapsedNanos: Long): Double =
        if (config.smoothingSeconds <= 0.0) {
            1.0
        } else {
            1.0 - exp(-elapsedNanos.coerceAtLeast(0L) / 1e9 / config.smoothingSeconds)
        }

    /**
     * A rise of [rise] metres over [seconds]: legs, or something the person was merely standing in.
     *
     * Within [Config.maxClimbSeconds], as the class promises. A rise reached over a gap in the samples
     * is not one anybody saw climbed: with the processor asleep for the night, the first reading of the
     * morning is the night's weather, and its hours would make any rise slow enough to pass for stairs.
     */
    private fun climbed(rise: Double, seconds: Double): Boolean =
        seconds > 0.0 &&
            seconds <= config.maxClimbSeconds &&
            rise / seconds <= config.maxClimbRateMps &&
            gaitSamplesSinceAnchor >= samplesSinceAnchor * config.minGaitShare

    // Every climb is judged on its own evidence, so the walking tally starts again with the anchor.
    private fun anchor(altitudeM: Double, timestampNanos: Long) {
        anchorM = altitudeM
        anchorAtNanos = timestampNanos
        samplesSinceAnchor = 0
        gaitSamplesSinceAnchor = 0
    }
}
