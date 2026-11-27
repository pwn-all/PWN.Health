package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow

/**
 * The health maths behind the bands: the filtration equation at the seam its two branches meet on,
 * the budget at the exact figure its safe floor stops applying at, the impedance gates on their own
 * ends, and what an age nobody knows does to every figure derived from one.
 *
 * The overflow of [HealthBandsEdgeCasesTest], which holds the bands themselves.
 */
class HealthBandsEdgeCasesExtraTest {
    /** Eighty kilos at 180 cm, born in 1996 — thirty years old in the 2026 every test below uses. */
    private val male = UserProfile(
        sex = "MALE",
        weightKg = 80.0,
        heightCm = 180,
        birthDate = "1996-06-15",
    )

    /** Noon UTC, the instant a packet arrives in the clock tests. */
    private val arrival: Long =
        LocalDateTime.of(2026, 9, 4, 12, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** One gate moved at a time, the other three holding an ordinary body. */
    private fun fat(impedanceOhms: Int, weightKg: Double, heightCm: Int, ageYears: Int = 40) =
        bodyFatPercent(impedanceOhms, weightKg, heightCm, ageYears, male = true)

    /** Flags saying "time stamp only", 20.0% of body fat, and the seven bytes of a GATT Date Time. */
    private fun timestampedPacket(at: LocalDateTime) = byteArrayOf(
        0x02,
        0x00,
        0xC8.toByte(),
        0x00,
        (at.year and 0xFF).toByte(),
        (at.year shr 8).toByte(),
        at.monthValue.toByte(),
        at.dayOfMonth.toByte(),
        at.hour.toByte(),
        at.minute.toByte(),
        at.second.toByte(),
    )

    // --- CKD-EPI where its two branches meet ----------------------------------------------------

    /**
     * CKD-EPI 2021 is a two-branch power law: one exponent applies below κ and −1.200 above it. At
     * creatinine exactly κ both `min(ratio, 1)` and `max(ratio, 1)` are 1, so the whole equation
     * collapses to the age term — 142 × 0.9938^age, and a woman's 1.012 on top of it. Swapping the
     * exponents or the min for the max still looks plausible in the middle of the range and is
     * wrong at both ends, which is the shape a single pinned point per sex cannot catch.
     */
    @Test
    fun theFiltrationRateIsContinuousAndFallingAcrossTheKappaSeam() {
        // κ is 0.9 mg/dL for a man and 0.7 for a woman, in the µmol/L this app stores.
        val maleSeam = 0.9 * 88.4
        val femaleSeam = 0.7 * 88.4
        val ageTerm = 142.0 * 0.9938.pow(40.0)

        assertEquals(ageTerm, ckdEpi(maleSeam, 40, "MALE"), 0.01)
        assertEquals(ageTerm * 1.012, ckdEpi(femaleSeam, 40, "FEMALE"), 0.01)

        val below = ckdEpi(maleSeam - 0.5, 40, "MALE")
        val seam = ckdEpi(maleSeam, 40, "MALE")
        val above = ckdEpi(maleSeam + 0.5, 40, "MALE")
        assertTrue("$below at 0.5 under the seam, $seam on it", below > seam)
        assertTrue("$seam on the seam, $above at 0.5 over it", seam > above)
        assertTrue("a step of ${below - above} across the seam", below - above < 2.0)

        val rates = listOf(40.0, 60.0, maleSeam, 100.0, 200.0, 400.0).map { ckdEpi(it, 40, "MALE") }
        assertEquals("more creatinine read as more filtration: $rates", rates.sortedDescending(), rates)
    }

    // --- an age nobody knows --------------------------------------------------------------------

    /**
     * [Backup] writes an empty birth date when a restored backup carries neither a date nor a
     * positive year, so [UserProfile.birthYear] is 0 and every `currentYear - birthYear` here reads
     * 2026 years old. [ageOn] clamps an age into 0..120 and `activityLabAdvice` writes the same
     * clamp by hand, so no figure derived from an age may fall outside what those two ends give:
     * unclamped the resting figure would be about −8,200 kcal, the budget would collapse to 0, and
     * every meal on the home screen would read as over budget for as long as the profile stayed
     * restored.
     */
    @Test
    fun aProfileWithNoBirthDateIsNotReadAsTwoThousandYearsOld() {
        val unknown = male.copy(birthDate = "")
        val oldest = restingCaloriesPerDay(male.copy(birthDate = "1906-06-15"), currentYear = 2026)
        val newborn = restingCaloriesPerDay(male.copy(birthDate = "2026-06-15"), currentYear = 2026)

        val resting = restingCaloriesPerDay(unknown, currentYear = 2026)
        assertTrue(
            "resting energy was $resting, outside the $oldest..$newborn any age can give",
            resting in oldest..newborn,
        )

        val budget = energyBudget(unknown, currentYear = 2026)
        assertTrue("the day's food budget was ${budget.targetKcal} kcal", budget.targetKcal >= 1_200.0)
        assertTrue("fat ${budget.fatG} g and carbs ${budget.carbsG} g", budget.fatG > 0.0 && budget.carbsG > 0.0)
    }

    // --- the losing budget on its floor ---------------------------------------------------------

    /**
     * `max(maintenance - 500, min(floor, maintenance))` has three cases and the interesting one is
     * the crossing: the flat deficit has to take over the moment it clears the floor, and a
     * maintenance already under the floor must be handed back untouched rather than raised to it.
     * Ordinary figures sit well inside each case; these sit on the line between them.
     */
    @Test
    fun theFlatDeficitTakesOverTheMomentItClearsTheSafeFloor() {
        // Sedentary maintenance is 1.2 × Mifflin, which at 180 cm and thirty is 12 × kg + 1,176 kcal.
        fun losingMan(weightKg: Double) = energyBudget(
            male.copy(weightKg = weightKg, energyGoal = "LOSE"),
            currentYear = 2026,
        ).targetKcal

        assertEquals("maintenance 1992, deficit 1492", 1_500.0, losingMan(68.0), 0.001)
        assertEquals("maintenance exactly 2000", 1_500.0, losingMan(824.0 / 12.0), 0.001)
        assertEquals("maintenance 2004, deficit 1504", 1_504.0, losingMan(69.0), 0.001)

        // Her floor is 1,200, and at 160 cm and forty her maintenance is 12 × kg + 766.8 kcal.
        val woman = male.copy(sex = "FEMALE", heightCm = 160, birthDate = "1986-06-15", energyGoal = "LOSE")
        fun losingWoman(weightKg: Double) =
            energyBudget(woman.copy(weightKg = weightKg), currentYear = 2026).targetKcal

        assertEquals("maintenance 1692, deficit 1192", 1_200.0, losingWoman(77.1), 0.001)
        assertEquals("maintenance 1704, deficit 1204", 1_204.0, losingWoman(78.1), 0.001)
    }

    // --- movement counted once ------------------------------------------------------------------

    /**
     * An imported Health Connect step session describes the very steps the pedometer counted, so it
     * competes with them rather than adding to them. [HEALTH_CONNECT_STEPS_CODE] is a string shared
     * with every provenance check in the app; if it ever drifts out of the step-counted set, the
     * day's burn silently doubles for everybody who syncs steps.
     */
    @Test
    fun anImportedStepSessionIsPaidForOnceNotTwice() {
        val imported = JournalEntry(
            day = "2026-08-07",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Steps",
            calories = 300.0,
            activityCode = HEALTH_CONNECT_STEPS_CODE,
        )
        val swim = imported.copy(title = "Swim", calories = 250.0, activityCode = "SWIM")

        // The larger of the two claims about one outing, never their sum.
        assertEquals(300.0, movedCalories(listOf(imported), stepsKcal = 190.0), 0.001)
        assertEquals(400.0, movedCalories(listOf(imported), stepsKcal = 400.0), 0.001)
        // A swim is not made of steps and adds on top.
        assertEquals(550.0, movedCalories(listOf(imported, swim), stepsKcal = 190.0), 0.001)
    }

    /**
     * A weighing and a workout can land in the same millisecond, and Room hands back ids in no
     * particular relation to timestamps. The rebuild promises to be deterministic, so the tie is
     * broken by id and the walk — logged first — is still costed at the weight that applied before
     * the scales were stepped on, whichever order the rows arrive in.
     */
    @Test
    fun aWeighInAndAWorkoutAtTheSameInstantRebuildTheSameWayInEitherOrder() {
        val walk = JournalEntry(
            id = 1,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 0.0,
            met = 3.0,
            durationMinutes = 60.0,
        )
        val weighIn = JournalEntry(
            id = 2,
            day = "2026-07-30",
            occurredAt = 100,
            kind = "MEASUREMENT",
            title = "Weight",
            calories = 0.0,
            measurementWeightKg = 100.0,
        )

        fun rebuiltWalk(entries: List<JournalEntry>) =
            recalculatedActivityTimeline(entries, baselineWeightKg = 80.0, profile = male)
                .first { it.kind == "ACTIVITY" }
                .calories

        // (3 − 1) MET × Mifflin–St Jeor's hour at the 80 kg baseline × 1 h, in both orders.
        assertEquals(2 * 1_780.0 / 24, rebuiltWalk(listOf(walk, weighIn)), 0.001)
        assertEquals(2 * 1_780.0 / 24, rebuiltWalk(listOf(weighIn, walk)), 0.001)
    }

    // --- body composition -----------------------------------------------------------------------

    /**
     * The four ranges the Sun equations are gated by are written inclusive, and each end is a
     * reading a real scale sends: 200 ohms off a very muscular body, 1,200 off a very light one.
     * An `until` slipped in for a `..` loses a legitimate measurement in silence, and only a probe on
     * each end catches it.
     */
    @Test
    fun theImpedanceAndWeightGatesTakeTheirOwnEndsAndRefuseOneStepOut() {
        assertNotNull("200 ohms is the lowest resistance the equations are used at", fat(200, 120.0, 150))
        assertNull(fat(199, 120.0, 150))
        assertNotNull("1200 ohms is the highest", fat(1_200, 80.0, 180))
        assertNull(fat(1_201, 80.0, 180))

        assertNotNull("20 kg is the lightest body", fat(600, 20.0, 100, ageYears = 10))
        assertNull(fat(600, 19.9, 100, ageYears = 10))
        assertNotNull("400 kg is the heaviest", fat(500, 400.0, 200))
        assertNull(fat(500, 400.1, 200))
    }

    @Test
    fun theHeightAndAgeGatesTakeTheirOwnEndsAndRefuseOneStepOut() {
        assertNotNull("50 cm is the shortest body", fat(600, 20.0, 50, ageYears = 10))
        assertNull(fat(600, 20.0, 49, ageYears = 10))
        assertNotNull("250 cm is the tallest", fat(1_200, 200.0, 250))
        assertNull(fat(1_200, 200.0, 251))

        assertNotNull("five is the youngest age Sun is published for", fat(500, 80.0, 180, ageYears = 5))
        assertNull(fat(500, 80.0, 180, ageYears = 4))
        assertNotNull("120 is the oldest", fat(500, 80.0, 180, ageYears = 120))
        assertNull(fat(500, 80.0, 180, ageYears = 121))
    }

    /** A fat mass can never exceed the body it is part of, and never be less than nothing. */
    @Test
    fun fatMassIsHeldInsideTheBodyItIsPartOf() {
        assertEquals(80.0, fatMassKg(80.0, 150.0), 1e-9)
        assertEquals(80.0, fatMassKg(80.0, 100.0), 1e-9)
        assertEquals(0.0, fatMassKg(80.0, 0.0), 1e-9)
        assertEquals(0.0, fatMassKg(80.0, -5.0), 1e-9)
    }

    /**
     * 0xFFFF alone is the profile's "measurement unsuccessful" sentinel, and 0xFFFE is one step
     * from it. The comparison is an exact equality against a magic number, so a `>=` there would
     * discard every reading above 6553.4% in silence; the parser applies no plausibility gate of
     * its own, and the range that drops an implausible figure is the vitals screen's.
     */
    @Test
    fun theLargestNonSentinelBodyFatIsStillAMeasurement() {
        val measured = BluetoothBodyComposition.parse(
            byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
            arrival,
            ZoneOffset.UTC,
        )

        assertNotNull(measured)
        assertTrue("0xFFFE was read as the failure sentinel", measured?.successful == true)
        assertEquals(6_553.4, measured?.bodyFatPercent ?: 0.0, 1e-6)
        assertFalse("the implausible figure is the screen's to drop", 6_553.4 in Vital.BODY_FAT.valid)

        val sentinel = BluetoothBodyComposition.parse(
            byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
            arrival,
            ZoneOffset.UTC,
        )
        assertNotNull(sentinel)
        assertNull(sentinel?.bodyFatPercent)
        assertFalse("the sentinel was read as a measurement", sentinel?.successful == true)
    }

    /**
     * A scale fresh out of the box counts from 2000-01-01 and the profile gives it no way to say
     * so, hence the window: ten years behind for a clock set once and never again, five minutes
     * ahead for two clocks disagreeing about the minute. Both ends are inclusive, and the flag
     * beside the reading has to say which side of them the packet fell on — a flipped comparison
     * files a weighing a decade back, where the journal never looks.
     */
    @Test
    fun theScaleClockWindowIsInclusiveAtBothEndsAndTheFlagFollowsIt() {
        fun parsed(at: LocalDateTime) =
            BluetoothBodyComposition.parse(timestampedPacket(at), arrival, ZoneOffset.UTC)

        val tenYearsBack = LocalDateTime.of(2026, 9, 4, 12, 0, 0).minusDays(3_650)
        assertEquals(tenYearsBack, parsed(tenYearsBack)?.timestamp)
        assertEquals(true, parsed(tenYearsBack)?.deviceClockSet)

        val tooOld = tenYearsBack.minusSeconds(1)
        assertNull(parsed(tooOld)?.timestamp)
        assertEquals(false, parsed(tooOld)?.deviceClockSet)

        val fiveMinutesAhead = LocalDateTime.of(2026, 9, 4, 12, 5, 0)
        assertEquals(fiveMinutesAhead, parsed(fiveMinutesAhead)?.timestamp)
        assertNull(parsed(fiveMinutesAhead.plusSeconds(1))?.timestamp)

        // The weighing itself survives a clock nobody set; only its date is given up.
        assertEquals(20.0, parsed(tooOld)?.bodyFatPercent ?: 0.0, 1e-9)
    }
}
