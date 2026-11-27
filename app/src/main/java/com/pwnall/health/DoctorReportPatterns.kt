package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// The three sections the pain map, the work timer and the morning check-in put on the doctor's sheet.
//
// Every figure is read from PainStats, WorkStats and MorningStats — the arithmetic the app's own cards
// read — so the sheet a doctor is handed cannot count a day differently from the phone it came from.
// Each section prints counts with what they are counts of, charts first as the rest of the sheet
// does, and no reading of them: no verdict, no band, no condition. Research about other people is a
// footnote with its source, and says it is about other people.

/**
 * Where it hurt and how often: every day a pain row stands on, per analysis group of the pain map,
 * over the days the diary was answered — never over every day of the period, since a day with
 * nothing written on it is unknown rather than a day without pain ([PainDiary]).
 *
 * The groups are the map's own coarse areas ([PainGroup]); one area on its own has too few days to
 * say anything of. Each prints its pain days of the days answered, the median and the highest score
 * on those days, the words used, the episodes ([PAIN_EPISODE_GAP_DAYS], the app's own heuristic) and
 * the days a painkiller was marked. Where [showSoreness] — the workouts go on the sheet — episodes
 * whose timing fits soreness after a logged session of that area's muscles are counted apart from
 * the others; without the workouts that fit would disclose a session the sheet leaves off, so the
 * episodes are counted together.
 *
 * A group recorded over more than [PAIN_SPAN_MONTHS] months says so with its first and last record,
 * and the footnote gives the classification's line as the reason for the line, never as a class the
 * app assigns. It is the one line that reaches back past the period, as the cycle section's last
 * period does: a pain recorded since January is not news of March, and a March sheet that said
 * nothing of January would be describing a new pain.
 *
 * Under the charts, the period's paintings on the body plates for [sex] ([painSheetDrawing]): where
 * the person marked the pain, as they marked it, which the groups above it cannot say.
 *
 * [journal] is the whole of it: an episode or the first record of an area can lie before the period.
 * Days after the period are not read — [PainDiary] reads up to a window's last day.
 */
internal fun painSection(
    journal: List<JournalEntry>,
    period: ClosedRange<LocalDate>,
    showSoreness: Boolean,
    zone: ZoneId,
    sex: String,
): ReportSection? {
    val rows = journal.filter { it.kind == KIND_PAIN && it.localDay()?.let { day -> day in period } == true }
    if (rows.isEmpty()) return null
    val diary = PainDiary(journal, zone)
    val all = diary.series(ALL_PAIN_AREAS, period)
    val groups = PainGroup.entries.map { diary.summary(it, period) }.filter { it.painDays > 0 }
    val answered = ReportText.Quantity(R.plurals.report_days_answered, all.answered.size)
    val sheetRows = buildList {
        add(ReportRow(res(R.string.report_pain_any), res(R.string.report_count_of, all.pain.size, answered)))
        groups.forEach { addAll(painGroupRows(it, showSoreness)) }
    }
    return ReportSection(
        part = ReportPart.PAIN,
        rows = sheetRows,
        completeness = Completeness(all.answered.size, dayCount(period), rows.size),
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_pain,
        charts = listOfNotNull(painWeekChart(all, period), painScoreChart(all, period)).sideBySide(),
        painDrawing = painSheetDrawing(rows, sex),
        footnotes = listOfNotNull(
            background(R.string.report_pain_background_span, R.string.report_pain_source_span)
                .takeIf { groups.any(PainGroupSummary::recordedOverThreeMonths) },
        ),
    )
}

private val ALL_PAIN_AREAS: Set<PainRegion> = PainRegion.entries.toSet()

/**
 * The paintings of the period's pain [rows], each entry's strokes as painted ([painShownStrokes]);
 * null where none of them has a stroke left to draw. An entry painted only on the head above the
 * plates, or only over areas it struck off, is not counted as drawn: nothing of it is.
 */
