package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A name given to both a string and a plurals is legal to Android and invisible to lint's
 * MissingTranslation, which is satisfied by either: a locale that translated one of the two leaves
 * the other in English on the screen or the sheet, and nothing fails.
 *
 * Every file of the default values is read, not ui.xml alone: the two can be declared in different
 * files and still collide, and ui.xml holds under two thirds of the strings.
 */
class StringNamesTest {
    @Test
    fun noNameIsBothAStringAndAPlurals() {
        val files = File("src/main/res/values").listFiles { file -> file.extension == "xml" }.orEmpty()
        val text = files.joinToString("\n") { it.readText() }
        fun names(tag: String) = Regex("""<$tag name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()

        assertTrue("the folder is read: ${files.size} files", files.size > 1 && "ui.xml" in files.map { it.name })
        assertEquals(emptySet<String>(), names("string") intersect names("plurals"))
    }
}
