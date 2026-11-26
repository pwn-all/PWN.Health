package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A row the app names itself was given its name as text when it was written, so a day logged in
 * Russian and read in English would list "Вода" beside "Water". [shownTitle] looks the name up again
 * where the row says what it is, and leaves alone what a person or another app named.
 *
 * [NamedResources] answers every string with its resource's name, so each line says which one was asked for.
 */
class ShownTitleTest {
    private val resources = NamedResources()

    private fun row(
        kind: String,
        title: String,
        code: String? = null,
        payload: String? = null,
        marker: String? = null,
    ) = JournalEntry(
        day = "2026-11-19",
        occurredAt = 1_795_000_000_000L,
        kind = kind,
        title = title,
        calories = 0.0,
        activityCode = code,
        measurementPayloadJson = payload,
        healthConnectRecordKey = marker,
    )

    @Test
    fun aKindWithOneNameIsCalledByItInTheLanguageOnScreen() {
        assertEquals("water_title", row(KIND_WATER, "Вода").shownTitle(resources))
        assertEquals("cycle_title", row(KIND_CYCLE, "Начало месячных").shownTitle(resources))
        assertEquals("cycle_period_end", row(KIND_CYCLE_END, "Конец месячных").shownTitle(resources))
        assertEquals("smoking_title", row(KIND_SMOKING, "Курение").shownTitle(resources))
        assertEquals("pain_title", row(KIND_PAIN, "Боль").shownTitle(resources))
        assertEquals("work_title", row(KIND_WORK, "Работа").shownTitle(resources))
        // An imported glass of water is still a glass of water: the kind has no other name to keep.
        assertEquals("water_title", row(KIND_WATER, "Hydration", marker = "Health Connect:x:1").shownTitle(resources))
    }

    /** The answer is its enum name in the payload, which is what the details line under it already reads. */
    @Test
    fun aCycleAnswerIsNamedFromItsPayload() {
        val flow = row(KIND_CYCLE_LOG, "Скудные", payload = """{"cycle":"FLOW_LIGHT"}""")
        assertEquals("cycle_flow_light", flow.shownTitle(resources))
        assertEquals("Скудные", row(KIND_CYCLE_LOG, "Скудные", payload = "{}").shownTitle(resources))
    }

    /**
     * The editor writes the catalogue's name over a workout's title on every save, so there is no name
     * of the person's own to lose. An import files "Morning run" under the catalogue's running and
     * keeps its own name, and a session read from a sensor has a code the catalogue does not hold.
     */
    @Test
    fun aCatalogueExerciseIsNamedByTheCatalogueAndAnImportedOneByItself() {
        assertEquals("activity_run", row(KIND_ACTIVITY, "Бег", code = "RUN").shownTitle(resources))
        assertEquals("activity_run", row(KIND_NOT_DONE, "Бег", code = "RUN").shownTitle(resources))
        val imported = row(KIND_ACTIVITY, "Morning run", code = "RUN", marker = "Strava:activity:Morning run:1")
        assertEquals("Morning run", imported.shownTitle(resources))
        assertEquals(
            "Run with a sensor",
            row(KIND_ACTIVITY, "Run with a sensor", code = "BLUETOOTH_RSCS").shownTitle(resources),
        )
        assertEquals("breathing_title", row(KIND_BREATHING, "Дыхание").shownTitle(resources))
        assertEquals(
            "Box breathing",
            row(KIND_BREATHING, "Box breathing", marker = "Health Connect:x:2").shownTitle(resources),
        )
    }

    @Test
    fun whatAPersonNamedKeepsItsWords() {
        assertEquals("Борщ", row(KIND_EAT, "Борщ").shownTitle(resources))
        assertEquals("Ибупрофен", row(KIND_MED, "Ибупрофен").shownTitle(resources))
        assertEquals("Push day", row(KIND_PLAN, "Push day").shownTitle(resources))
        // A measurement is still shown as it was stored, and so is a cup with milk: it is a meal the
        // food editor may have renamed.
        assertEquals("Вес", row(KIND_MEASUREMENT, "Вес").shownTitle(resources))
        val latte = cupEntry("Мой латте", CaffeineDrink.CAPPUCCINO, 250, "2026-11-19", 1_795_000_000_000L)
        assertEquals("Мой латте", latte.shownTitle(resources))
    }

