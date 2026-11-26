package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/**
 * The pain thread's families and red flags: which part of the body each
 * area belongs to, the lists each family asks — put together, split past ten chips, the most urgent
 * level winning — and the rules that read them. Every miss here is a 999 or an urgent line not shown.
 */
class ChatPainTest {
    private fun area(code: String): RegionSide = checkNotNull(RegionSide.parse(code)) { code }

    private fun areas(vararg codes: String): Set<RegionSide> = codes.map(::area).toSet()

    /** The lists for [codes], told with or without an injury, for a man or a woman, an adult or not. */
    private fun lists(
        vararg codes: String,
        injury: Boolean? = false,
        female: Boolean = false,
        adult: Boolean = true,
        spreadCovered: Boolean = false,
    ): PainSignLists {
        val marks = areas(*codes)
        return painSignLists(painFamilies(marks), marks, injury, female, adult, spreadCovered)
    }

    private fun PainSignLists.all(): List<SymptomOption> = (first + more).filter { it != SymptomOption.NONE }

    /** A pain thread's report with [codes] as its area answer and [answers] given after it. */
    private fun told(codes: String, vararg answers: Pair<SymptomStep, List<SymptomOption>>, nrs: Int? = null) =
        SymptomReport(
            symptom = Symptom.PAIN,
            answers = answers.toMap(),
            values = mapOf(SymptomStep.PAIN_AREA to codes) + listOfNotNull(nrs?.let { SymptomStep.PAIN_NRS to "$it" }),
        )

    private fun fired(report: SymptomReport): List<String> = firedRules(report).map { it.id }

    private fun pick(step: SymptomStep, vararg options: SymptomOption) = step to options.toList()

    @Test
    fun everyPainRegionHasAFamily() {
        PainRegion.entries.forEach { region ->
            assertTrue(region.name, painFamilies(region).isNotEmpty())
        }
        // Every family but the chest has a list to ask; the chest hands over to its own questions.
        assertEquals(PainFamily.entries.toSet(), PAIN_SIGNS_BY_FAMILY.keys)
        assertTrue(PAIN_SIGNS_BY_FAMILY.getValue(PainFamily.CHEST).isEmpty())
        PainFamily.entries.filter { it != PainFamily.CHEST }.forEach { family ->
            assertTrue(family.name, PAIN_SIGNS_BY_FAMILY.getValue(family).isNotEmpty())
        }
    }

    /** A family by the area's own name first, then by the area it was carved from; the back of the knee is both. */
    @Test
    fun familiesComeFromTheNameThenTheRoot() {
        assertEquals(setOf(PainFamily.KNEE, PainFamily.LEG), painFamilies(PainRegion.BACK_OF_KNEE))
        assertEquals(setOf(PainFamily.CHEST), painFamilies(PainRegion.STERNUM))
        assertEquals(setOf(PainFamily.SHOULDER), painFamilies(PainRegion.TRAPEZIUS))
        assertEquals(setOf(PainFamily.BACK), painFamilies(PainRegion.SACRUM))
        assertEquals(setOf(PainFamily.BACK), painFamilies(PainRegion.FLANK))
        assertEquals(setOf(PainFamily.HAND), painFamilies(PainRegion.FINGERS))
        assertEquals(setOf(PainFamily.HIP), painFamilies(PainRegion.GROIN))
        assertEquals(setOf(PainFamily.LEG), painFamilies(PainRegion.INNER_THIGH))
        assertEquals(setOf(PainFamily.FOOT), painFamilies(PainRegion.HEEL))
        assertEquals(setOf(PainFamily.BELLY), painFamilies(PainRegion.UNDER_RIBS))
        assertEquals(setOf(PainFamily.HEAD), painFamilies(PainRegion.TEMPLE))
        // Several areas: each family once, in the families' order.
        assertEquals(
            listOf(PainFamily.KNEE, PainFamily.LEG, PainFamily.FOOT),
            painFamilies(areas("TOES:L", "BACK_OF_KNEE:R", "CALF:R", "KNEE:L")),
        )
    }

