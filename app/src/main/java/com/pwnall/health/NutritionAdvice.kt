package com.pwnall.health

import java.time.LocalDateTime
import kotlin.math.roundToInt

/** The food card, remaining budget and daily advice share this activity-adjusted denominator. */
internal fun dailyFoodTarget(baseKcal: Double, movedKcal: Double): Double =
    baseKcal + movedKcal.takeIf { it.isFinite() && it > 0.0 }.orZero()

private fun Double?.orZero(): Double = this ?: 0.0

/**
 * Daily diary feedback, not a diagnosis of actual intake. The percentage and clock thresholds are
 * product heuristics; dietary wording follows the NHS and WHO sources in docs/nutrition-advice.md.
 * No advice leaves the app: these use the existing, hideable ENERGY topic.
 */
internal fun dailyNutritionAdvice(
    journal: List<JournalEntry>,
    profile: UserProfile,
    now: LocalDateTime,
    movedKcal: Double = 0.0,
): List<Advice> {
    val today = now.toLocalDate()
    val budget = energyBudget(profile, today.year)
    val target = dailyFoodTarget(budget.targetKcal, movedKcal)
    val food = journal.filter { it.day == today.toString() && it.kind == KIND_EAT }
    val eaten = food.sumOf { it.calories }
    val invalidFoodEnergy = food.any { !it.calories.isFinite() || it.calories < 0.0 }
    val invalidTarget = !target.isFinite() || target <= 0.0
    // Corrupt energy cannot support either a percentage or a claim that nothing was logged.
    if (invalidTarget || !eaten.isFinite() || invalidFoodEnergy) {
        return emptyList()
    }
    val snapshot = NutritionSnapshot(
        goal = coachedEnergyGoal(profile, today.year),
        hour = now.hour,
        target = target,
        eaten = eaten,
        budget = budget,
        food = food,
    )
    return listOfNotNull(intakeAdvice(snapshot), compositionAdvice(snapshot))
}

private data class NutritionSnapshot(
    val goal: String,
    val hour: Int,
    val target: Double,
    val eaten: Double,
    val budget: EnergyBudget,
    val food: List<JournalEntry>,
) {
    val share: Double get() = eaten / target
    val percent: String get() = (share * 100.0).roundToInt().toLong().formatted()
}

private fun intakeAdvice(day: NutritionSnapshot): Advice? {
    if (day.food.isEmpty()) {
        return if (day.hour >= 12) {
            nutritionAdvice("unlogged", R.string.nutrition_unlogged_title, R.string.nutrition_unlogged_body)
        } else {
            null
        }
    }
    return excessIntakeAdvice(day) ?: when {
        day.hour >= 14 && day.share < 0.25 -> lowIntakeAdvice(day, veryLow = true)
        day.hour >= 18 && day.share < 0.6 -> lowIntakeAdvice(day, veryLow = false)
        day.goal == "GAIN" && day.hour >= 18 && day.share < 0.85 -> lowIntakeAdvice(day, veryLow = false)
        else -> null
    }
}

private fun excessIntakeAdvice(day: NutritionSnapshot): Advice? = when {
    day.goal == "LOSE" && day.share > 1.0 -> nutritionAdvice(
        "lose-over",
        R.string.nutrition_lose_over_title,
        R.string.nutrition_lose_over_body,
        listOf(day.percent),
        AdviceLevel.ATTENTION,
    )

    day.goal == "GAIN" && day.share >= 1.3 -> nutritionAdvice(
        "gain-over",
        R.string.nutrition_gain_over_title,
        R.string.nutrition_gain_over_body,
        listOf(day.percent),
        AdviceLevel.ATTENTION,
    )

    day.goal == "MAINTAIN" && day.share >= 1.2 -> nutritionAdvice(
        "tracking-over",
        R.string.nutrition_tracking_over_title,
        R.string.nutrition_tracking_over_body,
        listOf(day.percent),
    )

    else -> null
}

private fun lowIntakeAdvice(day: NutritionSnapshot, veryLow: Boolean): Advice {
    val (title, body) = when (day.goal) {
        "LOSE" -> R.string.nutrition_lose_low_title to R.string.nutrition_lose_low_body
        "GAIN" -> R.string.nutrition_gain_low_title to R.string.nutrition_gain_low_body
        else -> R.string.nutrition_tracking_low_title to R.string.nutrition_tracking_low_body
    }
    return nutritionAdvice(
        if (veryLow) "very-low" else "low",
        title,
        body,
        listOf(day.percent),
        if (veryLow) AdviceLevel.ATTENTION else AdviceLevel.HABIT,
    )
}

private fun compositionAdvice(day: NutritionSnapshot): Advice? {
    // A small breakfast is not enough of the day to characterise its food composition.
    val food = day.food.filter { it.calories > 0.0 }
    val fatEnergy = food.sumOf { it.fatG } * 9.0
    val saturatedEnergy = food.mapNotNull { it.saturatedFatG }.filter { it.isFinite() && it >= 0.0 }.sum() * 9.0
    val totalMacros = food.sumOf { macroCalories(it.proteinG, it.fatG, it.carbsG) }
    val inconsistentMacros = totalMacros > day.eaten * 1.25 || saturatedEnergy > fatEnergy
    val insufficientFood = day.eaten <= 0.0 || day.share < 0.25
    if (insufficientFood || food.any { !it.hasUsableMacros() } || inconsistentMacros) {
        return null
    }
    return when {
        saturatedEnergy > day.target * 0.1 -> nutritionAdvice(
            "saturated-fat",
            R.string.nutrition_saturated_title,
            R.string.nutrition_saturated_body,
        )

        fatEnergy > day.eaten * 0.4 && fatEnergy >= day.target * 0.25 -> nutritionAdvice(
            "fat",
            R.string.nutrition_fat_title,
            R.string.nutrition_fat_body,
            listOf((fatEnergy / day.eaten * 100.0).roundToInt().toLong().formatted()),
        )

        hasLowRecordedProtein(day, food) -> nutritionAdvice(
            "protein",
            R.string.nutrition_protein_title,
            R.string.nutrition_protein_body,
        )

        else -> null
    }
}

private fun hasLowRecordedProtein(day: NutritionSnapshot, food: List<JournalEntry>): Boolean =
    day.goal == "GAIN" && day.share >= 0.75 && food.all { it.hasCompleteMacros() } &&
        food.sumOf { it.proteinG } < day.budget.proteinG * 0.5

private fun JournalEntry.hasUsableMacros(): Boolean =
    listOf(proteinG, fatG, carbsG).all { it.isFinite() && it >= 0.0 }

private fun JournalEntry.hasCompleteMacros(): Boolean =
    macroCalories(proteinG, fatG, carbsG) in calories * 0.75..calories * 1.25

private fun nutritionAdvice(
    key: String,
    title: Int,
    body: Int,
    args: List<Any> = emptyList(),
    level: AdviceLevel = AdviceLevel.HABIT,
): Advice = Advice(
    topic = AdviceTopic.ENERGY,
    level = level,
    titleRes = title,
    bodyRes = body,
    args = args,
    sourceRes = R.string.nutrition_source,
    windowDays = 1,
    id = "nutrition-$key",
)
