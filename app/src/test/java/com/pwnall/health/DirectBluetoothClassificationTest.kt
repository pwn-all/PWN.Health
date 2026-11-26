package com.pwnall.health

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DirectBluetoothClassificationTest {
    /**
     * A Stryd pod advertises RSCS beside CPS and is a running sensor: taken for a cycling one, every
     * run it saved would carry the cycling MET and lose its pace and cadence.
     */
    @Test
    fun aPodThatSpeaksRunningAndPowerIsARunningPod() {
        assertEquals(
            DirectDeviceKind.RUNNING_POD,
            directDeviceKind(
                setOf(BluetoothRunningSpeedCadence.serviceUuid, BluetoothCyclingPower.serviceUuid),
                scale = null,
                colmiModel = null,
            ),
        )
        assertEquals(
            DirectDeviceKind.CYCLING_SENSOR,
            directDeviceKind(setOf(BluetoothCyclingPower.serviceUuid), scale = null, colmiModel = null),
        )
    }

    /** A TICKR X is a strap first; the running profile beside it names the workout, not the device. */
    @Test
    fun aStrapWithARunningProfileKeepsTheRunningWorkout() {
        assertEquals(
            DirectDeviceKind.RUNNING_POD,
            directDeviceKind(
                setOf(BluetoothHeartRate.serviceUuid, BluetoothRunningSpeedCadence.serviceUuid),
                scale = null,
                colmiModel = null,
            ),
        )
        assertEquals(
            DirectDeviceKind.HEART_RATE,
            directDeviceKind(setOf(BluetoothHeartRate.serviceUuid), scale = null, colmiModel = null),
        )
    }

    /**
     * Android delivers a packet it could not parse as a record with no lists at all, and the scan
     * hears every advertiser in the room: reading the missing Service Data map would crash the app.
     */
    @Test
    fun aPacketAndroidCouldNotParseAdvertisesNothing() {
        val unparsed = mergedAdvertisedServiceUuids(null, null)

        assertTrue(unparsed.isEmpty())
        assertNull(directDeviceKind(unparsed, scale = null, colmiModel = null))
        assertEquals(
            setOf(BluetoothHeartRate.serviceUuid),
            mergedAdvertisedServiceUuids(listOf(BluetoothHeartRate.serviceUuid), null),
        )
        assertEquals(
            setOf(BluetoothWeightScale.serviceUuid),
            mergedAdvertisedServiceUuids(null, listOf(BluetoothWeightScale.serviceUuid)),
        )
    }

    @Test
    fun aScaleWeightFillsACompositionThatCarriesNone() {
        val scale = StandardScaleMeasurement(80.0, 175, null, null, 1_000L)
        val composition = BodyCompositionMeasurement(
            bodyFatPercent = 20.0,
            timestamp = LocalDateTime.of(2026, 9, 4, 8, 0),
        )

        val folded = foldWeighingIntoComposition(scale, composition)!!

        assertEquals(80.0, folded.weightKg!!, 0.0001)
        assertEquals(175.0, folded.heightCm!!, 0.0001)
        assertEquals(20.0, folded.bodyFatPercent!!, 0.0001)
    }

    @Test
    fun aCompositionThatAlreadyCarriesTheSameWeightAbsorbsTheScaleCard() {
        val scale = StandardScaleMeasurement(80.0, null, null, null, 1_000L)
        val composition = BodyCompositionMeasurement(bodyFatPercent = 20.0, weightKg = 80.0, heightCm = 180.0)

        val folded = foldWeighingIntoComposition(scale, composition)!!

        assertEquals(80.0, folded.weightKg!!, 0.0001)
        assertEquals(180.0, folded.heightCm!!, 0.0001)
    }

    /** Two weights that disagree are two people, and each keeps its own card. */
    @Test
    fun aDifferentWeightIsADifferentWeighing() {
        val scale = StandardScaleMeasurement(72.0, null, null, null, 1_000L)
        assertNull(foldWeighingIntoComposition(scale, BodyCompositionMeasurement(20.0, weightKg = 80.0)))
    }

    /** A failed composition cannot be saved, so the plain weight must keep a Save of its own. */
    @Test
    fun aFailedCompositionLeavesTheScaleCardAlone() {
        val scale = StandardScaleMeasurement(80.0, null, null, null, 1_000L)
        assertNull(foldWeighingIntoComposition(scale, BodyCompositionMeasurement(bodyFatPercent = null)))
        assertNull(foldWeighingIntoComposition(null, BodyCompositionMeasurement(20.0)))
        assertNull(foldWeighingIntoComposition(scale, null))
    }

    /**
     * A scale whose clock was never set has its weighing dated on arrival, separately for every
     * frame, so two frames of one weighing never share an occurredAt, and a guard that compared
     * them would let the weight just saved come back as an unsaved card after Save.
     */
    @Test
    fun aRebroadcastOfAnUnsetClockWeighingIsTheWeighingJustSaved() {
        val arrival = 1_785_406_830_000L
        val xiaomi = byteArrayOf(
            0x02, 0x26, // kilograms; stable, with impedance
            0x00, 0x00, 0x01, 0x01, 0x0F, 0x02, 0x01, // year 0: a clock nobody set
            0xAC.toByte(), 0x01, // 428 ohm
            0x86.toByte(), 0x42, // 85.15 kg
        )
        val saved = XiaomiMiScaleV2.parse(xiaomi, fallbackOccurredAt = arrival, zoneId = ZoneOffset.UTC)!!
        val again = XiaomiMiScaleV2.parse(xiaomi, fallbackOccurredAt = arrival + 1_000L, zoneId = ZoneOffset.UTC)!!
        assertEquals(false, saved.deviceClockSet)
        assertTrue(isSameWeighing(saved, again))

        val wss = byteArrayOf(
            0x02, 0xB0.toByte(), 0x36, // kilograms with a timestamp
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // year 0, month 0, day 0
        )
        val indicated = BluetoothWeightScale.parse(wss, fallbackOccurredAt = arrival, zoneId = ZoneOffset.UTC)!!
        val reindicated = BluetoothWeightScale.parse(
            wss,
            fallbackOccurredAt = arrival + 1_000L,
            zoneId = ZoneOffset.UTC,
        )!!
        assertTrue(isSameWeighing(indicated, reindicated))
    }

    /** A Mi Scale advertises the stable weight before the impedance; the two frames are one weighing. */
    @Test
    fun anImpedanceThatArrivesAfterTheSaveIsNotANewWeighing() {
        val saved = StandardScaleMeasurement(85.15, null, null, null, 1_000L, deviceClockSet = false)
        assertTrue(isSameWeighing(saved, saved.copy(occurredAt = 3_000L, impedanceOhms = 428)))
    }

    /** Another weight, or the same weight long after the scale went quiet, is a new weighing. */
    @Test
    fun aNewWeighingOnAnUnsetClockScaleIsToldByWeightAndTime() {
        val saved = StandardScaleMeasurement(85.15, null, null, null, 1_000L, deviceClockSet = false)
        assertFalse(isSameWeighing(saved, saved.copy(weightKg = 85.2, occurredAt = 2_000L)))
        assertFalse(isSameWeighing(saved, saved.copy(occurredAt = 1_000L + 5 * 60 * 1_000L + 1)))
    }

    /** A scale that dated the weighing itself repeats the date; a new date is a new weighing. */
    @Test
    fun aDeviceDatedWeighingIsKnownByItsOwnTime() {
        val saved = StandardScaleMeasurement(80.0, null, null, null, 1_000L, deviceClockSet = true)
        assertTrue(isSameWeighing(saved, saved.copy(impedanceOhms = 500)))
        assertFalse(isSameWeighing(saved, saved.copy(occurredAt = 2_000L)))
    }

    /**
     * Blanking the card only after the row is written and Health Connect told would let a second
     * tap in that time write the reading again. The first tap takes the reading off the
     * card; the second finds nothing there to save.
     */
    @Test
    fun aSecondTapOnSaveFindsNothingLeftToSave() {
        val weighing = StandardScaleMeasurement(80.0, null, null, null, 1_000L)
        val card = mutableStateOf<StandardScaleMeasurement?>(weighing)

        assertEquals(weighing, card.takeReading())
        assertNull(card.value)
        assertNull(card.takeReading())
    }

    /**
     * Android starts pairing by itself when a peripheral refuses an unencrypted write, and tells the
     * app nothing: the thirty seconds a connection is given would run out under the passkey dialog.
     */
    @Test
    fun pairingThePlatformStartedIsGivenThePairingTimeout() {
        fun extendsTheWatchdog(bondState: Int, requested: Boolean = false, ready: Boolean = false) =
            pairingStartedByPlatform(bondState, bondRequested = requested, connectionReady = ready)

        assertTrue(extendsTheWatchdog(BluetoothDevice.BOND_BONDING))
        // Pairing the manager asked for has its ninety seconds already, and its own failure token.
        assertFalse(extendsTheWatchdog(BluetoothDevice.BOND_BONDING, requested = true))
        // A link already delivering data has no watchdog left to extend.
        assertFalse(extendsTheWatchdog(BluetoothDevice.BOND_BONDING, ready = true))
        assertFalse(extendsTheWatchdog(BluetoothDevice.BOND_BONDED))
        assertFalse(extendsTheWatchdog(BluetoothDevice.BOND_NONE))
    }

    /**
     * A device the phone is already paired with that still wants pairing has forgotten its half:
     * no pairing request is coming, so the page must not ask the person to accept one.
     */
    @Test
    fun aPairingTheDeviceForgotIsNotAPairingRequestToAccept() {
        val forgotten = bondRefusal(BluetoothDevice.BOND_BONDED)
        assertEquals("bond_stale", forgotten)
        assertEquals(R.string.m3_sync_error_stale_bond, directBluetoothErrorRes(forgotten))
        assertEquals(R.string.m3_sync_error_pairing, directBluetoothErrorRes(bondRefusal(BluetoothDevice.BOND_NONE)))
    }

    /**
     * Two Mi Scales in range: before one is chosen the one being stood on speaks, and after it is
     * chosen the other one's weighing does not land on its card.
     */
    @Test
    fun aBroadcastWeighingComesOnlyFromTheChosenScaleOnceOneIsChosen() {
        assertTrue(takesBroadcastWeighing("AA:BB:CC:DD:EE:01", watched = null))
        assertTrue(takesBroadcastWeighing("AA:BB:CC:DD:EE:01", watched = "AA:BB:CC:DD:EE:01"))
        assertFalse(takesBroadcastWeighing("AA:BB:CC:DD:EE:02", watched = "AA:BB:CC:DD:EE:01"))
    }
}
