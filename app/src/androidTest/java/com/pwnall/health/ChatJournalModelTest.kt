package com.pwnall.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the shipped package through Android's actual ONNX runtime and journal decoder. */
@RunWith(AndroidJUnit4::class)
class ChatJournalModelTest {
    @Test
    fun selectedJournalModelLoadsAndReportsGroundedPredictions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = AssetChatModelFiles(context.assets)
        val load = OnnxChatModelEngine.load(files, threads = 2, allowSmoke = false)
        assertTrue(load.toString(), load is ChatModelLoad.Loaded)
        val engine = (load as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        val rows = JSONArray()
        try {
            assertFalse(engine.manifest.smoke)
            assertNotNull(engine.schema.journal)
            assertTrue(engine.mapped)
            val texts = listOf(
                "Я выпил 250 мл воды",
                "Я съел гречку 500 г и курицу 100 г",
                "Сегодня сделал 3 подхода приседаний по 12 повторений с весом 40 кг",
                "Мой вес сегодня 75 кг",
                "Я не пил кофе",
                "I drank 250 ml of water",
                "Ich habe 250 ml Wasser getrunken",
                "Wypiłem 250 ml wody",
            )
            for (text in texts) {
                val started = System.nanoTime()
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                assertTrue(reading.heads.values.flatMap { it.values }.all { it.isFinite() && it in 0.0..1.0 })
                assertTrue(reading.journal.all { it.groundedIn(text) })
                rows.put(
                    JSONObject().put("text", text)
                        .put("milliseconds", (System.nanoTime() - started) / 1_000_000)
                        .put("events", JSONArray(encodeJournalEvents(reading.journal))),
                )
            }
            val report = JSONObject().put("model", engine.manifest.model).put("rows", rows)
            File(context.getExternalFilesDir(null), "journal-model-probe.json").writeText(report.toString(2))
            println("JournalModelProbe $report")
        } finally {
            engine.close()
        }
    }
}
