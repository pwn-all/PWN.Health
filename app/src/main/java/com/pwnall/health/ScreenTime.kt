package com.pwnall.health

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * What an app is to this page. Feeds — the endless-scroll and short-video ones, where the eyes stay
 * on a close screen and attention is cut into a new piece every few seconds — are what the reminders
 * watch. The rest is counted and shown, nothing more.
 */
internal enum class AppKind { FEED, VIDEO, MESSENGER, GAME }

/**
 * The apps whose time is read: where most phone time goes. Each lists every package it ships
 * under — regional publishers, Lite and business builds — and keeps its brand name untranslated.
 * Browsers are left out on purpose: a page of a bank and a page of a feed look the same from here.
 */
internal enum class TrackedApp(val label: String, val kind: AppKind, vararg packageNames: String) {
    TIKTOK(
        "TikTok",
        AppKind.FEED,
        "com.zhiliaoapp.musically",
        "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme",
    ),
    INSTAGRAM("Instagram", AppKind.FEED, "com.instagram.android", "com.instagram.lite"),
    YOUTUBE("YouTube", AppKind.FEED, "com.google.android.youtube"),
    REDDIT("Reddit", AppKind.FEED, "com.reddit.frontpage"),
    FACEBOOK("Facebook", AppKind.FEED, "com.facebook.katana", "com.facebook.lite"),
    X("X", AppKind.FEED, "com.twitter.android"),
    THREADS("Threads", AppKind.FEED, "com.instagram.barcelona"),
    SNAPCHAT("Snapchat", AppKind.FEED, "com.snapchat.android"),
    PINTEREST("Pinterest", AppKind.FEED, "com.pinterest"),
    VK("VK", AppKind.FEED, "com.vkontakte.android"),
    LIKEE("Likee", AppKind.FEED, "video.like"),
    KWAI("Kwai", AppKind.FEED, "com.kwai.video"),
    TUMBLR("Tumblr", AppKind.FEED, "com.tumblr"),
    NINE_GAG("9GAG", AppKind.FEED, "com.ninegag.android.app"),

    TWITCH("Twitch", AppKind.VIDEO, "tv.twitch.android.app"),
    NETFLIX("Netflix", AppKind.VIDEO, "com.netflix.mediaclient"),
    PRIME_VIDEO("Prime Video", AppKind.VIDEO, "com.amazon.avod.thirdpartyclient"),
    DISNEY_PLUS("Disney+", AppKind.VIDEO, "com.disney.disneyplus"),
    VK_VIDEO("VK Video", AppKind.VIDEO, "com.vk.vkvideo"),
    RUTUBE("Rutube", AppKind.VIDEO, "ru.rutube.app"),
    KINOPOISK("Кинопоиск", AppKind.VIDEO, "ru.kinopoisk"),
    IVI("ivi", AppKind.VIDEO, "ru.ivi.client"),
    OKKO("Okko", AppKind.VIDEO, "ru.more.play"),

    TELEGRAM(
        "Telegram",
        AppKind.MESSENGER,
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    ),
    WHATSAPP("WhatsApp", AppKind.MESSENGER, "com.whatsapp", "com.whatsapp.w4b"),
    DISCORD("Discord", AppKind.MESSENGER, "com.discord"),
    MESSENGER("Messenger", AppKind.MESSENGER, "com.facebook.orca"),

