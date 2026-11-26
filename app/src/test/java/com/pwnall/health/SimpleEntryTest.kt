package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reopening a one-tap row has to land on the option it was logged with, and the match is made on
 * a number rather than on a word. That is the fragile part, so it is the part with a test.
 */
class SimpleEntryTest {
    private fun row(kind: String, servingG: Double = 0.0, subtitle: String = "", mood: Int? = null) =
        JournalEntry(
            day = "2026-08-10",
            occurredAt = 1_754_800_000_000,
            kind = kind,
            title = kind,
            subtitle = subtitle,
            calories = 0.0,
            servingG = servingG,
            moodScore = mood,
        )

    /**
     * The whole reason alcohol and tobacco are matched by grams: the subtitle holds a translated
     * word, so a beer logged in Russian and reopened in German would match nothing. Two drinks
     * sharing a gram figure would break that silently rather than fail to compile.
     */
    @Test
    fun everyDrinkAndEveryTobaccoProductHasItsOwnGramFigure() {
        val drinks = AlcoholDrink.entries.map { it.ethanolG }
        assertEquals(drinks.size, drinks.distinct().size)
        val tobacco = TobaccoProduct.entries.map { it.tobaccoG }
        assertEquals(tobacco.size, tobacco.distinct().size)
    }

    /** A stored row resolves back to the option it was logged as, for every option there is. */
    @Test
    fun everyOptionIsRecognisedBackFromWhatWasStored() {
        AlcoholDrink.entries.forEach {
            assertEquals(it.name, simpleValueOf(row(KIND_ALCOHOL, servingG = it.ethanolG)))
        }
        TobaccoProduct.entries.forEach {
            assertEquals(it.name, simpleValueOf(row(KIND_SMOKING, servingG = it.tobaccoG)))
        }
        StoolKind.entries.forEach {
            assertEquals(it.name, simpleValueOf(row(KIND_STOOL, subtitle = it.name)))
        }
        Mood.entries.forEach {
            assertEquals(it.name, simpleValueOf(row(KIND_MOOD, mood = it.score)))
        }
        MorningErection.entries.forEach {
            assertEquals(it.name, simpleValueOf(morning(it)))
        }
        // Water is its own number rather than a named option.
        assertEquals("330", simpleValueOf(row(KIND_WATER, servingG = 330.0)))
    }

    /**
     * The kinds with nothing to choose must answer "" — that empty string is what leaves the Save
     * button off and puts the read-only card up instead of a form.
     */
    @Test
    fun aRowWithNothingToChooseOffersNothing() {
        listOf(
            KIND_URINATION, KIND_BREATHING, KIND_LABS, KIND_PLAN, KIND_NOT_DONE, KIND_MASTURBATION,
            KIND_MED, KIND_WORK, KIND_PAIN, KIND_SYMPTOM, "EAT",
        )
            .forEach { assertEquals("", simpleValueOf(row(it))) }
        assertEquals("", simpleValueOf(null))
        // A row from a build that knew a drink this one does not: no match, no false confidence.
        assertEquals("", simpleValueOf(row(KIND_ALCOHOL, servingG = 999.0)))
    }

    /**
     * A pour of its own size matches no chip, and a chip tapped over it would rebuild the row at the
     * drink's standard pour. Such a row is not offered the chips at all; a standard one still is.
     */
    @Test
    fun aDrinkPouredToItsOwnSizeIsNotOfferedTheStandardPours() {
        val bottle = row(KIND_ALCOHOL, servingG = AlcoholDrink.BEER.pouredG(ml = 330, abvTenths = 50))
        val standard = row(KIND_ALCOHOL, servingG = AlcoholDrink.BEER.pouredG(AlcoholDrink.BEER.ml, 50))

        assertFalse(rechoosable(bottle))
        assertTrue(rechoosable(standard))
        assertTrue(rechoosable(row(KIND_WATER, servingG = 275.0)))
        assertFalse(rechoosable(row(KIND_URINATION)))
        assertFalse(rechoosable(null))
    }

    /** Every option a chooser offers must be one [simpleValueOf] can produce, or a chip is dead. */
    @Test
    fun theWaterPortionsOfferedAreTheOnesStored() {
        assertTrue(WATER_PORTIONS_ML.isNotEmpty())
        WATER_PORTIONS_ML.forEach {
            assertEquals(it.toString(), simpleValueOf(row(KIND_WATER, servingG = it.toDouble())))
        }
    }

    /**
     * Every product has to be reachable from the smoking screen exactly once. A variant added to
     * [TobaccoProduct] and forgotten in both [SINGLE_TOBACCO] and every [TobaccoGroup] would sit in
     * the data with no tile that can log it, and one listed twice would be offered twice.
     */
    @Test
    fun everyTobaccoProductIsOfferedBySomethingOnTheSmokingScreenExactlyOnce() {
        val offered = SINGLE_TOBACCO + TobaccoGroup.entries.flatMap(TobaccoGroup::members)

        assertEquals(offered.size, offered.distinct().size)
        assertEquals(TobaccoProduct.entries.toSet(), offered.toSet())
    }

