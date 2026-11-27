package com.pwnall.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Minutes since midnight, which is the unit a dose's time is written in. */
private fun nowMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

/**
 * How far back the advice and streak cards read: the whole of [ACTIVITY_HISTORY_DAYS], because the
 * month [activityAdvice] reads is thirty finished days and today is not one of them. Everything
 * above them only ever shows today.
 */
private const val HISTORY_DAYS = ACTIVITY_HISTORY_DAYS

/** How far back the home screen reads every kind of row; older rows reach it only for [HOME_HISTORY_KINDS]. */
private const val HOME_JOURNAL_DAYS = 366L

/** The kinds read whole, however old: a cycle is judged by its history, and lab panels are few and lasting. */
private val HOME_HISTORY_KINDS = listOf(KIND_CYCLE, KIND_CYCLE_END, KIND_LABS)

/** The air between the home page's parts, and under the cards' dots above the bar. */
private val HOME_GAP = 16.dp

/** The chat's circle: the pill's own air at its side, and a little over the air above the pill. */
private val HOME_CHAT_SIDE = 12.dp
private val HOME_CHAT_LIFT = 4.dp

/** Two seconds of rings, each living half of them and starting a quarter after the one before. */
private const val CHAT_WAVE_MS = 2_000
private const val CHAT_WAVES = 3

/** Set in the "home" preferences the first time the chat opens, by whatever way it was reached. */
internal const val CHAT_SEEN = "chat_seen"

