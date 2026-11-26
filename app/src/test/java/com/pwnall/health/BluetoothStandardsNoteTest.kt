package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The note under the Bluetooth section names what the scanner speaks, the Keiser M Series
 * ([DirectDeviceKind.KEISER_M3I]) included: in every language, by the names that are not translated.
 */
class BluetoothStandardsNoteTest {
    @Test
    fun theNoteNamesKeiser() {
        val note = Regex("""<string name="direct_bluetooth_standards_note">([^<]*)</string>""")
        val folders = File("src/main/res").listFiles { file -> file.name.startsWith("values") }.orEmpty()
            .filter { note.containsMatchIn(File(it, "ui.xml").takeIf(File::exists)?.readText().orEmpty()) }
        assertEquals(16, folders.size)
        folders.forEach { folder ->
            val text = note.find(File(folder, "ui.xml").readText())?.groupValues?.get(1).orEmpty()
            assertTrue("${folder.name}: $text", "Keiser M Series" in text)
        }
        assertTrue(DirectDeviceKind.KEISER_M3I in DirectDeviceKind.entries)
    }

    /** The same for the watch app the scanner talks to ([DirectDeviceKind.BANGLE_JS2]). */
    @Test
    fun theNoteNamesBangleJs2() {
        val note = Regex("""<string name="direct_bluetooth_standards_note">([^<]*)</string>""")
        val texts = File("src/main/res").listFiles { file -> file.name.startsWith("values") }.orEmpty()
            .mapNotNull { folder -> File(folder, "ui.xml").takeIf(File::exists)?.readText() }
            .mapNotNull { text -> note.find(text)?.groupValues?.get(1) }
        assertEquals(16, texts.size)
        texts.forEach { assertTrue(it, "Bangle.js 2" in it && "PWN.Health" in it) }
    }
}
