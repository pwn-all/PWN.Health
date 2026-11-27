package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Location all the time, held against what the app says about it.
 *
 * The About page's note is one string for both builds, and the sideload build declares the
 * permission for the Work screen's start by location, so while any manifest declares it, no language
 * may name it as absent, as "asks for no ... background-location permission" would. And the play
 * build must not declare it (docs/play-release-checklist.md): Google Play holds every update of an
 * app that declares it for review until a declaration for it is approved.
 *
 * Read as files from the module directory the unit tests run in, like the privacy policy test.
 */
class BackgroundLocationClaimTest {
    private val permission = "android.permission.ACCESS_BACKGROUND_LOCATION"

    private fun declares(sourceSet: String): Boolean =
        File("src/$sourceSet/AndroidManifest.xml").takeIf(File::exists)?.readText()?.contains(permission) == true

    /** How each language names background location in the note, as it was written. */
    private val backgroundWords = mapOf(
        "values" to "background",
        "values-ru" to "в фоне",
        "values-ar" to "الخلفية",
        "values-cs" to "pozadí",
        "values-de" to "Hintergrund",
        "values-es" to "segundo plano",
        "values-fr" to "arrière-plan",
        "values-hi" to "पृष्ठभूमि",
        "values-in" to "latar belakang",
        "values-it" to "background",
        "values-ja" to "バックグラウンド",
        "values-ko" to "백그라운드",
        "values-pl" to "w tle",
        "values-pt" to "segundo plano",
        "values-tr" to "arka plan",
        "values-zh" to "后台",
    )

    private fun note(folder: String): String = File("src/main/res/$folder/ui.xml").readLines()
        .single { "name=\"about_permissions_note\"" in it }

    @Test
    fun theSideloadBuildDeclaresItAndThePlayBuildDoesNot() {
        assertTrue(declares("sideload"))
        assertFalse("main manifest", declares("main"))
        assertFalse("play manifest", declares("play"))
    }

    @Test
    fun whileAnyBuildDeclaresItTheAboutNoteNeverSaysItIsNotAskedFor() {
        if (listOf("main", "play", "sideload").none(::declares)) return
        backgroundWords.forEach { (folder, word) ->
            assertFalse("$folder: ${note(folder)}", word in note(folder))
        }
    }

    @Test
    fun everyLanguageOfTheNoteIsChecked() {
        val folders = File("src/main/res").listFiles().orEmpty()
            .filter { File(it, "ui.xml").exists() && "about_permissions_note" in File(it, "ui.xml").readText() }
            .map { it.name }
            .toSet()
        assertEquals(backgroundWords.keys, folders)
    }

    /**
     * While the two builds ask for different permissions, the policy's §15 must not say they hold
     * "the same permissions". Read from the merged manifests the release builds ship.
     */
    @Test
    fun whileTheBuildsAskForDifferentPermissionsThePolicyNeverSaysTheyAreTheSame() {
        val merged = mergedManifests()
        fun permissions(variant: String): Set<String> {
            val text = checkNotNull(merged[variant]) { "no merged manifest of $variant" }.readText()
                .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            return Regex("""<uses-permission\s[^>]*?android:name="([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }.toSet()
        }
        val play = permissions("playRelease")
        val sideload = permissions("sideloadRelease")
        val policy = File("../docs/privacy-policy.html").readText().replace(Regex("""\s+"""), " ").lowercase()

        assertTrue("the manifests are read", "android.permission.ACTIVITY_RECOGNITION" in play)
        assertTrue("the policy is read", "<h2>15. how you got the app" in policy)
        if (play == sideload) return
        listOf("same permissions", "same features").forEach { claim ->
            assertFalse("the policy says \"$claim\" of builds that differ in ${sideload - play}", claim in policy)
        }
    }
}
