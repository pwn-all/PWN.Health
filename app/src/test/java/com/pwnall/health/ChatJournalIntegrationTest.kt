package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ChatJournalIntegrationTest {
    private fun event(type: ChatJournalType, assertion: String = "AFFIRMED", subject: String = "SELF") =
        ChatJournalEvent(type, ChatJournalSpan(0, 4, "test"), mapOf("subject" to subject, "assertion" to assertion))

    @Test
    fun nativeDecoderUsesTheSameOwnershipAsThePythonReference() {
        val schema = ChatModelSchema.parse(java.io.File(ChatModelTestFiles.ml, "schema-journal.json").readText())
        assertNull(schema.misfit())
        assertEquals(10, schema.outputNames.size)
        val text = "lift 10 lift 20"
        val tokenizer = ChatModelTestFiles.tokenizer
        val encoding = tokenizer.encode(text)
        val windows = tokenizer.windows(encoding, tokenizer.contextIds(ChatReaderContext()))
        val window = windows.single()
        val values = nativeOutputs(schema, encoding, window)
        val empty = FloatArray(0)
        val logits = WindowLogits(empty, empty, empty, empty, empty, window.inputIds.size, values)
        val events = decodeJournal(text, encoding, windows, listOf(logits), schema, ChatModelCalibration())
        assertEquals(listOf("lift", "lift"), events.map { it.anchor.text })
        assertEquals(listOf(listOf("10"), listOf("20")), events.map { e -> e.arguments.map { it.span.text } })
        assertTrue(events.all { it.groundedIn(text) })
    }

    private fun nativeOutputs(
        schema: ChatModelSchema,
        encoding: ChatEncoding,
        window: ChatWindow,
    ): Map<String, FloatArray> {
        val count = window.inputIds.size
        val out = JOURNAL_OUTPUTS.associateWith { FloatArray(count * schema.outputSizes.getValue(it)) { -9f } }
        val role = JOURNAL_ROLES.indexOf("LOAD")
        repeat(count) { p ->
            out.getValue(JOURNAL_OUTPUTS[0])[p * 3] = 9f
            JOURNAL_ROLES.indices.forEach { r ->
                out.getValue(JOURNAL_OUTPUTS[2])[(p * JOURNAL_ROLES.size + r) * 3] = 9f
            }
        }
        for ((anchor, value, sign) in listOf(Triple(0 until 4, 5 until 7, 3), Triple(8 until 12, 13 until 15, -3))) {
            val anchors = encoding.ids.indices.filter {
                encoding.starts[it] >= anchor.first && encoding.ends[it] <= anchor.last + 1 &&
                    encoding.starts[it] < encoding.ends[it]
            }
            val args = encoding.ids.indices.filter {
                encoding.starts[it] >= value.first && encoding.ends[it] <= value.last + 1 &&
                    encoding.starts[it] < encoding.ends[it]
            }
            markNative(out.getValue(JOURNAL_OUTPUTS[0]), anchors.map { it + window.prefix }, 3, 0)
            markNative(
                out.getValue(JOURNAL_OUTPUTS[2]),
                args.map { it + window.prefix },
                JOURNAL_ROLES.size * 3,
                role * 3,
            )
            anchors.forEach { setNativeAttributes(out.getValue(JOURNAL_OUTPUTS[1]), it + window.prefix) }
            setNativeProjection(out.getValue(JOURNAL_OUTPUTS[3]), anchors.map { it + window.prefix }, role, sign)
            setNativeProjection(out.getValue(JOURNAL_OUTPUTS[4]), args.map { it + window.prefix }, role, sign)
        }
        return out
    }

    private fun setNativeAttributes(values: FloatArray, position: Int) {
        val targets = mapOf(
            "record_type" to "WORKOUT_LOG",
            "subject" to "SELF",
            "assertion" to "AFFIRMED",
            "phase" to "DONE",
            "operation" to "REPORT",
        )
        var offset = 0
        for ((name, labels) in JOURNAL_ATTRIBUTES) {
            values[position * 50 + offset + labels.indexOf(targets.getValue(name))] = 9f
            offset += labels.size
        }
    }

    private fun setNativeProjection(values: FloatArray, positions: List<Int>, role: Int, sign: Int) {
        positions.forEach { position ->
            val start = (position * JOURNAL_ROLES.size + role) * 8
            repeat(8) { d -> values[start + d] = sign.toFloat() }
        }
    }

    private fun markNative(values: FloatArray, positions: List<Int>, stride: Int, offset: Int) {
        positions.forEachIndexed { index, p ->
            values[p * stride + offset] = -9f
            values[p * stride + offset + if (index == 0) 1 else 2] = 9f
        }
    }

    @Test
    fun everyRecordTypeHasAConfirmationDestination() {
        assertEquals(32, ChatJournalType.entries.size)
        ChatJournalType.entries.forEach { assertNotNull(it.name, event(it).destination()) }
        assertEquals(ChatJournalDestination.DOCUMENT, event(ChatJournalType.ATTACHMENT).destination())
        assertEquals(ChatJournalDestination.LABS, event(ChatJournalType.LAB_RESULT_LOG).destination())
    }

    @Test
    fun negatedUncertainAndOtherPersonFactsDoNotOfferPersonalWrites() {
        for (type in ChatJournalType.entries) {
            assertNull(event(type, assertion = "NEGATED").destination())
            assertNull(event(type, assertion = "UNCERTAIN").destination())
            assertNull(event(type, subject = "CHILD").destination())
        }
        val planned = event(ChatJournalType.WORKOUT_LOG).let {
            it.copy(
                attributes =
                it.attributes + ("phase" to "PLANNED"),
            )
        }
        assertNull(planned.destination())
    }

    @Test
    fun eventOwnershipAndAttachmentMetadataSurviveChatPersistence() {
        val events = listOf(
            event(
                ChatJournalType.WEIGHT_LOG,
            ).copy(arguments = listOf(ChatJournalArgument("VALUE", ChatJournalSpan(5, 7, "70")))),
            event(
                ChatJournalType.WORKOUT_LOG,
            ).copy(arguments = listOf(ChatJournalArgument("LOAD", ChatJournalSpan(8, 10, "20")))),
        )
        val read = ChatRead(model = "fixture", journal = events)
        assertEquals(events, decodeChatRead(org.json.JSONObject(encodeChatRead(read))).journal)
        val files = storeMedicalAttachments(listOf(MedicalAttachment("content://doc", AttachmentKind.LAB)))
        val log = ChatLog(attachments = files)
        assertEquals(log, decodeChatLog(encodeChatLog(log, pending = false)))
        val row = ChatRow("chat-a", "2026-10-09", 1, 1, encodeChatLog(log, pending = false))
        assertEquals(setOf("content://doc"), namedAttachments(emptyList(), emptyList(), emptyList(), listOf(row)))
    }

    @Test
    fun shadowReadingsAndWrongTextOffsetsCannotOfferForms() {
        val proposal = event(ChatJournalType.WEIGHT_LOG)
        val turn = ChatTurn.Wrote("test", ChatRead(model = "fixture", shadow = true, journal = listOf(proposal)))
        val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 170, birthDate = "1990-01-01")
        assertTrue(journalProposals(turn, journalTypesOffered(profile, LocalDate.of(2026, 10, 9))).isEmpty())
        assertTrue(proposal.groundedIn("test"))
        assertFalse(proposal.groundedIn("next"))
    }

    /** The cycle's records are offered as its page is: to a woman's profile, never to a man's or one not said. */
    @Test
    fun theCyclesRecordsAreOfferedToAWomanOnly() {
        val day = LocalDate.of(2026, 10, 9)
        val cycle = setOf(ChatJournalType.PERIOD_START, ChatJournalType.PERIOD_END, ChatJournalType.CYCLE_NOTE)
        val woman = UserProfile(sex = "FEMALE", weightKg = 60.0, heightCm = 165, birthDate = "1990-01-01")
        assertTrue(journalTypesOffered(woman, day).containsAll(cycle))
        for (sex in listOf("MALE", "")) {
            assertTrue(sex, journalTypesOffered(woman.copy(sex = sex), day).none { it in cycle })
        }
    }

    /**
     * A journal form is offered only for a message in a language its package enables journal proposals
     * in, the model sure of it, whether or not the chat routes by the model there; a package without
     * that list offers them where it routes.
     */
    @Test
    fun journalFormsAreOfferedOnlyInTheJournalLanguages() {
        val fixture = File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText()
        val routed = JSONObject(fixture).put("routing_languages", JSONArray(listOf("en", "ko"))).toString()
        val journal = JSONObject(routed).put("journal_languages", JSONArray(listOf("en", "ru")))
        val gated = ChatModelManifest.parse(journal.toString())
        val unlisted = ChatModelManifest.parse(routed)
        val proposal = event(ChatJournalType.WEIGHT_LOG)
        val day = LocalDate.of(2026, 10, 9)
        fun offered(manifest: ChatModelManifest, language: String, p: Double = 0.9): List<ChatJournalEvent> {
            val reading = readingOf().copy(lang = ReaderLabel(language, p), journal = listOf(proposal))
            val turn = ChatTurn.Wrote("test", reading.enabledBy(manifest).decided(day, past = false))
            val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 170, birthDate = "1990-01-01")
            return journalProposals(turn, journalTypesOffered(profile, day))
        }
        val form = listOf(proposal.copy(sourceText = "test"))
        assertEquals(form, offered(gated, "en"))
        assertEquals(form, offered(gated, "ru")) // not routed there: the journal's gate is its own
        assertTrue(offered(gated, "ko").isEmpty()) // routed there, its journal gate not passed
        assertTrue(offered(gated, "ar").isEmpty())
        assertTrue(offered(gated, "en", p = 0.4).isEmpty()) // a language the model is not sure of
        assertEquals(form, offered(unlisted, "ko"))
        assertTrue(offered(unlisted, "ru").isEmpty())
    }
}
