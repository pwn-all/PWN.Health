package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ln

/**
 * One figure read off a lab sheet: the marker, the value in the unit the marker is stored in, and
 * the laboratory's own range converted the same way.
 *
 * [printedUnit] is the UCUM spelling of the unit the sheet printed, where it is one of the two the
 * marker's field offers — so the form can show the figure exactly as the sheet printed it.
 */
internal data class SheetReading(
    val marker: LabMarker,
    val value: Double,
    val range: ReportedRange = ReportedRange(),
    val printedUnit: String? = null,
)

/**
 * What a sheet was read as.
 *
 * [unread] counts the markers the app keeps that the sheet names but whose figure could not be read
 * with certainty — no unit and no range to tell mg/dL from mmol/L, two figures on one row, a figure
 * the marker cannot take. Those are left for the person to type rather than guessed at: a wrong
 * number in a medical record costs more than an empty field.
 */
internal data class LabSheetScan(
    val readings: List<SheetReading> = emptyList(),
    val day: LocalDate? = null,
    val veterinary: Boolean = false,
    val unread: Int = 0,
)

/**
 * Whether this reading of a page is the one to keep over [other]: more figures, then more markers
 * named and left unread, then a day where the other has none. Without the last, a page that is all
 * heading — the patient, "Collected 13.01.2025", no table — would come back with no day: no pass
 * that reads the date reads more figures than the empty scan the comparison starts from. The table on
 * the page after it prints no date and takes the one its neighbour gives ([labImportDrafts]), so it
 * would be dated today instead.
 */
internal fun LabSheetScan.betterThan(other: LabSheetScan): Boolean = when {
    readings.size != other.readings.size -> readings.size > other.readings.size
    unread != other.unread -> unread > other.unread
    else -> day != null && other.day == null
}

/**
 * Whether a page's turns can stop at this reading, the best so far with the page turned by [degrees].
 *
 * Figures settle it at any turn: only print standing the right way up gives a name, its figure and
 * its unit. A day alone settles it upright and nowhere else: otherwise an upright page that is all
 * heading would be turned three more times and read six more times for nothing, and a pass over the
 * turned page that took a marker's name out of the noise could replace the reading that held the
 * date ([betterThan]). A day read off a turned page is weaker evidence — a few digits survive a half
 * turn — so the ladder runs on past it to the turn that may hold the table.
 */
internal fun LabSheetScan.settlesPage(degrees: Int): Boolean = readings.isNotEmpty() || (degrees == 0 && day != null)

/**
 * The lab results on a page of OCR boxes.
 *
 * A laboratory prints a result as a name, a figure, a unit and a range, in whatever order and over
 * however many lines its layout likes: a table row, a card with the range on the line below, a grid
 * of two cards a row, a German sheet with the range before the figure and the long name under it.
 * So the reader does not trust columns. It finds the names it knows ([nameNear]) at the start of a
 * cell, and reads each name's figure from the cells to its right, else from the lines under it up
 * to the next name, keeping to the name's own column where a row holds two.
 *
 * Every figure then has to convince: its unit must be one its marker is written in, the value one
 * the marker can hold, and the printed range one a laboratory could print for it. A figure with no
 * unit is only taken when its range says which unit it was. [monthFirst] settles 03/04 where the
 * sheet itself never does.
 */
internal fun scanLabSheet(page: List<TextBox>, today: LocalDate, monthFirst: Boolean): LabSheetScan {
    val rows = sheetRows(page)
    val anchors = anchorsOf(rows)
    if (veterinary(rows) || anchors.any { it.name.mcv && animalMcv(it, rows, anchors) }) {
        return LabSheetScan(veterinary = true)
    }
    val headers = dateHeaders(rows, anchors, today, monthFirst)
    val named = anchors.filter { !it.ratio }.mapNotNull { it.marker }.toSet()
    val read = anchors.mapNotNull { anchor ->
        anchor.marker?.takeIf { !anchor.ratio }?.let { marker ->
            candidateFor(anchor, rows, anchors, headers)?.let { settle(marker, it) }
        }
    }
    val kept = read.groupBy { it.marker }.mapNotNull { (_, same) ->
        same.first().takeIf { first -> same.all { abs(it.value - first.value) <= first.value * SAME_FIGURE } }
    }
    return LabSheetScan(
        readings = kept,
        day = headers.lastOrNull()?.dates?.maxOrNull() ?: sheetDay(rows, today, monthFirst),
        unread = (named - kept.map { it.marker }.toSet()).count { !it.derived },
    )
}

/** Two readings of one marker are one when they agree to a thousandth. */
private const val SAME_FIGURE = 0.001

/** One OCR box, cleaned, and where it sits on the page. */
private class Cell(val text: String, val x0: Int, val x1: Int, val height: Int) {
    val centre: Int get() = (x0 + x1) / 2
}

/** A printed line of cells, left to right, and whether the sheet had turned to urine by it. */
private class SheetRow(val cells: List<Cell>, val urine: Boolean) {
    val text: String get() = cells.joinToString(" ") { it.text }
}

/**
 * A name the dictionary knows, where it opens a cell, and the stretch of the page that is its own:
 * from its cell to the next name on the same line.
 */
private class Anchor(
    val row: Int,
    val cell: Int,
    val name: SheetName,
    val marker: LabMarker?,
    /** What the name's own cell holds after the name — a figure, when the OCR merged the two. */
    val tail: String,
    /** One half of a ratio ("Cholesterol/HDL", "LDL/HDL Ratio"): its figure is not the marker's. */
    val ratio: Boolean,
    val from: Int,
    val until: Int,
)

/**
 * The page as printed lines ([rowsOfText]), each cell cleaned and each line told whether it sits
 * under a urinalysis heading. Words the detector split apart are joined back ("Non HDL" and
 * "Cholesterol" are one name), and a range broken over two lines ("0.44 -" over "1.00") is closed up.
 */
private fun sheetRows(page: List<TextBox>): List<SheetRow> {
    val comma = decimalComma(page)
    val lines = page.rowsOfText().map { line ->
        line.joinedWords().map { Cell(cleanFigures(it.text, comma), it.box.x0, it.box.x1, it.box.y1 - it.box.y0) }
    }
    var urine = false
    return closeWrappedRanges(lines).map { cells ->
        sectionOf(cells.joinToString(" ") { it.text })?.let { urine = it }
        SheetRow(cells, urine)
    }
}

/**
 * Boxes closer than half a line's height are one phrase the detector happened to split. Boxes that
 * start in the same place are two lines the row assembly stacked into one, and stay apart.
 */
private fun List<TextBox>.joinedWords(): List<TextBox> {
    val joined = mutableListOf<TextBox>()
    for (box in this) {
        val last = joined.lastOrNull()
        val height = minOf(box.box.y1 - box.box.y0, last?.let { it.box.y1 - it.box.y0 } ?: 0)
        val beside = last != null && box.box.x0 > (last.box.x0 + last.box.x1) / 2
        if (last != null && beside && box.box.x0 - last.box.x1 < height * WORD_GAP) {
            val bounds = Box(last.box.x0, minOf(last.box.y0, box.box.y0), box.box.x1, maxOf(last.box.y1, box.box.y1))
            joined[joined.lastIndex] = TextBox(bounds, "${last.text} ${box.text}")
        } else {
            joined += box
        }
    }
    return joined
}