@Composable
fun HomeScreen(
    modifier: Modifier,
    profile: UserProfile,
    onCycle: () -> Unit,
    onMedicine: () -> Unit,
    onLevels: () -> Unit,
    onOpenMetric: (HomeMetric) -> Unit,
    onChat: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val todayDate = rememberToday()
    val today = todayDate.toString()
    val homePreferences = remember(context) { context.getSharedPreferences("home", Context.MODE_PRIVATE) }
    var focusDismissed by remember(today) {
        mutableStateOf(homePreferences.getString("focus_dismissed_day", null) == today)
    }
    // The journal the cards read: every row of the last year, and the cycle and lab rows from
    // before it. Collected whole, the table would be re-read entire on every write anywhere — a
    // glass of water, a dose, a row the sync brought — and hand the home screen tens of thousands of
    // rows to walk again.
    val recentFrom = remember(today) { todayDate.minusDays(HOME_JOURNAL_DAYS).toString() }
    val recent by remember(today) {
        combine(
            dao.journalBetween(recentFrom, today),
            dao.journalOfKindsBefore(HOME_HISTORY_KINDS, recentFrom),
        ) { window, history -> window + history }
    }.collectAsStateWithLifecycle(emptyList())
    val recentDays by remember(today) { dao.daysSince(todayDate.minusDays(HISTORY_DAYS).toString()) }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val homeToday = rememberHomeToday(profile)
    val xp = rememberAnnualXp(todayDate)
    var hiddenTopics by remember { mutableStateOf(AdviceLog.hidden(context)) }
    var shade by remember { mutableStateOf(false) }
    var seenNotices by remember { mutableStateOf(HomeNoticeLog.seen(context)) }
    val medications by remember { dao.medications() }.collectAsStateWithLifecycle(emptyList())
    // The dialogs outlive a turn of the phone, which recreates the activity, as the cycle page's do:
    // otherwise the period being closed and the first one being entered would go with it. The advice
    // open is kept by its notice's key, an advice being nothing a Bundle holds, and found again
    // among what the page says.
    var selectedAdviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var closingPeriod by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var showCycleNudge by rememberSaveable { mutableStateOf(false) }
    var recordingFirstCycle by rememberSaveable { mutableStateOf(false) }
    val adviceDays = remember(recentDays, today) { recentDays.coarseToday(today) }
    val advice by produceState<List<Advice>>(emptyList(), adviceDays, recent, profile, todayDate, hiddenTopics) {
        value = withContext(Dispatchers.Default) {
            allAdvice(adviceDays, recent, profile, todayDate, todayDate.year, hiddenTopics)
        }
    }
    val hasCycle = profile.sex == "FEMALE"
    // The first-cycle ask is the one nudge a woman can turn down for good: it is a question about
    // her body asked before she has written anything down, and an app that keeps asking after a no
    // is an app that gets the card swiped past without being read.
    var cycleSetupDismissed by remember {
        mutableStateOf(homePreferences.getBoolean("cycle_setup_dismissed", false))
    }
    val nudge = remember(recent, todayDate, hasCycle, cycleSetupDismissed) {
        if (!hasCycle) {
            null
        } else {
            cycleNudge(recent, todayDate)
                ?.takeUnless { it.kind == CycleNudgeKind.FIRST_CYCLE && cycleSetupDismissed }
        }
    }
    // Every dose whose time has gone by and which is still unmarked — not medNudge, which answers
    // a different question: it returns one dose and falls silent four hours after its time, which
    // is right for a notification and wrong for a card that ought to last the day.
    // Only while the page can be seen, and read again on the way back: the step service keeps the
    // process, and with it a stopped page, through the whole day.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val minuteOfDay by produceState(nowMinuteOfDay(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = nowMinuteOfDay()
                delay(60_000)
            }
        }
    }
    val missedDoses = remember(medications, recent, todayDate, minuteOfDay) {
        plannedDoses(medications, recent, todayDate, wakeMinuteOf(recent, todayDate, ZoneId.systemDefault()))
            .filterNot(PlannedDose::taken)
            .filter { it.minuteOfDay < minuteOfDay }
    }
    // Keyed by what each says, not by where it stands — see [adviceNoticeKeys].
    val shownAdvice = (homeToday.nutritionAdvice + advice).filterNot { it.topic.name in hiddenTopics }
    val adviceKeys = adviceNoticeKeys(shownAdvice)
    val notices = buildList {
        missedDoses.forEach { dose ->
            add(
                HomeNotice(
                    // One notice a dose: its day, its course and its slot. The notification's tag is
                    // spelt otherwise, since doses due together share one ([DatedMedNudge.key]).
                    key = "dose:$today:${dose.medicationId}:${dose.slot}",
                    label = resources.getString(R.string.med_title),
                    headline = resources.getString(R.string.home_dose_missed, dose.name),
                    message = resources.getString(
                        R.string.home_dose_missed_at,
                        if (dose.onWaking) {
                            resources.getString(R.string.med_dose_on_waking)
                        } else {
                            clockText(dose.minuteOfDay)
                        },
                    ),
                    art = R.drawable.ic_art_hint,
                    // No dismissal: it goes when the dose is marked, and nothing else should make
                    // a record of an unmarked dose disappear.
                    pinned = true,
                    onClick = onMedicine,
                ),
            )
        }
        nudge?.let {
            add(
                HomeNotice(
                    key = "cycle-nudge",
                    label = resources.getString(R.string.cycle_title),
                    headline = when (it.kind) {
                        CycleNudgeKind.CLOSE_PERIOD -> resources.getString(R.string.cycle_nudge_open)

                        CycleNudgeKind.FIRST_CYCLE -> resources.getString(R.string.cycle_nudge_never)

                        CycleNudgeKind.PERIOD_RUNNING -> resources.getString(R.string.cycle_nudge_running)

                        CycleNudgeKind.LOG_START -> resources.getString(
                            R.string.cycle_nudge_late,
                            it.since?.format(
                                localizedDatePattern(resources.configuration.locales[0], "dMMM", "d MMM"),
                            ).orEmpty(),
                        )
                    },
                    // The word for what the tap offers, so the card reads as the button it is.
                    message = resources.getString(cycleNudgeAction(it.kind)),
                    art = R.drawable.ic_art_cycle,
                    // Pinned, and still refusable for good: pinning decides where it sorts and how
                    // long it stays, not whether a person may answer no to it once and for all.
                    pinned = true,
                    onDismiss = if (it.kind == CycleNudgeKind.FIRST_CYCLE) {
                        {
                            cycleSetupDismissed = true
                            homePreferences.edit { putBoolean("cycle_setup_dismissed", true) }
                        }
                    } else {
                        null
                    },
                ) { showCycleNudge = true },
            )
        }
        shownAdvice.zip(adviceKeys)
            .forEach { (item, key) ->
                add(
                    HomeNotice(
                        key = key,
                        label = resources.getString(item.topic.titleRes),
                        headline = resources.getString(item.titleRes),
                        message = resources.formatAdviceBody(item),
                        art = R.drawable.ic_art_hint,
                    ) { selectedAdviceKey = key },
                )
            }
        if (hasCycle) {
            val summary = cycleSummary(recent, todayDate)
            // Nothing logged yet is the nudge's line above, not a second card saying so.
            summary.dayOfCycle?.let { day ->
                add(
                    HomeNotice(
                        key = "cycle",
                        label = resources.getString(R.string.cycle_title),
                        headline = resources.getString(R.string.cycle_day, day),
                        message = summary.nextExpected?.let {
                            resources.getString(
                                R.string.cycle_next,
                                it.format(localizedDatePattern(resources.configuration.locales[0], "dMMM", "d MMM")),
                            )
                        } ?: resources.getString(R.string.cycle_no_prediction),
                        art = R.drawable.ic_art_cycle,
                        onClick = onCycle,
                    ),
                )
            }
        }
        // Offer the daily encouragement until it has been dismissed for today.
        if (isEmpty() && !focusDismissed) {
            add(
                HomeNotice(
                    key = "motivation",
                    onDismiss = {
                        focusDismissed = true
                        homePreferences.edit { putString("focus_dismissed_day", today) }
                    },
                    label = resources.getString(R.string.home_focus),
                    headline = resources.getString(R.string.home_focus_headline),
                    message = resources.getString(R.string.home_focus_message),
                    art = R.drawable.ic_art_hint,
                ),
            )
        }
    }
    // Pinned first, then in the order they were built. Sorted stably, so two pinned notices keep
    // the order above — a missed dose before a cycle ask.
    val ordered = notices.sortedByDescending(HomeNotice::pinned)
    val unread = ordered.count { it.key !in seenNotices }
    BoxWithConstraints(
        modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(PwnBlueSoft.copy(alpha = 0.55f), MaterialTheme.colorScheme.background),
            ),
        ),
    ) {
        // What is left of the viewport once the head has had its share, so the first screenful
        // holds the whole page and nothing has to be scrolled to. The share is measured with the
        // permission card still standing — the tallest the head ever is — so the last line inside
        // the card and the dots under it stay on screen on a first run as well as on every run
        // after it. Taking the dead band under the card is worth about 38dp; the card's real gain
        // is its width.
        val cardHeight = (maxHeight - LocalBottomBarInset.current - 220.dp).coerceIn(340.dp, 480.dp) * 0.90f
        // The same gap the column puts between its children, so the air under the dots matches the
        // air over the cards once the two spacers below have shared out the rest.
        val foot = LocalBottomBarInset.current + HOME_GAP
        Column(
            Modifier.fillMaxSize()
                // While the notice shade is down the page behind it is scenery: a screen reader
                // would otherwise walk the page's cards from under the shade.
                .then(if (shade) Modifier.clearAndSetSemantics { } else Modifier)
                .panelVerticalScroll(rememberScrollState())
                .padding(bottom = foot)
                // At least the viewport tall, so the spacers have the leftover to divide: inside a
                // scroll a column is measured unbounded, and weights then share out its minimum.
                .heightIn(min = maxHeight - foot),
            verticalArrangement = Arrangement.spacedBy(HOME_GAP),
        ) {
            HomeGreeting(
                xp = xp,
                unread = unread,
                onNotices = {
                    shade = true
                    // Marked on the way in rather than on the next visit, the way the blood work's
                    // bell does it, and pruned to what is on screen in the same breath.
                    val keys = ordered.map(HomeNotice::key).toSet()
                    HomeNoticeLog.markSeen(context, keys)
                    seenNotices = keys
                },
                onLevels = onLevels,
            )
            PermissionGate()
            // The cards stand in the middle of what the greeting leaves above the bar, with as much
            // room over them as under them, rather than right under the greeting with the whole of
            // the spare height piled up between their dots and the bar.
            Spacer(Modifier.weight(1f))
            HomeMetricCarousel(homeToday.faces, onOpenMetric, cardHeight)
            Spacer(Modifier.weight(1f))
        }
        // Over the page, not in it: the circle stays put while the page scrolls under it, a little
        // above the floating bar, and goes with the rest of the page under the notices.
        if (!shade) {
            HomeChatButton(
                onChat,
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = HOME_CHAT_SIDE, bottom = LocalBottomBarInset.current + HOME_CHAT_LIFT),
            )
        }
        NoticeShade(shade, stringResource(R.string.home_notices_title), { shade = false }) {
            if (ordered.isEmpty()) {
                Text(stringResource(R.string.home_notices_empty), color = PwnMuted)
            } else {
                ordered.forEach { notice ->
                    HomeNoticeRow(notice, unread = notice.key !in seenNotices)
                }
            }
        }
    }
    selectedAdviceKey?.let { key -> shownAdvice.getOrNull(adviceKeys.indexOf(key)) }?.let { item ->
        HomeAdviceDialog(
            advice = item,
            onDismiss = { selectedAdviceKey = null },
            onHide = {
                AdviceLog.hide(context, item.topic)
                hiddenTopics = AdviceLog.hidden(context)
                selectedAdviceKey = null
            },
        )
    }
    if (showCycleNudge && nudge != null) {
        AlertDialog(
            onDismissRequest = { showCycleNudge = false },
            text = {
                CycleNudgeCard(nudge, onLog = {
                    showCycleNudge = false
                    when (nudge.kind) {
                        CycleNudgeKind.CLOSE_PERIOD -> closingPeriod = nudge.since

                        // Both ends of a period are a deliberate act: the screen owns the
                        // question and the checks that follow it, so the card hands over.
                        CycleNudgeKind.PERIOD_RUNNING -> onCycle()

                        // The first ask is the one that is about a day in the past, so it opens the
                        // dialog instead of filing today: a woman four days into her period who is
                        // handed a start dated today loses those four days for good.
                        CycleNudgeKind.FIRST_CYCLE -> recordingFirstCycle = true

                        CycleNudgeKind.LOG_START -> scope.launch {
                            withContext(NonCancellable) {
                                db.logCycleStart(cycleEntry(resources, LocalDate.now().toString()))
                            }
                        }
                    }
                }, onOpen = {
                    showCycleNudge = false
                    onCycle()
                }, onSymptom = {
                    showCycleNudge = false
                    onCycle()
                })
            },
            confirmButton = {
                TextButton(onClick = { showCycleNudge = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    if (recordingFirstCycle) {
        FirstCycleDialog(
            onDismiss = { recordingFirstCycle = false },
            onConfirm = { start, end ->
                recordingFirstCycle = false
                // One write for the pair, as the cycle screen's own page files it: an end is never
                // filed behind a start the record turned down.
                scope.launch { db.logRememberedPeriod(resources, start, end) }
            },
        )
    }
    closingPeriod?.let { start ->
        ClosePeriodDialog(start = start, onDismiss = { closingPeriod = null }, onConfirm = { from, to ->
            closingPeriod = null
            scope.launch {
                withContext(NonCancellable) {
                    db.logCyclePeriod(
                        cycleEntry(resources, from.toString()),
                        cycleEndEntry(resources, to.toString()),
                        start.toString(),
                    )
                }
            }
        })
    }
}

@Composable
private fun HomeAdviceDialog(advice: Advice, onDismiss: () -> Unit, onHide: () -> Unit) {
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(advice.titleRes)) },
        text = {
            Column(
                Modifier.panelVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(resources.formatAdviceBody(advice), style = MaterialTheme.typography.bodyLarge)
                Text(
                    resources.formatAdviceWindow(advice),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onHide) { Text(stringResource(R.string.advice_hide)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun PermissionGate() {
    val context = LocalContext.current
    val required = remember { requiredAppPermissions() }
    var missing by remember { mutableStateOf(context.missingPermissions(required)) }
    var dismissed by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    // Android answers a second refusal immediately and shows nothing. `shouldShowRequestRationale`
    // is the one signal for it, and it reads false both before the first ask and after the last —
    // so the first ask is recorded, and only after that does false mean "asked and shut out".
    val preferences = remember(context) { context.getSharedPreferences("home", Context.MODE_PRIVATE) }
    var asked by remember { mutableStateOf(preferences.getBoolean(ASKED_PERMISSIONS, false)) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missing = context.missingPermissions(required)
        asked = true
        preferences.edit { putBoolean(ASKED_PERMISSIONS, true) }
    }
    val shutOut = asked && activity != null &&
        missing.none { activity.shouldShowRequestPermissionRationale(it) }
    // Read again on every return to the app. The launcher above answers the system's dialog, but a
    // person shut out is sent to the app's settings page, which answers nobody: granted there, the
    // permission would come back to a card still asking for it and to a step service the effect
    // below had not started, until the tab was left and opened again.
    LifecycleResumeEffect(Unit) {
        missing = context.missingPermissions(required)
        onPauseOrDispose { }
    }

    // Step counting is switched on here, where the permission it needs is asked for: waiting for
    // the next launch loses a day of steps.
    // Keyed on the answer rather than on composition, so granting it starts the service at once.
    // Started only with the app in sight, which is what Android lets start a foreground service: an
    // encrypted record opening slowly composes this page after the window has been left, and a
    // start from there would be refused with an exception nothing catches.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(missing.size, lifecycle) {
        val counter = context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter != null && context.hasStepPermission()) {
            lifecycle.withStarted {
                ContextCompat.startForegroundService(context, Intent(context, StepService::class.java))
            }
        }
    }

    // The card asks before the system does. Firing the request on composition would throw the
    // dialog at the person on every return to this tab — over the card explaining it, and over the
    // lock screen at a cold start — and two of those reflexive denials deny the permission for good.
    if (missing.isEmpty() || dismissed) return
    PwnCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Image(
                themePainterResource(R.drawable.ic_art_sensors),
                contentDescription = null,
                modifier = Modifier.size(46.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.permission_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.permission_intro_body),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A flow rather than a row: in a row a long label on the first button would leave
                // the second too little of the column — "Later" would break mid-word, and beside the
                // Russian settings label it would get no width at all. Here the second button drops
                // to its own line.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            // With the dialog no longer coming, the app's own settings page is the
                            // only road left; sending the person there beats a button that does
                            // nothing at all above a card that returns at every launch.
                            if (shutOut) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        "package:${context.packageName}".toUri(),
                                    ),
                                )
                            } else {
                                request.launch(missing)
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (shutOut) R.string.permission_intro_settings else R.string.permission_intro_allow,
                            ),
                        )
                    }
                    TextButton(onClick = { dismissed = true }) {
                        Text(stringResource(R.string.permission_intro_later))
                    }
                }
            }
        }
    }
}

