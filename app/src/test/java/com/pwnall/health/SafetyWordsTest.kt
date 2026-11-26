package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale

/**
 * The words of suicide, of an emergency and of too much of a medicine in the app's fourteen other
 * languages. The keys have not been checked by a native speaker of each language: these tests pin
 * what they read, so every change such a check brings shows here. Each brings only its question, first,
 * and never counts as a word read: the line to call for an emergency stays beside it, and the chest's
 * question is all a key of the chest brings — no thread of the chest after it. The idioms —
 * dying of laughter in every language that has it — and the everyday words of a steps app stay no key.
 */
class SafetyWordsTest {
    private val other = chatSetting().copy(language = ChatLanguage.OTHER)

    private fun firstTurn(text: String): BotTurn = chatTurn(ChatLog().wrote(text).settle(other), other)

    private fun signs(text: String): Set<SafetySign> = safetySignsIn(text).mapTo(HashSet()) { it.second }

    /** One sentence of each sign as people type it, per language: crisis, breathing, the chest. */
    private val sentences: Map<SafetyLanguage, List<String>> = mapOf(
        SafetyLanguage.AR to listOf("لا أريد أن أعيش", "لا أستطيع التنفس", "ألم في الصدر"),
        SafetyLanguage.CS to listOf("nechci žít", "nemůžu dýchat", "bolest na hrudi"),
        SafetyLanguage.DE to listOf("ich will nicht mehr leben", "ich kann nicht atmen", "Schmerzen in der Brust"),
        SafetyLanguage.ES to listOf("no quiero vivir", "no puedo respirar", "dolor en el pecho"),
        SafetyLanguage.FR to listOf("je veux mourir", "je n'arrive pas à respirer", "douleur à la poitrine"),
        SafetyLanguage.HI to listOf("मैं जीना नहीं चाहता", "सांस नहीं ले पा रहा", "सीने में दर्द"),
        SafetyLanguage.ID to listOf("saya tidak ingin hidup", "saya tidak bisa bernapas", "nyeri dada"),
        SafetyLanguage.IT to listOf("non voglio più vivere", "non riesco a respirare", "dolore al petto"),
        SafetyLanguage.JA to listOf("死にたい", "息ができない", "胸が苦しい"),
        SafetyLanguage.KO to listOf("죽고 싶어요", "숨을 못 쉬겠어요", "가슴이 아파요"),
        SafetyLanguage.PL to listOf("nie chcę żyć", "nie mogę oddychać", "ból w klatce piersiowej"),
        SafetyLanguage.PT to listOf("não quero viver", "não consigo respirar", "dor no peito"),
        SafetyLanguage.TR to listOf("yaşamak istemiyorum", "nefes alamıyorum", "göğsümde ağrı var"),
        SafetyLanguage.ZH to listOf("我不想活了", "喘不过气", "胸口痛"),
    )

    /**
     * The fourteen languages: «не хочу жить», «не могу дышать», the chest — each its
     * question first, none a word read, the line to call beside every one.
     */
    @Test
    fun crisisBreathingAndChestAwaitingNativeSpeakerReviewAreReadInEveryLanguage() {
        assertEquals(SafetyLanguage.entries.toSet(), sentences.keys)
        sentences.forEach { (language, phrases) ->
            val (crisis, breath, chest) = phrases
            assertTrue("$language $crisis", crisisIn(crisis))
            assertEquals("$language $breath", listOf(EmergencyWords.BREATHING), emergencyIn(breath))
            assertEquals("$language $chest", listOf(EmergencyWords.CHEST), emergencyIn(chest))
            phrases.forEach { assertFalse("$language $it is no word read", readWords(it, other).language) }
            assertEquals(SymptomStep.CRISIS_NOW, firstTurn(crisis).asked())
            assertTrue(ChatLine.CRISIS_SUPPORT in firstTurn(crisis).lines())
            listOf(breath to SymptomStep.BREATH_NOW, chest to SymptomStep.CHEST_NOW).forEach { (text, step) ->
                val turn = firstTurn(text)
                assertEquals("$language $text", step, turn.asked())
                assertTrue("$language $text keeps the line to call", ChatLine.SAFETY in turn.lines())
            }
        }
    }

