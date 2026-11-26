package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The soreness-after-training rule of thumb: every border of the timing, and every way a pain can
 * fail to be the usual picture. A rule shown to people about their own body has to be exactly the
 * rule its sources describe, so each part has its own case.
 */
class SorenessTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val zone = ZoneOffset.UTC
    private val began = 1_790_000_000_000L
    private val thigh = RegionSide(PainRegion.THIGH_FRONT, BodySide.LEFT)
    private val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
    private val soreThigh =
        PainReport(regions = listOf(thigh), types = listOf(PainType.ACHING), nrs = 5, onsetAt = began)

    /** The report day of [reportedAt] in the test's own zone, as a row carries it. */
    private fun pain(report: PainReport, reportedAt: Long = began + 2 * hour) = JournalEntry(
        day = Instant.ofEpochMilli(reportedAt).atZone(zone).toLocalDate().toString(),
        occurredAt = reportedAt,
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        durationMinutes = report.lastedMinutes?.toDouble(),
        measurementPayloadJson = encodePainPayload(report),
    )

    private fun session(code: String, endedAt: Long, importedAs: String? = null) = JournalEntry(
        day = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate().toString(),
        occurredAt = endedAt,
        kind = "ACTIVITY",
        title = code,
        calories = 200.0,
        activityCode = code,
        durationMinutes = 45.0,
        healthConnectRecordKey = importedAs,
    )

    private fun verdict(report: PainReport, vararg journal: JournalEntry, reportedAt: Long = began + 2 * hour) =
        trainingSoreness(pain(report, reportedAt), journal.toList(), zone)

    /** A moment on [date] at [hours] o'clock in the test's zone. */
    private fun at(date: LocalDate, hours: Int) = date.atTime(hours, 0).toInstant(zone).toEpochMilli()

    private val reportDay = LocalDate.of(2026, 9, 21)

    /** A sore thigh with its start answered as [onset] and no clock time. */
    private fun startedOn(onset: PainOnset) = soreThigh.copy(onset = onset, onsetAt = null)

    @Test
    fun achingLegsADayAfterSquatsFitTheUsualTiming() {
        val squats = session("SQUATS", began - 24 * hour)
        val result = verdict(soreThigh, squats)
        assertEquals(SorenessFit.FITS, result.fit)
        assertEquals(squats, result.session)
    }

    @Test
    fun sixHoursAfterIsTheEarliestAndSeventyTwoTheLatest() {
        assertEquals(SorenessFit.FITS, verdict(soreThigh, session("SQUATS", began - 6 * hour)).fit)
        assertEquals(SorenessFit.FITS, verdict(soreThigh, session("SQUATS", began - 72 * hour)).fit)
        assertEquals(SorenessFit.NO_SESSION, verdict(soreThigh, session("SQUATS", began - 6 * hour + 60_000)).fit)
        assertEquals(SorenessFit.NO_SESSION, verdict(soreThigh, session("SQUATS", began - 72 * hour - 60_000)).fit)
    }

    /** With no start answered at all, the pain began when it was reported. */
    @Test
    fun withoutAStartTheReportIsTheStart() {
        val reported = began + 10 * hour
        val squats = session("SQUATS", reported - 8 * hour)
        assertEquals(SorenessFit.FITS, verdict(soreThigh.copy(onsetAt = null), squats, reportedAt = reported).fit)
    }

    /**
     * "Yesterday" is any time yesterday. A workout logged this morning came after the pain began,
     * however many hours before the report it was — a case that would read as a fit if the report
     * stood in for the start.
     */
    @Test
    fun yesterdayIsNeverMatchedToAWorkoutLoggedToday() {
        val reported = at(reportDay, 19)
        val thisMorning = session("SQUATS", at(reportDay, 11))
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.YESTERDAY), thisMorning, reportedAt = reported).fit,
        )
        val yesterdayEvening = session("SQUATS", at(reportDay.minusDays(1), 20))
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.YESTERDAY), yesterdayEvening, reportedAt = reported).fit,
        )
    }

    /** Six hours before the first moment of yesterday, and within 72 of the last. */
    @Test
    fun yesterdayFitsAWorkoutWellBeforeTheWholeDay() {
        val reported = at(reportDay, 19)
        val twoDaysBefore = session("SQUATS", at(reportDay.minusDays(2), 12))
        assertEquals(
            SorenessFit.FITS,
            verdict(startedOn(PainOnset.YESTERDAY), twoDaysBefore, reportedAt = reported).fit,
        )
        val tooLateInTheDay = session("SQUATS", at(reportDay.minusDays(2), 19))
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.YESTERDAY), tooLateInTheDay, reportedAt = reported).fit,
        )
        val tooEarly = session("SQUATS", at(reportDay.minusDays(4), 0) - hour)
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.YESTERDAY), tooEarly, reportedAt = reported).fit,
        )
    }

    @Test
    fun twoOrThreeDaysAgoIsNeverMatchedToALaterWorkout() {
        val reported = at(reportDay, 10)
        val yesterday = session("SQUATS", at(reportDay.minusDays(1), 8))
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.DAYS_2_3), yesterday, reportedAt = reported).fit,
        )
        val fourDaysBefore = session("SQUATS", at(reportDay.minusDays(4), 12))
        assertEquals(
            SorenessFit.FITS,
            verdict(startedOn(PainOnset.DAYS_2_3), fourDaysBefore, reportedAt = reported).fit,
        )
    }

    /** "Earlier today" can be as early as midnight, so last night's late session is too close. */
    @Test
    fun earlierTodayRunsFromMidnightToTheReport() {
        val reported = at(reportDay, 18)
        val lateLastNight = session("SQUATS", at(reportDay.minusDays(1), 20))
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.EARLIER_TODAY), lateLastNight, reportedAt = reported).fit,
        )
        val lastAfternoon = session("SQUATS", at(reportDay.minusDays(1), 16))
        assertEquals(
            SorenessFit.FITS,
            verdict(startedOn(PainOnset.EARLIER_TODAY), lastAfternoon, reportedAt = reported).fit,
        )
    }

    /** A start four days or more back cannot be placed against a workout at all. */
    @Test
    fun aStartLongAgoHasNoSession() {
        val reported = at(reportDay, 10)
        val squats = session("SQUATS", at(reportDay.minusDays(5), 10))
        assertEquals(SorenessFit.NO_SESSION, verdict(startedOn(PainOnset.WEEK), squats, reportedAt = reported).fit)
        assertEquals(
            SorenessFit.NO_SESSION,
            verdict(startedOn(PainOnset.MONTHS_3_PLUS), squats, reportedAt = reported).fit,
        )
    }

    @Test
    fun aSessionOfAnotherGroupIsNoSession() {
        assertEquals(SorenessFit.NO_SESSION, verdict(soreThigh, session("BICEPS_CURL", began - 24 * hour)).fit)
    }

    @Test
    fun aWalkIsNotTraining() {
        assertEquals(SorenessFit.NO_SESSION, verdict(soreThigh, session("WALK", began - 24 * hour)).fit)
    }

    /** An import that could not name its exercise is filed as general strength work: not evidence. */
    @Test
    fun anImportedSessionIsLeftOut() {
        val imported = session("STRENGTH", began - 24 * hour, importedAs = "file:Strong:1789900000000:Squat")
        assertEquals(SorenessFit.NO_SESSION, verdict(soreThigh, imported).fit)
    }

    /**
     * No Health Connect code is in the catalogue today, so none works a muscle and the verdict alone
     * could not show the refusal. It is pinned on the session test itself: a code under the prefix is
     * never the person's own session, even one the catalogue might list one day.
     */
    @Test
    fun aHealthConnectCodeIsNeverAnOwnSession() {
        assertTrue(session("SQUATS", began).isOwnSession())
        assertFalse(session("HEALTH_CONNECT", began).isOwnSession())
        assertFalse(session(HEALTH_CONNECT_STEPS_CODE, began).isOwnSession())
        assertFalse(session("HEALTH_CONNECT_SQUATS", began).isOwnSession())
        assertFalse(session("SQUATS", began, importedAs = "hc:record:1").isOwnSession())
        assertFalse(session("WALK", began).isOwnSession())
    }

    @Test
    fun aJointIsNotTheUsualPicture() {
        val result = verdict(soreThigh.copy(regions = listOf(knee)), session("SQUATS", began - 24 * hour))
        assertEquals(SorenessFit.NOT_TYPICAL, result.fit)
        assertEquals(setOf(SorenessReason.ON_A_JOINT), result.reasons)
    }

    @Test
    fun sharpOrBurningWordsAreNotTheUsualPicture() {
        val squats = session("SQUATS", began - 24 * hour)
        val sharp = verdict(soreThigh.copy(types = listOf(PainType.ACHING, PainType.SHARP)), squats)
        assertEquals(setOf(SorenessReason.SHARP_OR_BURNING), sharp.reasons)
        val burning = verdict(soreThigh.copy(types = listOf(PainType.BURNING)), squats)
        assertEquals(setOf(SorenessReason.SHARP_OR_BURNING), burning.reasons)
        val cramping = verdict(soreThigh.copy(types = listOf(PainType.CRAMPING)), squats)
        assertEquals(setOf(SorenessReason.OTHER_WORDS), cramping.reasons)
        assertEquals(SorenessFit.FITS, verdict(soreThigh.copy(types = listOf(PainType.STIFF)), squats).fit)
    }

    /** What was answered — first noticed during the workout — and nothing about how suddenly. */
    @Test
    fun firstNoticedDuringTheWorkoutIsNotTheUsualPicture() {
        val result = verdict(soreThigh.copy(noticed = PainNoticed.DURING_WORKOUT), session("SQUATS", began - 24 * hour))
        assertEquals(setOf(SorenessReason.NOTICED_DURING_WORKOUT), result.reasons)
    }

    /**
     * How long it lasted is read off the entry, never off the clock: a pain still there when it was
     * reported has lasted until the report, and the same entry says the same thing a month later.
     */
    @Test
    fun overAWeekIsNotTheUsualPicture() {
        val squats = session("SQUATS", began - 24 * hour)
        val long = setOf(SorenessReason.LASTED_OVER_A_WEEK)
        // Still there when reported eight days on.
        assertEquals(long, verdict(soreThigh, squats, reportedAt = began + 8 * day).reasons)
        // Still there when reported the same day: fine, however long ago that report was made.
        assertEquals(SorenessFit.FITS, verdict(soreThigh, squats, reportedAt = began + 2 * hour).fit)
        // Gone after eight days.
        assertEquals(long, verdict(soreThigh.copy(endedAt = began + 8 * day), squats).reasons)
        // Gone within the week.
        val threeDays = soreThigh.copy(endedAt = began + 3 * day, lastedMinutes = 3 * 24 * 60)
        assertEquals(SorenessFit.FITS, verdict(threeDays, squats).fit)
        // Exactly seven days is still within the week.
        assertEquals(SorenessFit.FITS, verdict(soreThigh, squats, reportedAt = began + 7 * day).fit)
    }

    @Test
    fun theNearestSessionInTheWindowIsTheOneNamed() {
        val earlier = session("LEG_PRESS", began - 60 * hour)
        val later = session("SQUATS", began - 20 * hour)
        assertEquals(later, verdict(soreThigh, earlier, later).session)
    }

    /** The first report of a sore thigh, and the squats a day before it began. */
    private val firstReport = pain(soreThigh)
    private val squatsBefore = session("SQUATS", began - 24 * hour)

    /** The same pain as [firstReport], told at [reportedAt] with no start of its own. */
    private fun followUp(reportedAt: Long, report: PainReport = PainReport(regions = listOf(thigh), nrs = 4)) =
        pain(report.copy(same = firstReport.occurredAt), reportedAt)

    private fun judged(followUp: JournalEntry, vararg journal: JournalEntry) =
        trainingSoreness(followUp, journal.toList(), zone)

    /**
     * Told three days on with no start of its own, the entry alone would begin at its report, three
     * days after the squats and past the window. It is the same pain, and the first report's start
     * and session are what it is judged by.
     */
    @Test
    fun theSamePainIsJudgedByItsFirstReport() {
        val result = judged(followUp(firstReport.occurredAt + 3 * day), firstReport, squatsBefore)
        assertEquals(SorenessFit.FITS, result.fit)
        assertEquals(squatsBefore, result.session)
        val alone = PainReport(regions = listOf(thigh), nrs = 4)
        val threeDaysOn = firstReport.occurredAt + 3 * day
        assertEquals(SorenessFit.NO_SESSION, verdict(alone, squatsBefore, reportedAt = threeDaysOn).fit)
    }

    @Test
    fun theSamePainWithoutItsFirstReportHasNoSession() {
        assertEquals(SorenessFit.NO_SESSION, judged(followUp(firstReport.occurredAt + day), squatsBefore).fit)
        val noSession = judged(followUp(firstReport.occurredAt + 9 * day), firstReport)
        assertEquals(SorenessFit.NO_SESSION, noSession.fit)
    }

    /**
     * Still there more than a week after the pain began: not the usual week. Gone by the report does
     * not make it the usual week either — a pain back ten days on was there ten days on.
     */
    @Test
    fun theSamePainStillThereAfterAWeekLastedLonger() {
        val week = began + 7 * day
        assertEquals(SorenessFit.FITS, judged(followUp(week), firstReport, squatsBefore).fit)
        val past = judged(followUp(week + 60_000), firstReport, squatsBefore)
        assertEquals(SorenessFit.NOT_TYPICAL, past.fit)
        assertEquals(setOf(SorenessReason.LASTED_OVER_A_WEEK), past.reasons)
        assertEquals(squatsBefore, past.session)
        val gone =
            judged(
                followUp(week + day, PainReport(regions = listOf(thigh), lastedMinutes = 30)),
                firstReport,
                squatsBefore,
            )
        assertEquals(SorenessFit.NOT_TYPICAL, gone.fit)
        assertEquals(setOf(SorenessReason.LASTED_OVER_A_WEEK), gone.reasons)
        // Gone an hour past the week after two hours: it was there last before the week was out.
        val twoHours = PainReport(regions = listOf(thigh), lastedMinutes = 120)
        assertEquals(SorenessFit.FITS, judged(followUp(week + hour, twoHours), firstReport, squatsBefore).fit)
        // However long it lasted, a pain filed for a day past the week was there on that day.
        val longAgo = PainReport(regions = listOf(thigh), lastedMinutes = 24 * 60)
        val nextDay = Instant.ofEpochMilli(week).atZone(zone).toLocalDate().plusDays(1)
        assertEquals(SorenessFit.NOT_TYPICAL, judged(followUp(at(nextDay, 10), longAgo), firstReport, squatsBefore).fit)
    }

    /**
     * The week counts from when the first report says the pain began. Begun "2–3 days ago", it has
     * been there ten days when a follow-up six and a half days after that report still hurts.
     */
    @Test
    fun theWeekCountsFromWhenThePainBegan() {
        val first = pain(startedOn(PainOnset.DAYS_2_3), at(reportDay, 12))
        val squats = session("SQUATS", at(reportDay.minusDays(4), 12))
        assertEquals(SorenessFit.FITS, trainingSoreness(first, listOf(first, squats), zone).fit)
        fun laterOn(after: Long) = pain(
            PainReport(regions = listOf(thigh), same = first.occurredAt),
            first.occurredAt + after,
        )
        assertEquals(SorenessFit.FITS, judged(laterOn(3 * day), first, squats).fit)
        val tenDays = judged(laterOn(6 * day + 12 * hour), first, squats)
        assertEquals(SorenessFit.NOT_TYPICAL, tenDays.fit)
        assertEquals(setOf(SorenessReason.LASTED_OVER_A_WEEK), tenDays.reasons)
    }

    /**
     * Every entry filed for a day gone by carries that day's midday, so a first report can share its
     * moment with another pain filed for that day, and with a follow-up of itself filed the same day.
     * The first report is the one that follows up nothing and names an area this entry names.
     */
    @Test
    fun theFirstReportIsPickedFromEntriesOfTheSameMoment() {
        val moment = firstReport.occurredAt
        val kneeFirst = pain(soreThigh.copy(regions = listOf(knee)), moment).copy(id = 10)
        val sameDayFollowUp =
            pain(
                PainReport(regions = listOf(thigh), types = listOf(PainType.SHARP), same = moment),
                moment,
            ).copy(id = 12)
        val thighFirst = firstReport.copy(id = 11)
        val laterOn = followUp(moment + 2 * day).copy(id = 13)
        val result = judged(laterOn, kneeFirst, sameDayFollowUp, thighFirst, squatsBefore)
        assertEquals(SorenessFit.FITS, result.fit)
        assertEquals(squatsBefore, result.session)
        // With the areas since moved off every first report, the one of that moment still stands.
        val moved =
            followUp(moment + 2 * day, PainReport(regions = listOf(RegionSide(PainRegion.THIGH_FRONT, BodySide.RIGHT))))
        assertEquals(SorenessFit.FITS, judged(moved, sameDayFollowUp, thighFirst, squatsBefore).fit)
    }

    /**
     * A follow-up painted on the second map of a pain the first map recorded: the back of the knee
     * after a knee. Its first report is found by family among the entries of that moment — the knee,
     * not the neck filed beside it — and the follow-up is judged by the knee's start and session.
     */
    @Test
    fun theFirstReportIsFoundByFamilyAcrossTheMaps() {
        val moment = firstReport.occurredAt
        val firstMap = PainMapVersion.V1
        val neckFirst =
            pain(soreThigh.copy(regions = listOf(RegionSide(PainRegion.NECK, BodySide.CENTRE)), map = firstMap), moment)
                .copy(id = 10)
        val kneeFirst = pain(soreThigh.copy(regions = listOf(knee), map = firstMap), moment).copy(id = 11)
        val backOfKnee = RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)
        val laterOn = pain(PainReport(regions = listOf(backOfKnee), nrs = 4, same = moment), moment + 2 * day)
        val result = judged(laterOn, neckFirst, kneeFirst, squatsBefore)
        assertEquals(SorenessFit.NOT_TYPICAL, result.fit)
        assertEquals(setOf(SorenessReason.ON_A_JOINT), result.reasons)
        assertEquals(squatsBefore, result.session)
        // The neck alone at that moment is what it would be judged by otherwise: no session.
        assertEquals(SorenessFit.NO_SESSION, judged(laterOn, neckFirst, squatsBefore).fit)
    }

    /** Its own words when it has any — carried over or changed — and the first report's otherwise. */
    @Test
    fun theSamePainIsReadWithItsOwnWords() {
        val sharp = PainReport(regions = listOf(thigh), types = listOf(PainType.SHARP))
        val changed = judged(followUp(firstReport.occurredAt + day, sharp), firstReport, squatsBefore)
        assertEquals(setOf(SorenessReason.SHARP_OR_BURNING), changed.reasons)
        val burningFirst = pain(soreThigh.copy(types = listOf(PainType.BURNING)))
        val eased = pain(
            PainReport(regions = listOf(thigh), types = listOf(PainType.STIFF), same = burningFirst.occurredAt),
            burningFirst.occurredAt + day,
        )
        assertEquals(SorenessFit.FITS, judged(eased, burningFirst, squatsBefore).fit)
        val wordless =
            pain(PainReport(regions = listOf(thigh), same = burningFirst.occurredAt), burningFirst.occurredAt + day)
        assertEquals(setOf(SorenessReason.SHARP_OR_BURNING), judged(wordless, burningFirst, squatsBefore).reasons)
    }

    @Test
    fun noPainIsNeverSoreness() {
        val result = verdict(PainReport(none = true), session("SQUATS", began - 24 * hour))
        assertEquals(SorenessFit.NO_SESSION, result.fit)
        assertNull(result.session)
    }
}
