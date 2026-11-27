package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * [ReportPart.MICRONUTRIENTS]: the vitamin table over the report's period — see [nutrientTable].
 *
 * It reads only what the sheet itself carries: the meals where the food section is ticked, the
 * courses where the medicines are, and it is not built at all where neither is — a computed section
 * must not be a side door into one its owner left off. Null too where neither half has anything to
 * print: no meal points at a catalogue row with a figure, and no course holding a nutrient asked for
 * a dose.
 *
 * Each nutrient is a line and up to two lines under it: the daily figure and the upper levels with
 * what may be said against them, then the food with how much of the day's eating it stands for, then
 * the doses with how many days asked for one. A doctor reads the floor and its counts together or
 * not at all.
 */
internal fun micronutrientSection(
    sources: ReportSources,
    request: ReportRequest,
    today: LocalDate,
    nowMinute: Int,
    zone: ZoneId,
): ReportSection? {
    val food = ReportPart.NUTRITION in request.parts
    val supplements = ReportPart.MEDICATIONS in request.parts
    val table = if (food || supplements) {
        nutrientTable(
            sources.entries,
            sources.medications,
            sources.profile,
            request.from,
            request.to,
            today,
            nowMinute,
            zone,
            includeFood = food,
            includeSupplements = supplements,
        )
    } else {
        null
    }
    return table?.takeUnless(NutrientTable::empty)?.let { built ->
        ReportSection(
            part = ReportPart.MICRONUTRIENTS,
            rows = onceUntilItChanges(built.rows.flatMap { reportRows(it, built.foodIncluded) }),
            // The food's days, as the food section counts its own: the doses carry theirs on each line.
            completeness = Completeness(built.loggedDays, built.periodDays, built.meals)
                .takeIf { built.foodIncluded && built.loggedDays > 0 },
            provenance = micronutrientProvenance(sources, request, built),
            caveatRes = micronutrientCaveatRes(built.foodIncluded, built.supplementsIncluded),
        )
    }
}

/**
 * The food line's note — how much of the eating a figure stands for — printed where it first appears
 * and again wherever it changes. It is the same sentence for most nutrients of one diary, and printed
 * under all eighteen it would triple the table's length without saying anything new.
 */
private fun onceUntilItChanges(rows: List<ReportRow>): List<ReportRow> {
    val fromFood = ReportText.Res(R.string.nutrient_from_food)
    var last: ReportText? = null
    return rows.map { row ->
        if (row.label != fromFood) return@map row
        val repeated = row.note != null && row.note == last
        last = row.note
        if (repeated) row.copy(note = null) else row
    }
}

private fun reportRows(row: NutrientRow, foodIncluded: Boolean): List<ReportRow> = buildList {
    add(ReportRow(res(row.nutrient.titleRes), row.figuresText(), row.statusText()))
    if (foodIncluded) add(ReportRow(res(R.string.nutrient_from_food), row.foodText(), row.foodNote(), detail = true))
    row.supplements?.let { window ->
        add(
            ReportRow(
                label = res(R.string.nutrient_from_supplements),
                value = window.markedText(row.nutrient),
                note = window.markedNote(),
                detail = true,
            ),
        )
    }
}

/**
 * Who wrote the figures, the way every measured section says it. The meals are the person's own or
 * another app's, and only those whose figures are in the table are named: a meal that carries one,
 * on a day the table counted. A day [foodMicroSummaries] passed over adds nothing to it, and a meal
 * with no figure only to the counts and to the energy each share is taken of. The doses are only
 * ever ticked here.
 */
private fun micronutrientProvenance(
    sources: ReportSources,
    request: ReportRequest,
    table: NutrientTable,
): List<Provenance> {
    val period = request.from.toString()..request.to.toString()
    val meals = sources.entries.filter { table.foodIncluded && it.kind == KIND_EAT && it.day in period }
        .groupBy(JournalEntry::day)
        .filterValues(::isCountedDay)
        .values
        .flatten()
        .filter { it.foodMicros().isNotEmpty() }
    val doses = ProvenanceCategory.MANUAL.takeIf { table.rows.any { it.supplements != null } }
    return (meals.map { provenanceCategory(it.measurementSource) } + listOfNotNull(doses))
        .distinct()
        .sorted()
        .map { Provenance(it) }
}

