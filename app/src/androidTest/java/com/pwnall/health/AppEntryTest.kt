package com.pwnall.health

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every way into the app against the system's real task stack: whatever reached it and whatever is
 * open over it, one MainActivity. A notification tap must not stack a second one, nor the icon on an
 * app a widget started.
 */
@RunWith(AndroidJUnit4::class)
class AppEntryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    @After
    fun closeTheApp() {
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        settle(emptyList(), null)
    }

    @Test
    fun aRunningAppTakesEveryEntryInItsOneWindow() {
        open(openAppIntent(context))
        settle(listOf(MAIN), MAIN)
        for (entry in listOf(
            openAppIntent(context),
            icon(),
            openAppIntent(context, WidgetRoute.TRACK, clearTop = false),
            widget(WidgetRoute.MOOD),
            widget(null),
            // A bare NEW_TASK intent, the kind that stacks a window on every tap of a notification.
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )) {
            open(entry)
            settle(listOf(MAIN), MAIN)
        }
    }

    @Test
    fun anAppAWidgetStartedTakesTheIconInItsOneWindow() {
        open(widget(WidgetRoute.WATER))
        settle(listOf(MAIN), MAIN)
        open(icon())
        settle(listOf(MAIN), MAIN)
        overTheApp()
        for (entry in listOf(icon(), openAppIntent(context), widget(null))) {
            open(entry)
            settle(listOf(MAIN, POLICY), POLICY)
        }
    }

    @Test
    fun whatIsOpenOverTheAppStaysOpenUntilARouteAsks() {
        open(icon())
        settle(listOf(MAIN), MAIN)
        overTheApp()
        for (entry in listOf(
            openAppIntent(context),
            openAppIntent(context, WidgetRoute.TRACK, clearTop = false),
            icon(),
        )) {
            open(entry)
            settle(listOf(MAIN, POLICY), POLICY)
        }
        open(widget(WidgetRoute.FOOD))
        settle(listOf(MAIN), MAIN)
    }

    private fun open(intent: Intent) = context.startActivity(intent)

    /** The launcher's tap, as Launcher3 builds it. */
    private fun icon() = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** A widget's tap as Glance hands it on: the app's intent with a data URI of Glance's own. */
    private fun widget(route: String?) = openAppIntent(context, route).setData("glance-action://test/$route".toUri())

    /** A window of the app's own over MainActivity, in its task — as the camera or a picker would be. */
    private fun overTheApp() {
        instrumentation.runOnMainSync {
            val main = monitor().getActivitiesInStage(Stage.RESUMED).single()
            main.startActivity(Intent(main, PrivacyPolicyActivity::class.java))
        }
        settle(listOf(MAIN, POLICY), POLICY)
    }

    /**
     * Waits for the app's windows to hold [alive] and [resumed] for a full second: a second window is
     * stacked a beat after the intent and sent away a beat after that, and one that stays breaks the
     * run of matching reads for good.
     */
    private fun settle(alive: List<String>, resumed: String?) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var since = 0L
        var seen: Pair<List<String>, String?>
        do {
            seen = windows()
            val now = System.currentTimeMillis()
            if (seen != alive to resumed) {
                since = 0L
            } else if (since == 0L) {
                since = now
            } else if (now - since >= STABLE_MS) {
                return
            }
            Thread.sleep(POLL_MS)
        } while (now < deadline)
        assertEquals(alive to resumed, seen)
    }

    private fun windows(): Pair<List<String>, String?> {
        var seen: Pair<List<String>, String?> = emptyList<String>() to null
        instrumentation.runOnMainSync {
            val stages = Stage.entries - Stage.DESTROYED
            val alive = stages.flatMap { monitor().getActivitiesInStage(it) }.map(::name).sorted()
            seen = alive to monitor().getActivitiesInStage(Stage.RESUMED).singleOrNull()?.let(::name)
        }
        return seen
    }

    private fun monitor() = ActivityLifecycleMonitorRegistry.getInstance()

    private fun name(activity: Activity): String = activity.javaClass.simpleName

    private companion object {
        const val MAIN = "MainActivity"
        const val POLICY = "PrivacyPolicyActivity"
        const val TIMEOUT_MS = 10_000L
        const val STABLE_MS = 1_000L
        const val POLL_MS = 100L
    }
}
