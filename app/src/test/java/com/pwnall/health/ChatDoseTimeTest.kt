package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Twelve-hour clocks require confirmation; a confirmed relative date survives filing and replay. What
 * is no time to confirm — "today", a count, the end of a longer word — asks nothing; a dose keeps the
 * day it was chosen on past midnight; and words of a dose missed are never noted as one taken.
 */
class ChatDoseTimeTest {
    @After
    fun resetClock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    @Test
    fun aBareClockOffersBothHalvesOfTheDay() {
        for (text in listOf("I took paracetamol at 8", "приняла парацетамол в 8", "at 8:30", "в 8.30")) {
            val minutes = if ('3' in text) listOf(510, 1230) else listOf(480, 1200)
            val timing = doseTimingIn(text, CHAT_TODAY)
            assertEquals(text, minutes, timing.minutes)
            assertTrue(text, timing.ambiguous)
        }
        assertEquals(listOf(0, 720), doseTimingIn("at 12", CHAT_TODAY).minutes)
    }

    @Test
    fun explicitPeriodsAndTwentyFourHourClocksHaveOneInterpretation() {
        val cases = mapOf(
            "at 8 am" to 480,
            "at 8 p.m." to 1200,
            "at 8:30 PM" to 1230,
            "в 8 утра" to 480,
            "в 8 вечера" to 1200,
            "утром в 8" to 480,
            "вечером в 8" to 1200,
            "в 20:15" to 1215,
            "в 08:00" to 480,
            "at 12 am" to 0,
            "at 12 pm" to 720,
        )
        cases.forEach { (text, minute) ->
            val timing = doseTimingIn(text, CHAT_TODAY)
            assertEquals(text, listOf(minute), timing.minutes)
            assertFalse(text, timing.ambiguous)
        }
    }

    @Test
    fun quantitiesAndInvalidClocksAreNotClockProposals() {
        for (text in listOf("8 tablets", "8 mg", "в 8 мг", "2.50 mg", "at 25", "at 8:75", "6/10", "500 mg")) {
            assertTrue(text, doseTimingIn(text, CHAT_TODAY).minutes.isEmpty())
        }
        assertTrue(doseTimingIn("8", CHAT_TODAY).minutes.isEmpty())
        assertEquals(listOf(480, 1200), doseTimingIn("8", CHAT_TODAY, bare = true).minutes)
    }

    /**
     * Every unit the dose reader takes an amount by makes the number before it an amount here as well: were a
     * number with minutes behind its point a time unless a unit said otherwise, "2.50 IU" would be ten to three,
     * "0.25 of a tablet" a quarter past midnight and "2.30 pills" half past two.
     */
    @Test
    fun anAmountInAUnitOfADoseIsNoClock() {
        val units = listOf(
            "mg", "мг", "mcg", "мкг", "µg", "g", "г", "ml", "мл", "IU", "МЕ", "units", "ед",
            "tablets", "таблетки", "capsules", "капсулы", "drops", "капли", "капель",
        )
        for (unit in units) {
            val text = "2.50 $unit"
            assertEquals(text, listOf(text), doseTextsIn(text))
            assertTrue(text, doseTimingIn(text, CHAT_TODAY).minutes.isEmpty())
        }
        for (text in listOf("0.25 of a tablet", "2.30 pills", "1.30 шт", "2.30 штуки")) {
            assertTrue(text, doseTimingIn(text, CHAT_TODAY).minutes.isEmpty())
        }
        // A word that only begins like a unit is none: «еду» is no «ед».
        assertEquals(listOf(510, 1230), doseTimingIn("в 8.30 еду", CHAT_TODAY).minutes)
    }

    @Test
    fun yesterdayAndClockPeriodsAlsoWorkAcrossOtherScripts() {
        val yesterday = CHAT_TODAY.minusDays(1)
        for (text in listOf("昨天晚上8点", "昨日午後8時", "어제 오후8시", "gestern um 8 PM")) {
            val timing = doseTimingIn(text, CHAT_TODAY)
            assertEquals(text, yesterday, timing.day)
            assertEquals(text, listOf(1200), timing.minutes)
        }
    }

