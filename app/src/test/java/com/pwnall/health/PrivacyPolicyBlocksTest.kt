package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-app policy is set block by block — headings as headings, list items, table rows — rather
 * than as one run of text, and a legal document cut into blocks must not lose a word on the way.
 * Read from the asset the app opens, from the module directory the unit tests run in.
 */
class PrivacyPolicyBlocksTest {
    private val html = File("src/main/assets/privacy-policy.html").readText()
    private val body = html.substringAfter("<body>").substringBefore("</body>")
    private val blocks = policyBlocks(html)

    @Test
    fun `every word of the policy lands in exactly one block, in reading order`() {
        val fromBlocks = blocks.flatMap { it.cells }.joinToString(" ")
        assertEquals(words(body), words(fromBlocks))
    }

    @Test
    fun `every heading in the document is set as one`() {
        val headings = Regex("""<h[1-3]\b""").findAll(body).count()
        val kinds = setOf(PolicyBlockKind.TITLE, PolicyBlockKind.SECTION, PolicyBlockKind.SUBSECTION)
        assertTrue(headings > 0)
        assertEquals(headings, blocks.count { it.kind in kinds })
        assertEquals(PolicyBlockKind.TITLE, blocks.first().kind)
    }

    @Test
    fun `the permissions table keeps its two columns and its header row`() {
        val rows = blocks.filter { it.kind == PolicyBlockKind.TABLE_HEAD || it.kind == PolicyBlockKind.TABLE_ROW }
        assertEquals(Regex("""<tr\b""").findAll(body).count(), rows.size)
        assertEquals(PolicyBlockKind.TABLE_HEAD, rows.first().kind)
        assertTrue(rows.all { it.cells.size == 2 })
    }

    /** The words between the tags, entities and all, with the markup and spacing thrown away. */
    private fun words(text: String): List<String> =
        Regex("""[\w&;#]+""").findAll(text.replace(Regex("<[^>]+>"), " ")).map { it.value }.toList()
}