private const val WORD_GAP = 0.5

/** A range whose second edge went to the next line takes it back from the cell under it. */
private fun closeWrappedRanges(lines: List<List<Cell>>): List<List<Cell>> {
    val rows = lines.map { it.toMutableList() }
    rows.forEachIndexed { index, row ->
        val next = rows.getOrNull(index + 1) ?: return@forEachIndexed
        row.indices.filter { DANGLING_RANGE.containsMatchIn(row[it].text) }.forEach { at ->
            val open = row[at]
            val rest = next.firstOrNull {
                it.x0 < open.x1 && it.x1 > open.x0 - open.height &&
                    LEADING_FIGURE.containsMatchIn(it.text)
            }
            if (rest != null) {
                row[at] = Cell("${open.text} ${rest.text}", open.x0, maxOf(open.x1, rest.x1), open.height)
                next.remove(rest)
            }
        }
    }
    return rows
}

private val DANGLING_RANGE = Regex("""[0-9]$SPACE*[-~]$SPACE*$""")
private val LEADING_FIGURE = Regex("""^$SPACE*[0-9]""")

/**
 * Whether this sheet writes its decimals with a comma: Turkish, German, Russian and most of Europe
 * print 5,58 where an English sheet prints 5.58. Decided once for the whole sheet by which of the
 * two the figures use more, so a US sheet's "1,016" stays one thousand and sixteen. A "0,012" counts
 * for the comma, since no sheet groups the thousands of a zero; an Indian "2,51,000" does not.
 */
private fun decimalComma(page: List<TextBox>): Boolean {
    val text = page.joinToString(" ") { it.text }.replace(NUMERIC_DATE, " ")
    return COMMA_DECIMAL.findAll(text).count() > DOT_DECIMAL.findAll(text).count()
}

private val COMMA_DECIMAL = Regex("""[0-9],[0-9]{1,2}(?![0-9]|,[0-9])|(?<![0-9])0,[0-9]""")
private val DOT_DECIMAL = Regex("""[0-9]\.[0-9]""")

/**
 * A cell's figures written the one way the parser reads: decimal points, no thousands separators,
 * a leading zero on ".91", "29. 8" closed up, dot leaders gone, fullwidth digits folded.
 */
private fun cleanFigures(raw: String, comma: Boolean): String {
    var text = raw.map { char -> if (char in '！'..'～') char - FULLWIDTH_OFFSET else char }.joinToString("")
        .replace('−', '-').replace('–', '-').replace('—', '-')
        .replace(DOT_LEADERS, " ")
    text = if (comma) {
        text.replace(DECIMAL_COMMA, "$1.$2")
    } else {
        text.replace(THOUSANDS) { it.value.replace(",", "") }.replace(DECIMAL_COMMA, "$1.$2")
    }
    return text.replace(SPACED_DECIMAL, "$1.$2").replace(BARE_DECIMAL, "$10.$2").trim()
}

private const val FULLWIDTH_OFFSET = 0xFEE0
private val DOT_LEADERS = Regex("""\.{2,}|…+""")
private val DECIMAL_COMMA = Regex("""([0-9]),([0-9])""")

/**
 * A figure grouped by thousands — "251,000", "1,234,567" — or the Indian way, by lakhs: "2,51,000" on
 * a count printed per cumm. Never one that opens with a lone zero: "0,012" is a decimal.
 */
private val THOUSANDS = Regex(
    """(?<![0-9.,])(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]?(?:,[0-9]{2})+,[0-9]{3})(?![0-9.,])""",
)
private val SPACED_DECIMAL = Regex("""([0-9])\.$SPACE+([0-9])""")
private val BARE_DECIMAL = Regex("""(^|[^0-9.])\.([0-9])""")

/**
 * Whether a heading turns the sheet to urine (true) or back to blood (false), or neither. A line
 * with a figure on it is a result, never a heading; and the words are whole words, because a
 * urinalysis prints "Blood" and "Haemoglobin" as dipstick rows of its own.
 */
private fun sectionOf(text: String): Boolean? {
    if (text.any(Char::isDigit)) return null
    val tokens = nameTokens(text).map { it.trimEnd(Char::isDigit) }
    return when {
        tokens.any { it in URINE_HEADINGS } -> true
        tokens.any { it in BLOOD_HEADINGS } -> false
        else -> null
    }
}

private val URINE_HEADINGS = setOf(
    "urinalysis", "urine", "urin", "urines", "urinary", "urinanalyse", "urinalyse", "urinstatus", "harn",
    "harnstatus", "harnbefund", "моча", "мочи", "сеча", "сечі", "orina", "urina", "idrar", "mocz", "moczu", "moc",
    "moci", "尿液", "尿検査", "尿常规", "소변", "소변검사",
)
private val BLOOD_HEADINGS = setOf(
    "hematology", "haematology", "hematologie", "hamatologie", "hematologia", "ematologia", "hematoloji",
    "гематология", "chemistry", "biochemistry", "chemie", "biochemie", "biochimie", "bioquimica", "biochimica",
    "biyokimya", "биохимия", "haem", "chem", "cbc", "lipid", "lipids", "metabolic", "serum", "plasma", "blutbild",
    "кровь", "крови", "кров", "крові", "血液", "生化", "혈액",
)

/** Every name that opens a cell, each with the stretch of its line that is its own. */
private fun anchorsOf(rows: List<SheetRow>): List<Anchor> = rows.flatMapIndexed { index, row ->
    val found = row.cells.mapIndexedNotNull { cell, box ->
        val match = anchorIn(box.text, row.urine)
            ?: wrappedName(rows, index, cell)?.let { anchorIn(it, row.urine) }
        match?.let { cell to it }
    }
    found.mapIndexed { order, (cell, match) ->
        val next = found.getOrNull(order + 1)?.let { row.cells[it.first].x0 } ?: Int.MAX_VALUE
        Anchor(
            row = index,
            cell = cell,
            name = match.name,
            marker = match.marker,
            tail = match.tail,
            ratio = match.ratio,
            from = row.cells[cell].x0,
            until = next,
        )
    }
}

/**
 * A name that wrapped onto the next line ("Alkaline" over "phosphatase"): the first cell of a line,
 * words only, joined with the words-only cell starting under it.
 */
private fun wrappedName(rows: List<SheetRow>, row: Int, cell: Int): String? {
    val head = rows[row].cells[cell].takeIf { cell == 0 && it.text.none(Char::isDigit) } ?: return null
    val under = rows.getOrNull(row + 1)?.cells?.firstOrNull() ?: return null
    return "${head.text} ${under.text}".takeIf {
        abs(under.x0 - head.x0) <= head.height &&
            under.text.none(Char::isDigit)
    }
}

/** A name at the head of a cell, the marker it resolves to on this sheet, and the cell's remainder. */
private class NameMatch(val name: SheetName, val marker: LabMarker?, val tail: String, val ratio: Boolean)

/**
 * The name at the head of a cell — or after the group a sheet files it under, "Фізико-хімічні
 * властивості: pH", whose words still say whether the row is urine's.
 */
private fun anchorIn(text: String, urineSection: Boolean): NameMatch? = nameMatch(text, urineSection)
    ?: text.substringAfterLast(':', "").takeIf(String::isNotBlank)?.let { name ->
        nameMatch(name, urineSection || nameTokens(text).any { it in URINE_WORDS })
    }

