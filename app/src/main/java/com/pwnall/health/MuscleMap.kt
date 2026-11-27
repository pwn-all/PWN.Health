package com.pwnall.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The picture for an exercise: the how-to panel's own opening pose where the exercise has a panel,
 * and the drawn set's general illustration where it does not.
 *
 * Each panel is of one exercise and the general set is not, so the thumbnails cut from the panels
 * are what gives each exercise that has one a picture of its own: five barbell rows are told apart
 * by their pictures, which are a way of finding a row rather than decoration.
 *
 * What is left on the general set is walking, running, hiking and the two catch-all rows, each with a
 * picture of its own, and [OTHER_WORKOUT_CODES], which all wear interval training's.
 */
internal fun ActivitySpec.art(): Int = drawn()?.thumb ?: generalArt()

/**
 * [art] at the size a page's top drawing is shown: the same opening pose, cut at 528px so a 200dp
 * drawing is drawn near its own pixels rather than the 44dp thumbnail stretched three times.
 */
internal fun ActivitySpec.heroArt(): Int = drawn()?.hero ?: generalArt()

/**
 * The general drawn set, for the rows that have no how-to panel.
 *
 * `STRENGTH` and `CALISTHENICS` are catalogue rows rather than movements — a session of unspecified
 * lifting is not a thing anyone can be photographed doing — so a general picture is the honest one
 * for them rather than a gap waiting to be filled.
 */
private fun ActivitySpec.generalArt(): Int = when (code) {
    in OTHER_WORKOUT_CODES -> OTHER_WORKOUTS_ART

    "WALK" -> R.drawable.ic_art_steps

    "RUN" -> R.drawable.ic_art_running

    "HIKING" -> R.drawable.ic_art_hiking

    "CALISTHENICS" -> R.drawable.ic_art_bodyweight

    "STRENGTH" -> R.drawable.ic_art_barbell

    // No row in the catalogue lands here, but a when over a String has to end somewhere,
    // and the drawing of sport in general is the honest thing to end it with.
    else -> R.drawable.ic_art_sport
}

/**
 * The one picture every workout of the clock wears, and their card with them: interval training's.
 * A dozen sports in a dozen drawings would make the card a mosaic of things nobody picks by their picture;
 * the panels some of them have still show how under "How to?".
 */
internal val OTHER_WORKOUTS_ART = R.drawable.ic_art_running

/**
 * What has been drawn for an exercise: the wide how-to panel, and the thumbnail cut out of it.
 *
 * One table rather than two, because the thumbnail is made from the panel by
 * [tools/pose-icons.py](tools/pose-icons.py) and a row that had one without the other would be
 * either a panel no list can show or a thumbnail of a drawing that no longer exists.
 *
 * The panel draws the start pose and the end pose side by side, an arrow from the first to the
 * second, and small rings around the joints that are meant to stay where they are — the elbow that
 * should not drift, the back that should not swing. There is not one word on it, and that is the
 * point. The app is read in sixteen languages, so any caption baked into the picture would be
 * legible to one of them and noise to the other fifteen, and would quietly fall out of step the
 * first time a translation was corrected. A ring drawn round an elbow says "this does not move" in
 * all sixteen at once and can never go stale.
 *
 * The panel is a wide, opaque card that carries its own pale background: lay it out at full width
 * and leave it in its own colours, never tinted. The thumbnail is the opposite — one pose lifted
 * off that background, square and transparent, for the places an icon goes.
 *
 * The two pulldowns each get a panel of their own even though the general set has one machine for
 * both of them, because the grip is precisely what a panel is good at showing.
 *
 * A workout of the clock has its panel alone: its picture is [OTHER_WORKOUTS_ART], as its card's.
 *
 * Null is the ordinary answer rather than a fault: some rows are things a diagram cannot help with —
 * running, football, the catch-all rows, and the classes that are a hundred poses each — and a caller
 * is expected to show the general picture and say nothing about it. Walking's stairs, the pool,
 * basketball and yoga have a panel each all the same.
 */
private class Drawn(val panel: Int, val thumb: Int? = null, val hero: Int? = null)

