package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mood's words: what each kind proposes, the words that are kept out on purpose, and what the
 * mood's phrases take from the body's — and that nothing read is ever a face.
 */
class MoodWordsTest {
    private val setting = chatSetting()

    private fun read(text: String) = readWords(text, setting)

    private fun keys(text: String) = read(text).mentions.map { it.mention.key }

    private fun pains(text: String) = read(text).mentions.filterIsInstance<PainWordMention>()

    @Test
    fun everyKeyIsNormalised() {
        val keys = moodKeyTexts()
        assertTrue(keys.size > 100)
        keys.forEach { key ->
            assertEquals(key, key.lowercase(), key)
            assertFalse(key, 'ё' in key)
            assertTrue(key, key.all { it.isLetterOrDigit() || it == ' ' || it == '*' })
            assertTrue(key, key.split(' ').all { word -> word.isNotEmpty() && '*' !in word.dropLast(1) })
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun eachKindProposesTheMoodAndABadOneTheTalkToo() {
        listOf(
            "I feel great today", "so happy", "I'm in high spirits", "отличное настроение", "я счастлива",
            "на душе легко", "всё отлично", "meh", "так себе", "my mood is all over the place", "настроение скачет",
            "my mood", "настроение",
        ).forEach { assertEquals(it, listOf("MOOD"), keys(it)) }
        listOf(
            "bad mood today", "feeling blue", "плохое настроение", "тяжело на душе", "грусть", "руки опускаются",
            // The low mood's own words are a bad mood's too.
            "I feel sad", "feeling down", "подавленное настроение", "I am unhappy",
        ).forEach { assertEquals(it, setOf("MOOD", "LOW_MOOD"), keys(it).toSet()) }
        // Every one of them is a word read: the line to call is not added for them.
        assertTrue(read("так себе").language)
        assertTrue(read("руки опускаются").language)
    }

    /**
     * The mood's phrases hold their words against the body's: «болит душа» is no pain anywhere,
     * «голова кипит» no head, and «руки опускаются» no arm — while it is still a mood.
     */
    @Test
    fun theMoodsPhrasesTakeTheirWordsFromTheBody() {
        assertEquals(setOf("MOOD", "LOW_MOOD"), keys("болит душа").toSet())
        assertEquals(setOf("MOOD", "LOW_MOOD"), keys("руки опускаются").toSet())
        assertTrue(pains("болит душа").isEmpty())
        assertTrue(pains("душа болит").isEmpty())
        assertTrue(pains("голова кипит").isEmpty())
        assertTrue(pains("руки опускаются").none { it.family == "ARM" })
        // A real pain beside them is still read.
        assertEquals(listOf("KNEE"), pains("на душе тяжело, болит колено").map { it.family })
    }

    /** Needing little sleep and the other high signs name nothing: only a mood thread reads them. */
    @Test
    fun sleepAndTheHighSignsAreReadOnlyByTheMood() {
        listOf("I barely slept and I don't need sleep", "почти не сплю", "не сомкнула глаз").forEach {
            assertTrue(it, sleepNeedIn(it))
            assertTrue(it, read(it).mentions.none { mention -> mention.mention.topic == Symptom.MOOD })
        }
        val high = listOf("racing thoughts", "мысли скачут", "full of energy", "энергии через край", "накупила всего")
        high.forEach {
            assertTrue(it, highSignIn(it))
            assertFalse(it, sleepNeedIn(it))
        }
        assertFalse(sleepNeedIn("I slept well"))
        assertFalse(highSignIn("I have no energy"))
        assertTrue(moodChangesIn("ups and downs all week"))
        assertTrue(moodChangesIn("эмоциональные качели"))
        assertFalse(moodChangesIn("I walk up the stairs"))
    }

    /** Pinned on purpose: none of these is a mood. */
    @Test
    fun wordsThatAreNotMood() {
        listOf(
            "I have an upset stomach",
            "расстройство желудка",
            "перепады давления",
            "всё нормально",
            "not bad",
            "средне",
            "мне невесело",
            "повеселиться бы",
        ).forEach { assertFalse(it, Symptom.MOOD in read(it).mentions.map { mention -> mention.mention.topic }) }
        assertEquals(listOf("DIARRHOEA"), keys("расстройство желудка"))
        assertFalse(moodChangesIn("перепады давления"))
        // A short key is a whole word: "so sore" is no "so so", and the knee's pain keeps its word.
        assertEquals(listOf("PAIN:KNEE"), keys("my knee is so sore"))
        assertEquals(listOf("MOOD"), keys("so so today"))
        // "Unhappy" is no happy: it is a low mood's word, and with it the mood is a bad one.
        assertTrue("LOW_MOOD" in keys("I am unhappy"))
        // Two words of change name no mood by themselves — a blood sugar up and down, a pain all over the
        // place, which the body's words then read — while inside a mood thread they are its changes.
        listOf("my blood sugar is up and down", "I am aching all over the place").forEach {
            assertFalse(it, Symptom.MOOD in read(it).mentions.map { mention -> mention.mention.topic })
            assertTrue(it, moodChangesIn(it))
        }
        assertEquals(1, pains("I am aching all over the place").size)
        assertEquals(listOf("MOOD"), keys("my mood is up and down"))
    }

    /** Words only propose: a mood mention carries no face, and a mood thread's dial rests on the middle one. */
    @Test
    fun noWordIsEverAFace() {
        listOf("I feel amazing", "плохое настроение", "😢", "🙂").forEach { text ->
            val mention = read(text).mentions.first { it.mention.topic == Symptom.MOOD }
            assertEquals(text, SymptomWordMention::class, mention::class)
            assertEquals(text, TopicMention(Symptom.MOOD), mention.mention)
        }
    }
}
