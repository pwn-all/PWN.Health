package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Vitamins and minerals from logged food: the table USDA's figures come from, the pointer a meal
 * keeps into the catalogue, and the summary the vitamin table reads.
 *
 * The rule under all of it is that unknown stays unknown. A cell USDA left empty, a meal with one
 * part nobody can vouch for and a day the known meals do not cover are each absent — never a zero
 * that would read as a deficiency somebody does not have.
 */
class FoodMicrosTest {
    private fun rows(resource: String): List<List<String>> =
        catalogResource(resource).lineSequence().filter(String::isNotBlank).map { it.split(';') }.toList()

    /** The file is written by a script from an older catalogue; a row renamed since must fail here. */
    @Test
    fun everyMicrosRowIsACatalogueRow() {
        assertTrue(TOP_FOOD_MICROS.size > 3_000)
        assertEquals(emptySet<String>(), TOP_FOOD_MICROS.keys - TOP_FOODS_BY_ID.keys)
    }

    /** Every column the app knows is in the file, so a script that dropped one cannot pass as complete. */
    @Test
    fun theFileCarriesEveryColumn() {
        val header = rows(MICROS_RESOURCE).first()

        assertEquals(MICROS_LEADING_COLUMNS + FoodMicro.entries.map(FoodMicro::name), header)
    }

    /**
     * `exact` promises the FDC food's four numbers are the row's, as build-top-foods.py's gram()
     * prints them. A hand edit of the catalogue afterwards breaks that, and the vitamins would then
     * belong to a different food than the one on the plate. The script writes those numbers to a
     * test resource, since the app has no use for them.
     */
    @Test
    fun exactRowsStillHaveTheirFoodsNumbers() {
        // gram() prints with Python's "%.1f": the exact binary value, rounded half to even.
        fun gram(value: Double) = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN)
        val fdcRows = rows("/top_foods_micros_fdc.csv")
        assertEquals(listOf("id", "fdc_kcal", "fdc_protein", "fdc_fat", "fdc_carbs"), fdcRows.first())
        val fdcNumbers = fdcRows.drop(1).associate { it[0] to it.drop(1).map(::BigDecimal) }
        val exact = TOP_FOOD_MICROS.filterValues { it.match == FoodMicroMatch.EXACT }.keys

