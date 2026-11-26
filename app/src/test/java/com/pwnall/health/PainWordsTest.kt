package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body's lexicon: the place each area word names on the map of this build,
 * what a family too wide for chips opens, how a side or a word of place narrows a family, and the
 * first widget a pain thread shows for it.
 */
class PainWordsTest {
    private val setting = chatSetting()

    private fun pain(text: String) = readWords(text, setting).mentions.filterIsInstance<PainWordMention>().single()

    private fun codes(text: String) = pain(text).candidates.map { it.code }

    private fun chips(text: String) = (pain(text).proposal as PainProposal.Chips).choices.map { choice ->
        if (choice.both) choice.areas.first().region.name + ":BOTH" else choice.areas.single().code
    }

    private fun map(text: String) = (pain(text).proposal as PainProposal.Body).frame

    /** Each area word, in its Russian cases and English forms, names its place: on the second map, this build's. */
    @Test
    fun everyAreaWordNamesItsPlace() {
        assertTrue(secondMapNamesIn(PainRegion.entries.mapTo(HashSet()) { it.name }))
        mapOf(
            "болит колено" to listOf("KNEE:L", "KNEE:R"),
            "болит коленка" to listOf("KNEE:L", "KNEE:R"),
            "болит в коленях" to listOf("KNEE:L", "KNEE:R"),
            "my knees hurt" to listOf("KNEE:L", "KNEE:R"),
            "болит шея" to listOf("NECK:C"),
            "болит шею, тянет" to listOf("NECK:C"),
            "болит плечо" to listOf("SHOULDER:L", "SHOULDER:R"),
            "болит между шеей и плечом" to listOf("TRAPEZIUS:L", "TRAPEZIUS:R"),
            "болит под лопаткой" to listOf("SHOULDER_BLADE:L", "SHOULDER_BLADE:R"),
            "болит между лопатками" to listOf("UPPER_BACK:C"),
            "болит поясница" to listOf("LOWER_BACK:C"),
            "lower back pain" to listOf("LOWER_BACK:C"),
            "болят почки" to listOf("FLANK:L", "FLANK:R"),
            "болит крестец" to listOf("SACRUM:L", "SACRUM:R"),
            "болит ягодица" to listOf("GLUTE:L", "GLUTE:R"),
            "болит пятая точка" to listOf("GLUTE:L", "GLUTE:R"),
            "болит под ложечкой" to listOf("UPPER_BELLY:C"),
            "болит под ребрами" to listOf("UNDER_RIBS:L", "UNDER_RIBS:R"),
            "болит низ живота" to listOf("LOWER_BELLY:C"),
            "болит пупок" to listOf("LOWER_BELLY:C"),
            "болит подвздошная область" to listOf("LOWER_SIDE:L", "LOWER_SIDE:R"),
            "болит бицепс" to listOf("UPPER_ARM:L", "UPPER_ARM:R"),
            "болит трицепс" to listOf("BACK_OF_UPPER_ARM:L", "BACK_OF_UPPER_ARM:R"),
            "болит локоть" to listOf("ELBOW:L", "ELBOW:R"),
            "golfer’s elbow hurts" to listOf("ELBOW_INNER:L", "ELBOW_INNER:R"),
            "болит предплечье" to listOf("FOREARM:L", "FOREARM:R"),
            "болит запястье" to listOf("WRIST_HAND:L", "WRIST_HAND:R"),
            "болит ладонь" to listOf("WRIST_HAND:L", "WRIST_HAND:R"),
            "болит пах" to listOf("GROIN:L", "GROIN:R"),
            "hip pain" to listOf("HIP:L", "HIP:R"),
            "болит бедро спереди" to listOf("THIGH_FRONT:L", "THIGH_FRONT:R"),
            "hamstring hurts" to listOf("THIGH_BACK:L", "THIGH_BACK:R"),
            "болит под коленом" to listOf("BACK_OF_KNEE:L", "BACK_OF_KNEE:R"),
            "болит голень" to listOf("SHIN:L", "SHIN:R"),
            "болит голеностоп" to listOf("ANKLE:L", "ANKLE:R"),
            "болит икра" to listOf("CALF:L", "CALF:R"),
            "болит ахилл" to listOf("ACHILLES:L", "ACHILLES:R"),
            "болит пятка" to listOf("HEEL:L", "HEEL:R"),
            "болит подошва" to listOf("SOLE:L", "SOLE:R"),
            "болит большой палец ноги" to listOf("TOES:L", "TOES:R"),
            "болит палец на руке" to listOf("FINGERS:L", "FINGERS:R"),
            "болит челюсть" to listOf("JAW:L", "JAW:R"),
            "болит лоб" to listOf("HEAD_FRONT:C"),
            "болит затылок" to listOf("HEAD_BACK:C"),
            "у меня радикулит" to listOf("LOWER_BACK:C"),
        ).forEach { (text, places) -> assertEquals(text, places, codes(text)) }
        // The longer key holds its words: the ankle over the shin, the breastbone over the chest, and the knee's back.
        assertEquals("FOOT", pain("болит голеностоп").family)
        assertEquals(listOf("STERNUM:C"), codes("болит грудина"))
        assertEquals(listOf("BACK_OF_KNEE:L", "BACK_OF_KNEE:R"), codes("pain in the back of my knee"))
    }

