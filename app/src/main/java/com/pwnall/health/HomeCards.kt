package com.pwnall.health

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The three things the home screen leads with, in the order the carousel holds them: steps in the
 * middle, food the page after it and the burn the page before — calories on the left of the steps
 * and food on their right.
 *
 * The navigation saves the open one by name rather than as the enum itself, the way it saves its
 * trail of screens: a build that drops one of these must not crash on state written by the build
 * before it.
 */
enum class HomeMetric { STEPS, FOOD, BURN }

/** A day has twenty-four of them, and every chart on these cards is one bar per hour. */
private const val HOURS_IN_DAY = 24

/** Below this share of the tallest hour a bar is background rather than one of the day's peaks. */
private const val PEAK_SHARE = 0.6f

/**
 * How far a quiet hour's bar is worn thin beside the solid peaks. No thinner: every card tint
 * still has to clear 3:1 against the card at this opacity, in both themes, because those bars are
 * the only place the shape of the day is drawn (HomeChartContrastTest).
 */
internal const val QUIET_BAR_ALPHA = 0.72f

/** An hour with nothing in it: a stub that marks the hour without claiming anything happened. */
private const val STUB_ALPHA = 0.28f

/**
 * How long the progress line takes to run round to the figure it is reporting, and with it the
 * ring on the page the card opens.
 *
 * At half this the line would arrive before the eye had found the card it was drawn on, which is a
 * line nobody ever sees move.
 */
private const val PROGRESS_MS = 1800

/**
 * Where the progress line starts a run from: nothing, on the swipe that lands on its card, and
 * where it is [drawn] already once it has [landed] and only the figure under it has moved.
 */
internal fun progressLineStart(landed: Boolean, drawn: Float): Float = if (landed) drawn else 0f

/** How long a card's goal glow stays up before it settles away. */
private const val CELEBRATION_HOLD_MS = 800L

/**
 * The neighbouring cards: drawn at this much of the middle one, in the share of the width the
 * middle card gives up on each side for them, with this much air between a card and the next.
 *
 * Stepped back by size alone, at full opacity. A faded layer would take its muted goal line and stat
 * label down with it, under the contrast text owes — on cards that are still a tap away from
 * opening.
 */
private const val ASIDE_SCALE = 0.76f

/** Fit the neighbouring summary into the visible strip of its card. */
private const val ASIDE_FIT = 0.9f

// The centre card occupies 68.8% of the viewport. Its neighbours still show a strip either side —
// that strip is what says the cards go on — but the card being read gets the room instead of the
// two nobody is looking at.
private const val ASIDE_INSET = 0.156f
private val ASIDE_GAP = 8.dp

/**
 * The card's own corner, read from the shape token [PwnCard] is drawn with, so the fine outline and
 * the inset progress track stay concentric with the edge the card is clipped to.
 */
private val CARD_RADIUS = PwnCorners.extraLarge

private val PROGRESS_STROKE = 4.dp
private val PROGRESS_INSET = 6.dp

private val RING_STROKE = 14.dp

/** The largest the detail page's ring is drawn, whatever the width of the pane. */
private val RING_MAX_SIDE = 320.dp

/** And the most of the window's height it may take, for a window that is wide but short. */
private const val RING_WINDOW_SHARE = 0.6f

/**
 * One card's worth of the day: what it counts, what it is aiming at, and the shape of its hours.
 *
 * The card and the page it opens onto are both drawn from this, so the two can never disagree
 * about the number — which is what a carousel of summaries and a separate set of detail pages
 * would otherwise drift into.
 */
internal data class MetricFace(
    val metric: HomeMetric,
    val art: Int,
    val tint: Color,
    val title: String,
    /** Today's figure, already formatted: "7,432", "1,320". */
    val value: String,
    /** The same for the goal — the card writes the slash itself. */
    val goal: String,
    val progress: Float,
    /** The line under the goal: the walk's distance, the meal's macros. */
    val detail: String,
    /** Twenty-four buckets, midnight to midnight. */
    val hours: List<Float>,
    /** What the chart of those buckets is called: they are not all activity. */
    val chartTitle: String,
    /** What one of those buckets is counted in, for the label over the fullest of them. */
    val unit: String,
    /**
     * True where the buckets cannot add up to the figure on the card, so the chart says so.
     *
     * `hourly_steps` is filled by this phone's pedometer alone — Health Connect and a Bluetooth
     * band both report a day as one total with no time inside it, and the card's own figure is the
     * largest of the three. Without this the chart draws a quiet morning under a headline of
     * fourteen thousand steps and looks broken rather than partial.
     */
    val hoursPartial: Boolean,
    /**
     * A figure and the word for it, set along the foot of the card. The card shows the first two; the
     * page it opens has the width for all three.
     */
    val stats: List<Pair<String, String>>,
)

/**
 * The day as the home screen reads it: the three cards, and the energy budget under them.
 *
 * The budget travels with the cards rather than being computed again on the page a card opens,
 * because it is the same day either way and two readings of it that disagree would be a bug
 * nobody could see until the numbers were compared side by side.
 */
internal data class HomeToday(
    val faces: List<MetricFace>,
    /** Today's rows, handed on so the screen around the cards does not open a second collector. */
    val journal: List<JournalEntry>,
    val budget: EnergyBudget,
    val eatenKcal: Double,
    val movedKcal: Double,
    val standbyKcal: Double,
    val restingForecast: Double,
    val walkingKcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val motion: StepsMotion,
    val nutritionAdvice: List<Advice>,
)