internal fun painSheetDrawing(rows: List<JournalEntry>, sex: String): PainSheetDrawing? {
    val entries = rows.map { row -> painShownStrokes(decodePainPayload(row.measurementPayloadJson)) }
        .filter { it.isNotEmpty() }
    return if (entries.isEmpty()) null else PainSheetDrawing(sex, entries)
}

/** One group's rows: its pain days with the scores, then the words, episodes, painkillers and span under it. */
private fun painGroupRows(group: PainGroupSummary, showSoreness: Boolean): List<ReportRow> = buildList {
    val answered = ReportText.Quantity(R.plurals.report_days_answered, group.answeredDays)
    val scores = listOfNotNull(
        group.medianNrs?.let { median -> res(R.string.report_pain_scores, median.pretty(), group.highestNrs ?: 0) },
        ReportText.Quantity(R.plurals.report_pain_rated_days, group.ratedDays).takeIf {
            group.ratedDays < group.painDays
        },
    )
    add(
        ReportRow(
            label = res(group.group.sheetLabelRes),
            value = res(R.string.report_count_of, group.painDays, answered),
            note = scores.takeIf { it.isNotEmpty() }?.let { ReportText.Join(it) },
        ),
    )
    // Most-used first, the enum's own order between equals, so two sheets of one diary read alike.
    val words = group.types.entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).map { (type, days) ->
        res(R.string.report_changes_item, res(type.shortRes), ReportText.Quantity(R.plurals.count_days, days))
    }
    if (words.isNotEmpty()) {
        add(ReportRow(res(R.string.report_pain_words), ReportText.Join(words, LIST_COMMA), detail = true))
    }
    val episodes = if (showSoreness) group.episodes else group.episodes + group.sorenessEpisodes
    add(
        ReportRow(
            label = res(R.string.report_pain_episodes),
            value = plain(episodes.toString()),
            note = res(R.string.report_pain_episodes_rule, PAIN_EPISODE_GAP_DAYS),
            detail = true,
        ),
    )
    if (showSoreness && group.sorenessEpisodes > 0) {
        add(
            ReportRow(
                label = res(R.string.report_pain_soreness),
                value = plain(group.sorenessEpisodes.toString()),
                note = res(
                    R.string.report_pain_soreness_days,
                    ReportText.Quantity(R.plurals.count_days, group.sorenessDays),
                ),
                detail = true,
            ),
        )
    }
    // A painkiller left unmarked is not one not taken: only the days it was marked are a count.
    if (group.painkillerDays > 0) {
        add(ReportRow(res(R.string.report_pain_painkiller), plain(group.painkillerDays.toString()), detail = true))
    }
    val first = group.firstRecorded
    val last = group.lastRecorded
    if (group.recordedOverThreeMonths && first != null && last != null) {
        add(
            ReportRow(
                label = res(R.string.report_pain_span, PAIN_SPAN_MONTHS.toInt()),
                value = res(R.string.report_period_value, ReportText.Day(first), ReportText.Day(last)),
                note = res(R.string.report_pain_span_note),
                detail = true,
            ),
        )
    }
}

/**
 * Of the days answered in each whole week, how many had pain in any area — the bars — and beside them
 * how many were answered, since two days of pain in a week of two answers and in a week of seven are
 * different weeks. A week with no day answered is not drawn: nothing is known about it. Only the weeks
 * the period holds whole: a short first week's bar would read as a lighter week ([wholeWeeks]).
 */
private fun painWeekChart(all: PainDaySeries, period: ClosedRange<LocalDate>): ReportChart? {
    val weeks = byWeek(all.answered.associateWith { it in all.pain }, period.endInclusive).wholeWeeks(period)
    return chartOf(
        period,
        res(R.string.report_changes_per_week, res(R.string.unit_days)),
        listOf(
            ChartSeries(
                weeks.mapValues { (_, days) -> days.count { it }.toDouble() }.points(),
                ChartStyle.BARS,
                res(R.string.report_chart_pain_days),
            ),
            ChartSeries(
                weeks.mapValues { (_, days) -> days.size.toDouble() }.points(),
                ChartStyle.DOTS,
                res(R.string.report_chart_days_answered),
            ),
        ),
        res(R.string.report_chart_pain_weeks),
    )?.copy(yCeiling = CHART_WEEK_DAYS.toDouble())
}

