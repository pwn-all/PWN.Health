package com.pwnall.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** What one exchange with the Bangle.js 2 app brought, and the local day its step count is for. */
data class BangleJsSync(
    /** Salted pseudonymous key ([directSourceId]); the watch's address is never stored. */
    val sourceId: String,
    val day: LocalDate,
    val health: BangleJsHealthSnapshot,
)

sealed interface BangleJsSyncResult {
    data object AwaitingMore : BangleJsSyncResult

    data class Complete(val sync: BangleJsSync) : BangleJsSyncResult

    data class Rejected(val detail: String) : BangleJsSyncResult
}

/** One `PWN_HEALTH 1` request and the line that answers it, fed TX notifications as they arrive. */
internal class BangleJsSyncSession(
    private val sourceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val decoder = BangleJsLineDecoder()

    fun request(): ByteArray = BangleJsOfflineProtocol.snapshotRequest()

    fun accept(chunk: ByteArray): BangleJsSyncResult {
        // Other watch apps write to the same UART: a blank line, or an object with fields of its own
        // such as {"t":"status",…}. Those are set aside; a broken answer fails the sync, and a watch
        // that never answers meets the manager's watchdog.
        val frame = decoder.feed(chunk).firstOrNull {
            it !is BangleJsFrameResult.Rejected || it.reason !in OTHER_APPS_LINES
        }
        return when (frame) {
            null -> BangleJsSyncResult.AwaitingMore
            is BangleJsFrameResult.Snapshot -> complete(frame.value)
            is BangleJsFrameResult.Rejected -> BangleJsSyncResult.Rejected("bangle_${frame.reason.name.lowercase()}")
        }
    }

    /**
     * The count is the watch's total for the day its clock is on, filed under that day in the
     * phone's zone. As with the COLMI ring, a day more than one away from the phone's is the watch's
     * clock and not a day.
     */
    private fun complete(health: BangleJsHealthSnapshot): BangleJsSyncResult {
        val day = Instant.ofEpochMilli(health.timestampMillis).atZone(zoneId).toLocalDate()
        val today = Instant.ofEpochMilli(clock()).atZone(zoneId).toLocalDate()
        if (abs(ChronoUnit.DAYS.between(today, day)) > 1L) return BangleJsSyncResult.Rejected("bangle_clock_unset")
        return BangleJsSyncResult.Complete(BangleJsSync(sourceId, day, health))
    }

    private companion object {
        val OTHER_APPS_LINES = setOf(BangleJsRejectReason.EMPTY_FRAME, BangleJsRejectReason.UNKNOWN_FIELD)
    }
}
