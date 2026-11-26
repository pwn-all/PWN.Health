package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The arithmetic behind a stored progress photograph: what it is called and how far it shrinks.
 * The decoding itself is Android's and runs only on a phone; everything decided before it is here.
 */
class ProgressPhotoTest {
    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun theFileIsNamedAfterTheMomentItWasTaken() {
        assertEquals("1800000000000.jpg", progressPhotoName(1_800_000_000_000L))
    }

    @Test
    fun aLargePhotographShrinksToTheLongerEdgeKeepingItsProportions() {
        assertEquals(1_280 to 960, shrunkSize(4_000, 3_000))
        assertEquals(720 to 1_280, shrunkSize(2_250, 4_000))
    }

    /** Enlarging invents pixels, so a picture already small enough is stored exactly as it is. */
    @Test
    fun aSmallPhotographIsLeftAlone() {
        assertEquals(800 to 600, shrunkSize(800, 600))
        assertEquals(1_280 to 1_280, shrunkSize(1_280, 1_280))
    }

    @Test
    fun aDegenerateSizeNeverBecomesZero() {
        assertEquals(1_280 to 1, shrunkSize(100_000, 1))
        assertEquals(0 to 0, shrunkSize(0, 0))
    }

    @Test
    fun theThumbnailUsesTheSameRuleAtItsOwnSize() {
        assertEquals(320 to 240, shrunkSize(4_000, 3_000, longest = 320))
    }

    /** What a camera writes into a picked picture, and what the app files it under. */
    @Test
    fun aPickedPictureIsDatedByItsOwnExifStamp() {
        assertEquals(
            LocalDateTime.of(2019, 7, 13, 18, 24, 3).toInstant(ZoneOffset.UTC).toEpochMilli(),
            exifTakenAt("2019:07:13 18:24:03", ZoneOffset.UTC),
        )
    }

    /** A screenshot carries no stamp, and a broken one is not a date: both fall back to the form. */
    @Test
    fun aStampThatIsNotOneIsNoDateAtAll() {
        assertNull(exifTakenAt("", ZoneOffset.UTC))
        assertNull(exifTakenAt("2019-07-13 18:24:03", ZoneOffset.UTC))
        assertNull(exifTakenAt("0000:00:00 00:00:00", ZoneOffset.UTC))
    }

    /** The caption is the phone's own date order, and the year is never half-said. */
    @Test
    fun theDayUnderAPhotographIsWrittenTheWayThePhoneWritesDates() {
        val day = LocalDate.of(1999, 12, 31)
        assertEquals("12/31/1999", day.format(DateTimeFormatter.ofPattern(photoDayPattern(Locale.US))))
        assertEquals("31.12.1999", day.format(DateTimeFormatter.ofPattern(photoDayPattern(Locale.GERMANY))))
    }

    /**
     * A shot whose screen never came back for it — the app closed behind the camera and opened
     * somewhere else — is a full-size photograph of a body in the cache with nothing to delete it.
     * A later start takes the ones an hour old; a younger one may be a camera still open.
     */
    @Test
    fun aShotNobodyCameBackForIsSweptAndAFreshOneIsLeft() {
        val now = 1_800_000_000_000L
        fun left(name: String, ageMs: Long) = File(cache.root, name).apply {
            parentFile?.mkdirs()
            writeText("jpeg")
            setLastModified(now - ageMs)
        }
        // The camera writes into the folder the provider serves; the cut of a label is the app's own.
        val body = left("$SHARED_CACHE_DIR/progress-capture.jpg", IN_FLIGHT_STALE_MS + 1)
        val meal = left("$SHARED_CACHE_DIR/$MEAL_PHOTO_FILE", 2 * IN_FLIGHT_STALE_MS)
        val label = left(LABEL_CROP_FILE, 2 * IN_FLIGHT_STALE_MS)
        // A packet's photograph and a template's picture are taken the same way, and are left lying too.
        val packet = left("$SHARED_CACHE_DIR/medicine-capture.jpg", 2 * IN_FLIGHT_STALE_MS)
        val template = left("$SHARED_CACHE_DIR/routine-capture.jpg", 2 * IN_FLIGHT_STALE_MS)
        val medal = left("$SHARED_CACHE_DIR/medal-10k.png", 30 * IN_FLIGHT_STALE_MS)
        // And the shrunk pictures that waited for a form no fresh start has open, a half-written one too.
        val waitingPacket = left(MEDICINE_PENDING_FILE, 2 * IN_FLIGHT_STALE_MS)
        val waitingTemplate = left(ROUTINE_PENDING_FILE, 2 * IN_FLIGHT_STALE_MS)
        val half = left("$MEDICINE_PENDING_FILE.part", 2 * IN_FLIGHT_STALE_MS)

        sweepLostCaptures(cache.root, now)

        listOf(body, meal, label, packet, template, waitingPacket, waitingTemplate, half)
            .forEach { assertFalse(it.name, it.exists()) }
        assertEquals("medicine-photo.jpg" to "routine-photo.jpg", MEDICINE_PENDING_FILE to ROUTINE_PENDING_FILE)
        assertEquals("medicine-capture.jpg" to "routine-capture.jpg", MEDICINE_CAPTURE_FILE to ROUTINE_CAPTURE_FILE)
        assertTrue("not a camera's file", medal.exists())

        val fresh = left("$SHARED_CACHE_DIR/progress-capture.jpg", 60_000)
        sweepLostCaptures(cache.root, now)
        // A cache the system has cleared has no files at all, and that is not a failure.
        sweepLostCaptures(File(cache.root, "gone"), now)

        assertTrue("the camera may still be writing it", fresh.exists())
    }
}