// A lookup table, one row per drawn exercise: its length is the catalogue's, not a procedure's.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun ActivitySpec.drawn(): Drawn? = when (code) {
    "LAT_PULLDOWN" -> Drawn(
        R.drawable.ic_howto_lat_pulldown,
        R.drawable.ic_pose_lat_pulldown,
        R.drawable.ic_pose_lat_pulldown_hero,
    )

    "LAT_PULLDOWN_CLOSE" -> Drawn(
        R.drawable.ic_howto_lat_pulldown_close,
        R.drawable.ic_pose_lat_pulldown_close,
        R.drawable.ic_pose_lat_pulldown_close_hero,
    )

    "SEATED_ROW" -> Drawn(
        R.drawable.ic_howto_seated_row,
        R.drawable.ic_pose_seated_row,
        R.drawable.ic_pose_seated_row_hero,
    )

    "TRICEPS_PUSHDOWN" -> Drawn(
        R.drawable.ic_howto_triceps_pushdown,
        R.drawable.ic_pose_triceps_pushdown,
        R.drawable.ic_pose_triceps_pushdown_hero,
    )

    "CHEST_PRESS" -> Drawn(
        R.drawable.ic_howto_chest_press,
        R.drawable.ic_pose_chest_press,
        R.drawable.ic_pose_chest_press_hero,
    )

    "PEC_DECK" -> Drawn(R.drawable.ic_howto_pec_deck, R.drawable.ic_pose_pec_deck, R.drawable.ic_pose_pec_deck_hero)

    "CABLE_CROSSOVER" -> Drawn(
        R.drawable.ic_howto_cable_crossover,
        R.drawable.ic_pose_cable_crossover,
        R.drawable.ic_pose_cable_crossover_hero,
    )

    "SHOULDER_PRESS_MACHINE" -> Drawn(
        R.drawable.ic_howto_shoulder_press_machine,
        R.drawable.ic_pose_shoulder_press_machine,
        R.drawable.ic_pose_shoulder_press_machine_hero,
    )

    "LATERAL_RAISE" -> Drawn(
        R.drawable.ic_howto_lateral_raise,
        R.drawable.ic_pose_lateral_raise,
        R.drawable.ic_pose_lateral_raise_hero,
    )

    "LEG_PRESS" -> Drawn(R.drawable.ic_howto_leg_press, R.drawable.ic_pose_leg_press, R.drawable.ic_pose_leg_press_hero)

    "LEG_EXTENSION" -> Drawn(
        R.drawable.ic_howto_leg_extension,
        R.drawable.ic_pose_leg_extension,
        R.drawable.ic_pose_leg_extension_hero,
    )

    "LEG_CURL" -> Drawn(R.drawable.ic_howto_leg_curl, R.drawable.ic_pose_leg_curl, R.drawable.ic_pose_leg_curl_hero)

    "CALF_RAISE" -> Drawn(
        R.drawable.ic_howto_calf_raise,
        R.drawable.ic_pose_calf_raise,
        R.drawable.ic_pose_calf_raise_hero,
    )

    "HIP_THRUST" -> Drawn(
        R.drawable.ic_howto_hip_thrust,
        R.drawable.ic_pose_hip_thrust,
        R.drawable.ic_pose_hip_thrust_hero,
    )

    "HIP_ABDUCTION" -> Drawn(
        R.drawable.ic_howto_hip_abduction,
        R.drawable.ic_pose_hip_abduction,
        R.drawable.ic_pose_hip_abduction_hero,
    )

    "BACK_EXTENSION" -> Drawn(
        R.drawable.ic_howto_back_extension,
        R.drawable.ic_pose_back_extension,
        R.drawable.ic_pose_back_extension_hero,
    )

    "DEADLIFT" -> Drawn(R.drawable.ic_howto_deadlift, R.drawable.ic_pose_deadlift, R.drawable.ic_pose_deadlift_hero)

    "BENCH_PRESS" -> Drawn(
        R.drawable.ic_howto_bench_press,
        R.drawable.ic_pose_bench_press,
        R.drawable.ic_pose_bench_press_hero,
    )

    "SQUATS" -> Drawn(R.drawable.ic_howto_squats, R.drawable.ic_pose_squats, R.drawable.ic_pose_squats_hero)

    "OVERHEAD_PRESS" -> Drawn(
        R.drawable.ic_howto_overhead_press,
        R.drawable.ic_pose_overhead_press,
        R.drawable.ic_pose_overhead_press_hero,
    )

    "BARBELL_ROW" -> Drawn(
        R.drawable.ic_howto_barbell_row,
        R.drawable.ic_pose_barbell_row,
        R.drawable.ic_pose_barbell_row_hero,
    )

    "PULLUPS" -> Drawn(R.drawable.ic_howto_pullups, R.drawable.ic_pose_pullups, R.drawable.ic_pose_pullups_hero)

    "PUSHUPS" -> Drawn(R.drawable.ic_howto_pushups, R.drawable.ic_pose_pushups, R.drawable.ic_pose_pushups_hero)

    "DIPS" -> Drawn(R.drawable.ic_howto_dips, R.drawable.ic_pose_dips, R.drawable.ic_pose_dips_hero)

    "LUNGES" -> Drawn(R.drawable.ic_howto_lunges, R.drawable.ic_pose_lunges, R.drawable.ic_pose_lunges_hero)

    "BICEPS_CURL" -> Drawn(
        R.drawable.ic_howto_biceps_curl,
        R.drawable.ic_pose_biceps_curl,
        R.drawable.ic_pose_biceps_curl_hero,
    )

    "TRICEPS_DUMBBELL" -> Drawn(
        R.drawable.ic_howto_triceps_dumbbell,
        R.drawable.ic_pose_triceps_dumbbell,
        R.drawable.ic_pose_triceps_dumbbell_hero,
    )

    "TRICEPS_BODY" -> Drawn(
        R.drawable.ic_howto_triceps_body,
        R.drawable.ic_pose_triceps_body,
        R.drawable.ic_pose_triceps_body_hero,
    )

    "ABS" -> Drawn(R.drawable.ic_howto_abs, R.drawable.ic_pose_abs, R.drawable.ic_pose_abs_hero)

    "PLANK" -> Drawn(R.drawable.ic_howto_plank, R.drawable.ic_pose_plank, R.drawable.ic_pose_plank_hero)

    "BURPEES" -> Drawn(R.drawable.ic_howto_burpees, R.drawable.ic_pose_burpees, R.drawable.ic_pose_burpees_hero)

    "JUMPING_JACKS" -> Drawn(
        R.drawable.ic_howto_jumping_jacks,
        R.drawable.ic_pose_jumping_jacks,
        R.drawable.ic_pose_jumping_jacks_hero,
    )

    "JUMP_ROPE" -> Drawn(R.drawable.ic_howto_jump_rope, R.drawable.ic_pose_jump_rope, R.drawable.ic_pose_jump_rope_hero)

    "ROWING_MACHINE" -> Drawn(R.drawable.ic_howto_rowing_machine)

    "BAR_LEG_RAISE" -> Drawn(
        R.drawable.ic_howto_bar_leg_raise,
        R.drawable.ic_pose_bar_leg_raise,
        R.drawable.ic_pose_bar_leg_raise_hero,
    )

    "BICYCLE_CRUNCH" -> Drawn(
        R.drawable.ic_howto_bicycle_crunch,
        R.drawable.ic_pose_bicycle_crunch,
        R.drawable.ic_pose_bicycle_crunch_hero,
    )

    "HIP_ADDUCTION" -> Drawn(
        R.drawable.ic_howto_hip_adduction,
        R.drawable.ic_pose_hip_adduction,
        R.drawable.ic_pose_hip_adduction_hero,
    )

    "REVERSE_FLY" -> Drawn(
        R.drawable.ic_howto_reverse_fly,
        R.drawable.ic_pose_reverse_fly,
        R.drawable.ic_pose_reverse_fly_hero,
    )

    "STAIRS" -> Drawn(
        R.drawable.ic_howto_stairs,
        R.drawable.ic_pose_stairs,
        R.drawable.ic_pose_stairs_hero,
    )

    "SWIMMING" -> Drawn(R.drawable.ic_howto_swimming)

    "BASKETBALL" -> Drawn(R.drawable.ic_howto_basketball)

    "YOGA" -> Drawn(R.drawable.ic_howto_yoga)

    else -> null
}

