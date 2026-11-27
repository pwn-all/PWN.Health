package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A figure a string resource formats on the doctor's sheet is in Latin digits, as every other figure
 * on it is, while the words around it stay the reader's. Run on Robolectric because the digits come
 * from the locale of the resources the sheet is printed with, [sheetResources], which only the
 * framework builds. A test of the locale alone passes just the same when the sheet is printed with
 * the phone's own resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoctorReportSheetDigitsTest {
    private val page = ReportText.Res(R.string.report_page, listOf(3, 12))

    /** Arabic counts in digits of its own, and the page number is a figure its resources format. */
    @Test
    @Config(qualifiers = "+ar")
    fun anArabicSheetNumbersItsPagesInLatinDigits() {
        val app = RuntimeEnvironment.getApplication()

        assertFalse(page.resolve(app.resources).contains("12"))
        assertEquals("صفحة 3 من 12", page.resolve(sheetResources(app)))
    }
}
