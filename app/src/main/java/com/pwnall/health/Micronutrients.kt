package com.pwnall.health

// Vitamins and minerals: which ones the app names, what unit each is counted in, and what a day of
// them is read against.
//
// One table for the three places that count them — a supplement's label, the catalogue rows a meal
// was built from, and the report's intake table — so that "vitamin D" means micrograms of D2 + D3
// in all three, and a label typed in international units lands on the same scale as USDA's figure
// for a salmon fillet.

/** The unit an amount is stored and printed in. Only three, all UCUM-valid as written. */
enum class NutrientUnit(val ucum: String, val labelRes: Int) {
    MICROGRAM("ug", R.string.unit_micrograms),
    MILLIGRAM("mg", R.string.unit_milligrams),
    GRAM("g", R.string.unit_grams),
}

/**
 * A nutrient somebody can take or eat, in the unit the reference intakes are written in.
 *
 * Stored by [name] — in a course's contents and nowhere else — so the order here is free to change
 * and a name must never be.
 *
 * Vitamin A is micrograms of retinol activity equivalents; on a supplement it means preformed
 * retinol and its esters, which is what the upper level is about — beta-carotene is not recorded
 * here at all, since neither body set an upper level for it. [FOLATE] is dietary folate
 * equivalents, what USDA reports for a food; [FOLIC_ACID] is the synthetic form a tablet holds,
 * counted separately because the upper level applies to it alone and because a microgram of it is
 * worth more than one of the food form — see [FOLIC_ACID_DFE].
 */
enum class Nutrient(val titleRes: Int, val unit: NutrientUnit) {
    VITAMIN_A(R.string.nutrient_vitamin_a, NutrientUnit.MICROGRAM),
    VITAMIN_C(R.string.nutrient_vitamin_c, NutrientUnit.MILLIGRAM),
    VITAMIN_D(R.string.nutrient_vitamin_d, NutrientUnit.MICROGRAM),
    VITAMIN_E(R.string.nutrient_vitamin_e, NutrientUnit.MILLIGRAM),
    VITAMIN_K(R.string.nutrient_vitamin_k, NutrientUnit.MICROGRAM),
    THIAMIN(R.string.nutrient_thiamin, NutrientUnit.MILLIGRAM),
    RIBOFLAVIN(R.string.nutrient_riboflavin, NutrientUnit.MILLIGRAM),
    NIACIN(R.string.nutrient_niacin, NutrientUnit.MILLIGRAM),
    VITAMIN_B6(R.string.nutrient_vitamin_b6, NutrientUnit.MILLIGRAM),
    FOLATE(R.string.nutrient_folate, NutrientUnit.MICROGRAM),
    FOLIC_ACID(R.string.nutrient_folic_acid, NutrientUnit.MICROGRAM),
    VITAMIN_B12(R.string.nutrient_vitamin_b12, NutrientUnit.MICROGRAM),
    BIOTIN(R.string.nutrient_biotin, NutrientUnit.MICROGRAM),
    CALCIUM(R.string.nutrient_calcium, NutrientUnit.MILLIGRAM),
    IRON(R.string.nutrient_iron, NutrientUnit.MILLIGRAM),
    MAGNESIUM(R.string.nutrient_magnesium, NutrientUnit.MILLIGRAM),
    PHOSPHORUS(R.string.nutrient_phosphorus, NutrientUnit.MILLIGRAM),
    POTASSIUM(R.string.nutrient_potassium, NutrientUnit.MILLIGRAM),
    ZINC(R.string.nutrient_zinc, NutrientUnit.MILLIGRAM),
    SELENIUM(R.string.nutrient_selenium, NutrientUnit.MICROGRAM),
    IODINE(R.string.nutrient_iodine, NutrientUnit.MICROGRAM),
    OMEGA3(R.string.nutrient_omega3, NutrientUnit.MILLIGRAM),
    CREATINE(R.string.nutrient_creatine, NutrientUnit.GRAM),
}

/** Whether the daily figure is an average requirement's safe margin or only an observed adequate intake. */
enum class ReferenceKind(val labelRes: Int) {
    /** Recommended Dietary Allowance: covers 97-98% of healthy people, set two SDs above a requirement. */
    RDA(R.string.nutrient_reference_rda),

