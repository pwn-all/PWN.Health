package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the manifest offers the rest of the phone, held to what the app needs: the components another
 * app may start, and the permissions the install screen and the privacy policy have to list.
 *
 * Both grow quietly. BootReceiver is not exported "because the system is another app", which the
 * export check never asks of the system, and VIBRATE is not declared for a vibration the system
 * plays itself, on behalf of the notification channel it belongs to.
 * [HealthPermissionManifestTest] holds the Health Connect permissions the same way.
 */
class ManifestSurfaceTest {
    /**
     * Read from the merged manifests, the ones that ship: a library's component is as reachable as
     * one of ours. The build hands over every variant's, as it does to [NoNetworkPermissionTest].
     */
    @Test
    fun nothingIsExportedBeyondWhatAnotherAppHasToReach() {
        mergedManifests().forEach { (variant, merged) ->
            val exported = elements(merged, "activity-alias|activity|receiver|service|provider")
                .filter { "android:exported=\"true\"" in it }
                .associate { attribute(it, "name").orEmpty() to attribute(it, "permission") }
            assertEquals("$variant: ${merged.path}", EXPORTED, exported)
        }
    }

    /**
     * A permission nothing calls for is one more line on the store's list and one more row for the
     * policy to explain. Every platform permission the app's own manifest declares names here what
     * in the code asks for it or cannot run without it, and that has to be in the code. The rule
     * that takes ACCESS_NETWORK_STATE back out of the merged manifest declares nothing.
     */
    @Test
    fun everyDeclaredPermissionIsOneTheCodeUses() {
        val declared = elements(File("src/main/AndroidManifest.xml"), "uses-permission")
            .filterNot { "tools:node=\"remove\"" in it }
            .mapNotNull { PLATFORM_PERMISSION.find(it)?.groupValues?.get(1) }
        assertEquals(USED_BY.keys.sorted(), declared.sorted())

        val code = File("src/main/java/com/pwnall/health").walkTopDown()
            .filter { it.extension == "kt" }
            .map { it.readText() }
            .toList()
        assertEquals(emptyMap<String, String>(), USED_BY.filterValues { use -> code.none { use in it } })
    }

    /**
     * Four switches a quick fix reaches for, each of which Android 16 and 17 ignore on a large screen
     * or turn against the app: a fixed orientation, an activity that may not be resized, the opt-out of
     * the predictive back gesture, and the opt-out of drawing edge to edge. None of them stands in the
     * manifest or in a theme, and a new one is a decision to be made here, not in passing.
     */
    @Test
    fun noSwitchOutOfTheNewerPlatformsBehaviourIsSet() {
        val values = File("src/main/res").listFiles { dir -> dir.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
        val texts = (values + File("src/main/AndroidManifest.xml"))
            .associate { it.path to it.readText().replace(COMMENT, "") }
        assertTrue("the activities are read", texts.values.any { "<activity " in it })
        assertTrue("the themes are read: ${values.size} files", texts.values.any { "<style " in it })

        val set = texts.filterValues { text -> OPT_OUTS.any { it.containsMatchIn(text) } }.keys
        assertEquals(emptySet<String>(), set)
    }

    /** The opening tags of the named elements. Comments go first: one may quote a tag. */
    private fun elements(manifest: File, tags: String): List<String> =
        Regex("""<(?:$tags)\s[^>]*>""")
            .findAll(manifest.readText().replace(COMMENT, ""))
            .map { it.value }
            .toList()

    private fun attribute(element: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(element)?.groupValues?.get(1)

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        /** Each switch as it would be written: an attribute of the manifest or an item of a theme. */
        val OPT_OUTS = listOf(
            Regex("""android:screenOrientation\s*="""),
            Regex("""android:resizeableActivity\s*=\s*"false""""),
            Regex("""android:enableOnBackInvokedCallback\s*=\s*"false""""),
            Regex("""windowOptOutEdgeToEdgeEnforcement"""),
        )

        /** Not the Health Connect ones, whose names go on in lower case after this prefix. */
        val PLATFORM_PERMISSION = Regex("""android:name="android\.permission\.([A-Z_]+)"""")

        /** Each exported component and the permission its caller must hold; null lets any app in. */
        val EXPORTED: Map<String, String?> = mapOf(
            "com.pwnall.health.MainActivity" to null,
            // Health Connect's "why does this app read my data" page, and the system's door to it.
            "com.pwnall.health.PermissionsRationaleActivity" to null,
            "com.pwnall.health.ViewPermissionUsageActivity" to "android.permission.START_VIEW_PERMISSION_USAGE",
            // The Health Connect client library's own, declared there with no permission on it.
            "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService" to null,
            "androidx.glance.appwidget.GlanceRemoteViewsService" to "android.permission.BIND_REMOTEVIEWS",
            "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )

        /** A platform permission of the main manifest, and the text its use leaves in the code. */
        val USED_BY = mapOf(
            "ACCESS_COARSE_LOCATION" to "Manifest.permission.ACCESS_COARSE_LOCATION",
            "ACCESS_FINE_LOCATION" to "Manifest.permission.ACCESS_FINE_LOCATION",
            "ACTIVITY_RECOGNITION" to "Manifest.permission.ACTIVITY_RECOGNITION",
            "BLUETOOTH_CONNECT" to "Manifest.permission.BLUETOOTH_CONNECT",
            "BLUETOOTH_SCAN" to "Manifest.permission.BLUETOOTH_SCAN",
            "FOREGROUND_SERVICE" to "startForeground(",
            "FOREGROUND_SERVICE_HEALTH" to "FOREGROUND_SERVICE_TYPE_HEALTH",
            "FOREGROUND_SERVICE_LOCATION" to "FOREGROUND_SERVICE_TYPE_LOCATION",
            "PACKAGE_USAGE_STATS" to "UsageStatsManager",
            "POST_NOTIFICATIONS" to "Manifest.permission.POST_NOTIFICATIONS",
            "RECEIVE_BOOT_COMPLETED" to "ACTION_BOOT_COMPLETED",
            "SCHEDULE_EXACT_ALARM" to "canScheduleExactAlarms",
            "USE_BIOMETRIC" to "BiometricPrompt",
            "USE_FULL_SCREEN_INTENT" to "setFullScreenIntent",
            "WAKE_LOCK" to "newWakeLock",
        )
    }
}
