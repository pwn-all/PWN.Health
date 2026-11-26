package com.pwnall.health

import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A piece of the report's text, before it is a string.
 *
 * The builder below runs with no Resources in reach, and it has to: every decision in it — which
 * rows a period holds, whether a pressure stayed a pair, what an adherence figure came to, whether
 * a minor gets a BMI verdict — is one a test has to be able to pin without a device. So the report
 * carries references to the app's own strings and plurals, and the renderer resolves them in the
 * language the phone is set to at the moment the PDF is written. A figure that the app does not
 * translate — "128/84", a medicine's name — travels as [Plain].
 */
sealed interface ReportText {
    /** A figure or a name the app does not translate. */
    data class Plain(val text: String) : ReportText

    /** A string resource with its arguments; an argument may itself be a [ReportText]. */
    data class Res(val id: Int, val args: List<Any> = emptyList()) : ReportText

    /** A plural, with the count both choosing the form and filling it. */
    data class Quantity(val id: Int, val count: Int) : ReportText

    /** A day, printed in the reader's locale by the renderer rather than as the ISO the row stores. */
    data class Day(val day: LocalDate) : ReportText

    /**
     * Several texts in one cell, separated the way the app's own subtitles separate their parts. A
     * [LIST_COMMA] is printed as the reader's language writes a list's comma — "、" in Japanese, "،"
     * in Arabic — as the translated sentences around it do; the middle dot is one mark in every language.
     */
    data class Join(val parts: List<ReportText>, val separator: String = " · ") : ReportText
}

/** The separator of a list on the sheet, which the renderer prints as the reader's own comma. */
internal const val LIST_COMMA = ", "

/** The six headings the picker groups its rows under, in the order it shows them. */
enum class ReportGroup(val titleRes: Int) {
    ESSENTIAL(R.string.report_group_essential),
    VITALS(R.string.report_group_vitals),
    LIFESTYLE(R.string.report_group_lifestyle),
    HISTORY(R.string.report_group_history),
    REPRODUCTIVE(R.string.report_group_reproductive),
    SENSITIVE(R.string.report_group_sensitive),
}

/**
 * The sections a report can have, in the order they are printed.
 *
 * The order follows the International Patient Summary's own: what is wrong, what cannot be given,
 * what is being taken, what was measured — because that is the order a doctor reads in, and because
 * a summary that opens with step counts buries the three things it exists to carry.
 *
 * [core] sections are printed whether or not anything fills them. A patient summary owes an answer
 * about conditions and allergies, and a missing heading would read as "none" — see
 * [SectionPresence]. What fills them is what the person wrote down: the conditions in the medical
 * history, and the answer to the allergy question on the profile. Of the two, only the allergies
 * carry [pickable] = false: the answer is the profile's own, and a sheet without it would read as
 * "none".
 *
 * Every part but [PERSON] is also a row in the picker, which is why each carries [bodyRes]: a
 * heading on paper says what the section is to a doctor, the line under it in the picker says what
 * the person would be handing over. They are separate sentences.
 *
 * The four instruments each stand on their own — a cardiologist asked for a pulse and a pressure,
 * not for the scale's estimate of body water — and the scale's four readings stay together under
 * [BODY_COMPOSITION], because they all come off one row of one weigh-in.
 */
@Suppress("LongParameterList") // A declarative table; every column is read by a different caller.
enum class ReportPart(
    val titleRes: Int,
    val bodyRes: Int,
    val group: ReportGroup,
    val sensitivity: DataSensitivity = DataSensitivity.ROUTINE,
    /** The vitals this section prints, in the order it prints them; empty where it prints none. */
    val vitals: Set<Vital> = emptySet(),
    /** Whose report it can appear in. Follows the profile's own two values; null means everyone. */
    val sex: String? = null,
    /** Printed even when empty, because a patient summary owes the answer. */
    val core: Boolean = false,
    /**
     * Offered as a tick. False for the profile's own answers — the person, the allergies — which
     * every sheet carries.
     */
    val pickable: Boolean = true,
    /**
     * Whether the section stands outside the report's window.
     *
     * The written-down history does: an appendix out in 1998 belongs on a sheet about last March,
     * and an empty one of these sections says "nothing written down" rather than "nothing in this
     * period" — see [emptyLabelRes].
     */
    val timeless: Boolean = false,
    /**
     * Whether the section is the app's own arithmetic over other sections rather than a record.
     *
     * These read only from sections that are printed, and are ticked by a preset whenever what
     * they read is: see [preselectedParts].
     */
    val computed: Boolean = false,
) {
    PERSON(
        titleRes = R.string.report_section_person,
        bodyRes = R.string.report_part_person_body,
        group = ReportGroup.ESSENTIAL,
        core = true,
        pickable = false,
    ),

    /**
     * Conditions and allergies: a heading on every sheet, filled or not.
     *
     * The conditions are the medical history's CONDITION rows, the allergies the answer to the
     * profile's allergy question — both as the person wrote them, and neither ever derived from a
     * measurement. The headings stand when nothing fills them because a doctor reading a patient
     * summary has to be able to tell "this person reports none" from "nothing was written down":
     * an empty one says the second, and only the allergy answer "none that I know of" says the first.
     */
    PROBLEMS(
        titleRes = R.string.report_part_problems,
        bodyRes = R.string.report_part_problems_body,
        group = ReportGroup.ESSENTIAL,
        core = true,
        timeless = true,
    ),
    ALLERGIES(
        titleRes = R.string.report_part_allergies,
        bodyRes = R.string.report_part_allergies_body,
        group = ReportGroup.ESSENTIAL,
        core = true,
        timeless = true,
        pickable = false,
    ),
    MEDICATIONS(
        titleRes = R.string.med_list_title,
        bodyRes = R.string.report_part_medications_body,
        group = ReportGroup.ESSENTIAL,
    ),
    LABS(
        titleRes = R.string.labs_title,
        bodyRes = R.string.report_part_labs_body,
        group = ReportGroup.ESSENTIAL,
    ),

    /** What the diary and the courses held around each blood test, and a supplement against its marker. */
    LAB_CONTEXT(
        titleRes = R.string.report_part_lab_context,
        bodyRes = R.string.report_part_lab_context_body,
        group = ReportGroup.ESSENTIAL,
        computed = true,
    ),

    /**
     * Operations and vaccinations, as they were written down.
     *
     * Neither is bounded by the report's window and neither pretends to be: an appendix removed in
     * 1998 is part of what a surgeon needs to know in 2026, and a report covering the last quarter
     * that dropped it would be hiding it. The same is true of the two sections above.
     */
    PROCEDURES(
        titleRes = R.string.history_procedures,
        bodyRes = R.string.report_part_procedures_body,
        group = ReportGroup.HISTORY,
        timeless = true,
    ),
    IMMUNISATIONS(
        titleRes = R.string.history_immunisations,
        bodyRes = R.string.report_part_immunisations_body,
        group = ReportGroup.HISTORY,
        timeless = true,
    ),
    WEIGHT(
        titleRes = R.string.weight,
        bodyRes = R.string.report_part_weight_body,
        group = ReportGroup.VITALS,
    ),
    BODY_COMPOSITION(
        titleRes = R.string.report_part_composition,
        bodyRes = R.string.report_part_composition_body,
        group = ReportGroup.VITALS,
        vitals = setOf(Vital.BODY_FAT, Vital.MUSCLE_MASS, Vital.BODY_WATER, Vital.BASAL_METABOLISM),
    ),

    /**
     * What a tape measure says, which is a different claim from what a scale says.
     *
     * Its own section rather than a line under the body composition: those four figures are a
     * scale's estimates from an impedance, these three are a length somebody measured, and the one
     * of them anybody publishes a threshold for is the waist.
     */
    GIRTH(
        titleRes = R.string.report_part_girth,
        bodyRes = R.string.report_part_girth_body,
        group = ReportGroup.VITALS,
        vitals = setOf(Vital.WAIST, Vital.HIP, Vital.CHEST),
    ),
    PRESSURE(
        titleRes = R.string.direct_bluetooth_blood_pressure_title,
        bodyRes = R.string.report_part_pressure_body,
        group = ReportGroup.VITALS,
        vitals = setOf(Vital.BLOOD_PRESSURE),
    ),
    PULSE(
        titleRes = R.string.heart_rate,
        bodyRes = R.string.report_part_pulse_body,
        group = ReportGroup.VITALS,
        // Variability travels with the pulse: it is the same strap and the same sitting still, and
        // a section of its own would be one line under a heading on most sheets.
        vitals = setOf(Vital.HEART_RATE, Vital.RESTING_HEART_RATE, Vital.HRV),
    ),
    OXYGEN(
        titleRes = R.string.direct_bluetooth_spo2,
        bodyRes = R.string.report_part_oxygen_body,
        group = ReportGroup.VITALS,
        vitals = setOf(Vital.OXYGEN_SATURATION),
    ),
    TEMPERATURE(
        titleRes = R.string.direct_bluetooth_temperature_title,
        bodyRes = R.string.report_part_temperature_body,
        group = ReportGroup.VITALS,
        vitals = setOf(Vital.BODY_TEMPERATURE),
    ),
    ACTIVITY(
        titleRes = R.string.add_section_activity,
        bodyRes = R.string.report_part_activity_body,
        group = ReportGroup.LIFESTYLE,
    ),
    SLEEP(
        titleRes = R.string.sleep,
        bodyRes = R.string.report_part_sleep_body,
        group = ReportGroup.LIFESTYLE,
    ),
    NUTRITION(
        titleRes = R.string.home_card_food,
        bodyRes = R.string.report_part_nutrition_body,
        group = ReportGroup.LIFESTYLE,
    ),

    /** Vitamins and minerals from the food logged and the doses marked, against the daily figures. */
    MICRONUTRIENTS(
        titleRes = R.string.report_part_micronutrients,
        bodyRes = R.string.report_part_micronutrients_body,
        group = ReportGroup.LIFESTYLE,
        computed = true,
    ),
    MOOD(
        titleRes = R.string.mood_title,
        bodyRes = R.string.report_part_mood_body,
        group = ReportGroup.LIFESTYLE,
    ),

    /** Alcohol and tobacco as the journal counts them: social history, in the summary's own sense. */
    SOCIAL(
        titleRes = R.string.report_part_social,
        bodyRes = R.string.report_part_social_body,
        group = ReportGroup.LIFESTYLE,
    ),

    /**
     * The work timer's hours, counted: weeks, long days, evenings, days off and rests. Time that was
     * tracked, never how heavy the work felt — see [workSection].
     */
    WORK(
        titleRes = R.string.work_title,
        bodyRes = R.string.report_part_work_body,
        group = ReportGroup.LIFESTYLE,
    ),
    BOWEL(
        titleRes = R.string.bathroom_title,
        bodyRes = R.string.report_part_bowel_body,
        group = ReportGroup.HISTORY,
    ),

    /**
     * Where it hurt and how often, per area of the pain map, over the days the diary was answered —
     * see [painSection]. Beside the bathroom counts: the same kind of self-kept symptom record.
     */
    PAIN(
        titleRes = R.string.pain_title,
        bodyRes = R.string.report_part_pain_body,
        group = ReportGroup.HISTORY,
    ),

    /** Appointments the person marked as attended: where they have already been, not what was found. */
    VISITS(
        titleRes = R.string.report_part_visits,
        bodyRes = R.string.report_part_visits_body,
        group = ReportGroup.HISTORY,
    ),

    /**
     * Pregnancy and what is being used to avoid one: the two answers a prescription can turn on.
     *
     * Reproductive rather than sensitive — a doctor about to prescribe is the person who has to
     * know — but neither is ticked by a preset that was not written for it, nor by the ticks the
     * page opens with or by "My own choice", and both are the patient's own statement rather than
     * a test.
     */
    PREGNANCY(
        titleRes = R.string.history_pregnancy_status,
        bodyRes = R.string.report_part_pregnancy_body,
        group = ReportGroup.REPRODUCTIVE,
        sensitivity = DataSensitivity.REPRODUCTIVE,
        sex = "FEMALE",
        timeless = true,
    ),
    CONTRACEPTION(
        titleRes = R.string.history_contraception,
        bodyRes = R.string.report_part_contraception_body,
        group = ReportGroup.REPRODUCTIVE,
        sensitivity = DataSensitivity.REPRODUCTIVE,
        timeless = true,
    ),
    CYCLE(
        titleRes = R.string.report_part_cycle,
        bodyRes = R.string.report_part_cycle_body,
        group = ReportGroup.REPRODUCTIVE,
        sensitivity = DataSensitivity.REPRODUCTIVE,
        sex = "FEMALE",
    ),

    /**
     * The symptoms noted during sex, without the tally of it.
     *
     * A section of its own because pain, bleeding after sex, dryness, difficulty with an
     * erection — the questions a clinician opens with — would otherwise be printed inside the
     * section that also counts the acts, protected and unprotected, and a woman could not tell her
     * gynaecologist that intercourse hurts without also handing over how often she had it.
     *
     * It is still a sensitive one. Sensitive because the ticks live
     * on the act rows and nowhere else, so a symptom does disclose that sex happened; every guard
     * the class carries therefore holds here too — no preset ticks it, "select all" does not reach
     * it, it is never announced as withheld, and it goes in only after its own question. What it
     * does not carry is the number, the dates and the protection, which is the whole point.
     */
    SEXUAL_SYMPTOMS(
        titleRes = R.string.report_part_sexual_symptoms,
        bodyRes = R.string.report_part_sexual_symptoms_body,
        group = ReportGroup.SENSITIVE,
        sensitivity = DataSensitivity.SENSITIVE,
    ),

    /**
     * Sexual activity, counted.
     *
     * The one section of this report that can put somebody at risk if it reaches the wrong desk, so
     * it is the one section no preset ticks, no "select all" reaches, and nothing but the person's
     * own answer to its own question can include — see [DataSensitivity.SENSITIVE].
     */
    SEXUAL_HEALTH(
        titleRes = R.string.report_part_sexual,
        bodyRes = R.string.report_part_sexual_body,
        group = ReportGroup.SENSITIVE,
        sensitivity = DataSensitivity.SENSITIVE,
    ),

    /**
     * The morning check-ins, counted over the period and by week, never morning by morning — see
     * [morningSection].
     *
     * A man's own section, and a sensitive one with every guard the class carries: no preset ticks
     * it, "select all" does not reach it, and it goes in only after its own question, which is not
     * the sexual-activity one — that section's dated depth says nothing about mornings, and ticking
     * it brings none of them.
     */
    MORNING_ERECTIONS(
        titleRes = R.string.report_part_morning,
        bodyRes = R.string.report_part_morning_body,
        group = ReportGroup.SENSITIVE,
        sensitivity = DataSensitivity.SENSITIVE,
        sex = "MALE",
    ),

    /**
     * What changed between the start and the end of the period, side by side, last on the sheet.
     *
     * Its sensitivity is whatever the sections it reads carry: it reads only printed ones.
     */
    CHANGES(
        titleRes = R.string.report_part_changes,
        bodyRes = R.string.report_part_changes_body,
        group = ReportGroup.ESSENTIAL,
        computed = true,
    ),
}

/**
 * How a section with nothing in it says so.
 *
 * "No data recorded for this period" is the right sentence for a measurement and the wrong one for
 * a history: an operation, a vaccination or a diagnosis is not news from the window, and a person
 * who has had none is saying something about their life rather than about the last three months.
 * The timeless sections say "nothing written down" instead, which is what is actually true — and
 * neither sentence is ever "none", which no absence in this app may be read as.
 */
internal fun ReportSection.emptyLabelRes(): Int =
    if (presence == SectionPresence.NO_DATA && part.timeless) {
        R.string.section_nothing_written
    } else {
        presence.labelRes
    }

/** Which sections a person of [sex] can be offered as a tick, in the order the enum declares them. */
fun reportPartsFor(sex: String): List<ReportPart> =
    ReportPart.entries.filter { it.pickable && (it.sex == null || it.sex == sex) }

/**
 * Which sections this person is offered as a tick on [today]: [reportPartsFor] their sex, behind the
 * age gate the rest of the app keeps.
 *
 * The gate's rule is that a door it closes is not drawn at all — see [sexLogOffered] and
 * [habitsOffered] — and the picker keeps it too: otherwise a thirteen-year-old would be shown the
 * sensitive group with its three questions, and a GP's preset would print an "Alcohol and
 * tobacco" heading over nothing. Rows of either kind that a restored backup brought along could
 * leave the phone only through here.
 */
fun reportPartsFor(profile: UserProfile, today: LocalDate): List<ReportPart> {
    val acts = sexLogOffered(profile.birthDate, today)
    val habits = habitsOffered(profile.birthDate, today)
    return reportPartsFor(profile.sex).filter {
        (acts || it.sensitivity != DataSensitivity.SENSITIVE) && (habits || it != ReportPart.SOCIAL)
    }
}

/**
 * One line of a section: what it is, the figure, and the note that qualifies the figure — a date, a
 * verdict, the body that published a band. A [detail] row belongs to the row above it and is drawn
 * indented and quieter: the readings under a vital, the earlier results under a marker.
 */
data class ReportRow(
    val label: ReportText,
    val value: ReportText,
    val note: ReportText? = null,
    val detail: Boolean = false,
)

/**
 * One section of the sheet: what it is, what is in it, and what a doctor needs in order to read
 * that as evidence rather than as a claim.
 *
 * [presence] is the difference between "none", "not recorded" and "not sent" — see
 * [SectionPresence]. [completeness] says how much of the period the figures actually cover, so an
 * average of four days is not read as an average of a quarter. [provenance] says who wrote the
 * numbers: a cuff, a watch or a thumb. [caveatRes] is the one line that qualifies the whole
 * section, where it needs one.
 */
