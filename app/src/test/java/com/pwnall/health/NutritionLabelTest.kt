package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser between the OCR and the meal form. OCR text is noisy and label layouts vary, so
 * every fixture here is a layout seen on a real packet — the Russian table, the inline EU line,
 * the two-column table with a portion column, the kJ-only imports.
 */
class NutritionLabelTest {
    @Test
    fun readsARussianTable() {
        val label = nutritionLabel(
            """
            Пищевая ценность на 100 г продукта:
            Белки 12,0 г
            Жиры 5,0 г
            Углеводы 44,0 г
            Энергетическая ценность 1130 кДж / 270 ккал
            """.trimIndent(),
        )

        assertEquals(270.0, requireNotNull(label.kcal), 0.01)
        assertEquals(12.0, requireNotNull(label.proteinG), 0.01)
        assertEquals(5.0, requireNotNull(label.fatG), 0.01)
        assertEquals(44.0, requireNotNull(label.carbsG), 0.01)
        assertTrue(label.found)
    }

    @Test
    fun readsAnEnglishLabel() {
        val label = nutritionLabel(
            """
            Nutrition information per 100 g
            Energy 1500 kJ / 359 kcal
            Fat 6.2 g
            Carbohydrate 62 g
            of which sugars 18 g
            Fibre 7.0 g
            Protein 11 g
            Salt 0.9 g
            """.trimIndent(),
        )

        assertEquals(359.0, requireNotNull(label.kcal), 0.01)
        assertEquals(11.0, requireNotNull(label.proteinG), 0.01)
        assertEquals(6.2, requireNotNull(label.fatG), 0.01)
        assertEquals(62.0, requireNotNull(label.carbsG), 0.01)
        assertEquals(18.0, requireNotNull(label.sugarG), 0.01)
        assertEquals(7.0, requireNotNull(label.fiberG), 0.01)
        // 0.9 g of salt is 360 mg of sodium.
        assertEquals(360.0, requireNotNull(label.sodiumMg), 0.01)
    }

    /** Imports print kJ alone often enough that the conversion has to carry the line. */
    @Test
    fun kilojoulesConvertWhenNoKcalIsPrinted() {
        val label = nutritionLabel("Energia 1700 kJ")

        assertEquals(406.3, requireNotNull(label.kcal), 0.1)
    }

    /** "1130/270" with the units lost to OCR: the ratio names which half is the kcal. */
    @Test
    fun aBareEnergyPairPicksTheKilocalorieHalf() {
        assertEquals(270.0, requireNotNull(nutritionLabel("энергетическая ценность 1130 / 270").kcal), 0.01)
        // A single bare figure on the calorie line is believed as printed.
        assertEquals(52.0, requireNotNull(nutritionLabel("калорийность: 52").kcal), 0.01)
    }

    /** Sub-rows must not answer for their parent, whether they share the printed line or not. */
    @Test
    fun subordinateRowsDoNotStealTheParentMacro() {
        val split = nutritionLabel(
            """
            Жиры 10 г
            насыщенные жирные кислоты 4,0 г
            Углеводы 44 г
            в том числе сахара 20 г
            """.trimIndent(),
        )
        assertEquals(10.0, requireNotNull(split.fatG), 0.01)
        assertEquals(44.0, requireNotNull(split.carbsG), 0.01)
        assertEquals(20.0, requireNotNull(split.sugarG), 0.01)

        val inline = nutritionLabel("Углеводы 44 г, в том числе сахара 20 г")
        assertEquals(44.0, requireNotNull(inline.carbsG), 0.01)
        assertEquals(20.0, requireNotNull(inline.sugarG), 0.01)
    }

    /** "белки, г на 100 г 12" — the header's 100 is a column name, not the protein figure. */
    @Test
    fun theHundredGramHeaderIsNotAValue() {
        assertEquals(12.0, requireNotNull(nutritionLabel("белки, г на 100 г 12").proteinG), 0.01)
        assertEquals(3.4, requireNotNull(nutritionLabel("protein per 100 g 3.4 g").proteinG), 0.01)
    }

    /** The %-of-daily-norm column is a share, not an amount; the portion column sits rightward. */
    @Test
    fun percentAndPortionColumnsLoseToThePer100Column() {
        val label = nutritionLabel(
            """
            fat 5 g 8%
            белки 12,0 г 7,2 г
            """.trimIndent(),
        )

        assertEquals(5.0, requireNotNull(label.fatG), 0.01)
        assertEquals(12.0, requireNotNull(label.proteinG), 0.01)
    }

