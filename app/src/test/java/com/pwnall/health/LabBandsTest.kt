package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The edges of the reference bands: which side a marker has, what the row prints for it, and what a
 * reading with no band at all is allowed to mean. [LabsTest] holds the advice these feed.
 */
class LabBandsTest {
    private val today = LocalDate.parse("2026-08-08")
    private val currentYear = 2026

    private fun profile() =
        UserProfile(sex = "MALE", weightKg = 75.0, heightCm = 180, birthDate = "1976-06-15")

    private fun reading(
        marker: LabMarker,
        value: Double,
        sex: String = "MALE",
        fasting: Boolean = true,
        age: Int = 50,
    ) = LabReading(
        marker = marker,
        value = value,
        occurredAt = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        day = today.toString(),
        fasting = fasting,
        ageYears = age,
        status = marker.status(value, sex, age, fasting),
    )

    /** A reading with no band is neither inside nor outside, and every count leaves it out of both. */
    @Test
    fun aReadingWithoutABandIsNeitherInsideNorOutside() {
        val fedInsulin = reading(LabMarker.INSULIN, 40.0, fasting = false)
        val inside = reading(LabMarker.GLUCOSE, 5.4)
        val far = reading(LabMarker.GLUCOSE, 7.4)

        assertNull(fedInsulin.status)
        assertFalse(fedInsulin.outside)
        assertFalse(inside.outside)
        assertTrue(far.outside)
        assertEquals(LabBandTally(inside = 1, graded = 2), labBandTally(listOf(fedInsulin, inside, far)))
        assertNull(labsAdvice(listOf(fedInsulin), "MALE", false, today))
        val quiet = (0 until 7).map { DaySteps(today.minusDays(it.toLong()).toString(), 3_000) }
        val fedHoma = reading(LabMarker.HOMA_IR, 5.0, fasting = false)
        assertNull(activityLabAdvice(listOf(fedHoma), quiet, profile(), currentYear, today))
    }

    /**
     * What the strip says aloud follows the band's edges, not only the readings: a NORMAL under a
     * ceiling alone is "not above", never "inside", and a row with no band gets the figures alone.
     */
    @Test
    fun theSpokenTrendNamesTheSideTheBandHas() {
        val band = { marker: LabMarker, fasting: Boolean -> marker.bandFor("MALE", 50, fasting) }
        val glucose = listOf(reading(LabMarker.GLUCOSE, 5.4))
        assertEquals(R.string.labs_trend_inside, labTrendSummaryRes(glucose, band(LabMarker.GLUCOSE, true)))
        val alt = listOf(reading(LabMarker.ALT, 20.0))
        assertEquals(R.string.labs_trend_not_above, labTrendSummaryRes(alt, band(LabMarker.ALT, true)))
        val hdl = listOf(reading(LabMarker.HDL, 1.4))
        assertEquals(R.string.labs_trend_not_below, labTrendSummaryRes(hdl, band(LabMarker.HDL, true)))
        val fedInsulin = listOf(reading(LabMarker.INSULIN, 12.0, fasting = false))
        assertEquals(R.string.labs_trend_plain, labTrendSummaryRes(fedInsulin, band(LabMarker.INSULIN, false)))
        val outside = listOf(reading(LabMarker.ALT, 20.0), reading(LabMarker.ALT, 45.0))
        assertEquals(R.string.labs_trend_outside, labTrendSummaryRes(outside, band(LabMarker.ALT, true)))
    }

    /** Which side a band has is what the colour and the sentence both ask. */
    @Test
    fun aBandKnowsWhichEdgesItHas() {
        val ferritin = LabMarker.FERRITIN.bandFor("MALE", 50, fasting = true)!!
        val alt = LabMarker.ALT.bandFor("MALE", 50, fasting = true)!!
        val hdl = LabMarker.HDL.bandFor("MALE", 50, fasting = true)!!
        assertTrue(ferritin.twoEdged)
        assertTrue(alt.hasCeiling && !alt.hasFloor && !alt.twoEdged)
        assertTrue(hdl.hasFloor && !hdl.hasCeiling && !hdl.twoEdged)
    }

