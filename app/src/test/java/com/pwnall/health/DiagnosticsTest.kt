package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The report is pasted into messages by people who have not read it, so what it says is fixed here
 * line by line: the counts, the settings, and nothing that could be a row of the journal.
 */
class DiagnosticsTest {
    private val diagnostics = Diagnostics(
        build = DiagnosticsBuild(
            versionName = "1.52",
            versionCode = 52,
            androidRelease = "16",
            sdkInt = 36,
            manufacturer = "Google",
            model = "Pixel 9a",
            locale = "ru-RU",
        ),
        settings = DiagnosticsSettings(unitSystem = "METRIC", lockOn = true, autoBackupOn = false, exportAgeDays = 3),
        health = DiagnosticsHealth(status = "available", granted = 44, requested = 63),
        rows = DiagnosticsRows(journal = 1234, stepDays = 400, tracks = 12, progressPhotos = 5),
        installId = "0f1e2d3c-0000-4000-8000-000000000000",
    )

    @Test
    fun theReportIsEightPlainLines() {
        assertEquals(
            listOf(
                "PWN.Health 1.52 (52)",
                "Android 16 (SDK 36), Google Pixel 9a",
                "Locale ru-RU, units METRIC",
                "App lock: on",
                "Health Connect: available, 44 of 63 permissions granted",
                "Last export: 3 days ago; automatic copy: off",
                "Rows: 1234 journal entries, 400 step days, 12 tracks, 5 progress photos",
                "Install id: 0f1e2d3c-0000-4000-8000-000000000000",
            ),
            diagnostics.render().lines(),
        )
    }

    @Test
    fun aPhoneThatNeverExportedSaysSo() {
        val never = diagnostics.copy(settings = diagnostics.settings.copy(exportAgeDays = null, autoBackupOn = true))
        assertEquals("Last export: never; automatic copy: on", never.render().lines()[5])
    }
}
