package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The edges of every conversion and every keystroke filter: the exact value on each side of a
 * rounding, the magnitude that leaves the type it is printed through, and the round trip back.
 *
 * [UnitsTest] pins the middle of each scale; this pins the ends, where a constant that drifted in
 * its last digits and a formatter that silently changed the number it was given both hide.
 */
class UnitsAndFormattingEdgeCasesTest {
    private val defaultLocale: Locale = Locale.getDefault()

    /** Everything a field can hold mid-typing, plus what a keyboard sends that a parser will not. */
    private val filterCorpus = listOf(
        "",
        ".",
        "..",
        ",",
        "7.2.5",
        "72.",
        ".5",
        "0,005",
        "1.234,5",
        "123456789012345.5",
        "12345678.",
        "0.0058",
        "  ",
        "-9",
        "1e5",
    )

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun profile(unitSystem: String) = UserProfile(
        sex = "MALE",
        weightKg = 80.0,
        heightCm = 180,
        birthDate = "1990-04-17",
        unitSystem = unitSystem,
    )

    /**
     * A restored backup can carry `"unitSystem": ""` — org.json applies an optString fallback only
     * to a key that is missing, not to one that is empty — and a hand-edited one can carry
     * anything. Every spelling that is not the stored one has to read as metric, because metric is
     * what the rest of the app prints for it.
     */
    @Test
    fun anyUnitSpellingButTheStoredOneReadsAsMetric() {
        listOf("", " ", "IMPERIAL ", " IMPERIAL", "US", "Imperial", "METRIC", "METRIC ")
            .forEach { assertFalse("'$it' read as imperial", profile(it).imperial) }
        assertTrue(profile("IMPERIAL").imperial)
    }

    /**
     * The mile is a definition and not a measurement, and the same function prints both a route
     * length and a speed — so an error in the constant's last digits is multiplied by a marathon in
     * one caller and by 3 600 in the other, and shows up as a route that reads 26.21 mi this
     * release and 26.22 the next with nothing on screen to say which was wrong.
     */
    @Test
    fun aDistanceSurvivesTheTripIntoMilesAndBack() {
        listOf(1.0, 42_195.0, 160_934.4, 18_000.0).forEach {
            val miles = distanceValue(it, imperial = true)
            assertEquals("$it m read as $miles mi", it, miles * 1_609.344, 1e-6)
            assertFalse("$it m read the same in both", miles == distanceValue(it, imperial = false))
        }
        // 100 miles, and the marathon by its imperial definition: 26 miles and 385 yards, which is
        // 26 * 1 609.344 + 385 * 0.9144 metres.
        assertEquals(100.0, distanceValue(160_934.4, imperial = true), 1e-9)
        assertEquals(26.21875, distanceValue(42_194.988, imperial = true), 1e-9)
        assertEquals(0.0, distanceValue(0.0, imperial = true), 0.0)
    }

    /**
     * The US ounce and the imperial one differ by four per cent — under a swallow in a glass, over
     * a glass across a day — so the round trip is checked at the small end too, where a British
     * constant would otherwise hide inside the rounding of a litre-sized figure.
     */
    @Test
    fun aVolumeSurvivesTheTripIntoFluidOuncesAndBack() {
        listOf(1.0, 29.5735295625, 100.0, 330.0, 500.0, 2_500.0, 10_000.0).forEach {
            val ounces = volumeValue(it, imperial = true)
            assertEquals("$it ml read as $ounces fl oz", it, ounces * 29.5735295625, 1e-9)
        }
        // A 128th of the 3.785411784 l gallon is the whole definition of the unit.
        assertEquals(1.0, volumeValue(3_785.411784 / 128.0, imperial = true), 1e-12)
        assertEquals(2.45, volumeValue(2_450.0, imperial = false), 1e-12)
    }

