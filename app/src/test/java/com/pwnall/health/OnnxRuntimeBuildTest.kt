package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The ONNX Runtime the app ships is the one tools/onnxruntime-android builds, and stays that way.
 *
 * Microsoft's own AAR has carried a telemetry client since 1.29 — native code that posts to
 * mobile.events.data.microsoft.com, a provider that starts it, INTERNET to let it — and a manifest
 * rule can only switch the start off. This build has none of it compiled in. A dependency bump, or
 * a hand-copied AAR from Maven, would bring it all back without any other test noticing:
 * [NoNetworkPermissionTest] reads the merged manifest, not the native libraries.
 */
class OnnxRuntimeBuildTest {
    private val aar: File = sequenceOf("libs", "app/libs").map(::File).first(File::isDirectory)
        .listFiles { file -> file.name.startsWith("onnxruntime-android-") && file.name.endsWith(".aar") }
        .orEmpty().single()

    @Test
    fun bothShippedAbisAreBuilt() {
        ZipFile(aar).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("jni/") && it.endsWith("/libonnxruntime.so") }
                .map { it.split('/')[1] }.toSet()
            assertEquals(setOf("arm64-v8a", "armeabi-v7a"), abis)
        }
    }

    @Test
    fun noTelemetryClientIsCompiledIn() {
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                val text = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                TELEMETRY_MARKERS.forEach { marker -> assertFalse("${entry.name} contains $marker", marker in text) }
            }
            val classes = ZipInputStream(zip.getInputStream(zip.getEntry("classes.jar"))).use { jar ->
                generateSequence { jar.nextEntry }.map { it.name }.toList()
            }
            assertTrue(classes.toString(), classes.any { it == "ai/onnxruntime/OrtEnvironment.class" })
            assertFalse(
                classes.toString(),
                classes.any {
                    "Telemetry" in it ||
                        it.startsWith("ai/onnxruntime/telemetry/")
                },
            )
        }
    }

    @Test
    fun itsManifestAsksForNothing() {
        ZipFile(aar).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).bufferedReader().readText()
            assertFalse(manifest, "uses-permission" in manifest)
            assertFalse(manifest, "<provider" in manifest)
        }
    }

    /**
     * The AAR is the file tools/onnxruntime-android/README.md names by its SHA-256. One of the same
     * name from anywhere else — Maven's, or a rebuild nobody wrote down — is another file here, even
     * where it passes every check above.
     */
    @Test
    fun itIsTheFileTheReadmeNames() {
        val readme = sequenceOf("../tools", "tools").map(::File).first(File::isDirectory)
            .resolve("onnxruntime-android/README.md").readText()
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue("README.md does not name ${aar.name} as $sha256", sha256 in readme)
    }

    /**
     * One release of ONNX Runtime on every side. The JVM tests load the desktop runtime from Maven, and
     * what they show holds for the phone only while that is the release the AAR was built from;
     * build.sh and the README beside it are what the next AAR is made by. Raised alone, any one of
     * the four passes every other gate.
     */
    @Test
    fun everySideNamesTheReleaseTheAarIs() {
        val version = aar.name.removePrefix("onnxruntime-android-").removeSuffix("-pwnall.aar")
        assertTrue(aar.name, Regex("""\d+\.\d+\.\d+""").matches(version))
        // Beside the folder the AAR is in, wherever the tests were started from.
        val script = aar.resolveSibling("../build.gradle.kts").readText()
        assertTrue("the AAR the app is built with", "files(\"libs/${aar.name}\")" in script)
        assertTrue("the JVM tests' desktop runtime", "\"com.microsoft.onnxruntime:onnxruntime:$version\"" in script)
        val tools = aar.resolveSibling("../../tools/onnxruntime-android")
        assertTrue("build.sh", "ORT_TAG=\"\${ORT_TAG:-v$version}\"" in tools.resolve("build.sh").readText())
        val readme = tools.resolve("README.md").readText()
        assertTrue("README.md", "onnxruntime==$version" in readme && "from the tag `v$version`" in readme)
    }

    /** Google Play refuses native code whose loadable segments are not aligned to 16 KB. */
    @Test
    fun itsLibrariesAreSixteenKilobyteAligned() {
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                val alignments = loadAlignments(zip.getInputStream(entry).readBytes())
                assertTrue(entry.name, alignments.isNotEmpty() && alignments.all { it >= SIXTEEN_KB })
            }
        }
    }

    /** p_align of every PT_LOAD program header, from the ELF header's own table. */
    private fun loadAlignments(elf: ByteArray): List<Long> {
        val buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val is64 = elf[ELF_CLASS].toInt() == ELF_CLASS_64
        val tableAt = if (is64) buffer.getLong(PH_OFFSET_64) else buffer.getInt(PH_OFFSET_32).toLong()
        val entrySize = buffer.getShort(if (is64) PH_ENTRY_SIZE_64 else PH_ENTRY_SIZE_32).toInt()
        val count = buffer.getShort(if (is64) PH_COUNT_64 else PH_COUNT_32).toInt()
        return (0 until count).mapNotNull { index ->
            val at = (tableAt + index * entrySize).toInt()
            if (buffer.getInt(at) != PT_LOAD) {
                null
            } else if (is64) {
                buffer.getLong(at + P_ALIGN_64)
            } else {
                buffer.getInt(at + P_ALIGN_32).toLong()
            }
        }
    }

    private companion object {
        val TELEMETRY_MARKERS = listOf("mobile.events.data.microsoft.com", "OneCollector")
        const val SIXTEEN_KB = 16_384L
        const val ELF_CLASS = 4
        const val ELF_CLASS_64 = 2
        const val PH_OFFSET_64 = 0x20
        const val PH_OFFSET_32 = 0x1C
        const val PH_ENTRY_SIZE_64 = 0x36
        const val PH_ENTRY_SIZE_32 = 0x2A
        const val PH_COUNT_64 = 0x38
        const val PH_COUNT_32 = 0x2C
        const val PT_LOAD = 1
        const val P_ALIGN_64 = 0x30
        const val P_ALIGN_32 = 0x1C
    }
}
