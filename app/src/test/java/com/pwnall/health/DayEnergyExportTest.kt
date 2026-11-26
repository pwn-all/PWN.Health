package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * The day's total energy as Health Connect is handed it, against a real database: the weight each
 * day is counted at comes out of the journal, so it can only be checked where the journal is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DayEnergyExportTest {
    private val zone = ZoneOffset.UTC
    private val stored = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")

    /**
     * A weighing on the 2nd moves the days after it and none before: the 1st is counted at the
     * profile's own 80 kg, as the activity timeline costs that day's sessions, and the 3rd at the
     * 100 kg weighed. Counted at the latest weighing — the profile the export is handed — every day
     * of the month would move with each trip to the scales.
     */
    @Test
    fun eachDayIsCountedAtTheWeightInForceWhenItEnded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
        try {
            db.dao().saveProfile(stored)
            db.dao().put(DaySteps("2026-08-01", 0))
            db.dao().put(DaySteps("2026-08-03", 0))
            db.dao().insertJournalEntries(
                listOf(
                    JournalEntry(
                        day = "2026-08-02",
                        occurredAt = Instant.parse("2026-08-02T08:00:00Z").toEpochMilli(),
                        kind = KIND_MEASUREMENT,
                        title = "Weight",
                        calories = 0.0,
                        measurementWeightKg = 100.0,
                    ),
                ),
            )
            val inEffect = stored.copy(weightKg = 100.0)

            val byDay = dailyTotalEnergyRecords(
                db,
                emptyList(),
                inEffect,
                Instant.parse("2026-08-04T12:00:00Z"),
                zone,
                version = 1L,
            ).associate { it.startTime.atZone(zone).toLocalDate().toString() to it.energy.inKilocalories }

            assertEquals(restingCaloriesPerDay(stored, 2026), byDay.getValue("2026-08-01"), 0.001)
            assertEquals(restingCaloriesPerDay(inEffect, 2026), byDay.getValue("2026-08-03"), 0.001)
        } finally {
            db.close()
        }
    }
}