    /** A printed sodium figure beats one derived from salt, and grams convert to milligrams. */
    @Test
    fun sodiumIsPreferredOverSaltAndKeepsItsUnits() {
        val label = nutritionLabel("натрий 0,4 г\nсоль 1,2 г")

        assertEquals(400.0, requireNotNull(label.sodiumMg), 0.01)
    }

    /**
     * "Soluble Fibre" is a fibre sub-row and names no salt. The three-letter spellings the parser
     * keeps for the Czech and Slovak shelves hide inside it, and the sub-row prints above the salt
     * row it would answer for — so the phantom would be the figure the meal form is filled with.
     */
    @Test
    fun aSolubleFibreRowIsNotReadAsSalt() {
        val label = nutritionLabel(
            """
            Nutrition information per 100 g
            Energy 1500 kJ / 359 kcal
            Fat 6.2 g
            Carbohydrate 62 g
            Dietary Fibre 9 g
            Soluble Fibre 2 g
            Protein 11 g
            Salt 1.1 g
            """.trimIndent(),
        )

        assertEquals(9.0, requireNotNull(label.fiberG), 0.01)
        // 1.1 g of salt is 440 mg of sodium; the 2 g of soluble fibre would have said 800.
        assertEquals(440.0, requireNotNull(label.sodiumMg), 0.01)
    }

    /**
     * The composition line a stuffed packet prints above its table: «подсолнечное» (sunflower) carries the salt
     * root inside it, and the grams beside an ingredient are that ingredient's — a packet with no
     * salt row printed must not arrive with a sodium figure anyway.
     */
    @Test
    fun anIngredientHidingTheSaltRootInventsNoSodium() {
        val label = nutritionLabel(
            """
            Начинка: творог 30 г, масло подсолнечное 5 г
            Пищевая ценность на 100 г
            Белки 12,0 г
            Жиры 5,0 г
            Углеводы 44,0 г
            """.trimIndent(),
        )

        assertEquals(12.0, requireNotNull(label.proteinG), 0.01)
        assertNull(label.sodiumMg)
    }

    /** Figures physics forbids per 100 g are misread OCR, and dropping them beats showing them. */
    @Test
    fun impossibleFiguresAreDropped() {
        val label = nutritionLabel(
            """
            белки 250 г
            калорийность 2700 ккал
            """.trimIndent(),
        )

        assertNull(label.proteinG)
        assertNull(label.kcal)
        assertFalse(label.found)
    }

    /**
     * Tesseract's exact output for a photograph of a real Japanese carton,
     * cropped to its table and turned 90°. Half the vocabulary drowned (たんぱく質 came back as
     * "SNS", 炭水化物 lost its first kanji, 190 g lost its 1), but the kcal, the carbohydrates
     * and a serving marker survive — and everything lands editable.
     */
    @Test
    fun readsTheJapaneseCartonPhotograph() {
        val label = nutritionLabel(
            "| ポ 義 成分 表示\nА | (30g 当 た り )\n> 4 № 量 134kcal\n| SNS 6.3:\nee 区 水 化物 25.5g\n_ ОН\n",
        )

        assertEquals(134.0, requireNotNull(label.kcal), 0.01)
        assertEquals(25.5, requireNotNull(label.carbsG), 0.01)
        assertEquals(30.0, requireNotNull(label.perServingG), 0.01)
        assertTrue(label.found)
    }

    /** The same table as print would have it, full vocabulary, spaces shredded OCR-style. */
    @Test
    fun readsAJapaneseTable() {
        val label = nutritionLabel(
            """
            栄養成分表示 (190g 当たり)
            熱量 134kcal
            たんぱく質 6.3g
            脂質 0.8g
            炭水化物 25.5g
            糖類 12.1g
            食物繊維 0.4g
            食塩相当量 0.3g
            """.trimIndent(),
        )

        assertEquals(134.0, requireNotNull(label.kcal), 0.01)
        assertEquals(6.3, requireNotNull(label.proteinG), 0.01)
        assertEquals(0.8, requireNotNull(label.fatG), 0.01)
        assertEquals(25.5, requireNotNull(label.carbsG), 0.01)
        assertEquals(12.1, requireNotNull(label.sugarG), 0.01)
        assertEquals(0.4, requireNotNull(label.fiberG), 0.01)
        // 0.3 g of salt equivalent is 120 mg of sodium.
        assertEquals(120.0, requireNotNull(label.sodiumMg), 0.01)
        assertEquals(190.0, requireNotNull(label.perServingG), 0.01)
    }

