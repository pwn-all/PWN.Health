package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The vitamin table on the doctor's sheet, and the lines a course that says what one dose holds adds
 * to the medicines.
 *
 * Two things are held here that the table itself cannot hold. The section reads only the halves the
 * person ticked — no food without the food section, no doses without the medicines. And nothing it
 * can print claims a cause, grades a person, or tells anybody what to do: every string it can reach
 * is read out of the resources and searched for the words that would.
 */
class ReportMicronutrientsTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val from: LocalDate = today.minusDays(29)
    private val zone: ZoneId = ZoneOffset.UTC
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1986-03-01")

    private fun at(day: LocalDate): Long = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun oranges(count: Int = 14): List<JournalEntry> = (0 until count).flatMap { back ->
        val day = today.minusDays(back.toLong())
        List(2) {
            JournalEntry(
                day = day.toString(),
                occurredAt = at(day),
                kind = "EAT",
                title = "Orange",
                calories = 47.0,
                foodSource = "orange=100.0",
            )
        }
    }

    private fun course(contents: String, id: Long = 1, name: String = "Supplement") = Medication(
        id = id,
        name = name,
        dose = "1 tablet",
        times = "480",
        startDay = from.toString(),
        contents = contents,
        createdAt = 0,
    )

    private fun marks(course: Medication, count: Int): List<JournalEntry> = (0 until count).map { back ->
        val day = today.minusDays(back.toLong())
        medTakenEntry(plannedDoses(listOf(course), emptyList(), day).first(), day, at(day))
    }

    private val everythingButSensitive: Set<ReportPart> =
        ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet()

    private fun section(
        entries: List<JournalEntry>,
        medications: List<Medication>,
        parts: Set<ReportPart> = everythingButSensitive,
        profile: UserProfile = man,
    ): ReportSection? = micronutrientSection(
        ReportSources(profile, entries, emptyList(), medications),
        ReportRequest(from = from, to = today, parts = parts),
        today,
        24 * 60,
        zone,
    )

    private fun report(entries: List<JournalEntry>, medications: List<Medication>) = doctorReport(
        sources = ReportSources(man, entries, emptyList(), medications),
        request = ReportRequest(from = from, to = today),
        today = today,
        zone = zone,
    )

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun ReportSection.labels(): List<ReportText> = rows.map(ReportRow::label)

    @Test
    fun theSectionReadsBothHalvesWhereBothAreTicked() {
        val iron = course("IRON=65")
        val built = section(oranges() + marks(iron, 30), listOf(iron))!!

        assertEquals(ReportPart.MICRONUTRIENTS, built.part)
        assertTrue(res(R.string.nutrient_from_food) in built.labels())
        assertTrue(res(R.string.nutrient_from_supplements) in built.labels())
        assertEquals(R.string.report_caveat_micronutrients, built.caveatRes)
        // The food's days against the period's, as the food section counts its own.
        assertEquals(Completeness(14, 30, 28), built.completeness)
        assertEquals(listOf(Provenance(ProvenanceCategory.MANUAL)), built.provenance)
    }

    /**
     * Only the meals whose figures are in the table are named: not another app's meal on a day with
     * no second one, nor one of its meals the catalogue has no figure for — until one of its meals
     * carries a figure on a day that was counted.
     */
    @Test
    fun theSourcesAreThoseOfTheMealsTheTableCounted() {
        fun imported(day: LocalDate, title: String, source: String?) = JournalEntry(
            day = day.toString(),
            occurredAt = at(day),
            kind = "EAT",
            title = title,
            calories = 300.0,
            foodSource = source,
            measurementSource = "HEALTH_CONNECT",
        )
        val lone = imported(from, "Orange", "orange=100.0")
        val unknown = imported(today, "Typed at home", null)
        val manual = listOf(Provenance(ProvenanceCategory.MANUAL))

        assertEquals(manual, section(oranges() + lone + unknown, emptyList())!!.provenance)
        val counted = imported(today, "Orange", "orange=100.0")
        assertEquals(
            listOf(ProvenanceCategory.MANUAL, ProvenanceCategory.HEALTH_CONNECT).sorted().map { Provenance(it) },
            section(oranges() + counted, emptyList())!!.provenance,
        )
    }

    /** No food section on the sheet, no food in the table: the courses' nutrients and nothing else. */
    @Test
    fun withoutTheFoodSectionThereIsNoFoodColumn() {
        val iron = course("IRON=65")
        val built = section(oranges() + marks(iron, 30), listOf(iron), everythingButSensitive - ReportPart.NUTRITION)!!

        assertFalse(res(R.string.nutrient_from_food) in built.labels())
        assertEquals(listOf(res(Nutrient.IRON.titleRes), res(R.string.nutrient_from_supplements)), built.labels())
        assertEquals(R.string.report_caveat_micronutrients_supplements, built.caveatRes)
        assertNull(built.completeness)
    }

    @Test
    fun withoutTheMedicinesThereIsNoSupplementColumn() {
        val iron = course("IRON=65")
        val parts = everythingButSensitive - ReportPart.MEDICATIONS
        val built = section(oranges() + marks(iron, 30), listOf(iron), parts)!!

        assertFalse(res(R.string.nutrient_from_supplements) in built.labels())
        assertEquals(R.string.report_caveat_micronutrients_food, built.caveatRes)
        // Nor does the course's iron lift the verdict it was not allowed to be read for.
        val ironLine = built.rows.first { it.label == res(Nutrient.IRON.titleRes) }
        assertEquals(res(R.string.nutrient_verdict_cannot_say), (ironLine.note as ReportText.Join).parts.first())
    }

    @Test
    fun neitherHalfTickedOrNothingKnownIsNoSection() {
        val iron = course("IRON=65")
        val entries = oranges() + marks(iron, 30)

        assertNull(section(entries, listOf(iron), setOf(ReportPart.MICRONUTRIENTS, ReportPart.LABS)))
        assertNull(section(emptyList(), emptyList()))
        assertFalse(ReportPart.MICRONUTRIENTS in report(emptyList(), emptyList()).filled)
        assertTrue(ReportPart.MICRONUTRIENTS in report(entries, listOf(iron)).filled)
    }

    /** A course asked for and never ticked is "no dose marked" over its days — not "0 mg", not "0%". */
    @Test
    fun anUnmarkedDoseIsWordsNotANought() {
        val built = section(oranges(), listOf(course("IRON=65")))!!
        val doses = built.rows.single { it.label == res(R.string.nutrient_from_supplements) }

        assertEquals(res(R.string.nutrient_supplement_none_marked), doses.value)
        assertEquals(ReportText.Quantity(R.plurals.nutrient_days_with_dose, 30), doses.note)
    }

    @Test
    fun theFoodLineCarriesItsCountsAndSaysAtLeastWhereAMealIsUnknown() {
        val full = section(oranges(), emptyList())!!
        val food = full.rows.first { it.label == res(R.string.nutrient_from_food) }

        val days = res(R.string.report_count_of, 14, ReportText.Quantity(R.plurals.count_days, 14))
        assertEquals(res(R.string.nutrient_food_coverage, 100, days), food.note)
        // Vitamin C's food line: every meal an orange and every orange known, so a plain mean.
        val vitaminC = full.rows[full.rows.indexOfFirst { it.label == res(Nutrient.VITAMIN_C.titleRes) } + 1]
        assertEquals(R.string.report_per_day_rate, (vitaminC.value as ReportText.Res).id)

        val typed = oranges() + (0 until 14).map { back ->
            val day = today.minusDays(back.toLong())
            JournalEntry(day = day.toString(), occurredAt = at(day), kind = "EAT", title = "Typed", calories = 600.0)
        }
        val partial = section(typed, emptyList())!!
        val floor = partial.rows[partial.rows.indexOfFirst { it.label == res(Nutrient.VITAMIN_C.titleRes) } + 1]
        assertEquals(R.string.nutrient_at_least_per_day, (floor.value as ReportText.Res).id)
        // The note stands on the first food line and again only where it changes: every nutrient here
        // is known for the same share of the same days, so vitamin C's line carries none of its own.
        val first = partial.rows.first { it.label == res(R.string.nutrient_from_food) }
        val floorOnly = ReportText.Quantity(R.plurals.nutrient_floor_only, FOOD_MICROS_MIN_DAYS)
        assertTrue(floorOnly in (first.note as ReportText.Join).parts)
        assertNull(floor.note)
    }

    /** "In one dose", then each nutrient's marked figure a day over the days that asked for one. */
    @Test
    fun aCourseSaysWhatOneDoseHoldsAndWhatTheMarkedDosesCameTo() {
        val d3 = course("VITAMIN_D=50;MAGNESIUM=200", id = 7, name = "D3 and magnesium")
        val plain = course("", id = 8, name = "Metformin")
        val medicines = report(marks(d3, 10), listOf(d3, plain)).sections.single { it.part == ReportPart.MEDICATIONS }

        val held = medicines.rows.single { it.label == res(R.string.report_course_contents) }
        assertEquals(
            ReportText.Join(
                listOf(
                    res(
                        R.string.report_value_unit,
                        res(Nutrient.VITAMIN_D.titleRes),
                        res(R.string.report_value_unit, "50", res(R.string.unit_micrograms)),
                    ),
                    res(
                        R.string.report_value_unit,
                        res(Nutrient.MAGNESIUM.titleRes),
                        res(R.string.report_value_unit, "200", res(R.string.unit_milligrams)),
                    ),
                ),
            ),
            held.value,
        )
        val vitaminD = medicines.rows.single { it.label == res(Nutrient.VITAMIN_D.titleRes) }
        // Ten doses of fifty over thirty days that asked for one.
        assertEquals(
            res(R.string.report_course_marked, res(R.string.report_value_unit, "17", res(R.string.unit_micrograms))),
            vitaminD.value,
        )
        assertEquals(
            ReportText.Join(
                listOf(
                    ReportText.Quantity(R.plurals.nutrient_days_with_dose, 30),
                    res(R.string.nutrient_marked_share, 33),
                ),
            ),
            vitaminD.note,
        )
        // The course without a label adds nothing: its name, its dates and its adherence, as for any course.
        val adherence = medicines.rows.count { it.label == res(R.string.report_adherence) }
        assertEquals(1, medicines.rows.count { it.label == res(R.string.report_course_contents) })
        assertEquals(2 + 2 + 1 + 2, medicines.rows.size - adherence)
    }

    /**
     * A period that ended yesterday, a sheet made at ten in the morning, and an evening dose left
     * unmarked on the last day: that day is over, so the adherence row asks for the dose, and the
     * course's own lines count the same doses rather than one fewer.
     */
    @Test
    fun aCoursesLinesCountTheDosesItsAdherenceRowCounts() {
        val evening = course("MAGNESIUM=200", id = 5, name = "Magnesium").copy(times = "1260")
        val end = today.minusDays(1)
        val medicines = doctorReport(
            sources = ReportSources(man, marks(evening, 12).drop(2), emptyList(), listOf(evening)),
            request = ReportRequest(from = from, to = end),
            today = today,
            nowMinute = 600,
            zone = zone,
        ).sections.single { it.part == ReportPart.MEDICATIONS }

        val adherence = medicines.rows.single { it.label == res(R.string.report_adherence) }
        assertEquals(res(R.string.report_doses_marked, 10, 29), adherence.note)
        val magnesium = medicines.rows.single { it.label == res(Nutrient.MAGNESIUM.titleRes) }
        assertEquals(
            ReportText.Quantity(R.plurals.nutrient_days_with_dose, 29),
            (magnesium.note as ReportText.Join).parts.first(),
        )
    }

    // --- what the words may say ---------------------------------------------------------------

    /**
     * Every sentence the table, the page and the medicines lines can reach, from fixtures that walk
     * every state — each verdict, an upper level passed, no daily figure, the extras, an unmarked
     * course, each half alone — read in English and searched for a cause, a grade, a prediction or
     * advice. The table's own strings are searched as well, whether or not a fixture reached them.
     */
    @Test
    fun nothingTheTableCanPrintClaimsACauseGradesOrAdvises() {
        val reached = everyTextTheTableCanReach().flatMap(::resources).toSet()
        // Every state the fixtures were meant to walk, walked.
        listOf(
            R.string.nutrient_verdict_at_or_above,
            R.string.nutrient_verdict_below,
            R.string.nutrient_verdict_cannot_say,
            R.string.nutrient_verdict_above_upper,
            R.string.nutrient_no_reference,
            R.string.nutrient_supplement_none_marked,
            R.string.nutrient_food_no_figures,
            R.string.nutrient_upper_preformed,
            R.string.nutrient_upper_folic_acid,
            R.string.nutrient_upper_supplements,
            R.string.nutrient_upper_added,
        ).forEach { assertTrue(name(it), it in reached) }

        val english = File("src/main/res/values/ui.xml").readText()
        val names = reached.map(::name) + NEW_NAME.findAll(english).map { it.groupValues[1] }
            .filter { name -> OWN_PREFIXES.any(name::startsWith) }
        names.distinct().forEach { name ->
            val text = englishText(english, name)
            assertNotNull("no English text for $name", text)
            val found = FORBIDDEN.find(text!!)
            assertNull("$name says \"${found?.value}\": $text", found)
        }
    }

    /**
     * The fixtures: one course past three upper levels, one holding the four extras, one never
     * marked, liver and oranges every day — read with both halves, each alone, and for a child.
     */
    private fun everyTextTheTableCanReach(): List<ReportText> {
        val iron = course("IRON=65;MAGNESIUM=400;FOLIC_ACID=1200;VITAMIN_A=4000", id = 1)
        val extras = course("BIOTIN=5000;IODINE=150;OMEGA3=300;CREATINE=5", id = 2)
        val courses = listOf(iron, extras, course("VITAMIN_D=25", id = 3))
        val liver = oranges().map { it.copy(title = "Liver", calories = 135.0, foodSource = "beef_liver=100.0") }
        val entries = oranges() + liver + marks(iron, 30) + marks(extras, 10)
        fun MutableList<ReportText>.addRows(rows: List<ReportRow>) =
            rows.forEach { addAll(listOfNotNull(it.label, it.value, it.note)) }
        return buildList {
            listOf(
                everythingButSensitive,
                everythingButSensitive - ReportPart.NUTRITION,
                everythingButSensitive - ReportPart.MEDICATIONS,
            ).forEach { parts ->
                listOf(man, man.copy(birthDate = "2012-01-01")).forEach { profile ->
                    section(entries, courses, parts, profile)?.let { built ->
                        addRows(built.rows)
                        add(res(built.caveatRes!!))
                    }
                }
            }
            addRows(section(oranges(), emptyList())!!.rows)
            addRows(report(entries, courses).sections.single { it.part == ReportPart.MEDICATIONS }.rows)
            val table = nutrientTable(
                entries,
                courses,
                man,
                from,
                today,
                today,
                24 * 60,
                zone,
                includeFood = true,
                includeSupplements = true,
            )
            add(foodLoggedText(table))
        }
    }

    /** Every string and plural a text reaches, arguments included. */
    private fun resources(text: Any): List<Int> = when (text) {
        is ReportText.Res -> listOf(text.id) + text.args.flatMap(::resources)
        is ReportText.Quantity -> listOf(text.id)
        is ReportText.Join -> text.parts.flatMap(::resources)
        else -> emptyList()
    }

    private val ids: Map<Int, String> by lazy {
        (R.string::class.java.fields + R.plurals::class.java.fields).associate { it.getInt(null) to it.name }
    }

    private fun name(id: Int): String = ids.getValue(id)

    private fun englishText(xml: String, name: String): String? = listOf(
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL),
    ).firstNotNullOfOrNull { it.find(xml)?.groupValues?.get(1) }

    private companion object {
        val NEW_NAME = Regex("""<(?:string|plurals) name="([a-z0-9_]+)"""")

        /** The strings of this table, searched whether or not a fixture reached them. */
        val OWN_PREFIXES = listOf("nutrient_", "vitamins_", "report_caveat_micronutrients", "report_course_")

        /** The house list: causes, grades, forecasts, advice, and the words for what the app does not measure. */
        val FORBIDDEN = Regex(
            """\b(because|due to|caused?|led to|resulted in|improved|worsened|better|worse|helped|harmed|""" +
                """effects?|impacts?|linked to|associated with|risks?|predicts?|likely|suggests?|indicates?|""" +
                """significant\w*|abnormal|deficien\w*|stress\w*|libido|dehydrated|fast food|junk|unhealthy|""" +
                """consider\w*|check\w*|retest\w*|get tested|reduc\w*|increas\w*|stop\w*|ask about)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
