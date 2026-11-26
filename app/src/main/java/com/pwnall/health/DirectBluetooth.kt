package com.pwnall.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private val CLIENT_CONFIGURATION_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal enum class DirectDeviceKind {
    SCALE,
    FITNESS_MACHINE,
    CONCEPT2_PM5,
    KEISER_M3I,
    HEART_RATE,
    RUNNING_POD,
    CYCLING_SENSOR,
    BLOOD_PRESSURE,
    PULSE_OXIMETER,
    THERMOMETER,
    GLUCOSE_METER,
    BODY_COMPOSITION,
    COLMI_RING,
    BANGLE_JS2,
}

internal data class DirectDevice(
    val address: String,
    val name: String,
    val kind: DirectDeviceKind,
    val adapterId: String? = null,
    val advertisementOnly: Boolean = false,
)

internal enum class DirectConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

/** Optional standard GATT identity used for display and hardware/firmware acceptance records. */
internal data class DirectDeviceMetadata(
    val manufacturer: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val hardwareRevision: String? = null,
    val firmwareRevision: String? = null,
    val softwareRevision: String? = null,
    val batteryPercent: Int? = null,
    val glucoseFeatureBits: Int? = null,
)

internal sealed interface DirectProfileMeasurement {
    data class Cycling(val value: CyclingLiveMeasurement) : DirectProfileMeasurement
    data class BloodPressure(val value: BloodPressureMeasurement) : DirectProfileMeasurement
    data class PulseOximeter(val value: PulseOximeterMeasurement) : DirectProfileMeasurement
    data class Temperature(val value: TemperatureMeasurement) : DirectProfileMeasurement
    data class BodyComposition(val value: BodyCompositionMeasurement) : DirectProfileMeasurement
    data class Glucose(val value: GlucoseRecord) : DirectProfileMeasurement
    data class ColmiHistory(val value: ColmiHistorySnapshot) : DirectProfileMeasurement
}

private enum class DirectWritePurpose {
    GLUCOSE_RECORDS,
    COLMI_BATTERY,
    COLMI_HISTORY,
    BANGLE_REQUEST,
}

private class DirectPendingWrite(
    val characteristic: BluetoothGattCharacteristic,
    val value: ByteArray,
    val purpose: DirectWritePurpose,
)

/**
 * Runtime BLE boundary for local collection through adopted SIG profiles, Concept2's published PM5
 * service and explicitly allow-listed community protocols. It never needs INTERNET access.
 *
 * Lint's MissingPermission is off for the whole class because the check it looks for beside each
 * call sits in the screens, and lint cannot see it there. What stands in for it is [permitted]:
 * every way into the radio is [startScan] or [connect] — [watch] and [choose] go through them, and
 * every GATT call and scan result follows one of the two — and both ask it first. Taking a
 * permission back ends the process, so one that held at the door holds for the link it opened.
 */
