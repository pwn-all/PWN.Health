package com.pwnall.health

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.SexualActivityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The reverse of the period export: an exclusive midnight end belongs to the day before it. And
 * what a period deleted, or taken back, leaves behind of itself and of its days.
 */
class CycleImportTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun instant(day: String, hour: Int = 0) =
        LocalDate.parse(day).atStartOfDay(zone).plusHours(hour.toLong()).toInstant()

    @Test
    fun ownExportRoundTrips() {
        // Published as [start 00:00, end+1 00:00); the day the export added must come back off.
        val days = importedPeriodDays(instant("2026-03-01"), instant("2026-03-06"), zone, zone)
        assertEquals(LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-05"), days)
    }

    @Test
    fun foreignMidDayEndStaysOnItsDay() {
        val days = importedPeriodDays(instant("2026-03-01", 9), instant("2026-03-05", 14), zone, zone)
        assertEquals(LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-05"), days)
    }

    @Test
    fun singleDayPeriodSurvives() {
        val days = importedPeriodDays(instant("2026-03-01"), instant("2026-03-02"), zone, zone)
        assertEquals(LocalDate.parse("2026-03-01") to LocalDate.parse("2026-03-01"), days)
    }

    @Test
    fun backwardsAndOverlongIntervalsAreRefused() {
        assertNull(importedPeriodDays(instant("2026-03-05"), instant("2026-03-01"), zone, zone))
        assertNull(importedPeriodDays(instant("2026-01-01"), instant("2026-03-01"), zone, zone))
    }

    /** What a deleted start or end leaves behind, so the import can refuse the record standing for it. */
    @Test
    fun aDeletedPeriodRowIsRememberedByItsKindAndDay() {
        assertEquals("period:2026-03-01", periodTombstoneKey(KIND_CYCLE, "2026-03-01"))
        assertEquals("period-end:2026-03-05", periodTombstoneKey(KIND_CYCLE_END, "2026-03-05"))
        // Every other kind is named by its row id already.
        assertNull(periodTombstoneKey("MEASUREMENT", "2026-03-01"))
    }

    /**
     * "A slip — delete it" takes back the period: its marks and its flow. A test, the mucus, the
     * pain, the desire and spotting are what she noticed on those days whatever the bleeding was,
     * and stay; so does sex.
     */
    @Test
    fun aPeriodTakenBackTakesItsMarksAndItsFlowAndNothingElseOfTheDay() {
        fun row(kind: String, log: CycleLog? = null) = JournalEntry(
            day = "2026-03-01",
            occurredAt = 0L,
            kind = kind,
            title = "",
            calories = 0.0,
            measurementPayloadJson = log?.let { "{\"$CYCLE_PAYLOAD_KEY\":\"${it.name}\"}" },
        )
        val flow = listOf(CycleLog.FLOW_LIGHT, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_HEAVY)

        assertTrue(row(KIND_CYCLE).isOfPeriod())
        assertTrue(row(KIND_CYCLE_END).isOfPeriod())
        flow.forEach { assertTrue(it.name, row(KIND_CYCLE_LOG, it).isOfPeriod()) }
        (CycleLog.entries - flow.toSet()).forEach { assertFalse(it.name, row(KIND_CYCLE_LOG, it).isOfPeriod()) }
        assertFalse(row(KIND_MASTURBATION).isOfPeriod())
    }

    /**
     * Each table names every answer by the Health Connect code of the same name — the positive test
     * by RESULT_POSITIVE, egg white by APPEARANCE_EGG_WHITE — and reads every code back as the
     * answer it was written for. The names are looked up on the record class, so a swapped pair
     * fails here rather than on somebody's fertility word.
     */
    @Test
    fun everyAnswerTravelsUnderTheCodeOfItsOwnNameAndComesBackAsItself() {
        val tables = listOf(
            Triple(HC_FLOW, MenstruationFlowRecord::class.java, "FLOW_" to "FLOW_"),
            Triple(HC_OVULATION_RESULT, OvulationTestRecord::class.java, "OVULATION_" to "RESULT_"),
            Triple(HC_MUCUS_APPEARANCE, CervicalMucusRecord::class.java, "MUCUS_" to "APPEARANCE_"),
            Triple(HC_PROTECTION, SexualActivityRecord::class.java, "SEX_" to "PROTECTION_USED_"),
        )

        tables.forEach { (table, record, prefixes) ->
            val (ours, theirs) = prefixes
            assertEquals("every answer", CycleLog.entries.filter { it.name.startsWith(ours) }, table.keys.toList())
            table.forEach { (log, code) ->
                val named = theirs + log.name.removePrefix(ours)
                assertEquals(log.name, record.getField(named).getInt(null), code)
                assertEquals(log.name, log, table.logFor(code))
            }
        }
    }

    /**
     * Pain, desire and spotting go out under no code of these four (spotting has a record type of
     * its own), and the codes Health Connect keeps for "not known" read back as nothing rather than
     * as a guessed answer.
     */
    @Test
    fun painDesireAndTheUnknownCodesHaveNoPlaceInTheTables() {
        val tables = listOf(HC_FLOW, HC_OVULATION_RESULT, HC_MUCUS_APPEARANCE, HC_PROTECTION)
        val unmapped = CycleSection.PAIN.logs + CycleSection.DESIRE.logs + CycleLog.SPOTTING

        unmapped.forEach { log -> assertTrue(log.name, tables.none { log in it }) }
        assertNull(HC_FLOW.logFor(MenstruationFlowRecord.FLOW_UNKNOWN))
        assertNull(HC_OVULATION_RESULT.logFor(OvulationTestRecord.RESULT_INCONCLUSIVE))
        assertNull(HC_MUCUS_APPEARANCE.logFor(CervicalMucusRecord.APPEARANCE_UNKNOWN))
        assertNull(HC_MUCUS_APPEARANCE.logFor(CervicalMucusRecord.APPEARANCE_UNUSUAL))
        assertNull(HC_PROTECTION.logFor(SexualActivityRecord.PROTECTION_USED_UNKNOWN))
    }
}
