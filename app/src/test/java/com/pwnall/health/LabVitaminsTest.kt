package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The vitamin and mineral markers, transferrin saturation, and the field that asks which unit a
 * figure was typed in. The anchors are the published lines each band is written from, typed as a
 * sheet prints them in either unit; [LabBandsTest] already sweeps every marker's printed edges.
 */
class LabVitaminsTest {
    private val today = LocalDate.parse("2026-08-08")
    private val profile = UserProfile(sex = "MALE", weightKg = 75.0, heightCm = 180, birthDate = "1976-06-15")

    private fun at(day: LocalDate): Long = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun reading(marker: LabMarker, value: Double, sex: String = "MALE", age: Int = 40) = LabReading(
        marker = marker,
        value = value,
        occurredAt = at(today),
        day = today.toString(),
        fasting = true,
        ageYears = age,
        status = marker.status(value, sex, age, fasting = true),
    )

    /** A figure typed in the stored unit, graded for an adult man. */
    private fun si(marker: LabMarker, typed: Double) = marker.status(typed, "MALE", 40, fasting = true)

    /** The same figure typed in the field's other unit. */
    private fun other(marker: LabMarker, typed: Double) =
        marker.status(marker.storedIn(typed, marker.otherUnit), "MALE", 40, fasting = true)

    /**
     * IOM 2011: under 30 nmol/L (12 ng/mL) the risk of deficiency, 50 (20) adequate, above 125 (50)
     * the concern about adverse effects; above 375 (150) the toxicity hallmark. At ODS's 2.5 each
     * ng/mL line lands on the side the IOM put it, 20 and 50 included.
     */
    @Test
    fun vitaminDLandsOnTheIomLinesInBothUnits() {
        val d = LabMarker.VITAMIN_D
        assertEquals(LabStatus.FAR_BELOW, si(d, 29.9))
        assertEquals(LabStatus.BELOW, si(d, 30.0))
        assertEquals(LabStatus.BELOW, si(d, 49.9))
        assertEquals(LabStatus.NORMAL, si(d, 50.0))
        assertEquals(LabStatus.NORMAL, si(d, 125.0))
        assertEquals(LabStatus.ABOVE, si(d, 126.0))
        assertEquals(LabStatus.ABOVE, si(d, 375.0))
        assertEquals(LabStatus.FAR_ABOVE, si(d, 376.0))

        assertEquals(LabStatus.FAR_BELOW, other(d, 11.0))
        assertEquals(LabStatus.BELOW, other(d, 12.0))
        assertEquals(LabStatus.BELOW, other(d, 19.0))
        assertEquals(LabStatus.NORMAL, other(d, 20.0))
        assertEquals(LabStatus.NORMAL, other(d, 50.0))
        assertEquals(LabStatus.ABOVE, other(d, 51.0))
        assertEquals(LabStatus.ABOVE, other(d, 150.0))
        assertEquals(LabStatus.FAR_ABOVE, other(d, 151.0))

        assertEquals("50" to "125", d.printedEdges(d.band, imperialUnits = false, places = 0))
        assertEquals("20" to "50", d.printedEdges(d.band, imperialUnits = true, places = 1))
    }

    /**
     * NICE NG239: under 180 ng/L (133 pmol/L) confirmed, 180 to 350 (133 to 258) indeterminate, over
     * 350 unlikely. 180 and 350 are themselves indeterminate, in both units.
     */
    @Test
    fun b12LandsOnNicesLinesInBothUnits() {
        val b12 = LabMarker.B12
        assertEquals(LabStatus.FAR_BELOW, other(b12, 179.0))
        assertEquals(LabStatus.BELOW, other(b12, 180.0))
        assertEquals(LabStatus.BELOW, other(b12, 350.0))
        assertEquals(LabStatus.NORMAL, other(b12, 351.0))
        assertEquals(LabStatus.FAR_BELOW, si(b12, 132.0))
        assertEquals(LabStatus.BELOW, si(b12, 133.0))
        assertEquals(LabStatus.BELOW, si(b12, 258.0))
        assertEquals(LabStatus.NORMAL, si(b12, 259.0))
        // No ceiling: NICE draws none, and a high B12 is not called high.
        assertEquals(LabStatus.NORMAL, si(b12, 2_000.0))
        assertEquals("259" to null, b12.printedEdges(b12.band, imperialUnits = false, places = 0))
        assertEquals("351" to null, b12.printedEdges(b12.band, imperialUnits = true, places = 0))
    }