    /** The range prints the last figure inside, not the edge the column would colour red. */
    @Test
    fun theRangePrintsTheLastValueInsideNotTheEdge() {
        // ACG's 33 and 25 U/L are themselves inside, and the range ends on them.
        assertEquals(33.0, LabMarker.ALT.insideTop(checkNotNull(LabMarker.ALT.band.high), imperialUnits = false), 1e-9)
        val femaleAlt = checkNotNull(LabMarker.ALT.femaleBand?.high)
        assertEquals(25.0, LabMarker.ALT.insideTop(femaleAlt, imperialUnits = false), 1e-9)
        assertEquals(6.0, LabMarker.GLUCOSE.insideTop(6.1, imperialUnits = false), 1e-9)
        assertEquals(5.6, LabMarker.HBA1C.insideTop(5.7, imperialUnits = false), 1e-9)
        // 6.1 mmol/L is 109.9 mg/dL: the last whole milligram inside is 109, and 110 is over.
        assertEquals(109.0, LabMarker.GLUCOSE.insideTop(6.1, imperialUnits = true), 1e-9)
        // 1.2 mmol/L of HDL rounds to 46 mg/dL, and 46 stored back is 1.19 — below. 47 is inside.
        assertEquals(47.0, LabMarker.HDL.insideBottom(1.2, imperialUnits = true), 1e-9)
        assertEquals(3.9, LabMarker.GLUCOSE.insideBottom(3.9, imperialUnits = false), 1e-9)
    }

    /**
     * And the promise that keeps the printed range and the colour in step, for every marker, both
     * sexes, fed and fasting, in both unit systems: the figure printed as the top grades inside, the
     * next printable step grades outside, and the same at the floor.
     */
    @Test
    fun everyPrintedEdgeIsInsideTheBandAndTheNextStepIsNot() {
        val people = listOf("MALE", "FEMALE").flatMap { sex -> listOf(8, 30, 55, 75).map { age -> sex to age } }
        val samples = listOf(true, false).flatMap { fasting ->
            listOf(false, true).map { imperial -> fasting to imperial }
        }
        LabMarker.entries.forEach { marker ->
            people.forEach { (sex, age) ->
                samples.forEach { (fasting, imperial) -> assertPrintedEdgesInside(marker, sex, age, fasting, imperial) }
            }
        }
    }

    private fun assertPrintedEdgesInside(
        marker: LabMarker,
        sex: String,
        age: Int,
        fasting: Boolean,
        imperial: Boolean,
    ) {
        val band = marker.bandFor(sex, age, fasting) ?: return
        val where = "${marker.name} $sex $age fasting=$fasting imperial=$imperial"
        fun status(printed: Double) = marker.status(marker.stored(printed, imperial), sex, age, fasting)
        (band.high ?: band.veryHigh)?.let { edge ->
            val top = marker.insideTop(edge, imperial)
            val next = marker.insideBottom(edge, imperial)
            assertEquals(where, LabStatus.NORMAL, status(top))
            assertTrue(where, status(next) in setOf(LabStatus.ABOVE, LabStatus.FAR_ABOVE))
        }
        (band.low ?: band.veryLow)?.let { edge ->
            val bottom = marker.insideBottom(edge, imperial)
            val step = bottom - marker.insideTop(edge, imperial)
            assertEquals(where, LabStatus.NORMAL, status(bottom))
            assertTrue(where, status(bottom - step) in setOf(LabStatus.BELOW, LabStatus.FAR_BELOW))
        }
    }

