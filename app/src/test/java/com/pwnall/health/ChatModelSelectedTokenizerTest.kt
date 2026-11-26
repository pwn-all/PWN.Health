package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChatModelSelectedTokenizerTest {
    @Test
    fun shippedTokenizerMatchesPythonIdsOffsetsAndWindows() {
        val bytes = File(ChatModelTestFiles.assets, CHAT_MODEL_TOKENIZER).readBytes()
        val tokenizer = ChatModelTokenizer.parse(bytes.toString(Charsets.UTF_8))
        // The golden written for the tokenizer that ships: the v1 tokenizer's or the lifestyle one's.
        val golden = listOf("tokenizer-golden.json", "tokenizer-lifestyle-golden.json")
            .map { ChatModelTestFiles.json(File(ChatModelTestFiles.ml, "tokenizer/$it")) }
            .single { it.getString("tokenizer_sha256") == sha256(bytes) }
        val cases = golden.getJSONArray("cases").jsonObjects()
        assertEquals(golden.getInt("count"), cases.size)
        for (case in cases) {
            val name = case.getString("id")
            val encoded = tokenizer.encode(case.getString("text"))
            assertEquals(name, case.getJSONArray("ids").intArray().toList(), encoded.ids.toList())
            val pieces = case.getJSONArray("pieces")
            assertEquals(name, (0 until pieces.length()).map(pieces::getString), encoded.pieces)
            val offsets = case.getJSONArray("offsets")
            val starts = (0 until offsets.length()).map { offsets.getJSONArray(it).getInt(0) }
            val ends = (0 until offsets.length()).map { offsets.getJSONArray(it).getInt(1) }
            assertEquals(name, starts, encoded.starts.toList())
            assertEquals(name, ends, encoded.ends.toList())
            if (case.has("windows")) {
                val context = tokenizer.contextIds(case.getJSONObject("context").readerContext())
                val windows = tokenizer.windows(encoded, context)
                val expected = case.getJSONArray("windows").jsonObjects()
                assertEquals(name, expected.size, windows.size)
                for ((actual, wanted) in windows.zip(expected)) {
                    assertEquals(name, wanted.getInt("start"), actual.start)
                    assertEquals(name, wanted.getInt("end"), actual.end)
                    val ids = wanted.getJSONArray("input_ids").intArray().toList()
                    assertEquals(name, ids, actual.inputIds.map(Long::toInt))
                }
            }
        }
    }
}