/** The day's highest score, a dot a pain day that was scored, on the scale's own 1–10 ([PAIN_NRS_RANGE]). */
private fun painScoreChart(all: PainDaySeries, period: ClosedRange<LocalDate>): ReportChart? = chartOf(
    period,
    res(R.string.report_chart_pain_scale),
    listOf(
        ChartSeries(
            all.pain.mapNotNull { (day, facts) -> facts.nrs?.let { day to it.toDouble() } }.toMap().points(),
            ChartStyle.DOTS,
        ),
    ),
    res(R.string.report_chart_pain_highest),
)?.copy(yFloor = PAIN_NRS_RANGE.first.toDouble(), yCeiling = PAIN_NRS_RANGE.last.toDouble())

/** The analysis groups' names on paper. */
private val PainGroup.sheetLabelRes: Int
    get() = when (this) {
        PainGroup.HEAD -> R.string.report_pain_group_head
        PainGroup.NECK_SHOULDERS -> R.string.report_pain_group_neck_shoulders
        PainGroup.LOWER_BACK -> R.string.report_pain_group_lower_back
        PainGroup.ARMS_HANDS -> R.string.report_pain_group_arms_hands
        PainGroup.CHEST_ABDOMEN -> R.string.report_pain_group_chest_abdomen
        PainGroup.HIPS_LEGS -> R.string.report_pain_group_hips_legs
    }

/**
 * The work timer over the period, as [workSummary] counts it: days with work, the counted weeks' median
 * and range and how many reached 48 and 55 hours, the longest day, the long days by the person's own
 * setting, work after 20:00, usual days off with work, the longest run, short rests between work days,
 * long stretches without a pressed pause, and the sessions typed in afterwards or stopped at the cap,
 * counted apart.
 *
 * The week-shaped figures wait for [WORK_SHEET_MIN_WEEKS] counted weeks, the rests for
 * [WORK_SHEET_MIN_PAIRS] pairs of work days and the stretches for [WORK_SHEET_MIN_SESSIONS] timed
 * sessions — the floors the research behind them was read with — and below one the row says how many
 * there were instead. Nothing is set against a limit: 48 and 55 are the bands the research counts in,
 * and the footnote on the 55 says whose band it is.
 *
 * Read from the whole journal, since the first day the timer was used decides which earlier days are
 * unknown rather than days off. [ReportSources.workDaysOff] and [ReportSources.longDayMinutes] are the
 * person's settings; the long-day row says the threshold is theirs.
 */
internal fun workSection(sources: ReportSources, period: ClosedRange<LocalDate>, zone: ZoneId): ReportSection? {
    val summary = workSummary(sources.entries, period, zone, sources.workDaysOff, sources.longDayMinutes)
    val sessions = summary.timedSessions + summary.typedSessions + summary.cappedSessions
    if (sessions == 0) return null
    val weekly = summary.weeks.size >= WORK_SHEET_MIN_WEEKS
    val workDays = ReportText.Quantity(R.plurals.report_work_days, summary.workDays)
    val rows = buildList {
        add(
            ReportRow(
                label = res(R.string.report_work_days_tracked),
                value = ReportText.Quantity(R.plurals.count_days, summary.workDays),
                note = ReportText.Quantity(
                    R.plurals.report_work_sessions,
                    summary.timedSessions + summary.typedSessions,
                ),
            ),
        )
        if (weekly) addAll(workWeekRows(summary)) else add(workWeeksNotShown(summary.weeks.size))
        summary.dailyMinutes.maxByOrNull { it.value }?.let { (day, minutes) ->
            add(ReportRow(res(R.string.report_work_longest_day), hoursAndMinutes(minutes), ReportText.Day(day)))
        }
        add(
            ReportRow(
                label = res(R.string.report_work_long_days),
                value = res(R.string.report_count_of, summary.longDays, workDays),
                note = res(R.string.report_work_long_days_note, sources.longDayMinutes / MINUTES_AN_HOUR),
            ),
        )
        if (weekly) addAll(workPatternRows(summary, workDays))
        addAll(workRestRows(summary))
    }
    return ReportSection(
        part = ReportPart.WORK,
        rows = rows,
        completeness = Completeness(summary.workDays, dayCount(period), sessions),
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_work,
        charts = listOfNotNull(workWeekChart(summary, period).takeIf { weekly }, workDayChart(summary, period))
            .sideBySide(),
        footnotes = listOfNotNull(
            background(R.string.report_work_background_hours, R.string.report_work_source_hours).takeIf { weekly },
            background(R.string.report_work_background_recall, R.string.report_work_source_recall),
            background(R.string.report_work_background_load, R.string.report_work_source_load),
        ),
    )
}