private fun nameMatch(text: String, urineSection: Boolean): NameMatch? {
    val tokens = nameTokens(text)
    if (tokens.isEmpty()) return null
    // "S-Kreatinin", "U-Albumin", a flag column merged into the name ("F HGB"), "1. Glucose".
    val skip = if (nameNear(tokens, 0) == null && tokens.size > 1 && tokens[0].skippablePrefix()) 1 else 0
    val (name, length) = nameNear(tokens, skip) ?: return null
    val rest = tokens.drop(skip + length)
    // Two names in one cell are two lines the OCR ran together, and neither's figure is certain. The
    // same name again is its abbreviation, "Aspartate Aminotransferase (AST/SGOT)", and is name too:
    // left in the tail, its letters would make the figure the OCR glued on after it read as prose.
    val again = nameAt(rest, 0)
    if (again != null && again.first !== name) return null
    val urine = urineSection || tokens.any { it in URINE_WORDS } || tokens.first() == "u"
    val marker = if (urine) name.urine else name.blood
    val tail = tailAfter(text, tokens.take(skip + length + (again?.second ?: 0)))
    return NameMatch(name, marker, tail, halfOfRatio(rest, marker))
}

/** What follows a name makes it one half of a ratio: "Cholesterol/HDL", "LDL/HDL Ratio". */
private fun halfOfRatio(rest: List<String>, marker: LabMarker?): Boolean = marker != LabMarker.URINE_ACR &&
    ((rest.firstOrNull() == "/" && !rest.drop(1).startsSameMarker(marker)) || rest.any { it in RATIO_WORDS })

private fun String.skippablePrefix(): Boolean =
    length == 1 || this in NAME_PREFIXES || (all(Char::isDigit) && length <= 2)

private val NAME_PREFIXES = setOf("serum", "plasma", "blood", "fs", "fp", "cb")

private fun List<String>.startsSameMarker(marker: LabMarker?): Boolean =
    firstOrNull() == "total" || (marker != null && nameAt(this, 0)?.first?.blood == marker)

private val URINE_WORDS = setOf(
    "urine", "urin", "urinary", "моча", "моче", "мочи", "сеча", "сечі", "harn", "orina", "urina", "idrar", "mocz",
    "moci", "moc", "尿", "尿液", "尿中", "소변",
)

private val RATIO_WORDS = setOf(
    "ratio", "quotient", "rapport", "cociente", "razon", "rapporto", "relacao", "verhaltnis", "oran", "orani",
    "stosunek", "pomer", "отношение", "соотношение", "коэффициент", "比", "비율",
)

/**
 * The cell's text after the name's tokens: found by walking the tokens through the cell folded the
 * way [nameTokens] folds it, so that "Hämoglobin 13,9" hands back its "13,9" however it was accented.
 * A fuzzy match ([nameNear]) is not walked, and its cell is all name.
 */
private fun tailAfter(text: String, tokens: List<String>): String {
    val folded = StringBuilder()
    val origin = ArrayList<Int>()
    text.forEachIndexed { index, char ->
        nameTokens(char.toString()).joinToString("").ifEmpty { if (char == '/') "/" else "" }.forEach {
            folded.append(it)
            origin.add(index)
        }
    }
    var at = 0
    for (token in tokens) {
        val found = folded.indexOf(token, at)
        if (found < 0) return ""
        at = found + token.length
    }
    return if (at == 0) text else text.substring(origin[at - 1] + 1)
}

/**
 * A veterinary report: its figures are an animal's, and neither the person's bands nor their record
 * has any business with them. Said by the words such a report prints.
 */
private fun veterinary(rows: List<SheetRow>): Boolean {
    val text = nameTokens(rows.joinToString(" ") { it.text }).joinToString(" ")
    return VETERINARY.any { " $it " in " $text " }
}

private val VETERINARY = listOf(
    "idexx", "vetconnect", "vetscan", "antech", "pet owner", "owner name", "species", "canine", "feline", "equine",
    "veterinary", "veterinarian", "tierarzt", "tierhalter", "tierart", "ветеринарная", "ветеринар",
    "владелец животного", "кличка", "veterinaire", "veterinario", "veteriner", "weterynarz", "獣医", "兽医", "수의사",
)

/**
 * The other tell of an animal's sheet: the laboratory's own range for the red cells' size. A human
 * one sits around 80–100 fL at every age; a dog's tops out in the 70s and a cat's in the 50s, so a
 * printed upper edge under 70 is not a person's.
 *
 * The figure beside the range has to be an animal's too. A person's 90.91 beside 38–48 is the
 * haematocrit's range read into the MCV row — PP-OCRv6's boxes can do exactly that on a Cyrillic
 * blood count — and believing the range alone would refuse the whole sheet as a dog's.
 */
private fun animalMcv(anchor: Anchor, rows: List<SheetRow>, anchors: List<Anchor>): Boolean {
    val candidate = candidateFor(anchor, rows, anchors, emptyList()) ?: return false
    val high = candidate.range?.second ?: return false
    return high < HUMAN_MCV_FLOOR && candidate.figure < HUMAN_MCV_FLOOR
}

private const val HUMAN_MCV_FLOOR = 70.0

/** What the cells around a name offer: its figure, the unit printed nearest it, and a range. */
private class Candidate(
    val figure: Double,
    val unit: String?,
    val range: Pair<Double?, Double?>?,
    /** One range of several, each for an age: it still tells the unit, but it is not this person's to show. */
    val stratified: Boolean = false,
) {
    val reported: Pair<Double?, Double?>? get() = range.takeUnless { stratified }
}

/** What one cell holds for the reader, after its dates, units and range are taken out. */
private class CellRead(
    val figure: Double?,
    val unit: String?,
    val range: Pair<Double?, Double?>?,
    /** Words that are neither a unit, a flag nor a label: a name, or prose. */
    val words: Boolean,
    val centre: Int,
    /** The cell says whom its range is for: "<1 року", "> or = 20 Years". */
    val forAnAge: Boolean = false,
)

private fun candidateFor(
    anchor: Anchor,
    rows: List<SheetRow>,
    anchors: List<Anchor>,
    headers: List<DateHeader>,
): Candidate? {
    val nameCell = rows[anchor.row].cells[anchor.cell]
    val own = rows[anchor.row].cells.withIndex()
        .filter { (index, cell) -> index > anchor.cell && cell.centre in anchor.from until anchor.until }
        .map { (_, cell) -> readCell(cell.text, cell.centre) }
    val first = listOfNotNull(readCell(anchor.tail, nameCell.centre).takeIf { anchor.tail.isNotBlank() }) + own
    val below = continuation(anchor, rows, anchors)
    val above = if (first.all { it.figure == null } && below.all { line -> line.all { it.figure == null } }) {
        borrowedFromAbove(anchor, rows, anchors)
    } else {
        emptyList()
    }
    val header = headers.lastOrNull { it.row < anchor.row }
    val chosen = figureOf(first + above, header) ?: figureBelow(below, header) ?: return null
    val cells = first + above + below.flatten()
    val rest = cells.filter { it !== chosen }
    val unit = chosen.unit ?: rest.firstNotNullOfOrNull { it.unit }
    // "Children <1 year: 100–140" over "1–6 years: 110–145": the first range printed is a baby's.
    val stratified = cells.count { it.range != null } > 1 && cells.any { it.forAnAge }
    val range = rest.firstNotNullOfOrNull { it.range } ?: chosen.range
    return Candidate(chosen.figure ?: return null, unit, range, stratified)
}

