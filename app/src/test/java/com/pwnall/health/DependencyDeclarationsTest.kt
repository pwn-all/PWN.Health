package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every library app/build.gradle.kts declares names its version, or takes it from a BOM declared
 * beside it. A library declared bare resolves to whatever another library asks for: Lint's version
 * checks never see it, and the day no library asks, the build finds no version at all.
 */
class DependencyDeclarationsTest {
    @Test
    fun everyLibraryNamesItsVersionOrItsBom() {
        val dependencies = File("build.gradle.kts").readText().substringAfter("\ndependencies {")
        val boms = Regex("""platform\("([^":]+):[^":]+:[^"]+"\)""").findAll(dependencies)
            .map { it.groupValues[1] }.toSet()
        val declared = Regex("""\b\w+\("([^":\s]+:[^"\s]+)"\)""").findAll(dependencies)
            .map { it.groupValues[1] }.toList()
        val bare = declared.filter { it.count { c -> c == ':' } == 1 && boms.none(it::startsWith) }

        assertTrue("the declarations are read: $declared", declared.size > 20 && boms.isNotEmpty())
        assertEquals(emptyList<String>(), bare)
    }
}
