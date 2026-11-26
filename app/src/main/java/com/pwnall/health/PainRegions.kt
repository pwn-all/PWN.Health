package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which side of the body, as the person whose body it is would say it.
 *
 * Never the side of the picture: a figure seen from the front has its right hand on the viewer's
 * left, and the back view turns that round again. [sideOf] is the one place the two are converted,
 * and it has a test of its own because getting it wrong would silently swap every side a pain was
 * ever logged on.
 */
enum class BodySide(val code: String) {
    LEFT("L"),
    RIGHT("R"),
    CENTRE("C"),
}

/** Which of the two plates a stroke was painted on. */
enum class PainView(val code: String) {
    FRONT("F"),
    BACK("B"),
}

/**
 * The coarse area statistics pool a region into. One muscle has too few days to say anything
 * about, and these are the areas the work-pain and headache literature itself reports on.
 */
enum class PainGroup {
    HEAD,
    NECK_SHOULDERS,
    LOWER_BACK,
    ARMS_HANDS,
    CHEST_ABDOMEN,
    HIPS_LEGS,
}

/**
 * Which table of patches a painting is hit-tested on, and so which areas its strokes mark.
 *
 * The map is versioned rather than redrawn in place. An entry records the areas its strokes marked
 * when it was painted, and the summary, the doctor's sheet and the page that opens it again all run
 * the hit test on those strokes again: read with a later table, an old stroke on the back of the knee
 * would mark an area the entry never recorded, and be hidden everywhere as a stroke over an area
 * struck off the list. So a report keeps the map it was painted on for life — its payload says which,
 * as [schema] — and never moves to a later one, even when it is corrected or painted on again. Nothing
 * stored is ever rewritten for a new map: the payload is part of the row's identity in a backup. The
 * rule that reads strokes is part of a map as much as its patches are — the thresholds and the largest
 * share ([paintedRegions]) — and a change to it has to leave every map that has entries reading them
 * as before: the largest share reckoned by family is the plain largest area on the first map.
 *
 * [V1] is the first map: twenty-three areas, eighteen of them on the plates. [V2] carves the coarse
 * ones into the places a pain diary and a doctor tell apart — the centre of the chest, the belly in
 * six, the loin, the shoulder blade, the back of the knee, the groin, the heel — and closes the gaps
 * and overlaps the first left between its patches.
 */
enum class PainMapVersion(val schema: Int) {
    V1(1),
    V2(2),
    ;

    companion object {
        /** The map a new entry is painted on. */
        val CURRENT: PainMapVersion = V2

        /**
         * The map a payload of [schema] was painted on: the first for a payload without one, and the
         * latest this build has for one written by a later version than it knows. Saved again here,
         * such an entry is rewritten on that map, without the areas this build cannot name — so a later
         * map is to come with a backup schema of its own, which an older build refuses to restore
         * ([isSupportedBackupSchema]).
         */
        fun ofSchema(schema: Int): PainMapVersion = entries.lastOrNull { it.schema <= schema } ?: V1
    }
}

/**
 * Where it hurts, at the resolution a pain diary can honestly record.
 *
 * Built on the Nordic Musculoskeletal Questionnaire's nine areas (Kuorinka 1987) — neck, shoulders,
 * upper back, elbows, wrists and hands, lower back, hips, knees, ankles and feet — with the head
 * split into the parts headaches are told apart by, and the muscle bellies between the joints, so
 * soreness after training can be matched to the group a workout worked.
 *
 * Deliberately not [Muscle]: that enum means "what an exercise works", and a knee or a temple added
 * to it would start appearing as worked muscles in the catalogue. [muscle] is the link the other
 * way — the group under this area, where there is one. The bones and tendons — the breastbone, the
 * base of the spine, the tailbone, the Achilles tendon, the heel — have none, so a pain there is never
 * read as soreness after training.
 *
 * [joint] marks the areas centred on a joint, which is one of the things that makes a pain an
 * unusual picture for soreness after training.
 *
 * [parent] is the area of the first map an area of the second was carved from ([PainMapVersion]),
 * null for the first map's own. An area once carved keeps its name and its words as what is left of
 * it — "Knee" is the front of the knee on the second map, beside "Back of the knee" — and stays the
 * family's name ([root]), which is how an entry on one map finds the same pain on the other. An
 * area's name is what the payload stores, so none is ever renamed or removed, only added, each in its
 * place from head to foot. A carved area takes its parent's [group], so the statistics pool it as
 * before — but for the base of the spine and the tailbone: the first map read them as the buttock,
 * and they are the lower back's, so from the second map on they count there and the buttock's series
 * loses them.
 *
 * The head's areas have no patch on the two body plates, which stop at the neck: they are chosen from
 * the list of areas, and an entry whose strokes lie on the head above the plates keeps them. So are
 * the fingers and the sole of the foot, which no plate shows.
 */
