package com.pwnall.health

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

enum class ActivityInput {
    DURATION,
    WEIGHTED_SETS,
}

/**
 * The muscle groups an exercise works, at the resolution a person picking an exercise cares about.
 *
 * Coarse on purpose: "arms" rather than biceps and triceps separately, because the app does not know
 * which grip was used and a finer claim would be invented rather than recorded.
 */
enum class Muscle {
    CHEST,
    BACK,
    SHOULDERS,
    ARMS,
    CORE,
    GLUTES,
    LEGS,
}

/**
 * One row of the catalogue. [bodyWeight] marks a move whose load is the body itself — push-ups,
 * lunges, pull-ups, burpees: it is [ActivityInput.WEIGHTED_SETS] for the vest, the belt or the
 * dumbbells that can go on top, but the kilograms may stay at nought, and what is put on top costs
 * energy by [loadedMet].
 */
data class ActivitySpec(
    val code: String,
    val titleRes: Int,
    val met: Double,
    val input: ActivityInput,
    val muscles: Set<Muscle> = emptySet(),
    val bodyWeight: Boolean = false,
)

/** Pressing moves all work the same three, whether the load is a barbell or the floor. */
private val PUSH_MUSCLES = setOf(Muscle.CHEST, Muscle.ARMS, Muscle.SHOULDERS)

/**
 * MET values are from the 2024 Adult Compendium of Physical Activities (pacompendium.com) — the
 * general code where the catalogue names no intensity (stair climbing 17131, rope skipping 02068,
 * basketball 15055, tennis 15675), the vigorous calisthenics code 02020 for the jumping moves, the
 * punching bag for boxing (15110), the deadlift under 02052, the moderate interval code 02210 for
 * HIIT. They estimate population averages, not an individual's measured energy expenditure.
 *
 * Push-ups, pull-ups and dips are the moderate 02022 (3.8), the row that names push-ups and
 * pull-ups beside lunges: a session's minutes hold the rests between sets, and 02020's 7.5 is
 * vigorous work without them — at 6.5 METs over the resting one instead of 2.8, three sets of
 * fifteen push-ups would be costed at well over twice what the moderate row gives. Squats are that
 * row too, the body alone: 02052's 5.0 is the weighted squat, and a bar on the back is
 * [loadedMet]'s to add.
 */