    /**
     * Adequate Intake: what healthy people were seen to eat where no requirement could be set.
     *
     * An intake below it says nothing about adequacy — NASEM, *DRI: Applications in Dietary
     * Assessment* (2000), ch. 3, which is the chapter about one person — so no table built on this
     * may call such an intake low.
     */
    AI(R.string.nutrient_reference_ai),
}

/** What an upper level counts. */
enum class UpperLevelScope {
    /** Everything eaten and taken. */
    TOTAL,

    /** A pharmacological dose only — NASEM's magnesium, which excludes food and water. */
    SUPPLEMENTS,

    /**
     * Synthetic or added forms: from supplements, and from fortified food where the food says so.
     *
     * NASEM's vitamin E, niacin and folic acid limits and EFSA's magnesium one. The diary can only
     * see the synthetic share of food for folic acid, which USDA lists apart; for the others the
     * supplement is counted and the fortified cereal is not, so a sum against this is a floor.
     */
    SYNTHETIC,

    /** Retinol and its esters, never carotenoids. */
    PREFORMED,
}

/** The body that published a figure, printed beside it. */
enum class ReferenceBody(val labelRes: Int) {
    NASEM(R.string.nutrient_body_nasem),
    EFSA(R.string.nutrient_body_efsa),
}

data class NutrientReference(val amount: Double, val kind: ReferenceKind)

data class UpperLevel(val amount: Double, val scope: UpperLevelScope, val body: ReferenceBody)

/** The ages the adult reference tables start at; younger than this, no figure is read against any. */
const val NUTRIENT_ADULT_AGE = 19

private const val AGE_31 = 31
private const val AGE_51 = 51
private const val AGE_71 = 71

/**
 * The daily figure for an adult, or null where there is none to read against.
 *
 * NASEM Dietary Reference Intakes, the Food and Nutrition Board's summary tables for RDA and AI. The
 * US figures rather than EFSA's for one reason that decides it: the units. USDA reports vitamin A in
 * retinol *activity* equivalents and EFSA writes its reference in retinol equivalents, which count
 * beta-carotene at twice the weight — so dividing one by the other halves plant vitamin A. EFSA also
 * writes thiamin and niacin per megajoule, which a diary that under-records energy by a fifth
 * (Foster 2019) would turn into a lower target the worse somebody logs.
 *
 * Adults only, and never pregnancy or lactation: under 19 the tables change band by band, and a
 * pregnancy changes folate, iron and iodine enough that an adult row would mislead. And only for the
 * two sexes the tables are written for: where a figure differs by sex and the profile names neither,
 * there is no figure, rather than a man's iron allowance for a woman of thirty.
 */
@Suppress("CyclomaticComplexMethod") // One branch per nutrient: it is the table.
fun Nutrient.reference(sex: String, ageYears: Int): NutrientReference? {
    if (ageYears < NUTRIENT_ADULT_AGE) return null
    val male = sex == "MALE"
    val bySex = sex == "MALE" || sex == "FEMALE"
    fun rda(amount: Double) = NutrientReference(amount, ReferenceKind.RDA)
    fun ai(amount: Double) = NutrientReference(amount, ReferenceKind.AI)
    if (!bySex && this in SEX_SPLIT) return null
    return when (this) {
        Nutrient.VITAMIN_A -> rda(if (male) 900.0 else 700.0)

        Nutrient.VITAMIN_C -> rda(if (male) 90.0 else 75.0)

        Nutrient.VITAMIN_D -> rda(if (ageYears >= AGE_71) 20.0 else 15.0)

        Nutrient.VITAMIN_E -> rda(15.0)

        Nutrient.VITAMIN_K -> ai(if (male) 120.0 else 90.0)

        Nutrient.THIAMIN -> rda(if (male) 1.2 else 1.1)

        Nutrient.RIBOFLAVIN -> rda(if (male) 1.3 else 1.1)

        Nutrient.NIACIN -> rda(if (male) 16.0 else 14.0)

        Nutrient.VITAMIN_B6 -> rda(
            when {
                ageYears < AGE_51 -> 1.3
                male -> 1.7
                else -> 1.5
            },
        )

        Nutrient.FOLATE -> rda(400.0)

        Nutrient.VITAMIN_B12 -> rda(2.4)

        Nutrient.BIOTIN -> ai(30.0)

        Nutrient.CALCIUM -> rda(
            when {
                ageYears >= AGE_71 -> 1200.0
                !male && ageYears >= AGE_51 -> 1200.0
                else -> 1000.0
            },
        )

        Nutrient.IRON -> rda(if (!male && ageYears < AGE_51) 18.0 else 8.0)

        Nutrient.MAGNESIUM -> rda(
            when {
                male && ageYears < AGE_31 -> 400.0
                male -> 420.0
                ageYears < AGE_31 -> 310.0
                else -> 320.0
            },
        )

        Nutrient.PHOSPHORUS -> rda(700.0)

        Nutrient.POTASSIUM -> ai(if (male) 3400.0 else 2600.0)

        Nutrient.ZINC -> rda(if (male) 11.0 else 8.0)

        Nutrient.SELENIUM -> rda(55.0)

        Nutrient.IODINE -> rda(150.0)

        // Folic acid is read through FOLATE at 1.7 DFE a microgram; the other two have no DRI.
        Nutrient.FOLIC_ACID, Nutrient.OMEGA3, Nutrient.CREATINE -> null
    }
}

