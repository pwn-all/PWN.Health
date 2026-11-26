package com.pwnall.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every exercise's how-to panel, pose and hero decodes on the device the way painterResource loads it.
 * Some panels are AVIF, which the platform reads from Android 12; one the decoder refused would
 * leave "How to?" blank, and no JVM test can see that.
 */
@RunWith(AndroidJUnit4::class)
class ExerciseArtDecodeTest {
    @Test
    fun everyPanelAndPoseDecodes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = ACTIVITY_CATALOG.flatMap { listOfNotNull(it.howTo(), it.art(), it.heroArt()) }.distinct()

        ids.forEach { id ->
            val name = context.resources.getResourceEntryName(id)
            val drawable = checkNotNull(context.getDrawable(id)) { name }
            assertTrue(name, drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }
        assertTrue(
            "${ids.size} pictures",
            ids.any {
                context.resources.getResourceEntryName(it) == "ic_howto_basketball"
            },
        )
    }
}
