package com.pwnall.health

import kotlin.math.pow

/** Decoders for the decimal floating-point formats used by Bluetooth health profiles. */
internal object Ieee11073 {
    private val SPECIAL_SFLOATS = setOf(0x07FE, 0x07FF, 0x0800, 0x0801, 0x0802)
    private val SPECIAL_FLOATS = setOf(
        0x007FFFFEL,
        0x007FFFFFL,
        0x00800000L,
        0x00800001L,
        0x00800002L,
    )

    /**
     * IEEE 11073 16-bit SFLOAT: a signed 12-bit mantissa and signed 4-bit base-10 exponent.
     *
     * Bluetooth assigns five non-numeric wire values (NaN, NRes, both infinities and RFU). They
     * all become null: none may leak into a journal as an ordinary, very large measurement.
     */
    fun decodeSfloat(raw: Int): Double? {
        val value = raw and 0xFFFF
        if (value in SPECIAL_SFLOATS) return null

        val unsignedMantissa = value and 0x0FFF
        val mantissa = if (unsignedMantissa and 0x0800 != 0) {
            unsignedMantissa - 0x1000
        } else {
            unsignedMantissa
        }
        val unsignedExponent = value ushr 12
        val exponent = if (unsignedExponent and 0x08 != 0) {
            unsignedExponent - 0x10
        } else {
            unsignedExponent
        }
        return (mantissa * 10.0.pow(exponent.toDouble())).takeIf { it.isFinite() }
    }

    /** IEEE 11073 32-bit FLOAT: signed 24-bit mantissa and signed 8-bit base-10 exponent. */
    fun decodeFloat(raw: Long): Double? {
        val value = raw and 0xFFFF_FFFFL
        if (value in SPECIAL_FLOATS) return null

        val unsignedMantissa = (value and 0x00FF_FFFFL).toInt()
        val mantissa = if (unsignedMantissa and 0x0080_0000 != 0) {
            unsignedMantissa - 0x0100_0000
        } else {
            unsignedMantissa
        }
        val unsignedExponent = (value ushr 24).toInt()
        val exponent = if (unsignedExponent and 0x80 != 0) {
            unsignedExponent - 0x100
        } else {
            unsignedExponent
        }
        return (mantissa * 10.0.pow(exponent.toDouble())).takeIf { it.isFinite() }
    }
}