/** The nutrients whose adult figure differs between men and women somewhere in the table. */
private val SEX_SPLIT = setOf(
    Nutrient.VITAMIN_A, Nutrient.VITAMIN_C, Nutrient.VITAMIN_K, Nutrient.THIAMIN, Nutrient.RIBOFLAVIN,
    Nutrient.NIACIN, Nutrient.VITAMIN_B6, Nutrient.CALCIUM, Nutrient.IRON, Nutrient.MAGNESIUM,
    Nutrient.POTASSIUM, Nutrient.ZINC,
)

/**
 * The tolerable upper levels that apply to an adult, NASEM's first and EFSA's where EFSA set a lower
 * one — both printed, each with its body, because where they disagree the reader should see that
 * they do.
 *
 * The scope matters as much as the figure. NASEM's magnesium limit counts pharmacological doses
 * only; its vitamin E, niacin and folic acid limits count the synthetic forms, from supplements and
 * fortified food; its vitamin A limit counts preformed retinol only. A plate of carrots or a handful
 * of almonds cannot exceed any of them, and a table that added food to them would flag a salad.
 *
 * EFSA figures, as its UL overview (version 11, August 2025) cites them: vitamin B6 12 mg (EFSA
 * 2023), selenium 255 µg (EFSA 2023), zinc 25 mg (SCF 2002), iodine 600 µg (SCF 2003), magnesium
 * 250 mg from readily dissociable salts in supplements, water and fortified food (SCF 2001), vitamin
 * E 300 mg for every form of alpha-tocopherol (SCF 2003, retained by EFSA 2024). EFSA's 40 mg for
 * iron (2024) is a "safe level", not an upper level, and is not printed as one. Its nicotinic acid
 * limit, 10 mg, is left out on purpose: a label rarely says whether its niacin is nicotinic acid
 * or nicotinamide, whose EFSA limit is 900 mg, and a guess either way would print a wrong verdict.
 */
@Suppress("CyclomaticComplexMethod") // One branch per nutrient: it is the table.
fun Nutrient.upperLevels(ageYears: Int): List<UpperLevel> {
    if (ageYears < NUTRIENT_ADULT_AGE) return emptyList()
    fun nasem(amount: Double, scope: UpperLevelScope = UpperLevelScope.TOTAL) =
        UpperLevel(amount, scope, ReferenceBody.NASEM)
    fun efsa(amount: Double, scope: UpperLevelScope = UpperLevelScope.TOTAL) =
        UpperLevel(amount, scope, ReferenceBody.EFSA)
    return when (this) {
        Nutrient.VITAMIN_A -> listOf(nasem(3000.0, UpperLevelScope.PREFORMED))

        Nutrient.VITAMIN_C -> listOf(nasem(2000.0))

        Nutrient.VITAMIN_D -> listOf(nasem(100.0))

        Nutrient.VITAMIN_E -> listOf(nasem(1000.0, UpperLevelScope.SYNTHETIC), efsa(300.0))

        Nutrient.NIACIN -> listOf(nasem(35.0, UpperLevelScope.SYNTHETIC))

        Nutrient.VITAMIN_B6 -> listOf(nasem(100.0), efsa(12.0))

        Nutrient.FOLIC_ACID -> listOf(nasem(1000.0, UpperLevelScope.SYNTHETIC))

        Nutrient.CALCIUM -> listOf(nasem(if (ageYears >= AGE_51) 2000.0 else 2500.0))

        Nutrient.IRON -> listOf(nasem(45.0))

        Nutrient.MAGNESIUM -> listOf(
            nasem(350.0, UpperLevelScope.SUPPLEMENTS),
            efsa(250.0, UpperLevelScope.SYNTHETIC),
        )

        Nutrient.PHOSPHORUS -> listOf(nasem(if (ageYears >= AGE_71) 3000.0 else 4000.0))

        Nutrient.ZINC -> listOf(nasem(40.0), efsa(25.0))

        Nutrient.SELENIUM -> listOf(nasem(400.0), efsa(255.0))

        Nutrient.IODINE -> listOf(nasem(1100.0), efsa(600.0))

        Nutrient.VITAMIN_K, Nutrient.THIAMIN, Nutrient.RIBOFLAVIN, Nutrient.FOLATE, Nutrient.VITAMIN_B12,
        Nutrient.BIOTIN, Nutrient.POTASSIUM, Nutrient.OMEGA3, Nutrient.CREATINE,
        -> emptyList()
    }
}

