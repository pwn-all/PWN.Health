package com.pwnall.health

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// A supplement course read beside the blood marker it can move: the result on file before the course
// began, the result after it, and what the diary says about the doses in between.
//
// Pure and shared. The doctor's sheet prints it under "Around each blood test", and the course page
// shows the same lines in a block of its own, so the two cannot describe one course two ways.
//
// Neither says whether a course worked. A before and an after of one person's marker cannot tell a
// supplement from a season, a new laboratory or the ordinary wobble of the test, so what is printed is
// the two figures and everything else that also differed between them. The size of that wobble goes
// on the sheet once, as a general note, and never as a verdict on one person's pair.

/** Whether a marker's change over a course is laid out as a before and an after, or only beside it. */
internal enum class ResponseMode {
    /** The marker follows the course over weeks: a result before it and one far enough into it. */
    RESPONSE,

    /** The marker follows the last days or is held tight: its results are shown, not compared. */
    CONTEXT,
}

/**
 * A nutrient a course can hold, and the blood marker it is read beside.
 *
 * - **Vitamin D, 25-hydroxyvitamin D: from 56 days, near its plateau at 84.** Jones 2014 (JCEM)
 *   measured a half-life of 15.1 days for 25(OH)D3; a first-order approach reaches about 92% of a new
 *   steady level at 56 days and 98% at 84. It ignores what fat stores, so it is the shortest wait,
 *   not a promise.
 * - **Iron, haemoglobin: from 14 days.** BSG 2021 (Snook et al., Gut) reads a rise within two weeks.
 * - **Iron, ferritin: from 84 days.** BSG 2021 continues iron for around three months after the
 *   haemoglobin is back in range, to refill the stores.
 * - **Iron, transferrin saturation: beside it only.** Iron taken in the 24 hours before a sample
 *   raises serum iron and transferrin saturation (Mayo Clinic Laboratories).
 * - **Folic acid, serum folate: beside it only.** WHO 2015: serum folate reflects recent intake.
 * - **Vitamin B12: beside it only.** NICE NG239 (2024): supplements raise the concentration and can
 *   mask a deficiency.
 * - **Magnesium: beside it only.** About 0.3% of the body's magnesium is in the serum
 *   (Jahnen-Dechent and Ketteler 2012, Clin Kidney J).
 *
 * [sentenceRes] is the line under a result drawn too early, or under none yet, for a [ResponseMode.RESPONSE]
 * pair — dated from the course's start at [informativeDays] and, where the pair has one, [plateauDays] —
 * and the reason no comparison is made for a [ResponseMode.CONTEXT] one. [food] is what a meal holds of
 * the same nutrient: food folate for folic acid, since a tablet's folic acid is read into folate.
 */
internal enum class SupplementPairing(
    val nutrient: Nutrient,
    val marker: LabMarker,
    val sentenceRes: Int,
    val food: FoodMicro,
    val informativeDays: Int? = null,
    val plateauDays: Int? = null,
) {
    VITAMIN_D(
        Nutrient.VITAMIN_D,
        LabMarker.VITAMIN_D,
        R.string.supplement_steady_vitamin_d,
        FoodMicro.VITAMIN_D,
        informativeDays = 56,
        plateauDays = 84,
    ),
    IRON_HAEMOGLOBIN(
        Nutrient.IRON,
        LabMarker.HEMOGLOBIN,
        R.string.supplement_steady_hemoglobin,
        FoodMicro.IRON,
        informativeDays = 14,
    ),
    IRON_FERRITIN(
        Nutrient.IRON,
        LabMarker.FERRITIN,
        R.string.supplement_steady_ferritin,
        FoodMicro.IRON,
        informativeDays = 84,
    ),
    IRON_TSAT(Nutrient.IRON, LabMarker.TSAT, R.string.supplement_context_tsat, FoodMicro.IRON),
    FOLIC_ACID(Nutrient.FOLIC_ACID, LabMarker.FOLATE, R.string.supplement_context_folate, FoodMicro.FOLATE),
    VITAMIN_B12(Nutrient.VITAMIN_B12, LabMarker.B12, R.string.supplement_context_b12, FoodMicro.VITAMIN_B12),
    MAGNESIUM(Nutrient.MAGNESIUM, LabMarker.MAGNESIUM, R.string.supplement_context_magnesium, FoodMicro.MAGNESIUM),
    ;

    /** A pair with a day from which a result is read as the course's is a before and an after. */
    val mode: ResponseMode get() = if (informativeDays == null) ResponseMode.CONTEXT else ResponseMode.RESPONSE
}

