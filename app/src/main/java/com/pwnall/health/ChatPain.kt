package com.pwnall.health

import android.content.res.Resources
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// The pain thread: the pain page in conversation — the same steps in the same order, answered the
// same way, the same "same pain?" lookup and the same build of the one KIND_PAIN row the page would
// write — with three things in front of the page's own steps: where it hurts, whether it started
// with an injury, and the red flags of the body family it is in, the call-to-999 list of the NHS
// pages first. A chest marked on the map hands over to the chest's own questions before anything is
// recorded for it, and the pain is taken up again after them. Everything here is pure: the thread's
// questions, lines and record are worked out from its answers, so it reads the same each time.

/**
 * The parts of the body the red flags are grouped by, each with the NHS pages its list follows
 * ([sourceRes]), whether an injury is asked about there ([injuryAsked]: the pages of the head, the
 * jaw, the chest and the belly are not about injuries), and after how many days a pain still there is
 * worth a doctor's look ([weeksDays]; none where its pages give no such line).
 */
internal enum class PainFamily(val injuryAsked: Boolean, val sourceRes: Int, val weeksDays: Int?) {
    HEAD(false, R.string.pain_source_head, null),
    JAW(false, R.string.pain_source_jaw, null),
    NECK(true, R.string.pain_source_neck, 21),
    SHOULDER(true, R.string.pain_source_shoulder, 14),
    CHEST(false, R.string.symptom_source_chest_pain, null),
    BACK(true, R.string.pain_source_back, 21),
    BELLY(false, R.string.pain_source_belly, null),
    ARM(true, R.string.pain_source_arm, 21),
    HAND(true, R.string.pain_source_hand, 14),
    HIP(true, R.string.pain_source_hip, 14),
    KNEE(true, R.string.pain_source_knee, 21),
    LEG(true, R.string.pain_source_leg, null),
    FOOT(true, R.string.pain_source_foot, 14),
}

/**
 * The families of [region], from the one table by name ([bodyFamilies]): a later map's area needs a
 * row there, or takes its root's. The back of the knee is the knee's and the leg's.
 */
internal fun painFamilies(region: PainRegion): Set<PainFamily> =
    bodyFamilies(region).mapTo(LinkedHashSet()) { PainFamily.valueOf(it) }

/** The families of every area of [areas], each once, in the families' own order. */
internal fun painFamilies(areas: Collection<RegionSide>): List<PainFamily> =
    areas.flatMap { painFamilies(it.region) }.distinct().sortedBy { it.ordinal }

/** The areas a pain thread recorded: its area answer's codes, those this build can read. */
internal fun painThreadMarks(report: SymptomReport?): List<RegionSide> =
    report?.values?.get(SymptomStep.PAIN_AREA).orEmpty().split(',').mapNotNull { RegionSide.parse(it.trim()) }
        .distinct().sortedWith(REGION_SIDE_ORDER)

/** The lower back the kidney pages speak of: the lumbar strip and the loins. */
private val LOWER_BACK_AREAS = setOf("LOWER_BACK", "FLANK")

/** Where the heel pages' tiptoe, stairs and calf-and-ankle items are asked without an injury. */
private val HEEL_AREAS = setOf("HEEL", "ACHILLES", "ANKLE", "ANKLE_FOOT")

/** The joints of [family] by the map's own flag: the elbows of the arm, the ankles and the toes of the foot. */
private fun jointsOf(family: PainFamily): Set<String> =
    PainRegion.entries.filter { it.joint && family in painFamilies(it) }.mapTo(HashSet()) { it.name }

private val ELBOW_JOINTS: Set<String> = jointsOf(PainFamily.ARM)
private val FOOT_JOINTS: Set<String> = jointsOf(PainFamily.FOOT)

/**
 * How urgent a red flag is, and the line it brings: call emergency services ([E]); be seen at an
 * emergency department now ([I], which stops the chat as 999 does); get advice now from an urgent
 * helpline ([N]); get advice today ([D]); see a GP ([G]).
 */
internal enum class SignLevel(val alert: SymptomAlert) {
    E(SymptomAlert.EMERGENCY),
    I(SymptomAlert.IN_PERSON_NOW),
    N(SymptomAlert.URGENT_NOW),
    D(SymptomAlert.URGENT),
    G(SymptomAlert.DOCTOR),
}

/** Whether a red flag is asked always, only after an injury, or only when it did not start with one. */
internal enum class SignWhen { ALWAYS, INJURY, NO_INJURY }

/**
 * One red flag of a family's list: the [option] ticked, its [level], whether an injury decides it
 * ([whenInjury]), its words there ([label]: a family may put an item in its own page's words), and
 * whether it is asked only of a woman ([female]), of someone not yet an adult ([child]), or only
 * when one of [areas] is marked (region names, with ":side" where a side matters; none: anywhere in
 * the family).
 */
internal data class PainSign(
    val option: SymptomOption,
    val level: SignLevel,
    val whenInjury: SignWhen = SignWhen.ALWAYS,
    val label: Int = option.labelRes,
    val female: Boolean = false,
    val child: Boolean = false,
    val areas: Set<String> = emptySet(),
)

private val E = SignLevel.E
private val I = SignLevel.I
private val N = SignLevel.N
private val D = SignLevel.D
private val G = SignLevel.G
private val INJURY = SignWhen.INJURY
private val NO_INJURY = SignWhen.NO_INJURY

// The lists, family by family, in the order the pages give them and in their words. One more
// urgent level of one option in two families wins when the lists are put together ([painSignLists]).

private val HEAD_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.SEIZURE, E),
    PainSign(SymptomOption.NUMB_WEAK, E),
    PainSign(SymptomOption.SPEAK_BALANCE, E),
    PainSign(SymptomOption.DROWSY_CONFUSED, E),
    PainSign(SymptomOption.VISION_LOSS, E),
    PainSign(SymptomOption.HEAD_INJURY, E),
    PainSign(SymptomOption.NON_BLANCHING_RASH, E),
    PainSign(SymptomOption.FEVER_STIFF_LIGHT, E),
    PainSign(SymptomOption.EYE_PROBLEMS, D),
    PainSign(SymptomOption.WORSE_STRAIN, D),
    PainSign(SymptomOption.VOMITING, D),
    PainSign(SymptomOption.JAW_SCALP, D),
    PainSign(SymptomOption.CHILD_WORSE, D, child = true),
)

private val JAW_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CHEST_TOO, E),
)

private val NECK_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.HIGH_TEMP, E),
    PainSign(SymptomOption.SEVERE_HEADACHE, E),
    PainSign(SymptomOption.CONFUSED, E, label = R.string.pain_chat_sign_confused_neck),
    PainSign(SymptomOption.LIGHT_HURTS, E),
    PainSign(SymptomOption.NON_BLANCHING_RASH, E),
    PainSign(SymptomOption.SEIZURE, E),
    PainSign(SymptomOption.CHEST_TOO, E),
    PainSign(SymptomOption.TINGLE_NUMB, G, NO_INJURY, R.string.pain_chat_sign_tingle_numb_neck),
    PainSign(SymptomOption.TINGLE_NUMB, D, INJURY, R.string.pain_chat_sign_tingle_numb_neck_inj),
    PainSign(SymptomOption.WALK_SIT, D, INJURY),
    PainSign(SymptomOption.ELECTRIC, D, INJURY),
    PainSign(SymptomOption.WEAK_LIMBS, D, INJURY),
    PainSign(SymptomOption.SEVERE_DESPITE, D, INJURY),
)

private val SHOULDER_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CHEST_TOO, E),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.SUDDEN_OR_BAD, D),
    PainSign(SymptomOption.CANT_MOVE, D, label = R.string.pain_chat_sign_cant_move_shoulder),
    PainSign(SymptomOption.ODD_SHAPE, D, NO_INJURY, R.string.pain_chat_sign_odd_shape_shoulder),
    PainSign(SymptomOption.TINGLE_NUMB, D, NO_INJURY, R.string.pain_chat_sign_tingle_numb_shoulder),
    PainSign(SymptomOption.SWELL_BRUISE, D, INJURY),
    PainSign(SymptomOption.HOT_OR_COLD, D),
    PainSign(SymptomOption.FEVERISH, D, label = R.string.pain_chat_sign_feverish_shoulder),
    PainSign(SymptomOption.SKIN_COLOUR, D, NO_INJURY),
)

