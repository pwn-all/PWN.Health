package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The lab and vitals bands read on the exact values either side of their edges, and the promises
 * the bands themselves make in prose and nothing enforces.
 *
 * [LabBandsTest] and [VitalsTest] hold the ordinary behaviour of both models. Everything here is a
 * boundary, an unparsable input, or an invariant that has to hold across the whole catalogue.
 */
class HealthBandsEdgeCasesTest {
    private val today: LocalDate = LocalDate.parse("2026-09-04")

    /** Every published transition, and the year either side of it: 2, 5, 12, 15, 18, 50 and 70. */
    private val ageSweep = listOf(0, 1, 2, 4, 5, 11, 12, 14, 15, 18, 49, 50, 69, 70, 120)

    /** Both sexes, every age above, and a sample drawn either side of breakfast. */
    private val bandCases: List<Triple<String, Int, Boolean>> = listOf("MALE", "FEMALE")
        .flatMap { sex -> ageSweep.map { sex to it } }
        .flatMap { (sex, age) -> listOf(Triple(sex, age, true), Triple(sex, age, false)) }

    private fun profile(sex: String = "MALE", birthDate: String = "1976-06-15") =
        UserProfile(sex = sex, weightKg = 80.0, heightCm = 180, birthDate = birthDate)

    private fun reading(
        marker: LabMarker,
        value: Double,
        sex: String = "MALE",
        fasting: Boolean = true,
        age: Int = 50,
        day: String = today.toString(),
    ) = LabReading(
        marker = marker,
        value = value,
        occurredAt = 1_780_000_000_000L,
        day = day,
        fasting = fasting,
        ageYears = age,
        status = marker.status(value, sex, age, fasting),
    )

    /** One stored row, the way a panel, a meter or an import writes it. */
    private fun row(payload: String?, day: String = "2026-03-01", at: Long = 1_780_000_000_000L) =
        JournalEntry(
            day = day,
            occurredAt = at,
            kind = KIND_LABS,
            title = "panel",
            calories = 0.0,
            measurementPayloadJson = payload,
        )

    private fun fastingGlucose(mmolL: Double): String =
        """{"schema":1,"concentrationMmolL":$mmolL,"fasting":true}"""

    private fun pulseReading(value: Double, at: Long) = VitalReading(
        vital = Vital.HEART_RATE,
        value = value,
        secondary = null,
        occurredAt = at,
        day = "2026-03-01",
        site = null,
        siteName = null,
        status = null,
    )

    // --- what every band has to be, whoever it is read for -----------------------------------

    /**
     * [LabMarker.status] tests veryHigh before high and veryLow before low, so a pair put the wrong
     * way round does not throw: the clinician arm simply swallows the attention band, and a result
     * that should have been ABOVE is reported as FAR_ABOVE or the other way about. The
     * age-adjusted copies are built with `copy`, which is exactly where one edge moves and its
     * partner stays behind.
     */
    @Test
    fun everyBandEdgeIsOrderedAndSitsInsideTheMarkersOwnRange() {
        LabMarker.entries.forEach { marker ->
            bandCases.forEach { (sex, age, fasting) ->
                val band = marker.bandFor(sex, age, fasting) ?: return@forEach
                assertBandIsWellFormed("${marker.key} $sex ${age}y fasting=$fasting", band, marker.valid)
            }
        }
    }

    private fun assertBandIsWellFormed(
        where: String,
        band: LabBand,
        valid: ClosedFloatingPointRange<Double>,
    ) {
        val high = band.high
        val veryHigh = band.veryHigh
        val low = band.low
        val veryLow = band.veryLow
        if (high != null && veryHigh != null) {
            assertTrue("$where: clinician edge $veryHigh under the attention edge $high", veryHigh >= high)
        }
        if (low != null && veryLow != null) {
            assertTrue("$where: clinician edge $veryLow over the attention edge $low", veryLow <= low)
        }
        if (low != null && high != null) {
            assertTrue("$where: nothing is normal between $low and $high", low < high)
        }
        listOfNotNull(high, veryHigh, low, veryLow).forEach {
            assertTrue("$where: edge $it is outside the values the form takes, $valid", it in valid)
        }
    }

