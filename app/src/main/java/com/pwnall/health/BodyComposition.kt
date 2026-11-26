package com.pwnall.health

import kotlin.math.max

/**
 * Body fat from a scale's impedance reading.
 *
 * Bioelectrical impedance measures how hard it is for a small current to cross the body; fat
 * conducts worse than muscle, so impedance plus height, weight and sex gives an estimate. These are
 * the Sun et al. 2003 fat-free-mass regressions, one per sex. Sun rather than Kyle because Kyle
 * needs *reactance* and the Bluetooth Body Composition Service only reports resistance — the number
 * a consumer scale actually sends. The result moves with hydration, food and the time of day — it is
 * a trend line, not a measurement, and the UI has to say so.
 *
 * [ageYears] is validated but not a term: neither Sun equation takes one.
 *
 * Returns null rather than a number when the inputs cannot support one.
 */
fun bodyFatPercent(
    impedanceOhms: Int,
    weightKg: Double,
    heightCm: Int,
    ageYears: Int,
    male: Boolean,
): Double? {
    if (impedanceOhms !in 200..1_200) return null
    if (weightKg !in PLAUSIBLE_KG || heightCm !in 50..250 || ageYears !in 5..120) return null

    val heightSquaredOverResistance = heightCm.toDouble() * heightCm / impedanceOhms
    val fatFreeMass = if (male) {
        -10.68 + 0.65 * heightSquaredOverResistance + 0.26 * weightKg + 0.02 * impedanceOhms
    } else {
        -9.53 + 0.69 * heightSquaredOverResistance + 0.17 * weightKg + 0.02 * impedanceOhms
    }

    if (fatFreeMass <= 0.0 || fatFreeMass >= weightKg) return null
    val percent = (weightKg - fatFreeMass) / weightKg * 100.0
    return percent.takeIf { it in 3.0..70.0 }
}

/** Fat mass in kilograms, for the days someone wants the absolute number. */
fun fatMassKg(weightKg: Double, percent: Double): Double =
    max(0.0, weightKg) * percent.coerceIn(0.0, 100.0) / 100.0
