package com.pwnall.health

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The real ONNX pipeline over photographs of real packets, on the JVM: the desktop ONNX
 * Runtime speaks the same API as the Android artifact, so the exact detection, recognition and
 * assembly code the phone runs is what these tests measure. The models load from main assets —
 * no copies — and the photographs are the two shelf shots in the test resources: the Czech UHT
 * milk carton Tesseract reads as "297M/ekgll", and the sideways Japanese drinking-yoghurt carton
 * whose inline row wraps mid-nutrient. Both are read whole — every macro the packet prints — which
 * the oriented crops in [OcrGeometry] make possible.
 */
class OnnxOcrTest {
    @Test
    fun readsTheCzechMilkCartonPhotograph() {
        val label = nutritionLabel(ocr().page(raster("label_czech_milk.jpg"), OcrScript.LATIN_CJK).asLineText())

        assertEquals(64.0, requireNotNull(label.kcal), 0.01)
        assertEquals(3.3, requireNotNull(label.proteinG), 0.01)
        assertEquals(3.5, requireNotNull(label.fatG), 0.01)
        assertEquals(4.8, requireNotNull(label.carbsG), 0.01)
        assertEquals(4.8, requireNotNull(label.sugarG), 0.01)
        // 0.13 g of salt equivalent is 52 mg of sodium.
        assertEquals(52.0, requireNotNull(label.sodiumMg), 0.01)
        assertTrue(label.found)
    }

    /** The sideways carton also proves the quarter-turn arithmetic: garbled pixels read nothing. */
    @Test
    fun readsTheJapaneseCartonThroughTheTurn() {
        val turned = raster("label_japanese_yogurt.jpg").turned(90)
        val label = nutritionLabel(ocr().page(turned, OcrScript.LATIN_CJK).asBoxText())

        assertEquals(123.0, requireNotNull(label.kcal), 0.01)
        assertEquals(5.8, requireNotNull(label.proteinG), 0.01)
        assertEquals(1.1, requireNotNull(label.fatG), 0.01)
        assertEquals(22.7, requireNotNull(label.carbsG), 0.01)
        assertEquals(190.0, requireNotNull(label.perServingG), 0.01)
    }

    /**
     * The reader says what it is doing while it does it: which turn it is looking from, how much
     * text came back, and what parsed out of it. The sheet spends those seconds showing this
     * instead of a bare spinner, so the steps have to be real ones.
     */
    @Test
    fun theLadderSaysWhatItIsDoing() {
        val steps = mutableListOf<LabelStep>()
        val label = LabelReader.climb(
            ocr(),
            raster("label_czech_milk.jpg"),
            EmptyCoroutineContext,
        ) { steps += it }

        assertTrue(steps.toString(), steps.filterIsInstance<LabelStep.Turning>().any { it.degrees == 0 })
        assertTrue(steps.toString(), steps.filterIsInstance<LabelStep.Read>().any { it.fragments > 0 })
        assertTrue(steps.toString(), steps.filterIsInstance<LabelStep.Found>().isNotEmpty())
        assertEquals(64.0, requireNotNull(label.kcal), 0.01)
    }

    /**
     * The desktop runtime these tests load reports to Microsoft unless told not to before it starts,
     * and app/build.gradle.kts is what tells it. A test run without the variable has already sent
     * its first event by the time anything could switch it off, so the wiring is checked here.
     */
    @Test
    fun theDesktopRuntimeIsToldNotToReport() {
        assertEquals("1", System.getenv("ORT_DISABLE_TELEMETRY"))
    }

    private fun raster(resource: String): Raster {
        val image = ImageIO.read(checkNotNull(javaClass.getResourceAsStream("/$resource")) { "missing $resource" })
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return Raster(image.width, image.height, argb)
    }

    private companion object {
        /** Unit tests run with the module directory as their working directory. */
        val ASSETS = sequenceOf("src/main/assets/ocr", "app/src/main/assets/ocr")
            .map(::File).first(File::isDirectory)

        val shared: OnnxOcr by lazy {
            val environment = OrtEnvironment.getEnvironment()
            val detector = environment.createSession(File(ASSETS, "PP-OCRv6_small_det.onnx").readBytes())
            val recognizers = OcrScript.entries.associateWith { script ->
                val session = environment.createSession(File(ASSETS, script.model).readBytes())
                val charset = buildList {
                    add("")
                    File(ASSETS, script.dictionary).bufferedReader().forEachLine { add(it) }
                    add(" ")
                }
                session to charset
            }
            OnnxOcr(detector, recognizers)
        }

        fun ocr(): OnnxOcr = shared
    }
}