    /**
     * The chest of another language brings the chest's question and nothing after it.
     * After "no" the chat starts no thread of the chest — it would say «Вы написали о груди…» and file
     * one — but offers the whole menu with the line to call, as for any other words it did not read.
     */
    @Test
    fun theChestOfAnotherLanguageAsksItsQuestionAndStartsNoThread() {
        val chests = sentences.values.map { it[2] } + listOf("Me duele el pecho", "sakit di dada", "Kalbim ağrıyor")
        chests.forEach { text ->
            assertFalse(text, chestWordsIn(text))
            val asked = ChatLog().wrote(text).settle(other)
            assertEquals(text, SymptomStep.CHEST_NOW, chatTurn(asked, other).asked())
            val no = asked.answeredLast(other) { it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null) }
                .settle(other)
            assertNull(text, no.threads.single().topic)
            assertEquals(text, 1, no.threads.size)
            val turn = chatTurn(no, other)
            assertTrue(text, ChatLine.SAFETY in turn.lines())
            assertFalse(text, ChatLine.CHEST_WORDS in turn.lines())
            assertTrue(text, (turn.reply as ChatReply.Topics).all)
        }
        // And words that are no chest at all bring not even the question: «dadakan» is "sudden», «zawaliłem»
        // "I failed", «mostek» the bridge exercise, «hice pecho» no pain while the pain is the knee's alone.
        listOf(
            "Sakit kepala dadakan",
            "Zawaliłem dietę, boli mnie brzuch",
            "Jestem zawalony robotą",
            "Robiłem mostek i boli mnie kolano",
            "Zawalę ten egzamin, boli mnie głowa",
        ).forEach { text ->
            assertTrue(text, emergencyIn(text).isEmpty())
            assertNull(text, firstTurn(text).asked())
        }
        // The breastbone the pain stands behind, the colloquial chest and a heart attack still ask.
        listOf("Ból za mostkiem", "Ucisk za mostkiem", "Boli mnie klatka", "Miał zawał serca", "Dada saya sakit")
            .forEach { assertEquals(it, listOf(EmergencyWords.CHEST), emergencyIn(it)) }
    }

    /**
     * A phrase that holds keys out holds them only in its own clause, and in Chinese,
     * Japanese and Korean only in one word: «我想死你了» is "I miss you", «我想死，你能帮我吗» "I want to
     * die, can you help me". «가슴살» is chicken breast, «가슴 살짝 아파» a chest that hurts a little.
     */
    @Test
    fun aPhraseThatHoldsKeysOutHoldsNoneAcrossAClauseOrASpace() {
        listOf(
            "我想死，你能帮我吗", "我想死。你别劝我", "好想死，你懂吗", "好想死 你能帮我吗", "我真想死，你说怎么办",
            "我想死你们都不懂", "我想死 你们别管我", "我想死", "我很想死", "我真的想死",
        ).forEach { text ->
            assertTrue(text, crisisIn(text))
            assertEquals(text, SymptomStep.CRISIS_NOW, firstTurn(text).asked())
        }
        listOf("我想死你了", "好想死你啊", "想死你们了", "可想死我了").forEach { assertFalse(it, crisisIn(it)) }
        listOf("가슴 살짝 아파", "가슴 살짝 답답해요", "我吃了鸡，胸口痛", "sakit di dada, ayam goreng tadi pedas")
            .forEach { text ->
                assertEquals(text, listOf(EmergencyWords.CHEST), emergencyIn(text))
                assertEquals(text, SymptomStep.CHEST_NOW, firstTurn(text).asked())
            }
        listOf("닭가슴살 먹고 배가 아파요", "鸡胸肉很好吃，头疼", "makan dada ayam, perut sakit").forEach {
            assertTrue(it, emergencyIn(it).isEmpty())
        }
        // A space ends the phrase that holds keys out as a comma does: «鸡 胸口痛» is no chicken breast.
        assertEquals(listOf(EmergencyWords.CHEST), emergencyIn("我吃了鸡 胸口痛"))
        // A key of Chinese or Japanese is read across a space typed inside it.
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("息が できない"))
    }

    /**
     * A Korean key never joins the end of one word to the start of the next — «여자 살
     * 빼기» (losing weight) is no «자살», «피자 해먹기» no «자해» — while a space typed or left out inside a
     * key is the same key, «죽고싶다» and «죽고 싶다».
     */
    @Test
    fun aKoreanKeyNeverJoinsTwoWords() {
        listOf("여자 살 빼기 운동", "남자 살 빼는 법", "피자 살 쪄요?", "과자 살 거야", "피자 해먹기", "의자 해체했어요")
            .forEach { text ->
                assertFalse(text, crisisIn(text))
                assertNull(text, firstTurn(text).asked())
            }
        listOf("죽고싶다", "죽고 싶다", "진짜죽고싶어", "자살하고 싶어", "나 자해했어", "그냥 사라지고싶어")
            .forEach { assertTrue(it, crisisIn(it)) }
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("숨을못쉬겠어"))
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("숨을 못 쉬겠어요"))
    }

    /**
     * An Arabic word carries «و», «ف», «ب», «ل», «ك» and «ال» on its front — "and", "with",
     * "the" — and is read without them too: «بالانتحار» is «انتحار», «ولا أستطيع» is «لا أستطيع».
     */
    @Test
    fun anArabicWordIsReadWithoutWhatStandsStuckToItsFront() {
        listOf("أفكر في الانتحار", "أفكر بالانتحار", "أريد الانتحار", "وأفكر بالانتحار كل يوم", "تعبت وأريد أن أموت")
            .forEach { text ->
                assertTrue(text, crisisIn(text))
                assertEquals(text, SymptomStep.CRISIS_NOW, firstTurn(text).asked())
            }
        listOf("ولا أستطيع التنفس", "أشعر بضيق في التنفس", "عندي صعوبة بالتنفس", "ما بقدر اتنفس").forEach {
            assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it))
        }
        // Both questions of a message that has both, breathing first as everywhere.
        assertEquals(
            listOf(EmergencyWords.BREATHING, EmergencyWords.CHEST),
            emergencyIn("عندي ألم في صدري ولا أستطيع التنفس"),
        )
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("والدي فاقد الوعي"))
        assertTrue(emergencyIn("عندي ألم في المعدة").isEmpty())
        // What is taken off, and that two letters at least are left: «ولا» is «لا», never «ا».
        assertTrue("انتحار" in arabicStems("بالانتحار"))
        assertTrue("التنفس" in arabicStems("بالتنفس"))
        assertTrue("انتحار" in arabicStems("للانتحار"))
        assertEquals(listOf("ولا", "لا"), arabicStems("ولا"))
        assertEquals(listOf("لا"), arabicStems("لا"))
        assertEquals(listOf("nie"), arabicStems("nie"))
    }

    /**
     * The commonest ways of saying it, beyond the three sentences a language — a word
     * or two inside a phrase of suicide or of breathing, the reflexive forms, Hindi's spellings, German
     * typed without its umlauts, and the heart for the chest as English and Russian read it. Each,
     * like every key here, is yet to be checked by a native speaker.
     */
    @Test
    fun theCommonWaysOfSayingItAwaitingNativeSpeakerReview() {
        listOf(
            "Nie chce mi się żyć", "Ich will nur noch sterben", "Ich will einfach nicht mehr leben",
            "Ich denke daran, mich umzubringen", "Ich will mich töten", "Ich moechte nicht mehr leben",
            "Mi voglio ammazzare", "Mi voglio uccidere", "Mi ammazzo", "मुझे मरना है", "mujhe marna hai",
            "मैं जीना नही चाहता", "jeena nahin chahta", "jeena nhi chahta", "死んでしまいたい", "消えてしまいたい",
            "死のうと思っている", "生きるのがつらい", "我很想死", "我真的想死", "Mám sebevražedné myšlenky", "Nechci už dál žít",
            "Hayattan bıktım", "Yaşamaktan bıktım", "Ölmek istiyom", "Deseo morir", "Prefiero estar muerta",
            "Me corto a propósito", "Je me fais du mal", "j'ai des pensées suicidaires",
        ).forEach { assertTrue(it, crisisIn(it)) }
        listOf(
            "Nie mogę złapać tchu", "Ich bekomme kaum Luft", "Ich kriege schlecht Luft", "saans nahin le pa raha",
            "mujhe saans nahi aa rahi", "息がしづらい", "Nemůžu popadnout dech", "Nefes almakta zorlanıyorum",
            "ما بقدر اتنفس", "Ich kann nicht richtig atmen",
        ).forEach { assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it)) }
        listOf("chest mein dard hai", "心口疼", "Herzstechen", "Mein Herz tut weh", "Me duele el corazón")
            .plus("Kalbim ağrıyor")
            .forEach { assertEquals(it, listOf(EmergencyWords.CHEST), emergencyIn(it)) }
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("Mein Gesicht haengt auf einer Seite"))
        listOf(
            "睡眠薬をたくさん飲んだ",
            "我吃了太多药",
            "我吃了好多药",
            "약을 너무 많이 먹었어",
            "수면제를 너무 많이 먹었어",
            "Bir sürü ilaç içtim",
            "Aku minum obat terlalu banyak",
            "Tomei vários remédios de uma vez",
        ).forEach { text ->
            assertTrue(text, overdoseIn(text))
            assertEquals(text, SymptomStep.MED_MAX_WORDS, firstTurn(text).asked())
        }
    }

    /**
     * Words a steps app hears every day are no key — a stroke of going downstairs, a
     * knee hurt running, a recipe loved, a word that does not suit, an app that does not respond, a pain
     * that does not give way to its pills, wishing to feel better soon, quitting cigarettes, a leg numb
     * from sitting, a course of pills finished — nor taking one's pills every day.
     */
    @Test
    fun theEverydayWordsOfEachLanguageAskNothing() {
        listOf(
            "Merdiven inme sırasında dizim ağrıyor", "Me hago daño en la rodilla cuando corro",
            "Kendimi kesinlikle iyi hissetmiyorum", "Bu tarife bayıldım", "To mi nie odpowiada", "Moje váha neodpovídá",
            "Die App reagiert nicht", "Mein Handy reagiert nicht", "El dolor no responde a los analgésicos",
            "Mi madre no responde al teléfono", "早く楽になりたい", "Voglio farla finita con le sigarette",
            "Kaki saya mati rasa karena duduk lama", "처방받은 약을 다 먹었는데 아직 아파요", "我每天吃很多药",
            "毎日薬をたくさん飲んでいる", "tomo vários remédios por dia", "her gün bir sürü ilaç kullanıyorum",
            "Ich bin heute 10 km gelaufen", "falta de tempo para arrumar a casa", "我很紧张，头疼", "血压高，头疼",
            "私は胸焼けがして苦しい", "Göğüs antrenmanı yaptım, çok acıktım",
        ).forEach { text ->
            assertTrue(text, signs(text).all { it == SafetySign.CHEST_PAIN || it == SafetySign.CHEST })
            assertNull(text, firstTurn(text).asked())
        }
        // What those words resemble stays a key.
        listOf("Annem bayıldı", "inme geçirdi", "Mein Vater reagiert nicht", "mi abuelo no responde").forEach {
            assertTrue(it, emergencyIn(it).isNotEmpty())
        }
        listOf("Voglio farla finita", "voglio farla finita con la vita", "もう楽になりたい", "Kendimi kesmek istiyorum")
            .forEach { assertTrue(it, crisisIn(it)) }
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("Tangan saya mati rasa"))
    }

    /**
     * Every key of every language, written inside a message — in capitals, its accents left off where
     * its letters are Latin, a German one with "ae", "oe", "ue" — reads as its own sign: none folds to
     * nothing, none holds a mark that ends a clause, none is lost to the folding or to a phrase of its
     * own language that holds keys out.
     */
    @Test
    fun everyKeyAwaitingNativeSpeakerReviewReadsAsItself() {
        val written = SAFETY_WORDS.flatMap { (language, keys) ->
            keys.bySign().flatMap { (sign, texts) -> texts.map { Triple(language, sign, it) } }
        }
        written.forEach { (language, _, key) ->
            assertTrue("$language «$key» folds to nothing", foldedForKeys(normalisedWords(key)).isNotBlank())
        }
        written.forEach { (language, sign, key) ->
            sign ?: return@forEach
            spellings(key, language).forEach { form ->
                val message = if (form.first().isHangul()) "그때 $form 정말" else "a $form b"
                assertTrue("$language $sign «$form»", (language to sign) in safetySignsIn(message))
            }
        }
        assertTrue(safetyKeyTexts().size > 1000)
    }

    /** [key] as written; in capitals and without its accents if its letters are Latin; German with "ae", "oe", "ue". */
    private fun spellings(key: String, language: SafetyLanguage): List<String> {
        val latin = key.all { it.code < 0x250 || !it.isLetter() }
        val bare = Normalizer.normalize(key, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")
        val umlauts = key.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        return listOfNotNull(
            key,
            key.uppercase(Locale.ROOT).takeIf { latin },
            bare.takeIf { latin },
            umlauts.takeIf { language == SafetyLanguage.DE },
        ).distinct()
    }

    private fun Char.isHangul(): Boolean = Character.UnicodeScript.of(code) == Character.UnicodeScript.HANGUL

    /** Typed without the accents, as people often type: «nie chce zyc», «nao consigo respirar». */
    @Test
    fun theAccentsAreFoldedAwaitingNativeSpeakerReview() {
        listOf("nie chce zyc", "nechci zit", "yasamak istemiyorum", "nao quero viver", "non voglio piu vivere")
            .forEach { assertTrue(it, crisisIn(it)) }
        listOf("nao consigo respirar", "nemuzu dychat", "nefes alamiyorum", "NIE MOGĘ ODDYCHAĆ").forEach {
            assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it))
        }
        // The alif with its hamza or without, «ة» or «ه»: one key.
        assertTrue(crisisIn("اريد ان اموت"))
        assertTrue(crisisIn("أريد أن أموت"))
        assertEquals(foldedForKeys(" أريد "), foldedForKeys(" اريد "))
        assertEquals(foldedForKeys(" حياة "), foldedForKeys(" حياه "))
        assertEquals(" nie chce zyc ", foldedForKeys(normalisedWords("Nie chcę żyć")))
        // Hindi without its nasal dot, and in Latin letters "nahin", "nhi": one word.
        assertEquals(foldedForKeys(" नहीं "), foldedForKeys(" नही "))
        assertEquals(foldedForKeys(" सांस "), foldedForKeys(" साँस "))
        assertEquals(" jeena nahi chahta ", foldedForKeys(" jeena nhi chahta "))
        assertEquals(" jeena nahi chahta ", foldedForKeys(" jeena nahin chahta "))
        // Cyrillic stays as it is: «й» is no «и».
        assertEquals(" мой ", foldedForKeys(" мой "))
    }

    /** The idioms and the everyday phrases that hold a key's word: none is read as any sign. */
    @Test
    fun dyingOfLaughterAndChickenBreastAreNoKey() {
        listOf(
            "笑死了", "笑死我了", "累死了", "我想死你了", "好想死你啊", "웃겨 죽겠다", "배고파 죽겠어", "ich lach mich tot",
            "ich bin todmüde", "gülmekten öldüm", "me muero de risa", "me muero de hambre", "mort de rire",
            "je suis mort de fatigue", "morto dal ridere", "morrendo de rir", "umieram ze śmiechu", "umírám smíchy",
            "ميت من الضحك", "हंसते हंसते मर गया", "baterai mau mati", "struktur organisasi kantor", "자살골을 넣었어",
            "死ぬほど笑った", "疲れて死にそう", "estoy mejor sin mi novio", "quiero cortarme el pelo",
            "voglio tagliarmi i capelli", "nach dem Brustschwimmen tut die Schulter weh",
            "tavuk göğsü yedim, midem ağrıyor",
            "닭가슴살 먹고 배가 아파요", "鸡胸肉很好吃，头疼", "petto di pollo, mal di testa",
            "comi peito de frango e minha cabeça dói", "makan dada ayam, perut sakit", "胸肉を食べたら頭が痛い",
            "أكلت صدر دجاج وعندي الم في المعدة",
        ).forEach { text ->
            assertTrue(text, signs(text).all { it == SafetySign.CHEST_PAIN })
            assertFalse(text, crisisIn(text))
            assertTrue(text, emergencyIn(text).isEmpty())
        }
    }

    /** A message holding a key of another language says so nowhere but in its question: no topic, no word read. */
    @Test
    fun aKeyNamesNoTopicAndKeepsTheLineToCall() {
        listOf("je veux mourir", "nie mogę oddychać", "dor no peito", "胸口痛").forEach { text ->
            val found = readWords(text, other)
            assertTrue(text, found.mentions.isEmpty())
            assertFalse(text, found.language)
        }
        // English or Russian words read beside it still count as read.
        assertTrue(readWords("je veux mourir, my head hurts", other).language)
        // A mood's emoji beside a key: the question first, the emoji's chips after it.
        assertEquals(SymptomStep.CRISIS_NOW, firstTurn("Je veux mourir 😐").asked())
    }

    /**
     * The normaliser: a combining mark is part of its word — the Hindi vowel signs do not cut
     * one — the Arabic short vowels and tanween are taken off, and so is the dot a Turkish «İ» leaves.
     */
    @Test
    fun marksStayInTheirWords() {
        assertEquals(" सिर में दर्द है ", normalisedWords("सिर में दर्द है"))
        assertEquals(listOf("सिर", "में", "दर्द", "है"), MessageWords(normalisedWords("सिर में दर्द है")).tokens)
        assertEquals(normalisedWords("جدا"), normalisedWords("جداً"))
        assertEquals(normalisedWords("تعبان جدا"), normalisedWords("تعبان جدًا"))
        assertEquals(" intihar ", normalisedWords("İntihar"))
        assertEquals(" istanbul ", normalisedWords("İstanbul"))
        // A message is read the same way: its words, and where each one starts.
        assertEquals(normalisedWords("मुझे सांस नहीं आ रही"), Message.of("मुझे सांस नहीं आ रही").words)
        // An emoji's variation selector or keycap is no word, and continues none.
        assertEquals("", normalisedWords("☹️").trim())
        assertEquals(" 1 ", normalisedWords("1️⃣"))
        assertEquals(" спасибо ", normalisedWords("спасибо ❤️"))
        assertEquals(SmallTalk.THANKS, readWords("спасибо ❤️", other).smallTalk)
        // Russian and English read as they are: «й» is one letter, «ё» is «е».
        assertEquals(" мой ее ", normalisedWords("Мой её"))
    }

    /** Chinese and Japanese have no spaces, Korean puts them where it likes: their keys stand anywhere in a message. */
    @Test
    fun chineseJapaneseAndKoreanKeysStandAnywhere() {
        assertTrue(crisisIn("我真的不想活了，太累了"))
        assertTrue(crisisIn("もう本当に死にたいです"))
        assertTrue(crisisIn("죽고싶다"))
        assertTrue(crisisIn("진짜 죽고 싶어"))
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("我现在喘不过气来"))
        assertEquals(listOf(EmergencyWords.CHEST), emergencyIn("胸がすごく痛い"))
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("숨을못쉬겠어"))
    }
}
