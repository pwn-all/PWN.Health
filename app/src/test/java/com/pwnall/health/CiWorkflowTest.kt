package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules the build workflow keeps about the signing key and about what it publishes, held to its
 * text. Nothing on a developer's machine runs a workflow, so a rule loosened there is first seen on
 * the run that needed it: the tag whose APK is the download.
 */
class CiWorkflowTest {
    private val workflows = File("../.github/workflows").listFiles { file -> file.extension == "yml" }.orEmpty()
        .associate { it.name to it.readText() }
    private val build = checkNotNull(workflows["build.yml"]) { "No build.yml where this test looks." }

    /** One step of the build workflow, from its name (after its anchor, if it has one) to the step after it. */
    private fun step(name: String): String = build.substringAfter("name: $name\n", "").substringBefore("\n      - ")
        .also { assertTrue("no step named $name", it.isNotEmpty()) }

    private fun jobs(workflow: String): List<String> =
        workflow.substringAfter("\njobs:\n").split(Regex("""\n(?=  [\w-]+:\n)""")).filter { it.isNotBlank() }

    /**
     * A tag of an action can be moved by whoever owns the action, and on a version tag these steps
     * share a job with the signing key. A commit cannot be moved; the comment says which version it was.
     */
    @Test
    fun everyActionIsPinnedToACommit() {
        val uses = workflows.values.flatMap { text -> Regex("""uses: *(\S+)(.*)""").findAll(text).toList() }
        assertTrue("the workflows are read", uses.size > 5)
        val loose = uses.filterNot { use ->
            Regex("""@[0-9a-f]{40}$""").containsMatchIn(use.groupValues[1]) &&
                Regex("""^ # v\d+(\.\d+)*$""").matches(use.groupValues[2])
        }
        assertEquals(emptyList<String>(), loose.map { it.value })
    }

    /**
     * The key is the identity of every update the app will ever get. A push to a branch runs the
     * branch's build scripts, every Gradle plugin and every test with its libraries, and none of that
     * has a use for the key: only the build of a version tag is published. GitHub hands a runner
     * every secret its job names, in a skipped step too, so the key is named in a job only a tag runs.
     */
    @Test
    fun theSigningKeyIsRestoredForAVersionTagAndNothingElse() {
        val signed = jobs(build).single { "name: Restore release signing" in it }
        assertEquals("    if: startsWith(github.ref, 'refs/tags/v')", signed.lines().first { it.startsWith("    if:") })
        val named = build.lines().filter { "secrets." in it }.map { it.trim() }
        assertEquals(
            listOf(
                "KEYSTORE_B64: \${{ secrets.KEYSTORE_B64 }}",
                "KEYSTORE_PASSWORD: \${{ secrets.KEYSTORE_PASSWORD }}",
            ),
            named,
        )
        assertTrue(signed, named.all { line -> signed.lines().any { it.trim() == line } })
    }

    /**
     * The download is the sideload APK. Play's flavour reaches people as the bundle the Play Console
     * signs, and an unsigned APK installs on nobody's phone over the app they have.
     */
    @Test
    fun aReleaseCarriesTheSignedSideloadApksOnly() {
        assertTrue("app/build/outputs/apk/sideload/release/*.apk" in build)
        assertFalse("outputs/apk/*/" in build)
        val published = step("Publish a release for a version tag")
        val apks = published.lines().filter { ".apk" in it }
        assertTrue(published, apks.isNotEmpty() && apks.all { it.trim().endsWith("*-release.apk") })
    }

    /** A release says what the changelog's section for its version says (ChangelogVersionTest holds that heading). */
    @Test
    fun aReleaseIsDescribedByItsChangelogSection() {
        assertTrue(build, "awk -v head=\"## \${TAG#v}\"" in step("Take the notes from the changelog"))
        assertTrue(build, "body_path: \${{ runner.temp }}/notes.md" in step("Publish a release for a version tag"))
        assertFalse(build, "generate_release_notes" in build)
    }

