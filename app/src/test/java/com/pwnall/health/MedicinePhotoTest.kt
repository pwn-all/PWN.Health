package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The rules behind a course's photograph: what a name may be, which file a save leaves behind, and
 * what a backup is believed about. The camera and the decoding are Android's and run only on a
 * phone; everything decided around them is here.
 */
class MedicinePhotoTest {
    private val course = Medication(id = 3, name = "Metformin", startDay = "2026-01-01", createdAt = 5)

    @Test
    fun theFileIsNamedAfterTheMomentItWasKept() {
        assertEquals("1800000000000.jpg", medicinePhotoName(1_800_000_000_000L))
        assertTrue(isMedicinePhotoName(medicinePhotoName(1_800_000_000_000L)))
    }

    /** Only the shape this app writes: a name out of a backup is somebody else's text. */
    @Test
    fun aNameWithAPathOrAnotherShapeIsNotOne() {
        listOf(
            "",
            "../databases/steps.db",
            "../1800000000000.jpg",
            "progress/1800000000000.jpg",
            "1800000000000.jpg.part",
            "1800000000000.JPG",
            "photo.jpg",
            "content://media/1",
        ).forEach { assertFalse(it, isMedicinePhotoName(it)) }
    }

    @Test
    fun aFreeNameIsKeptAndATakenOneMovesToTheNextStamp() {
        assertEquals("100.jpg", freeMedicinePhotoName("100.jpg") { false })
        val held = setOf("100.jpg", "101.jpg")
        assertEquals("102.jpg", freeMedicinePhotoName("100.jpg") { it in held })
    }

    /** The file a save leaves without a course goes; the one it goes on carrying never does. */
    @Test
    fun aSaveDeletesOnlyThePictureTheCourseNoLongerCarries() {
        assertNull("kept as it was", replacedMedicinePhoto("100.jpg", "100.jpg"))
        assertEquals("retaken", "100.jpg", replacedMedicinePhoto("100.jpg", "200.jpg"))
        assertEquals("removed", "100.jpg", replacedMedicinePhoto("100.jpg", ""))
        assertNull("a course that had none", replacedMedicinePhoto("", "200.jpg"))
        assertNull("a name this app never wrote", replacedMedicinePhoto("../steps.db", ""))
    }

    @Test
    fun aCourseWithoutAPhotographHasNone() {
        assertEquals("", course.photo)
        assertEquals(course, course.toJson().toMedication())
    }

    @Test
    fun theNameTravelsThroughTheBackupRow() {
        val pictured = course.copy(photo = "1800000000000.jpg")
        assertEquals(pictured, pictured.toJson().toMedication())
    }

    /** A hostile name in a file is read as no picture, never as a path to write the bytes to. */
    @Test
    fun aBackupRowNamingAnotherFileComesBackWithoutAPicture() {
        val hostile = course.toJson().put("photo", "../databases/steps.db")
        assertEquals("", hostile.toMedication().photo)
    }

    /** A file written before the column existed has no member for it at all. */
    @Test
    fun aRowFromBeforeThePhotographReadsAsNone() {
        val old = course.toJson().apply { remove("photo") }
        assertEquals("", old.toMedication().photo)
    }

    /** The cache file the camera's shot waits in is not a name a course can carry. */
    @Test
    fun theWaitingShotIsNeverMistakenForAKeptOne() {
        assertFalse(isMedicinePhotoName("medicine-photo.jpg"))
    }

    /** The waiting shot is kept; the file the course carried goes only once another stands in. */
    @Test
    fun aSaveKeepsTheWaitingShotAndLeavesTheOldFileToBeDeleted() {
        assertEquals("200.jpg" to "100.jpg", savedMedicinePhoto("100.jpg", "100.jpg", waiting = true) { "200.jpg" })
        assertEquals("a first one", "200.jpg" to null, savedMedicinePhoto("", "", waiting = true) { "200.jpg" })
    }

    /** The cache cleared under the open form: the course keeps the picture it was showing. */
    @Test
    fun aShotTheCacheLostLeavesThePictureTheFormShowed() {
        val lost = savedMedicinePhoto("100.jpg", "100.jpg", waiting = true) { error("no shot waiting") }
        assertEquals("100.jpg" to null, lost)
    }