    /**
     * A black cup keeps its drink by name, a symptom its topic in the payload, a lab row what it holds:
     * each was stored as the words of the day it was written, "Эспрессо" in an English list.
     */
    @Test
    fun aCupASymptomAndALabRowAreNamedByWhatTheyHold() {
        val espresso = cupEntry("Эспрессо", CaffeineDrink.ESPRESSO, 60, "2026-11-19", 1_795_000_000_000L)
        assertEquals("caffeine_espresso", espresso.shownTitle(resources))
        assertEquals("caffeine_title", row(KIND_CAFFEINE, "Кофеин").shownTitle(resources))
        assertEquals(
            "Flat white",
            row(KIND_CAFFEINE, "Flat white", marker = "Health Connect:x:3").shownTitle(resources),
        )

        val nausea = encodeSymptomPayload(SymptomReport(symptom = Symptom.NAUSEA))
        assertEquals("symptom_nausea", row(KIND_SYMPTOM, "Тошнота", payload = nausea).shownTitle(resources))
        val words = encodeSymptomPayload(SymptomReport(symptom = Symptom.OTHER))
        assertEquals("Колет в боку", row(KIND_SYMPTOM, "Колет в боку", payload = words).shownTitle(resources))

        val panel = labsPayload(mapOf(LabMarker.GLUCOSE to 5.4), fasting = true)
        assertEquals("labs_title", row(KIND_LABS, "Анализы", payload = panel).shownTitle(resources))
        val scan = labDocumentEntry(AttachmentKind.ULTRASOUND, "УЗИ", "content://scan", LocalDate.of(2026, 11, 19), "")
        assertEquals("attachment_kind_ultrasound", scan.shownTitle(resources))
        val allergy = allergyPanelEntry("Аллергопанель", "content://panel", LocalDate.of(2026, 11, 19), "", emptyList())
        assertEquals("lab_add_allergy", allergy.shownTitle(resources))
    }

    /** A drink's pour and a smoking row's product are kept as names and figures, not the words of the day. */
    @Test
    fun aPourAndAProductAreKeptAsNames() {
        val wine = alcoholEntry(resources, AlcoholDrink.WINE, LocalDate.of(2026, 11, 19), ml = 175, abvTenths = 135)
        assertEquals(Pour(AlcoholDrink.WINE, 175, 135), pourOf(wine))
        assertEquals("", wine.subtitle)
        assertEquals(null, pourOf(row(KIND_ALCOHOL, "Beer")))
        val stick = smokingEntry(resources, TobaccoProduct.HEATED, LocalDate.of(2026, 11, 19))
        assertEquals(TobaccoProduct.HEATED, tobaccoOf(stick))
    }

    /**
     * The ticks of an act: the day list names them from the payload, in the language showing and
     * parted by its own comma, rather than printing the subtitle written in the language of the day.
     */
    @Test
    fun theTicksOfAnActAreNamedFromThePayload() {
        val day = LocalDate.of(2026, 11, 19)
        val solo = masturbationEntry(resources, day, listOf(SexNote.PAIN, SexNote.LOW_DESIRE))
        assertEquals("sex_note_painlist_commasex_note_low_desire", sexNotesText(resources, solo.measurementPayloadJson))
        val partnered = cycleLogEntry(resources, CycleLog.SEX_PROTECTED, day.toString(), listOf(SexNote.DRYNESS))
        assertEquals("sex_note_dryness", sexNotesText(resources, partnered.measurementPayloadJson))
        assertEquals("", sexNotesText(resources, masturbationEntry(resources, day, emptyList()).measurementPayloadJson))
    }
}
