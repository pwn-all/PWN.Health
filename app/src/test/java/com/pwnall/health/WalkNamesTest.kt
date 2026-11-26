package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A walk's name is drawn from walk_names when its recording starts, and an older route is given one
 * by when it started — so every language needs as many names as English, or the same old walk would
 * be called something else after a change of language. The name is the walk page's title: short, and never the
 * same name twice in one language, or a draw of two would be no draw.
 */
class WalkNamesTest {
    private fun names(file: File): List<String> {
        val array = Regex("""<string-array name="walk_names">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1).orEmpty()
        return Regex("""<item>(.*?)</item>""").findAll(array).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyLocaleHasAsManyShortDistinctNames() {
        val files = File("src/main/res").listFiles { dir -> dir.name.startsWith("values") }.orEmpty()
            .map { File(it, "walk.xml") }
            .filter(File::exists)
        val english = names(File("src/main/res/values/walk.xml"))

        assertTrue(english.size >= 20)
        assertEquals(16, files.size)
        files.forEach { file ->
            val shown = names(file)
            assertEquals(file.path, english.size, shown.size)
            assertEquals("${file.path} repeats a name", shown.size, shown.toSet().size)
            shown.forEach { name ->
                val length = name.codePointCount(0, name.length)
                assertTrue("${file.path}: “$name” is $length", length in 1..MAX_NAME)
            }
        }
    }

    private companion object {
        const val MAX_NAME = 28
    }
}
