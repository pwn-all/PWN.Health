package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ActivityHealthTest {
    private val loggedAt = Instant.parse("2026-08-01T12:00:00Z")
    private val now = Instant.parse("2026-08-01T12:30:00Z")
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")

    private fun session(activityCode: String?, minutes: Double?) = JournalEntry(
        day = "2026-08-01",
        occurredAt = loggedAt.toEpochMilli(),
        kind = "ACTIVITY",
        title = "Session",
        calories = 100.0,
        activityCode = activityCode,
        durationMinutes = minutes,
    )

    /** A session typed in afterwards describes time that has already passed. */
    @Test
    fun aManualSessionEndsWhenItWasWrittenDown() {
        val (start, end) = session("RUN", 30.0).sessionInterval(now)!!
        assertEquals(loggedAt.minusSeconds(1_800), start)
        assertEquals(loggedAt, end)
    }

    /** A live Bluetooth workout is written the moment it starts, so it runs the other way. */
    @Test
    fun aLiveSessionStartsWhenItWasWrittenDown() {
        val (start, end) = session("BLUETOOTH_FTMS", 30.0).sessionInterval(now)!!
        assertEquals(loggedAt, start)
        assertEquals(loggedAt.plusSeconds(1_800), end)
    }

    /** A live session cannot claim time that has not happened yet. */
    @Test
    fun aLiveSessionNeverRunsPastNow() {
        val (_, end) = session("BLUETOOTH_FTMS", 90.0).sessionInterval(now)!!
        assertEquals(now, end)
    }

    @Test
    fun aSessionWithoutADurationIsNotAnInterval() {
        assertNull(session("RUN", null).sessionInterval(now))
        assertNull(session("RUN", 0.0).sessionInterval(now))
    }

    /**
     * The exported day has to be the day the home screen shows. The pedometer counted the very steps
     * a logged walk was made of, so charging for both would publish a day nobody lived; a session
     * the steps know nothing about still adds on top.
     */
    @Test
    fun theExportedDayDoesNotPayForAWalkTwice() {
        val walk = session("WALK", 60.0).copy(calories = 224.0)
        val pushups = session("PUSHUPS", 15.0).copy(calories = 80.0)

        val total = dayTotalCalories(profile, 2026, listOf(walk, pushups), steps = 10_000, coveredShare = 1.0)

        // 10,000 steps are worth 298 kcal, more than the walk's own estimate of the same outing.
        assertEquals(restingCaloriesPerDay(profile, 2026) + 298.08 + 80.0, total, 0.01)
    }

    /** Today's record stops at the moment of the sync, and resting energy is spent by the clock. */
    @Test
    fun aPartialDayIsChargedOnlyTheRestingEnergyItCovers() {
        val resting = restingCaloriesPerDay(profile, 2026)

        assertEquals(resting / 4.0, dayTotalCalories(profile, 2026, emptyList(), steps = 0, coveredShare = 0.25), 0.001)
        assertEquals(resting, dayTotalCalories(profile, 2026, emptyList(), steps = 0, coveredShare = 1.0), 0.001)
    }

    /**
     * The marker lives in its own column, not in the note: an origin read from the note matches
     * nothing, and no imported session would be handed the energy, distance or power its source
     * measured for it.
     */
    @Test
    fun theImportOriginIsReadFromTheRecordKeyColumn() {
        val imported = session("HEALTH_CONNECT", 30.0).copy(
            healthConnectRecordKey = "Health Connect:com.example.watch:8f2c",
            note = "",
        )

        assertEquals("com.example.watch", imported.importOrigin())
        assertEquals("", session("RUN", 30.0).importOrigin())
    }

    /**
     * Asking for every imported session's energy again each time one more session arrives would read
     * a year of records whole. A session is asked about once for the span it stands on, and again
     * when its source moves it or while its source may still be writing what it measured.
     */
    @Test
    fun aSessionsEnergyIsAskedForOnceForTheSpanItStandsOn() {
        val weekLater = loggedAt.plus(java.time.Duration.ofDays(7))
        val imported = session("HEALTH_CONNECT", 30.0)

        assertTrue(imported.owesMeasuredEnergy(weekLater))
        val asked = imported.withMeasuredEnergy(245.0)
        assertEquals(245.0, asked.calories, 0.0)
        assertFalse(asked.owesMeasuredEnergy(weekLater))
        // Moved by its source: asked again over the new span.
        assertTrue(asked.copy(durationMinutes = 45.0).owesMeasuredEnergy(weekLater))
        // Ended an hour ago: its source may not have written its figures yet.
        assertTrue(asked.owesMeasuredEnergy(loggedAt.plusSeconds(5_400)))
    }

    /** No answer, or one under a kilocalorie, leaves the estimate — and the question answered all the same. */
    @Test
    fun aSessionItsSourceMeasuredNothingForKeepsItsEstimate() {
        val weekLater = loggedAt.plus(java.time.Duration.ofDays(7))
        val imported = session("HEALTH_CONNECT", 30.0).copy(measurementPayloadJson = """{"schema":1}""")

        val unanswered = imported.withMeasuredEnergy(null)
        assertEquals(100.0, unanswered.calories, 0.0)
        assertEquals(100.0, imported.withMeasuredEnergy(0.4).calories, 0.0)
        assertFalse(unanswered.owesMeasuredEnergy(weekLater))
        assertEquals(1, org.json.JSONObject(unanswered.measurementPayloadJson!!).getInt("schema"))
    }
}