/**
 * The day, read once and cut three ways.
 *
 * The carousel and the page a card opens both call this, so both are collecting the same queries
 * out of the same database — Room hands two collectors of one query the same rows, and the two
 * screens stay in step without either passing the other anything.
 */
@Composable
internal fun rememberHomeToday(profile: UserProfile): HomeToday {
    val context = LocalContext.current
    val resources = LocalResources.current
    val dao = remember { StepsDb.get(context).dao() }
    val todayDate = rememberToday()
    val today = remember(todayDate) { todayDate.toString() }
    // Resting burn accrues with the clock, not with the journal. Without a tick of its own the
    // "at rest" figure freezes at whatever time the screen was opened and stays there. It ticks only
    // while the screen can be seen: the step service keeps the process, and a stopped page with it.
    // Each start ticks at once: otherwise, back from the background, the figures would stand still
    // since the stop and go on showing that for another minute.
    var minuteTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                minuteTick++
                delay(60_000L)
            }
        }
    }
    val day by remember(today) { dao.day(today) }.collectAsStateWithLifecycle(null)
    val journal by remember(today) { dao.journalOn(today) }.collectAsStateWithLifecycle(emptyList())
    val hourly by remember(today) { dao.hoursOn(today) }.collectAsStateWithLifecycle(emptyList())

    val tracks by remember { dao.tracks() }.collectAsStateWithLifecycle(emptyList())

    val steps = day?.steps ?: 0
    val activities = journal.filter { it.kind == KIND_ACTIVITY }
    val food = journal.filter { it.kind == KIND_EAT }
    val eatenKcal = food.sumOf { it.calories }
    val budget = remember(profile, todayDate) { energyBudget(profile, todayDate.year) }
    val burn = remember(profile, eatenKcal, budget) { burnGoal(profile, eatenKcal, budget.targetKcal) }
    val walkKcal = stepCalories(steps, profile.weightKg, profile.heightCm)
    // Not a plain sum: a logged walk and the pedometer both saw the same outing.
    val movedKcal = movedCalories(activities, walkKcal)
    val walkedKcal = walkingShareOfMoved(activities, walkKcal)
    // todayDate is a key as well, or the card holds yesterday's near-full standby burn for up to a
    // minute after midnight while every other number on it has already turned over.
    val standbyKcal = remember(profile, minuteTick, todayDate) {
        standbyCaloriesSoFar(profile, LocalDateTime.now())
    }
    val motion = remember(steps, hourly, tracks, journal, profile.heightCm, todayDate, minuteTick) {
        dayStepsMotion(steps, hourly, tracks, journal, profile.heightCm, todayDate, System.currentTimeMillis())
    }
    val walkingClock = (if (motion.estimated) "≈ " else "") +
        formatActivityClock(resources, (motion.seconds / 60.0).roundToInt())
    val activityMinutes = activities.sumOf { it.durationMinutes ?: 0.0 }

    val stepHours = remember(hourly) { hourlySteps(hourly) }
    // Walking energy has no hourly table of its own, so it is the step curve priced by the formula
    // the day's total uses. These bars are the shape of the day, not a second claim about it.
    val burnHours = remember(stepHours, profile) {
        stepHours.map { stepCalories(it.roundToInt().toLong(), profile.weightKg, profile.heightCm).toFloat() }
    }
    val foodHours = remember(food) { hourlyKcal(food) }

    val goalLabel = stringResource(R.string.home_stat_goal)
    // Logged workouts, and named for that: a strength set carries no minutes and a walk carries
    // none either, so this is the time somebody wrote down rather than the time they moved.
    val workoutLabel = stringResource(R.string.home_stat_workouts)
    val activityChart = stringResource(R.string.home_hourly)
    // Health Connect and a band report a day as one number; only this phone's pedometer fills the
    // hours. Where the day's figure is one of theirs the bars cannot reach it, and the chart says so.
    val phoneHours = stepHours.sum()
    val stepsPartial = phoneHours + 1f < steps
    val burnPartial = stepsPartial || activities.isNotEmpty()
    val walked = formatDistance(resources, motion.meters, profile.imperial)
    val activityClock = formatActivityClock(resources, activityMinutes.roundToInt())
    val stepShare = share(steps.toDouble(), profile.dailyStepGoal.toDouble())
    val totalBurnGoal = restingCaloriesPerDay(profile, todayDate.year) + burn.totalKcal
    val burnShare = share(movedKcal + standbyKcal, totalBurnGoal)
    val foodTarget = dailyFoodTarget(budget.targetKcal, movedKcal)
    val foodShare = share(eatenKcal, foodTarget)
    val faces = listOf(
        MetricFace(
            metric = HomeMetric.STEPS,
            art = R.drawable.ic_art_steps,
            tint = PwnIndigo,
            title = stringResource(R.string.steps),
            value = steps.formatted(),
            goal = profile.dailyStepGoal.formatted(),
            progress = stepShare,
            detail = walked,
            hours = stepHours,
            chartTitle = activityChart,
            unit = stringResource(R.string.steps_unit),
            hoursPartial = stepsPartial,
            stats = listOf(
                walkingClock to
                    stringResource(R.string.home_stat_walks),
                percent(stepShare) to goalLabel,
                walked to stringResource(R.string.home_stat_distance),
            ),
        ),
        MetricFace(
            metric = HomeMetric.FOOD,
            art = R.drawable.ic_art_food,
            tint = PwnOrange,
            title = stringResource(R.string.home_card_food),
            value = eatenKcal.wholeKcal(),
            goal = foodTarget.wholeKcal(),
            progress = foodShare,
            // The macro summary sits under the same activity-adjusted target as the budget panel.
            detail = stringResource(
                R.string.entry_food_details,
                food.sumOf { it.proteinG }.shown(),
                food.sumOf { it.fatG }.shown(),
                food.sumOf { it.carbsG }.shown(),
            ),
            hours = foodHours,
            // Not "activity by hour": these bars are what was eaten, bucketed by the clock on each
            // row. They are also the one series here that does add up to the figure above it.
            chartTitle = stringResource(R.string.home_hourly_food),
            unit = stringResource(R.string.unit_kcal),
            hoursPartial = false,
            // Two, not three: the other cards' third figure is one the budget panel further down
            // states properly, and a summary that disagrees with the working under it is worse
            // than no summary. Protein is bare — the macro bar down there carries unit and target.
            stats = listOf(
                food.sumOf { it.proteinG }.shown() to stringResource(R.string.home_stat_protein),
                percent(foodShare) to goalLabel,
            ),
        ),
        MetricFace(
            metric = HomeMetric.BURN,
            art = R.drawable.ic_art_calories,
            // Purple is what this app draws movement in — the workout rows and the sleep card wear
            // it — and active energy is movement counted in kilocalories.
            tint = PwnPurple,
            title = stringResource(R.string.home_card_calories),
            value = (movedKcal + standbyKcal).wholeKcal(),
            goal = totalBurnGoal.wholeKcal(),
            progress = burnShare,
            detail = stringResource(R.string.home_from_steps, walkedKcal.wholeKcal()),
            hours = burnHours,
            chartTitle = activityChart,
            unit = stringResource(R.string.unit_kcal),
            // The bars price this phone's steps; a swim and a press are in the figure above them
            // and were never in an hour of the pedometer's.
            hoursPartial = burnPartial,
            stats = listOf(
                standbyKcal.wholeKcal() to stringResource(R.string.home_stat_rest),
                movedKcal.wholeKcal() to stringResource(R.string.burn_active),
                activityClock to workoutLabel,
            ),
        ),
    )
    return HomeToday(
        faces = faces,
        journal = journal,
        budget = budget,
        eatenKcal = eatenKcal,
        movedKcal = movedKcal,
        standbyKcal = standbyKcal,
        restingForecast = restingCaloriesPerDay(profile, todayDate.year),
        walkingKcal = walkedKcal,
        proteinG = food.sumOf { it.proteinG },
        fatG = food.sumOf { it.fatG },
        carbsG = food.sumOf { it.carbsG },
        motion = motion,
        nutritionAdvice = remember(journal, profile, todayDate, minuteTick, movedKcal) {
            dailyNutritionAdvice(journal, profile, LocalDateTime.now(), movedKcal)
        },
    )
}

