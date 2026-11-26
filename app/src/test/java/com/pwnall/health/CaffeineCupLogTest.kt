package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/**
 * A cappuccino from the caffeine dialog is a food row, so the two guards a cup had as a caffeine row
 * have to hold for it as food: a bounced tap is one cup, and the recent meals — whose repeat saves
 * no caffeine — do not offer it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class CaffeineCupLogTest {
    private lateinit var db: StepsDb
    private val day = "2026-10-03"
    private val at = 1_790_000_000_000L

    @Before
    fun open() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun aSecondCappuccinoInsideTheTapWindowIsSwallowed() = runBlocking {
        val cup = cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML, day, at)

        assertTrue(db.logCaffeine(cup))
        assertFalse(db.logCaffeine(cup.copy(occurredAt = at + DUPLICATE_TAP_WINDOW_MS / 2)))
        assertTrue(db.logCaffeine(cup.copy(occurredAt = at + DUPLICATE_TAP_WINDOW_MS * 2)))
        // A black cup at the same moment is a different kind and is not read as the same tap.
        assertTrue(db.logCaffeine(cupEntry("Coffee", CaffeineDrink.COFFEE, STANDARD_CUP_ML, day, at)))

        val journal = db.dao().journalOn(day).first()
        assertEquals(2, journal.count { it.kind == "EAT" })
        assertEquals(1, journal.count { it.kind == KIND_CAFFEINE })
    }

    @Test
    fun theRecentMealsLeaveTheCappuccinoOut() = runBlocking {
        val lunch = JournalEntry(day = day, occurredAt = at, kind = "EAT", title = "Soup", calories = 200.0)
        db.dao().insertJournalEntries(listOf(lunch))
        db.logCaffeine(cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML, day, at + 60_000))

        assertEquals(listOf("Soup"), db.dao().recentMeals().first().map(JournalEntry::title))
    }
}
