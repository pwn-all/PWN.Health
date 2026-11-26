package com.pwnall.health

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The three protocols a training machine can report a workout over, all of them local.
 *
 * Each is a published wire format read passively: the Bluetooth SIG's own Fitness Machine Service,
 * Concept2's published PM5 service, and Keiser's published M Series broadcast. None of them needs
 * an account, a manufacturer app or the network, which is why they are the three this app can have.
 */
internal val MACHINE_KINDS = setOf(
    DirectDeviceKind.FITNESS_MACHINE,
    DirectDeviceKind.KEISER_M3I,
    DirectDeviceKind.CONCEPT2_PM5,
)

/** What a machine handed over, in the shape the exercise form keeps its own numbers. */
internal data class MachineResult(
    val minutes: Double,
    val kcal: Int?,
    val note: String,
    val payloadJson: String,
    /** What the machine says it is, for a page that has no exercise chosen to file the numbers under. */
    val type: FitnessMachineType = FitnessMachineType.UNKNOWN,
    /** The ride's average speed, or the last one the console gave; null where it gave none. */
    val kph: Double? = null,
)

/** The protocol's own name, for the provenance line on a saved workout. */
private fun DirectDeviceKind.machineProtocol(): String = when (this) {
    DirectDeviceKind.CONCEPT2_PM5 -> "Concept2 PM5"
    DirectDeviceKind.KEISER_M3I -> "Keiser M Series"
    else -> "Bluetooth SIG FTMS"
}

/**
 * The live link and everything it has said, held apart from the composition that draws it.
 *
 * A rotation, a font-size change or split-screen destroys the composition, and with it a plain
 * `remember` — including the phone-side stopwatch for a console that reports no elapsed time, so a
 * half-hour ride would come back as the one-minute floor. The Bluetooth screen already solves this
 * by retaining its session across a configuration change; this is the same answer for the exercise
 * form. A rebuilt window whose first frame does not draw the form — one that opens behind the app
 * lock — lets the session go, link and all, where a slot of the app's own would keep the link open
 * until the process ended.
 */
private class MachineSession {
    val devices = mutableStateListOf<DirectDevice>()
    val scanning = mutableStateOf(false)
    val connection = mutableStateOf(DirectConnectionState.IDLE)
    val deviceName = mutableStateOf<String?>(null)

    /** Why the link failed, kept apart from the name so an error neither renames the machine nor goes unsaid. */
    val errorDetail = mutableStateOf<String?>(null)
    val kind = mutableStateOf(DirectDeviceKind.FITNESS_MACHINE)
    val measurement = mutableStateOf<FitnessMachineMeasurement?>(null)

    /** When the phone started counting, on elapsedRealtime, for a machine whose own clock says nothing. */
    val startedAt = mutableStateOf<Long?>(null)
    var manager: DirectBluetoothManager? = null
}

/**
 * The machine beside the exercise: choose one, ride it, and take its numbers into the form.
 *
 * The equipment is asked for from the exercise itself rather than from a device screen somewhere
 * else, because the question "which bike am I on" only has an answer while somebody is on it. What
 * comes back is read-only — this app never tells a machine what resistance to hold — and it lands
 * in the same fields a hand-typed session fills, so the summary before Done is the same summary,
 * and every number on it can still be corrected.
 */
@Composable
internal fun MachineSection(open: Boolean, onOpen: (Boolean) -> Unit, onResult: (MachineResult) -> Unit) {
    val context = LocalContext.current
    val session = retain { MachineSession() }
    val devices = session.devices
    var scanning by session.scanning
    var connection by session.connection
    var deviceName by session.deviceName
    var errorDetail by session.errorDetail
    var kind by session.kind
    var measurement by session.measurement
    var startedAt by session.startedAt

    val manager = remember {
        session.manager ?: DirectBluetoothManager(
            context = context,
            onDevicesChanged = {
                devices.clear()
                devices.addAll(it)
            },
            onScanningChanged = { scanning = it },
            onConnectionChanged = { state, detail ->
                if (!hangUpEcho(connection, state)) {
                    connection = state
                    if (state != DirectConnectionState.ERROR && detail != null) deviceName = detail
                    errorDetail = detail.takeIf { state == DirectConnectionState.ERROR }
                    if (state == DirectConnectionState.CONNECTING) measurement = null
                }
            },
            // A machine screen collects a machine. The remaining profiles stay unwired rather than
            // half-wired: a strap's reading has a form of its own and does not belong in a session.
            onScaleMeasurement = { _, _ -> },
            onFitnessMeasurement = {
                measurement = it
                if (startedAt == null) startedAt = SystemClock.elapsedRealtime()
            },
            onHeartRate = {},
            onRunning = {},
        ).also { session.manager = it }
    }
    // Leaving the form for good ends the ride; being rebuilt by a configuration change does not,
    // and the link has to outlive it or the stopwatch restarts from zero. A rebuilt window that
    // does not draw the form again ends it too, where a global slot would keep it open.
    RetainedEffect(manager) { onRetire { manager.close() } }

    if (open) {
        DevicePickerDialog(
            manager = manager,
            devices = devices,
            scanning = scanning,
            kinds = MACHINE_KINDS,
            onPick = { device ->
                // Choosing replaces what was chosen before it, link included — see choose().
                measurement = null
                kind = device.kind
                deviceName = device.name
                startedAt = null
                manager.choose(device)
                onOpen(false)
            },
            onDismiss = { onOpen(false) },
        )
    }

    // Put away by hand, a result taken or a Disconnect: the link's own report of the drop follows and
    // is let go ([hangUpEcho]).
    fun hangUp() {
        manager.disconnect()
        measurement = null
        startedAt = null
        connection = DirectConnectionState.IDLE
    }

    if (connection == DirectConnectionState.IDLE) return
    Spacer(Modifier.height(10.dp))
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Text(
                deviceName ?: stringResource(R.string.direct_bluetooth_live_workout),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                kind.machineProtocol(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val live = measurement
            MachineStatus(connection, waiting = live == null, deviceName, errorDetail)
            if (live != null) {
                MachineMetrics(live)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onResult(live.toResult(kind, deviceName, startedAt))
                        hangUp()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.machine_take_result)) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = ::hangUp,
                modifier = Modifier.fillMaxWidth(),
                // An outline edge: Material's default tonal hairline all but vanishes on a card.
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) { Text(stringResource(R.string.direct_bluetooth_disconnect)) }
        }
    }
}

