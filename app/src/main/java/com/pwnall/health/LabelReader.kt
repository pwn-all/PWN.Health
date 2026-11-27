package com.pwnall.health

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * What the reader is doing, said in terms of what it has actually found.
 *
 * Reading a packet takes seconds and a spinner says nothing about them. These are the steps the
 * ladder really takes — the turn it is looking from, how much text came back, what parsed out of
 * it, and the moment it gives up on the whole frame and goes closer — so the wait is legible
 * rather than merely animated.
 */
internal sealed interface LabelStep {
    data class Turning(val degrees: Int) : LabelStep

    data class Read(val fragments: Int) : LabelStep

    data class Found(val reading: NutritionLabel) : LabelStep

    /** Nothing usable in the whole frame; the panel is being cut out and read on its own. */
    data object Closer : LabelStep

    data object Done : LabelStep
}

/**
 * The PP-OCR pipeline over a photograph, entirely on the device.
 *
 * PP-OCR via ONNX Runtime: its mobile recognizers keep Czech diacritics, Cyrillic and Japanese
 * alike, and read the figures of a nutrition table, where Tesseract returns "297M/ekgll" for
 * a printed "267kJ/64kcal". This app holds no INTERNET permission ([NoNetworkPermissionTest]
 * enforces it); the det/rec models (~39 MB) ship in assets/ocr like the classifiers' models do.
 *
 * The detector and the Latin/CJK recognizer are PP-OCRv6 small: one recognizer reads Latin and
 * Japanese both. docs/DESIGN.md §19 gives the measurements behind the choice of models.
 *
 * OnnxOcrTest reads two photographs through it in every build.
 *
 * No OpenCV, no document unwarping, no orientation classifier: the reading ladder turns the frame
 * instead, and the detector's axis-aligned boxes are enough once it has.
 */
internal object LabelReader {
    /**
     * The nutrition table on the photograph, read through a ladder of attempts. Blocking for
     * seconds — call it off the main thread. Cancellation is honoured between rungs: a dismissed
     * sheet is owed none of the remaining turns, and one turn of PP-OCR is seconds of them.
     *
     * Each quarter turn is detected once and read in up to three scripts, each read offered to
     * the parser three ways: box-by-box, whose line breaks the parser's wrap carry-over already
     * pairs; reassembled into printed lines for the tables that keep name and figure side by
     * side; and as a table, where a row naming a nutrient adopts the figure printed above or below
     * it — the layout every US panel uses for its calories.
     *
     * A parse that reads energy plus two macros ends the climb; anything less keeps the best
     * attempt. The serving size and the per-100 g basis are believed from any pass of the same
     * photograph — every pass read the same packet — and a declared per-100 g basis outranks a
     * serving size, because a pot printing both means the first for its figures.
     */
    suspend fun label(
        context: Context,
        photo: Uri,
        onStep: (LabelStep) -> Unit = {},
    ): NutritionLabel {
        // The ladder runs inside a Sequence, where arbitrary suspension is not allowed, so the
        // caller's context is carried in rather than asked for again at each rung.
        val work = currentCoroutineContext()
        // The pixels are copied out, so the bitmap's dozen megabytes go now rather than at some later
        // collection, while the seconds of climbing that follow hold the copy alone.
        val raster = decode(context, photo).let { photograph ->
            try {
                photograph.toRaster()
            } finally {
                photograph.recycle()
            }
        }
        return withEngine(context) { climb(it, raster, work, onStep) }.also { onStep(LabelStep.Done) }
    }