    /**
     * The foot is 0.3048 m exactly, and the climb is divided by that one figure: a reciprocal rounded
     * to ten digits, written beside it as a second factor, would drift by 1e-11 a metre, which nobody
     * notices until it is multiplied by a mountain. The same function carries an elevation loss,
     * where a sign lost on the way turns a descent into a climb.
     */
    @Test
    fun aClimbConvertedToFeetComesBackTheSameMountain() {
        listOf(0.001, 1.0, 12.5, 100.0, 8_848.0).forEach {
            val feet = heightValue(it, imperial = true)
            assertEquals("$it m read as $feet ft", it / 0.3048, feet, 0.0)
            assertEquals("$it m read as $feet ft", it, feet * 0.3048, it * 1e-9 + 1e-9)
        }
        val loss = heightValue(-12.5, imperial = true)
        assertTrue("a descent read as $loss ft", loss < 0.0)
        assertEquals(-12.5 / 0.3048, loss, 1e-6)
    }

    /**
     * -40 is the one reading identical on both scales and absolute zero is the far end of the same
     * line: an offset applied before the ratio, or a 5/9 written where 9/5 belongs, still prints a
     * plausible body temperature and gets both of these wrong.
     */
    @Test
    fun theFixedPointsOfFahrenheitAndTheWayBack() {
        assertEquals(-40.0, temperatureValue(-40.0, imperial = true), 1e-9)
        assertEquals(-459.67, temperatureValue(-273.15, imperial = true), 1e-9)
        listOf(-40.0, 0.0, 32.0, 35.0, 36.6, 38.0, 41.5).forEach {
            val fahrenheit = temperatureValue(it, imperial = true)
            assertEquals("$it °C read as $fahrenheit °F", it, (fahrenheit - 32.0) * 5.0 / 9.0, 1e-9)
            assertEquals("$it °C moved on the metric side", it, temperatureValue(it, imperial = false), 0.0)
        }
    }

    /**
     * The figure and its caption reach the card separately, and both functions are a one-line
     * ternary over the same boolean: a copy-paste that returns the metric id from both branches is
     * invisible on a metric phone and labels 84.5 fl oz as litres on the other one.
     */
    @Test
    fun theTwoCaptionsMoveWithTheirNumbers() {
        assertFalse(
            "both ${volumeUnitRes(imperial = true)}",
            volumeUnitRes(imperial = true) == volumeUnitRes(imperial = false),
        )
        assertFalse(
            "both ${temperatureUnitRes(imperial = true)}",
            temperatureUnitRes(imperial = true) == temperatureUnitRes(imperial = false),
        )
    }

    /**
     * The imperial side almost never lands on a whole number, so the shortcut that drops ".0" is
     * exercised on metric values alone. The one input that does land whole is the definition of the
     * unit itself, and an empty glass has to read "0" rather than "0.0" on either side.
     */
    @Test
    fun oneFluidOunceAndAnEmptyGlassPrintAsWholeNumbers() {
        assertEquals("1", formatVolumeNumber(29.5735295625, imperial = true, Locale.US))
        assertEquals("2", formatVolumeNumber(29.5735295625 * 2.0, imperial = true, Locale.US))
        assertEquals("0", formatVolumeNumber(0.0, imperial = true, Locale.US))
        assertEquals("0", formatVolumeNumber(0.0, imperial = false, Locale.US))
    }

    /**
     * A day's water is a sum of stored figures and a target is a divisor, so one corrupted row
     * upstream arrives here as a NaN. This is the home screen: whatever comes out has to be an odd
     * caption rather than a crash on the first thing shown after unlock.
     */
    @Test
    fun aCorruptedTotalDoesNotTakeTheWaterCardDown() {
        listOf(
            formatVolumeNumber(Double.NaN, imperial = false, Locale.US),
            formatVolumeNumber(Double.POSITIVE_INFINITY, imperial = true, Locale.US),
            formatVolumeNumber(Double.NEGATIVE_INFINITY, imperial = false, Locale.US),
            formatVolumeNumber(Double.MAX_VALUE, imperial = true, Locale.US),
            formatVolumeNumber(-330.0, imperial = false, Locale.US),
        ).forEach { assertTrue("printed '$it'", it.isNotEmpty()) }
        val negative = formatVolumeNumber(-330.0, imperial = false, Locale.US)
        assertTrue("-330 ml printed as '$negative'", negative.startsWith("-"))
    }

