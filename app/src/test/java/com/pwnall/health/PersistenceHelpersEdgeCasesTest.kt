package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The edges of the small helpers every journal write and every import passes through.
 *
 * Nothing here touches the database: what these decide is what the database is then asked. A reading
 * judged a twin one millisecond too early is stored twice and published twice; a kind with no drawing
 * shows a pair of scales beside a cigarette; and a birth date nobody can read is turned into an age and
 * then into a food budget. Each of those is invisible on the screen that shows the result.
 */
class PersistenceHelpersEdgeCasesTest {
    /** The set of rows owed to the mirror is process-wide; no test may leave one behind. */
    @Before
    @After
    fun clearEditedRows() = forgetEditedJournalRows(editedJournalRows())

    // --- one reading under two names --------------------------------------------------------------

    private val readAt = 1_754_000_000_000L

    private fun reading(
        payload: String?,
        at: Long = readAt,
        source: String? = null,
        weightKg: Double? = null,
    ) = JournalEntry(
        day = "2026-08-01",
        occurredAt = at,
        kind = "MEASUREMENT",
        title = "Reading",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementSource = source,
        measurementPayloadJson = payload,
    )

    /**
     * A meter's report-all after a reinstall arrives beside the row Health Connect restored, and
     * the meter's clock and the phone's are not the same clock. The window is documented as a whole
     * second to either side, so a second exactly is still one reading; a strict comparison in
     * either direction decides whether that report is swallowed or saved again and re-published
     * under a fresh client record id, which is the duplicate history this rule was written for.
     */
    @Test
    fun theSecondEitherSideOfAReadingIsInclusiveInBothDirections() {
        val standing = reading("""{"spo2Percent":97}""")
        val oximeter = reading("""{"spo2Percent":97,"pulseRateBpm":70}""", source = "BLUETOOTH_PLXS")

        assertTrue("a second later", sameDirectReading(standing, oximeter.copy(occurredAt = readAt + 1_000)))
        assertTrue("a second earlier", sameDirectReading(standing, oximeter.copy(occurredAt = readAt - 1_000)))
        assertFalse("a millisecond past", sameDirectReading(standing, oximeter.copy(occurredAt = readAt - 1_001)))

        // The row already standing is as often the later of the pair: the same window, mirrored.
        val later = standing.copy(occurredAt = readAt + 1_000)
        assertTrue("standing a second later", sameDirectReading(later, oximeter))
        assertFalse("standing past the window", sameDirectReading(later.copy(occurredAt = readAt + 1_001), oximeter))
    }

    /**
     * A truncated write, or a payload from a build that never used JSON. This runs inside the
     * transaction importing a whole RACP batch, so a row nobody can parse has to be nobody's twin —
     * an exception here abandons every reading queued behind it and leaves the import half done.
     */
    @Test
    fun aPayloadThatIsNotJsonIsRefusedRatherThanThrownOver() {
        val standing = reading("""{"spo2Percent":97}""")

        listOf("{", "", "spo2Percent=97", "null", "[97]").forEach { broken ->
            val incoming = reading(broken, source = "BLUETOOTH_PLXS")
            assertFalse("a reading whose payload is <$broken>", sameDirectReading(standing, incoming))
        }
        // And from the other side, where the unreadable payload is the row already stored.
        assertFalse(sameDirectReading(reading("{"), reading("""{"spo2Percent":97}""")))
        assertFalse("a reading with no payload at all", sameDirectReading(standing, reading(null)))
    }

    /**
     * A payload restored from another app, or written by a build that quoted everything, spells the
     * same figure as a string. What identifies a reading is the value and the unit it is in, not
     * whether the writer put quotes round it — a meter re-sending 97% must not be stored twice
     * because one of the two copies came back through JSON as text.
     */
    @Test
    fun aFigureWrittenAsAQuotedNumberIsTheSameFigure() {
        val standing = reading("""{"spo2Percent":97}""")
        val quoted = reading("""{"spo2Percent":"97"}""", source = "BLUETOOTH_PLXS")

        assertTrue(sameDirectReading(standing, quoted))
        assertTrue("and with the quoted copy the row already standing", sameDirectReading(quoted, standing))
        assertFalse("a different figure, quoted", sameDirectReading(standing, reading("""{"spo2Percent":"96"}""")))
    }