val ACTIVITY_CATALOG = listOf(
    ActivitySpec("PLANK", R.string.activity_plank, 2.8, ActivityInput.DURATION, setOf(Muscle.CORE, Muscle.SHOULDERS)),
    ActivitySpec(
        "TRICEPS_BODY",
        R.string.activity_triceps_body,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.ARMS, Muscle.CHEST),
        bodyWeight = true,
    ),
    ActivitySpec(
        "TRICEPS_DUMBBELL",
        R.string.activity_triceps_dumbbell,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.ARMS),
    ),
    ActivitySpec(
        "STRENGTH",
        R.string.activity_strength,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CHEST, Muscle.BACK, Muscle.ARMS, Muscle.LEGS),
    ),
    ActivitySpec(
        "CALISTHENICS",
        R.string.activity_calisthenics,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CHEST, Muscle.ARMS, Muscle.CORE, Muscle.LEGS),
        bodyWeight = true,
    ),
    ActivitySpec(
        "PUSHUPS",
        R.string.activity_pushups,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        PUSH_MUSCLES,
        bodyWeight = true,
    ),
    ActivitySpec("DIPS", R.string.activity_dips, 3.8, ActivityInput.WEIGHTED_SETS, PUSH_MUSCLES, bodyWeight = true),
    ActivitySpec(
        "PULLUPS",
        R.string.activity_pullups,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.ARMS),
        bodyWeight = true,
    ),
    ActivitySpec("ABS", R.string.activity_abs, 2.8, ActivityInput.WEIGHTED_SETS, setOf(Muscle.CORE), bodyWeight = true),
    // A crunch with the legs pedalling, elbow to the opposite knee: 02024's crunches, as the floor crunch.
    ActivitySpec(
        "BICYCLE_CRUNCH",
        R.string.activity_bicycle_crunch,
        2.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CORE, Muscle.LEGS),
        bodyWeight = true,
    ),
    // Held up on the bars as for dips, the legs raised: the abdomen and the hip flexors lift them. Harder
    // than a crunch on the floor, so the moderate 02022, the row that names sit-ups, not 02024's light one.
    ActivitySpec(
        "BAR_LEG_RAISE",
        R.string.activity_bar_leg_raise,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CORE, Muscle.LEGS),
        bodyWeight = true,
    ),
    ActivitySpec(
        "SQUATS",
        R.string.activity_squats,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS, Muscle.GLUTES, Muscle.CORE),
        bodyWeight = true,
    ),
    ActivitySpec(
        "LUNGES",
        R.string.activity_lunges,
        3.8,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS, Muscle.GLUTES),
        bodyWeight = true,
    ),
    ActivitySpec(
        "JUMPING_JACKS",
        R.string.activity_jumping_jacks,
        7.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS, Muscle.SHOULDERS, Muscle.CORE),
        bodyWeight = true,
    ),
    ActivitySpec(
        "BURPEES",
        R.string.activity_burpees,
        7.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CHEST, Muscle.ARMS, Muscle.CORE, Muscle.LEGS),
        bodyWeight = true,
    ),
    ActivitySpec("BENCH_PRESS", R.string.activity_bench_press, 5.0, ActivityInput.WEIGHTED_SETS, PUSH_MUSCLES),
    ActivitySpec(
        "OVERHEAD_PRESS",
        R.string.activity_overhead_press,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.SHOULDERS, Muscle.ARMS, Muscle.CORE),
    ),
    ActivitySpec(
        "BARBELL_ROW",
        R.string.activity_barbell_row,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.ARMS),
    ),
    ActivitySpec(
        "DEADLIFT",
        R.string.activity_deadlift,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.LEGS, Muscle.GLUTES, Muscle.CORE),
    ),
    ActivitySpec("BICEPS_CURL", R.string.activity_biceps_curl, 3.5, ActivityInput.WEIGHTED_SETS, setOf(Muscle.ARMS)),

    // The machines a commercial gym has a row of, which is where most weighted work actually
    // happens. The back extension is loaded by the body unless a plate is held; the MET values are
    // the compendium's 5.0 for a compound machine and 3.5 for an isolation one, which is the same
    // split the barbell rows above use. Each machine that works one way has the one that works back
    // beside it — the pec deck its reverse for the rear shoulders, abduction its adduction for the
    // inner thighs — since those are other muscles, not the same ones from the other side.
    ActivitySpec(
        "LAT_PULLDOWN",
        R.string.activity_lat_pulldown,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.ARMS),
    ),
    ActivitySpec(
        "LAT_PULLDOWN_CLOSE",
        R.string.activity_lat_pulldown_close,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.ARMS),
    ),
    ActivitySpec(
        "SEATED_ROW",
        R.string.activity_seated_row,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.ARMS),
    ),
    ActivitySpec(
        "TRICEPS_PUSHDOWN",
        R.string.activity_triceps_pushdown,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.ARMS),
    ),
    ActivitySpec("CHEST_PRESS", R.string.activity_chest_press, 5.0, ActivityInput.WEIGHTED_SETS, PUSH_MUSCLES),
    ActivitySpec(
        "PEC_DECK",
        R.string.activity_pec_deck,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CHEST, Muscle.SHOULDERS),
    ),
    ActivitySpec(
        "REVERSE_FLY",
        R.string.activity_reverse_fly,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.SHOULDERS, Muscle.BACK),
    ),
    ActivitySpec(
        "CABLE_CROSSOVER",
        R.string.activity_cable_crossover,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.CHEST, Muscle.SHOULDERS),
    ),
    ActivitySpec(
        "SHOULDER_PRESS_MACHINE",
        R.string.activity_shoulder_press_machine,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.SHOULDERS, Muscle.ARMS),
    ),
    ActivitySpec(
        "LATERAL_RAISE",
        R.string.activity_lateral_raise,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.SHOULDERS),
    ),
    ActivitySpec(
        "LEG_PRESS",
        R.string.activity_leg_press,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS, Muscle.GLUTES),
    ),
    ActivitySpec(
        "LEG_EXTENSION",
        R.string.activity_leg_extension,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS),
    ),
    ActivitySpec("LEG_CURL", R.string.activity_leg_curl, 3.5, ActivityInput.WEIGHTED_SETS, setOf(Muscle.LEGS)),
    ActivitySpec("CALF_RAISE", R.string.activity_calf_raise, 3.5, ActivityInput.WEIGHTED_SETS, setOf(Muscle.LEGS)),
    ActivitySpec(
        "HIP_THRUST",
        R.string.activity_hip_thrust,
        5.0,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.GLUTES, Muscle.LEGS),
    ),
    ActivitySpec(
        "HIP_ABDUCTION",
        R.string.activity_hip_abduction,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.GLUTES, Muscle.LEGS),
    ),
    ActivitySpec(
        "HIP_ADDUCTION",
        R.string.activity_hip_adduction,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.LEGS),
    ),
    ActivitySpec(
        "BACK_EXTENSION",
        R.string.activity_back_extension,
        3.5,
        ActivityInput.WEIGHTED_SETS,
        setOf(Muscle.BACK, Muscle.GLUTES),
        bodyWeight = true,
    ),
    ActivitySpec("WALK", R.string.activity_walk, 3.8, ActivityInput.DURATION, setOf(Muscle.LEGS)),
    ActivitySpec("RUN", R.string.activity_run, 7.5, ActivityInput.DURATION, setOf(Muscle.LEGS, Muscle.CORE)),
    ActivitySpec(
        "HIKING",
        R.string.activity_hiking,
        6.0,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.GLUTES, Muscle.CORE),
    ),
    ActivitySpec("STAIRS", R.string.activity_stairs, 6.8, ActivityInput.DURATION, setOf(Muscle.LEGS, Muscle.GLUTES)),
    ActivitySpec("CYCLING", R.string.activity_cycling, 7.0, ActivityInput.DURATION, setOf(Muscle.LEGS, Muscle.GLUTES)),
    ActivitySpec(
        "ELLIPTICAL",
        R.string.activity_elliptical,
        5.0,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.ARMS),
    ),
    ActivitySpec(
        "ROWING_MACHINE",
        R.string.activity_rowing_machine,
        7.3,
        ActivityInput.DURATION,
        setOf(Muscle.BACK, Muscle.LEGS, Muscle.ARMS),
    ),
    ActivitySpec(
        "SWIMMING",
        R.string.activity_swimming,
        5.8,
        ActivityInput.DURATION,
        setOf(Muscle.BACK, Muscle.SHOULDERS, Muscle.CORE, Muscle.LEGS),
    ),
    ActivitySpec(
        "JUMP_ROPE",
        R.string.activity_jump_rope,
        11.0,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.SHOULDERS, Muscle.CORE),
    ),
    ActivitySpec(
        "HIIT",
        R.string.activity_hiit,
        7.0,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.CORE, Muscle.ARMS),
    ),
    ActivitySpec(
        "BOXING",
        R.string.activity_boxing,
        5.8,
        ActivityInput.DURATION,
        setOf(Muscle.ARMS, Muscle.SHOULDERS, Muscle.CORE),
    ),
    ActivitySpec("DANCING", R.string.activity_dancing, 5.0, ActivityInput.DURATION, setOf(Muscle.LEGS, Muscle.CORE)),
    ActivitySpec(
        "BASKETBALL",
        R.string.activity_basketball,
        7.5,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.CORE),
    ),
    ActivitySpec("FOOTBALL", R.string.activity_football, 7.0, ActivityInput.DURATION, setOf(Muscle.LEGS, Muscle.CORE)),
    ActivitySpec(
        "TENNIS",
        R.string.activity_tennis,
        6.8,
        ActivityInput.DURATION,
        setOf(Muscle.ARMS, Muscle.LEGS, Muscle.CORE),
    ),
    ActivitySpec(
        "PILATES",
        R.string.activity_pilates,
        2.8,
        ActivityInput.DURATION,
        setOf(Muscle.CORE, Muscle.BACK, Muscle.GLUTES),
    ),
    ActivitySpec(
        "YOGA",
        R.string.activity_yoga,
        2.3,
        ActivityInput.DURATION,
        setOf(Muscle.CORE, Muscle.BACK, Muscle.LEGS),
    ),
    ActivitySpec(
        "STRETCHING",
        R.string.activity_stretching,
        2.3,
        ActivityInput.DURATION,
        setOf(Muscle.LEGS, Muscle.BACK, Muscle.CORE),
    ),
)

