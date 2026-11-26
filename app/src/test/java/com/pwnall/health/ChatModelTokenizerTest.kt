package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * The Kotlin tokenizer against the Python reference tokenizer. The model was trained on what the
 * Python reference produces, so a piece that comes out differently here
 * is a word the phone reads differently from how the model learnt it: these tests want the same
 * ids, the same pieces and the same UTF-16 offsets for every case, not most of them.
 */
class ChatModelTokenizerTest {
    private val tokenizer = ChatModelTestFiles.tokenizer
    private val golden = ChatModelTestFiles.json(File(ChatModelTestFiles.ml, "tokenizer/tokenizer-golden.json"))
    private val cases = golden.getJSONArray("cases").jsonObjects()

    @Test
    fun theGoldenIsForTheFixturesReferenceTokenizer() {
        val shipped = ChatModelTestFiles.referenceFile(CHAT_MODEL_TOKENIZER).readBytes()
        assertEquals(golden.getString("tokenizer_sha256"), sha256(shipped))
        assertEquals(golden.getInt("count"), cases.size)
        assertEquals(70_925, tokenizer.vocabSize)
    }

    /** All 878 cases: added pieces, emoji, joiners, harakat, Hindi, CJK, controls and long messages. */
    @Test
    fun everyGoldenCaseHasTheReferencesIdsPiecesAndOffsets() {
        val wrong = cases.mapNotNull { case ->
            val text = case.getString("text")
            val encoding = tokenizer.encode(text)
            val offsets = case.getJSONArray("offsets").offsetPairs()
            val piecesJson = case.getJSONArray("pieces")
            val pieces = (0 until piecesJson.length()).map { piecesJson.getString(it) }
            val ok = encoding.ids.contentEquals(case.getJSONArray("ids").intArray()) &&
                encoding.pieces == pieces &&
                encoding.starts.toList() == offsets.map { it.first } &&
                encoding.ends.toList() == offsets.map { it.second }
            if (ok) null else "${case.getString("id")}: ${encoding.pieces} vs $pieces"
        }
        assertEquals(wrong.take(10).joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun windowsAreTheReferencesForTheirContext() {
        val withWindows = cases.filter { it.has("windows") }
        assertTrue(withWindows.size >= 100)
        val long = withWindows.filter { it.getJSONArray("windows").length() > 1 }
        assertTrue("some case is read in more than one window", long.isNotEmpty())
        for (case in withWindows) {
            val encoding = tokenizer.encode(case.getString("text"))
            val context = tokenizer.contextIds(case.getJSONObject("context").readerContext())
            val windows = tokenizer.windows(encoding, context)
            val expected = case.getJSONArray("windows").jsonObjects()
            assertEquals(case.getString("id"), expected.size, windows.size)
            for ((window, want) in windows.zip(expected)) {
                assertEquals(case.getString("id"), want.getInt("start"), window.start)
                assertEquals(case.getString("id"), want.getInt("end"), window.end)
                val ids = want.getJSONArray("input_ids").intArray().toList()
                assertEquals(case.getString("id"), ids, window.inputIds.map(Long::toInt))
                assertTrue(window.inputIds.size <= tokenizer.maxPositions)
            }
        }
    }

    /**
     * A message past one window is read in overlapping windows that end where it ends: nothing is
     * cut off, the overlap is the file's, and every piece is in some window.
     */
    @Test
    fun aLongMessageIsReadToItsLastPiece() {
        val text = "今天工作非常忙，老板一直在催项目的进度，同事们也都很累。".repeat(60) + "我不想活了"
        val encoding = tokenizer.encode(text)
        val windows = tokenizer.windows(encoding, tokenizer.contextIds(ChatReaderContext()))
        assertTrue(windows.size >= 3)
        assertEquals(encoding.size, windows.last().end)
        for ((earlier, later) in windows.zipWithNext()) assertEquals(tokenizer.overlap, earlier.end - later.start)
        for (t in 0 until encoding.size) ownerWindow(windows, t)
        // The last piece takes its outputs from the last window, the only one that holds it.
        assertEquals(windows.size - 1, ownerWindow(windows, encoding.size - 1))
    }

    /** Context tokens: a step the model was not taught by name is OTHER, none is NONE. */
    @Test
    fun theContextTokensSayWhereTheConversationIs() {
        fun names(context: ChatReaderContext) = tokenizer.contextIds(context).toList()
        val start = names(ChatReaderContext())
        assertEquals(listOf(5, 35, 56), start) // [W=START] [S=NONE] [T=NONE], as the golden's empty message has them
        val painArea = names(ChatReaderContext(ChatReaderWhere.QUESTION, SymptomStep.PAIN_AREA, Symptom.PAIN))
        val chestNow = names(ChatReaderContext(ChatReaderWhere.QUESTION, SymptomStep.CHEST_NOW, Symptom.CHEST_PAIN))
        assertEquals(painArea[0], chestNow[0])
        assertEquals(34, chestNow[1]) // [S=OTHER]: CHEST_NOW is not one of the 25 steps it reads
        assertTrue(painArea[1] != chestNow[1] && painArea[2] != chestNow[2])
        // Every topic the app has is a thread topic the model knows.
        for (topic in Symptom.entries) names(ChatReaderContext(topic = topic))
    }

    /**
     * Every place the chat can be in, and every question it can ask there, is one the model has words
     * for: a place without its token would throw in every prepare and turn the model off for the
     * opening, which the warm-up, read at the start alone, would never show.
     */
    @Test
    fun everyPlaceAndQuestionOfTheChatHasItsContextTokens() {
        for (where in ChatReaderWhere.entries) {
            for (step in SymptomStep.entries + null) {
                assertEquals("$where $step", 3, tokenizer.contextIds(ChatReaderContext(where, step)).size)
            }
        }
    }

    /**
     * Characters the vocabulary has no piece for — Egyptian hieroglyphs here — are one <unk>
     * however many stand in a row, spanning all of them (the reference's own answer).
     */
    @Test
    fun unknownCharactersInARowAreOneUnknown() {
        val encoding = tokenizer.encode("a𓀀𓀁b")
        assertEquals(listOf(63, tokenizer.unk, 323), encoding.ids.toList())
        assertEquals(listOf("▁a", "𓀀𓀁", "b"), encoding.pieces)
        assertEquals(listOf(0, 1, 5), encoding.starts.toList())
        assertEquals(listOf(1, 5, 6), encoding.ends.toList())
    }

    /** "<s>" typed in a message is three characters, never the special token. */
    @Test
    fun specialTokensAreNeverReadFromText() {
        val encoding = tokenizer.encode("<s> </s> <pad> <unk> <mask> [W=START] [T=PAIN]")
        assertTrue(encoding.ids.toList().toString(), encoding.ids.none { it < CONTEXT_END })
    }

    /**
     * Every offset lands in the raw text and moves forward, and no letter or digit is left out of
     * every piece. (Pieces may share a span: "Ⅻ" is the three pieces of "XII" on one character.)
     */
    @Test
    fun offsetsCoverTheRawTextInOrder() {
        for (case in cases) {
            val id = case.getString("id")
            val text = case.getString("text")
            val encoding = tokenizer.encode(text)
            val covered = BooleanArray(text.length)
            for (t in 0 until encoding.size) {
                assertTrue(id, encoding.starts[t] in 0..encoding.ends[t] && encoding.ends[t] <= text.length)
                if (t >
                    0
                ) {
                    assertTrue(
                        id,
                        encoding.starts[t] >= encoding.starts[t - 1] && encoding.ends[t] >= encoding.ends[t - 1],
                    )
                }
                for (i in encoding.starts[t] until encoding.ends[t]) covered[i] = true
            }
            val lost = text.indices.filter { text[it].isLetterOrDigit() && !covered[it] }
            assertEquals("$id lost ${lost.map { text[it] }}", emptyList<Int>(), lost)
        }
    }

    /**
     * Offsets in UTF-16 of the raw text, through composed letters, emoji and expanding forms — the
     * reference's own answers, so a span the model marks shows the person's own words.
     */
    @Test
    fun offsetsPointAtWhatWasTyped() {
        fun read(text: String) = tokenizer.encode(text).let { e ->
            (0 until e.size).map { Triple(e.pieces[it], e.starts[it], e.ends[it]) }
        }
        // é typed as e + U+0301 is one character to the model and two to the String.
        assertEquals(
            listOf(Triple("▁café", 0, 5), Triple("▁fi", 6, 8), Triple("è", 8, 10), Triple("vre", 10, 13)),
            read("café fièvre"),
        )
        // A surrogate pair stays whole; the joiner inside 😵‍💫 is a space to the model, and a piece
        // that is only ▁ has the empty span at the end of the space it stands for.
        assertEquals(
            listOf(
                Triple("▁мне", 0, 3),
                Triple("▁плохо", 4, 9),
                Triple("▁", 10, 10),
                Triple("😵", 10, 12),
                Triple("▁", 13, 13),
                Triple("💫", 13, 15),
            ),
            read("мне плохо 😵‍💫"),
        )
        // ½ is three pieces to the model ("1⁄2") and one character of the String.
        assertEquals(
            listOf(Triple("▁1", 0, 1), Triple("⁄", 0, 1), Triple("2", 0, 1), Triple("▁таблетки", 2, 10)),
            read("½ таблетки"),
        )
        // Leading tabs and spaces belong to no piece.
        assertEquals(listOf(Triple("▁hi", 3, 5)), read("\t  hi"))
    }

    /**
     * What the ASCII fast path in the normaliser rests on: no canonical composite ends in an ASCII
     * character, so an ASCII character never composes onto what came before it. Checked on this
     * JVM's own tables over every code point.
     */
    @Test
    fun noCanonicalCompositeEndsInAscii() {
        val composites = (0x80..0x10FFFF).filter { it !in 0xD800..0xDFFF }.filter { cp ->
            val nfd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            nfd.length >= 2 && nfd.last().code < 0x80
        }
        assertEquals(composites.map(Integer::toHexString).toString(), 0, composites.size)
    }

    /**
     * The parity run: ≥ 10 000 lines per language, written by the Python reference, compared id for
     * id and offset for offset. The file is not in the repository (its texts' licences): name it in
     * CHAT_READER_PARITY.
     */
    @Test
    fun parityWithTheReferenceOnTheCorpusLines() {
        val path = System.getenv("CHAT_READER_PARITY")
        assumeTrue("set CHAT_READER_PARITY to the parity lines' file", path != null && File(path).isFile)
        val lines = HashMap<String, Int>()
        val wrong = ArrayList<String>()
        File(requireNotNull(path)).forEachLine { line ->
            val case = JSONObject(line)
            val encoding = tokenizer.encode(case.getString("text"))
            val offsets = case.getJSONArray("offsets").offsetPairs()
            val same = encoding.ids.contentEquals(case.getJSONArray("ids").intArray()) &&
                encoding.starts.toList() == offsets.map { it.first } &&
                encoding.ends.toList() == offsets.map { it.second }
            if (!same) wrong += "${case.getString("lang")}: ${case.getString("text").take(60)}"
            lines.merge(case.getString("lang"), 1, Int::plus)
        }
        println("chat reader tokenizer parity: ${lines.values.sum()} lines, ${wrong.size} differ")
        // 10 000 in each of the file's sixteen languages, not 10 000 in all.
        assertEquals(lines.toString(), 16, lines.count { it.value >= 10_000 })
        assertEquals(wrong.take(10).joinToString("\n"), 0, wrong.size)
    }

    private fun org.json.JSONArray.offsetPairs(): List<Pair<Int, Int>> =
        (0 until length()).map { getJSONArray(it).let { pair -> pair.getInt(0) to pair.getInt(1) } }

    private companion object {
        /** Ids below this are the five special tokens and the 52 context tokens. */
        const val CONTEXT_END = 57
    }
}