/** Counted weeks before the week-shaped rows are printed at all, as the research reads them. */
internal const val WORK_SHEET_MIN_WEEKS = 4

/** Pairs of consecutive work days before the short rests are counted. */
internal const val WORK_SHEET_MIN_PAIRS = 10

/** Timed sessions before the long stretches without a pause are counted. */
internal const val WORK_SHEET_MIN_SESSIONS = 10

private const val MINUTES_AN_HOUR = 60

/** The median week with its range, and the weeks at 48 and 55 hours and with no day off, of the counted weeks. */
private fun workWeekRows(summary: WorkSummary): List<ReportRow> {
    val counted = ReportText.Quantity(R.plurals.report_counted_weeks, summary.weeks.size)
    fun of(weeks: Int) = res(R.string.report_count_of, weeks, counted)
    val range = summary.weekHoursRange
    return listOfNotNull(
        summary.medianWeekHours?.let { median ->
            ReportRow(
                label = res(R.string.report_work_week_median),
                value = hours(median),
                note = ReportText.Join(
                    listOfNotNull(
                        range?.let { res(R.string.report_work_week_range, hours(it.start), hours(it.endInclusive)) },
                        counted,
                    ),
                ),
            )
        },
        ReportRow(res(R.string.report_work_weeks_48), of(summary.weeksAtLeast48), detail = true),
        ReportRow(res(R.string.report_work_weeks_55), of(summary.weeksAtLeast55), detail = true),
        ReportRow(res(R.string.report_work_weeks_no_day_off), of(summary.weeksWithoutADayOff), detail = true),
    )
}

/** In place of the week-shaped rows: how many weeks were counted, and how many they wait for. */
private fun workWeeksNotShown(weeks: Int): ReportRow = ReportRow(
    label = res(R.string.report_work_week_median),
    value = res(
        R.string.report_work_not_shown,
        ReportText.Quantity(R.plurals.report_counted_weeks, weeks),
        WORK_SHEET_MIN_WEEKS,
    ),
    note = res(R.string.report_work_counted_week, WORK_COUNTED_WEEK_DAYS),
)

/**
 * Evenings, usual days off and the longest run: the figures that read the weeks' shape. The days off
 * only where some are known: a person who set none has no usual day off to have worked on, and "0 of
 * 0 days" would read as a finding.
 */
private fun workPatternRows(summary: WorkSummary, workDays: ReportText): List<ReportRow> = listOfNotNull(
    ReportRow(res(R.string.report_work_evenings), res(R.string.report_count_of, summary.eveningDays, workDays)),
    summary.usualDaysOff.takeIf { it.of > 0 }?.let { off ->
        ReportRow(
            label = res(R.string.report_work_days_off),
            value = res(R.string.report_count_of, off.days, ReportText.Quantity(R.plurals.count_days, off.of)),
            note = res(R.string.report_work_days_off_note, WORK_DAY_OFF_MINUTES.toInt()),
        )
    },
    summary.longestRun?.let { run ->
        val days = ReportText.Quantity(R.plurals.count_days, run.days)
        ReportRow(
            label = res(R.string.report_work_run),
            // "At least": a day next to it is unknown, so the run may have gone on.
            value = if (run.atLeast) res(R.string.report_work_at_least, days) else days,
            note = res(R.string.report_period_value, ReportText.Day(run.from), ReportText.Day(run.to)),
        )
    },
)

