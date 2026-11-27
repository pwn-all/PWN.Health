package com.pwnall.health

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/*
 * The archive the other service hands over, opened without unpacking it.
 *
 * Every one of the twelve services this app reads exports a ZIP, and half of them put the file that
 * matters three folders deep beside a hundred that do not. So the picker takes the archive itself
 * rather than asking somebody to find `weight.csv` inside it — an instruction that cannot even be
 * followed on a phone, where the Files app will not open a ZIP to let you pick one thing out of it.
 *
 * Nothing is ever written to disk. An entry is read into memory, used, and dropped; the one entry
 * that cannot fit in memory — Apple Health's, which is routinely half a gigabyte and has been seen
 * past two — is handed to its reader as a stream and never held whole at all. That is also the
 * whole of the zip-slip defence: a path that escapes its folder is only dangerous to something that
 * extracts, and this extracts nothing. What it does defend against is the archive that is a bomb,
 * and that is [ENTRY_LIMIT] and [ENTRY_BYTE_LIMIT] below.
 */

/**
 * The signature every ZIP begins with.
 *
 * `PK` and then the record it opens with, which is a local file header (03 04) in every archive that
 * holds anything, the end of the central directory (05 06) in an empty one, and a spanning marker
 * (07 08) in an archive that was split across volumes. Matching only the first of the three reads an
 * empty archive as though it were a text file.
 */
private const val ZIP_P = 0x50.toByte()
private const val ZIP_K = 0x4B.toByte()
private val ZIP_RECORDS = setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte())

/**
 * How many entries are walked before the archive is called hostile.
 *
 * A real export is generous with files — a Garmin account archive runs to a few hundred, an Apple
 * workout-routes folder to one GPX per session and so into the thousands — so the limit is well
 * above any of them. It exists for the other kind of archive: entry after empty entry, a header and
 * a name apiece, which deliver nothing and cost the walk time all the same. The count is of the
 * entries the walk meets: [ZipInputStream] reads each entry's own header in turn and never the
 * central directory, so what a directory claims decides nothing here.
 */
internal const val ENTRY_LIMIT = 20_000

/**
 * How much of one entry is read into memory before it is abandoned.
 *
 * Everything read whole here is a CSV or a JSON of a person's own history, and the largest of those
 * seen in practice — a decade of Cronometer servings, a Samsung Health step file — is a few tens of
 * megabytes. Past this the entry is not read at all rather than read in part: half a CSV parses
 * perfectly well and would import a truncated history while reporting success, which is worse than
 * importing nothing.
 *
 * No more than an eighth of the heap either. An entry is held as its bytes, as its text and as the
 * cells or the tree parsed out of that, several times its own size at once, so one past that share
 * could only have ended in an OutOfMemoryError; refused here, it is counted and said instead.
 */
internal val ENTRY_BYTE_LIMIT: Int =
    minOf(MOST_ENTRY_BYTES, Runtime.getRuntime().maxMemory() / HEAP_SHARES_PER_ENTRY).toInt()

private const val MOST_ENTRY_BYTES = 96L * 1024 * 1024
private const val HEAP_SHARES_PER_ENTRY = 8

/**
 * Decompressed bytes handed to the readers across the whole archive, past which the walk stops.
 *
 * Only what a reader was handed. An entry passed over by its name, and the rest of one refused for
 * its size, are skipped by the [ZipInputStream] itself and are not counted: they cost time and no
 * memory, and the per-sample files a Fitbit archive carries would spend the budget several times
 * over without one of them being read.
 */
internal const val ARCHIVE_BYTE_LIMIT = 512L * 1024 * 1024

/** One entry, as its name and a stream of its bytes that is only valid inside the callback. */
internal class ArchiveEntry(val name: String, stream: InputStream) {
    /**
     * The entry's bytes, unwrapped once.
     *
     * Once, because a `.gz` entry read twice would build a second [GZIPInputStream] over a stream
     * whose header has already been consumed, and the second read would fail rather than repeat.
     */
    private val source: InputStream by lazy {
        if (fileName.endsWith(".gz")) GZIPInputStream(stream) else stream
    }

    /** The entry's own file name, lowercased, with every folder above it dropped. */
    val fileName: String get() = name.substringAfterLast('/').substringAfterLast('\\').lowercase()

    /**
     * The whole entry as text, or null when it is longer than [ENTRY_BYTE_LIMIT].
     *
     * A `.gz` inside the archive is unwrapped here rather than by the caller: Strava stores its
     * routes that way and Garmin has shipped gzipped JSON. No export's reader is handed one,
     * though: [readHealthExports] admits an entry by its last extension, so a `.json.gz` is passed
     * over before it is opened, and only the tests read one. The gate is left that way on purpose:
     * past it, every one of the thousands of `.tcx.gz` routes in a Strava archive would be inflated
     * and read, and could spend [ARCHIVE_BYTE_LIMIT] before the one table that matters was reached.
     */
    fun text(): String? = bounded()?.exportText()

