package com.pwnall.health

import android.content.res.Resources
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Lab work is its own kind, so the journal shows a flask rather than the scales. */
const val KIND_LABS = "LABS"

/** Where a panel's attached files live inside its payload. */
private const val ATTACHMENTS_KEY = "attachments"

/** The files attached to a saved panel, if any. */
internal fun JournalEntry.labAttachments(): String =
    measurementPayloadJson
        ?.let { runCatching { JSONObject(it).optString(ATTACHMENTS_KEY) }.getOrNull() }
        .orEmpty()

/**
 * What a lab row is called, by what it holds — as the add flow named it when it was saved
 * ([LabAddType]): an allergy panel; one ultrasound, MRI or other examination's file and no figure;
 * otherwise the lab results. Read again in the language showing ([ownTitleRes]).
 */
internal fun JournalEntry.labTitleRes(): Int {
    val payload = measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: return R.string.labs_title
    if (payload.has(ALLERGY_PANEL_KEY)) return R.string.lab_add_allergy
    val figures = LabMarker.entries.any { payload.has(it.key) }
    val document = medicalAttachments(payload.optString(ATTACHMENTS_KEY)).singleOrNull()?.kind
        ?.takeIf { kind -> LabAddType.entries.any { it.document == kind && it != LabAddType.ALLERGY } }
    return document?.titleRes?.takeUnless { figures } ?: R.string.labs_title
}

/**
 * What was given — the form's first question, and the one that decides which panels it offers.
 *
 * Coarser than [LabSpecimen] on purpose. Somebody holding a sheet knows they gave blood or urine;
 * whether the tube was serum or plasma is a detail the blood form asks after that, and may be left
 * blank. Saliva is not offered: nothing the app holds is read from it.
 */
enum class LabSample(val titleRes: Int) {
    BLOOD(R.string.labs_sample_blood),
    URINE(R.string.labs_specimen_urine),
}

/** Which sheet a marker is printed on. The screen groups its fields the same way. */
enum class LabPanel(val titleRes: Int, val sample: LabSample = LabSample.BLOOD) {
    GLYCEMIA(R.string.labs_panel_glycemia),
    LIPIDS(R.string.labs_panel_lipids),
    KIDNEY(R.string.labs_panel_kidney),
    LIVER(R.string.labs_panel_liver),
    BLOOD(R.string.labs_panel_blood),
    INFLAMMATION(R.string.labs_panel_inflammation),
    THYROID(R.string.labs_panel_thyroid),
    MICRONUTRIENTS(R.string.labs_panel_micronutrients),
    URINE(R.string.labs_panel_urine, LabSample.URINE),
}

/**
 * The edges of a reference band. An absent edge is an edge the marker does not have: cholesterol is
 * only read from above, haemoglobin only from below, ferritin from both.
 */
data class LabBand(
    val high: Double? = null,
    val veryHigh: Double? = null,
    val low: Double? = null,
    val veryLow: Double? = null,
    /**
     * Whether [low] itself is already low. Every band here reads "strictly below is below", and
     * prints [low] as the first value inside; a source that says "90 or lower" names an edge that is
     * itself outside, and this grades it as the source does, while a range printed from it starts one
     * step above it ([Vital.insideBottom]).
     */
    val lowInclusive: Boolean = false,
)

/**
 * How a marker is written where SI is not used: what to multiply the stored value by, what the
 * result is called, and how many decimals that unit is printed with. Follows the profile's
 * METRIC/IMPERIAL setting.
 *
 * [decimals] is the step a lab sheet prints the unit at. It is where printing starts, not where it
 * stops: [printPlaces] goes finer only where this step would put a figure on the wrong side of the
 * band printed beside it. Creatinine's mg/dL takes two, as American sheets print it: at one, 0.95 and
 * 1.04 both read "1". CRP's mg/dL is only ever typed, never printed, and takes two: a hs-CRP of
 * 0.3 mg/L is 0.03 of them.
 *
 * [ucum] is set on the units a field lets a person pick ([otherUnit]), because the panel records
 * which one a figure was typed in and a record takes the UCUM spelling, not the label.
 *
 * [offset] is added after [factor], for the one unit that is no multiple of the stored one: HbA1c's
 * mmol/mol ([HBA1C_IFCC]). It is only ever a unit a field is switched to, never a profile's, so the
 * band edges, which are converted for the profile's unit alone, are read through [factor] alone.
 */
data class LabImperial(
    val unitRes: Int,
    val factor: Double,
    val decimals: Int = 0,
    val ucum: UcumUnit? = null,
    val offset: Double = 0.0,
) {
    /** A figure stored in the band's unit, written in this one. */
    fun of(stored: Double): Double = stored * factor + offset
}

/**
 * Ordered by how much a reading is worth saying, so the most pressing one can be picked with a
 * `maxByOrNull`. FAR_ is the clinician level; the plain directions are the attention level.
 */
enum class LabStatus { NORMAL, BELOW, ABOVE, FAR_BELOW, FAR_ABOVE }

/**
 * A blood or urine marker the app can hold, and the band its reading is read against. Which of the
 * two is its panel's [LabPanel.sample]: a urine creatinine is its own entry, never [CREATININE] on a
 * different sheet, because everything downstream of that one — the filtration rate, its serum code —
 * is a blood figure's.
 *
 * Everything is **stored** in the unit its reference band is published in. Bands come from the
 * bodies each entry names, and [bandSourceRes] prints them; sex-specific ones carry [femaleBand], and
 * the two markers food moves carry [fedBand]. Nothing here interprets a result — see [labsAdvice].
 *
 * One enum, no per-marker classes. [derived] entries are computed rather than typed, so
 * the same table drives the form, the history and the advice.
 */
