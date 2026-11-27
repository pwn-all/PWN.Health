package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * gradle/verification-metadata.xml, held to what it is for: every jar and AAR the build downloads
 * and runs or packages has a SHA-256 there, and Gradle refuses one that differs or is not listed.
 *
 * Gradle enforces the hashes. These are the two ways the file stops protecting anything, or stops a
 * build that did nothing wrong, without Gradle saying so on the machine that changed it.
 */
class DependencyVerificationTest {
    private val metadata = File("../gradle/verification-metadata.xml").readText()

    /**
     * A refused artifact has two answers: its hash, or a rule that lets it through unread. Only what
     * no build runs is let through: sources and javadoc an IDE fetches, and Gradle's own sources.
     * Descriptors (POM and module files) are not hashed, and signatures are not checked at all.
     */
    @Test
    fun nothingThatRunsIsTrustedWithoutAHash() {
        val trusted = Regex("""<trust [^>]*/>""").findAll(metadata).map { it.value }.toList()
        assertEquals(
            listOf(
                """<trust file=".*-javadoc[.]jar" regex="true"/>""",
                """<trust file=".*-sources[.]jar" regex="true"/>""",
                """<trust file="gradle-[0-9.]+-src[.]zip" regex="true"/>""",
            ),
            trusted,
        )
        assertTrue("<verify-metadata>false</verify-metadata>" in metadata)
        val hashes = Regex("""<sha256 value="[0-9a-f]{64}"""").findAll(metadata).count()
        assertTrue("the hashes are there: $hashes", hashes > 400)
    }

    /**
     * aapt2 is one jar for each platform, and Gradle records the one for the machine it writes the
     * file on. An AGP raised on a Mac would pass there and fail on CI, which is Linux, and the other
     * way round. Google's Maven has the three under
     * dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/<version>/.
     */
    @Test
    fun aapt2IsThereForEveryPlatformTheAppIsBuiltOn() {
        val plugin = Regex("""id\("com\.android\.application"\) version "([^"]+)"""")
        val agp = checkNotNull(plugin.find(File("../build.gradle.kts").readText())) {
            "No AGP version in the root build script."
        }.groupValues[1]
        val jars = Regex("""<artifact name="(aapt2-${Regex.escape(agp)}-\d+-(\w+)\.jar)">""").findAll(metadata)
            .map { it.groupValues[2] }.toSet()
        assertEquals("aapt2 for AGP $agp", setOf("linux", "osx", "windows"), jars)
    }
}