    CALL_OF_DUTY(
        "Call of Duty",
        AppKind.GAME,
        "com.activision.callofduty.shooter",
        "com.activision.callofduty.warzone",
        "com.garena.game.codm",
        "com.tencent.tmgp.cod",
        "com.vng.codmvn",
    ),
    PUBG(
        "PUBG",
        AppKind.GAME,
        "com.tencent.ig",
        "com.tencent.iglite",
        "com.pubg.krmobile",
        "com.pubg.imobile",
        "com.pubg.newstate",
        "com.vng.pubgmobile",
        "com.rekoo.pubgm",
        "com.tencent.tmgp.pubgmhd",
    ),
    FREE_FIRE("Free Fire", AppKind.GAME, "com.dts.freefireth", "com.dts.freefiremax"),
    FORTNITE("Fortnite", AppKind.GAME, "com.epicgames.fortnite"),
    STANDOFF_2("Standoff 2", AppKind.GAME, "com.axlebolt.standoff2"),
    ARENA_BREAKOUT("Arena Breakout", AppKind.GAME, "com.proximabeta.mf.uamo"),
    MOBILE_LEGENDS("Mobile Legends", AppKind.GAME, "com.mobile.legends"),
    HONOR_OF_KINGS("Honor of Kings", AppKind.GAME, "com.levelinfinite.sgameGlobal"),
    BRAWL_STARS("Brawl Stars", AppKind.GAME, "com.supercell.brawlstars"),
    CLASH_OF_CLANS("Clash of Clans", AppKind.GAME, "com.supercell.clashofclans"),
    CLASH_ROYALE("Clash Royale", AppKind.GAME, "com.supercell.clashroyale"),
    ROBLOX("Roblox", AppKind.GAME, "com.roblox.client"),
    MINECRAFT("Minecraft", AppKind.GAME, "com.mojang.minecraftpe"),
    GENSHIN("Genshin Impact", AppKind.GAME, "com.miHoYo.GenshinImpact"),
    STAR_RAIL("Honkai: Star Rail", AppKind.GAME, "com.HoYoverse.hkrpgoversea"),
    ZENLESS("Zenless Zone Zero", AppKind.GAME, "com.HoYoverse.Nap"),
    WUTHERING_WAVES("Wuthering Waves", AppKind.GAME, "com.kurogame.wutheringwaves.global"),
    WOT_BLITZ("World of Tanks Blitz", AppKind.GAME, "net.wargaming.wot.blitz"),
    EA_FC("EA SPORTS FC Mobile", AppKind.GAME, "com.ea.gp.fifamobile"),
    EFOOTBALL("eFootball", AppKind.GAME, "jp.konami.pesam"),
    ASPHALT("Asphalt Legends", AppKind.GAME, "com.gameloft.android.ANMP.GloftA9HM"),
    POKEMON_GO("Pokémon GO", AppKind.GAME, "com.nianticlabs.pokemongo"),
    AMONG_US("Among Us", AppKind.GAME, "com.innersloth.spacemafia"),
    STUMBLE_GUYS("Stumble Guys", AppKind.GAME, "com.kitkagames.fallbuddies"),
    SUBWAY_SURFERS("Subway Surfers", AppKind.GAME, "com.kiloo.subwaysurf"),
    CANDY_CRUSH("Candy Crush Saga", AppKind.GAME, "com.king.candycrushsaga"),
    ROYAL_MATCH("Royal Match", AppKind.GAME, "com.dreamgames.royalmatch"),
    MONOPOLY_GO("Monopoly GO!", AppKind.GAME, "com.scopely.monopolygo"),
    COIN_MASTER("Coin Master", AppKind.GAME, "com.moonactive.coinmaster"),
    EIGHT_BALL_POOL("8 Ball Pool", AppKind.GAME, "com.miniclip.eightballpool"),
    ;

    val packages: Set<String> = packageNames.toSet()

    companion object {
        private val byPackage = entries.flatMap { app -> app.packages.map { it to app } }.toMap()
        fun of(packageName: String?): TrackedApp? = packageName?.let(byPackage::get)
    }
}

/**
 * One event as the platform reports it: the package it belongs to, its type and moment, and for the
 * events about an activity, which one. Both names are null for an app that is not a [TrackedApp].
 */
internal data class AppUsageEvent(
    val packageName: String?,
    val type: Int,
    val at: Long,
    val className: String? = null,
)

/** A stretch one feed app spent in the foreground. */
internal data class ForegroundSpan(val app: TrackedApp, val startAt: Long, val endAt: Long) {
    val durationMs: Long get() = endAt - startAt
}

