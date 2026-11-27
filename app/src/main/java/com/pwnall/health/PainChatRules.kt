package com.pwnall.health

// The pain thread's red flags, read through [SYMPTOM_RULES] like every other rule, so a
// line is shown under the first answer that makes it true and the thread's most urgent sets its close.
// A rule reads only the report: what it needs from the chat — the days since a same pain's first
// report — was frozen into the report as its question was answered.

/**
 * A rating of eight or more read as severe pain, where a page says "severe" or "very painful": the
 * app's convention, not NHS. The page's own words are asked as a chip in every family's list; the
 * rating is a second way to the same line, never the only one.
 */
private const val SEVERE_NRS = 8

/** "Lasts longer than 30 minutes": NHS "Gallstones". */
private const val RIBS_LASTED_MINUTES = 30

/** "Cramps last longer than 10 minutes": NHS "Leg cramps". */
private const val CRAMP_LASTED_MINUTES = 10

/** A same pain first reported this many days ago or more, painkillers not working: NHS "Neck pain", "Headaches". */
private const val PAINKILLER_DAYS = 3

/** The starts of two to three days ago and longer. */
private val STARTED_DAYS_AGO = setOf(
    SymptomOption.STARTED_2_3_DAYS,
    SymptomOption.STARTED_A_WEEK,
    SymptomOption.STARTED_A_MONTH,
    SymptomOption.STARTED_LONGER,
)

/** The starts of about a month ago and longer: a pain of some weeks. */
private val STARTED_WEEKS_AGO = setOf(SymptomOption.STARTED_A_MONTH, SymptomOption.STARTED_LONGER)

/** The right upper belly, where gallstones are felt: under the right ribs, and the pit of the stomach. */
private val RIGHT_UPPER_BELLY = setOf("UNDER_RIBS:R", "UPPER_BELLY:C")

private fun SymptomReport.families(): List<PainFamily> = painFamilies(painThreadMarks(this))

private fun SymptomReport.injured(): Boolean = has(SymptomStep.PAIN_INJURY, SymptomOption.YES)

private fun SymptomReport.severe(): Boolean = (values[SymptomStep.PAIN_NRS]?.toIntOrNull() ?: 0) >= SEVERE_NRS

private fun SymptomReport.lasted(): Int = values[SymptomStep.PAIN_LASTED]?.toIntOrNull() ?: 0

private fun SymptomReport.stillHurts(): Boolean = has(SymptomStep.PAIN_STATUS, SymptomOption.STILL_HURTS)

private fun SymptomReport.gone(): Boolean = has(SymptomStep.PAIN_STATUS, SymptomOption.GONE)

private fun SymptomReport.painkillerAnswered(): Boolean = !answers[SymptomStep.PAIN_PAINKILLER].isNullOrEmpty()

private fun SymptomReport.samePainDays(): Long? = meta[PAIN_SAME_DAYS]?.toLongOrNull()

/**
 * Whether an option ticked in the thread's red-flag lists has a row of [family] at [level] that stands
 * by the answers ([fitsAnswers]): one rule per family and level, each with its family's pages.
 */
private fun signHolds(report: SymptomReport, family: PainFamily, level: SignLevel): Boolean {
    val marks = painThreadMarks(report)
    if (family !in painFamilies(marks)) return false
    val ticked = listOf(SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE).flatMap { report.answers[it].orEmpty() }
    val injured = family.injuryAsked && report.injured()
    return PAIN_SIGNS_BY_FAMILY[family].orEmpty().any { sign ->
        sign.level == level && sign.option in ticked && sign.fitsAnswers(injured, marks)
    }
}

/**
 * `PAIN_<FAMILY>_<LEVEL>`, one per family and level its list has. Each reads the areas marked, both
 * lists of red flags and, where its family asks it, the injury ([signHolds]).
 */
private fun signRules(): List<AlertRule> = PAIN_SIGNS_BY_FAMILY.flatMap { (family, signs) ->
    signs.map { it.level }.distinct().sortedBy { it.ordinal }.map { level ->
        AlertRule(
            id = "PAIN_${family.name}_${level.name}",
            topics = setOf(Symptom.PAIN),
            shownAt = if (level <= SignLevel.I) SymptomStep.PAIN_SIGNS else SymptomStep.PAIN_SIGNS_MORE,
            alert = level.alert,
            sourceRes = family.sourceRes,
            reads = SIGN_READS + listOfNotNull(SymptomStep.PAIN_INJURY.takeIf { family.injuryAsked }),
        ) { report -> signHolds(report, family, level) }
    }
}

/** What every red flag of the lists reads: the areas, and the ticks of both lists. */
private val SIGN_READS = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE)