    /**
     * The inline format: one running "エネルギー 123kcal/たんぱく質 5.8g/…" wrapped wherever the
     * carton ran out of width, so 脂質 and 食物繊維 end their lines and their numbers open the
     * next — the layout of a real carton as printed. 糖質 is available
     * carbohydrate, not sugars, and must stay unread.
     */
    @Test
    fun readsAnInlineLabelAcrossItsWraps() {
        val label = nutritionLabel(
            """
            栄養成分表示:
            1本190g当たり
            エネルギー 123kcal/
            たんぱく質 5.8g/脂質
            1.1g/炭水化物 22.7g
            (糖質 22.4g/食物繊維
            0.3g)/食塩相当量 0.19g/
            カルシウム 181mg
            """.trimIndent(),
        )

        assertEquals(123.0, requireNotNull(label.kcal), 0.01)
        assertEquals(5.8, requireNotNull(label.proteinG), 0.01)
        assertEquals(1.1, requireNotNull(label.fatG), 0.01)
        assertEquals(22.7, requireNotNull(label.carbsG), 0.01)
        assertEquals(0.3, requireNotNull(label.fiberG), 0.01)
        // 0.19 g of salt equivalent is 76 mg of sodium.
        assertEquals(76.0, requireNotNull(label.sodiumMg), 0.01)
        assertEquals(190.0, requireNotNull(label.perServingG), 0.01)
        assertNull(label.sugarG)
    }

    /**
     * PP-OCRv6 small's lines for a photograph of a real 12-language wafer wrapper:
     * the Bulgarian «Мазнини» came back as "Ma3Hw" and gave the fat row a 3, and the Georgian კკალ under
     * the energy cell came back as a bare 333. Atwater over that fat favours 333; the printed
     * 2256 kJ / 540 kcal pair outranks it, and the disagreement is flagged instead of hidden.
     */
    @Test
    fun aWitnessedKilojoulePairOutranksAMisreadMacro() {
        val label = nutritionLabel(
            """
            bil, ac     ()/   2256 κ/kJ/33 Mdat
            6 (kanopnoc)/ Enerji dəyəri/ Energinė verté/ Energētiskà un uzturyērtība/ Energiasisaldus/ 540 κk/kcal/ 3ngmm
            DMAnb Wartos energetyzn/ Valoare energetic/ Epr oo/ Evpyα/ 333 agodm
            Ne 169. pw/Mainap/Knpbi/NYağlar/Riebalai/Tauki/Rasvad/Ttuszcz/Grăsimi/Ma3Hw/Auapα/6wputn/Bbo∂n: 31,1r/g/8 J3mon6
            wbwb Byrneson/Kemipcynap/Yrneobi/Karbohidratlar/Angliavandeniai/Oglhidrāti/ Süsivesikud/ 57,1r/g/8 21001,
            kowe, binkn/Hapybi3ap/benkn/Zülallar/Baltymai/Olbaltumvielas/Valgud/ 7,2r/g/8 08304,
            """.trimIndent(),
        )

        assertEquals(540.0, requireNotNull(label.kcal), 0.01)
        assertEquals(57.1, requireNotNull(label.carbsG), 0.01)
        assertEquals(7.2, requireNotNull(label.proteinG), 0.01)
        assertTrue(label.approximate)
    }

    /**
     * PP-OCRv6 small's exact boxes for the same drinking-yoghurt carton, turned upright: every figure
     * read clean, but たんぱく質 lost the handakuten of its ぱ and came back as たんはく質.
     */
    @Test
    fun readsTheInlineLabelAsPpOcrV6ReadsIt() {
        val label = nutritionLabel(
            """
            栄養成分表示：
            1本190g当たり
            エネルギー123kcal/
            たんはく質5.8g/脂質
            1.1g/炭水化物22.7g
            (糖質22.4g/食物繊維
            0.3g)/食塩相当量0.19g/
            カルシウム181mg
            アントシアニン15mg
            """.trimIndent(),
        )

        assertEquals(123.0, requireNotNull(label.kcal), 0.01)
        assertEquals(5.8, requireNotNull(label.proteinG), 0.01)
        assertEquals(1.1, requireNotNull(label.fatG), 0.01)
        assertEquals(22.7, requireNotNull(label.carbsG), 0.01)
        assertEquals(0.3, requireNotNull(label.fiberG), 0.01)
        assertEquals(76.0, requireNotNull(label.sodiumMg), 0.01)
        assertEquals(190.0, requireNotNull(label.perServingG), 0.01)
        assertNull(label.sugarG)
    }