/**
 * The figure among the lines under a name: the first one printed with its unit, else the first at
 * all. A portal's chat bubble reads as a lone "3" above the "19 ug/L" it annotates.
 */
private fun figureBelow(lines: List<List<CellRead>>, header: DateHeader?): CellRead? {
    val figures = lines.mapNotNull { figureOf(it, header, below = true) }
    return figures.firstOrNull { it.unit != null } ?: figures.firstOrNull()
}

/**
 * The lines under a name that still belong to it: a card's figure and its "Reference Range: 3.8–10.8"
 * line, a range that wrapped. It stops at the next name in its column, at prose, at a code opening
 * the next line of a German sheet, and after three.
 */
@Suppress("LoopWithTooManyJumpStatements") // Stop at the next name; skip empty lines within the same card.
private fun continuation(anchor: Anchor, rows: List<SheetRow>, anchors: List<Anchor>): List<List<CellRead>> {
    val nameCell = rows[anchor.row].cells[anchor.cell]
    val lines = mutableListOf<List<CellRead>>()
    for (index in anchor.row + 1..minOf(rows.lastIndex, anchor.row + CONTINUATION_LINES)) {
        if (anchors.any { it.row == index && overlaps(it, anchor) }) break
        if (rows[index].cells.firstOrNull()?.let { it.x0 < nameCell.x1 && it.isCode() } == true) break
        val cells = rows[index].cells.filter { it.centre in anchor.from until anchor.until }
            .map { readCell(it.text, it.centre) }
        if (cells.any { it.words }) break
        lines += cells
    }
    return lines
}

private const val CONTINUATION_LINES = 3

private fun Cell.isCode(): Boolean = text.trim().let { code ->
    code.length in 1..CODE_LENGTH && code.all(Char::isLetter) && code.lowercase() !in LABELS
}

private fun overlaps(other: Anchor, anchor: Anchor): Boolean = other.from < anchor.until && anchor.from < other.until

/**
 * The line above, for a German sheet: it prints a code, the range and the figure on one line and
 * the analyte's full name under it ("KREA  <= 1.25 (mg/dl)  1.04" over "Kreatinin"). Only a line
 * whose name is a bare code the dictionary does not know, so no other name's figure is taken.
 */
private fun borrowedFromAbove(anchor: Anchor, rows: List<SheetRow>, anchors: List<Anchor>): List<CellRead> {
    val above = rows.getOrNull(anchor.row - 1) ?: return emptyList()
    if (above.cells.firstOrNull()?.isCode() != true || anchors.any { it.row == anchor.row - 1 }) return emptyList()
    return above.cells.drop(1).map { readCell(it.text, it.centre) }
}

private const val CODE_LENGTH = 6

/**
 * The one figure a line gives its name, or null. Where a line holds two — a cumulative sheet's
 * columns, a figure beside a location code — the one printed before the range is the result; on a
 * sheet that heads its columns with dates, only the one under the newest date is.
 */
@Suppress("ReturnCount") // Each alternative either resolves the row or rejects it immediately.
private fun figureOf(line: List<CellRead>, header: DateHeader?, below: Boolean = false): CellRead? {
    val figures = line.filter { it.figure != null }
    if (header != null) {
        val nearest = figures.minByOrNull { abs(it.centre - header.newestCentre) } ?: return null
        return nearest.takeIf { header.centres.minBy { centre -> abs(centre - it.centre) } == header.newestCentre }
    }
    if (figures.size <= 1) return figures.firstOrNull()
    // Under a card's name, the figure printed with its unit is the result and a lone figure beside it
    // is an icon. Not on the name's own line: a German sheet's "<= 60 (U/l)" that lost its "<=" reads
    // as a figure with a unit, and the result beside it has none.
    if (below) figures.singleOrNull { it.unit != null }?.let { return it }
    val rangeAt = line.indexOfFirst { it.range != null && it.figure == null }
    val before = figures.filter { rangeAt < 0 || line.indexOf(it) < rangeAt }
    val after = figures.filter { rangeAt >= 0 && line.indexOf(it) > rangeAt }
    return before.singleOrNull() ?: after.singleOrNull()?.takeIf { before.isEmpty() }
}

/** One cell, taken apart: its unit, its range, and the figure left standing if it is a clean one. */
private fun readCell(raw: String, centre: Int): CellRead {
    if (gauge(raw)) return CellRead(figure = null, unit = null, range = null, words = false, centre = centre)
    var text = withoutDates(latinUnits(raw.lowercase())).replace(OR_EQUAL, "$1=")
    val unitHit = UNIT_PATTERNS.asSequence().mapNotNull { (pattern, key) -> pattern.find(text)?.let { it to key } }
        .minByOrNull { (hit, _) -> hit.range.first }
    val unit = unitHit?.let { (hit, key) -> key.ifEmpty { countUnit(hit) } }
    UNIT_PATTERNS.forEach { (pattern, _) -> text = text.replace(pattern, " ") }
    val forAnAge = AGE.containsMatchIn(text)
    text = text.replace(AGE, " ")
    val range = rangeIn(text)
    range?.let { (hit, _) -> text = text.removeRange(hit.range).let { " $it " } }
    val tokens = text.split(NOT_TOKEN).filter(String::isNotEmpty)
    val figures = tokens.mapNotNull(::figureToken)
    val words = tokens.any { token ->
        figureToken(token) == null && token.count(Char::isLetter) >= 2 && token !in FLAGS && token !in LABELS
    }
    return CellRead(
        figure = figures.singleOrNull()?.takeIf { !words },
        unit = unit,
        range = range?.second,
        words = words && figures.isEmpty() && range == null && unit == null,
        centre = centre,
        forAnAge = forAnAge,
    )
}

private val NOT_TOKEN = Regex("""[^\p{L}\p{N}.-]+""")

/** A figure, or a figure with its flag glued on ("6L"), as one token; null for anything else. */
private fun figureToken(token: String): Double? = FIGURE_TOKEN.matchEntire(token)?.groupValues?.get(1)?.toDoubleOrNull()

private val FIGURE_TOKEN = Regex("""([0-9]+(?:\.[0-9]+)?)(?:h|l|hh|ll)?""")

/**
 * A rating drawn as a gauge beside the figure, "|  |*|  |" with a star where the result falls, as
 * Czech sheets print it. OCR hands it back as "*1", "1|1" or "1[!", its bars read as ones, and a
 * row holding the result and that "1" would be a row with two figures, left unread.
 */
private fun gauge(text: String): Boolean =
    text.any { it in GAUGE_MARKS } && text.all { it in GAUGE_GLYPHS || it.isWhitespace() }

private const val GAUGE_MARKS = "|*[]!"
private const val GAUGE_GLYPHS = "|*[]!1lIi一_"

