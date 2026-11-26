package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat's tag is kept, never shown: only the files that work it out and write it may name it, no
 * file that draws anything reads it, and no string the app can print names its areas.
 */
class SymptomTagHiddenTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun onlyTheTagsOwnFilesNameIt() {
        val names =
            listOf("SymptomTag", "ProblemArea", "ProblemCourse", "ProblemImpact", "classifySymptomChat")
        // The chat payload carries each thread's tag, and the filer works it out at every write.
        val allowed = setOf("SymptomTag.kt", "Symptoms.kt", "SymptomChat.kt", "ChatPayload.kt", "ChatFiling.kt")
        val naming = sources.filter { file -> names.any { it in file.readText() } }.map { it.name }.toSet()
        assertTrue(naming.isNotEmpty())
        assertEquals(emptySet<String>(), naming - allowed)
    }

    @Test
    fun noFileThatDrawsReadsTheTag() {
        val drawing = sources.filter { "@Composable" in it.readText() }
        assertTrue(drawing.isNotEmpty())
        drawing.forEach { file ->
            val text = file.readText()
            // By the report's own name for it: a View's or a notification's `tag` is no business of this test.
            assertTrue(file.name, !Regex("""\bsymptomTag\b""").containsMatchIn(text))
            assertTrue(file.name, "encodeSymptomTag" !in text && "decodeSymptomTag" !in text)
        }
    }

    @Test
    fun noStringNamesTheAreas() {
        val strings = File("src/main/res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name.orEmpty().startsWith("values") }
            .toList()
        assertTrue(strings.isNotEmpty())
        val areas = ProblemArea.entries.map { it.name } + ProblemCourse.entries.map { it.name } +
            ProblemImpact.entries.map { it.name }
        strings.forEach { file ->
            val names = Regex("""name="([^"]+)"""").findAll(file.readText()).map {
                it.groupValues[1].uppercase()
            }.toList()
            val leaking = names.filter { name ->
                areas.any { area ->
                    name.endsWith("_$area") &&
                        name.startsWith("SYMPTOM_TAG")
                }
            }
            assertEquals(file.path, emptyList<String>(), leaking)
            assertTrue(file.path, names.none { it.startsWith("SYMPTOM_TAG") || it.startsWith("PROBLEM_AREA") })
        }
    }
}
