package com.pwnall.health

import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/*
 * The bookkeeping that lets [HealthImport] ask Health Connect what changed instead of reading a year
 * of every type at every return to the foreground.
 *
 * Health Connect issues a change token on request and, handed it back, answers with every record
 * upserted or deleted since — in pages, each ending in the token for the next ask. One token is kept
 * per record type, because a deletion names only the record's id and the type it belonged to is
 * otherwise unknowable. The tokens live in the sync preferences beside the adoption instant, so a
 * reinstall or a cleared store starts over with the full read. The decisions are pure so a test can
 * hold them; the two calls that reach the client are guarded the way the import's own
 * reads are.
 */

/**
 * Where the sync preferences keep one type's change token.
 *
 * The key is the type's read-permission string, not its class name: R8 renames classes in a release
 * build and reassigns the names between versions, so a class-name key would hand one type another's
 * token after an update — silently, and for good, since a token that is used never expires.
 */
internal fun changesTokenKey(recordType: KClass<out Record>): String =
    "changesToken:${HealthPermission.getReadPermission(recordType)}"

/**
 * The types whose import is an aggregation over the whole window rather than a row per record.
 *
 * The longest sleep of a night and the latest heart rate of a day are chosen among every record the
 * window holds, and a feed of the records that changed cannot answer "the longest of the night" —
 * the winner may be one that did not change. Both keep the full read. The step-day totals are
 * aggregated by Health Connect itself in `syncStepDays` and never pass through the import at all.
 */
internal val AGGREGATED_IMPORT_TYPES: Set<KClass<out Record>> =
    setOf(HeartRateRecord::class, SleepSessionRecord::class)

/**
 * The stretch of time one import reads. Health Connect is asked for the records between [start] and
 * [end]; once a type is read only for what changed, [start] is also the edge behind which an
 * upserted record is history the window has already left — a source app rewriting years of it would
 * otherwise pour them into the journal in one exchange, and an imported row is never swept. The far
 * edge is deliberately not enforced there: a record dated after the exchange's snapshot of now was
 * written while the exchange ran, the feed has moved past it and would never offer it again, and
 * time brings the window to it anyway — a full read would have met it on a later day.
 */
internal data class ImportWindow(val start: Instant, val end: Instant) {
    /** The window as Health Connect is asked for it. */
    fun filter(): TimeRangeFilter = TimeRangeFilter.between(start, end)

    /** Whether a row dated [occurredAt] is history this window has already left behind. */
    fun leftBehind(occurredAt: Long): Boolean = occurredAt < start.toEpochMilli()
}

/** What one exchange does for one record type, decided from the token it kept last time. */
internal sealed interface ImportPlan {
    /**
     * Read the whole window. [seedToken] asks for a change token before the read, so that the next
     * exchange can ask only for what changed; false for a type that always reads whole.
     */
    data class Full(val seedToken: Boolean) : ImportPlan

    /** Ask only for what changed since [token] was issued. */
    data class Incremental(val token: String) : ImportPlan
}

/**
 * The plan for a type. A read narrowed to some origins is a whole read too: a token is issued for
 * the type entire, and the next exchange would then push every other origin's records through a
 * mapping meant for these alone.
 */
internal fun importPlan(
    recordType: KClass<out Record>,
    originFiltered: Boolean,
    storedToken: String?,
): ImportPlan = when {
    recordType in AGGREGATED_IMPORT_TYPES || originFiltered -> ImportPlan.Full(seedToken = false)
    storedToken.isNullOrEmpty() -> ImportPlan.Full(seedToken = true)
    else -> ImportPlan.Incremental(storedToken)
}

/**
 * The Health Connect record a row's import marker stands for, or null for a key that names none.
 * The marker is "Health Connect:<package>:<id>", a period's end row hangs "#end" off the same
 * marker, and a deletion carries the id alone. An adopted row has no marker and answers no
 * deletion, which is right: it is this install's own event now.
 */
internal fun markedRecordId(marker: String?): String? = marker
    ?.takeIf { it.startsWith(HEALTH_IMPORT_MARKER_PREFIX) }
    ?.removePrefix(HEALTH_IMPORT_MARKER_PREFIX)
    ?.substringAfter(':', "")
    ?.substringBefore('#')
    ?.takeIf { it.isNotEmpty() }

/**
 * One page of a type's feed as [HealthConnectClient.getChanges] answers it, in a shape a test can
 * script — the library builds its own response and lets nobody else.
 */
internal class ChangesPage(
    val changes: List<Change>,
    val nextToken: String,
    val hasMore: Boolean,
    val expired: Boolean,
)