enum class PainRegion(
    val labelRes: Int,
    val paired: Boolean,
    val joint: Boolean,
    val muscle: Muscle?,
    val group: PainGroup,
    val parent: PainRegion? = null,
) {
    HEAD_FRONT(R.string.pain_region_head_front, false, false, null, PainGroup.HEAD),
    TEMPLE(R.string.pain_region_temple, true, false, null, PainGroup.HEAD),
    HEAD_TOP(R.string.pain_region_head_top, false, false, null, PainGroup.HEAD),
    HEAD_BACK(R.string.pain_region_head_back, false, false, null, PainGroup.HEAD),
    JAW(R.string.pain_region_jaw, true, false, null, PainGroup.HEAD),
    NECK(R.string.pain_region_neck, false, false, null, PainGroup.NECK_SHOULDERS),
    SHOULDER(R.string.pain_region_shoulder, true, true, Muscle.SHOULDERS, PainGroup.NECK_SHOULDERS),

    /**
     * The ridge between the neck and the shoulder: the trapezius, which the catalogue counts as back,
     * and no joint. Carved from the shoulder, though its patches also take the top of the chest above
     * the collarbone in front and the top of the upper back behind — so a pain here finds an earlier
     * one the first map recorded as the shoulder ([earlierPainAmong]), and not one it recorded on the
     * same ridge as the upper back or the neck, as it did for a dab from behind with the figure whole.
     */
    TRAPEZIUS(R.string.pain_region_trapezius, true, false, Muscle.BACK, PainGroup.NECK_SHOULDERS, SHOULDER),
    CHEST(R.string.pain_region_chest, true, false, Muscle.CHEST, PainGroup.CHEST_ABDOMEN),

    /**
     * The centre of the chest. The first map splits it down the axis between its two sides, so a dab
     * on it says one side of the chest; the safety line fires for it as for the chest it is carved
     * from ([painNeedsSafetyLine]).
     */
    STERNUM(R.string.pain_region_sternum, false, false, null, PainGroup.CHEST_ABDOMEN, CHEST),
    UPPER_BACK(R.string.pain_region_upper_back, false, false, Muscle.BACK, PainGroup.NECK_SHOULDERS),
    SHOULDER_BLADE(
        R.string.pain_region_shoulder_blade,
        true,
        false,
        Muscle.BACK,
        PainGroup.NECK_SHOULDERS,
        UPPER_BACK,
    ),
    LOWER_BACK(R.string.pain_region_lower_back, false, false, Muscle.BACK, PainGroup.LOWER_BACK),

    /** The loin, on its side: what is left of the lower back on the second map is the strip over the spine. */
    FLANK(R.string.pain_region_flank, true, false, Muscle.BACK, PainGroup.LOWER_BACK, LOWER_BACK),

    /** Carved whole on the second map, into the six areas after it: it has no patch there, only theirs. */
    ABDOMEN(R.string.pain_region_abdomen, false, false, Muscle.CORE, PainGroup.CHEST_ABDOMEN),
    UPPER_BELLY(R.string.pain_region_upper_belly, false, false, Muscle.CORE, PainGroup.CHEST_ABDOMEN, ABDOMEN),
    UNDER_RIBS(R.string.pain_region_under_ribs, true, false, Muscle.CORE, PainGroup.CHEST_ABDOMEN, ABDOMEN),
    LOWER_BELLY(R.string.pain_region_lower_belly, false, false, Muscle.CORE, PainGroup.CHEST_ABDOMEN, ABDOMEN),
    LOWER_SIDE(R.string.pain_region_lower_side, true, false, Muscle.CORE, PainGroup.CHEST_ABDOMEN, ABDOMEN),
    UPPER_ARM(R.string.pain_region_upper_arm, true, false, Muscle.ARMS, PainGroup.ARMS_HANDS),

    /** The upper arm seen from behind; "Upper arm" is its front on the second map. */
    BACK_OF_UPPER_ARM(
        R.string.pain_region_back_of_upper_arm,
        true,
        false,
        Muscle.ARMS,
        PainGroup.ARMS_HANDS,
        UPPER_ARM,
    ),
    ELBOW(R.string.pain_region_elbow, true, true, Muscle.ARMS, PainGroup.ARMS_HANDS),

    /** The inner half of the elbow on both views; "Elbow" is its outer half on the second map. */
    ELBOW_INNER(R.string.pain_region_elbow_inner, true, true, Muscle.ARMS, PainGroup.ARMS_HANDS, ELBOW),
    FOREARM(R.string.pain_region_forearm, true, false, Muscle.ARMS, PainGroup.ARMS_HANDS),

    /** The forearm seen from behind; "Forearm" is its front on the second map. */
    BACK_OF_FOREARM(
        R.string.pain_region_back_of_forearm,
        true,
        false,
        Muscle.ARMS,
        PainGroup.ARMS_HANDS,
        FOREARM,
    ),
    WRIST_HAND(R.string.pain_region_wrist_hand, true, true, Muscle.ARMS, PainGroup.ARMS_HANDS),

    /** Chosen from the list only: the plates cut the hands off at the palm. */
    FINGERS(R.string.pain_region_fingers, true, true, Muscle.ARMS, PainGroup.ARMS_HANDS, WRIST_HAND),
    HIP(R.string.pain_region_hip, true, true, Muscle.GLUTES, PainGroup.HIPS_LEGS),

    /**
     * The groin: the front of the hip on the first map, whose outer strip is what is left of the hip.
     * A joint, as the hip was, so a pain there after training is never taken for soreness; over the
     * hip flexors and the top of the adductors, which a leg session works, where the hip's muscle is
     * the buttock's.
     */
    GROIN(R.string.pain_region_groin, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS, HIP),
    GLUTE(R.string.pain_region_glute, true, false, Muscle.GLUTES, PainGroup.HIPS_LEGS),

    /**
     * The base of the spine, carved from the top of the two buttocks, in halves either side of the
     * axis: the sacroiliac joints under it hurt on one side, and a dab on the middle of it is split
     * between the two halves and marks both, as one on the middle of anything paired does. The
     * lower back's group, not the buttock's: see [PainRegion].
     */
    SACRUM(R.string.pain_region_sacrum, true, true, null, PainGroup.LOWER_BACK, GLUTE),

    /** The tailbone, at the top of the cleft between the buttocks: one bone on the axis, so no side. */
    TAILBONE(R.string.pain_region_tailbone, false, false, null, PainGroup.LOWER_BACK, GLUTE),
    THIGH_FRONT(R.string.pain_region_thigh_front, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS),
    INNER_THIGH(R.string.pain_region_inner_thigh, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS, THIGH_FRONT),
    THIGH_BACK(R.string.pain_region_thigh_back, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS),
    KNEE(R.string.pain_region_knee, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS),

    /** The hollow behind the knee; "Knee" is the front of it on the second map. */
    BACK_OF_KNEE(R.string.pain_region_back_of_knee, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS, KNEE),
    SHIN(R.string.pain_region_shin, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS),
    CALF(R.string.pain_region_calf, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS),

    /** Carved whole on the second map, into the six areas after it. */
    ANKLE_FOOT(R.string.pain_region_ankle_foot, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS),
    ANKLE(R.string.pain_region_ankle, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS, ANKLE_FOOT),
    ACHILLES(R.string.pain_region_achilles, true, false, null, PainGroup.HIPS_LEGS, ANKLE_FOOT),
    HEEL(R.string.pain_region_heel, true, false, null, PainGroup.HIPS_LEGS, ANKLE_FOOT),
    TOP_OF_FOOT(R.string.pain_region_top_of_foot, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS, ANKLE_FOOT),

    /** Chosen from the list only: no plate shows the sole. */
    SOLE(R.string.pain_region_sole, true, false, Muscle.LEGS, PainGroup.HIPS_LEGS, ANKLE_FOOT),
    TOES(R.string.pain_region_toes, true, true, Muscle.LEGS, PainGroup.HIPS_LEGS, ANKLE_FOOT),
}

/**
 * One area on one side — what a pain entry actually records, written "KNEE:L".
 *
 * A paired area is always LEFT or RIGHT and an unpaired one always CENTRE; [parse] refuses any other
 * combination rather than guess, so a hand-edited or future payload cannot put a left spine in the
 * record.
 */
data class RegionSide(val region: PainRegion, val side: BodySide) {
    val code: String get() = "${region.name}:${side.code}"

    companion object {
        fun parse(code: String): RegionSide? {
            val parts = code.split(':').takeIf { it.size == 2 }
            val region = parts?.let { PainRegion.entries.firstOrNull { it.name == parts[0] } }
            val side = parts?.let { BodySide.entries.firstOrNull { it.code == parts[1] } }
            return if (region == null || side == null) null else RegionSide(region, side).takeIf { it.valid }
        }

        /** Every area on every side it has, in the order the lists show them. */
        val ALL: List<RegionSide> = PainRegion.entries.flatMap { region ->
            if (region.paired) {
                listOf(RegionSide(region, BodySide.LEFT), RegionSide(region, BodySide.RIGHT))
            } else {
                listOf(RegionSide(region, BodySide.CENTRE))
            }
        }
    }

    private val valid: Boolean get() = region.paired == (side != BodySide.CENTRE)
}

/** Lists and payloads name areas head to foot, and a pair's left before its right. */
val REGION_SIDE_ORDER: Comparator<RegionSide> = compareBy({ it.region.ordinal }, { it.side.ordinal })

/**
 * The first map's area this one belongs to: itself for each of the first map's own, the area it was
 * carved from for one the second map added. Families are what an entry on one map and an entry on
 * the other can be compared by — a back of the knee painted today and a knee painted last month.
 */
val PainRegion.root: PainRegion get() = parent?.root ?: this

/**
 * Whether [other] can be the same place as this one, told on the same map or on another: an area of
 * the same family ([root]) on a side that agrees — the same side, or either of the two the centre,
 * which has none. A left knee is never a right knee, but the back of the left knee is the left knee as
 * the first map recorded it; the centre of the chest is whichever side of the chest the first map had
 * to put it on; a left loin is the lower back the first map gave no side to. Within one family
 * neighbours find each other — the upper and the lower belly — as they did on the first map, where
 * they were one area.
 */
fun RegionSide.sameFamilyAs(other: RegionSide): Boolean =
    region.root == other.region.root &&
        (side == other.side || side == BodySide.CENTRE || other.side == BodySide.CENTRE)

/**
 * The first map's areas this one is a part of, each on its side as the first map had it: itself, for
 * one of the first map's own; for one carved out of a paired area, that area on the same side — the
 * back of the left knee is the left knee — or on both sides for the centre of the chest and the
 * tailbone, which the first map cut down the axis; and for one carved out of an unpaired area, that
 * area — a left loin was the lower back. What a dab is first reckoned by ([paintedRegions]).
 */
