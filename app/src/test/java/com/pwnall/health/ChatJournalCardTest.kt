package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A record the person's words could become is the bot's answer to them: a card
 * in the turn under the message, in place of "found none" and of "got it", with no line to call for an
 * emergency beside it ([SAFETY_LINE]) and no whole menu under it — and a cancelled card stays folded in
 * the chat's own payload.
 */
class ChatJournalCardTest {
    private val setting = chatSetting().copy(journal = ChatJournalType.entries.toSet() - ChatJournalType.SYMPTOM)

    private fun proposal(type: ChatJournalType, text: String, words: String): ChatJournalEvent {
        val start = text.indexOf(words)
        return ChatJournalEvent(
            type,
            ChatJournalSpan(start, start + words.length, words),
            mapOf("subject" to "SELF", "assertion" to "AFFIRMED"),
        )
    }

    private fun read(vararg events: ChatJournalEvent) = chatRead { copy(journal = events.toList()) }

    private fun card(event: ChatJournalEvent, text: String, thread: Int = 0, message: Int = 0) =
        BotPiece.Journal(event.copy(sourceText = text), thread, message)

    private fun opening(text: String, read: ChatRead, setting: ChatSetting = this.setting) =
        chatTurn(ChatLog().wroteRead(text, read, setting), setting)

    @Test
    fun wordsTheRulesReadNothingInAreAnsweredWithTheCardAlone() {
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val turn = opening(text, read(water))
        assertEquals(listOf(card(water, text)), turn.pieces)
        // Only "Show all topics" under it: the whole menu stood once, at the opening.
        assertEquals(ChatReply.Topics(emptyList(), all = false), turn.reply)
    }