/**
 * "Strength training" and "Body-weight exercises" name no exercise. An import, Health Connect or an
 * old row may still say no more than that, so the catalogue keeps them for those; nothing offers
 * them to be picked, and the chat does not guess them.
 */
private val CATCH_ALL_CODES = setOf("STRENGTH", "CALISTHENICS")

/** The catalogue as it is offered to pick an exercise from. */
val PICKABLE_ACTIVITIES = ACTIVITY_CATALOG.filterNot { it.code in CATCH_ALL_CODES }

/** 1 MET ≈ 1 kcal per kg per hour — the cost of merely existing, which is why it comes back out. */
private const val RESTING_MET = 1.0

/**
 * Walking stride as a fraction of height. The standard offline estimate when there is no GPS to
 * calibrate against; individual stride varies with speed, so treat it as ±10%.
 */
private const val STRIDE_PER_HEIGHT = 0.414

/**
 * Net cost of walking, kilocalories per kilogram per kilometre — the classic figure, and the one
 * that stays roughly constant across normal walking speeds because slower walking simply takes
 * proportionally longer.
 */
private const val WALKING_KCAL_PER_KG_KM = 0.5

/**
 * Energy the activity costs *on top of* resting metabolism: (MET − 1) × the person's resting energy
 * per hour × hours.
 *
 * Compendium MET values are gross: one MET is the resting metabolic rate itself, so MET × rest × h
 * includes the calories the body would have burned lying still. Showing that number as "activity"
 * next to a separate resting figure counts the same hour of rest twice. The Compendium's own guidance
 * is to subtract one MET when resting energy is accounted separately.
 *
 * [restingKcalPerHour] is the person's own ([restingKcalPerHour] of the profile) rather than the
 * textbook 1 kcal per kilogram an hour: the day's resting figure is already Mifflin–St Jeor on the
 * profile's weight, height, age and sex, and the one MET taken off here has to be that same rest, or
 * the two figures overlap. The textbook rate also overstates most adults' rest, the more so the older
 * and heavier they are — an 80 kg man of 30 at 180 cm rests at about 74 kcal an hour, not 80.
 */