internal val RegionSide.firstMapAreas: List<RegionSide>
    get() {
        val family = region.root
        return when {
            family == region -> listOf(this)
            !family.paired -> listOf(RegionSide(family, BodySide.CENTRE))
            side == BodySide.CENTRE -> listOf(RegionSide(family, BodySide.LEFT), RegionSide(family, BodySide.RIGHT))
            else -> listOf(RegionSide(family, side))
        }
    }

/**
 * The person's side of a point on a plate, from where it sits in the picture.
 *
 * FRONT: the figure faces the viewer, so the left half of the picture is the person's RIGHT.
 * BACK: the viewer stands behind the figure, so the left half of the picture is the person's LEFT.
 */
fun sideOf(view: PainView, x: Float): BodySide = when (view) {
    PainView.FRONT -> if (x < 0.5f) BodySide.RIGHT else BodySide.LEFT
    PainView.BACK -> if (x < 0.5f) BodySide.LEFT else BodySide.RIGHT
}

/**
 * Where the figure starts, in plate heights: the top of the plate. The photographs stop at the neck,
 * and the head's areas are chosen from the list rather than painted.
 */
const val FIGURE_TOP = 0f

/** Where it ends: the foot of the plate. */
const val FIGURE_BOTTOM = 1f

/** How tall the figure is, in plate heights. */
const val FIGURE_HEIGHT = FIGURE_BOTTOM - FIGURE_TOP

/**
 * The plates' width over their height — 423 by 900 pixels for the man, 422 for the woman. The
 * figure's x is in plate widths and its y in plate heights, the way the workout map's masks are
 * written, so a distance is measured after x has been scaled by this.
 */
const val PLATE_ASPECT = 423f / 900f

/**
 * One area's mask on one view, in the plate's own 0..1 space.
 *
 * A paired area is written once, for the half of the picture on the viewer's left, and [points]
 * of its twin are mirrored across the figure's axis by [sidePatches] — the same rule the workout
 * map's masks follow, so the two sides cannot drift apart.
 */
internal class RegionPatch(
    val region: PainRegion,
    val view: PainView,
    val points: List<Offset>,
) {
    constructor(region: PainRegion, view: PainView, vararg pts: Float) :
        this(region, view, pts.toList().chunked(2).map { Offset(it[0], it[1]) })
}

/** A mask with its side settled: what the hit test and the lit pass work with. */
internal class SidePatch(val area: RegionSide, val view: PainView, val points: List<Offset>)

/**
 * The first map's patches, both plates, both sexes — frozen: every entry painted on the first map is
 * hit-tested on exactly these for as long as it is kept ([PainMapVersion]).
 *
 * Traced against the union of the man's and the woman's outlines, each mirrored onto the other half,
 * with a margin of three plate pixels, so one table fits both plates. The man's back plate cuts his
 * left forearm at the frame; the mirrored patch still covers where it would be. The hand is cut by the
 * frame on every plate, so [PainRegion.WRIST_HAND] covers the wrist and the stub of palm that is in
 * the picture.
 *
 * Its borders were meant to be one line shared by both sides, and seven were not: a hole under each
 * collarbone and one at the pubis, a notch in each armpit from behind, a sliver between the thighs on
 * both views, and the shoulder overlapping the chest in front and the upper back behind. A dab there
 * marks nothing, or two areas where one was meant. They are kept as they were, for the entries painted
 * on them; the second map closes them.
 */
