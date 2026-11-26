package com.pwnall.health

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit

/**
 * The lock on the app's own screen. The device credential *is* the PIN: Android already stores one,
 * already rate-limits guesses against it, and already offers the fingerprint or face on top where
 * the hardware has them — so there is no second PIN here to invent, hash, or lock out.
 *
 * Widgets never come through here. Water and mood write from their own ActionCallback, which the
 * launcher runs without ever starting MainActivity, so a locked app still records a tap.
 */
internal object AppLock {
    private const val PREFS = "lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SECURE = "secure"

    /**
     * A biometric the platform classes as strong, or the device PIN, which is always the fallback.
     * What the lock stands in front of is a cycle, a sex life, medicines and lab sheets, and a face
     * unlock the phone's own maker rates as the weaker class is not asked to guard those: on such a
     * phone the prompt offers the fingerprint or the PIN.
     */
    private const val TYPES = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL

    /**
     * Whether the app has been let in. It is kept here rather than in the Activity because turning
     * the phone destroys the Activity and is not leaving the app: a flag that died with the
     * instance would put the prompt back up on every rotation. A new process starts locked again.
     */
    val unlocked = mutableStateOf(false)

    /**
     * The prompt now on screen. Held so it can be called off; the framework keeps the Activity. A
     * state, because [LockScreen] reads [prompting] to tell what has taken its window's focus.
     */
    private var pending by mutableStateOf<CancellationSignal?>(null)

    /** Whether the lock's own prompt is up: a system window over the app, which the person is answering. */
    val prompting: Boolean get() = pending != null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** False on a device with no screen lock at all: there would be nothing to ask for. */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(TYPES) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
    }

    /**
     * Whether the window is to be hidden from screenshots and the recents preview: asked for on its
     * own, or implied by the lock — see [secureWindow].
     */
    fun secure(context: Context): Boolean =
        secureWindow(enabled(context), prefs(context).getBoolean(KEY_SECURE, false))

    /** The separate switch, kept as its own answer so that turning the lock off restores it. */
    fun blocksScreenshots(context: Context): Boolean = prefs(context).getBoolean(KEY_SECURE, false)

    fun setSecure(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_SECURE, on) }
    }

    /**
     * Hides the window from screenshots and from the recents preview. Off by default on its own:
     * people screenshot their own numbers to send them on, and this flag blocks that too. With the
     * lock it is not optional — see [secureWindow] — so whoever toggles the lock calls this too, the
     * window being already up.
     */
    fun applySecure(activity: Activity) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (secure(activity)) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }

    /**
     * Asks for the biometric or the device PIN. A device that has lost its screen lock since the
     * setting was switched on passes straight through — otherwise the app would be shut for good.
     */
    fun prompt(activity: Activity, onResult: (Boolean) -> Unit) {
        if (!available(activity)) {
            onResult(true)
            return
        }
        cancelPrompt()
        val signal = CancellationSignal()
        pending = signal
        BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.lock_title))
            .setDescription(activity.getString(R.string.lock_body))
            .setAllowedAuthenticators(TYPES)
            .build()
            .authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (answers(signal)) onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (answers(signal)) onResult(false)
                    }
                },
            )
    }

    /** A called-off prompt still reports its cancellation; nobody is waiting for that answer. */
    private fun answers(signal: CancellationSignal): Boolean {
        if (pending !== signal) return false
        pending = null
        return true
    }

    /**
     * Calls off a prompt whose Activity is on its way out. Until it ends, the framework holds the
     * callback — and through it the Activity and its whole composition — and a second prompt from
     * the Activity that replaces it would cancel this one anyway, in front of the person.
     */
    fun cancelPrompt() {
        pending?.cancel()
        pending = null
    }
}

/**
 * Whether the window carries FLAG_SECURE.
 *
 * The lock brings it along whether or not the separate switch is on. Android keeps a picture of the
 * last frame for the recents list and the lock is drawn only once the app comes back to the front,
 * so a locked app whose numbers still show in that thumbnail is a lock with a window in it: the
 * prompt guards the door and the weight of the week is on the wall outside. The switch on its own
 * still means something with the lock off, and turning the lock off gives it back its own answer.
 */
internal fun secureWindow(lockEnabled: Boolean, blockScreenshots: Boolean): Boolean =
    lockEnabled || blockScreenshots

/** Whether opening the app should ask now. Asking again while a prompt is up would stack dialogs. */
internal fun shouldPrompt(enabled: Boolean, unlocked: Boolean, prompting: Boolean): Boolean =
    enabled && !unlocked && !prompting

