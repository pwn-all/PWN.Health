package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * A device sync is repeated, and the journal must not grow with each repetition: a glucose meter
 * answers every sync with its whole memory, and a ring's snapshot can be saved twice. Run on Room
 * over SQLite, through [saveGlucoseTransfer] and [saveDirectBleStepSync], the two writes the
 * devices page makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DirectMeasurementSaveRoomTest {
    private lateinit var db: StepsDb
    private val zone = TimeZone.getDefault()

    @Before
    fun open() {
        // A believed meter record is dated in the phone's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
    }

    @After
    fun close() {
        db.close()
        TimeZone.setDefault(zone)
    }

    private suspend fun measurementRows() = db.dao().journalAround(KIND_MEASUREMENT, 0L, Long.MAX_VALUE)

    private fun reading(sequence: Int, minute: Int, mmolL: Double, clockSet: Boolean = true) = GlucoseRecord(
        GlucoseMeasurement(
            sequenceNumber = sequence,
            baseTime = LocalDateTime.of(2026, 9, 4, 10, minute),
            concentration = mmolL / 1_000.0,
            concentrationUnit = GlucoseConcentrationUnit.MOLES_PER_LITER,
            type = 1,
            sampleLocation = 1,
            deviceClockSet = clockSet,
        ),
        context = null,
    )

    private suspend fun save(records: List<GlucoseRecord>) =
        db.saveGlucoseTransfer(records, title = "Glucose", deviceName = "Meter", sourceId = "BLE_0123456789abcdef")

    @Test
    fun aMeterSendingItsWholeMemoryAgainWritesNothingTheSecondTime() = runBlocking {
        val memory = listOf(reading(7, minute = 7, mmolL = 5.5), reading(8, minute = 40, mmolL = 6.1))

        assertEquals(2, save(memory))
        assertEquals(0, save(memory))
        assertEquals(2, measurementRows().size)
    }

    /**
     * A meter running fast: its newest record is disbelieved by a sync a minute after the test and
     * dated by the phone, believed by the next sync and dated by the meter. Two payloads and two
     * times, one record; the batch save alone would write it twice.
     */
    @Test
    fun aRecordWhoseClockWasJudgedAgainBetweenSyncsIsSavedOnce() = runBlocking {
        assertEquals(1, save(listOf(reading(7, minute = 7, mmolL = 5.5, clockSet = false))))
        assertEquals(0, save(listOf(reading(7, minute = 7, mmolL = 5.5, clockSet = true))))
        assertEquals(1, measurementRows().size)
    }

    /** After a reinstall the reading comes back from Health Connect: no source, one figure. */
    @Test
    fun aReadingRestoredFromHealthConnectIsNotSavedAgainByTheMeter() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                JournalEntry(
                    day = "2026-09-04",
                    occurredAt = LocalDateTime.of(2026, 9, 4, 10, 7).toInstant(ZoneOffset.UTC).toEpochMilli(),
                    kind = KIND_MEASUREMENT,
                    title = "Glucose",
                    calories = 0.0,
                    measurementPayloadJson = """{"concentrationMmolL":5.5}""",
                ),
            ),
        )

        assertEquals(0, save(listOf(reading(7, minute = 7, mmolL = 5.5))))
        // Another figure at that minute is another reading.
        assertEquals(1, save(listOf(reading(9, minute = 7, mmolL = 7.2))))
    }

    @Test
    fun aRingSnapshotSavedTwiceKeepsOneJournalRow() = runBlocking {
        val syncedAt = LocalDateTime.of(2026, 9, 4, 21, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val day = DirectBleDaySteps(sourceId = "COLMI_0123456789abcdef", day = "2026-09-04", steps = 8_412)
        val entry = JournalEntry(
            day = "2026-09-04",
            occurredAt = syncedAt,
            kind = KIND_MEASUREMENT,
            title = "Ring history",
            calories = 0.0,
            measurementSource = "BLUETOOTH_COLMI",
            measurementPayloadJson = """{"schema":1,"syncedAt":$syncedAt,"steps":8412}""",
            note = "COLMI R02",
        )

        db.saveDirectBleStepSync(listOf(day), entry)
        db.saveDirectBleStepSync(listOf(day), entry)

        assertEquals(1, measurementRows().size)
    }
}