    /** Ten chips or fewer are one list, most urgent first; more, and the call-999 and A&E ones come first. */
    @Test
    fun theListsSplitPastTenChipsByLevel() {
        val hip = lists("HIP:R")
        assertEquals(6, hip.all().size)
        assertTrue(hip.more.isEmpty())
        assertEquals(SymptomOption.NONE, hip.first.last())
        val levels = hip.all().map { hip.level.getValue(it) }
        assertEquals(levels.sortedBy { it.ordinal }, levels)

        val back = lists("LOWER_BACK:C")
        assertTrue(back.all().size > 10)
        assertTrue(back.first.dropLast(1).all { back.level.getValue(it) == SignLevel.E })
        assertTrue(back.more.dropLast(1).all { back.level.getValue(it) in setOf(SignLevel.D, SignLevel.G) })
        assertEquals(SymptomOption.NONE, back.more.last())

        // The neck: nine without an injury, one list; thirteen with one, split.
        assertEquals(9, lists("NECK:C").all().size)
        assertTrue(lists("NECK:C").more.isEmpty())
        val injured = lists("NECK:C", injury = true)
        assertEquals(13, injured.all().size)
        assertTrue(injured.more.isNotEmpty())
        // The head: the nine 999 signs, then the rest.
        val head = lists("TEMPLE:L")
        assertEquals(9, head.first.size - 1)
        assertEquals(SymptomOption.SUDDEN_SEVERE, head.first.first())
    }

    /** An option two families ask is asked once, at the more urgent level, in the words of the row that gave it. */
    @Test
    fun dedupeKeepsTheMostUrgentLevelAndItsLabel() {
        val hipAndKnee = lists("HIP:R", "KNEE:R")
        // The hip's fever is a GP-today line in its own words; the knee's, later in the order, is "111 now".
        assertEquals(SignLevel.N, hipAndKnee.level[SymptomOption.FEVERISH])
        assertEquals(R.string.pain_chat_sign_feverish, hipAndKnee.labels[SymptomOption.FEVERISH])
        // The hip's "cannot walk" is 999, before the knee's "111 now".
        assertEquals(SignLevel.E, hipAndKnee.level[SymptomOption.NO_WEIGHT])
        assertEquals(R.string.pain_chat_sign_no_weight_hip, hipAndKnee.labels[SymptomOption.NO_WEIGHT])
        assertEquals(1, hipAndKnee.all().count { it == SymptomOption.NO_WEIGHT })
        // On a tie the first family's words stand: the neck's "chest too" before the back's.
        val neckAndBack = lists("NECK:C", "UPPER_BACK:C")
        assertEquals(R.string.pain_chat_sign_chest_too, neckAndBack.labels[SymptomOption.CHEST_TOO])
        // Except that "chest too" keeps its own, widest words whatever family comes first: an arm with the
        // back or the belly is asked about pressure, tightness and squeezing too.
        listOf("UPPER_BACK:C", "LOWER_BACK:C", "UPPER_BELLY:C").forEach { other ->
            val armAnd = lists("UPPER_ARM:L", other)
            assertEquals(other, R.string.pain_chat_sign_chest_too, armAnd.labels[SymptomOption.CHEST_TOO])
            assertEquals(other, 1, armAnd.all().count { it == SymptomOption.CHEST_TOO })
        }
        assertEquals(
            R.string.pain_chat_sign_chest_too_back_belly,
            lists("UPPER_BACK:C").labels[SymptomOption.CHEST_TOO],
        )
        // Every other tie keeps the first family's words: the hip's fever before the knee's at one level.
        assertEquals(R.string.pain_chat_sign_feverish, hipAndKnee.labels[SymptomOption.FEVERISH])
    }

    /**
     * The steps the pain rules read are owed: a model may never word or skip them — whether the pain is
     * still there among them, which the weeks and painkiller rules read.
     */
    @Test
    fun theStepsThePainRulesReadAreOwed() {
        listOf(
            SymptomStep.PAIN_AREA,
            SymptomStep.PAIN_INJURY,
            SymptomStep.PAIN_SIGNS,
            SymptomStep.PAIN_SIGNS_MORE,
            SymptomStep.PAIN_SEPSIS,
            SymptomStep.PAIN_NRS,
            SymptomStep.PAIN_STATUS,
            SymptomStep.PAIN_LASTED,
            SymptomStep.PAIN_PAINKILLER,
            SymptomStep.CHEST_SPREAD,
        ).forEach { assertTrue(it.name, it in PainScript.owedSteps) }
        assertFalse(SymptomStep.PAIN_TYPES in PainScript.owedSteps)
    }

