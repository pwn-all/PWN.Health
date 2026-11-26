package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive every one of these twelve services hands over.
 *
 * The tests here are about the container rather than about anybody's columns: that a ZIP is opened
 * without being unpacked, that a file which is not a ZIP still reads, that an entry the walk skipped
 * does not take the rest of the archive with it, and that the two shapes which have historically
 * broken CSV readers — a byte-order mark and a gzipped entry — come out as the text underneath.
 */
class HealthArchiveTest {
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzipOf(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    /** The picked file already in memory; the app itself always streams it from where it lives. */
    private fun walkArchive(bytes: ByteArray, name: String, visit: (ArchiveEntry) -> Boolean): Boolean =
        walkArchive({ ByteArrayInputStream(bytes) }, name, visit)

    /** Every entry as text; one too large to hold is left out rather than truncated ([ArchiveEntry.text]). */
    private fun texts(bytes: ByteArray, name: String = "export.zip"): Map<String, String> {
        val found = mutableMapOf<String, String>()
        walkArchive(bytes, name) { entry ->
            entry.text()?.let { found[entry.name] = it }
            true
        }
        return found
    }

    @Test
    fun aPlainFileIsItsOwnSingleEntry() {
        val read = texts("Date,Weight\n2026-01-01,80\n".toByteArray(), "weight.csv")

        assertEquals(mapOf("weight.csv" to "Date,Weight\n2026-01-01,80\n"), read)
    }

    @Test
    fun everyEntryOfAnArchiveIsRead() {
        val read = texts(zipOf("a/weight.csv" to "one".toByteArray(), "b/bp.csv" to "two".toByteArray()))

        assertEquals(mapOf("a/weight.csv" to "one", "b/bp.csv" to "two"), read)
    }

    /** Withings buries `weight.csv` in a folder; the name a reader matches on is the last part. */
    @Test
    fun anEntryIsKnownByItsOwnNameAndNotItsFolder() {
        val names = mutableListOf<String>()
        walkArchive(zipOf("Health Export/DI_CONNECT/Weight.CSV" to "x".toByteArray()), "e.zip") {
            names += it.fileName
            true
        }

        assertEquals(listOf("weight.csv"), names)
    }

    /**
     * The reason the walk takes a callback rather than returning a list: a Garmin archive holds
     * several hundred entries and a reader that has found its file should not decompress the rest.
     */
    @Test
    fun aReaderThatHasFoundItsFileStopsTheWalk() {
        val seen = mutableListOf<String>()
        walkArchive(
            zipOf("first.csv" to "1".toByteArray(), "second.csv" to "2".toByteArray()),
            "e.zip",
        ) {
            seen += it.name
            false
        }

        assertEquals(listOf("first.csv"), seen)
    }

    /**
     * A skipped entry must not end the archive. The stream handed to a reader is the ZIP's own, and
     * closing it — which any `use` block would — closes every entry after it too.
     */
    @Test
    fun anEntryReadAndClosedDoesNotEndTheArchive() {
        val read = mutableListOf<String?>()
        walkArchive(zipOf("a.csv" to "one".toByteArray(), "b.csv" to "two".toByteArray()), "e.zip") { entry ->
            entry.reader().use { it.readText() }.let(read::add)
            true
        }

        assertEquals(listOf("one", "two"), read)
    }

    /**
     * An archiver that writes its names in its own code page and says nothing of it, as older Windows
     * ones do. Were every name read as UTF-8, the first that was not would end the walk with
     * "MALFORMED" as all the import had to say.
     */
    @Test
    fun aNameInAnOldCodePageDoesNotEndTheWalk() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.ISO_8859_1).use { zip ->
            zip.putNextEntry(ZipEntry("Messungen März/weight.csv"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val names = mutableListOf<String>()

        walkArchive(out.toByteArray(), "e.zip") {
            names += it.fileName
            true
        }

        assertEquals(listOf("weight.csv"), names)
    }

    /** Directory entries are not files and must not arrive as empty ones. */
    @Test
    fun aFolderEntryIsNotAFile() {
        val read = texts(zipOf("folder/" to ByteArray(0), "folder/weight.csv" to "x".toByteArray()))

        assertEquals(setOf("folder/weight.csv"), read.keys)
    }

    /** Strava stores its routes gzipped, and Garmin has shipped gzipped JSON. */
    @Test
    fun aGzippedEntryComesOutAsWhatIsInsideIt() {
        val read = texts(zipOf("activities/1.json.gz" to gzipOf("{\"steps\":900}")))

        assertEquals("{\"steps\":900}", read["activities/1.json.gz"])
    }

    /**
     * A byte-order mark sits inside the *first column's name*, so a header that reads `Date` stops
     * comparing equal to those four letters and no format recognises the file at all.
     */
    @Test
    fun aByteOrderMarkIsNotPartOfTheFirstColumnName() {
        val read = texts("\uFEFFDate,Weight\n".toByteArray(), "weight.csv")

        assertEquals("Date,Weight\n", read["weight.csv"])
        assertEquals("date", read["weight.csv"]!!.substringBefore(',').headerKey())
    }

    /**
     * An entry longer than the cap ([ENTRY_BYTE_LIMIT], which follows the heap) is answered as null
     * rather than as its first bytes up to the cap: half a CSV parses perfectly well, and would
     * import a truncated history while reporting success.
     */
    @Test
    fun anEntryPastTheCapIsRefusedRatherThanTruncated() {
        var text: String? = "unset"
        // Highly compressible, so the archive stays small while what comes out of it does not.
        walkArchive(zipOf("huge.csv" to ByteArray(ENTRY_BYTE_LIMIT + 1)), "e.zip") { entry ->
            text = entry.text()
            true
        }

        assertNull(text)
    }

    /**
     * A walk that gives up at a limit says so. What it collected up to there is a part of the
     * archive that reads exactly like the whole of one, and the import would report it as the whole.
     */
    @Test
    fun aWalkThatGaveUpOnTheEntryCountSaysSo() {
        val names = (0..ENTRY_LIMIT).map { "$it.csv" }
        val seen = mutableListOf<String>()
        val whole = walkArchive(zipOf(*names.map { it to ByteArray(0) }.toTypedArray()), "e.zip") {
            seen += it.name
            true
        }

        assertFalse(whole)
        assertEquals(names.dropLast(1), seen)
    }

    /** The same answer for the bytes, which are counted as the readers take them. */
    @Test
    fun aWalkThatGaveUpOnTheByteBudgetSaysSo() {
        // Highly compressible, as above; as many of them as it takes to pass the budget, and one
        // small entry after them for the walk to give up on.
        val block = ByteArray(ENTRY_BYTE_LIMIT)
        val blocks = (0..ARCHIVE_BYTE_LIMIT / ENTRY_BYTE_LIMIT).map { "$it.csv" to block }
        val seen = mutableListOf<String>()
        val whole = walkArchive(zipOf(*blocks.toTypedArray(), "last.csv" to ByteArray(0)), "e.zip") { entry ->
            seen += entry.name
            // Read to its end and held nowhere, which is all the budget asks of a reader.
            entry.reader().skip(Long.MAX_VALUE)
            true
        }

        assertFalse(whole)
        assertEquals(blocks.map { it.first }, seen)
    }

    /**
     * Only an entry never handed over is one given up on: an archive that ends exactly where the
     * limit does, a walk its reader stopped and a file that is no archive were each read as far as
     * anybody wanted.
     */
    @Test
    fun aWalkThatWasNotCutShortSaysThatToo() {
        val full = (1..ENTRY_LIMIT).map { "$it.csv" to ByteArray(0) }

        assertTrue(walkArchive(zipOf(*full.toTypedArray()), "e.zip") { true })
        assertTrue(walkArchive(zipOf("a.csv" to ByteArray(0), "b.csv" to ByteArray(0)), "e.zip") { false })
        assertTrue(walkArchive("Date,Weight\n".toByteArray(), "weight.csv") { true })
    }

    /** An empty archive is not a crash, and not a file claimed by anybody. */
    @Test
    fun anEmptyArchiveReadsAsNothing() {
        assertTrue(texts(zipOf()).isEmpty())
    }

    /**
     * A picked archive is walked once, whoever's it is: looking for Apple's file in a walk of its own
     * would open and inflate every other archive twice. Apple's, holding records, is still
     * the whole answer when other files stand beside it.
     */
    @Test
    fun anArchiveIsWalkedOnceAndApplesFileIsStillItsAnswer() {
        val csv = "Date,Weight\n2026-01-01,80\n".toByteArray()
        val weighIn = "type=\"HKQuantityTypeIdentifierBodyMass\" unit=\"kg\" startDate=\"2026-03-01 07:00:00 +0100\""
        val apple = "<HealthData locale=\"en_GB\"><Record $weighIn value=\"72\"/></HealthData>".toByteArray()
        fun walked(bytes: ByteArray): Pair<Int, HealthExports> {
            var opened = 0
            val open = {
                opened += 1
                ByteArrayInputStream(bytes)
            }
            val found = readHealthExports(open, "export.zip", ZoneId.of("Europe/London"), imperial = false)
            return opened to found
        }

        val (plain, _) = walked(zipOf("weight.csv" to csv))
        val (beside, exports) = walked(zipOf("weight.csv" to csv, "apple_health_export/export.xml" to apple))

        assertEquals("the signature and the walk", 2, plain)
        assertEquals(2, beside)
        assertEquals(HealthExportApp.APPLE_HEALTH, exports.files.single().app)
        assertEquals(1, exports.files.single().records.size)
    }

    /** A file that is not a ZIP but is named like one is still read as what it is. */
    @Test
    fun somethingThatIsNotAnArchiveIsReadAsItself() {
        val read = texts("not a zip at all".toByteArray(), "export.zip")

        assertEquals(mapOf("export.zip" to "not a zip at all"), read)
    }
}
