package com.pwnall.health

import android.content.Context
import android.database.SQLException
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Only foreground time counts, including an app left open through midnight on any screen. */
@Composable
internal fun RecordAppOpenDays() {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            recordOpenDays({ LocalDate.now() }) { day ->
                withContext(Dispatchers.IO) {
                    StepsDb.get(context).dao().recordAppOpen(AppOpenDay(day.toString()))
                }
            }
        }
    }
}

/**
 * The loop [RecordAppOpenDays] runs while the app is in front, with the clock and the write handed
 * in: each date [today] reads is recorded once, and a date that changes while the app stays open —
 * across midnight, on any screen — is recorded on the next round, within [OPEN_DAY_ROUND_MS].
 */
internal suspend fun recordOpenDays(today: () -> LocalDate, record: suspend (LocalDate) -> Unit) {
    var recorded: LocalDate? = null
    while (true) {
        val day = today()
        if (recorded != day) {
            // A full disk or a locked file is no reason to take the app down with it, at every
            // start, over a day on a badge: the day is asked for again on the next round.
            try {
                record(day)
                recorded = day
            } catch (failed: SQLException) {
                Log.w("Levels", "the day the app was opened was not recorded", failed)
            }
        }
        delay(OPEN_DAY_ROUND_MS)
    }
}

/** How often a foreground app looks at the date again. */
internal const val OPEN_DAY_ROUND_MS = 30_000L

/**
 * The last figure this process worked out, so the badge opens on it rather than on nothing.
 *
 * The counting is a database read and a fold over a year of rows, which takes a frame or two — and
 * the home screen is rebuilt every time the tab is left and come back to, so a count started again
 * from its zero each time would run the ring visibly up from LVL 0 on every visit. Held here it is
 * one value for the process, replaced only by a count of all four tables ([annualXpFlow]); a level
 * that has genuinely changed still arrives, one recomposition later.
 */
private val lastAnnualXp = mutableStateOf(AnnualXp())

/**
 * The year's count as the four tables behind it change: one figure for each answer they give
 * together, and none before the last of them has answered.
 *
 * Each table collected on its own from an empty list would finish the count over four empty lists
 * before the database had said anything: a zero on the badge and in [lastAnnualXp], then partial
 * sums as the tables came in one by one — the very run-up from LVL 0 the held value exists to spare
 * a returning visit.
 */
internal fun annualXpFlow(
    today: LocalDate,
    zone: ZoneId,
    days: Flow<List<DaySteps>>,
    journal: Flow<List<JournalEntry>>,
    visits: Flow<List<Appointment>>,
    opens: Flow<List<AppOpenDay>>,
): Flow<AnnualXp> = combine(days, journal, visits, opens) { stepDays, entries, appointments, openDays ->
    annualXp(today, stepDays, entries, appointments, openDays, zone)
}

@Composable
internal fun rememberAnnualXp(today: LocalDate): AnnualXp {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val zone = ZoneId.systemDefault()
    val counts = remember(dao, today, zone) {
        val start = today.withDayOfYear(1).toString()
        val counted = annualXpFlow(
            today,
            zone,
            dao.daysSince(start),
            dao.journalBetween(start, today.toString()),
            dao.appointments(),
            dao.appOpenDays(),
        )
        // The fold over a year of rows stays off the main thread.
        counted.flowOn(Dispatchers.Default).onEach { lastAnnualXp.value = it }
    }
    val xp by counts.collectAsStateWithLifecycle(lastAnnualXp.value)
    return xp
}

@Composable
internal fun LevelBadge(xp: AnnualXp, onOpen: () -> Unit) {
    val progress = xp.progress
    // A floor rather than a size: at a large font scale the two lines outgrow 58dp, and the
    // badge grows with them, square, instead of cutting the number off.
    Box(
        Modifier.squareAtLeast(58.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.level_details), onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.matchParentSize().padding(2.dp),
            color = PwnIndigo,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 3.dp,
        )
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.level_short),
                style = MaterialTheme.typography.labelSmall,
                color = PwnMuted,
            )
            Text(
                progress.level.toLong().formatted(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The level on a page of its own: what it stands at, what the year has paid for it, and the six
 * kinds of record that pay. A page rather than a dialog: a dialog could show the same figures but
 * not the standing of them — a dialog is an interruption, and a year's progress is something to
 * look at.
 *
 * No rules are printed under the figures: every row already names what it counts.
 */
@Composable
internal fun LevelsScreen(modifier: Modifier, onBack: () -> Unit) {
    val xp = rememberAnnualXp(rememberToday())
    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.level_screen_title), onBack)
        Column(
            Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = LocalBottomBarInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LevelSummaryCard(xp)
            LevelSourcesCard(xp)
        }
    }
}