private val FLAGS = setOf("hh", "ll", "high", "low", "abnormal", "critical", "normal", "abn", "ok", "neg")
private val LABELS = setOf(
    "reference", "range", "ranges", "ref", "standard", "your", "value", "values", "result", "results", "interval",
    "laboratory", "lab", "units", "unit", "flag", "final", "calc", "calculated", "show", "more", "see", "status",
    "norm", "normal", "current", "previous", "optimal", "desirable", "borderline", "near", "very", "ideal",
    "raised", "male", "female", "men", "women", "adult", "adults", "fasting", "non", "expected", "biological",
    "observed", "total", "of", "hgb", "норма", "референсные", "значения", "результат", "единицы", "ед", "изм",
    "normwert", "referenzbereich", "normbereich", "ergebnis", "einheit", "valeurs", "resultat", "valores",
    "referencia", "resultado", "unidad", "valori", "riferimento", "risultato", "wynik", "referencyjne", "vysledek",
    "referans", "aralik", "sonuc", "birim",
)

/** Dates and clock times, which carry figures that are never a result. */
private fun withoutDates(text: String): String = text.replace(NUMERIC_DATE, " ").replace(CLOCK, " ")
    .replace(WORD_DATE) { hit ->
        if (monthOf(hit.groupValues[2].ifEmpty { hit.groupValues[4] }) !=
            null
        ) {
            " "
        } else {
            hit.value
        }
    }

private val CLOCK = Regex("""[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?$SPACE*(?:am|pm)?""")
private val OR_EQUAL = Regex("""([<>])$SPACE*or$SPACE*=""")

/** "> or = 20 Years", "(M) 1-5 yrs": whom a range is for, which is never the range. */
private val AGE = Regex(
    """(?:[<>≤≥]=?|=)?$SPACE*[0-9]{1,3}$SPACE*""" +
        """(?:years?|yrs?|лет|года?|рок(?:и|у|ів)|рік|jahre?|ans|años|anos|anni|lat|let|yaş|yas)(?!\p{L})""",
)

private val TWO_SIDED = Regex(
    """([0-9]+(?:\.[0-9]+)?)$SPACE*(?:-|~|$SPACE(?:to|до|bis|a|à|ile)$SPACE)$SPACE*([0-9]+(?:\.[0-9]+)?)""",
)
private val ONE_SIDED = Regex(
    """(<=|>=|≤|≥|=<|=>|<|>|up$SPACE*to|до|bis|less$SPACE+than|below|under|above|over|greater$SPACE+than)""" +
        """$SPACE*=?$SPACE*([0-9]+(?:\.[0-9]+)?)""",
)

/**
 * The range a cell prints, and where in it. An edge that will not parse is no range, and is never
 * parsed by a call that throws. Every pattern in this file names its digits `[0-9]` rather than the
 * digit class, which on the phone is ICU's and takes every script's digits — the East Slavic
 * recogniser's dictionary holds a Devanagari zero — while toDouble takes ASCII alone, and its
 * exception would fail the whole file. The tests run on java.util.regex, where that class is ASCII,
 * so naming the digits keeps what the tests read the same as what the phone reads.
 */
private fun rangeIn(text: String): Pair<MatchResult, Pair<Double?, Double?>>? {
    TWO_SIDED.find(text)?.let { hit ->
        val low = hit.groupValues[1].toDoubleOrNull()
        val high = hit.groupValues[2].toDoubleOrNull()
        if (low != null && high != null && low <= high) return hit to (low to high)
    }
    val hit = ONE_SIDED.find(text) ?: return null
    val edge = hit.groupValues[2].toDoubleOrNull() ?: return null
    val below = UPPER_EDGE.any(hit.groupValues[1]::startsWith)
    return hit to if (below) null to edge else edge to null
}

/** The one-sided ranges that name a ceiling: "< 5", "up to 5", "до 5", "bis 5". */
private val UPPER_EDGE = listOf("<", "=<", "≤", "up", "до", "bis", "less", "below", "under")

/**
 * The count units, whose exponent decides which of two they are: ×10⁹/L and 10³/µL are one, 10¹²/L
 * and 10⁶/µL the other. An exponent the OCR lost ("×10/L") names a count of unknown size, which is
 * as good as no unit at all: the range has to say what it is.
 */
private fun countUnit(hit: MatchResult): String {
    val exponent = hit.groupValues[1].map { SUPERSCRIPTS.indexOf(it).takeIf { i -> i >= 0 }?.digitToChar() ?: it }
        .joinToString("").toIntOrNull()
    val perLitre = hit.groupValues[2].trim() == "l"
    return when {
        perLitre && exponent == GIGA -> "10^9/l"
        !perLitre && exponent == KILO -> "10^9/l"
        perLitre && exponent == TERA -> "10^12/l"
        !perLitre && exponent == MEGA -> "10^12/l"
        else -> "?"
    }
}

private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
private const val KILO = 3
private const val MEGA = 6
private const val GIGA = 9
private const val TERA = 12

/**
 * Russian units in Latin letters, so one set of patterns reads both: "мкмоль/л" is "umol/l". Insulin's
 * "мкЕд/мл" is µU/mL, and a count's "х10^9/л" may come with a Cyrillic "х", which as a letter before
 * the 10 would keep the count's pattern from opening and leave its 9 standing as a second figure.
 */
private fun latinUnits(text: String): String = CYRILLIC_UNIT.replace(text) { match ->
    CYRILLIC_UNIT_PARTS.fold(match.value) { unit, (cyrillic, latin) -> unit.replace(cyrillic, latin) }
}

private val CYRILLIC_UNIT = Regex("""[\p{IsCyrillic}0-9^*]*/[\p{IsCyrillic}0-9]+""")
private val CYRILLIC_UNIT_PARTS = listOf(
    "мкмоль" to "umol", "ммоль" to "mmol", "нмоль" to "nmol", "пмоль" to "pmol", "моль" to "mol", "мкме" to "uiu",
    "мкед" to "uiu", "мме" to "miu", "мед" to "mu", "мкг" to "ug", "мг" to "mg", "нг" to "ng", "пг" to "pg",
    "тыс" to "k", "млн" to "m", "ед" to "u", "ме" to "iu", "мкл" to "ul", "мл" to "ml", "дл" to "dl", "л" to "l",
    "г" to "g", "х" to "x",
)

/**
 * [pattern] as a cell may print it: a space in it stands for whatever gap OCR left between two parts of
 * a unit, or none — "m g / d l" — and is read as any number of [SPACE].
 */
private fun unit(pattern: String, key: String) =
    Regex("""(?<!\p{L})(?:${pattern.replace(" ", "$SPACE*")})(?!\p{L})""") to key

private const val LITRE = """[l1i|]"""
private const val MICRO = """(?:[uµμ]|mc|micro)"""
private const val PER_MICROLITRE = """(?:$MICRO $LITRE|mm 3|mm³|cumm|micro lit(?:er|re))"""

/**
 * Units a sheet prints, spelled the ways OCR hands them back, each to one key the marker table
 * reads. The first hit in the cell is its unit. An empty key is a count unit whose exponent
 * [countUnit] reads.
 *
 * A unit that may open with a digit — the "1" an "IU" comes back as, the 1000 of "×1000/µL" — does
 * not open inside a figure. "131 U/L" in one cell is 131: its last digit taken for the "I" would make a
 * normal 13 of it; and "251,000 /µL" is 251 thousand, not 25 of "1000/µL".
 */
