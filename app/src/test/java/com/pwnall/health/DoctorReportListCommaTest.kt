package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A list on the doctor's sheet is joined with the comma of the language it is printed in. The
 * translations write "、" in Japanese and "،" in Arabic in every sentence of their own, and a list
 * between those sentences joined with a Latin comma would be the one foreign mark on the line. The middle
 * dot between the parts of a cell is one mark in every language and stays. Run on Robolectric
 * because the comma is each language's own string resource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoctorReportListCommaTest {
    private val names = listOf(ReportText.Plain("A"), ReportText.Plain("B"))

    private fun printed(separator: String): String =
        ReportText.Join(names, separator).resolve(RuntimeEnvironment.getApplication().resources)

    @Test
    fun anEnglishListTakesACommaAndACellItsDot() {
        assertEquals("A, B", printed(LIST_COMMA))
        assertEquals("A · B", printed(" · "))
    }

    @Test
    @Config(qualifiers = "+ja")
    fun aJapaneseListTakesTheJapaneseComma() {
        assertEquals("A、B", printed(LIST_COMMA))
        assertEquals("A · B", printed(" · "))
    }

    @Test
    @Config(qualifiers = "+ar")
    fun anArabicListTakesTheArabicComma() {
        assertEquals("A، B", printed(LIST_COMMA))
    }
}