private val BACK_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.BOTH_LEGS, E),
    PainSign(SymptomOption.SADDLE, E),
    PainSign(SymptomOption.BLADDER_BOWEL, E),
    PainSign(SymptomOption.SEX_CHANGES, E),
    PainSign(SymptomOption.CHEST_TOO, E, label = R.string.pain_chat_sign_chest_too_back_belly),
    PainSign(SymptomOption.BREATHING_HARD, E),
    PainSign(SymptomOption.BLUE_SKIN, E),
    PainSign(SymptomOption.PASSED_OUT, E),
    PainSign(SymptomOption.SERIOUS_ACCIDENT, E, INJURY),
    PainSign(
        SymptomOption.CONFUSED,
        E,
        label = R.string.pain_chat_sign_confused_lower_back,
        areas = LOWER_BACK_AREAS,
    ),
    PainSign(SymptomOption.FEVERISH, D, label = R.string.pain_chat_sign_feverish_back),
    PainSign(SymptomOption.WORSE_FAST, D),
    PainSign(SymptomOption.VERY_PAINFUL, D, areas = LOWER_BACK_AREAS),
    PainSign(SymptomOption.PEE_BLOOD, D, areas = LOWER_BACK_AREAS),
    PainSign(SymptomOption.SICK, D, areas = LOWER_BACK_AREAS),
    PainSign(SymptomOption.VERY_LOW_TEMP, D, areas = LOWER_BACK_AREAS),
    PainSign(
        SymptomOption.PREGNANCY,
        D,
        label = R.string.pain_chat_sign_pregnancy,
        female = true,
        areas = LOWER_BACK_AREAS,
    ),
    PainSign(SymptomOption.WEIGHT_LOSS, G),
    PainSign(SymptomOption.LUMP_SHAPE, G),
    PainSign(SymptomOption.NIGHT_REST, G),
    PainSign(SymptomOption.COUGH_SNEEZE_POO, G),
)

private val BELLY_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_OR_SEVERE, E),
    PainSign(SymptomOption.TENDER_TUMMY, E),
    PainSign(SymptomOption.WORSE_OR_MOVING, E),
    PainSign(SymptomOption.VOMIT_BLOOD, E),
    PainSign(SymptomOption.BLACK_POO, E),
    PainSign(SymptomOption.CANT_PEE_POO, E),
    PainSign(SymptomOption.CHEST_TOO, E, label = R.string.pain_chat_sign_chest_too_back_belly),
    PainSign(SymptomOption.BREATHING_HARD, E),
    PainSign(SymptomOption.DIZZY_FAINT, E, label = R.string.pain_chat_sign_dizzy_faint_belly),
    PainSign(SymptomOption.SHOULDER_TIP, E),
    PainSign(SymptomOption.YELLOW, E),
    PainSign(SymptomOption.SPREAD_BACK_SICK, E),
    PainSign(SymptomOption.DIABETES_SICK, E),
    PainSign(SymptomOption.FEVERISH, E, label = R.string.pain_chat_sign_feverish_belly),
    PainSign(SymptomOption.CONFUSED_PALE, E),
    PainSign(SymptomOption.HEAVY_BLEEDING, E, female = true),
    PainSign(SymptomOption.KEEPS_COMING, D),
    PainSign(SymptomOption.BLOATING, D),
    PainSign(SymptomOption.SWALLOWING, D),
    PainSign(SymptomOption.WEIGHT_LOSS, D),
    PainSign(SymptomOption.PEE_CHANGE, D),
    PainSign(SymptomOption.BOTTOM_BLEED, D),
    PainSign(SymptomOption.HARD_TO_PEE_POO, D),
    PainSign(SymptomOption.SICK_DIARRHOEA, D),
    PainSign(SymptomOption.DIARRHOEA_DAYS, D),
    PainSign(SymptomOption.MOVED_RIGHT, D),
    PainSign(SymptomOption.PREGNANCY, D, label = R.string.pain_chat_sign_pregnancy, female = true),
    PainSign(SymptomOption.VAGINAL, D, female = true),
)

private val ARM_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CHEST_TOO, E),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.BONE_OUT, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.BREATHLESS, E, NO_INJURY, R.string.pain_chat_sign_breathless_leg),
    PainSign(SymptomOption.SEVERE_CANT_MOVE, I, NO_INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, I, NO_INJURY, R.string.pain_chat_sign_tingle_numb_arm),
    PainSign(SymptomOption.EXERCISE_EASES, D, NO_INJURY),
    PainSign(SymptomOption.CLOT_SIGNS, D, NO_INJURY, R.string.pain_chat_sign_clot_signs_arm),
    PainSign(SymptomOption.SWOLLEN_JOINT, D, NO_INJURY, areas = ELBOW_JOINTS),
    PainSign(SymptomOption.SKIN_COLOUR, D, NO_INJURY, areas = ELBOW_JOINTS),
    PainSign(SymptomOption.FEVERISH, D),
    PainSign(SymptomOption.VERY_PAINFUL, D, INJURY, R.string.pain_chat_sign_very_painful_inj),
    PainSign(SymptomOption.SWELL_BRUISE, D, INJURY),
    PainSign(SymptomOption.STIFF_HARD, D, INJURY),
)

private val HAND_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.BONE_OUT, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, D, NO_INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, D, INJURY, R.string.pain_chat_sign_very_painful_inj),
    PainSign(SymptomOption.FAINT_FROM_PAIN, D),
    PainSign(SymptomOption.FEVERISH, D),
    PainSign(SymptomOption.CANT_MOVE, D),
    PainSign(SymptomOption.HOT_RED_SWOLLEN, D, label = R.string.pain_chat_sign_hot_red_swollen_hand),
    PainSign(SymptomOption.SICK_CANT_EAT, D),
    PainSign(SymptomOption.ODD_SHAPE, D, NO_INJURY, R.string.pain_chat_sign_odd_shape_hand),
    PainSign(SymptomOption.TINGLE_NUMB, D, NO_INJURY, R.string.pain_chat_sign_tingle_numb_hand),
    PainSign(SymptomOption.SWELL_BRUISE, D, INJURY),
    PainSign(SymptomOption.STIFF_HARD, D, INJURY),
    PainSign(SymptomOption.HAS_DIABETES, G),
)

private val HIP_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.NO_WEIGHT, E, label = R.string.pain_chat_sign_no_weight_hip),
    PainSign(SymptomOption.SEVERE_SINCE_FALL, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY, R.string.pain_chat_sign_tingle_numb_hip_inj),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.HOT_RED_SWOLLEN, D, label = R.string.pain_chat_sign_hot_red_swollen_hip),
    PainSign(SymptomOption.SKIN_COLOUR, D),
    PainSign(SymptomOption.FEVERISH, D, label = R.string.pain_chat_sign_feverish_hip),
    PainSign(SymptomOption.SWOLLEN_JOINT, D, NO_INJURY),
)

private val KNEE_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.BONE_OUT, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.FAST_SWELL, I, INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, N, NO_INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, N, INJURY, R.string.pain_chat_sign_very_painful_inj),
    PainSign(SymptomOption.NO_WEIGHT, N, label = R.string.pain_chat_sign_no_weight_knee),
    PainSign(SymptomOption.ODD_SHAPE, N, NO_INJURY, R.string.pain_chat_sign_odd_shape_knee),
    PainSign(SymptomOption.LOCKS_GIVES, N),
    PainSign(SymptomOption.FEVERISH, N),
    PainSign(SymptomOption.SWELL_BRUISE, N, INJURY, R.string.pain_chat_sign_swell_bruise_knee_inj),
    PainSign(SymptomOption.KNEECAP_SLIPPED, D, INJURY),
    PainSign(SymptomOption.STIFF_HARD, D, INJURY),
    PainSign(SymptomOption.SKIN_COLOUR, D, NO_INJURY),
    PainSign(SymptomOption.SICK_CANT_EAT, D, NO_INJURY),
    PainSign(SymptomOption.JOINT_WORSE, D, NO_INJURY),
)

private val LEG_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.BREATHLESS, E, label = R.string.pain_chat_sign_breathless_leg),
    PainSign(SymptomOption.CHEST_TOO, E, label = R.string.pain_chat_sign_chest_too_leg),
    PainSign(SymptomOption.FAST_HEART, E),
    PainSign(SymptomOption.PASSED_OUT, E),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY, R.string.pain_chat_sign_odd_shape_leg_inj),
    PainSign(SymptomOption.BONE_OUT, E, INJURY),
    PainSign(SymptomOption.SHOCK, E, INJURY),
    PainSign(SymptomOption.BLUE_SKIN, E, INJURY),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.CLOT_SIGNS, D, NO_INJURY),
    PainSign(SymptomOption.LEG_SKIN, D, NO_INJURY),
    PainSign(SymptomOption.SUDDEN_WORSE_LEG, D, NO_INJURY),
    PainSign(SymptomOption.WARM_SWOLLEN, D, NO_INJURY),
    PainSign(SymptomOption.COUGHING_BLOOD, D),
    PainSign(SymptomOption.VERY_PAINFUL, D, INJURY, R.string.pain_chat_sign_very_painful_inj),
    PainSign(SymptomOption.SWELL_BRUISE, D, INJURY),
    PainSign(SymptomOption.STIFF_HARD, D, INJURY),
    PainSign(SymptomOption.NO_WEIGHT, D, INJURY, R.string.pain_chat_sign_no_weight_leg_inj),
)

