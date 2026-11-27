package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class MedicalVisitHistoryTest {
    @Test
    fun historyUsesVisitDatesAndKeepsOldRecordsButExcludesBookingsAndFutureVisits() {
        val visits = listOf(
            Appointment(id = 1, specialty = "GP", atMs = 10, doneAt = 900),
            Appointment(id = 2, specialty = "GP", atMs = 800, doneAt = 800),
            Appointment(id = 3, specialty = "GP", atMs = 700),
            Appointment(id = 4, specialty = "GP", atMs = 1001, doneAt = 900),
        )
        assertEquals(listOf(2L, 1L), completedVisits(visits, null, null, 1000).map { it.id })
    }

    @Test
    fun doctorFilterIsScopedToTheSelectedSpecialty() {
        val visits = listOf(
            Appointment(id = 1, specialty = "GP", doctor = "Анна", atMs = 10, doneAt = 10),
            Appointment(id = 2, specialty = "GP", doctor = "Иван", atMs = 20, doneAt = 20),
            Appointment(id = 3, specialty = "DENTIST", doctor = "Анна", atMs = 30, doneAt = 30),
        )
        assertEquals(listOf(1L), completedVisits(visits, "GP", "Анна", 1000).map { it.id })
        assertEquals(listOf(2L, 1L), completedVisits(visits, "GP", null, 1000).map { it.id })
        assertEquals(listOf(3L, 1L), completedVisits(visits, null, "Анна", 1000).map { it.id })
    }

    /**
     * The doctors are listed as the person's language orders them, whatever the case and the letters:
     * by code point "Ёлкин" would come first and "Борис" before "анна". The one chosen stays, at the end,
     * after it has left the record.
     */
    @Test
    fun doctorsAreListedInTheOrderOfThePersonsLanguage() {
        val visits = listOf("Ёлкин", "анна", "Борис", "Борис", " ").mapIndexed { i, name ->
            Appointment(id = i.toLong(), specialty = "GP", doctor = name, atMs = 10, doneAt = 10)
        }
        val russian = Collator.getInstance(Locale.forLanguageTag("ru"))

        assertEquals(listOf("анна", "Борис", "Ёлкин"), visitDoctors(visits, null, null, russian))
        assertEquals(listOf("анна", "Борис", "Ёлкин", "Пётр"), visitDoctors(visits, "GP", "Пётр", russian))
        assertEquals(listOf("Пётр"), visitDoctors(visits, "DENTIST", "Пётр", russian))
    }
}
