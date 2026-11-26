package com.pwnall.health

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The intents into MainActivity, read from the source. A window stacked over the app's own is sent
 * away at run time (MainActivity.stacksOnTheApp, AppEntryTest on a device); these keep the app's own
 * intents from stacking one in the first place.
 */
class AppEntryIntentTest {
    /**
     * A bare `Intent(this, MainActivity::class.java)`, in the steps notification or anywhere else,
     * would stack a second MainActivity on every tap. [openAppIntent] is shaped like the launcher's, so
     * a running app is brought forward instead — and nothing else may name the activity.
     */
    @Test
    fun onlyOpenAppIntentNamesMainActivity() {
        val named = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf {
                    Regex("""MainActivity::class|<MainActivity>|\.MainActivity"""").containsMatchIn(line)
                }
            }
        }.toList()
        assertEquals(listOf("Widgets.kt"), named.map { it.substringBefore(':') })
    }

    /**
     * singleTask or singleInstance would end the stacking too, by closing whatever is open over the
     * app on every tap of its icon: the walk camera's shot thrown away, a save picker that saves
     * nothing.
     */
    @Test
    fun mainActivityKeepsTheStandardLaunchMode() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val main = Regex("""<activity\s[^>]*android:name="\.MainActivity"[^>]*>""").find(manifest)?.value.orEmpty()
        assertFalse(main, main.isEmpty() || main.contains("launchMode") || main.contains("taskAffinity"))
    }

    /**
     * A task a widget started, finished and then opened from Recents is handed the widget's intent
     * again, flagged as launched from history, which would bring the water dialog back days after its
     * tap.
     */
    @Test
    fun aRouteBroughtBackFromRecentsIsNotFollowedAgain() {
        assertEquals(WidgetRoute.WATER, startingRoute(false, null, 0, WidgetRoute.WATER))
        assertNull(startingRoute(false, null, Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, WidgetRoute.WATER))
    }

    /**
     * A rebuilt window never reads its intent, whose tap the window before it may have followed long
     * ago; it takes the route that window had not followed yet, which the phone turned while an
     * encrypted record is still opening would otherwise lose.
     */
    @Test
    fun aRebuiltWindowTakesTheRouteItsPredecessorHadNotFollowed() {
        assertEquals(WidgetRoute.FOOD, startingRoute(true, WidgetRoute.FOOD, 0, WidgetRoute.WATER))
        assertNull(startingRoute(true, null, 0, WidgetRoute.WATER))
    }
}
