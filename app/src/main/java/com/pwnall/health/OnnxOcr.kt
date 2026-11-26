package com.pwnall.health

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * The PP-OCR pipeline, run through ONNX Runtime: one text detector and two script-specific
 * recognizers with their CTC dictionaries — see [OcrScript] for which and why.
 *
 * Deliberately free of android.graphics: pixels arrive as a plain [Raster], so the exact code the
 * phone runs is what the JVM tests run against real photographs — the desktop ONNX Runtime speaks
 * the same API. Every constant here (960-long detection side, 48-pixel recognition height, the DB
 * thresholds) is the model family's own published preprocessing contract.
 *
 * Limitation: the geometry between detector and recognizer lives in [OcrGeometry], and it is an
 * oriented rectangle rather than PaddleOCR's arbitrary unclipped polygon — a detected blob is a
 * line of text, and a rectangle is the shape of one.
 */
internal class OnnxOcr(
    private val detector: OrtSession,
    private val recognizers: Map<OcrScript, Pair<OrtSession, List<String>>>,
) : AutoCloseable {
    /** Every text region on the page, with its recognized text, in reading order (top, left). */
    fun page(raster: Raster, script: OcrScript): List<TextBox> = read(raster, regions(raster), script)

    /**
     * The detector's text regions, found once per frame and read in any script after. [longSide] is
     * the detection resolution: the packet's 960, or more for a dense page, never more than the frame.
     */
    fun regions(raster: Raster, longSide: Double = DET_LONG_SIDE): List<Region> {
        val scale = minOf(1.0, longSide / maxOf(raster.width, raster.height).toDouble())
        val nw = (Math.round(raster.width * scale / DET_STRIDE) * DET_STRIDE).toInt().coerceAtLeast(DET_STRIDE)
        val nh = (Math.round(raster.height * scale / DET_STRIDE) * DET_STRIDE).toInt().coerceAtLeast(DET_STRIDE)
        val probabilities = detect(raster.resized(nw, nh))
        val sx = raster.width.toDouble() / nw
        val sy = raster.height.toDouble() / nh
        return componentsOf(probabilities, nw, nh).mapNotNull { component -> component.region(sx, sy) }
    }

    /** The given regions read in one script, in reading order (top, left). */
    fun read(raster: Raster, regions: List<Region>, script: OcrScript): List<TextBox> {
        val (session, charset) = recognizers.getValue(script)
        return regions
            .mapNotNull { region ->
                raster.sampled(region, REC_HEIGHT, REC_MIN_WIDTH, REC_MAX_WIDTH)
                    ?.let { crop -> TextBox(region.bounds, recognize(crop, session, charset)) }
            }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ (it.box.y0 + it.box.y1) / 2 }, { it.box.x0 }))
    }

    private fun detect(resized: Raster): FloatArray {
        val input = FloatBuffer.allocate(3 * resized.height * resized.width)
        for (channel in BGR_CHANNELS) {
            val mean = DET_MEAN[channel]
            val std = DET_STD[channel]
            for (pixel in resized.argb) {
                val value = (pixel shr (channel * BITS_PER_CHANNEL) and CHANNEL_MASK) / MAX_CHANNEL
                input.put(((value - mean) / std).toFloat())
            }
        }
        input.rewind()
        val shape = longArrayOf(1, 3, resized.height.toLong(), resized.width.toLong())
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, shape).use { tensor ->
            detector.run(mapOf(detector.inputNames.first() to tensor)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.let { buffer ->
                    FloatArray(buffer.remaining()).also(buffer::get)
                }
            }
        }
    }

    override fun close() {
        detector.close()
        recognizers.values.forEach { (session, _) -> session.close() }
    }

    /** One sampled line, already 48 pixels tall, through the recognizer and its CTC decode. */
    private fun recognize(crop: Raster, session: OrtSession, charset: List<String>): String {
        val width = crop.width
        val input = FloatBuffer.allocate(3 * crop.height * width)
        for (channel in BGR_CHANNELS) {
            for (pixel in crop.argb) {
                val value = (pixel shr (channel * BITS_PER_CHANNEL) and CHANNEL_MASK) / MAX_CHANNEL
                input.put(((value - REC_CENTER) / REC_CENTER).toFloat())
            }
        }
        input.rewind()
        val shape = longArrayOf(1, 3, crop.height.toLong(), width.toLong())
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val logits = (result[0] as OnnxTensor)
                val steps = logits.info.shape[1].toInt()
                val classes = logits.info.shape[2].toInt()
                val buffer = logits.floatBuffer
                return ctcDecode(buffer, steps, classes, charset)
            }
        }
    }

    private fun ctcDecode(buffer: FloatBuffer, steps: Int, classes: Int, charset: List<String>): String {
        val text = StringBuilder()
        var previous = 0
        for (step in 0 until steps) {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            val base = step * classes
            for (index in 0 until classes) {
                val score = buffer.get(base + index)
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex != 0 && bestIndex != previous && bestIndex < charset.size) {
                text.append(charset[bestIndex])
            }
            previous = bestIndex
        }
        return text.toString()
    }

    private companion object {
        /**
         * The DB detector's published input contract: long side to 960, strides of 32, and the
         * normalization means applied to the channels in BGR array order — exactly as PaddleOCR's
         * own NormalizeImage does it, blue first.
         */
        const val DET_LONG_SIDE = 960.0
        const val DET_STRIDE = 32
        val DET_MEAN = doubleArrayOf(0.485, 0.456, 0.406)
        val DET_STD = doubleArrayOf(0.229, 0.224, 0.225)

        /** The recognizer's contract: 48-pixel rows, width proportional, values in [-1, 1]. */
        const val REC_HEIGHT = 48
        const val REC_MIN_WIDTH = 16
        const val REC_MAX_WIDTH = 1600
        const val REC_CENTER = 0.5

        /** ARGB channel plumbing: channel index n is bits n*8..n*8+7, images feed the net as BGR. */
        val BGR_CHANNELS = intArrayOf(0, 1, 2)
        const val BITS_PER_CHANNEL = 8
        const val CHANNEL_MASK = 0xFF
        const val MAX_CHANNEL = 255.0
    }
}

