package com.pwnall.health

import java.util.UUID

/** A bounded, versioned health snapshot produced locally by the bundled Bangle.js 2 app. */
data class BangleJsHealthSnapshot(
    val timestampMillis: Long,
    val steps: Int,
    val heartRateBpm: Int?,
    val batteryPercent: Int,
)

enum class BangleJsRejectReason {
    EMPTY_FRAME,
    FRAME_TOO_LONG,
    INPUT_CHUNK_TOO_LONG,
    NON_ASCII,
    TRUNCATED_FRAME,
    MALFORMED_JSON,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_MESSAGE_TYPE,
    INVALID_FIELD_TYPE,
    VALUE_OUT_OF_RANGE,
}

sealed interface BangleJsFrameResult {
    data class Snapshot(val value: BangleJsHealthSnapshot) : BangleJsFrameResult

    data class Rejected(val reason: BangleJsRejectReason) : BangleJsFrameResult
}

/**
 * Protocol constants and strict parser for the bundled Bangle.js 2 Nordic UART app.
 *
 * The BLE UUIDs are the standard Nordic UART Service UUIDs documented by Espruino. This object is
 * deliberately transport-free: discovering GATT, subscribing and writing are runtime concerns.
 *
 * @see <a href="https://www.espruino.com/BLE%20UART">Espruino BLE UART documentation</a>
 */
object BangleJsOfflineProtocol {
    val serviceUuid: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val receiveUuid: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val transmitUuid: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    const val VERSION = 1
    const val MAX_FRAME_BYTES = 192
    const val MAX_INPUT_CHUNK_BYTES = 1_024

    fun snapshotRequest(): ByteArray = "PWN_HEALTH 1\n".toByteArray(Charsets.US_ASCII)

    /**
     * Scan-time match on the name Espruino advertises: "Bangle.js" and the last four hex digits of
     * the address. Only a valid snapshot over the Nordic UART proves the watch runs the app.
     */
    fun isAdvertisedName(localName: String?): Boolean = localName != null && ADVERTISED_NAME.matches(localName)

    fun parseLine(line: String): BangleJsFrameResult {
        if (line.isEmpty()) return rejected(BangleJsRejectReason.EMPTY_FRAME)
        if (line.length > MAX_FRAME_BYTES) return rejected(BangleJsRejectReason.FRAME_TOO_LONG)
        if (line.any { it.code > ASCII_MAX }) return rejected(BangleJsRejectReason.NON_ASCII)

        return try {
            decodeSnapshot(FlatJsonObjectParser(line).parse())
        } catch (failure: BangleJsFrameFailure) {
            rejected(failure.reason)
        }
    }

    private fun decodeSnapshot(values: Map<String, JsonScalar>): BangleJsFrameResult {
        values.keys.firstOrNull { it !in REQUIRED_FIELDS }?.let {
            failProtocol(BangleJsRejectReason.UNKNOWN_FIELD)
        }
        if (!values.keys.containsAll(REQUIRED_FIELDS)) {
            failProtocol(BangleJsRejectReason.MISSING_FIELD)
        }

        val version = values.requiredInteger("v")
        if (version != VERSION.toLong()) {
            failProtocol(BangleJsRejectReason.UNSUPPORTED_VERSION)
        }
        val type = (values.getValue("type") as? JsonScalar.Text)?.value
            ?: failProtocol(BangleJsRejectReason.INVALID_FIELD_TYPE)
        if (type != MESSAGE_TYPE) {
            failProtocol(BangleJsRejectReason.UNSUPPORTED_MESSAGE_TYPE)
        }

        val timestamp = values.requiredInteger("ts", MIN_TIMESTAMP_MS..MAX_TIMESTAMP_MS)
        val steps = values.requiredInteger("steps", 0L..MAX_DAILY_STEPS).toInt()
        val heartRate = values.optionalHeartRate()
        val battery = values.requiredInteger("battery", 0L..100L).toInt()
        return BangleJsFrameResult.Snapshot(
            BangleJsHealthSnapshot(
                timestampMillis = timestamp,
                steps = steps,
                heartRateBpm = heartRate,
                batteryPercent = battery,
            ),
        )
    }

