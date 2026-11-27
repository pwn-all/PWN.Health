package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** What the medical history decides before anything is drawn: the form's years and days, and the current answer. */
class MedicalHistoryTest {
    private val today = LocalDate.parse("2026-11-15")

    /**
     * A history is mostly old, and Material's picker takes no date outside its years: with the
     * medicine courses' picker, whose years begin two before this one, a diagnosis from 2015 or a
     * vaccination from 1950 could only be saved without a date.
     */
    @Test
    fun aNewRecordCanBeDatedDecadesBack() {
        val years = historyYearRange(initial = today, today = today)

        assertTrue(1950 in years)
        assertTrue(2015 in years)
        assertTrue(today.year in years)
    }

    /** A history says what has been: its picker's years stop at this one. */
    @Test
    fun theYearsEndWithThisOne() {
        assertEquals(today.year, historyYearRange(initial = today, today = today).last)
    }

    // --- which days the form takes -----------------------------------------------------------------

    /**
     * Neither picker offers a vaccination dated 2030 or an "until" set before its "from", which the
     * doctor's sheet would print as written; today itself is offered, and so is one day for both ends.
     */
    @Test
    fun aDateIsNeverAheadOfTodayNorOnTheWrongSideOfTheOther() {
        val start = LocalDate.parse("2025-03-10")

        assertTrue(historyDayAllowed(today, today))
        assertFalse(historyDayAllowed(today.plusDays(1), today))
        // The end, against a start already given.
        assertTrue(historyDayAllowed(start, today, notBefore = start))
        assertFalse(historyDayAllowed(LocalDate.parse("2025-02-01"), today, notBefore = start))
        // The start, against an end already given.
        assertTrue(historyDayAllowed(start, today, notAfter = start))
        assertFalse(historyDayAllowed(start.plusDays(1), today, notAfter = start))
    }

    /**
     * The day a file's examination was made is a day of the history too. Its dialog takes the
     * history's days and not only its years, or an X-ray could be dated next month. Pinned in the source
     * because the choice is one call inside a composable.
     */
    @Test
    fun aFilesExaminationDayIsAskedWithTheHistorysOwnPicker() {
        val text = File("src/main/java/com/pwnall/health/AttachmentDetailsDialog.kt").readText()
        val dialog = text.substringAfter("private fun StudyDayDialog(").substringBefore("\n}\n")

        assertTrue(dialog, "HistoryDayDialog(initial ?: today, allowed = { historyDayAllowed(it, today) }" in dialog)
    }

    /**
     * The picker has only OK and Cancel, so there a date once picked cannot be taken back: an "until"
     * set by mistake on a diagnosis that goes on would mean deleting the record. The form's own buttons
     * empty the fields, and what is saved is a row with no day in that column.
     */
    @Test
    fun aPickedDateCanBeTakenAwayAgain() {
        val form = HistoryFormState(HistoryKind.CONDITION)
        form.show(
            HistoryKind.CONDITION,
            row(HistoryKind.CONDITION, "Asthma", written = today, start = today.minusYears(4), end = today),
        )

        form.end = null
        assertEquals("", form.toRow(0).endDay)
        assertEquals(today.minusYears(4).toString(), form.toRow(0).startDay)
        form.start = null
        assertEquals("", form.toRow(0).startDay)
        assertTrue(form.canSave)
    }

    /** A record needs a name, and a row that ends before it began — a backup can hold one — is not saved again. */
    @Test
    fun aRecordIsSavedWithANameAndItsDatesInOrder() {
        val form = HistoryFormState(HistoryKind.CONDITION)
        form.show(HistoryKind.CONDITION, null)
        assertFalse(form.canSave)

        form.title = "Asthma"
        assertTrue(form.canSave)
        form.start = LocalDate.parse("2025-03-10")
        form.end = LocalDate.parse("2025-02-01")
        assertFalse(form.canSave)
        form.end = form.start
        assertTrue(form.canSave)
    }

    /**
     * The picker shows a date outside its years as nothing chosen, with OK unlit, so the year a
     * record opens on is offered wherever it lies — a backup can carry any date at all.
     */
    @Test
    fun theYearARecordOpensOnIsAlwaysOffered() {
        assertTrue(1899 in historyYearRange(LocalDate.parse("1899-12-31"), today))
        assertTrue(2040 in historyYearRange(LocalDate.parse("2040-01-01"), today))
    }

    // --- which answer is the current one -----------------------------------------------------------