/** The wide instructional panel for an exercise, where one has been drawn. */
internal fun ActivitySpec.howTo(): Int? = drawn()?.panel

internal fun Muscle.labelRes(): Int = when (this) {
    Muscle.CHEST -> R.string.muscle_chest
    Muscle.BACK -> R.string.muscle_back
    Muscle.SHOULDERS -> R.string.muscle_shoulders
    Muscle.ARMS -> R.string.muscle_arms
    Muscle.CORE -> R.string.muscle_core
    Muscle.GLUTES -> R.string.muscle_glutes
    Muscle.LEGS -> R.string.muscle_legs
}

/**
 * The body panel: two anatomical figures — front and back — with everything the exercise does not
 * work drained of its colour, and everything it does work left alone.
 *
 * A drawing of a muscle is a claim about where that muscle is, and one traced by eye onto a stylised
 * figure is a weak one; the anatomical plate underneath makes the claim properly, and all this has
 * to do is say which parts of it are today's work. So the picture is never redrawn — it is drawn
 * twice, once grey and once in full colour through a mask, which is why the lit muscles keep their own
 * anatomy instead of becoming blue blobs of it.
 *
 * The masks are polygons rather than traced outlines on purpose: a straight-edged patch reads as a
 * region the app is pointing at, and does not pretend to a precision the catalogue does not have.
 * The catalogue knows "arms", so the mask covers the arm, and the pills underneath stay the written
 * answer. The exercise's own name is not among them: whatever opens this plate has already named it
 * on the row above.
 */
