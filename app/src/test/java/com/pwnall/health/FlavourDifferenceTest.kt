package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The unit tests run on the sideload build only (app/build.gradle.kts switches play's off), because
 * the two flavours are one program with one line of manifest between them, and the tests that read
 * a manifest are handed every variant's. This holds the premise. The day play gets a source file, a
 * library or a build field of its own, a test here fails, and play's unit tests go back on in the
 * commit that makes it so.
 */
class FlavourDifferenceTest {
    private val script = File("build.gradle.kts").readText()

    @Test
    fun noFlavourHasSourcesOfItsOwnBeyondAManifest() {
        // src/sideload, and as much src/play, src/playDebug or src/testSideload on the day one appears.
        val own = File("src").listFiles().orEmpty().filter { Regex("(?i)play|sideload").containsMatchIn(it.name) }
        assertEquals(listOf("sideload"), own.map { it.name })
        val files = own.single().walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("AndroidManifest.xml"), files)
    }

    @Test
    fun theBuildScriptGivesBothFlavoursTheSame() {
        val blocks = Regex("""create\("(play|sideload)"\) \{\n(.*?)\n {8}\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(script)
            .associate { block ->
                // Which of the two a bare task name means is not a difference in what is built.
                val lines = block.groupValues[2].lines().map { it.trim() }
                block.groupValues[1] to lines.filterNot { it == "isDefault = true" }
            }
        assertEquals(setOf("play", "sideload"), blocks.keys)
        assertTrue(blocks.toString(), blocks.getValue("play").isNotEmpty())
        assertEquals(blocks["play"], blocks["sideload"])
        // A library for one flavour alone: playImplementation("…"), sideloadRuntimeOnly("…").
        assertFalse(Regex("""\b(play|sideload)[A-Z]\w*\(""").containsMatchIn(script))
    }

    @Test
    fun noCodeAsksWhichFlavourItIs() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that read no file finds nothing either.
        assertTrue(sources.size > 50)
        assertEquals(emptyList<String>(), sources.filter { "BuildConfig.FLAVOR" in it.readText() }.map { it.name })
    }
}
