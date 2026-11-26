package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

// "Around each blood test": what the diary and the courses held in the weeks before each sample,
// laid out by the date of the draw.
//
// It describes and does not compare. A result is printed beside what was logged before it — food in
// the window the marker is formed over, steps, the workouts before a liver enzyme, the nearest
// weigh-in, alcohol, the days of heavy flow before an iron marker, the courses and how many of their
// doses were marked — and nothing here says which way a figure moved, whether the move is
// bigger than the test's own wobble, or what moved it. Showing laboratory results stays outside the
// US device definition only while nothing interprets or analyses them (FD&C Act §520(o)(1)(D)), and
// MDCG 2019-11 rev.1 names trend analysis in a self-testing app as medical device software; the diary
// of one person cannot tell a meal from a season anyway. The size of the wobble is printed once, as a
// note about the tests on the sheet, never against a pair of results.
//
// It reads only what the sheet prints: nothing unless the blood tests are ticked, food only with the
// nutrition section, steps and workouts with the activity one, the weigh-in with the weight, alcohol
// with the social history, the flow with the cycle, and the courses with the medicines.

/**
 * [ReportPart.LAB_CONTEXT]: each laboratory draw of a marker the sheet prints two results of or more,
 * with the diary around it; each course whose label names a nutrient against the marker it is read
 * beside ([supplementResponses]); and the notes the doses and the samples together call for.
 *
 * Null where the blood tests are not ticked, and where nothing is left to say.
 */
internal fun labContextSection(
    sources: ReportSources,
    request: ReportRequest,
    labs: ReportLabs,
    today: LocalDate,
    nowMinute: Int,
    zone: ZoneId,
): ReportSection? {
    if (ReportPart.LABS !in request.parts) return null
    // The minute matters only on the day the sheet is made; a period that ended earlier asked for
    // every dose of its last day.
    val sheet = LabSheet(sources, request, labs, if (request.to == today) nowMinute else MINUTES_A_DAY, zone)
    val responses = responsesOnSheet(sheet)
    val rows = drawRows(sheet) + responses.flatMap { (course, pairs) -> courseRows(sheet, course, pairs) }
    return rows.takeIf { it.isNotEmpty() }?.let {
        ReportSection(
            ReportPart.LAB_CONTEXT,
            it,
            caveatRes = R.string.report_caveat_lab_context,
            footnotes = footnotes(sheet, responses.flatMap { pair -> pair.second }, it),
        )
    }
}

/** What every part of the section reads, worked out once. */
private class LabSheet(
    val sources: ReportSources,
    val request: ReportRequest,
    val labs: ReportLabs,
    val nowMinute: Int,
    val zone: ZoneId,
) {
    val sex: String = sources.profile.sex
    val imperial: Boolean = request.imperial(sources.profile)
    val until: LocalDate = request.to

    fun ticked(part: ReportPart): Boolean = part in request.parts

    /** What the blood-test section prints of each marker: its newest panel result and the ones under it. */
    val printed: Map<LabMarker, List<LabReading>> = labs
        .mapValues { (_, readings) -> readings.filter(LabReading::fromPanel).takeLast(PRINTED_RESULTS) }
        .filterValues { it.isNotEmpty() }

    /** The result of the same marker the blood-test section prints before [reading], if it prints one. */
    fun earlierResult(reading: LabReading): LabReading? {
        val results = printed[reading.marker].orEmpty()
        return results.getOrNull(results.indexOf(reading) - 1)
    }

    /** The precision each marker's figures print at on the blood-test section, so both print alike. */
    val places: Map<LabMarker, Int> = printed.mapValues { (marker, readings) ->
        marker.printPlaces(readings, sex, imperial)
    }

    /** Markers with two panel results or more up to the end of the period, one of them inside it. */
    val compared: Set<LabMarker> = labs.filterValues { readings ->
        val panels = readings.filter(LabReading::fromPanel)
        panels.size >= 2 && panels.any { it.day in request.from.toString()..request.to.toString() }
    }.keys

    val record: ResponseRecord by lazy {
        ResponseRecord(sources.medications, labs, sources.entries, until, nowMinute, zone)
    }

    /** Days a count was carried, at the larger count where a day came twice — the activity section's rule. */
    val steps: Map<LocalDate, Long> by lazy {
        sources.days.groupBy(DaySteps::day)
            .mapNotNull { (day, rows) -> day.asDay()?.let { it to rows.maxOf(DaySteps::steps) } }
            .filter { it.second > 0L }
            .toMap()
    }

    val weights: List<WeightReading> by lazy { weightReadings(sources.entries) }

    /**
     * Days with a row somebody typed in: the days an absent drink can be read as none. A day where only
     * a watch wrote a pulse is not a day the person was keeping the diary, and nor is the day of a
     * laboratory sheet copied in afterwards under its own date.
     *
     * Nor does a sexual act, a solo one or a morning check-in mark a day, whatever the sheet ticks:
     * the solo ones and the mornings are never listed by date on any sheet — counted by week or by
     * stretch of the period at most — the dated acts only inside the period while a draw's window can
     * reach before it, and a day counted here would carry them onto the sheet through the alcohol line.
     *
     * Nor does the work timer's own row: a Start and a Stop write nothing else down, and every day
     * with only a session on it would read as a day without a drink.
     *
     * It is the changes section's own set ([keptDays]) and not a second copy of its rule, so the same
     * drinks come out alike here and in the changes section of the same sheet.
     */
    val typedDays: Set<LocalDate> by lazy { keptDays(sources.entries) }

    val markedEver: Set<Long> by lazy { sources.entries.mapNotNullTo(HashSet()) { medSlotOf(it)?.first } }

    fun entriesIn(from: LocalDate, to: LocalDate): List<JournalEntry> =
        sources.entries.filter { it.day in from.toString()..to.toString() }

    /** The minute a day's doses are counted up to: all of them, except on the day the sheet is made. */
    fun minuteOn(day: LocalDate): Int = if (day == until) nowMinute else MINUTES_A_DAY

    /** "38 nmol/L", at the precision the blood-test section prints the marker at. */
    fun figure(reading: LabReading): ReportText {
        val marker = reading.marker
        val at = places[marker] ?: marker.printPlaces(listOf(reading), sex, imperial)
        return res(
            R.string.report_value_unit,
            marker.printed(reading, sex, imperial, at),
            res(marker.unitLabelRes(imperial)),
        )
    }
}

