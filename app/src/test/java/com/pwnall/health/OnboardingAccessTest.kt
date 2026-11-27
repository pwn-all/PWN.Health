package com.pwnall.health

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The access page of onboarding: which settings screens it asks for twice, and what it says first. */
class OnboardingAccessTest {
    /**
     * Usage access is documented as taking no input, and a build whose filter has no package scheme
     * refuses this app's own row. This page falls back to the plain list as the profile does:
     * without it, on such a phone the estimate of sleep from phone use could not be switched on here.
     */
    @Test
    fun usageAccessIsAskedForAgainWithoutTheRowWhenTheRowIsRefused() {
        assertEquals(
            listOf("package:com.pwnall.health", null),
            settingsAttempts(Settings.ACTION_USAGE_ACCESS_SETTINGS, "com.pwnall.health"),
        )
    }

    /** These take the package by their documentation, so a refusal is a screen the phone lacks. */
    @Test
    fun theOtherScreensAreAskedForOnce() {
        val once = listOf(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        for (action in once) {
            assertEquals(action, listOf("package:com.pwnall.health"), settingsAttempts(action, "com.pwnall.health"))
        }
    }

    /**
     * Next on this page opens Health Connect's request, so the page has to have said what the
     * exchange is by then: Play asks for the disclosure in the app, before the system's screen. It
     * stands above the rows, where it is on screen without scrolling.
     */
    @Test
    fun whatHealthConnectExchangesIsSaidAboveTheRowsBeforeNextAsksForIt() {
        val page = File("src/main/java/com/pwnall/health/OnboardingAccess.kt").readText()
        val said = page.indexOf("stringResource(R.string.sync_health_body)")
        assertTrue(said >= 0)
        assertTrue(said < page.indexOf("OnboardingAccess.entries.filter"))
    }

    /**
     * On a phone without Health Connect — Android 13 without the app, a ROM without Google's — Next
     * asks for nothing, so the page must not say "Health Connect is offered next" over a description
     * of an exchange that is not coming: there it says neither. The shorter paragraph is the longer one
     * without its last sentence in every language, so the two cannot come to say different things.
     */
    @Test
    fun healthConnectIsPromisedOnlyWhereNextWillAskForIt() {
        assertEquals(R.string.onboarding_access_body, accessLeadRes(healthConnect = true))
        assertEquals(R.string.onboarding_access_body_alone, accessLeadRes(healthConnect = false))

        val page = File("src/main/java/com/pwnall/health/OnboardingAccess.kt").readText()
        val gated = Regex("""if \(healthConnect\) \{\s+Text\(stringResource\(R\.string\.sync_health_title\)""")
        assertTrue("the exchange is described only where it is offered", gated.containsMatchIn(page))

        val locales = File("src/main/res").listFiles { dir -> File(dir, "onboarding_access.xml").isFile }.orEmpty()
        assertEquals(16, locales.size)
        locales.forEach { dir ->
            val strings = File(dir, "onboarding_access.xml").readText()
            fun text(name: String) = strings.substringAfter("<string name=\"$name\">").substringBefore("</string>")
            val whole = text("onboarding_access_body")
            val alone = text("onboarding_access_body_alone")
            assertTrue(dir.name, alone.isNotBlank() && alone.length < whole.length && whole.startsWith(alone))
        }
    }

    /**
     * A system dialog closed without an answer is no refusal and leaves the rationale false, as it
     * is before any refusal, so the second tap asks again; the dialog would come up. Asked twice with
     * still no rationale is two refusals — or a permission no dialog will show — and goes to settings.
     */
    @Test
    fun aDialogClosedWithoutAnAnswerIsAskedForAgainBeforeSettingsAre() {
        val motion = "android.permission.ACTIVITY_RECOGNITION"
        val missing = listOf(motion)
        assertFalse(accessBlocked(missing, asked = emptyList()) { false })
        assertFalse(accessBlocked(missing, asked = listOf(motion)) { false })
        assertTrue(accessBlocked(missing, asked = listOf(motion, motion)) { false })
        // Refused once: the platform offers a rationale, and the dialog still comes up.
        assertFalse(accessBlocked(missing, asked = listOf(motion, motion)) { true })
        // No activity to ask is no reason to send anybody to settings.
        assertFalse(accessBlocked(missing, asked = listOf(motion, motion)) { null })
        // Only what is still missing counts.
        assertFalse(accessBlocked(emptyList(), asked = listOf(motion, motion)) { false })
    }

    /** Usage access is opened one way: the profile's buttons keep no fallback of their own beside this page's. */
    @Test
    fun usageAccessIsOpenedTheSameWayFromEveryScreen() {
        val sleep = File("src/main/java/com/pwnall/health/SleepDetection.kt").readText()

        assertTrue("openSettingsScreen(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.packageName)" in sleep)
        assertTrue("ActivityNotFoundException" !in sleep)
    }
}