@SuppressLint("MissingPermission")
@Suppress("LargeClass") // One connection-level GATT queue must serialize all enabled profiles.
internal class DirectBluetoothManager(
    context: Context,
    private val onDevicesChanged: (List<DirectDevice>) -> Unit,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onConnectionChanged: (DirectConnectionState, String?) -> Unit,
    /** The second argument is the pseudonymous identity of the device the weighing came from. */
    private val onScaleMeasurement: (StandardScaleMeasurement, String?) -> Unit,
    private val onFitnessMeasurement: (FitnessMachineMeasurement) -> Unit,
    private val onHeartRate: (HeartRateMeasurement) -> Unit,
    private val onRunning: (RunningMeasurement) -> Unit,
    private val onMetadataChanged: (DirectDeviceMetadata) -> Unit = {},
    private val onProfileMeasurement: (DirectProfileMeasurement) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val results = linkedMapOf<String, ScanResult>()
    private val devices = linkedMapOf<String, DirectDevice>()
    private var gatt: BluetoothGatt? = null
    private var connected: DirectDevice? = null
    private var scaleAdapter: BluetoothMeasurementAdapter? = null

    /** Android permits one outstanding GATT operation, so CCCD writes are serialized. */
    private val subscriptionQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var subscribing: BluetoothGattCharacteristic? = null
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var reading: BluetoothGattCharacteristic? = null
    private var metadata = DirectDeviceMetadata()
    private var concept2Accumulator = Concept2Pm5Accumulator()
    private var cyclingAccumulator = CyclingMeasurementAccumulator()
    private var bodyCompositionAccumulator = BodyCompositionAccumulator()
    private var glucoseAccumulator = GlucoseRecordAccumulator()
    private var fitnessAccumulator = FitnessMachineAccumulator()

    /**
     * The site a thermometer published once for every measurement, and the readings it sent before
     * that was known — every one of them, because a thermometer with a memory sends them all at
     * once, and each is saved.
     */
    private var thermometerSite: TemperatureType? = null
    private val sitelessTemperatures = ArrayDeque<TemperatureMeasurement>()
    private var pendingWrite: DirectPendingWrite? = null
    private var writeAwaitingBond = false
    private var servicesAwaitingBond = false
    private var bondRequested = false
    private var receiverRegistered = true
    private var connectionReady = false

    /**
     * Set by [disconnect] and cleared when the link is released. Asking for the link to close does
     * not close it: the peripheral goes on notifying until it has heard, and a machine sending four
     * packets a second would put the card a screen had just emptied back on it, to be taken a second
     * time. What a link still says after the screen let go of it is not delivered.
     */
    private var hangingUp = false

    /**
     * True except while this app's own MTU exchange is outstanding. Android also reports MTU
     * changes nobody here asked for, and one arriving before [configureDataSubscriptions] must not
     * run the whole profile handshake on an empty queue.
     */
    private var mtuHandled = true
    private var colmiSession: ColmiSyncSession? = null
    private val deferredColmiNotifications = ArrayDeque<ByteArray>()
    private var bangleSession: BangleJsSyncSession? = null
    private var scanTimeout: Runnable? = null
    private var scanning = false
    private var colmiTimeout: Runnable? = null
    private var glucoseRacpTimeout: Runnable? = null
    private var readyTimeout: Runnable? = null
    private val glucoseProcedureRecords = linkedMapOf<Int, GlucoseRecord>()

    /** Measurement notifications of the running transfer that the strict parser turned away. */
    private var glucoseUnreadable = 0

    /**
     * The end of the RACP procedure: how many records it delivered, and how many notifications
     * could not be read as one. An empty meter completes without a single record, so no
     * measurement callback ever fires; and a transfer whose records were all refused would end
     * the same way, with nothing to say that anything had been sent. A property because the
     * constructor sits at detekt's parameter cap.
     */
    var onGlucoseSyncComplete: (records: Int, unreadable: Int) -> Unit = { _, _ -> }

    /**
     * A search that could not start beside an open link, which is not the connection's failure
     * ([searchFailureIsTheConnections]): the page says so under its search button and leaves the
     * connection as it stands. A property for the reason [onGlucoseSyncComplete] is one.
     */
    var onSearchFailed: () -> Unit = {}

    /**
     * A Bangle.js 2 snapshot that arrived whole; the link is let go of right after it. A property for
     * the reason [onGlucoseSyncComplete] is one.
     */
    var onBangleSync: (BangleJsSync) -> Unit = {}

    /**
     * A failed encrypted CCCD write starts Android pairing asynchronously. Retrying before the
     * BOND_BONDED broadcast just fails again, while never retrying leaves a standards-compliant
     * bonded sensor connected but silent.
     */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.bluetoothDeviceExtra() ?: return
            if (device.address != connected?.address) return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            when {
                // Nothing told this manager that pairing began, so the thirty seconds a connection
                // is given are still running under the system's pairing dialog.
                pairingStartedByPlatform(bondState, bondRequested, connectionReady) ->
                    gatt?.let { scheduleReadyTimeout(it, "bond_timeout", BOND_TIMEOUT_MS) }

                // Every other state matters only to the pairing this manager asked for.
                !bondRequested -> Unit

                bondState == BluetoothDevice.BOND_BONDED -> {
                    val activeGatt = gatt ?: return
                    main.post {
                        if (gatt !== activeGatt) return@post
                        if (servicesAwaitingBond) {
                            servicesAwaitingBond = false
                            configureDataSubscriptions(activeGatt)
                        } else if (writeAwaitingBond) {
                            writeAwaitingBond = false
                            retryCharacteristicWrite(activeGatt)
                        } else {
                            retrySubscription(activeGatt)
                        }
                    }
                }

                bondState == BluetoothDevice.BOND_NONE -> {
                    val previous = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR,
                    )
                    if (previous == BluetoothDevice.BOND_BONDING) {
                        val activeGatt = gatt ?: return
                        main.post {
                            if (gatt === activeGatt) fail(activeGatt, "bond")
                        }
                    }
                }
            }
        }
    }

    /**
     * Snapshot state rather than a plain read of the adapter, so a card saying Bluetooth is off
     * follows the switch flipped in the quick settings shade instead of keeping the old answer
     * until the page is reopened. The adapter is asked again at every broadcast, and once more
     * after the receiver is in place, so no change falls between the first read and the first
     * broadcast.
     */
    private val radioOn = mutableStateOf(false)

    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            radioOn.value = adapter?.isEnabled == true
            if (!radioOn.value) letGoOfTheRadio()
        }
    }

    /**
     * Switching the radio off ends every link and every search, and the stack does not always tell a
     * GATT client so: the card would go on saying "connected" to a device nobody can hear any more,
     * and the dead handle would stay. A watched advertiser's search goes with the radio as well. Whatever was
     * held is let go of here and said to have ended, once: the broadcast comes again as the radio
     * finishes turning off, and on the way back on.
     */
    private fun letGoOfTheRadio() {
        val held = gatt != null || watchedAdvertiser != null
        gatt?.let(::release)
        watchedAdvertiser = null
        connected = null
        scaleAdapter = null
        if (scanning) stopScan()
        if (held) onConnectionChanged(DirectConnectionState.ERROR, "bluetooth_off")
    }

    init {
        // Bluetooth broadcasts originate from a privileged system component rather than this app.
        ContextCompat.registerReceiver(
            appContext,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            radioReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        radioOn.value = adapter?.isEnabled == true
    }

    val bluetoothEnabled: Boolean
        get() = radioOn.value

    /**
     * Both nearby-devices permissions, asked again at the doors (see the class): a caller that
     * skipped its own check gets the permission message instead of a SecurityException.
     */
    private fun permitted(): Boolean = BLUETOOTH_PERMISSIONS.all {
        appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
            batchResults.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post {
                onScanningChanged(false)
                // Beside an open link the search answers for itself, and the link is left alone.
                if (searchFailureIsTheConnections(linkOpen = gatt != null)) {
                    onConnectionChanged(DirectConnectionState.ERROR, "scan:$errorCode")
                } else {
                    Log.w(DIRECT_BLUETOOTH_LOG_TAG, "Search failed beside an open link: $errorCode")
                    onSearchFailed()
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@DirectBluetoothManager.gatt !== gatt) return
            // A failed connect arrives as STATE_CONNECTED with a non-zero status. Ignoring the
            // status would make GATT_ERROR 133 — the single most common BLE failure on Android — look
            // like a clean connection, and service discovery would then run on a dead link.
            if (newState == BluetoothProfile.STATE_CONNECTED && status != BluetoothGatt.GATT_SUCCESS) {
                release(gatt)
                main.post { onConnectionChanged(DirectConnectionState.ERROR, "connect:$status") }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // CONNECTED in the UI means ready to receive data, not merely a live radio
                    // link. Service discovery and the CCCD write still have to succeed first.
                    if (!gatt.discoverServices()) fail(gatt, "services_start")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val name = connected?.name
                    release(gatt)
                    main.post {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            onConnectionChanged(DirectConnectionState.DISCONNECTED, name)
                        } else {
                            onConnectionChanged(DirectConnectionState.ERROR, "link:$status")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@DirectBluetoothManager.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "services:$status")
                return
            }
            if (connected?.kind == DirectDeviceKind.COLMI_RING) {
                val model = BluetoothColmiRing.identifyModel(
                    connected?.name,
                    gatt.services.map { it.uuid },
                )
                if (model == null) {
                    fail(gatt, "colmi_identity")
                    return
                }
                colmiSession = ColmiSyncSession(
                    model = model,
                    // Deliberately the unsalted key: it is the primary key of direct_ble_step_days
                    // and has to come out the same on every install (see colmiSourceId). That
                    // leaves it pseudonymous only: the address can be enumerated back from it.
                    sourceId = colmiSourceId(gatt.device.address, model),
                )
            }
            if (connected?.kind == DirectDeviceKind.BANGLE_JS2) {
                // Made before the subscription, so the decoder hears every byte the watch sends.
                bangleSession = BangleJsSyncSession(sourceId = directSourceId(appContext, gatt.device.address))
            }
            if (connected?.kind == DirectDeviceKind.GLUCOSE_METER &&
                gatt.device.bondState != BluetoothDevice.BOND_BONDED
            ) {
                bondRequested = true
                servicesAwaitingBond = true
                scheduleReadyTimeout(gatt, "glucose_bond_timeout", BOND_TIMEOUT_MS)
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDING &&
                    !gatt.device.createBond()
                ) {
                    fail(gatt, "glucose_bond")
                }
                return
            }
            configureDataSubscriptions(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (this@DirectBluetoothManager.gatt !== gatt) return
            // Android also reports MTU exchanges this app did not request — peer-initiated ones and
            // the automatic negotiation. Those arrive with the queue already empty, and a second
            // pass through subscribeNext repeats the whole profile handshake.
            if (mtuHandled) return
            mtuHandled = true
            // Whether the larger MTU was granted or not, the subscription is what the exchange was for.
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@DirectBluetoothManager.gatt !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribing = null
                subscribeNext(gatt)
                return
            }
            // The write's own return value only reports that the operation started. A scale that
            // wants a bond fails here, asynchronously, and without this the screen would sit on "waiting for
            // data" for as long as the person was willing to look at it.
            if (status == NEEDS_AUTHENTICATION || status == NEEDS_ENCRYPTION) {
                if (bondRequested) {
                    fail(gatt, "bond")
                } else {
                    bondRequested = true
                    scheduleReadyTimeout(gatt, "bond_timeout", BOND_TIMEOUT_MS)
                    if (!gatt.device.createBond()) fail(gatt, bondRefusal(gatt.device.bondState))
                }
                return
            }
            fail(gatt, "subscribe:$status")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@DirectBluetoothManager.gatt !== gatt || hangingUp) return
            handleCharacteristic(characteristic.uuid, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(gatt, characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicWrite(gatt, characteristic, status)
        }
    }

    private fun configureDataSubscriptions(gatt: BluetoothGatt) {
        if (this.gatt !== gatt) return
        val characteristics = dataCharacteristics(gatt)
        if (characteristics.isEmpty()) {
            fail(gatt, "characteristic")
            return
        }
        subscriptionQueue.clear()
        subscriptionQueue.addAll(
            characteristics.distinctBy(BluetoothGattCharacteristic::getUuid),
        )
        subscribing = null
        mtuHandled = false
        // Android keeps the link at the default 23-octet MTU unless the client asks for more,
        // and a body-composition measurement does not fit in what that leaves for a payload.
        // The request is best-effort: a refusal still leaves a usable link for short records.
        if (!gatt.requestMtu(PREFERRED_MTU)) {
            mtuHandled = true
            subscribeNext(gatt)
        }
    }

    fun startScan() {
        if (!permitted()) {
            onConnectionChanged(DirectConnectionState.ERROR, "permission")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onConnectionChanged(DirectConnectionState.ERROR, "bluetooth_off")
            return
        }
        results.clear()
        devices.clear()
        onDevicesChanged(emptyList())
        val serviceUuids = listOf(
            BluetoothWeightScale.serviceUuid,
            BluetoothWeightScale.bodyCompositionServiceUuid,
            BluetoothFitnessMachine.serviceUuid,
            BluetoothConcept2Pm5.serviceUuid,
            BluetoothConcept2Pm5.advertisedServiceUuid,
            BluetoothHeartRate.serviceUuid,
            BluetoothRunningSpeedCadence.serviceUuid,
            BluetoothCyclingSpeedCadence.serviceUuid,
            BluetoothCyclingPower.serviceUuid,
            BluetoothBloodPressure.serviceUuid,
            BluetoothPulseOximeter.serviceUuid,
            BluetoothHealthThermometer.serviceUuid,
            BluetoothGlucose.serviceUuid,
            BluetoothColmiRing.serviceUuid,
        )
        val filters = serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        } + BLUETOOTH_MEASUREMENT_ADAPTERS
            .filter(BluetoothMeasurementAdapter::parsesAdvertisementData)
            .flatMap(BluetoothMeasurementAdapter::advertisedServiceUuids)
            .distinct()
            .map { uuid ->
                // Xiaomi scales commonly put 0x181D/0x181B only in the Service Data AD structure,
                // not in the separate Service UUID list. An empty prefix matches any payload for
                // that service while still keeping the scan filtered in Android.
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(uuid), byteArrayOf())
                    .build()
            }
        // Some R02-family firmware advertises only its exact Rxx_XXXX local name and reveals the
        // proprietary service after connection. The scan is therefore broad, but handleResult()
        // exposes only the three allow-listed names; GATT discovery and a valid battery response
        // are still mandatory before the connection becomes ready.
        val candidateFilters = filters + ScanFilter.Builder().build()
        scanner.startScan(
            candidateFilters,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCallback,
        )
        scanning = true
        onScanningChanged(true)
        cancelScanTimeout()
        val timeout = Runnable { stopScan() }
        scanTimeout = timeout
        main.postDelayed(timeout, 15_000)
    }

    fun stopScan() {
        // An earlier scan's auto-stop would otherwise still be pending and truncate this one.
        cancelScanTimeout()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        onScanningChanged(false)
    }

    private fun cancelScanTimeout() {
        scanTimeout?.let(main::removeCallbacks)
        scanTimeout = null
    }

    // Deprecated on API 37 in favour of connectGatt(BluetoothGattConnectionSettings, …), which
    // minSdk 33 cannot call.
    @Suppress("DEPRECATION")
    fun connect(device: DirectDevice) {
        if (gatt != null) {
            onConnectionChanged(DirectConnectionState.ERROR, "disconnect_first")
            return
        }
        if (!permitted()) {
            onConnectionChanged(DirectConnectionState.ERROR, "permission")
            return
        }
        stopScan()
        val result = results[device.address]
        if (result == null) {
            onConnectionChanged(DirectConnectionState.ERROR, "device_missing")
            return
        }
        connected = device
        watchedAdvertiser = null
        metadata = DirectDeviceMetadata()
        concept2Accumulator = Concept2Pm5Accumulator()
        cyclingAccumulator = CyclingMeasurementAccumulator()
        bodyCompositionAccumulator = BodyCompositionAccumulator()
        glucoseAccumulator = GlucoseRecordAccumulator()
        fitnessAccumulator = FitnessMachineAccumulator()
        thermometerSite = null
        sitelessTemperatures.clear()
        pendingWrite = null
        writeAwaitingBond = false
        servicesAwaitingBond = false
        connectionReady = false
        colmiSession = null
        bangleSession = null
        deferredColmiNotifications.clear()
        cancelColmiTimeout()
        cancelGlucoseRacpTimeout()
        glucoseProcedureRecords.clear()
        glucoseUnreadable = 0
        glucoseAccumulator.reset()
        onMetadataChanged(metadata)
        scaleAdapter = device.adapterId?.let { id ->
            BLUETOOTH_MEASUREMENT_ADAPTERS.firstOrNull { it.id == id }
        }
        onConnectionChanged(DirectConnectionState.CONNECTING, device.name)
        val client = result.device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            // The Handler is what confines this manager to one thread: without it Android runs
            // every callback inline on a binder thread, racing the timeouts, the bond receiver and
            // close() — which clear the very queues and maps the callbacks are appending to.
            main,
        )
        gatt = client
        if (client != null) {
            scheduleReadyTimeout(client, "connection_timeout", READY_TIMEOUT_MS)
        } else {
            // Null means the Bluetooth stack is unavailable, so no callback and no watchdog will
            // ever arrive to take the screen out of CONNECTING.
            connected = null
            scaleAdapter = null
            onConnectionChanged(DirectConnectionState.ERROR, "connect_failed")
        }
    }

    /**
     * Asks the peripheral to drop the link and lets [onConnectionStateChange] do the closing.
     * Calling close() here as well would discard the pending disconnect callback, so the peripheral
     * would only notice at supervision timeout — several seconds during which it refuses a reconnect.
     */
    fun disconnect() {
        // A watched advertiser was never linked, so no disconnect callback is coming and the state
        // has to be put back by hand — including the scan, which watching leaves running with its
        // fifteen-second stop cancelled.
        if (watchedAdvertiser != null) {
            val name = connected?.name
            watchedAdvertiser = null
            connected = null
            scaleAdapter = null
            stopScan()
            onConnectionChanged(DirectConnectionState.DISCONNECTED, name)
            return
        }
        hangingUp = gatt != null
        gatt?.disconnect() ?: run {
            connected = null
            scaleAdapter = null
        }
    }

    fun close() {
        stopScan()
        gatt?.let(::release)
        watchedAdvertiser = null
        connected = null
        scaleAdapter = null
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondReceiver) }
            runCatching { appContext.unregisterReceiver(radioReceiver) }
            receiverRegistered = false
        }
    }

    /** Closes the client interface and forgets the connection, whatever state it was in. */
    private fun release(gatt: BluetoothGatt) {
        gatt.close()
        // A late callback from an old link must never erase a newer connection's state.
        if (this.gatt !== gatt) return
        this.gatt = null
        subscriptionQueue.clear()
        subscribing = null
        readQueue.clear()
        reading = null
        pendingWrite = null
        writeAwaitingBond = false
        servicesAwaitingBond = false
        bondRequested = false
        connectionReady = false
        hangingUp = false
        mtuHandled = true
        colmiSession = null
        bangleSession = null
        thermometerSite = null
        sitelessTemperatures.clear()
        deferredColmiNotifications.clear()
        cancelColmiTimeout()
        cancelGlucoseRacpTimeout()
        cancelReadyTimeout()
        glucoseProcedureRecords.clear()
        glucoseAccumulator.reset()
        connected = null
        scaleAdapter = null
    }

    /** Reports a failure and hangs up. Leaving the link open would leak a GATT client interface. */
    private fun fail(gatt: BluetoothGatt, detail: String) {
        release(gatt)
        main.post { onConnectionChanged(DirectConnectionState.ERROR, detail) }
    }

    private fun dataCharacteristics(gatt: BluetoothGatt): List<BluetoothGattCharacteristic> =
        when (connected?.kind) {
            DirectDeviceKind.SCALE -> gatt.singleCharacteristic(
                BluetoothWeightScale.serviceUuid,
                BluetoothWeightScale.measurementUuid,
            )

            DirectDeviceKind.HEART_RATE -> gatt.heartRateCharacteristic()

            // A strap that also speaks RSCS (Wahoo TICKR X) or a pod that also speaks CPS (Stryd)
            // is one device with two profiles. The kind decides which workout gets saved; the
            // heart rate rides alongside whichever it is.
            DirectDeviceKind.RUNNING_POD -> gatt.singleCharacteristic(
                BluetoothRunningSpeedCadence.serviceUuid,
                BluetoothRunningSpeedCadence.measurementUuid,
            ) + gatt.heartRateCharacteristic()

            DirectDeviceKind.FITNESS_MACHINE -> {
                val service = gatt.getService(BluetoothFitnessMachine.serviceUuid)
                listOf(
                    BluetoothFitnessMachine.treadmillDataUuid,
                    BluetoothFitnessMachine.rowerDataUuid,
                    BluetoothFitnessMachine.indoorBikeDataUuid,
                ).mapNotNull { service?.getCharacteristic(it) } + gatt.heartRateCharacteristic()
            }

            DirectDeviceKind.CONCEPT2_PM5 -> {
                val service = gatt.getService(BluetoothConcept2Pm5.serviceUuid)
                listOf(
                    BluetoothConcept2Pm5.generalStatusUuid,
                    BluetoothConcept2Pm5.additionalStatusOneUuid,
                    BluetoothConcept2Pm5.additionalStatusTwoUuid,
                ).mapNotNull { service?.getCharacteristic(it) } + gatt.heartRateCharacteristic()
            }

            DirectDeviceKind.CYCLING_SENSOR -> listOf(
                gatt.getService(BluetoothCyclingPower.serviceUuid)
                    ?.getCharacteristic(BluetoothCyclingPower.measurementUuid),
                gatt.getService(BluetoothCyclingSpeedCadence.serviceUuid)
                    ?.getCharacteristic(BluetoothCyclingSpeedCadence.measurementUuid),
            ).filterNotNull() + gatt.heartRateCharacteristic()

            DirectDeviceKind.COLMI_RING -> gatt.singleCharacteristic(
                BluetoothColmiRing.serviceUuid,
                BluetoothColmiRing.notifyUuid,
            )

            DirectDeviceKind.BANGLE_JS2 -> gatt.singleCharacteristic(
                BangleJsOfflineProtocol.serviceUuid,
                BangleJsOfflineProtocol.transmitUuid,
            )

            else -> medicalDataCharacteristics(gatt, connected?.kind)
        }

    private fun medicalDataCharacteristics(
        gatt: BluetoothGatt,
        kind: DirectDeviceKind?,
    ): List<BluetoothGattCharacteristic> = when (kind) {
        DirectDeviceKind.BLOOD_PRESSURE -> gatt.singleCharacteristic(
            BluetoothBloodPressure.serviceUuid,
            BluetoothBloodPressure.measurementUuid,
        )

        DirectDeviceKind.PULSE_OXIMETER -> {
            val service = gatt.getService(BluetoothPulseOximeter.serviceUuid)
            listOf(
                BluetoothPulseOximeter.spotCheckMeasurementUuid,
                BluetoothPulseOximeter.continuousMeasurementUuid,
            ).mapNotNull { service?.getCharacteristic(it) }
        }

        DirectDeviceKind.THERMOMETER -> gatt.singleCharacteristic(
            BluetoothHealthThermometer.serviceUuid,
            BluetoothHealthThermometer.measurementUuid,
        )

        DirectDeviceKind.GLUCOSE_METER -> {
            val service = gatt.getService(BluetoothGlucose.serviceUuid)
            listOf(
                BluetoothGlucose.measurementUuid,
                BluetoothGlucose.contextUuid,
                BluetoothGlucose.racpUuid,
            ).mapNotNull { service?.getCharacteristic(it) }
        }

        DirectDeviceKind.BODY_COMPOSITION -> listOf(
            gatt.getService(BluetoothBodyComposition.serviceUuid)
                ?.getCharacteristic(BluetoothBodyComposition.measurementUuid),
            gatt.getService(BluetoothWeightScale.serviceUuid)
                ?.getCharacteristic(BluetoothWeightScale.measurementUuid),
        ).filterNotNull()

        else -> emptyList()
    }

    private fun BluetoothGatt.singleCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): List<BluetoothGattCharacteristic> = getService(serviceUuid)
        ?.getCharacteristic(characteristicUuid)
        ?.let(::listOf)
        .orEmpty()

    private fun BluetoothGatt.heartRateCharacteristic(): List<BluetoothGattCharacteristic> =
        singleCharacteristic(BluetoothHeartRate.serviceUuid, BluetoothHeartRate.measurementUuid)

    private fun subscribeNext(gatt: BluetoothGatt) {
        if (subscribing != null) return
        val characteristic = subscriptionQueue.removeFirstOrNull()
        if (characteristic == null) {
            onSubscriptionsReady(gatt)
            return
        }
        subscribing = characteristic
        if (!subscribe(gatt, characteristic)) fail(gatt, "characteristic")
    }

    /** Retries the exact CCCD that requested encryption after Android reports a completed bond. */
    private fun retrySubscription(gatt: BluetoothGatt) {
        val characteristic = subscribing ?: return
        if (!subscribe(gatt, characteristic)) fail(gatt, "characteristic")
    }

    /** Profile-specific reads or writes start only after every CCCD has completed. */
    private fun onSubscriptionsReady(gatt: BluetoothGatt) {
        when (connected?.kind) {
            DirectDeviceKind.GLUCOSE_METER -> {
                val racp = gatt.getService(BluetoothGlucose.serviceUuid)
                    ?.getCharacteristic(BluetoothGlucose.racpUuid)
                if (racp == null || !beginWrite(
                        gatt,
                        racp,
                        BluetoothGlucoseRacp.reportAllRecords,
                        DirectWritePurpose.GLUCOSE_RECORDS,
                    )
                ) {
                    fail(gatt, "glucose_request")
                }
            }

            DirectDeviceKind.COLMI_RING -> {
                val write = gatt.getService(BluetoothColmiRing.serviceUuid)
                    ?.getCharacteristic(BluetoothColmiRing.writeUuid)
                val session = colmiSession
                if (write == null || session == null || !beginWrite(
                        gatt,
                        write,
                        session.firstRequest(),
                        DirectWritePurpose.COLMI_BATTERY,
                    )
                ) {
                    fail(gatt, "colmi_handshake")
                }
            }

            DirectDeviceKind.BANGLE_JS2 -> {
                val receive = gatt.getService(BangleJsOfflineProtocol.serviceUuid)
                    ?.getCharacteristic(BangleJsOfflineProtocol.receiveUuid)
                val session = bangleSession
                if (receive == null || session == null || !beginWrite(
                        gatt,
                        receive,
                        session.request(),
                        DirectWritePurpose.BANGLE_REQUEST,
                    )
                ) {
                    fail(gatt, "bangle_request")
                }
            }

            else -> {
                markConnected()
                enqueueStandardMetadataReads(gatt)
            }
        }
    }

    private fun handleCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        val pending = pendingWrite
        if (this.gatt !== gatt || pending?.characteristic !== characteristic) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pendingWrite = null
            writeAwaitingBond = false
            when (pending.purpose) {
                DirectWritePurpose.GLUCOSE_RECORDS -> {
                    markConnected()
                    scheduleGlucoseRacpTimeout(gatt)
                    enqueueStandardMetadataReads(gatt)
                }

                DirectWritePurpose.COLMI_BATTERY,
                DirectWritePurpose.COLMI_HISTORY,
                -> {
                    scheduleColmiTimeout(gatt, "colmi_response")
                    drainDeferredColmiNotifications()
                }

                // The watch answers at once, so the answer is not given the thirty seconds a
                // connection gets. One that came before this callback has already marked it ready.
                DirectWritePurpose.BANGLE_REQUEST ->
                    scheduleReadyTimeout(gatt, "bangle_response_timeout", BANGLE_RESPONSE_TIMEOUT_MS)
            }
            return
        }
        if (status == NEEDS_AUTHENTICATION || status == NEEDS_ENCRYPTION) {
            if (pending.purpose != DirectWritePurpose.GLUCOSE_RECORDS) cancelColmiTimeout()
            if (bondRequested) {
                fail(gatt, "bond")
            } else {
                bondRequested = true
                writeAwaitingBond = true
                scheduleReadyTimeout(gatt, "bond_timeout", BOND_TIMEOUT_MS)
                if (!gatt.device.createBond()) fail(gatt, bondRefusal(gatt.device.bondState))
            }
            return
        }
        fail(gatt, "write:$status")
    }

    private fun retryCharacteristicWrite(gatt: BluetoothGatt) {
        val pending = pendingWrite ?: return
        if (pending.purpose != DirectWritePurpose.GLUCOSE_RECORDS) {
            scheduleColmiTimeout(gatt, "colmi_write")
        }
        if (!writeCharacteristic(gatt, pending.characteristic, pending.value, pending.purpose)) {
            fail(gatt, "profile_request")
        }
    }

    private fun beginWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        purpose: DirectWritePurpose,
    ): Boolean {
        if (pendingWrite != null) return false
        val supportsWrite = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsWriteWithoutResponse =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!supportsWrite && !supportsWriteWithoutResponse) return false
        if (purpose == DirectWritePurpose.GLUCOSE_RECORDS && !supportsWrite) return false
        pendingWrite = DirectPendingWrite(characteristic, value.copyOf(), purpose)
        if (purpose == DirectWritePurpose.COLMI_BATTERY ||
            purpose == DirectWritePurpose.COLMI_HISTORY
        ) {
            scheduleColmiTimeout(gatt, "colmi_write")
        }
        if (writeCharacteristic(gatt, characteristic, value, purpose)) return true
        pendingWrite = null
        cancelColmiTimeout()
        return false
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        purpose: DirectWritePurpose,
    ): Boolean =
        gatt.writeCharacteristic(
            characteristic,
            value,
            characteristic.writeTypeFor(purpose),
        ) == android.bluetooth.BluetoothStatusCodes.SUCCESS

    private fun BluetoothGattCharacteristic.writeTypeFor(
        purpose: DirectWritePurpose,
    ): Int = if (
        purpose != DirectWritePurpose.GLUCOSE_RECORDS &&
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    ) {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    } else {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }

    private fun markConnected() {
        if (connectionReady) return
        connectionReady = true
        cancelReadyTimeout()
        main.post { onConnectionChanged(DirectConnectionState.CONNECTED, connected?.name) }
    }

    /** Bounds Android callbacks that can otherwise leave the screen in CONNECTING forever. */
    private fun scheduleReadyTimeout(
        gatt: BluetoothGatt,
        detail: String,
        delayMs: Long,
    ) {
        cancelReadyTimeout()
        val timeout = Runnable {
            if (this.gatt === gatt && !connectionReady) fail(gatt, detail)
        }
        readyTimeout = timeout
        main.postDelayed(timeout, delayMs)
    }

    private fun cancelReadyTimeout() {
        readyTimeout?.let(main::removeCallbacks)
        readyTimeout = null
    }

    private fun scheduleColmiTimeout(gatt: BluetoothGatt, detail: String) {
        cancelColmiTimeout()
        val timeout = Runnable {
            if (this.gatt === gatt && connected?.kind == DirectDeviceKind.COLMI_RING) {
                fail(gatt, detail)
            }
        }
        colmiTimeout = timeout
        main.postDelayed(timeout, COLMI_COMMAND_TIMEOUT_MS)
    }

    private fun cancelColmiTimeout() {
        colmiTimeout?.let(main::removeCallbacks)
        colmiTimeout = null
    }

    private fun scheduleGlucoseRacpTimeout(gatt: BluetoothGatt) {
        cancelGlucoseRacpTimeout()
        val timeout = Runnable {
            if (this.gatt === gatt && connected?.kind == DirectDeviceKind.GLUCOSE_METER) {
                fail(gatt, "glucose_racp_timeout")
            }
        }
        glucoseRacpTimeout = timeout
        main.postDelayed(timeout, GLUCOSE_RACP_TIMEOUT_MS)
    }

    private fun restartGlucoseRacpTimeout() {
        val activeGatt = gatt ?: return
        if (glucoseRacpTimeout != null) scheduleGlucoseRacpTimeout(activeGatt)
    }

    private fun cancelGlucoseRacpTimeout() {
        glucoseRacpTimeout?.let(main::removeCallbacks)
        glucoseRacpTimeout = null
    }

    /**
     * A ring answers one history request with a header frame plus a frame per record, and BLE packs
     * several notifications into a single connection event — so more than one can arrive while a
     * write is outstanding. Popping one per completed write would strand the rest in the deque, and
     * the next live frame would then fail the assembler's sequence check and tear the connection down.
     *
     * The `pendingWrite == null` guard is what makes the loop correct rather than merely longer: a
     * drained frame can itself start the next write, and everything after it must stay buffered
     * until that one completes.
     */
    private fun drainDeferredColmiNotifications() {
        while (pendingWrite == null && deferredColmiNotifications.isNotEmpty()) {
            handleColmiCharacteristic(
                BluetoothColmiRing.notifyUuid,
                deferredColmiNotifications.removeFirst(),
            )
        }
    }

    private fun enqueueStandardMetadataReads(gatt: BluetoothGatt) {
        val information = gatt.getService(DEVICE_INFORMATION_SERVICE_UUID)
        val concept2Information = gatt.getService(BluetoothConcept2Pm5.deviceInformationServiceUuid)
        val battery = gatt.getService(BATTERY_SERVICE_UUID)
        readQueue.clear()
        readQueue.addAll(
            listOf(
                information?.getCharacteristic(MANUFACTURER_NAME_UUID),
                information?.getCharacteristic(MODEL_NUMBER_UUID),
                information?.getCharacteristic(SERIAL_NUMBER_UUID),
                information?.getCharacteristic(HARDWARE_REVISION_UUID),
                information?.getCharacteristic(FIRMWARE_REVISION_UUID),
                information?.getCharacteristic(SOFTWARE_REVISION_UUID),
                battery?.getCharacteristic(BATTERY_LEVEL_UUID),
                gatt.getService(BluetoothGlucose.serviceUuid)
                    ?.getCharacteristic(BluetoothGlucose.featureUuid),
                gatt.getService(BluetoothHealthThermometer.serviceUuid)
                    ?.getCharacteristic(BluetoothHealthThermometer.temperatureTypeUuid),
                concept2Information?.getCharacteristic(BluetoothConcept2Pm5.manufacturerNameUuid),
                concept2Information?.getCharacteristic(BluetoothConcept2Pm5.modelNumberUuid),
                concept2Information?.getCharacteristic(BluetoothConcept2Pm5.serialNumberUuid),
                concept2Information?.getCharacteristic(BluetoothConcept2Pm5.hardwareRevisionUuid),
                concept2Information?.getCharacteristic(BluetoothConcept2Pm5.firmwareRevisionUuid),
            ).filterNotNull(),
        )
        reading = null
        readNext(gatt)
    }

    private fun readNext(gatt: BluetoothGatt) {
        if (reading != null) return
        while (readQueue.isNotEmpty()) {
            val characteristic = readQueue.removeFirst()
            reading = characteristic
            if (gatt.readCharacteristic(characteristic)) return
            reading = null
        }
    }

    private fun handleRead(
        gatt: BluetoothGatt,
        uuid: UUID,
        value: ByteArray,
        status: Int,
    ) {
        if (this.gatt !== gatt || reading?.uuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (uuid == BluetoothHealthThermometer.temperatureTypeUuid) {
                rememberThermometerSite(value)
            } else {
                metadata = metadata.withGattValue(uuid, value)
                main.post { onMetadataChanged(metadata) }
            }
        }
        reading = null
        readNext(gatt)
    }

    /**
     * HTS 3.2: a thermometer whose site never changes publishes it here once and leaves the type
     * flag clear in every measurement. A stored reading is often indicated the moment its CCCD is
     * written, before this read has come back, so the measurements already posted without a site
     * are posted again with one rather than left for the vitals card to grade as core temperatures.
     * In the order they came, so the last of them is the one left on the card.
     */
    private fun rememberThermometerSite(value: ByteArray) {
        val site = BluetoothHealthThermometer.parseTemperatureType(value) ?: return
        thermometerSite = site
        sitelessTemperatures.forEach {
            postProfile(DirectProfileMeasurement.Temperature(it.withDefaultType(site)))
        }
        sitelessTemperatures.clear()
    }

    /** Holds a reading that named no site until [rememberThermometerSite] can give it one. */
    private fun awaitThermometerSite(reading: TemperatureMeasurement) {
        if (sitelessTemperatures.size == MAX_SITELESS_TEMPERATURES) sitelessTemperatures.removeFirst()
        sitelessTemperatures.addLast(reading)
    }

    private fun DirectDeviceMetadata.withGattValue(uuid: UUID, value: ByteArray): DirectDeviceMetadata {
        if (uuid == BATTERY_LEVEL_UUID) {
            val percent = value.singleOrNull()?.toInt()?.and(0xFF)?.takeIf { it <= 100 }
            return copy(batteryPercent = percent ?: batteryPercent)
        }
        if (uuid == BluetoothGlucose.featureUuid) {
            val features = value.takeIf { it.size == 2 }?.let {
                (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
            }
            return copy(glucoseFeatureBits = features ?: glucoseFeatureBits)
        }
        val text = value.decodeGattText() ?: return this
        return withStandardText(uuid, text) ?: withConcept2Text(uuid, text) ?: this
    }

    private fun DirectDeviceMetadata.withStandardText(
        uuid: UUID,
        text: String,
    ): DirectDeviceMetadata? = when (uuid) {
        MANUFACTURER_NAME_UUID -> copy(manufacturer = text)
        MODEL_NUMBER_UUID -> copy(model = text)
        SERIAL_NUMBER_UUID -> copy(serial = text)
        HARDWARE_REVISION_UUID -> copy(hardwareRevision = text)
        FIRMWARE_REVISION_UUID -> copy(firmwareRevision = text)
        SOFTWARE_REVISION_UUID -> copy(softwareRevision = text)
        else -> null
    }

    private fun DirectDeviceMetadata.withConcept2Text(
        uuid: UUID,
        text: String,
    ): DirectDeviceMetadata? = when (uuid) {
        BluetoothConcept2Pm5.manufacturerNameUuid -> copy(manufacturer = text)
        BluetoothConcept2Pm5.modelNumberUuid -> copy(model = text)
        BluetoothConcept2Pm5.serialNumberUuid -> copy(serial = text)
        BluetoothConcept2Pm5.hardwareRevisionUuid -> copy(hardwareRevision = text)
        BluetoothConcept2Pm5.firmwareRevisionUuid -> copy(firmwareRevision = text)
        else -> null
    }

    private fun ByteArray.decodeGattText(): String? =
        toString(Charsets.UTF_8)
            .trim('\u0000', ' ', '\t', '\r', '\n')
            .filterNot(Char::isISOControl)
            .take(MAX_METADATA_TEXT_LENGTH)
            .ifBlank { null }

    /**
     * Who a reading came from, in the only form this app is willing to keep: two scales of the same
     * make are told apart in the journal, while the address they were derived from is never stored.
     */
    private fun sourceIdOf(device: DirectDevice?): String? =
        device?.let { directSourceId(appContext, it.address) }

    // A vendor packet inside a service UUID inside a scan result is three levels deep.
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        // Neither getter is annotated, and both answer null for a packet Android could not parse:
        // the record is still delivered, with every field empty. The scan takes any advertiser in
        // range, so one malformed packet read without that check would end the process from this
// main-looper callback.
        val serviceUuids = mergedAdvertisedServiceUuids(
            record.serviceUuids?.map(ParcelUuid::getUuid),
            record.serviceData?.keys?.map(ParcelUuid::getUuid),
        )
        val advertisedName = record.deviceName
        val scale = BLUETOOTH_MEASUREMENT_ADAPTERS.firstOrNull {
            it.supports(advertisedName, serviceUuids)
        }
        val colmiModel = BluetoothColmiRing.identifyName(advertisedName)
        // The bike is identified by the packet it is broadcasting, not by anything it claims to be:
        // it advertises no service UUID and never accepts a connection.
        val keiserPayload = record.getManufacturerSpecificData(BluetoothKeiserM3i.COMPANY_ID)
        val keiser = BluetoothKeiserM3i.isBroadcast(keiserPayload)
        val bangleJs = BangleJsOfflineProtocol.isAdvertisedName(advertisedName)
        val kind = directDeviceKind(serviceUuids, scale, colmiModel, keiser, bangleJs) ?: return
        // getName() is a synchronous binder round trip into the Bluetooth process, and this
        // callback runs on the main looper for every advertisement of every advertiser in range.
        // The parsed packet already carries the local name; the stack's cached copy is worth the
        // round trip only for a packet that has already matched something this app collects.
        val deviceName = advertisedName ?: runCatching { result.device.name }.getOrNull()

        val direct = DirectDevice(
            address = result.device.address,
            name = deviceName ?: kind.defaultName(appContext),
            kind = kind,
            adapterId = scale?.id ?: colmiModel?.let { "colmi_${it.name.lowercase()}" },
            // The Keiser console is a transmit-only advertiser: there is nothing to connect to, and
            // the ride is already in the packet the scan just delivered.
            advertisementOnly = scale == XiaomiMiScaleV2 || keiser,
        )
        // A sensor advertises many times per second, and every republication rewrites the whole
        // Compose list. A crowded room can also offer more devices than the list can usefully
        // show, so unknown addresses stop being accepted once it is full.
        val previous = devices[direct.address]
        if (previous != null || devices.size < MAX_SCAN_DEVICES) {
            results[direct.address] = result
            devices[direct.address] = direct
            if (previous != direct) main.post { onDevicesChanged(devices.values.toList()) }
        }

        if (scale?.parsesAdvertisementData == true && takesBroadcastWeighing(direct.address, watchedAdvertiser)) {
            scale.advertisedServiceUuids.forEach { uuid ->
                record.getServiceData(ParcelUuid(uuid))?.let { payload ->
                    scale.parse(payload)?.takeIf { it.isStable }?.let { measurement ->
                        val sourceId = sourceIdOf(direct)
                        main.post { onScaleMeasurement(measurement, sourceId) }
                    }
                }
            }
        }

        // Only the bike somebody chose, and only while it is actually being ridden: a room of
        // Keisers all advertise at once, and a console that has finished replays the ride it just
        // ended until it is reset.
        if (keiser && direct.address == watchedAdvertiser) {
            BluetoothKeiserM3i.parse(keiserPayload)?.let { measurement ->
                // Asked again when its turn comes: the screen may have let the bike go meanwhile.
                main.post { if (direct.address == watchedAdvertiser) onFitnessMeasurement(measurement) }
            }
        }
    }

    /**
     * The advertisement-only device whose packets are being taken, chosen the way a connectable one
     * is chosen by connecting. Without it the first Keiser to advertise would write its ride into
     * whatever the person had opened, and the bike beside it would overwrite that a moment later.
     * Snapshot state for [watching].
     */
    private var watchedAdvertiser by mutableStateOf<String?>(null)

    /**
     * Whether the search is the link: an advertiser is being watched, and stopping the search, or
     * starting it afresh with its fifteen-second stop, would end the ride while the card still said
     * connected. The device picker reads it to leave its search button out.
     */
    val watching: Boolean
        get() = watchedAdvertiser != null

    /**
     * Point everything at [device], whatever was pointed at before.
     *
     * Not [connect]: that one refuses while a link is open, and a graceful [disconnect] does not
     * close the handle until the peripheral answers — so a person tapping a second machine would get
     * "disconnect_first" while the first machine goes on filling the card under the second one's
     * name. Choosing is a decision, so the old link is dropped there and then; the peripheral it
     * dropped notices at its own supervision timeout, which is the price of an immediate answer.
     */
    fun choose(device: DirectDevice) {
        gatt?.let(::release)
        watchedAdvertiser = null
        if (device.advertisementOnly) watch(device) else connect(device)
    }

    /** Take the ride from this advertiser and no other; [disconnect] stops it, as it stops a link. */
    fun watch(device: DirectDevice) {
        // An advertiser replaces a link as surely as another link would; without this, two
        // machines would write into one card and the card would show whichever spoke last.
        gatt?.let(::release)
        // For an advertiser the scan is the link, so it has to outlive the fifteen seconds that
        // merely finding a device is given — and be restarted if that fifteen seconds already ran
        // out while the person was choosing.
        if (!scanning) startScan()
        // No scanner means the radio is off, and startScan has said so; CONNECTED after it would put a
        // ride card on a page that can hear nothing.
        if (!scanning) return
        watchedAdvertiser = device.address
        connected = device
        cancelScanTimeout()
        onConnectionChanged(DirectConnectionState.CONNECTED, device.name)
    }

    private fun subscribe(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: return false
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return gatt.writeDescriptor(descriptor, value) ==
            android.bluetooth.BluetoothStatusCodes.SUCCESS
    }

    private companion object {
        /** The largest MTU Android will negotiate; the peripheral answers with what it can do. */
        const val PREFERRED_MTU = 517

        const val NEEDS_AUTHENTICATION = BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
        const val NEEDS_ENCRYPTION = BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION

        val DEVICE_INFORMATION_SERVICE_UUID: UUID = bluetoothUuid(0x180A)
        val BATTERY_SERVICE_UUID: UUID = bluetoothUuid(0x180F)
        val BATTERY_LEVEL_UUID: UUID = bluetoothUuid(0x2A19)
        val MODEL_NUMBER_UUID: UUID = bluetoothUuid(0x2A24)
        val SERIAL_NUMBER_UUID: UUID = bluetoothUuid(0x2A25)
        val FIRMWARE_REVISION_UUID: UUID = bluetoothUuid(0x2A26)
        val HARDWARE_REVISION_UUID: UUID = bluetoothUuid(0x2A27)
        val SOFTWARE_REVISION_UUID: UUID = bluetoothUuid(0x2A28)
        val MANUFACTURER_NAME_UUID: UUID = bluetoothUuid(0x2A29)
        val FITNESS_MEASUREMENT_UUIDS = setOf(
            BluetoothFitnessMachine.treadmillDataUuid,
            BluetoothFitnessMachine.rowerDataUuid,
            BluetoothFitnessMachine.indoorBikeDataUuid,
        )
        val CONCEPT2_MEASUREMENT_UUIDS = setOf(
            BluetoothConcept2Pm5.generalStatusUuid,
            BluetoothConcept2Pm5.additionalStatusOneUuid,
            BluetoothConcept2Pm5.additionalStatusTwoUuid,
        )
        val OXIMETRY_MEASUREMENT_UUIDS = setOf(
            BluetoothPulseOximeter.spotCheckMeasurementUuid,
            BluetoothPulseOximeter.continuousMeasurementUuid,
        )

        const val MAX_METADATA_TEXT_LENGTH = 128
        const val COLMI_COMMAND_TIMEOUT_MS = 5_000L
        const val BANGLE_RESPONSE_TIMEOUT_MS = 5_000L
        const val GLUCOSE_RACP_TIMEOUT_MS = 30_000L
        const val READY_TIMEOUT_MS = 30_000L
        const val BOND_TIMEOUT_MS = 90_000L

        /** A whole day of history: one header frame and up to 96 quarter-hour records. */
        const val MAX_DEFERRED_COLMI_NOTIFICATIONS = 97
        const val MAX_SCAN_DEVICES = 32

        /**
         * What one transfer may hold before it is taken for a meter that will not stop. Every sync
         * asks for all stored records, so a meter that holds more fails every sync, not one. Meters
         * keep from a few hundred records to about a thousand, so 1,024 would leave next to no margin
         * above that; a record held here costs a few hundred bytes.
         */
        const val MAX_GLUCOSE_PROCEDURE_RECORDS = 4_096

        /** Readings kept until the thermometer's site is read; one without the characteristic never says. */
        const val MAX_SITELESS_TEMPERATURES = 64
    }

    private fun handleCharacteristic(uuid: UUID, value: ByteArray) {
        if (handleColmiCharacteristic(uuid, value)) return
        if (handleBangleCharacteristic(uuid, value)) return
        if (handleBasicCharacteristic(uuid, value)) return
        if (handleWorkoutCharacteristic(uuid, value)) return
        if (handleCyclingCharacteristic(uuid, value)) return
        handleClinicalCharacteristic(uuid, value)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Stateful multipart protocol boundary.
    private fun handleColmiCharacteristic(uuid: UUID, value: ByteArray): Boolean {
        if (uuid != BluetoothColmiRing.notifyUuid || connected?.kind != DirectDeviceKind.COLMI_RING) {
            return false
        }
        if (connectionReady) return true

        val pendingPurpose = pendingWrite?.purpose
        if (pendingPurpose == DirectWritePurpose.COLMI_BATTERY ||
            pendingPurpose == DirectWritePurpose.COLMI_HISTORY
        ) {
            // Dropping the oldest frame here would only move the failure: the assembler would then meet a
            // record index it was not expecting and fail the whole day as out of sequence.
            if (deferredColmiNotifications.size == MAX_DEFERRED_COLMI_NOTIFICATIONS) {
                gatt?.let { fail(it, "colmi_buffer_overflow") }
                return true
            }
            deferredColmiNotifications.addLast(value.copyOf())
            return true
        }

        val activeGatt = gatt ?: return true
        val session = colmiSession ?: run {
            fail(activeGatt, "colmi_session")
            return true
        }
        cancelColmiTimeout()
        when (val result = session.accept(value)) {
            ColmiSyncResult.AwaitingMore ->
                scheduleColmiTimeout(activeGatt, "colmi_history_timeout")

            is ColmiSyncResult.RequestHistory -> {
                metadata = metadata.copy(batteryPercent = result.battery.percent)
                main.post { onMetadataChanged(metadata) }
                val write = activeGatt.getService(BluetoothColmiRing.serviceUuid)
                    ?.getCharacteristic(BluetoothColmiRing.writeUuid)
                if (write == null || !beginWrite(
                        activeGatt,
                        write,
                        result.payload,
                        DirectWritePurpose.COLMI_HISTORY,
                    )
                ) {
                    fail(activeGatt, "colmi_history_request")
                }
            }

            is ColmiSyncResult.Complete -> {
                metadata = metadata.copy(batteryPercent = result.snapshot.battery.percent)
                main.post {
                    onMetadataChanged(metadata)
                    onProfileMeasurement(DirectProfileMeasurement.ColmiHistory(result.snapshot))
                }
                markConnected()
                enqueueStandardMetadataReads(activeGatt)
            }

            is ColmiSyncResult.Rejected -> fail(activeGatt, result.detail)
        }
        return true
    }

    /** The watch's one answer, however the notifications split it; the link is let go of once it is in. */
    private fun handleBangleCharacteristic(uuid: UUID, value: ByteArray): Boolean {
        if (uuid != BangleJsOfflineProtocol.transmitUuid || connected?.kind != DirectDeviceKind.BANGLE_JS2) {
            return false
        }
        val activeGatt = gatt
        when (val result = bangleSession?.takeUnless { connectionReady }?.accept(value)) {
            is BangleJsSyncResult.Complete -> {
                // Posted, not delivered: the hang-up below must not hold back the snapshot it ends.
                main.post { onBangleSync(result.sync) }
                markConnected()
                disconnect()
            }

            is BangleJsSyncResult.Rejected -> activeGatt?.let { fail(it, result.detail) }

            BangleJsSyncResult.AwaitingMore, null -> Unit
        }
        return true
    }

    private fun handleBasicCharacteristic(uuid: UUID, value: ByteArray): Boolean = when (uuid) {
        BluetoothWeightScale.measurementUuid -> {
            // This characteristic is subscribed only for scales, and a body-composition scale that
            // advertised 0x181B alone matches no vendor adapter — without the SIG decoder its
            // weight indication would be dropped after the user already stepped on it.
            (scaleAdapter ?: BluetoothWeightScale).parse(value)?.takeIf { it.isStable }?.let { measurement ->
                val sourceId = sourceIdOf(connected)
                deliver { onScaleMeasurement(measurement, sourceId) }
            }
            true
        }

        BluetoothHeartRate.measurementUuid -> {
            BluetoothHeartRate.parse(value)?.let { measurement ->
                deliver { onHeartRate(measurement) }
            }
            true
        }

        BluetoothRunningSpeedCadence.measurementUuid -> {
            BluetoothRunningSpeedCadence.parse(value)?.let { measurement ->
                deliver { onRunning(measurement) }
            }
            true
        }

        else -> false
    }

    private fun handleWorkoutCharacteristic(uuid: UUID, value: ByteArray): Boolean {
        if (uuid in FITNESS_MEASUREMENT_UUIDS) {
            BluetoothFitnessMachine.parse(uuid, value)?.let { frame ->
                val measurement = fitnessAccumulator.update(frame)
                deliver { onFitnessMeasurement(measurement) }
            }
            return true
        }
        if (uuid in CONCEPT2_MEASUREMENT_UUIDS) {
            BluetoothConcept2Pm5.parse(uuid, value)?.let { partial ->
                val measurement = concept2Accumulator.update(partial)
                deliver { onFitnessMeasurement(measurement) }
            }
            return true
        }
        return false
    }

    private fun handleCyclingCharacteristic(uuid: UUID, value: ByteArray): Boolean = when (uuid) {
        BluetoothCyclingSpeedCadence.measurementUuid -> {
            BluetoothCyclingSpeedCadence.parse(value)?.let { parsed ->
                postCycling(cyclingAccumulator.update(parsed))
            }
            true
        }

        BluetoothCyclingPower.measurementUuid -> {
            BluetoothCyclingPower.parse(value)?.let { parsed ->
                postCycling(cyclingAccumulator.update(parsed))
            }
            true
        }

        else -> false
    }

    private fun postCycling(measurement: CyclingLiveMeasurement) {
        deliver { onProfileMeasurement(DirectProfileMeasurement.Cycling(measurement)) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleClinicalCharacteristic(uuid: UUID, value: ByteArray) {
        when {
            uuid == BluetoothBloodPressure.measurementUuid ->
                BluetoothBloodPressure.parse(value)?.let {
                    postProfile(DirectProfileMeasurement.BloodPressure(it))
                }

            uuid in OXIMETRY_MEASUREMENT_UUIDS ->
                BluetoothPulseOximeter.parse(uuid, value)?.let {
                    postProfile(DirectProfileMeasurement.PulseOximeter(it))
                }

            uuid == BluetoothHealthThermometer.measurementUuid ->
                BluetoothHealthThermometer.parse(value)?.let {
                    if (it.type == null && thermometerSite == null) awaitThermometerSite(it)
                    postProfile(DirectProfileMeasurement.Temperature(it.withDefaultType(thermometerSite)))
                }

            uuid == BluetoothBodyComposition.measurementUuid ->
                BluetoothBodyComposition.parse(value)
                    ?.let(bodyCompositionAccumulator::update)
                    ?.let { postProfile(DirectProfileMeasurement.BodyComposition(it)) }

            uuid == BluetoothGlucose.measurementUuid -> {
                val parsed = BluetoothGlucose.parse(value)
                // A record the strict parser turns away is still a record the meter sent, and the
                // person is told how many there were — see onGlucoseSyncComplete.
                if (parsed == null) glucoseUnreadable += 1
                parsed
                    ?.also { restartGlucoseRacpTimeout() }
                    ?.let(glucoseAccumulator::update)
                    ?.let(::bufferGlucoseRecord)
            }

            uuid == BluetoothGlucose.contextUuid ->
                BluetoothGlucose.parseContext(value)
                    ?.also { restartGlucoseRacpTimeout() }
                    ?.let(glucoseAccumulator::update)
                    ?.let(::bufferGlucoseRecord)

            uuid == BluetoothGlucose.racpUuid -> handleGlucoseRacpCompletion(value)
        }
    }

    private fun handleGlucoseRacpCompletion(value: ByteArray) {
        val activeGatt = gatt ?: return
        when (val completion = BluetoothGlucoseRacp.parseReportCompletion(value)) {
            GlucoseRacpCompletion.Success -> {
                cancelGlucoseRacpTimeout()
                // A context the meter promised and never sent does not void the records of the
                // transfer. The reading stands without it, as it does when the pairing buffer
                // overruns: only the context is missing, and the context is not mandatory.
                glucoseAccumulator.drainPending().forEach(::bufferGlucoseRecord)
                val records = glucoseProcedureRecords.values.toList()
                glucoseProcedureRecords.clear()
                val unreadable = glucoseUnreadable
                glucoseUnreadable = 0
                main.post {
                    records.forEach {
                        onProfileMeasurement(DirectProfileMeasurement.Glucose(it))
                    }
                    // Said for every finished transfer, not only an empty one: once the records are
                    // saved and their list cleared, the page would otherwise go back to "waiting for data".
                    onGlucoseSyncComplete(records.size, unreadable)
                }
            }

            GlucoseRacpCompletion.NoRecords -> {
                glucoseProcedureRecords.clear()
                cancelGlucoseRacpTimeout()
                val unreadable = glucoseUnreadable
                glucoseUnreadable = 0
                main.post { onGlucoseSyncComplete(0, unreadable) }
            }

            is GlucoseRacpCompletion.Error -> fail(
                activeGatt,
                "glucose_racp:${completion.responseCode}",
            )

            null -> fail(activeGatt, "glucose_racp_response")
        }
    }

    private fun bufferGlucoseRecord(record: GlucoseRecord) {
        val sequence = record.measurement.sequenceNumber
        if (sequence !in glucoseProcedureRecords &&
            glucoseProcedureRecords.size >= MAX_GLUCOSE_PROCEDURE_RECORDS
        ) {
            gatt?.let { fail(it, "glucose_record_limit") }
            return
        }
        glucoseProcedureRecords[sequence] = record
    }

    private fun postProfile(measurement: DirectProfileMeasurement) {
        deliver { onProfileMeasurement(measurement) }
    }

    /** Hands a link's reading to the screen, unless the screen hung up while it waited its turn. */
    private fun deliver(reading: () -> Unit) {
        main.post(unlessHungUp({ hangingUp }, reading))
    }
}

/**
 * A link's reading on its way to the screen, which asks whether the screen has [hungUp] when its
 * turn on the main thread comes and not when it was queued: the packet already waiting there as
 * Disconnect is tapped is the one that would put the emptied card back.
 */
internal fun unlessHungUp(hungUp: () -> Boolean, reading: () -> Unit): Runnable = Runnable { if (!hungUp()) reading() }

/**
 * Whether a search that could not start is the connection's failure. It is where the search is all
 * the connection there is — nothing linked yet, or an advertiser being watched, whose search is its
 * link. Beside an open link it says nothing about that link: published as the connection's state,
 * it would take Disconnect off the page while the device goes on sending, and every Connect after
 * it would be refused. There the search answers for itself ([searchStatusRes]).
 */
internal fun searchFailureIsTheConnections(linkOpen: Boolean): Boolean = !linkOpen

/**
 * Whether a weighing a scale broadcasts is taken. Until a broadcaster is chosen the scale somebody
 * is standing on is the one speaking; once one is ([watched]), a second Mi Scale in range — a
 * flatmate's, or a neighbour's through the wall — is no longer written into the chosen one's card,
 * as a second Keiser is not.
 */
internal fun takesBroadcastWeighing(address: String, watched: String?): Boolean = watched == null || address == watched

/**
 * The token for a device that wants pairing the phone could not start. createBond() refuses a device
 * the phone is already paired with, and one that still turns the link away has forgotten its half of
 * the pairing, or wants a stronger one: no request is coming to accept, so the person must not be
 * told to accept one. Only forgetting the device in Android's settings and pairing afresh gets past it.
 */
internal fun bondRefusal(bondState: Int): String =
    if (bondState == BluetoothDevice.BOND_BONDED) "bond_stale" else "bond"

@Suppress("CyclomaticComplexMethod")
internal fun directDeviceKind(
    serviceUuids: Set<UUID>,
    scale: BluetoothMeasurementAdapter?,
    colmiModel: ColmiRingModel?,
    keiser: Boolean = false,
    bangleJs: Boolean = false,
): DirectDeviceKind? = when {
    scale == XiaomiMiScaleV2 -> DirectDeviceKind.SCALE

    colmiModel != null -> DirectDeviceKind.COLMI_RING

    // The name is the watch's own; a heart rate service some other watch app advertises beside it
    // does not make it a strap.
    bangleJs -> DirectDeviceKind.BANGLE_JS2

    // A decoded Keiser packet is proof on its own; the bike advertises no service at all, and the
    // strap somebody is wearing on it must not rename the bike a heart rate monitor.
    keiser -> DirectDeviceKind.KEISER_M3I

    BluetoothConcept2Pm5.serviceUuid in serviceUuids ||
        BluetoothConcept2Pm5.advertisedServiceUuid in serviceUuids -> DirectDeviceKind.CONCEPT2_PM5

    BluetoothFitnessMachine.serviceUuid in serviceUuids -> DirectDeviceKind.FITNESS_MACHINE

    BluetoothGlucose.serviceUuid in serviceUuids -> DirectDeviceKind.GLUCOSE_METER

    BluetoothBloodPressure.serviceUuid in serviceUuids -> DirectDeviceKind.BLOOD_PRESSURE

    BluetoothPulseOximeter.serviceUuid in serviceUuids -> DirectDeviceKind.PULSE_OXIMETER

    BluetoothHealthThermometer.serviceUuid in serviceUuids -> DirectDeviceKind.THERMOMETER

    BluetoothBodyComposition.serviceUuid in serviceUuids -> DirectDeviceKind.BODY_COMPOSITION

    // A pod that speaks RSCS beside CPS (Stryd) is a running pod first: pace and cadence are what
    // it is worn for, and the cycling MET would be the wrong one on every run it saved.
    BluetoothRunningSpeedCadence.serviceUuid in serviceUuids -> DirectDeviceKind.RUNNING_POD

    BluetoothCyclingPower.serviceUuid in serviceUuids ||
        BluetoothCyclingSpeedCadence.serviceUuid in serviceUuids -> DirectDeviceKind.CYCLING_SENSOR

    BluetoothHeartRate.serviceUuid in serviceUuids -> DirectDeviceKind.HEART_RATE

    scale != null -> DirectDeviceKind.SCALE

    else -> null
}

/** The kind label doubles as the name of an advertisement that carries none. */
@StringRes
@Suppress("CyclomaticComplexMethod") // One branch per kind, and nothing else.
internal fun DirectDeviceKind.labelRes(): Int = when (this) {
    DirectDeviceKind.SCALE -> R.string.direct_bluetooth_scale
    DirectDeviceKind.FITNESS_MACHINE -> R.string.direct_bluetooth_machine
    DirectDeviceKind.CONCEPT2_PM5 -> R.string.direct_bluetooth_concept2
    DirectDeviceKind.KEISER_M3I -> R.string.direct_bluetooth_keiser
    DirectDeviceKind.HEART_RATE -> R.string.direct_bluetooth_heart_rate
    DirectDeviceKind.RUNNING_POD -> R.string.direct_bluetooth_pod
    DirectDeviceKind.CYCLING_SENSOR -> R.string.direct_bluetooth_cycling_sensor
    DirectDeviceKind.BLOOD_PRESSURE -> R.string.direct_bluetooth_blood_pressure
    DirectDeviceKind.PULSE_OXIMETER -> R.string.direct_bluetooth_pulse_oximeter
    DirectDeviceKind.THERMOMETER -> R.string.direct_bluetooth_thermometer
    DirectDeviceKind.GLUCOSE_METER -> R.string.direct_bluetooth_glucose_meter
    DirectDeviceKind.BODY_COMPOSITION -> R.string.direct_bluetooth_body_composition_scale
    DirectDeviceKind.COLMI_RING -> R.string.direct_bluetooth_colmi_ring
    DirectDeviceKind.BANGLE_JS2 -> R.string.direct_bluetooth_banglejs
}

private fun DirectDeviceKind.defaultName(context: Context): String = context.getString(labelRes())

/**
 * The provenance line of a saved reading: what the device called itself, then what this install
 * calls it. The name is whatever the peripheral broadcasts and two of a make share it, so the
 * pseudonymous id is the part that says which meter or scale this reading actually came from.
 */
internal fun directMeasurementNote(vararg parts: String?): String =
    parts.mapNotNull { part -> part?.takeIf(String::isNotBlank) }.joinToString(" · ")

/**
 * Service Data has its own AD structure; Android does not also expose its UUID in serviceUuids.
 *
 * Either list is null, not empty, when the platform gave up on the packet: a malformed AD structure
 * leaves a scan record with no parsed field at all. Such a packet advertises nothing.
 */
internal fun mergedAdvertisedServiceUuids(
    declaredServiceUuids: Collection<UUID>?,
    serviceDataUuids: Collection<UUID>?,
): Set<UUID> = LinkedHashSet<UUID>().apply {
    addAll(declaredServiceUuids.orEmpty())
    addAll(serviceDataUuids.orEmpty())
}

private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

/**
 * Whether a bond-state broadcast is pairing that the platform began on its own, on a link that is
 * not ready yet.
 *
 * Android answers a peripheral's "insufficient authentication" by repeating the write itself with
 * encryption asked for, and the callback this app would have heard is never made; a peripheral can
 * also ask for pairing the moment it is connected. Either way the system's dialog is up while the
 * manager still counts the thirty seconds it gives a connection, and whoever types a passkey slower
 * than that would be hung up on in the middle of pairing. Pairing the manager asked for itself has its
 * ninety seconds already.
 */
internal fun pairingStartedByPlatform(bondState: Int, bondRequested: Boolean, connectionReady: Boolean): Boolean =
    bondState == BluetoothDevice.BOND_BONDING && !bondRequested && !connectionReady

/** About twenty minutes of beats. Past that the oldest go, so the figure is about now. */
private const val MAX_RR_INTERVALS = 1_200

/**
 * A live GATT link and the readings it has already delivered cannot be rebuilt by recomposing: a
 * scale sends its weight once, a glucose sync takes a bond plus a whole record transfer. Activity
 * recreation — rotation, locale, split-screen resize — throws the composition away but keeps the
 * process, so the section [retain]s the session across it. Leaving the screen for real closes it,
 * and so does a rebuilt window whose first frame does not draw the section — one that opens behind
 * the app lock. A slot of the app's own would keep the session there, live link and all, until
 * the process ended if that window were then left.
 */
private class DirectBluetoothSession {
    val devices = mutableStateListOf<DirectDevice>()
    val scanning = mutableStateOf(false)

    /**
     * Whether a scan has run at all in this session. An empty list before the first tap means
     * "you have not searched yet"; an empty list after one means "nothing answered", and only
     * this flag tells the two apart.
     */
    val searched = mutableStateOf(false)

    /** Whether the last search could not start beside an open link; the next search clears it. */
    val searchFailed = mutableStateOf(false)
    val connection = mutableStateOf(DirectConnectionState.IDLE)
    val connectionName = mutableStateOf<String?>(null)
    val connectionKind = mutableStateOf<DirectDeviceKind?>(null)
    val connectionDetail = mutableStateOf<String?>(null)

    /** Provenance of the connected device, and of the scale that broadcast without connecting. */
    val connectionSourceId = mutableStateOf<String?>(null)
    val scaleSourceId = mutableStateOf<String?>(null)
    val scaleMeasurement = mutableStateOf<StandardScaleMeasurement?>(null)
    val fitnessMeasurement = mutableStateOf<FitnessMachineMeasurement?>(null)
    val heartRateMeasurement = mutableStateOf<HeartRateMeasurement?>(null)

    /**
     * Every RR interval this connection has reported, in order.
     *
     * Kept because variability is a property of a run of beats rather than of the notification in
     * hand: the strap sends one or two intervals every second, and RMSSD cannot be computed from a
     * number that has already been thrown away.
     * Cleared when a connection starts, so one strap's session is never averaged with another's.
     */
    val rrIntervalsMs = mutableStateListOf<Int>()
    val runningMeasurement = mutableStateOf<RunningMeasurement?>(null)
    val cyclingMeasurement = mutableStateOf<CyclingLiveMeasurement?>(null)
    val clinicalMeasurement = mutableStateOf<DirectProfileMeasurement?>(null)

    /**
     * The readings the connected device has handed over, beside the one a card holds — see
     * [storedReadingsAfter]. The weighings are a list of their own because the scale card is.
     */
    val storedClinical = mutableStateOf<List<DirectProfileMeasurement>>(emptyList())
    val storedWeighings = mutableStateOf<List<StandardScaleMeasurement>>(emptyList())
    val colmiHistory = mutableStateOf<ColmiHistorySnapshot?>(null)
    val bangleSync = mutableStateOf<BangleJsSync?>(null)
    val glucoseRecords = mutableStateListOf<GlucoseRecord>()

    /** Record count of a finished glucose sync, so a meter with nothing new is not "waiting for data". */
    val glucoseSyncedCount = mutableStateOf<Int?>(null)

    /** Notifications of that sync the parser could not read as a record. */
    val glucoseUnreadable = mutableIntStateOf(0)
    val deviceMetadata = mutableStateOf(DirectDeviceMetadata())
    val workoutStartedAt = mutableStateOf<Long?>(null)

    /** A foot pod's Total Distance in the first packet of the connection — see [RunningMeasurement.sinceStart]. */
    var runningStartMeters: Double? = null
    var manager: DirectBluetoothManager? = null

    /** Takes [arrived] into [storedClinical] when it is one to keep and it can be saved. */
    fun rememberClinical(arrived: DirectProfileMeasurement) {
        val keep = arrived.canSaveClinicalMeasurement() && arrived.isKeptBesideCard(System.currentTimeMillis())
        storedClinical.value = storedReadingsAfter(storedClinical.value, arrived, keep, ::isSameClinicalReading)
    }

    /**
     * Set by Finish and cleared by the next connection. Finish asks the link to close and does not
     * wait for it, and a machine goes on notifying until it has: one more packet would put the card
     * back, Finish and all, with the whole workout still in it to be saved a second time.
     */
    var workoutFinished = false

    /** Ends the workout inside the tap that finished it; the row is written after. */
    fun finishWorkout() {
        workoutFinished = true
        workoutStartedAt.value = null
        manager?.disconnect()
    }

    /**
     * The weighing Save was last tapped for: in the journal, or on its way there. A scale
     * rebroadcasts it for many seconds afterwards.
     */
    var lastSavedScaleMeasurement: StandardScaleMeasurement? = null
    var lastSavedScaleSourceId: String? = null

    fun isAlreadySavedWeighing(measurement: StandardScaleMeasurement, sourceId: String?): Boolean {
        val saved = lastSavedScaleMeasurement ?: return false
        return sourceId == lastSavedScaleSourceId && isSameWeighing(saved, measurement)
    }

    /**
     * When the pulse was last saved. Its card cannot be blanked by the tap ([takeReading]): the strap
     * goes on beating into it. The write takes a few milliseconds, so a busy flag cleared after it
     * would not stop a double tap either; [isDuplicateTap]'s window, counted from this, does.
     */
    var heartRateSavedAt: Long? = null
}

/**
 * Blanks a card and answers what was on it, or null when an earlier tap has already taken it.
 *
 * Blanked only after the row is written and Health Connect has been told — and the second of those
 * waits for a whole exchange when one is running — the card would keep its button live for all of
 * it, so a second tap would write the reading, or the workout with its calories, a second time. The
 * card is blanked inside the tap instead, and the tap that finds it blank has nothing to save.
 */
internal fun <T : Any> MutableState<T?>.takeReading(): T? = value.also { value = null }

/**
 * The readings a device has handed over beside the one on its card, once [arrived] has come in.
 *
 * A cuff, a thermometer or a scale with a memory indicates everything it kept the moment
 * indications are switched on, a tenth of a second apart, and Android acknowledges each one on the
 * app's behalf. A card holds one reading, so all but the last would be overwritten before anyone
 * could see them, while the device counts them delivered and need never offer them again: three
 * mornings of blood pressure would come down to the third. They are kept here instead, and Save
 * writes them with the reading on the card.
 *
 * [keep] is the caller's word on whether [arrived] is such a reading. A cuff or a thermometer sends
 * what it has when it is done measuring — three readings of one sitting, a minute apart, as often
 * as last week's — so every reading it dated itself is one; a reading dated on arrival is live,
 * and the next replaces it on the card. A scale's are judged by [isFromDeviceMemory].
 * A reading the device sends again — a thermometer's, re-posted once its site is known — replaces
 * its earlier copy.
 */
internal fun <T : Any> storedReadingsAfter(
    stored: List<T>,
    arrived: T,
    keep: Boolean,
    sameReading: (T, T) -> Boolean,
): List<T> = (stored.filterNot { sameReading(it, arrived) } + listOfNotNull(arrived.takeIf { keep }))
    .takeLast(MAX_STORED_READINGS)

/** More than a home device holds for one person; past it the oldest go. */
private const val MAX_STORED_READINGS = 256

/**
 * Whether a weighing came out of the scale's memory rather than off the person standing on it:
 * the scale dated it itself, and more than five minutes before it arrived. A scale is stood on
 * while the phone listens, and stepping off and on again is one weighing tried twice, which the
 * card goes on answering with the last. Five minutes is what two clocks are allowed to disagree by
 * ([isPlausibleDeviceTime]), so a scale running a little slow does not turn every try into a row.
 */
internal fun isFromDeviceMemory(deviceTime: Long?, arrivalMillis: Long): Boolean =
    deviceTime != null && arrivalMillis - deviceTime > STORED_READING_AGE_MS

private const val STORED_READING_AGE_MS = 5L * 60 * 1_000

/** Whether a reading is kept beside the card: see [storedReadingsAfter]. A composition is a scale's. */
internal fun DirectProfileMeasurement.isKeptBesideCard(arrivalMillis: Long): Boolean = when (this) {
    is DirectProfileMeasurement.BodyComposition -> isFromDeviceMemory(deviceTime(), arrivalMillis)
    else -> deviceTime() != null
}

/**
 * The stored readings a Save of [shown] takes with it: every other one the device files under the
 * same user. A cuff or a scale shared by a household keeps each person's readings under a number,
 * and the one on the card is the one the person looked at and called theirs.
 */
internal fun <T : Any> List<T>.savedBeside(
    shown: T,
    sameReading: (T, T) -> Boolean,
    userOf: (T) -> Int?,
): List<T> = filter { !sameReading(it, shown) && userOf(it) == userOf(shown) }

/**
 * How many stored readings a Save of [shown] leaves behind: those the device files under another
 * user. They are not this person's to save, and the page drops them with the rest of the memory,
 * not without a word: the card prints a count of them.
 */
internal fun <T : Any> List<T>.leftBeside(
    shown: T,
    sameReading: (T, T) -> Boolean,
    userOf: (T) -> Int?,
): Int = count { !sameReading(it, shown) && userOf(it) != userOf(shown) }

/**
 * When the device itself says the reading was taken, or null for one dated on arrival. An oximeter
 * has none: its spot checks are live, and its memory is behind a procedure this app does not run.
 */
internal fun DirectProfileMeasurement.deviceTime(): Long? = when (this) {
    is DirectProfileMeasurement.BloodPressure -> value.occurredAt.takeIf { value.deviceClockSet == true }

    is DirectProfileMeasurement.Temperature -> value.occurredAt.takeIf { value.deviceClockSet == true }

    is DirectProfileMeasurement.BodyComposition ->
        value.timestamp?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    else -> null
}

internal fun StandardScaleMeasurement.deviceTime(): Long? = occurredAt.takeIf { deviceClockSet == true }

/** The number a shared device files the reading under, where its profile has one. */
internal fun DirectProfileMeasurement.userId(): Int? = when (this) {
    is DirectProfileMeasurement.BloodPressure -> value.userId
    is DirectProfileMeasurement.BodyComposition -> value.userId
    else -> null
}

/** One reading sent twice: the same kind, dated by the device to the same moment. */
internal fun isSameClinicalReading(one: DirectProfileMeasurement, other: DirectProfileMeasurement): Boolean =
    one::class == other::class && one.deviceTime() == other.deviceTime()

/**
 * Writes the readings the journal does not hold yet. A device may offer what is in its memory at
 * every connection, and a reading already saved on an earlier visit, or restored from Health
 * Connect, would be written again; [sameDirectReading] is how the glucose batch knows one too. A
 * reading dated on arrival has no twin, and is always written.
 */
private suspend fun StepsDb.saveReadingsNotHeld(entries: List<JournalEntry>) {
    entries.forEach { entry ->
        val around = dao().journalAround(entry.kind, entry.occurredAt - 1_000L, entry.occurredAt + 1_000L)
        if (around.none { standsForReading(it, entry) }) saveJournalEvent(entry)
    }
}

/**
 * Whether the row [standing] is [reading], already saved. Only a row from the same kind of device,
 * or a restored one that names none, can be: the plain weight a scale card saved a moment ago
 * matches a composition on its weight and its second, and must not swallow the body fat that came
 * with it.
 */
internal fun standsForReading(standing: JournalEntry, reading: JournalEntry): Boolean =
    (standing.measurementSource ?: reading.measurementSource) == reading.measurementSource &&
        sameDirectReading(standing, reading)

/**
 * The time to print on a reading's card, or null when the reading is filed under today.
 *
 * A reading is filed under the day its device dated it, and a clock believed inside its ten-year
 * window can still be wrong by years. A card with the figure and no date would have Save say "saved"
 * while the row went to a day nobody was looking at.
 */
internal fun readingDateToShow(occurredAt: Long, now: Long, zone: ZoneId): Long? {
    fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return occurredAt.takeIf { dayOf(it) != dayOf(now) }
}

/** The date line of a reading's card; nothing for a reading of today. */
@Composable
private fun ReadingDate(occurredAt: Long) {
    val shown = readingDateToShow(occurredAt, System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
    DirectMetric(
        stringResource(R.string.visit_when),
        // The day as the language orders it, and the time on the 24-hour clock: see [clockText].
        skeletonDateText("yMMMdHm", LocalLocale.current.platformLocale, shown, ZoneId.systemDefault()),
    )
}

/**
 * How many earlier readings of the device the Save below writes: with the one shown, or — where
 * that one cannot be saved, [withShown] false — without it.
 */
@Composable
private fun EarlierReadings(count: Int, withShown: Boolean = true) {
    if (count == 0) return
    val words = if (withShown) {
        R.plurals.direct_bluetooth_earlier_readings
    } else {
        R.plurals.direct_bluetooth_earlier_readings_only
    }
    Text(pluralStringResource(words, count, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How many readings out of the device's memory the Save below leaves, as another user's: [leftBeside]. */
@Composable
private fun OtherUsersReadings(count: Int) {
    if (count == 0) return
    Text(
        pluralStringResource(R.plurals.direct_bluetooth_other_user_readings, count, count),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The line under the search button, or null for none.
 *
 * A search that could not start is said first. Beside an open link it is not the connection's
 * failure ([searchFailureIsTheConnections]), and saying nothing of it would leave the button back
 * at "Search", the code in the log, and the line under it saying nothing had answered a search
 * that never ran. Otherwise both halves of a search are answered — looking, and nothing found — and
 * a list with a device in it speaks for itself.
 */
@StringRes
internal fun searchStatusRes(searched: Boolean, scanning: Boolean, found: Boolean, failed: Boolean): Int? = when {
    failed -> R.string.m3_sync_error_scan
    !searched || found -> null
    scanning -> R.string.direct_bluetooth_searching
    else -> R.string.device_picker_empty
}

/** What the status line says of a link that is up: see [connectedLine]. */
internal enum class ConnectedLine { WAITING, TRANSFER_DONE, CONNECTED }

/**
 * Which line a link that is up gets. [nothingOnPage] is no reading of any kind waiting on the page;
 * [glucoseSynced] is the record count of a glucose transfer that has finished, null until one has,
 * and [glucoseWaiting] whether records of it still wait to be saved.
 *
 * A finished transfer is never "waiting for data". An empty meter finishes without one record, and
 * once a transfer's records are saved and their list cleared the page holds none either: the
 * manager reports the end of every transfer, not only of an empty one.
 */
internal fun connectedLine(nothingOnPage: Boolean, glucoseSynced: Int?, glucoseWaiting: Boolean): ConnectedLine =
    when {
        nothingOnPage && glucoseSynced == null -> ConnectedLine.WAITING
        glucoseSynced != null && !glucoseWaiting -> ConnectedLine.TRANSFER_DONE
        else -> ConnectedLine.CONNECTED
    }

/**
 * How many records of a transfer the Save under it leaves out, for the line that says so: the
 * control-solution tests, the records the meter itself disowned and those it sent without a figure
 * ([readingsToSave]). The page counts every record the meter sent, and without this line a transfer
 * of readings with a control test among them would write fewer rows than it had counted, without a
 * word. Zero where there is nothing to save at all, which [glucoseSaveBlockedReason] says in its
 * own line.
 */
internal fun glucoseLeftOut(records: List<GlucoseRecord>): Int =
    if (glucoseSaveBlockedReason(records) == null) records.size - records.readingsToSave().size else 0

/**
 * What a Save of the clinical card writes: the stored readings of [shown]'s user ([savedBeside])
 * and [shown] itself where it is one that can be saved.
 *
 * A cuff that ends its memory on a failed measurement — figures it sent as not-a-number — leaves the
 * last card unsaveable. The mornings before it are saved without it, rather than going with it
 * behind a grey Save that nothing else on the page could stand in for.
 */
internal fun clinicalReadingsToSave(
    stored: List<DirectProfileMeasurement>,
    shown: DirectProfileMeasurement,
): List<DirectProfileMeasurement> =
    stored.savedBeside(shown, ::isSameClinicalReading, DirectProfileMeasurement::userId) +
        listOfNotNull(shown.takeIf { it.canSaveClinicalMeasurement() })

/**
 * Why the Save under a glucose transfer is grey, or null when it holds a reading to save. A
 * transfer made only of control-solution tests would otherwise say nothing: the meter has sent
 * records, the figure line is empty and the button is dead.
 */
@StringRes
internal fun glucoseSaveBlockedReason(records: List<GlucoseRecord>): Int? = when {
    records.readingsToSave().isNotEmpty() -> null

    records.all { it.measurement.controlSolution } -> R.string.direct_bluetooth_control_solution_only

    // A reading of the person the meter did not disown, which came without its figure.
    records.any { !it.measurement.flaggedInvalid && !it.measurement.controlSolution } ->
        R.string.m3_sync_reading_incomplete

    else -> R.string.direct_bluetooth_reading_invalid
}

/**
 * The centre flash after a glucose Save. A transfer that brought nothing but records already in
 * the journal writes no row, and the flash must not say "saved" over it.
 */
@StringRes
internal fun glucoseSavedRes(inserted: Int): Int =
    if (inserted > 0) R.string.direct_bluetooth_saved else R.string.direct_bluetooth_glucose_already_saved

/**
 * [onSaved] receives the confirmation of a saved reading or workout, for the page to show as its
 * centre flash: a line printed at the foot of this card would be below the fold, silent to a screen
 * reader, and the tap that wrote it has just removed the button focus was on.
 */
@Composable
internal fun DirectBluetoothSection(profile: UserProfile, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    // Every launch on it is a reading written to the database: a sheet swiped away a moment after
    // Save must not cancel the write inside its transaction.
    val scope = rememberAutoTakeScope()
    val weightTitle = stringResource(R.string.measurement_weight_title)
    val heartRateTitle = stringResource(R.string.measurement_heart_rate_title)
    val scaleSavedText = stringResource(R.string.direct_bluetooth_saved)
    val workoutTitle = stringResource(R.string.direct_bluetooth_workout_title)
    val cyclingWorkoutTitle = stringResource(R.string.direct_bluetooth_cycling_workout_title)
    val workoutSavedText = stringResource(R.string.direct_bluetooth_workout_saved)
    val glucoseTitle = stringResource(R.string.direct_bluetooth_glucose_title)
    val colmiHistoryTitle = stringResource(R.string.direct_bluetooth_colmi_history_title)
    val clockUnsetNote = stringResource(R.string.m3_sync_scale_clock_unset)
    val imperial = profile.imperial
    val session = retain { DirectBluetoothSession() }
    val devices = session.devices
    var scanning by session.scanning
    var searched by session.searched
    var connection by session.connection
    var connectionName by session.connectionName
    var connectionKind by session.connectionKind
    var connectionDetail by session.connectionDetail
    var connectionSourceId by session.connectionSourceId
    var scaleSourceId by session.scaleSourceId
    var scaleMeasurement by session.scaleMeasurement
    var fitnessMeasurement by session.fitnessMeasurement
    var heartRateMeasurement by session.heartRateMeasurement
    var runningMeasurement by session.runningMeasurement
    var cyclingMeasurement by session.cyclingMeasurement
    var clinicalMeasurement by session.clinicalMeasurement
    var colmiHistory by session.colmiHistory
    var bangleSync by session.bangleSync
    val glucoseRecords = session.glucoseRecords
    var glucoseSyncedCount by session.glucoseSyncedCount
    var glucoseUnreadable by session.glucoseUnreadable
    var deviceMetadata by session.deviceMetadata
    var workoutStartedAt by session.workoutStartedAt

    fun permissionsGranted(): Boolean = BLUETOOTH_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(permissionsGranted()) }
    // Nearby devices is granted on Android's own settings page as often as through the dialog, and
    // this card is what the person comes back to from there.
    LifecycleResumeEffect(Unit) {
        granted = permissionsGranted()
        onPauseOrDispose { }
    }
    // After the second refusal Android stops showing its dialog and answers every request with an
    // instant denial, so "Allow" would be a button that does nothing at all. Once the system says it
    // will not ask again, the app's settings page is the only door left, and the card offers that.
    var shutOut by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val manager = remember {
        session.manager ?: DirectBluetoothManager(
            context = context,
            onDevicesChanged = {
                devices.clear()
                devices.addAll(it)
            },
            onScanningChanged = {
                scanning = it
                if (it) {
                    searched = true
                    session.searchFailed.value = false
                }
            },
            onConnectionChanged = { state, detail ->
                // The token is a lead for whoever reads the log; the card says what it means.
                if (state == DirectConnectionState.ERROR) Log.w(DIRECT_BLUETOOTH_LOG_TAG, "Connection failed: $detail")
                connection = state
                // Only a transition carries a device name; an error's detail is a protocol token,
                // and taking it as the name attributes a saved measurement to "bond_timeout".
                if (state != DirectConnectionState.ERROR && detail != null) connectionName = detail
                connectionDetail = detail
                if (state == DirectConnectionState.CONNECTING) {
                    scaleMeasurement = null
                    scaleSourceId = null
                    fitnessMeasurement = null
                    heartRateMeasurement = null
                    session.rrIntervalsMs.clear()
                    runningMeasurement = null
                    cyclingMeasurement = null
                    clinicalMeasurement = null
                    session.storedClinical.value = emptyList()
                    session.storedWeighings.value = emptyList()
                    colmiHistory = null
                    bangleSync = null
                    glucoseRecords.clear()
                    glucoseSyncedCount = null
                    glucoseUnreadable = 0
                    deviceMetadata = DirectDeviceMetadata()
                    workoutStartedAt = null
                    session.runningStartMeters = null
                    session.workoutFinished = false
                }
            },
            onScaleMeasurement = { measurement, sourceId ->
                // A rebroadcast of the weighing just saved must not resurrect it as unsaved.
                if (!session.isAlreadySavedWeighing(measurement, sourceId)) {
                    val pending = (clinicalMeasurement as? DirectProfileMeasurement.BodyComposition)?.value
                    val folded = foldWeighingIntoComposition(measurement, pending)
                    if (folded != null) {
                        val shown = DirectProfileMeasurement.BodyComposition(folded)
                        session.rememberClinical(shown)
                        clinicalMeasurement = shown
                    } else {
                        // Only the scale this page is connected to hands over a memory. One that
                        // broadcasts sends the weighing somebody is standing on, and the next
                        // person's must not be saved beside it.
                        val stored = sourceId != null && sourceId == connectionSourceId &&
                            isFromDeviceMemory(measurement.deviceTime(), System.currentTimeMillis())
                        session.storedWeighings.value = storedReadingsAfter(
                            session.storedWeighings.value.takeIf { sourceId == scaleSourceId }.orEmpty(),
                            measurement,
                            stored,
                            ::isSameWeighing,
                        )
                        scaleMeasurement = measurement
                        scaleSourceId = sourceId
                    }
                }
            },
            onFitnessMeasurement = {
                if (!session.workoutFinished) {
                    fitnessMeasurement = it
                    if (workoutStartedAt == null) workoutStartedAt = System.currentTimeMillis()
                }
            },
            onHeartRate = {
                heartRateMeasurement = it
                // Appended rather than replaced: see DirectBluetoothSession.rrIntervalsMs. Capped
                // so a strap left connected for an afternoon cannot grow the list without end —
                // and the cap drops the oldest, because what variability is asked about is the
                // stretch of beats the person is sitting through now.
                session.rrIntervalsMs.addAll(it.rrIntervalsMs)
                while (session.rrIntervalsMs.size > MAX_RR_INTERVALS) session.rrIntervalsMs.removeAt(0)
            },
            onRunning = {
                if (!session.workoutFinished) {
                    // The pod's distance is its odometer; the card, the note and the saved workout
                    // all take what it has gained since the first packet.
                    if (session.runningStartMeters == null) session.runningStartMeters = it.totalDistanceMeters
                    runningMeasurement = it.sinceStart(session.runningStartMeters)
                    if (workoutStartedAt == null) workoutStartedAt = System.currentTimeMillis()
                }
            },
            onMetadataChanged = { deviceMetadata = it },
            onProfileMeasurement = { measurement ->
                when (measurement) {
                    is DirectProfileMeasurement.Cycling -> if (!session.workoutFinished) {
                        cyclingMeasurement = measurement.value
                        if (workoutStartedAt == null) {
                            workoutStartedAt = System.currentTimeMillis()
                        }
                    }

                    is DirectProfileMeasurement.ColmiHistory -> colmiHistory = measurement.value

                    is DirectProfileMeasurement.Glucose -> {
                        glucoseRecords.removeAll {
                            it.measurement.sequenceNumber == measurement.value.measurement.sequenceNumber
                        }
                        glucoseRecords.add(measurement.value)
                    }

                    is DirectProfileMeasurement.BodyComposition -> {
                        val weighing = scaleMeasurement
                        val folded = foldWeighingIntoComposition(weighing, measurement.value)
                        if (weighing != null && folded != null) {
                            // The weighing now rides with the composition, out of memory or not.
                            session.storedWeighings.value =
                                session.storedWeighings.value.filterNot { isSameWeighing(it, weighing) }
                            scaleMeasurement = null
                            scaleSourceId = null
                        }
                        val shown = folded?.let(DirectProfileMeasurement::BodyComposition) ?: measurement
                        session.rememberClinical(shown)
                        clinicalMeasurement = shown
                    }

                    else -> {
                        session.rememberClinical(measurement)
                        clinicalMeasurement = measurement
                    }
                }
            },
        ).also {
            it.onGlucoseSyncComplete = { count, unreadable ->
                // How many, for whoever reads the log; never what was in them.
                if (unreadable > 0) Log.w(DIRECT_BLUETOOTH_LOG_TAG, "Glucose sync: $unreadable unreadable")
                glucoseSyncedCount = count
                glucoseUnreadable = unreadable
            }
            it.onSearchFailed = { session.searchFailed.value = true }
            it.onBangleSync = { sync -> bangleSync = sync }
            session.manager = it
        }
    }
    // The link ends with the session: not on a rotation, which hands both to the rebuilt section,
    // but on leaving the screen, and on a rebuilt window that did not draw the section again.
    RetainedEffect(manager) { onRetire { manager.close() } }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = permissionsGranted()
        shutOut = !granted && activity != null &&
            BLUETOOTH_PERMISSIONS.none { activity.shouldShowRequestPermissionRationale(it) }
        if (granted) manager.startScan()
    }

    // One row per weighing: Save writes the one on the card and, with it, those the scale sent out
    // of its memory.
    fun weighingEntry(weighing: StandardScaleMeasurement, sourceId: String?) = JournalEntry(
        day = Instant.ofEpochMilli(weighing.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
        occurredAt = weighing.occurredAt,
        kind = KIND_MEASUREMENT,
        title = weightTitle,
        calories = 0.0,
        measurementWeightKg = weighing.weightKg,
        measurementHeightCm = weighing.heightCm,
        measurementSource = "BLUETOOTH_SCALE",
        // The same impedance the note below records, turned into a body-fat estimate.
        bodyFatPercent = weighing.impedanceOhms?.let { ohms ->
            bodyFatPercent(
                impedanceOhms = ohms,
                weightKg = weighing.weightKg,
                heightCm = weighing.heightCm ?: profile.heightCm,
                ageYears = Year.now().value - profile.birthYear,
                male = profile.sex == "MALE",
            )
        },
        // The note opens in the entry editor, so it is written in the person's language rather than
        // as key=value tokens.
        note = directMeasurementNote(
            weighing.sourceModel,
            weighing.impedanceOhms?.let { resources.getString(R.string.direct_bluetooth_impedance, it) },
            // The scale path carries no payload, so the note is where a row dated on arrival says
            // its clock was not believed.
            clockUnsetNote.takeIf { weighing.deviceClockSet == false },
            sourceId,
        ),
    )
    val noMeasurementYet = glucoseRecords.isEmpty() &&
        listOf(
            scaleMeasurement,
            fitnessMeasurement,
            heartRateMeasurement,
            runningMeasurement,
            cyclingMeasurement,
            clinicalMeasurement,
            colmiHistory,
            bangleSync,
        ).all { it == null }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // What the section scans for and which standards it speaks is worth reading once.
            // Printed open, it would be two grey paragraphs on every visit, one of them a row of
            // protocol acronyms.
            DeviceCardTitle(stringResource(R.string.direct_bluetooth_title)) {
                Text(stringResource(R.string.direct_bluetooth_body))
                Text(stringResource(R.string.direct_bluetooth_standards_note))
            }
            when {
                !granted && shutOut -> BluetoothGate(
                    message = stringResource(R.string.device_picker_permission),
                    action = stringResource(R.string.permission_intro_settings),
                    onAction = {
                        context.startSettings(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                )

                !granted -> Button(
                    onClick = { permissionLauncher.launch(BLUETOOTH_PERMISSIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_allow)) }

                // The line says to turn Bluetooth on in Android settings, so the card holds the
                // door to that page, the way the device picker does.
                !manager.bluetoothEnabled -> BluetoothGate(
                    message = stringResource(R.string.direct_bluetooth_disabled),
                    action = stringResource(R.string.device_picker_open_settings),
                    onAction = { context.startSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                )

                // Once a device is connected or a reading waits to be saved, saving is the step in
                // hand: the search steps back to an outlined button and leaves the filled weight to
                // Save and Finish.
                else -> ScanToggle(
                    scanning = scanning,
                    secondary = connection == DirectConnectionState.CONNECTED || !noMeasurementYet,
                    onClick = { if (scanning) manager.stopScan() else manager.startScan() },
                )
            }

            // The scan stops itself after 15 s (startScan posts stopScan on a 15,000 ms delay),
            // so reporting only the searching half would erase the status line the moment the search
            // ends: 15 s of "Looking for…" followed by nothing at all, indistinguishable from a
            // screen where Scan had never been pressed. Both halves are answered here, the way
            // DevicePicker's DeviceList already answers them with the same two strings. The two
            // swap in place — the line neither appears nor moves focus when the search ends — and
            // TalkBack does not speak a content change it was not told to watch, so the live
            // region is what makes the end of the search audible at all. A search that could not
            // start beside an open link is said on the same line: see searchStatusRes.
            searchStatusRes(searched, scanning, devices.isNotEmpty(), session.searchFailed.value)?.let { line ->
                Text(
                    stringResource(line),
                    color = if (line == R.string.m3_sync_error_scan) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // The training machines are chosen from the exercise being logged, where the ride
            // belongs. A Keiser in particular has nothing to connect to, so a row for it here
            // would be a row that does nothing at all.
            devices.filterNot { it.kind == DirectDeviceKind.KEISER_M3I }.forEach { device ->
                DeviceCardDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(device.kind.labelRes()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val hint = when {
                            device.advertisementOnly && device.kind == DirectDeviceKind.SCALE ->
                                R.string.direct_bluetooth_advertisement_hint

                            device.kind == DirectDeviceKind.BANGLE_JS2 -> R.string.direct_bluetooth_banglejs_hint

                            else -> null
                        }
                        hint?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!device.advertisementOnly) {
                        // Every row's button says "Connect"; spoken, it names the device as well,
                        // or a list of found devices is a list of identical buttons.
                        val connectLabel = stringResource(R.string.m3_sync_connect_named, device.name)
                        OutlinedButton(
                            onClick = {
                                connectionKind = device.kind
                                connectionSourceId = directSourceId(context, device.address)
                                // Not connect(): that one refuses while a link is open, and a page
                                // showing a failure beside a live link would then have no way out of it.
                                manager.choose(device)
                            },
                            // The manager holds one link at a time, and choosing another device
                            // drops the open one with whatever it sent and nobody saved. So the
                            // rows wait for Disconnect.
                            enabled = connection != DirectConnectionState.CONNECTING &&
                                connection != DirectConnectionState.CONNECTED,
                            modifier = Modifier.semantics { contentDescription = connectLabel },
                        ) {
                            Text(stringResource(R.string.direct_bluetooth_connect))
                        }
                    }
                }
            }

            // One line follows the connection from start to finish, and it stays one node while its
            // words change: TalkBack speaks a live region's changes, but a line that appears for
            // the first time is not reliably announced, and Connect would start a silent sequence
            // of connecting, waiting, a failure or a hang-up.
            ConnectionStatusLine(
                connection = connection,
                deviceName = connectionName,
                errorDetail = connectionDetail,
                connected = connectedLine(noMeasurementYet, glucoseSyncedCount, glucoseRecords.isNotEmpty()),
                glucoseSynced = glucoseSyncedCount ?: 0,
            )
            if (glucoseUnreadable > 0) {
                // Outside the glucose card, which is not drawn when every record was turned away —
                // and then this line is all there is to say the meter was not empty.
                Text(
                    pluralStringResource(
                        R.plurals.direct_bluetooth_glucose_unreadable,
                        glucoseUnreadable,
                        glucoseUnreadable,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (connection == DirectConnectionState.CONNECTED) {
                // The waiting text goes away once something arrives; the way out of the connection
                // must not. Gating the button on "no measurement yet" would leave a heart-rate strap
                // connected with no way to release it short of leaving the screen.
                OutlinedButton(
                    onClick = manager::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_disconnect)) }
                val identity = listOfNotNull(
                    deviceMetadata.manufacturer,
                    deviceMetadata.model,
                    deviceMetadata.firmwareRevision?.let { stringResource(R.string.m3_sync_firmware, it) },
                ).joinToString(" · ")
                if (identity.isNotBlank()) {
                    Text(identity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                deviceMetadata.batteryPercent?.let {
                    DirectMetric(
                        stringResource(R.string.direct_bluetooth_battery),
                        stringResource(R.string.direct_bluetooth_percent, it),
                    )
                }
            }

            scaleMeasurement?.let { measurement ->
                DeviceCardDivider()
                Text(massText(measurement.weightKg, imperial), style = MaterialTheme.typography.headlineSmall)
                measurement.impedanceOhms?.let {
                    Text(stringResource(R.string.direct_bluetooth_impedance, it))
                }
                Text(
                    measurement.sourceModel ?: connectionName.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReadingDate(measurement.occurredAt)
                val weighings = session.storedWeighings.value
                val earlier = weighings.savedBeside(measurement, ::isSameWeighing, StandardScaleMeasurement::userId)
                EarlierReadings(earlier.size)
                OtherUsersReadings(
                    weighings.leftBeside(measurement, ::isSameWeighing, StandardScaleMeasurement::userId),
                )
                Button(
                    onClick = {
                        val sourceId = scaleSourceId
                        if (session.scaleMeasurement.takeReading() == null) return@Button
                        // Marked as saved inside the tap as well. A scale rebroadcasts the weighing
                        // for many seconds, and the first packet after the card went blank would
                        // put it back, Save and all, before the row below is written.
                        session.lastSavedScaleMeasurement = measurement
                        session.lastSavedScaleSourceId = sourceId
                        session.storedWeighings.value = emptyList()
                        scope.launch {
                            db.saveReadingsNotHeld((earlier + measurement).map { weighingEntry(it, sourceId) })
                            onSaved(scaleSavedText)
                            writeLocalHealthIfAvailable(context, db)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_save_measurement)) }
            }

            if (glucoseRecords.isNotEmpty()) {
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_glucose_title))
                Text(
                    pluralStringResource(
                        R.plurals.direct_bluetooth_glucose_records,
                        glucoseRecords.size,
                        glucoseRecords.size,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The count above is of everything the meter sent; this is how many of them the
                // Save below leaves out. Straight under the count, since its words are "of them".
                val leftOut = glucoseLeftOut(glucoseRecords)
                if (leftOut > 0) {
                    Text(
                        pluralStringResource(R.plurals.direct_bluetooth_glucose_left_out, leftOut, leftOut),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The figure under this heading is read as the person's glucose, so a test of the
                // meter with control solution, or a figure the meter disowned, is never the one
                // printed here.
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_glucose),
                    glucoseRecords.readingsToSave().lastOrNull()?.let { glucoseText(it.measurement) },
                )
                // The batch does not go through ClinicalMeasurementCard, so a transfer with nothing
                // in it to save needs its own word on why Save is grey.
                val blocked = glucoseSaveBlockedReason(glucoseRecords)
                blocked?.let { SaveBlockedReason(it) }
                Button(
                    onClick = {
                        // Taken off the page inside the tap, as a card's reading is (takeReading).
                        // Cleared after the rows and Health Connect, the list would let a second tap run
                        // the batch again: it would write nothing, and say so over the first one's flash.
                        if (glucoseRecords.isEmpty()) return@Button
                        val transfer = glucoseRecords.toList()
                        glucoseRecords.clear()
                        // The meter the records came from, as it is named at the tap.
                        val deviceName = connectionName
                        val sourceId = connectionSourceId
                        scope.launch {
                            val inserted = db.saveGlucoseTransfer(transfer, glucoseTitle, deviceName, sourceId)
                            writeLocalHealthIfAvailable(context, db)
                            onSaved(resources.getString(glucoseSavedRes(inserted)))
                        }
                    },
                    enabled = blocked == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_save_glucose_records)) }
            }

            clinicalMeasurement
                ?.takeUnless {
                    it is DirectProfileMeasurement.Cycling || it is DirectProfileMeasurement.Glucose
                }
                ?.let { measurement ->
                    // What Save writes: the stored readings of the same user, and the one on the
                    // card where it can be saved. The rest of the memory is another user's.
                    val stored = session.storedClinical.value
                    val saved = clinicalReadingsToSave(stored, measurement)
                    val userOf = DirectProfileMeasurement::userId
                    ClinicalMeasurementCard(
                        measurement = measurement,
                        imperial = imperial,
                        earlierReadings = saved.count { it !== measurement },
                        otherUsersReadings = stored.leftBeside(measurement, ::isSameClinicalReading, userOf),
                        onSave = {
                            val entries = saved.mapNotNull { reading ->
                                reading.toJournalEntry(
                                    title = resources.getString(reading.titleResource()),
                                    deviceName = connectionName,
                                    sourceId = connectionSourceId,
                                )
                            }
                            if (entries.isEmpty()) return@ClinicalMeasurementCard
                            if (session.clinicalMeasurement.takeReading() == null) return@ClinicalMeasurementCard
                            session.storedClinical.value = emptyList()
                            scope.launch {
                                db.saveReadingsNotHeld(entries)
                                onSaved(scaleSavedText)
                                writeLocalHealthIfAvailable(context, db)
                            }
                        },
                    )
                }

            colmiHistory?.let { snapshot ->
                val totalSteps = snapshot.samples.sumOf { it.steps.toLong() }
                val totalDistance = snapshot.samples.sumOf { it.distanceMeters.toLong() }
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_colmi_history_title))
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_model),
                    snapshot.model.name,
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_battery),
                    stringResource(R.string.direct_bluetooth_percent, snapshot.battery.percent),
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_steps),
                    totalSteps.toString(),
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_distance),
                    formatDistance(resources, totalDistance.toDouble(), imperial),
                )
                Text(
                    stringResource(R.string.direct_bluetooth_colmi_security),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val label = "COLMI ${snapshot.model.name} · ${connectionName.orEmpty()}"
                        val rows = snapshot.toDirectBleDays(label)
                        val payload = snapshot.toJournalPayload()
                        scope.launch {
                            db.saveDirectBleStepSync(
                                rows = rows,
                                entry = JournalEntry(
                                    day = Instant.ofEpochMilli(snapshot.syncedAt)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                        .toString(),
                                    occurredAt = snapshot.syncedAt,
                                    kind = KIND_MEASUREMENT,
                                    title = colmiHistoryTitle,
                                    calories = 0.0,
                                    measurementSource = "BLUETOOTH_COLMI",
                                    measurementPayloadJson = payload,
                                    note = label,
                                ),
                            )
                            colmiHistory = null
                            onSaved(scaleSavedText)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_save_history)) }
            }

            bangleSync?.let { sync ->
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_banglejs))
                ReadingDate(sync.health.timestampMillis)
                DirectMetric(stringResource(R.string.direct_bluetooth_steps), sync.health.steps.toString())
                DirectMetric(
                    stringResource(R.string.measurement_heart_rate_title),
                    sync.health.heartRateBpm?.let { stringResource(R.string.measurement_heart_rate_value, it) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_battery),
                    stringResource(R.string.direct_bluetooth_percent, sync.health.batteryPercent),
                )
                Button(
                    onClick = {
                        if (session.bangleSync.takeReading() == null) return@Button
                        val day = sync.day.toString()
                        // The watch's total for the day, beside the phone's own count: the day shows
                        // the larger of the two and never their sum.
                        val steps = DirectBleDaySteps(
                            sourceId = sync.sourceId,
                            day = day,
                            steps = sync.health.steps.toLong(),
                            sourceLabel = connectionName,
                            updatedAt = System.currentTimeMillis(),
                        )
                        val heartRate = sync.health.heartRateBpm?.let { bpm ->
                            JournalEntry(
                                day = day,
                                occurredAt = sync.health.timestampMillis,
                                kind = KIND_MEASUREMENT,
                                title = heartRateTitle,
                                calories = 0.0,
                                measurementHeartRateBpm = bpm,
                                measurementSource = "BLUETOOTH_BANGLEJS",
                                note = directMeasurementNote(connectionName, sync.sourceId),
                            )
                        }
                        scope.launch {
                            if (heartRate != null) {
                                db.saveDirectBleStepSync(listOf(steps), heartRate)
                            } else {
                                db.dao().putDirectBleDays(listOf(steps))
                            }
                            onSaved(scaleSavedText)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_save_measurement)) }
            }

            heartRateMeasurement?.let { measurement ->
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_heart_rate))
                DirectMetric(
                    stringResource(R.string.measurement_heart_rate_title),
                    stringResource(R.string.measurement_heart_rate_value, measurement.bpm),
                )
                measurement.rrIntervalsMs.lastOrNull()?.let { rr ->
                    DirectMetric(stringResource(R.string.direct_bluetooth_rr), valueWithUnit("$rr", R.string.unit_ms))
                }
                // The figure the beats add up to, once there are enough of them to add up to one.
                val rmssd = rmssdMs(session.rrIntervalsMs)
                if (rmssd != null) {
                    DirectMetric(
                        stringResource(R.string.direct_bluetooth_hrv),
                        valueWithUnit("${rmssd.roundToInt()}", R.string.unit_ms) + " · " +
                            pluralStringResource(
                                R.plurals.direct_bluetooth_hrv_samples,
                                session.rrIntervalsMs.size,
                                session.rrIntervalsMs.size,
                            ),
                    )
                }
                // The pulse is still shown while the strap reports no contact, so the grey Save
                // beneath it needs its reason beside it.
                if (measurement.contact == false) SaveBlockedReason(R.string.m3_sync_no_skin_contact)
                Button(
                    onClick = {
                        // One pulse per tap: see heartRateSavedAt.
                        val now = System.currentTimeMillis()
                        if (isDuplicateTap(session.heartRateSavedAt, now)) return@Button
                        session.heartRateSavedAt = now
                        scope.launch {
                            db.saveJournalEvent(
                                JournalEntry(
                                    day = LocalDate.now().toString(),
                                    occurredAt = now,
                                    kind = KIND_MEASUREMENT,
                                    title = heartRateTitle,
                                    calories = 0.0,
                                    measurementHeartRateBpm = measurement.bpm,
                                    // The variability of the run of beats this pulse came out of,
                                    // written beside it rather than in a row of its own: they are
                                    // one measurement, taken by one strap, over one sitting.
                                    measurementPayloadJson = rmssd?.let {
                                        JSONObject()
                                            .put("schema", 1)
                                            .put(Vital.HRV.key, it)
                                            .toString()
                                    },
                                    measurementSource = "BLUETOOTH_STRAP",
                                    note = directMeasurementNote(connectionName, connectionSourceId),
                                ),
                            )
                            onSaved(scaleSavedText)
                        }
                    },
                    enabled = measurement.bpm > 0 && measurement.contact != false,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_save_measurement)) }
            }

            runningMeasurement?.let { measurement ->
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_running_pod))
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_speed),
                    speedText(measurement.speedKph, imperial),
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_cadence),
                    stringResource(R.string.direct_bluetooth_spm, measurement.cadenceSpm),
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_stride),
                    measurement.strideLengthCm?.let { stringResource(R.string.unit_cm_value, it) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_distance),
                    measurement.totalDistanceMeters?.let { formatDistance(resources, it, imperial) },
                )
                Button(
                    onClick = {
                        if (session.runningMeasurement.takeReading() == null) return@Button
                        val now = System.currentTimeMillis()
                        val started = workoutStartedAt ?: now
                        val minutes = ((now - started) / 60_000.0).coerceAtLeast(1.0)
                        // The pod itself says whether this is a walk or a run; the MET follows.
                        val met = if (measurement.running) RUNNING_MET else WALKING_MET
                        session.finishWorkout()
                        scope.launch {
                            db.saveJournalEvent(
                                JournalEntry(
                                    day = Instant.ofEpochMilli(started)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                        .toString(),
                                    occurredAt = started,
                                    kind = KIND_ACTIVITY,
                                    title = workoutTitle,
                                    calories = netActivityCalories(met, profile.restingKcalPerHour(), minutes),
                                    activityCode = "BLUETOOTH_RSCS",
                                    met = met,
                                    durationMinutes = minutes,
                                    measurementPayloadJson = runningPayload(measurement),
                                    // Distance rides in the note, as on the FTMS path: nothing
                                    // reads it as a figure, so it has no column of its own.
                                    note = buildString {
                                        append("Bluetooth RSCS · ${connectionName.orEmpty()}")
                                        // In the person's unit and language: the note opens in
                                        // the entry editor.
                                        measurement.totalDistanceMeters?.let {
                                            append(" · ${formatDistance(resources, it, imperial)}")
                                        }
                                    },
                                ),
                            )
                            onSaved(workoutSavedText)
                            writeLocalHealthIfAvailable(context, db)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_finish_workout)) }
            }

            cyclingMeasurement?.let { measurement ->
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_cycling_live))
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_power),
                    measurement.powerWatts?.let { stringResource(R.string.machine_power_value, it) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_cadence),
                    measurement.cadenceRpm?.let { stringResource(R.string.machine_cadence_rpm, it.roundToInt()) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_wheel_revolutions),
                    measurement.cumulativeWheelRevolutions?.toString(),
                )
                Button(
                    onClick = {
                        if (session.cyclingMeasurement.takeReading() == null) return@Button
                        val now = System.currentTimeMillis()
                        val started = workoutStartedAt ?: now
                        val minutes = ((now - started) / 60_000.0).coerceAtLeast(1.0)
                        session.finishWorkout()
                        scope.launch {
                            db.saveJournalEvent(
                                JournalEntry(
                                    day = Instant.ofEpochMilli(started)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                        .toString(),
                                    occurredAt = started,
                                    kind = KIND_ACTIVITY,
                                    title = cyclingWorkoutTitle,
                                    calories = netActivityCalories(
                                        CYCLING_MET,
                                        profile.restingKcalPerHour(),
                                        minutes,
                                    ),
                                    activityCode = "BLUETOOTH_CSC_CPS",
                                    met = CYCLING_MET,
                                    durationMinutes = minutes,
                                    measurementPayloadJson = cyclingPayload(measurement),
                                    note = "Bluetooth CSC/CPS · ${connectionName.orEmpty()}",
                                ),
                            )
                            onSaved(workoutSavedText)
                            writeLocalHealthIfAvailable(context, db)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_finish_workout)) }
            }

            fitnessMeasurement?.let { measurement ->
                DeviceCardDivider()
                MeasurementHeading(stringResource(R.string.direct_bluetooth_live_workout))
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_speed),
                    measurement.speedKph?.let { speedText(it, imperial) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_distance),
                    measurement.totalDistanceMeters?.let { formatDistance(resources, it.toDouble(), imperial) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_power),
                    measurement.powerWatts?.let { stringResource(R.string.machine_power_value, it) },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_cadence),
                    measurement.cadenceRpm?.let { stringResource(R.string.machine_cadence_rpm, it.roundToInt()) }
                        ?: measurement.strokeRateSpm?.let {
                            stringResource(R.string.direct_bluetooth_spm, it.roundToInt())
                        },
                )
                DirectMetric(
                    stringResource(R.string.heart_rate),
                    measurement.heartRateBpm?.let {
                        stringResource(R.string.measurement_heart_rate_value, it)
                    },
                )
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_energy),
                    measurement.totalEnergyKcal?.let { valueWithUnit("$it", R.string.unit_kcal) },
                )
                DirectMetric(
                    stringResource(R.string.editor_time),
                    measurement.elapsedSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) },
                )
                Button(
                    onClick = {
                        if (session.fitnessMeasurement.takeReading() == null) return@Button
                        val now = System.currentTimeMillis()
                        val elapsedSeconds = measurement.elapsedSeconds?.takeIf { it > 0 }
                        val seconds = elapsedSeconds?.toLong()
                            ?: ((now - (workoutStartedAt ?: now)) / 1_000).coerceAtLeast(60)
                        val startedAt = elapsedSeconds
                            ?.let { now - it.toLong() * 1_000L }
                            ?: workoutStartedAt
                            ?: now
                        val minutes = seconds / 60.0
                        val calories = measurement.totalEnergyKcal?.toDouble()
                            ?: netActivityCalories(5.0, profile.restingKcalPerHour(), minutes)
                        val concept2 = connectionKind == DirectDeviceKind.CONCEPT2_PM5
                        val source = if (concept2) "BLUETOOTH_CONCEPT2_PM5" else "BLUETOOTH_FTMS"
                        val protocol = if (concept2) "Concept2 PM5" else "Bluetooth SIG FTMS"
                        session.finishWorkout()
                        scope.launch {
                            db.saveJournalEvent(
                                JournalEntry(
                                    day = Instant.ofEpochMilli(startedAt)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                        .toString(),
                                    occurredAt = startedAt,
                                    kind = KIND_ACTIVITY,
                                    title = workoutTitle,
                                    calories = calories,
                                    activityCode = source,
                                    met = 5.0,
                                    durationMinutes = minutes,
                                    measurementPayloadJson = fitnessPayload(measurement, protocol),
                                    note = "$protocol · ${connectionName.orEmpty()}",
                                ),
                            )
                            measurement.heartRateBpm?.takeIf { it in 30..250 }?.let { bpm ->
                                db.saveJournalEvent(
                                    JournalEntry(
                                        day = LocalDate.now().toString(),
                                        occurredAt = now,
                                        kind = KIND_MEASUREMENT,
                                        title = heartRateTitle,
                                        calories = 0.0,
                                        measurementHeartRateBpm = bpm,
                                        measurementSource = source,
                                        note = directMeasurementNote(
                                            protocol,
                                            connectionName,
                                            connectionSourceId,
                                        ),
                                    ),
                                )
                            }
                            onSaved(workoutSavedText)
                            writeLocalHealthIfAvailable(context, db)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.direct_bluetooth_finish_workout)) }
            }
        }
    }
}

