package com.pwnall.health

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The places of care the appointment form can suggest, shipped inside the app as
 * `assets/facilities/facilities.csv` and searched on the phone.
 *
 * Every row comes out of an official register or open data — Czechia's NRPZS (ÚZIS), Ukraine's
 * NSZU contracts on data.gov.ua, and in the Emirates the Dubai register of December 2023, the
 * Ministry of Health and Prevention's open data (Abu Dhabi's licensed places, Emirates Health
 * Services) and the Bayanat portal's lists — and nothing was added by hand. The page's "?" names
 * the source of every one of them, by the prefix of its rows' ids: [FACILITY_SOURCES].
 *
 * The file is pipe-separated with the header `id|country|name|lat|lon|types|city|address|name_alt`,
 * and the header is read rather than assumed, so a file rebuilt with its columns moved still loads.
 * `name_alt` is the register's own second spelling of the name — Dubai's register names every place
 * in English and in Arabic — and may be empty or missing.
 *
 * [id] is the register's own identifier with the country in front ("CZ:180966"). It is the key a
 * list of doctors would hang off, which is why it is kept even though the screen never shows
 * it: a doctor belongs to a place, and a place's name is not unique across a country.
 */
const val FACILITIES_ASSET = "facilities/facilities.csv"

/**
 * The kinds of care a place offers, with the ids the asset's `types` column uses.
 *
 * The registers do not share a vocabulary — a Czech "obor péče", a Ukrainian NSZU service
 * package, a Dubai licence category — so each was sorted into these thirty when the file was
 * built. A place carries every kind it was licensed or contracted for at that address.
 */
enum class CareType(val id: Int) {
    DENTISTRY(1),
    GYNAECOLOGY(2),
    PRIMARY_CARE(3),
    PAEDIATRICS(4),
    CARDIOLOGY(5),
    DERMATOLOGY(6),
    OPHTHALMOLOGY(7),
    ENT(8),
    ORTHOPAEDICS(9),
    NEUROLOGY(10),
    PSYCHIATRY(11),
    PSYCHOLOGY(12),
    UROLOGY(13),
    ENDOCRINOLOGY(14),
    GASTROENTEROLOGY(15),
    EMERGENCY(16),
    HOSPITAL(17),
    LABORATORY(18),
    IMAGING(19),
    REHABILITATION(20),
    SURGERY(21),
    ONCOLOGY(22),
    ALLERGOLOGY(23),
    PULMONOLOGY(24),
    NEPHROLOGY(25),
    RHEUMATOLOGY(26),
    INFECTIOUS_DISEASES(27),
    NUTRITION(28),
    SPORTS_MEDICINE(29),
    SLEEP_MEDICINE(30),
    ;

    /** This kind's bit in [Facility.types]. Thirty ids fit an Int with the sign bit to spare. */
    val bit: Int get() = 1 shl id
}

/** A point on the ground in WGS-84 degrees, which is what both the registers and Android give. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * One place of care, as the register describes it.
 *
 * [types] is a bit set of [CareType.bit] rather than a set of enums: forty thousand rows each
 * holding a small set is forty thousand objects kept for nothing. [nameKey], [altKey] and
 * [placeKey] are [foldForSearch] of the name, the second name and the city with the address,
 * worked out once at load so a keystroke compares plain strings instead of normalising the whole
 * list again.
 *
 * [nameAlt] is searched but not shown: the row is printed and filled in under [name], the one
 * spelling every register has, so a place reads the same whichever script found it.
 */
class Facility(
    val id: String,
    val country: String,
    val name: String,
    val point: GeoPoint,
    val types: Int,
    val city: String,
    val address: String,
    val nameAlt: String = "",
) {
    val nameKey: String = foldForSearch(name)
    val altKey: String = foldForSearch(nameAlt)
    val placeKey: String = foldForSearch("$city $address")

    /** True when this place offers any of [kinds]; an empty set asks for no kind and so is always met. */
    fun offers(kinds: Set<CareType>): Boolean = kinds.isEmpty() || kinds.any { types and it.bit != 0 }

    /**
     * Where the place is, as a line under its name: the address, and the city after it when the
     * address does not already say it. A Czech or Ukrainian register writes the town into the
     * address and printing it twice is noise; Dubai's addresses stop at the district.
     */
    val where: String
        get() = when {
            address.isBlank() -> city
            city.isBlank() || foldForSearch(city) in foldForSearch(address) -> address
            else -> "$address, $city"
        }

    /** What the appointment's place is filled with when this one is picked: the name, then where it is. */
    val placeText: String get() = listOf(name, where).filter(String::isNotBlank).joinToString(", ")
}

