package com.pwnall.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pain row as the journal stores it. The rule that matters most: the row is the moment of the
 * report, never the onset — a row filed at the onset would mark an old day as kept by hand on the
 * doctor's sheet.
 */
class PainEntryTest {
    private val today = LocalDate.of(2026, 9, 25)
    private val now = today.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
    private val fiveDaysAgo = now - 5 * 24 * 3_600_000L

    @Test
    fun theRowIsTheReportNotTheOnset() {
        val report = PainReport(regions = listOf(knee), nrs = 4, onset = PainOnset.DAYS_2_3, onsetAt = fiveDaysAgo)
        val row = painEntry("Pain", report, day = today, today = today, now = now)
        assertEquals(now, row.occurredAt)
        assertEquals(today.toString(), row.day)
        assertEquals(KIND_PAIN, row.kind)
        assertEquals(0.0, row.calories, 0.0)
        assertTrue(row.measurementPayloadJson!!.contains("\"onsetAt\":$fiveDaysAgo"))
    }

    /** A report for a day gone by has no real moment of its own, and takes that day's midday. */
    @Test
    fun aBackDatedReportCarriesItsOwnDay() {
        // Years back, so the day is never the real today the helper compares with.
        val day = LocalDate.of(2001, 3, 10)
        val row = painEntry("Pain", PainReport(regions = listOf(knee)), day = day, today = day.plusDays(3), now = now)
        assertEquals(day.toString(), row.day)
        assertEquals(dayInstant(day.toString()), row.occurredAt)
    }

    @Test
    fun correctingAnEntryKeepsItsIdDayAndMoment() {
        val first = painEntry("Pain", PainReport(regions = listOf(knee)), day = today, today = today, now = now)
            .copy(id = 42)
        val corrected = painEntry(
            "Pain",
            PainReport(regions = listOf(knee), nrs = 7),
            day = today.plusDays(1),
            today = today.plusDays(1),
            now = now + 86_400_000L,
            previous = first,
        )
        assertEquals(42L, corrected.id)
        assertEquals(first.day, corrected.day)
        assertEquals(first.occurredAt, corrected.occurredAt)
    }

    @Test
    fun aGonePainCarriesHowLongItLasted() {
        val onsetAt = now - 3 * 3_600_000L
        val gone = PainReport(
            regions = listOf(knee),
            onsetAt = onsetAt,
            endedAt = onsetAt + 90 * 60_000L,
            lastedMinutes = 90,
        )
        assertEquals(90.0, painEntry("Pain", gone, today, today, now).durationMinutes!!, 0.0)
        val withoutAStart = PainReport(regions = listOf(knee), lastedMinutes = 45)
        assertEquals(45.0, painEntry("Pain", withoutAStart, today, today, now).durationMinutes!!, 0.0)
        assertNull(painEntry("Pain", PainReport(regions = listOf(knee)), today, today, now).durationMinutes)
    }

    /** Nothing a Health Connect export picks rows by: no sleep, no pulse, no weight. */
    @Test
    fun nothingGoesIntoAColumnAnExportReads() {
        val row = painEntry("Pain", PainReport(regions = listOf(knee), nrs = 9), today, today, now)
        assertNull(row.sleepStartAt)
        assertNull(row.sleepEndAt)
        assertNull(row.measurementHeartRateBpm)
        assertNull(row.measurementWeightKg)
        assertNull(row.measurementSource)
        assertNull(row.activityCode)
    }

    @Test
    fun noPainTodayIsARowOfItsOwn() {
        val row = painEntry("Pain", PainReport(none = true), today, today, now, note = "  ")
        assertEquals("""{"schema":2,"none":true}""", row.measurementPayloadJson)
        assertEquals("", row.note)
        assertNull(row.durationMinutes)
    }

    /**
     * A "no pain today" tapped in the morning and a pain logged in the evening: the pain is the
     * answer that stands. Saving it removes the morning's answer, and a day holding both — after a
     * restore, say — is never counted as pain-free.
     */
    @Test
    fun aDayWithAPainIsNeverPainFree() {
        val none = painEntry("Pain", PainReport(none = true), today, today, now - 10 * 3_600_000L).copy(id = 1)
        val pain = painEntry("Pain", PainReport(regions = listOf(knee)), today, today, now).copy(id = 2)
        val yesterday = today.minusDays(1)
        val noneYesterday = painEntry("Pain", PainReport(none = true), yesterday, today, now).copy(id = 3)
        assertEquals(setOf(yesterday.toString()), painFreeDays(listOf(none, pain, noneYesterday)))
        assertEquals(setOf(today.toString()), painFreeDays(listOf(none)))
        assertEquals(listOf(none), noPainRowsOverruled(PainReport(regions = listOf(knee)), listOf(none, pain)))
        assertEquals(emptyList<JournalEntry>(), noPainRowsOverruled(PainReport(none = true), listOf(none, pain)))
    }