fun netActivityCalories(met: Double, restingKcalPerHour: Double, durationMinutes: Double): Double =
    max(0.0, met - RESTING_MET) * max(0.0, restingKcalPerHour) * max(0.0, durationMinutes) / 60.0

/**
 * At most half the body's weight again counts on top of it in [loadedMet]. Past that a set gets
 * shorter and the rests longer rather than each minute harder; at the cap a loaded squat costs
 * 1 + 2.8 × 1.5 = 5.2 METs, about the compendium's own weighted squat (02052, 5.0).
 */
private const val MAX_BODY_LOAD_FACTOR = 1.5

/**
 * The MET a [ActivitySpec.bodyWeight] move is costed at with [loadKg] on top of a [bodyKg] body.
 *
 * The compendium's figure is the body alone, and the work grows with the mass moved: 20 kg of vest
 * on an 80 kg body is a quarter more, so the part over the resting MET grows by
 * (body + load) / body, up to [MAX_BODY_LOAD_FACTOR]. The base is the catalogue's MET, not [met]:
 * a row stored with the weighted squat's MET (02052, 5.0) would otherwise count its bar twice on
 * recalculation. Every other move keeps [met] — a machine's or a bar's load is in its MET already —
 * and so does a move with no load, or a body of no known weight.
 *
 * Limitation: [loadKg] is a row's working weight, the mean of its loaded sets, so one set in a vest
 * among three without is costed as three in it; per-set costing would mean reading the payload at
 * every recalculation.
 */
fun loadedMet(code: String?, met: Double, bodyKg: Double, loadKg: Double?): Double {
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == code && it.bodyWeight }
    val load = loadKg ?: 0.0
    if (spec == null || load <= 0.0 || bodyKg <= 0.0) return met
    val factor = min((bodyKg + load) / bodyKg, MAX_BODY_LOAD_FACTOR)
    return RESTING_MET + max(0.0, spec.met - RESTING_MET) * factor
}

/**
 * The person's own one MET: resting energy an hour by Mifflin–St Jeor at [weightKg] — the weight in
 * force when the workout was done, for one logged before a later weigh-in — and the age in [year].
 * No weight, no estimate: the equation's height and age terms alone are not a body.
 */
