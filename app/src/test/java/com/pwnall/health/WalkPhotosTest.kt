package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a walk photograph keeps about the moment it was taken: which way the camera looked, how it
 * was tilted and turned, when the shutter fell and where the walker stood. The sensor and the
 * decoding are Android's and run only on a phone; everything decided from their output is here.
 *
 * The poses are built from where the phone's own axes point in the world, worked out by hand for
 * each way of holding it, rather than taken from the code under test: a rotation matrix's columns
 * are the phone's right edge, its top and its screen's outward normal, in east, north and up.
 */
class WalkPhotosTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun heldUpFacingNorthLooksNorthLevelAndUpright() {
        // Top to the sky, screen back towards the face — south — and the right edge east.
        val pose = cameraPose(0, phone(right = EAST, top = UP, screen = SOUTH))
        assertBearing(0f, pose.bearingDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
        assertRoll(0f, pose.rollDeg)
    }

    @Test
    fun turningToFaceEastTurnsTheBearingWithIt() {
        // Facing east, the right hand is south and the screen faces back west.
        val pose = cameraPose(0, phone(right = SOUTH, top = UP, screen = WEST))
        assertBearing(90f, pose.bearingDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
        assertRoll(0f, pose.rollDeg)
    }

    @Test
    fun facingSouthWestIsTwoHundredAndTwentyFive() {
        val half = sqrt(0.5)
        // The screen faces back north-east, and the right hand points north-west.
        val pose = cameraPose(0, phone(right = v(-half, half, 0.0), top = UP, screen = v(half, half, 0.0)))
        assertBearing(225f, pose.bearingDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
        assertRoll(0f, pose.rollDeg)
    }

    /** Aiming the camera up leans the top back towards the face, and the bearing does not move. */
    @Test
    fun aimingThirtyDegreesUpIsAPositivePitch() {
        val c = cos(Math.toRadians(30.0))
        val s = sin(Math.toRadians(30.0))
        val pose = cameraPose(0, phone(right = EAST, top = v(0.0, -s, c), screen = v(0.0, -c, -s)))
        assertEquals(30f, pose.pitchDeg, TOLERANCE)
        assertBearing(0f, pose.bearingDeg)
        assertRoll(0f, pose.rollDeg)
    }

    /** Facing west with the camera aimed down: the right hand is north and the top leans away. */
    @Test
    fun aimingThirtyDegreesDownIsANegativePitch() {
        val c = cos(Math.toRadians(30.0))
        val s = sin(Math.toRadians(30.0))
        val pose = cameraPose(0, phone(right = NORTH, top = v(-s, 0.0, c), screen = v(c, 0.0, s)))
        assertEquals(-30f, pose.pitchDeg, TOLERANCE)
        assertBearing(270f, pose.bearingDeg)
        assertRoll(0f, pose.rollDeg)
    }

    /** Turned on its side with the top to the left: the right edge now points at the sky. */
    @Test
    fun landscapeWithTheTopToTheLeftIsPlusNinety() {
        val pose = cameraPose(0, phone(right = UP, top = WEST, screen = SOUTH))
        assertRoll(90f, pose.rollDeg)
        assertBearing(0f, pose.bearingDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
    }

    @Test
    fun landscapeWithTheTopToTheRightIsMinusNinety() {
        val pose = cameraPose(0, phone(right = DOWN, top = EAST, screen = SOUTH))
        assertRoll(-90f, pose.rollDeg)
        assertBearing(0f, pose.bearingDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
    }

    @Test
    fun upsideDownIsAHalfTurn() {
        val pose = cameraPose(0, phone(right = WEST, top = DOWN, screen = SOUTH))
        assertEquals(180f, abs(pose.rollDeg!!), TOLERANCE)
        assertBearing(0f, pose.bearingDeg)
    }

    /** Lying face up the camera looks at the ground, and the bearing is the way the top points. */
    @Test
    fun flatOnItsBackLooksStraightDownAlongTheTop() {
        val north = cameraPose(0, phone(right = EAST, top = NORTH, screen = UP))
        assertEquals(-90f, north.pitchDeg, TOLERANCE)
        assertBearing(0f, north.bearingDeg)
        val east = cameraPose(0, phone(right = SOUTH, top = EAST, screen = UP))
        assertEquals(-90f, east.pitchDeg, TOLERANCE)
        assertBearing(90f, east.bearingDeg)
    }

    /**
     * Held overhead and looked up at, the screen faces down and the top leans back over the face, the
     * right edge still on the right hand: the camera looks at the sky, and the bottom points ahead.
     */
    @Test
    fun heldOverheadLooksStraightUpTheWayTheBottomPoints() {
        val north = cameraPose(0, phone(right = EAST, top = SOUTH, screen = DOWN))
        assertEquals(90f, north.pitchDeg, TOLERANCE)
        assertBearing(0f, north.bearingDeg)
        val east = cameraPose(0, phone(right = SOUTH, top = WEST, screen = DOWN))
        assertEquals(90f, east.pitchDeg, TOLERANCE)
        assertBearing(90f, east.bearingDeg)
    }

    /**
     * Flat with the top north and tipped so the camera leans a little south. Five degrees off
     * straight down, the camera's own heading is a sliver and the top's is used; fifteen degrees
     * off, the camera's own is long enough to trust, and it is south.
     */
    @Test
    fun nearlyStraightDownTheTopDecidesAndFurtherOffTheCameraDoes() {
        fun tipped(degrees: Double): CameraPose {
            val c = cos(Math.toRadians(degrees))
            val s = sin(Math.toRadians(degrees))
            return cameraPose(0, phone(right = EAST, top = v(0.0, c, -s), screen = v(0.0, s, c)))
        }
        assertBearing(0f, tipped(5.0).bearingDeg)
        assertEquals(-85f, tipped(5.0).pitchDeg, TOLERANCE)
        assertBearing(180f, tipped(15.0).bearingDeg)
        assertEquals(-75f, tipped(15.0).pitchDeg, TOLERANCE)
    }

    /**
     * An upright phone facing north, its camera tipped from level towards the sky or the ground about
     * the right edge: the bearing stays north on both sides of the turn from the camera's own heading
     * to the heading of the phone's long side, up as well as down.
     */
    @Test
    fun tippingAnUprightPhoneUpOrDownKeepsItsBearing() {
        for (degrees in listOf(-89.0, -85.0, -75.0, 0.0, 75.0, 85.0, 89.0)) {
            val c = cos(Math.toRadians(degrees))
            val s = sin(Math.toRadians(degrees))
            val pose = cameraPose(0, phone(right = EAST, top = v(0.0, -s, c), screen = v(0.0, -c, -s)))
            assertEquals(degrees.toFloat(), pose.pitchDeg, TOLERANCE)
            assertBearing(0f, pose.bearingDeg)
        }
    }

    /** The game rotation vector has no compass in it: tilt and turn, and nobody's north. */
    @Test
    fun aReadingWithoutACompassHasNoBearing() {
        val pose =
            cameraPose(0, phone(right = UP, top = WEST, screen = SOUTH), magnetic = false, headingAccuracyDeg = 4f)
        assertNull(pose.bearingDeg)
        assertNull(pose.bearingAccuracyDeg)
        assertRoll(90f, pose.rollDeg)
        assertEquals(0f, pose.pitchDeg, TOLERANCE)
    }

    /**
     * Within ten degrees of straight down or up the right edge and the top both lie level, and the
     * angle between them is the hand's tremor: no hold is kept, whichever way the phone was turned.
     */
    @Test
    fun aimedNearlyStraightDownOrUpTheHoldIsLeftOut() {
        for (turn in listOf(0.0, 90.0, -90.0, 180.0)) {
            for (tip in listOf(-95.0, -90.0, -85.0, 85.0, 90.0, 95.0)) {
                assertNull("turned $turn, tipped $tip", cameraPose(0, held(turn, tip)).rollDeg)
            }
        }
    }

    /**
     * One's own feet, taken with the top tipped past level so the camera looks a little back under the
     * hand, and the sky taken just past straight up: portrait, not upside down, since nobody turns the
     * phone over for either.
     */
    @Test
    fun aSteepShotPastTheVerticalIsPortraitNotUpsideDown() {
        val feet = cameraPose(0, held(0.0, -110.0))
        assertEquals(-70f, feet.pitchDeg, TOLERANCE)
        assertRoll(0f, feet.rollDeg)
        val sky = cameraPose(0, held(0.0, 110.0))
        assertEquals(70f, sky.pitchDeg, TOLERANCE)
        assertRoll(0f, sky.rollDeg)
    }

    /**
     * Steep but clear of the vertical, the hold is only portrait or on its side: the larger of how high
     * the top and the right edge stand decides, and on its side keeps the right edge's sign. A phone
     * turned a little in the hand is still portrait, and one turned over is never upside down there.
     */
    @Test
    fun steepTheHoldIsPortraitOrOnItsSideAndNeverUpsideDown() {
        assertRoll(0f, cameraPose(0, held(25.0, -70.0)).rollDeg)
        assertRoll(0f, cameraPose(0, held(180.0, -70.0)).rollDeg)
        assertRoll(90f, cameraPose(0, held(90.0, -70.0)).rollDeg)
        assertRoll(90f, cameraPose(0, held(60.0, 70.0)).rollDeg)
        assertRoll(-90f, cameraPose(0, held(-90.0, -70.0)).rollDeg)
        // Past the vertical the right edge has tipped below level, and the side follows it.
        assertRoll(-90f, cameraPose(0, held(90.0, -110.0)).rollDeg)
    }

    /** Sixty degrees or less from the horizon, the hold is the plain angle, upside down included. */
    @Test
    fun clearOfTheSteepBandTheHoldIsStillAnAngle() {
        assertRoll(30f, cameraPose(0, held(30.0, -30.0)).rollDeg)
        assertRoll(-120f, cameraPose(0, held(-120.0, 45.0)).rollDeg)
        assertEquals(180f, abs(cameraPose(0, held(180.0, -50.0)).rollDeg!!), TOLERANCE)
    }

    /**
     * The same convention as Android's own: a rotation vector — a turn about an axis — run through
     * getRotationMatrixFromVector's arithmetic, copied below. Lying flat with its top north is no
     * turn at all; a quarter turn about east stands it up facing north; a quarter turn clockwise
     * about up leaves it flat with the top east.
     */
    @Test
    fun androidsRotationVectorAgreesWithTheseConventions() {
        val quarter = sqrt(0.5)
        val stoodUp = cameraPose(0, androidRotationMatrix(quarter, 0.0, 0.0, quarter))
        assertBearing(0f, stoodUp.bearingDeg)
        assertEquals(0f, stoodUp.pitchDeg, TOLERANCE)
        assertRoll(0f, stoodUp.rollDeg)
        val turned = cameraPose(0, androidRotationMatrix(0.0, 0.0, -quarter, quarter))
        assertBearing(90f, turned.bearingDeg)
        assertEquals(-90f, turned.pitchDeg, TOLERANCE)
        // Stood up and then turned clockwise to face east: the two turns multiplied out by hand.
        val facingEast = cameraPose(0, androidRotationMatrix(0.5, -0.5, -0.5, 0.5))
        assertBearing(90f, facingEast.bearingDeg)
        assertEquals(0f, facingEast.pitchDeg, TOLERANCE)
        assertRoll(0f, facingEast.rollDeg)
    }

    @Test
    fun theHeadingAccuracyIsTheFifthValueInRadians() {
        assertEquals(Math.toDegrees(0.1).toFloat(), headingAccuracyDeg(floatArrayOf(0f, 0f, 0f, 1f, 0.1f))!!, TOLERANCE)
        assertNull(headingAccuracyDeg(floatArrayOf(0f, 0f, 0f, 1f, -1f)))
        assertNull(headingAccuracyDeg(floatArrayOf(0f, 0f, 0f, 1f)))
        assertNull(headingAccuracyDeg(floatArrayOf(0f, 0f, 0f, 1f, Float.NaN)))
    }

    @Test
    fun anyAngleBecomesABearingFromZeroUpToThreeSixty() {
        assertEquals(270f, normalisedBearing(-90.0), TOLERANCE)
        assertEquals(0f, normalisedBearing(360.0), TOLERANCE)
        assertEquals(5f, normalisedBearing(725.0), TOLERANCE)
        assertEquals(359.5f, normalisedBearing(359.5), TOLERANCE)
        // A millionth of a degree west of north is 360 once it is a Float, and 360 is written 0.
        assertEquals(0f, normalisedBearing(-1e-6), 0f)
    }

    /** Magnetic north east of true north adds its angle; west of it takes the angle away. */
    @Test
    fun theDeclinationTurnsAMagneticBearingIntoATrueOne() {
        assertEquals(5f, trueBearing(350f, 15f), TOLERANCE)
        assertEquals(350f, trueBearing(10f, -20f), TOLERANCE)
        assertEquals(90f, trueBearing(90f, 0f), TOLERANCE)
    }

    @Test
    fun theReadingNearestTheShutterIsTaken() {
        val poses = listOf(pose(10_000, 1f), pose(11_000, 2f), pose(14_000, 3f))
        assertEquals(2f, nearestPose(poses, 11_400)?.pitchDeg)
        // Half way between two readings, the earlier one.
        assertEquals(2f, nearestPose(poses, 12_500)?.pitchDeg)
    }

    @Test
    fun aReadingMoreThanThreeSecondsAwayIsNone() {
        val poses = listOf(pose(10_000, 1f), pose(14_000, 3f))
        assertEquals(3f, nearestPose(poses, 17_000)?.pitchDeg)
        assertNull(nearestPose(poses, 17_001))
        assertEquals(1f, nearestPose(poses, 7_000)?.pitchDeg)
        assertNull(nearestPose(poses, 6_999))
        assertNull(nearestPose(emptyList(), 10_000))
    }

    /** All three fields: the stamp, the milliseconds after it, and the zone it was written in. */
    @Test
    fun theExifStampIsReadToTheMillisecondInItsOwnOffset() {
        assertEquals(
            Instant.parse("2026-10-12T11:03:27.457Z").toEpochMilli(),
            exifMoment("2026:10:12 14:03:27", "457", "+03:00", ZoneOffset.UTC),
        )
        assertEquals(
            Instant.parse("2026-10-12T19:33:27Z").toEpochMilli(),
            exifMoment("2026:10:12 14:03:27", null, "-05:30", ZoneOffset.UTC),
        )
    }

    /** The fraction is digits after a decimal point, not a count of milliseconds. */
    @Test
    fun theSubSecondsAreAFractionOfASecond() {
        val second = Instant.parse("2026-10-12T14:03:27Z").toEpochMilli()
        fun read(fraction: String?) = exifMoment("2026:10:12 14:03:27", fraction, null, ZoneOffset.UTC)
        assertEquals(second + 500, read("5"))
        assertEquals(second + 120, read("12"))
        assertEquals(second + 123, read("123456"))
        assertEquals(second + 450, read(" 45 "))
        assertEquals(second, read("4a"))
        assertEquals(second, read(""))
        assertEquals(second, read(null))
    }

    /** No offset, or one that is not an offset: the wall clock of the phone's own zone. */
    @Test
    fun withoutAnOffsetTheStampIsThePhonesWallClock() {
        val moscow = ZoneId.of("Europe/Moscow")
        val expected = Instant.parse("2026-10-12T11:03:27Z").toEpochMilli()
        assertEquals(expected, exifMoment("2026:10:12 14:03:27", null, null, moscow))
        assertEquals(expected, exifMoment("2026:10:12 14:03:27", null, "   :  ", moscow))
        assertEquals(expected, exifMoment(" 2026:10:12 14:03:27 ", null, "", moscow))
    }

    @Test
    fun aMissingOrZeroStampIsNoMomentAtAll() {
        assertNull(exifMoment("", "123", "+03:00", ZoneOffset.UTC))
        assertNull(exifMoment("0000:00:00 00:00:00", null, null, ZoneOffset.UTC))
        assertNull(exifMoment("    :  :     :  :  ", null, null, ZoneOffset.UTC))
        assertNull(exifMoment("2026-10-12 14:03:27", null, null, ZoneOffset.UTC))
    }

    /** The camera's stamp first, then the file's own time, then now: whichever is found first. */
    @Test
    fun theShutterIsTheStampElseTheFileElseNow() {
        assertEquals(130_000L, shutterMoment(exif = 130_000, modified = 150_000, openedAt = OPENED, now = NOW))
        assertEquals(150_000L, shutterMoment(exif = null, modified = 150_000, openedAt = OPENED, now = NOW))
        assertEquals(NOW, shutterMoment(exif = null, modified = null, openedAt = OPENED, now = NOW))
    }

    /** A camera clock that disagrees is brought back inside the time the camera was open. */
    @Test
    fun theShutterIsKeptBetweenTheOpeningAndNow() {
        assertEquals(99_000L, shutterMoment(exif = 99_000, modified = null, openedAt = OPENED, now = NOW))
        assertEquals(98_000L, shutterMoment(exif = 98_000, modified = null, openedAt = OPENED, now = NOW))
        assertEquals(98_000L, shutterMoment(exif = 40_000, modified = null, openedAt = OPENED, now = NOW))
        assertEquals(NOW, shutterMoment(exif = 170_000, modified = null, openedAt = OPENED, now = NOW))
        assertEquals(NOW, shutterMoment(exif = NOW, modified = null, openedAt = OPENED, now = NOW))
    }

    /** The clock set back while the camera was open: there is no window, and now is what is left. */
    @Test
    fun aClockSetBackLeavesNow() {
        assertEquals(NOW, shutterMoment(exif = 150_000, modified = null, openedAt = NOW + 5_000, now = NOW))
    }

    @Test
    fun aFixNearTheShutterIsWhereThePhotographWasTaken() {
        val fix = fix(at = 1_000_000 + 59_000, correctedLat = 48.15, correctedLon = 17.11, barometric = 151f)
        val row = walkPhotoRow(7, 1_000_000, fix, pose(1_000_000, -10f, bearing = 80f, accuracy = 6f), 4.5f)
        assertEquals(7L, row.trackId)
        assertEquals(1_000_000L, row.takenAt)
        assertEquals("1000000.jpg", row.file)
        assertEquals(48.15, row.lat!!, 0.0)
        assertEquals(17.11, row.lon!!, 0.0)
        assertEquals(151.0, row.altitudeM!!, 0.0)
        assertEquals(4f, row.accuracyM)
        assertEquals(84.5f, row.bearingDeg!!, TOLERANCE)
        assertEquals(6f, row.bearingAccuracyDeg)
        assertEquals(-10f, row.pitchDeg)
        assertEquals(0f, row.rollDeg)
    }

    /** No correction and no barometer: the fix's own coordinates and the satellite height. */
    @Test
    fun anUncorrectedFixGivesItsOwnCoordinates() {
        val row = walkPhotoRow(7, 1_000_000, fix(at = 1_000_000 - 60_000), null, null)
        assertEquals(48.1, row.lat!!, 0.0)
        assertEquals(17.1, row.lon!!, 0.0)
        assertEquals(140.0, row.altitudeM!!, 0.0)
    }

    /** A minute off, the walker could have been anywhere in between; the compass still knows north. */
    @Test
    fun aFixTooFarFromTheShutterGivesNoPositionButStillTheDeclination() {
        val row = walkPhotoRow(7, 1_000_000, fix(at = 1_000_000 + 61_000), pose(1_000_000, 5f, bearing = 355f), 10f)
        assertNull(row.lat)
        assertNull(row.lon)
        assertNull(row.altitudeM)
        assertNull(row.accuracyM)
        assertEquals(5f, row.bearingDeg!!, TOLERANCE)
        assertEquals(5f, row.pitchDeg)
    }

    /**
     * The first shots of a walk come before its first fix: no position, but a declination read where
     * the phone last knew itself to be still turns the compass's bearing true.
     */
    @Test
    fun beforeTheFirstFixADeclinationFromElsewhereStillGivesTheBearing() {
        val row = walkPhotoRow(7, 1_000_000, null, pose(1_000_000, -5f, bearing = 80f, accuracy = 6f), 4.5f)
        assertNull(row.lat)
        assertNull(row.lon)
        assertNull(row.altitudeM)
        assertEquals(84.5f, row.bearingDeg!!, TOLERANCE)
        assertEquals(6f, row.bearingAccuracyDeg)
    }

    /** No declination, no true north: the bearing is left out, and its accuracy with it. */
    @Test
    fun withoutADeclinationThereIsNoBearing() {
        val row = walkPhotoRow(7, 1_000_000, null, pose(1_000_000, 12f, bearing = 80f, accuracy = 6f), null)
        assertNull(row.bearingDeg)
        assertNull(row.bearingAccuracyDeg)
        assertEquals(12f, row.pitchDeg)
        assertNull(row.lat)
    }

    @Test
    fun withoutAReadingThereAreNoAngles() {
        val row = walkPhotoRow(7, 1_000_000, fix(at = 1_000_000), null, 4f)
        assertNull(row.bearingDeg)
        assertNull(row.bearingAccuracyDeg)
        assertNull(row.pitchDeg)
        assertNull(row.rollDeg)
        assertEquals(48.1, row.lat!!, 0.0)
    }

    /**
     * Each shot has a file of its own in the cache, named by the moment its camera opened: the keep
     * still saving one shot finds and deletes that one, and the next shot's picture beside it stays.
     */
    @Test
    fun eachShotHasACaptureFileOfItsOwn() {
        val cache = folder.newFolder("cache")
        val first = walkCaptureFile(cache, 1_000_000L)
        val second = walkCaptureFile(cache, 1_004_500L)
        assertNotEquals(first, second)
        assertEquals(cache, first.parentFile)
        first.writeText("first")
        second.writeText("second")
        // The first shot's keep, or its cancellation, finds its own file again by the same moment.
        assertTrue(walkCaptureFile(cache, 1_000_000L).delete())
        assertFalse(first.exists())
        assertEquals("second", second.readText())
    }

    private fun pose(at: Long, pitch: Float, bearing: Float? = null, accuracy: Float? = null) =
        CameraPose(at = at, bearingDeg = bearing, bearingAccuracyDeg = accuracy, pitchDeg = pitch, rollDeg = 0f)

    private fun fix(at: Long, correctedLat: Double? = null, correctedLon: Double? = null, barometric: Float? = null) =
        TrackPointEntity(
            trackId = 7,
            timestamp = at,
            lat = 48.1,
            lon = 17.1,
            correctedLat = correctedLat,
            correctedLon = correctedLon,
            altitudeM = 140.0,
            accuracyM = 4f,
            speedMps = 1.3f,
            barometricAltitudeM = barometric,
            provider = "gps",
            accepted = true,
        )

    /**
     * The rotation matrix of a phone whose right edge, top and screen point along [right], [top] and
     * [screen] in the world. Checked to be a rotation — three square unit axes, right-handed — so a
     * slip in working out a pose fails here rather than passing as a mirror image.
     */
    private fun phone(right: DoubleArray, top: DoubleArray, screen: DoubleArray): FloatArray {
        for (axis in listOf(right, top, screen)) assertEquals(1.0, sqrt(dot(axis, axis)), 1e-9)
        assertEquals(0.0, dot(right, top), 1e-9)
        val turned = cross(right, top)
        for (i in 0..2) assertEquals(screen[i], turned[i], 1e-9)
        // Row-major: each row is one world direction, each column one of the phone's axes.
        val axes = listOf(right, top, screen)
        return FloatArray(9) { axes[it % 3][it / 3].toFloat() }
    }

    /**
     * Android's SensorManager.getRotationMatrixFromVector for a rotation vector given with its
     * fourth, scalar, part — the arithmetic as AOSP writes it, since android.jar is only stubs here.
     */
    private fun androidRotationMatrix(x: Double, y: Double, z: Double, w: Double): FloatArray {
        val sqX = 2 * x * x
        val sqY = 2 * y * y
        val sqZ = 2 * z * z
        val xy = 2 * x * y
        val zw = 2 * z * w
        val xz = 2 * x * z
        val yw = 2 * y * w
        val yz = 2 * y * z
        val xw = 2 * x * w
        val rows = listOf(
            doubleArrayOf(1 - sqY - sqZ, xy - zw, xz + yw),
            doubleArrayOf(xy + zw, 1 - sqX - sqZ, yz - xw),
            doubleArrayOf(xz - yw, yz + xw, 1 - sqX - sqY),
        )
        return FloatArray(9) { rows[it / 3][it % 3].toFloat() }
    }

    /**
     * An upright phone facing north, turned [rollDeg] about the camera's axis — the top towards the left
     * for a positive turn — and then tipped [tipDeg] about the east axis, the camera towards the sky for
     * a positive tip. Past ninety degrees either way the camera looks back south, over the head or
     * under the hand.
     */
    private fun held(rollDeg: Double, tipDeg: Double): FloatArray {
        val cr = cos(Math.toRadians(rollDeg))
        val sr = sin(Math.toRadians(rollDeg))
        val ct = cos(Math.toRadians(tipDeg))
        val st = sin(Math.toRadians(tipDeg))

        // Turned about north, the right edge goes from east towards up and the top from up towards
        // west; tipped about east, what pointed north rises by the tip and what pointed up leans south.
        fun tipped(east: Double, north: Double, up: Double) = v(east, north * ct - up * st, north * st + up * ct)
        return phone(right = tipped(cr, 0.0, sr), top = tipped(-sr, 0.0, cr), screen = tipped(0.0, -1.0, 0.0))
    }

    /** A turn within the tolerance of [expected], and one there is: the pose was clear of the vertical. */
    private fun assertRoll(expected: Float, actual: Float?) {
        assertNotNull("no roll", actual)
        assertEquals(expected, actual!!, TOLERANCE)
    }

    /** A bearing within the tolerance of [expected] going either way round, and inside 0 up to 360. */
    private fun assertBearing(expected: Float, actual: Float?) {
        assertNotNull(actual)
        val bearing = actual!!
        assertTrue("bearing $bearing is outside 0 up to 360", bearing >= 0f && bearing < 360f)
        val off = ((bearing - expected) % 360f + 540f) % 360f - 180f
        assertEquals("bearing $bearing, expected $expected", 0f, off, TOLERANCE)
    }

    private fun v(east: Double, north: Double, up: Double) = doubleArrayOf(east, north, up)

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** A photograph with no route fix near it takes the phone's own position, if it is of the moment. */
    @Test
    fun aPhotographWithoutANearFixTakesThePhonesOwnPlace() {
        val here = PhotoPlace(lat = 48.15, lon = 17.11, altitudeM = 140.0, accuracyM = 8f)
        val far = walkPhotoRow(7, 1_000_000, fix(at = 1_000_000 + 61_000), null, null, here)
        assertEquals(48.15, far.lat!!, 1e-9)
        assertEquals(17.11, far.lon!!, 1e-9)
        assertEquals(8f, far.accuracyM)
        val none = walkPhotoRow(7, 1_000_000, null, null, null, here)
        assertEquals(140.0, none.altitudeM!!, 1e-9)
        // A fix of the moment wins over the phone's own position.
        val near = walkPhotoRow(7, 1_000_000, fix(at = 1_000_000 - 30_000), null, null, here)
        assertNotEquals(48.15, near.lat!!, 1e-9)
    }

    /** During a pause the route's last fix is behind the walker; the fresher of the two marks the spot. */
    @Test
    fun theFixOrThePhonesPlaceNearerTheShutterMarksThePhotograph() {
        assertTrue(fixWins(fixOffsetMs = 1_000, phoneOffsetMs = 2_000))
        assertTrue(fixWins(fixOffsetMs = 1_000, phoneOffsetMs = 1_000))
        assertTrue(fixWins(fixOffsetMs = 30_000, phoneOffsetMs = null))
        assertFalse(fixWins(fixOffsetMs = 30_000, phoneOffsetMs = 1_500))
        assertFalse(fixWins(fixOffsetMs = FIX_WINDOW_MS + 1, phoneOffsetMs = null))
        assertFalse(fixWins(fixOffsetMs = null, phoneOffsetMs = 1_000))
        assertFalse(fixWins(fixOffsetMs = null, phoneOffsetMs = null))
    }

    @Test
    fun onlyAPositionOfTheSameMinuteAndAStreetWideMarksTheSpot() {
        assertTrue(placeStands(0, 10f))
        assertTrue(placeStands(-FIX_WINDOW_MS, PLACE_ACCURACY_M))
        assertFalse(placeStands(FIX_WINDOW_MS + 1, 10f))
        assertFalse(placeStands(0, PLACE_ACCURACY_M + 1f))
        assertFalse(placeStands(0, null))
    }

    /** The declination falls back to the phone's last known place only while that place is fresh. */
    @Test
    fun aLastKnownPlaceGivesTheDeclinationOnlyWithinTheHour() {
        assertTrue(lastKnownIsFresh(0))
        assertTrue(lastKnownIsFresh(LAST_KNOWN_MAX_AGE_NANOS))
        assertFalse(lastKnownIsFresh(LAST_KNOWN_MAX_AGE_NANOS + 1))
        // A position stamped after "now" — a clock that went back — is not trusted either.
        assertFalse(lastKnownIsFresh(-1))
    }

    private companion object {
        const val TOLERANCE = 1e-3f
        const val OPENED = 100_000L
        const val NOW = 160_000L
        val EAST = doubleArrayOf(1.0, 0.0, 0.0)
        val WEST = doubleArrayOf(-1.0, 0.0, 0.0)
        val NORTH = doubleArrayOf(0.0, 1.0, 0.0)
        val SOUTH = doubleArrayOf(0.0, -1.0, 0.0)
        val UP = doubleArrayOf(0.0, 0.0, 1.0)
        val DOWN = doubleArrayOf(0.0, 0.0, -1.0)
    }
}