/** Whether the system dialog has been put up once, so that a later refusal can be told apart. */
private const val ASKED_PERMISSIONS = "asked_permissions"

// Location is not here: it is asked for on the track screen, by the button that starts a route,
// where the reason for it is on screen.
private fun requiredAppPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.missingPermissions(all: Array<String>): Array<String> =
    all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

@Composable
internal fun JournalEntry.shortDetails(imperial: Boolean): String = when {
    kind == KIND_WATER -> formatVolume(LocalResources.current, servingG.toInt(), imperial)

    kind == KIND_CAFFEINE -> stringResource(R.string.caffeine_entry_value, servingG.toInt())

    // The date as the phone's language writes one, not as the journal keys it.
    kind == KIND_PLAN -> {
        val format = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
            .withLocale(LocalLocale.current.platformLocale)
        stringResource(R.string.routines_planned, day.toLocalDateOrNull()?.format(format) ?: day)
    }

    kind == KIND_NOT_DONE -> stringResource(R.string.routines_not_done)

    // The panel writes its own summary line: "Glucose 6.4 · Total cholesterol 5.9".
    kind == KIND_LABS -> note

    // The pour and the product are kept as names and figures, so they read in the language showing now.
    kind == KIND_ALCOHOL -> stringResource(
        R.string.alcohol_entry_value,
        pourOf(this)?.let { pourText(LocalResources.current, it) } ?: subtitle,
        (servingG / STANDARD_DRINK_G).shown(),
    )

    kind == KIND_SMOKING -> stringResource(
        R.string.smoking_entry_value,
        tobaccoOf(this)?.let { stringResource(it.titleRes) } ?: subtitle,
        servingG.shown(),
    )

    // The subtitle holds the enum name, so the day list names it in the language showing now.
    kind == KIND_STOOL -> stringResource(
        R.string.stool_entry_value,
        stoolKindOf(this)?.let { stringResource(it.titleRes) } ?: subtitle,
    )

    kind == KIND_URINATION -> stringResource(R.string.urination_entry_value)

    // The ticks are kept by name in the payload, so they read in the language showing now.
    kind == KIND_MASTURBATION -> sexNotesText(LocalResources.current, measurementPayloadJson)

    // The answer is its enum name in the payload, so it reads in the language showing now.
    kind == KIND_MORNING_CHECK -> morningCheckOf(measurementPayloadJson)?.let {
        stringResource(R.string.sexual_morning_entry_value, stringResource(it.answer.titleRes), wakeClock(it.wokeAt))
    }.orEmpty()

    // The payload holds the enum name, so the day list names the answer in the language showing
    // now rather than in the one it happened to be logged in. The title was baked at write time by
    // `cycleLogEntry`, so without this a pain entry from last week still reads English in Russian.
    kind == KIND_CYCLE_LOG -> cycleLog()
        ?.let { stringResource(it.titleRes) }
        ?.let { listOf(it, sexNotesText(LocalResources.current, measurementPayloadJson)) }
        ?.filter(String::isNotEmpty)
        ?.joinToString(" · ")
        ?: subtitle

    // The dose as it was typed on the course, or just that one was taken when it carried no figure.
    kind == KIND_MED -> subtitle.ifBlank { stringResource(R.string.med_entry_value) }

    kind == KIND_MOOD -> Mood.fromScore(moodScore)
        ?.let { "${it.emoji} ${stringResource(it.titleRes)}" }
        ?: note

    // Hours and minutes rather than the else branch's "432 min", and how many pauses it held.
    kind == KIND_WORK -> workDetails()

    // Read from the payload's enum names, so the areas are named in the language showing now.
    kind == KIND_PAIN -> painDetails()

    // How bad, from the payload's enum names in the language showing now.
    kind == KIND_SYMPTOM -> symptomDetails()

    kind == KIND_BREATHING -> pluralStringResource(
        R.plurals.breathing_entry_value,
        breathCycles ?: 0,
        breathCycles ?: 0,
        (durationMinutes ?: 0.0).shown(),
    )

    kind == KIND_EAT -> note.ifBlank {
        stringResource(R.string.entry_food_details, proteinG.shown(), fatG.shown(), carbsG.shown())
    }

    sleepStartAt != null && sleepEndAt != null -> {
        val minutes = ((sleepEndAt - sleepStartAt).coerceAtLeast(0) / 60_000).toInt()
        stringResource(R.string.measurement_sleep_value, minutes / 60, minutes % 60)
    }

    // The weigh-in is stored in kilograms and the height in centimetres whatever the profile
    // reads in, so the row converts here rather than anywhere earlier — the same two helpers the
    // profile card and the pickers already print with, so a list and a form never disagree.
    // One row can hold both — onboarding writes exactly such a row, and so does a scale that
    // reports a height — and a day list that named only the weight would leave the height unseen
    // anywhere but the editor's own history.
    measurementWeightKg != null || measurementHeightCm != null -> listOfNotNull(
        measurementWeightKg?.let {
            if (imperial) {
                "${imperialWeight(it).shown()} ${stringResource(R.string.unit_lb)}"
            } else {
                stringResource(R.string.measurement_weight_value, it.shown())
            }
        },
        measurementHeightCm?.let {
            if (imperial) imperialHeight(it) else stringResource(R.string.measurement_height_value, it)
        },
    ).joinToString(" · ")

    measurementHeartRateBpm != null -> stringResource(R.string.measurement_heart_rate_value, measurementHeartRateBpm)

    // The two kinds that carry energy, and the only two: a meal adds it, a workout spends it —
    // which is why the workout's figure is signed. Everything else on this list has a calories
    // column of zero, and "0 kcal" under a period is the app reporting an empty field as a
    // measurement. Those rows say what they have to say and stop.
    kind == KIND_ACTIVITY -> listOfNotNull(
        // First, because the line is one row tall: what this exercise was paired with is the part
        // that would otherwise be cut, and it is the part the duration cannot be guessed without.
        supersetPartnerCode(measurementPayloadJson)
            ?.let { code -> ACTIVITY_CATALOG.firstOrNull { it.code == code } }
            ?.let { stringResource(R.string.workout_superset_with, stringResource(it.titleRes)) },
        // A template session's exercise left short says by how much: "3 min of 5 min", "Sets 2 of 3".
        routineShortfallText(),
        durationMinutes?.takeIf { it > 0 }
            ?.let { stringResource(R.string.entry_duration, it.shown()) },
        calories.takeIf { it >= 1.0 }?.let { stringResource(R.string.entry_energy_burned, it) },
    ).joinToString(" · ")

    else -> durationMinutes?.let { stringResource(R.string.entry_duration, it.shown()) }.orEmpty()
}

