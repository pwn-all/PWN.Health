package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The figures that could reach a screen with the point whatever the language: the water in fluid
 * ounces, on its card and its widget; the load of a workout template's sets; a lab value in the
 * advice the home card and its notification read out; a drink's strength in the day list. Each
 * takes the mark of the language showing, as the weight beside them does. Run on
 * Robolectric because the language is the resources'.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ScreenDecimalMarkTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    private val triglycerides = Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.CLINICIAN,
        titleRes = R.string.advice_labs_tg_urgent_title,
        bodyRes = R.string.advice_labs_tg_urgent_body,
        args = listOf(PrintedFigure("11.35"), Labeled(LabMarker.TRIGLYCERIDES.unitLabelRes(false))),
        sourceRes = R.string.advice_source_labs,
        windowDays = 1,
        id = "labs-tg-urgent",
    )

    private val sets = listOf(RoutineSet(reps = 10, loadKg = 22.5), RoutineSet(reps = 10, loadKg = 22.5))

    @Test
    @Config(qualifiers = "+ru")
    fun aRussianScreenReadsACommaInEachOfThem() {
        val water = waterOfTargetText(resources, 500, 2_500, imperial = true)
        assertTrue(water, "16,9" in water && "84,5" in water)
        assertTrue(routineSetsText(resources, sets, imperial = false).contains("22,5"))
        val advice = resources.formatAdviceBody(triglycerides)
        assertTrue(advice, "11,35" in advice)
        assertFalse(advice, "11.35" in advice)
        // A drink's strength, which the row keeps as a number, not as the text of the day it was poured.
        assertEquals("Вино · 175 мл · 13,5%", pourText(resources, Pour(AlcoholDrink.WINE, 175, 135)))
    }

    @Test
    fun anEnglishScreenKeepsThePoint() {
        assertEquals("16.9 of 84.5 fl oz", waterOfTargetText(resources, 500, 2_500, imperial = true))
        assertTrue(routineSetsText(resources, sets, imperial = false).contains("22.5"))
        assertTrue("11.35" in resources.formatAdviceBody(triglycerides))
    }
}
