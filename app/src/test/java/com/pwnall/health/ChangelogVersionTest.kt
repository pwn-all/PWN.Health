package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The changelog's top heading, held against the version the build declares.
 *
 * The rule is one line: the section at the top of the changelog is the version this tree builds.
 * A change that belongs to the next release therefore opens a new heading and raises `versionName`
 * together, which is the same moment, and a change that belongs to this one goes under the heading
 * already there. A bump that renamed the section it found would carry the entries under it along,
 * and a person on the earlier release could not be told what they had received.
 *
 * Both files are read as text on purpose. The test has no Android and no Gradle; what it is
 * checking is a habit, and the habit is visible in the two lines it reads.
 */
class ChangelogVersionTest {
    private val buildFile = File("build.gradle.kts")
    private val changelogFile = File("../CHANGELOG.md")

    private val buildSource = buildFile.takeIf { it.isFile }?.readText()
        ?: error("${buildFile.path} is not where this test looks; fix the path rather than the test.")

    private val changelog = changelogFile.takeIf { it.isFile }?.readText()
        ?: error("${changelogFile.path} is not where this test looks; fix the path rather than the test.")

    private val versionName = Regex("""versionName = "([^"]+)"""").find(buildSource)
        ?.groupValues?.get(1)
        ?: error("No versionName in ${buildFile.path}")

    private val versionCode = Regex("""versionCode = (\d+)""").find(buildSource)
        ?.groupValues?.get(1)?.toInt()
        ?: error("No versionCode in ${buildFile.path}")

    private val headings = Regex("""(?m)^## (.+)$""").findAll(changelog).map { it.groupValues[1] }.toList()

    @Test
    fun theTopSectionIsTheVersionBeingBuilt() {
        assertEquals(
            "The changelog's first section must name the version this tree builds: open a new " +
                "`## $versionName` heading rather than writing under the one already released.",
            versionName,
            headings.firstOrNull(),
        )
    }

    @Test
    fun everyVersionIsDescribedOnce() {
        assertEquals("A version may head only one section", headings.size, headings.toSet().size)
    }

    @Test
    fun theSectionsRunNewestFirst() {
        assertEquals("Sections run newest first", headings.sortedWith(newestFirst), headings)
    }

    /** The order the sections are held to, on the versions this project has had and the next major. */
    @Test
    fun aVersionIsReadNumberByNumber() {
        assertEquals(
            listOf("2.0", "1.78", "1.10", "1.9", "1.8.2", "1.8"),
            listOf("1.8", "1.9", "2.0", "1.8.2", "1.10", "1.78").sortedWith(newestFirst),
        )
    }

    /**
     * The two numbers rise together or an update cannot install: Android refuses an APK whose code
     * is not above the one on the phone, whatever its name says. A 1.x release's name is its code
     * with a "1." in front of it; from 2.0 on, each release raises the code by one over 1.78's 78,
     * so 2.0 is 79. Holding them to that is what makes one check the other.
     */
    @Test
    fun theVersionCodeMatchesTheNameItShipsUnder() {
        val expected = if (versionName.startsWith("1.")) {
            versionName.removePrefix("1.").toInt()
        } else {
            78 + headings.indexOf("1.78")
        }
        assertEquals("versionCode follows versionName", expected, versionCode)
    }

    /**
     * "1.53" as 1 and then 53, compared number by number, a missing one below any: 1.8.2 after 1.8.
     * Read as its digits run together, 153, a 2.0 would be 20 and sort below every 1.x from 1.20 on.
     */
    private val newestFirst = Comparator<String> { a, b ->
        val (x, y) = releaseNumbers(a) to releaseNumbers(b)
        x.zip(y).map { (p, q) -> q.compareTo(p) }.firstOrNull { it != 0 } ?: y.size.compareTo(x.size)
    }

    private fun releaseNumbers(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: error("A version this test cannot read as numbers: $version") }

    @Test
    fun theTopSectionSaysSomething() {
        val body = changelog.substringAfter("## $versionName").substringBefore("\n## ")
        assertTrue(
            "The version being built has no entries; a release nobody can read about is a release " +
                "nobody can check.",
            body.contains("- **"),
        )
    }
}