private val UNIT_PATTERNS: List<Pair<Regex, String>> = listOf(
    unit(
        """(?:[x×*] )?(?:10 [\^*°ˆ˄]? |10 e |e )([1-9][0-9]?|[⁰¹²³⁴⁵⁶⁷⁸⁹]{1,2})""" +
            """ / (l|$PER_MICROLITRE)""",
        "",
    ),
    unit("""(?:[x×*] )?10 [\^*%°½ˆ˄]? / (?:l|$PER_MICROLITRE)""", "?"),
    unit("""\*? 1000000 / $PER_MICROLITRE""", "10^12/l"),
    unit("""\*? (?<![0-9.])1000 / $PER_MICROLITRE""", "10^9/l"),
    unit("""(?:k|thou(?:sand)?s?) / $PER_MICROLITRE""", "10^9/l"),
    unit("""(?:m|mill?(?:ion)?s?) / $PER_MICROLITRE""", "10^12/l"),
    unit("""/ n $LITRE""", "10^9/l"),
    unit("""/ p $LITRE""", "10^12/l"),
    unit("""(?:cells )?/ $PER_MICROLITRE""", "/ul"),
    unit("""m mol / mol""", "mmol/mol"),
    unit("""m g / m mol""", "mg/mmol"),
    unit("""m g / g(?: cr\p{L}*)?|$MICRO g / m g""", "mg/g"),
    unit("""m mo[l1i|]?e?s? / $LITRE""", "mmol/l"),
    unit("""$MICRO mo[l1i|]?e?s? / $LITRE""", "umol/l"),
    unit("""n mol / $LITRE""", "nmol/l"),
    unit("""p mol / $LITRE""", "pmol/l"),
    unit("""m [il1|]? u / $LITRE|m e / $LITRE""", "miu/l"),
    unit("""$MICRO [il1|]? u / m $LITRE|uiu / m $LITRE""", "miu/l"),
    unit("""$MICRO kat / $LITRE""", "ukat/l"),
    unit("""m eq / $LITRE""", "meq/l"),
    unit("""(?:(?:[il|]|(?<![0-9.])1) )?u / $LITRE|units? / $LITRE""", "u/l"),
    unit("""m g / d [l1i|t)]|m g %""", "mg/dl"),
    unit("""$MICRO g / d $LITRE""", "ug/dl"),
    unit("""n g / d $LITRE""", "ng/dl"),
    unit("""g(?:m|r)? / d [l1i|t)]|g / 100 m $LITRE|g %""", "g/dl"),
    unit("""$MICRO g / m $LITRE""", "mg/l"),
    unit("""$MICRO g / $LITRE""", "ug/l"),
    unit("""n g / m $LITRE""", "ug/l"),
    unit("""p g / m $LITRE""", "ng/l"),
    unit("""n g / $LITRE""", "ng/l"),
    unit("""m g / $LITRE""", "mg/l"),
    unit("""g / $LITRE""", "g/l"),
    unit("""m l / min(?: / 1[.,]?73 (?:m [²2]?)?)?""", "ml/min"),
    unit("""mm / h(?:r|our)?""", "mm/h"),
    unit("""f l""", "fl"),
    unit("""ratio""", "ratio"),
    unit("""%""", "%"),
)

/**
 * A figure settled into its marker: converted from the unit the sheet printed, and believed only if
 * the unit is the marker's, the value one it can hold, and the printed range one a laboratory could
 * print for it. With no unit, the range has to pick the unit out on its own, and clearly.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // Ordered unit and validity checks fail closed.
private fun settle(marker: LabMarker, candidate: Candidate): SheetReading? {
    if (marker.unitless) {
        // Specific gravity as a density, the way Czech sheets print it: 1023 kg/m³ is 1.023.
        val scale = if (marker == LabMarker.URINE_SG && candidate.figure >= MILLI) 1 / MILLI else 1.0
        return (candidate.figure * scale).takeIf { it in marker.valid }?.let { value ->
            SheetReading(marker, value, candidate.reported.toReported { it * scale })
        }
    }
    val units = marker.sheetUnits()
    val printed = candidate.unit?.takeIf { it != "?" }
    if (printed != null) {
        val unit = units[printed] ?: return null
        val value = unit.convert(candidate.figure).takeIf { it in marker.valid } ?: return null
        val score = candidate.range?.let { marker.rangeScore(it, unit) }
        if (score != null && score > PRINTED_RANGE_SLACK) return null
        return SheetReading(marker, value, candidate.reported.toReported(unit.convert), marker.fieldUcum(unit))
    }
    val range = candidate.range ?: return null
    val (inferable, printedOnly) = units.values.filter { it.convert(candidate.figure) in marker.valid }
        .distinctBy { it.factor to it.affine }
        .mapNotNull { unit -> marker.rangeScore(range, unit)?.let { unit to it } }
        .sortedBy { it.second }
        .partition { !it.first.explicitOnly }
    val (best, score) = inferable.firstOrNull() ?: return null
    val rival = inferable.getOrNull(1)?.second
    // A unit only ever believed when printed still gets a say: a Dutch haemoglobin's 7.3–9.5 fits
    // mmol/L far better than g/dL, so with its unit lost it is left alone rather than read as g/dL.
    val unprinted = printedOnly.firstOrNull()?.second
    if (score > INFERRED_RANGE_SLACK || (rival != null && rival - score < INFERRED_MARGIN)) return null
    if (unprinted != null && unprinted < score) return null
    val reported = candidate.reported.toReported(best.convert)
    return SheetReading(marker, best.convert(candidate.figure), reported, marker.fieldUcum(best))
}

/** How far a printed range may sit from the marker's band: four times either way, as a logarithm. */
private val PRINTED_RANGE_SLACK = ln(4.0)

/**
 * A range that is to name the unit on its own must sit within twice the band, and twice as close as
 * any other unit's reading of it. Not tighter: a laboratory's ALT range runs to 55 U/L where ACG's
 * ceiling, which the app grades by, is 33.
 */
private val INFERRED_RANGE_SLACK = ln(2.0)
private val INFERRED_MARGIN = ln(2.0)

private fun Pair<Double?, Double?>?.toReported(convert: (Double) -> Double): ReportedRange =
    this?.let { (low, high) -> ReportedRange(low?.let(convert), high?.let(convert)) } ?: ReportedRange()

/**
 * How far the printed range sits from the bands this app holds for the marker, as the worst edge's
 * log ratio; null when no edge compares — an ungraded marker, or a range of "0–5" against a band
 * with no floor. A zero floor is printed where a laboratory means "none" and says nothing.
 */
private fun LabMarker.rangeScore(range: Pair<Double?, Double?>, unit: SheetUnit): Double? {
    val low = range.first?.takeIf { it > 0 }?.let(unit.convert)
    val high = range.second?.let(unit.convert)
    return listOfNotNull(band, femaleBand, fedBand).mapNotNull { band ->
        listOfNotNull(
            low?.let { edge -> (band.low ?: band.veryLow)?.let { abs(ln(edge / it)) } },
            high?.let { edge -> (band.high ?: band.veryHigh)?.let { abs(ln(edge / it)) } },
        ).maxOrNull()
    }.minOrNull()
}

/** A unit a marker is printed in, and what takes a figure in it to the marker's stored unit. */
private class SheetUnit(val factor: Double, val explicitOnly: Boolean = false, val affine: Double = 0.0) {
    val convert: (Double) -> Double = { it * factor + affine }
}

/**
 * The UCUM spelling of [unit] where the marker's field offers it: its stored unit or its other one.
 * A unit with an offset is the field's other one where the two take the same figures to the same
 * stored ones: HbA1c's mmol/mol, which a sheet's 42 fills as 42 on its chip rather than as 5.99416%.
 */