/**
 * How long an appointment's place may be typed. A place picked from the list is its name with its
 * address, and a Ukrainian one runs to some 950 characters with the legal entity after the
 * department; the 60 a doctor's name is held to would drop the address on the first key pressed
 * to correct a letter in it. A test holds every place on the shipped list under this.
 */
const val PLACE_MAX_CHARS = 1_000

/** A place as a suggestion: the place, and how far it is from the person when that is known. */
data class FacilityHit(val facility: Facility, val metres: Double?)

/**
 * The asset's lines as places, header first. A line that does not have the header's number of
 * fields, or whose coordinates are not numbers inside the world, is left out rather than guessed
 * at: every row on this list is meant to be one somebody checked, and a half-read row is not.
 * A file whose header lacks a column this needs reads as no places at all.
 */
fun parseFacilities(lines: Sequence<String>): List<Facility> {
    val iterator = lines.iterator()
    val header = iterator.takeIf { it.hasNext() }?.next()
        ?.removePrefix(BYTE_ORDER_MARK)?.split('|')?.map(String::trim)
        .orEmpty()
    val column = FACILITY_COLUMNS.associateWith(header::indexOf)
    if (column.values.any { it < 0 }) return emptyList()
    val withAlt = column + (NAME_ALT_COLUMN to header.indexOf(NAME_ALT_COLUMN))
    return iterator.asSequence().mapNotNull { facilityOf(it.split('|'), header.size, withAlt) }.toList()
}

/** One line's [fields] as a place, or null for a line [parseFacilities] leaves out. */
private fun facilityOf(fields: List<String>, width: Int, column: Map<String, Int>): Facility? {
    if (fields.size != width) return null
    fun field(name: String) = fields[column.getValue(name)].trim()
    val alt = column.getValue(NAME_ALT_COLUMN).let { at -> if (at < 0) "" else fields[at].trim() }
    val lat = field("lat").toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lon = field("lon").toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val name = field("name")
    val types = field("types").split(',')
        .mapNotNull { it.trim().toIntOrNull()?.let(KINDS_BY_ID::get) }
        .fold(0) { bits, kind -> bits or kind.bit }
    return if (lat == null || lon == null || name.isEmpty()) {
        null
    } else {
        Facility(
            id = field("id"),
            // Three countries over forty thousand rows: one string each, not one a row.
            country = field("country").intern(),
            name = name,
            point = GeoPoint(lat, lon),
            types = types,
            city = field("city"),
            address = field("address"),
            nameAlt = alt,
        )
    }
}

private val KINDS_BY_ID = CareType.entries.associateBy(CareType::id)

private val FACILITY_COLUMNS = listOf("id", "country", "name", "lat", "lon", "types", "city", "address")
private const val NAME_ALT_COLUMN = "name_alt"

/** Written as an escape: the bare character is invisible, and could be lost from this line unnoticed. */
private const val BYTE_ORDER_MARK = "\uFEFF"

/**
 * Text as the search compares it: lower case, accents and other marks gone, punctuation turned to
 * single spaces.
 *
 * Decomposing first is what lets "nemocnice" find "Nemocnice Na Bulovce" and "zdravi" find
 * "Zdraví", and it does the same for the other scripts on the list: Ukrainian "й" and "ї" lose their
 * marks the way "č" does, and Arabic loses its short vowels and the hamza seats of "أ", "إ" and "آ".
 * A few letters carry no mark to lose and are folded by hand: the Arabic final "ى" and "ة" to the
 * letters people type for them, Eastern Arabic digits to ASCII ones, and the Ukrainian letters a
 * Russian keyboard does not have to the ones typed in their place — "і" and "ї" to "и", "є" to "е",
 * "ґ" to "г" — because many people in Ukraine type on that layout, and "Днипро" means "Дніпро".
 * Both sides are folded alike, so a name typed in Ukrainian still finds itself.
 *
 * Apostrophes join rather than split, because a Ukrainian name is spelt with one inside a word —
 * "Здоров’я" — and people type it with any of three marks or none.
 */
fun foldForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val folded = StringBuilder(decomposed.length)
    for (char in decomposed) {
        when {
            Character.getType(char) in MARKS -> Unit
            char in APOSTROPHES || char == TATWEEL -> Unit
            char in FOLDED_LETTERS -> folded.append(FOLDED_LETTERS.getValue(char))
            char in '٠'..'٩' -> folded.append('0' + (char - '٠'))
            char in '۰'..'۹' -> folded.append('0' + (char - '۰'))
            char.isLetterOrDigit() -> folded.append(char)
            else -> folded.append(' ')
        }
    }
    return folded.toString().trim().replace(SPACES, " ")
}