private val BODY_PATCHES_V1 = listOf(
    RegionPatch(
        PainRegion.NECK, PainView.FRONT,
        0.404f, 0.005f, 0.397f, 0.025f, 0.390f, 0.030f, 0.390f, 0.060f,
        0.610f, 0.060f, 0.610f, 0.030f, 0.603f, 0.025f, 0.596f, 0.005f,
    ),
    RegionPatch(
        PainRegion.SHOULDER, PainView.FRONT,
        0.374f, 0.035f, 0.343f, 0.045f, 0.206f, 0.070f, 0.173f, 0.080f,
        0.147f, 0.095f, 0.132f, 0.110f, 0.125f, 0.125f, 0.128f, 0.155f,
        0.300f, 0.155f, 0.300f, 0.060f, 0.390f, 0.055f, 0.390f, 0.035f,
    ),
    RegionPatch(
        PainRegion.CHEST, PainView.FRONT,
        0.300f, 0.060f, 0.300f, 0.150f, 0.270f, 0.155f, 0.270f, 0.195f,
        0.500f, 0.195f, 0.500f, 0.060f,
    ),
    RegionPatch(
        PainRegion.UPPER_ARM, PainView.FRONT,
        0.128f, 0.155f, 0.102f, 0.180f, 0.080f, 0.210f, 0.073f, 0.230f,
        0.052f, 0.245f, 0.239f, 0.245f, 0.270f, 0.215f, 0.270f, 0.155f,
    ),
    RegionPatch(
        PainRegion.ELBOW, PainView.FRONT,
        0.052f, 0.245f, 0.017f, 0.270f, 0.002f, 0.290f, 0.206f, 0.290f,
        0.225f, 0.255f, 0.239f, 0.245f,
    ),
    RegionPatch(
        PainRegion.FOREARM, PainView.FRONT,
        0.002f, 0.290f, 0.000f, 0.385f, 0.069f, 0.385f, 0.102f, 0.360f,
        0.168f, 0.325f, 0.194f, 0.305f, 0.206f, 0.290f,
    ),
    RegionPatch(
        PainRegion.WRIST_HAND, PainView.FRONT,
        0.000f, 0.385f, 0.000f, 0.490f, 0.014f, 0.490f, 0.050f, 0.430f,
        0.069f, 0.385f,
    ),
    RegionPatch(
        PainRegion.ABDOMEN, PainView.FRONT,
        0.270f, 0.195f, 0.270f, 0.220f, 0.291f, 0.240f, 0.303f, 0.265f,
        0.291f, 0.300f, 0.286f, 0.325f, 0.290f, 0.330f, 0.490f, 0.430f,
        0.510f, 0.430f, 0.710f, 0.330f, 0.714f, 0.325f, 0.709f, 0.300f,
        0.697f, 0.265f, 0.709f, 0.240f, 0.730f, 0.220f, 0.730f, 0.195f,
    ),
    // The front of the hip is the groin: the band under the fold of the abdomen, down to where
    // the legs part.
    RegionPatch(
        PainRegion.HIP, PainView.FRONT,
        0.279f, 0.330f, 0.241f, 0.390f, 0.236f, 0.410f, 0.217f, 0.445f,
        0.500f, 0.445f, 0.500f, 0.435f, 0.290f, 0.330f,
    ),
    RegionPatch(
        PainRegion.THIGH_FRONT, PainView.FRONT,
        0.217f, 0.445f, 0.203f, 0.485f, 0.201f, 0.515f, 0.206f, 0.540f,
        0.225f, 0.570f, 0.463f, 0.570f, 0.463f, 0.540f, 0.499f, 0.490f,
        0.500f, 0.445f,
    ),
    RegionPatch(
        PainRegion.KNEE, PainView.FRONT,
        0.225f, 0.570f, 0.236f, 0.585f, 0.236f, 0.640f, 0.220f, 0.665f,
        0.416f, 0.665f, 0.444f, 0.625f, 0.463f, 0.570f,
    ),
    RegionPatch(
        PainRegion.SHIN, PainView.FRONT,
        0.220f, 0.665f, 0.194f, 0.695f, 0.187f, 0.725f, 0.203f, 0.755f,
        0.206f, 0.780f, 0.222f, 0.805f, 0.239f, 0.855f, 0.376f, 0.855f,
        0.381f, 0.825f, 0.402f, 0.775f, 0.430f, 0.750f, 0.437f, 0.735f,
        0.437f, 0.715f, 0.416f, 0.675f, 0.416f, 0.665f,
    ),
    RegionPatch(
        PainRegion.ANKLE_FOOT, PainView.FRONT,
        0.239f, 0.855f, 0.227f, 0.895f, 0.215f, 0.915f, 0.199f, 0.930f,
        0.156f, 0.955f, 0.144f, 0.970f, 0.154f, 0.985f, 0.201f, 0.995f,
        0.355f, 0.995f, 0.371f, 0.970f, 0.369f, 0.945f, 0.390f, 0.930f,
        0.388f, 0.890f, 0.376f, 0.855f,
    ),
    RegionPatch(
        PainRegion.NECK, PainView.BACK,
        0.442f, 0.005f, 0.414f, 0.015f, 0.414f, 0.025f, 0.390f, 0.035f,
        0.390f, 0.050f, 0.610f, 0.050f, 0.610f, 0.035f, 0.586f, 0.025f,
        0.586f, 0.015f, 0.558f, 0.005f,
    ),
    RegionPatch(
        PainRegion.SHOULDER, PainView.BACK,
        0.376f, 0.040f, 0.274f, 0.065f, 0.213f, 0.075f, 0.184f, 0.085f,
        0.165f, 0.095f, 0.144f, 0.115f, 0.137f, 0.130f, 0.137f, 0.155f,
        0.290f, 0.155f, 0.290f, 0.065f, 0.390f, 0.055f, 0.390f, 0.040f,
    ),
    RegionPatch(
        PainRegion.UPPER_BACK, PainView.BACK,
        0.390f, 0.050f, 0.390f, 0.055f, 0.298f, 0.060f, 0.290f, 0.065f,
        0.290f, 0.150f, 0.270f, 0.155f, 0.270f, 0.210f, 0.298f, 0.235f,
        0.300f, 0.245f, 0.700f, 0.245f, 0.702f, 0.235f, 0.730f, 0.210f,
        0.730f, 0.155f, 0.710f, 0.150f, 0.710f, 0.065f, 0.702f, 0.060f,
        0.610f, 0.055f, 0.610f, 0.050f,
    ),
    RegionPatch(
        PainRegion.UPPER_ARM, PainView.BACK,
        0.137f, 0.155f, 0.139f, 0.165f, 0.109f, 0.190f, 0.092f, 0.225f,
        0.064f, 0.245f, 0.248f, 0.245f, 0.274f, 0.225f, 0.270f, 0.220f,
        0.270f, 0.155f,
    ),
    RegionPatch(
        PainRegion.ELBOW, PainView.BACK,
        0.064f, 0.245f, 0.014f, 0.275f, 0.002f, 0.290f, 0.220f, 0.290f,
        0.248f, 0.245f,
    ),
    RegionPatch(
        PainRegion.FOREARM, PainView.BACK,
        0.002f, 0.290f, 0.000f, 0.390f, 0.078f, 0.390f, 0.125f, 0.355f,
        0.189f, 0.320f, 0.220f, 0.290f,
    ),
    RegionPatch(
        PainRegion.WRIST_HAND, PainView.BACK,
        0.000f, 0.390f, 0.000f, 0.480f, 0.019f, 0.480f, 0.052f, 0.445f,
        0.069f, 0.415f, 0.069f, 0.400f, 0.078f, 0.390f,
    ),
    RegionPatch(
        PainRegion.LOWER_BACK, PainView.BACK,
        0.300f, 0.245f, 0.307f, 0.270f, 0.291f, 0.325f, 0.709f, 0.325f,
        0.693f, 0.270f, 0.700f, 0.245f,
    ),
    // From behind, the hip is the outer strip beside the buttock, over the joint's bony point.
    RegionPatch(
        PainRegion.HIP, PainView.BACK,
        0.291f, 0.325f, 0.217f, 0.465f, 0.300f, 0.465f, 0.300f, 0.325f,
    ),
    RegionPatch(
        PainRegion.GLUTE, PainView.BACK,
        0.300f, 0.325f, 0.300f, 0.465f, 0.500f, 0.465f, 0.500f, 0.325f,
    ),
    RegionPatch(
        PainRegion.THIGH_BACK, PainView.BACK,
        0.217f, 0.465f, 0.208f, 0.520f, 0.225f, 0.565f, 0.246f, 0.595f,
        0.459f, 0.595f, 0.499f, 0.500f, 0.500f, 0.465f,
    ),
    RegionPatch(
        PainRegion.KNEE, PainView.BACK,
        0.246f, 0.595f, 0.253f, 0.605f, 0.241f, 0.645f, 0.442f, 0.645f,
        0.459f, 0.595f,
    ),
    RegionPatch(
        PainRegion.CALF, PainView.BACK,
        0.241f, 0.645f, 0.187f, 0.720f, 0.187f, 0.735f, 0.225f, 0.815f,
        0.232f, 0.865f, 0.376f, 0.865f, 0.385f, 0.835f, 0.395f, 0.825f,
        0.416f, 0.775f, 0.437f, 0.760f, 0.444f, 0.745f, 0.444f, 0.730f,
        0.421f, 0.690f, 0.426f, 0.670f, 0.442f, 0.645f,
    ),
    RegionPatch(
        PainRegion.ANKLE_FOOT, PainView.BACK,
        0.232f, 0.865f, 0.225f, 0.925f, 0.187f, 0.945f, 0.156f, 0.955f,
        0.149f, 0.965f, 0.156f, 0.970f, 0.213f, 0.980f, 0.239f, 0.990f,
        0.362f, 0.990f, 0.378f, 0.985f, 0.383f, 0.975f, 0.376f, 0.950f,
        0.385f, 0.910f, 0.374f, 0.890f, 0.376f, 0.865f,
    ),
)

/**
 * The second map's patches, traced as the first were — against the union of both sexes' outlines,
 * mirrored, three plate pixels out — and checked on each of the four plates: they cover every body
 * pixel the first map's did and the rims it left, the top of the neck and the soles; no pixel is in
 * two patches; and where two patches meet, both list the same points, so every border between them is
 * one line. A paired patch is written for the viewer's left half, and an unpaired one is symmetric
 * about the axis and wider than a column of the hit grid either side of it, or the mirrored grid would
 * give it no cell.
 *
 * What was coarse on the first map is cut where a body map's areas are cut: the chest from the
 * shoulder along the groove between the deltoid and the breast muscle, and down the breastbone; the
 * ridge above the collarbone from the shoulder; the belly into six; the back into the shoulder blades,
 * the strip between them, and the loins either side of the lumbar spine; the buttock into the base of
 * the spine and the tailbone; the knee, the upper arm and the forearm into their front and back, the
 * elbow into its inner and outer halves, the hip into the groin and its outer side, the inner thigh
 * from the front of it; the foot into its parts. An area carved whole — the abdomen, the ankle and
 * foot — has no patch of its own here, only its children's ([litOn]).
 */
