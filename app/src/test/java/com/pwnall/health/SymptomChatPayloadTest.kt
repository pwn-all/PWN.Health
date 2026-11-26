package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The chat in the payload: written in one fixed order, read back whole, old entries read as they
 * were, and nothing in it that a vital or an exporter could take for a measurement.
 */
class SymptomChatPayloadTest {
    private val day = LocalDate.of(2026, 10, 5)

    private val tag = SymptomTag(
        area = ProblemArea.BALANCE_FAINTING,
        also = emptyList(),
        urgency = SymptomUrgency.GP,
        course = ProblemCourse.NEW,
        recurring = true,
        impact = ProblemImpact.SOME,
        mind = false,
        flags = listOf("DIZZY_RETURNS"),
        mentioned = listOf(Symptom.DIZZINESS),
    )

    private val chat = SymptomReport(
        symptom = Symptom.DIZZINESS,
        severity = SymptomSeverity.MODERATE,
        answers = mapOf(
            SymptomStep.ONSET to listOf(SymptomOption.STARTED_YESTERDAY),
            SymptomStep.PATTERN to listOf(SymptomOption.COMES_AND_GOES),
            SymptomStep.WITH to listOf(SymptomOption.NONE),
        ),
        chat = SymptomChat(
            turns = listOf(
                ChatTurn.Wrote("голова \"кружится\" с утра\\\n🙂"),
                ChatTurn.Asked(SymptomStep.SYMPTOM),
                ChatTurn.Asked(SymptomStep.WITH),
                ChatTurn.Asked(SymptomStep.ONSET),
                ChatTurn.Asked(SymptomStep.PATTERN, SymptomAlert.DOCTOR, "DIZZY_RETURNS"),
                ChatTurn.Asked(SymptomStep.SEVERITY),
                ChatTurn.Said("Hello", "en"),
            ),
            sameAs = 1_700_000_000_000L,
            ended = true,
        ),
        context = SymptomContext(sameDay = true, steps = 4_200, sleepMinutes = 395),
        symptomTag = tag,
    )

    @Test
    fun aChatComesBackAsItWent() {
        assertEquals(chat, decodeSymptomPayload(encodeSymptomPayload(chat)))
    }

    @Test
    fun theChatIsWrittenInOneFixedOrder() {
        val golden = """{"schema":2,"symptom":"DIZZINESS","severity":"MODERATE",""" +
            """"answers":{"WITH":["NONE"],"ONSET":["STARTED_YESTERDAY"],"PATTERN":["COMES_AND_GOES"]},""" +
            """"chat":{"turns":[{"text":"голова \"кружится\" с утра\\\n🙂"},{"q":"SYMPTOM"},{"q":"WITH"},""" +
            """{"q":"ONSET"},{"q":"PATTERN","alert":"DOCTOR","rule":"DIZZY_RETURNS"},{"q":"SEVERITY"},""" +
            """{"said":"Hello","lang":"en"}],"same":1700000000000,"ended":true},""" +
            """"context":{"today":true,"steps":4200,"sleepMin":395},""" +
            """"tag":{"v":1,"area":"BALANCE_FAINTING","urgency":"GP","course":"NEW","recurring":true,""" +
            """"impact":"SOME","mind":false,"flags":["DIZZY_RETURNS"],"mentioned":["DIZZINESS"]}}"""
        assertEquals(golden, encodeSymptomPayload(chat))
        val shuffled = chat.copy(answers = chat.answers.entries.reversed().associate { it.toPair() })
        assertEquals(golden, encodeSymptomPayload(shuffled))
    }

    /** An entry of the symptom page is written byte for byte as the page wrote it, and reads back with no chat. */
    @Test
    fun aPageEntryStaysSchemaOne() {
        val page = """{"schema":1,"symptom":"VOMITING","severity":"MILD","answers":{"AFTER_MEAL":["YES"],""" +
            """"CAUSE":["ALCOHOL"],"TIMES":["ONCE"],"BLOOD":["NO"]}}"""
        val decoded = decodeSymptomPayload(page)
        assertNull(decoded.chat)
        assertNull(decoded.symptomTag)
        assertEquals(page, encodeSymptomPayload(decoded))
    }