/** Walking and running METs from the compendium, used when the pod reports no energy of its own. */
private const val WALKING_MET = 3.5
private const val RUNNING_MET = 8.3
private const val CYCLING_MET = 7.5

private fun ColmiHistorySnapshot.toDirectBleDays(sourceLabel: String): List<DirectBleDaySteps> =
    samples
        .groupBy { it.startedAtLocal.toLocalDate() }
        .map { (day, samples) ->
            DirectBleDaySteps(
                sourceId = sourceId,
                day = day.toString(),
                steps = samples.sumOf { it.steps.toLong() },
                sourceLabel = sourceLabel,
                updatedAt = syncedAt,
            )
        }

private fun ColmiHistorySnapshot.toJournalPayload(): String = JSONObject()
    .put("schema", 1)
    .put("profile", "COLMI R02-family clean-room")
    .put("model", model.name)
    .put("sourceId", sourceId)
    .put("batteryPercent", battery.percent)
    .put("charging", battery.charging)
    .put(
        "samples",
        JSONArray().apply {
            samples.forEach { sample ->
                put(
                    JSONObject()
                        .put("startedAtLocal", sample.startedAtLocal.toString())
                        .put("intervalIndex", sample.intervalIndex)
                        .put("steps", sample.steps)
                        .put("distanceMeters", sample.distanceMeters)
                        .put("calorieCounterRaw", sample.calorieCounterRaw)
                        .put("newCalorieProtocol", sample.newCalorieProtocol),
                )
            }
        },
    )
    .toString()

