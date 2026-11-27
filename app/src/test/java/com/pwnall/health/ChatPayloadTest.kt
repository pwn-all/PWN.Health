package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat in its own row: written by hand in one fixed order, read back whole, a later build's keys
 * read past, and the thread's symptom row naming its chat instead of carrying it.
 */
class ChatPayloadTest {
    private val stroke = PainStroke(PainView.FRONT, 0.035f, listOf(Offset(0.64f, 0.615f), Offset(0.7f, 0.62f)))

    private val feverTag = SymptomTag(
        area = ProblemArea.TEMPERATURE,
        also = emptyList(),
        urgency = SymptomUrgency.SELF_CARE,
        course = ProblemCourse.NEW,
        recurring = false,
        impact = ProblemImpact.UNKNOWN,
        mind = false,
        flags = emptyList(),
        mentioned = listOf(Symptom.FEVER),
    )

    private val fever = SymptomReport(
        symptom = Symptom.FEVER,
        severity = SymptomSeverity.MILD,
        answers = mapOf(
            SymptomStep.WITH to listOf(SymptomOption.CHILLS),
            SymptomStep.ONSET to listOf(SymptomOption.STARTED_TODAY),
            SymptomStep.TREND to listOf(SymptomOption.NO_CHANGE),
        ),
        context = SymptomContext(sameDay = true, steps = 4_200),
        chat = SymptomChat(
            turns = listOf(
                ChatTurn.Wrote("Shivery since this morning"),
                ChatTurn.Asked(SymptomStep.SYMPTOM),
                ChatTurn.Asked(SymptomStep.WITH),
                ChatTurn.Asked(SymptomStep.ONSET),
                ChatTurn.Asked(SymptomStep.TREND),
                ChatTurn.Asked(SymptomStep.SEVERITY),
            ),
            ended = true,
        ),
        symptomTag = feverTag,
    )

    private val other = SymptomReport(
        symptom = Symptom.OTHER,
        severity = SymptomSeverity.MILD,
        answers = mapOf(
            SymptomStep.WITH to listOf(SymptomOption.NONE),
            SymptomStep.ONSET to listOf(SymptomOption.STARTED_YESTERDAY),
            SymptomStep.PATTERN to listOf(SymptomOption.ALL_THE_TIME),
            SymptomStep.AFFECTS to listOf(SymptomOption.NOT_REALLY),
        ),
        context = SymptomContext(sameDay = true, steps = 4_200),
        chat = SymptomChat(
            turns = listOf(
                SymptomStep.SYMPTOM,
                SymptomStep.WITH,
                SymptomStep.ONSET,
                SymptomStep.PATTERN,
                SymptomStep.AFFECTS,
                SymptomStep.SEVERITY,
            )
                .map { ChatTurn.Asked(it) },
            ended = true,
        ),
        symptomTag = feverTag.copy(area = ProblemArea.UNSORTED, impact = ProblemImpact.NONE, mentioned = emptyList()),
    )

    /** Golden 1: a calm chat of two symptom threads, as its row keeps it. */
    private val calm = ChatLog(
        threads = listOf(
            ChatThread(
                0,
                fever,
                from = "FEVER",
                filed = listOf(
                    FiledLink(KIND_SYMPTOM, at = 1_791_187_200_000, title = "Fever", sig = -1_418_806_272, wsig = 55),
                ),
            ),
            ChatThread(
                1,
                other,
                filed = listOf(
                    FiledLink(
                        KIND_SYMPTOM,
                        at = 1_791_190_800_000,
                        title = "Something else",
                        sig = 822_513_940,
                        wsig = -7,
                    ),
                ),
            ),
        ),
        next = 2,
        end = ChatEnd.ALL,
        rev = 14,
    )