@Composable
internal fun MuscleMap(muscles: Set<Muscle>, sex: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Surface(shape = MaterialTheme.shapes.large, color = musclePanel()) {
            MuscleBodies(muscles, sex, MUSCLE_MAP_HEIGHT, Modifier.padding(vertical = 14.dp, horizontal = 22.dp))
        }
        // The caveat about what the pills name is an explanation, so it waits behind the question
        // mark at the end of their rail rather than standing under every map as a grey line.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuscleTitleRow(null, muscles, Modifier.weight(1f))
            HelpDot(stringResource(R.string.common_what_is_this)) {
                Text(stringResource(R.string.muscle_map_note))
            }
        }
    }
}

/** The plate alone, front and back, [height] tall: what the map draws, and what a held exercise picture turns into. */
@Composable
internal fun MuscleBodies(muscles: Set<Muscle>, sex: String, height: Dp, modifier: Modifier = Modifier) {
    // One group and only one is what "this is a triceps exercise" looks like in the catalogue, and
    // it is the only case where closing in on a muscle says more than it hides.
    val focus = muscles.singleOrNull()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (focus == null) {
            BodyView(muscles, sex, null, Modifier.weight(1f).height(height), isFront = true)
            BodyView(muscles, sex, null, Modifier.weight(1f).height(height), isFront = false)
        } else {
            // One frame, the whole width. Two tall narrow columns cannot be zoomed into: whatever the
            // crop asks for, a frame half as wide as it is high has to take the full height of the
            // plate to fill itself, and the muscle stays small.
            BodyView(
                muscles,
                sex,
                focus,
                Modifier.weight(1f).height(height),
                isFront = BODY_FRONT.any {
                    it.group ==
                        focus
                },
            )
        }
    }
}

private val MUSCLE_MAP_HEIGHT = 230.dp

/**
 * What the exercise works, as a rail of named pills.
 *
 * [title] is the exercise's own name, and it is optional because on one of the two screens showing
 * this rail the name is already up: the record screen has it in the picker at the top, and
 * repeating it under the body plate would say the same word twice with a muscle map in between. The
 * summary screen has no picker, so there it is still the only place the exercise is named.
 *
 * The pills carry no picture. Anatomy is what the plate above them is for — it lights the worked
 * groups red against a grey body, which answers "where" better than a thumbnail can — so a drawing
 * on the pill would be a second, smaller portrait of what had just been shown properly. Without it the
 * rail is words: it takes the whole width and scrolls when there are more groups than fit.
 *
 * Where it sits on that width follows the same division. Under the body plate only the question
 * mark at the end of the line shares it, and a rail packed to the left under a centred figure reads
 * as having slipped off it, so there the pills are centred; beside a title they are the second half
 * of a line the name begins, and they stay where the name leaves off.
 */
