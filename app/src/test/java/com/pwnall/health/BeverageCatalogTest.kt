package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeverageCatalogTest {
    @Test
    fun drinksAreSearchableInBothLanguagesAndWithCommonMisspellings() {
        mapOf(
            "коффе" to "coffee_black",
            "лате" to "latte",
            "матча на воде" to "matcha_tea",
            "matcha tea" to "matcha_tea",
            "матча латте" to "matcha_latte",
        ).forEach { (text, id) ->
            assertTrue(text, searchTopFoods(text).any { it.id == id })
        }
    }

    @Test
    fun existingPhotoDrinkClassesOpenTheRightCatalogueRows() {
        mapOf(
            "espresso" to "coffee_espresso",
            "latte_macchiato" to "latte",
            "white_coffee" to "latte",
            "cola_based_drink" to "cola",
        ).forEach { (label, id) ->
            assertTrue(label, label in IMAGENET_LABELS)
            assertEquals(id, requireNotNull(productFor(label, 0.9f)).items.single().food.id)
        }
    }
}