    /** An old entry is read as the chat it would have been, ended, with today's lines. */
    @Test
    fun aPageEntryOpensAsAChat() {
        val page = decodeSymptomPayload(
            """{"schema":1,"symptom":"VOMITING","severity":"MILD","answers":{"AFTER_MEAL":["YES"],""" +
                """"CAUSE":["ALCOHOL"],"TIMES":["ONCE"],"BLOOD":["YES"]}}""",
        )
        val synthesized = synthesizedChat(page)
        assertEquals(
            listOf(
                SymptomStep.SYMPTOM,
                SymptomStep.AFTER_MEAL,
                SymptomStep.CAUSE,
                SymptomStep.TIMES,
                SymptomStep.BLOOD,
                SymptomStep.SEVERITY,
            ),
            synthesized.turns.map { (it as ChatTurn.Asked).step },
        )
        assertTrue(synthesized.ended)
        assertEquals(SymptomAlert.EMERGENCY, (synthesized.turns[4] as ChatTurn.Asked).alert)
        val told = ChatLog(threads = listOf(ChatThread(0, page.copy(chat = synthesizedChat(page)))), next = 1)
        val transcript = chatTranscript(told, chatSetting(), live = false)
        assertEquals(6, transcript.count { it is ChatItem.Person })
        assertEquals(ChatReply.End, (transcript.last() as ChatItem.Bot).turn.reply)
        // Unanswered how bad: the page never saved that, but an entry missing it reads as left early.
        assertFalse(synthesizedChat(page.copy(severity = null)).ended)
    }

    @Test
    fun whatThisBuildDoesNotKnowIsSkippedAndNothingElse() {
        val payload =
            """{"schema":3,"symptom":"FEVER","chat":{"turns":[{"q":"LATER"},{"q":"WITH","alert":"LOUDER"},""" +
                """{"shape":"new"},{"text":"ok"},{"said":"x"}],"ended":true,"later":1},""" +
                """"tag":{"v":2,"area":"SOMEWHERE"}}"""
        val decoded = decodeSymptomPayload(payload)
        assertEquals(
            listOf(ChatTurn.Asked(SymptomStep.WITH), ChatTurn.Wrote("ok"), ChatTurn.Said("x", "")),
            decoded.chat?.turns,
        )
        assertTrue(decoded.chat?.ended == true)
        assertNull(decoded.symptomTag)
    }

    /** Ticks on a question still being answered are not an answer yet, and are not written. */
    @Test
    fun pendingTicksAreNotSaved() {
        val ticking = newChat().pick(
            Symptom.FEVER,
        ).copy(answers = mapOf(SymptomStep.WITH to listOf(SymptomOption.CHILLS)))
        assertTrue(ticking.forSave().answers.isEmpty())
        val taken = ticking.answered(SymptomStep.WITH)
        assertEquals(listOf(SymptomOption.CHILLS), taken.forSave().answers[SymptomStep.WITH])
        // How bad stands only once its own question was taken.
        assertNull(ticking.copy(severity = SymptomSeverity.MILD).forSave().severity)
    }

    /**
     * Free text that quotes a pulse and a temperature, and the tag, are in the payload: none of it may
     * ever be read as a measurement, or by an exporter.
     */
    @Test
    fun aChatRowIsNeverReadAsAMeasurement() {
        val profile = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1990-01-01")
        val worded = chat.copy(
            chat = checkNotNull(chat.chat).let {
                it.copy(
                    turns =
                    it.turns + ChatTurn.Wrote("pulse 120, temperature 39, bp 150/95"),
                )
            },
        )
        val entry = symptomEntry("Dizziness", worded, day, day, 1L)
        assertTrue(vitalHistory(listOf(entry), profile).isEmpty())
        listOf("\"type\"", "systolicMmhg", "pulseRateBpm", "temperatureCelsius").forEach {
            assertFalse(it, it in entry.measurementPayloadJson.orEmpty())
        }
        assertNull(entry.measurementHeartRateBpm)
        assertEquals(0.0, entry.calories, 0.0)
    }

