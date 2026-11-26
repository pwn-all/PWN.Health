package com.pwnall.health

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private const val MIN_SLEEP_GAP_MS = 4L * 60 * 60 * 1_000
private const val MAX_SLEEP_GAP_MS = 16L * 60 * 60 * 1_000
private const val DETECTION_PREFS = "automatic_sleep_detection"
private const val PROCESSED_DAYS = "processed_days"

/** How many night windows, this morning's among them, each reading of the nights looks back over. */
private const val LOOKBACK_NIGHTS = 7

// Events that mean the phone was touched, i.e. the person was awake.
private val INTERACTION_EVENT_TYPES = setOf(
    UsageEvents.Event.ACTIVITY_RESUMED,
    UsageEvents.Event.SCREEN_INTERACTIVE,
    UsageEvents.Event.USER_INTERACTION,
    UsageEvents.Event.KEYGUARD_HIDDEN,
)

// Of those four, the two nobody but a person can produce: a touch and an unlock. A restart makes
// the other two on its own, lighting the lock screen in an empty room.
private val HUMAN_EVENT_TYPES = setOf(
    UsageEvents.Event.USER_INTERACTION,
    UsageEvents.Event.KEYGUARD_HIDDEN,
)

/** How long after a restart its own screen-on and activity resumes keep arriving. */
private const val BOOT_SETTLE_MS = 5L * 60 * 1_000

/** One event as the platform reports it, kept with its type so a restart can be told from a hand. */
internal data class UsageEventRecord(val type: Int, val at: Long)

/**
 * The moments somebody was awake, minus the burst a restart makes.
 *
 * A phone that restarts itself in the night — an idle-reboot policy, a crash, an update — lights
 * its lock screen, which is not a person waking up. Filed as one, a single such timestamp would be
 * enough to lose the whole night, because the windows [detectEstimatedSleep] passes admit exactly
 * one pair of consecutive interactions: the one straddling 04:00. A restart at 03:20 would leave
 * 03:21 → 07:00, three and a half hours, under [MIN_SLEEP_GAP_MS], and the night would estimate to
 * nothing at all. A touch or an unlock still counts, so somebody who really does restart their
 * phone and unlock it at seven keeps their waking moment.
 */
internal fun awakeMoments(events: List<UsageEventRecord>): List<Long> {
    val boots = events.filter { it.type == UsageEvents.Event.DEVICE_STARTUP }.map { it.at }
    fun isBootNoise(event: UsageEventRecord) = event.type !in HUMAN_EVENT_TYPES &&
        boots.any { event.at - it in 0..BOOT_SETTLE_MS }
    return events.filter { it.type in INTERACTION_EVENT_TYPES && !isBootNoise(it) }.map { it.at }
}

internal data class EstimatedSleep(
    val startAt: Long,
    val endAt: Long,
)

/**
 * Returns the longest interaction-free gap whose beginning and end fit a night window.
 *
 * This is deliberately only an estimate of phone inactivity. It cannot distinguish sleep from a
 * powered-off phone, reading, a movie, travel, or simply leaving the device in another room.
 */
// One parameter per night-window input; a wrapper type would only move them.
@Suppress("LongParameterList")
internal fun estimateNightSleep(
    interactions: List<Long>,
    startEarliest: Long,
    startLatest: Long,
    wakeEarliest: Long,
    wakeLatest: Long,
    minGapMs: Long = MIN_SLEEP_GAP_MS,
    maxGapMs: Long = MAX_SLEEP_GAP_MS,
): EstimatedSleep? = interactions
    .asSequence()
    .filter { it in startEarliest..wakeLatest }
    .distinct()
    .sorted()
    .zipWithNext()
    .map { (start, end) -> EstimatedSleep(start, end) }
    .filter { it.startAt in startEarliest..startLatest }
    .filter { it.endAt in wakeEarliest..wakeLatest }
    .filter { (it.endAt - it.startAt) in minGapMs..maxGapMs }
    .maxByOrNull { it.endAt - it.startAt }

internal fun hasUsageStatsAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    )
    // MODE_DEFAULT is "ask the permission", not "no": on installs where the op was never written —
    // a preload, a device-policy grant — the permission is the only place the answer lives.
    return if (mode == AppOpsManager.MODE_DEFAULT) {
        context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        mode == AppOpsManager.MODE_ALLOWED
    }
}

/**
 * The Usage access screen, on our own row where Settings takes the package. The platform documents
 * this screen as taking no input; builds whose filter has no such scheme reject it, and the plain
 * screen is the answer there — the order [settingsAttempts] keeps for onboarding's page too.
 */
internal fun ActivityResultLauncher<Intent>.openUsageAccess(context: Context) {
    openSettingsScreen(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.packageName)
}

/**
 * Turns the estimate from phone use on in the stored profile and reads the nights by it at once: a
 * course taken on waking is timed by the night it records.
 */