    /**
     * Tesseract's exact output for a photograph of a real packet: たんぱく came back as
     * たんぽく, the fat row dissolved entirely, and the answer is still worth offering — the fat
     * field stays empty instead of wrong.
     */
    @Test
    fun readsTheChipsBagPhotograph() {
        val label = nutritionLabel(
            "栄養 成分 表示 (1 袋 57g 当 た り ) _ ЕВ\n" +
                "| エネ ルギー 315kcall 炭 水 化物 \"31.6g\n" +
                "| た ん ぽく 質 2.9g | 食塩 相当 量 0.6g\n" +
                "‘|i Я 19.78 |\n",
        )

        assertEquals(315.0, requireNotNull(label.kcal), 0.01)
        assertEquals(2.9, requireNotNull(label.proteinG), 0.01)
        assertEquals(31.6, requireNotNull(label.carbsG), 0.01)
        assertEquals(240.0, requireNotNull(label.sodiumMg), 0.01)
        assertEquals(57.0, requireNotNull(label.perServingG), 0.01)
        assertNull(label.fatG)
    }

    /**
     * Tesseract's exact output for a photograph of a real konbini sticker in
     * dot-matrix print. The kilocalories came back as "2 1 ЗК" with a Cyrillic «З», which digit
     * normalisation must turn back into 213 rather than let the line shed a bogus 2 or 21.
     */
    @Test
    fun readsTheKonbiniStickerThroughCyrillicDigits() {
        val label = nutritionLabel(
            "бл VAAN) 熱量 2 1 ЗК НИЗ. 2= ВАТТ\n" +
                "2502 5 3+ 24. 85 食物 繊維 0.5g А ©\n" +
                "| 0 14g <> —_\n",
        )

        assertEquals(213.0, requireNotNull(label.kcal), 0.01)
        assertEquals(0.5, requireNotNull(label.fiberG), 0.01)
        // The stray "0 14g" line belongs to nobody: no keyword claimed it, so nothing may.
        assertNull(label.sodiumMg)
        assertTrue(label.found)
    }

    /**
     * Tesseract's exact output for a photograph of a real bottled tea whose
     * zero calories OCR as "Okcal". The letter is a lost zero, and zero is a reading: the person
     * logging a tea wants 0 kcal on the form, not a shrug.
     */
    @Test
    fun zeroCaloriesIsAReadingNotAnAbsence() {
        val label = nutritionLabel("ел ИНК\nFRATRA (100\nL2$-:Okcal EAE\n")

        assertEquals(0.0, requireNotNull(label.kcal), 0.01)
        assertTrue(label.found)
    }

    /** The frozen-bag header spells its portion in plain kana — あたり anchors like 当たり. */
    @Test
    fun aHiraganaServingMarkerCounts() {
        val label = nutritionLabel("宋 養 成分 表示 (100g あ た り )\n\n|158 kcal\n")

        assertEquals(158.0, requireNotNull(label.kcal), 0.01)
        assertEquals(100.0, requireNotNull(label.perServingG), 0.01)
    }

    /** "1本(110ml)当たり" — the bracket between the millilitres and the 当 changes nothing. */
    @Test
    fun aBracketedServingMarkerCounts() {
        val label = nutritionLabel("1本(110ml)当たり/熱量 47kcal")

        assertEquals(47.0, requireNotNull(label.kcal), 0.01)
        assertEquals(110.0, requireNotNull(label.perServingG), 0.01)
    }

    /**
     * The carry-over must not turn greedy: here 炭水化物 and 食塩相当量 both end their lines, but
     * the next line opens with words, not a number — a new table row, not a wrapped continuation.
     * Tesseract's exact output for the chips bag photographed sideways.
     */
    @Test
    fun aWordyNextLineIsNotAContinuation() {
        val label = nutritionLabel(
            "RRM (1857244)\n\n" +
                "| エネ ルギー 315kcall 炭 水 化 物\n" +
                "た ん ば く 質 2.9g | 食塩 相当 量\n" +
                "AB ® 19.75\n",
        )

        assertEquals(315.0, requireNotNull(label.kcal), 0.01)
        assertEquals(2.9, requireNotNull(label.proteinG), 0.01)
        // 19.75 sits after letters on its line: it answers for neither carbs nor salt.
        assertNull(label.carbsG)
        assertNull(label.sodiumMg)
    }

