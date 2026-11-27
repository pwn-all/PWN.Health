package com.pwnall.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Home-screen surfaces. Each one is either a number worth seeing without opening anything, or an
 * action worth taking without opening anything — a widget that only launches the app is a shortcut
 * with extra steps.
 *
 * Water and mood write straight to the database from the widget and redraw themselves. Food opens
 * the editor already on the food form, because the point of the shortcut is skipping the chooser.
 */
internal object WidgetRoute {
    const val EXTRA = "com.pwnall.health.OPEN"
    const val FOOD = "FOOD"
    const val MOOD = "MOOD"
    const val EXERCISE = "EXERCISE"
    const val TRACK = "TRACK"
    const val WATER = "WATER"
    const val CAFFEINE = "CAFFEINE"
    const val BATHROOM = "BATHROOM"
    const val SMOKING = "SMOKING"
    const val ALCOHOL = "ALCOHOL"
    const val INTIMATE = "INTIMATE"
    const val CYCLE = "CYCLE"
    const val WORK = "WORK"

    /** The screen-time reminders' tap: the page whose figures they quote. */
    const val SCREEN_TIME = "SCREEN_TIME"

    /** The level widget's tap: the levels page it quotes. */
    const val LEVELS = "LEVELS"

    /** The dose widget's tap: the medicine tab, where the day's doses are. */
    const val MEDICINE = "MEDICINE"

    /** The quick-entry widget's breathing icon: the practice itself. */
    const val BREATHING = "BREATHING"
}

private val PORTION_KEY = ActionParameters.Key<Int>("millilitres")
private val MOOD_KEY = ActionParameters.Key<Int>("moodScore")

/**
 * The corner every widget root is rounded to and the frame inside it, named once so that no root
 * drifts to a value of its own.
 */
internal val WIDGET_CORNER = PwnCorners.large
internal val WIDGET_FRAME = 10.dp

/**
 * Every way into the app from outside it — widgets, notifications, the alarm screen's Open — and
 * nothing else names MainActivity. It is the launcher's own intent: Android brings a running task
 * to the front as it stands only for an intent shaped like the one the task was started with, and
 * stacks a second MainActivity over the first for any other, as a bare intent would on every tap.
 * Extras and flags are not part of the shape, so the route rides along. MainActivity still sends
 * away a second window that gets through (a widget's tap carries a data URI of Glance's own), but
 * one never drawn is better than one sent away.
 *
 * SINGLE_TOP hands the intent to a MainActivity already in front through onNewIntent, keeping its
 * trail. A route also closes what the app has open over itself ([clearTop]), so that it reaches the
 * one MainActivity. Without one nothing is closed: a walk camera closed answers that no shot was
 * taken and throws the shot's file away, and a save picker closed saves nothing.
 */
internal fun openAppIntent(context: Context, route: String? = null, clearTop: Boolean = route != null): Intent =
    Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                if (clearTop) Intent.FLAG_ACTIVITY_CLEAR_TOP else 0,
        )
        .apply { route?.let { putExtra(WidgetRoute.EXTRA, it) } }

/**
 * Switches the tobacco widget's provider off for a profile the app does not offer tobacco to, and
 * the cycle widget's off for a profile without a cycle.
 *
 * Hiding them inside the app would not be hiding them: a widget provider is listed by the launcher
 * straight out of the manifest, so a twelve-year-old picking widgets would still be shown a
 * one-tap cigarette counter for an app that will not let them near one, and a man a cycle day.
 * Disabling the component is how a manifest entry stops existing, and it is reversible — the day
 * the profile is old enough, or says otherwise, the next resume puts it back.
 *
 * Called from onResume rather than from wherever the profile is written, because that is where the
 * app already has the profile in hand. A profile restored from a backup lands after that resume —
 * the import runs on in a coroutine of its own, and its password door opens no picker to come back
 * from — so the import follows it there and then ([followProfile]), with the row it has just written.
 */
fun applyHabitWidgetVisibility(context: Context, profile: UserProfile?) {
    setWidgetOffered(context, SmokingWidgetReceiver::class.java, habitsOffered(profile?.birthDate, LocalDate.now()))
    setWidgetOffered(context, CycleWidgetReceiver::class.java, profile?.sex == "FEMALE")
}