fun UserProfile.restingKcalPerHour(weightKg: Double = this.weightKg, year: Int = LocalDate.now().year): Double =
    if (weightKg <= 0.0) 0.0 else max(0.0, restingCaloriesPerDay(copy(weightKg = weightKg), year)) / 24.0

/** Estimated walking distance for a step count, with no GPS involved. */
fun stepDistanceKm(steps: Long, heightCm: Int): Double =
    max(0L, steps) * strideLengthM(heightCm) / 1_000.0

fun strideLengthM(heightCm: Int): Double = max(0, heightCm) / 100.0 * STRIDE_PER_HEIGHT

/**
 * Net energy of a step count. Steps are the one activity this app measures by itself, so leaving
 * them out of the day's energy would make a 10,000-step day with nothing logged read as zero.
 */
fun stepCalories(steps: Long, weightKg: Double, heightCm: Int): Double =
    WALKING_KCAL_PER_KG_KM * max(0.0, weightKg) * stepDistanceKm(steps, heightCm)

/** Extra daily walking a losing goal asks for, on top of the age band's recommendation. */
const val LOSE_STEP_BONUS = 2_000L

/**
 * Daily step goal from public-health guidance rather than a flat 10,000: youth guidance sits around
 * twelve thousand, Tudor-Locke's adult band at seven to ten, and for people over sixty the same
 * health benefit arrives a couple of thousand steps earlier. Losing weight adds [LOSE_STEP_BONUS] —
 * the mode's whole movement ask lives here, and the burn goal below inherits it as those steps'
 * energy rather than adding a second bonus of its own. Gaining keeps the base: movement stays for
 * health, the surplus is food's job.
 *
 * The goal is the one the budget acts on ([coachedEnergyGoal]), so a minor's "lose weight" adds no
 * walking here for the same reason it takes no food away there.
 */
fun recommendedStepGoal(profile: UserProfile, currentYear: Int): Long {
    val age = (currentYear - profile.birthYear).coerceAtLeast(0)
    val base = when {
        age < 18 -> 12_000L
        age < 60 -> 8_000L
        else -> 7_000L
    }
    return base + if (coachedEnergyGoal(profile, currentYear) == "LOSE") LOSE_STEP_BONUS else 0L
}

/**
 * The profile onboarding hands over: the one it built, with the daily step goal set from the same
 * age band the burn goal already reads. Left to the constructor, every profile would start at 8,000
 * — a seventy-year-old would see that goal on the home screen while the burn goal under it was
 * derived from 7,000. Set once, here: a goal the person edits on the profile screen afterwards is
 * theirs, and nothing recomputes it.
 */
fun UserProfile.withRecommendedStepGoal(currentYear: Int): UserProfile =
    copy(dailyStepGoal = recommendedStepGoal(this, currentYear))

/**
 * The day's active-burn goal, kept in parts because the explanation dialog states each one: the
 * energy of walking the day's step goal (which already carries the losing mode's extra walking),
 * plus whatever was eaten beyond the day's food budget — overeating raises today's bar rather than
 * tomorrow's.
 */
data class BurnGoal(
    val goalSteps: Long,
    val baseKcal: Double,
    val overflowKcal: Double,
) {
    val totalKcal: Double get() = baseKcal + overflowKcal
}

/**
 * The profile's own [UserProfile.dailyStepGoal] rather than [recommendedStepGoal], because the two
 * goals stand side by side on the home screen: the steps card counts against the stored goal and
 * the calories card prices it, and a person who moves their step goal to fifteen thousand would
 * otherwise be shown a calorie goal still derived from the eight thousand the recommendation gives.
 * Onboarding seeds the stored goal from the recommendation ([withRecommendedStepGoal]), so the
 * losing mode's extra walking still arrives — through the goal the person can see. Nothing here
 * reads the age.
 */
fun burnGoal(profile: UserProfile, eatenKcal: Double, foodTargetKcal: Double): BurnGoal {
    val goalSteps = profile.dailyStepGoal.coerceAtLeast(0L)
    return BurnGoal(
        goalSteps = goalSteps,
        baseKcal = stepCalories(goalSteps, profile.weightKg, profile.heightCm),
        overflowKcal = max(0.0, eatenKcal - foodTargetKcal),
    )
}

