package com.pwnall.health

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a shared medal hands the chooser: a picture, by a URI of the photo provider, with a read
 * grant for that URI and no words. A sentence riding along would be a step count — health data —
 * in whatever app the person picks, which the card exists to avoid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MedalShareTest {
    @Test
    fun aMedalLeavesAsAPictureAndNothingBesideIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val started = mutableListOf<Intent>()
        val screen = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                started += intent
            }
        }

        shareMedal(screen, 100_000L, R.drawable.ic_art_medal_100k, "2026-09-11")

        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        val stream = checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals("${app.packageName}.photos", stream.authority)
        assertNotEquals(0, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_SUBJECT))
    }
}