private val BODY_PATCHES_V2 = listOf(
    // The neck, from the top of the plate to the line of the collarbones.
    RegionPatch(
        PainRegion.NECK, PainView.FRONT,
        0.404f, 0.000f, 0.397f, 0.025f, 0.390f, 0.030f, 0.390f, 0.033f,
        0.390f, 0.068f, 0.475f, 0.068f, 0.525f, 0.068f, 0.610f, 0.068f,
        0.610f, 0.033f, 0.610f, 0.030f, 0.603f, 0.025f, 0.596f, 0.000f,
    ),
    // The slope between the neck and the shoulder, above the collarbone.
    RegionPatch(
        PainRegion.TRAPEZIUS, PainView.FRONT,
        0.390f, 0.033f, 0.374f, 0.035f, 0.343f, 0.045f, 0.300f, 0.053f,
        0.300f, 0.075f, 0.340f, 0.072f, 0.390f, 0.068f,
    ),
    // The deltoid, inside it the groove it shares with the chest and the front fold of the armpit.
    RegionPatch(
        PainRegion.SHOULDER, PainView.FRONT,
        0.300f, 0.053f, 0.206f, 0.070f, 0.173f, 0.080f, 0.147f, 0.095f,
        0.132f, 0.110f, 0.125f, 0.125f, 0.128f, 0.155f, 0.270f, 0.155f,
        0.245f, 0.132f, 0.340f, 0.072f, 0.300f, 0.075f,
    ),
    RegionPatch(
        PainRegion.CHEST, PainView.FRONT,
        0.475f, 0.068f, 0.390f, 0.068f, 0.340f, 0.072f, 0.245f, 0.132f,
        0.270f, 0.155f, 0.270f, 0.195f, 0.400f, 0.195f, 0.475f, 0.195f,
    ),
    // The breastbone, wider than a dab is uncertain: the man's is drawn a little right of the axis.
    RegionPatch(
        PainRegion.STERNUM, PainView.FRONT,
        0.475f, 0.068f, 0.475f, 0.195f, 0.525f, 0.195f, 0.525f, 0.068f,
    ),
    RegionPatch(
        PainRegion.UPPER_ARM, PainView.FRONT,
        0.128f, 0.155f, 0.102f, 0.180f, 0.080f, 0.210f, 0.073f, 0.230f,
        0.052f, 0.245f, 0.120f, 0.245f, 0.239f, 0.245f, 0.270f, 0.220f,
        0.270f, 0.195f, 0.270f, 0.155f,
    ),
    // The outer half of the elbow, toward the frame: the arms hang with the palms forward.
    RegionPatch(
        PainRegion.ELBOW, PainView.FRONT,
        0.052f, 0.245f, 0.017f, 0.270f, 0.000f, 0.290f, 0.120f, 0.290f,
        0.120f, 0.245f,
    ),
    RegionPatch(
        PainRegion.ELBOW_INNER, PainView.FRONT,
        0.120f, 0.245f, 0.120f, 0.290f, 0.206f, 0.290f, 0.225f, 0.255f,
        0.239f, 0.245f,
    ),
    RegionPatch(
        PainRegion.FOREARM, PainView.FRONT,
        0.000f, 0.290f, 0.000f, 0.385f, 0.069f, 0.385f, 0.102f, 0.360f,
        0.168f, 0.325f, 0.194f, 0.305f, 0.206f, 0.290f, 0.120f, 0.290f,
    ),
    RegionPatch(
        PainRegion.WRIST_HAND, PainView.FRONT,
        0.000f, 0.385f, 0.000f, 0.495f, 0.011f, 0.495f, 0.050f, 0.430f,
        0.069f, 0.385f,
    ),
    // The belly in six: under the ribs either side of the upper belly, the lower sides either side of
    // the lower belly. The lines are the edges of the six-pack, and one above the navel so a dab on
    // the navel is the lower belly's.
    RegionPatch(
        PainRegion.UNDER_RIBS, PainView.FRONT,
        0.270f, 0.195f, 0.270f, 0.220f, 0.291f, 0.240f, 0.303f, 0.265f,
        0.295f, 0.290f, 0.400f, 0.290f, 0.400f, 0.195f,
    ),
    RegionPatch(
        PainRegion.UPPER_BELLY, PainView.FRONT,
        0.400f, 0.195f, 0.400f, 0.290f, 0.600f, 0.290f, 0.600f, 0.195f,
        0.525f, 0.195f, 0.475f, 0.195f,
    ),
    RegionPatch(
        PainRegion.LOWER_SIDE, PainView.FRONT,
        0.295f, 0.290f, 0.291f, 0.300f, 0.286f, 0.325f, 0.290f, 0.330f,
        0.300f, 0.335f, 0.400f, 0.385f, 0.400f, 0.290f,
    ),
    RegionPatch(
        PainRegion.LOWER_BELLY, PainView.FRONT,
        0.400f, 0.290f, 0.400f, 0.385f, 0.500f, 0.435f, 0.600f, 0.385f,
        0.600f, 0.290f,
    ),
    // From the front the hip is the outer strip beside the groin, the groin the band under the fold of
    // the belly down to where the legs part.
    RegionPatch(
        PainRegion.HIP, PainView.FRONT,
        0.279f, 0.330f, 0.241f, 0.390f, 0.236f, 0.410f, 0.217f, 0.445f,
        0.300f, 0.445f, 0.300f, 0.335f, 0.290f, 0.330f,
    ),
    RegionPatch(
        PainRegion.GROIN, PainView.FRONT,
        0.300f, 0.335f, 0.300f, 0.445f, 0.400f, 0.445f, 0.500f, 0.445f,
        0.500f, 0.435f, 0.400f, 0.385f,
    ),
    RegionPatch(
        PainRegion.THIGH_FRONT, PainView.FRONT,
        0.217f, 0.445f, 0.203f, 0.485f, 0.201f, 0.515f, 0.206f, 0.540f,
        0.225f, 0.570f, 0.463f, 0.570f, 0.463f, 0.540f, 0.400f, 0.540f,
        0.400f, 0.445f, 0.300f, 0.445f,
    ),
    // The two inner thighs meet on the axis down to the crotch.
    RegionPatch(
        PainRegion.INNER_THIGH, PainView.FRONT,
        0.400f, 0.445f, 0.400f, 0.540f, 0.463f, 0.540f, 0.500f, 0.490f,
        0.500f, 0.445f,
    ),
    RegionPatch(
        PainRegion.KNEE, PainView.FRONT,
        0.225f, 0.570f, 0.236f, 0.585f, 0.236f, 0.640f, 0.220f, 0.665f,
        0.416f, 0.665f, 0.444f, 0.625f, 0.463f, 0.570f,
    ),
    RegionPatch(
        PainRegion.SHIN, PainView.FRONT,
        0.220f, 0.665f, 0.194f, 0.695f, 0.187f, 0.725f, 0.203f, 0.755f,
        0.206f, 0.780f, 0.222f, 0.805f, 0.239f, 0.855f, 0.376f, 0.855f,
        0.381f, 0.825f, 0.402f, 0.775f, 0.430f, 0.750f, 0.437f, 0.735f,
        0.437f, 0.715f, 0.416f, 0.675f, 0.416f, 0.665f,
    ),
    // The foot in three: the ankle, the top of the foot, the toes.
    RegionPatch(
        PainRegion.ANKLE, PainView.FRONT,
        0.239f, 0.855f, 0.227f, 0.895f, 0.221f, 0.905f, 0.389f, 0.905f,
        0.388f, 0.890f, 0.376f, 0.855f,
    ),
    RegionPatch(
        PainRegion.TOP_OF_FOOT, PainView.FRONT,
        0.221f, 0.905f, 0.215f, 0.915f, 0.199f, 0.930f, 0.164f, 0.950f,
        0.370f, 0.950f, 0.369f, 0.945f, 0.390f, 0.930f, 0.389f, 0.905f,
    ),
    RegionPatch(
        PainRegion.TOES, PainView.FRONT,
        0.164f, 0.950f, 0.156f, 0.955f, 0.144f, 0.970f, 0.154f, 0.985f,
        0.201f, 1.000f, 0.355f, 1.000f, 0.371f, 0.970f, 0.370f, 0.950f,
    ),
    RegionPatch(
        PainRegion.NECK, PainView.BACK,
        0.442f, 0.000f, 0.414f, 0.015f, 0.414f, 0.025f, 0.390f, 0.035f,
        0.390f, 0.038f, 0.390f, 0.050f, 0.430f, 0.050f, 0.570f, 0.050f,
        0.610f, 0.050f, 0.610f, 0.038f, 0.610f, 0.035f, 0.586f, 0.025f,
        0.586f, 0.015f, 0.558f, 0.000f,
    ),
    // The ridge from the neck to the shoulder: down to the spine of the shoulder blade where it meets
    // the shoulder, and level above the blade nearer the spine.
    RegionPatch(
        PainRegion.TRAPEZIUS, PainView.BACK,
        0.390f, 0.038f, 0.376f, 0.040f, 0.290f, 0.061f, 0.290f, 0.075f,
        0.430f, 0.075f, 0.430f, 0.050f, 0.390f, 0.050f,
    ),
    RegionPatch(
        PainRegion.SHOULDER, PainView.BACK,
        0.290f, 0.061f, 0.274f, 0.065f, 0.213f, 0.075f, 0.184f, 0.085f,
        0.165f, 0.095f, 0.144f, 0.115f, 0.137f, 0.130f, 0.137f, 0.155f,
        0.270f, 0.155f, 0.290f, 0.155f, 0.290f, 0.075f,
    ),
    RegionPatch(
        PainRegion.SHOULDER_BLADE, PainView.BACK,
        0.290f, 0.075f, 0.290f, 0.155f, 0.270f, 0.155f, 0.270f, 0.185f,
        0.430f, 0.185f, 0.430f, 0.075f,
    ),
    // Between the shoulder blades, and the band under them across the back.
    RegionPatch(
        PainRegion.UPPER_BACK, PainView.BACK,
        0.430f, 0.050f, 0.430f, 0.075f, 0.430f, 0.185f, 0.270f, 0.185f,
        0.270f, 0.220f, 0.274f, 0.225f, 0.298f, 0.235f, 0.300f, 0.245f,
        0.420f, 0.245f, 0.580f, 0.245f, 0.700f, 0.245f, 0.702f, 0.235f,
        0.726f, 0.225f, 0.730f, 0.220f, 0.730f, 0.185f, 0.570f, 0.185f,
        0.570f, 0.075f, 0.570f, 0.050f,
    ),
    RegionPatch(
        PainRegion.BACK_OF_UPPER_ARM, PainView.BACK,
        0.137f, 0.155f, 0.139f, 0.165f, 0.109f, 0.190f, 0.092f, 0.225f,
        0.064f, 0.245f, 0.130f, 0.245f, 0.248f, 0.245f, 0.274f, 0.225f,
        0.270f, 0.220f, 0.270f, 0.185f, 0.270f, 0.155f,
    ),
    RegionPatch(
        PainRegion.ELBOW, PainView.BACK,
        0.064f, 0.245f, 0.014f, 0.275f, 0.000f, 0.290f, 0.130f, 0.290f,
        0.130f, 0.245f,
    ),
    RegionPatch(
        PainRegion.ELBOW_INNER, PainView.BACK,
        0.130f, 0.245f, 0.130f, 0.290f, 0.220f, 0.290f, 0.248f, 0.245f,
    ),
    RegionPatch(
        PainRegion.BACK_OF_FOREARM, PainView.BACK,
        0.000f, 0.290f, 0.000f, 0.390f, 0.078f, 0.390f, 0.125f, 0.355f,
        0.189f, 0.320f, 0.220f, 0.290f, 0.130f, 0.290f,
    ),
    RegionPatch(
        PainRegion.WRIST_HAND, PainView.BACK,
        0.000f, 0.390f, 0.000f, 0.485f, 0.015f, 0.485f, 0.052f, 0.445f,
        0.069f, 0.415f, 0.069f, 0.400f, 0.078f, 0.390f,
    ),
    // The loin either side of the lumbar strip.
    RegionPatch(
        PainRegion.FLANK, PainView.BACK,
        0.300f, 0.245f, 0.307f, 0.270f, 0.291f, 0.325f, 0.300f, 0.325f,
        0.420f, 0.325f, 0.420f, 0.245f,
    ),
    RegionPatch(
        PainRegion.LOWER_BACK, PainView.BACK,
        0.420f, 0.245f, 0.420f, 0.325f, 0.440f, 0.325f, 0.500f, 0.325f,
        0.560f, 0.325f, 0.580f, 0.325f, 0.580f, 0.245f,
    ),
    // From behind, the hip is the outer strip beside the buttock, over the joint's bony point.
    RegionPatch(
        PainRegion.HIP, PainView.BACK,
        0.291f, 0.325f, 0.217f, 0.465f, 0.300f, 0.465f, 0.300f, 0.325f,
    ),
    RegionPatch(
        PainRegion.GLUTE, PainView.BACK,
        0.300f, 0.325f, 0.300f, 0.465f, 0.500f, 0.465f, 0.500f, 0.450f,
        0.485f, 0.450f, 0.480f, 0.410f, 0.462f, 0.370f, 0.440f, 0.325f,
        0.420f, 0.325f,
    ),
    // The base of the spine in halves meeting on the axis, and the tailbone under it.
    RegionPatch(
        PainRegion.SACRUM, PainView.BACK,
        0.440f, 0.325f, 0.462f, 0.370f, 0.480f, 0.410f, 0.500f, 0.410f,
        0.500f, 0.325f,
    ),
    RegionPatch(
        PainRegion.TAILBONE, PainView.BACK,
        0.480f, 0.410f, 0.485f, 0.450f, 0.500f, 0.450f, 0.515f, 0.450f,
        0.520f, 0.410f, 0.500f, 0.410f,
    ),
    RegionPatch(
        PainRegion.THIGH_BACK, PainView.BACK,
        0.217f, 0.465f, 0.208f, 0.520f, 0.225f, 0.565f, 0.246f, 0.595f,
        0.459f, 0.595f, 0.500f, 0.500f, 0.500f, 0.465f, 0.300f, 0.465f,
    ),
    RegionPatch(
        PainRegion.BACK_OF_KNEE, PainView.BACK,
        0.246f, 0.595f, 0.253f, 0.605f, 0.241f, 0.645f, 0.442f, 0.645f,
        0.459f, 0.595f,
    ),
    RegionPatch(
        PainRegion.CALF, PainView.BACK,
        0.241f, 0.645f, 0.187f, 0.720f, 0.187f, 0.735f, 0.225f, 0.815f,
        0.232f, 0.865f, 0.280f, 0.865f, 0.330f, 0.865f, 0.376f, 0.865f,
        0.385f, 0.835f, 0.395f, 0.825f, 0.416f, 0.775f, 0.437f, 0.760f,
        0.444f, 0.745f, 0.444f, 0.730f, 0.421f, 0.690f, 0.426f, 0.670f,
        0.442f, 0.645f,
    ),
    // The tendon above the heel, only as wide as the band the two plates' tendons share: the man's
    // ankle is drawn further out than the woman's, and a wider strip would take his inner ankle bone and her
    // outer one. The ankle beside it is its two sides, the outer one with the side of the foot under it.
    RegionPatch(
        PainRegion.ACHILLES, PainView.BACK,
        0.280f, 0.865f, 0.280f, 0.935f, 0.330f, 0.935f, 0.330f, 0.865f,
    ),
    RegionPatch(
        PainRegion.HEEL, PainView.BACK,
        0.230f, 0.935f, 0.230f, 0.987f, 0.239f, 0.990f, 0.250f, 0.995f,
        0.362f, 0.995f, 0.378f, 0.985f, 0.383f, 0.975f, 0.376f, 0.950f,
        0.380f, 0.935f, 0.330f, 0.935f, 0.280f, 0.935f,
    ),
    RegionPatch(
        PainRegion.ANKLE, PainView.BACK,
        0.232f, 0.865f, 0.225f, 0.925f, 0.187f, 0.945f, 0.156f, 0.955f,
        0.149f, 0.965f, 0.156f, 0.970f, 0.185f, 0.977f, 0.213f, 0.980f,
        0.230f, 0.987f, 0.230f, 0.935f, 0.280f, 0.935f, 0.280f, 0.865f,
    ),
    RegionPatch(
        PainRegion.ANKLE, PainView.BACK,
        0.330f, 0.865f, 0.330f, 0.935f, 0.380f, 0.935f, 0.385f, 0.910f,
        0.374f, 0.890f, 0.376f, 0.865f,
    ),
)