private fun setWidgetOffered(context: Context, receiver: Class<*>, offered: Boolean) {
    val wanted = if (offered) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    val component = ComponentName(context, receiver)
    val packages = context.packageManager
    // Asked before it is set: setComponentEnabledSetting is a cross-process write that the system
    // logs, and this runs on every resume.
    if (packages.getComponentEnabledSetting(component) != wanted) {
        packages.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
    }
}

/** How many times [refreshWidgets] has been called: what a face already drawn reads again on. */
private val widgetRefreshes = MutableStateFlow(0)

/**
 * Draws a widget from what [read] returns, and reads again at every [refreshWidgets] for as long as
 * the face stands.
 *
 * Glance runs provideGlance once for a session, keeps the session for about 45 seconds — longer
 * while updates keep arriving — and answers an update inside that time by composing the content it
 * already has. A figure read before provideContent stays what it was when the session began: a
 * second glass of water tapped ten seconds after the first would leave the first one's total
 * standing, and a route stopped while the service redraws its widget every five seconds would keep
 * the recording face and its Stop. So the reading is taken inside the composition, the way
 * [WorkWidget] takes its own.
 *
 * The first reading still comes before the content, so the first frame is never an empty one, and
 * the count is taken before that reading, so a write landing between the two is read again rather
 * than missed. Off the main thread, which is where a session composes: a walk asks for a refresh
 * every five seconds.
 */
internal suspend fun <T> GlanceAppWidget.provideReading(
    read: suspend () -> T,
    content: @androidx.compose.runtime.Composable (T) -> Unit,
): Nothing {
    val asOf = widgetRefreshes.value
    val first = withContext(Dispatchers.Default) { read() }
    provideContent {
        val refreshes by widgetRefreshes.collectAsState()
        val reading by produceState(first, refreshes) {
            if (refreshes != asOf) value = withContext(Dispatchers.Default) { read() }
        }
        content(reading)
    }
}

/**
 * Redraws the widgets a change can show: every one of them when [kind] is unknown, or the few that
 * read rows of that kind. Called after anything that changes what they display.
 *
 * The count goes up first and whatever the kind: updateAll starts a session for a widget that has
 * none, and does nothing a face already in a session can see; that face reads again on the count
 * ([provideReading]).
 */
internal suspend fun refreshWidgets(context: Context, kind: String? = null) {
    // Midnight changes every "today" with no write behind it, so it is asked for by name ([WidgetClock]).
    WidgetClock.armMidnight(context)
    widgetRefreshes.update { it + 1 }
    for (widget in widgetsShowing(kind)) {
        // Guarded one at a time rather than all of them together: a widget that cannot render must
        // not leave the rest of the home screen showing yesterday. Cancellation is the exception that
        // has to travel — swallowing it would carry on updating widgets for a caller that is gone.
        runCatching { widget.updateAll(context) }
            .onFailure { if (it is CancellationException) throw it }
    }
}

/**
 * The widgets a journal row of [kind] can appear on. The steps, week and walk widgets read the
 * pedometer and the open route and never the journal, so a journal write leaves them alone, and so
 * does a tap on the water widget or the tobacco one, which name their row's kind too.
 *
 * No row reaches the multi-slot widget either. Its face is shortcuts, chosen on its own screen from
 * what the profile offers ([offeredWidgetSlots]), and no journal write changes one. A redraw of
 * every widget, the one its own screen and midnight ask for, still reaches it.
 *
 * The water widget's target is the latest weight, so a weighing redraws it too, as it does the
 * weight widget; a night's sleep is a measurement row as well. A meal or an activity moves the day's
 * energy and the year's XP; a dose marked, a period logged, each redraws its own.
 *
 * The pedometer and the route recorder name what they changed as well ([WIDGETS_OF_STEPS],
 * [WIDGETS_OF_ROUTE]). Asked with no kind, at most once in five seconds, StepService would make up
 * to 720 rounds an hour while a walk records, each of them fourteen classes asked for the one or
 * the four that have anything new to show. Steps move the day's count, the week's bars, the day's
 * energy and the year's XP; the open route is the walk widget's alone.
 */