private val FOOT_SIGNS = listOf(
    PainSign(SymptomOption.SUDDEN_SEVERE, E),
    PainSign(SymptomOption.CRACK, E, INJURY),
    PainSign(SymptomOption.ODD_SHAPE, E, INJURY),
    PainSign(SymptomOption.TINGLE_NUMB, E, INJURY),
    PainSign(SymptomOption.BLUE_COLD, E, INJURY),
    PainSign(SymptomOption.BONE_OUT, I, INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, I, INJURY),
    PainSign(SymptomOption.BIG_TOE, I, INJURY),
    PainSign(SymptomOption.VERY_PAINFUL, D, NO_INJURY),
    PainSign(SymptomOption.ODD_SHAPE, D, NO_INJURY, R.string.pain_chat_sign_odd_shape_foot),
    PainSign(SymptomOption.HOT_RED_SWOLLEN, D, NO_INJURY, R.string.pain_chat_sign_hot_red_swollen_foot),
    PainSign(SymptomOption.TINGLE_NUMB, D, NO_INJURY, R.string.pain_chat_sign_tingle_numb_foot),
    PainSign(SymptomOption.FAINT_FROM_PAIN, D),
    PainSign(SymptomOption.NO_WEIGHT, D, label = R.string.pain_chat_sign_no_weight_foot),
    PainSign(SymptomOption.FEVERISH, D),
    PainSign(SymptomOption.SICK_CANT_EAT, D),
    PainSign(SymptomOption.JOINT_WORSE, D, NO_INJURY, areas = FOOT_JOINTS),
    PainSign(SymptomOption.SWOLLEN_JOINT, D, NO_INJURY, areas = FOOT_JOINTS),
    PainSign(SymptomOption.SKIN_COLOUR, D, NO_INJURY, areas = FOOT_JOINTS),
    PainSign(SymptomOption.TIPTOE_STAIRS, D, INJURY),
    PainSign(SymptomOption.TIPTOE_STAIRS, D, NO_INJURY, areas = HEEL_AREAS),
    PainSign(SymptomOption.CALF_ANKLE_SWELL, D, INJURY),
    PainSign(SymptomOption.CALF_ANKLE_SWELL, D, NO_INJURY, areas = HEEL_AREAS),
    PainSign(SymptomOption.SWELL_BRUISE, D, INJURY),
    PainSign(SymptomOption.STIFF_HARD, D, INJURY),
    PainSign(SymptomOption.HAS_DIABETES, G),
)

/**
 * Every family's red flags, in the order each family's pages give them. The chest has none: a chest
 * marked hands over to the chest's own questions ([painHandsOver]), and after them its marks bring
 * nothing of their own but "does it spread?".
 */
internal val PAIN_SIGNS_BY_FAMILY: Map<PainFamily, List<PainSign>> = mapOf(
    PainFamily.HEAD to HEAD_SIGNS,
    PainFamily.JAW to JAW_SIGNS,
    PainFamily.NECK to NECK_SIGNS,
    PainFamily.SHOULDER to SHOULDER_SIGNS,
    PainFamily.CHEST to emptyList(),
    PainFamily.BACK to BACK_SIGNS,
    PainFamily.BELLY to BELLY_SIGNS,
    PainFamily.ARM to ARM_SIGNS,
    PainFamily.HAND to HAND_SIGNS,
    PainFamily.HIP to HIP_SIGNS,
    PainFamily.KNEE to KNEE_SIGNS,
    PainFamily.LEG to LEG_SIGNS,
    PainFamily.FOOT to FOOT_SIGNS,
)

/** The families whose "chest too" goes once the person said the pain does not spread there from the chest. */
private val SPREAD_CHEST_TOO = setOf(PainFamily.JAW, PainFamily.NECK, PainFamily.SHOULDER, PainFamily.ARM)

/** One list holds this many red flags at most; past it the most urgent come first, the rest after. */
private const val ONE_LIST_MAX = 10

/**
 * The thread's red flags as asked: [first], and when they do not fit in one list [more] — each ended
 * by "None of these" — the words each option is shown in ([labels]) and its level ([level]).
 */
internal data class PainSignLists(
    val first: List<SymptomOption>,
    val more: List<SymptomOption>,
    val labels: Map<SymptomOption, Int>,
    val level: Map<SymptomOption, SignLevel>,
)

/**
 * Whether this red flag stands by the answers: the injury one ([injured] in its family) and the areas
 * marked. The profile's part is [applies]'s; a rule reads only the answers.
 */
internal fun PainSign.fitsAnswers(injured: Boolean, marks: Collection<RegionSide>): Boolean {
    val byInjury = when (whenInjury) {
        SignWhen.ALWAYS -> true
        SignWhen.INJURY -> injured
        SignWhen.NO_INJURY -> !injured
    }
    return byInjury && (areas.isEmpty() || marks.any { it.region.name in areas || it.code in areas })
}

/** Whether [this] red flag is asked here: by the answers, and by the profile — a woman's, a child's. */
private fun PainSign.applies(
    injured: Boolean,
    female: Boolean,
    adult: Boolean,
    marks: Collection<RegionSide>,
): Boolean =
    fitsAnswers(injured, marks) && (!this.female || female) && (!child || !adult)

/**
 * The red flags of [families], put together: every row that applies — by [injury] (a
 * family the injury question is not asked for counts as no injury), the profile, the areas marked —
 * family by family in their order, without the jaw's, neck's, shoulder's and arm's "chest too" when
 * [spreadCovered]; each option once, at its most urgent level, in the words of the row that gave it
 * (the first family's on a tie); then, ten or fewer, one list from the most urgent down, else the
 * call-999 and A&E ones first and the rest after.
 */
internal fun painSignLists(
    families: List<PainFamily>,
    areas: Set<RegionSide>,
    injury: Boolean?,
    female: Boolean,
    adult: Boolean,
    spreadCovered: Boolean,
): PainSignLists {
    val chosen = LinkedHashMap<SymptomOption, PainSign>()
    families.sortedBy { it.ordinal }.forEach { family ->
        val injured = injury == true && family.injuryAsked
        PAIN_SIGNS_BY_FAMILY[family].orEmpty()
            .filter { it.applies(injured, female, adult, areas) }
            .filterNot { it.option == SymptomOption.CHEST_TOO && family in SPREAD_CHEST_TOO && spreadCovered }
            .forEach { sign ->
                val held = chosen[sign.option]
                if (held == null || sign.level < held.level || widerOnTie(sign, held)) chosen[sign.option] = sign
            }
    }
    val ordered = chosen.values.sortedBy { it.level.ordinal }
    val (first, more) = if (ordered.size <= ONE_LIST_MAX) {
        ordered to emptyList()
    } else {
        ordered.partition { it.level == SignLevel.E || it.level == SignLevel.I }
    }
    fun ended(list: List<PainSign>) =
        if (list.isEmpty()) emptyList() else list.map { it.option } + SymptomOption.NONE
    return PainSignLists(
        first = ended(first),
        more = ended(more),
        labels = chosen.mapValues { it.value.label },
        level = chosen.mapValues { it.value.level },
    )
}

/**
 * Whether [sign] takes the place of [held] at the same level: only for "chest pain too", the wider of
 * its words — its own (pain, pressure, tightness, heaviness or squeezing) over any family's,
 * and the leg's "chest pain, or pain in the upper back" (NHS "Pulmonary embolism") over the back's and
 * the belly's "chest pain", which it holds whole; every other tie keeps the first family's.
 */
private fun widerOnTie(sign: PainSign, held: PainSign): Boolean =
    sign.option == SymptomOption.CHEST_TOO && sign.level == held.level && chestTooWidth(sign) > chestTooWidth(held)

/** How much a "chest pain too" label says: the back's and belly's least, the leg's more, its own the most. */
private fun chestTooWidth(sign: PainSign): Int = when (sign.label) {
    sign.option.labelRes -> 2
    R.string.pain_chat_sign_chest_too_leg -> 1
    else -> 0
}

/** The answers to the question asked with a list that held a fever below 999: the signs of sepsis. */
internal val SEPSIS_OPTIONS = listOf(
    SymptomOption.SEVERE_BREATHING,
    SymptomOption.CONFUSED,
    SymptomOption.BLUE_SKIN,
    SymptomOption.NON_BLANCHING_RASH,
    SymptomOption.NONE,
)

/** The words of the sepsis question's answers: the confusion in the meningitis page's own. */
private val SEPSIS_LABELS = mapOf(SymptomOption.CONFUSED to R.string.pain_chat_sign_confused_neck)

/**
 * Whether a chest of the thread's marks has to be asked about first, in a thread of its own:
 * as it stood when the area was answered, which the answer froze — a sitting begun since (a quarter of
 * an hour away) never turns a thread that was already past its chest into one that hands over again.
 */
internal fun painHandsOver(state: ChatState): Boolean {
    val frozen = state.report.meta[PAIN_HANDOVER]
    return if (frozen != null &&
        SymptomStep.PAIN_AREA in state.chat.askedSteps()
    ) {
        frozen == "1"
    } else {
        chestUntriaged(state)
    }
}

/** A chest among the thread's marks, and none asked about in this sitting yet: what the area answer freezes. */
private fun chestUntriaged(state: ChatState): Boolean =
    PainFamily.CHEST in painFamilies(painThreadMarks(state.report)) && !state.thread.chestAsked