private fun bodyPatches(map: PainMapVersion): List<RegionPatch> = when (map) {
    PainMapVersion.V1 -> BODY_PATCHES_V1
    PainMapVersion.V2 -> BODY_PATCHES_V2
}

private val SIDE_PATCHES: Map<PainMapVersion, Map<PainView, List<SidePatch>>> =
    PainMapVersion.entries.associateWith { map ->
        PainView.entries.associateWith { view ->
            bodyPatches(map).filter { it.view == view }.flatMap { patch ->
                if (patch.region.paired) {
                    val mirrored = patch.points.map { Offset(1f - it.x, it.y) }
                    listOf(
                        SidePatch(RegionSide(patch.region, sideOf(view, 0.25f)), view, patch.points),
                        SidePatch(RegionSide(patch.region, sideOf(view, 0.75f)), view, mirrored),
                    )
                } else {
                    listOf(SidePatch(RegionSide(patch.region, BodySide.CENTRE), view, patch.points))
                }
            }
        }
    }

/** The masks of one view of [map] with their sides settled, the paired ones as two. */
internal fun sidePatches(view: PainView, map: PainMapVersion): List<SidePatch> =
    SIDE_PATCHES.getValue(map).getValue(view)

/**
 * The areas that show this one on the figure of [map], when it is lit whole — chosen from the list,
 * with nothing painted on it.
 *
 * The area itself where [map] has a patch for it. Where [map] has none of its own but the areas carved
 * out of it have — "Abdomen" on the second map, an entry the first map recorded or one chosen by that
 * name — those areas, on its side: a centre area lights its children on either side, a left one its
 * left children. And for an area recorded on an older map ([from]), its children as well as itself —
 * its family on [map]: a knee chosen from the list on the first map is the front of the knee and the
 * back of it. That is close to what it covers there, not the same: where [map] moves a border the
 * family loses what is a neighbour's on [map] — the first map's chest reaches up to the neck and out
 * over the front of the shoulder, which the second gives the neck, the ridge to the shoulder and the
 * shoulder — and a centre part carved from both sides of a pair, the breastbone, lights with neither
 * side alone. An area no map draws — the head's, the fingers' — is shown by none, and is only itself
 * here.
 */