data class ReportSection(
    val part: ReportPart,
    val rows: List<ReportRow>,
    val presence: SectionPresence = SectionPresence.PRESENT,
    val completeness: Completeness? = null,
    val provenance: List<Provenance> = emptyList(),
    val caveatRes: Int? = null,
    /** Dated rows kept out of the sheet's body and printed in the appendix, where one is asked for. */
    val appendix: List<ReportRow> = emptyList(),
    /**
     * Paragraphs the section needs said once, at the foot of the sheet with its other notes rather
     * than squeezed into a row: general background a reader may want, never a figure about the person.
     */
    val footnotes: List<ReportText> = emptyList(),
    /**
     * Pictures of the section's figures over time, drawn under its heading and above its rows: a
     * doctor reads a year of steps or three years of LDL off a line faster than off a column of
     * dates. They show what the rows already say — every figure on a chart is one the section
     * holds — and never replace the rows, which stay the record.
     */
    val charts: List<ReportChart> = emptyList(),
    /**
     * Where the pain was painted, drawn on the body under the section's charts: the doctor sees the
     * marks as the person made them, not the areas a program read off them.
     */
    val painDrawing: PainSheetDrawing? = null,
)

/**
 * The period's pain paintings for the sheet: the plates for [sex] ([bodyPlateRes]) and the strokes of
 * every entry that has any to show ([painShownStrokes]), one list per entry, exactly as painted.
 * Built here, without Resources, like [ReportChart]; the layout places it and the renderer draws it.
 *
 * An entry with nothing painted — its areas chosen from the list — is not in [entries]: the rows
 * already name it, and a mark drawn for it would be a place nobody pointed at.
 */
data class PainSheetDrawing(val sex: String, val entries: List<List<PainStroke>>)

/**
 * One chart: series of dated figures in the unit the section prints, over [from]..[to].
 *
 * Built here, without Resources, so a test can pin what is drawn; the PDF renderer decides only how.
 * [band] is the reference range the section prints, shaded; [marks] are days worth a vertical line —
 * a course started, a period that began before the chart. [yFloor] and [yCeiling] fix the axis where
 * the scale has ends of its own (mood 1–5, a percentage); otherwise the renderer fits the data and the
 * band. [width] lets two small charts share a row, which is how a panel's markers are laid out.
 */
data class ReportChart(
    val series: List<ChartSeries>,
    val unit: ReportText,
    val from: LocalDate,
    val to: LocalDate,
    val title: ReportText? = null,
    val band: ChartBand? = null,
    val marks: List<ChartMark> = emptyList(),
    val yFloor: Double? = null,
    val yCeiling: Double? = null,
    val width: ChartWidth = ChartWidth.FULL,
)

/** One line, set of bars or set of dots; [label] names it in the legend where a chart holds more than one. */
data class ChartSeries(
    val points: List<ChartPoint>,
    val style: ChartStyle,
    val label: ReportText? = null,
)

/**
 * A figure on a day. [label] is printed beside the point, which is what a lab result wants; [note] is
 * printed with it where the figure's row carries a word the figure is not read without — a glucose or
 * a triglyceride after food is held to another band than a fasting one, and the row says which.
 */
data class ChartPoint(val day: LocalDate, val value: Double, val label: String? = null, val note: ReportText? = null)

enum class ChartStyle { LINE, LINE_DOTS, DOTS, BARS }

enum class ChartWidth { FULL, HALF }

/** The shaded reference range; either edge may be missing, as the section's own range may be one-sided. */
data class ChartBand(val low: Double?, val high: Double?)

/** A day marked by a vertical line and a short label. */
data class ChartMark(val day: LocalDate, val label: ReportText)

/** How much of the sexual-health section a person chose to send, where they chose to send any. */
enum class SexualDetail { SUMMARY, DETAILED }

/**
 * Everything the phone holds that a report is built from, handed in rather than read here.
 *
 * The builder asks nothing of the database: the screen collects these, so the same sheet can be
 * built in a test from rows written by hand.
 *
 * The screen hands each list in whole, whatever the window. [doctorReport] cuts what a section
 * prints to the period itself, and reads past it where a section is read against what came before:
 * a marker's earlier results, the newest allergy panel, a course begun before the window opened.
 */
data class ReportSources(
    val profile: UserProfile,
    val entries: List<JournalEntry>,
    val days: List<DaySteps>,
    val medications: List<Medication> = emptyList(),
    val visits: List<Appointment> = emptyList(),
    /**
     * The written-down history, printed whole.
     *
     * Not cut to the period, unlike what the sheet prints of everything above it: a diagnosis, an
     * operation or a vaccination is not news from the window, it is the thing the window is read against.
     */
    val history: List<MedicalHistoryRow> = emptyList(),
    /**
     * The usual days off and where a long day starts, as the Work screen keeps them: the person's own
     * settings, which the work section counts by and says it counts by. They live beside the timer
     * rather than in the journal, so the screen hands them in with the rest.
     */
    val workDaysOff: Set<DayOfWeek> = WORK_DAYS_OFF_DEFAULT,
    val longDayMinutes: Int = WORK_LONG_DAY_DEFAULT_MINUTES,
)

/**
 * What the person asked for: the window, who it is for, whose system that doctor works in, what
 * goes in, and how much of the sensitive section goes with it.
 *
 * [parts] is the whole of what the sheet may carry. A part that is not in it is not printed, and a
 * sensitive part is not printed unless it is in it — there is no other door into the sheet.
 */
data class ReportRequest(
    val from: LocalDate,
    val to: LocalDate,
    val specialty: DoctorSpecialty? = null,
    val country: CountryProfile = CountryProfileRegistry.international,
    val parts: Set<ReportPart> = ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet(),
    val sexualDetail: SexualDetail = SexualDetail.SUMMARY,
) {
    /**
     * Whether the sheet prints pounds, feet and inches, Fahrenheit and mg/dL.
     *
     * The country answers where it has an answer of its own; the international profile has none to
     * give and defers to whatever the person set the app to. Either way the stored figure is
     * unchanged — the scale is a presentation.
     */
    fun imperial(profile: UserProfile): Boolean = country.imperial ?: profile.imperial
}

/**
 * The report a person hands a doctor: the last months of their own record, section by section, every
 * figure read against the band the app already reads it against, and the body that published that
 * band named beside it. [notes] are the paragraphs printed last — what the bands are and are not.
 *
 * [specialty] is who it was put together for, printed on the sheet rather than deciding what is on
 * it: the ticks it preselected are the person's to change afterwards, and a sheet that named a
 * cardiologist while its owner had since ticked everything would be describing itself wrongly.
 *
 * [filled] is every section the period holds something for, whether or not it was ticked — what the
 * picker greys out as empty. A quarter with nothing in it should be discovered on the phone and not
 * in a waiting room, and "nothing recorded" has to stay distinguishable from "nothing chosen".
 */
data class DoctorReport(
    val request: ReportRequest,
    val sections: List<ReportSection>,
    val notes: List<ReportText>,
    val filled: Set<ReportPart>,
    val attachments: List<ReportAttachment> = emptyList(),
) {
    val from: LocalDate get() = request.from
    val to: LocalDate get() = request.to
    val specialty: DoctorSpecialty? get() = request.specialty

    /**
     * Nothing but what every sheet carries would be printed — the person, and the allergies, which are
     * the profile's own answer rather than a tick — so the sheet has no section of the record on it.
     * Without this, an answered allergy question would make a sheet with nothing ticked look full,
     * and the page would keep quiet about the ticks.
     */
    val empty: Boolean get() = sections.all { !it.part.pickable || it.presence != SectionPresence.PRESENT }

    /**
     * Whether the sheet carries sexual activity or its symptoms: a sensitive section other than the
     * morning check-ins. What the preview says of sexual activity, "INCLUDED" or "not included".
     *
     * A section that was ticked and holds nothing for the period counts: its heading is printed
     * over "no data recorded", and a heading says the record is kept and was chosen. The preview
     * must not call that sheet "not included" under its own line naming the section.
     */
    val carriesSexualActivity: Boolean
        get() = sections.any {
            it.part != ReportPart.MORNING_ERECTIONS && it.part.sensitivity == DataSensitivity.SENSITIVE
        }

    /**
     * Whether the sheet carries the morning check-ins, which the preview names on a line of their own:
     * their question promised nothing about sexual activity, and a sheet carrying only them must not
     * be announced as carrying it — nor one carrying them as carrying nothing sensitive. A heading
     * over nothing counts here too.
     */
    val carriesMornings: Boolean get() = sections.any { it.part == ReportPart.MORNING_ERECTIONS }
}

/**
 * The same reading, arriving twice, counted once.
 *
 * A watch writes a measurement, the app it pairs with writes it again, and Health Connect hands
 * both to this app as separate records — the import deduplicates steps, sleep and heart rate and
 * nothing else, so a blood pressure taken once can stand in the journal twice. On a screen that is
 * a cosmetic repeat; on a sheet that prints "14 readings" and a mean over them it is a wrong
 * figure, so the report collapses readings that agree on the second and on every value.
 *
 * Deliberately narrow. Two readings a minute apart are two readings — somebody measuring twice is a
 * real thing a cuff records — and nothing here merges across time.
 */
internal fun deduplicated(readings: List<VitalReading>): List<VitalReading> =
    readings.distinctBy { Triple(it.occurredAt / 1000L, it.value, it.secondary) }

/** What the period asked of one course and what was marked: the two figures behind the percentage. */
data class Adherence(val taken: Int, val planned: Int) {
    /** Rounded the way the medicine chart rounds the figure over its bars. */
    val percent: Int get() = (taken * 100.0 / planned).roundToInt()
}

/**
 * One weigh-in the journal holds, kept with its day so the trend can say when its ends were, and
 * with the row's own source so the sheet can say whether a scale or a thumb wrote it.
 */
data class WeightReading(
    val kg: Double,
    val day: String,
    val occurredAt: Long,
    val source: String? = null,
)

/**
 * The report over the window [request] names, both days included, built from rows the caller holds.
 *
 * Nothing is asked of the database and nothing is stored: the vitals come through [vitalHistory],
 * the blood tests through [labHistory], the doses through [medAdherence], the nights through
 * [sleepSummary] and the cycle through [cycleSummary] — the same readers the screens use, so the
 * sheet a doctor is handed cannot disagree with the phone it came from.
 *
 * Three rules decide what reaches the page. A section the person did not tick is not built into the
 * sheet, and says [SectionPresence.NOT_INCLUDED] rather than vanishing. A section nothing filled
 * says [SectionPresence.NO_DATA], because a heading a doctor cannot find is read as "normal". And a
 * sensitive section is printed only where [ReportRequest.parts] names it — there is no preset, no
 * "select all" and no default that can put one there.
 *
 * Every section is built before the filter runs, so [DoctorReport.filled] can say which of them the
 * period actually holds something for without a second pass over the journal. The notes follow what
 * was printed rather than what was recorded: a sheet without the blood tests does not credit the
 * bodies who published bands it never printed.
 *
 * [today] is the day the report is made, which decides the age the BMI verdict is read at.
 * [nowMinute] is the minute of that day it is made at, so a dose due this evening is not yet
 * counted as missed — the medicine chart makes the same allowance.
 */
@Suppress("LongMethod") // One list of every section, read top to bottom; splitting it hides the order.
fun doctorReport(
    sources: ReportSources,
    request: ReportRequest,
    today: LocalDate,
    nowMinute: Int = MINUTES_IN_DAY,
    zone: ZoneId = ZoneId.systemDefault(),
): DoctorReport {
    val profile = sources.profile
    val from = request.from
    val to = request.to
    val period = from.toString()..to.toString()
    val inPeriod = sources.entries.filter { it.day in period }
    val stepDays = sources.days.filter { it.day in period }
    val imperial = request.imperial(profile)
    val vitals = vitalHistory(inPeriod, profile).mapValues { (_, readings) -> deduplicated(readings) }
    val labs = ReportLabs(from, labHistory(sources.entries.filter { it.day <= to.toString() }, profile))
    val courses = sources.medications.filter { it.overlaps(from, to) }
    val visits = reportVisits(sources.visits, from, minOf(to, today), zone)
    // A period that ended before today is counted to its own last day, whole: the minute of the day
    // the sheet is made at belongs to today, not to that day.
    val periodMinute = if (to.isBefore(today)) MINUTES_IN_DAY else nowMinute
    // Kept day by day for the chart under each course, and summed for the figure beside it.
    val doseDays = courses.associateWith { course ->
        medAdherence(listOf(course), inPeriod, to, daysIn(from, to), periodMinute, zone)
    }
    val adherence = doseDays.mapValues { (_, days) -> adherenceOf(days) }
    val weights = weightReadings(inPeriod)
    val bmiVerdict = bmiVerdictOf(weights, profile, today)
    val days = daysIn(from, to)
    val span = from..to
    val built = historySections(sources.history, profile.sex) + listOfNotNull(
        personSection(profile, weights.lastOrNull(), request, today, imperial, bmiVerdict, zone),
        allergySection(profile.allergyAnswer(), latestAllergyPanel(sources.entries)),
        weightSection(weights, imperial, days, zone, span),
        vitalSection(ReportPart.BODY_COMPOSITION, vitals, profile, imperial, days, span),
        vitalSection(ReportPart.GIRTH, vitals, profile, imperial, days, span),
        activitySection(stepDays, inPeriod, days, span),
        nutritionSection(inPeriod, days, span),
        sleepSection(sleepSummary(inPeriod, zone), nightMinutesByDay(inPeriod), days, span),
        vitalSection(ReportPart.PULSE, vitals, profile, imperial, days, span),
        vitalSection(ReportPart.PRESSURE, vitals, profile, imperial, days, span),
        vitalSection(ReportPart.OXYGEN, vitals, profile, imperial, days, span),
        vitalSection(ReportPart.TEMPERATURE, vitals, profile, imperial, days, span),
        moodSection(moodSummary(inPeriod), inPeriod, days, span),
        socialSection(inPeriod, days, span),
        // Work and pain are handed the whole journal rather than the period's rows: an episode, the
        // first record of an area, or the first day the work timer was used can lie before the period.
        // Neither reads past its last day, and nor do the mornings.
        workSection(sources, span, zone),
        bowelSection(inPeriod, days, span),
        painSection(
            sources.entries,
            span,
            showSoreness = ReportPart.ACTIVITY in request.parts,
            zone = zone,
            sex = profile.sex,
        ),
        visitsSection(visits, zone),
        labsSection(
            labs,
            profile.sex,
            imperial,
            to,
            // A course is named on a blood-test chart only where the medicines are on the sheet.
            courses = sources.medications.takeIf { ReportPart.MEDICATIONS in request.parts }.orEmpty(),
        ),
        labContextSection(sources, request, labs, today, nowMinute, zone),
        micronutrientSection(sources, request, today, nowMinute, zone),
        medicationSection(courses, adherence, doseDays, sources.entries, request, periodMinute, zone),
        cycleSection(profile, inPeriod, sources.entries, span, sexLogOffered(profile.birthDate, today)),
        sexualSymptomSection(inPeriod, profile.sex),
        sexualSection(inPeriod, request.sexualDetail),
        morningSection(profile.sex, sources.entries, span, zone),
    )
    // Last, because it reads only what the rest of the sheet prints.
    val changes = changesSection(sources, request, assembled(built, request).printedParts(), zone)
    val sheet = assembled(built + listOfNotNull(changes), request)
    val printedParts = sheet.printedParts()
    val bandedVitals = printedParts.flatMap(ReportPart::vitals)
        .filter { vital -> vitals[vital]?.let { vital.bandFor(profile, it.last()) } != null }
    return DoctorReport(
        request = request,
        sections = sheet,
        attachments = reportAttachments(inPeriod, courses, visits, request.parts, zone),
        notes = reportNotes(
            // The BMI goes with the weight it is read off: see [personSection].
            bmiGraded = bmiVerdict != null && ReportPart.WEIGHT in request.parts,
            bandedVitals = bandedVitals,
            labs = ReportPart.LABS in printedParts,
            adherence = ReportPart.MEDICATIONS in printedParts && printsAdherence(adherence, sources.entries),
            mood = ReportPart.MOOD in printedParts,
            sensitive = ReportPart.SEXUAL_HEALTH in printedParts,
            mornings = ReportPart.MORNING_ERECTIONS in printedParts,
            chosen = (reportPartsFor(profile, today) - request.parts).isNotEmpty(),
        ) + sheet.filter { it.presence == SectionPresence.PRESENT }.flatMap(ReportSection::footnotes),
        // Only the sections the picker can grey out, which is the only thing [filled] is read for.
        // The person's own block and the allergies answer are on every sheet and are not ticks.
        filled = (built + listOfNotNull(changes)).filter { it.part.pickable }.map(ReportSection::part).toSet(),
    )
}

/**
 * Whether any course on the sheet prints a percentage, which is what the adherence note explains: a
 * course whose doses were never marked says so instead of printing one — see [medicationSection].
 */
private fun printsAdherence(adherence: Map<Medication, Adherence?>, journal: List<JournalEntry>): Boolean {
    val markedEver = journal.mapNotNullTo(HashSet()) { medSlotOf(it)?.first }
    return adherence.any { (course, figure) -> figure != null && course.id in markedEver }
}

/** The sections a sheet prints with something in them. */
private fun List<ReportSection>.printedParts(): Set<ReportPart> =
    filter { it.presence == SectionPresence.PRESENT }.map(ReportSection::part).toSet()

