package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat's strings: every English one in Russian, the citations in English only, every locale's
 * safety lines in place — the question about suicide and where help is are never shown in English
 * to someone reading another language — and no straight quote or doubled word anywhere.
 *
 * Every `symptom_chat*.xml` of a folder is read, so each thread's own file — the pain thread's, the
 * mood's, the medicine's — is held to the same rules as the first.
 */
class SymptomChatStringsTest {
    private data class Entry(val name: String, val text: String, val translatable: Boolean)

    private fun read(folder: String): List<Entry> {
        val files = File("src/main/res/$folder").listFiles().orEmpty()
            .filter { it.name.startsWith("symptom_chat") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
        return files.flatMap { file ->
            Regex("""<string name="([^"]+)"((?: [\w:]+="[^"]*")*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { Entry(it.groupValues[1], it.groupValues[3], "translatable=\"false\"" !in it.groupValues[2]) }
                .toList()
        }
    }

    private val english = read("values")
    private val locales = File("src/main/res").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "symptoms.xml").exists() }
        .map { it.name }

    /** The lines that keep a person safe, in every language the app ships in. */
    private val safety = setOf(
        "symptom_chat_safety",
        "symptom_chat_crisis_support",
        "symptom_chat_close_emergency",
        "symptom_chat_close_emergency_mind",
        "symptom_chat_close_today",
        "symptom_chat_close_crisis",
        "symptom_chat_close_gp",
        "symptom_chat_close_body",
        "symptom_chat_close_mind",
        "symptom_ask_crisis",
        "symptom_ask_crisis_now",
        "symptom_ask_chest_now",
        "symptom_ask_palp_now",
        "symptom_ask_breath_now",
        "symptom_ask_stroke",
        "symptom_ask_unresponsive",
        "symptom_option_crisis_safe",
        "symptom_option_crisis_unsafe",
        "symptom_option_crisis_harmed",
        "symptom_option_rather_not_say",
        "symptom_option_safe_now",
        "symptom_option_no_such_thoughts",
        "symptom_alert_emergency_mind",
        "symptom_alert_urgent_mind",
        "symptom_alert_doctor_mind",
        "symptom_alert_safety_net",
    )

    /** Every red flag of the pain thread is a safety line by its name. */
    private val safetyPendingPrefixes = listOf("pain_chat_sign_")

    @Test
    fun russianHasEveryTranslatableStringAndNoCitation() {
        val russian = read("values-ru").map { it.name }.toSet()
        assertEquals(english.filter { it.translatable }.map { it.name }.toSet(), russian)
        english.filter { it.name.startsWith("symptom_source_") || it.name.startsWith("pain_source_") }
            .forEach { assertTrue(it.name, !it.translatable) }
    }

    @Test
    fun everyLocaleHasTheSafetyLinesAndNothingTheEnglishLacks() {
        assertEquals(15, locales.size)
        val translatable = english.filter { it.translatable }.map { it.name }.toSet()
        assertTrue(translatable.containsAll(safety))
        // The chat's safety lines have their fourteen translations, so every language must hold them too.
        val shipped = safety + MODEL_PENDING + translatable.filter { name ->
            name in safetyPending || safetyPendingPrefixes.any { name.startsWith(it) }
        }
        locales.forEach { locale ->
            val names = read(locale).map { it.name }.toSet()
            assertTrue("$locale lacks ${shipped - names}", names.containsAll(shipped))
            assertEquals(locale, emptySet<String>(), names - translatable)
        }
    }

    /** A pending safety line exists in English and is in Russian, word for word in meaning, before it ships. */
    @Test
    fun everyPendingSafetyLineIsInRussian() {
        val translatable = english.filter { it.translatable }.map { it.name }.toSet()
        val lines = translatable.filter { name ->
            name in safetyPending || safetyPendingPrefixes.any { name.startsWith(it) }
        }.toSet()
        // The prefix still finds the pain thread's red flags, counted without the model's own pending
        // lines: renamed, the flags would drop out of every check here unseen.
        assertTrue("only ${lines.size}", lines.size > safetyPending.size)
        assertTrue("${safetyPending - translatable}", translatable.containsAll(safetyPending))
        assertTrue("${MODEL_PENDING - translatable}", translatable.containsAll(MODEL_PENDING))
        val pending = lines + MODEL_PENDING
        val russian = read("values-ru").associate { it.name to it.text }
        pending.forEach { assertTrue(it, russian[it].orEmpty().isNotBlank()) }
    }

    @Test
    fun noStraightQuoteAndNoWordTwiceInARow() {
        (listOf("values") + locales).forEach { folder ->
            read(folder).forEach { (name, text) ->
                assertTrue("$folder/$name", '\'' !in text)
                assertTrue("$folder/$name", '"' !in text)
                val words = text.lowercase().split(Regex("""[\s,.:;!?()«»“”—–-]+""")).filter { it.isNotEmpty() }
                val doubled = words.zipWithNext().firstOrNull { (a, b) -> a == b }
                assertEquals("$folder/$name", null, doubled)
            }
        }
    }

    @Test
    fun formatArgumentsMatchEveryLanguage() {
        fun args(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSortedSet()
        val reference = english.associate { it.name to args(it.text) }
        (listOf("values-ru") + locales).distinct().forEach { folder ->
            read(folder).forEach { assertEquals("$folder/${it.name}", reference[it.name], args(it.text)) }
        }
    }

    /** A line never sends the person to a pencil: the way to change an answer is not something to read out. */
    @Test
    fun noChatStringPointsAtAPencil() {
        (listOf("values") + locales).forEach { folder ->
            read(folder).forEach { (name, text) ->
                val lower = text.lowercase()
                assertTrue("$folder/$name", "карандаш" !in lower && "pencil" !in lower)
            }
        }
    }

    /** The bot never says what it did in a past tense that has to pick a gender: «Записано», never «Записал». */
    @Test
    fun noFirstPersonPastInRussian() {
        val gendered = setOf(
            "записал", "записала", "сохранил", "сохранила", "отметил", "отметила", "добавил", "добавила",
            "удалил", "удалила", "понял", "поняла", "нашла", "услышал", "услышала",
        )
        read("values-ru").forEach { (name, text) ->
            val words = text.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
            assertEquals(name, emptyList<String>(), words.filter { it in gendered })
        }
    }

    /** No line names a number to call: emergency services are said as such, so no country is told the wrong one. */
    @Test
    fun noHotlineNumber() {
        val numbers = setOf("999", "111", "112", "103", "911")
        (listOf("values") + locales).forEach { folder ->
            read(folder).filter { it.translatable }.forEach { (name, text) ->
                val found = Regex("""\d+""").findAll(text).map { it.value }.filter { it in numbers }.toList()
                assertEquals("$folder/$name", emptyList<String>(), found)
            }
        }
    }

    private companion object {
        /**
         * The chat's lines that keep a person safe, checked in Russian word for word and, with their
         * fourteen translations, in every language beside [safety]. One set per part of the chat,
         * each on lines of its own.
         */
        val P1_PENDING = setOf(
            "symptom_chat_chest_first",
            "symptom_chat_chest_words",
            "symptom_chat_someone_else",
            "symptom_alert_urgent_now",
            "pain_chat_alert_in_person",
            "symptom_chat_remark_kept",
            "symptom_chat_about",
            "symptom_ask_with_now",
            "symptom_ask_chest_spread",
            "pain_chat_ask_injury",
            "pain_chat_ask_signs_more",
            "pain_chat_ask_sepsis",
        )

        /** The mood thread's pending safety lines. */
        val MOOD_PENDING = setOf(
            "chat_mood_swings_net",
            "chat_mood_ask_high_signs",
            "chat_mood_option_less_sleep",
            "chat_mood_option_racing",
            "chat_mood_option_risks",
            "chat_mood_option_talking",
            "chat_mood_ask_life",
            "chat_mood_high_net",
            "chat_mood_alert_high_antidepressant",
        )

        /** The medicine threads' pending safety lines. */
        val MEDICINE_PENDING = setOf(
            "med_chat_ask_max",
            "med_chat_ask_max_course",
            "med_chat_ask_max_words",
            "med_chat_option_more",
            "med_chat_alert_too_much",
            "med_chat_ask_signs_tca",
            "med_chat_ask_signs_opioid",
            "med_chat_ask_signs_any",
            "med_chat_ask_purpose",
            "med_chat_option_by_accident",
            "med_chat_option_on_purpose",
            "med_chat_ask_crisis_start",
            "med_chat_ask_serious",
            "med_chat_serious_harm",
            "med_chat_serious_high",
            "med_chat_serious_heart",
            "med_chat_serious_heart_tca",
            "med_chat_serious_bleeding",
            "med_chat_serious_throat",
            "med_chat_serious_collapse",
            "med_chat_alert_serious",
            "med_chat_alert_side_effects",
            "med_chat_missed",
            "med_chat_missed_leaflet",
            "med_chat_alcohol_avoid",
            "med_chat_alcohol_limit",
            "med_chat_alcohol_sleepy",
            "med_chat_mix",
            "med_chat_mix_sjw",
            "med_chat_mix_nsaids",
            "med_chat_mix_paracetamol",
            "med_chat_mix_opioids",
            "med_chat_mix_tca_opioid",
            "med_chat_mix_not_together",
            "med_chat_mix_any_course",
            "med_chat_alert_stop",
            "med_chat_withdrawal_net",
            "med_chat_reminder_soon",
            "med_chat_missed_today",
            "med_chat_missed_today_several",
            "med_chat_start_safety",
            "med_chat_start_safety_tca",
        )

        val safetyPending = P1_PENDING + MOOD_PENDING + MEDICINE_PENDING

        /**
         * The chat model's lines: that a model reads the messages, and
         * behind the "?" that it may add one of the app's safety questions, never remove one — standing
         * only while a model does — the button that takes what a message told, and the three lines that
         * say the chat looks for a few words only as they read once a model reads a message in a
         * language its exam was passed in. Checked in Russian here, not yet in the fourteen other
         * languages every other safety line is held in.
         */
        val MODEL_PENDING = setOf(
            "symptom_chat_model_status",
            "symptom_chat_model_line",
            "symptom_chat_model_more",
            "symptom_chat_keep_told",
            "symptom_chat_no_match_model",
            "symptom_chat_remark_model",
            "symptom_chat_about_model",
        )
    }
}
