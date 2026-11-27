package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every merged manifest the build makes, by the name of its variant. `app/build.gradle.kts` hands
 * them to the unit-test task as `merged.manifest.<variant>`, one property each.
 */
internal fun mergedManifests(): Map<String, File> = System.getProperties().entries
    .filter { it.key.toString().startsWith(MERGED_MANIFEST) }
    .associate { it.key.toString().removePrefix(MERGED_MANIFEST) to File(it.value.toString()) }
    .ifEmpty {
        error(
            "No merged manifest handed to this test. They come from the androidComponents block in " +
                "app/build.gradle.kts; run the tests through Gradle, and fix that wiring rather " +
                "than this test if AGP moved the artifact.",
        )
    }

private const val MERGED_MANIFEST = "merged.manifest."

/**
 * This app never talks to the network, and the privacy claim it makes to users rests on that.
 *
 * The source manifest is the easy half. The half worth testing is the *merged* manifest: a
 * dependency can hand INTERNET to the app through its own manifest without anyone editing a file
 * here, and the merged result is what actually ships in the APK.
 *
 * The files are named by the build, not looked for. Walking `build/intermediates` for anything
 * called AndroidManifest.xml would find whatever an earlier build happened to leave there: nothing
 * makes the manifest before such a test runs, so a manifest edited in one commit would be asserted
 * against the previous commit's copy. `app/build.gradle.kts` hands the unit-test task the merged
 * manifest of every variant, which makes Gradle build the current ones first, and a run that cannot
 * see them fails rather than passing quietly.
 *
 * Every variant, the release ones above all. Unit tests exist for debug builds alone, so reading
 * only the manifest of the variant whose unit tests are running would leave unread the two
 * manifests that ship, and a library declared for release builds only, or a manifest under
 * src/release, could bring INTERNET into them with every test green.
 */
class NoNetworkPermissionTest {
    private val merged: Map<String, File> = mergedManifests()

    @Test
    fun everyVariantTheBuildMakesIsRead() {
        assertEquals(setOf("playDebug", "playRelease", "sideloadDebug", "sideloadRelease"), merged.keys)
        merged.forEach { (variant, manifest) ->
            assertTrue("$variant: ${manifest.path}", manifest.readText().contains("com.pwnall.health"))
        }
    }

    /**
     * The offline policy also excludes network-state access. WorkManager contributes that
     * permission, but none of our workers needs connectivity; the manifest removes it.
     */
    @Test
    fun noDependencyAddsATransmittingPermission() {
        merged.forEach { (variant, manifest) ->
            val text = manifest.readText()
            val network = listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
                "android.permission.CHANGE_NETWORK_STATE",
            ).filter(text::contains)
            assertEquals("$variant: ${manifest.path}", emptyList<String>(), network)
        }
    }

    /**
     * Microsoft's ONNX Runtime AAR from 1.29 on declares a TelemetryInitializer that starts its
     * telemetry at launch. The AAR here is built without it (OnnxRuntimeBuildTest); this holds every
     * shipped manifest to having none from any dependency.
     */
    @Test
    fun noDependencyStartsTelemetry() {
        merged.forEach { (variant, manifest) ->
            assertTrue("$variant: ${manifest.path}", "TelemetryInitializer" !in manifest.readText())
        }
    }

    /**
     * No permission is involved in this one, which is why the test above cannot see it: emoji2's
     * startup initializer asks the phone's downloadable fonts provider for a font, over binder, and
     * on a phone with Google Play services that provider is Google's and logs the request. The
     * manifest removes the initializer; the others androidx.startup runs stay, WorkManager's among
     * them, so the provider itself must still be there.
     */
    @Test
    fun noDependencyAsksGoogleForAFontAtStartup() {
        merged.forEach { (variant, manifest) ->
            val text = manifest.readText()
            assertTrue("$variant: ${manifest.path}", "EmojiCompatInitializer" !in text)
            assertTrue("$variant: ${manifest.path}", "androidx.work.WorkManagerInitializer" in text)
        }
    }

    /**
     * Nor this one: Android's cloud backup uploads to Google under the system's own name. The rules
     * exclude every domain it reads, the device-protected ones too, which nothing writes today and a
     * library could start writing without a line here changing.
     */
    @Test
    fun nothingGoesToTheCloudBackup() {
        val cloud = File("src/main/res/xml/data_extraction_rules.xml").readText()
            .substringAfter("<cloud-backup>", "")
            .substringBefore("</cloud-backup>")
        val excluded = Regex("""<exclude domain="([a-z_]+)" />""").findAll(cloud).map { it.groupValues[1] }.toSet()
        val domains = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

        assertEquals(domains, excluded)
        assertTrue(cloud, "<include" !in cloud)
        // Rules nothing names leave the default, which backs up everything.
        merged.forEach { (variant, manifest) ->
            val rules = "android:dataExtractionRules=\"@xml/data_extraction_rules\""
            assertTrue("$variant: ${manifest.path}", rules in manifest.readText())
        }
    }

    /**
     * The test above names emoji2's initializer because it is the one there. The next one a library
     * brings runs at process start too, before any code of ours, and asks for no permission either:
     * a content provider of its own, or one more initializer under androidx.startup's. Both lists are
     * held whole, so a new entry fails here until somebody has read what it does.
     */
    @Test
    fun nothingStartsWithTheProcessButWhatIsKnown() {
        merged.forEach { (variant, manifest) ->
            val text = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val providers = Regex("""<provider\s[^>]*?android:name="([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }.toSet()
            val startup = text.substringAfter("android:name=\"androidx.startup.InitializationProvider\"", "")
                .substringBefore("</provider>")
            val initializers = Regex("""<meta-data\s[^>]*?android:name="([^"]+)"""").findAll(startup)
                .map { it.groupValues[1] }.toSet()

            assertEquals(
                "$variant: ${manifest.path}",
                setOf("androidx.startup.InitializationProvider", "androidx.core.content.FileProvider"),
                providers,
            )
            assertEquals(
                "$variant: ${manifest.path}",
                setOf(
                    "androidx.work.WorkManagerInitializer",
                    "androidx.lifecycle.ProcessLifecycleInitializer",
                    "androidx.profileinstaller.ProfileInstallerInitializer",
                ),
                initializers,
            )
        }
    }
}