/** Where a course stands against its marker. */
internal enum class ResponseState {
    /** A result on or before the start, and one at least [SupplementPairing.informativeDays] into it. */
    COMPARED,

    /** The result read since the start was drawn before the pair's informative day; it is shown alone. */
    TOO_EARLY,

    /** No result since the course began. */
    WAITING,

    /** A result far enough into the course, and none on or before its start. */
    NO_BEFORE,

    /** A [ResponseMode.CONTEXT] pair: its results beside the course, never set against each other. */
    CONTEXT_ONLY,
}

/** Something else that differed between the two results, said in one sentence each. */
internal enum class ResponseFlag {
    BEFORE_OLDER_THAN_A_YEAR,
    BEFORE_OUTSIDE_PERIOD,

    /** Another course of the same nutrient was running on the day of the earlier result. */
    BEFORE_ON_SAME_NUTRIENT,
    COURSE_ENDED_BEFORE_DRAW,

    /** 25-hydroxyvitamin D only: the two samples fall in different quarters of the year. */
    SEASONS_DIFFER,

    /** The two results do not name the same laboratory or method, one of them naming none. */
    DIFFERENT_METHOD,

    /** Ferritin only: C-reactive protein on the same sheet as the later result was above its band. */
    INFLAMMATION_ON_SHEET,

    /** Serum folate and transferrin saturation: a dose holding the nutrient was marked that day. */
    DOSE_ON_DRAW_DAY,

    /** The course has no daily slots, so what it holds cannot be turned into an amount a day. */
    AMOUNT_NOT_COMPUTED,
}

/** What the diary's food held of a nutrient in the [SUPPLEMENT_FOOD_DAYS] up to one draw. */
internal data class FoodBeforeDraw(val day: LocalDate, val summary: FoodMicroSummary)

/**
 * The record a course is read against: every course, every blood result and every journal row up to
 * [until] — the whole history, because the result before a course is often years before it and
 * before any report's period.
 */
