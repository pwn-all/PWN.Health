package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The energy beside each cup, from USDA FoodData Central, and where it is counted: a milk drink is a
 * meal carrying its caffeine, and a black cup stays a caffeine row rather than turning into a meal.
 */
class CaffeineEnergyTest {
    private val day = "2026-10-03"
    private val at = 1_790_000_000_000L

    @Test
    fun aBrewedCupScalesItsEnergyWithTheCup() {
        // FNDDS: brewed coffee and black tea are 1 kcal per 100 g each.
        assertEquals(1, kcalForCup(CaffeineDrink.COFFEE, 100))
        assertEquals(3, kcalForCup(CaffeineDrink.COFFEE, STANDARD_CUP_ML))
        assertEquals(5, kcalForCup(CaffeineDrink.COFFEE, 500))
        assertEquals(3, kcalForCup(CaffeineDrink.TEA, STANDARD_CUP_ML))
        // Matcha: 2 g of a 250 kcal-per-100 g powder in the standard cup, scaled by the cup.
        assertEquals(2, kcalForCup(CaffeineDrink.MATCHA, 100))
        assertEquals(5, kcalForCup(CaffeineDrink.MATCHA, STANDARD_CUP_ML))
        assertEquals(8, kcalForCup(CaffeineDrink.MATCHA, 500))
    }

    @Test
    fun anEspressoIsItsShotWhateverTheCup() {
        // 60 ml at FNDDS's 9 kcal per 100 g, in every size.
        CUP_SIZES.forEach { (_, millilitres) ->
            assertEquals(ESPRESSO_SHOT_ML, energyMillilitres(CaffeineDrink.ESPRESSO, millilitres))
            assertEquals(5, kcalForCup(CaffeineDrink.ESPRESSO, millilitres))
        }
    }

    @Test
    fun aCappuccinosEnergyIsTheMilkAndScalesWithTheCup() {
        // Its caffeine is the shot in every cup; its 27 kcal per 100 g is nearly all milk.
        assertEquals(27, kcalForCup(CaffeineDrink.CAPPUCCINO, 100))
        assertEquals(81, kcalForCup(CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML))
        assertEquals(135, kcalForCup(CaffeineDrink.CAPPUCCINO, 500))
        assertEquals(80, caffeineForCup(CaffeineDrink.CAPPUCCINO, 500))
    }

    @Test
    fun aCappuccinoIsLoggedAsAMealCarryingItsCaffeine() {
        val row = cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML, day, at)
        assertEquals("EAT", row.kind)
        assertEquals(81.0, row.calories, 1e-9)
        assertEquals(5.13, row.proteinG, 1e-9)
        assertEquals(2.97, row.fatG, 1e-9)
        assertEquals(8.25, row.carbsG, 1e-9)
        assertEquals(300.0, row.servingG, 0.0)
        assertEquals(80.0, row.caffeineMg ?: 0.0, 0.0)
        assertEquals("Cappuccino", row.title)
        assertEquals(at, row.occurredAt)
    }

    @Test
    fun aBlackCupStaysACaffeineRowWithNoEnergy() {
        val black = listOf(CaffeineDrink.TEA, CaffeineDrink.MATCHA, CaffeineDrink.ESPRESSO, CaffeineDrink.COFFEE)
        black.forEach { drink ->
            assertFalse(drink.withMilk)
            val row = cupEntry("Cup", drink, STANDARD_CUP_ML, day, at)
            assertEquals(KIND_CAFFEINE, row.kind)
            assertEquals(0.0, row.calories, 0.0)
            assertEquals(caffeineForCup(drink, STANDARD_CUP_ML).toDouble(), row.servingG, 0.0)
            assertNull(row.caffeineMg)
        }
        assertTrue(CaffeineDrink.CAPPUCCINO.withMilk)
    }

    /**
     * Through [caffeineMgOf], which the dialog's total, the doctor's report, the report of changes
     * and the Health Connect export all read; the report's own row is in CaffeineCupMealTest.
     */
    @Test
    fun theCappuccinoCountsInTheDaysCaffeine() {
        val coffee = cupEntry("Coffee", CaffeineDrink.COFFEE, STANDARD_CUP_ML, day, at)
        val cappuccino = cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, 500, day, at + 60_000)
        val lunch = JournalEntry(day = day, occurredAt = at, kind = "EAT", title = "Soup", calories = 200.0)
        assertEquals(135.0, caffeineMgOf(coffee) ?: 0.0, 0.0)
        assertEquals(80.0, caffeineMgOf(cappuccino) ?: 0.0, 0.0)
        assertNull("a meal stating no caffeine is not a meal with none", caffeineMgOf(lunch))
        assertEquals(135.0 + 80.0, dayCaffeineMg(listOf(coffee, cappuccino, lunch)), 0.0)
    }
}
