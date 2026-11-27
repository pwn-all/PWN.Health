package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HealthMathTest {
    private val male = UserProfile(
        sex = "MALE",
        weightKg = 80.0,
        heightCm = 180,
        birthDate = "1996-06-15",
    )

    /**
     * Compendium METs are gross — one MET is resting metabolism itself. An hour at 3.5 MET for a
     * person resting at 80 kcal an hour costs 280 kcal in total, of which 80 kcal would have been
     * spent lying still, so the activity is worth 200 kcal next to a separately reported resting figure.
     */
    @Test
    fun activityEnergyExcludesRestingMetabolism() {
        assertEquals(200.0, netActivityCalories(met = 3.5, restingKcalPerHour = 80.0, durationMinutes = 60.0), 0.001)
    }

    @Test
    fun anActivityAtRestingIntensityCostsNothingExtra() {
        assertEquals(0.0, netActivityCalories(met = 1.0, restingKcalPerHour = 80.0, durationMinutes = 60.0), 0.001)
        assertEquals(0.0, netActivityCalories(met = 0.5, restingKcalPerHour = 80.0, durationMinutes = 60.0), 0.001)
    }

    /**
     * One MET is the person's own rest, Mifflin–St Jeor's figure the day's budget already counts: 80 kg
     * at 180 cm, thirty in 2026, rests at 1,780 kcal a day. The weight in force and the year move it;
     * a profile with no weight costs nothing rather than the equation's height and age terms alone.
     */
    @Test
    fun oneMetIsTheProfilesOwnRestingRate() {
        assertEquals(1_780.0 / 24, male.restingKcalPerHour(year = 2026), 1e-9)
        assertEquals(1_980.0 / 24, male.restingKcalPerHour(weightKg = 100.0, year = 2026), 1e-9)
        assertEquals(1_730.0 / 24, male.restingKcalPerHour(year = 2036), 1e-9)
        assertEquals(0.0, male.restingKcalPerHour(weightKg = 0.0, year = 2026), 0.0)
        // An hour's walk at 3.8 MET: 2.8 of the person's resting hours, not 2.8 × 80 kcal.
        assertEquals(2.8 * 1_780.0 / 24, netActivityCalories(3.8, male.restingKcalPerHour(year = 2026), 60.0), 1e-9)
    }

    /**
     * Kilograms on top of the body grow what is over the resting MET by (body + load) / body, up to half
     * the body again; a machine's or a bar's MET already holds its load.
     */
    @Test
    fun aLoadOnTheBodyCostsWhatItAdds() {
        assertEquals(1 + 2.8 * 1.25, loadedMet("PUSHUPS", 3.8, bodyKg = 80.0, loadKg = 20.0), 1e-9)
        assertEquals(3.8, loadedMet("PUSHUPS", 3.8, bodyKg = 80.0, loadKg = null), 0.0)
        assertEquals(3.8, loadedMet("PUSHUPS", 3.8, bodyKg = 0.0, loadKg = 20.0), 0.0)
        assertEquals(5.0, loadedMet("BENCH_PRESS", 5.0, bodyKg = 80.0, loadKg = 100.0), 0.0)
        // A 100 kg squat on an 80 kg body stops at the cap: about the compendium's weighted squat.
        assertEquals(5.2, loadedMet("SQUATS", 3.8, bodyKg = 80.0, loadKg = 100.0), 1e-9)
        // A row carrying another MET for squats, 5.0, is costed from the catalogue, not with its bar twice.
        assertEquals(5.2, loadedMet("SQUATS", 5.0, bodyKg = 80.0, loadKg = 100.0), 1e-9)

        // Recalculating the journal costs the load at the weight in force, as writing the row did.
        val vest = JournalEntry(
            id = 1,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Push-ups",
            calories = 0.0,
            activityCode = "PUSHUPS",
            met = 3.8,
            durationMinutes = 60.0,
            loadKg = 20.0,
        )
        val recalculated = recalculatedActivityTimeline(listOf(vest), baselineWeightKg = 80.0, profile = male)
        assertEquals(2.8 * 1.25 * 1_780.0 / 24, recalculated.single().calories, 0.001)
    }

    @Test
    fun strideComesFromHeight() {
        assertEquals(0.724, strideLengthM(175), 0.001)
        assertEquals(0.0, strideLengthM(0), 0.001)
    }

    @Test
    fun stepsBecomeDistanceAndEnergy() {
        // 10,000 steps at 175 cm ≈ 7.2 km; at 80 kg that is ≈ 290 kcal net.
        assertEquals(7.245, stepDistanceKm(10_000, 175), 0.001)
        assertEquals(289.8, stepCalories(10_000, weightKg = 80.0, heightCm = 175), 0.1)
        assertEquals(0.0, stepCalories(0, weightKg = 80.0, heightCm = 175), 0.001)
        assertEquals(0.0, stepCalories(-5, weightKg = 80.0, heightCm = 175), 0.001)
    }

    @Test
    fun theBudgetIsMaintenanceShiftedByTheGoal() {
        // Resting 1780 x 1.2 = 2136 kcal sedentary maintenance for this profile. Sedentary, not
        // 1.55: steps and workouts are credited separately, in remainingKcal.
        val maintain = energyBudget(male, currentYear = 2026)
        val lose = energyBudget(male.copy(energyGoal = "LOSE"), currentYear = 2026)
        val gain = energyBudget(male.copy(energyGoal = "GAIN"), currentYear = 2026)

        assertEquals(2136.0, maintain.targetKcal, 0.001)
        assertEquals(1636.0, lose.targetKcal, 0.001)
        assertEquals(2436.0, gain.targetKcal, 0.001)
    }

    @Test
    fun anActivityLevelFromAnOldBackupDoesNotMoveTheBudget() {
        // The budget is sedentary on purpose: movement is credited once, in remainingKcal. A
        // restored HIGH profile must not quietly hand out a second helping of the same walk.
        val moderate = energyBudget(male.copy(activityLevel = "MODERATE"), currentYear = 2026)
        val high = energyBudget(male.copy(activityLevel = "HIGH"), currentYear = 2026)

        assertEquals(moderate.targetKcal, high.targetKcal, 0.001)
    }

    @Test
    fun macrosSplitTheBudgetAndAddBackUpToIt() {
        val budget = energyBudget(male, currentYear = 2026)

        assertEquals(128.0, budget.proteinG, 0.001) // 1.6 g per kg at 80 kg
        assertEquals(budget.targetKcal * 0.25 / 9.0, budget.fatG, 0.001)
        assertEquals(
            budget.targetKcal,
            macroCalories(budget.proteinG, budget.fatG, budget.carbsG),
            0.001,
        )
    }

    /**
     * A flat -500 kcal from a small profile's sedentary maintenance lands under every published
     * minimum for an unsupervised diet, and the macro split collapses with it. The floor is where
     * the coaching stops; a profile whose whole maintenance is already under it is simply not asked
     * to run a deficit.
     */
    @Test
    fun theLosingBudgetNeverGoesUnderTheSafeFloor() {
        val woman = male.copy(
            sex = "FEMALE",
            weightKg = 55.0,
            heightCm = 165,
            birthDate = "1986-06-15",
            energyGoal = "LOSE",
        )
        val man = male.copy(weightKg = 60.0, heightCm = 165, birthDate = "1956-06-15", energyGoal = "LOSE")
        val tiny = woman.copy(weightKg = 45.0, heightCm = 155, birthDate = "1956-06-15")

        // 1464 kcal of maintenance for her, 1544 for him: a flat deficit would prescribe 964 and 1044.
        assertEquals(1_200.0, energyBudget(woman, currentYear = 2026).targetKcal, 0.001)
        assertEquals(1_500.0, energyBudget(man, currentYear = 2026).targetKcal, 0.001)
        assertEquals(
            energyBudget(tiny.copy(energyGoal = "MAINTAIN"), currentYear = 2026).targetKcal,
            energyBudget(tiny, currentYear = 2026).targetKcal,
            0.001,
        )
    }

    /**
     * A goal is an adult's to set. A fourteen-year-old who picked "lose weight" would otherwise be
     * handed 1,200 kcal — the floor published for adults dieting unsupervised, under an adult
     * equation's estimate of a body still growing — while the day's food advice answers her as "just tracking".
     */
    @Test
    fun aMinorsGoalDoesNotMoveTheBudget() {
        val girl = male.copy(sex = "FEMALE", weightKg = 45.0, heightCm = 155, birthDate = "2012-03-01")
        val maintenance = energyBudget(girl, currentYear = 2026).targetKcal

        // Resting 1187.75 at fourteen, times the sedentary 1.2.
        assertEquals(1_425.3, maintenance, 0.001)
        assertEquals(maintenance, energyBudget(girl.copy(energyGoal = "LOSE"), currentYear = 2026).targetKcal, 0.001)
        assertEquals(maintenance, energyBudget(girl.copy(energyGoal = "GAIN"), currentYear = 2026).targetKcal, 0.001)
    }

    /**
     * The callers know the year and not the day, so the age is the one held on the 1st of January:
     * never a day early, and for a birth date nothing can read, never at all.
     */
    @Test
    fun aGoalIsActedOnFromTheYearThatOpensAtEighteen() {
        assertFalse("seventeen until June", energyGoalOffered("2008-06-15", currentYear = 2026))
        assertTrue(energyGoalOffered("2008-06-15", currentYear = 2027))
        assertTrue("eighteen on the day itself", energyGoalOffered("2008-01-01", currentYear = 2026))
        assertFalse(energyGoalOffered("", currentYear = 2026))

        assertEquals("LOSE", coachedEnergyGoal(male.copy(energyGoal = "LOSE"), currentYear = 2026))
        assertEquals("MAINTAIN", coachedEnergyGoal(male.copy(birthDate = "", energyGoal = "LOSE"), currentYear = 2026))
        assertEquals("MAINTAIN", coachedEnergyGoal(male.copy(energyGoal = "BULK"), currentYear = 2026))
    }

    @Test
    fun anImplausiblyLowBudgetStillLeavesNoNegativeCarbs() {
        val tiny = male.copy(weightKg = 200.0, heightCm = 50, birthDate = "1900-06-15", energyGoal = "LOSE")

        assertTrue(energyBudget(tiny, currentYear = 2026).carbsG >= 0.0)
    }

    @Test
    fun remainingCountsMovementBackIn() {
        assertEquals(700.0, remainingKcal(target = 2000.0, eatenKcal = 1500.0, netActiveKcal = 200.0), 0.001)
        assertEquals(-300.0, remainingKcal(target = 2000.0, eatenKcal = 2300.0, netActiveKcal = 0.0), 0.001)
    }

    @Test
    fun waterTargetScalesWithWeight() {
        assertEquals(2400, waterTargetMl(80.0))
        assertEquals(2100, waterTargetMl(70.0))
    }

    /**
     * EFSA 2010's adequate intakes are 2.0 L for women and 2.5 L for men as total water, about 80%
     * of it from drinks. Left alone the per-kilogram rule would ask a 45 kg woman for 1.35 L of drinks
     * and a 90 kg man for 2.7 L; it is held between the drink share of the women's intake and the
     * men's whole day, and an impossible weight lands on the floor rather than on zero.
     */
    @Test
    fun waterTargetIsHeldInsideTheEfsaIntakes() {
        assertEquals(1600, waterTargetMl(45.0))
        assertEquals(1600, waterTargetMl(53.0))
        assertEquals(1620, waterTargetMl(54.0))
        assertEquals(2490, waterTargetMl(83.0))
        assertEquals(2500, waterTargetMl(90.0))
        assertEquals(1600, waterTargetMl(-5.0))
    }

    @Test
    fun macrosUseFourNineFour() {
        assertEquals(465.0, macroCalories(proteinG = 30.0, fatG = 25.0, carbsG = 30.0), 0.001)
    }

    @Test
    fun bmiUsesMetricHeight() {
        assertEquals(24.691, bmi(weightKg = 80.0, heightCm = 180), 0.001)
    }

    @Test
    fun negativeInputsCountAsZero() {
        assertEquals(0.0, netActivityCalories(met = -3.5, restingKcalPerHour = 80.0, durationMinutes = 60.0), 0.001)
        assertEquals(0.0, netActivityCalories(met = 3.5, restingKcalPerHour = 80.0, durationMinutes = -60.0), 0.001)
        assertEquals(0.0, macroCalories(proteinG = -30.0, fatG = -25.0, carbsG = -30.0), 0.001)
    }

    @Test
    fun bmiWithoutHeightIsZero() {
        assertEquals(0.0, bmi(weightKg = 80.0, heightCm = 0), 0.001)
    }

    private val today = LocalDate.of(2026, 8, 7)

    @Test
    fun bmiLabelsSwitchAtWhoBoundaries() {
        fun label(value: Double) = bmiLabelRes(value, male.birthDate, today)
        assertEquals(R.string.bmi_no_data, label(0.0))
        assertEquals(R.string.bmi_no_data, label(-1.0))
        assertEquals(R.string.bmi_under, label(18.49))
        assertEquals(R.string.bmi_normal, label(18.5))
        assertEquals(R.string.bmi_normal, label(24.99))
        assertEquals(R.string.bmi_over, label(25.0))
        assertEquals(R.string.bmi_over, label(29.99))
        assertEquals(R.string.bmi_high, label(30.0))
    }

    /**
     * WHO's cut-points are adult ones. A fourteen-year-old girl at 24.5 would read "normal range"
     * against a line the CDC puts near 23.3 for her age, and an eight-year-old boy at 17.5 "below
     * range" against 18.5; so under eighteen the figure is shown and the verdict withheld, and a birth
     * date nothing can parse is told apart from a young one.
     */
    @Test
    fun aMinorsBmiIsShownWithoutAVerdict() {
        assertEquals(R.string.vitals_no_verdict_age, bmiLabelRes(24.5, "2012-03-01", today))
        assertEquals(R.string.vitals_no_verdict_age, bmiLabelRes(17.5, "2018-03-01", today))
        assertEquals(R.string.vitals_no_verdict_age, bmiLabelRes(24.5, "2008-08-08", today))
        assertEquals(R.string.bmi_normal, bmiLabelRes(24.5, "2008-08-07", today))
        assertEquals(R.string.vitals_no_verdict_birthdate, bmiLabelRes(24.5, "", today))
        assertEquals(R.string.bmi_no_data, bmiLabelRes(0.0, "2012-03-01", today))
    }

    @Test
    fun mifflinMaleEquation() {
        assertEquals(1780.0, restingCaloriesPerDay(male, currentYear = 2026), 0.001)
    }

    @Test
    fun mifflinFemaleEquationUsesItsOwnOffset() {
        assertEquals(1614.0, restingCaloriesPerDay(male.copy(sex = "FEMALE"), currentYear = 2026), 0.001)
    }

    @Test
    fun yearBeforeBirthYearCountsAsAgeZero() {
        assertEquals(1930.0, restingCaloriesPerDay(male, currentYear = 1990), 0.001)
    }

    @Test
    fun standbyAtMiddayIsHalfDailyRestingEnergy() {
        val noon = LocalDateTime.of(2026, 7, 30, 12, 0)
        assertEquals(890.0, standbyCaloriesSoFar(male, noon), 0.001)
    }

    @Test
    fun stepGoalFollowsAgeAndMode() {
        assertEquals(8_000L, recommendedStepGoal(male, currentYear = 2026)) // age 30
        assertEquals(7_000L, recommendedStepGoal(male.copy(birthDate = "1950-06-15"), currentYear = 2026))
        assertEquals(12_000L, recommendedStepGoal(male.copy(birthDate = "2012-06-15"), currentYear = 2026))
        assertEquals(10_000L, recommendedStepGoal(male.copy(energyGoal = "LOSE"), currentYear = 2026))
        assertEquals(8_000L, recommendedStepGoal(male.copy(energyGoal = "GAIN"), currentYear = 2026))
        // A minor's goal is not acted on: the budget takes no food away, and this adds no walking.
        val minor = male.copy(birthDate = "2012-06-15", energyGoal = "LOSE")
        assertEquals(12_000L, recommendedStepGoal(minor, currentYear = 2026))
    }

    /**
     * Onboarding hands over the goal the age band recommends rather than the constructor's 8,000, which
     * a seventy-year-old would see while the burn goal beneath it is derived from 7,000. Only
     * the goal moves, and only there — a goal edited later is never touched by this.
     */
    @Test
    fun onboardingHandsOverTheRecommendedStepGoal() {
        val older = male.copy(birthDate = "1956-06-15")
        assertEquals(8_000L, older.dailyStepGoal)
        assertEquals(older.copy(dailyStepGoal = 7_000L), older.withRecommendedStepGoal(2026))
        assertEquals(12_000L, male.copy(birthDate = "2012-06-15").withRecommendedStepGoal(2026).dailyStepGoal)
        assertEquals(10_000L, male.copy(energyGoal = "LOSE").withRecommendedStepGoal(2026).dailyStepGoal)
        assertEquals(8_000L, male.withRecommendedStepGoal(2026).dailyStepGoal)
    }

    @Test
    fun burnGoalSumsBaseAndOverflow() {
        val maintain = burnGoal(male, eatenKcal = 1_000.0, foodTargetKcal = 2_136.0)
        assertEquals(8_000L, maintain.goalSteps)
        assertEquals(stepCalories(8_000, weightKg = 80.0, heightCm = 180), maintain.baseKcal, 0.001)
        assertEquals(0.0, maintain.overflowKcal, 0.001)
        assertEquals(maintain.baseKcal, maintain.totalKcal, 0.001)

        // The losing mode's whole movement ask arrives through the raised step goal — no second
        // bonus — and it reaches the burn goal the way the home screen shows it: through the goal
        // stored on the profile, which is where onboarding writes the recommendation.
        val losing = male.copy(energyGoal = "LOSE").withRecommendedStepGoal(2026)
        val over = burnGoal(losing, eatenKcal = 2_000.0, foodTargetKcal = 1_636.0)
        assertEquals(10_000L, over.goalSteps)
        assertEquals(stepCalories(10_000, weightKg = 80.0, heightCm = 180), over.baseKcal, 0.001)
        assertEquals(364.0, over.overflowKcal, 0.001)
        assertEquals(over.baseKcal + 364.0, over.totalKcal, 0.001)
    }

    /**
     * The steps card counts against the profile's own goal; the calories card prices that same
     * goal. A person who moves their step goal must not be left measuring their walking against
     * one number and its energy against another.
     */
    @Test
    fun burnGoalPricesTheStoredStepGoalRatherThanTheRecommendation() {
        val edited = male.copy(dailyStepGoal = 15_000)
        assertEquals(8_000L, recommendedStepGoal(edited, 2026))
        assertEquals(15_000L, burnGoal(edited, eatenKcal = 0.0, foodTargetKcal = 2_136.0).goalSteps)
        assertEquals(
            stepCalories(15_000, weightKg = 80.0, heightCm = 180),
            burnGoal(edited, eatenKcal = 0.0, foodTargetKcal = 2_136.0).baseKcal,
            0.001,
        )
    }

    @Test
    fun eatingUnderBudgetDoesNotLowerTheBurnGoal() {
        assertEquals(0.0, burnGoal(male, eatenKcal = 500.0, foodTargetKcal = 2_136.0).overflowKcal, 0.001)
    }

    @Test
    fun aLoggedWalkAndItsOwnStepsCountOnce() {
        val walk = JournalEntry(
            day = "2026-08-07",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 224.0,
            activityCode = "WALK",
        )
        val pushups = walk.copy(title = "Push-ups", calories = 80.0, activityCode = "PUSHUPS")

        // The walk's MET estimate outweighs the pedometer's view of the same outing; push-ups add on top.
        assertEquals(304.0, movedCalories(listOf(walk, pushups), stepsKcal = 190.0), 0.001)
        // A day of untracked walking: the steps win over the smaller logged claim.
        assertEquals(330.0, movedCalories(listOf(walk.copy(calories = 120.0)), stepsKcal = 330.0), 0.001)
        assertEquals(150.0, movedCalories(emptyList(), stepsKcal = 150.0), 0.001)
    }

    /**
     * The calories card prints the walking's share under the total the ring counts, so that share
     * has to be the claim that won the competition above — never the pedometer's raw figure on a
     * day when a logged walk outweighed it, which is no part of the total at all.
     */
    @Test
    fun theWalkingShareIsTheClaimThatSurvivedTheCompetition() {
        val walk = JournalEntry(
            day = "2026-08-07",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 224.0,
            activityCode = "WALK",
        )
        val pushups = walk.copy(title = "Push-ups", calories = 80.0, activityCode = "PUSHUPS")

        // The logged walk won, so the walking share is its 224 and not the pedometer's 190.
        assertEquals(224.0, walkingShareOfMoved(listOf(walk, pushups), stepsKcal = 190.0), 0.001)
        // The pedometer won, so the share is its own figure.
        assertEquals(330.0, walkingShareOfMoved(listOf(walk.copy(calories = 120.0)), stepsKcal = 330.0), 0.001)
        assertEquals(150.0, walkingShareOfMoved(emptyList(), stepsKcal = 150.0), 0.001)
        // Whatever wins, the share is part of the total and never more than it.
        listOf(190.0, 330.0, 0.0).forEach { steps ->
            val moved = movedCalories(listOf(walk, pushups), steps)
            assertTrue(walkingShareOfMoved(listOf(walk, pushups), steps) <= moved)
        }
    }

    @Test
    fun measurementRecalculatesOnlyFollowingActivities() {
        val before = JournalEntry(
            id = 1,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 0.0,
            met = 3.0,
            durationMinutes = 60.0,
        )
        val measurement = JournalEntry(
            id = 2,
            day = "2026-07-30",
            occurredAt = 200,
            kind = "MEASUREMENT",
            title = "Weight",
            calories = 0.0,
            measurementWeightKg = 100.0,
        )
        val after = before.copy(id = 3, occurredAt = 300)

        val result = recalculatedActivityTimeline(
            listOf(after, measurement, before),
            baselineWeightKg = 80.0,
            profile = male,
        ).associateBy(JournalEntry::id)

        // (3 − 1) MET × Mifflin–St Jeor's hour at 80 kg, then at the 100 kg weighed in between.
        assertEquals(2 * 1_780.0 / 24, result.getValue(1).calories, 0.001)
        assertEquals(2 * 1_980.0 / 24, result.getValue(3).calories, 0.001)
    }

    @Test
    fun implausibleWeightDoesNotPoisonLaterEstimates() {
        val measurement = JournalEntry(
            id = 1,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "MEASUREMENT",
            title = "Weight",
            calories = 0.0,
            measurementWeightKg = 500.0,
        )
        val activity = JournalEntry(
            id = 2,
            day = "2026-07-30",
            occurredAt = 200,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 0.0,
            met = 3.0,
            durationMinutes = 60.0,
        )

        val result = recalculatedActivityTimeline(
            listOf(measurement, activity),
            baselineWeightKg = 80.0,
            profile = male,
        ).associateBy(JournalEntry::id)

        assertEquals(2 * 1_780.0 / 24, result.getValue(2).calories, 0.001)
    }

    /**
     * An ergometer reports the energy it measured, and a live Bluetooth session is stored with a
     * flat 5 MET as a label for the kind of workout. Deleting an unrelated row runs this over the
     * whole journal, and the estimate must not be allowed to overwrite the measurement.
     */
    @Test
    fun aMachineMeasuredWorkoutKeepsItsOwnEnergy() {
        val rowing = JournalEntry(
            id = 1,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Rowing",
            calories = 412.0,
            activityCode = "BLUETOOTH_CONCEPT2_PM5",
            met = 5.0,
            durationMinutes = 60.0,
        )

        val result = recalculatedActivityTimeline(listOf(rowing), baselineWeightKg = 80.0, profile = male)

        assertEquals(412.0, result.single().calories, 0.001)
    }

    @Test
    fun deletingMeasurementRestoresBaselineCalculation() {
        val activity = JournalEntry(
            id = 3,
            day = "2026-07-30",
            occurredAt = 300,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 300.0,
            met = 3.0,
            durationMinutes = 60.0,
        )

        val result = recalculatedActivityTimeline(listOf(activity), baselineWeightKg = 80.0, profile = male)

        assertEquals(2 * 1_780.0 / 24, result.single().calories, 0.001)
    }
}