/** The BMI verdict for the sheet: from the newest weighing in the window, or the profile's own. */
private fun bmiVerdictOf(weights: List<WeightReading>, profile: UserProfile, today: LocalDate): Int? =
    bmiVerdictRes(
        bmi(weights.lastOrNull()?.kg ?: profile.weightKg, profile.heightCm),
        profile.birthDate,
        today,
    )

/**
 * The sheet in printing order: every section the summary owes an answer for, whether or not the
 * record or the person filled it.
 *
 * A section that was built and ticked is printed. A section that was ticked and holds nothing says
 * so. A section that was not ticked is left off, whatever the record holds — except the problem
 * list, which a patient summary owes an answer for and which says it was withheld.
 *
 * Left off rather than marked "not included": a heading that appeared only over an unticked section
 * that had something in it would tell a doctor that cycle, pregnancy, alcohol or mood records exist
 * on a sheet whose owner kept them off it. Which headings a sheet carries depends on the ticks
 * alone, and one line at its foot says it carries only the
 * sections the person chose — see [reportNotes].
 */

private fun assembled(built: List<ReportSection>, request: ReportRequest): List<ReportSection> {
    val byPart = built.associateBy(ReportSection::part)
    return ReportPart.entries.mapNotNull { part ->
        val section = byPart[part]
        when {
            part == ReportPart.PERSON -> section

            part in request.parts && section != null -> section

            part in request.parts -> ReportSection(part, emptyList(), SectionPresence.NO_DATA)

            // A core section nobody ticked is still printed, and printed as withheld. The heading
            // has to be there — a doctor who cannot find it reads the silence as "none" — but what
            // stands under it is the person's decision, and diagnoses are the one thing on this
            // sheet somebody has the most reason to keep off it.
            part.core && part.pickable ->
                ReportSection(part, emptyList(), SectionPresence.NOT_INCLUDED)

            // The core sections nobody can untick, because there is no tick: the allergies, whose
            // answer is the profile's own and whose absence would read as "none".
            part.core -> section ?: ReportSection(part, emptyList(), SectionPresence.NO_DATA)

            else -> null
        }
    }
}

/** The chart's own sum over its days, or null where the period never asked for a dose. */
fun adherenceOf(days: List<MedDay>): Adherence? {
    val planned = days.sumOf(MedDay::planned)
    if (planned <= 0) return null
    return Adherence(days.sumOf(MedDay::taken), planned)
}

/**
 * The BMI verdict the profile card prints, or null where the card prints no verdict: a number
 * nothing can be made of, a birth date not on file, or a person under [ADULT_BMI_AGE], whose index
 * is a percentile of age and sex this app does not hold. The figure is still printed; the word is
 * not, and the report says nothing where the card would say nothing.
 */
internal fun bmiVerdictRes(value: Double, birthDate: String, today: LocalDate): Int? =
    bmiLabelRes(value, birthDate, today).takeIf { it !in NO_BMI_VERDICT }

/**
 * The verdict in words, or null where there is none to give — the same words and the same silence
 * the vitals card keeps: under a band closed on one side, NORMAL is only "not above" or "not below",
 * because a band with no floor has said nothing about a low number.
 */
internal fun LabStatus?.reportVerdictRes(band: LabBand?): Int? = when (this) {
    LabStatus.FAR_ABOVE -> R.string.vitals_status_far_above

    LabStatus.FAR_BELOW -> R.string.vitals_status_far_below

    LabStatus.ABOVE -> R.string.vitals_status_above

    LabStatus.BELOW -> R.string.vitals_status_below

    LabStatus.NORMAL -> when {
        band == null -> null
        band.twoEdged -> R.string.vitals_status_inside
        band.hasCeiling -> R.string.vitals_status_not_above
        else -> R.string.vitals_status_not_below
    }

    null -> null
}

/** Every weigh-in among the rows, oldest first — the same rows and the same gate the timeline reads. */
internal fun weightReadings(entries: List<JournalEntry>): List<WeightReading> = entries
    .filter { it.kind == KIND_MEASUREMENT }
    .mapNotNull { entry ->
        entry.measurementWeightKg
            ?.takeIf { it in PLAUSIBLE_KG }
            ?.let { WeightReading(it, entry.day, entry.occurredAt, entry.measurementSource) }
    }
    // One weighing that reached the phone twice is one weighing — see [deduplicated].
    .distinctBy { it.occurredAt / 1000L to it.kg }
    .sortedBy(WeightReading::occurredAt)

@Suppress("LongParameterList") // Seven figures on one section, none of which belongs to another.
private fun personSection(
    profile: UserProfile,
    latestWeight: WeightReading?,
    request: ReportRequest,
    today: LocalDate,
    imperial: Boolean,
    bmiVerdict: Int?,
    zone: ZoneId,
): ReportSection {
    val from = request.from
    val to = request.to
    val specialty = request.specialty
    val weightKg = latestWeight?.kg ?: profile.weightKg
    val rows = buildList {
        add(
            ReportRow(
                label = res(R.string.report_period),
                value = res(R.string.report_period_value, ReportText.Day(from), ReportText.Day(to)),
            ),
        )
        // Who it was put together for, where that is a doctor: a sheet that says "cardiologist"
        // explains its own selection to the person reading it, which a bare list of sections cannot.
        // "My own choice" is not an answer to that question and is not printed as one.
        specialty?.takeIf { it != DoctorSpecialty.CUSTOM }
            ?.let { add(ReportRow(res(R.string.report_for_doctor), res(it.titleRes))) }
        // Whose health system the sheet was laid out for: it decides the units and the date format
        // above, and a reader in another country needs to know which they are looking at.
        add(ReportRow(res(R.string.report_for_country), res(request.country.displayNameRes)))
        // The zone every date on this sheet was stamped in. The app stores a day, not an offset,
        // so a row written abroad carries the day the phone thought it was; saying which zone the
        // sheet was made in is the honest half of that, and the notes carry the other half.
        add(ReportRow(res(R.string.report_timezone), plain(zone.id)))
        if (birthDateKnown(profile.birthDate)) {
            add(ReportRow(res(R.string.report_age), plain(ageOn(today.toString(), profile.birthDate).toString())))
        }
        add(ReportRow(res(R.string.sex), res(if (profile.sex == "FEMALE") R.string.sex_female else R.string.sex_male)))
        add(ReportRow(res(R.string.height), heightText(profile.heightCm, imperial)))
        // The weight and the BMI read off it go with the weight tick, whose line in the picker
        // names both. A sheet that said "not included by the patient" under the weight heading
        // while this block printed a dated weigh-in and a verdict on it would be saying two things.
        if (ReportPart.WEIGHT !in request.parts) return@buildList
        add(
            ReportRow(
                label = res(R.string.weight),
                value = weightText(weightKg, imperial),
                // A weight the period measured is dated; the profile's own figure is only a figure.
                note = latestWeight?.let { dayText(it.day) } ?: res(R.string.report_weight_profile),
            ),
        )
        add(
            ReportRow(
                label = res(R.string.profile_bmi),
                // The one decimal on the sheet the app formats itself: pinned to the same scale the
                // weight above it prints on, so a phone set to a comma does not put two separators
                // in one section of a page written to be read by somebody else.
                value = plain("%.1f".format(Locale.US, bmi(weightKg, profile.heightCm))),
                note = bmiVerdict?.let { res(it) },
            ),
        )
    }
    return ReportSection(ReportPart.PERSON, rows)
}

/**
 * One instrument's section: the vitals [part] names, in the order it names them.
 *
 * A pulse and a blood pressure do not share a heading called "Vitals" with the scale's estimate of
 * body water: they are separate questions asked of separate instruments, and — since the person
 * chooses what to hand over — separate answers to give or withhold.
 *
 * [imperial] is the sheet's own scale, [ReportRequest.imperial], and not the profile's: the person
 * block and the weight above print on the country the report was laid out for, and a muscle mass
 * in the profile's kilograms under a body weight in that country's pounds is two units for one
 * scale's readings.
 *
 * Each vital is drawn over [period] above the rows, one chart apiece, since a percentage, a mass and
 * a kilocalorie share no axis: see [vitalCharts]. A section that can hold more than one names each
 * chart after its vital; the others sit under a heading that already does.
 */
private fun vitalSection(
    part: ReportPart,
    vitals: Map<Vital, List<VitalReading>>,
    profile: UserProfile,
    imperial: Boolean,
    days: Int,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    val readings = part.vitals.flatMap { vitals[it].orEmpty() }
    val rows = part.vitals.flatMap { vital ->
        vitals[vital]?.let { vitalRows(vital, it, profile, imperial) }.orEmpty()
    }
    if (rows.isEmpty()) return null
    val charts = part.vitals.flatMap { vital ->
        val title = res(vital.titleRes).takeIf { part.vitals.size > 1 }
        vitals[vital]?.let { vitalCharts(vital, it, profile, imperial, period, title) }.orEmpty()
    }
    // The caveat speaks of the app's bands, and a section whose rows print none — a child's pressure,
    // an armpit temperature — has none for it to speak of. Read at the newest reading, as the rows are.
    val banded = part.vitals.any { vital ->
        vitals[vital]?.let(::vitalStats)?.let { vital.bandFor(profile, it.latest) } != null
    }
    return ReportSection(
        part = part,
        rows = rows,
        completeness = Completeness(readings.map(VitalReading::day).distinct().size, days, readings.size),
        provenance = provenanceOf(readings.map(VitalReading::source)),
        caveatRes = R.string.report_caveat_band.takeIf { banded },
        charts = charts.sideBySide(),
    )
}

/**
 * One vital's readings over [period], every one of them, at the figure its row would print: floored
 * at the band's step and then put on the sheet's scale, the order [readingText] keeps.
 *
 * The shaded band is the one the Reference row prints — read at the newest reading, as that row is,
 * and at its last figures inside — and there is none where the row prints none. Nor is there one
 * unless it is the band of every reading on the chart: the row speaks of the newest reading alone,
 * while the chart puts each dot inside or outside what is shaded. An armpit reading under the core
 * fever band, or a pressure taken at seventeen under the adult one, would sit inside a range the app
 * declines to read it by — see [temperatureBandApplies] and [Vital.adultOnly]. A blood pressure is
 * two charts rather than two lines on one: the systolic band shaded behind a diastolic of 95 would
 * put that reading inside a range it is above, so each half is drawn against its own printed edge.
 *
 * A pulse, a saturation and a temperature are dots: each is a moment, and a line between two
 * mornings would draw readings nobody took. The rest are joined, as a series that moves slowly.
 */
private fun vitalCharts(
    vital: Vital,
    readings: List<VitalReading>,
    profile: UserProfile,
    imperial: Boolean,
    period: ClosedRange<LocalDate>,
    title: ReportText?,
): List<ReportChart> {
    val latest = readings.maxByOrNull(VitalReading::occurredAt) ?: return emptyList()
    val band = vital.bandFor(profile, latest)
        ?.takeIf { printed -> readings.all { vital.bandFor(profile, it) == printed } }
    val unit = res(vital.displayUnitRes(imperial))
    val style = if (vital in DOTTED_VITALS) ChartStyle.DOTS else ChartStyle.LINE_DOTS
    fun series(of: (VitalReading) -> Double?) = ChartSeries(
        readings.mapNotNull { reading ->
            val value = of(reading) ?: return@mapNotNull null
            reading.day.chartDay()?.let { ChartPoint(it, vital.displayValue(vital.atStep(value), imperial)) }
        },
        style,
    )
    if (!vital.paired) {
        val chart = chartOf(period, unit, listOf(series(VitalReading::value)), title, chartBand(vital, band, imperial))
        return listOfNotNull(chart?.copy(yCeiling = PER_CENT.takeIf { vital == Vital.OXYGEN_SATURATION }))
    }
    val systolic = res(R.string.direct_bluetooth_systolic)
    val diastolic = res(R.string.direct_bluetooth_diastolic)
    return listOfNotNull(
        chartOf(period, unit, listOf(series(VitalReading::value)), systolic, chartBand(vital, band, imperial)),
        chartOf(
            period,
            unit,
            listOf(series(VitalReading::secondary)),
            diastolic,
            band?.let { chartBand(vital, vital.secondaryBand, imperial) },
        ),
    ).sideBySide()
}

/** The vitals drawn as dots alone — see [vitalCharts]. */
private val DOTTED_VITALS = setOf(
    Vital.HEART_RATE,
    Vital.RESTING_HEART_RATE,
    Vital.HRV,
    Vital.OXYGEN_SATURATION,
    Vital.BODY_TEMPERATURE,
)

/**
 * A band as the Reference row prints it, as figures: the lower edge, and the last figure inside the
 * upper one, on the sheet's scale — the same edges [vitalRange] turns into text.
 */
private fun chartBand(vital: Vital, band: LabBand?, imperial: Boolean): ChartBand? = band?.let {
    ChartBand(
        low = (it.low ?: it.veryLow)?.let { edge -> vital.displayValue(vital.insideBottom(edge, it), imperial) },
        high = (it.high ?: it.veryHigh)?.let { edge -> vital.displayValue(vital.insideTop(edge), imperial) },
    )
}

/**
 * Who wrote a series, counted rather than guessed: the app's own source names turned into the words
 * a doctor can use, with every distinct one kept.
 *
 * A pressure series that is half cuff and half typed is two provenances and the sheet says both. No
 * package name reaches the page — "a connected application" is as specific as a doctor needs.
 */
internal fun provenanceOf(sources: List<String?>): List<Provenance> =
    sources.map(::provenanceCategory).distinct().sorted().map { Provenance(it) }

/**
 * The words for one stored source.
 *
 * A file import writes the exporting app's own label into the row — "Withings", "Strava" — rather
 * than a code of this app's, so those labels are matched by name, and only a source nothing here
 * recognises is "not recorded".
 */
internal fun provenanceCategory(source: String?): ProvenanceCategory = when {
    source == null || source == "MANUAL" -> ProvenanceCategory.MANUAL
    source.startsWith("BLUETOOTH") -> ProvenanceCategory.DEVICE
    source == "HEALTH_CONNECT" || source.startsWith("HEALTH_CONNECT") -> ProvenanceCategory.HEALTH_CONNECT
    source == "PHONE_SENSOR" -> ProvenanceCategory.PHONE_SENSOR
    source == "PHONE_USAGE_ESTIMATE" -> ProvenanceCategory.ESTIMATE
    source in IMPORTED_SOURCES -> ProvenanceCategory.IMPORTED_APP
    else -> ProvenanceCategory.UNKNOWN
}

/** The labels the two file imports write as a row's source: one per [HealthExportApp] and [WorkoutExportApp]. */
private val IMPORTED_SOURCES: Set<String> =
    (HealthExportApp.entries.map(HealthExportApp::label) + WorkoutExportApp.entries.map(WorkoutExportApp::label))
        .toSet()

/**
 * One vital: the latest reading with its date and verdict, the count with the lowest, mean and
 * highest, and — where a band applies to this person — the band and who published it.
 *
 * A blood pressure stays a pair throughout. The lowest and highest are whole readings, printed with
 * the diastolic measured alongside, and the mean is the two halves averaged over the same readings,
 * which is how [vitalStats] already keeps them.
 */
private fun vitalRows(
    vital: Vital,
    readings: List<VitalReading>,
    profile: UserProfile,
    imperial: Boolean,
): List<ReportRow> {
    val stats = vitalStats(readings) ?: return emptyList()
    val latest = stats.latest
    val band = vital.bandFor(profile, latest)
    // The sheet's scale, applied to the figures and to the caption together. A sheet headed °C in
    // a country that reads °F is one a doctor has to convert in their head. The bands are compared
    // on the stored figures before either is converted, so the verdict does not move with it.
    val unit = res(vital.displayUnitRes(imperial))
    return buildList {
        add(
            ReportRow(
                label = res(vital.titleRes),
                value = withUnit(readingText(vital, latest.value, latest.secondary, imperial), unit),
                // Where it was taken, when, how it was taken, and only then the verdict. A
                // thermometer's site is part of the reading: 37.6 under an arm and 37.6 in an ear
                // are different measurements, and the app already declines to grade some sites.
                note = join(
                    latest.site?.let { res(it.labelRes) },
                    dayText(latest.day),
                    res(provenanceCategory(latest.source).labelRes),
                    latest.status.reportVerdictRes(band)?.let { res(it) },
                ),
            ),
        )
        // A number with no verdict beside it invites the reader to supply one. Where the app
        // withheld the band on purpose, the sheet says which band it withheld and why.
        if (band == null && vital == Vital.BODY_TEMPERATURE && latest.siteName != null) {
            add(ReportRow(res(R.string.report_reference), res(R.string.report_temperature_site_note), detail = true))
        }
        add(
            ReportRow(
                label = res(R.string.report_readings),
                value = ReportText.Quantity(R.plurals.count_readings, stats.count),
                note = res(
                    R.string.report_min_mean_max,
                    readingText(vital, stats.lowest.value, stats.lowest.secondary, imperial),
                    readingText(vital, stats.mean, stats.meanSecondary, imperial),
                    readingText(vital, stats.highest.value, stats.highest.secondary, imperial),
                ),
                detail = true,
            ),
        )
        band?.let {
            add(
                ReportRow(
                    label = res(R.string.report_reference),
                    value = withUnit(vitalRange(vital, it, imperial), unit),
                    note = vital.sourceRes?.let { source -> res(source) },
                    detail = true,
                ),
            )
        }
    }
}

