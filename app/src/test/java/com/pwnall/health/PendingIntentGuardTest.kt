package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A PendingIntent lets whoever holds it — the alarm service, the launcher, the app that reads the
 * notification shade — send its intent as this app. Each one the app makes, read from the source:
 * immutable, so the holder cannot fill in an action, a component or an extra of its own, and aimed
 * at a class of the app's own ([openAppIntent] names MainActivity), so no other app can answer it.
 */
class PendingIntentGuardTest {
    @Test
    fun everyPendingIntentIsImmutableAndNamesItsComponent() {
        val calls = File("src/main/java/com/pwnall/health").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                PENDING_INTENT.findAll(text).map { "${file.name}: ${arguments(text, it.range.last)}" }
            }
            .toList()

        assertTrue("no PendingIntent was found", calls.isNotEmpty())
        calls.forEach { call ->
            assertTrue(call, "PendingIntent.FLAG_IMMUTABLE" in call)
            assertFalse(call, "FLAG_MUTABLE" in call)
            assertTrue(call, EXPLICIT_INTENT.containsMatchIn(call) || "openAppIntent(" in call)
        }
    }

    /** What stands between the parenthesis at [open] in [text] and the one that closes it. */
    private fun arguments(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, index)
            }
        }
        error("no parenthesis closes the call at $open")
    }

    private companion object {
        val PENDING_INTENT = Regex("""PendingIntent\.get(?:Activities|Activity|Broadcast|Service)\(""")

        /** `Intent(context, SomeReceiver::class.java)`, or a class handed in as a parameter. */
        val EXPLICIT_INTENT = Regex("""\bIntent\(\w+, \w+(?:::class\.java)?\)""")
    }
}
