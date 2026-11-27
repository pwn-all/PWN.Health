package com.pwnall.health

import android.annotation.SuppressLint
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.TimeZone

/**
 * A page's time zone follows the phone's. A page that remembered the zone it opened in would, left
 * open over a flight, go on drawing and filing by the zone of departure; [rememberZone] reads it
 * again when Android broadcasts the change. Composed on Robolectric, which delivers the broadcast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class RememberZoneTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private val before: TimeZone = TimeZone.getDefault()

    @After
    fun restoreTheZone() = TimeZone.setDefault(before)

    // The test speaks for the system, whose broadcast this is: an implicit one, as Android sends it.
    @SuppressLint("UnsafeImplicitIntentLaunch")
    @Test
    fun aPageTakesTheZoneTheBroadcastAnnounces() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        var shown: ZoneId? = null
        compose.setContent { shown = rememberZone() }
        compose.waitForIdle()
        assertEquals(ZoneId.of("Europe/Berlin"), shown)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        compose.waitForIdle()
        assertEquals(ZoneId.of("Asia/Tokyo"), shown)
    }

    @Test
    fun noPageRemembersTheZoneItOpenedIn() {
        val pages = File("src/main/java/com/pwnall/health").listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(pages.size > 100)
        val frozen = Regex("""remember(\([^)]*\))?\s*\{\s*(mutableStateOf\()?ZoneId\.systemDefault\(\)""")
        val holding = pages.filter { it.name != "Today.kt" && frozen.containsMatchIn(it.readText()) }.map { it.name }
        // The medicine tab reads it again with its minute's tick, which a broadcast would not add to.
        assertEquals(listOf("MedicineScreen.kt"), holding)
    }
}