    /**
     * [LabMarker.bandFor] returns a fed band without consulting sex and without the age correction,
     * and its KDoc says that is safe only because "Sex and food never both apply". Nothing else enforces
     * it: adding a fedBand to a sex-specific or age-varying marker is a one-line change that would
     * silently grade a woman's sample after food against the male band.
     *
     * Triglycerides do move with age — a child gets no triglyceride band at all — so "never
     * moves with age" is not the rule to hold. What has to hold is that the fed branch
     * skips nothing: a marker withheld at an age is withheld after food too, and any edge the age
     * rule changes is one the fed band does not carry.
     */
    @Test
    fun noMarkerCarriesASexBandAndAFoodBandAtOnce() {
        val movesWithAge = LabMarker.entries.filter { marker ->
            listOf("MALE", "FEMALE").any { sex ->
                ageSweep.map { marker.bandFor(sex, it, fasting = true) }.distinct().size > 1
            }
        }
        LabMarker.entries.filter { it.fedBand != null }.forEach { marker ->
            assertNull("${marker.key} carries a female band and a fed band", marker.femaleBand)
            val fed = marker.fedBand!!
            ageSweep.forEach { age ->
                val fasting = marker.bandFor("MALE", age, fasting = true)
                val afterFood = marker.bandFor("MALE", age, fasting = false)
                assertEquals("${marker.key} at $age: withheld fasting but not fed", fasting == null, afterFood == null)
                if (fasting == null) return@forEach
                val moved = listOf(
                    (fasting.high != marker.band.high) to fed.high,
                    (fasting.veryHigh != marker.band.veryHigh) to fed.veryHigh,
                    (fasting.low != marker.band.low) to fed.low,
                    (fasting.veryLow != marker.band.veryLow) to fed.veryLow,
                )
                moved.forEach { (changed, fedEdge) ->
                    assertFalse("${marker.key} at $age moves an edge its fed band keeps", changed && fedEdge != null)
                }
            }
        }
        assertTrue("no marker moves with age at all", movesWithAge.isNotEmpty())
    }

    // --- the exact birthdays a published band steps on ------------------------------------------

    /**
     * WHO 2024 steps its anaemia cutoffs at 24 months, 5 years and 12 years. `childHemoglobinLow`
     * is a four-armed `when` and the ordinary tests probe only the middle of each arm, so an arm
     * shifted by a year — the whole failure mode of a chain of `<` comparisons — reads an anaemic
     * toddler as normal.
     */
    @Test
    fun theChildHaemoglobinFloorsStepOnTheirExactBirthdays() {
        fun statusAt(value: Double, age: Int) =
            LabMarker.HEMOGLOBIN.status(value, "MALE", age, fasting = true)

        // 105 g/L up to 24 months, then 110.
        assertEquals(LabStatus.NORMAL, statusAt(107.0, 1))
        assertEquals(LabStatus.BELOW, statusAt(107.0, 2))
        // 110 up to five years, then 115.
        assertEquals(LabStatus.NORMAL, statusAt(112.0, 4))
        assertEquals(LabStatus.BELOW, statusAt(112.0, 5))
        // 115 up to twelve, then 120.
        assertEquals(LabStatus.NORMAL, statusAt(117.0, 11))
        assertEquals(LabStatus.BELOW, statusAt(117.0, 12))
        // The clinician floor is the same at every age.
        assertEquals(LabStatus.FAR_BELOW, statusAt(79.0, 1))
    }

    /**
     * The elderly thyroid ceiling exists so that an incidental 5.0 at seventy does not become a
     * referral for nothing. A `>` where a `>=` belongs moves the whole rule by a year, and the
     * direction of the slip decides which of the two mistakes gets made.
     */
    @Test
    fun theElderlyThyroidCeilingArrivesOnTheSeventiethBirthday() {
        fun statusAt(value: Double, age: Int) = LabMarker.TSH.status(value, "MALE", age, fasting = true)

        assertEquals(LabStatus.ABOVE, statusAt(5.0, 69))
        assertEquals(LabStatus.NORMAL, statusAt(5.0, 70))
        // At seventy the elderly edge is itself the first value outside.
        assertEquals(LabStatus.NORMAL, statusAt(5.9, 70))
        assertEquals(LabStatus.ABOVE, statusAt(6.0, 70))
        assertEquals(6.0, LabMarker.TSH.bandFor("MALE", 70, fasting = true)!!.high!!, 1e-9)
        // Only the attention ceiling moves: the clinician edge and both floors stay put.
        assertEquals(LabStatus.FAR_ABOVE, statusAt(10.0, 70))
        assertEquals(LabStatus.BELOW, statusAt(0.3, 70))
        assertEquals(LabStatus.FAR_BELOW, statusAt(0.05, 70))
    }