private fun runningPayload(measurement: RunningMeasurement): String = JSONObject()
    .put("schema", 1)
    .put("profile", "Bluetooth SIG RSCS")
    .put("speedMps", measurement.speedMps)
    .put("cadenceSpm", measurement.cadenceSpm)
    .put("strideLengthCm", measurement.strideLengthCm)
    .put("totalDistanceMeters", measurement.totalDistanceMeters)
    .put("running", measurement.running)
    .toString()

internal fun fitnessPayload(
    measurement: FitnessMachineMeasurement,
    protocol: String,
): String = JSONObject()
    .put("schema", 1)
    .put("profile", protocol)
    .put("machineType", measurement.type.name)
    .put("speedKph", measurement.speedKph)
    .put("cadenceRpm", measurement.cadenceRpm)
    .put("strokeRateSpm", measurement.strokeRateSpm)
    .put("strokeCount", measurement.strokeCount)
    .put("totalDistanceMeters", measurement.totalDistanceMeters)
    .put("inclinationPercent", measurement.inclinationPercent)
    .put("resistanceLevel", measurement.resistanceLevel)
    .put("powerWatts", measurement.powerWatts)
    .put("totalEnergyKcal", measurement.totalEnergyKcal)
    .put("heartRateBpm", measurement.heartRateBpm)
    .put("elapsedSeconds", measurement.elapsedSeconds)
    .toString()

