package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseRacpTest {
    @Test
    fun buildsReportAllAndParsesOnlyItsTerminalResponse() {
        assertArrayEquals(byteArrayOf(0x01, 0x01), BluetoothGlucoseRacp.reportAllRecords)
        assertEquals(
            GlucoseRacpCompletion.Success,
            BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x01)),
        )
        assertEquals(
            GlucoseRacpCompletion.NoRecords,
            BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x06)),
        )
        assertEquals(
            GlucoseRacpCompletion.Error(8),
            BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x08)),
        )
        // 0x09 "Operand not supported" is the last defined code: it reaches the screen as
        // glucose_racp:9, not as the malformed reply's glucose_racp_response.
        assertEquals(
            GlucoseRacpCompletion.Error(9),
            BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x09)),
        )
        assertNull(BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x0A)))
        assertNull(BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 0x01, 0x00)))
        assertNull(BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x05, 0x00, 1, 0)))
        assertNull(BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 2, 1)))
        assertNull(BluetoothGlucoseRacp.parseReportCompletion(byteArrayOf(0x06, 0x00, 1)))
    }
}
