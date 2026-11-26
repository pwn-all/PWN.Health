package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The vitamin table: what the meals and the marked doses add up to, and what that sum is allowed to
 * say against the daily figure and the upper levels.
 *
 * The rule under every case: the diary is a floor. What it knows can reach a figure or pass a limit;
 * it can fall short of one only where nothing could be missing — every meal carrying the figure,
 * every dose marked, an allowance rather than an adequate intake, and neither vitamin D nor niacin.
 * The foods are the catalogue's own rows, so the figures are USDA's as the app ships them.
 */
class NutrientTableTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val from: LocalDate = today.minusDays(29)
    private val zone: ZoneId = ZoneOffset.UTC

    /** Forty on the last day of the window: the adult tables, a man's figures. */
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1986-03-01")

    private fun at(day: LocalDate): Long = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun meal(day: LocalDate, source: String?, kcal: Double = 100.0, title: String = "Typed at home") =
        JournalEntry(
            day = day.toString(),
            occurredAt = at(day),
            kind = "EAT",
            title = title,
            calories = kcal,
            foodSource = source,
        )

    /** [count] days up to today, each holding two meals of [source] — full days, every meal known. */
    private fun days(count: Int, source: String, kcal: Double = 100.0): List<JournalEntry> =
        (0 until count).flatMap { back -> List(2) { meal(today.minusDays(back.toLong()), source, kcal) } }

    /** A meal nobody picked from the catalogue on each of the same days, carrying most of the energy. */
    private fun typedMeals(count: Int): List<JournalEntry> =
        (0 until count).map { back -> meal(today.minusDays(back.toLong()), source = null, kcal = 600.0) }

    private fun course(contents: String, id: Long = 1) = Medication(
        id = id,
        name = "Supplement",
        dose = "1 tablet",
        times = "480",
        startDay = from.toString(),
        contents = contents,
        createdAt = 0,
    )

    /** The rows the screen writes when the dose is ticked, on the last [count] days. */
    private fun marks(course: Medication, count: Int): List<JournalEntry> = (0 until count).map { back ->
        val day = today.minusDays(back.toLong())
        medTakenEntry(plannedDoses(listOf(course), emptyList(), day).first(), day, at(day))
    }

    private fun table(
        entries: List<JournalEntry>,
        medications: List<Medication> = emptyList(),
        profile: UserProfile = man,
        food: Boolean = true,
        supplements: Boolean = true,
    ) = nutrientTable(entries, medications, profile, from, today, today, 24 * 60, zone, food, supplements)

    private fun NutrientTable.row(nutrient: Nutrient): NutrientRow = rows.first { it.nutrient == nutrient }

    private fun per100(id: String, micro: FoodMicro): Double = TOP_FOOD_MICROS.getValue(id)[micro]!!

    @Test
    fun everyMealCarryingItOverFourteenDaysMayReadBelowAnAllowance() {
        val table = table(days(14, "orange=100.0"))
        val iron = table.row(Nutrient.IRON)

        assertEquals(NUTRIENT_TABLE_ORDER, table.rows.map(NutrientRow::nutrient))
        assertEquals(2 * per100("orange", FoodMicro.IRON), iron.food!!.meanPerDay!!, 1e-9)
        assertEquals(NutrientVerdict.BELOW_BY_ENTRIES, iron.verdict)
    }

    /** One meal a day nobody picked from the catalogue, and the oranges are only what is known. */
    @Test
    fun aMealWithoutAFigureLeavesAFloorThatCannotSayBelow() {
        val iron = table(days(14, "orange=100.0") + typedMeals(14)).row(Nutrient.IRON)

        assertNull(iron.food!!.meanPerDay)
        assertEquals(2 * per100("orange", FoodMicro.IRON), iron.foodFloor!!, 1e-9)
        assertEquals(NutrientVerdict.CANNOT_SAY, iron.verdict)
    }

    /**
     * Fourteen days of oranges and sixteen of meals nobody picked from the catalogue: the mean over
     * the counted days is past vitamin C's allowance, but it is the mean of the days whose meals were
     * known. Spread over the thirty logged days the oranges fall short of it, and that is what is read.
     */
    @Test
    fun theMeanOfTheKnownDaysDoesNotReachAFigureForTheMonth() {
        val typedDays = (14 until 30).flatMap { back -> List(2) { meal(today.minusDays(back.toLong()), null, 600.0) } }
        val vitaminC = table(days(14, "orange=100.0") + typedDays).row(Nutrient.VITAMIN_C)
        val oranges = 2 * per100("orange", FoodMicro.VITAMIN_C)

        assertTrue("two oranges hold $oranges mg", oranges >= vitaminC.reference!!.amount)
        assertEquals(oranges, vitaminC.foodFloor!!, 1e-9)
        assertEquals(oranges * 14 / 30, vitaminC.foodLowerBound!!, 1e-9)
        assertTrue(vitaminC.foodLowerBound!! < vitaminC.reference.amount)
        assertEquals(NutrientVerdict.CANNOT_SAY, vitaminC.verdict)
    }

    /** Thirteen full days are counted and said to be, and a floor under fourteen says nothing short. */
    @Test
    fun fewerThanFourteenCountedDaysCannotSayBelow() {
        assertEquals(NutrientVerdict.CANNOT_SAY, table(days(13, "orange=100.0")).row(Nutrient.IRON).verdict)
    }

    @Test
    fun markedDosesLiftANutrientOverItsAllowance() {
        val iron = course("IRON=65")
        val row = table(days(14, "orange=100.0") + marks(iron, 30), listOf(iron)).row(Nutrient.IRON)

        assertEquals(65.0, row.supplements!!.meanMarked, 1e-9)
        assertEquals(30, row.supplements.askedDays)
        assertEquals(NutrientVerdict.AT_OR_ABOVE, row.verdict)
        // 65 mg of it and the oranges' iron: past NASEM's 45 mg, which counts everything.
        assertTrue(row.upperLevels.single().above)
    }

    /**
     * Five days of a tablet in a thirty-day window: the line says what the doses were on the days
     * asked, and the verdict spreads them over the month — which then neither reaches the allowance
     * nor, with the tablet's days above it, reads short of it.
     */
    @Test
    fun aShortCourseIsSpreadOverTheWindowForTheVerdict() {
        val iron = course("IRON=10").copy(startDay = today.minusDays(4).toString())
        val alone = table(marks(iron, 5), listOf(iron), food = false).row(Nutrient.IRON)

        assertEquals(10.0, alone.supplements!!.meanMarked, 1e-9)
        assertEquals(5, alone.supplements.askedDays)
        assertEquals(10.0 * 5 / 30, alone.floorPerDay, 1e-9)
        assertEquals(NutrientVerdict.CANNOT_SAY, alone.verdict)

        val withFood = table(days(30, "orange=100.0") + marks(iron, 5), listOf(iron)).row(Nutrient.IRON)
        assertTrue(withFood.floorPerDay < withFood.reference!!.amount)
        assertEquals(NutrientVerdict.CANNOT_SAY, withFood.verdict)
    }

    /**
     * An adequate intake is what healthy people were seen to eat; under it says nothing about
     * adequacy (NASEM 2000). The oranges carry a known nought of vitamin K at every meal, and the
     * row still does not read short — while iron, an allowance, does from the same meals.
     */
    @Test
    fun anAdequateIntakeIsNeverReadAsShort() {
        val table = table(days(14, "orange=100.0"))

        assertEquals(ReferenceKind.AI, table.row(Nutrient.VITAMIN_K).reference!!.kind)
        assertEquals(NutrientVerdict.CANNOT_SAY, table.row(Nutrient.VITAMIN_K).verdict)
        assertEquals(NutrientVerdict.CANNOT_SAY, table.row(Nutrient.POTASSIUM).verdict)
        assertEquals(NutrientVerdict.BELOW_BY_ENTRIES, table.row(Nutrient.IRON).verdict)
        // Niacin's figure counts the tryptophan in protein, which no column holds.
        assertEquals(NutrientVerdict.CANNOT_SAY, table.row(Nutrient.NIACIN).verdict)
    }

    /** Salmon twice a day is past the daily figure, and food alone is still not read against it. */
    @Test
    fun vitaminDFromFoodAloneHasNoVerdict() {
        val salmon = table(days(14, "salmon=100.0")).row(Nutrient.VITAMIN_D)
        val oranges = table(days(14, "orange=100.0")).row(Nutrient.VITAMIN_D)

        assertTrue(salmon.foodFloor!! > salmon.reference!!.amount)
        assertNull(salmon.verdict)
        assertNull(oranges.verdict)

        val d3 = course("VITAMIN_D=25")
        val taken = table(days(14, "orange=100.0") + marks(d3, 30), listOf(d3)).row(Nutrient.VITAMIN_D)
        assertEquals(NutrientVerdict.AT_OR_ABOVE, taken.verdict)
        val skipped = table(days(14, "orange=100.0") + marks(d3, 3), listOf(d3)).row(Nutrient.VITAMIN_D)
        // A course, but under the figure: the sun makes it too, so never "below".
        assertEquals(NutrientVerdict.CANNOT_SAY, skipped.verdict)
        // A course never ticked leaves the salmon speaking alone, and it still may not.
        assertNull(table(days(14, "salmon=100.0"), listOf(d3)).row(Nutrient.VITAMIN_D).verdict)
    }

    /** The food's folate equivalents, and the tablet's folic acid at 1/0.6 of a microgram each. */
    @Test
    fun folateIsTheFoodsEquivalentsAndTheFolicAcidReadIntoThem() {
        val folic = course("FOLIC_ACID=400")
        val table = table(days(14, "orange=100.0") + marks(folic, 30), listOf(folic))
        val folate = table.row(Nutrient.FOLATE)
        val food = 2 * per100("orange", FoodMicro.FOLATE)

        assertEquals(400 * FOLIC_ACID_DFE, folate.supplements!!.meanMarked, 1e-9)
        assertEquals(1.667, FOLIC_ACID_DFE, 0.001)
        assertEquals(food + 400 * FOLIC_ACID_DFE, folate.floorPerDay, 1e-9)
        assertEquals(NutrientVerdict.AT_OR_ABOVE, folate.verdict)
        // The limit is on folic acid itself, in micrograms of it rather than of folate equivalents.
        val limit = folate.upperLevels.single()
        assertEquals(1000.0, limit.level.amount, 0.0)
        assertEquals(400.0, limit.counted, 1e-9)
        assertFalse(table.rows.any { it.nutrient == Nutrient.FOLIC_ACID })
    }

    /** Marked on some days and not others: the days add, one day of the two is still one day. */
    @Test
    fun folateAndFolicAcidOnTheSameDayAreOneDay() {
        val folate = course("FOLATE=200", id = 1)
        val folic = course("FOLIC_ACID=300", id = 2)
        val row = table(marks(folate, 30) + marks(folic, 30), listOf(folate, folic)).row(Nutrient.FOLATE)

        assertEquals(30, row.supplements!!.askedDays)
        assertEquals(200 + 300 * FOLIC_ACID_DFE, row.supplements.meanMarked, 1e-9)
    }

    /**
     * NASEM's 350 mg counts magnesium from supplements alone and EFSA's 250 mg the added forms: the
     * oats in the bowl count towards neither.
     */
    @Test
    fun magnesiumsUpperLevelsCountTheSupplementAndNotTheFood() {
        val magnesium = course("MAGNESIUM=400")
        val over = table(days(14, "orange=100.0") + marks(magnesium, 30), listOf(magnesium)).row(Nutrient.MAGNESIUM)

        assertEquals(listOf(ReferenceBody.NASEM, ReferenceBody.EFSA), over.upperLevels.map { it.level.body })
        over.upperLevels.forEach {
            assertEquals(400.0, it.counted, 1e-9)
            assertTrue(it.above)
        }

        val lower = course("MAGNESIUM=300")
        val oats = table(days(14, "oats_dry=200.0") + marks(lower, 30), listOf(lower)).row(Nutrient.MAGNESIUM)
        assertTrue("the oats alone hold ${oats.foodFloor}", oats.foodFloor!! > 350.0)
        assertEquals(listOf(false, true), oats.upperLevels.map(UpperLevelCheck::above))
    }

    /** Carrots are a great deal of vitamin A and none of it retinol, which is what the limit counts. */
    @Test
    fun theVitaminAUpperLevelCountsRetinolNotRetinolActivity() {
        val carrots = table(days(14, "carrot=200.0")).row(Nutrient.VITAMIN_A)
        val liver = table(days(14, "beef_liver=100.0")).row(Nutrient.VITAMIN_A)

        assertTrue("carrots hold ${carrots.foodFloor} µg RAE", carrots.foodFloor!! > 3000.0)
        assertEquals(UpperLevelScope.PREFORMED, carrots.upperLevels.single().level.scope)
        assertEquals(0.0, carrots.upperLevels.single().counted, 0.0)
        assertFalse(carrots.upperLevels.single().above)
        assertEquals(2 * per100("beef_liver", FoodMicro.RETINOL), liver.upperLevels.single().counted, 1e-9)
        assertTrue(liver.upperLevels.single().above)
    }

    /** A dose asked for and not marked is nought marked — and may have been swallowed, so no "below". */
    @Test
    fun anUnmarkedCourseIsNothingMarkedAndStopsAReadingOfShort() {
        val iron = course("IRON=65")
        val row = table(days(14, "orange=100.0"), listOf(iron)).row(Nutrient.IRON)

        assertEquals(0.0, row.supplements!!.meanMarked, 0.0)
        assertEquals(30, row.supplements.askedDays)
        assertEquals(NutrientVerdict.CANNOT_SAY, row.verdict)
    }

    /** The medicines left off a sheet cannot be ruled out, so a table without them never says short. */
    @Test
    fun withoutTheCoursesNothingReadsShort() {
        val iron = table(days(14, "orange=100.0"), supplements = false).row(Nutrient.IRON)

        assertNull(iron.supplements)
        assertEquals(NutrientVerdict.CANNOT_SAY, iron.verdict)
    }

    @Test
    fun withoutTheFoodTheTableIsTheCourses() {
        val iron = course("IRON=65")
        val table = table(days(14, "orange=100.0") + marks(iron, 30), listOf(iron), food = false)

        assertFalse(table.foodIncluded)
        assertEquals(listOf(Nutrient.IRON), table.rows.map(NutrientRow::nutrient))
        assertNull(table.row(Nutrient.IRON).food)
        assertEquals(NutrientVerdict.AT_OR_ABOVE, table.row(Nutrient.IRON).verdict)
        assertEquals(0, table.loggedDays)
    }

    /** Biotin, iodine, omega-3 and creatine have no food column, so they are rows only with a course. */
    @Test
    fun theExtrasAppearOnlyWhereACourseHoldsThem() {
        assertTrue(table(days(14, "orange=100.0")).rows.none { it.nutrient in NUTRIENT_TABLE_EXTRAS })

        val multi = course("BIOTIN=5000;IODINE=150;OMEGA3=300;CREATINE=5")
        val table = table(days(14, "orange=100.0") + marks(multi, 30), listOf(multi))

        assertEquals(NUTRIENT_TABLE_ORDER + NUTRIENT_TABLE_EXTRAS, table.rows.map(NutrientRow::nutrient))
        NUTRIENT_TABLE_EXTRAS.forEach { assertNull(table.row(it).food) }
        // Iodine from food is never counted: USDA reports none, and the course is all there is.
        assertEquals(NutrientVerdict.AT_OR_ABOVE, table.row(Nutrient.IODINE).verdict)
        assertEquals(NutrientVerdict.AT_OR_ABOVE, table.row(Nutrient.BIOTIN).verdict)
        assertNull(table.row(Nutrient.OMEGA3).reference)
        assertNull(table.row(Nutrient.CREATINE).verdict)
    }

    /** Under nineteen the tables change band by band, and without a sex the split rows have no figure. */
    @Test
    fun noDailyFigureForAChildOrForASexTheTablesDoNotName() {
        val child = table(days(14, "orange=100.0"), profile = man.copy(birthDate = "2012-01-01"))

        assertTrue(child.rows.all { it.reference == null && it.verdict == null && it.upperLevels.isEmpty() })

        val unstated = table(days(14, "orange=100.0"), profile = man.copy(sex = ""))
        assertNull(unstated.row(Nutrient.IRON).reference)
        assertNull(unstated.row(Nutrient.IRON).verdict)
        assertNotNull(unstated.row(Nutrient.FOLATE).reference)
    }

    @Test
    fun nothingKnownIsAnEmptyTable() {
        assertTrue(table(emptyList()).empty)
        assertTrue(table(typedMeals(20) + typedMeals(20)).empty)
        assertTrue(table(days(14, "orange=100.0"), food = false, supplements = false).empty)
        assertFalse(table(days(14, "orange=100.0")).empty)
    }

    @Test
    fun figuresAreWholeFromTenAndTwoSignificantUnder() {
        assertEquals("2450", nutrientFigure(2450.4))
        assertEquals("64", nutrientFigure(64.3))
        assertEquals("10", nutrientFigure(9.96))
        assertEquals("1.2", nutrientFigure(1.24))
        assertEquals("0.046", nutrientFigure(0.0456))
        assertEquals("0", nutrientFigure(0.0))
    }
}
