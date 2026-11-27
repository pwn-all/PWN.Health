package com.pwnall.health

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * [DirectBluetoothManager] against Robolectric's adapter, scanner and GATT client: what reaches it
 * from Android — advertisements, connection states, a failed search — and what it says back to the
 * page. The scan callback and the GATT callback are called the way Android calls them, on the main
 * looper, which is the thread the manager confines itself to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DirectBluetoothManagerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)).adapter
    private val published = mutableListOf<List<DirectDevice>>()
    private val states = mutableListOf<Pair<DirectConnectionState, String?>>()
    private val remotes = mutableMapOf<Int, BluetoothDevice>()
    private var searchFailures = 0
    private lateinit var manager: DirectBluetoothManager
    private lateinit var scan: ScanCallback

    @Before
    fun searchWithTheRadioOnAndPermitted() {
        BLUETOOTH_PERMISSIONS.forEach { shadowOf(context).grantPermissions(it) }
        shadowOf(adapter).setEnabled(true)
        manager = DirectBluetoothManager(
            context,
            onDevicesChanged = { published += it },
            onScanningChanged = {},
            onConnectionChanged = { state, detail -> states += state to detail },
            onScaleMeasurement = { _, _ -> },
            onFitnessMeasurement = {},
            onHeartRate = {},
            onRunning = {},
        )
        manager.onSearchFailed = { searchFailures += 1 }
        manager.startScan()
        scan = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
    }

    @After
    fun close() = manager.close()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun remote(n: Int) = remotes.getOrPut(n) { adapter.getRemoteDevice("AA:BB:CC:00:00:0$n") }

    private fun gatts(n: Int): List<BluetoothGatt> = shadowOf(remote(n)).bluetoothGatts

    /** An advertisement as Android's own parser reads it; the parser is hidden API. */
    private fun record(vararg bytes: Int): ScanRecord =
        ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
            .invoke(null, ByteArray(bytes.size) { bytes[it].toByte() }) as ScanRecord

    /** "HRMn" offering the Heart Rate service, 0x180D. */
    private fun heartRateMonitor(n: Int) = record(
        0x05, 0x09, 'H'.code, 'R'.code, 'M'.code, '0'.code + n, // complete local name
        0x03, 0x03, 0x0D, 0x18, // complete list of 16-bit service UUIDs
    )

    private fun advertise(n: Int, record: ScanRecord?) {
        val result = ScanResult(
            remote(n),
            0,
            BluetoothDevice.PHY_LE_1M,
            ScanResult.PHY_UNUSED,
            ScanResult.SID_NOT_PRESENT,
            ScanResult.TX_POWER_NOT_PRESENT,
            -60,
            ScanResult.PERIODIC_INTERVAL_NOT_PRESENT,
            record,
            0L,
        )
        scan.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
        idle()
    }

    private fun found(n: Int): DirectDevice {
        advertise(n, heartRateMonitor(n))
        return published.last().single { it.address == remote(n).address }
    }

    /**
     * The scan hears every advertiser in the room, and Android hands over a packet it could not
     * parse as a record with every field empty. Reading the missing Service Data map would end the
     * process from this callback; the packet is passed over, and the next one is still heard.
     */
    @Test
    fun anAdvertisementAndroidCouldNotParseIsPassedOver() {
        // Manufacturer data too short to hold its own company identifier.
        val unparsed = record(0x02, 0xFF, 0x00)
        assertNull("the fixture is the packet Android gave up on", unparsed.serviceData)

        advertise(1, unparsed)
        advertise(2, null)

        assertTrue(published.all { it.isEmpty() })
        advertise(3, heartRateMonitor(3))
        assertEquals(listOf("HRM3"), published.last().map { it.name })
    }

    /** A Bangle.js advertises a name and no service this app collects; the name is what lists it. */
    @Test
    fun aBangleJsIsListedByTheNameItAdvertises() {
        val name = "Bangle.js 7a3c"
        advertise(1, record(name.length + 1, 0x09, *name.map(Char::code).toIntArray()))

        val watch = published.last().single()
        assertEquals(DirectDeviceKind.BANGLE_JS2, watch.kind)
        assertEquals(name, watch.name)
        assertFalse(watch.advertisementOnly)
    }

    /**
     * GATT_ERROR 133 arrives as STATE_CONNECTED with a failing status. It is an error on the page,
     * the client is closed, and connecting again is not refused as "disconnect first".
     */
    @Test
    fun aConnectThatFailedWith133IsAnErrorAndTheNextConnectIsTried() {
        val monitor = found(1)
        manager.connect(monitor)
        val first = gatts(1).single()

        shadowOf(first).gattCallback.onConnectionStateChange(first, 133, BluetoothProfile.STATE_CONNECTED)
        idle()

        assertEquals(DirectConnectionState.ERROR to "connect:133", states.last())
        assertTrue(shadowOf(first).isClosed)
        manager.connect(monitor)
        assertEquals(DirectConnectionState.CONNECTING to "HRM1", states.last())
        assertEquals(2, gatts(1).size)
    }

    /** A link that never gets as far as ready is given thirty seconds, then closed and reported. */
    @Test
    fun aLinkThatNeverBecomesReadyIsGivenUpAfterThirtySeconds() {
        manager.connect(found(1))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertEquals(DirectConnectionState.CONNECTING to "HRM1", states.last())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(DirectConnectionState.ERROR to "connection_timeout", states.last())
        assertTrue(shadowOf(gatts(1).single()).isClosed)
    }

    /**
     * Choosing a second device drops the first link there and then, and the goodbye the dropped
     * link sends later is not taken for the end of the new one.
     */
    @Test
    fun choosingAnotherDeviceDropsTheOpenLinkAndIgnoresItsLateCallback() {
        val first = found(1)
        val second = found(2)
        manager.connect(first)
        val dropped = gatts(1).single()

        manager.choose(second)
        val current = gatts(2).single()
        assertTrue(shadowOf(dropped).isClosed)
        assertEquals(DirectConnectionState.CONNECTING to "HRM2", states.last())

        shadowOf(dropped).gattCallback.onConnectionStateChange(
            dropped,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED,
        )
        idle()

        assertEquals(DirectConnectionState.CONNECTING to "HRM2", states.last())
        assertFalse(shadowOf(current).isClosed)
    }

    /** Beside an open link a failed search answers for itself and leaves the connection as it is. */
    @Test
    fun aSearchFailingBesideAnOpenLinkLeavesTheLinkAlone() {
        manager.connect(found(1))
        val before = states.toList()

        scan.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        idle()

        assertEquals(before, states)
        assertEquals(1, searchFailures)
        assertFalse(shadowOf(gatts(1).single()).isClosed)
    }

    @Test
    fun aSearchFailingWithNoLinkIsTheErrorOnThePage() {
        scan.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        idle()

        assertEquals(DirectConnectionState.ERROR to "scan:${ScanCallback.SCAN_FAILED_INTERNAL_ERROR}", states.last())
        assertEquals(0, searchFailures)
    }
}
