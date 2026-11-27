package com.pwnall.health

sealed interface GlucoseRacpCompletion {
    data object Success : GlucoseRacpCompletion

    data object NoRecords : GlucoseRacpCompletion

    data class Error(val responseCode: Int) : GlucoseRacpCompletion
}

/** Strict subset used by the Report Stored Records / All procedure. */
object BluetoothGlucoseRacp {
    val reportAllRecords: ByteArray = byteArrayOf(
        OP_REPORT_STORED_RECORDS.toByte(),
        OPERATOR_ALL_RECORDS.toByte(),
    )

    fun parseReportCompletion(payload: ByteArray): GlucoseRacpCompletion? {
        if (payload.size != RESPONSE_BYTES) return null
        if (payload.u8(0) != OP_RESPONSE_CODE || payload.u8(1) != OPERATOR_NULL) return null
        if (payload.u8(2) != OP_REPORT_STORED_RECORDS) return null
        return when (val code = payload.u8(3)) {
            RESPONSE_SUCCESS -> GlucoseRacpCompletion.Success

            RESPONSE_NO_RECORDS -> GlucoseRacpCompletion.NoRecords

            in RESPONSE_SUCCESS..RESPONSE_OPERAND_NOT_SUPPORTED ->
                GlucoseRacpCompletion.Error(code)

            else -> null
        }
    }

    private const val OP_REPORT_STORED_RECORDS = 0x01
    private const val OP_RESPONSE_CODE = 0x06
    private const val OPERATOR_NULL = 0x00
    private const val OPERATOR_ALL_RECORDS = 0x01
    private const val RESPONSE_SUCCESS = 0x01
    private const val RESPONSE_NO_RECORDS = 0x06

    /** The last code GLS 1.0.1 defines; a lower bound would read "Operand not supported" as a malformed reply. */
    private const val RESPONSE_OPERAND_NOT_SUPPORTED = 0x09
    private const val RESPONSE_BYTES = 4
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