/** "1" when the area answer handed the chest over to its own questions, "0" when it did not. */
internal const val PAIN_HANDOVER = "PAIN_AREA.handover"

/** The red flags of [state]'s thread as its answers and its chat stand. */
internal fun painSignListsOf(state: ChatState): PainSignLists {
    val marks = painThreadMarks(state.report)
    val injury = when {
        state.report.has(SymptomStep.PAIN_INJURY, SymptomOption.YES) -> true
        state.report.has(SymptomStep.PAIN_INJURY, SymptomOption.NO) -> false
        else -> null
    }
    return painSignLists(
        families = painFamilies(marks),
        areas = marks.toSet(),
        injury = injury,
        female = state.female,
        adult = state.adult,
        spreadCovered = chestTooCovered(marks, state.thread.chestSpreadAreas),
    )
}

/**
 * Whether every area of the jaw, the neck, the shoulders and the arms marked was named by this
 * sitting's "does it spread from the chest?", answered no: their "chest too" is then asked already.
 */
internal fun chestTooCovered(marks: Collection<RegionSide>, asked: Set<String>): Boolean {
    val spread = marks.filter { area -> painFamilies(area.region).any { it in SPREAD_CHEST_TOO } }
    return spread.isNotEmpty() && spread.all { it.code in asked }
}

/** Whether a list [step] answered holds a fever below 999, ticked: the sepsis question follows it. */
private fun sepsisAfter(report: SymptomReport, lists: PainSignLists, step: SymptomStep, list: List<SymptomOption>) =
    SymptomOption.FEVERISH in list && report.has(step, SymptomOption.FEVERISH) &&
        lists.level[SymptomOption.FEVERISH] != SignLevel.E

/**
 * The thread's questions after its topic, in order: where it hurts; nothing more when a
 * chest marked hands over; "does it spread?" for an arm, a neck, a jaw, a back or a belly marked with
 * the chest after the chest was asked about; the injury question for a family that has one; the red
 * flags, one list or two, with the sepsis question after a fever below 999; "the same pain?" for an
 * area of the month; then the pain page's own questions, as it asks them ([painQuestions]).
 */
internal fun painThreadSteps(state: ChatState): List<SymptomStep> {
    val report = state.report
    val marks = painThreadMarks(report)
    val steps = mutableListOf(SymptomStep.PAIN_AREA)
    if (marks.isEmpty() || SymptomStep.PAIN_AREA !in state.chat.askedSteps() || painHandsOver(state)) return steps
    if (SymptomStep.CHEST_SPREAD in state.chat.askedSteps() || chestSpreadCodes(state).isNotEmpty()) {
        steps += SymptomStep.CHEST_SPREAD
    }
    if (painFamilies(marks).any { it.injuryAsked }) steps += SymptomStep.PAIN_INJURY
    steps += signSteps(state)
    if (report.meta[PAIN_EARLIER] != null) {
        steps += SymptomStep.PAIN_SAME
        if (report.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES)) steps += SymptomStep.PAIN_CHANGED
    }
    return steps + pageSteps(report)
}

/** The red flags the thread asks, one list or two, each followed by the sepsis question after a fever below 999. */
private fun signSteps(state: ChatState): List<SymptomStep> {
    val lists = painSignListsOf(state)
    return listOf(SymptomStep.PAIN_SIGNS to lists.first, SymptomStep.PAIN_SIGNS_MORE to lists.more)
        .filter { (_, list) -> list.isNotEmpty() }
        .flatMap { (step, list) ->
            listOfNotNull(step, SymptomStep.PAIN_SEPSIS.takeIf { sepsisAfter(state.report, lists, step, list) })
        }
}

/** The pain page's own questions, as it asks them ([painQuestions]); how long it lasted only once gone. */
private fun pageSteps(report: SymptomReport): List<SymptomStep> {
    val questions = painQuestions(
        editing = null,
        marked = true,
        rowsLoaded = true,
        earlier = frozenEarlier(report)?.report ?: earlierShell(report),
        same = sameAnswer(report),
        changed = changedAnswer(report),
    )
    val gone = report.has(SymptomStep.PAIN_STATUS, SymptomOption.GONE)
    val asked = questions.steps.takeIf { questions.asked }.orEmpty()
    return asked.filter { it != PainStep.LASTED || gone }.map { PAGE_STEPS.getValue(it) }
}

/** The pain page's questions as the chat asks them. */
private val PAGE_STEPS: Map<PainStep, SymptomStep> = mapOf(
    PainStep.TYPES to SymptomStep.PAIN_TYPES,
    PainStep.ONSET to SymptomStep.ONSET,
    PainStep.INTENSITY to SymptomStep.PAIN_NRS,
    PainStep.NOTICED to SymptomStep.PAIN_NOTICED,
    PainStep.STATUS to SymptomStep.PAIN_STATUS,
    PainStep.LASTED to SymptomStep.PAIN_LASTED,
    PainStep.PAINKILLER to SymptomStep.PAIN_PAINKILLER,
)

/** An earlier pain found but not yet known as the same one: what "the same pain?" waits on, with nothing to carry. */
private fun earlierShell(report: SymptomReport): PainReport? = report.meta[PAIN_EARLIER]?.let { PainReport() }

private fun sameAnswer(report: SymptomReport): Boolean? = when {
    report.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES) -> true
    report.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_NEW) -> false
    else -> null
}

private fun changedAnswer(report: SymptomReport): Boolean? = when {
    report.has(SymptomStep.PAIN_CHANGED, SymptomOption.FEELS_CHANGED) -> true
    report.has(SymptomStep.PAIN_CHANGED, SymptomOption.FEELS_SAME) -> false
    else -> null
}

// ── "Does it spread from the chest?" ──

/** The families a pain spreads to from the chest, as the heart attack page names them. */
private val SPREAD_FAMILIES = setOf(
    PainFamily.JAW,
    PainFamily.NECK,
    PainFamily.SHOULDER,
    PainFamily.ARM,
    PainFamily.HAND,
    PainFamily.BELLY,
)

/** The parts of the back a pain from the chest does not spread to: the buttocks, and the base of the spine. */
private val NOT_SPREAD = setOf("GLUTE", "SACRUM", "TAILBONE")

/**
 * Whether a pain may spread to [area] from the chest: every area of the jaw, the neck, the
 * shoulders, the arms, the hands and the belly, and of the back but its base — by family, so every
 * area a later map adds is in.
 */
internal fun isChestSpreadArea(area: RegionSide): Boolean {
    val families = painFamilies(area.region)
    return families.any { it in SPREAD_FAMILIES } || (PainFamily.BACK in families && area.region.name !in NOT_SPREAD)
}

/**
 * The areas "does it spread from the chest?" names in this thread: those frozen when it was
 * answered; before that, for the chest pain thread a pain thread handed over to, the spread areas
 * that thread marked with the chest; for a pain thread after the chest was asked about, its spread
 * areas marked with the chest that this sitting has not asked about yet. None otherwise.
 */
internal fun chestSpreadCodes(state: ChatState): List<String> {
    val frozen = state.report.meta[CHEST_SPREAD_AREAS]
    if (frozen != null) return frozen.split(',').filter { it.isNotEmpty() }
    val handedOver = state.report.symptom == Symptom.CHEST_PAIN &&
        state.thread.from?.startsWith(ROUTE_HANDOVER) == true
    val pain = state.report.symptom == Symptom.PAIN && state.thread.chestAsked
    val marks = when {
        handedOver -> painThreadMarks(state.thread.resumed)
        pain -> painThreadMarks(state.report)
        else -> emptyList()
    }
    val withChest = marks.any { PainFamily.CHEST in painFamilies(it.region) }
    val asked = if (pain) state.thread.chestSpreadAreas else emptySet()
    return marks.filter { withChest && isChestSpreadArea(it) }.map { it.code }.filter { it !in asked }
}

/** The chest's "does it spread?" in words: the areas named, in the language showing. */
internal fun chestSpreadQuestion(state: ChatState): ChatText =
    ChatText(R.string.symptom_ask_chest_spread, listOf(PieceArg.Areas(chestSpreadCodes(state))))

/** What "does it spread?" freezes: the areas it named, which are then asked about for the sitting. */
internal fun chestSpreadFacts(state: ChatState): Map<String, String> =
    mapOf(CHEST_SPREAD_AREAS to chestSpreadCodes(state).joinToString(","))

// ── "The same pain?" ──

/** The moment of the earlier pain the area answer found in the month, if any. */
internal const val PAIN_EARLIER = "PAIN_AREA.earlier"

/** "1" when that earlier pain is a record of this very chat. */
internal const val PAIN_HERE = "PAIN_AREA.here"

/** The first report of the same pain: what the record's `same` points at. */
internal const val PAIN_SAME_FIRST = "PAIN_SAME.first"

/** The earlier pain's words, its first-noticed answer and its rating, frozen as the same pain was told. */
internal const val PAIN_SAME_TYPES = "PAIN_SAME.types"
internal const val PAIN_SAME_NOTICED = "PAIN_SAME.noticed"
internal const val PAIN_SAME_NRS = "PAIN_SAME.nrs"