/**
 * The families whose pages put a severe pain at "get advice today": the shoulder and the hand; the
 * foot without an injury; the arm and the leg after one.
 */
private fun SymptomReport.severeTodayFamilies(): List<PainFamily> {
    val injured = injured()
    return families().filter { family ->
        when (family) {
            PainFamily.SHOULDER, PainFamily.HAND -> true
            PainFamily.FOOT -> !injured
            PainFamily.ARM, PainFamily.LEG -> injured
            else -> false
        }
    }
}

/**
 * The families whose pages put a severe pain at "see a GP": the neck, the back, the jaw; the hip, the arm and the leg
 * without an injury.
 */
private fun SymptomReport.severeGpFamilies(): List<PainFamily> {
    val injured = injured()
    return families().filter { family ->
        when (family) {
            PainFamily.NECK, PainFamily.BACK, PainFamily.JAW -> true
            PainFamily.HIP, PainFamily.ARM, PainFamily.LEG -> !injured
            else -> false
        }
    }
}

/**
 * The first family whose pages say a pain still there after some weeks is a GP's: by its own days
 * for the same pain, or for a new one any family with such a line once it started a month ago or more.
 */
private fun SymptomReport.weeksFamily(): PainFamily? {
    val days = samePainDays()
    val same = has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES)
    return families().firstOrNull { family ->
        val weeks = family.weeksDays ?: return@firstOrNull false
        if (same) (days ?: 0) >= weeks else hasAny(SymptomStep.ONSET, STARTED_WEEKS_AGO)
    }
}

/** The head or the neck, whose pages name painkillers that have not worked: the first marked. */
private fun SymptomReport.painkillerFamily(): PainFamily? =
    families().firstOrNull { it == PainFamily.HEAD || it == PainFamily.NECK }

private fun painRule(
    id: String,
    shownAt: SymptomStep,
    alert: SymptomAlert,
    sourceRes: Int,
    reads: Set<SymptomStep> = emptySet(),
    holds: (SymptomReport) -> Boolean,
) = rule(id, Symptom.PAIN, shownAt, alert, sourceRes, reads, holds)

