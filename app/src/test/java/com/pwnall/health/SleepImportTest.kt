package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One night can arrive several times over — the watch's own session, the session its companion
 * app republished, and the phone's guess from screen use — and the journal must not show all of
 * them: 21:05→6:25 next to 23:54→4:42 next to 22:55→4:51.
 */
class SleepImportTest {
    private fun night(source: String?, id: Long) = JournalEntry(
        id = id,
        day = "2026-08-01",
        occurredAt = id,
        kind = "MEASUREMENT",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = 0,
        sleepEndAt = 60_000,
        measurementSource = source,
    )

    @Test
    fun anEmptyNightTakesTheMeasurement() {
        val replaced = sleepGuessesToReplace(emptyList())

        assertTrue(replaced!!.isEmpty())
    }

    @Test
    fun aMeasurementReplacesTheGuessMadeFromScreenUse() {
        val replaced = sleepGuessesToReplace(listOf(night("PHONE_USAGE_ESTIMATE", 1)))

        assertEquals(listOf(1L), replaced?.map(JournalEntry::id))
    }

    @Test
    fun aNightThatAlreadyHasAMeasurementIsLeftAlone() {
        assertNull(sleepGuessesToReplace(listOf(night("HEALTH_CONNECT", 1))))
        assertNull(sleepGuessesToReplace(listOf(night("MANUAL", 1))))
        assertNull(
            sleepGuessesToReplace(listOf(night("PHONE_USAGE_ESTIMATE", 1), night("HEALTH_CONNECT", 2))),
        )
    }

    // A session in Health Connect goes on changing after an exchange has read it: the night met at
    // a quarter to five as 23:54→04:42 is longer by breakfast, and must not stay as it was first read.

    private val watchMarker = "Health Connect:com.example.watch:night-1"

    private fun imported(id: Long, marker: String, minutes: Long) =
        night("HEALTH_CONNECT", id).copy(sleepEndAt = minutes * 60_000, healthConnectRecordKey = marker)

    /** What the watch's session, [minutes] long from the same start, does to the rows its night holds. */
    private fun offered(recorded: List<JournalEntry>, minutes: Long) =
        sleepRowsToReplace(recorded, watchMarker, startMs = 0, endMs = minutes * 60_000)

    /** Nothing is replaced and the record goes on, to be refreshed in the row it was imported into. */
    @Test
    fun aNightReadEarlyLetsItsOwnRecordThroughAgain() {
        val replaced = offered(listOf(imported(1, watchMarker, 288)), minutes = 456)

        assertTrue(replaced!!.isEmpty())
    }

    /** A year of nights is read on every exchange, and one that has not moved is not written again. */
    @Test
    fun aNightThatHasNotMovedIsLeftAsItStands() {
        assertNull(offered(listOf(imported(1, watchMarker, 288)), minutes = 288))
    }

    @Test
    fun anImportedNightGivesWayToALongerSession() {
        val early = imported(1, "Health Connect:com.example.watch:night-0", 288)
        val guess = night("PHONE_USAGE_ESTIMATE", 2)

        val replaced = offered(listOf(early, guess), minutes = 456)

        assertEquals(setOf(1L, 2L), replaced?.map(JournalEntry::id)?.toSet())
        // A session no longer than the one standing is a piece of it.
        assertNull(offered(listOf(early), minutes = 288))
    }

    /** A night typed in, corrected by hand or restored as this app's own is the person's, whatever arrives. */
    @Test
    fun aNightThePersonSettledIsLeftAloneHoweverLongTheSession() {
        val corrected = imported(1, "Health Connect:com.example.watch:night-0", 288).copy(editedAt = 1)

        assertNull(offered(listOf(corrected), minutes = 456))
        assertNull(offered(listOf(night("MANUAL", 1)), minutes = 456))
        assertNull(offered(listOf(night(null, 1)), minutes = 456))
    }

    // The day's heart rate is filed under its day and names no record, so nothing reports its
    // samples deleted at their source: a day is lost when they were there last time and are gone now.

    private fun day(date: String): LocalDate = LocalDate.parse(date)

    private fun dailyRate(id: Long, date: String) = night("HEALTH_CONNECT", id).copy(
        day = date,
        sleepStartAt = null,
        sleepEndAt = null,
        measurementHeartRateBpm = 61,
        healthConnectRecordKey = dailyHeartRateMarker(day(date)),
    )

    @Test
    fun aDayIsLostWhenItsSamplesWereThereLastTimeAndAreGoneNow() {
        val before = setOf(day("2026-08-03"), day("2026-08-04"), day("2026-08-05"))

        val lost = heartDaysLost(before, now = setOf(day("2026-08-05")), windowOpens = day("2026-08-03"))

        // Not the 3rd: the read began part-way through it, so its silence says nothing.
        assertEquals(setOf(day("2026-08-04")), lost)
    }

    /** A rate a backup or a phone transfer brought was never this Health Connect's to lose. */
    @Test
    fun aDayHealthConnectWasNeverSeenToHoldIsNotLost() {
        val lost = heartDaysLost(before = emptySet(), now = emptySet(), windowOpens = day("2026-08-01"))

        assertTrue(lost.isEmpty())
    }

    @Test
    fun aLostDaysImportedRateGoesAndNothingElseOfTheDayDoes() {
        val rate = dailyRate(1, "2026-08-04")
        val corrected = dailyRate(2, "2026-08-04").copy(editedAt = 1)
        val typed = dailyRate(3, "2026-08-04").copy(healthConnectRecordKey = null)
        val otherDay = dailyRate(4, "2026-08-05")

        val standing = listOf(rate, corrected, typed, otherDay)

        val vanished = vanishedDailyHeartRates(standing, lost = setOf(day("2026-08-04")))

        assertEquals(listOf(1L), vanished.map(JournalEntry::id))
    }

    /**
     * Held in memory, the days would go with the process, and samples deleted before the next one
     * began would leave their rows for good. They are written down where no transfer carries them.
     */
    @Test
    fun theDaysARateWasFoundOnOutliveTheProcessAndStayOnThePhone() {
        val file = java.io.File.createTempFile("heart-days", null).apply { deleteOnExit() }

        keepHeartDaysOffered(file, setOf(day("2026-08-05"), day("2026-08-04")))
        assertEquals(setOf(day("2026-08-04"), day("2026-08-05")), heartDaysOfferedIn(file))
        // A first exchange, or a file the disk lost, has seen no day.
        file.delete()
        assertTrue(heartDaysOfferedIn(file).isEmpty())

        val sync = java.io.File("src/main/java/com/pwnall/health/HealthSync.kt").readText()
        assertTrue("java.io.File(context.noBackupFilesDir, HEART_DAYS_FILE)" in sync)
    }
}