    /**
     * The litre figure is the millilitres rounded to a single decimal, half up in both directions —
     * the goal is rounded by the same function and is not itself a round figure (a 73 kg profile
     * asks for 2 190 ml), and the ring the pair captions is drawn from the raw millilitres, so a
     * 2 450 ml day printing "2.5" beside a 2 500 ml goal never fills it. What the caption does owe
     * is its own last digit: half a decilitre, on a scale where a divisor that drifted or a locale
     * that writes a comma is out by whole litres.
     */
    @Test
    fun theWaterCardsLitreFigureStaysWithinHalfADecilitre() {
        listOf(990.0, 1_750.0, 2_450.0).forEach {
            val printed = formatVolumeNumber(it, imperial = false, Locale.US).toDoubleOrNull()
            assertTrue("$it ml printed unparseably", printed != null)
            assertTrue(
                "$it ml printed as $printed l",
                abs((printed ?: 0.0) - it / 1_000.0) <= 0.05 + 1e-9,
            )
        }
    }

    /**
     * What [pretty] prints goes into a title, an export or a field and is read back. Locale.US is
     * pinned in exactly one place; dropping it compiles, passes on an American machine and writes
     * "12,3" on half the planet, where the figure can no longer be parsed back at all.
     */
    @Test
    fun theseNumbersStayMachineReadableWhereTheLocaleWritesAComma() {
        Locale.setDefault(Locale.GERMANY)
        val quarter = (12.34).pretty()
        val negative = (-0.5).pretty()
        listOf(quarter, negative).forEach {
            assertFalse("printed '$it'", it.contains(','))
            assertTrue("printed '$it'", it.toDoubleOrNull() != null)
        }
        assertEquals("12.3", quarter)
    }

    /**
     * The water card's figure is read by a person and by nothing else, so it takes the mark of the
     * language it is shown in, as the weight beside it does: an imperial day never reads "84.5 fl oz" in
     * German over a body weight of "72,4". The machine's default plays no part.
     */
    @Test
    fun theWaterFigureTakesTheDecimalMarkOfTheLanguageShowing() {
        Locale.setDefault(Locale.US)
        assertEquals("84,5", formatVolumeNumber(2_500.0, imperial = true, Locale.GERMANY))
        assertEquals("1,5", formatVolumeNumber(1_500.0, imperial = false, Locale.forLanguageTag("ru")))
        assertEquals("2", formatVolumeNumber(2_000.0, imperial = false, Locale.GERMANY))
        assertEquals("84.5", formatVolumeNumber(2_500.0, imperial = true, Locale.US))
    }

    /**
     * The other half of that rule. What a person reads on a screen takes the decimal mark of the
     * language it is shown in, as a string resource's own `%.1f` does: the profile never prints a
     * weight of "72.4 kg" over a BMI of "23,5". The rounding is [pretty]'s, so a figure printed both
     * ways is one figure, and a composable asks for it as `shown()`.
     */
    @Test
    fun aFigureOnScreenTakesTheDecimalMarkOfItsLanguage() {
        assertEquals("72,4", 72.4.pretty(Locale.GERMANY))
        assertEquals("72,4", 72.4.pretty(Locale.forLanguageTag("ru")))
        assertEquals("72.4", 72.4.pretty(Locale.US))
        assertEquals("72", 72.0.pretty(Locale.GERMANY))
        // No grouping, as [pretty] has none: the figure is the same one with another mark.
        assertEquals("2147483648", 2_147_483_648.0.pretty(Locale.GERMANY))
        listOf(12.34, 0.25, 72.45, -0.5, 1_000_000.26).forEach {
            assertEquals(it.pretty(), it.pretty(Locale.GERMANY).replace(',', '.'))
        }
        assertEquals(String.format(Locale.GERMANY, "%.1f", 23.46), 23.46.pretty(Locale.GERMANY))
        val profile = File("src/main/java/com/pwnall/health/ProfileScreen.kt").readText()
        assertTrue("\"\${profile.weightKg.shown()} \${stringResource(R.string.unit_kg)}\"" in profile)
    }