    /** The knee without an injury asks the knee page's own items, one list. */
    @Test
    fun theKneeWithoutInjuryShowsTheKneeTrueChips() {
        val knee = lists("KNEE:L")
        assertEquals(
            listOf(
                SymptomOption.SUDDEN_SEVERE,
                SymptomOption.VERY_PAINFUL,
                SymptomOption.NO_WEIGHT,
                SymptomOption.ODD_SHAPE,
                SymptomOption.LOCKS_GIVES,
                SymptomOption.FEVERISH,
                SymptomOption.SKIN_COLOUR,
                SymptomOption.SICK_CANT_EAT,
                SymptomOption.JOINT_WORSE,
                SymptomOption.NONE,
            ),
            knee.first,
        )
        assertTrue(knee.more.isEmpty())
        assertEquals(R.string.pain_chat_sign_no_weight_knee, knee.labels[SymptomOption.NO_WEIGHT])
        assertEquals(R.string.pain_chat_sign_odd_shape_knee, knee.labels[SymptomOption.ODD_SHAPE])
        // With an injury: the 999 and A&E ones first, the kneecap and the stiffness after.
        val injured = lists("KNEE:L", injury = true)
        assertTrue(SymptomOption.FAST_SWELL in injured.first)
        assertTrue(SymptomOption.KNEECAP_SLIPPED in injured.more)
        assertEquals(R.string.pain_chat_sign_very_painful_inj, injured.labels[SymptomOption.VERY_PAINFUL])
    }

    /** Every family, injured or not, asks at least one call-999 sign in its first list. */
    @Test
    fun everyFamilyShowsAnEmergencyChip() {
        val sample = mapOf(
            PainFamily.HEAD to "HEAD_FRONT:C",
            PainFamily.JAW to "JAW:L",
            PainFamily.NECK to "NECK:C",
            PainFamily.SHOULDER to "SHOULDER:R",
            PainFamily.BACK to "UPPER_BACK:C",
            PainFamily.BELLY to "LOWER_BELLY:C",
            PainFamily.ARM to "FOREARM:L",
            PainFamily.HAND to "WRIST_HAND:R",
            PainFamily.HIP to "HIP:L",
            PainFamily.KNEE to "KNEE:R",
            PainFamily.LEG to "CALF:L",
            PainFamily.FOOT to "SOLE:R",
        )
        assertEquals(PainFamily.entries.toSet() - PainFamily.CHEST, sample.keys)
        sample.forEach { (family, code) ->
            listOf(true, false).forEach { injury ->
                val asked = lists(code, injury = injury)
                assertTrue("$family $injury", asked.first.any { asked.level[it] == SignLevel.E })
            }
        }
    }

    /** A fever below 999 ticked brings the sepsis question right after its list; any sign there is 999. */
    @Test
    fun feverishBelowEmergencyBringsTheSepsisQuestion() {
        val knee = told("KNEE:R", pick(SymptomStep.PAIN_INJURY, SymptomOption.NO))
        val kneeAsked = knee.withAsked(SymptomStep.PAIN_AREA, SymptomStep.PAIN_INJURY)
        val state = ChatState(kneeAsked, female = false, earlier = null)
        val calm = state.copy(report = state.report.withAnswer(SymptomStep.PAIN_SIGNS, SymptomOption.NONE))
        assertFalse(SymptomStep.PAIN_SEPSIS in painThreadSteps(calm))
        val fever = state.copy(report = state.report.withAnswer(SymptomStep.PAIN_SIGNS, SymptomOption.FEVERISH))
        val steps = painThreadSteps(fever)
        assertEquals(steps.indexOf(SymptomStep.PAIN_SIGNS) + 1, steps.indexOf(SymptomStep.PAIN_SEPSIS))
        assertEquals(listOf("PAIN_KNEE_N"), fired(fever.report))
        val sepsis = fever.report.withAnswer(SymptomStep.PAIN_SEPSIS, SymptomOption.BLUE_SKIN)
        assertTrue("PAIN_SEPSIS_E" in fired(sepsis))
        assertEquals(SymptomUrgency.EMERGENCY, chatUrgency(sepsis))
        assertFalse("PAIN_SEPSIS_E" in fired(fever.report.withAnswer(SymptomStep.PAIN_SEPSIS, SymptomOption.NONE)))
        // The belly's fever is 999 itself: no question after it.
        val belly = ChatState(
            told("UPPER_BELLY:C")
                .withAsked(SymptomStep.PAIN_AREA)
                .withAnswer(SymptomStep.PAIN_SIGNS, SymptomOption.FEVERISH),
            female = false,
            earlier = null,
        )
        assertFalse(SymptomStep.PAIN_SEPSIS in painThreadSteps(belly))
        assertTrue("PAIN_BELLY_E" in fired(belly.report))
    }