/**
 * One sitting: feed spans with no more than [FEED_GAP_MS] between them, whichever of the apps they
 * were in. Hopping from TikTok to Instagram is the same scroll to the eyes.
 */
internal data class FeedSession(
    val startAt: Long,
    val endAt: Long,
    val activeMs: Long,
    val byApp: Map<TrackedApp, Long>,
) {
    /** The app the sitting was mostly spent in, for the reminder's sentence. */
    val mainApp: TrackedApp get() = byApp.maxBy { it.value }.key
}

/** A look away shorter than this — a reply in a messenger, the shade pulled down — is not a rest. */
internal const val FEED_GAP_MS = 2L * 60 * 1_000

/** How recently a sitting has to have been going for its reminder still to be worth sending. */
internal const val FEED_ONGOING_MS = 5L * 60 * 1_000

// The events that start and end a foreground stretch. ACTIVITY_RESUMED is MOVE_TO_FOREGROUND's
// newer name and ACTIVITY_PAUSED MOVE_TO_BACKGROUND's; they share their numbers.
private val ENDING_EVENTS = setOf(
    UsageEvents.Event.ACTIVITY_PAUSED,
    UsageEvents.Event.ACTIVITY_STOPPED,
)
private val SCREEN_GONE_EVENTS = setOf(
    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
    UsageEvents.Event.KEYGUARD_SHOWN,
    UsageEvents.Event.DEVICE_SHUTDOWN,
)

/** Every event [SpanReader] reads; the rest of a week's log is left where it lies. */
private val SPAN_EVENTS = ENDING_EVENTS + SCREEN_GONE_EVENTS + UsageEvents.Event.ACTIVITY_RESUMED

/**
 * The tracked apps' foreground spans in [events], clipped to [from]..[now]. A span runs from its app's
 * resume to the first of: its own pause, another app coming forward, the screen going off. One
 * still open at the end of the events runs to [now] — that is the scroll going on as we look.
 */
internal fun foregroundSpans(events: List<AppUsageEvent>, from: Long, now: Long): List<ForegroundSpan> {
    val reader = SpanReader(from, now)
    events.sortedBy(AppUsageEvent::at).forEach(reader::read)
    reader.close(now)
    return reader.spans
}

/** [foregroundSpans]' one open span and the closed ones behind it. */
private class SpanReader(private val from: Long, private val now: Long) {
    val spans = mutableListOf<ForegroundSpan>()
    private var open: OpenSpan? = null

    /** The app in front since [startAt], and which of its activities is the one in front now. */
    private class OpenSpan(val app: TrackedApp, var activity: String?, val startAt: Long) {
        fun inFront(app: TrackedApp?, activity: String?) = this.app == app && this.activity == activity
    }

    fun read(event: AppUsageEvent) {
        val app = TrackedApp.of(event.packageName)
        val current = open
        when (event.type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> if (current != null && current.app == app) {
                // The same app's next activity — a feed opening a profile, a video its comments —
                // carries the span on; only the activity whose pause will end it changes.
                current.activity = event.className
            } else {
                close(event.at)
                open = app?.let { OpenSpan(it, event.className, event.at) }
            }

            // Only the activity in front ends the span by pausing or stopping. The platform reports
            // an activity's stop after the next one's resume, so the one just left must not cut the
            // new one short: Instagram's feed stopping behind its story viewer is not Instagram going.
            in ENDING_EVENTS -> if (current?.inFront(app, event.className) == true) close(event.at)

            in SCREEN_GONE_EVENTS -> close(event.at)
        }
    }

    fun close(at: Long) {
        open?.let { span ->
            val clippedStart = maxOf(span.startAt, from)
            val clippedEnd = minOf(at, now)
            if (clippedEnd > clippedStart) spans += ForegroundSpan(span.app, clippedStart, clippedEnd)
        }
        open = null
    }
}