/**
 * The carousel: one card in the middle, its neighbours peeking in from either side, and no end to
 * it — past the last card is the first again.
 *
 * The pager consumes its own horizontal drags, as the rulers and the sleep dial do. Nothing above it
 * listens for a sideways swipe.
 */
@Composable
internal fun HomeMetricCarousel(faces: List<MetricFace>, onOpen: (HomeMetric) -> Unit, cardHeight: Dp) {
    // Which cards have had their goal celebrated today. Held here rather than in the card: the
    // endless pager composes the same card under a new page index every lap, and a page's own
    // state starts over, so the glow would play again each time round.
    val today = rememberToday()
    var celebrated by rememberSaveable(today.toString()) { mutableStateOf(listOf<String>()) }
    // A page count nobody swipes to the end of, opened in its middle on the first card: the page
    // before it is the last card and the page after it the second, and the loop closes itself.
    val initialPage = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2) % faces.size
    val state = rememberPagerState(initialPage = initialPage, pageCount = { Int.MAX_VALUE })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inset = maxWidth * ASIDE_INSET
        val page = maxWidth - inset * 2
        // Scaling a neighbour down takes this much off its inner edge; the pages are set that much
        // closer, so the air between a card and the next is ASIDE_GAP wherever the swipe has got to.
        val shrink = page * (1f - ASIDE_SCALE) / 2f
        // Slide the neighbouring summary into the visible strip of its card.
        val strip = inset - ASIDE_GAP
        val slide = (page / 2f - shrink - strip / 2f) / ASIDE_SCALE
        // A layer's translation knows nothing of the writing direction, and in an Arabic layout
        // the page after this one stands on its left.
        val inward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(
                state = state,
                contentPadding = PaddingValues(horizontal = inset),
                pageSpacing = ASIDE_GAP - shrink,
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                val face = faces[index % faces.size]
                // How far this page stands from the middle, in pages and signed: the left
                // neighbour is −1, the right one +1, and a swipe walks it through the fractions.
                val offset = { index - state.currentPage - state.currentPageOffsetFraction }
                MetricCard(
                    face = face,
                    isPrimary = index == state.currentPage,
                    asidePadding = ((page - strip / (ASIDE_SCALE * ASIDE_FIT)) / 2f).coerceAtLeast(0.dp),
                    cardHeight = cardHeight,
                    celebrated = face.metric.name in celebrated,
                    onCelebrated = { celebrated = celebrated + face.metric.name },
                    modifier = Modifier.zIndex(if (index == state.currentPage) 1f else 0f).graphicsLayer {
                        val distance = abs(offset()).coerceIn(0f, 1f)
                        scaleX = lerp(1f, ASIDE_SCALE, distance)
                        scaleY = scaleX
                    },
                    contentModifier = Modifier.graphicsLayer {
                        val distance = abs(offset()).coerceIn(0f, 1f)
                        scaleX = lerp(1f, ASIDE_FIT, distance)
                        scaleY = scaleX
                        translationX = inward * offset().coerceIn(-1f, 1f) * slide.toPx()
                    },
                    onClick = { onOpen(face.metric) },
                )
            }
            Spacer(Modifier.height(14.dp))
            HomePagerDots(faces.size, state.currentPage % faces.size)
        }
    }
}

