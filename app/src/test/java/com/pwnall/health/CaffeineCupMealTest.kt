package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A cappuccino from the caffeine dialog is food — its energy is in the day's — but it is a drink,
 * and every rule that counts meals has to leave it out: two of them are not a day of eating, and one
 * beside a dinner is not a day logged to the end. Each case here is a figure that would read wrongly
 * if the cup counted as a meal.
 */
class CaffeineCupMealTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val zone: ZoneId = ZoneOffset.UTC
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-04-10")

    private fun at(day: LocalDate, minute: Int): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun meal(day: LocalDate, minute: Int, kcal: Double = 1_000.0) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, minute),
        kind = "EAT",
        title = "meal",
        calories = kcal,
        proteinG = 10.0,
    )

    private fun cappuccino(day: LocalDate, minute: Int) =
        cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML, day.toString(), at(day, minute))

    private fun days(count: Int) = (1..count).map { today.minusDays(it.toLong()) }

    private fun nutrition(entries: List<JournalEntry>): ReportSection {
        val from = today.minusMonths(6)
        val report = doctorReport(
            sources = ReportSources(profile, entries, emptyList()),
            request = ReportRequest(from = from, to = today),
            today = today,
            zone = zone,
        )
        return report.sections.single { it.part == ReportPart.NUTRITION && it.presence == SectionPresence.PRESENT }
    }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    @Test
    fun theCupIsMarkedAsACupAndAnyOtherFoodIsAMeal() {
        val cup = cappuccino(today, 9 * 60)
        assertTrue(cup.isCaffeineCup)
        assertFalse(cup.countsAsMeal)
        // Its pointer is the catalogue's cappuccino at the cup's grams, which the micronutrients read.
        val items = foodSourceItems(cup.foodSource!!)!!
        assertEquals(CAPPUCCINO_FOOD_ID, items.single().food.id)
        assertEquals(STANDARD_CUP_ML.toDouble(), items.single().grams, 0.0)

        // A synced meal stating its caffeine, and a catalogue meal stating none, are both still meals.
        assertTrue(meal(today, 12 * 60).copy(caffeineMg = 40.0).countsAsMeal)
        assertTrue(meal(today, 12 * 60).copy(foodSource = "$CAPPUCCINO_FOOD_ID=240.0").countsAsMeal)
        assertFalse(cappuccino(today, 9 * 60).copy(kind = KIND_CAFFEINE).countsAsMeal)
    }

    /** A dinner and a cappuccino every day for a week is seven half-logged days, not an average day. */
    @Test
    fun aMealAndACappuccinoAreNotAFullDayOnTheDoctorsSheet() {
        val entries = days(7).flatMap { listOf(meal(it, 19 * 60, 700.0), cappuccino(it, 9 * 60)) }

        val food = nutrition(entries)

        assertEquals(ReportText.Quantity(R.plurals.report_not_averaged, 7), food.rows[0].value)
        assertEquals(res(R.string.report_count_of, 0, ReportText.Quantity(R.plurals.count_days, 7)), food.rows[0].note)
    }

    /** Two cappuccinos a day and no food are no average day at all. */
    @Test
    fun cappuccinosAloneAreNoAverageDay() {
        val entries = days(7).flatMap { listOf(cappuccino(it, 9 * 60), cappuccino(it, 15 * 60)) }

        val food = nutrition(entries)

        assertEquals(ReportText.Quantity(R.plurals.report_not_averaged, 7), food.rows[0].value)
        // Its caffeine is still the day's: 80 mg each, twice a day.
        val caffeine = food.rows.first { it.label == res(R.string.caffeine_title) }
        assertEquals(
            res(R.string.report_per_day_rate, res(R.string.report_value_unit, 160, res(R.string.unit_milligrams))),
            caffeine.value,
        )
    }

    /** On a day kept in full the cup is not a meal, but its energy is the day's. */
    @Test
    fun aFullDaysEnergyHoldsTheCappuccino() {
        val entries = days(7).flatMap { listOf(meal(it, 12 * 60), meal(it, 19 * 60), cappuccino(it, 9 * 60)) }

        val food = nutrition(entries)

        assertEquals(res(R.string.report_value_unit, 2_081, res(R.string.unit_kcal)), food.rows[0].value)
    }

    /** The diet window before a blood draw drops a day of one meal and a cup, as it drops a day of one meal. */
    @Test
    fun aMealAndACappuccinoAreNotALoggedDayBeforeADraw() {
        val drawn = LocalDate.of(2026, 6, 1)
        val halfDays = (0 until 42).map { drawn.minusDays(it.toLong()) }
        val reading = LabReading(
            marker = LabMarker.LDL,
            value = 4.2,
            occurredAt = at(drawn, 9 * 60),
            day = drawn.toString(),
            fasting = true,
            ageYears = 40,
            status = LabStatus.ABOVE,
            entryId = 1L,
        )

        val half = foodBeforeDraw(halfDays.flatMap { listOf(meal(it, 19 * 60), cappuccino(it, 8 * 60)) }, reading)!!
        assertEquals(0, half.daysLogged)
        assertFalse(half.enough)

        val whole = halfDays.flatMap { listOf(meal(it, 12 * 60), meal(it, 19 * 60), cappuccino(it, 8 * 60)) }
        assertEquals(42, foodBeforeDraw(whole, reading)!!.daysLogged)
    }

    /**
     * The mark is the pair of caffeine and the catalogue's cappuccino alone. A cup corrected in the
     * editor keeps both and stays a cup; a drink carrying caffeine that is given some other food, or
     * a dish with a cappuccino in it, is a meal.
     */
    @Test
    fun onlyTheCappuccinoPointerMarksACup() {
        val cup = cappuccino(today, 9 * 60)
        assertTrue(cup.copy(foodSource = "$CAPPUCCINO_FOOD_ID=250.0", servingG = 250.0).isCaffeineCup)

        val synced = meal(today, 12 * 60).copy(caffeineMg = 32.0)
        assertTrue(synced.copy(foodSource = "cola=330.0").countsAsMeal)
        assertTrue(synced.copy(foodSource = "$CAPPUCCINO_FOOD_ID=200.0|croissant=60.0").countsAsMeal)
    }

    /**
     * A day's fibre, sugar, sodium, saturated fat and cholesterol count only when every row of the day
     * carries them, so the cup carries the catalogue cappuccino's, scaled to its grams.
     */
    @Test
    fun theCupCarriesTheCataloguesOtherColumns() {
        val cup = cappuccino(today, 9 * 60)
        val per100 = TOP_FOODS_BY_ID.getValue(CAPPUCCINO_FOOD_ID).nutrition
        val scale = cup.servingG / 100.0

        assertEquals(per100.sugarG!! * scale, cup.sugarG!!, 1e-9)
        assertEquals(per100.sodiumMg!! * scale, cup.sodiumMg!!, 1e-9)
        assertEquals(per100.cholesterolMg!! * scale, cup.cholesterolMg!!, 1e-9)
        assertEquals(per100.saturatedFatG!! * scale, cup.saturatedFatG!!, 1e-9)
        assertEquals(per100.fiberG!! * scale, cup.fiberG!!, 1e-9)
    }

    /**
     * A dose taken before food is held against any food, the milk of a cappuccino included: drunk
     * after the dose's time, the cup has broken the empty stomach the dose asked for. A dose taken with
     * food still waits for a meal.
     */
    @Test
    fun aCappuccinoBreaksTheEmptyStomachButIsNotTheMealADoseWaitsFor() {
        fun course(food: MedFood) = Medication(
            id = 1,
            name = "Thyroid",
            dose = "1",
            schedule = MedSchedule.CLOCK.name,
            times = "480",
            foodRelation = food.name,
            startDay = today.minusDays(1).toString(),
            createdAt = 0,
        )
        val journal = listOf(cappuccino(today, 8 * 60 + 20))
        val now = at(today, 8 * 60 + 30)

        val before = dueMedicine(listOf(course(MedFood.BEFORE)), journal, now, zone).single()
        assertEquals(MedReason.MISSED_BEFORE_FOOD, before.nudge.reason)
        assertTrue(
            dueMedicine(listOf(course(MedFood.WITH)), journal, now, zone).none {
                it.nudge.reason ==
                    MedReason.WITH_FOOD
            },
        )
    }

    /** A dose taken after a meal is timed from the lunch, not from the coffee drunk after it. */
    @Test
    fun aDoseAfterAMealIsNotTimedFromTheCappuccino() {
        val journal = listOf(meal(today, 12 * 60 + 30), cappuccino(today, 15 * 60))

        assertEquals(12 * 60 + 30, lastMealMinuteOf(journal, today, zone))
        assertNull(lastMealMinuteOf(listOf(cappuccino(today, 9 * 60)), today, zone))
    }
}