/** [spans] joined into sittings, oldest first. */
internal fun feedSessions(spans: List<ForegroundSpan>, gapMs: Long = FEED_GAP_MS): List<FeedSession> {
    val sessions = mutableListOf<FeedSession>()
    for (span in spans.sortedBy(ForegroundSpan::startAt)) {
        val last = sessions.lastOrNull()
        if (last != null && span.startAt - last.endAt <= gapMs) {
            sessions[sessions.lastIndex] = last + span
        } else {
            sessions += sessionOf(span)
        }
    }
    return sessions
}

/**
 * The feed sitting [spans] end in, for the eyes' reminder. Taken from spans that have not been cut
 * at midnight: the eyes do not start afresh because the date changed, and a scroll begun at 23:40
 * has run forty minutes at 00:20, not twenty. A day's own [FeedDay.sessions] are cut at its
 * midnight, which is right for the day's figures and wrong for this.
 */
internal fun latestFeedSitting(spans: List<ForegroundSpan>): FeedSession? =
    feedSessions(spans.filter { it.app.kind == AppKind.FEED }).lastOrNull()

/** A sitting of this one span. */
private fun sessionOf(span: ForegroundSpan) =
    FeedSession(span.startAt, span.endAt, span.durationMs, mapOf(span.app to span.durationMs))

/** This sitting carried on through [span]. */
private operator fun FeedSession.plus(span: ForegroundSpan) = copy(
    endAt = maxOf(endAt, span.endAt),
    activeMs = activeMs + span.durationMs,
    byApp = byApp + (span.app to (byApp[span.app] ?: 0L) + span.durationMs),
)

/**
 * One day's reading. [totalMs], [sessions] and [longestSessionMs] are the feeds' alone — they are
 * what the reminders read; [msOf] and [longestOf] give any kind its own.
 */
internal data class FeedDay(
    val day: LocalDate,
    val byApp: Map<TrackedApp, Long>,
    val sessions: List<FeedSession>,
    val longestByKind: Map<AppKind, Long>,
) {
    val totalMs: Long get() = msOf(AppKind.FEED)
    val longestSessionMs: Long get() = longestOf(AppKind.FEED)
    fun msOf(kind: AppKind): Long = byApp.filterKeys { it.kind == kind }.values.sum()
    fun longestOf(kind: AppKind): Long = longestByKind[kind] ?: 0L
}

internal fun feedDay(day: LocalDate, spans: List<ForegroundSpan>): FeedDay {
    val byApp = spans.groupBy(ForegroundSpan::app).mapValues { (_, list) -> list.sumOf(ForegroundSpan::durationMs) }
    // Feeds join into one sitting whichever of them it was in: hopping between them is the same
    // scroll. Every other app's sitting is its own — two matches with a short lobby between are one
    // evening of play, a match and a film are not.
    val sessionsByKind = spans.groupBy { it.app.kind }.mapValues { (kind, list) ->
        if (kind == AppKind.FEED) {
            feedSessions(list)
        } else {
            list.groupBy(ForegroundSpan::app).values.flatMap { feedSessions(it) }
        }
    }
    val longestByKind = sessionsByKind.mapValues { (_, sessions) -> sessions.maxOfOrNull(FeedSession::activeMs) ?: 0L }
    return FeedDay(day, byApp, sessionsByKind[AppKind.FEED].orEmpty(), longestByKind)
}

/**
 * [spans] as [days] days from [first], oldest first, each span cut at midnight: a night's scroll
 * counts toward the day each half fell in. The last day runs to [now].
 */
internal fun feedDays(
    spans: List<ForegroundSpan>,
    first: LocalDate,
    days: Int,
    zone: ZoneId,
    now: Long,
): List<FeedDay> = (0 until days).map { offset ->
    val day = first.plusDays(offset.toLong())
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
    feedDay(day, spans.mapNotNull { it.clipped(start, end) })
}

/**
 * Where the log is read from for days that begin with [first]: the midnight before, so the evening
 * that led into the first day is read whole.
 *
 * An app open as the day began resumed before it, and only that event says it was in front; what it
 * did before midnight is still cut off at midnight for the day's figures, and is the start of the
 * sitting the eyes' reminder counts. Read only three hours back, a scroll begun at 20:30 would be
 * seen after midnight from its first change of screen past 21:00: a sitting with another start, so
 * the step said before midnight would be said once more — and an app that never changes its screen,
 * a feed of videos in one activity, would not be seen after midnight at all, by the reminder or the day.
 */
