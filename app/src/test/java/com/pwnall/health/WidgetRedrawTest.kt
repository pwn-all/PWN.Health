package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which widgets the pedometer and the route recorder redraw: not every widget class there is, at
 * most once in five seconds for as long as a walk records, but the ones their writes change — of
 * fourteen classes, a step written can change four and a fix stored one.
 */
class WidgetRedrawTest {
    private fun redrawn(kind: String?): List<String> = widgetsShowing(kind).map { it.javaClass.simpleName }

    /** The day's count, the week's bars, the day's energy and the year's XP are what steps move. */
    @Test
    fun aStepWrittenRedrawsTheWidgetsThatReadSteps() {
        assertEquals(listOf("StepsWidget", "WeekWidget", "EnergyWidget", "LevelWidget"), redrawn(WIDGETS_OF_STEPS))
    }

    /** The open route is the walk widget's alone. */
    @Test
    fun aRouteChangedRedrawsTheWalkWidget() {
        assertEquals(listOf("TrackWidget"), redrawn(WIDGETS_OF_ROUTE))
    }

    /** Neither name is a journal kind, and a kind no widget shows redraws none. */
    @Test
    fun theTwoAreNotTakenForJournalKinds() {
        assertEquals(emptyList<String>(), redrawn("SOMETHING_ELSE"))
        assertFalse("MultiWidget" in redrawn(WIDGETS_OF_STEPS) + redrawn(WIDGETS_OF_ROUTE))
    }

    /**
     * The multi-slot widget is shortcuts, and no journal row changes one, so no write redraws it. A
     * redraw of every widget, which its own screen asks for once a row of shortcuts is saved, still
     * reaches it.
     */
    @Test
    fun noJournalWriteRedrawsTheShortcuts() {
        val kinds = listOf(
            KIND_WATER, KIND_CAFFEINE, KIND_MEASUREMENT, KIND_EAT, KIND_ACTIVITY, KIND_MOOD, KIND_SMOKING,
            KIND_WORK, KIND_MED, KIND_CYCLE, KIND_CYCLE_END, KIND_CYCLE_LOG, KIND_ALCOHOL, KIND_URINATION,
        )
        assertEquals(emptyList<String>(), kinds.filter { "MultiWidget" in redrawn(it) })
        assertTrue("MultiWidget" in redrawn(null))
    }

    /**
     * A tap on a widget redraws what its row can change, as a write from the app does, rather than
     * every widget class at every tap — the water one after logWater has already redrawn the one a
     * glass shows on.
     */
    @Test
    fun aWidgetTapNeverRedrawsEveryWidget() {
        val callback = Regex("""class \w+ : ActionCallback \{.*?\n}""", RegexOption.DOT_MATCHES_ALL)
        val callbacks = File("src/main/java/com/pwnall/health").listFiles().orEmpty()
            .flatMap { file -> callback.findAll(file.readText()).map { it.value }.toList() }
        // The six there are today; a scan that found fewer has stopped reading what it guards.
        assertTrue(callbacks.size.toString(), callbacks.size >= 6)

        val everyWidget = Regex("""refreshWidgets\(\w+\)""")
        assertEquals(
            emptyList<String>(),
            callbacks.filter { everyWidget.containsMatchIn(it) }.map { it.substringBefore(" :") },
        )
    }

    /**
     * A cup with milk from the caffeine dialog is a food row: the energy and the level widgets show
     * it, and a cup of black coffee moves the water widget's drinks alone. Redrawn as a black cup, the
     * milk would wait up to half an hour to reach the day's energy.
     */
    @Test
    fun aCupWithMilkRedrawsTheEnergyAndTheLevelBesideTheDrinks() {
        fun cup(name: String, drink: CaffeineDrink) =
            cupRedrawKinds(cupEntry(name, drink, STANDARD_CUP_ML, "2026-11-18", 1_795_000_000_000L))
                .flatMap(::redrawn)
                .toSet()

        assertEquals(setOf("WaterWidget"), cup("Coffee", CaffeineDrink.COFFEE))
        assertEquals(
            setOf("WaterWidget", "EnergyWidget", "LevelWidget"),
            cup("Cappuccino", CaffeineDrink.CAPPUCCINO),
        )
    }

    /**
     * Every widget is redrawn by some write: a kind, the steps or the route. The shortcuts alone wait
     * for the redraw of every class their own screen asks for. A widget added to the list and to no
     * kind would show the morning's figures until the half-hourly redraw.
     */
    @Test
    fun everyWidgetButTheShortcutsIsRedrawnBySomeWrite() {
        val kinds = listOf(
            WIDGETS_OF_STEPS, WIDGETS_OF_ROUTE,
            KIND_WATER, KIND_CAFFEINE, KIND_MEASUREMENT, KIND_EAT, KIND_ACTIVITY, KIND_MOOD, KIND_SMOKING,
            KIND_WORK, KIND_MED, KIND_CYCLE, KIND_CYCLE_END, KIND_CYCLE_LOG,
        )
        val reached = kinds.flatMap(::redrawn).toSet()

        assertEquals(listOf("MultiWidget"), redrawn(null).filterNot { it in reached })
        assertTrue(reached.toString(), redrawn(null).containsAll(reached))
    }

    /** The service names what it changed at every redraw it asks for; with no kind, every class is asked. */
    @Test
    fun theServiceAlwaysSaysWhatItChanged() {
        val service = File("src/main/java/com/pwnall/health/StepService.kt").readText()
        val asked = Regex("""refreshWidgets\(([^)]*)\)""").findAll(service).map { it.groupValues[1] }.toList()

        assertTrue(asked.toString(), asked.isNotEmpty())
        assertTrue(asked.toString(), asked.all { "WIDGETS_OF_" in it || it.endsWith(", kind") })
    }
}