private fun cyclingPayload(measurement: CyclingLiveMeasurement): String = JSONObject()
    .put("schema", 1)
    .put("profile", "Bluetooth SIG CSC/CPS")
    .put("powerWatts", measurement.powerWatts)
    .put("cadenceRpm", measurement.cadenceRpm)
    .put("cumulativeWheelRevolutions", measurement.cumulativeWheelRevolutions)
    .put("cumulativeCrankRevolutions", measurement.cumulativeCrankRevolutions)
    .toString()

private fun DirectProfileMeasurement.titleResource(): Int = when (this) {
    is DirectProfileMeasurement.BloodPressure -> R.string.direct_bluetooth_blood_pressure_title
    is DirectProfileMeasurement.PulseOximeter -> R.string.direct_bluetooth_pulse_oximeter_title
    is DirectProfileMeasurement.Temperature -> R.string.direct_bluetooth_temperature_title
    is DirectProfileMeasurement.BodyComposition -> R.string.direct_bluetooth_body_composition_title
    is DirectProfileMeasurement.Glucose -> R.string.direct_bluetooth_glucose_title
    is DirectProfileMeasurement.Cycling -> R.string.direct_bluetooth_cycling_workout_title
    is DirectProfileMeasurement.ColmiHistory -> R.string.direct_bluetooth_colmi_history_title
}