internal fun screenLogStart(first: LocalDate, zone: ZoneId): Long =
    first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** The part of this span inside [from]..[to], or null when none of it is. */
internal fun ForegroundSpan.clipped(from: Long, to: Long): ForegroundSpan? {
    val start = maxOf(startAt, from)
    val end = minOf(endAt, to)
    return if (end > start) ForegroundSpan(app, start, end) else null
}

/** What the person asked to be told, and when. Kept in preferences: nothing here is a record. */
internal data class ScreenTimeSettings(
    val eyesOn: Boolean = false,
    val eyesMinutes: Int = DEFAULT_EYES_MINUTES,
    val brainOn: Boolean = false,
    val brainMinutes: Int = DEFAULT_BRAIN_MINUTES,
) {
    val anyOn: Boolean get() = eyesOn || brainOn
}

internal const val DEFAULT_EYES_MINUTES = 30
internal const val DEFAULT_BRAIN_MINUTES = 120
internal val EYES_MINUTE_CHOICES = listOf(20, 30, 45, 60)
internal val BRAIN_MINUTE_CHOICES = listOf(60, 90, 120, 180)

/** Whole minutes of [ms], the unit every figure on the page and in a notice is said in. */
internal fun minutesOf(ms: Long): Int = (ms / 60_000).toInt()

/**
 * The marks that keep each reminder from being said twice: the sitting, by its start, how far into
 * it the eyes were last told, and how far into today the head was told. Minutes, not steps: a step
 * changed since is read against what was said — counted in steps, a mark made at 3 × 20 minutes
 * would keep a step of 45 quiet until 4 × 45.
 */
internal data class ScreenTimeMarks(
    val eyesSession: Long? = null,
    val eyesSaidMinutes: Int = 0,
    val brainSaidMinutes: Int = 0,
)

/** Which reminders a check owes: the sitting the eyes are owed for and its step, and the head's step. */
internal data class ScreenTimeDue(
    val eyes: FeedSession? = null,
    val eyesLevel: Int = 0,
    val brainLevel: Int = 0,
)

/** One reading of the log: its days, oldest first, and the feed sitting it ends in ([latestFeedSitting]). */
internal data class ScreenTimeReading(val days: List<FeedDay>, val sitting: FeedSession?)

/**
 * The reminders due [now], given what was [said] already. Eyes: the sitting still going has passed
 * another [ScreenTimeSettings.eyesMinutes] without a break — at 30, at 60, at 90 — and nothing that
 * far in has been said for this sitting. Brain: the day's total has passed another
 * [ScreenTimeSettings.brainMinutes] — two hours, four, six — and nothing that far in has been said
 * today. A step's mark is its minutes: its level times the step. Pure, so the rules are testable.
 *
 * [sitting] is the latest sitting as the check read it, whole ([latestFeedSitting]). Left out, it is
 * the day's own last one, which begins at midnight at the earliest: the same sitting on any day
 * but the one a scroll crossed into.
 */
internal fun screenTimeDue(
    now: Long,
    today: FeedDay,
    settings: ScreenTimeSettings,
    said: ScreenTimeMarks,
    sitting: FeedSession? = today.sessions.lastOrNull(),
): ScreenTimeDue {
    val eyesStep = settings.eyesMinutes * 60_000L
    val current = sitting?.takeIf { now - it.endAt <= FEED_ONGOING_MS }
    val eyesLevel = current?.let { (it.activeMs / eyesStep).toInt() } ?: 0
    val alreadyEyes = if (current?.startAt == said.eyesSession) said.eyesSaidMinutes else 0
    val eyes = current.takeIf {
        settings.eyesOn && eyesLevel >= 1 && eyesLevel * settings.eyesMinutes > alreadyEyes
    }

    val brainLevel = (today.totalMs / (settings.brainMinutes * 60_000L)).toInt()
    val brain = brainLevel.takeIf {
        settings.brainOn && it >= 1 && it * settings.brainMinutes > said.brainSaidMinutes
    } ?: 0
    return ScreenTimeDue(eyes = eyes, eyesLevel = if (eyes != null) eyesLevel else 0, brainLevel = brain)
}

