package com.pwnall.health

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the assets leave of Google Play's limit on a bundle's base module, 200 MB as a compressed
 * download. Nearly all of the APK is the models and the map data in assets, which the APK stores
 * uncompressed for the runtime to map and which hardly compress for the download either: the
 * assets are about 174 MB and the arm64 APK about 197 MB, 164 MB once gzip-compressed.
 * They are held under 185 MB, so that the next model or map is weighed against that limit when it
 * is added rather than when the Console refuses the bundle; docs/play-release-checklist.md says
 * where the real figure is read.
 */
class AssetsBudgetTest {
    @Test
    fun theAssetsLeaveRoomUnderPlaysDownloadLimit() {
        val bytes = File("src/main/assets").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("$bytes bytes", bytes in MIN_BYTES..BUDGET_BYTES)
    }

    private companion object {
        /** Well under what is there: a count this low means the assets were not found. */
        const val MIN_BYTES = 100_000_000L
        const val BUDGET_BYTES = 185_000_000L
    }
}
