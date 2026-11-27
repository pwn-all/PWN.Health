package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The names of the constants that come back out of text.
 *
 * Some eighty enums are written by `name` into a journal row's payload, a column, a backup, the
 * preferences or a saved screen, and read back by looking the name up among `entries` with a fall
 * to null or to a default. That is the right reading of a name a newer version wrote. Its other
 * side is that renaming a constant compiles, passes every other test, and from then on reads each
 * row already written as nothing: a course loses "before food", a pain its kind, with no error
 * anywhere, unless the name happens to stand in some test's example.
 *
 * `src/test/resources/stored-enum-names.txt` is every one of those lists as it stands. A constant
 * renamed or dropped fails here and names itself, and so does a new one, so that the file stays the
 * whole list. What to do then is in the file's first lines.
 */
class StoredEnumNamesTest {
    @Test
    fun noNameThatIsReadBackChangesUnnoticed() {
        val sources = File("src/main/java/com/pwnall/health").listFiles().orEmpty()
            .filter { it.extension == "kt" }.map { it.readText() }
        // A scan that read nothing finds nothing to hold.
        assertTrue("${sources.size} sources", sources.size > 100)
        val types = readBackByName(sources)
        val nested = nestedEnums(sources).filter { it in types && namesOf(it) == null }
        assertEquals("nested enums read back by name, which this test cannot load", emptyList<String>(), nested)
        val now = types.mapNotNull { type -> namesOf(type)?.let { type to it } }.toMap()
        assertTrue("only ${now.size} enums found read back by name", now.size > 60)
        // Written for whoever has to bring the list up to date: the whole of it, ready to copy.
        File("build/stored-enum-names.txt").apply { parentFile?.mkdirs() }.writeText(HEADER + render(now))

        val kept = parse(checkNotNull(javaClass.getResourceAsStream("/stored-enum-names.txt")).reader().readText())
        assertEquals(
            "see the first lines of src/test/resources/stored-enum-names.txt; the list as the code has it " +
                "now is in app/build/stored-enum-names.txt",
            emptyList<String>(),
            storedNameChanges(kept, now),
        )
    }

    @Test
    fun aRenameIsALossAndAnAdditionAndBothAreSaid() {
        val kept = mapOf("MedFood" to listOf("ANY", "BEFORE", "AFTER"), "Gone" to listOf("A"))
        val now = mapOf("MedFood" to listOf("ANY", "BEFORE_FOOD", "AFTER", "WITH"), "Fresh" to listOf("B"))
        assertEquals(
            listOf(
                "Fresh is read back by name and not listed: B",
                "Gone is listed and no longer read back by name",
                "MedFood lost BEFORE: what was written under that name now reads as nothing",
                "MedFood gained BEFORE_FOOD, WITH: list them",
            ),
            storedNameChanges(kept, now),
        )
        assertEquals(emptyList<String>(), storedNameChanges(kept, kept))
        // The order of the constants is nothing a name is read by.
        val swapped = storedNameChanges(mapOf("T" to listOf("A", "B")), mapOf("T" to listOf("B", "A")))
        assertEquals(emptyList<String>(), swapped)
    }

    @Test
    fun theSpellingsOfALookUpByNameAreAllSeen() {
        val source = """
            val a = MedFood.entries.firstOrNull { it.name == stored } ?: MedFood.ANY
            val b = SexNote.entries.filter { "\"${'$'}{it.name}\"" in inside }
            val c = runCatching { MedPage.valueOf(saved) }.getOrNull()
            val d = enumNamed<ProblemArea>(text)
            val e = BigDecimal.valueOf(1.0)
            val f = Mood.entries.size
        """.trimIndent()
        val seen = readBackByName(listOf(source))
        assertEquals(setOf("MedFood", "SexNote", "MedPage", "ProblemArea", "BigDecimal"), seen)
        assertEquals(listOf("A", "B"), parse("# said\n\nT: A B\n").getValue("T"))
        assertEquals("T: A B\n", render(mapOf("T" to listOf("A", "B"))))
    }

    /**
     * The types [sources] look up by a constant's name: `entries` walked with `name` on the line,
     * `valueOf`, and the two private helpers that do the same for a type argument. A name that is
     * no enum of this package — BigDecimal — is dropped by [namesOf].
     */
    private fun readBackByName(sources: List<String>): Set<String> = sources.flatMapTo(sortedSetOf()) { text ->
        LOOK_UPS.flatMap { it.findAll(text).map { found -> found.groupValues[1] } }
    }

    private fun nestedEnums(sources: List<String>): Set<String> =
        sources.flatMapTo(HashSet()) { text -> NESTED_ENUM.findAll(text).map { it.groupValues[1] } }

    /**
     * The constants of the enum [type] of this package, or null where there is none. Off the
     * fields, which does not run the class's initialiser: an enum that holds an icon or a colour
     * need not be built to be named.
     */
    private fun namesOf(type: String): List<String>? =
        runCatching { Class.forName("com.pwnall.health.$type", false, javaClass.classLoader) }.getOrNull()
            ?.takeIf { it.isEnum }
            ?.declaredFields?.filter { it.isEnumConstant }?.map { it.name }

    private fun parse(text: String): Map<String, List<String>> = text.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { it.substringBefore(':') to it.substringAfter(':').split(' ').filter(String::isNotEmpty) }

    private fun render(names: Map<String, List<String>>): String = names.toSortedMap().entries
        .joinToString("") { (type, constants) -> "$type: ${constants.joinToString(" ")}\n" }

    private companion object {
        val LOOK_UPS = listOf(
            Regex("""\b([A-Z]\w*)\.entries\b[^\n]*?\bname\b"""),
            Regex("""\b([A-Z]\w*)\.valueOf\("""),
            Regex("""\benum\w*<([A-Z]\w*)>"""),
        )
        val NESTED_ENUM = Regex("""(?m)^[ \t]+(?:(?:internal|private|public)\s+)?enum class (\w+)""")

        val HEADER = """
            # Every enum whose constants are read back from their names, with the names: what a journal
            # row's payload, a column, a backup, the preferences or a saved screen may hold.
            # StoredEnumNamesTest keeps this file to the code, and writes the list as the code has it
            # now to app/build/stored-enum-names.txt each time it runs.
            #
            # A name the test reports lost was renamed or removed. Whatever was written under it now
            # reads as nothing or as a default, silently. Either put the name back, or carry what was
            # written to the new name (a Room migration for a column or a payload, a reading of both
            # names for a backup), and only then change the line here. A type that never outlives the
            # process, such as the page of a screen, needs the line changed and nothing more.
            # A name reported gained is a new constant: add it, so that it is held from now on.

        """.trimIndent() + "\n"
    }
}

/**
 * What differs between the names [kept] in the list and the ones the code has [now], a line for
 * each difference and none when they agree. A rename is a loss and a gain, and the loss is the one
 * that costs: nothing reads the old name any more.
 */
internal fun storedNameChanges(kept: Map<String, List<String>>, now: Map<String, List<String>>): List<String> =
    (kept.keys + now.keys).sorted().flatMap { type ->
        val before = kept[type]
        val after = now[type]
        when {
            before == null -> listOf("$type is read back by name and not listed: ${after.orEmpty().joinToString()}")

            after == null -> listOf("$type is listed and no longer read back by name")

            else -> listOfNotNull(
                (before - after.toSet()).takeIf { it.isNotEmpty() }?.joinToString()
                    ?.let { "$type lost $it: what was written under that name now reads as nothing" },
                (after - before.toSet()).takeIf { it.isNotEmpty() }?.joinToString()
                    ?.let { "$type gained $it: list them" },
            )
        }
    }
