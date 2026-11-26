package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat reader's files as the APK carries them: the tokenizer and
 * schema byte for byte the ones the reader's tooling made and the model was trained with, in the main
 * source set so both flavours ship the same, and — once a model ships — a manifest whose hashes are
 * the files'. Experimental release weights must match the explicitly selected model hash.
 */
class ChatModelAssetsTest {
    private val assets = ChatModelTestFiles.assets
    private val ml = ChatModelTestFiles.ml

    /**
     * Routing can be enabled explicitly without claiming an exam passed; a package that names no routing
     * languages routes where it passed.
     */
    @Test
    fun routingLanguagesAreSeparateFromExamResultsAndDefaultToThem() {
        val json = JSONObject(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        json.remove("languages")
        assertTrue(ChatModelManifest.parse(json.toString()).routingLanguages.isEmpty())
        json.put("languages", JSONArray(listOf("ru")))
        assertEquals(setOf("ru"), ChatModelManifest.parse(json.toString()).routingLanguages)
        json.put("routing_languages", JSONArray(listOf("ru", "en")))
        val enabled = ChatModelManifest.parse(json.toString())
        assertEquals(setOf("ru"), enabled.languages)
        assertEquals(setOf("ru", "en"), enabled.routingLanguages)
        json.put("routing_languages", JSONArray())
        assertTrue(ChatModelManifest.parse(json.toString()).routingLanguages.isEmpty())
    }

    /** Journal forms have a gate of their own; a package without it offers them where it routes. */
    @Test
    fun journalLanguagesAreTheirOwnAndDefaultToTheRoutingLanguages() {
        val json = JSONObject(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        assertTrue(ChatModelManifest.parse(json.toString()).journalLanguages.isEmpty())
        json.put("languages", JSONArray(listOf("ru")))
        assertEquals(setOf("ru"), ChatModelManifest.parse(json.toString()).journalLanguages)
        json.put("routing_languages", JSONArray(listOf("ru", "en", "ko")))
        assertEquals(setOf("ru", "en", "ko"), ChatModelManifest.parse(json.toString()).journalLanguages)
        json.put("journal_languages", JSONArray(listOf("en", "ar", "", 7)))
        val gated = ChatModelManifest.parse(json.toString())
        assertEquals(setOf("en", "ar"), gated.journalLanguages)
        assertEquals(setOf("ru", "en", "ko"), gated.routingLanguages)
        assertEquals(setOf("ru"), gated.languages)
        json.put("journal_languages", JSONArray())
        assertTrue(ChatModelManifest.parse(json.toString()).journalLanguages.isEmpty())
    }

    @Test
    fun smokeWeightsLoadOnlyInAModelTestBuild() {
        val modelTest = BuildConfig.CHAT_MODEL_EXPERIMENTAL &&
            (BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".modeltestrelease"))
        assertEquals(modelTest, allowExperimentalChatModel())
    }

    @Test
    fun theTokenizerIsTheOneTheModelWasTrainedWith() {
        val shipped = File(assets, CHAT_MODEL_TOKENIZER).readBytes()
        assertTrue(shipped.contentEquals(File(ml, "tokenizer/reader-tokenizer.json").readBytes()))
    }

    @Test
    fun theSchemaIsTheOneTheModelWasTrainedWithAndFitsTheApp() {
        val shipped = File(assets, CHAT_MODEL_SCHEMA)
        // A journal reader ships one of the tree's journal schemas: on the lifestyle schema, or on v1
        val references = if (ChatModelSchema.parse(shipped.readText()).journal != null) {
            listOf("schema-journal.json", "schema-journal-v1.json")
        } else {
            listOf("schema.json")
        }
        assertTrue(references.any { shipped.readBytes().contentEquals(File(ml, it).readBytes()) })
        assertNull(ChatModelSchema.parse(shipped.readText()).misfit())
    }

    /** The safety steps the reader may add are the app's own questions, one per flag. */
    @Test
    fun everyFlagAddsOneOfTheAppsSafetySteps() {
        val schema = ChatModelSchema.parse(File(assets, CHAT_MODEL_SCHEMA).readText())
        assertEquals(ReaderSafety.entries.associate { it.name to it.step.name }, schema.safetySteps)
        assertEquals(ReaderSafety.entries.size, ReaderSafety.entries.map { it.step }.toSet().size)
    }

    /**
     * Both distributions read the main source set's assets; no other source set — a flavour, a build
     * type, a variant — may carry a reader of its own, which no test here would read.
     */
    @Test
    fun bothFlavoursShipTheSameReader() {
        val src = File(assets, "../../..").canonicalFile
        val others = src.listFiles().orEmpty().filter { it.isDirectory && it.name != "main" }
        assertTrue(others.any { it.name == "test" })
        for (set in others) {
            assertFalse("${set.name} has its own chat assets", File(set, "assets/$CHAT_MODEL_ASSETS").exists())
        }
    }

    /**
     * The tiny fixture never ships, whatever its manifest says of itself: not
     * under its own model's name, not by its own bytes — its random weights would flag almost every
     * message — in any source set's chat assets.
     */
    @Test
    fun theFixtureIsNoModelThatShips() {
        val golden = JSONObject(File(ChatModelTestFiles.fixture, "fixture-golden.json").readText())
        val fixtureSha = golden.getString("onnx_sha256")
        val fixture = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        val src = File(assets, "../../..").canonicalFile
        val sets = src.listFiles().orEmpty().filter { it.isDirectory && it.name != "test" }
        for (set in sets) {
            val chat = File(set, "assets/$CHAT_MODEL_ASSETS")
            chat.listFiles().orEmpty().filter { it.extension == "onnx" }.forEach { model ->
                assertTrue("the fixture's bytes are in ${set.name}", sha256(model.readBytes()) != fixtureSha)
            }
            val file = File(chat, CHAT_MODEL_MANIFEST).takeIf(File::isFile) ?: continue
            val manifest = ChatModelManifest.parse(file.readText())
            assertTrue("the fixture's manifest is in ${set.name}", manifest.model != fixture.model)
            assertTrue("the fixture's model is in ${set.name}", manifest.onnxSha256 != fixtureSha)
        }
    }

    /**
     * The build says a model ships exactly when its manifest is in the assets (CHAT_MODEL_SHIPS): with
     * none, the chat page asks for no reader at all.
     */
    @Test
    fun theBuildKnowsWhetherAModelShips() {
        assertEquals(File(assets, CHAT_MODEL_MANIFEST).isFile, BuildConfig.CHAT_MODEL_SHIPS)
    }

    /**
     * When a model ships its manifest has to describe it: the bytes and SHA-256 of the file and its
     * calibration, and the tokenizer and schema beside it, and it is never a smoke run's.
     */
    @Test
    fun aShippedModelIsTheOneItsManifestDescribes() {
        val manifestFile = File(assets, CHAT_MODEL_MANIFEST)
        if (!manifestFile.isFile) {
            // No model shipped: then none of a model's files may be lying there either.
            assertEquals(setOf(CHAT_MODEL_TOKENIZER, CHAT_MODEL_SCHEMA), assets.list().orEmpty().toSet())
            return
        }
        val manifest = ChatModelManifest.parse(manifestFile.readText())
        assertFalse("a shipped reader is a release run's, never a smoke run's", manifest.smoke)
        val model = File(assets, manifest.onnxFile)
        assertEquals(manifest.onnxBytes, model.length())
        assertEquals(manifest.onnxSha256, sha256(model.readBytes()))
        assertEquals(manifest.calibrationSha256, sha256(File(assets, manifest.calibrationFile).readBytes()))
        assertEquals(manifest.tokenizerSha256, sha256(File(assets, CHAT_MODEL_TOKENIZER).readBytes()))
        assertEquals(manifest.schemaSha256, sha256(File(assets, CHAT_MODEL_SCHEMA).readBytes()))
        ChatModelCalibration.parse(File(assets, manifest.calibrationFile).readText())
    }

    /**
     * The loader takes any safety threshold from 0 to 1, yet one near 1 leaves its flag as dead as a NaN
     * would: what ships stays on the grid the calibration searches, 0.05 to 0.35, every flag's
     * threshold and every language's own.
     */
    @Test
    fun theShippedSafetyThresholdsStayOnTheCalibrationGrid() {
        val manifestFile = File(assets, CHAT_MODEL_MANIFEST)
        if (!manifestFile.isFile) return
        val calibration = File(assets, ChatModelManifest.parse(manifestFile.readText()).calibrationFile)
        val thresholds = JSONObject(calibration.readText()).getJSONObject("thresholds")
        val flags = thresholds.getJSONObject("safety")
        val byLanguage = thresholds.getJSONObject("safety_by_lang")
        val all = flags.keyNames().associateWith(flags::getDouble) +
            byLanguage.keyNames().flatMap { flag ->
                val languages = byLanguage.getJSONObject(flag)
                languages.keyNames().map { "$flag in $it" to languages.getJSONObject(it).getDouble("threshold") }
            }

        assertTrue(all.isNotEmpty())
        assertEquals(emptyMap<String, Double>(), all.filterValues { it !in 0.05..0.35 })
    }

    /**
     * The reader maps its model out of the APK (AssetChatModelFiles.map), which only a stored file
     * allows: compressed, the model would be read onto the Java heap and inflated on every opening
     * of the chat. The name it ships under — the manifest's — is
     * one app/build.gradle.kts keeps uncompressed, matched as AGP matches it, by the end of the path.
     */
    @Test
    fun theModelIsStoredUncompressedSoItIsMappedNotRead() {
        val uncompressed = System.getProperty("android.noCompress").orEmpty().lines().filter(String::isNotBlank)
        assertTrue("app/build.gradle.kts hands the tests no noCompress list", uncompressed.isNotEmpty())
        val manifest = File(assets, CHAT_MODEL_MANIFEST).takeIf(File::isFile)?.readText()?.let(ChatModelManifest::parse)
        fun stored(path: String) = uncompressed.any { path.endsWith(it, ignoreCase = true) }
        for (name in listOfNotNull(manifest?.onnxFile)) {
            assertTrue("$name would be compressed", stored("assets/$CHAT_MODEL_ASSETS/$name"))
        }
        // The label reader's models are left alone: the entry names the chat's model, not every .onnx.
        val ocr = File(assets, "../ocr").listFiles { file -> file.extension == "onnx" }.orEmpty()
        assertTrue(ocr.isNotEmpty())
        for (model in ocr) assertFalse("${model.name} would be stored", stored("assets/ocr/${model.name}"))
    }

    /**
     * The switch is a mode the code knows, and on in the build these tests run, as in both flavours:
     * the chat uses the readings of a model that ships with its manifest. A build without that
     * package must still read with the rules alone.
     */
    @Test
    fun theSwitchIsOnAndUsesOnlyAModelThatShips() = runBlocking {
        assertTrue(ChatModelMode.entries.any { it.name == BuildConfig.CHAT_MODEL })
        assertEquals(ChatModelMode.ON, ChatModelMode.build)
        assertTrue(ChatModelMode.build.reads && ChatModelMode.build.routes)
        assertTrue(ChatModelMode.SHADOW.reads && !ChatModelMode.SHADOW.routes)
        assertTrue(ChatModelMode.ON.reads && ChatModelMode.ON.routes)
        if (File(assets, CHAT_MODEL_MANIFEST).isFile) return@runBlocking
        val reader = ChatModelReader(ChatModelTestFiles.files(listOf(assets)), allowSmoke = false, threads = 1)
        reader.prepare()
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.NO_MODEL), reader.state())
        reader.close()
    }
}
