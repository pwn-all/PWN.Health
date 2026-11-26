package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeling unwell and nothing of what: every key and
 * form is read as such, every exclusion keeps it out, the keys it meets hold their own meanings, and
 * the router asks "physically or emotionally?" with the line to call for an emergency — never
 * answering for the person — then narrows the menu without a word of thanks, and the body's first
 * question does not say the sympathy twice.
 */
class ChatUnwellTest {
    private val setting = chatSetting()

    private fun read(text: String, with: ChatSetting = setting) = readWords(text, with)

    /** The log after each of [messages] was written and committed, as the page makes it. */
    private fun wrote(vararg messages: String, with: ChatSetting = setting): ChatLog =
        messages.fold(ChatLog(past = with.past)) { log, text -> log.wrote(text).settle(with) }

    private fun turn(vararg messages: String, with: ChatSetting = setting): BotTurn =
        chatTurn(wrote(*messages, with = with), with)

    private fun ChatLog.narrow(narrow: ChatNarrow, with: ChatSetting = setting): ChatLog =
        narrowed(narrow, with).settle(with)

    private fun ChatLog.choose(topic: Symptom, with: ChatSetting = setting): ChatLog =
        choseTopic(TopicChoice(topic), with).settle(with)

    private fun topics(reply: ChatReply): List<Symptom> = (reply as ChatReply.Topics).choices.map { it.topic }

    /** The core phrases, each read as feeling unwell, in Russian, in Latin letters and in English. */
    @Test
    fun everyKeyOfTheTableIsReadAsFeelingUnwell() {
        listOf(
            "я чувствую себя плохо", "чувствую себя плохо", "плохо себя чувствую", "Я себя плохо чувствую",
            "чувствую себя неважно", "неважно себя чувствую", "чувствую себя ужасно", "чувствую себя отвратительно",
            "чувствую себя паршиво", "чувствую себя хреново", "чувствую себя не очень", "чувствую себя так себе",
            "мне плохо", "Мне нехорошо", "мне хреново", "мне паршиво", "мне фигово", "мне дурно", "мне нездоровится",
            "что-то нездоровится", "самочувствие плохое", "самочувствие ужасное", "самочувствие так себе",
            "самочувствие неважное", "плохое самочувствие", "ужасное самочувствие", "я заболел", "я заболела",
            "кажется, приболела", "совсем расклеилась", "что-то мне не по себе", "mne ploho", "Mne ploxo",
            "ploho sebya chuvstvuyu", "chuvstvuyu sebya ploho", "мне не очень", "плохо мне",
            "I feel bad", "feeling bad today", "I feel unwell", "feeling unwell", "I'm unwell", "im unwell",
            "I'm not feeling well", "not feeling good", "not feeling great", "I don't feel well", "i dont feel well",
            "I do not feel well", "I don't feel good", "dont feel good", "I feel awful", "feeling awful",
            "I feel terrible", "feeling terrible", "I feel horrible", "I feel rough", "feeling rough today",
            "I feel off", "feeling off", "I feel lousy", "I feel like crap", "I feel crap", "I feel poorly",
            "feeling poorly", "I'm poorly", "I'm ill", "im ill", "I feel ill", "feeling ill", "I'm under the weather",
            "I think I'm coming down with something", "not myself today", "I feel like death warmed up",
        ).forEach { phrase -> assertTrue(phrase, read(phrase).unwell) }
    }

    /** One word between, of those that make it stronger — every one a key's form ("с усилителем"). */
    @Test
    fun aWordThatMakesItStrongerStandsBetween() {
        listOf(
            "мне очень плохо", "мне так плохо", "мне совсем плохо", "мне что-то плохо", "мне сегодня плохо",
            "мне реально плохо", "чувствую себя очень плохо", "очень плохо себя чувствую", "мне очень нехорошо",
            "мне прям плохо", "mne ochen ploho", "I feel so bad", "I feel really bad", "I feel very bad",
            "I'm feeling so bad", "I'm feeling really bad", "I feel pretty bad", "I feel really unwell",
            "I feel very unwell", "I'm feeling very unwell", "I feel really ill", "I feel so ill",
            "I'm not feeling very well", "I'm not feeling too well", "I don't feel so good", "I feel a bit off",
            // How it is said most often in Russian, beyond the core phrases.
            "мне стало плохо", "мне вдруг стало плохо", "мне становится плохо", "мне всё ещё плохо",
            "мне по-прежнему плохо", "мне до сих пор плохо", "мне стало нехорошо", "мне снова стало плохо",
        ).forEach { phrase -> assertTrue(phrase, read(phrase).unwell) }
        assertEquals(listOf(ChatLine.UNWELL_ASK, ChatLine.SAFETY), turn("мне вдруг стало плохо").lines())
        // The exclusions take the same words between: «мне стало плохо видно» is a sight.
        assertFalse(read("мне стало плохо видно").unwell)
    }