/**
 * The weight trend: its first and latest readings, the lowest and highest between them, the change,
 * and the readings themselves in the appendix.
 *
 * The change carries a sign only where the printed figure is not nought: 80.00 kg and then 80.04 kg
 * is no change on a sheet that prints one decimal, and "+0.0 kg" would be a direction nobody could
 * see in the figures above it.
 *
 * [zone] is the one the times in the appendix are read in: the sheet's own, which its person block
 * prints.
 *
 * Every weigh-in is drawn over [period], joined, on the scale the rows print: a scale stepped on
 * every morning for a year is several hundred dots, and that is the trend a doctor asked for.
 */
private fun weightSection(
    weights: List<WeightReading>,
    imperial: Boolean,
    days: Int,
    zone: ZoneId,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    val first = weights.firstOrNull() ?: return null
    val last = weights.last()
    val lowest = weights.minBy(WeightReading::kg)
    val highest = weights.maxBy(WeightReading::kg)
    val change = last.kg - first.kg
    val shown = if (imperial) imperialWeight(abs(change)) else abs(change)
    val sign = when {
        (shown * 10).roundToInt() == 0 -> ""
        change < 0.0 -> "−"
        else -> "+"
    }
    val rows = listOf(
        ReportRow(res(R.string.report_first), weightText(first.kg, imperial), dayText(first.day)),
        ReportRow(res(R.string.report_latest), weightText(last.kg, imperial), dayText(last.day)),
        ReportRow(res(R.string.vitals_lowest), weightText(lowest.kg, imperial), dayText(lowest.day), detail = true),
        ReportRow(res(R.string.vitals_highest), weightText(highest.kg, imperial), dayText(highest.day), detail = true),
        ReportRow(res(R.string.report_change), weightText(if (sign.isEmpty()) 0.0 else abs(change), imperial, sign)),
    )
    return ReportSection(
        part = ReportPart.WEIGHT,
        rows = rows,
        completeness = Completeness(weights.map(WeightReading::day).distinct().size, days, weights.size),
        provenance = provenanceOf(weights.map(WeightReading::source)),
        appendix = weightAppendix(weights, imperial, zone),
        charts = listOfNotNull(
            chartOf(
                period,
                res(if (imperial) R.string.unit_lb else R.string.unit_kg),
                listOf(
                    ChartSeries(
                        weights.mapNotNull { reading ->
                            reading.day.chartDay()?.let {
                                ChartPoint(it, if (imperial) imperialWeight(reading.kg) else reading.kg)
                            }
                        },
                        ChartStyle.LINE_DOTS,
                    ),
                ),
            ),
        ),
    )
}

/**
 * The weigh-ins behind the section's ends, newest first, for the reader who wants the series.
 *
 * Two readings on one day carry the time each was logged at, so a morning and an evening weigh-in
 * read as two readings rather than as one typed in twice. It is the time of the weighing only where
 * the reading was taken or typed in on its day: one typed in afterwards for an earlier day carries
 * the clock it was typed at (the editor stamps that day at the current time). The series stops after
 * [WEIGHT_APPENDIX_ROWS] and says how many older ones it left out: a scale used every day for two
 * years is over seven hundred rows, and pages of them bury the summary a doctor asked for — whose
 * first reading is printed in the section itself.
 */
private fun weightAppendix(weights: List<WeightReading>, imperial: Boolean, zone: ZoneId): List<ReportRow> {
    val onDay = weights.groupingBy(WeightReading::day).eachCount()
    val listed = weights.asReversed().take(WEIGHT_APPENDIX_ROWS).map { reading ->
        val time = Instant.ofEpochMilli(reading.occurredAt).atZone(zone).toLocalTime()
        val sharedDay = (onDay[reading.day] ?: 0) > 1
        ReportRow(
            label = dayText(reading.day),
            value = weightText(reading.kg, imperial),
            note = plain(clockText(time.hour * MINUTES_IN_HOUR + time.minute)).takeIf { sharedDay },
        )
    }
    val older = weights.size - listed.size
    if (older == 0) return listed
    return listed + ReportRow(res(R.string.report_weigh_ins_earlier), plain(older.toString()))
}

/** Ninety rows: three months of a scale stepped on every morning. */
private const val WEIGHT_APPENDIX_ROWS = 90

private const val MINUTES_IN_HOUR = 60

/**
 * A chart of [series] over [period], or none where it would draw no change over time: fewer than two
 * days among its points. One reading is a figure, and the rows under the chart already print it.
 *
 * Bars stand on nought: a bar's height is its figure, and on an axis fitted to the figures every bar
 * is cut off at the lowest of them, so days a little apart read as days far apart.
 */
internal fun chartOf(
    period: ClosedRange<LocalDate>,
    unit: ReportText,
    series: List<ChartSeries>,
    title: ReportText? = null,
    band: ChartBand? = null,
): ReportChart? = ReportChart(
    series,
    unit,
    period.start,
    period.endInclusive,
    title,
    band,
    yFloor = 0.0.takeIf { series.any { it.style == ChartStyle.BARS } },
).takeIf { chart -> chart.series.flatMap(ChartSeries::points).map(ChartPoint::day).distinct().size >= 2 }

/** Charts of one section share a row two at a time; a chart on its own keeps the width. */
internal fun List<ReportChart>.sideBySide(): List<ReportChart> =
    if (size > 1) map { it.copy(width = ChartWidth.HALF) } else this

/**
 * [perDay] gathered into weeks counted back from [to]: each week is the seven days ending on the day
 * it is drawn at, and the oldest holds fewer where the period does not divide into weeks. A week with
 * no day in [perDay] is not in the result — nothing is known about it, which is not nought.
 *
 * A mean or a share over that short week is still a mean or a share of the days it holds; a sum
 * over it is not a week's — see [wholeWeeks].
 */
internal fun <T> byWeek(perDay: Map<LocalDate, T>, to: LocalDate): Map<LocalDate, List<T>> =
    perDay.entries.groupBy(
        { (day, _) -> to.minusDays(ChronoUnit.DAYS.between(day, to) / CHART_WEEK_DAYS * CHART_WEEK_DAYS) },
        { it.value },
    )

/**
 * The weeks of [byWeek] whose seven days all lie inside [period], for a chart that sums a week. The
 * oldest week a period leaves short — two days of a three-month sheet — summed and drawn "a week"
 * tall would be a light week that was only a short one, and on an alcohol chart that is the direction
 * a reader is falsely reassured in. The rows still count those days; only the bar is left off.
 */
internal fun <T> Map<LocalDate, T>.wholeWeeks(period: ClosedRange<LocalDate>): Map<LocalDate, T> =
    filterKeys { end -> end.minusDays(CHART_WEEK_DAYS - 1) >= period.start }

/** A figure a day, oldest first, as a series draws it. */
internal fun Map<LocalDate, Double>.points(): List<ChartPoint> =
    entries.sortedBy { it.key }.map { (day, value) -> ChartPoint(day, value) }

/** The journal's own day keys as dates; a day the parser will not take is left off the chart. */
private fun Map<String, Double>.byDate(): Map<LocalDate, Double> =
    entries.mapNotNull { (day, value) -> day.chartDay()?.let { it to value } }.toMap()

private fun String.chartDay(): LocalDate? = toLocalDateOrNull()

internal const val CHART_WEEK_DAYS = 7L

/**
 * The laboratory panels on file up to the end of the period, a marker at a time, and apart from them
 * the glucose readings that did not come in on a panel, inside it.
 *
 * The panels are not cut to the period — see [ReportLabs]. A result from before it says so on its
 * row, and a marker's newest result is printed even when it is older than the period: a quarter's
 * sheet that printed "no data" over last spring's panel would be hiding the only blood test there is.
 *
 * A glucose that did not come in on a panel — a meter's fingerstick, a reading out of Health Connect —
 * is not printed as the laboratory's, and printed as the glucose marker's newest it would push the
 * panel's own glucose off the sheet. Those are counted on a row of their own that names who wrote
 * them rather than guessing at what they were: a Health Connect record can come from a hospital's app
 * as well as from a meter. Only inside the period, like every measurement, and rounded to the unit's
 * step, since a mean is nobody's typed figure — see [printedRounded].
 *
 * Each marker the rows print is also drawn, in the rows' order, from the same results — see
 * [labChart] — up to [to], the period's last day. The other glucose readings are not drawn on the
 * laboratory's chart, for the reason they are not printed as its results. [courses] are the ones the
 * sheet may name, which [courseStarts] marks on the charts of the markers they pair with.
 */
private fun labsSection(
    labs: ReportLabs,
    sex: String,
    imperial: Boolean,
    to: LocalDate,
    courses: List<Medication>,
): ReportSection? {
    // The newest result of each marker and the ones behind it, [PRINTED_LAB_RESULTS] in all, newest first.
    val printed = labs.mapValues { (_, readings) ->
        readings.filter(LabReading::fromPanel).asReversed().take(PRINTED_LAB_RESULTS)
    }.filterValues { it.isNotEmpty() }
    val shown = printed.values.flatten()
    val own = labs[LabMarker.GLUCOSE].orEmpty().filter { !it.fromPanel && it.day >= labs.from.toString() }
    val ownRow = own.takeIf { it.isNotEmpty() }?.let { readings ->
        val figures = readings.map(LabReading::value).let { listOf(it.min(), it.average(), it.max()) }
            .map { LabMarker.GLUCOSE.printedRounded(it, imperial) }
        val spread = ReportText.Res(R.string.report_min_mean_max, figures)
        ReportRow(
            label = res(R.string.report_lab_other_glucose),
            value = ReportText.Quantity(R.plurals.count_readings, readings.size),
            note = ReportText.Join(
                listOf(
                    withUnit(spread, res(LabMarker.GLUCOSE.unitLabelRes(imperial))),
                    ReportText.Quantity(R.plurals.count_days, readings.map(LabReading::day).distinct().size),
                ) + provenanceOf(readings.map(LabReading::source)).map { res(it.category.labelRes) },
            ),
        )
    }
    val rows = printed.flatMap { (marker, results) -> labRows(marker, results, sex, imperial, labs.from) }
    val charts = printed.mapNotNull { (marker, results) ->
        labChart(marker, results, sex, imperial, to)?.let {
            it.copy(marks = courseStarts(marker, courses, it.from, to))
        }
    }
    return (rows + listOfNotNull(ownRow)).takeIf { it.isNotEmpty() }?.let {
        ReportSection(
            part = ReportPart.LABS,
            rows = it,
            provenance = provenanceOf((shown + own).map(LabReading::source)),
            // The bands are the app's, printed beside the laboratory's own: the sheet has to say
            // which is which wherever it prints one.
            caveatRes = R.string.report_caveat_band.takeIf {
                shown.any { reading -> reading.marker.bandFor(sex, reading.ageYears, reading.fasting) != null }
            },
            charts = charts,
        )
    }
}

/**
 * One marker's results drawn over time, for the reader who takes a direction off a line faster than
 * off four dated rows: the results [labRows] prints, figure for figure, on an axis that runs from the
 * oldest of them to [to] — so a 2024 panel and a 2026 one stand two years apart rather than side by
 * side, and the months since the newest show as well.
 *
 * Each point sits at the figure its row prints and is labelled with it. [printPlaces] chose those
 * decimals so that no printed figure lands across a printed edge from its verdict, and the band shaded
 * is the newest row's, at the same decimals, one-sided where that band is — so the newest dot cannot
 * sit inside the shading while its row says above. An older result may have been read against another
 * band, and its row prints that band under it. Where food decides the band, a triglyceride after food
 * under a fasting one, the point carries the fasting word its row carries, so the difference is on the
 * chart as well as under it.
 *
 * Null only where no result has a day the axis can place.
 */
private fun labChart(
    marker: LabMarker,
    shown: List<LabReading>,
    sex: String,
    imperial: Boolean,
    to: LocalDate,
): ReportChart? {
    val places = marker.printPlaces(shown, sex, imperial)
    val points = shown.asReversed().mapNotNull { reading ->
        reading.day.toLocalDateOrNull()?.let { day ->
            val figure = marker.printed(reading, sex, imperial, places)
            ChartPoint(day, figure.toDouble(), figure, reading.fastingRes?.let { res(it) })
        }
    }
    // A single result is a dot on an axis of its own month: the row already says everything it can.
    if (points.size < 2) return null
    val from = points.first().day
    val newest = shown.first()
    val band = marker.bandFor(sex, newest.ageYears, newest.fasting)?.let { band ->
        val (low, high) = marker.printedEdges(band, imperial, places)
        ChartBand(low?.toDouble(), high?.toDouble())
    }
    return ReportChart(
        series = listOf(ChartSeries(points, ChartStyle.LINE_DOTS)),
        unit = res(marker.unitLabelRes(imperial)),
        from = from,
        to = to,
        title = res(marker.titleRes),
        band = band,
        width = ChartWidth.HALF,
    )
}

/**
 * The days on [from]..[to] that a course began whose written contents pair with [marker] — the pairs
 * "Around each blood test" reads, [SupplementPairing] — each named as the course is named.
 *
 * A course nobody described by what it holds pairs with nothing, as it does there: "D3" typed as a
 * name is a guess this app does not make. The line marks when a course began and nothing more; what
 * the results did around it is for the reader to see, not for the chart to say.
 */
private fun courseStarts(
    marker: LabMarker,
    courses: List<Medication>,
    from: LocalDate,
    to: LocalDate,
): List<ChartMark> {
    val nutrients = SupplementPairing.entries.filter { it.marker == marker }.map(SupplementPairing::nutrient)
    return courses.filter { course -> contentsOf(course.contents).keys.any { it in nutrients } }
        .mapNotNull { course ->
            course.startDay.toLocalDateOrNull()
                ?.takeIf { it in from..to }
                ?.let { ChartMark(it, plain(course.name)) }
        }
        .sortedBy(ChartMark::day)
}

/**
 * One marker: the newest result with its date, its fasting state where that picks the band, and its
 * verdict; the band it was read against and who published it; and the older results under it,
 * [PRINTED_LAB_RESULTS] in all, newest first — enough to show a direction without reprinting the
 * whole history the blood tests page already keeps. [shown] is that list, newest first.
 *
 * Every figure, band edge and printed range of a marker shares one precision, [printPlaces], so no
 * figure is rounded across the edge printed beside it.
 *
 * An earlier result carries its own state and verdict, because it was read against its own band: a
 * fasting triglyceride from one year is graded against 1.7 while a sample after food the next is
 * graded against 2.0, and "above" printed under "up to 1.9" would read as the sheet contradicting itself.
 * Where that band or the laboratory's printed range differs from the newest one's, the earlier
 * result prints its own under it.
 */
@Suppress("CyclomaticComplexMethod") // A marker's rows on paper: each branch is a row a result may lack.
private fun labRows(
    marker: LabMarker,
    shown: List<LabReading>,
    sex: String,
    imperial: Boolean,
    from: LocalDate,
): List<ReportRow> {
    val latest = shown.firstOrNull() ?: return emptyList()
    val places = marker.printPlaces(shown, sex, imperial)
    val unit = res(marker.unitLabelRes(imperial))
    fun band(reading: LabReading) = marker.bandFor(sex, reading.ageYears, reading.fasting)
    fun value(reading: LabReading) = withUnit(marker.printed(reading, sex, imperial, places), unit)

    // Whether the result is older than the period; whether it was fasting, where that decides the
    // band and a result printed without it cannot be checked; and where it fell.
    fun about(reading: LabReading): List<ReportText> = listOfNotNull(
        R.string.report_lab_before_period.takeIf { reading.day < from.toString() },
        reading.fastingRes,
        reading.status.reportVerdictRes(band(reading)),
    ).map { res(it) }

    // The band and the body that published it, or why there is no band.
    fun bandRow(reading: LabReading): ReportRow = band(reading)?.let { band ->
        val range = withUnit(labRange(marker, band, imperial, places), unit)
        // An adult line read over somebody younger says so beside its source.
        val source = marker.bandSourceRes?.let {
            if (marker.adultBandAt(reading.ageYears)) res(R.string.labs_band_source_youth, res(it)) else res(it)
        }
        ReportRow(res(R.string.report_reference), range, note = source, detail = true)
    } ?: ReportRow(res(R.string.labs_norm_status), res(marker.withoutBandRes), detail = true)
    fun reportedRow(reading: LabReading) = reading.reported?.let { reported ->
        val range = withUnit(reportedRange(marker, reported, imperial, places), unit)
        ReportRow(res(R.string.report_lab_reported_range_label), range, detail = true)
    }

    // What the sample was and what read it, once per marker, because a panel can hold results from
    // two sheets and the specimen belongs to the result rather than to the section. A figure the app
    // worked out was read by nothing, and says so instead.
    val drawn = if (marker.derived) {
        listOf(ReportRow(res(R.string.labs_method), res(R.string.report_lab_calculated), detail = true))
    } else {
        listOfNotNull(
            latest.specimen?.let { ReportRow(res(R.string.labs_specimen), res(it.titleRes), detail = true) },
            latest.method.takeIf(String::isNotBlank)?.let {
                ReportRow(res(R.string.labs_method), plain(it), detail = true)
            },
        )
    }
    val headline = ReportRow(
        label = res(marker.titleRes),
        value = value(latest),
        note = ReportText.Join(listOf(dayText(latest.day)) + about(latest)),
    )
    // An absent filtration rate says nothing about why; under 18 the equation is not written.
    val noRate = ReportRow(res(R.string.labs_marker_egfr), res(R.string.labs_egfr_pediatric), detail = true)
    val earlier = shown.drop(1).flatMap { result ->
        val note = about(result).takeIf { it.isNotEmpty() }?.let { ReportText.Join(it) }
        listOfNotNull(
            ReportRow(dayText(result.day), value(result), note, detail = true),
            reportedRow(result).takeIf { result.reported != latest.reported },
            bandRow(result).takeIf { band(result) != band(latest) },
        )
    }
    // The laboratory's own range before the app's band, where the sheet printed one and somebody
    // typed it in. It outranks the band on the page for the same reason it does in a clinic: it
    // belongs to the analyser that ran this sample, and the band under it does not.
    return listOfNotNull(headline, reportedRow(latest), bandRow(latest)) + drawn +
        listOfNotNull(noRate.takeIf { latest.egfrWithheld }) + earlier
}