@Composable
internal fun MuscleTitleRow(title: String?, muscles: Set<Muscle>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        title?.let {
            Text(it, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            // Centring needs a width wider than the pills to centre them in, and the rail has one:
            // the weight above hands this row the whole line as its minimum, and a scroll only
            // lifts the maximum, so a rail too long to fit is still one that scrolls.
            horizontalArrangement = if (title == null) {
                Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(8.dp)
            },
        ) {
            Muscle.entries.filter { it in muscles }.forEach { muscle ->
                Surface(
                    shape = RoundedCornerShape(PwnCorners.medium),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        stringResource(muscle.labelRes()),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = PwnAccentInk,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The card the two figures stand on: the page's own quiet surface, in either theme.
 *
 * The scheme's container role rather than two fixed colours picked by the system's dark flag, so a
 * custom background or surface colour carries through to the panel instead of leaving it standing
 * in the default blue-grey on a page that is no longer that colour.
 */
@Composable
internal fun musclePanel(): Color = MaterialTheme.colorScheme.surfaceContainer

/**
 * One muscle group's mask on one view, as a polygon in the picture's own 0..1 space.
 *
 * [mirrored] draws the twin across the figure's own axis, which every plate here is centred on:
 * the two arms are one shape written once, and they cannot drift apart.
 */
private class MusclePatch(val group: Muscle, val mirrored: Boolean, vararg pts: Float) {
    val points: List<Offset> = pts.toList().chunked(2).map { Offset(it[0], it[1]) }
}

/**
 * The front plate: deltoids, pectorals, the arm down to the wrist, the abdominal wall, and the
 * thigh and shin of each leg. The back muscles and the glutes have no patch here, because they are
 * not in this view and lighting the nearest thing that is would be a lie about the picture.
 */
private val BODY_FRONT = listOf(
    MusclePatch(
        Muscle.SHOULDERS, true,
        0.215f, 0.048f, 0.285f, 0.062f, 0.322f, 0.100f, 0.318f, 0.152f,
        0.262f, 0.190f, 0.178f, 0.184f, 0.140f, 0.140f, 0.150f, 0.085f,
    ),
    MusclePatch(
        Muscle.CHEST, true,
        0.500f, 0.052f, 0.380f, 0.060f, 0.322f, 0.092f, 0.310f, 0.140f,
        0.358f, 0.180f, 0.440f, 0.200f, 0.500f, 0.192f,
    ),
    // One patch from the shoulder to the wrist, not an upper arm and a forearm: the seam between
    // two of them falls across the elbow, and at the size a focused exercise draws this, a mask
    // with a notch in it reads as a mistake in the picture rather than as a boundary.
    MusclePatch(
        Muscle.ARMS, true,
        0.300f, 0.166f, 0.292f, 0.222f, 0.278f, 0.280f, 0.256f, 0.336f,
        0.232f, 0.392f, 0.172f, 0.418f, 0.104f, 0.390f, 0.058f, 0.326f,
        0.066f, 0.258f, 0.100f, 0.208f, 0.156f, 0.194f,
    ),
    MusclePatch(
        Muscle.CORE, false,
        0.400f, 0.180f, 0.600f, 0.180f, 0.622f, 0.240f, 0.610f, 0.300f,
        0.560f, 0.342f, 0.500f, 0.356f, 0.440f, 0.342f, 0.390f, 0.300f, 0.378f, 0.240f,
    ),
    MusclePatch(
        Muscle.LEGS, true,
        0.280f, 0.360f, 0.430f, 0.350f, 0.470f, 0.420f, 0.470f, 0.500f,
        0.450f, 0.570f, 0.410f, 0.612f, 0.350f, 0.602f, 0.300f, 0.530f, 0.270f, 0.440f,
    ),
    MusclePatch(
        Muscle.LEGS, true,
        0.300f, 0.630f, 0.410f, 0.630f, 0.430f, 0.692f, 0.418f, 0.758f,
        0.386f, 0.822f, 0.340f, 0.828f, 0.310f, 0.762f, 0.292f, 0.688f,
    ),
)

/**
 * The back plate: rear deltoids, the trapezius and latissimus sheet, the triceps and forearm, the
 * glutes, and the hamstring and calf of each leg. The chest and the abdominal wall are not in this
 * view and have no patch in it.
 */
private val BODY_BACK = listOf(
    MusclePatch(
        Muscle.SHOULDERS, true,
        0.218f, 0.046f, 0.288f, 0.060f, 0.326f, 0.100f, 0.322f, 0.152f,
        0.266f, 0.188f, 0.180f, 0.182f, 0.142f, 0.138f, 0.152f, 0.082f,
    ),
    MusclePatch(
        Muscle.BACK, false,
        0.500f, 0.022f, 0.588f, 0.040f, 0.648f, 0.086f, 0.686f, 0.150f,
        0.698f, 0.212f, 0.658f, 0.268f, 0.575f, 0.288f, 0.500f, 0.294f,
        0.425f, 0.288f, 0.342f, 0.268f, 0.302f, 0.212f, 0.314f, 0.150f,
        0.352f, 0.086f, 0.412f, 0.040f,
    ),
    MusclePatch(
        Muscle.ARMS, true,
        0.304f, 0.164f, 0.296f, 0.220f, 0.282f, 0.274f, 0.260f, 0.324f,
        0.236f, 0.376f, 0.178f, 0.402f, 0.110f, 0.376f, 0.068f, 0.314f,
        0.076f, 0.250f, 0.108f, 0.202f, 0.158f, 0.192f,
    ),
    MusclePatch(
        Muscle.GLUTES, false,
        0.322f, 0.298f, 0.420f, 0.288f, 0.500f, 0.292f, 0.580f, 0.288f,
        0.678f, 0.298f, 0.700f, 0.352f, 0.664f, 0.408f, 0.580f, 0.430f,
        0.500f, 0.426f, 0.420f, 0.430f, 0.336f, 0.408f, 0.300f, 0.352f,
    ),
    MusclePatch(
        Muscle.LEGS, true,
        0.302f, 0.442f, 0.428f, 0.436f, 0.462f, 0.490f, 0.458f, 0.550f,
        0.428f, 0.598f, 0.372f, 0.610f, 0.320f, 0.574f, 0.294f, 0.502f,
    ),
    MusclePatch(
        Muscle.LEGS, true,
        0.306f, 0.618f, 0.424f, 0.618f, 0.448f, 0.680f, 0.440f, 0.750f,
        0.402f, 0.808f, 0.350f, 0.800f, 0.318f, 0.734f, 0.300f, 0.664f,
    ),
)

/** How much air a zoomed-in muscle keeps round it, as a share of its own size. */
private const val MUSCLE_FOCUS_MARGIN = 0.28f

/** How much colour an unworked muscle keeps. None: the whole point is that it is not today's. */
private const val MUSCLE_IDLE_SATURATION = 0f

/** And how strongly it is still there. Faded, not deleted — the body has to stay a body. */
internal const val MUSCLE_IDLE_ALPHA = 0.42f

/**
 * Which photographed plate a profile is drawn on: a woman's for a female profile, a man's for
 * anything else — including no answer, since the man's plate is the one the catalogue was drawn on.
 */
internal fun bodyPlateRes(sex: String, isFront: Boolean): Int {
    val woman = sex == "FEMALE"
    return when {
        woman && isFront -> R.drawable.ic_body_woman_front
        woman -> R.drawable.ic_body_woman_back
        isFront -> R.drawable.ic_body_man_front
        else -> R.drawable.ic_body_man_back
    }
}

/**
 * The drained filter the idle body is drawn through. A function rather than a value, and called
 * inside `remember`: it wraps a platform colour filter, and a top-level value would build one the
 * moment anything in this file was first touched — a JVM test reading a label included.
 */
internal fun bodyPlateGrey(): ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(MUSCLE_IDLE_SATURATION) })

/**
 * How a crop of a plate sits in a frame: fitted rather than stretched, and centred.
 *
 * [source] is in the image's own pixels. [place] and [unplace] work for any point of the plate's
 * 0..1 space and past it, so a stroke that runs off the plate maps outside the image rather than
 * onto its edge.
 */
internal class PlateFit(val source: Rect, val imageWidth: Float, val imageHeight: Float, frame: Size) {
    // An anatomical plate that does not keep its proportions is a picture of a different body.
    val scale = minOf(frame.width / source.width, frame.height / source.height)
    val drawn = Size(source.width * scale, source.height * scale)
    val origin = Offset((frame.width - drawn.width) / 2f, (frame.height - drawn.height) / 2f)

    fun place(point: Offset) = Offset(
        origin.x + (point.x * imageWidth - source.left) * scale,
        origin.y + (point.y * imageHeight - source.top) * scale,
    )

    /** The inverse of [place]: a point of the frame, back in the plate's 0..1 space. */
    fun unplace(point: Offset) = Offset(
        ((point.x - origin.x) / scale + source.left) / imageWidth,
        ((point.y - origin.y) / scale + source.top) / imageHeight,
    )
}

/** One pass of a plate: [srcSize] of it from [srcOffset], into [dstSize] at [dstOffset]. */
internal fun DrawScope.drawPlate(
    plate: ImageBitmap,
    srcOffset: Offset,
    srcSize: Size,
    dstOffset: Offset,
    dstSize: Size,
    filter: ColorFilter?,
    alpha: Float,
) = drawImage(
    image = plate,
    srcOffset = IntOffset(srcOffset.x.roundToInt(), srcOffset.y.roundToInt()),
    srcSize = IntSize(srcSize.width.roundToInt(), srcSize.height.roundToInt()),
    dstOffset = IntOffset(dstOffset.x.roundToInt(), dstOffset.y.roundToInt()),
    dstSize = IntSize(dstSize.width.roundToInt(), dstSize.height.roundToInt()),
    alpha = alpha,
    colorFilter = filter,
    filterQuality = FilterQuality.High,
)

/**
 * The body drawn twice: [pass] once grey and faded, then once in full colour through [mask]. With no
 * mask there is nothing lit and the grey body stands alone.
 */
internal fun DrawScope.drawLitPlate(grey: ColorFilter, mask: Path?, pass: (ColorFilter?, Float) -> Unit) {
    pass(grey, MUSCLE_IDLE_ALPHA)
    if (mask == null) return
    clipPath(mask) { pass(null, 1f) }
}

/**
 * One figure, drawn twice.
 *
 * The first pass is the whole plate with its colour drained and its presence lowered; the second is
 * the same plate at full strength, clipped to the muscles this exercise works. Nothing is tinted
 * and nothing is painted over: what is lit is the photograph itself, which is the only way the
 * highlight can be more informative than a blue shape would be.
 *
 * When the exercise names exactly one group, the frame closes in on it — the same picture, cropped
 * so the muscle in question arrives large enough to look at rather than as a patch a centimetre
 * across. A group with nothing in this view leaves the view alone, whole and grey.
 */
@Composable
private fun BodyView(
    muscles: Set<Muscle>,
    sex: String,
    focus: Muscle?,
    modifier: Modifier,
    isFront: Boolean,
) {
    val plate = ImageBitmap.imageResource(bodyPlateRes(sex, isFront))
    val patches = if (isFront) BODY_FRONT else BODY_BACK
    val grey = remember { bodyPlateGrey() }
    // The pills below name the worked groups, but not which of the two figures carries them, and a
    // muscle that has no patch on this side of the body is not lit here at all. So the plate is
    // described from what it actually lights: this side, and the groups showing on it.
    val side = stringResource(if (isFront) R.string.muscle_map_front else R.string.muscle_map_back)
    val shown = Muscle.entries
        .filter { muscle -> muscle in muscles && patches.any { it.group == muscle } }
        .map { stringResource(it.labelRes()) }
    val comma = stringResource(R.string.list_comma)
    val description = if (shown.isEmpty()) side else "$side · ${shown.joinToString(comma)}"
    Canvas(modifier.fillMaxHeight().semantics { contentDescription = description }) {
        val imageWidth = plate.width.toFloat()
        val imageHeight = plate.height.toFloat()
        val lit = patches.filter { it.group in muscles }
        val source = focusRect(
            patches.filter { it.group == focus },
            imageWidth,
            imageHeight,
            size.width / size.height,
        ) ?: Rect(0f, 0f, imageWidth, imageHeight)
        val fit = PlateFit(source, imageWidth, imageHeight, size)
        val mask = lit.takeIf { it.isNotEmpty() }?.let {
            Path().apply {
                lit.forEach { patch ->
                    addPath(polygon(patch.points.map(fit::place)))
                    if (patch.mirrored) {
                        addPath(polygon(patch.points.map { fit.place(Offset(1f - it.x, it.y)) }))
                    }
                }
            }
        }
        drawLitPlate(grey, mask) { filter, alpha ->
            drawPlate(plate, source.topLeft, Size(source.width, source.height), fit.origin, fit.drawn, filter, alpha)
        }
    }
}

/** A closed straight-edged mask through its own corners. No smoothing: these are regions, not organs. */
internal fun polygon(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/**
 * The crop that puts one muscle group in the middle of the frame, in image pixels, or null when the
 * group has nothing in this view and the whole plate should stand.
 *
 * Grown to the frame's own shape before it is used, so the zoom fills the panel instead of leaving
 * the muscle floating in letterboxing, and clamped so it never asks for pixels off the plate.
 */
private fun focusRect(
    patches: List<MusclePatch>,
    imageWidth: Float,
    imageHeight: Float,
    aspect: Float,
): Rect? {
    if (patches.isEmpty()) return null
    val points = patches.flatMap { patch ->
        patch.points + if (patch.mirrored) patch.points.map { Offset(1f - it.x, it.y) } else emptyList()
    }
    val left = points.minOf { it.x } * imageWidth
    val right = points.maxOf { it.x } * imageWidth
    val top = points.minOf { it.y } * imageHeight
    val bottom = points.maxOf { it.y } * imageHeight
    val centre = Offset((left + right) / 2f, (top + bottom) / 2f)
    var halfWidth = (right - left) / 2f * (1f + MUSCLE_FOCUS_MARGIN)
    var halfHeight = (bottom - top) / 2f * (1f + MUSCLE_FOCUS_MARGIN)
    if (halfWidth / halfHeight > aspect) halfHeight = halfWidth / aspect else halfWidth = halfHeight * aspect
    // Slid back onto the plate rather than shrunk: a crop that loses its margin at the edge of the
    // picture should still show the muscle at the size every other crop shows one.
    halfWidth = minOf(halfWidth, imageWidth / 2f)
    halfHeight = minOf(halfHeight, imageHeight / 2f)
    val x = centre.x.coerceIn(halfWidth, imageWidth - halfWidth)
    val y = centre.y.coerceIn(halfHeight, imageHeight - halfHeight)
    return Rect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
}

/**
 * The picker row: thumbnail, name and what it works.
 *
 * The drawings carry their own colour, so the one already chosen cannot be marked by
 * tinting its glyph — it is marked the way a chosen row usually is instead, by the row itself
 * standing on the accent's own ground with its name in the accent, and by a tick at its end. The
 * ground is one tonal step off the window it sits in and the name's accent is a change of hue, so
 * the tick is the mark that can be seen without telling colours apart.
 */
@Composable
internal fun ActivityRow(spec: ActivitySpec, selected: Boolean, onClick: () -> Unit) {
    Row(
        // The ground and the accent-coloured name are silent to a screen reader, which would announce
        // a chosen row exactly as an unchosen one; selectable is what says it is chosen.
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PwnCorners.large))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(themePainterResource(spec.art()), contentDescription = null, modifier = Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                stringResource(spec.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) PwnIndigo else MaterialTheme.colorScheme.onSurface,
            )
            // joinToString takes a plain function, which cannot call stringResource; map is inline.
            val worked = Muscle.entries.filter { it in spec.muscles }.map { stringResource(it.labelRes()) }
            Text(
                worked.joinToString(" · ").ifBlank { "${spec.met} MET" },
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
        if (selected) {
            // Silent: selectable already says the row is chosen.
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
