package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edges of the label reader, where a figure that is off by a factor of ten is still a figure
 * the form will happily accept.
 *
 * [NutritionLabelTest] pins the tables that read correctly. This pins the ones easy to read wrongly:
 * the exact value on each side of every ceiling, floor and tolerance the parser applies, the words
 * that hide a nutrient's name inside another word, and the text a recognizer hands back when the
 * photograph was of a plate, a menu or a thumb over the lens. Everything here ends up in the
 * journal as somebody's dinner, and a wrong number there is indistinguishable from a right one.
 */
class NutritionParsingEdgeCasesTest {
    private fun servingOf(text: String): Double? = nutritionLabel(text).perServingG

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int, text: String) = TextBox(Box(x0, y0, x1, y1), text)

    /**
     * A two-column bar wrapper prints "Serving size 1 bar" and "Total Fat 15g" on one printed line,
     * and both assemblies the reader feeds the parser — box by box and reassembled into lines — hand
     * it over that way. The bar's own weight was never printed, so this label declares no portion at
     * all; 15 g of fat offered as the serving would file the whole bar as fifteen grams and drag
     * every other figure on the form down with it.
     */
    @Test
    fun aNutrientBesideTheServingMarkerIsNotTheServing() {
        val oneLine = nutritionLabel("Serving size 1 bar Total Fat 15g\nCalories 190")

        assertNull("the fat row is not the bar's weight, was ${oneLine.perServingG}", oneLine.perServingG)
        assertEquals(15.0, requireNotNull(oneLine.fatG), 1e-9)
        assertEquals(MealBasis.PER_100G, FoodState().withLabel("С этикетки", oneLine).basis)
        // The same panel with the nutrient on its own printed line is refused too.
        assertNull(nutritionLabel("Serving size 1 bar\nTotal Fat 15g\nCalories 190").perServingG)
    }

    /**
     * "Творог обезжиренный" and "Fat free milk" print a net weight straight after a word carrying
     * the fat root, and the first line to name a macro answers for it for good — so read as the fat
     * row, the packet's 100 g would become its fat content, and 900 kcal of arithmetic would then sit
     * in judgement over the energy line. The fat root is guarded against this as the salt row is.
     */
    @Test
    fun aFatFreeNameNeverAnswersForTheFatRow() {
        val curd = nutritionLabel("Творог обезжиренный 100 г\nБелки 18 г\nЖиры 0,1 г")

        assertEquals(0.1, requireNotNull(curd.fatG), 1e-9)

        val milk = nutritionLabel("Fat free milk 100 g\nProtein 3,4 g\nFat 1 g")

        assertEquals(1.0, requireNotNull(milk.fatG), 1e-9)
    }

    /**
     * A tin photographed at arm's length comes back with "110" at twice the height of everything
     * around it and no "Calories" anywhere: the reader takes the panel's largest type for the
     * energy, which is a guess about typography rather than a figure it read. [NutritionLabel] says
     * a figure that "came from the panel's largest type because the word beside it never read" is
     * approximate and gets a "≈" on the form, and the flag has to survive the fallback's re-parse of
     * a synthesised line: 18 g of protein and 5 g of fat weigh 117 kcal, near enough to 110 that,
     * with the provenance lost, the guess would be presented as a reading.
     */
    @Test
    fun energyTakenFromTheHeadlineTypeIsMarkedApproximate() {
        val panel = listOf(
            box(0, 0, 300, 20, "Nutrition Facts"),
            box(0, 30, 300, 50, "Amount per serving"),
            box(0, 60, 120, 120, "110"),
            box(0, 130, 300, 150, "Total Fat 5 g"),
            box(0, 160, 300, 180, "Protein 18 g"),
            box(0, 190, 300, 210, "Sodium 150 mg"),
            box(0, 220, 300, 240, "Vitamin D 0 mcg"),
            box(0, 250, 300, 270, "Potassium 240 mg"),
        )

        val readings = LabelReader.readingsOf(panel)
        val headline = readings.last()

        assertTrue("only the fallback may read energy here", readings.dropLast(1).all { it.kcal == null })
        assertEquals(110.0, requireNotNull(headline.kcal), 1e-9)
        assertTrue("a figure taken from the type size is a guess", headline.approximate)
    }

    /**
     * A Russian import sticker over a US panel. "Содержание витаминов 100 %" is a share of the daily
     * norm and names no basis at all, but with the spaces stripped it reads "…витаминов100…", and
     * the per-100 g pattern's bare Cyrillic "в" must not fire on it — which would both flip the basis
     * and throw away the serving size the panel underneath printed. The bare Latin "na" is kept out
     * of that same pattern for the same reason.
     */
    @Test
    fun aRussianWordEndingInVeIsNotAPerHundredBasis() {
        val sticker = nutritionLabel("Содержание витаминов 100 % от нормы\nServing size 30 g\nЭнергия 140 ккал")

        assertFalse("a %-of-norm footnote names no basis", sticker.perHundred)
        assertEquals(30.0, requireNotNull(sticker.perServingG), 1e-9)
        assertEquals(140.0, requireNotNull(sticker.kcal), 1e-9)
    }

    /**
     * Pure sugar really is 100 g of carbohydrate per 100 g, and 400 kcal with it. The rule that
     * drops the 100 of "на 100 г" tests the words before the figure with endsWith against a
     * one-letter entry, and the Russian genitive «белков»/«жиров»/«углеводов» ends in that letter — so
     * the macros' genitives are let through, or the figure would be discarded on exactly the products
     * where 100 g of it is the whole 100 g.
     */
    @Test
    fun aHundredGramsOfCarbohydrateSurvivesTheColumnHeaderRule() {
        val sugar = nutritionLabel("Сахар\nУглеводов 100 г\nЭнергия 400 ккал")

        assertEquals(100.0, requireNotNull(sugar.carbsG), 1e-9)
        assertEquals(400.0, requireNotNull(sugar.kcal), 1e-9)
    }

    /**
     * "1500 kJ / 120 kcal" cannot be both: 1500 kJ is 358 kcal, and 12.5 is nowhere near the ratio a
     * printed pair stands at. The macros are the only witness — 5 g of protein, 2 g of fat and 12 g
     * of carbohydrate weigh 86 kcal, which 120 can live with and 358 cannot — and with no macros at
     * all the documented order of trust puts a lone united kilojoule figure, converted, above a
     * kilocalorie figure nothing confirms.
     */
    @Test
    fun theMacrosDecideWhichHalfOfAContradictoryPairIsBelieved() {
        val witnessed = nutritionLabel("Energy 1500 kJ / 120 kcal\nProtein 5 g\nFat 2 g\nCarbohydrate 12 g")

        assertEquals(120.0, requireNotNull(witnessed.kcal), 1e-9)
        assertFalse("120 kcal is what 86 kcal of macros can live with", witnessed.approximate)

        val alone = requireNotNull(nutritionLabel("Energy 1500 kJ / 120 kcal").kcal)

        assertEquals(1500.0 / 4.184, alone, 0.2)
    }

    /**
     * The energy cross-check forgives the larger of 60% of what the macros weigh and a flat 40 kcal,
     * and those two regimes cross over at 100 kcal of macros. 25 g of protein weigh 100 kcal, so 160
     * is the last printed figure that agrees with them and 161 the first that does not; 10 g weigh
     * 40 kcal, where the floor takes over, so 80 agrees and 81 does not. A figure that disagrees is
     * still what the form shows — with the "≈" beside it, which is the whole point of the flag.
     */
    @Test
    fun theAtwaterToleranceHoldsOnBothOfItsRegimes() {
        fun read(proteinG: Int, printedKcal: Int) =
            nutritionLabel("Protein $proteinG g\nFat 0 g\nCalories $printedKcal")

        assertFalse("60 kcal away from 100 is inside the relative slack", read(25, 160).approximate)
        assertTrue("61 kcal away from 100 is outside it", read(25, 161).approximate)
        assertEquals(161.0, requireNotNull(read(25, 161).kcal), 1e-9)

        assertFalse("40 kcal away from 40 is inside the floor", read(10, 80).approximate)
        assertTrue("41 kcal away from 40 is outside it", read(10, 81).approximate)
        assertEquals(81.0, requireNotNull(read(10, 81).kcal), 1e-9)
    }

    /**
     * Pure fat is 900 kcal per 100 g, and the reader's ceiling stands at 999: nothing edible prints
     * higher, so 999 is a reading and 1000 is two figures welded together or a servings-per-container
     * column. The ceiling is applied in four places inside the energy rules, and a food that really
     * does print 999 must not be lost by any of them.
     */
    @Test
    fun theKilocalorieCeilingIsInclusiveAndNothingAboveItIsRead() {
        assertEquals(999.0, requireNotNull(nutritionLabel("калорийность 999 ккал").kcal), 1e-9)

        val tooHigh = nutritionLabel("калорийность 1000 ккал")

        assertNull("nothing edible prints 1000 kcal per 100 g, was ${tooHigh.kcal}", tooHigh.kcal)
        assertFalse(tooHigh.found)
    }

    /**
     * A bare figure on the energy line has two boundaries and they face opposite ways. Ten is the
     * least it may be: below that it is OCR debris rather than a diet drink, and a phantom 4 kcal
     * packet is worse than no reading. Above the kilocalorie ceiling it stops being kilocalories at
     * all — a lone 1000 is a kilojoule figure whose "239 ккал" half never read, so it converts
     * rather than being thrown away, and the reading falls fourfold across that one step.
     */
    @Test
    fun aBareEnergyFigureHasAFloorAndTurnsIntoKilojoulesAboveTheCeiling() {
        assertEquals(10.0, requireNotNull(nutritionLabel("калорийность: 10").kcal), 1e-9)
        assertNull(nutritionLabel("калорийность: 9").kcal)

        assertEquals(999.0, requireNotNull(nutritionLabel("Энергетическая ценность 999").kcal), 1e-9)
        assertEquals(1000.0 / 4.184, requireNotNull(nutritionLabel("Энергетическая ценность 1000").kcal), 0.1)
    }

    /**
     * A serving is what somebody is served: never a kilogram, which is a misread net weight, and
     * never nothing at all. Where the Japanese marker prints no unit the figure has to be big enough
     * to be a weight, because "2個当たり" is a count of pieces and reading it as grams files a
     * chocolate bar as two grams of chocolate. A zero serving is the worst of them: it puts the form
     * on a portion basis with nothing to scale by.
     */
    @Test
    fun aServingIsNeverZeroAndNeverAKilogram() {
        assertEquals(1000.0, requireNotNull(servingOf("(1000g当たり)")), 1e-9)
        assertNull(servingOf("(1001g当たり)"))
        assertEquals(4.0, requireNotNull(servingOf("(4当り)")), 1e-9)
        assertNull(servingOf("(3当り)"))
        assertNull(servingOf("(0g当たり)"))

        val zero = nutritionLabel("Serving size 0 g\nCalories 90")

        assertNull("a zero serving is not a portion, was ${zero.perServingG}", zero.perServingG)
        assertEquals(90.0, requireNotNull(zero.kcal), 1e-9)
    }

    /**
     * A small US panel abbreviates its saturated row to "Sat Fat", and on this photograph the Total
     * Fat row above it was lost to the fold in the wrapper. Saturated fat is a part of the total and
     * can never answer for it: 4.5 g filed as the whole fat content would understate the shortbread by
     * two thirds, and the first line to name fat holds the field for good.
     */
    @Test
    fun anAbbreviatedSaturatedRowNeverAnswersForTheTotalFat() {
        val panel = nutritionLabel("Nutrition Facts\nSat Fat 4.5g\nProtein 9g\nCalories 190")

        assertNull("a saturated row is not the total fat, was ${panel.fatG}", panel.fatG)
        assertEquals(9.0, requireNotNull(panel.proteinG), 1e-9)
        assertEquals(190.0, requireNotNull(panel.kcal), 1e-9)
    }

    /**
     * "Жиры 0 г" is what every fat-free product prints and "Белки 3 г" what a glass of milk does.
     * The spaces come out of the text before anything else reads it, which welds a single digit
     * between the nutrient's name and its Cyrillic unit — and the rule that rescues "Ма3нини" from
     * OCR must not read that digit as the letter it usually is and delete it. The whole row would go
     * missing: an empty field on the form where a zero belonged, and one macro fewer to judge the
     * energy line by. Only single digits are at risk, so a table printing "5,0 г" cannot show it.
     */
    @Test
    fun aSingleDigitBetweenCyrillicLettersIsStillAFigure() {
        assertEquals(0.0, requireNotNull(nutritionLabel("Жиры 0 г").fatG), 1e-9)
        assertEquals(3.0, requireNotNull(nutritionLabel("Белки 3 г").proteinG), 1e-9)
        assertEquals(0.0, requireNotNull(nutritionLabel("Сахар 0 г").sugarG), 1e-9)
    }

    /**
     * These shelves lose decimal commas wholesale — the cheddar's carbohydrate came back "01" and
     * the crepe's salt "014" — so a leading zero is read as the decimal it was. A lone "0" is not a
     * fraction of anything and stays zero. In the other direction, a three-digit group after the
     * separator is a thousands group only beside a kilojoule: "Соль 1,250 г" is a gram and a quarter
     * of salt, which is half a gram of sodium, not half a kilo of it.
     */
    @Test
    fun theSeparatorsAreReadAsTheLabelPrintedThem() {
        assertEquals(0.1, requireNotNull(nutritionLabel("Углеводы 01 г").carbsG), 1e-9)
        assertEquals(56.0, requireNotNull(nutritionLabel("Соль 014").sodiumMg), 1e-9)
        assertEquals(0.0, requireNotNull(nutritionLabel("Fat 0 g").fatG), 1e-9)

        assertEquals(500.0, requireNotNull(nutritionLabel("Соль 1,250 г").sodiumMg), 1e-9)
        assertEquals(1250.0, requireNotNull(nutritionLabel("Sodium 1.250 g").sodiumMg), 1e-9)
    }

    /**
     * 50 g of protein, 50 g of fat and 50 g of carbohydrate do not fit inside 100 g of anything, and
     * by 4/9/4 they weigh 850 kcal against the 200 the packet printed. Nothing weighs the macros
     * against the product, so the energy cross-check is the only defence a self-contradicting label
     * has — and it has to fire: 650 kcal apart is far outside any tolerance, and the form owes the
     * person the "≈" rather than a confident reading.
     */
    @Test
    fun macrosHeavierThanTheProductLeaveTheEnergyApproximate() {
        val label = nutritionLabel("Белки 50 г\nЖиры 50 г\nУглеводы 50 г\nКалорийность 200 ккал")
        val macros = listOfNotNull(label.proteinG, label.fatG, label.carbsG)

        assertEquals(200.0, requireNotNull(label.kcal), 1e-9)
        assertTrue("50/50/50 weigh 850 kcal, not 200", label.approximate)
        assertTrue("was $macros", macros.all { it <= 100.0 })
    }

    /**
     * A run of five digits is two figures welded together — a table border read as a leading 1, or a
     * percentage glued to its amount — and never a measurement anybody printed. The digit pattern
     * anchors the start of a run but not its end, so it can hand back the first four digits of one:
     * 12345 mg of sodium must not arrive as 1234 mg, a figure that appears nowhere on the packet.
     */
    @Test
    fun anOcrDigitRunTooLongIsNotTruncatedIntoAReading() {
        val sodium = nutritionLabel("натрий 12345 мг").sodiumMg

        assertTrue("read the run whole or refuse it, was $sodium", sodium == null || sodium == 12_345.0)
        // The protein path is saved only by the 100 g ceiling, which the truncation happens to trip.
        assertNull(nutritionLabel("Белки 12345").proteinG)
    }

    /**
     * A photograph of a plate, of a menu, or of a thumb over the lens all reach this parser, and so
     * does whatever the recognizer makes of blur. None of it may throw, and none of it may reach the
     * journal: the energy is either absent or a figure a food could carry, and the favourite a label
     * becomes is worth a finite, non-negative number of calories whatever was read.
     */
    @Test
    fun theParserSurvivesWhateverTheRecognizerHandsIt() {
        val junk = listOf(
            "",
            "\n",
            "9".repeat(20_000),
            "....,,,,",
            "kcal",
            "----",
            "ккал".repeat(5_000),
            "\uD800 калорийность \uDFFF",
            "белки жиры углеводы соль сахар клетчатка натрий",
        )

        junk.forEach { text ->
            val label = nutritionLabel(text)
            val kcal = label.kcal
            assertTrue("$kcal from ${text.take(20)}", kcal == null || (kcal.isFinite() && kcal in 0.0..999.0))
            val calories = label.toFavorite("Обед", "С этикетки", 0L).calories
            assertTrue("$calories from ${text.take(20)}", calories.isFinite() && calories >= 0.0)
            assertFalse("${text.take(20)} is not a label", label.found)
        }
    }

    /**
     * The form may still hold the previous product's cholesterol and saturated fat when the camera
     * opens. A label is the whole of what its packet declares, so what it does not print is
     * not this packet's: a cholesterol figure carried over from the last food would be attributed to
     * a packet that never printed one, and nobody editing the form afterwards could tell.
     */
    @Test
    fun readingALabelClearsTheExtrasOfWhateverWasInTheFormBefore() {
        val stale = FoodState(
            extra = mapOf(ExtraNutrient.CHOLESTEROL to "120", ExtraNutrient.SATURATED_FAT to "3"),
        )

        val read = stale.withLabel("С этикетки", NutritionLabel(kcal = 270.0, fiberG = 7.0))

        assertEquals(setOf(ExtraNutrient.FIBER), read.extra.keys)
        assertEquals("7.0", read.extra[ExtraNutrient.FIBER])
    }

    /**
     * A figure is grams only where its unit lets it be. "Salt 20 mg" read for its number alone would be
     * twenty grams of salt — 8 000 mg of sodium kept with the product and counted into every meal
     * logged from it — where the packet states eight. The same on a wrapped row, whose figure stands
     * on the line under its name; and a figure wearing kcal is an energy, not a weight of fat.
     */
    @Test
    fun aMilligramFigureIsNotThatManyGrams() {
        assertEquals(8.0, requireNotNull(nutritionLabel("Protein 5 g\nFat 1 g\nSalt 20 mg").sodiumMg), 1e-9)
        assertEquals(8.0, requireNotNull(nutritionLabel("Salt\n20 mg").sodiumMg), 1e-9)
        assertEquals(0.5, requireNotNull(nutritionLabel("Sugars 500 mg").sugarG), 1e-9)
        assertEquals(0.9, requireNotNull(nutritionLabel("Клетчатка 900 мг").fiberG), 1e-9)
        assertNull(nutritionLabel("Fat 9 kcal").fatG)
        // Grams and bare figures are read as grams.
        assertEquals(360.0, requireNotNull(nutritionLabel("Salt 0.9 g").sodiumMg), 1e-9)
        assertEquals(5.0, requireNotNull(nutritionLabel("Sugars 5").sugarG), 1e-9)
    }

    /**
     * "Sodium 1,200mg" on a US panel is twelve hundred milligrams. Read as a fraction, as three digits
     * after a separator are beside grams, it would leave a bowl of instant noodles 1.2 mg of sodium.
     * Nobody prints a thousandth of a milligram; grams do have three decimals, and keep them.
     */
    @Test
    fun aThousandsGroupOfMilligramsIsNotAFraction() {
        assertEquals(1200.0, requireNotNull(nutritionLabel("Sodium 1,200mg").sodiumMg), 1e-9)
        assertEquals(1200.0, requireNotNull(nutritionLabel("Натрий 1.200 мг").sodiumMg), 1e-9)
        assertEquals(1.5, requireNotNull(nutritionLabel("Sodium 1,5 mg").sodiumMg), 1e-9)
        assertEquals(125.0, requireNotNull(nutritionLabel("Sodium 0.125 g").sodiumMg), 1e-9)
    }

    /**
     * Every US panel ends on "2,000 calories a day", which read as 2.0 would stand at the kJ/kcal ratio
     * to a "Calories 10" above it and become the packet's energy: two kilocalories for ten.
     */
    @Test
    fun theDailyDietInTheFootnoteIsNotTheEnergyOfThePacket() {
        val footnote = "2,000 calories a day is used for general nutrition advice."
        assertEquals(10.0, requireNotNull(nutritionLabel("Calories 10\n$footnote").kcal), 1e-9)
        val older = "Percent Daily Values are based on a 2,000 calorie diet."
        assertEquals(10.0, requireNotNull(nutritionLabel("Calories 10\n$older").kcal), 1e-9)
        // A kilojoule written in thousands keeps its unit and still counts.
        assertEquals(270.0, requireNotNull(nutritionLabel("Brennwert 1.130 kJ\n$footnote").kcal), 0.1)
    }

    /** The salt rows of the Ukrainian, Latvian, Lithuanian and Hungarian packets on the same shelf. */
    @Test
    fun saltIsReadInEveryLanguageOfTheShelf() {
        for (row in listOf("Сіль 0,5 г", "Sāls 0,5 g", "Druska 0,5 g", "Druskos 0,5 g", "Só 0,5 g")) {
            assertEquals(row, 200.0, requireNotNull(nutritionLabel(row).sodiumMg) { row }, 1e-9)
        }
        // Inside a word it is no salt row: "utolsó" is Hungarian for "last".
        assertNull(nutritionLabel("Utolsó 5 g").sodiumMg)
    }

    /**
     * Spaces go before anything else is read, figures included. That is what reads "1 130 kJ" as
     * one figure, and its price is that two unitless columns side by side weld into one: physics
     * refuses the weld once it is no longer a possible gram figure.
     */
    @Test
    fun aSpaceInsideAFigureIsNoBreakAndAWeldPastPhysicsIsRefused() {
        assertEquals(270.0, requireNotNull(nutritionLabel("Energie 1 130 kJ").kcal), 0.1)
        assertNull(nutritionLabel("Protein 12 5").proteinG)
    }
}