    /**
     * A clock or a dose typed in the digits of the keyboard's script reads as its ASCII one, and a
     * no-break space between a figure and its unit as a space. The patterns say [0-9] and [SPACE],
     * which the phone and these tests read alike; the short classes would read «٨» and the no-break
     * space on the phone alone, where nothing here could see what was done with them.
     */
    @Test
    fun digitsOfAnotherScriptAndANoBreakSpaceAreReadAsTheTestsReadThem() {
        assertEquals(listOf(1200), doseTimingIn("في ٨ مساءً", CHAT_TODAY).minutes)
        assertEquals(listOf(8 * 60 + 30), clockMinutesIn("८:३०"))
        assertEquals(listOf("٥٠ mg"), doseTextsIn("٥٠ mg"))
        assertEquals(listOf("50 mg"), doseTextsIn("50 mg"))
    }

    @Test
    fun ambiguousClockChipsAskRatherThanRecordBeforeConfirmation() {
        val chat = oneOff("I took paracetamol at 8")
        assertEquals(SymptomStep.MED_WHEN, chat.step)
        assertEquals(R.string.med_chat_ask_when_am_pm, DoseScript.question(chat.state, SymptomStep.MED_WHEN))
        assertEquals(listOf("480", "1200"), timeChips(chat))
        assertTrue(filed(chat).isEmpty())
        chat.value("480").value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        assertEquals(todayAt(480), filed(chat).single().occurredAt)
    }

    @Test
    fun yesterdaysMorningAndEveningFileOnYesterdayWithTheChosenTime() {
        val yesterday = CHAT_TODAY.minusDays(1)
        for (minute in listOf(480, 1200)) {
            val chat = oneOff("вчера приняла парацетамол в 8")
            assertEquals(listOf("$yesterday@480", "$yesterday@1200"), timeChips(chat))
            assertEquals(R.string.med_chat_ask_when_am_pm, DoseScript.question(chat.state, SymptomStep.MED_WHEN))
            assertTrue(filed(chat).isEmpty())
            chat.value("$yesterday@$minute").value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
            val row = filed(chat).single()
            assertEquals(yesterday.toString(), row.day)
            assertEquals(todayAt(minute, yesterday), row.occurredAt)
            val saved = checkNotNull(decodeChatLog(encodeChatLog(chat.log, pending = false)))
            assertEquals(chatTurn(chat.log, chat.setting), chatTurn(saved, chat.setting))
        }
    }

    @Test
    fun anExplicitPmIsProposedOnceAndYesterdayIsRetainedByThePicker() {
        val yesterday = CHAT_TODAY.minusDays(1)
        val chat = oneOff("I took paracetamol yesterday at 8 PM")
        assertEquals(listOf("$yesterday@1200"), timeChips(chat))
        assertEquals(R.string.med_chat_ask_when, DoseScript.question(chat.state, SymptomStep.MED_WHEN))
        val choose = (chat.reply as ChatReply.Values).choices.single { it.picker == ValuePicker.CLOCK }
        chat.value(pickedDoseClock(choose.value, 1230)).value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        assertEquals(todayAt(1230, yesterday), filed(chat).single().occurredAt)
    }

    @Test
    fun choosingTodaysFutureEveningAsksAgainInsteadOfFilingAtAnotherTime() {
        val chat = oneOff("приняла парацетамол в 8")
        chat.value("1200")
        assertEquals(SymptomStep.MED_WHEN_AGAIN, chat.step)
        assertTrue(filed(chat).isEmpty())
        chat.value("480").value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        assertEquals(todayAt(480), filed(chat).single().occurredAt)
    }

    /**
     * Told "that time has not come yet today", a person who gives a dose of no course the same time once
     * more took it last night: 23:40 picked twice at twenty past midnight — the picker has no yesterday to
     * offer — is filed then, never at the minute it was said.
     */
    @Test
    fun aTimeStillToComeGivenAgainIsLastNights() {
        val chat = MedicineChat().apply { now = todayAt(20) }.start(Symptom.DOSE)
        chat.type("paracetamol", SymptomStep.MED_NAME).value("1420", SymptomStep.MED_WHEN)
        assertEquals(SymptomStep.MED_WHEN_AGAIN, chat.step)
        chat.value("1420", SymptomStep.MED_WHEN_AGAIN).value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        assertNull(chat.step)
        val yesterday = CHAT_TODAY.minusDays(1)
        val row = filed(chat).single()
        assertEquals(yesterday.toString() to todayAt(23 * 60 + 40, yesterday), row.day to row.occurredAt)
    }

    @Test
    fun aScheduledDoseDoesNotSkipConfirmationOfClockWords() {
        val med = course("paracetamol", times = "480")
        val chat = MedicineChat(ChatEnv(courses = listOf(med))).apply { now = todayAt(490) }
        chat.write("I took paracetamol at 8")
        if (chat.step == SymptomStep.MED_WHICH) chat.value("C${med.createdAt}@480")
        assertEquals(SymptomStep.MED_WHEN, chat.step)
        assertEquals(listOf("480", "1200"), timeChips(chat))
        assertTrue(filed(chat).isEmpty())
    }