internal class ResponseRecord(
    val courses: List<Medication>,
    val labs: Map<LabMarker, List<LabReading>>,
    val journal: List<JournalEntry>,
    val until: LocalDate,
    val nowMinute: Int = MINUTES_A_DAY,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * One course against one marker.
 *
 * [perDay] is what the course's label says one dose holds, times the doses a day asks for — the
 * label as typed, not what was absorbed. [overlapping] are the other courses holding the nutrient whose
 * dates overlap this one's up to [countedTo], and [combinedPerDay] the amounts of all of them added up,
 * or null where one of them has no amount a day. [marked] is the doses asked of this course and marked
 * in the app from its start to [countedTo] — the later result, the course's own last day where it ended
 * first, or the day asked about where there is no result yet. The count begins at [countedFrom], the
 * later of the start and the day the course was written down, since the app asked for nothing before.
 */
internal data class SupplementResponse(
    val course: Medication,
    val pairing: SupplementPairing,
    val state: ResponseState,
    val start: LocalDate,
    val before: LabReading?,
    val after: LabReading?,
    val perDay: Double?,
    val overlapping: List<Medication>,
    val combinedPerDay: Double?,
    val marked: Adherence?,
    /** Whether any dose of this course was ever marked; a course nobody ticks prints "not marked", never 0. */
    val everMarked: Boolean,
    val countedFrom: LocalDate,
    val countedTo: LocalDate,
    val food: List<FoodBeforeDraw>,
    val flags: Set<ResponseFlag>,
    /** Days from the course's last day to the later result, where it ended before that result. */
    val endedDaysBefore: Int?,
) {
    /** Days from the start to the later result, or null with no later result. */
    val daysIntoCourse: Int? get() = after?.day?.asDay()?.let { daysBetween(start, it) }
}

/**
 * Every marker [course] is paired with through what its label holds, each against the whole [record].
 *
 * A course nobody described by its contents has no pair: its name is not read, because "D3" typed as a
 * name and "Vitamin D3 2000 IU" typed as a dose are guesses this app does not make. A context pair
 * with no result at all says nothing and is left out; a response pair with none says it is waiting.
 * [periodFrom] is where a report's period starts, for the flag that says the earlier result is older;
 * null on the course page, which has no period. [withFood] is off where food is not to be read — the
 * report without its nutrition section, and the course page, which does not show it.
 */
internal fun supplementResponses(
    course: Medication,
    record: ResponseRecord,
    periodFrom: LocalDate? = null,
    withFood: Boolean = true,
): List<SupplementResponse> {
    val start = course.startDay.asDay()?.takeIf { it <= record.until } ?: return emptyList()
    val holds = contentsOf(course.contents)
    return SupplementPairing.entries.filter { it.nutrient in holds }.mapNotNull { pairing ->
        val draws = drawsFor(pairing, course, start, record)
        draws.takeUnless { pairing.mode == ResponseMode.CONTEXT && it.before == null && it.after == null }
            ?.let { responseOf(course, start, pairing, it, record, periodFrom, withFood) }
    }
}

/** The result on or before the start, and the one read as the course's: see [drawsFor]. */
private class Draws(val before: Pair<LocalDate, LabReading>?, val after: Pair<LocalDate, LabReading>?)

/**
 * The two results of a pair's marker, from laboratory panels only.
 *
 * Before: the newest on or before the day the course began. After: the newest drawn while the course
 * ran and at least the pair's informative days into it; failing that, the first drawn after the course
 * ended, which is flagged with the days between; failing that, the newest drawn too early. Only the
 * first result after an ended course is read against it: any later one is nearer to whatever followed.
 */
private fun drawsFor(pairing: SupplementPairing, course: Medication, start: LocalDate, record: ResponseRecord): Draws {
    val end = course.endDay?.asDay()
    val readings = record.labs[pairing.marker].orEmpty()
        .filter(LabReading::fromPanel)
        .mapNotNull { reading -> reading.day.asDay()?.takeIf { it <= record.until }?.let { it to reading } }
    val since = readings.filter { (day, _) -> day > start }
    val inCourse = since.filter { (day, _) -> end == null || day <= end }
    val informative = pairing.informativeDays ?: 0
    val after = inCourse.lastOrNull { (day, _) -> daysBetween(start, day) >= informative }
        ?: since.firstOrNull { (day, _) -> end != null && day > end }
        ?: inCourse.lastOrNull()
    return Draws(readings.lastOrNull { (day, _) -> day <= start }, after)
}

@Suppress("LongParameterList") // One pair's inputs; each is read once, and a holder would only rename them.
private fun responseOf(
    course: Medication,
    start: LocalDate,
    pairing: SupplementPairing,
    draws: Draws,
    record: ResponseRecord,
    periodFrom: LocalDate?,
    withFood: Boolean,
): SupplementResponse {
    val end = course.endDay?.asDay()
    // The course up to the later result, or to its own last day where it ended first: the days its
    // doses are counted over, and the days another course of the same nutrient overlapped it on.
    val reference = draws.after?.first ?: record.until
    val counted = minOf(reference, end ?: reference)
    val perDay = perDayOf(course, pairing.nutrient)
    val overlapping = record.courses.filter { other ->
        other.id != course.id && pairing.nutrient in contentsOf(other.contents) && other.overlaps(start, counted)
    }
    val amounts = (listOf(course) + overlapping).map { perDayOf(it, pairing.nutrient) }
    val ended = draws.after?.first?.let { day -> end?.takeIf { it < day }?.let { daysBetween(it, day) } }
    val state = stateOf(pairing, draws, start)
    // The earlier result is printed only where it is set beside the later one, so only there is
    // anything about it said — its age, its method, the food before it: a result nobody sees on the
    // page needs no sentence about it.
    val printsBefore = state == ResponseState.COMPARED || state == ResponseState.CONTEXT_ONLY
    val shown = Draws(draws.before?.takeIf { printsBefore }, draws.after)
    return SupplementResponse(
        course = course,
        pairing = pairing,
        state = state,
        start = start,
        before = draws.before?.second,
        after = draws.after?.second,
        perDay = perDay,
        overlapping = overlapping,
        combinedPerDay = amounts.takeIf { overlapping.isNotEmpty() && null !in it }?.sumOf { it ?: 0.0 },
        marked = markedUpTo(course, start, counted, record),
        everMarked = record.journal.any { medSlotOf(it)?.first == course.id },
        countedFrom = maxOf(start, Instant.ofEpochMilli(course.createdAt).atZone(record.zone).toLocalDate()),
        countedTo = counted,
        food = if (withFood) foodAround(pairing, shown, record.journal) else emptyList(),
        flags = beforeFlags(course, pairing, shown, start, record, periodFrom) +
            afterFlags(pairing, shown, record) +
            listOfNotNull(ResponseFlag.COURSE_ENDED_BEFORE_DRAW.takeIf { ended != null }) +
            listOfNotNull(ResponseFlag.AMOUNT_NOT_COMPUTED.takeIf { perDay == null }),
        endedDaysBefore = ended,
    )
}

private fun stateOf(pairing: SupplementPairing, draws: Draws, start: LocalDate): ResponseState {
    val after = draws.after?.first
    return when {
        pairing.mode == ResponseMode.CONTEXT -> ResponseState.CONTEXT_ONLY
        after == null -> ResponseState.WAITING
        daysBetween(start, after) < (pairing.informativeDays ?: 0) -> ResponseState.TOO_EARLY
        draws.before == null -> ResponseState.NO_BEFORE
        else -> ResponseState.COMPARED
    }
}

/** What one course's label holds of [nutrient] a day: one dose's amount times its daily slots. */
private fun perDayOf(course: Medication, nutrient: Nutrient): Double? {
    val slots = course.slots().size
    return contentsOf(course.contents)[nutrient]?.takeIf { slots > 0 }?.times(slots)
}

/** Whether the course ran on any day from [from] to [to]. */
private fun Medication.overlaps(from: LocalDate, to: LocalDate): Boolean {
    val start = startDay.asDay() ?: return false
    val end = endDay?.asDay()
    return start <= to && (end == null || end >= from)
}

/** This course's doses asked and marked from its first day to [to], both included. */
private fun markedUpTo(course: Medication, start: LocalDate, to: LocalDate, record: ResponseRecord): Adherence? {
    val days = daysBetween(start, to) + 1
    val minute = if (to == record.until) record.nowMinute else MINUTES_A_DAY
    return adherenceOf(medAdherence(listOf(course), record.journal, to, days, minute, record.zone))
}

private fun foodAround(pairing: SupplementPairing, draws: Draws, journal: List<JournalEntry>): List<FoodBeforeDraw> =
    listOfNotNull(draws.before?.first, draws.after?.first).map { day ->
        val summaries = foodMicroSummaries(journal, day.minusDays(SUPPLEMENT_FOOD_DAYS - 1L), day)
        FoodBeforeDraw(day, summaries.getValue(pairing.food))
    }

@Suppress("LongParameterList") // The earlier result's three questions need the course, the pair and the period.
private fun beforeFlags(
    course: Medication,
    pairing: SupplementPairing,
    draws: Draws,
    start: LocalDate,
    record: ResponseRecord,
    periodFrom: LocalDate?,
): Set<ResponseFlag> {
    val day = draws.before?.first ?: return emptySet()
    val sameNutrient = record.courses.any { other ->
        other.id != course.id && pairing.nutrient in contentsOf(other.contents) && other.activeOn(day)
    }
    return setOfNotNull(
        ResponseFlag.BEFORE_OLDER_THAN_A_YEAR.takeIf { daysBetween(day, start) > DAYS_IN_A_YEAR },
        ResponseFlag.BEFORE_OUTSIDE_PERIOD.takeIf { periodFrom != null && day < periodFrom },
        ResponseFlag.BEFORE_ON_SAME_NUTRIENT.takeIf { sameNutrient },
    )
}

private fun afterFlags(pairing: SupplementPairing, draws: Draws, record: ResponseRecord): Set<ResponseFlag> {
    val (day, after) = draws.after ?: return emptySet()
    val before = draws.before
    val inflamed = pairing.marker == LabMarker.FERRITIN && record.labs[LabMarker.CRP].orEmpty().any {
        it.entryId == after.entryId && it.entryId != 0L && it.status in RAISED
    }
    val doseOnDay = pairing in SAME_DAY_PAIRS && supplementIntake(
        record.courses,
        record.journal.filter { it.day == after.day },
        day,
        day,
        record.until,
        record.nowMinute,
        record.zone,
    )[pairing.nutrient]?.get(day)?.let { it.marked > 0.0 } == true
    return setOfNotNull(
        ResponseFlag.SEASONS_DIFFER.takeIf {
            pairing.marker == LabMarker.VITAMIN_D && before != null && quarterOf(before.first) != quarterOf(day)
        },
        ResponseFlag.DIFFERENT_METHOD.takeIf { before != null && !sameLabMethod(before.second, after) },
        ResponseFlag.INFLAMMATION_ON_SHEET.takeIf { inflamed },
        ResponseFlag.DOSE_ON_DRAW_DAY.takeIf { doseOnDay },
    )
}

/**
 * December to February, March to May, June to August, September to November as 0 to 3.
 *
 * The same four blocks are winter in one hemisphere and summer in the other, and the app does not
 * know which one the person lives in — so it asks only whether two samples fall in different blocks,
 * which is true or false in both, and prints the dates rather than naming a season.
 */
private fun quarterOf(day: LocalDate): Int = (day.monthValue % MONTHS_IN_A_YEAR) / MONTHS_IN_A_SEASON

/** Whether two results name the same laboratory or method: both named alike, or both unnamed. */
internal fun sameLabMethod(one: LabReading, other: LabReading): Boolean =
    one.method.trim().equals(other.method.trim(), ignoreCase = true)

/** A marker drawn on the same day as a dose this pair's nutrient was marked in: the flag's two pairs. */
private val SAME_DAY_PAIRS = setOf(SupplementPairing.IRON_TSAT, SupplementPairing.FOLIC_ACID)

/** A reading above its band, at either level. */
private val RAISED = setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE)