    /** NICE writes for over-16s and the app grades from 18: a fifteen-year-old's B12 is a figure alone. */
    @Test
    fun b12HasNoBandUnderSixteen() {
        listOf(8, 15).forEach { age ->
            assertNull(LabMarker.B12.bandFor("FEMALE", age, fasting = true))
            assertNull(reading(LabMarker.B12, 150.0, age = age).status)
        }
        assertEquals(R.string.labs_note_adult_band, LabMarker.B12.withoutBandRes)
        assertNotNull(LabMarker.B12.bandFor("FEMALE", 18, fasting = true))
    }

    /**
     * WHO 2015: under 10 nmol/L by homocysteine, under 6.8 (3 ng/mL) deficiency by macrocytic
     * anaemia. At WHO's 2.265 a sheet's 3.0 ng/mL is 6.795, still "possible deficiency", and its 4.5
     * is the first tenth of a ng/mL at or over 10 nmol/L.
     */
    @Test
    fun folateLandsOnWhosLinesInBothUnits() {
        val folate = LabMarker.FOLATE
        assertEquals(LabStatus.FAR_BELOW, si(folate, 6.7))
        assertEquals(LabStatus.BELOW, si(folate, 6.8))
        assertEquals(LabStatus.BELOW, si(folate, 9.9))
        assertEquals(LabStatus.NORMAL, si(folate, 10.0))
        assertEquals(LabStatus.FAR_BELOW, other(folate, 2.9))
        assertEquals(LabStatus.BELOW, other(folate, 3.0))
        assertEquals(LabStatus.BELOW, other(folate, 4.4))
        assertEquals(LabStatus.NORMAL, other(folate, 4.5))
        assertEquals(LabStatus.NORMAL, si(folate, 60.0))
        assertEquals("4.5" to null, folate.printedEdges(folate.band, imperialUnits = true, places = 1))
    }

    /**
     * Pathology Harmony's 0.7–1.0 mmol/L, with 1.7 mg/dL — 0.6994 mmol/L — and 1.00 inside, and a
     * figure printed to the hundredth: 0.66 is not "0.7" beside a range that starts at 0.7.
     */
    @Test
    fun magnesiumIsReadAndPrintedToTheHundredth() {
        val mg = LabMarker.MAGNESIUM
        assertEquals(LabStatus.BELOW, si(mg, 0.69))
        assertEquals(LabStatus.NORMAL, si(mg, 0.70))
        assertEquals(LabStatus.NORMAL, si(mg, 1.00))
        assertEquals(LabStatus.ABOVE, si(mg, 1.01))
        assertEquals(LabStatus.BELOW, other(mg, 1.6))
        assertEquals(LabStatus.NORMAL, other(mg, 1.7))
        assertEquals(LabStatus.NORMAL, other(mg, 2.4))
        assertEquals(LabStatus.ABOVE, other(mg, 2.5))

        assertEquals(2, mg.decimals(imperialUnits = false))
        val low = reading(mg, 0.66)
        val places = mg.printPlaces(listOf(low), "MALE", imperialUnits = false)
        assertEquals(2, places)
        assertEquals("0.66", mg.printed(low, "MALE", imperialUnits = false, places = places))
        assertEquals("0.7" to "1", mg.printedEdges(mg.band, imperialUnits = false, places = places))
        assertEquals("1.7" to "2.4", mg.printedEdges(mg.band, imperialUnits = true, places = 1))
        // Converted on the way in, rounded to the unit's own step rather than to the conversion's.
        assertEquals("0.7", mg.printed(mg.storedIn(1.7, mg.otherUnit), imperialUnits = false))
        assertEquals("0.85", mg.printed(0.85, imperialUnits = false))
    }