    @Test
    fun unknownTimeKeepsYesterdaysDateAndTheNoonPickerHasItsOwnValue() {
        val yesterday = CHAT_TODAY.minusDays(1)
        val chat = oneOff("I took paracetamol yesterday at 12")
        val choices = (chat.reply as ChatReply.Values).choices
        assertEquals(choices.size, choices.map { it.value }.distinct().size)
        val picker = choices.single { it.picker == ValuePicker.CLOCK }
        assertEquals(720, doseClockMinute(picker.value))
        assertEquals("$yesterday@480", pickedDoseClock(picker.value, 480))
        chat.value("$yesterday@UNKNOWN").value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        assertEquals(yesterday.toString(), filed(chat).single().day)
    }

    /**
     * "I do not remember" and 12:00 chosen stand on one moment, the day's midday, so the moment alone
     * cannot tell the card which to say. The row says which: only the one whose time was not said carries
     * the flag the card and [untimedAct] read.
     */
    @Test
    fun aTimeNotSaidIsSaidInTheRowAndNoonChosenIsNot() {
        val yesterday = CHAT_TODAY.minusDays(1)
        fun row(answer: String): JournalEntry {
            val chat = oneOff("I took paracetamol yesterday at 12")
            chat.value(answer).value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
            return filed(chat).single()
        }

        val unsaid = row("$yesterday@UNKNOWN")
        val noon = row("$yesterday@720")

        assertEquals(middayOf(yesterday, CHAT_ZONE), unsaid.occurredAt)
        assertEquals(unsaid.occurredAt, noon.occurredAt)
        assertTrue(statesNoTime(unsaid))
        assertFalse(statesNoTime(noon))
        assertTrue(untimedAct(unsaid.copy(occurredAt = unsaid.occurredAt + 1), CHAT_ZONE))
        assertEquals(MED_ONE_OFF_PREFIX + unsaid.occurredAt, medSlotOf(unsaid)?.second)
        // The card says the day alone for a dose of the schedule too: "marked at 12:00" would be a time nobody gave.
        assertEquals(R.string.med_chat_saved_one_off_day, doseLineRes(null, unsaid = true))
        assertEquals(R.string.med_chat_saved_one_off, doseLineRes(null, unsaid = false))
        assertEquals(R.string.med_chat_saved_scheduled_day, doseLineRes("540", unsaid = true))
        assertEquals(R.string.med_chat_saved_scheduled_wake_day, doseLineRes(MED_SLOT_WAKE, unsaid = true))
        assertEquals(R.string.med_chat_saved_scheduled_wake, doseLineRes(MED_SLOT_WAKE, unsaid = false))
    }

    @Test
    fun aClockCannotHideAnActualTabletCount() {
        for (text in listOf("I took 8 tablets at 8", "выпила 8 таблеток в 8", "I took 20 paracetamol at 8 PM")) {
            assertTrue(text, overdoseIn(text))
        }
    }

    @Test
    fun aPastDayAlsoOffersClockEntitiesWithoutAnExactRuleMatch() {
        val chat = MedicineChat(past = true).start(Symptom.DOSE)
        val read = ChatRead(model = "clock-fixture", told = ReadTold(clocks = listOf(480, 1200)))
        chat.log = chat.log.wroteRead("I took paracetamol", read).settle(chat.setting)
        if (chat.step == SymptomStep.MED_NAME) chat.type("paracetamol")
        assertEquals(SymptomStep.MED_WHEN, chat.step)
        assertEquals(listOf("480", "1200"), timeChips(chat))
        chat.value("1200").value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        val at = Instant.ofEpochMilli(filed(chat).single().occurredAt).atZone(CHAT_ZONE)
        assertEquals(chat.setting.day, at.toLocalDate())
        assertEquals(20, at.hour)
        assertEquals(0, at.minute)
    }

    @Test
    fun aConfirmedEightRemainsEightOnADayWithAnOffsetChange() {
        val zone = ZoneId.of("Europe/Berlin")
        for (day in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            val at = Instant.ofEpochMilli(doseClockMoment(day, 480, zone)).atZone(zone)
            assertEquals(day, at.toLocalDate())
            assertEquals(8, at.hour)
            assertEquals(0, at.minute)
        }
    }

