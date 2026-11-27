package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What the device page does with more than one reading: a memory handed over in a burst, the date
 * a reading is filed under, and a glucose transfer with nothing in it to save. And the lines it says
 * about a search that failed, a link hung up on, and a transfer that has finished.
 */
class DirectStoredReadingsTest {
    private val arrival = LocalDateTime.of(2026, 11, 18, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val hour = 60L * 60 * 1_000
    private val day = 24 * hour

    private fun pressure(at: Long, clockSet: Boolean? = true, user: Int? = null) =
        DirectProfileMeasurement.BloodPressure(
            BloodPressureMeasurement(
                systolic = 121.0,
                diastolic = 79.0,
                meanArterialPressure = null,
                unit = BloodPressureUnit.MMHG,
                pulseRateBpm = 64.0,
                userId = user,
                measurementStatus = null,
                occurredAt = at,
                deviceClockSet = clockSet,
            ),
        )

    private fun temperature(at: Long, type: TemperatureType? = null) = DirectProfileMeasurement.Temperature(
        TemperatureMeasurement(36.8, TemperatureUnit.CELSIUS, type, occurredAt = at, deviceClockSet = true),
    )

    /** The page's own bookkeeping, one arrival at a time. */
    private fun handedOver(vararg arrivals: DirectProfileMeasurement): List<DirectProfileMeasurement> =
        arrivals.fold(emptyList<DirectProfileMeasurement>()) { stored, arrived ->
            storedReadingsAfter(
                stored,
                arrived,
                keep = arrived.isKeptBesideCard(arrival),
                sameReading = ::isSameClinicalReading,
            )
        }

    /**
     * A cuff with three readings in its memory indicates them a tenth of a second apart. The card
     * holds one, and the first two are kept rather than gone before anyone sees them.
     */
    @Test
    fun everyReadingOutOfACuffsMemoryIsKept() {
        val monday = pressure(arrival - 2 * day)
        val mondayEvening = pressure(arrival - 2 * day + 12 * hour)
        val tuesday = pressure(arrival - day)

        assertEquals(listOf(monday, mondayEvening, tuesday), handedOver(monday, mondayEvening, tuesday))
        // Three readings of one sitting, a minute apart and sent as soon as the cuff is done.
        val sitting = listOf(3, 2, 1).map { pressure(arrival - it * 60_000L) }
        assertEquals(sitting, handedOver(*sitting.toTypedArray()))
    }

    /** A reading with no time of the device's own is a live one: the next replaces it. */
    @Test
    fun aReadingDatedOnArrivalIsNotKeptBesideTheCard() {
        assertNull(pressure(arrival - day, clockSet = null).deviceTime())
        assertNull(pressure(arrival - day, clockSet = false).deviceTime())
        assertTrue(handedOver(pressure(arrival, clockSet = null), pressure(arrival + 1, clockSet = null)).isEmpty())
        assertTrue(handedOver(pressure(arrival - day, clockSet = false)).isEmpty())
    }

    /** A thermometer's readings are posted again once its site is known; each replaces its copy. */
    @Test
    fun aReadingSentAgainReplacesItsEarlierCopy() {
        val first = temperature(arrival - day)
        val second = temperature(arrival - hour)
        val firstSited = temperature(arrival - day, TemperatureType.ARMPIT)
        val secondSited = temperature(arrival - hour, TemperatureType.ARMPIT)

        assertEquals(listOf(firstSited, secondSited), handedOver(first, second, firstSited, secondSited))
        // Two kinds of reading dated to one moment are still two readings.
        assertFalse(isSameClinicalReading(first, pressure(arrival - day)))
    }

    /**
     * Save writes the card's reading and the stored ones the device files under the same user: a
     * household's cuff keeps each person's under a number, and the other person's stay out.
     */
    @Test
    fun saveTakesTheOtherStoredReadingsOfTheSameUser() {
        val mine = pressure(arrival - 2 * day, user = 1)
        val theirs = pressure(arrival - day - hour, user = 2)
        val shown = pressure(arrival - day, user = 1)
        val stored = handedOver(mine, theirs, shown)

        val beside = stored.savedBeside(shown, ::isSameClinicalReading, DirectProfileMeasurement::userId)

        assertEquals(listOf(mine), beside)
        // A live reading on the card takes everything out of the memory of a cuff with no users.
        val live = pressure(arrival, clockSet = null)
        val single = handedOver(pressure(arrival - 2 * day), pressure(arrival - day), live)
        assertEquals(2, single.savedBeside(live, ::isSameClinicalReading, DirectProfileMeasurement::userId).size)
    }

    /**
     * A scale's memory. A scale is stood on while the phone listens, and stepping off and on again
     * is one weighing tried twice: only what the scale dated well before it arrived is kept.
     */
    @Test
    fun weighingsOutOfAScalesMemoryAreKeptAndASecondTryIsNot() {
        val monday = StandardScaleMeasurement(80.4, null, null, null, arrival - 2 * day, deviceClockSet = true)
        val tuesday = monday.copy(weightKg = 80.1, occurredAt = arrival - day)
        val firstTry = monday.copy(weightKg = 80.3, occurredAt = arrival - 20_000)
        val now = monday.copy(weightKg = 80.2, occurredAt = arrival)

        val arrivals = listOf(monday, tuesday, tuesday, firstTry, now)
        val stored = arrivals.fold(emptyList<StandardScaleMeasurement>()) { kept, weighing ->
            storedReadingsAfter(kept, weighing, isFromDeviceMemory(weighing.deviceTime(), arrival), ::isSameWeighing)
        }

        assertEquals(listOf(monday, tuesday), stored)
        assertEquals(stored, stored.savedBeside(now, ::isSameWeighing, StandardScaleMeasurement::userId))
        assertEquals(listOf(monday), stored.savedBeside(tuesday, ::isSameWeighing, StandardScaleMeasurement::userId))
        // Five minutes is what two clocks may disagree by; past it the scale had kept the weighing.
        assertFalse(isFromDeviceMemory(arrival - 5 * 60_000, arrival))
        assertTrue(isFromDeviceMemory(arrival - 5 * 60_000 - 1, arrival))
        assertFalse(isFromDeviceMemory(arrival + 60_000, arrival))
        assertNull(monday.copy(deviceClockSet = false).deviceTime())
    }

    /** A body-composition scale is a scale: its reading of this minute is live, yesterday's is kept. */
    @Test
    fun aCompositionIsKeptByTheScalesRule() {
        fun composition(at: Long) = DirectProfileMeasurement.BodyComposition(
            BodyCompositionMeasurement(
                bodyFatPercent = 20.5,
                timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()),
                deviceClockSet = true,
            ),
        )

        assertEquals(listOf(composition(arrival - day)), handedOver(composition(arrival - day), composition(arrival)))
        // A composition the scale did not date has no time to be known by.
        assertNull(DirectProfileMeasurement.BodyComposition(BodyCompositionMeasurement(20.5)).deviceTime())
    }

