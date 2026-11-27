package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * The worker deletes files out of a folder somebody else chose, so what it may delete is the part
 * worth pinning down; the rest is the arithmetic behind "N days ago".
 */
class AutoBackupTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun theFileIsNamedByItsDay() {
        assertEquals(
            "pwn-health-auto-2026-09-05-00000000-0000-0000-0000-000000000001-10-" +
                "00000000-0000-0000-0000-000000000002.json",
            autoBackupName(LocalDate.of(2026, 9, 5), UUID(0, 1).toString(), 10L, UUID(0, 2)),
        )
    }

    /** Only the worker's own files, only past the newest seven, and the newest is the latest day. */
    @Test
    fun onlyTheOldestAutomaticCopiesArePruned() {
        val days = (1..9).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        val folder = days.shuffled(Random(7)) +
            listOf("pwn-health-backup-2026-01-01.json", "notes.txt", "pwn-health-auto-x.json")

        assertEquals(days.take(2), autoBackupsToPrune(folder).sorted())
    }

    /**
     * Files under `pwn-fit-auto-`, the app's earlier name, are the worker's own copies too:
     * recognised, ordered by their day beside the `pwn-health-auto-` ones, and pruned in the same
     * rotation rather than left in the folder for good.
     */
    @Test
    fun copiesWrittenBeforeTheRenameStayInTheRotation() {
        val old = (1..5).map { "pwn-fit-auto-2026-09-%02d.json".format(it) }
        val new = (6..9).map { "pwn-health-auto-2026-09-%02d.json".format(it) }

        assertEquals(old.take(2), autoBackupsToPrune((old + new).shuffled(Random(7))).sorted())
    }

    @Test
    fun aFolderWithSevenOrFewerLosesNothing() {
        val days = (1..7).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        assertTrue(autoBackupsToPrune(days).isEmpty())
        assertTrue(autoBackupsToPrune(emptyList()).isEmpty())
    }

    /**
     * A database that was damaged, or sealed by a key the phone lost, starts over empty, and the
     * worker goes on copying it into the same folder. Seven nights of that would push out every copy
     * of the record that was lost. Its last seven stay; the copies made since rotate among themselves.
     */
    @Test
    fun copiesOfARecordThatWasLostAreNotRotatedOutByTheEmptyOneAfterIt() {
        val before = (1..7).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        val after = (8..16).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        val folder = (before + after).shuffled(Random(7))

        // Unmarked, the nine oldest go, which is every copy made before the 8th.
        assertEquals(before + after.take(2), autoBackupsToPrune(folder).sorted())
        // The record began again on the 7th, after that day's copy was made.
        assertEquals(after.take(2), autoBackupsToPrune(folder, startedOver = setOf("2026-09-07")).sorted())
    }

    /** Each stretch between two such days keeps its own seven, so a second loss does not undo the first hold. */
    @Test
    fun aSecondLossKeepsTheCopiesOfBothRecords() {
        val days = (1..20).map { "pwn-health-auto-2026-09-%02d.json".format(it) }

        // 1–4 are the first record's, 5–12 the second's, 13–20 the third's: eight each of the last two.
        assertEquals(
            listOf(days[4], days[12]),
            autoBackupsToPrune(days.shuffled(Random(7)), startedOver = setOf("2026-09-04", "2026-09-12")).sorted(),
        )
    }

    /** The text of function [name] in [file], from its name to the brace that closes it at the margin. */
    private fun function(file: String, name: String): String =
        File("src/main/java/com/pwnall/health/$file").readText().substringAfter("fun $name(").substringBefore("\n}\n")

    /**
     * Whether the day is marked only a phone shows, so the wiring is pinned in the source: the one
     * call every database that starts over makes marks it, and the worker hands the marks to the
     * rotation.
     */
    @Test
    fun aDatabaseStartingOverMarksTheDayForTheRotation() {
        val startingOver = function("Backup.kt", "forgetInstallId")
        assertTrue(startingOver, "AutoBackup.markStartedOver(context)" in startingOver)
        val worker = function("AutoBackup.kt", "writeAutomaticCopy")
        assertTrue(worker, "startedOver = AutoBackup.startedOverDays(context)," in worker)
        // What the rotation does with the marks is pinned on its own, in AutoBackupRotationTest.
    }

    /** Calendar days, so a copy made last night reads as a day old all of today. */
    @Test
    fun theAgeCountsCalendarDaysNotStretchesOfTwentyFourHours() {
        val today = LocalDate.of(2026, 9, 5)
        val lastNight = LocalDate.of(2026, 9, 4).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val thisMorning = today.atTime(0, 10).atZone(zone).toInstant().toEpochMilli()

        assertEquals(1L, backupAgeDays(lastNight, today, zone))
        assertEquals(0L, backupAgeDays(thisMorning, today, zone))
        assertNull(backupAgeDays(null, today, zone))
    }

    /** A stamp from a clock that has since been set back is "today", not a negative number of days. */
    @Test
    fun aStampFromTheFutureIsToday() {
        val today = LocalDate.of(2026, 9, 5)
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(0L, backupAgeDays(tomorrow, today, zone))
    }

    @Test
    fun theNudgeAppearsAfterAMonthOrWhenThereWasNeverACopy() {
        assertTrue(backupIsStale(null))
        assertTrue(backupIsStale(AutoBackup.STALE_DAYS + 1))
        assertFalse(backupIsStale(AutoBackup.STALE_DAYS))
        assertFalse(backupIsStale(0L))
    }

    /**
     * A copy that failed last night is said on the front page at once, however fresh the last export:
     * the worker can only write its failure down, and under the switch is not where somebody who
     * switched the copy on goes back to look. Without a failure the month's rule stands.
     */
    @Test
    fun aFailedNightlyCopyIsSaidOnTheFrontPageHoweverFreshTheLastExport() {
        assertTrue(backupNeedsAttention(0L, "the folder is no longer reachable"))
        assertTrue(backupNeedsAttention(null, null))
        assertTrue(backupNeedsAttention(AutoBackup.STALE_DAYS + 1, null))
        assertFalse(backupNeedsAttention(AutoBackup.STALE_DAYS, null))
        assertFalse(backupNeedsAttention(0L, null))
    }

    /**
     * The encryption switch promises that the storage reads as noise, and the daily copies are the
     * same record in plain text: with both on, the switch says so. Either alone has its own words.
     */
    @Test
    fun theEncryptionSwitchSaysTheDailyCopiesStayPlainOnlyWhenBothAreOn() {
        val both = plainCopyWarning(databaseEncrypted = true, dailyCopyOn = true)

        assertEquals(R.string.db_encrypt_daily_copy_plain, both)
        assertNull(plainCopyWarning(databaseEncrypted = true, dailyCopyOn = false))
        assertNull(plainCopyWarning(databaseEncrypted = false, dailyCopyOn = true))
    }
}
