package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * The edges of the habit counters: where a window starts and stops, what a gram figure is worth,
 * and what the two tick readers do with a payload nobody wrote by hand on purpose.
 *
 * Everything here is one of the three numbers a person is judged by on this screen — grams of
 * ethanol against the weekly line, cigarette equivalents, and a count of acts — or the reading of
 * the payload those numbers are recovered from after a restore. Writing an entry needs a Resources
 * this JVM cannot build; reading is what every screen and every restored backup does.
 */
class HabitsEdgeCasesTest {
    private val today = LocalDate.of(2026, 8, 9)

    private fun row(
        kind: String,
        day: String,
        servingG: Double = 0.0,
        payload: String? = null,
    ) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
        servingG = servingG,
        measurementPayloadJson = payload,
    )

    private fun drink(day: LocalDate, ethanolG: Double = 20.0) =
        row(KIND_ALCOHOL, day.toString(), servingG = ethanolG)

    private fun smoke(day: LocalDate, tobaccoG: Double) =
        row(KIND_SMOKING, day.toString(), servingG = tobaccoG)

    private fun urination(day: LocalDate) = row(KIND_URINATION, day.toString())

    private fun solo(day: LocalDate, payload: String? = null) =
        row(KIND_MASTURBATION, day.toString(), payload = payload)

    private fun cycleLogRow(day: LocalDate, payload: String?) =
        row(KIND_CYCLE_LOG, day.toString(), payload = payload)

    /** A payload written the way the entry factories write one: enum names inside the shared key. */
    private fun ticks(vararg names: String) =
        names.joinToString(",", prefix = """{"$CYCLE_NOTES_KEY":[""", postfix = "]}") { "\"$it\"" }

    /**
     * 100 g of ethanol a week is the line the app reports against, and a day's slip at the edge of
     * the window moves a person across it in either direction. Five 20 g drinks inside the seven
     * days are exactly the line; the sixth, one day older, belongs to last week.
     */
    @Test
    fun theWeekKeepsTheSixthDayBackAndDropsTheSeventh() {
        val inside = listOf(0L, 1L, 3L, 5L, 6L).map { drink(today.minusDays(it)) }
        val lastWeek = drink(today.minusDays(HABIT_WINDOW_DAYS.toLong()))

        val week = habitWeek(inside + lastWeek, today)

        assertEquals(ALCOHOL_WEEKLY_G, week.ethanolG, 1e-9)
        assertEquals(ALCOHOL_WEEKLY_G / STANDARD_DRINK_G, week.standardDrinks, 1e-9)
        assertEquals(5, week.drinkingDays)
    }

    /** Days, not drinks: three beers in one evening are one evening. */
    @Test
    fun theWeeksDrinkingDaysCountDaysRatherThanDrinks() {
        val journal = listOf(drink(today), drink(today), drink(today), drink(today.minusDays(1)))

        val week = habitWeek(journal, today)

        assertEquals(80.0, week.ethanolG, 1e-9)
        assertEquals(2, week.drinkingDays)
    }

    /**
     * The day card reuses [HabitWeek], so its "days" fields are flags smuggled in an Int: two beers
     * in one evening are two drinks and one drinking day, and yesterday's cigarette is not on
     * today's card at all.
     */
    @Test
    fun theDayCardCountsDrinksButOnlyFlagsTheDay() {
        val journal = listOf(
            drink(today),
            drink(today),
            smoke(today.minusDays(1), TobaccoProduct.CIGARETTE.tobaccoG),
        )

        val day = habitDay(journal, today)

        assertEquals(40.0, day.ethanolG, 1e-9)
        assertEquals(1, day.drinkingDays)
        assertEquals(0.0, day.tobaccoG, 1e-9)
        assertEquals(0, day.smokingDays)
    }

    /**
     * Days are stored as strings and matched as strings on the day card while being parsed into
     * dates for the week; a month end, a leap day or a year boundary is where the two
     * representations part company.
     */
    @Test
    fun theWeekIsDateArithmeticAcrossALeapDay() {
        val march = LocalDate.of(2024, 3, 1)
        val journal = listOf(drink(LocalDate.of(2024, 2, 29)), drink(LocalDate.of(2024, 2, 23)))

        val week = habitWeek(journal, march)

        // The window opens on 2024-02-24, so the leap day counts and the 23rd does not.
        assertEquals(20.0, week.ethanolG, 1e-9)
        assertEquals(1, week.drinkingDays)
    }

    /**
     * The day column is free text restored from backups written by older versions and by other
     * locales. One DateTimeParseException escaping any of these readers takes down the habits
     * screen and the doctor's report together.
     */
    @Test
    fun aDayColumnThatIsNotADateIsSkippedRatherThanThrownOn() {
        val broken = listOf("", "not-a-date", "2026-02-30", "20260809", "2026-8-9")
        val journal = broken.flatMap {
            listOf(row(KIND_ALCOHOL, it, servingG = 20.0), row(KIND_URINATION, it), row(KIND_MASTURBATION, it))
        }

        val week = habitWeek(journal, today)

        assertEquals(0.0, week.ethanolG, 1e-9)
        assertEquals(0, week.drinkingDays)
        assertEquals(0, bathroomWeek(journal, today).urinations)
        assertEquals(0, sexualWindow(journal, today, 30).soloActs)
    }

    /**
     * A clock set wrong, or a row restored from a device an hour ahead, files a visit under
     * tomorrow. Tomorrow belongs to no window: the week has an upper end as well as a lower one.
     */
    @Test
    fun tomorrowsVisitBelongsToNoWeek() {
        val journal = listOf(
            urination(today.plusDays(1)),
            urination(today),
            urination(today.minusDays(HABIT_WINDOW_DAYS - 1L)),
            urination(today.minusDays(HABIT_WINDOW_DAYS.toLong())),
        )

        assertEquals(2, bathroomWeek(journal, today).urinations)
        assertEquals(1, bathroomDay(journal, today).urinations)
    }

    /**
     * These four figures are the whole distance between "I had a beer" and the weekly gram total the
     * 100 g line is drawn on, and a digit slip in one of them halves or doubles a person's reported
     * drinking with nothing else in the app to contradict it. The enum's own KDoc names the
     * servings, so each is checked against ml x strength x 0.789 rather than against itself.
     */
    @Test
    fun eachDrinkCarriesTheEthanolItsOwnServingHolds() {
        assertPouredEthanol(AlcoholDrink.BEER, ml = 500.0, strength = 0.05)
        assertPouredEthanol(AlcoholDrink.WINE, ml = 150.0, strength = 0.12)
        assertPouredEthanol(AlcoholDrink.SPIRIT, ml = 50.0, strength = 0.40)
        assertPouredEthanol(AlcoholDrink.SHOT, ml = 40.0, strength = 0.40)
        // A shot is the same spirit in the smaller glass, so it can never carry more than the measure.
        assertTrue(
            "shot ${AlcoholDrink.SHOT.ethanolG} vs spirit ${AlcoholDrink.SPIRIT.ethanolG}",
            AlcoholDrink.SHOT.ethanolG < AlcoholDrink.SPIRIT.ethanolG,
        )
    }

    /** Ethanol is 0.789 g per ml; a serving is within a third of a gram of what it pours. */
    private fun assertPouredEthanol(drink: AlcoholDrink, ml: Double, strength: Double) {
        // The chooser opens on the serving the grams are for, and files those very grams for it.
        assertEquals(drink.name, ml, drink.ml.toDouble(), 0.0)
        assertEquals(drink.name, strength * 1_000, drink.abvTenths.toDouble(), 1e-9)
        assertEquals(drink.name, drink.ethanolG, drink.pouredG(drink.ml, drink.abvTenths), 0.0)
        assertTrue(drink.name, drink.abvTenths in drink.abvRangeTenths)
        val poured = ml * strength * 0.789
        assertTrue("${drink.name} declares ${drink.ethanolG} against $poured", abs(drink.ethanolG - poured) <= 0.3)
        assertTrue("${drink.name} declares ${drink.ethanolG}", drink.ethanolG > 0.0)
    }

    /**
     * A pour is named by the lines the app draws: wine up to 100 ml is little, past it and under
     * 250 ml is medium, 250 ml and up is a lot; beer by its three bottles. The arrows step a beer
     * through those bottles and the rest by 25 ml, and a size typed off the steps moves to its
     * neighbour rather than jumping a whole step past it.
     */
    @Test
    fun aPourIsSizedAndSteppedTheWayTheChooserShowsIt() {
        assertEquals(PourSize.SMALL, AlcoholDrink.WINE.sizeOf(100))
        assertEquals(PourSize.MEDIUM, AlcoholDrink.WINE.sizeOf(125))
        assertEquals(PourSize.MEDIUM, AlcoholDrink.WINE.sizeOf(249))
        assertEquals(PourSize.LARGE, AlcoholDrink.WINE.sizeOf(250))
        assertEquals(
            listOf(PourSize.SMALL, PourSize.MEDIUM, PourSize.LARGE),
            AlcoholDrink.BEER.stopsMl.map(AlcoholDrink.BEER::sizeOf),
        )

        assertEquals(750, AlcoholDrink.BEER.stepMl(500, up = true))
        assertEquals(null, AlcoholDrink.BEER.stepMl(750, up = true))
        assertEquals(330, AlcoholDrink.BEER.stepMl(400, up = false))
        assertEquals(175, AlcoholDrink.WINE.stepMl(150, up = true))
        assertEquals(25, AlcoholDrink.SHOT.stepMl(40, up = false))
        assertEquals(null, AlcoholDrink.SHOT.stepMl(25, up = false))

        // A 330 ml bottle at 12% is 31.2 g, half as much again as the half litre of 5% the tile shows.
        assertEquals(31.24, AlcoholDrink.BEER.pouredG(330, 120), 0.01)
    }

    /**
     * The week's count is printed beside the line, and the warning colour comes on at the line's
     * grams: 96 g rounded to "10 standard drinks, reporting line 10" without it. Under the line the
     * count stops one short of it; from the line on it is the rounded figure again.
     */
    @Test
    fun aWeekUnderTheLineNeverPrintsTheLinesOwnFigure() {
        fun printed(ethanolG: Double) = HabitWeek(ethanolG, 3, 0.0, 0).shownStandardDrinks

        assertEquals(9, printed(96.0))
        assertEquals(9, printed(99.9))
        assertEquals(9, printed(91.0))
        assertEquals(10, printed(ALCOHOL_WEEKLY_G))
        assertEquals(10, printed(104.0))
        assertEquals(11, printed(105.0))
        assertEquals(1, printed(AlcoholDrink.SHOT.ethanolG))
        assertEquals(0, printed(0.0))
    }

    /**
     * "That evening was N cigarettes" is the only familiar scale a hookah bowl has here, and the
     * constant the division uses is declared two hundred lines from the enum value it duplicates.
     * One cigarette has to be worth one cigarette, and the medium bowl's 12 g fifteen of them.
     */
    @Test
    fun oneCigaretteIsWorthExactlyOneCigaretteEquivalent() {
        assertEquals(TobaccoProduct.CIGARETTE.tobaccoG, CIGARETTE_EQUIVALENT_G, 1e-9)

        val single = habitWeek(listOf(smoke(today, TobaccoProduct.CIGARETTE.tobaccoG)), today)
        assertEquals(1.0, single.cigaretteEquivalents, 1e-9)

        val bowl = habitWeek(listOf(smoke(today, TobaccoProduct.HOOKAH_MEDIUM.tobaccoG)), today)
        assertEquals(15.0, bowl.cigaretteEquivalents, 1e-9)
    }

    /**
     * The chooser draws its picture bigger for a bigger choice, and the group's own flag is what
     * says whether that is honest: a bowl grows with its grams, a pouch does not — the pouch gets
     * lighter as the nicotine rises, which is how the products are actually sold.
     */
    @Test
    fun eachTobaccoGroupIsOrderedTheWayItsPictureClaims() {
        val bowls = TobaccoGroup.HOOKAH.members.map { it.tobaccoG }
        assertTrue("bowls $bowls", TobaccoGroup.HOOKAH.grows)
        assertEquals(bowls.sorted(), bowls)
        assertEquals(bowls.size, bowls.distinct().size)

        val pouches = TobaccoGroup.SNUS.members.map { it.tobaccoG }
        assertFalse("pouches $pouches", TobaccoGroup.SNUS.grows)
        assertEquals(pouches.sortedDescending(), pouches)
        assertEquals(pouches.size, pouches.distinct().size)
    }

    /**
     * The grams are also how the duplicate-tap guard tells one product from another
     * ([StepsDb.logSmoking] probes the portion, as a drink's guard does): two products filed under
     * the same grams would be one product to it, and a tap on the second inside three seconds of
     * the first would be dropped without a word, with its button on the screen still open.
     */
    @Test
    fun noTwoTobaccoProductsAreFiledUnderTheSameGrams() {
        val grams = TobaccoProduct.entries.map { it.tobaccoG }

        assertEquals("$grams", grams.size, grams.distinct().size)
    }

    /**
     * A copy-pasted string resource on a bowl or a pouch shows the wrong strength on the one screen
     * whose entire point is which strength was taken, and nothing downstream would notice: the row
     * stores grams, so the wrong label is wrong only to the person reading it.
     */
    @Test
    fun noTwoHabitOptionsShareALabel() {
        val tobacco = TobaccoProduct.entries.map { it.titleRes } + TobaccoProduct.entries.map { it.detailRes }
        val alcohol = AlcoholDrink.entries.map { it.titleRes } + AlcoholDrink.entries.map { it.detailRes }

        assertEquals("$tobacco", tobacco.size, tobacco.distinct().size)
        assertEquals("$alcohol", alcohol.size, alcohol.distinct().size)
        assertTrue("$tobacco", tobacco.none { it == 0 })
        assertTrue("$alcohol", alcohol.none { it == 0 })
    }

    /**
     * The prostate note is written from a count over this window, so a day's slip at either edge
     * moves a person across the frequency it is written about. One day is the degenerate case the
     * arithmetic has to get right: today, and nothing before it.
     */
    @Test
    fun theSexualWindowKeepsItsLastDayAndDropsTheOneBeforeIt() {
        val august = LocalDate.of(2026, 8, 31)
        val journal = listOf(solo(august.minusDays(29)), solo(august.minusDays(30)))

        val month = sexualWindow(journal, august, 30)
        assertEquals(1, month.soloActs)
        assertEquals(30, month.days)

        assertEquals(0, sexualWindow(journal, august, 1).soloActs)
        assertEquals(1, sexualWindow(journal + solo(august), august, 1).soloActs)
    }

    /**
     * The symptom counts are what a "see a clinician" line is written from. A tick repeated inside
     * one payload — which a hand-edited row or a twice-merged backup can carry — is still one
     * occurrence of that symptom, and the solo entries' ticks have to be counted at all.
     */
    @Test
    fun ticksAreTalliedAcrossActsAndSoloActsOncePerEntry() {
        val journal = listOf(
            cycleLogRow(today, """{"cycle":"SEX_PROTECTED","notes":["PAIN"]}"""),
            cycleLogRow(today, """{"cycle":"SEX_UNPROTECTED","notes":["PAIN","DISCOMFORT"]}"""),
            solo(today, ticks("PAIN", "PAIN")),
        )

        val window = sexualWindow(journal, today, 30)

        assertEquals(2, window.acts)
        assertEquals(1, window.soloActs)
        assertEquals(3, window.ejaculations)
        assertEquals(mapOf(SexNote.PAIN to 3, SexNote.DISCOMFORT to 1), window.notes)
    }

    /**
     * An act is recognised by a string pulled out of a payload eleven other cycle logs share. A
     * looser match would count every period-tracking tap as sexual activity and inflate the very
     * frequency the prostate note is written from.
     */
    @Test
    fun flowMucusAndPeriodRowsAreNotSexualActs() {
        val journal = listOf(
            cycleLogRow(today, """{"cycle":"FLOW_HEAVY"}"""),
            cycleLogRow(today, """{"cycle":"MUCUS_DRY"}"""),
            cycleLogRow(today, null),
            row(KIND_CYCLE, today.toString()),
            cycleLogRow(today, """{"cycle":"SEX_PROTECTED"}"""),
        )

        val window = sexualWindow(journal, today, 30)

        assertEquals(1, window.acts)
        assertEquals(0, window.soloActs)
        assertEquals(emptyMap<SexNote, Int>(), window.notes)
    }

    /**
     * Both readers match names by substring, and only the quotes around a name keep PAIN out of
     * EJACULATION_PAIN and BLOOD out of BLOOD_IN_SEMEN. A symptom invented on a clinical screen by
     * a suffix match is a wrong nothing downstream can catch.
     */
    @Test
    fun aTickNameInsideAnotherTickNameIsReadAsItselfOnly() {
        SexNote.entries.forEach { note ->
            assertEquals("for ${note.name}", listOf(note), sexNotesOf(ticks(note.name)))
        }
        // The bathroom vocabulary shares its key and its shape, and BLOOD is not BLOOD_IN_SEMEN.
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(ticks("BLOOD_IN_SEMEN")))
        assertEquals(listOf(BathroomNote.BLOOD), bathroomNotesOf(ticks("BLOOD")))
    }

    /**
     * Backup JSON is re-indented by whatever tool it is passed through, and a file interrupted
     * mid-write ends in the middle of an array. A restored row must degrade to "no ticks" rather
     * than to a crash on the restore screen.
     */
    @Test
    fun aReindentedOrTruncatedPayloadReadsWhatIsThereAndDoesNotThrow() {
        assertEquals(
            listOf(BathroomNote.BURNING, BathroomNote.BLOOD),
            bathroomNotesOf("""{ "notes" : [ "BURNING" , "BLOOD" ] }"""),
        )
        // A backup cut off mid-array: the closing bracket the reader needs never arrives.
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(ticks("BURNING").removeSuffix("]}")))
        assertEquals(emptyList<SexNote>(), sexNotesOf(ticks("PAIN").removeSuffix("]}")))
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(ticks()))
        assertEquals(emptyList<BathroomNote>(), bathroomNotesOf(""))
    }

    /**
     * The profile's sex is a free-text column matched case-sensitively against an enum name, so a
     * value arriving lowercase from an import or an older backup is simply unknown. Unknown has to
     * mean "ask what is asked of everyone" — never one body's questions put to the other.
     */
    @Test
    fun anUnrecognisedSexIsOfferedOnlyTheSharedTicks() {
        val shared = listOf(SexNote.PAIN, SexNote.LOW_DESIRE, SexNote.DISCOMFORT)

        listOf("male", "female", "OTHER", "").forEach {
            assertEquals("for '$it'", shared, sexNotesFor(it))
        }
    }
}