    /**
     * The figure beside the range, and not only the range: for every marker, both sexes, fed and
     * fasting, a child and three adults, in both unit systems, a reading typed just either side of
     * every edge prints on the side of the printed range its verdict names, never as zero, and never
     * a whole printed step or more from what was stored.
     *
     * Rounding every figure to a tenth while the range beside it names the last step inside would
     * print a glucose of 6.08 as 6.1 under "3.9 – 6" and "inside the band", and a suppressed TSH of
     * 0.035 as 0.0.
     */
    @Test
    fun everyFigureNearAnEdgePrintsOnTheSideItsVerdictNames() {
        val people = listOf("MALE", "FEMALE").flatMap { sex -> listOf(8, 30, 55, 75).map { age -> sex to age } }
        val samples = listOf(true, false).flatMap { fasting ->
            listOf(false, true).map { imperial -> fasting to imperial }
        }
        LabMarker.entries.forEach { marker ->
            people.forEach { (sex, age) ->
                samples.forEach { (fasting, imperial) -> assertEdgesPrintTrue(marker, sex, age, fasting, imperial) }
            }
        }
    }

    private fun assertEdgesPrintTrue(marker: LabMarker, sex: String, age: Int, fasting: Boolean, imperial: Boolean) =
        valuesNearEdges(marker, sex, age, fasting, imperial).forEach {
            assertPrintsTrue(marker, it, sex, age, fasting, imperial)
        }

    /**
     * Figures either side of every edge of the band: typed in the stored unit to the form's three
     * decimals, and — where the profile reads another unit — typed in that one, a few steps either side.
     */
    private fun valuesNearEdges(
        marker: LabMarker,
        sex: String,
        age: Int,
        fasting: Boolean,
        imperial: Boolean,
    ): List<Double> {
        val band = marker.bandFor(sex, age, fasting) ?: return emptyList()
        val edges = listOfNotNull(band.high, band.veryHigh, band.low, band.veryLow)
        val offsets = listOf(0.0, 0.001, 0.004, 0.005, 0.006, 0.01, 0.04, 0.05, 0.06, 0.1, 0.5, 1.0)
        val typed = edges.flatMap { edge -> offsets.flatMap { listOf(edge - it, edge + it) } }
            .map { Math.round(it * 1000.0) / 1000.0 }
        val step = Math.pow(10.0, -marker.decimals(true).toDouble())
        val converted = edges.takeIf { imperial && marker.unit(true) != null }.orEmpty().flatMap { edge ->
            val at = Math.round(edge * marker.factor(true) / step) * step
            (-2..2).map { marker.stored(at + it * step, true) }
        }
        return (typed + converted).filter { it in marker.valid }
    }

    private fun assertPrintsTrue(
        marker: LabMarker,
        value: Double,
        sex: String,
        age: Int,
        fasting: Boolean,
        imperial: Boolean,
    ) {
        val band = marker.bandFor(sex, age, fasting) ?: return
        val reading = reading(marker, value, sex, fasting, age)
        val places = marker.printPlaces(listOf(reading), sex, imperial)
        val printed = marker.printed(reading, sex, imperial, places).toDouble()
        val (bottom, top) = marker.printedEdges(band, imperial, places).let { (low, high) ->
            low?.toDouble() to high?.toDouble()
        }
        val where = "${marker.name} $value $sex $age fasting=$fasting imperial=$imperial: " +
            "printed $printed at $places places, range $bottom – $top, verdict ${reading.status}"
        assertTrue(where, printed != 0.0)
        val step = Math.pow(10.0, -places.toDouble())
        assertTrue(where, abs(printed - value * marker.factor(imperial)) < step)
        when (reading.status) {
            LabStatus.NORMAL -> {
                assertTrue(where, bottom == null || printed >= bottom)
                assertTrue(where, top == null || printed <= top)
            }

            LabStatus.ABOVE, LabStatus.FAR_ABOVE -> assertTrue(where, top != null && printed > top)

            LabStatus.BELOW, LabStatus.FAR_BELOW -> assertTrue(where, bottom != null && printed < bottom)

            null -> Unit
        }
        // The far edges are not printed, but a figure rounded over one reads as the other verdict.
        band.veryHigh?.let { edge ->
            val lastInside = marker.insideTop(edge, imperial, places)
            assertEquals(where, reading.status == LabStatus.FAR_ABOVE, printed > lastInside + 1e-9)
        }
        band.veryLow?.let { edge ->
            val firstInside = marker.insideBottom(edge, imperial, places)
            assertEquals(where, reading.status == LabStatus.FAR_BELOW, printed < firstInside - 1e-9)
        }
    }

