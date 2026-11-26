package com.pwnall.health

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val WORK_FROM = ActionParameters.Key<String>("workFrom")
private val WORK_TO = ActionParameters.Key<String>("workTo")

/** Two 48dp targets and the 10dp frame either side of them: the narrowest face, see the info xml. */
private val WORK_COMPACT = DpSize(116.dp, 48.dp)

/**
 * Wide enough for the figures beside the buttons. The frame, the two targets and the gap take
 * 124dp, and "since 09:14 · 3 h 10" at 12sp is about 120dp of glyphs, so the column needs the rest
 * of 250dp to print it whole in English — a longer locale is cut at the end of the line, never
 * pushed over a button.
 */
private val WORK_WIDE = DpSize(250.dp, 48.dp)

/** How long the confirmation stands over the widget before the buttons come back. */
private const val WORK_WIDGET_FLASH_MS = 1_800L

/**
 * Start, pause and stop a work timer from the home screen, acting in place: the tap is the action,
 * and the face that replaces the buttons for a moment — a tick and one word — is the confirmation.
 * Nothing opens, nothing asks, and there is no toast (a background toast always lands at the foot of
 * the screen, where nobody tapping a widget is looking).
 *
 * The session and today's rows are read inside the composition, never before it. Glance keeps a
 * session alive for about 45 seconds and an update inside that window only swaps its state: a value
 * read before [provideContent] stays whatever it was when the session began, and a second tap would
 * be drawn from the first one's figures.
 *
 * No chronometer: a ticking view redraws the launcher every second for a number glanced at once an
 * hour. The wide face prints when the session began, which is never wrong, and the net time as it
 * stood when the face was last drawn.
 */
class WorkWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WORK_COMPACT, WORK_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        // The dao is a handle, not a value: what it is asked is asked inside the composition.
        val dao = StepsDb.get(app).dao()
        provideContent {
            val initial = remember { WorkClock.snapshot(app) }
            val clock by remember { WorkClock.flow(app) }.collectAsState(initial)
            val today = LocalDate.now()
            // Yesterday too: a session that began before midnight counts partly to today.
            val rows by remember(today) {
                dao.journalOfKindBetween(KIND_WORK, today.minusDays(1).toString(), today.toString())
            }.collectAsState(emptyList())
            WorkFace(app, clock, rows, today, System.currentTimeMillis())
        }
    }
}

class WorkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WorkWidget()
}

/**
 * Every tap on the widget: one transition, named by the state it leaves and the state it enters,
 * never a toggle. Then the flash, for as long as it takes to read, and the buttons back.
 */
class WorkTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val from = parameters[WORK_FROM]?.let(::workStateOf) ?: return
        val to = parameters[WORK_TO]?.let(::workStateOf) ?: return
        val step = WorkClock.act(context, from, to, System.currentTimeMillis())
        if (!step.moved) {
            // A stale face or a bounce: drawn again from the stored state, since a face whose
            // redraw was lost would otherwise refuse every tap until the half-hourly update.
            refreshWorkWidget(context)
            return
        }
        // Counted from now, not from the tap: a Stop on a cold process can take longer than the
        // flash to write its row, and a deadline already past would never show the flash at all.
        val until = System.currentTimeMillis() + WORK_WIDGET_FLASH_MS
        WorkClock.showFlash(context, workFlashWord(context.resources, from, to, step), until)
        // The face has no buttons while the flash stands, so a process killed before the redraw
        // below would leave it without any until the launcher's half-hourly update. An alarm redraws
        // it too: the stored flash has run out by then, and the buttons come back.
        WidgetClock.redrawAt(context, KIND_WORK, until)
        refreshWorkWidget(context)
        delay(WORK_WIDGET_FLASH_MS)
        WorkClock.clearFlash(context, until)
        refreshWorkWidget(context)
    }
}

private fun workStateOf(name: String): WorkState? = WorkState.entries.firstOrNull { it.name == name }

/** Redraws the work widget alone, guarded the way [refreshWidgets] guards each of its own. */
internal suspend fun refreshWorkWidget(context: Context) {
    runCatching { WorkWidget().updateAll(context) }
        .onFailure { if (it is CancellationException) throw it }
}