    /**
     * Internal so a JVM test can climb the real ladder over real photographs without an Android
     * [Context]: a test that reimplemented the ladder would measure itself.
     */
    internal fun climb(
        ocr: OnnxOcr,
        raster: Raster,
        work: CoroutineContext,
        onStep: (LabelStep) -> Unit = {},
    ): NutritionLabel {
        var best = NutritionLabel()
        var serving: Double? = null
        var perHundred = false
        var rich: NutritionLabel? = null
        // A turn is read to the end even once a rich parse arrives: the serving size is printed in
        // its own row, and neither the assembly nor the script that pairs the macros is usually the
        // one that pairs that. A rich parse with no basis at all keeps the ladder going — a
        // sideways carton reads its macros upright and its "1本（240ml）当り" only after the turn —
        // and a per-100 g basis seen anywhere outranks a serving size seen anywhere else, because a
        // yoghurt pot prints both and only one of them is what its figures are for.
        for (turn in turns(ocr, raster, work, onStep)) {
            for (reading in turn.flatten()) {
                if (reading.found) onStep(LabelStep.Found(reading))
                if (serving == null) serving = reading.perServingG
                perHundred = perHundred || reading.perHundred
                if (rich == null && reading.rich) rich = reading
                if (reading.rank > best.rank) best = reading
            }
            val found = rich
            if (found != null && (serving != null || perHundred)) {
                return found.onBasis(serving, perHundred)
            }
        }
        return (rich ?: best).onBasis(serving, perHundred)
    }

    private fun NutritionLabel.onBasis(serving: Double?, perHundred: Boolean): NutritionLabel = when {
        perHundred -> if (perServingG == null) this else copy(perServingG = null)
        perServingG == null && serving != null -> copy(perServingG = serving)
        else -> this
    }

    /**
     * What one page of boxes is worth, read four ways.
     *
     * Three are assemblies of the same text — box by box, as printed lines, and as a table where a
     * row adopts the figure printed above it. Ahead of them, when the panel has one, goes the column
     * its header names: the ladder keeps the first rich parse it meets, and the box-by-box assembly
     * of a two-column panel is rich too, with the serving column's figures, since those stand
     * nearest each nutrient's name — so an Australian tin would read its "per serving" numbers and
     * file them as per 100 g. The fifth only exists where the others failed to find any energy at all:
     * the panel's headline figure, taken as the calories because that is what the largest type on
     * a nutrition panel always is. It is a fallback and never a competitor — a page that read its
     * calories properly never reaches it. It is also the second case of
     * [NutritionLabel.approximate]: the figure came from the type size and not from a word beside
     * it, so it is a guess about typography however well it parses back in.
     */
    internal fun readingsOf(page: List<TextBox>): List<NutritionLabel> {
        val table = page.asTableText()
        val read = listOfNotNull(
            page.asPerHundredColumn()?.let(::nutritionLabel),
            nutritionLabel(page.asBoxText()),
            nutritionLabel(page.asLineText()),
            nutritionLabel(table),
        )
        val headline = page.headlineFigure().takeIf { read.all { parse -> parse.kcal == null } }
        return read + listOfNotNull(
            headline?.let { nutritionLabel("$table\ncalories $it").copy(approximate = true) },
        )
    }

    /** The ladder's parses, a quarter turn at a time, every script of that turn inside it. */
    private fun turns(
        ocr: OnnxOcr,
        raster: Raster,
        work: CoroutineContext,
        onStep: (LabelStep) -> Unit,
    ): Sequence<List<List<NutritionLabel>>> = sequence {
        for (degrees in TURNS) {
            work.ensureActive()
            onStep(LabelStep.Turning(degrees))
            val turned = raster.turned(degrees)
            val regions = ocr.regions(turned)
            val pages = regions.takeIf { it.isNotEmpty() }?.let {
                OcrScript.entries.map { script ->
                    work.ensureActive()
                    ocr.read(turned, regions, script)
                }
            }.orEmpty()
            if (pages.isNotEmpty()) {
                onStep(LabelStep.Read(pages.maxOf { it.size }))
                yield(pages.map(::readingsOf))
            }

            // Only reached when the turn above satisfied nobody, because a sequence is lazy: the
            // panel is cut out of the frame and read again on its own. Detection normalises the
            // long side to 960 whatever it is handed, so a table filling a third of a photograph
            // arrives at three times the resolution it had — which is the difference between
            // reading "Serving size 1 CAN (90g)" and reading only the 110 printed twice its size.
            work.ensureActive()
            val panel = pages.firstNotNullOfOrNull { it.panelBounds(turned.width, turned.height) }
            val closer = panel?.let(turned::cropped)?.stretched()
            val closerRegions = closer?.let(ocr::regions).orEmpty()
            if (closer != null && closerRegions.isNotEmpty()) {
                onStep(LabelStep.Closer)
                yield(
                    OcrScript.entries.map { script ->
                        work.ensureActive()
                        readingsOf(ocr.read(closer, closerRegions, script))
                    },
                )
            }
        }
    }