/**
 * Days of food read before each result: a quarter of a year, the window proposed for vitamin D and
 * ferritin, and used for every pair so that the food line reads the same way
 * under each. For serum folate, which follows the last days, it is context rather than a window the
 * marker is formed over.
 */
internal const val SUPPLEMENT_FOOD_DAYS = 90

private const val DAYS_IN_A_YEAR = 365
private const val MONTHS_IN_A_YEAR = 12
private const val MONTHS_IN_A_SEASON = 3

/** The minute after the last one: a day that has ended asks for every dose it holds. */
private const val MINUTES_A_DAY = 24 * 60

private fun daysBetween(from: LocalDate, to: LocalDate): Int = ChronoUnit.DAYS.between(from, to).toInt()

/** A stored day, or null for one the parser will not take. */
private fun String.asDay(): LocalDate? = toLocalDateOrNull()

// --- the words ----------------------------------------------------------------------------------

/**
 * What a response prints, in order: the result or its absence, then one sentence for the wait or for
 * why the pair is not compared, then one for each thing that also differed.
 *
 * The arrow is "then", not "so": it joins two dated figures and the days on the course, and no word
 * about which way they moved. The sheet and the course page print these same lines.
 */
internal fun SupplementResponse.responseLines(sex: String, imperial: Boolean): List<ReportText> {
    // The wait is a sentence already; the figures are made one, so the lines read as prose when joined.
    val first = headline(sex, imperial).takeIf { state == ResponseState.WAITING }
        ?: ReportText.Res(R.string.supplement_sentence, listOf(headline(sex, imperial)))
    return listOf(first) + sentences() + flags.sortedBy(ResponseFlag::ordinal).map(::flagText)
}

