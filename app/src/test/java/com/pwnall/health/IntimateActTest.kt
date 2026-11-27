package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The sex-life page's acts outside the screen: the history's list, what an edit writes — above all
 * which edits keep the row Health Connect knows the act by — and the act marked by a tap.
 */
class IntimateActTest {
    /** Acts only, newest first; two on one instant — two typed for one earlier day — by id. */
    @Test
    fun theHistoryIsEveryActNewestFirst() {
        val flow = row(1, KIND_CYCLE_LOG, """{"cycle":"FLOW_LIGHT"}""", at = 900)
        val older = row(2, KIND_CYCLE_LOG, """{"cycle":"SEX_PROTECTED"}""", at = 100)
        val solo = row(3, KIND_MASTURBATION, null, at = 500)
        val sameInstant = row(4, KIND_CYCLE_LOG, """{"cycle":"SEX_UNPROTECTED"}""", at = 100)
        val morning =
            row(5, KIND_MORNING_CHECK, """{"schema":1,"morning":"FIRM","wokeAt":1,"wake":"TYPED"}""", at = 950)

        assertEquals(listOf(3L, 4L, 2L), intimateHistory(listOf(flow, older, solo, sameInstant, morning)).map { it.id })
    }

    /**
     * An edit writes what the tile would have written — the kind, the title, the ticks under it and
     * the payload — and keeps the row's own id and note.
     */
    @Test
    fun anEditCarriesTheTilesRow() {
        val original = row(6, KIND_CYCLE_LOG, """{"cycle":"SEX_PROTECTED"}""", at = 1_000).copy(note = "kept")
        val filed = filed(IntimateAct.UNPROTECTED, listOf(SexNote.PAIN)).copy(title = "Unprotected", subtitle = "Pain")

        val edit = revisedAct(original, filed, EDITED_AT)

        assertEquals(filed.measurementPayloadJson, edit.write.measurementPayloadJson)
        assertEquals("Unprotected", edit.write.title)
        assertEquals("Pain", edit.write.subtitle)
        assertEquals(6L, edit.write.id)
        assertEquals("kept", edit.write.note)
    }

    /**
     * Protected to unprotected, the ticks, the day and the time: the act stays published, so it keeps
     * its row — the id is its client record id, and the sync upserts the record in place.
     */
    @Test
    fun anEditInsideHealthConnectKeepsTheRow() {
        val original = row(7, KIND_CYCLE_LOG, """{"cycle":"SEX_PROTECTED"}""", at = 1_000).copy(note = "kept")

        val edit = revise(original, IntimateAct.UNPROTECTED, listOf(SexNote.PAIN))

        assertNull(edit.replaces)
        assertEquals(7L, edit.write.id)
        assertEquals(KIND_CYCLE_LOG, edit.write.kind)
        assertEquals(IntimateAct.UNPROTECTED, edit.write.intimateAct())
        assertEquals(listOf(SexNote.PAIN), sexNotesOf(edit.write.measurementPayloadJson))
        assertEquals(EDITED_DAY.toString(), edit.write.day)
        assertEquals(EDITED_AT, edit.write.occurredAt)
        assertEquals("kept", edit.write.note)
    }

    /** Another app's act stays that app's record while it stays partnered: its marker goes nowhere. */
    @Test
    fun anImportedActKeepsItsMarkerInsideItsStore() {
        val imported = row(8, KIND_CYCLE_LOG, """{"cycle":"SEX_PROTECTED"}""", at = 1_000)
            .copy(measurementSource = "HEALTH_CONNECT", healthConnectRecordKey = "hc:abc")

        val edit = revise(imported, IntimateAct.UNPROTECTED, emptyList())

        assertEquals(8L, edit.write.id)
        assertEquals("hc:abc", edit.write.healthConnectRecordKey)
    }

    /**
     * Partnered to solo has to leave Health Connect, which only a deletion's tombstone does; the
     * solo act is a new row of this app's own, with no marker that would keep it from staying local.
     */
    @Test
    fun aPartneredActMadeSoloIsReplaced() {
        val imported = row(9, KIND_CYCLE_LOG, """{"cycle":"SEX_UNPROTECTED"}""", at = 1_000)
            .copy(measurementSource = "HEALTH_CONNECT", healthConnectRecordKey = "hc:def")

        val edit = revise(imported, IntimateAct.SOLO, listOf(SexNote.PAIN))

        assertEquals(9L, edit.replaces)
        assertEquals(0L, edit.write.id)
        assertEquals(KIND_MASTURBATION, edit.write.kind)
        assertNull(edit.write.healthConnectRecordKey)
        assertNull(edit.write.measurementSource)
        assertEquals(IntimateAct.SOLO, edit.write.intimateAct())
    }

