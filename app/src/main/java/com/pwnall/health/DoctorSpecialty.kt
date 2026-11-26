package com.pwnall.health

/**
 * Which doctor a report is aimed at, and what it opens with ticked.
 *
 * A preset is a DEFAULT SELECTION and nothing more. It is not a clinical guideline, it does not say
 * what a doctor needs, and every tick it makes stays the person's to undo. The list of doctors
 * names the sections each one ticks, the "What goes in" row under the chosen doctor opens them for
 * changing, and the preview names them again before anything leaves the phone — so nobody hands
 * over a set they have not seen or could not have changed.
 *
 * [sex] follows the profile's own two values and null means everyone, which is the rule the routine
 * examinations already keep. Age is deliberately not asked about, unlike [checkupsFor]: which
 * waiting room a person is sitting in is not a screening interval, and a seventeen-year-old sees a
 * gynaecologist.
 *
 * [optional] is what the picker marks as suggested for this doctor rather than what it ticks.
 * [defaultMonths] is the window the report opens at for this doctor, until the person picks a
 * window themselves; it is always one the period control offers.
 *
 * Nothing sensitive is ever in [recommended], and nothing sensitive is in [optional] for a
 * specialty that has no clinical use for it: a sexual-activity section reaches a sheet only when
 * the person ticks it themselves and answers for it — see [DataSensitivity.SENSITIVE].
 */
enum class DoctorSpecialty(
    val titleRes: Int,
    val sex: String?,
    val recommended: Set<ReportPart>,
    val optional: Set<ReportPart> = emptySet(),
    val defaultMonths: Int = 6,
) {
    GP(
        titleRes = R.string.checkup_gp,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.PROCEDURES,
            ReportPart.IMMUNISATIONS,
            ReportPart.MEDICATIONS,
            ReportPart.LABS,
            ReportPart.WEIGHT,
            ReportPart.PRESSURE,
            ReportPart.PULSE,
            ReportPart.ACTIVITY,
            ReportPart.SLEEP,
            ReportPart.SOCIAL,
        ),
        optional = setOf(
            ReportPart.PREGNANCY,
            ReportPart.CONTRACEPTION,
            ReportPart.BODY_COMPOSITION,
            ReportPart.NUTRITION,
            ReportPart.MOOD,
            ReportPart.OXYGEN,
            ReportPart.TEMPERATURE,
            ReportPart.BOWEL,
            ReportPart.VISITS,
            ReportPart.PAIN,
            ReportPart.WORK,
        ),
    ),
    CARDIOLOGIST(
        titleRes = R.string.doctor_cardiologist,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.MEDICATIONS,
            ReportPart.PRESSURE,
            ReportPart.PULSE,
            ReportPart.OXYGEN,
            ReportPart.WEIGHT,
            ReportPart.ACTIVITY,
            ReportPart.LABS,
        ),
        optional = setOf(ReportPart.SLEEP, ReportPart.SOCIAL, ReportPart.BODY_COMPOSITION, ReportPart.NUTRITION),
        defaultMonths = 3,
    ),
    ENDOCRINOLOGIST(
        titleRes = R.string.doctor_endocrinologist,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.MEDICATIONS,
            ReportPart.LABS,
            ReportPart.WEIGHT,
            ReportPart.BODY_COMPOSITION,
            ReportPart.PRESSURE,
            ReportPart.NUTRITION,
            ReportPart.ACTIVITY,
        ),
        optional = setOf(ReportPart.CYCLE, ReportPart.SLEEP, ReportPart.MOOD, ReportPart.SOCIAL),
        defaultMonths = 12,
    ),
    GYNECOLOGIST(
        titleRes = R.string.checkup_gynecologist,
        sex = "FEMALE",
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.PREGNANCY,
            ReportPart.CONTRACEPTION,
            ReportPart.MEDICATIONS,
            ReportPart.CYCLE,
            ReportPart.LABS,
            ReportPart.WEIGHT,
            ReportPart.PRESSURE,
        ),
        // The one specialty a sexual-health section can be clinically useful to, and still only as
        // a suggestion: it is ticked by the person, with its own question, or not at all.
        optional = setOf(
            // Suggested, never ticked: a preset may put a sensitive row forward and no preset may
            // answer its question. The symptoms are the row a gynaecologist actually asks about.
            ReportPart.SEXUAL_SYMPTOMS,
            ReportPart.SEXUAL_HEALTH,
            ReportPart.MOOD,
            ReportPart.BODY_COMPOSITION,
            ReportPart.VISITS,
        ),
    ),
    DIETITIAN(
        titleRes = R.string.doctor_dietitian,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.NUTRITION,
            ReportPart.WEIGHT,
            ReportPart.BODY_COMPOSITION,
            ReportPart.ACTIVITY,
            ReportPart.LABS,
        ),
        optional = setOf(ReportPart.SLEEP, ReportPart.BOWEL, ReportPart.MEDICATIONS, ReportPart.SOCIAL),
        defaultMonths = 3,
    ),
    SPORTS(
        titleRes = R.string.doctor_sports,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.PROCEDURES,
            ReportPart.ACTIVITY,
            ReportPart.PULSE,
            ReportPart.WEIGHT,
            ReportPart.BODY_COMPOSITION,
            ReportPart.SLEEP,
            ReportPart.NUTRITION,
        ),
        optional = setOf(
            ReportPart.PRESSURE,
            ReportPart.OXYGEN,
            ReportPart.LABS,
            ReportPart.MEDICATIONS,
            ReportPart.PAIN,
        ),
        defaultMonths = 3,
    ),
    SOMNOLOGIST(
        titleRes = R.string.doctor_somnologist,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.SLEEP,
            ReportPart.PULSE,
            ReportPart.OXYGEN,
            ReportPart.ACTIVITY,
            ReportPart.MEDICATIONS,
        ),
        optional = setOf(
            ReportPart.MOOD,
            ReportPart.SOCIAL,
            ReportPart.WEIGHT,
            ReportPart.NUTRITION,
            ReportPart.WORK,
        ),
        defaultMonths = 3,
    ),
    UROLOGIST(
        titleRes = R.string.checkup_urologist,
        sex = "MALE",
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.MEDICATIONS,
            ReportPart.LABS,
            ReportPart.BOWEL,
            ReportPart.WEIGHT,
            ReportPart.PRESSURE,
        ),
        optional = setOf(
            ReportPart.SEXUAL_SYMPTOMS,
            ReportPart.SEXUAL_HEALTH,
            // Suggested, never ticked, and asked for with its own question like the two above.
            ReportPart.MORNING_ERECTIONS,
            ReportPart.ACTIVITY,
            ReportPart.VISITS,
        ),
    ),

    /**
     * Somebody who talks rather than examines, and asks how the weeks went: mood and the wound-up
     * dial, sleep, work hours, drinking and smoking, movement, and what is being taken. Pain and the
     * visits are suggested. Nothing sensitive, ticked or suggested: a count of sex is something a
     * person brings into that room themselves, not something a preset puts forward.
     */
    PSYCHOLOGIST(
        titleRes = R.string.doctor_psychologist,
        sex = null,
        recommended = setOf(
            ReportPart.PROBLEMS,
            ReportPart.MEDICATIONS,
            ReportPart.MOOD,
            ReportPart.SLEEP,
            ReportPart.WORK,
            ReportPart.SOCIAL,
            ReportPart.ACTIVITY,
        ),
        optional = setOf(ReportPart.PAIN, ReportPart.VISITS),
        defaultMonths = 3,
    ),

    /**
     * Not a doctor: the answer for somebody who wants to say what goes in themselves. It opens with
     * every routine section the person is offered — [DataSensitivity.ROUTINE] — which is also what
     * the page opens with before any doctor is chosen.
     *
     * The reproductive sections are left for the person to tick: pregnancy status, contraception
     * and the cycle are ticked by the doctors they were written for and by nobody's default, and an
     * answer that names no doctor names none of those. The sensitive ones never arrive ticked from
     * anywhere.
     *
     * It suggests nothing. The picker marks a suggestion on the row itself: under an answer
     * that is not a doctor, that mark would be the app putting sexual activity forward on nobody's
     * behalf.
     */
    CUSTOM(
        titleRes = R.string.doctor_custom,
        sex = null,
        recommended = ReportPart.entries.filter { it.sensitivity == DataSensitivity.ROUTINE }.toSet(),
    ),
}