    /**
     * "No pain today" is offered only on a day known to have no answer. Before the journal is read
     * nothing is known, and the button shown then could be tapped on a day that already had one.
     */
    @Test
    fun noPainIsNotOfferedBeforeTheDayIsRead() {
        assertEquals(PainNoneControl.HIDDEN, painNoneControl(loaded = false, todayNone = false, todayPain = false))
        assertEquals(PainNoneControl.OFFERED, painNoneControl(loaded = true, todayNone = false, todayPain = false))
        assertEquals(PainNoneControl.ANSWERED, painNoneControl(loaded = true, todayNone = true, todayPain = false))
        assertEquals(PainNoneControl.HIDDEN, painNoneControl(loaded = true, todayNone = false, todayPain = true))
    }

    /**
     * The page's save on a day already answered "no pain": the pain stands alone as the day's answer.
     * A write that throws leaves the "no pain" standing and is the page's failure, and a delete that
     * throws after the write is not: the entry, written, would be saved twice on a second try.
     */
    @Test
    fun aPainSavedOnAPainFreeDayTakesTheNoPainAway() = runTest {
        val rows = mutableMapOf<Long, JournalEntry>()
        val none = painEntry("Pain", PainReport(none = true), today, today, now - 3_600_000L).copy(id = 1)
        val report = PainReport(regions = listOf(knee))
        val pain = painEntry("Pain", report, today, today, now).copy(id = 2)
        val sameDay: suspend (String) -> List<JournalEntry> = { day -> rows.values.filter { it.day == day } }
        val write: suspend (JournalEntry) -> Unit = { rows[it.id] = it }

        rows[none.id] = none
        val saved = savePainRow(pain, report, write, sameDay) { rows.remove(it) }
        assertTrue(saved.isSuccess)
        assertEquals(listOf(pain), rows.values.toList())

        rows.clear()
        rows[none.id] = none
        val unwritten = savePainRow(pain, report, { error("disk full") }, sameDay) { rows.remove(it) }
        assertTrue(unwritten.isFailure)
        assertEquals(listOf(none), rows.values.toList())

        rows.clear()
        rows[none.id] = none
        val undeleted = savePainRow(pain, report, write, sameDay) { error("locked") }
        assertTrue(undeleted.isSuccess)
        assertEquals(setOf(none, pain), rows.values.toSet())
    }

