package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor's English and Russian keys: dangerous words a reader could miss each bring their question
 * first — and only add it, every question other words ask still asked — while the everyday words
 * beside them stay no key. And the English keys that are words of other languages too never take the
 * line to call for an emergency away from a message in one of them.
 */
class ChatSafetyFloorTest {
    private val setting = chatSetting()

    private val other = setting.copy(language = ChatLanguage.OTHER)

    private fun firstTurn(text: String, with: ChatSetting = setting): BotTurn =
        chatTurn(ChatLog().wrote(text).settle(with), with)

    private fun asked(text: String): SymptomStep? = firstTurn(text).asked()

    @Test
    fun theDangerousMissesAskTheirQuestionFirst() {
        mapOf(
            SymptomStep.BREATH_NOW to listOf(
                "не могу вдохнуть", "воздуха не хватает", "не хватает воздуха", "не могу отдышаться", "дышать тяжело",
                "тяжело дышать", "ne mogu dyshat", "I can't catch my breath", "I cant get enough air",
                "breathing is hard",
            ),
            SymptomStep.STROKE_SIGNS to listOf(
                "отнялась рука", "рука отнимается", "лицо поехало на одну сторону", "язык не слушается",
                "не могу подобрать слова", "my face feels droopy", "one side of my face dropped",
                "I can't get my words out", "my arm went dead", "my arm feels weak", "I can't feel my arm",
            ),
            SymptomStep.CHEST_NOW to listOf(
                "в груди как кирпич лежит",
                "как плита на груди",
                "elephant sitting on my chest",
            ),
            SymptomStep.MED_MAX_WORDS to listOf(
                "выпила пачку снотворного",
                "выпила полфлакона корвалола",
                "горсть валокордина",
                "купила пачку снотворного",
                "I took a bunch of pills",
                "I took a whole lot of sleeping pills",
                "took a handful of sleeping pills",
            ),
            SymptomStep.CRISIS_NOW to listOf(
                "хочу исчезнуть", "устала от всего, хочу исчезнуть", "всем будет лучше без меня",
                "лучше бы я не просыпалась", "хочу уснуть и не проснуться", "порезалась специально", "режу руки",
                "сделала себе больно", "сделал себе больно", "хоть вешайся", "ne hochu zhit", "I want to disappear",
                "everyone would be better off without me", "I wish I could sleep forever",
                "I don't see the point anymore", "kms", "I just want it all to stop", "I'm so tired of living",
                "не вижу смысла жить", "я больше не вижу смысла жить", "не вижу смысла в жизни",
            ),
        ).forEach { (step, phrases) -> phrases.forEach { assertEquals(it, step, asked(it)) } }
    }

    /** «сделала себе больно» opens no pain on the whole map: the words of self-harm hold their words. */
    @Test
    fun selfHarmIsNoPain() {
        listOf("сделала себе больно", "сделал себе больно", "причиняю себе боль").forEach { text ->
            val found = readWords(text, setting)
            assertTrue(text, found.crisis)
            assertTrue(text, found.mentions.filterIsInstance<PainWordMention>().isEmpty())
        }
        // A pain is still a pain.
        val knee = readWords("больно колено", setting).mentions.filterIsInstance<PainWordMention>().single()
        assertEquals("KNEE", knee.family)
    }

    /**
     * "kms" is a crisis's, except where it has kilometres, as it often does in an app that counts them:
     * after a number, a count, "my", "total" or "in", before "walked" or "today".
     */
    @Test
    fun kmsOfADistanceIsKilometres() {
        listOf("kms", "i want to kms", "kms lol", "honestly kms", "lol kms", "I want to kms today", "omg kms")
            .plus("I'm gonna kms")
            .forEach { assertTrue(it, crisisIn(it)) }
        listOf(
            "I walked 5 kms", "ran 10 kms today", "5.5 kms", "a few kms", "how many kms did you do", "I logged my kms",
            "total kms this week", "kms walked today", "distance in kms", "my kms are up", "kms per week",
        ).forEach { assertFalse(it, crisisIn(it)) }
    }

    /** Hard negatives of everyday speech and of a steps app: none brings a question. */
    @Test
    fun theEverydayWordsBesideThemStayNoKey() {
        listOf(
            "умираю со смеху", "убить время", "I'm dying to see it", "this exam is killing me", "смертельно устала",
            "отсидела ногу", "рука затекла", "язык не поворачивается сказать", "слов нет", "lost for words",
            "I feel off", "my face dropped when I heard", "I walked 5 kms", "I can't feel my legs after that run",
        ).forEach { text ->
            assertFalse(text, crisisIn(text))
            assertTrue(text, emergencyIn(text).isEmpty())
        }
        listOf("камень на сердце", "chest day with weights", "на сердце тяжело").forEach {
            assertFalse(it, chestWordsIn(it))
        }
        listOf("купила пачку парацетамола", "I took a bunch of photos", "I take a lot of pills every day")
            .plus("пачка сигарет")
            .forEach { assertFalse(it, overdoseIn(it)) }
    }