    /**
     * A lab value is printed for the sheet at its own precision, with a point; on a screen it keeps
     * every digit and takes the language's mark: a TSH of 0.035 never reads "0.035" in Russian over a body
     * weight of "72,4". Text that is no plain figure is left as it came.
     */
    @Test
    fun aSheetsFigureOnScreenKeepsItsDigitsAndTakesTheLanguagesMark() {
        assertEquals("0,035", shownFigure("0.035", Locale.forLanguageTag("ru")))
        assertEquals("5,70", shownFigure("5.70", Locale.GERMANY))
        assertEquals("-1,5", shownFigure("-1.5", Locale.GERMANY))
        assertEquals("259", shownFigure("259", Locale.GERMANY))
        assertEquals("0.035", shownFigure("0.035", Locale.US))
        assertEquals("<0.15", shownFigure("<0.15", Locale.GERMANY))
        assertEquals("", shownFigure("", Locale.GERMANY))
    }

    /**
     * Two branches with different formatting machinery, and only the fractional one is bounded by
     * the format string. Whatever comes out has to parse back to the number that went in, to within
     * half of the last digit printed — one invariant that catches a locale slip, a lost sign and
     * any future change of rounding.
     */
    @Test
    fun whatPrettyPrintsParsesBackToWithinHalfItsLastDigit() {
        val sweep = listOf(0.0, 0.04, 0.35, 1.75, 2.45, 12.34, 84.5350567546075, 881.84904872, -3.0, -0.04)
        val whole = listOf(1_234.0, 1_000_000.25, 2_147_483_646.0)
        (sweep + whole).forEach {
            val printed = it.pretty()
            val parsed = printed.toDoubleOrNull()
            assertTrue("$it printed as '$printed', which does not parse", parsed != null)
            assertEquals("$it printed as '$printed'", it, parsed ?: Double.NaN, 0.0501)
        }
    }

    /**
     * A workout's volume is sets × reps × load and the input filters admit 9 999 × 9 999 ×
     * 1 234 567.99; a serving or a calorie figure out of a hand-edited backup has no ceiling at
     * all. Double.toInt() saturates instead of wrapping, so every whole value at or above 2^31
     * prints as the same 2 147 483 647 — not a formatting glitch anybody would spot, but a
     * different number that still looks like a real one.
     */
    @Test
    fun aWholeNumberTooBigForAnIntStillPrintsAsItself() {
        assertEquals("2147483648", 2_147_483_648.0.pretty())
        assertEquals("3000000000", 3_000_000_000.0.pretty())
        assertEquals("1000000000000", (1e12).pretty())
        assertEquals("-3000000000", (-3_000_000_000.0).pretty())
    }

    /**
     * The filter runs on every keystroke over the text it produced last time, so it has to be a
     * fixed point of itself. The whole part and the fraction are capped separately after a splice,
     * and a cap that fires on the second pass would shorten text nobody touched.
     */
    @Test
    fun theKeystrokeFiltersAreFixedPointsOfThemselves() {
        filterCorpus.forEach {
            assertEquals("from '$it'", it.decimalInput(), it.decimalInput().decimalInput())
            assertEquals("from '$it'", it.labDecimalInput(), it.labDecimalInput().labDecimalInput())
            assertEquals("from '$it'", it.digitsOnly(), it.digitsOnly().digitsOnly())
        }
    }

    /**
     * An Arabic or Persian keyboard sends ٧٢ on a Decimal field. Char.isDigit is true for every
     * Unicode decimal digit and Double.parseDouble accepts ASCII alone, so the field keeps a figure
     * the save path then reads as null: a routine silently becomes ten minutes, a session's kcal is
     * stored absent, and a typed lab reading is marked refused with the number still on screen.
     */
    @Test
    fun aNumericFieldKeepsNothingItsOwnParserWouldRefuse() {
        listOf("٧٢", "१२", "７２", "72").forEach {
            val decimal = it.decimalInput()
            assertTrue("'$it' kept as '$decimal'", decimal.isEmpty() || decimal.toDoubleOrNull() != null)
            val lab = it.labDecimalInput()
            assertTrue("'$it' kept as '$lab'", lab.isEmpty() || lab.toDoubleOrNull() != null)
            val digits = it.digitsOnly()
            assertTrue("'$it' kept as '$digits'", digits.isEmpty() || digits.toIntOrNull() != null)
        }
    }