/**
 * The range a laboratory printed, on the scale the profile reads in.
 *
 * Stored the way the value beside it is stored — in the unit the band is written in — so an
 * imperial profile prints both through the same conversion and the sheet cannot contradict itself.
 * One edge alone prints as one edge: "< 5.0" is what half of them say. Never coarser than it was
 * typed: a sheet's 0.27 – 4.20 is not 0.3 – 4.2 — see [printed].
 */
private fun reportedRange(marker: LabMarker, reported: ReportedRange, imperial: Boolean, places: Int): ReportText =
    rangeText(
        reported.low?.let { marker.printed(it, imperial, places) },
        reported.high?.let { marker.printed(it, imperial, places) },
    )

/**
 * One course per row — name, dose and schedule, with the food instruction where there is one — and
 * under it the share of the period's doses that were marked, wherever the period asked for any.
 *
 * A course nobody has ever marked a dose of, in the whole of [journal], prints that instead of a
 * percentage. Somebody who writes a supplement down as a course to keep a record of it and never
 * uses the tick has not taken none of it, and "0%" is how a doctor reads a refused treatment. A
 * course that was marked before and not in this period keeps its figure: the tick was in use, so
 * its silence is a zero the record can stand behind.
 *
 * A course whose label was written down also says what one dose holds, and what the doses marked
 * over the period came to a day — see [courseContentsRows]. One whose dose is free text says nothing
 * more: nothing is read out of "1 tablet". Those doses are counted from the period's last day at
 * [nowMinute], where the adherence row's are, so the lines under one course count the same doses.
 *
 * A course that prints a percentage is drawn too, a half-width chart apiece — see [adherenceChart]
 * — from [doseDays], the days that percentage was summed from. One that prints "never marked" is
 * not: its chart would be the flat nought the row exists to keep off the page.
 */
private fun medicationSection(
    courses: List<Medication>,
    adherence: Map<Medication, Adherence?>,
    doseDays: Map<Medication, List<MedDay>>,
    journal: List<JournalEntry>,
    request: ReportRequest,
    nowMinute: Int,
    zone: ZoneId,
): ReportSection? {
    if (courses.isEmpty()) return null
    val markedEver = journal.mapNotNullTo(HashSet()) { medSlotOf(it)?.first }
    val charts = courses.mapNotNull { course ->
        doseDays[course]?.takeIf { adherence[course] != null && course.id in markedEver }
            ?.let { adherenceChart(course, it, request.from..request.to) }
    }
    val rows = courses.flatMap { course ->
        val schedule = when (course.scheduleKind()) {
            MedSchedule.WAKE -> res(R.string.med_schedule_wake)
            MedSchedule.CLOCK -> ReportText.Join(course.doseMinutes().map { plain(clockText(it)) }, LIST_COMMA)
        }
        val food = course.foodRelation().takeIf { it != MedFood.ANY }?.let { res(it.titleRes) }
        buildList {
            val dose = course.dose.takeIf(String::isNotBlank)?.let(::plain)
            add(ReportRow(plain(course.name), join(dose, schedule), food))
            // When it was started and whether it is still being taken — the two things a doctor
            // asks about a drug before anything else, and both are on the course's own row.
            add(
                ReportRow(
                    label = res(R.string.report_course_dates),
                    value = join(
                        dayText(course.startDay),
                        course.endDay?.let(::dayText) ?: res(R.string.report_course_ongoing),
                    ),
                    detail = true,
                ),
            )
            adherence[course]?.let {
                add(
                    if (course.id in markedEver) {
                        ReportRow(
                            label = res(R.string.report_adherence),
                            value = res(R.string.med_chart_percent, it.percent),
                            note = res(R.string.report_doses_marked, it.taken, it.planned),
                            detail = true,
                        )
                    } else {
                        ReportRow(
                            label = res(R.string.report_adherence),
                            value = res(R.string.report_doses_never_marked),
                            detail = true,
                        )
                    },
                )
            }
            addAll(courseContentsRows(course, journal, request.from, request.to, request.to, nowMinute, zone))
        }
    }
    return ReportSection(ReportPart.MEDICATIONS, rows, charts = charts)
}

/**
 * The share of one course's scheduled doses marked, a week at a time, over the weeks that asked for
 * any: the days behind the adherence row, summed a week instead of a period. A week the course asked
 * nothing of — before it started, after it ended — is absent rather than drawn as nought, and the
 * day it started is marked where that falls inside the period.
 */
private fun adherenceChart(course: Medication, days: List<MedDay>, period: ClosedRange<LocalDate>): ReportChart? {
    val weekly = byWeek(days.filter { it.planned > 0 }.associateBy(MedDay::day), period.endInclusive)
        .mapValues { (_, week) -> week.sumOf(MedDay::taken) * PER_CENT / week.sumOf(MedDay::planned) }
    val start = course.startDay.chartDay()?.takeIf { it in period }
    return chartOf(
        period,
        res(R.string.report_chart_share_doses),
        listOf(ChartSeries(weekly.points(), ChartStyle.LINE_DOTS)),
        plain(course.name),
    )?.copy(
        marks = listOfNotNull(start?.let { ChartMark(it, res(R.string.report_chart_course_start)) }),
        yFloor = 0.0,
        yCeiling = PER_CENT,
        width = ChartWidth.HALF,
    )
}

internal const val PER_CENT = 100.0

/**
 * The nights counted, the average one, and how far apart the bedtimes and the waking times were.
 *
 * The spreads are the whole of what this app can say about quality: it records intervals, not stages,
 * and a sheet that offered a doctor "deep sleep" off a phone in a pocket would be inventing it.
 *
 * Who wrote the nights is said the way every measured section says it, because here it changes
 * what a night is. A night the app estimated is the longest overnight stretch the phone went
 * untouched — [estimateNightSleep] says itself that it cannot tell sleep from reading or a phone
 * left in another room — and printed without its source it reads as sleep a device measured. Where
 * any night is one of those, the caveat under the section says what such a night is.
 *
 * [nights] are the same nights [sleep] counted, by day, and are drawn over [period] — see
 * [sleepChart].
 */
private fun sleepSection(
    sleep: SleepSummary,
    nights: Map<LocalDate, Int>,
    days: Int,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    if (sleep.nights == 0) return null
    val provenance = provenanceOf(sleep.sources)
    return ReportSection(
        part = ReportPart.SLEEP,
        completeness = Completeness(sleep.nights, days, sleep.nights),
        provenance = provenance,
        charts = listOfNotNull(sleepChart(nights, period)),
        caveatRes = if (provenance.any { it.category == ProvenanceCategory.ESTIMATE }) {
            R.string.report_caveat_sleep_estimated
        } else {
            R.string.report_caveat_sleep
        },
        rows = listOf(
            ReportRow(res(R.string.report_nights), ReportText.Quantity(R.plurals.count_nights, sleep.nights)),
            ReportRow(res(R.string.report_sleep_mean), plain(formatSleepClock(sleep.averageMinutes))),
            ReportRow(
                label = res(R.string.report_sleep_spread),
                value = ReportText.Quantity(R.plurals.count_minutes, sleep.bedtimeSpreadMinutes),
                detail = true,
            ),
            // A bedtime held to the minute says nothing about the morning: in bed at 23:00 and up at
            // 05:30 and 09:30 on alternate days would print "Bedtimes apart by 0 minutes" and no more.
            // Night-to-night shifts in waking time went with the next day's mood among 2,115 interns
            // where shifts in bedtime did not (Fang et al., npj Digital Medicine 2021). The row prints
            // the spread and not that finding, as the bedtime row does.
            ReportRow(
                label = res(R.string.report_sleep_wake_spread),
                value = ReportText.Quantity(R.plurals.count_minutes, sleep.wakeSpreadMinutes),
                detail = true,
            ),
            // Per night, not piled up over the window. The sum of the shortfalls says more about
            // how long the report is than about how the person sleeps: the same ten minutes short
            // a night adds up to 15 hours over 90 days and to more than 60 over a year, and neither
            // number means anything on its own. A nightly average reads the same at any length.
            //
            // Against [SLEEP_TARGET_MINUTES], which is the app's own target and not a published
            // one, and the note says so: a doctor who reads "short" against a guideline would read
            // 7:20 as too little, and the adult guidelines put that inside the range — see there.
            ReportRow(
                label = res(R.string.report_sleep_debt),
                value = plain(formatSleepClock(sleep.debtMinutes / sleep.nights.coerceAtLeast(1))),
                note = res(R.string.report_sleep_target),
                detail = true,
            ),
        ),
    )
}

/**
 * Hours a night, each night a dot, and through them the mean of the nights logged in the seven days
 * up to each one.
 *
 * Over the nights logged, never the calendar: a week with three nights on record is the mean of
 * three, and a night nobody logged pulls nothing towards nought. No target is shaded, because the
 * section prints none — the eight hours it measures shortfall against are the app's own.
 */
private fun sleepChart(nights: Map<LocalDate, Int>, period: ClosedRange<LocalDate>): ReportChart? {
    val hours = nights.mapValues { (_, minutes) -> minutes / MINUTES_IN_HOUR.toDouble() }
    val mean = hours.mapValues { (night, _) ->
        (0L until CHART_WEEK_DAYS).mapNotNull { back -> hours[night.minusDays(back)] }.average()
    }
    return chartOf(
        period,
        res(R.string.report_chart_hours_night),
        listOf(
            ChartSeries(hours.points(), ChartStyle.DOTS, res(R.string.report_nights)),
            ChartSeries(
                mean.points(),
                ChartStyle.LINE,
                ReportText.Quantity(R.plurals.report_chart_sleep_mean, CHART_WEEK_DAYS.toInt()),
            ),
        ),
    )
}

/**
 * What was eaten and drunk: an average day, the nutrients behind it, the stretch of the period the
 * meals come from, and the water and caffeine logged beside them.
 *
 * Averaged over days rather than over the period, because a fortnight of holidays nobody logged
 * would otherwise be printed as a fortnight of fasting — and over the days holding at least
 * [LAB_DIET_MIN_MEALS_PER_DAY] meals rather than every day holding one, for the same reason one
 * step further in: a day with only a breakfast on it is somebody who stopped logging after
 * breakfast. A cappuccino from the caffeine dialog is summed into a day's energy but is not one of
 * its meals ([countsAsMeal]), so a breakfast and a coffee are still a day left half-logged. That
 * is the rule the window before a blood draw applies to the same diary, so the sheet and the
 * blood-test screen describe the same days. Under [REPORT_MIN_FOOD_DAYS] such days the
 * counts are printed and the average is not, because a figure printed as "Average day" is read as
 * the person's usual, and three days are not that.
 *
 * Water and caffeine are rows of their own and do not wait for a meal. A person who logs only water
 * is sending the one figure a doctor reads beside a creatinine, and a section that needed food first
 * would print it as "No data recorded". The completeness under the section counts the days any of the
 * three was logged, and every row carries its own count of days.
 *
 * The same days are drawn over [period], one bar a day and only on the days the row counts: food
 * energy on the full days, water and caffeine on the days any was logged.
 */
private fun nutritionSection(
    entries: List<JournalEntry>,
    periodDays: Int,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    val meals = entries.filter { it.kind == KIND_EAT }
    val water = dailyTotals(entries.filter { it.kind == KIND_WATER }) { it.servingG }
    // A cup keeps its milligrams as its serving and a meal beside its portion — see [caffeineRows].
    val caffeine = dailyTotals(entries, ::caffeineMgOf)
    val rows = foodRows(meals) + hydrationRows(water) + caffeineRows(caffeine)
    if (rows.isEmpty()) return null
    val logged = entries.filter { it.kind == KIND_EAT || it.kind == KIND_WATER || it.kind == KIND_CAFFEINE }
    val fullDays = meals.groupBy(JournalEntry::day)
        .filterValues { day -> day.count(JournalEntry::countsAsMeal) >= LAB_DIET_MIN_MEALS_PER_DAY }
        .mapValues { (_, day) -> day.sumOf(JournalEntry::calories) }
    val energy = ReportText.Quantity(R.plurals.report_changes_food, LAB_DIET_MIN_MEALS_PER_DAY)
    return ReportSection(
        part = ReportPart.NUTRITION,
        completeness = Completeness(logged.map(JournalEntry::day).distinct().size, periodDays, logged.size),
        caveatRes = R.string.report_caveat_nutrition,
        // A diary that came through Health Connect or out of another app's file was not typed here.
        provenance = provenanceOf(logged.map(JournalEntry::measurementSource)),
        rows = rows,
        charts = listOfNotNull(
            dailyBars(fullDays, energy, R.string.unit_kcal, period),
        ) + listOfNotNull(
            dailyBars(water, res(R.string.water_title), R.string.unit_ml, period),
            dailyBars(caffeine, res(R.string.caffeine_title), R.string.unit_milligrams, period),
        ).sideBySide(),
    )
}

/** What [of] says on each day, summed over the rows that say anything; a day no row speaks for is absent. */
private fun dailyTotals(entries: List<JournalEntry>, of: (JournalEntry) -> Double?): Map<String, Double> = entries
    .mapNotNull { entry -> of(entry)?.let { entry.day to it } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, figures) -> figures.sum() }

/** A bar on each day of [perDay], in [unitRes] a day. */
private fun dailyBars(
    perDay: Map<String, Double>,
    title: ReportText,
    unitRes: Int,
    period: ClosedRange<LocalDate>,
): ReportChart? = chartOf(
    period,
    res(R.string.report_per_day_rate, res(unitRes)),
    listOf(ChartSeries(perDay.byDate().points(), ChartStyle.BARS)),
    title,
)

/**
 * The meals: the average day where enough full days hold one, and always the counts that say how
 * much of the diary it stands on — full days out of the days with any food, the days left out, and
 * the first and last of them, so an average of an old fortnight is not read as this month's.
 */
private fun foodRows(meals: List<JournalEntry>): List<ReportRow> {
    if (meals.isEmpty()) return emptyList()
    val byDay = meals.groupBy(JournalEntry::day)
    val full = byDay.values.filter { day -> day.count(JournalEntry::countsAsMeal) >= LAB_DIET_MIN_MEALS_PER_DAY }
    val averaged = full.size >= REPORT_MIN_FOOD_DAYS
    val partial = byDay.size - full.size
    val kcal = if (averaged) full.sumOf { day -> day.sumOf(JournalEntry::calories) } / full.size else null
    return buildList {
        add(
            ReportRow(
                label = res(R.string.report_food_mean),
                value = kcal?.let { withUnit(it.roundToInt(), res(R.string.unit_kcal)) }
                    ?: ReportText.Quantity(R.plurals.report_not_averaged, REPORT_MIN_FOOD_DAYS),
                note = res(R.string.report_count_of, full.size, ReportText.Quantity(R.plurals.count_days, byDay.size)),
            ),
        )
        if (averaged) {
            add(macroRow(full))
            addAll(FOOD_NUTRIENTS.mapNotNull { nutrientRow(it, full) })
        }
        // Straight under the averages it was left out of, and above the count of entries, which it
        // is not left out of.
        if (partial > 0) {
            add(
                ReportRow(
                    label = ReportText.Quantity(R.plurals.report_food_short_days, LAB_DIET_MIN_MEALS_PER_DAY),
                    value = plain(partial.toString()),
                    note = res(R.string.report_left_out).takeIf { averaged },
                    detail = true,
                ),
            )
        }
        add(ReportRow(res(R.string.report_meals), plain(meals.size.toString()), detail = true))
        add(ReportRow(res(R.string.report_first_last_day), daySpan(byDay.keys), detail = true))
    }
}

/** Protein, fat and carbohydrate a day, over the same full days as the average above them. */
private fun macroRow(full: List<List<JournalEntry>>): ReportRow {
    val eaten = full.flatten()
    return ReportRow(
        label = res(R.string.editor_macros),
        value = res(
            R.string.entry_food_details,
            (eaten.sumOf(JournalEntry::proteinG) / full.size).pretty(),
            (eaten.sumOf(JournalEntry::fatG) / full.size).pretty(),
            (eaten.sumOf(JournalEntry::carbsG) / full.size).pretty(),
        ),
        note = res(R.string.report_food_per_day),
        detail = true,
    )
}

/**
 * An optional nutrient of the food form, with the label and the unit the sheet prints it under;
 * [whole] prints it without a decimal, which is how milligrams are read.
 */
private class FoodNutrient(
    val labelRes: Int,
    val unitRes: Int,
    val whole: Boolean,
    val of: (JournalEntry) -> Double?,
)

private val FOOD_NUTRIENTS = listOf(
    FoodNutrient(R.string.editor_fiber, R.string.unit_grams, whole = false) { it.fiberG },
    FoodNutrient(R.string.editor_sugar, R.string.unit_grams, whole = false) { it.sugarG },
    FoodNutrient(R.string.editor_saturated_fat, R.string.unit_grams, whole = false) { it.saturatedFatG },
    FoodNutrient(R.string.editor_sodium, R.string.unit_milligrams, whole = true) { it.sodiumMg },
)

