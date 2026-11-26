package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fourteen other languages kept level (SafetyWords.kt). Their keys have not been checked by a
 * native speaker of each language, so the least the lists owe is that no language is read worse than
 * its neighbour: the same plain sentence of every sign brings its question in each — «숨이 막혀요»,
 * «意識がありません», «he vomitado sangre» and «mio padre non respira» among them — and no list is
 * left with a handful of keys.
 */
class SafetyWordsParityTest {
    /** Someone unconscious, a stroke, blood vomited and too many tablets, as each language plainly says them. */
    private val sentences: Map<SafetyLanguage, List<String>> = mapOf(
        SafetyLanguage.AR to listOf("أبي فاقد الوعي", "أعتقد أنها جلطة دماغية", "تقيأت دم", "أخذت حبوب كثيرة"),
        SafetyLanguage.CS to listOf(
            "Je v bezvědomí",
            "Myslím, že mám mrtvici",
            "Zvracel jsem krev",
            "Vzal jsem si moc prášků",
        ),
        SafetyLanguage.DE to listOf(
            "Er ist bewusstlos",
            "Ich glaube, ich habe einen Schlaganfall",
            "Ich spucke Blut",
            "Ich habe zu viele Tabletten genommen",
        ),
        SafetyLanguage.ES to listOf(
            "Está inconsciente",
            "Creo que me está dando un ictus",
            "He vomitado sangre",
            "Me tomé demasiadas pastillas",
        ),
        SafetyLanguage.FR to listOf(
            "Il est inconscient",
            "Je crois que je fais un AVC",
            "Je vomis du sang",
            "J'ai pris trop de médicaments",
        ),
        SafetyLanguage.HI to listOf(
            "वह बेहोश है",
            "मुझे लकवा मार गया",
            "खून की उल्टी हो रही है",
            "मैंने बहुत सारी गोलियां खा लीं",
        ),
        SafetyLanguage.ID to listOf(
            "Dia tidak sadarkan diri",
            "Wajahnya mencong",
            "Muntahnya ada darah",
            "Saya minum terlalu banyak obat",
        ),
        SafetyLanguage.IT to listOf(
            "È privo di sensi",
            "Credo di avere un ictus",
            "Mio padre vomita sangue",
            "Ho preso troppe pillole",
        ),
        SafetyLanguage.JA to listOf("意識がありません", "脳卒中かもしれない", "血を吐きました", "薬を飲みすぎました"),
        SafetyLanguage.KO to listOf("의식이 없어요", "뇌졸중인 것 같아요", "피를 토했어요", "약을 너무 많이 먹었어요"),
        SafetyLanguage.PL to listOf(
            "Jest nieprzytomny",
            "Opadł mu kącik ust",
            "Zwymiotowałem krwią",
            "Wziąłem za dużo tabletek",
        ),
        SafetyLanguage.PT to listOf(
            "Ele está inconsciente",
            "Acho que estou tendo um AVC",
            "Ele vomitou sangue",
            "Tomei muitos comprimidos",
        ),
        SafetyLanguage.TR to listOf("Bilinci yok", "Sanırım felç geçiriyorum", "Kan kusuyorum", "Çok fazla ilaç aldım"),
        SafetyLanguage.ZH to listOf("他昏迷了", "我好像中风了", "我吐血了", "我吃了太多药"),
    )

    /** Someone who is not breathing, as each language plainly says it. */
    private val notBreathing: Map<SafetyLanguage, String> = mapOf(
        SafetyLanguage.AR to "أبي لا يتنفس",
        SafetyLanguage.CS to "Táta nedýchá",
        SafetyLanguage.DE to "Er atmet nicht",
        SafetyLanguage.ES to "Mi padre no respira",
        SafetyLanguage.FR to "Il ne respire plus",
        SafetyLanguage.HI to "वह सांस नहीं ले रहा",
        SafetyLanguage.ID to "Dia tidak bernapas",
        SafetyLanguage.IT to "Mio padre non respira",
        SafetyLanguage.JA to "息をしていません",
        SafetyLanguage.KO to "숨을 안 쉬어요",
        SafetyLanguage.PL to "Tata nie oddycha",
        SafetyLanguage.PT to "Meu pai não respira",
        SafetyLanguage.TR to "Babam nefes almıyor",
        SafetyLanguage.ZH to "他没有呼吸了",
    )

