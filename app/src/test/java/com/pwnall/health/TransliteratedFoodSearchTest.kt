package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search through Android's own ICU, which Robolectric's platform jar carries with its data: a query
 * in another script also tries its Latin spelling against the English names. The plain JVM tests
 * have no ICU and match directly, so this is the one place the spelling is seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TransliteratedFoodSearchTest {
    /** BGN/PCGN, the way English spells a borrowed Russian name, and nothing for Latin. */
    @Test
    fun spellsOtherScriptsTheWayEnglishBorrowsThem() {
        // As typed, the way searchTopFoods passes it: folding the marks first would make «й» «и» and ビ ヒ.
        mapOf(
            "биг мак" to "big mak",
            "хачапури" to "khachapuri",
            "Йогурт" to "yogurt",
            "пельмени" to "pelmeni",
            "γύρος" to "gyros",
            "ビッグマック" to "biggumakku",
        ).forEach { (query, latin) -> assertEquals(query, latin, latinSpelling(query)) }
        assertNull(latinSpelling("big mac"))
        assertNull(latinSpelling("strauß"))
    }

    /** Rows with no Russian in them at all, which only the Latin spelling can reach. */
    @Test
    fun aCyrillicSpellingReachesTheEnglishName() {
        assertTrue("muffin_plain" in searchTopFoods("маффин", limit = 60).map { it.id })
        assertTrue("nachos_nfs" in searchTopFoods("начос", limit = 60).map { it.id })
        assertTrue("miso" in searchTopFoods("мисо", limit = 60).map { it.id })
        assertEquals("tiramisu", searchTopFoods("tiramisu").first().id)
    }

    /** A guessed spelling never outranks a name the person typed. */
    @Test
    fun aDirectMatchAlwaysRanksFirst() {
        val rows = searchTopFoods("начос", limit = TOP_FOODS.size)
        val direct = rows.map { foodSearchScore(it, "начос", listOf("начос")) != null }

        assertTrue(direct.any { it } && direct.any { !it })
        assertEquals(direct.sortedDescending(), direct)
    }

    /**
     * A short guess counts only at the start of a name or alias: "мед" as "med" would fill a page
     * with MCT oil and smørrebrød, and a slip of two letters would make "брауни" every bran.
     */
    @Test
    fun aShortOrLooseGuessDoesNotFloodThePage() {
        mapOf("мед" to "med", "сало" to "salo").forEach { (query, latin) ->
            searchTopFoods(query, limit = 60)
                .filter { foodSearchScore(it, query, listOf(query)) == null }
                .forEach { food -> assertTrue(food.id, food.namePhrases.any { it.startsWith(latin) }) }
        }
        val brownies = searchTopFoods("брауни", limit = 60)
            .filter { foodSearchScore(it, "брауни", listOf("брауни")) == null }
        assertTrue(brownies.map { it.id }.toString(), brownies.none { "bran" in it.id })
    }
}
