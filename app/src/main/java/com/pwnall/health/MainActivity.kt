package com.pwnall.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

internal enum class AppScreen {
    HOME,
    MEDICINE,

    /** Reached from the profile's front page: the instruments that have no form of their own. */
    DEVICES,

    /** Also from the profile: a history kept in another app, through Health Connect or its export file. */
    IMPORT,
    ADD,
    CALENDAR,
    PROFILE,

    /**
     * The live view of the accelerometer. Long press the home stop in a debuggable build, or seven
     * taps on the version line in About in any build.
     */
    SENSORS,
    TRACK,

    /** One walk from the track page's history, opened out: its map, its figures and its photographs. */
    WALK,
    MOOD,
    BREATHING,

    /** What the blood work says. The form that adds to it is [LABS_ADD], a page of its own. */
    LABS,

    /** The panel form: reached from the "+" tab and from the button under the results. */
    LABS_ADD,
    ALCOHOL,
    SMOKING,
    BATHROOM,
    INTIMATE,
    ROUTINES,
    CYCLE,

    /** Behind the home screen's level badge: the year's XP and what earned it. */
    LEVELS,

    /** The months of the record on paper. A page of its own: it is made to leave the phone. */
    REPORT,

    /** One of the home screen's three cards, opened out to fill the screen. */
    METRIC,

    /** The vitamins and minerals against their daily figures, opened from the meal flow's first page. */
    VITAMINS,

    /** The work timer, from the add sheet and from its widget. */
    WORK,

    /** Where it hurt and how often. Painting a new one is [PAIN_ADD], a page of its own. */
    PAIN,

    /** The body to paint a pain on: reached from the "+" tab and from the button on [PAIN]. */
    PAIN_ADD,

    /**
     * A symptom, one question at a time beside what the day held: reached from the home screen's chat
     * circle, and from the "+" tab of an earlier calendar day.
     */
    SYMPTOM_ADD,

    /** Time in the feed apps and the eye and head reminders it feeds: from the "+" tab's health section. */
    SCREEN_TIME,
}

class MainActivity : ComponentActivity() {
    /**
     * Where a widget asked us to go. Held as state so a second tap re-routes a running app.
     *
     * Any app can send one: this is the launcher's activity, so it is exported, and the extra is a
     * plain string. That is let be. A route only names a screen the navigation's `followRoute` knows,
     * and none of them writes anything until the person does, behind the lock like any start; a name
     * it does not know is dropped. Android lets an app start another's activity only while it is in
     * front itself, so the most a stranger gets is this app opened on a page a widget opens.
     */
    private val route = mutableStateOf<String?>(null)

    /** Whether this window has been let in yet ([composesApp]). Never saved: a window built anew starts shut. */
    private var letIn = false

    /** Sent away from onCreate as a second window over the app; see [stacksOnTheApp]. */
    private var stacked = false

    /** Whether this window is the one to sweep the unnamed attachments and has not begun yet. */
    private var sweepPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A second window over the app's own goes before it is drawn, and the app comes forward as
        // it was left. Android stacks one whenever the intent that reached a running task is shaped
        // unlike the one the task was started with — the launcher's icon after a start from a
        // widget, whose intent carries a data URI of Glance's own — and it hides the window
        // underneath, alive or waiting to be rebuilt with a form in it, behind a fresh home screen.
        // A route goes with it: what is open over the app is the person's own doing, and the
        // recording notification already leaves it there.
        if (savedInstanceState == null && stacksOnTheApp()) {
            stacked = true
            finish()
            return
        }
        instances++
        enableEdgeToEdge()
        AppLock.applySecure(this)
        // The widget's tap is a one-shot, but the Intent that carried it outlives every recreation,
        // and Recents hands it back once the window it opened has been finished: replaying it would
        // throw away the trail the restored screens have just put back. See [startingRoute].
        route.value = startingRoute(
            restored = savedInstanceState != null,
            savedRoute = savedInstanceState?.getString(KEY_ROUTE),
            launchFlags = intent?.flags ?: 0,
            intentRoute = intent?.getStringExtra(WidgetRoute.EXTRA),
        )
        // The food catalogue is a megabyte of text, and the first food page to ask for it would parse
        // it on the main thread, inside its opening frame. It is loaded here instead, off that thread,
        // while the first screen draws; a window rebuilt later finds it loaded and this does nothing.
        lifecycleScope.launch(Dispatchers.Default) { warmFoodCatalog() }
        // Anything an export left behind, swept on the way in. A shared report is written into the
        // cache and handed out by URI, and left alone it would stay there until the next report
        // replaced it — which for most people is never. A sheet naming their medicines, their blood tests and,
        // if they chose to send it, their sexual activity is not a file to leave lying in a cache
        // for a year. The backup spool is swept for the same reason: a killed export leaves a
        // plain-text copy of the whole record behind it. Only on a fresh start, not on every
        // recreation — the dark theme switched, the phone turned, the language changed: those come
        // in the middle of a visit, while an app a report or a walk was just handed to may still be
        // reading the file. Nor from a second window, which another app can start into its own task
        // while the app's own stands with a report just handed out of it.
        if (savedInstanceState == null && instances == 1) {
            lifecycleScope.launch(Dispatchers.IO) { sweepExportLeftovers(applicationContext) }
        }
        // The same start is also when the attachments no saved entry names any more are let go —
        // but only one no form can be open behind. A window stacked over the app's own is sent away
        // above; the count and the root stay as the second guard, because a form in the window
        // underneath may yet be saved with the file it holds. A window rebuilt before it had begun the
        // sweep — the phone turned while an encrypted record was still opening — hands it on
        // ([onSaveInstanceState]) rather than dropping it until the next fresh start.
        sweepPending = (savedInstanceState?.getBoolean(KEY_SWEEP) ?: true) && instances == 1 && isTaskRoot
        // The first composition asks for the database on the main thread, and on the start after
        // the encryption switch was moved that ask rewrites the whole file first, under
        // StepsDb.get's lock, for as long as the record is large. On that one start the database
        // is asked for off the main thread before anything is composed, and the window stays blank
        // until it has answered, so that a large record is a wait and not an unresponsive app. Only
        // when this window is the first to ask, though: "Close the app" under the switch ends the
        // process and opens nothing, and the step service, back on its own, may have converted the
        // file before anyone looks. An encrypted record takes the same road on every start:
        // opening it means the Keystore, the file's header and a preferences write before the
        // first frame, which is main-thread time nobody asked for. A plain record composes at
        // once.
        val encryption = DatabaseEncryption.state(this)
        if (encryption.pending || encryption.wantsCipher || DatabaseKey.exists(this)) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { StepsDb.get(applicationContext) }
                showApp()
                // Only once that open has answered: the sweep reads the database, and a second
                // caller asking a Keystore that will not answer is only a second failure.
                sweepAttachments()
            }
        } else {
            showApp()
            sweepAttachments()
        }
    }

    /**
     * What this window was handed and has not acted on goes to the one rebuilt in its place: a route
     * not followed yet, and the sweep not begun. A window turned while an encrypted record was still
     * opening has composed and swept nothing, and the one restored after it would otherwise do neither.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ROUTE, route.value)
        outState.putBoolean(KEY_SWEEP, sweepPending)
    }

    /**
     * Whether a MainActivity, alive or waiting to be rebuilt, already roots this task. Asked of the
     * app's own tasks only: a window another app started into its own task is not a second one.
     */
    private fun stacksOnTheApp(): Boolean = !isTaskRoot && runCatching {
        getSystemService(ActivityManager::class.java).appTasks.any {
            val task = it.taskInfo
            task?.taskId == taskId && task.baseActivity?.className == javaClass.name
        }
    }.getOrDefault(false)

    private fun sweepAttachments() {
        if (!sweepPending) return
        sweepPending = false
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sweepUnnamedAttachments(applicationContext) }
                .onFailure {
                    // A window closed halfway through is not a sweep that failed: the next start sweeps.
                    if (it is CancellationException) throw it
                    Log.w("MainActivity", "attachments not swept", it)
                }
        }
    }

    private fun showApp() = setContent {
        PwnFitTheme(darkTheme = isSystemInDarkTheme()) {
            RecordAppOpenDays()
            // The app stays composed under the lock rather than being swapped out for it. A
            // swap disposes every launcher waiting for a result — the file picker's callback
            // among them — and a save that returns to a disposed callback leaves the empty
            // file the picker already created. FLAG_SECURE and the opaque lock screen are what
            // keep the content unseen; the composition underneath keeps the work alive. Only in
            // a window that has been let in, though: one built while the lock is shut would put
            // its dialogs back after the lock's own window, and so above it — see [composesApp].
            letIn = composesApp(letIn, AppLock.unlocked.value)
            if (letIn) StepsApp(route.value) { route.value = null }
            if (!AppLock.unlocked.value) LockScreen(::unlock)
        }
    }

    /**
     * Whether a prompt is up is the lock's word, not this window's. A window another app opened into
     * its own task shares the lock, and its prompt calls this one's off without an answer: a flag of
     * this window's own would stay up for good, and its Unlock button would do nothing from then on.
     */
    private fun unlock() {
        if (AppLock.prompting) return
        AppLock.prompt(this) { ok -> AppLock.unlocked.value = ok }
    }

    override fun onStart() {
        super.onStart()
        val enabled = AppLock.enabled(this)
        if (!enabled) AppLock.unlocked.value = true
        if (shouldPrompt(enabled, AppLock.unlocked.value, AppLock.prompting)) unlock()
        // Every time the app comes forward, not only on the fresh start onCreate sweeps at: this
        // window is the task's root, which back does not finish, and the step service keeps the
        // process, so a fresh start can be weeks away — see [sweepStaleShares].
        lifecycleScope.launch(Dispatchers.IO) { sweepStaleShares(cacheDir, System.currentTimeMillis()) }
    }

    /** Something is in front of the app: the person's own doing, or the app's, or another app's. */
    override fun onStop() {
        super.onStop()
        // A rotation stops the activity on its way to rebuilding it, and that is not leaving.
        if (isChangingConfigurations) return
        val unlocking = unlockUnderway(AppLock.prompting, AppLock.unlocked.value)
        if (shouldRelock(AppLock.enabled(this), unlocking)) AppLock.unlocked.value = false
    }

    /** Two things outlive this instance and should not: the prompt, and being let in. */
    override fun onDestroy() {
        super.onDestroy()
        // Never counted, and the prompt and the lock belong to the window it was stacked over.
        if (stacked) return
        instances--
        AppLock.cancelPrompt()
        // The unlocked flag belongs to the process, so that turning the phone does not ask
        // again — but that also means it survives this instance, and a departure that ends in
        // destruction rather than in a stop has no other place to shut the app: the step service
        // keeps the process alive, and with it a "yes" nobody gave. A rebuilt configuration is the
        // one destruction that is not leaving.
        if (!isChangingConfigurations && AppLock.enabled(this)) AppLock.unlocked.value = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = intent.getStringExtra(WidgetRoute.EXTRA)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val db = StepsDb.get(this@MainActivity)
            val profile = db.dao().profileNow()
            followProfile(this@MainActivity, profile)
            AutoBackup.reschedule(this@MainActivity)
            ScreenTime.apply(this@MainActivity)
            // After the three above, which need nothing of it: a night it saves goes to Health Connect
            // straight away, and that waits behind an exchange already running — put first, it would
            // hold the reminders, the nightly copy and the screen-time watch back with it.
            detectEstimatedSleep(this@MainActivity)
            // Opening the app is the moment its figures should already be the watch's and the food
            // app's. Here rather than only in the composition, because a process that survived in
            // the background comes back through this and through no LaunchedEffect at all — and
            // the exchange's own interval is what keeps a returned dialog from re-running it.
            profile?.let { autoSyncHealthConnectIfDue(this@MainActivity, db, it) }
        }
    }

    private companion object {
        /** The windows of the app alive in this process; only the main thread counts them. */
        var instances = 0

        const val KEY_ROUTE = "route"
        const val KEY_SWEEP = "sweep"
    }
}