    /** "Today" is the day every question here already means: it neither asks when nor takes "just now" away. */
    @Test
    fun todayAloneIsNoTimeToConfirm() {
        val chat = oneOff("I took paracetamol today")
        assertEquals(SymptomStep.MED_WHEN, chat.step)
        val choices = (chat.reply as ChatReply.Values).choices
        assertEquals("NOW", choices.first().value)
        assertTrue(choices.none { it.accent })
        // Yesterday is another day: its times and the clock, never "just now".
        val yesterday = (oneOff("I took paracetamol yesterday").reply as ChatReply.Values).choices
        assertTrue(yesterday.none { it.value == "NOW" })
        // A course's dose in time is still marked with the one tap.
        val med = course("paracetamol", times = "480")
        val scheduled = MedicineChat(ChatEnv(courses = listOf(med))).apply { now = todayAt(490) }
        scheduled.write("I took paracetamol today")
        if (scheduled.step == SymptomStep.MED_WHICH) scheduled.value("C${med.createdAt}@480")
        assertNull(scheduled.step)
    }

    @Test
    fun countsAndTheEndsOfLongerWordsAreNoClockWords() {
        val counts = listOf("выпила в 2 раза больше", "приняла в 3 приёма", "took it at 2 times the dose", "as 2 doses")
        for (text in counts) {
            assertTrue(text, doseTimingIn(text, CHAT_TODAY).minutes.isEmpty())
        }
        // «сегодня» ends in «дня» and "citalopram" in "am": neither says which half of the day it was.
        for (text in listOf("сегодня в 8", "took citalopram at 8")) {
            val timing = doseTimingIn(text, CHAT_TODAY)
            assertEquals(text, listOf(480, 1200), timing.minutes)
            assertTrue(text, timing.ambiguous)
        }
        assertEquals(listOf(1200), doseTimingIn("сегодня в 8 вечера", CHAT_TODAY).minutes)
    }

    /** Russian «дня» is the daytime: eleven of it is the morning's, two the afternoon's, twelve noon. */
    @Test
    fun russianDaytimeHoursBeforeNoonAreTheMornings() {
        val cases = mapOf("в 11 дня" to 660, "днём в 11" to 660, "в 2 дня" to 840, "в 12 дня" to 720, "днем в 3" to 900)
        cases.forEach { (text, minute) ->
            assertEquals(text, listOf(minute), doseTimingIn(text, CHAT_TODAY).minutes)
        }
    }

    /**
     * «3 дня» with no word of time before it is three days. Read as 15:00 it would date a dose nobody timed; behind
     * «в», «около» or «к» it is the afternoon still, and a count of days gives the clock after it no half.
     */
    @Test
    fun aCountOfDaysIsNoTimeOfDay() {
        for (text in listOf("пью нурофен 3 дня", "принимаю уже 2 дня", "болит 4 дня", "пила на 3 дня дольше")) {
            assertTrue(text, doseTimingIn(text, CHAT_TODAY).minutes.isEmpty())
        }
        val times = mapOf("выпила в 3 дня" to 900, "около 3 дня" to 900, "к 2 дня" to 840, "выпила 2:30 дня" to 870)
        times.forEach { (text, minute) ->
            assertEquals(text, listOf(minute), doseTimingIn(text, CHAT_TODAY).minutes)
        }
        val after = doseTimingIn("пью нурофен 3 дня в 8", CHAT_TODAY)
        assertEquals(listOf(480, 1200), after.minutes)
        assertTrue(after.ambiguous)
    }

