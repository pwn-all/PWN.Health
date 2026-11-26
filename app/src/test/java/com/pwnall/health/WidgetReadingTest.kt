package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Glance runs a widget's provideGlance once for a session and keeps the session for about 45 seconds;
 * an update inside that time draws the content again from what it already holds. So nothing a widget
 * shows may be read before its content: only handles are taken there — the dao, the widget's id — and
 * the figures are read inside, through provideReading or as WorkWidget collects its own. Read before,
 * a second glass of water would leave the first one's total standing, and a stopped route would keep
 * its Stop.
 */
class WidgetReadingTest {
    private val sources: List<File> = File("src/main/java/com/pwnall/health")
        .listFiles { file -> file.extension == "kt" }.orEmpty().toList()

    /** Every widget class by name, with its source from the class's opening to where its content begins. */
    private val beforeContent: Map<String, String> = sources
        .flatMap { file -> WIDGET.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
        .toMap()

    @Test
    fun theWidgetsAreWhereTheTestThinksTheyAre() {
        val expected = listOf("StepsWidget", "TrackWidget", "LevelWidget", "MultiWidget", "WorkWidget")
        assertTrue(beforeContent.keys.toString(), beforeContent.keys.containsAll(expected))
    }

    @Test
    fun noWidgetReadsWhatItShowsBeforeItsContent() {
        val readEarly = beforeContent
            .mapValues { (_, source) -> READS.filter { it in source } }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), readEarly)
    }

    /**
     * A face in a session reads again on the count refreshWidgets keeps, and on nothing Glance does: a
     * redraw asked of Glance directly paints what the face already held, so a settings page that asked
     * that way would leave a row just chosen waiting for the session to end. Only refreshWidgets
     * asks, and the work widget's own refresh, whose face collects its rows itself.
     */
    @Test
    fun aRedrawIsAskedThroughRefreshWidgets() {
        val asking = sources.filter { ".updateAll(" in it.readText() }.map { it.name }.sorted()
        assertEquals(listOf("Widgets.kt", "WorkWidget.kt"), asking)
    }

    /**
     * A screen collects a flow with the lifecycle's own collector, which stops while the app is out of
     * sight; a bare collectAsState goes on reading the database behind a locked screen. A widget's face
     * has no lifecycle to hand it, so the bare one is the widgets' alone.
     */
    @Test
    fun onlyAWidgetCollectsAFlowWithoutALifecycle() {
        val bare = sources.filter { ".collectAsState(" in it.readText() }.map { it.name }.sorted()

        assertTrue(bare.toString(), bare.isNotEmpty())
        assertEquals(emptyList<String>(), bare.filterNot { "Widget" in it })
    }

    private companion object {
        val WIDGET = Regex(
            """class (\w+) : GlanceAppWidget\(\) \{(.*?)(?:provideContent \{|provideReading\()""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** What a reading looks like in these files: a one-shot query, a flow's first value, the day, the moment. */
        val READS = listOf(
            "Now(",
            ".first()",
            "stepsOn(",
            "sleepOn(",
            "todaysDoses(",
            "WidgetSlots.slots(",
            "LocalDate.now()",
            "currentTimeMillis()",
        )
    }
}
