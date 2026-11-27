package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class VisitKindsTest {
    @Test
    fun `every kind of care but the two kinds of place has one kind of doctor`() {
        val places = setOf(CareType.EMERGENCY, CareType.HOSPITAL)
        assertEquals(
            CareType.entries.filter { it !in places },
            VisitKind.entries.map(VisitKind::care).sortedBy(CareType::id),
        )
        assertEquals((1..15).toList() + (18..30).toList(), VisitKind.entries.map { it.care.id }.sorted())
    }

    @Test
    fun `the stored words never change, and the examination doctors store the examination`() {
        // Rows written by every build hold these: a rename here would turn them into another doctor.
        assertEquals(
            listOf(
                "DENTIST", "GYNECOLOGIST", "GP", "PAEDIATRICIAN", "CARDIOLOGIST", "DERMATOLOGIST", "EYES", "ENT",
                "ORTHOPAEDIST", "NEUROLOGIST", "PSYCHIATRIST", "PSYCHOLOGIST", "UROLOGIST", "ENDOCRINOLOGIST",
                "GASTROENTEROLOGIST", "LABORATORY", "IMAGING", "REHABILITATION", "SURGEON", "ONCOLOGIST",
                "ALLERGIST", "PULMONOLOGIST", "NEPHROLOGIST", "RHEUMATOLOGIST", "INFECTIOUS_DISEASES",
                "DIETITIAN", "SPORTS", "SOMNOLOGIST",
            ),
            VisitKind.entries.map(VisitKind::stored),
        )
        assertEquals(
            mapOf(
                VisitKind.DENTIST to Checkup.DENTIST,
                VisitKind.GYNECOLOGIST to Checkup.GYNECOLOGIST,
                VisitKind.GP to Checkup.GP,
                VisitKind.OPHTHALMOLOGIST to Checkup.EYES,
                VisitKind.UROLOGIST to Checkup.UROLOGIST,
            ),
            VisitKind.entries.filter { it.checkup != null }.associateWith { it.checkup },
        )
        assertEquals(listOf(Checkup.CERVICAL, Checkup.MAMMOGRAPHY, Checkup.BOWEL), SCREENING_CHECKUPS)
        assertFalse(VISIT_SPECIALTY_OTHER in VisitKind.entries.map(VisitKind::stored))
    }

    @Test
    fun `the report doctors are the same words as the diary's kinds`() {
        DoctorSpecialty.entries.filter { it != DoctorSpecialty.CUSTOM }
            .forEach { assertEquals(it.name, it.name, visitKindOf(it.name)?.stored) }
    }

    @Test
    fun `every stored value reads back, and a word nobody knows reads as another doctor`() {
        VisitKind.entries.forEach {
            assertEquals(it, visitKindOf(it.stored))
            assertEquals(it.name, it.titleRes, specialtyTitleRes(it.stored))
        }
        assertEquals(R.string.visit_kind_ophthalmologist, specialtyTitleRes(Checkup.EYES.name))
        assertEquals(R.string.checkup_dentist, specialtyTitleRes(Checkup.DENTIST.name))
        assertEquals(R.string.checkup_mammography, specialtyTitleRes(Checkup.MAMMOGRAPHY.name))
        assertEquals(R.string.checkup_bowel, specialtyTitleRes(Checkup.BOWEL.name))
        assertEquals(R.string.visit_specialty_other, specialtyTitleRes(VISIT_SPECIALTY_OTHER))
        assertEquals(R.string.visit_specialty_other, specialtyTitleRes("SOMETHING_NEW"))
        assertNull(visitKindOf(VISIT_SPECIALTY_OTHER))
        assertNull(visitKindOf(Checkup.CERVICAL.name))
    }

    @Test
    fun `a visit to the ophthalmologist winds the eye examination clock`() {
        val day = LocalDate.of(2026, 3, 2)
        val atMs = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val visit = Appointment(id = 1, specialty = VisitKind.OPHTHALMOLOGIST.stored, atMs = atMs, doneAt = atMs)
        val status = checkupStatuses(listOf(Checkup.EYES), listOf(visit), day.plusDays(10), ZoneOffset.UTC).single()
        assertEquals(day, status.lastDay)
        assertEquals(day.plusMonths(24), status.dueOn)
    }

    @Test
    fun `places are filtered by the kind of doctor, a sleep doctor's by the departments that do the work`() {
        VisitKind.entries.forEach { assertTrue(it.name, it.care in careTypesFor(it.stored)) }
        assertEquals(setOf(CareType.NEUROLOGY), careTypesFor(VisitKind.NEUROLOGIST.stored))
        assertEquals(setOf(CareType.OPHTHALMOLOGY), careTypesFor(Checkup.EYES.name))
        assertEquals(
            setOf(CareType.SLEEP_MEDICINE, CareType.NEUROLOGY, CareType.PULMONOLOGY),
            careTypesFor(VisitKind.SOMNOLOGIST.stored),
        )
        assertEquals(setOf(CareType.IMAGING), careTypesFor(Checkup.MAMMOGRAPHY.name))
        assertTrue(careTypesFor(VISIT_SPECIALTY_OTHER).isEmpty())
    }

    private val label: (String) -> String = { it.lowercase() }

    @Test
    fun `the list opens with the examinations that apply, then every doctor in order, then another doctor`() {
        val applicable = listOf(Checkup.GP, Checkup.DENTIST, Checkup.CERVICAL)
        val choices = visitChoices(applicable, VISIT_SPECIALTY_OTHER, label, naturalOrder())
        assertEquals(listOf("GP", "DENTIST", "CERVICAL"), choices.take(3))
        assertEquals(VISIT_SPECIALTY_OTHER, choices.last())
        val doctors = choices.drop(3).dropLast(1)
        assertEquals(doctors.sortedBy(label), doctors)
        // Every kind of doctor once, whoever is asking: a woman sees a urologist, a child a dentist.
        assertEquals(
            VisitKind.entries.map(VisitKind::stored).toSet(),
            (choices - VISIT_SPECIALTY_OTHER - Checkup.CERVICAL.name).toSet(),
        )
        assertEquals(choices.distinct(), choices)
        assertFalse(Checkup.MAMMOGRAPHY.name in choices)
    }

    @Test
    fun `a screening that no longer applies stays on the list of its own booking, and an unknown word is not added`() {
        val kept = visitChoices(emptyList(), Checkup.MAMMOGRAPHY.name, label, naturalOrder())
        assertEquals(Checkup.MAMMOGRAPHY.name, kept.first())
        val unknown = visitChoices(emptyList(), "SOMETHING_NEW", label, naturalOrder())
        assertFalse("SOMETHING_NEW" in unknown)
        assertEquals(VisitKind.entries.size + 1, unknown.size)
    }

    @Test
    fun `a booking needs the place, then the kind, then the doctor, and spaces are none of them`() {
        val place = "Nemocnice Na Bulovce"
        val dentist = VisitKind.DENTIST.stored
        assertEquals(VisitMissing.PLACE, visitMissing("", VISIT_SPECIALTY_UNANSWERED, " ", recording = false))
        assertEquals(VisitMissing.PLACE, visitMissing("  ", dentist, "Dr Novák", recording = false))
        assertEquals(VisitMissing.KIND, visitMissing(place, VISIT_SPECIALTY_UNANSWERED, "", recording = false))
        assertEquals(VisitMissing.KIND, visitMissing(place, " ", "Dr Novák", recording = false))
        assertEquals(VisitMissing.DOCTOR, visitMissing(place, dentist, "", recording = false))
        assertNull(visitMissing(place, dentist, "Dr Novák", recording = false))
        // Another doctor is an answer the person gave, not a missing one.
        assertNull(visitMissing(place, VISIT_SPECIALTY_OTHER, "Dr Novák", recording = false))
    }

    @Test
    fun `a visit written down afterwards needs only its kind, so no name has to be made up`() {
        assertNull(visitMissing("", Checkup.MAMMOGRAPHY.name, "", recording = true))
        assertNull(visitMissing("", VisitKind.LABORATORY.stored, " ", recording = true))
        assertEquals(
            VisitMissing.KIND,
            visitMissing("Clinic A", VISIT_SPECIALTY_UNANSWERED, "Dr Novák", recording = true),
        )
    }

    @Test
    fun `a new booking's kind is no kind at all, and never one on the list`() {
        val choices = visitChoices(listOf(Checkup.GP), VISIT_SPECIALTY_UNANSWERED, label, naturalOrder())
        assertFalse(VISIT_SPECIALTY_UNANSWERED in choices)
        assertNull(visitKindOf(VISIT_SPECIALTY_UNANSWERED))
        assertTrue(careTypesFor(VISIT_SPECIALTY_UNANSWERED).isEmpty())
    }

    @Test
    fun `the doctors seen at the chosen place come first, the clinic typed either way`() {
        fun visit(id: Long, doctor: String, place: String) =
            Appointment(id = id, specialty = VISIT_SPECIALTY_OTHER, doctor = doctor, place = place, atMs = id * 1_000)
        val visits = listOf(
            visit(1, "Dr Black", "Clinic A"),
            visit(2, "Dr White", "clinic  a "),
            visit(3, "Dr Green", "Clinic B"),
            visit(4, "Dr Black", "Clinic B"),
            visit(5, "", "Clinic A"),
        )
        assertEquals(listOf("Dr White", "Dr Black") to listOf("Dr Green"), typedBefore(visits, false, "Clinic A"))
        assertEquals(emptyList<String>() to listOf("Dr Black", "Dr Green", "Dr White"), typedBefore(visits, false, ""))
        assertEquals(
            emptyList<String>() to listOf("Clinic A", "Clinic B", "clinic  a"),
            typedBefore(visits, true, "Clinic A"),
        )
    }

    @Test
    fun `a searchable list matches every word typed, without case or marks`() {
        assertTrue(choiceMatches("Cardiologist", "card"))
        assertTrue(choiceMatches("Невролог (невропатолог)", "НЕВРОПАТ"))
        assertTrue(choiceMatches("Imaging: X-ray, ultrasound, CT, MRI", "mri x ray"))
        assertTrue(choiceMatches("Dermatológ", "dermatolog"))
        assertTrue(choiceMatches("Surgeon", "  "))
        assertFalse(choiceMatches("Surgeon", "surgeon dentist"))
        assertFalse(choiceMatches("Oncologist", "cardio"))
    }

    /** One string of a resource file, as the app ships it. */
    private fun shipped(locale: String, name: String): String {
        val text = File("src/main/res/values$locale/visit_kinds.xml").readText()
        return Regex("<string name=\"$name\">([^<]*)</string>").find(text)!!.groupValues[1]
    }

    @Test
    fun `the other names a doctor goes by find the row, the dentist first`() {
        assertEquals(R.string.visit_kind_search_dentist, VisitKind.DENTIST.searchRes)
        assertEquals(R.string.visit_kind_search_gp, VisitKind.GP.searchRes)
        assertTrue(choiceMatches("Стоматолог", "дантист", shipped("-ru", "visit_kind_search_dentist")))
        assertTrue(choiceMatches("Терапевт", "семейный", shipped("-ru", "visit_kind_search_gp")))
        assertTrue(choiceMatches("General practitioner", "GP", shipped("", "visit_kind_search_gp")))
        assertTrue(choiceMatches("General practitioner", "family doctor", shipped("", "visit_kind_search_gp")))
        assertTrue(choiceMatches("Paediatrician", "pediatr", shipped("", "visit_kind_search_paediatrician")))
        // The extra words widen the search for their own row and no other.
        assertFalse(choiceMatches("Терапевт", "дантист", shipped("-ru", "visit_kind_search_gp")))
        assertFalse(choiceMatches("Стоматолог", "дантист"))
    }

    @Test
    fun `every kind with other names has them in English and Russian`() {
        val en = File("src/main/res/values/visit_kinds.xml").readText()
        val ru = File("src/main/res/values-ru/visit_kinds.xml").readText()
        val names = Regex("<string name=\"(visit_kind_search_\\w+)\"").findAll(en).map { it.groupValues[1] }.toList()
        assertEquals(VisitKind.entries.count { it.searchRes != null }, names.size)
        names.forEach { assertTrue(it, "<string name=\"$it\">" in ru) }
    }
}
