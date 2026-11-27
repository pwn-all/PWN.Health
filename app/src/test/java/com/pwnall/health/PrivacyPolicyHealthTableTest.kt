package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6 of the policy tabulates the Health Connect request row by row, and the rationale screen is the
 * same table: Play reads the two side by side, so a type the screen names and the policy does not —
 * or a reason worded one way in the app and another in the policy — is a grant explained twice in
 * two different ways. Each row of [HEALTH_RATIONALE_ROWS] must stand in the policy as its English
 * names and its English purpose, word for word, and the policy's table must have no other rows.
 */
class PrivacyPolicyHealthTableTest {
    private val policy = File("src/main/assets/privacy-policy.html").readText()

    /** Every English string by its resource name, unescaped the way Android reads it. */
    private val english: Map<String, String> = File("src/main/res/values").listFiles().orEmpty()
        .filter { it.extension == "xml" }
        .flatMap { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
                .toList()
        }.toMap()

    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun text(res: Int): String = english.getValue(nameOf.getValue(res))

    private fun expectedRows(): List<String> = HEALTH_RATIONALE_ROWS.map { row ->
        val left = if (row.types.isEmpty()) {
            text(requireNotNull(row.title))
        } else {
            row.types.joinToString(", ") { text(HEALTH_RECORD_TYPE_NAMES.getValue(it)) }
        }
        "<tr><td>$left</td><td>${text(row.purpose)}</td></tr>"
    }

    /** The table that follows the header "Records", as its rows are written. */
    private fun policyRows(): List<String> {
        val table = policy.substringAfter("<tr><th>Records</th>").substringBefore("</table>")
        return Regex("""<tr>.*?</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(table).map { it.value.trim() }.toList()
    }

    @Test
    fun theHealthConnectTableIsTheRationaleScreenWordForWord() {
        assertTrue("§6 has no Health Connect table", policy.contains("<tr><th>Records</th>"))
        assertEquals(expectedRows(), policyRows())
    }
}