    /**
     * A woman's urate reference rises to the male one after menopause. The band is rebuilt with
     * `copy(high = ...)`, and a copy that dropped the clinician edge with it would turn a urate of
     * 560 from FAR_ABOVE into ABOVE — the reading that stops being a lifestyle conversation,
     * reported as one.
     */
    @Test
    fun urateJoinsTheMaleCeilingAtFiftyAndKeepsItsClinicianEdge() {
        fun statusAt(value: Double, age: Int) =
            LabMarker.URIC_ACID.status(value, "FEMALE", age, fasting = true)

        assertEquals(LabStatus.ABOVE, statusAt(400.0, 49))
        assertEquals(LabStatus.NORMAL, statusAt(400.0, 50))
        assertEquals(LabStatus.NORMAL, statusAt(419.0, 50))
        assertEquals(LabStatus.ABOVE, statusAt(420.0, 50))
        assertEquals(LabStatus.FAR_ABOVE, statusAt(560.0, 50))
        assertEquals(540.0, LabMarker.URIC_ACID.bandFor("FEMALE", 50, fasting = true)!!.veryHigh!!, 1e-9)
    }

    // --- what reaches the history, and what silently does not -----------------------------------

    /**
     * The mass-concentration half of the meter market fills only its own key. 50 mg/dL is
     * 2.78 mmol/L — under ADA's level-2 floor — and a meter must not be able to hide a hypo by
     * writing the other field.
     */
    @Test
    fun aMeterThatReportsOnlyMilligramsStillShowsAHypoglycaemia() {
        val history = labHistory(
            listOf(row("""{"schema":1,"concentrationMgDl":50,"fasting":true}""")),
            profile(),
        )

        val glucose = history.getValue(LabMarker.GLUCOSE).single()
        assertEquals(2.775, glucose.value, 0.001)
        assertEquals(LabStatus.FAR_BELOW, glucose.status)
    }

    /**
     * `valid` is an inclusive range and doubles as the artefact filter, the derivation gate and the
     * form's bounds. A glucose of exactly 0.5 is the lowest figure the form takes, so it has to
     * reach the history rather than be dropped as noise on the way in.
     */
    @Test
    fun aValueOnTheEndOfItsRangeIsKeptAndOneStepOutsideIsDropped() {
        fun glucose(payload: String) = labHistory(listOf(row(payload)), profile())[LabMarker.GLUCOSE]

        assertEquals(LabStatus.FAR_BELOW, glucose(fastingGlucose(0.5))?.single()?.status)
        assertEquals(LabStatus.FAR_ABOVE, glucose(fastingGlucose(50.0))?.single()?.status)
        assertNull(glucose(fastingGlucose(0.49)))
        assertNull(glucose(fastingGlucose(50.1)))

        fun tsh(value: Double) =
            labHistory(listOf(row("""{"schema":1,"tshMIUL":$value}""")), profile())[LabMarker.TSH]

        assertEquals(LabStatus.FAR_BELOW, tsh(0.005)?.single()?.status)
        assertEquals(LabStatus.FAR_ABOVE, tsh(150.0)?.single()?.status)
        assertNull(tsh(0.004))
        assertNull(tsh(150.1))
    }

