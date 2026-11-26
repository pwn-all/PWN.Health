package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The red flags and what the chat says at its end: each rule against the answers that make it
 * true, the level it calls for and where it comes from; the closing turn's lines and offers by that
 * level; and the guard a model would be held behind.
 */
class SymptomRulesTest {
    private fun fired(report: SymptomReport) = firedRules(report).map { it.id }

    private fun told(
        topic: Symptom,
        vararg answers: Pair<SymptomStep, List<SymptomOption>>,
        severity: SymptomSeverity? = null,
    ) = SymptomReport(topic, answers.toMap(), severity)

    private fun ticked(step: SymptomStep, vararg options: SymptomOption) = step to options.toList()

    @Test
    fun everyRuleHasAStableIdAndASource() {
        val ids = SYMPTOM_RULES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        SYMPTOM_RULES.forEach { rule ->
            // A rule for every topic names its own source; a topic's rule may lean on the topic's.
            assertTrue(rule.id, rule.sourceRes != null || rule.topics.isNotEmpty())
        }
        assertEquals(CRISIS_RULE_IDS, CRISIS_RULE_IDS intersect ids.toSet())
    }

    /**
     * Every list of rules a thread reads is in the one list the chat reads — the pain thread's,
     * the mood's and the medicine's after the symptoms' own — so no topic's red flag sits where
     * nothing fires it, and no id is taken twice.
     */
    @Test
    fun everyRuleListFeedingTheChatIsInSymptomRules() {
        val read = SYMPTOM_RULES.map { it.id }
        listOf(PAIN_RULES, MOOD_RULES, MEDICINE_RULES).forEach { list ->
            assertTrue(read.containsAll(list.map { it.id }))
        }
        assertEquals(read.size, read.toSet().size)
        assertTrue(CRISIS_RULE_IDS.containsAll(MEDICINE_CRISIS_RULE_IDS))
        assertTrue(listOf("CRISIS_SAFE", "CRISIS_UNSAFE", "CRISIS_SKIP").all { it in CRISIS_RULE_IDS && it in read })
    }

    /** The most urgent is by level, not by the order the lines were written in. */
    @Test
    fun theMostUrgentLineIsChosenByUrgency() {
        assertEquals(SymptomAlert.EMERGENCY_MIND, mostUrgent(listOf(SymptomAlert.DOCTOR, SymptomAlert.EMERGENCY_MIND)))
        assertEquals(SymptomAlert.URGENT, mostUrgent(listOf(SymptomAlert.DOCTOR_MIND, SymptomAlert.URGENT)))
        assertEquals(SymptomAlert.SAFETY_NET, mostUrgent(listOf(SymptomAlert.SAFETY_NET, SymptomAlert.SELF_INDUCED)))
    }