private fun SupplementResponse.headline(sex: String, imperial: Boolean): ReportText {
    val marker = pairing.marker
    val places = marker.printPlaces(listOfNotNull(before, after), sex, imperial)
    fun figure(reading: LabReading) = marker.printed(reading, sex, imperial, places)
    fun withUnit(reading: LabReading) =
        ReportText.Res(
            R.string.report_value_unit,
            listOf(figure(reading), ReportText.Res(marker.unitLabelRes(imperial))),
        )
    fun on(reading: LabReading) =
        ReportText.Res(R.string.supplement_result_on, listOf(withUnit(reading), dayOf(reading.day)))
    val into = daysIntoCourse?.let { ReportText.Quantity(R.plurals.supplement_days_since_start, it) }
    val earlier = before
    val later = after
    return when {
        state == ResponseState.WAITING ->
            ReportText.Res(R.string.supplement_waiting, listOf(ReportText.Res(marker.titleRes), ReportText.Day(start)))

        // A comparison is the one state holding both results, so the checks below only satisfy the compiler.
        state == ResponseState.COMPARED && earlier != null && later != null -> ReportText.Res(
            R.string.supplement_compared,
            listOf(figure(earlier), withUnit(later), dayOf(earlier.day), dayOf(later.day), into ?: ""),
        )

        // A context pair shows the earlier result too, beside the course rather than against the later.
        else -> ReportText.Join(
            listOfNotNull(
                earlier?.takeIf { state == ResponseState.CONTEXT_ONLY }?.let(::on),
                later?.let { ReportText.Join(listOfNotNull(on(it), into), ", ") },
            ),
            "; ",
        )
    }
}