    /**
     * A thread carried on past midnight marks the dose it asked about. Chosen at ten to midnight and timed
     * five minutes into the next day, the 21:00 dose is still that evening's: under the new date it would
     * mark the coming evening's dose taken, and that one's reminder would never come.
     */
    @Test
    fun aCoursesDoseChosenBeforeMidnightStaysThatDaysWhenTimedAfterIt() {
        val med = course("Vitamin D", times = "1260")
        val chat = MedicineChat(ChatEnv(courses = listOf(med))).apply { now = todayAt(23 * 60 + 50) }
        chat.start(Symptom.DOSE).value("C${med.createdAt}@1260", SymptomStep.MED_WHICH)
        val tomorrow = CHAT_TODAY.plusDays(1)
        chat.now = todayAt(5, tomorrow)
        chat.value("SLOT", SymptomStep.MED_WHEN)
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "clock"), "clock", today = tomorrow)
        val row = store.db.journal.values.single()
        assertEquals(CHAT_TODAY.toString() to todayAt(21 * 60), row.day to row.occurredAt)
    }

    /**
     * The time of such a dose chosen on the clock after midnight is the new day's once it has come there: 00:03
     * picked at five past is three minutes old, not a day and three minutes. One still ahead on the new date is
     * the evening's, and the dose marked is the evening's either way.
     */
    @Test
    fun aTimeChosenAfterMidnightThatHasComeIsTheNewDays() {
        val tomorrow = CHAT_TODAY.plusDays(1)
        val cases = mapOf(3 to todayAt(3, tomorrow), 23 * 60 + 40 to todayAt(23 * 60 + 40))
        cases.forEach { (minute, moment) ->
            val med = course("Vitamin D", times = "1260")
            val chat = MedicineChat(ChatEnv(courses = listOf(med))).apply { now = todayAt(23 * 60 + 50) }
            chat.start(Symptom.DOSE).value("C${med.createdAt}@1260", SymptomStep.MED_WHICH)
            chat.now = todayAt(5, tomorrow)
            chat.value(minute.toString(), SymptomStep.MED_WHEN)
            val store = MemoryChatStore()
            chat.file(ChatFiling(store, "clock"), "clock", today = tomorrow)
            val row = store.db.journal.values.single()
            assertEquals(CHAT_TODAY.toString() to moment, row.day to row.occurredAt)
        }
    }

    /**
     * A time said with no date stands on the day it was said. «В 8» confirmed as the evening's at ten to midnight
     * and the thread finished after it: under the new date eight in the evening has not come, and the dose would
     * be dated the minute it was filed.
     */
    @Test
    fun aTimeSaidBeforeMidnightStaysThatDaysWhenTheDoseIsFiledAfterIt() {
        val chat = oneOff("приняла парацетамол в 8").apply { now = todayAt(23 * 60 + 50) }
        chat.value("1200", SymptomStep.MED_WHEN)
        val tomorrow = CHAT_TODAY.plusDays(1)
        chat.now = todayAt(3, tomorrow)
        chat.value(AMOUNT_ONE).tap(SymptomOption.FOR_TEMPERATURE)
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "clock"), "clock", today = tomorrow)
        val row = store.db.journal.values.single()
        assertEquals(CHAT_TODAY.toString() to todayAt(20 * 60), row.day to row.occurredAt)
    }

    /**
     * Words of a dose not taken start a dose's thread as words of one taken do. For a medicine of no course
     * that thread would otherwise only note it as taken: "I missed a dose" beside the times ends it with nothing
     * noted.
     */
    @Test
    fun wordsOfADoseMissedOfferTheWayOutThatNotesNothing() {
        val chat = MedicineChat().write("не выпила нурофен")
        if (chat.step == SymptomStep.MED_NAME) chat.type("нурофен")
        assertEquals(SymptomStep.MED_WHEN, chat.step)
        val missed = (chat.reply as ChatReply.Values).choices.last()
        assertEquals("MISSED" to true, missed.value to missed.accent)
        chat.value("MISSED", SymptomStep.MED_WHEN)
        assertNull(chat.step)
        assertTrue(R.string.med_chat_missed_leaflet in chat.turn.notes())
        assertTrue(R.string.med_chat_nothing_new in chat.turn.notes())
        assertTrue(filed(chat).isEmpty())
        // Words of a dose taken offer no such chip.
        assertTrue((oneOff("выпила нурофен").reply as ChatReply.Values).choices.none { it.value == "MISSED" })
    }

    /** The same words of a course's dose never take the one tap that would mark it taken. */
    @Test
    fun wordsOfADoseMissedLeaveNoOneTapChipOnACourse() {
        val med = course("Nurofen", times = "480")
        fun slots(text: String): List<String?> {
            val chat = MedicineChat(ChatEnv(courses = listOf(med))).apply { now = todayAt(490) }.write(text)
            return (chat.reply as ChatReply.MedicinePick).choices.map { it.slot }
        }
        assertEquals(listOf<String?>("480"), slots("выпила нурофен"))
        assertEquals(listOf<String?>(null), slots("не выпила нурофен"))
    }

    private fun oneOff(text: String): MedicineChat = MedicineChat().apply {
        write(text)
        if (step == SymptomStep.MED_NAME) type("paracetamol")
    }

    private fun timeChips(chat: MedicineChat): List<String> = (chat.reply as ChatReply.Values).choices
        .filter { it.accent }.map { it.value }

    private fun filed(chat: MedicineChat): List<JournalEntry> {
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "clock"), "clock")
        return store.db.journal.values.toList()
    }
}
