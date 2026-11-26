package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which form the editor's form scroll belongs to — see [formScrollKey]. A scroll keyed as it was in
 * the last composition is the one kept; any change of key starts a new one at the top.
 */
class EditorFormScrollTest {
    /** Food A's form, the hub, food B's form: both steps change the key, so B's form opens at its top. */
    @Test
    fun aSecondFoodOpensItsFormAtTheTop() {
        val (first, hub, second) = listOf(FoodStep.EDIT, FoodStep.HUB, FoodStep.EDIT).map { formScrollKey("EAT", it) }
        assertNotEquals(first, hub)
        assertNotEquals(hub, second)
    }

    /** Every page of the meal flow stands in for the form, and none of them shares the form's key. */
    @Test
    fun noMealStepButTheFormHoldsTheFormsScroll() {
        FoodStep.entries.filter { it != FoodStep.EDIT }.forEach {
            assertNotEquals(formScrollKey("EAT", FoodStep.EDIT), formScrollKey("EAT", it))
        }
    }

    /**
     * Any other kind keeps one scroll whatever the meal step reads — HUB on every new entry — so a
     * workout keeps its place under the summary and the templates, and a weight under the gallery.
     */
    @Test
    fun otherKindsIgnoreTheMealStep() {
        listOf("ACTIVITY", "MEASUREMENT", "NOTE").forEach { kind ->
            FoodStep.entries.forEach { assertEquals(kind, formScrollKey(kind, it)) }
        }
    }

    /** A different kind is a different form, at its own top. */
    @Test
    fun eachKindHasAScrollOfItsOwn() {
        assertNotEquals(formScrollKey("ACTIVITY", FoodStep.HUB), formScrollKey("MEASUREMENT", FoodStep.HUB))
        assertNotEquals(formScrollKey("ACTIVITY", FoodStep.HUB), formScrollKey("EAT", FoodStep.EDIT))
    }
}