internal fun widgetsShowing(kind: String?): List<GlanceAppWidget> = when (kind) {
    null -> everyWidget()
    WIDGETS_OF_STEPS -> listOf(StepsWidget(), WeekWidget(), EnergyWidget(), LevelWidget())
    WIDGETS_OF_ROUTE -> listOf(TrackWidget())
    KIND_WATER, KIND_CAFFEINE -> listOf(WaterWidget())
    KIND_MEASUREMENT -> listOf(WaterWidget(), WeightWidget(), SleepWidget())
    KIND_EAT, KIND_ACTIVITY -> listOf(EnergyWidget(), LevelWidget())
    KIND_MOOD -> listOf(MoodWidget())
    KIND_SMOKING -> listOf(SmokingWidget())
    KIND_WORK -> listOf(WorkWidget())
    KIND_MED -> listOf(DoseWidget())
    KIND_CYCLE, KIND_CYCLE_END, KIND_CYCLE_LOG -> listOf(CycleWidget())
    else -> emptyList()
}

/**
 * What the pedometer and the route recorder hand [refreshWidgets] where a journal write hands its
 * row's kind: steps were written, or the open route changed. No journal row carries either.
 */
internal const val WIDGETS_OF_STEPS = "widgets:steps"
internal const val WIDGETS_OF_ROUTE = "widgets:route"

private fun everyWidget(): List<GlanceAppWidget> = listOf(
    StepsWidget(),
    WaterWidget(),
    MoodWidget(),
    MultiWidget(),
    SmokingWidget(),
    TrackWidget(),
    WorkWidget(),
    SleepWidget(),
    WeightWidget(),
    EnergyWidget(),
    WeekWidget(),
    LevelWidget(),
    DoseWidget(),
    CycleWidget(),
)

// --- steps -------------------------------------------------------------------------------------

class StepsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = { (dao.stepsOn(LocalDate.now().toString()) ?: 0L) to (dao.profileNow()?.dailyStepGoal ?: 0L) },
        ) { (steps, goal) ->
            WidgetFrame(onClick = openAppIntent(context)) {
                Text(steps.formatted(), style = valueStyle())
                Text(
                    context.getString(R.string.widget_steps_of, goal.formatted()),
                    style = captionStyle(),
                )
            }
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}

// --- water -------------------------------------------------------------------------------------

class WaterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StepsDb.get(context)
        provideReading(
            read = {
                val millilitres = db.dao().journalOnNow(LocalDate.now().toString())
                    .filter { it.kind == KIND_WATER }
                    .sumOf { it.servingG }
                    .toInt()
                val profile = db.dao().profileNow()
                val weight = db.dao().latestMeasuredWeightKg().first() ?: profile?.weightKg ?: 0.0
                // The same line the home screen's dialog prints, in the same unit the profile reads
                // in — and the buttons under it are offered in that unit too, or the caption counts
                // ounces towards a target while every button beneath it is marked in millilitres.
                Triple(millilitres, waterTargetMl(weight), profile?.imperial == true)
            },
        ) { (millilitres, target, imperial) ->
            WidgetFrame {
                Text(waterOfTargetText(context.resources, millilitres, target, imperial), style = captionStyle())
                // Four portions in one row. At 20sp with 10dp of side padding each label would
                // want 65.3dp, so the four would want 261.4dp — 273.2dp in fluid ounces — inside
                // the 205-215dp a three-cell widget actually offers, and a Glance Row is a
                // RemoteViews LinearLayout with wrap_content children: what does not fit is
                // cropped rather than wrapped, and this Row is centred, so the overflow would be
                // split between its ends and "+100" and "+500" each cut in half. At 16sp
                // with 4dp of side padding the four measure 177.1dp (186.6dp imperial), and
                // defaultWeight() hands out what is left in equal shares, so narrowing the widget
                // takes the same few dp off every button instead of eating the last one whole;
                // centring is what keeps each label in the middle of the share it is given.
                // The vertical padding is the tap target itself — Glance sets the padding on the
                // very view that carries the pending intent — so 18.75dp of 16sp line box plus
                // 15dp above and below is a 48.75dp target against Android's 48dp minimum.
                Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    WATER_PORTIONS_ML.forEach { portion ->
                        Text(
                            formatVolumeAdd(context.resources, portion, imperial),
                            style = valueStyle(size = 16, align = TextAlign.Center),
                            maxLines = 1,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .padding(horizontal = 4.dp, vertical = 15.dp)
                                .clickable(
                                    actionRunCallback<LogWaterAction>(
                                        actionParametersOf(PORTION_KEY to portion),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

class LogWaterAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val millilitres = parameters[PORTION_KEY] ?: return
        // logWater redraws the widgets a glass can change once it has committed, whoever logged the
        // glass, so nothing is redrawn here: a redraw of every widget would ask fourteen classes again
        // for the one already drawn.
        StepsDb.get(context).logWater(waterEntry(context.resources, millilitres, LocalDate.now().toString()))
    }
}

// --- mood --------------------------------------------------------------------------------------

class MoodWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StepsDb.get(context)
        provideReading(
            read = {
                val now = System.currentTimeMillis()
                // The question logMood itself asks, of the last four hours and not of today. Asked
                // of today's rows, a mood logged late in the evening was gone at midnight: the faces
                // came back, and a tap on one was refused without a word until the pause ran out.
                // To the minute, which is all the face prints: to the millisecond, every reading
                // would be a new face to send the launcher.
                val last = db.dao().lastMoodNear(now)
                // Nothing is written when the pause runs out, so the waiting face asks to be drawn
                // again at that moment rather than at the next half-hourly update.
                moodPauseEndsAt(last, now)?.let { WidgetClock.redrawAt(context, KIND_MOOD, it) }
                moodWidgetWaitMinutes(last, now)
            },
        ) { minutes ->
            WidgetFrame(onClick = openAppIntent(context, WidgetRoute.MOOD)) {
                if (minutes != null) {
                    Text(
                        context.getString(
                            R.string.mood_cooldown,
                            (minutes / 60).toInt(),
                            (minutes % 60).toInt(),
                        ),
                        style = captionStyle(),
                    )
                } else {
                    Text(context.getString(R.string.mood_question), style = captionStyle())
                    Row(
                        GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    ) {
                        // The padding is the whole tap target: these are RemoteViews TextViews,
                        // and nothing in Glance inherits Material's 48dp minimum the way a
                        // Composable does. With five faces abutting in a full-width row, a face
                        // under the minimum would let a miss of a few dp log the neighbouring
                        // score, and the four-hour cooldown would then hold the wrong one on
                        // screen. Thirteen and twelve dp round the emoji give 48 x 49.8dp: the
                        // 22sp emoji is ~22dp wide and 25.8dp tall. Five 48dp faces need 240dp of
                        // content, which is why mood_widget_info.xml declares 260dp of width — the
                        // frame spends 10dp a side — rather than letting a resize below that crop
                        // the last face.
                        // Each face is read out as the app's own word for that mood, not as the
                        // emoji's name: the words are what the mood screen and the journal use.
                        Mood.entries.forEach { mood ->
                            Text(
                                mood.emoji,
                                style = valueStyle(size = 22),
                                modifier = GlanceModifier
                                    .semantics { contentDescription = context.getString(mood.titleRes) }
                                    .padding(horizontal = 13.dp, vertical = 12.dp)
                                    .clickable(
                                        actionRunCallback<LogMoodAction>(
                                            actionParametersOf(MOOD_KEY to mood.score),
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

class MoodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoodWidget()
}

/**
 * The minutes the mood widget's waiting face counts down, or null when a mood may be logged and the
 * faces are shown. Rounded up, as the mood page rounds them: rounded down, the last minute of the
 * pause would read "0h 00m" on a widget that still took no mood.
 */
internal fun moodWidgetWaitMinutes(lastMoodAtMs: Long?, nowMs: Long): Long? =
    moodCooldownLeftMs(lastMoodAtMs, nowMs).takeIf { it > 0L }?.let { (it + 59_999L) / 60_000L }

class LogMoodAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val mood = Mood.fromScore(parameters[MOOD_KEY]) ?: return
        val db = StepsDb.get(context)
        // The same row the mood page writes, through the same factory. A mood logged redraws the widgets
        // itself once it has committed; a tap the pause refused redraws them here, for the faces of a
        // widget drawn before the mood that started it to give way to the wait.
        if (!db.logMood(moodEntry(context::getString, mood, null, LocalDate.now()))) refreshWidgets(context, KIND_MOOD)
    }
}

// --- quick add ---------------------------------------------------------------------------------

class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetFrame {
                Row(
                    GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    QuickAction(context.getString(R.string.add_meal), openAppIntent(context, WidgetRoute.FOOD))
                    QuickAction(context.getString(R.string.mood_title), openAppIntent(context, WidgetRoute.MOOD))
                    QuickAction(context.getString(R.string.water_title), openAppIntent(context, WidgetRoute.WATER))
                }
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

// --- shared chrome ------------------------------------------------------------------------------

/**
 * One label, one destination — and the padding around it is the target, because Glance emits it as
 * setViewPadding on the same TextView that takes the pending intent.
 *
 * These three labels are the whole of QuickAddWidget: its frame is given no intent, so a miss lands
 * on nothing at all. At 12sp the line box is 14.1dp, so 17dp above and below makes a 48.1dp target
 * against Android's 48dp minimum. Sideways, 12dp carries the shortest English label ("Meal", 26.7dp
 * of glyphs) to 50.7dp.
 */
@androidx.compose.runtime.Composable
private fun QuickAction(label: String, intent: Intent) {
    Text(
        label,
        style = captionStyle(),
        modifier = GlanceModifier
            .padding(horizontal = 12.dp, vertical = 17.dp)
            .clickable(androidx.glance.appwidget.action.actionStartActivity(intent)),
    )
}

// --- track -------------------------------------------------------------------------------------

/**
 * A route being recorded, and the two controls worth having on a home screen for it.
 *
 * Both faces act: one starts a recording, the other stops it. Starting is allowed, and the
 * reasoning that says otherwise is a trap worth naming. A widget tap *is* on both of the platform's
 * exemption lists — the Android 12 foreground-service background-start
 * list ("the user performs an action on a UI element related to your app… widget") and the separate
 * while-in-use list ("the service starts by interacting with app widgets"). ACCESS_BACKGROUND_LOCATION
 * is only what would upgrade that grant to all-the-time access, and a recording does not need it.
 *
 * What the start does *not* do is go straight from the Glance callback to the service. That
 * callback is a broadcast, and the grant reaches it only as a background-start token with a
 * ten-second life — and no open-source location tracker ships that path, so it has no track record
 * on the OEM ROMs that rewrite this part of the framework. Where it fails, it fails silently: a
 * track created, a notification that flashes, and no points at all. So starting goes through
 * [TrackStartActivity], a window-less activity that starts the service and finishes — the shape
 * Glance itself falls back to, and the shape OpenTracks and the Fossify recorder use for the same
 * reason. The failure to design against here is starvation, not an exception.
 *
 * Live figures cost little: StepService asks this widget to redraw while it records, at most every
 * five seconds, updateTrack writes the distance as each fix lands, and a face already drawn reads
 * the route again each time it is asked ([provideReading]). Without that, the figures would stand as
 * they were when its Glance session began, and move about once a minute.
 *
 * A paused route keeps the recording face, Stop and all, under a title of its own, and its clock
 * stands still. A paused route stores no fix, so the service redraws the widgets itself as a pause
 * begins and ends.
 */
class TrackWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = {
                val active = dao.activeTrackNow()
                // The moment the clock is read against goes with a route in hand, and makes each of
                // its readings a new one to draw; the resting face has no clock, and is drawn once.
                val now = if (active == null) 0L else System.currentTimeMillis()
                // The screen prints this distance in the profile's units; a widget saying km over a
                // card saying mi would be the same number in two languages.
                Triple(active, dao.profileNow()?.imperial ?: false, now)
            },
        ) { (active, imperial, now) ->
            if (active == null) {
                // The second line is a promise, so it has to be the true one. With location granted
                // the tap records straight away; without it the same tap can only go and ask, and
                // saying "one tap" there would be a lie the first time it matters.
                val ready = context.canRecordRoute()
                WidgetFrame(onClick = Intent(context, TrackStartActivity::class.java)) {
                    Text(context.getString(R.string.widget_track_idle), style = valueStyle(size = 18))
                    Text(
                        context.getString(
                            if (ready) R.string.widget_track_idle_ready else R.string.widget_track_idle_hint,
                        ),
                        style = captionStyle(),
                    )
                }
                return@provideReading
            }
            GlanceTheme {
                Row(
                    GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(WIDGET_CORNER)
                        .padding(WIDGET_FRAME),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text(
                            context.getString(trackWidgetTitle(active)),
                            style = valueStyle(size = 18),
                        )
                        Text(
                            context.getString(
                                R.string.widget_track_figures,
                                formatDistance(context.resources, active.distanceMeters, imperial),
                                trackWidgetClock(active, now),
                            ),
                            style = captionStyle(),
                        )
                    }
                    // While a route records, this label is the widget's only tap target: the Row
                    // around it carries no click of its own, unlike the idle face's WidgetFrame.
                    // Roboto puts 18.75dp of line box under 16sp, so 15dp above and below makes a
                    // 48.75dp target against Android's 48dp minimum. Width is not the tight axis —
                    // "Stop" is 64dp across, and the longest of the sixteen shipped locales 100dp.
                    // The taller row is why track_widget_info.xml declares 70dp of height for the
                    // 68.8dp this face measures.
                    Text(
                        context.getString(R.string.widget_track_stop),
                        style = valueStyle(size = 16),
                        modifier = GlanceModifier
                            .padding(horizontal = 14.dp, vertical = 15.dp)
                            .clickable(actionRunCallback<StopTrackAction>()),
                    )
                }
            }
        }
    }
}

class TrackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackWidget()
}

/**
 * Stops the recording the widget is showing.
 *
 * This one may stay a plain callback. The service is already in the foreground and has already
 * claimed its types, so nothing here is a background *start*: the reason that a granted foreground
 * reason is never recomputed is the same reason a second startForeground() on a running service
 * cannot be refused.
 *
 * The service is told and the database is asked nothing. The token that carries a widget's
 * privilege into a broadcast expires about ten seconds after the tap, so a Room open in front of
 * the intent spends exactly the budget that matters — and there is nothing to ask anyway: stopTrack
 * finds the row itself when the service has no id in hand, and a stop that arrives with no
 * recording running settles nothing and breaks nothing.
 *
 * Nor is the widget redrawn from here. Its recording face is the open row, and the row is still open
 * when this returns — a redraw from the tap would find it so and paint the face it was meant to take
 * down. The service redraws it once endTrack has written.
 *
 * One route cannot be stopped that way: the one a restart or a reboot left open on a phone that
 * has not granted the step permission. No service is in the foreground for it, and one started for
 * the stop would have no type to declare ([stopGoesToService]), which the platform turns into a
 * crash of the app. Its row is closed here, as stopTrack would close it, and the widget is redrawn
 * after.
 */
class StopTrackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val android14 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (stopGoesToService(StepService.inForeground, android14, context.hasStepPermission())) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StepService::class.java).setAction(StepService.ACTION_STOP_TRACK),
            )
            return
        }
        val dao = StepsDb.get(context).dao()
        dao.activeTrackNow()?.let { dao.endTrack(it.id, System.currentTimeMillis()) }
        refreshWidgets(context, WIDGETS_OF_ROUTE)
    }
}