        assertTrue(exact.size > 3_000)
        exact.forEach { id ->
            val food = TOP_FOODS_BY_ID.getValue(id).nutrition
            val row = listOf(food.kcal, food.proteinG, food.fatG, food.carbsG).map(::gram)
            val fdc = fdcNumbers[id]
            assertNotNull("$id has no FDC numbers", fdc)
            assertTrue("$id: FDC $fdc, catalogue $row", row.zip(fdc!!).all { (a, b) -> a.compareTo(b) == 0 })
        }
    }

    /**
     * USDA's folate is already dietary folate equivalents with the added folic acid in it, so the
     * folic acid column must not reach a per-nutrient sum that reads folic acid into folate again.
     */
    @Test
    fun folicAcidIsInsideFolateAndAddsToNoNutrient() {
        assertNull(FoodMicro.FOLIC_ACID.nutrient)
        val fortified = TOP_FOOD_MICROS.values.filter { (it[FoodMicro.FOLIC_ACID] ?: 0.0) > 0.0 }

        assertTrue(fortified.size > 100)
        fortified.forEach { micros ->
            val folate = micros[FoodMicro.FOLATE]
            val folicAcid = micros[FoodMicro.FOLIC_ACID]!!
            // Both are rounded figures, and 1.7 times a rounded one can overshoot by over a microgram.
            assertTrue(micros.fdcId, folate != null && folate + 1.5 >= FOLIC_ACID_DFE * folicAcid * 0.99)
        }
    }

    /** The hand-checked mapping is the one thing a person wrote here; each line must have landed. */
    @Test
    fun everyManualLineIsInTheFile() {
        val manual = rows("/top_foods_micros_manual.csv").drop(1)

        assertTrue(manual.size >= 50)
        manual.forEach { cell ->
            assertEquals(cell[0], cell[1], TOP_FOOD_MICROS.getValue(cell[0]).fdcId)
        }
        assertEquals(FoodMicroMatch.PROXY, TOP_FOOD_MICROS.getValue("salmon").match)
        // Picked by hand, and still exact: the unenriched rice has the enriched one's four numbers.
        assertEquals(FoodMicroMatch.EXACT, TOP_FOOD_MICROS.getValue("white_rice").match)
    }

    @Test
    fun aValueUsdaDoesNotReportIsUnknownRatherThanZero() {
        // SR Legacy files McDonald's hamburger with no vitamin A at all.
        val hamburger = TOP_FOOD_MICROS.getValue("hamburger")

        assertNull(hamburger[FoodMicro.VITAMIN_A])
        assertNotNull(hamburger[FoodMicro.CALCIUM])
    }

    /** A product sold at several fat contents cannot vouch for what travels with the fat. */
    @Test
    fun aFatRailLeavesTheFatSolubleVitaminsUnknown() {
        val railed = TOP_FOODS.filter { it.fatSteps.isNotEmpty() }.mapNotNull { TOP_FOOD_MICROS[it.id] }

        assertTrue(railed.isNotEmpty())
        railed.forEach { micros ->
            FoodMicro.entries.filter(FoodMicro::fatSoluble).forEach { assertNull(micros[it]) }
        }
    }

    @Test
    fun aSinglePickTakesTheSavedPortionWhole() {
        assertEquals("big_mac=150.0", savedFoodSource("big_mac=205.0", 150.0))
        assertEquals("cottage_cheese_5=250.0=9.0", savedFoodSource("cottage_cheese_5=180.0=9.0", 250.0))
    }

    @Test
    fun aDishIsScaledTogetherWhenItsPortionIsEdited() {
        assertEquals("orange=100.0|apple=50.0", savedFoodSource("orange=100.0|apple=50.0", 150.0))
        assertEquals("orange=200.0|apple=100.0", savedFoodSource("orange=100.0|apple=50.0", 300.0))
        // A product taken off the plate is not part of what was eaten.
        assertEquals("orange=100.0", savedFoodSource("orange=100.0|apple=0.0", 100.0))
    }

    @Test
    fun noPointerWithoutAPortionOrARowToPointAt() {
        assertNull(savedFoodSource(null, 100.0))
        assertNull(savedFoodSource("big_mac=205.0", 0.0))
        // One part the catalogue no longer carries loses the whole pointer, not just that part.
        assertNull(savedFoodSource("orange=100.0|no_such_row=50.0", 150.0))
        assertNull(foodSourceItems("orange=100.0|no_such_row=50.0"))
    }

    private fun meal(
        day: String = "2026-09-01",
        title: String = "Typed at home",
        subtitle: String = "",
        kcal: Double = 500.0,
        source: String? = null,
        servingG: Double = 0.0,
        kind: String = "EAT",
    ) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = title,
        subtitle = subtitle,
        calories = kcal,
        servingG = servingG,
        foodSource = source,
    )

    /** An older meal, saved exactly as the picker filled the form for [food] at [grams]. */
    private fun pickedBefore(
        food: TopFood,
        grams: Double,
        title: String,
        subtitle: String,
        fat: Double? = null,
    ): JournalEntry {
        val per100 = fat?.let(food.nutrition::atFatPercent) ?: food.nutrition

        // The form types per-100 g figures to one decimal and saves the portion's.
        fun portion(per100g: Double) = per100g.asGramText().toDouble() * grams / 100.0
        return meal(title = title, subtitle = subtitle, kcal = portion(per100.kcal), servingG = grams).copy(
            proteinG = portion(per100.proteinG),
            fatG = portion(per100.fatG),
            carbsG = portion(per100.carbsG),
        )
    }

    @Test
    fun anOlderMealIsRecognisedByItsNameAndFigures() {
        val bigMac = TOP_FOODS_BY_ID.getValue("big_mac")

        assertEquals("big_mac=205.0", pickedBefore(bigMac, 205.0, "Big Mac", "McDonald's").inferredFoodSource())
        assertEquals("big_mac=205.0", pickedBefore(bigMac, 205.0, "Биг Мак", "McDonald's").inferredFoodSource())
        // A meal pointing somewhere already keeps its own pointer.
        val pointed = pickedBefore(bigMac, 205.0, "Big Mac", "").copy(foodSource = "hamburger=100.0")
        assertNull(pointed.inferredFoodSource())
        assertEquals("hamburger=100.0", pointed.foodSourceOrInferred())
    }

    @Test
    fun anOlderMealWhoseFiguresWereEditedIsNotTheRow() {
        val bigMac = TOP_FOODS_BY_ID.getValue("big_mac")
        val picked = pickedBefore(bigMac, 205.0, "Big Mac", "McDonald's")

        assertNull(picked.copy(calories = picked.calories * 1.1).inferredFoodSource())
        assertNull(picked.copy(proteinG = picked.proteinG + 1.0).inferredFoodSource())
        assertNull(picked.copy(title = "My big mac").inferredFoodSource())
        assertNull(picked.copy(kind = KIND_ALCOHOL).foodSourceOrInferred())
    }

    /** "Овсяные хлопья" names two rows with the same four numbers; the qualifier says which. */
    @Test
    fun theQualifierChoosesBetweenRowsThatShareAName() {
        val oats = TOP_FOODS_BY_ID.getValue("oats_dry")

        assertEquals("oats_dry=40.0", pickedBefore(oats, 40.0, "Овсяные хлопья", "сухие").inferredFoodSource())
        assertEquals(
            "cereals_oats_regular_and_quick=40.0",
            pickedBefore(oats, 40.0, "Овсяные хлопья", "").inferredFoodSource(),
        )
    }

    @Test
    fun anOlderMealAtAnotherFatContentIsReadBackWithIt() {
        val curd = TOP_FOODS_BY_ID.getValue("cottage_cheese_5")

        assertEquals("cottage_cheese_5=180.0", pickedBefore(curd, 180.0, "Творог", "без добавок").inferredFoodSource())
        assertEquals(
            "cottage_cheese_5=180.0=9.0",
            pickedBefore(curd, 180.0, "Творог", "без добавок", fat = 9.0).inferredFoodSource(),
        )
    }

    @Test
    fun aKeptCatalogueFoodPointsAtItsRow() {
        val curd = TOP_FOODS_BY_ID.getValue("cottage_cheese_5")
        val fat = curd.nutrition.atFatPercent(18.0)
        val kept = FavoriteFood(
            key = "cottage_cheese_5",
            title = "Творог",
            servingG = 100.0,
            calories = fat.kcal,
            proteinG = fat.proteinG,
            fatG = fat.fatG,
            carbsG = fat.carbsG,
            createdAt = 0L,
        )

        assertEquals("cottage_cheese_5=100.0=18.0", kept.foodSource())
        assertNull(kept.copy(key = "творог").foodSource())
    }

    /**
     * A food kept under its folded name can land on a catalogue id by accident. Only the row's own
     * figures make it that row: a dish named after its first product is not that product, and a
     * label named "Kefir" is not the catalogue's kefir.
     */
    @Test
    fun aKeptFoodWhoseNameIsAnIdIsNotThatRowByName() {
        fun kept(key: String, servingG: Double, items: List<DishItem>) = FavoriteFood(
            key = key,
            title = key.replaceFirstChar(Char::uppercase),
            servingG = servingG,
            calories = items.sumOf { it.food.nutrition.kcal * it.grams / 100.0 },
            proteinG = items.sumOf { it.food.nutrition.proteinG * it.grams / 100.0 },
            fatG = items.sumOf { it.food.nutrition.fatG * it.grams / 100.0 },
            carbsG = items.sumOf { it.food.nutrition.carbsG * it.grams / 100.0 },
            createdAt = 0L,
        )
        val orange = TOP_FOODS_BY_ID.getValue("orange")
        val apple = TOP_FOODS_BY_ID.getValue("apple")

        assertEquals("orange=150.0", kept("orange", 150.0, listOf(DishItem(orange, 150.0))).foodSource())
        assertNull(kept("orange", 150.0, listOf(DishItem(orange, 100.0), DishItem(apple, 50.0))).foodSource())
        // A label read and named "Kefir": 50 kcal, 2.9 g protein, 2.5 g fat, 4 g carbs per 100 g, kept at 250 g.
        // Neither the row's figures nor any step of its fat rail.
        val label = FavoriteFood(
            key = "kefir",
            title = "Kefir",
            servingG = 250.0,
            calories = 125.0,
            proteinG = 7.25,
            fatG = 6.25,
            carbsG = 10.0,
            createdAt = 0L,
        )
        assertNull(label.foodSource())
    }

    @Test
    fun aMealAddsUpItsPartsAndKnowsOnlyWhatEveryPartReports() {
        val orange = TOP_FOOD_MICROS.getValue("orange")
        val hamburger = TOP_FOOD_MICROS.getValue("hamburger")

        val micros = meal(source = "orange=100.0|hamburger=100.0").foodMicros()

        assertEquals(
            orange[FoodMicro.VITAMIN_C]!! + hamburger[FoodMicro.VITAMIN_C]!!,
            micros[FoodMicro.VITAMIN_C]!!,
            1e-9,
        )
        assertFalse("the hamburger reports no vitamin A", FoodMicro.VITAMIN_A in micros)
        assertTrue(meal(source = "orange=100.0|no_such_row=50.0").foodMicros().isEmpty())
        assertTrue(meal(title = "Typed at home").foodMicros().isEmpty())
    }

    /** Skimmed curd holds more of everything the fat displaced, and cannot vouch for vitamin A. */
    @Test
    fun aPartAtAnotherFatContentScalesLikeProtein() {
        val curd = TOP_FOODS_BY_ID.getValue("cottage_cheese_5")
        val calcium = TOP_FOOD_MICROS.getValue("cottage_cheese_5")[FoodMicro.CALCIUM]!!

        val micros = meal(source = "cottage_cheese_5=200.0=0.0").foodMicros()

        assertEquals(calcium * 2.0 * 100.0 / (100.0 - curd.nutrition.fatG), micros[FoodMicro.CALCIUM]!!, 1e-9)
        assertTrue(FoodMicro.entries.filter(FoodMicro::fatSoluble).none { it in micros })
    }

    private val orangeC = TOP_FOOD_MICROS.getValue("orange")[FoodMicro.VITAMIN_C]!!

    private fun orangeDays(
        count: Int,
        first: LocalDate = LocalDate.of(
            2026,
            8,
            1,
        ),
    ) = (0 until count).flatMap { offset ->
        val day = first.plusDays(offset.toLong()).toString()
        List(2) { meal(day = day, title = "Orange", kcal = 47.0, source = "orange=100.0") }
    }

    private val range = LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 9, 30)

    private fun summary(entries: List<JournalEntry>) =
        foodMicroSummaries(entries, range.start, range.endInclusive).getValue(FoodMicro.VITAMIN_C)

    @Test
    fun aMeanIsOfferedFromFourteenCountedDays() {
        val summary = summary(orangeDays(14))

        assertEquals(2 * orangeC, summary.meanPerDay!!, 1e-9)
        assertEquals(14, summary.countedDays)
        assertEquals(14, summary.loggedDays)
        assertEquals(1.0, summary.coverage!!, 1e-9)
        assertEquals(28, summary.knownMeals)
        assertEquals(28, summary.meals)
    }

    /** Thirteen days are counted and said to be, and the floor is still a floor. */
    @Test
    fun belowFourteenDaysOnlyTheCountsAndTheFloorAreGiven() {
        val summary = summary(orangeDays(13))

        assertNull(summary.meanPerDay)
        assertEquals(13, summary.countedDays)
        assertEquals(2 * orangeC, summary.lowerBoundPerDay!!, 1e-9)
    }

    /** A day where the unknown meal carries most of the energy is not a day of eating only oranges. */
    @Test
    fun aDayTheKnownMealsDoNotCoverIsNotCounted() {
        val thin = listOf(
            meal(day = "2026-09-20", title = "Orange", kcal = 47.0, source = "orange=100.0"),
            meal(day = "2026-09-20", kcal = 600.0),
        )

        val summary = summary(orangeDays(14) + thin)

        assertEquals(14, summary.countedDays)
        assertEquals(15, summary.loggedDays)
        assertEquals(2 * orangeC, summary.meanPerDay!!, 1e-9)
        // The floor takes what the thin day did report, over every logged day.
        assertEquals((28 * orangeC + orangeC) / 15, summary.lowerBoundPerDay!!, 1e-9)
        assertTrue(summary.coverage!! < 1.0)
        assertEquals(29, summary.knownMeals)
        assertEquals(30, summary.meals)
    }

    /** One meal on a day is a day somebody stopped logging; a drink from the alcohol tracker is not a meal. */
    @Test
    fun aHalfLoggedDayIsNotALoggedDay() {
        val half = listOf(
            meal(day = "2026-09-21", title = "Orange", kcal = 47.0, source = "orange=100.0"),
            meal(day = "2026-09-21", title = "Beer", kcal = 200.0, kind = KIND_ALCOHOL),
        )

        assertEquals(14, summary(orangeDays(14) + half).loggedDays)
        assertEquals(0, summary(half).loggedDays)
        assertNull(summary(half).coverage)
    }

    /**
     * Another app's vitamin tablet, stored as a meal of nothing, does not make one real meal a logged
     * day. A zero-energy meal the person logged here still does.
     */
    @Test
    fun aTabletAnEarlierSyncFiledAsAMealIsNotAMeal() {
        val orange = meal(day = "2026-09-22", title = "Orange", kcal = 47.0, source = "orange=100.0")
        val tablet = meal(day = "2026-09-22", title = "Vitamin D", kcal = 0.0)
            .copy(healthConnectRecordKey = "Health Connect:com.example.vitamins:record-1")
        val dietCola = meal(day = "2026-09-22", title = "Diet cola", kcal = 0.0)

        assertEquals(0, summary(listOf(orange, tablet)).loggedDays)
        assertEquals(1, summary(listOf(orange, dietCola)).loggedDays)
        // One that stated its caffeine said something, and stays a meal.
        assertEquals(1, summary(listOf(orange, tablet.copy(caffeineMg = 40.0))).loggedDays)
    }

    @Test
    fun nothingKnownIsNoFloorRatherThanAFloorOfNothing() {
        val typed = (1..20).flatMap { day ->
            List(2) { meal(day = LocalDate.of(2026, 8, day).toString()) }
        }

        val summary = summary(typed)

        assertNull(summary.lowerBoundPerDay)
        assertNull(summary.meanPerDay)
        assertEquals(0, summary.countedDays)
        assertEquals(20, summary.loggedDays)
        assertEquals(0.0, summary.coverage!!, 0.0)
    }

    @Test
    fun daysOutsideTheRangeAreLeftOut() {
        assertEquals(0, summary(orangeDays(3, first = LocalDate.of(2026, 7, 1))).loggedDays)
    }

    /**
     * The same two rules as the lab window's. That file's constants are private to it, so this reads
     * them the only way it can.
     */
    @Test
    fun theDayRulesAreTheLabWindowsRules() {
        fun labConstant(name: String): Int = Class.forName("com.pwnall.health.LabFoodWindowKt")
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .getInt(null)

        assertEquals(labConstant("LAB_DIET_MIN_DAYS"), FOOD_MICROS_MIN_DAYS)
        assertEquals(labConstant("LAB_DIET_MIN_MEALS_PER_DAY"), FOOD_MICROS_MIN_MEALS_PER_DAY)
    }

    /** Every product of a built dish reports saturated fat here, so the dish does too. */
    @Test
    fun aBuiltDishKeepsItsSaturatedFat() {
        val items = listOf(
            DishItem(TOP_FOODS_BY_ID.getValue("big_mac"), 100.0),
            DishItem(TOP_FOODS_BY_ID.getValue("hamburger"), 100.0),
        )
        val expected = items.sumOf { it.food.nutrition.saturatedFatG!! * it.grams / 100.0 }

        val state = FoodState().withPhotoDish("Обед", "Собрано из продуктов", items)

        assertEquals(expected.asGramText(), state.extra[ExtraNutrient.SATURATED_FAT])
    }

    /** A vitamin tablet another app filed as nutrition is not a meal of nought kilocalories. */
    @Test
    fun aNutritionRecordStatingNothingAMealHoldsIsNotAMeal() {
        assertFalse(statesMealNutrition(null, null, null, null, null))
        // A diet cola states its energy, and the energy is nothing.
        assertTrue(statesMealNutrition(null, 0.0, null, null, null))
        assertTrue(statesMealNutrition(0.08, null, null, null, null))
    }
}
