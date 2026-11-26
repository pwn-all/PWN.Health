package com.pwnall.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Which devices can answer the question this form is asking.
 *
 * Deliberately narrow per mode: offering a heart-rate strap under a weight, or a scale under a
 * pulse, turns a device list into a catalogue of everything the app has ever supported and leaves
 * the person to work out which row could possibly help. A night's sleep has no device path at all
 * here, so its card is not drawn rather than drawn and useless.
 *
 * Narrow is not the same as short-changed: a Body Composition Service measurement carries a height
 * field, so the height form offers those scales too. Withholding them would make the same scale
 * available under "weight and height" and hidden under height alone, where height is the point.
 */
internal fun measurementDeviceKinds(mode: String): Set<DirectDeviceKind> = when (mode) {
    "WEIGHT", "BOTH", "HEIGHT" -> setOf(DirectDeviceKind.SCALE, DirectDeviceKind.BODY_COMPOSITION)
    "HEART_RATE" -> setOf(DirectDeviceKind.HEART_RATE)
    else -> emptySet()
}

/**
 * "Take it from a device", answered where it is asked.
 *
 * Not a door to the Sync tab, where the person would leave the half-filled form, find their scale
 * on another screen, save a row from there, and come back to a form that knew nothing about it.
 * The list opens over this form, the reading lands in the fields already on screen, and Save is
 * still the person's own — a scale is allowed to suggest a number, not to file it.
 */
@Composable
internal fun MeasurementDeviceCard(mode: String, onReading: (DeviceReading) -> Unit) {
    val kinds = measurementDeviceKinds(mode)
    if (kinds.isEmpty()) return

    val context = LocalContext.current
    val devices = remember { mutableStateListOf<DirectDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(DirectConnectionState.IDLE) }
    var deviceName by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<DeviceReading?>(null) }
    var open by remember { mutableStateOf(false) }

    val manager = remember {
        DirectBluetoothManager(
            context = context,
            onDevicesChanged = {
                devices.clear()
                devices.addAll(it)
            },
            onScanningChanged = { scanning = it },
            onConnectionChanged = { state, detail ->
                connection = state
                if (state != DirectConnectionState.ERROR && detail != null) deviceName = detail
                if (state == DirectConnectionState.CONNECTING) reading = null
            },
            onScaleMeasurement = { measurement, _ ->
                reading = DeviceReading(
                    weightKg = measurement.weightKg,
                    heightCm = measurement.heightCm,
                    source = "BLUETOOTH_SCALE",
                )
            },
            onFitnessMeasurement = {},
            onHeartRate = {
                reading = DeviceReading(heartRateBpm = it.bpm, source = "BLUETOOTH_HEART_RATE")
            },
            onRunning = {},
            onProfileMeasurement = { profile ->
                (profile as? DirectProfileMeasurement.BodyComposition)?.value?.let { body ->
                    reading = DeviceReading(
                        weightKg = body.weightKg,
                        heightCm = body.heightCm?.roundToInt(),
                        bodyFatPercent = body.bodyFatPercent,
                        source = "BLUETOOTH_SCALE",
                    )
                }
            },
        )
    }
    DisposableEffect(manager) { onDispose(manager::close) }

    if (open) {
        DevicePickerDialog(
            manager = manager,
            devices = devices,
            scanning = scanning,
            kinds = kinds,
            onPick = { device ->
                // Choosing replaces whatever was chosen before it: a graceful disconnect does not
                // free the handle in time, so the second tap would otherwise be refused while the first
                // device went on talking. A scale that only broadcasts is never dialled at all —
                // stepping on it is the whole interaction — and choose() knows the difference.
                reading = null
                deviceName = device.name
                manager.choose(device)
                open = false
            },
            onDismiss = { open = false },
        )
    }

    PwnCard(Modifier.fillMaxWidth(), onClick = { open = true }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bluetooth, null, tint = PwnMuted, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.measurement_device_sync),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        deviceName ?: stringResource(deviceSyncBodyRes(mode)),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The card opens the list: an arrow that points on, drawn and mirrored with the
                // layout, and silent, where a typed "›" would be read out as a character.
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
            }
            ConnectionStatus(connection, deviceName, waiting = reading == null)
            reading?.let { taken ->
                Spacer(Modifier.height(10.dp))
                DirectMetric(
                    stringResource(R.string.weight),
                    taken.weightKg?.let { stringResource(R.string.direct_bluetooth_weight_value, it.shown()) },
                )
                // The unit from the resources, as the weight beside it is: "cm" typed in would stand in
                // Latin letters in every language the card is otherwise translated into.
                DirectMetric(
                    stringResource(R.string.height),
                    taken.heightCm?.let { stringResource(R.string.unit_cm_value, it) },
                )
                DirectMetric(
                    stringResource(R.string.heart_rate),
                    taken.heartRateBpm?.let { stringResource(R.string.measurement_heart_rate_value, it) },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onReading(taken)
                        manager.disconnect()
                        reading = null
                        connection = DirectConnectionState.IDLE
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.machine_take_result)) }
            }
            if (connection == DirectConnectionState.CONNECTED || connection == DirectConnectionState.CONNECTING) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        manager.disconnect()
                        reading = null
                        connection = DirectConnectionState.IDLE
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_disconnect)) }
            }
        }
    }
}

/**
 * Where the connection stands, in one line that a screen reader announces as it changes.
 *
 * The slot is always there, empty or not, so the line is one node whose words change rather than
 * a node appearing and vanishing — the change is what a polite live region speaks, without taking
 * the person's focus off the form. A failed connection says so here rather than falling back to
 * the card's resting text with nothing said at all. The manager's detail is a raw code, not words, so
 * only the device's name goes into the line. A plain disconnect says nothing, since taking a
 * reading ends with one.
 */
@Composable
private fun ConnectionStatus(connection: DirectConnectionState, deviceName: String?, waiting: Boolean) {
    val status = when {
        connection == DirectConnectionState.CONNECTING ->
            stringResource(R.string.direct_bluetooth_connecting, deviceName.orEmpty())

        connection == DirectConnectionState.CONNECTED && waiting ->
            stringResource(R.string.direct_bluetooth_waiting_data)

        connection == DirectConnectionState.ERROR ->
            stringResource(R.string.direct_bluetooth_error, deviceName.orEmpty())

        else -> null
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        status?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                color = if (connection == DirectConnectionState.ERROR) MaterialTheme.colorScheme.error else PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun deviceSyncBodyRes(mode: String): Int = if (mode == "HEART_RATE") {
    R.string.measurement_device_sync_heart_rate
} else {
    R.string.measurement_device_sync_body
}
