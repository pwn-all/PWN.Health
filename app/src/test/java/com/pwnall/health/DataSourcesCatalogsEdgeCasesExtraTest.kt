package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The packet edges and the classification order behind the device picker: which adapter is handed
 * the bytes, what a scale's own clock is allowed to do to a weighing, and which devices a form is
 * allowed to offer.
 */
class DataSourcesCatalogsEdgeCasesExtraTest {
    /** The international pound, which is what "lbs" on a scale's units bit means. */
    private val kgPerPound = 0.45359237

    /** 2026-07-30T10:20:30Z, the arrival instant the scale fixtures are timed against. */
    private val arrival = 1_785_406_830_000L

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()

    /** A WSS packet in kilograms with the timestamp flag set: 14 000 * 5 g is 70.00 kg. */
    private fun timedWeighing(vararg dateTime: Int): ByteArray = bytes(0x02, 0xB0, 0x36, *dateTime)

    private fun gattDateTime(at: LocalDateTime): IntArray = intArrayOf(
        at.year and 0xFF,
        at.year shr 8,
        at.monthValue,
        at.dayOfMonth,
        at.hour,
        at.minute,
        at.second,
    )

    /** A 13-byte Mi Body Composition Scale 2 advertisement, stabilized, 17 030 raw weight. */
    private fun miScaleV2(units: Int): ByteArray =
        bytes(units, 0x26, 0xEA, 0x07, 0x05, 0x04, 0x0F, 0x02, 0x01, 0xAC, 0x01, 0x86, 0x42)

    private fun adapterFor(name: String?, uuid: UUID): String? =
        BLUETOOTH_MEASUREMENT_ADAPTERS.firstOrNull { it.supports(name, listOf(uuid)) }?.id

    /**
     * The floor is this app's own invention, not the profile's, and it sits below a newborn on
     * purpose: an infant weighed on a bathroom scale is a real weighing and must come back.
     */
    @Test
    fun theWeightScalePlausibilityFloorSitsOnTwoKilogramsExactly() {
        val onTheFloor = BluetoothWeightScale.parse(bytes(0x00, 0x90, 0x01), zoneId = ZoneOffset.UTC)

        assertEquals(2.0, onTheFloor!!.weightKg, 1e-9)
        assertNull(BluetoothWeightScale.parse(bytes(0x00, 0x8F, 0x01), zoneId = ZoneOffset.UTC))
    }

    /**
     * Advertisement and indication bytes come off whatever device is in range. An hour of 24 or a
     * 29th of February in a year that has none must cost the timestamp and nothing else — an
     * exception here kills the BLE callback thread and takes the whole sync down with it.
     */
    @Test
    fun aGarbledClockOctetCostsTheTimestampNeverTheWeighing() {
        val garbled = listOf(
            intArrayOf(0xEA, 0x07, 0x07, 0x1E, 0x18, 0x00, 0x00), // hour 24
            intArrayOf(0xEA, 0x07, 0x07, 0x1E, 0x0A, 0x3C, 0x00), // minute 60
            intArrayOf(0xEA, 0x07, 0x07, 0x1E, 0x0A, 0x14, 0x3C), // second 60
            intArrayOf(0xEA, 0x07, 0x0D, 0x1E, 0x0A, 0x14, 0x1E), // month 13
            intArrayOf(0xEA, 0x07, 0x07, 0x20, 0x0A, 0x14, 0x1E), // 32nd of July
            intArrayOf(0xEA, 0x07, 0x02, 0x1D, 0x0A, 0x14, 0x1E), // 2026-02-29
        )

        garbled.forEach { dateTime ->
            val where = "date time ${dateTime.toList()}"
            val decoded = BluetoothWeightScale.parse(
                timedWeighing(*dateTime),
                fallbackOccurredAt = arrival,
                zoneId = ZoneOffset.UTC,
            )
            assertEquals(where, 70.0, decoded!!.weightKg, 1e-9)
            assertEquals(where, arrival, decoded.occurredAt)
            assertEquals(where, false, decoded.deviceClockSet)
        }
    }