    /**
     * Appended, never inserted: the first names are the symptom page's and then the single-topic
     * chat's, so a payload's order cannot shift. The chat of several threads appends after them,
     * and its mood and medicine threads after it: every name stored so far is pinned, so what
     * comes later can only come after it.
     */
    @Test
    fun theEnumsOnlyEverGrowAtTheEnd() {
        assertEquals(
            listOf(
                "SYMPTOM", "AFTER_MEAL", "CAUSE", "TIMES", "BLOOD", "DOING", "WHEN", "FEEL", "FAINTED", "INJURED",
                "SINCE", "WITH", "SEVERITY", "ONSET", "SAME", "PATTERN", "HOW_OFTEN", "DURATION", "TREND",
                "CHEST_NOW", "ABOUT", "HOW_LONG", "MIND_OFTEN", "AFFECTS", "COPING", "NIGHT", "CRISIS", "PALP_NOW",
                "BREATH_NOW", "STROKE_SIGNS", "UNRESPONSIVE", "CRISIS_NOW",
            ),
            SymptomStep.entries.take(32).map { it.name },
        )
        assertEquals(
            listOf(
                "VOMITING", "NAUSEA", "DIZZINESS", "FAINTING", "PALPITATIONS", "BREATHLESSNESS", "HEARTBURN",
                "DIARRHOEA", "FEVER", "TIREDNESS", "CHEST_PAIN", "WORRY", "LOW_MOOD", "STRESS", "SLEEP_WORRY",
                "OTHER",
            ),
            Symptom.entries.take(16).map { it.name },
        )
        assertEquals(
            listOf(
                "EMERGENCY",
                "URGENT",
                "DOCTOR",
                "SELF_INDUCED",
                "EMERGENCY_MIND",
                "URGENT_MIND",
                "DOCTOR_MIND",
                "SAFETY_NET",
            ),
            SymptomAlert.entries.take(8).map { it.name },
        )
        assertEquals(
            listOf(
                "STOMACH_GUT", "CHEST_HEARTBEAT", "BREATHING", "BALANCE_FAINTING", "TEMPERATURE", "ENERGY",
                "FEELINGS", "SLEEP", "HEAD", "SKIN", "UNSORTED",
            ),
            ProblemArea.entries.take(11).map { it.name },
        )
        // The several-thread chat's own come straight after, the record topics after the symptom ones.
        assertEquals(SymptomStep.CRISIS_NOW.ordinal + 1, SymptomStep.CHEST_SPREAD.ordinal)
        assertEquals(listOf("PAIN", "MOOD", "DOSE", "COURSE"), Symptom.entries.drop(16).map { it.name })
        assertEquals(SymptomAlert.SAFETY_NET.ordinal + 1, SymptomAlert.IN_PERSON_NOW.ordinal)
        assertEquals(ProblemArea.UNSORTED.ordinal + 1, ProblemArea.MUSCULOSKELETAL.ordinal)
        assertThePinnedPayload()
        // The chat's onset answers are the pain page's, under the same words.
        assertEquals(PainOnset.ABOUT_A_WEEK, SymptomOption.STARTED_A_WEEK.painOnset)
        assertEquals(PainOnset.ABOUT_A_WEEK.labelRes, SymptomOption.STARTED_A_WEEK.labelRes)
        assertNull(SymptomOption.NONE.painOnset)
    }

    /** The rest of the pinned names: the core's and the pain thread's, then the mood's, then the medicine's. */
    private fun assertThePinnedPayload() {
        assertEquals(
            PINNED_CORE_STEPS + PINNED_MOOD_STEPS + PINNED_MEDICINE_STEPS,
            SymptomStep.entries.drop(32).map { it.name },
        )
        assertEquals(PINNED_ALERTS, SymptomAlert.entries.drop(8).map { it.name })
        assertEquals(
            listOf("MUSCULOSKELETAL", "MEDICINES", "MOOD_NEUTRAL", "MOOD_POSITIVE"),
            ProblemArea.entries.drop(11).map { it.name },
        )
        assertEquals(listOf("SYMPTOM", "PAIN", "MOOD", "DOSE", "COURSE"), ChatRecord.entries.map { it.name })
    }