/** The disc and the bar under it: where the level stands and how far into the next one it is. */
@Composable
private fun LevelSummaryCard(xp: AnnualXp) {
    val progress = xp.progress
    val maxed = progress.level == MAX_USER_LEVEL
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LevelDisc(progress.level)
            Text(
                stringResource(R.string.level_title, progress.level),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.level_year_xp, xp.total.formatted()),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (maxed) {
                        stringResource(R.string.level_max)
                    } else {
                        stringResource(
                            R.string.level_xp_of,
                            progress.earned.formatted(),
                            progress.required.formatted(),
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (!maxed) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.level_to_next),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                color = PwnIndigo,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/**
 * A square as large as its content needs and never smaller than [minimum]: a badge that grows with
 * the font scale instead of clipping its number, without claiming the width it is offered.
 */
private fun Modifier.squareAtLeast(minimum: Dp) = layout { measurable, constraints ->
    val side = maxOf(
        minimum.roundToPx(),
        measurable.maxIntrinsicWidth(constraints.maxHeight),
        measurable.maxIntrinsicHeight(constraints.maxWidth),
    ).coerceAtMost(minOf(constraints.maxWidth, constraints.maxHeight))
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}

/** The whole figure the page is about, lit from behind so the number is the brightest thing on it. */
@Composable
private fun LevelDisc(level: Int) {
    Box(Modifier.size(172.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(PwnIndigo.copy(alpha = 0.22f), Color.Transparent)),
                CircleShape,
            ),
        )
        Box(
            Modifier.squareAtLeast(104.dp).background(PwnIndigoSoft, CircleShape).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                level.toLong().formatted(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = PwnIndigoDark,
            )
        }
        // Decoration, and read as such: they are unlabelled, so a screen reader passes them by.
        val context = LocalContext.current
        val animated = remember(context) { context.animationsOn() }
        repeat(STAR_COUNT) { index ->
            Star(index, animated, level)
        }
    }
}

/**
 * Every place a star may light: a ring around the disc, wobbling in and out so it does not read as
 * a drawn circle, and nowhere near the middle, which belongs to the number.
 */
private val STAR_SPOTS = listOf(
    BiasAlignment(0.14f, -0.73f), BiasAlignment(0.51f, -0.78f), BiasAlignment(0.66f, -0.45f),
    BiasAlignment(0.95f, -0.19f), BiasAlignment(0.70f, 0.14f), BiasAlignment(0.73f, 0.49f),
    BiasAlignment(0.44f, 0.65f), BiasAlignment(0.19f, 0.93f), BiasAlignment(-0.15f, 0.75f),
    BiasAlignment(-0.50f, 0.76f), BiasAlignment(-0.69f, 0.46f), BiasAlignment(-0.94f, 0.19f),
    BiasAlignment(-0.72f, -0.14f), BiasAlignment(-0.72f, -0.47f), BiasAlignment(-0.44f, -0.65f),
    BiasAlignment(-0.18f, -0.90f),
)

/**
 * How many are alight at once, and how big each is.
 *
 * Star `i` draws only from spots `i`, `i + 4`, `i + 8`… — every fourth place on the ring. That is
 * the whole of the collision handling: two stars can never choose the same spot, without any shared
 * state between them saying which places are taken, and each still ranges right around the disc.
 */
private const val STAR_COUNT = 4
private val STAR_SIZES = listOf(27.dp, 21.dp, 31.dp, 18.dp)

/**
 * One star: it lights and goes out once somewhere on the ring, then lights again somewhere else
 * and stays lit, still, for as long as the page is open.
 *
 * Every duration is drawn afresh, and so is each place. Fixed timings would give four lights pulsing
 * in lockstep, which reads as one mechanism blinking rather than as a sky.
 *
 * For as long as the page is open — an endless twinkle is fine for this purely decorative
 * light (it carries no figure). With animations switched off on the phone the stars stand lit and
 * still, so nothing moves that the person cannot stop.
 */