/**
 * The profile the app counts with: the stored answers, with the latest measured weight and height
 * laid over the two that onboarding gave.
 *
 * For reading only. The stored row keeps the onboarding figures as the baseline the activity
 * timeline starts from, so whatever saves a profile copies the stored row and never this one.
 */
internal fun UserProfile.inEffect(measuredWeightKg: Double?, measuredHeightCm: Int?): UserProfile =
    copy(weightKg = measuredWeightKg ?: weightKg, heightCm = measuredHeightCm ?: heightCm)

/**
 * [inEffect] for the readers that have no composition to hand it to them — a widget, the hourly
 * worker, the Health Connect export — so that they count from the same weight the screens do.
 */
internal suspend fun StepsDao.profileInEffect(): UserProfile? =
    profileNow()?.inEffect(latestMeasuredWeightKg().first(), latestMeasuredHeightCm().first())

/**
 * What a profile switches outside the database, brought in line with it: the hourly reminders with
 * the medicine alarms, and the two widgets an age or a sex takes off the launcher's list. One
 * function for every way a profile arrives — each return to the app, a profile restored from a
 * backup, and the first one onboarding saves. Left to the next return, the last two would wait until
 * the app was left and come back to: a child's restored profile would keep the tobacco widget and one
 * with reminders off its alarms, and a first profile would have no hourly reminder and neither of the
 * two widgets, which stay off the launcher's list for as long as there is no profile to offer them to.
 */
internal suspend fun followProfile(context: Context, profile: UserProfile?) {
    Reminders.apply(context, profile?.remindersEnabled == true)
    applyHabitWidgetVisibility(context, profile)
}