    /** Every question the other keys' words ask is asked still: a key of the floor only adds one. */
    @Test
    fun theQuestionsBeforeAreAskedStill() {
        mapOf(
            "не хочу жить" to SymptomStep.CRISIS_NOW,
            "задыхаюсь" to SymptomStep.BREATH_NOW,
            "онемела рука" to SymptomStep.STROKE_SIGNS,
            "болит в груди" to SymptomStep.CHEST_NOW,
            "I took 20 paracetamol" to SymptomStep.MED_MAX_WORDS,
            "он без сознания" to SymptomStep.UNRESPONSIVE,
            "рвота кровью" to SymptomStep.BLOOD,
            "не хочу жить, болит грудь, задыхаюсь" to SymptomStep.CRISIS_NOW,
            "болит в груди и выпил горсть парацетамола" to SymptomStep.CHEST_NOW,
        ).forEach { (text, step) -> assertEquals(text, step, asked(text)) }
    }

    /** Written during another question, the floor's words interrupt it as every key's do. */
    @Test
    fun duringAQuestionTheyInterruptIt() {
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(SymptomStep.PAIN_AREA, knee.asked())
        assertEquals(SymptomStep.BREATH_NOW, knee.wrote("не могу вдохнуть").settle().asked())
        assertEquals(SymptomStep.MED_MAX_WORDS, knee.wrote("выпила пачку снотворного").settle().asked())
        assertEquals(SymptomStep.STROKE_SIGNS, knee.wrote("и рука отнялась").settle().asked())
        assertEquals(SymptomStep.CRISIS_NOW, knee.wrote("сделала себе больно").settle().asked())
    }

    /**
     * Chance hits: «j'ai acheté du pain» (bread), «🤕 no sé qué hacer», «ich habe Stress» — in an
     * app in another language the English key that is a word of theirs is no word read, so the line to
     * call for an emergency stays; the chips it proposes still stand.
     */
    @Test
    fun aKeyThatIsAWordOfAnotherLanguageKeepsTheLineToCall() {
        listOf("j'ai acheté du pain", "🤕 no sé qué hacer", "ich habe Stress", "Meine Hand tut weh", "dor no peito")
            .plus("ok")
            .forEach { text ->
                assertFalse(text, readWords(text, other).language)
                assertTrue(text, ChatLine.SAFETY in firstTurn(text, other).lines())
            }
        val stress = firstTurn("ich habe Stress", other).reply as ChatReply.Topics
        assertEquals(listOf(Symptom.STRESS), stress.choices.map { it.topic })
        assertEquals(SymptomStep.PAIN_AREA, firstTurn("j'ai acheté du pain", other).asked())
        // In an English or Russian app they are read as words, and in another an English phrase, two keys or Russian.
        listOf("no", "pain", "stress").forEach { assertTrue(it, readWords(it, setting).language) }
        listOf("my knee hurts", "I can't sleep", "болит колено", "so tired and dizzy").forEach {
            assertTrue(it, readWords(it, other).language)
        }
        assertFalse(ChatLine.SAFETY in firstTurn("ich habe Stress", setting).lines())
        assertTrue(HOMOGRAPH_KEYS.containsAll(setOf("no", "ok", "pain", "hand", "stress")))
    }

    /**
     * "gut" (de: good), "hi" (Hindi in Latin letters: even, only), "fatigue" and
     * «crampe» (fr), "Burnout", and any English key alone in an app in another language — "vomit" of the
     * Italian «vomito» — never take the line to call for an emergency away; «crampe» beside «poitrine»
     * asks about the chest first.
     */
    @Test
    fun aLoneEnglishKeyInAnotherLanguageKeepsTheLineToCall() {
        mapOf(
            "Mir geht es nicht gut, ich habe Atemnot" to SymptomStep.BREATH_NOW,
            "abhi hi saans phool rahi hai" to SymptomStep.BREATH_NOW,
            "j'ai une crampe dans la poitrine" to SymptomStep.CHEST_NOW,
            "ho vomitato sangue" to SymptomStep.BLOOD,
            "grande fatigue, j'ai l'impression que mon cœur va lâcher" to null,
            "fatigue, palpitations, j'ai peur" to null,
            "Ich habe Burnout" to null,
            "Mir geht es nicht gut" to null,
        ).forEach { (text, step) ->
            assertFalse(text, readWords(text, other).language)
            val turn = firstTurn(text, other)
            assertEquals(text, step, turn.asked())
            assertTrue(text, ChatLine.SAFETY in turn.lines())
        }
        assertTrue(HOMOGRAPH_KEYS.containsAll(setOf("gut", "guts", "hi", "fatigue", "cramp", "cramps", "burnout")))
        listOf("headache", "vomito", "tired").forEach { assertFalse(it, readWords(it, other).language) }
    }

    /**
     * Words a question was brought by are read in the app's own language: in an English app "tired"
     * beside «nie mogę oddychać» is a word read, and the question stands without the line to call; in
     * an app in another language the line to call stands beside it.
     */
    @Test
    fun theWordsOfAQuestionAreReadInTheAppsLanguage() {
        val english = firstTurn("tired, nie mogę oddychać", setting)
        assertEquals(SymptomStep.BREATH_NOW, english.asked())
        assertFalse(ChatLine.SAFETY in english.lines())
        val polish = firstTurn("tired, nie mogę oddychać", other)
        assertEquals(SymptomStep.BREATH_NOW, polish.asked())
        assertTrue(ChatLine.SAFETY in polish.lines())
    }
}
