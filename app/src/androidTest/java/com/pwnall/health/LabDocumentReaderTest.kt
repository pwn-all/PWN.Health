package com.pwnall.health

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Exercises Android decoding/rendering and the shipped ONNX models, with synthetic documents. */
@RunWith(AndroidJUnit4::class)
class LabDocumentReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun cameraAttachmentsAreDistinctDurableFiles() {
        val first = newLabCapture(context)
        val second = newLabCapture(context)
        try {
            assertNotEquals(first, second)
            assertEquals("lab-captures", first.pathSegments.first())
            context.contentResolver.openOutputStream(first)!!.use { it.write(byteArrayOf(1, 2, 3)) }
            context.contentResolver.openOutputStream(second)!!.use { it.write(byteArrayOf(4, 5)) }
            val original = context.contentResolver.openInputStream(first)!!.use { it.readBytes() }
            assertTrue(original.contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(File(context.filesDir, "lab-captures/${first.lastPathSegment}").isFile)
        } finally {
            context.contentResolver.delete(first, null, null)
            context.contentResolver.delete(second, null, null)
        }
    }

    @Test
    fun pdfPagesStaySeparateAndMatchPhoto() = runBlocking {
        val pdf = File.createTempFile("lab-test-", ".pdf", context.cacheDir)
        val png = File.createTempFile("lab-test-", ".png", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(1000, 600, index + 1).create())
                    draw(page.canvas, if (index == 0) "90" else "108")
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val bitmap = createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap), "90")
                png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            val progress = mutableListOf<Pair<Int, Int>>()
            val pages = LabDocumentReader.read(
                context,
                Uri.fromFile(pdf),
                today,
                false,
                password = { throw AssertionError("An unlocked PDF asked for a password") },
            ) { page, total ->
                progress += page to total
            }
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            assertEquals(2, pages.size)
            assertEquals(90 / 18.016, pages[0].readings.single().value, 0.001)
            assertEquals(108 / 18.016, pages[1].readings.single().value, 0.001)
            val photo = LabDocumentReader.read(context, Uri.fromFile(png), today, false).single()
            assertEquals(pages[0].readings.single().value, photo.readings.single().value, 0.001)
            assertEquals(LocalDate.of(2026, 9, 20), photo.day)
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("lab-import-") })
        } finally {
            pdf.delete()
            png.delete()
        }
    }

    /** lab-locked.pdf: the sheet [draw] makes with "90", AES-128 under the user password 1234 (pypdf). */
    @Test
    fun lockedPdfAsksUntilItsPasswordOpensIt() = runBlocking {
        val file = File.createTempFile("lab-test-", ".pdf", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("lab-locked.pdf").use { source ->
                file.outputStream().use(source::copyTo)
            }
            val asked = mutableListOf<Boolean>()
            val typed = ArrayDeque(listOf("0000", "1234"))
            val page = LabDocumentReader.read(context, Uri.fromFile(file), today, false, password = { wrong ->
                asked += wrong
                typed.removeFirst()
            }).single()
            assertEquals(listOf(false, true), asked)
            assertEquals(90 / 18.016, page.readings.single().value, 0.001)
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("lab-import-") })
        } finally {
            file.delete()
        }
    }

    @Test
    fun oversizedPdfIsRejectedWithoutPartialResults() = runBlocking {
        val file = File.createTempFile("lab-test-", ".pdf", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                repeat(21) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(100, 100, index + 1).create())
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val failure = runCatching {
                LabDocumentReader.read(context, Uri.fromFile(file), today, false)
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("lab-import-") })
        } finally {
            file.delete()
        }
    }

    private fun draw(canvas: Canvas, value: String) {
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 30f
        }
        canvas.drawText("Laboratory results", 40f, 70f, paint)
        canvas.drawText("Collected 2026-09-20", 40f, 125f, paint)
        canvas.drawText("Glucose", 40f, 240f, paint)
        canvas.drawText(value, 370f, 240f, paint)
        canvas.drawText("mg/dL", 570f, 240f, paint)
        canvas.drawText("70 - 110", 780f, 240f, paint)
    }
}