    private fun Map<String, JsonScalar>.requiredInteger(
        name: String,
        validRange: LongRange? = null,
    ): Long {
        val raw = (getValue(name) as? JsonScalar.Number)?.raw
            ?: failProtocol(BangleJsRejectReason.INVALID_FIELD_TYPE)
        if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
            failProtocol(BangleJsRejectReason.INVALID_FIELD_TYPE)
        }
        val value = raw.toLongOrNull()
            ?: failProtocol(BangleJsRejectReason.VALUE_OUT_OF_RANGE)
        if (validRange != null && value !in validRange) {
            failProtocol(BangleJsRejectReason.VALUE_OUT_OF_RANGE)
        }
        return value
    }

    private fun Map<String, JsonScalar>.optionalHeartRate(): Int? = when (val value = getValue("hr")) {
        JsonScalar.Null -> null

        is JsonScalar.Number -> {
            val parsed = mapOf("hr" to value).requiredInteger("hr", MIN_HEART_RATE..MAX_HEART_RATE)
            parsed.toInt()
        }

        else -> failProtocol(BangleJsRejectReason.INVALID_FIELD_TYPE)
    }

    private fun rejected(reason: BangleJsRejectReason) = BangleJsFrameResult.Rejected(reason)

    private fun failProtocol(reason: BangleJsRejectReason): Nothing = throw BangleJsFrameFailure(reason)

    private const val ASCII_MAX = 0x7F
    private const val MESSAGE_TYPE = "health"
    private const val MIN_TIMESTAMP_MS = 946_684_800_000L // 2000-01-01T00:00:00Z
    private const val MAX_TIMESTAMP_MS = 4_102_444_800_000L // 2100-01-01T00:00:00Z
    private const val MAX_DAILY_STEPS = 1_000_000L
    private const val MIN_HEART_RATE = 20L
    private const val MAX_HEART_RATE = 250L
    private val REQUIRED_FIELDS = setOf("v", "type", "ts", "steps", "hr", "battery")
    private val ADVERTISED_NAME = Regex("""Bangle\.js [0-9a-f]{4}""", RegexOption.IGNORE_CASE)
}

/** Incrementally turns arbitrarily split Nordic UART notifications into bounded protocol frames. */
class BangleJsLineDecoder {
    private val buffer = ByteArray(BangleJsOfflineProtocol.MAX_FRAME_BYTES)
    private var length = 0
    private var droppingReason: BangleJsRejectReason? = null

    fun feed(chunk: ByteArray): List<BangleJsFrameResult> {
        if (chunk.size > BangleJsOfflineProtocol.MAX_INPUT_CHUNK_BYTES) {
            reset()
            return listOf(BangleJsFrameResult.Rejected(BangleJsRejectReason.INPUT_CHUNK_TOO_LONG))
        }

        val results = mutableListOf<BangleJsFrameResult>()
        chunk.forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            when {
                value == NEWLINE -> finishLine(results)
                droppingReason != null -> Unit
                value > ASCII_MAX -> startDropping(BangleJsRejectReason.NON_ASCII)
                length == buffer.size -> startDropping(BangleJsRejectReason.FRAME_TOO_LONG)
                else -> buffer[length++] = byte
            }
        }
        return results
    }

    /** Rejects and clears a partial frame when a BLE stream disconnects. */
    fun endOfStream(): BangleJsFrameResult? {
        val reason = droppingReason ?: if (length > 0) BangleJsRejectReason.TRUNCATED_FRAME else null
        reset()
        return reason?.let { BangleJsFrameResult.Rejected(it) }
    }

    fun reset() {
        length = 0
        droppingReason = null
    }

    private fun finishLine(results: MutableList<BangleJsFrameResult>) {
        val rejection = droppingReason
        if (rejection != null) {
            results += BangleJsFrameResult.Rejected(rejection)
        } else {
            val contentLength = if (length > 0 && buffer[length - 1].toInt() == CARRIAGE_RETURN) {
                length - 1
            } else {
                length
            }
            val line = String(buffer, 0, contentLength, Charsets.US_ASCII)
            results += BangleJsOfflineProtocol.parseLine(line)
        }
        reset()
    }

    private fun startDropping(reason: BangleJsRejectReason) {
        droppingReason = reason
        length = 0
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val ASCII_MAX = 0x7F
        const val NEWLINE = 0x0A
        const val CARRIAGE_RETURN = 0x0D
    }
}