    /** A woman is asked the pregnancy and bleeding items; someone not yet an adult, whether it wakes them at night. */
    @Test
    fun femaleAndChildChipsFollowTheProfile() {
        val womanBelly = lists("LOWER_BELLY:C", female = true).all()
        val manBelly = lists("LOWER_BELLY:C").all()
        listOf(SymptomOption.HEAVY_BLEEDING, SymptomOption.PREGNANCY, SymptomOption.VAGINAL).forEach {
            assertTrue(it.name, it in womanBelly)
            assertFalse(it.name, it in manBelly)
        }
        assertTrue(SymptomOption.PREGNANCY in lists("FLANK:L", female = true).all())
        assertFalse(SymptomOption.PREGNANCY in lists("UPPER_BACK:C", female = true).all())
        assertTrue(SymptomOption.CHILD_WORSE in lists("HEAD_TOP:C", adult = false).all())
        assertFalse(SymptomOption.CHILD_WORSE in lists("HEAD_TOP:C").all())
    }

    /** The jaw: the TMD page's "111 now" card from the area alone, under the area answer. */
    @Test
    fun theJawFiresTmdAtTheArea() {
        val jaw = newChat().chose(Symptom.PAIN).valued(SymptomStep.PAIN_AREA, "JAW:R").answered(SymptomStep.PAIN_AREA)
        val turn = jaw.chat?.turns?.last() as ChatTurn.Asked
        assertEquals(SymptomAlert.URGENT_NOW, turn.alert)
        assertEquals("PAIN_JAW_TMD", turn.rule)
        assertEquals(R.string.pain_source_jaw, alertRule(turn.rule)?.sourceRes)
        val knee = newChat().chose(Symptom.PAIN).valued(SymptomStep.PAIN_AREA, "KNEE:R").answered(SymptomStep.PAIN_AREA)
        assertNull((knee.chat?.turns?.last() as ChatTurn.Asked).alert)
    }

    /**
     * A rating of eight or more is a second way to a family's line for severe pain, never the only
     * one: for every family and injury where it fires, the family's list asks a chip of its severity
     * at least as urgent.
     */
    @Test
    fun nrsRulesAreExtraTriggersOnly() {
        val severityChips = setOf(
            SymptomOption.SUDDEN_SEVERE,
            SymptomOption.SUDDEN_OR_SEVERE,
            SymptomOption.SUDDEN_OR_BAD,
            SymptomOption.VERY_PAINFUL,
            SymptomOption.SEVERE_SINCE_FALL,
            SymptomOption.SEVERE_HEADACHE,
            SymptomOption.SEVERE_CANT_MOVE,
        )
        val nrsRules = PAIN_RULES.filter { it.shownAt == SymptomStep.PAIN_NRS }.map { it.id }.toSet()
        val samples = listOf(
            "HEAD_BACK:C", "JAW:L", "NECK:C", "SHOULDER:R", "UPPER_BACK:C", "FLANK:R", "LOWER_SIDE:R", "UPPER_ARM:L",
            "WRIST_HAND:L", "HIP:R", "KNEE:L", "CALF:R", "ANKLE:L",
        )
        var fires = 0
        samples.forEach { code ->
            listOf(true, false).forEach { injury ->
                val injuryAnswer = if (injury) SymptomOption.YES else SymptomOption.NO
                val report = told(code, pick(SymptomStep.PAIN_INJURY, injuryAnswer), nrs = 9)
                val lines = firedRules(report).filter { it.id in nrsRules }
                val chips = lists(code, injury = injury)
                val severity = chips.all().filter { it in severityChips }
                val chipLevels = severity.map { chips.level.getValue(it).alert.urgency }
                lines.forEach { line ->
                    fires++
                    assertTrue("$code $injury ${line.id}", chipLevels.any { it <= line.alert.urgency })
                }
                // Below eight nothing of these fires.
                val milder = told(code, pick(SymptomStep.PAIN_INJURY, injuryAnswer), nrs = 7)
                assertTrue(firedRules(milder).none { it.id in nrsRules })
            }
        }
        assertTrue(fires > samples.size)
        // The head has none of them: its severe pain is the safety line's.
        assertTrue(fired(told("TEMPLE:L", nrs = 10)).none { it in nrsRules })
    }

