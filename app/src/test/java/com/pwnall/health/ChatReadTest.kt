package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chat keeps of its model's reading of a message: the decisions with the model's version,
 * in one fixed order beside the message in the chat's
 * payload — never a probability — read back whatever a later build added, and a payload from before
 * there was a model reading byte for byte as it was.
 */
class ChatReadTest {
    private val full = ChatRead(
        model = "chat-reader test",
        safety = listOf(SymptomStep.CRISIS_NOW, SymptomStep.BREATH_NOW),
        topics = listOf(Symptom.PAIN, Symptom.DOSE),
        add = listOf(Symptom.PAIN),
        unwell = true,
        about = ReadAbout.CHILD,
        told = ReadTold(
            places = listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)),
            onset = SymptomOption.STARTED_YESTERDAY,
            types = listOf(PainType.SHARP),
            noticed = SymptomOption.NOTICED_ON_WAKING,
            status = SymptomOption.STILL_HURTS,
            same = SymptomOption.PAIN_NEW,
            signs = listOf(SymptomOption.SUDDEN_SEVERE),
            intensity = listOf(ReadIntensity.SEVERE),
            nrs = 7,
            painkiller = true,
            medicines = listOf(ReadMedicine("Нурофен \"плюс\"", listOf(ReadRole.TAKEN))),
            amounts = listOf("2 таблетки"),
            clocks = listOf(510),
        ),
    )

    private val golden =
        """{"model":"chat-reader test","safety":["CRISIS_NOW","BREATH_NOW"],"topics":["PAIN","DOSE"],""" +
            """"add":["PAIN"],"unwell":true,"about":"CHILD",""" +
            """"told":{"places":[{"family":"KNEE","area":"KNEE","side":"R"}],""" +
            """"onset":"STARTED_YESTERDAY","types":["SHARP"],"noticed":"NOTICED_ON_WAKING","status":"STILL_HURTS",""" +
            """"same":"PAIN_NEW","signs":["SUDDEN_SEVERE"],"intensity":["SEVERE"],"nrs":7,"painkiller":true,""" +
            """"medicines":[{"name":"Нурофен \"плюс\"","roles":["TAKEN"]}],"amounts":["2 таблетки"],"clocks":[510]}}"""

    @Test
    fun aReadingIsWrittenInOneFixedOrder() {
        assertEquals(golden, encodeChatRead(full))
        assertEquals(full, decodeChatRead(JSONObject(golden)))
        // Every key only when set: no reading at all is an empty object, a reason alone is the reason alone.
        assertEquals("{}", encodeChatRead(ChatRead()))
        assertEquals("""{"why":"timeout"}""", encodeChatRead(ChatRead(why = "timeout")))
        assertEquals(
            """{"model":"m","why":"abstain","shadow":true}""",
            encodeChatRead(ChatRead("m", "abstain", shadow = true)),
        )
    }

    /** Keys in any order, as a later build or another writer may put them, read the same. */
    @Test
    fun theReadingReadsTheSameWithItsKeysShuffled() {
        val shuffled = JSONObject()
        val parsed = JSONObject(golden)
        parsed.keyNames().reversed().forEach { shuffled.put(it, parsed.get(it)) }
        assertEquals(full, decodeChatRead(shuffled))
    }

    /** The message keeps its reading in the chat's payload, after its words, and both come back. */
    @Test
    fun aMessageKeepsItsReadingInTheChatsPayload() {
        val log = ChatLog().wrote("my knee", full)
        val payload = encodeChatLog(log, pending = false)
        assertTrue(payload, """{"text":"my knee","read":$golden}""" in payload)
        val back = checkNotNull(decodeChatLog(payload))
        assertEquals(log, back)
        assertEquals(full, (back.threads.single().report.chat?.turns?.single() as ChatTurn.Wrote).read)
        // The rotation's form keeps it the same.
        assertEquals(log, CHAT_LOG_SAVER.restore(encodeChatLog(log, pending = true)))
    }

    /**
     * A payload written before the chat had a model — every message without a reading — reads as it
     * did and is written again byte for byte: no message gains a reading, so the rules alone read it.
     */
    @Test
    fun aPayloadFromBeforeTheModelReadsAsItWas() {
        val old =
            """{"schema":1,"rev":2,"threads":[{"id":0,"from":"PAIN:KNEE","report":{"schema":2,"symptom":"PAIN",""" +
                """"chat":{"turns":[{"text":"болит колено"},{"q":"SYMPTOM","routed":true}]}}}],"next":1,"note":"ok"}"""
        val log = checkNotNull(decodeChatLog(old))
        assertEquals(old, encodeChatLog(log, pending = false))
        val message = log.threads.single().report.chat?.turns?.first() as ChatTurn.Wrote
        assertEquals(ChatTurn.Wrote("болит колено"), message)
        assertNull(message.read)
        assertNull(log.noteRead)
        assertFalse(log.routesByReadings())
    }

    /**
     * What this build does not know is skipped, each by itself: an unknown key, a topic or an option
     * of a later build, a safety step that is not one of the seven a flag adds — no payload makes a
     * reading ask anything else — a number out of ten past ten, a time past the day, a place of no area.
     */
    @Test
    fun whatThisBuildDoesNotKnowIsSkipped() {
        val later = """{"model":"m","later":{"x":1},"safety":["PAIN_AREA","BLOOD","CRISIS","SYMPTOM"],""" +
            """"topics":["SNEEZING","FEVER"],"about":"ROBOT","told":{"nrs":11,"clocks":[480,1440,-1],""" +
            """"places":[{"area":"ELBOW_TIP"},{"family":"KNEE","side":"UP"}],"onset":"STARTED_LATER",""" +
            """"medicines":[{"name":" "},{"name":"aspirin","roles":["TAKEN","EATEN"]}],"amounts":["",5]}}"""
        val read = decodeChatRead(JSONObject(later))
        assertEquals("m", read.model)
        assertEquals(listOf(SymptomStep.BLOOD), read.safety)
        assertEquals(listOf(Symptom.FEVER), read.topics)
        assertNull(read.about)
        assertNull(read.told.nrs)
        assertEquals(listOf(480), read.told.clocks)
        assertEquals(listOf(ReadPlace(family = "KNEE")), read.told.places)
        assertNull(read.told.onset)
        assertEquals(listOf(ReadMedicine("aspirin", listOf(ReadRole.TAKEN))), read.told.medicines)
        assertTrue(read.told.amounts.isEmpty())
        // A turn whose reading is not an object at all is the message alone.
        val turn = """{"schema":1,"rev":0,"threads":[{"id":0,"report":{"schema":2,"chat":{"turns":""" +
            """[{"text":"hi","read":"broken"}]}}}],"next":1}"""
        assertEquals(
            ChatTurn.Wrote("hi"),
            checkNotNull(decodeChatLog(turn)).threads.single().report.chat?.turns?.single(),
        )
    }

    /**
     * The note keeps its reading while it is the note read; a note changed goes without it. Opened
     * again from the history, the note shows the card its reading added, as the live chat showed it
     * (the past chat draws the note's cards through the one function the live chat draws them with,
     * its stored reading among them).
     */
    @Test
    fun theNoteKeepsItsReadingWhileItIsTheSameNote() {
        val flag = chatRead { copy(safety = listOf(SymptomStep.CRISIS_NOW)) }
        val noted = ChatLog(end = ChatEnd.ALL).noted("  tired of it all ")
        assertNull(noted.noteReadAs("something else", flag))
        val kept = checkNotNull(noted.noteReadAs("tired of it all", flag))
        assertEquals(flag, kept.noteRead)
        val payload = encodeChatLog(kept, pending = false)
        assertTrue(payload, payload.endsWith(""""note":"tired of it all","noteread":${encodeChatRead(flag)}}"""))
        assertEquals(kept, decodeChatLog(payload))
        val past = checkNotNull(decodeChatLog(payload))
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT.textRes), past.noteCards().map { it.first })
        // Live, while the note is still the one read; a draft of other words goes by its words alone.
        assertEquals(past.noteCards(), past.noteCards(" tired of it all"))
        assertTrue(past.noteCards("better now").isEmpty())
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT.textRes), past.noteCards("не хочу жить").map { it.first })
        assertEquals(flag, kept.noted("tired of it all").noteRead)
        assertNull(kept.noted("better now").noteRead)
    }

    /**
     * What a chat holds for a reading goes into its payload, in one fixed form, and comes back; one a
     * later build wrote with a step this one does not know, or with no words, is nothing held — and a
     * payload holding nothing is the payload it was.
     */
    @Test
    fun whatTheChatHoldsIsKeptInItsPayload() {
        val log = ChatLog().wrote("hmm")
        val message = log.copy(held = ChatHeld.Message(MessagePlace(0, 0)))
        val payload = encodeChatLog(message, pending = false)
        val held = """"held":{"thread":0,"message":0}"""
        assertTrue(payload, payload.endsWith(""","next":1,$held}"""))
        assertEquals(message, decodeChatLog(payload))
        val typed = log.copy(held = ChatHeld.Typed(SymptomStep.MED_NAME, "Нурофен \"плюс\""))
        val words = encodeChatLog(typed, pending = false)
        assertTrue(words, words.endsWith(""""held":{"step":"MED_NAME","text":"Нурофен \"плюс\""}}"""))
        assertEquals(typed, decodeChatLog(words))
        val later = payload.replace(held, """"held":{"step":"LATER","text":"x"}""")
        assertNull(checkNotNull(decodeChatLog(later)).held)
        val blank = payload.replace(held, """"held":{"step":"MED_NAME","text":" "}""")
        assertNull(checkNotNull(decodeChatLog(blank)).held)
        assertFalse("held" in encodeChatLog(log, pending = false))
    }

    /**
     * A reading is kept on the very message it read, found by its place — its thread and which of the
     * thread's messages it is — never by its words: the same words sent again are another message.
     * Nothing else moves; a place read already, holding other words or gone takes nothing.
     */
    @Test
    fun aReadingIsKeptOnTheMessageItRead() {
        val flag = chatRead { copy(safety = listOf(SymptomStep.BREATH_NOW)) }
        val log = ChatLog().wrote("hello").wrote("hello").wrote("again")
        val id = log.threads.single().id
        fun reads(of: ChatLog) = of.threads.single().report.chat?.turns.orEmpty().map { (it as ChatTurn.Wrote).read }
        val first = checkNotNull(log.readMessage(MessagePlace(id, 0), "hello", flag))
        assertEquals(listOf(flag, null, null), reads(first))
        assertEquals(MessagePlace(id, 2), log.lastMessagePlace())
        val second = checkNotNull(first.readMessage(MessagePlace(id, 1), "hello", flag))
        assertEquals(listOf(flag, flag, null), reads(second))
        assertNull(second.readMessage(MessagePlace(id, 0), "hello", flag))
        assertNull(log.readMessage(MessagePlace(id, 2), "hello", flag))
        assertNull(log.readMessage(MessagePlace(id + 1, 0), "hello", flag))
        assertNull(log.readMessage(MessagePlace(id, 3), "again", flag))
        assertNotNull(log.readMessage(MessagePlace(id, 2), " again ", flag))
    }

    /**
     * A message read while an answer is taken back keeps its place, and its reading goes to it — not to
     * the same words sent again after the rewind, which the words alone would have found newest.
     */
    @Test
    fun aRewindWhileAMessageIsReadKeepsItsPlace() {
        val flag = chatRead { copy(safety = listOf(SymptomStep.CRISIS_NOW)) }
        val knee = ChatLog().wrote("болит колено").settle().give("KNEE:R")
        val sent = knee.wrote("hmm").settle()
        val place = checkNotNull(sent.lastMessagePlace())
        val thread = sent.threads.single()
        val area = thread.report.chat?.turns.orEmpty().indexOfFirst {
            it is ChatTurn.Asked && it.step == SymptomStep.PAIN_AREA
        }
        val again = sent.rewoundLog(thread.id, area).wrote("hmm").settle()
        val kept = checkNotNull(again.readMessage(place, "hmm", flag))
        val messages = messagesOf(kept.threads.single().report.chat)
        assertEquals(listOf("болит колено", "hmm", "hmm"), messages.map { it.text })
        assertEquals(listOf(null, flag, null), messages.map { it.read })
    }

    /** A reading the chat does not route by — from the shadow, or with no model behind it — adds nothing. */
    @Test
    fun aReadingOfTheShadowOrOfNoModelRoutesNothing() {
        assertTrue(chatRead().routes)
        assertFalse(chatRead { copy(shadow = true) }.routes)
        assertFalse(ChatRead(why = "timeout", safety = listOf(SymptomStep.CRISIS_NOW)).routes)
        assertFalse(ChatRead(why = "timeout", safety = listOf(SymptomStep.CRISIS_NOW)).raises(SymptomStep.CRISIS_NOW))
        assertFalse(
            chatRead {
                copy(shadow = true, safety = listOf(SymptomStep.CRISIS_NOW))
            }.raises(SymptomStep.CRISIS_NOW),
        )
        assertTrue(chatRead { copy(safety = listOf(SymptomStep.CRISIS_NOW)) }.raises(SymptomStep.CRISIS_NOW))
    }
}