    /**
     * How much of the table this attempt actually read. A parse worth offering ([NutritionLabel.found])
     * outranks any count of stray fields: a clean "110 kcal" must not lose to a pass that scraped
     * two macros off a mangled read and still cannot be offered.
     */
    private val NutritionLabel.rank: Int
        get() = (if (found) FOUND_RANK else 0) +
            listOfNotNull(kcal, proteinG, fatG, carbsG, sugarG, fiberG, sodiumMg).size

    /** Energy plus two macros: a table read well enough that later rungs cannot beat one edit. */
    private val NutritionLabel.rich: Boolean
        get() = kcal != null && listOfNotNull(proteinG, fatG, carbsG).size >= 2

    /**
     * The sessions outlive a scan by a minute: a retake or the next page reuses them rather than
     * initializing again per shot, and the ~39 MB of models leave native memory once the scanning is
     * over rather than with the process.
     */
    private val engines = LingeringModel<OnnxOcr>()

    /** [block] over the OCR sessions, which stay open until every scan using them has finished. */
    internal fun <R> withEngine(context: Context, block: (OnnxOcr) -> R): R =
        engines.borrow({ build(context.applicationContext) }, block)

    private fun build(context: Context): OnnxOcr {
        val environment = OrtEnvironment.getEnvironment()
        // Every session is native memory no collector reclaims: one opened before a later model
        // failed to load is closed here, because nothing else will ever hold it to close it.
        val opened = mutableListOf<OrtSession>()

        // Read rather than mapped as FoodRecognizer's models are: stored compressed, the three take
        // about 33 MB of the APK rather than 39, and a session keeps its own native copy of the
        // weights however the bytes reach it, so a mapping would spare only this passing array.
        fun open(model: String): OrtSession =
            environment.createSession(context.assets.open("$OCR_ASSETS/$model").use { it.readBytes() })
                .also { opened += it }
        return runCatching {
            val detector = open(DET_MODEL)
            val recognizers = OcrScript.entries.associateWith { script ->
                open(script.model) to charsetOf(context, script)
            }
            OnnxOcr(detector, recognizers)
        }.onFailure { opened.forEach(OrtSession::close) }.getOrThrow()
    }

    /** CTC classes: index 0 is the blank, the dictionary follows, and a space closes the list. */
    private fun charsetOf(context: Context, script: OcrScript): List<String> = buildList {
        add("")
        context.assets.open("$OCR_ASSETS/${script.dictionary}").bufferedReader().forEachLine { add(it) }
        add(" ")
    }

    internal fun Bitmap.toRaster(): Raster {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

    /**
     * Aspect ratio kept: stretched glyphs stop being glyphs. 2048 on the long side keeps a
     * frame-filling table legible while a 12 MP JPEG stays out of the heap; the detector scales
     * itself down to its own 960 contract, but recognition crops from this full decode.
     * [ImageDecoder] applies the camera's EXIF rotation on the way.
     */
    internal fun decode(context: Context, photo: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > OCR_SIDE) {
                val scale = OCR_SIDE.toDouble() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private const val OCR_ASSETS = "ocr"
    private const val DET_MODEL = "PP-OCRv6_small_det.onnx"
    private const val OCR_SIDE = 2048

    /** An offerable parse beats any field count a non-offerable one can muster. */
    private const val FOUND_RANK = 100

    /** 90 first among the turns: sideways print with the phone held upright is the common miss. */
    private val TURNS = intArrayOf(0, 90, 270, 180)
}