/** Every page one token yielded, or the reason none could be read. */
internal sealed interface ChangeFeed {
    /** Health Connect forgot the token — thirty days unused — and the window has to be read whole again. */
    data object Expired : ChangeFeed

    /** Reading this type is no longer allowed. The token is kept for the day it is again. */
    data object Denied : ChangeFeed

    /** The changes in the order they happened, and the token the next exchange asks with. */
    data class Changes(val changes: List<Change>, val nextToken: String) : ChangeFeed
}

/**
 * Follows one type's token through every page it has. Order is kept: a record upserted and then
 * deleted in the same stretch has to end deleted, which separate lists would not promise.
 */
@Suppress("SwallowedException")
internal suspend fun readChangeFeed(token: String, fetch: suspend (String) -> ChangesPage): ChangeFeed {
    val changes = mutableListOf<Change>()
    var next = token
    var page: ChangesPage
    do {
        val fetched = runCatching { fetch(next) }
        val early = fetched.exceptionOrNull()?.let { failure ->
            when (failure) {
                is CancellationException -> throw failure

                // A type whose grant was withdrawn since the token was issued imports nothing this time.
                is SecurityException -> ChangeFeed.Denied

                // A token the service no longer recognises is not always reported through the expiry
                // flag; either way the answer is the same — read the window whole, keep a fresh token.
                is IllegalArgumentException -> ChangeFeed.Expired

                else -> throw failure
            }
        }
        if (early != null) return early
        page = fetched.getOrThrow()
        changes += page.changes
        next = page.nextToken
    } while (page.hasMore && !page.expired)
    return if (page.expired) ChangeFeed.Expired else ChangeFeed.Changes(changes, next)
}

/**
 * A change token for one type, or null when Health Connect would not issue one — a grant missing,
 * the service away — in which case the type simply reads whole again next time. A cancellation is
 * not a refusal and goes on up.
 */
internal suspend fun changesTokenFor(client: HealthConnectClient, recordType: KClass<out Record>): String? =
    runCatching { client.getChangesToken(ChangesTokenRequest(setOf(recordType))) }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

/** Every stored change token begins with this; a version change retires them all at once. */
private const val CHANGES_TOKEN_PREFIX = "changesToken:"
private const val KEY_CHANGES_TOKENS_VERSION = "changesTokensVersion"

/**
 * Where a type's token waits once an update has retired it: no longer what the next read asks with,
 * and still the only thing that can name the records deleted since it was issued. The night, whose
 * type is never read by a token ([AGGREGATED_IMPORT_TYPES]), keeps one here from the start, for the
 * deletions alone.
 */
internal fun retiredTokenKey(tokenKey: String): String = "retired:$tokenKey"

/**
 * What an update files away out of the [stored] preferences: every live token, under its retired
 * key. A token already waiting there is the older of the two and reaches further back, so it stays.
 */
internal fun retiredTokens(stored: Map<String, *>): Map<String, String> =
    stored.filterKeys { it.startsWith(CHANGES_TOKEN_PREFIX) && retiredTokenKey(it) !in stored }
        .mapNotNull { (key, token) -> (token as? String)?.let { retiredTokenKey(key) to it } }
        .toMap()

/**
 * Retires every change token when the app's version has changed since they were taken.
 *
 * A whole read re-places every record it meets, which is how a mapping fixed in an update reaches
 * rows imported before it. The incremental path refreshes a row only when its source record changes,
 * so an update that reads a record differently would leave the earlier reading standing for good.
 * One whole read per app version keeps that maintenance path — the cost of a year's read once after
 * each update, not on every launch.
 *
 * Retired rather than dropped. A whole read finds what stands in Health Connect and never what has
 * gone from it, so a record its source deleted since the last exchange would keep the row it was
 * imported into for good, and one rewritten under a new id would stand twice. The old token still answers with
 * those deletions, and the whole read asks it for them before it starts.
 */
internal fun retireChangeTokensIfAppChanged(prefs: android.content.SharedPreferences, versionCode: Long) {
    if (prefs.getLong(KEY_CHANGES_TOKENS_VERSION, -1L) == versionCode) return
    val stored = prefs.all
    prefs.edit {
        retiredTokens(stored).forEach { (key, token) -> putString(key, token) }
        stored.keys.filter { it.startsWith(CHANGES_TOKEN_PREFIX) }.forEach(::remove)
        putLong(KEY_CHANGES_TOKENS_VERSION, versionCode)
    }
}
