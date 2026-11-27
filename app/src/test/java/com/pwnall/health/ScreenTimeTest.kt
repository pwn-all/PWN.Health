package com.pwnall.health

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ScreenTimeTest {
    private val min = 60_000L
    private val tiktok = "com.zhiliaoapp.musically"
    private val insta = "com.instagram.android"
    private val untracked = "com.google.android.apps.maps"
    private val day = LocalDate.of(2026, 11, 6)

    private fun resumed(pkg: String, at: Long, activity: String? = null) =
        AppUsageEvent(pkg, UsageEvents.Event.ACTIVITY_RESUMED, at * min, activity)

    private fun paused(pkg: String, at: Long, activity: String? = null) =
        AppUsageEvent(pkg, UsageEvents.Event.ACTIVITY_PAUSED, at * min, activity)

    private fun stopped(pkg: String, at: Long, activity: String? = null) =
        AppUsageEvent(pkg, UsageEvents.Event.ACTIVITY_STOPPED, at * min, activity)

    private fun screenOff(at: Long) = AppUsageEvent(null, UsageEvents.Event.SCREEN_NON_INTERACTIVE, at * min)

    @Test
    fun `every package of an app is the app`() {
        assertEquals(TrackedApp.TIKTOK, TrackedApp.of("com.ss.android.ugc.trill"))
        assertEquals(TrackedApp.INSTAGRAM, TrackedApp.of("com.instagram.lite"))
        assertEquals(TrackedApp.REDDIT, TrackedApp.of("com.reddit.frontpage"))
        assertNull(TrackedApp.of(untracked))
    }

    @Test
    fun `a span ends at its pause, another app, or the screen going off`() {
        val spans = foregroundSpans(
            listOf(
                resumed(tiktok, 0),
                paused(tiktok, 10),
                resumed(insta, 20),
                resumed(untracked, 25),
                resumed(tiktok, 30),
                screenOff(40),
            ),
            from = 0,
            now = 100 * min,
        )
        assertEquals(listOf(10 * min, 5 * min, 10 * min), spans.map(ForegroundSpan::durationMs))
        assertEquals(listOf(TrackedApp.TIKTOK, TrackedApp.INSTAGRAM, TrackedApp.TIKTOK), spans.map(ForegroundSpan::app))
    }

    /**
     * The log is read with every untracked app left nameless, so a nameless app coming forward has
     * to end a feed's span exactly as a named one did — or the policy's promise would cost the count.
     */
    @Test
    fun `a nameless app coming forward ends a span like a named one`() {
        val events = listOf(resumed(tiktok, 0), resumed(untracked, 10), resumed(tiktok, 20), screenOff(30))
        val nameless = events.map { if (it.packageName == untracked) it.copy(packageName = null) else it }
        assertEquals(foregroundSpans(events, 0, 60 * min), foregroundSpans(nameless, 0, 60 * min))
        assertEquals(listOf(10 * min, 10 * min), foregroundSpans(nameless, 0, 60 * min).map(ForegroundSpan::durationMs))
    }

    @Test
    fun `a span still open runs to now and is clipped to the window`() {
        val spans = foregroundSpans(listOf(resumed(tiktok, 0)), from = 30 * min, now = 50 * min)
        assertEquals(ForegroundSpan(TrackedApp.TIKTOK, 30 * min, 50 * min), spans.single())
    }

    @Test
    fun `a new activity of the same app does not split the span`() {
        val spans = foregroundSpans(listOf(resumed(tiktok, 0), resumed(tiktok, 5), paused(tiktok, 9)), 0, 60 * min)
        assertEquals(9 * min, spans.single().durationMs)
    }

    /**
     * Within one app the activity left behind stops after the next one has resumed — Instagram's feed
     * behind its story viewer, YouTube's home behind a video. That stop is not the app going away: the
     * twenty minutes in the story viewer have to be counted, and the two screens are one sitting.
     */
    @Test
    fun `an activity stopping behind the next one of its app does not end the span`() {
        val spans = foregroundSpans(
            listOf(
                resumed(insta, 0, "Feed"),
                paused(insta, 10, "Feed"),
                resumed(insta, 10, "Story"),
                stopped(insta, 11, "Feed"),
                paused(insta, 30, "Story"),
                stopped(insta, 31, "Story"),
            ),
            from = 0,
            now = 60 * min,
        )
        assertEquals(30 * min, spans.sumOf(ForegroundSpan::durationMs))
        assertEquals(1, feedSessions(spans).size)
    }

    @Test
    fun `a sitting across midnight counts toward each day its halves fell in`() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 11, 5)
        fun at(day: LocalDate, hour: Int) = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()
        val spans = listOf(ForegroundSpan(TrackedApp.TIKTOK, at(first, 23), at(first.plusDays(1), 1)))
        val days = feedDays(spans, first, days = 2, zone = zone, now = at(first.plusDays(1), 12))
        assertEquals(listOf(first, first.plusDays(1)), days.map(FeedDay::day))
        assertEquals(listOf(60 * min, 60 * min), days.map(FeedDay::totalMs))
        assertEquals(at(first.plusDays(1), 0), days[1].sessions.single().startAt)
    }

    @Test
    fun `hopping between feeds with short looks away is one sitting`() {
        val spans = listOf(
            ForegroundSpan(TrackedApp.TIKTOK, 0, 20 * min),
            ForegroundSpan(TrackedApp.INSTAGRAM, 21 * min, 35 * min),
            // A real break: ten minutes away.
            ForegroundSpan(TrackedApp.REDDIT, 45 * min, 50 * min),
        )
        val sessions = feedSessions(spans)
        assertEquals(2, sessions.size)
        assertEquals(34 * min, sessions[0].activeMs)
        assertEquals(TrackedApp.TIKTOK, sessions[0].mainApp)
    }

    @Test
    fun `the eye reminder comes once per step of a sitting still going`() {
        val settings = ScreenTimeSettings(eyesOn = true, eyesMinutes = 30)
        val today = feedDay(day, listOf(ForegroundSpan(TrackedApp.TIKTOK, 0, 35 * min)))
        val first = screenTimeDue(36 * min, today, settings, ScreenTimeMarks())
        assertNotNull(first.eyes)
        assertEquals(1, first.eyesLevel)
        val said = ScreenTimeMarks(eyesSession = 0, eyesSaidMinutes = 30)
        assertNull(screenTimeDue(36 * min, today, settings, said).eyes)
        val longer = feedDay(day, listOf(ForegroundSpan(TrackedApp.TIKTOK, 0, 61 * min)))
        assertEquals(2, screenTimeDue(61 * min, longer, settings, said).eyesLevel)
    }

    /**
     * The check reads the sitting whole, not the day's half of it: a scroll begun at 23:40 has run
     * its thirty minutes by 00:20, and after midnight it is still the sitting a step was said for.
     */
    @Test
    fun `the eye reminder counts a sitting through midnight as one`() {
        val zone = ZoneOffset.UTC
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli() / min
        val events = listOf(resumed(tiktok, midnight - 20))
        val settings = ScreenTimeSettings(eyesOn = true, eyesMinutes = 30)

        // As the check reads them: from the midnight before the day's own, today alone.
        fun reading(now: Long): ScreenTimeReading {
            val spans = foregroundSpans(events, from = screenLogStart(day, zone), now = now)
            return ScreenTimeReading(feedDays(spans, day, days = 1, zone = zone, now = now), latestFeedSitting(spans))
        }

        val now = (midnight + 20) * min
        val early = reading(now)
        val today = early.days.single()
        val sitting = early.sitting!!
        assertEquals(20 * min, today.totalMs)
        assertEquals((midnight - 20) * min, sitting.startAt)
        assertEquals(40 * min, sitting.activeMs)
        val due = screenTimeDue(now, today, settings, ScreenTimeMarks(), sitting)
        assertEquals(sitting, due.eyes)
        assertEquals(1, due.eyesLevel)

        // The step said before midnight is not said again after it, and the next one follows it.
        val said = ScreenTimeMarks(eyesSession = (midnight - 35) * min, eyesSaidMinutes = 30)
        val longer = listOf(resumed(tiktok, midnight - 35))
        val spans = foregroundSpans(longer, from = (midnight - 180) * min, now = now)
        val whole = latestFeedSitting(spans)!!
        assertNull(screenTimeDue(now, today, settings, said, whole).eyes)
        val later = (midnight + 25) * min
        val grown = latestFeedSitting(foregroundSpans(longer, from = (midnight - 180) * min, now = later))!!
        assertEquals(2, screenTimeDue(later, today, settings, said, grown).eyesLevel)
    }

    /**
     * The log is read from the midnight before the first day, so a sitting keeps after midnight the
     * start it had before it, however early in the evening that was. Read from three hours back, a
     * scroll begun at 20:30 in one activity would leave the window no event: it would not be seen after
     * midnight at all, and one that changed its screen at 21:10 would come back as a sitting begun
     * then, whose step would be said a second time.
     */
    @Test
    fun `a sitting begun early in the evening keeps its start after midnight`() {
        val zone = ZoneOffset.UTC
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli() / min
        val logStart = screenLogStart(day, zone)
        assertEquals((midnight - 24 * 60) * min, logStart)
        // The platform hands over the events inside the window and nothing older.
        val sent = listOf(resumed(tiktok, midnight - 210), resumed(tiktok, midnight - 170))
        val events = sent.filter { it.at >= logStart }
        val now = (midnight + 20) * min

        val spans = foregroundSpans(events, from = logStart, now = now)
        val sitting = latestFeedSitting(spans)!!

        assertEquals((midnight - 210) * min, sitting.startAt)
        val today = feedDays(spans, day, days = 1, zone = zone, now = now).single()
        assertEquals(20 * min, today.totalMs)
        val settings = ScreenTimeSettings(eyesOn = true, eyesMinutes = 30)
        val said = ScreenTimeMarks(eyesSession = (midnight - 210) * min, eyesSaidMinutes = 210)
        assertNull(screenTimeDue(now, today, settings, said, sitting).eyes)
    }

    @Test
    fun `the sitting the eyes are told about is a feed's`() {
        val spans = listOf(
            ForegroundSpan(TrackedApp.TIKTOK, 0, 20 * min),
            ForegroundSpan(TrackedApp.PUBG, 20 * min, 50 * min),
        )
        assertEquals(TrackedApp.TIKTOK, latestFeedSitting(spans)?.mainApp)
        assertEquals(20 * min, latestFeedSitting(spans)?.activeMs)
        assertNull(latestFeedSitting(emptyList()))
    }

    @Test
    fun `no eye reminder for a sitting that ended a while ago or while it is off`() {
        val today = feedDay(day, listOf(ForegroundSpan(TrackedApp.TIKTOK, 0, 40 * min)))
        assertNull(screenTimeDue(60 * min, today, ScreenTimeSettings(eyesOn = true), ScreenTimeMarks()).eyes)
        assertNull(screenTimeDue(41 * min, today, ScreenTimeSettings(eyesOn = false), ScreenTimeMarks()).eyes)
    }

    @Test
    fun `the brain warning steps with the day's total`() {
        val settings = ScreenTimeSettings(brainOn = true, brainMinutes = 120)
        val spans = listOf(
            ForegroundSpan(TrackedApp.REDDIT, 0, 70 * min),
            ForegroundSpan(TrackedApp.INSTAGRAM, 200 * min, 260 * min),
        )
        val today = feedDay(day, spans)
        assertEquals(130 * min, today.totalMs)
        assertEquals(1, screenTimeDue(300 * min, today, settings, ScreenTimeMarks()).brainLevel)
        assertEquals(0, screenTimeDue(300 * min, today, settings, ScreenTimeMarks(brainSaidMinutes = 120)).brainLevel)
        assertEquals(0, screenTimeDue(300 * min, today, ScreenTimeSettings(), ScreenTimeMarks()).brainLevel)
    }

    /**
     * A step changed after a reminder carries on from how far the reminder went. Counted in steps, a
     * mark made at 3 × 20 minutes would keep a step of 45 quiet until 4 × 45, one made at 1 × 45 would
     * have a step of 20 say 40 minutes at once, and the head told at 2 × 60 would wait for 3 × 120.
     */
    @Test
    fun `a step changed after a reminder carries on from what was said`() {
        fun scrolled(minutes: Long) = feedDay(day, listOf(ForegroundSpan(TrackedApp.TIKTOK, 0, minutes * min)))
        val saidAtSixty = ScreenTimeMarks(eyesSession = 0, eyesSaidMinutes = 60)
        val longer = ScreenTimeSettings(eyesOn = true, eyesMinutes = 45)
        assertEquals(2, screenTimeDue(90 * min, scrolled(90), longer, saidAtSixty).eyesLevel)

        val saidAtFortyFive = ScreenTimeMarks(eyesSession = 0, eyesSaidMinutes = 45)
        val shorter = ScreenTimeSettings(eyesOn = true, eyesMinutes = 20)
        assertNull(screenTimeDue(50 * min, scrolled(50), shorter, saidAtFortyFive).eyes)
        assertEquals(3, screenTimeDue(60 * min, scrolled(60), shorter, saidAtFortyFive).eyesLevel)

        val brain = ScreenTimeSettings(brainOn = true, brainMinutes = 120)
        val saidAtTwoHours = ScreenTimeMarks(brainSaidMinutes = 120)
        assertEquals(2, screenTimeDue(240 * min, scrolled(240), brain, saidAtTwoHours).brainLevel)
    }

    @Test
    fun `every package of a game is the game`() {
        assertEquals(TrackedApp.PUBG, TrackedApp.of("com.tencent.ig"))
        assertEquals(TrackedApp.PUBG, TrackedApp.of("com.pubg.imobile"))
        assertEquals(TrackedApp.CALL_OF_DUTY, TrackedApp.of("com.activision.callofduty.shooter"))
        assertEquals(TrackedApp.CALL_OF_DUTY, TrackedApp.of("com.garena.game.codm"))
    }

    @Test
    fun `games are counted apart and feed no reminder`() {
        val spans = foregroundSpans(
            listOf(
                resumed("com.tencent.ig", 0),
                paused("com.tencent.ig", 90),
                resumed("com.activision.callofduty.shooter", 91),
                screenOff(121),
            ),
            from = 0,
            now = 200 * min,
        )
        val today = feedDay(day, spans)
        assertEquals(120 * min, today.msOf(AppKind.GAME))
        assertEquals(0L, today.totalMs)
        assertEquals(90 * min, today.longestOf(AppKind.GAME))
        assertEquals(0L, today.longestSessionMs)
        val settings = ScreenTimeSettings(eyesOn = true, brainOn = true, brainMinutes = 60)
        val due = screenTimeDue(121 * min, today, settings, ScreenTimeMarks())
        assertNull(due.eyes)
        assertEquals(0, due.brainLevel)
    }

    @Test
    fun `a game between two feeds is a break from the feed`() {
        val today = feedDay(
            day,
            foregroundSpans(
                listOf(resumed(tiktok, 0), resumed("com.tencent.ig", 20), resumed(insta, 50), screenOff(60)),
                from = 0,
                now = 100 * min,
            ),
        )
        assertEquals(2, today.sessions.size)
        assertEquals(30 * min, today.totalMs)
        assertEquals(30 * min, today.msOf(AppKind.GAME))
    }

    @Test
    fun `no package belongs to two apps`() {
        val all = TrackedApp.entries.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `apps fall into their kinds`() {
        assertEquals(AppKind.FEED, TrackedApp.of("com.google.android.youtube")?.kind)
        assertEquals(AppKind.FEED, TrackedApp.of("com.vkontakte.android")?.kind)
        assertEquals(AppKind.VIDEO, TrackedApp.of("com.netflix.mediaclient")?.kind)
        assertEquals(AppKind.MESSENGER, TrackedApp.of("org.telegram.messenger")?.kind)
        assertEquals(AppKind.GAME, TrackedApp.of("com.roblox.client")?.kind)
        assertEquals(AppKind.GAME, TrackedApp.of("com.dts.freefiremax")?.kind)
    }

    @Test
    fun `only feeds reach the reminders`() {
        val spans = listOf(
            ForegroundSpan(TrackedApp.TELEGRAM, 0, 60 * min),
            ForegroundSpan(TrackedApp.NETFLIX, 60 * min, 180 * min),
            ForegroundSpan(TrackedApp.YOUTUBE, 180 * min, 215 * min),
        )
        val today = feedDay(day, spans)
        assertEquals(35 * min, today.totalMs)
        assertEquals(60 * min, today.msOf(AppKind.MESSENGER))
        assertEquals(120 * min, today.longestOf(AppKind.VIDEO))
        val settings = ScreenTimeSettings(eyesOn = true, brainOn = true, brainMinutes = 60)
        val due = screenTimeDue(215 * min, today, settings, ScreenTimeMarks())
        assertEquals(TrackedApp.YOUTUBE, due.eyes?.mainApp)
        assertEquals(0, due.brainLevel)
    }
}