private val MARKS = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
)
private val APOSTROPHES = setOf('\'', '’', 'ʼ', '`', '´')
private const val TATWEEL = 'ـ'
private val FOLDED_LETTERS = mapOf(
    'ł' to "l",
    'ı' to "i",
    'ß' to "ss",
    'ø' to "o",
    'æ' to "ae",
    'đ' to "d",
    'ґ' to "г",
    'і' to "и",
    'є' to "е",
    'ى' to "ي",
    'ة' to "ه",
)
private val SPACES = Regex(" +")

/**
 * The kinds of care a visit of [specialty] is had at, or an empty set — no filter — for a visit
 * the list cannot place.
 *
 * [specialty] is an appointment's own value: a [VisitKind.stored] word, a screening's [Checkup]
 * name, or [VISIT_SPECIALTY_OTHER]. The [DoctorSpecialty] names read the same way, because the
 * kinds of doctor share them, so a report aimed at a doctor asks with the words it already has.
 *
 * A screening goes where it is done rather than to a doctor of the same name: mammography is an
 * imaging department, a bowel screening a gastroenterologist's colonoscopy.
 */
fun careTypesFor(specialty: String): Set<CareType> = CARE_TYPES_BY_SPECIALTY[specialty].orEmpty()

private val CARE_TYPES_BY_SPECIALTY: Map<String, Set<CareType>> =
    VisitKind.entries.associate { it.stored to it.careTypes } + mapOf(
        Checkup.CERVICAL.name to setOf(CareType.GYNAECOLOGY),
        Checkup.MAMMOGRAPHY.name to setOf(CareType.IMAGING),
        Checkup.BOWEL.name to setOf(CareType.GASTROENTEROLOGY),
    )

/**
 * Great-circle distance in metres, by the haversine formula on a sphere of the Earth's mean radius.
 * The sphere is off by up to half a percent against the ellipsoid, which across a town is metres —
 * less than the error in an approximate location to begin with.
 */
