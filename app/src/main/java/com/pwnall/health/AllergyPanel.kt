package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * One row of an allergy panel: an allergen by the code laboratories share for it — f13 for peanut,
 * d2 for the house dust mite D. farinae, fx10 for a mix — the name the sheet printed beside it, the
 * specific IgE in kU/L, and its class.
 *
 * [below] marks a figure printed under the assay's floor, "<0.15": [kuL] is then that floor and not a
 * measurement. The class is the sheet's own where it printed one, else the standard scale's
 * ([igeClassOf]).
 */
internal data class AllergenResult(
    val code: String,
    val name: String,
    val kuL: Double,
    val below: Boolean,
    val igeClass: Int,
) {
    /**
     * Class 1 and up, 0.35 kU/L and more: sensitised. It is not an allergy — a positive in somebody who
     * eats the food without trouble is common — so nothing here calls it one.
     */
    val positive: Boolean get() = igeClass >= 1
}

/**
 * The class a figure falls in on the scale ImmunoCAP made common and Polycheck and EUROLINE print
 * too: under 0.35 kU/L is 0, then 0.7, 3.5, 17.5, 50 and 100 open classes 1 to 6.
 */
internal fun igeClassOf(kuL: Double, below: Boolean): Int =
    if (below) 0 else CLASS_FLOORS.count { kuL >= it }

private val CLASS_FLOORS = listOf(0.35, 0.7, 3.5, 17.5, 50.0, 100.0)

/**
 * The allergen rows on a page of OCR boxes: a line opening with an allergen code, and to its right
 * the figure — with a decimal point or a "<" — and, where the sheet prints one, the class as a lone
 * digit. A line with a code and no figure is a footnote spelling out a mix ("fx10 f4 / f5 / f6") and
 * is not a row. The first row of a code wins, so a legend repeating it cannot overwrite it.
 */
internal fun readAllergyPanel(page: List<TextBox>): List<AllergenResult> {
    val rows = page.rowsOfText().mapNotNull(::allergenRow).distinctBy { it.code }
    val cyrillic = rows.count { row -> row.name.any(::isCyrillic) } * 2 > rows.size
    return rows.map { it.copy(name = cyrillicWords(it.name, cyrillic)) }
}

@Suppress("ReturnCount") // Each step either finds its part of the row or rejects the line.
private fun allergenRow(row: List<TextBox>): AllergenResult? {
    val at = row.indexOfFirst { allergenCode(it.text.trim().substringBefore(' ')) != null }
    if (at < 0) return null
    val head = row[at].text.trim()
    val code = allergenCode(head.substringBefore(' ')) ?: return null
    val rest = row.drop(at + 1).map { it.text.trim() }
    // T3, T4, D3, E2, C3 and C4 read as allergen codes and are the names of ordinary tests too, which a
    // panel's file can carry from the same visit. An allergen's reference never reaches past the line
    // class 1 opens at; theirs does, "Т4 свободный 15.2 9.0-19.0", and that turns the row away.
    if (rest.any(::isTestReference)) return null
    val name = head.substringAfter(' ', "").trim()
        .ifEmpty { rest.firstOrNull { cell -> cell.any(Char::isLetter) && cell.none(Char::isDigit) }.orEmpty() }
    val figure = rest.firstNotNullOfOrNull(::igeFigure) ?: return null
    val printedClass = rest.firstOrNull { it.length == 1 && it[0] in '0'..'6' }?.toInt()
    val (kuL, below) = figure
    return AllergenResult(code, name.replace(SPACES, " "), kuL, below, printedClass ?: igeClassOf(kuL, below))
}

/**
 * A word with a Cyrillic letter in it written in Cyrillic throughout: the Cyrillic pass hands back
 * "kлiщ" for "кліщ", taking the letters both alphabets share for Latin. On a [panel] whose names are
 * mostly Cyrillic, a word made only of those shared letters is Cyrillic too — "apaxic" is "арахіс" —
 * while a word with a letter only Latin has stays Latin: "Alternaria", "D. farinae".
 */
private fun cyrillicWords(name: String, panel: Boolean): String = name.split(' ').joinToString(" ") { word ->
    val twins = word.any(Char::isLetter) && word.filter(Char::isLetter).all { it in CYRILLIC_TWINS }
    if (word.any(::isCyrillic) || (panel && twins)) word.map { CYRILLIC_TWINS[it] ?: it }.joinToString("") else word
}

private fun isCyrillic(char: Char): Boolean = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.CYRILLIC

