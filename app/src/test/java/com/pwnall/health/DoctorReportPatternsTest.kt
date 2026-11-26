package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The doctor's sheet sections for the pain map, the work timer and the morning check-ins: what each
 * prints, what it waits for, and what it never carries — soreness after training without the
 * workouts, a morning on anybody's sheet but a man's, a single morning with its date, a morning
 * arriving by a preset.
 */
class DoctorReportPatternsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val march = d(3, 1)..d(3, 31)

    private fun d(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-01-01")

    private fun report(
        entries: List<JournalEntry>,
        parts: Set<ReportPart> = ReportPart.entries.toSet(),
        profile: UserProfile = man,
        period: ClosedRange<LocalDate> = march,
        longDayMinutes: Int = WORK_LONG_DAY_DEFAULT_MINUTES,
        workDaysOff: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    ) = doctorReport(
        sources = ReportSources(
            profile = profile,
            entries = entries,
            days = emptyList(),
            workDaysOff = workDaysOff,
            longDayMinutes = longDayMinutes,
        ),
        request = ReportRequest(from = period.start, to = period.endInclusive, parts = parts),
        today = period.endInclusive,
        zone = zone,
    )

    private fun DoctorReport.present(part: ReportPart): ReportSection? =
        sections.firstOrNull { it.part == part && it.presence == SectionPresence.PRESENT }

    private fun ReportSection.row(label: ReportText): ReportRow? = rows.firstOrNull { it.label == label }

    private fun ReportText.flattened(): List<ReportText> = listOf(this) + when (this) {
        is ReportText.Res -> args.filterIsInstance<ReportText>().flatMap { it.flattened() }
        is ReportText.Join -> parts.flatMap { it.flattened() }
        else -> emptyList()
    }

    private fun ReportSection.texts(): List<ReportText> =
        (rows.flatMap { listOfNotNull(it.label, it.value, it.note) } + footnotes).flatMap { it.flattened() }

    // --- pain -------------------------------------------------------------------------------------

    private fun pain(
        day: LocalDate,
        region: PainRegion,
        nrs: Int? = null,
        types: List<PainType> = emptyList(),
        onsetAt: Long? = null,
        painkiller: Boolean = false,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(
            PainReport(
                regions = listOf(RegionSide(region, if (region.paired) BodySide.LEFT else BodySide.CENTRE)),
                types = types,
                nrs = nrs,
                onsetAt = onsetAt,
                painkiller = painkiller,
            ),
        ),
    )

    private fun kept(day: LocalDate) =
        JournalEntry(day = day.toString(), occurredAt = at(day, 12), kind = KIND_WATER, title = "Water", calories = 0.0)

    private fun session(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 18),
        kind = "ACTIVITY",
        title = "Leg curl",
        calories = 0.0,
        activityCode = "LEG_CURL",
    )

    /** March's legs, PainStatsTest's own: soreness after a session on the 2nd, then a knee that is not. */
    private fun legs() = listOf(
        kept(d(3, 1)),
        session(d(3, 2)),
        pain(d(3, 3), PainRegion.THIGH_FRONT, nrs = 4, types = listOf(PainType.ACHING), onsetAt = at(d(3, 3), 8)),
        pain(d(3, 4), PainRegion.THIGH_FRONT, nrs = 3, types = listOf(PainType.ACHING)),
        kept(d(3, 5)),
        pain(d(3, 10), PainRegion.KNEE, nrs = 6, types = listOf(PainType.SHARP), painkiller = true),
        pain(d(3, 11), PainRegion.KNEE, nrs = 7, types = listOf(PainType.SHARP), painkiller = true),
        pain(d(3, 13), PainRegion.KNEE, nrs = 5),
        pain(d(3, 17), PainRegion.KNEE, nrs = 2),
        pain(d(3, 18), PainRegion.NECK, nrs = 9),
    )

    private fun answered(count: Int, of: Int) =
        res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.report_days_answered, of))

    /** Pain days of the days answered, per group, with the scores beside them; a blank day is in neither count. */
    @Test
    fun painIsCountedPerGroupOverTheDaysAnswered() {
        val section = report(legs()).present(ReportPart.PAIN)!!

        // Ten days answered in March: three kept by hand, the workout's among them, and seven with pain.
        assertEquals(answered(7, 10), section.row(res(R.string.report_pain_any))!!.value)
        val legsRow = section.row(res(R.string.report_pain_group_hips_legs))!!
        assertEquals(answered(6, 10), legsRow.value)
        assertEquals(ReportText.Join(listOf(res(R.string.report_pain_scores, "4.5", 7))), legsRow.note)
        assertEquals(answered(1, 10), section.row(res(R.string.report_pain_group_neck_shoulders))!!.value)
        // Groups without pain are not listed.
        assertNull(section.row(res(R.string.report_pain_group_head)))
        assertEquals(ReportText.Plain("2"), section.row(res(R.string.report_pain_painkiller))!!.value)
        assertEquals(Completeness(10, 31, 7), section.completeness)
        assertEquals(R.string.report_caveat_pain, section.caveatRes)
        // Under three months recorded: no span line and no footnote for it.
        assertTrue(section.footnotes.isEmpty())
    }

    /**
     * Soreness after training is counted apart only where the workouts go on the sheet: without them
     * the fit would disclose a session the sheet leaves off, and the episodes are counted together.
     */
    @Test
    fun sorenessIsCountedApartOnlyWithTheWorkouts() {
        val episodes = res(R.string.report_pain_episodes)
        val soreness = res(R.string.report_pain_soreness)

        // Three runs on the legs: the thigh after the session, the knee from the 10th to the 13th, and
        // the knee again on the 17th.
        val with = report(legs()).present(ReportPart.PAIN)!!
        val legsRows = with.rows.dropWhile { it.label != res(R.string.report_pain_group_hips_legs) }
        assertEquals(ReportText.Plain("2"), legsRows.first { it.label == episodes }.value)
        val sore = legsRows.first { it.label == soreness }
        assertEquals(ReportText.Plain("1"), sore.value)
        assertEquals(
            res(R.string.report_pain_soreness_days, ReportText.Quantity(R.plurals.count_days, 2)),
            sore.note,
        )

        val without = report(
            legs(),
            parts = ReportPart.entries.toSet() - ReportPart.ACTIVITY,
        ).present(ReportPart.PAIN)!!
        val plain = without.rows.dropWhile { it.label != res(R.string.report_pain_group_hips_legs) }
        assertEquals(ReportText.Plain("3"), plain.first { it.label == episodes }.value)
        assertFalse(without.rows.any { it.label == soreness })
    }

    /** More than three months recorded says so with its first and last record, and the footnote says why. */
    @Test
    fun anAreaRecordedOverMoreThanThreeMonthsSaysSo() {
        val rows = listOf(pain(d(1, 5), PainRegion.LOWER_BACK, nrs = 3)) + legs() +
            pain(d(3, 20), PainRegion.LOWER_BACK, nrs = 4) + pain(d(4, 20), PainRegion.LOWER_BACK, nrs = 5)
        val section = report(rows, period = d(1, 1)..d(4, 30)).present(ReportPart.PAIN)!!

        val span = section.row(res(R.string.report_pain_span, 3))!!
        assertEquals(res(R.string.report_period_value, ReportText.Day(d(1, 5)), ReportText.Day(d(4, 20))), span.value)
        assertEquals(
            listOf(
                res(
                    R.string.report_changes_background,
                    res(R.string.report_pain_background_span),
                    res(R.string.report_pain_source_span),
                ),
            ),
            section.footnotes,
        )
        // A March-only sheet reads nothing after it, and the back's span there is a day.
        assertNull(report(rows).present(ReportPart.PAIN)!!.row(res(R.string.report_pain_span, 3)))
    }

    /** Charts first: pain days a whole week beside the days answered, and the day's highest score on 1–10. */
    @Test
    fun painChartsDrawTheWeeksAndTheScores() {
        val section = report(legs() + (1..31).map { kept(d(3, it)) }).present(ReportPart.PAIN)!!
        val (weeks, scores) = section.charts
        assertEquals(listOf(ChartStyle.BARS, ChartStyle.DOTS), weeks.series.map { it.style })
        assertEquals(7.0, weeks.yCeiling)
        // Four whole weeks back from the 31st; the three days before them are left off.
        assertEquals(listOf(7.0, 7.0, 7.0, 7.0), weeks.series[1].points.map { it.value })
        // The pain page's own scale: 1, mild, to 10, unbearable — there is no 0 to stand a dot on.
        assertEquals(PAIN_NRS_RANGE.first.toDouble(), scores.yFloor)
        assertEquals(PAIN_NRS_RANGE.last.toDouble(), scores.yCeiling)
        assertEquals(listOf(4.0, 3.0, 6.0, 7.0, 5.0, 2.0, 9.0), scores.series.single().points.map { it.value })
    }

    private fun painted(day: LocalDate, strokes: List<PainStroke>) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(
            PainReport(regions = paintedRegions(strokes, PainMapVersion.CURRENT).toList(), nrs = 5, strokes = strokes),
        ),
    )

    private val kneeStroke = PainStroke(PainView.FRONT, 0.035f, listOf(Offset(0.64f, 0.615f), Offset(0.70f, 0.62f)))
    private val backDab = PainStroke(PainView.BACK, 0.012f, listOf(Offset(0.62f, 0.29f)))

    /**
     * The section carries every painted entry of the period's strokes, as stored, for the plates of
     * the profile's sex; an entry chosen from the list only, and one painted before the period, are
     * not in it.
     */
    @Test
    fun thePainSectionCarriesThePeriodsPaintings() {
        val rows = listOf(
            painted(d(2, 20), listOf(backDab)),
            painted(d(3, 3), listOf(kneeStroke)),
            painted(d(3, 9), listOf(kneeStroke, backDab)),
            pain(d(3, 10), PainRegion.KNEE),
        )
        val woman = man.copy(sex = "FEMALE")
        val drawing = report(rows, profile = woman).present(ReportPart.PAIN)!!.painDrawing!!
        assertEquals("FEMALE", drawing.sex)
        val stored = { stroke: PainStroke ->
            decodePainPayload(encodePainPayload(PainReport(strokes = listOf(stroke)))).strokes
        }
        assertEquals(listOf(stored(kneeStroke), stored(kneeStroke) + stored(backDab)), drawing.entries)
    }

    private fun painEntry(day: LocalDate, regions: List<RegionSide>, strokes: List<PainStroke>) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(PainReport(regions = regions, strokes = strokes)),
    )

    /**
     * Nothing painted in the period — areas from the list only, or strokes wholly above the plate —
     * no drawing. The strokes above it include the whole-figure brush over the jaw and the back of the
     * head, whose reach crosses the top of the plate onto the neck: where they were painted decides,
     * so they print no cap of ink on the neck. The entry records every area those strokes mark, so
     * only where they were painted can leave them out.
     */
    @Test
    fun noPaintingNoDrawing() {
        val section = report(legs()).present(ReportPart.PAIN)!!
        assertNull(section.painDrawing)
        val head = listOf(
            PainStroke(PainView.FRONT, 0.008f, listOf(Offset(0.5f, -0.08f))),
            PainStroke(PainView.FRONT, 0.035f, listOf(Offset(0.44f, -0.02f))),
            PainStroke(PainView.BACK, 0.035f, listOf(Offset(0.5f, -0.03f))),
        )
        val regions = listOf(
            RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE),
            RegionSide(PainRegion.JAW, BodySide.RIGHT),
            RegionSide(PainRegion.HEAD_BACK, BodySide.CENTRE),
        ) + paintedRegions(head, PainMapVersion.CURRENT)
        val headOnly = painEntry(d(3, 6), regions, head)
        assertNull(report(legs() + headOnly).present(ReportPart.PAIN)!!.painDrawing)
    }

    /**
     * A stroke over an area the person then struck off the list is not drawn: the rows do not name
     * that place, and the person said it was not there. A stroke over an area still recorded is.
     */
    @Test
    fun aStrokeOverAnAreaStruckOffIsNotDrawn() {
        val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
        val struckOff = painEntry(d(3, 6), emptyList(), listOf(kneeStroke))
        assertNull(report(legs() + struckOff).present(ReportPart.PAIN)!!.painDrawing)
        val kept = painEntry(d(3, 7), listOf(knee), listOf(kneeStroke, backDab))
        val drawing = report(legs() + struckOff + kept).present(ReportPart.PAIN)!!.painDrawing!!
        assertEquals(listOf(listOf(PainView.FRONT)), drawing.entries.map { strokes -> strokes.map { it.view } })
    }

    /**
     * An entry painted on the first map is drawn as it was recorded: its stroke on the back of the
     * knee, saved as the knee, stays on the sheet. Read with the second map that stroke marks the back
     * of the knee, which the entry never named, and it would be left off as a stroke over an area the
     * person struck from the list.
     */
    @Test
    fun aFirstMapPaintingIsDrawnAsItWasRecorded() {
        val oldRow = JournalEntry(
            day = d(3, 8).toString(),
            occurredAt = at(d(3, 8), 20),
            kind = KIND_PAIN,
            title = "Pain",
            calories = 0.0,
            measurementPayloadJson = """{"schema":1,"regions":["KNEE:L"],"nrs":4,""" +
                """"strokes":[{"v":"B","r":18,"p":[300,620,370,620]}]}""",
        )
        val drawing = report(legs() + oldRow).present(ReportPart.PAIN)!!.painDrawing!!
        assertEquals(listOf(decodePainPayload(oldRow.measurementPayloadJson).strokes), drawing.entries)
        val stroke = drawing.entries.single().single()
        assertEquals(
            setOf(RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)),
            paintedRegions(listOf(stroke), PainMapVersion.V2),
        )
    }

    /** A period with no pain row has no section, whatever else was kept. */
    @Test
    fun noPainRowNoSection() {
        assertNull(report((1..31).map { kept(d(3, it)) }).present(ReportPart.PAIN))
        // "No pain today" is a row, and the section says the answer.
        val none = JournalEntry(
            day = d(3, 4).toString(),
            occurredAt = at(d(3, 4), 9),
            kind = KIND_PAIN,
            title = "No pain",
            calories = 0.0,
            measurementPayloadJson = encodePainPayload(PainReport(none = true)),
        )
        assertEquals(
            answered(0, 1),
            report(listOf(none)).present(ReportPart.PAIN)!!.row(res(R.string.report_pain_any))!!.value,
        )
    }

    // --- work -------------------------------------------------------------------------------------

    private fun work(day: LocalDate, fromHour: Int, toHour: Int, typed: Boolean = false) =
        workEntry(listOf(WorkSegment(at(day, fromHour), at(day, toHour))), "Work", zone, typed = typed)!!

    /** Nine hours on every weekday of March and a Saturday of eleven. */
    private fun weekdays() = (1..31).map { d(3, it) }.mapNotNull { day ->
        when {
            day == d(3, 14) -> work(day, 8, 19)
            day.dayOfWeek.value <= 5 -> work(day, 9, 18)
            else -> null
        }
    } + (1..31).map { kept(d(3, it)) }

    @Test
    fun workPrintsItsWeeksDaysAndRestsFromWorkStats() {
        val section = report(weekdays()).present(ReportPart.WORK)!!
        fun of(count: Int, plural: Int, total: Int) =
            res(R.string.report_count_of, count, ReportText.Quantity(plural, total))

        assertEquals(
            ReportText.Quantity(R.plurals.count_days, 23),
            section.row(res(R.string.report_work_days_tracked))!!.value,
        )
        // Whole Monday-to-Sunday weeks in March 2026: the 2nd to the 29th. 45 h, and the one with the Saturday 56 h.
        val median = section.row(res(R.string.report_work_week_median))!!
        assertEquals(res(R.string.report_hours_value, "45"), median.value)
        assertEquals(of(1, R.plurals.report_counted_weeks, 4), section.row(res(R.string.report_work_weeks_48))!!.value)
        assertEquals(of(1, R.plurals.report_counted_weeks, 4), section.row(res(R.string.report_work_weeks_55))!!.value)
        assertEquals(
            of(0, R.plurals.report_counted_weeks, 4),
            section.row(res(R.string.report_work_weeks_no_day_off))!!.value,
        )
        val longest = section.row(res(R.string.report_work_longest_day))!!
        assertEquals(res(R.string.measurement_sleep_value, 11, 0), longest.value)
        assertEquals(ReportText.Day(d(3, 14)), longest.note)
        assertEquals(of(1, R.plurals.report_work_days, 23), section.row(res(R.string.report_work_long_days))!!.value)
        assertEquals(
            res(R.string.report_work_long_days_note, 10),
            section.row(res(R.string.report_work_long_days))!!.note,
        )
        // The usual days off known from the first day the timer was used, the 2nd: the 1st is before it.
        assertEquals(of(1, R.plurals.count_days, 8), section.row(res(R.string.report_work_days_off))!!.value)
        // Nine hours each, a pause nobody pressed: every session is a long stretch.
        assertEquals(
            of(23, R.plurals.report_work_sessions, 23),
            section.row(res(R.string.report_work_long_stretches, 6))!!.value,
        )
        assertEquals(
            res(R.string.report_work_pauses_pressed),
            section.row(res(R.string.report_work_long_stretches, 6))!!.note,
        )
        assertEquals(R.string.report_caveat_work, section.caveatRes)
        assertEquals(3, section.footnotes.size)
        assertEquals(listOf(ChartStyle.BARS, ChartStyle.LINE), section.charts.first().series.map { it.style })
    }

    /** The long-day line is the person's own setting, and the row says so. */
    @Test
    fun theLongDayIsThePersonsOwnSetting() {
        val section = report(weekdays(), longDayMinutes = 8 * 60).present(ReportPart.WORK)!!
        val long = section.row(res(R.string.report_work_long_days))!!
        assertEquals(res(R.string.report_count_of, 23, ReportText.Quantity(R.plurals.report_work_days, 23)), long.value)
        assertEquals(res(R.string.report_work_long_days_note, 8), long.note)
    }

    /** Under four counted weeks the week-shaped rows wait, and say how many there were. */
    @Test
    fun fewCountedWeeksAreNotShown() {
        val twoWeeks = (2..15).map { d(3, it) }.filter { it.dayOfWeek.value <= 5 }.map { work(it, 9, 17) } +
            (2..15).map { kept(d(3, it)) }
        val section = report(twoWeeks).present(ReportPart.WORK)!!

        assertEquals(
            res(R.string.report_work_not_shown, ReportText.Quantity(R.plurals.report_counted_weeks, 2), 4),
            section.row(res(R.string.report_work_week_median))!!.value,
        )
        assertNull(section.row(res(R.string.report_work_weeks_48)))
        assertNull(section.row(res(R.string.report_work_evenings)))
        // Ten sessions are enough for the stretches; eight pairs of days are not enough for the rests.
        assertEquals(
            res(R.string.report_work_not_shown, ReportText.Quantity(R.plurals.report_work_pairs, 8), 10),
            section.row(res(R.string.report_work_short_rests, 11))!!.value,
        )
        assertEquals(1, section.charts.size)
        assertEquals(2, section.footnotes.size)
    }

    /** Typed and capped sessions are counted apart, and a capped one is in none of the hours. */
    @Test
    fun typedAndCappedSessionsAreCountedApart() {
        val capped = workEntry(listOf(WorkSegment(at(d(3, 20), 8), at(d(3, 21), 0))), "Work", zone, capped = true)!!
        val section = report(weekdays() + work(d(3, 21), 10, 12, typed = true) + capped).present(ReportPart.WORK)!!

        assertEquals(ReportText.Plain("1"), section.row(res(R.string.report_work_typed))!!.value)
        assertEquals(ReportText.Plain("1"), section.row(res(R.string.report_work_capped, 16))!!.value)
        // The capped Friday is unknown, and the typed Saturday is a day with work.
        assertEquals(
            ReportText.Quantity(R.plurals.count_days, 23),
            section.row(res(R.string.report_work_days_tracked))!!.value,
        )
    }

    /** A person who set no usual day off has none to have worked on: no "0 of 0 days" row. */
    @Test
    fun noUsualDayOffNoDaysOffRow() {
        val section = report(weekdays(), workDaysOff = emptySet()).present(ReportPart.WORK)!!
        assertNull(section.row(res(R.string.report_work_days_off)))
        assertNotNull(section.row(res(R.string.report_work_evenings)))
    }

    @Test
    fun noSessionNoWorkSection() {
        assertNull(report((1..31).map { kept(d(3, it)) }).present(ReportPart.WORK))
    }

    // --- mornings ---------------------------------------------------------------------------------

    private fun morning(day: LocalDate, answer: MorningErection, hour: Int = 7) =
        morningCheckEntry("Morning", day, MorningCheck(answer, at(day, hour), WakeSource.TYPED))

    /** Twenty mornings: twelve firm, four partly, four none, and three not noticed. */
    private fun mornings() = (1..20).map { i ->
        morning(
            d(3, i),
            when {
                i <= 12 -> MorningErection.FIRM
                i <= 16 -> MorningErection.PARTLY
                else -> MorningErection.NONE
            },
        )
    } + (21..23).map { morning(d(3, it), MorningErection.DIDNT_NOTICE) }

    /** Counts with no morning listed, "didn't notice" left out, and the usual waking with its source and spread. */
    @Test
    fun morningsAreCountedNotListed() {
        val built = report(mornings())
        val section = built.present(ReportPart.MORNING_ERECTIONS)!!
        val answered = ReportText.Quantity(R.plurals.report_mornings_answered, 20)

        assertEquals(ReportText.Plain("20"), section.row(res(R.string.report_morning_answered))!!.value)
        assertEquals(
            res(R.string.report_count_of, 16, answered),
            section.row(res(R.string.report_morning_firm_or_partly))!!.value,
        )
        assertEquals(
            res(R.string.report_count_of, 12, answered),
            section.row(res(R.string.sexual_morning_firm))!!.value,
        )
        assertEquals(res(R.string.report_count_of, 4, answered), section.row(res(R.string.sexual_morning_none))!!.value)
        assertEquals(ReportText.Plain("3"), section.row(res(R.string.sexual_morning_didnt_notice))!!.value)
        val waking = section.row(res(R.string.report_morning_waking))!!
        assertEquals(ReportText.Plain("07:00"), waking.value)
        // Every waking set by hand, none of them the moment of answering: all twenty-three, and said so.
        val wakingNote = waking.note!!.flattened()
        assertTrue(ReportText.Quantity(R.plurals.report_morning_wakings, 23) in wakingNote)
        assertTrue(res(R.string.report_morning_waking_from_typed) in wakingNote)
        assertFalse(section.texts().any { it is ReportText.Day })
        assertTrue(section.appendix.isEmpty())
        assertEquals(1, section.charts.size)
        assertEquals(2, section.footnotes.size)
        // Its own paragraph at the foot, and not the one about sexual activity; the preview says the same.
        assertTrue(res(R.string.report_note_sensitive_morning) in built.notes)
        assertFalse(res(R.string.report_note_sensitive) in built.notes)
        assertTrue(built.carriesMornings)
        // Every part is ticked here, so the empty section's heading is printed: the preview says so.
        assertTrue(built.carriesSexualActivity)
    }

    /**
     * No single morning carries a date anywhere on the sheet. The dates that do go with the mornings
     * are the weeks of the chart, each a share of at least three answers, and the two stretches of the
     * changes section — which is what the question, the caveat and the foot of the sheet say, and
     * not "no dates".
     */
    @Test
    fun noSingleMorningIsDated() {
        val quarter = d(1, 1)..d(3, 31)
        // Every morning from the 1st of January to the 24th of March, firm through January and none
        // after, then two answers in the last week: too few for its bar.
        val rows = generateSequence(quarter.start) { it.plusDays(1) }.takeWhile { it <= d(3, 24) }.map { day ->
            morning(day, if (day.monthValue == 1) MorningErection.FIRM else MorningErection.NONE)
        }.toList() + morning(d(3, 30), MorningErection.NONE) + morning(d(3, 31), MorningErection.NONE)
        val built = report(rows, period = quarter)

        val section = built.present(ReportPart.MORNING_ERECTIONS)!!
        assertFalse(section.texts().any { it is ReportText.Day })
        // A bar a week, counted back from the 31st; the week of the 25th to the 31st had two answers.
        val bars = section.charts.single().series.single().points
        assertEquals((12 downTo 1).map { d(3, 31).minusWeeks(it.toLong()) }, bars.map { it.day })

        // The changes section: the thirds of the quarter, and the share in each as two counts.
        val changes = built.present(ReportPart.CHANGES)!!
        val morning = changes.row(res(R.string.report_changes_morning))!!
        assertTrue(listOfNotNull(morning.value, morning.note).flatMap { it.flattened() }.none { it is ReportText.Day })
        assertTrue(
            res(R.string.report_count_of, 30, ReportText.Quantity(R.plurals.report_mornings_answered, 30)) in
                morning.value.flattened(),
        )
        assertEquals(
            setOf(d(1, 1), d(1, 30), d(3, 2), d(3, 31)),
            changes.texts().filterIsInstance<ReportText.Day>().map { it.day }.toSet(),
        )
    }

    /** Under fourteen answered mornings the shares and the chart wait, and the row says for how many. */
    @Test
    fun fewMorningsAreNotSummarised() {
        val section = report(mornings().take(13)).present(ReportPart.MORNING_ERECTIONS)!!

        assertEquals(
            ReportText.Quantity(R.plurals.report_morning_too_few, 14),
            section.row(res(R.string.report_morning_firm_or_partly))!!.value,
        )
        assertNull(section.row(res(R.string.sexual_morning_firm)))
        assertTrue(section.charts.isEmpty())
    }

    /** A man's section only, ticked or not: on a woman's sheet there is nothing to print. */
    @Test
    fun theMorningsAreNeverOnAnybodyElsesSheet() {
        val hers = report(mornings(), profile = man.copy(sex = "FEMALE"))
        assertNull(hers.present(ReportPart.MORNING_ERECTIONS))
        assertFalse(ReportPart.MORNING_ERECTIONS in reportPartsFor("FEMALE"))
        assertFalse(res(R.string.report_note_sensitive_morning) in hers.notes)
    }

    /** Never by default, never by a preset: the urologist suggests it and still asks. */
    @Test
    fun theMorningsArriveOnlyByTheirOwnTick() {
        val default = doctorReport(
            sources = ReportSources(man, mornings(), emptyList()),
            request = ReportRequest(from = march.start, to = march.endInclusive),
            today = march.endInclusive,
            zone = zone,
        )
        assertNull(default.present(ReportPart.MORNING_ERECTIONS))
        assertFalse(default.carriesMornings)
        DoctorSpecialty.entries.forEach { specialty ->
            assertFalse("$specialty", ReportPart.MORNING_ERECTIONS in preselectedParts(specialty, "MALE"))
        }
        assertTrue(ReportPart.MORNING_ERECTIONS in suggestedParts(DoctorSpecialty.UROLOGIST, "MALE"))
    }

    // --- the doctors ------------------------------------------------------------------------------

    /** The psychologist opens with mood, sleep, work, drinking, movement and what is taken; nothing sensitive. */
    @Test
    fun thePsychologistsPreset() {
        val ticked = preselectedParts(DoctorSpecialty.PSYCHOLOGIST, "MALE")
        assertEquals(
            setOf(
                ReportPart.PROBLEMS,
                ReportPart.MEDICATIONS,
                ReportPart.MOOD,
                ReportPart.SLEEP,
                ReportPart.WORK,
                ReportPart.SOCIAL,
                ReportPart.ACTIVITY,
                ReportPart.CHANGES,
            ),
            ticked,
        )
        assertEquals(listOf(ReportPart.PAIN, ReportPart.VISITS), suggestedParts(DoctorSpecialty.PSYCHOLOGIST, "FEMALE"))
        assertTrue(DoctorSpecialty.PSYCHOLOGIST.optional.none { it.sensitivity == DataSensitivity.SENSITIVE })
        assertEquals(3, DoctorSpecialty.PSYCHOLOGIST.defaultMonths)
        assertEquals(DoctorSpecialty.CUSTOM, DoctorSpecialty.entries.last())
        assertTrue(ReportPart.PAIN in DoctorSpecialty.GP.optional && ReportPart.WORK in DoctorSpecialty.GP.optional)
        assertTrue(ReportPart.PAIN in DoctorSpecialty.SPORTS.optional)
        assertTrue(ReportPart.WORK in DoctorSpecialty.SOMNOLOGIST.optional)
    }

    /** The work and pain sections sit where the sheet's order puts them: work after alcohol, pain after bathroom. */
    @Test
    fun theNewSectionsKeepTheSheetsOrder() {
        val order = ReportPart.entries
        assertEquals(order.indexOf(ReportPart.SOCIAL) + 1, order.indexOf(ReportPart.WORK))
        assertEquals(order.indexOf(ReportPart.BOWEL) + 1, order.indexOf(ReportPart.PAIN))
        assertEquals(ReportPart.BOWEL.group, ReportPart.PAIN.group)
        assertEquals(DataSensitivity.ROUTINE, ReportPart.PAIN.sensitivity)
        val built = report(legs() + weekdays())
        assertNotNull(built.present(ReportPart.PAIN))
        assertNotNull(built.present(ReportPart.WORK))
    }
}
