package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Counting visits, and what a visit felt like.
 *
 * The counts are the whole feature: nothing here is calories or grams, and a double-logged visit
 * is invisible afterwards — which is exactly why the three-second door on the urination button,
 * [isDuplicateTap], has a test of its own (DuplicateTapTest) rather than a comment.
 * Of the sensations only the reading half is held here: writing one goes through a Resources this
 * JVM cannot build, while reading is what every screen and every restored backup does.
 */
class BathroomTest {
    private val today = LocalDate.of(2026, 8, 9)

    private fun stool(kind: StoolKind, day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = KIND_STOOL,
        title = "Стул",
        subtitle = kind.name,
        calories = 0.0,
    )

    private fun urination(day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = KIND_URINATION,
        title = "Мочеиспускание",
        calories = 0.0,
    )

    /** A payload written the way the entry factories write one: enum names inside the shared key. */
    private fun ticks(vararg notes: String) =
        notes.joinToString(separator = ",", prefix = """{"notes":[""", postfix = "]}") { "\"$it\"" }

    @Test
    fun countsTheDaysVisitsByKind() {
        val journal = listOf(
            stool(StoolKind.NORMAL),
            stool(StoolKind.LOOSE),
            stool(StoolKind.LOOSE),
            urination(),
            urination(),
            urination(),
            // Yesterday's rows belong to yesterday, not to the card on screen.
            stool(StoolKind.NORMAL, today.minusDays(1)),
            urination(today.minusDays(1)),
        )

        val day = bathroomDay(journal, today)

        assertEquals(3, day.stools)
        assertEquals(2, day.looseStools)
        assertEquals(3, day.urinations)
    }

    /** The week is the same window every other habit is read over, ends included. */
    @Test
    fun theWeekSpansSevenDaysAndStopsThere() {
        val journal = listOf(
            stool(StoolKind.LOOSE, today),
            stool(StoolKind.LOOSE, today.minusDays(HABIT_WINDOW_DAYS - 1L)),
            stool(StoolKind.LOOSE, today.minusDays(HABIT_WINDOW_DAYS.toLong())),
        )

        val week = bathroomWeek(journal, today)

        assertEquals(2, week.stools)
        assertEquals(2, week.looseStools)
    }

    /** The entry stores the enum's name, so a phone in another language still reads it back. */
    @Test
    fun theStoolKindSurvivesTheJournal() {
        assertEquals(StoolKind.STRAINED, stoolKindOf(stool(StoolKind.STRAINED)))
        // A row from a version that named them differently is unknown, not a crash.
        assertEquals(null, stoolKindOf(stool(StoolKind.NORMAL).copy(subtitle = "SOFT")))
    }

    /**
     * The sensations ride in the payload as the enum's own names, under the same key the intimate
     * screen writes and reads — one shape for both of the private screens, so a restore has a single
     * thing to understand. Writing them wants android's Resources and stays on the device; reading is
     * the half a restored backup leans on, and the half a plain JVM test can hold to.
     */
    @Test
    fun everySensationIsReadBackFromThePayload() {
        BathroomNote.entries.forEach { note ->
            val entry = urination().copy(measurementPayloadJson = ticks(note.name))

            assertEquals(listOf(note), bathroomNotesOf(entry.measurementPayloadJson))
        }
    }

    /** Ticked in whatever order they were touched, read back in the order the screen offers them. */
    @Test
    fun severalSensationsComeBackInTheOrderTheyAreOffered() {
        val entry = stool(StoolKind.LOOSE).copy(measurementPayloadJson = ticks("BLOOD", "BURNING", "URGENCY"))

        assertEquals(
            listOf(BathroomNote.BURNING, BathroomNote.URGENCY, BathroomNote.BLOOD),
            bathroomNotesOf(entry.measurementPayloadJson),
        )
    }

    @Test
    fun aVisitWithoutSensationsReadsAsNothing() {
        // The one-tap visit this screen is built around carries no payload at all.
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(urination().measurementPayloadJson))
        // Neither does a row from another screen whose payload keeps something else entirely...
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf("""{"cycle":"FLOW_LIGHT"}"""))
        // ...nor a name only a later version knows, which is passed over rather than crashed on.
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(ticks("FUTURE_NOTE")))
    }

    /** The ticks ride in the payload as names, so a restore reads back the same four options. */
    @Test
    fun sexNotesAreReadBackFromThePayload() {
        val payload = """{"cycle":"SEX_PROTECTED","notes":["PAIN","BLEEDING"]}"""

        assertEquals(listOf(SexNote.PAIN, SexNote.BLEEDING), sexNotesOf(payload))
        // An entry with no ticks, a row with none of the key, and rubbish all read as nothing.
        assertEquals(emptyList<SexNote>(), sexNotesOf("""{"cycle":"SEX_PROTECTED"}"""))
        assertEquals(emptyList<SexNote>(), sexNotesOf(null))
        assertEquals(emptyList<SexNote>(), sexNotesOf("not json"))
        // A name a later version added is ignored rather than crashed on.
        assertEquals(listOf(SexNote.PAIN), sexNotesOf("""{"notes":["PAIN","FUTURE_NOTE"]}"""))
    }
}