internal suspend fun enableSleepEstimate(context: Context) {
    val dao = StepsDb.get(context).dao()
    val profile = dao.profileNow() ?: return
    if (!profile.automaticSleepDetection) dao.saveProfile(profile.copy(automaticSleepDetection = true))
    withContext(Dispatchers.IO) { detectEstimatedSleep(context) }
}

/** One reading of the nights at a time; see [detectEstimatedSleep]. */
private val sleepDetection = Mutex()

/**
 * Whether the journal already holds [estimate] as the night's row, which is then left as it stands.
 * Saved again on every opening of the app whether or not it had changed, each of the week's estimated
 * nights would be a row stamped as edited by hand, published to Health Connect once more, the
 * medicine alarms worked out again and four widgets redrawn, seven times over, before anything else
 * the opening had to do.
 */
internal fun estimateIsOnFile(existing: JournalEntry?, estimate: EstimatedSleep): Boolean =
    existing != null && existing.sleepStartAt == estimate.startAt && existing.sleepEndAt == estimate.endAt

/**
 * Looks back over the latest seven night windows whenever PWN.Health is opened. No monitoring service
 * runs in the background, and of the usage events nothing is kept but the night they add up to: its
 * start and end, saved as a sleep row like any other, which goes to Health Connect with the rest of
 * the journal when that exchange is on ([writeLocalHealthIfAvailable]).
 *
 * One run at a time. The app coming to the front starts one, and so does switching the estimate on,
 * which is done on the way back from the Usage access page — a coming to the front as well. Side by
 * side, the two would each find a night without a row, and each write it one.
 */
internal suspend fun detectEstimatedSleep(context: Context): Int {
    val appContext = context.applicationContext
    val db = StepsDb.get(appContext)
    val saved = sleepDetection.withLock { estimateNights(appContext, db) }
    // Outside the lock: the exchange with Health Connect can take its time, and a second reading
    // of the nights has no need to wait for it.
    if (saved > 0) writeLocalHealthIfAvailable(appContext, db)
    return saved
}

// One guard per reason a night window cannot be estimated.
@Suppress("LongMethod")
private suspend fun estimateNights(appContext: Context, db: StepsDb): Int {
    val profile = db.dao().profileNow() ?: return 0
    if (!profile.automaticSleepDetection || !hasUsageStatsAccess(appContext)) return 0

    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val usage = appContext.getSystemService(UsageStatsManager::class.java)
    val prefs = appContext.getSharedPreferences(DETECTION_PREFS, Context.MODE_PRIVATE)
    val processed = nightsStillLookedAt(prefs.getStringSet(PROCESSED_DAYS, emptySet()).orEmpty(), today)
        .toMutableSet()
    var saved = 0

    repeat(LOOKBACK_NIGHTS) { daysAgo ->
        val wakeDay = today.minusDays(daysAgo.toLong())
        val dayKey = wakeDay.toString()
        val existing = db.dao().estimatedSleepOn(dayKey)
        if (dayKey in processed && existing == null) return@repeat
        // A measured night beats a guess from screen use, and two records of one night are worse
        // than either of them alone.
        if (db.dao().sleepOn(dayKey).any { it.measurementSource != PHONE_ESTIMATE }) {
            return@repeat
        }

        val window = nightWindow(wakeDay, zone, now) ?: return@repeat
        val interactions = awakeMoments(usage.eventsBetween(window.startEarliest, window.wakeLatest)) +
            window.openedNow(now)
        val estimate = window.estimate(interactions) ?: return@repeat
        if (estimateIsOnFile(existing, estimate)) return@repeat

        val entry = existing?.copy(
            day = dayKey,
            occurredAt = estimate.endAt,
            sleepStartAt = estimate.startAt,
            sleepEndAt = estimate.endAt,
        ) ?: JournalEntry(
            day = dayKey,
            occurredAt = estimate.endAt,
            kind = KIND_MEASUREMENT,
            title = appContext.getString(R.string.measurement_sleep_title),
            calories = 0.0,
            sleepStartAt = estimate.startAt,
            sleepEndAt = estimate.endAt,
            measurementSource = PHONE_ESTIMATE,
            note = appContext.getString(R.string.auto_sleep_event_note),
        )
        db.saveJournalEvent(entry)
        processed += dayKey
        saved++
    }

    if (saved > 0) prefs.edit { putStringSet(PROCESSED_DAYS, processed) }
    return saved
}

/**
 * The nights of [processed] a reading on [today] still looks at. A night is remembered so that one
 * whose estimate was deleted is not written again, which only matters while it is in the look-back;
 * kept for good, the set would take on a day for every night estimated and be read and written whole.
 */
internal fun nightsStillLookedAt(processed: Set<String>, today: LocalDate): Set<String> {
    val oldest = today.minusDays(LOOKBACK_NIGHTS - 1L).toString()
    return processed.filterTo(HashSet()) { it >= oldest }
}

