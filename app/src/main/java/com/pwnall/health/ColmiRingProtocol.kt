package com.pwnall.health

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

enum class ColmiRingModel {
    R02,
    R06,
    R10,
}

enum class ColmiLiveMetric(internal val wireId: Int) {
    HEART_RATE(1),
    BLOOD_OXYGEN(3),
}

enum class ColmiLiveAction {
    START,
    CONTINUE,
    STOP,
}

data class ColmiBatteryStatus(
    val percent: Int,
    val charging: Boolean,
)

sealed interface ColmiLiveResponse {
    val metric: ColmiLiveMetric

    data class Reading(
        override val metric: ColmiLiveMetric,
        val value: Int,
    ) : ColmiLiveResponse

    data class DeviceError(
        override val metric: ColmiLiveMetric,
        val code: Int,
    ) : ColmiLiveResponse
}

data class ColmiSportSample(
    /** The ring stores local wall-clock fields and does not transmit an offset or zone. */
    val startedAtLocal: LocalDateTime,
    /** 0..95, one bucket per 15 minutes. Kept for deterministic duplicate handling. */
    val intervalIndex: Int,
    val steps: Int,
    val distanceMeters: Int,
    /** Kept raw: the meaning/scaling differs between observed firmware generations. */
    val calorieCounterRaw: Int,
    val newCalorieProtocol: Boolean,
)

enum class ColmiHistoryRejectReason {
    INVALID_FRAME,
    EXPECTED_HEADER,
    INVALID_HEADER,
    INVALID_RECORD,
    OUT_OF_SEQUENCE,
}

sealed interface ColmiSportHistoryResult {
    data object AwaitingMore : ColmiSportHistoryResult

    data object NoData : ColmiSportHistoryResult

    data class Complete(val samples: List<ColmiSportSample>) : ColmiSportHistoryResult

    data class Rejected(val reason: ColmiHistoryRejectReason) : ColmiSportHistoryResult
}

/**
 * Clean-room core for the locally accessible COLMI R02-family BLE protocol.
 *
 * This object intentionally contains no Android Bluetooth types. Runtime code must still discover
 * [serviceUuid], subscribe to [notifyUuid], and serialize writes to [writeUuid]. A matching name is
 * not enough to claim support: [identifyModel] also requires the proprietary GATT service.
 */
object BluetoothColmiRing {
    val serviceUuid: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val writeUuid: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val notifyUuid: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * The independent MIT implementation explicitly reports these three model families as fully
     * compatible. R03/R09 and arbitrary QRing rebrands are outside this gate: their traffic has not
     * been confirmed against this protocol.
     */
    fun identifyModel(localName: String?, discoveredServices: Collection<UUID>): ColmiRingModel? {
        if (serviceUuid !in discoveredServices) return null
        return identifyName(localName)
    }

    /** Scan-time candidate only; runtime must still call [identifyModel] after GATT discovery. */
    internal fun identifyName(localName: String?): ColmiRingModel? {
        val modelName = DEVICE_NAME.matchEntire(localName ?: return null)?.groupValues?.get(1)
            ?: return null
        return ColmiRingModel.entries.firstOrNull { it.name.equals(modelName, ignoreCase = true) }
    }

    fun batteryRequest(): ByteArray = encodeFrame(COMMAND_BATTERY)

    fun sportHistoryRequest(dayOffset: Int): ByteArray {
        require(dayOffset in 0..U8_MAX) { "dayOffset must fit in one byte" }
        return encodeFrame(
            command = COMMAND_SPORT_HISTORY,
            data = intArrayOf(dayOffset, 0x0F, 0x00, 0x5F, 0x01),
        )
    }

    fun liveRequest(metric: ColmiLiveMetric, action: ColmiLiveAction): ByteArray = when (action) {
        ColmiLiveAction.START -> encodeFrame(
            COMMAND_LIVE_CONTROL,
            intArrayOf(metric.wireId, ACTION_START),
        )

        ColmiLiveAction.CONTINUE -> encodeFrame(
            COMMAND_LIVE_CONTROL,
            intArrayOf(metric.wireId, ACTION_CONTINUE),
        )

        ColmiLiveAction.STOP -> encodeFrame(
            COMMAND_LIVE_STOP,
            intArrayOf(metric.wireId, 0x00, 0x00),
        )
    }