private fun LabMarker.fieldUcum(unit: SheetUnit): String? = when {
    unit.affine != 0.0 -> otherUnit?.takeIf { it.undoes(unit) }?.ucum?.ucum
    unit.factor == 1.0 -> ucum.ucum
    otherUnit?.let { abs(unit.factor * it.factor - 1) < UNIT_MATCH } == true -> otherUnit?.ucum?.ucum
    else -> null
}

private const val UNIT_MATCH = 1e-6

/** Whether this field unit takes back what [unit] takes to the stored one: two figures settle a line. */
private fun LabImperial.undoes(unit: SheetUnit): Boolean =
    listOf(0.0, 1.0).all { abs(of(unit.convert(it)) - it) < UNIT_MATCH }

/**
 * The units each marker is printed in, keyed as [UNIT_PATTERNS] reads them. The second unit's factor
 * is the one the form converts with ([LabMarker.imperial], or [LabMarker.otherUnit] for CRP), so a
 * figure read here lands where the same figure typed would.
 *
 * [SheetUnit.explicitOnly] units are believed only when printed: a haemoglobin of 13.9 with no unit
 * fits g/dL and the Dutch mmol/L alike, and only the sheet can say.
 */
@Suppress("CyclomaticComplexMethod") // One declarative unit map per supported marker.
private fun LabMarker.sheetUnits(): Map<String, SheetUnit> {
    val second = (otherUnit ?: imperial)?.let { SheetUnit(1 / it.factor) }
    val stored = SheetUnit(1.0)
    return when (this) {
        LabMarker.GLUCOSE, LabMarker.CHOLESTEROL, LabMarker.LDL, LabMarker.HDL, LabMarker.TRIGLYCERIDES,
        LabMarker.MAGNESIUM, LabMarker.URINE_CREATININE,
        -> mapOf("mmol/l" to stored, "mg/dl" to second, "meq/l" to MAGNESIUM_MEQ.takeIf { this == LabMarker.MAGNESIUM })

        LabMarker.HBA1C -> mapOf("%" to stored, "mmol/mol" to IFCC_TO_NGSP)

        LabMarker.INSULIN, LabMarker.TSH -> mapOf("miu/l" to stored)

        LabMarker.LP_A -> mapOf("nmol/l" to stored)

        LabMarker.CREATININE, LabMarker.URIC_ACID ->
            mapOf("umol/l" to stored, "mg/dl" to second, "mmol/l" to SheetUnit(MILLI, explicitOnly = true))

        LabMarker.UREA -> mapOf("mmol/l" to stored)

        LabMarker.ALT, LabMarker.AST, LabMarker.ALP, LabMarker.GGT ->
            mapOf("u/l" to stored, "ukat/l" to SheetUnit(UNITS_PER_MICROKATAL, explicitOnly = true))

        LabMarker.BILIRUBIN -> mapOf("umol/l" to stored, "mg/dl" to second)

        LabMarker.PLATELETS ->
            mapOf("10^9/l" to stored, "g/l" to stored, "/ul" to SheetUnit(1 / MILLI, explicitOnly = true))

        LabMarker.HEMOGLOBIN ->
            mapOf("g/l" to stored, "g/dl" to second, "mmol/l" to SheetUnit(HB_G_PER_MMOL, explicitOnly = true))

        LabMarker.FERRITIN -> mapOf("ug/l" to stored)

        LabMarker.TSAT -> mapOf("%" to stored)

        LabMarker.CRP, LabMarker.URINE_ALBUMIN -> mapOf("mg/l" to stored, "mg/dl" to second)

        LabMarker.FT4 -> mapOf("pmol/l" to stored, "ng/dl" to second)

        LabMarker.B12 -> mapOf("pmol/l" to stored, "ng/l" to second)

        LabMarker.VITAMIN_D, LabMarker.FOLATE -> mapOf("nmol/l" to stored, "ug/l" to second)

        LabMarker.URINE_ACR -> mapOf("mg/mmol" to stored, "mg/g" to second)

        LabMarker.URINE_PROTEIN ->
            mapOf("g/l" to stored, "mg/dl" to second, "mg/l" to SheetUnit(1 / MILLI, explicitOnly = true))

        LabMarker.HOMA_IR, LabMarker.NON_HDL, LabMarker.EGFR, LabMarker.URINE_SG, LabMarker.URINE_PH -> emptyMap()
    }.mapNotNull { (key, unit) -> unit?.let { key to it } }.toMap()
}

private const val MILLI = 1000.0
private const val UNITS_PER_MICROKATAL = 60.0

/** Haemoglobin's molar mass per haem, 16 114.5 g/mol: the Dutch mmol/L to the g/L the band is written in. */
private const val HB_G_PER_MMOL = 16.1145

/** Magnesium is divalent: one mEq/L is half a mmol/L. */
private val MAGNESIUM_MEQ = SheetUnit(0.5, explicitOnly = true)

/** The IFCC–NGSP master equation (Hoelzel et al. 2004, Clin Chem): NGSP % = 0.09148 × IFCC + 2.152. */
private val IFCC_TO_NGSP = SheetUnit(NGSP_PERCENT_PER_IFCC, affine = NGSP_PERCENT_AT_IFCC_ZERO)

/**
 * A row that heads a cumulative sheet's columns with dates ("CMP  10/31/15  10/26/16"), and where
 * each date sits across the page: the newest date's column is the one read.
 */
private class DateHeader(val row: Int, val dates: List<LocalDate>, val centres: List<Int>, val newestCentre: Int)

private fun dateHeaders(
    rows: List<SheetRow>,
    anchors: List<Anchor>,
    today: LocalDate,
    monthFirst: Boolean,
): List<DateHeader> {
    val order = dayOrder(rows, monthFirst)
    return rows.mapIndexedNotNull { index, row ->
        if (anchors.any { it.row == index }) return@mapIndexedNotNull null
        // A column's heading is its date alone: "Date Coll: 16/Sep/2024" beside "Date Rec: 17/Sep/2024"
        // are labels, the sheet's own dates, and the newest of them is not a column to read.
        val dated = row.cells.filter { cell -> withoutDates(cell.text.lowercase()).none(Char::isLetter) }
            .mapNotNull { cell -> datesIn(cell.text, order, today).maxOrNull()?.let { it to cell.centre } }
        dated.takeIf { it.size >= 2 }?.let {
            DateHeader(
                index,
                it.map { (date, _) ->
                    date
                },
                it.map { (_, centre) -> centre },
                it.maxBy { (date, _) -> date }.second,
            )
        }
    }
}

/**
 * The day the sample was drawn: the earliest date the sheet prints within a year of its newest, so a
 * report printed the day after the draw is dated by the draw, and a birth date decades earlier —
 * whether or not it says "DOB" — is not taken for it.
 */
private fun sheetDay(rows: List<SheetRow>, today: LocalDate, monthFirst: Boolean): LocalDate? {
    val order = dayOrder(rows, monthFirst)
    val dates = rows.flatMap { datesIn(it.text, order, today) }
    val newest = dates.maxOrNull() ?: return null
    return dates.filter { ChronoUnit.DAYS.between(it, newest) <= SAME_VISIT_DAYS }.minOrNull()
}

private const val SAME_VISIT_DAYS = 366L