    /**
     * HOMA-IR is computed rather than typed, and every derived figure is filed through the same
     * gate as a typed one: a product inside [LabMarker.valid] becomes a row and is graded, a
     * product outside it is not a row at all. HOMA-IR is only interpretable near normoglycaemia, so
     * its 0.1..50 ceiling is narrower than the arithmetic its two inputs allow — and the finding at
     * that end is carried by those two inputs, which are each on the table and each graded.
     */
    @Test
    fun aDerivedHomaIrIsCarriedInsideItsRangeAndAbsentOutsideIt() {
        fun homaOf(glucose: Double, insulin: Double) = derivedLabs(
            mapOf(
                LabMarker.GLUCOSE to reading(LabMarker.GLUCOSE, glucose),
                LabMarker.INSULIN to reading(LabMarker.INSULIN, insulin),
            ),
            profile(),
        ).firstOrNull { it.marker == LabMarker.HOMA_IR }

        // 5.5 × 30 / 22.5 = 7.3, inside the range — derived and kept, and graded by nothing:
        // laboratory-utilisation guidance reserves this calculation for research.
        assertEquals(7.3, homaOf(5.5, 30.0)?.value ?: 0.0, 0.05)
        assertNull(homaOf(5.5, 30.0)?.status)
        // 15.0 × 80 / 22.5 = 53.3, past the ceiling, so no row is derived from it.
        assertNull(homaOf(15.0, 80.0))
        // Glucose, which does have a published line, is still graded on its own.
        assertEquals(LabStatus.FAR_ABOVE, LabMarker.GLUCOSE.status(15.0, "MALE", 50, fasting = true))
    }

    // --- the flags a payload carries about its own sample ---------------------------------------

    /**
     * The GLS meal field names preprandial, postprandial, casual and bedtime samples as well as the
     * fasting one, and 0 is reserved. Reading any of them as fasting would grade a sample taken
     * after food against the fasting band — the finding this rule exists to prevent, in reverse.
     * None of the malformed shapes may throw: this runs over every journal row.
     */
    @Test
    fun onlyTheFastingMealContextReadsAsAFastingSample() {
        listOf(0, 1, 2, 4, 5).forEach { meal ->
            val payload = """{"schema":1,"concentrationMmolL":5.4,"context":{"meal":$meal}}"""
            assertFalse("meal $meal", row(payload).labFasting())
            assertFalse("meal $meal, read as JSON", JSONObject(payload).labFasting())
        }
        assertTrue(row("""{"schema":1,"context":{"meal":3}}""").labFasting())
        assertFalse("a context that is not an object", row("""{"schema":1,"context":7}""").labFasting())
        assertFalse("a payload that is not JSON", row("not json at all").labFasting())
        assertFalse("a truncated payload", row("""{"schema":1,"context":""").labFasting())
        assertFalse("no word either way", row("""{"schema":1}""").labFasting())
        assertFalse("no payload at all", row(null).labFasting())
    }

    /** Attachments are read off rows a restore may have half-written; a throw here takes the screen. */
    @Test
    fun attachmentsAreEmptyRatherThanAThrowOnAHalfWrittenPayload() {
        assertEquals("", row(null).labAttachments())
        assertEquals("", row("").labAttachments())
        assertEquals("", row("not json").labAttachments())
        assertEquals("", row("""{"attachments":""").labAttachments())
        assertEquals("", row("""{"schema":1}""").labAttachments())
        assertEquals("sheet.jpg", row("""{"schema":1,"attachments":"sheet.jpg"}""").labAttachments())
    }

    // --- the vitals half ------------------------------------------------------------------------

    /**
     * [Vital.atStep]'s KDoc states the promise outright: every published edge sits on a multiple of
     * [Vital.step], and rounding down at that step cannot cross one. A new vital measured in half
     * units, or an edge of 25.15, breaks it silently and only in the printed figure — a reading
     * shown as 25.1 beside a verdict reached at 25.0.
     */
    @Test
    fun everyVitalBandEdgeSitsOnItsOwnStepAndInsideItsPlausibleRange() {
        Vital.entries.forEach { vital ->
            listOfNotNull(vital.band, vital.femaleBand).forEach { assertEdgesSitOnTheStep(vital, it, vital.valid) }
            vital.secondaryBand?.let {
                assertEdgesSitOnTheStep(vital, it, vital.secondaryValid ?: vital.valid)
            }
        }
    }

    private fun assertEdgesSitOnTheStep(
        vital: Vital,
        band: LabBand,
        valid: ClosedFloatingPointRange<Double>,
    ) {
        listOfNotNull(band.high, band.veryHigh, band.low, band.veryLow).forEach { edge ->
            val offStep = BigDecimal.valueOf(edge).remainder(BigDecimal.valueOf(vital.step))
            assertEquals(
                "${vital.name}: the edge $edge is not a multiple of the ${vital.step} it is measured in",
                0,
                offStep.compareTo(BigDecimal.ZERO),
            )
            assertTrue("${vital.name}: the edge $edge is outside $valid", edge in valid)
            assertEquals("${vital.name}: flooring $edge moved it", edge, vital.atStep(edge), 0.0)
        }
    }

