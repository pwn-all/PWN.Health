package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams main keeps for the tests. [MedicineClock] is read by the medicine threads and set only
 * by a test, which freezes it for a chat and has to set it going again: the classes share one JVM,
 * and a frozen clock left behind is every later class's clock. Each class that freezes it sets it
 * going again in an `@After`, and the first test here holds every one of them to that.
 * [ChatModelReader.failures] is read by the tests alone. Both are marked for Lint, and main assigns
 * neither.
 *
 * [StepsDb.builder] is no seam: it is the app's own open, which the migration tests go through.
 */
class TestSeamsTest {
    private fun sources(dir: String) = File(dir).listFiles { file -> file.extension == "kt" }.orEmpty().toList()

    @Test
    fun aClassThatFreezesTheMedicineClockSetsItGoingAgain() {
        val freezing = sources("src/test/java/com/pwnall/health")
            .filter { it.name != "MedicineChatDriver.kt" && it.name != "TestSeamsTest.kt" }
            .filter { file -> file.readText().let { DRIVEN.containsMatchIn(it) || FROZEN.containsMatchIn(it) } }
        assertTrue(freezing.size >= 12)
        val leaving = freezing.filterNot { file ->
            val text = file.readText()
            "@After" in text && RESET.all { it in text }
        }
        assertEquals(emptyList<String>(), leaving.map { it.name })
    }

    @Test
    fun onlyTheTestsSetTheSeams() {
        val main = sources("src/main/java/com/pwnall/health")
        assertTrue(main.size > 100)
        val setting = main.filter { Regex("""MedicineClock\.(now|zone)\s*=""").containsMatchIn(it.readText()) }
        assertEquals(emptyList<String>(), setting.map { it.name })
        val clock = File("src/main/java/com/pwnall/health/ChatMedicine.kt").readText()
            .substringAfter("internal object MedicineClock {").substringBefore("\n}")
        assertEquals(2, Regex("""@set:VisibleForTesting\s+var (now|zone):""").findAll(clock).count())
        val reader = File("src/main/java/com/pwnall/health/ChatModelReader.kt").readText()
        assertTrue(Regex("""@get:VisibleForTesting\s+val failures:""").containsMatchIn(reader))
    }

    private companion object {
        /** The medicine chat of MedicineChatDriver.kt, which freezes the clock as it opens. */
        val DRIVEN = Regex("""\bMedicineChat\(""")

        /** A clock set to a moment of the test's choosing rather than the system's. */
        val FROZEN = Regex("""MedicineClock\.(now|zone)\s*=\s*\{""")

        val RESET =
            listOf("MedicineClock.now = System::currentTimeMillis", "MedicineClock.zone = ZoneId::systemDefault")
    }
}