    /** The cases a tenth's rounding would misprint, as they print: the figure as typed, the range to match it. */
    @Test
    fun theFiguresPrintAsTyped() {
        val glucose = reading(LabMarker.GLUCOSE, 6.08)
        val glucosePlaces = LabMarker.GLUCOSE.printPlaces(listOf(glucose), "MALE", imperialUnits = false)
        assertEquals("6.08", LabMarker.GLUCOSE.printed(glucose, "MALE", imperialUnits = false, places = glucosePlaces))
        assertEquals(
            "3.9" to "6.09",
            LabMarker.GLUCOSE.printedEdges(LabMarker.GLUCOSE.band, imperialUnits = false, places = glucosePlaces),
        )
        val tsh = reading(LabMarker.TSH, 0.035)
        val tshPlaces = LabMarker.TSH.printPlaces(listOf(tsh), "MALE", imperialUnits = false)
        assertEquals(LabStatus.FAR_BELOW, tsh.status)
        assertEquals("0.035", LabMarker.TSH.printed(tsh, "MALE", imperialUnits = false, places = tshPlaces))
        // A figure that needs nothing finer keeps the sheet's usual step.
        val plain = reading(LabMarker.GLUCOSE, 5.4)
        assertEquals(1, LabMarker.GLUCOSE.printPlaces(listOf(plain), "MALE", imperialUnits = false))
        assertEquals("5.4", LabMarker.GLUCOSE.printed(5.4, imperialUnits = false))
        assertEquals("32", LabMarker.ALT.printed(32.0, imperialUnits = false))
    }

    /**
     * A figure typed in mg/dL is stored in µmol/L with the conversion's decimals: 1.3 mg/dL of
     * creatinine is 114.92 µmol/L, which printed "as typed" would read 114.92 on a scale a laboratory prints
     * whole. A figure typed in the stored unit keeps its own decimals, as the TSH above does.
     */
    @Test
    fun aFigureConvertedOnTheWayInIsNotPrintedAsTyped() {
        val stored = LabMarker.CREATININE.stored(1.3, imperialUnits = true)
        assertEquals(114.92, stored, 1e-9)
        assertEquals("115", LabMarker.CREATININE.printed(stored, imperialUnits = false))
        assertEquals("1.3", LabMarker.CREATININE.printed(stored, imperialUnits = true))
        assertEquals("6.08", LabMarker.GLUCOSE.printed(6.08, imperialUnits = false))
    }

    /**
     * Under 18 glucose and HbA1c keep the adult band, prediabetes line included: ISPAD 2024 lists the
     * same lines for the young. A band cut to the diabetes line alone would call a fifteen-year-old's
     * fasting 6.5 mmol/L and an HbA1c of 6.2% normal. The row says the band is an adult one instead.
     */
    @Test
    fun aMinorsGlucoseAndHba1cKeepTheAdultLines() {
        listOf(LabMarker.GLUCOSE, LabMarker.HBA1C).forEach { marker ->
            listOf(8, 15, 17).forEach { age ->
                assertEquals("$marker at $age", marker.band, marker.bandFor("MALE", age, fasting = true))
                assertTrue("$marker at $age", marker.adultBandAt(age))
            }
            assertFalse(marker.adultBandAt(18))
        }
        assertEquals(LabStatus.ABOVE, reading(LabMarker.GLUCOSE, 6.5, age = 15).status)
        assertEquals(LabStatus.ABOVE, reading(LabMarker.HBA1C, 6.2, age = 15).status)
        assertFalse(LabMarker.ALP.adultBandAt(15))
        assertNull(reading(LabMarker.ALP, 260.0, age = 15).status)
    }