    /** And a solo act made partnered is a record Health Connect has never had: a new row as well. */
    @Test
    fun aSoloActMadePartneredIsReplaced() {
        val solo = row(10, KIND_MASTURBATION, null, at = 1_000)

        val edit = revise(solo, IntimateAct.PROTECTED, emptyList())

        assertEquals(10L, edit.replaces)
        assertEquals(0L, edit.write.id)
        assertEquals(IntimateAct.PROTECTED, edit.write.intimateAct())
    }

    /** Solo stays solo: the row is kept, as it is for the two partnered acts. */
    @Test
    fun aSoloEditKeepsTheRow() {
        val edit = revise(row(11, KIND_MASTURBATION, null, at = 1_000), IntimateAct.SOLO, listOf(SexNote.DISCOMFORT))

        assertNull(edit.replaces)
        assertEquals(11L, edit.write.id)
    }

    /** An act set later today than now is filed now: an act still to come is not one to record. */
    @Test
    fun anEditedTimeIsNeverLaterThanNow() {
        val zone = ZoneId.of("Europe/Berlin")
        val day = LocalDate.of(2026, 9, 14)
        val nine = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(nine, actInstant(day, 22 * 60, zone, nine))
        assertEquals(nine - 60 * 60_000, actInstant(day, 8 * 60, zone, nine))
    }

    /** A tap marks its act; a tap on the marked one takes it back; another tile switches. */
    @Test
    fun aTapMarksSwitchesOrTakesBack() {
        assertEquals(IntimateAct.PROTECTED, pendingActAfterTap(null, IntimateAct.PROTECTED))
        assertNull(pendingActAfterTap(IntimateAct.PROTECTED, IntimateAct.PROTECTED))
        assertEquals(IntimateAct.SOLO, pendingActAfterTap(IntimateAct.PROTECTED, IntimateAct.SOLO))
    }

    /** An act filed for an earlier day carries its midday, which the history reads as no time at all. */
    @Test
    fun aMiddayStampIsNoTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val midday = LocalDate.of(2026, 9, 10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        assertTrue(untimedAct(row(1, KIND_MASTURBATION, null, at = midday), zone))
        assertFalse(untimedAct(row(2, KIND_MASTURBATION, null, at = midday + 60_000), zone))
        assertFalse(untimedAct(row(3, KIND_MASTURBATION, null, at = midday).copy(day = "2026-09-11"), zone))
    }

    /**
     * An act with no time moved to another day keeps none: the new day's midday, or — on a today whose
     * midday is still ahead, since nothing is filed later than now — the day's first moment, which the
     * history reads as no time too. Never the moment of the edit.
     */
    @Test
    fun anUntimedActMovedToTodayKeepsNoTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val today = LocalDate.of(2026, 10, 4)
        val morning = today.atTime(9, 14).atZone(zone).toInstant().toEpochMilli()
        val evening = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(midnight, untimedInstant(today, zone, morning))
        assertEquals(today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), untimedInstant(today, zone, evening))
        val moved = row(
            1,
            KIND_MASTURBATION,
            null,
            at = untimedInstant(today, zone, morning),
        ).copy(day = today.toString())
        assertTrue(untimedAct(moved, zone))
        assertFalse(untimedAct(moved.copy(occurredAt = morning), zone))
    }

    private fun revise(previous: JournalEntry, act: IntimateAct, notes: List<SexNote>) =
        revisedAct(previous, filed(act, notes), EDITED_AT)

    /**
     * What a tile files for [act], as [cycleLogEntry] and [masturbationEntry] spell it; those take
     * the app's resources for the title, which a plain JVM test does not have.
     */
    private fun filed(act: IntimateAct, notes: List<SexNote>): JournalEntry {
        val ticks = notes.joinToString(",") { "\"${it.name}\"" }
        val log = act.log?.name
        val payload = when {
            log == null && notes.isEmpty() -> null
            log == null -> """{"notes":[$ticks]}"""
            notes.isEmpty() -> """{"cycle":"$log"}"""
            else -> """{"cycle":"$log","notes":[$ticks]}"""
        }
        return JournalEntry(
            day = EDITED_DAY.toString(),
            occurredAt = 0,
            kind = if (act.log == null) KIND_MASTURBATION else KIND_CYCLE_LOG,
            title = "Act",
            calories = 0.0,
            measurementPayloadJson = payload,
        )
    }

    private fun row(id: Long, kind: String, payload: String?, at: Long) = JournalEntry(
        id = id,
        day = "2026-09-10",
        occurredAt = at,
        kind = kind,
        title = "Act",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    private companion object {
        val EDITED_DAY: LocalDate = LocalDate.of(2026, 9, 12)
        const val EDITED_AT = 1_757_700_000_000L
    }
}