    /**
     * A crisis the cuff can actually have measured is kept whole: the systolic range reaches past
     * Health Connect's own limit because "A screen about a person's own body must not quietly drop
     * the highest reading their cuff ever gave them", and 240 over a diastolic inside its plausible
     * range grades FAR_ABOVE on the systolic alone.
     *
     * A diastolic outside that range is a parse artefact rather than a measurement, and "Half a
     * blood pressure is not a blood pressure": the pair is refused whole rather than leaving a lone
     * systolic to be averaged into a diary of paired readings.
     */
    @Test
    fun aCrisisIsKeptWhileBothHalvesAreReadingsAndTheHalfPairIsRefused() {
        val crisis = row("""{"schema":1,"systolicMmhg":240,"diastolicMmhg":190}""")

        val readings = vitalSeries(listOf(crisis), Vital.BLOOD_PRESSURE, profile())

        assertEquals("240/190 never reached the series", 1, readings.size)
        assertEquals(240.0, readings.firstOrNull()?.value ?: 0.0, 1e-9)
        assertEquals(LabStatus.FAR_ABOVE, readings.firstOrNull()?.status)

        val artefact = row("""{"schema":1,"systolicMmhg":240,"diastolicMmhg":210}""")
        assertTrue(
            "a systolic with no diastolic beside it reached the series",
            vitalSeries(listOf(artefact), Vital.BLOOD_PRESSURE, profile()).isEmpty(),
        )
    }

    /**
     * A scale or a cuff can write two rows inside one millisecond. Neither is then the newer one —
     * [VitalStats.latest] is "the newest reading, which carries its own instant", and that instant
     * is all a tie can promise; telling the two apart would want the row id the card's own note
     * asks for. The statistics that do have an answer over a tie are the four below.
     */
    @Test
    fun twoReadingsAtOneInstantLeaveTheLatestUndecidedAndTheRestOfTheStatsStanding() {
        val stats = vitalStats(listOf(pulseReading(60.0, at = 100L), pulseReading(90.0, at = 100L)))

        assertNotNull(stats)
        assertEquals("the latest is not one of the two rows", 100L, stats?.latest?.occurredAt)
        assertEquals(60.0, stats?.lowest?.value ?: 0.0, 1e-9)
        assertEquals(90.0, stats?.highest?.value ?: 0.0, 1e-9)
        assertEquals(75.0, stats?.mean ?: 0.0, 1e-9)
        assertEquals(2, stats?.count)
    }

    // --- back to the labs: the one sentence the screen has room for -----------------------------

    /**
     * The single advice slot is won on [LabStatus]'s ordinal, which its own KDoc declares to be the
     * order of "how much a reading is worth saying" and to exist for exactly this `maxByOrNull`.
     * FAR_ABOVE is the top of it, so between two clinician-level findings the high side takes the
     * sentence — the same weighting the paired blood-pressure grade makes.
     *
     * The finding that loses the slot is not lost: both readings are outside their bands, both are
     * painted on the labs table and both are counted by [labBandTally]. Which of two clinician
     * findings deserves the one sentence is the app's choice, so it is pinned here rather than
     * left to be read off an enum by accident.
     */
    @Test
    fun theHigherOfTwoClinicianFindingsTakesTheSingleAdviceSlot() {
        val readings = listOf(reading(LabMarker.GLUCOSE, 2.5), reading(LabMarker.CHOLESTEROL, 7.6))

        val advice = labsAdvice(readings, sex = "MALE", imperial = false, today = today)

        assertEquals(LabStatus.FAR_BELOW, readings.first().status)
        assertEquals(LabStatus.FAR_ABOVE, readings.last().status)
        assertEquals("labs-cholesterol-far_above", advice?.id)
        assertEquals(AdviceLevel.CLINICIAN, advice?.level)
        assertEquals(LabBandTally(inside = 0, graded = 2), labBandTally(readings))
    }
}