/**
 * Whether this sheet's slashed and dashed dates put the month first. A date with a first part over
 * 12 says they cannot; one with a second part over 12 says they must; a sheet that never says is read
 * the way the phone's own region writes dates ([localeMonthFirst]).
 */
private fun dayOrder(rows: List<SheetRow>, monthFirst: Boolean): Boolean {
    val dates = rows.flatMap { DAY_AND_MONTH.findAll(it.text).toList() }
    val dayFirst = dates.any { it.groupValues[1].toInt() in DAY_ONLY }
    val monthOnly = dates.any { it.groupValues[3].toInt() in DAY_ONLY }
    return when {
        dayFirst && !monthOnly -> false
        monthOnly && !dayFirst -> true
        else -> monthFirst
    }
}

/** A part that can only be the day: over twelve, and a day at all — a telephone's "12-34-56" names none. */
private val DAY_ONLY = 13..31

/**
 * A slashed or dashed date with its year last and its separator the same both times, as
 * [NUMERIC_DATE] reads one — and the year a year, so that a sample number shaped like a date
 * ("25-11-0042") says nothing about the order the sheet's dates are written in.
 */
private val DAY_AND_MONTH =
    Regex(
        """(?<![0-9])([0-9]{1,2})$SPACE*([/-])$SPACE*([0-9]{1,2})$SPACE*\2$SPACE*""" +
            """(?:(?:19|20)[0-9]{2}|[0-9]{2})(?![0-9])""",
    )

/**
 * The dates printed in a line, skipping a birth date, and never one after [today] or from before the
 * years the form's date picker offers and the results page reads ([labWindowStart]): a day the reader
 * took and the form could not hold would be saved where the results page never shows it.
 */
private fun datesIn(text: String, monthFirst: Boolean, today: LocalDate): List<LocalDate> {
    val lower = text.lowercase()
    val oldest = labWindowStart(today)
    val found = mutableListOf<Pair<Int, LocalDate?>>()
    NUMERIC_DATE.findAll(lower).forEach { hit -> found += hit.range.first to numericDate(hit, monthFirst, today) }
    WORD_DATE.findAll(lower).forEach { hit -> found += hit.range.first to wordDate(hit.groupValues, today) }
    return found.filter { (at, _) -> BIRTH.none { lower.substring(maxOf(0, at - BIRTH_REACH), at).contains(it) } }
        .mapNotNull { it.second }
        .filter { it <= today && it.toString() >= oldest }
}

/**
 * A year, ending where its digits do — or a four-digit year with the clock welded straight on:
 * PP-OCRv6 reads "Collected: 01/13/2025 7:30 am" as "01/13/20257:30", and without this the draw
 * date would be lost and the report's completion a day later would date the sheet.
 */
private const val YEAR = """(?:19|20)[0-9]{2}(?=[0-9]{1,2}:[0-9]{2})|[0-9]{2,4}(?![0-9.])"""

/** A numeric date, its separator the same both times — so a range like "11.1 - 15.9" is never one. */
private val NUMERIC_DATE =
    Regex("""(?<![0-9.])([0-9]{1,4})$SPACE*([./-])$SPACE*([0-9]{1,2})$SPACE*\2$SPACE*($YEAR)""")

/** "16 Sep 2024", "16-Sep-2024", the UAE's "16/Sep/2024", and "Sep 16, 2024". */
private val WORD_DATE = Regex(
    """(?<![0-9])([0-9]{1,2})[${SPACE_CHARS}/-]+(\p{L}{3,9})\.?,?[${SPACE_CHARS}/-]+([0-9]{4}|[0-9]{2})(?![0-9])|""" +
        """(\p{L}{3,9})\.?$SPACE+([0-9]{1,2}),?$SPACE+([0-9]{4})(?![0-9])""",
)
private val BIRTH =
    listOf("dob", "birth", "born", "рожд", "geb", "naiss", "nacim", "nascit", "doğum", "dogum", "urodz", "narozen", "生")
private const val BIRTH_REACH = 24

private fun numericDate(hit: MatchResult, monthFirst: Boolean, today: LocalDate): LocalDate? {
    val first = hit.groupValues[1]
    val separator = hit.groupValues[2]
    val second = hit.groupValues[3]
    val third = hit.groupValues[4]
    if (first.length == 4) return dateOf(first.toInt(), second.toInt(), third.toInt())
    val year = fullYear(third, today)
    // A dotted date is day first, whatever the phone's region. A dashed one is read as a slashed one
    // is, by the sheet's own dates and then the region ([dayOrder]): an American sheet prints
    // 03-04-2025 for the fourth of March, so a dash is not day first on every phone.
    val american = separator != "." && monthFirst
    val preferred = if (american) {
        dateOf(
            year,
            first.toInt(),
            second.toInt(),
        )
    } else {
        dateOf(year, second.toInt(), first.toInt())
    }
    val other = if (american) {
        dateOf(
            year,
            second.toInt(),
            first.toInt(),
        )
    } else {
        dateOf(year, first.toInt(), second.toInt())
    }
    return preferred?.takeIf { it <= today } ?: other?.takeIf { separator != "." && it <= today } ?: preferred
}

private fun wordDate(groups: List<String>, today: LocalDate): LocalDate? {
    val dayFirst = groups[1].isNotEmpty()
    val day = (if (dayFirst) groups[1] else groups[5]).toInt()
    val month = monthOf(if (dayFirst) groups[2] else groups[4]) ?: return null
    val year = fullYear(if (dayFirst) groups[3] else groups[6], today)
    return dateOf(year, month, day)
}

private fun fullYear(printed: String, today: LocalDate): Int {
    val year = printed.toInt()
    if (printed.length > 2) return year
    return if (year <= today.year % CENTURY) CURRENT_CENTURY + year else CURRENT_CENTURY - CENTURY + year
}

private const val CENTURY = 100
private const val CURRENT_CENTURY = 2000

private fun dateOf(year: Int, month: Int, day: Int): LocalDate? = runCatching {
    LocalDate.of(year, month, day)
}.getOrNull()

/** A month named in any of the app's Latin- or Cyrillic-script languages, by its first letters. */
private fun monthOf(word: String): Int? {
    val folded = nameTokens(word).firstOrNull() ?: return null
    return MONTH_NAMES.indexOfFirst { names -> names.any { folded.startsWith(it) } }.takeIf { it >= 0 }?.plus(1)
}

private val MONTH_NAMES = listOf(
    listOf("jan", "янв", "ene", "gen", "oca", "sty", "led"),
    listOf("feb", "фев", "fev", "sub", "lut", "uno"),
    listOf("mar", "мар", "bre"),
    listOf("apr", "апр", "avr", "abr", "nis", "kwi", "dub"),
    listOf("may", "мая", "май", "mai", "mag", "maj", "kve"),
    listOf("jun", "июн", "juin", "giu", "haz", "cze", "cer"),
    listOf("jul", "июл", "juil", "lug", "tem", "lip", "cvc"),
    listOf("aug", "авг", "aou", "ago", "agu", "sie", "srp"),
    listOf("sep", "сен", "set", "eyl", "wrz", "zar"),
    listOf("oct", "окт", "okt", "ott", "out", "eki", "paz", "rij"),
    listOf("nov", "ноя", "kas", "lis"),
    listOf("dec", "дек", "dez", "dic", "ara", "gru", "pro"),
)