// --- each draw -------------------------------------------------------------------------------------

/**
 * One block per day of the draws the sheet prints: a line naming what was drawn, the diary around it
 * where a marker drawn that day has two results or more on file ([LabSheet.compared]) — its oldest
 * draw included, which has no earlier result to stand beside — and the notes its doses call for.
 * Oldest first, so the blocks read as the record ran.
 */
private fun drawRows(sheet: LabSheet): List<ReportRow> {
    val byDay = sheet.printed.values.flatten().groupBy(LabReading::day).toSortedMap()
    return byDay.flatMap { (text, drawn) ->
        val day = text.asDay() ?: return@flatMap emptyList<ReportRow>()
        val compared = drawn.filter { it.marker in sheet.compared }
        val context = if (compared.isEmpty()) emptyList() else contextRows(sheet, day, compared)
        val below = context + safetyRows(sheet, day, drawn)
        if (below.isEmpty()) emptyList<ReportRow>() else listOf(headerRow(day, drawn)) + below
    }
}

/**
 * The day and how many results it holds. The figures themselves are printed once, in the blood-test
 * section above, where each stands with its band: repeating a dozen of them here would bury the
 * diary lines this section is for.
 */
private fun headerRow(day: LocalDate, drawn: List<LabReading>): ReportRow {
    val fasting = drawn.mapNotNull(LabReading::fastingRes).distinct().map { res(it) }
    return ReportRow(
        label = ReportText.Day(day),
        value = ReportText.Quantity(R.plurals.count_readings, drawn.size),
        note = fasting.takeIf { it.isNotEmpty() }?.let { ReportText.Join(it) },
    )
}

/**
 * The diary in the days up to the draw: the longest window a marker drawn that day is formed over
 * ([LAB_DIET_WINDOWS]), or [CONTEXT_DAYS] where none of them has one. The workouts and the flow keep
 * windows of their own, and stand only beside the markers their research is about.
 */
private fun contextRows(sheet: LabSheet, day: LocalDate, compared: List<LabReading>): List<ReportRow> {
    val window = compared.mapNotNull { LAB_DIET_WINDOWS[it.marker]?.windowDays }.maxOrNull() ?: CONTEXT_DAYS
    val from = day.minusDays(window - 1L)
    return buildList {
        if (sheet.ticked(ReportPart.NUTRITION)) addAll(foodRows(sheet, compared))
        if (sheet.ticked(ReportPart.ACTIVITY)) {
            add(stepsRow(sheet, from, day, window))
            if (compared.any { it.marker in WORKOUT_MARKERS }) add(workoutsRow(sheet, day))
        }
        if (sheet.sex == "FEMALE" && sheet.ticked(ReportPart.CYCLE) && compared.any { it.marker in FLOW_MARKERS }) {
            add(flowRow(sheet, day))
        }
        if (sheet.ticked(ReportPart.WEIGHT)) add(weightRow(sheet, day))
        if (sheet.ticked(ReportPart.SOCIAL)) add(alcoholRow(sheet, from, day, window))
        if (sheet.ticked(ReportPart.MEDICATIONS)) {
            addAll(runningRows(sheet, from, day, window))
            startedRow(sheet, compared, day)?.let(::add)
        }
        methodRow(sheet, compared)?.let(::add)
    }
}

/**
 * The food window of each marker drawn that day that has one, once per window: LDL, total and
 * non-HDL cholesterol share theirs. A window the diary cannot describe is refused with its counts.
 *
 * Alcohol is the one nutrient read before GGT and urate, and it is not printed here. The window
 * averages a nutrient over the days that carry it, which for a drink are the days one was logged: a
 * glass every fourth day would come out as that glass "a day", beside a count of the days food was logged
 * on, and against [alcoholRow] under it. That row is the sheet's one figure for the drinks before a
 * draw — over every day the diary was kept, and only with the social history. A window that reads
 * nothing else is left out whole: a line saying its days carry too little would give a false reason
 * for a line left out on purpose.
 */
private fun foodRows(sheet: LabSheet, compared: List<LabReading>): List<ReportRow> = compared
    .mapNotNull { reading -> LAB_DIET_WINDOWS[reading.marker]?.let { reading to it } }
    .distinctBy { it.second }
    .filter { (_, window) -> window.factors.any { it.labelRes != R.string.labs_diet_ethanol } }
    .mapNotNull { (reading, window) ->
        foodBeforeDraw(sheet.sources.entries, reading)?.let { result ->
            val readings = result.readings.filter { it.labelRes != R.string.labs_diet_ethanol }
            val logged = res(
                R.string.report_count_of,
                result.daysLogged,
                ReportText.Quantity(R.plurals.count_days, result.windowDays),
            )
            val value = when {
                !result.enough -> res(R.string.report_lab_context_not_enough, logged)
                readings.isEmpty() -> res(R.string.report_lab_context_food_no_columns)
                else -> ReportText.Join(readings.map { perDay(res(it.labelRes), grams(it.meanPerDay)) })
            }
            // A window with a half-life leans towards the draw, and says so beside its count.
            val weighted = res(R.string.report_lab_context_weighted).takeIf { window.halfLifeDays != null }
            // Which results this window stands before: LDL, total and non-HDL cholesterol share one.
            val markers = compared.filter { LAB_DIET_WINDOWS[it.marker] == window }.map { res(it.marker.titleRes) }
            ReportRow(
                label = ReportText.Quantity(R.plurals.report_lab_context_food_window, result.windowDays),
                value = value,
                note = ReportText.Join(markers + listOfNotNull(logged.takeIf { result.enough }, weighted)),
                detail = true,
            )
        }
    }

