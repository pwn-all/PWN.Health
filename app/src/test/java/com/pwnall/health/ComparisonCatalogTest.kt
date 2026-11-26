package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalogue of comparisons and its standing record, docs/REPORT_COMPARISONS.md, kept in step.
 *
 * A pair reaches a doctor's sheet only after it has been worked through in the doc — why its two
 * figures can move together, what else moves them, what the app does not record — so a key without a
 * section fails here, and so does a section for a key the code does not have.
 */
class ComparisonCatalogTest {
    private val doc = File("../docs/REPORT_COMPARISONS.md")

    /** Every `## <key>` heading in the doc: a lowercase key alone on the line. */
    private fun documentedKeys(): List<String> =
        KEY_HEADING.findAll(doc.readText()).map { it.groupValues[1] }.toList()

    private fun codeKeys(): List<String> =
        ComparisonPair.entries.map(ComparisonPair::key) + LabComparison.entries.map(LabComparison::key)

    @Test
    fun everyKeyHasASectionInTheDocAndEverySectionAKey() {
        assertTrue("${doc.absolutePath} is missing", doc.isFile)
        val documented = documentedKeys()

        assertEquals("keys with no section in the doc", emptySet<String>(), codeKeys().toSet() - documented.toSet())
        assertEquals(
            "sections for keys the code does not have",
            emptySet<String>(),
            documented.toSet() - codeKeys().toSet(),
        )
        assertEquals("a key documented twice", documented.distinct(), documented)
    }

    @Test
    fun keysAreUniqueAndSpelledAsTheirHeadings() {
        val keys = codeKeys()

        assertEquals(keys.distinct(), keys)
        keys.forEach { assertTrue(it, KEY.matches(it)) }
        ComparisonPair.entries.forEach { assertEquals(it.name.lowercase(), it.key) }
        LabComparison.entries.forEach { assertEquals(it.name.lowercase(), it.key) }
    }

    /**
     * A pair compares two different figures, a third is named only with a sentence for it, and what
     * the doc lists as never printed stays out of the catalogue.
     */
    @Test
    fun everyPairSetsTwoDifferentFiguresSideBySide() {
        ComparisonPair.entries.forEach { pair ->
            assertNotEquals(pair.key, pair.exposure, pair.outcome)
            assertEquals(pair.key, pair.also == null, pair.alsoSentenceRes == null)
            assertEquals(pair.key, pair.maleBackgroundRes == null, pair.maleSourceRes == null)
            assertTrue(pair.key, pair.also != pair.exposure && pair.also != pair.outcome)
            val named = listOfNotNull(pair.exposure, pair.outcome, pair.also)
            // Water is described and never paired, and weight is never set beside sleep: see "Never
            // printed" in the doc.
            assertTrue(pair.key, ChangeDomain.WATER !in named)
            assertFalse(pair.key, named.containsAll(listOf(ChangeDomain.WEIGHT, ChangeDomain.SLEEP)))
        }
        LabComparison.entries.forEach {
            assertTrue(it.key, it.markers.isNotEmpty())
            // HbA1c is beside no diary figure: see "Never printed" in the doc.
            assertTrue(it.key, LabMarker.HBA1C !in it.markers)
        }
        // Nor is the cycle's phase a figure: see "Never printed" in the doc.
        assertTrue(ChangeDomain.entries.none { it.part == ReportPart.CYCLE })
    }

    /**
     * Each entry's strings exist in English, the sentence and the background are written for it, and
     * the citation is marked as one nobody translates — a background for male profiles alone as well.
     */
    @Test
    fun everyEntryCarriesItsOwnSentenceBackgroundAndCitation() {
        val xml = File("src/main/res/values/ui.xml").readText()
        fun name(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

        ComparisonPair.entries.forEach { pair ->
            assertEquals("report_changes_pair_${pair.key}", name(pair.sentenceRes))
            assertEquals("report_changes_background_${pair.key}", name(pair.backgroundRes))
            assertEquals("report_changes_source_${pair.key}", name(pair.sourceRes))
            pair.maleBackgroundRes?.let { assertEquals("report_changes_background_${pair.key}_male", name(it)) }
            pair.maleSourceRes?.let { assertEquals("report_changes_source_${pair.key}_male", name(it)) }
        }
        LabComparison.entries.forEach { comparison ->
            assertEquals("report_changes_background_${comparison.key}", name(comparison.backgroundRes))
            assertEquals("report_changes_source_${comparison.key}", name(comparison.sourceRes))
        }
        val sources = ComparisonPair.entries.flatMap { listOfNotNull(it.sourceRes, it.maleSourceRes) } +
            LabComparison.entries.map { it.sourceRes }
        sources.map(::name).forEach { source ->
            assertTrue("$source is translated", """<string name="$source" translatable="false">""" in xml)
        }
    }

    private companion object {
        val KEY = Regex("[a-z0-9_]+")
        val KEY_HEADING = Regex("""^## ([a-z0-9_]+)\s*$""", RegexOption.MULTILINE)
    }
}
