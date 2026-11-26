package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every list a person reads — on a screen, in a notification, read out by a screen reader, on the
 * doctor's sheet or in the chat — is parted by `list_comma`, the comma of the language showing: "、"
 * in Japanese and Chinese, "،" in Arabic. No screen or report keeps a string of its own for it, and no
 * list writes ", " into the code. What is written to a file or to the database keeps its plain comma;
 * those few joins are counted here by file, so that a new one is a choice made in this test rather
 * than a comma nobody noticed.
 */
class ListCommaTest {
    private val sources = File("src/main/java/com/pwnall/health").listFiles { file -> file.extension == "kt" }
        .orEmpty()
        .toList()

    @Test
    fun noListAPersonReadsIsJoinedWithAPlainComma() {
        assertTrue(sources.size > 100)
        val plain = sources.associate { it.name to PLAIN_JOIN.findAll(it.readText()).count() }.filterValues { it > 0 }
        assertEquals(WRITTEN_DOWN, plain)
    }

    @Test
    fun eachLanguageHasOneListCommaOfItsOwn() {
        val values = File("src/main/res").listFiles { dir -> dir.name == "values" || dir.name.startsWith("values-") }
            .orEmpty()
            .filter { File(it, "ui.xml").isFile }
        assertEquals(16, values.size)
        values.forEach { dir ->
            val strings = dir.listFiles { file -> file.extension == "xml" }.orEmpty()
                .joinToString("\n") { it.readText() }
            val commas = Regex("""<string name="(\w*list_comma)">([^<]*)</string>""").findAll(strings)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            val own = when (dir.name) {
                "values-ja", "values-zh" -> "、"
                "values-ar" -> "،\\u0020"
                else -> ",\\u0020"
            }
            assertEquals(dir.name, listOf("list_comma" to own), commas)
        }
    }

    private companion object {
        val PLAIN_JOIN = Regex("""joinToString\(\s*(?:separator\s*=\s*)?", """")

        /** The joins that write a file or a row, not a line anybody reads in the app. */
        val WRITTEN_DOWN = mapOf(
            // The ticks of a partnered act and of a solo one, kept in the row's subtitle for the backup
            // file; the day list names them from the payload (sexNotesText).
            "CycleScreen.kt" to 1,
            "Sexual.kt" to 1,
            // A facility's name and address, filled into the appointment's place, which the person edits
            // and the database keeps as typed.
            "Facilities.kt" to 1,
            // A mood row's note; the day list names the mood by its score.
            "JournalEntries.kt" to 1,
            // A GeoJSON position.
            "RouteExport.kt" to 1,
        )
    }
}
