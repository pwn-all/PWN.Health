package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class LabsTest {
    private val today = LocalDate.parse("2026-08-08")
    private val currentYear = 2026

    private fun profile(sex: String = "MALE", weightKg: Double = 75.0, heightCm: Int = 180) =
        UserProfile(sex = sex, weightKg = weightKg, heightCm = heightCm, birthDate = "1976-06-15")

    private fun at(day: LocalDate): Long =
        day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun reading(
        marker: LabMarker,
        value: Double,
        day: LocalDate = today,
        sex: String = "MALE",
        fasting: Boolean = true,
        age: Int = 50,
    ) = LabReading(
        marker = marker,
        value = value,
        occurredAt = at(day),
        day = day.toString(),
        fasting = fasting,
        ageYears = age,
        status = marker.status(value, sex, age, fasting),
    )

    private fun meals(
        days: Int,
        sugarPerDay: Double? = null,
        saturatedPerDay: Double? = null,
        fiberPerDay: Double? = null,
        proteinPerDay: Double = 0.0,
        kcal: Double = 2_000.0,
        until: LocalDate = today,
    ) = (0 until days).map { offset ->
        val day = until.minusDays(offset.toLong())
        JournalEntry(
            day = day.toString(),
            occurredAt = at(day),
            kind = "EAT",
            title = "Meal",
            calories = kcal,
            proteinG = proteinPerDay,
            sugarG = sugarPerDay,
            saturatedFatG = saturatedPerDay,
            fiberG = fiberPerDay,
        )
    }

    /**
     * The one key that is not ours to choose: Health Connect's blood glucose import and export both
     * read it, so a rename here silently stops the marker syncing.
     */
    @Test
    fun glucoseKeepsTheHealthConnectKey() {
        assertEquals("concentrationMmolL", LabMarker.GLUCOSE.key)
    }

    /** Every key is a column name in storage: two markers sharing one would overwrite each other. */
    @Test
    fun everyMarkerHasItsOwnKey() {
        assertEquals(LabMarker.entries.size, LabMarker.entries.map { it.key }.distinct().size)
    }

    /**
     * [labHistory] skips a payload that contains no marker key as a substring before parsing it, so a
     * key hidden inside another — a marker's or one of the panel's own — would be found where it is not.
     */
    @Test
    fun noKeyIsHiddenInsideAnother() {
        val panelKeys = listOf(
            FASTING_KEY,
            SPECIMEN_KEY,
            METHOD_KEY,
            REPORTED_RANGES_KEY,
            TYPED_UNITS_KEY,
            "attachments",
            "schema",
        )
        val keys = LabMarker.entries.map { it.key }
        keys.forEach { key ->
            (keys - key + panelKeys).forEach { otherKey ->
                assertFalse("$key inside $otherKey", key in otherKey)
                assertFalse("$otherKey inside $key", otherKey in key)
            }
        }
    }

    @Test
    fun glucoseBandsFollowTheFastingThresholds() {
        assertEquals(LabStatus.NORMAL, LabMarker.GLUCOSE.status(5.4, "MALE", 50, fasting = true))
        assertEquals(LabStatus.ABOVE, LabMarker.GLUCOSE.status(6.4, "MALE", 50, fasting = true))
        assertEquals(LabStatus.FAR_ABOVE, LabMarker.GLUCOSE.status(7.2, "MALE", 50, fasting = true))
    }

    /**
     * A 7.2 an hour after lunch is an ordinary reading, which the fasting bands would call a reason
     * to see someone.
     */
    @Test
    fun aSampleAfterFoodIsReadAgainstTheRandomThreshold() {
        assertEquals(LabStatus.NORMAL, LabMarker.GLUCOSE.status(7.2, "MALE", 50, fasting = false))
        assertEquals(LabStatus.FAR_ABOVE, LabMarker.GLUCOSE.status(11.4, "MALE", 50, fasting = false))
        assertEquals(LabStatus.ABOVE, LabMarker.TRIGLYCERIDES.status(2.2, "MALE", 50, fasting = false))
        assertEquals(LabStatus.NORMAL, LabMarker.TRIGLYCERIDES.status(1.8, "MALE", 50, fasting = false))
    }

    /**
     * Insulin is held, drawn and never graded — fasting or not.
     *
     * It says nothing rather than "normal": a NORMAL after breakfast would be painted indigo and
     * counted "inside the band" while the row beside it asked for a fasting sample. It says nothing
     * on a fasting sample either, because the printed 25 µIU/mL is an assay's reference interval and
     * not a line anybody crosses into something.
     */
    @Test
    fun anUngradedMarkerIsNeverCalledHighOrLow() {
        listOf(true, false).forEach { fasting ->
            assertNull(LabMarker.INSULIN.bandFor("MALE", 50, fasting = fasting))
            assertNull(LabMarker.INSULIN.status(40.0, "MALE", 50, fasting = fasting))
            assertNull(LabMarker.HOMA_IR.status(9.0, "MALE", 50, fasting = fasting))
        }
        // And it is still recorded: refusing to grade a figure is not refusing to keep it.
        assertTrue(LabMarker.INSULIN.ungraded && LabMarker.HOMA_IR.ungraded)
        assertTrue(40.0 in LabMarker.INSULIN.valid)
    }

    /**
     * Under the metric setting a mg/dL figure is refused rather than stored: 110 is not a plausible
     * mmol/L glucose, and a silently impossible reading would drive every band and every alert.
     */
    @Test
    fun aMilligramFigureIsRefusedUnderTheMetricSetting() {
        assertEquals(false, LabMarker.GLUCOSE.stored(110.0, imperialUnits = false) in LabMarker.GLUCOSE.valid)
        assertEquals(false, LabMarker.LDL.stored(130.0, imperialUnits = false) in LabMarker.LDL.valid)
    }

    /** The same sheet under the imperial setting is the ordinary case it was printed for. */
    @Test
    fun theSameSheetIsAcceptedUnderTheImperialSetting() {
        val glucose = LabMarker.GLUCOSE.stored(110.0, imperialUnits = true)
        assertEquals(6.1, glucose, 0.05)
        assertEquals(LabStatus.ABOVE, LabMarker.GLUCOSE.status(glucose, "MALE", 50, fasting = true))
        assertEquals(1.29, LabMarker.HDL.stored(50.0, imperialUnits = true), 0.05)
        // A US sheet prints creatinine in mg/dL; 1.13 of those is the 100 µmol/L stored here.
        assertEquals(100.0, LabMarker.CREATININE.stored(1.13, imperialUnits = true), 1.0)
    }

    @Test
    fun aReadingSurvivesTheRoundTrip() {
        LabMarker.entries.forEach { marker ->
            val stored = marker.stored(97.0, imperialUnits = true)
            assertEquals(97.0, marker.display(stored, imperialUnits = true), 0.001)
            assertEquals(5.4, marker.display(5.4, imperialUnits = false), 0.001)
        }
    }

    /**
     * The markers whose conventional unit sits around one: rounding those to a whole number would
     * print a low, a normal and a raised creatinine as the same "1" and an ordinary bilirubin as "0",
     * while the units a lab really does print whole have to stay whole. Creatinine takes the two
     * decimals an American sheet prints it with: at one, its 0.95 and 1.04 would be the same "1".
     */
    @Test
    fun theConventionalUnitKeepsTheDecimalItIsPrintedWith() {
        assertEquals(1.13, LabMarker.CREATININE.display(100.0, imperialUnits = true), 0.001)
        assertEquals(1.3, LabMarker.CREATININE.display(115.0, imperialUnits = true), 0.001)
        assertEquals("0.95", LabMarker.CREATININE.printed(LabMarker.CREATININE.stored(0.95, true), true))
        assertEquals("1.04", LabMarker.CREATININE.printed(LabMarker.CREATININE.stored(1.04, true), true))
        assertEquals(0.5, LabMarker.BILIRUBIN.display(8.0, imperialUnits = true), 0.001)
        assertEquals(13.5, LabMarker.HEMOGLOBIN.display(135.0, imperialUnits = true), 0.001)
        assertEquals(7.1, LabMarker.URIC_ACID.display(420.0, imperialUnits = true), 0.001)
        assertEquals(97.0, LabMarker.GLUCOSE.display(5.4, imperialUnits = true), 0.001)
    }

    @Test
    fun hdlIsReadFromBelowAndDiffersBySex() {
        assertEquals(LabStatus.BELOW, LabMarker.HDL.status(1.1, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HDL.status(1.1, "MALE", 50, fasting = true))
    }

    /** Ferritin is the one marker read from both ends: stores can be empty or overloaded. */
    @Test
    fun ferritinIsReadFromBothEnds() {
        assertEquals(LabStatus.FAR_BELOW, LabMarker.FERRITIN.status(12.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.FERRITIN.status(20.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.FERRITIN.status(90.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.ABOVE, LabMarker.FERRITIN.status(260.0, "FEMALE", 50, fasting = true))
    }

    /**
     * The bands are written as "under 15" and "under 30", and the low edge is already read that
     * way. A reading sitting exactly on the very-low line is that level, not below it, and calling
     * it far below sends a round number typed off a sheet straight to the clinician card.
     */
    @Test
    fun aReadingExactlyOnTheVeryLowEdgeIsNotFarBelow() {
        assertEquals(LabStatus.BELOW, LabMarker.FERRITIN.status(15.0, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.FAR_BELOW, LabMarker.FERRITIN.status(14.9, "FEMALE", 50, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.EGFR.status(30.0, "MALE", 50, fasting = true))
        assertEquals(LabStatus.FAR_BELOW, LabMarker.EGFR.status(29.9, "MALE", 50, fasting = true))
    }

    /**
     * The same creatinine is an ordinary result in one body and a finding in another — which is the
     * whole reason the app derives the rate rather than showing the raw figure alone.
     */
    @Test
    fun theSameCreatinineIsADifferentFiltrationRateBySex() {
        assertEquals(79.0, ckdEpi(100.0, 50, "MALE"), 1.5)
        assertEquals(59.0, ckdEpi(100.0, 50, "FEMALE"), 1.5)
        assertEquals(LabStatus.NORMAL, LabMarker.EGFR.status(79.0, "MALE", 50, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.EGFR.status(59.0, "FEMALE", 50, fasting = true))
    }

    @Test
    fun derivedValuesFollowFromTheTypedOnes() {
        val typed = mapOf(
            LabMarker.CREATININE to reading(LabMarker.CREATININE, 100.0),
            LabMarker.CHOLESTEROL to reading(LabMarker.CHOLESTEROL, 6.0),
            LabMarker.HDL to reading(LabMarker.HDL, 1.4),
            LabMarker.GLUCOSE to reading(LabMarker.GLUCOSE, 5.4),
            LabMarker.INSULIN to reading(LabMarker.INSULIN, 15.0),
        )
        val derived = derivedLabs(typed, profile()).associateBy { it.marker }
        assertEquals(79.0, derived.getValue(LabMarker.EGFR).value, 1.5)
        assertEquals(4.6, derived.getValue(LabMarker.NON_HDL).value, 0.001)
        assertEquals(3.6, derived.getValue(LabMarker.HOMA_IR).value, 0.05)
    }

    /** HOMA-IR out of a sample drawn after lunch is arithmetic, not a measurement. */
    @Test
    fun homaNeedsBothHalvesFasting() {
        val typed = mapOf(
            LabMarker.GLUCOSE to reading(LabMarker.GLUCOSE, 5.4, fasting = false),
            LabMarker.INSULIN to reading(LabMarker.INSULIN, 15.0, fasting = true),
        )
        assertNull(derivedLabs(typed, profile()).firstOrNull { it.marker == LabMarker.HOMA_IR })
    }

    @Test
    fun theWorstResultIsTheOneReported() {
        val advice = labsAdvice(
            listOf(reading(LabMarker.CHOLESTEROL, 5.4), reading(LabMarker.GLUCOSE, 7.4)),
            "MALE",
            false,
            today,
        )
        assertEquals("labs-glucose-far_above", advice?.id)
        assertEquals(AdviceLevel.CLINICIAN, advice?.level)
    }

    @Test
    fun aResultInsideItsBandSaysNothing() {
        assertNull(labsAdvice(listOf(reading(LabMarker.GLUCOSE, 5.2)), "MALE", false, today))
    }

    /** Half a year on, a single lab result is history rather than something to act on today. */
    @Test
    fun aStaleResultIsNotAdvice() {
        val old = reading(LabMarker.GLUCOSE, 7.4, today.minusDays(200))
        assertNull(labsAdvice(listOf(old), "MALE", false, today))
    }

    /**
     * The sample date is the day of the draw, not the day the result was typed in, or a panel drawn
     * a year ago would read as today's and every window measure from the wrong end.
     */
    @Test
    fun aBackDatedPanelIsDatedByTheDraw() {
        val lastYear = reading(LabMarker.GLUCOSE, 7.4, today.minusDays(365))
        val lastMonth = reading(LabMarker.GLUCOSE, 7.4, today.minusDays(30))
        assertNull(labsAdvice(listOf(lastYear), "MALE", false, today))
        assertEquals(30, labsAdvice(listOf(lastMonth), "MALE", false, today)?.windowDays)
    }

    /** The 2000 kcal day the WHO's 50 g is quoted for, so the assertions below are written against that 50 g. */
    private val quotedDayKcal = 2_000.0

    @Test
    fun sugarAndARaisedGlucoseAreReportedTogether() {
        val advice = sugarLabAdvice(
            listOf(reading(LabMarker.GLUCOSE, 6.6)),
            meals(days = 7, sugarPerDay = 80.0),
            false,
            today,
            quotedDayKcal,
        )
        assertEquals("labs-sugar", advice?.id)
    }

    /** Urate belongs in the same rule: fructose drives it, which glucose alone would miss. */
    @Test
    fun sugarAlsoAnswersForUrate() {
        assertNotNull(
            sugarLabAdvice(
                listOf(reading(LabMarker.URIC_ACID, 470.0)),
                meals(days = 7, sugarPerDay = 80.0),
                false,
                today,
                quotedDayKcal,
            ),
        )
    }

    @Test
    fun sugarAloneIsNotTheFinding() {
        assertNull(
            sugarLabAdvice(
                listOf(reading(LabMarker.GLUCOSE, 5.2)),
                meals(days = 7, sugarPerDay = 120.0),
                false,
                today,
                quotedDayKcal,
            ),
        )
    }

    /** Three sweet days are not a fortnight's diet, however sweet they were. */
    @Test
    fun tooFewLoggedDaysStaySilent() {
        assertNull(
            sugarLabAdvice(
                listOf(reading(LabMarker.GLUCOSE, 6.6)),
                meals(days = 3, sugarPerDay = 150.0),
                false,
                today,
                quotedDayKcal,
            ),
        )
    }

    /**
     * A meal logged without its sugar column is unknown, not zero: averaging those in would let a
     * fortnight of untyped meals dilute the days that were typed until nothing is ever said.
     */
    @Test
    fun mealsWithoutASugarColumnDoNotCountAsZero() {
        val untyped = meals(days = 7)
        assertNotNull(
            sugarLabAdvice(
                listOf(reading(LabMarker.GLUCOSE, 6.6)),
                meals(days = 4, sugarPerDay = 80.0) + untyped,
                false,
                today,
                quotedDayKcal,
            ),
        )
    }

    /**
     * WHO's line is a tenth of the day's energy, and 50 g is only what that comes to at 2000 kcal.
     * On a 1200 kcal losing budget the same share is 30 g, and 40 g a day is over it there while it
     * is under a flat 50 g.
     */
    @Test
    fun theSugarLineIsAShareOfTheEnergyTarget() {
        assertEquals(50.0, sugarReferenceG(2_000.0), 1e-9)
        assertEquals(30.0, sugarReferenceG(1_200.0), 1e-9)
        assertEquals(37.5, sugarReferenceG(1_500.0), 1e-9)
        assertEquals(50.0, sugarReferenceG(null), 1e-9)
        assertEquals(50.0, sugarReferenceG(0.0), 1e-9)

        val raised = listOf(reading(LabMarker.GLUCOSE, 6.6))
        val sweet = meals(days = 7, sugarPerDay = 40.0)
        assertNull(sugarLabAdvice(raised, sweet, false, today, 2_000.0))
        val advice = sugarLabAdvice(raised, sweet, false, today, 1_200.0)
        assertEquals("labs-sugar", advice?.id)
        assertEquals("30", advice?.args?.get(2))
    }

    @Test
    fun alcoholIsNamedWhenTheWeekCarriesIt() {
        val drinks = (0 until 7).map { offset ->
            val day = today.minusDays(offset.toLong())
            JournalEntry(
                day = day.toString(),
                occurredAt = at(day),
                kind = KIND_ALCOHOL,
                title = "Alcohol",
                calories = 0.0,
                servingG = AlcoholDrink.BEER.ethanolG,
            )
        }
        assertEquals(
            "labs-alcohol",
            alcoholLabAdvice(listOf(reading(LabMarker.GGT, 70.0)), drinks, today)?.id,
        )
        assertNull(alcoholLabAdvice(listOf(reading(LabMarker.GGT, 70.0)), drinks.take(2), today))
    }

    /** A raised enzyme with nothing else attached stays with the plain band advice. */
    @Test
    fun aLiverEnzymeOnlyMeetsTheScalesWhenThereIsWeightToMeet() {
        val alt = listOf(reading(LabMarker.ALT, 45.0))
        assertNotNull(weightLiverAdvice(alt, emptyList(), profile(weightKg = 95.0), today))
        assertNull(weightLiverAdvice(alt, emptyList(), profile(weightKg = 70.0), today))
    }

    /** The weight to lose is said in the unit the profile weighs in, not in kilograms to everyone. */
    @Test
    fun theWeightToLoseIsSaidInTheProfilesUnit() {
        val alt = listOf(reading(LabMarker.ALT, 45.0))
        val metric = weightLiverAdvice(alt, emptyList(), profile(weightKg = 95.0), today)!!.args
        assertEquals(listOf("7", Labeled(R.string.unit_kg)), metric.takeLast(2))
        val pounds = weightLiverAdvice(alt, emptyList(), profile(weightKg = 95.0).copy(unitSystem = "IMPERIAL"), today)
        assertEquals(listOf("15", Labeled(R.string.unit_lb)), pounds!!.args.takeLast(2))
    }

    /**
     * The body-fat half of the question is asked of the band the vitals card grades body fat
     * against — AACE's "above 25%" for men and "above 35%" for women — not a 32 with no
     * source, which would send a woman at 33% to the weight-loss card.
     */
    @Test
    fun theLiverAdviceReadsBodyFatAgainstTheVitalsBand() {
        fun scale(percent: Double) = listOf(
            JournalEntry(
                day = today.toString(),
                occurredAt = at(today),
                kind = "MEASUREMENT",
                title = "Scale",
                calories = 0.0,
                bodyFatPercent = percent,
            ),
        )
        // BMI 20.8 for her and 21.6 for him: only the body fat can make either "fatty".
        val her = profile(sex = "FEMALE", weightKg = 60.0, heightCm = 170)
        val alt = listOf(reading(LabMarker.ALT, 45.0, sex = "FEMALE"))
        assertNull(weightLiverAdvice(alt, scale(33.0), her, today))
        assertNotNull(weightLiverAdvice(alt, scale(36.0), her, today))
        val him = profile(weightKg = 70.0, heightCm = 180)
        assertNull(weightLiverAdvice(alt, scale(25.0), him, today))
        assertNotNull(weightLiverAdvice(alt, scale(25.1), him, today))
    }

    /**
     * WHO's 25 is an adult line; a child's BMI is a percentile this app does not hold, so a raised
     * enzyme in a fifteen-year-old at a BMI of 29 is not "extra weight" and gets no advice here.
     */
    @Test
    fun aMinorIsNotToldToLoseWeightForALiverEnzyme() {
        // Graded as an adult's reading on purpose: the rule under test is the advice's own age gate,
        // not the band's, which leaves a fifteen-year-old's ALT ungraded in the first place.
        val alt = listOf(reading(LabMarker.ALT, 45.0, age = 18))
        val teen = profile(weightKg = 95.0).copy(birthDate = today.minusYears(15).toString())
        assertNull(weightLiverAdvice(alt, emptyList(), teen, today))
        val adult = profile(weightKg = 95.0).copy(birthDate = today.minusYears(18).toString())
        assertNotNull(weightLiverAdvice(alt, emptyList(), adult, today))
        assertNull(reading(LabMarker.ALT, 45.0, age = 15).status)
    }

    /**
     * The confounder rule: a creatinine drawn the morning after a heavy session is reporting the
     * session as much as the kidney.
     */
    @Test
    fun aHardSessionBeforeTheDrawIsNamed() {
        val session = JournalEntry(
            day = today.minusDays(1).toString(),
            occurredAt = at(today) - 20 * 3_600_000L,
            kind = "ACTIVITY",
            title = "Squats",
            calories = 500.0,
            met = 8.0,
            durationMinutes = 60.0,
        )
        val advice = sampleContextAdvice(
            listOf(reading(LabMarker.CREATININE, 130.0)),
            listOf(session),
            profile(),
            today,
        )
        assertEquals("labs-context-creatinine", advice?.id)
    }

    /** A very high-protein fortnight lifts creatinine on its own, and the copy says which one. */
    @Test
    fun aHighProteinStretchBeforeTheDrawIsNamed() {
        val advice = sampleContextAdvice(
            listOf(reading(LabMarker.CREATININE, 130.0)),
            meals(days = 3, proteinPerDay = 200.0),
            profile(weightKg = 75.0),
            today,
        )
        assertNotNull(advice)
    }

    @Test
    fun lowIronMeetsTheCycleOnlyWhereThereIsOne() {
        val cycles = (0 until 2).map { offset ->
            val day = today.minusDays(offset * 30L)
            JournalEntry(
                day = day.toString(),
                occurredAt = at(day),
                kind = KIND_CYCLE,
                title = "Cycle",
                calories = 0.0,
            )
        }
        val ferritin = listOf(reading(LabMarker.FERRITIN, 20.0, sex = "FEMALE"))
        assertNotNull(ferritinCycleAdvice(ferritin, cycles, profile(sex = "FEMALE"), today))
        assertNull(ferritinCycleAdvice(ferritin, cycles, profile(sex = "MALE"), today))
        assertNull(ferritinCycleAdvice(ferritin, emptyList(), profile(sex = "FEMALE"), today))
    }

    @Test
    fun saturatedFatIsNamedBeforeFibre() {
        val ldl = listOf(reading(LabMarker.LDL, 3.6))
        val fatty = meals(days = 5, saturatedPerDay = 30.0, fiberPerDay = 30.0)
        assertEquals("labs-saturated-fat", lipidDietAdvice(ldl, fatty, today)?.id)
        val thin = meals(days = 5, saturatedPerDay = 15.0, fiberPerDay = 10.0)
        assertEquals("labs-fiber", lipidDietAdvice(ldl, thin, today)?.id)
    }

    /**
     * The body takes its share as %d, so the arg has to reach it as an Int: a String in a %d slot
     * throws IllegalFormatConversionException when the card is drawn, and nothing before the draw
     * notices. Asserting the id alone — as the test above does — never formats the body.
     */
    @Test
    fun saturatedFatShareIsFormattableIntoItsBody() {
        val ldl = listOf(reading(LabMarker.LDL, 3.6))
        val fatty = meals(days = 5, saturatedPerDay = 30.0, fiberPerDay = 30.0)
        val args = lipidDietAdvice(ldl, fatty, today)?.args.orEmpty()
        assertEquals(listOf<Any>(14), args)
        val body = File("src/main/res/values/ui.xml").readText()
            .substringAfter("\"advice_labs_saturated_body\">").substringBefore("</string>")
        assertTrue(body, body.contains("%1\$d"))
    }

    /** Dietary cholesterol is recorded and deliberately not used: the evidence does not carry it. */
    @Test
    fun dietaryCholesterolIsNeverTheFinding() {
        val ldl = listOf(reading(LabMarker.LDL, 3.6))
        val eggs = meals(days = 7).map { it.copy(cholesterolMg = 900.0) }
        assertNull(lipidDietAdvice(ldl, eggs, today))
    }

    @Test
    fun walkingIsOfferedOnlyForTheMarkersItMoves() {
        val quiet = (0 until 7).map { DaySteps(today.minusDays(it.toLong()).toString(), 3_000) }
        assertNotNull(
            activityLabAdvice(listOf(reading(LabMarker.HBA1C, 6.0)), quiet, profile(), currentYear, today),
        )
        assertNull(
            activityLabAdvice(listOf(reading(LabMarker.TSH, 6.0)), quiet, profile(), currentYear, today),
        )
    }

    /**
     * The three bands that genuinely move with the birthday. A 5.0 TSH is a finding at forty and an
     * ordinary result at seventy-five, and reading it against the wrong one is how an incidental
     * thyroid number turns into a referral for nothing.
     */
    @Test
    fun theBandsThatMoveWithAgeMoveWithIt() {
        assertEquals(LabStatus.ABOVE, LabMarker.TSH.status(5.0, "MALE", 40, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.TSH.status(5.0, "MALE", 75, fasting = true))
        assertEquals(LabStatus.ABOVE, LabMarker.URIC_ACID.status(400.0, "FEMALE", 45, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.URIC_ACID.status(400.0, "FEMALE", 55, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.HEMOGLOBIN.status(125.0, "MALE", 30, fasting = true))
        assertEquals(LabStatus.NORMAL, LabMarker.HEMOGLOBIN.status(125.0, "MALE", 13, fasting = true))
    }

    /** Every other band is the same at every adult age, and must not drift on its own. */
    @Test
    fun everyOtherBandStandsStill() {
        val moving = setOf(LabMarker.TSH, LabMarker.URIC_ACID, LabMarker.HEMOGLOBIN)
        LabMarker.entries.filterNot { it in moving }.forEach { marker ->
            assertEquals(
                marker.name,
                marker.bandFor("MALE", 25, fasting = true),
                marker.bandFor("MALE", 80, fasting = true),
            )
        }
    }

    /**
     * The age correction that matters most is inside the equation: the same creatinine is a
     * different filtration rate at thirty and at seventy, and the reading is dated by its own draw.
     */
    @Test
    fun theFiltrationRateUsesTheAgeAtTheDraw() {
        fun rateAt(age: Int) = derivedLabs(
            mapOf(LabMarker.CREATININE to reading(LabMarker.CREATININE, 100.0, age = age)),
            profile(),
        ).first { it.marker == LabMarker.EGFR }
        assertTrue(rateAt(30).value > rateAt(70).value)
        assertEquals(30, rateAt(30).ageYears)
    }

    /**
     * CKD-EPI is an adult equation and the app holds no paediatric one. Running a child's creatinine
     * through it with the age forced up to eighteen would report a filtration rate for a body that was
     * never measured; no number at all is the honest answer.
     */
    @Test
    fun aChildIsGivenNoFiltrationRateAtAll() {
        fun rateAt(age: Int) = derivedLabs(
            mapOf(LabMarker.CREATININE to reading(LabMarker.CREATININE, 100.0, age = age)),
            profile(),
        ).firstOrNull { it.marker == LabMarker.EGFR }
        assertNull(rateAt(8))
        assertNull(rateAt(17))
        assertNotNull(rateAt(18))
    }

    @Test
    fun ageIsCountedFromTheDrawNotFromToday() {
        assertEquals(24, ageOn("2022-05-01", "1998-01-20"))
        assertEquals(28, ageOn("2026-05-01", "1998-01-20"))
    }

    @Test
    fun theHabitWeekSumsWhatWasLogged() {
        val journal = listOf(
            JournalEntry(
                day = today.toString(),
                occurredAt = at(today),
                kind = KIND_ALCOHOL,
                title = "Alcohol",
                calories = 0.0,
                servingG = AlcoholDrink.BEER.ethanolG,
            ),
            JournalEntry(
                day = today.minusDays(2).toString(),
                occurredAt = at(today.minusDays(2)),
                kind = KIND_SMOKING,
                title = "Smoking",
                calories = 0.0,
                servingG = TobaccoProduct.HOOKAH_MEDIUM.tobaccoG,
            ),
            JournalEntry(
                day = today.minusDays(30).toString(),
                occurredAt = at(today.minusDays(30)),
                kind = KIND_ALCOHOL,
                title = "Alcohol",
                calories = 0.0,
                servingG = AlcoholDrink.SPIRIT.ethanolG,
            ),
        )
        val week = habitWeek(journal, today)
        assertEquals(20.0, week.ethanolG, 0.001)
        assertEquals(2.0, week.standardDrinks, 0.001)
        assertEquals(12.0, week.tobaccoG, 0.001)
        assertEquals(1, week.smokingDays)
        assertTrue(week.cigaretteEquivalents > 14.0)
    }

    /**
     * The floor a meter's owner most needs painted. A band with only a ceiling would call 2.8
     * mmol/L normal, colour it in and count it among the readings "inside the band"; ADA puts
     * hypoglycaemia below 3.9 and its serious level 2 below 3.0, fed or fasting alike.
     */
    @Test
    fun glucoseHasAFloorWhicheverBandApplies() {
        assertEquals(LabStatus.BELOW, LabMarker.GLUCOSE.status(3.8, "MALE", 50, fasting = true))
        assertEquals(LabStatus.FAR_BELOW, LabMarker.GLUCOSE.status(2.9, "MALE", 50, fasting = true))
        assertEquals(LabStatus.BELOW, LabMarker.GLUCOSE.status(3.5, "MALE", 50, fasting = false))
        assertEquals(LabStatus.FAR_BELOW, LabMarker.GLUCOSE.status(2.8, "MALE", 50, fasting = false))
        assertEquals(LabStatus.NORMAL, LabMarker.GLUCOSE.status(3.9, "MALE", 50, fasting = true))
    }

    /**
     * A meter says "fasting" in its GLS context, Health Connect says it with the plain flag its
     * importer writes; a row that says nothing is read as fed. Both are read: with the flag alone,
     * every reading a meter took before breakfast would be judged by the after-meal band — a fasting
     * 7.5 mmol/L, diabetic range, would come out normal.
     */
    @Test
    fun fastingIsReadFromTheMeterContextAndFromTheImportedFlag() {
        fun glucoseRow(payload: String) = JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = "MEASUREMENT",
            title = "Glucose",
            calories = 0.0,
            measurementPayloadJson = payload,
        )
        val meter = glucoseRow("""{"schema":1,"concentrationMmolL":7.5,"context":{"meal":3}}""")
        val imported = glucoseRow("""{"schema":1,"concentrationMmolL":7.5,"fasting":true}""")
        val silent = glucoseRow("""{"schema":1,"concentrationMmolL":7.5}""")

        val readings = labHistory(listOf(meter, imported, silent), profile()).getValue(LabMarker.GLUCOSE)

        assertEquals(listOf(true, true, false), readings.map(LabReading::fasting))
        assertEquals(
            listOf(LabStatus.FAR_ABOVE, LabStatus.FAR_ABOVE, LabStatus.NORMAL),
            readings.map(LabReading::status),
        )
        assertTrue(meter.labFasting())
        assertFalse(silent.labFasting())
    }
    // --- what the sheet said about the sample ------------------------------------------------------

    /** A panel as the journal holds one: the payload is the whole of it, and the words are not. */
    private fun panel(payload: String) = JournalEntry(
        day = today.toString(),
        occurredAt = at(today),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    /**
     * A panel carries the specimen, the analyser and the range the laboratory printed, and every
     * one of them comes back out attached to the reading it belongs to.
     *
     * These three are what a marker name is missing before it can honestly be a coded test: the
     * same name over serum and over whole blood is two different results, and the range beside it
     * belongs to the analyser that ran the sample rather than to any published guidance.
     */
    @Test
    fun aPanelCarriesItsSpecimenMethodAndPrintedRange() {
        val entry = panel(
            labsPayload(
                values = mapOf(LabMarker.HBA1C to 5.4, LabMarker.TSH to 2.1),
                fasting = true,
                specimen = LabSpecimen.SERUM,
                method = "Abbott Alinity ci",
                reportedRanges = mapOf(LabMarker.HBA1C to ReportedRange(low = 4.0, high = 6.0)),
            ),
        )

        val history = labHistory(listOf(entry), profile())
        val hba1c = history.getValue(LabMarker.HBA1C).last()
        val tsh = history.getValue(LabMarker.TSH).last()

        assertEquals(LabSpecimen.SERUM, hba1c.specimen)
        assertEquals("Abbott Alinity ci", hba1c.method)
        assertEquals(ReportedRange(low = 4.0, high = 6.0), hba1c.reported)
        // The specimen belongs to the draw, the printed range to the result: a marker the sheet
        // printed no range for carries none rather than borrowing its neighbour's.
        assertEquals(LabSpecimen.SERUM, tsh.specimen)
        assertNull(tsh.reported)
    }

    /** A panel that says nothing about its sample says nothing, rather than being filed as serum. */
    @Test
    fun anUnsaidSpecimenIsNotGuessedAt() {
        val entry = panel(labsPayload(mapOf(LabMarker.HBA1C to 5.4), fasting = true))

        val reading = labHistory(listOf(entry), profile()).getValue(LabMarker.HBA1C).last()

        assertNull(reading.specimen)
        assertEquals("", reading.method)
        assertNull(reading.reported)
        assertNull(labSpecimenOf("SOMETHING_ELSE"))
        assertNull(labSpecimenOf(null))
    }

    /** One edge is a range: "< 5.0" and "> 40" are how half of them are printed. */
    @Test
    fun aRangePrintedWithOneEdgeKeepsThatEdgeAlone() {
        val entry = panel(
            labsPayload(
                values = mapOf(LabMarker.TSH to 2.1),
                fasting = false,
                reportedRanges = mapOf(
                    LabMarker.TSH to ReportedRange(high = 4.0),
                    // Neither edge is not a range, and is left out of the payload entirely.
                    LabMarker.HBA1C to ReportedRange(),
                ),
            ),
        )

        val reading = labHistory(listOf(entry), profile()).getValue(LabMarker.TSH).last()

        assertEquals(ReportedRange(high = 4.0), reading.reported)
        assertFalse(entry.measurementPayloadJson!!.contains(LabMarker.HBA1C.name))
    }
}