private val CYRILLIC_TWINS = mapOf(
    'a' to 'а', 'c' to 'с', 'e' to 'е', 'i' to 'і', 'k' to 'к', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
    'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'I' to 'І', 'K' to 'К', 'M' to 'М', 'O' to 'О',
    'P' to 'Р', 'T' to 'Т', 'X' to 'Х',
)

/**
 * A figure in kU/L: "0.36", "<0.15", ">100", the comma decimal, and Polycheck's "0 .36", which prints
 * a gap before the point. A bare whole number is a figure only above the classes' 0–6, so a class is
 * never read as one.
 */
private fun igeFigure(cell: String): Pair<Double, Boolean>? {
    val text = cell.replace(',', '.').replace(GAPPED_POINT, "$1.")
    val hit = IGE_FIGURE.matchEntire(text) ?: return null
    val sign = hit.groupValues[1]
    val value = hit.groupValues[2].toDoubleOrNull() ?: return null
    val whole = '.' !in hit.groupValues[2]
    if (sign.isEmpty() && whole && value <= MAX_CLASS) return null
    return value to (sign == "<" || sign == "≤" || sign == "<=")
}

/** A cell that is a reference range, "9.0-19.0", whose top lies past an allergen's 0.35. */
private fun isTestReference(cell: String): Boolean =
    TEST_RANGE.matchEntire(cell.replace(',', '.'))?.groupValues?.get(1)?.toDoubleOrNull()
        ?.let { it > CLASS_FLOORS.first() } == true

private val TEST_RANGE = Regex("""$SPACE*[0-9]+(?:\.[0-9]+)?$SPACE*[-–—]$SPACE*([0-9]+(?:\.[0-9]+)?)$SPACE*""")
private val IGE_FIGURE = Regex("""$SPACE*(<=|>=|<|>|≤|≥)?$SPACE*([0-9]+(?:\.[0-9]+)?)$SPACE*""")
private val GAPPED_POINT = Regex("""([0-9])$SPACE+\.""")
private val SPACES = Regex("""$SPACE+""")
private const val MAX_CLASS = 6.0

/**
 * The token as an allergen code, written the one way the app keeps it — lower case, numbers without
 * leading zeros, "e2/e5" for a pair — or null. Cyrillic letters that look Latin are read as Latin,
 * because the Cyrillic OCR pass hands "е02" back for "e02". Only the families the codes use: f food,
 * d mites, e animals, g grasses, t trees, w weeds, m moulds, i insects, k occupational, c drugs,
 * h house dust, o other, p parasites, each with its "x" mixes.
 */
internal fun allergenCode(token: String): String? {
    val folded = token.lowercase().map { LOOKALIKES[it] ?: it }.joinToString("").trimEnd('.', ',', ':')
    if (!CODE.matches(folded)) return null
    val parts = folded.split('/').map { part -> part.takeWhile(Char::isLetter) to part.dropWhile(Char::isLetter) }
    if (parts.any { (letters, _) -> letters.removeSuffix("x") !in FAMILIES }) return null
    return parts.joinToString("/") { (letters, number) -> letters + number.trimStart('0').ifEmpty { "0" } }
}

private val CODE = Regex("""[a-z]{1,2}[0-9]{1,4}(?:/[a-z]{1,2}[0-9]{1,4})*""")
private val FAMILIES = setOf("f", "d", "e", "g", "t", "w", "m", "i", "k", "c", "h", "o", "p")
private val LOOKALIKES = mapOf(
    'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'к' to 'k', 'м' to 'm', 'т' to 't',
    'і' to 'i', 'н' to 'h',
)

/**
 * The food allergens of the profile's fourteen that a single-allergen code names, for offering — never
 * writing — a positive row to the person's own answer. Mixes are left out: an "fx10" is wheat, rye,
 * barley and oats on one maker's panel and something else on another's. Sulphites have no IgE test.
 */
internal fun AllergenResult.foodAllergen(): Allergen? = FOOD_CODES.entries.firstOrNull { code in it.value }?.key