/**
 * The wait for a response pair that has no result far enough in, and the reason for a context pair.
 * A course that ended before its informative day never reached a steady dose, so the wait is not
 * dated for it.
 */
private fun SupplementResponse.sentences(): List<ReportText> {
    val end = course.endDay?.asDay()
    val informative = pairing.informativeDays?.let { start.plusDays(it.toLong()) }
    val steady = informative?.takeIf { end == null || end >= it }?.let { from ->
        val dates = listOfNotNull(from, pairing.plateauDays?.let { start.plusDays(it.toLong()) })
        ReportText.Res(pairing.sentenceRes, dates.map { ReportText.Day(it) })
    }
    return when (state) {
        ResponseState.WAITING, ResponseState.TOO_EARLY -> listOfNotNull(steady)
        ResponseState.NO_BEFORE -> listOf(ReportText.Res(R.string.supplement_no_before))
        ResponseState.CONTEXT_ONLY -> listOf(ReportText.Res(pairing.sentenceRes))
        ResponseState.COMPARED -> emptyList()
    }
}

private fun SupplementResponse.flagText(flag: ResponseFlag): ReportText = when (flag) {
    ResponseFlag.BEFORE_OLDER_THAN_A_YEAR -> ReportText.Res(R.string.supplement_flag_before_old)

    ResponseFlag.BEFORE_OUTSIDE_PERIOD -> ReportText.Res(R.string.supplement_flag_before_period)

    ResponseFlag.BEFORE_ON_SAME_NUTRIENT ->
        ReportText.Res(R.string.supplement_flag_before_on_course, listOf(ReportText.Res(pairing.nutrient.titleRes)))

    ResponseFlag.COURSE_ENDED_BEFORE_DRAW -> ReportText.Res(
        R.string.supplement_flag_ended,
        listOf(ReportText.Quantity(R.plurals.count_days, endedDaysBefore ?: 0)),
    )

    ResponseFlag.SEASONS_DIFFER -> ReportText.Res(
        R.string.supplement_flag_seasons,
        listOf(dayOf(before?.day.orEmpty()), dayOf(after?.day.orEmpty())),
    )

    ResponseFlag.DIFFERENT_METHOD -> ReportText.Res(R.string.report_lab_context_method_differs)

    ResponseFlag.INFLAMMATION_ON_SHEET -> ReportText.Res(R.string.supplement_flag_crp)

    ResponseFlag.DOSE_ON_DRAW_DAY ->
        ReportText.Res(R.string.supplement_flag_dose_day, listOf(ReportText.Res(pairing.nutrient.titleRes)))

    ResponseFlag.AMOUNT_NOT_COMPUTED -> ReportText.Res(R.string.supplement_amount_not_computed)
}

/** A stored day as a [ReportText.Day]; one the parser will not take is printed exactly as stored. */
private fun dayOf(day: String): ReportText = day.asDay()?.let { ReportText.Day(it) } ?: ReportText.Plain(day)

/**
 * "50 µg": an amount in its nutrient's own unit, to the decimal the app prints everything at, and
 * finer only where that decimal would make the line contradict itself — the rule the blood-test
 * figures follow ([LabMarker.printPlaces]). An amount printed beside a level it is over ([above]:
 * the upper level a note names) must read over it, where "100.0 µg a day, above the 100 µg upper
 * level" would not; and an amount that is not nought must not read as nought, where a food mean of
 * 0.03 µg would print as "0.0". So the places step down to [MOST_AMOUNT_PLACES], and past that the last
 * one is rounded away from the level, which leaves the figure within one printed step of the amount.
 */
internal fun nutrientAmountText(nutrient: Nutrient, amount: Double, above: Double = 0.0): ReportText =
    ReportText.Res(
        R.string.report_value_unit,
        listOf(amount.prettyAbove(above), ReportText.Res(nutrient.unit.labelRes)),
    )

private fun Double.prettyAbove(edge: Double): String {
    if (!isFinite() || this <= edge || pretty().toDouble() > edge) return pretty()
    val exact = BigDecimal.valueOf(this)
    val finer = (2..MOST_AMOUNT_PLACES)
        .map { exact.setScale(it, RoundingMode.HALF_UP) }
        .firstOrNull { it.toDouble() > edge }
    return (finer ?: exact.setScale(MOST_AMOUNT_PLACES, RoundingMode.CEILING)).stripTrailingZeros().toPlainString()
}

/** The finest an amount is printed at: the three places the blood-test figures stop at too. */
private const val MOST_AMOUNT_PLACES = 3