/** Whole days from the pain's first report to the day it is told of ([painDay]): how long it has gone on. */
internal const val PAIN_SAME_DAYS = "PAIN_SAME.days"

/**
 * The day a pain is told of: an earlier day's, or today in a chat being had now — one gone on with past
 * midnight keeps the day it was begun on, while the row it files stands on today ([ChatFiling]).
 */
private fun painDay(state: ChatState): LocalDate? = if (state.pastDay) state.thread.day else state.thread.today

/**
 * What the area answer freezes: the newest pain of the month up to the day it is told of that names an area
 * of the marks, or one of its family on a side that agrees ([earlierPainAmong]) — never the row this
 * thread filed itself; another thread's record of this chat is kept, the pain said twice — and
 * whether it is this chat's.
 */
private fun areaFacts(state: ChatState): Map<String, String> {
    val day = painDay(state) ?: return emptyMap()
    val marks = painThreadMarks(state.report).toSet()
    val own = state.thread.links.filter { it.kind == KIND_PAIN }
    val rows = state.thread.env.painMonth
        .filter { row -> row.kind == KIND_PAIN && own.none { it.at == row.occurredAt && it.wsig == rowSig(row) } }
        .map { PainRow(it) }
    val earlier = earlierPainAmong(marks, rows, day, editingId = null) ?: return emptyMap()
    val at = earlier.entry.occurredAt
    val here = state.thread.others.any { other ->
        other.links.any { it.kind == KIND_PAIN && it.at == at && it.state == LinkState.FILED }
    }
    return buildMap {
        put(PAIN_EARLIER, at.toString())
        if (here) put(PAIN_HERE, "1")
    }
}

/**
 * What "the same pain" freezes: the earlier row's first report, its words, first-noticed answer and
 * rating, and how many whole days that first report is before the day told of. A later edit or
 * deletion of the earlier row changes nothing already asked. Nothing for "a new one".
 */
private fun sameFacts(state: ChatState): Map<String, String> {
    if (!state.report.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES)) return emptyMap()
    val at = state.report.meta[PAIN_EARLIER]?.toLongOrNull() ?: return emptyMap()
    val month = state.thread.env.painMonth.filter { it.kind == KIND_PAIN }
    val row = month.firstOrNull { it.occurredAt == at }?.let { PainRow(it) }
    val first = row?.let { firstReportOf(it) } ?: at
    val firstDay = firstReportDay(first, row, month)
    val days = painDay(state)?.let { ChronoUnit.DAYS.between(firstDay, it) }
    return buildMap {
        put(PAIN_SAME_FIRST, first.toString())
        put(PAIN_SAME_TYPES, row?.report?.types.orEmpty().joinToString(",") { it.name })
        row?.report?.noticed?.let { put(PAIN_SAME_NOTICED, it.name) }
        row?.report?.nrs?.let { put(PAIN_SAME_NRS, it.toString()) }
        days?.let { put(PAIN_SAME_DAYS, it.coerceAtLeast(0).toString()) }
    }
}

/**
 * The day the pain was first reported on: the earlier row's own day when it is the first report;
 * else the first report's day among the month's rows; else, older than the month, its moment's date.
 */
private fun firstReportDay(first: Long, row: PainRow?, month: List<JournalEntry>): LocalDate {
    val day = if (row != null && row.entry.occurredAt == first) {
        row.entry.day
    } else {
        month.firstOrNull { it.occurredAt == first }?.day
    }
    return day?.let { it.toLocalDateOrNull() }
        ?: Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** The earlier pain as "the same pain" froze it: a row at its first report, with its words, noticed and rating. */
internal fun frozenEarlier(report: SymptomReport): PainRow? {
    val first = report.meta[PAIN_SAME_FIRST]?.toLongOrNull() ?: return null
    val types = report.meta[PAIN_SAME_TYPES].orEmpty().split(',')
        .mapNotNull { name -> PainType.entries.firstOrNull { it.name == name } }
    val noticed = report.meta[PAIN_SAME_NOTICED]?.let { name -> PainNoticed.entries.firstOrNull { it.name == name } }
    val nrs = report.meta[PAIN_SAME_NRS]?.toIntOrNull()
    val shell = JournalEntry(day = "", occurredAt = first, kind = KIND_PAIN, title = "", calories = 0.0)
    return PainRow(shell, PainReport(types = types, noticed = noticed, nrs = nrs))
}

/** "The same pain?": its earlier pain, the words it is told in, and "Open that entry" when it is this chat's. */
internal data class PainSameFound(val at: Long, val here: Boolean, val areas: List<String>) : BotPiece

/** "Carried over: aching, tight; noticed after a workout" — what the same pain takes from its first entry. */
internal data class PainCarried(val types: List<PainType>, val noticed: PainNoticed?) : BotPiece

// ── The answers as options ──

private val YES_NO = listOf(SymptomOption.YES, SymptomOption.NO)

/**
 * Each kind of pain's option, in the pain page's order: named pair by pair, so a kind added to either
 * list never moves the others onto the wrong word, as pairing them by place would.
 */
private val TYPE_OF_OPTION: Map<SymptomOption, PainType> = mapOf(
    SymptomOption.PAIN_ACHING to PainType.ACHING,
    SymptomOption.PAIN_STIFF to PainType.STIFF,
    SymptomOption.PAIN_THROBBING to PainType.THROBBING,
    SymptomOption.PAIN_SHARP to PainType.SHARP,
    SymptomOption.PAIN_BURNING to PainType.BURNING,
    SymptomOption.PAIN_CRAMPING to PainType.CRAMPING,
)

private val TYPE_OPTIONS = TYPE_OF_OPTION.keys.toList()

/** Each first-noticed answer's option, named pair by pair as the kinds are. */
private val NOTICED_OF_OPTION: Map<SymptomOption, PainNoticed> = mapOf(
    SymptomOption.NOTICED_DURING_WORKOUT to PainNoticed.DURING_WORKOUT,
    SymptomOption.NOTICED_AFTER_WORKOUT to PainNoticed.AFTER_WORKOUT,
    SymptomOption.NOTICED_WORK_OR_SITTING to PainNoticed.WORK_OR_SITTING,
    SymptomOption.NOTICED_ON_WAKING to PainNoticed.ON_WAKING,
    SymptomOption.NOTICED_AFTER_LIFTING to PainNoticed.AFTER_LIFTING,
    SymptomOption.NOTICED_NO_CLEAR_MOMENT to PainNoticed.NO_CLEAR_MOMENT,
)

private val NOTICED_OPTIONS = NOTICED_OF_OPTION.keys.toList()

private val ONSET_OPTIONS = listOf(
    SymptomOption.STARTED_NOW,
    SymptomOption.STARTED_TODAY,
    SymptomOption.STARTED_YESTERDAY,
    SymptomOption.STARTED_2_3_DAYS,
    SymptomOption.STARTED_A_WEEK,
    SymptomOption.STARTED_A_MONTH,
    SymptomOption.STARTED_LONGER,
)

/** Where the "how long did it last?" ruler starts: an hour, as on the pain page. */
internal const val PAIN_LASTED_START = 60

/**
 * The steps whose answer the pain thread's own file draws: the areas, the two rulers, the red flags in their family's
 * words.
 */
private val PAIN_BUBBLE_STEPS = setOf(
    SymptomStep.PAIN_AREA,
    SymptomStep.PAIN_NRS,
    SymptomStep.PAIN_LASTED,
    SymptomStep.PAIN_SIGNS,
    SymptomStep.PAIN_SIGNS_MORE,
    SymptomStep.PAIN_SEPSIS,
)

/** The pain topic's script ([Symptom.PAIN]). */
internal object PainScript : TopicScript {
    override fun steps(state: ChatState): List<SymptomStep> = painThreadSteps(state)

    override fun options(state: ChatState, step: SymptomStep): List<SymptomOption> = when (step) {
        SymptomStep.PAIN_INJURY, SymptomStep.PAIN_PAINKILLER, SymptomStep.CHEST_SPREAD -> YES_NO

        SymptomStep.PAIN_SIGNS -> painSignListsOf(state).first

        SymptomStep.PAIN_SIGNS_MORE -> painSignListsOf(state).more

        SymptomStep.PAIN_SEPSIS -> SEPSIS_OPTIONS

        SymptomStep.PAIN_SAME -> listOf(SymptomOption.PAIN_SAME_YES, SymptomOption.PAIN_NEW)

        SymptomStep.PAIN_CHANGED -> listOf(SymptomOption.FEELS_SAME, SymptomOption.FEELS_CHANGED)

        SymptomStep.PAIN_TYPES -> TYPE_OPTIONS

        SymptomStep.ONSET -> ONSET_OPTIONS

        SymptomStep.PAIN_NOTICED -> NOTICED_OPTIONS

        SymptomStep.PAIN_STATUS -> listOf(SymptomOption.STILL_HURTS, SymptomOption.GONE)

        SymptomStep.PAIN_AREA, SymptomStep.PAIN_NRS, SymptomStep.PAIN_LASTED -> emptyList()

        // A question the person's words brought into the thread is asked as everywhere else.
        else -> SymptomScript.options(state, step)
    }

    override fun reply(state: ChatState, step: SymptomStep): ChatReply = when (step) {
        SymptomStep.PAIN_AREA -> painAreaReply(state)

        SymptomStep.PAIN_NRS -> ChatReply.PainScale(
            step,
            start = state.report.meta[PAIN_SAME_NRS]?.toIntOrNull(),
            rest = threadPainMention(state)?.nrs,
        )

        SymptomStep.PAIN_LASTED -> ChatReply.PainLasted(step, PAIN_LASTED_START)

        else -> painChoice(state, step)
    }

    override fun question(state: ChatState, step: SymptomStep): Int = painQuestionRes(state, step)

    override fun questionText(state: ChatState, step: SymptomStep): ChatText? =
        if (step == SymptomStep.CHEST_SPREAD) chestSpreadQuestion(state) else null

    override fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> =
        painLead(state, last, step)

    override fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece> = painClose(state, alert)

    override fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState,
    ): String {
        val report = decodePainPayload(row?.measurementPayloadJson)
        val line = painSummaryLine(
            report,
            area = resources::painArea,
            score = { resources.getString(R.string.pain_nrs_value, it) },
            word = { resources.getString(it.shortRes) },
            none = resources.getString(R.string.pain_none_entry),
            comma = resources.getString(R.string.list_comma),
        )
        return listOf(resources.getString(R.string.pain_title), line).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    override fun savedDetail(resources: Resources, row: JournalEntry?, course: Medication?, role: String): String? =
        row?.let { resources.painCardDetail(it) }?.takeIf { it.isNotEmpty() }

    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> = painWants(thread, ctx)

    override fun freeze(state: ChatState, step: SymptomStep): Map<String, String> = when (step) {
        SymptomStep.PAIN_AREA -> areaFacts(state) + (PAIN_HANDOVER to if (chestUntriaged(state)) "1" else "0")
        SymptomStep.PAIN_SAME -> sameFacts(state)
        SymptomStep.CHEST_SPREAD -> chestSpreadFacts(state)
        else -> emptyMap()
    }

    override val stepKinds: Map<SymptomStep, StepKind> get() = PAIN_STEP_KINDS

    override fun followUps(state: ChatState): List<TopicChoice> {
        val took = state.report.has(SymptomStep.PAIN_PAINKILLER, SymptomOption.YES)
        val mention = TopicMention(Symptom.DOSE, PAINKILLER_DETAIL)
        val dose = TopicChoice(Symptom.DOSE, mention, R.string.pain_chat_follow_dose)
        return listOfNotNull(dose.takeIf { took && !state.pastDay })
    }

    override fun handover(state: ChatState): Symptom? = Symptom.CHEST_PAIN.takeIf {
        SymptomStep.PAIN_AREA in state.chat.askedSteps() && painHandsOver(state)
    }

    /** Every answer in its words is the pain file's to draw ([bubbleSteps]) or its options' own labels. */
    override fun answerText(state: ChatState, step: SymptomStep): ChatText? = null

    override val bubbleSteps: Set<SymptomStep> get() = PAIN_BUBBLE_STEPS

    /** "Pain · Knee (R)", or "Pain" before an area is told. */
    override fun title(resources: Resources, thread: ChatThread): String {
        val areas = painThreadMarks(thread.report.forSave())
            .joinToString(resources.getString(R.string.list_comma)) { resources.painAreaShort(it) }
        val name = resources.getString(R.string.pain_title)
        return if (areas.isEmpty()) name else "$name · $areas"
    }

    /** A pain thread takes up every pain of the families it marked: the queue does not offer the knee again. */
    override fun serves(thread: ChatThread, mention: TopicMention): Boolean = painThreadServes(thread, mention)

    /** "Pain: Knee", by the mention's body family ([painMentionLabel]). */
    override fun mentionLabel(mention: TopicMention): ChatText? = painMentionLabel(mention)

    override fun tagFacts(thread: ChatThread): TagFacts {
        val report = thread.report.forSave()
        return TagFacts(
            families = painFamilies(painThreadMarks(report)).mapTo(LinkedHashSet()) { it.name },
            course = report.meta[PAIN_SAME_DAYS]?.toLongOrNull()?.let(::onsetOfAge),
            recurring = report.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES),
        )
    }

    /**
     * The steps the rules read — the area, the injury, the red-flag lists, the 1–10, whether
     * it is still there, how long it lasted, the painkiller — and the chest's "does it spread?": a model
     * never words or skips them.
     */
    override val owedSteps: Set<SymptomStep> = setOf(
        SymptomStep.PAIN_AREA,
        SymptomStep.PAIN_INJURY,
        SymptomStep.PAIN_SIGNS,
        SymptomStep.PAIN_SIGNS_MORE,
        SymptomStep.PAIN_SEPSIS,
        SymptomStep.PAIN_NRS,
        SymptomStep.PAIN_STATUS,
        SymptomStep.PAIN_LASTED,
        SymptomStep.PAIN_PAINKILLER,
        SymptomStep.CHEST_SPREAD,
    )

    override fun offered(setting: ChatSetting): Boolean = true

    override val oncePerChat: Boolean = false

    override val flashAt: FlashAt = FlashAt.FILE
}

