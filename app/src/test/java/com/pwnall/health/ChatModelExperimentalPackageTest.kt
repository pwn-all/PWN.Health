package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs only against a model package given by the chatReaderTestAssets Gradle property, as a modeltest build is. */
class ChatModelExperimentalPackageTest {
    @Test
    fun downloadedWeightsLoadAndReadThroughTheAppsNativeAdapter() = runBlocking {
        val path = System.getProperty("chatReader.testAssets")
        assumeTrue(path != null)
        assertTrue(BuildConfig.CHAT_MODEL_EXPERIMENTAL)
        assertTrue(
            (BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".modeltest")) ||
                (!BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".modeltestrelease")),
        )
        val assets = File(checkNotNull(path))
        val manifest = ChatModelManifest.parse(File(assets, CHAT_MODEL_MANIFEST).readText())
        // The loader holds the model to its byte count alone; here, once a build, to its SHA-256 as
        // ChatModelAssetsTest holds the package in the main assets.
        assertEquals(manifest.onnxSha256, sha256(File(assets, manifest.onnxFile).readBytes()))
        val reader = ChatModelReader(
            ChatModelTestFiles.files(listOf(assets)),
            mode = ChatModelMode.ON,
            allowSmoke = BuildConfig.CHAT_MODEL_EXPERIMENTAL &&
                (BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".modeltestrelease")),
            threads = 2,
        )
        try {
            reader.prepare()
            assertEquals(ChatModelState.Ready(manifest.model, manifest.routingLanguages), reader.state())
            for (text in listOf("сегодня я кушал гречку 500г с вареной курицей 100г", "я пил воду", "болит голова")) {
                val outcome = reader.readPatiently(text)
                assertTrue(outcome.toString(), outcome is ChatReadOutcome.Read)
                val reading = (outcome as ChatReadOutcome.Read).reading
                assertEquals(manifest.model, reading.model)
                assertTrue(reading.heads.values.flatMap { it.values }.all { it.isFinite() && it in 0.0..1.0 })
            }
            assertTrue(reader.failures.toString(), reader.failures.isEmpty())
        } finally {
            reader.close()
        }
    }
}
