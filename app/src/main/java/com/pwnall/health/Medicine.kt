package com.pwnall.health

import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One dose that was actually taken. The row points back at its course through the payload. */
const val KIND_MED = "MED"

/** Payload keys on a [KIND_MED] row: which course it belongs to, and which slot of the day it settles. */
const val MED_COURSE_KEY = "medicationId"
const val MED_SLOT_KEY = "slot"

/** Payload key on a [KIND_MED] row: what the dose held when it was marked — see [medTakenEntry]. */
const val MED_CONTENTS_KEY = "per"

/**
 * The slot a dose taken on waking carries. A dose on the clock carries its minute of day as digits,
 * so the two can never collide and a course can be reopened without guessing which is which.
 */
const val MED_SLOT_WAKE = "WAKE"

/**
 * When the night was not recorded, waking is assumed at eight. The real figure comes from the sleep
 * record whenever there is one — see [wakeMinuteOf] — because "when I get up" is the one instruction
 * on a box that no clock can be written for.
 *
 * The estimate from the phone's use becomes such a record only when the app is opened, never in the
 * background. Until it is opened that morning a dose on waking is therefore a reminder at eight —
 * every day, for someone who does not open it — and the course's form says so where "on waking" is
 * chosen. A record that arrives later moves the dose to the waking it names, and its grace
 * ([MED_GRACE_MINUTES]) with it: the window is counted from when the person got up, not from when
 * the stand-in alarm happened to ring.
 */
const val MED_DEFAULT_WAKE_MINUTE = 8 * 60

/**
 * How long a dose is still worth mentioning after its time.
 *
 * Four hours, and then the app goes quiet about it: a dose that late is a decision for the person
 * and their doctor, not something a phone should keep asking about at midnight. The screen still
 * shows it unmarked, which is the honest record.
 */
const val MED_GRACE_MINUTES = 240

/** How long an after-food dose waits for a meal to be logged before it is mentioned regardless. */
const val MED_MEAL_WAIT_MINUTES = 90

/** A meal logged this long before the dose's time still counts as the meal that dose follows. */
const val MED_MEAL_LOOKBACK_MINUTES = 120

/**
 * The shorter window for a dose taken with food. "After food" can trail a meal by a couple of hours
 * and still be after it; "with food" means the same sitting, and a lunch logged two hours before
 * the dose's time is not the meal this tablet goes down with — the dose waits for a fresh one.
 */
const val MED_WITH_MEAL_MINUTES = 30

/** Two weeks of doses: long enough for a pattern, short enough that every bar is still readable. */
const val MED_CHART_DAYS = 14

/** What the box says about when to take it: at named times, or whenever the person gets up. */
enum class MedSchedule { CLOCK, WAKE }

/**
 * How a dose relates to a meal, which is the one instruction on a prescription this app can act on:
 * it already knows when food was logged.
 *
 * [BEFORE] is announced at its time and says so. [AFTER] and [WITH] wait for a meal to be
 * logged — that is what makes the reminder worth having rather than a clock repeating itself — and
 * fall back to their own nudge after [MED_MEAL_WAIT_MINUTES] so a dose is never lost to an unlogged
 * lunch.
 */
enum class MedFood(val titleRes: Int, val hintRes: Int) {
    ANY(R.string.med_food_any, R.string.med_food_hint_any),
    BEFORE(R.string.med_food_before, R.string.med_food_hint_before),
    WITH(R.string.med_food_with, R.string.med_food_hint_with),
    AFTER(R.string.med_food_after, R.string.med_food_hint_after),
}

/** Why the app is about to speak about a dose. Each reason carries the sentence it is said in. */
enum class MedReason(val bodyRes: Int) {
    DUE(R.string.med_reminder_due),
    BEFORE_FOOD(R.string.med_reminder_before),
    MISSED_BEFORE_FOOD(R.string.med_reminder_missed_before),
    WITH_FOOD(R.string.med_reminder_with),
    NEEDS_WITH_FOOD(R.string.med_reminder_needs_with_food),
    AFTER_FOOD(R.string.med_reminder_after),
    NEEDS_FOOD(R.string.med_reminder_needs_food),
}

/**
 * A visit's specialty as a string resource, for the places that cannot call a composable.
 *
 * [specialtyLabel] is the same lookup for a screen; this one exists because the report model holds
 * resource ids rather than words — it is resolved by whatever renders it, which is what lets one
 * sheet be printed in the reader's language and another in the file's.
 */
fun specialtyTitleRes(specialty: String): Int = visitKindOf(specialty)?.titleRes
    ?: Checkup.entries.firstOrNull { it.name == specialty }?.titleRes
    ?: R.string.visit_specialty_other

/** The course's schedule as an enum, defaulting to the clock for a row from a newer build. */
fun Medication.scheduleKind(): MedSchedule =
    MedSchedule.entries.firstOrNull { it.name == schedule } ?: MedSchedule.CLOCK

fun Medication.foodRelation(): MedFood =
    MedFood.entries.firstOrNull { it.name == foodRelation } ?: MedFood.ANY

/** The minutes of day this course is due at, in order. Empty for a course taken on waking. */
fun Medication.doseMinutes(): List<Int> = if (scheduleKind() == MedSchedule.WAKE) {
    emptyList()
} else {
    times.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0 until MINUTES_PER_DAY }
        .distinct()
        .sorted()
}

/** The slots one active day holds: a minute figure each, or the single wake slot. */
fun Medication.slots(): List<String> = if (scheduleKind() == MedSchedule.WAKE) {
    listOf(MED_SLOT_WAKE)
} else {
    doseMinutes().map(Int::toString)
}

/** Whether the course covers [day]: a course has a first day, and sometimes a last one. */
fun Medication.activeOn(day: LocalDate): Boolean {
    val start = startDay.toLocalDateOrNull() ?: return false
    if (day < start) return false
    val end = endDay?.let { it.toLocalDateOrNull() } ?: return true
    return day <= end
}

