package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// What the bathroom, the tap and the bedroom say about each other.
//
// Everything here reads the journal the person already fills in and says one sentence about a
// count. No condition is named, for the same reason cycleAdvice names none: an app that sees only
// tallies cannot diagnose, and naming a condition is the line between a wellness app and a
// regulated device. Every threshold below is somebody's published one, cited where it sits. These
// are also the topics that must never reach a lock screen — see AdviceTopic.inAppOnly.

/** The window every rule here reads, matching the habits screen's own week. */
const val BODY_WINDOW_DAYS = 7

/** Two weeks, for the statements that need a pattern rather than a week. */
const val BODY_LONG_WINDOW_DAYS = 14

/** A month, which is the unit the ejaculation cohort counted in. */
const val SEXUAL_WINDOW_DAYS = 30

/**
 * ICS/IUGA joint terminology: four to eight daytime voids is the ordinary adult range. The rule
 * below fires far below it, so that under-logging alone does not raise a flag.
 */
private const val LOW_VOIDS_PER_DAY = 2

/** Litres in millilitres. Drinking this much and voiding twice is the pair worth looking at. */
private const val HIGH_WATER_ML = 2_500.0

/** Two such days in a week: one is a hot afternoon, two is a pattern. */
private const val LOW_VOID_DAYS = 2

/**
 * Rome IV functional constipation: fewer than three spontaneous bowel movements a week, and
 * straining during more than a quarter of them — with the criteria met for the last three months
 * and the onset six months back. Both counts are made here, neither is named. Under three in a week
 * is a line about fibre, under three in a fortnight a firmer one, and only a quarter of a year at
 * under three a week — over a record that actually reaches back that far, with no fortnight of it
 * left empty — is put to a clinician.
 */
private const val WEEKLY_STOOLS_LOW = 3
private const val STRAINING_SHARE = 0.25
private const val STRAINING_MIN_STOOLS = 4

/** Thirteen weeks: Rome IV's three months, in the whole weeks the stool rate is counted per. */
private const val BODY_QUARTER_WEEKS = 13
private const val BODY_QUARTER_DAYS = BODY_QUARTER_WEEKS * BODY_WINDOW_DAYS

/** Loose stools this often in a week is worth a line about fluids. */
private const val LOOSE_WEEK = 3

/**
 * Diarrhoea is called chronic at four weeks of persistent loose stools (the ACG and WGO definitions
 * alike), so the clinician line waits for four consecutive weeks each carrying [LOOSE_WEEK]. Six
 * loose stools over eight weeks would not do: two bouts of gastroenteritis reach that with a
 * healthy fortnight between them — and two upset stomachs are not a chronic anything.
 */
private const val LOOSE_CHRONIC_WEEKS = 4
private const val LOOSE_CHRONIC_DAYS = LOOSE_CHRONIC_WEEKS * BODY_WINDOW_DAYS

/**
 * WHO/EFSA: 25 g of dietary fibre a day for an adult. Mentioned only when the journal actually
 * knows the figure — an unlogged day is not a day without fibre.
 */
const val FIBRE_TARGET_G = 25.0

/** Smoking on this many of seven days is daily smoking by any reasonable reading. */
private const val DAILY_SMOKING_DAYS = 5

/**
 * Rider et al., European Urology 2016 (Health Professionals Follow-up Study): 21 or more
 * ejaculations a month, against 4-7, went with about a fifth lower prostate-cancer incidence.
 * It is an observational association in one cohort, and the wording says so — the app counts
 * ejaculations, it does not prescribe them.
 */
private const val EJACULATIONS_LOW_MONTH = 4
private const val EJACULATIONS_REFERENCE_MONTH = 21

/**
 * Pain, twice in a month, is the point at which every gynaecology and urology guideline says to
 * ask someone rather than wait. One occasion is not a pattern.
 */
private const val SYMPTOM_REPEATS = 2

/**
 * Twice a day, averaged over a month, is far enough out to be worth a sentence — and the sentence
 * is not "cut down".
 *
 * ICD-11 6C72 puts compulsive sexual behaviour in loss of control, a life organised around the
 * behaviour, continuing despite harm and real distress — and says outright that distress arising
 * only from moral judgement about one's own impulses does not meet the criterion. Frequency is not
 * on that list. So the app declines to prescribe less of it and says what the criterion actually
 * is, which is the only thing here a person could act on.
 */