    /** A guilt, a sight, a sleep, a subject hard to learn — and with the same word between too: no unwell. */
    @Test
    fun theExclusionsKeepItOut() {
        listOf(
            "I feel bad about missing my run", "I feel bad for her", "I feel bad that I skipped it",
            "feeling bad about it", "I feel terrible about yesterday", "I feel awful for him",
            "I feel horrible about it", "I feel so bad about it", "I feel really bad for you",
            "I'm feeling really bad about this", "I felt bad about it", "not feeling good about the exam",
            "мне плохо видно", "мне плохо слышно", "мне плохо спится", "мне плохо даётся математика",
            "мне плохо понятно", "я плохо себя вела", "мне не очень нравится", "мне не очень понятно",
            "мне не очень хочется", "мне очень плохо видно", "плохо мне спится",
        ).forEach { phrase -> assertFalse(phrase, read(phrase).unwell) }
        // Feeling no such thing, and the past, which the keys do not read.
        listOf(
            "I don't feel bad",
            "I do not feel bad",
            "I'm not feeling bad",
            "I don't feel so bad",
            "I felt bad yesterday but now I am fine",
            "I felt unwell last week",
            "I felt awful about it",
        ).forEach { phrase -> assertFalse(phrase, read(phrase).unwell) }
        // British "I'm sick" is no key: it is often being sick, which a model will tell apart.
        assertFalse(read("I'm sick").unwell)
        assertFalse(read("плохо").unwell)
        assertFalse(read("bad").unwell)
    }

    /**
     * The longest key holds its words: «самочувствие так себе» is no so-so mood, «я заболела» no weak
     * word of a pain, "not feeling good" no good mood; and the keys it meets keep their own meanings.
     */
    @Test
    fun theKeysItMeetsKeepTheirOwnMeanings() {
        assertTrue(read("самочувствие так себе").mentions.none { it.mention.topic == Symptom.MOOD })
        assertTrue(read("чувствую себя так себе").mentions.isEmpty())
        assertTrue(read("я заболела").mentions.isEmpty())
        assertTrue(read("not feeling good").mentions.isEmpty())
        // What stays itself.
        assertEquals(listOf("MOOD"), read("так себе").mentions.map { it.mention.key })
        assertEquals(listOf("SLEEP_WORRY"), read("плохо сплю").mentions.map { it.mention.key })
        assertEquals(listOf("MOOD", "LOW_MOOD"), read("на душе плохо").mentions.map { it.mention.key })
        assertEquals(listOf("MOOD", "LOW_MOOD"), read("мне на душе плохо").mentions.map { it.mention.key })
        assertFalse(read("мне на душе плохо").unwell)
        assertEquals(listOf("NAUSEA"), read("I feel sick").mentions.map { it.mention.key })
        assertFalse(read("I feel sick").unwell)
        assertEquals(listOf("MOOD"), read("I feel good").mentions.map { it.mention.key })
        assertEquals(listOf("DIZZINESS"), read("feeling off balance").mentions.map { it.mention.key })
        assertFalse(read("feeling off balance").unwell)
        // A word of feeling unwell is a word read: the line to call is said in its reply on purpose.
        assertTrue(read("мне плохо").language)
    }

    /** Every form of every key and exclusion is lower case, «е» for «ё», words split by one space. */
    @Test
    fun everyKeyIsNormalised() {
        val keys = unwellKeyTexts()
        assertTrue(keys.size > UNWELL_KEYS.size + UNWELL_ENGLISH_KEYS.size + UNWELL_NOT_KEYS.size)
        keys.forEach { key -> assertEquals(key, key, normalisedWords(key).trim()) }
    }

