package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.time.LocalDate

/**
 * The words engine: what a message's words propose, and what they never do. Every phrase
 * people type is pinned one by one, as `SymptomWordsTest` pins the topics', because a word read
 * wrongly is a chip offered wrongly, and one not read is a question not brought forward.
 */
class ChatWordsTest {
    private val setting = chatSetting()

    private fun read(text: String, with: ChatSetting = setting) = readWords(text, with)

    private fun pains(text: String, with: ChatSetting = setting) =
        read(text, with).mentions.filterIsInstance<PainWordMention>()

    private fun pain(text: String, with: ChatSetting = setting) = pains(text, with).single()

    private fun families(text: String) = pains(text).map { it.family }

    private fun codes(mention: PainWordMention) = mention.candidates.map { it.code }

    private fun keys(text: String) = read(text).mentions.map { it.mention.key }

    /** Every key of every table is lower case, «е» for «ё», letters and digits split by one space, as words are. */
    @Test
    fun everyKeyIsNormalised() {
        val keys = chatWordKeyTexts() + PAIN_WORD_KEYS.map { it.removeSuffix("*") } +
            TOPIC_KEYS.values.flatten().map { it.trim() } + CRISIS_KEYS.map { it.trim() } +
            emergencyKeyTexts().map { it.trim() }
        assertTrue(keys.size > 1_000)
        keys.forEach { key -> assertEquals(key, key, normalisedWords(key).trim()) }
        // The notation's mark is only ever a word's end: "кол*ено" would be a word that never matches.
        AREAS.flatMap { it.keys + it.not }.forEach { key ->
            assertTrue(key, key.split(' ').all { word -> '*' !in word.dropLast(1) })
        }
    }

    /**
     * The words that are not areas: each kept out, so no place is proposed
     * for it; and the two errors kept on purpose — the chest's question for an idiom about the heart,
     * the crisis question for a headache that "will kill me".
     */
    @Test
    fun wordsThatAreNotAreas() {
        mapOf(
            "болит и голова кругом" to "HEAD",
            "ломаю голову, болит" to "HEAD",
            "болит животное" to "BELLY",
            "болит за спиной" to "BACK",
            "сломал спиннинг" to "BACK",
            "болит спинка стула" to "BACK",
            "болит, пахнет" to "HIP",
            "болит красная икра" to "LEG",
            "болит киста" to "HAND",
            "болит, стоп" to "FOOT",
            "попал в больницу, болит" to "BACK",
            "сломал коленвал" to "KNEE",
            "болит шейка матки" to "NECK",
            "кормлю грудью, болит" to "CHEST",
            "руки опускаются, всё болит" to "ARM",
            "the pain came back" to "BACK",
            "on the other hand it hurts" to "HAND",
            "the side effects hurt" to "BELLY",
            "I fell 6 feet and it hurts" to "FOOT",
            "break a leg, it hurts" to "LEG",
            "болит, позвоню врачу" to "BACK",
            "болит, позвонишь врачу?" to "BACK",
            "my knee pain is back" to "BACK",
            "headache's back again" to "BACK",
            "выпил виски, болит голова" to "TEMPLE",
        ).forEach { (text, kept) ->
            val named = pains(text).flatMap { mention ->
                listOfNotNull(mention.family) +
                    mention.candidates.map { it.region.name }
            }
            assertFalse("$text: $named", kept in named)
        }
        // Words of the throat, an ear or a tooth are "something else", never a place: no pain mention at all.
        listOf("болит горло", "my tooth hurts", "болит ухо", "itchy skin hurts").forEach { text ->
            assertEquals(text, listOf("OTHER"), keys(text))
        }
        // «ноги не держат» is tiredness, and no leg.
        assertEquals(listOf("TIREDNESS"), keys("ноги не держат"))
        assertTrue(pains("my knee hurts after football").all { it.family == "KNEE" })
        // A pain that is back is no back; back pain, said so, still is — "is back pain" too.
        assertEquals(listOf("KNEE"), families("my knee pain is back"))
        assertEquals(listOf("BACK"), families("I think it's back pain"))
        assertEquals(listOf("LOWER_BACK:C"), pain("low back pain").candidates.map { it.code })
        // Kept on purpose: an idiom about the heart asks about the chest, a figure of speech about suicide.
        assertTrue(EmergencyWords.CHEST in read("сердце болит за сына").emergencies)
        assertTrue(EmergencyWords.CHEST in read("heartburn pain").emergencies)
        assertTrue(read("this headache will kill me").crisis)
        assertEquals("HEAD", pain("this headache will kill me").family)
        // The breastfeeding words keep the chest area out, never the chest's question.
        assertTrue(EmergencyWords.CHEST in read("кормлю грудью, болит").emergencies)
    }