/**
 * Steps a day over the days in the window a counter was carried, and how many those were. The mean
 * is rounded as the changes section rounds its own: cut down to a whole step, a window could print
 * a step fewer here than there.
 */
private fun stepsRow(sheet: LabSheet, from: LocalDate, day: LocalDate, window: Int): ReportRow {
    val carried = sheet.steps.filterKeys { it in from..day }.values
    return ReportRow(
        label = ReportText.Quantity(R.plurals.report_lab_context_steps_window, window),
        value = if (carried.isEmpty()) {
            res(R.string.report_lab_context_not_recorded)
        } else {
            res(R.string.report_steps_per_day, carried.average().roundToLong().sheetCount())
        },
        note = carried.takeIf { it.isNotEmpty() }?.let { ReportText.Quantity(R.plurals.count_days, it.size) },
        detail = true,
    )
}

/**
 * The workouts logged 1 to [WORKOUT_DAYS] days before the draw, by day, oldest first. The draw day is
 * left out: a laboratory row carries no time of the draw, so a workout that day cannot be put before
 * or after it.
 *
 * Each by its title as logged, never its activity code, a type or an intensity: an import falls back
 * to a default code where its file named none, and a routine's Start writes the rows of its template
 * before any of it is done. A row says only that a workout of that name was logged, which is what the
 * activity section counts too; a plan is a kind of its own and is not read.
 */
private fun workoutsRow(sheet: LabSheet, day: LocalDate): ReportRow {
    val titles = sheet.entriesIn(day.minusDays(WORKOUT_DAYS.toLong()), day.minusDays(1))
        .filter { it.kind == KIND_ACTIVITY && it.title.isNotBlank() }
        .sortedBy(JournalEntry::occurredAt)
        .mapNotNull { entry -> entry.day.asDay()?.let { it to entry.title } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
    return ReportRow(
        label = ReportText.Quantity(R.plurals.report_lab_context_workouts_window, WORKOUT_DAYS),
        value = if (titles.isEmpty()) {
            res(R.string.report_lab_context_not_recorded)
        } else {
            ReportText.Join(
                titles.map { (on, names) ->
                    res(
                        R.string.report_lab_context_at,
                        ReportText.Quantity(R.plurals.visit_remind_days, daysBetween(on, day)),
                        ReportText.Join(names.distinct().map(ReportText::Plain), ", "),
                    )
                },
                "; ",
            )
        },
        detail = true,
    )
}

/**
 * The days answered with heavy flow in the [BLEEDING_DAYS] up to the draw, the draw day included, of
 * the days any flow was answered. An answer is optional, so a day without one is not a day without
 * flow, and a window with none says so rather than printing nought. Spotting is left out, as it is
 * from the rungs of [CycleScale.FLOW]: it is a kind of bleeding rather than an amount of it.
 */
private fun flowRow(sheet: LabSheet, day: LocalDate): ReportRow {
    val flow = sheet.entriesIn(day.minusDays(BLEEDING_DAYS - 1L), day)
        .filter { it.cycleLog() in CycleScale.FLOW.logs }
    val answered = flow.map(JournalEntry::day).distinct().size
    val heavy = flow.filter { it.cycleLog() == CycleLog.FLOW_HEAVY }.map(JournalEntry::day).distinct().size
    return ReportRow(
        label = ReportText.Quantity(R.plurals.report_lab_context_heavy_flow_window, BLEEDING_DAYS),
        value = if (answered == 0) {
            res(R.string.report_lab_context_not_recorded)
        } else {
            res(R.string.report_count_of, heavy, ReportText.Quantity(R.plurals.report_lab_context_flow_days, answered))
        },
        detail = true,
    )
}

/**
 * The weigh-in nearest the draw on either side of it, within [WEIGH_IN_DAYS], with its own date. Of
 * two as near, the one before it, as every other line here reads the days before the sample, rather
 * than whichever came first.
 */
private fun weightRow(sheet: LabSheet, day: LocalDate): ReportRow {
    val nearest = sheet.weights
        .mapNotNull { reading -> reading.day.asDay()?.let { it to reading } }
        .filter { (on, _) -> abs(daysBetween(on, day)) <= WEIGH_IN_DAYS }
        .minWithOrNull(compareBy({ (on, _) -> abs(daysBetween(on, day)) }, { (on, _) -> on > day }))
    return ReportRow(
        label = res(R.string.weight),
        value = nearest?.let { (_, reading) ->
            val shown = if (sheet.imperial) imperialWeight(reading.kg) else reading.kg
            res(
                R.string.report_value_unit,
                shown.pretty(),
                res(if (sheet.imperial) R.string.unit_lb else R.string.unit_kg),
            )
        } ?: res(R.string.report_lab_context_no_weigh_in),
        note = nearest?.let { ReportText.Day(it.first) },
        detail = true,
    )
}

/**
 * Grams of ethanol a week over the days in the window somebody typed a row in, and how many those
 * were: a day nobody kept the diary is not a day without a drink, and a day only a device wrote to
 * is not one they kept it.
 */
private fun alcoholRow(sheet: LabSheet, from: LocalDate, day: LocalDate, window: Int): ReportRow {
    val typed = sheet.typedDays.filter { it in from..day }.toSet()
    val grams = sheet.entriesIn(from, day)
        .filter { entry -> entry.kind == KIND_ALCOHOL && entry.day.asDay()?.let { it in typed } == true }
        .sumOf(JournalEntry::servingG)
    return ReportRow(
        label = ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, window),
        value = if (typed.isEmpty()) {
            res(R.string.report_lab_context_not_recorded)
        } else {
            res(
                R.string.report_lab_context_per_week,
                res(
                    R.string.report_value_unit,
                    (grams / typed.size * DAYS_IN_A_WEEK).roundToInt(),
                    res(R.string.unit_grams),
                ),
            )
        },
        note = typed.size.takeIf { it > 0 }?.let { ReportText.Quantity(R.plurals.report_lab_context_typed_days, it) },
        detail = true,
    )
}