    /** Each sentence is read by a key of its own language, and brings its sign's question and no other. */
    @Test
    fun thePlainSentenceOfEverySignIsReadInEveryLanguage() {
        assertEquals(SafetyLanguage.entries.toSet(), sentences.keys)
        val emergencies = listOf(
            SafetySign.UNRESPONSIVE to EmergencyWords.UNRESPONSIVE,
            SafetySign.STROKE to EmergencyWords.STROKE,
            SafetySign.BLOOD to EmergencyWords.BLOOD,
        )
        sentences.forEach { (language, phrases) ->
            assertEquals(language.name, emergencies.size + 1, phrases.size)
            phrases.zip(emergencies).forEach { (text, asked) ->
                assertTrue("$language $text", (language to asked.first) in safetySignsIn(text))
                assertEquals("$language $text", listOf(asked.second), emergencyIn(text))
            }
            val tablets = phrases.last()
            assertTrue("$language $tablets", overdoseIn(tablets))
        }
    }

    /**
     * Someone who is not breathing brings both questions in every language, as English and Russian ask
     * them ([NOT_BREATHING_KEYS]): whether they respond, then whether it is very hard to breathe.
     */
    @Test
    fun someoneNotBreathingAsksBothQuestionsInEveryLanguage() {
        assertEquals(SafetyLanguage.entries.toSet(), notBreathing.keys)
        val both = listOf(EmergencyWords.UNRESPONSIVE, EmergencyWords.BREATHING)
        notBreathing.forEach { (language, text) -> assertEquals("$language $text", both, emergencyIn(text)) }
        // "I cannot breathe" is still the breathing's question alone.
        listOf("no puedo respirar", "non riesco a respirare", "nie mogę oddychać", "saya tidak bisa bernapas")
            .forEach { assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it)) }
    }

    /**
     * An app, a phone or a medicine that does not respond is no one unconscious in the languages whose
     * key says only "does not respond" — German, Spanish and their like hold these out too — and
     * coffee thrown up is no blood, though «피를 토» stands inside «커피를 토했어요».
     */
    @Test
    fun anAppThatDoesNotRespondIsNoOneUnconscious() {
        listOf(
            "التطبيق لا يستجيب",
            "Aplikace nereaguje",
            "Aplikasi tidak merespon",
            "앱이 반응이 없어요",
            "Aplikacja nie reaguje",
            "手机没反应",
            "Ból nie reaguje na leki",
            "吃了药没反应",
            "커피를 토했어요",
            "アプリが反応しません",
            "画面が反応しない",
            "痛みが薬に反応しない",
        ).forEach { assertTrue(it, emergencyIn(it).isEmpty()) }
        // Japanese in the polite form too: «反応しません» is a key as «反応がない» is.
        val people = listOf(
            "والدي لا يستجيب",
            "Táta nereaguje",
            "아버지가 반응이 없어요",
            "Tata nie reaguje",
            "他没反应",
            "父が反応しません",
            "呼んでも反応しない",
        )
        people.forEach { assertEquals(it, listOf(EmergencyWords.UNRESPONSIVE), emergencyIn(it)) }
        assertEquals(listOf(EmergencyWords.BLOOD), emergencyIn("피를 토했어요"))
    }

    /** A gym with no air conditioning is short of no breath: «sem ar condicionado» is not «sem ar». */
    @Test
    fun noAirConditioningIsNoOneShortOfBreath() {
        listOf(
            "Estou sem ar condicionado em casa",
            "Falta ar condicionado na academia",
            "Hay falta de aire acondicionado",
            "Il n'y a plus d'air conditionné",
            "Mi manca l'aria condizionata",
        ).forEach { assertTrue(it, emergencyIn(it).isEmpty()) }
        listOf("Estou sem ar", "Me falta ar", "Tengo falta de aire", "Je manque d'air", "Mi manca l'aria").forEach {
            assertEquals(it, listOf(EmergencyWords.BREATHING), emergencyIn(it))
        }
    }

    /**
     * The fewest keys a sign has in any language: a floor a change may raise, never a count to aim at.
     */
    @Test
    fun noLanguageIsLeftWithAHandfulOfKeys() {
        val floors = mapOf(
            SafetySign.CRISIS to 27,
            SafetySign.UNRESPONSIVE to 9,
            SafetySign.BREATHING to 13,
            SafetySign.STROKE to 13,
            SafetySign.BLOOD to 6,
            SafetySign.OVERDOSE to 9,
        )
        SAFETY_WORDS.forEach { (language, keys) ->
            val counts = keys.bySign().associate { (sign, written) -> sign to written.size }
            floors.forEach { (sign, least) ->
                assertTrue("$language $sign: ${counts[sign]} keys", (counts[sign] ?: 0) >= least)
            }
        }
    }
}