    /** A word as wide as a family opens the map framed on it; nothing named, the whole body. */
    @Test
    fun aFamilyWordOpensTheMapFramedOnIt() {
        assertEquals(PainFrame(PainView.FRONT, "LEG"), map("болит нога"))
        assertEquals(PainFrame(PainView.FRONT, "LEG"), map("болит бедро"))
        assertEquals(PainFrame(PainView.FRONT, "ARM"), map("болит рука"))
        assertEquals(PainFrame(PainView.BACK, "BACK"), map("болит спина"))
        assertEquals(PainFrame(PainView.FRONT, "BELLY"), map("болит живот"))
        assertEquals(PainFrame(PainView.FRONT, "FOOT"), map("болит стопа"))
        assertEquals(PainFrame(PainView.FRONT, "BELLY"), map("my side hurts"))
        assertEquals(PainFrame(PainView.BACK, "BACK"), map("болит копчик"))
        assertEquals(PainFrame(PainView.FRONT, null), map("всё тело ломит"))
        assertEquals(PainFrame(PainView.FRONT, null), map("всё болит"))
        // Two places of one family: the family's map, not two rows of chips.
        assertEquals(PainFrame(PainView.FRONT, "LEG"), map("болит голень и икра"))
        // An arm with a side has its four places as chips.
        assertEquals(listOf("UPPER_ARM:R", "ELBOW:R", "FOREARM:R", "WRIST_HAND:R"), chips("болит правая рука"))
    }

