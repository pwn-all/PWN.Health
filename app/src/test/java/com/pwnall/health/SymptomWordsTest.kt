package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * What the chat finds in the person's words. A topic found is only offered; the signs of suicide and
 * of an emergency only ask their question first — so a miss here is the error that costs, and the
 * phrases people actually type are pinned one by one.
 */
class SymptomWordsTest {
    private fun topics(text: String) = symptomTopicsIn(text)

    @Test
    fun everyTopicIsFoundInEnglishAndRussian() {
        val cases = mapOf(
            Symptom.VOMITING to listOf("I threw up twice", "меня вырвало", "рвёт с утра", "рвет с утра"),
            Symptom.NAUSEA to listOf("feeling sick all day", "меня тошнит", "чувствую тошноту"),
            Symptom.DIARRHOEA to listOf("I have diarrhea", "понос с утра"),
            Symptom.HEARTBURN to listOf("bad heartburn", "изжога после ужина"),
            Symptom.DIZZINESS to listOf("I feel dizzy", "кружится голова"),
            Symptom.FAINTING to listOf("I passed out", "был обморок"),
            Symptom.PALPITATIONS to listOf("my heart is racing", "сердце колотится"),
            Symptom.CHEST_PAIN to listOf("chest pain since noon", "болит в груди", "my heart hurts", "сердце болит"),
            Symptom.BREATHLESSNESS to listOf("I can't breathe", "I can’t breathe", "одышка"),
            Symptom.FEVER to listOf("high temperature", "температура 38", "знобит"),
            Symptom.TIREDNESS to listOf("so tired", "нет сил"),
            Symptom.WORRY to listOf("I am anxious", "тревожно"),
            Symptom.LOW_MOOD to listOf("feeling down", "подавленное настроение"),
            Symptom.STRESS to listOf("so much stress", "я не справляюсь"),
            Symptom.SLEEP_WORRY to listOf("I cannot sleep", "не могу уснуть"),
        )
        cases.forEach { (topic, phrases) ->
            phrases.forEach { phrase -> assertTrue("$phrase → $topic: ${topics(phrase)}", topic in topics(phrase)) }
        }
    }

    @Test
    fun severalTopicsComeInChipOrder() {
        assertEquals(listOf(Symptom.NAUSEA, Symptom.DIZZINESS), topics("Голова кружится и тошнит"))
        // A burning in the chest is offered as chest pain as well as heartburn.
        assertEquals(listOf(Symptom.HEARTBURN, Symptom.CHEST_PAIN), topics("burning in my chest"))
        assertEquals(listOf(Symptom.HEARTBURN, Symptom.CHEST_PAIN), topics("жжёт в груди"))
    }

    @Test
    fun theWordsThatAreNotKeysAreNotKeys() {
        assertFalse(Symptom.WORRY in topics("меня беспокоит живот"))
        assertFalse(Symptom.LOW_MOOD in topics("I need to lie down"))
        // "Sad" is a whole word: a saddle is no mood, sadness is.
        assertFalse(Symptom.LOW_MOOD in topics("saddle sore after cycling"))
        listOf("I feel sad", "so sad.", "such sadness today", "it saddened me").forEach {
            assertTrue(it, Symptom.LOW_MOOD in topics(it))
        }
        // A spinning class is a bike ([TOPIC_NOT]); a room that spins is dizziness still.
        listOf("went to a spinning class", "45 minutes of spinning", "did spinning at the gym").forEach {
            assertFalse(it, Symptom.DIZZINESS in topics(it))
        }
        listOf("the room is spinning", "my head is spinning", "it feels like spinning").forEach {
            assertTrue(it, Symptom.DIZZINESS in topics(it))
        }
        TOPIC_NOT.forEach { phrase ->
            assertEquals(phrase, phrase, normalisedWords(phrase).trim())
            assertTrue(phrase, symptomTopicsIn("a $phrase b").isEmpty())
        }
        assertFalse(Symptom.FEVER in topics("жареная картошка"))
        assertFalse(Symptom.HEARTBURN in topics("acidic taste"))
        // «боль» as a word, not as the start of «больше» or «больница».
        assertFalse(painIn("больше не могу ждать"))
        assertFalse(painIn("была в больнице"))
        assertTrue(painIn("боль в колене"))
        assertTrue(painIn("my back aches"))
    }

    @Test
    fun caseAndPunctuationAndYoDoNotMatter() {
        assertEquals(" i can t breathe ", normalisedWords("I CAN’T  breathe!!"))
        assertEquals(" ее ", normalisedWords("Её"))
        assertTrue(Symptom.VOMITING in topics("РВЁТ!"))
        // Composed first: a «й» typed as «и» and its breve is one letter, so the crisis key still finds it.
        val decomposed = Normalizer.normalize("не хочу быть живой", Normalizer.Form.NFD)
        assertEquals(" не хочу быть живой ", normalisedWords(decomposed))
        assertTrue(crisisIn(decomposed))
    }