    /**
     * The entry as characters, for a reader that must not hold the file whole.
     *
     * Apple Health's `export.xml` is the only caller. The stream is the archive's own, so the reader
     * has to consume it before the walk moves on — which is exactly what a streaming reader does.
     */
    fun reader(): Reader = InputStreamReader(source, Charsets.UTF_8)

    /** True once [text] refused the entry for its size, so the walk can count what it did not read. */
    var refused: Boolean = false
        private set

    /** Reads at most [ENTRY_BYTE_LIMIT] bytes, and answers null rather than a truncated prefix. */
    private fun bounded(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            if (out.size().toLong() + read > ENTRY_BYTE_LIMIT) {
                refused = true
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

private const val BUFFER_BYTES = 64 * 1024
private const val ZIP_SIGNATURE_BYTES = 4

/**
 * Walks the picked file: every entry of it if it is an archive, and the file itself if it is not.
 *
 * [visit] is called with each entry in the order the archive stores them and may stop the walk by
 * returning false, which is what a reader that has found its file does — a Garmin archive holds
 * several hundred entries and there is no reason to decompress the rest of them once the one that
 * was wanted has been read.
 *
 * A file that is not a ZIP is presented as a single entry named after itself, so a reader written
 * against archives also reads the bare `activities.csv` somebody unpacked by hand on a desktop and
 * copied to their phone.
 *
 * Answers false when the walk gave up at [ENTRY_LIMIT] or [ARCHIVE_BYTE_LIMIT] with entries it never
 * handed over, and that has to be said by whoever reports the import: what was collected up to there
 * is a part of the archive that reads exactly like the whole of one. A walk [visit] stopped, and one
 * that reached the end, answer true.
 */
internal fun walkArchive(open: () -> InputStream, name: String, visit: (ArchiveEntry) -> Boolean): Boolean {
    // Opened once to read the signature and again to walk: the file is streamed from wherever it
    // lives, never held whole — an Apple archive is hundreds of megabytes.
    val head = open().use { it.readNBytes(ZIP_SIGNATURE_BYTES) }
    if (!head.startsWithZipMagic()) {
        open().use { visit(ArchiveEntry(name, it)) }
        return true
    }
    // Names are read as ISO 8859-1 unless an entry flags its own as UTF-8, which the stream honours
    // whatever is passed here. The default reads every name as UTF-8 and throws "MALFORMED" on the
    // first one an older Windows archiver wrote in its own code page, and that word would be all the
    // import said.
    // A name is matched only by its ASCII extension and prefix, which any single-byte reading keeps.
    return ZipInputStream(open(), Charsets.ISO_8859_1).use { zip ->
        var entries = 0
        val delivered = Counter()
        var gaveUp = false
        var carryOn = true
        while (carryOn) {
            val entry = zip.nextEntry
            entries += 1
            // Asked once there is another entry to give up on, so an archive that ends exactly where
            // a limit does counts as read whole. The bytes are counted after the fact rather than read off
            // the entry's header: a ZipInputStream reports a size of -1 for every entry a streaming
            // writer produced, which is most of them, so a budget spent on the declared size would
            // never be spent at all. What is measured here is what the archive actually handed
            // over, which is the number a bomb lies about.
            gaveUp = entry != null && (entries > ENTRY_LIMIT || delivered.bytes > ARCHIVE_BYTE_LIMIT)
            carryOn = when {
                entry == null || gaveUp -> false

                entry.isDirectory -> true

                // The stream is deliberately not closed: closing a ZipInputStream's entry stream
                // closes the archive, and the walk has more entries to give.
                else -> visit(ArchiveEntry(entry.name, CountedEntry(zip, delivered)))
            }
        }
        !gaveUp
    }
}

/** How much the archive has actually handed its readers so far, across all of its entries. */
private class Counter {
    var bytes = 0L
}

/**
 * An entry's bytes, counted, and without the power to end the archive they came out of.
 *
 * Closing a [ZipInputStream]'s current entry closes the whole archive, and a reader that politely
 * closes what it was given would take the remaining entries with it.
 */
private class CountedEntry(private val source: InputStream, private val counter: Counter) : InputStream() {
    override fun read(): Int = source.read().also { if (it >= 0) counter.bytes += 1 }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        source.read(b, off, len).also { if (it > 0) counter.bytes += it }

    override fun available(): Int = source.available()

    override fun close() = Unit
}

private fun ByteArray.startsWithZipMagic(): Boolean =
    size >= ZIP_SIGNATURE_BYTES && this[0] == ZIP_P && this[1] == ZIP_K && this[2] in ZIP_RECORDS