/** Short rests, long stretches, and the sessions typed in or stopped at the cap, each only where there are some. */
private fun workRestRows(summary: WorkSummary): List<ReportRow> = listOfNotNull(
    summary.gapPairs.takeIf { it > 0 }?.let { pairs ->
        val counted = ReportText.Quantity(R.plurals.report_work_pairs, pairs)
        ReportRow(
            label = res(R.string.report_work_short_rests, WORK_SHORT_GAP_HOURS.toInt()),
            value = if (pairs >= WORK_SHEET_MIN_PAIRS) {
                res(R.string.report_count_of, summary.shortGaps, counted)
            } else {
                res(R.string.report_work_not_shown, counted, WORK_SHEET_MIN_PAIRS)
            },
        )
    },
    summary.timedSessions.takeIf { it > 0 }?.let { timed ->
        val counted = ReportText.Quantity(R.plurals.report_work_sessions, timed)
        ReportRow(
            label = res(R.string.report_work_long_stretches, WORK_LONG_STRETCH_HOURS.toInt()),
            value = if (timed >= WORK_SHEET_MIN_SESSIONS) {
                res(R.string.report_count_of, summary.longStretchSessions, counted)
            } else {
                res(R.string.report_work_not_shown, counted, WORK_SHEET_MIN_SESSIONS)
            },
            note = res(R.string.report_work_pauses_pressed),
        )
    },
    summary.typedSessions.takeIf { it > 0 }?.let {
        ReportRow(
            res(R.string.report_work_typed),
            plain(it.toString()),
            res(R.string.report_work_typed_note),
            detail = true,
        )
    },
    summary.cappedSessions.takeIf { it > 0 }?.let {
        ReportRow(
            label = res(R.string.report_work_capped, (WORK_CAP_MS / HOUR_MILLIS).toInt()),
            value = plain(it.toString()),
            note = res(R.string.report_work_capped_note),
            detail = true,
        )
    },
)

private const val HOUR_MILLIS = 3_600_000L

/**
 * Net hours of each counted week, a bar at the Sunday that ends it, and the person's own median across
 * them as a line — the week row's own figures, drawn.
 */
private fun workWeekChart(summary: WorkSummary, period: ClosedRange<LocalDate>): ReportChart? {
    val bars = summary.weeks.associate { it.monday.plusDays(CHART_WEEK_DAYS - 1) to it.hours }
    val median = summary.medianWeekHours
    val line = listOfNotNull(bars.keys.minOrNull(), bars.keys.maxOrNull()).distinct()
    return chartOf(
        period,
        res(R.string.report_changes_per_week, res(R.string.report_chart_hours)),
        listOfNotNull(
            ChartSeries(bars.points(), ChartStyle.BARS, res(R.string.report_chart_work_weeks)),
            median?.let {
                ChartSeries(line.associateWith { median }.points(), ChartStyle.LINE, res(R.string.report_median))
            },
        ),
        res(R.string.report_chart_work_weeks),
    )
}

/**
 * Net hours of each day with work, a dot a day, and through them the mean of the days worked in the
 * seven days up to each: a week with two days of work is the mean of those two, and a day off pulls
 * nothing towards nought — the sleep chart's reading of the nights logged.
 */