    /**
     * The pain words are the body lexicon's one list, wider than the single-topic chat's:
     * "headache" is one of them. The chest's question reads them, so more words beside the chest ask
     * it — a false alarm about a baby's tummy included, the safe error.
     */
    @Test
    fun thePainWordsWidenAndTheChestsQuestionWithThem() {
        listOf("headache", "my back aches", "ноет колено", "колет в боку", "a throbbing pain", "it is sore").forEach {
            assertTrue(it, painIn(it))
        }
        listOf("грудь жжёт", "my chest is throbbing", "у грудничка крутит живот", "ребёнок, грудь болит").forEach {
            assertTrue(it, chestPainIn(it))
            assertEquals(it, listOf(EmergencyWords.CHEST), emergencyIn(it))
        }
        assertFalse(chestPainIn("my knee is throbbing"))
        assertFalse(painIn("был в больнице, болезнь прошла"))
    }

    /**
     * A heart attack named, and a chest's pain told as NHS "Chest pain" tells it — "discomfort…
     * squeezing or pressure", tight, heavy — or in the past of a pain word, asks whether it is there
     * now, at the opening and inside any thread, rather than reading as no chest words at all.
     * Heavy or tight alone, heartburn after a heavy meal, a racing heart and breathing that is hard are
     * still themselves.
     */
    @Test
    fun aHeartAttackAndTheWaysAChestsPainIsToldAreChestWords() {
        listOf(
            "I think I'm having a heart attack",
            "tightness in my chest",
            "my chest feels heavy",
            "heaviness in my chest",
            "a squeezing in my chest",
            "chest discomfort since lunch",
            "crushing feeling in my chest",
            "инфаркт?",
            "кажется, у меня сердечный приступ",
            "боюсь сердечного приступа",
            "сердце прихватило, тяжесть в груди",
            "грудь сжимает",
            "давило в груди",
            "сдавило грудь",
            "жмёт в груди",
            "давление в груди",
            "щемит в груди",
            "кололо в груди",
            "жгло в груди",
            "пекло в груди",
            "ныло в груди",
            "a heavy feeling in my chest",
            "my heart feels tight",
            "тяжесть за грудиной",
            "давило в грудной клетке",
        ).forEach { phrase ->
            assertTrue(phrase, chestPainIn(phrase))
            assertTrue(phrase, chestWordsIn(phrase))
            assertEquals(phrase, listOf(EmergencyWords.CHEST), emergencyIn(phrase))
            assertTrue(phrase, Symptom.CHEST_PAIN in topics(phrase))
        }
        listOf(
            "heavy rain all day",
            "tight hamstrings after the run",
            "heartburn after a heavy meal",
            "сердцебиение, тяжело дышать",
            "давление 140 на 90",
            "тяжёлый день",
        ).forEach { phrase -> assertFalse(phrase, chestPainIn(phrase)) }
        (CHEST_QUALITY_KEYS + CHEST_ONLY_QUALITY_KEYS).forEach { key ->
            assertEquals(key, key, normalisedWords(key).trim())
        }
    }