    /** The Czech shelf: diacritics lost to OCR, kJ/kcal pair, sub-rows under z toho. */
    @Test
    fun readsACzechTable() {
        val label = nutritionLabel(
            """
            Energie 1778 kJ/424 kcal
            Tuky 18 g
            z toho nasycené mastné kyseliny 11 g
            Sacharidy 60 g
            z toho cukry 56 g
            Vlaknina 4,2 g
            Bilkoviny 3 g
            Sul 0,02 g
            """.trimIndent(),
        )

        assertEquals(424.0, requireNotNull(label.kcal), 0.01)
        assertEquals(3.0, requireNotNull(label.proteinG), 0.01)
        assertEquals(18.0, requireNotNull(label.fatG), 0.01)
        assertEquals(60.0, requireNotNull(label.carbsG), 0.01)
        assertEquals(56.0, requireNotNull(label.sugarG), 0.01)
        assertEquals(4.2, requireNotNull(label.fiberG), 0.01)
        // 0.02 g of salt is 8 mg of sodium.
        assertEquals(8.0, requireNotNull(label.sodiumMg), 0.01)
    }

    /**
     * Tesseract's exact energy lines for a praline box: the real "434 kcal" lost its unit to
     * "434 kal" while the portion column donated a phantom "S4kcal". The bare kJ/kcal pair —
     * 1815/434 — is the witness that names the true half.
     */
    @Test
    fun aBareEnergyPairBeatsAPhantomUnit() {
        val label = nutritionLabel(
            "Warto% energetyczna/Energetické hodnota/ 1815 Ю/| 227 k)/\n" +
                "Energijska vrednost/Energia 434 kal} S4kcal 2%\n",
        )

        assertEquals(434.0, requireNotNull(label.kcal), 0.01)
    }

    /** "energetická hodnota" arrives beheaded as "hodnota"; the pair survives a misread digit. */
    @Test
    fun readsTheEnergyTailAlone() {
        assertEquals(
            544.0,
            requireNotNull(nutritionLabel("| hodnota 2772 61/544 heal").kcal),
            0.01,
        )
    }

    /** A lone unit-less kilojoule-sized figure converts — its kcal half simply never OCR'd. */
    @Test
    fun aLoneKilojouleFigureConverts() {
        val label = nutritionLabel("Энергетическая ценность 2256")

        assertEquals(539.2, requireNotNull(label.kcal), 0.1)
    }

    /** "Ма3нини" is «Мазнини» with a digit for its «з» — the 3 is a letter, not a fat reading. */
    @Test
    fun aDigitInsideACyrillicWordIsALetter() {
        val label = nutritionLabel("Ма3нини 31,1 г")

        assertEquals(31.1, requireNotNull(label.fatG), 0.01)
    }

    /**
     * The spaces are gone before the digit cures run, so "Białko 12 g" arrives as "białko12g". The
     * o that ends a word is a letter. Read as a zero it would make the figure "012" — 0,12 g of
     * protein by the eaten-comma rule — make a Spanish "energético 250 kcal" a quarter of a
     * kilocalorie, and leave "pro 100 g" naming no basis, so a German table would be filed under its
     * portion column. An o with a digit before it, or with no letter before it, is still a lost zero.
     */
    @Test
    fun theLetterEndingAWordIsNotAZero() {
        val polish = nutritionLabel(
            "Wartość energetyczna 1046 kJ / 250 kcal\nTłuszcz 20 g\nWęglowodany 5 g\nBiałko 12 g",
        )
        assertEquals(12.0, requireNotNull(polish.proteinG), 0.01)
        assertEquals(250.0, requireNotNull(nutritionLabel("Valor energético 250 kcal").kcal), 0.01)

        val german = nutritionLabel(
            "Nährwerte pro 100 g pro Portion (30 g)\nEnergie 1580 kJ / 375 kcal\nFett 5,0 g 1,5 g\n" +
                "Kohlenhydrate 70 g 21 g\nEiweiß 11 g 3,3 g",
        )
        assertTrue(german.perHundred)
        assertNull(german.perServingG)
        assertEquals(11.0, requireNotNull(german.proteinG), 0.01)

        assertEquals(105.0, requireNotNull(nutritionLabel("Energy 1O5 kcal").kcal), 0.01)
        assertEquals(0.5, requireNotNull(nutritionLabel("Carbohydrate: O5 g").carbsG), 0.01)
    }

