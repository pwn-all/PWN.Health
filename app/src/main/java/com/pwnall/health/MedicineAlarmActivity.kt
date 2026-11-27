package com.pwnall.health

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The lock-screen alarm contains no medicine name or dose. Opening the app keeps its normal lock. */
class MedicineAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window is edge to edge whatever this says, but without it the status bar keeps the
        // platform theme's white icons, which vanish over the alarm's light page in daylight.
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val body = intent.getStringExtra(MedicineAlarmNotification.EXTRA_BODY).orEmpty()
        onBackPressedDispatcher.addCallback(this) { dismiss() }
        lifecycleScope.launch {
            val expires = intent.getLongExtra(MedicineAlarmNotification.EXTRA_EXPIRES, System.currentTimeMillis())
            delay((expires - System.currentTimeMillis()).coerceIn(0, MedicineAlarmNotification.RING_MS))
            // Only the page goes when the minute is up, never the notification under the dose's tag.
            // The ring takes itself down then (setTimeoutAfter), and the silent card that takes its
            // place goes up under that same tag: a cancel from here — which comes late whenever the
            // phone slept through part of this wait — would take the card down, and nothing would
            // bring the dose back for an hour.
            finish()
        }
        setContent {
            PwnFitTheme(isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    // Scrolls when it has to: a landscape phone, a split screen or a large font
                    // leaves less height than the question and its three answers take, and the last
                    // of them would otherwise be cut off with no way to reach it. Filling before the
                    // scroll keeps the whole thing centred when it does fit.
                    Column(
                        Modifier.fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.med_reminder_title),
                            // What a screen reader's heading gesture lands on, as on the app's own pages.
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(body, style = MaterialTheme.typography.titleLarge)
                        // The answer to the question comes first, and it is the only button here
                        // that writes anything down — so it is the app's tall wide button, the one
                        // a half-awake thumb finds first, and the two ways out stay plain text.
                        PwnWideButton(
                            icon = Icons.Outlined.Check,
                            label = stringResource(R.string.med_action_taken),
                            onClick = ::taken,
                        )
                        TextButton(onClick = ::dismiss) { Text(stringResource(R.string.med_alarm_dismiss)) }
                        TextButton(onClick = {
                            // On the tab the dose is on, as the notification's card opens it.
                            startActivity(openAppIntent(this@MedicineAlarmActivity, WidgetRoute.MEDICINE))
                            dismiss()
                        }) { Text(stringResource(R.string.med_alarm_open)) }
                        Text(stringResource(R.string.med_alarm_not_taken), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    /**
     * Marks the dose down through the same receiver the notification's own button uses.
     *
     * By broadcast rather than by writing here: this screen finishes in the same breath, and a
     * coroutine started on its lifecycle would be cancelled before the row reached the database.
     */
    private fun taken() {
        intent.getStringExtra(MedicineAlarmNotification.EXTRA_KEY)?.let { key ->
            sendBroadcast(
                Intent(this, MedicineTakenReceiver::class.java)
                    .putExtra(MedicineAlarmNotification.EXTRA_KEY, key),
            )
        }
        finish()
    }

    /** "Stop sound", "Open app" and Back: the person's own answer, which takes the notification with it. */
    private fun dismiss() {
        intent.getStringExtra(MedicineAlarmNotification.EXTRA_KEY)?.let {
            MedicineAlarmNotification.cancel(this, it)
        }
        finish()
    }
}
