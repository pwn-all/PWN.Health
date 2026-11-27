package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The pedometer's half that the service runs on Room: [writeSteps] stores the accumulator's state and
 * the steps it credited in one transaction, and [stepLedger] starts the next ledger from that state.
 * A service destroyed in the middle of a walk and created again in the same process reads the state
 * back this way, so the two have to carry everything the accumulator decides by — a pending bout, the
 * rate window, the day the bout began on — or the walk is counted again, or in part not at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class StepWriteRoundTripTest {
    private val zone = ZoneOffset.UTC
    private val boot = 1_780_000_000_000L
    private val day = "2026-10-18"

    /** A walk with a refused stretch and a repeated value in it, an event every two seconds. */
    private fun walk(): List<StepReading> {
        val start = LocalDateTime.of(2026, 10, 18, 9, 59, 30)
        return (0 until 40).map { i ->
            val delivered = start.plusSeconds(2L * i)
            StepReading(
                rawCounter = 1_000L + 3 * i + if (i > 20) 1 else 0,
                bootId = boot,
                day = delivered.toLocalDate().toString(),
                eventTimestampNanos = 5_000_000_000_000L + i * 2_000_000_000L,
                gaitEvidence = i !in 12..14,
                doubtful = false,
                deliveredAtMs = delivered.toInstant(zone).toEpochMilli(),
            )
        }
    }

    private fun <T> withDb(block: suspend (StepsDb) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
        return try {
            runBlocking { block(db) }
        } finally {
            db.close()
        }
    }

    /** Feeds [readings] to [ledger] and writes what it decided, as the service's write does. */
    private suspend fun StepsDb.feed(ledger: StepLedger, readings: List<StepReading>) {
        readings.forEachIndexed { i, reading -> ledger.add(reading, now = i.toLong(), zone) }
        ledger.take()?.let { writeSteps(it) }
    }

    /** What the database holds after a walk: the day's total, its hours, and the stored state. */
    private suspend fun StepsDb.stored() = Triple(dao().stepsOn(day), dao().allHourSteps(), dao().state())

    /**
     * The walk written by one ledger, and the same walk with the service destroyed and created again
     * partway — before its first bout is confirmed, in the refused stretch, after the repeated value —
     * leave the same total, the same hours and the same state.
     */
    @Test
    fun aServiceCreatedAgainMidWalkCarriesOnFromTheStoredState() {
        val readings = walk()
        val whole = withDb { db ->
            db.feed(db.stepLedger(), readings)
            db.stored()
        }
        assertTrue("the walk credits steps: ${whole.first}", (whole.first ?: 0L) > 0L)

        listOf(2, 13, 25).forEach { split ->
            val restarted = withDb { db ->
                db.feed(db.stepLedger(), readings.take(split))
                db.feed(db.stepLedger(), readings.drop(split))
                db.stored()
            }
            assertEquals("restarted after reading $split", whole, restarted)
        }
    }

    /**
     * The first reading a new ledger is handed is often the one the last service already wrote: the
     * listener is registered again and the sensor repeats its value. It adds nothing to the day.
     */
    @Test
    fun theLastValueReadAgainByANewServiceAddsNothing() = withDb { db ->
        val readings = walk()
        db.feed(db.stepLedger(), readings)
        val total = db.dao().stepsOn(day)
        val hours = db.dao().allHourSteps()

        val again = readings.last().let { it.copy(eventTimestampNanos = it.eventTimestampNanos + 1_000_000_000L) }
        val ledger = db.stepLedger()
        assertEquals(StepVerdict.DUPLICATE, ledger.add(again, now = 0L, zone).verdict)
        ledger.take()?.let { db.writeSteps(it) }

        assertEquals(total, db.dao().stepsOn(day))
        assertEquals(hours, db.dao().allHourSteps())
    }

    /** Nothing has been decided by a ledger fresh from the database, so it has nothing to write. */
    @Test
    fun aLedgerReadBackHasNothingToWriteUntilItDecides() = withDb { db ->
        db.feed(db.stepLedger(), walk())

        assertNull(db.stepLedger().take())
    }
}