    private val kneeRight = RegionSide(PainRegion.KNEE, BodySide.RIGHT)
    private val back = RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE)

    /** A pain row told on [day] at [hour] o'clock, built by hand so no real clock is read. */
    private fun told(id: Long, day: LocalDate, report: PainReport, hour: Int = 9) = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(report),
    )

    private fun hurt(vararg areas: RegionSide) = PainReport(regions = areas.toList(), nrs = 5)

    /** A left knee is not a right knee: the side is part of the area. */
    @Test
    fun anEarlierPainMustShareAnAreaOnTheSameSide() {
        val leftKnee = told(1, today.minusDays(3), hurt(knee))
        assertNull(earlierPainFor(setOf(kneeRight), listOf(leftKnee), today, null))
        assertEquals(leftKnee, earlierPainFor(setOf(kneeRight, knee), listOf(leftKnee), today, null))
        assertNull(earlierPainFor(emptySet(), listOf(leftKnee), today, null))
    }

    /** Thirty days up to and including the report's day: the 29th day back counts, the 30th does not. */
    @Test
    fun theLookupReachesBackThirtyDaysAndNoFurther() {
        val lastDay = told(1, today.minusDays(29), hurt(knee))
        val tooOld = told(2, today.minusDays(30), hurt(knee))
        assertEquals(lastDay, earlierPainFor(setOf(knee), listOf(lastDay), today, null))
        assertNull(earlierPainFor(setOf(knee), listOf(tooOld), today, null))
        val sameDay = told(3, today, hurt(knee), hour = 7)
        assertEquals(sameDay, earlierPainFor(setOf(knee), listOf(sameDay), today, null))
        // A report for a day gone by is never the same pain as one that came after it.
        val later = told(4, today.plusDays(1), hurt(knee))
        assertNull(earlierPainFor(setOf(knee), listOf(later), today, null))
    }

    @Test
    fun noPainAnswersAndTheEntryBeingCorrectedAreNeverTheEarlierPain() {
        // Written by hand with an area in it: the answer is "no pain", whatever else it names.
        val none = told(1, today.minusDays(1), hurt(knee))
            .copy(measurementPayloadJson = """{"schema":1,"none":true,"regions":["KNEE:L"]}""")
        assertNull(earlierPainFor(setOf(knee), listOf(none), today, null))
        val older = told(2, today.minusDays(5), hurt(knee))
        val editing = told(3, today.minusDays(2), hurt(knee))
        assertEquals(older, earlierPainFor(setOf(knee), listOf(older, editing), today, editingId = 3))
        val workout = told(4, today.minusDays(1), hurt(knee)).copy(kind = "ACTIVITY")
        assertNull(earlierPainFor(setOf(knee), listOf(workout), today, null))
    }

    @Test
    fun theNewestEarlierPainWins() {
        val weekAgo = told(1, today.minusDays(7), hurt(knee, back))
        val twoDaysAgo = told(2, today.minusDays(2), hurt(back))
        val morning = told(3, today.minusDays(2), hurt(knee), hour = 8)
        val evening = told(4, today.minusDays(2), hurt(knee), hour = 20)
        val rows = listOf(evening, weekAgo, morning, twoDaysAgo)
        assertEquals(evening, earlierPainFor(setOf(knee), rows, today, null))
        assertEquals(twoDaysAgo, earlierPainFor(setOf(back), rows, today, null))
    }

    private val firstMap = PainMapVersion.V1

    private fun area(region: PainRegion, side: BodySide = BodySide.CENTRE) = RegionSide(region, side)

    /**
     * An area painted on the second map finds the pain the first map recorded as its family — the area
     * it was carved from: the back of the left knee is the left knee painted last week, the centre of
     * the chest either side of it, a loin the lower back. Never the other side, and never another family.
     */
    @Test
    fun anEarlierPainOfTheFirstMapIsFoundByFamily() {
        fun old(id: Long, vararg areas: RegionSide) = told(id, today.minusDays(6), hurt(*areas).copy(map = firstMap))
        fun found(marked: RegionSide, row: JournalEntry) = earlierPainFor(setOf(marked), listOf(row), today, null)
        val leftKnee = old(1, knee)
        assertEquals(leftKnee, found(area(PainRegion.BACK_OF_KNEE, BodySide.LEFT), leftKnee))
        assertNull(found(area(PainRegion.BACK_OF_KNEE, BodySide.RIGHT), leftKnee))
        assertNull(found(area(PainRegion.SHIN, BodySide.LEFT), leftKnee))

        val chest = old(2, area(PainRegion.CHEST, BodySide.LEFT))
        assertEquals(chest, found(area(PainRegion.STERNUM), chest))

        val back = old(3, area(PainRegion.LOWER_BACK))
        assertEquals(back, found(area(PainRegion.FLANK, BodySide.RIGHT), back))
        val rightLoin = told(4, today.minusDays(2), hurt(area(PainRegion.FLANK, BodySide.RIGHT)))
        assertNull(found(area(PainRegion.FLANK, BodySide.LEFT), rightLoin))
        assertEquals(rightLoin, found(area(PainRegion.LOWER_BACK), rightLoin))
    }

    /**
     * The card that asks whether it is the same pain names where the earlier one was, as that one named
     * it, for every entry the lookup finds — found by family too: the knee for a back of the knee, one
     * side of the chest for its centre. The earlier entry's other areas are left out of it.
     */
    @Test
    fun theSamePainCardNamesWhereTheEarlierPainWas() {
        val chest = area(PainRegion.CHEST, BodySide.LEFT)
        val neck = area(PainRegion.NECK)
        val earlier = hurt(neck, knee, chest).copy(map = firstMap)
        val row = told(1, today.minusDays(6), earlier)
        val cases = mapOf(
            setOf(area(PainRegion.BACK_OF_KNEE, BodySide.LEFT)) to listOf(knee),
            setOf(knee) to listOf(knee),
            setOf(area(PainRegion.STERNUM)) to listOf(chest),
            setOf(area(PainRegion.BACK_OF_KNEE, BodySide.LEFT), area(PainRegion.STERNUM)) to listOf(knee, chest),
            setOf(area(PainRegion.BACK_OF_KNEE, BodySide.RIGHT)) to emptyList(),
        )
        cases.forEach { (marked, named) ->
            assertEquals("$marked", named, painSharedAreas(earlier, marked))
            // The card is shown exactly when the lookup finds the entry, and then it names a place.
            assertEquals("$marked", named.isNotEmpty(), earlierPainFor(marked, listOf(row), today, null) == row)
        }
    }

    /**
     * A "no pain" answer of the first map opened again and painted on is a new pain, painted on the
     * current map and saved on it: it had no painting to keep the first map for. An entry that has one
     * keeps its own map, and a new one is on the current map.
     */
    @Test
    fun aNoPainAnswerPaintedOnIsPaintedOnTheCurrentMap() {
        val none = decodePainPayload("""{"schema":1,"none":true}""")
        assertEquals(firstMap, none.map)
        assertEquals(PainMapVersion.CURRENT, painEntryMap(none))
        val painted = painReportToSave(hurt(knee).copy(map = painEntryMap(none)), reportedAt, null, null, null, none)
        assertEquals(PainMapVersion.CURRENT, painted.map)
        assertTrue(encodePainPayload(painted).startsWith("""{"schema":2,"regions":["KNEE:L"]"""))
        // Whatever map the answers came with: the entry decides.
        assertEquals(
            PainMapVersion.CURRENT,
            painReportToSave(hurt(knee).copy(map = firstMap), reportedAt, null, null, null, none).map,
        )
        assertEquals(firstMap, painEntryMap(hurt(knee).copy(map = firstMap)))
        assertEquals(PainMapVersion.CURRENT, painEntryMap(decodePainPayload(null)))
    }

    /** An entry painted on the first map is saved on the first map again; a new one on the map it was painted on. */
    @Test
    fun aCorrectedEntryKeepsItsMap() {
        val old = hurt(knee).copy(map = firstMap)
        val corrected = painReportToSave(hurt(knee, kneeRight), reportedAt, null, null, null, editing = old)
        assertEquals(firstMap, corrected.map)
        assertTrue(encodePainPayload(corrected).startsWith("""{"schema":1,"""))
        val fresh = painReportToSave(hurt(knee), reportedAt, null, null, null, editing = null)
        assertEquals(PainMapVersion.CURRENT, fresh.map)
        assertTrue(encodePainPayload(fresh).startsWith("""{"schema":2,"""))
    }

    /** Every entry of one pain points at its first report, never along a chain of follow-ups. */
    @Test
    fun theSamePainPointsAtItsFirstReport() {
        val first = told(1, today.minusDays(9), hurt(knee))
        val followUp = told(2, today.minusDays(3), hurt(knee).copy(same = first.occurredAt))
        assertEquals(first.occurredAt, firstReportOf(first))
        assertEquals(first.occurredAt, firstReportOf(followUp))
        assertEquals(
            PainRow(followUp),
            earlierPainAmong(setOf(knee), listOf(PainRow(first), PainRow(followUp)), today, null),
        )
    }

    private val reportedAt = now

    /** What the page's chips hold: a start of "just now" and words chosen before any answer. */
    private val onThePage = PainReport(
        regions = listOf(knee),
        types = listOf(PainType.SHARP),
        nrs = 6,
        onset = PainOnset.JUST_NOW,
        lastedMinutes = 30,
        noticed = PainNoticed.ON_WAKING,
        painkiller = true,
    )

    /** A pain told nine days back, and a follow-up of it five days later with its own words. */
    private val root = told(1, today.minusDays(9), hurt(knee).copy(noticed = PainNoticed.AFTER_WORKOUT))
    private val middle = told(
        2,
        today.minusDays(4),
        hurt(knee).copy(types = listOf(PainType.STIFF), noticed = PainNoticed.AFTER_WORKOUT, same = root.occurredAt),
    )

    private fun saved(
        same: Boolean?,
        changed: Boolean? = null,
        earlier: JournalEntry? = middle,
        editing: PainReport? = null,
    ) = painReportToSave(onThePage, reportedAt, earlier?.let { PainRow(it) }, same, changed, editing)

    /**
     * Feels the same: its words and first-noticed answer are the earlier entry's, whatever the hidden
     * chips hold, it has no start even with "just now" chosen before the answer, and it points at the
     * first report of the pain, not at the follow-up it was matched to.
     */
    @Test
    fun theSamePainFeelingTheSameCarriesTheEarlierAnswers() {
        val report = saved(same = true, changed = false)
        assertEquals(listOf(PainType.STIFF), report.types)
        assertEquals(PainNoticed.AFTER_WORKOUT, report.noticed)
        assertNull(report.onset)
        assertNull(report.onsetAt)
        assertNull(report.endedAt)
        assertEquals(root.occurredAt, report.same)
        assertEquals(6, report.nrs)
        assertEquals(30, report.lastedMinutes)
        assertTrue(report.painkiller)
        assertFalse(encodePainPayload(report).contains("onset"))
    }

    @Test
    fun theSamePainThatHasChangedKeepsItsNewWords() {
        val report = saved(same = true, changed = true)
        assertEquals(listOf(PainType.SHARP), report.types)
        assertEquals(PainNoticed.AFTER_WORKOUT, report.noticed)
        assertNull(report.onset)
        assertNull(report.onsetAt)
        assertEquals(root.occurredAt, report.same)
    }

    /** An earlier entry with no words has none to carry: the words asked for this one are the ones kept. */
    @Test
    fun theSamePainWithNothingToCarryKeepsTheWordsItWasAsked() {
        val wordless = told(3, today.minusDays(2), hurt(knee))
        val report = saved(same = true, changed = false, earlier = wordless)
        assertEquals(listOf(PainType.SHARP), report.types)
        assertEquals(wordless.occurredAt, report.same)
    }

    /**
     * A new one, or no earlier pain at all: the answers as chosen, "just now" the report's moment —
     * here the end of a pain gone after half an hour.
     */
    @Test
    fun aNewPainIsSavedAsChosen() {
        val chosen = onThePage.copy(onsetAt = reportedAt - 30 * 60_000L, endedAt = reportedAt)
        assertEquals(chosen, saved(same = false))
        assertEquals(chosen, saved(same = null))
        assertEquals(chosen, saved(same = true, changed = false, earlier = null))
    }

    /**
     * "Just now" and gone after an hour and a half: the pain was over by the report, and began an hour
     * and a half before it. Counted on from the report, it would end in the future. One that still hurts
     * starts at the report and has no end.
     */
    @Test
    fun aPainGoneByTheReportEndedThere() {
        val gone = painReportToSave(onThePage.copy(lastedMinutes = 90), reportedAt, null, null, null, null)
        assertEquals(reportedAt, gone.endedAt)
        assertEquals(reportedAt - 90 * 60_000L, gone.onsetAt)
        assertEquals(90.0, painEntry("Pain", gone, today, today, reportedAt).durationMinutes!!, 0.0)
        val stillHurts = painReportToSave(onThePage.copy(lastedMinutes = null), reportedAt, null, null, null, null)
        assertEquals(reportedAt, stillHurts.onsetAt)
        assertNull(stillHurts.endedAt)
    }

    /** Correcting a follow-up keeps what it follows up and still gives it no start; a plain one keeps its own. */
    @Test
    fun anEntryBeingCorrectedKeepsWhatItFollowsUp() {
        val followUp = saved(same = null, earlier = null, editing = hurt(knee).copy(same = root.occurredAt))
        assertEquals(root.occurredAt, followUp.same)
        assertNull(followUp.onset)
        assertNull(followUp.onsetAt)
        assertEquals(listOf(PainType.SHARP), followUp.types)
        assertEquals(PainNoticed.ON_WAKING, followUp.noticed)
        val plain = saved(same = null, earlier = null, editing = hurt(knee))
        assertNull(plain.same)
        assertEquals(PainOnset.JUST_NOW, plain.onset)
        assertEquals(reportedAt - 30 * 60_000L, plain.onsetAt)
    }

    @Test
    fun painIsAKindWithItsOwnNameDrawingAndFilter() {
        val row = painEntry("Pain", PainReport(regions = listOf(knee)), today, today, now)
        assertEquals(R.string.pain_title, importedKindTitle(KIND_PAIN))
        assertEquals(R.drawable.ic_art_pain, row.visual().art)
        assertEquals(CalendarFilter.PAIN, calendarEntryFilter(row))
    }
}