    /**
     * A cuff whose read was cut short reports the systolic alone. It is still the reading already
     * standing, because the rule is that every figure *the incoming reading* carries has to stand
     * in the row and not the other way round. The predicate reads as though it were symmetric, so
     * swapping its two arguments at the one call site would invert it in silence and store the
     * half-read report a second time, an hour after the whole one.
     */
    @Test
    fun everyFigureTheReadingCarriesHasToStandInTheRowAndNotTheOtherWayRound() {
        val whole = reading("""{"systolicMmhg":121,"diastolicMmhg":79}""")
        val half = reading("""{"systolicMmhg":121}""", source = "BLUETOOTH_BPS")

        assertTrue("the half-read report is the row already standing", sameDirectReading(whole, half))
        assertFalse("the row standing knows less than the reading does", sameDirectReading(half, whole))
    }

    /**
     * Blood pressure is stored to half a millimetre of mercury, and the tolerance is that precision
     * itself: at exactly half a millimetre the two rows are one reading, past it they are two. Both
     * 0.5 and 121.5 are exact in binary, so this is the boundary and not a rounding accident.
     */
    @Test
    fun theToleranceOfAFigureIsInclusiveOnItsOwnBoundary() {
        val standing = reading("""{"systolicMmhg":121.0,"diastolicMmhg":79.0}""")
        fun cuff(systolic: Double) =
            reading("""{"systolicMmhg":$systolic,"diastolicMmhg":79.0}""", source = "BLUETOOTH_BPS")

        assertTrue("half a millimetre over", sameDirectReading(standing, cuff(121.5)))
        assertTrue("half a millimetre under", sameDirectReading(standing, cuff(120.5)))
        assertFalse("three quarters over", sameDirectReading(standing, cuff(121.75)))
    }

    /**
     * A meter that reported a figure no double can hold. Infinity is not 97 and `abs(inf - inf)` is
     * NaN, which every comparison answers false to, so the only way this can go wrong is by
     * throwing — in the middle of an import with the rest of the batch behind it.
     */
    @Test
    fun aFigureTooLargeForADoubleNeitherMatchesNorThrows() {
        val standing = reading("""{"spo2Percent":97}""")

        val huge = reading("""{"spo2Percent":1e400}""", source = "BLUETOOTH_PLXS")
        assertFalse("an unbounded figure was read as 97%", sameDirectReading(standing, huge))
        val negative = reading("""{"spo2Percent":-1e400}""", source = "BLUETOOTH_PLXS")
        assertFalse("an unbounded negative figure was read as 97%", sameDirectReading(standing, negative))
    }

    // --- the hour a credit lands in ---------------------------------------------------------------

    /**
     * The step service reads the day and the write reads `LocalDate.now()`; between the two a
     * timezone change or a clock correction moves the calendar backwards, so the day being credited
     * is one the phone has not reached. Filing it at 23:00 draws an evening walk on a day that has
     * not happened, and the hourly row is what draws the shape of the day. The honest answers are
     * the hour the batch arrived in or that day's first hour; the last hour is the one that cannot
     * be right, because it is reserved for the earlier half of a bout that straddled midnight.
     */
    @Test
    fun aDayLaterThanTodayIsNotCreditedToItsLastHour() {
        val hour = creditedHour(day = "2026-08-13", today = "2026-08-12", currentHour = 14)

        assertTrue("a day that has not happened was credited to $hour:00", hour != 23)
        assertTrue("outside a clock face: $hour", hour in 0..23)
    }

    // --- an age nobody typed ----------------------------------------------------------------------

    private fun profile(birthDate: String) = UserProfile(
        sex = "MALE",
        weightKg = 70.0,
        heightCm = 175,
        birthDate = birthDate,
    )

    /** The date the age screen writes, and the bare year the profile carried before it did. */
    @Test
    fun theYearIsReadOutOfAWholeDateAndOutOfAYearOnItsOwn() {
        assertEquals(1990, profile("1990-04-17").birthYear)
        assertEquals(1990, profile("1990").birthYear)
        assertEquals(2026, profile("2026-01-01").birthYear)
    }