/**
 * One optional nutrient a day, over the full days on which every entry carries it, or no row where
 * no such day exists.
 *
 * A blank is "never entered", not nought — the column says so — and one blank entry turns a day's
 * sum into part of a day, which would print half the entries carrying sodium as half the
 * sodium. So each nutrient has its own days and its own count, because the four are filled in on
 * different entries, and each is held to the same floor as the average day above it before it is
 * printed as a daily figure.
 */
private fun nutrientRow(nutrient: FoodNutrient, full: List<List<JournalEntry>>): ReportRow? {
    val perDay = full.mapNotNull { day -> day.mapNotNull(nutrient.of).takeIf { it.size == day.size }?.sum() }
    if (perDay.isEmpty()) return null
    val value = if (perDay.size >= REPORT_MIN_FOOD_DAYS) {
        val mean = perDay.average()
        res(
            R.string.report_per_day_rate,
            withUnit(if (nutrient.whole) mean.roundToInt() else mean.pretty(), res(nutrient.unitRes)),
        )
    } else {
        ReportText.Quantity(R.plurals.report_not_averaged, REPORT_MIN_FOOD_DAYS)
    }
    return ReportRow(
        res(nutrient.labelRes),
        value,
        ReportText.Quantity(R.plurals.count_days, perDay.size),
        detail = true,
    )
}

/** The first and the last of [days], or the one day where they are the same. */
private fun daySpan(days: Collection<String>): ReportText {
    val first = days.min()
    val last = days.max()
    return if (first == last) dayText(first) else res(R.string.report_period_value, dayText(first), dayText(last))
}

/**
 * A week of full days before the diary is averaged at all. Not a figure anybody published: it is the
 * shortest run that reads as more than a few days, and the counts are printed either way.
 */
private const val REPORT_MIN_FOOD_DAYS = 7

/**
 * What was drunk, where anything was: millilitres a day over the days that hold a glass, and how
 * many days those were, so ten days of glasses are not read as half a year's habit.
 *
 * A row rather than a section, because hydration is read beside food and beside a creatinine rather
 * than on its own — but not a detail under the meals, because it stands whether or not a meal was
 * logged, and a detail row belongs to the row above it.
 */
private fun hydrationRows(perDay: Map<String, Double>): List<ReportRow> {
    val wateredDays = perDay.size
    if (wateredDays == 0) return emptyList()
    val mean = (perDay.values.sum() / wateredDays).roundToInt()
    return listOf(
        ReportRow(
            label = res(R.string.water_title),
            value = res(R.string.report_per_day_rate, withUnit(mean, res(R.string.unit_ml))),
            note = ReportText.Quantity(R.plurals.count_days, wateredDays),
        ),
    )
}

/**
 * Caffeine, milligrams a day over the days any was logged, and how many days those were.
 *
 * A cup keeps its milligrams as its serving and a meal beside its portion, the two the drinks widget
 * already adds up. A meal that states no caffeine is not a meal with none — [JournalEntry.caffeineMg]
 * says so — so it neither adds to a day nor makes one. Nothing is printed beside it: the limit the
 * app holds is the adult one, and the sheet counts what was drunk rather than grading it.
 */
private fun caffeineRows(perDay: Map<String, Double>): List<ReportRow> {
    if (perDay.isEmpty()) return emptyList()
    val mean = (perDay.values.sum() / perDay.size).roundToInt()
    return listOf(
        ReportRow(
            label = res(R.string.caffeine_title),
            value = res(R.string.report_per_day_rate, withUnit(mean, res(R.string.unit_milligrams))),
            note = ReportText.Quantity(R.plurals.count_days, perDay.size),
        ),
    )
}

/**
 * Mood as the app measures it: how many times it was answered, the mean of the daily means, and the
 * word the wellbeing figure falls under — and, on a row of its own, how often the second dial came
 * back wound up or restless.
 *
 * Nothing is printed until [moodSummary] names a state, which it refuses to do under three answers
 * over two days: a sheet that named a mood from one tap would be handing a doctor one afternoon.
 * The paragraph at the foot says what the scale is, because a percentage that borrows WHO-5's
 * cut-offs will otherwise be read as a WHO-5 score.
 *
 * The average and the count go to the doctor from there; the word beside the average waits for
 * [MIN_MOOD_WORD_DAYS] answered days. Two days are enough for the card, which looks at one week, and
 * on a sheet that runs for months they would print "Low" over three taps out of half a year.
 *
 * The word beside the average is the wellbeing band alone, never [MoodState.TENSE]. Tension is the
 * other dial, answered on other days, and the card's rule — two answered days of a week — would let
 * one word stand for a whole report on two days out of half a year, masking the band of a mean it is
 * printed beside. So tension has its own row, with the days behind it, and only from
 * [MIN_TENSION_DAYS] answered days: "Mostly tense" is printed there when the share reaches the
 * card's own [TENSE_SHARE], and the figure goes to the doctor either way.
 *
 * Drawn over [period]: each answered day's own mean on the scale's five points — the days the
 * average above is the mean of — and, where the row is printed, the second dial a week at a time.
 */
private fun moodSection(
    mood: MoodSummary,
    entries: List<JournalEntry>,
    days: Int,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    if (mood.state == MoodState.NO_DATA) return null
    val answeredDays = entries.filter { (it.moodScore ?: 0) in 1..5 }.map(JournalEntry::day).distinct().size
    val daily = chartOf(
        period,
        res(R.string.report_chart_mood_unit),
        listOf(ChartSeries(moodSeries(entries).byDay.points(), ChartStyle.LINE_DOTS)),
        res(R.string.report_mood_average),
    )?.copy(yFloor = Mood.BAD.score.toDouble(), yCeiling = Mood.FUN.score.toDouble())
    return ReportSection(
        part = ReportPart.MOOD,
        completeness = Completeness(answeredDays, days, mood.entries),
        caveatRes = R.string.report_caveat_mood,
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        charts = listOfNotNull(daily, if (mood.tenseDays >= MIN_TENSION_DAYS) woundUpChart(entries, period) else null),
        rows = buildList {
            add(
                ReportRow(
                    label = res(R.string.report_mood_average),
                    value = res(R.string.mood_week_value, mood.average.pretty(), mood.wellbeingPercent.roundToInt()),
                    note = res(wellbeingBand(mood.wellbeingPercent).titleRes())
                        .takeIf { answeredDays >= MIN_MOOD_WORD_DAYS },
                ),
            )
            add(
                ReportRow(
                    label = res(R.string.report_readings),
                    value = ReportText.Quantity(R.plurals.mood_entries, mood.entries),
                    detail = true,
                ),
            )
            if (mood.tenseDays >= MIN_TENSION_DAYS) {
                add(
                    ReportRow(
                        label = res(R.string.report_mood_wound_up),
                        value = res(
                            R.string.report_count_of,
                            woundUpDays(entries),
                            ReportText.Quantity(R.plurals.report_days_answered, mood.tenseDays),
                        ),
                        note = res(MoodState.TENSE.titleRes()).takeIf { mood.tenseShare >= TENSE_SHARE },
                    ),
                )
            }
        },
    )
}

/**
 * Of the days the second dial was answered on in each week, the share with an answer at the wound-up
 * end — the day rule [woundUpDays] counts the row by, a week at a time. A week nobody answered it is
 * absent, not calm.
 */
private fun woundUpChart(entries: List<JournalEntry>, period: ClosedRange<LocalDate>): ReportChart? {
    val answered = entries.filter { (it.energyScore ?: 0) in 1..Energy.SETTLED.score }
        .groupBy(JournalEntry::day)
        .mapNotNull { (day, rows) ->
            day.chartDay()?.let { it to rows.any { row -> (row.energyScore ?: 0) <= Energy.RESTLESS.score } }
        }
        .toMap()
    val weekly = byWeek(answered, period.endInclusive)
        .mapValues { (_, week) -> week.count { it } * PER_CENT / week.size }
    return chartOf(
        period,
        res(R.string.report_chart_share_answered),
        listOf(ChartSeries(weekly.points(), ChartStyle.BARS)),
        res(R.string.report_mood_wound_up),
    )?.copy(yFloor = 0.0, yCeiling = PER_CENT)
}

/**
 * A week of answers on the second dial before the sheet prints how many were wound up.
 *
 * The app's own floor, not a published one: the card's two days were written for a card that looks
 * at one week, and a report runs for months. Below it the row is left out rather than printed as a
 * share of a handful of days, and the note at the foot of the sheet names the figure.
 */
private const val MIN_TENSION_DAYS = 7

/**
 * The answered days before the sheet puts a word — "Below average", "Low" — beside the
 * average mood: the same week, and the app's own floor for the same reason. Under it the average is
 * printed bare, with the days it covers in the line below the section.
 */
private const val MIN_MOOD_WORD_DAYS = MIN_TENSION_DAYS

/**
 * Steps a day over the days a counter was carried, and the workouts as sessions with their minutes.
 *
 * Averaged over the days that hold a count rather than over the period, so a week the phone stayed
 * at home is not printed as a week in bed. A day handed in twice is one day at the larger of its
 * counts, the rule [effectiveStepTotal] settles a day by on the phone rather than a sum. A day held
 * at 0 is left out of the average and counted under it: the app reads a zero as a counter nobody
 * carried rather than as a day without a step, and the doctor is told how many there were.
 *
 * A workout is a session, not a row — [workoutSessions] says how the rows of one are told apart —
 * and its minutes are the lengths that were recorded. A session with none is counted as such rather
 * than added in as nought. The completeness under the section counts a day with either a count or
 * a session, and the counts and the sessions as its measurements.
 *
 * Drawn over [period] from the same days: see [stepChart] and [workoutChart].
 */
private fun activitySection(
    stepDays: List<DaySteps>,
    entries: List<JournalEntry>,
    days: Int,
    period: ClosedRange<LocalDate>,
): ReportSection? {
    val counts = stepDays.groupBy(DaySteps::day).mapValues { (_, rows) -> rows.maxOf(DaySteps::steps) }
    val carried = counts.filterValues { it > 0L }
    val sessions = workoutSessions(entries.filter { it.kind == KIND_ACTIVITY })
    if (carried.isEmpty() && sessions.isEmpty()) return null
    val dataDays = carried.keys + sessions.map { it.first().day }
    return ReportSection(
        part = ReportPart.ACTIVITY,
        rows = stepRows(carried, zeroDays = counts.size - carried.size) + workoutRows(sessions),
        completeness = Completeness(dataDays.size, days, carried.size + sessions.size),
        caveatRes = R.string.report_caveat_activity,
        charts = listOfNotNull(stepChart(carried, days, period), workoutChart(sessions, period)),
    )
}

/**
 * Steps a day over the days the mean is taken over: a bar a day up to [DAILY_STEP_BARS_DAYS] days, a
 * line through each week's mean past it. A day held at 0 and a day with no count are both absent —
 * the rows leave them out of the mean and count them apart — so neither is drawn as a day in bed.
 */
private fun stepChart(carried: Map<String, Long>, days: Int, period: ClosedRange<LocalDate>): ReportChart? {
    val perDay = carried.mapValues { (_, steps) -> steps.toDouble() }.byDate()
    val series = if (days <= DAILY_STEP_BARS_DAYS) {
        ChartSeries(perDay.points(), ChartStyle.BARS)
    } else {
        val weekly = byWeek(perDay, period.endInclusive).mapValues { (_, week) -> week.average() }
        ChartSeries(weekly.points(), ChartStyle.LINE, res(R.string.report_chart_week_mean))
    }
    return chartOf(period, res(R.string.report_per_day_rate, res(R.string.steps)), listOf(series), res(R.string.steps))
}

/**
 * The app's own choice, not a published figure: about four months of a bar a day, and past that a
 * line through the weekly means, which is the trend a longer period is sent for.
 */
private const val DAILY_STEP_BARS_DAYS = 120

/**
 * The minutes of the timed sessions, summed a week — the lengths the workout row adds up. A session
 * with no recorded length adds nothing and makes no week, as it adds nothing to the row. Only the
 * weeks the period holds whole are drawn: see [wholeWeeks].
 */
private fun workoutChart(sessions: List<List<JournalEntry>>, period: ClosedRange<LocalDate>): ReportChart? {
    val perDay = sessions.mapNotNull { session ->
        val day = session.first().day.chartDay()
        val minutes = session.timedMinutes()
        if (day == null || minutes.isEmpty()) null else day to minutes.sum()
    }.groupBy({ it.first }, { it.second }).mapValues { (_, minutes) -> minutes.sum() }
    val weekly = byWeek(perDay, period.endInclusive).wholeWeeks(period).mapValues { (_, week) -> week.sum() }
    return chartOf(
        period,
        res(R.string.report_changes_per_week, res(R.string.unit_minutes)),
        listOf(ChartSeries(weekly.points(), ChartStyle.BARS)),
        res(R.string.home_workouts),
    )
}

/** The lengths recorded on a session's rows; nought and blank are both "not recorded". */
private fun List<JournalEntry>.timedMinutes(): List<Double> = mapNotNull { row ->
    row.durationMinutes?.takeIf { it > 0.0 }
}

/**
 * The mean over the days with a count, and the days held at 0 that were left out of it.
 *
 * The zero days are counted even where no day holds a count to average — beside a few workouts, say
 * — and then stand as a row of their own, since there is no mean for them to be a detail under or to
 * have been left out of.
 */
private fun stepRows(carried: Map<String, Long>, zeroDays: Int): List<ReportRow> = buildList {
    if (carried.isNotEmpty()) {
        add(
            ReportRow(
                label = res(R.string.steps),
                value = res(R.string.report_steps_per_day, (carried.values.sum() / carried.size).sheetCount()),
                note = ReportText.Quantity(R.plurals.count_days, carried.size),
            ),
        )
    }
    if (zeroDays > 0) {
        add(
            ReportRow(
                res(R.string.report_steps_zero_days),
                plain(zeroDays.toString()),
                res(R.string.report_left_out).takeIf { carried.isNotEmpty() },
                detail = carried.isNotEmpty(),
            ),
        )
    }
}

/** How many sessions, the minutes the timed ones add up to, and how many carried no length at all. */
private fun workoutRows(sessions: List<List<JournalEntry>>): List<ReportRow> {
    if (sessions.isEmpty()) return emptyList()
    val lengths = sessions.map { it.timedMinutes() }
    val untimed = lengths.count(List<Double>::isEmpty)
    val minutes = lengths.sumOf(List<Double>::sum).roundToInt()
    return buildList {
        add(
            ReportRow(
                label = res(R.string.home_workouts),
                value = plain(sessions.size.toString()),
                note = ReportText.Quantity(R.plurals.count_minutes, minutes).takeIf { untimed < sessions.size },
            ),
        )
        if (untimed > 0) {
            add(ReportRow(res(R.string.report_workouts_untimed), plain(untimed.toString()), detail = true))
        }
    }
}

/**
 * The workout rows gathered into the sessions they were logged as.
 *
 * This journal keeps a session as one row per exercise, and each writer spaces the rows its own way.
 * A routine stamps each exercise where the next one began, the last at the finish ([sessionRows]),
 * and older routine rows may stand a second apart; a file import stamps every
 * exercise with the session's own start ([importedJournalEntries]); and a superset saved from the
 * editor is two rows, the second stamped at the finish and the first placed the length of the
 * second before it. By the stamps alone the second follows the first by as long as it lasted, but
 * it runs back, by [sessionInterval], to the very moment the first is stamped at. So a row belongs
 * to the session before it on the same day when it is stamped, or starts, within [SESSION_STEP_MS]
 * of that session's last stamp: far wider than any writer's step, and far narrower than the gap
 * between two workouts somebody actually did. Two exercises typed in by hand within the same minute,
 * or one typed in to start the minute the one before it was stamped, are the cases it joins that
 * might have been two.
 */
private fun workoutSessions(workouts: List<JournalEntry>): List<List<JournalEntry>> {
    val sessions = mutableListOf<MutableList<JournalEntry>>()
    workouts.sortedBy(JournalEntry::occurredAt).forEach { row ->
        val previous = sessions.lastOrNull()?.last()
        if (previous != null && previous.day == row.day && row.follows(previous)) {
            sessions.last() += row
        } else {
            sessions += mutableListOf(row)
        }
    }
    return sessions
}

/**
 * Whether this row was written as the next part of [previous]'s session: stamped within a step of
 * it, or starting within a step of it. The start is the one [sessionInterval] publishes, and a row
 * with no length has only its stamp.
 */
private fun JournalEntry.follows(previous: JournalEntry): Boolean {
    val start = sessionInterval(Instant.MAX)?.first?.toEpochMilli() ?: occurredAt
    return occurredAt - previous.occurredAt <= SESSION_STEP_MS || abs(start - previous.occurredAt) <= SESSION_STEP_MS
}

private const val SESSION_STEP_MS = 60_000L

