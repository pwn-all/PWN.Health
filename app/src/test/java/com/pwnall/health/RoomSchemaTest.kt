package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hand-written migration chain, held against the schema Room exports from the entities.
 *
 * The migrations carry every health record this app has ever stored. What a JVM test can do is
 * read the exported schema and the migrations that are supposed to produce it, and refuse a table,
 * a column or an index that appears in one and not the other. That is the mistake that opens as
 * "Migration didn't properly handle" on the phone of everyone upgrading, and it is invisible to a
 * fresh install — which is what the developer, CI and the pre-launch report all are. What a JVM
 * test cannot do is execute one: `MigrationTest` under app/src/androidTest does that on a device,
 * for the versions with an exported schema — 25 onwards. For 2 through 24 there is none, and
 * this file is the whole of the check; from 25 on every version's schema is committed, and each
 * step between two of them is read against the one migration named for it as well.
 *
 * The exported file is written by KSP during the build (see `room.schemaLocation` in
 * app/build.gradle.kts) and committed, so this test reads the same file a reviewer does.
 */
class RoomSchemaTest {
    private val dataSource = File("src/main/java/com/pwnall/health/Data.kt")
    private val source = dataSource.takeIf { it.isFile }?.readText()
        ?: error("${dataSource.path} is not where this test looks; fix the path rather than the test.")

    private val version = Regex("""\n\s*version = (\d+),""").find(source)
        ?.groupValues?.get(1)?.toInt()
        ?: error("No database version in ${dataSource.path}")

    private val schemaDir = File("schemas/com.pwnall.health.StepsDb")

    private val schemaFile = File(schemaDir, "$version.json")

    /** Name, and the two versions the constructor was actually given. */
    private val declared = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
        .findAll(source)
        .map { it.groupValues }
        .toList()

    /** The body of `ALL_MIGRATIONS`, the one list the builder is ever handed. */
    private val registered = Regex("""val ALL_MIGRATIONS: Array<Migration> = arrayOf\(([\s\S]*?)\n\s*\)""")
        .find(source)
        ?.groupValues?.get(1)
        ?: error("No ALL_MIGRATIONS array in ${dataSource.path}")

    /**
     * The tables that predate the chain, each with the columns it had then. Version 2 is where the
     * migrations begin — anything older is a development database [StepsDb] is allowed to drop — so
     * nothing in the source creates these four, and what they began with is written down here
     * instead. Every column one of them has gained since has to come from an ALTER, as for any other
     * table: left out of the check whole, the step counter's state and the routes — the tables that
     * have gained the most columns of all — would be the ones a field added without a migration gets
     * past.
     */
    private val baselineColumns = mapOf(
        "days" to setOf("day", "steps"),
        "state" to setOf("id", "bootId", "lastCumulative"),
        "tracks" to setOf("id", "startedAt", "endedAt", "distanceMeters", "points"),
        "trackpoints" to setOf(
            "id", "trackId", "timestamp", "lat", "lon", "altitudeM", "accuracyM", "speedMps", "provider", "accepted",
        ),
    )

    /** The one index that came with them. */
    private val baselineIndices = setOf("index_trackpoints_trackId_timestamp")