/** The follow-up a painkiller answered "yes" brings: noting the dose taken (the medicine package's). */
internal const val PAINKILLER_DETAIL = "PAINKILLER"

/** A same pain's first report [days] before the chat, as the start chip that age would be. */
private fun onsetOfAge(days: Long): SymptomOption = when {
    days <= 0 -> SymptomOption.STARTED_TODAY
    days == 1L -> SymptomOption.STARTED_YESTERDAY
    days <= 3 -> SymptomOption.STARTED_2_3_DAYS
    days <= PAIN_ONSET_REACH_DAYS -> SymptomOption.STARTED_A_WEEK
    days <= DAYS_A_MONTH -> SymptomOption.STARTED_A_MONTH
    else -> SymptomOption.STARTED_LONGER
}

private const val DAYS_A_MONTH = 31L

/**
 * How the pain thread's own steps are answered: the area is a painting with its areas, sent with one
 * tap of Send or of an area chip; the 1–10 and how long it lasted are values the clock settles, as
 * the page's rulers are. Every other step is a choice.
 */
private val PAIN_STEP_KINDS: Map<SymptomStep, StepKind> = mapOf(
    SymptomStep.PAIN_AREA to StepKind(StepHolds.PAINTING, AnswerKind.TAP),
    SymptomStep.PAIN_NRS to StepKind(StepHolds.VALUE, AnswerKind.SETTLE),
    SymptomStep.PAIN_LASTED to StepKind(StepHolds.VALUE, AnswerKind.SETTLE),
)

// ── The area question ──

/**
 * The area question's widget: for the pain taken up again after the chest's questions, the map with
 * what was marked before; else what the thread's words propose — chips for a place, the map framed
 * on a family, the head's chips, the map on the chest after the chest was asked about, or the whole
 * body ([painProposalFor]).
 */
internal fun painAreaReply(state: ChatState): ChatReply {
    val step = SymptomStep.PAIN_AREA
    val resumed = state.thread.resumed?.takeIf { state.thread.from?.startsWith(ROUTE_RESUME) == true }
    if (resumed != null) {
        val view = resumed.strokes.firstOrNull()?.view ?: PainView.FRONT
        return ChatReply.BodyMap(step, PainFrame(view, null), head = false, headChips = emptyList())
    }
    return painProposalFor(state).reply(step)
}

/** The area question in the words of its widget. */
private fun areaQuestionRes(state: ChatState): Int = when (val reply = painAreaReply(state)) {
    is ChatReply.AreaChips -> R.string.pain_chat_ask_where
    is ChatReply.BodyMap -> if (reply.head) R.string.pain_chat_ask_head else R.string.pain_chat_ask_map
    else -> R.string.pain_chat_ask_map
}

/**
 * Each question in its words; a chat for an earlier day asks its red flags about now and
 * the rest about that day.
 */
private fun painQuestionRes(state: ChatState, step: SymptomStep): Int = if (step == SymptomStep.PAIN_AREA) {
    areaQuestionRes(state)
} else {
    PAIN_DAY_QUESTIONS[step]?.takeIf { state.pastDay } ?: PAIN_QUESTIONS[step]
        ?: chatQuestionRes(state.report.symptom, step, state.report, state.pastDay)
}