    /** A table of rules against answers that fire them: the id, and the level it calls for. */
    private val cases: List<Triple<SymptomReport, String, SymptomUrgency>> = listOf(
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.BLOOD, SymptomOption.YES)),
            "VOMIT_BLOOD",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.WITH, SymptomOption.GREEN_VOMIT)),
            "VOMIT_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.WITH, SymptomOption.BLUE_SKIN)),
            "VOMIT_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.WITH, SymptomOption.STIFF_NECK)),
            "VOMIT_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.WITH, SymptomOption.SEVERE_BREATHING)),
            "VOMIT_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.VOMITING, ticked(SymptomStep.WITH, SymptomOption.CANT_KEEP_FLUIDS)),
            "VOMIT_FLUIDS",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(
                Symptom.VOMITING,
                ticked(SymptomStep.ONSET, SymptomOption.STARTED_2_3_DAYS),
                ticked(SymptomStep.TIMES, SymptomOption.THREE_PLUS),
            ),
            "VOMIT_2_DAYS",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.NAUSEA, ticked(SymptomStep.WITH, SymptomOption.PAIN_SPREADING)),
            "NAUSEA_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.NAUSEA, ticked(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES)),
            "NAUSEA_RETURNS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.DIZZINESS, ticked(SymptomStep.WITH, SymptomOption.SPEECH)),
            "DIZZY_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(
                Symptom.DIZZINESS,
                ticked(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME),
                ticked(SymptomStep.ONSET, SymptomOption.STARTED_A_WEEK),
            ),
            "DIZZY_STAYS",
            SymptomUrgency.GP,
        ),
        Triple(told(Symptom.FAINTING), "FAINT_ANY", SymptomUrgency.GP),
        // Palpitations happening now that will not stop are 999, not a doctor some day.
        Triple(
            told(Symptom.PALPITATIONS, ticked(SymptomStep.PALP_NOW, SymptomOption.YES)),
            "PALP_NOW",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.PALPITATIONS, ticked(SymptomStep.DURATION, SymptomOption.HOURS)),
            "PALP_LONG",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.PALPITATIONS, ticked(SymptomStep.WITH, SymptomOption.HEART_CONDITION)),
            "PALP_HEART",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.OTHER, ticked(SymptomStep.CHEST_NOW, SymptomOption.YES)),
            "CHEST_NOW",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.CHEST_PAIN, ticked(SymptomStep.CHEST_NOW, SymptomOption.NO)),
            "CHEST_GP",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.CHEST_PAIN, ticked(SymptomStep.WITH, SymptomOption.TIGHT_HEAVY)),
            "CHEST_999",
            SymptomUrgency.EMERGENCY,
        ),
        // The 999 and urgent signs of NHS "Shortness of breath".
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WITH, SymptomOption.TIGHT_HEAVY)),
            "BREATH_SEVERE",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WITH, SymptomOption.PAIN_SPREADING)),
            "BREATH_SEVERE",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WITH, SymptomOption.COUGHING_BLOOD)),
            "BREATH_URGENT",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WITH, SymptomOption.ONE_LEG_SWELLING)),
            "BREATH_URGENT",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WITH, SymptomOption.RACING_HEART)),
            "BREATH_URGENT",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, severity = SymptomSeverity.SEVERE),
            "BREATH_SEVERITY",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.OTHER, ticked(SymptomStep.BREATH_NOW, SymptomOption.YES)),
            "BREATH_NOW",
            SymptomUrgency.EMERGENCY,
        ),
        // Heartburn can be how a heart attack feels.
        Triple(
            told(Symptom.HEARTBURN, ticked(SymptomStep.WITH, SymptomOption.PAIN_SPREADING)),
            "HEARTBURN_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.HEARTBURN, ticked(SymptomStep.WITH, SymptomOption.SWEATY_SICK)),
            "HEARTBURN_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.HEARTBURN, ticked(SymptomStep.HOW_OFTEN, SymptomOption.DAILY)),
            "HEARTBURN_DAILY",
            SymptomUrgency.GP,
        ),
        // The call-999 list NHS "Diarrhoea and vomiting" gives both.
        Triple(
            told(Symptom.DIARRHOEA, ticked(SymptomStep.WITH, SymptomOption.SEVERE_TUMMY_PAIN)),
            "DIARR_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.DIARRHOEA, ticked(SymptomStep.WITH, SymptomOption.CONFUSED)),
            "DIARR_999",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.DIARRHOEA, ticked(SymptomStep.ONSET, SymptomOption.STARTED_A_WEEK)),
            "DIARR_7_DAYS",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.FEVER, ticked(SymptomStep.TREND, SymptomOption.GETTING_WORSE)),
            "FEVER_WORSE",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(
                Symptom.FEVER,
                ticked(SymptomStep.TREND, SymptomOption.NO_CHANGE),
                ticked(SymptomStep.ONSET, SymptomOption.STARTED_2_3_DAYS),
            ),
            "FEVER_STAYS",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.TIREDNESS, ticked(SymptomStep.ONSET, SymptomOption.STARTED_A_MONTH)),
            "TIRED_WEEKS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.TIREDNESS, ticked(SymptomStep.AFFECTS, SymptomOption.EATING)),
            "TIRED_LIFE",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.FEVER, ticked(SymptomStep.STROKE_SIGNS, SymptomOption.YES)),
            "STROKE",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.FEVER, ticked(SymptomStep.UNRESPONSIVE, SymptomOption.YES)),
            "UNRESPONSIVE",
            SymptomUrgency.EMERGENCY,
        ),
        Triple(
            told(Symptom.OTHER, ticked(SymptomStep.WITH, SymptomOption.ONE_SIDE_WEAK)),
            "OTHER_999",
            SymptomUrgency.EMERGENCY,
        ),
        // The body's signs under a feeling are not put down to it.
        Triple(
            told(Symptom.WORRY, ticked(SymptomStep.WITH, SymptomOption.BREATHLESS)),
            "MIND_BREATHLESS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.STRESS, ticked(SymptomStep.WITH, SymptomOption.DIZZY_FAINT)),
            "MIND_FAINT",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.LOW_MOOD, ticked(SymptomStep.HOW_LONG, SymptomOption.MONTHS)),
            "MOOD_2_WEEKS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.STRESS, ticked(SymptomStep.COPING, SymptomOption.TRIED_NOT_HELPING)),
            "MIND_COPING",
            SymptomUrgency.GP,
        ),
        Triple(
            told(
                Symptom.WORRY,
                ticked(SymptomStep.MIND_OFTEN, SymptomOption.MOST_DAYS),
                ticked(SymptomStep.AFFECTS, SymptomOption.SLEEP),
            ),
            "WORRY_MOST",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.SLEEP_WORRY, ticked(SymptomStep.HOW_LONG, SymptomOption.MONTHS)),
            "SLEEP_MONTHS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.WORRY, ticked(SymptomStep.CRISIS, SymptomOption.CRISIS_SAFE)),
            "CRISIS_SAFE",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.WORRY, ticked(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)),
            "CRISIS_SAFE",
            SymptomUrgency.SAME_DAY,
        ),
        Triple(
            told(Symptom.LOW_MOOD, ticked(SymptomStep.CRISIS, SymptomOption.CRISIS_UNSAFE)),
            "CRISIS_UNSAFE",
            SymptomUrgency.EMERGENCY,
        ),
        // "rather not say" is met with help today, never with self-care.
        Triple(
            told(Symptom.LOW_MOOD, ticked(SymptomStep.CRISIS, SymptomOption.RATHER_NOT_SAY)),
            "CRISIS_SKIP",
            SymptomUrgency.SAME_DAY,
        ),
        // Nausea that has not eased in a few days, as "2–3 days ago" or longer and there all the time.
        Triple(
            told(
                Symptom.NAUSEA,
                ticked(SymptomStep.ONSET, SymptomOption.STARTED_2_3_DAYS),
                ticked(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME),
            ),
            "NAUSEA_DAYS",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WHEN, SymptomOption.LYING_NIGHT)),
            "BREATH_LYING",
            SymptomUrgency.GP,
        ),
        Triple(
            told(
                Symptom.BREATHLESSNESS,
                ticked(SymptomStep.WITH, SymptomOption.COUGH),
                ticked(SymptomStep.ONSET, SymptomOption.STARTED_A_MONTH),
            ),
            "BREATH_COUGH",
            SymptomUrgency.GP,
        ),
        Triple(
            told(Symptom.PALPITATIONS, ticked(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME)),
            "PALP_RETURNS",
            SymptomUrgency.GP,
        ),
        Triple(told(Symptom.NAUSEA, severity = SymptomSeverity.SEVERE), "SEVERE", SymptomUrgency.GP),
        Triple(told(Symptom.STRESS, severity = SymptomSeverity.SEVERE), "SEVERE_MIND", SymptomUrgency.GP),
    )

    @Test
    fun eachRuleFiresOnItsAnswers() {
        cases.forEach { (report, id, urgency) ->
            assertTrue("$id in ${fired(report)}", id in fired(report))
            assertEquals(id, urgency, alertRule(id)?.alert?.urgency)
        }
        // And not on the answers beside them.
        assertFalse(
            "PALP_LONG" in fired(told(Symptom.PALPITATIONS, ticked(SymptomStep.DURATION, SymptomOption.SECONDS))),
        )
        assertTrue(fired(told(Symptom.WORRY, ticked(SymptomStep.WITH, SymptomOption.TENSE))).isEmpty())
        assertTrue(fired(told(Symptom.WORRY, ticked(SymptomStep.CRISIS, SymptomOption.NO))).isEmpty())
        assertTrue(fired(told(Symptom.FEVER, ticked(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS))).isEmpty())
        // These rules' edges: nausea started yesterday has not had its few days; one that comes
        // and goes is its own rule; a cough of a week is not "for weeks"; lying down is not walking.
        val yesterday = told(
            Symptom.NAUSEA,
            ticked(SymptomStep.ONSET, SymptomOption.STARTED_YESTERDAY),
            ticked(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME),
        )
        assertFalse("NAUSEA_DAYS" in fired(yesterday))
        val week = told(
            Symptom.BREATHLESSNESS,
            ticked(SymptomStep.WITH, SymptomOption.COUGH),
            ticked(SymptomStep.ONSET, SymptomOption.STARTED_A_WEEK),
        )
        assertFalse("BREATH_COUGH" in fired(week))
        val walking = told(Symptom.BREATHLESSNESS, ticked(SymptomStep.WHEN, SymptomOption.WALKING_STAIRS))
        assertFalse("BREATH_LYING" in fired(walking))
        // Each is shown under the question it reads last.
        assertEquals(SymptomStep.PATTERN, alertRule("NAUSEA_DAYS")?.shownAt)
        assertEquals(SymptomStep.WHEN, alertRule("BREATH_LYING")?.shownAt)
        assertEquals(SymptomStep.WITH, alertRule("BREATH_COUGH")?.shownAt)
        assertEquals(SymptomStep.PATTERN, alertRule("PALP_RETURNS")?.shownAt)
    }

    /**
     * Where shortness of breath's floor, not a red flag, is what calls for a doctor, the closing line
     * is the advice itself and carries the NHS page behind its "?"; where a rule said it, the rule's
     * line has the source and the close is a plain bubble.
     */
    @Test
    fun theFloorsCloseCarriesItsSource() {
        val (_, quiet) = driveChat(Symptom.BREATHLESSNESS) { step, options ->
            if (step == SymptomStep.WHEN) listOf(SymptomOption.WALKING_STAIRS) else calm(options)
        }
        assertTrue(floorSetsUrgency(quiet.asTold()))
        val close = quiet.turn().pieces.filterIsInstance<BotPiece.Line>().single { it.line == ChatLine.CLOSE_GP }
        assertEquals(R.string.symptom_source_breathlessness, close.sourceRes)
        val (_, lying) = driveChat(Symptom.BREATHLESSNESS) { step, options ->
            if (step == SymptomStep.WHEN) listOf(SymptomOption.LYING_NIGHT) else calm(options)
        }
        assertFalse(floorSetsUrgency(lying.asTold()))
        val plain = lying.turn().pieces.filterIsInstance<BotPiece.Line>().single { it.line == ChatLine.CLOSE_GP }
        assertEquals(null, plain.sourceRes)
        // Only a topic with a floor has one.
        assertFalse(floorSetsUrgency(driveChat(Symptom.FEVER).second.asTold()))
    }

    /** Whatever was answered, a chat about shortness of breath ends on seeing a GP at least. */
    @Test
    fun shortnessOfBreathIsNeverLeftAtSelfCare() {
        val (_, report) = driveChat(Symptom.BREATHLESSNESS) { step, options ->
            if (step == SymptomStep.WHEN) listOf(SymptomOption.WALKING_STAIRS) else calm(options)
        }
        assertTrue(firedRules(report.asTold()).isEmpty())
        assertEquals(SymptomUrgency.GP, chatUrgency(report.asTold()))
        val closing = report.turn()
        assertTrue(ChatLine.CLOSE_GP in closing.lines())
        assertTrue(ChatOffer.BOOK_VISIT in closing.offers())
    }

    @Test
    fun theClosingTurnSaysWhatToDoByTheChatsMostUrgentAnswer() {
        // A thread's close says what to do and what is saved, and offers what fits; the thanks is the
        // whole chat's end, and another topic is the router's chip — neither is in a close.
        val calm = driveChat(Symptom.FEVER).second.turn()
        assertEquals(listOf(ChatLine.CLOSE_BODY), calm.lines())
        assertEquals(BotPiece.Saved(0), calm.pieces.first())
        assertEquals(emptyList<ChatOffer>(), calm.offers())

        val urgent = driveChat(Symptom.FEVER) { step, options ->
            if (step == SymptomStep.TREND) listOf(SymptomOption.GETTING_WORSE) else calm(options)
        }.second.turn()
        // The visit is never the only thing said where advice is needed today.
        assertTrue(ChatLine.CLOSE_TODAY in urgent.lines())
        assertEquals(listOf(ChatOffer.BOOK_VISIT), urgent.offers())

        val worry = driveChat(Symptom.WORRY).second.turn()
        assertTrue(ChatLine.CLOSE_MIND in worry.lines())
        assertTrue(ChatOffer.BREATHING in worry.offers())
        // Low mood has no breathing page to offer.
        assertFalse(ChatOffer.BREATHING in driveChat(Symptom.LOW_MOOD).second.turn().offers())
        // Nor anything with a racing heart, breathlessness or chest pain ticked.
        val racing = driveChat(Symptom.WORRY) { step, options ->
            if (step == SymptomStep.WITH) listOf(SymptomOption.RACING_HEART) else calm(options)
        }.second.turn()
        assertFalse(ChatOffer.BREATHING in racing.offers())
    }

    /** After any answer about suicide but "no" — no visit to plan, no breathing, no goodbye, no flash. */
    @Test
    fun anAnswerAboutSuicideIsMetWithHelpAndNothingThatSoundsLikeAnEnd() {
        listOf(SymptomOption.CRISIS_SAFE, SymptomOption.RATHER_NOT_SAY).forEach { answer ->
            val (_, report) = driveChat(Symptom.WORRY) { step, options ->
                when (step) {
                    SymptomStep.COPING -> listOf(SymptomOption.NOT_COPING)
                    SymptomStep.CRISIS -> listOf(answer)
                    else -> calm(options)
                }
            }
            val told = report.asTold()
            assertTrue(crisisAnswered(told))
            assertEquals(SymptomUrgency.SAME_DAY, chatUrgency(told))
            val closing = report.turn()
            assertEquals(listOf(ChatLine.CLOSE_CRISIS), closing.lines())
            assertTrue(closing.offers().isEmpty())
            assertTrue(closing.pieces.none { it is BotPiece.Saved })
            // And the chat's end is no goodbye either: what to do if it gets worse.
            val over = ChatLog(threads = listOf(ChatThread(0, report)), next = 1).settle().endedByPerson()
            assertEquals(listOf(ChatLine.CLOSE_CRISIS), chatTurn(over, chatSetting()).lines())
        }
        val struggling = driveChat(Symptom.WORRY) { step, options ->
            when (step) {
                SymptomStep.COPING -> listOf(SymptomOption.STRUGGLING)
                SymptomStep.CRISIS -> listOf(SymptomOption.RATHER_NOT_SAY)
                else -> calm(options)
            }
        }.second
        val transcript =
            chatTranscript(ChatLog(threads = listOf(ChatThread(0, struggling)), next = 1), chatSetting(), live = true)
        val shown = transcript.filterIsInstance<ChatItem.Bot>().flatMap { it.turn.alerts() }
        assertTrue(SymptomAlert.SAFETY_NET in shown)
        assertEquals(SymptomUrgency.SAME_DAY, SymptomAlert.SAFETY_NET.urgency)
    }

    /** The line for each ticked sign is drawn under the answer that made it true, once. */
    @Test
    fun aLineIsShownUnderTheAnswerThatCalledForItAndNotAgain() {
        val report = newChat().pick(Symptom.DIZZINESS)
            .say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_A_WEEK)
            .say(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES)
            .say(SymptomStep.HOW_OFTEN, SymptomOption.DAILY)
        val turns = checkNotNull(report.chat).turns.filterIsInstance<ChatTurn.Asked>()
        assertEquals(SymptomAlert.DOCTOR, turns.single { it.step == SymptomStep.PATTERN }.alert)
        assertEquals("DIZZY_RETURNS", turns.single { it.step == SymptomStep.PATTERN }.rule)
        assertEquals(null, turns.single { it.step == SymptomStep.HOW_OFTEN }.alert)
        val alert = report.say(
            SymptomStep.DURATION,
            SymptomOption.SECONDS,
        ).turn().pieces.filterIsInstance<BotPiece.Alert>()
        assertTrue(alert.isEmpty())
        val sourced = newChat().pick(Symptom.HEARTBURN).say(SymptomStep.WITH, SymptomOption.PAIN_SPREADING).turn()
        assertEquals(R.string.symptom_source_heart_attack, (sourced.pieces.first() as BotPiece.Alert).sourceRes)
    }

    private class Proposing(private val turn: (ChatState) -> BotTurn) : SymptomResponder {
        override suspend fun next(state: ChatState): BotTurn = turn(state)
    }

    /** The guard: a model may not skip a red flag, drop a line, or say anything of its own. */
    @Test
    fun theGuardKeepsEveryRedFlagAndEveryLineInTheScriptsHands() = runBlocking {
        val vomiting = newChat().pick(Symptom.VOMITING)
        val scripted = ScriptedSymptomResponder.next(chatState(vomiting))
        // Ending early while the blood question is owed is refused.
        val ending = SafetyGuard(Proposing { BotTurn(emptyList(), ChatReply.End) })
        assertEquals(scripted, ending.next(chatState(vomiting)))
        // A model's words are refused while there is no filter for diagnoses.
        val talking =
            SafetyGuard(
                Proposing {
                    BotTurn(scripted.pieces + BotPiece.Said("You have a stomach bug."), scripted.reply)
                },
            )
        assertEquals(scripted, talking.next(chatState(vomiting)))
        // The script itself passes through untouched.
        assertEquals(scripted, symptomResponder.next(chatState(vomiting)))

        // Where a model could add words — a question no rule reads, every safety line kept first — and
        // only with a filter that passes them.
        val calmer = vomiting.say(SymptomStep.BLOOD, SymptomOption.NO).say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY)
        val state = chatState(calmer)
        val plain = turnBefore(state, nextChatStep(state))
        assertEquals(SymptomStep.AFTER_MEAL, plain.asked())
        val worded = BotTurn(plain.pieces + BotPiece.Said("Take your time."), plain.reply)
        assertTrue(acceptsProposal(state, plain, worded) { true })
        assertFalse(acceptsProposal(state, plain, worded) { false })
        // The script's own pieces are not a model's to drop or add to: the question stays, and no line joins it.
        assertFalse(acceptsProposal(state, plain, BotTurn(emptyList(), plain.reply)) { true })
        val closing = BotTurn(plain.pieces + BotPiece.Line(ChatLine.CLOSE_BODY), plain.reply)
        assertFalse(acceptsProposal(state, plain, closing) { true })
        // A dropped safety line is never accepted.
        val alerted = newChat().pick(Symptom.FAINTING)
        val withAlert = turnBefore(chatState(alerted), nextChatStep(chatState(alerted)))
        assertNotNull(withAlert.pieces.firstOrNull { it is BotPiece.Alert })
        val dropped = BotTurn(withAlert.pieces.filter { it !is BotPiece.Alert }, withAlert.reply)
        assertFalse(acceptsProposal(chatState(alerted), withAlert, dropped) { true })
    }
}