    /**
     * The chooser rebuilds a row through its factory, and the bathroom factory takes its symptoms as
     * an argument the chooser does not have. The rebuilt row therefore inherits whatever the factory
     * left empty — a stool corrected from hard to loose keeps the blood it was logged with — while
     * a value the factory did set, the corrected mood's own score, is the one that stands.
     */
    @Test
    fun aRebuiltRowKeepsWhatItsFactoryCouldNotKnow() {
        val previous = row(KIND_STOOL, subtitle = "HARD", mood = 2).copy(
            id = 41,
            note = "after lunch",
            healthConnectRecordKey = "Health Connect:com.other:abc",
            measurementSource = "HEALTH_CONNECT",
            measurementPayloadJson = """{"notes":["BLOOD","PAIN"]}""",
            caffeineMg = 80.0,
        )
        val fresh = row(KIND_STOOL, subtitle = "LOOSE", mood = 4).copy(occurredAt = 1L)

        val rebuilt = fresh.rebuiltFrom(previous)

        assertEquals(41L, rebuilt.id)
        assertEquals(previous.occurredAt, rebuilt.occurredAt)
        assertEquals("LOOSE", rebuilt.subtitle)
        assertEquals("after lunch", rebuilt.note)
        assertEquals("Health Connect:com.other:abc", rebuilt.healthConnectRecordKey)
        assertEquals("HEALTH_CONNECT", rebuilt.measurementSource)
        assertEquals("""{"notes":["BLOOD","PAIN"]}""", rebuilt.measurementPayloadJson)
        assertEquals(80.0, rebuilt.caffeineMg!!, 0.0)
        assertEquals(4, rebuilt.moodScore)
    }

    /**
     * A morning re-answered from the calendar keeps its waking, where the waking came from and what
     * ended the night — none of which the chooser offers — and keeps its id and moment.
     */
    @Test
    fun aMorningReansweredKeepsItsWaking() {
        val previous = morning(MorningErection.FIRM).copy(id = 7)

        val rebuilt = rebuiltMorningCheck("Morning", previous, MorningErection.NONE, WOKE_DAY, SAVED_AT)!!
            .rebuiltFrom(previous)

        assertEquals(7L, rebuilt.id)
        assertEquals(WOKE_AT, rebuilt.occurredAt)
        assertEquals("2026-08-10", rebuilt.day)
        assertEquals(
            MorningCheck(MorningErection.NONE, WOKE_AT, WakeSource.SLEEP, WokeBy.ALARM, answeredAt = SAVED_AT),
            morningCheckOf(rebuilt.measurementPayloadJson),
        )
    }

    /** An answer changed after its day is one given from memory; saving the same answer again is not. */
    @Test
    fun aMorningReansweredAfterItsDayIsMarkedLater() {
        val previous = morning(MorningErection.FIRM)
        val nextDay = WOKE_DAY.plusDays(1)

        val changed = rebuiltMorningCheck("Morning", previous, MorningErection.PARTLY, nextDay, SAVED_AT)!!
        assertTrue(morningCheckOf(changed.measurementPayloadJson)!!.later)
        val kept = rebuiltMorningCheck("Morning", previous, MorningErection.FIRM, nextDay, SAVED_AT)!!
        assertFalse(morningCheckOf(kept.measurementPayloadJson)!!.later)
        // Once later, always later.
        val again = rebuiltMorningCheck("Morning", changed, MorningErection.PARTLY, WOKE_DAY, SAVED_AT)!!
        assertTrue(morningCheckOf(again.measurementPayloadJson)!!.later)
    }

    /**
     * A face corrected in the editor keeps the tension the chat asked, in its column and as the note's
     * second word, and what the mood was about; its identity and moment stay too.
     */
    @Test
    fun aCorrectedChatMoodKeepsItsEnergy() {
        val payload = encodeMoodPayload(listOf(SymptomOption.FAMILY))
        val chat = moodEntry(::stringName, Mood.LOW, Energy.WOUND_UP, WOKE_DAY, at = SAVED_AT, payload = payload)
            .copy(id = 12)
        val corrected = checkNotNull(rebuiltSimpleEntry(NamedStrings(), chat, Mood.GOOD.name))
        assertEquals(Mood.GOOD.score, corrected.moodScore)
        assertEquals(Energy.WOUND_UP.score, corrected.energyScore)
        assertEquals("mood_good, energy_wound_up", corrected.note)
        assertEquals(payload, corrected.measurementPayloadJson)
        assertEquals(12L, corrected.id)
        assertEquals(SAVED_AT, corrected.occurredAt)
        // A mood logged with no tension gets none from the correction.
        val plain = moodEntry(::stringName, Mood.NEUTRAL, null, WOKE_DAY, at = SAVED_AT).copy(id = 13)
        val fixed = checkNotNull(rebuiltSimpleEntry(NamedStrings(), plain, Mood.BAD.name))
        assertEquals(null, fixed.energyScore)
        assertEquals("mood_bad", fixed.note)
    }

    /** A morning answered with a word this build does not know offers no option, so Save stays dark. */
    @Test
    fun aMorningFromANewerBuildOffersNoOption() {
        val newer = morning(MorningErection.FIRM).let {
            it.copy(measurementPayloadJson = it.measurementPayloadJson!!.replace("FIRM", "SOMETHING_NEW"))
        }
        assertEquals("", simpleValueOf(newer))
    }

    private fun morning(answer: MorningErection) = morningCheckEntry(
        "Morning",
        WOKE_DAY,
        MorningCheck(answer, WOKE_AT, WakeSource.SLEEP, WokeBy.ALARM),
    )

    private companion object {
        val WOKE_DAY: LocalDate = LocalDate.of(2026, 8, 10)
        val WOKE_AT: Long = WOKE_DAY.atTime(7, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val SAVED_AT: Long = WOKE_DAY.atTime(21, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