/**
 * Whether the course's last day is behind [day]: such a course leaves the medicine page's list for
 * its history. An open-ended one never has, and neither has one whose end cannot be read — that is
 * kept in sight rather than filed away on a guess.
 */
fun Medication.endedBefore(day: LocalDate): Boolean =
    endDay?.let { it.toLocalDateOrNull() }?.let { it < day } == true

/** The times in "1 tablet · 08:00, 20:00 · after food", parted by the language's [comma]. */
fun Medication.timesText(comma: String): String = when (scheduleKind()) {
    MedSchedule.WAKE -> ""
    MedSchedule.CLOCK -> doseMinutes().joinToString(comma, transform = ::clockText)
}

/**
 * One dose the day asks for, and whether it has been marked.
 *
 * [minuteOfDay] is the effective time — the wake minute for a dose on waking — so everything that
 * sorts, compares or draws these works on one number rather than on two cases.
 */
data class PlannedDose(
    val medicationId: Long,
    val name: String,
    val dose: String,
    val food: MedFood,
    val slot: String,
    val minuteOfDay: Int,
    val onWaking: Boolean,
    val takenAtMs: Long? = null,
    /** The journal row that marked it, so unmarking is a deletion of that row and nothing else. */
    val takenEntryId: Long? = null,
    /**
     * What this one dose holds, in [Medication.contents]'s form: as it was when the dose was marked,
     * where the mark kept a copy, and as the course says today otherwise — an unmarked dose, or a
     * mark that kept no copy. Empty for a course nobody described that way.
     */
    val contents: String = "",
) {
    val taken: Boolean get() = takenAtMs != null
}

