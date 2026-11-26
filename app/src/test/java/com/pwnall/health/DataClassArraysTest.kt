package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No data class in main holds an array. The equals and hashCode a data class is given compare an
 * array by its identity, so two Colmi frames with the same bytes, or two equal mood faces, would be
 * unequal: a data class holding an array is a plain class in all but name.
 */
class DataClassArraysTest {
    @Test
    fun noDataClassHoldsAnArray() {
        val sources = File("src/main/java/com/pwnall/health").listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(sources.size > 100)
        val holding = sources.flatMap { file ->
            val text = file.readText()
            Regex("""\bdata class (\w+)(?:<[^>]*>)?\s*\(""").findAll(text)
                .filter { ARRAY.containsMatchIn(parameters(text, it.range.last)) }
                .map { "${file.name}: ${it.groupValues[1]}" }
                .toList()
        }
        assertEquals(emptyList<String>(), holding)
    }

    /** The parameter list that opens at [open], up to the parenthesis that closes it. */
    private fun parameters(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, index)
            }
        }
        return text.substring(open)
    }

    private companion object {
        val ARRAY = Regex("""\b(?:Byte|Short|Int|Long|Float|Double|Boolean|Char)Array\b|\bArray<""")
    }
}