/**
 * Micrograms of dietary folate equivalents in one microgram of folic acid taken with food.
 *
 * NASEM's definition, 1 DFE = 0.6 µg of folic acid with food, taken as its reciprocal rather than
 * the 1.7 it is usually rounded to. On an empty stomach the same footnote makes it 2.0; the diary
 * does not know which, and the smaller figure never overstates what a tablet contributed.
 */
const val FOLIC_ACID_DFE = 1.0 / 0.6

/** International units printed on a label, into the unit the nutrient is counted in. */
enum class LabelUnit(val nutrient: Nutrient, val perUnit: Double, val labelRes: Int) {
    /** 1 µg of vitamin D is 40 IU, by definition. */
    VITAMIN_D_IU(Nutrient.VITAMIN_D, 0.025, R.string.nutrient_unit_iu),

    /** Preformed vitamin A only: 1 IU of retinol is 0.3 µg RAE. Beta-carotene is not recorded — see [Nutrient]. */
    VITAMIN_A_IU(Nutrient.VITAMIN_A, 0.3, R.string.nutrient_unit_iu),

    /** Natural d-alpha-tocopherol: 1 IU is 0.67 mg. */
    VITAMIN_E_IU_NATURAL(Nutrient.VITAMIN_E, 0.67, R.string.nutrient_unit_iu_natural),

    /**
     * Synthetic dl-alpha-tocopherol, counted as its whole mass: 0.9 mg an IU.
     *
     * Only half of that mass is the 2R form the daily figure counts (0.45 mg an IU), but both upper
     * levels count every form of alpha-tocopherol. One stored number cannot serve both, and the
     * whole mass is the one that cannot make a large dose look safe. The cost is on the other side:
     * a synthetic dose counts double against the 15 mg daily figure, which matters only near it — a
     * multivitamin's 30 IU is 13.5 mg of the 2R form and is stored as 27 mg.
     */
    VITAMIN_E_IU_SYNTHETIC(Nutrient.VITAMIN_E, 0.9, R.string.nutrient_unit_iu_synthetic),
}

private val CONTENT_ENTRY = Regex("""([A-Z0-9_]+)=([0-9]+(?:\.[0-9]+)?)""")

/**
 * A course's [Medication.contents] read back: each nutrient the label states, in its own unit.
 *
 * A name this build does not know is skipped rather than guessed at — a backup from a newer version
 * may carry one — and a zero is dropped, because "0 µg of vitamin D" on a label nobody typed is an
 * empty field, not a statement.
 */
fun contentsOf(text: String): Map<Nutrient, Double> = CONTENT_ENTRY.findAll(text).mapNotNull { match ->
    val nutrient = Nutrient.entries.firstOrNull { it.name == match.groupValues[1] } ?: return@mapNotNull null
    // Never by a call that throws, whatever the pattern says it took. See IcuRegexTest.
    match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0.0 }?.let { nutrient to it }
}.toMap()

/**
 * The way back, in declaration order. Plain decimals only: `Double.toString()` writes 0.0001 as
 * `1.0E-4`, which [contentsOf] would not read, and a formatter in the phone's locale writes a comma.
 */
fun contentsText(contents: Map<Nutrient, Double>): String = contents.entries
    .filter { it.value > 0.0 }
    .sortedBy { it.key.ordinal }
    .joinToString(";") { (nutrient, amount) ->
        "${nutrient.name}=${java.math.BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()}"
    }