/** The thread's questions in their words: the pain page's own, but for the injury and the red flags. */
private val PAIN_QUESTIONS: Map<SymptomStep, Int> = mapOf(
    SymptomStep.PAIN_INJURY to R.string.pain_chat_ask_injury,
    SymptomStep.PAIN_SIGNS to R.string.symptom_ask_with,
    SymptomStep.PAIN_SIGNS_MORE to R.string.pain_chat_ask_signs_more,
    SymptomStep.PAIN_SEPSIS to R.string.pain_chat_ask_sepsis,
    SymptomStep.PAIN_SAME to R.string.pain_same_question,
    SymptomStep.PAIN_CHANGED to R.string.pain_same_changed_question,
    SymptomStep.PAIN_TYPES to R.string.pain_chat_ask_types,
    SymptomStep.ONSET to R.string.pain_onset,
    SymptomStep.PAIN_NRS to R.string.pain_intensity,
    SymptomStep.PAIN_NOTICED to R.string.pain_noticed_title,
    SymptomStep.PAIN_STATUS to R.string.pain_status,
    SymptomStep.PAIN_LASTED to R.string.pain_lasted,
    SymptomStep.PAIN_PAINKILLER to R.string.pain_painkiller_question,
    SymptomStep.CHEST_SPREAD to R.string.symptom_ask_chest_spread,
)

/**
 * In a chat for a day gone by: the red flags asked about now, the rating, the end and the painkiller about that day.
 */
private val PAIN_DAY_QUESTIONS: Map<SymptomStep, Int> = mapOf(
    SymptomStep.PAIN_SIGNS to R.string.symptom_ask_with_now,
    SymptomStep.PAIN_SIGNS_MORE to R.string.symptom_ask_with_now,
    SymptomStep.PAIN_SEPSIS to R.string.symptom_ask_with_now,
    SymptomStep.PAIN_NRS to R.string.pain_chat_nrs_day,
    SymptomStep.PAIN_STATUS to R.string.pain_chat_status_day,
    SymptomStep.PAIN_PAINKILLER to R.string.pain_chat_painkiller_day,
)

/**
 * The answers to [step] as chips: the family's words for a red flag; the chips the thread's words
 * point at ([painMarks]) and those an earlier answer of this chat does — the chest's start, a dose
 * taken for the pain noted in this chat; for a same pain that changed, its earlier words standing
 * ticked with "Keep: …", and on an ordinary step what the model's readings told, proposed — marked,
 * never ticked — for "From your message: …" ([toldProposal]); at most two words of pain; "Open that
 * entry" beside "the same pain?" about a pain noted earlier in this chat.
 */
private fun painChoice(state: ChatState, step: SymptomStep): ChatReply {
    val options = PainScript.options(state, step)
    val marked = painChoiceMarks(state, step, options)
    val chatMarked = when (step) {
        SymptomStep.ONSET -> chestOnset(state).filterTo(LinkedHashSet()) { it in options && it !in marked }

        SymptomStep.PAIN_PAINKILLER -> setOfNotNull(
            SymptomOption.YES.takeIf { it !in marked && painkillerFiledInChat(othersLog(state)) },
        )

        else -> emptySet()
    }
    val labels = painChipLabels(state, step)
    val preset = if (step == SymptomStep.PAIN_TYPES) keptWords(state) else emptyList()
    val proposed = if (preset.isEmpty()) toldProposal(state, step, options, marked) else emptyList()
    return ChatReply.Choose(
        step = step,
        options = options,
        labels = labels,
        marked = marked,
        preset = preset,
        keep = keepText(preset, proposed),
        proposed = proposed,
        limit = if (step == SymptomStep.PAIN_TYPES) PAIN_TYPES_MAX else 0,
        openRef = sameRef(state, step),
        chatMarked = chatMarked,
    )
}

/**
 * The chips [step]'s words mark among [options] ([painMarks]): for the start, the words' own first —
 * the pain's, or one the thread's other messages name — and a reading's only where they name none
 * ([onsetMarks]), so a reading never takes their mark away; for the painkiller, one the words or a
 * reading told.
 */
private fun painChoiceMarks(state: ChatState, step: SymptomStep, options: List<SymptomOption>): Set<SymptomOption> =
    when (step) {
        SymptomStep.ONSET -> painMarks(step, threadPainMention(state, readings = false), options)
            .ifEmpty { onsetMarks(state, options) }

        SymptomStep.PAIN_PAINKILLER -> setOfNotNull(
            SymptomOption.YES.takeIf {
                painkillerIn(threadTexts(state).joinToString("\n")) || threadTold(state).painkiller
            },
        )

        else -> painMarks(step, threadPainMention(state), options)
    }

/** "Keep: …" for the words carried from before, ticked; "From your message: …" for what a message told. */
private fun keepText(preset: List<SymptomOption>, proposed: List<SymptomOption>): ChatText? {
    val (res, words) = when {
        preset.isNotEmpty() -> R.string.pain_chat_keep_words to preset
        proposed.isNotEmpty() -> R.string.symptom_chat_keep_told to proposed
        else -> return null
    }
    return ChatText(res, listOf(PieceArg.Listed(words.map { PieceArg.Res(shortOf(it)) })))
}

/** The words [step]'s chips are shown in where a family puts them in its own page's: the red flags'. */
internal fun painChipLabels(state: ChatState, step: SymptomStep): Map<SymptomOption, Int> = when (step) {
    SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE -> painSignListsOf(state).labels
    SymptomStep.PAIN_SEPSIS -> SEPSIS_LABELS
    else -> emptyMap()
}

/**
 * The next-step decider's one-tap proposal: on an ordinary step of the
 * pain no rule reads ([prefillable]) — when it was noticed, as the rules stand — the answer
 * the model's readings told ([readPainMention]), for "From your message: …" to take with one tap,
 * where the marks of the words agree with it or there are none of the words' own. Never ticked, never
 * on a step a rule reads, never taken without the tap: the question is asked as ever, its chips marked
 * as the words' are, and any of them answers it.
 */
private fun toldProposal(
    state: ChatState,
    step: SymptomStep,
    options: List<SymptomOption>,
    marked: Set<SymptomOption>,
): List<SymptomOption> {
    if (!prefillable(state.report.symptom, step)) return emptyList()
    val told = painMarks(step, readPainMention(state), options)
    return if (told.isNotEmpty() && told == marked) told.toList() else emptyList()
}

/** A kind of pain's short word ("aching"), for the "Keep" chip. */
private fun shortOf(option: SymptomOption): Int = TYPE_OF_OPTION[option]?.shortRes ?: option.labelRes

/** The same pain that has changed: its earlier words, ticked to start from (the page's prefill). */
private fun keptWords(state: ChatState): List<SymptomOption> {
    val report = state.report
    if (changedAnswer(report) != true || sameAnswer(report) != true) return emptyList()
    val words = frozenEarlier(report)?.report?.types.orEmpty()
    return TYPE_OPTIONS.filter { TYPE_OF_OPTION.getValue(it) in words }
}

/** "Open that entry" beside "the same pain?" when the earlier pain is this chat's own record. */
private fun sameRef(state: ChatState, step: SymptomStep): ChatRecordRef? {
    if (step != SymptomStep.PAIN_SAME || state.report.meta[PAIN_HERE] != "1") return null
    val at = state.report.meta[PAIN_EARLIER]?.toLongOrNull() ?: return null
    return ChatRecordRef(KIND_PAIN, at, null)
}

/**
 * The start the chest's own questions were told with, for the pain taken up after them — the chest
 * pain thread just before this one, which it resumes after or whose follow-up began it. A mark
 * "from this chat"; never another topic's start.
 */
private fun chestOnset(state: ChatState): Set<SymptomOption> {
    val from = state.thread.from.orEmpty()
    if (!from.startsWith(ROUTE_RESUME) && !from.startsWith(ROUTE_FOLLOW)) return emptySet()
    val chest = state.thread.others.lastOrNull()?.takeIf { it.topic == Symptom.CHEST_PAIN }
    return chest?.report?.answers?.get(SymptomStep.ONSET).orEmpty().toSet()
}

/** The chat's other threads as a log, for a hook that reads the chat (the medicine package's). */
private fun othersLog(state: ChatState): ChatLog =
    ChatLog(threads = state.thread.others.map { ChatThread(it.id, it.report, filed = it.links) })

// ── The lines between questions ──

/**
 * What the thread says before asking [step] after [last]: its start — "I am sorry it hurts" in the
 * chat's first pain thread, or the chest's "now let us finish the pain entry" — then, by the last
 * answer: the safety line for the chest or a bad head pain after the rating; a short thanks after the
 * area and after the last list of red flags, never after a line of advice; what the same pain carries
 * over from its first entry; and before "the same pain?" the earlier pain it asks about.
 */
private fun painLead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> {
    last ?: return emptyList()
    val report = state.report
    val pieces = mutableListOf<BotPiece>()
    if (last.step == SymptomStep.SYMPTOM) {
        painStartLine(state)?.let { pieces += BotPiece.Line(it) }
    } else {
        val marks = painThreadMarks(report)
        val nrs = report.values[SymptomStep.PAIN_NRS]?.toIntOrNull()
        if (last.step == SymptomStep.PAIN_NRS && painNeedsSafetyLine(marks, nrs)) {
            pieces += BotPiece.Line(ChatLine.PAIN_SAFETY)
        }
        if (last.alert == null && thanked(last.step, step)) pieces += BotPiece.Line(ChatLine.ACK_1)
        if (last.step == SymptomStep.PAIN_CHANGED) carriedPiece(report)?.let { pieces += it }
    }
    if (step == SymptomStep.PAIN_SAME) sameFoundPiece(report)?.let { pieces += it }
    if (askedForWords(state, step)) pieces += BotPiece.Line(ChatLine.WORDS_CHECK)
    return pieces
}

