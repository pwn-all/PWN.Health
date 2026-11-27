package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the OCR geometry: the rules that turn a bag of detected boxes into text a
 * parser can pair up, and the raster arithmetic underneath them.
 *
 * These are the layouts real packets are full of and the other fixtures are not — a US panel with
 * its serving size printed above the calories, a Japanese panel printing fullwidth digits, a Gulf
 * panel with its per-100 column on the left, a photograph one pixel tall out of a slipped drag.
 */
class OcrGeometryEdgeCasesExtraTest {

    /**
     * Every US panel prints "Serving size 2/3 cup (55g)" directly above the word "Calories". That
     * 55 is grams of crisps, not kilocalories, and the row that owns it is prose rather than a bare
     * number — the KDoc's own rule. A silent kcal of 55 is worse than reading no calories at all.
     */
    @Test
    fun `a row that names a nutrient does not adopt a figure out of a neighbour's prose`() {
        val page = listOf(
            box(0, 0, 300, 20, "Serving size 2/3 cup (55g)"),
            box(0, 30, 90, 50, "Calories"),
            box(0, 60, 200, 80, "Total Fat 8 g"),
        )

        val calories = page.asTableText().lines().first { it.contains("Calories") }

        assertEquals("Calories", calories)
    }

    /**
     * A Japanese carton prints its figures in fullwidth digits. Those are digits to Char.isDigit and
     * nothing at all to an ASCII \d, so a row of them could pass the lend test, win the choice of
     * donor, and then hand over nothing — and the 450 kJ printed directly below would never arrive.
     */
    @Test
    fun `a neighbour in fullwidth digits does not shadow the one with a real figure`() {
        val page = listOf(
            box(0, 0, 60, 20, "１２"),
            box(0, 30, 90, 50, "熱量"),
            box(0, 60, 200, 80, "450 kJ"),
        )

        val energy = page.asTableText().lines().first { it.contains("熱量") }

        assertTrue("was $energy", energy.contains("450"))
    }

    /**
     * An anchor that moved with every box added would walk down the page and swallow the lower half
     * of a table into one line — eight row names followed by twelve figures that no parser could pair
     * again. A staircase is the only shape that separates a fixed anchor from a drifting one.
     */
    @Test
    fun `a row's band is fixed by the region that opened it`() {
        val page = listOf(
            box(0, 0, 100, 20, "a"),
            box(200, 8, 300, 28, "b"),
            box(400, 16, 500, 36, "c"),
        )

        assertEquals(listOf("a b", "c"), page.asLineText().lines())
    }

    /**
     * Half of the shorter box's height, measured against the box that opened the row. Shifting that
     * by one pixel on a dense table re-pairs every nutrient with the wrong figure.
     */
    @Test
    fun `half of the shorter box's height is the rule for sharing a row`() {
        val name = box(0, 0, 100, 20, "name")
        val exactlyHalf = listOf(name, box(200, 10, 300, 30, "12"))
        val oneLess = listOf(name, box(200, 11, 300, 31, "12"))

        assertEquals(listOf("name 12"), exactlyHalf.asLineText().lines())
        assertEquals(listOf("name", "12"), oneLess.asLineText().lines())
    }

    /** A tilted line's boxes share a vertical centre, so reading order promises nothing about x. */
    @Test
    fun `a row is emitted left to right whatever order its boxes arrived in`() {
        val page = listOf(box(300, 0, 400, 20, "267 kJ"), box(0, 0, 200, 20, "Energie"))

        assertEquals("Energie 267 kJ", page.asLineText())
    }

    /**
     * A Gulf panel prints the per-100 g column first and the per-serving column beside it. Keeping
     * the last figure of each row reads the serving column and labels it per 100 g, so the figures
     * are then wrong by whatever the serving happens to be.
     */
    @Test
    fun `the per hundred column is chosen by position even when it is printed first`() {
        val page = listOf(
            box(100, 0, 300, 20, "Per 100 g"),
            box(400, 0, 600, 20, "Per serving"),
            box(0, 40, 80, 60, "Energy"),
            box(150, 40, 250, 60, "1190"),
            box(450, 40, 550, 60, "593"),
            box(0, 80, 80, 100, "Protein"),
            box(150, 80, 250, 100, "6.8"),
            box(450, 80, 550, 100, "3.4"),
        )

        val kept = requireNotNull(page.asPerHundredColumn())

        assertTrue(kept, kept.contains("1190") && !kept.contains("593"))
        assertTrue(kept, kept.contains("6.8") && !kept.contains("3.4"))
    }

