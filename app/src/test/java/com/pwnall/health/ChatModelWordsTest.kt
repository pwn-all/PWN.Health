package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * What the chat says of how it reads: the lines that say it looks for a few words
 * only — "found none it knows", the long remark during a question, what the chat is behind its "?" — are
 * said in a model's words only where they would otherwise be untrue: once a model that is not the
 * pipeline's smoke run read the message in a language its exam was passed in, its manifest's `languages`
 * ([ChatRead.examined]). Without a model, in the shadow, past its second, after a failure, and in a language
 * no exam vouches for, the chat keeps its own words.
 */
class ChatModelWordsTest {
    private val setting = chatSetting()

    /** A message the rules read nothing in: no key of any lexicon, no question, no topic. */
    private val nothing = "hmm"

    /** A model's reading that found nothing, abstaining, as the decoder makes it of a language passed. */
    private val abstained = chatRead { copy(why = ChatReadOutcome.ABSTAIN) }

    /** The decisions kept by a reading of a language its exam was not passed in: the flags alone. */
    private val unexamined = chatRead { copy(why = ChatReadOutcome.UNEXAMINED) }

    /** What is kept when the model had no reading in time: the reason, no model. */
    private val timedOut = ChatRead(why = ChatReadOutcome.TIMEOUT)

    private fun opening(read: ChatRead?) = chatTurn(ChatLog().wroteRead(nothing, read), setting).lines()

    /** Why the reading kept with the first message of [this] chat kept to the rules, if it did. */
    private fun ChatLog.messageWhy(): String? =
        threads.first().report.chat?.turns?.firstNotNullOfOrNull { (it as? ChatTurn.Wrote)?.read?.why }

