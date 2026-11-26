package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * When an attachment's read grant may go. The grant is the app's, one per file, and four kinds of row
 * hang files: releasing it from one course while a visit still points at the same scan would leave
 * that visit opening nothing.
 */
class AttachmentsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scan = "content://com.android.providers.media.documents/document/image%3A12"
    private val letter = "content://com.android.providers.downloads.documents/document/7"
    private val own = "com.pwnall.health.photos"
    private val database = "file:///data/user/0/com.pwnall.health/databases/steps.db"

    private fun course(id: Long, vararg files: String) = Medication(
        id = id,
        name = "Metformin",
        startDay = "2026-08-10",
        attachments = storeAttachments(files.toList()),
        createdAt = 0,
    )

    private fun visit(id: Long, vararg files: String) = Appointment(
        id = id,
        specialty = VISIT_SPECIALTY_OTHER,
        atMs = 0,
        attachments = storeAttachments(files.toList()),
    )

    private fun sheet(vararg files: String) = JournalEntry(
        day = "2026-08-10",
        occurredAt = 0,
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = JSONObject().put("attachments", storeAttachments(files.toList())).toString(),
    )

    /** Whether the scan is still named by a row, given what the database holds. */
    private fun named(
        courses: List<Medication> = emptyList(),
        visits: List<Appointment> = emptyList(),
        sheets: List<JournalEntry> = emptyList(),
    ) = scan in namedAttachments(courses, visits, sheets)

    @Test
    fun aFileNoRowNamesMayBeLetGo() {
        assertFalse(named())
        assertFalse(named(courses = listOf(course(1, letter)), visits = listOf(visit(1, letter))))
    }

    @Test
    fun aFileAnyRowStillNamesIsKept() {
        assertTrue(named(courses = listOf(course(1, letter, scan))))
        assertTrue(named(visits = listOf(visit(1, scan))))
        // A lab sheet keeps its files inside its payload rather than in a column of its own.
        assertTrue(named(sheets = listOf(sheet(scan))))
    }

    /**
     * The row a form is editing counts like any other: until its form is saved without the file, the
     * row still opens it through the grant, and a cross tapped on a form then left unsaved lets
     * nothing go.
     */
    @Test
    fun theRowBeingEditedStillNamesItsFileUntilItIsSaved() {
        assertTrue(named(courses = listOf(course(1, scan))))
        assertFalse(named(courses = listOf(course(1))))
    }

    /**
     * A fresh start deletes the lab camera's photographs no row names — a sheet left unsaved — but
     * not one a row names, and not one younger than an hour, which the camera may still be writing.
     */
    @Test
    fun aFreshStartDeletesTheSheetPhotographsNoRowNamesOnceAnHourOld() {
        val dir = folder.newFolder(LAB_CAPTURE_DIR)
        val now = 1_800_000_000_000L
        val hour = 60 * 60 * 1000L
        fun shot(name: String, age: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - age)
        }
        shot("lab-1.jpg", 2 * hour)
        val lost = shot("lab-2.jpg", 2 * hour)
        shot("lab-3.jpg", hour / 12)
        val named = setOf("content://com.pwnall.health.photos/lab-captures/lab-1.jpg", scan)

        assertEquals(listOf(lost), unnamedLabCaptures(dir, named, now))
    }

    /**
     * A backup's rows are merged in as they stand, so a row can name what no picker ever handed
     * over. A path would close the app at the tap and be read with the app's own access; a web page
     * would be sent to the browser.
     */
    @Test
    fun onlyAContentReferenceIsOpened() {
        assertTrue(isAttachmentReference(scan))
        assertTrue(isAttachmentReference("content://$own/progress-photos/1.jpg"))
        assertFalse(isAttachmentReference(database))
        assertFalse(isAttachmentReference("https://example.com/scan.pdf"))
        assertFalse(isAttachmentReference("new.pdf"))
    }

    /**
     * The archive for the doctor copies every attachment of the chosen records into a ZIP that
     * leaves the phone. A file of another app's is one the person picked; of this app's own files
     * only a lab sheet's photograph is ever a row's to name, however the reference spells the
     * provider.
     */
    @Test
    fun ofTheAppsOwnFilesOnlyALabPhotographLeavesInTheArchive() {
        assertTrue(attachmentMayLeave(scan, own))
        // A file picked from the work profile carries its user in front of the provider.
        assertTrue(attachmentMayLeave("content://10@com.android.providers.media.documents/document/image%3A12", own))
        assertTrue(attachmentMayLeave("content://$own/lab-captures/lab-1.jpg", own))

        assertFalse(attachmentMayLeave(database, own))
        assertFalse(attachmentMayLeave("content://$own/shared/backup1.out", own))
        assertFalse(attachmentMayLeave("content://$own/progress-photos/1.jpg", own))
        assertFalse(attachmentMayLeave("content://0@$own/shared/backup1.out", own))
        assertFalse(attachmentMayLeave("content://com.pwnall.health%2Ephotos/shared/backup1.out", own))
        assertFalse(attachmentMayLeave("content://$own/lab-captures/../progress/1.jpg", own))
        assertFalse(attachmentMayLeave("content://$own/shared/x?/lab-captures/lab-1.jpg", own))
    }

    /**
     * A tap hands the viewer a read grant on the reference, and this app's provider serves the report
     * for the doctor and the camera's shots besides its pictures: a row restored from somebody else's
     * file could name either. The app's own pictures still open, as the screens that show them
     * full-size need.
     */
    @Test
    fun aTapNeverOpensTheAppsOwnCache() {
        assertTrue(attachmentOpens(scan, own))
        assertTrue(attachmentOpens("content://$own/progress-photos/1.jpg", own))
        assertTrue(attachmentOpens("content://$own/lab-captures/lab-1.jpg", own))

        assertFalse(attachmentOpens(database, own))
        assertFalse(attachmentOpens("content://$own/shared/backup1.out", own))
        assertFalse(attachmentOpens("content://$own/reports/report.pdf", own))
        assertFalse(attachmentOpens("content://0@$own/progress-photos/1.jpg", own))
        assertFalse(attachmentOpens("content://com.pwnall.health%2Ephotos/progress-photos/1.jpg", own))
    }

    /** The folders a tap opens are the provider's own `files-path` ones, and none of its cache. */
    @Test
    fun theFoldersATapOpensAreThePicturesTheProviderServes() {
        val paths = File("src/main/res/xml/photo_paths.xml").readText()
        fun names(tag: String) = Regex("<$tag\\s+name=\"([^\"]+)\"").findAll(paths).map { it.groupValues[1] }.toList()

        assertTrue(names("files-path").isNotEmpty())
        names("files-path").forEach { assertTrue(it, attachmentOpens("content://$own/$it/1.jpg", own)) }
        names("cache-path").forEach { assertFalse(it, attachmentOpens("content://$own/$it/1.jpg", own)) }
    }

    /**
     * Of the cache, the provider serves the folder the camera and the shares write into and the
     * reports' own, never the root: the backup spool and an import's staged copy lie there, and a URI
     * could name them once it served the whole cache.
     */
    @Test
    fun theProviderServesTwoFoldersOfTheCacheAndNotItsRoot() {
        val paths = File("src/main/res/xml/photo_paths.xml").readText()
        val served = Regex("<cache-path\\s+name=\"[^\"]+\"\\s+path=\"([^\"]+)\"").findAll(paths)

        assertEquals(setOf("$SHARED_CACHE_DIR/", "reports/"), served.map { it.groupValues[1] }.toSet())
        listOf(ROUTE_SHARE_DIR, ROUTINE_SHARE_DIR).forEach { assertTrue(it, it.startsWith("$SHARED_CACHE_DIR/")) }
    }
}
