package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the measurement form may save, when it offers its tabs, which saved rows it opens on and
 * where its dial opens for a night told to the chat — see [measurementValid],
 * [measurementTabsOffered], [measurementFormShows] and [chatNight].
 */
class MeasurementFormTest {
    /** The form as it opens: the profile's weight and height on the wheels, 23:00 to 07:00 on the dial. */
    private fun valid(
        mode: String,
        weightKg: Double = 72.0,
        heightCm: Int = 178,
        heartRateBpm: Int = 72,
        sleepMinutes: Int = 480,
        chatSleepDuration: String = "",
        girthMeasured: Boolean = false,
    ) = measurementValid(mode, weightKg, heightCm, heartRateBpm, sleepMinutes, chatSleepDuration, girthMeasured)

    /**
     * The tape is not judged as the body row is, by the profile's weight and height: Save would light
     * over three rulers reading "Not measured" and write a measurement with no value in it.
     */
    @Test
    fun aTapeNobodyMovedHasNothingToSave() {
        assertFalse(valid("GIRTH"))
        assertTrue(valid("GIRTH", girthMeasured = true))
    }

    /** A measured girth is the whole of it: the wheels of another tab do not hold a tape's Save back. */
    @Test
    fun aTapeIsNotJudgedByTheWeightOrTheHeight() {
        assertTrue(valid("GIRTH", weightKg = 0.0, heightCm = 0, girthMeasured = true))
    }

    @Test
    fun eachOtherTabIsJudgedByItsOwnControl() {
        assertTrue(valid("WEIGHT", heightCm = 0))
        assertFalse(valid("WEIGHT", weightKg = 19.9))
        assertTrue(valid("WEIGHT", weightKg = 20.0))
        assertTrue(valid("WEIGHT", weightKg = 400.0))
        assertFalse(valid("WEIGHT", weightKg = 400.1))
        assertTrue(valid("HEIGHT", weightKg = 0.0))
        assertFalse(valid("HEIGHT", heightCm = 251))
        assertTrue(valid("HEART_RATE"))
        assertFalse(valid("HEART_RATE", heartRateBpm = 29))
        assertTrue(valid("SLEEP"))
        assertFalse(valid("SLEEP", sleepMinutes = 0))
    }

    /** The night a chat named has to be the night on the dial before it is saved as that night. */
    @Test
    fun aNightSaidInTheChatMustMatchTheDial() {
        assertTrue(valid("SLEEP", chatSleepDuration = "480"))
        assertFalse(valid("SLEEP", chatSleepDuration = "450"))
    }

    /**
     * And while it is not, the form says what the dial shows, rather than a dark Save over the two
     * with nothing on the page to say they disagree. The line and the button ask one question.
     */
    @Test
    fun aLengthTheDialDoesNotShowIsNamedAndNotOnlyRefused() {
        assertEquals(480, sleepLengthMismatch(dialMinutes = 480, chatSleepDuration = "450"))
        assertNull(sleepLengthMismatch(dialMinutes = 480, chatSleepDuration = "480"))
        assertNull(sleepLengthMismatch(dialMinutes = 480, chatSleepDuration = ""))
        // Whole minutes, as Save compares them: "480." on the way to another figure is no disagreement.
        assertNull(sleepLengthMismatch(dialMinutes = 480, chatSleepDuration = "480."))
        listOf("", "450", "480", "480.").forEach { typed ->
            assertEquals(typed, sleepLengthMismatch(480, typed) == null, valid("SLEEP", chatSleepDuration = typed))
        }
    }

    /** The body row onboarding writes carries both readings, and both have to stand. */
    @Test
    fun theBodyRowNeedsBothItsReadings() {
        assertTrue(valid("BOTH"))
        assertFalse(valid("BOTH", heightCm = 0))
        assertFalse(valid("BOTH", weightKg = 0.0))
    }

    /**
     * A saved row is corrected as what it is. The strip over one must not turn a weighing into a
     * night dated today, or write the slider's untouched 72 beside the weight.
     */
    @Test
    fun onlyANewEntryIsOfferedTheTabs() {
        assertTrue(measurementTabsOffered(entryId = null))
        assertFalse(measurementTabsOffered(entryId = 7L))
    }

    private fun saved(payload: String? = null, bodyFatPercent: Double? = null) = JournalEntry(
        id = 7,
        day = "2026-11-03",
        occurredAt = 0L,
        kind = "MEASUREMENT",
        title = "Measurement",
        calories = 0.0,
        measurementPayloadJson = payload,
        bodyFatPercent = bodyFatPercent,
    )