    /**
     * Two results of one marker share a precision, so they can share a range: a fasting
     * triglyceride above its band beside a fed one inside its own, each on its own side.
     */
    @Test
    fun resultsPrintedTogetherShareOnePrecision() {
        val fasting = reading(LabMarker.TRIGLYCERIDES, 1.8)
        val fed = reading(LabMarker.TRIGLYCERIDES, 1.95, fasting = false)
        val places = LabMarker.TRIGLYCERIDES.printPlaces(listOf(fed, fasting), "MALE", imperialUnits = false)
        assertEquals(2, places)
        assertEquals("1.95", LabMarker.TRIGLYCERIDES.printed(1.95, imperialUnits = false, places = places))
        val fedBand = LabMarker.TRIGLYCERIDES.bandFor("MALE", 50, fasting = false)!!
        assertEquals(null to "1.99", LabMarker.TRIGLYCERIDES.printedEdges(fedBand, false, places))
    }

    /**
     * The ceiling is WHO 2020's: overload risk above 200 µg/L in men and 150 in women, so a woman's
     * 180 — over the published line — reads as raised.
     */
    @Test
    fun ferritinsCeilingIsTheWhoOverloadThreshold() {
        assertEquals(LabStatus.ABOVE, LabMarker.FERRITIN.status(180.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.FERRITIN.status(140.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.FERRITIN.status(180.0, "MALE", 50, fasting = true))
        assertEquals(LabStatus.ABOVE, LabMarker.FERRITIN.status(210.0, "MALE", 50, fasting = true))
    }

    /**
     * A ceiling its source writes as "above 200", or as an interval that ends at 120, leaves the round
     * figure inside. Were ferritin's 200 and 150 and alkaline phosphatase's 120 the first figures
     * outside, as a [LabBand.high] is, a sheet's ferritin of exactly 200 would be graded raised under a
     * range printed as "30 – 199", and the report would say of it "above 199, WHO's upper edge".
     */
    @Test
    fun aCeilingItsSourceWritesAsAboveKeepsTheRoundFigureInside() {
        fun ferritin(value: Double, sex: String) = LabMarker.FERRITIN.status(value, sex, 40, fasting = true)
        assertEquals(LabStatus.NORMAL, ferritin(200.0, "MALE"))
        assertEquals(LabStatus.ABOVE, ferritin(201.0, "MALE"))
        assertEquals(LabStatus.NORMAL, ferritin(150.0, "FEMALE"))
        assertEquals(LabStatus.ABOVE, ferritin(151.0, "FEMALE"))
        assertEquals(LabStatus.NORMAL, LabMarker.ALP.status(120.0, "MALE", 40, fasting = true))
        assertEquals(LabStatus.ABOVE, LabMarker.ALP.status(121.0, "MALE", 40, fasting = true))

        val men = LabMarker.FERRITIN.bandFor("MALE", 40, fasting = true)!!
        val women = LabMarker.FERRITIN.bandFor("FEMALE", 40, fasting = true)!!
        assertEquals("30" to "200", LabMarker.FERRITIN.printedEdges(men, imperialUnits = false, places = 0))
        assertEquals("30" to "150", LabMarker.FERRITIN.printedEdges(women, imperialUnits = false, places = 0))
        assertEquals("30" to "120", LabMarker.ALP.printedEdges(LabMarker.ALP.band, imperialUnits = false, places = 0))
    }

    /**
     * WHO 2024's haemoglobin cutoffs climb through childhood: 105 g/L at 6–23 months, 110 at 24–59,
     * 115 at 5–11 years, 120 at 12–14. A flat 120 for everyone under 15 would call a healthy toddler's
     * 108 anaemic and let an eight-year-old's 113 pass.
     */
    @Test
    fun aChildsHaemoglobinIsReadAgainstItsOwnAge() {
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(108.0, "MALE", 1, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.HEMOGLOBIN.status(108.0, "MALE", 3, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(112.0, "FEMALE", 3, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.HEMOGLOBIN.status(113.0, "MALE", 8, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(117.0, "MALE", 8, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.HEMOGLOBIN.status(118.0, "FEMALE", 13, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(121.0, "MALE", 14, fasting = true))
        // From 15 the adult bands take over, and they part by sex.
        assertEquals(LabStatus.BELOW, LabMarker.HEMOGLOBIN.status(125.0, "MALE", 15, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(125.0, "FEMALE", 15, fasting = true))
    }

    /**
     * The verdict on a whole figure lying exactly on an edge, for the markers printed whole whose
     * source says which side the edge itself is on: WHO's "above 200" and "below 130", EASL's "above
     * 45%", IOM's "under 30" and "above 125", NICE's 133–258 pmol/L as indeterminate, KDIGO's G3a
     * from under 60, ACG's ALT up to 33 and 25 U/L, ESC's Lp(a) above 125, and an interval a
     * laboratory prints whole, ALP's 30–120 and the count's 150–450. The property tests only keep the
     * printed range in step with the verdict; this keeps the verdict on the side the source names.
     * The platelets' 450 is inside, and so are an ALT of 33 and a woman's of 25.
     */
    @Test
    fun aWholeFigureOnAnEdgeIsGradedOnTheSideItsSourceNames() {
        val cases = listOf(
            Triple(LabMarker.ALT, "MALE", listOf(33.0 to N, 34.0 to A, 99.0 to A, 100.0 to FA)),
            Triple(LabMarker.ALT, "FEMALE", listOf(25.0 to N, 26.0 to A, 75.0 to A, 76.0 to FA)),
            Triple(LabMarker.LP_A, "MALE", listOf(74.0 to N, 75.0 to A, 125.0 to A, 126.0 to FA)),
            Triple(LabMarker.FERRITIN, "MALE", listOf(200.0 to N, 201.0 to A, 30.0 to N, 29.0 to B, 15.0 to B)),
            Triple(LabMarker.FERRITIN, "FEMALE", listOf(150.0 to N, 151.0 to A, 14.0 to FB)),
            Triple(LabMarker.ALP, "MALE", listOf(120.0 to N, 121.0 to A, 30.0 to N, 29.0 to B)),
            Triple(LabMarker.PLATELETS, "MALE", listOf(450.0 to N, 451.0 to A, 150.0 to N, 149.0 to B)),
            Triple(LabMarker.HEMOGLOBIN, "MALE", listOf(130.0 to N, 129.0 to B)),
            Triple(LabMarker.HEMOGLOBIN, "FEMALE", listOf(120.0 to N, 119.0 to B)),
            Triple(LabMarker.TSAT, "MALE", listOf(50.0 to N, 51.0 to A, 20.0 to N, 19.0 to B)),
            Triple(LabMarker.TSAT, "FEMALE", listOf(45.0 to N, 46.0 to A)),
            Triple(LabMarker.VITAMIN_D, "MALE", listOf(125.0 to N, 126.0 to A, 375.0 to A, 376.0 to FA)),
            Triple(LabMarker.VITAMIN_D, "MALE", listOf(50.0 to N, 49.0 to B, 30.0 to B, 29.0 to FB)),
            Triple(LabMarker.B12, "MALE", listOf(259.0 to N, 258.0 to B, 133.0 to B, 132.0 to FB)),
            Triple(LabMarker.EGFR, "MALE", listOf(60.0 to N, 59.0 to B, 30.0 to B, 29.0 to FB)),
        )
        cases.forEach { (marker, sex, figures) ->
            figures.forEach { (figure, expected) ->
                assertEquals("$marker $sex $figure", expected, marker.status(figure, sex, 40, fasting = true))
            }
        }
    }

    private companion object {
        val N = LabStatus.NORMAL
        val A = LabStatus.ABOVE
        val FA = LabStatus.FAR_ABOVE
        val B = LabStatus.BELOW
        val FB = LabStatus.FAR_BELOW
    }
}
