package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rest of [UnitsAndFormattingEdgeCasesTest]: the millilitres a one-tap row is read back as, the
 * columns a correction must not quietly rewrite, and the table Play reads the app's reasons from.
 *
 * Split off because the two together outgrow one file, not because they are a different subject —
 * a hydration record imported in fluid ounces is a unit conversion that ends in an integer.
 */
class UnitsAndFormattingEdgeCasesExtraTest {
    private fun row(
        kind: String,
        servingG: Double = 0.0,
        subtitle: String = "",
        mood: Int? = null,
    ) = JournalEntry(
        day = "2026-08-10",
        occurredAt = 1_754_800_000_000,
        kind = kind,
        title = kind,
        subtitle = subtitle,
        calories = 0.0,
        servingG = servingG,
        moodScore = mood,
    )

    private fun populatedRow() = row(KIND_STOOL, subtitle = "NORMAL", mood = 3).copy(
        id = 41,
        note = "after lunch",
        healthConnectRecordKey = "Health Connect:com.other:abc",
        measurementSource = "HEALTH_CONNECT",
        measurementPayloadJson = """{"notes":["BLOOD","PAIN"]}""",
        caffeineMg = 80.0,
        bodyFatPercent = 21.5,
        breathCycles = 7,
    )

    /**
     * A hydration record from another app is millilitres converted out of litres or fluid ounces,
     * so it arrives carrying a fraction: 12 US fl oz is stored as 354.88235953125 ml. The editor
     * still has to read it back as a number it can hand to the water factory, and it reads back
     * the whole millilitres the row holds — never a millilitre more than were drunk.
     *
     * Truncating rather than rounding is the app's convention for water everywhere, not a slip in
     * this one branch: a journal row prints `servingG.toInt()` and the day and calendar totals cut
     * their sum the same way. Rounding here alone would let saving an imported row nobody edited
     * push the figure already on screen up by one, which is a display change, not a fix.
     */
    @Test
    fun aHydrationRecordImportedInFluidOuncesReadsBackAsTheMillilitresItHolds() {
        listOf(354.88235953125, 100.4, 500.0).forEach {
            val read = simpleValueOf(row(KIND_WATER, servingG = it))
            val millilitres = read.toDoubleOrNull()
            assertTrue("$it ml read back as '$read'", millilitres != null)
            assertTrue("$it ml read back as '$read'", millilitres!! <= it && millilitres > it - 1.0)
        }
    }

    /**
     * A row logged by a build that knew more options than this one has to resolve to nothing rather
     * than to the nearest thing: "" is what leaves the Save button dark and puts the read-only card
     * up instead of a chooser with the wrong chip lit. The alcohol, mood and stool branches are
     * three different lookups, and each can start defaulting.
     */
    @Test
    fun aRowFromABuildThatKnewMoreOptionsResolvesToNoOption() {
        assertEquals("", simpleValueOf(row(KIND_MOOD, mood = 6)))
        assertEquals("", simpleValueOf(row(KIND_MOOD, mood = 0)))
        assertEquals("", simpleValueOf(row(KIND_MOOD, mood = null)))
        assertEquals("", simpleValueOf(row(KIND_STOOL, subtitle = "TYPE_3")))
        assertEquals("", simpleValueOf(row(KIND_STOOL, subtitle = "")))
        assertEquals("", simpleValueOf(row(KIND_SMOKING, servingG = 0.0)))
        assertEquals("", simpleValueOf(row(KIND_SMOKING, servingG = -0.8)))
    }

    /**
     * Choosing the chip that was already lit is a tap the editor cannot refuse, and it rewrites the
     * row through ten hand-written field assignments. One of them written `previous.x ?: x` instead
     * of `x ?: previous.x` is invisible until the two rows differ — so the row that must come back
     * unchanged is a fully populated one, compared column by column rather than field by field.
     */
    @Test
    fun correctingARowToTheOptionItAlreadyHadChangesNothingAtAll() {
        val populated = populatedRow()
        assertEquals(populated, populated.rebuiltFrom(populated))
    }