/**
 * Where the link stands, in one line that stays in place while its words change.
 *
 * One node rather than one per state, and a live region, so a screen reader says so when the link
 * comes up, starts waiting for data or fails — the only moments the card changes without being
 * touched. A failure interrupts; the rest wait their turn. The ride's own figures replace the line
 * once they arrive.
 */
@Composable
private fun MachineStatus(
    connection: DirectConnectionState,
    waiting: Boolean,
    deviceName: String?,
    errorDetail: String?,
) {
    val failed = connection == DirectConnectionState.ERROR
    val status = when {
        connection == DirectConnectionState.CONNECTING ->
            stringResource(R.string.direct_bluetooth_connecting, deviceName.orEmpty())

        // The manager's token as a sentence, as the Devices page says it: "link:133" tells nobody what
        // to do next.
        failed -> stringResource(directBluetoothErrorRes(errorDetail))

        connection == DirectConnectionState.CONNECTED && waiting ->
            stringResource(R.string.direct_bluetooth_waiting_data)

        else -> null
    } ?: return
    Text(
        status,
        Modifier.semantics { liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whether the link's report of [reported] is only the echo of a hang-up the card, standing at
 * [shown], has already acted on: the drop that follows a Disconnect or a result taken. Applied, it
 * would bring the card back with nothing on it but a second Disconnect to press.
 */
internal fun hangUpEcho(shown: DirectConnectionState, reported: DirectConnectionState): Boolean =
    shown == DirectConnectionState.IDLE && reported == DirectConnectionState.DISCONNECTED

@Composable
private fun MachineMetrics(measurement: FitnessMachineMeasurement) {
    DirectMetric(
        stringResource(R.string.editor_time),
        measurement.elapsedSeconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
    )
    // Each figure through a string of its own, so a unit reads in the reader's language rather
    // than as an English abbreviation on every locale.
    DirectMetric(
        stringResource(R.string.direct_bluetooth_distance),
        measurement.totalDistanceMeters?.let { stringResource(R.string.track_distance, it / 1_000.0) },
    )
    DirectMetric(
        stringResource(R.string.direct_bluetooth_energy),
        measurement.totalEnergyKcal?.let { "$it ${stringResource(R.string.unit_kcal)}" },
    )
    DirectMetric(
        stringResource(R.string.direct_bluetooth_power),
        measurement.powerWatts?.let { stringResource(R.string.machine_power_value, it) },
    )
    DirectMetric(
        stringResource(R.string.direct_bluetooth_cadence),
        measurement.cadenceRpm?.let { stringResource(R.string.machine_cadence_rpm, it.roundToInt()) }
            ?: measurement.strokeRateSpm?.let { stringResource(R.string.machine_stroke_rate, it.roundToInt()) },
    )
    DirectMetric(
        stringResource(R.string.heart_rate),
        measurement.heartRateBpm?.let { stringResource(R.string.measurement_heart_rate_value, it) },
    )
}

/**
 * The machine's reading as the form's own numbers.
 *
 * A console that reports no elapsed time is timed by the phone from the first packet instead, which
 * is what the Sync tab does for the same machines. The console's own calorie figure is kept where
 * it exists: it was measured from the work done, and the MET estimate the form would otherwise
 * compute is an average for a population rather than a number about this ride.
 */
private fun FitnessMachineMeasurement.toResult(
    kind: DirectDeviceKind,
    deviceName: String?,
    startedAt: Long?,
): MachineResult {
    val protocol = kind.machineProtocol()
    // elapsedRealtime, as every other stopwatch here: on the wall clock, a clock set during the ride
    // would move its length.
    val now = SystemClock.elapsedRealtime()
    val seconds = elapsedSeconds?.takeIf { it > 0 }
        ?: ((now - (startedAt ?: now)) / 1_000L).coerceAtLeast(MIN_MACHINE_SECONDS).toInt()
    return MachineResult(
        minutes = seconds / 60.0,
        kcal = totalEnergyKcal?.takeIf { it > 0 },
        note = directMeasurementNote(protocol, deviceName),
        payloadJson = fitnessPayload(this, protocol),
        type = type,
        kph = totalDistanceMeters?.takeIf { it > 0 }?.let { it * 3.6 / seconds } ?: speedKph,
    )
}

/** A machine that reports no clock still ran for at least this long, or nobody would have used it. */
private const val MIN_MACHINE_SECONDS = 60L