@Composable
private fun StepsApp(route: String?, onRouteHandled: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { StepsDb.get(context).dao() }
    var baseProfile by remember { mutableStateOf<UserProfile?>(null) }
    var measuredWeightKg by remember { mutableStateOf<Double?>(null) }
    var measuredHeightCm by remember { mutableStateOf<Int?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }

    // Only while the window is in sight, as every page collects: the step service keeps the process,
    // and with it a window left days ago, and every row a sync writes in the night would otherwise run
    // the weight and height queries again for nobody. Room hands the current answers over on the way back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(dao, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { dao.latestMeasuredWeightKg().collect { measuredWeightKg = it } }
            launch { dao.latestMeasuredHeightCm().collect { measuredHeightCm = it } }
            dao.profile().collect {
                baseProfile = it
                profileLoaded = true
            }
        }
    }
    // Keyed on existence, not on the profile: it fires once when the profile first loads — or the
    // moment onboarding saves one, with the Health Connect access just granted there — and stays
    // quiet through every later edit. That second case is the one onResume cannot cover: the
    // activity is already resumed when onboarding saves, so nothing else would sync until the app
    // was left and come back to. The sync throttles itself either way.
    LaunchedEffect(baseProfile == null) {
        baseProfile?.let { autoSyncHealthConnectIfDue(context, StepsDb.get(context), it) }
    }
    val profile = baseProfile?.inEffect(measuredWeightKg, measuredHeightCm)

    when {
        !profileLoaded -> Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.loading))
            }
        }

        baseProfile == null -> {
            // A widget tap with no screen to act on yet is dropped, not saved up to open the
            // water dialog over the home screen the moment onboarding ends.
            LaunchedEffect(route) { if (route != null) onRouteHandled() }
            OnboardingScreen(
                onComplete = {
                    StepsDb.get(context).saveProfile(it)
                    // Followed here and not at the next resume: the activity is resumed already,
                    // and onResume read no profile to follow. Off the main thread, as it is there.
                    withContext(Dispatchers.IO) { followProfile(context, it) }
                },
            )
        }

        // Provided once, here, where the profile is already in hand: the food rows that read it
        // are leaves of three different screens and none of them has another reason to see a
        // profile — see [LocalAllergyAnswer].
        else -> CompositionLocalProvider(LocalAllergyAnswer provides profile!!.allergyAnswer()) {
            MainNavigation(
                profile = profile,
                baseProfile = baseProfile!!,
                route = route,
                onRouteHandled = onRouteHandled,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainNavigation(
    profile: UserProfile,
    baseProfile: UserProfile,
    route: String?,
    onRouteHandled: () -> Unit,
) {
    // A trail, not a single screen: back walks it, so leaving the editor returns to the calendar
    // that opened it rather than dumping everyone on the home screen.
    val history = rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.map(AppScreen::name) },
            restore = { names -> restoredTrail(names).toMutableStateList() },
        ),
    ) { mutableStateListOf(AppScreen.HOME) }
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var addDay by rememberSaveable { mutableStateOf<String?>(null) }
    var widgetDrink by rememberSaveable { mutableStateOf<String?>(null) }
    var addKind by rememberSaveable { mutableStateOf("") }
    var addJournal by rememberSaveable { mutableStateOf<String?>(null) }
    // A workout the chat heard of, for the workouts page to open on: Freestyle's page for logging it.
    var routineJournal by rememberSaveable { mutableStateOf<String?>(null) }
    var drinkJournal by rememberSaveable { mutableStateOf<String?>(null) }
    var chatLabDocument by rememberSaveable { mutableStateOf<String?>(null) }
    // Which visit the medicine tab should open on arrival, when the calendar sent us there.
    var openVisitId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Which course the medicine tab should open on arrival, by its createdAt, when a chat's card sent us there.
    var openCourseAt by rememberSaveable { mutableStateOf<Long?>(null) }
    // A visit the symptom chat suggested, for the medicine tab to open its form on: the specialty, and
    // the note. Two strings rather than the draft, so a Bundle takes them as they are.
    var bookSpecialty by rememberSaveable { mutableStateOf<String?>(null) }
    var bookNote by rememberSaveable { mutableStateOf("") }
    // The chat row an opened record came from, when a chat's card opened it over the chat's own
    // editor: closing the record's editor goes back to that chat, not to the list under it.
    var returnToEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var journalReturnEntry by rememberSaveable { mutableStateOf<Long?>(null) }
    var journalReturnShot by remember { mutableStateOf<ImageBitmap?>(null) }
    // Which of the home cards [AppScreen.METRIC] is showing. Held by name for the reason the trail
    // above is: a build that drops a card must not crash on a name written by the build before it.
    var openMetric by rememberSaveable { mutableStateOf(HomeMetric.STEPS.name) }
    // Which route [AppScreen.WALK] is showing, by its id: the page reads the walk itself, so a name
    // or a photograph changed while it is open is the one shown.
    var openWalkId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Whether the add tab is standing on a workout that leaving would throw away, as its form last
    // said — see [workoutInputAtStake]. A session is written down by its Save and by nothing sooner.
    var workoutAtStake by remember { mutableStateOf(false) }
    // A way off that workout, held while the person is asked whether to discard it: a tab's tap or
    // a route from outside, taken only on a yes. Not saved: a question lost to a rotation loses
    // nothing — the workout is still standing, and the tap can be made again.
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    val screen = history.last()
    // What each screen remembered while it was standing: the profile's open section, a list left
    // halfway down. A screen that is not composed keeps nothing of its own, so without a holder
    // every departure is a demolition and every return lands on a front page.
    val holder = rememberSaveableStateHolder()
    // The app as it stood under each panel of the trail, photographed as the panel opened: [shots]
    // at an index is what the screen at that index of [history] was opened over, and the editor's
    // own is apart because it is drawn over whatever screen is standing. See [BackdropCamera].
    val camera = rememberBackdropCamera()
    val shots = remember { mutableStateMapOf<Int, ImageBitmap>() }
    var editorShot by remember { mutableStateOf<ImageBitmap?>(null) }
    // The picture the chat's own editor stood over while a record it opened stands in its place: what the
    // chat's panel is drawn over again once that record is closed.
    var returnShot by remember { mutableStateOf<ImageBitmap?>(null) }
    var arrivingBack by remember { mutableStateOf(false) }
    // Up for exactly one composition: the one that lands back on a page — see [LocalArrivingBack].
    LaunchedEffect(arrivingBack) { arrivingBack = false }
    // The picture of the page being returned to, laid over it until it stands where it was left.
    // The page comes back from its saved state with its rows still loading, and for the frame or
    // two before they arrive it is a shorter page drawn at the wrong place — see [ReturnScroll].
    // The panel fell away over this same picture, so what is seen is the page where it was left,
    // then the same page live. Never saved: a page restored after a rotation has no panel to have
    // fallen off it.
    var veil by remember { mutableStateOf<ImageBitmap?>(null) }
    val returnHold = remember { ReturnHold() }
    LaunchedEffect(veil) {
        if (veil == null) return@LaunchedEffect
        returnHold.awaitSettled()
        veil = null
    }

    fun open(next: AppScreen) {
        // The trail as it is now, not [screen] as this composition read it: two calls before the next
        // frame would otherwise stack the same page twice.
        if (next == history.last()) return
        camera.shoot { shot ->
            arrivingBack = false
            veil = null
            history.add(next)
            val kept = shot?.takeIf { next !in OPAQUE_TABS && next !in STANDING_PAGES }
            if (kept != null) shots[history.lastIndex] = kept else shots.remove(history.lastIndex)
        }
    }

    fun edit(entryId: Long) {
        camera.shoot { shot ->
            arrivingBack = false
            veil = null
            editorShot = shot
            editingEntryId = entryId
        }
    }

    fun openWalk(trackId: Long) {
        openWalkId = trackId
        open(AppScreen.WALK)
    }

    // Leaving the add flow by any road — back, Save, another tab — resets what a widget, the chat or
    // the calendar preselected, or the next plain "+" would reopen that form. The two roads below
    // are every road: back and Save both go through [back].
    fun forgetAddForm() {
        addKind = ""
        addJournal = null
        addDay = null
    }

    // The bottom bar is a set of roots, not steps in a journey: switching tabs starts a new trail
    // instead of stacking Home on Sync on Home.
    fun openRoot(next: AppScreen) {
        journalReturnEntry?.let { holder.removeState(editorKey(it)) }
        journalReturnEntry = null
        journalReturnShot = null
        if (history.lastOrNull() == AppScreen.ADD && next != AppScreen.ADD) forgetAddForm()
        // Home is what a new trail falls back to, so a picture of it is only true when it is home
        // that is standing now; from anywhere else the page under the new root goes unphotographed.
        val fromHome = history.size == 1 && editingEntryId == null && next !in OPAQUE_TABS
        camera.shoot { shot ->
            arrivingBack = false
            veil = null
            // A tab is a new trail, and the screens stacked on the old one are not coming back: what
            // they remembered goes with them, which is what keeps the holder's map the size of a
            // trail rather than of a whole session. The two roots — home, and the tab being opened —
            // keep theirs, so a tab returned to is still the tab that was left.
            history.drop(1).forEach { if (it != next) holder.removeState(it.name) }
            history.clear()
            shots.clear()
            history.add(AppScreen.HOME)
            if (next != AppScreen.HOME) history.add(next)
            if (fromHome && shot != null) shots[history.lastIndex] = shot
        }
    }

    fun back() {
        val returningJournal = journalReturnEntry != null &&
            (history.lastOrNull() == AppScreen.ADD || history.lastOrNull() == AppScreen.ROUTINES)
        if (history.lastOrNull() == AppScreen.ADD) forgetAddForm()
        // The screen walked away from is gone for good, and what it remembered goes with it.
        //
        // The veil only after a panel: its fall uncovered this same picture, so the picture staying
        // up is the page standing still. A tab leaving for a tab — Save or back on the add tab — is
        // the cross-fade instead, and a picture over it would be one frame of the page as it was
        // before the save, laid over the very fade that shows it as it is now.
        if (history.size > 1) {
            arrivingBack = true
            val shot = shots.remove(history.lastIndex)
            val leaving = history.removeAt(history.lastIndex)
            veil = shot.takeIf { leaving !in PRIMARY_TABS && leaving !in STANDING_PAGES }
            holder.removeState(leaving.name)
        }
        if (returningJournal) {
            editingEntryId = journalReturnEntry
            editorShot = journalReturnShot
            journalReturnEntry = null
            journalReturnShot = null
        }
    }

    /**
     * Shutting the editor, rather than merely covering it: its slot is emptied on the way out.
     * [fell] when its panel has just fallen away over [editorShot], which then stays up as the veil;
     * a save shuts the panel in place, with no fall, over a page the save has just changed.
     * [leaving] when the app is going somewhere else altogether — a widget's route — and no editor
     * is to be left standing over the page it opens.
     */
    fun closeEditor(fell: Boolean = false, leaving: Boolean = false) {
        // A record a chat opened over its own editor: back or save returns to that chat, whose
        // state the holder kept under its row.
        val back = editorAfterClose(editingEntryId, returnToEntryId, leaving)
        if (back != null) {
            holder.removeState(editorKey(editingEntryId))
            returnToEntryId = null
            // Back to the chat as it stood, as any panel's fall lands on the page under it: the chat's
            // panel was standing in the picture the record's fall uncovers, so it does not rise again,
            // and it is drawn over what it stood over before the record opened.
            arrivingBack = true
            veil = if (fell) editorShot else null
            editorShot = returnShot
            returnShot = null
            editingEntryId = back
            return
        }
        // The chat under a record is not come back to on the way out, so what its editor remembered
        // goes with the record's own, and so does the picture it stood over.
        returnToEntryId?.let { holder.removeState(editorKey(it)) }
        returnToEntryId = null
        returnShot = null
        if (editingEntryId != null) {
            arrivingBack = true
            if (fell) veil = editorShot
        }
        holder.removeState(editorKey(editingEntryId))
        editingEntryId = null
        editorShot = null
    }

    // What this profile's age leaves in the app. Read once here because the answer removes whole
    // screens rather than closing them: a door with no callback is not drawn at all, and a trail
    // already standing on one of these — restored from a saved instance, or left there when a
    // restored backup changed the birth date underneath — is walked back to the home screen.
    // Kept current through midnight: a chat left open overnight would file its records under the day before.
    val today = rememberToday()
    val habits = habitsOffered(profile.birthDate, today)
    val intimate = sexLogOffered(profile.birthDate, today)

    fun openJournal(event: ChatJournalEvent) {
        val destination = event.destination() ?: return
        val payload = encodeJournalEvents(listOf(event))

        // A form a record's own chat opens stands in for that record, which back brings up again.
        fun coverEditor() {
            if (editingEntryId != null) {
                journalReturnEntry = editingEntryId
                journalReturnShot = editorShot
                editingEntryId = null
            }
        }
        when (destination) {
            ChatJournalDestination.WORKOUT -> {
                coverEditor()
                routineJournal = payload
                open(AppScreen.ROUTINES)
            }

            ChatJournalDestination.FOOD,
            ChatJournalDestination.WORKOUT_PLAN,
            ChatJournalDestination.MEASUREMENT,
            ChatJournalDestination.SLEEP,
            -> {
                coverEditor()
                addJournal = payload
                addDay = event.prefillDay(addDay?.let(LocalDate::parse) ?: today)?.toString() ?: addDay
                addKind = when (event.type) {
                    ChatJournalType.FOOD_LOG -> KIND_EAT
                    ChatJournalType.WORKOUT_PLAN -> KIND_ACTIVITY
                    ChatJournalType.SLEEP_LOG -> "SLEEP"
                    ChatJournalType.HEIGHT_LOG -> "HEIGHT"
                    ChatJournalType.HEART_RATE_LOG -> "HEART_RATE"
                    ChatJournalType.GIRTH_LOG -> "GIRTH"
                    else -> KIND_MEASUREMENT
                }
                open(AppScreen.ADD)
            }

            ChatJournalDestination.SYMPTOM -> open(AppScreen.SYMPTOM_ADD)

            ChatJournalDestination.PAIN -> open(AppScreen.PAIN_ADD)

            ChatJournalDestination.MOOD -> open(AppScreen.MOOD)

            ChatJournalDestination.MEDICINE, ChatJournalDestination.DOCUMENT -> open(AppScreen.MEDICINE)

            ChatJournalDestination.WORK -> open(AppScreen.WORK)

            ChatJournalDestination.BREATHING -> open(AppScreen.BREATHING)

            ChatJournalDestination.WATER -> {
                drinkJournal = payload
                widgetDrink = "WATER"
            }

            ChatJournalDestination.CAFFEINE -> {
                drinkJournal = null
                widgetDrink = "CAFFEINE"
            }

            ChatJournalDestination.ALCOHOL -> if (habits) open(AppScreen.ALCOHOL)

            ChatJournalDestination.SMOKING -> if (habits) open(AppScreen.SMOKING)

            ChatJournalDestination.BATHROOM -> open(AppScreen.BATHROOM)

            ChatJournalDestination.CYCLE -> open(AppScreen.CYCLE)

            ChatJournalDestination.INTIMATE -> if (intimate) open(AppScreen.INTIMATE)

            ChatJournalDestination.LABS -> open(AppScreen.LABS_ADD)

            ChatJournalDestination.PROFILE -> open(AppScreen.PROFILE)
        }
    }

    // Where a widget or a notification asked to go. Apart from the effect that hears of the route,
    // because a route off an unsaved workout is followed only once the person has said so.
    fun followRoute(asked: String?) {
        when (asked) {
            WidgetRoute.FOOD -> {
                closeEditor(leaving = true)
                addKind = KIND_EAT
                openRoot(AppScreen.ADD)
            }

            WidgetRoute.MOOD -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.MOOD)
            }

            WidgetRoute.EXERCISE -> {
                closeEditor(leaving = true)
                addKind = KIND_ACTIVITY
                openRoot(AppScreen.ADD)
            }

            WidgetRoute.WATER, WidgetRoute.CAFFEINE -> widgetDrink = asked

            WidgetRoute.BATHROOM -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.BATHROOM)
            }

            WidgetRoute.SMOKING -> if (habits) {
                closeEditor(leaving = true)
                openRoot(AppScreen.SMOKING)
            }

            WidgetRoute.ALCOHOL -> if (habits) {
                closeEditor(leaving = true)
                openRoot(AppScreen.ALCOHOL)
            }

            WidgetRoute.INTIMATE -> if (intimate) {
                closeEditor(leaving = true)
                openRoot(AppScreen.INTIMATE)
            }

            WidgetRoute.CYCLE -> if (profile.sex == "FEMALE") {
                closeEditor(leaving = true)
                openRoot(AppScreen.CYCLE)
            }

            WidgetRoute.TRACK -> openRoot(AppScreen.TRACK)

            WidgetRoute.WORK -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.WORK)
            }

            WidgetRoute.SCREEN_TIME -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.SCREEN_TIME)
            }

            WidgetRoute.LEVELS -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.LEVELS)
            }

            WidgetRoute.MEDICINE -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.MEDICINE)
            }

            WidgetRoute.BREATHING -> {
                closeEditor(leaving = true)
                openRoot(AppScreen.BREATHING)
            }
        }
    }

    LaunchedEffect(route) {
        if (route != null) {
            drinkJournal = null
            widgetDrink = null
        }
        // The route is handled either way: held, it is the question below that follows it or drops it.
        if (routeLeavesWorkout(screen, workoutAtStake, route)) {
            leaving = { followRoute(route) }
        } else {
            followRoute(route)
        }
        if (route != null) onRouteHandled()
    }
    // Not while the lock screen is up: a dialog is a window of its own and would stack above it.
    // The route is kept, so the dialog opens the moment the lock is passed.
    if (AppLock.unlocked.value) {
        widgetDrink?.let { kind ->
            WidgetDrinkDialog(profile, kind, initialJournal = drinkJournal) {
                widgetDrink = null
                drinkJournal = null
            }
        }
        // The same question back asks on the workout's own form, for the roads that form cannot see.
        leaving?.let { leave ->
            WorkoutDiscardDialog(
                onDismiss = { leaving = null },
                onConfirm = {
                    leaving = null
                    leave()
                },
            )
        }
    }
    LaunchedEffect(habits, intimate) {
        val hidden = setOfNotNull(
            AppScreen.ALCOHOL.takeUnless { habits },
            AppScreen.SMOKING.takeUnless { habits },
            AppScreen.INTIMATE.takeUnless { intimate },
        )
        if (history.any { it in hidden }) openRoot(AppScreen.HOME)
    }

    val primary = screen in PRIMARY_TABS
    // Which slot of saved state the page below is filling. The editor is drawn over whichever
    // screen opened it while `screen` is still that screen, so one key for both would hand the
    // calendar and the form each other's remembered scroll. The record is part of the editor's key
    // as well: two entries opened in turn must not inherit each other's half-typed fields.
    val stateKey = if (editingEntryId != null) editorKey(editingEntryId) else screen.name
    // The sensor view's door, and only where the person holding the phone is the one tuning the
    // gate. The debuggable flag is the bit BuildConfig.DEBUG would carry, read from the package.
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    BackHandler(enabled = editingEntryId != null || history.size > 1) {
        if (editingEntryId != null) {
            // Only reached once the editor's panel has fallen: its own handler takes the back first.
            closeEditor(fell = true)
        } else {
            back()
        }
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The arrow in a page's top bar takes the same road as the system's own back: through the
    // dispatcher, so a page's own nested handler — an inner page, the editor's chooser — gets
    // its turn before the trail pops.
    val goBack: () -> Unit = { backDispatcher?.onBackPressed() }
    // Every panel open over the app raises this, and the bar goes away while any of them is up.
    val panels = remember { PanelCount() }
    // The window shrinks to the keyboard's top edge, and the pill is anchored to the window's foot,
    // so while typing it would stand on the keyboard over the very field being typed into — the add
    // tab's note sits right above its Save. It steps aside for as long as the keyboard is up.
    val typing = WindowInsets.isImeVisible
    // A tab, no editor over it, nothing held open in front of it, and nobody typing.
    val barUp = primary && editingEntryId == null && panels.open == 0 && !typing
    // The bar's measured height, kept while a panel hides it so the page behind keeps its padding.
    var barReserve by remember { mutableStateOf(BAR_RESERVE) }
    // No `bottomBar` slot: Scaffold would reserve the bar's height and rule a band of page colour
    // across the foot of every screen — a hard edge that would cut whatever was scrolling past it
    // in half. The bar is drawn over the page instead, and what has to clear it clears it by padding
    // itself with [LocalBottomBarInset]. `insets` is the system bars alone.
    Scaffold { insets ->
        // Everything opened from inside the app is held over the screen it was opened from rather
        // than swapped in for it: a panel that rises, leaves a strip of what it covers showing, and
        // is let go of by the downward pull the report's own pickers already answer to. The five
        // tabs are the exception — they are places the app stands, not things held open over it.
        // Workouts are the exception the other way: the place a session is chosen and run from, a
        // page of its own that no pull of the thumb lets go of.
        val sheeted = !(primary || screen in STANDING_PAGES) || editingEntryId != null
        // Nor is the track page, which is a map from edge to edge: no panel and no inset — it runs
        // under the status and navigation bars and keeps its own controls clear of them. The walk
        // page opened from it is a panel again, over a picture of the map.
        val edgeToEdge = screen == AppScreen.TRACK && editingEntryId == null
        // What leaving the standing page lands on, as it looked when this page was opened over it:
        // the backdrop of the panel the page is held in, and of the meal flow's last step, which
        // leaves the add tab altogether. A picture rather than the page itself — see
        // [BackdropCamera] for why a screen at this level cannot be kept composed behind a panel.
        // None where there is no picture to show: a trail restored after the process died, or a
        // widget that opened a panel while some other page was standing.
        val behind = if (editingEntryId != null) editorShot else shots[history.lastIndex]
        // Reserve the measured bar height for every screen, including nested editors and
        // summaries. Their final actions remain inside the usable viewport at any font scale. When
        // a page is held open as a panel it is the panel that wears this, and the top inset goes
        // with it: the strip of screen above the panel is the one thing left showing of the page
        // underneath, and a page inside the panel has no status bar of its own to clear.
        val hostModifier = if (sheeted) {
            Modifier.padding(bottom = insets.calculateBottomPadding())
        } else {
            Modifier.padding(insets)
        }
        val layoutDirection = LocalLayoutDirection.current
        // The sides go to the panel itself rather than to the host. In landscape the navigation
        // buttons and a camera cutout stand on a side edge, and a panel's back arrow and trailing
        // controls must clear them; padded here instead, the blurred page and the scrim behind the
        // panel would be pushed in too, and the page behind would be inset a second time.
        val panelSides = PaddingValues(
            start = insets.calculateStartPadding(layoutDirection),
            end = insets.calculateEndPadding(layoutDirection),
        )
        // No sideways swipe goes back or walks the tabs: one would close the mood dial's page under the
        // finger dragging it. Back is the arrow and the system's own back.
        Box(Modifier.fillMaxSize().filmedBy(camera)) {
            CompositionLocalProvider(
                // What a page has to keep clear at its foot. The system navigation inset is not in
                // it: Scaffold's own `insets` already carries that, and the bar wears it too.
                //
                // Not gated on a panel being open, though the bar goes away when one is: it is the
                // panel that zeroes this for its own content, so the page drawn BEHIND the panel
                // keeps the measurement it was laid out with and comes back where it was left.
                LocalBottomBarInset provides
                    if (primary && editingEntryId == null) barReserve else 0.dp,
                LocalPanels provides panels,
                LocalBackdropCamera provides camera,
                LocalReturnPhoto provides behind,
                LocalArrivingBack provides arrivingBack,
                LocalReturnHold provides returnHold,
                // Not from a saved entry being corrected: that editor is drawn over whatever screen
                // is standing, so the table would open underneath it.
                LocalOpenVitamins provides if (editingEntryId == null) ({ open(AppScreen.VITAMINS) }) else null,
            ) {
                // Which page the app is standing on, as a lambda rather than a block: the panel branch
                // and the tabs' branch below both draw it. Only the two reads that say WHERE the app is
                // become parameters; every write still goes to the real state.
                val pageFor: @Composable (AppScreen, Long?, Modifier) -> Unit = { shown, editor, modifier ->
                    when {
                        editor != null -> EntryEditorScreen(
                            modifier = modifier,
                            profile = profile,
                            entryId = editor,
                            onJournal = ::openJournal,
                            onLabDocument = { uri ->
                                chatLabDocument = uri
                                open(AppScreen.LABS_ADD)
                            },
                            onSaved = { closeEditor() },
                            // A course is the medicine package's to open; a journal row opens in this
                            // editor's place, and closing it comes back to the chat.
                            onOpenRecord = { ref ->
                                ref.id?.takeIf { ref.kind != LINK_COURSE }?.let { id ->
                                    returnToEntryId = editor
                                    returnShot = editorShot
                                    edit(id)
                                }
                            },
                        )

                        shown == AppScreen.HOME -> HomeScreen(
                            modifier = modifier,
                            profile = profile,
                            onCycle = { open(AppScreen.CYCLE) },
                            onMedicine = { open(AppScreen.MEDICINE) },
                            onLevels = { open(AppScreen.LEVELS) },
                            onOpenMetric = {
                                openMetric = it.name
                                open(AppScreen.METRIC)
                            },
                            onChat = { open(AppScreen.SYMPTOM_ADD) },
                        )

                        shown == AppScreen.ADD -> EntryEditorScreen(
                            modifier = modifier,
                            profile = profile,
                            entryId = null,
                            initialKind = addKind,
                            initialDay = addDay,
                            initialJournal = addJournal,
                            onSaved = {
                                // A save can end in the frame after another tab was tapped, while this page's
                                // scope is still alive: the way back is taken only while the add tab is up.
                                if (history.lastOrNull() == AppScreen.ADD) back()
                            },
                            onTrack = { open(AppScreen.TRACK) },
                            onWork = { open(AppScreen.WORK) },
                            onMood = { open(AppScreen.MOOD) },
                            onBreathing = { open(AppScreen.BREATHING) },
                            onLabs = { open(AppScreen.LABS_ADD) },
                            onJournal = ::openJournal,
                            onLabDocument = { uri ->
                                chatLabDocument = uri
                                open(AppScreen.LABS_ADD)
                            },
                            onAlcohol = if (habits) ({ open(AppScreen.ALCOHOL) }) else null,
                            onSmoking = if (habits) ({ open(AppScreen.SMOKING) }) else null,
                            onBathroom = { open(AppScreen.BATHROOM) },
                            onScreenTime = { open(AppScreen.SCREEN_TIME) },
                            onPain = { open(AppScreen.PAIN_ADD) },
                            // The chat of an earlier day, from the calendar's "+": today's is the home
                            // screen's circle, and the add hub lists it only for the day it cannot reach.
                            onSymptom = addDay?.takeIf {
                                it != today.toString()
                            }?.let { { open(AppScreen.SYMPTOM_ADD) } },
                            onIntimate = if (intimate) ({ open(AppScreen.INTIMATE) }) else null,
                            onRoutines = {
                                routineJournal = null
                                open(AppScreen.ROUTINES)
                            },
                            onDrink = {
                                drinkJournal = null
                                widgetDrink = it
                            },
                            onWorkoutAtStake = { workoutAtStake = it },
                        )

                        shown == AppScreen.MEDICINE -> MedicineScreen(
                            modifier = modifier,
                            profile = profile,
                            openVisitId = openVisitId,
                            onVisitOpened = { openVisitId = null },
                            bookVisit = bookSpecialty?.let { VisitDraft(it, bookNote) },
                            onBookOpened = {
                                bookSpecialty = null
                                bookNote = ""
                            },
                            openCourseAt = openCourseAt,
                            onCourseOpened = { openCourseAt = null },
                            onReport = { open(AppScreen.REPORT) },
                        )

                        shown == AppScreen.DEVICES -> DevicesScreen(
                            modifier = modifier,
                            profile = profile,
                            onBack = goBack,
                        )

                        shown == AppScreen.IMPORT -> DevicesScreen(
                            modifier = modifier,
                            profile = profile,
                            onBack = goBack,
                            imports = true,
                        )

                        shown == AppScreen.CALENDAR -> CalendarScreen(
                            modifier = modifier,
                            initialDay = null,
                            profile = profile,
                            onOpenVisit = {
                                openVisitId = it
                                open(AppScreen.MEDICINE)
                            },
                            onAdd = { day ->
                                addDay = day
                                addKind = ""
                                addJournal = null
                                open(AppScreen.ADD)
                            },
                            onEdit = ::edit,
                        )

                        shown == AppScreen.PROFILE -> ProfileScreen(
                            modifier = modifier,
                            profile = profile,
                            baseProfile = baseProfile,
                            onLabs = { open(AppScreen.LABS) },
                            onPain = { open(AppScreen.PAIN) },
                            onDevices = { open(AppScreen.DEVICES) },
                            onImport = { open(AppScreen.IMPORT) },
                            onSensors = { open(AppScreen.SENSORS) },
                        )

                        shown == AppScreen.METRIC -> MetricDetailScreen(
                            modifier = modifier,
                            profile = profile,
                            metric = HomeMetric.entries.firstOrNull { it.name == openMetric }
                                ?: HomeMetric.STEPS,
                            onClose = goBack,
                            onEdit = ::edit,
                        )

                        shown == AppScreen.SENSORS -> SensorScreen(modifier, onBack = goBack)

                        shown == AppScreen.TRACK -> TrackScreen(
                            modifier,
                            profile,
                            onBack = goBack,
                            onOpenWalk = ::openWalk,
                        )

                        // A trail restored without its walk is not a road the app takes; were it
                        // ever to happen, the track page it came from stands in for an empty one.
                        shown == AppScreen.WALK -> {
                            val walk = openWalkId
                            if (walk != null) {
                                WalkScreen(modifier, profile, walk, onBack = goBack)
                            } else {
                                TrackScreen(modifier, profile, onBack = goBack, onOpenWalk = ::openWalk)
                            }
                        }

                        // The evenings' drinks are set beside work only for a profile the app asks
                        // about drinks at all.
                        shown == AppScreen.WORK -> WorkScreen(modifier, onBack = goBack, alcohol = habits)

                        shown == AppScreen.MOOD -> MoodScreen(
                            modifier,
                            onBreathing = { open(AppScreen.BREATHING) },
                            onBack = goBack,
                        )

                        shown == AppScreen.BREATHING -> BreathingScreen(modifier, onBack = goBack)

                        shown == AppScreen.LABS -> LabResultsScreen(
                            modifier = modifier,
                            profile = profile,
                            onBack = goBack,
                            onAdd = { open(AppScreen.LABS_ADD) },
                        )

                        shown == AppScreen.LABS_ADD -> LabAddScreen(
                            modifier,
                            // The stored row, not the one in effect: this page saves the allergens
                            // a panel found back into the profile, and the measured weight and
                            // height would go over the onboarding baseline with them. It reads
                            // neither figure itself.
                            storedProfile = baseProfile,
                            onBack = {
                                chatLabDocument = null
                                goBack()
                            },
                            // Added from the results page, the results are the page under this one;
                            // from anywhere else they open over the finished flow.
                            onResults = {
                                chatLabDocument = null
                                if (history.getOrNull(history.lastIndex - 1) == AppScreen.LABS) {
                                    goBack()
                                } else {
                                    open(AppScreen.LABS)
                                }
                            },
                            initialDocument = chatLabDocument,
                        )

                        // Both branches carry their gate as well as their screen. The effect above
                        // walks a gated trail back to the home screen, but it runs after the first
                        // composition, and one frame of a screen this profile is not shown is one
                        // frame too many.
                        shown == AppScreen.ALCOHOL && habits -> AlcoholScreen(modifier, onBack = goBack)

                        shown == AppScreen.SMOKING && habits -> SmokingScreen(modifier, onBack = goBack)

                        shown == AppScreen.BATHROOM -> BathroomScreen(modifier, onBack = goBack)

                        shown == AppScreen.SCREEN_TIME -> ScreenTimeScreen(
                            modifier,
                            onBack = goBack,
                            onBreathing = { open(AppScreen.BREATHING) },
                        )

                        shown == AppScreen.INTIMATE && intimate -> IntimateScreen(modifier, onBack = goBack)

                        // The cycle screen is the app's one coloured page. Inside a panel there is
                        // no status bar for its violet to run under, so it begins at the panel's own
                        // rounded edge like every other page.
                        shown == AppScreen.CYCLE -> CycleScreen(modifier, onBack = goBack)

                        shown == AppScreen.ROUTINES -> RoutinesScreen(
                            modifier,
                            profile,
                            routineJournal,
                            onWalk = { open(AppScreen.TRACK) },
                            onBack = goBack,
                        )

                        shown == AppScreen.LEVELS -> LevelsScreen(modifier, onBack = goBack)

                        shown == AppScreen.REPORT -> DoctorReportScreen(modifier, profile, goBack)

                        shown == AppScreen.VITAMINS -> VitaminsScreen(modifier, profile, onBack = goBack)

                        shown == AppScreen.PAIN -> PainScreen(
                            modifier = modifier,
                            profile = profile,
                            onBack = goBack,
                            onAdd = { open(AppScreen.PAIN_ADD) },
                            onEdit = ::edit,
                            analysisCards = { journal -> PainInsightsCards(journal) },
                        )

                        // The day the calendar's "+" was pressed on, while the add hub it opened is
                        // still underneath; from anywhere else a new pain is today's.
                        shown == AppScreen.PAIN_ADD -> PainEntryPage(
                            modifier = modifier,
                            profile = profile,
                            entry = null,
                            onBack = goBack,
                            onDone = goBack,
                            day = addDay.takeIf { AppScreen.ADD in history },
                        )

                        // Filed like a pain: the calendar's day while its add hub is underneath, today otherwise.
                        shown == AppScreen.SYMPTOM_ADD -> SymptomChatScreen(
                            modifier = modifier,
                            profile = profile,
                            entry = null,
                            onBack = goBack,
                            onDone = goBack,
                            day = addDay.takeIf { AppScreen.ADD in history },
                            onBook = { draft ->
                                bookSpecialty = draft.specialty
                                bookNote = draft.note
                                open(AppScreen.MEDICINE)
                            },
                            onBreathing = { open(AppScreen.BREATHING) },
                            onLabs = { open(AppScreen.LABS_ADD) },
                            onJournal = ::openJournal,
                            onLabDocument = { uri ->
                                chatLabDocument = uri
                                open(AppScreen.LABS_ADD)
                            },
                            // A journal row opens in the editor over the chat's panel, whose back or
                            // save comes back to the chat; a course, on the medicine tab.
                            onOpenRecord = { ref ->
                                if (ref.kind == LINK_COURSE) {
                                    openCourseAt = ref.at
                                    open(AppScreen.MEDICINE)
                                } else {
                                    ref.id?.let { edit(it) }
                                }
                            },
                        )
                    }
                }
                if (edgeToEdge) {
                    holder.SaveableStateProvider(stateKey) { pageFor(screen, null, Modifier) }
                } else if (sheeted) {
                    holder.SaveableStateProvider(stateKey) {
                        PwnPageHost(
                            sheet = true,
                            onClose = goBack,
                            modifier = hostModifier,
                            topInset = insets.calculateTopPadding(),
                            sides = panelSides,
                            backdrop = behind?.let { shot -> { BackdropPhoto(shot) } },
                        ) { modifier ->
                            pageFor(screen, editingEntryId, modifier)
                        }
                    }
                } else {
                    // Only a tab replacing a tab is animated here: a panel brings its own rise and
                    // fall, and the branch above is left the moment one opens, so the page it covers
                    // is gone in the same frame. Each tab keeps its own slot of
                    // saved state, keyed by the tab being drawn rather than by the one standing, so
                    // the page fading out and the page fading in never share one.
                    TabTransition(screen) { tab ->
                        holder.SaveableStateProvider(tab.name) {
                            PwnPageHost(sheet = false, onClose = goBack, modifier = hostModifier) { modifier ->
                                pageFor(tab, null, modifier)
                            }
                        }
                    }
                }
                // Under the bar, which is in the picture too and comes back a frame after the page.
                veil?.let { ReturnVeil(it) }
            }
            if (barUp) {
                PwnBottomBar(
                    current = screen,
                    onSensors = if (debuggable) ({ open(AppScreen.SENSORS) }) else null,
                    // The bar stands right under a running session's own buttons, and a new trail takes
                    // everything the add tab held with it: a tab away from a workout is asked about
                    // first, as back off it is.
                    onNavigate = { next ->
                        if (leavesWorkout(screen, workoutAtStake, next)) {
                            leaving = { openRoot(next) }
                        } else {
                            openRoot(next)
                        }
                    },
                    onReserve = { barReserve = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** The editor's own slot, one per record, so no two entries ever share what was typed into one. */
private fun editorKey(entryId: Long?) = "editor/$entryId"

/**
 * Whether a tab's tap has to ask before it is followed: the add tab is [standing] on a workout that
 * leaving would throw away — [workoutAtStake], see [workoutInputAtStake] — and [next] is somewhere
 * else. A new trail drops everything the add tab remembered, and a session is nowhere but there
 * until its Save. The add tab's own stop keeps what the tab holds, so that tap goes straight through.
 *
 * [standing] is asked as well as the flag because the form goes on saying it for the moment its
 * page takes to fade out under the tab that replaced it.
 */
internal fun leavesWorkout(standing: AppScreen, workoutAtStake: Boolean, next: AppScreen): Boolean =
    workoutAtStake && standing == AppScreen.ADD && next != AppScreen.ADD

/**
 * The same for a [route] from a widget or a notification. Water and caffeine open their dialog over
 * the app as it stands, and the exercise shortcut opens the very form the workout is on; every
 * other route starts a new trail or — the meal's — turns the add tab into another form.
 */
internal fun routeLeavesWorkout(standing: AppScreen, workoutAtStake: Boolean, route: String?): Boolean =
    workoutAtStake && standing == AppScreen.ADD && route != null &&
        route !in setOf(WidgetRoute.WATER, WidgetRoute.CAFFEINE, WidgetRoute.EXERCISE)

/**
 * The editor that stands once the one on screen is shut, if any does: the chat whose card opened
 * this record over the chat's own editor, [returnTo], which back and Save go back to. Not when the
 * app is [leaving] for a route from a widget or a notification. A route opens a root of its own
 * and touches no editor, so a chat put back up would stand over that root: a tap on the food widget
 * would land in the symptom chat, with the meal's form waiting under it.
 */
internal fun editorAfterClose(editing: Long?, returnTo: Long?, leaving: Boolean): Long? =
    returnTo.takeUnless { leaving || it == editing }

/**
 * The trail of screens a rebuilt window reads back from its saved state. A build that drops a screen
 * must not crash on a trail saved by the one before it: valueOf throws on a name it does not know
 * rather than returning null. A trail with nothing left in it stands on the home screen.
 */
internal fun restoredTrail(names: List<String>): List<AppScreen> =
    names.mapNotNull { name -> AppScreen.entries.firstOrNull { it.name == name } }
        .ifEmpty { listOf(AppScreen.HOME) }

/**
 * The route a window starts on. A fresh one takes its intent's, unless Recents brought the intent
 * back: once the window a widget opened has been finished — "Close the app", a crash — the system
 * hands the task's first intent over again, flagged as launched from history, and the water dialog
 * asked for days ago would open a second time. A rebuilt window takes [savedRoute], the one the window
 * before it had not followed yet, if any.
 */
internal fun startingRoute(restored: Boolean, savedRoute: String?, launchFlags: Int, intentRoute: String?): String? =
    when {
        restored -> savedRoute
        (launchFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0 -> null
        else -> intentRoute
    }

/**
 * Whether the prompt that is up is the one letting the app in, which [shouldRelock] spares a stop
 * for. Not every prompt is: the profile asks the lock's own question of an open app before the lock
 * or the file's encryption is taken off, and a window left from under that one — Home pressed over
 * it — would stay open behind it, for whoever picks the phone up next.
 */
internal fun unlockUnderway(prompting: Boolean, unlocked: Boolean): Boolean = prompting && !unlocked

/** The folder DoctorReportPdf and DoctorReportArchive write a shared report into, under the cache. */
private const val SHARED_REPORT_DIR = "reports"

/**
 * The files a share left in the cache an hour ago or longer, removed whenever the app comes
 * forward. A shared report, route or workout template has to be a real file, and the read grant
 * handed over with it outlives this process, so none of them can be deleted as it is sent;
 * [sweepExportLeftovers] empties the same folders, but only on a fresh start, and the app can go
 * weeks without one. A sheet naming medicines and blood tests, or an archive with the scans
 * attached to it, would lie there in the clear all that while — outside the database's encryption, when
 * that is on. An hour is [IN_FLIGHT_STALE_MS], the age a file in the cache is already taken for
 * done with: the app it was handed to has read it by then. Only files go; a walk's emptied folder
 * waits for the fresh start. Errors are swallowed for the reason they are there: a cache the
 * system has already cleared is not a failure.
 */
internal fun sweepStaleShares(cacheDir: File, now: Long) {
    listOf(SHARED_REPORT_DIR, ROUTE_SHARE_DIR, ROUTINE_SHARE_DIR).forEach { name ->
        runCatching {
            File(cacheDir, name).walkBottomUp()
                .filter { it.isFile && it.lastModified() < now - IN_FLIGHT_STALE_MS }
                .forEach { it.delete() }
        }
    }
}

/**
 * The tabs nothing is ever pulled off to show the page underneath, so no picture is kept of what
 * they were opened over. Add is not one of them: the meal flow's last step falls away onto it.
 */
private val OPAQUE_TABS = setOf(AppScreen.HOME, AppScreen.MEDICINE, AppScreen.CALENDAR, AppScreen.PROFILE)

/** Pages off the bar that stand as a tab does, rather than being held open over another as panels. */
private val STANDING_PAGES = setOf(AppScreen.ROUTINES)

/** The five places the app stands, drawn as pages rather than held open over another as panels. */
private val PRIMARY_TABS =
    setOf(AppScreen.HOME, AppScreen.MEDICINE, AppScreen.ADD, AppScreen.CALENDAR, AppScreen.PROFILE)

/** The five stops in the order the bar draws them, which is the order a tab change travels in. */
private val BAR_ORDER =
    listOf(AppScreen.HOME, AppScreen.MEDICINE, AppScreen.ADD, AppScreen.CALENDAR, AppScreen.PROFILE)

/** A tab arriving from the side starts out the page's width divided by this, a tenth, away. */
private const val TAB_SLIDE_SHARE = 10

/**
 * One tab handing over to the next: the old page fades out as the new one fades in, the way
 * Material moves between top-level places, and the new one drifts a short way in from the side of
 * the bar it was reached towards — so the move itself says which way along the bar the app went. It
 * plays once per change and settles; nothing about it repeats.
 *
 * Both pages are composed for as long as the hand-over lasts, which is why the fades run on the
 * quick effects spring and the drift is only a tenth of the width.
 */
@Composable
private fun TabTransition(tab: AppScreen, content: @Composable (AppScreen) -> Unit) {
    val mirrored = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            // A page off the bar is a step forward from the tab it was opened from, and back from it a step back.
            val step = BAR_ORDER.indexOf(targetState).let { if (it < 0) BAR_ORDER.size else it }
                .compareTo(BAR_ORDER.indexOf(initialState).let { if (it < 0) BAR_ORDER.size else it }) * mirrored
            (fadeIn(PwnMotion.effects()) + slideInHorizontally(PwnMotion.spatial()) { it * step / TAB_SLIDE_SHARE })
                .togetherWith(fadeOut(PwnMotion.effects()))
        },
        label = "tab",
    ) { shown -> content(shown) }
}

/**
 * What a page has to keep clear at its foot for the floating bar, or 0 where no bar is up.
 *
 * The bar is drawn over the page rather than beside it, so nothing is reserved for it by the
 * layout: a screen whose content reaches the bottom of the window pads itself by this, and a
 * screen that ends well short of the bar ignores it. Read it on the scrolling container's own
 * modifier, under any scroll, so the padding scrolls with the content instead of cropping it.
 */
internal val LocalBottomBarInset = compositionLocalOf { 0.dp }

/**
 * What the person said about their own allergies, for the leaves that need it.
 *
 * Ambient rather than a parameter because the readers are food rows four screens deep — the
 * catalogue list, the search results, the photograph's own picker — and none of them has any other
 * reason to know about the profile. It defaults to [AllergyAnswer.Unanswered], which is what a
 * preview or a test that provides nothing should see: no warnings, no claims.
 */
internal val LocalAllergyAnswer = compositionLocalOf<AllergyAnswer> { AllergyAnswer.Unanswered }

@Composable
private fun PwnBottomBar(
    current: AppScreen,
    onSensors: (() -> Unit)?,
    onNavigate: (AppScreen) -> Unit,
    onReserve: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Material 3 navigation bar, tall variant, carried on a floating pill rather than a band
    // ruled across the foot of the window: surfaceContainer at elevation level 2, no outline, five
    // destinations each with an icon over a labelMedium label. The page runs on underneath the pill
    // and out either side; what must not end up beneath it keeps [onReserve]'s height clear.
    val shape = RoundedCornerShape(BAR_CORNER)
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val locales = LocalConfiguration.current.locales
    // Whether every label fits its stop. A label that has to be cut — a long word at a large font
    // scale in a narrow window — switches the bar to Material's selected-label-only arrangement,
    // and the chosen stop widens to carry its word whole. Latched until the scale, width or locale
    // changes, so hiding the labels cannot flip it straight back.
    var labelsFit by remember(density.fontScale, window.width, locales) {
        mutableStateOf(true)
    }
    val stop: @Composable RowScope.(String, Int, AppScreen, Dp, Boolean, (() -> Unit)?) -> Unit =
        { label, art, destination, glyph, accented, longPress ->
            Destination(
                label = label,
                art = art,
                destination = destination,
                current = current,
                onNavigate = onNavigate,
                labelsFit = labelsFit,
                onLabelCut = { labelsFit = false },
                glyph = glyph,
                accented = accented,
                onLongPress = longPress,
            )
        }
    Surface(
        // Clear of the navigation bar, and of a camera cutout on the side edge in landscape, which
        // the navigation bar's own inset does not include.
        modifier = modifier.windowInsetsPadding(
            WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        )
            .widthIn(max = BAR_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = BAR_SIDE, vertical = BAR_FLOAT)
            // Labels grow with the font scale and the pill grows with them, so pages are told the
            // height it actually took rather than the one it was designed at.
            .onSizeChanged { with(density) { onReserve(it.height.toDp() + BAR_FLOAT * 2) } }
            .dropShadow(shape, BAR_SHADOW),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = BAR_HEIGHT)
                .height(IntrinsicSize.Min)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The live sensor view's way in is a long press on the home stop, handed in only by
            // a debuggable build.
            stop(stringResource(R.string.nav_home), R.drawable.ic_art_home, AppScreen.HOME, BAR_GLYPH, false, onSensors)
            stop(
                stringResource(R.string.nav_medicine),
                R.drawable.ic_art_doctor,
                AppScreen.MEDICINE,
                BAR_GLYPH,
                false,
                null,
            )
            // The one that writes something down rather than one more place to look at: the same
            // stop as the others, its disc a little larger and always in the accent, the way a
            // primary action is drawn filled. Being on it is still said by the indicator and label.
            stop(stringResource(R.string.nav_add), R.drawable.ic_art_plus, AppScreen.ADD, ADD_GLYPH, true, null)
            stop(
                stringResource(R.string.nav_calendar),
                R.drawable.ic_art_calendar,
                AppScreen.CALENDAR,
                BAR_GLYPH,
                false,
                null,
            )
            stop(
                stringResource(R.string.nav_profile),
                R.drawable.ic_art_profile,
                AppScreen.PROFILE,
                BAR_GLYPH,
                false,
                null,
            )
        }
    }
}

/** The air either side of the pill. */
private val BAR_SIDE = 12.dp

/** In a wide window the pill stays a pill: five stops do not need the width of a tablet. */
private val BAR_MAX_WIDTH = 560.dp

/** The air under the pill and over it, which is what makes it stand on the page rather than end it. */
private val BAR_FLOAT = 8.dp

/** Material 3's tall navigation bar: indicator, gap and label with 12dp above and 16dp below. */
private val BAR_HEIGHT = 80.dp

/** Half that, so the pill's ends are round rather than merely soft. */
private val BAR_CORNER = BAR_HEIGHT / 2

/**
 * A short, close shadow along the pill's edge, lifted a dp so it reads mostly above it. The pill is
 * barely darker than the page, and the app's usual pale elevation shadow spreads too thin to
 * separate them; this one stays within a few dp and is kept at night too, where that one is off.
 */
internal val BAR_SHADOW = Shadow(radius = 6.dp, color = Color(0x3321304D), offset = DpOffset(0.dp, (-1).dp))

/**
 * How much of a page's foot the pill stands over at the default font scale, handed down as
 * [LocalBottomBarInset] until the bar has been measured.
 *
 * The system navigation inset is deliberately not in it. Scaffold's own `insets` already pads the
 * page by that, and the pill wears it too, so counting it here would push every page a second
 * navigation bar's height up.
 */
private val BAR_RESERVE = BAR_HEIGHT + BAR_FLOAT * 2

/** Material's active indicator: a full-radius pill behind the icon. */
private val INDICATOR_WIDTH = 56.dp
private val INDICATOR_HEIGHT = 32.dp

/**
 * How big a stop's drawing is. The duotone set inks about three quarters of its square, so 28dp
 * lands roughly the 21dp of ink a 24dp Material icon carries.
 */
private val BAR_GLYPH = 28.dp

/** The add stop's disc fills the indicator's height. */
private val ADD_GLYPH = 32.dp

/** How much wider than the others the chosen stop grows once only its label is shown. */
private const val SELECTED_ONLY_WEIGHT = 2f

/**
 * One destination: icon over label, with Material's active indicator behind the icon.
 *
 * Which stop you are on is said three ways, because the indicator alone — secondaryContainer on
 * surfaceContainer — is a tonal step, not a 3:1 edge: the pill, the drawing turning from the
 * neutral onSurfaceVariant set to the accent set, and the label turning primary and bold. Every
 * drawing clears 3:1 against what it sits on in either state (DesignContrastTest). The whole
 * column is the target; the pressed, focused and hovered state layers are drawn on the indicator,
 * where Material puts them. The indicator widens out of its centre on the spatial spring.
 *
 * When [labelsFit] is false only the chosen stop shows its label, and that stop widens to hold it.
 * [accented] keeps the drawing in the accent set whether or not the stop is chosen.
 * [onLongPress] is how a developer build reaches a screen a shipping one has no room to offer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Destination(
    label: String,
    art: Int,
    destination: AppScreen,
    current: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    labelsFit: Boolean,
    onLabelCut: () -> Unit,
    glyph: Dp,
    accented: Boolean,
    onLongPress: (() -> Unit)?,
) {
    val selected = current == destination
    val widened by animateFloatAsState(
        if (selected && !labelsFit) SELECTED_ONLY_WEIGHT else 1f,
        PwnMotion.spatial(),
        label = "stop width",
    )
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val reveal by animateFloatAsState(if (selected) 1f else 0f, PwnMotion.spatial(), label = "indicator")
    val icons = if (selected || accented) LocalAccentIconColors.current else LocalThemeIconColors.current
    Column(
        Modifier.weight(widened).fillMaxHeight()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongPress,
                onClick = { onNavigate(destination) },
            )
            // Even with the label on screen, the drawing, the pill and the text are one stop: a
            // screen reader hears its name, that it is a tab, and whether it is the chosen one.
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
            }
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(INDICATOR_WIDTH, INDICATOR_HEIGHT), contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = reveal }
                    .background(scheme.secondaryContainer, CircleShape),
            )
            Box(Modifier.matchParentSize().clip(CircleShape).indication(interaction, ripple()))
            CompositionLocalProvider(LocalThemeIconColors provides icons) {
                Image(themePainterResource(art), contentDescription = null, modifier = Modifier.size(glyph))
            }
        }
        if (labelsFit || selected) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // labelSmall is the floor: a long word steps down once before it counts as cut.
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                onTextLayout = { if (labelsFit && it.hasVisualOverflow) onLabelCut() },
            )
        }
    }
}

@Composable
internal fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
