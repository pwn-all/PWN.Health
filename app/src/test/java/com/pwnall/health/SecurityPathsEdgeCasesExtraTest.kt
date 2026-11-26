package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The other three gates in front of a person's record: the lock on the app's own screen, the age
 * the app was told, and the read grants an attachment hangs on.
 *
 * All three fail in one direction more expensively than the other — a lock that stops asking, a
 * gate that opens for a child, a grant released while a row still points at the file — so what is
 * asserted here is the whole truth table rather than the cells somebody thought of.
 */
class SecurityPathsEdgeCasesExtraTest {
    private val booleans = listOf(false, true)

    private val scan = "content://com.android.providers.media.documents/document/image%3A12"
    private val letter = "content://com.android.providers.downloads.documents/document/7"

    private fun sheetWithPayload(payload: String?) = JournalEntry(
        day = "2026-08-10",
        occurredAt = 0,
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    /**
     * Both directions cost something and they are opposite: one true too many stacks credential
     * dialogs on top of each other for ever, one true too few lets whoever picks the phone up walk
     * straight in. Exactly one of the eight ways the lock can stand is the one that asks.
     */
    @Test
    fun exactlyOneOfTheEightWaysTheLockCanStandAsksForTheCredential() {
        val asking = booleans.flatMap { enabled ->
            booleans.flatMap { unlocked ->
                booleans.filter { prompting -> shouldPrompt(enabled, unlocked, prompting) }
                    .map { prompting -> Triple(enabled, unlocked, prompting) }
            }
        }

        assertEquals(listOf(Triple(true, false, false)), asking)
    }

    /**
     * The lock is the composition of the two halves, not either of them: whatever shuts the app
     * must leave it asking at the next foreground, and whatever does not shut it must be the
     * credential screen taking the front. A picker exemption in the relock half would break exactly
     * this pairing — press Home from the camera and the app would stay open, unasked, for whoever
     * opened it next.
     */
    @Test
    fun anAppThatWasShutIsAlwaysAskedAgainAtTheNextForeground() {
        booleans.forEach { enabled ->
            booleans.forEach { prompting ->
                val shut = shouldRelock(enabled, prompting)
                val asksNext = shouldPrompt(enabled, unlocked = !shut, prompting = false)
                val situation = "enabled=$enabled prompting=$prompting"

                if (shut) assertTrue(situation, asksNext)
                if (enabled && !shut) assertTrue("$situation left the app open with the lock on", prompting)
            }
        }
    }

    /**
     * Android draws the recents thumbnail from the last frame before the lock closes, so a locked
     * app whose window is not secure shows the weight of the week on the wall outside its own door.
     * The lock carries the flag whatever the separate switch says; with the lock off the switch is
     * given back its own answer.
     */
    @Test
    fun aLockedAppIsHiddenFromRecentsWhateverTheOtherSwitchSays() {
        booleans.forEach { screenshots ->
            assertTrue("screenshots=$screenshots", secureWindow(lockEnabled = true, blockScreenshots = screenshots))
            assertEquals(
                "screenshots=$screenshots",
                screenshots,
                secureWindow(lockEnabled = false, blockScreenshots = screenshots),
            )
        }
    }

    /**
     * FLAG_SECURE is per-window state that any screen can clear on its way out, and the failure is
     * invisible inside the app: it shows up only in the recents thumbnail of a locked app. So the
     * two switches stay its single owner, and a second setter anywhere in the tree is the bug.
     */
    @Test
    fun onlyTheLockSetsTheWindowsSecureFlag() {
        val sources = File("src/main/java/com/pwnall/health")
            .listFiles { file -> file.name.endsWith(".kt") }
            .orEmpty()

        assertTrue("found ${sources.size} sources; the working directory is wrong", sources.size > 50)
        val setters = sources
            .filter { it.readText().contains("WindowManager.LayoutParams.FLAG_SECURE") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("AppLock.kt"), setters)
    }

    /**
     * A birthday on 29 February has no anniversary in three years out of four, and every naive
     * implementation of this either throws, opens the gate a day early or holds it shut a year too
     * long. Whole years only: still seventeen on 28 February 2026, eighteen on 1 March.
     */
    @Test
    fun aLeapDayBirthdayReachesEighteenOnTheFirstOfMarch() {
        assertEquals(17, ageOn("2026-02-27", "2008-02-29"))
        assertEquals(17, ageOn("2026-02-28", "2008-02-29"))
        assertEquals(18, ageOn("2026-03-01", "2008-02-29"))
        assertEquals(16, ageOn("2024-02-29", "2008-02-29"))
        assertFalse(habitsOffered("2008-02-29", LocalDate.parse("2026-02-28")))
        assertTrue(habitsOffered("2008-02-29", LocalDate.parse("2026-03-01")))
    }

    /**
     * The birth date is a free text column fed by imports, backups and a merge; what reaches it is
     * not always what the picker wrote. A day that does not exist, a month that does not, a
     * timestamp, another country's order, a single digit where two belong — none of them is a
     * birthday, all of them close both gates, and none of them may throw on the home screen.
     */
    @Test
    fun aDateThatIsNotAnIsoDayIsNoBirthdayAtAll() {
        val notDates = listOf(
            "2026-02-30",
            "2013-13-01",
            "2013-08-25T00:00:00",
            "25/08/2013",
            "2013-8-5",
            " 2013-08-25",
            "2013-08-25 ",
            "8".repeat(4096),
        )

        notDates.forEach { text -> assertEquals(text.take(24), 0, ageOn("2026-08-25", text)) }
    }

    /**
     * A phone whose clock was wrong when the profile was typed, or a backup restored from a device
     * set forward. The span is negative, and an implementation that formats or absolutes it hands a
     * child alcohol; when the app cannot tell, both doors stay shut.
     */
    @Test
    fun aBirthDateInTheFutureShutsBothGates() {
        val today = LocalDate.parse("2026-08-25")

        listOf("2030-01-01", "2027-09-01").forEach { born ->
            assertEquals(born, 0, ageOn(today.toString(), born))
            assertFalse(born, sexLogOffered(born, today))
            assertFalse(born, habitsOffered(born, today))
        }
    }

    /**
     * The other extreme, which an import is equally able to produce. The age is clamped for the
     * sake of what the profile prints, and the clamp must never take the answer below the gates or
     * let an implausible year reach Period's Int arithmetic as an exception or a wrapped number.
     */
    @Test
    fun anImplausiblyOldBirthDateOpensTheGatesRatherThanWrappingRound() {
        val today = LocalDate.parse("2026-08-25")

        listOf("1850-06-01", LocalDate.MIN.toString()).forEach { born ->
            val age = ageOn(today.toString(), born)
            assertTrue("$born was $age", age in HABITS_MIN_AGE..150)
            assertTrue(born, habitsOffered(born, today))
            assertTrue(born, sexLogOffered(born, today))
        }
    }

    /**
     * Two thresholds compared against one clamped number: swapping the constants, or the comparison
     * at one call site, is invisible to a test per case and produces nonsense on exactly one band —
     * alcohol offered to somebody the app will not let record a partner. Each gate opens once, on
     * one day, and never closes again, and alcohol never opens first.
     */
    @Test
    fun eachGateOpensOnceAndNeverBeforeTheOneBelowIt() {
        val born = "2008-06-15"
        val days = generateSequence(LocalDate.parse("2023-06-01")) { it.plusDays(1) }
            .takeWhile { it <= LocalDate.parse("2027-07-01") }
            .toList()

        assertEquals(1, days.zipWithNext().count { (a, b) -> sexLogOffered(born, a) != sexLogOffered(born, b) })
        assertEquals(1, days.zipWithNext().count { (a, b) -> habitsOffered(born, a) != habitsOffered(born, b) })
        assertFalse(sexLogOffered(born, days.first()))
        assertTrue(habitsOffered(born, days.last()))
        days.forEach { day ->
            assertTrue("$day", !habitsOffered(born, day) || sexLogOffered(born, day))
        }
    }

    /**
     * This string is a database column and it is what the backup carries. Splitting on a newline
     * turns "" into a list holding one empty string, which only the blank filter saves: a change to
     * `isNotEmpty` or a trim would put a phantom attachment on every row that has none.
     */
    @Test
    fun theStoredAttachmentStringRoundTrips() {
        val lists = listOf(emptyList<String>(), listOf(scan), listOf(scan, letter, "$scan.2"))

        lists.forEach { uris -> assertEquals(uris, attachmentUris(storeAttachments(uris))) }
        assertEquals("", storeAttachments(emptyList()))
        assertEquals(emptyList<String>(), attachmentUris(""))
        assertEquals(emptyList<String>(), attachmentUris("\n"))
        assertEquals(emptyList<String>(), attachmentUris("   "))
        assertEquals(listOf(scan, letter), attachmentUris("$scan\n\n$letter\n"))
        // A carriage return is whitespace, so an entry made of nothing else is dropped by the same
        // blank filter rather than becoming an attachment that names no file.
        assertEquals(listOf(scan), attachmentUris("$scan\n\r\n"))
    }

    /**
     * A lab sheet keeps its files inside a JSON payload, and a payload that will not parse answers
     * with an empty list. That answer is the same one the shelf on the labs page reads, so a
     * damaged sheet shows no attachment and hangs none: it is no reason to keep a grant alive, and
     * the check stays a plain question about the rows the app can actually read.
     */
    @Test
    fun aLabSheetWhosePayloadCannotBeReadHangsNoFileAndHoldsNoGrant() {
        val truncated = sheetWithPayload("{\"attachments\":\"$scan\"")
        val nothing = sheetWithPayload(null)

        assertEquals("", nothing.labAttachments())
        assertTrue(namedAttachments(emptyList(), emptyList(), listOf(nothing)).isEmpty())
        assertTrue(
            "a payload the app cannot read names no file, on the shelf or here",
            namedAttachments(emptyList(), emptyList(), listOf(truncated)).isEmpty(),
        )
    }
}
