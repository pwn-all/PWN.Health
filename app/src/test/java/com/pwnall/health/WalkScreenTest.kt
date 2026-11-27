package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words under a walk's photographs and the name its shared files go out under: the boundaries
 * each reading is cut at, and what a title may not carry into a file name. And what a deleted walk
 * leaves behind, so that a backup does not bring it back.
 */
class WalkScreenTest {
    @Test
    fun eachPointOfTheCompassHoldsTheFortyFiveDegreesCentredOnIt() {
        val cases = mapOf(
            0f to 0,
            22.4f to 0,
            22.5f to 1,
            45f to 1,
            67.4f to 1,
            67.5f to 2,
            90f to 2,
            135f to 3,
            180f to 4,
            245f to 5,
            270f to 6,
            315f to 7,
            337.4f to 7,
            337.5f to 0,
            359.9f to 0,
            360f to 0,
        )
        cases.forEach { (bearing, point) -> assertEquals("bearing $bearing", point, compassPoint(bearing)) }
    }

    @Test
    fun aBearingOutsideOneTurnStillLandsOnItsPoint() {
        assertEquals(7, compassPoint(-30f))
        assertEquals(0, compassPoint(-10f))
        assertEquals(2, compassPoint(450f))
    }

    @Test
    fun theCameraIsLevelWithinFiveDegreesOfTheHorizon() {
        assertEquals(PhotoTilt.LEVEL, photoTilt(0f))
        assertEquals(PhotoTilt.LEVEL, photoTilt(4.9f))
        assertEquals(PhotoTilt.LEVEL, photoTilt(-4.9f))
        assertEquals(PhotoTilt.UP, photoTilt(5f))
        assertEquals(PhotoTilt.DOWN, photoTilt(-5f))
        assertEquals(PhotoTilt.UP, photoTilt(80f))
        assertEquals(PhotoTilt.DOWN, photoTilt(-90f))
    }

    @Test
    fun theHoldTurnsAtFortyFiveAndAHundredAndThirtyFiveDegreesEitherWay() {
        assertEquals(PhotoHold.PORTRAIT, photoHold(0f))
        assertEquals(PhotoHold.PORTRAIT, photoHold(44.9f))
        assertEquals(PhotoHold.PORTRAIT, photoHold(-44.9f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(45f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(-45f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(90f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(-90f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(135f))
        assertEquals(PhotoHold.LANDSCAPE, photoHold(-135f))
        assertEquals(PhotoHold.UPSIDE_DOWN, photoHold(135.1f))
        assertEquals(PhotoHold.UPSIDE_DOWN, photoHold(-135.1f))
        assertEquals(PhotoHold.UPSIDE_DOWN, photoHold(180f))
        assertEquals(PhotoHold.UPSIDE_DOWN, photoHold(-180f))
    }

    @Test
    fun aSharedFileIsNamedAfterTheWalk() {
        assertEquals("Lactate party.gpx", walkShareName("Lactate party", 7, "gpx"))
        assertEquals("Вечерняя прогулка.geojson", walkShareName("Вечерняя прогулка", 7, "geojson"))
    }

    @Test
    fun noSeparatorOrReservedCharacterReachesTheFileName() {
        val name = walkShareName("a/b\\c:d*e?f\"g<h>i|j\tk\u0000l", 7, "csv")
        assertEquals("a b c d e f g h i j k l.csv", name)
        val base = name.removeSuffix(".csv")
        assertFalse(base.any { it in "\\/:*?\"<>|" || it.isISOControl() })
    }

    @Test
    fun aClimbOutOfTheFolderIsJustAName() {
        val name = walkShareName("../../etc/passwd", 7, "gpx")
        assertEquals("etc passwd.gpx", name)
        assertFalse('/' in name)
        assertFalse(name.startsWith("."))
    }

    @Test
    fun aTitleWithNothingLeftFallsBackToTheRouteNumber() {
        assertEquals("route-42.gpx", walkShareName("", 42, "gpx"))
        assertEquals("route-42.csv", walkShareName("   ", 42, "csv"))
        assertEquals("route-42.geojson", walkShareName("///", 42, "geojson"))
        assertEquals("route-42.gpx", walkShareName(". . .", 42, "gpx"))
    }

    @Test
    fun runsOfSpaceCollapseAndTheEndsAreTrimmed() {
        assertEquals("Coffee run.gpx", walkShareName("  Coffee   run.  ", 1, "gpx"))
    }

    /**
     * A walk deleted on its page leaves its start as a tombstone, and an import leaves out the
     * file's copy of it: without that, a backup written before the deletion is all it takes to
     * bring the walk back, fixes and photographs with it.
     */
    @Test
    fun aWalkDeletedHereStaysOutOfABackupWrittenBeforeIt() {
        val kept = TrackEntity(id = 1, startedAt = 1_000, endedAt = 2_000)
        val deleted = TrackEntity(id = 2, startedAt = 5_000, endedAt = 6_000)
        // Named by the moment its recording started, the one name a walk has on every phone.
        assertEquals("track:5000", routeTombstoneKey(deleted.startedAt))

        // Among the journal's own tombstones, whose row 1 is not walk 1.
        val tombstoned = setOf(routeTombstoneKey(deleted.startedAt), journalRecordKey(1))

        // The file's copy carries whatever id the phone that wrote it gave the walk.
        assertEquals(listOf(kept), withoutDeletedRoutes(listOf(kept, deleted.copy(id = 77)), tombstoned))
        assertEquals(listOf(kept, deleted), withoutDeletedRoutes(listOf(kept, deleted), emptySet()))
    }

    @Test
    fun everyLanguageNamesTheEightPointsOfTheCompass() {
        val files = File("src/main/res").listFiles { file -> file.name.startsWith("values") }.orEmpty()
            .map { File(it, "walk_detail.xml") }
            .filter(File::exists)
        assertEquals("walk_detail.xml in values and the fifteen translations", 16, files.size)
        files.forEach { file ->
            val text = file.readText()
            listOf("walk_compass_points", "walk_compass_names").forEach { array ->
                val body = Regex("""<string-array name="$array">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1)
                assertTrue("$array missing from ${file.path}", body != null)
                assertEquals("$array in ${file.path}", 8, Regex("<item>").findAll(body.orEmpty()).count())
            }
        }
    }
}