private const val SOLO_HIGH_MONTH = 60

/**
 * Water going in with almost nothing recorded coming out.
 *
 * Only fires when the person logs both — the app can only see what is written down, and the
 * sentence says as much, because the likeliest explanation is an unlogged visit rather than a
 * kidney.
 *
 * [imperial] settles the scale the threshold is read out on and nothing else: the comparison is
 * made in the millilitres every water row is stored in, whichever way the profile is set. No
 * default, because the wrong answer here is the silent one — a caller that forgot would print
 * millilitres to a person whose every water card says fl oz.
 */
fun hydrationAdvice(journal: List<JournalEntry>, today: LocalDate, imperial: Boolean): Advice? {
    val days = (0 until BODY_WINDOW_DAYS).map { today.minusDays(it.toLong()) }
    val mismatched = days.count { day ->
        val onDay = journal.filter { it.day == day.toString() }
        val water = onDay.filter { it.kind == KIND_WATER }.sumOf(JournalEntry::servingG)
        val voids = onDay.count { it.kind == KIND_URINATION }
        water >= HIGH_WATER_ML && voids in 1..LOW_VOIDS_PER_DAY
    }
    if (mismatched < LOW_VOID_DAYS) return null
    return Advice(
        topic = AdviceTopic.HYDRATION,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_hydration_title,
        bodyRes = R.string.advice_hydration_body,
        args = listOf(
            Counted(mismatched, R.plurals.count_days),
            WaterVolume(HIGH_WATER_ML.toInt(), imperial),
        ),
        sourceRes = R.string.advice_source_ics,
        windowDays = BODY_WINDOW_DAYS,
        id = "hydration-voids",
    )
}

/**
 * The bowel, in the two directions it goes wrong, and the fibre figure when the journal has one.
 *
 * Each direction is read over a week and over the longer window its guidance names — four
 * consecutive weeks for loose stools, three months for rare ones — and only the long window reaches
 * a clinician: a quiet week can be a quiet week, and a bad fortnight is worth attention, but the
 * words Rome IV and the diarrhoea definitions use are months and weeks in a row.
 */
@Suppress("ReturnCount")
fun bowelAdvice(journal: List<JournalEntry>, today: LocalDate): Advice? {
    val week = bathroomWeek(journal, today)
    val fortnight = bathroomWindow(journal, today, BODY_LONG_WINDOW_DAYS)
    val quarter = bathroomWindow(journal, today, BODY_QUARTER_DAYS)

    if (looseEveryWeek(journal, today)) {
        val month = bathroomWindow(journal, today, LOOSE_CHRONIC_DAYS)
        return looseAdvice(month.looseStools, LOOSE_CHRONIC_DAYS, AdviceLevel.CLINICIAN, "bowel-loose-long")
    }
    if (week.looseStools >= LOOSE_WEEK) {
        return looseAdvice(week.looseStools, BODY_WINDOW_DAYS, AdviceLevel.ATTENTION, "bowel-loose")
    }
    if (quarter.stools in 1 until WEEKLY_STOOLS_LOW * BODY_QUARTER_WEEKS && stoolRecordReaches(journal, today)) {
        return constipationAdvice(quarter.stools, BODY_QUARTER_DAYS, AdviceLevel.CLINICIAN, "bowel-rare-quarter")
    }
    if (fortnight.stools in 1 until WEEKLY_STOOLS_LOW) {
        return constipationAdvice(fortnight.stools, BODY_LONG_WINDOW_DAYS, AdviceLevel.ATTENTION, "bowel-rare-long")
    }
    if (week.stools in 1 until WEEKLY_STOOLS_LOW) {
        return constipationAdvice(week.stools, BODY_WINDOW_DAYS, AdviceLevel.ATTENTION, "bowel-rare")
    }
    // Counted rather than left over after the loose and the normal ones: a subtitle from another
    // version's vocabulary is not evidence of straining. The share is strict because the criterion
    // is "more than a quarter", which one hard visit out of four is not.
    val strained = strainedStools(journal, today)
    if (week.stools >= STRAINING_MIN_STOOLS && strained > week.stools * STRAINING_SHARE) {
        return Advice(
            topic = AdviceTopic.BOWEL,
            level = AdviceLevel.ATTENTION,
            titleRes = R.string.advice_straining_title,
            bodyRes = R.string.advice_straining_body,
            args = listOf(Counted(strained, R.plurals.count_times), Counted(week.stools, R.plurals.count_times)),
            sourceRes = R.string.advice_source_rome,
            windowDays = BODY_WINDOW_DAYS,
            id = "bowel-straining",
        )
    }
    return null
}

