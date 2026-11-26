package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every widget this app ships shows a figure scoped to today, and a day rolls over with nobody
 * touching the phone. The redraw a write triggers cannot cover that — there is no write — so each
 * provider has to declare a period of its own, or the home screen keeps yesterday until the person
 * happens to log something.
 */
class WidgetMetadataTest {
    private val providers: List<File> = File("src/main/res/xml")
        .walkTopDown()
        .filter { it.name.endsWith("_widget_info.xml") }
        .toList()
        .ifEmpty {
            error(
                "No *_widget_info.xml under app/src/main/res/xml. " +
                    "The widget metadata moved; update this test rather than deleting it.",
            )
        }

    @Test
    fun everyWidgetDeclaresThePeriodicUpdateItNeedsToSurviveMidnight() {
        providers.forEach { provider ->
            val declared = PERIOD.find(provider.readText())?.groupValues?.get(1)?.toInt() ?: 0
            assertEquals(provider.name, MINIMUM_PERIOD_MS, declared)
        }
    }

    /**
     * With no initialLayout the host paints "Problem loading widget" until the first update, and
     * after a boot that can be the half-hourly one. What it paints instead can stand just as long, so
     * it sits on the widget's own ground and shows no figure the person did not record.
     */
    @Test
    fun everyWidgetLandsOnAFigureFreeFaceOfItsOwn() {
        val english = File("src/main/res/values").listFiles { file -> file.extension == "xml" }.orEmpty()
            .flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .toMap()
        providers.forEach { provider ->
            val layout = INITIAL.find(provider.readText())?.groupValues?.get(1)
            assertNotNull("${provider.name} declares no android:initialLayout", layout)
            val face = File("src/main/res/layout/$layout.xml").readText()
            assertTrue("$layout paints no ground", GROUND in face)
            assertTrue("$layout is not the widget background", BACKGROUND_ID in face)
            val figures = STRING_REF.findAll(face).map { it.groupValues[1] }
                .filter { name -> english.getValue(name).any(Char::isDigit) }
                .toList()
            assertEquals("$layout shows figures nobody recorded", emptyList<String>(), figures)
        }
    }

    /**
     * The words on a widget are put there by this process in the language of the moment, and the
     * launcher keeps them: after the phone's language changes, every face would go on in the old one
     * until its half-hourly update. GlanceAppWidgetReceiver draws its widgets again on
     * LOCALE_CHANGED, which reaches a receiver only through its manifest filter.
     */
    @Test
    fun everyWidgetIsDrawnAgainWhenThePhonesLanguageChanges() {
        val receivers = File("src/main/AndroidManifest.xml").readText().replace(COMMENT, "")
            .split("<receiver").drop(1)
            .map { it.substringBefore("</receiver>") }
            .filter { "android.appwidget.provider" in it }

        assertEquals(providers.size, receivers.size)
        receivers.forEach { assertTrue(it.substringBefore(" android:exported"), LOCALE_CHANGED in it) }
    }

    /**
     * The cycle widget names a period and the next one expected, and not the fertile days: those are
     * an estimate the app never shows without saying it is no method of contraception, and two short
     * lines on a launcher cannot say it. Pinned in the source, where the caption is chosen.
     */
    @Test
    fun theCycleWidgetDoesNotNameTheFertileDays() {
        val lines = File("src/main/java/com/pwnall/health/DoseWidgets.kt").readText()
            .substringAfter("internal fun cycleLines(")
            .substringBefore("\n}\n")

        assertTrue("they are captioned as the days around them", FERTILE_AS_ORDINARY in lines)
        assertEquals("and by nothing of their own", 1, lines.split("CyclePhase.Fertile").size - 1)
    }

    private companion object {
        const val FERTILE_AS_ORDINARY = "is CyclePhase.Fertile, is CyclePhase.Ordinary -> summary.nextExpected?.let {"
        val PERIOD = Regex("""android:updatePeriodMillis="(\d+)"""")
        val INITIAL = Regex("""android:initialLayout="@layout/([a-z0-9_]+)"""")
        val STRING_REF = Regex("""@string/([a-z0-9_]+)""")
        val STRING = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        const val GROUND = """android:background="?android:attr/colorBackground""""
        const val BACKGROUND_ID = """android:id="@android:id/background""""
        const val LOCALE_CHANGED = """<action android:name="android.intent.action.LOCALE_CHANGED" />"""
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        // The platform delivers a periodic update no more than once every 30 minutes, so asking for
        // less is asking for nothing, and asking for more is the same thing spelled differently.
        const val MINIMUM_PERIOD_MS = 1_800_000
    }
}