    /** "57 g" welds into "579" when the unit reads as a digit; physics unwelds it. */
    @Test
    fun aWeldedGramUnitShedsItsNine() {
        assertEquals(57.0, requireNotNull(nutritionLabel("Kohlenhydrate 579").carbsG), 0.01)
        // A genuine figure inside the range is never touched.
        assertEquals(89.0, requireNotNull(nutritionLabel("Sacharidy 89 g").carbsG), 0.01)
    }

    /** エネルギー loses its エ and kcal its l; the zero and its unit still read. */
    @Test
    fun readsATruncatedJapaneseEnergyRow() {
        val label = nutritionLabel("=ネルギー0kca//たんば\n")

        assertEquals(0.0, requireNotNull(label.kcal), 0.01)
        assertTrue(label.found)
    }

    /** A plate of borscht OCRs into noise, and noise must not become a label offer. */
    @Test
    fun aPlateIsNotALabel() {
        assertFalse(nutritionLabel("").found)
        assertFalse(nutritionLabel("вилка нож тарелка 2 шт").found)
        // One macro alone is not enough evidence either.
        assertFalse(nutritionLabel("белки 12 г").found)
    }

    /** What the form receives: per-100 g basis, macros in place, extras only when printed. */
    @Test
    fun theFormGetsThePer100Basis() {
        val state = FoodState(title = "Хлопья", serving = "40").withLabel(
            "С этикетки",
            NutritionLabel(kcal = 270.0, proteinG = 12.0, fatG = 5.0, carbsG = 44.0, sugarG = 20.0),
        )

        assertEquals(MealBasis.PER_100G, state.basis)
        assertEquals("Хлопья", state.title)
        assertEquals("40", state.serving)
        assertEquals("С этикетки", state.subtitle)
        assertEquals("12.0", state.protein)
        assertEquals("5.0", state.fat)
        assertEquals("44.0", state.carbs)
        assertEquals("270.0", state.reportedEnergy)
        assertEquals("20.0", state.extra[ExtraNutrient.SUGAR])
        assertNull(state.extra[ExtraNutrient.FIBER])
        // 40 g of a 270 kcal/100 g product on the plate.
        assertEquals(108.0, state.portionCalories, 0.01)
    }

    /**
     * The US panel the Gulf shelf prints, as Tesseract returned it from a photograph of a real packet: its
     * figures are per serving, and filing 240 ml of milk as 100 g would understate the glass by
     * more than half.
     */
    @Test
    fun readsAUsPanelOnItsServing() {
        val label = nutritionLabel(
            """
            Nutrition Facts
            About 4 servings per container
            Serving size 1 cup (240 mL)
            Amount per serving
            Calories 110
            Total Fat 2g
            Sodium 125mg 5%
            Total Carbohydrate 9g 3%
            Total Sugars 9g
            Protein 10g 16%
            """.trimIndent(),
        )

        assertEquals(110.0, requireNotNull(label.kcal), 0.01)
        assertEquals(10.0, requireNotNull(label.proteinG), 0.01)
        assertEquals(2.0, requireNotNull(label.fatG), 0.01)
        assertEquals(9.0, requireNotNull(label.carbsG), 0.01)
        assertEquals(240.0, requireNotNull(label.perServingG), 0.01)
    }

    /** The peanut butter jar, whose serving grams arrive with the unit OCR turned into a 9. */
    @Test
    fun readsAServingWhoseGramsLostTheirUnit() {
        assertEquals(
            32.0,
            requireNotNull(nutritionLabel("Nutrition Facts\nServing size 2 tablespoon (32g)").perServingG),
            0.01,
        )
        assertEquals(
            130.0,
            requireNotNull(nutritionLabel("Serving Size: 1/2 Cup (1309)\nCalories 90").perServingG),
            0.01,
        )
    }

    /** A table that names its own 100 g basis keeps it: the figures read are that column's. */
    @Test
    fun aPerHundredTableIgnoresAPrintedServingSize() {
        val label = nutritionLabel(
            """
            Nutrition information per 100g
            Serving size 30 g
            Energy 745 kJ
            Protein 4,4 g
            """.trimIndent(),
        )

        assertNull(label.perServingG)
        assertEquals(178.0, requireNotNull(label.kcal), 0.5)
    }

