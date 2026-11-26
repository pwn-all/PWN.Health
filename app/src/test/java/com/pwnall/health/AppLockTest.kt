package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lock has two ways to go wrong that cost more than a missing dialog: an ask-forever loop, and
 * a widget that stops recording because the app is shut.
 */
class AppLockTest {
    @Test
    fun anOpenAppIsNotAskedAgain() {
        assertTrue(shouldPrompt(enabled = true, unlocked = false, prompting = false))
        assertFalse(shouldPrompt(enabled = true, unlocked = true, prompting = false))
    }

    /** The PIN screen stops this activity on its way in; both halves must ignore that. */
    @Test
    fun theCredentialScreenIsNotTreatedAsLeaving() {
        assertFalse(shouldPrompt(enabled = true, unlocked = false, prompting = true))
        // Mid-prompt is never a relock: the PIN screen stopping this one is not the user leaving.
        assertFalse(shouldRelock(enabled = true, prompting = true))
        assertTrue(shouldRelock(enabled = true, prompting = false))
    }

    @Test
    fun anAppWithTheLockOffIsNeverGated() {
        assertFalse(shouldPrompt(enabled = false, unlocked = false, prompting = false))
        assertFalse(shouldRelock(enabled = false, prompting = false))
    }

    /**
     * The recents thumbnail is drawn from the last frame before the lock closes, so a locked app
     * with the window unsecured would show its numbers there; the lock carries FLAG_SECURE with it.
     * The separate switch keeps its own answer for when the lock is off.
     */
    @Test
    fun theLockBringsTheSecureWindowWithIt() {
        assertTrue(secureWindow(lockEnabled = true, blockScreenshots = false))
        assertTrue(secureWindow(lockEnabled = false, blockScreenshots = true))
        assertFalse(secureWindow(lockEnabled = false, blockScreenshots = false))
    }

    /**
     * A window built while the lock is shut — the phone turned or the theme switched under the lock
     * screen, a process brought back with its saved state — that composed the app at once would put
     * its screens' dialogs back as windows added after the lock's own and so above it: the name of
     * a medicine over a working Delete, for whoever held the phone. Such a window composes nothing
     * until it is let in; one that has been let in stays composed when the lock closes, for the
     * launchers waiting on a result.
     */
    @Test
    fun aWindowBuiltLockedComposesNoneOfTheAppUntilItIsLetIn() {
        assertFalse(composesApp(letInBefore = false, unlocked = false))
        assertTrue(composesApp(letInBefore = false, unlocked = true))
        assertTrue(composesApp(letInBefore = true, unlocked = false))
        assertTrue(composesApp(letInBefore = true, unlocked = true))
    }

    /**
     * The rule above holds only for as long as the activity asks it: the app is composed nowhere in
     * its source as a statement of its own, only behind the answer.
     */
    @Test
    fun theActivityAsksBeforeItComposesTheApp() {
        val activity = File("src/main/java/com/pwnall/health/MainActivity.kt").readText()
        assertTrue(activity.contains("composesApp("))
        assertFalse(Regex("""\n\s+StepsApp\(""").containsMatchIn(activity))
    }

    /**
     * Water, mood, a dose, a cigarette and the work timer are written by ActionCallbacks the launcher
     * runs on their own — nothing in a file that declares a widget or one of its actions may reach
     * for the lock or the activity that carries it, or a locked phone would stop recording taps.
     * Every such file, found by what it declares: the actions live in more files than Widgets.kt,
     * and a scan of that one alone would miss a dose marked from its widget being held to nothing.
     */
    @Test
    fun theWidgetWritePathDoesNotGoThroughTheLock() {
        val sources = File("src/main/java/com/pwnall/health").listFiles().orEmpty()
            .associate { it.name to it.readText() }
        val widgets = widgetSources(sources)
        // The five there are today; a scan that found fewer has stopped reading what it guards.
        assertTrue("$widgets", widgets.size >= 5 && "DoseWidgets.kt" in widgets && "Widgets.kt" in widgets)
        assertEquals(emptyList<String>(), reachingForTheLock(sources.filterKeys { it in widgets }))
    }

    @Test
    fun aWidgetFileThatReachesForTheLockIsNamed() {
        val sources = mapOf(
            "Taps.kt" to "class Tap : ActionCallback {\n    fun go() = AppLock.unlocked\n}",
            "Face.kt" to "class Face : GlanceAppWidget() {\n    // waits for the unlock\n}",
            "Quiet.kt" to "class Quiet : GlanceAppWidget()",
            // Says the words and declares no widget: the lock's own file does.
            "AppLock.kt" to "/** Widgets never come here: an ActionCallback writes on its own. */\nobject AppLock",
        )
        val widgets = widgetSources(sources)
        assertEquals(listOf("Face.kt", "Quiet.kt", "Taps.kt"), widgets)
        assertEquals(listOf("Face.kt", "Taps.kt"), reachingForTheLock(sources.filterKeys { it in widgets }))
    }