private sealed interface JsonScalar {
    data class Text(val value: String) : JsonScalar

    data class Number(val raw: String) : JsonScalar

    data class BooleanValue(val value: Boolean) : JsonScalar

    data object Null : JsonScalar
}

private class FlatJsonObjectParser(private val source: String) {
    private var offset = 0

    fun parse(): Map<String, JsonScalar> {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        if (consume('}')) return finish(emptyMap())

        val values = linkedMapOf<String, JsonScalar>()
        while (true) {
            val key = readString()
            if (values.containsKey(key)) fail(BangleJsRejectReason.DUPLICATE_FIELD)
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[key] = readScalar()
            skipWhitespace()
            if (consume('}')) return finish(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun readScalar(): JsonScalar {
        if (offset >= source.length) fail()
        return when (source[offset]) {
            '"' -> JsonScalar.Text(readString())
            'n' -> readLiteral("null", JsonScalar.Null)
            't' -> readLiteral("true", JsonScalar.BooleanValue(true))
            'f' -> readLiteral("false", JsonScalar.BooleanValue(false))
            '-', in '0'..'9' -> JsonScalar.Number(readNumber())
            else -> fail()
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < source.length) {
            val current = source[offset++]
            when {
                current == '"' -> return result.toString()
                current == '\\' -> appendEscape(result)
                current.code < JSON_SPACE || current.isSurrogate() -> fail()
                else -> result.append(current)
            }
        }
        fail()
    }

    private fun appendEscape(result: StringBuilder) {
        if (offset >= source.length) fail()
        when (val escaped = source[offset++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> fail()
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = readHexCodeUnit()
        when {
            first in HIGH_SURROGATES -> {
                if (!consume('\\') || !consume('u')) fail()
                val second = readHexCodeUnit()
                if (second !in LOW_SURROGATES) fail()
                result.append(first.toChar()).append(second.toChar())
            }

            first in LOW_SURROGATES -> fail()

            else -> result.append(first.toChar())
        }
    }

    private fun readHexCodeUnit(): Int {
        if (offset + HEX_DIGITS > source.length) fail()
        var value = 0
        repeat(HEX_DIGITS) {
            val digit = source[offset++].digitToIntOrNull(HEX_RADIX) ?: fail()
            value = (value shl HEX_BITS_PER_DIGIT) or digit
        }
        return value
    }

    private fun readNumber(): String {
        val start = offset
        consume('-')
        if (consume('0')) {
            if (peek() in '0'..'9') fail()
        } else {
            requireDigits()
        }
        if (consume('.')) requireDigits()
        if (peek() == 'e' || peek() == 'E') {
            offset++
            if (peek() == '+' || peek() == '-') offset++
            requireDigits()
        }
        return source.substring(start, offset)
    }

    private fun requireDigits() {
        val start = offset
        while (peek() in '0'..'9') offset++
        if (start == offset) fail()
    }

    private fun <T : JsonScalar> readLiteral(text: String, value: T): T {
        if (!source.regionMatches(offset, text, 0, text.length)) fail()
        offset += text.length
        return value
    }

    private fun finish(values: Map<String, JsonScalar>): Map<String, JsonScalar> {
        skipWhitespace()
        if (offset != source.length) fail()
        return values
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) fail()
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        offset++
        return true
    }

    private fun peek(): Char? = source.getOrNull(offset)

    private fun skipWhitespace() {
        while (peek() in JSON_WHITESPACE) offset++
    }

    private fun fail(reason: BangleJsRejectReason = BangleJsRejectReason.MALFORMED_JSON): Nothing =
        throw BangleJsFrameFailure(reason)

    private companion object {
        const val JSON_SPACE = 0x20
        const val HEX_DIGITS = 4
        const val HEX_RADIX = 16
        const val HEX_BITS_PER_DIGIT = 4
        val HIGH_SURROGATES = 0xD800..0xDBFF
        val LOW_SURROGATES = 0xDC00..0xDFFF
        val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
    }
}

private class BangleJsFrameFailure(val reason: BangleJsRejectReason) : RuntimeException()