    /**
     * The two filters are a copy-paste pair in two files, differing by the one decimal place a
     * suppressed thyroid needs. A fix applied to the whole-part cap or to the comma in one of them
     * and not the other drifts them apart, and only the TSH case would ever be noticed.
     */
    @Test
    fun theLabFieldNeverKeepsLessThanTheGramFieldItWasForkedFrom() {
        filterCorpus.forEach {
            val gram = it.decimalInput()
            val lab = it.labDecimalInput()
            assertTrue("'$it' -> gram '$gram', lab '$lab'", lab.startsWith(gram))
            assertTrue("'$it' -> gram '$gram', lab '$lab'", lab.length >= gram.length)
        }
    }

    /**
     * The cap is applied after the filter, so the characters dropped decide which four digits
     * survive. A minus cannot be typed into a rep count, and "0012" still has to read back as 12
     * rather than as nothing at all.
     */
    @Test
    fun theFourDigitFieldDropsASignAndKeepsALeadingZero() {
        assertEquals("25", "-25".digitsOnly())
        assertEquals("12", "+12".digitsOnly())
        assertEquals("0012", "0012".digitsOnly())
        assertEquals(12, "0012".digitsOnly().toInt())
        assertEquals("1234", "1 2 3 4 5".digitsOnly())
        assertEquals("", "-".digitsOnly())
    }

    /**
     * Int.MIN_VALUE has no positive counterpart, so any abs() or unary minus on the way in
     * overflows straight back to a negative and the card prints "-35791394:-8". The coerce is the
     * only thing between that and the home screen, and a whole day asleep is 24:00, not 0:00.
     */
    @Test
    fun theSleepClockSurvivesTheExtremesOfTheIntItTakes() {
        assertEquals("0:00", formatSleepClock(Int.MIN_VALUE))
        assertEquals("0:00", formatSleepClock(-1))
        assertEquals("0:59", formatSleepClock(59))
        assertEquals("1:00", formatSleepClock(60))
        assertEquals("24:00", formatSleepClock(1_440))
        val extreme = formatSleepClock(Int.MAX_VALUE)
        assertFalse("printed '$extreme'", extreme.contains("-"))
        val minutes = extreme.substringAfter(':')
        assertTrue("printed '$extreme'", minutes.length == 2 && (minutes.toIntOrNull() ?: 60) < 60)
    }

    /**
     * A few sample heights can all happen to round the same way. The ruler runs 50..250 cm,
     * and an inch count reaching 12 through a rounding would print "5′ 12″" — a height nobody
     * writes and a figure that reads an inch short of the truth.
     */
    @Test
    fun feetAndInchesStayWithinHalfAnInchAcrossTheWholeRuler() {
        (50..250).forEach { cm ->
            val printed = imperialHeight(cm)
            val feet = printed.substringBefore('′').trim().toInt()
            val inches = printed.substringAfter('′').trim().removeSuffix("″").toInt()
            assertTrue("$cm cm printed as '$printed'", inches in 0..11)
            assertTrue("$cm cm printed as '$printed'", feet > 0)
            assertEquals("$cm cm printed as '$printed'", cm / 2.54, feet * 12.0 + inches, 0.5 + 1e-9)
        }
        assertEquals("1′ 8″", imperialHeight(50))
        assertEquals("8′ 2″", imperialHeight(250))
    }

    /**
     * The pound is 0.45359237 kg exactly, and the factor for it is written out in four files. The
     * weight wheel is built from it in tenths of a pound — 441..8 818 for the 20..400 kg the editor
     * clamps to — and divides by it again on the way back, so a factor that drifts moves the ends
     * of the ruler without moving the kilograms they are supposed to stand for.
     */
    @Test
    fun poundsMatchTheDefinitionOfThePoundAtBothClamps() {
        assertEquals(0.0, imperialWeight(0.0), 0.0)
        listOf(20.0, 70.0, 400.0).forEach {
            assertEquals("$it kg", it / 0.45359237, imperialWeight(it), 1e-6)
            val tenths = (imperialWeight(it) * 10).roundToInt()
            assertEquals("$it kg is $tenths tenths of a pound", it, tenths / 10.0 / 2.2046226218, 0.023)
        }
        assertEquals(441, (imperialWeight(20.0) * 10).roundToInt())
        assertEquals(8_818, (imperialWeight(400.0) * 10).roundToInt())
    }
}