private fun looseAdvice(count: Int, window: Int, level: AdviceLevel, id: String) = Advice(
    topic = AdviceTopic.BOWEL,
    level = level,
    titleRes = R.string.advice_loose_title,
    bodyRes = R.string.advice_loose_body,
    args = listOf(Counted(count, R.plurals.count_times), Counted(window, R.plurals.count_days)),
    sourceRes = R.string.advice_source_rome,
    windowDays = window,
    id = id,
)

private fun constipationAdvice(count: Int, window: Int, level: AdviceLevel, id: String) = Advice(
    topic = AdviceTopic.BOWEL,
    level = level,
    titleRes = R.string.advice_rare_stool_title,
    bodyRes = R.string.advice_rare_stool_body,
    args = listOf(
        Counted(count, R.plurals.count_times),
        Counted(window, R.plurals.count_days),
        FIBRE_TARGET_G.toInt(),
    ),
    sourceRes = R.string.advice_source_rome,
    windowDays = window,
    id = id,
)

/** Tobacco every day of the week, which is the pattern half the blood panel is read against. */
fun tobaccoAdvice(journal: List<JournalEntry>, today: LocalDate): Advice? {
    val week = habitWeek(journal, today)
    if (week.smokingDays < DAILY_SMOKING_DAYS) return null
    return Advice(
        topic = AdviceTopic.TOBACCO,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_tobacco_title,
        bodyRes = R.string.advice_tobacco_body,
        args = listOf(
            Counted(week.smokingDays, R.plurals.count_days),
            Counted(BODY_WINDOW_DAYS, R.plurals.count_days),
        ),
        sourceRes = R.string.advice_source_who_tobacco,
        windowDays = BODY_WINDOW_DAYS,
        id = "tobacco-daily",
    )
}

/**
 * The sexual-health statements, symptoms before frequency.
 *
 * A symptom that repeats has an address to go to and a guideline behind it; a frequency has an
 * association at best, so it is never allowed to speak over one. Nothing here tells anyone to have
 * more or less sex — the app counts, and says what the counting is associated with.
 */
@Suppress("ReturnCount")
fun sexualAdvice(journal: List<JournalEntry>, profile: UserProfile, today: LocalDate): Advice? {
    val window = sexualWindow(journal, today, SEXUAL_WINDOW_DAYS)
    alarmingNote(window)?.let { note ->
        return symptomAdvice(note, window.notes.getValue(note), AdviceLevel.CLINICIAN, "sexual-symptom")
    }
    if ((window.notes[SexNote.PAIN] ?: 0) >= SYMPTOM_REPEATS) {
        return symptomAdvice(SexNote.PAIN, window.notes.getValue(SexNote.PAIN), AdviceLevel.CLINICIAN, "sexual-pain")
    }
    if (window.soloActs >= SOLO_HIGH_MONTH) {
        return Advice(
            topic = AdviceTopic.SEXUAL,
            level = AdviceLevel.HABIT,
            titleRes = R.string.advice_solo_title,
            bodyRes = R.string.advice_solo_body,
            args = listOf(
                Counted(window.soloActs, R.plurals.count_times),
                Counted(SEXUAL_WINDOW_DAYS, R.plurals.count_days),
            ),
            sourceRes = R.string.advice_source_icd_csbd,
            windowDays = SEXUAL_WINDOW_DAYS,
            id = "sexual-solo-frequency",
        )
    }
    // An empty month is not a quiet month: a profile that never opened the feature has reported
    // nothing to compare against the cohort, and below the logging age the section is not offered
    // at all, so neither is the association.
    if (profile.sex == "MALE" &&
        window.ejaculations in 1..EJACULATIONS_LOW_MONTH &&
        sexLogOffered(profile.birthDate, today)
    ) {
        return Advice(
            topic = AdviceTopic.SEXUAL,
            level = AdviceLevel.HABIT,
            titleRes = R.string.advice_prostate_title,
            bodyRes = R.string.advice_prostate_body,
            args = listOf(
                Counted(window.ejaculations, R.plurals.count_times),
                Counted(SEXUAL_WINDOW_DAYS, R.plurals.count_days),
                EJACULATIONS_REFERENCE_MONTH,
            ),
            sourceRes = R.string.advice_source_rider,
            windowDays = SEXUAL_WINDOW_DAYS,
            id = "sexual-prostate",
        )
    }
    return null
}

