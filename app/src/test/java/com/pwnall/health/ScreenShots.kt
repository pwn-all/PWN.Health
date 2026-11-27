package com.pwnall.health

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Screen shots drawn on the JVM, for a machine with no emulator.
 *
 * Robolectric's native graphics runs the real Compose pipeline — layout, text shaping, the app's own
 * vector drawables and theme — into a Bitmap, and the shot is that Bitmap. Every test that shoots
 * calls [shotsDir] first, which skips it unless the build was asked for shots with -Pscreens, so the
 * ordinary test run never pays for drawing.
 */
internal object ScreenShots {
    /**
     * [compose] with the empty activity it hosts content in registered first. The unit-test
     * manifest does not carry it, and the compose rule launches it before any @Before runs.
     */
    fun hosted(compose: TestRule): TestRule = RuleChain.outerRule(
        object : ExternalResource() {
            override fun before() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        },
    ).around(compose)

    fun shotsDir(): File {
        val out = System.getProperty("screens.out")
        assumeTrue("screen shots are taken only with -Pscreens", !out.isNullOrBlank())
        return File(out!!).apply { mkdirs() }
    }

    /** The whole window as it is now, written as [name].png. */
    fun ComposeContentTestRule.shoot(dir: File, name: String) {
        waitForIdle()
        val bitmap = onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * An animation as a run of frames, [count] of them [stepMs] apart, with the clock held still
     * between them so each frame is exactly that far along. Named [name]-000.png onwards.
     */
    fun ComposeContentTestRule.film(dir: File, name: String, count: Int, stepMs: Long) {
        mainClock.autoAdvance = false
        repeat(count) { frame ->
            val bitmap = onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name-%03d.png".format(java.util.Locale.ROOT, frame)).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            mainClock.advanceTimeBy(stepMs)
        }
        mainClock.autoAdvance = true
    }

    /** The app's theme around [content], light or dark as asked. */
    @Composable
    fun Themed(dark: Boolean, content: @Composable () -> Unit) = PwnFitTheme(darkTheme = dark, content = content)
}
