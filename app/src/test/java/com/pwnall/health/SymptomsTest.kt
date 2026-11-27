package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The symptom diary: its payload, the snapshot of the journal it is told beside, and the red flags as
 * the symptom page asked them. The snapshot is facts read the way their own screens read them, so each
 * figure is pinned against a journal built by hand. The chat is in SymptomChatFlowTest and beside it.
 */
class SymptomsTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 9, 30)
    private val now = at(today, 19, 0)
    private val profile = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1990-01-01")

    private fun at(day: LocalDate, hour: Int, minute: Int) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun row(kind: String, occurredAt: Long, day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = occurredAt,
        kind = kind,
        title = "",
        calories = 0.0,
    )

    private val full = SymptomReport(
        symptom = Symptom.VOMITING,
        answers = mapOf(
            SymptomStep.AFTER_MEAL to listOf(SymptomOption.YES),
            SymptomStep.CAUSE to listOf(SymptomOption.ATE_TOO_MUCH, SymptomOption.ALCOHOL),
            SymptomStep.TIMES to listOf(SymptomOption.TWICE),
            SymptomStep.BLOOD to listOf(SymptomOption.NO),
        ),
        severity = SymptomSeverity.MODERATE,
        context = SymptomContext(
            sameDay = true,
            mealTitle = "Pasta \"al forno\" — 2×",
            mealKcal = 820,
            mealMinutesBefore = 35,
            foodKcal = 2_450,
            foodTargetKcal = 2_000,
            standardDrinks = 2.0,
        ),
    )

    @Test
    fun anEntryComesBackAsItWent() {
        assertEquals(full, decodeSymptomPayload(encodeSymptomPayload(full)))
    }

    /** JSON holds no Infinity or NaN: a figure of the snapshot that is not a number is left out, the rest kept. */
    @Test
    fun aFigureThatIsNoNumberIsLeftOutOfThePayload() {
        val context = checkNotNull(full.context)
        val endless = context.copy(standardDrinks = Double.POSITIVE_INFINITY, temperatureC = Double.NaN)
        val payload = encodeSymptomPayload(full.copy(context = endless))
        assertFalse(payload, "Infinity" in payload || "NaN" in payload)
        assertEquals(full.copy(context = context.copy(standardDrinks = null)), decodeSymptomPayload(payload))
    }

    /** The payload is part of the row's identity in a backup, so the same answers make the same string. */
    @Test
    fun thePayloadIsWrittenInOneFixedOrder() {
        val shuffled = full.copy(
            answers = mapOf(
                SymptomStep.BLOOD to listOf(SymptomOption.NO),
                SymptomStep.CAUSE to listOf(SymptomOption.ALCOHOL, SymptomOption.ATE_TOO_MUCH),
                SymptomStep.TIMES to listOf(SymptomOption.TWICE),
                SymptomStep.AFTER_MEAL to listOf(SymptomOption.YES),
            ),
            context = null,
        )
        assertEquals(
            """{"schema":1,"symptom":"VOMITING","severity":"MODERATE","answers":{"AFTER_MEAL":["YES"],""" +
                """"CAUSE":["ATE_TOO_MUCH","ALCOHOL"],"TIMES":["TWICE"],"BLOOD":["NO"]}}""",
            encodeSymptomPayload(shuffled),
        )
    }

    @Test
    fun namesThisBuildDoesNotKnowAreSkipped() {
        val payload = """{"schema":2,"symptom":"VOMITING","severity":"UNBEARABLE",""" +
            """"answers":{"CAUSE":["ALCOHOL","SPICY_FOOD"],"LATER_QUESTION":["YES"],"TIMES":["SEVEN"]},""" +
            """"context":{"today":true,"steps":450,"somethingNew":3}}"""
        val decoded = decodeSymptomPayload(payload)
        assertEquals(Symptom.VOMITING, decoded.symptom)
        assertNull(decoded.severity)
        assertEquals(mapOf(SymptomStep.CAUSE to listOf(SymptomOption.ALCOHOL)), decoded.answers)
        assertEquals(SymptomContext(sameDay = true, steps = 450), decoded.context)
        assertNull(decodeSymptomPayload("""{"symptom":"SNEEZING"}""").symptom)
        assertEquals(SymptomReport(), decodeSymptomPayload("not json"))
        assertEquals(SymptomReport(), decodeSymptomPayload(null))
    }

    /**
     * The snapshot quotes a blood pressure and a pulse, and the vitals series finds its readings by
     * key anywhere in a payload: a symptom row must never become a second copy of the reading.
     */
    @Test
    fun aSymptomRowIsNeverReadAsAMeasurement() {
        val context = SymptomContext(
            sameDay = true,
            systolic = 95,
            diastolic = 60,
            pressureBefore = 120,
            pulseBpm = 110,
            pulseBefore = 5,
            temperatureC = 38.4,
            temperatureBefore = 30,
        )
        val entry = symptomEntry("Dizziness", SymptomReport(Symptom.DIZZINESS, context = context), today, today, now)
        assertTrue(vitalHistory(listOf(entry), profile).isEmpty())
        assertEquals(KIND_SYMPTOM, entry.kind)
        assertEquals(0.0, entry.calories, 0.0)
        assertNull(entry.measurementHeartRateBpm)
        assertNull(entry.sleepStartAt)
        assertNull(entry.durationMinutes)
    }

    @Test
    fun theRowIsTheMomentItWasToldOrTheMiddayOfAnEarlierDay() {
        val told = symptomEntry("Fever", SymptomReport(Symptom.FEVER), today, today, now, note = "  shivery ")
        assertEquals(now, told.occurredAt)
        assertEquals("shivery", told.note)
        // Years back, so the day is never the real today the helper compares with.
        val earlier = LocalDate.of(2001, 3, 10)
        val filed = symptomEntry("Fever", SymptomReport(Symptom.FEVER), earlier, earlier.plusDays(2), now)
        assertEquals(earlier.toString(), filed.day)
        assertEquals(dayInstant(earlier.toString()), filed.occurredAt)
        val corrected = symptomEntry(
            "Nausea",
            SymptomReport(Symptom.NAUSEA),
            today.plusDays(1),
            today.plusDays(1),
            now + 1,
            previous = told.copy(id = 9),
        )
        assertEquals(9L, corrected.id)
        assertEquals(told.day, corrected.day)
        assertEquals(told.occurredAt, corrected.occurredAt)
    }

    /** A journal with something of everything, told at 19:00. */
    private fun journal(): List<JournalEntry> {
        val yesterday = today.minusDays(1)
        return listOf(
            row("EAT", at(today, 8, 0)).copy(title = "Porridge", calories = 400.0, caffeineMg = 50.0),
            row("EAT", at(today, 18, 25)).copy(title = "Pasta", calories = 820.0),
            row("EAT", at(yesterday, 21, 0), yesterday).copy(title = "Late snack", calories = 300.0),
            row("ACTIVITY", at(today, 18, 40))
                .copy(title = "Running", activityCode = "RUN", durationMinutes = 30.0, calories = 300.0),
            row(KIND_WATER, at(today, 9, 0)).copy(servingG = 300.0),
            row(KIND_ALCOHOL, at(today, 18, 0)).copy(servingG = 20.0),
            row(KIND_CAFFEINE, at(today, 10, 0)).copy(servingG = 250.0),
            row("MEASUREMENT", at(today, 7, 0)).copy(
                sleepStartAt = at(yesterday, 23, 0),
                sleepEndAt = at(today, 4, 10),
            ),
            row("MEASUREMENT", at(today, 17, 0))
                .copy(measurementPayloadJson = """{"systolicMmhg":95,"diastolicMmhg":60}"""),
            row(KIND_STOOL, at(today, 12, 0)).copy(subtitle = StoolKind.LOOSE.name),
            row(KIND_STOOL, at(today, 15, 0)).copy(subtitle = StoolKind.NORMAL.name),
        )
    }

    @Test
    fun theSnapshotReadsEachFigureTheWayItsScreenDoes() {
        val context = symptomContext(journal(), today, today, now, steps = 450, profile = profile)
        assertTrue(context.sameDay)
        assertEquals("Pasta", context.mealTitle)
        assertEquals(820, context.mealKcal)
        assertEquals(35, context.mealMinutesBefore)
        // Only today's meals count towards today's food: last night's snack is not in it.
        assertEquals(1_220, context.foodKcal)
        // The home card's target, which grows with the day's movement: the run's 300 kcal, which
        // outbids the 450 steps it was made of, on top of the budget.
        assertEquals((energyBudget(profile, today.year).targetKcal + 300.0).roundToInt(), context.foodTargetKcal)
        assertEquals(450L, context.steps)
        assertEquals("RUN", context.workoutCode)
        assertEquals(30, context.workoutMinutes)
        assertEquals(20, context.workoutEndedBefore)
        assertFalse(context.noWorkout)
        assertEquals(300, context.waterMl)
        assertEquals(waterTargetMl(profile.weightKg), context.waterTargetMl)
        assertEquals(2.0, context.standardDrinks!!, 1e-9)
        // The cup's milligrams and the caffeine a meal carries, as the drink dialog adds them up.
        assertEquals(300, context.caffeineMg)
        assertEquals(310, context.sleepMinutes)
        assertEquals(95, context.systolic)
        assertEquals(60, context.diastolic)
        assertEquals(120, context.pressureBefore)
        assertEquals(2, context.stools)
        assertEquals(1, context.looseStools)
    }

    /** Late at night the last meal can be yesterday's: it is looked for over the day before as well. */
    @Test
    fun theLastMealCanBeLastNights() {
        val morning = at(today, 7, 0)
        val context = symptomContext(journal(), today, today, morning, steps = null, profile = profile)
        assertEquals("Late snack", context.mealTitle)
        assertEquals(600, context.mealMinutesBefore)
        // Nothing logged yet today is left out rather than printed as nought.
        assertNull(context.foodKcal)
        assertNull(context.waterMl)
        assertNull(context.steps)
        assertTrue(context.noWorkout)
    }

    @Test
    fun aDayWithNoWorkoutSaysSo() {
        val context = symptomContext(journal().filter { it.kind != "ACTIVITY" }, today, today, now, 450, profile)
        assertTrue(context.noWorkout)
        assertNull(context.workoutTitle)
        // "No workout logged" is a line of its own, in the order the symptom lists its facts.
        assertEquals(listOf(SymptomFact.WORKOUT, SymptomFact.STEPS), context.present(Symptom.BREATHLESSNESS.facts))
    }

    /** An entry filed for a day gone by keeps that day's totals, and nothing counted back from a moment. */
    @Test
    fun anEarlierDayKeepsOnlyItsTotals() {
        val context = symptomContext(journal(), today, today.plusDays(2), now, steps = 450, profile = profile)
        assertFalse(context.sameDay)
        assertNull(context.mealTitle)
        assertNull(context.mealMinutesBefore)
        assertNull(context.workoutEndedBefore)
        assertNull(context.systolic)
        assertNull(context.pressureBefore)
        assertEquals(1_220, context.foodKcal)
        assertEquals("RUN", context.workoutCode)
        assertEquals(300, context.caffeineMg)
        assertEquals(310, context.sleepMinutes)
    }

    /**
     * Saved as vomiting and changed to dizziness when opened again: the snapshot is kept whole, so the
     * dizziness card still has the steps, the water and the rest from the same moment.
     */
    @Test
    fun aSymptomChangedLaterStillHasItsFigures() {
        val context = symptomContext(journal(), today, today, now, steps = 450, profile = profile)
        val saved = decodeSymptomPayload(encodeSymptomPayload(full.copy(context = context).forSave()))
        assertEquals(context, saved.context)
        assertEquals(
            listOf(SymptomFact.LAST_MEAL, SymptomFact.FOOD, SymptomFact.ALCOHOL),
            saved.context!!.present(Symptom.VOMITING.facts),
        )
        val changed = saved.copy(symptom = Symptom.DIZZINESS, answers = emptyMap(), severity = null)
        assertEquals(Symptom.DIZZINESS.facts, changed.context!!.present(Symptom.DIZZINESS.facts))
        // An answer to a question the symptom does not ask is not saved with it.
        val stray = full.copy(symptom = Symptom.FEVER).forSave()
        assertTrue(stray.answers.isEmpty())
        assertEquals(context, full.copy(symptom = Symptom.FEVER, context = context).forSave().context)
    }

    private fun alerts(symptom: Symptom, vararg answers: Pair<SymptomStep, List<SymptomOption>>) =
        symptomAlerts(symptom, answers.toMap())

    @Test
    fun theRedFlagsCallForTheLineTheirSourceGives() {
        assertEquals(
            mapOf(SymptomStep.BLOOD to SymptomAlert.EMERGENCY),
            alerts(Symptom.VOMITING, SymptomStep.BLOOD to listOf(SymptomOption.YES)),
        )
        assertTrue(alerts(Symptom.VOMITING, SymptomStep.BLOOD to listOf(SymptomOption.NO)).isEmpty())
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.EMERGENCY),
            alerts(Symptom.DIZZINESS, SymptomStep.WITH to listOf(SymptomOption.ONE_SIDE_WEAK)),
        )
        assertTrue(alerts(Symptom.DIZZINESS, SymptomStep.WITH to listOf(SymptomOption.NONE)).isEmpty())
        assertEquals(
            mapOf(SymptomStep.WHEN to SymptomAlert.URGENT),
            alerts(Symptom.BREATHLESSNESS, SymptomStep.WHEN to listOf(SymptomOption.AT_REST)),
        )
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.EMERGENCY),
            alerts(Symptom.BREATHLESSNESS, SymptomStep.WITH to listOf(SymptomOption.BLUE_LIPS)),
        )
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.EMERGENCY),
            alerts(Symptom.PALPITATIONS, SymptomStep.WITH to listOf(SymptomOption.CHEST_PAIN)),
        )
        assertTrue(alerts(Symptom.PALPITATIONS, SymptomStep.WITH to listOf(SymptomOption.NONE)).isEmpty())
    }

    /**
     * Black or dark poo is the same sign whichever symptom brought the person here, and NHS
     * "Bleeding from the bottom" puts it at an urgent appointment or 111, not 999.
     */
    @Test
    fun blackStoolsGetTheSameLineUnderEverySymptom() {
        val black = SymptomStep.WITH to listOf(SymptomOption.BLACK_STOOLS)
        assertEquals(mapOf(SymptomStep.WITH to SymptomAlert.URGENT), alerts(Symptom.HEARTBURN, black))
        assertEquals(mapOf(SymptomStep.WITH to SymptomAlert.URGENT), alerts(Symptom.DIARRHOEA, black))
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.URGENT),
            alerts(Symptom.DIARRHOEA, SymptomStep.WITH to listOf(SymptomOption.BLOOD, SymptomOption.BLACK_STOOLS)),
        )
        // The rest of heartburn's list is NHS "Heartburn and acid reflux"'s see-a-GP box.
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.DOCTOR),
            alerts(Symptom.HEARTBURN, SymptomStep.WITH to listOf(SymptomOption.SWALLOWING, SymptomOption.WEIGHT_LOSS)),
        )
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.URGENT),
            alerts(Symptom.HEARTBURN, SymptomStep.WITH to listOf(SymptomOption.VOMITING, SymptomOption.BLACK_STOOLS)),
        )
        assertTrue(alerts(Symptom.HEARTBURN, SymptomStep.WITH to listOf(SymptomOption.NONE)).isEmpty())
    }

    /**
     * NHS "Fainting": see a GP after any faint; 999 with chest pain or palpitations, a serious injury
     * or a faint while exercising. Breathlessness is not on that list: NICE CG109 1.1.4.2 asks for
     * an urgent heart assessment within 24 hours, so it gets the urgent line.
     */
    @Test
    fun aFaintIsAlwaysWorthADoctorAndAnExertionalOneAnEmergency() {
        assertEquals(mapOf(SymptomStep.SYMPTOM to SymptomAlert.DOCTOR), alerts(Symptom.FAINTING))
        fun faintWith(vararg options: SymptomOption) =
            alerts(Symptom.FAINTING, SymptomStep.WITH to options.toList())[SymptomStep.WITH]
        assertEquals(SymptomAlert.EMERGENCY, faintWith(SymptomOption.DURING_EXERCISE))
        assertEquals(SymptomAlert.EMERGENCY, faintWith(SymptomOption.CHEST_PAIN))
        assertEquals(SymptomAlert.EMERGENCY, faintWith(SymptomOption.RACING_HEART))
        assertEquals(SymptomAlert.URGENT, faintWith(SymptomOption.BREATHLESS))
        assertEquals(SymptomAlert.EMERGENCY, faintWith(SymptomOption.BREATHLESS, SymptomOption.CHEST_PAIN))
        assertNull(faintWith(SymptomOption.NONE))
        assertEquals(
            SymptomAlert.EMERGENCY,
            alerts(Symptom.FAINTING, SymptomStep.INJURED to listOf(SymptomOption.YES))[SymptomStep.INJURED],
        )
        assertNull(alerts(Symptom.FAINTING, SymptomStep.INJURED to listOf(SymptomOption.NO))[SymptomStep.INJURED])
    }

    /**
     * NHS "Meningitis": "might not have every symptom listed" — a stiff neck with a fever is a call
     * to 999 by itself, as is a rash that does not fade. A plain rash, the symptom page's answer for any
     * rash at all, is not.
     */
    @Test
    fun aFeverCallsFor999OnEachSignByItself() {
        listOf(
            SymptomOption.STIFF_NECK,
            SymptomOption.NON_BLANCHING_RASH,
            SymptomOption.CONFUSED,
            SymptomOption.SUDDEN_HEADACHE,
            SymptomOption.SEVERE_BREATHING,
            SymptomOption.BLUE_SKIN,
        ).forEach { sign ->
            assertEquals(
                sign.name,
                mapOf(SymptomStep.WITH to SymptomAlert.EMERGENCY),
                alerts(Symptom.FEVER, SymptomStep.WITH to listOf(sign)),
            )
        }
        assertTrue(alerts(Symptom.FEVER, SymptomStep.WITH to listOf(SymptomOption.RASH)).isEmpty())
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.EMERGENCY),
            alerts(Symptom.FEVER, SymptomStep.WITH to listOf(SymptomOption.RASH, SymptomOption.STIFF_NECK)),
        )
    }

    @Test
    fun lowMoodIsWorthADoctorOnlyPastTwoWeeks() {
        assertTrue(
            alerts(
                Symptom.TIREDNESS,
                SymptomStep.SINCE to listOf(SymptomOption.FEW_DAYS),
                SymptomStep.WITH to listOf(SymptomOption.LOW_MOOD),
            ).isEmpty(),
        )
        assertEquals(
            mapOf(SymptomStep.WITH to SymptomAlert.DOCTOR),
            alerts(
                Symptom.TIREDNESS,
                SymptomStep.SINCE to listOf(SymptomOption.OVER_TWO_WEEKS),
                SymptomStep.WITH to listOf(SymptomOption.LOW_MOOD),
            ),
        )
    }

    /** "I made myself sick" is met with the kind line, never an alarm. */
    @Test
    fun makingOneselfSickGetsTheKindLine() {
        assertEquals(
            mapOf(SymptomStep.CAUSE to SymptomAlert.SELF_INDUCED),
            alerts(Symptom.VOMITING, SymptomStep.CAUSE to listOf(SymptomOption.SELF_INDUCED, SymptomOption.ALCOHOL)),
        )
    }

    @Test
    fun pregnancyIsOfferedToFemaleProfilesOnly() {
        fun causes(symptom: Symptom, female: Boolean) =
            symptomAsks(symptom, female).first { it.step == SymptomStep.CAUSE }.options
        assertTrue(SymptomOption.PREGNANCY in causes(Symptom.VOMITING, female = true))
        assertTrue(SymptomOption.PREGNANCY in causes(Symptom.NAUSEA, female = true))
        assertFalse(SymptomOption.PREGNANCY in causes(Symptom.VOMITING, female = false))
        assertFalse(SymptomOption.PREGNANCY in causes(Symptom.NAUSEA, female = false))
        // Only vomiting offers the self-induced answer; nausea offers stress in its place.
        assertFalse(SymptomOption.SELF_INDUCED in causes(Symptom.NAUSEA, female = true))
        assertTrue(SymptomOption.STRESS in causes(Symptom.NAUSEA, female = true))
    }

    @Test
    fun noneAndNotSureStandAlone() {
        val ticked = toggledOption(listOf(SymptomOption.CHEST_PAIN), SymptomOption.SPEECH)
        assertEquals(listOf(SymptomOption.CHEST_PAIN, SymptomOption.SPEECH), ticked)
        assertEquals(listOf(SymptomOption.NONE), toggledOption(ticked, SymptomOption.NONE))
        assertEquals(listOf(SymptomOption.SPEECH), toggledOption(listOf(SymptomOption.NONE), SymptomOption.SPEECH))
        assertEquals(emptyList<SymptomOption>(), toggledOption(listOf(SymptomOption.SPEECH), SymptomOption.SPEECH))
    }

    /** The symptom page's steps for a symptom: which, its own, how bad — what an old entry is read back by. */
    @Test
    fun theOldPagesStepsStand() {
        assertEquals(listOf(SymptomStep.SYMPTOM, SymptomStep.WITH, SymptomStep.SEVERITY), symptomSteps(Symptom.FEVER))
        assertEquals(listOf(SymptomStep.SYMPTOM), symptomSteps(null))
    }

    /** The title above the line names the symptom; the line names it again only in another language. */
    @Test
    fun theDayListNamesTheSymptomAndHowBad() {
        assertEquals("MODERATE", symptomSummaryLine(full, "VOMITING", name = { it.name }, severity = { it.name }))
        assertEquals(
            "VOMITING · MODERATE",
            symptomSummaryLine(full, "Рвота", name = { it.name }, severity = { it.name }),
        )
        assertEquals("", symptomSummaryLine(SymptomReport(Symptom.FEVER), "FEVER", { it.name }, { it.name }))
        assertEquals("", symptomSummaryLine(SymptomReport(), "Fever", { it.name }, { it.name }))
        // "Something else" is titled with the person's words, and never printed again under them.
        assertEquals(
            "MILD",
            symptomSummaryLine(SymptomReport(Symptom.OTHER, severity = SymptomSeverity.MILD), "Itchy", {
                it.name
            }, { it.name }),
        )
    }
}