/**
 * How many courses ran in the window, and each of them with its dates and the doses marked in it.
 * The count is a count, not a judgement: several at once is what a doctor asks about, and how many
 * is theirs to weigh.
 */
private fun runningRows(sheet: LabSheet, from: LocalDate, day: LocalDate, window: Int): List<ReportRow> {
    val running = sheet.sources.medications.filter { it.ranOn(from, day) }
    if (running.isEmpty()) return emptyList()
    val journal = sheet.entriesIn(from, day)
    return listOf(
        ReportRow(res(R.string.report_lab_context_courses), ReportText.Plain(running.size.toString()), detail = true),
    ) + running.map { course ->
        val marked = adherenceOf(medAdherence(listOf(course), journal, day, window, sheet.minuteOn(day), sheet.zone))
        ReportRow(
            label = ReportText.Plain(course.name),
            value = ReportText.Join(
                listOfNotNull(course.dose.takeIf(String::isNotBlank)?.let(ReportText::Plain), courseDates(course)),
            ),
            note = markedText(marked, course.id in sheet.markedEver),
            detail = true,
        )
    }
}

/**
 * The courses that began after the earlier result of a marker drawn that day and by this draw, the
 * widest gap among them: the draw before this one of another marker is not where these results'
 * pairs start, and a course begun between two LDLs would be lost under a TSH drawn in between. Each with
 * its dates, so one that began and stopped between the draws says so.
 */
private fun startedRow(sheet: LabSheet, compared: List<LabReading>, day: LocalDate): ReportRow? {
    val previous = compared.mapNotNull { sheet.earlierResult(it)?.day?.asDay() }.minOrNull()
    val started = previous?.let { after ->
        sheet.sources.medications.filter { course -> course.startDay.asDay()?.let { it > after && it <= day } == true }
    }.orEmpty()
    return started.takeIf { it.isNotEmpty() }?.let { courses ->
        ReportRow(
            label = res(R.string.report_lab_context_started),
            value = ReportText.Join(
                courses.map { res(R.string.report_changes_item, ReportText.Plain(it.name), courseDates(it)) },
                ", ",
            ),
            detail = true,
        )
    }
}

/** A result whose sheet does not name the laboratory or method the one before it named. */
private fun methodRow(sheet: LabSheet, compared: List<LabReading>): ReportRow? = compared.filter { reading ->
    val earlier = sheet.earlierResult(reading)
    earlier != null && !sameLabMethod(earlier, reading)
}.takeIf { it.isNotEmpty() }?.let { differ ->
    // Once for the draw, naming the markers: a panel from a new laboratory differs for all of them.
    ReportRow(
        label = res(R.string.labs_method),
        value = ReportText.Join(differ.map { res(it.marker.titleRes) }),
        note = res(R.string.report_lab_context_method_differs),
        detail = true,
    )
}

// --- the doses beside a draw ---------------------------------------------------------------------

/**
 * What the courses' marked doses held in the days before one draw: the mean a day over the month and
 * the week before it ([supplementWindow], over the days a course asked), and the most marked on the
 * day of the draw or the day before.
 */
private class DoseWindows(
    val month: Map<Nutrient, IntakeWindow>,
    val week: Map<Nutrient, IntakeWindow>,
    val nearDraw: Map<Nutrient, Double>,
) {
    fun monthMean(nutrient: Nutrient): Double = month[nutrient]?.meanMarked ?: 0.0

    fun weekMean(nutrient: Nutrient): Double = week[nutrient]?.meanMarked ?: 0.0
}

private fun doseWindows(sheet: LabSheet, day: LocalDate): DoseWindows {
    val monthFrom = day.minusDays(SAFETY_MONTH_DAYS - 1L)
    val journal = sheet.entriesIn(monthFrom, day)
    fun intake(from: LocalDate) =
        supplementIntake(sheet.sources.medications, journal, from, day, sheet.until, sheet.minuteOn(day), sheet.zone)
    val month = intake(monthFrom)
    val dayBefore = day.minusDays(1)
    return DoseWindows(
        month = supplementWindow(month),
        week = supplementWindow(intake(day.minusDays(SAFETY_WEEK_DAYS - 1L))),
        nearDraw = month.mapValues { (_, days) ->
            days.filterKeys { it >= dayBefore }.values.maxOfOrNull(NutrientDay::marked) ?: 0.0
        },
    )
}

/**
 * Two things recorded together and one published fact, never more: a dose and a sample, and what a
 * named body said about the pair. Only from courses whose label was written down and doses that were
 * marked — a name is never read as a nutrient — and only with the medicines on the sheet.
 *
 * An intake above an upper level is said beside a draw of the markers it is read with, not under
 * every draw: vitamin D beside 25-hydroxyvitamin D, vitamin A beside the liver enzymes, iron beside
 * ferritin, transferrin saturation or haemoglobin. Vitamin B6 has no marker the app holds, so its
 * note goes with the medicines alone.
 */
