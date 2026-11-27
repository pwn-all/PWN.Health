package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tests under app/src/androidTest need a device, and no gate has one. What a gate can still do
 * for them is held here, so that it is not quietly undone: they are compiled by `check`, the
 * release checklist asks for their run, and none of them works on the record of the phone it runs
 * on.
 */
class DeviceTestsTest {
    private val sources = File("src/androidTest/java/com/pwnall/health").listFiles().orEmpty()
        .filter { it.extension == "kt" }

    /**
     * Lint, detekt and ktlint all read that source set and none resolves a name, so a symbol
     * renamed in the app could leave it unable to build with every gate green.
     */
    @Test
    fun checkCompilesTheDeviceTests() {
        val script = File("build.gradle.kts").readText()
        assertTrue("""tasks.named("check") { dependsOn("compileSideloadDebugAndroidTestKotlin") }""" in script)
    }

    @Test
    fun theReleaseChecklistAsksForTheirRun() {
        val checklist = File("../docs/play-release-checklist.md").readText()
        assertTrue("- [ ] `./gradlew connectedSideloadDebugAndroidTest` is green" in checklist)
    }

    /**
     * A device test runs inside the installed app, with its files. [StepsDb.get] there is the
     * phone's own `steps.db`: a test that writes through it writes into whatever record the phone
     * holds. Each opens a database in memory or a file of its own name.
     */
    @Test
    fun noDeviceTestOpensTheAppsOwnDatabase() {
        // A scan that read nothing finds nothing.
        assertTrue("${sources.size} device tests", sources.size >= 8)
        val reaching = sources.filter { "StepsDb.get(" in it.readText() || "\"steps.db\"" in it.readText() }
        assertEquals(emptyList<String>(), reaching.map { it.name })
    }
}