    /**
     * A waist saved here reopens on the form, not as a card saying it was logged with a single tap:
     * the tape's girths live in the payload, so a payload alone does not make a row one the form cannot
     * show.
     */
    @Test
    fun aSavedTapeReopensInTheFormThatWroteIt() {
        assertTrue(measurementFormShows(saved("""{"waistCircumferenceCm":82,"schema":1}""")))
        assertTrue(measurementFormShows(saved("""{"schema":1,"hipCircumferenceCm":96,"chestCircumferenceCm":101}""")))
        assertTrue(measurementFormShows(saved()))
        assertTrue(measurementFormShows(null))
    }

    /** What the form has no field for it may not be handed: one Save would replace the reading. */
    @Test
    fun aReadingTheFormCannotShowStaysReadOnly() {
        assertFalse(measurementFormShows(saved("""{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}""")))
        // A waist beside a pressure: the tape's rulers would show half of the row.
        assertFalse(measurementFormShows(saved("""{"schema":1,"waistCircumferenceCm":82,"systolicMmhg":118}""")))
        assertFalse(measurementFormShows(saved(bodyFatPercent = 18.5)))
        assertFalse(measurementFormShows(saved("not a payload")))
    }

    /**
     * "Slept six hours" names no clock time. A dial left on 23:00 to 07:00 beside a box reading
     * 360 would keep Save, which wants the two to agree, dark.
     */
    @Test
    fun aNightToldByItsLengthIsCountedBackFromTheWaking() {
        val opensAt = 23 * 60 to 7 * 60
        val night = chatNight(start = null, end = null, minutes = 360, opensAt = opensAt)

        assertEquals(60 to 420, night)
        val onTheDial = sleepDurationMinutes(night.first, night.second)
        assertTrue(valid("SLEEP", sleepMinutes = onTheDial, chatSleepDuration = "360"))
        // The waking it named is kept, and the falling asleep it named is counted forward from.
        assertEquals(30 to 390, chatNight(start = null, end = 390, minutes = 360, opensAt = opensAt))
        assertEquals(1_380 to 300, chatNight(start = 1_380, end = null, minutes = 360, opensAt = opensAt))
        // Back across midnight.
        assertEquals(1_320 to 120, chatNight(start = null, end = 120, minutes = 240, opensAt = opensAt))
    }

    /**
     * A ruler draws the profile's unit and hands the row kilograms and centimetres, and each tick has
     * to come back as itself: a trip that drifted would pin the imperial height ruler to one value
     * whatever the drag.
     */
    @Test
    fun everyTickOfARulerComesBackAsItself() {
        listOf(true, false).forEach { metric ->
            weightTicks(metric).forEach { assertEquals("$metric", it, weightTick(weightOfTick(it, metric), metric)) }
            heightTicks(metric).forEach { assertEquals("$metric", it, heightTick(heightOfTick(it, metric), metric)) }
            GIRTH_VITAL_ORDER.forEach { vital ->
                girthTicks(vital, metric).forEach { tick ->
                    assertEquals("$vital $metric", tick, girthTick(girthOfTick(tick, vital, metric), metric))
                }
            }
        }
    }

    /** And whatever a row holds in range opens on a tick its ruler has, the ends of the range too. */
    @Test
    fun everyStoredValueOpensOnATickOfItsRuler() {
        listOf(true, false).forEach { metric ->
            listOf(20.0, 72.35, 400.0).forEach { kg ->
                assertTrue("$kg", weightTick(kg, metric) in weightTicks(metric))
            }
            (50..250).forEach { cm -> assertTrue("$cm", heightTick(cm, metric) in heightTicks(metric)) }
            GIRTH_VITAL_ORDER.forEach { vital ->
                girthRulerCm(vital).forEach { cm ->
                    assertTrue("$vital $cm", girthTick(cm, metric) in girthTicks(vital, metric))
                    assertEquals("$vital $cm", cm, girthOfTick(cm, vital, metric = true))
                }
            }
        }
        assertEquals(441, weightTick(20.0, metric = false))
        assertEquals(8_818, weightTick(400.0, metric = false))
        // A pull past either end of a ruler is the end, not a reading the row would refuse.
        assertEquals(20.0, weightOfTick(1, metric = true), 0.0)
        assertEquals(250, heightOfTick(120, metric = false))
    }

    /** Two named times are the night; a length that is no night moves nothing. */
    @Test
    fun aNightToldByItsTimesIsLeftAsTold() {
        val opensAt = 23 * 60 to 7 * 60

        assertEquals(1_320 to 360, chatNight(start = 1_320, end = 360, minutes = 600, opensAt = opensAt))
        assertEquals(opensAt, chatNight(start = null, end = null, minutes = 0, opensAt = opensAt))
        assertEquals(opensAt, chatNight(start = null, end = null, minutes = 1_440, opensAt = opensAt))
        assertEquals(1_320 to 420, chatNight(start = 1_320, end = null, minutes = 0, opensAt = opensAt))
    }
}