    /** A side or a word of place said with a family narrows it. */
    @Test
    fun qualifiersNarrowTheFamily() {
        mapOf(
            "болит живот справа внизу" to listOf("LOWER_SIDE:R"),
            "болит низ живота слева" to listOf("LOWER_SIDE:L"),
            "болит живот внизу" to listOf("LOWER_BELLY:C", "LOWER_SIDE:R", "LOWER_SIDE:L"),
            "болит живот вверху справа" to listOf("UNDER_RIBS:R"),
            "болит живот вверху" to listOf("UPPER_BELLY:C", "UNDER_RIBS:R", "UNDER_RIBS:L"),
            "болит живот справа" to listOf("UNDER_RIBS:R", "LOWER_SIDE:R"),
            "болит живот посередине" to listOf("UPPER_BELLY:C", "LOWER_BELLY:C"),
            "pain in my lower right abdomen" to listOf("LOWER_SIDE:R"),
            "pain at the bottom right of my stomach" to listOf("LOWER_SIDE:R"),
            "болит спина справа" to listOf("SHOULDER_BLADE:R", "FLANK:R"),
            "болит поясница справа" to listOf("FLANK:R"),
            "болит верх спины слева" to listOf("SHOULDER_BLADE:L"),
            "болит спина внизу" to listOf("LOWER_BACK:C"),
            "болит бедро сбоку" to listOf("HIP:R", "HIP:L", "HIP:BOTH"),
            "болит колено сзади" to listOf("BACK_OF_KNEE:R", "BACK_OF_KNEE:L", "BACK_OF_KNEE:BOTH"),
        ).forEach { (text, places) -> assertEquals(text, places, chips(text)) }
        // The back of the knee is seen from behind; the outside of the thigh is the hip's.
        assertEquals(PainView.BACK, (pain("болит колено сзади").proposal as PainProposal.Chips).frame?.view)
        assertEquals("HIP", pain("болит бедро сбоку").family)
        // The fingers: by the limb beside them, else fingers and toes both.
        assertEquals(listOf("TOES:L", "TOES:R"), codes("болит палец на ноге"))
        assertEquals("FOOT", pain("болит палец на ноге").family)
        assertEquals(listOf("FINGERS:R", "FINGERS:L", "TOES:R", "TOES:L"), chips("болит палец"))
        // The side said with the limb is the finger's; «руки» with a side is one hand's genitive, not both arms.
        assertEquals(listOf("FINGERS:R"), codes("болит палец правой руки"))
        assertEquals(listOf("TOES:L"), codes("болит палец на левой ноге"))
        assertEquals(listOf("UPPER_ARM:R", "ELBOW:R", "FOREARM:R", "WRIST_HAND:R"), chips("перелом правой руки"))
        assertEquals(PainFrame(PainView.FRONT, "ARM"), map("болят руки"))
    }

    /** Head words bring the head's chips, the named first and none ticked; the chest, its own questions. */
    @Test
    fun theHeadAndTheChestHaveTheirOwnStarts() {
        val head = listOf("HEAD_FRONT:C", "TEMPLE:R", "TEMPLE:L", "HEAD_TOP:C", "HEAD_BACK:C", "JAW:R", "JAW:L")
        fun headChips(text: String) = (pain(text).proposal as PainProposal.Head).chips.map { it.code }
        assertEquals(head, headChips("headache"))
        assertEquals(listOf("TEMPLE:R", "TEMPLE:L") + (head - listOf("TEMPLE:R", "TEMPLE:L")), headChips("болят виски"))
        assertEquals(listOf("TEMPLE:L") + (head - "TEMPLE:L"), headChips("болит левый висок"))
        assertEquals(listOf("TEMPLE:R") + (head - "TEMPLE:R"), headChips("болит голова справа"))
        assertEquals(head, headChips("🤕"))
        val reply = pain("болят виски").proposal.reply(SymptomStep.PAIN_AREA) as ChatReply.BodyMap
        assertTrue(reply.head)
        assertEquals(PainFrame(PainView.FRONT, "HEAD"), reply.frame)
        // The jaw is a family of its own, chosen from the list: no "Show on the body".
        assertNull((pain("болит челюсть").proposal as PainProposal.Chips).frame)
        // The chest: the chest's questions first, then the map framed on it.
        assertEquals(PainProposal.Chest, pain("ребра болят").proposal)
        assertEquals(
            ChatReply.BodyMap(
                SymptomStep.PAIN_AREA,
                PainFrame(PainView.FRONT, "CHEST"),
                head = false,
                headChips = emptyList(),
            ),
            PainProposal.Chest.reply(SymptomStep.PAIN_AREA),
        )
        val chips = pain("болит колено").proposal.reply(SymptomStep.PAIN_AREA) as ChatReply.AreaChips
        assertEquals(PainFrame(PainView.FRONT, "KNEE"), chips.frame)
        assertEquals(3, chips.choices.size)
    }

