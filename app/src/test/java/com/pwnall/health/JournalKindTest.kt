package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three oldest kinds of journal row, "EAT", "ACTIVITY" and "MEASUREMENT", are named once, as
 * [KIND_EAT], [KIND_ACTIVITY] and [KIND_MEASUREMENT] beside the row they describe: a comparison
 * against a misspelt string written out in place compiles and is simply never true.
 */
class JournalKindTest {
    /** What is stored: a backup, a restored database and every query in Data.kt spell these three. */
    @Test
    fun theStoredSpellingIsWhatItWas() {
        assertEquals(listOf("EAT", "ACTIVITY", "MEASUREMENT"), listOf(KIND_EAT, KIND_ACTIVITY, KIND_MEASUREMENT))
    }

    /** Comments may say the word; code names the constant. */
    @Test
    fun noCodeSpellsOneOfTheThreeOut() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that reads no file finds nothing either.
        assertTrue(sources.size > 50)
        val spelledOut = Regex(""""(?:EAT|ACTIVITY|MEASUREMENT)"""")
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trimStart().takeUnless { it.startsWith("//") || it.startsWith("*") }.orEmpty()
                "${file.name}:${index + 1}".takeIf {
                    spelledOut.containsMatchIn(code.substringBefore(" // ")) && !code.startsWith("const val KIND_")
                }
            }
        }
        assertEquals(emptyList<String>(), found)
    }
}