    /**
     * A device offers its memory again at the next connection, and what the journal already holds
     * is not written twice. A row of another kind of device is not that reading: the weight a scale
     * card saved must not stand in for the composition that carries the same weight and second.
     */
    @Test
    fun aReadingTheJournalHoldsIsKnownByItsDeviceAndItsFigure() {
        fun row(source: String?, payload: String, weightKg: Double? = null) = JournalEntry(
            day = "2026-11-17",
            occurredAt = arrival - day,
            kind = "MEASUREMENT",
            title = "Reading",
            calories = 0.0,
            measurementWeightKg = weightKg,
            measurementSource = source,
            measurementPayloadJson = payload,
        )
        val cuff = row("BLUETOOTH_BPS", """{"systolicMmhg":121,"diastolicMmhg":79,"pulseRateBpm":64}""")

        assertTrue(standsForReading(cuff, cuff.copy(note = "Omron")))
        // Restored from Health Connect: no source, and the one pair of figures the record carried.
        assertTrue(standsForReading(row(null, """{"systolicMmhg":121.0,"diastolicMmhg":79.0}"""), cuff))
        assertFalse(standsForReading(cuff.copy(occurredAt = arrival), cuff))

        val weight = row("BLUETOOTH_SCALE", "{}", weightKg = 80.4)
        val composition = row("BLUETOOTH_BCS", """{"bodyFatPercent":20.5,"weightKg":80.4}""", weightKg = 80.4)
        assertFalse(standsForReading(weight, composition))
        assertTrue(standsForReading(composition, composition))
    }