    /** The answers' first 142 names, in their order: every answer a stored chat holds is written in it. */
    @Test
    fun theAnswersOnlyEverGrowAtTheEnd() {
        assertEquals(
            listOf(
                "YES", "NO", "ATE_TOO_MUCH", "FOOD_OFF", "ALCOHOL", "MOTION", "MEDICINE", "STOMACH_BUG",
                "SELF_INDUCED", "PREGNANCY", "STRESS", "TRAVEL", "NOT_SURE", "ONCE", "TWICE", "THREE_PLUS",
                "STANDING_UP", "EXERCISING", "AFTER_EXERCISE", "SITTING_LYING", "TURNING_HEAD", "WALKING",
                "LIGHT_HEADED", "SPINNING", "UNSTEADY", "RACING", "POUNDING", "IRREGULAR", "FLUTTERING", "AT_REST",
                "AT_NIGHT", "WALKING_STAIRS", "HARD_EXERCISE", "LYING_NIGHT", "AFTER_EATING", "LYING_BENDING",
                "EMPTY_STOMACH", "TODAY", "FEW_DAYS", "OVER_TWO_WEEKS", "CHEST_PAIN", "RACING_HEART",
                "ONE_SIDE_WEAK", "SPEECH", "BREATHLESS", "DURING_EXERCISE", "DIZZY_FAINT", "WHEEZING", "COUGH",
                "BLUE_LIPS", "SWALLOWING", "VOMITING", "BLACK_STOOLS", "WEIGHT_LOSS", "BLOOD", "DEHYDRATION",
                "FEVER", "CHILLS", "SWEATING", "SORE_THROAT", "RASH", "STIFF_NECK", "POOR_SLEEP", "LOW_MOOD",
                "NONE", "STARTED_NOW", "STARTED_TODAY", "STARTED_YESTERDAY", "STARTED_2_3_DAYS", "STARTED_A_WEEK",
                "STARTED_A_MONTH", "STARTED_LONGER", "SAME_AS_BEFORE", "SOMETHING_NEW", "ALL_THE_TIME",
                "COMES_AND_GOES", "JUST_ONCE", "SEVERAL_A_DAY", "DAILY", "FEW_A_WEEK", "LESS_OFTEN", "SECONDS",
                "FEW_MINUTES", "LONGER_MINUTES", "HOURS", "GETTING_BETTER", "NO_CHANGE", "GETTING_WORSE",
                "CANT_KEEP_FLUIDS", "GREEN_VOMIT", "SEVERE_TUMMY_PAIN", "SUDDEN_HEADACHE", "CONFUSED",
                "SEVERE_BREATHING", "PAIN_SPREADING", "TIGHT_HEAVY", "SWEATY_SICK", "WORK_STUDY", "MONEY", "FAMILY",
                "HEALTH", "LONELINESS", "LOSS", "FUTURE", "NO_CLEAR_REASON", "SOMETHING_ELSE", "ABOUT_A_WEEK",
                "TWO_WEEKS_PLUS", "MONTHS", "NOW_AND_THEN", "MOST_DAYS", "SLEEP", "EATING", "DAILY_TASKS", "PEOPLE",
                "NOT_REALLY", "MANAGING", "STRUGGLING", "NOT_COPING", "STOMACH", "TENSE", "HEADACHE",
                "NO_ENJOYMENT", "HOPELESS", "TIRED", "HARD_TO_FALL_ASLEEP", "WAKING_IN_NIGHT", "WAKING_EARLY",
                "RACING_THOUGHTS", "TIRED_IN_DAY", "CRISIS_SAFE", "CRISIS_UNSAFE", "CRISIS_HARMED",
                "RATHER_NOT_SAY", "NON_BLANCHING_RASH", "BLUE_SKIN", "COUGHING_BLOOD", "ONE_LEG_SWELLING",
                "HEART_CONDITION", "SAFE_NOW", "TRIED_NOT_HELPING", "NO_SUCH_THOUGHTS",
            ),
            SymptomOption.entries.take(142).map { it.name },
        )
        assertEquals(SymptomOption.NO_SUCH_THOUGHTS.ordinal + 1, SymptomOption.LEAVE_IT.ordinal)
        // The rest of the pinned names after them: the core's and the pain thread's, the mood's, the medicine's.
        assertEquals(
            PINNED_CORE_OPTIONS + PINNED_MOOD_OPTIONS + PINNED_MEDICINE_OPTIONS,
            SymptomOption.entries.drop(142).map { it.name },
        )
    }
}

