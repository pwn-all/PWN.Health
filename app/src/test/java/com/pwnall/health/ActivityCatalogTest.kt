package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The catalogue is data, and data with a duplicate code silently makes one exercise unreachable —
 * the editor looks a spec up by code and takes the first match.
 */
class ActivityCatalogTest {
    @Test
    fun everyCodeIsUsedOnce() {
        val codes = ACTIVITY_CATALOG.map(ActivitySpec::code)

        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun everyExerciseSaysWhatItWorksAndCostsSomething() {
        ACTIVITY_CATALOG.forEach { spec ->
            assertTrue(spec.code, spec.muscles.isNotEmpty())
            // Below one MET is below lying still, which no exercise is.
            assertTrue("${spec.code} ${spec.met}", spec.met > 1.0)
            assertTrue("${spec.code} ${spec.met}", spec.met < 20.0)
        }
    }

    @Test
    fun weightedInputIsOnlyAskedForWhereALoadExists() {
        val weighted = ACTIVITY_CATALOG.filter { it.input == ActivityInput.WEIGHTED_SETS }

        assertTrue(weighted.map(ActivitySpec::code).contains("DEADLIFT"))
        assertTrue(ACTIVITY_CATALOG.first { it.code == "WALK" }.input == ActivityInput.DURATION)
    }

    /** Push-ups and lunges are reps × kg, and the kilograms on top of the body may be nought. */
    @Test
    fun aBodyWeightMoveTakesKilogramsOnTop() {
        val byCode = ACTIVITY_CATALOG.associateBy(ActivitySpec::code)

        assertTrue(byCode.getValue("PUSHUPS").bodyWeight)
        assertTrue(byCode.getValue("LUNGES").bodyWeight)
        ACTIVITY_CATALOG.filter(ActivitySpec::bodyWeight).forEach {
            assertEquals(it.code, ActivityInput.WEIGHTED_SETS, it.input)
        }
    }

    @Test
    fun theCatalogueIsWorthOpeningAPickerFor() {
        assertTrue("${ACTIVITY_CATALOG.size} exercises", ACTIVITY_CATALOG.size >= 30)
    }

    /**
     * A bare `lowercase()` is the invariant fold: the Turkish capital İ comes out of it as an i and a
     * combining dot, so "ip" would not find "İp atlama". Both pickers fold the translated names and the
     * query by the language the names are in, so it does, and the dotless ı of that language stays
     * apart from the dotted one. The name is read from the Turkish resources, so this
     * holds for the string as it is shipped.
     */
    @Test
    fun theSearchFoldsCaseAsTheLanguageOnScreenDoes() {
        val turkish = Locale.forLanguageTag("tr")
        val ropeSkipping = Regex("""<string name="activity_jump_rope">([^<]+)</string>""")
            .find(File("src/main/res/values-tr/ui.xml").readText())!!.groupValues[1]

        assertEquals("İp atlama", ropeSkipping)
        assertFalse("ip" in ropeSkipping.lowercase())
        assertTrue("ip".searchFolded(turkish) in ropeSkipping.searchFolded(turkish))
        assertTrue(" İP ".searchFolded(turkish) in ropeSkipping.searchFolded(turkish))
        assertFalse("ıp".searchFolded(turkish) in ropeSkipping.searchFolded(turkish))
        // Elsewhere nothing moves: every other language folds as a plain lowercase does.
        assertEquals("jump rope", "  Jump Rope ".searchFolded(Locale.ENGLISH))
        assertEquals("прыжки", "Прыжки".searchFolded(Locale.forLanguageTag("ru")))
    }
}