/**
 * Whether the app going out of sight should shut it again.
 *
 * One thing that stops this activity is not the person leaving: the PIN screen is a separate
 * activity, so it stops this one on its way in, and treating that as leaving would re-lock the app
 * mid-unlock and ask forever. That is [prompting].
 *
 * Everything else shuts it, the camera and the file pickers this app opens included. Exempting
 * them — the person is still in the app, after all — would be a way past the lock: the flag marking
 * "this window is ours" could only be cleared on the way back, and pressing Home from the camera
 * never comes back, so the app would stay unlocked and the next person to open it would never be
 * asked. Nor is an exemption needed: [LockScreen] is drawn over a composition that keeps running,
 * so a picker's callback completes under a locked screen and a sensor export keeps its contents.
 *
 * Shutting on everything else also matters because there is no later chance to ask: a stopped
 * activity is not stopped a second time when the screen finally goes dark, so a call, an alarm or
 * any other window that takes the front would otherwise leave the app open behind it for good.
 */
internal fun shouldRelock(enabled: Boolean, prompting: Boolean): Boolean = enabled && !prompting

/**
 * Whether a window composes the app at all, shut or open.
 *
 * A window that has been let in stays composed when the lock closes — [shouldRelock] says what that
 * keeps alive — and [LockScreen]'s own window, added at that moment, lies above every dialog already
 * open. A window built while the lock is shut has it the other way round: the lock's window is
 * added first, and whatever the app composed after it would stand above it. That is every dialog a
 * screen puts back from its saved state when the phone is turned or the theme switched under the
 * lock screen — a medicine's name over a Delete that works, with nobody asked for anything. So such
 * a window composes none of the app until it has been let in once ([letInBefore], which lives and
 * dies with the window). A result a picker hands back meanwhile is not lost: the activity keeps it
 * for the launcher, which registers when the app is composed.
 */
internal fun composesApp(letInBefore: Boolean, unlocked: Boolean): Boolean = letInBefore || unlocked

/**
 * Whether another of the app's windows has come over the lock's own, which then has to be added
 * again to be the top one. A window that was let in stays composed under the lock ([composesApp]),
 * and what it opens there — a sheet a reminder's tap leads to, a form answering the file a picker
 * hands back, a timer running out — is a window added after the lock's and so above it, with its
 * buttons working. The sign is the lock's window losing the focus it had. The one thing that takes
 * the focus without covering anything of the app's is the lock's own prompt ([prompting]), a system
 * window; what opened underneath that is dealt with when the prompt comes and when it goes, by
 * adding the lock's window again at both.
 */
internal fun lockCovered(hadFocus: Boolean, focused: Boolean, prompting: Boolean): Boolean =
    hadFocus && !focused && !prompting

/** What a locked app shows: no data, and a way to ask again after a cancelled prompt. */
@Composable
internal fun LockScreen(onUnlock: () -> Unit) {
    // Twice over, because one layer cannot cover both things. In the activity's window it hides the
    // app in the very frame the lock closes, before any other window can be added. In a window of
    // its own it also covers the dialogs that were open when the lock closed — the medicine name in
    // a delete confirmation, the entry behind one, the scanner's camera — which are windows of their
    // own above the activity's, and which nothing dismisses, the app being deliberately left
    // composed. A dialog opened after this one stacks above it, which is why a window built while
    // the lock is shut composes none of the app until it has been let in ([composesApp]), and why
    // this one is taken down and added again, as the newest and so the top one, each time the
    // prompt comes or goes and each time something else of the app's has covered it ([lockCovered]).
    LockFace(onUnlock)
    val prompting = AppLock.prompting
    var raised by remember { mutableIntStateOf(0) }
    key(prompting, raised) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            // Remembered with the window, so each one added starts as never focused: a window
            // that has not had the focus yet cannot have lost it, and is not added over and over
            // while something outside the app — the notification shade, the prompt — holds it.
            val focused = LocalWindowInfo.current.isWindowFocused
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(focused) {
                if (lockCovered(hadFocus, focused, prompting)) raised++
                hadFocus = hadFocus || focused
            }
            // Back is the way out of a locked app, but not a way past the lock, so it puts the app
            // down rather than dismissing anything. Finishing instead would dispose the launchers
            // the lock deliberately keeps composed.
            val activity = LocalActivity.current
            BackHandler { activity?.moveTaskToBack(true) }
            LockFace(onUnlock)
        }
    }
}

@Composable
private fun LockFace(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        // Same skeleton as the other window-filling screen, HealthRationale: the dialog is added
        // with decorFitsSystemWindows = false, so the bars are ours to keep clear of, and a
        // compact landscape window at 200% font asks for ~424dp of icon, title, body and button
        // where a half-screen split leaves ~381dp. fillMaxSize sits before verticalScroll so the
        // fixed minimum height still reaches the Column: CenterVertically keeps the face centred
        // whenever it fits, and only the overflowing case scrolls.
        // The display cutout as well as the bars: the window is edge to edge in landscape too, where
        // a side cutout would otherwise sit over the edge of the face.
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .panelVerticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = PwnMuted)
            Text(
                stringResource(R.string.lock_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
                textAlign = TextAlign.Center,
            )
            Button(onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
