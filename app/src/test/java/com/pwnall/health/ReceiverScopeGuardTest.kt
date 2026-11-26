package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the app's coroutines are held, read from the source, as [AppEntryIntentTest] reads the intents:
 * the rules a new receiver or a new write keeps only because its author remembered them.
 */
class ReceiverScopeGuardTest {
    private val sources: Map<String, String> = File("src/main/java/com/pwnall/health").walkTopDown()
        .filter { it.extension == "kt" }
        .associate { it.name to it.readText() }

    /**
     * GlobalScope launches what nothing cancels and nothing sees fail; runBlocking holds a thread —
     * the main one, as often as not — for as long as the database takes.
     */
    @Test
    fun noCoroutineEscapesEveryScopeOrBlocksAThread() {
        val found = sources.filterValues { "GlobalScope" in it || "runBlocking" in it }.keys

        assertEquals(emptySet<String>(), found)
    }

    /**
     * A broadcast held open with goAsync is the system waiting on the app: one never finished is an
     * ANR, and the receiver's process killed ten seconds on. Each is let go in a finally, so a check
     * that threw or ran out of time lets it go too.
     */
    @Test
    fun everyBroadcastHeldOpenIsLetGoWhateverHappens() {
        val held = sources.flatMap { (name, text) -> text.split("goAsync()").drop(1).map { name to it } }

        assertTrue("no goAsync was found", held.isNotEmpty())
        held.forEach { (name, after) ->
            val body = after.substringBefore("pending.finish()", missingDelimiterValue = "")
            assertTrue(name, body.trimEnd().endsWith("} finally {"))
        }
    }
}