private fun safetyRows(sheet: LabSheet, day: LocalDate, drawn: List<LabReading>): List<ReportRow> {
    if (!sheet.ticked(ReportPart.MEDICATIONS) || sheet.sources.medications.all { it.contents.isBlank() }) {
        return emptyList()
    }
    val doses = doseWindows(sheet, day)
    val age = ageOn(day.toString(), sheet.sources.profile.birthDate)
    val on = drawn.associateBy(LabReading::marker)
    fun beside(vararg markers: LabMarker) = markers.any { it in on }
    return listOfNotNull(
        biotinNote(doses, drawn),
        creatineNote(doses, on),
        overUpperLevel(doses, Nutrient.VITAMIN_D, age, ReferenceBody.NASEM, R.string.safety_vitamin_d_intake)
            ?.takeIf { beside(LabMarker.VITAMIN_D) },
        raisedWhileMarked(sheet, doses, on[LabMarker.VITAMIN_D], Nutrient.VITAMIN_D, R.string.safety_vitamin_d_level),
        overUpperLevel(doses, Nutrient.VITAMIN_B6, age, ReferenceBody.EFSA, R.string.safety_vitamin_b6),
        overUpperLevel(doses, Nutrient.VITAMIN_A, age, ReferenceBody.NASEM, R.string.safety_vitamin_a)
            ?.takeIf { beside(LabMarker.ALT, LabMarker.AST, LabMarker.GGT) },
        overUpperLevel(doses, Nutrient.IRON, age, ReferenceBody.NASEM, R.string.safety_iron_intake)
            ?.takeIf { beside(LabMarker.FERRITIN, LabMarker.TSAT, LabMarker.HEMOGLOBIN) },
        raisedWhileMarked(sheet, doses, on[LabMarker.FERRITIN], Nutrient.IRON, R.string.safety_iron_ferritin),
        folicAcidNote(sheet, doses, age, day),
        vitaminCNote(sheet, doses, age, on[LabMarker.EGFR]),
        b12CourseNote(doses, on[LabMarker.B12]),
    )
}

/**
 * Biotin at [BIOTIN_INTERFERENCE_UG] or more on the day of the draw or the day before, beside a
 * marker whose assays it is known to disturb. AACC's guidance (Li et al., J Appl Lab Med 2020): 5 mg a
 * day or more can interfere, and a wait of at least 8 hours is asked after 5–10 mg; sandwich assays
 * read falsely low (TSH, ferritin), competitive ones falsely high (FT4, 25-hydroxyvitamin D, B12,
 * folate), depending on the platform. FDA Safety Communication 2017, updated 2019.
 */
private fun biotinNote(doses: DoseWindows, drawn: List<LabReading>): ReportRow? {
    val biotin = doses.nearDraw[Nutrient.BIOTIN] ?: 0.0
    val affected = drawn.filter { it.marker in BIOTIN_MARKERS }
    return if (biotin < BIOTIN_INTERFERENCE_UG || affected.isEmpty()) {
        null
    } else {
        note(
            Nutrient.BIOTIN,
            res(
                R.string.safety_biotin,
                nutrientAmountText(Nutrient.BIOTIN, biotin),
                ReportText.Join(affected.map { res(it.marker.titleRes) }, ", "),
            ),
        )
    }
}

/** KDIGO 2024 (its table of non-GFR determinants of serum creatinine): creatine supplements. */
private fun creatineNote(doses: DoseWindows, on: Map<LabMarker, LabReading>): ReportRow? {
    val creatine = doses.weekMean(Nutrient.CREATINE)
    val kidney = LabMarker.CREATININE in on || LabMarker.EGFR in on
    return if (creatine <= 0.0 || !kidney) {
        null
    } else {
        note(Nutrient.CREATINE, res(R.string.safety_creatine, nutrientAmountText(Nutrient.CREATINE, creatine)))
    }
}

/**
 * A month's marked mean a day above an upper level [Nutrient.upperLevels] gives for this age — the
 * figure is never typed here — from the body the sentence names.
 */
private fun overUpperLevel(
    doses: DoseWindows,
    nutrient: Nutrient,
    age: Int,
    body: ReferenceBody,
    sentence: Int,
): ReportRow? {
    val level = nutrient.upperLevels(age).firstOrNull { it.body == body }
    val mean = doses.monthMean(nutrient)
    return if (level == null || mean <= level.amount) {
        null
    } else {
        val over = nutrientAmountText(nutrient, mean, above = level.amount)
        note(nutrient, res(sentence, over, nutrientAmountText(nutrient, level.amount)))
    }
}

/**
 * A result above its band's ceiling on a sample drawn while doses of the matching nutrient were
 * marked in the month before: 25-hydroxyvitamin D over the IOM's 125 nmol/L, or ferritin over WHO
 * 2020's 150 or 200 µg/L. The edge is the band's own, printed the way the blood-test section prints it.
 */
private fun raisedWhileMarked(
    sheet: LabSheet,
    doses: DoseWindows,
    reading: LabReading?,
    nutrient: Nutrient,
    sentence: Int,
): ReportRow? {
    val raised = reading?.takeIf { it.status in RAISED_STATUSES && doses.monthMean(nutrient) > 0.0 }
    val edge = raised?.let { found ->
        val marker = found.marker
        marker.bandFor(sheet.sex, found.ageYears, found.fasting)
            ?.let {
                marker.printedEdges(it, sheet.imperial, sheet.places[marker] ?: marker.decimals(sheet.imperial)).second
            }
            ?.let { res(R.string.report_value_unit, it, res(marker.unitLabelRes(sheet.imperial))) }
    }
    return if (raised == null || edge == null) null else note(nutrient, res(sentence, sheet.figure(raised), edge))
}

/**
 * Folic acid above NASEM's upper level for the synthetic form, with the newest vitamin B12 on file up
 * to the draw below its NICE NG239 band, or no B12 on file at all.
 */