    fun parseBattery(payload: ByteArray): ColmiBatteryStatus? {
        val frame = decodeFrame(payload, COMMAND_BATTERY) ?: return null
        if (frame.deviceError) return null
        val percent = frame.data.u8(0)
        val charging = frame.data.u8(1)
        if (percent !in 0..100 || charging !in 0..1) return null
        return ColmiBatteryStatus(percent, charging == 1)
    }

    fun parseLive(payload: ByteArray): ColmiLiveResponse? {
        val frame = decodeFrame(payload, COMMAND_LIVE_CONTROL) ?: return null
        if (frame.deviceError) return null
        val metric = ColmiLiveMetric.entries.firstOrNull { it.wireId == frame.data.u8(0) }
            ?: return null
        val errorCode = frame.data.u8(1)
        return if (errorCode == 0) {
            ColmiLiveResponse.Reading(metric, frame.data.u8(2))
        } else {
            ColmiLiveResponse.DeviceError(metric, errorCode)
        }
    }

    internal fun decodeSportFrame(payload: ByteArray): ColmiFrame? =
        decodeFrame(payload, COMMAND_SPORT_HISTORY)

    private fun encodeFrame(command: Int, data: IntArray = intArrayOf()): ByteArray {
        require(command in 0..COMMAND_MASK) { "command must fit in seven bits" }
        require(data.size <= DATA_BYTES) { "data must fit in a COLMI frame" }
        require(data.all { it in 0..U8_MAX }) { "every data value must fit in one byte" }

        return ByteArray(FRAME_BYTES).also { frame ->
            frame[0] = command.toByte()
            data.forEachIndexed { index, value -> frame[index + 1] = value.toByte() }
            frame[CHECKSUM_INDEX] = checksum(frame).toByte()
        }
    }

    private fun decodeFrame(payload: ByteArray, expectedCommand: Int): ColmiFrame? {
        if (frameCommand(payload) != expectedCommand) return null
        return ColmiFrame(
            deviceError = payload.u8(0) and ERROR_FLAG != 0,
            data = payload.copyOfRange(1, CHECKSUM_INDEX),
        )
    }

    /** The command of a well-formed frame, whichever it is; null for one of the wrong size or sum. */
    internal fun frameCommand(payload: ByteArray): Int? {
        if (payload.size != FRAME_BYTES || checksum(payload) != payload.u8(CHECKSUM_INDEX)) return null
        return payload.u8(0) and COMMAND_MASK
    }

    private fun checksum(frame: ByteArray): Int {
        var sum = 0
        for (index in 0 until minOf(frame.size, CHECKSUM_INDEX)) {
            sum += frame.u8(index)
        }
        return sum and U8_MAX
    }

    private val DEVICE_NAME = Regex("^(R02|R06|R10)_[0-9A-F]{4}$", RegexOption.IGNORE_CASE)
    private const val FRAME_BYTES = 16
    private const val DATA_BYTES = 14
    private const val CHECKSUM_INDEX = 15
    private const val ERROR_FLAG = 0x80
    private const val COMMAND_MASK = 0x7F
    private const val U8_MAX = 0xFF

    internal const val COMMAND_BATTERY = 0x03
    internal const val COMMAND_SPORT_HISTORY = 0x43
    private const val COMMAND_LIVE_CONTROL = 0x69
    private const val COMMAND_LIVE_STOP = 0x6A
    private const val ACTION_START = 1
    private const val ACTION_CONTINUE = 3
}

/** Strict, reset-on-error assembler for the multi-notification step-history response. */
class ColmiSportHistoryAssembler {
    private var expectedRecords: Int? = null
    private var nextRecordIndex = 0
    private var newCalorieProtocol = false
    private val samples = mutableListOf<ColmiSportSample>()

    fun accept(payload: ByteArray): ColmiSportHistoryResult {
        val frame = BluetoothColmiRing.decodeSportFrame(payload)
            ?: return reject(ColmiHistoryRejectReason.INVALID_FRAME)
        if (frame.deviceError) return reject(ColmiHistoryRejectReason.INVALID_FRAME)

        return if (expectedRecords == null) {
            acceptHeader(frame.data)
        } else {
            acceptRecord(frame.data)
        }
    }

    fun reset() {
        expectedRecords = null
        nextRecordIndex = 0
        newCalorieProtocol = false
        samples.clear()
    }