    /**
     * An area word is a pain only beside a word of pain, injury or kind, in its own clause or in a list
     * without verbs that one began — forward only; a full stop ends a list.
     */
    @Test
    fun clausesAndLicensing() {
        listOf("колено", "my knee", "правое плечо", "спина, колено").forEach { assertTrue(it, pains(it).isEmpty()) }
        assertEquals(listOf("BACK", "KNEE", "HEAD"), families("болит спина, колено, голова к вечеру"))
        // Cut by a word of contrast, the list goes on: negation is not read.
        assertEquals(listOf("BACK", "KNEE"), families("болит спина, а колено нет"))
        // Forward only: the whisky before the pain is no temple, the knee before it no pain.
        assertEquals(listOf("HEAD"), families("выпил виски, болит голова"))
        assertEquals(listOf("BACK"), families("колено, болит спина"))
        assertEquals(listOf("BACK"), families("Болит спина. Колено."))
        // A head word that is a pain in itself; «заболела» names a pain only beside an area word.
        assertEquals(listOf("HEAD"), families("headache"))
        assertEquals(listOf("HEAD"), families("мигрень"))
        assertTrue(pains("я заболела").isEmpty())
        assertEquals(listOf("HEAD"), families("заболела голова"))
        // An injury is enough; «судорога» only for a leg or a foot.
        assertEquals(listOf("KNEE"), families("у меня ушиб колена"))
        assertEquals(listOf("FOOT"), families("I twisted my ankle"))
        assertEquals(listOf("LEG"), families("судорога в ноге"))
        assertTrue(pains("судорога в руке").isEmpty())
        // A pain said with no area: the whole body, one mention; and none for "something else".
        assertEquals(listOf("PAIN"), keys("всё болит"))
        assertEquals(PainProposal.Body(PainFrame(PainView.FRONT, null)), pain("I am in pain").proposal)
        // One pain per family a message names, in the order they first appear; two of one family are one.
        assertEquals(listOf("PAIN:KNEE", "PAIN:BACK"), keys("болит колено и спина"))
        assertEquals(listOf("PAIN:KNEE"), keys("болит колено, очень болит колено"))
    }