    /** Per serving, per 100 g and per 100 ml: two of the three have to go, not one of them. */
    @Test
    fun `three figure columns collapse to the one under the named header`() {
        val page = listOf(
            box(300, 0, 420, 20, "per serving"),
            box(600, 0, 700, 20, "per 100 g"),
            box(850, 0, 950, 20, "per 100 ml"),
            box(0, 40, 200, 60, "Energy"),
            box(310, 40, 410, 60, "593"),
            box(600, 40, 700, 60, "1190"),
            box(850, 40, 950, 60, "281"),
        )

        val energy = requireNotNull(page.asPerHundredColumn())
            .lines()
            .first { it.startsWith("Energy") }

        assertEquals("Energy 1190", energy)
    }

    /**
     * The result ends with a literal "per 100 g" that the parser then believes. With no header
     * naming a basis there is no evidence which column is which, and relabelling an Australian
     * tin's per-serving figures as per 100 g is exactly the failure this rule exists to prevent.
     */
    @Test
    fun `two columns with no basis header leave the table alone`() {
        val page = listOf(
            box(300, 0, 520, 20, "Amount per serving"),
            box(600, 0, 780, 20, "Amount per pack"),
            box(0, 40, 120, 60, "Energy"),
            box(320, 40, 400, 60, "593"),
            box(600, 40, 690, 60, "1190"),
            box(0, 80, 120, 100, "Protein"),
            box(320, 80, 380, 100, "3.4"),
            box(600, 80, 660, 100, "6.8"),
        )

        assertNull(page.asPerHundredColumn())
    }

    /**
     * German, Czech, French, Spanish and Polish headings are known beside English and Russian ones:
     * a two-column table whose heading is not known never has its 100 g column chosen and is read
     * left to right instead. A percent after the hundred is a claim on the packet, not a heading.
     */
    @Test
    fun `the per hundred column is found under the heading of every shelf the parser reads`() {
        fun page(heading: String) = listOf(
            box(100, 0, 300, 20, "Portion 30 g"),
            box(400, 0, 600, 20, heading),
            box(0, 40, 80, 60, "Energie"),
            box(150, 40, 250, 60, "593"),
            box(450, 40, 550, 60, "1190"),
        )

        for (heading in listOf("pro 100 g", "je 100 g", "na 100 g", "pour 100 g", "por 100 g", "w 100 g")) {
            val kept = requireNotNull(page(heading).asPerHundredColumn()) { heading }
            assertTrue("$heading: $kept", kept.contains("1190") && !kept.contains("593"))
        }
        assertNull(page("w 100% naturalny").asPerHundredColumn())
        assertNull(page("Dina 100 g").asPerHundredColumn())
    }

    /**
     * A milk carton's 64 kcal is set in the largest type on the packet; a "100" split out of the
     * "per 100 g" heading is merely large. Both clear the height gate, and the larger number is not
     * the louder one. This path runs only when nothing else on the page read an energy at all, so
     * whichever figure it picks is what gets written down as dinner.
     */
    @Test
    fun `the headline is the figure in the largest type not the largest number`() {
        val panel = listOf(
            box(0, 0, 180, 20, "Nutrition Facts"),
            box(0, 30, 100, 90, "64"),
            box(200, 30, 260, 62, "100"),
            box(0, 100, 120, 120, "Total Fat"),
            box(200, 100, 260, 120, "5.0g"),
            box(0, 130, 120, 150, "Sodium"),
            box(200, 130, 280, 150, "860mg"),
            box(0, 160, 120, 180, "Protein"),
            box(200, 160, 260, 180, "18g"),
        )

        assertEquals(64.0, requireNotNull(panel.headlineFigure()), 0.01)
    }

    /**
     * The "2,000 calories a day" footnote and a net weight in grams are printed large too, and
     * neither is a per-serving calorie count. Nothing on a panel is nine calories either.
     */
    @Test
    fun `the headline has to be a plausible calorie count`() {
        assertEquals(250.0, requireNotNull(headlinePanel("1000", "250").headlineFigure()), 0.01)
        assertNull(headlinePanel("9", "9").headlineFigure())
        assertEquals(10.0, requireNotNull(headlinePanel("10", "10").headlineFigure()), 0.01)
        assertEquals(999.0, requireNotNull(headlinePanel("999", "999").headlineFigure()), 0.01)
    }