    /** The files of [sources], name to text, that declare a Glance widget or an action of one. */
    private fun widgetSources(sources: Map<String, String>): List<String> =
        sources.filterValues { WIDGET_DECLARATION.containsMatchIn(it) }.keys.sorted()

    private fun reachingForTheLock(sources: Map<String, String>): List<String> =
        sources.filterValues { "AppLock" in it || "unlock" in it }.keys.sorted()

    /**
     * Everything that is not the credential screen shuts the app, and the three cases are one
     * assertion because the rule has one exemption. A screen going dark on its own; a call or an
     * alarm taking the front, which stops the app once and never stops it again; and a window the
     * app itself opened. Exempting that last one would leave a way past the lock, since the
     * exemption could only be lifted on the way back into the app: press Home from the camera and
     * there is no way back, so the app would stay unlocked and whoever opened it next would walk
     * straight in. Nothing needs the exemption — the composition keeps running under [LockScreen],
     * so a picker's callback still writes the file it was opened for.
     */
    @Test
    fun everythingButTheCredentialScreenShutsTheApp() {
        assertTrue(shouldRelock(enabled = true, prompting = false))
    }

    /**
     * The stop the lock spares is the one its own unlock brings. The profile asks the same question
     * of an app already open, before the lock or the file's encryption is taken off, and Home pressed
     * over that prompt would leave the app open behind it.
     */
    @Test
    fun onlyAnUnlockUnderwaySparesAStop() {
        assertTrue(unlockUnderway(prompting = true, unlocked = false))
        assertFalse(unlockUnderway(prompting = true, unlocked = true))
        assertFalse(unlockUnderway(prompting = false, unlocked = false))
        assertTrue(shouldRelock(enabled = true, prompting = unlockUnderway(prompting = true, unlocked = true)))
    }

    /**
     * One window's prompt calls another's off without an answer — a window another app opened into
     * its own task shares the lock — so whether one is up is the lock's word: a flag of the window's
     * own would stay up for good, and its Unlock button do nothing from then on.
     */
    @Test
    fun theActivityAsksTheLockWhetherAPromptIsUp() {
        val activity = File("src/main/java/com/pwnall/health/MainActivity.kt").readText()
        assertFalse(Regex("""\bvar prompting\b""").containsMatchIn(activity))
        assertTrue("if (AppLock.prompting) return" in activity)
    }

    /** Taking the lock off, or the file's encryption while the lock is on, is asked as putting it on is. */
    @Test
    fun theProfileTakesNoProtectionOffWithoutTheLocksQuestion() {
        val profile = File("src/main/java/com/pwnall/health/ProfileScreen.kt").readText()
        val lock = profile.substringAfter("title = stringResource(R.string.lock_title)")
            .substringBefore("PwnSwitchRow(")
        val cipher = profile.substringAfter("title = stringResource(R.string.db_encrypt_title)")
            .substringBefore("PwnSwitchRow(")

        assertTrue(lock, lock.indexOf("AppLock.prompt(") in 0 until lock.indexOf("AppLock.setEnabled(context, on)"))
        assertFalse(lock, "AppLock.setEnabled(context, false)" in lock)
        assertTrue(cipher, cipher.indexOf("AppLock.prompt(") in 0 until cipher.lastIndexOf("move()"))
    }

    /**
     * A window that was let in goes on running under the lock, and what it opens there is a window
     * above the lock's: the sheet a reminder's tap leads to, with its buttons working. The lock's
     * window is added again when it loses the focus it had — but not for its own prompt, which
     * takes the focus too and covers nothing, and not before it has had the focus at all, or a
     * window added under the notification shade would be added again for ever.
     */
    @Test
    fun theLockIsPutBackOnTopOfWhatOpensOverIt() {
        assertTrue(lockCovered(hadFocus = true, focused = false, prompting = false))
        assertFalse(lockCovered(hadFocus = true, focused = false, prompting = true))
        assertFalse(lockCovered(hadFocus = false, focused = false, prompting = false))
        assertFalse(lockCovered(hadFocus = true, focused = true, prompting = false))
    }

    /**
     * The record behind the lock is a cycle, medicines and lab sheets, not a step count: the prompt
     * takes a biometric the platform classes as strong or the device credential, and never the
     * weaker class a photograph may pass.
     */
    @Test
    fun theLockAsksForAStrongBiometricOrTheDeviceCredential() {
        val lock = File("src/main/java/com/pwnall/health/AppLock.kt").readText()
        assertTrue(lock.contains("Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL"))
        assertFalse(lock.contains("BIOMETRIC_WEAK"))
    }

    private companion object {
        /** A class that is a widget's action or a widget: what the launcher runs with no activity behind it. */
        val WIDGET_DECLARATION = Regex(""":\s*(ActionCallback\b|GlanceAppWidget\(\))""")
    }
}