    @Test
    fun wordsWithNoCardKeepTheLineToCallAndShowTheMenuOnlyOnATap() {
        val turn = opening("hmm", chatRead())
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), turn.lines())
        assertEquals(ChatReply.Topics(emptyList(), all = false), turn.reply)
    }

    @Test
    fun aSecondEverydayDrinkIsReadAsTheFirstWas() {
        val two = ChatLog().wroteRead("I drank 250 ml of water", null, setting)
            .wroteRead("I drank 300 ml of water", null, setting)
        assertEquals(listOf(ChatLine.EVERYDAY_HEARD), chatTurn(two, setting).lines())
    }

    @Test
    fun anEverydayDrinkIsAnsweredWithTheCardAlone() {
        val text = "I drank 250 ml of water"
        val water = proposal(ChatJournalType.WATER_LOG, text, "water")
        assertEquals(listOf(card(water, text)), opening(text, read(water)).pieces)
        val russian = "выпил 200мл воды"
        val drink = proposal(ChatJournalType.WATER_LOG, russian, "воды")
        assertEquals(listOf(card(drink, russian)), opening(russian, read(drink)).pieces)
    }

    @Test
    fun withoutAnOfferedTypeOrARoutingReadingTheChatSaysWhatItAlwaysSaid() {
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val none = setting.copy(journal = emptySet())
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), opening(text, read(water), none).lines())
        val shadow = chatRead { copy(shadow = true, journal = listOf(water)) }
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), opening(text, shadow).lines())
        val someoneElse = water.copy(attributes = mapOf("subject" to "OTHER", "assertion" to "AFFIRMED"))
        assertTrue(opening(text, read(someoneElse)).pieces.none { it is BotPiece.Journal })
    }

    @Test
    fun aMessageAfterACardIsNotNoMatchTwiceInARow() {
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val twice = ChatLog().wroteRead(text, read(water), setting).wroteRead("hmm", chatRead(), setting)
        assertEquals(listOf(ChatLine.NO_MATCH_MODEL, ChatLine.SAFETY), chatTurn(twice, setting).lines())
    }

    @Test
    fun aMessageTheRulesRouteToATopicKeepsItsOtherRecordsButNotTheTopicsOwn() {
        val text = "болит колено, выпил 250 мл воды"
        val water = proposal(ChatJournalType.WATER_LOG, text, "воды")
        val pain = proposal(ChatJournalType.PAIN, text, "болит колено")
        val log = ChatLog().wroteRead(text, read(pain, water), setting)
        assertEquals(SymptomStep.PAIN_AREA, log.asked(setting))
        val cards = chatTurn(log, setting).pieces.filterIsInstance<BotPiece.Journal>()
        assertEquals(listOf(card(water, text)), cards)
    }

    @Test
    fun noCardStandsBesideWhereHelpIs() {
        val text = "выпил воды и хочу умереть"
        val water = proposal(ChatJournalType.WATER_LOG, text, "воды")
        val turn = chatTurn(ChatLog().wroteRead(text, read(water), setting), setting)
        assertTrue("$turn", turn.pieces.none { it is BotPiece.Journal })
    }

    @Test
    fun aRemarkDuringAQuestionCarriesTheCard() {
        val knee = ChatLog().wrote("болит колено").settle(setting)
        assertEquals(SymptomStep.PAIN_AREA, knee.asked(setting))
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val turn = chatTurn(knee.wroteRead(text, read(water), setting), setting)
        assertEquals(listOf(ChatLine.REMARK_MODEL), turn.lines())
        val at = turn.pieces.indexOfFirst { it is BotPiece.Journal }
        assertEquals(BotPiece.Line(ChatLine.REMARK_MODEL), turn.pieces[at - 1])
        assertEquals(1, (turn.pieces[at] as BotPiece.Journal).message)
        // Without a card the remark keeps its line to call.
        assertEquals(
            listOf(ChatLine.REMARK_MODEL, ChatLine.SAFETY),
            chatTurn(knee.wroteRead(text, chatRead(), setting), setting).lines(),
        )
    }

    @Test
    fun theHistoryDrawsTheCardUnderItsMessageAsTheLiveChatDid() {
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val log = ChatLog().wroteRead(text, read(water), setting).wroteRead("hmm", chatRead(), setting)
        val items = chatTranscript(log, setting, live = false)
        val person = items.indexOfFirst { it is ChatItem.Person && it.text == text }
        val answer = items[person + 1] as ChatItem.Bot
        assertEquals(listOf(card(water, text)), answer.turn.pieces)
    }

    @Test
    fun whatBecameOfACardSurvivesThePayloadAndARotation() {
        val text = "250 ml"
        val water = proposal(ChatJournalType.WATER_LOG, text, "250 ml")
        val log = ChatLog().wroteRead(text, read(water), setting)
        val key = card(water, text).key
        listOf(
            CardMark.Declined,
            CardMark.Opened,
            CardMark.Added("WATER", 42L, 1_760_000_000_001L, "Water", 300.0),
        ).forEach { mark ->
            val marked = log.marked(0, key, mark)
            assertEquals(mapOf(key to mark), marked.threads.single().cards)
            assertEquals(mark, marked.markOf(card(water, text)))
            listOf(false, true).forEach { pending ->
                assertEquals(
                    mapOf(key to mark),
                    decodeChatLog(encodeChatLog(marked, pending = pending))?.threads?.single()?.cards,
                )
            }
        }
        // Nothing is written for a thread without one; a type or a state this build does not know is read past.
        assertTrue("\"cards\"" !in encodeChatLog(log, pending = false))
        val declined = encodeChatLog(log.marked(0, key, CardMark.Declined), pending = false)
        assertEquals(
            emptyMap<JournalKey, CardMark>(),
            decodeChatLog(declined.replace("WATER_LOG", "LATER_LOG"))?.threads?.single()?.cards,
        )
        assertEquals(
            emptyMap<JournalKey, CardMark>(),
            decodeChatLog(declined.replace("DECLINED", "LATER"))?.threads?.single()?.cards,
        )
        // Another thread's id changes nothing.
        assertEquals(log, log.marked(7, key, CardMark.Declined))
    }

    @Test
    fun aRecordReadTwiceInOverlappingWordsIsOneCard() {
        val text = "съел 200 г гречки"
        val atVerb = proposal(ChatJournalType.FOOD_LOG, text, "съел")
            .copy(arguments = listOf(ChatJournalArgument("FOOD", ChatJournalSpan(11, 17, "гречки"))))
        val atFood = proposal(ChatJournalType.FOOD_LOG, text, "200 г гречки")
        val elsewhere = proposal(ChatJournalType.WATER_LOG, text, "съел")
        val offered = ChatJournalType.entries.toSet()
        val cards = journalProposals(ChatTurn.Wrote(text, read(atVerb, atFood, elsewhere)), offered)
        assertEquals(listOf(ChatJournalType.FOOD_LOG, ChatJournalType.WATER_LOG), cards.map { it.type })
    }

    @Test
    fun theCardSavesWaterAndAPickedFoodItselfAndOpensEveryOtherForm() {
        val day = LocalDate.of(2026, 10, 11)
        val text = "I drank 250 ml of water"
        val water = proposal(ChatJournalType.WATER_LOG, text, "water")
            .copy(
                arguments = listOf(ChatJournalArgument("AMOUNT", ChatJournalSpan(8, 14, "250 ml"))),
                sourceText = text,
            )
        fun waterRow(ml: Int, onDay: String) =
            JournalEntry(
                day = onDay,
                occurredAt = 0L,
                kind = "WATER",
                title = "Water",
                calories = 0.0,
                servingG = ml.toDouble(),
            )
        assertEquals(250.0, cardRow(water, JournalDraft(), day, ::waterRow, ::cupRow)?.servingG)
        val typed = cardRow(water, JournalDraft(millilitres = "330"), day, ::waterRow, ::cupRow)
        assertEquals(330.0, typed?.servingG)
        assertEquals(day.toString(), typed?.day)
        assertEquals(null, cardRow(water, JournalDraft(millilitres = "0"), day, ::waterRow, ::cupRow))
        assertTrue(!water.addable(JournalDraft(millilitres = "")))

        val meal = "I ate 200 g of rice"
        val rice = proposal(ChatJournalType.FOOD_LOG, meal, "rice").copy(sourceText = meal)
        // No food picked: the form opens, with nothing saved from the card.
        assertEquals(null, cardRow(rice, JournalDraft(grams = "200"), day, ::waterRow, ::cupRow))
        assertTrue(rice.addable(JournalDraft()))
        val top = searchTopFoods("rice", limit = 1).single()
        val picked =
            ChatFoodDraft(FoodState(serving = "200").withJournalCatalogFood(top, java.util.Locale.ENGLISH), top.id)
        val row = checkNotNull(cardRow(rice, JournalDraft(food = picked, grams = "150"), day, ::waterRow, ::cupRow))
        assertEquals("EAT", row.kind)
        assertEquals(150.0, row.servingG, 0.001)
        assertEquals(top.nutrition.kcal * 1.5, row.calories, 0.5)
        assertEquals("${top.id}=150.0", row.foodSource)
        assertEquals(meal, row.note)
        assertEquals(day.toString(), row.day)

        val weight = proposal(ChatJournalType.WEIGHT_LOG, "I weigh 70 kg", "weigh").copy(sourceText = "I weigh 70 kg")
        assertEquals(null, cardRow(weight, JournalDraft(), day, ::waterRow, ::cupRow))
    }

    private val noWater: (Int, String) -> JournalEntry = { _, _ -> error("a cup is no water") }

    private fun cupRow(drink: CaffeineDrink, ml: Int, onDay: String) = cupEntry(drink.name, drink, ml, onDay, 0L)

    /** A caffeine record as the shipped reader reads «выпил 200 мл капучино»: the drink's words and the amount. */
    private fun cup(text: String, drink: String, amount: String? = null): ChatJournalEvent {
        fun span(words: String) = ChatJournalSpan(text.indexOf(words), text.indexOf(words) + words.length, words)
        return ChatJournalEvent(
            ChatJournalType.CAFFEINE_LOG,
            span(drink),
            mapOf("subject" to "SELF", "assertion" to "AFFIRMED"),
            listOfNotNull(
                amount?.let {
                    ChatJournalArgument("AMOUNT", span(it))
                },
                ChatJournalArgument("DRINK_KIND", span(drink)),
            ),
            sourceText = text,
        )
    }

    /** 200 ml of cappuccino the chat read is saved at that amount, never picked again at 100/300/500. */
    @Test
    fun aCupTheWordsNameIsSavedFromTheCardAtTheMillilitresSaid() {
        val day = LocalDate.of(2026, 10, 13)
        val cappuccino = cup("выпил 200 мл капучино", "капучино", "200 мл")
        assertEquals(CaffeineDrink.CAPPUCCINO, cappuccino.prefillDrink())
        val row = checkNotNull(cardRow(cappuccino, JournalDraft(), day, noWater, ::cupRow))
        assertEquals("EAT", row.kind)
        assertEquals(200.0, row.servingG, 0.0)
        assertEquals(54.0, row.calories, 0.5)
        assertEquals(80.0, row.caffeineMg)
        assertEquals(day, journalDay(cappuccino, JournalDraft(), day, day))
        assertEquals(250.0, cardRow(cappuccino, JournalDraft(millilitres = "250"), day, noWater, ::cupRow)?.servingG)
        assertTrue(!cappuccino.addable(JournalDraft(millilitres = "1200")))

        // No amount: the standard cup the dialog starts on; a black cup keeps its milligrams.
        val coffee = checkNotNull(cardRow(cup("выпил чашку кофе", "кофе"), JournalDraft(), day, noWater, ::cupRow))
        assertEquals(KIND_CAFFEINE, coffee.kind)
        assertEquals(135.0, coffee.servingG, 0.0)
        assertEquals(CaffeineDrink.TEA, cup("выпил 300 мл чая", "чая", "300 мл").prefillDrink())
        assertEquals(CaffeineDrink.ESPRESSO, cup("我喝了一杯浓缩咖啡", "浓缩咖啡").prefillDrink())
        assertEquals(CaffeineDrink.MATCHA, cup("抹茶を飲んだ", "抹茶").prefillDrink())
        assertEquals(CaffeineDrink.COFFEE, cup("I had a cup of coffee", "coffee").prefillDrink())

        // A drink the dialog has none of, or milk in a black cup: the dialog opens with no drink chosen.
        listOf(cup("выпил латте 250 мл", "латте", "250 мл"), cup("выпил кофе с молоком", "кофе")).forEach {
            assertEquals(null, it.prefillDrink())
            assertEquals(null, cardRow(it, JournalDraft(), day, noWater, ::cupRow))
            assertTrue(it.addable(JournalDraft()))
        }
    }

    @Test
    fun theDayIsTheCardsOnlyWhereItsRecordKeepsItAndNeverAhead() {
        val today = LocalDate.of(2026, 10, 12)
        val text = "yesterday I drank 250 ml of water"
        val water = proposal(ChatJournalType.WATER_LOG, text, "water")
            .copy(arguments = listOf(ChatJournalArgument("TIME", ChatJournalSpan(0, 9, "yesterday"))))
        assertEquals(today.minusDays(1), journalDay(water, JournalDraft(), today, today))
        assertEquals(today.minusDays(5), journalDay(water, JournalDraft(day = today.minusDays(5)), today, today))
        assertEquals(today, journalDay(water, JournalDraft(day = today.plusDays(2)), today, today))
        val coffee = proposal(ChatJournalType.CAFFEINE_LOG, "coffee", "coffee")
        assertEquals(null, journalDay(coffee, JournalDraft(day = today.minusDays(1)), today, today))
    }

    /**
     * A chat being had counts a card's day from today, whatever day its page was begun on: counted from
     * the page's day, one begun before midnight and gone on with after it would file "today I drank…"
     * under yesterday and "yesterday" under the day before, while its threads' own records go by the
     * moment. A chat for an earlier day, and one read back, count from their own day still.
     */
    @Test
    fun aCardInAChatHadOverMidnightCountsItsDayFromToday() {
        val today = LocalDate.of(2026, 10, 12)
        val begun = today.minusDays(1)
        fun water(time: String?): ChatJournalEvent {
            val text = listOfNotNull(time, "I drank 300 ml of water").joinToString(" ")
            val said = time?.let { ChatJournalArgument("TIME", ChatJournalSpan(0, it.length, it)) }
            return proposal(ChatJournalType.WATER_LOG, text, "water").copy(arguments = listOfNotNull(said))
        }
        val live = cardBaseDay(past = false, chatDay = begun, today = today)
        assertEquals(today, journalDay(water("today"), JournalDraft(), live, today))
        assertEquals(begun, journalDay(water("yesterday"), JournalDraft(), live, today))
        assertEquals(today, journalDay(water(null), JournalDraft(), live, today))
        val earlier = cardBaseDay(past = true, chatDay = begun, today = today)
        assertEquals(begun, journalDay(water("today"), JournalDraft(), earlier, today))
        assertEquals(begun.minusDays(1), journalDay(water("yesterday"), JournalDraft(), earlier, today))
        assertEquals(begun, journalDay(water(null), JournalDraft(), earlier, today))
    }

    /**
     * The card and the two acts that file from it — Add, and the form it opens — count from the one
     * base, pinned in the source as [ChatGestureWiringTest]'s calls are: with the helper alone held on
     * the JVM, a caller still counting from the page's day would show one day and file under another.
     */
    @Test
    fun theCardAndWhatFilesFromItCountFromOneBase() {
        fun source(file: String) = File("src/main/java/com/pwnall/health/$file").readText()
        val card = source("ChatJournalOffers.kt").substringAfter("fun JournalCard(").substringBefore("\n}\n")
        assertTrue(card, "cardBaseDay(inputs.setting.past || inputs.offers == null, " in card)
        val screen = source("SymptomChatScreen.kt")
        assertFalse("journalDay(card.event, draft, chatDay, today)" in screen)
        assertFalse("sourceDay = chatDayText" in screen)
        assertEquals(2, screen.split("val base = cardBaseDay(log.past, chatDay, today)").size - 1)
    }

    @Test
    fun aCardsDraftSurvivesTheBundle() {
        val top = searchTopFoods("banana", limit = 1).single()
        val food =
            ChatFoodDraft(FoodState(serving = "120").withJournalCatalogFood(top, java.util.Locale.ENGLISH), top.id)
        val drafts = mapOf(
            "0:0:WATER_LOG:0:6" to JournalDraft(day = LocalDate.of(2026, 10, 1), millilitres = "330"),
            "0:1:FOOD_LOG:2:8" to JournalDraft(grams = "120", query = "банан", food = food),
        )
        assertEquals(drafts, decodeJournalDrafts(encodeJournalDrafts(drafts)))
        assertEquals(emptyMap<String, JournalDraft>(), decodeJournalDrafts("not json"))
    }

    @Test
    fun everyRecordTypeHasItsOwnNameOnItsCard() {
        ChatJournalType.entries.forEach { assertNotEquals(it.name, R.string.editor_new_title, it.titleRes) }
    }

    @Test
    fun aCardNamesTheWordsItWasReadFrom() {
        val text = "Утром выпил 200 мл воды, потом кофе"
        val water = proposal(ChatJournalType.WATER_LOG, text, "воды")
            .copy(arguments = listOf(ChatJournalArgument("AMOUNT", ChatJournalSpan(12, 18, "200 мл"))))
        assertEquals("200 мл воды", water.copy(sourceText = text).phrase())
        assertEquals(null, water.phrase())
    }
}