internal fun RegionSide.litOn(map: PainMapVersion, from: PainMapVersion = map): Set<RegionSide> {
    val drawn = PainView.entries.any { view -> sidePatches(view, map).any { it.area == this } }
    if (drawn && from >= map) return setOf(this)
    val children = PainView.entries.flatMap { view -> sidePatches(view, map) }
        .map { it.area }
        .filter { it.region.carvedFrom(region) && (side == BodySide.CENTRE || it.side == side) }
    return (listOf(this) + children).toSortedSet(REGION_SIDE_ORDER)
}

/** Whether this area was carved out of [area], at any remove. */
private fun PainRegion.carvedFrom(area: PainRegion): Boolean = parent?.let { it == area || it.carvedFrom(area) } == true

/** Whether [map] has this area at all: the first map its own, carved from no other; the second every one. */
private fun PainRegion.isOn(map: PainMapVersion): Boolean = map > PainMapVersion.V1 || parent == null

/**
 * Every area on every side that the list offers for an entry painted on [map], head to foot: the areas
 * [map] has, less any it has carved up whole — "Abdomen" on the second map, whose six parts are offered
 * in its place. The first map has its own twenty-three; the second has every area there is. An area
 * with no patch and nothing carved from it — the head's, the fingers', the sole's — is offered, the
 * list being the only place it can be chosen.
 */
fun painAreasOffered(map: PainMapVersion): List<RegionSide> {
    val drawn = PainView.entries.flatMap { view -> sidePatches(view, map) }.map { it.area.region }.toSet()
    return RegionSide.ALL.filter { area ->
        val carvedWhole = area.region !in drawn && drawn.any { it.carvedFrom(area.region) }
        area.region.isOn(map) && !carvedWhole
    }
}

/**
 * The list of areas for an entry painted on [map] that records [marked]: what [map] offers, and with it
 * any area already on the entry that [map] does not offer — "Abdomen" on the second map, which offers
 * its six parts instead — so that it can be taken off. Head to foot, each once.
 */
fun painAreaChoices(map: PainMapVersion, marked: Collection<RegionSide>): List<RegionSide> =
    (painAreasOffered(map) + marked).toSortedSet(REGION_SIDE_ORDER).toList()

/**
 * A stroke of the highlighter: the view it was painted on, its brush radius in plate heights, and
 * its path in figure units (x in plate widths, y in plate heights).
 *
 * The radius is kept in figure units rather than on screen, so a stroke painted zoomed in stays the
 * fine line it was drawn as when the figure is shown whole again.
 */
data class PainStroke(val view: PainView, val radius: Float, val points: List<Offset>)

/*
 * The hit test. Figure space is cut into a grid of about six plate pixels a cell, and each cell
 * belongs to the patch its centre falls inside, or to none. A stroke covers the cells whose centre
 * is within its brush of any of its segments. All of it plain arithmetic: android.graphics' Path
 * and Region are stubs off the device, and this is the part of the feature that most needs a test.
 *
 * The column count is even, so no cell centre lies on the figure's axis: with an odd count the
 * middle column would sit exactly on the line where every pair's two patches meet, and ray casting
 * would give all of it to one side — the person's left from the front, their right from behind.
 *
 * Each map has a grid of its own, built from its own patches: an entry is hit-tested on the map it
 * was painted on.
 */
internal const val GRID_COLUMNS = 72
internal const val GRID_ROWS = 150

/** A cell's width and height, in plate heights. */
private const val CELL_WIDTH = PLATE_ASPECT / GRID_COLUMNS
private const val CELL_HEIGHT = FIGURE_HEIGHT / GRID_ROWS

/** Share of an area's cells a painting has to cover before the area counts as marked. */
private const val MARKED_SHARE = 0.10f

/** And the fewest cells it takes, so a smudge on the edge of a small area does not select it. */
private const val MARKED_MIN_CELLS = 3

/**
 * And half of what one touch of the brush could cover of the area — the whole area, where it is
 * smaller than a touch. With the figure whole a finger is wider than a small area such as the neck
 * or a wrist, so a dab on one spills over its neighbours; ten per cent of a small area is a few
 * cells, and the spill alone would mark it. Zoomed in, a touch is a few cells and this is below the
 * other two.
 */
private const val MARKED_TOUCH_SHARE = 0.5f

private class PainGrid(val patches: List<SidePatch>) {
    /** The patch each cell belongs to, as an index into [patches], or -1 off the body. */
    val owner = IntArray(GRID_COLUMNS * GRID_ROWS) { -1 }

    /** How many cells each area on each side has. */
    val totals: Map<RegionSide, Int>

    init {
        val half = GRID_COLUMNS / 2
        patches.forEachIndexed { index, patch ->
            forEachCell(
                patch.points.minOf { it.x },
                patch.points.maxOf { it.x },
                patch.points.minOf { it.y },
                patch.points.maxOf { it.y },
            ) { column, row -> if (column < half) claim(index, column, row) }
        }
        // The viewer's right half is the left half mirrored, cell for cell — the patches are
        // mirrored the same way — so the two sides of a pair always have the same cells and a dab
        // on the middle of the figure lands on both alike.
        val twin = IntArray(patches.size) { index ->
            val area = patches[index].area
            if (area.side == BodySide.CENTRE) {
                index
            } else {
                patches.indexOfFirst { it.area.region == area.region && it.area.side != area.side }
            }
        }
        for (row in 0 until GRID_ROWS) {
            for (column in 0 until half) {
                val index = owner[row * GRID_COLUMNS + column]
                if (index >= 0) owner[row * GRID_COLUMNS + GRID_COLUMNS - 1 - column] = twin[index]
            }
        }
        totals = owner.filter { it >= 0 }.groupingBy { patches[it].area }.eachCount()
    }

    fun areaAt(cell: Int): RegionSide? = owner[cell].takeIf { it >= 0 }?.let { patches[it].area }

    private fun claim(index: Int, column: Int, row: Int) {
        val cell = row * GRID_COLUMNS + column
        if (owner[cell] == -1 && inside(cellCentre(column, row), patches[index].points)) owner[cell] = index
    }
}

private val GRIDS: Map<PainMapVersion, Map<PainView, PainGrid>> by lazy {
    PainMapVersion.entries.associateWith { map -> PainView.entries.associateWith { PainGrid(sidePatches(it, map)) } }
}