/** Whether the table counted one day's meals, asked of the same rule that counted them. */
private fun isCountedDay(meals: List<JournalEntry>): Boolean =
    meals.first().day.toLocalDateOrNull()
        ?.let { day -> foodMicroSummaries(meals, day, day).values.first().loggedDays > 0 } == true

/** The line under the table, saying only what applies to the halves it was built from. */
internal fun micronutrientCaveatRes(food: Boolean, supplements: Boolean): Int = when {
    food && supplements -> R.string.report_caveat_micronutrients
    food -> R.string.report_caveat_micronutrients_food
    else -> R.string.report_caveat_micronutrients_supplements
}

/**
 * The rows a course that says what one dose holds adds under itself in the medicines section: the
 * label's figures, and for each nutrient what the marked doses came to a day over the days that
 * asked for one — [supplementWindow] over [askedDoses], the call the adherence row above it counts
 * through.
 *
 * The medicines section passes the period's last day as [today] and the minute its adherence row
 * counts to — the whole day when the period ended before the sheet was made — so the two lines of
 * one course, and the vitamin table beside them, count the same doses.
 *
 * Nothing for a course nobody described that way: its dose is free text, and nothing is read from it.
 */
internal fun courseContentsRows(
    course: Medication,
    journal: List<JournalEntry>,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    nowMinute: Int,
    zone: ZoneId,
): List<ReportRow> {
    val held = contentsOf(course.contents).entries.sortedBy { it.key.ordinal }
    if (held.isEmpty()) return emptyList()
    val marked = supplementWindow(supplementIntake(listOf(course), journal, from, to, today, nowMinute, zone))
    val dose = ReportText.Join(
        held.map { (nutrient, amount) ->
            res(R.string.report_value_unit, res(nutrient.titleRes), nutrientAmount(nutrient, amount))
        },
    )
    return listOf(ReportRow(res(R.string.report_course_contents), dose, detail = true)) +
        marked.entries.sortedBy { it.key.ordinal }.map { (nutrient, window) ->
            ReportRow(
                label = res(nutrient.titleRes),
                value = window.markedText(nutrient, R.string.report_course_marked),
                note = window.markedNote(),
                detail = true,
            )
        }
}

// --- the texts, shared by the sheet and the page in the food flow -----------------------------

/**
 * The daily figure and the upper levels, each with the body that set it: "RDA: 90 mg (NASEM) ·
 * Upper level: 2000 mg (NASEM)". Every daily figure is NASEM's — see [Nutrient.reference].
 */
internal fun NutrientRow.figuresText(): ReportText = join(
    reference?.let {
        val amount = nutrientAmount(nutrient, it.amount)
        res(R.string.nutrient_reference_value, res(it.kind.labelRes), amount, res(ReferenceBody.NASEM.labelRes))
    } ?: res(R.string.nutrient_no_reference),
    upperLevelText(),
)

/**
 * The upper levels, each with what it counts and the body that set it — folate's in micrograms of
 * folic acid and vitamin A's in micrograms of retinol, which is what each limit is written in.
 */
private fun NutrientRow.upperLevelText(): ReportText? {
    val limited = if (nutrient == Nutrient.FOLATE) Nutrient.FOLIC_ACID else nutrient
    val levels = upperLevels.map { check ->
        val amount = res(R.string.report_value_unit, nutrientFigure(check.level.amount), res(limited.unit.labelRes))
        val scope = when (check.level.scope) {
            UpperLevelScope.TOTAL -> R.string.nutrient_upper_total

            UpperLevelScope.SUPPLEMENTS -> R.string.nutrient_upper_supplements

            UpperLevelScope.SYNTHETIC ->
                if (nutrient == Nutrient.FOLATE) R.string.nutrient_upper_folic_acid else R.string.nutrient_upper_added

            UpperLevelScope.PREFORMED -> R.string.nutrient_upper_preformed
        }
        res(scope, amount, res(check.level.body.labelRes))
    }
    return levels.takeIf { it.isNotEmpty() }?.let { res(R.string.nutrient_upper_level, ReportText.Join(it)) }
}