@Composable
private fun ClinicalMeasurementCard(
    measurement: DirectProfileMeasurement,
    imperial: Boolean,
    earlierReadings: Int,
    otherUsersReadings: Int,
    onSave: () -> Unit,
) {
    DeviceCardDivider()
    MeasurementHeading(stringResource(measurement.titleResource()))
    measurement.occurredAt()?.let { ReadingDate(it) }
    when (measurement) {
        is DirectProfileMeasurement.BloodPressure -> {
            DirectMetric(
                stringResource(R.string.direct_bluetooth_systolic),
                measurement.value.systolicMmhg?.let { pressureText(it) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_diastolic),
                measurement.value.diastolicMmhg?.let { pressureText(it) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_mean_pressure),
                measurement.value.meanArterialPressureMmhg?.let { pressureText(it) },
            )
            DirectMetric(
                stringResource(R.string.heart_rate),
                measurement.value.pulseRateBpm?.let {
                    stringResource(R.string.measurement_heart_rate_value, it.roundToInt())
                },
            )
        }

        is DirectProfileMeasurement.PulseOximeter -> {
            DirectMetric(
                stringResource(R.string.direct_bluetooth_spo2),
                measurement.value.spo2Percent?.let { percentText(it) },
            )
            DirectMetric(
                stringResource(R.string.heart_rate),
                measurement.value.pulseRateBpm?.let {
                    stringResource(R.string.measurement_heart_rate_value, it.roundToInt())
                },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_perfusion_index),
                measurement.value.pulseAmplitudeIndexPercent?.let { percentText(it) },
            )
        }

        // Only the printing moves to Fahrenheit: see temperatureValue for what stays Celsius.
        is DirectProfileMeasurement.Temperature -> DirectMetric(
            stringResource(R.string.direct_bluetooth_temperature),
            measurement.value.temperatureCelsius?.let {
                valueWithUnit(fixed(temperatureValue(it, imperial), 2), temperatureUnitRes(imperial))
            },
        )

        is DirectProfileMeasurement.BodyComposition -> {
            DirectMetric(
                stringResource(R.string.measurement_weight_title),
                measurement.value.weightKg?.let { massText(it, imperial) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_body_fat),
                measurement.value.bodyFatPercent?.let { percentText(it) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_muscle),
                measurement.value.muscleMassKg?.let { massText(it, imperial) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_body_water),
                measurement.value.bodyWaterMassKg?.let { massText(it, imperial) },
            )
            DirectMetric(
                stringResource(R.string.direct_bluetooth_impedance_label),
                measurement.value.impedanceOhms?.let { valueWithUnit(fixed(it, 1), R.string.unit_ohm) },
            )
        }

        is DirectProfileMeasurement.Glucose -> {
            DirectMetric(stringResource(R.string.direct_bluetooth_glucose), glucoseText(measurement.value.measurement))
            measurement.value.context?.hba1cPercent?.let {
                DirectMetric(
                    stringResource(R.string.direct_bluetooth_hba1c),
                    percentText(it),
                )
            }
        }

        is DirectProfileMeasurement.Cycling -> Unit

        is DirectProfileMeasurement.ColmiHistory -> Unit
    }
    // Why this reading cannot be saved comes first, so that the line under it — the earlier ones
    // still can — is read as the answer to it.
    val saveable = measurement.canSaveClinicalMeasurement()
    measurement.saveBlockedReason()?.let { SaveBlockedReason(it) }
    EarlierReadings(earlierReadings, withShown = saveable)
    OtherUsersReadings(otherUsersReadings)
    Button(
        onClick = onSave,
        enabled = saveable || earlierReadings > 0,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.direct_bluetooth_save_measurement)) }
}