    private val goldenOne = """{"schema":1,"rev":14,"threads":[""" +
        """{"id":0,"from":"FEVER","report":{"schema":2,"symptom":"FEVER","severity":"MILD",""" +
        """"answers":{"WITH":["CHILLS"],"ONSET":["STARTED_TODAY"],"TREND":["NO_CHANGE"]},""" +
        """"chat":{"turns":[{"text":"Shivery since this morning"},{"q":"SYMPTOM"},{"q":"WITH"},{"q":"ONSET"},""" +
        """{"q":"TREND"},{"q":"SEVERITY"}],"ended":true},"context":{"today":true,"steps":4200},""" +
        """"tag":{"v":1,"area":"TEMPERATURE","urgency":"SELF_CARE","course":"NEW","recurring":false,""" +
        """"impact":"UNKNOWN","mind":false,"flags":[],"mentioned":["FEVER"]}},""" +
        """"filed":[{"k":"SYMPTOM","at":1791187200000,"t":"Fever","sig":-1418806272,"wsig":55}]},""" +
        """{"id":1,"report":{"schema":2,"symptom":"OTHER","severity":"MILD",""" +
        """"answers":{"WITH":["NONE"],"ONSET":["STARTED_YESTERDAY"],"PATTERN":["ALL_THE_TIME"],""" +
        """"AFFECTS":["NOT_REALLY"]},""" +
        """"chat":{"turns":[{"q":"SYMPTOM"},{"q":"WITH"},{"q":"ONSET"},{"q":"PATTERN"},{"q":"AFFECTS"},""" +
        """{"q":"SEVERITY"}],"ended":true},"context":{"today":true,"steps":4200},""" +
        """"tag":{"v":1,"area":"UNSORTED","urgency":"SELF_CARE","course":"NEW","recurring":false,""" +
        """"impact":"NONE","mind":false,"flags":[]}},""" +
        """"filed":[{"k":"SYMPTOM","at":1791190800000,"t":"Something else","sig":822513940,"wsig":-7}]}""" +
        """],"next":2,"end":"ALL"}"""

    /** A pain thread the chat routed from words, with every optional key of a thread, a link and the chat. */
    private val pain = SymptomReport(
        symptom = Symptom.PAIN,
        answers = mapOf(SymptomStep.PAIN_INJURY to listOf(SymptomOption.NO)),
        values = mapOf(SymptomStep.PAIN_NRS to "6", SymptomStep.PAIN_AREA to "KNEE:R"),
        meta = mapOf("PAIN_AREA.here" to "1", "PAIN_AREA.earlier" to "1790000000000"),
        strokes = listOf(stroke),
        chat = SymptomChat(
            turns = listOf(
                ChatTurn.Wrote("my knee \"hurts\""),
                ChatTurn.Asked(SymptomStep.SYMPTOM, routed = true),
                ChatTurn.Asked(SymptomStep.PAIN_AREA),
                ChatTurn.Asked(SymptomStep.PAIN_INJURY, SymptomAlert.URGENT_NOW, "PAIN_X"),
                ChatTurn.Asked(SymptomStep.PAIN_NRS),
            ),
        ),
    )

    private val everyKey = ChatLog(
        threads = listOf(
            ChatThread(
                3,
                pain,
                from = "PAIN:KNEE",
                noRoute = true,
                filed = listOf(
                    FiledLink(KIND_PAIN, at = 1, title = "Pain", sig = 1, wsig = 2),
                    FiledLink(
                        LINK_COURSE,
                        role = "",
                        at = 2,
                        title = "Sertraline",
                        start = "2026-10-05",
                        sig = 3,
                        wsig = 4,
                    ),
                    FiledLink(
                        KIND_MED,
                        role = "dose:540",
                        at = 3,
                        title = "Sertraline",
                        slot = "540",
                        course = 2,
                        sig = 5,
                    ),
                    FiledLink(
                        KIND_PAIN,
                        role = "pain",
                        at = 4,
                        title = "Боль",
                        made = false,
                        prior = "{\"nrs\":5}",
                        sig = 6,
                    ),
                    FiledLink(KIND_MOOD, role = "a", at = 5, title = "Mood", state = LinkState.UNDONE),
                    FiledLink(KIND_MOOD, role = "b", at = 6, title = "Mood", state = LinkState.GONE),
                    FiledLink(KIND_MOOD, role = "c", at = 7, title = "Mood", state = LinkState.REFUSED),
                    FiledLink(KIND_MOOD, role = "d", at = 8, title = "Mood", state = LinkState.CHANGED),
                    FiledLink(KIND_MOOD, role = "e", at = 9, title = "Mood", state = LinkState.SUPERSEDED, err = true),
                ),
                draft = MapDraft(listOf(stroke), added = listOf("KNEE:R"), removed = listOf("CALF:L")),
            ),
        ),
        next = 4,
        end = ChatEnd.STOP,
        note = "Stood up\ttoo fast",
        past = true,
        rev = 7,
    )

