package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the pain record adds up to. The rules under test: a day with nothing written is unknown and
 * never pain-free; a "no pain today" is an answer; a start needs an answered day before it; an
 * episode merges gaps of two days and not three; soreness after training is counted apart; the
 * steps before a start are the three days before the first day it can have begun and never a day it
 * hurt; a start is set against its own month; a workout is one session however many exercises it
 * holds; the work timer's own row answers nothing.
 */
class PainStatsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun d(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun side(region: PainRegion) = RegionSide(region, if (region.paired) BodySide.LEFT else BodySide.CENTRE)

    private fun pain(
        day: LocalDate,
        vararg regions: PainRegion,
        nrs: Int? = null,
        types: List<PainType> = emptyList(),
        onset: PainOnset? = null,
        onsetAt: Long? = null,
        painkiller: Boolean = false,
        same: Long? = null,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(
            PainReport(
                regions = regions.map(::side),
                types = types,
                nrs = nrs,
                onset = onset,
                onsetAt = onsetAt,
                painkiller = painkiller,
                same = same,
            ),
        ),
    )

    private fun noPain(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 9),
        kind = KIND_PAIN,
        title = "No pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(PainReport(none = true)),
    )

    /** Any row written by hand: what makes a day kept. */
    private fun kept(day: LocalDate, source: String? = null, synced: String? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 12),
        kind = "WATER",
        title = "Water",
        calories = 0.0,
        measurementSource = source,
        healthConnectRecordKey = synced,
    )

    private fun mood(day: LocalDate, energy: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 21),
        kind = "MOOD",
        title = "Mood",
        calories = 0.0,
        moodScore = 3,
        energyScore = energy,
    )

    private fun session(day: LocalDate, code: String = "LEG_CURL") = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 18),
        kind = "ACTIVITY",
        title = "Leg curl",
        calories = 0.0,
        activityCode = code,
    )

    private fun morningOnly(day: LocalDate) =
        morningCheckEntry("Morning", day, MorningCheck(MorningErection.FIRM, at(day, 7), WakeSource.TYPED))

    private fun work(day: LocalDate, fromHour: Int, toHour: Int) =
        workEntry(listOf(WorkSegment(at(day, fromHour), at(day, toHour))), "Work", zone)!!

    private fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

    private val march = d(3, 1)..d(3, 31)

    private val aching = listOf(PainType.ACHING)

    @Test
    fun aDayIsAnsweredWhenKeptByHandOrWhenItHoldsAPainRowOfEitherKind() {
        val diary = PainDiary(
            listOf(
                kept(d(3, 1)),
                kept(d(3, 2), source = SOURCE_MANUAL),
                noPain(d(3, 3)),
                pain(d(3, 4), PainRegion.NECK),
                // A morning check-in alone is sexual data and keeps nothing; a synced row is no diary.
                morningOnly(d(3, 5)),
                kept(d(3, 6), source = "HEALTH_CONNECT", synced = "hc-1"),
                kept(d(3, 7), synced = "hc-2"),
            ),
            zone,
        )
        assertEquals(setOf(d(3, 1), d(3, 2), d(3, 3), d(3, 4)), diary.answered)
        val series = diary.series(PainGroup.NECK_SHOULDERS.areas, march)
        assertEquals(setOf(d(3, 4)), series.pain.keys)
        assertTrue(series.answered.containsAll(series.pain.keys))
        // Nothing on the 8th: unknown, in neither set.
        assertFalse(d(3, 8) in series.answered)
    }

    /**
     * A painkiller noted in a chat on its own "for pain" says the day had pain: with no pain row the
     * day is unanswered, never one without pain — a "No pain today" still answers it — and a dose
     * taken for anything else, or one of a course, keeps its day as any row written by hand does.
     */
    @Test
    fun aPainkillerTakenForPainWithNoPainRowLeavesTheDayUnanswered() {
        val diary = PainDiary(
            listOf(
                oneOffDoseEntry("Нурофен", "1 tablet", d(3, 1), at(d(3, 1), 10), MedTakenFor.PAIN),
                oneOffDoseEntry("Нурофен", "1 tablet", d(3, 2), at(d(3, 2), 10), MedTakenFor.PAIN),
                noPain(d(3, 2)),
                oneOffDoseEntry("Melatonin", "1 tablet", d(3, 3), at(d(3, 3), 22), MedTakenFor.SLEEP),
                medTakenEntry(
                    PlannedDose(1, "Sertraline", "50 mg", MedFood.ANY, "540", 540, false),
                    d(3, 4),
                    at(d(3, 4), 9),
                ),
            ),
            zone,
        )
        assertEquals(setOf(d(3, 2), d(3, 3), d(3, 4)), diary.answered)
    }

    /**
     * "No pain today" in the morning and a pain in the evening: the pain stands, and the day is no
     * day without pain anywhere — the 3rd carries the 2nd's pain on rather than following a day
     * without it. A "no pain" alone answers its day without pain, and the pain after it starts anew.
     */
    @Test
    fun aPainOverrulesTheSameDaysNoPain() {
        val diary = PainDiary(
            listOf(
                pain(d(3, 2), PainRegion.TEMPLE, nrs = 5),
                noPain(d(3, 3)),
                pain(d(3, 3), PainRegion.TEMPLE, nrs = 5),
                noPain(d(3, 4)),
                pain(d(3, 5), PainRegion.TEMPLE, nrs = 5),
            ),
            zone,
        )
        val head = PainGroup.HEAD.areas
        val series = diary.series(head, march)
        assertEquals(setOf(d(3, 2), d(3, 3), d(3, 4), d(3, 5)), series.answered)
        assertEquals(setOf(d(3, 2), d(3, 3), d(3, 5)), series.pain.keys)
        assertEquals(setOf(d(3, 5)), diary.starts(head, march))
        val summary = diary.summary(PainGroup.HEAD, march)
        assertEquals(4, summary.answeredDays)
        assertEquals(3, summary.painDays)
    }

    /**
     * A Start and a Stop on the widget write nothing else down: a day holding only a work session is
     * not answered, so it is neither a day without pain nor the day before a pain that came back.
     */
    @Test
    fun aDayWithOnlyTheWorkTimerIsNotAnswered() {
        val diary = PainDiary(
            listOf(
                pain(d(3, 2), PainRegion.LOWER_BACK, nrs = 4),
                work(d(3, 3), 9, 17),
                pain(d(3, 4), PainRegion.LOWER_BACK, nrs = 4),
                kept(d(3, 5)),
                work(d(3, 5), 9, 17),
            ),
            zone,
        )
        assertEquals(setOf(d(3, 2), d(3, 4), d(3, 5)), diary.answered)
        // The 4th is read against the 2nd, not against a timer-only 3rd: the same pain, no start.
        assertTrue(diary.starts(PainGroup.LOWER_BACK.areas, march).isEmpty())
    }

    /** A day's figures pool its rows: the highest score, every word, a painkiller from any of them. */
    @Test
    fun aDayPoolsItsRowsPerGroup() {
        val diary = PainDiary(
            listOf(
                pain(d(3, 2), PainRegion.KNEE, PainRegion.TEMPLE, nrs = 4, types = listOf(PainType.ACHING)),
                pain(d(3, 2), PainRegion.CALF, nrs = 7, types = listOf(PainType.CRAMPING), painkiller = true),
            ),
            zone,
        )
        val legs = diary.series(PainGroup.HIPS_LEGS.areas, march).pain.getValue(d(3, 2))
        assertEquals(PainDay(7, setOf(PainType.ACHING, PainType.CRAMPING), true), legs)
        val head = diary.series(PainGroup.HEAD.areas, march).pain.getValue(d(3, 2))
        assertEquals(PainDay(4, setOf(PainType.ACHING), false), head)
        assertTrue(diary.series(PainGroup.LOWER_BACK.areas, march).pain.isEmpty())
    }

    @Test
    fun anEpisodeMergesGapsOfTwoDaysAndNotThree() {
        val diary = PainDiary(
            listOf(
                pain(d(3, 1), PainRegion.NECK),
                pain(d(3, 2), PainRegion.NECK),
                // Two days without: one episode.
                pain(d(3, 5), PainRegion.NECK),
                // Three days without: a new one.
                pain(d(3, 9), PainRegion.NECK),
            ),
            zone,
        )
        val episodes = diary.episodes(PainGroup.NECK_SHOULDERS.areas, march)
        assertEquals(listOf(d(3, 1) to d(3, 5), d(3, 9) to d(3, 9)), episodes.map { it.first to it.last })
        assertEquals(listOf(3, 1), episodes.map { it.painDays })
    }

    /**
     * A pain the person said is the same as one reported before neither starts again after days
     * answered without it nor opens an episode of its own, however long the gap.
     */
    @Test
    fun aFollowUpOfAnEarlierPainIsNeitherAStartNorANewEpisode() {
        val first = pain(d(3, 2), PainRegion.NECK, nrs = 5)
        val diary = PainDiary(
            listOf(kept(d(3, 1)), first) + days(d(3, 3), d(3, 6)).map { kept(it) } + listOf(
                pain(d(3, 7), PainRegion.NECK, nrs = 5, same = first.occurredAt),
                kept(d(3, 19)),
                // Not said to be the same: a pain of its own.
                pain(d(3, 20), PainRegion.NECK, nrs = 5),
            ),
            zone,
        )
        val neck = PainGroup.NECK_SHOULDERS.areas
        assertEquals(setOf(d(3, 2), d(3, 20)), diary.starts(neck, march))
        assertEquals(
            listOf(d(3, 2) to d(3, 7), d(3, 20) to d(3, 20)),
            diary.episodes(neck, march).map { it.first to it.last },
        )
    }

    /** An episode begun before the window is one episode; days after the window are not read. */
    @Test
    fun episodesReachBackButNotForward() {
        val diary = PainDiary(
            listOf(
                pain(d(2, 27), PainRegion.NECK),
                pain(d(3, 1), PainRegion.NECK),
                pain(d(3, 31), PainRegion.NECK),
                pain(d(4, 1), PainRegion.NECK),
            ),
            zone,
        )
        val episodes = diary.episodes(PainGroup.NECK_SHOULDERS.areas, march)
        assertEquals(listOf(d(2, 27) to d(3, 1), d(3, 31) to d(3, 31)), episodes.map { it.first to it.last })
        // A window that ends before the first pain has none.
        assertTrue(diary.episodes(PainGroup.NECK_SHOULDERS.areas, d(2, 1)..d(2, 26)).isEmpty())
    }

    @Test
    fun aStartIsANewPainOrARiseOfTwoFromTheLastAnsweredDay() {
        val diary = PainDiary(
            listOf(
                // The first answered day of the record: nothing says the pain was not already there.
                pain(d(3, 1), PainRegion.HEAD_FRONT, nrs = 3),
                pain(d(3, 2), PainRegion.HEAD_FRONT, nrs = 4),
                pain(d(3, 3), PainRegion.HEAD_FRONT, nrs = 6),
                // The 4th is unknown, so the 5th is read against the 3rd.
                pain(d(3, 5), PainRegion.HEAD_FRONT, nrs = 8),
                kept(d(3, 6)),
                pain(d(3, 7), PainRegion.HEAD_FRONT),
                // No score on the 7th to rise from.
                pain(d(3, 8), PainRegion.HEAD_FRONT, nrs = 9),
                kept(d(3, 9)),
                // Months old when first reported: not a start.
                pain(d(3, 10), PainRegion.HEAD_FRONT, onset = PainOnset.MONTHS_3_PLUS),
                kept(d(3, 11)),
                // One row a week old, another two days old: the newer one starts it.
                pain(d(3, 12), PainRegion.HEAD_FRONT, onset = PainOnset.WEEK),
                pain(d(3, 12), PainRegion.HEAD_BACK, onset = PainOnset.DAYS_2_3),
                kept(d(3, 13)),
                pain(d(3, 14), PainRegion.TEMPLE, onset = PainOnset.DAYS_2_3),
                kept(d(3, 15)),
                pain(d(3, 16), PainRegion.TEMPLE, onset = PainOnset.WEEK),
            ),
            zone,
        )
        val head = PainGroup.HEAD.areas
        assertEquals(setOf(d(3, 3), d(3, 5), d(3, 7), d(3, 12), d(3, 14)), diary.starts(head, march))
        // The day before a window still decides its first day.
        assertEquals(setOf(d(3, 5), d(3, 7), d(3, 12), d(3, 14)), diary.starts(head, d(3, 4)..d(3, 31)))
    }

    /** The legs' figures over March, soreness after training counted apart from the rest. */
    @Test
    fun aGroupSummaryCountsItsDaysScoresWordsEpisodesAndPainkillers() {
        val diary = PainDiary(
            listOf(
                kept(d(3, 1)),
                session(d(3, 2)),
                // Fourteen hours after the session, aching, over the thigh: the usual picture.
                pain(d(3, 3), PainRegion.THIGH_FRONT, nrs = 4, types = aching, onsetAt = at(d(3, 3), 8)),
                pain(d(3, 4), PainRegion.THIGH_FRONT, nrs = 3, types = aching),
                kept(d(3, 5)),
                pain(d(3, 10), PainRegion.KNEE, nrs = 6, types = listOf(PainType.SHARP), painkiller = true),
                pain(d(3, 11), PainRegion.KNEE, nrs = 7, types = listOf(PainType.SHARP), painkiller = true),
                pain(d(3, 13), PainRegion.KNEE, nrs = 5),
                pain(d(3, 17), PainRegion.KNEE, nrs = 2),
                // Another group's pain changes nothing here.
                pain(d(3, 18), PainRegion.NECK, nrs = 9),
            ),
            zone,
        )
        val summary = diary.summary(PainGroup.HIPS_LEGS, march)
        assertEquals(10, summary.answeredDays)
        assertEquals(6, summary.painDays)
        assertEquals(2, summary.sorenessDays)
        assertEquals(6, summary.ratedDays)
        assertEquals(4.5, summary.medianNrs!!, 0.0)
        assertEquals(7, summary.highestNrs)
        assertEquals(mapOf(PainType.ACHING to 2, PainType.SHARP to 2), summary.types)
        assertEquals(2, summary.episodes)
        assertEquals(1, summary.sorenessEpisodes)
        assertEquals(2, summary.painkillerDays)
        assertEquals(d(3, 3), summary.firstRecorded)
        assertEquals(d(3, 17), summary.lastRecorded)
        assertFalse(summary.recordedOverThreeMonths)
        val sore = diary.episodes(PainGroup.HIPS_LEGS.areas, march).first()
        assertEquals(SorenessFit.FITS, sore.fit)
        assertEquals(d(3, 2).toString(), sore.session?.day)
    }

    @Test
    fun aSummaryOfNothingIsEmptyNotNaN() {
        val summary = PainDiary(listOf(kept(d(3, 1))), zone).summary(PainGroup.HEAD, march)
        assertEquals(1, summary.answeredDays)
        assertEquals(0, summary.painDays)
        assertNull(summary.medianNrs)
        assertNull(summary.highestNrs)
        assertNull(summary.firstRecorded)
        assertFalse(summary.recordedOverThreeMonths)
    }

    /** Soreness is usually over within a week of the session: still recorded on day +8, it no longer fits. */
    @Test
    fun anEpisodeStillGoingPastTheSeventhDayAfterTheSessionDoesNotFit() {
        fun episodeTo(last: LocalDate): PainEpisode {
            val first = pain(d(3, 3), PainRegion.CALF, types = aching, onsetAt = at(d(3, 3), 8))
            val later = days(d(3, 4), last).map { pain(it, PainRegion.CALF, types = aching) }
            val rows = listOf(session(d(3, 2)), first) + later
            return PainDiary(rows, zone).episodes(PainGroup.HIPS_LEGS.areas, march).single()
        }
        assertEquals(SorenessFit.FITS, episodeTo(d(3, 9)).fit)
        val outlasted = episodeTo(d(3, 10))
        assertEquals(SorenessFit.NOT_TYPICAL, outlasted.fit)
        assertEquals(setOf(SorenessReason.LASTED_OVER_A_WEEK), outlasted.reasons)
    }

    @Test
    fun recordedOverThreeMonthsIsMoreThanThreeCalendarMonthsFirstToLast() {
        fun summary(last: LocalDate) = PainDiary(
            listOf(pain(d(1, 10), PainRegion.LOWER_BACK), pain(last, PainRegion.LOWER_BACK)),
            zone,
        ).summary(PainGroup.LOWER_BACK, d(4, 1)..d(4, 30))
        assertFalse(summary(d(4, 10)).recordedOverThreeMonths)
        assertTrue(summary(d(4, 11)).recordedOverThreeMonths)
        assertEquals(d(1, 10), summary(d(4, 11)).firstRecorded)
    }

    /**
     * Steps from the 7th to the 17th of March rise by a thousand a day in threes, so the three-day
     * means of the 10th to the 18th are 1,000 to 9,000 and the thirds cut at 3,000 and 7,000.
     */
    @Test
    fun movementBeforeAStartIsTheThreeDaysBeforeItInThirdsOfThePersonsOwnRange() {
        val steps = listOf(1000, 1000, 1000, 4000, 4000, 4000, 7000, 7000, 7000, 10_000, 10_000)
            .mapIndexed { i, count -> DaySteps(d(3, 7 + i).toString(), count.toLong()) }
        val rows = days(d(3, 9), d(3, 18)).map { kept(it) } +
            pain(d(3, 11), PainRegion.LOWER_BACK) +
            pain(d(3, 16), PainRegion.LOWER_BACK)
        val movement = PainDiary(rows, zone).movementBeforeStarts(PainGroup.LOWER_BACK.areas, d(3, 10)..d(3, 18), steps)
        assertEquals(2, movement.starts)
        assertEquals(7, movement.controls)
        assertEquals(3000.0, movement.lowestThirdUpTo!!, 0.0)
        assertEquals(7000.0, movement.highestThirdFrom!!, 0.0)
        assertEquals((2000.0 + 7000.0) / 2, movement.startSteps!!, 1e-9)
        assertEquals(DayShare(1, 2), movement.afterLowest.exposed)
        assertEquals(DayShare(2, 7), movement.afterLowest.other)
        assertEquals(DayShare(1, 2), movement.afterHighest.exposed)
        assertEquals(DayShare(2, 7), movement.afterHighest.other)
        assertFalse(movement.enough)
    }

    /**
     * A lower-back pain reported on the 20th that began "2–3 days ago" began on the 17th or the 18th:
     * its stretch is the 14th to the 16th, never the days it may already have hurt, and the 17th to
     * the 20th are no control days.
     */
    @Test
    fun aStartsStepsAreTheThreeDaysBeforeItCanHaveBegun() {
        val steps = days(d(3, 1), d(3, 31)).map { day ->
            DaySteps(day.toString(), if (day in d(3, 17)..d(3, 19)) 500L else 6_000L)
        }
        val rows = days(d(3, 1), d(3, 31)).map { kept(it) } +
            pain(d(3, 20), PainRegion.LOWER_BACK, onset = PainOnset.DAYS_2_3)
        val movement = PainDiary(rows, zone).movementBeforeStarts(PainGroup.LOWER_BACK.areas, march, steps)
        assertEquals(1, movement.starts)
        assertEquals(6_000.0, movement.startSteps!!, 0.0)
        // March less the four days the start can lie on, and less the 1st to the 3rd, which have no
        // three days of steps before them.
        assertEquals(24, movement.controls)
    }

    /**
     * Steps of 2,000 a day in January and March, 10,000 in February and April; the diary kept from
     * the 10th to the 25th of each month. Six lower-back starts in each of the low months and one in
     * each of the high: the starts follow a low stretch 12 times of 14, the control days 20 of 50 —
     * but each start's own month says 12 of 14 too, so the plain gap is the months' and nothing else.
     */
    @Test
    fun eachStartIsSetAgainstItsOwnMonth() {
        val months = listOf(1, 2, 3, 4)
        val low = setOf(12, 1, 3)
        val steps = days(LocalDate.of(2025, 12, 20), d(4, 30)).map { day ->
            DaySteps(day.toString(), if (day.monthValue in low) 2_000L else 10_000L)
        }
        val startDays = months.flatMap { month ->
            if (month in low) (12..22 step 2).map { d(month, it) } else listOf(d(month, 15))
        }
        val rows = months.flatMap { month -> (10..25).map { kept(d(month, it)) } } +
            startDays.map { pain(it, PainRegion.LOWER_BACK) }
        val movement = PainDiary(rows, zone).movementBeforeStarts(PainGroup.LOWER_BACK.areas, d(1, 1)..d(4, 30), steps)
        assertEquals(14, movement.starts)
        assertEquals(50, movement.controls)
        val startSteps = movement.startSteps!!
        assertEquals((12 * 2_000.0 + 2 * 10_000.0) / 14, startSteps, 1e-9)
        // Weighed month by month, the control days walked what the starts' own months did.
        assertEquals(startSteps, movement.controlSteps!!, 1e-9)
        assertEquals(DayShare(12, 14), movement.afterLowest.exposed)
        assertEquals(DayShare(20, 50), movement.afterLowest.other)
        assertEquals(12.0 / 14, movement.controlShareAfterLowest!!, 1e-9)
        // The plain counts are far apart, and their range is clear of nought; the months are not.
        assertTrue(movement.afterLowest.interval!!.start > 0.0)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, movement.afterLowest.verdict)
    }

    /**
     * Five lower-back starts in March, each after three days of 2,000 steps and on a day of 50,000:
     * the day itself is never read. April has no start, so its days are no controls.
     */
    @Test
    fun controlsAreTheAnsweredNonStartDaysOfTheStartsMonths() {
        val startDays = listOf(5, 10, 15, 20, 25).map { d(3, it) }
        val low = startDays.flatMap { s -> (1L..3L).map(s::minusDays) }.toSet()
        fun steps(missing: LocalDate? = null) = days(d(2, 20), d(4, 30)).filter { it != missing }.map { day ->
            val count = when (day) {
                in startDays -> 50_000L
                in low -> 2_000L
                else -> 8_000L
            }
            DaySteps(day.toString(), count)
        }
        val rows = days(d(3, 1), d(4, 30)).map { kept(it) } + startDays.map { pain(it, PainRegion.LOWER_BACK) }
        val diary = PainDiary(rows, zone)
        val window = d(3, 1)..d(4, 30)
        val movement = diary.movementBeforeStarts(PainGroup.LOWER_BACK.areas, window, steps())
        assertEquals(5, movement.starts)
        assertEquals(26, movement.controls)
        assertEquals(2000.0, movement.startSteps!!, 0.0)
        assertEquals(DayShare(5, 5), movement.afterLowest.exposed)
        assertTrue(movement.enough)
        // A day without a count before the last start leaves it out, and two controls with it.
        val gap = diary.movementBeforeStarts(PainGroup.LOWER_BACK.areas, window, steps(missing = d(3, 24)))
        assertEquals(4, gap.starts)
        assertEquals(24, gap.controls)
        assertFalse(gap.enough)
        // A day at nought is a counter nobody carried: the same as no count at all.
        val carried = steps().map { if (it.day == d(3, 24).toString()) it.copy(steps = 0L) else it }
        assertEquals(4, diary.movementBeforeStarts(PainGroup.LOWER_BACK.areas, window, carried).starts)
    }

    /**
     * Twenty blocks of four days: wound up on the first, calm on the rest, head pain on the second.
     * Same day: 0 of 20 tense days against 20 of 60 calm ones. The day after: 20 of 20 against 0 of
     * 59. The reverse: the pain was on the day before a calm day, 20 of 60, and never before a tense
     * one, 0 of 19 — notable too, so no direction may be worded.
     */
    @Test
    fun tensionIsReadOnTheSameDayTheDayAfterAndInReverse() {
        val start = d(1, 5)
        val rows = (0 until 20).flatMap { block ->
            val base = start.plusDays(4L * block)
            listOf(
                mood(base, Energy.WOUND_UP.score),
                mood(base.plusDays(1), Energy.CALM.score),
                pain(base.plusDays(1), PainRegion.HEAD_FRONT),
                mood(base.plusDays(2), Energy.CALM.score),
                mood(base.plusDays(3), Energy.CALM.score),
            )
        }
        val tension = PainDiary(rows, zone).tensionAndPain(PainGroup.HEAD.areas, start..start.plusDays(79))
        assertEquals(DayShare(0, 20), tension.sameDay.exposed)
        assertEquals(DayShare(20, 60), tension.sameDay.other)
        assertEquals(DayShare(20, 20), tension.nextDay.forward.exposed)
        assertEquals(DayShare(0, 59), tension.nextDay.forward.other)
        assertEquals(DayShare(0, 19), tension.nextDay.reverse.exposed)
        assertEquals(DayShare(20, 60), tension.nextDay.reverse.other)
        assertTrue(tension.nextDay.forward.notable)
        assertTrue(tension.nextDay.reverse.notable)
        assertFalse(tension.nextDay.directed)
    }

    /** Restless counts as tense, like wound up; a day with any such answer is tense. */
    @Test
    fun aDayWithAnyWoundUpOrRestlessAnswerIsTense() {
        val window = d(3, 1)..d(3, 31)
        val tense = tenseDays(
            listOf(
                mood(d(3, 1), Energy.RESTLESS.score),
                mood(d(3, 2), Energy.STEADY.score),
                mood(d(3, 2), Energy.WOUND_UP.score),
                mood(d(3, 3), Energy.STEADY.score),
                mood(d(4, 1), Energy.WOUND_UP.score),
            ),
            window,
        )
        assertEquals(mapOf(d(3, 1) to true, d(3, 2) to true, d(3, 3) to false), tense)
    }

    /**
     * The timer was first used on the 5th: the days kept before it are not days off. Six long days
     * — the first exactly at the setting — six shorter ones, and four days off. The 21st and 22nd
     * hold nothing but the timer: not answered, so in neither group.
     */
    @Test
    fun workDaysAndPainSetLongAgainstOtherWithDaysOffApart() {
        val rows = days(d(3, 1), d(3, 4)).map { kept(it) } +
            days(d(3, 5), d(3, 10)).flatMap { listOf(work(it, 8, 18), kept(it)) } +
            days(d(3, 11), d(3, 16)).flatMap { listOf(work(it, 9, 13), kept(it)) } +
            days(d(3, 17), d(3, 20)).map { kept(it) } +
            days(d(3, 21), d(3, 22)).map { work(it, 8, 18) } +
            listOf(pain(d(3, 6), PainRegion.NECK), pain(d(3, 12), PainRegion.NECK), pain(d(3, 18), PainRegion.NECK))
        val result = PainDiary(rows, zone).workAndPain(PainGroup.NECK_SHOULDERS.areas, march, longDayMinutes = 600)
        assertEquals(DayShare(1, 6), result.longVsOther.exposed)
        assertEquals(DayShare(1, 6), result.longVsOther.other)
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, result.longVsOther.verdict)
        assertEquals(DayShare(1, 4), result.daysOff)
    }

    @Test
    fun withoutTheTimerThereAreNoWorkDaysAndNoDaysOff() {
        val result = PainDiary(listOf(kept(d(3, 1)), pain(d(3, 2), PainRegion.NECK)), zone)
            .workAndPain(PainGroup.NECK_SHOULDERS.areas, march, longDayMinutes = 600)
        assertEquals(DayShare(0, 0), result.longVsOther.exposed)
        assertEquals(DayShare(0, 0), result.daysOff)
    }

    /**
     * Leg sessions every Monday of March and one on the 27th of February, whose window covers the 1st.
     * Knee pain starts two days after a session, four days after one and a day after one; the calf
     * soreness fourteen hours after a session fits the usual picture and is counted apart.
     */
    @Test
    fun painAfterSessionsIsSetAgainstTheShareOfDaysTheirWindowsCover() {
        val rows = days(d(3, 1), d(3, 31)).map { kept(it) } +
            listOf(d(2, 27), d(3, 2), d(3, 9), d(3, 16), d(3, 23), d(3, 30)).map { session(it) } +
            listOf(
                pain(d(3, 4), PainRegion.KNEE, nrs = 5, types = listOf(PainType.SHARP)),
                pain(d(3, 13), PainRegion.KNEE, nrs = 5),
                pain(d(3, 17), PainRegion.CALF, types = listOf(PainType.ACHING), onsetAt = at(d(3, 17), 9)),
                pain(d(3, 24), PainRegion.KNEE, nrs = 4),
                // The head is not a leg area, whatever its timing.
                pain(d(3, 3), PainRegion.TEMPLE),
            )
        val pattern = PainDiary(rows, zone).trainedArea(Muscle.LEGS, march)
        assertEquals(5, pattern.sessions)
        assertEquals(1, pattern.sorenessStarts)
        // Covered: the 1st; 2–4; 9–11; 16–18; 23–25; 30–31.
        assertEquals(DayShare(15, 31), pattern.daysInWindows)
        assertEquals(DayShare(2, 3), pattern.startsInWindows.exposed)
        assertEquals(DayShare(12, 27), pattern.startsInWindows.other)
        assertTrue(pattern.enough)
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, pattern.startsInWindows.verdict)
    }

    /**
     * A leg session on the 4th covers the 4th to the 6th. Knee pain reported on the 5th that began
     * "2–3 days ago" began on the 2nd or 3rd, before the session: not inside its window. Knee pain
     * reported on the 12th that began "yesterday", the day after a session on the 10th, is.
     */
    @Test
    fun aPainThatMayHaveBegunBeforeTheSessionIsNotAfterIt() {
        val rows = days(d(3, 1), d(3, 31)).map { kept(it) } +
            listOf(session(d(3, 4)), session(d(3, 10))) +
            listOf(
                pain(d(3, 5), PainRegion.KNEE, nrs = 5, onset = PainOnset.DAYS_2_3),
                pain(d(3, 12), PainRegion.KNEE, nrs = 5, onset = PainOnset.YESTERDAY),
            )
        val pattern = PainDiary(rows, zone).trainedArea(Muscle.LEGS, march)
        assertEquals(DayShare(1, 2), pattern.startsInWindows.exposed)
        // March less the days either start can lie on: the 2nd to the 5th and the 11th to the 12th.
        assertEquals(25, pattern.startsInWindows.other.of)
    }

    /** Each exercise of a workout is a row of its own: five on one day are one session, not five. */
    @Test
    fun aWorkoutOfFiveExercisesIsOneSession() {
        val rows = days(d(3, 1), d(3, 31)).map { kept(it) } +
            List(5) { session(d(3, 2)) } +
            listOf(d(3, 4), d(3, 9), d(3, 16)).map { pain(it, PainRegion.KNEE, nrs = 5) }
        val pattern = PainDiary(rows, zone).trainedArea(Muscle.LEGS, march)
        assertEquals(1, pattern.sessions)
        assertEquals(3, pattern.startsInWindows.exposed.of)
        assertFalse(pattern.enough)
    }

    /**
     * A group's areas are the ones over its muscles, the joints it crosses included, and no others —
     * on both maps: the parts the second map carved out of an area keep its group, and the bones and
     * tendons among them — the heel, the Achilles tendon — none. The groin is the legs', whose session
     * works the hip flexors and the adductors under it; the ridge to the shoulder the back's.
     */
    @Test
    fun aTrainedGroupsAreasAreFixedByTheMap() {
        assertEquals(
            setOf(
                PainRegion.GROIN,
                PainRegion.THIGH_FRONT,
                PainRegion.INNER_THIGH,
                PainRegion.THIGH_BACK,
                PainRegion.KNEE,
                PainRegion.BACK_OF_KNEE,
                PainRegion.SHIN,
                PainRegion.CALF,
                PainRegion.ANKLE_FOOT,
                PainRegion.ANKLE,
                PainRegion.TOP_OF_FOOT,
                PainRegion.SOLE,
                PainRegion.TOES,
            ),
            Muscle.LEGS.painAreas,
        )
        assertEquals(
            setOf(
                PainRegion.UPPER_ARM,
                PainRegion.BACK_OF_UPPER_ARM,
                PainRegion.ELBOW,
                PainRegion.ELBOW_INNER,
                PainRegion.FOREARM,
                PainRegion.BACK_OF_FOREARM,
                PainRegion.WRIST_HAND,
                PainRegion.FINGERS,
            ),
            Muscle.ARMS.painAreas,
        )
        assertEquals(setOf(PainRegion.SHOULDER), Muscle.SHOULDERS.painAreas)
        assertEquals(
            setOf(
                PainRegion.TRAPEZIUS,
                PainRegion.UPPER_BACK,
                PainRegion.SHOULDER_BLADE,
                PainRegion.LOWER_BACK,
                PainRegion.FLANK,
            ),
            Muscle.BACK.painAreas,
        )
        assertEquals(
            setOf(PainRegion.HEAD_FRONT, PainRegion.TEMPLE, PainRegion.HEAD_TOP, PainRegion.HEAD_BACK, PainRegion.JAW),
            PainGroup.HEAD.areas,
        )
    }
}
