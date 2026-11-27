package com.pwnall.health

import android.content.res.Resources
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The guards the one-tap loggers keep at the data layer, where the screens, the widgets and the chat
 * all end up: a bounced tap is one row, two different things tapped a second apart are two, and a
 * mood waits out its pause across midnight. The screens' own locks and the pure rules behind these
 * guards are tested elsewhere; this is the transaction that asks the database and writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class QuickLogGuardsTest {
    private lateinit var db: StepsDb
    private lateinit var resources: Resources
    private val day = LocalDate.of(2026, 10, 3)
    private val at = LocalDateTime.of(2026, 10, 3, 22, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val minute = 60_000L

    @Before
    fun open() {
        val context = RuntimeEnvironment.getApplication()
        resources = context.resources
        db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    private suspend fun rows(kind: String) = db.dao().allJournal().count { it.kind == kind }

    private fun profile(years: Long) = UserProfile(
        sex = "FEMALE",
        weightKg = 60.0,
        heightCm = 165,
        birthDate = LocalDate.now().minusYears(years).minusDays(1).toString(),
    )

    /** The same product twice inside the window is the tap that bounced; the tile beside it is not. */
    @Test
    fun theSameTobaccoProductTwiceIsOneRowAndAnotherProductIsItsOwn() = runBlocking {
        val cigarette = smokingEntry(resources, TobaccoProduct.CIGARETTE, day).copy(occurredAt = at)

        assertTrue(db.logSmoking(cigarette))
        assertFalse(db.logSmoking(cigarette.copy(occurredAt = at + 1_500)))
        assertTrue(db.logSmoking(smokingEntry(resources, TobaccoProduct.HEATED, day).copy(occurredAt = at + 1_500)))
        assertTrue(db.logSmoking(cigarette.copy(occurredAt = at + DUPLICATE_TAP_WINDOW_MS)))

        assertEquals(3, rows(KIND_SMOKING))
    }

    /** A beer and then a shot a second later is two drinks on purpose; the same beer twice is not. */
    @Test
    fun aBeerAndAShotASecondApartAreTwoDrinks() = runBlocking {
        val beer = alcoholEntry(resources, AlcoholDrink.BEER, day).copy(occurredAt = at)

        assertTrue(db.logAlcohol(beer))
        assertTrue(db.logAlcohol(alcoholEntry(resources, AlcoholDrink.SHOT, day).copy(occurredAt = at + 1_000)))
        assertFalse(db.logAlcohol(beer.copy(occurredAt = at + 1_500)))

        assertEquals(2, rows(KIND_ALCOHOL))
    }

    @Test
    fun aBathroomVisitTappedTwiceIsOneVisit() = runBlocking {
        val visit = urinationEntry(resources, day).copy(occurredAt = at)

        assertTrue(db.logUrination(visit))
        assertFalse(db.logUrination(visit.copy(occurredAt = at + 1_500)))
        assertTrue(db.logUrination(visit.copy(occurredAt = at + DUPLICATE_TAP_WINDOW_MS)))

        assertEquals(2, rows(KIND_URINATION))
    }

    /** Logged at half past ten at night, the pause holds until half past two, whatever the date says. */
    @Test
    fun aMoodWaitsOutItsPauseAcrossMidnight() = runBlocking {
        val evening = moodEntry(resources, Mood.GOOD, null, day).copy(occurredAt = at)
        val nextDay = day.plusDays(1)

        assertTrue(db.logMood(evening))
        val tooSoon = moodEntry(resources, Mood.LOW, null, nextDay).copy(occurredAt = at + 239 * minute)
        assertFalse(db.logMood(tooSoon))
        assertTrue(db.logMood(tooSoon.copy(occurredAt = at + MOOD_COOLDOWN_MS)))

        assertEquals(2, rows(KIND_MOOD))
    }

    /**
     * The widget's tap answers to the age on the profile, not only to whether the widget is offered:
     * a backup restored over it can make the profile a seventeen-year-old's while the widget is still
     * on the home screen. It writes through the app's own database, so this case uses it and leaves it
     * empty again.
     */
    @Test
    fun theCigaretteWidgetWritesNothingForAProfileUnderAge() = runBlocking {
        val app = StepsDb.get(RuntimeEnvironment.getApplication())
        try {
            app.dao().saveProfile(profile(17))
            assertFalse(logWidgetCigarette(RuntimeEnvironment.getApplication()))
            assertEquals(0, app.dao().allJournal().count { it.kind == KIND_SMOKING })

            app.dao().saveProfile(profile(30))
            assertTrue(logWidgetCigarette(RuntimeEnvironment.getApplication()))
            assertEquals(1, app.dao().allJournal().count { it.kind == KIND_SMOKING })
        } finally {
            // Off the test thread, which Robolectric makes the main one and Room refuses a blocking call on.
            withContext(Dispatchers.IO) { app.clearAllTables() }
        }
    }

    /**
     * The widget's cigarette goes through the screen's guard, which asks after the same product: a
     * heated stick logged in the app a moment before is not the widget's tap bouncing, and the
     * widget's own tap twice still is.
     */
    @Test
    fun theCigaretteWidgetIsNotSwallowedByAnotherProductLoggedAMomentBefore() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val app = StepsDb.get(context)
        try {
            app.dao().saveProfile(profile(30))
            assertTrue(app.logSmoking(smokingEntry(context.resources, TobaccoProduct.HEATED, LocalDate.now())))

            assertTrue(logWidgetCigarette(context))
            assertFalse(logWidgetCigarette(context))
            assertEquals(2, app.dao().allJournal().count { it.kind == KIND_SMOKING })
        } finally {
            withContext(Dispatchers.IO) { app.clearAllTables() }
        }
    }
}