    private val goldenTwo = """{"schema":1,"rev":7,"past":true,"threads":[""" +
        """{"id":3,"from":"PAIN:KNEE","noroute":true,"report":{"schema":2,"symptom":"PAIN",""" +
        """"answers":{"PAIN_INJURY":["NO"]},"values":{"PAIN_AREA":"KNEE:R","PAIN_NRS":"6"},""" +
        """"meta":{"PAIN_AREA.earlier":"1790000000000","PAIN_AREA.here":"1"},""" +
        """"strokes":[{"v":"F","r":35,"p":[640,615,700,620]}],""" +
        """"chat":{"turns":[{"text":"my knee \"hurts\""},{"q":"SYMPTOM","routed":true},{"q":"PAIN_AREA"},""" +
        """{"q":"PAIN_INJURY","alert":"URGENT_NOW","rule":"PAIN_X"},{"q":"PAIN_NRS"}]}},""" +
        """"filed":[{"k":"PAIN","at":1,"t":"Pain","sig":1,"wsig":2},""" +
        """{"k":"COURSE","at":2,"t":"Sertraline","start":"2026-10-05","sig":3,"wsig":4},""" +
        """{"k":"MED","r":"dose:540","at":3,"t":"Sertraline","slot":"540","course":2,"sig":5,"wsig":0},""" +
        """{"k":"PAIN","r":"pain","at":4,"t":"Боль","made":false,"prior":"{\"nrs\":5}","sig":6,"wsig":0},""" +
        """{"k":"MOOD","r":"a","at":5,"t":"Mood","sig":0,"wsig":0,"state":"UNDONE"},""" +
        """{"k":"MOOD","r":"b","at":6,"t":"Mood","sig":0,"wsig":0,"state":"GONE"},""" +
        """{"k":"MOOD","r":"c","at":7,"t":"Mood","sig":0,"wsig":0,"state":"REFUSED"},""" +
        """{"k":"MOOD","r":"d","at":8,"t":"Mood","sig":0,"wsig":0,"state":"CHANGED"},""" +
        """{"k":"MOOD","r":"e","at":9,"t":"Mood","sig":0,"wsig":0,"state":"SUPERSEDED","err":true}],""" +
        """"draft":{"strokes":[{"v":"F","r":35,"p":[640,615,700,620]}],"added":["KNEE:R"],"removed":["CALF:L"]}}""" +
        """],"next":4,"end":"STOP","note":"Stood up\ttoo fast"}"""

    @Test
    fun goldenOneByteForByte() {
        assertEquals(goldenOne, encodeChatLog(calm, pending = false))
        assertEquals(calm, decodeChatLog(goldenOne))
    }

    @Test
    fun goldenTwoByteForByte() {
        assertEquals(goldenTwo, encodeChatLog(everyKey, pending = false))
        assertEquals(everyKey, decodeChatLog(goldenTwo))
    }

    /** The maps in another order write the same bytes: the payload is part of nothing's identity by luck. */
    @Test
    fun bothGoldensWithTheirMapsShuffled() {
        fun <K, V> Map<K, V>.shuffled() = entries.reversed().associate { it.toPair() }
        val calmShuffled = calm.copy(
            threads = calm.threads.map { it.copy(report = it.report.copy(answers = it.report.answers.shuffled())) },
        )
        val everyShuffled = everyKey.copy(
            threads = everyKey.threads.map {
                it.copy(
                    report = it.report.copy(
                        answers = it.report.answers.shuffled(),
                        values = it.report.values.shuffled(),
                        meta = it.report.meta.shuffled(),
                    ),
                )
            },
        )
        assertEquals(goldenOne, encodeChatLog(calmShuffled, pending = false))
        assertEquals(goldenTwo, encodeChatLog(everyShuffled, pending = false))
    }

    /** Whatever the codec leaves out a rotation loses; every field set comes back from the saver whole. */
    @Test
    fun everyFieldSurvivesTheSaver() {
        val restored = decodeChatLog(encodeChatLog(everyKey, pending = true))
        assertEquals(everyKey, restored)
        // Every field of every link, the thread and the chat differs from its default here.
        val link = FiledLink(
            kind = KIND_MED,
            role = "dose:WAKE",
            at = 11,
            title = "t",
            start = "2026-10-01",
            slot = "WAKE",
            course = 12,
            made = false,
            prior = "p",
            sig = -1,
            wsig = -2,
            state = LinkState.CHANGED,
            err = true,
        )
        val log = everyKey.copy(threads = everyKey.threads.map { it.copy(filed = listOf(link)) })
        assertEquals(log, decodeChatLog(encodeChatLog(log, pending = true)))
    }