@Composable
private fun WorkFace(
    context: Context,
    clock: WorkClockState,
    rows: List<JournalEntry>,
    today: LocalDate,
    now: Long,
) {
    val session = clock.session
    val flash = clock.flash?.takeIf { clock.flashUntil > now }
    val wide = LocalSize.current.width >= WORK_WIDE.width
    GlanceTheme {
        // The radius and frame every widget root in the app uses.
        Row(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(WIDGET_CORNER)
                .padding(WIDGET_FRAME),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            when {
                // No targets while it stands, so the flash is also the widget's bounce guard.
                flash != null -> {
                    Image(ImageProvider(R.drawable.ic_art_done), contentDescription = null, GlanceModifier.size(28.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(flash, style = workValueStyle(), maxLines = 2)
                }

                wide -> {
                    WorkFigures(context, session, rows, today, now, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    PrimaryButton(context, session.state)
                    Spacer(GlanceModifier.width(8.dp))
                    StopButton(context, session.state)
                }

                // Two cells: each button centred in half the width, so a wider placement spaces
                // them out instead of pushing them together at one end.
                else -> {
                    Cell { PrimaryButton(context, session.state) }
                    Cell { StopButton(context, session.state) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The state word, when the session began and its net time so far, and today's total. The whole
 * column opens the Work screen; the buttons beside it keep acting in place.
 */
@Composable
private fun WorkFigures(
    context: Context,
    session: WorkSession,
    rows: List<JournalEntry>,
    today: LocalDate,
    now: Long,
    modifier: GlanceModifier,
) {
    val resources = context.resources
    val zone = ZoneId.systemDefault()
    val todayMinutes = workMinutesByDay(rows, zone, session.liveSegments(now))[today] ?: 0.0
    Column(modifier.clickable(actionStartActivity(openAppIntent(context, WidgetRoute.WORK)))) {
        Text(resources.getString(workStateWord(session.state)), style = workValueStyle(), maxLines = 1)
        if (session.state != WorkState.IDLE) {
            val started = Instant.ofEpochMilli(session.sessionStart).atZone(zone).toLocalTime()
            Text(
                resources.getString(
                    R.string.work_widget_since,
                    clockText(started.hour * 60 + started.minute),
                    workShortDuration(resources, (session.liveSegments(now).sumOf { it.length } / 60_000L).toInt()),
                ),
                style = workCaptionStyle(),
                maxLines = 1,
            )
        }
        Text(
            resources.getString(R.string.work_today, workShortDuration(resources, todayMinutes.toInt())),
            style = workCaptionStyle(),
            maxLines = 1,
        )
    }
}

/**
 * Start, Pause or Resume — one place, the glyph and its container saying which. Running fills the
 * button with the primary container and paused with the secondary one, so the state reads from
 * across the room without a word.
 */
@Composable
private fun PrimaryButton(context: Context, state: WorkState) {
    val colors = GlanceTheme.colors
    when (state) {
        WorkState.IDLE -> WorkButton(
            R.drawable.ic_work_play,
            context.getString(R.string.work_start),
            colors.surface,
            colors.onSurface,
            workTap(WorkState.IDLE, WorkState.RUNNING),
        )

        WorkState.RUNNING -> WorkButton(
            R.drawable.ic_work_pause,
            context.getString(R.string.work_pause),
            colors.primaryContainer,
            colors.onPrimaryContainer,
            workTap(WorkState.RUNNING, WorkState.PAUSED),
        )

        WorkState.PAUSED -> WorkButton(
            R.drawable.ic_work_play,
            context.getString(R.string.work_resume),
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            workTap(WorkState.PAUSED, WorkState.RUNNING),
        )
    }
}

/**
 * Always drawn, so nothing jumps sideways when a session starts. With nothing running it has no
 * container, no action and no description: a dimmed glyph that a screen reader passes over, drawn
 * at Material's disabled opacity so it does not read as a button that can be pressed.
 */
@Composable
private fun StopButton(context: Context, state: WorkState) {
    val colors = GlanceTheme.colors
    if (state == WorkState.IDLE) {
        WorkButton(R.drawable.ic_work_stop, null, null, colors.onSurfaceVariant, null, alpha = DISABLED_GLYPH_ALPHA)
    } else {
        WorkButton(
            R.drawable.ic_work_stop,
            context.getString(R.string.work_stop),
            colors.surface,
            colors.onSurface,
            workTap(state, WorkState.IDLE),
        )
    }
}

private fun workTap(from: WorkState, to: WorkState): Action =
    actionRunCallback<WorkTapAction>(actionParametersOf(WORK_FROM to from.name, WORK_TO to to.name))

/**
 * One 48dp target, the MultiWidget slot's shape. The size is the target: Glance sets the click on
 * the image view itself, and the 12dp inside it is what leaves the 24dp glyph its margin.
 */
@Composable
private fun WorkButton(
    icon: Int,
    description: String?,
    background: ColorProvider?,
    tint: ColorProvider,
    action: Action?,
    alpha: Float = 1f,
) {
    var modifier = GlanceModifier.size(48.dp).cornerRadius(14.dp)
    background?.let { modifier = modifier.background(it) }
    action?.let { modifier = modifier.clickable(it) }
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        alpha = alpha,
        modifier = modifier.padding(12.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** Material's opacity for the content of a control that cannot be used. */
private const val DISABLED_GLYPH_ALPHA = 0.38f

@Composable
private fun workValueStyle() = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    color = GlanceTheme.colors.onSurface,
)

@Composable
private fun workCaptionStyle() = TextStyle(
    fontSize = 12.sp,
    color = GlanceTheme.colors.onSurfaceVariant,
)
