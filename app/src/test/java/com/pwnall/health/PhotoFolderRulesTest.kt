package com.pwnall.health

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The folders the app keeps its pictures in, as the code names them, against the two XML files that
 * name them again: the transfer rules, which carry them to a new phone, and the provider's paths,
 * which hand them to a viewer. A folder renamed in one place and not the other fails nothing at
 * build time — the move to a new phone arrives with rows pointing at nothing, or the tap on a
 * picture throws in getUriForFile. [TrackPhotoBackupTest] holds the walk photographs alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PhotoFolderRulesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** The folder as the XML spells it: its path under the app's files, with a trailing slash. */
    private fun folder(dir: File): String = dir.relativeTo(context.filesDir).path + "/"

    /** The [name] attribute of every [element] in [xml]. */
    private fun attribute(xml: String, element: String, name: String): Set<String> =
        Regex("""<$element\s+[^>]*?$name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test
    fun aMoveToANewPhoneCarriesEveryFolderOfPictures() {
        val transfer = File("src/main/res/xml/data_extraction_rules.xml").readText()
            .substringAfter("<device-transfer>")
            .substringBefore("</device-transfer>")
        val carried = attribute(transfer, "include", "path")
        val pictures = listOf(
            progressPhotoDir(context),
            medicinePhotoDir(context),
            walkPhotoDir(context),
            routinePhotoDir(context),
            labCaptureDir(context),
        ).map(::folder)

        assertEquals(emptyList<String>(), pictures.filterNot { it in carried })
    }

    /** The folders whose pictures a viewer is handed full-size, each one the provider serves. */
    @Test
    fun everyFolderAViewerOpensIsOneTheProviderServes() {
        val served = attribute(File("src/main/res/xml/photo_paths.xml").readText(), "files-path", "path")
        val opened = listOf(
            progressPhotoDir(context),
            medicinePhotoDir(context),
            walkPhotoDir(context),
            labCaptureDir(context),
        ).map(::folder)

        assertEquals(emptyList<String>(), opened.filterNot { it in served })
    }
}