    private fun acceptHeader(data: ByteArray): ColmiSportHistoryResult = when (data.u8(0)) {
        NO_DATA_MARKER -> {
            reset()
            ColmiSportHistoryResult.NoData
        }

        HEADER_MARKER -> {
            val recordCount = data.u8(1)
            val calorieProtocol = data.u8(2)
            if (recordCount == 0 || calorieProtocol !in 0..1) {
                reject(ColmiHistoryRejectReason.INVALID_HEADER)
            } else {
                expectedRecords = recordCount
                newCalorieProtocol = calorieProtocol == 1
                ColmiSportHistoryResult.AwaitingMore
            }
        }

        else -> reject(ColmiHistoryRejectReason.EXPECTED_HEADER)
    }

    // Every failed invariant resets the batch immediately; nesting those checks obscures the state.
    private fun acceptRecord(data: ByteArray): ColmiSportHistoryResult {
        val expected = expectedRecords ?: return reject(ColmiHistoryRejectReason.EXPECTED_HEADER)
        val recordIndex = data.u8(4)
        val recordCount = data.u8(5)
        if (recordCount != expected || recordIndex != nextRecordIndex) {
            return reject(ColmiHistoryRejectReason.OUT_OF_SEQUENCE)
        }

        val sample = parseSample(data) ?: return reject(ColmiHistoryRejectReason.INVALID_RECORD)
        if (samples.lastOrNull()?.startedAtLocal?.let { !sample.startedAtLocal.isAfter(it) } == true) {
            return reject(ColmiHistoryRejectReason.OUT_OF_SEQUENCE)
        }
        samples += sample
        nextRecordIndex += 1

        return if (nextRecordIndex == expected) {
            val complete = ColmiSportHistoryResult.Complete(samples.toList())
            reset()
            complete
        } else {
            ColmiSportHistoryResult.AwaitingMore
        }
    }

    // Wire fields are validated independently so malformed dates never become partial samples.
    private fun parseSample(data: ByteArray): ColmiSportSample? {
        val year = decodeBcd(data.u8(0))?.plus(BASE_YEAR) ?: return null
        val month = decodeBcd(data.u8(1)) ?: return null
        val day = decodeBcd(data.u8(2)) ?: return null
        val interval = data.u8(3)
        if (interval !in 0 until INTERVALS_PER_DAY) return null
        val startedAt = runCatching {
            LocalDateTime.of(
                year,
                month,
                day,
                interval / INTERVALS_PER_HOUR,
                interval % INTERVALS_PER_HOUR * MINUTES_PER_INTERVAL,
            )
        }.getOrNull() ?: return null

        return ColmiSportSample(
            startedAtLocal = startedAt,
            intervalIndex = interval,
            calorieCounterRaw = data.u16(6),
            steps = data.u16(8),
            distanceMeters = data.u16(10),
            newCalorieProtocol = newCalorieProtocol,
        )
    }

    private fun reject(reason: ColmiHistoryRejectReason): ColmiSportHistoryResult.Rejected {
        reset()
        return ColmiSportHistoryResult.Rejected(reason)
    }

    private fun decodeBcd(raw: Int): Int? {
        val high = raw ushr 4
        val low = raw and 0x0F
        if (high > 9 || low > 9) return null
        return high * 10 + low
    }

    private companion object {
        const val HEADER_MARKER = 0xF0
        const val NO_DATA_MARKER = 0xFF
        const val BASE_YEAR = 2000
        const val INTERVALS_PER_DAY = 96
        const val INTERVALS_PER_HOUR = 4
        const val MINUTES_PER_INTERVAL = 15
    }
}

/**
 * The ring's records carry the date its own clock says, and a ring nobody has ever synced says
 * 2000-01-01. This app does not set the ring's time, so a day it reports is believed only when the
 * phone agrees to within a day — room for a sync just after midnight or across a zone change — and
 * anything further off is not a day at all. Filing it would have let the MAX merge of daily steps
 * adopt a quarter-century-old date for good.
 */
internal fun List<ColmiSportSample>.withinADayOf(today: LocalDate): List<ColmiSportSample> =
    filter { abs(ChronoUnit.DAYS.between(today, it.startedAtLocal.toLocalDate())) <= 1L }

internal class ColmiFrame(
    val deviceError: Boolean,
    val data: ByteArray,
)

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