internal fun formatSleepClock(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** The one permission the `health` foreground type may be claimed under. [BootReceiver] asks too. */
internal fun Context.hasStepPermission() =
    checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

/**
 * The cycle's one line on the home screen: what is unfinished, and the button that finishes it.
 *
 * One tap writes today's date, because the answer is nearly always today — the period ended, or it
 * started this morning. The screen behind it is there for the day it was not.
 */
@Composable
private fun CycleNudgeCard(
    nudge: CycleNudge,
    onLog: () -> Unit,
    onOpen: () -> Unit,
    onSymptom: () -> Unit,
) {
    val format = rememberDayMonth()
    val message = when (nudge.kind) {
        CycleNudgeKind.CLOSE_PERIOD -> stringResource(R.string.cycle_nudge_open)

        CycleNudgeKind.FIRST_CYCLE -> stringResource(R.string.cycle_nudge_never_body)

        CycleNudgeKind.PERIOD_RUNNING -> stringResource(
            R.string.cycle_nudge_running_body,
            nudge.since?.format(format).orEmpty(),
        )

        CycleNudgeKind.LOG_START -> stringResource(R.string.cycle_nudge_late, nudge.since?.format(format).orEmpty())
    }
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                themePainterResource(R.drawable.ic_art_cycle),
                contentDescription = null,
                modifier = Modifier.size(46.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onLog) {
                    Text(stringResource(cycleNudgeAction(nudge.kind)))
                }
                // While a period runs there are two things to do about it, and the front page
                // carries both rather than making her open the screen to find the second.
                if (nudge.kind == CycleNudgeKind.PERIOD_RUNNING) {
                    OutlinedButton(onClick = onSymptom) {
                        Text(stringResource(R.string.cycle_symptoms_add))
                    }
                }
            }
        }
    }
}