/**
 * An imported Health Connect session whose steps the pedometer also counted. Keeps the
 * HEALTH_CONNECT prefix so every provenance check (adoption, export filter, detail import)
 * still recognises the row as an import.
 */
const val HEALTH_CONNECT_STEPS_CODE = "HEALTH_CONNECT_STEPS"

/** Workouts whose energy the pedometer already sees as steps. */
private val STEP_COUNTED_CODES = setOf("WALK", "RUN", "HIKING", "STAIRS", HEALTH_CONNECT_STEPS_CODE)

/**
 * Movement energy without paying for the same outing twice: a logged walk stores its MET estimate
 * while the pedometer counts the very steps it was made of, so the two claims compete and only the
 * larger one wins. Everything else — presses, swims, rides — adds on top.
 *
 * Limitation: a day-level max rather than hour-level overlap matching — conservative on the day
 * that has both a logged run and a lot of separate walking.
 */
fun movedCalories(activities: List<JournalEntry>, stepsKcal: Double): Double {
    val other = activities.filterNot { it.activityCode in STEP_COUNTED_CODES }
    return other.sumOf { it.calories } + walkingShareOfMoved(activities, stepsKcal)
}

/**
 * The part of [movedCalories] that the walking paid for.
 *
 * Not simply [stepCalories]: the pedometer's figure and a logged walk's own estimate compete there
 * and only the larger is counted, so on a day with a logged run the raw step figure is no part of
 * the total at all. A card that prints this under that total has to print the claim that won.
 */
fun walkingShareOfMoved(activities: List<JournalEntry>, stepsKcal: Double): Double {
    val stepCounted = activities.filter { it.activityCode in STEP_COUNTED_CODES }
    return max(max(0.0, stepsKcal), stepCounted.sumOf { it.calories })
}

/** Daily energy budget and the macronutrient split it implies. */
data class EnergyBudget(
    val targetKcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
)

/**
 * The app's mode, and the order the segmented control renders it in. MAINTAIN is "just tracking":
 * daily diary feedback is offered without weight-change coaching.
 */
val ENERGY_GOALS = listOf("MAINTAIN", "LOSE", "GAIN")

/** A deficit that loses roughly half a kilo a week; a surplus that gains slowly. */
private const val LOSE_KCAL = -500.0
private const val GAIN_KCAL = 300.0

/**
 * The lowest intake the losing mode is allowed to ask for — the figures public guidance puts under
 * an unsupervised diet. A small or older profile can have a sedentary maintenance below them, and
 * there the floor is maintenance itself: no deficit is coached rather than one raised above what
 * the body spends.
 */
private const val LOSE_FLOOR_MALE_KCAL = 1_500.0
private const val LOSE_FLOOR_FEMALE_KCAL = 1_200.0

/** The age from which a goal moves the budget; under it the day is tracked and nothing is coached. */
private const val COACHED_GOAL_AGE = 18

/**
 * Whether this profile's goal is acted on at all, which is a question about an adult.
 *
 * The resting figure under the budget is an adult equation's estimate, and a deficit of 500 kcal
 * set from it — on a floor published for adults dieting unsupervised — is not this app's to hand a
 * fourteen-year-old who picked "lose weight". The day's food advice answers a minor as
 * "just tracking", and the budget it compares her meals with must not be cut either.
 *
 * Limitation: the callers know the year and not the day, so the age is the one held on the 1st of
 * January — the goal starts with the year after the eighteenth birthday, up to twelve months late
 * and never a day early.
 *
 * A birth date nothing can read is zero years to [ageOn], so it is offered nothing either: the gate
 * closes when it cannot tell, as the others do.
 */
internal fun energyGoalOffered(birthDate: String, currentYear: Int): Boolean =
    ageOn("$currentYear-01-01", birthDate) >= COACHED_GOAL_AGE

/**
 * The goal the budget, the advice and the step recommendation act on: the profile's own where
 * [energyGoalOffered], or none. Nothing else reads the stored goal, so where this answers "just
 * tracking" a choice made on the profile screen would change nothing at all.
 */
internal fun coachedEnergyGoal(profile: UserProfile, currentYear: Int): String =
    profile.energyGoal.takeIf { it in ENERGY_GOALS && energyGoalOffered(profile.birthDate, currentYear) }
        ?: "MAINTAIN"

