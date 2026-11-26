package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The line under the home greeting shares its row with the bell and the level badge, so it
 * is kept short: thirty characters at most, in every language, and one a day in turn —
 * so every locale carries the same number of lines.
 */
class HomeMotivationTest {
    private fun lines(file: File): List<String> {
        val array = Regex("""<string-array name="home_motivations">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1).orEmpty()
        return Regex("""<item>(.*?)</item>""").findAll(array).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyLocaleHasTheSameShortLines() {
        val files = File("src/main/res").listFiles { dir -> dir.name.startsWith("values") }.orEmpty()
            .map { File(it, "redesign.xml") }
            .filter(File::exists)
        val english = lines(File("src/main/res/values/redesign.xml"))

        assertTrue(english.size >= 3)
        assertEquals(16, files.size)
        files.forEach { file ->
            val shown = lines(file)
            assertEquals(file.path, english.size, shown.size)
            shown.forEach { line -> assertTrue("${file.path}: “$line” is ${line.length}", line.length <= 30) }
        }
    }
}