/**
 * The recognizers shipped in assets/ocr, keyed by what the packet might print.
 *
 * Two: PP-OCRv6 small reads Latin print with its diacritics, Chinese and Japanese in one model, so
 * every turn of the ladder takes two reads at most. No v6 recognizer has a single Cyrillic letter in
 * its dictionary, so the East Slavic one is a PP-OCRv5 model.
 */
internal enum class OcrScript(val model: String, val dictionary: String) {
    LATIN_CJK("PP-OCRv6_small_rec.onnx", "PP-OCRv6_small_rec.dict.txt"),
    EAST_SLAVIC("eslav_PP-OCRv5_mobile_rec.onnx", "eslav_PP-OCRv5_mobile_rec.dict.txt"),
}

internal data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

private const val MIN_CROP_SIDE = 32
private const val LEVELS = 256
private const val MAX_LEVEL = 255
private const val ALPHA = 0xFF shl 24
private const val LOW_TAIL = 0.02
private const val HIGH_TAIL = 0.98
private const val MIN_RANGE = 24

internal data class TextBox(val box: Box, val text: String)

/**
 * Plain ARGB pixels — the one image type both Android and the JVM tests can produce, so the
 * whole pipeline stays testable off-device.
 */
internal class Raster(val width: Int, val height: Int, val argb: IntArray) {
    /**
     * Bilinear resize, in the same arithmetic as PIL's.
     *
     * The far neighbour is clamped as well as the near one. A crop dialog drag that slips onto the
     * last column hands this a strip one pixel across, and there the near clamp folds back to zero
     * and the far sample reads off the end of the array. On anything two pixels a side or larger
     * the clamp never bites and the arithmetic is unchanged; on a one-pixel side the duplicated
     * sample carries no weight, because the fraction there is zero.
     */
    fun resized(newWidth: Int, newHeight: Int): Raster {
        if (newWidth == width && newHeight == height) return this
        val out = IntArray(newWidth * newHeight)
        val xRatio = width.toDouble() / newWidth
        val yRatio = height.toDouble() / newHeight
        for (y in 0 until newHeight) {
            val sy = ((y + 0.5) * yRatio - 0.5).coerceIn(0.0, height - 1.0)
            val y0 = sy.toInt().coerceAtMost(height - 2).coerceAtLeast(0)
            val yNext = (y0 + 1).coerceAtMost(height - 1)
            val fy = sy - y0
            for (x in 0 until newWidth) {
                val sx = ((x + 0.5) * xRatio - 0.5).coerceIn(0.0, width - 1.0)
                val x0 = sx.toInt().coerceAtMost(width - 2).coerceAtLeast(0)
                val xNext = (x0 + 1).coerceAtMost(width - 1)
                val fx = sx - x0
                out[y * newWidth + x] = blend(
                    argb[y0 * width + x0],
                    argb[y0 * width + xNext],
                    argb[yNext * width + x0],
                    argb[yNext * width + xNext],
                    fx,
                    fy,
                )
            }
        }
        return Raster(newWidth, newHeight, out)
    }

    /** The rectangle as its own raster, clamped to what exists. */
    fun cropped(box: Box): Raster? {
        val x0 = box.x0.coerceIn(0, width - 1)
        val y0 = box.y0.coerceIn(0, height - 1)
        val x1 = box.x1.coerceIn(x0 + 1, width)
        val y1 = box.y1.coerceIn(y0 + 1, height)
        if (x1 - x0 < MIN_CROP_SIDE || y1 - y0 < MIN_CROP_SIDE) return null
        val out = IntArray((x1 - x0) * (y1 - y0))
        for (y in y0 until y1) {
            System.arraycopy(argb, y * width + x0, out, (y - y0) * (x1 - x0), x1 - x0)
        }
        return Raster(x1 - x0, y1 - y0, out)
    }