/** Protein per kilogram of body mass — the figure sports-nutrition guidance settles around. */
private const val PROTEIN_G_PER_KG = 1.6

/** Share of the budget from fat, the floor most guidance puts on it. */
private const val FAT_ENERGY_SHARE = 0.25

/**
 * Movement is measured, not guessed: steps and logged activities are added back in [remainingKcal].
 * The budget therefore has to be built on a *sedentary* base — an activity factor here would pay for
 * the same walk twice, once as an allowance and once as a credit.
 */
private const val SEDENTARY_MULTIPLIER = 1.2

/**
 * What the day is allowed to cost, and how to divide it.
 *
 * Sedentary maintenance, shifted by the goal — an adult's goal, see [coachedEnergyGoal] — and never
 * taken below the safe floor a deficit has. Protein is set per kilogram rather than as a percentage
 * — it is the macro with a real physiological requirement — fat takes a quarter of the energy, and
 * carbohydrate is whatever is left.
 */
fun energyBudget(profile: UserProfile, currentYear: Int): EnergyBudget {
    // Deliberately not profile.activityLevel: a restored backup can still carry HIGH, which would
    // bring back the double count this constant exists to remove.
    val maintenance = restingCaloriesPerDay(profile, currentYear) * SEDENTARY_MULTIPLIER
    val lowest = if (profile.sex == "MALE") LOSE_FLOOR_MALE_KCAL else LOSE_FLOOR_FEMALE_KCAL
    val target = max(
        0.0,
        when (coachedEnergyGoal(profile, currentYear)) {
            "LOSE" -> max(maintenance + LOSE_KCAL, min(lowest, maintenance))
            "GAIN" -> maintenance + GAIN_KCAL
            else -> maintenance
        },
    )
    val protein = max(0.0, profile.weightKg) * PROTEIN_G_PER_KG
    val fat = target * FAT_ENERGY_SHARE / 9.0
    val carbs = max(0.0, target - protein * 4.0 - fat * 9.0) / 4.0
    return EnergyBudget(target, protein, fat, carbs)
}

/**
 * What is left of the budget: eaten, minus what movement has already paid for.
 *
 * Activity energy here must be net (see [netActivityCalories]) — resting metabolism is already
 * inside the target, so adding a gross figure would hand out the same calories twice.
 */
fun remainingKcal(target: Double, eatenKcal: Double, netActiveKcal: Double): Double =
    target - eatenKcal + netActiveKcal

/**
 * Millilitres of drink a day: the 30 ml/kg rule of thumb, held inside what EFSA publishes.
 *
 * EFSA 2010 (Scientific Opinion on Dietary Reference Values for water) sets adequate intakes of
 * 2.0 L a day for women and 2.5 L for men as total water, of which about 80% comes from drinks —
 * 1.6 and 2.0 L. The per-kilogram rule has no body behind it, and left alone it would ask a 45 kg
 * woman for 1.35 L and a 90 kg man for 2.7 L of drinks: under the smallest published intake at one
 * end, over the largest whole day's water at the other. The rule stays, because the figure does
 * move with body size and EFSA gives no formula to replace it with; the clamp keeps it between the
 * drink share of the women's intake and the men's total.
 */
fun waterTargetMl(weightKg: Double): Int =
    (max(0.0, weightKg) * WATER_ML_PER_KG).toInt().coerceIn(WATER_TARGET_FLOOR_ML, WATER_TARGET_CEILING_ML)

private const val WATER_ML_PER_KG = 30.0
private const val WATER_TARGET_FLOOR_ML = 1_600
private const val WATER_TARGET_CEILING_ML = 2_500

/** Atwater-style 4/9/4 estimate from protein, fat and available carbohydrate. */
fun macroCalories(proteinG: Double, fatG: Double, carbsG: Double): Double =
    max(0.0, proteinG) * 4.0 + max(0.0, fatG) * 9.0 + max(0.0, carbsG) * 4.0

fun bmi(weightKg: Double, heightCm: Int): Double {
    val meters = heightCm / 100.0
    return if (meters > 0.0) weightKg / (meters * meters) else 0.0
}

