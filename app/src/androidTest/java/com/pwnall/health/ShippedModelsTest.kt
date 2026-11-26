package com.pwnall.health

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.time.LocalDate

/** Uses the APK's reduced Android runtime, which the desktop tests cannot validate. */
@RunWith(AndroidJUnit4::class)
class ShippedModelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun beverageAndCigaretteDecisionsKeepSymptomsButDoNotInventAMedicine() = runBlocking {
        ChatModelReader(
            AssetChatModelFiles(context.assets),
            mode = ChatModelMode.ON,
            allowSmoke = allowExperimentalChatModel(),
            threads = 2,
        ).use { reader ->
            val day = LocalDate.of(2026, 10, 9)
            listOf("выпил целую колу", "выпил целую банку колы", "покурил сигаретку").forEach { text ->
                val read = reader.decide(
                    text,
                    ChatReaderContext(),
                    day,
                    past = false,
                    routes = true,
                    limitMillis = 60_000,
                )
                assertTrue("$text: $read", read != null && read.safety.isEmpty() && read.topics.isEmpty())
                assertFalse(text, overdoseIn(text))
            }
            mapOf(
                "выпил целую пачку таблеток" to SymptomStep.MED_MAX_WORDS,
                "покурил сигаретку и задыхаюсь" to SymptomStep.BREATH_NOW,
                "выпил колу и не хочу жить" to SymptomStep.CRISIS_NOW,
            ).forEach { (text, step) ->
                val read = reader.decide(
                    text,
                    ChatReaderContext(),
                    day,
                    past = false,
                    routes = true,
                    limitMillis = 60_000,
                )
                assertTrue("$text: $read", step in read?.safety.orEmpty())
            }
        }
    }

    @Test
    fun onePhotoFileAnswersDishesAndProductsOnAndroid() {
        val models = context.assets.list("").orEmpty().filter { it.endsWith(".onnx") || it.endsWith(".tflite") }
        assertEquals(listOf(PhotoModel.ASSET), models)
        OrtSession.SessionOptions().use { options ->
            val bytes = context.mapModelAsset(PhotoModel.ASSET)
            OrtEnvironment.getEnvironment().createSession(bytes, options).use { session ->
                val model = PhotoModel(session)
                val rgb = ByteBuffer.allocateDirect(model.side * model.side * 3).apply {
                    repeat(capacity()) { put(128.toByte()) }
                    rewind()
                }
                val scores = model.scores(rgb)
                assertEquals(FOOD_LABELS.size, scores.dishes.size)
                assertEquals(1f, scores.dishes.sum(), 1e-3f)
                assertEquals(IMAGENET_LABELS.size, scores.products.size)
                assertTrue(scores.products.all { it.isFinite() && it in 0f..1f })
            }
        }
    }

    @Test
    fun theTrainedChatPackageLoadsMappedAndReadsOnAndroid() {
        val files = AssetChatModelFiles(context.assets)
        val load = OnnxChatModelEngine.load(files, threads = 2, allowSmoke = allowExperimentalChatModel())
        assertTrue("$load", load is ChatModelLoad.Loaded)
        val engine = (load as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        try {
            assertTrue(engine.mapped)
            assertFalse(engine.manifest.smoke)
            assertTrue(engine.manifest.languages.isEmpty())
            assertEquals(engine.schema.head("lang").labels.filter { it != "OTHER" }.toSet(), engine.languages)
            val texts = listOf("болит правое колено, 6/10", "I took paracetamol at 8", "我今天头疼")
            val decisions = texts.map { text ->
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                assertTrue("$text: ${reading.lang}", reading.examined)
                assertTrue(reading.topics.all { it.p.isFinite() && it.p in 0.0..1.0 })
                // These are Android ICU expressions; the same rules run under java.util.regex on the JVM.
                crisisIn(text)
                emergencyIn(text)
                reading.decided(LocalDate.of(2026, 10, 8), past = false)
            }
            assertEquals(listOf(Symptom.PAIN), decisions[0].topics)
            assertEquals(listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)), decisions[0].told.places)
            assertEquals(6, decisions[0].told.nrs)
            assertEquals(listOf(Symptom.DOSE), decisions[1].topics)
            assertEquals(listOf(ReadMedicine("paracetamol", listOf(ReadRole.TAKEN))), decisions[1].told.medicines)
            assertEquals(listOf(480, 1200), decisions[1].told.clocks)
            assertEquals(listOf(Symptom.PAIN), decisions[2].topics)
            assertEquals("HEAD", decisions[2].told.places.single().family)
        } finally {
            engine.close()
        }
    }
}