    /**
     * Each job names the image it runs on, which ubuntu-latest does not — GitHub moves it — and is
     * stopped within an hour and a half (the build takes 46 to 57 minutes) rather than the six hours
     * GitHub allows one that hangs.
     */
    @Test
    fun everyJobHasAnImageOfItsOwnAndATimeout() {
        assertEquals(listOf("build", "signed", "release"), jobs(build).map { it.trim().substringBefore(':') })
        workflows.values.flatMap(::jobs).forEach { job ->
            assertTrue(job, Regex("""\n    runs-on: ubuntu-[0-9]+\.[0-9]+\n""").containsMatchIn(job))
            val minutes = Regex("""\n    timeout-minutes: ([0-9]+)\n""").find(job)?.groupValues?.get(1)?.toInt()
            assertTrue(job, minutes != null && minutes <= 90)
        }
    }

    /**
     * Dependabot raises the versions of Gradle's manifests and the workflows' actions. A Gradle
     * branch is refused by gradle/verification-metadata.xml until
     * dependabot-gradle.yml writes the checksums of the new versions onto it.
     */
    @Test
    fun dependabotWatchesEveryManifestHere() {
        val settings = File("../.github/dependabot.yml").readText()
        val watched = Regex("""package-ecosystem: *(\S+)\n *directory: *(\S+)""").findAll(settings)
            .map { it.groupValues[1] to it.groupValues[2] }.toSet()
        listOf("gradle" to "/", "github-actions" to "/").forEach {
            assertTrue("$it in $watched", it in watched)
        }
        assertTrue("dependabot-gradle.yml" in workflows)
    }

    /**
     * The merge is unattended, so what it merges is narrow: a pull request Dependabot opened, at the
     * commit every build passed, by a job that runs nothing from the branch. The token that can merge
     * a change to the workflows is named in that job and in no other, least of all one that runs
     * Gradle and the new dependencies with it.
     */
    @Test
    fun onlyDependabotsPullRequestIsMergedAtTheCommitThatPassed() {
        val merge = checkNotNull(workflows["automerge.yml"]) { "No automerge.yml beside build.yml." }
        assertFalse(merge, "actions/checkout" in merge)
        assertTrue(merge, "github.event.workflow_run.conclusion == 'success'" in merge)
        assertTrue(merge, "select(.author.login == \\\"app/dependabot\\\" and .headRefOid == \\\"\$SHA\\\")" in merge)
        assertTrue(merge, "--match-head-commit \"\$SHA\"" in merge)
        val named = workflows.filterKeys { it != "build.yml" }
            .flatMap { (file, text) -> text.lines().filter { "secrets." in it }.map { file to it.trim() } }
        assertEquals(listOf("automerge.yml" to "PAT: \${{ secrets.AUTOMERGE_TOKEN }}"), named)
    }

    /**
     * The build tools CI installs are the ones the build names. Left to the Android Gradle plugin's
     * default, the build would read its own default version and leave the one installed here unused.
     */
    @Test
    fun ciInstallsTheBuildToolsTheBuildNames() {
        val named = Regex("""buildToolsVersion = "([^"]+)"""").find(File("build.gradle.kts").readText())
        assertTrue("app/build.gradle.kts names no buildToolsVersion", named != null)
        assertTrue(build, "build-tools;${named?.groupValues?.get(1)}" in step("Set up the Android SDK"))
    }

    /** The platform CI installs is the one the build compiles against, minor release included. */
    @Test
    fun ciInstallsThePlatformTheBuildCompilesAgainst() {
        val named = Regex("""version = release\(([0-9]+)\) \{ minorApiLevel = ([0-9]+) \}""")
            .find(File("build.gradle.kts").readText())
        val (major, minor) = checkNotNull(named) { "build.gradle.kts names no compileSdk minor release" }.destructured
        assertTrue(build, "platforms;android-$major.$minor " in step("Set up the Android SDK"))
    }

    /**
     * The checkout leaves no token in .git/config for the build scripts, plugins and tests after it to
     * read, and a pull request from a branch of this repository is built once, by the branch's push.
     */
    @Test
    fun theCheckoutKeepsNoTokenAndACommitIsBuiltOnce() {
        val checkout = build.substringAfter("uses: actions/checkout@").substringBefore("\n      - ")
        assertTrue(checkout, Regex("""\n {8}with:\n {10}persist-credentials: false""").containsMatchIn(checkout))
        val job = build.substringAfter("\n  build:\n").substringBefore("\n    steps:")
        val once = "github.event_name != 'pull_request' || " +
            "github.event.pull_request.head.repo.full_name != github.repository"
        assertTrue(job, once in job && "!startsWith(github.ref, 'refs/tags/')" in job)
    }
}