/**
 * The word on the button, which is also the word on the card that leads to it. One function rather
 * than the same `when` in the two places the nudge is drawn, since a card offering "Log period
 * start" that opens a dialog offering something else is the bug this shape prevents.
 */
private fun cycleNudgeAction(kind: CycleNudgeKind): Int = when (kind) {
    CycleNudgeKind.CLOSE_PERIOD -> R.string.cycle_period_end
    CycleNudgeKind.FIRST_CYCLE -> R.string.cycle_first_action
    CycleNudgeKind.PERIOD_RUNNING -> R.string.cycle_finish
    CycleNudgeKind.LOG_START -> R.string.cycle_add
}

/**
 * The chat, a circle in the bottom right corner over the floating bar, worn the way the bar is: its
 * container, its resting grey ink and its shadow. Until the chat has been opened once, it wears a
 * red dot, and every time the home screen opens rings spread from the circle
 * for two seconds — once, then still, so a page left open does not keep twitching in the corner of
 * the eye. Off with the phone's animations. The home page leaves composition under the chat's panel,
 * so the flag is read afresh on the way back.
 */
@Composable
private fun HomeChatButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val seen = remember(context) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean(CHAT_SEEN, false)
    }
    var wave by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(lifecycle, seen) {
        if (seen || !context.animationsOn()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                animate(0f, 1f, animationSpec = tween(CHAT_WAVE_MS, easing = LinearEasing)) { value, _ -> wave = value }
            } finally {
                wave = 1f // a pause in the middle must not leave a ring frozen round the circle
            }
        }
    }
    val ring = PwnIndigo
    FloatingActionButton(
        onClick = onOpen,
        modifier = modifier
            .drawBehind {
                val from = size.minDimension / 2
                repeat(CHAT_WAVES) { i ->
                    val t = (wave - i / (CHAT_WAVES + 1f)) * 2
                    if (t > 0f && t < 1f) {
                        drawCircle(
                            ring.copy(alpha = (1 - t) / 2),
                            radius = lerp(from, from + 10.dp.toPx(), t),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
            }
            .dropShadow(CircleShape, BAR_SHADOW),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = PwnMuted,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Box {
            Icon(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.symptom_chat_title))
            if (!seen) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .size(NOTICE_DOT)
                        // Said in the icon's own label, as the bell's dot is.
                        .clearAndSetSemantics { }
                        .background(PwnBleed, CircleShape),
                )
            }
        }
    }
}