    /**
     * A pain still there after its family's weeks is a GP's: by the onset for a new one, by the first report's age for
     * the same one.
     */
    @Test
    fun painWeeksUsesTheFamilyDays() {
        val asked = arrayOf(
            pick(SymptomStep.PAIN_STATUS, SymptomOption.STILL_HURTS),
            pick(SymptomStep.PAIN_PAINKILLER, SymptomOption.NO),
        )
        fun same(code: String, days: Int) = told(code, pick(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES), *asked)
            .copy(meta = mapOf(PAIN_SAME_DAYS to "$days"))
        assertFalse("PAIN_WEEKS" in fired(same("KNEE:R", 14)))
        assertTrue("PAIN_WEEKS" in fired(same("KNEE:R", 21)))
        assertTrue("PAIN_WEEKS" in fired(same("SHOULDER:L", 14)))
        assertFalse("PAIN_WEEKS" in fired(same("SHOULDER:L", 13)))
        assertFalse("PAIN_WEEKS" in fired(same("NECK:C", 20)))
        assertTrue("PAIN_WEEKS" in fired(same("NECK:C", 21)))
        // The leg's pages give no such line.
        assertFalse("PAIN_WEEKS" in fired(same("CALF:R", 60)))
        val month = told("KNEE:R", pick(SymptomStep.ONSET, SymptomOption.STARTED_A_MONTH), *asked)
        assertTrue("PAIN_WEEKS" in fired(month))
        val week = told("KNEE:R", pick(SymptomStep.ONSET, SymptomOption.STARTED_A_WEEK), *asked)
        assertFalse("PAIN_WEEKS" in fired(week))
        // Gone, or before the painkiller is answered, it is not said.
        assertFalse(
            "PAIN_WEEKS" in fired(
                told(
                    "KNEE:R",
                    pick(SymptomStep.ONSET, SymptomOption.STARTED_A_MONTH),
                    pick(SymptomStep.PAIN_STATUS, SymptomOption.STILL_HURTS),
                ),
            ),
        )
        val sourced = firedRules(same("SHOULDER:L", 20)).first { it.id == "PAIN_WEEKS" }
        assertEquals(R.string.pain_source_shoulder, sourced.sourceOf?.invoke(same("SHOULDER:L", 20)))
    }

    /**
     * Under the right ribs a pain still there that did not begin just now, or one gone after more than
     * half an hour, is today's (NHS "Gallstones"); a leg cramp of more than ten minutes is a GP's (NHS
     * "Leg cramps").
     */
    @Test
    fun theRibsAndTheCrampGoByHowLong() {
        fun gone(code: String, minutes: Int, vararg more: Pair<SymptomStep, List<SymptomOption>>) =
            told(code, pick(SymptomStep.PAIN_STATUS, SymptomOption.GONE), *more)
                .let { it.copy(values = it.values + (SymptomStep.PAIN_LASTED to "$minutes")) }
        assertTrue("PAIN_RIBS_LONG" in fired(gone("UNDER_RIBS:R", 31)))
        assertFalse("PAIN_RIBS_LONG" in fired(gone("UNDER_RIBS:R", 30)))
        assertTrue("PAIN_RIBS_LONG" in fired(gone("UPPER_BELLY:C", 45)))
        assertFalse("PAIN_RIBS_LONG" in fired(gone("UNDER_RIBS:L", 45)))
        val still = pick(SymptomStep.PAIN_STATUS, SymptomOption.STILL_HURTS)
        fun since(onset: SymptomOption) = told("UNDER_RIBS:R", still, pick(SymptomStep.ONSET, onset))
        assertTrue("PAIN_RIBS_LONG" in fired(since(SymptomOption.STARTED_TODAY)))
        assertFalse("PAIN_RIBS_LONG" in fired(since(SymptomOption.STARTED_NOW)))
        val cramp = pick(SymptomStep.PAIN_TYPES, SymptomOption.PAIN_CRAMPING)
        assertTrue("PAIN_LEG_CRAMP" in fired(gone("CALF:L", 11, cramp)))
        assertFalse("PAIN_LEG_CRAMP" in fired(gone("CALF:L", 10, cramp)))
        assertFalse("PAIN_LEG_CRAMP" in fired(gone("CALF:L", 30)))
    }