/** What may be said: the verdict, and an upper level passed, naming whose; null where nothing may. */
internal fun NutrientRow.statusText(): ReportText? {
    val bodies = upperLevels.filter(UpperLevelCheck::above).map { res(it.level.body.labelRes) }
    val parts = listOfNotNull(
        verdict?.let { res(it.labelRes) },
        bodies.takeIf { it.isNotEmpty() }
            ?.let { res(R.string.nutrient_verdict_above_upper, ReportText.Join(it, separator = ", ")) },
    )
    return parts.takeIf { it.isNotEmpty() }?.let { ReportText.Join(it) }
}

/**
 * The food figure: a plain mean only where every meal of every logged day carries the nutrient,
 * "at least" everywhere else — the mean over the counted days, or the floor over the logged ones.
 */
internal fun NutrientRow.foodText(): ReportText {
    val summary = food
    val floor = foodFloor
    return when {
        summary == null -> res(R.string.nutrient_food_no_figures)

        floor == null -> res(R.string.nutrient_food_unknown)

        summary.meanPerDay != null && summary.knownMeals == summary.meals ->
            res(R.string.report_per_day_rate, nutrientAmount(nutrient, floor))

        else -> res(R.string.nutrient_at_least_per_day, nutrientAmount(nutrient, floor))
    }
}

/**
 * How much of the eating the food figure stands for: the share of the logged days' meal energy
 * whose meals carry a figure, and the days counted.
 */
internal fun NutrientRow.foodNote(): ReportText? = food?.let { summary ->
    summary.coverage?.let { coverage ->
        val logged = ReportText.Quantity(R.plurals.count_days, summary.loggedDays)
        val days = res(R.string.report_count_of, summary.countedDays, logged)
        val known = res(R.string.nutrient_food_coverage, percent(coverage), days)
        if (summary.meanPerDay == null) {
            join(known, ReportText.Quantity(R.plurals.nutrient_floor_only, FOOD_MICROS_MIN_DAYS))
        } else {
            known
        }
    }
}

/**
 * What the marked doses came to a day, or that none was marked — in words rather than a nought,
 * because a course somebody keeps without using the tick has not been refused.
 */
internal fun IntakeWindow.markedText(nutrient: Nutrient, templateRes: Int = R.string.report_per_day_rate): ReportText =
    if (meanMarked > 0.0) {
        res(templateRes, nutrientAmount(nutrient, meanMarked))
    } else {
        res(R.string.nutrient_supplement_none_marked)
    }

/**
 * The days behind the figure and the share of what they asked for that was marked — and no share at
 * all where nothing was, which is not 0%.
 */
internal fun IntakeWindow.markedNote(): ReportText {
    val days = ReportText.Quantity(R.plurals.nutrient_days_with_dose, askedDays)
    return if (meanMarked > 0.0) {
        join(days, res(R.string.nutrient_marked_share, percent(markedShare)))
    } else {
        days
    }
}

/** "Food logged on 31 of 90 days": the days the food figures stand on, over the page's rows. */
internal fun foodLoggedText(table: NutrientTable): ReportText = res(
    R.string.nutrient_food_logged,
    res(R.string.report_count_of, table.loggedDays, ReportText.Quantity(R.plurals.count_days, table.periodDays)),
)

/** A figure in the unit the row is read in: µg RAE for vitamin A, µg DFE for folate. */
internal fun nutrientAmount(nutrient: Nutrient, value: Double): ReportText {
    val unit = when (nutrient) {
        Nutrient.VITAMIN_A -> R.string.med_contents_unit_rae
        Nutrient.FOLATE -> R.string.nutrient_unit_dfe
        else -> nutrient.unit.labelRes
    }
    return res(R.string.report_value_unit, nutrientFigure(value), res(unit))
}

/**
 * A share as a whole percentage, rounded as the adherence line beside it is, except at the two ends:
 * something short of all never prints as 100%, and something more than none never as 0%.
 */
private fun percent(share: Double): Int = when {
    share >= 1.0 -> PERCENT
    share <= 0.0 -> 0
    else -> (share * PERCENT).roundToInt().coerceIn(1, PERCENT - 1)
}

private const val PERCENT = 100

private fun res(id: Int, vararg args: Any): ReportText = ReportText.Res(id, args.toList())

private fun join(vararg parts: ReportText?): ReportText = ReportText.Join(parts.filterNotNull())