private fun workDayChart(summary: WorkSummary, period: ClosedRange<LocalDate>): ReportChart? {
    val daily = summary.dailyMinutes.mapValues { (_, minutes) -> minutes / MINUTES_AN_HOUR }
    val mean = daily.mapValues { (day, _) ->
        (0L until CHART_WEEK_DAYS).mapNotNull { back -> daily[day.minusDays(back)] }.average()
    }
    return chartOf(
        period,
        res(R.string.report_per_day_rate, res(R.string.report_chart_hours)),
        listOf(
            ChartSeries(daily.points(), ChartStyle.DOTS, res(R.string.report_chart_work_days)),
            ChartSeries(
                mean.points(),
                ChartStyle.LINE,
                ReportText.Quantity(R.plurals.report_chart_work_mean, CHART_WEEK_DAYS.toInt()),
            ),
        ),
        res(R.string.report_chart_work_days),
    )
}

/**
 * The morning check-ins over the period, counted and never listed morning by morning: the mornings
 * answered, how many of them firm or partly, firm and none, how many "didn't notice", and the usual
 * waking with its spread. The dates on the sheet are the weeks of the chart — a share of at least
 * [MORNING_CHART_WEEK_MIN] answers each — and, in the changes section, the stretches; the caveat, the
 * question that lets it on and the paragraph at the foot all say so.
 *
 * A man's own section, enforced where it is built as the pregnancy row is: a request carrying every
 * part must not be able to put it on anybody else's sheet.
 *
 * The shares wait for [MORNING_SHEET_MIN_ANSWERED] answered mornings, and the weekly chart with them —
 * a single morning says little, and a share of six would read as a finding. Under it the answered count
 * and the waking still print, with how many the shares wait for. "Didn't notice" and a morning without
 * an answer are unknown, and never counted as none.
 */
internal fun morningSection(
    sex: String,
    journal: List<JournalEntry>,
    period: ClosedRange<LocalDate>,
    zone: ZoneId,
): ReportSection? {
    if (sex != "MALE") return null
    val shares = morningShares(journal, period)
    val checkIns = shares.answered + shares.didntNotice
    if (checkIns == 0) return null
    val summarised = shares.answered >= MORNING_SHEET_MIN_ANSWERED
    return ReportSection(
        part = ReportPart.MORNING_ERECTIONS,
        rows = morningRows(shares, morningWakings(journal, period, zone)),
        completeness = Completeness(checkIns, dayCount(period), checkIns),
        provenance = listOf(Provenance(ProvenanceCategory.MANUAL)),
        caveatRes = R.string.report_caveat_morning,
        charts = listOfNotNull(morningChart(journal, period).takeIf { summarised }),
        footnotes = listOf(
            background(R.string.report_morning_background_sleep, R.string.report_morning_source_sleep),
            background(R.string.report_morning_background_survey, R.string.report_morning_source_survey),
        ),
    )
}

/** The answers as counts, the shares only from [MORNING_SHEET_MIN_ANSWERED], and the usual waking. */
private fun morningRows(shares: MorningShares, wakings: Wakings): List<ReportRow> {
    val answered = ReportText.Quantity(R.plurals.report_mornings_answered, shares.answered)
    val summarised = shares.answered >= MORNING_SHEET_MIN_ANSWERED
    return buildList {
        add(ReportRow(res(R.string.report_morning_answered), plain(shares.answered.toString())))
        if (summarised) {
            add(
                ReportRow(
                    res(R.string.report_morning_firm_or_partly),
                    res(R.string.report_count_of, shares.firmOrPartly, answered),
                ),
            )
            add(
                ReportRow(
                    res(MorningErection.FIRM.titleRes),
                    res(R.string.report_count_of, shares.firm, answered),
                    detail = true,
                ),
            )
            add(
                ReportRow(
                    res(MorningErection.NONE.titleRes),
                    res(R.string.report_count_of, shares.none, answered),
                    detail = true,
                ),
            )
        } else {
            add(
                ReportRow(
                    res(R.string.report_morning_firm_or_partly),
                    ReportText.Quantity(R.plurals.report_morning_too_few, MORNING_SHEET_MIN_ANSWERED),
                ),
            )
        }
        add(
            ReportRow(
                label = res(MorningErection.DIDNT_NOTICE.titleRes),
                value = plain(shares.didntNotice.toString()),
                note = res(R.string.report_left_out),
                detail = true,
            ),
        )
        wakingRow(wakings)?.let(::add)
    }
}