/**
 * The reproductive section: the cycle as it was recorded, and the observations recorded inside it.
 *
 * Only on a profile the cycle is offered to, and only where something was written down. What it
 * prints is what a gynaecologist asks out loud — when the last period started, how long the cycles
 * run and how much they vary, how long the bleeding lasts, and what was observed along the way —
 * and nothing it prints is a conclusion. This sheet does not say that ovulation happened, that a
 * cycle is anovulatory, or that anything is a diagnosis: it says a test read positive on a day,
 * because that is the only thing the app was told.
 *
 * Spotting keeps its own line under the bleeding. Bleeding between periods is a question a
 * clinician asks separately, and a total alone would answer a different one.
 *
 * Everything here is read from [entries], the period's rows, except the date of the last period,
 * which is read from [journal] up to [to]: it is the last value known rather than a figure about
 * the window, and a period that last started before the window opened is exactly the one a doctor
 * needs to see rather than a heading saying nothing was recorded. It is marked as falling before
 * the period, and nothing is said about the gap — the record cannot tell a period that did not
 * come from one that was not logged.
 *
 * The acts the same log keeps are not this section's, and nothing of them is counted here, the
 * days in the completeness line included: that section is sent only when it is asked for, and a
 * day count that grew with it would be a tally of it printed under another heading.
 *
 * The one chart is the cycles the rows count, each at the day it began — see [cycleChart]. Nothing
 * of the acts is drawn, for the reason nothing of them is counted.
 *
 * [acts] is the age gate's answer ([sexLogOffered]). Where it is false the answers about desire are
 * neither printed nor counted ([behindSexGate]): the cycle page does not ask the question under the
 * gate, this section is not one of the sensitive ones the picker withholds there, and an answer a
 * restored backup brought would have left the phone on a sheet under "Desire".
 */
private fun cycleSection(
    profile: UserProfile,
    entries: List<JournalEntry>,
    journal: List<JournalEntry>,
    period: ClosedRange<LocalDate>,
    acts: Boolean,
): ReportSection? {
    if (profile.sex != "FEMALE") return null
    val to = period.endInclusive
    val summary = cycleSummary(entries, to)
    val lastStart = lastCycleStart(journal, to)
    // The rows this section prints from, which are the rows its completeness line counts.
    val recorded = entries.shownTo(acts).filter { it.isCycleRecord() }
    val observed = recorded.filter { it.cycleLog() != null }
    if (summary.recordedCycles == 0 && observed.isEmpty() && lastStart == null) return null
    val rows = buildList {
        lastStart?.let {
            // The one figure on this section a clinician writes down first, under its own name.
            val note = res(R.string.report_cycle_lmp_before).takeIf { summary.lastStart == null }
            add(ReportRow(res(R.string.report_cycle_lmp), ReportText.Day(it), note))
        }
        addAll(cycleLengthRows(summary, entries))
        summary.periodLengthDays?.let {
            add(
                ReportRow(
                    label = res(R.string.report_period_length),
                    value = ReportText.Quantity(R.plurals.count_days, it),
                    note = res(R.string.report_median),
                ),
            )
        }
        // Counted observations, in the screen's own vocabulary and in its own order. A rung nobody
        // ticked is not printed as a zero: the app cannot tell "no spotting" from "not looked at".
        // Counted in days and said to be days an entry was made on: a bare "Bleeding 26" is read
        // as twenty-six days of bleeding, or as one episode that long, and it is neither.
        CycleSection.entries.forEach { section ->
            val counted = section.logs.filter { log -> observed.any { it.cycleLog() == log } }
            if (counted.isEmpty()) return@forEach
            add(ReportRow(res(section.titleRes), loggedOn(observed.filter { it.cycleLog() in section.logs })))
            counted.forEach { log ->
                add(ReportRow(res(log.titleRes), loggedOn(observed.filter { it.cycleLog() == log }), detail = true))
            }
        }
    }
    return ReportSection(
        part = ReportPart.CYCLE,
        rows = rows,
        completeness = Completeness(
            daysWithData = recorded.map(JournalEntry::day).distinct().size,
            expectedDays = null,
            measurements = recorded.size,
        ),
        // A start imported from Health Connect or a Flo file is not one typed by hand.
        provenance = provenanceOf(recorded.map(JournalEntry::measurementSource)),
        caveatRes = R.string.report_caveat_cycle,
        charts = listOfNotNull(cycleChart(entries, period)),
    )
}

/**
 * The length of each cycle the section counts, at the day it began: the onset-to-onset gaps
 * [cycleSummary] keeps — the plausible ones, and only the last [CYCLE_REFERENCE_INTERVALS] of them —
 * so the dots are the cycles its count, average and spread are taken over, and no others.
 */
private fun cycleChart(entries: List<JournalEntry>, period: ClosedRange<LocalDate>): ReportChart? {
    val cycles = cycleGaps(entries)
        .takeLastWhile { (_, length) -> length < CYCLE_BREAK_DAYS }
        .filter { (_, length) -> length in PLAUSIBLE_CYCLE_DAYS }
        .takeLast(CYCLE_REFERENCE_INTERVALS)
    return chartOf(
        period,
        res(R.string.unit_days),
        listOf(ChartSeries(cycles.map { (start, length) -> ChartPoint(start, length.toDouble()) }, ChartStyle.DOTS)),
        res(R.string.report_chart_cycle_lengths),
    )
}

/** Every gap from one period's start to the next among [entries], in days, at the day it began. */
private fun cycleGaps(entries: List<JournalEntry>): List<Pair<LocalDate, Int>> = entries
    .filter { it.kind == KIND_CYCLE }
    .mapNotNull { it.day.chartDay() }
    .distinct()
    .sorted()
    .zipWithNext { start, next -> start to ChronoUnit.DAYS.between(start, next).toInt() }

/**
 * The cycles the section counts — how many, their average and its spread — and under them the gaps
 * between two starts that all three leave out for falling outside [PLAUSIBLE_CYCLE_DAYS].
 *
 * Counted, because the figures above cannot be read without the count. Three cycles averaging 29
 * days say "regular", and a 75-day gap among them — the one a gynaecologist is looking for — is
 * missing from the count, the average, the spread and the chart alike: without this row there would
 * be not a word of it on the sheet. Every such gap in the period is counted, a break of
 * [CYCLE_BREAK_DAYS] or more included.
 * Where no gap was plausible there are no figures for them to have been left out of, and the row
 * stands on its own.
 */
private fun cycleLengthRows(summary: CycleSummary, entries: List<JournalEntry>): List<ReportRow> = buildList {
    val counted = summary.recordedCycles > 0
    if (counted) {
        add(ReportRow(res(R.string.report_cycles), ReportText.Quantity(R.plurals.count_cycles, summary.recordedCycles)))
        add(
            ReportRow(
                label = res(R.string.report_cycle_length),
                value = ReportText.Quantity(R.plurals.count_days, summary.averageLengthDays),
                note = cycleSpread(summary),
            ),
        )
    }
    val leftOut = cycleGaps(entries).count { (_, length) -> length !in PLAUSIBLE_CYCLE_DAYS }
    if (leftOut > 0) {
        add(
            ReportRow(
                label = res(R.string.report_cycle_gaps_outside, PLAUSIBLE_CYCLE_DAYS.first, PLAUSIBLE_CYCLE_DAYS.last),
                value = plain(leftOut.toString()),
                note = res(R.string.report_left_out).takeIf { counted },
                detail = counted,
            ),
        )
    }
}

/** A period's start or end, or an observation of the cycle that is not an act. */
private fun JournalEntry.isCycleRecord(): Boolean =
    kind == KIND_CYCLE || kind == KIND_CYCLE_END || cycleLog()?.let { it !in INTIMATE_LOGS } == true

/** The newest period start on or before [to], anywhere in [journal]; null where none was ever marked. */
private fun lastCycleStart(journal: List<JournalEntry>, to: LocalDate): LocalDate? = journal
    .filter { it.kind == KIND_CYCLE }
    .mapNotNull { it.day.toLocalDateOrNull() }
    .filter { it <= to }
    .maxOrNull()

/** "Logged on 4 days": the days among [rows] an entry was made on, never the taps. */
private fun loggedOn(rows: List<JournalEntry>): ReportText =
    ReportText.Quantity(R.plurals.report_logged_on_days, rows.map(JournalEntry::day).distinct().size)

/** "28 – 33 days" under the average, where enough cycles were measured to have a spread at all. */
private fun cycleSpread(summary: CycleSummary): ReportText? {
    val shortest = summary.shortestDays ?: return null
    val longest = summary.longestDays ?: return null
    return res(R.string.report_cycle_spread, shortest, longest)
}

/**
 * What the person said about their own allergies, which is the one section of this sheet that is
 * not a measurement and not bounded by the period.
 *
 * The three answers print as three different things, and that is the whole point of asking. A list
 * of allergens is [SectionPresence.PRESENT] and labelled self-reported. "None that I know of" is
 * [SectionPresence.NO_KNOWN] — the one place in this app that member is ever used, because it is
 * the one question the person themselves can answer negatively. "Never tested" is neither: it is
 * [SectionPresence.NO_DATA] with a line saying why, which is what a doctor needs to know before
 * deciding whether to ask for a test.
 *
 * Nothing here is a test result, and the caveat under it says so. The fourteen are a food-labelling
 * list; a reaction to a medicine is not among them and the caveat says that too, so that a blank
 * where a drug allergy would be is not read as its absence.
 */
private fun allergySection(answer: AllergyAnswer, panel: Pair<LocalDate, List<AllergenResult>>?): ReportSection {
    val said = allergySection(answer)
    panel ?: return said
    val (day, rows) = panel
    val positive = rows.filter(AllergenResult::positive)
    // What the person said stays a line of its own beside the test: a panel does not answer for them.
    val reported = res(R.string.report_allergy_reported)
    val answerRows = when (answer) {
        is AllergyAnswer.Known -> said.rows
        AllergyAnswer.NoneKnown -> listOf(ReportRow(res(R.string.allergy_none_known), reported))
        AllergyAnswer.NotTested -> listOf(ReportRow(res(R.string.allergy_not_tested), reported))
        AllergyAnswer.Unanswered -> emptyList()
    }
    val panelRows = listOf(
        ReportRow(
            res(R.string.report_allergy_panel, ReportText.Day(day)),
            res(R.string.allergy_panel_count, rows.size, positive.size),
        ),
    ) + positive.map {
        ReportRow(
            ReportText.Plain("${it.code} ${it.name}".trim()),
            res(R.string.allergy_panel_class_value, it.igeClass, ReportText.Plain(it.figureText())),
        )
    }
    return ReportSection(
        part = ReportPart.ALLERGIES,
        rows = answerRows + panelRows,
        provenance = listOfNotNull(
            Provenance(ProvenanceCategory.MANUAL).takeIf { answerRows.isNotEmpty() },
            Provenance(ProvenanceCategory.LABORATORY),
        ),
        caveatRes = R.string.report_caveat_allergy_panel,
    )
}

/**
 * The newest allergy panel on file, whenever it was drawn: like the answer printed beside it, an
 * allergy is not bounded by the period.
 */
private fun latestAllergyPanel(entries: List<JournalEntry>): Pair<LocalDate, List<AllergenResult>>? =
    entries.sortedByDescending(JournalEntry::occurredAt).firstNotNullOfOrNull { entry ->
        val rows = entry.allergyPanel()
        val day = entry.day.toLocalDateOrNull()
        if (rows.isEmpty() || day == null) null else day to rows
    }

private fun allergySection(answer: AllergyAnswer): ReportSection = when (answer) {
    is AllergyAnswer.Known -> ReportSection(
        part = ReportPart.ALLERGIES,
        rows = Allergen.entries.filter { it in answer.allergens }.map {
            ReportRow(res(it.titleRes), res(R.string.report_allergy_reported))
        },
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_allergies,
    )

    AllergyAnswer.NoneKnown -> ReportSection(
        part = ReportPart.ALLERGIES,
        rows = emptyList(),
        presence = SectionPresence.NO_KNOWN,
        caveatRes = R.string.report_caveat_allergies,
    )

    AllergyAnswer.NotTested -> ReportSection(
        part = ReportPart.ALLERGIES,
        rows = emptyList(),
        presence = SectionPresence.NO_DATA,
        caveatRes = R.string.report_caveat_allergies_untested,
    )

    AllergyAnswer.Unanswered -> ReportSection(
        part = ReportPart.ALLERGIES,
        rows = emptyList(),
        presence = SectionPresence.NO_DATA,
    )
}

/**
 * The five written-down kinds, each under the section that prints it.
 *
 * Kept together so the mapping is one list rather than five lines buried in the assembly: a kind
 * belongs to exactly one section, and the pairing is the whole of what there is to know.
 */
private fun historySections(history: List<MedicalHistoryRow>, sex: String): List<ReportSection> =
    listOf(
        ReportPart.PROBLEMS to HistoryKind.CONDITION,
        ReportPart.PROCEDURES to HistoryKind.PROCEDURE,
        ReportPart.IMMUNISATIONS to HistoryKind.IMMUNISATION,
        ReportPart.PREGNANCY to HistoryKind.PREGNANCY,
        ReportPart.CONTRACEPTION to HistoryKind.CONTRACEPTION,
    ).mapNotNull { (part, kind) -> historySection(part, kind, history, sex) }

/**
 * What was written down under one kind, as a section of the sheet.
 *
 * Every row is the patient's own statement and the caveat under the section says so: nothing here
 * was checked by this app, nothing was coded, and nothing was graded. The three-state emptiness
 * applies as everywhere else — rows, or "no data recorded", never "none".
 *
 * A [HistoryKind.current] kind prints its newest row first and in full, and the rows behind it
 * quieter and indented: what somebody is using now is the answer a prescription turns on, and what
 * they used before it is context rather than an answer.
 */
private fun historySection(
    part: ReportPart,
    kind: HistoryKind,
    history: List<MedicalHistoryRow>,
    sex: String,
): ReportSection? {
    // The section's own owner, enforced where the section is built and not only where it is
    // offered: a request carrying every part — a test, a preset written wrong, a later screen —
    // must not be able to put a pregnancy row on a man's sheet.
    if (part.sex != null && part.sex != sex) return null
    val rows = history.ofHistoryKind(kind)
    if (rows.isEmpty()) return null
    return ReportSection(
        part = part,
        rows = rows.mapIndexed { index, row ->
            ReportRow(
                label = historyName(row),
                value = historyWhen(row, kind),
                note = row.detail.takeIf(String::isNotBlank)?.let { ReportText.Plain(it) },
                detail = kind.current && index > 0,
            )
        },
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_history,
    )
}

/** The record's own name: the word behind a fixed option, or the words the person typed. */
private fun historyName(row: MedicalHistoryRow): ReportText =
    row.historyOption()?.let { res(it.titleRes) } ?: ReportText.Plain(row.title)

/**
 * When it was, in the shape its kind can honestly carry.
 *
 * A kind that can end and has not says so — "since March" is different from "in March" to anybody
 * deciding what to prescribe — and a record nobody dated says that instead of borrowing today.
 */
private fun historyWhen(row: MedicalHistoryRow, kind: HistoryKind): ReportText {
    val start = row.startDay.asHistoryDay()
    val end = row.endDay.asHistoryDay()
    return when {
        start != null && end != null ->
            res(R.string.history_between, ReportText.Day(start), ReportText.Day(end))

        start != null && kind.hasEnd -> res(R.string.history_since, ReportText.Day(start))

        start != null -> ReportText.Day(start)

        end != null -> res(R.string.history_until, ReportText.Day(end))

        else -> res(R.string.history_no_date)
    }
}

/**
 * Alcohol and tobacco over the period: the social history a doctor asks about out loud, counted
 * rather than described.
 *
 * Grams of ethanol and grams of tobacco are what the journal stores, and both are printed beside
 * the figure they are usually asked in — standard drinks, and cigarette equivalents. Days rather
 * than totals alone, because "sixty grams over the quarter" reads differently from "sixty grams on
 * one evening", and the app knows which it was.
 *
 * The completeness under it counts the entries and every day either habit was logged on, the way
 * the bathroom section counts its two kinds. The caveat says what this section cannot see: a drink
 * logged from the food catalogue is a meal with no ethanol on it, and is not counted here.
 *
 * Both are drawn over [period] a week at a time — grams of ethanol, and tobacco in the cigarette
 * equivalents its row prints — over the weeks something was logged: a week with nothing written
 * down is absent, since the app cannot tell a sober week from one nobody logged. So is the short
 * week a period starts with, which a sum would draw as a light one — see [wholeWeeks].
 */
private fun socialSection(entries: List<JournalEntry>, days: Int, period: ClosedRange<LocalDate>): ReportSection? {
    val habits = habitsOver(entries)
    val logged = entries.filter { it.kind == KIND_ALCOHOL || it.kind == KIND_SMOKING }
    if (habits.drinkingDays == 0 && habits.smokingDays == 0) return null
    val ethanol = dailyTotals(entries.filter { it.kind == KIND_ALCOHOL }) { it.servingG }
    val tobacco = dailyTotals(entries.filter { it.kind == KIND_SMOKING }) { it.servingG / CIGARETTE_EQUIVALENT_G }
    val charts = listOfNotNull(
        weeklyBars(ethanol, res(R.string.alcohol_title), res(R.string.unit_grams), period),
        weeklyBars(tobacco, res(R.string.smoking_title), res(R.string.report_chart_cigarettes), period),
    )
    val rows = buildList {
        if (habits.drinkingDays > 0) {
            add(
                ReportRow(
                    label = res(R.string.alcohol_title),
                    // With the size of the drink it was counted in: a "standard drink" is 14 g to a
                    // reader in the United States and a unit of 8 g to one in Britain.
                    value = res(
                        R.string.report_standard_drinks,
                        habits.standardDrinks.pretty(),
                        withUnit(STANDARD_DRINK_G.pretty(), res(R.string.unit_grams)),
                    ),
                    note = ReportText.Quantity(R.plurals.count_days, habits.drinkingDays),
                ),
            )
            add(
                ReportRow(
                    label = res(R.string.report_ethanol),
                    value = withUnit(habits.ethanolG.roundToInt(), res(R.string.unit_grams)),
                    detail = true,
                ),
            )
        }
        if (habits.smokingDays > 0) {
            add(
                ReportRow(
                    label = res(R.string.smoking_title),
                    value = res(R.string.report_cigarette_equivalents, habits.cigaretteEquivalents.pretty()),
                    note = ReportText.Quantity(R.plurals.count_days, habits.smokingDays),
                ),
            )
        }
    }
    return ReportSection(
        part = ReportPart.SOCIAL,
        rows = rows,
        completeness = Completeness(logged.map(JournalEntry::day).distinct().size, days, logged.size),
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_social,
        charts = charts.sideBySide(),
    )
}