private fun folicAcidNote(sheet: LabSheet, doses: DoseWindows, age: Int, day: LocalDate): ReportRow? {
    val level = Nutrient.FOLIC_ACID.upperLevels(age).firstOrNull { it.body == ReferenceBody.NASEM }
    val mean = doses.monthMean(Nutrient.FOLIC_ACID)
    val b12 = sheet.labs[LabMarker.B12].orEmpty().lastOrNull { it.day <= day.toString() }
    val onFile = b12?.let { reading ->
        val verdict = reading.status.reportVerdictRes(
            reading.marker.bandFor(sheet.sex, reading.ageYears, reading.fasting),
        )
        val drawn = res(R.string.safety_b12_on_file, sheet.figure(reading), dayOf(reading.day))
        ReportText.Join(listOfNotNull(drawn, verdict?.let { res(it) }))
    } ?: res(R.string.safety_no_b12)
    val limit = level?.amount?.takeIf { mean > it }
    val b12Below = b12 == null || b12.status in LOWERED_STATUSES
    return if (limit == null || !b12Below) {
        null
    } else {
        note(
            Nutrient.FOLIC_ACID,
            res(
                R.string.safety_folic_acid,
                nutrientAmountText(Nutrient.FOLIC_ACID, mean, above = limit),
                nutrientAmountText(Nutrient.FOLIC_ACID, limit),
                onFile,
            ),
        )
    }
}

/** Vitamin C above NASEM's upper level with an eGFR on the same day below its band (KDIGO's G3a, under 60). */
private fun vitaminCNote(sheet: LabSheet, doses: DoseWindows, age: Int, egfr: LabReading?): ReportRow? {
    val level = Nutrient.VITAMIN_C.upperLevels(age).firstOrNull { it.body == ReferenceBody.NASEM }
    val mean = doses.monthMean(Nutrient.VITAMIN_C)
    val low = egfr?.takeIf { it.status in LOWERED_STATUSES }
    return if (level == null || mean <= level.amount || low == null) {
        null
    } else {
        note(
            Nutrient.VITAMIN_C,
            res(
                R.string.safety_vitamin_c,
                nutrientAmountText(Nutrient.VITAMIN_C, mean, above = level.amount),
                nutrientAmountText(Nutrient.VITAMIN_C, level.amount),
                sheet.figure(low),
            ),
        )
    }
}

/** NICE NG239 (2024), rationale for 1.3.8: supplements raise B12 and can mask a deficiency. */
private fun b12CourseNote(doses: DoseWindows, b12: LabReading?): ReportRow? =
    if (b12 == null || doses.weekMean(Nutrient.VITAMIN_B12) <= 0.0) {
        null
    } else {
        note(Nutrient.VITAMIN_B12, res(R.string.safety_b12_course))
    }

private fun note(nutrient: Nutrient, text: ReportText): ReportRow = ReportRow(
    res(nutrient.titleRes),
    text,
    detail = true,
)

// --- each course ----------------------------------------------------------------------------------

/**
 * Each course whose label names a nutrient paired with a marker, and its pairs, where the course ran
 * during the period or a result read as its own was drawn in it. Only with the medicines on the sheet:
 * the course's name and doses are that section's.
 */
private fun responsesOnSheet(sheet: LabSheet): List<Pair<Medication, List<SupplementResponse>>> {
    if (!sheet.ticked(ReportPart.MEDICATIONS)) return emptyList()
    val from = sheet.request.from
    val period = from.toString()..sheet.until.toString()
    return sheet.sources.medications.filter { it.contents.isNotBlank() }.mapNotNull { course ->
        supplementResponses(course, sheet.record, from, withFood = sheet.ticked(ReportPart.NUTRITION))
            .filter { course.ranOn(from, sheet.until) || it.after?.day?.let { day -> day in period } == true }
            .takeIf { it.isNotEmpty() }
            ?.let { course to it }
    }
}

/**
 * The course with the amount a day of each nutrient paired, and its dates; the courses holding the
 * same nutrient whose dates overlap it, added up; and under it each marker's lines, the doses marked
 * up to the result read, and the nutrient in the food before each result.
 */
private fun courseRows(sheet: LabSheet, course: Medication, responses: List<SupplementResponse>): List<ReportRow> {
    val byNutrient = responses.groupBy { it.pairing.nutrient }
    val header = ReportRow(
        label = ReportText.Plain(course.name),
        value = ReportText.Join(
            byNutrient.map { (nutrient, pairs) ->
                pairs.first().perDay?.let { perDay(res(nutrient.titleRes), nutrientAmountText(nutrient, it)) }
                    ?: res(R.string.supplement_amount_not_computed)
            },
        ),
        note = courseDates(course),
    )
    val overlaps = byNutrient.mapNotNull { (nutrient, pairs) ->
        val first = pairs.first()
        first.overlapping.takeIf { it.isNotEmpty() }?.let { others ->
            ReportRow(
                label = res(R.string.supplement_overlap_label),
                value = first.combinedPerDay?.let { perDay(res(nutrient.titleRes), nutrientAmountText(nutrient, it)) }
                    ?: res(R.string.supplement_amount_not_computed),
                note = ReportText.Join(others.map { ReportText.Plain(it.name) }, ", "),
                detail = true,
            )
        }
    }
    return listOf(header) + overlaps + responses.flatMap { pairRows(sheet, it) }
}

/**
 * One marker's lines under a course. The sheet names a missing amount once, on the course's own line
 * above, so the flag the course page prints it by is not repeated under each marker here.
 */
private fun pairRows(sheet: LabSheet, response: SupplementResponse): List<ReportRow> {
    val lines = response.copy(flags = response.flags - ResponseFlag.AMOUNT_NOT_COMPUTED)
        .responseLines(sheet.sex, sheet.imperial)
    // The figures on the marker's own line, and each qualification on a line of its own under it:
    // run together they would be one paragraph a reader has to take apart.
    return listOf(ReportRow(res(response.pairing.marker.titleRes), lines.first(), detail = true)) +
        lines.drop(1).map { ReportRow(ReportText.Plain(""), it, detail = true) } +
        responseDetailRows(response)
}