/** The several-thread chat's steps of the core and the pain thread, in their order. */
private val PINNED_CORE_STEPS = listOf(
    "CHEST_SPREAD", "PAIN_AREA", "PAIN_INJURY", "PAIN_SIGNS", "PAIN_SIGNS_MORE", "PAIN_SEPSIS", "PAIN_SAME",
    "PAIN_CHANGED", "PAIN_TYPES", "PAIN_NRS", "PAIN_NOTICED", "PAIN_STATUS", "PAIN_LASTED", "PAIN_PAINKILLER",
)

/** The mood thread's steps, after the core's. */
private val PINNED_MOOD_STEPS = listOf(
    "MOOD_UPDATE", "MOOD_DIAL", "MOOD_ENERGY", "MOOD_ABOUT", "MOOD_ELSE", "MOOD_SWINGS", "MOOD_HIGH_ASK",
    "MOOD_HIGH_SIGNS", "MOOD_HIGH_LONG", "MOOD_HIGH_LIFE",
)

/** The dose and course threads' steps, last. */
private val PINNED_MEDICINE_STEPS = listOf(
    "MED_WHICH", "MED_NAME", "MED_IS_COURSE", "MED_SLOT", "MED_SAME_AGAIN", "MED_WHEN", "MED_AMOUNT", "MED_MAX",
    "MED_SIGNS", "MED_PURPOSE", "MED_WHY", "MED_PAIN_LINK", "MED_FEEL", "MED_SERIOUS", "COURSE_NAME", "COURSE_SAME",
    "COURSE_STOP_WHO", "COURSE_FOR", "COURSE_DOSE", "COURSE_OFTEN", "COURSE_OFTEN_OTHER", "COURSE_TIMES",
    "COURSE_START", "COURSE_LENGTH", "COURSE_TODAY", "COURSE_TODAY_SLOTS", "COURSE_FOOD", "COURSE_WHO",
    "COURSE_REMIND_SWITCH", "COURSE_REMIND_NOTIFY", "COURSE_REMIND_ALARM", "MED_WHEN_AGAIN", "COURSE_PRN",
    "MED_MAX_WORDS", "MED_MAX_AGAIN",
)

/** The several-thread chat's answers of the core and the pain thread, in their order. */
private val PINNED_CORE_OPTIONS = listOf(
    "LEAVE_IT", "PAIN_SAME_YES", "PAIN_NEW", "FEELS_SAME", "FEELS_CHANGED", "PAIN_ACHING", "PAIN_STIFF",
    "PAIN_THROBBING", "PAIN_SHARP", "PAIN_BURNING", "PAIN_CRAMPING", "NOTICED_DURING_WORKOUT", "NOTICED_AFTER_WORKOUT",
    "NOTICED_WORK_OR_SITTING", "NOTICED_ON_WAKING", "NOTICED_AFTER_LIFTING", "NOTICED_NO_CLEAR_MOMENT", "STILL_HURTS",
    "GONE", "SUDDEN_SEVERE", "SUDDEN_OR_SEVERE", "SUDDEN_OR_BAD", "VERY_PAINFUL", "CHEST_TOO", "BREATHING_HARD",
    "FAST_HEART", "TINGLE_NUMB", "ODD_SHAPE", "CRACK", "BONE_OUT", "BLUE_COLD", "SKIN_COLOUR", "NO_WEIGHT", "CANT_MOVE",
    "SEVERE_CANT_MOVE", "SWELL_BRUISE", "FAST_SWELL", "STIFF_HARD", "FAINT_FROM_PAIN", "HOT_RED_SWOLLEN", "HOT_OR_COLD",
    "SWOLLEN_JOINT", "FEVERISH", "LOCKS_GIVES", "KNEECAP_SLIPPED", "SHOCK", "TIPTOE_STAIRS", "CALF_ANKLE_SWELL",
    "BIG_TOE", "EXERCISE_EASES", "WORSE_FAST", "SEVERE_SINCE_FALL", "SEIZURE", "NUMB_WEAK", "SPEAK_BALANCE",
    "DROWSY_CONFUSED", "VISION_LOSS", "HEAD_INJURY", "FEVER_STIFF_LIGHT", "EYE_PROBLEMS", "WORSE_STRAIN", "JAW_SCALP",
    "CHILD_WORSE", "HIGH_TEMP", "SEVERE_HEADACHE", "LIGHT_HURTS", "WALK_SIT", "ELECTRIC", "WEAK_LIMBS",
    "SEVERE_DESPITE", "BOTH_LEGS", "SADDLE", "BLADDER_BOWEL", "SEX_CHANGES", "SERIOUS_ACCIDENT", "PEE_BLOOD", "SICK",
    "VERY_LOW_TEMP", "LUMP_SHAPE", "NIGHT_REST", "COUGH_SNEEZE_POO", "TENDER_TUMMY", "WORSE_OR_MOVING", "VOMIT_BLOOD",
    "BLACK_POO", "CANT_PEE_POO", "SHOULDER_TIP", "YELLOW", "DIABETES_SICK", "CONFUSED_PALE", "HEAVY_BLEEDING",
    "KEEPS_COMING", "BLOATING", "PEE_CHANGE", "BOTTOM_BLEED", "HARD_TO_PEE_POO", "SICK_DIARRHOEA", "DIARRHOEA_DAYS",
    "MOVED_RIGHT", "VAGINAL", "CLOT_SIGNS", "LEG_SKIN", "SUDDEN_WORSE_LEG", "WARM_SWOLLEN", "SICK_CANT_EAT",
    "HAS_DIABETES", "SPREAD_BACK_SICK", "JOINT_WORSE", "PASSED_OUT",
)