private val MED_COURSE_PATTERN = Regex(""""$MED_COURSE_KEY"$SPACE*:$SPACE*([0-9]+)""")
private val MED_SLOT_PATTERN = Regex(""""$MED_SLOT_KEY"$SPACE*:$SPACE*"([^"]*)"""")
private val MED_CONTENTS_PATTERN = Regex(""""$MED_CONTENTS_KEY"$SPACE*:$SPACE*"([A-Z0-9_=.;]*)"""")

/** The characters [Medication.contents] is written in: enum names, digits, '=', '.' and ';'. */
private fun Char.isContentsChar(): Boolean = this in 'A'..'Z' || this in '0'..'9' || this in "_=.;"

/**
 * The course and slot a stored row settles, or null for a row this build cannot read.
 *
 * Read by pattern rather than through `JSONObject`, the way [SexNote]'s payload is, and written the
 * same way in [medTakenEntry]: `org.json` is a stub on the unit-test classpath and throws instead of
 * parsing, so a payload that only a real device can read is a payload no test can hold to. Both
 * values are the app's own — a row id, and either digits or [MED_SLOT_WAKE] — so there is nothing
 * here that could need escaping.
 */
fun medSlotOf(entry: JournalEntry): Pair<Long, String>? {
    if (entry.kind != KIND_MED) return null
    val payload = entry.measurementPayloadJson ?: return null
    val course = MED_COURSE_PATTERN.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val slot = MED_SLOT_PATTERN.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        ?: return null
    return course to slot
}

/**
 * Which stored row settles each of [slots], for one course on one day.
 *
 * A slot takes the row written under its own key first. What is left over — a row whose key names a
 * time the course no longer has — is paired off against the nearest still-open slot by time, so
 * correcting 08:00 to 08:30 does not turn every dose already marked at eight into a missed one. A
 * row whose key is still one of [slots] is never re-attributed: that is a second mark of the same
 * dose, not a mark orphaned by an edit.
 */
private fun markedSlots(
    slots: List<String>,
    marks: List<Pair<String, JournalEntry>>,
): Map<String, JournalEntry> {
    val matched = mutableMapOf<String, JournalEntry>()
    slots.forEach { slot ->
        marks.firstOrNull { it.first == slot }?.let { matched[slot] = it.second }
    }
    // A dose taken outside the course ([oneOffDoseEntry]) never settles a slot, whatever id it names.
    val orphaned = marks
        .filterNot { it.first in slots || it.first.startsWith(MED_ONE_OFF_PREFIX) }
        .sortedBy { it.second.occurredAt }
    val open = slots.filterNot { it in matched }.toMutableList()
    for ((key, entry) in orphaned) {
        val minute = key.toIntOrNull() ?: MED_DEFAULT_WAKE_MINUTE
        val slot = open.minByOrNull { abs((it.toIntOrNull() ?: MED_DEFAULT_WAKE_MINUTE) - minute) } ?: break
        open.remove(slot)
        matched[slot] = entry
    }
    return matched
}

/**
 * Every dose [day] asks for, marked with when it was taken.
 *
 * The journal is the record of what happened, so "taken" is not a column on the course but a row in
 * the journal — which means a marked dose shows up in the day list, the calendar and the backup
 * without any of them being taught about medicine.
 */
fun plannedDoses(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    day: LocalDate,
    wakeMinute: Int = MED_DEFAULT_WAKE_MINUTE,
): List<PlannedDose> {
    val dayText = day.toString()
    val marks = journal
        .filter { it.day == dayText }
        .mapNotNull { entry -> medSlotOf(entry)?.let { it.first to (it.second to entry) } }
        .groupBy({ it.first }, { it.second })
    return medications
        .filter { it.activeOn(day) }
        .flatMap { medication ->
            val onWaking = medication.scheduleKind() == MedSchedule.WAKE
            val slots = medication.slots()
            val settled = markedSlots(slots, marks[medication.id].orEmpty())
            slots.map { slot ->
                val marked = settled[slot]
                PlannedDose(
                    medicationId = medication.id,
                    name = medication.name,
                    dose = medication.dose,
                    food = medication.foodRelation(),
                    slot = slot,
                    minuteOfDay = slot.toIntOrNull() ?: wakeMinute,
                    onWaking = onWaking,
                    takenAtMs = marked?.occurredAt,
                    takenEntryId = marked?.id,
                    contents = marked?.measurementPayloadJson
                        ?.let { MED_CONTENTS_PATTERN.find(it)?.groupValues?.get(1) }
                        ?: medication.contents,
                )
            }
        }
        .sortedWith(compareBy({ it.minuteOfDay }, { it.name }))
}

/** A dose worth mentioning right now, or null when the hour has nothing to say. */
data class MedNudge(val dose: PlannedDose, val reason: MedReason)

/**
 * Which single unmarked dose this moment deserves a word about.
 *
 * Deliberately not gated by the quiet hours the other reminders obey: those are the app's own idea
 * of a decent time, and a dose at eleven at night is the person's own instruction. The grace window
 * is what keeps it from becoming nagging, and only one dose is ever returned — the earliest overdue
 * one, because that is the one a person should catch up on first.
 *
 * [yesterday] is the day before's doses, which are the earliest overdue ones there can be and are
 * therefore looked at first. Without them a dose due at 23:50 would lose the rest of its grace to
 * the date changing, and a dose whose window is still open would go unmentioned. [yesterdayMeal] is
 * the last meal logged on that day; a meal logged after midnight is later than every dose the day
 * before named, so [lastMealMinute] is the cue whenever there is one.
 *
 * [lastFoodMinute] is the newest food of any kind, a cup of coffee with milk included, and it is
 * what a dose taken before food is held against: the milk breaks the empty stomach that dose asks
 * for, even though the cup is not the meal a dose with or after food waits for.
 */
fun medNudge(
    doses: List<PlannedDose>,
    nowMinute: Int,
    lastMealMinute: Int?,
    yesterday: List<PlannedDose> = emptyList(),
    yesterdayMeal: Int? = null,
    lastFoodMinute: Int? = lastMealMinute,
    yesterdayFood: Int? = yesterdayMeal,
): MedNudge? = overdueNudge(
    doses = yesterday,
    // Yesterday's clock, which keeps running past midnight rather than restarting at it.
    nowMinute = nowMinute + MINUTES_PER_DAY,
    lastMealMinute = lastMealMinute?.plus(MINUTES_PER_DAY) ?: yesterdayMeal,
    lastFoodMinute = lastFoodMinute?.plus(MINUTES_PER_DAY) ?: yesterdayFood,
) ?: overdueNudge(doses, nowMinute, lastMealMinute, lastFoodMinute)

private fun overdueNudge(
    doses: List<PlannedDose>,
    nowMinute: Int,
    lastMealMinute: Int?,
    lastFoodMinute: Int?,
): MedNudge? = doses
    .asSequence()
    .filterNot(PlannedDose::taken)
    .mapNotNull { dose ->
        val late = nowMinute - dose.minuteOfDay
        if (late < 0 || late > MED_GRACE_MINUTES) return@mapNotNull null
        when (dose.food) {
            MedFood.ANY -> MedNudge(dose, MedReason.DUE)

            MedFood.BEFORE -> MedNudge(dose, beforeFoodReason(dose, nowMinute, lastFoodMinute))

            // A meal already eaten is the cue itself; without one the dose waits, then asks.
            MedFood.WITH, MedFood.AFTER -> when {
                lastMealMinute != null &&
                    lastMealMinute <= nowMinute &&
                    lastMealMinute >= dose.minuteOfDay - mealLookbackMinutes(dose.food) ->
                    MedNudge(dose, if (dose.food == MedFood.WITH) MedReason.WITH_FOOD else MedReason.AFTER_FOOD)

                late >= MED_MEAL_WAIT_MINUTES -> MedNudge(
                    dose,
                    if (dose.food == MedFood.WITH) MedReason.NEEDS_WITH_FOOD else MedReason.NEEDS_FOOD,
                )

                else -> null
            }
        }
    }
    .firstOrNull()

private fun beforeFoodReason(dose: PlannedDose, nowMinute: Int, lastFoodMinute: Int?): MedReason =
    if (lastFoodMinute != null && lastFoodMinute in (dose.minuteOfDay - MED_MEAL_LOOKBACK_MINUTES)..nowMinute) {
        MedReason.MISSED_BEFORE_FOOD
    } else {
        MedReason.BEFORE_FOOD
    }

/** How far back a meal may be and still be the one this dose is tied to. */
private fun mealLookbackMinutes(food: MedFood): Int =
    if (food == MedFood.WITH) MED_WITH_MEAL_MINUTES else MED_MEAL_LOOKBACK_MINUTES

/** One day of the adherence chart: what was asked for, and what was marked. */
data class MedDay(val day: LocalDate, val planned: Int, val taken: Int) {
    val share: Double get() = if (planned <= 0) 0.0 else taken.toDouble() / planned
}

/**
 * The doses [day] had asked for by [nowMinute] on [today]: the one count that both the adherence
 * chart and [supplementIntake] divide by, so a sheet cannot score one set of doses and add up the
 * vitamins of another.
 *
 * A day asks only for the doses it could have asked for. One entered with a start a week back was
 * asking nothing before it existed: the day [Medication.createdAt] falls on is the first day that
 * counts. That day counts whole, as the day list shows it — every dose on it is a dose to take,
 * and the chart scores the same doses. Counted from the minute instead, a dose due before the
 * course was written down would count only while it was marked, so marking it and then undoing the
 * mark would take it out of the count altogether and leave the share at 100% over a dose that was
 * never taken. Today's slots join the count as their time comes — at [nowMinute] the evening dose
 * says nothing yet about the day, and a day after [today] has asked for nothing — and a dose
 * marked ahead of its time counts with its mark, so the share never runs past one.
 *
 * A dose taken on waking is placed at the minute the night's sleep record ends — the same minute
 * the day list beneath the chart uses, see [wakeMinuteOf] — so the bar and the list on one screen
 * cannot disagree about whether a dose was owed yet.
 */
fun askedDoses(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    day: LocalDate,
    today: LocalDate,
    nowMinute: Int = MINUTES_PER_DAY,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PlannedDose> {
    val createdOn = medications.associate { it.id to Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
    return plannedDoses(medications, journal, day, wakeMinuteOf(journal, day, zone)).filter { dose ->
        val come = day < today || (day == today && dose.minuteOfDay <= nowMinute)
        dose.taken || (come && day >= (createdOn[dose.medicationId] ?: LocalDate.MIN))
    }
}

/**
 * The last [days] days of doses, oldest first, for the chart and the percentage over it. Which
 * doses a day counts is [askedDoses]'s to say.
 *
 * The journal is split by day once rather than filtered whole for every day of the window: the
 * report asks for two years of it, course by course, and each day only ever reads its own rows.
 */
fun medAdherence(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    today: LocalDate,
    days: Int = MED_CHART_DAYS,
    nowMinute: Int = MINUTES_PER_DAY,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MedDay> {
    val byDay = journal.groupBy(JournalEntry::day)
    return (days - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        val asked = askedDoses(medications, byDay[day.toString()].orEmpty(), day, today, nowMinute, zone)
        MedDay(day, asked.size, asked.count(PlannedDose::taken))
    }
}

/** One nutrient on one day: what the doses marked as taken held, and what every dose asked for held. */
data class NutrientDay(val marked: Double, val planned: Double)

/**
 * Per nutrient, per day from [from] to [to]: how much the doses marked as taken held, and how much
 * the doses that day asked for held between them.
 *
 * A dose not marked was not taken, and a day that asked for nothing recorded nothing. So a day whose
 * dose went unmarked is here with [NutrientDay.marked] at zero — the day asked, and nothing was
 * marked — while a day nothing asked about is absent, never a zero: before the course was written
 * down, outside its dates, or a dose whose time has not yet come. [askedDoses] decides which is
 * which, the same call the adherence chart counts through.
 *
 * A marked dose counts what it held when it was marked, where the mark kept a copy (see
 * [medTakenEntry]); a mark without one, and every unmarked dose, counts what the course holds now,
 * because nothing else was written down. A mark whose course was deleted counts for nothing, as it
 * does on the chart: without the course there is no dose it settled.
 *
 * The figures are the label as the person typed it, times the doses ticked: not what was absorbed,
 * and nothing from food.
 *
 * Schedules are daily, so [NutrientDay.planned] is right only for a daily course: a weekly 50 000 IU
 * of vitamin D is planned at 1 250 µg every day and marked on one day in seven. The marked figure is
 * the one to print; the planned one must never be read against an upper level.
 */
fun supplementIntake(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    nowMinute: Int = MINUTES_PER_DAY,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<Nutrient, Map<LocalDate, NutrientDay>> {
    val byDay = journal.groupBy(JournalEntry::day)
    return generateSequence(from) { it.plusDays(1) }
        .takeWhile { it <= to }
        .flatMap { day ->
            askedDoses(medications, byDay[day.toString()].orEmpty(), day, today, nowMinute, zone).flatMap { dose ->
                contentsOf(dose.contents).map { (nutrient, amount) ->
                    Triple(nutrient, day, NutrientDay(if (dose.taken) amount else 0.0, amount))
                }
            }
        }
        .groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, doses) ->
            doses.groupBy({ it.first }, { it.second })
                .mapValues { (_, shares) ->
                    NutrientDay(shares.sumOf(NutrientDay::marked), shares.sumOf(NutrientDay::planned))
                }
        }
}

/**
 * One nutrient over a window, for a line on a sheet.
 *
 * [meanMarked] is the amount marked, averaged over the [askedDays] on which some course holding it
 * asked for a dose — not over the calendar, so a course that ran for a month of a two-year report
 * reads as what it was during that month. [markedShare] is what was marked of what was asked, by
 * amount; it belongs beside the mean, because a mean from half the doses ticked is not an intake.
 */
data class IntakeWindow(val meanMarked: Double, val askedDays: Int, val markedShare: Double)

/** [supplementIntake] folded to one [IntakeWindow] a nutrient. */
fun supplementWindow(intake: Map<Nutrient, Map<LocalDate, NutrientDay>>): Map<Nutrient, IntakeWindow> = intake
    .filterValues { it.isNotEmpty() }
    .mapValues { (_, days) ->
        val marked = days.values.sumOf(NutrientDay::marked)
        IntakeWindow(
            meanMarked = marked / days.size,
            askedDays = days.size,
            markedShare = marked / days.values.sumOf(NutrientDay::planned),
        )
    }

/**
 * The minute the person got up on [day], read off the night's sleep record when there is one.
 *
 * The night is the sleep that ended first. An afternoon nap is filed under the same day as the
 * night before it, so taking the latest end would move a dose tied to waking to the middle of the
 * afternoon the moment a nap is logged.
 */
fun wakeMinuteOf(journal: List<JournalEntry>, day: LocalDate, zone: ZoneId): Int {
    val wokeAt = recordedWakeAt(journal, day) ?: return MED_DEFAULT_WAKE_MINUTE
    val time = Instant.ofEpochMilli(wokeAt).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/**
 * The moment [day]'s night ended by its sleep record — the earliest end filed under that day, for the
 * reason above — or null when no night was recorded. [wakeMinuteOf] supplies a default for a dose
 * that has to be timed from something; a morning check-in must not, since a made-up waking filed as
 * a recorded one is a claim about a night nobody logged.
 */
fun recordedWakeAt(journal: List<JournalEntry>, day: LocalDate): Long? {
    val dayText = day.toString()
    return journal.filter { it.day == dayText }.mapNotNull(JournalEntry::sleepEndAt).minOrNull()
}

/**
 * The minute of the newest meal logged on [day], or null when no meal was logged yet. A cappuccino
 * from the caffeine dialog is not a meal a dose is taken after ([countsAsMeal]) — unless [cups], for
 * the dose taken before food, which any food breaks.
 */
fun lastMealMinuteOf(journal: List<JournalEntry>, day: LocalDate, zone: ZoneId, cups: Boolean = false): Int? {
    val dayText = day.toString()
    val eatenAt = journal
        .filter { it.day == dayText && (it.countsAsMeal || (cups && it.isCaffeineCup)) }
        .maxOfOrNull(JournalEntry::occurredAt) ?: return null
    val time = Instant.ofEpochMilli(eatenAt).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/**
 * The journal row that marks a dose as taken.
 *
 * A dose whose course says what it holds keeps a copy of that under [MED_CONTENTS_KEY], the way the
 * row already keeps the name and the dose as its title and subtitle: raising vitamin D from 1000 to
 * 2000 IU next month must not double every dose taken before it. The copy is cut to the characters
 * the format is written in, so a hand-edited backup cannot put a quote inside the payload.
 */
fun medTakenEntry(dose: PlannedDose, day: LocalDate, atMs: Long, noTime: Boolean = false): JournalEntry {
    val held = dose.contents.filter { it.isContentsChar() }
    val per = if (held.isEmpty()) "" else ",\"$MED_CONTENTS_KEY\":\"$held\""
    return JournalEntry(
        day = day.toString(),
        occurredAt = atMs,
        kind = KIND_MED,
        title = dose.name,
        subtitle = dose.dose,
        calories = 0.0,
        measurementPayloadJson =
        """{"$MED_COURSE_KEY":${dose.medicationId},"$MED_SLOT_KEY":"${dose.slot}"$per${noTimeField(noTime)}}""",
    )
}

/**
 * Payload key on a row whose time nobody said — a dose "I do not remember" when, of an earlier day. Its
 * moment is that day's midday, which a time chosen as 12:00 is too: the key, and not the moment, is
 * what tells the two apart ([statesNoTime]).
 */
private const val NO_TIME_KEY = "noTime"

private val NO_TIME_PATTERN = Regex(""""$NO_TIME_KEY"$SPACE*:$SPACE*true""")

/** The payload's field saying the time was not said, or nothing. */
private fun noTimeField(noTime: Boolean): String = if (noTime) ",\"$NO_TIME_KEY\":true" else ""

/** Whether [entry]'s payload says its time was not said, read by pattern as [medSlotOf] reads the slot. */
internal fun statesNoTime(entry: JournalEntry): Boolean =
    entry.measurementPayloadJson?.let(NO_TIME_PATTERN::containsMatchIn) == true

/**
 * The course a dose taken outside every course names: none has this id — the database numbers them
 * from one, and a restore inserts each with id 0 for it to be numbered — so [plannedDoses] never
 * pairs such a dose with a slot.
 */
internal const val MED_NO_COURSE = 0L

/** The start of a dose's slot key when it belongs to no course: `X<its own moment>` ([oneOffDoseEntry]). */
internal const val MED_ONE_OFF_PREFIX = "X"

/** How long a medicine's name, its dose or a place may be; and how long a course's note may be. */
internal const val MED_NAME_MAX = 60
internal const val MED_NOTE_MAX = 120

/**
 * How long after a course's first day the chat asks how the person has been feeling since starting an
 * antidepressant. The app's convention, not NHS's own figure: it is taken from "Antidepressants": they
 * "can take up to 8 weeks to work fully".
 */
internal const val MED_START_WINDOW_DAYS = 56L

/** Payload key on a dose taken outside a course: what it was taken for ([MedTakenFor]). */
private const val MED_FOR_KEY = "for"

/** What a dose taken outside a course was taken for, as the person said. */
internal enum class MedTakenFor { PAIN, TEMPERATURE, MOOD, SLEEP, OTHER }

private val MED_FOR_PATTERN = Regex(""""$MED_FOR_KEY"$SPACE*:$SPACE*"([A-Z]+)"""")

/**
 * A dose taken outside any course, as a chat notes it: [name] as the person has it, [amount] in their
 * words ("2 tablets", "50 mg"), at [at] on [day]. Its slot is `X<at>`, the row's own moment, which the
 * filer keeps unique, so no course's slot is ever settled by it and two of them never meet. The free
 * text goes into the title and subtitle, never into the payload, which is written by hand as
 * [medTakenEntry]'s is: `{"medicationId":0,"slot":"X1790000000000"[,"for":"PAIN"][,"noTime":true]}`.
 */
internal fun oneOffDoseEntry(
    name: String,
    amount: String,
    day: LocalDate,
    at: Long,
    takenFor: MedTakenFor?,
    noTime: Boolean = false,
): JournalEntry {
    val reason = takenFor?.let { ",\"$MED_FOR_KEY\":\"${it.name}\"" }.orEmpty()
    val payload =
        """{"$MED_COURSE_KEY":$MED_NO_COURSE,"$MED_SLOT_KEY":"$MED_ONE_OFF_PREFIX$at"$reason${noTimeField(noTime)}}"""
    return JournalEntry(
        day = day.toString(),
        occurredAt = at,
        kind = KIND_MED,
        title = name.trim().take(MED_NAME_MAX),
        subtitle = amount.trim().take(MED_NAME_MAX),
        calories = 0.0,
        measurementPayloadJson = payload,
    )
}

/** Whether [entry] is a dose taken outside any course ([oneOffDoseEntry]). */
internal fun isOneOffDose(entry: JournalEntry): Boolean =
    medSlotOf(entry)?.let { (course, slot) -> course == MED_NO_COURSE && slot.startsWith(MED_ONE_OFF_PREFIX) } == true

/** What a dose outside a course was taken for, read by pattern as [medSlotOf] reads its slot; null when not said. */
internal fun medTakenFor(entry: JournalEntry): MedTakenFor? {
    if (entry.kind != KIND_MED) return null
    val name = entry.measurementPayloadJson?.let { MED_FOR_PATTERN.find(it)?.groupValues?.get(1) } ?: return null
    return MedTakenFor.entries.firstOrNull { it.name == name }
}

/**
 * What a course is, as the form or a chat says it: its name, dose, schedule, times, how it goes with
 * food, its days and its note — before it is a row.
 */
internal data class CoursePlan(
    val name: String,
    val dose: String,
    val schedule: MedSchedule,
    val times: List<Int>,
    val food: MedFood,
    val start: LocalDate,
    val end: LocalDate?,
    val note: String,
)

/**
 * The course row [plan] makes, written down at [createdAt]: the one builder the course form and a
 * chat share, so a course is the same row whichever made it — the texts trimmed and cut to their
 * caps, the times each once and in order.
 */
internal fun medicationOf(
    plan: CoursePlan,
    createdAt: Long,
    id: Long = 0,
    photo: String = "",
    attachments: String = "",
    contents: String = "",
): Medication = Medication(
    id = id,
    name = plan.name.trim().take(MED_NAME_MAX),
    dose = plan.dose.trim().take(MED_NAME_MAX),
    schedule = plan.schedule.name,
    times = plan.times.distinct().sorted().joinToString(","),
    foodRelation = plan.food.name,
    startDay = plan.start.toString(),
    endDay = plan.end?.toString(),
    note = plan.note.trim().take(MED_NOTE_MAX),
    attachments = attachments,
    contents = contents,
    createdAt = createdAt,
    photo = photo,
)

/**
 * One line of a course form's "what one dose holds", while it is being typed: the figure as the
 * person typed it, and the unit they typed it in — null for the nutrient's own.
 */
data class ContentLine(val nutrient: Nutrient, val typed: String = "", val unit: LabelUnit? = null) {
    /** The figure in the nutrient's own unit, as [Medication.contents] stores it; null until one is typed. */
    val amount: Double? get() = canonicalAmount(typed, unit)
}

/**
 * [typed], read in [unit] — or in the nutrient's own unit when that is null — as the figure a course
 * stores. In decimal arithmetic rather than doubles: 3 IU of retinol is 0.9 µg RAE, where the double
 * product is 0.8999999999999999, and that is the figure [contentsText] would have written down.
 */
fun canonicalAmount(typed: String, unit: LabelUnit?): Double? {
    val figure = typed.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    // Rounded to what the line can hold again, so a course reopened after an IU conversion shows the
    // figure it stored and a keystroke in the field cannot quietly cut it shorter.
    return (if (unit == null) figure else figure?.multiply(unit.perUnit.toBigDecimal()))
        ?.setScale(DOSE_DECIMALS, RoundingMode.HALF_UP)
        ?.toDouble()
}

/**
 * The same line with its figure rewritten in [unit], keeping the amount it stands for.
 *
 * A chip that only relabelled the digits would turn the D3 preset's 25 µg into 25 IU — forty times
 * less vitamin D — on the tap somebody makes to match their label.
 */
fun ContentLine.inUnit(unit: LabelUnit?): ContentLine {
    val stored = amount ?: return copy(unit = unit)
    val figure = unit?.let {
        stored.toBigDecimal().divide(it.perUnit.toBigDecimal(), DOSE_DECIMALS, RoundingMode.HALF_UP)
    } ?: stored.toBigDecimal()
    return copy(typed = figure.stripTrailingZeros().toPlainString(), unit = unit)
}

/** The decimals a dose line takes, which is what [decimalInput] lets through. */
private const val DOSE_DECIMALS = 2

/** A figure as plain decimal digits: what a form line holds and what [contentsText] writes. */
fun plainAmount(amount: Double): String = amount.toBigDecimal().stripTrailingZeros().toPlainString()

/** A course's stored contents as form lines, each in the nutrient's own unit. */
fun contentLinesOf(contents: String): List<ContentLine> =
    contentsOf(contents).map { (nutrient, amount) -> ContentLine(nutrient, plainAmount(amount)) }

private val CONTENTS_ENTRY = Regex("""[A-Z0-9_]+=[0-9]+(?:\.[0-9]+)?""")

/**
 * The entries of [contents] this build has no [Nutrient] for — written by a newer one and carried
 * here in a backup — kept as they stand, so that moving a course's time on this phone does not
 * erase them. [contentsOf] skips them; the form writes them back unread.
 */
fun unnamedContentsOf(contents: String): String = contents.split(';')
    .filter { part -> part.matches(CONTENTS_ENTRY) && Nutrient.entries.none { it.name == part.substringBefore('=') } }
    .joinToString(";")

/** What a course form's lines store as, with [unnamed] carried through. A line left empty is dropped. */
fun contentsOfLines(lines: List<ContentLine>, unnamed: String = ""): String = listOf(
    contentsText(lines.mapNotNull { line -> line.amount?.let { line.nutrient to it } }.toMap()),
    unnamed,
).filter(String::isNotEmpty).joinToString(";")

/**
 * The form's lines as one Bundle-friendly string, unit and all: `VITAMIN_D/VITAMIN_D_IU=2000`. A
 * figure typed in IU has to come back as IU after a rotation, or 2000 would be reread as micrograms.
 */
fun savedContentLines(lines: List<ContentLine>): String =
    lines.joinToString(";") { "${it.nutrient.name}/${it.unit?.name.orEmpty()}=${it.typed}" }

fun restoredContentLines(saved: String): List<ContentLine> = saved.split(';').mapNotNull { part ->
    val nutrient = Nutrient.entries.firstOrNull { it.name == part.substringBefore('/') }
    val unit = LabelUnit.entries.firstOrNull { it.name == part.substringAfter('/').substringBefore('=') }
    nutrient?.let { ContentLine(it, part.substringAfter('=', ""), unit) }
}

/**
 * The supplements a course is most often opened for, each with a strength common on the shelf.
 *
 * A starting point and no more. The lines a preset adds are ordinary lines, their figures printed
 * in bold where the person checks them against their own label; one already on the form keeps what
 * was typed into it. A multivitamin adds its lines empty, because no two brands hold the same and a
 * figure made up here would reach the doctor's sheet looking as if somebody had read it off a box.
 */
enum class SupplementPreset(val titleRes: Int, val holds: Map<Nutrient, Double?>) {
    /** 25 µg is 1000 IU, at [LabelUnit.VITAMIN_D_IU]'s 40 IU to the microgram. */
    VITAMIN_D3(R.string.med_preset_vitamin_d3, mapOf(Nutrient.VITAMIN_D to 25.0)),
    VITAMIN_B12(R.string.nutrient_vitamin_b12, mapOf(Nutrient.VITAMIN_B12 to 1000.0)),
    FOLIC_ACID(R.string.nutrient_folic_acid, mapOf(Nutrient.FOLIC_ACID to 400.0)),

    /** Elemental iron: the label's figure for the iron itself, not the weight of the salt it comes as. */
    IRON(R.string.nutrient_iron, mapOf(Nutrient.IRON to 65.0)),
    MAGNESIUM(R.string.nutrient_magnesium, mapOf(Nutrient.MAGNESIUM to 200.0)),
    ZINC(R.string.nutrient_zinc, mapOf(Nutrient.ZINC to 15.0)),
    OMEGA3(R.string.nutrient_omega3, mapOf(Nutrient.OMEGA3 to 300.0)),
    VITAMIN_C(R.string.nutrient_vitamin_c, mapOf(Nutrient.VITAMIN_C to 500.0)),
    CALCIUM(R.string.nutrient_calcium, mapOf(Nutrient.CALCIUM to 500.0)),
    BIOTIN(R.string.nutrient_biotin, mapOf(Nutrient.BIOTIN to 5000.0)),
    CREATINE(R.string.nutrient_creatine, mapOf(Nutrient.CREATINE to 5.0)),
    MULTIVITAMIN(
        R.string.med_preset_multivitamin,
        listOf(
            Nutrient.VITAMIN_D,
            Nutrient.VITAMIN_A,
            Nutrient.VITAMIN_E,
            Nutrient.VITAMIN_C,
            Nutrient.VITAMIN_B6,
            Nutrient.VITAMIN_B12,
            Nutrient.FOLIC_ACID,
            Nutrient.IRON,
            Nutrient.ZINC,
            Nutrient.IODINE,
            Nutrient.SELENIUM,
        ).associateWith { null },
    ),
}

/** These lines with [preset]'s added after them; a nutrient already here keeps its line as it is. */
fun List<ContentLine>.withPreset(preset: SupplementPreset): List<ContentLine> = this + preset.holds
    .filterKeys { nutrient -> none { it.nutrient == nutrient } }
    .map { (nutrient, amount) -> ContentLine(nutrient, amount?.let(::plainAmount).orEmpty()) }

/**
 * A routine examination worth keeping a clock on, and the interval the published guidance names.
 *
 * The list is short on purpose: every entry here is a programme with an interval somebody
 * responsible has written down, and nothing else. It decides nothing — an age and a sex say which
 * rows apply, the person's own record of visits says when each is next due, and the screen shows
 * both so the figure can be checked rather than believed.
 *
 * [sex] follows the profile's own two values; null means everyone. [toAge] is the last birthday a
 * programme still invites at; null means it never stops asking. Screening programmes do stop — the
 * evidence runs out before the person does — and a list that kept asking a woman of seventy-eight
 * for a mammogram would be advising against the bodies it cites.
 */
enum class Checkup(
    val titleRes: Int,
    val everyMonths: Int,
    val sex: String?,
    val fromAge: Int,
    val toAge: Int? = null,
) {
    /** A general review: blood pressure, weight, and whatever the year has added. Yearly is the norm. */
    GP(R.string.checkup_gp, 12, null, 18),

    /** Six to twelve months is the conventional recall; the shorter end is the one people know. */
    DENTIST(R.string.checkup_dentist, 6, null, 18),

    /** AAO: every two years from 40, where pressure and retinal change start to be worth looking for. */
    EYES(R.string.checkup_eyes, 24, null, 40),

    /** An annual gynaecological review, which is what most national programmes ask for. */
    GYNECOLOGIST(R.string.checkup_gynecologist, 12, "FEMALE", 18),

    /**
     * USPSTF 2018 and ACS: cytology every three years from 21; WHO 2021 puts HPV testing first, from
     * 30 and every five to ten years. The shorter interval is used, and screening ends at 65.
     */
    CERVICAL(R.string.checkup_cervical, 36, "FEMALE", 21, toAge = 65),

    /**
     * USPSTF 2024: biennial mammography from 40 to 74; IARC/EU programmes run 45 or 50 to 74. The
     * earlier start and the shared end are used.
     */
    MAMMOGRAPHY(R.string.checkup_mammography, 24, "FEMALE", 40, toAge = 74),

    /**
     * No screening body mandates it; a yearly urological review from 40 is the common advice, and it
     * ends where the evidence does — USPSTF 2018 leaves PSA to a shared decision at 55–69 and
     * recommends against it from 70.
     */
    UROLOGIST(R.string.checkup_urologist, 12, "MALE", 40, toAge = 69),

    /** USPSTF 2021: bowel screening from 45 to 75, colonoscopy at ten-year intervals. */
    BOWEL(R.string.checkup_bowel, 120, null, 45, toAge = 75),
}

/** The specialty an appointment with nobody on this list is filed under. */
const val VISIT_SPECIALTY_OTHER = "OTHER"

/** Which examinations apply to this person, in the order the enum declares them. */
fun checkupsFor(sex: String, ageYears: Int): List<Checkup> = Checkup.entries.filter {
    (it.sex == null || it.sex == sex) && ageYears >= it.fromAge && (it.toAge == null || ageYears <= it.toAge)
}

/**
 * Where one examination stands: when it was last done, when it falls due, and how far through the
 * interval today is. [daysLeft] is null when there is no visit on record at all, which is not the
 * same as overdue — nobody knows when the last one was, so the screen says exactly that.
 */
data class CheckupStatus(
    val checkup: Checkup,
    val lastDay: LocalDate?,
    val dueOn: LocalDate?,
    val elapsedShare: Double,
    val daysLeft: Long?,
) {
    val due: Boolean get() = daysLeft == null || daysLeft <= 0

    /**
     * Behind a date that is actually known — the case worth a warning colour. A blank record is
     * [due] in the sense that the screen has something to suggest, but nobody is late on it.
     */
    val overdue: Boolean get() = daysLeft != null && daysLeft <= 0
}

/**
 * The clock on each examination, wound from the person's own visits.
 *
 * A visit counts once it is marked as happened: an appointment in the diary is an intention, and an
 * intention should not reset a screening interval. Being marked is what makes it count, but the day
 * it counts from is the visit's own — the screen asks about visits whose date has passed, so a
 * three-week-old appointment is normally ticked off three weeks late.
 */
fun checkupStatuses(
    checkups: List<Checkup>,
    appointments: List<Appointment>,
    today: LocalDate,
    zone: ZoneId,
): List<CheckupStatus> = checkups.map { checkup ->
    val lastDay = appointments
        .filter { it.specialty == checkup.name && it.doneAt != null }
        .maxOfOrNull(Appointment::atMs)
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val dueOn = lastDay?.plusMonths(checkup.everyMonths.toLong())
    val interval = lastDay?.let { ChronoUnit.DAYS.between(it, dueOn) }?.takeIf { it > 0 }
    CheckupStatus(
        checkup = checkup,
        lastDay = lastDay,
        dueOn = dueOn,
        elapsedShare = if (lastDay == null || interval == null) {
            1.0
        } else {
            (ChronoUnit.DAYS.between(lastDay, today).toDouble() / interval).coerceIn(0.0, 1.0)
        },
        daysLeft = dueOn?.let { ChronoUnit.DAYS.between(today, it) },
    )
}

/** An hour, for the appointment reminder that has run out of day to be early in. */
private const val HOUR_MS = 60 * 60 * 1_000L

/**
 * How far past its time an appointment is still announced. The reminder rides a worker that runs
 * once an hour, so a visit at half past eight can have its time come and go between two runs, and
 * the run at nine is the first that could have spoken: one period late it still does. Later than
 * that the screen asks whether the visit happened, which is the honest question by then.
 */
const val VISIT_REMIND_LATE_MS = HOUR_MS

private const val MINUTES_PER_DAY = 24 * 60

/**
 * The moment an appointment's reminder falls due: the morning of the day that was asked for.
 *
 * Days, not a span of milliseconds. "Three days before" names a date on a calendar, and the app
 * prints that date on the screen where the choice is made — so counting 72 hours back from 08:00
 * and announcing it on the wrong side of a midnight is a promise broken in writing. The morning is
 * [Reminders.QUIET_UNTIL], the same hour everything else in this app is allowed to start speaking.
 *
 * The clamp is for the visit early enough that its own morning is already too late: an 08:00
 * appointment asked to be announced on the day would otherwise be announced at 09:00, after it
 * started, which is to say never. An hour's warning is what is left to give.
 */
fun appointmentRemindAt(visit: Appointment, zone: ZoneId): Long {
    val morning = Instant.ofEpochMilli(visit.atMs)
        .atZone(zone)
        .toLocalDate()
        .minusDays(visit.remindDaysBefore.coerceAtLeast(0).toLong())
        .atTime(Reminders.QUIET_UNTIL)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
    return minOf(morning, visit.atMs - HOUR_MS)
}

/**
 * The next appointment worth a notification, or null when none has come due.
 *
 * Once per appointment: [Appointment.remindedAt] is written when it is announced, so this survives
 * the app's process being killed between two runs of the hourly worker — which the in-process
 * markers the other reminders use do not.
 *
 * A visit still ahead qualifies, and so does one less than [VISIT_REMIND_LATE_MS] behind, provided
 * it is still today's: a visit at ten to midnight is not announced in the small hours as if it
 * were about to start. Anything further behind is past due, and the screen asks about it instead.
 */
fun appointmentToRemind(
    appointments: List<Appointment>,
    nowMs: Long,
    zone: ZoneId,
): Appointment? {
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = dayOf(nowMs)
    return appointments
        .filter { it.doneAt == null && it.remindedAt == null }
        .filter { it.atMs >= nowMs || (it.atMs > nowMs - VISIT_REMIND_LATE_MS && dayOf(it.atMs) == today) }
        .filter { nowMs >= appointmentRemindAt(it, zone) }
        .minByOrNull(Appointment::atMs)
}

/** Appointments still ahead, soonest first; the screen lists these and offers to mark them done. */
fun upcomingAppointments(appointments: List<Appointment>, nowMs: Long): List<Appointment> =
    appointments.filter { it.doneAt == null && it.atMs >= nowMs }.sortedBy(Appointment::atMs)

/** Appointments whose time has passed with nothing marked: the screen asks whether they happened. */
fun pastDueAppointments(appointments: List<Appointment>, nowMs: Long): List<Appointment> =
    appointments.filter { it.doneAt == null && it.atMs < nowMs }.sortedByDescending(Appointment::atMs)
