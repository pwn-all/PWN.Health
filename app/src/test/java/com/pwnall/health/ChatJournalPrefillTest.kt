package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ChatJournalPrefillTest {
    private fun event(text: String, type: ChatJournalType, vararg fields: Pair<String, String>): ChatJournalEvent {
        val args = fields.map { (role, value) ->
            val at = text.indexOf(value)
            check(at >= 0)
            ChatJournalArgument(role, ChatJournalSpan(at, at + value.length, value))
        }
        return ChatJournalEvent(
            type,
            ChatJournalSpan(0, text.length, text),
            mapOf("subject" to "SELF", "assertion" to "AFFIRMED", "phase" to "DONE"),
            args,
        )
    }

    @Test
    fun twoFoodsKeepTheirOwnGramsDespiteArgumentsBeingOutOfOrder() {
        val text = "сегодня я кушал гречку 500г с вареной курицей 100г"
        val seed = event(
            text,
            ChatJournalType.FOOD_LOG,
            "PORTION" to "100г",
            "FOOD" to "вареной курицей",
            "PORTION" to "500г",
            "FOOD" to "гречку",
        )
        assertTrue(seed.groundedIn(text))
        assertEquals(listOf("гречку", "вареной курицей"), seed.prefillFoods().map { it.state.title })
        assertEquals(listOf("500", "100"), seed.prefillFoods().map { it.state.serving })
        assertEquals(600.0, seed.prefillFoods().combinedFood().servingG, 0.0)
        assertFalse(seed.prefillFoods().all { it.valid }) // Names and grams alone do not establish nutrition.
    }

    @Test
    fun quantitiesBeforeFoodsAndSeparateUnitSpansAreSupported() {
        val seed = event(
            "500 г гречки и 100 g курицы",
            ChatJournalType.FOOD_LOG,
            "PORTION" to "500",
            "UNIT" to "г",
            "FOOD" to "гречки",
            "PORTION" to "100",
            "UNIT" to "g",
            "FOOD" to "курицы",
        )
        assertEquals(listOf("500", "100"), seed.prefillFoods().map { it.state.serving })
    }

    @Test
    fun sharedOrAmbiguousPortionIsNotAssignedToBothFoods() {
        val seed = event(
            "рис, рыба — всего 300г",
            ChatJournalType.FOOD_LOG,
            "FOOD" to "рис",
            "FOOD" to "рыба",
            "PORTION" to "300г",
        ).copy(sourceText = "рис, рыба — всего 300г")
        // One amount is not evidence that both products weighed 300 g.
        assertTrue(seed.prefillFoods().all { it.state.serving.isEmpty() })
        assertTrue(seed.prefillNote().contains("300г"))
    }

    @Test
    fun aTotalInsideTheTaggedSpanIsStillNotOneFoodsPortion() {
        val seed = event(
            "рис и рыба, всего 300г",
            ChatJournalType.FOOD_LOG,
            "FOOD" to "рис",
            "FOOD" to "рыба",
            "PORTION" to "всего 300г",
        )
        assertTrue(seed.prefillFoods().all { it.state.serving.isEmpty() })
    }

    @Test
    fun gramsTaggedAsAmountOrValueReachTheirFoodsWhileCountsAndVolumesStayOut() {
        val seed = event(
            "2 яйца 120 г, гречка 200 грамм и молоко 250 мл",
            ChatJournalType.FOOD_LOG,
            "AMOUNT" to "2",
            "FOOD" to "яйца",
            "VALUE" to "120 г",
            "FOOD" to "гречка",
            "AMOUNT" to "200 грамм",
            "FOOD" to "молоко",
            "AMOUNT" to "250 мл",
        )
        // The egg count would otherwise be a second candidate for the eggs and blank their grams.
        assertEquals(listOf("120", "200", ""), seed.prefillFoods().map { it.state.serving })
    }

    @Test
    fun hedgedSpokenAndDoublyTaggedGramsReachTheirFoods() {
        val twice = event(
            "съел около 200 г гречки",
            ChatJournalType.FOOD_LOG,
            "PORTION" to "около 200 г",
            "AMOUNT" to "200 г",
            "FOOD" to "гречки",
        )
        // The same words under two roles are one amount, not two rivals for the buckwheat.
        assertEquals(listOf("200"), twice.prefillFoods().map { it.state.serving })
        val spoken = event(
            "курицы грамм двести и полкило риса",
            ChatJournalType.FOOD_LOG,
            "FOOD" to "курицы",
            "PORTION" to "грамм двести",
            "AMOUNT" to "полкило",
            "FOOD" to "риса",
        )
        assertEquals(listOf("200", "500"), spoken.prefillFoods().map { it.state.serving })
    }

    @Test
    fun unsupportedOrMissingUnitsStayUnknown() {
        assertNull("500".journalQuantity("mass"))
        assertNull("2 чашки".journalQuantity("mass"))
        assertNull("-100г".journalQuantity("mass"))
        assertNull("500 мл".journalQuantity("mass"))
        assertNull("100г и 200г".journalQuantity("mass"))
    }

    @Test
    fun explicitUnitsAndDecimalSeparatorsConvertWithoutDefaultPortions() {
        assertEquals(500.0, "0,5 кг".journalQuantity("mass")!!, 0.0)
        assertEquals(250.0, "0.25 l".journalQuantity("volume")!!, 0.0)
        assertEquals(420.0, "7 часов".journalQuantity("duration")!!, 0.0)
        assertEquals(500.0, "٥٠٠g".journalQuantity("mass")!!, 0.0)
    }

    /**
     * A comma or a point before three digits groups thousands in grams, millilitres and minutes, where nobody states
     * a thousandth: "1,500 ml" is a litre and a half, never 1.5 ml. In a litre or a kilogram it stays a decimal.
     */
    @Test
    fun thousandsAreGroupedWhereAThousandthOfTheUnitIsNoAmount() {
        assertEquals(1500.0, "1,500 ml".journalQuantity("volume")!!, 0.0)
        assertEquals(2000.0, "2.000 g".journalQuantity("mass")!!, 0.0)
        assertEquals(1.5, "1,5 ml".journalQuantity("volume")!!, 0.0)
        assertEquals(330.0, "0,330 л".journalQuantity("volume")!!, 0.0)
        assertEquals(1250.0, "1.250 kg".journalQuantity("mass")!!, 0.0)
        assertNull(",5 кг".journalQuantity("mass"))
    }

    @Test
    fun wordsAroundTheAmountAreToleratedButTheUnitMustSitBesideTheNumber() {
        assertEquals(200.0, "около 200 г".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "200 г примерно".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "грамм 200".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "гречка — 200 г".journalQuantity("mass")!!, 0.0) // A spaced dash is punctuation.
        assertEquals(420.0, "около 7 часов".journalQuantity("duration")!!, 0.0)
        assertNull("около 200".journalQuantity("mass"))
        assertNull("200 штук, а не грамм".journalQuantity("mass"))
    }

    @Test
    fun massUnitsOfOtherLanguagesAndDecagramsConvert() {
        val grams = listOf(
            "gr", "GR.", "gramm", "gramme", "grammes", "grammi", "gramos", "gramas", "gramów", "gramy",
            "グラム", "克", "그램", "غرام", "جرام", "ग्राम",
        )
        for (unit in grams) assertEquals(unit, 200.0, "200 $unit".journalQuantity("mass")!!, 0.0)
        val kilograms = listOf(
            "kilo", "Kilos", "kilogram", "kilograms", "kilogramm", "кило", "公斤", "килограмм", "килограмма",
            "килограмму", "килограммом", "килограмме", "килограммы", "килограммов", "килограммам",
            "килограммами", "килограммах",
        )
        for (unit in kilograms) assertEquals(unit, 1500.0, "1,5 $unit".journalQuantity("mass")!!, 0.0)
        assertEquals(250.0, "25 dag".journalQuantity("mass")!!, 0.0)
        assertEquals(250.0, "25 dkg".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "200克".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "٢٠٠ غرام".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "२०० ग्राम".journalQuantity("mass")!!, 0.0)
        assertNull("25 dag".journalQuantity("volume"))
    }

    @Test
    fun signedNumbersAndRangesAreRefusedRatherThanReadAsOneEnd() {
        val refused = listOf(
            "100-200 г", "100–200 г", "100 - 200 г", "от 100 до 200 г", "+100 г", "−100 г", "100+ г", ".5 кг",
            "сто-двести грамм",
        )
        for (text in refused) assertNull(text, text.journalQuantity("mass"))
    }

    @Test
    fun commonSpokenMassesAreReadButLongerNumbersStayUnknown() {
        val russian = listOf(
            "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот",
        )
        val english = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        for (i in russian.indices) {
            assertEquals(russian[i], 100.0 * (i + 1), "${russian[i]} грамм".journalQuantity("mass")!!, 0.0)
            assertEquals(english[i], 100.0 * (i + 1), "${english[i]} hundred grams".journalQuantity("mass")!!, 0.0)
        }
        assertEquals(100.0, "a hundred g".journalQuantity("mass")!!, 0.0)
        assertEquals(1000.0, "Тысяча грамм".journalQuantity("mass")!!, 0.0)
        assertEquals(1000.0, "тысячу г".journalQuantity("mass")!!, 0.0)
        assertEquals(500.0, "полкило".journalQuantity("mass")!!, 0.0)
        assertEquals(500.0, "где-то пол кило".journalQuantity("mass")!!, 0.0)
        assertEquals(1500.0, "полтора кило".journalQuantity("mass")!!, 0.0)
        assertEquals(1500.0, "полтора килограмма".journalQuantity("mass")!!, 0.0)
        assertEquals(500.0, "about half a kilo".journalQuantity("mass")!!, 0.0)
        assertEquals(200.0, "грамм двести".journalQuantity("mass")!!, 0.0)
        // Not a number parser: a longer number is left unread rather than cut to its first word.
        assertNull("сто пятьдесят грамм".journalQuantity("mass"))
        assertNull("грамм сто пятьдесят".journalQuantity("mass"))
        assertNull("two hundred fifty grams".journalQuantity("mass"))
        assertNull("сто грамм и 200 г".journalQuantity("mass"))
        assertNull("просто грамм".journalQuantity("mass")) // Whole words only: never "про100 грамм"…
        assertNull("стог".journalQuantity("mass")) // …or "100г".
        assertNull("полкило".journalQuantity("volume"))
    }

    @Test
    fun waterWithoutAnAmountDoesNotReceiveAnInventedGlass() {
        val seed = event("я пил воду", ChatJournalType.WATER_LOG)
        assertNull(seed.prefillQuantity("AMOUNT", "volume"))
        val stated = event("я пил 0,5 л воды", ChatJournalType.WATER_LOG, "AMOUNT" to "0,5", "UNIT" to "л")
        assertEquals(500.0, stated.prefillQuantity("AMOUNT", "volume")!!, 0.0)
    }

    @Test
    fun aStatedWaterVolumeReachesTheWaterFormWhicheverRoleCarriesIt() {
        val glued = event("выпил 200мл воды", ChatJournalType.WATER_LOG, "AMOUNT" to "200мл", "DRINK_KIND" to "воды")
        assertEquals(200.0, glued.prefillWaterMl()!!, 0.0)
        val apart = event("выпил 200 мл воды", ChatJournalType.WATER_LOG, "VALUE" to "200", "UNIT" to "мл")
        assertEquals(200.0, apart.prefillWaterMl()!!, 0.0)
        val portion = event("drank 500 milliliters of water", ChatJournalType.WATER_LOG, "PORTION" to "500 milliliters")
        assertEquals(500.0, portion.prefillWaterMl()!!, 0.0)
        val glass = event("выпил стакан воды", ChatJournalType.WATER_LOG, "AMOUNT" to "стакан")
        assertNull(glass.prefillWaterMl()) // A glass is not a stated volume: the field stays blank for review.
        val two = event("выпил 200 мл и 300 мл", ChatJournalType.WATER_LOG, "AMOUNT" to "200 мл", "AMOUNT" to "300 мл")
        assertNull(two.prefillWaterMl())
    }

    @Test
    fun waterVolumesOfEveryLanguageConvert() {
        val millilitres = listOf(
            "миллилитров", "миллилитра", "milliliters", "millilitres", "mililitros", "millilitri", "mililitrów",
            "mililitrů", "mililitre", "mililiter", "ミリリットル", "毫升", "밀리리터", "مل", "मिलीलीटर",
        )
        for (unit in millilitres) assertEquals(unit, 200.0, "200 $unit".journalQuantity("volume")!!, 0.0)
        val litres = listOf(
            "литры", "литрах", "liters", "litres", "litro", "litri", "litry", "litrów", "リットル", "升", "리터", "لتر",
        )
        for (unit in litres) assertEquals(unit, 1500.0, "1,5 $unit".journalQuantity("volume")!!, 0.0)
        assertEquals(330.0, "33 cl".journalQuantity("volume")!!, 0.0)
        assertEquals(500.0, "пол литра".journalQuantity("volume")!!, 0.0)
        assertEquals(500.0, "выпил пол-литра".journalQuantity("volume")!!, 0.0) // The spelling the rules ask for.
        assertEquals(500.0, "поллитра".journalQuantity("volume")!!, 0.0)
        assertEquals(1500.0, "полтора литра".journalQuantity("volume")!!, 0.0)
        assertEquals(500.0, "half a litre".journalQuantity("volume")!!, 0.0)
        assertNull("пол литра".journalQuantity("mass"))
    }

    @Test
    fun completedWorkoutCarriesSeparateCountsLoadAndDuration() {
        val seed = event(
            "приседания 3 подхода по 10 повторений с 20кг за 15мин",
            ChatJournalType.WORKOUT_LOG,
            "EXERCISE" to "приседания",
            "SETS" to "3 подхода",
            "REPS" to "10 повторений",
            "LOAD" to "20кг",
            "DURATION" to "15мин",
        )
        assertEquals(3, seed.prefillCount("SETS"))
        assertEquals(10, seed.prefillCount("REPS"))
        assertEquals(20_000.0, seed.prefillQuantity("LOAD", "mass")!!, 0.0)
        assertEquals(15.0, seed.prefillQuantity("DURATION", "duration")!!, 0.0)
        assertEquals("SQUATS", seed.prefillExercise(mapOf("SQUATS" to "Приседания", "RUN" to "Бег")))
        assertNull(seed.prefillExercise(mapOf("RUN" to "Бег")))
    }

    /** A count in another script's digits is read as an amount in them is: «٣ مجموعات» is three sets. */
    @Test
    fun countsInAnyDecimalDigitsAreRead() {
        val text = "٣ مجموعات ١٠ تكرارات"
        val arabic = event(text, ChatJournalType.WORKOUT_LOG, "SETS" to "٣ مجموعات", "REPS" to "١٠ تكرارات")
        assertEquals(3, arabic.prefillCount("SETS"))
        assertEquals(10, arabic.prefillCount("REPS"))
        assertEquals(12, event("१२ बार", ChatJournalType.WORKOUT_LOG, "REPS" to "१२ बार").prefillCount("REPS"))
    }

    @Test
    fun aRunToldWithoutAnExerciseIsFoundByItsAnchorNotFiledAsStrength() {
        fun span(
            text: String,
            part: String,
        ) = ChatJournalSpan(text.indexOf(part), text.indexOf(part) + part.length, part)
        fun told(text: String, anchor: String, vararg args: Pair<String, String>) = ChatJournalEvent(
            ChatJournalType.WORKOUT_LOG,
            span(text, anchor),
            mapOf("subject" to "SELF", "assertion" to "AFFIRMED", "phase" to "DONE"),
            args.map { (role, part) -> ChatJournalArgument(role, span(text, part)) },
        )
        val ru = linkedMapOf(
            "STRENGTH" to "Силовая тренировка",
            "SQUATS" to "Приседания",
            "BENCH_PRESS" to "Жим лёжа",
            "LEG_PRESS" to "Жим ногами",
            "WALK" to "Ходьба, умеренный темп",
            "RUN" to "Бег, спокойный темп",
            "SWIMMING" to "Плавание, кроль",
        )
        // What the shipped reader gives for it: the event "бегал", its EXERCISE cut to "бе", its distance.
        assertEquals(
            "RUN",
            told("я бегал 1.5км", "бегал", "EXERCISE" to "бе", "DISTANCE" to "1.5км").prefillExercise(ru),
        )
        assertEquals("RUN", told("я бегал 1.5км", "бегал").prefillExercise(ru))
        assertEquals("RUN", told("утром побегал", "побегал").prefillExercise(ru))
        assertEquals("WALK", told("ходил час", "ходил").prefillExercise(ru))
        assertEquals("SWIMMING", told("плавал в бассейне", "плавал").prefillExercise(ru))
        assertEquals("STRENGTH", told("тренировался в зале", "тренировался").prefillExercise(ru))
        // Every word counts: "ногами" takes the leg press past the bench both start with "жим".
        assertEquals("LEG_PRESS", told("жим ногами 3x10", "жим ногами").prefillExercise(ru))
        assertEquals("BENCH_PRESS", told("жим лёжа 3x10", "жим лёжа").prefillExercise(ru))
        // Nothing the catalogue knows: no guess, the cards are asked instead.
        assertNull(told("занимался", "занимался").prefillExercise(ru))
        // The English titles name a run; English the titles do not know goes through the importer's phrases.
        val en = linkedMapOf("STRENGTH" to "Strength training", "RUN" to "Running, easy pace")
        assertEquals("RUN", told("i was running", "running").prefillExercise(en))
        assertEquals("RUN", told("went jogging", "jogging").prefillExercise(en))
        assertEquals("RUN", told("i was running", "running").prefillExercise(ru))
    }

    @Test
    fun negatedOtherPersonAndPlannedFoodCannotSeedPersonalForms() {
        val seed = event("съел рис 100г", ChatJournalType.FOOD_LOG, "FOOD" to "рис", "PORTION" to "100г")
        for ((key, value) in listOf("assertion" to "NEGATED", "subject" to "CHILD", "phase" to "PLANNED")) {
            val rejected = seed.copy(attributes = seed.attributes + (key to value))
            assertTrue(rejected.prefillFoods().isEmpty())
            assertNull(journalPrefill(encodeJournalEvents(listOf(rejected))))
        }
    }

    @Test
    fun choosingCatalogueNutritionKeepsExplicitGramsAndMissingWeight() {
        val picked = TOP_FOODS.first()
        val draft = FoodState(title = "исходное имя", serving = "500")
        val filled = draft.withJournalCatalogFood(picked, Locale.ENGLISH)
        assertEquals("500", filled.serving)
        assertEquals(picked.nutrition.kcal * 5, filled.portionCalories, 0.6)
        assertEquals("", draft.copy(serving = "").withJournalCatalogFood(picked, Locale.ENGLISH).serving)
    }

    @Test
    fun changingCatalogueFatKeepsTheStatedPortionRatherThanTheDefault() {
        val food = TOP_FOODS.first { it.fatSteps.isNotEmpty() }
        val percent = food.fatSteps.first()
        val filled = FoodState(serving = "500").withJournalCatalogFood(food, Locale.ENGLISH, percent)
        assertEquals("500", filled.serving)
        assertEquals(percent * 5, filled.portionFat, 0.6)
    }

    @Test
    fun reviewedRowsSurviveRecreationWithoutReplacingManualCorrections() {
        val food = TOP_FOODS.first()
        val rows = listOf(
            ChatFoodDraft(
                FoodState(
                    title = "первый",
                    serving = "450",
                    protein = "10",
                    fat = "2",
                    carbs = "20",
                    reportedEnergy = "150",
                    extra = mapOf(ExtraNutrient.FIBER to "0"),
                ),
                food.id,
            ),
            ChatFoodDraft(
                FoodState(title = "второй", serving = "100", reportedEnergy = "200", basis = MealBasis.PER_PORTION),
            ),
        )
        val restored = rows.encodeChatFoods().decodeChatFoods()
        assertEquals(rows, restored)
        assertEquals(550.0, restored.combinedFood().servingG, 0.0)
        assertEquals(875.0, restored.combinedFood().portionCalories, 0.0)
        assertNull(restored.combinedFoodSource()) // Never pretend the catalogue covers the manual ingredient.
        assertNull(restored.combinedFood().portionExtra(ExtraNutrient.FIBER))
    }

    @Test
    fun savedManualAndMixedIngredientsKeepCorrectionsAndUnrelatedPayload() {
        val rows = listOf(
            ChatFoodDraft(FoodState(title = "гречка", serving = "450", reportedEnergy = "110")),
            ChatFoodDraft(
                FoodState(title = "курица", serving = "100", reportedEnergy = "165"),
                TOP_FOODS.first().id,
            ),
        )
        val payload = rows.foodDraftPayload("{\"custom\":42}")!!
        assertEquals(rows, savedChatFoods(payload))
        assertEquals(42, org.json.JSONObject(payload).getInt("custom"))
        val replaced = emptyList<ChatFoodDraft>().foodDraftPayload(payload)!!
        assertTrue(savedChatFoods(replaced).isEmpty())
        assertEquals(42, org.json.JSONObject(replaced).getInt("custom"))
        assertNull(emptyList<ChatFoodDraft>().foodDraftPayload(rows.foodDraftPayload(null)))
        assertTrue(savedChatFoods("{\"chat_food_v1\":[{}]}").isEmpty())
        assertEquals("legacy", emptyList<ChatFoodDraft>().foodDraftPayload("legacy"))
        assertEquals("legacy", org.json.JSONObject(rows.foodDraftPayload("legacy")!!).getString("original_payload"))
    }

    @Test
    fun catalogueSourcePreservesBothReviewedIngredientsAndAmounts() {
        val foods = TOP_FOODS.take(2)
        val rows = foods.mapIndexed { index, food ->
            ChatFoodDraft(
                FoodState(
                    serving = if (index ==
                        0
                    ) {
                        "500"
                    } else {
                        "100"
                    },
                ).withJournalCatalogFood(food, Locale.ENGLISH),
                food.id,
            )
        }
        val source = rows.combinedFoodSource()!!.decodeDish()
        assertEquals(foods.map { it.id }, source.map { it.food.id })
        assertEquals(listOf(500.0, 100.0), source.map { it.grams })
    }

    @Test
    fun sourceMessageAndItsDayTravelToTheFormAndWrongOffsetsAreRejected() {
        val text = "я ел рис 100г"
        val seed = event(text, ChatJournalType.FOOD_LOG, "FOOD" to "рис", "PORTION" to "100г")
            .copy(sourceText = text, sourceDay = "2026-10-08")
        val restored = journalPrefill(encodeJournalEvents(listOf(seed)))!!
        assertEquals(seed, restored)
        assertEquals(text, restored.prefillNote())
        assertEquals(LocalDate.of(2026, 10, 8), restored.prefillDay(LocalDate.of(2026, 10, 10)))
        assertNull(journalPrefill(encodeJournalEvents(listOf(seed.copy(sourceText = "другой текст")))))
    }

    /** A payload restored damaged — no JSON, or an object where the events' array stood — opens the form unseeded. */
    @Test
    fun aPayloadThatIsNoArrayOfEventsSeedsNothing() {
        assertNull(journalPrefill("not json"))
        assertNull(journalPrefill("{}"))
        assertNull(journalPrefill(null))
    }

    @Test
    fun sleepDurationDoesNotInventBedtimeAndRelativeDayIsResolved() {
        val seed = event("вчера спал 7 часов", ChatJournalType.SLEEP_LOG, "TIME" to "вчера", "DURATION" to "7 часов")
        assertEquals(LocalDate.of(2026, 10, 9), seed.prefillDay(LocalDate.of(2026, 10, 10)))
        assertEquals(420.0, seed.prefillQuantity("DURATION", "duration")!!, 0.0)
        assertNull(seed.prefillClock("START_TIME"))
        assertNull(seed.prefillClock("END_TIME"))
    }

    /**
     * A TIME is read by the day it opens with, in the app's languages (read by the four exact words
     * «сегодня», "today", «вчера» and "yesterday" alone, «вчера вечером» and "gestern" would file under
     * today).
     */
    @Test
    fun aDayIsReadFromTheWordsItsTimeOpensWith() {
        val base = LocalDate.of(2026, 10, 10)
        fun day(time: String, phase: String = "DONE"): LocalDate? {
            val seed = event("$time 300 ml", ChatJournalType.WATER_LOG, "TIME" to time)
            return seed.copy(attributes = seed.attributes + ("phase" to phase)).prefillDay(base)
        }
        listOf("вчера вечером", "Yesterday at 8", "gestern", "ayer por la noche", "hier soir", "昨天晚上", "어제는")
            .forEach { assertEquals(it, base.minusDays(1), day(it)) }
        listOf("позавчера", "the day before yesterday", "ieri l’altro", "kemarin dulu", "一昨日", "avant-hier")
            .forEach { assertEquals(it, base.minusDays(2), day(it)) }
        // Arabic names the day before with yesterday's word first as well: the longer words are read first.
        assertEquals(base.minusDays(2), day("أمس الأول"))
        listOf("сегодня утром", "heute", "今天").forEach { assertEquals(it, base, day(it)) }
        assertEquals(LocalDate.of(2026, 10, 3), day("2026-10-03"))
        // A word that only begins as a day's does, and a time that names no day: none, never a guess.
        listOf("hierher", "last night", "утром", "в пятницу").forEach { assertNull(it, day(it)) }
        // Hindi's कल is tomorrow as well: a day back only in what is done.
        assertEquals(base.minusDays(1), day("कल रात"))
        assertNull(day("कल", phase = "PLANNED"))
    }
}