private fun grid(view: PainView, map: PainMapVersion): PainGrid = GRIDS.getValue(map).getValue(view)

/** How many cells of the hit grid of [map] each area on each side has on [view]. */
internal fun areaCells(view: PainView, map: PainMapVersion): Map<RegionSide, Int> = grid(view, map).totals

/**
 * The area under one point of [view] on [map] — what a tap on the figure chooses, where a stroke is a
 * painting: the area whose cell of the hit grid the point falls in, or null off the body and off the
 * figure. Never a dab of a tiny brush put through [paintedRegions]: one small enough to be a point,
 * put where four cells meet, reaches no cell centre and marks nothing, and one a little larger reaches
 * two areas at once.
 *
 * A tap kept as a stroke, so that the summary and the doctor's sheet draw it, must not mark only areas
 * the tap did not choose, or the stroke is hidden as one painted over an area struck off the list
 * ([painShownStrokes]). A dab of no more than 0.003 plate heights at the tap marks the tapped area or
 * nothing: a disc that small holds one cell centre at most, and only the tapped cell's. A finger-sized
 * one near a border can mark the neighbour alone; for that one, record what the stored stroke marks.
 */
internal fun areaAt(view: PainView, point: Offset, map: PainMapVersion = PainMapVersion.CURRENT): RegionSide? {
    if (point.x !in 0f..1f || point.y !in FIGURE_TOP..FIGURE_BOTTOM) return null
    return grid(view, map).areaAt(rowOf(point.y) * GRID_COLUMNS + columnOf(point.x))
}

private fun columnOf(x: Float): Int = floor(x * GRID_COLUMNS).toInt().coerceIn(0, GRID_COLUMNS - 1)

private fun rowOf(y: Float): Int =
    floor((y - FIGURE_TOP) / FIGURE_HEIGHT * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)

private fun cellCentre(column: Int, row: Int) =
    Offset((column + 0.5f) / GRID_COLUMNS, FIGURE_TOP + (row + 0.5f) * FIGURE_HEIGHT / GRID_ROWS)

/** Every cell of the box from ([minX], [minY]) to ([maxX], [maxY]), clamped to the grid. */
private inline fun forEachCell(minX: Float, maxX: Float, minY: Float, maxY: Float, action: (Int, Int) -> Unit) {
    for (row in rowOf(minY)..rowOf(maxY)) {
        for (column in columnOf(minX)..columnOf(maxX)) action(column, row)
    }
}

/** Ray casting: whether [point] is inside the closed polygon through [polygon]. */
internal fun inside(point: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > point.y) != (b.y > point.y) &&
            point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** How far [point] is from the segment [a]–[b], in plate heights. */
private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val px = point.x * PLATE_ASPECT
    val ax = a.x * PLATE_ASPECT
    val bx = b.x * PLATE_ASPECT
    val dx = bx - ax
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else ((px - ax) * dx + (point.y - a.y) * dy) / lengthSquared
    val along = t.coerceIn(0f, 1f)
    val cx = ax + along * dx - px
    val cy = a.y + along * dy - point.y
    return sqrt(cx * cx + cy * cy)
}

/** The cells a stroke covers, whether or not they are on the body. A single point is a dab. */
private fun coveredCells(stroke: PainStroke): Set<Int> {
    val points = stroke.points
    val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
    val reachX = stroke.radius / PLATE_ASPECT
    val cells = HashSet<Int>()
    segments.forEach { (a, b) ->
        forEachCell(
            min(a.x, b.x) - reachX,
            max(a.x, b.x) + reachX,
            min(a.y, b.y) - stroke.radius,
            max(a.y, b.y) + stroke.radius,
        ) { column, row ->
            if (distanceToSegment(cellCentre(column, row), a, b) <= stroke.radius) cells += row * GRID_COLUMNS + column
        }
    }
    return cells
}

/**
 * The areas a painting marks on [map] — for a stored entry always the map it was painted on
 * ([PainReport.map]), never a later one.
 *
 * An area on a side is marked when the strokes cover at least a tenth of its cells, never fewer than
 * three, and at least half of what one touch of the finest brush that reached it could cover of it —
 * so the rim of a dab does not mark the neighbours of what it was put on. Each stroke also marks
 * where it took the largest share whatever that share is, so a small dab on a small joint still says
 * something: the largest of the first map's areas it fell on, and in it the largest part the second
 * map carved ([largestShare]); a stroke split evenly marks every area it is split between. Paint off
 * the body covers no area and counts for nothing. Strokes on the two views are tested against their
 * own plate and merged: on the first map, where the front and the back of a joint are one area, a
 * knee painted front and back is one knee; on the second it is the knee and the back of it, one
 * family ([root]).
 *
 * There is no default map: whoever runs the hit test says which, since an entry's strokes read on
 * another map mark areas it never recorded.
 */
fun paintedRegions(strokes: List<PainStroke>, map: PainMapVersion): Set<RegionSide> =
    strokes
        .groupBy { it.view }
        .flatMap { (view, onView) -> grid(view, map).marked(onView) }
        .toSortedSet(REGION_SIDE_ORDER)

/** The areas [strokes], all painted on this grid's view, mark on it. */
private fun PainGrid.marked(strokes: List<PainStroke>): Set<RegionSide> {
    val marked = HashSet<RegionSide>()
    val covered = HashMap<RegionSide, MutableSet<Int>>()
    val touch = HashMap<RegionSide, Float>()
    strokes.forEach { stroke ->
        val byArea = coveredCells(stroke)
            .mapNotNull { cell -> areaAt(cell)?.let { it to cell } }
            .groupBy({ it.first }, { it.second })
        marked += largestShare(byArea.mapValues { it.value.size })
        val cells = touchCells(stroke.radius)
        byArea.forEach { (area, onArea) ->
            covered.getOrPut(area, ::HashSet) += onArea
            touch[area] = min(touch[area] ?: cells, cells)
        }
    }
    covered.forEach { (area, cells) ->
        val total = totals.getValue(area)
        val needed = maxOf(
            MARKED_MIN_CELLS,
            ceil(total * MARKED_SHARE).toInt(),
            ceil(min(total.toFloat(), touch.getValue(area)) * MARKED_TOUCH_SHARE).toInt(),
        )
        if (cells.size >= needed) marked += area
    }
    return marked
}

/**
 * The areas that took the largest share of one stroke, from how many of its cells fell on each.
 *
 * The shares are reckoned between the first map's areas, each on its sides ([firstMapAreas]) — on
 * the second map the parts carved out of one are counted together — and the largest goes to the
 * largest of its parts. Reckoned between the parts themselves, a family the second map carved finer
 * would lose to a neighbour it left whole: a whole-figure dab on either end of the breastbone falls
 * on the breastbone and on both sides of the chest, a share each, and would go to the neck above it
 * or to the upper belly under it — and the chest's safety line with it. On the first map every area is
 * its own family, and the largest share is the largest area's.
 *
 * A tie is never settled by which a list names first: a dab on the middle of a pair is split evenly
 * between its two sides, and marks both.
 */
private fun largestShare(cells: Map<RegionSide, Int>): Set<RegionSide> {
    val shares = HashMap<RegionSide, Int>()
    cells.forEach { (area, count) -> area.firstMapAreas.forEach { shares[it] = (shares[it] ?: 0) + count } }
    val most = shares.values.maxOrNull() ?: return emptySet()
    return shares.filterValues { it == most }.keys.flatMapTo(HashSet()) { family ->
        val parts = cells.filterKeys { family in it.firstMapAreas }
        val largest = parts.values.max()
        parts.filterValues { it == largest }.keys
    }
}

/** How many cells one touch of a brush of [radius] plate heights covers: its disc over a cell. */
private fun touchCells(radius: Float): Float = PI.toFloat() * radius * radius / (CELL_WIDTH * CELL_HEIGHT)