private fun responseDetailRows(response: SupplementResponse): List<ReportRow> = listOfNotNull(
    ReportRow(
        label = res(R.string.report_lab_context_doses_marked_label),
        value = markedText(response.marked, response.everMarked),
        note = response.marked?.let {
            res(R.string.report_period_value, ReportText.Day(response.countedFrom), ReportText.Day(response.countedTo))
        },
        detail = true,
    ),
    response.food.takeIf { it.isNotEmpty() }?.let { windows ->
        val nutrient = response.pairing.food.nutrient ?: response.pairing.nutrient
        ReportRow(
            label = res(R.string.supplement_food_label, res(nutrient.titleRes)),
            value = ReportText.Join(
                windows.map {
                    res(R.string.report_lab_context_at, ReportText.Day(it.day), foodMicroText(it.summary, nutrient))
                },
                "; ",
            ),
            note = ReportText.Quantity(R.plurals.supplement_food_window, SUPPLEMENT_FOOD_DAYS),
            detail = true,
        )
    },
)

/**
 * The nutrient from food in the window before a result: a mean over the days it is known on, refused
 * with its counts under [FOOD_MICROS_MIN_DAYS] of them, and "not recorded" — never nought — where no
 * day in the window holds a logged day of food.
 */
private fun foodMicroText(summary: FoodMicroSummary, nutrient: Nutrient): ReportText {
    val counted = res(
        R.string.report_count_of,
        summary.countedDays,
        ReportText.Quantity(R.plurals.count_days, SUPPLEMENT_FOOD_DAYS),
    )
    val mean = summary.meanPerDay
    return when {
        summary.loggedDays == 0 -> res(R.string.report_lab_context_not_recorded)
        mean == null -> res(R.string.supplement_food_not_enough, counted)
        else -> res(R.string.report_lab_context_food_mean, perDay(null, nutrientAmountText(nutrient, mean)), counted)
    }
}

/**
 * Doses marked of those asked, as counts rather than a percentage. A course nobody ever marked a dose
 * of says so — it was written down to keep a record of it, not refused — and a window that asked for
 * nothing, before the course was written down, says that instead.
 */
private fun markedText(marked: Adherence?, everMarked: Boolean): ReportText = when {
    marked == null -> res(R.string.report_lab_context_no_doses_asked)

    !everMarked -> res(R.string.report_lab_context_not_marked)

    else -> res(
        R.string.report_lab_context_marked,
        marked.taken,
        ReportText.Quantity(R.plurals.report_lab_context_doses, marked.planned),
    )
}

private fun courseDates(course: Medication): ReportText = res(
    R.string.report_period_value,
    dayOf(course.startDay),
    course.endDay?.let(::dayOf) ?: res(R.string.report_course_ongoing),
)

// --- the notes -----------------------------------------------------------------------------------

/**
 * The notes that are about the tests and published research rather than the person: how far two
 * results of one test wander in somebody whose health has not changed, for the markers the section
 * sets side by side; where a vitamin D course is printed, the published average response to a dose;
 * and where a workouts or a flow line lists something, the study about other people behind it.
 *
 * A course adds its marker to the first note only where its two results are printed as a before and
 * an after. A pair shown beside the course — B12, folate, magnesium, transferrin saturation — is shown
 * that way because a before and an after mean little for it, and "more than the test's wobble" set
 * near it would read as a verdict; a result drawn too early is printed alone.
 */
private fun footnotes(sheet: LabSheet, responses: List<SupplementResponse>, rows: List<ReportRow>): List<ReportText> {
    val paired = responses.filter { it.state == ResponseState.COMPARED }.map { it.pairing.marker }
    val markers = LabMarker.entries.filter { (it in sheet.compared || it in paired) && it in WITHIN_PERSON_CV }

    // A "Not recorded" line has nothing the research speaks to.
    fun lists(plural: Int) = rows.any {
        (it.label as? ReportText.Quantity)?.id == plural && it.value != res(R.string.report_lab_context_not_recorded)
    }
    return listOfNotNull(
        markers.takeIf { it.isNotEmpty() }?.let { shown ->
            val spans = shown.map { marker ->
                val (up, down) = referenceChange(WITHIN_PERSON_CV.getValue(marker))
                res(R.string.report_lab_context_rcv_marker, up, down, res(marker.titleRes))
            }
            res(R.string.report_lab_context_rcv, ReportText.Join(spans, "; "))
        },
        res(R.string.report_lab_context_heaney).takeIf { responses.any { it.pairing == SupplementPairing.VITAMIN_D } },
        res(R.string.report_lab_context_background_workouts, res(R.string.report_lab_context_source_workouts))
            .takeIf { lists(R.plurals.report_lab_context_workouts_window) },
        res(R.string.report_lab_context_background_flow, res(R.string.report_lab_context_source_flow))
            .takeIf { lists(R.plurals.report_lab_context_heavy_flow_window) },
    )
}

/**
 * The reference change value in whole percent, up and down: how far apart two results of one test can
 * be, in 19 pairs of 20, in somebody whose health has not changed.
 *
 * Log-normal (Fokkema et al. 2006, Clin Chem 52:1602), with Z = 1.96 and the laboratory's own
 * imprecision assumed at half the within-person variation — EFLM's "desirable" analytical
 * performance, because the person's laboratory is not known. The sheet says it is assumed.
 */
internal fun referenceChange(withinPersonCvPercent: Double): Pair<Int, Int> {
    val biological = withinPersonCvPercent / PERCENT
    val analytical = biological * ASSUMED_ANALYTICAL_SHARE
    val sigma = sqrt(ln(1 + biological * biological) + ln(1 + analytical * analytical))
    val z = Z_95 * sqrt(2.0) * sigma
    return ((exp(z) - 1) * PERCENT).roundToInt() to ((1 - exp(-z)) * PERCENT).roundToInt()
}