    /**
     * The unwell question: "physically or emotionally?", the line to call for an emergency as
     * these words are read, three chips and "Show all topics" — no topic, nothing answered for the person.
     */
    @Test
    fun theUnwellQuestionWithTheLineToCallAndThreeChips() {
        listOf("я чувствую себя плохо", "мне плохо", "мне нехорошо", "i feel bad", "I feel unwell")
            .plus("I'm not feeling well")
            .forEach { phrase ->
                val turn = turn(phrase)
                assertEquals(phrase, listOf(ChatLine.UNWELL_ASK, ChatLine.SAFETY), turn.lines())
                val reply = turn.reply as ChatReply.Topics
                assertEquals(phrase, ChatNarrow.entries, reply.narrow)
                assertTrue(phrase, reply.choices.isEmpty())
                // "Show all topics" stands under chips that are not the whole menu and have no "Another topic".
                assertFalse(phrase, reply.all)
                assertFalse(phrase, reply.exit)
                assertNull(phrase, wrote(phrase).threads.single().topic)
            }
        assertEquals(R.string.symptom_chat_unwell_ask, ChatLine.UNWELL_ASK.textRes)
        assertEquals(
            listOf(
                R.string.symptom_chat_unwell_body,
                R.string.symptom_chat_unwell_mind,
                R.string.symptom_chat_unwell_both,
            ),
            ChatNarrow.entries.map { it.labelRes },
        )
    }

    /**
     * Words about someone else are no "I am sorry you are not feeling well" — whose
     * journal this is and to call for them, the line to call and the menu, as any message about someone else.
     */
    @Test
    fun someoneElseUnwellIsMetAsBefore() {
        listOf("my husband is not feeling well", "my son is feeling unwell", "мне плохо, у мужа тоже").forEach {
            assertFalse(it, read(it).unwell)
            val turn = turn(it)
            assertFalse(it, ChatLine.UNWELL_ASK in turn.lines())
            assertTrue(it, ChatLine.SOMEONE_ELSE in turn.lines())
            assertTrue(it, ChatLine.SAFETY in turn.lines())
            assertTrue(it, (turn.reply as ChatReply.Topics).narrow.isEmpty())
        }
    }

    /**
     * Unwell with the heart — «мне плохо с сердцем», "something is wrong with my heart"
     * — asks about the chest first; beside the chest or the heart named alone it is no "physically or
     * emotionally?" either, but the line to call and the menu.
     */
    @Test
    fun unwellWithTheHeartAsksAboutTheChest() {
        listOf("мне плохо с сердцем", "плохо с сердцем", "I feel awful, something is wrong with my heart").forEach {
            assertEquals(it, SymptomStep.CHEST_NOW, turn(it).asked())
        }
        listOf("мне плохо, сердце", "I feel unwell, my heart").forEach {
            assertFalse(it, read(it).unwell)
            val turn = turn(it)
            assertFalse(it, ChatLine.UNWELL_ASK in turn.lines())
            assertTrue(it, ChatLine.SAFETY in turn.lines())
        }
    }

    /** With a question, the line that it is no doctor; with a topic, the topic's reply; the second time, plainly. */
    @Test
    fun theRowsOfTheRoutersTable() {
        val question = turn("мне плохо, что делать?")
        assertEquals(listOf(ChatLine.QUESTION, ChatLine.SAFETY), question.lines())
        assertEquals(ChatNarrow.entries, (question.reply as ChatReply.Topics).narrow)
        // With a topic named, the topic's own reply; the unwell reading is unused.
        val head = turn("мне плохо, болит голова")
        assertFalse(ChatLine.UNWELL_ASK in head.lines())
        assertEquals(SymptomStep.PAIN_AREA, head.asked())
        val sick = turn("мне плохо, тошнит")
        assertEquals(listOf(ChatLine.MATCH), sick.lines())
        assertEquals(listOf(Symptom.NAUSEA), topics(sick.reply))
        // The second message in a row of nothing else: a chip may be easier, the same three chips.
        val again = turn("мне плохо", "мне очень плохо")
        assertEquals(listOf(ChatLine.NO_MATCH_AGAIN, ChatLine.SAFETY), again.lines())
        assertEquals(ChatNarrow.entries, (again.reply as ChatReply.Topics).narrow)
        // Words of an emergency still come first, and the unwell question waits behind them.
        assertEquals(SymptomStep.BREATH_NOW, turn("мне плохо, задыхаюсь").asked())
        assertEquals(SymptomStep.CRISIS_NOW, turn("мне плохо, не хочу жить").asked())
    }

