package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The two decisions the report page makes before anything may leave the phone: whether there is
 * enough read to build a sheet at all, and whether the sheet standing is the one for the choices on
 * screen.
 *
 * Both can be answered too early. The lists the sheet is built from start empty, so a sheet saying
 * "nothing recorded" could be saved in the moment before Room answers; and a document the system
 * picker created could be dropped, empty, when the picker's answer arrives before any sheet.
 *
 * And, after those, the page's smaller promises: which rows it offers this person at all, what its
 * last line says of the sensitive sections, what its question about them promises, and how it
 * writes a date.
 */
class DoctorReportScreenTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val profile = UserProfile(sex = "FEMALE", weightKg = 68.0, heightCm = 168, birthDate = "1990-04-10")
    private val work: Pair<Set<DayOfWeek>, Int> = setOf(DayOfWeek.FRIDAY) to 540

    private fun sources(
        journal: List<JournalEntry>? = emptyList(),
        days: List<DaySteps>? = emptyList(),
        medications: List<Medication>? = emptyList(),
        visits: List<Appointment>? = emptyList(),
        history: List<MedicalHistoryRow>? = emptyList(),
        work: Pair<Set<DayOfWeek>, Int>? = this.work,
    ) = reportSources(profile, journal, days, medications, visits, history, work)

    /** One source still unread is no sheet: an empty list in its place would print "nothing recorded". */
    @Test
    fun noSheetIsBuiltUntilEverySourceIsRead() {
        assertNull(sources(journal = null))
        assertNull(sources(days = null))
        assertNull(sources(medications = null))
        assertNull(sources(visits = null))
        assertNull(sources(history = null))
        assertNull(sources(work = null))
    }

    /** Read and found empty is an answer, and the sheet is built on it with the person's own work settings. */
    @Test
    fun aRecordReadAndFoundEmptyIsBuiltOn() {
        val days = listOf(DaySteps(today.toString(), 9_000))

        val built = sources(days = days)!!

        assertEquals(profile, built.profile)
        assertEquals(days, built.days)
        assertTrue(built.entries.isEmpty())
        assertEquals(work.first, built.workDaysOff)
        assertEquals(work.second, built.longDayMinutes)
    }

    /**
     * A document the picker created is written with the sheet for the choices on screen and with no
     * other: none yet means it waits, and one built for another window is not what was previewed.
     */
    @Test
    fun onlyTheSheetBuiltForTheChoicesOnScreenLeaves() {
        val request = ReportRequest(from = today.minusMonths(3), to = today)
        val built = doctorReport(sources()!!, request, today, zone = ZoneOffset.UTC)

        assertNull(null.builtFor(request))
        assertSame(built, built.builtFor(request))
        assertNull(built.builtFor(request.copy(from = today.minusMonths(6))))
        assertNull(built.builtFor(request.copy(parts = setOf(ReportPart.PRESSURE))))
    }

    /**
     * A page left while the document still waits for its sheet removes the document; an Activity
     * taken down with its state saved keeps it, because the page that comes back writes it.
     */
    @Test
    fun aWaitingDocumentIsGivenUpOnlyWhenThePageIsLeftForGood() {
        // Another screen of the app opened over the page: the Activity lives on.
        assertTrue(abandonsWaitingDocument(destroyed = false, finishing = false))
        // The app closed with Back.
        assertTrue(abandonsWaitingDocument(destroyed = true, finishing = true))
        // A rotation, or the system reclaiming the Activity in the background: the wait is saved.
        assertFalse(abandonsWaitingDocument(destroyed = true, finishing = false))
    }

    /**
     * The wait is saved either way, and comes back only with its document: a page returned to from
     * under another screen has the document removed as it went, and writing the sheet into it then
     * would put up the export's error card.
     */
    @Test
    fun aWaitComesBackOnlyWithTheDocumentItWaitsFor() {
        // Covered by another screen and come back to: the document went, and the wait goes with it.
        assertTrue(abandonsWaitingDocument(destroyed = false, finishing = false))
        assertFalse(waitOutlivesReturn(arrivedBack = true))
        // Put up again with its Activity: the document was kept, and the wait writes it.
        assertFalse(abandonsWaitingDocument(destroyed = true, finishing = false))
        assertTrue(waitOutlivesReturn(arrivedBack = false))
    }

    /**
     * "Select all" says it leaves the sensitive section out, and under sixteen there is no such
     * section on the list to leave out: the line then says what the sweep is, every row.
     */
    @Test
    fun selectAllNamesTheSensitiveSectionOnlyWhereOneIsOffered() {
        val child = reportPartsFor(profile.copy(birthDate = today.minusYears(13).toString()), today)

        assertEquals(R.string.report_select_all_body, selectAllBodyRes(reportPartsFor(profile, today)))
        assertEquals(R.string.report_select_all_body_plain, selectAllBodyRes(child))
    }

    /**
     * The age gate takes its rows off the picker rather than greying them: under sixteen there is
     * no sensitive group to answer for, and under eighteen no alcohol and tobacco row.
     */
    @Test
    fun thePickerOffersNothingTheAgeGateKeepsFromThisPerson() {
        fun offered(years: Long, sex: String = "MALE") =
            reportPartsFor(profile.copy(sex = sex, birthDate = today.minusYears(years).toString()), today)
        val sensitive = ReportPart.entries.filter { it.sensitivity == DataSensitivity.SENSITIVE }

        listOf("MALE", "FEMALE").forEach { sex ->
            assertTrue(offered(13, sex).none { it in sensitive || it == ReportPart.SOCIAL })
            assertEquals(reportPartsFor(sex) - sensitive.toSet() - ReportPart.SOCIAL, offered(13, sex))
            assertEquals(reportPartsFor(sex) - ReportPart.SOCIAL, offered(17, sex))
            assertEquals(reportPartsFor(sex), offered(18, sex))
        }
        // A birth date nobody can read closes the gate, as it does everywhere else in the app.
        assertFalse(ReportPart.SOCIAL in reportPartsFor(profile.copy(birthDate = ""), today))
        // And a doctor's preset is cut by the same list: the GP's alcohol row is not a child's.
        assertFalse(ReportPart.SOCIAL in preselectedParts(DoctorSpecialty.GP, "MALE").intersect(offered(13)))
    }

    /**
     * The preview's last line answers for the sheet, heading included. A sensitive section ticked
     * over a period that holds nothing is still printed as a heading over "no data recorded", and
     * the line under it says it is included, not "Sexual activity: not included".
     */
    @Test
    fun aTickedSensitiveSectionWithNothingInItIsStillAnnouncedAsIncluded() {
        fun built(vararg parts: ReportPart) = doctorReport(
            sources()!!,
            ReportRequest(from = today.minusMonths(3), to = today, parts = parts.toSet()),
            today,
            zone = ZoneOffset.UTC,
        )

        listOf(ReportPart.SEXUAL_HEALTH, ReportPart.SEXUAL_SYMPTOMS).forEach { part ->
            val sheet = built(part)
            assertEquals(SectionPresence.NO_DATA, sheet.sections.single { it.part == part }.presence)
            assertTrue("$part", sheet.carriesSexualActivity)
            assertFalse("$part", sheet.carriesMornings)
        }
        assertTrue(built(ReportPart.MORNING_ERECTIONS).carriesMornings)
        assertFalse(built(ReportPart.MORNING_ERECTIONS).carriesSexualActivity)
        assertFalse(built(ReportPart.PRESSURE).carriesSexualActivity)
    }

    /**
     * Nothing ticked is said so under an answered allergy question too. The allergies are on every
     * sheet as the profile's own answer, and a list of them must not make a sheet of the profile
     * alone count as full: that would hide the line saying that nothing is ticked.
     */
    @Test
    fun nothingTickedIsSaidSoUnderAnAnsweredAllergyQuestion() {
        val allergic = profile.copy(allergies = AllergyAnswer.Known(setOf(Allergen.PEANUTS)).stored())
        val sources = reportSources(allergic, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), work)!!
        val request = ReportRequest(from = today.minusMonths(3), to = today, parts = emptySet())

        val built = doctorReport(sources, request, today, zone = ZoneOffset.UTC)

        assertEquals(SectionPresence.PRESENT, built.sections.single { it.part == ReportPart.ALLERGIES }.presence)
        assertTrue(built.empty)
        assertEquals(R.string.report_nothing_chosen, emptyNoteRes(built))
    }

    /** The question that lets sexual activity onto the sheet promises what that section prints, and no more. */
    @Test
    fun theSexualActivityQuestionPromisesNoSymptoms() {
        val ui = File("src/main/res/values/ui.xml").readText()
        listOf("report_sensitive_body", "report_sexual_summary_body", "report_sexual_detailed_body").forEach { name ->
            val text = ui.substringAfter("<string name=\"$name\">").substringBefore("</string>")
            assertFalse("$name still promises symptoms", text.contains("symptom", ignoreCase = true))
        }
    }

    /**
     * The international profile names no country, so its date follows the language: day, month in
     * letters, year, except where the language writes the year first. A national profile keeps its
     * country's order in every language.
     */
    @Test
    fun theInternationalDateFollowsALanguageThatWritesTheYearFirst() {
        val day = LocalDate.of(2026, 9, 5)
        fun printed(country: CountryProfile, tag: String): String {
            val locale = Locale.forLanguageTag(tag)
            return day.format(reportDayFormat(locale, country.datePatternIn(locale)))
        }

        assertEquals("5 Sep 2026", printed(InternationalProfile, "en"))
        assertEquals("2026年9月5日", printed(InternationalProfile, "ja"))
        assertEquals("2026年9月5日", printed(InternationalProfile, "zh"))
        assertEquals("2026년 9월 5일", printed(InternationalProfile, "ko"))
        assertEquals("05.09.2026", printed(GermanyProfile, "ja"))
        assertEquals("2026年9月5日", day.format(reportDayFormat(Locale.JAPANESE)))
        // The chart's axis drops the year from the same pattern, and still reads as a date.
        assertEquals("M月d日", shortDayPattern(InternationalProfile.datePatternIn(Locale.JAPANESE)))
        assertEquals("M월 d일", shortDayPattern(InternationalProfile.datePatternIn(Locale.KOREAN)))
    }
}