/**
 * Why Save is grey, for the line above it: the device disowning its number, a composition the
 * scale reported as failed, or a reading that arrived without the figure Save needs. Without it
 * the button would be grey with nothing to say why.
 */
@StringRes
private fun DirectProfileMeasurement.saveBlockedReason(): Int? = when {
    this is DirectProfileMeasurement.Cycling || this is DirectProfileMeasurement.ColmiHistory -> null
    flaggedInvalid() -> R.string.direct_bluetooth_reading_invalid
    this is DirectProfileMeasurement.BodyComposition && !value.successful -> R.string.direct_bluetooth_reading_invalid
    !canSaveClinicalMeasurement() -> R.string.m3_sync_reading_incomplete
    else -> null
}

private fun DirectProfileMeasurement.canSaveClinicalMeasurement(): Boolean = when (this) {
    is DirectProfileMeasurement.BloodPressure ->
        value.systolicMmhg != null && value.diastolicMmhg != null

    is DirectProfileMeasurement.PulseOximeter -> value.spo2Percent != null && !value.flaggedInvalid

    is DirectProfileMeasurement.Temperature -> value.temperatureCelsius != null

    is DirectProfileMeasurement.BodyComposition -> value.successful

    is DirectProfileMeasurement.Glucose ->
        value.measurement.concentration != null && !value.measurement.flaggedInvalid

    is DirectProfileMeasurement.Cycling -> false

    is DirectProfileMeasurement.ColmiHistory -> false
}