    @Test
    fun foundNoneIsInAModelsWordsOnlyForAMessageAModelReadInALanguagePassed() {
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), opening(null))
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), opening(chatRead()))
        // Finding nothing is what a model abstaining is: still a model that read the message.
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), opening(abstained))
        listOf(unexamined, timedOut, chatRead { copy(shadow = true) }).forEach { read ->
            assertEquals("$read", listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), opening(read))
        }
        // The second time in a row it says it more plainly, a model or none.
        val twice = ChatLog().wroteRead(nothing, chatRead()).wroteRead(nothing, chatRead())
        assertEquals(listOf(ChatLine.NO_MATCH_AGAIN, ChatLine.SAFETY), chatTurn(twice, setting).lines())
    }

    /** A chat of one thread asks its topic question after words the same way, from its own words' lines. */
    @Test
    fun aChatOfOneThreadSaysItTheSameWay() {
        val bare = newChat().wrote(nothing).turn().lines()
        assertTrue("$bare", ChatLine.NO_MATCH in bare)
        val read = newChat().wrote(nothing, chatRead()).turn().lines()
        assertEquals(bare.map { if (it == ChatLine.NO_MATCH) ChatLine.NO_MATCH_MODEL else it }, read)
        assertEquals(bare, newChat().wrote(nothing, unexamined).turn().lines())
    }

    /** The history draws it from what is kept with the message, as the live chat said it. */
    @Test
    fun theHistorySaysItAsTheLiveChatDid() {
        val read = ChatLog().wroteRead(nothing, chatRead()).choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val lines = chatTranscript(read, setting, live = false).filterIsInstance<ChatItem.Bot>()
            .flatMap { it.turn.lines() }
        assertTrue("$lines", ChatLine.NO_MATCH_MODEL in lines)
        assertFalse("$lines", ChatLine.NO_MATCH in lines)
        val bare = chatTranscript(read.withoutReadings(), setting, live = false).filterIsInstance<ChatItem.Bot>()
            .flatMap { it.turn.lines() }
        assertTrue("$bare", ChatLine.NO_MATCH in bare)
        assertFalse("$bare", ChatLine.NO_MATCH_MODEL in bare)
    }

    @Test
    fun theRemarkDuringAQuestionIsInAModelsWordsOnlyForAMessageAModelRead() {
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(SymptomStep.PAIN_AREA, knee.asked())
        fun remark(read: ChatRead?) = chatTurn(knee.wroteRead(nothing, read), setting).lines()
        assertEquals(listOf(ChatLine.REMARK, ChatLine.SAFETY), remark(null))
        assertEquals(listOf(ChatLine.REMARK_MODEL, ChatLine.SAFETY), remark(chatRead()))
        assertEquals(listOf(ChatLine.REMARK_MODEL, ChatLine.SAFETY), remark(abstained))
        listOf(unexamined, timedOut, chatRead { copy(shadow = true) }).forEach { read ->
            assertEquals("$read", listOf(ChatLine.REMARK, ChatLine.SAFETY), remark(read))
        }
        // The short one after, whoever read the first.
        val again = knee.wroteRead(nothing, chatRead()).wroteRead(nothing, chatRead())
        assertEquals(listOf(ChatLine.REMARK_SHORT, ChatLine.SAFETY), chatTurn(again, setting).lines())
    }

    /**
     * Through the page's own path — read by a reader, the decisions kept, the turn settled with them: a
     * reading of a language passed that found nothing says so in a model's words; the same reading of a
     * language no exam vouches for keeps to the chat's own, and says it is one ([ChatReading.decided]).
     */
    @Test
    fun aReaderOfALanguagePassedIsWhatTheWordsGoBy() = runBlocking {
        val passed = scriptedReader(ScriptedEngine { readingOf() })
        val read = ChatLog().sentWith(passed, nothing, setting)
        assertEquals(ChatReadOutcome.ABSTAIN, read.messageWhy())
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), chatTurn(read, setting).lines())
        val other = scriptedReader(ScriptedEngine { readingOf().copy(examined = false) })
        val kept = ChatLog().sentWith(other, nothing, setting)
        assertEquals(ChatReadOutcome.UNEXAMINED, kept.messageWhy())
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), chatTurn(kept, setting).lines())
        passed.close()
        other.close()
    }

    /**
     * A reading that abstains in a language no exam vouches for says it is of such a language: a kept
     * abstention is always one a model made in a language passed, which is what the chat's words go by.
     */
    @Test
    fun aLanguageNotPassedSaysSoEvenWhenItAbstains() {
        val day = CHAT_TODAY
        val abstaining = readingOf(flags = setOf(ReaderSafety.CRISIS))
        assertTrue(abstaining.abstain)
        val passed = abstaining.decided(day, past = false)
        assertEquals(ChatReadOutcome.ABSTAIN, passed.why)
        assertTrue(passed.examined)
        val other = abstaining.copy(examined = false).decided(day, past = false)
        assertEquals(
            ChatRead(TEST_MODEL, why = ChatReadOutcome.UNEXAMINED, safety = listOf(SymptomStep.CRISIS_NOW)),
            other,
        )
        assertFalse(other.examined)
        // Both keep the flag's question all the same: the words differ, never what is asked.
        assertEquals(passed.safety, other.safety)
    }

    /**
     * The chat's "?" says what the chat is: in a model's words while a model reads in full here — on, its
     * exam passed in the app's language, which is known once it has loaded — or once it read something of
     * this chat so, a message, an answer typed or the note; else the chat's own words.
     */
    @Test
    fun theAboutGoesByTheModelAndTheLanguageTheAppSpeaks() = runBlocking {
        val reader = scriptedReader(ScriptedEngine { readingOf() })
        val ready = ChatModelState.Ready(TEST_MODEL, setOf("ru", "en"))
        val russian = Locale.forLanguageTag("ru-RU")
        assertTrue(chatModelHold(reader, ready, ChatModelMode.ON).readsInFull(russian))
        assertFalse(chatModelHold(reader, ready, ChatModelMode.ON).readsInFull(Locale.GERMAN))
        // Loading, nothing is known of its languages yet; in the shadow nothing it reads is used.
        assertFalse(chatModelHold(reader, null, ChatModelMode.ON).readsInFull(russian))
        assertFalse(chatModelHold(reader, ready, ChatModelMode.SHADOW).readsInFull(russian))
        val gone = ChatModelState.Unavailable(ChatModelUnavailable.RUN_FAILED)
        assertFalse(chatModelHold(reader, gone, ChatModelMode.ON).readsInFull(russian))
        // A model that passed no exam reads in full in no language.
        assertFalse(chatModelHold(reader, ChatModelState.Ready(TEST_MODEL), ChatModelMode.ON).readsInFull(russian))
        reader.close()

        assertEquals(R.string.symptom_chat_about, chatAboutRes(false))
        assertEquals(R.string.symptom_chat_about_model, chatAboutRes(true))
        assertFalse(ChatLog().readExamined())
        assertFalse(ChatLog().wroteRead(nothing, null).readExamined())
        assertTrue(ChatLog().wroteRead(nothing, chatRead()).readExamined())
        assertTrue(ChatLog().wroteRead(nothing, abstained).readExamined())
        assertFalse(ChatLog().wroteRead(nothing, unexamined).readExamined())
        assertFalse(ChatLog().wroteRead(nothing, timedOut).readExamined())
        assertTrue(ChatLog(note = "ok", noteRead = chatRead()).readExamined())
        assertFalse(ChatLog(note = "ok", noteRead = unexamined).readExamined())
    }

    /** The app's language as the reader's language head names it, Indonesian's old name and all. */
    @Test
    fun theAppsLanguageIsNamedAsTheReaderNamesIt() {
        assertEquals("id", readerLanguage(Locale.forLanguageTag("in-ID")))
        assertEquals("id", readerLanguage(Locale.forLanguageTag("id-ID")))
        assertEquals("zh", readerLanguage(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("pt", readerLanguage(Locale.forLanguageTag("pt-BR")))
        assertEquals("ru", readerLanguage(Locale.forLanguageTag("ru")))
        assertEquals("en", readerLanguage(Locale.ENGLISH))
    }

    /**
     * The languages a model's package names are what its loaded state carries, so the page knows where it
     * reads in full: the fixture's own package names none, and with a list named the same model has it.
     */
    @Test
    fun theLoadedModelCarriesTheLanguagesItsPackageNames() = runBlocking {
        val files = ChatModelTestFiles.fixtureFiles()
        val model = ChatModelManifest.parse(fixtureManifest()).model
        val plain = ChatModelReader(files, mode = ChatModelMode.ON, allowSmoke = true, threads = 1)
        assertEquals(ChatModelState.Ready(model, emptySet()), plain.state())
        plain.close()
        val reader = ChatModelReader(fixtureNaming("ru", "en"), mode = ChatModelMode.ON, allowSmoke = true, threads = 1)
        val state = reader.state()
        assertEquals(ChatModelState.Ready(model, setOf("ru", "en")), state)
        val hold = chatModelHold(reader, state, ChatModelMode.ON)
        assertTrue(hold.readsInFull(Locale.forLanguageTag("en-GB")))
        assertFalse(hold.readsInFull(Locale.forLanguageTag("ja-JP")))
        reader.close()
    }

    /**
     * The pipeline's smoke model never brings a model's words, whatever languages its package names: the
     * chat's own reader refuses it ([ChatModelReaders.of]), so the message keeps no reading to say them
     * by, the turn waits for none, and the "?" keeps the chat's own words — the chat as it is without one.
     */
    @Test
    fun aSmokeModelNeverBringsAModelsWords() = runBlocking {
        val smoke = ChatModelReader(fixtureNaming("ru", "en"), mode = ChatModelMode.ON, allowSmoke = false, threads = 1)
        val state = smoke.state()
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.SMOKE), state)
        assertFalse(chatModelHold(smoke, state, ChatModelMode.ON).readsInFull(Locale.ENGLISH))
        val sent = ChatLog().sentWith(smoke, nothing, setting)
        val message = sent.threads.single().report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>().single()
        assertEquals(null, message.read)
        assertFalse(sent.readExamined())
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), chatTurn(sent, setting).lines())
        smoke.close()
    }

    /** The fixture's own manifest, which names no language. */
    private fun fixtureManifest(): String = File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText()

    /** The fixture's package with its manifest naming [languages] as the ones its exam was passed in. */
    private fun fixtureNaming(vararg languages: String): ChatModelFiles {
        val files = ChatModelTestFiles.fixtureFiles()
        val named = JSONObject(fixtureManifest()).put("languages", JSONArray(languages.toList())).toString()
        return object : ChatModelFiles {
            override fun open(name: String): InputStream? =
                if (name == CHAT_MODEL_MANIFEST) named.byteInputStream() else files.open(name)

            override fun map(name: String): ByteBuffer? = files.map(name)
        }
    }
}