/** The rules beside the lists: by the area, the injury, the rating, how long, the painkiller, the chest. */
private val ANSWER_RULES: List<AlertRule> = listOf(
    painRule("PAIN_SEPSIS_E", SymptomStep.PAIN_SEPSIS, SymptomAlert.EMERGENCY, R.string.pain_source_sepsis) {
        it.answers[SymptomStep.PAIN_SEPSIS].orEmpty().any { option -> option != SymptomOption.NONE }
    },
    // NHS "Temporomandibular disorder (TMD)": its "111 now" card lists pain around the jaw itself.
    painRule("PAIN_JAW_TMD", SymptomStep.PAIN_AREA, SymptomAlert.URGENT_NOW, R.string.pain_source_jaw) {
        PainFamily.JAW in it.families()
    },
    painRule(
        "PAIN_SHOULDER_INJURY",
        SymptomStep.PAIN_INJURY,
        SymptomAlert.URGENT,
        R.string.pain_source_shoulder,
        reads = setOf(SymptomStep.PAIN_AREA),
    ) {
        PainFamily.SHOULDER in it.families() && it.injured()
    },
    painRule(
        "PAIN_NRS_BELLY_E",
        SymptomStep.PAIN_NRS,
        SymptomAlert.EMERGENCY,
        R.string.pain_source_belly,
        reads = setOf(SymptomStep.PAIN_AREA),
    ) {
        PainFamily.BELLY in it.families() && it.severe()
    },
    painRule(
        "PAIN_NRS_HIP_INJ_E",
        SymptomStep.PAIN_NRS,
        SymptomAlert.EMERGENCY,
        R.string.pain_source_hip,
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_INJURY),
    ) {
        PainFamily.HIP in it.families() && it.injured() && it.severe()
    },
    painRule(
        "PAIN_NRS_FOOT_INJ_I",
        SymptomStep.PAIN_NRS,
        SymptomAlert.IN_PERSON_NOW,
        R.string.pain_source_foot,
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_INJURY),
    ) {
        PainFamily.FOOT in it.families() && it.injured() && it.severe()
    },
    painRule(
        "PAIN_NRS_KNEE_N",
        SymptomStep.PAIN_NRS,
        SymptomAlert.URGENT_NOW,
        R.string.pain_source_knee,
        reads = setOf(SymptomStep.PAIN_AREA),
    ) {
        PainFamily.KNEE in it.families() && it.severe()
    },
    AlertRule(
        "PAIN_NRS_D",
        setOf(Symptom.PAIN),
        SymptomStep.PAIN_NRS,
        SymptomAlert.URGENT,
        sourceOf = { it.severeTodayFamilies().firstOrNull()?.sourceRes },
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_INJURY),
    ) { it.severe() && it.severeTodayFamilies().isNotEmpty() },
    AlertRule(
        "PAIN_NRS_G",
        setOf(Symptom.PAIN),
        SymptomStep.PAIN_NRS,
        SymptomAlert.DOCTOR,
        sourceOf = { it.severeGpFamilies().firstOrNull()?.sourceRes },
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_INJURY),
    ) { it.severe() && it.severeGpFamilies().isNotEmpty() },
    // NHS "Kidney stones": "you're in severe pain", beside the lower back's own "very painful" chip;
    // the loin rated eight or more is the app's convention for it, not NHS.
    painRule(
        "PAIN_FLANK_SEVERE",
        SymptomStep.PAIN_NRS,
        SymptomAlert.URGENT,
        R.string.pain_source_back,
        reads = setOf(SymptomStep.PAIN_AREA),
    ) {
        painThreadMarks(it).any { area -> area.region.name == "FLANK" } && it.severe()
    },
    painRule(
        "PAIN_RIBS_LONG",
        SymptomStep.PAIN_LASTED,
        SymptomAlert.URGENT,
        R.string.pain_source_belly,
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_STATUS, SymptomStep.ONSET),
    ) {
        painThreadMarks(it).any { area -> area.code in RIGHT_UPPER_BELLY } &&
            (
                (it.stillHurts() && !it.has(SymptomStep.ONSET, SymptomOption.STARTED_NOW)) ||
                    (it.gone() && it.lasted() > RIBS_LASTED_MINUTES)
                )
    },
    AlertRule(
        "PAIN_WEEKS",
        setOf(Symptom.PAIN),
        SymptomStep.PAIN_PAINKILLER,
        SymptomAlert.DOCTOR,
        sourceOf = { it.weeksFamily()?.sourceRes },
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_STATUS, SymptomStep.ONSET, SymptomStep.PAIN_SAME),
    ) { it.painkillerAnswered() && it.stillHurts() && it.weeksFamily() != null },
    AlertRule(
        "PAIN_PAINKILLER_NOT_WORKING",
        setOf(Symptom.PAIN),
        SymptomStep.PAIN_PAINKILLER,
        SymptomAlert.DOCTOR,
        sourceOf = { it.painkillerFamily()?.sourceRes },
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_STATUS, SymptomStep.ONSET, SymptomStep.PAIN_SAME),
    ) {
        it.painkillerFamily() != null && it.has(SymptomStep.PAIN_PAINKILLER, SymptomOption.YES) && it.stillHurts() &&
            (it.hasAny(SymptomStep.ONSET, STARTED_DAYS_AGO) || (it.samePainDays() ?: 0) >= PAINKILLER_DAYS)
    },
    // NHS "Back pain": "it's coming from the top of your back (between your shoulders)".
    painRule(
        "PAIN_BACK_TOP",
        SymptomStep.PAIN_PAINKILLER,
        SymptomAlert.DOCTOR,
        R.string.pain_source_back,
        reads = setOf(SymptomStep.PAIN_AREA),
    ) {
        painThreadMarks(it).any { area -> area.region == PainRegion.UPPER_BACK } && it.painkillerAnswered()
    },
    painRule(
        "PAIN_LEG_CRAMP",
        SymptomStep.PAIN_LASTED,
        SymptomAlert.DOCTOR,
        R.string.pain_source_leg,
        reads = setOf(SymptomStep.PAIN_AREA, SymptomStep.PAIN_STATUS, SymptomStep.PAIN_TYPES),
    ) {
        PainFamily.LEG in it.families() && it.has(SymptomStep.PAIN_TYPES, SymptomOption.PAIN_CRAMPING) && it.gone() &&
            it.lasted() > CRAMP_LASTED_MINUTES
    },
    // NHS "Chest pain", "Heart attack": chest pain that spreads to the arms, the back, the neck or the jaw.
    AlertRule(
        "CHEST_SPREAD",
        setOf(Symptom.CHEST_PAIN, Symptom.PAIN),
        SymptomStep.CHEST_SPREAD,
        SymptomAlert.EMERGENCY,
        R.string.symptom_source_chest_spread,
    ) { it.has(SymptomStep.CHEST_SPREAD, SymptomOption.YES) },
)

/**
 * The pain thread's red flags by body family, built with [rule] and read through [SYMPTOM_RULES]
 * like every other rule: one per family and level of its lists, then the rules by the answers.
 */
internal val PAIN_RULES: List<AlertRule> = signRules() + ANSWER_RULES