/**
 * Each metric has its own detail: step history, expenditure breakdown, or budget and meals.
 *
 * The page is one of the many the app holds open over whatever opened it, so the rising, the
 * falling, the pull down and the system back all belong to [PwnSheetPage] around it; what is left
 * here is the card's own title and the button that shuts it.
 */
@Composable
internal fun MetricDetailScreen(
    modifier: Modifier,
    profile: UserProfile,
    metric: HomeMetric,
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val today = rememberHomeToday(profile)
    val face = today.faces.firstOrNull { it.metric == metric } ?: today.faces.first()
    val detailScroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // Not [PwnTopBar]: what undoes opening a card out to fill the screen is shutting it again,
            // and the title belongs where the card had it rather than centred over an arrow.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    face.title,
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = PwnIndigoSoft,
                        contentColor = PwnIndigoDark,
                    ),
                ) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.common_close), tint = PwnMuted)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(detailScroll).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (metric == HomeMetric.STEPS) 10.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (metric) {
                    HomeMetric.STEPS -> {
                        StepsDetailContent(face, profile.imperial, today.motion)
                    }

                    HomeMetric.BURN -> BurnBreakdown(today)

                    HomeMetric.FOOD -> {
                        MetricRing(face)
                        PwnCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                DayBudgetPanel(
                                    budget = today.budget,
                                    eatenKcal = today.eatenKcal,
                                    movedKcal = today.movedKcal,
                                    proteinG = today.proteinG,
                                    fatG = today.fatG,
                                    carbsG = today.carbsG,
                                )
                            }
                        }
                        DailyNutritionCards(today.nutritionAdvice)
                        FoodHistory(today.journal.filter { it.kind == KIND_EAT }, onEdit)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DailyNutritionCards(advice: List<Advice>) {
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(AdviceLog.hidden(context)) }
    advice.filterNot { it.topic.name in hidden }.forEach { item ->
        AdviceCard(item) { hidden = AdviceLog.hidden(context) }
    }
}

/**
 * The figure inside a ring that fills to the same share the card's border traces.
 *
 * The card's detail line stays on the card. Inside a circle a line of text has only the chord to
 * live on — about 180dp on a 360dp phone — and the food card's «Б 128,5 · Ж 58,3 · У 265,5 г» and
 * the German burn line both run 15-25dp past it and print over the stroke. The stat row directly
 * under the ring says the same things with the whole width to say them in.
 */