    /**
     * Both gates decide whether a bare number becomes someone's dinner: the page has to look like a
     * panel at all, and the figure has to stand out by half again over the median line.
     */
    @Test
    fun `the headline needs a panel's worth of boxes and a figure that stands out`() {
        assertNull(headlineWith(count = 7, tall = 60).headlineFigure())
        assertEquals(250.0, requireNotNull(headlineWith(count = 8, tall = 60).headlineFigure()), 0.01)
        assertEquals(250.0, requireNotNull(headlineWith(count = 9, tall = 30).headlineFigure()), 0.01)
        assertNull(headlineWith(count = 9, tall = 29).headlineFigure())
    }

    /** A frame the detector found nothing in is an everyday outcome, not an error to throw on. */
    @Test
    fun `an empty page produces empty text and no claims`() {
        val nothing = emptyList<TextBox>()

        assertEquals("", nothing.asBoxText())
        assertEquals("", nothing.asLineText())
        assertEquals("", nothing.asTableText())
        assertNull(nothing.asPerHundredColumn())
        assertNull(nothing.headlineFigure())
        assertNull(nothing.panelBounds(1000, 1000))

        val one = listOf(box(0, 0, 100, 20, "Calories"))

        assertEquals("Calories", one.asBoxText())
        assertEquals("Calories", one.asLineText())
        assertEquals("Calories", one.asTableText())
        assertNull(one.asPerHundredColumn())
        assertNull(one.headlineFigure())
        assertNull(one.panelBounds(1000, 1000))
    }

    /**
     * The crop is the crowd of table words and figures, padded by one percent of width plus height.
     * A pad derived from that sum means a portrait frame pads differently from a landscape one of
     * the same area, so the number is worth pinning rather than eyeballing.
     */
    @Test
    fun `the panel crop is padded by one percent of the frame`() {
        val corner = (0..7).map { box(100, 100 + it * 12, 200, 116 + it * 12, "Protein 7 g") }

        assertEquals(Box(80, 80, 220, 220), corner.panelBounds(1000, 1000))
        // Fewer than a panel's worth of table words is not a panel to cut out.
        assertNull(corner.take(7).panelBounds(1000, 1000))
    }

    /** Re-reading the whole photograph closer is just re-reading the whole photograph. */
    @Test
    fun `a panel that covers most of the frame is not worth cutting to`() {
        // Padded, these span 800 by 749 and 800 by 750 of a million-pixel frame: 59.92% and 60%.
        assertNotNull(spread(bottom = 729).panelBounds(1000, 1000))
        assertNull(spread(bottom = 730).panelBounds(1000, 1000))
    }

    /**
     * The reader resizes whatever raster it is handed to a stride multiple before detection, and a
     * drag begun on the last column of the crop dialog hands it a one-pixel strip. Its sibling
     * sampled() refuses anything under two pixels a side; this one has to resize the strip without
     * reading past its own array.
     */
    @Test
    fun `a one pixel tall photograph does not crash the resize`() {
        val strip = Raster(5, 1, IntArray(5) { 0xFF808080.toInt() })
        val column = Raster(1, 5, IntArray(5) { 0xFF808080.toInt() })

        assertEquals(32, strip.resized(32, 32).width)
        assertEquals(32, strip.resized(32, 32).height)
        assertEquals(32, column.resized(32, 32).width)
        assertEquals(32, column.resized(32, 32).height)
    }

    /**
     * The transpose crosses every one of its axes on purpose. A clockwise-for-anticlockwise slip
     * still reads a square test image correctly, and every photograph is the case that catches it.
     */
    @Test
    fun `quarter turns round trip and a non quarter angle changes nothing`() {
        val page = Raster(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))

        val quarter = page.turned(90)
        assertEquals(3, quarter.width)
        assertEquals(2, quarter.height)
        assertEquals(listOf(5, 3, 1, 6, 4, 2), quarter.argb.toList())

