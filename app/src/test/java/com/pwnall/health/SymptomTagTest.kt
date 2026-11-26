package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The chat's hidden sorting: worked out from what was asked so far, the same however it is reached. */
class SymptomTagTest {
    private fun tagOf(report: SymptomReport, earlier: EarlierChat? = null) = classifySymptomChat(report, earlier)

    @Test
    fun eachTopicHasADescribedAreaNeverACause() {
        val areas = Symptom.entries.associateWith { tagOf(newChat().pick(it)).area }
        assertEquals(ProblemArea.STOMACH_GUT, areas[Symptom.VOMITING])
        assertEquals(ProblemArea.CHEST_HEARTBEAT, areas[Symptom.PALPITATIONS])
        assertEquals(ProblemArea.CHEST_HEARTBEAT, areas[Symptom.CHEST_PAIN])
        assertEquals(ProblemArea.BALANCE_FAINTING, areas[Symptom.DIZZINESS])
        assertEquals(ProblemArea.BALANCE_FAINTING, areas[Symptom.FAINTING])
        assertEquals(ProblemArea.TEMPERATURE, areas[Symptom.FEVER])
        assertEquals(ProblemArea.FEELINGS, areas[Symptom.WORRY])
        assertEquals(ProblemArea.SLEEP, areas[Symptom.SLEEP_WORRY])
        assertEquals(ProblemArea.UNSORTED, areas[Symptom.OTHER])
        assertEquals(ProblemArea.UNSORTED, tagOf(newChat()).area)
        // No area is named after what something is.
        val causal = setOf("INFECTION", "HEART", "NERVES", "MIND", "HEART_CIRCULATION")
        assertTrue(ProblemArea.entries.none { it.name in causal })
    }

    @Test
    fun whatCameWithItAddsItsAreasApartFromTheTopicsOwn() {
        val tag = tagOf(
            newChat().pick(Symptom.FEVER)
                .say(SymptomStep.WITH, SymptomOption.COUGH, SymptomOption.CHILLS, SymptomOption.NON_BLANCHING_RASH),
        )
        assertEquals(listOf(ProblemArea.BREATHING, ProblemArea.SKIN), tag.also)
        assertEquals(SymptomUrgency.EMERGENCY, tag.urgency)
        assertEquals(listOf("FEVER_999"), tag.flags)
    }

