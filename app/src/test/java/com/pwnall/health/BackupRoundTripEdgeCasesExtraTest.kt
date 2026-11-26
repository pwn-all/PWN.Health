package com.pwnall.health

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The rest of the trip: the merges a restore decides row by row, the sealed container the file
 * travels in, the folder the automatic copies share with the person's own documents, and the two
 * names a restored row is given before anybody sees it.
 *
 * Everything here is a boundary the file's own edges fall on — a tie, an empty side, a day that
 * lost an hour, a length one past what the format allows — rather than a case somebody would think
 * to try by hand.
 */
class BackupRoundTripEdgeCasesExtraTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun forged(offset: Int, value: Int): ByteArray {
        val bytes = SEALED.copyOf()
        for (i in 0 until LENGTH_BYTES) bytes[offset + i] = (value ushr (8 * (LENGTH_BYTES - 1 - i))).toByte()
        return bytes
    }

    /**
     * Two phones that saw the same number of samples. The sum and the count describe one series and
     * have to move together: 1 000 hPa over two samples is 500 hPa a sample, which is not weather
     * anywhere on Earth. The other merge test pits 2 samples against 1, so the tie is this one's.
     */
    @Test
    fun `a tie in sample count leaves the pressure series whole`() {
        val here = BarometerDay("2026-08-10", ascentM = 20.0, pressureSumHpa = 2_000.0, pressureSamples = 2)
        val file = BarometerDay("2026-08-10", ascentM = 5.0, pressureSumHpa = 1_000.0, pressureSamples = 2)

        val merged = mergeBarometerDays(listOf(here), listOf(file))

        assertEquals(listOf(here), merged)
    }

    /**
     * Several devices report the same day and their totals overlap, so the merge is per device and
     * day rather than per day. The result is the upsert's argument, so it carries only what the file
     * brought: the ring survives by not being named, and must never appear under the band's key.
     */
    @Test
    fun `a device only one side has ever seen is left where it is`() {
        val ring = DirectBleDaySteps("ring", "2026-08-10", 8_000, updatedAt = 20)
        val band = DirectBleDaySteps("band", "2026-08-10", 3_000, updatedAt = 5)

        val merged = mergeDirectBleDays(listOf(ring), listOf(band))

        assertEquals(listOf(band), merged)
    }

    /**
     * A first-ever restore onto a fresh install is the commonest use of this code and it runs
     * entirely down the "nothing here yet" path; a file carrying no days must write no row at all,
     * least of all one holding zero.
     */
    @Test
    fun `a fresh install takes the file's totals and an empty file writes nothing`() {
        val day = DaySteps("2026-08-10", 5_000)
        val hour = HourSteps("2026-08-10", 9, 900)
        val external = ExternalDaySteps("2026-08-10", 5_000)

        assertEquals(listOf(day), mergeDaySteps(emptyList(), listOf(day)))
        assertEquals(listOf(hour), mergeHourSteps(emptyList(), listOf(hour)))
        assertEquals(listOf(external), mergeExternalDays(emptyList(), listOf(external)))
        assertEquals(emptyList<DaySteps>(), mergeDaySteps(listOf(day), emptyList()))
        assertEquals(emptyList<HourSteps>(), mergeHourSteps(listOf(hour), emptyList()))
        assertEquals(emptyList<ExternalDaySteps>(), mergeExternalDays(listOf(external), emptyList()))
    }

    /**
     * A file that says "no day here is legacy" is making a statement, and a file with no days at all
     * has nothing to make one about. A day wrongly marked legacy is one this app will never offer
     * back to Health Connect, so "missing" and "empty" are opposites the code deliberately keeps
     * one line apart.
     */
    @Test
    fun `an explicit empty marker list is honoured and a file with no days marks nothing`() {
        val stated = legacyStepDaysForBackupImport(
            schema = 8,
            importedDays = listOf("2026-07-27"),
            encodedLegacyDays = emptySet(),
        )
        val nothing = legacyStepDaysForBackupImport(schema = 1, importedDays = emptyList(), encodedLegacyDays = null)

        assertEquals("marked $stated", emptySet<String>(), stated)
        assertEquals("marked $nothing", emptySet<String>(), nothing)
    }

    /**
     * The chunk index sits in the nonce and under the tag, so that a file with two chunks exchanged
     * fails rather than opening as a shorter, different history. It is the format's stated promise
     * and the whole reason for the counter, and a nonce derived from anything but the running index
     * would leave every other test in the suite green.
     */
    @Test
    fun `two chunks exchanged in a sealed file fail their tag`() {
        val block = LENGTH_BYTES + BackupCrypto.CHUNK_BYTES + TAG_BYTES
        val swapped = LARGE_SEALED.copyOf()
        LARGE_SEALED.copyOfRange(HEADER_BYTES + block, HEADER_BYTES + 2 * block).copyInto(swapped, HEADER_BYTES)
        LARGE_SEALED.copyOfRange(HEADER_BYTES, HEADER_BYTES + block).copyInto(swapped, HEADER_BYTES + block)

        BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(swapped)).use { stream ->
            assertThrows(WrongBackupPasswordException::class.java) { stream.readBytes() }
        }
    }

    /**
     * A half-copied cloud document is the commonest damaged file there is. Blaming the password for
     * one sends its owner back to type the password again for ever; the other truncation tests
     * only cut the ciphertext, and the header is where a copy that stopped early actually stops.
     */
    @Test
    fun `a file cut inside its header is reported truncated, not as a wrong password`() {
        val tagOnly = SEALED.copyOf(BackupCrypto.MAGIC_LENGTH)
        val saltOnly = SEALED.copyOf(BackupCrypto.MAGIC_LENGTH + SALT_BYTES)

        val early = assertThrows(IllegalStateException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(tagOnly))
        }
        val later = assertThrows(IllegalStateException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(saltOnly))
        }

        assertEquals("truncated backup", early.message)
        assertEquals("truncated backup", later.message)
    }

    /**
     * And it is said in the reader's language. Were every failure but a wrong password and a newer
     * version the exception's own English inside "Import error: …", the commonest one would
     * read "truncated backup" in sixteen languages. A file cut short and a file that is not this
     * app's each have their sentence; what the system reports — a full disk — keeps the general one.
     */
    @Test
    fun `a file cut short and a damaged one are each reported in their own sentence`() {
        val cut = assertThrows(TruncatedBackupException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(SEALED.copyOf(BackupCrypto.MAGIC_LENGTH)))
        }
        val forgedRounds = assertThrows(IllegalStateException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(forged(ITERATIONS_OFFSET, 0)))
        }

        assertEquals(R.string.profile_import_truncated, importFailureRes(cut))
        assertEquals(R.string.profile_import_truncated, importFailureRes(EOFException("End of input")))
        assertEquals(R.string.profile_import_damaged, importFailureRes(forgedRounds))
        assertEquals(R.string.profile_import_damaged, importFailureRes(JSONException("No value for id")))
        assertEquals(R.string.profile_import_damaged, importFailureRes(NumberFormatException("x")))
        assertEquals(R.string.profile_import_wrong_password, importFailureRes(WrongBackupPasswordException()))
        assertEquals(R.string.profile_import_unsupported, importFailureRes(UnsupportedBackupVersionException()))
        assertEquals(R.string.profile_import_error, importFailureRes(IOException("ENOSPC")))
    }

    /**
     * The declared length is the only number a forged file gets to choose before the tag is checked,
     * and it fills a fixed buffer. Read as a signed int it can be negative, which would reach
     * System.arraycopy; two billion would be asked of the heap. Both edges of the one check must
     * hold, and the answer must be the format's own error rather than a crash out of the runtime.
     */
    @Test
    fun `a declared chunk length outside the format's bounds is refused at both edges`() {
        listOf(Int.MAX_VALUE, -1, BackupCrypto.CHUNK_BYTES + 1).forEach { declared ->
            val thrown = assertThrows(IllegalStateException::class.java) {
                BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(forged(HEADER_BYTES, declared)))
                    .use { it.readBytes() }
            }

            assertEquals("declared $declared", "corrupt backup", thrown.message)
        }
    }

    /**
     * The bottom edge of the header's round count. Zero rounds is a key derived from nothing, and
     * the check that stops it is one inclusive range shared with the cap far above it.
     */
    @Test
    fun `a header asking for no rounds at all is refused before any key is derived`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            BackupCrypto.decrypting(PASSWORD, ByteArrayInputStream(forged(ITERATIONS_OFFSET, 0)))
        }

        assertEquals("corrupt backup", thrown.message)
    }

    /**
     * The picker calls this on the first bytes of whatever document it was handed, so a short head
     * must answer rather than throw. Nine matching bytes is the whole test and the version byte is
     * deliberately not consulted; six of them is a different file that happens to start the same.
     */
    @Test
    fun `a partial or misspelt tag does not open an encrypted backup`() {
        assertFalse(BackupCrypto.isEncrypted("PWNFIT".toByteArray()))
        assertTrue(BackupCrypto.isEncrypted("PWNFITENC".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted("PWNFITEND".toByteArray()))
    }

    /**
     * The day the clocks go forward is 23 hours long and February 2024 has 29 days. Counting
     * (now - then) / 86 400 000 answers one day short on the first and would shift every "days since
     * your last backup" line twice a year, suppressing the nudge on the day it falls due.
     */
    @Test
    fun `a day that lost an hour and a leap day are both whole days`() {
        val newYork = ZoneId.of("America/New_York")
        val beforeTheClocksWentForward =
            LocalDate.of(2026, 3, 7).atTime(23, 30).atZone(newYork).toInstant().toEpochMilli()
        val beforeTheLeapDay = LocalDate.of(2024, 2, 28).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(2L, backupAgeDays(beforeTheClocksWentForward, LocalDate.of(2026, 3, 9), newYork))
        assertEquals(2L, backupAgeDays(beforeTheLeapDay, LocalDate.of(2024, 3, 1), zone))
    }

    /**
     * The stamp is an instant; which calendar day it fell on is a fact about the zone. Half past
     * midnight UTC is the afternoon of the same day at UTC+14 and the afternoon of the day before at
     * UTC-11 — and saying which day it was is this function's whole job.
     */
    @Test
    fun `the stamp is read in the zone it was asked about`() {
        val stamp = LocalDate.of(2026, 9, 5).atTime(0, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 9, 5)

        assertEquals(0L, backupAgeDays(stamp, today, ZoneId.of("Pacific/Kiritimati")))
        assertEquals(1L, backupAgeDays(stamp, today, ZoneId.of("Pacific/Niue")))
    }

    /**
     * A phone whose clock ran ahead once leaves a misdated file in the folder for good. The day is
     * in the name so that sorting by name sorts by day: the misdated one counts as the newest and
     * the folder still shrinks, rather than growing without bound or losing a real copy first.
     */
    @Test
    fun `a file dated in the future does not stop the folder shrinking`() {
        val days = (1..8).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        val folder = (days + "pwn-health-auto-2027-01-01.json").shuffled(Random(7))

        assertEquals(days.take(2), autoBackupsToPrune(folder).sorted())
    }

    /**
     * The folder is one the person chose, where their own exports and unrelated documents live. A
     * pattern anchored at one end only, or loosened to ignore case, deletes somebody else's files:
     * these four are the four ways it leaks.
     */
    @Test
    fun `nothing that merely looks like the worker's file is a candidate for deletion`() {
        val days = (1..8).map { "pwn-health-auto-2026-09-%02d.json".format(it) }
        val lookalikes = listOf(
            "pwn-health-auto-2026-09-01.json.bak",
            "Pwn-Health-Auto-2026-09-02.json",
            "pwn-health-auto-2026-9-3.json",
            "my-pwn-health-auto-2026-09-04.json",
        )

        val pruned = autoBackupsToPrune(days + lookalikes)

        assertEquals(listOf(days.first()), pruned)
    }

    /**
     * Nothing asked for and nothing granted. "Allowed" reads as complete because nought of nought
     * is, so the Allow button goes; the Import button must still stay off, or a tap starts an
     * exchange that returns before the spinner has drawn.
     */
    @Test
    fun `an empty permission request offers nothing to import`() {
        val access = importAccess(granted = emptySet(), requested = emptySet())

        assertEquals(0, access.allowed)
        assertEquals(0, access.requested)
        assertEquals(HistoryGrant.UNSUPPORTED, access.history)
        assertFalse("ready with nothing granted", importReady(access, running = false))
    }

    /**
     * The importer throws away the name the file carried and rebuilds it from the moment, so that
     * `../databases/steps.db` cannot be a name; the same derived name is the key that stops a
     * second import doubling the album. A formatted number would break the match and could put a
     * separator back.
     */
    @Test
    fun `a restored photograph's name is its moment in plain digits and nothing else`() {
        val names = listOf(1_800_000_000_000L, 0L, -1L).map(::progressPhotoName)

        assertEquals(listOf("1800000000000.jpg", "0.jpg", "-1.jpg"), names)
        names.forEach { assertFalse(it, it.contains('/') || it.contains('\\')) }
    }

    /**
     * A new journal kind fails silently. A test holding a frozen list of kinds would pass one added
     * later while its restored rows are counted under "other records"; this asks the sources what
     * kinds exist.
     */
    @Test
    fun everyJournalKindTheAppCanWriteHasANameInTheImportMirror() {
        val sources = File("src/main/java/com/pwnall/health").listFiles()
            ?.filter { it.name.endsWith(".kt") }
            ?.joinToString("\n") { it.readText() }
            ?: error("no sources beside Backup.kt")
        val named = Regex("""const val KIND_\w+ = "(\w+)""").findAll(sources).map { it.groupValues[1] }
        val literal = Regex("""kind = "(\w+)""").findAll(sources).map { it.groupValues[1] }
        val kinds = (named + literal).toSet()

        assertTrue("only found $kinds", kinds.size >= 18)
        assertEquals(emptyList<String>(), kinds.filter { importedKindTitle(it) == R.string.sources_metric_other })
        // A chat's link to a course names it by a pseudo-kind that is no journal kind: declared without
        // the KIND_ prefix and never written as a literal, it is never asked a title here.
        assertFalse(LINK_COURSE in kinds)
    }

    private companion object {
        /** The header as BackupCrypto lays it out: tag and version, salt, iteration count, nonce. */
        const val HEADER_BYTES = 38
        const val SALT_BYTES = 16
        const val ITERATIONS_OFFSET = 26
        const val LENGTH_BYTES = 4
        const val TAG_BYTES = 16

        val PASSWORD = "password".toCharArray()
        val SEALED = seal("""{"schema":8,"exportedAt":1800000000000,"days":[]}""".toByteArray())

        /** Two whole chunks and the empty flagged one after them, so there are two to exchange. */
        val LARGE_SEALED = seal(Random(20_260_906).nextBytes(2 * BackupCrypto.CHUNK_BYTES))

        fun seal(plain: ByteArray): ByteArray {
            val sink = ByteArrayOutputStream()
            BackupCrypto.encrypting(PASSWORD, sink).use { it.write(plain) }
            return sink.toByteArray()
        }
    }
}
