package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profile the app counts with is the stored row under the latest weighing, and it is read, never
 * saved: the stored weight and height are the baseline the activity timeline starts from.
 */
class ProfileInEffectTest {
    private val stored = UserProfile(sex = "MALE", weightKg = 95.0, heightCm = 178, birthDate = "1990-04-17")

    @Test
    fun theLatestMeasurementStandsOverTheOnboardingAnswer() {
        val weighed = stored.inEffect(measuredWeightKg = 80.0, measuredHeightCm = null)
        assertEquals(80.0, weighed.weightKg, 0.0)
        assertEquals(178, weighed.heightCm)

        val measured = stored.inEffect(measuredWeightKg = 80.0, measuredHeightCm = 180)
        assertEquals(stored.copy(weightKg = 80.0, heightCm = 180), measured)
    }

    @Test
    fun withNothingMeasuredTheStoredAnswersStand() {
        assertEquals(stored, stored.inEffect(measuredWeightKg = null, measuredHeightCm = null))
    }

    /**
     * A connected scale's reading is kept down to 2 kg, so the query that hands the app the weight in
     * effect is bounded: taking the newest weighing whatever it said, a child weighed on that scale
     * would become the weight of the profile. The bound is the one the editor, the importers and the
     * activity timeline hold a weighing to, and it is written once. Pinned as the other queries are —
     * the database is not opened in a JVM test.
     */
    @Test
    fun theWeightInEffectIsTheNewestOneAPersonCanWeigh() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val data = sources.single { it.name == "Data.kt" }.readText()
        val query = data.substringBefore("fun latestMeasuredWeightKg()").substringAfterLast("@Query(")

        assertTrue(query, "WHERE measurementWeightKg BETWEEN \$MIN_PLAUSIBLE_KG AND \$MAX_PLAUSIBLE_KG " in query)
        assertEquals(20.0..400.0, PLAUSIBLE_KG)
        assertEquals(MIN_PLAUSIBLE_KG..MAX_PLAUSIBLE_KG, PLAUSIBLE_KG)
        val spelledOut = Regex("""\b20\.0\s*(?:\.\.|,| AND )\s*400\.0\b|PLAUSIBLE_WEIGHT_KG""")
        assertEquals(emptyList<String>(), sources.filter { spelledOut.containsMatchIn(it.readText()) }.map { it.name })
    }

    /**
     * The lab page saves the allergens a panel found back into the profile, so it is handed the
     * stored row: given the one in effect, it would write the measured weight and height over the
     * baseline. Pinned in the source because the choice is one argument of a call in a composable —
     * and the argument is named for what it must be, so the next caller reads it at the call: named
     * `profile`, as on the pages that take the one in effect, it would invite that one back.
     */
    @Test
    fun thePageThatSavesTheProfileIsHandedTheStoredRow() {
        val text = File("src/main/java/com/pwnall/health/MainActivity.kt").readText()
        val call = text.substringAfter("-> LabAddScreen(").substringBefore("onBack")

        assertTrue(call, Regex("""\n\s+storedProfile = baseProfile,\n""").containsMatchIn(call))
        val page = File("src/main/java/com/pwnall/health/LabsScreen.kt").readText()
        val saves = Regex("""saveProfile\((\w+)\.copy\(""").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(listOf("storedProfile"), saves)
    }
}
