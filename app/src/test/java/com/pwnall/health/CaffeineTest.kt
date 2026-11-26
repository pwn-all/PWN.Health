package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal counts milligrams and Health Connect stores grams, so the conversion is the one place
 * a mistake would publish a thousandfold dose.
 */
class CaffeineTest {
    @Test
    fun aMugOfCoffeeIsAlmostATenthOfAGram() {
        assertEquals(0.095, caffeineGrams(95.0), 1e-9)
    }

    @Test
    fun anImpossibleAmountIsNotNegative() {
        assertEquals(0.0, caffeineGrams(-10.0), 1e-9)
    }

    @Test
    fun noStandardCupIsASingleDoseEfsaFlags() {
        // EFSA 2015 puts a single dose at 200 mg. At the real figures one of every preset in a day
        // is over the 400 mg daily line — five caffeinated drinks is — so the invariant worth holding
        // is per cup: no preset at the standard cup reaches the single dose, and none rounds to zero.
        assertTrue(CaffeineDrink.entries.all { caffeineForCup(it, STANDARD_CUP_ML) in 1 until 200 })
        assertTrue(CaffeineDrink.entries.all { drink -> CUP_SIZES.all { caffeineForCup(drink, it.second) > 0 } })
    }

    @Test
    fun noPresetIsOffByADigit() {
        // The failure that matters: 950 mg reads like a cup on screen and is a hospital visit.
        assert(CaffeineDrink.entries.all { it.milligrams in 20..200 })
    }

    /**
     * EFSA 2015's filter coffee is 90 mg per 200 ml; on the 300 ml mug that is 135, and the S and L
     * cups scale it by volume.
     */
    @Test
    fun aBrewedCupScalesTheStandardDose() {
        assertEquals(135, caffeineForCup(CaffeineDrink.COFFEE, STANDARD_CUP_ML))
        assertEquals(45, caffeineForCup(CaffeineDrink.COFFEE, 100))
        assertEquals(225, caffeineForCup(CaffeineDrink.COFFEE, 500))
        assertEquals(70, caffeineForCup(CaffeineDrink.TEA, STANDARD_CUP_ML))
    }

    /**
     * An espresso is a 60 ml shot whatever it is served in, and a cappuccino is that shot under
     * milk. Scaled by the cup, an S espresso would log 20 mg and an L one 100; both are the shot.
     */
    @Test
    fun aShotIsTheSameDoseInEveryCup() {
        listOf(CaffeineDrink.ESPRESSO, CaffeineDrink.CAPPUCCINO).forEach { shot ->
            assertTrue(shot.fixedDose)
            CUP_SIZES.forEach { (_, millilitres) -> assertEquals(80, caffeineForCup(shot, millilitres)) }
        }
        assertFalse(CaffeineDrink.COFFEE.fixedDose)
        assertFalse(CaffeineDrink.TEA.fixedDose)
    }
}