    /**
     * A lexicon's phrase stands within one clause. The keys are matched against the words alone, so a
     * comma, a full stop or the end of a message between two words of a phrase would leave its match
     * ending outside the clause it began in, and reading «ломит, всё тело болит» would stop the chat.
     * Parted, a phrase names nothing, and its words are read as their clauses have them.
     */
    @Test
    fun aPhraseACutPartsIsNoKey() {
        // No period, no side, no jaw, no whole body ached: a pain said with no place beside it.
        listOf(
            "I'm on my period, pain is bad",
            "I'm on my period\npain is bad",
            "Pain in my right side. Hurts when breathing",
            "Не могу жевать, больно глотать",
            "Ломит, всё тело болит",
            "всё тело ломит\nвсё болит",
        ).forEach { assertNull(it, pain(it).family) }
        assertEquals(listOf("PAIN:KNEE"), keys("I'm on my period. Pain in my knee"))
        assertEquals("BELLY", pain("period pain is bad").family)
        assertEquals("BELLY", pain("my side hurts").family)
        // «Нурофен, плюс …» is ibuprofen, never the codeine of «Нурофен Плюс» and its questions.
        fun medicines(text: String) =
            read(text).mentions.filterIsInstance<MedicineWordMention>().map { it.medicine?.id }
        assertEquals(listOf("DOSE:нурофен", "OTHER"), keys("Выпил нурофен, плюс болит горло"))
        assertEquals(listOf("ibuprofen"), medicines("Выпил нурофен, плюс болит горло"))
        assertEquals(listOf("ibuprofen+codeine"), medicines("выпил нурофен плюс"))
        // The full stop of "St." cuts as any other, and a course of the person's own is cut before a word of
        // contrast in its name: both are read, the course by its first word.
        assertTrue(read("I take St. John's wort").language)
        val own = chatSetting(env = ChatEnv(courses = listOf(course("Витамин А"))))
        assertEquals(listOf("DOSE:витамин"), read("выпил витамин а", own).mentions.map { it.mention.key })
        // The script's own words are read across a cut all the same, and keep their words from a lexicon's.
        assertTrue(read("мне... плохо").unwell)
        assertTrue(read("сделала себе... больно").crisis)
        assertTrue(pains("сделала себе... больно").isEmpty())
    }

    /** What parts a phrase and what does not; and a match placed across a cut ends with its clause. */
    @Test
    fun whatPartsAPhrase() {
        fun whole(text: String) = Message.of(text).let { it.inOneClause(1 until it.words.length - 1) }
        listOf(
            "нурофен, плюс",
            "нурофен. Плюс",
            "нурофен\nплюс",
            "нурофен? Плюс",
            "нурофен — плюс",
            "нурофен - плюс",
            "нурофен но плюс",
        ).forEach { assertFalse(it, whole(it)) }
        listOf("нурофен плюс", "нурофен-плюс", "St John's wort", "2,5 мг").forEach { assertTrue(it, whole(it)) }
        val message = Message.of("хочу, умереть")
        val across = WordMatch(1 until message.words.length - 1, "any")
        assertEquals(listOf(0), message.clauses(listOf(across)).flatMap { clause -> clause.kept.map { it.to } })
    }

    /**
     * Every phrase of the lexicons is one clause as it is written — no word of contrast inside it, whose
     * cut would part it wherever it stands — and, parted at each of its gaps by each cut, is read all the same.
     */
    @Test
    fun everyPhrasePartedIsRead() {
        val phrases = (bodyKeyTexts() + moodKeyTexts() + medicineKeyTexts()).map { it.replace("*", "").trim() }
            .distinct().filter { ' ' in it }
        assertTrue(phrases.size > 500)
        phrases.forEach { phrase ->
            assertEquals(phrase, 1, Message.of(phrase).clauses(emptyList()).size)
            val words = phrase.split(' ')
            (1 until words.size).forEach { gap ->
                listOf(", ", ". ", "\n", "? ", " — ").forEach { cut ->
                    val parted = words.take(gap).joinToString(" ") + cut + words.drop(gap).joinToString(" ")
                    assertTrue(parted, read("болит $parted hurts").language)
                }
            }
        }
    }