    /**
     * On a build whose map has only the first map's areas, the same rows propose those: the back's two
     * chips, the abdomen, the ankle and foot.
     */
    @Test
    fun theFirstMapIsTheFallback() {
        val v1 = PainRegion.entries.filter { it.parent == null }.mapTo(HashSet()) { it.name }
        assertFalse(secondMapNamesIn(v1))
        fun firstMap(text: String): PainWordMention {
            val message = Message.of(text)
            val all = BodyLexicon.matches(message.words, message.raw, setting)
            return painMentionsIn(message.clauses(keptMatches(all)), setting, secondMap = false).single()
        }
        assertEquals(listOf("UPPER_BACK:C", "LOWER_BACK:C"), firstMap("болит спина").candidates.map { it.code })
        assertEquals(listOf("ABDOMEN:C"), firstMap("болит живот справа внизу").candidates.map { it.code })
        assertEquals(listOf("ANKLE_FOOT:L", "ANKLE_FOOT:R"), firstMap("болит пятка").candidates.map { it.code })
        assertEquals(listOf("CHEST:L", "CHEST:R"), firstMap("болит грудина").candidates.map { it.code })
        assertTrue(firstMap("болит копчик").proposal is PainProposal.Body)
    }

    /** Every place has a body family, by its name and then its root's; a mention's chip is named by its family. */
    @Test
    fun familiesAndTheirLabels() {
        PainRegion.entries.forEach { assertTrue(it.name, bodyFamilies(it).isNotEmpty()) }
        assertEquals(setOf("KNEE", "LEG"), bodyFamilies(PainRegion.BACK_OF_KNEE))
        assertEquals(setOf("BACK"), bodyFamilies(PainRegion.TAILBONE))
        assertEquals(setOf("CHEST"), bodyFamilies(PainRegion.STERNUM))
        // Every family a mention can name has a chip label; a pain named nowhere is the topic's own name.
        val families = AREAS.mapNotNullTo(HashSet()) { it.family } + "FOOT" + "HIP"
        families.forEach { family -> assertTrue(family, painMentionLabel(TopicMention(Symptom.PAIN, family)) != null) }
        assertEquals(
            ChatText(R.string.pain_chat_topic_area, listOf(PieceArg.Res(R.string.pain_region_knee))),
            PainScript.mentionLabel(TopicMention(Symptom.PAIN, "KNEE")),
        )
        assertNull(painMentionLabel(TopicMention(Symptom.PAIN)))
    }

    /** A pain thread serves every pain of the families it marked; a pain named nowhere, by any mark. */
    @Test
    fun aPainThreadServesItsFamilies() {
        fun thread(codes: String) = ChatThread(
            0,
            newChat().pick(Symptom.PAIN).valued(SymptomStep.PAIN_AREA, codes).sent(SymptomStep.PAIN_AREA),
        )
        val knee = thread("KNEE:R")
        assertTrue(PainScript.serves(knee, TopicMention(Symptom.PAIN, "KNEE")))
        assertFalse(PainScript.serves(knee, TopicMention(Symptom.PAIN, "BACK")))
        assertTrue(PainScript.serves(knee, TopicMention(Symptom.PAIN)))
        assertFalse(PainScript.serves(knee, TopicMention(Symptom.FEVER)))
        val behind = thread("BACK_OF_KNEE:L")
        assertTrue(PainScript.serves(behind, TopicMention(Symptom.PAIN, "KNEE")))
        assertTrue(PainScript.serves(behind, TopicMention(Symptom.PAIN, "LEG")))
        assertFalse(PainScript.serves(ChatThread(1, newChat().pick(Symptom.PAIN)), TopicMention(Symptom.PAIN, "KNEE")))
    }

