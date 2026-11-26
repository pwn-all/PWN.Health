package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles patterns with ICU, the JVM these tests run on with java.util.regex, and the two do not
 * read every pattern alike. ICU takes `[:` at the start of a set as the start of a `[:property:]`, so
 * `[:.]` — a plain colon-or-dot to the JVM — throws on the phone the first time the chat reads a message,
 * while every test here passes. A set that begins with a colon is written `[.:]` or `[\\:]`.
 */
class IcuRegexTest {
    @Test
    fun noPatternOpensASetWithAColon() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that reads no file finds nothing either: run from another folder, this test would pass.
        assertTrue(sources.size > 50)
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { Regex("""(?<!\\)\[:""").containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    /**
     * Nor do the two read the short classes alike. To ICU `\d` is a digit of any script and `\s` every
     * space Unicode names; to java.util.regex the ten ASCII digits and six ASCII spaces. A pattern that
     * reads a message, a sheet or a file with either does on the phone what no test here sees: a no-break
     * space between a figure and its unit is a space there and a wall here. So a pattern says [0-9]
     * and [SPACE] (TextClasses.kt), and only a comment may name the short forms.
     */
    @Test
    fun noPatternUsesAClassTheTwoEnginesReadApart() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)
        val short = Regex("""\\[dDsS]""")
        val comment = Regex("""^\s*(?://|/?\*)""")
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { !comment.containsMatchIn(line) && short.containsMatchIn(line) }
            }
        }
        // The scan reads what it means to: the classes written in their place are there to be found.
        assertTrue(sources.sumOf { file -> file.readText().split("[0-9]").size - 1 } > 100)
        assertEquals(emptyList<String>(), found)
    }

    /**
     * Nor do the two read the digit class alike: `\d` is every script's digits to ICU and ASCII's ten to
     * java.util.regex. What such a pattern cuts out on the phone may be a Devanagari zero — the East Slavic
     * recogniser's dictionary holds one — which toDouble and toFloat throw on, where the JVM never matches
     * it. So a group is parsed with toDoubleOrNull, whatever its pattern says: what will not parse is no
     * figure, and no test here could show the exception.
     */
    @Test
    fun noGroupAPatternCutOutIsParsedByACallThatThrows() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)
        val thrower = Regex("""groupValues\[[0-9]+]\.to(?:Double|Float)\(\)""")
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { thrower.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), found)
    }
}