/**
 * The median waking of the check-ins, whatever their answer, with how many there were, where their
 * times came from and their spread — the standard deviation, as the sleep section reads the spread of
 * its wakings. A moment of answering is no waking and one source is read ([morningWakings]), so the
 * count can be under the mornings answered, and the source says which ones it is.
 */
private fun wakingRow(wakings: Wakings): ReportRow? = wakings.medianMinute?.let { median ->
    ReportRow(
        label = res(R.string.report_morning_waking),
        value = plain(clockText(median)),
        note = ReportText.Join(
            listOfNotNull(
                ReportText.Quantity(R.plurals.report_morning_wakings, wakings.count),
                when (wakings.source) {
                    // A phone picked up is not a recorded night's end, and the sheet does not say it is.
                    WakeSource.SLEEP -> res(
                        if (wakings.fromPhone) {
                            R.string.report_morning_waking_from_phone
                        } else {
                            R.string.report_morning_waking_from_sleep
                        },
                    )

                    WakeSource.TYPED -> res(R.string.report_morning_waking_from_typed)

                    null -> null
                },
                wakings.spreadMinutes?.let {
                    res(R.string.report_morning_spread, ReportText.Quantity(R.plurals.count_minutes, it))
                },
            ),
        ),
    )
}

/** Answered mornings before the shares are printed: two weeks of them, the app's own floor. */
internal const val MORNING_SHEET_MIN_ANSWERED = 14

/** Answered mornings a week needs before its bar is drawn. */
private const val MORNING_CHART_WEEK_MIN = 3

/**
 * Of the mornings answered in each week, the share firm or partly — a bar a week that holds at least
 * [MORNING_CHART_WEEK_MIN] answers, since a bar of one answer is 0 or 100 and says nothing.
 */
private fun morningChart(journal: List<JournalEntry>, period: ClosedRange<LocalDate>): ReportChart? {
    val answered = morningChecksByDay(journal).mapNotNull { (day, check) ->
        val date = day.toLocalDateOrNull()?.takeIf { it in period }
        if (date == null || check.answer.score == null) {
            null
        } else {
            date to (check.answer == MorningErection.FIRM || check.answer == MorningErection.PARTLY)
        }
    }.toMap()
    val weekly = byWeek(answered, period.endInclusive)
        .filterValues { it.size >= MORNING_CHART_WEEK_MIN }
        .mapValues { (_, week) -> week.count { it } * PER_CENT / week.size }
    return chartOf(
        period,
        res(R.string.report_chart_share_mornings),
        listOf(ChartSeries(weekly.points(), ChartStyle.BARS)),
        res(R.string.report_morning_firm_or_partly),
    )?.copy(yFloor = 0.0, yCeiling = PER_CENT)
}

/** A footnote: published research about other people, and where it was published. */
private fun background(textRes: Int, sourceRes: Int): ReportText =
    res(R.string.report_changes_background, res(textRes), res(sourceRes))

/** "42 h", to a tenth where there is one. */
private fun hours(value: Double): ReportText = res(R.string.report_hours_value, value.pretty())

/** "11 h 20 min", from minutes that may carry seconds. */
private fun hoursAndMinutes(minutes: Double): ReportText {
    val whole = minutes.toInt()
    return res(R.string.measurement_sleep_value, whole / MINUTES_AN_HOUR, whole % MINUTES_AN_HOUR)
}

/** Days in the period, both ends counted. */
private fun dayCount(period: ClosedRange<LocalDate>): Int =
    (ChronoUnit.DAYS.between(period.start, period.endInclusive) + 1).toInt()

private fun res(id: Int, vararg args: Any): ReportText = ReportText.Res(id, args.toList())

private fun plain(text: String): ReportText = ReportText.Plain(text)