/**
 * The device's own word that its number is not one, from the status bits, so a reading the oximeter
 * or the meter has declared invalid does not come with a live Save button.
 * The soft blood-pressure flags are not here: a moved arm does not make the pressure wrong, and
 * they travel in the payload instead.
 */
private fun DirectProfileMeasurement.flaggedInvalid(): Boolean = when (this) {
    is DirectProfileMeasurement.PulseOximeter -> value.flaggedInvalid
    is DirectProfileMeasurement.Glucose -> value.measurement.flaggedInvalid
    else -> false
}

@Suppress("LongMethod")
private fun DirectProfileMeasurement.toJournalEntry(
    title: String,
    deviceName: String?,
    sourceId: String?,
): JournalEntry? {
    val occurredAt = occurredAt() ?: return null
    val payload = JSONObject().put("schema", 1)
    val source: String
    var weightKg: Double? = null
    var heightCm: Int? = null
    var heartRateBpm: Int? = null
    var bodyFatPercent: Double? = null

    when (this) {
        is DirectProfileMeasurement.BloodPressure -> {
            source = "BLUETOOTH_BPS"
            heartRateBpm = value.pulseRateBpm?.roundToInt()
            payload.put("profile", "Bluetooth SIG BPS")
                .put("sourceUnit", value.unit.name)
                .put("systolic", value.systolic)
                .put("diastolic", value.diastolic)
                .put("meanArterialPressure", value.meanArterialPressure)
                .put("systolicMmhg", value.systolicMmhg)
                .put("diastolicMmhg", value.diastolicMmhg)
                .put("meanArterialPressureMmhg", value.meanArterialPressureMmhg)
                .put("pulseRateBpm", value.pulseRateBpm)
                .put("userId", value.userId)
                .put("measurementStatus", value.measurementStatus)
                .put("deviceClockSet", value.deviceClockSet)
                .put("qualityFlags", value.qualityFlags.takeIf { it.isNotEmpty() }?.let(::JSONArray))
        }

        is DirectProfileMeasurement.PulseOximeter -> {
            source = "BLUETOOTH_PLXS"
            heartRateBpm = value.pulseRateBpm?.roundToInt()
            payload.put("profile", "Bluetooth SIG PLXS")
                .put("spo2Percent", value.spo2Percent)
                .put("pulseRateBpm", value.pulseRateBpm)
                .put("fastSpo2Percent", value.fastSpo2Percent)
                .put("fastPulseRateBpm", value.fastPulseRateBpm)
                .put("slowSpo2Percent", value.slowSpo2Percent)
                .put("slowPulseRateBpm", value.slowPulseRateBpm)
                .put("pulseAmplitudeIndexPercent", value.pulseAmplitudeIndexPercent)
                .put("measurementStatus", value.measurementStatus)
                .put("deviceAndSensorStatus", value.deviceAndSensorStatus)
                .put("spotCheck", value.isSpotCheck)
                .put("deviceClockSet", value.deviceClockSet)
        }

        is DirectProfileMeasurement.Temperature -> {
            source = "BLUETOOTH_HTS"
            payload.put("profile", "Bluetooth SIG HTS")
                .put("temperatureCelsius", value.temperatureCelsius)
                .put("sourceUnit", value.sourceUnit.name)
                .put("type", value.type?.name)
                .put("deviceClockSet", value.deviceClockSet)
        }

        is DirectProfileMeasurement.BodyComposition -> {
            source = "BLUETOOTH_BCS"
            weightKg = value.weightKg
            heightCm = value.heightCm?.roundToInt()
            bodyFatPercent = value.bodyFatPercent
            payload.put("profile", "Bluetooth SIG BCS")
                .put("bodyFatPercent", value.bodyFatPercent)
                .put("timestamp", value.timestamp?.toString())
                .put("basalMetabolismKj", value.basalMetabolismKj)
                .put("musclePercent", value.musclePercent)
                .put("muscleMassKg", value.muscleMassKg)
                .put("fatFreeMassKg", value.fatFreeMassKg)
                .put("softLeanMassKg", value.softLeanMassKg)
                .put("bodyWaterMassKg", value.bodyWaterMassKg)
                .put("impedanceOhms", value.impedanceOhms)
                .put("weightKg", value.weightKg)
                .put("heightCm", value.heightCm)
                .put("userId", value.userId)
                .put("userIdPresent", value.userIdPresent)
                .put("imperialSource", value.imperialSource)
                .put("deviceClockSet", value.deviceClockSet)
        }

        is DirectProfileMeasurement.Glucose -> {
            source = "BLUETOOTH_GLS"
            val glucose = value.measurement
            payload.put("profile", "Bluetooth SIG GLS")
                .put("sequenceNumber", glucose.sequenceNumber)
                .put("baseTime", glucose.baseTime.toString())
                .put("timeOffsetMinutes", glucose.timeOffsetMinutes)
                .put("concentration", glucose.concentration)
                .put("concentrationUnit", glucose.concentrationUnit?.name)
                .put("concentrationMgDl", glucose.concentrationMgDl)
                .put("concentrationMmolL", glucose.concentrationMmolL)
                .put("type", glucose.type)
                .put("sampleLocation", glucose.sampleLocation)
                .put("sensorStatus", glucose.sensorStatus)
            // Only when false: this payload is the record's identity across syncs, and a key that
            // every record carried would make each one look new to the next transfer. The verdict
            // itself can differ from one transfer to the next; journalPayload is what covers that.
            if (!glucose.deviceClockSet) payload.put("deviceClockSet", false)
            value.context?.let { payload.put("context", it.toJournalPayload()) }
        }

        is DirectProfileMeasurement.Cycling -> return null

        is DirectProfileMeasurement.ColmiHistory -> return null
    }

    return JournalEntry(
        day = Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
        occurredAt = occurredAt,
        kind = KIND_MEASUREMENT,
        title = title,
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementHeightCm = heightCm,
        measurementHeartRateBpm = heartRateBpm,
        measurementSource = source,
        measurementPayloadJson = payload.toString(),
        bodyFatPercent = bodyFatPercent,
        // The identity stays out of the payload on purpose: saveDirectBluetoothMeasurements treats
        // the payload as the record's identity when it decides whether a glucose record has
        // already been saved, and a new field in it would re-insert every record already saved.
        note = directMeasurementNote(deviceName, sourceId),
    )
}

private fun GlucoseMeasurementContext.toJournalPayload(): JSONObject = JSONObject()
    .put("sequenceNumber", sequenceNumber)
    .put("extendedFlagsPresent", extendedFlagsPresent)
    .put("carbohydrateId", carbohydrateId)
    .put("carbohydrateKg", carbohydrateKg)
    .put("meal", meal)
    .put("tester", tester)
    .put("health", health)
    .put("exerciseDurationSeconds", exerciseDurationSeconds)
    .put("exerciseIntensityPercent", exerciseIntensityPercent)
    .put("medicationId", medicationId)
    .put("medication", medication)
    .put("medicationUnit", medicationUnit?.name)
    .put("hba1cPercent", hba1cPercent)

/**
 * The payload this record is saved under when its clock is, or is not, believed.
 *
 * The verdict is taken against the phone's clock at the moment of the transfer, and it is written
 * into the payload — the string the batch save knows a record by. A meter running seven minutes
 * fast has its newest reading disbelieved by a sync a minute after the test and believed by every
 * later one, and the record would then go into the journal twice: under two payloads, and dated twice.
 * The record is one record under either string, so whoever saves it asks for both.
 */
internal fun GlucoseRecord.journalPayload(clockBelieved: Boolean): String? =
    DirectProfileMeasurement.Glucose(copy(measurement = measurement.copy(deviceClockSet = clockBelieved)))
        .toJournalEntry(title = "", deviceName = null, sourceId = null)
        ?.measurementPayloadJson

/**
 * Saves a meter's transfer and answers how many rows it wrote. A record the meter itself disowned,
 * one without a figure and a control-solution test are left out ([readingsToSave]). Every sync asks
 * for every record the meter holds, so most of a transfer is saved already: the batch save knows a
 * record by the payload it arrives under today, and one saved while its clock was judged the other
 * way stands under the other payload ([journalPayload]) and is a record already saved as well.
 */
internal suspend fun StepsDb.saveGlucoseTransfer(
    records: List<GlucoseRecord>,
    title: String,
    deviceName: String?,
    sourceId: String?,
): Int {
    val dao = dao()
    val unsaved = records.readingsToSave().mapNotNull { record ->
        DirectProfileMeasurement.Glucose(record).toJournalEntry(title, deviceName, sourceId)?.takeUnless { entry ->
            val source = entry.measurementSource
            val underOtherVerdict = record.journalPayload(clockBelieved = !record.measurement.deviceClockSet)
            source != null && underOtherVerdict != null && dao.hasDirectMeasurement(source, underOtherVerdict)
        }
    }
    return saveDirectBluetoothMeasurements(unsaved)
}

private fun DirectProfileMeasurement.occurredAt(): Long? = when (this) {
    is DirectProfileMeasurement.BloodPressure -> value.occurredAt

    is DirectProfileMeasurement.PulseOximeter -> value.occurredAt

    is DirectProfileMeasurement.Temperature -> value.occurredAt

    is DirectProfileMeasurement.BodyComposition ->
        value.timestamp
            ?.atZone(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
            ?: System.currentTimeMillis()

    is DirectProfileMeasurement.Glucose ->
        if (value.measurement.deviceClockSet) {
            value.measurement.userFacingTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } else {
            System.currentTimeMillis()
        }

    is DirectProfileMeasurement.Cycling -> null

    is DirectProfileMeasurement.ColmiHistory -> null
}

/**
 * One weighing, one card. A scale that speaks both services indicates the weight twice: once on
 * its own through WSS and once inside the BCS measurement — or not at all there, when the
 * composition packet leaves its weight flag clear. Two cards for one weighing would mean two weight
 * rows and two WeightRecords when both are saved, because the rebroadcast guard knows only the
 * scale path.
 *
 * Returns the composition the weight now rides with, and null when the two are not one weighing:
 * no scale weight pending, weights that disagree (someone else stepped on), or a composition that
 * failed — that one cannot be saved, so the plain weight keeps its own card and its own Save.
 */
internal fun foldWeighingIntoComposition(
    scale: StandardScaleMeasurement?,
    composition: BodyCompositionMeasurement?,
): BodyCompositionMeasurement? {
    if (scale == null || composition == null || !composition.successful) return null
    val own = composition.weightKg
    if (own != null && abs(own - scale.weightKg) > SAME_WEIGHING_TOLERANCE_KG) return null
    return composition.copy(
        weightKg = own ?: scale.weightKg,
        heightCm = composition.heightCm ?: scale.heightCm?.toDouble(),
    )
}

/** Both services resolve weight to 5 g; two readings of one weighing agree to far less than this. */
private const val SAME_WEIGHING_TOLERANCE_KG = 0.05

/**
 * Whether [measurement] is the scale repeating [saved] rather than a new weighing. A scale that
 * dated the weighing itself repeats that date exactly on every rebroadcast, so the date is its
 * identity. A scale that sent no time, or one this app declined to believe, has the weighing dated
 * on arrival — separately for every frame, so no two rebroadcasts agree — and comparing those dates
 * would let the weight just saved come back as an unsaved card a few hundred milliseconds after Save.
 * For such a scale the weight inside a short window is the identity. The impedance is not part of
 * it: a Mi Scale advertises the stable weight before it has measured one.
 */
internal fun isSameWeighing(saved: StandardScaleMeasurement, measurement: StandardScaleMeasurement): Boolean {
    if (measurement.weightKg != saved.weightKg) return false
    val deviceDated = saved.deviceClockSet == true && measurement.deviceClockSet == true
    return if (deviceDated) {
        measurement.occurredAt == saved.occurredAt
    } else {
        abs(measurement.occurredAt - saved.occurredAt) <= SCALE_REBROADCAST_WINDOW_MS
    }
}

/**
 * A Mi Scale advertises the stable weight several times a second for as long as someone stands on
 * it. The same weight again inside five minutes is that, not a second weighing worth a second row.
 */
private const val SCALE_REBROADCAST_WINDOW_MS = 5L * 60 * 1_000

/**
 * A label and its value, read by a screen reader as one item.
 *
 * A flowing row rather than a plain one: in a plain row the label is measured first against the
 * whole width, so a label that wraps at a large font or in a long translation leaves the value a
 * sliver, and it breaks a glyph to a line. Here the value takes what the label leaves on its line,
 * aligned to the end, and drops to a line of its own when not even its longest word fits.
 */
@Composable
internal fun DirectMetric(label: String, value: String?) {
    if (value == null) return
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/**
 * The one line that follows a connection. Its words change in place and the node stays, so the
 * live region speaks every change; a failure interrupts, everything else waits its turn.
 */
@Composable
private fun ConnectionStatusLine(
    connection: DirectConnectionState,
    deviceName: String?,
    errorDetail: String?,
    connected: ConnectedLine,
    glucoseSynced: Int,
) {
    val line = when (connection) {
        DirectConnectionState.IDLE -> null

        DirectConnectionState.CONNECTING ->
            stringResource(R.string.direct_bluetooth_connecting, deviceName.orEmpty())

        DirectConnectionState.CONNECTED -> when (connected) {
            ConnectedLine.WAITING -> stringResource(R.string.direct_bluetooth_waiting_data)

            // A meter whose memory is empty finishes the sync without any record, and one whose
            // records were just saved has none left on the page; either way the waiting text
            // gives way to a completion line.
            ConnectedLine.TRANSFER_DONE ->
                pluralStringResource(R.plurals.direct_bluetooth_glucose_records, glucoseSynced, glucoseSynced)

            ConnectedLine.CONNECTED -> stringResource(R.string.m3_sync_connected, deviceName.orEmpty())
        }

        DirectConnectionState.DISCONNECTED -> deviceName?.let { stringResource(R.string.m3_sync_disconnected, it) }

        DirectConnectionState.ERROR -> stringResource(directBluetoothErrorRes(errorDetail))
    } ?: return
    val failed = connection == DirectConnectionState.ERROR
    Text(
        line,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics {
            liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    )
}

/**
 * What a failed connection means to the person holding the device. The manager's detail is a
 * protocol token, such as "link:133" or "bond_timeout": a lead in the log, never to be printed as
 * it stands, untranslated, inside a translated sentence.
 */
@StringRes
internal fun directBluetoothErrorRes(detail: String?): Int {
    val token = detail.orEmpty()
    return when {
        token == "bluetooth_off" -> R.string.direct_bluetooth_disabled
        token == "permission" -> R.string.device_picker_permission
        token.startsWith("scan:") -> R.string.m3_sync_error_scan
        token == "bond_stale" -> R.string.m3_sync_error_stale_bond
        "bond" in token -> R.string.m3_sync_error_pairing
        token.isUnanswered() -> R.string.m3_sync_error_no_answer
        else -> R.string.m3_sync_error_generic
    }
}

/** A link that failed or dropped, or a watchdog that ran out waiting for the device. */
private fun String.isUnanswered(): Boolean =
    "timeout" in this || this in UNANSWERED_STEPS || UNANSWERED_PREFIXES.any(::startsWith)

/** A ring's command watchdog names the step that went unanswered instead of saying "timeout". */
private val UNANSWERED_STEPS = setOf("colmi_write", "colmi_response")
private val UNANSWERED_PREFIXES = listOf("link:", "connect:")

private const val DIRECT_BLUETOOTH_LOG_TAG = "DirectBluetooth"

/** Why the search cannot start, and the one door past it. */
@Composable
private fun BluetoothGate(message: String, action: String, onAction: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.error)
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
}

@Composable
private fun ScanToggle(scanning: Boolean, secondary: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (scanning) R.string.direct_bluetooth_stop_scan else R.string.direct_bluetooth_scan)
    if (secondary) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    } else {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

/** A settings page is another app's activity, and a device can lack any one of them. */
internal fun Context.startSettings(intent: Intent) {
    runCatching { startActivity(intent) }
}

/** A reading's title, as a heading a screen reader can jump between. */
@Composable
private fun MeasurementHeading(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** Why the Save below is grey. A verdict, so it is printed rather than put behind a help circle. */
@Composable
private fun SaveBlockedReason(@StringRes reason: Int) {
    Text(
        stringResource(reason),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** A figure and its unit, in the order and spacing the translation gives them. */
@Composable
private fun valueWithUnit(figure: String, @StringRes unit: Int): String =
    stringResource(R.string.report_value_unit, figure, stringResource(unit))

/**
 * A figure to [places] decimals, with the decimal mark of the language on screen, as [shown] beside
 * it writes its own. A bare "%.1f".format() asks the JVM's default locale instead.
 */
@Composable
private fun fixed(value: Double, places: Int): String =
    String.format(LocalLocale.current.platformLocale, "%.${places}f", value)

/** A percentage to one decimal, its sign placed by the translation as every other unit here is. */
@Composable
private fun percentText(value: Double): String = valueWithUnit(fixed(value, 1), R.string.labs_unit_percent)

/** Kilograms as the device sent them, printed in pounds where the profile reads in miles. */
@Composable
private fun massText(kg: Double, imperial: Boolean): String = if (imperial) {
    valueWithUnit(imperialWeight(kg).shown(), R.string.unit_lb)
} else {
    stringResource(R.string.direct_bluetooth_weight_value, kg.shown())
}

/** The sensor's km/h, in miles an hour where the profile reads in miles. */
@Composable
private fun speedText(kph: Double, imperial: Boolean): String = stringResource(
    if (imperial) R.string.track_speed_value_mph else R.string.track_speed_value,
    distanceValue(kph * 1_000.0, imperial),
)

@Composable
private fun pressureText(mmhg: Double): String = valueWithUnit(fixed(mmhg, 0), R.string.vitals_unit_mmhg)

@Composable
private fun glucoseText(glucose: GlucoseMeasurement): String? =
    glucose.concentrationMgDl?.let { valueWithUnit(fixed(it, 1), R.string.labs_unit_mgdl) }
        ?: glucose.concentrationMmolL?.let { valueWithUnit(fixed(it, 2), R.string.labs_unit_mmol) }