    /**
     * A reading is filed under the day its device dated it. A clock three years out is still inside
     * the window this app believes, so the card shows the figure with its date.
     */
    @Test
    fun aReadingFiledUnderAnotherDayShowsItsDate() {
        val threeYearsAgo = arrival - 3 * 365 * day

        assertEquals(threeYearsAgo, readingDateToShow(threeYearsAgo, arrival, ZoneOffset.UTC))
        assertNull(readingDateToShow(arrival - hour, arrival, ZoneOffset.UTC))
        // The day is the phone's own: last night by UTC is still today twelve hours to the west.
        assertEquals(arrival - 10 * hour, readingDateToShow(arrival - 10 * hour, arrival, ZoneOffset.UTC))
        assertNull(readingDateToShow(arrival - 10 * hour, arrival, ZoneOffset.ofHours(-12)))
    }

    /** A transfer with nothing in it to save says which kind of nothing it is. */
    @Test
    fun aGlucoseTransferWithNothingToSaveSaysWhy() {
        fun record(sequence: Int, type: Int = 1, status: Int? = null, concentration: Double? = 0.0055) =
            GlucoseRecord(
                GlucoseMeasurement(
                    sequenceNumber = sequence,
                    baseTime = LocalDateTime.of(2026, 11, 17, 8, 0),
                    concentration = concentration,
                    concentrationUnit = GlucoseConcentrationUnit.MOLES_PER_LITER,
                    type = type,
                    sampleLocation = 1,
                    sensorStatus = status,
                ),
                null,
            )
        val reading = record(1)
        val control = record(2, type = 10)
        val disowned = record(3, status = 1 shl 5)

        assertNull(glucoseSaveBlockedReason(listOf(control, reading)))
        assertEquals(
            R.string.direct_bluetooth_control_solution_only,
            glucoseSaveBlockedReason(listOf(control, control.copy())),
        )
        assertEquals(R.string.direct_bluetooth_reading_invalid, glucoseSaveBlockedReason(listOf(disowned)))
        assertEquals(R.string.direct_bluetooth_reading_invalid, glucoseSaveBlockedReason(listOf(control, disowned)))
        assertEquals(
            R.string.m3_sync_reading_incomplete,
            glucoseSaveBlockedReason(listOf(control, record(4, concentration = null))),
        )

        // A transfer with readings in it counts what Save leaves out of the records it received,
        // and one with nothing to save leaves that to the line above.
        assertEquals(0, glucoseLeftOut(listOf(reading, record(5))))
        assertEquals(1, glucoseLeftOut(listOf(control, reading)))
        assertEquals(2, glucoseLeftOut(listOf(control, reading, disowned)))
        assertEquals(0, glucoseLeftOut(listOf(control, disowned)))
        // A record without a figure is not saved as an empty glucose row, and the line counts it.
        assertEquals(1, glucoseLeftOut(listOf(reading, record(6, concentration = null))))
        assertEquals(
            R.string.direct_bluetooth_reading_invalid,
            glucoseSaveBlockedReason(listOf(record(7, status = 1 shl 1, concentration = null))),
        )
    }

    /** A Save that found every record of the transfer already in the journal does not say "saved". */
    @Test
    fun aGlucoseSaveThatWroteNothingSaysTheReadingsWereThereAlready() {
        assertEquals(R.string.direct_bluetooth_saved, glucoseSavedRes(3))
        assertEquals(R.string.direct_bluetooth_glucose_already_saved, glucoseSavedRes(0))
    }