    /**
     * The same pixels with the ends of their range pulled apart.
     *
     * A photograph of a packet is rarely a photograph of paper: a chilled bottle under shop
     * lighting arrives grey on grey, and the detector's threshold has nothing to bite on. Pulling
     * the second and ninety-eighth percentiles out to black and white is the cheapest thing that
     * helps, and it is honest about what it cannot do — a blown highlight is gone, and no stretch
     * brings it back.
     */
    fun stretched(): Raster {
        val luma = IntArray(LEVELS)
        for (pixel in argb) luma[grey(pixel)]++
        val low = percentile(luma, argb.size * LOW_TAIL)
        val high = percentile(luma, argb.size * HIGH_TAIL)
        if (high - low < MIN_RANGE) return this
        val map = IntArray(LEVELS) { level ->
            ((level - low) * (LEVELS - 1) / (high - low)).coerceIn(0, LEVELS - 1)
        }
        val out = IntArray(argb.size) { index ->
            val pixel = argb[index]
            (pixel and ALPHA) or
                (map[(pixel shr 16) and MAX_LEVEL] shl 16) or
                (map[(pixel shr 8) and MAX_LEVEL] shl 8) or
                map[pixel and MAX_LEVEL]
        }
        return Raster(width, height, out)
    }

    private fun grey(pixel: Int): Int =
        (((pixel shr 16) and MAX_LEVEL) + ((pixel shr 8) and MAX_LEVEL) + (pixel and MAX_LEVEL)) / 3

    private fun percentile(histogram: IntArray, target: Double): Int {
        var seen = 0
        histogram.forEachIndexed { level, count ->
            seen += count
            if (seen >= target) return level
        }
        return MAX_LEVEL
    }

    /**
     * The region's pixels sampled along its own axes into a straight crop [outHeight] rows tall —
     * a tilted line of text arrives at the recognizer level, without the background an upright
     * box around it would have swept in.
     */
    fun sampled(region: Region, outHeight: Int, minWidth: Int, maxWidth: Int): Raster? {
        if (width < 2 || height < 2 || region.thickness < 1.0) return null
        val outWidth = Math.round(region.length * outHeight / region.thickness).toInt()
            .coerceIn(minWidth, maxWidth)
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val across = 2.0 * (y + 0.5) / outHeight - 1
            for (x in 0 until outWidth) {
                val along = 2.0 * (x + 0.5) / outWidth - 1
                out[y * outWidth + x] = at(
                    region.cx + region.ux * along + region.vx * across,
                    region.cy + region.uy * along + region.vy * across,
                )
            }
        }
        return Raster(outWidth, outHeight, out)
    }

    /** One bilinear sample at a fractional position, clamped to the edge pixels. */
    private fun at(px: Double, py: Double): Int {
        val sx = px.coerceIn(0.0, width - 1.0)
        val sy = py.coerceIn(0.0, height - 1.0)
        val x0 = sx.toInt().coerceIn(0, width - 2)
        val y0 = sy.toInt().coerceIn(0, height - 2)
        return blend(
            argb[y0 * width + x0],
            argb[y0 * width + x0 + 1],
            argb[(y0 + 1) * width + x0],
            argb[(y0 + 1) * width + x0 + 1],
            sx - x0,
            sy - y0,
        )
    }

    /** Quarter turns for the reading ladder, clockwise; 0 returns this raster untouched. */
    fun turned(degrees: Int): Raster = when (degrees) {
        QUARTER_TURN -> transposed { x, y -> argb[(height - 1 - x) * width + y] }
        HALF_TURN -> Raster(width, height, IntArray(width * height) { argb[argb.size - 1 - it] })
        THREE_QUARTER_TURN -> transposed { x, y -> argb[x * width + (width - 1 - y)] }
        else -> this
    }

    private inline fun transposed(source: (Int, Int) -> Int): Raster {
        val out = IntArray(width * height)
        for (y in 0 until width) {
            for (x in 0 until height) {
                out[y * height + x] = source(x, y)
            }
        }
        return Raster(height, width, out)
    }

    private fun blend(tl: Int, tr: Int, bl: Int, br: Int, fx: Double, fy: Double): Int {
        var result = 0
        for (shift in intArrayOf(0, 8, 16, 24)) {
            val top = (tl shr shift and 0xFF) * (1 - fx) + (tr shr shift and 0xFF) * fx
            val bottom = (bl shr shift and 0xFF) * (1 - fx) + (br shr shift and 0xFF) * fx
            val value = (top * (1 - fy) + bottom * fy).let { Math.round(it) }.toInt().coerceIn(0, 0xFF)
            result = result or (value shl shift)
        }
        return result
    }

    private companion object {
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val THREE_QUARTER_TURN = 270
    }
}