    /** "Note what you took" follows a painkiller taken today; for a day gone by there is nothing to note now. */
    @Test
    fun theDoseFollowUpIsForToday() {
        val took = ChatState(told("KNEE:R", pick(SymptomStep.PAIN_PAINKILLER, SymptomOption.YES)), false, null)
        assertEquals(listOf(Symptom.DOSE), PainScript.followUps(took).map { it.topic })
        assertTrue(PainScript.followUps(took.copy(pastDay = true)).isEmpty())
        val none = ChatState(told("KNEE:R", pick(SymptomStep.PAIN_PAINKILLER, SymptomOption.NO)), false, null)
        assertTrue(PainScript.followUps(none).isEmpty())
    }

    /** In a chat for an earlier day every list of red flags asks what is happening now. */
    @Test
    fun aPastDayChatAsksNowOnEveryRedFlagList() {
        val report = told("KNEE:R").withAsked(SymptomStep.PAIN_AREA)
        val past = ChatState(report, female = false, earlier = null, pastDay = true)
        val today = past.copy(pastDay = false)
        listOf(SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE, SymptomStep.PAIN_SEPSIS).forEach { step ->
            assertEquals(step.name, R.string.symptom_ask_with_now, PainScript.question(past, step))
        }
        assertEquals(R.string.symptom_ask_with, PainScript.question(today, SymptomStep.PAIN_SIGNS))
        assertEquals(R.string.pain_chat_ask_signs_more, PainScript.question(today, SymptomStep.PAIN_SIGNS_MORE))
        assertEquals(R.string.pain_chat_ask_sepsis, PainScript.question(today, SymptomStep.PAIN_SEPSIS))
        assertEquals(R.string.pain_chat_nrs_day, PainScript.question(past, SymptomStep.PAIN_NRS))
        assertEquals(R.string.pain_chat_status_day, PainScript.question(past, SymptomStep.PAIN_STATUS))
        assertEquals(R.string.pain_chat_painkiller_day, PainScript.question(past, SymptomStep.PAIN_PAINKILLER))
    }

    /** The safety line after the rating: for any chest area, and for the head at eight or more. */
    @Test
    fun painSafetyShowsForAChestAreaAndAHeadAtEight() {
        fun lead(codes: String, nrs: Int): List<BotPiece> {
            val report = told(codes, nrs = nrs).withAsked(SymptomStep.PAIN_AREA, SymptomStep.PAIN_NRS)
            val state = ChatState(report, female = false, earlier = null, thread = ThreadContext(chestAsked = true))
            return PainScript.lead(state, ChatTurn.Asked(SymptomStep.PAIN_NRS), SymptomStep.PAIN_NOTICED)
        }
        val safety = BotPiece.Line(ChatLine.PAIN_SAFETY)
        assertTrue(safety in lead("HEAD_FRONT:C", 8))
        assertFalse(safety in lead("HEAD_FRONT:C", 7))
        assertTrue(safety in lead("STERNUM:C", 3))
        assertTrue(safety in lead("CHEST:L,UPPER_ARM:L", 2))
        assertFalse(safety in lead("KNEE:R", 10))
        assertEquals(R.string.pain_source_safety, ChatLine.PAIN_SAFETY.sourceRes)
        assertTrue(ChatLine.PAIN_SAFETY.safety)
    }