    /** A tap narrows the menu: no thanks, nothing answered or filed; the body's topics, a pain, something else. */
    @Test
    fun physicallyNarrowsTheMenuToTheBody() {
        val log = wrote("мне плохо").narrow(ChatNarrow.BODY)
        val thread = log.threads.single()
        assertEquals(ChatTurn.Narrowed(ChatNarrow.BODY), thread.report.chat?.turns?.last())
        assertNull(thread.topic)
        assertTrue(thread.report.answers.isEmpty())
        assertTrue(SymptomScript.wants(thread, filingContext(log, chatKey())).isEmpty())
        val turn = chatTurn(log, setting)
        assertEquals(listOf(ChatLine.UNWELL_PICK), turn.lines())
        assertFalse(ChatLine.MATCH in turn.lines())
        val reply = turn.reply as ChatReply.Topics
        assertTrue(reply.narrow.isEmpty())
        assertFalse(reply.all || reply.exit)
        val body = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_body }.second
        assertEquals(body + Symptom.PAIN + Symptom.OTHER, topics(reply))
        assertEquals(11, body.size)
    }

    /** "Emotionally": the feelings and the mood; "Both": the body, the feelings, a pain, the mood, something else. */
    @Test
    fun emotionallyAndBothNarrowTheirWay() {
        val mind = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_mind }.second
        val body = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_body }.second
        assertEquals(mind + Symptom.MOOD, topics(chatTurn(wrote("мне плохо").narrow(ChatNarrow.MIND), setting).reply))
        assertEquals(
            body + mind + listOf(Symptom.PAIN, Symptom.MOOD, Symptom.OTHER),
            topics(chatTurn(wrote("I feel unwell").narrow(ChatNarrow.BOTH), setting).reply),
        )
    }

    @Test
    fun writtenAnswersUseTheUnwellQuestionInsteadOfRepeatingIt() {
        listOf("мне плохо", "привет.мне плохо?").forEach { opening ->
            mapOf(
                "на душе" to ChatNarrow.MIND,
                "Эмоционально." to ChatNarrow.MIND,
                "физически" to ChatNarrow.BODY,
                "и то и другое" to ChatNarrow.BOTH,
            ).forEach { (answer, narrow) ->
                val before = wrote(opening)
                val log = before.wrote(answer).settle(setting)
                val reply = chatTurn(log, setting)
                assertEquals("$opening / $answer", listOf(ChatLine.UNWELL_PICK), reply.lines())
                assertEquals(topics(chatTurn(before.narrow(narrow), setting).reply), topics(reply.reply))
                assertNull(log.threads.single().topic)
                assertEquals(answer, (log.threads.single().report.chat?.turns?.last() as ChatTurn.Wrote).text)
                assertTrue(log.threads.single().report.answers.isEmpty())
                assertEquals(log, decodeChatLog(encodeChatLog(log, pending = false)))
            }
        }
    }

    @Test
    fun writtenNarrowingIsBoundedToTheQuestionAndKeepsUrgentWordsFirst() {
        listOf("на душе", "эмоционально", "physically", "both").forEach { answer ->
            assertFalse(answer, ChatLine.UNWELL_PICK in turn(answer).lines())
        }
        listOf("не на душе", "у мужа на душе", "на душе, но болит голова").forEach { answer ->
            assertFalse(answer, ChatLine.UNWELL_PICK in turn("мне плохо", answer).lines())
        }
        assertEquals(SymptomStep.CRISIS_NOW, turn("мне плохо", "на душе, не хочу жить").asked())
        assertEquals(SymptomStep.BREATH_NOW, turn("мне плохо", "на душе и задыхаюсь").asked())
        val chosen = wrote("мне плохо").narrow(ChatNarrow.BODY).wrote("на душе").settle(setting)
        assertFalse(ChatLine.UNWELL_PICK in chatTurn(chosen, setting).lines())
        val abstained = wrote("мне плохо").sentRead("на душе", chatRead { copy(why = ChatReadOutcome.ABSTAIN) })
        assertEquals(listOf(ChatLine.UNWELL_PICK), chatTurn(abstained, setting).lines())
        val urgent = wrote("мне плохо").sentRead("на душе", chatRead { copy(safety = listOf(SymptomStep.CRISIS_NOW)) })
        assertEquals(SymptomStep.CRISIS_NOW, chatTurn(urgent, setting).asked())
    }

    /** For an earlier day, and after an answer about suicide in the chat, the feelings come without the mood. */
    @Test
    fun theMoodIsLeftOutWhereTheMenuLeavesItOut() {
        val past = chatSetting(past = true)
        val mind = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_mind }.second
        assertEquals(mind, topics(chatTurn(wrote("мне плохо", with = past).narrow(ChatNarrow.MIND, past), past).reply))
        // An answer about suicide other than "no" in an earlier thread: the mood is not offered after it.
        val crisis = wrote("не хочу жить").answeredLast(setting) {
            it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW, null)
        }.settle()
        assertTrue(crisis.crisisInChat())
        assertTrue(crisis.threads.single().ended)
        val after = crisis.wrote("мне плохо").settle().narrow(ChatNarrow.MIND)
        assertEquals(mind, topics(chatTurn(after, setting).reply))
    }

    /** Answered "physically", then nausea — the body's first question does not say the sympathy again. */
    @Test
    fun theBodysFirstQuestionSaysTheSympathyOnce() {
        val nausea = wrote("я чувствую себя плохо").narrow(ChatNarrow.BODY).choose(Symptom.NAUSEA)
        val first = chatTurn(nausea, setting)
        assertEquals(ChatLine.START_QUESTIONS, first.lines().first())
        assertFalse(ChatLine.START_BODY in first.lines())
        // Straight from the menu under the unwell question, "Show all topics" unfolded: the same.
        val menu = chatTurn(wrote("мне плохо").choose(Symptom.FEVER), setting)
        assertEquals(ChatLine.START_QUESTIONS, menu.lines().first())
        // Words that named the topic beside feeling unwell were never asked the question: the sympathy stands.
        val named = chatTurn(wrote("мне плохо, тошнит").choose(Symptom.NAUSEA), setting)
        assertEquals(ChatLine.START_BODY, named.lines().first())
        // And with no words of feeling unwell at all: the sympathy stands too.
        assertEquals(ChatLine.START_BODY, chatTurn(ChatLog().choose(Symptom.NAUSEA), setting).lines().first())
        // A feeling's start has no sympathy to repeat.
        val worry = chatTurn(wrote("мне плохо").narrow(ChatNarrow.MIND).choose(Symptom.WORRY), setting)
        assertEquals(ChatLine.START_MIND, worry.lines().first())
        assertEquals(R.string.symptom_chat_start_questions, ChatLine.START_QUESTIONS.textRes)
        // Under the line that the chat is no doctor no sympathy was said, so a tap on
        // "Physically" is no sign it was: the body's first question says it.
        val asked = chatTurn(wrote("мне плохо, что делать?").narrow(ChatNarrow.BODY).choose(Symptom.NAUSEA), setting)
        assertEquals(ChatLine.START_BODY, asked.lines().first())
        assertFalse(ChatLine.START_QUESTIONS in asked.lines())
        // Said once and again «Если так проще…»: the first message said it.
        val twice = wrote("мне плохо", "мне очень плохо").narrow(ChatNarrow.BODY).choose(Symptom.FEVER)
        assertEquals(ChatLine.START_QUESTIONS, chatTurn(twice, setting).lines().first())
    }

    /** A chip of a turn gone writes nothing: once the topic is taken, or where no unwell question was asked. */
    @Test
    fun aNarrowingChipOnlyWhileItIsOffered() {
        val chosen = wrote("мне плохо").choose(Symptom.NAUSEA)
        assertEquals(chosen, chosen.narrowed(ChatNarrow.BODY, setting))
        val knee = wrote("болит колено")
        assertEquals(knee, knee.narrowed(ChatNarrow.MIND, setting))
        assertEquals(ChatLog(), ChatLog().narrowed(ChatNarrow.BODY, setting))
    }

    /** The transcript draws the tap as the person's bubble, the unwell question before it, the narrowed menu after. */
    @Test
    fun theTranscriptDrawsTheTap() {
        val log = wrote("мне плохо").narrow(ChatNarrow.BOTH).choose(Symptom.FEVER)
        val items = chatTranscript(log, setting, live = true)
        val tap = items.filterIsInstance<ChatItem.Person>().single { it.narrowed != null }
        assertEquals(ChatNarrow.BOTH, tap.narrowed)
        assertNull(tap.step)
        assertNull(tap.text)
        val at = items.indexOf(tap)
        assertEquals(ChatLine.UNWELL_ASK, (items[at - 1] as ChatItem.Bot).turn.lines().first())
        assertEquals(listOf(ChatLine.UNWELL_PICK), (items[at + 1] as ChatItem.Bot).turn.lines())
        assertEquals(SymptomStep.SYMPTOM, (items[at + 2] as ChatItem.Person).step)
    }

    /** The tap is kept in the payload and read back; a narrowing this build does not know is dropped by itself. */
    @Test
    fun theTapComesBackFromThePayload() {
        val report = wrote("мне плохо").narrow(ChatNarrow.MIND).threads.single().report
        val payload = encodeSymptomPayload(report)
        assertTrue(payload, "{\"narrow\":\"MIND\"}" in payload)
        assertEquals(report.chat, decodeSymptomPayload(payload).chat)
        val log = wrote("I feel bad").narrow(ChatNarrow.BODY)
        assertEquals(log, decodeChatLog(encodeChatLog(log, pending = false)))
        val later = decodeSymptomPayload("""{"schema":2,"chat":{"turns":[{"text":"x"},{"narrow":"LATER"}]}}""")
        assertEquals(listOf(ChatTurn.Wrote("x")), later.chat?.turns)
    }
}