private val FOOD_CODES: Map<Allergen, Set<String>> = mapOf(
    Allergen.MILK to setOf("f2", "f76", "f77", "f78"),
    Allergen.EGGS to setOf("f1", "f75", "f245"),
    Allergen.FISH to setOf("f3", "f40", "f41", "f61", "f204", "f205", "f206"),
    Allergen.CRUSTACEANS to setOf("f23", "f24", "f80"),
    Allergen.MOLLUSCS to setOf("f37", "f58", "f207", "f290", "f338"),
    Allergen.PEANUTS to setOf("f13", "f352", "f422", "f423", "f424", "f427"),
    Allergen.SOY to setOf("f14"),
    Allergen.TREE_NUTS to setOf("f17", "f18", "f20", "f201", "f202", "f203", "f256", "f345"),
    Allergen.GLUTEN to setOf("f4", "f5", "f6", "f7", "f79"),
    Allergen.CELERY to setOf("f85"),
    Allergen.MUSTARD to setOf("f89"),
    Allergen.SESAME to setOf("f10"),
    Allergen.LUPIN to setOf("f335"),
)

/** A panel read off one page: its rows and the day the page says it was drawn. */
internal data class AllergyPanelScan(val rows: List<AllergenResult> = emptyList(), val day: LocalDate? = null)

/**
 * The page read once per OCR script, keeping the pass that found more rows, and of two that found as
 * many, the one whose names hold more letters — the Latin pass reads a Cyrillic name as punctuation.
 */
internal fun bestAllergyPanel(passes: List<AllergyPanelScan>): AllergyPanelScan =
    passes.maxWithOrNull(
        compareBy({ it.rows.size }, { scan -> scan.rows.sumOf { row -> row.name.count(Char::isLetter) } }),
    ) ?: AllergyPanelScan()

/** The key the rows travel under in a lab row's payload. */
internal const val ALLERGY_PANEL_KEY = "allergyPanel"

/**
 * An allergy panel saved: a lab row like a document's — its file on the shelf of sheets and in the
 * doctor's archive — that also carries every row of the table, so the panel can be shown and
 * reported without reading the file again.
 */
internal fun allergyPanelEntry(
    title: String,
    file: String,
    day: LocalDate,
    note: String,
    rows: List<AllergenResult>,
): JournalEntry {
    val entry = labDocumentEntry(AttachmentKind.LAB, title, file, day, note)
    val payload = JSONObject(entry.measurementPayloadJson.orEmpty()).put(ALLERGY_PANEL_KEY, rowsJson(rows))
    return entry.copy(measurementPayloadJson = payload.toString())
}

private fun rowsJson(rows: List<AllergenResult>): JSONArray = JSONArray(
    rows.map { row ->
        JSONObject().put("code", row.code).put("name", row.name).put("kuL", row.kuL)
            .put("below", row.below).put("class", row.igeClass)
    },
)

private fun rowsOf(rows: JSONArray): List<AllergenResult> =
    (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let(::storedAllergen) }

/** Rows as text, for a page to hold them across a rotation the way it holds its other answers. */
internal fun encodeAllergenRows(rows: List<AllergenResult>): String = rowsJson(rows).toString()

internal fun decodeAllergenRows(text: String): List<AllergenResult> =
    runCatching { rowsOf(JSONArray(text)) }.getOrDefault(emptyList())

/** The panel a lab row carries, empty for every other row; a row it cannot read is dropped. */
internal fun JournalEntry.allergyPanel(): List<AllergenResult> {
    if (kind != KIND_LABS) return emptyList()
    val rows = runCatching { JSONObject(measurementPayloadJson.orEmpty()).optJSONArray(ALLERGY_PANEL_KEY) }
        .getOrNull() ?: return emptyList()
    return rowsOf(rows)
}

/**
 * The figure as the sheet printed it, "<0.15" or "0.36", without the zeros a double adds, and with
 * the decimal mark of [locale]: "0,36" on a screen read in Russian or German, where every other
 * figure has a comma. The doctor's sheet keeps the point, as [pretty] does, and asks with no locale.
 */
internal fun AllergenResult.figureText(locale: Locale = Locale.ROOT): String {
    val figure = BigDecimal.valueOf(kuL).stripTrailingZeros()
    return (if (below) "<" else "") + String.format(locale, "%.${figure.scale().coerceAtLeast(0)}f", figure)
}

private fun storedAllergen(row: JSONObject): AllergenResult? {
    val code = allergenCode(row.optString("code")) ?: return null
    val kuL = row.optDouble("kuL").takeIf { it.isFinite() && it >= 0 } ?: return null
    val below = row.optBoolean("below")
    val igeClass = row.optInt("class", igeClassOf(kuL, below)).coerceIn(0, MAX_CLASS.toInt())
    return AllergenResult(code, row.optString("name"), kuL, below, igeClass)
}