    /** The lower back asks the kidney pages' "very painful"; the top of the back does not. */
    @Test
    fun theLowerBackHasAVeryPainfulChip() {
        assertTrue(SymptomOption.VERY_PAINFUL in lists("LOWER_BACK:C").all())
        assertTrue(SymptomOption.VERY_PAINFUL in lists("FLANK:R").all())
        assertFalse(SymptomOption.VERY_PAINFUL in lists("UPPER_BACK:C").all())
        val ticked = told("FLANK:R", pick(SymptomStep.PAIN_SIGNS_MORE, SymptomOption.VERY_PAINFUL))
        assertTrue("PAIN_BACK_D" in fired(ticked))
        val upper = told("UPPER_BACK:C", pick(SymptomStep.PAIN_SIGNS_MORE, SymptomOption.VERY_PAINFUL))
        assertFalse("PAIN_BACK_D" in fired(upper))
        // And a loin rated eight or more is the same line by the app's convention.
        assertTrue("PAIN_FLANK_SEVERE" in fired(told("FLANK:L", nrs = 8)))
    }

    /** Gout's and septic arthritis' items reach every joint they name, and no other area. */
    @Test
    fun goutAndSepticArthritisReachEveryJointTheyName() {
        listOf("ANKLE_FOOT:L", "ANKLE:R", "TOES:L").forEach { joint ->
            val foot = lists(joint).all()
            val items = listOf(SymptomOption.JOINT_WORSE, SymptomOption.SWOLLEN_JOINT, SymptomOption.SKIN_COLOUR)
            assertTrue(joint, foot.containsAll(items))
        }
        val heel = lists("HEEL:L").all()
        assertFalse(SymptomOption.JOINT_WORSE in heel)
        assertFalse(SymptomOption.SWOLLEN_JOINT in heel)
        assertTrue(lists("KNEE:R").all().containsAll(listOf(SymptomOption.JOINT_WORSE, SymptomOption.SKIN_COLOUR)))
        assertTrue(SymptomOption.SWOLLEN_JOINT in lists("HIP:L").all())
        assertTrue(SymptomOption.SWOLLEN_JOINT in lists("GROIN:L").all())
        listOf("ELBOW:L", "ELBOW_INNER:R").forEach { elbow ->
            val items = listOf(SymptomOption.SWOLLEN_JOINT, SymptomOption.SKIN_COLOUR)
            assertTrue(elbow, lists(elbow).all().containsAll(items))
        }
        assertFalse(SymptomOption.SWOLLEN_JOINT in lists("FOREARM:L").all())
        assertTrue(SymptomOption.SKIN_COLOUR in lists("SHOULDER:R").all())
        // After an injury these are not asked: the injury's own items are.
        assertFalse(SymptomOption.JOINT_WORSE in lists("TOES:L", injury = true).all())
    }

    /** The heel page's tiptoe, stairs and calf-and-ankle items need no injury on a heel area. */
    @Test
    fun heelItemsNeedNoInjury() {
        listOf("HEEL:R", "ACHILLES:L", "ANKLE:R", "ANKLE_FOOT:L").forEach { heel ->
            val asked = lists(heel).all()
            assertTrue(heel, SymptomOption.TIPTOE_STAIRS in asked && SymptomOption.CALF_ANKLE_SWELL in asked)
        }
        assertFalse(SymptomOption.TIPTOE_STAIRS in lists("TOES:R").all())
        assertTrue(SymptomOption.TIPTOE_STAIRS in lists("TOES:R", injury = true).all())
        assertTrue(SymptomOption.CALF_ANKLE_SWELL in lists("SOLE:L", injury = true).all())
    }

    /** The back and the belly together ask both "passing out" and the belly's wider faint item. */
    @Test
    fun passedOutAndTheBellysFaintChipBothShowForBackAndBelly() {
        val both = lists("LOWER_BACK:C", "UPPER_BELLY:C")
        assertTrue(SymptomOption.PASSED_OUT in both.all())
        assertTrue(SymptomOption.DIZZY_FAINT in both.all())
        assertEquals(R.string.pain_chat_sign_dizzy_faint_belly, both.labels[SymptomOption.DIZZY_FAINT])
        assertTrue(SymptomOption.PASSED_OUT in lists("CALF:L").all())
        assertTrue("PAIN_LEG_E" in fired(told("CALF:L", pick(SymptomStep.PAIN_SIGNS, SymptomOption.PASSED_OUT))))
    }