    /**
     * A cuff whose memory ends on a failed measurement. The reading on the card cannot be saved, and
     * that does not grey out Save for the mornings before it: they are saved without it, and the
     * other person's readings a Save leaves behind are counted for the card to say.
     */
    @Test
    fun aFailedLastReadingDoesNotTakeTheEarlierOnesWithIt() {
        val monday = pressure(arrival - 2 * day, user = 1)
        val theirs = pressure(arrival - day - hour, user = 2)
        val tuesday = pressure(arrival - day, user = 1)
        val failed = DirectProfileMeasurement.BloodPressure(pressure(arrival, user = 1).value.copy(systolic = null))
        val stored = handedOver(monday, theirs, tuesday)

        assertEquals(listOf(monday, tuesday), clinicalReadingsToSave(stored, failed))
        assertEquals(listOf(monday, tuesday), clinicalReadingsToSave(stored, tuesday))
        assertTrue(clinicalReadingsToSave(emptyList(), failed).isEmpty())
        assertEquals(1, stored.leftBeside(failed, ::isSameClinicalReading, DirectProfileMeasurement::userId))
        assertEquals(2, stored.leftBeside(theirs, ::isSameClinicalReading, DirectProfileMeasurement::userId))
        assertEquals(0, listOf(monday).leftBeside(tuesday, ::isSameClinicalReading, DirectProfileMeasurement::userId))
    }

    /**
     * A search that could not start. With nothing linked it is the connection's failure; beside an
     * open link it is not, and the page says so on the line under the search button, where "nothing
     * found" would have answered for a search that never ran. The next search clears it.
     */
    @Test
    fun aSearchThatFailedBesideALinkIsSaidWithoutTouchingTheLink() {
        assertTrue(searchFailureIsTheConnections(linkOpen = false))
        assertFalse(searchFailureIsTheConnections(linkOpen = true))

        assertEquals(
            R.string.m3_sync_error_scan,
            searchStatusRes(searched = true, scanning = false, found = false, failed = true),
        )
        assertEquals(
            R.string.m3_sync_error_scan,
            searchStatusRes(searched = true, scanning = false, found = true, failed = true),
        )
        assertEquals(
            R.string.direct_bluetooth_searching,
            searchStatusRes(searched = true, scanning = true, found = false, failed = false),
        )
        assertEquals(
            R.string.device_picker_empty,
            searchStatusRes(searched = true, scanning = false, found = false, failed = false),
        )
        assertNull(searchStatusRes(searched = false, scanning = false, found = false, failed = false))
        assertNull(searchStatusRes(searched = true, scanning = false, found = true, failed = false))
    }

    /**
     * What a link still says after the screen hung up is not delivered — and the question is asked
     * when the reading's turn comes, not when it was queued, so the packet already waiting as
     * Disconnect was tapped is dropped too.
     */
    @Test
    fun aReadingQueuedBeforeTheHangUpIsNotDelivered() {
        val hungUp = booleanArrayOf(false)
        val delivered = mutableListOf<String>()
        val queued = unlessHungUp({ hungUp[0] }) { delivered += "reading" }

        queued.run()
        assertEquals(listOf("reading"), delivered)
        hungUp[0] = true
        queued.run()
        assertEquals(listOf("reading"), delivered)
    }

    /**
     * A finished glucose transfer is never "waiting for data": not an empty meter's, and not one
     * whose records were saved and cleared off the page.
     */
    @Test
    fun aFinishedGlucoseTransferIsNotWaitingForData() {
        assertEquals(ConnectedLine.WAITING, connectedLine(nothingOnPage = true, null, glucoseWaiting = false))
        assertEquals(ConnectedLine.TRANSFER_DONE, connectedLine(nothingOnPage = true, 0, glucoseWaiting = false))
        assertEquals(ConnectedLine.TRANSFER_DONE, connectedLine(nothingOnPage = true, 3, glucoseWaiting = false))
        assertEquals(ConnectedLine.CONNECTED, connectedLine(nothingOnPage = false, 3, glucoseWaiting = true))
        assertEquals(ConnectedLine.CONNECTED, connectedLine(nothingOnPage = false, null, glucoseWaiting = false))
    }
}