/** Which doctors this person can aim a report at, in the order the enum declares them. */
fun specialtiesFor(sex: String): List<DoctorSpecialty> =
    DoctorSpecialty.entries.filter { it.sex == null || it.sex == sex }

/**
 * What [specialty] opens with ticked on this person's profile.
 *
 * Three things are filtered out of every preset, whatever it names: a section this person is not
 * offered at all, the person's own section (which is always printed), and anything sensitive. The
 * last is the rule that matters: a preset can suggest a sexual-health section in [optional], and no
 * preset can ever tick one.
 */
fun preselectedParts(specialty: DoctorSpecialty, sex: String): Set<ReportPart> {
    val ticked = reportPartsFor(sex)
        .filter { it in specialty.recommended && it.sensitivity != DataSensitivity.SENSITIVE }
        .toSet()
    // The app's own comparisons follow what they read: they print nothing a doctor did not ask for.
    return ticked + listOfNotNull(
        ReportPart.LAB_CONTEXT.takeIf { ReportPart.LABS in ticked },
        // Only with the food: a course's own doses are on its medicines lines already, and the table
        // of daily figures beside a cardiologist's or a sleep doctor's sheet would be a page of noise.
        ReportPart.MICRONUTRIENTS.takeIf { ReportPart.NUTRITION in ticked },
        ReportPart.CHANGES.takeIf { ticked.isNotEmpty() },
    )
}

/** What the picker marks as suggested for this doctor, unticked, in the enum's own order. */
fun suggestedParts(specialty: DoctorSpecialty, sex: String): List<ReportPart> =
    reportPartsFor(sex).filter { it in specialty.optional }
