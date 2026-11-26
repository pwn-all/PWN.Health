package com.pwnall.health

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The photo model itself, on the JVM: ONNX Runtime ships a desktop build (already a test dependency for the label
 * reader), so the asset the phone loads is opened and run here.
 *
 * What the photographs do is pinned in [FoodRecognitionTest] through recognition_samples.csv; this test keeps the
 * file and the label lists in step, and both answers the sheet reads in the shape [photoGuesses] expects.
 */
class PhotoModelTest {
    @Test
    fun theModelTakesTheAppsBytesAndAnswersBothQuestions() {
        val model = model()
        assertEquals(224, model.side)

        val scores = model.scores(grey(model.side))
        // One softmax over the background and the 2 023 dishes.
        assertEquals(FOOD_LABELS.size, scores.dishes.size)
        assertEquals(1f, scores.dishes.sum(), 1e-3f)
        // Independent per food, not a softmax: a plate may hold several.
        assertEquals(IMAGENET_LABELS.size, scores.products.size)
        assertTrue(scores.products.all { it in 0f..1f })
    }

    @Test
    fun theSameBytesGiveTheSameAnswer() {
        val model = model()
        val first = model.scores(grey(model.side))
        val second = model.scores(grey(model.side))
        assertTrue(first.dishes.contentEquals(second.dishes))
        assertTrue(first.products.contentEquals(second.products))
    }

    @Test
    fun everyMappedLabelIsOneTheModelEmits() {
        val mapped = catalogResource("/product_labels.csv").lineSequence().drop(1).filter(String::isNotBlank)
            .map { it.substringBefore(';') }.toList()
        assertTrue(mapped.size >= 15)
        assertTrue(mapped.all { it in IMAGENET_LABELS })
    }

    private fun grey(side: Int): ByteBuffer =
        ByteBuffer.allocateDirect(side * side * 3).apply {
            repeat(side * side * 3) { put(GREY) }
            rewind()
        }

    private companion object {
        const val GREY: Byte = 128.toByte()

        /** Unit tests run with the module directory as their working directory. */
        val ASSET = sequenceOf("src/main/assets", "app/src/main/assets").map(::File).first(File::isDirectory)

        val shared: PhotoModel by lazy {
            PhotoModel(OrtEnvironment.getEnvironment().createSession(File(ASSET, PhotoModel.ASSET).readBytes()))
        }

        fun model(): PhotoModel = shared
    }
}