    /**
     * BSG 2021's 20% floor; EASL 2022's ceilings, above 45% for women and above 50% for men and
     * postmenopausal women, which the app reads as fifty and over. A sheet that prints a decimal is
     * over the line as soon as it passes it: 45.3 for a woman of forty, 50.4 for a man.
     */
    @Test
    fun transferrinSaturationMovesForWomenAtFifty() {
        val tsat = LabMarker.TSAT
        assertEquals(LabStatus.BELOW, si(tsat, 19.0))
        assertEquals(LabStatus.NORMAL, si(tsat, 20.0))
        assertEquals(LabStatus.NORMAL, si(tsat, 50.0))
        assertEquals(LabStatus.ABOVE, si(tsat, 51.0))

        assertEquals(LabStatus.NORMAL, tsat.status(45.0, "FEMALE", 40, fasting = true))
        assertEquals(LabStatus.ABOVE, tsat.status(45.3, "FEMALE", 40, fasting = true))
        assertEquals(LabStatus.ABOVE, tsat.status(46.0, "FEMALE", 40, fasting = true))
        assertEquals(LabStatus.ABOVE, si(tsat, 50.4))
        assertEquals(LabStatus.ABOVE, tsat.status(50.4, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.ABOVE, tsat.status(48.0, "FEMALE", 49, fasting = true))
        assertEquals(LabStatus.NORMAL, tsat.status(48.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.ABOVE, tsat.status(51.0, "FEMALE", 50, fasting = true))
        // Only the ceiling moves, and only for women.
        assertEquals(tsat.bandFor("MALE", 30, true), tsat.bandFor("MALE", 70, true))
        assertEquals(LabStatus.BELOW, tsat.status(19.0, "FEMALE", 60, fasting = true))

        val young = tsat.bandFor("FEMALE", 49, fasting = true)!!
        assertEquals("20" to "45", tsat.printedEdges(young, imperialUnits = false, places = 0))
        // The fraction is printed, and only where it is needed, so the range still reads "20 – 45".
        val over = reading(tsat, 45.3, sex = "FEMALE")
        val places = tsat.printPlaces(listOf(over), "FEMALE", imperialUnits = false)
        assertEquals("45.3", tsat.printed(over, "FEMALE", imperialUnits = false, places = places))
        assertEquals("20" to "45", tsat.printedEdges(young, imperialUnits = false, places = places))
        val older = tsat.bandFor("FEMALE", 50, fasting = true)!!
        assertEquals("20" to "50", tsat.printedEdges(older, imperialUnits = false, places = 0))
        // Typed, never derived, and no fed band: a sex band and a food band never share a marker.
        assertFalse(tsat.derived)
        assertNull(tsat.fedBand)
    }

    /**
     * The field's other unit for the two markers stored in one unit alone: a US free T4 of 1.3 ng/dL
     * is 16.7 pmol/L and normal, where taken as 1.3 pmol/L it would be graded low; a CRP of 0.5 mg/dL
     * is 5 mg/L and over the band, where taken as 0.5 mg/L it would be normal.
     */
    @Test
    fun freeT4AndCrpCanBeTypedInTheOtherUnit() {
        assertEquals(LabStatus.BELOW, si(LabMarker.FT4, 1.3))
        assertEquals(LabStatus.NORMAL, other(LabMarker.FT4, 1.3))
        assertEquals(LabStatus.BELOW, other(LabMarker.FT4, 0.9))
        assertEquals(LabStatus.ABOVE, other(LabMarker.FT4, 1.8))
        assertEquals(LabStatus.NORMAL, si(LabMarker.CRP, 0.5))
        assertEquals(LabStatus.ABOVE, other(LabMarker.CRP, 0.5))
        assertEquals(LabStatus.FAR_ABOVE, other(LabMarker.CRP, 1.1))
        // CRP keeps mg/L on every profile: its mg/dL is for typing only.
        assertEquals(R.string.labs_unit_mgl, LabMarker.CRP.unitLabelRes(imperialUnits = true))
        assertEquals(R.string.labs_unit_ngdl, LabMarker.FT4.unitLabelRes(imperialUnits = true))
    }

    /**
     * The chip's round trip: a figure typed in either unit is stored in the band's unit, reads back as
     * typed in the unit it was typed in, and the unit the panel records resolves to the same field.
     */
    @Test
    fun aFigureTypedAgainstEitherChipSurvivesTheRoundTrip() {
        val switchable = LabMarker.entries.filter { it.otherUnit != null }
        assertEquals(
            setOf(
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
                LabMarker.URINE_ACR,
                LabMarker.URINE_ALBUMIN,
                LabMarker.URINE_CREATININE,
                LabMarker.URINE_PROTEIN,
            ),
            switchable.toSet(),
        )
        switchable.forEach { marker ->
            val other = marker.otherUnit!!
            val code = marker.typedUnitCode(other)
            assertNotNull(marker.name, other.ucum)
            assertNotEquals(marker.name, marker.ucum.ucum, code)
            assertEquals(marker.name, other, marker.fieldUnit(code, imperialUnits = false))
            assertNull(marker.name, marker.fieldUnit(marker.typedUnitCode(null), imperialUnits = true))
            // Nothing recorded, or something the field does not offer: the profile's unit.
            assertEquals(marker.name, marker.unit(true), marker.fieldUnit(null, imperialUnits = true))
            assertEquals(marker.name, marker.unit(false), marker.fieldUnit("[iU]", imperialUnits = false))
            val typed = 12.3
            assertEquals(marker.name, typed, other.of(marker.storedIn(typed, other)), 1e-9)
            assertEquals(marker.name, typed, marker.storedIn(typed, null), 0.0)
        }
        // A marker that offers one unit takes the profile's whatever is recorded against it.
        assertEquals(LabMarker.NON_HDL.unit(true), LabMarker.NON_HDL.fieldUnit("mmol/L", imperialUnits = true))
        assertEquals(
            97.0,
            LabMarker.VITAMIN_D.display(LabMarker.VITAMIN_D.storedIn(97.0, LabMarker.VITAMIN_D.otherUnit), true),
            1e-9,
        )
    }

    /**
     * A sheet's 32 ng/mL of 25-hydroxyvitamin D copied under the metric setting. Typed against the
     * ng/mL chip it is 80 nmol/L and adequate; against nmol/L it would be 32 and not.
     * The line under the field says which before anything is saved.
     */
    @Test
    fun aNanogramSheetUnderTheMetricSettingIsReadAsWhatItSays() {
        val d = LabMarker.VITAMIN_D
        val ngml = d.fieldUnit("ng/mL", imperialUnits = false)
        assertEquals(80.0, d.storedIn(32.0, ngml), 1e-9)
        assertEquals(LabStatus.NORMAL, si(d, d.storedIn(32.0, ngml)))
        assertEquals(LabStatus.BELOW, si(d, d.storedIn(32.0, null)))

        assertEquals("80" to R.string.labs_unit_nmol, d.inOtherUnit(32.0, ngml))
        assertEquals("12.8" to R.string.labs_unit_ngml, d.inOtherUnit(32.0, null))
        assertEquals("0.5" to R.string.labs_unit_mgdl, LabMarker.CRP.inOtherUnit(5.0, null))
        assertEquals("133" to R.string.labs_unit_pmol, LabMarker.B12.inOtherUnit(180.0, LabMarker.B12.otherUnit))
        assertNull(LabMarker.TSH.inOtherUnit(2.0, null))
    }

    /**
     * Haemoglobin has a second unit: with no choice, a sheet's 13.9 g/dL under the metric setting
     * would be refused, since nothing its g/L field takes is a figure in g/dL. The chip
     * takes it as printed, whichever the profile reads, and the line under the field shows the other.
     */
    @Test
    fun aHaemoglobinIsReadInTheUnitItsSheetPrinted() {
        val hemoglobin = LabMarker.HEMOGLOBIN
        val gdl = hemoglobin.fieldUnit("g/dL", imperialUnits = false)
        assertEquals(hemoglobin.imperial, gdl)
        assertFalse(hemoglobin.storedIn(13.9, null) in hemoglobin.valid)
        assertEquals(139.0, hemoglobin.storedIn(13.9, gdl), 1e-9)
        assertNull(hemoglobin.fieldUnit("g/L", imperialUnits = true))
        assertEquals("g/dL", hemoglobin.typedUnitCode(gdl))

        assertEquals("139" to R.string.labs_unit_gl, hemoglobin.inOtherUnit(13.9, gdl))
        assertEquals("13.9" to R.string.labs_unit_gdl, hemoglobin.inOtherUnit(139.0, null))
    }

    /**
     * Bilirubin's two units both take 1 to 35, every ordinary result in either unit, so the bounds
     * catch nothing, and a field that took the profile's unit unasked would keep a sheet's 2.5 mg/dL,
     * raised, as 2.5 µmol/L and call it normal, and make a British sheet's 15 µmol/L under the imperial
     * setting 256 and far above. The chip decides, whichever the profile reads.
     */
    @Test
    fun aBilirubinIsReadInTheUnitItsSheetPrinted() {
        val bilirubin = LabMarker.BILIRUBIN
        assertEquals(LabStatus.NORMAL, si(bilirubin, 2.5))
        assertEquals(LabStatus.ABOVE, other(bilirubin, 2.5))
        assertEquals(LabStatus.NORMAL, si(bilirubin, 15.0))
        assertEquals(LabStatus.FAR_ABOVE, other(bilirubin, 15.0))

        assertEquals(bilirubin.imperial, bilirubin.fieldUnit("mg/dL", imperialUnits = false))
        assertNull(bilirubin.fieldUnit("umol/L", imperialUnits = true))
        assertEquals("43" to R.string.labs_unit_umol, bilirubin.inOtherUnit(2.5, bilirubin.otherUnit))
        assertEquals("0.9" to R.string.labs_unit_mgdl, bilirubin.inOtherUnit(15.0, null))
    }

    /** The unit is recorded beside the figure, and the form offers the one written down last. */
    @Test
    fun thePanelRecordsTheTypedUnitAndTheFormOffersTheLastOne() {
        fun panel(id: Long, payload: String) = JournalEntry(
            id = id,
            day = today.toString(),
            occurredAt = at(today.minusDays(id)),
            kind = KIND_LABS,
            title = "Labs",
            calories = 0.0,
            measurementPayloadJson = payload,
        )
        val first = labsPayload(
            values = mapOf(LabMarker.VITAMIN_D to 80.0, LabMarker.B12 to 258.2),
            fasting = true,
            typedUnits = mapOf(
                LabMarker.VITAMIN_D to "ng/mL",
                LabMarker.B12 to "pg/mL",
                // No figure beside it, so nothing is written for it.
                LabMarker.FOLATE to "ng/mL",
            ),
        )
        val json = JSONObject(first)
        assertEquals("ng/mL", json.getJSONObject(TYPED_UNITS_KEY).getString(LabMarker.VITAMIN_D.key))
        assertFalse(json.getJSONObject(TYPED_UNITS_KEY).has(LabMarker.FOLATE.key))
        // The figure itself is stored in the band's unit.
        assertEquals(80.0, labHistory(listOf(panel(1, first)), profile).getValue(LabMarker.VITAMIN_D).last().value, 0.0)

        val later = labsPayload(
            values = mapOf(LabMarker.VITAMIN_D to 75.0),
            fasting = true,
            typedUnits = mapOf(LabMarker.VITAMIN_D to "nmol/L"),
        )
        val strange = """{"schema":1,"b12PmolL":300,"typedUnits":{"b12PmolL":"IU"}}"""
        // Written down in this order — ids — though the draws were the other way round.
        val units = lastTypedUnits(listOf(panel(3, strange), panel(2, later), panel(1, first)))
        assertEquals(mapOf(LabMarker.VITAMIN_D to "nmol/L", LabMarker.B12 to "pg/mL"), units)
        assertTrue(lastTypedUnits(emptyList()).isEmpty())
    }

    /** The refusal line prints the bounds as the field takes them, not rounded to a tenth. */
    @Test
    fun theRefusalLinePrintsTheBoundsTheFieldTakes() {
        assertEquals("0.005" to "150", LabMarker.TSH.acceptedBounds(null))
        // 0.01 mg/dL is CRP's 0.1 mg/L floor exactly: the division's floating-point residue must not put
        // it a hair under and refuse it, or the line would name 0.02.
        assertEquals(0.1, LabMarker.CRP.storedIn(0.01, LabMarker.CRP.otherUnit), 0.0)
        assertTrue(LabMarker.CRP.storedIn(0.01, LabMarker.CRP.otherUnit) in LabMarker.CRP.valid)
        assertEquals("0.01" to "50", LabMarker.CRP.acceptedBounds(LabMarker.CRP.otherUnit))
        assertEquals("0.1" to "5", LabMarker.MAGNESIUM.acceptedBounds(null))
    }

    /** The one sentence the section has names the edge its row prints, and the body behind it. */
    @Test
    fun theAdviceNamesThePrintedEdgeAndItsSource() {
        val b12 = labsAdvice(listOf(reading(LabMarker.B12, 200.0)), "MALE", false, today)
        assertEquals(PrintedFigure("259"), b12?.args?.get(3))
        assertEquals(R.string.labs_band_source_b12, b12?.sourceRes)
        val alt = labsAdvice(listOf(reading(LabMarker.ALT, 45.0)), "MALE", false, today)
        assertEquals(PrintedFigure("33"), alt?.args?.get(3))
        // A far reading is measured against where the band ends, which is what the words say.
        val ferritin = labsAdvice(listOf(reading(LabMarker.FERRITIN, 12.0)), "MALE", false, today)
        assertEquals(PrintedFigure("30"), ferritin?.args?.get(3))
        val vitaminD = labsAdvice(listOf(reading(LabMarker.VITAMIN_D, 400.0)), "MALE", true, today)
        assertEquals(PrintedFigure("50"), vitaminD?.args?.get(3))
        // A marker whose band has no named body keeps the generic line.
        val urea = labsAdvice(listOf(reading(LabMarker.UREA, 9.0)), "MALE", false, today)
        assertEquals(R.string.advice_source_labs, urea?.sourceRes)
        // So does a low ferritin: the body its source line names wrote only the ceiling.
        assertEquals(R.string.advice_source_labs, ferritin?.sourceRes)
        val overloaded = labsAdvice(listOf(reading(LabMarker.FERRITIN, 250.0)), "MALE", false, today)
        assertEquals(R.string.labs_band_source_ferritin, overloaded?.sourceRes)
        val lowTsat = labsAdvice(listOf(reading(LabMarker.TSAT, 15.0)), "MALE", false, today)
        assertEquals(R.string.labs_band_source_tsat, lowTsat?.sourceRes)
    }
}