internal object ScreenTime {
    private const val WORK_NAME = "screen_time"
    internal const val CHANNEL = "screen_time"
    private const val PREFS = "screen_time"
    private const val EYES_ON = "eyes_on"
    private const val EYES_MINUTES = "eyes_minutes"
    private const val BRAIN_ON = "brain_on"
    private const val BRAIN_MINUTES = "brain_minutes"
    private const val EYES_SESSION = "eyes_said_session"
    private const val EYES_SAID = "eyes_said_minutes"
    private const val BRAIN_DAY = "brain_said_day"
    private const val BRAIN_SAID = "brain_said_minutes"

    internal fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): ScreenTimeSettings = prefs(context).let {
        ScreenTimeSettings(
            eyesOn = it.getBoolean(EYES_ON, false),
            eyesMinutes = it.getInt(EYES_MINUTES, DEFAULT_EYES_MINUTES),
            brainOn = it.getBoolean(BRAIN_ON, false),
            brainMinutes = it.getInt(BRAIN_MINUTES, DEFAULT_BRAIN_MINUTES),
        )
    }

    fun save(context: Context, settings: ScreenTimeSettings) {
        prefs(context).edit {
            putBoolean(EYES_ON, settings.eyesOn)
            putInt(EYES_MINUTES, settings.eyesMinutes)
            putBoolean(BRAIN_ON, settings.brainOn)
            putInt(BRAIN_MINUTES, settings.brainMinutes)
        }
        apply(context)
    }

    /** What the reminders have said, from [prefs]; the head's mark counts only when it was made [today]. */
    fun marks(prefs: SharedPreferences, today: LocalDate) = ScreenTimeMarks(
        eyesSession = prefs.getLong(EYES_SESSION, -1L).takeIf { it >= 0 },
        eyesSaidMinutes = prefs.getInt(EYES_SAID, 0),
        brainSaidMinutes = if (prefs.getString(BRAIN_DAY, null) == today.toString()) prefs.getInt(BRAIN_SAID, 0) else 0,
    )

    fun markEyes(prefs: SharedPreferences, session: FeedSession, minutes: Int) {
        prefs.edit {
            putLong(EYES_SESSION, session.startAt)
            putInt(EYES_SAID, minutes)
        }
    }

    fun markBrain(prefs: SharedPreferences, today: LocalDate, minutes: Int) {
        prefs.edit {
            putString(BRAIN_DAY, today.toString())
            putInt(BRAIN_SAID, minutes)
        }
    }

    /**
     * The check every fifteen minutes — WorkManager's shortest period — while either reminder is on,
     * and none at all otherwise. Called on every opening of the app as well, which costs nothing
     * with KEEP and puts the work back after a data wipe of WorkManager's own.
     */
    fun apply(context: Context) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!settings(context).anyOn) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        work.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScreenTimeWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /**
     * The last [days] days of the tracked apps, oldest first, today last. Empty without Usage access.
     * Android keeps the event log about a week, so older days come back as zero.
     */
    suspend fun read(context: Context, days: Int = 7, now: Long = System.currentTimeMillis()): List<FeedDay> =
        reading(context, days, now)?.days.orEmpty()

    /**
     * [read], with the feed sitting going on at [now] beside the days; null without Usage access.
     * The spans are cut where the log begins rather than at the first midnight: [feedDays] cuts
     * each day's own, and the sitting needs the part before it.
     */
    suspend fun reading(context: Context, days: Int, now: Long): ScreenTimeReading? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!hasUsageStatsAccess(app)) return@withContext null
            val zone = ZoneId.systemDefault()
            val first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days - 1L)
            val logStart = screenLogStart(first, zone)
            val usage = app.getSystemService(UsageStatsManager::class.java)
            val events = runCatching { usage.feedEvents(logStart, now) }.getOrDefault(emptyList())
            val spans = foregroundSpans(events, logStart, now)
            ScreenTimeReading(feedDays(spans, first, days, zone, now), latestFeedSitting(spans))
        }

    /**
     * The event log with every app that is not a [TrackedApp] left nameless as it is read — its
     * activities' names with it, which name the app as surely. That an app came forward still
     * matters — it ends a feed's span — but which one does not, so the list of everything else on
     * the phone never reaches the app's memory, which is what §5 of the privacy policy promises.
     */
    private fun UsageStatsManager.feedEvents(startAt: Long, endAt: Long): List<AppUsageEvent> {
        val events = queryEvents(startAt, endAt)
        val event = UsageEvents.Event()
        return buildList {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType !in SPAN_EVENTS) continue
                val tracked = event.packageName?.takeIf { TrackedApp.of(it) != null }
                add(AppUsageEvent(tracked, event.eventType, event.timeStamp, tracked?.let { event.className }))
            }
        }
    }

    fun channel(context: Context) = NotificationChannel(
        CHANNEL,
        context.getString(R.string.screen_time_channel),
        // Heard, unlike the hourly nudges: a silent notice over a full-screen video is never seen.
        NotificationManager.IMPORTANCE_DEFAULT,
    )

    /** Reports whether the notification was handed over, which a denied permission is not. */
    // One guard per reason the notification cannot be shown.
    fun post(context: Context, id: Int, title: String, body: String): Boolean {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(channel(app))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(
                PendingIntent.getActivity(
                    app,
                    id,
                    openAppIntent(app, WidgetRoute.SCREEN_TIME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // The permission can be taken away while the notification is being built.
            false
        }
    }
}

/** Reads today's feed time and says what is due. Everything is decided on the phone. */
internal class ScreenTimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val resources get() = applicationContext.resources

    // One guard per reason there is nothing to read this time.
    override suspend fun doWork(): Result {
        val settings = ScreenTime.settings(applicationContext)
        if (!settings.anyOn || !hasUsageStatsAccess(applicationContext)) return Result.success()
        val now = System.currentTimeMillis()
        val reading = ScreenTime.reading(applicationContext, days = 1, now = now) ?: return Result.success()
        val today = reading.days.lastOrNull() ?: return Result.success()
        val prefs = ScreenTime.prefs(applicationContext)
        // The sitting as read past midnight, not the day's own: a scroll begun last night is still one.
        val due = screenTimeDue(now, today, settings, ScreenTime.marks(prefs, today.day), reading.sitting)
        // A mark is made only for a notice that went out: a denied permission leaves the step owed.
        due.eyes?.let { session ->
            if (remindEyes(session)) ScreenTime.markEyes(prefs, session, due.eyesLevel * settings.eyesMinutes)
        }
        if (due.brainLevel > 0 && remindBrain(today)) {
            ScreenTime.markBrain(prefs, today.day, due.brainLevel * settings.brainMinutes)
        }
        return Result.success()
    }

    /** The 20-20-20 rule, naming the app the sitting was mostly in. True when the notice went out. */
    private fun remindEyes(session: FeedSession): Boolean = ScreenTime.post(
        applicationContext,
        NotificationIds.FEED_EYES,
        resources.getString(R.string.screen_time_eyes_title),
        resources.getString(R.string.screen_time_eyes_body, session.mainApp.label, minutesOf(session.activeMs)),
    )

    /** Put the phone down for ten minutes, with the day's total. True when the notice went out. */
    private fun remindBrain(today: FeedDay): Boolean {
        val total = workDurationText(resources, minutesOf(today.totalMs))
        return ScreenTime.post(
            applicationContext,
            NotificationIds.FEED_BRAIN,
            resources.getString(R.string.screen_time_brain_title),
            resources.getString(R.string.screen_time_brain_body, total),
        )
    }
}