    @Test
    fun aSaveWithoutAShotKeepsOrRemovesWhatTheFormSays() {
        val untouched = savedMedicinePhoto("100.jpg", "100.jpg", waiting = false) { error("not asked") }
        assertEquals("100.jpg" to null, untouched)
        val removed = savedMedicinePhoto("100.jpg", "", waiting = false) { error("not asked") }
        assertEquals("" to "100.jpg", removed)
    }

    @Test
    fun aRestoredCourseTakesTheBackupsNameOrTheNextFreeOne() {
        assertEquals("100.jpg", restoredMedicinePhotoName("100.jpg", null, { false }) { false })
        assertEquals("101.jpg", restoredMedicinePhotoName("100.jpg", null, { false }) { it == "100.jpg" })
    }

    /** A course already here keeps a picture that is really there, and only that one. */
    @Test
    fun aCourseHereGetsTheBackupsPictureOnlyWhenItsOwnIsMissing() {
        assertNull("its own is in place", restoredMedicinePhotoName("100.jpg", "300.jpg", { true }) { false })
        assertEquals("removed by mistake", "100.jpg", restoredMedicinePhotoName("100.jpg", "", { true }) { false })
        val gone = restoredMedicinePhotoName("100.jpg", "100.jpg", { false }) { it == "100.jpg" }
        assertEquals("its file has gone", "101.jpg", gone)
    }

    @Test
    fun aBackupNameThisAppNeverWroteRestoresNothing() {
        assertNull(restoredMedicinePhotoName("", null, { false }) { false })
        assertNull(restoredMedicinePhotoName("../steps.db", "", { false }) { false })
    }

    @Test
    fun theFormStartsFromTheCoursesOwnPictureAndForgetsAShotLeftWaiting() {
        val form = CourseFormState(LocalDate.of(2026, 1, 1))
        form.photoTaken()
        assertTrue(form.pendingPhoto)
        assertEquals(1, form.photoVersion)

        form.show(course.copy(photo = "100.jpg"), LocalDate.of(2026, 1, 1))

        assertEquals("100.jpg", form.photo)
        assertFalse("another form's shot is nobody's", form.pendingPhoto)
        assertEquals("100.jpg", form.toMedication().photo)
    }

    @Test
    fun removingThePictureForgetsBothTheKeptOneAndTheWaitingShot() {
        val form = CourseFormState(LocalDate.of(2026, 1, 1))
        form.show(course.copy(photo = "100.jpg"), LocalDate.of(2026, 1, 1))
        form.photoTaken()

        form.removePhoto()

        assertEquals("", form.photo)
        assertFalse(form.pendingPhoto)
        assertEquals("", form.toMedication().photo)
    }

    /** A rotation with a shot taken and not yet saved keeps both the kept name and the waiting shot. */
    @Test
    fun thePictureOutlivesARotation() {
        val form = CourseFormState(LocalDate.of(2026, 1, 1))
        form.show(course.copy(photo = "100.jpg"), LocalDate.of(2026, 1, 1))
        form.photoTaken()

        val saved = with(CourseFormState.Saver) { SaverScope { true }.save(form) }
        val restored = CourseFormState.Saver.restore(checkNotNull(saved))!!

        assertEquals("100.jpg", restored.photo)
        assertTrue(restored.pendingPhoto)
        assertEquals(form.toMedication(), restored.toMedication())
    }

    /**
     * A save lands after the photograph's move, and closing the course page whatever was open on it
     * by then would lose the edits of another course, or of a new one begun. It closes the page only for the
     * opening it was tapped in, and every opening of the form is another one — a new course after a
     * new course too, where the id alone could not tell the two apart.
     */
    @Test
    fun aSaveClosesOnlyTheOpeningItWasTappedIn() {
        val today = LocalDate.of(2026, 1, 1)
        val form = CourseFormState(today)
        form.show(course, today)
        val tapped = form.openings

        form.show(course.copy(id = 4), today)
        val another = form.openings
        form.show(null, today)
        val adding = form.openings
        form.show(null, today)

        assertEquals(4, setOf(tapped, another, adding, form.openings).size)
        val screen = File("src/main/java/com/pwnall/health/MedicineScreen.kt").readText()
        val save = screen.substringAfter("val opening = course.openings").substringBefore("refreshWidgets(")
        val closing = "if (page == MedPage.COURSE && course.openings == opening) page = backFrom(MedPage.COURSE)"
        assertTrue(save, closing in save)
    }
}