fun haversineMetres(from: GeoPoint, to: GeoPoint): Double {
    val dLat = Math.toRadians(to.lat - from.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_METRES * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** IUGG mean radius. */
private const val EARTH_RADIUS_METRES = 6_371_008.8

/** How many suggestions a search or a "near me" answer lists: a screen of them, not a register. */
const val FACILITY_SUGGESTIONS = 20

/** Shorter than this, a query matches a third of a country and says nothing about which place. */
const val FACILITY_QUERY_MIN = 2

/**
 * How far "near me" looks. Fifty kilometres is a drive to a regional hospital; past it the nearest
 * dentist on a list of three countries is in another one, and listing it would be a list for its
 * own sake.
 */
const val FACILITY_NEAR_METRES = 50_000.0

/**
 * The places [query] could mean, best first.
 *
 * Every word typed has to appear somewhere in the name, the city or the address — anywhere in a
 * word, not only at its start, because Arabic writes the article onto the noun and a person typing
 * "زهراء" means "الزهراء". The order is how well the name answers: one that starts with what was
 * typed, then one whose words start with each typed word, then one that holds them anywhere, then
 * one that only the address explains. Within that, a place offering [kinds] comes before one that
 * does not — the list is not narrowed to them, since a person typing a clinic's name knows which
 * clinic they mean better than a register's licence list does — and then the nearer one, when
 * [from] is known.
 */
fun searchFacilities(
    all: List<Facility>,
    query: String,
    kinds: Set<CareType>,
    from: GeoPoint?,
    limit: Int = FACILITY_SUGGESTIONS,
): List<FacilityHit> {
    val typed = foldForSearch(query)
    if (typed.length < FACILITY_QUERY_MIN) return emptyList()
    val words = typed.split(' ')
    return all.asSequence()
        .mapNotNull { place -> matchRank(place, typed, words)?.let { rank -> place to rank } }
        .map { (place, rank) -> Triple(place, rank, from?.let { haversineMetres(it, place.point) }) }
        .sortedWith(
            compareBy<Triple<Facility, Int, Double?>> { it.second }
                .thenBy { if (it.first.offers(kinds)) 0 else 1 }
                .thenBy { it.third ?: 0.0 }
                .thenBy { it.first.name.length }
                .thenBy { it.first.name },
        )
        .take(limit)
        .map { (place, _, metres) -> FacilityHit(place, metres) }
        .toList()
}

/**
 * Null when [place] does not hold every one of [words]; otherwise 0 (best) to 3, as [searchFacilities]
 * orders, by whichever of its names answers best.
 */
private fun matchRank(place: Facility, typed: String, words: List<String>): Int? {
    val alt = place.altKey
    if (words.any { it !in place.nameKey && it !in alt && it !in place.placeKey }) return null
    val byName = nameRank(place.nameKey, typed, words)
    return if (alt.isEmpty()) byName else minOf(byName, nameRank(alt, typed, words))
}

private fun nameRank(name: String, typed: String, words: List<String>): Int = when {
    name.startsWith(typed) -> 0
    words.all { name.startsWith(it) || " $it" in name } -> 1
    words.all { it in name } -> 2
    else -> 3
}

/**
 * The nearest places to [from] that offer [kinds], within [withinMetres], nearest first. An empty
 * [kinds] asks for any place at all.
 */
fun nearestFacilities(
    all: List<Facility>,
    from: GeoPoint,
    kinds: Set<CareType>,
    limit: Int = FACILITY_SUGGESTIONS,
    withinMetres: Double = FACILITY_NEAR_METRES,
): List<FacilityHit> = all.asSequence()
    .filter { it.offers(kinds) }
    .map { it to haversineMetres(from, it.point) }
    .filter { (_, metres) -> metres <= withinMetres }
    .sortedBy { (_, metres) -> metres }
    .take(limit)
    .map { (place, metres) -> FacilityHit(place, metres) }
    .toList()

/**
 * What the Place page lists under the person's own places: the places [typed] could mean once it
 * is long enough to mean anything, sorted by distance as well when [from] is known; before that,
 * the nearest ones that offer [kinds]; and with neither a query nor a position — location refused,
 * switched off, or never asked for — nothing at all, rather than forty thousand rows in file order.
 *
 * [nearAsked] is a tap on "Places near me" that nothing has been typed since. It wins over the
 * text, because the field is seldom empty when the tap comes — a place being changed opens with
 * its old name in it — and a tap that went on listing matches for that name would look like a
 * tap that did nothing.
 */
fun facilitySuggestions(
    all: List<Facility>,
    typed: String,
    kinds: Set<CareType>,
    from: GeoPoint?,
    nearAsked: Boolean = false,
): List<FacilityHit> = when {
    nearAsked && from != null -> nearestFacilities(all, from, kinds)
    foldForSearch(typed).length >= FACILITY_QUERY_MIN -> searchFacilities(all, typed, kinds, from)
    from != null -> nearestFacilities(all, from, kinds)
    else -> emptyList()
}

/**
 * True when the Place page is showing the nearest places rather than the matches for [typed]: the
 * same choice [facilitySuggestions] makes, for the list's title and its "nothing near" line.
 */
fun showsNearest(typed: String, from: GeoPoint?, nearAsked: Boolean): Boolean =
    from != null && (nearAsked || foldForSearch(typed).length < FACILITY_QUERY_MIN)

/**
 * [point] rounded to a hundredth of a degree — about a kilometre north to south, less east to
 * west. Android hands over a satellite-grade fix when precise location was granted for route
 * recording, and the page promises an approximate position; rounding here keeps that promise
 * whichever grant there is, and costs a list sorted by kilometres nothing.
 */
fun roughly(point: GeoPoint): GeoPoint = GeoPoint(point.lat.roundToHundredth(), point.lon.roundToHundredth())

/**
 * The kind of care a "near me" list for [specialty] is titled by, when that is not the visit's own
 * name. A screening is filtered to the department where it is usually done, and a title reading
 * "Mammography" over every imaging department would promise a mammograph at each; null means the
 * visit's name says truly what the list holds.
 */
fun nearListKind(specialty: String): CareType? = SCREENING_DEPARTMENTS[specialty]

private val SCREENING_DEPARTMENTS: Map<String, CareType> = mapOf(
    Checkup.CERVICAL.name to CareType.GYNAECOLOGY,
    Checkup.MAMMOGRAPHY.name to CareType.IMAGING,
    Checkup.BOWEL.name to CareType.GASTROENTEROLOGY,
)

/** Where a "near me" request stands. */
enum class NearStatus { IDLE, LOCATING, FOUND, DENIED, LOCATION_OFF, NO_FIX }

/**
 * How a "near me" request ended. A refused permission is said as such, so the page does not
 * blame the phone; with the permission, a missing position is told apart by whether location is
 * switched on at all, because only one of the two has a setting to open.
 */
fun nearStatusOf(fix: GeoPoint? = null, locationOn: Boolean = true, granted: Boolean = true): NearStatus = when {
    !granted -> NearStatus.DENIED
    fix != null -> NearStatus.FOUND
    !locationOn -> NearStatus.LOCATION_OFF
    else -> NearStatus.NO_FIX
}
