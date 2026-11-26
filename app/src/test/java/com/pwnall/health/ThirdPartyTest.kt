package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The About page's list of what the build is made of, held to the build script. The list is kept by
 * hand, and a library added to the dependencies without a row ships uncredited with nothing else to
 * notice — SQLCipher's licence, for one, asks to be named where the person using the app can find
 * it. The licence texts behind the list's button are held to the assets the same way.
 */
class ThirdPartyTest {
    private val names = THIRD_PARTY.map { it.name }

    @Test
    fun everyLibraryOutsideAndroidXAndKotlinHasARow() {
        // `implementation("group:` only: the test and annotation-processor configurations ship nothing.
        val groups = Regex("""\bimplementation\("([^:"]+):""")
            .findAll(File("build.gradle.kts").readText())
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("androidx.") || it.startsWith("org.jetbrains") }
            .toSet()

        assertTrue("the script is read: $groups", "net.zetetic" in groups)
        groups.forEach { group ->
            val row = checkNotNull(ROW_OF_GROUP[group]) { "$group is new: give it a row in About and a name here" }
            assertTrue("$group ships without a row naming $row", names.any { row in it })
        }
    }

    @Test
    fun everyArchiveBuiltIntoTheAppHasARow() {
        val archives = File("libs").listFiles { file -> file.extension == "aar" }.orEmpty()

        assertTrue("the folder is read", archives.isNotEmpty())
        archives.forEach { archive ->
            val row = ROW_OF_ARCHIVE.entries.firstOrNull { archive.name.startsWith(it.key) }?.value
            assertTrue("${archive.name} ships without a row", row != null && names.any { row in it })
        }
    }

    /** A name in the list is the credit; BSD, MIT and Apache also ask for their own words to ship. */
    @Test
    fun everyLibraryBuiltInBringsItsLicenceText() {
        val titles = LICENCE_TEXTS.map { it.title }

        (ROW_OF_GROUP.values + ROW_OF_ARCHIVE.values).forEach { row ->
            val text = checkNotNull(TEXT_OF_ROW[row]) { "$row is new: say here which licence text is its own" }
            assertTrue("$row ships without its licence text", titles.any { text in it })
        }
    }

    @Test
    fun everyLicenceTextNamedIsInTheAssets() {
        LICENCE_TEXTS.forEach { text ->
            val file = File(ASSETS, text.asset)
            assertTrue("${text.asset} is named in About and is not in the assets", file.isFile && file.length() > 0)
        }
    }

    /** The chat reader's notices are in every APK, so a page has to open each of them. */
    @Test
    fun everyNoticeInTheAssetsHasADoor() {
        val shipped = listOf("licences", "chat/NOTICES").flatMap { folder ->
            File(ASSETS, folder).listFiles().orEmpty().map { "$folder/${it.name}" }
        } + "chat/NOTICES.md"
        val named = LICENCE_TEXTS.map { it.asset }.toSet()

        assertTrue("the folders are read: $shipped", shipped.size > 1)
        shipped.forEach { assertTrue("$it ships and no page opens it", it in named) }
    }

    /** The app's own terms head the page, and are the repository's `LICENSE` and not a copy that drifted. */
    @Test
    fun theAppsOwnLicenceComesFirstAndIsTheTextInTheRoot() {
        val own = LICENCE_TEXTS.first()

        assertEquals("licences/pwn-health-gpl-3.0.txt", own.asset)
        assertTrue("PWN.Health" in own.title && "GNU General Public License" in own.title)
        assertTrue(
            "${own.asset} is not LICENSE byte for byte: copy the file again",
            File("../LICENSE").readBytes().contentEquals(File(ASSETS, own.asset).readBytes()),
        )
    }

    /** Other people's words, reproduced: nothing dropped, nothing joined, however the file ends its lines. */
    @Test
    fun aLicenceTextIsCutIntoRunsThatAreTheTextAgain() {
        val lines = (1..6_369).map { if (it % 7 == 0) "" else "line $it" }

        val runs = licenceTextRuns(lines.joinToString("\r\n") + "\n\n")

        assertEquals(lines.joinToString("\n"), runs.joinToString("\n"))
        assertTrue("a run is a few lines, not the file", runs.size > 500 && runs.all { it.lines().size <= 12 })
    }

    private companion object {
        /** What the list calls each group, so that a group nobody has named fails here until it is. */
        val ROW_OF_GROUP = mapOf("net.zetetic" to "SQLCipher", "com.google.guava" to "Guava")
        val ROW_OF_ARCHIVE = mapOf("onnxruntime-android-" to "ONNX Runtime")

        /** What the page of licence texts heads each of those rows' own text with. */
        val TEXT_OF_ROW =
            mapOf("SQLCipher" to "SQLCipher", "Guava" to "Apache License 2.0", "ONNX Runtime" to "ONNX Runtime")
        val ASSETS = File("src/main/assets")
    }
}
