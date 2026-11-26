package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Both conversions are exact by definition, so both are pinned exactly. A mile quietly drifting by
 * a metre would not look like a bug — it would look like a route that reads 3.11 mi this release
 * and 3.12 the next, with nothing on screen to say which one was wrong.
 */
class UnitsTest {
    @Test
    fun metresBecomeKilometresOrMiles() {
        assertEquals(1.0, distanceValue(1_000.0, imperial = false), 1e-9)
        assertEquals(3.42, distanceValue(3_420.0, imperial = false), 1e-9)
        assertEquals(1.0, distanceValue(1_609.344, imperial = true), 1e-9)
        assertEquals(0.0, distanceValue(0.0, imperial = true), 1e-9)
    }

    @Test
    fun millilitresBecomeLitresOrFluidOunces() {
        assertEquals(1.0, volumeValue(1_000.0, imperial = false), 1e-9)
        assertEquals(33.814, volumeValue(1_000.0, imperial = true), 0.001)
        // The other end of the same definition: a US gallon is 128 fluid ounces, not 133 imperial
        // ones, and a conversion that used the British ounce would land here instead.
        assertEquals(128.0, volumeValue(3_785.411784, imperial = true), 1e-9)
    }

    @Test
    fun theWaterCardKeepsOneDecimalAndDropsAWholeNumbersAltogether() {
        assertEquals("2", formatVolumeNumber(2_000.0, imperial = false, Locale.US))
        assertEquals("1.5", formatVolumeNumber(1_500.0, imperial = false, Locale.US))
        assertEquals("33.8", formatVolumeNumber(1_000.0, imperial = true, Locale.US))
        assertEquals("16.9", formatVolumeNumber(500.0, imperial = true, Locale.US))
    }

    /**
     * A climb is feet, not miles: the ground is measured in one unit and the height above it in
     * another, which is the pairing every trail sign in the country asking for these units uses.
     */
    @Test
    fun metresBecomeMetresOrFeet() {
        assertEquals(120.0, heightValue(120.0, imperial = false), 1e-9)
        assertEquals(3.280839895013123, heightValue(1.0, imperial = true), 1e-9)
        assertEquals(328.0839895013123, heightValue(100.0, imperial = true), 1e-9)
        assertEquals(0.0, heightValue(0.0, imperial = true), 1e-9)
    }

    /**
     * The three fixed points of the scale, because an offset applied in the wrong order is the one
     * mistake here that still looks like a temperature: 37 °C read out as 66.6 °F would be noticed,
     * and 37 °C read out as 98.6 °F is the whole of what this has to get right.
     */
    @Test
    fun celsiusBecomesCelsiusOrFahrenheit() {
        assertEquals(36.6, temperatureValue(36.6, imperial = false), 1e-9)
        assertEquals(32.0, temperatureValue(0.0, imperial = true), 1e-9)
        assertEquals(98.6, temperatureValue(37.0, imperial = true), 1e-9)
        assertEquals(212.0, temperatureValue(100.0, imperial = true), 1e-9)
        // The fever threshold and the two hypothermia edges the vitals card prints as band edges.
        assertEquals(100.4, temperatureValue(38.0, imperial = true), 1e-9)
        assertEquals(95.0, temperatureValue(35.0, imperial = true), 1e-9)
        assertEquals(89.6, temperatureValue(32.0, imperial = true), 1e-9)
    }

    /**
     * The hydration threshold, which is not a portion anybody pours: 2,500 ml is not a round number
     * of anything imperial, so it is printed as the conversion it is rather than as a tidier figure
     * nearby. [WaterVolume] carries it to the advice card, which reads out this "84.5 fl oz" — and,
     * on a metric profile, the millilitres [formatVolume] prints from the stored integer instead.
     */
    @Test
    fun theAdviceThresholdConvertsRatherThanRounding() {
        assertEquals("84.5", formatVolumeNumber(2_500.0, imperial = true, Locale.US))
        assertEquals("2.5", formatVolumeNumber(2_500.0, imperial = false, Locale.US))
    }

    /**
     * A portion keeps the round metric number it is stored as, and its imperial twin is the honest
     * conversion of that number rather than a tidier one nearby. Inventing an 8 fl oz glass would
     * log 237 ml where 250 was drunk, and the stored figure is millilitres either way.
     */
    @Test
    fun theWaterPortionsConvertRatherThanRounding() {
        assertEquals(listOf(100, 330, 400, 500), WATER_PORTIONS_ML)
        assertEquals(
            listOf("3.4", "11.2", "13.5", "16.9"),
            WATER_PORTIONS_ML.map { formatVolumeNumber(it.toDouble(), imperial = true, Locale.US) },
        )
    }

    /** Anything that is not the stored spelling is metric, including the empty profile default. */
    @Test
    fun onlyTheStoredSpellingReadsAsImperial() {
        val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1990-04-17")
        assertFalse(profile.imperial)
        assertFalse(profile.copy(unitSystem = "imperial").imperial)
        assertTrue(profile.copy(unitSystem = "IMPERIAL").imperial)
    }
}