    /**
     * The Gulf shelf's own handwriting, as PP-OCRv5 returned it: "kJ" read as "kl" and "kcal" as
     * "keall". Neither unit is recognisable, and the kilojoule figure must not be filed as the
     * calories — a bread wrapper claiming 745 kcal per 100 g.
     */
    @Test
    fun aKilojouleWhoseUnitOcrMangledIsStillAKilojoule() {
        val label = nutritionLabel("Energy value 745 kl\n(177 keall")

        // 745 kJ converts to 178.1 kcal; the packet rounded it to 177 and both are the same food.
        assertEquals(177.0, requireNotNull(label.kcal), 1.5)
    }

    /** "Amount per serving 100 ml Calories 78" — millilitres are a volume, never the energy. */
    @Test
    fun theServingVolumeIsNotTheCalories() {
        val label = nutritionLabel("Amount per serving 100 ml Calories 78\nTotal Fat 0.1 g")

        assertEquals(78.0, requireNotNull(label.kcal), 0.01)
    }

    /** "Serv. size:" ends the line and its grams open the next one. */
    @Test
    fun aServingSizeMayWrapOntoTheNextLine() {
        val label = nutritionLabel(
            "Servings: About 25, Serv. size:\n2 Tbsp (32g), Amount per serving:\nCalories 180",
        )

        assertEquals(32.0, requireNotNull(label.perServingG), 0.01)
    }

    /**
     * A pot printing both "Serving Size 1 pot (120g)" and "Amount per 100 (g)" declares the second
     * for its figures; the reader carries that flag so a serving read off another pass of the same
     * photograph cannot overrule it.
     */
    @Test
    fun aTableThatNamesPerHundredSaysSo() {
        val perHundred = nutritionLabel("Serving Size 1 pot (120g)\nAmount per 100 (g)\nCalories 78")
        assertTrue(perHundred.perHundred)
        assertNull(perHundred.perServingG)

        assertFalse(nutritionLabel("Serving size 2 tablespoon (32g)\nCalories 190").perHundred)
    }

    /**
     * "in a 100g of food contributes to a daily diet" — the Gulf footnote carries a "na 100" inside
     * "in a", which must not throw away the serving size of every packet printing that sentence.
     */
    @Test
    fun theDailyValueFootnoteIsNotAPerHundredBasis() {
        val label = nutritionLabel(
            "Serving size 2 tablespoon (32g)\nCalories 190\n" +
                "The % Daily Value tells you how much a nutrient in a 100g of food contributes",
        )

        assertEquals(32.0, requireNotNull(label.perServingG), 0.01)
    }

    /**
     * A Japanese packet declares its basis in the marker itself, and the recognizer returns it as
     * printed: a full-width bracket, 当り with the kana dropped, and — where the unit was lost —
     * "（1パック45当り)". A figure with no unit has to be big enough to be a weight, or "1枚当たり"
     * would file a chocolate square as one gram of chocolate.
     */
    @Test
    fun theJapaneseMarkerIsBelievedInEveryFormItIsPrinted() {
        assertEquals(240.0, requireNotNull(nutritionLabel("栄養成分表示:1本（240ml）当り").perServingG), 0.01)
        assertEquals(45.0, requireNotNull(nutritionLabel("(1パック45当り)").perServingG), 0.01)
        assertEquals(4.1, requireNotNull(nutritionLabel("栄養成分表示1枚（4.1）当たり").perServingG), 0.01)
        assertNull(nutritionLabel("1枚当たり エネルギー 23kcal").perServingG)
    }

    /** "脂質0.5g未満/100ml" is a fat-free claim, not the basis the table is printed on. */
    @Test
    fun aFatFreeClaimIsNotAPerHundredBasis() {
        val label = nutritionLabel("脂質0.5g未満/100ml\n栄養成分表示:1本(200ml)当たり\nエネルギー103kcal")

        assertFalse(label.perHundred)
        assertEquals(200.0, requireNotNull(label.perServingG), 0.01)
    }

    /** A label that named its portion fills the form on that portion, grams included. */
    @Test
    fun theFormGetsThePortionBasisWhenTheLabelNamesOne() {
        val state = FoodState().withLabel(
            "С этикетки",
            NutritionLabel(kcal = 134.0, carbsG = 25.5, perServingG = 190.0),
        )

        assertEquals(MealBasis.PER_PORTION, state.basis)
        assertEquals("190", state.serving)
        assertEquals(134.0, state.portionCalories, 0.01)
    }