/**
 * Within-person coefficients of variation, percent: the median meta-estimates of the EFLM Biological
 * Variation Database (biologicalvariation.eu, meta_calculations, accessed 23 September 2026), serum
 * or plasma, haemoglobin whole blood.
 *
 * Left out: HbA1c, whose within-person figure (1.17%) is smaller than the 1.5–2% a laboratory's own
 * imprecision usually is, so an assumed half of it would print a range narrower than the assay; the
 * three figures the app derives — non-HDL, HOMA-IR, eGFR — which have no entry of their own; platelets,
 * which the database does not return under that name; and transferrin saturation, for which no
 * figure is taken.
 */
private val WITHIN_PERSON_CV: Map<LabMarker, Double> = mapOf(
    LabMarker.GLUCOSE to 4.7,
    LabMarker.INSULIN to 25.4,
    LabMarker.CHOLESTEROL to 5.3,
    LabMarker.LDL to 7.4,
    LabMarker.HDL to 5.7,
    LabMarker.TRIGLYCERIDES to 19.8,
    LabMarker.LP_A to 10.2,
    LabMarker.CREATININE to 4.39,
    LabMarker.UREA to 13.1,
    LabMarker.URIC_ACID to 8.25,
    LabMarker.ALT to 12.64,
    LabMarker.AST to 8.6,
    LabMarker.ALP to 5.2,
    LabMarker.GGT to 8.29,
    LabMarker.BILIRUBIN to 20.2,
    LabMarker.HEMOGLOBIN to 2.67,
    LabMarker.FERRITIN to 12.87,
    LabMarker.CRP to 34.7,
    LabMarker.TSH to 17.8,
    LabMarker.FT4 to 4.8,
    LabMarker.VITAMIN_D to 6.76,
    LabMarker.B12 to 7.25,
    LabMarker.FOLATE to 11.16,
    LabMarker.MAGNESIUM to 2.65,
)

private const val PERCENT = 100.0
private const val Z_95 = 1.96
private const val ASSUMED_ANALYTICAL_SHARE = 0.5

// --- small pieces --------------------------------------------------------------------------------

/** Whether the course ran on any day from [from] to [to]. */
private fun Medication.ranOn(from: LocalDate, to: LocalDate): Boolean {
    val start = startDay.asDay() ?: return false
    val end = endDay?.asDay()
    return start <= to && (end == null || end >= from)
}

/** "Saturated fat 24 g a day", or "24 g a day" with no label. */
private fun perDay(label: ReportText?, amount: ReportText): ReportText {
    val rate = res(R.string.report_per_day_rate, amount)
    return label?.let { res(R.string.report_value_unit, it, rate) } ?: rate
}

private fun grams(amount: Double): ReportText = res(
    R.string.report_value_unit,
    amount.pretty(),
    res(R.string.unit_grams),
)

private fun res(id: Int, vararg args: Any): ReportText = ReportText.Res(id, args.toList())

private fun dayOf(day: String): ReportText = day.asDay()?.let { ReportText.Day(it) } ?: ReportText.Plain(day)

private fun String.asDay(): LocalDate? = toLocalDateOrNull()

private fun daysBetween(from: LocalDate, to: LocalDate): Int = ChronoUnit.DAYS.between(from, to).toInt()

private val RAISED_STATUSES = setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE)
private val LOWERED_STATUSES = setOf(LabStatus.BELOW, LabStatus.FAR_BELOW)

/** The markers AACC's biotin table lists among those this app holds — see [biotinNote]. */
private val BIOTIN_MARKERS = setOf(
    LabMarker.TSH,
    LabMarker.FT4,
    LabMarker.VITAMIN_D,
    LabMarker.B12,
    LabMarker.FOLATE,
    LabMarker.FERRITIN,
)

/** 5 mg, in the micrograms biotin is counted in: AACC's threshold — see [biotinNote]. */
private const val BIOTIN_INTERFERENCE_UG = 5_000.0

/** The results the blood-test section prints of one marker: its newest and the three under it. */
private const val PRINTED_RESULTS = PRINTED_LAB_RESULTS

/** Days of diary before a draw of a marker with no food window of its own: an editorial month. */
private const val CONTEXT_DAYS = 30

/**
 * The markers the workouts stand beside: the two liver enzymes still raised days after one session of
 * weightlifting in Pettersson 2008. GGT, which it measured too, did not rise, and ALP and bilirubin
 * barely moved. Never creatinine, eGFR or urea: what is published there is about the hours after
 * endurance races, not a fortnight of workouts.
 */
private val WORKOUT_MARKERS = setOf(LabMarker.ALT, LabMarker.AST)

/**
 * How many days before a liver draw the workouts are listed: the app's own choice. In Pettersson 2008
 * ALT was still raised 10 to 12 days after one session, the last time it was measured.
 */
private const val WORKOUT_DAYS = 14

/** The markers the heavy flow stands beside: the iron stores, and the haemoglobin read with them. */
private val FLOW_MARKERS = setOf(LabMarker.FERRITIN, LabMarker.HEMOGLOBIN)

/** Days of flow counted up to an iron draw, the draw day included: about three cycles, the app's own choice. */
private const val BLEEDING_DAYS = 90

/** How far either side of a draw a weigh-in is still printed beside it. An editorial month. */
private const val WEIGH_IN_DAYS = 30

/** The windows of marked doses the notes read: a month for intake against a level, a week for "was taking". */
private const val SAFETY_MONTH_DAYS = 30
private const val SAFETY_WEEK_DAYS = 7

private const val DAYS_IN_A_WEEK = 7

private const val MINUTES_A_DAY = 24 * 60