    /**
     * A backup carrying neither a date nor a positive year restores as an empty birth date, and a
     * hand-edited or truncated export leaves whatever it likes. [ageOn] answers such a date with an
     * age inside 0..120 so the gates close; every reader of [UserProfile.birthYear] instead
     * subtracts it from the current year with only a lower clamp, so the year must imply no such age
     * either. Read as about two thousand years old, an unreadable date would have Mifflin–St Jeor
     * answer a resting energy of about minus eight thousand kcal, and the day's whole food budget
     * would be built on that figure.
     */
    @Test
    fun aBirthDateTheAppCannotReadImpliesNoAgeAtAll() {
        listOf("", "не знаю", "0000-12-31", "90-04-17", "-100").forEach { unreadable ->
            val implied = (2026 - profile(unreadable).birthYear).coerceAtLeast(0)
            assertTrue("\"$unreadable\" is read as $implied years old", implied <= 120)
        }
        // The same arithmetic on a date the app can read, so the check above is not vacuous.
        assertEquals(36, (2026 - profile("1990-04-17").birthYear).coerceAtLeast(0))
    }

    // --- the rows the mirror still owes -----------------------------------------------------------

    /**
     * The cap bounds one SQLite statement's bound variables, not the lifetime of the set. A
     * high-water mark and a size check look identical until something is forgotten: an
     * implementation that never re-opened would stop mirroring edits for the rest of the process,
     * and every row written afterwards would reach Health Connect only at the next full exchange.
     */
    @Test
    fun capacityComesBackOnceTheMirrorHasPublished() {
        repeat(500) { rememberEditedJournalRow(it + 1L) }

        forgetEditedJournalRows(setOf(1L))
        rememberEditedJournalRow(501L)

        val owed = editedJournalRows()
        assertTrue("the set holds ${owed.size} rows and not 501", 501L in owed)
        assertEquals(500, owed.size)
        assertFalse("the published row came back", 1L in owed)
    }

    /**
     * An `@Upsert` answers -1 for a row it updated rather than inserted, and `saveJournalEntry`
     * hands on whatever it is given. A row id the mirror cannot read back must not be remembered:
     * it would be bound into the next publish as a row that cannot exist, in place of one that can.
     */
    @Test
    fun aNegativeRowIdIsRefusedLikeAnUnnumberedOne() {
        rememberEditedJournalRow(-1L)
        rememberEditedJournalRow(Long.MIN_VALUE)

        assertEquals(emptySet<Long>(), editedJournalRows())
    }

    // --- the names a deleted row is withdrawn under -----------------------------------------------

    /**
     * A flow, spotting, ovulation, mucus or sex log is published as `pwnfit-journal-cycle:<row id>`,
     * so [journalRecordKey] already names it. A day key here would be one name for every log of that
     * day: deleting this morning's would withdraw the ones still standing, and they would vanish
     * from the cycle screen at the next sync without anybody deleting them.
     */
    @Test
    fun onlyThePeriodItselfIsNamedByItsDay() {
        assertNull(periodTombstoneKey(KIND_CYCLE_LOG, "2026-03-01"))
        assertNull(periodTombstoneKey(KIND_MED, "2026-03-01"))
        assertNull(periodTombstoneKey(KIND_MASTURBATION, "2026-03-01"))
        // A start and an end falling on one day are two withdrawals, not one.
        assertNotEquals(
            periodTombstoneKey(KIND_CYCLE, "2026-03-01"),
            periodTombstoneKey(KIND_CYCLE_END, "2026-03-01"),
        )
    }

    /**
     * A period start that arrived from another app has three names: the id this app publishes it
     * under, the marker the other store knows it by, and the day the period record is keyed on.
     * Withdrawing it under fewer than all three leaves a copy for the next sync to adopt back, and
     * the deletion undoes itself on a screen the person is still looking at.
     */
    @Test
    fun anImportedPeriodStartIsWithdrawnUnderEveryNameItIsKnownBy() {
        val marker = "com.example.tracker:1f3c"

        val keys = journalTombstoneKeys(31, marker) +
            listOfNotNull(periodTombstoneKey(KIND_CYCLE, "2026-03-01"))

        assertEquals("three names, none repeated: $keys", 3, keys.toSet().size)
        assertTrue("the row's own id is missing from $keys", journalRecordKey(31) in keys)
        assertTrue("the foreign marker is missing from $keys", marker in keys)
    }