        val fullCircle = page.turned(90).turned(90).turned(90).turned(90)
        assertEquals(2, fullCircle.width)
        assertEquals(3, fullCircle.height)
        assertEquals(page.argb.toList(), fullCircle.argb.toList())
        assertEquals(page.argb.toList(), page.turned(180).turned(180).argb.toList())
        assertEquals(page.argb.toList(), page.turned(90).turned(270).argb.toList())
        assertEquals(page.argb.toList(), page.turned(45).argb.toList())
        assertEquals(2, page.turned(45).width)
    }

    /**
     * A drag can end above where it started, and a crop can name a box entirely off the frame.
     * Rebuilding x1 as x0 + 1 at the least turns either into a one-pixel strip, so the refusal has
     * to come from the minimum side rather than from the clamp — and the clamp must not throw.
     */
    @Test
    fun `a crop of the minimum side is read and one pixel less is not`() {
        val page = Raster(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })

        val smallest = requireNotNull(page.cropped(Box(0, 0, 32, 32)))

        assertEquals(32, smallest.width)
        assertEquals(32, smallest.height)
        assertEquals(32 * 32, smallest.argb.size)
        assertNull(page.cropped(Box(0, 0, 31, 31)))
        assertNull(page.cropped(Box(50, 50, 10, 10)))
        assertNull(page.cropped(Box(200, 200, 300, 300)))
    }

    /**
     * The stretch divides by the distance between the second and ninety-eighth percentiles, so the
     * guard that leaves a flat photograph alone is also the guard against dividing by zero. Pulling
     * twenty levels of sensor noise out to full range is worse than leaving the grey where it is.
     */
    @Test
    fun `a flat image is left alone by the contrast stretch`() {
        val flat = Raster(32, 32, IntArray(32 * 32) { 0xFF808080.toInt() })

        assertEquals(flat.argb.toList(), flat.stretched().argb.toList())

        val narrow = Raster(
            32,
            32,
            IntArray(32 * 32) { index -> if (index % 2 == 0) 0xFF646464.toInt() else 0xFF787878.toInt() },
        )

        assertEquals(narrow.argb.toList(), narrow.stretched().argb.toList())
    }

    /**
     * The recognizer reads rows of one fixed height, so the width has to carry the line's aspect: a
     * line twice as long as it is thick, sampled 48 rows tall, is 96 columns wide. The divisor is a
     * thickness that can legitimately approach zero, which is why a hairline is refused outright.
     */
    @Test
    fun `a crop is sampled at the width its own aspect asks for`() {
        val page = Raster(200, 200, IntArray(200 * 200) { 0xFF808080.toInt() })
        val line = Region(cx = 100.0, cy = 100.0, ux = 48.0, uy = 0.0, vx = 0.0, vy = 24.0)

        val crop = requireNotNull(page.sampled(line, outHeight = 48, minWidth = 16, maxWidth = 1600))
        assertEquals(96, crop.width)
        assertEquals(48, crop.height)

        val clamped = requireNotNull(page.sampled(line, outHeight = 48, minWidth = 16, maxWidth = 50))
        assertEquals(50, clamped.width)

        val hairline = Region(cx = 100.0, cy = 100.0, ux = 48.0, uy = 0.0, vx = 0.0, vy = 0.25)
        assertNull(page.sampled(hairline, outHeight = 48, minWidth = 16, maxWidth = 1600))

        val speck = Raster(1, 1, IntArray(1))
        assertNull(speck.sampled(line, outHeight = 48, minWidth = 16, maxWidth = 1600))
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int, text: String) = TextBox(Box(x0, y0, x1, y1), text)

    /** Nine boxes anchored by the panel's own words, of which only [first] and [second] are large. */
    private fun headlinePanel(first: String, second: String): List<TextBox> = listOf(
        box(0, 0, 180, 20, "Nutrition Facts"),
        box(0, 30, 100, 90, first),
        box(200, 30, 300, 90, second),
        box(0, 100, 120, 120, "Total Fat"),
        box(200, 100, 260, 120, "5.0g"),
        box(0, 130, 120, 150, "Sodium"),
        box(200, 130, 280, 150, "860mg"),
        box(0, 160, 120, 180, "Protein"),
        box(200, 160, 260, 180, "18g"),
    )

    /** [count] boxes 20 px tall around one figure box [tall] px tall, so the median stays 20. */
    private fun headlineWith(count: Int, tall: Int): List<TextBox> =
        listOf(box(0, 0, 180, 20, "Nutrition Facts")) +
            (1 until count).map { index ->
                val y0 = index * 40
                if (index == 1) box(0, y0, 100, y0 + tall, "250") else box(0, y0, 120, y0 + 20, "Protein")
            }

    /** Eight table rows from y 20 down to [bottom], across x 20 to 780 of a 1000 px frame. */
    private fun spread(bottom: Int): List<TextBox> = (0..7).map { index ->
        val y0 = if (index == 7) bottom - 16 else 20 + index * 90
        box(20, y0, 780, y0 + 16, "Protein 7 g")
    }
}