    /**
     * Bluetooth Date Time carries no zone, so the phone's is applied to a wall clock that never
     * happened on the morning the clocks went forward. The same wire bytes have to give the same
     * instant every time, or the clientRecordId upsert stops matching and the weighing is filed
     * twice.
     */
    @Test
    fun aScaleTimestampInsideTheSpringForwardGapIsDatedDeterministically() {
        val newYork = ZoneId.of("America/New_York")
        val inTheGap = timedWeighing(0xEA, 0x07, 0x03, 0x08, 0x02, 0x1E, 0x00) // 2026-03-08 02:30
        val morning = Instant.parse("2026-03-08T08:00:00Z").toEpochMilli()

        val first = BluetoothWeightScale.parse(inTheGap, fallbackOccurredAt = morning, zoneId = newYork)!!
        val again = BluetoothWeightScale.parse(inTheGap, fallbackOccurredAt = morning + 1_000L, zoneId = newYork)!!

        // java.time moves a local time inside the gap forward by the offset change: 02:30 EST
        // becomes 03:30 EDT, which is 07:30 UTC.
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), first.occurredAt)
        assertEquals(first.occurredAt, again.occurredAt)
        assertEquals(true, first.deviceClockSet)
    }

    /**
     * A scale set once and never again is believed for ten years, and dated on arrival past that,
     * so the weighing lands where the journal and the vitals card look instead of a decade back.
     */
    @Test
    fun aScaleSetOnceADecadeAgoIsDatedOnArrivalRatherThanInThePast() {
        val tenYears = Duration.ofDays(3650).toMillis()
        val edge = LocalDateTime.ofInstant(Instant.ofEpochMilli(arrival - tenYears), ZoneOffset.UTC)

        val believed = BluetoothWeightScale.parse(
            timedWeighing(*gattDateTime(edge)),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        val older = BluetoothWeightScale.parse(
            timedWeighing(*gattDateTime(edge.minusDays(1))),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(arrival - tenYears, believed.occurredAt)
        assertEquals(true, believed.deviceClockSet)
        assertEquals(arrival, older.occurredAt)
        assertEquals(false, older.deviceClockSet)
        assertEquals(70.0, older.weightKg, 1e-9)
    }

    /**
     * The scale reports 0.01 jin where it reports 0.005 kg, and one jin is half a kilogram, so
     * setting the catty bit must not change the number a person sees. The pound bit must.
     */
    @Test
    fun cattyAndKilogramsLandOnTheSameFigureAndPoundsDoNot() {
        val taken = Instant.parse("2026-05-04T15:02:01Z").toEpochMilli()
        val kilograms = XiaomiMiScaleV2.parse(miScaleV2(0x02), taken, ZoneOffset.UTC)!!
        val catty = XiaomiMiScaleV2.parse(miScaleV2(0x12), taken, ZoneOffset.UTC)!!
        val pounds = XiaomiMiScaleV2.parse(miScaleV2(0x03), taken, ZoneOffset.UTC)!!

        assertEquals(85.15, kilograms.weightKg, 1e-6)
        assertEquals(kilograms.weightKg, catty.weightKg, 1e-9)
        assertEquals(170.30 * kgPerPound, pounds.weightKg, 1e-6)
    }

    /**
     * The v1 adapter masks the control byte to 0x0F before the strict generic decoder sees it. That
     * mask governs the units, the timestamp flag that makes a ten-byte packet legal and the BMI
     * flag at once, so a packet with a reserved high bit set is the only thing that shows it works.
     */
    @Test
    fun theMiScaleV1CattyBitIsStrippedWithoutDisturbingTheWeightOrTheClock() {
        val taken = Instant.parse("2021-04-11T16:19:01Z").toEpochMilli()
        val plain = bytes(0x22, 0x9E, 0x43, 0xE5, 0x07, 0x04, 0x0B, 0x10, 0x13, 0x01)
        val catty = plain.copyOf().also { it[0] = 0x32.toByte() }

        listOf(plain, catty).forEach { packet ->
            val where = "control byte ${packet[0]}"
            val decoded = XiaomiMiScaleV1.parse(packet, taken, ZoneOffset.UTC)
            assertEquals(where, 86.55, decoded!!.weightKg, 1e-6)
            assertTrue(where, decoded.isStable)
            assertEquals(where, taken, decoded.occurredAt)
        }
    }

    /**
     * Both Xiaomi adapters overlap the generic adapter's UUIDs, so the order of the list is what
     * decides which parser is handed the bytes. Reversing it feeds Xiaomi advertisements to the
     * strict WSS decoder, which drops every one of them.
     */
    @Test
    fun theAdapterListClaimsEachScaleExactlyOnceInTheDocumentedOrder() {
        val weight = BluetoothWeightScale.serviceUuid
        val composition = BluetoothWeightScale.bodyCompositionServiceUuid

        assertEquals("xiaomi_mi_scale_v2", adapterFor("MIBFS", composition))
        assertEquals("xiaomi_mi_scale_v1", adapterFor("MI_SCALE", weight))
        assertEquals("xiaomi_mi_scale_v1", adapterFor("miscale2", weight))
        // The adopted profile needs no name; an unnamed 0x181B peripheral is not a Xiaomi scale.
        assertEquals("bluetooth_sig_weight_scale", adapterFor(null, weight))
        assertNull(adapterFor(null, composition))
        assertNull(adapterFor("Omron HEM-7361T", composition))
    }

    /**
     * The v1 name arrives with an underscore where a space belongs, and neither adapter trims. A
     * name with no service UUID beside it is nobody's scale, whatever it is called.
     */
    @Test
    fun aDeviceNameIsRecognisedThroughCaseAndUnderscoresAndOnlyThen() {
        val weight = listOf(BluetoothWeightScale.serviceUuid)
        val composition = listOf(BluetoothWeightScale.bodyCompositionServiceUuid)

        listOf("MI_SCALE", "mi scale", "MISCALE", "miscale v1").forEach {
            assertTrue("v1 refused $it", XiaomiMiScaleV1.supports(it, weight))
        }
        listOf(" MI_SCALE", "MIBFS", "", null).forEach {
            assertFalse("v1 accepted $it", XiaomiMiScaleV1.supports(it, weight))
        }
        assertTrue(XiaomiMiScaleV2.supports("MIBFS-1B2C", composition))
        listOf(" MIBFS", "Mi Body Composition").forEach {
            assertFalse("v2 accepted $it", XiaomiMiScaleV2.supports(it, composition))
        }
        assertFalse(XiaomiMiScaleV1.supports("MI_SCALE", emptyList()))
        assertFalse(XiaomiMiScaleV2.supports("MIBFS", emptyList()))
    }

    /**
     * The MIBFS advertises under 0x181B but its payload is a fixed 13-byte vendor record, not a
     * Body Composition Measurement. Down the SIG path a real 105.8 kg weighing decodes as zero and
     * is thrown away as implausible, so the Xiaomi test has to stay above the 0x181B test.
     */
    @Test
    fun aMiBodyCompositionScaleIsAScaleNotABodyCompositionDevice() {
        val composition = setOf(BluetoothBodyComposition.serviceUuid)

        assertEquals(
            DirectDeviceKind.SCALE,
            directDeviceKind(composition, scale = XiaomiMiScaleV2, colmiModel = null),
        )
        assertEquals(
            DirectDeviceKind.BODY_COMPOSITION,
            directDeviceKind(composition, scale = null, colmiModel = null),
        )
    }

    /**
     * A Keiser bike advertises no service at all, so a decoded broadcast is the only proof there
     * is — and the strap the rider is wearing must not rename the bike. An advertisement nothing
     * recognises is dropped rather than guessed at, or every advertiser in the room is in the list.
     */
    @Test
    fun aKeiserBroadcastBeatsTheStrapAndARingBeatsEverythingBelowIt() {
        assertEquals(
            DirectDeviceKind.KEISER_M3I,
            directDeviceKind(
                setOf(BluetoothHeartRate.serviceUuid),
                scale = null,
                colmiModel = null,
                keiser = true,
            ),
        )
        assertEquals(
            DirectDeviceKind.COLMI_RING,
            directDeviceKind(
                setOf(BluetoothFitnessMachine.serviceUuid, BluetoothHeartRate.serviceUuid),
                scale = null,
                colmiModel = ColmiRingModel.R02,
            ),
        )
        assertNull(directDeviceKind(emptySet(), scale = null, colmiModel = null))
    }

    /**
     * Recent PM5 firmware exposes FTMS beside Concept2's own published service, and the rower's
     * native protocol carries the stroke rate and stroke count FTMS's rower record does not.
     */
    @Test
    fun aPm5ThatAlsoAdvertisesFtmsStaysAPm5() {
        assertEquals(
            DirectDeviceKind.CONCEPT2_PM5,
            directDeviceKind(
                setOf(BluetoothConcept2Pm5.serviceUuid, BluetoothFitnessMachine.serviceUuid),
                scale = null,
                colmiModel = null,
            ),
        )
        assertEquals(
            DirectDeviceKind.CONCEPT2_PM5,
            directDeviceKind(
                setOf(BluetoothConcept2Pm5.advertisedServiceUuid),
                scale = null,
                colmiModel = null,
            ),
        )
    }

    /**
     * The kind label doubles as the name of an advertisement that carries none, so a missing label
     * leaves an unnamed device in the picker and a shared one puts two devices under one name.
     */
    @Test
    fun everyDeviceKindHasItsOwnLabel() {
        val labels = DirectDeviceKind.entries.map { it.labelRes() }

        assertTrue("a kind resolved to no string at all: $labels", labels.none { it == 0 })
        assertEquals("labels were $labels", labels.size, labels.toSet().size)
    }

    /**
     * Service Data has its own AD structure, so a scale that declares what it broadcasts puts the
     * same UUID in both lists. Seeing it twice makes an adapter answer twice; seeing it in another
     * order changes which branch of the classification's or-chains gets there first.
     */
    @Test
    fun aServiceUuidAdvertisedTwiceIsMergedOnceDeclarationOrderFirst() {
        val merged = mergedAdvertisedServiceUuids(
            listOf(BluetoothHeartRate.serviceUuid, BluetoothWeightScale.bodyCompositionServiceUuid),
            listOf(BluetoothWeightScale.bodyCompositionServiceUuid, BluetoothWeightScale.serviceUuid),
        )

        assertEquals(
            listOf(
                BluetoothHeartRate.serviceUuid,
                BluetoothWeightScale.bodyCompositionServiceUuid,
                BluetoothWeightScale.serviceUuid,
            ),
            merged.toList(),
        )
        assertEquals(emptySet<UUID>(), mergedAdvertisedServiceUuids(emptyList(), emptyList()))
    }

    /**
     * No mode may offer a device that cannot answer the question the form is asking, and none may
     * withhold one that can: a Body Composition Service scale reports a height, and the height form
     * is the one place that height is the whole point.
     */
    @Test
    fun everyModeOffersExactlyTheDevicesThatCanAnswerIt() {
        val weighing = setOf(DirectDeviceKind.SCALE, DirectDeviceKind.BODY_COMPOSITION)

        assertEquals(weighing, measurementDeviceKinds("WEIGHT"))
        assertEquals(weighing, measurementDeviceKinds("BOTH"))
        assertEquals(weighing, measurementDeviceKinds("HEIGHT"))
        assertEquals(setOf(DirectDeviceKind.HEART_RATE), measurementDeviceKinds("HEART_RATE"))
        // A night's sleep has no device path, so the card is not drawn rather than drawn useless.
        assertEquals(emptySet<DirectDeviceKind>(), measurementDeviceKinds("SLEEP"))
        assertEquals(emptySet<DirectDeviceKind>(), measurementDeviceKinds(""))
        assertEquals(emptySet<DirectDeviceKind>(), measurementDeviceKinds("weight"))
    }

    /**
     * org.json drops a key whose value is null, so "the machine sent nought watts" and "the machine
     * sent no power at all" are one boxing decision apart — and once saved they can never be told
     * apart again.
     */
    @Test
    fun zeroFromAMachineIsAMeasurementAndAbsentIsAbsent() {
        val zeroes = JSONObject(
            fitnessPayload(
                FitnessMachineMeasurement(
                    type = FitnessMachineType.TREADMILL,
                    totalEnergyKcal = 0,
                    powerWatts = 0,
                ),
                "Bluetooth SIG FTMS",
            ),
        )
        val silent = JSONObject(
            fitnessPayload(FitnessMachineMeasurement(type = FitnessMachineType.ROWER), "Concept2 PM5"),
        )

        assertEquals(0L, zeroes.getInt("totalEnergyKcal").toLong())
        assertEquals(0L, zeroes.getInt("powerWatts").toLong())
        assertEquals("TREADMILL", zeroes.getString("machineType"))
        assertEquals("Bluetooth SIG FTMS", zeroes.getString("profile"))
        assertFalse("a speed the treadmill never sent", zeroes.has("speedKph"))
        // Schema, profile and machine type, and not one key for a field the rower never sent.
        assertEquals("keys held: ${silent.length()}", 3L, silent.length().toLong())
        assertEquals("ROWER", silent.getString("machineType"))
        assertFalse("a stroke count the rower never sent", silent.has("strokeCount"))
    }

    /**
     * A Mi Scale advertises the stable weight several times a second. The window that tells a
     * rebroadcast from a second weighing is inclusive: a millisecond either side of it decides
     * between a duplicate row and a genuine weighing swallowed.
     */
    @Test
    fun fiveMinutesIsTheRebroadcastWindowToTheMillisecond() {
        val saved = StandardScaleMeasurement(85.15, null, null, null, 1_000L, deviceClockSet = false)
        val onTheEdge = saved.copy(occurredAt = 1_000L + 300_000L)

        assertTrue(isSameWeighing(saved, onTheEdge))
        // A scale that sent no time at all is judged by the same window as one that was disbelieved.
        assertTrue(
            isSameWeighing(
                saved.copy(deviceClockSet = null),
                onTheEdge.copy(deviceClockSet = null),
            ),
        )
    }

    /**
     * Both services resolve weight to 5 g, so one weighing's two readings agree to far less than
     * 50 g. Too tight and one weighing becomes two rows and two WeightRecords; too loose and two
     * people in a household are folded into one body.
     */
    @Test
    fun twoReadingsOfOneWeighingAgreeToFiftyGramsAndNoFurther() {
        val scale = StandardScaleMeasurement(80.00, 175, null, null, 1_000L)

        assertEquals(80.05, folded(scale, 80.05), 1e-9)
        assertEquals(79.95, folded(scale, 79.95), 1e-9)
        assertNull(foldWeighingIntoComposition(scale, BodyCompositionMeasurement(20.0, weightKg = 80.0500001)))
    }

    private fun folded(scale: StandardScaleMeasurement, compositionWeightKg: Double): Double {
        val composition = BodyCompositionMeasurement(bodyFatPercent = 20.0, weightKg = compositionWeightKg)
        return foldWeighingIntoComposition(scale, composition)!!.weightKg!!
    }
}