    /** The Bundle keeps ticks and values still waiting; the row keeps only what was taken. */
    @Test
    fun theBundleFormKeepsWaitingTicksAndTheDatabaseFormDropsThem() {
        val waiting = pain.copy(
            answers = pain.answers + (SymptomStep.PAIN_TYPES to listOf(SymptomOption.PAIN_ACHING)),
            values = pain.values + (SymptomStep.PAIN_LASTED to "90"),
            meta = pain.meta + ("PAIN_SAME.first" to "1"),
        )
        val log = everyKey.copy(threads = listOf(everyKey.threads.single().copy(report = waiting)))

        val bundle = checkNotNull(decodeChatLog(encodeChatLog(log, pending = true))).threads.single().report
        val row = checkNotNull(decodeChatLog(encodeChatLog(log, pending = false))).threads.single().report

        assertEquals(listOf(SymptomOption.PAIN_ACHING), bundle.answers[SymptomStep.PAIN_TYPES])
        assertEquals("90", bundle.values[SymptomStep.PAIN_LASTED])
        assertEquals("1", bundle.meta["PAIN_SAME.first"])
        assertNull(row.answers[SymptomStep.PAIN_TYPES])
        assertNull(row.values[SymptomStep.PAIN_LASTED])
        assertNull(row.meta["PAIN_SAME.first"])
        assertEquals(pain.values, row.values)
        assertEquals(listOf(stroke), row.strokes)
    }

    /** A later build's keys, kinds and states are read past, and nothing else with them. */
    @Test
    fun unknownKeysAndFutureLinkKindsAreSkipped() {
        val later = """{"schema":2,"rev":3,"colour":"blue","threads":[""" +
            """{"id":0,"report":{"schema":2,"symptom":"FEVER","later":{"x":1}},"shape":"round","filed":[""" +
            """{"k":"NOTE","at":1,"t":"x","sig":1,"wsig":1},""" +
            """{"k":"PAIN","at":2,"t":"Pain","sig":1,"wsig":1,"state":"ARCHIVED"},""" +
            """{"k":"MOOD","at":3,"t":"Mood","sig":1,"wsig":2,"extra":true},{"at":4}]},""" +
            """{"report":{}},{"id":5},""" +
            """{"id":7,"report":{"schema":2,"values":{"LATER":"x","PAIN_NRS":"4"},""" +
            """"meta":{"LATER.x":"1","PAIN_NRS.y":"2"}}}""" +
            """],"next":9,"end":"LATER"}"""

        val log = checkNotNull(decodeChatLog(later))

        assertEquals(listOf(0, 7), log.threads.map { it.id })
        assertEquals(listOf(FiledLink(KIND_MOOD, at = 3, title = "Mood", sig = 1, wsig = 2)), log.threads[0].filed)
        assertEquals(Symptom.FEVER, log.threads[0].report.symptom)
        assertEquals(mapOf(SymptomStep.PAIN_NRS to "4"), log.threads[1].report.values)
        assertEquals(mapOf("PAIN_NRS.y" to "2"), log.threads[1].report.meta)
        assertEquals(9 to 3, log.next to log.rev)
        assertNull(log.end)
        assertNull(decodeChatLog("not a chat"))
    }

    /** A thread's symptom row is schema 3: the chat named by its key, the earlier chat's moment, no note, no turns. */
    @Test
    fun theSymptomRowIsSchema3WithChatAndSameAndNoNote() {
        val told = fever.copy(chat = checkNotNull(fever.chat).copy(sameAs = 1_790_000_000_000))
        val row = symptomEntry("Fever", told, CHAT_TODAY, CHAT_TODAY, CHAT_NOW, chatKey = "0f8c")

        assertEquals(
            """{"schema":3,"symptom":"FEVER","severity":"MILD",""" +
                """"answers":{"WITH":["CHILLS"],"ONSET":["STARTED_TODAY"],"TREND":["NO_CHANGE"]},""" +
                """"chat":"0f8c","same":1790000000000,"context":{"today":true,"steps":4200},""" +
                """"tag":{"v":1,"area":"TEMPERATURE","urgency":"SELF_CARE","course":"NEW","recurring":false,""" +
                """"impact":"UNKNOWN","mind":false,"flags":[],"mentioned":["FEVER"]}}""",
            row.measurementPayloadJson,
        )
        assertEquals("", row.note)
        // Values, facts and a painting are the chat's to keep, never a symptom row's.
        val withParts = told.copy(
            values = mapOf(SymptomStep.PAIN_NRS to "3"),
            meta = mapOf("WITH.x" to "1"),
            strokes = listOf(stroke),
        )
        val payload = encodeSymptomPayload(withParts, chatKey = "0f8c")
        listOf("values", "meta", "strokes", "turns").forEach { assertFalse(it, "\"$it\"" in payload) }
        // Without a key the old forms stand: schema 2 with its chat, schema 1 for the page's.
        assertTrue(encodeSymptomPayload(told).startsWith("""{"schema":2,"""))
        assertTrue(encodeSymptomPayload(told.copy(chat = null, symptomTag = null)).startsWith("""{"schema":1,"""))
        // Read back, a schema-3 row has no chat but the key, and its answers as they were.
        val decoded = decodeSymptomPayload(row.measurementPayloadJson)
        assertNull(decoded.chat)
        assertEquals(told.answers, decoded.answers)
    }