/** The recording face's title: the route records, or stands paused. */
@StringRes
internal fun trackWidgetTitle(track: TrackEntity): Int =
    if (track.pausedAt == null) R.string.widget_track_recording else R.string.widget_track_paused

/**
 * The recording face's clock, H:MM:SS once an hour is up and MM:SS before that — the way a stopwatch
 * is read. It counts the time walked, as the walk page does, so it leaves every pause out and stands
 * still while the route is paused.
 */
internal fun trackWidgetClock(track: TrackEntity, now: Long): String =
    (track.movingMillis(now) / 1_000L).toInt().elapsedClockText()

/** The frame every figure widget stands in: the launcher's ground, the app's corner, and one tap. */
@androidx.compose.runtime.Composable
internal fun WidgetFrame(
    onClick: Intent? = null,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    GlanceTheme {
        val base = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WIDGET_CORNER)
            .padding(WIDGET_FRAME)
        Column(
            modifier = onClick?.let {
                base.clickable(androidx.glance.appwidget.action.actionStartActivity(it))
            } ?: base,
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) { content() }
    }
}

/**
 * The figure style. [align] is only spent where a caller has given its text a weighted share of a
 * row rather than a wrap_content box, since inside such a share the default start alignment leaves
 * the label pinned to the left of a cell wider than itself.
 */
@androidx.compose.runtime.Composable
internal fun valueStyle(size: Int = 28, align: TextAlign? = null) = TextStyle(
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    color = GlanceTheme.colors.onSurface,
    textAlign = align,
)

@androidx.compose.runtime.Composable
internal fun captionStyle() = TextStyle(
    fontSize = 12.sp,
    color = GlanceTheme.colors.onSurfaceVariant,
)