    private val createdTables: Map<String, String> =
        Regex("""CREATE TABLE IF NOT EXISTS `(\w+)` \(([\s\S]*?)\n\s*\)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val exported: Map<String, ExportedEntity> by lazy {
        val json = schemaFile.readText()
        json.split("\"tableName\"").drop(1).associate { chunk ->
            val table = Regex("^\\s*:\\s*\"(\\w+)\"").find(chunk)?.groupValues?.get(1)
                ?: error("Unreadable entity in ${schemaFile.path}")
            table to ExportedEntity(
                columns = chunk.captures("\"columnName\"\\s*:\\s*\"(\\w+)"),
                indices = chunk.captures("\"name\"\\s*:\\s*\"(index_[^\"]+)"),
            )
        }
    }

    private data class ExportedEntity(val columns: Set<String>, val indices: Set<String>)

    private fun String.captures(pattern: String): Set<String> =
        Regex(pattern).findAll(this).mapTo(mutableSetOf()) { it.groupValues[1] }

    @Test
    fun theMigrationChainRunsUnbrokenToTheDeclaredVersion() {
        val steps = declared.map { it[3].toInt() to it[4].toInt() }.sortedBy { it.first }
        assertEquals("the chain has to start where the supported history does", 2, steps.first().first)
        steps.forEachIndexed { index, (from, to) ->
            assertEquals("migration $from -> $to skips a version", from + 1, to)
            if (index > 0) assertEquals("nothing migrates to $from", from, steps[index - 1].second)
        }
        assertEquals("the last migration must reach the database version", version, steps.last().second)
    }

    @Test
    fun everyMigrationIsNamedAfterTheVersionsItMoves() {
        declared.forEach {
            assertEquals("MIGRATION_${it[1]}_${it[2]} moves ${it[3]} -> ${it[4]}", it[1] to it[2], it[3] to it[4])
        }
    }

    /**
     * A migration that exists but is never handed to the builder is a migration that never runs. The
     * builder takes the list whole, so the list is what has to be complete — and the builder has to
     * be taking it, or the device test that opens through that builder proves nothing about the app.
     */
    @Test
    fun everyDeclaredMigrationIsRegistered() {
        val names = registered.captures("""(MIGRATION_\d+_\d+)""")
        assertEquals(declared.map { "MIGRATION_${it[1]}_${it[2]}" }.toSet(), names)
        assertEquals(declared.size, Regex("""MIGRATION_\d+_\d+""").findAll(registered).count())
        assertTrue(
            "the builder has to be handed ALL_MIGRATIONS, not a list of its own",
            source.contains(".addMigrations(*ALL_MIGRATIONS)"),
        )
    }

    @Test
    fun theSchemaOfTheCurrentVersionIsExportedAndCommitted() {
        assertTrue(
            "${schemaFile.path} is missing. Room writes it during the build and it is committed; " +
                "check room.schemaLocation and exportSchema before deleting this test.",
            schemaFile.isFile,
        )
        val exportedVersion = Regex("\"version\"\\s*:\\s*(\\d+)").find(schemaFile.readText())
            ?.groupValues?.get(1)?.toInt()
        assertEquals("the exported schema is not this database's", version, exportedVersion)
        assertEquals(source.captures("tableName = \"(\\w+)\""), exported.keys)
    }

    /**
     * A schema once written does not change. KSP rewrites the file of the current version from the
     * entities on every build, so an entity edited without a new version, or a released migration
     * edited to match it, leaves every test here reading the new file and green, while a phone
     * already at that version opens with "Looks like you've changed schema but forgot to update the
     * version number". A failure here means: raise the version and write its migration. Only for a
     * version no tag has shipped yet is the pin below the thing to update.
     */
    @Test
    fun noCommittedSchemaChangesItsIdentity() {
        val versions = schemaDir.list().orEmpty().mapNotNull { it.removeSuffix(".json").toIntOrNull() }
        val hashes = versions.associateWith { number ->
            JSONObject(File(schemaDir, "$number.json").readText()).getJSONObject("database").getString("identityHash")
        }
        assertEquals(IDENTITY_HASHES, hashes)
    }

    /**
     * Every column of every table has to be reachable from an older install: created with the table,
     * or added to it later. A field added to an entity without an ALTER fails here.
     */
    @Test
    fun everyExportedColumnIsCreatedByTheMigrationChain() {
        exported.forEach { (table, entity) ->
            val began = baselineColumns[table]
            val body = createdTables[table]
            assertTrue("no migration creates `$table`", began != null || body != null)
            val created = began.orEmpty() + body.orEmpty().captures("""`(\w+)`""") +
                source.captures("""ALTER TABLE `$table` ADD COLUMN `(\w+)`""")
            assertEquals("columns of `$table` no migration writes", emptySet<String>(), entity.columns - created)
        }
    }

    @Test
    fun everyExportedIndexIsCreatedByTheMigrationChain() {
        exported.forEach { (table, entity) ->
            (entity.indices - baselineIndices).forEach {
                assertTrue(
                    "no migration creates `$it` on `$table`",
                    source.contains("CREATE INDEX IF NOT EXISTS `$it`"),
                )
            }
        }
    }

    /** What Room compares of a column when it opens a migrated file: what it holds, and whether it may be empty. */
    private data class Column(val affinity: String, val notNull: Boolean)

    /** A table as one version's schema exports it: its CREATE TABLE and those of its indices, the name filled in. */
    private data class Table(val createSql: String, val columns: Map<String, Column>, val indices: Map<String, String>)

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    /** The tables of the schema committed for version [number], by name. */
    private fun schemaOf(number: Int): Map<String, Table> =
        JSONObject(File(schemaDir, "$number.json").readText())
            .getJSONObject("database")
            .getJSONArray("entities")
            .objects()
            .associate { entity ->
                val table = entity.getString("tableName")
                table to Table(
                    createSql = entity.getString("createSql").replace(TABLE_NAME, table),
                    columns = entity.getJSONArray("fields").objects().associate {
                        it.getString("columnName") to Column(it.getString("affinity"), it.optBoolean("notNull"))
                    },
                    indices = (entity.optJSONArray("indices") ?: JSONArray()).objects().associate {
                        it.getString("name") to it.getString("createSql").replace(TABLE_NAME, table)
                    },
                )
            }

    /**
     * SQL with the line breaks, the indentation and every other space out of it, so that a statement
     * laid out over a dozen lines of a migration and the single line Room exports can be compared
     * letter for letter.
     */
    private fun String.squeezed(): String = replace(Regex("""\s+"""), "")

    /**
     * The statements of the migration that leaves version [older], squeezed: its source from its
     * declaration to the next one's, the pieces of every string joined where a `+` breaks it across
     * two lines. The KDoc of the migration declared after it rides along, and holds no SQL.
     */
    private fun statementsOf(older: Int): String {
        val declarations = Regex("""val MIGRATION_(\d+)_\d+ = object : Migration\(""").findAll(source).toList()
        val at = declarations.indexOfFirst { it.groupValues[1].toInt() == older }
        assertTrue("no migration leaves version $older", at >= 0)
        val end = declarations.getOrNull(at + 1)?.range?.first ?: source.indexOf("val ALL_MIGRATIONS")
        return source.substring(declarations[at].range.first, end).replace(Regex("\"\\s*\\+\\s*\""), "").squeezed()
    }

    /**
     * Each step between two committed schemas, read against the one migration named for it.
     *
     * The tests above ask only that a column is written somewhere in the chain, and a column typed
     * into the CREATE TABLE of an old migration satisfies them — as it does the device test, which
     * builds its oldest file with that very statement. A phone already past that step never runs it
     * again and opens with "Migration didn't properly handle". So whatever the schema of version
     * N+1 holds that N's does not has to be written by MIGRATION_N_(N+1) itself. A table that is
     * new, or rebuilt because a column left it or changed, by the CREATE TABLE Room exported,
     * letter for letter once the spaces are out — which holds its keys to the export too. A column
     * added by an ALTER of the same type and the same NOT NULL, with the DEFAULT SQLite will not
     * add a NOT NULL column without. An index by its CREATE INDEX.
     */
    @Test
    fun everyStepBetweenCommittedSchemasIsWrittenByItsOwnMigration() {
        val versions = schemaDir.list().orEmpty().mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()
        assertEquals(
            "a schema has to be committed for every version since the first exported",
            (versions.first()..version).toList(),
            versions,
        )
        versions.zipWithNext().forEach { (older, newer) -> holdStepToItsMigration(older, newer) }
    }

    private fun holdStepToItsMigration(older: Int, newer: Int) {
        val step = "MIGRATION_${older}_$newer"
        val statements = statementsOf(older)
        // Table and column, against the column as the ALTER types it and whether it gives a DEFAULT.
        val added = ADDED_COLUMN.findAll(statements).map { it.groupValues }.associate {
            Pair(it[1], it[2]) to Pair(Column(it[3], it[4].isNotEmpty()), it[5].isNotEmpty())
        }
        val before = schemaOf(older)
        schemaOf(newer).forEach { (table, now) ->
            val was = before[table]
            val columnsBefore = was?.columns.orEmpty()
            val indicesBefore = was?.indices.orEmpty()
            val rebuilt = was == null || columnsBefore.any { (name, column) -> now.columns[name] != column }
            if (rebuilt) {
                assertTrue(
                    "$step has to create `$table` as version $newer exports it",
                    statements.contains(now.createSql.squeezed()),
                )
            }
            val gained = if (rebuilt) emptyMap() else now.columns - columnsBefore.keys
            gained.forEach { (name, column) ->
                val (written, defaulted) = added[Pair(table, name)] ?: Pair(null, false)
                assertEquals("what $step adds as `$table`.`$name`", column, written)
                assertTrue("$step adds `$table`.`$name` NOT NULL with no DEFAULT", defaulted || !column.notNull)
            }
            val indices = now.indices.filter { (name, create) -> rebuilt || indicesBefore[name] != create }
            indices.forEach { (name, create) ->
                assertTrue(
                    "$step has to create `$name` as version $newer exports it",
                    statements.contains(create.squeezed()),
                )
            }
        }
    }

    private companion object {
        /** The placeholder Room leaves for the table's name in the statements it exports. */
        const val TABLE_NAME = "\${TABLE_NAME}"

        /** `ALTER TABLE … ADD COLUMN`, as [squeezed] leaves it: table, column, type, NOT NULL, DEFAULT. */
        val ADDED_COLUMN = Regex("ALTERTABLE`(\\w+)`ADDCOLUMN`(\\w+)`(INTEGER|TEXT|REAL|BLOB)(NOTNULL)?(DEFAULT)?")

        /** The identity Room stamps into a database at each version. */
        val IDENTITY_HASHES = mapOf(
            25 to "3c1ae4883f78494e04a93a2108ad4d3b",
            26 to "0c1357fac0935fd95436ac56a40014e4",
            27 to "f061dd2aac1540d5bbcd0eadc1a34ecd",
            28 to "834e0eb49d9ef885ada5ce35b6b35b4c",
            29 to "d600c6f644b7948e440fe0bcbf8a2bfc",
            30 to "04c0c21b7eae5f194ea533cca776e3f0",
            31 to "fc24d99f4f986293c3fdc0f18cb85bbe",
            32 to "68e9328b08370eab094b1e27c12e46ed",
            33 to "f4ae3a795e789abba1876ef2c525124d",
            34 to "b1531cef67251a1adb555999a72dbdc0",
            35 to "4132d9f487299692081d684caaa78e66",
            36 to "b22cce935f11e5ccf0dabe84a75d9ed3",
            37 to "2d70e46399e8a6671b2f9c0ad708a0bb",
            38 to "e5c5b870a24075a8aa4f5e2b8140bb4d",
            39 to "527d5c62357accb2c7319709794580c3",
            40 to "f212ae6e90ba5e8d3bf684aa0384e985",
            41 to "52ececa4f9274267836e594e7733d8c1",
            42 to "139f58fb8ea005370d4d453dad1e77cf",
            43 to "e596b5d67c3e6562d20ec8ab03eb8816",
            44 to "c53cfb24e3876ef3f9d9cbba59858b5a",
            45 to "5494b54dc09cc2741ea6f6b5b86a815d",
            46 to "7deecaf1afaf88b8d2e98487f9480095",
            47 to "91e7df8878e0514b76b139c9c8423fba",
            48 to "295c148306641b61575bf0d015bdf313",
        )
    }
}
