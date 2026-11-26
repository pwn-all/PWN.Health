package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SQL the screenshot scripts under tools/ seed the database with, held against the schema.
 *
 * Those scripts run at a desk with an emulator and nowhere else, so nothing runs them when the schema
 * moves: one could go on naming a column the profile has lost, or leave out columns a course has
 * gained, and photograph the onboarding page under every name it is given. A JVM test cannot
 * boot an emulator. It can read every `INSERT INTO table(columns)` in them against the schema Room
 * exports, and refuse a column the table does not have, or a list that leaves out a NOT NULL column
 * with no default.
 */
class ToolSeedSqlTest {
    private val schema: File = File("schemas/com.pwnall.health.StepsDb")
        .listFiles { file -> file.extension == "json" }.orEmpty()
        .maxByOrNull { it.nameWithoutExtension.toInt() }
        ?: error("No exported schema where this test looks; fix the path rather than the test.")

    /** Per table: every column, and whether an INSERT has to give it a value. */
    private val tables: Map<String, Map<String, Boolean>> =
        schema.readText().split("\"tableName\"").drop(1).associate { entity ->
            val table = Regex("""^\s*:\s*"(\w+)"""").find(entity)?.groupValues?.get(1)
                ?: error("Unreadable entity in ${schema.path}")
            // The one column SQLite fills in by itself.
            val generated = Regex(""""autoGenerate"\s*:\s*true,\s*"columnNames"\s*:\s*\[\s*"(\w+)"""")
                .find(entity)?.groupValues?.get(1)
            table to Regex("""\{[^{}]*"columnName"\s*:\s*"(\w+)"[^{}]*\}""").findAll(entity).associate { field ->
                val column = field.groupValues[1]
                val notNull = Regex(""""notNull"\s*:\s*true""").containsMatchIn(field.value)
                column to (notNull && "\"defaultValue\"" !in field.value && column != generated)
            }
        }

    private data class Seed(val script: String, val table: String, val columns: List<String>)

    private val seeds: List<Seed> = sequenceOf("../tools", "tools").map(::File).first(File::isDirectory)
        .listFiles { file -> file.extension == "sh" }.orEmpty().sortedBy { it.name }
        .flatMap { script ->
            // A list broken across two Python string literals — `…,doneAt,"` and then `f"remindedAt)` — is one list.
            val text = script.readText().replace(Regex(""""\s*f?""""), "")
            Regex("""INSERT (?:OR REPLACE )?INTO (\w+)\(([^)]*)\)""").findAll(text).map { insert ->
                Seed(script.name, insert.groupValues[1], insert.groupValues[2].split(',').map(String::trim))
            }
        }

    @Test
    fun everySeedFitsTheSchema() {
        assertTrue("No INSERT under tools/*.sh: the scripts moved, or this test lost them.", seeds.isNotEmpty())
        seeds.forEach { (script, table, columns) ->
            val known = tables[table] ?: error("$script seeds $table, which ${schema.name} does not have")
            assertEquals("$script names columns $table does not have", emptyList<String>(), columns - known.keys)
            assertEquals(
                "$script leaves out columns of $table that are NOT NULL and have no default",
                emptySet<String>(),
                known.filterValues { it }.keys - columns.toSet(),
            )
        }
    }
}