    /**
     * One rule per family and level a list has, each with its family's pages; the chest's spread is 999 in both
     * threads.
     */
    @Test
    fun theSignRulesAreOnePerFamilyAndLevel() {
        val expected = PAIN_SIGNS_BY_FAMILY.flatMap { (family, signs) ->
            signs.map { it.level }.distinct().sortedBy { it.ordinal }.map { "PAIN_${family.name}_${it.name}" }
        }
        assertEquals(31, expected.size)
        val ids = PAIN_RULES.map { it.id }
        assertEquals(expected, ids.take(expected.size))
        assertEquals(ids.size, ids.toSet().size)
        PAIN_SIGNS_BY_FAMILY.keys.forEach { family ->
            ids.filter { it.startsWith("PAIN_${family.name}_") && it.length == "PAIN_${family.name}_E".length }
                .forEach { id -> assertEquals(id, family.sourceRes, alertRule(id)?.sourceRes) }
        }
        val spread = checkNotNull(alertRule("CHEST_SPREAD"))
        assertEquals(setOf(Symptom.CHEST_PAIN, Symptom.PAIN), spread.topics)
        assertEquals(SymptomAlert.EMERGENCY, spread.alert)
        assertEquals(R.string.symptom_source_chest_spread, spread.sourceRes)
        // An injured foot ticked as very painful is A&E now, and stops the thread as a 999 line does.
        val foot = told(
            "ANKLE:L",
            pick(SymptomStep.PAIN_INJURY, SymptomOption.YES),
            pick(SymptomStep.PAIN_SIGNS, SymptomOption.VERY_PAINFUL),
        )
        assertEquals(listOf("PAIN_FOOT_I"), fired(foot))
        assertEquals(SymptomUrgency.EMERGENCY, chatUrgency(foot))
    }

    /** "Now" is sooner than "today": a 111-now line wins over a today one of the same urgency. */
    @Test
    fun theLineToGetAdviceNowGoesBeforeTodays() {
        assertEquals(SymptomAlert.URGENT_NOW, mostUrgent(listOf(SymptomAlert.URGENT, SymptomAlert.URGENT_NOW)))
        assertEquals(SymptomAlert.EMERGENCY, mostUrgent(listOf(SymptomAlert.IN_PERSON_NOW, SymptomAlert.EMERGENCY)))
        val inPerson = mostUrgent(listOf(SymptomAlert.URGENT_NOW, SymptomAlert.IN_PERSON_NOW))
        assertEquals(SymptomAlert.IN_PERSON_NOW, inPerson)
    }

    /**
     * A list in the chat's words is joined with the comma of the language showing — "、" in Japanese,
     * "، " in Arabic — wherever it is put together: an area chip, the areas and the words a question
     * names, an answer read back. A ", " written into the code would be the English one in every language.
     */
    @Test
    fun aListInTheChatIsJoinedWithTheLanguagesComma() {
        val names = NamedResources()
        val knee = area("KNEE:R")
        val calf = area("CALF:L")
        val both = names.painArea(knee) + "list_comma" + names.painArea(calf)
        assertEquals(both, names.areaChoiceLabel(AreaChoice(listOf(knee, calf))))
        fun said(arg: PieceArg) =
            names.render(ChatText(R.string.symptom_ask_chest_spread, listOf(arg)), Locale.ROOT, ZoneOffset.UTC)
        assertEquals("symptom_ask_chest_spread[$both]", said(PieceArg.Areas(listOf("KNEE:R", "CALF:L"))))
        val words = PieceArg.Listed(listOf(PieceArg.Text("aching"), PieceArg.Text("tight")))
        assertEquals("symptom_ask_chest_spread[achinglist_commatight]", said(words))
        val about = listOf(SymptomOption.FAMILY, SymptomOption.WORK_STUDY)
        val report = SymptomReport(answers = mapOf(SymptomStep.MOOD_ABOUT to about))
        assertEquals(
            "symptom_option_family" + "list_comma" + "symptom_option_work_study",
            names.plainAnswer(SymptomStep.MOOD_ABOUT, report),
        )
    }
}

/** [this] with [steps] taken as asked, in order, as the thread's turns. */
internal fun SymptomReport.withAsked(vararg steps: SymptomStep): SymptomReport {
    val told = chat ?: SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM)))
    return copy(chat = told.copy(turns = told.turns + steps.map { ChatTurn.Asked(it) }))
}

/** [this] with [options] answered to [step], its turn added. */
internal fun SymptomReport.withAnswer(step: SymptomStep, vararg options: SymptomOption): SymptomReport =
    copy(answers = answers + (step to options.toList())).withAsked(step)
