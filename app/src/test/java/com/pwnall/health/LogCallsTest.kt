package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/medical-export/PRIVACY.md` promises no health data in logs: every logging call is a warning
 * with a constant message and a throwable, and the three in the Bluetooth code that append something
 * append a protocol token, an error code and a count. A count by hand drifts from the code.
 *
 * So every call is read here. A warning whose message is a plain string literal passes; any other
 * message is held to the list below, which is the document's three and the chat reader's reason
 * code, so a value written into a log line fails until somebody has said what it carries.
 */
class LogCallsTest {
    private data class LogCall(val file: String, val level: String, val message: String, val throwable: Boolean)

    private val calls: List<LogCall> = File("src/main/java/com/pwnall/health").walkTopDown()
        .filter { it.extension == "kt" }
        .flatMap { file ->
            val text = file.readText()
            CALL.findAll(text).map { call ->
                val inside = arguments(text, call.range.last + 1).replace(Regex("""\s+"""), " ")
                val parsed = ARGUMENTS.find(inside) ?: error("${file.name}: a log call this test cannot read: $inside")
                LogCall(file.name, call.groupValues[1], parsed.groupValues[1], parsed.groupValues[2].isNotEmpty())
            }
        }
        .toList()

    @Test
    fun everyLogCallIsAWarning() {
        assertTrue("the sources are read: ${calls.size}", calls.size > 10)
        assertEquals(emptyList<LogCall>(), calls.filter { it.level != "w" })
    }

    @Test
    fun aMessageThatIsNotAConstantIsOneThePrivacyNoteNames() {
        val variable = calls.filterNot { CONSTANT.matches(it.message) }.map { "${it.file}: ${it.message}" }.toSet()
        assertEquals(NAMED, variable)
    }

    /** A constant says what failed; the throwable says why, which is the only thing logcat is for here. */
    @Test
    fun aConstantMessageComesWithItsThrowable() {
        assertEquals(emptyList<LogCall>(), calls.filter { CONSTANT.matches(it.message) && !it.throwable })
    }

    /** The text between the parentheses of a call opened at [start], a string's own parentheses left alone. */
    private fun arguments(text: String, start: Int): String {
        var depth = 1
        var quoted = false
        var index = start
        while (depth > 0) {
            val char = text[index]
            when {
                char == '\\' && quoted -> index++
                char == '"' -> quoted = !quoted
                !quoted && char == '(' -> depth++
                !quoted && char == ')' -> depth--
            }
            index++
        }
        return text.substring(start, index - 1)
    }

    private companion object {
        val CALL = Regex("""\bLog\.([a-z]+)\(""")

        /** A tag without commas, the message, and whatever follows it: the throwable. */
        val ARGUMENTS = Regex("""^\s*(?:"[^"]*"|[^,"]+),\s*("(?:[^"\\]|\\.)*"|[^,]+?)\s*(?:,\s*(.+?))?\s*,?\s*$""")

        /** A string literal with no template in it. */
        val CONSTANT = Regex(""""(?:[^"\\$]|\\.)*"""")

        val NAMED = setOf(
            "DirectBluetooth.kt: \"Search failed beside an open link: \$errorCode\"",
            "DirectBluetooth.kt: \"Connection failed: \$detail\"",
            "DirectBluetooth.kt: \"Glucose sync: \$unreadable unreadable\"",
            // A ChatModelUnavailable's code: one of a few fixed words, never the message read.
            "ChatModelReader.kt: why.code",
        )
    }
}