    @Test
    fun theCourseIsReadFromTheStartTheSpanOrTheEarlierChat() {
        fun course(onset: SymptomOption) =
            tagOf(
                newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.NONE).say(SymptomStep.ONSET, onset),
            ).course
        assertEquals(ProblemCourse.NEW, course(SymptomOption.STARTED_2_3_DAYS))
        assertEquals(ProblemCourse.RECENT, course(SymptomOption.STARTED_A_WEEK))
        assertEquals(ProblemCourse.LASTING, course(SymptomOption.STARTED_LONGER))
        val worry = newChat().pick(Symptom.WORRY).say(SymptomStep.ABOUT, SymptomOption.MONEY)
        assertEquals(ProblemCourse.LASTING, tagOf(worry.say(SymptomStep.HOW_LONG, SymptomOption.MONTHS)).course)
        assertEquals(ProblemCourse.UNKNOWN, tagOf(worry).course)
        val earlier = EarlierChat(Symptom.FEVER, LocalDate.of(2026, 10, 1), 1L, SymptomOption.STARTED_A_WEEK)
        val same = newChat().pick(
            Symptom.FEVER,
            earlier,
        ).say(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE, earlier = earlier)
        assertEquals(ProblemCourse.RECENT, tagOf(same, earlier).course)
        assertTrue(tagOf(same, earlier).recurring)
        assertEquals(ProblemCourse.UNKNOWN, tagOf(same, earlier.copy(onset = null)).course)
    }

    @Test
    fun impactComesFromCopingHowBadAndWhatItGetsInTheWayOf() {
        val stress = newChat().pick(Symptom.STRESS).say(SymptomStep.ABOUT, SymptomOption.WORK_STUDY)
        assertEquals(ProblemImpact.UNKNOWN, tagOf(stress).impact)
        assertEquals(ProblemImpact.NONE, tagOf(stress.say(SymptomStep.AFFECTS, SymptomOption.NOT_REALLY)).impact)
        assertEquals(ProblemImpact.SOME, tagOf(stress.say(SymptomStep.AFFECTS, SymptomOption.SLEEP)).impact)
        assertEquals(
            ProblemImpact.MUCH,
            tagOf(
                stress.say(SymptomStep.AFFECTS, SymptomOption.SLEEP, SymptomOption.EATING, SymptomOption.PEOPLE),
            ).impact,
        )
        assertEquals(ProblemImpact.MUCH, tagOf(stress.say(SymptomStep.COPING, SymptomOption.NOT_COPING)).impact)
        // "mild" is not "it does not get in the way".
        assertEquals(ProblemImpact.UNKNOWN, tagOf(stress.rate(SymptomSeverity.MILD)).impact)
        assertEquals(ProblemImpact.SOME, tagOf(stress.rate(SymptomSeverity.MODERATE)).impact)
        assertTrue(tagOf(stress).mind)
        assertFalse(tagOf(newChat().pick(Symptom.FEVER)).mind)
    }

    @Test
    fun theWordsNamedTopicsAreKeptChosenOrNot() {
        val tag = tagOf(newChat().write("dizzy and sick, feeling sick").pick(Symptom.DIZZINESS))
        assertEquals(listOf(Symptom.NAUSEA, Symptom.DIZZINESS), tag.mentioned)
        // Every lexicon's topics: a pain the words named is kept too, though the chat asked about dizziness.
        val pain = tagOf(newChat().write("my knee hurts and I feel dizzy").pick(Symptom.DIZZINESS))
        assertEquals(listOf(Symptom.PAIN, Symptom.DIZZINESS), pain.mentioned)
    }

    /** An answer about suicide leaves no label for it in the tag; the answer is in the chat. */
    @Test
    fun noFlagForSuicideIsKeptInTheTag() {
        val tag = tagOf(
            newChat().pick(Symptom.LOW_MOOD).say(SymptomStep.ABOUT, SymptomOption.LOSS)
                .say(SymptomStep.HOW_LONG, SymptomOption.FEW_DAYS)
                .say(SymptomStep.CRISIS, SymptomOption.RATHER_NOT_SAY),
        )
        assertTrue(tag.flags.none { it in CRISIS_RULE_IDS })
        assertEquals(SymptomUrgency.SAME_DAY, tag.urgency)
    }

    /** Worked out from what was asked, so the order the answers came in does not change it. */
    @Test
    fun aPartialChatIsTaggedAndTheTagIsStable() {
        val partial = newChat().pick(Symptom.DIZZINESS).say(SymptomStep.WITH, SymptomOption.NONE)
        assertEquals(SymptomUrgency.SELF_CARE, tagOf(partial).urgency)
        val told = partial.say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY)
            .say(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES)
        val reordered = told.copy(answers = told.answers.entries.reversed().associate { it.toPair() })
        assertEquals(tagOf(told), tagOf(reordered))
        assertEquals(listOf("DIZZY_RETURNS"), tagOf(told).flags)
        assertTrue(tagOf(told).recurring)
        // Ticks not yet taken are not part of it.
        val ticking = told.copy(answers = told.answers + (SymptomStep.HOW_OFTEN to listOf(SymptomOption.DAILY)))
        assertEquals(tagOf(told), tagOf(ticking))
    }

    @Test
    fun theTagTravelsInThePayload() {
        val tag = tagOf(newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.COUGH))
        val json = org.json.JSONObject(encodeSymptomTag(tag))
        assertEquals(tag, decodeSymptomTag(json))
    }
}
