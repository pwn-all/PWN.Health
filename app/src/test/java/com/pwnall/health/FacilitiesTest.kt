package com.pwnall.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FacilitiesTest {
    private val header = "id|country|name|lat|lon|types|city|address"

    private fun parse(vararg rows: String) = parseFacilities(sequenceOf(header, *rows))

    private fun place(
        name: String,
        lat: Double = 50.0,
        lon: Double = 14.0,
        types: String = "3",
        city: String = "Praha",
        address: String = "Ulice 1, 110 00 Praha",
    ) = parse("CZ:$name|CZ|$name|$lat|$lon|$types|$city|$address").single()

    @Test
    fun `a row is read into its fields, its kinds of care and its point`() {
        val read = parse("CZ:7|CZ|Nemocnice Slapy|49.81513|14.40387|17,20|Slapy|Slapy 257, 252 08 Slapy").single()
        assertEquals("CZ:7", read.id)
        assertEquals("CZ", read.country)
        assertEquals("Nemocnice Slapy", read.name)
        assertEquals(GeoPoint(49.81513, 14.40387), read.point)
        assertTrue(read.offers(setOf(CareType.HOSPITAL)))
        assertTrue(read.offers(setOf(CareType.REHABILITATION, CareType.DENTISTRY)))
        assertFalse(read.offers(setOf(CareType.DENTISTRY)))
        assertEquals("Slapy", read.city)
        assertEquals("Slapy 257, 252 08 Slapy", read.address)
    }

    @Test
    fun `the header is read rather than assumed, byte order mark and all`() {
        val read = parseFacilities(
            sequenceOf(
                "\uFEFFname|id|lon|lat|country|address|city|types",
                "Klinika|CZ:1|14.5|50.1|CZ|Ulice 2|Brno|1",
            ),
        ).single()
        assertEquals("CZ:1", read.id)
        assertEquals("Klinika", read.name)
        assertEquals(GeoPoint(50.1, 14.5), read.point)
        assertTrue(read.offers(setOf(CareType.DENTISTRY)))
        assertEquals("", read.nameAlt)
    }

    @Test
    fun `a second name is read when the file has one, and searched though never shown`() {
        val read = parseFacilities(
            sequenceOf(
                "$header|name_alt",
                "AE:DHA-1|AE|Al Zahra Pvt. Hospital|25.1|55.2|17|Dubai|AL BARSHA FIRST|مستشفى الزهراء الخاص",
                "CZ:1|CZ|Klinika|50|14|3|Brno|Ulice 2|",
            ),
        )
        assertEquals(listOf("مستشفى الزهراء الخاص", ""), read.map(Facility::nameAlt))
        val hits = searchFacilities(read, "مستشفى الزهراء", emptySet(), null)
        assertEquals(listOf("AE:DHA-1"), hits.map { it.facility.id })
        assertEquals("Al Zahra Pvt. Hospital, AL BARSHA FIRST, Dubai", hits.single().facility.placeText)
    }

    @Test
    fun `a half-read row is left out rather than guessed at`() {
        val read = parse(
            "CZ:1|CZ|Good|50|14|3|Praha|Ulice 1",
            "CZ:2|CZ|Too few fields|50|14|3|Praha",
            "CZ:3|CZ|Too|many|50|14|3|Praha|Ulice 1",
            "CZ:4|CZ|No latitude||14|3|Praha|Ulice 1",
            "CZ:5|CZ|Off the world|91|14|3|Praha|Ulice 1",
            "CZ:6|CZ|Off the world too|50|181|3|Praha|Ulice 1",
            "CZ:7|CZ||50|14|3|Praha|Ulice 1",
            "",
        )
        assertEquals(listOf("Good"), read.map(Facility::name))
    }

    @Test
    fun `a kind the app does not know is ignored and the row kept`() {
        val read = parse("CZ:1|CZ|Clinic|50|14|3,99,x,|Praha|Ulice 1").single()
        assertTrue(read.offers(setOf(CareType.PRIMARY_CARE)))
        assertEquals(CareType.PRIMARY_CARE.bit, read.types)
    }

    @Test
    fun `a file without a needed column, or with nothing in it, is no places at all`() {
        assertTrue(parseFacilities(sequenceOf("id|country|name|lat|types|city|address", "a|b|c|1|3|d|e")).isEmpty())
        assertTrue(parseFacilities(emptySequence()).isEmpty())
        assertTrue(parseFacilities(sequenceOf(header)).isEmpty())
    }

    @Test
    fun `folding drops case and accents in Czech`() {
        assertEquals("nemocnice na bulovce", foldForSearch("Nemocnice Na Bulovce"))
        assertEquals("zdravi zluty kun", foldForSearch("Zdraví, žlutý kůň"))
        assertEquals(foldForSearch("Ústí nad Labem"), foldForSearch("usti nad labem"))
    }

    @Test
    fun `folding treats Ukrainian letters and apostrophes the way people type them`() {
        val spelt = foldForSearch("Центр здоров’я «Ґражда»")
        assertEquals(spelt, foldForSearch("центр здоров'я «гражда»"))
        assertEquals(spelt, foldForSearch("ЦЕНТР ЗДОРОВʼЯ ГРАЖДА"))
        assertEquals(spelt, foldForSearch("центр здоровя гражда"))
        assertEquals("киив", foldForSearch("Київ"))
        assertEquals(foldForSearch("Миколаївська"), foldForSearch("миколаiвська".replace('i', 'і')))
        assertEquals(foldForSearch("Новий"), foldForSearch("новии"))
    }

    @Test
    fun `Ukrainian names are found as typed on a Russian keyboard, and as spelt`() {
        val all = listOf(
            place("Лікарня № 4", city = "Дніпро", address = "вулиця Європейська, 1"),
            place("Поліклініка", city = "Київ", address = "вулиця Ґонти, 2"),
        )
        assertEquals(listOf("Лікарня № 4"), names(searchFacilities(all, "днипро", emptySet(), null)))
        assertEquals(listOf("Лікарня № 4"), names(searchFacilities(all, "ликарня", emptySet(), null)))
        assertEquals(listOf("Лікарня № 4"), names(searchFacilities(all, "дніпро", emptySet(), null)))
        assertEquals(listOf("Поліклініка"), names(searchFacilities(all, "киив", emptySet(), null)))
        assertEquals(listOf("Поліклініка"), names(searchFacilities(all, "гонти", emptySet(), null)))
        assertEquals(listOf("Лікарня № 4"), names(searchFacilities(all, "евро", emptySet(), null)))
        // A Russian name that is another word is not a spelling, and folding does not pretend it is.
        assertTrue(searchFacilities(all, "киев", emptySet(), null).isEmpty())
    }

    @Test
    fun `folding drops Arabic vowel marks, hamza seats and the stretch, and reads Eastern digits`() {
        assertEquals(foldForSearch("الزهراء"), foldForSearch("الزَّهْرَاء"))
        assertEquals(foldForSearch("احمد"), foldForSearch("أحمد"))
        assertEquals(foldForSearch("اسلام"), foldForSearch("إسلام"))
        assertEquals(foldForSearch("مدينه"), foldForSearch("مدينة"))
        assertEquals(foldForSearch("مستشفي"), foldForSearch("مستشفى"))
        assertEquals(foldForSearch("طبي"), foldForSearch("طـــبي"))
        assertEquals("شارع 26", foldForSearch("شارع ٢٦"))
        assertEquals("شارع 26", foldForSearch("شارع ۲۶"))
    }

    @Test
    fun `punctuation folds to single spaces`() {
        assertEquals("mudr jan novak s r o", foldForSearch("  MUDr. Jan Novák, s.r.o.  "))
    }

    @Test
    fun `a search finds a Czech name typed without its accents`() {
        val all = listOf(place("Fakultní nemocnice Motol"), place("Zubní ordinace Žižkov"))
        assertEquals(listOf("Fakultní nemocnice Motol"), names(searchFacilities(all, "fakultni", emptySet(), null)))
        assertEquals(listOf("Zubní ordinace Žižkov"), names(searchFacilities(all, "zizkov", emptySet(), null)))
    }

    @Test
    fun `a search finds Ukrainian and Arabic names`() {
        val all = listOf(
            place("Амбулаторія загальної практики № 10", city = "Звягель", address = "вулиця Соборна, 2Б"),
            place("مستشفى الزهراء", city = "Dubai", address = "Al Barsha"),
        )
        assertEquals(1, searchFacilities(all, "амбулаторія", emptySet(), null).size)
        assertEquals(1, searchFacilities(all, "АМБУЛАТОРІЯ", emptySet(), null).size)
        // The article is written onto the noun, so a word is matched anywhere in a word.
        assertEquals(listOf("مستشفى الزهراء"), names(searchFacilities(all, "زهراء", emptySet(), null)))
        assertEquals(listOf("مستشفى الزهراء"), names(searchFacilities(all, "مستشفي", emptySet(), null)))
    }

    @Test
    fun `every word typed has to be there, in the name or in the address`() {
        val all = listOf(
            place("Poliklinika Budějovická", city = "Praha", address = "Antala Staška 1670/80, 140 00 Praha"),
            place("Poliklinika Brno", city = "Brno", address = "Zahradníkova 2/8, 602 00 Brno"),
        )
        assertEquals(listOf("Poliklinika Brno"), names(searchFacilities(all, "poliklinika brno", emptySet(), null)))
        assertEquals(
            listOf("Poliklinika Budějovická"),
            names(searchFacilities(all, "poliklinika stask", emptySet(), null)),
        )
        assertTrue(searchFacilities(all, "poliklinika ostrava", emptySet(), null).isEmpty())
    }

    @Test
    fun `a name that starts with what was typed comes before one that holds it, and before an address`() {
        val all = listOf(
            place("Clinic on Motol Street", address = "Ulice 1"),
            place("Klinika", address = "Motolská 5, Praha"),
            place("Nemocnice Motol"),
            place("Motol nemocnice"),
            place("Bmotolx"),
        )
        assertEquals(
            listOf("Motol nemocnice", "Nemocnice Motol", "Clinic on Motol Street", "Bmotolx", "Klinika"),
            names(searchFacilities(all, "motol", emptySet(), null)),
        )
    }

    @Test
    fun `a place that sees this kind of doctor comes first, then the nearer one`() {
        val here = GeoPoint(50.0, 14.0)
        val all = listOf(
            place("Ordinace far dentist", lat = 50.5, types = "1"),
            place("Ordinace near GP", lat = 50.01, types = "3"),
            place("Ordinace near dentist", lat = 50.02, types = "1,3"),
        )
        assertEquals(
            listOf("Ordinace near dentist", "Ordinace far dentist", "Ordinace near GP"),
            names(searchFacilities(all, "ordinace", setOf(CareType.DENTISTRY), here)),
        )
        val hits = searchFacilities(all, "ordinace", emptySet(), here)
        assertEquals(listOf("Ordinace near GP", "Ordinace near dentist", "Ordinace far dentist"), names(hits))
        assertTrue(hits.all { it.metres != null })
    }

    @Test
    fun `a search with no position knows no distance`() {
        val hits = searchFacilities(listOf(place("Nemocnice")), "nemocnice", emptySet(), null)
        assertEquals(1, hits.size)
        assertEquals(null, hits.single().metres)
    }

    @Test
    fun `a query too short to mean anything finds nothing`() {
        val all = listOf(place("A clinic"))
        assertTrue(searchFacilities(all, "a", emptySet(), null).isEmpty())
        assertTrue(searchFacilities(all, " .. ", emptySet(), null).isEmpty())
    }

    @Test
    fun `a search lists a screen of places, not the register`() {
        val all = (1..50).map { place("Ordinace $it") }
        assertEquals(FACILITY_SUGGESTIONS, searchFacilities(all, "ordinace", emptySet(), null).size)
        assertEquals(5, searchFacilities(all, "ordinace", emptySet(), null, limit = 5).size)
    }

    @Test
    fun `near me lists the places of this kind, nearest first, within reach`() {
        val here = GeoPoint(50.0, 14.0)
        val all = listOf(
            place("Dentist 3 km", lat = 50.027, types = "1"),
            place("GP 1 km", lat = 50.009, types = "3"),
            place("Dentist 1 km", lat = 50.009, lon = 14.001, types = "1"),
            place("Dentist 80 km", lat = 50.72, types = "1"),
        )
        val hits = nearestFacilities(all, here, setOf(CareType.DENTISTRY))
        assertEquals(listOf("Dentist 1 km", "Dentist 3 km"), names(hits))
        assertTrue(hits.map { it.metres ?: Double.NaN }.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(listOf("GP 1 km", "Dentist 1 km", "Dentist 3 km"), names(nearestFacilities(all, here, emptySet())))
    }

    @Test
    fun `near me with nothing in reach, or nothing loaded, is an empty list`() {
        val here = GeoPoint(50.0, 14.0)
        assertTrue(nearestFacilities(listOf(place("Far", lat = 51.0)), here, emptySet()).isEmpty())
        assertTrue(nearestFacilities(emptyList(), here, emptySet()).isEmpty())
        assertTrue(nearestFacilities(listOf(place("GP")), here, setOf(CareType.DENTISTRY)).isEmpty())
    }

    @Test
    fun `without a query or a position the page lists nothing, and a query still works without one`() {
        val all = listOf(place("Nemocnice", lat = 50.001))
        assertTrue(facilitySuggestions(all, "", emptySet(), null).isEmpty())
        assertEquals(1, facilitySuggestions(all, "nemocnice", emptySet(), null).size)
        assertEquals(1, facilitySuggestions(all, "", emptySet(), GeoPoint(50.0, 14.0)).size)
        assertTrue(facilitySuggestions(emptyList(), "nemocnice", emptySet(), GeoPoint(50.0, 14.0)).isEmpty())
    }

    @Test
    fun `a refused permission is said as such, and a missing fix tells location off from no answer`() {
        assertEquals(NearStatus.DENIED, nearStatusOf(granted = false))
        assertEquals(NearStatus.FOUND, nearStatusOf(GeoPoint(1.0, 2.0)))
        assertEquals(NearStatus.LOCATION_OFF, nearStatusOf(null, locationOn = false))
        assertEquals(NearStatus.NO_FIX, nearStatusOf(null, locationOn = true))
    }

    @Test
    fun `haversine gives a degree of latitude and Prague to Brno`() {
        assertEquals(111_195.0, haversineMetres(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1.0)
        assertEquals(0.0, haversineMetres(GeoPoint(50.0, 14.0), GeoPoint(50.0, 14.0)), 0.0)
        // Old Town Square to Náměstí Svobody: some 186 km as the crow flies.
        val km = haversineMetres(GeoPoint(50.0875, 14.4213), GeoPoint(49.1951, 16.6080)) / 1000
        assertEquals(186.1, km, 0.5)
        // The antimeridian is not the far side of the world.
        assertEquals(111_195.0, haversineMetres(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5)), 1.0)
    }

    @Test
    fun `every appointment specialty maps to a kind of care, and another doctor to no filter`() {
        Checkup.entries.forEach { assertTrue(it.name, careTypesFor(it.name).isNotEmpty()) }
        DoctorSpecialty.entries.filter { it != DoctorSpecialty.CUSTOM }
            .forEach { assertTrue(it.name, careTypesFor(it.name).isNotEmpty()) }
        assertTrue(careTypesFor(VISIT_SPECIALTY_OTHER).isEmpty())
        assertTrue(careTypesFor(DoctorSpecialty.CUSTOM.name).isEmpty())
        assertTrue(careTypesFor("SOMETHING_NEW").isEmpty())
        assertEquals(setOf(CareType.DENTISTRY), careTypesFor(Checkup.DENTIST.name))
        assertEquals(setOf(CareType.IMAGING), careTypesFor(Checkup.MAMMOGRAPHY.name))
        assertEquals(setOf(CareType.GYNAECOLOGY), careTypesFor(Checkup.CERVICAL.name))
        assertEquals(setOf(CareType.CARDIOLOGY), careTypesFor(DoctorSpecialty.CARDIOLOGIST.name))
    }

    @Test
    fun `the taxonomy ids are the thirty the asset uses, once each`() {
        assertEquals((1..30).toList(), CareType.entries.map(CareType::id))
    }

    @Test
    fun `the place filled in is the name and where it is, the city said once`() {
        val czech = place("MUDr. Novák", city = "Ostrava", address = "Veleslavínova 252/5, 702 00 Ostrava")
        assertEquals("MUDr. Novák, Veleslavínova 252/5, 702 00 Ostrava", czech.placeText)
        val dubai = place("Al Zahra Hospital", city = "Dubai", address = "Al Na’ayat Street 6, AL BARSHA FIRST")
        assertEquals("Al Zahra Hospital, Al Na’ayat Street 6, AL BARSHA FIRST, Dubai", dubai.placeText)
        val bare = place("Clinic", city = "Brno", address = "")
        assertEquals("Clinic, Brno", bare.placeText)
    }

    /**
     * The shipped file itself: every line reads, every kind is one of the thirty, every point is
     * inside one of the three countries it covers. Counts are not pinned — the file is rebuilt.
     */
    @Test
    fun `the shipped list reads whole`() {
        val file = File("src/main/assets/$FACILITIES_ASSET")
        val lines = file.readLines()
        val read = parseFacilities(lines.asSequence())
        assertEquals(lines.size - 1, read.size)
        assertEquals(read.size, read.map(Facility::id).toSet().size)
        // Only Abu Dhabi's medical centres and general or specialised clinics may come with no kind of care:
        // the Department of Health names none for them, and a kind is never guessed from a name. They are
        // found by name, and among "every place" near the person.
        assertTrue(read.filter { it.types == 0 }.all { it.id.startsWith("AE:DOH-") })
        assertEquals(setOf("AE", "CZ", "UA"), read.map(Facility::country).toSet())
        val boxes = mapOf(
            "CZ" to (48.5..51.1 to 12.0..18.9),
            "UA" to (44.3..52.4 to 22.1..40.3),
            "AE" to (22.6..26.1 to 51.5..56.4),
        )
        read.forEach { place ->
            val (lat, lon) = boxes.getValue(place.country)
            assertTrue(place.id, place.point.lat in lat && place.point.lon in lon)
        }
        // Every row stays on the heap for as long as the app is on the screen once the list has been
        // opened, so the file growing past these is a decision to take, not a rebuild to wave through.
        assertTrue("${file.length()} bytes, ${read.size} places", file.length() <= 10_000_000L && read.size <= 50_000)
    }

    /**
     * The "?" names where the places come from, register by register, so no register's places stand
     * under "Sources" without a line: every row on the shipped list has to carry the prefix of a
     * register the page gives a source line for, and no line is kept for a register that has left
     * the list.
     */
    @Test
    fun `every register on the shipped list has its source behind the question mark`() {
        val read = parseFacilities(File("src/main/assets/$FACILITIES_ASSET").readLines().asSequence())
        val unsourced = read.filterNot { place -> FACILITY_SOURCES.keys.any { place.id.startsWith(it) } }
        assertEquals(emptyList<String>(), unsourced.map(Facility::id).take(5))
        FACILITY_SOURCES.forEach { (prefix, lines) ->
            assertTrue(prefix, lines.isNotEmpty() && read.any { it.id.startsWith(prefix) })
        }
        assertTrue(R.string.facility_source_ae_dubai in FACILITY_SOURCES.getValue("AE:DHA-"))
    }

    /**
     * A Place page left before its read is done, and opened again: the read the first one began is
     * kept, and the second waits for it rather than reading the file a second time beside it.
     */
    @Test
    fun `a read whose asker has gone is kept, and the next asker waits for it`() = runBlocking {
        val list = FacilityList()
        val clinic = listOf(place("Clinic"))
        val reads = AtomicInteger()
        val reading = CompletableDeferred<Unit>()
        val finish = CountDownLatch(1)

        val form = launch {
            list.get {
                reads.incrementAndGet()
                reading.complete(Unit)
                finish.await()
                clinic
            }
        }
        reading.await()
        form.cancel()
        val page = async {
            list.get {
                reads.incrementAndGet()
                emptyList()
            }
        }
        finish.countDown()

        assertEquals(clinic, page.await())
        assertEquals(1, reads.get())
        assertEquals(clinic, list.loaded)
    }

    /**
     * A read that failed is answered with no places and not kept, so the next page reads the file
     * again instead of offering nothing until the app is restarted; and a list let go when the app
     * left the screen is read again by whoever asks next.
     */
    @Test
    fun `a failed read is not kept, and a list let go is read again`() = runBlocking {
        val list = FacilityList()
        val clinic = listOf(place("Clinic"))

        assertEquals(emptyList<Facility>(), list.get { throw OutOfMemoryError("eight megabytes") })
        assertEquals(null, list.loaded)
        assertEquals(clinic, list.get { clinic })

        list.forget()
        assertEquals(null, list.loaded)
        assertEquals("Read again", list.get { listOf(place("Read again")) }.single().name)
    }

    /** A letter typed after "near me" and rubbed out again is a key pressed: the text has the list back. */
    @Test
    fun `a key pressed after near me ends it, even one taken back`() {
        val near = NearMe()
        near.ask("Poliklinika Praha")
        near.typed("Poliklinika Praha")
        assertEquals("Poliklinika Praha", near.askedFor)

        near.typed("Poliklinika Prah")
        near.typed("Poliklinika Praha")
        assertEquals(null, near.askedFor)
    }

    @Test
    fun `a tap on near me lists the nearest even with the old place still in the field`() {
        val here = GeoPoint(50.0, 14.0)
        val all = listOf(
            place("Poliklinika Praha", lat = 50.3, types = "1"),
            place("Zubní ordinace", lat = 50.01, types = "1"),
        )
        // Before the tap, and once a key has been pressed after it, the text decides.
        assertEquals(
            listOf("Poliklinika Praha"),
            names(facilitySuggestions(all, "Poliklinika Praha", emptySet(), here)),
        )
        assertFalse(showsNearest("Poliklinika Praha", here, nearAsked = false))
        // The tap itself shows the nearest places of the kind asked for.
        val near = facilitySuggestions(all, "Poliklinika Praha", setOf(CareType.DENTISTRY), here, nearAsked = true)
        assertEquals(listOf("Zubní ordinace", "Poliklinika Praha"), names(near))
        assertTrue(showsNearest("Poliklinika Praha", here, nearAsked = true))
        // A tap that found no position leaves the text's matches, and says it is not the near list.
        assertEquals(1, facilitySuggestions(all, "Poliklinika Praha", emptySet(), null, nearAsked = true).size)
        assertFalse(showsNearest("Poliklinika Praha", null, nearAsked = true))
        assertTrue(showsNearest("", here, nearAsked = false))
        assertFalse(showsNearest("", null, nearAsked = false))
    }

    @Test
    fun `a position is rounded to about a kilometre before it is used`() {
        assertEquals(GeoPoint(50.09, 14.42), roughly(GeoPoint(50.087_512, 14.421_348)))
        assertEquals(GeoPoint(-33.87, -151.21), roughly(GeoPoint(-33.868_8, -151.209_3)))
        assertEquals(GeoPoint(25.2, 55.27), roughly(GeoPoint(25.199_999, 55.270_04)))
        val moved = haversineMetres(GeoPoint(50.005, 14.005), roughly(GeoPoint(50.005, 14.005)))
        assertTrue(moved < 1_000.0)
    }

    @Test
    fun `a screening's near list is named by the department it was narrowed to`() {
        assertEquals(CareType.IMAGING, nearListKind(Checkup.MAMMOGRAPHY.name))
        assertEquals(CareType.GYNAECOLOGY, nearListKind(Checkup.CERVICAL.name))
        assertEquals(CareType.GASTROENTEROLOGY, nearListKind(Checkup.BOWEL.name))
        // Its department is what the list is narrowed to, so the title says what the list holds.
        listOf(Checkup.MAMMOGRAPHY, Checkup.CERVICAL, Checkup.BOWEL).forEach {
            assertEquals(it.name, setOf(nearListKind(it.name)), careTypesFor(it.name))
        }
        listOf(Checkup.GP, Checkup.DENTIST, Checkup.EYES, Checkup.GYNECOLOGIST, Checkup.UROLOGIST).forEach {
            assertEquals(it.name, null, nearListKind(it.name))
        }
        assertEquals(null, nearListKind(VISIT_SPECIALTY_OTHER))
    }

    @Test
    fun `an Arabic name finds a real Dubai place on the shipped list`() {
        val read = parseFacilities(File("src/main/assets/$FACILITIES_ASSET").readLines().asSequence())
        val dubai = read.filter { it.id.startsWith("AE:DHA-") }
        assertTrue(dubai.isNotEmpty() && dubai.all { it.nameAlt.isNotBlank() })
        val hits = searchFacilities(read, "مستشفى الزهراء", emptySet(), null)
        assertTrue(hits.map { it.facility.id }.toString(), hits.any { it.facility.id == "AE:DHA-0000035" })
    }

    @Test
    fun `every place on the shipped list fits the place field whole`() {
        val read = parseFacilities(File("src/main/assets/$FACILITIES_ASSET").readLines().asSequence())
        val longest = read.maxBy { it.placeText.length }
        assertTrue("${longest.id}: ${longest.placeText.length}", longest.placeText.length <= PLACE_MAX_CHARS)
    }

    @Test
    fun `the other emirates are on the shipped list, with an emergency department only where one works`() {
        val read = parseFacilities(File("src/main/assets/$FACILITIES_ASSET").readLines().asSequence())
        val abuDhabi = searchFacilities(read, "Sheikh Khalifa Medical City", emptySet(), null)
        assertTrue(names(abuDhabi).toString(), abuDhabi.any { it.facility.id.startsWith("AE:DOH-") })
        val saqr = read.single { it.id.startsWith("AE:MOHAP-") && it.name == "Saqr Hospital" }
        assertEquals("Ras Al Khaimah", saqr.city)
        assertTrue(saqr.offers(setOf(CareType.EMERGENCY)) && saqr.offers(setOf(CareType.HOSPITAL)))
        // 215 emergency patients in all of 2025: a hospital, but not a place to send an emergency.
        val kuwait = read.single { it.id == "AE:MOHAP-mkd-al-kuwait-hospital" }
        assertTrue(kuwait.offers(setOf(CareType.HOSPITAL)) && !kuwait.offers(setOf(CareType.EMERGENCY)))
    }

    private fun names(hits: List<FacilityHit>) = hits.map { it.facility.name }
}