/**
 * Mifflin–St Jeor resting-energy equation. Birth year only gives an approximate age until the app
 * asks for a full birth date, which it deliberately does not need.
 *
 * The age is held inside the same 0..120 [ageOn] and the lab advice clamp to. A restored backup can
 * carry no birth date at all; [UserProfile.birthYear] answers that with a year that stands for an
 * adult, and the clamp is a second line of defence, against a year that is absurd rather than
 * missing. Unclamped, a year of 0 would read two thousand years old, and the equation would come
 * back at about −8,200 kcal — which [energyBudget]'s floor would turn into a day's budget of
 * nothing, so every meal on the home screen would read as over budget.
 */
fun restingCaloriesPerDay(profile: UserProfile, currentYear: Int): Double {
    val age = (currentYear - profile.birthYear).coerceIn(0, 120)
    val sexOffset = if (profile.sex == "MALE") 5.0 else -161.0
    return 10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * age + sexOffset
}

fun standbyCaloriesSoFar(profile: UserProfile, now: LocalDateTime): Double {
    val elapsedMinutes = now.hour * 60 + now.minute + now.second / 60.0
    return restingCaloriesPerDay(profile, now.year) * elapsedMinutes / (24.0 * 60.0)
}

/**
 * The age WHO's BMI cut-points are written for. Under it a body mass index is a percentile of age
 * and sex — WHO's 5–19 growth reference, CDC's 2–20 charts — and neither table is held here: a
 * fourteen-year-old girl at 24.5 would be read "normal range" against a line the CDC puts at about
 * 23.3 for her age, and an eight-year-old boy at 17.5 "below range" against 18.5. Shared with the
 * liver advice, which is the other place a BMI is compared to 25.
 */
const val ADULT_BMI_AGE = 18

/**
 * What the profile card says under the figure: WHO's adult bands, or no verdict at all under
 * [ADULT_BMI_AGE] — the number is still shown, the way the vitals card shows a child's pressure
 * without grading it. A birth date nothing can parse is told apart from a young one, because "not
 * applied under 18" about an age nobody knows is a statement about nobody.
 */
fun bmiLabelRes(value: Double, birthDate: String, today: LocalDate): Int = when {
    value <= 0.0 -> R.string.bmi_no_data
    !birthDateKnown(birthDate) -> R.string.vitals_no_verdict_birthdate
    ageOn(today.toString(), birthDate) < ADULT_BMI_AGE -> R.string.vitals_no_verdict_age
    value < 18.5 -> R.string.bmi_under
    value < 25.0 -> R.string.bmi_normal
    value < 30.0 -> R.string.bmi_over
    else -> R.string.bmi_high
}

/** The middle value, or the mean of the two middle ones; null of nothing. */
internal fun Collection<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/**
 * Applies weight measurements in chronological order and recalculates MET activity estimates.
 * Removing or editing a measurement and calling this function again deterministically rebuilds
 * every dependent value. The [profile] gives the rest of the resting rate — height, sex, birth
 * date — and each workout is costed at the age it was done at, so a rebuild next year moves nothing.
 */
fun recalculatedActivityTimeline(
    entries: List<JournalEntry>,
    baselineWeightKg: Double,
    profile: UserProfile,
): List<JournalEntry> {
    var weightKg = baselineWeightKg
    return entries.sortedWith(compareBy(JournalEntry::occurredAt, JournalEntry::id)).map { entry ->
        when (entry.kind) {
            KIND_MEASUREMENT -> {
                entry.measurementWeightKg?.takeIf { it in PLAUSIBLE_KG }?.let { weightKg = it }
                entry
            }

            KIND_ACTIVITY -> {
                val met = entry.met
                val minutes = entry.durationMinutes
                // An ergometer reported its own energy; the MET it is stored with is a label for the
                // kind of session, not an estimate that may overwrite a measurement. A console's count
                // taken into a catalogue exercise — a run, a ride — is such a measurement too, and
                // only its frame says so ([hasMachineEnergy]).
                val measured = entry.activityCode in DIRECT_LIVE_ACTIVITY_CODES || entry.hasMachineEnergy()
                if (met == null || minutes == null || measured) {
                    entry
                } else {
                    val year = entry.day.take(4).toIntOrNull() ?: LocalDate.now().year
                    entry.copy(
                        calories = netActivityCalories(
                            loadedMet(entry.activityCode, met, weightKg, entry.loadKg),
                            profile.restingKcalPerHour(weightKg, year),
                            minutes,
                        ),
                    )
                }
            }

            else -> entry
        }
    }
}