    /** A row saved at noon of [written] in the phone's own zone, which is the zone the order reads. */
    private fun row(
        kind: HistoryKind,
        title: String,
        written: LocalDate,
        start: LocalDate? = null,
        end: LocalDate? = null,
    ) = MedicalHistoryRow(
        key = "$kind-$title",
        kind = kind.name,
        title = title,
        startDay = start?.toString().orEmpty(),
        endDay = end?.toString().orEmpty(),
        updatedAt = written.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    private fun List<MedicalHistoryRow>.titlesOf(kind: HistoryKind) = ofHistoryKind(kind).map { it.title }

    /**
     * The form opens a new answer on its first option with no date, so the commonest record is saved
     * undated. Sorted by an empty day under every dated row, it would leave "pregnant since January" the
     * current answer on the card, the page and the doctor's sheet after "not pregnant" was saved.
     */
    @Test
    fun anAnswerSavedWithoutADateIsCurrentFromTheDayItWasWritten() {
        val pregnancy = listOf(
            row(HistoryKind.PREGNANCY, "PREGNANT", written = today.minusMonths(10), start = today.minusMonths(10)),
            row(HistoryKind.PREGNANCY, "NOT_PREGNANT", written = today),
        )
        val methods = listOf(
            row(HistoryKind.CONTRACEPTION, "COMBINED_PILL", written = today.minusYears(2), start = today.minusYears(2)),
            row(HistoryKind.CONTRACEPTION, "NONE", written = today.minusDays(1)),
        )

        assertEquals(listOf("NOT_PREGNANT", "PREGNANT"), pregnancy.titlesOf(HistoryKind.PREGNANCY))
        assertEquals(listOf("NONE", "COMBINED_PILL"), methods.titlesOf(HistoryKind.CONTRACEPTION))
    }

    /** The day it was written is all an undated answer has: one dated after that day is the newer. */
    @Test
    fun anAnswerDatedAfterAnUndatedOneWasWrittenReplacesIt() {
        val methods = listOf(
            row(HistoryKind.CONTRACEPTION, "NONE", written = today.minusMonths(6)),
            row(HistoryKind.CONTRACEPTION, "IUD_COPPER", written = today, start = today.minusMonths(2)),
        )

        assertEquals(listOf("IUD_COPPER", "NONE"), methods.titlesOf(HistoryKind.CONTRACEPTION))
    }

    /**
     * Only an answer with no date at all is placed by the day it was written. One that carries an end
     * and no start says when it stopped, so it stays behind; and in a list, where there is no
     * current row to get wrong, an undated record stays under the dated ones as the sheet prints it.
     */
    @Test
    fun anEndedAnswerAndAnUndatedListRowStayBehindTheDatedOnes() {
        val methods = listOf(
            row(HistoryKind.CONTRACEPTION, "COMBINED_PILL", written = today, end = today.minusYears(1)),
            row(HistoryKind.CONTRACEPTION, "CONDOM", written = today.minusYears(3), start = today.minusYears(3)),
        )
        val conditions = listOf(
            row(HistoryKind.CONDITION, "Migraine", written = today),
            row(HistoryKind.CONDITION, "Asthma", written = today.minusYears(4), start = today.minusYears(4)),
        )

        assertEquals(listOf("CONDOM", "COMBINED_PILL"), methods.titlesOf(HistoryKind.CONTRACEPTION))
        assertEquals(listOf("Asthma", "Migraine"), conditions.titlesOf(HistoryKind.CONDITION))
    }

    /**
     * "Combined pill, until last June" is not tagged as the method in use, as it would be if the newest
     * row were the current one whatever its end. An answer that has ended is no longer standing, and the older
     * rows under it are answers it replaced — none of them comes back as the current one.
     */
    @Test
    fun anAnswerThatHasEndedIsNoLongerTheCurrentOne() {
        val pill = row(
            HistoryKind.CONTRACEPTION,
            "COMBINED_PILL",
            written = today.minusYears(2),
            start = today.minusYears(2),
            end = today.minusMonths(5),
        )
        val earlier = today.minusYears(3)
        val before = row(HistoryKind.CONTRACEPTION, "CONDOM", written = earlier, start = earlier)

        assertNull(listOf(before, pill).currentAnswer(HistoryKind.CONTRACEPTION, today))
        assertEquals("CONDOM", listOf(before).currentAnswer(HistoryKind.CONTRACEPTION, today)?.title)
        // Until today is still today's answer; until yesterday is not.
        val untilToday = pill.copy(endDay = today.toString())
        assertEquals(untilToday, listOf(before, untilToday).currentAnswer(HistoryKind.CONTRACEPTION, today))
        assertNull(listOf(before, untilToday).currentAnswer(HistoryKind.CONTRACEPTION, today.plusDays(1)))
    }

    /** A list has no current row: its newest diagnosis is one of several, not the answer. */
    @Test
    fun aListHasNoCurrentAnswer() {
        val conditions = listOf(row(HistoryKind.CONDITION, "Asthma", written = today, start = today.minusYears(4)))

        assertNull(conditions.currentAnswer(HistoryKind.CONDITION, today))
    }

    /**
     * An undated answer is placed by the day it was given, not by the day of the last save, or last
     * year's "none", opened to fix a word in its note, would become this week's answer in front of the
     * one given since. A corrected note keeps the answer's day; a changed option is the
     * answer given again, and so is a record written for the first time.
     */
    @Test
    fun correctingANoteDoesNotGiveTheAnswerAgain() {
        val old = row(HistoryKind.CONTRACEPTION, "NONE", written = today.minusYears(1))
        val since = row(HistoryKind.CONTRACEPTION, "IUD_COPPER", written = today.minusMonths(2))
        val form = HistoryFormState(HistoryKind.CONTRACEPTION)
        val now = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        form.show(HistoryKind.CONTRACEPTION, old)
        form.note = "as told to the nurse"
        val corrected = form.toRow(now)

        assertEquals(now, corrected.updatedAt)
        assertEquals(old.answeredAt, corrected.answeredAt)
        assertEquals(listOf("IUD_COPPER", "NONE"), listOf(corrected, since).titlesOf(HistoryKind.CONTRACEPTION))
        form.title = "CONDOM"
        assertEquals(now, form.toRow(now).answeredAt)
        assertEquals(listOf("CONDOM", "IUD_COPPER"), listOf(form.toRow(now), since).titlesOf(HistoryKind.CONTRACEPTION))
        form.show(HistoryKind.CONTRACEPTION, null)
        assertEquals(now, form.toRow(now).answeredAt)
    }

    // --- the form's record and what it is kept as --------------------------------------------------

    /** Two records of one day stand in the order they were written down, the newer first. */
    @Test
    fun recordsOfTheSameDayStandNewestWrittenFirst() {
        val day = today.minusYears(1)
        val conditions = listOf(
            row(HistoryKind.CONDITION, "Asthma", written = today.minusDays(3), start = day),
            row(HistoryKind.CONDITION, "Eczema", written = today, start = day),
        )

        assertEquals(listOf("Eczema", "Asthma"), conditions.titlesOf(HistoryKind.CONDITION))
    }

    /**
     * A correction keeps the key the row had, which is what makes it the same record on the next
     * phone a backup reaches; a new record gets a key of its own. Spaces typed around a word are
     * not part of it.
     */
    @Test
    fun aCorrectionKeepsItsKeyAndANewRecordGetsOne() {
        val form = HistoryFormState(HistoryKind.CONDITION)
        form.show(HistoryKind.CONDITION, row(HistoryKind.CONDITION, "Asthma", written = today))
        form.title = "  Asthma, allergic  "
        form.detail = " Dr Novak "

        val corrected = form.toRow(0)
        assertEquals("CONDITION-Asthma", corrected.key)
        assertEquals("Asthma, allergic", corrected.title)
        assertEquals("Dr Novak", corrected.detail)

        form.show(HistoryKind.IMMUNISATION, null)
        form.title = "Tetanus"
        val first = form.toRow(0).key
        assertTrue(first.isNotBlank())
        assertFalse(first == form.toRow(0).key)
        assertEquals(HistoryKind.IMMUNISATION.name, form.toRow(0).kind)
    }

    /**
     * The form outlives its process in the saved state: what comes back writes the very row the form
     * would have written, its key and the day its answer was given included — so a note corrected
     * before the process died is still not the answer given again.
     */
    @Test
    fun aFormBroughtBackFromSavedStateWritesTheSameRow() {
        val form = HistoryFormState(HistoryKind.CONDITION)
        form.show(
            HistoryKind.CONTRACEPTION,
            row(HistoryKind.CONTRACEPTION, "IUD_COPPER", written = today.minusYears(1), start = today.minusYears(1))
                .copy(detail = "Copper T380A", endDay = today.toString()),
        )
        form.note = "checked in March"

        val saved = with(HistoryFormState.Saver) { SaverScope { true }.save(form) }!!
        val back = HistoryFormState.Saver.restore(saved)!!

        assertEquals(HistoryKind.CONTRACEPTION, back.kind)
        assertEquals(form.toRow(42L), back.toRow(42L))
        // The answer was given a year ago, not at the moment the restored form saves.
        assertFalse(back.toRow(42L).answeredAt == 42L)
    }

    /** Pregnancy is asked of whoever the app asks about a cycle; every other kind of everyone. */
    @Test
    fun pregnancyIsAskedOnlyOnTheCycleRule() {
        assertFalse(HistoryKind.PREGNANCY in historyKindsFor("MALE"))
        assertTrue(HistoryKind.PREGNANCY in historyKindsFor("FEMALE"))
        assertEquals(HistoryKind.entries - HistoryKind.PREGNANCY, historyKindsFor("MALE"))
    }

    /** A code this version does not know, or a kind it does not, is no option rather than a guess. */
    @Test
    fun anUnknownCodeOrKindNamesNoOption() {
        val known = row(HistoryKind.CONTRACEPTION, "IMPLANT", written = today)

        assertEquals("IMPLANT", known.historyOption()?.code)
        assertNull(known.copy(title = "VAGINAL_SPONGE").historyOption())
        assertNull(known.copy(kind = "DEVICE").historyOption())
        assertNull(row(HistoryKind.CONDITION, "IMPLANT", written = today).historyOption())
    }
}