    @Test
    fun chatKeyOfReadsOnlyAString() {
        fun row(payload: String?, kind: String = KIND_SYMPTOM) =
            JournalEntry(
                day = "2026-10-05",
                occurredAt = 1,
                kind = kind,
                title = "x",
                calories = 0.0,
                measurementPayloadJson = payload,
            )

        assertEquals("k-1", chatKeyOf(row("""{"schema":3,"chat":"k-1"}""")))
        assertNull(chatKeyOf(row("""{"schema":2,"chat":{"turns":[]}}""")))
        assertNull(chatKeyOf(row("""{"schema":3,"chat":7}""")))
        assertNull(chatKeyOf(row("""{"schema":3,"chat":""}""")))
        assertNull(chatKeyOf(row(null)))
        assertNull(chatKeyOf(row("broken")))
        assertNull(chatKeyOf(row("""{"chat":"k-1"}""", kind = KIND_PAIN)))
    }

    /** A turn the chat answered itself keeps saying so; an older build's turn reads as the person's. */
    @Test
    fun aRoutedTurnSurvives() {
        val report = newChat().copy(chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM, routed = true))))
        val payload = encodeSymptomPayload(report)

        assertTrue(""""turns":[{"q":"SYMPTOM","routed":true}]""" in payload)
        assertEquals(report.chat, decodeSymptomPayload(payload).chat)
        assertEquals(
            listOf(ChatTurn.Asked(SymptomStep.SYMPTOM)),
            decodeSymptomPayload("""{"schema":2,"chat":{"turns":[{"q":"SYMPTOM"}]}}""").chat?.turns,
        )
    }

    /** Facts are written in key order, kept while their step stands, and go with it when it is taken back. */
    @Test
    fun metaKeysAreSortedAndDroppedWithTheirStep() {
        val turns =
            listOf(
                ChatTurn.Asked(SymptomStep.SYMPTOM),
                ChatTurn.Asked(SymptomStep.PAIN_AREA),
                ChatTurn.Asked(SymptomStep.PAIN_SAME),
            )
        val report = SymptomReport(
            symptom = Symptom.PAIN,
            values = mapOf(SymptomStep.PAIN_AREA to "KNEE:R"),
            meta = mapOf("PAIN_SAME.first" to "9", "PAIN_AREA.here" to "1", "PAIN_AREA.earlier" to "8"),
            answers = mapOf(SymptomStep.PAIN_SAME to listOf(SymptomOption.PAIN_SAME_YES)),
            chat = SymptomChat(turns),
        )
        assertTrue(
            """"meta":{"PAIN_AREA.earlier":"8","PAIN_AREA.here":"1","PAIN_SAME.first":"9"}""" in
                encodeSymptomPayload(report),
        )

        // The same pain taken back: its own fact goes, the area's stay.
        val rewound = rewoundTo(report, 2)
        assertEquals(mapOf("PAIN_AREA.here" to "1", "PAIN_AREA.earlier" to "8"), rewound.meta)
        // The area taken back: its facts go too, to be worked out again; its value stays, shown chosen.
        val area = rewoundTo(report, 1)
        assertTrue(area.meta.isEmpty())
        assertEquals("KNEE:R", area.values[SymptomStep.PAIN_AREA])
        // A fact of a step not asked is no fact yet.
        assertEquals(emptyMap<String, String>(), report.copy(chat = SymptomChat(turns.take(1))).forSave().meta)
    }

    /**
     * A fact of the thread rather than of a step — the notes its close showed — stands through
     * [forSave] and the database form, and goes with any rewind, to be worked out again at the close.
     */
    @Test
    fun threadMetaSurvivesForSaveAndGoesWithARewind() {
        val turns = listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.PAIN_AREA))
        val report = SymptomReport(
            symptom = Symptom.PAIN,
            values = mapOf(SymptomStep.PAIN_AREA to "KNEE:R"),
            meta = mapOf("THREAD.notes" to "ALC:paracetamol", "PAIN_AREA.earlier" to "8", "COURSE_GATES" to "x"),
            chat = SymptomChat(turns),
        )

        // A key with no step's prefix and no thread's is nobody's, and is not kept.
        assertEquals(mapOf("THREAD.notes" to "ALC:paracetamol", "PAIN_AREA.earlier" to "8"), report.forSave().meta)
        val log = ChatLog(threads = listOf(ChatThread(0, report)), next = 1)
        val stored = checkNotNull(decodeChatLog(encodeChatLog(log, pending = false))).threads.single().report
        assertEquals("ALC:paracetamol", stored.meta["THREAD.notes"])
        // Any rewind, even one that keeps every step's facts, takes the thread's away.
        assertNull(rewoundTo(report, 1).meta["THREAD.notes"])
        assertEquals(emptyMap<String, String>(), rewoundTo(stored, 1).meta.filterKeys { it.startsWith("THREAD.") })
    }

    /**
     * The painting not yet sent is written into the chat's row as the page stops; after the process
     * is killed the row reads back with the strokes and the codes added and struck off by hand, for the
     * map to open on.
     */
    @Test
    fun aDraftWrittenAtOnStopComesBackAfterProcessDeathAndReopensTheMap() {
        val draft = MapDraft(listOf(stroke), added = listOf("CALF:L"), removed = listOf("KNEE:R"))
        val asking = pain.copy(chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM))))
        val open = ChatThread(0, asking, draft = draft)
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, TestPlans({ emptyMap() }))

        persist(filing, ChatLog(threads = listOf(open), next = 1, rev = 3), key)

        val reopened = stored(store, key).threads.single()
        assertEquals(draft, reopened.draft)
        // Not an answer: the area question is still open, and nothing of the painting is in the report.
        assertFalse(SymptomStep.PAIN_AREA in checkNotNull(reopened.report.chat).askedSteps())
        assertTrue(reopened.report.strokes.isEmpty())
    }

    /** The unsent painting is the chat's only: no thread's report, so no record built from one, carries it. */
    @Test
    fun draftNeverReachesARecord() {
        val log = everyKey.copy(
            threads = listOf(everyKey.threads.single().copy(report = pain.copy(strokes = emptyList()))),
        )
        val decoded = checkNotNull(decodeChatLog(encodeChatLog(log, pending = false))).threads.single()

        assertEquals(everyKey.threads.single().draft, decoded.draft)
        assertTrue(decoded.report.strokes.isEmpty())
        assertFalse("\"draft\"" in encodeSymptomPayload(decoded.report))
        assertFalse("CALF:L" in encodeSymptomPayload(decoded.report))
    }

    /**
     * A card's amount that is no number is written as nought: `NaN` or `Infinity` is no JSON, and the
     * device's org.json would refuse the whole chat over it.
     */
    @Test
    fun anAmountThatIsNoNumberLeavesTheChatReadable() {
        val key = JournalKey(0, ChatJournalType.WATER_LOG, 0, 5)
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val thread = everyKey.threads.single()
            val added = thread.copy(cards = mapOf(key to CardMark.Added("WATER", 1, 2, "Water", amount)))
            val payload = encodeChatLog(everyKey.copy(threads = listOf(added)), pending = false)

            assertTrue(payload, "\"amt\":0.0}" in payload)
            val card = checkNotNull(decodeChatLog(payload)).threads.single().cards
            assertEquals(mapOf(key to CardMark.Added("WATER", 1, 2, "Water", 0.0)), card)
        }
    }

    /** The saver is the codec in its Bundle form. */
    @Test
    fun theSaverIsTheBundleForm() {
        val saved = with(CHAT_LOG_SAVER) { SaverScope { true }.save(everyKey) }
        assertEquals(encodeChatLog(everyKey, pending = true), saved)
        assertEquals(everyKey, CHAT_LOG_SAVER.restore(checkNotNull(saved)))
    }
}
