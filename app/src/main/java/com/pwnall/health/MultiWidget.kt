package com.pwnall.health

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.RowScope
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.ceil
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Row as GlanceRow
import androidx.glance.layout.fillMaxHeight as glanceFillMaxHeight
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.padding as glancePadding
import androidx.glance.text.FontWeight as GlanceFontWeight
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextStyle as GlanceTextStyle

/** A compact launcher widget containing one to six configurable entry shortcuts. */
class MultiWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideReading(
            read = { WidgetSlots.slots(context, widgetId, offeredWidgetSlots(dao.profileNow(), LocalDate.now())) },
        ) { slots ->
            GlanceTheme {
                val size = LocalSize.current
                val width = size.width.value - 2 * WIDGET_FRAME.value
                val height = size.height.value - 2 * WIDGET_FRAME.value
                val columns = multiWidgetColumns(width, height, slots.size)
                val rows = ceil(slots.size / columns.toFloat()).toInt().coerceAtLeast(1)
                // The icons give up size rather than being cut off once the cells get small; the
                // cell round each one stays the tap target whatever the icon is drawn at.
                val icon = minOf(48f, width / columns - 6f, height / rows - 6f).coerceAtLeast(24f).dp
                GlanceColumn(
                    GlanceModifier.glanceFillMaxSize().background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(WIDGET_CORNER).glancePadding(WIDGET_FRAME),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    slots.chunked(columns).forEach { row ->
                        // The rows share the height, so a cell reaches as far as its neighbours'
                        // and no strip between two rows is a tap that lands on nothing.
                        GlanceRow(
                            GlanceModifier.glanceFillMaxWidth().defaultWeight(),
                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        ) {
                            row.forEach { slot -> SlotButton(context, slot, icon) }
                        }
                    }
                }
            }
        }
    }
}

/** The pitch a slot is laid out at: the 48dp icon and the 3dp its cell keeps on either side. */
private const val SLOT_PITCH_DP = 54f

/**
 * How many columns [slots] shortcuts are laid out in, in a widget whose content is [width] by
 * [height] dp.
 *
 * As many columns at [SLOT_PITCH_DP] as the width takes, while the rows that leaves still fit the
 * height at the same pitch. Once they do not — a launcher lets a six-icon widget shrink to one cell —
 * the grid is the one whose cells keep the largest square, and of two that tie the one with the
 * larger cells: each cell is its slot's whole tap target. Counting rows at the full pitch would put
 * a widget a little short of two full rows into one row of six slivers, though it has the height for
 * two rows of wider cells.
 */
internal fun multiWidgetColumns(width: Float, height: Float, slots: Int): Int {
    val count = slots.coerceIn(1, WIDGET_SLOT_COUNT)
    val across = (width / SLOT_PITCH_DP).toInt().coerceAtLeast(1)
    val down = (height / SLOT_PITCH_DP).toInt().coerceAtLeast(1)
    if (across * down >= count) return across.coerceAtMost(WIDGET_SLOT_COUNT)
    fun cellWidth(columns: Int) = width / columns
    fun cellHeight(columns: Int) = height / ceil(count / columns.toFloat())
    return (count downTo 1).maxWith(
        compareBy<Int>({ minOf(cellWidth(it), cellHeight(it)) }, { cellWidth(it) * cellHeight(it) }),
    )
}

@Composable
private fun RowScope.SlotButton(context: Context, slot: WidgetSlot, icon: Dp) {
    // The whole cell takes the tap, not only the icon drawn in the middle of it: the icon shrinks
    // with the widget, and the margin round it would otherwise be a miss.
    androidx.glance.layout.Box(
        GlanceModifier.defaultWeight().glanceFillMaxHeight()
            .clickable(actionStartActivity(openAppIntent(context, slot.route)))
            .glancePadding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.glance.Image(
            provider = androidx.glance.ImageProvider(slot.iconRes),
            contentDescription = context.getString(slot.labelRes),
            modifier = GlanceModifier.width(icon).height(icon)
                .cornerRadius(14.dp).background(GlanceTheme.colors.surface)
                .glancePadding(6.dp),
        )
    }
}

class MultiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MultiWidget()

    /** A removed widget takes its configured row with it. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetSlots.forget(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

// --- one tap, one cigarette ----------------------------------------------------------------------

/**
 * The whole widget is the tap. A person who smokes and is counting it will not open an app for
 * each one, and a count that is only kept when it is convenient is not a count.
 */
class SmokingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = StepsDb.get(context).dao()
        provideReading(
            read = { cigarettesIn(dao.journalOnNow(LocalDate.now().toString())) },
        ) { cigarettes ->
            GlanceTheme {
                // The same radius and frame the other widget roots use.
                GlanceColumn(
                    GlanceModifier
                        .glanceFillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(WIDGET_CORNER)
                        .glancePadding(WIDGET_FRAME)
                        .clickable(actionRunCallback<LogCigaretteAction>()),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    GlanceText(
                        context.getString(R.string.widget_smoking_add),
                        style = GlanceTextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = GlanceFontWeight.Bold,
                        ),
                    )
                    GlanceText(
                        context.getString(R.string.widget_smoking_today, cigarettes.toLong().formatted()),
                        style = GlanceTextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                    )
                }
            }
        }
    }
}

class SmokingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmokingWidget()
}

class LogCigaretteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // logSmoking redraws the widgets a cigarette shows on once it has committed, as logWater does
        // for a glass; no redraw of every widget follows the tap here.
        logWidgetCigarette(context)
    }
}

/**
 * The cigarettes of [journal]: its rows under a cigarette's grams, which is what the button above
 * the count writes, and no product shares them. Counted as every tobacco row's grams over a
 * cigarette's, a medium hookah logged in the app would stand as fifteen under "+ Cigarette".
 */
internal fun cigarettesIn(journal: List<JournalEntry>): Int =
    journal.count { it.kind == KIND_SMOKING && it.servingG == TobaccoProduct.CIGARETTE.tobaccoG }

// --- choosing the bottom row ---------------------------------------------------------------------

/**
 * The configuration screen the launcher opens when this widget is placed.
 *
 * One to six positions, saved independently for each widget.
 * Cancelling leaves the default row rather than no widget: a person who dismissed a dialog still
 * wanted the widget they just dragged out.
 */
class MultiWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like every other activity: the system bars take their icon colours from the app's page.
        enableEdgeToEdge()
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // No id means there is no widget to configure, and RESULT_OK would tell the launcher to
            // place one anyway; the row would be saved under a key nothing ever reads back.
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        // The profile decides which slots are on offer, and reading it suspends; everything after
        // this point waits for that one query.
        lifecycleScope.launch {
            // Asked for off the main thread, as MainActivity asks: opening an encrypted record is the
            // Keystore, the file's header and a preferences write, and on the start after the
            // encryption switch was moved it rewrites the whole file first.
            val db = withContext(Dispatchers.IO) { StepsDb.get(applicationContext) }
            val profile = db.dao().profileNow()
            val offered = offeredWidgetSlots(profile, LocalDate.now())
            val initial = WidgetSlots.slots(this@MultiWidgetConfigActivity, widgetId, offered)
            setContent {
                PwnFitTheme(darkTheme = isSystemInDarkTheme()) {
                    SlotPicker(initial = initial, offered = offered) { chosen ->
                        WidgetSlots.save(this@MultiWidgetConfigActivity, widgetId, chosen)
                        // The redraw has to outlive this activity — a widget with a configuration
                        // screen gets no first APPWIDGET_UPDATE of its own — so it holds the
                        // application context, and nothing it throws may reach the default handler.
                        // Through refreshWidgets, which is what makes a face the launcher has
                        // already asked for read the row again; updateAll alone does not.
                        val app = applicationContext
                        MainScope().launch { runCatching { refreshWidgets(app) } }
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotPicker(
    initial: List<WidgetSlot>,
    offered: List<WidgetSlot>,
    onDone: (List<WidgetSlot>) -> Unit,
) {
    // Saved by name so a half-picked row survives the rotation that recreates the activity.
    var chosen by rememberSaveable(
        initial,
        stateSaver = listSaver(
            save = { row -> row.map(WidgetSlot::name) },
            restore = { names -> names.map(WidgetSlot::valueOf) },
        ),
    ) { mutableStateOf(initial) }
    // The palette's own ground under the page, and with it the ink every Text here inherits. The
    // window behind this activity is the platform's, dark or light as the system is, and a Text with
    // no Surface over it is drawn in Material's default black whatever that window is.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            // No Scaffold here to keep the title out from under the status bar, so the inset is
            // taken by hand.
            Modifier.fillMaxSize().safeDrawingPadding().panelVerticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.widget_config_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.widget_config_body), color = PwnMuted)
            Text(stringResource(R.string.widget_icon_count), style = MaterialTheme.typography.titleSmall)
            // One answer to each of these rows, so each is a group of radio buttons to a screen
            // reader rather than a row of boxes to tick.
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (count in 1..WIDGET_SLOT_COUNT) {
                    PwnFilterChip(
                        selected = chosen.size == count,
                        onClick = {
                            chosen = List(count) { index -> chosen.getOrNull(index) ?: offered[index % offered.size] }
                        },
                        label = count.toString(),
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            chosen.forEachIndexed { position, current ->
                Text(
                    stringResource(R.string.widget_config_position, position + 1),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.forEach { slot ->
                        PwnFilterChip(
                            selected = slot == current,
                            onClick = {
                                chosen = chosen.toMutableList().also { it[position] = slot }
                            },
                            label = stringResource(slot.labelRes),
                            modifier = Modifier.semantics { role = Role.RadioButton },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onDone(chosen) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_save))
            }
        }
    }
}