    /**
     * A pain thread reads the message that named its pain, in its own words or an earlier thread's, and
     * proposes from it; a start whose words are gone — the chest's follow-up — the map framed there.
     */
    @Test
    fun aPainThreadReadsTheMessageThatNamedIt() {
        val words = ChatThread(0, driveChat(Symptom.FEVER).second.wrote("и болит правое колено, 6/10"))
        val pain = ChatThread(1, newChat().chose(Symptom.PAIN), from = "PAIN:KNEE")
        val log = ChatLog(threads = listOf(words, pain), next = 2)
        val state = log.threadState(1, setting)
        assertEquals(listOf("и болит правое колено, 6/10"), threadTexts(state))
        val mention = checkNotNull(threadPainMention(state))
        assertEquals(6, mention.nrs)
        assertEquals(
            PainProposal.Chips(
                listOf(AreaChoice(listOf(RegionSide(PainRegion.KNEE, BodySide.RIGHT)))),
                PainFrame(PainView.FRONT, "KNEE"),
            ),
            painProposalFor(state),
        )
        // Its own words come first, newest first: a side said later is the one proposed.
        val own =
            ChatThread(0, newChat().wrote("болит колено").wrote("левое колено").chose(Symptom.PAIN), from = "PAIN:KNEE")
        val ownState = ChatLog(threads = listOf(own), next = 1).threadState(0, setting)
        assertEquals(listOf("левое колено", "болит колено"), threadTexts(ownState))
        // The chest's follow-up after a chest thread: no pain mention left in the words, the map on the chest.
        val chest = ChatThread(0, driveChat(Symptom.CHEST_PAIN).second, from = ROUTE_WORDS)
        val follow = ChatThread(1, newChat().routedTo(Symptom.PAIN), from = ROUTE_FOLLOW + "PAIN:CHEST")
        val followState = ChatLog(threads = listOf(chest, follow), next = 2).threadState(1, setting)
        assertEquals(PainProposal.Chest, painProposalFor(followState))
        // The menu's [Pain] with no words: the whole body.
        val menu = ChatLog(
            threads = listOf(ChatThread(0, newChat().chose(Symptom.PAIN))),
            next = 1,
        ).threadState(0, setting)
        assertEquals(PainProposal.Body(PainFrame(PainView.FRONT, null)), painProposalFor(menu))
    }

    /** What a pain's words mark on each of the pain thread's questions: only the options offered, never an answer. */
    @Test
    fun painMarksFollowTheWords() {
        val knee = pain("опять сильно болит колено, ноет и колет, с утра, после тренировки, отекло")
        val all = SymptomOption.entries.toList()
        assertEquals(setOf(SymptomOption.PAIN_SAME_YES), painMarks(SymptomStep.PAIN_SAME, knee, all))
        assertEquals(
            setOf(SymptomOption.PAIN_ACHING, SymptomOption.PAIN_SHARP),
            painMarks(SymptomStep.PAIN_TYPES, knee, all),
        )
        assertEquals(setOf(SymptomOption.STARTED_TODAY), painMarks(SymptomStep.ONSET, knee, all))
        assertEquals(setOf(SymptomOption.NOTICED_AFTER_WORKOUT), painMarks(SymptomStep.PAIN_NOTICED, knee, all))
        val signs =
            painMarks(
                SymptomStep.PAIN_SIGNS,
                knee,
                listOf(SymptomOption.SWELL_BRUISE, SymptomOption.VERY_PAINFUL, SymptomOption.CRACK),
            )
        assertEquals(setOf(SymptomOption.SWELL_BRUISE, SymptomOption.VERY_PAINFUL), signs)
        assertTrue(painMarks(SymptomStep.PAIN_STATUS, knee, all).isEmpty())
        assertTrue(painMarks(SymptomStep.ONSET, null, all).isEmpty())
        // Sudden words mark the sudden sign; «обе ноги» both legs; a fever word the feverish ones.
        assertTrue(SymptomOption.SUDDEN_SEVERE in pain("внезапно заболела голова, как никогда").signs)
        val legs = readWords("болит спина, немеют обе ноги", setting).mentions.filterIsInstance<PainWordMention>()
        assertEquals(listOf("BACK", "LEG"), legs.map { it.family })
        assertTrue(SymptomOption.BOTH_LEGS in legs.last().signs)
        assertFalse(SymptomOption.BOTH_LEGS in legs.first().signs)
        assertTrue(SymptomOption.FEVERISH in pain("болит колено и температура").signs)
    }
}