@Composable
private fun BoxScope.Star(index: Int, animated: Boolean, level: Int) {
    val spots = remember(index) { STAR_SPOTS.filterIndexed { spot, _ -> spot % STAR_COUNT == index } }
    val glow = remember { Animatable(if (animated) 0f else 1f) }
    // Standing still, the four would otherwise crowd into four neighbouring places on the ring.
    var at by remember(index) { mutableIntStateOf(index % spots.size) }
    LaunchedEffect(animated, level) {
        if (!animated) return@LaunchedEffect
        glow.snapTo(0f)
        delay(Random.nextLong(STAR_FIRST_LIGHT_MS))
        while (true) {
            at = nextSpot(at, spots.size)
            glow.animateTo(1f, tween(Random.nextInt(STAR_IN_MS.first, STAR_IN_MS.last)))
            delay(Random.nextLong(STAR_LIT_MS.first, STAR_LIT_MS.last))
            glow.animateTo(0f, tween(Random.nextInt(STAR_OUT_MS.first, STAR_OUT_MS.last)))
            delay(Random.nextLong(STAR_DARK_MS.first, STAR_DARK_MS.last))
        }
    }
    val colour = PwnPurple
    Canvas(
        // graphicsLayer rather than Modifier.alpha: the fade is then a re-composite of a layer
        // already drawn, so an open page does not rebuild its tree twenty times a second. The
        // swell from just over half size is what makes the light arrive rather than switch on.
        Modifier.align(spots[at]).size(STAR_SIZES[index]).graphicsLayer {
            alpha = glow.value
            scaleX = 0.55f + 0.45f * glow.value
            scaleY = scaleX
        },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Four arms with a waist between them. Each curve bends toward the middle, but its control
        // point stops short of it — put the control exactly on the centre and all four arms pinch
        // away to hairlines that read as dirt on the screen rather than as a star.
        val bx = cx * STAR_WAIST
        val by = cy * STAR_WAIST
        val star = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx + bx, cy - by, size.width, cy)
            quadraticTo(cx + bx, cy + by, cx, size.height)
            quadraticTo(cx - bx, cy + by, 0f, cy)
            quadraticTo(cx - bx, cy - by, cx, 0f)
            close()
        }
        drawPath(star, colour)
    }
}

/**
 * Any spot but the one a star is standing on, and it moves while it is dark: a star that slid
 * between its places would be a trail, not a light going on somewhere else.
 */
private fun nextSpot(at: Int, spots: Int): Int = (at + 1 + Random.nextInt(spots - 1)) % spots

/** Enough spread that the four do not all arrive together on the first frame of the page. */
private const val STAR_FIRST_LIGHT_MS = 700L

/** How far the curves stop short of the centre. Lower is a thinner star; zero is a hairline. */
private const val STAR_WAIST = 0.19f

private val STAR_IN_MS = 500..800
private val STAR_LIT_MS = 300L..700L
private val STAR_OUT_MS = 500..800
private val STAR_DARK_MS = 200L..500L

/**
 * Whether this phone wants to see movement at all. A person who has turned animations off in
 * developer options or in accessibility gets the stars standing still and lit from the start, and
 * the symptom chat's three dots standing still too.
 */
internal fun Context.animationsOn(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** What earned the year's XP, one row per kind, in the order the rules below list them. */
@Composable
private fun LevelSourcesCard(xp: AnnualXp) {
    val sources = listOf(
        Triple(R.string.level_opens, R.drawable.ic_art_home, xp.opens),
        Triple(R.string.level_steps, R.drawable.ic_art_steps, xp.steps),
        Triple(R.string.level_meals, R.drawable.ic_art_food, xp.meals),
        Triple(R.string.level_workouts, R.drawable.ic_art_sport, xp.workouts),
        Triple(R.string.level_visits, R.drawable.ic_art_doctor, xp.visits),
        Triple(R.string.level_medals, R.drawable.ic_art_trophy, xp.medals),
    )
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            Text(
                stringResource(R.string.level_sources),
                Modifier.padding(horizontal = 20.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            sources.forEachIndexed { index, (label, art, value) ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 76.dp, end = 20.dp), color = PwnBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(themePainterResource(art), null, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.level_xp_value, value.formatted()),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PwnMuted,
                    )
                }
            }
        }
    }
}