    /**
     * The ways of telling a chest's pain count only near the chest — three words between at most — and
     * those that say grief as often ("heavy", «тяжело», «сжимается», «щемит», "crushed") beside the chest
     * alone; "heart rate" and «на сердце» are no heart that hurts: none of these is read
     * as chest pain, asked "is it there now?" and, after "no", filed as one.
     */
    @Test
    fun griefOfTheHeartAndAHeavinessElsewhereAreNoChestPain() {
        listOf(
            "my heart is heavy",
            "на сердце тяжело",
            "сердце сжимается",
            "сердце щемит",
            "it crushed my heart",
            "heart rate was high, legs feel heavy",
            "heart rate strap too tight",
            "камень на сердце, всё давило",
            "сердце колотится, тяжело дышать",
            "съела куриную грудку, теперь тяжесть в желудке",
            "chest day at the gym, now my legs are heavy",
            "кормлю грудью, тяжело вставать по ночам",
        ).forEach { phrase ->
            assertFalse(phrase, chestPainIn(phrase))
            assertFalse(phrase, chestWordsIn(phrase))
            // No emergency at all, but for the one phrase whose breathing is hard (below).
            if (phrase != "сердце колотится, тяжело дышать") assertTrue(phrase, emergencyIn(phrase).isEmpty())
        }
        // Breathing that is hard asks whether it is very hard now, never about the chest.
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("сердце колотится, тяжело дышать"))
        // A racing heart and breathing that is hard are asked about as themselves.
        val told = topics("сердце колотится, тяжело дышать")
        assertTrue(told.toString(), Symptom.PALPITATIONS in told && Symptom.BREATHLESSNESS in told)
        assertFalse(told.toString(), Symptom.CHEST_PAIN in told)
    }

    /** The throat, an ear, an eye, the skin, a tooth: "something else", not on the map: a sore throat is no neck. */
    @Test
    fun theWordsOfOtherPartsAreSomethingElse() {
        listOf("болит горло", "в ушах звенит", "my tooth hurts", "сыпь на руке", "болит зуб", "itchy eyes").forEach {
            assertEquals(it, listOf(Symptom.OTHER), topics(it))
        }
        // «с ног валюсь», «ноги не держат»: tiredness, as the body lexicon keeps them out of the legs.
        assertEquals(listOf(Symptom.TIREDNESS), topics("с ног валюсь"))
        assertEquals(listOf(Symptom.TIREDNESS), topics("ноги не держат"))
    }

    /** The phrases of suicide and self-harm people type, in both languages, apostrophe or none. */
    @Test
    fun wordsOfSuicideAreFound() {
        listOf(
            "I want to kill myself",
            "thinking about suicide",
            "I'm suicidal",
            "I dont want to live anymore",
            "I don’t want to live",
            "I can't go on",
            "I cant go on like this",
            "I want to not wake up",
            "I just want to end myself",
            "please kill me",
            "I want to hang myself",
            "thinking of how to unalive",
            "there is no point living",
            "I took an overdose",
            "I want to end it all",
            "думаю о самоубийстве",
            "хочу самоубиться",
            "не хочу жить",
            "хочу покончить с собой",
            "хочется покончить",
            "хочу повеситься",
            "вскрыть вены",
            "не хочу просыпаться",
            "нет смысла жить",
            "устала жить",
            "хочу выпилиться",
            "наглоталась таблеток",
            "передозировка",
            // Forms easy to miss: the future, «больше» in the middle, «хочется».
            "Я покончу с собой",
            "она покончила с собой",
            "Не хочу больше жить",
            "больше не хочу жить",
            "жить больше не хочу",
            "не хочется больше жить",
            "Мне хочется умереть",
            "умереть хочу",
            "жить надоело",
            "надоело жить",
            "хочу сдохнуть",
            "лучше бы я сдохла",
            "не хочу быть живым",
            "не хочу быть живой",
            "лучше бы я умерла",
            "I don't want to be alive",
            "I dont want to be alive anymore",
            "I do not want to be alive",
            "I want to be dead",
        ).forEach { phrase -> assertTrue(phrase, crisisIn(phrase)) }
        listOf("I feel tired", "повеселиться бы", "so much stress at work", "болит живот").forEach {
            assertFalse(it, crisisIn(it))
        }
    }

    /**
     * Phrases easy to miss; a food poisoning, a jump out of bed
     * and waking up early are still no words of suicide.
     */
    @Test
    fun moreWordsOfSuicideOnceMissedAreFound() {
        listOf(
            "I don't want to wake up",
            "I dont want to wake up tomorrow",
            "I do not want to wake up",
            "I hope I don't wake up",
            "хочу выпрыгнуть из окна",
            "выброшусь из окна",
            "хочу отравиться",
            "я отравлюсь",
        ).forEach { phrase -> assertTrue(phrase, crisisIn(phrase)) }
        listOf("отравилась в кафе, тошнит", "выпрыгнула из кровати", "I want to wake up early").forEach {
            assertFalse(it, crisisIn(it))
        }
    }

    /**
     * Forms easy to miss — a word between a key's own, the "-ing" of its first, «себя»
     * before its verb, two idioms; a death told of and a life worth living are still no words of suicide.
     */
    @Test
    fun wordsOfSuicideOnceMissedAreFound() {
        listOf(
            "I want to take my own life",
            "thinking about taking my life",
            "I keep thinking about ending my life",
            "I want to end my own life",
            "thinking of ending it all",
            "I've been hurting myself",
            "I have been harming myself",
            "I don't wanna live anymore",
            "life is not worth living",
            "life isn't worth living",
            "I have nothing to live for",
            "хочу себя убить",
            "я себя убью",
            "убила бы себя",
            "хочу наложить на себя руки",
            "хочу уйти из жизни",
            "занимаюсь самоповреждением",
        ).forEach { phrase -> assertTrue(phrase, crisisIn(phrase)) }
        listOf("дедушка ушел из жизни", "life is worth living", "убить время").forEach {
            assertFalse(it, crisisIn(it))
        }
    }

    /** The signs of an emergency the words name, each with the question that asks about it. */
    @Test
    fun wordsOfAnEmergencyAreFound() {
        assertEquals(
            listOf(EmergencyWords.CHEST, EmergencyWords.STROKE),
            emergencyIn("My chest really hurts and my arm is numb"),
        )
        assertEquals(listOf(EmergencyWords.BREATHING), emergencyIn("I can't breathe"))
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("её лицо перекосило, речь невнятная"))
        assertEquals(listOf(EmergencyWords.BLOOD), emergencyIn("рвота кровью"))
        // "Breathe" typed without its "e", and blood told with «с» or as "blood in my vomit".
        assertTrue(EmergencyWords.BREATHING in emergencyIn("I can't breath"))
        assertTrue(Symptom.BREATHLESSNESS in topics("I cant breath"))
        listOf("рвота с кровью", "вырвало кровью", "there is blood in my vomit").forEach {
            assertTrue(it, EmergencyWords.BLOOD in emergencyIn(it))
        }
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("he is unconscious"))
        assertEquals(listOf(EmergencyWords.CHEST), emergencyIn("сердце болит"))
        assertTrue(emergencyIn("my knee hurts").isEmpty())
        assertTrue(emergencyIn("ich habe Kopfschmerzen").isEmpty())
        assertEquals(SymptomStep.CHEST_NOW, EmergencyWords.CHEST.step)
    }

    /**
     * Someone who is not breathing is asked about twice — whether they respond, then whether it is very
     * hard to breathe, for the one who breathes but barely. A nose that does not
     * breathe is blocked, and a room one cannot breathe in is stuffy. A nose said blocked is so across its
     * comma too, and who writes that they are not breathing well themselves is answering: theirs is the
     * question about breathing alone.
     */
    @Test
    fun someoneNotBreathingAsksBothQuestions() {
        val both = listOf(EmergencyWords.UNRESPONSIVE, EmergencyWords.BREATHING)
        listOf(
            "he is not breathing",
            "my dad isn't breathing",
            "she stopped breathing",
            "I think he is not breathing",
            "ребёнок не дышит",
            "муж почти не дышит",
            "она перестала дышать",
            // A nose holds nothing past its clause: who fell on it and does not breathe is a person.
            "упал, разбил нос, не дышит",
            // Nor does a blocked one hold the words of a clause that names who does not breathe.
            "нос заложен, он не дышит",
            // Nor one that is not what its clause is about: the words after it are of who has it.
            "ребёнок с заложенным носом, не дышит",
            "the baby has a stuffy nose, not breathing",
        ).forEach { assertEquals(it, both, emergencyIn(it)) }
        listOf(
            "нос не дышит уже неделю",
            "нос заложен и не дышит",
            "нос заложен, не дышит",
            "Заложило нос, совсем не дышит уже неделю",
            "у ребёнка носик не дышит",
            "в комнате не дышится",
            "my nose is not breathing",
            "my nose is blocked, not breathing",
        ).forEach { assertTrue(it, emergencyIn(it).isEmpty()) }
        listOf("I'm not breathing well", "I am not breathing properly", "im not breathing right").forEach {
            assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it))
        }
        assertEquals(both, emergencyIn("he is unconscious and I'm not breathing well"))
    }

    /**
     * A rower's stroke has a rate, and what does not respond is the app as often as a person: the
     * phrases that hold the keys out of their words ([EMERGENCY_NOT]). Read as keys, each would be asked
     * about as a stroke or as someone unconscious. A stroke had, a heat stroke and a person who does not
     * respond still ask.
     */
    @Test
    fun aRowersStrokeAndAnAppThatDoesNotRespondAreNoEmergency() {
        listOf(
            "my stroke rate on the rower was 24",
            "swam 400 m breast stroke",
            "24 strokes per minute",
            "500 strokes on the rower",
            "the app is not responding",
            "the screen is unresponsive",
            "бот не реагирует",
            "телефон не реагирует на нажатия",
            "the pain is not responding to painkillers",
        ).forEach { assertTrue(it, emergencyIn(it).isEmpty()) }
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("I think I'm having a stroke"))
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("heat stroke after the run"))
        // A count of ten or more is a rower's; two strokes had are a person's.
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("he has had 2 strokes"))
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("he is not responding"))
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("он не реагирует на голос"))
        // Only the phrase's own words are held: the person beside the app is still read.
        assertEquals(
            listOf(EmergencyWords.UNRESPONSIVE),
            emergencyIn("the app is not responding and he is unconscious"),
        )
        // Nor is a phrase made across a comma or a full stop, or of a touch a person does not answer.
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("he dropped his phone, not responding"))
        assertEquals(listOf(EmergencyWords.STROKE), emergencyIn("he's back. Stroke symptoms"))
        assertEquals(listOf(EmergencyWords.UNRESPONSIVE), emergencyIn("мама не реагирует на касания"))
        EMERGENCY_NOT.forEach { phrase ->
            assertEquals(phrase, phrase, normalisedWords(phrase).trim())
            assertTrue(phrase, emergencyKeysIn("a $phrase b").isEmpty())
        }
    }
}
