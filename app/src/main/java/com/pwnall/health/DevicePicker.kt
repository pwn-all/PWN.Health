package com.pwnall.health

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The two runtime permissions a scan and a link need.
 *
 * Location is deliberately absent. `BLUETOOTH_SCAN` is declared `neverForLocation`, and from
 * Android 12 that disavowal is what the platform gates the scan on — neither the location
 * permission nor the system Location switch is consulted. This app's minSdk is 33, so there is no
 * supported device on which the earlier rule applies, and asking for location in front of a scale would
 * be asking for a permission this feature genuinely does not use.
 */
internal val BLUETOOTH_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private const val ADAPTER_POLL_MS = 2_000L

/**
 * Choosing a device, as one modal question with one answer.
 *
 * Everything that can stand between a person and their equipment is asked here in the order the
 * system enforces it — the nearby-devices permission, then the radio, then the search — so the
 * dialog is never a list that is empty for a reason it does not say. Tapping a row is the whole
 * commitment: there is no second Connect button to find, because choosing a device from a list
 * headed "choose a device" can mean nothing else.
 */
@Composable
internal fun DevicePickerDialog(
    manager: DirectBluetoothManager,
    devices: List<DirectDevice>,
    scanning: Boolean,
    kinds: Set<DirectDeviceKind>,
    onPick: (DirectDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun permissionsGranted(): Boolean = BLUETOOTH_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(permissionsGranted()) }
    var enabled by remember { mutableStateOf(manager.bluetoothEnabled) }
    // The permission is granted in a different app and reports through no observable this screen
    // can subscribe to, so it is polled. The radio is read in the same pass, although
    // manager.bluetoothEnabled is snapshot state and could be read directly. The route-recording
    // screen watches the location switch by polling as well, and only while the app can be seen,
    // as here: a dialog left open would otherwise go on polling behind the home screen for as long
    // as the process lived.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                enabled = manager.bluetoothEnabled
                granted = permissionsGranted()
                delay(ADAPTER_POLL_MS)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = permissionsGranted()
        if (granted && manager.bluetoothEnabled) manager.startScan()
    }
    // Opening the list is the request to search; nothing here makes the person press a button
    // first. It starts only from a standing start, so reopening the dialog over a scan already
    // running does not clear what that scan has found.
    val ready = granted && enabled
    LaunchedEffect(ready) {
        if (ready && !scanning && devices.isEmpty()) manager.startScan()
    }

    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val offered = kinds.filter { kind -> devices.any { it.kind == kind } }
    // A filter for something nothing in the room is speaking hides everything and takes its own
    // chip with it, leaving an empty list and no way to clear it. A rescan is exactly that case.
    LaunchedEffect(offered) { if (offered.none { it.name == filter }) filter = null }
    val shown = devices.filter { it.kind in kinds && (filter == null || it.kind.name == filter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_picker_title)) },
        text = {
            Column(Modifier.panelVerticalScroll(rememberScrollState())) {
                when {
                    !granted -> PickerGate(
                        message = stringResource(R.string.device_picker_permission),
                        action = stringResource(R.string.direct_bluetooth_allow),
                        onAction = { permissionLauncher.launch(BLUETOOTH_PERMISSIONS) },
                    )

                    !enabled -> PickerGate(
                        message = stringResource(R.string.direct_bluetooth_disabled),
                        action = stringResource(R.string.device_picker_open_settings),
                        onAction = { context.startSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    )

                    else -> {
                        // Only protocols something in the room is actually speaking: a filter bar
                        // offering three names when one bike is present is a puzzle, not a filter.
                        if (offered.size > 1) {
                            KindFilters(offered, filter) { filter = it }
                            Spacer(Modifier.height(10.dp))
                        }
                        DeviceList(shown, scanning, onPick)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        // While a bike or a scale that only broadcasts is being watched, the search is its link and
        // runs for as long as the ride: stopping it would freeze the ride on a card that still says
        // connected, and a fresh search would end it fifteen seconds later. The list goes on filling meanwhile.
        dismissButton = if (granted && enabled && !manager.watching) {
            {
                TextButton(onClick = { if (scanning) manager.stopScan() else manager.startScan() }) {
                    Text(
                        stringResource(
                            if (scanning) R.string.direct_bluetooth_stop_scan else R.string.device_picker_rescan,
                        ),
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun PickerGate(message: String, action: String, onAction: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
}

@Composable
private fun KindFilters(kinds: List<DirectDeviceKind>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PwnFilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = stringResource(R.string.device_picker_all),
        )
        kinds.forEach { kind ->
            PwnFilterChip(
                selected = selected == kind.name,
                onClick = { onSelect(kind.name) },
                label = stringResource(kind.labelRes()),
            )
        }
    }
}

@Composable
private fun DeviceList(devices: List<DirectDevice>, scanning: Boolean, onPick: (DirectDevice) -> Unit) {
    if (devices.isEmpty()) {
        // This one line carries both answers the dialog can give while it is open, and it
        // swaps between them in place: "Rescan" clears the results, so "Nothing found" turns
        // back into "Looking for…" with no screen change and no focus move. TalkBack does not
        // speak a content change it was not told to watch, so without a live region the whole
        // search is silent. Polite is the register the pulse card's status text already uses.
        Text(
            stringResource(
                if (scanning) R.string.direct_bluetooth_searching else R.string.device_picker_empty,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    Column {
        devices.forEachIndexed { index, device ->
            if (index > 0) HorizontalDivider()
            DeviceRow(device) { onPick(device) }
        }
    }
}

@Composable
private fun DeviceRow(device: DirectDevice, onPick: () -> Unit) {
    TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Bluetooth, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(device.kind.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