@Composable
internal fun MetricRing(face: MetricFace) {
    // Held as State and read inside the draw lambda, the way the card's outline holds its own.
    // Unwrapped here it would recompose the picture, the figure and the line under it once per
    // frame, and PROGRESS_MS is long enough that this would run for the better part of two seconds.
    val progress = animateFloatAsState(
        targetValue = face.progress,
        animationSpec = tween(PROGRESS_MS, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val track = PwnIndigoSoft
    val brush = Brush.linearGradient(PwnGradientColors)
    // A share of the width on a phone, and no more than a fixed side or a share of the window's
    // height beyond it: in a wide pane or a landscape window a share of the width alone would draw a
    // ring taller than the window it is in, with the figures it frames scrolled out of sight.
    val window = LocalWindowInfo.current.containerSize
    val tallest = with(LocalDensity.current) { window.height.toDp() } * RING_WINDOW_SHARE
    val side = if (window.height > 0) minOf(RING_MAX_SIDE, tallest) else RING_MAX_SIDE
    val filledShare = face.progress.coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth(if (face.metric == HomeMetric.STEPS) 0.70f else 0.78f)
            .wrapContentWidth()
            .widthIn(max = side)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // The share it is filled to, as a progress reading: the figure inside is the count, and
        // this is how far through the goal that count is.
        Canvas(
            Modifier.fillMaxSize().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(filledShare, 0f..1f)
            },
        ) {
            val stroke = RING_STROKE.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            val cap = Stroke(stroke, cap = StrokeCap.Round)
            val filled = progress.value.coerceIn(0f, 1f)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), box, style = cap)
            if (filled > 0f) drawArc(brush, -90f, 360f * filled, false, Offset(inset, inset), box, style = cap)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (face.metric == HomeMetric.STEPS) {
                Box(Modifier.size(56.dp).background(PwnBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(themePainterResource(R.drawable.ic_shoe_outline), null, Modifier.size(32.dp), tint = PwnInk)
                }
            } else {
                Image(themePainterResource(face.art), contentDescription = null, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                face.value,
                style = if (face.metric == HomeMetric.STEPS) {
                    MaterialTheme.typography.displayMedium
                } else {
                    MaterialTheme.typography.displaySmall
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                if (face.goal.isEmpty()) {
                    stringResource(
                        R.string.burn_total_so_far,
                    )
                } else {
                    stringResource(R.string.home_of_goal, face.goal)
                },
                style = MaterialTheme.typography.titleMedium,
                color = PwnMuted,
                maxLines = 1,
            )
        }
    }
}

/** The row of figures under the ring, hairlined apart. */
@Composable
internal fun StatRow(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        stats.forEachIndexed { index, stat ->
            if (index > 0) VerticalDivider(Modifier.padding(vertical = 4.dp), color = PwnBorder)
            Column(
                Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // One line, shrunk to fit: in a column a quarter of the screen wide "≈ 3 ч 21 мин"
                // would break after the hour and read as two figures.
                Text(
                    stat.first,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 1.sp),
                )
                Text(
                    stat.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The selected card shows the full day; neighbours show a compact summary of the latest activity.
 *
 * Every metric is drawn by this one layout, detail line included, so the three pages of the pager
 * measure the same height and the carousel does not change size as it is swiped. [contentModifier]
 * moves the neighbouring summary into the strip of its card that is visible.
 */
@Composable
private fun MetricCard(
    face: MetricFace,
    modifier: Modifier,
    contentModifier: Modifier,
    isPrimary: Boolean,
    asidePadding: Dp,
    cardHeight: Dp,
    celebrated: Boolean,
    onCelebrated: () -> Unit,
    onClick: () -> Unit,
) {
    val gradient = PwnGradientColors
    val reached = face.progress >= 1f
    val celebration = remember { Animatable(0f) }
    // Keyed on the goal alone: the celebrated flag flips as this starts, and restarting on it
    // would cut the glow off at its first frame.
    LaunchedEffect(reached) {
        if (reached && !celebrated) {
            onCelebrated()
            // The app's spatial spring for the swell and for the settle back, with the glow held
            // between them long enough to be seen. It still plays once per goal and then stops.
            celebration.animateTo(1f, PwnMotion.spatial())
            delay(CELEBRATION_HOLD_MS)
            celebration.animateTo(0f, PwnMotion.spatial())
        }
    }
    // The line runs once for every swipe that lands on this card, back from zero each time. An
    // [animateFloatAsState] would not: a card would do its one run while it was still a neighbour,
    // so swiping to it would arrive at a line already drawn. The figure under the line moves too,
    // though — the energy card's with every minute, the steps' with every write of the counter — and
    // that carries the line on from where it stands ([progressLineStart]); taken back to zero for each
    // of those, it would vanish and draw itself again once a minute, and never arrive on a walk.
    //
    // Held as State and read inside the draw lambda rather than unwrapped here: read in
    // composition it invalidates the cache below on every frame of the animation, and the cache
    // holds a Path of four arcs and a native PathMeasure that has to flatten the whole outline.
    val progress = remember { Animatable(0f) }
    // Whether the line has run since the swipe that landed here. Read and written by the effect alone.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(isPrimary, face.progress) {
        if (!isPrimary) {
            landed = false
            return@LaunchedEffect
        }
        progress.snapTo(progressLineStart(landed, progress.value))
        landed = true
        progress.animateTo(face.progress, tween(PROGRESS_MS, easing = FastOutSlowInEasing))
    }
    PwnCard(
        modifier.fillMaxWidth().graphicsLayer {
            scaleX = 1f + celebration.value * 0.018f
            scaleY = scaleX
        },
        onClick = onClick,
    ) {
        Box(
            Modifier.fillMaxWidth().drawWithContent {
                drawContent()
                // Clamped: a spring can carry a hair past its ends, and a colour's alpha may not.
                drawRoundRect(
                    face.tint.copy(alpha = (celebration.value * 0.12f).coerceIn(0f, 1f)),
                    cornerRadius = CornerRadius(CARD_RADIUS.toPx()),
                )
            }.then(
                if (isPrimary) Modifier.progressOutline(face.tint, gradient) { progress.value } else Modifier,
            ),
        ) {
            Column(
                contentModifier.fillMaxWidth().heightIn(min = cardHeight)
                    .padding(horizontal = if (isPrimary) 18.dp else asidePadding, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricCardBody(face, reached, isPrimary)
            }
        }
    }
}

/** What stands inside the card's border, top to bottom. */
@Composable
private fun MetricCardBody(face: MetricFace, reached: Boolean, isPrimary: Boolean) {
    // Each card's picture sits in a pale disc of the card's own colour.
    Box(
        Modifier.size(56.dp).background(face.tint.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Unlabelled on every card: the title printed beside it is already in the card's merged
        // announcement, and naming the picture too would read the title out twice on the neighbours.
        Image(
            themePainterResource(face.art),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
    }
    if (!isPrimary) {
        MetricAsideBody(face)
        return
    }
    Spacer(Modifier.height(4.dp))
    Text(
        face.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        face.value,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 24.sp,
            maxFontSize = 44.sp,
            stepSize = 1.sp,
        ),
    )
    // The denominator uses the same basis as the headline value, including total daily burn.
    Text(
        if (face.goal.isEmpty()) face.unit else stringResource(R.string.home_of_goal, face.goal),
        style = MaterialTheme.typography.titleMedium,
        color = PwnMuted,
        maxLines = 1,
    )
    Text(
        face.detail,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (reached) {
        Text(
            stringResource(R.string.metric_goal_reached),
            color = PwnAccentInk,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Text(stringResource(R.string.home_today), style = MaterialTheme.typography.labelSmall, color = PwnMuted)
    Spacer(Modifier.height(8.dp))
    HourStrip(face.hours, face.tint, height = 56.dp, description = busiestHourSummary(face, 0 until HOURS_IN_DAY))
    Spacer(Modifier.height(8.dp))
    StatRow(face.stats.take(2))
}

/** Six hours ending at the most recent nonzero bucket, without the rest of the day's timeline. */
internal fun recentActivityHours(values: List<Float>): List<Float> {
    val end = values.indexOfLast { it > 0f }
    val recent = if (end < 0) emptyList() else values.subList((end - 5).coerceAtLeast(0), end + 1)
    return List(6 - recent.size) { 0f } + recent
}

@Composable
private fun MetricAsideBody(face: MetricFace) {
    Text(
        face.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            face.value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 32.sp),
        )
        Text(
            stringResource(R.string.home_of_goal, face.goal),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = PwnMuted,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp),
        )
    }
    val recent = recentActivityHours(face.hours)
    val last = face.hours.indexOfLast { it > 0f }
    val summary = busiestHourSummary(face, (last - (recent.size - 1)).coerceAtLeast(0)..last)
    Canvas(Modifier.fillMaxWidth().height(52.dp).describedAs(summary)) {
        drawHourBars(recent, face.tint, Rect(0f, 0f, size.width, size.height))
    }
    StatRow(listOf(percent(face.progress) to stringResource(R.string.home_stat_goal)))
}

/**
 * The day as twenty-four capsules under the card's figures, with its busiest hours picked out.
 *
 * An hour with nothing in it keeps a stub rather than a gap, the way the dashboard's week of bars
 * does: a chart with holes in it reads as a chart that broke rather than as hours nobody moved in.
 */
@Composable
private fun HourStrip(values: List<Float>, tint: Color, height: Dp, description: String?) {
    val hours = hourAxis()
    val footer = hours.first().second.size.height.toFloat()
    Canvas(Modifier.fillMaxWidth().height(height).describedAs(description)) {
        val gap = AXIS_GAP.toPx()
        val plot = Rect(0f, 0f, size.width, size.height - footer - gap)
        drawHourBars(values, tint, plot)
        drawHourAxis(hours, plot, gap)
    }
}

/**
 * The bars said in a sentence, for a screen reader: what they count and the fullest hour among
 * [window], with its figure. Nothing else on the card gives the hour-by-hour shape as text.
 *
 * Null when every hour in the window is empty, so a chart of nothing stays silent rather than
 * naming an hour in which nothing happened.
 */
@Composable
private fun busiestHourSummary(face: MetricFace, window: IntRange): String? {
    val hour = window.filter { face.hours.getOrElse(it) { 0f } > 0f }.maxByOrNull { face.hours[it] }
        ?: return null
    // Isolated left to right, as every clock range on these pages is: in an Arabic sentence the
    // two times would otherwise swap places.
    val range = "\u2066${hourText(hour)}\u2013${hourText(hour + 1)}\u2069"
    val figure = stringResource(R.string.home_peak_hour, face.hours[hour].toDouble().wholeKcal(), face.unit)
    return stringResource(R.string.home_chart_busiest, face.chartTitle, range, figure)
}

/** A drawing's text alternative, where it has one. */
private fun Modifier.describedAs(description: String?): Modifier =
    if (description == null) this else semantics { contentDescription = description }

/**
 * The bars themselves: a capsule an hour, the fullest of them solid and the quiet hours behind
 * them in the same colour worn thin.
 *
 * [ceiling] is the figure the plot's full height stands for. The strip on the card scales to its
 * own tallest hour — it has no rulings to disagree with — while the opened chart scales to the
 * round number its top ruling claims.
 */
private fun DrawScope.drawHourBars(values: List<Float>, tint: Color, plot: Rect, ceiling: Float = 0f) {
    val top = ceiling.takeIf { it > 0f } ?: values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val slot = plot.width / values.size.coerceAtLeast(1)
    val width = (slot - 3.dp.toPx()).coerceIn(1.dp.toPx(), BAR_MAX_WIDTH.toPx())
    val loudest = values.maxOrNull() ?: 0f
    values.forEachIndexed { hour, value ->
        val tall = (value / top * plot.height).coerceIn(2.dp.toPx(), plot.height)
        val shade = when {
            value <= 0f -> STUB_ALPHA
            value >= loudest * PEAK_SHARE -> 1f
            else -> QUIET_BAR_ALPHA
        }
        drawRoundRect(
            color = tint.copy(alpha = shade),
            topLeft = Offset(plot.left + slot * hour + (slot - width) / 2f, plot.bottom - tall),
            size = Size(width, tall),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
        )
    }
}

/** The clock under the bars, each number centred on the hour it names. */
private fun DrawScope.drawHourAxis(hours: List<Pair<Int, TextLayoutResult>>, plot: Rect, gap: Float) {
    val slot = plot.width / HOURS_IN_DAY
    hours.forEach { (hour, text) ->
        drawText(text, topLeft = Offset(plot.left + slot * (hour + 0.5f) - text.size.width / 2f, plot.bottom + gap))
    }
}

/**
 * The three numbers written under the bars, laid out before the canvas opens.
 *
 * Twenty-four hour, whichever locale is reading: this app writes every other clock that way, and
 * two clocks in two conventions on one screen is worse than one convention nobody chose.
 */
@Composable
private fun hourAxis(): List<Pair<Int, TextLayoutResult>> {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.typography.labelSmall.copy(color = PwnMuted)
    return remember(measurer, axis) { AXIS_HOURS.map { it to measurer.measure("$it:00", axis) } }
}

/** Where the hour axis puts a number: morning, midday, evening. */
private val AXIS_HOURS = listOf(6, 12, 18)

/** The gap between the plot and the numbers around it. */
private val AXIS_GAP = 6.dp

private val BAR_MAX_WIDTH = 14.dp

/**
 * A progress track inset from a fine outer outline, with rounded ends on the filled segments.
 *
 * The fill leaves the top-left corner and the bottom-right corner at once, each in both directions,
 * so at a full day the runs meet with no seam between them and nothing drawn twice.
 *
 * The path is built here rather than by `Path.addRoundRect` because the line has to start at a
 * corner: where Skia opens a rounded rectangle is Skia's business, and a progress line that begins
 * at whichever point that turns out to be reads as a drawing mistake. The runs out of the
 * bottom-right corner are segments of the same measured path rather than a path of their own — a
 * rounded rectangle looks the same turned upside down, so the half-way point of this one is exactly
 * the opposite corner.
 */
private fun Modifier.progressOutline(
    tint: Color,
    gradient: List<Color>,
    progress: () -> Float,
): Modifier = drawWithCache {
    val stroke = PROGRESS_STROKE.toPx()
    val inset = PROGRESS_INSET.toPx()
    val edge = cornerMidRoundRect(size, CARD_RADIUS.toPx() - 0.5.dp.toPx(), 0.5.dp.toPx())
    val outline = cornerMidRoundRect(size, CARD_RADIUS.toPx() - inset, inset)
    val measure = PathMeasure().apply { setPath(outline, false) }
    val length = measure.length
    val half = length / 2f
    val quarter = length / 4f
    val run = Path()
    val line = Stroke(stroke, cap = StrokeCap.Round)
    val hairline = Stroke(1.dp.toPx())
    val track = Stroke(stroke)
    val brush = Brush.linearGradient(
        gradient,
        Offset.Zero,
        Offset(size.width, size.height),
    )
    onDrawBehind {
        drawPath(edge, tint.copy(alpha = 0.12f), style = hairline)
        drawPath(outline, tint.copy(alpha = 0.16f), style = track)
        // Four runs, not two: each corner opens in both directions at once, so the line grows out
        // of the corner rather than setting off from it. They pair up — the two out of the top
        // left and the two out of the bottom right — and at the goal the four heads meet in the
        // middle of the two long edges and the outline closes with nothing drawn twice.
        val travelled = quarter * progress().coerceIn(0f, 1f)
        if (travelled > 0f) {
            drawRun(measure, run, 0f, travelled, brush, line)
            drawRun(measure, run, length - travelled, length, brush, line)
            drawRun(measure, run, half, half + travelled, brush, line)
            drawRun(measure, run, half - travelled, half, brush, line)
        }
    }
}

/** One stretch of the measured outline, reusing the caller's [Path] rather than making its own. */
private fun DrawScope.drawRun(
    measure: PathMeasure,
    run: Path,
    from: Float,
    to: Float,
    brush: Brush,
    line: Stroke,
) {
    run.reset()
    measure.getSegment(from, to, run, true)
    drawPath(run, brush, style = line)
}

/**
 * A rounded rectangle that opens in the middle of the top-left corner and runs clockwise.
 *
 * The middle of the corner and not the end of its arc: a run that began where the curve straightens
 * into the top edge would start a corner's width away from the corner it is supposed to start at, and
 * on a card as round as this one that is far enough in to read as starting from nowhere in particular.
 *
 * Its half-way point is the middle of the bottom-right corner, which is that same point turned
 * through half a circle — half the corner, the top edge, a whole corner, the right edge and half a
 * corner is exactly half of four edges and four corners. That is what lets the two corners be
 * addressed as `0` and `length / 2` without measuring anything else.
 */
private fun cornerMidRoundRect(size: Size, radius: Float, inset: Float): Path {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val r = min(radius, min(right - left, bottom - top) / 2f).coerceAtLeast(0f)
    val d = r * 2f
    // Where the corner's arc is at forty-five degrees, measured from the centre it turns about.
    val offset = r * CORNER_MID_OFFSET
    return Path().apply {
        moveTo(left + r - offset, top + r - offset)
        arcTo(Rect(left, top, left + d, top + d), 225f, 45f, false)
        lineTo(right - r, top)
        arcTo(Rect(right - d, top, right, top + d), 270f, 90f, false)
        lineTo(right, bottom - r)
        arcTo(Rect(right - d, bottom - d, right, bottom), 0f, 90f, false)
        lineTo(left + r, bottom)
        arcTo(Rect(left, bottom - d, left + d, bottom), 90f, 90f, false)
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + d, top + d), 180f, 45f, false)
    }
}

/** cos 45°, which is also sin 45°: how far the corner's midpoint stands from its centre, per radius. */
private const val CORNER_MID_OFFSET = 0.70710678f

/**
 * The day's energy budget: what is left of it, what has been eaten and moved, and the macros.
 *
 * Shown only on the food page. Active expenditure increases the food allowance; resting
 * expenditure is already part of the target and is itemised on the calorie page.
 */
@Composable
internal fun DayBudgetPanel(
    budget: EnergyBudget,
    eatenKcal: Double,
    movedKcal: Double,
    proteinG: Double,
    fatG: Double,
    carbsG: Double,
) {
    val remaining = remainingKcal(budget.targetKcal, eatenKcal, movedKcal)
    // The limit the pair below prints is the one the card, the ring above it and the figure on the
    // right of this very row are all already counting against — the day's target with what was
    // moved added on. Printing the bare target instead would put two different limits on one page
    // and leave "1,050 / 2,200" standing beside "1,470 left", which is arithmetic nobody can follow.
    val limit = dailyFoodTarget(budget.targetKcal, movedKcal)
    // Nothing in the resources says "so much moved" on its own — the one string that says it is the
    // three-number breakdown, which this panel does not print — so the movement line is the app's own
    // kilocalorie format beside the word it labels an activity figure with everywhere else.
    val moved = stringResource(R.string.burn_kcal, movedKcal.wholeKcal())
    val movedLabel = stringResource(R.string.steps_activity_label)
    // That breakdown is exactly what the panel means, and it is what the pair below is read
    // out as: spoken aloud, a figure followed by "/ 2,520" is two numbers with a slash between them.
    val spoken = stringResource(
        R.string.budget_breakdown,
        limit.wholeKcal(),
        eatenKcal.wholeKcal(),
        movedKcal.wholeKcal(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.budget_title), style = MaterialTheme.typography.titleMedium)
                // What has been eaten is the figure this panel is opened for, and in one muted line
                // of three it would carry no more weight than the target standing beside it. So it
                // is bold against a muted limit, and the two share a baseline so they read as one
                // figure rather than as a number with a caption after it.
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = spoken },
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        eatenKcal.wholeKcal(),
                        Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.home_of_goal, limit.wholeKcal()),
                        Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.titleMedium,
                        color = PwnMuted,
                        maxLines = 1,
                        // An ellipsis rather than the default clip: at a large font scale this pair
                        // runs out of room, and a limit cut to "2,5" is a wrong number rather than
                        // a shortened one.
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The movement stays, because it is why the figure on the right of this row is not
                // simply the limit less what was eaten. Dropping it would leave a day that allowed
                // three hundred kilocalories more than the limit says with nothing to explain it.
                Text("$moved · $movedLabel", color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    remaining.wholeKcal(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (remaining < 0) PwnWarn else PwnIndigo,
                )
                Text(
                    stringResource(if (remaining < 0) R.string.budget_over else R.string.budget_left),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MacroBar(stringResource(R.string.editor_protein), proteinG, budget.proteinG, PwnIndigo)
        MacroBar(stringResource(R.string.editor_fat), fatG, budget.fatG, PwnOrange)
        // The blue at text weight rather than the pale fill blue, which all but vanishes into the
        // card: a bar's filled share has to stand out from the track it is read against.
        MacroBar(stringResource(R.string.editor_carbs), carbsG, budget.carbsG, PwnBlueInk)
    }
}

@Composable
private fun MacroBar(label: String, eatenG: Double, targetG: Double, color: Color) {
    val share = if (targetG <= 0.0) 0f else (eatenG / targetG).coerceIn(0.0, 1.0).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // The same treatment the kilocalorie pair above these bars gets, for the same reason: what
        // has been eaten is the figure being read, and in one muted line it would weigh no more than
        // the target it is being read against.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            // Split in two to be read, joined again to be heard: spoken aloud "12.2 g / 112 g" is
            // a unit said twice, and the one-line form is the sentence.
            val spoken = stringResource(R.string.budget_macro_value, eatenG.shown(), targetG.shown())
            Row(
                Modifier.clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.dish_build_grams, eatenG.shown()),
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.home_of_goal, stringResource(R.string.dish_build_grams, targetG.shown())),
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
        }
        // Material's stop dot is kept: the track is nearly the card's own colour, and the dot at its
        // end is what shows how far the whole runs, so a part-filled bar reads as a share of it.
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
        )
    }
}

