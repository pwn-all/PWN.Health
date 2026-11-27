package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The two documents a clinician and a privacy reviewer are handed about the doctor's report, held
 * to the code they describe: `SPECIALTY_PROFILES.md` says it is `DoctorSpecialty.kt` read out, and
 * `PRIVACY.md` counts the sensitive sections.
 *
 * Either can drift: a preset missing from the table, or a section a preset ticks or suggests (the
 * problem list, pregnancy, contraception, the morning check-ins) left out of its row. A reviewer
 * approves what the table shows, so a tick the table leaves out is one nobody reviewed.
 *
 * Read as files, from the module directory the unit tests run in, like the changelog test.
 */
class SpecialtyProfilesDocTest {
    private val profiles = File("../docs/medical-export/SPECIALTY_PROFILES.md").readText()

    /** The "What ships" table, a row per preset, its cells without the outer pipes. */
    private val rows: List<List<String>> = profiles.substringAfter("## What ships").substringBefore("\n\n## ")
        .lines()
        .filter { it.startsWith("| ") && !it.startsWith("| Preset") && !it.startsWith("| ---") }
        .map { row -> row.split("|").drop(1).dropLast(1).map(String::trim) }

    @Test
    fun theTableHasARowForEveryPresetInTheEnumsOrder() {
        assertEquals(DoctorSpecialty.entries.size, rows.size)
        DoctorSpecialty.entries.zip(rows).forEach { (specialty, row) ->
            assertEquals("${row[0]} is not $specialty's row", SEX_WORDS.getValue(specialty.sex), row[1])
            assertEquals(row[0], "${specialty.defaultMonths} months", row[2])
        }
    }

    /** "My own choice" ticks every routine section by its own rule, which its row says in words. */
    @Test
    fun everyRowTicksAndSuggestsWhatItsPresetDoes() {
        DoctorSpecialty.entries.zip(rows).forEach { (specialty, row) ->
            if (specialty != DoctorSpecialty.CUSTOM) assertEquals(row[0], specialty.recommended, parts(row[3]))
            assertEquals(row[0], specialty.optional, parts(row[4]))
        }
    }

    /** A sensitive section is in bold wherever a row names it, and nothing else is. */
    @Test
    fun theTableMarksEverySensitiveSectionItNames() {
        rows.flatMap { it[3].split(", ") + it[4].split(", ") }.forEach { word ->
            val part = WORDS[word.removeSurrounding("**")] ?: return@forEach
            assertEquals(word, part.sensitivity == DataSensitivity.SENSITIVE, word.startsWith("**"))
        }
    }

    @Test
    fun theDocumentsCountTheSensitiveSectionsAndTheSpecialtiesThatSuggestThem() {
        val privacy = File("../docs/medical-export/PRIVACY.md").readText().replace(Regex("""\s+"""), " ")
        val sections = checkNotNull(Regex("""in this app it is (\w+) sections""").find(privacy)).groupValues[1]
        val suggesting = checkNotNull(Regex("""(\w+) specialties list such sections""").find(profiles))
            .groupValues[1]

        assertEquals(ReportPart.entries.count { it.sensitivity == DataSensitivity.SENSITIVE }, NUMBERS[sections])
        assertEquals(
            DoctorSpecialty.entries.count { s -> s.optional.any { it.sensitivity == DataSensitivity.SENSITIVE } },
            NUMBERS[suggesting.lowercase()],
        )
    }

    private fun parts(cell: String): Set<ReportPart> =
        if (cell == "nothing") {
            emptySet()
        } else {
            cell.split(", ").map { word ->
                checkNotNull(WORDS[word.removeSurrounding("**")]) { "\"$word\" names no section this test knows" }
            }.toSet()
        }

    private companion object {
        val SEX_WORDS = mapOf(null to "anyone", "FEMALE" to "female", "MALE" to "male")
        val NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)

        /** What the table calls each section. */
        val WORDS = mapOf(
            "problem list" to ReportPart.PROBLEMS,
            "operations" to ReportPart.PROCEDURES,
            "vaccinations" to ReportPart.IMMUNISATIONS,
            "medicines" to ReportPart.MEDICATIONS,
            "blood tests" to ReportPart.LABS,
            "weight" to ReportPart.WEIGHT,
            "body composition" to ReportPart.BODY_COMPOSITION,
            "blood pressure" to ReportPart.PRESSURE,
            "pulse" to ReportPart.PULSE,
            "SpO₂" to ReportPart.OXYGEN,
            "temperature" to ReportPart.TEMPERATURE,
            "activity" to ReportPart.ACTIVITY,
            "sleep" to ReportPart.SLEEP,
            "food" to ReportPart.NUTRITION,
            "mood" to ReportPart.MOOD,
            "alcohol/tobacco" to ReportPart.SOCIAL,
            "work hours" to ReportPart.WORK,
            "bowel/bladder" to ReportPart.BOWEL,
            "pain" to ReportPart.PAIN,
            "visits" to ReportPart.VISITS,
            "pregnancy" to ReportPart.PREGNANCY,
            "contraception" to ReportPart.CONTRACEPTION,
            "cycle" to ReportPart.CYCLE,
            "symptoms during sex" to ReportPart.SEXUAL_SYMPTOMS,
            "sexual activity" to ReportPart.SEXUAL_HEALTH,
            "morning check-ins" to ReportPart.MORNING_ERECTIONS,
        )
    }
}