    /**
     * A mood's word lives in the note column, so a correction that inherited the old note would
     * leave a face labelled with the mood it no longer is. This is the other direction from a fresh
     * note left empty, where the original's survives.
     */
    @Test
    fun theFactorysOwnNoteWinsOverTheNoteTheRowWasCarrying() {
        val previous = row(KIND_MOOD, mood = 4).copy(id = 7, note = "Good")
        val rebuilt = row(KIND_MOOD, mood = 1).copy(note = "Bad").rebuiltFrom(previous)
        assertEquals("Bad", rebuilt.note)
        assertEquals(1, rebuilt.moodScore)
        assertEquals(7L, rebuilt.id)
        assertEquals(previous.occurredAt, rebuilt.occurredAt)
    }

    /**
     * Each inheritance is its own `?:`, so a deleted line loses one column silently and only for
     * the rows that happened to carry it. The mood a stool row was logged beside, the cycles of a
     * practice and a scale's body-fat estimate are three such columns, held here as inherited, the
     * opposite direction of a fresh value winning over an inherited one.
     */
    @Test
    fun whatTheFactoryCouldNotKnowIsInheritedIncludingTheColumnsNothingElseChecks() {
        val previous = populatedRow()
        val rebuilt = row(KIND_STOOL, subtitle = "LOOSE").rebuiltFrom(previous)
        assertEquals("LOOSE", rebuilt.subtitle)
        assertEquals(3, rebuilt.moodScore)
        assertEquals(7, rebuilt.breathCycles)
        assertEquals(21.5, rebuilt.bodyFatPercent ?: 0.0, 1e-9)
        assertEquals(80.0, rebuilt.caffeineMg ?: 0.0, 1e-9)
        assertEquals("""{"notes":["BLOOD","PAIN"]}""", rebuilt.measurementPayloadJson)
    }

    /**
     * The other direction of the check the rationale test makes: a type dropped from the
     * request but left behind in the name map is a label for a grant nobody asks for any more, and
     * the two lists are edited in different commits.
     */
    @Test
    fun noRecordTypeCarriesANameTheTableNeverShows() {
        val explained = HEALTH_RATIONALE_ROWS.flatMap { it.types }.toSet()
        assertEquals(explained, HEALTH_RECORD_TYPE_NAMES.keys)
    }

    /**
     * A row's heading joins its types' names with " · ", so two types pointing at one string print
     * "Steps · Steps" and hide a grant from the reviewer reading the screen. Thirty-two lines of
     * copy-paste against a wall of resource names is exactly where two neighbours end up the same.
     */
    @Test
    fun twoRecordTypesDoNotShareOneLabel() {
        val repeated = HEALTH_RECORD_TYPE_NAMES.values.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<Int>(), repeated)
    }

    /**
     * A reason reused across two rows is the category-level wording Play turns a release down for.
     * Adding a row by duplicating the one above it and forgetting to change the sentence
     * leaves a table that reads plausibly and fails review; two groups that genuinely share a
     * purpose belong in one row.
     */
    @Test
    fun everyRationaleRowHasItsOwnSentence() {
        val repeated = HEALTH_RATIONALE_ROWS.groupBy { it.purpose }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<Int>(), repeated)
    }

    /**
     * The row that says these are written and never read has to list exactly the types that are.
     * The two write-only types sharing one row with that sentence is not enough: a third type that
     * is also read, sitting in the same list, would pass that and tell Play the app only writes
     * something it also imports.
     */
    @Test
    fun theRowThatSaysWrittenNeverReadListsOnlyTheWriteOnlyTypes() {
        val derived = HEALTH_RATIONALE_ROWS.filter { it.purpose == R.string.health_rationale_purpose_derived }
        assertEquals(1, derived.size)
        assertEquals(HEALTH_WRITE_ONLY_TYPES.size, derived.single().types.size)
        assertEquals(HEALTH_WRITE_ONLY_TYPES.toSet(), derived.single().types.toSet())
    }
}