/** The mood thread's answers, after the core's. */
private val PINNED_MOOD_OPTIONS = listOf(
    "UPDATE_IT", "ENERGY_WOUND_UP", "ENERGY_RESTLESS", "ENERGY_STEADY", "ENERGY_CALM", "ENERGY_SETTLED", "REST",
    "MOVEMENT", "WENT_WELL", "NATURE_WEATHER", "GOOD_NEWS", "ASK_ME", "NO_THANKS", "LESS_SLEEP_NEED", "RACING_MIND",
    "UNUSUAL_RISKS", "TALKING_MORE", "NOT_NOW",
)

/** The dose and course threads' answers, last. */
private val PINNED_MEDICINE_OPTIONS = listOf(
    "ANOTHER_DOSE", "SAME_DOSE", "FOR_PAIN", "FOR_TEMPERATURE", "FOR_MOOD", "FOR_SLEEP", "CHANGE_IT",
    "MORE_THAN_IT_SAYS", "BY_ACCIDENT", "ON_PURPOSE", "FEEL_BETTER", "FEEL_SAME", "FEEL_WORSE", "SIDE_EFFECTS",
    "SERIOUS_HARM", "SERIOUS_HIGH", "SERIOUS_HEART", "SERIOUS_BLEEDING", "SERIOUS_THROAT", "SERIOUS_COLLAPSE",
    "SAME_COURSE", "COURSE_CHANGED", "COURSE_STOPPED", "ANOTHER_COURSE", "DOCTOR_SAID", "OWN_DECISION", "ONCE_A_DAY",
    "TWICE_A_DAY", "THREE_A_DAY", "ON_WAKING", "WHEN_NEEDED", "OTHER_OFTEN", "MORE_THAN_THREE", "LESS_THAN_DAILY",
    "AS_DAILY_COURSE", "DOSE_NOW", "FOOD_ANY", "FOOD_BEFORE", "FOOD_WITH", "FOOD_AFTER", "TOOK_TODAY", "NOT_YET",
    "START_TOMORROW", "TURN_ON", "LEAVE_OFF", "ALLOW", "NOT_NOW_MED", "DONE",
)

/** The several-thread chat's alerts: the core's, then the mood's, then the medicine's. */
private val PINNED_ALERTS = listOf(
    "IN_PERSON_NOW", "URGENT_NOW", "HIGH_MOOD_NET", "MOOD_SWINGS_NET", "HIGH_ON_ANTIDEPRESSANT", "DOSE_TOO_MUCH",
    "MED_SERIOUS_SIDE_EFFECT", "STOP_TALK", "SIDE_EFFECTS",
)