enum class LabMarker(
    /** Key inside [JournalEntry.measurementPayloadJson]. Glucose shares Health Connect's own key. */
    val key: String,
    val panel: LabPanel,
    val titleRes: Int,
    val unitRes: Int,
    val valid: ClosedFloatingPointRange<Double>,
    val band: LabBand,
    /** Where the band differs for women; null when it does not. */
    val femaleBand: LabBand? = null,
    /** One line under the figure where the marker needs a word the band cannot carry. */
    val noteRes: Int? = null,
    /** Where food moves the band; null when it does not. */
    val fedBand: LabBand? = null,
    val imperial: LabImperial? = null,
) {
    /**
     * WHO fasting bands: 6.1 opens impaired fasting glucose, 7.0 is the diabetes threshold. The
     * floor is ADA's and does not move with food: below 3.9 mmol/L is hypoglycaemia, below 3.0 its
     * clinically serious level 2 — the one reading a meter's owner most needs painted, and the one a
     * band with only a ceiling would paint as normal.
     *
     * Its two fields meet from 10 to 50 — a diabetic's mmol/L, a severe low's mg/dL — so the field
     * asks which one was typed: see [UNIT_CHOICE_MARKERS].
     */
    GLUCOSE(
        key = "concentrationMmolL",
        panel = LabPanel.GLYCEMIA,
        titleRes = R.string.labs_marker_glucose,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.5..50.0,
        band = LabBand(high = 6.1, veryHigh = 7.0, low = 3.9, veryLow = 3.0),
        // After food only the unambiguous figure means anything: WHO's random-plasma threshold.
        fedBand = LabBand(veryHigh = 11.1, low = 3.9, veryLow = 3.0),
        imperial = LabImperial(R.string.labs_unit_mgdl, 18.016, ucum = UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * ADA: 5.7–6.4% is the raised band, 6.5% the diagnostic threshold. Food does not move it.
     *
     * Kept in %; a British sheet prints the IFCC mmol/mol alone, which the field takes on its own chip
     * ([HBA1C_IFCC]). Typed into a field that took 3 to 20%, a sheet's 42 mmol/mol would be refused.
     */
    HBA1C(
        key = "hba1cPercent",
        panel = LabPanel.GLYCEMIA,
        titleRes = R.string.labs_marker_hba1c,
        unitRes = R.string.labs_unit_percent,
        valid = 3.0..20.0,
        band = LabBand(high = 5.7, veryHigh = 6.5),
    ),

    /**
     * Kept and shown, graded by nothing: 25 µIU/mL is an assay's reference interval, not a line
     * anybody crosses into something. See [UNGRADED_MARKERS].
     */
    INSULIN(
        key = "insulinUIUml",
        panel = LabPanel.GLYCEMIA,
        titleRes = R.string.labs_marker_insulin,
        unitRes = R.string.labs_unit_uiuml,
        valid = 0.5..300.0,
        band = LabBand(),
        noteRes = R.string.labs_note_ungraded,
    ),

    /** Glucose × insulin / 22.5, shown as the figure it is and graded by nothing — see [INSULIN]. */
    HOMA_IR(
        key = "homaIr",
        panel = LabPanel.GLYCEMIA,
        titleRes = R.string.labs_marker_homa,
        unitRes = R.string.labs_unit_none,
        valid = 0.1..50.0,
        band = LabBand(),
        noteRes = R.string.labs_note_homa,
    ),

    /**
     * 5.0 mmol/L is the HEART UK / JBS3 "healthy" line for total cholesterol; 7.5 is NICE CG71's
     * threshold for suspected familial hypercholesterolaemia in an adult. Neither is ESC/EAS 2019:
     * that guideline sets no total-cholesterol threshold at all and writes its targets on LDL.
     */
    CHOLESTEROL(
        key = "cholesterolMmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_cholesterol,
        unitRes = R.string.labs_unit_mmol,
        valid = 1.0..20.0,
        band = LabBand(high = 5.0, veryHigh = 7.5),
        imperial = LabImperial(R.string.labs_unit_mgdl, 38.67, ucum = UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /** ESC/EAS: 3.0 is the general-population band, 4.9 the level followed up on its own. */
    LDL(
        key = "ldlMmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_ldl,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.3..15.0,
        band = LabBand(high = 3.0, veryHigh = 4.9),
        imperial = LabImperial(R.string.labs_unit_mgdl, 38.67, ucum = UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /** Read from below: 1.0 mmol/L for men, 1.2 for women. */
    HDL(
        key = "hdlMmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_hdl,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.2..5.0,
        band = LabBand(low = 1.0),
        femaleBand = LabBand(low = 1.2),
        imperial = LabImperial(R.string.labs_unit_mgdl, 38.67, ucum = UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /** EAS/EFLM: 1.7 fasting, 2.0 on a sample drawn after food, which is now the usual way. */
    TRIGLYCERIDES(
        key = "triglyceridesMmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_triglycerides,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.2..20.0,
        band = LabBand(high = 1.7, veryHigh = 5.6),
        fedBand = LabBand(high = 2.0, veryHigh = 5.6),
        imperial = LabImperial(R.string.labs_unit_mgdl, 88.57, ucum = UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /** Total minus HDL: the ESC target is the LDL one plus 0.8, and it needs no fasting sample. */
    NON_HDL(
        key = "nonHdlMmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_non_hdl,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.5..20.0,
        band = LabBand(high = 3.8, veryHigh = 5.7),
        imperial = LabImperial(R.string.labs_unit_mgdl, 38.67),
    ),

    /**
     * Largely genetic and measured once in a lifetime; ESC calls above ~125 nmol/L high risk. That
     * "above" is strict, so the far edge sits a thousandth past 125, as [ALT]'s do: a sheet's 125 is
     * raised, not yet the high-risk level.
     */
    LP_A(
        key = "lpaNmolL",
        panel = LabPanel.LIPIDS,
        titleRes = R.string.labs_marker_lpa,
        unitRes = R.string.labs_unit_nmol,
        valid = 1.0..1_000.0,
        band = LabBand(high = 75.0, veryHigh = 125.001),
    ),

    /** The raw figure. What it is actually read through is [EGFR], which age and sex correct. */
    CREATININE(
        key = "creatinineUmolL",
        panel = LabPanel.KIDNEY,
        titleRes = R.string.labs_marker_creatinine,
        unitRes = R.string.labs_unit_umol,
        valid = 20.0..2_000.0,
        band = LabBand(high = 115.0),
        femaleBand = LabBand(high = 97.0),
        imperial = LabImperial(
            R.string.labs_unit_mgdl,
            1 / CREATININE_UMOL_PER_MGDL,
            decimals = 2,
            UcumUnit.MILLIGRAM_PER_DECILITRE,
        ),
    ),

    /**
     * CKD-EPI 2021. Under 60 for three months is the CKD range; under 30 is not a wait-and-see. The
     * two lines are KDIGO's GFR categories, where G3a begins under 60 and G4 under 30.
     */
    EGFR(
        key = "egfr",
        panel = LabPanel.KIDNEY,
        titleRes = R.string.labs_marker_egfr,
        unitRes = R.string.labs_unit_egfr,
        valid = 1.0..200.0,
        band = LabBand(low = 60.0, veryLow = 30.0),
    ),

    UREA(
        key = "ureaMmolL",
        panel = LabPanel.KIDNEY,
        titleRes = R.string.labs_marker_urea,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.5..60.0,
        // Deliberately metric-only: the US reports this analyte as BUN, which is a different number
        // under a different name, and relabelling it here would invite reading one as the other.
        band = LabBand(high = 7.8),
    ),

    /** Upper reference by sex; 540 is where lowering it is discussed whoever you are. */
    URIC_ACID(
        key = "uricAcidUmolL",
        panel = LabPanel.KIDNEY,
        titleRes = R.string.labs_marker_uric_acid,
        unitRes = R.string.labs_unit_umol,
        valid = 50.0..1_500.0,
        band = LabBand(high = 420.0, veryHigh = 540.0),
        femaleBand = LabBand(high = 360.0, veryHigh = 540.0),
        imperial = LabImperial(R.string.labs_unit_mgdl, 0.0168, decimals = 1, UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * ACG 2017 true-normal ceilings, which are lower than most lab sheets print: 29–33 U/L for men and
     * 19–25 for women is the healthy range, both ends inside it. 3× them is the level that stops being
     * a lifestyle conversation.
     *
     * Every edge sits a thousandth past its figure, as [ALP]'s does past 120, so that a sheet's 33, or
     * 25 for a woman, is graded normal as ACG grades it and the row prints "up to 33"; 99 and 75 are
     * still a raised reading, not a far one. With the edge at 33 itself, a 33 would read "above"
     * under "up to 32".
     */
    ALT(
        key = "altUL",
        panel = LabPanel.LIVER,
        titleRes = R.string.labs_marker_alt,
        unitRes = R.string.labs_unit_ul,
        valid = 1.0..3_000.0,
        band = LabBand(high = 33.001, veryHigh = 99.001),
        femaleBand = LabBand(high = 25.001, veryHigh = 75.001),
    ),

    AST(
        key = "astUL",
        panel = LabPanel.LIVER,
        titleRes = R.string.labs_marker_ast,
        unitRes = R.string.labs_unit_ul,
        valid = 1.0..3_000.0,
        band = LabBand(high = 40.0, veryHigh = 120.0),
        femaleBand = LabBand(high = 32.0, veryHigh = 96.0),
    ),

    /**
     * Alkaline phosphatase, the other half of the liver panel people are handed.
     *
     * 30-120 U/L is the adult interval most assays print, both ends inside it: the ceiling sits a
     * thousandth past 120, as [TSAT]'s do, so that a sheet's 120 is graded as the sheet grades it and
     * the row prints "30 – 120"; with the edge at 120 itself, a 120 would read "above" under a range
     * ending at 119. It moves a great deal with age — an adolescent's is far higher, and normally so — so the band is
     * left alone rather than given brackets this app would be inventing; the laboratory's own printed
     * range travels beside it.
     */
    ALP(
        key = "alpUL",
        panel = LabPanel.LIVER,
        titleRes = R.string.labs_marker_alp,
        unitRes = R.string.labs_unit_ul,
        valid = 5.0..2_000.0,
        band = LabBand(high = 120.001, low = 30.0),
    ),

    GGT(
        key = "ggtUL",
        panel = LabPanel.LIVER,
        titleRes = R.string.labs_marker_ggt,
        unitRes = R.string.labs_unit_ul,
        valid = 1.0..3_000.0,
        band = LabBand(high = 55.0, veryHigh = 165.0),
        femaleBand = LabBand(high = 38.0, veryHigh = 114.0),
    ),

    /**
     * Stored in µmol/L; a sheet in mg/dL prints 17.1 µmol/L each. The two fields both take 1 to 35,
     * which is every ordinary result in either unit — 2.5 mg/dL, raised, would be kept as 2.5 µmol/L
     * and called normal — so the field asks which one was typed: see [UNIT_CHOICE_MARKERS].
     */
    BILIRUBIN(
        key = "bilirubinUmolL",
        panel = LabPanel.LIVER,
        titleRes = R.string.labs_marker_bilirubin,
        unitRes = R.string.labs_unit_umol,
        valid = 1.0..600.0,
        band = LabBand(high = 21.0, veryHigh = 50.0),
        imperial = LabImperial(R.string.labs_unit_mgdl, 0.0585, decimals = 1, UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * The number on every full blood count.
     *
     * A column rather than an analytic: it completes the count a person is handed, and
     * it is the denominator of the fibrosis index the liver library prints the formula for. The
     * index itself is not computed from it here — a screening score for a named condition computed
     * off somebody's own values is exactly the line this app does not cross.
     *
     * 150–450 × 10⁹/L is the adult interval a count prints, both ends inside it, so the ceiling sits
     * a thousandth past 450 as [ALP]'s does past 120: otherwise a sheet's 450 would be graded raised
     * under a range the row prints as ending at 449.
     */
    PLATELETS(
        key = "plateletsE9L",
        panel = LabPanel.BLOOD,
        titleRes = R.string.labs_marker_platelets,
        unitRes = R.string.labs_unit_e9l,
        valid = 5.0..2_000.0,
        band = LabBand(high = 450.001, low = 150.0),
    ),

    /**
     * WHO anaemia thresholds, and the level below which nobody waits for the next appointment.
     *
     * Its two fields meet nowhere — 30 to 250 g/L, 3 to 25 g/dL — and the field asks which one was
     * typed all the same: see [UNIT_CHOICE_MARKERS].
     */
    HEMOGLOBIN(
        key = "hemoglobinGL",
        panel = LabPanel.BLOOD,
        titleRes = R.string.labs_marker_hemoglobin,
        unitRes = R.string.labs_unit_gl,
        valid = 30.0..250.0,
        band = LabBand(low = 130.0, veryLow = 80.0),
        femaleBand = LabBand(low = 120.0, veryLow = 80.0),
        imperial = LabImperial(R.string.labs_unit_gdl, 0.1, decimals = 1, UcumUnit.GRAM_PER_DECILITRE),
    ),

    /**
     * Under 30 is iron deficiency in practice, under 15 by the WHO definition. The ceiling is WHO
     * 2020 (Guideline on use of ferritin concentrations to assess iron status, table 1): in an
     * apparently healthy adult, risk of iron overload above 200 µg/L in men and 150 in women. WHO
     * writes "above", and a [LabBand.high] is the first figure outside, so the edges sit a thousandth
     * past those two, as [TSAT]'s do: a ferritin of exactly 200, or 150 for a woman, is inside, and
     * the report's "above 200, WHO's upper edge" is said of 201. At the round figures themselves a
     * 200 would be graded raised and that sentence would name 199. The ng/mL the sheet prints is the
     * same number.
     */
    FERRITIN(
        key = "ferritinNgMl",
        panel = LabPanel.BLOOD,
        titleRes = R.string.labs_marker_ferritin,
        unitRes = R.string.labs_unit_ngml,
        valid = 1.0..3_000.0,
        band = LabBand(high = 200.001, low = 30.0, veryLow = 15.0),
        femaleBand = LabBand(high = 150.001, low = 30.0, veryLow = 15.0),
    ),

    /**
     * Transferrin saturation, typed as the sheet prints it rather than worked out here: laboratories
     * compute it from TIBC or from transferrin with factors of their own, and a figure this app
     * derived would be one no laboratory reported.
     *
     * Under 20% is BSG 2021's line for iron restriction (Snook et al., Gut). The ceilings are EASL
     * 2022's haemochromatosis guideline: above 45% in women, above 50% in men and postmenopausal
     * women, which [withAge] reads as 50 and over. Those are the figures EASL uses to recognise iron
     * overload in C282Y homozygotes, not a population band. The edges sit a thousandth past them, the
     * finest step the form takes, so that the guideline's ">" keeps 45 and 50 themselves inside and a
     * sheet printing 45.3 for a woman under fifty, or 50.4 for a man, is over the line; edges half a
     * percent past would have graded both normal.
     *
     * No fed band, though an iron tablet in the day before the draw lifts it — Mayo Clinic
     * Laboratories ask for none in the 24 hours before — because no marker here carries a sex band
     * and a food band at once. The library article says it instead.
     */
    TSAT(
        key = "tsatPercent",
        panel = LabPanel.BLOOD,
        titleRes = R.string.labs_marker_tsat,
        unitRes = R.string.labs_unit_percent,
        valid = 1.0..100.0,
        band = LabBand(high = TSAT_MALE_HIGH, low = 20.0),
        femaleBand = LabBand(high = 45.001, low = 20.0),
    ),

    /** AHA/CDC risk bands. Above 10 the sample is usually saying something acute, not chronic. */
    CRP(
        key = "crpMgL",
        panel = LabPanel.INFLAMMATION,
        titleRes = R.string.labs_marker_crp,
        unitRes = R.string.labs_unit_mgl,
        valid = 0.1..500.0,
        band = LabBand(high = 3.0, veryHigh = 10.0),
    ),

    TSH(
        key = "tshMIUL",
        panel = LabPanel.THYROID,
        titleRes = R.string.labs_marker_tsh,
        unitRes = R.string.labs_unit_miul,
        valid = 0.005..150.0,
        band = LabBand(high = 4.0, veryHigh = 10.0, low = 0.4, veryLow = 0.1),
    ),

    /**
     * Stored in pmol/L; a US sheet prints ng/dL, 12.87 pmol/L each (thyroxine's molar mass is 776.87).
     * The two ranges overlap — 1.3 ng/dL is a figure the pmol/L field takes too, and would grade low —
     * so the field asks which one was typed: see [UNIT_CHOICE_MARKERS].
     */
    FT4(
        key = "ft4PmolL",
        panel = LabPanel.THYROID,
        titleRes = R.string.labs_marker_ft4,
        unitRes = R.string.labs_unit_pmol,
        valid = 1.0..100.0,
        band = LabBand(high = 22.0, low = 12.0),
        imperial = LabImperial(R.string.labs_unit_ngdl, 1 / 12.87, decimals = 1, UcumUnit.NANOGRAM_PER_DECILITRE),
    ),

    /**
     * 25-hydroxyvitamin D, the total of D2 and D3. IOM 2011, as NIH ODS tabulates it: under 30 nmol/L
     * is the risk of deficiency, under 50 inadequate for some, and above 125 (50 ng/mL) the level at
     * which the IOM asks clinicians to be concerned about adverse effects (Ross et al. 2011, JCEM).
     * Above 375 nmol/L (150 ng/mL) is the hallmark of toxicity (Marcinowska-Suchowierska et al. 2018,
     * Front Endocrinol).
     *
     * Both tops are written as a strict "above", so the edges sit a tenth past 125 and 375. ng/mL is
     * converted at ODS's 2.5 nmol/L each rather than the molar 2.496 (calcifediol weighs 400.6 g/mol):
     * that is how the IOM paired 20 with 50 and 50 with 125, and at 2.496 a sheet's 20 ng/mL, the
     * adequacy line itself, would be stored as 49.92 and coloured below it.
     */
    VITAMIN_D(
        key = "vitaminD25NmolL",
        panel = LabPanel.MICRONUTRIENTS,
        titleRes = R.string.labs_marker_vitamin_d,
        unitRes = R.string.labs_unit_nmol,
        valid = 5.0..1_500.0,
        band = LabBand(high = 125.1, veryHigh = 375.1, low = 50.0, veryLow = 30.0),
        imperial = LabImperial(R.string.labs_unit_ngml, 0.4, decimals = 1, UcumUnit.NANOGRAM_PER_MILLILITRE),
    ),

    /**
     * Total vitamin B12, the serum cobalamin. NICE NG239 (2024), table 1: under 180 ng/L (133 pmol/L)
     * is confirmed deficiency, 180 to 350 ng/L (133 to 258 pmol/L) indeterminate, over 350 unlikely.
     * NICE counts 180 and 350 as indeterminate, and 132.5 and 258.5 are the only edges that keep 180
     * and 350 ng/L and 133 and 258 pmol/L on NICE's side in both units. A pg/mL is a ng/L, and 0.7378
     * pmol/L at cobalamin's 1355.4 g/mol.
     *
     * No ceiling: NICE draws none. NICE writes for people over 16; the app grades from 18, as every
     * adult band here does. And where validated thresholds vary locally, NICE says to use the ones set
     * by the laboratory doing the test — the printed range the sheet carries beside this band.
     */
    B12(
        key = "b12PmolL",
        panel = LabPanel.MICRONUTRIENTS,
        titleRes = R.string.labs_marker_b12,
        unitRes = R.string.labs_unit_pmol,
        valid = 20.0..5_000.0,
        band = LabBand(low = 258.5, veryLow = 132.5),
        imperial = LabImperial(R.string.labs_unit_pgml, 1 / 0.7378, ucum = UcumUnit.PICOGRAM_PER_MILLILITRE),
    ),

    /**
     * Serum folate. WHO 2015 (Serum and red blood cell folate concentrations for assessing folate
     * status in populations): under 10 nmol/L is its table 2 line, read against homocysteine, and
     * under 6.8 nmol/L (3 ng/mL) its table 1 deficiency, read against macrocytic anaemia. WHO's own
     * factor is 2.265 nmol/L per ng/mL, which puts a sheet's 3.0 ng/mL at 6.795, so the far edge sits
     * at 6.79 to keep it on the "possible deficiency" side of WHO's "<3".
     *
     * No ceiling: WHO's "elevated", above 45.3 nmol/L, describes supplements and fortified food. And
     * these are lines for populations, read here against one person's figure that follows the last
     * few days' intake — the library article says both.
     */
    FOLATE(
        key = "folateNmolL",
        panel = LabPanel.MICRONUTRIENTS,
        titleRes = R.string.labs_marker_folate,
        unitRes = R.string.labs_unit_nmol,
        valid = 1.0..150.0,
        band = LabBand(low = 10.0, veryLow = 6.79),
        imperial = LabImperial(R.string.labs_unit_ngml, 1 / 2.265, decimals = 1, UcumUnit.NANOGRAM_PER_MILLILITRE),
    ),

    /**
     * Serum magnesium, printed to two decimals: a sheet prints 0.66, and at one it would read 0.7
     * beside a range starting at 0.7. 0.7–1.0 mmol/L is Pathology Harmony's adult interval (Berg 2012,
     * Clin Biochem Rev). The edges sit a hair outside it so that 1.00 and a US sheet's 1.7 mg/dL —
     * 0.6994 mmol/L at magnesium's 24.305 g/mol — both stay inside.
     *
     * A reference interval and nothing further, so no clinician edge. Only about 0.3% of the body's
     * magnesium is in the serum (Jahnen-Dechent & Ketteler 2012, Clin Kidney J), so a figure inside it
     * does not rule out depletion.
     */
    MAGNESIUM(
        key = "magnesiumMmolL",
        panel = LabPanel.MICRONUTRIENTS,
        titleRes = R.string.labs_marker_magnesium,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.1..5.0,
        band = LabBand(high = 1.005, low = 0.699),
        imperial = LabImperial(R.string.labs_unit_mgdl, 2.4305, decimals = 1, UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * The urine albumin-to-creatinine ratio, the one urine figure a guideline grades. KDIGO 2024
     * Table 3 (and KDIGO 2012 Tables 6 and 7): A1 "<3", A2 "3–30", A3 ">30" mg/mmol, so 3.0 itself
     * is raised and the far edge sits a thousandth past 30, the way TSAT's does. NICE NG203 prints
     * the same lines. KDIGO 2024 gives the same 3 mg/mmol for children over two — see
     * [bandWithheldAt] — and one sex for both.
     *
     * A US sheet prints mg/g (a µg/mg is the same number), converted exactly at creatinine's 113.12
     * g/mol, KDIGO's own 0.113: 3 mg/mmol is 26.5 mg/g. KDIGO pairs it with 30 mg/g, having rounded
     * the 3.4 mg/mmol that 30 mg/g is down to 3 (KDIGO 2012), so a sheet's 27–29 mg/g is called
     * raised here though it sits under the line KDIGO started from. Every disagreement between the
     * two columns runs towards the higher category, never away from it. The two units' ranges
     * overlap entirely, so the field asks which one was typed — see [UNIT_CHOICE_MARKERS].
     *
     * The bounds are the app's own plausibility limits, wide enough for the NHANES extremes.
     */
    URINE_ACR(
        key = "urineAcrMgMmol",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_acr,
        unitRes = R.string.labs_unit_mgmmol,
        valid = 0.01..15_000.0,
        band = LabBand(high = 3.0, veryHigh = 30.001),
        // To one decimal in either unit, as KDIGO 2024 Table 17 asks a laboratory to report it.
        imperial = LabImperial(
            R.string.labs_unit_mgg,
            CREATININE_UMOL_PER_MGDL / 10,
            decimals = 1,
            UcumUnit.MILLIGRAM_PER_GRAM,
        ),
    ),

    /**
     * The albumin concentration a sheet prints on its own ("microalbumin"). Ungraded: KDIGO 2024
     * Table 17 asks laboratories to report the ratio "rather than the concentrations alone", since a
     * concentration moves with how much was drunk, and the 20 or 30 mg/L a sheet prints is its own
     * cut-off rather than a guideline's.
     */
    URINE_ALBUMIN(
        key = "urineAlbuminMgL",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_albumin,
        unitRes = R.string.labs_unit_mgl,
        valid = 0.1..20_000.0,
        band = LabBand(),
        noteRes = R.string.labs_note_urine_concentration,
        imperial = LabImperial(R.string.labs_unit_mgdl, 0.1, decimals = 1, UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * The ratio's denominator, printed beside it. No body publishes a band for a spot urine
     * creatinine; KDIGO 2024 Table 16 lists only what moves it — sex, weight, protein, exercise.
     */
    URINE_CREATININE(
        key = "urineCreatinineMmolL",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_creatinine,
        unitRes = R.string.labs_unit_mmol,
        valid = 0.1..80.0,
        band = LabBand(),
        noteRes = R.string.labs_note_urine_ungraded,
        imperial = LabImperial(
            R.string.labs_unit_mgdl,
            1_000 / CREATININE_UMOL_PER_MGDL,
            decimals = 0,
            UcumUnit.MILLIGRAM_PER_DECILITRE,
        ),
    ),

    /**
     * The protein line of a general urinalysis, in g/L, or mg/dL on a US sheet. Ungraded for the
     * albumin's reason: KDIGO 2024 wants a protein concentration reported as a ratio to creatinine,
     * and grades a dipstick only as negative, trace or plus.
     *
     * Limitation: a sheet printing mg/L has no chip of its own; a small mg/L figure fits the g/L range
     * and is kept a thousand times too high. Ungraded, so no verdict moves.
     */
    URINE_PROTEIN(
        key = "urineProteinGL",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_protein,
        unitRes = R.string.labs_unit_gl,
        valid = 0.005..30.0,
        band = LabBand(),
        noteRes = R.string.labs_note_urine_concentration,
        imperial = LabImperial(R.string.labs_unit_mgdl, 100.0, decimals = 0, UcumUnit.MILLIGRAM_PER_DECILITRE),
    ),

    /**
     * Specific gravity, on every urinalysis to three decimals. The 1.005–1.030 a sheet prints is its
     * laboratory's interval, not a guideline's, so the figure is ungraded and that interval goes in
     * the field for the sheet's own range. The ceiling leaves room for a refractometer after contrast.
     */
    URINE_SG(
        key = "urineSpecificGravity",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_sg,
        unitRes = R.string.labs_unit_none,
        valid = 1.0..1.15,
        band = LabBand(),
        noteRes = R.string.labs_note_urine_ungraded,
    ),

    /** Urine pH. Diet moves it from one day to the next, and no guideline grades it. */
    URINE_PH(
        key = "urinePh",
        panel = LabPanel.URINE,
        titleRes = R.string.labs_marker_urine_ph,
        unitRes = R.string.labs_unit_none,
        valid = 4.0..9.5,
        band = LabBand(),
        noteRes = R.string.labs_note_urine_ungraded,
    ),
    ;

    /** Held and drawn, but never called high or low — see [UNGRADED_MARKERS]. */
    val ungraded: Boolean get() = this in UNGRADED_MARKERS

    /** Computed by [derivedLabs] from other markers rather than typed into the form. */
    val derived: Boolean get() = this in DERIVED_MARKERS

    /**
     * The band this sample is read against, or null when there is no band to read it against.
     *
     * Insulin and HOMA-IR carry no band at all ([UNGRADED_MARKERS]): a marker nobody grades is not
     * graded after breakfast either.
     *
     * [ageYears] is the age on the day of the draw, not today's — a result from four years ago is
     * read against the band that applied to the person who gave it. Under [ADULT_LAB_AGE] that is
     * no band at all for most markers — see [bandWithheldAt].
     *
     * Sex and food never both apply — no marker here carries [femaleBand] and [fedBand] at once —
     * so the order below is a formality rather than a precedence rule.
     */
    fun bandFor(sex: String, ageYears: Int, fasting: Boolean): LabBand? = when {
        ungraded -> null
        bandWithheldAt(ageYears) -> null
        !fasting && fedBand != null -> fedBand
        else -> withAge(if (sex == "FEMALE" && femaleBand != null) femaleBand else band, sex, ageYears)
    }

    /**
     * The handful of published bands that actually move with age.
     *
     * Only these four, and each for a documented reason. Most adult reference intervals do not
     * shift with the birthday, and sliding them anyway would dress an invented number up as a
     * standard. The real age correction in this app is [EGFR], where age is inside the equation.
     */
    private fun withAge(band: LabBand, sex: String, ageYears: Int): LabBand = when {
        // The 97.5th percentile of TSH climbs with age; treating an older person's 5 as raised is
        // the commonest way an incidental thyroid result turns into a referral for nothing.
        this == TSH && ageYears >= ELDERLY_AGE -> band.copy(high = ELDERLY_TSH_HIGH)

        // Urate rises after menopause until the female reference meets the male one.
        this == URIC_ACID && sex == "FEMALE" && ageYears >= MENOPAUSE_AGE ->
            band.copy(high = URIC_ACID_MALE_HIGH)

        // EASL 2022 puts postmenopausal women on the men's 50%; the app is told an age, not a menopause.
        this == TSAT && sex == "FEMALE" && ageYears >= MENOPAUSE_AGE -> band.copy(high = TSAT_MALE_HIGH)

        // WHO's anaemia thresholds climb through childhood and are the same for both sexes until 15.
        this == HEMOGLOBIN && ageYears < ADULT_BLOOD_AGE -> band.copy(low = childHemoglobinLow(ageYears))

        else -> band
    }

    /**
     * Whether a person this young has no band here at all: every marker but haemoglobin, whose WHO
     * lines are written for each age ([withAge]), and glucose and HbA1c, which keep the adult lines.
     *
     * The rest are adult intervals. An adolescent's alkaline phosphatase is far higher than an
     * adult's, and normally so; an adult lipid or liver ceiling laid over a fifteen-year-old calls
     * that a finding. So the figure is shown and graded by nothing, and the row says why — the same
     * way the profile withholds an adult BMI verdict under 18.
     *
     * Glucose and HbA1c are the exception because the young are diagnosed by the same lines. ISPAD
     * 2024 (type 2 diabetes in children and adolescents) lists the WHO and ADA prediabetes lines this
     * app reads by, and says that "none of the diagnostic criteria for prediabetes have been
     * specifically validated in youth, rather all are extrapolated from adult definitions". That is a
     * caveat, not a reason to call a fifteen-year-old's fasting 6.5 mmol/L normal: a band cut to the
     * diabetes line would print exactly that. So the adult band stays, and the row says it is an adult
     * one — see [adultBandAt].
     *
     * The urine albumin-to-creatinine ratio is not an adult line at all: KDIGO 2024 calls a first
     * morning ACR under 3 mg/mmol "usually considered normal" for children over two, and refers a
     * child at 3 or more once a repeat confirms it (PP 5.1.2; NICE NG203 1.5.6 the same). So it is
     * graded from two, with nothing to say it is an adult band, and withheld only below that.
     */
    fun bandWithheldAt(ageYears: Int): Boolean =
        ageYears < ADULT_LAB_AGE && this != HEMOGLOBIN && this !in YOUTH_GLYCAEMIA &&
            !(this == URINE_ACR && ageYears >= ACR_CHILD_AGE)

    /**
     * Which side of its band the reading fell on, or null where there is no band to fall either
     * side of: an insulin drawn after breakfast is a number without a verdict, not a normal one.
     * Nothing here is a diagnosis — see [labsAdvice].
     */
    fun status(value: Double, sex: String, ageYears: Int, fasting: Boolean): LabStatus? {
        val band = bandFor(sex, ageYears, fasting) ?: return null
        return when {
            band.veryHigh != null && value >= band.veryHigh -> LabStatus.FAR_ABOVE
            band.veryLow != null && value < band.veryLow -> LabStatus.FAR_BELOW
            band.high != null && value >= band.high -> LabStatus.ABOVE
            band.low != null && value < band.low -> LabStatus.BELOW
            else -> LabStatus.NORMAL
        }
    }

    /**
     * The stored value on the scale the profile reads, rounded to the unit's own step — where a
     * chart draws it. Printed figures go through [printed] instead, which never lets that rounding
     * carry a figure across the band beside it.
     *
     * The imperial form is rounded the way that unit is printed — a lab reports 97 mg/dL, not 97.3,
     * but it reports creatinine as 1.13 rather than 1, and a whole number there would collapse the
     * entire clinical range onto one figure. The exact value survives in storage either way.
     */
    fun display(value: Double, imperialUnits: Boolean): Double =
        unit(imperialUnits)?.let {
            val scale = 10.0.pow(it.decimals)
            (it.of(value) * scale).roundToInt() / scale
        } ?: value

    /** The reverse of [display]: what was typed, in the unit the band is written in. */
    fun stored(typed: Double, imperialUnits: Boolean): Double = storedIn(typed, unit(imperialUnits))

    /**
     * The same for a figure typed in [typedIn], whichever the profile reads; null is the stored unit.
     *
     * The quotient is cut to [STORED_DIGITS] significant digits, which drops the division's
     * floating-point residue and nothing a sheet prints: uncut, 0.01 mg/dL of CRP over 0.1 comes out
     * as 0.09999999999999999, under the 0.1 mg/L the field starts at, and would be refused.
     */
    fun storedIn(typed: Double, typedIn: LabImperial?): Double =
        typedIn?.let {
            BigDecimal((typed - it.offset) / it.factor).round(MathContext(STORED_DIGITS)).toDouble()
        } ?: typed

    /**
     * The last figure inside a band whose upper edge is [edge], in the unit the row is printed in.
     *
     * [LabBand.high] and [LabBand.veryHigh] are the first value outside — that is the comparison
     * [status] makes, and how the guidelines are worded: 6.1 mmol/L opens impaired fasting glucose,
     * 5.7% is prediabetes; a source whose ceiling is itself inside, as ACG's 33 U/L of ALT is, has its
     * edge a thousandth past it. A row printing the edge itself as "up to" names a number it colours
     * red. So the printed top is one step of the printed unit under the edge, and where a conversion
     * lands the edge between two steps, the step below it: 6.1 mmol/L is 109.9 mg/dL, and the last
     * whole milligram inside is 109.
     *
     * [places] is the step: the unit's own unless the figures beside the band are printed finer — a
     * glucose of 6.08 is inside, and beside it the top is 6.09, not 6.0. See [printPlaces].
     */
    fun insideTop(edge: Double, imperialUnits: Boolean, places: Int = decimals(imperialUnits)): Double {
        val scale = 10.0.pow(places)
        return (ceil(edge * factor(imperialUnits) * scale - EDGE_EPSILON) - 1.0) / scale
    }

    /**
     * The first figure inside a band whose lower edge is [edge]: the edge itself, since below is
     * strictly below — until a conversion rounds it. 1.2 mmol/L of HDL rounds to 46 mg/dL, and 46
     * stored back is 1.19, which the row colours blue under a range that says "from 46". The first
     * printable step at or over the edge is what the range names instead.
     */
    fun insideBottom(edge: Double, imperialUnits: Boolean, places: Int = decimals(imperialUnits)): Double {
        val scale = 10.0.pow(places)
        return ceil(edge * factor(imperialUnits) * scale - EDGE_EPSILON) / scale
    }

    /**
     * How many decimals a lab sheet prints the unit at, which is where every printed figure starts.
     * The imperial form carries its own; the stored unit takes one, except where a lab sheet prints
     * the unit whole — see [WHOLE_NUMBER_MARKERS] — or finer, as magnesium's mmol/L and a urine
     * protein's g/L are printed to hundredths and a specific gravity to thousandths.
     */
    internal fun decimals(imperialUnits: Boolean): Int = unit(imperialUnits)?.decimals ?: when (this) {
        in WHOLE_NUMBER_MARKERS -> 0
        MAGNESIUM, URINE_PROTEIN -> 2
        URINE_SG -> 3
        else -> 1
    }

    internal fun factor(imperialUnits: Boolean): Double = unit(imperialUnits)?.factor ?: 1.0

    /**
     * The lowest figure the form will actually take, in the unit the field is switched to; null is
     * the stored one.
     *
     * Not [display] of [valid]'s start: display rounds to the precision the unit is printed at, and
     * a rounded edge can convert back outside the range — 0.5 mmol/L of glucose prints as 9 mg/dL,
     * and 9 mg/dL is stored as 0.4995, which is refused. Rounding inwards, and stepping once more
     * where the division still lands short, names the bound the field really enforces. The product is
     * read with a band edge's slack ([EDGE_EPSILON]), or its residue steps the bound in for nothing:
     * CRP's 0.1 mg/L is 0.1 × 0.1 × 100 = 1.0000000000000002 hundredths of a mg/dL, which ceil makes 2.
     */
    fun lowestAcceptedIn(printed: LabImperial?): Double {
        printed ?: return valid.start
        val scale = 10.0.pow(printed.decimals)
        val candidate = ceil(printed.of(valid.start) * scale - EDGE_EPSILON) / scale
        return if (storedIn(candidate, printed) < valid.start) candidate + 1.0 / scale else candidate
    }

    /** The highest figure the form will actually take. Same rounding argument as [lowestAcceptedIn]. */
    fun highestAcceptedIn(printed: LabImperial?): Double {
        printed ?: return valid.endInclusive
        val scale = 10.0.pow(printed.decimals)
        val candidate = floor(printed.of(valid.endInclusive) * scale + EDGE_EPSILON) / scale
        return if (storedIn(candidate, printed) > valid.endInclusive) {
            candidate - 1.0 / scale
        } else {
            candidate
        }
    }

    /** What that unit is called. Markers with no imperial form ignore the setting. */
    fun unitLabelRes(imperialUnits: Boolean): Int = unit(imperialUnits)?.unitRes ?: unitRes

    internal fun unit(imperialUnits: Boolean): LabImperial? = imperial?.takeIf { imperialUnits }
}

/** µmol/L per mg/dL of creatinine — the conversion CKD-EPI's own units need. */
private const val CREATININE_UMOL_PER_MGDL = 88.4

/** The youngest age CKD-EPI is published for. Below it a filtration rate needs a paediatric equation. */
private const val CKD_EPI_MIN_AGE = 18

/** Ages at which a published band changes, and what it changes to. */
private const val ELDERLY_AGE = 70
private const val ELDERLY_TSH_HIGH = 6.0
private const val MENOPAUSE_AGE = 50
private const val URIC_ACID_MALE_HIGH = 420.0
private const val TSAT_MALE_HIGH = 50.001
private const val ADULT_BLOOD_AGE = 15

/**
 * The age the adult bands start at: the one the profile's BMI verdict starts at, and the youngest
 * CKD-EPI is published for.
 */
private const val ADULT_LAB_AGE = 18

/** The two markers read against their adult lines under [ADULT_LAB_AGE] — see [LabMarker.bandWithheldAt]. */
private val YOUTH_GLYCAEMIA = setOf(LabMarker.GLUCOSE, LabMarker.HBA1C)

/**
 * The age KDIGO 2024 writes its children's ACR line from ("children over 2 years").
 *
 * Limitation: under it the row says the band is an adult one, which is only nearly true — KDIGO
 * gives no line there at all.
 */
private const val ACR_CHILD_AGE = 2

/**
 * WHO 2024 (Guideline on haemoglobin cutoffs to define anaemia in individuals and populations), in
 * g/L and the same for both sexes: 105 at 6–23 months, 110 at 24–59 months, 115 at 5–11 years, 120
 * at 12–14. Ages here are whole years, so the first two share the 6–23-month figure; WHO publishes
 * no cutoff under six months, and a year is as fine as the birth date is read.
 */
private fun childHemoglobinLow(ageYears: Int): Double = when {
    ageYears < 2 -> 105.0
    ageYears < 5 -> 110.0
    ageYears < 12 -> 115.0
    else -> 120.0
}

/**
 * The slack under a band edge before it counts as sitting on a printed step, so that 6.1 × 10
 * landing a hair under 61 in floating point still reads as the step 61 and not as a value between
 * 60 and 61. Far larger than any such error, far smaller than any step the rows print.
 */
private const val EDGE_EPSILON = 1e-6

/**
 * The significant digits a converted figure is stored to: short of the sixteenth or so, where a
 * double's division leaves its residue, and far past the three decimals the form takes on any figure
 * the fields hold.
 */
private const val STORED_DIGITS = 12

/** Held as a set rather than a constructor flag; the enum is at detekt's parameter limit. */
private val DERIVED_MARKERS = setOf(LabMarker.HOMA_IR, LabMarker.NON_HDL, LabMarker.EGFR)

/**
 * Markers the app records and draws but refuses to call high or low.
 *
 * Insulin's 25 µIU/mL is an assay reference interval rather than a risk threshold, and ADLM's
 * Optimal Testing guidance says of the calculation built on it that "clinical assessment of insulin
 * resistance (using a calculation based on fasting insulin and glucose, such as the homeostasis
 * model assessment of insulin resistance [HOMA-IR]) is not recommended and should be reserved for
 * research purposes only". Graded, a HOMA-IR over 2.7 would raise exactly the same red
 * notice as a creatinine over its ceiling, which is the wrong register for a number no guideline
 * draws a line on. The figure and its chart stay; the verdict goes.
 */
private val UNGRADED_MARKERS = setOf(
    LabMarker.INSULIN,
    LabMarker.HOMA_IR,
    // Urine figures nobody publishes a line for. Each is on the sheet beside the ratio that is
    // graded, or on a general urinalysis whose own printed range the form takes — see their entries.
    LabMarker.URINE_ALBUMIN,
    LabMarker.URINE_CREATININE,
    LabMarker.URINE_PROTEIN,
    LabMarker.URINE_SG,
    LabMarker.URINE_PH,
)

/**
 * Markers whose stored unit a lab sheet prints whole — µmol/L, U/L, g/L, ng/mL, nmol/L, a filtration
 * rate, a platelet count, B12's pmol/L, a saturation's percent — so the step under a ceiling is one,
 * not a tenth. Everything else is printed to a decimal: left out of this set, alkaline phosphatase and
 * the platelets would print their ranges as "30 – 119.9 U/L" and "150 – 449.9".
 */
private val WHOLE_NUMBER_MARKERS = setOf(
    LabMarker.LP_A,
    LabMarker.CREATININE,
    LabMarker.EGFR,
    LabMarker.URIC_ACID,
    LabMarker.ALT,
    LabMarker.AST,
    LabMarker.ALP,
    LabMarker.PLATELETS,
    LabMarker.GGT,
    LabMarker.BILIRUBIN,
    LabMarker.HEMOGLOBIN,
    LabMarker.FERRITIN,
    LabMarker.TSAT,
    LabMarker.VITAMIN_D,
    LabMarker.B12,
)

/** The finest a lab figure is printed at: the three decimals the form accepts (see labDecimalInput). */
private const val MAX_PRINT_PLACES = 3

/**
 * The decimals one marker's figures are printed at, where [readings] are printed side by side with
 * the bands they were read against.
 *
 * The unit's own step first — see [LabMarker.decimals]. Finer only where that step would make the
 * page contradict itself, which it would at exactly the edges where a verdict matters most: a glucose
 * of 6.08 printed as 6.1 beside "inside the band" and a range ending at 6, an HbA1c of 5.68 printed
 * as 5.7 beside "not above", a bilirubin of 21 µmol/L printed as 1.2 mg/dL beside "above" and a
 * range ending at 1.2. So the figures and the band edges step down together until every printed
 * figure sits on the side of the printed band that its verdict names, and no reading that is not
 * zero prints as zero.
 *
 * The verdict itself never moves: it is read off the stored value, which is also what the
 * machine-readable export carries.
 */
fun LabMarker.printPlaces(readings: Collection<LabReading>, sex: String, imperialUnits: Boolean): Int =
    (decimals(imperialUnits)..MAX_PRINT_PLACES).firstOrNull { places ->
        readings.all { printsTrue(it, sex, imperialUnits, places) }
    } ?: MAX_PRINT_PLACES

/**
 * A stored figure as a row prints it: on the profile's scale, at [places], trailing zeros dropped.
 *
 * A figure that needs no conversion is never printed coarser than it was typed. A suppressed TSH of
 * 0.035 stays 0.035 where the unit's step is a tenth, because that is what the laboratory printed and
 * what the person copied; at the step it would read 0.0, a real result shown as zero. A converted figure is
 * rounded, since its trailing digits are the conversion's rather than anybody's — and so is one that
 * was converted on the way in, see [typedInImperial].
 */
fun LabMarker.printed(value: Double, imperialUnits: Boolean, places: Int = decimals(imperialUnits)): String =
    shown(value, imperialUnits, places).stripTrailingZeros().toPlainString()

/**
 * A figure nobody typed — the mean of a run of readings — at the unit's own step. Through [printed]
 * its decimals would read as typed ones: the mean of 6.1, 7.2, 5.4 and 7.2 would print as 6.475,
 * while a mean of 6.4666… would print as 6.5.
 */
fun LabMarker.printedRounded(value: Double, imperialUnits: Boolean): String =
    figureText(value * factor(imperialUnits), decimals(imperialUnits))

/**
 * A reading's own figure as its row prints it: [printed] at [places], which [printPlaces] has already
 * made fine enough — except where no precision up to the form's three is. A uric acid typed a
 * thousandth of a µmol/L under its ceiling is under two hundred-thousandths of a mg/dL under it, and
 * the nearest three decimals land on the ceiling. Only there is the figure rounded towards the side
 * its verdict names instead, which still leaves it within one printed step of what was stored.
 */
fun LabMarker.printed(reading: LabReading, sex: String, imperialUnits: Boolean, places: Int): String {
    val rounding = PRINT_ROUNDING.firstOrNull { printsTrue(reading, sex, imperialUnits, places, it) }
    return shown(reading.value, imperialUnits, places, rounding ?: RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

/** The nearest figure first; the one either side of it only where the nearest crosses an edge. */
private val PRINT_ROUNDING = listOf(RoundingMode.HALF_UP, RoundingMode.FLOOR, RoundingMode.CEILING)

/**
 * A band's floor and ceiling as its range prints them — the first and last figures inside, at
 * [places] — or null for a side the band does not have.
 */
fun LabMarker.printedEdges(band: LabBand, imperialUnits: Boolean, places: Int): Pair<String?, String?> =
    (band.low ?: band.veryLow)?.let { figureText(insideBottom(it, imperialUnits, places), places) } to
        (band.high ?: band.veryHigh)?.let { figureText(insideTop(it, imperialUnits, places), places) }

private fun LabMarker.shown(
    value: Double,
    imperialUnits: Boolean,
    places: Int,
    rounding: RoundingMode = RoundingMode.HALF_UP,
): BigDecimal {
    val typed = typedPlaces(value)?.takeIf { unit(imperialUnits) == null && !typedInImperial(value) } ?: 0
    return BigDecimal.valueOf(value * factor(imperialUnits)).setScale(maxOf(places, typed), rounding)
}

/**
 * Whether [value] is what a figure typed in the imperial unit is stored as, which gives it decimals
 * that are the conversion's: 1.3 mg/dL of creatinine is kept as 114.92 µmol/L, and printed as typed
 * it would read "114.92" on a scale a laboratory prints whole. A panel records the unit only for the fields
 * that offer two ([TYPED_UNITS_KEY]), and a figure is printed without its panel, so this asks whether
 * it converts back to one step of that unit exactly.
 */
private fun LabMarker.typedInImperial(value: Double): Boolean =
    imperial != null && abs(stored(display(value, true), true) - value) < EDGE_EPSILON

private fun figureText(value: Double, places: Int): String =
    BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** The fewest decimals that write [value] exactly, up to the form's three; null where none do. */
private fun typedPlaces(value: Double): Int? = (0..MAX_PRINT_PLACES).firstOrNull { places ->
    val scaled = value * 10.0.pow(places)
    abs(scaled - scaled.roundToLong()) < EDGE_EPSILON
}

/** Whether [reading], printed at [places], reads on the page the way its verdict says it does. */
private fun LabMarker.printsTrue(
    reading: LabReading,
    sex: String,
    imperialUnits: Boolean,
    places: Int,
    rounding: RoundingMode = RoundingMode.HALF_UP,
): Boolean {
    val shown = shown(reading.value, imperialUnits, places, rounding).toDouble()
    val band = bandFor(sex, reading.ageYears, reading.fasting)
    return when {
        shown == 0.0 -> reading.value == 0.0
        band == null -> true
        else -> printedStatus(shown, band, imperialUnits, places) == reading.status
    }
}

/**
 * The verdict a reader reaches from the page: [shown] against the band's edges as they are printed
 * at [places]. The same order of questions as [LabMarker.status], asked of the printed figures.
 */
private fun LabMarker.printedStatus(shown: Double, band: LabBand, imperialUnits: Boolean, places: Int): LabStatus {
    fun over(edge: Double?) = edge != null && shown > insideTop(edge, imperialUnits, places)
    fun under(edge: Double?) = edge != null && shown < insideBottom(edge, imperialUnits, places)
    return when {
        over(band.veryHigh) -> LabStatus.FAR_ABOVE
        under(band.veryLow) -> LabStatus.FAR_BELOW
        over(band.high) -> LabStatus.ABOVE
        under(band.low) -> LabStatus.BELOW
        else -> LabStatus.NORMAL
    }
}

/**
 * The body behind each band a Reference row prints, where the band's own documentation above names
 * one; where it names one for only one of the edges the row prints, the words say which. A marker
 * missing here gets no name on the sheet rather than a guessed one, and the sheet's closing note
 * says that a name is printed only where the app records one.
 */
private val BAND_SOURCES = mapOf(
    LabMarker.GLUCOSE to R.string.labs_band_source_glucose,
    LabMarker.HBA1C to R.string.labs_band_source_hba1c,
    LabMarker.CHOLESTEROL to R.string.labs_band_source_cholesterol,
    LabMarker.LDL to R.string.labs_band_source_esc_eas,
    LabMarker.NON_HDL to R.string.labs_band_source_esc_eas,
    LabMarker.TRIGLYCERIDES to R.string.labs_band_source_triglycerides,
    LabMarker.EGFR to R.string.labs_band_source_egfr,
    LabMarker.ALT to R.string.labs_band_source_alt,
    LabMarker.HEMOGLOBIN to R.string.labs_band_source_hemoglobin,
    LabMarker.FERRITIN to R.string.labs_band_source_ferritin,
    LabMarker.TSAT to R.string.labs_band_source_tsat,
    LabMarker.CRP to R.string.labs_band_source_crp,
    LabMarker.VITAMIN_D to R.string.labs_band_source_vitamin_d,
    LabMarker.B12 to R.string.labs_band_source_b12,
    LabMarker.FOLATE to R.string.labs_band_source_folate,
    LabMarker.MAGNESIUM to R.string.labs_band_source_magnesium,
    // Its lines named in the unit KDIGO grades them in: on a mg/g sheet the edges print converted
    // (26.5 and 265.2), and a bare "KDIGO" beside them would read as though KDIGO had printed those.
    LabMarker.URINE_ACR to R.string.labs_band_source_kdigo_acr,
)

/**
 * The markers whose [BAND_SOURCES] line names a body for the ceiling alone, so that the advice about
 * a reading under the floor does not credit it with that floor. Ferritin's 150 and 200 are WHO
 * 2020's; its 30 is iron deficiency in practice, and only the 15 under it WHO's definition, which the
 * generic line's "WHO" still covers.
 */
private val CEILING_ONLY_SOURCES = setOf(LabMarker.FERRITIN)

/**
 * Why a figure of this marker has no band beside it, given that it has none: [LabMarker.bandFor]
 * withholds one for exactly two reasons, and a number with no verdict and no reason invites the
 * reader to supply a verdict of their own.
 */
val LabMarker.withoutBandRes: Int
    get() = if (ungraded) noteRes ?: R.string.labs_note_ungraded else R.string.labs_note_adult_band

/**
 * "Fasting sample" or "After food" where that is part of what the figure means, and null where it
 * is not. It decides the band of a glucose or a triglyceride, and an insulin is a fasting figure or
 * nothing — [derivedLabs] takes HOMA-IR only from one drawn fasting. On any other marker food moves
 * nothing, and the words repeated on every row of a panel would only bury the verdicts.
 */
val LabReading.fastingRes: Int?
    get() = (if (fasting) R.string.labs_fasting else R.string.labs_fed)
        .takeIf { marker.fedBand != null || marker == LabMarker.INSULIN }

/** Who published the band [LabMarker.bandFor] gives, or null where nobody is recorded. */
val LabMarker.bandSourceRes: Int? get() = BAND_SOURCES[this]

/**
 * Whether the band [LabMarker.bandFor] gives at [ageYears] is an adult one read over somebody
 * younger — glucose and HbA1c under 18, see [LabMarker.bandWithheldAt] — so that the row naming
 * its source can say so.
 */
fun LabMarker.adultBandAt(ageYears: Int): Boolean = this in YOUTH_GLYCAEMIA && ageYears < ADULT_LAB_AGE

/** Whether the band has an upper edge at all — a ceiling to be over. */
val LabBand.hasCeiling: Boolean get() = (high ?: veryHigh) != null

/** Whether the band has a lower edge at all — a floor to be under. */
val LabBand.hasFloor: Boolean get() = (low ?: veryLow) != null

/**
 * Whether the band closed both sides, and so whether its NORMAL is a pass or half of one.
 *
 * An absent edge is silence about that side. A cholesterol under its ceiling has not passed a
 * floor nobody published, and the screen paints, counts and speaks that NORMAL as the half-answer
 * it is — the same question the vitals card asks of its own bands.
 */
val LabBand.twoEdged: Boolean get() = hasFloor && hasCeiling

data class LabReading(
    val marker: LabMarker,
    val value: Double,
    val occurredAt: Long,
    val day: String,
    /** Whether the sample was drawn fasting. Unknown counts as not fasting — see [labHistory]. */
    val fasting: Boolean,
    /** How old the person was on the day of the draw, from the birth year on file. */
    val ageYears: Int,
    /** Where the reading fell, or null for a sample with no band to fall either side of. */
    val status: LabStatus?,
    /** What the sample was, where the sheet said. Null is "not recorded", never "probably serum". */
    val specimen: LabSpecimen? = null,
    /** The analyser or laboratory, as it was typed. Empty where none was given. */
    val method: String = "",
    /** The range the laboratory printed beside this result, which is not the app's own band. */
    val reported: ReportedRange? = null,
    /**
     * The journal row this reading was read out of, which is also when it was written down.
     *
     * Row ids are handed out in the order rows are inserted, and that is the only order that says
     * when the app learned something: [occurredAt] is the day of the draw, and a sheet from March
     * typed in September is an old date arriving new. The bell counts on this — see [labNotices].
     */
    val entryId: Long = 0L,
    /**
     * Whether it came off a laboratory panel — a [KIND_LABS] row, typed in from the sheet — rather
     * than off a glucose meter or out of Health Connect. The results page charts both; the doctor's
     * sheet prints only panels as laboratory results, because a fingerstick is not one.
     */
    val fromPanel: Boolean = true,
    /** Who wrote the row, as the journal names it ([JournalEntry.measurementSource]). */
    val source: String? = null,
) {
    /**
     * Whether the reading fell outside a band it was actually read against. A reading with no band
     * is neither inside nor outside, and every count and colour on the screen has to leave it out
     * of both rather than pass it as normal.
     */
    val outside: Boolean get() = status != null && status != LabStatus.NORMAL
}

/** A result stays a finding for half a year; after that it is history, not something to act on. */
const val LABS_WINDOW_DAYS = 180

/**
 * WHO 2015 (Guideline: sugars intake for adults and children): free sugars under 10% of energy, with
 * a conditional recommendation of 5%. The figure usually quoted, 50 g, is that share of a 2000 kcal
 * day at 4 kcal per gram; on the 1200–1500 kcal a losing budget can set, the same share is 30–37 g.
 */
const val SUGAR_ENERGY_SHARE = 0.10
const val SUGAR_KCAL_PER_G = 4.0
const val SUGAR_REFERENCE_KCAL = 2_000.0
const val SUGAR_WINDOW_DAYS = 14
const val SUGAR_MIN_LOGGED_DAYS = 4

/**
 * Grams of free sugar a day the WHO share comes to on this person's energy target, or on the 2000
 * kcal day the 50 g figure is quoted for where the target is missing or nonsense.
 */
fun sugarReferenceG(targetKcal: Double?): Double =
    (targetKcal?.takeIf { it > 0.0 } ?: SUGAR_REFERENCE_KCAL) * SUGAR_ENERGY_SHARE / SUGAR_KCAL_PER_G

/** EFSA/WHO: 25 g of fibre a day, and saturated fat under 10% of energy. */
const val FIBER_REFERENCE_G = 25.0
const val SATURATED_FAT_SHARE = 0.10

/** Above this a diet is high-protein enough to move creatinine on its own. */
const val HIGH_PROTEIN_G_PER_KG = 2.0

/** How long a hard session keeps creatinine and the transaminases up. */
const val EXERTION_HOURS_BEFORE = 48L
const val EXERTION_MET_MINUTES = 400.0

/** Days before the draw whose water intake is read, and the share of target that counts as low. */
const val HYDRATION_DAYS_BEFORE = 3
const val LOW_HYDRATION_SHARE = 0.6

/**
 * Every reading the journal holds, per marker, oldest first.
 *
 * Whoever wrote it — this screen, a Bluetooth meter, or Health Connect, which imports blood glucose
 * under the very key [LabMarker.GLUCOSE] uses. Each reading is read against the band that applied at
 * the age of the draw, so a panel from four years ago is judged by the person who gave it.
 *
 * A payload with no word on fasting is read as not fasting. That is the honest default for a row
 * that says nothing: the fasting bands would turn an ordinary reading after lunch into a finding.
 * But a meter that was told "fasting" says so in its GLS context, and a Health Connect import
 * carries the record's relation to a meal, and both are read as the flag — see [labFasting].
 *
 * The meals around the draw are consulted only for a payload that says nothing — see
 * [inferredFasting]. A panel off the form always answers, and "not fasting" typed on it is an answer:
 * a dinner logged the evening before does not overrule it, which would grade a sample drawn after
 * breakfast against the fasting bands and compute a HOMA-IR out of it.
 *
 * Hand it every meal up to the draw, not a window cut to start on the day of it: the evening before
 * is the evidence the inference reads.
 *
 * One JSON parse per candidate entry, and the cheap substring test first: this runs over the whole
 * journal inside composition, and a walk per marker per row is not free at a few thousand rows.
 *
 * Two panels of one day share their noon ([dayInstant]), and the row written later is the newer of
 * them: ordered by the time alone, which of the two is "the newest" would be the order SQLite hands
 * them over in.
 */
fun labHistory(entries: List<JournalEntry>, profile: UserProfile): Map<LabMarker, List<LabReading>> {
    val history = mutableMapOf<LabMarker, MutableList<LabReading>>()
    val panelsOfDay = mutableMapOf<String, MutableList<Map<LabMarker, LabReading>>>()
    // Read once rather than per panel: [inferredFasting] walks it for every sheet, and the journal
    // handed in here is the whole of it.
    val mealTimes = entries.filter { it.kind == KIND_EAT }.map(JournalEntry::occurredAt)
    entries.sortedWith(compareBy(JournalEntry::occurredAt, JournalEntry::id)).forEach { entry ->
        val payload = entry.measurementPayloadJson ?: return@forEach
        if (LabMarker.entries.none { it.key in payload } && GLUCOSE_MG_DL_KEY !in payload) return@forEach
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
        val fasting = json.labFasting() || (!json.statesFasting() && inferredFasting(entry.occurredAt, mealTimes))
        val typed = typedReadings(entry, json, profile, fasting)
        (typed.values + derivedLabs(typed, profile)).forEach {
            history.getOrPut(it.marker) { mutableListOf() }.add(it)
        }
        if (entry.kind == KIND_LABS) panelsOfDay.getOrPut(entry.day) { mutableListOf() }.add(typed)
    }
    panelsOfDay.values.flatMap { derivedAcrossPanels(it, profile) }.forEach {
        history.getOrPut(it.marker) { mutableListOf() }.add(it)
    }
    return LabMarker.entries
        .mapNotNull { marker -> history[marker]?.let { marker to it.sortedWith(READING_ORDER) } }
        .toMap()
}

/** Oldest first, and of two drawn the same day the one written down later last: see [labHistory]. */
private val READING_ORDER = compareBy<LabReading>(LabReading::occurredAt, LabReading::entryId)

/** The figures [entry]'s payload [json] gives itself, each read against its band at the age of the draw. */
private fun typedReadings(
    entry: JournalEntry,
    json: JSONObject,
    profile: UserProfile,
    fasting: Boolean,
): Map<LabMarker, LabReading> {
    val specimen = labSpecimenOf(json.optString(SPECIMEN_KEY).takeIf(String::isNotBlank))
    val method = json.optString(METHOD_KEY)
    val reported = json.reportedRanges()
    val age = ageOn(entry.day, profile.birthDate)
    val typed = mutableMapOf<LabMarker, LabReading>()
    LabMarker.entries.forEach markers@{ marker ->
        if (!marker.typedOn(specimen)) return@markers
        val value = (json.optDouble(marker.key).takeIf { !it.isNaN() } ?: mgDlGlucose(marker, json))
            ?.takeIf { it in marker.valid }
            ?: return@markers
        typed[marker] = LabReading(
            marker = marker,
            entryId = entry.id,
            value = value,
            occurredAt = entry.occurredAt,
            day = entry.day,
            fasting = fasting,
            ageYears = age,
            status = marker.status(value, profile.sex, age, fasting),
            specimen = specimen,
            method = method,
            reported = reported[marker],
            fromPanel = entry.kind == KIND_LABS,
            source = entry.measurementSource,
        )
    }
    return typed
}

/**
 * The figures that follow from one day's panels taken together, where none of the panels gave them
 * on its own. A sheet saved again writes only what the day's panels lack ([unsavedLabValues]), so one
 * visit can lie in two rows — the total cholesterol in the first, the HDL in the second — and read
 * row by row, its non-HDL would be nowhere. Only a figure read once that day is used: two of one marker are two
 * draws, and which of them the other half belongs to is not known.
 */
private fun derivedAcrossPanels(panels: List<Map<LabMarker, LabReading>>, profile: UserProfile): List<LabReading> {
    if (panels.size < 2) return emptyList()
    val own = panels.flatMap { derivedLabs(it, profile) }.map(LabReading::marker).toSet()
    val once = panels.flatMap { it.values }.groupBy(LabReading::marker)
        .mapNotNull { (_, readings) -> readings.singleOrNull() }
        .associateBy(LabReading::marker)
    return derivedLabs(once, profile).filter { it.marker !in own }
}

/**
 * A meter that reports a mass concentration — the mg/dL half of the market — fills only its own
 * field, so glucose alone is also read back through the factor [JournalEntry.glucoseMmolL] uses.
 */
private fun mgDlGlucose(marker: LabMarker, json: JSONObject): Double? =
    json.optDouble(GLUCOSE_MG_DL_KEY)
        .takeIf { marker == LabMarker.GLUCOSE && !it.isNaN() }
        ?.div(MG_DL_PER_MMOL_L)

/** The key a mass-concentration meter writes, and the mg/dL each mmol/L is worth. */
private const val GLUCOSE_MG_DL_KEY = "concentrationMgDl"
private const val MG_DL_PER_MMOL_L = 18.016

/**
 * The blood tests a doctor's sheet is built from: [labHistory] over every row up to the end of its
 * period, and the day the period starts.
 *
 * Not cut to the period the way the vitals are. A panel is drawn once a year or two, and the one
 * before is what the new one is read against: cut to the window, last year's panel would reach no
 * sheet however long, and a quarter after a spring draw would print "no data" over two panels on the
 * phone.
 * [from] is kept so that a result from before the period can say so on the row that prints it.
 *
 * A map still, so it reads as the history it is wherever the report reaches for it.
 */
class ReportLabs(val from: LocalDate, history: Map<LabMarker, List<LabReading>>) :
    Map<LabMarker, List<LabReading>> by history

/** The last reading of every marker — what the advice rules and the summary read. */
fun latestLabs(entries: List<JournalEntry>, profile: UserProfile): List<LabReading> =
    labHistory(entries, profile).values.mapNotNull(List<LabReading>::lastOrNull)

/**
 * The figures that follow from the ones typed on the same sheet, so nobody enters a number a formula
 * already knows.
 *
 * Derived within one draw rather than across the newest of each: a filtration rate wants the
 * creatinine of its own morning, and a non-HDL made of a total from March and an HDL from October is
 * a number that never existed. One draw is one panel, or one day's panels where they cannot be two
 * draws ([derivedAcrossPanels]).
 */
internal fun derivedLabs(
    typed: Map<LabMarker, LabReading>,
    profile: UserProfile,
): List<LabReading> = listOfNotNull(
    // A child's creatinine is left as the raw figure rather than run through an adult equation: a
    // rate derived from an age nobody was is a made-up number, not a cautious one.
    typed[LabMarker.CREATININE]?.takeIf { it.ageYears >= CKD_EPI_MIN_AGE }?.let { creatinine ->
        creatinine.derive(
            LabMarker.EGFR,
            ckdEpi(creatinine.value, creatinine.ageYears, profile.sex),
            profile,
        )
    },
    typed[LabMarker.CHOLESTEROL]?.let { total ->
        typed[LabMarker.HDL]?.let { hdl ->
            total.derive(LabMarker.NON_HDL, total.value - hdl.value, profile)
        }
    },
    typed[LabMarker.GLUCOSE]?.takeIf { it.fasting }?.let { glucose ->
        typed[LabMarker.INSULIN]?.takeIf { it.fasting }?.let { insulin ->
            glucose.derive(LabMarker.HOMA_IR, glucose.value * insulin.value / 22.5, profile)
        }
    },
)

/**
 * Whether the newest creatinine on file was drawn at an age CKD-EPI is not published for.
 *
 * [derivedLabs] deliberately leaves the rate out there rather than inventing one from an adult
 * equation, and an absent row says nothing about why; the screen turns this into a line that does.
 */
internal fun egfrNotCalculated(history: Map<LabMarker, List<LabReading>>): Boolean =
    history[LabMarker.CREATININE]?.lastOrNull()?.egfrWithheld == true

/** Whether this is a creatinine drawn too young for CKD-EPI, so no rate was derived from it. */
internal val LabReading.egfrWithheld: Boolean
    get() = marker == LabMarker.CREATININE && ageYears < CKD_EPI_MIN_AGE

/**
 * A figure the app worked out from [this] reading, keeping its day, sample and fasting state.
 *
 * Not the laboratory's range or its analyser: those belong to the analyte that was run, and a
 * filtration rate carrying the creatinine's printed range would show it beside itself as "the
 * laboratory's own", in µmol/L, on the sheet and in the exported file alike. The sample
 * stays, because an eGFR from a serum creatinine is a serum figure and its code is chosen by it.
 */
private fun LabReading.derive(marker: LabMarker, value: Double, profile: UserProfile): LabReading? =
    value.takeIf { it in marker.valid }?.let {
        copy(
            marker = marker,
            value = it,
            status = marker.status(it, profile.sex, ageYears, fasting),
            reported = null,
            method = "",
        )
    }

/**
 * CKD-EPI 2021, the race-free equation, on creatinine in µmol/L.
 *
 * The 2021 revision is used rather than the 2009 one it replaced because the older equation carries
 * a race coefficient that has since been withdrawn on both scientific and clinical grounds.
 */
fun ckdEpi(creatinineUmolL: Double, ageYears: Int, sex: String): Double {
    val female = sex == "FEMALE"
    val scr = creatinineUmolL / CREATININE_UMOL_PER_MGDL
    val kappa = if (female) 0.7 else 0.9
    val alpha = if (female) -0.241 else -0.302
    val ratio = scr / kappa
    return 142.0 *
        minOf(ratio, 1.0).pow(alpha) *
        maxOf(ratio, 1.0).pow(-1.200) *
        0.9938.pow(ageYears.toDouble()) *
        if (female) 1.012 else 1.0
}

/**
 * The single most pressing reading that fell outside its band.
 *
 * No condition is named and no marker is interpreted: the advice says which number left which
 * published band and by how much, which is the most an app holding one result can honestly say.
 */
fun labsAdvice(
    readings: List<LabReading>,
    sex: String,
    imperial: Boolean,
    today: LocalDate,
): Advice? {
    val worst = readings
        .filter { it.outside && it.isRecent(today) }
        .maxByOrNull { it.status?.ordinal ?: 0 }
        ?: return null
    val status = worst.status ?: return null
    val band = worst.marker.bandFor(sex, worst.ageYears, worst.fasting) ?: return null
    val places = worst.marker.printPlaces(listOf(worst), sex, imperial)
    val far = status in setOf(LabStatus.FAR_ABOVE, LabStatus.FAR_BELOW)
    val below = status in setOf(LabStatus.BELOW, LabStatus.FAR_BELOW)
    return Advice(
        topic = AdviceTopic.LABS,
        level = if (far) AdviceLevel.CLINICIAN else AdviceLevel.ATTENTION,
        titleRes = if (far) R.string.advice_labs_clinician_title else R.string.advice_labs_title,
        bodyRes = when {
            far && below -> R.string.advice_labs_far_below_body

            far -> R.string.advice_labs_very_high_body

            below -> R.string.advice_labs_low_body

            // The blood line ends on a fasting sample, which a urine ratio never needed. What KDIGO
            // 2024 asks of a raised one is a first morning sample to confirm it (PP 1.3.1.2), and
            // its Table 16 names what raises it for a while.
            worst.marker.panel.sample == LabSample.URINE -> R.string.advice_labs_urine_high_body

            else -> R.string.advice_labs_high_body
        },
        args = listOf(
            Labeled(worst.marker.titleRes),
            PrintedFigure(worst.marker.printed(worst, sex, imperial, places)),
            Labeled(worst.marker.unitLabelRes(imperial)),
            PrintedFigure(worst.marker.printedEdgeBeside(band, below, imperial, places)),
        ),
        sourceRes = worst.marker.adviceSourceRes(below),
        windowDays = worst.daysSince(today),
        id = "labs-${worst.marker.name.lowercase()}-${status.name.lowercase()}",
    )
}

/**
 * The body behind this marker's band where one is recorded: the generic line names WHO, ADA and
 * ESC/EAS, and it was NICE, the IOM and ACG that wrote the B12, vitamin D and ALT bands. Not where
 * that body wrote only the ceiling and the reading is under the floor — see [CEILING_ONLY_SOURCES].
 */
private fun LabMarker.adviceSourceRes(below: Boolean): Int =
    bandSourceRes?.takeUnless { below && this in CEILING_ONLY_SOURCES } ?: R.string.advice_source_labs

/**
 * Where the band a reading's row prints starts, for a reading under it, or ends, for one over it —
 * as the row prints it, which is what the advice's words name.
 *
 * Not the raw edge, which is the first value outside: "a band that ends at 5.7" would name an HbA1c
 * the row colours red beside "up to 5.6", and ALT's 33.001 and B12's 258.5 would be printed as they
 * are stored. Nor the clinician line for a far reading, which the words would then call the place
 * the reference band ends.
 */
private fun LabMarker.printedEdgeBeside(band: LabBand, below: Boolean, imperial: Boolean, places: Int): String =
    printedEdges(band, imperial, places).let { (floor, ceiling) -> if (below) floor else ceiling }.orEmpty()

/**
 * Sweet food against the markers sugar actually moves: blood glucose, HbA1c, triglycerides and
 * urate, where fructose is a documented driver.
 *
 * Only days with sugar actually recorded count. A meal logged without its sugar column is unknown,
 * not zero, and averaging those in would invent a diet nobody ate. The diary also counts every
 * sugar, including the ones in fruit and milk, while the WHO figure is about free sugars — the copy
 * says so, because a threshold that is quietly measuring something else is worse than none.
 */
// One early return per reason the record cannot support a statement yet.
fun sugarLabAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    imperial: Boolean,
    today: LocalDate,
    /** The day's energy target, which the WHO share is a share of. */
    targetKcal: Double?,
): Advice? {
    val raised = readings.raisedAmong(SUGAR_SENSITIVE, today) ?: return null
    val perDay = journal.dailyNutrient(today, SUGAR_WINDOW_DAYS) { it.sugarG }
    if (perDay.size < SUGAR_MIN_LOGGED_DAYS) return null

    val mean = perDay.values.average()
    val reference = sugarReferenceG(targetKcal)
    if (mean < reference) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_sugar_title,
        bodyRes = R.string.advice_labs_sugar_body,
        args = listOf(
            Counted(perDay.size, R.plurals.count_days),
            mean.roundToInt().toString(),
            reference.roundToInt().toString(),
            Labeled(raised.marker.titleRes),
            PrintedFigure(raised.marker.printed(raised.value, imperial)),
            Labeled(raised.marker.unitLabelRes(imperial)),
        ),
        sourceRes = R.string.advice_source_sugar,
        windowDays = SUGAR_WINDOW_DAYS,
        id = "labs-sugar",
    )
}

/**
 * The markers movement moves, against the week that was actually walked.
 *
 * Deliberately narrow: walking shifts glucose handling and HDL, and it does not shift creatinine,
 * TSH or ferritin. Pretending otherwise would make every lab result a step-count lecture.
 */
fun activityLabAdvice(
    readings: List<LabReading>,
    days: List<DaySteps>,
    profile: UserProfile,
    currentYear: Int,
    today: LocalDate,
): Advice? {
    val marker = readings.firstOrNull {
        it.marker in MOVEMENT_SENSITIVE && it.outside && it.isRecent(today)
    } ?: return null
    val age = (currentYear - profile.birthYear).coerceIn(0, 120)
    val reference = if (age >= 60) 6_000L else 7_000L
    val week = days.filter {
        it.day.toLocalDateOrNull()
            ?.let { d -> d in today.minusDays(ACTIVITY_WINDOW_DAYS - 1L)..today } == true
    }
    if (week.size < ACTIVITY_WINDOW_DAYS) return null
    val mean = week.sumOf(DaySteps::steps) / week.size
    if (mean >= reference) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.HABIT,
        titleRes = R.string.advice_labs_activity_title,
        bodyRes = R.string.advice_labs_activity_body,
        args = listOf(Labeled(marker.marker.titleRes), mean.formatted(), reference.formatted()),
        sourceRes = R.string.advice_source_labs_activity,
        windowDays = ACTIVITY_WINDOW_DAYS,
        id = "labs-activity",
    )
}

/**
 * A raised liver enzyme next to the body it sits in.
 *
 * This is the one place in the labs section where losing weight is named as a treatment, because it
 * is the one place the evidence supports it: 7–10% of body mass moves the transaminases of fatty
 * liver more reliably than anything else that can be recommended by an app.
 */
fun weightLiverAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
): Advice? {
    val enzyme = readings.raisedAmong(LIVER_ENZYMES, today) ?: return null
    // Both yardsticks below are adult ones — WHO's BMI cut-points and AACE's body-fat cut-offs — and
    // a child's BMI is a percentile of age and sex this app does not hold. A raised enzyme in a
    // fourteen-year-old at a BMI of 24.5 is not "extra weight"; it is a reading with no advice here.
    val age = ageOn(today.toString(), profile.birthDate)
    if (age < ADULT_BMI_AGE) return null
    val bodyMass = bmi(profile.weightKg, profile.heightCm)
    val bodyFat = journal.filter { it.bodyFatPercent != null }.maxByOrNull { it.occurredAt }
        ?.bodyFatPercent
    // The same AACE band the vitals card grades body fat against — above 25% for men, 35% for women
    // — rather than a second pair of figures kept here.
    val fatOverBand = bodyFat?.let { Vital.BODY_FAT.status(it, null, profile.sex, age) }
    val fatty = bodyMass >= OVERWEIGHT_BMI || fatOverBand in setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE)
    if (!fatty) return null
    // In the unit the profile weighs in: "lose about 7 kg" means little to somebody whose scale reads pounds.
    val toLose = profile.weightKg * LIVER_WEIGHT_LOSS_SHARE
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_liver_title,
        bodyRes = R.string.advice_labs_liver_body,
        args = listOf(
            Labeled(enzyme.marker.titleRes),
            Decimal(bodyMass),
            (if (profile.imperial) imperialWeight(toLose) else toLose).roundToInt().toString(),
            Labeled(if (profile.imperial) R.string.unit_lb else R.string.unit_kg),
        ),
        sourceRes = R.string.advice_source_labs_liver,
        windowDays = enzyme.daysSince(today),
        id = "labs-liver-weight",
    )
}

/**
 * Alcohol against the four markers it moves first.
 *
 * Named rather than hinted at: a raised GGT with a bottle of wine a night in the diary is the most
 * ordinary explanation there is, and an app that lists everything except the obvious is useless.
 */
fun alcoholLabAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    today: LocalDate,
): Advice? {
    val marker = readings.raisedAmong(ALCOHOL_SENSITIVE, today) ?: return null
    val week = habitWeek(journal, today)
    if (week.ethanolG < ALCOHOL_WEEKLY_G) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_alcohol_title,
        bodyRes = R.string.advice_labs_alcohol_body,
        args = listOf(
            Labeled(marker.marker.titleRes),
            (week.ethanolG / STANDARD_DRINK_G).roundToInt().toString(),
            (ALCOHOL_WEEKLY_G / STANDARD_DRINK_G).roundToInt().toString(),
        ),
        sourceRes = R.string.advice_source_labs_alcohol,
        windowDays = HABIT_WINDOW_DAYS,
        id = "labs-alcohol",
    )
}

/**
 * What the days around the draw were doing to the sample.
 *
 * Creatinine and the transaminases are not only reporting an organ: a heavy session in the two days
 * before, a very high-protein stretch, or three dry days all lift them on their own. Saying so is
 * the difference between a useful prompt to re-test and a fortnight of unnecessary worry.
 */
fun sampleContextAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
): Advice? {
    val marker = readings
        .filter { it.marker in DRAW_SENSITIVE && it.outside && it.isRecent(today) }
        .maxByOrNull { it.status?.ordinal ?: 0 }
        ?: return null
    val drawn = marker.day.toLocalDateOrNull() ?: return null

    val exertion = journal
        .filter { it.kind == KIND_ACTIVITY }
        .filter { it.occurredAt in (marker.occurredAt - EXERTION_HOURS_BEFORE * 3_600_000L)..marker.occurredAt }
        .sumOf { (it.met ?: 0.0) * (it.durationMinutes ?: 0.0) }
    val proteinPerKg = journal.dailyNutrient(drawn, HYDRATION_DAYS_BEFORE) { it.proteinG }
        .values.averageOrNull()
        ?.div(profile.weightKg.coerceAtLeast(1.0))
    val waterShare = journal.dailyWaterMl(drawn, HYDRATION_DAYS_BEFORE)
        .values.averageOrNull()
        ?.div(waterTargetMl(profile.weightKg).coerceAtLeast(1))

    val reason = when {
        exertion >= EXERTION_MET_MINUTES -> R.string.advice_labs_context_exertion
        (proteinPerKg ?: 0.0) >= HIGH_PROTEIN_G_PER_KG -> R.string.advice_labs_context_protein
        waterShare != null && waterShare < LOW_HYDRATION_SHARE -> R.string.advice_labs_context_water
        else -> return null
    }
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.HABIT,
        titleRes = R.string.advice_labs_context_title,
        bodyRes = R.string.advice_labs_context_body,
        args = listOf(Labeled(marker.marker.titleRes), Labeled(reason)),
        sourceRes = R.string.advice_source_labs_context,
        windowDays = marker.daysSince(today),
        id = "labs-context-${marker.marker.name.lowercase()}",
    )
}

/**
 * Low iron stores next to a recorded cycle.
 *
 * The two halves of this only exist together in an app that holds both, which is the whole reason
 * to say it: menstrual loss is the commonest cause of iron deficiency in women who have periods,
 * and it is the one a lab result alone does not mention.
 */
fun ferritinCycleAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
): Advice? {
    if (profile.sex != "FEMALE") return null
    val iron = readings.firstOrNull {
        it.marker in setOf(LabMarker.FERRITIN, LabMarker.HEMOGLOBIN) &&
            it.status in setOf(LabStatus.BELOW, LabStatus.FAR_BELOW) &&
            it.isRecent(today)
    } ?: return null
    val cycles = journal
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .filter { it >= today.minusDays(LABS_WINDOW_DAYS.toLong()) }
        .distinct()
    if (cycles.size < 2) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_iron_title,
        bodyRes = R.string.advice_labs_iron_body,
        args = listOf(Labeled(iron.marker.titleRes), Counted(cycles.size, R.plurals.count_cycles)),
        sourceRes = R.string.advice_source_labs_iron,
        windowDays = LABS_WINDOW_DAYS,
        id = "labs-iron-cycle",
    )
}

/**
 * The two dietary levers that actually move LDL, and only when the diary holds them.
 *
 * Dietary cholesterol is deliberately not one of them: the diary has a column for it, and the
 * evidence that it moves blood cholesterol in most people is weak enough that naming it would be
 * inventing a finding out of an available number.
 */
fun lipidDietAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    today: LocalDate,
): Advice? {
    readings.raisedAmong(setOf(LabMarker.LDL, LabMarker.NON_HDL, LabMarker.CHOLESTEROL), today)
        ?: return null
    val saturated = journal.dailyNutrient(today, SUGAR_WINDOW_DAYS) { it.saturatedFatG }
    val energy = journal.dailyNutrient(today, SUGAR_WINDOW_DAYS) { it.calories }
    val fiber = journal.dailyNutrient(today, SUGAR_WINDOW_DAYS) { it.fiberG }

    val saturatedShare = saturated.values.averageOrNull()?.let { grams ->
        energy.values.averageOrNull()?.takeIf { it > 0 }?.let { kcal -> grams * 9.0 / kcal }
    }
    val fiberMean = fiber.values.averageOrNull().takeIf { fiber.size >= SUGAR_MIN_LOGGED_DAYS }
    return when {
        saturated.size >= SUGAR_MIN_LOGGED_DAYS && (saturatedShare ?: 0.0) > SATURATED_FAT_SHARE ->
            lipidAdvice(
                R.string.advice_labs_saturated_body,
                listOf(((saturatedShare ?: 0.0) * 100).roundToInt()),
                "labs-saturated-fat",
            )

        fiberMean != null && fiberMean < FIBER_REFERENCE_G -> lipidAdvice(
            R.string.advice_labs_fiber_body,
            listOf(fiberMean.roundToInt().toString(), FIBER_REFERENCE_G.pretty()),
            "labs-fiber",
        )

        else -> null
    }
}

private fun lipidAdvice(bodyRes: Int, args: List<Any>, id: String) = Advice(
    topic = AdviceTopic.LABS,
    level = AdviceLevel.HABIT,
    titleRes = R.string.advice_labs_diet_title,
    bodyRes = bodyRes,
    args = args,
    sourceRes = R.string.advice_source_labs_diet,
    windowDays = SUGAR_WINDOW_DAYS,
    id = id,
)

/**
 * Everything the labs section can say, most pressing first.
 *
 * One entry point so the screen and the health section cannot drift apart: a rule added here shows
 * up in both, and in the reminder notification, without being wired three times.
 */
fun labsSectionAdvice(
    readings: List<LabReading>,
    journal: List<JournalEntry>,
    days: List<DaySteps>,
    profile: UserProfile,
    currentYear: Int,
    today: LocalDate,
): List<Advice> {
    val imperial = profile.imperial
    return listOfNotNull(
        labsAdvice(readings, profile.sex, imperial, today),
        sugarLabAdvice(readings, journal, imperial, today, energyBudget(profile, currentYear).targetKcal),
        alcoholLabAdvice(readings, journal, today).takeIf { habitsOffered(profile.birthDate, today) },
        weightLiverAdvice(readings, journal, profile, today),
        sampleContextAdvice(readings, journal, profile, today),
        ferritinCycleAdvice(readings, journal, profile, today),
        lipidDietAdvice(readings, journal, today),
        activityLabAdvice(readings, days, profile, currentYear, today),
        triglyceridesUrgentAdvice(readings, imperial, today),
        ferritinContextAdvice(readings, imperial, today),
        hba1cReliabilityAdvice(readings, imperial, today),
    ).sortedByDescending { it.level.ordinal }
}

/**
 * The triglyceride figure that belongs in a different conversation from the one the band is having.
 *
 * The Endocrine Society's 2012 guideline puts 150-999 mg/dL in the cardiovascular register and
 * anything at or above 1000 mg/dL — 11.3 mmol/L — in another one entirely. Our band tops out at
 * 5.6, so without this a figure three times that would be announced in the same words as a mildly
 * raised one, which is the wrong register rather than the wrong number.
 *
 * No condition is named here and no threshold is quoted at the reader: the sentence says the figure
 * is far past the band and that it is worth taking to somebody soon. That is the whole of it.
 */
const val TRIGLYCERIDES_URGENT_MMOL = 11.3

fun triglyceridesUrgentAdvice(readings: List<LabReading>, imperial: Boolean, today: LocalDate): Advice? {
    val reading = readings
        .filter { it.marker == LabMarker.TRIGLYCERIDES && it.value >= TRIGLYCERIDES_URGENT_MMOL }
        .filter { it.isRecent(today) }
        .maxByOrNull(LabReading::occurredAt)
        ?: return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.CLINICIAN,
        titleRes = R.string.advice_labs_tg_urgent_title,
        bodyRes = R.string.advice_labs_tg_urgent_body,
        args = listOf(
            PrintedFigure(reading.marker.printed(reading.value, imperial)),
            Labeled(reading.marker.unitLabelRes(imperial)),
        ),
        sourceRes = R.string.advice_source_labs,
        windowDays = reading.daysSince(today),
        id = "labs-tg-urgent",
    )
}

/**
 * The [marker] that came off the same sheet as [reading], which is the only footing a cross-panel
 * remark has: its own panel's, or else the one such figure among the day's other panels. A sheet
 * saved again writes only what the day's panels lack ([unsavedLabValues]), so one visit can lie in
 * two rows; two of the marker that day are two draws, and neither is said to be this one's.
 */
private fun List<LabReading>.onSheet(reading: LabReading, marker: LabMarker): LabReading? =
    firstOrNull { it.marker == marker && it.entryId == reading.entryId && it.entryId != 0L }
        ?: filter { it.marker == marker && it.fromPanel && reading.fromPanel && it.day == reading.day }
            .singleOrNull()

/**
 * Ferritin cannot be read while something is inflamed, and a ferritin the laboratory printed as
 * normal can still be low when the person is anaemic. Two remarks, one marker, both cross-panel.
 *
 * WHO 2020, verbatim: "In individuals with infection or inflammation, a ferritin concentration
 * below 30 µg/L in children and 70 µg/L in adults may be used to indicate iron deficiency." So a
 * raised CRP on the same sheet does not make the ferritin high or low — it makes it unreadable, and
 * saying "normal" over the top of that is the one answer that is certainly wrong. WHO asks for CRP
 * *and* AGP; only CRP is held here, which the copy says.
 *
 * AGA's 2020 technical review puts the cutoff at 45 ng/mL against bone-marrow iron — sensitivity
 * 0.85, specificity 0.92, against 0.59 and 0.99 at 15 — so a ferritin of 30 to 44 beside a low
 * haemoglobin is the classic miss, and it is one the app can see because both figures are on the
 * same sheet.
 */
@Suppress("ReturnCount")
fun ferritinContextAdvice(readings: List<LabReading>, imperial: Boolean, today: LocalDate): Advice? {
    val ferritin = readings
        .filter { it.marker == LabMarker.FERRITIN && it.isRecent(today) }
        .maxByOrNull(LabReading::occurredAt)
        ?: return null
    val crp = readings.onSheet(ferritin, LabMarker.CRP)
    if (crp != null && crp.status in setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE)) {
        return Advice(
            topic = AdviceTopic.LABS,
            level = AdviceLevel.ATTENTION,
            titleRes = R.string.advice_labs_ferritin_crp_title,
            bodyRes = R.string.advice_labs_ferritin_crp_body,
            args = listOf(
                PrintedFigure(ferritin.marker.printed(ferritin.value, imperial)),
                PrintedFigure(crp.marker.printed(crp.value, imperial)),
            ),
            sourceRes = R.string.advice_source_ferritin_context,
            windowDays = ferritin.daysSince(today),
            id = "labs-ferritin-crp",
        )
    }
    val haemoglobin = readings.onSheet(ferritin, LabMarker.HEMOGLOBIN) ?: return null
    if (haemoglobin.status !in setOf(LabStatus.BELOW, LabStatus.FAR_BELOW)) return null
    if (ferritin.value !in FERRITIN_ANAEMIA_LOW..FERRITIN_ANAEMIA_HIGH) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_ferritin_anaemia_title,
        bodyRes = R.string.advice_labs_ferritin_anaemia_body,
        args = listOf(
            PrintedFigure(ferritin.marker.printed(ferritin.value, imperial)),
            PrintedFigure(haemoglobin.marker.printed(haemoglobin.value, imperial)),
            Labeled(haemoglobin.marker.unitLabelRes(imperial)),
        ),
        sourceRes = R.string.advice_source_ferritin_context,
        windowDays = ferritin.daysSince(today),
        id = "labs-ferritin-anaemia",
    )
}

/**
 * The window in which a ferritin the laboratory called normal is worth raising anyway.
 *
 * The floor is our own band's, so this says nothing about a ferritin already marked low; the
 * ceiling is AGA's 45.
 */
private const val FERRITIN_ANAEMIA_LOW = 30.0
private const val FERRITIN_ANAEMIA_HIGH = 45.0

/** Below this the guidance stops treating HbA1c as a reading about sugar. */
private const val EGFR_A1C_UNRELIABLE = 30.0

/**
 * HbA1c is a figure about red cells as much as about sugar, and two things on the same sheet can
 * make it say less than it appears to.
 *
 * ADA's Standards of Care recommendation 2.4 lists the conditions that disturb the relationship
 * between HbA1c and mean glucose — among them anaemia and iron status — and KDIGO 2022 practice
 * point 2.1.2 says the same of advanced kidney disease. The row is caveated rather than hidden: a
 * result that vanishes leaves the person wondering, and the honest move is to point them at their
 * own glucose figures instead.
 */
fun hba1cReliabilityAdvice(readings: List<LabReading>, imperial: Boolean, today: LocalDate): Advice? {
    val a1c = readings
        .filter { it.marker == LabMarker.HBA1C && it.isRecent(today) }
        .maxByOrNull(LabReading::occurredAt)
        ?: return null
    val anaemic = readings.onSheet(a1c, LabMarker.HEMOGLOBIN)
        ?.takeIf { it.status in setOf(LabStatus.BELOW, LabStatus.FAR_BELOW) }
    val failing = readings.onSheet(a1c, LabMarker.EGFR)?.takeIf { it.value < EGFR_A1C_UNRELIABLE }
    if (anaemic == null && failing == null) return null
    return Advice(
        topic = AdviceTopic.LABS,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_labs_a1c_context_title,
        bodyRes = if (anaemic != null) {
            R.string.advice_labs_a1c_anaemia_body
        } else {
            R.string.advice_labs_a1c_kidney_body
        },
        args = listOf(PrintedFigure(a1c.marker.printed(a1c.value, imperial))),
        sourceRes = R.string.advice_source_a1c_context,
        windowDays = a1c.daysSince(today),
        id = "labs-a1c-context",
    )
}

/** How many readings passed the band they were read against, over how many had a band at all. */
data class LabBandTally(val inside: Int, val graded: Int)

/**
 * The summary card's count. A reading with no band — insulin drawn after breakfast — is left out of
 * both figures: it did not pass anything, and counting it among the readings "inside the band" while
 * the row beside it asks for a fasting sample is the screen contradicting itself.
 */
fun labBandTally(readings: List<LabReading>): LabBandTally {
    val graded = readings.filter { it.status != null }
    return LabBandTally(inside = graded.count { it.status == LabStatus.NORMAL }, graded = graded.size)
}

/**
 * What a marker's chart ([LabSeriesChart]) says aloud about a row: the drawing publishes nothing, so the sentence is
 * chosen from the same figures. Some readings outside, and it says so. None outside under a band
 * with both edges is "inside"; under a band with one edge it is only "not above" or "not below" —
 * a pass on a side nobody published an edge for would be the reader saying what the chart refuses
 * to paint. A row whose band is missing, or that holds readings nobody could grade, gets the
 * figures alone.
 */
fun labTrendSummaryRes(readings: Collection<LabReading>, band: LabBand?): Int = when {
    readings.any { it.outside } -> R.string.labs_trend_outside
    band == null || readings.any { it.status == null } -> R.string.labs_trend_plain
    band.twoEdged -> R.string.labs_trend_inside
    band.hasCeiling -> R.string.labs_trend_not_above
    else -> R.string.labs_trend_not_below
}

/**
 * One journal row per panel: whatever was typed at once was drawn at once.
 *
 * The payload keeps the stored unit and the fasting flag, so a switch to mg/dL later re-reads the
 * same numbers and a sample drawn after breakfast is never read against a fasting band — and, where
 * the field offered two units, which one the figure was typed in. The note is a snapshot of the
 * values with their unit, because a bare "Glucose 97" in the journal is unreadable a year later. It
 * is written in the profile's unit, the one every other figure in the app reads in: a vitamin D typed
 * as 32 ng/mL on a metric profile is noted as 80 nmol/L, and the ng/mL stays in [TYPED_UNITS_KEY].
 */
@Suppress("LongParameterList") // One row of a sheet: what was measured, on what, by whom, and when.
internal fun labsEntry(
    resources: Resources,
    values: Map<LabMarker, Double>,
    fasting: Boolean,
    imperial: Boolean,
    day: LocalDate,
    attachments: String = "",
    specimen: LabSpecimen? = null,
    method: String = "",
    reportedRanges: Map<LabMarker, ReportedRange> = emptyMap(),
    typedUnits: Map<LabMarker, String> = emptyMap(),
) = JournalEntry(
    day = day.toString(),
    occurredAt = dayInstant(day.toString()),
    kind = KIND_LABS,
    title = resources.getString(R.string.labs_title),
    calories = 0.0,
    measurementPayloadJson = labsPayload(values, fasting, attachments, specimen, method, reportedRanges, typedUnits),
    note = values.entries.joinToString(" · ") { (marker, value) ->
        resources.getString(
            R.string.labs_note_value,
            resources.getString(marker.titleRes),
            marker.printed(value, imperial),
            // A pH has no unit to name; the dash that stands for one in a table is not a word here.
            if (marker.unitless) "" else resources.getString(marker.unitLabelRes(imperial)),
        ).trim()
    },
)

/**
 * The unit a marker is stored in, as UCUM spells it.
 *
 * Held apart from [LabMarker.unitRes], which is the word a person reads: "ng/mL" on a card and
 * `ng/mL` in a file happen to agree here, but the two are answerable to different things — one to
 * the sheet the person copied, the other to a machine that will do arithmetic with it. Every
 * spelling below passes the terminology server's check (`$validate-code` on
 * http://unitsofmeasure.org), and the filtration rate carries its annotation: a laboratory reports it
 * per 1.73 m² of body surface, and plain `mL/min` is a different quantity.
 *
 * HOMA-IR is `1`, UCUM's unity: it is a ratio the app computes, and it has no unit to give. A
 * specific gravity is one too — a ratio of densities.
 */
internal val LabMarker.ucum: UcumUnit
    get() = when (this) {
        LabMarker.GLUCOSE,
        LabMarker.CHOLESTEROL,
        LabMarker.LDL,
        LabMarker.HDL,
        LabMarker.TRIGLYCERIDES,
        LabMarker.NON_HDL,
        LabMarker.UREA,
        LabMarker.MAGNESIUM,
        LabMarker.URINE_CREATININE,
        -> UcumUnit.MILLIMOLE_PER_LITRE

        LabMarker.HBA1C, LabMarker.TSAT -> UcumUnit.PERCENT

        LabMarker.INSULIN -> UcumUnit.MICROUNIT_PER_MILLILITRE

        LabMarker.HOMA_IR, LabMarker.URINE_SG -> UcumUnit.COUNT

        LabMarker.LP_A, LabMarker.VITAMIN_D, LabMarker.FOLATE -> UcumUnit.NANOMOLE_PER_LITRE

        LabMarker.CREATININE, LabMarker.URIC_ACID, LabMarker.BILIRUBIN -> UcumUnit.MICROMOLE_PER_LITRE

        LabMarker.EGFR -> UcumUnit.MILLILITRE_PER_MINUTE_PER_BSA

        LabMarker.ALT, LabMarker.AST, LabMarker.GGT, LabMarker.ALP -> UcumUnit.UNIT_PER_LITRE

        LabMarker.PLATELETS -> UcumUnit.GIGA_PER_LITRE

        LabMarker.HEMOGLOBIN, LabMarker.URINE_PROTEIN -> UcumUnit.GRAM_PER_LITRE

        LabMarker.FERRITIN -> UcumUnit.NANOGRAM_PER_MILLILITRE

        LabMarker.CRP, LabMarker.URINE_ALBUMIN -> UcumUnit.MILLIGRAM_PER_LITRE

        LabMarker.TSH -> UcumUnit.MILLIUNIT_PER_LITRE

        LabMarker.FT4, LabMarker.B12 -> UcumUnit.PICOMOLE_PER_LITRE

        LabMarker.URINE_ACR -> UcumUnit.MILLIGRAM_PER_MILLIMOLE

        LabMarker.URINE_PH -> UcumUnit.PH
    }

/**
 * What the sample was, as the laboratory wrote it on the sheet.
 *
 * It is here because it is half of what a result means: the same marker name over serum and over
 * whole blood is two different tests with two different ranges, and a summary that carries the
 * number without the specimen is asking the next reader to guess.
 *
 * Nothing is inferred. A blood panel typed in without a specimen keeps none, prints none, and
 * carries no code, rather than being filed as the serum it probably was. A urine panel is always
 * [URINE]: the form's first question is what was given ([LabSample]), and that answer is the
 * person's, not a guess. Saliva is not offered — nothing the app holds is read from it — and stays
 * so that a panel already naming it still reads.
 */
enum class LabSpecimen(val titleRes: Int) {
    SERUM(R.string.labs_specimen_serum),
    PLASMA(R.string.labs_specimen_plasma),
    WHOLE_BLOOD(R.string.labs_specimen_whole_blood),
    CAPILLARY_BLOOD(R.string.labs_specimen_capillary),
    URINE(R.string.labs_specimen_urine),
    SALIVA(R.string.labs_specimen_saliva),
    ;

    /**
     * Whether this is blood. A saved or restored sheet may be marked
     * urine with the blood panels filled in, and a "glucose" or "creatinine" typed under it is not
     * a blood figure whatever field it went in.
     */
    val blood: Boolean get() = this != URINE && this != SALIVA
}

/**
 * Whether a sheet whose sample is [specimen] holds a typed figure of this marker in its field.
 *
 * Never one the app works out itself ([derived]). Nor a blood field on a sheet marked urine or
 * saliva, which a saved or restored panel may still hold: that holds no blood figure, and there is
 * no band for what it does hold — read as blood, a urine glucose of 0.8 would be graded far under the
 * hypoglycaemia line and sent to a clinician. It stays in the journal row's note, as it was typed,
 * and is read as nothing else.
 */
internal fun LabMarker.typedOn(specimen: LabSpecimen?): Boolean =
    !derived && (panel.sample != LabSample.BLOOD || specimen?.blood != false)

/** Whether the marker's figure has no unit to print beside it — a pH, a specific gravity, an index. */
val LabMarker.unitless: Boolean get() = unitRes == R.string.labs_unit_none

/** The blood form's optional detail: which of these the sheet names, or none. */
internal val BLOOD_SPECIMENS = LabSpecimen.entries.filter(LabSpecimen::blood)

/** The specimen a saved panel names, if it names one. */
internal fun JournalEntry.labSpecimen(): LabSpecimen? =
    measurementPayloadJson
        ?.let { runCatching { JSONObject(it).optString(SPECIMEN_KEY) }.getOrNull() }
        .let(::labSpecimenOf)

/** The specimen a stored panel names, or null for one that names none or names it unknown. */
fun labSpecimenOf(stored: String?): LabSpecimen? =
    LabSpecimen.entries.firstOrNull { it.name == stored?.trim() }

/**
 * The range printed beside a result on the laboratory's own sheet.
 *
 * Which is not the band this app applies. A laboratory's range belongs to the analyser that ran
 * the sample, the population it was validated on and the units it reported in; the app's bands are
 * published population guidance it applies itself, and the report says so under every graded
 * section. Recording the printed one is what lets the sheet show both and say
 * which is which — and it is the only range this app will ever put in a machine-readable file.
 *
 * Either edge may be absent: "< 5.0" and "> 40" are how half of them are printed.
 */
data class ReportedRange(val low: Double? = null, val high: Double? = null) {
    val empty: Boolean get() = low == null && high == null
}

/** Where the specimen, the method and the laboratory's own ranges live inside a panel's payload. */
internal const val SPECIMEN_KEY = "specimen"
internal const val METHOD_KEY = "method"
internal const val REPORTED_RANGES_KEY = "reportedRanges"

/** The laboratory's own ranges in a panel's payload, by marker. Absent keys mean none was printed. */
internal fun JSONObject.reportedRanges(): Map<LabMarker, ReportedRange> {
    val held = optJSONObject(REPORTED_RANGES_KEY) ?: return emptyMap()
    return LabMarker.entries.mapNotNull { marker ->
        val range = held.optJSONObject(marker.name) ?: return@mapNotNull null
        val low = range.optDouble("low", Double.NaN).takeIf { !it.isNaN() }
        val high = range.optDouble("high", Double.NaN).takeIf { !it.isNaN() }
        ReportedRange(low, high).takeIf { !it.empty }?.let { marker to it }
    }.toMap()
}

/** The same on the way in: a marker with neither edge is left out rather than written empty. */
internal fun reportedRangesJson(ranges: Map<LabMarker, ReportedRange>): JSONObject? {
    val held = JSONObject()
    ranges.forEach { (marker, range) ->
        if (range.empty) return@forEach
        held.put(
            marker.name,
            JSONObject().apply {
                range.low?.let { put("low", it) }
                range.high?.let { put("high", it) }
            },
        )
    }
    return held.takeIf { it.length() > 0 }
}

/**
 * Everything a panel knows, as the payload column holds it.
 *
 * Apart from [labsEntry] so the shape of a sheet can be tested without an Android [Resources]: the
 * note on the row is the only part that needs one, and what has to be got right here is which keys
 * are written and which are left out.
 */
@Suppress("LongParameterList") // The same row as [labsEntry], minus the words.
internal fun labsPayload(
    values: Map<LabMarker, Double>,
    fasting: Boolean,
    attachments: String = "",
    specimen: LabSpecimen? = null,
    method: String = "",
    reportedRanges: Map<LabMarker, ReportedRange> = emptyMap(),
    typedUnits: Map<LabMarker, String> = emptyMap(),
): String = JSONObject().apply {
    put("schema", 1)
    put(FASTING_KEY, fasting)
    // The scan of the sheet rides in the payload rather than in a column of its own: a panel is
    // already a JSON blob, and nothing queries an attachment.
    if (attachments.isNotBlank()) put(ATTACHMENTS_KEY, attachments)
    // What the sample was and what read it, where the sheet said so. Absent rather than guessed:
    // see [LabSpecimen].
    specimen?.let { put(SPECIMEN_KEY, it.name) }
    if (method.isNotBlank()) put(METHOD_KEY, method.trim())
    reportedRangesJson(reportedRanges)?.let { put(REPORTED_RANGES_KEY, it) }
    values.forEach { (marker, value) -> put(marker.key, value) }
    // Only beside a figure that was saved: a unit with no number under it says nothing.
    typedUnits.filterKeys(values::containsKey).takeIf { it.isNotEmpty() }?.let { units ->
        put(TYPED_UNITS_KEY, JSONObject().apply { units.forEach { (marker, unit) -> put(marker.key, unit) } })
    }
}.toString()

/**
 * The figures of [values] that no panel among [saved] — the rows of the draw's day — already holds
 * with the same figure. A sheet scanned a second time, or its first page saved again with the second,
 * is one result and not two: written twice, it would draw the same point twice on every trend and
 * list it twice for the doctor. A figure that differs is kept, since two draws in one day are two results.
 */
internal fun unsavedLabValues(values: Map<LabMarker, Double>, saved: List<JournalEntry>): Map<LabMarker, Double> {
    val panels = saved.labPanels().map { (_, panel) -> panel }
    return values.filterNot { (marker, value) -> panels.any { it.holdsFigure(marker, value) } }
}

/**
 * The day's panel a sheet saved again belongs to, with what the sheet brings and the panel lacks — or
 * null where it brings nothing. [sheet] is the payload the save would have written ([labsPayload]) had
 * [unsavedLabValues] left it a figure to write.
 *
 * Figures typed by hand, and the laboratory's PDF read afterwards to keep the original beside them:
 * every figure is held already, so nothing is written, and the page would say "already saved" over a
 * file attached to no row, which the sweep of files no row names would then let go of. The file goes to the
 * panel holding the most of the sheet's figures, the first of them on a tie. The laboratory's name,
 * the specimen and a printed range are added only where that panel has none: what was saved is never
 * replaced by a second reading of it.
 */
internal fun panelWithSheet(values: Map<LabMarker, Double>, saved: List<JournalEntry>, sheet: String): JournalEntry? {
    val (row, panel) = saved.labPanels()
        .map { it to values.count { (marker, value) -> it.second.holdsFigure(marker, value) } }
        .filter { (_, held) -> held > 0 }
        .maxByOrNull { (_, held) -> held }?.first ?: return null
    val before = panel.toString()
    val brought = JSONObject(sheet)
    val files = medicalAttachments(panel.optString(ATTACHMENTS_KEY))
    val added = medicalAttachments(brought.optString(ATTACHMENTS_KEY))
        .filter { file -> files.none { it.uri == file.uri } }
    if (added.isNotEmpty()) panel.put(ATTACHMENTS_KEY, storeMedicalAttachments(files + added))
    listOf(METHOD_KEY, SPECIMEN_KEY).filter { brought.has(it) && !panel.has(it) }
        .forEach { panel.put(it, brought.get(it)) }
    brought.optJSONObject(REPORTED_RANGES_KEY)?.let { printed ->
        val own = panel.optJSONObject(REPORTED_RANGES_KEY)
        val lacking = values.filter { (marker, value) -> panel.holdsFigure(marker, value) }.keys
            .filter { printed.has(it.name) && own?.has(it.name) != true }
        if (lacking.isNotEmpty()) {
            val ranges = own ?: JSONObject()
            lacking.forEach { ranges.put(it.name, printed.get(it.name)) }
            panel.put(REPORTED_RANGES_KEY, ranges)
        }
    }
    val merged = panel.toString()
    return row.copy(measurementPayloadJson = merged).takeIf { merged != before }
}

/** What a panel's Save did, and the heading of the page that says so. */
internal enum class LabSaveOutcome(val titleRes: Int) {
    ADDED(R.string.lab_add_done_title),
    ALREADY(R.string.lab_add_done_already),
    ATTACHED(R.string.lab_add_done_attached),
}

/**
 * What to say after a panel's Save: [fresh] are the figures it wrote ([unsavedLabValues]), and
 * [merged] is the panel [panelWithSheet] gave back where it wrote none, out of the day's rows [held].
 *
 * A sheet read to keep the original beside figures typed earlier would otherwise end on
 * "Results already saved", which reads as nothing done though the file has gone to the panel. A file
 * that reached a panel is said; a laboratory's name or a printed range filled in is not, since the
 * heading has room for one thing and the file is the one that was asked for.
 */
internal fun labSaveOutcome(
    fresh: Map<LabMarker, Double>,
    held: List<JournalEntry>,
    merged: JournalEntry?,
): LabSaveOutcome {
    fun files(row: JournalEntry?) = attachmentUris(row?.labAttachments().orEmpty()).size
    return when {
        fresh.isNotEmpty() -> LabSaveOutcome.ADDED
        merged != null && files(merged) > files(held.firstOrNull { it.id == merged.id }) -> LabSaveOutcome.ATTACHED
        else -> LabSaveOutcome.ALREADY
    }
}

/** The panels among a day's rows, each with its payload read; a payload that will not parse is no panel. */
private fun List<JournalEntry>.labPanels(): List<Pair<JournalEntry, JSONObject>> =
    filter { it.kind == KIND_LABS }.mapNotNull { row ->
        row.measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { row to it }
    }

/** Whether a panel's payload holds [value] for [marker], to within [SAME_FIGURE]. */
private fun JSONObject.holdsFigure(marker: LabMarker, value: Double): Boolean {
    val held = optDouble(marker.key)
    return !held.isNaN() && abs(held - value) <= SAME_FIGURE * maxOf(1.0, abs(value))
}

/** How near two stored figures are one figure: unit arithmetic's noise, far inside anything a sheet prints. */
private const val SAME_FIGURE = 1e-9

internal const val FASTING_KEY = "fasting"

/**
 * Which unit each figure was typed in, for the fields that offer two ([otherUnit]), by UCUM spelling
 * and marker key: `{"vitaminD25NmolL":"ng/mL"}`. The figure beside it is stored in the band's unit as
 * ever; this is what lets the form offer the same unit next time, and a record say what was copied.
 */
internal const val TYPED_UNITS_KEY = "typedUnits"

/**
 * The markers whose field asks which unit the figure was typed in, rather than taking the profile's.
 *
 * Here the two units' ranges overlap, so the field's own bounds cannot catch a figure copied in the
 * other one, and the profile's metric or imperial setting is no guide to the sheet. 32 ng/mL of
 * 25-hydroxyvitamin D typed into an nmol/L field would be stored as 32 nmol/L, inadequate, where the
 * sheet meant 80, adequate. A B12 of 170 pg/mL taken as pmol/L lands in NICE's indeterminate band
 * rather than its confirmed one, a US free T4 of 1.3 ng/dL would be graded low as 1.3 pmol/L, a CRP
 * of 0.5 mg/dL — 5 mg/L, over the band — would be graded normal as 0.5 mg/L, and a bilirubin of
 * 2.5 mg/dL, 43 µmol/L and raised, would be graded normal as 2.5 µmol/L.
 *
 * Glucose, LDL, the triglycerides and creatinine meet their other unit at the far ends alone — glucose
 * from 10 to 50, LDL from 12 to 15, the triglycerides from 18 to 20, creatinine from 20 to 22.6 — and
 * are asked all the same, because a figure out there is the one a wrong unit costs most: a glucose of
 * 12 mmol/L typed under the imperial setting would be kept as 0.67 and read as far below, where it
 * is far above.
 *
 * Total cholesterol, HDL, uric acid and haemoglobin meet theirs nowhere, and are asked so that a sheet
 * is copied in one unit from top to bottom: with LDL on its mg/dL chip and the cholesterol above it
 * refusing 200, the panel could only be typed by changing the whole profile's units. Left to the
 * profile, a haemoglobin field would refuse a sheet's 13.9 g/dL under the metric setting, with
 * nothing on the field to say the figure was right and the unit was not. A field with a second unit
 * and no choice — no field here is one — must still refuse a figure copied in the other one, and
 * LabFieldBoundsTest holds any that is added to that.
 *
 * HbA1c has no imperial unit, and is asked all the same: its % and the IFCC mmol/mol meet from 10 to
 * 20, and a British sheet prints the mmol/mol alone ([HBA1C_IFCC]).
 *
 * Lp(a) overlaps as badly as any field here and gets no choice either, for another reason: no fixed
 * factor converts its mg/dL to nmol/L across assays, as the lipid article says, so its field stays
 * nmol/L alone.
 */
private val UNIT_CHOICE_MARKERS = setOf(
    LabMarker.GLUCOSE,
    LabMarker.HBA1C,
    LabMarker.CHOLESTEROL,
    LabMarker.LDL,
    LabMarker.HDL,
    LabMarker.TRIGLYCERIDES,
    LabMarker.CREATININE,
    LabMarker.URIC_ACID,
    LabMarker.BILIRUBIN,
    LabMarker.HEMOGLOBIN,
    LabMarker.CRP,
    LabMarker.FT4,
    LabMarker.VITAMIN_D,
    LabMarker.B12,
    LabMarker.FOLATE,
    LabMarker.MAGNESIUM,
    // Every urine figure with a second unit overlaps it, and the ratio worst: a sheet's 5 mg/mmol,
    // raised, taken as mg/g is 0.57 mg/mmol and would be called normal.
    LabMarker.URINE_ACR,
    LabMarker.URINE_ALBUMIN,
    LabMarker.URINE_CREATININE,
    LabMarker.URINE_PROTEIN,
)

/** CRP's mg/dL, a tenth of the mg/L its bands are written in — typed, never printed: see [LabImperial]. */
private val CRP_MG_DL = LabImperial(R.string.labs_unit_mgdl, 0.1, decimals = 2, UcumUnit.MILLIGRAM_PER_DECILITRE)

/**
 * HbA1c's IFCC mmol/mol, typed and never printed, through the IFCC–NGSP master equation (Hoelzel et
 * al. 2004, Clin Chem) the sheet reader converts with: NGSP % = 0.09148 × IFCC + 2.152.
 */
private val HBA1C_IFCC = LabImperial(
    R.string.labs_unit_mmolmol,
    factor = 1 / NGSP_PERCENT_PER_IFCC,
    ucum = UcumUnit.MILLIMOLE_PER_MOLE,
    offset = -NGSP_PERCENT_AT_IFCC_ZERO / NGSP_PERCENT_PER_IFCC,
)

internal const val NGSP_PERCENT_PER_IFCC = 0.09148
internal const val NGSP_PERCENT_AT_IFCC_ZERO = 2.152

/**
 * The unit a sheet may print this marker in besides the stored one, where its field offers both:
 * the profile's imperial unit, except for CRP, which keeps the mg/L its AHA/CDC bands are written in
 * on every profile and takes mg/dL only as typed, and HbA1c, whose mmol/mol is no profile's unit.
 * Null where the field takes the profile's unit alone.
 */
internal val LabMarker.otherUnit: LabImperial?
    get() = when {
        this !in UNIT_CHOICE_MARKERS -> null
        this == LabMarker.CRP -> CRP_MG_DL
        this == LabMarker.HBA1C -> HBA1C_IFCC
        else -> imperial
    }

/**
 * The unit a figure in this marker's field is taken in, null being the stored one: the unit
 * [typedUnit] spells, where the field offers two and it spells one of them, and the profile's
 * otherwise — which is also the form's default until a panel has recorded one.
 */
internal fun LabMarker.fieldUnit(typedUnit: String?, imperialUnits: Boolean): LabImperial? {
    val other = otherUnit ?: return unit(imperialUnits)
    return when (typedUnit) {
        other.ucum?.ucum -> other
        ucum.ucum -> null
        else -> unit(imperialUnits)
    }
}

/** How the panel records a unit [fieldUnit] gave: its UCUM spelling, the stored unit's for null. */
internal fun LabMarker.typedUnitCode(typedIn: LabImperial?): String = (typedIn?.ucum ?: ucum).ucum

/** The units a panel's payload records, by marker. A spelling neither of the field's units has is dropped. */
internal fun JSONObject.typedUnits(): Map<LabMarker, String> {
    val held = optJSONObject(TYPED_UNITS_KEY) ?: return emptyMap()
    return LabMarker.entries.mapNotNull { marker ->
        val other = marker.otherUnit ?: return@mapNotNull null
        held.optString(marker.key).takeIf { it == other.ucum?.ucum || it == marker.ucum.ucum }?.let { marker to it }
    }.toMap()
}

/**
 * The unit each field was last typed in, which the form offers first. Last is the panel written
 * down last — the row id, the order the app learned things in — not the latest draw: a March sheet
 * typed in September is the newest habit.
 */
internal fun lastTypedUnits(entries: List<JournalEntry>): Map<LabMarker, String> =
    entries.filter { it.kind == KIND_LABS }
        .sortedBy(JournalEntry::id)
        .mapNotNull { entry -> entry.measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() } }
        .fold(emptyMap()) { last, payload -> last + payload.typedUnits() }

/**
 * A figure typed in [typedIn], written in the field's other unit, with that unit's label: the line
 * under a field that offers two, so that a figure typed against the wrong one shows itself before it
 * is saved — 32 taken as nmol/L reads "12.8 ng/mL" while the sheet said 32 ng/mL. Null for a field
 * with one unit.
 */
internal fun LabMarker.inOtherUnit(typed: Double, typedIn: LabImperial?): Pair<String, Int>? {
    val other = otherUnit ?: return null
    return if (typedIn == null) {
        figureText(other.of(typed), other.decimals) to other.unitRes
    } else {
        printed(storedIn(typed, typedIn), imperialUnits = false) to unitRes
    }
}

/**
 * The bounds a field in [typedIn] takes, as its refusal line prints them. Not through Double.pretty,
 * which rounds to a tenth: TSH's 0.005 would print as "0.0".
 */
internal fun LabMarker.acceptedBounds(typedIn: LabImperial?): Pair<String, String> {
    val places = typedIn?.decimals ?: MAX_PRINT_PLACES
    return figureText(lowestAcceptedIn(typedIn), places) to figureText(highestAcceptedIn(typedIn), places)
}

/** The Bluetooth glucose meter's context object, and the meal value the GLS spec names "Fasting". */
private const val GLS_CONTEXT_KEY = "context"
private const val GLS_MEAL_KEY = "meal"
private const val GLS_MEAL_FASTING = 3

/**
 * Whether a reading was taken fasting, whoever wrote the payload: this app's own panel flag, or the
 * meal field a Bluetooth meter reports in its measurement context. The Health Connect importer
 * writes the plain flag when the record says fasting, so it needs no case of its own here.
 */
internal fun JSONObject.labFasting(): Boolean =
    optBoolean(FASTING_KEY, false) ||
        optJSONObject(GLS_CONTEXT_KEY)?.optInt(GLS_MEAL_KEY) == GLS_MEAL_FASTING

/**
 * Whether the payload answers the question at all, either way: the form's flag, which a panel always
 * carries, or a meter's meal field. Only a payload that does not is left to [inferredFasting].
 */
private fun JSONObject.statesFasting(): Boolean =
    has(FASTING_KEY) || optJSONObject(GLS_CONTEXT_KEY)?.has(GLS_MEAL_KEY) == true

/** Eight hours without calories, which is what "fasting" means on a request form. */
private const val LAB_FASTING_HOURS = 8L

/** How far back a meal counts as evidence the person was keeping a diary around the draw. */
private const val LAB_DIARY_EVIDENCE_HOURS = 24L

private const val HOUR_MS = 60L * 60L * 1000L

/**
 * Whether a sample that says nothing about fasting can be read as one, from the meals around it.
 *
 * Worth doing because the answer changes the band: EAS/EFLM put the triglyceride line at 1.7 on a
 * fasting sample and 2.0 on a fed one, and WHO's glucose lines are written for a fasting sample. The
 * meal timestamps are already on file, so no extra question is needed.
 *
 * It is asked only of a payload that carries neither the form's flag nor a meter's meal field — see
 * [statesFasting]: a glucose out of Health Connect that is not marked fasting (the importer writes the
 * flag only when the record says so), a meter reading sent with no context. A panel off the form
 * always carries the flag, and its answer stands either way.
 *
 * Only where there is evidence the person was keeping a food diary at the time. A stretch with no
 * meals in it is silence, not an empty stomach, and reading silence as fasting would move every
 * triglyceride onto the tighter band and manufacture findings for everybody who does not log food.
 */
internal fun inferredFasting(drawnAtMs: Long, mealTimes: List<Long>): Boolean {
    val keepingDiary = mealTimes.any { it in (drawnAtMs - LAB_DIARY_EVIDENCE_HOURS * HOUR_MS)..drawnAtMs }
    if (!keepingDiary) return false
    return mealTimes.none { it in (drawnAtMs - LAB_FASTING_HOURS * HOUR_MS)..drawnAtMs }
}

/** [labFasting] for a journal row; false for a row with no payload or one that does not parse. */
internal fun JournalEntry.labFasting(): Boolean =
    measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.labFasting() == true

/** Markers a sweet diet moves. Urate belongs here because of fructose, not because of glucose. */
private val SUGAR_SENSITIVE = setOf(
    LabMarker.GLUCOSE,
    LabMarker.HBA1C,
    LabMarker.HOMA_IR,
    LabMarker.TRIGLYCERIDES,
    LabMarker.URIC_ACID,
)

/** Markers that walking moves. A short list on purpose. */
private val MOVEMENT_SENSITIVE = setOf(
    LabMarker.GLUCOSE,
    LabMarker.HBA1C,
    LabMarker.HOMA_IR,
    LabMarker.HDL,
    LabMarker.TRIGLYCERIDES,
)

private val LIVER_ENZYMES = setOf(LabMarker.ALT, LabMarker.AST, LabMarker.GGT)

private val ALCOHOL_SENSITIVE = setOf(
    LabMarker.GGT,
    LabMarker.ALT,
    LabMarker.AST,
    LabMarker.TRIGLYCERIDES,
    LabMarker.URIC_ACID,
)

/** Markers the days around the draw can lift on their own. */
private val DRAW_SENSITIVE = setOf(
    LabMarker.CREATININE,
    LabMarker.EGFR,
    LabMarker.UREA,
    LabMarker.AST,
)

/** WHO's overweight line for adults; the body-fat half of the question is [Vital.BODY_FAT]'s band. */
private const val OVERWEIGHT_BMI = 25.0

/** AASLD: 7–10% of body mass is the loss that moves the enzymes, not just the fat. */
private const val LIVER_WEIGHT_LOSS_SHARE = 0.07

private fun List<LabReading>.raisedAmong(markers: Set<LabMarker>, today: LocalDate): LabReading? =
    firstOrNull {
        it.marker in markers &&
            it.status in setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE) &&
            it.isRecent(today)
    }

/** Days that actually carry the column, summed. A missing column is unknown, never zero. */
private fun List<JournalEntry>.dailyNutrient(
    until: LocalDate,
    windowDays: Int,
    of: (JournalEntry) -> Double?,
): Map<String, Double> {
    val from = until.minusDays(windowDays - 1L)
    return filter { it.kind == KIND_EAT && of(it) != null }
        .filter { it.day.toLocalDateOrNull()?.let { d -> d in from..until } == true }
        .groupBy(JournalEntry::day)
        .mapValues { (_, meals) -> meals.sumOf { of(it) ?: 0.0 } }
}

private fun List<JournalEntry>.dailyWaterMl(until: LocalDate, windowDays: Int): Map<String, Double> {
    val from = until.minusDays(windowDays - 1L)
    return filter { it.kind == KIND_WATER }
        .filter { it.day.toLocalDateOrNull()?.let { d -> d in from..until } == true }
        .groupBy(JournalEntry::day)
        .mapValues { (_, glasses) -> glasses.sumOf(JournalEntry::servingG) }
}

internal fun Collection<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun LabReading.isRecent(today: LocalDate): Boolean =
    day.toLocalDateOrNull()
        ?.let { it in today.minusDays(LABS_WINDOW_DAYS - 1L)..today } == true

private fun LabReading.daysSince(today: LocalDate): Int =
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(day), today).toInt() }
        .getOrDefault(0)
        .coerceAtLeast(1)
