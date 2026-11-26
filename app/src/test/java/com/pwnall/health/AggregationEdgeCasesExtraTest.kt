package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The rest of the same area: the wait that ends when the date changes, and the widget metadata
 * nothing but a launcher ever reads.
 */
class AggregationEdgeCasesExtraTest {
    private fun hours(millis: Long) = millis / 3_600_000.0

    // -----------------------------------------------------------------------------------------
    // The day
    // -----------------------------------------------------------------------------------------

    /**
     * Havana ends summer time at 01:00, so 4 November 2018 began twice — midnight at -04:00 and
     * again an hour later at -05:00 — and lasted 25 hours. The date changes at the first of the
     * two midnights: resolved to the later one the screen would go on showing yesterday for an hour,
     * which is the mistake this wait exists to stop.
     */
    @Test
    fun aDayWithTwoMidnightsInItStartsAtTheFirstOfThem() {
        val havana = ZoneId.of("America/Havana")

        val noonBefore = ZonedDateTime.of(2018, 11, 3, 12, 0, 0, 0, havana)
        assertEquals(12.0, hours(millisUntilNextDay(noonBefore)), 0.0)

        val firstMidnight = ZonedDateTime.of(2018, 11, 4, 0, 0, 0, 0, havana)
        assertEquals(25.0, hours(millisUntilNextDay(firstMidnight)), 0.0)
    }

    // -----------------------------------------------------------------------------------------
    // The metadata a launcher reads and nothing else does
    // -----------------------------------------------------------------------------------------

    /**
     * A renamed or orphaned info xml fails silently: no compile error, no lint warning, and a
     * widget the launcher quietly refuses to place.
     */
    @Test
    fun everyWidgetProviderNamesAnXmlThatExistsAndEveryXmlIsNamedOnce() {
        val named = PROVIDER_XML.findAll(manifest).map { it.groupValues[1] }.toList()

        assertEquals("two receivers name one xml: $named", named.size, named.toSet().size)
        assertEquals(
            "the info xml files and the providers naming them have parted company",
            widgetInfoFiles.map { it.name.removeSuffix(".xml") }.sorted(),
            named.sorted(),
        )
    }

    /**
     * The multi widget's whole configuration flow hangs off one hand-written class name inside a
     * resource file. Mistype it, or move the filter to another component, and the launcher refuses
     * to place the widget — with nothing but a device to catch it.
     */
    @Test
    fun theConfiguredWidgetNamesTheActivityTheManifestGivesTheConfigureFilterTo() {
        val configured = widgetInfoFiles.mapNotNull { file ->
            CONFIGURE.find(file.readText())?.groupValues?.get(1)?.let { file.name to it }
        }
        assertEquals("widgets declaring android:configure: $configured", 1, configured.size)
        val (xmlName, activity) = configured.single()
        assertTrue("$xmlName names $activity, which is not fully qualified", activity.startsWith("$PACKAGE."))
        val simple = activity.substringAfterLast('.')
        assertTrue("no Kotlin source declares $simple", declaresClass(simple))

        val at = manifest.indexOf(CONFIGURE_ACTION)
        assertTrue("the manifest declares no APPWIDGET_CONFIGURE filter", at >= 0)
        assertEquals("more than one component offers to configure a widget", at, manifest.lastIndexOf(CONFIGURE_ACTION))
        val opens = manifest.lastIndexOf("<activity", at)
        assertTrue("no <activity> encloses the configure filter", opens >= 0)
        val header = manifest.substring(opens, at)
        assertTrue("the configure filter is not $simple's", header.contains("android:name=\".$simple\""))
        assertFalse("the configure filter is outside $simple's own element", header.contains("</activity>"))
    }

    private val manifest: String = File("src/main/AndroidManifest.xml")
        .takeIf { it.isFile }
        ?.readText()
        ?: error("No AndroidManifest.xml beside the module. Unit tests run from the module directory.")

    private val widgetInfoFiles: List<File> = File("src/main/res/xml")
        .walkTopDown()
        .filter { it.name.endsWith("_widget_info.xml") }
        .toList()
        .ifEmpty { error("No *_widget_info.xml under app/src/main/res/xml; update this test, do not delete it.") }

    private fun declaresClass(simpleName: String) = File("src/main/java/com/pwnall/health")
        .walkTopDown()
        .filter { it.name.endsWith(".kt") }
        .any { Regex("""\bclass $simpleName\b""").containsMatchIn(it.readText()) }

    private companion object {
        const val PACKAGE = "com.pwnall.health"
        const val CONFIGURE_ACTION = "android.appwidget.action.APPWIDGET_CONFIGURE"

        val PROVIDER_XML = Regex("""android\.appwidget\.provider"\s+android:resource="@xml/(\w+)"""")
        val CONFIGURE = Regex("""android:configure="([\w.]+)"""")
    }
}