    /** A side binds to the nearest area word in its clause, three words off at most; "right" only right before it. */
    @Test
    fun sidesBindToTheNearestArea() {
        val right = AreaChoice(listOf(RegionSide(PainRegion.KNEE, BodySide.RIGHT)))
        val left = AreaChoice(listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT)))
        val both =
            AreaChoice(
                listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT), RegionSide(PainRegion.KNEE, BodySide.RIGHT)),
                both = true,
            )
        fun chips(text: String) = (pains(text).first { it.family == "KNEE" }.proposal as PainProposal.Chips).choices
        assertEquals(listOf(right), chips("болит правое колено"))
        assertEquals(listOf(right), chips("болит справа колено"))
        assertEquals(listOf(right), chips("справа у меня колено болит"))
        assertEquals(listOf(right), chips("my right knee hurts"))
        assertEquals(listOf(left), chips("my left knee is sore"))
        // No side named: right, left, both; both named or a plural: "both" first.
        assertEquals(listOf(right, left, both), chips("болит колено"))
        assertEquals(listOf(both, right, left), chips("болят колени"))
        assertEquals(listOf(both, right, left), chips("both knees hurt"))
        assertEquals(listOf(both, right, left), chips("болит правое и левое колено"))
        // Further than three words, "right now", slang for fake: no side.
        assertEquals(listOf(right, left, both), chips("справа у меня очень сильно болит колено"))
        assertEquals(listOf(right, left, both), chips("right now my knee hurts"))
        assertEquals(listOf(right, left, both), chips("my knee hurts right here"))
        assertEquals(listOf(right, left, both), chips("левые таблетки, болит колено"))
        assertEquals(listOf(right, left, both), chips("правда болит колено"))
        // The side goes to the nearest area word only: the shoulder after it is on both sides.
        val knee = pains("правое колено и плечо болят").first { it.family == "KNEE" }
        val shoulder = pains("правое колено и плечо болят").first { it.family == "SHOULDER" }
        assertEquals(listOf("KNEE:R"), codes(knee))
        assertEquals(listOf("SHOULDER:L", "SHOULDER:R"), codes(shoulder))
    }

    /**
     * Words only mark and rest: a number rests the ruler, a time or a kind marks a chip — and none of
     * it is an answer. A symptom thread's "when did it start?" carries the words' mark, unticked, and
     * the question still waits.
     */
    @Test
    fun marksAndRestsNeverSelect() {
        val knee = pain("болит правое колено после бега, 6 из 10")
        assertEquals(6, knee.nrs)
        assertEquals(SymptomOption.NOTICED_AFTER_WORKOUT, knee.noticed)
        val noticed = listOf(SymptomOption.NOTICED_DURING_WORKOUT, SymptomOption.NOTICED_AFTER_WORKOUT)
        assertEquals(setOf(SymptomOption.NOTICED_AFTER_WORKOUT), painMarks(SymptomStep.PAIN_NOTICED, knee, noticed))
        // A mark is only ever an offered option, and nothing marks a question about suicide.
        assertTrue(painMarks(SymptomStep.PAIN_NOTICED, knee, listOf(SymptomOption.NOTICED_ON_WAKING)).isEmpty())
        assertTrue(painMarks(SymptomStep.CRISIS_NOW, knee, SymptomOption.entries).isEmpty())
        // The ruler rests on a number out of ten, and only on one.
        listOf(
            "6/10 колено болит",
            "колено болит, шесть из десяти",
            "6 out of 10 knee pain",
            "колено болит на 6 баллов",
            "колено болит, 6/10.",
        )
            .forEach { assertEquals(it, 6, pain(it).nrs) }
        listOf(
            "0/10 колено болит", "11/10 колено болит", "6,5/10 колено болит", "6-7/10 колено болит",
            "6/10/2026 колено болит", "с 8/10 колено болит", "140/90 колено болит", "6/100 колено болит",
            "6/10, вчера было 8/10, колено болит",
            "колено болит 6 дней",
        ).forEach { assertNull(it, pain(it).nrs) }
        // An adjective is never a number: it marks a severe sign, which mild words take away.
        val severe = pain("очень сильно болит колено")
        assertNull(severe.nrs)
        assertTrue(SymptomOption.VERY_PAINFUL in severe.signs && SymptomOption.SUDDEN_OR_SEVERE in severe.signs)
        assertTrue(pain("болит колено, но не сильно").signs.isEmpty())
        // Severe and mild words in different clauses: told as both, so the severe sign stays unmarked.
        val both = pain("очень сильно болело колено, а сейчас немного")
        assertTrue(both.severe && both.mild)
        assertFalse(SymptomOption.VERY_PAINFUL in both.signs)
        // In a fever thread asked "when did it start?", a message names the time: the chip is marked, nothing answered.
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
            .answeredLast(setting) {
                it.copy(
                    answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE)),
                ).answered(SymptomStep.WITH)
            }
            .settle().wrote("shivery since yesterday").settle()
        val turn = chatTurn(fever, setting)
        assertEquals(SymptomStep.ONSET, turn.asked())
        val reply = turn.reply as ChatReply.Choose
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), reply.marked)
        assertNull(fever.threads.single().report.answers[SymptomStep.ONSET])
        assertEquals(SymptomStep.ONSET, (chatMove(fever, setting) as ChatMove.Ask).step)
    }

    /** Negation is not read, except three marks: still there, gone, not severe. */
    @Test
    fun negationIsNotReadButTheThreeMarks() {
        assertTrue(EmergencyWords.CHEST in read("no chest pain").emergencies)
        val gone = pain("колено больше не болит")
        assertEquals("KNEE", gone.family)
        assertEquals(false, gone.still)
        val statuses = listOf(SymptomOption.STILL_HURTS, SymptomOption.GONE)
        assertEquals(setOf(SymptomOption.GONE), painMarks(SymptomStep.PAIN_STATUS, gone, statuses))
        assertEquals(true, pain("до сих пор болит колено").still)
        assertEquals(
            setOf(SymptomOption.STILL_HURTS),
            painMarks(SymptomStep.PAIN_STATUS, pain("до сих пор болит колено"), statuses),
        )
        assertEquals(true, pain("колено не прошло, болит").still)
        // Both said, nothing marked; «с прошлой недели» is a time, not "gone".
        assertNull(pain("колено до сих пор болит, хотя вчера прошло").still)
        assertNull(pain("колено болит с прошлой недели").still)
        assertTrue(pain("болит колено, но не сильно").mild)
        // The same pain again, or a first one.
        assertEquals(true, pain("опять болит колено").same)
        assertEquals(false, pain("впервые болит колено").same)
    }

    /** Words of time mark when it started — a weekday counted back from the chat's day — but none for a past day. */
    @Test
    fun timeWordsAreNotMarkedInAPastDayChat() {
        val monday = setting.copy(day = LocalDate.of(2026, 10, 5))
        assertEquals(SymptomOption.STARTED_YESTERDAY, pain("болит колено со вчера", monday).onset)
        assertEquals(SymptomOption.STARTED_TODAY, pain("с утра болит колено", monday).onset)
        assertEquals(SymptomOption.STARTED_2_3_DAYS, pain("болит колено 3 дня", monday).onset)
        assertEquals(SymptomOption.STARTED_A_WEEK, pain("колено болит с прошлой недели", monday).onset)
        assertEquals(SymptomOption.STARTED_LONGER, pain("колено болит уже полгода", monday).onset)
        mapOf(
            "болит колено с воскресенья" to SymptomOption.STARTED_YESTERDAY,
            "болит колено с пятницы" to SymptomOption.STARTED_2_3_DAYS,
            "my knee hurts since tuesday" to SymptomOption.STARTED_A_WEEK,
            "болит колено с понедельника" to null,
            "болит колено с четверга" to null,
        ).forEach { (text, onset) -> assertEquals(text, onset, pain(text, monday).onset) }
        // "Now", «по утрам», «4 дня», how often: no mark; two times: none.
        listOf(
            "сейчас болит колено",
            "колено болит по утрам",
            "колено болит 4 дня",
            "колено болит 3 раза в неделю",
            "my knee hurts twice a week",
            "колено болит раз в месяц",
            "со вчера, а вообще неделю болит колено",
        )
            .forEach { assertNull(it, pain(it, monday).onset) }
        // A chat for an earlier day reads no time word.
        val past = chatSetting(past = true)
        assertNull(pain("болит колено со вчера", past).onset)
        val fever = ChatLog(past = true).choseTopic(TopicChoice(Symptom.FEVER), past).settle(past)
            .answeredLast(past) {
                it.copy(
                    answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE)),
                ).answered(SymptomStep.WITH)
            }
            .settle(past).wrote("shivery since yesterday").settle(past)
        assertTrue((chatTurn(fever, past).reply as ChatReply.Choose).marked.isEmpty())
    }

    /** Emoji propose their topics, in every language, and are never a word read: the line to call stays beside them. */
    @Test
    fun emojiProposeAndNeverSetLanguage() {
        assertEquals(listOf("MOOD", "LOW_MOOD"), keys("😢"))
        assertEquals(listOf("MOOD", "LOW_MOOD"), keys("☹️"))
        assertEquals(listOf("MOOD"), keys("🙂"))
        assertEquals(listOf("WORRY"), keys("😱"))
        assertEquals(listOf("NAUSEA"), keys("🤢"))
        assertEquals(listOf("DOSE"), keys("💊"))
        assertEquals("HEAD", pain("🤕").family)
        assertTrue(pain("🤕").proposal is PainProposal.Head)
        listOf("😢", "🤕", "💊", "😴🥱").forEach { assertFalse(it, read(it).language) }
        // Beside words, the words are read and the emoji's topics come after them.
        assertTrue(read("болит колено 😢").language)
        assertEquals(listOf("PAIN:KNEE", "MOOD", "LOW_MOOD"), keys("болит колено 😢"))
        // A message in another language with an emoji: the emoji's chips — the mood's pair, which the mood
        // package offers — and the line to call.
        val turn = chatTurn(ChatLog().wrote("Ich bin müde 😭").settle(), setting)
        assertTrue(ChatLine.SAFETY in turn.lines())
        assertEquals(listOf(Symptom.MOOD, Symptom.LOW_MOOD), (turn.reply as ChatReply.Topics).choices.map { it.topic })
        // Words of suicide in another language are read (SafetyWords.kt): their question comes first,
        // with where help is, and they are still no word read.
        val crisis = chatTurn(ChatLog().wrote("死にたい😭").settle(), setting)
        assertEquals(SymptomStep.CRISIS_NOW, crisis.asked())
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT), crisis.lines())
        assertFalse(read("死にたい😭").language)
    }

    /** Small talk and questions: what each is read as, and what the router says to it. */
    @Test
    fun smallTalkAndQuestions() {
        mapOf(
            "привет" to SmallTalk.GREETING, "Привет!" to SmallTalk.GREETING, "hello there" to SmallTalk.GREETING,
            "спасибо" to SmallTalk.THANKS, "thank you very much" to SmallTalk.THANKS, "ok thanks" to SmallTalk.THANKS,
            "как дела?" to SmallTalk.HOW_ARE_YOU, "how are you?" to SmallTalk.HOW_ARE_YOU,
            "тупой бот" to SmallTalk.UPSET, "this is a useless app" to SmallTalk.UPSET,
        ).forEach { (text, talk) -> assertEquals(text, talk, read(text).smallTalk) }
        // Thanks and greetings only when the message is nothing else; a dull pain is no insult.
        listOf("привет, болит колено", "чувствую себя хорошо", "тупая боль в колене").forEach {
            assertNull(it, read(it).smallTalk)
        }
        assertEquals(setOf(PainType.ACHING), pain("тупая боль в колене").types)
        // A question: a phrase of one, or a question mark after a word that asks; "how are you?" is none.
        listOf("что мне делать?", "what should I do", "это опасно", "why?", "can I take something for it?")
            .forEach { assertTrue(it, read(it).question) }
        listOf("сам не знаю почему", "почему болит колено", "how are you?", "болит колено").forEach {
            assertFalse(it, read(it).question)
        }
        // About someone else; a child too, whom the questions are not written for.
        assertTrue(read("у мамы болит голова").someoneElse)
        assertFalse(read("у мамы болит голова").child)
        listOf("у ребёнка температура", "my son has a fever").forEach {
            assertTrue(
                it,
                read(it).someoneElse && read(it).child,
            )
        }
        // The router's lines.
        fun lines(text: String) = chatTurn(ChatLog().wrote(text).settle(), setting).lines()
        assertEquals(listOf(ChatLine.CHAT_HELLO), lines("привет"))
        assertEquals(listOf(ChatLine.WELCOME), lines("спасибо"))
        assertEquals(listOf(ChatLine.HOW_ARE_YOU), lines("как дела?"))
        assertEquals(listOf(ChatLine.QUESTION, ChatLine.SAFETY), lines("что мне делать?"))
        assertEquals(listOf(ChatLine.SORRY), lines("тупой бот"))
        // Sorry once a chat: the second time, plainly that a chip may be easier.
        val again = ChatLog().wrote("тупой бот").settle().wrote("useless bot").settle()
        assertEquals(listOf(ChatLine.NO_MATCH_AGAIN), chatTurn(again, setting).lines())
        assertEquals(listOf(ChatLine.SOMEONE_ELSE, ChatLine.ADULTS, ChatLine.MATCH), lines("у ребёнка температура"))
        // Thanks while a question waits: "you are welcome", the line to call, and the question again.
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle().wrote("спасибо").settle()
        val turn = chatTurn(fever, setting)
        assertEquals(listOf(ChatLine.WELCOME, ChatLine.SAFETY), turn.lines())
        assertEquals(SymptomStep.WITH, turn.asked())
    }

    /** «й» typed as «и» and its breve, «ё» as «е» and its dots: one letter again, and every key finds it. */
    @Test
    fun nfcDecomposedLettersMatch() {
        val decomposed = Normalizer.normalize("не хочу быть живой", Normalizer.Form.NFD)
        assertTrue(decomposed.length > "не хочу быть живой".length)
        assertTrue(crisisIn(decomposed))
        assertTrue(read(decomposed).crisis)
        assertEquals(" ее ", normalisedWords(Normalizer.normalize("Её", Normalizer.Form.NFD)))
        assertEquals("BELLY", pain(Normalizer.normalize("болит живот, всё ещё", Normalizer.Form.NFD)).family)
    }

    /**
     * The chips an earlier answer of this chat points at ([TopicScript.markPairs]): marked from this
     * chat, never ticked, and only where the words have not marked them already.
     */
    @Test
    fun anEarlierAnswerMarksThePairedQuestion() {
        val script = object : TopicScript by SymptomScript {
            override val markPairs = listOf(SymptomStep.ONSET to SymptomStep.ONSET)
        }
        val chest =
            OtherThread(
                0,
                Symptom.CHEST_PAIN,
                newChat().copy(answers = mapOf(SymptomStep.ONSET to listOf(SymptomOption.STARTED_YESTERDAY))),
                emptyList(),
            )
        val state =
            ChatState(
                newChat(),
                female = false,
                earlier = null,
                thread = ThreadContext(index = 1, others = listOf(chest)),
            )
        val options = chatOptions(Symptom.FEVER, SymptomStep.ONSET, false)
        val marked = withChatMarks(ChatReply.Choose(SymptomStep.ONSET, options), script, state) as ChatReply.Choose
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), marked.chatMarked)
        assertTrue(marked.marked.isEmpty())
        // The words' own mark stands, and the earlier answer adds nothing over it.
        val worded = ChatReply.Choose(SymptomStep.ONSET, options, marked = setOf(SymptomOption.STARTED_YESTERDAY))
        assertTrue((withChatMarks(worded, script, state) as ChatReply.Choose).chatMarked.isEmpty())
        // A script with no pairs marks nothing.
        assertEquals(
            ChatReply.Choose(SymptomStep.ONSET, options),
            withChatMarks(ChatReply.Choose(SymptomStep.ONSET, options), SymptomScript, state),
        )
    }

    /** The hidden tag keeps every topic the words named, of every lexicon. */
    @Test
    fun theTagKeepsEveryTopicTheWordsNamed() {
        assertEquals(listOf(Symptom.PAIN, Symptom.DIZZINESS), mentionedTopics("my knee hurts and I feel dizzy"))
        assertEquals(listOf(Symptom.NAUSEA, Symptom.DIZZINESS), mentionedTopics("dizzy and sick, feeling sick"))
        assertEquals(listOf(Symptom.OTHER), mentionedTopics("болит горло"))
        assertTrue(mentionedTopics("").isEmpty())
    }
}