/**
 * [perDay] summed a week, a bar a week that holds any day of it, in [unit] a week — over the weeks the
 * period holds whole: see [wholeWeeks].
 */
private fun weeklyBars(
    perDay: Map<String, Double>,
    title: ReportText,
    unit: ReportText,
    period: ClosedRange<LocalDate>,
): ReportChart? {
    val weekly = byWeek(perDay.byDate(), period.endInclusive).wholeWeeks(period).mapValues { (_, week) -> week.sum() }
    return chartOf(
        period,
        res(R.string.report_changes_per_week, unit),
        listOf(ChartSeries(weekly.points(), ChartStyle.BARS)),
        title,
    )
}

/**
 * What was counted in a bathroom: stools by consistency and the times a bladder was emptied.
 *
 * Counts and a daily rate, never a verdict. The consistency words are the app's own three rungs,
 * and they are the person's own report of them — nothing here is a Bristol score, and the sheet
 * does not print one.
 *
 * Each rate is over days the bathroom log was used, with that count beside it, and not over the
 * period: a day nothing was logged is a day nobody logged, and dividing by every day of the period
 * would spread a few days of diary thinly over months. Stools are over the days of either kind rather
 * than their own, because a stool is logged when there is one — stools over stool days could never
 * come out under one a day, and a slow bowel is exactly what the row is there to show. Urinations
 * are over their own days: a day with a stool on record and no urination is a day the bladder
 * diary was not kept, not a day the bladder was never emptied, and counting it as none would pull
 * the rate down with days nobody counted.
 *
 * Drawn over [period] on the same days — see [bowelChart].
 */
private fun bowelSection(entries: List<JournalEntry>, days: Int, period: ClosedRange<LocalDate>): ReportSection? {
    val stools = entries.filter { it.kind == KIND_STOOL }
    val urinations = entries.filter { it.kind == KIND_URINATION }
    if (stools.isEmpty() && urinations.isEmpty()) return null
    val loggedDays = (stools + urinations).map(JournalEntry::day).distinct().size
    val urinationDays = urinations.map(JournalEntry::day).distinct().size
    val rows = buildList {
        if (stools.isNotEmpty()) {
            add(
                ReportRow(
                    label = res(R.string.stool_title),
                    value = plain(stools.size.toString()),
                    note = join(
                        res(R.string.report_per_day_rate, perDay(stools.size, loggedDays)),
                        ReportText.Quantity(R.plurals.count_days, loggedDays),
                    ),
                ),
            )
            StoolKind.entries.forEach { kind ->
                val count = stools.count { stoolKindOf(it) == kind }
                if (count > 0) {
                    add(ReportRow(res(kind.titleRes), plain(count.toString()), detail = true))
                }
            }
        }
        if (urinations.isNotEmpty()) {
            add(
                ReportRow(
                    label = res(R.string.urination_title),
                    value = plain(urinations.size.toString()),
                    note = join(
                        res(R.string.report_per_day_rate, perDay(urinations.size, urinationDays)),
                        ReportText.Quantity(R.plurals.count_days, urinationDays),
                    ),
                ),
            )
        }
        // The ticks the bathroom screen collects, counted. Blood, burning and urgency are the
        // reasons that screen has a tick list at all, and a report that dropped them would be
        // carrying the count of visits while leaving out what happened at them.
        BathroomNote.entries.forEach { note ->
            val count = (stools + urinations).count { note in bathroomNotesOf(it.measurementPayloadJson) }
            if (count > 0) add(ReportRow(res(note.titleRes), plain(count.toString()), detail = true))
        }
    }
    return ReportSection(
        part = ReportPart.BOWEL,
        rows = rows,
        completeness = Completeness(loggedDays, days, stools.size + urinations.size),
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_selflog,
        charts = listOfNotNull(bowelChart(stools, urinations, period)),
    )
}

/**
 * How many of each kind a day, a dot a day on the days the rate beside its row is taken over, so a
 * series' mean is that rate. Stools are on every day the bathroom log was used, and a day with a
 * urination and no stool is a day without one, for the reason the rate says. Urinations are on the
 * days one was logged, and never drawn at nought beside a stool — see [bowelSection]. A day with
 * neither logged is not on the chart.
 */
private fun bowelChart(
    stools: List<JournalEntry>,
    urinations: List<JournalEntry>,
    period: ClosedRange<LocalDate>,
): ReportChart? {
    fun counted(rows: List<JournalEntry>, days: List<String>, labelRes: Int): ChartSeries? =
        rows.takeIf { it.isNotEmpty() }?.let {
            val perDay = rows.groupingBy(JournalEntry::day).eachCount()
            val points = days.associateWith { (perDay[it] ?: 0).toDouble() }.byDate().points()
            ChartSeries(points, ChartStyle.DOTS, res(labelRes))
        }
    return chartOf(
        period,
        res(R.string.report_per_day_rate, res(R.string.report_chart_times)),
        listOfNotNull(
            counted(stools, (stools + urinations).map(JournalEntry::day).distinct(), R.string.stool_title),
            counted(urinations, urinations.map(JournalEntry::day).distinct(), R.string.urination_title),
        ),
    )
}

/** Completed appointments, using the same local dates as the visit history. */
internal fun reportVisits(
    visits: List<Appointment>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<Appointment> = visits.filter {
    it.doneAt != null && Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() in from..to
}.sortedByDescending(Appointment::atMs)

private fun visitsSection(visits: List<Appointment>, zone: ZoneId): ReportSection? {
    if (visits.isEmpty()) return null
    val rows = visits.flatMap { visit ->
        buildList {
            add(
                ReportRow(
                    label = ReportText.Day(Instant.ofEpochMilli(visit.atMs).atZone(zone).toLocalDate()),
                    value = if (visit.doctor.isBlank()) {
                        res(specialtyTitleRes(visit.specialty))
                    } else {
                        join(res(specialtyTitleRes(visit.specialty)), plain(visit.doctor))
                    },
                    note = plain(visit.place),
                ),
            )
            if (visit.note.isNotBlank()) {
                add(ReportRow(res(R.string.visit_note), plain(visit.note), detail = true))
            }
        }
    }
    return ReportSection(
        part = ReportPart.VISITS,
        rows = rows,
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_visits_patient_notes,
    )
}

/**
 * The sensitive section, and the only one that can reach a sheet without a preset ever putting it
 * there.
 *
 * Two depths, and the difference between them is what a reader learns about single days.
 * [SexualDetail.SUMMARY] is counts over the whole period — how many acts, how many with protection
 * and how many without — which is what a clinical question about contraception actually needs; the
 * symptoms are a section of their own with a question of their own, see [sexualSymptomSection].
 * [SexualDetail.DETAILED] adds the dated acts, and is a second answer the person gives separately.
 *
 * Protection is carried exactly as it was recorded, and the journal records two states: protected
 * and unprotected. Health Connect's record has a third, not stated, which the import does not keep
 * — see [importCycleRecords] — so an act another app wrote without an answer is not counted
 * here, and the caveat under the section says so. Nothing is inferred about a partner, and nothing
 * about a partner is stored to be inferred from. Solo acts are counted apart, because the journal
 * keeps them apart and because they answer a different question.
 */
private fun sexualSection(entries: List<JournalEntry>, detail: SexualDetail): ReportSection? {
    val acts = entries.filter { it.cycleLog() in INTIMATE_LOGS }
    val solo = entries.filter { it.kind == KIND_MASTURBATION }
    if (acts.isEmpty() && solo.isEmpty()) return null
    val protected = acts.count { it.cycleLog() == CycleLog.SEX_PROTECTED }
    val unprotected = acts.count { it.cycleLog() == CycleLog.SEX_UNPROTECTED }
    val rows = buildList {
        if (acts.isNotEmpty()) {
            add(ReportRow(res(R.string.report_sexual_acts), plain(acts.size.toString())))
            add(ReportRow(res(R.string.cycle_sex_protected), plain(protected.toString()), detail = true))
            add(ReportRow(res(R.string.cycle_sex_unprotected), plain(unprotected.toString()), detail = true))
        }
        if (solo.isNotEmpty()) {
            add(ReportRow(res(R.string.report_sexual_solo), plain(solo.size.toString())))
        }
    }
    val dated = if (detail == SexualDetail.DETAILED) {
        acts.sortedByDescending(JournalEntry::occurredAt).map { entry ->
            ReportRow(
                label = dayText(entry.day),
                value = res(entry.cycleLog()?.titleRes ?: R.string.report_sexual_unstated),
            )
        }
    } else {
        emptyList()
    }
    return ReportSection(
        part = ReportPart.SEXUAL_HEALTH,
        rows = rows,
        // An act Health Connect handed over with its protection stated was written by another app.
        provenance = provenanceOf((acts + solo).map(JournalEntry::measurementSource)),
        caveatRes = R.string.report_caveat_sexual,
        appendix = dated,
    )
}

/**
 * The symptoms, as symptoms: reported or not on the sheet at all.
 *
 * No count beside them, and that is the design rather than a shortage of data. "Pain during sex ×4"
 * is a tally of acts wearing a clinical word, and the question a doctor asks — has this been
 * happening — is answered by the presence of the line. Nothing here carries a date, a protection or
 * a partner either, and the caveat under it says both what is absent and the one thing the ticks
 * cannot tell apart: the app records the same symptom words against a solo entry as against an act.
 */
private fun sexualSymptomSection(entries: List<JournalEntry>, sex: String): ReportSection? {
    val intimate = entries.filter { it.cycleLog() in INTIMATE_LOGS || it.kind == KIND_MASTURBATION }
    val reported = sexNotesFor(sex).filter { note ->
        intimate.any { note in sexNotesOf(it.measurementPayloadJson) }
    }
    if (reported.isEmpty()) return null
    return ReportSection(
        part = ReportPart.SEXUAL_SYMPTOMS,
        rows = reported.map { ReportRow(res(it.titleRes), res(R.string.report_symptom_reported)) },
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_sexual_symptoms,
    )
}

/**
 * A count over some days as a rate a day, to one decimal: "0.8 a day" rather than 24 in a month.
 *
 * A rate that rounds to nothing is printed as under a tenth rather than as "0.0": the count beside
 * it is not nought, and a zero printed next to it would read as none.
 */
private fun perDay(count: Int, days: Int): String {
    val rate = if (days <= 0) 0.0 else count.toDouble() / days
    return if (count > 0 && rate < SMALLEST_PRINTED_RATE) "<0.1" else rate.pretty()
}

/** Half of the last decimal the rate is printed to: anything smaller would print as "0.0". */
private const val SMALLEST_PRINTED_RATE = 0.05

/**
 * The paragraphs printed last: that this is not a medical device, and where every band on the sheet
 * came from — only the bands the sheet actually used, so a report with no blood tests does not
 * credit the WHO for bands it never printed.
 *
 * Each sensitive section brings its own paragraph saying it is there at the person's request. The
 * two say different things because they carry different things: the one about sexual activity
 * speaks of protection, which a morning check-in has nothing to do with.
 */
@Suppress("LongParameterList") // One flag per paragraph the foot of the sheet can carry.
private fun reportNotes(
    bmiGraded: Boolean,
    bandedVitals: Collection<Vital>,
    labs: Boolean,
    adherence: Boolean,
    mood: Boolean,
    sensitive: Boolean,
    mornings: Boolean,
    chosen: Boolean,
): List<ReportText> = buildList {
    add(res(R.string.report_note_not_medical))
    if (bmiGraded) add(res(R.string.report_note_bmi))
    bandedVitals.forEach { vital ->
        vital.sourceRes?.let { add(res(R.string.report_note_band_source, res(vital.titleRes), res(it))) }
    }
    if (labs) add(res(R.string.report_note_labs))
    if (adherence) add(res(R.string.report_note_adherence))
    if (mood) add(res(R.string.report_note_mood))
    if (sensitive) add(res(R.string.report_note_sensitive))
    if (mornings) add(res(R.string.report_note_sensitive_morning))
    if (chosen) add(res(R.string.report_note_chosen))
}

/**
 * The band this person's reading is read against, at the age they were on the day of it — and at
 * the site it was taken at. A thermometer under the arm or in the ear is not graded against the
 * core-temperature band on the phone ([temperatureBandApplies]), so the sheet does not print that
 * band beside it either, nor credit its source in the notes: it says instead why there is none.
 */
private fun Vital.bandFor(profile: UserProfile, reading: VitalReading): LabBand? =
    bandFor(profile.sex, ageOn(reading.day, profile.birthDate))
        ?.takeIf { this != Vital.BODY_TEMPERATURE || temperatureBandApplies(reading.siteName) }

/**
 * "90 – 139/89", "from 95", "up to 25": the printed edges are the last figures inside, as the card
 * prints them — and on the scale the reading above them was printed on.
 *
 * A band whose edges stayed Celsius under a Fahrenheit reading would be the one thing on the sheet
 * a doctor could not check: 100.4 °F against a range ending at 37.9.
 */
private fun vitalRange(vital: Vital, band: LabBand, imperial: Boolean): ReportText {
    val pair = vital.secondaryBand
    val pairLow = pair?.let { it.low ?: it.veryLow }
    val pairTop = (pair?.high ?: pair?.veryHigh)?.let(vital::insideTop)
    val low = (band.low ?: band.veryLow)?.let { vital.printed(vital.insideBottom(it, band), pairLow, imperial) }
    val top = (band.high ?: band.veryHigh)?.let { vital.printed(vital.insideTop(it), pairTop, imperial) }
    return rangeText(low, top)
}

/** The same range for a blood marker, in the unit the profile prints it in, at the marker's [places]. */
private fun labRange(marker: LabMarker, band: LabBand, imperial: Boolean, places: Int): ReportText {
    val (low, high) = marker.printedEdges(band, imperial, places)
    return rangeText(low, high)
}

private fun rangeText(low: String?, high: String?): ReportText = when {
    low != null && high != null -> res(R.string.labs_norm_range, low, high)
    high != null -> res(R.string.labs_norm_up_to, high)
    low != null -> res(R.string.labs_norm_from, low)
    else -> res(R.string.vitals_no_band)
}

private fun heightText(heightCm: Int, imperial: Boolean): ReportText =
    if (imperial) plain(imperialHeight(heightCm)) else res(R.string.unit_cm_value, heightCm)

/** Kilograms, or the pounds the profile card prints, with the unit the card prints after them. */
private fun weightText(kg: Double, imperial: Boolean, sign: String = ""): ReportText {
    val shown = if (imperial) imperialWeight(kg) else kg
    return withUnit(sign + shown.pretty(), res(if (imperial) R.string.unit_lb else R.string.unit_kg))
}

/** Whether the course was running on any day of the period: started by [to], not ended before [from]. */
private fun Medication.overlaps(from: LocalDate, to: LocalDate): Boolean {
    val start = startDay.toLocalDateOrNull() ?: return false
    if (start > to) return false
    val end = endDay?.let { it.toLocalDateOrNull() } ?: return true
    return end >= from
}

/** Days in the period, both ends counted, which is the window [medAdherence] is asked for. */
private fun daysIn(from: LocalDate, to: LocalDate): Int = (ChronoUnit.DAYS.between(from, to) + 1).toInt()

/** A stored day as a [ReportText.Day]; one the parser will not take is printed exactly as stored. */
private fun dayText(day: String): ReportText =
    day.toLocalDateOrNull()?.let { ReportText.Day(it) } ?: plain(day)

private fun res(id: Int, vararg args: Any): ReportText = ReportText.Res(id, args.toList())

/**
 * A whole count as the sheet prints one: grouped the way the reader's language groups thousands, and
 * in Latin digits — the one numbering system every other figure on the sheet is in, see
 * [latinDigits]. The app's own [formatted] takes the language's digits with its grouping, and a
 * phone set to Arabic would print the steps a day as the only Eastern Arabic figure on the page; the
 * axis of the chart above the row is set the same way ([valueLabels]).
 */
internal fun Long.sheetCount(): String = NumberFormat.getIntegerInstance(latinDigits(Locale.getDefault())).format(this)

private fun plain(text: String): ReportText = ReportText.Plain(text)

private fun join(vararg parts: ReportText?): ReportText = ReportText.Join(parts.filterNotNull())

private fun withUnit(figure: Any, unit: ReportText): ReportText = res(R.string.report_value_unit, figure, unit)

/** The three labels the profile card prints where it prints no verdict — see [bmiLabelRes]. */
private val NO_BMI_VERDICT = setOf(
    R.string.bmi_no_data,
    R.string.vitals_no_verdict_age,
    R.string.vitals_no_verdict_birthdate,
)

/**
 * The results the blood-test section prints of one marker: its newest and the three under it. The
 * context under each draw and the changes section take the same results, and their precision and
 * their pairs agree with this section only while all three count alike, so they count by this.
 */
internal const val PRINTED_LAB_RESULTS = 4

/** The minute after the last one: a report made at this minute counts every dose of the day as due. */
private const val MINUTES_IN_DAY = 24 * 60
