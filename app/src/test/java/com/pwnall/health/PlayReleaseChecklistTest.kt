package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The permission table of `docs/play-release-checklist.md`, held to the merged manifests the build
 * ships. Play asks a form of some permissions and rejects an upload whose form is missing, and the
 * checklist is where somebody preparing a release learns which: its own first lines name a new
 * `uses-permission` as the change that slips through, as usage access for Screen time or a
 * permission a library brings can.
 *
 * Health Connect's are held by [HealthPermissionManifestTest] and declared on their own form; every
 * other platform permission of every variant needs its row, named in backticks.
 */
class PlayReleaseChecklistTest {
    private val table = File("../docs/play-release-checklist.md").readText()
        .substringAfter("### Permission declarations", "")
        .substringBefore("\n#")

    @Test
    fun everyPermissionTheBuildsShipHasARowInTheChecklist() {
        val shipped = mergedManifests().mapValues { (_, manifest) ->
            val text = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<uses-permission\s[^>]*?android:name="android\.permission\.([A-Z_]+)"""").findAll(text)
                .map { it.groupValues[1] }.toSet()
        }

        assertTrue("the table is read", "| Permission |" in table)
        assertTrue("the manifests are read", shipped.values.all { "ACTIVITY_RECOGNITION" in it })
        shipped.forEach { (variant, permissions) ->
            val unlisted = permissions.filterNot { "`$it`" in table }.toSet()
            assertEquals("$variant ships without a checklist row", emptySet<String>(), unlisted)
        }
    }
}