    /**
     * The Japanese recogniser's dictionary carries full-width digits, ｇ, ｋｃａｌ and brackets and
     * returns them whenever the packet printed them; Java's \d is ASCII only, so without folding this
     * label would read as nothing at all. Compatibility folding also spells ㎉ out as kcal.
     */
    @Test
    fun fullWidthDigitsAndUnitsRead() {
        val label = nutritionLabel("エネルギー １２３kcal / たんぱく質 ５.８ｇ / （１９０ｇ当たり）")

        assertEquals(123.0, requireNotNull(label.kcal), 0.01)
        assertEquals(5.8, requireNotNull(label.proteinG), 0.01)
        assertEquals(190.0, requireNotNull(label.perServingG), 0.01)
        assertTrue(label.found)

        assertEquals(47.0, requireNotNull(nutritionLabel("熱量 47㎉").kcal), 0.01)
    }

    /**
     * A Canadian panel declares its serving as "Per 1 cup (250 mL)" or "Per 2 cookies (30 g)", its
     * French half as "pour", a German one as "Portion" and a Spanish one as "porción". With no
     * serving-size row, read as per 100 g by default, everything on them would be two and a half
     * times too small once converted. The French and Spanish per-100 headers keep declaring their
     * basis.
     */
    @Test
    fun perAndPortionMarkersNameTheServing() {
        assertEquals(250.0, requireNotNull(nutritionLabel("Per 1 cup (250 mL)\nCalories 110").perServingG), 0.01)
        assertEquals(30.0, requireNotNull(nutritionLabel("Per 2 cookies (30 g)\nCalories 140").perServingG), 0.01)
        assertEquals(250.0, requireNotNull(nutritionLabel("pour 1 tasse (250 mL)\nCalories 110").perServingG), 0.01)
        assertEquals(30.0, requireNotNull(nutritionLabel("Portion (30 g)\nEnergie 140 kcal").perServingG), 0.01)
        assertEquals(30.0, requireNotNull(nutritionLabel("Porción 30 g\nEnergía 140 kcal").perServingG), 0.01)
        // "Servings per container" carries the bare word and names nothing.
        assertNull(nutritionLabel("About 4 servings per container\nCalories 110").perServingG)

        val french = nutritionLabel("Valeur nutritive pour 100 g\nPortion 30 g\nEnergie 400 kcal")
        assertTrue(french.perHundred)
        assertNull(french.perServingG)
        val spanish = nutritionLabel("Información nutricional por 100 g\nPorción 30 g\nEnergía 400 kcal")
        assertTrue(spanish.perHundred)
        assertNull(spanish.perServingG)
    }

    /**
     * "Энергетическая ценность 100 г продукта 150 кал", kcal mangled to «кал»: with the «г» read the
     * 100 is a gram figure and never a candidate; with the «г» lost both figures are bare, and
     * reading order would take the column header for the calories — 100 sits inside the tolerance of
     * a 145 kcal table. The figure nearest what the macros weigh is believed instead.
     */
    @Test
    fun theColumnHeaderIsNotTheEnergyAmongBareFigures() {
        val macros = "Белки 5 г\nЖиры 5 г\nУглеводы 20 г\n"
        fun kcalOf(energyLine: String) = requireNotNull(nutritionLabel(macros + energyLine).kcal)

        assertEquals(150.0, kcalOf("Энергетическая ценность 100 продукта 150 кал"), 0.01)
        assertEquals(150.0, kcalOf("Энергетическая ценность 100 r продукта 150 кал"), 0.01)
        assertEquals(150.0, kcalOf("Энергетическая ценность 100 г продукта 150 кал"), 0.01)
    }

    /**
     * Sodium is printed to three decimals — "0.125 g" — and cut to two the leftover digit would eat
     * the unit, filing 0.12 mg where 125 belongs. Beside grams, three digits after a separator are
     * decimals; beside a kilojoule they are a thousands group, since German and Italian packets
     * print "1.130 kJ".
     */
    @Test
    fun threeDecimalsKeepTheirUnit() {
        assertEquals(125.0, requireNotNull(nutritionLabel("Sodium 0.125 g").sodiumMg), 0.01)
        // 0.125 g of salt is 50 mg of sodium; 0.12 g would have said 48.
        assertEquals(50.0, requireNotNull(nutritionLabel("Salt 0,125 g").sodiumMg), 0.01)

        assertEquals(270.0, requireNotNull(nutritionLabel("Brennwert 1.130 kJ / 270 kcal").kcal), 0.01)
        assertEquals(406.3, requireNotNull(nutritionLabel("Energia 1.700 kJ").kcal), 0.1)
    }
}
