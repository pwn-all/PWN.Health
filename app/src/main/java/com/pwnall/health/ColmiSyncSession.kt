package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

data class ColmiHistorySnapshot(
    val model: ColmiRingModel,
    /** Pseudonymous local key: the raw Bluetooth address is never persisted. */
    val sourceId: String,
    val battery: ColmiBatteryStatus,
    val samples: List<ColmiSportSample>,
    val syncedAt: Long,
)

sealed interface ColmiSyncResult {
    data object AwaitingMore : ColmiSyncResult

    class RequestHistory(
        val battery: ColmiBatteryStatus,
        val payload: ByteArray,
    ) : ColmiSyncResult

    data class Complete(val snapshot: ColmiHistorySnapshot) : ColmiSyncResult

    data class Rejected(val detail: String) : ColmiSyncResult
}

/** One bounded battery-handshake + today's step-history exchange. */
internal class ColmiSyncSession(
    private val model: ColmiRingModel,
    private val sourceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /** Each stage with the command its answer comes under. */
    private enum class Stage(val answeredBy: Int?) {
        BATTERY(BluetoothColmiRing.COMMAND_BATTERY),
        HISTORY(BluetoothColmiRing.COMMAND_SPORT_HISTORY),
        COMPLETE(null),
    }

    private var stage = Stage.BATTERY
    private var battery: ColmiBatteryStatus? = null
    private val history = ColmiSportHistoryAssembler()

    fun firstRequest(): ByteArray = BluetoothColmiRing.batteryRequest()

    fun accept(payload: ByteArray): ColmiSyncResult {
        // A ring also speaks unprompted, and a well-formed frame of another command is not the answer
        // this stage waits for: it is set aside rather than failing the whole sync. A broken frame
        // still fails it, and a ring that never answers still meets the watchdog.
        val command = BluetoothColmiRing.frameCommand(payload)
        if (command != null && stage.answeredBy != null && command != stage.answeredBy) {
            return ColmiSyncResult.AwaitingMore
        }
        return answer(payload)
    }

    private fun answer(payload: ByteArray): ColmiSyncResult = when (stage) {
        Stage.BATTERY -> {
            val status = BluetoothColmiRing.parseBattery(payload)
                ?: return ColmiSyncResult.Rejected("battery_packet")
            battery = status
            stage = Stage.HISTORY
            ColmiSyncResult.RequestHistory(
                battery = status,
                payload = BluetoothColmiRing.sportHistoryRequest(dayOffset = 0),
            )
        }

        Stage.HISTORY -> when (val result = history.accept(payload)) {
            ColmiSportHistoryResult.AwaitingMore -> ColmiSyncResult.AwaitingMore

            ColmiSportHistoryResult.NoData -> complete(emptyList())

            is ColmiSportHistoryResult.Complete -> complete(result.samples)

            is ColmiSportHistoryResult.Rejected ->
                ColmiSyncResult.Rejected("history_${result.reason.name.lowercase()}")
        }

        Stage.COMPLETE -> ColmiSyncResult.Rejected("unexpected_packet")
    }

    private fun complete(samples: List<ColmiSportSample>): ColmiSyncResult {
        val status = battery ?: return ColmiSyncResult.Rejected("missing_battery")
        val syncedAt = clock()
        val today = Instant.ofEpochMilli(syncedAt).atZone(zoneId).toLocalDate()
        val dated = samples.withinADayOf(today)
        // Every record off by more than a day is the ring's clock, not a stray record. Saying so
        // beats quietly saving an empty day that looks like a day nobody walked.
        if (samples.isNotEmpty() && dated.isEmpty()) return ColmiSyncResult.Rejected("history_clock_unset")
        stage = Stage.COMPLETE
        return ColmiSyncResult.Complete(
            ColmiHistorySnapshot(
                model = model,
                sourceId = sourceId,
                battery = status,
                samples = dated,
                syncedAt = syncedAt,
            ),
        )
    }
}

/**
 * Stable local provenance without putting a raw MAC address into the database or backup.
 *
 * Deliberately unsalted, unlike [directSourceId]. This digest is the primary key of
 * direct_ble_step_days, and a key has to be reproducible by whoever needs to find the row again:
 * an install-specific secret would strand every day already synced on upgrade, and — since the
 * secret lives in preferences the export does not carry — would mint a third key again after a
 * backup is restored on a new phone, leaving the imported history orphaned. The address digest is
 * only pseudonymous, which is the price of that; it is paid here and not in [directSourceId].
 */
internal fun colmiSourceId(address: String, model: ColmiRingModel): String {
    val digest = sourceIdDigest("colmi", salt = "", subject = "${model.name}:$address")
    return "COLMI_${model.name}_$digest"
}

/**
 * The same identity for the devices that are not rings. A blood-pressure monitor, a scale or a
 * meter announces nothing that tells it apart from the next one of its make, so without this a
 * reading in the journal cannot be traced to the thing that produced it.
 *
 * The salt is not optional here: nothing already stored is keyed on this digest, so there is no
 * unsalted form to stay compatible with.
 */
internal fun directSourceId(context: Context, address: String): String =
    directSourceId(address, colmiInstallSalt(context))

internal fun directSourceId(address: String, salt: String): String =
    "BLE_${sourceIdDigest("device", salt, address)}"

private fun sourceIdDigest(scope: String, salt: String, subject: String): String {
    // Without a salt the digested string must stay byte-for-byte as it is: the colmi
    // key is a primary key of direct_ble_step_days, and changing it strands every day synced.
    val salted = if (salt.isEmpty()) "" else "$salt:"
    return MessageDigest.getInstance("SHA-256")
        .digest("pwn-fit:$scope:$salted$subject".toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

/**
 * Random secret written once per install, and used by [directSourceId] alone.
 *
 * It stays in app-private preferences. data_extraction_rules.xml keeps those out of cloud backup; a
 * device-to-device transfer does carry them, together with the journal rows that hold ids made
 * with this salt, so a device keeps its id on the new phone. The app's own JSON export never reads
 * it — so the mapping from a device's address to its BLE_ source id cannot be reproduced from an
 * export. A ring's key ([colmiSourceId]) is deliberately unsalted and has no such protection.
 */
@Synchronized
internal fun colmiInstallSalt(context: Context): String {
    val prefs = context.getSharedPreferences(COLMI_PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_INSTALL_SALT, null)?.let { return it }
    val salt = ByteArray(INSTALL_SALT_BYTES)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    prefs.edit { putString(KEY_INSTALL_SALT, salt) }
    return salt
}

private const val COLMI_PREFS = "colmi_source"
private const val KEY_INSTALL_SALT = "install_salt"
private const val INSTALL_SALT_BYTES = 16