/** Steps by hour, with the hours the phone counted nothing in kept as zeroes rather than absent. */
private fun hourlySteps(rows: List<HourSteps>): List<Float> {
    val buckets = FloatArray(HOURS_IN_DAY)
    rows.forEach { row -> if (row.hour in 0 until HOURS_IN_DAY) buckets[row.hour] += row.steps.toFloat() }
    return buckets.toList()
}

/** Energy by the hour it was eaten in, read off the row's own timestamp. */
private fun hourlyKcal(rows: List<JournalEntry>): List<Float> {
    val zone = ZoneId.systemDefault()
    val buckets = FloatArray(HOURS_IN_DAY)
    rows.forEach { row ->
        val hour = Instant.ofEpochMilli(row.occurredAt).atZone(zone).hour
        if (hour in 0 until HOURS_IN_DAY) buckets[hour] += row.calories.toFloat()
    }
    return buckets.toList()
}

/**
 * A figure against its target as a fraction, with a target of nothing reading as nothing done.
 *
 * Uncapped: a day that doubled its goal is a different day from one that just met it, and the
 * percentage under the card says so. The ring and the border line clamp it themselves — a line
 * that ran round twice would say less than one that finished.
 */
private fun share(value: Double, target: Double): Float =
    if (target <= 0.0) 0f else (value / target).toFloat()

/** Hours and minutes: "2h 18m", and "45m" for anything short of the hour. */
internal fun formatActivityClock(resources: Resources, totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    return if (minutes < 60) {
        resources.getString(R.string.home_duration_m, minutes)
    } else {
        resources.getString(R.string.home_duration_hm, minutes / 60, minutes % 60)
    }
}

@Composable
private fun percent(fraction: Float): String = stringResource(R.string.home_percent, (fraction * 100).roundToInt())

/**
 * A kilocalorie figure as a card prints it: whole, and grouped the way the step count beside it is.
 *
 * [pretty] is what the budget panel further down uses, and it keeps a tenth of a kilocalorie
 * — right in a line of small print, wrong at 36sp beside a step count written "7 245". Anything
 * that is not a real number falls back to it rather than through rounding, which would turn a NaN
 * out of a hand-edited backup into a confident nought.
 */
internal fun Double.wholeKcal(): String = if (isFinite()) roundToLong().formatted() else pretty()