/** Bleeding, from either body, is the one tick that never waits for a second occurrence. */
private fun alarmingNote(window: SexualWindow): SexNote? =
    listOf(SexNote.BLEEDING, SexNote.BLOOD_IN_SEMEN).firstOrNull { (window.notes[it] ?: 0) > 0 }

private fun symptomAdvice(note: SexNote, times: Int, level: AdviceLevel, id: String) = Advice(
    topic = AdviceTopic.SEXUAL,
    level = level,
    titleRes = R.string.advice_sexual_symptom_title,
    bodyRes = R.string.advice_sexual_symptom_body,
    args = listOf(
        Labeled(note.titleRes),
        Counted(times, R.plurals.count_times),
        Counted(SEXUAL_WINDOW_DAYS, R.plurals.count_days),
    ),
    sourceRes = R.string.advice_source_symptom,
    windowDays = SEXUAL_WINDOW_DAYS,
    id = id,
)

/** The bathroom counts over any window, since the rules here need more than the habits week. */
fun bathroomWindow(journal: List<JournalEntry>, today: LocalDate, days: Int): BathroomDay {
    val from = today.minusDays(days - 1L)
    val window = journal.filter {
        it.day.toLocalDateOrNull()?.let { day -> day in from..today } == true
    }
    val stools = window.filter { it.kind == KIND_STOOL }
    return BathroomDay(
        stools = stools.size,
        looseStools = stools.count { it.subtitle == StoolKind.LOOSE.name },
        urinations = window.count { it.kind == KIND_URINATION },
    )
}

private fun strainedStools(journal: List<JournalEntry>, today: LocalDate): Int {
    val from = today.minusDays(BODY_WINDOW_DAYS - 1L)
    return journal.count {
        it.kind == KIND_STOOL && it.subtitle == StoolKind.STRAINED.name &&
            it.day.toLocalDateOrNull()?.let { d -> d in from..today } == true
    }
}

/**
 * Whether every one of the last [LOOSE_CHRONIC_WEEKS] weeks, counted back from today, carried at
 * least [LOOSE_WEEK] loose stools — "persistent" as a run of weeks, not a total with a gap in it.
 */
private fun looseEveryWeek(journal: List<JournalEntry>, today: LocalDate): Boolean =
    (0 until LOOSE_CHRONIC_WEEKS).all { weeksBack ->
        val weekEnd = today.minusDays(weeksBack * BODY_WINDOW_DAYS.toLong())
        bathroomWindow(journal, weekEnd, BODY_WINDOW_DAYS).looseStools >= LOOSE_WEEK
    }

/**
 * Whether stools have been logged through the whole quarter the clinician line is counted over. A
 * quarter's verdict needs a quarter's record: without this the second stool anyone ever logs reads
 * as two in three months. The oldest row is not enough on its own — one entry from last year, in
 * front of a log that restarted on Tuesday, is three months of silence rather than three months of
 * evidence — and neither is a record with a hole in it: a month of daily entries and then nothing
 * is a log somebody put down, and its thirty rows read as thirty stools in a quarter to a rule that
 * only counts. So no fortnight of the quarter may be empty, which is the reading the fortnight's own
 * rule already gives an empty window: silence, not a finding. One stool every two weeks still
 * passes, and that is far rarer than the three a week this line is drawn at.
 */
private fun stoolRecordReaches(journal: List<JournalEntry>, today: LocalDate): Boolean {
    val days = journal.filter { it.kind == KIND_STOOL }
        .mapNotNull { it.day.toLocalDateOrNull() }
    val earliest = days.minOrNull() ?: return false
    val from = today.minusDays(BODY_QUARTER_DAYS - 1L)
    // The day before the quarter and the day after today stand at its two ends, so the stretch
    // before the first row and the one since the last are measured like any gap between two rows.
    val marks = (days.filter { it in from..today } + from.minusDays(1) + today.plusDays(1)).sorted()
    return ChronoUnit.DAYS.between(earliest, today) >= BODY_QUARTER_DAYS - 1L &&
        marks.zipWithNext().all { (a, b) -> ChronoUnit.DAYS.between(a, b) <= BODY_LONG_WINDOW_DAYS }
}