private val SIGN_STEPS = setOf(SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE, SymptomStep.PAIN_SEPSIS)

/** Thanks after the area, and after the last of the lists of red flags — once, not between two lists. */
private fun thanked(answered: SymptomStep, next: SymptomStep): Boolean =
    answered == SymptomStep.PAIN_AREA || (answered in SIGN_STEPS && next !in SIGN_STEPS)

/**
 * A pain thread's first words: after the chest's questions, "now let us finish the pain entry";
 * after the chest's follow-up nothing — the chest was just talked about; else, in the chat's first
 * pain thread only, "I am sorry it hurts".
 */
private fun painStartLine(state: ChatState): ChatLine? {
    val from = state.thread.from.orEmpty()
    return when {
        from.startsWith(ROUTE_RESUME) -> ChatLine.PAIN_RESUME
        from.startsWith(ROUTE_FOLLOW) -> null
        Symptom.PAIN in state.thread.before -> null
        else -> ChatLine.PAIN_START
    }
}

/**
 * What the same pain carries from its first entry, when anything: its words unless they changed, when it was noticed.
 */
private fun carriedPiece(report: SymptomReport): PainCarried? {
    val earlier = frozenEarlier(report)?.report ?: return null
    val types = earlier.types.takeIf { changedAnswer(report) == false }.orEmpty()
    val noticed = earlier.noticed
    return PainCarried(types, noticed).takeIf { types.isNotEmpty() || noticed != null }
}

private fun sameFoundPiece(report: SymptomReport): PainSameFound? {
    val at = report.meta[PAIN_EARLIER]?.toLongOrNull() ?: return null
    val areas = painThreadMarks(report).map { it.code }
    return PainSameFound(at, here = report.meta[PAIN_HERE] == "1", areas = areas)
}

// ── The close ──

/**
 * The thread's close: the last answer's line; nothing more for a chest handed over to its own
 * questions; otherwise the record's card — never after a call-999 or A&E line — and what to do now
 * by the thread's most urgent answer, with where it comes from for a pain to look after oneself, and
 * not to wait after a line to get advice now; a visit to add for advice today or a GP's —
 * after the pain's own "now" line too, whose NHS words ask for an urgent GP appointment or
 * 111 — unless an answer about suicide came up in the chat or a medicine's own "now" line fired in
 * this thread, too much of one or its serious side effect, which is for 111 or A&E alone.
 */
private fun painClose(state: ChatState, alert: List<BotPiece>): List<BotPiece> {
    if (painHandsOver(state)) return alert
    val report = state.report
    val urgency = chatUrgency(report)
    // What to do now reads every answer about suicide in the chat, as the end and the offers do.
    val crisis = crisisAnswered(report) || state.thread.crisisInChat
    val pieces = alert.toMutableList()
    if (urgency != SymptomUrgency.EMERGENCY) pieces += BotPiece.Saved(state.thread.id)
    pieces += painCloseLines(report, urgency, crisis, nowLineFired(report))
    val visit = urgency == SymptomUrgency.SAME_DAY || urgency == SymptomUrgency.GP
    if (visit && !crisis && !medicineNowFired(report)) pieces += BotPiece.Offer(ChatOffer.BOOK_VISIT, state.thread.id)
    return pieces
}

/**
 * What to do now, by the thread's [urgency]: not to wait after a line to get advice now ([nowLine]),
 * the crisis's line after an answer about suicide ([crisis]), else the level's own line.
 */
private fun painCloseLines(
    report: SymptomReport,
    urgency: SymptomUrgency,
    crisis: Boolean,
    nowLine: Boolean,
): List<BotPiece> = when (urgency) {
    SymptomUrgency.EMERGENCY -> closeLines(urgency, crisis, report.symptom).map { BotPiece.Line(it) }

    SymptomUrgency.SAME_DAY -> listOf(
        BotPiece.Line(
            when {
                nowLine -> ChatLine.CLOSE_NOW
                crisis -> ChatLine.CLOSE_CRISIS
                else -> ChatLine.CLOSE_TODAY
            },
        ),
    )

    SymptomUrgency.GP -> listOf(BotPiece.Line(ChatLine.CLOSE_GP))

    SymptomUrgency.SELF_CARE -> listOf(
        BotPiece.Line(ChatLine.CLOSE_BODY, painFamilies(painThreadMarks(report)).firstOrNull()?.sourceRes),
    )
}

// ── The record ──

/**
 * What a pain thread files: nothing until the area is told, nor for a chest handed over to its own
 * questions; after a call-999 or A&E line nothing more — what was filed stays as it stands; while the
 * questions go on, what is filed stays as it stands; once the last is answered, the one pain row the
 * pain page itself would write, through its door. The row of a crisis or emergency answer is the
 * filer's own ([WORDS_ROLE]).
 */
internal fun painWants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
    val report = thread.report.forSave()
    val asked = report.chat?.askedSteps().orEmpty()
    val marks = painThreadMarks(report)
    val handedOver = ctx.log.threads.any { it.from == ROUTE_HANDOVER + thread.id }
    // A record filed already stands as it is while its area is answered again: rewritten
    // under its id and moment once the thread is complete again, never taken back by the pencil.
    val linked = thread.filed.any { it.role.isEmpty() }
    val keep = if (linked) mapOf("" to RecordWant.Keep) else emptyMap()
    if (SymptomStep.PAIN_AREA !in asked || marks.isEmpty() || handedOver) return keep
    val stopped = firedRules(report.asTold()).any { it.alert.urgency == SymptomUrgency.EMERGENCY }
    return when {
        stopped -> keep
        SymptomStep.PAIN_PAINKILLER !in asked -> mapOf("" to RecordWant.Keep)
        else -> mapOf("" to RecordWant.File(painDraft(report, ctx)))
    }
}

/** The pain row, built as the pain page builds one: in the shell's day and moment, titled in the language showing. */
private fun painDraft(report: SymptomReport, ctx: FilingContext): RecordDraft =
    RecordDraft.Journal(JournalDoor.PAIN, wanted = null) { shell, _ ->
        painEntry(
            title = ctx.strings(R.string.pain_title),
            report = painRecordReport(report, shell.occurredAt),
            day = LocalDate.parse(shell.day),
            today = ctx.clock.today,
            now = shell.occurredAt,
            note = "",
            previous = shell,
        )
    }

/**
 * The report a thread's answers make, as the pain page's own save makes it ([painReportToSave]):
 * the areas and the painting, the words, the start, the 1–10, when it was noticed, how long it lasted
 * once gone, the painkiller; the same pain's first report from what "the same pain" froze, so a later
 * change to the earlier row changes nothing; on the current map.
 */
internal fun painRecordReport(report: SymptomReport, reportedAt: Long): PainReport {
    val gone = report.has(SymptomStep.PAIN_STATUS, SymptomOption.GONE)
    val ticked = report.answers[SymptomStep.PAIN_TYPES].orEmpty()
    val answers = PainReport(
        regions = painThreadMarks(report),
        types = TYPE_OPTIONS.filter { it in ticked }.map { TYPE_OF_OPTION.getValue(it) },
        nrs = report.values[SymptomStep.PAIN_NRS]?.toIntOrNull(),
        onset = report.answers[SymptomStep.ONSET]?.firstOrNull()?.painOnset,
        lastedMinutes = report.values[SymptomStep.PAIN_LASTED]?.toIntOrNull()?.takeIf { gone },
        noticed = report.answers[SymptomStep.PAIN_NOTICED]?.firstOrNull()?.let { NOTICED_OF_OPTION[it] },
        painkiller = report.has(SymptomStep.PAIN_PAINKILLER, SymptomOption.YES),
        strokes = report.strokes,
        map = PainMapVersion.CURRENT,
    )
    return painReportToSave(
        answers,
        reportedAt,
        earlier = frozenEarlier(report),
        same = sameAnswer(report),
        changed = changedAnswer(report),
        editing = null,
    )
}

/** The card's muted line: "6/10 · started 2–3 days before · still hurting when logged", the pain history's own. */
private fun Resources.painCardDetail(row: JournalEntry): String {
    val zone = ZoneId.systemDefault()
    val locale = configuration.locales[0]
    return painHistoryLine(
        decodePainPayload(row.measurementPayloadJson),
        lastedMinutes = row.durationMinutes?.toInt(),
        score = { getString(R.string.pain_nrs_value, it) },
        onset = { getString(it.sinceRes) },
        sameAs = { at ->
            val skeleton = painFirstReportSkeleton(at, row.day, zone)
            getString(R.string.pain_history_same, skeletonDateText(skeleton, locale, at, zone))
        },
        stillHurts = getString(R.string.pain_history_still_hurts),
        lasted = { getString(R.string.pain_history_lasted, durationLabel(this, it)) },
    )
}