/** Every event of the window, not only the interactions: DEVICE_STARTUP is what tells the two apart. */
private fun UsageStatsManager.eventsBetween(startAt: Long, endAt: Long): List<UsageEventRecord> {
    val events = queryEvents(startAt, endAt)
    val event = UsageEvents.Event()
    return buildList {
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            add(UsageEventRecord(event.eventType, event.timeStamp))
        }
    }
}

/**
 * The hours one night is looked for in: it starts between seven in the evening and four in the
 * morning, and ends between four and noon of the day woken on — or now, for a morning
 * still under way. Shared by the sleep estimate and the morning check-in's waking, so the two can
 * never disagree about which gap was the night.
 */
internal data class NightWindow(
    val startEarliest: Long,
    val startLatest: Long,
    val wakeEarliest: Long,
    val fullWakeLatest: Long,
    val wakeLatest: Long,
) {
    /**
     * The moment of looking, as one more sign of somebody awake: opening the app is a touch, and
     * the platform reports it a little late, so a night still ending now would otherwise run on.
     */
    fun openedNow(now: Long): List<Long> = listOfNotNull(now.takeIf { it in wakeEarliest..fullWakeLatest })

    fun estimate(interactions: List<Long>): EstimatedSleep? = estimateNightSleep(
        interactions = interactions,
        startEarliest = startEarliest,
        startLatest = startLatest,
        wakeEarliest = wakeEarliest,
        wakeLatest = wakeLatest,
    )
}

/** [wakeDay]'s night window, or null while its morning has not begun. */
internal fun nightWindow(wakeDay: LocalDate, zone: ZoneId, now: Long): NightWindow? {
    fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val fullWakeLatest = at(wakeDay, 12)
    val window = NightWindow(
        startEarliest = at(wakeDay.minusDays(1), 19),
        startLatest = at(wakeDay, 4),
        wakeEarliest = at(wakeDay, 4),
        fullWakeLatest = fullWakeLatest,
        wakeLatest = minOf(fullWakeLatest, now),
    )
    return window.takeIf { it.wakeLatest >= it.wakeEarliest }
}

/**
 * When the phone was first picked up after the night: the night is the gap [detectEstimatedSleep]
 * would find, by its own rules and from the same [awakeMoments], and the waking is the first touch
 * or unlock at or after the gap's end. Usually that is the end itself. It is later when the gap was
 * closed by something no hand did — a notification lighting the screen, an alarm's own page coming
 * up. Where no hand followed before noon there is no phone waking: a screen nobody touched is not a
 * use of the phone, and the check-in would print it as one.
 *
 * The moment of looking closes a night still under way, as it does for the sleep estimate, but it
 * is never the waking itself: a morning whose only sign of a hand is the app being opened has no
 * phone waking, since that is the moment of answering, which the check-in offers as just that. The
 * unlock and the taps that open the app are reported a little before the moment of looking, so a
 * first touch within [OPENING_SPAN_MS] of it is the opening, not a waking. The span is timed from
 * the moment of looking — the page reading the phone — not from the app's own opening: an unlock
 * into the app minutes before the morning page is opened is offered as the first use, and it is.
 *
 * Null when the window holds no night — too little use to see one, or a phone in use all night —
 * when no hand followed the night, and when the waking would be the opening of the app.
 */
internal fun phoneWakeAt(events: List<UsageEventRecord>, window: NightWindow, now: Long): Long? {
    val opened = window.openedNow(now)
    val night = window.estimate(awakeMoments(events) + opened)
    val touches = events.filter { it.type in HUMAN_EVENT_TYPES }.map { it.at }
    val waking = night?.let { found -> touches.filter { it in found.endAt..window.wakeLatest }.minOrNull() }
    return waking?.takeIf { opened.isEmpty() || now - it >= OPENING_SPAN_MS }
}

/** The source the sleep estimate files its nights under: the phone's own reading, never a record. */
internal const val PHONE_ESTIMATE = "PHONE_USAGE_ESTIMATE"

/**
 * How long before the moment of looking a first touch still belongs to opening the app: the unlock,
 * the launcher's tap and the page's own taps come within seconds of each other.
 */
internal const val OPENING_SPAN_MS = 2L * 60 * 1_000

/**
 * This morning's [phoneWakeAt] read off the phone, for the morning check-in. Null without the usage
 * access the sleep estimate already asks for, or with no night in the window. Not gated on the
 * estimate's own switch: that one decides whether a guessed night is written into the journal, and
 * nothing here is written until the person files the morning with the time in front of them.
 */
internal suspend fun phoneWakeOn(context: Context, day: LocalDate): Long? = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val now = System.currentTimeMillis()
    val window = nightWindow(day, ZoneId.systemDefault(), now)
    if (window == null || !hasUsageStatsAccess(app)) {
        null
    } else {
        val usage = app.getSystemService(UsageStatsManager::class.java)
        runCatching { phoneWakeAt(usage.eventsBetween(window.startEarliest, window.wakeLatest), window, now) }
            .getOrNull()
    }
}