    /**
     * A line of the medical history deleted here has nothing for a backup's merge to find newer than
     * the file's copy, so without a tombstone the merge would take the copy, and a diagnosis taken
     * back, or a pregnancy marked by mistake, would come home from the first older backup restored.
     * Its key is random and the same on every phone, so its tombstone names it wherever the file
     * goes, and shares no name with a row's.
     */
    @Test
    fun aDeletedHistoryLineStaysOutOfEveryBackupRestoredAfter() {
        fun line(key: String) = MedicalHistoryRow(key = key, kind = "CONDITION", title = "Asthma", updatedAt = 1L)
        val deleted = line("6f1d0c2a")
        val kept = line("9b27e4f1")

        val tombstoned = setOf(medicalHistoryTombstoneKey(deleted.key), journalRecordKey(31))

        assertEquals(listOf(kept), withoutDeletedHistory(listOf(deleted, kept), tombstoned))
        assertTrue(medicalHistoryTombstoneKey(deleted.key).startsWith(MEDICAL_HISTORY_TOMBSTONE_PREFIX))
        assertNotEquals(journalRecordKey(31), medicalHistoryTombstoneKey("31"))

        // And so it is believed from another installation's file, as a deleted route's is.
        val history = JournalTombstone(medicalHistoryTombstoneKey(deleted.key), 1L)
        val rows = listOf(JournalTombstone(journalRecordKey(31), 1L), history)
        assertEquals(listOf(history), importableTombstones(rows, "install-b", "install-a"))
        assertEquals(listOf(history), importableTombstones(rows, null, "install-a"))
    }

    // --- the kinds themselves ---------------------------------------------------------------------

    private val sourceDirectory = File("src/main/java/com/pwnall/health")

    /** Every `KIND_…` the app declares, by constant name, as the sources spell it. */
    private fun declaredKinds(): Map<String, String> {
        val sources = sourceDirectory.listFiles { file: File -> file.extension == "kt" }
            ?: error("${sourceDirectory.path} is not where this test looks; fix the path, not the test.")
        val declaration = Regex("const val (KIND_\\w+) = \"([^\"]+)\"")
        return sources.sortedBy { it.name }
            .flatMap { declaration.findAll(it.readText()).toList() }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * The kinds are free strings declared in seven different files, and the stored value is what
     * every `kind = :kind` probe, the day list and [periodTombstoneKey] tell two kinds apart by.
     * Two of them sharing a spelling makes each invisible on the other's screen and makes a
     * one-tap guard refuse a drink because a cigarette was logged; nothing but a test can see it.
     */
    @Test
    fun noTwoJournalKindsShareAStoredString() {
        val kinds = declaredKinds()

        assertTrue("only ${kinds.size} kinds were found; the scan is reading the wrong files", kinds.size >= 12)
        val shared = kinds.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals("kinds sharing one stored string", emptyMap<String, List<String>>(), shared)
        assertTrue("a blank kind among $kinds", kinds.values.all { it.isNotBlank() })
    }

    /**
     * A kind missing from the dispatch table falls through to the measurement drawing, which is a
     * pair of scales — beside "😊 Good", or beside a cigarette. A hand-written list of kinds falls
     * behind the app; this one reads what the sources actually declare, so a kind registered and
     * forgotten cannot pass.
     */
    @Test
    fun everyDeclaredKindHasADrawingOfItsOwn() {
        val designSource = File(sourceDirectory, "PwnDesign.kt")
        val design = designSource.takeIf { it.isFile }?.readText()
            ?: error("${designSource.path} is not where this test looks; fix the path, not the test.")
        val table = Regex("KIND_VISUALS: Map<String, EntryVisual> = mapOf\\(([\\s\\S]*?)\\n\\)")
            .find(design)?.groupValues?.get(1)
            ?: error("no KIND_VISUALS table in ${designSource.path}; fix the path, not the test.")

        val drawn = Regex("(KIND_\\w+) to").findAll(table).map { it.groupValues[1] }.toSet()

        assertTrue("only ${drawn.size} kinds are drawn; the table was not read", drawn.size >= 12)
        // A measurement is the one kind with no row of its own, on purpose: it says what it is by
        // which column is filled, so it is what the table falls through to, by a name of its own.
        assertTrue("KIND_VISUALS[kind] ?: measurementVisual()" in design)
        val undrawn = declaredKinds().keys - drawn - "KIND_MEASUREMENT"
        assertEquals("kinds with no drawing of their own", emptySet<String>(), undrawn)
    }
}
