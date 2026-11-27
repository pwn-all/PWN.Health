package com.pwnall.health

import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Body values answered in the onboarding questionnaire are recorded as a measurement of their own,
 * which is what takes them to Health Connect; one taken from Health Connect and left alone is not.
 */
class OnboardingBodyTest {
    @Test
    fun valuesHealthConnectNeverHeldAreRecorded() {
        val recorded = bodyWorthRecording(weightKg = 74.5, heightCm = 178, prefill = null)

        assertEquals(74.5, recorded?.weightKg!!, 0.001)
        assertEquals(178, recorded.heightCm)
    }

    @Test
    fun aValueTakenFromHealthConnectAndLeftAloneIsNotCopiedBack() {
        val prefill = HealthConnectBody(weightKg = 74.5, heightCm = 178)

        assertNull(bodyWorthRecording(weightKg = 74.5, heightCm = 178, prefill = prefill))
    }

    @Test
    fun aPrefillReEmittedAtTheWheelsResolutionStillCountsAsLeftAlone() {
        // Health Connect held 155 lb; the wheel shows 70.3 kg and hands back exactly that.
        val prefill = HealthConnectBody(weightKg = 70.30676, heightCm = 178)

        assertNull(bodyWorthRecording(weightKg = 70.3, heightCm = 178, prefill = prefill))
    }

    @Test
    fun aCorrectedValueIsRecordedAndTheUntouchedOneIsNot() {
        val prefill = HealthConnectBody(weightKg = 74.5, heightCm = 178)

        val recorded = bodyWorthRecording(weightKg = 72.0, heightCm = 178, prefill = prefill)

        assertEquals(72.0, recorded?.weightKg!!, 0.001)
        assertNull(recorded.heightCm)
    }

    @Test
    fun aHalfEmptyHealthConnectStillGetsTheMissingHalf() {
        val prefill = HealthConnectBody(weightKg = 74.5, heightCm = null)

        val recorded = bodyWorthRecording(weightKg = 74.5, heightCm = 178, prefill = prefill)

        assertNull(recorded?.weightKg)
        assertEquals(178, recorded?.heightCm)
    }

    /**
     * The rulers open on 70 kg and 170 cm because a ruler has to stand somewhere. Left there, the
     * figure is nobody's answer, so it is not logged as a measurement taken by hand, which would carry
     * it from the journal into the charts, the doctor's report and Health Connect.
     */
    @Test
    fun aRulerNobodyMovedRecordsNothing() {
        assertNull(bodyWorthRecording(weightKg = null, heightCm = null, prefill = null))
        assertNull(bodyWorthRecording(weightKg = null, heightCm = null, prefill = HealthConnectBody(74.5, 178)))

        val recorded = bodyWorthRecording(weightKg = null, heightCm = 178, prefill = null)

        assertNull(recorded?.weightKg)
        assertEquals(178, recorded?.heightCm)
    }

    /**
     * The wheels start from the phone: its measurement setting where it has one, else the country,
     * of which only four answer in pounds; the pages' switch corrects either. A country read as blank
     * must not throw, because a phone can be set to a language with no region at all.
     */
    @Test
    fun onlyThePoundCountriesStartTheWheelsInPounds() {
        listOf("US", "GB", "LR", "MM", "us", "gb").forEach {
            assertEquals(it, "IMPERIAL", localeUnitSystem(it))
        }
        listOf("RU", "DE", "JP", "CA", "AU", "").forEach {
            assertEquals(it, "METRIC", localeUnitSystem(it))
        }
    }

    /**
     * The measurement is recorded after the profile: recorded before it, a profile that failed to
     * save would leave it behind, and the run through the questions that followed would record it
     * again.
     */
    @Test
    fun aProfileThatFailsToSaveLeavesNoMeasurementForTheNextRunToRepeat() {
        var logged = false
        val finish = runCatching {
            runBlocking {
                finishOnboarding(saveProfile = { throw IOException("disk full") }, logBody = { logged = true })
            }
        }
        assertTrue(finish.isFailure)
        assertFalse(logged)
    }

    /** The saved profile swaps the screen out and cancels its scope; the measurement still follows it. */
    @Test
    fun theMeasurementIsRecordedAfterTheProfileEvenOnceTheScreenIsGone() = runBlocking {
        val writes = mutableListOf<String>()
        val finish = launch {
            val screen = coroutineContext.job
            finishOnboarding(
                saveProfile = {
                    writes += "profile"
                    screen.cancel()
                    yield()
                },
                logBody = {
                    yield()
                    writes += "measurement"
                },
            )
        }
        finish.join()
        assertEquals(listOf("profile", "measurement"), writes)
    }

    /** The phone's own measurement setting beats the country in both directions. */
    @Test
    fun thePhonesMeasurementSettingBeatsTheCountry() {
        assertEquals("METRIC", localeUnitSystem("US", "metric"))
        assertEquals("IMPERIAL", localeUnitSystem("RU", "ussystem"))
        assertEquals("IMPERIAL", localeUnitSystem("DE", "uksystem"))
        assertEquals("IMPERIAL", localeUnitSystem("US", "unknown"))
    }
}
