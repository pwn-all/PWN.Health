package com.pwnall.health

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MedicalServices
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * A symptom as the person tells it — vomiting, a dizzy spell, a fever — with a few short answers
 * about it and, beside it, what the journal already held at that moment: the last meal, the day's
 * steps and water, the last workout, the last blood pressure.
 *
 * The facts are set beside the symptom and never read as its cause. A dizzy spell after a day of
 * 400 steps and 300 ml of water is a dizzy spell on such a day, and a person or their doctor can see
 * that without the app saying why. So nothing here grades, explains or guesses; the only words the
 * app adds of its own are the safety lines, each taken from a named source.
 *
 * Its own kind, and one that stays on the phone. Health Connect has no record for a symptom, and
 * nothing here is written into a column an exporter reads — no calories, no heart rate, no sleep
 * times — and none of the snapshot's payload keys is a key a vital or an exporter looks for, so a
 * row can never be published, or counted in a vitals series, as a measurement it is not.
 */
const val KIND_SYMPTOM = "SYMPTOM"

/** The glyph a symptom row, door and filter wear where no drawing is shown. */
internal val SymptomIcon = Icons.Outlined.MedicalServices

/** How bad it is, in the three words a GP asks for; there is no number to invent precision with. */
enum class SymptomSeverity(val labelRes: Int) {
    MILD(R.string.symptom_severity_mild),
    MODERATE(R.string.symptom_severity_moderate),
    SEVERE(R.string.symptom_severity_severe),
}

/**
 * One answer to one of the questions. Shared between the symptoms that ask the same thing — "chest
 * pain" is the same tick under dizziness, a faint and palpitations — so a month of entries can be
 * counted over one vocabulary. [NONE] and [NOT_SURE] stand alone: ticking either clears the rest,
 * and ticking anything else clears them ([toggledOption]).
 */
enum class SymptomOption(val labelRes: Int) {
    YES(R.string.common_yes),
    NO(R.string.common_no),

    ATE_TOO_MUCH(R.string.symptom_option_ate_too_much),
    FOOD_OFF(R.string.symptom_option_food_off),
    ALCOHOL(R.string.symptom_option_alcohol),
    MOTION(R.string.symptom_option_motion),
    MEDICINE(R.string.symptom_option_medicine),
    STOMACH_BUG(R.string.symptom_option_stomach_bug),
    SELF_INDUCED(R.string.symptom_option_self_induced),
    PREGNANCY(R.string.symptom_option_pregnancy),
    STRESS(R.string.symptom_option_stress),
    TRAVEL(R.string.symptom_option_travel),
    NOT_SURE(R.string.symptom_option_not_sure),

    ONCE(R.string.symptom_option_once),
    TWICE(R.string.symptom_option_twice),
    THREE_PLUS(R.string.symptom_option_three_plus),

    STANDING_UP(R.string.symptom_option_standing_up),
    EXERCISING(R.string.symptom_option_exercising),
    AFTER_EXERCISE(R.string.symptom_option_after_exercise),
    SITTING_LYING(R.string.symptom_option_sitting_lying),
    TURNING_HEAD(R.string.symptom_option_turning_head),
    WALKING(R.string.symptom_option_walking),

    LIGHT_HEADED(R.string.symptom_option_light_headed),
    SPINNING(R.string.symptom_option_spinning),
    UNSTEADY(R.string.symptom_option_unsteady),

    RACING(R.string.symptom_option_racing),
    POUNDING(R.string.symptom_option_pounding),
    IRREGULAR(R.string.symptom_option_irregular),
    FLUTTERING(R.string.symptom_option_fluttering),

    AT_REST(R.string.symptom_option_at_rest),
    AT_NIGHT(R.string.symptom_option_at_night),
    WALKING_STAIRS(R.string.symptom_option_walking_stairs),
    HARD_EXERCISE(R.string.symptom_option_hard_exercise),
    LYING_NIGHT(R.string.symptom_option_lying_night),
    AFTER_EATING(R.string.symptom_option_after_eating),
    LYING_BENDING(R.string.symptom_option_lying_bending),
    EMPTY_STOMACH(R.string.symptom_option_empty_stomach),

    TODAY(R.string.symptom_option_today),
    FEW_DAYS(R.string.symptom_option_few_days),
    OVER_TWO_WEEKS(R.string.symptom_option_over_two_weeks),

    CHEST_PAIN(R.string.symptom_option_chest_pain),
    RACING_HEART(R.string.symptom_option_racing_heart),
    ONE_SIDE_WEAK(R.string.symptom_option_one_side_weak),
    SPEECH(R.string.symptom_option_speech),
    BREATHLESS(R.string.symptom_option_breathless),
    DURING_EXERCISE(R.string.symptom_option_during_exercise),
    DIZZY_FAINT(R.string.symptom_option_dizzy_faint),
    WHEEZING(R.string.symptom_option_wheezing),
    COUGH(R.string.symptom_option_cough),
    BLUE_LIPS(R.string.symptom_option_blue_lips),
    SWALLOWING(R.string.symptom_option_swallowing),
    VOMITING(R.string.symptom_vomiting),
    BLACK_STOOLS(R.string.symptom_option_black_stools),
    WEIGHT_LOSS(R.string.symptom_option_weight_loss),
    BLOOD(R.string.symptom_option_blood),
    DEHYDRATION(R.string.symptom_option_dehydration),
    FEVER(R.string.symptom_fever),
    CHILLS(R.string.symptom_option_chills),
    SWEATING(R.string.symptom_option_sweating),
    SORE_THROAT(R.string.symptom_option_sore_throat),
    RASH(R.string.symptom_option_rash),
    STIFF_NECK(R.string.symptom_option_stiff_neck),
    POOR_SLEEP(R.string.symptom_option_poor_sleep),
    LOW_MOOD(R.string.symptom_option_low_mood),
    NONE(R.string.symptom_option_none),

    // Everything below is the chat's (SymptomChat.kt). Appended, never inserted: the payload
    // writes each answer's options in this order, and a backup knows a row by its payload.

    /** When it started: the pain page's seven offered answers, in the same words ([painOnset]). */
    STARTED_NOW(R.string.pain_onset_just_now),
    STARTED_TODAY(R.string.pain_onset_earlier_today),
    STARTED_YESTERDAY(R.string.pain_onset_yesterday),
    STARTED_2_3_DAYS(R.string.pain_onset_days_2_3),
    STARTED_A_WEEK(R.string.pain_onset_about_week),
    STARTED_A_MONTH(R.string.pain_onset_about_month),
    STARTED_LONGER(R.string.pain_onset_more_than_month),

    SAME_AS_BEFORE(R.string.symptom_option_same_as_before),
    SOMETHING_NEW(R.string.symptom_option_something_new),

    ALL_THE_TIME(R.string.symptom_option_all_the_time),
    COMES_AND_GOES(R.string.symptom_option_comes_and_goes),
    JUST_ONCE(R.string.symptom_option_just_once),

    SEVERAL_A_DAY(R.string.symptom_option_several_a_day),
    DAILY(R.string.symptom_option_daily),
    FEW_A_WEEK(R.string.symptom_option_few_a_week),
    LESS_OFTEN(R.string.symptom_option_less_often),

    SECONDS(R.string.symptom_option_seconds),
    FEW_MINUTES(R.string.symptom_option_few_minutes),
    LONGER_MINUTES(R.string.symptom_option_longer_minutes),
    HOURS(R.string.symptom_option_hours),

    GETTING_BETTER(R.string.symptom_option_getting_better),
    NO_CHANGE(R.string.symptom_option_no_change),
    GETTING_WORSE(R.string.symptom_option_getting_worse),

    CANT_KEEP_FLUIDS(R.string.symptom_option_cant_keep_fluids),
    GREEN_VOMIT(R.string.symptom_option_green_vomit),
    SEVERE_TUMMY_PAIN(R.string.symptom_option_severe_tummy_pain),
    SUDDEN_HEADACHE(R.string.symptom_option_sudden_headache),
    CONFUSED(R.string.symptom_option_confused),
    SEVERE_BREATHING(R.string.symptom_option_severe_breathing),
    PAIN_SPREADING(R.string.symptom_option_pain_spreading),
    TIGHT_HEAVY(R.string.symptom_option_tight_heavy),
    SWEATY_SICK(R.string.symptom_option_sweaty_sick),

    WORK_STUDY(R.string.symptom_option_work_study),
    MONEY(R.string.symptom_option_money),
    FAMILY(R.string.symptom_option_family),
    HEALTH(R.string.symptom_option_health),
    LONELINESS(R.string.symptom_option_loneliness),
    LOSS(R.string.symptom_option_loss),
    FUTURE(R.string.symptom_option_future),
    NO_CLEAR_REASON(R.string.symptom_option_no_clear_reason),
    SOMETHING_ELSE(R.string.symptom_other),

    ABOUT_A_WEEK(R.string.symptom_option_about_a_week),
    TWO_WEEKS_PLUS(R.string.symptom_option_two_weeks_plus),
    MONTHS(R.string.symptom_option_months),

    NOW_AND_THEN(R.string.symptom_option_now_and_then),
    MOST_DAYS(R.string.symptom_option_most_days),

    SLEEP(R.string.symptom_option_sleep),
    EATING(R.string.symptom_option_eating),
    DAILY_TASKS(R.string.symptom_option_daily_tasks),
    PEOPLE(R.string.symptom_option_people),
    NOT_REALLY(R.string.symptom_option_not_really),

    MANAGING(R.string.symptom_option_managing),
    STRUGGLING(R.string.symptom_option_struggling),
    NOT_COPING(R.string.symptom_option_not_coping),

    STOMACH(R.string.symptom_option_stomach),
    TENSE(R.string.symptom_option_tense),
    HEADACHE(R.string.symptom_option_headache),
    NO_ENJOYMENT(R.string.symptom_option_no_enjoyment),
    HOPELESS(R.string.symptom_option_hopeless),
    TIRED(R.string.symptom_tiredness),

    HARD_TO_FALL_ASLEEP(R.string.symptom_option_hard_to_fall_asleep),
    WAKING_IN_NIGHT(R.string.symptom_option_waking_in_night),
    WAKING_EARLY(R.string.symptom_option_waking_early),
    RACING_THOUGHTS(R.string.symptom_option_racing_thoughts),
    TIRED_IN_DAY(R.string.symptom_option_tired_in_day),

    CRISIS_SAFE(R.string.symptom_option_crisis_safe),
    CRISIS_UNSAFE(R.string.symptom_option_crisis_unsafe),
    CRISIS_HARMED(R.string.symptom_option_crisis_harmed),
    RATHER_NOT_SAY(R.string.symptom_option_rather_not_say),

    /**
     * A rash that does not fade under a pressed glass: a sign NHS "Meningitis" and "Sepsis" each put
     * on their call-999 lists by itself. Its own answer rather than a new label on [RASH], which a
     * stored entry may hold for any rash at all.
     */
    NON_BLANCHING_RASH(R.string.symptom_option_non_blanching_rash),
    BLUE_SKIN(R.string.symptom_option_blue_skin),
    COUGHING_BLOOD(R.string.symptom_option_coughing_blood),
    ONE_LEG_SWELLING(R.string.symptom_option_one_leg_swelling),
    HEART_CONDITION(R.string.symptom_option_heart_condition),
    SAFE_NOW(R.string.symptom_option_safe_now),
    TRIED_NOT_HELPING(R.string.symptom_option_tried_not_helping),

    /**
     * "I am not having thoughts like that": the "no" to whether the person can keep safe, which their
     * words asked because a key matched — "this headache will kill me". Not [NO], which there would
     * read as "no, I cannot keep myself safe".
     */
    NO_SUCH_THOUGHTS(R.string.symptom_option_no_such_thoughts),

    // The chest triage and the pain thread. [LEAVE_IT] is shared by the mood update and the medicine's
    // pain link; the pain thread's answers carry the pain page's own labels; each red flag after them
    // is labelled `pain_chat_sign_<name>`, and a body family may show it in words of its own
    // ([ChatReply.Choose]).

    /** "Leave it as it is": the record already there stays as it was. */
    LEAVE_IT(R.string.symptom_chat_option_leave_it),
    PAIN_SAME_YES(R.string.pain_same_yes),
    PAIN_NEW(R.string.pain_same_new),
    FEELS_SAME(R.string.pain_same_unchanged),
    FEELS_CHANGED(R.string.pain_same_changed),
    PAIN_ACHING(R.string.pain_type_aching),
    PAIN_STIFF(R.string.pain_type_stiff),
    PAIN_THROBBING(R.string.pain_type_throbbing),
    PAIN_SHARP(R.string.pain_type_sharp),
    PAIN_BURNING(R.string.pain_type_burning),
    PAIN_CRAMPING(R.string.pain_type_cramping),
    NOTICED_DURING_WORKOUT(R.string.pain_noticed_during_workout),
    NOTICED_AFTER_WORKOUT(R.string.pain_noticed_after_workout),
    NOTICED_WORK_OR_SITTING(R.string.pain_noticed_work_or_sitting),
    NOTICED_ON_WAKING(R.string.pain_noticed_on_waking),
    NOTICED_AFTER_LIFTING(R.string.pain_noticed_after_lifting),
    NOTICED_NO_CLEAR_MOMENT(R.string.pain_noticed_no_clear_moment),
    STILL_HURTS(R.string.pain_still_hurts),
    GONE(R.string.pain_gone),

    // The red flags of the pain thread, by body family (the NHS pages each family follows).
    SUDDEN_SEVERE(R.string.pain_chat_sign_sudden_severe),
    SUDDEN_OR_SEVERE(R.string.pain_chat_sign_sudden_or_severe),
    SUDDEN_OR_BAD(R.string.pain_chat_sign_sudden_or_bad),
    VERY_PAINFUL(R.string.pain_chat_sign_very_painful),
    CHEST_TOO(R.string.pain_chat_sign_chest_too),
    BREATHING_HARD(R.string.pain_chat_sign_breathing_hard),
    FAST_HEART(R.string.pain_chat_sign_fast_heart),
    TINGLE_NUMB(R.string.pain_chat_sign_tingle_numb),
    ODD_SHAPE(R.string.pain_chat_sign_odd_shape),
    CRACK(R.string.pain_chat_sign_crack),
    BONE_OUT(R.string.pain_chat_sign_bone_out),
    BLUE_COLD(R.string.pain_chat_sign_blue_cold),
    SKIN_COLOUR(R.string.pain_chat_sign_skin_colour),
    NO_WEIGHT(R.string.pain_chat_sign_no_weight),
    CANT_MOVE(R.string.pain_chat_sign_cant_move),
    SEVERE_CANT_MOVE(R.string.pain_chat_sign_severe_cant_move),
    SWELL_BRUISE(R.string.pain_chat_sign_swell_bruise),
    FAST_SWELL(R.string.pain_chat_sign_fast_swell),
    STIFF_HARD(R.string.pain_chat_sign_stiff_hard),
    FAINT_FROM_PAIN(R.string.pain_chat_sign_faint_from_pain),
    HOT_RED_SWOLLEN(R.string.pain_chat_sign_hot_red_swollen),
    HOT_OR_COLD(R.string.pain_chat_sign_hot_or_cold),
    SWOLLEN_JOINT(R.string.pain_chat_sign_swollen_joint),
    FEVERISH(R.string.pain_chat_sign_feverish),
    LOCKS_GIVES(R.string.pain_chat_sign_locks_gives),
    KNEECAP_SLIPPED(R.string.pain_chat_sign_kneecap_slipped),
    SHOCK(R.string.pain_chat_sign_shock),
    TIPTOE_STAIRS(R.string.pain_chat_sign_tiptoe_stairs),
    CALF_ANKLE_SWELL(R.string.pain_chat_sign_calf_ankle_swell),
    BIG_TOE(R.string.pain_chat_sign_big_toe),
    EXERCISE_EASES(R.string.pain_chat_sign_exercise_eases),
    WORSE_FAST(R.string.pain_chat_sign_worse_fast),
    SEVERE_SINCE_FALL(R.string.pain_chat_sign_severe_since_fall),
    SEIZURE(R.string.pain_chat_sign_seizure),
    NUMB_WEAK(R.string.pain_chat_sign_numb_weak),
    SPEAK_BALANCE(R.string.pain_chat_sign_speak_balance),
    DROWSY_CONFUSED(R.string.pain_chat_sign_drowsy_confused),
    VISION_LOSS(R.string.pain_chat_sign_vision_loss),
    HEAD_INJURY(R.string.pain_chat_sign_head_injury),
    FEVER_STIFF_LIGHT(R.string.pain_chat_sign_fever_stiff_light),
    EYE_PROBLEMS(R.string.pain_chat_sign_eye_problems),
    WORSE_STRAIN(R.string.pain_chat_sign_worse_strain),
    JAW_SCALP(R.string.pain_chat_sign_jaw_scalp),
    CHILD_WORSE(R.string.pain_chat_sign_child_worse),
    HIGH_TEMP(R.string.pain_chat_sign_high_temp),
    SEVERE_HEADACHE(R.string.pain_chat_sign_severe_headache),
    LIGHT_HURTS(R.string.pain_chat_sign_light_hurts),
    WALK_SIT(R.string.pain_chat_sign_walk_sit),
    ELECTRIC(R.string.pain_chat_sign_electric),
    WEAK_LIMBS(R.string.pain_chat_sign_weak_limbs),
    SEVERE_DESPITE(R.string.pain_chat_sign_severe_despite),
    BOTH_LEGS(R.string.pain_chat_sign_both_legs),
    SADDLE(R.string.pain_chat_sign_saddle),
    BLADDER_BOWEL(R.string.pain_chat_sign_bladder_bowel),
    SEX_CHANGES(R.string.pain_chat_sign_sex_changes),
    SERIOUS_ACCIDENT(R.string.pain_chat_sign_serious_accident),
    PEE_BLOOD(R.string.pain_chat_sign_pee_blood),
    SICK(R.string.pain_chat_sign_sick),
    VERY_LOW_TEMP(R.string.pain_chat_sign_very_low_temp),
    LUMP_SHAPE(R.string.pain_chat_sign_lump_shape),
    NIGHT_REST(R.string.pain_chat_sign_night_rest),
    COUGH_SNEEZE_POO(R.string.pain_chat_sign_cough_sneeze_poo),
    TENDER_TUMMY(R.string.pain_chat_sign_tender_tummy),
    WORSE_OR_MOVING(R.string.pain_chat_sign_worse_or_moving),
    VOMIT_BLOOD(R.string.pain_chat_sign_vomit_blood),
    BLACK_POO(R.string.pain_chat_sign_black_poo),
    CANT_PEE_POO(R.string.pain_chat_sign_cant_pee_poo),
    SHOULDER_TIP(R.string.pain_chat_sign_shoulder_tip),
    YELLOW(R.string.pain_chat_sign_yellow),
    DIABETES_SICK(R.string.pain_chat_sign_diabetes_sick),
    CONFUSED_PALE(R.string.pain_chat_sign_confused_pale),
    HEAVY_BLEEDING(R.string.pain_chat_sign_heavy_bleeding),
    KEEPS_COMING(R.string.pain_chat_sign_keeps_coming),
    BLOATING(R.string.pain_chat_sign_bloating),
    PEE_CHANGE(R.string.pain_chat_sign_pee_change),
    BOTTOM_BLEED(R.string.pain_chat_sign_bottom_bleed),
    HARD_TO_PEE_POO(R.string.pain_chat_sign_hard_to_pee_poo),
    SICK_DIARRHOEA(R.string.pain_chat_sign_sick_diarrhoea),
    DIARRHOEA_DAYS(R.string.pain_chat_sign_diarrhoea_days),
    MOVED_RIGHT(R.string.pain_chat_sign_moved_right),
    VAGINAL(R.string.pain_chat_sign_vaginal),
    CLOT_SIGNS(R.string.pain_chat_sign_clot_signs),
    LEG_SKIN(R.string.pain_chat_sign_leg_skin),
    SUDDEN_WORSE_LEG(R.string.pain_chat_sign_sudden_worse_leg),
    WARM_SWOLLEN(R.string.pain_chat_sign_warm_swollen),
    SICK_CANT_EAT(R.string.pain_chat_sign_sick_cant_eat),
    HAS_DIABETES(R.string.pain_chat_sign_has_diabetes),
    SPREAD_BACK_SICK(R.string.pain_chat_sign_spread_back_sick),
    JOINT_WORSE(R.string.pain_chat_sign_joint_worse),
    PASSED_OUT(R.string.pain_chat_sign_passed_out),

    // ── The mood thread: append here, before the medicine block. ──
    // The mood thread's answers: whether to update a mood noted a moment ago, the Mood page's own
    // tension answers, what lifts a good mood, and the optional check on sleep and energy.

    /** "Update it": the mood already noted takes this one's place. */
    UPDATE_IT(R.string.chat_mood_option_update),
    ENERGY_WOUND_UP(R.string.energy_wound_up),
    ENERGY_RESTLESS(R.string.energy_restless),
    ENERGY_STEADY(R.string.energy_steady),
    ENERGY_CALM(R.string.energy_calm),
    ENERGY_SETTLED(R.string.energy_settled),
    REST(R.string.chat_mood_option_rest),
    MOVEMENT(R.string.chat_mood_option_movement),
    WENT_WELL(R.string.chat_mood_option_went_well),
    NATURE_WEATHER(R.string.chat_mood_option_nature),
    GOOD_NEWS(R.string.chat_mood_option_good_news),
    ASK_ME(R.string.chat_mood_option_ask_me),
    NO_THANKS(R.string.chat_mood_option_no_thanks),
    LESS_SLEEP_NEED(R.string.chat_mood_option_less_sleep),
    RACING_MIND(R.string.chat_mood_option_racing),
    UNUSUAL_RISKS(R.string.chat_mood_option_risks),
    TALKING_MORE(R.string.chat_mood_option_talking),
    NOT_NOW(R.string.chat_mood_option_not_now),

    // ── The medicine threads: append here, last. ──

    // The medicine threads' answers (ChatDose.kt, ChatCourse.kt): a dose taken, a course prescribed.
    ANOTHER_DOSE(R.string.med_chat_option_another_dose),
    SAME_DOSE(R.string.med_chat_option_same_dose),
    FOR_PAIN(R.string.med_chat_why_pain),
    FOR_TEMPERATURE(R.string.med_chat_why_temperature),
    FOR_MOOD(R.string.med_chat_why_mood),
    FOR_SLEEP(R.string.med_chat_why_sleep),
    CHANGE_IT(R.string.med_chat_option_change_it),
    MORE_THAN_IT_SAYS(R.string.med_chat_option_more),
    BY_ACCIDENT(R.string.med_chat_option_by_accident),
    ON_PURPOSE(R.string.med_chat_option_on_purpose),
    FEEL_BETTER(R.string.med_chat_feel_better),
    FEEL_SAME(R.string.med_chat_feel_same),
    FEEL_WORSE(R.string.med_chat_feel_worse),
    SIDE_EFFECTS(R.string.med_chat_feel_side_effects),
    SERIOUS_HARM(R.string.med_chat_serious_harm),
    SERIOUS_HIGH(R.string.med_chat_serious_high),
    SERIOUS_HEART(R.string.med_chat_serious_heart),
    SERIOUS_BLEEDING(R.string.med_chat_serious_bleeding),
    SERIOUS_THROAT(R.string.med_chat_serious_throat),

    /** A tricyclic's own emergency signs (its NHS pages' 999 items): offered for a tricyclic only. */
    SERIOUS_COLLAPSE(R.string.med_chat_serious_collapse),
    SAME_COURSE(R.string.med_chat_course_same_one),
    COURSE_CHANGED(R.string.med_chat_course_changed),
    COURSE_STOPPED(R.string.med_chat_course_stopped),
    ANOTHER_COURSE(R.string.med_chat_course_another),
    DOCTOR_SAID(R.string.med_chat_stop_doctor),
    OWN_DECISION(R.string.med_chat_stop_own),
    ONCE_A_DAY(R.string.med_chat_often_once),
    TWICE_A_DAY(R.string.med_chat_often_twice),
    THREE_A_DAY(R.string.med_chat_often_three),
    ON_WAKING(R.string.med_schedule_wake),
    WHEN_NEEDED(R.string.med_chat_often_needed),
    OTHER_OFTEN(R.string.med_chat_often_other),
    MORE_THAN_THREE(R.string.med_chat_often_more),
    LESS_THAN_DAILY(R.string.med_chat_often_less),
    AS_DAILY_COURSE(R.string.med_chat_offer_daily),
    DOSE_NOW(R.string.med_chat_offer_dose_now),
    FOOD_ANY(R.string.med_food_any),
    FOOD_BEFORE(R.string.med_food_before),
    FOOD_WITH(R.string.med_food_with),
    FOOD_AFTER(R.string.med_food_after),
    TOOK_TODAY(R.string.med_chat_today_taken),
    NOT_YET(R.string.med_chat_today_not_yet),
    START_TOMORROW(R.string.med_chat_today_tomorrow),
    TURN_ON(R.string.med_chat_remind_on),
    LEAVE_OFF(R.string.med_chat_remind_leave),
    ALLOW(R.string.med_chat_notify_allow),
    NOT_NOW_MED(R.string.med_chat_not_now),
    DONE(R.string.med_chat_alarm_done),
}

/** The pain page's onset for a chat's answer to "when did it start?", so both can be read together. */
val SymptomOption.painOnset: PainOnset?
    get() = ONSET_AS_PAIN[this]

private val ONSET_AS_PAIN = mapOf(
    SymptomOption.STARTED_NOW to PainOnset.JUST_NOW,
    SymptomOption.STARTED_TODAY to PainOnset.EARLIER_TODAY,
    SymptomOption.STARTED_YESTERDAY to PainOnset.YESTERDAY,
    SymptomOption.STARTED_2_3_DAYS to PainOnset.DAYS_2_3,
    SymptomOption.STARTED_A_WEEK to PainOnset.ABOUT_A_WEEK,
    SymptomOption.STARTED_A_MONTH to PainOnset.ABOUT_A_MONTH,
    SymptomOption.STARTED_LONGER to PainOnset.MORE_THAN_A_MONTH,
)

/** The answers that stand alone in a question that takes several. */
internal val EXCLUSIVE_OPTIONS = setOf(
    SymptomOption.NONE,
    SymptomOption.NOT_SURE,
    SymptomOption.NOT_REALLY,
    SymptomOption.NO_CLEAR_REASON,
)

/**
 * The questions, in one order — which symptom first, how bad last, and between them the ones
 * [SYMPTOM_ASKS] gives that symptom — and after them the chat's own, in the order they were
 * appended. [multi] when it takes several answers, which a tap does not end:
 * they are taken three seconds after the last change ([AutoTakeClock]). Appended, never inserted:
 * the payload writes answers in this order.
 *
 * A step's label is the short name a summary prints; the chat asks most of them in fuller words
 * ([chatQuestionRes]).
 */
enum class SymptomStep(val labelRes: Int, val multi: Boolean = false) {
    SYMPTOM(R.string.symptom_step_symptom),
    AFTER_MEAL(R.string.symptom_step_after_meal),
    CAUSE(R.string.symptom_step_cause, multi = true),
    TIMES(R.string.symptom_step_times),
    BLOOD(R.string.symptom_step_blood),
    DOING(R.string.symptom_step_doing),
    WHEN(R.string.symptom_step_when),
    FEEL(R.string.symptom_step_feel),
    FAINTED(R.string.symptom_step_fainted),
    INJURED(R.string.symptom_step_injured),
    SINCE(R.string.symptom_step_since),
    WITH(R.string.symptom_step_with, multi = true),
    SEVERITY(R.string.symptom_step_severity),

    ONSET(R.string.symptom_ask_onset),
    SAME(R.string.symptom_step_same),
    PATTERN(R.string.symptom_ask_pattern),
    HOW_OFTEN(R.string.symptom_ask_how_often),
    DURATION(R.string.symptom_ask_duration),
    TREND(R.string.symptom_ask_trend),
    CHEST_NOW(R.string.symptom_ask_chest_now),
    ABOUT(R.string.symptom_ask_about, multi = true),
    HOW_LONG(R.string.symptom_ask_how_long),
    MIND_OFTEN(R.string.symptom_ask_mind_often),
    AFFECTS(R.string.symptom_ask_affects, multi = true),
    COPING(R.string.symptom_ask_coping),
    NIGHT(R.string.symptom_ask_night, multi = true),
    CRISIS(R.string.symptom_ask_crisis),

    /** Palpitations that are happening now and not stopping: NHS "Heart palpitations" puts that at 999. */
    PALP_NOW(R.string.symptom_ask_palp_now),

    /** Breathing so hard it is a struggle now: the first line of NHS "Shortness of breath"'s 999 list. */
    BREATH_NOW(R.string.symptom_ask_breath_now),

    /** The FAST signs of NHS "Stroke", now or in the last day even if they have stopped. */
    STROKE_SIGNS(R.string.symptom_ask_stroke),

    /** Someone unconscious or not responding, which NHS "When to call 999" puts first. */
    UNRESPONSIVE(R.string.symptom_ask_unresponsive),

    /** Whether the person is safe now, asked after words of suicide or self-harm ([crisisIn]). */
    CRISIS_NOW(R.string.symptom_ask_crisis_now),

    // The chest triage and the pain thread.

    /** Whether a pain marked on the chest and elsewhere spreads from the chest to there: NHS "Heart attack". */
    CHEST_SPREAD(R.string.symptom_step_chest_spread),

    /** Where it hurts: area codes in [SymptomReport.values], and the painting in [SymptomReport.strokes]. */
    PAIN_AREA(R.string.pain_areas),
    PAIN_INJURY(R.string.pain_chat_step_injury),
    PAIN_SIGNS(R.string.symptom_step_with, multi = true),
    PAIN_SIGNS_MORE(R.string.symptom_step_with, multi = true),
    PAIN_SEPSIS(R.string.symptom_step_with, multi = true),
    PAIN_SAME(R.string.pain_same_question),
    PAIN_CHANGED(R.string.pain_same_changed_question),
    PAIN_TYPES(R.string.pain_types_title, multi = true),

    /** The 1–10, in [SymptomReport.values]. */
    PAIN_NRS(R.string.pain_intensity),
    PAIN_NOTICED(R.string.pain_noticed_title),
    PAIN_STATUS(R.string.pain_status),

    /** How long a pain that has gone lasted, in minutes, in [SymptomReport.values]. */
    PAIN_LASTED(R.string.pain_lasted),
    PAIN_PAINKILLER(R.string.pain_painkiller),

    // ── The mood thread: append here, before the medicine block. ──
    // The mood thread: the Mood page's dial and its tension question, what it is about, and the
    // optional questions a changing or a very high mood brings.

    /** Whether a mood noted inside the cooldown takes this one's place: before the dial, or after a refused write. */
    MOOD_UPDATE(R.string.chat_mood_step_update),

    /** The face, 1–5, in [SymptomReport.values]: only ever the dial the person moved. */
    MOOD_DIAL(R.string.mood_title),
    MOOD_ENERGY(R.string.energy_title),
    MOOD_ABOUT(R.string.symptom_ask_about, multi = true),

    /** What "something else" is, typed, in [SymptomReport.values]; kept in the chat, never in the mood row. */
    MOOD_ELSE(R.string.chat_mood_step_else),
    MOOD_SWINGS(R.string.chat_mood_step_swings),
    MOOD_HIGH_ASK(R.string.chat_mood_step_high),
    MOOD_HIGH_SIGNS(R.string.symptom_step_with, multi = true),
    MOOD_HIGH_LONG(R.string.symptom_ask_how_long),
    MOOD_HIGH_LIFE(R.string.chat_mood_step_life),

    // ── The medicine threads: append here, last. ──

    // The medicine threads (ChatDose.kt, ChatCourse.kt). Each label is its question; a value step keeps
    // its answer in [SymptomReport.values].
    MED_WHICH(R.string.med_chat_ask_which),
    MED_NAME(R.string.med_chat_ask_name),
    MED_IS_COURSE(R.string.med_chat_ask_is_course_several),
    MED_SLOT(R.string.med_chat_ask_slot),
    MED_SAME_AGAIN(R.string.med_chat_option_another_dose),
    MED_WHEN(R.string.med_chat_ask_when),
    MED_AMOUNT(R.string.med_chat_ask_amount),
    MED_MAX(R.string.med_chat_ask_max),
    MED_SIGNS(R.string.symptom_step_with),
    MED_PURPOSE(R.string.med_chat_ask_purpose),
    MED_WHY(R.string.med_chat_ask_why),
    MED_PAIN_LINK(R.string.pain_painkiller),
    MED_FEEL(R.string.med_chat_ask_feel_label),
    MED_SERIOUS(R.string.symptom_step_with, multi = true),
    COURSE_NAME(R.string.med_chat_course_name_hint),
    COURSE_SAME(R.string.med_chat_course_same_label),
    COURSE_STOP_WHO(R.string.med_chat_ask_stop_who),
    COURSE_FOR(R.string.med_chat_ask_for),
    COURSE_DOSE(R.string.med_dose_label),
    COURSE_OFTEN(R.string.med_chat_ask_often),
    COURSE_OFTEN_OTHER(R.string.med_chat_ask_often_other),
    COURSE_TIMES(R.string.med_chat_ask_times),
    COURSE_START(R.string.med_chat_ask_start),
    COURSE_LENGTH(R.string.med_chat_ask_length),
    COURSE_TODAY(R.string.med_chat_today_label),
    COURSE_TODAY_SLOTS(R.string.med_chat_today_label),
    COURSE_FOOD(R.string.med_chat_ask_food),
    COURSE_WHO(R.string.med_chat_who_hint),
    COURSE_REMIND_SWITCH(R.string.med_chat_remind_label),
    COURSE_REMIND_NOTIFY(R.string.med_chat_remind_label),
    COURSE_REMIND_ALARM(R.string.med_chat_remind_label),

    /** "When did you take it?" once more, after a time that has not come yet today. */
    MED_WHEN_AGAIN(R.string.med_chat_ask_when),

    /** After "only when needed" or "less often than every day": a daily course, or each dose noted. */
    COURSE_PRN(R.string.med_chat_ask_prn),

    /**
     * "Have you taken more of a medicine than it says?", asked where the person's words or a typed answer
     * told of too much — a question of its own, so it never stands for a dose's own [MED_MAX] nor takes
     * its answer.
     */
    MED_MAX_WORDS(R.string.med_chat_ask_max_words),

    /** The same once more, for words of too much written after a "no" to it. */
    MED_MAX_AGAIN(R.string.med_chat_ask_max_words),
}

/** A question as one symptom puts it: the step and the answers it offers there. */
data class SymptomAsk(val step: SymptomStep, val options: List<SymptomOption>)

/**
 * The figures a symptom's card can show, each read from the journal the way the screen that owns it
 * reads it — see [symptomContext].
 */
enum class SymptomFact {
    LAST_MEAL,
    FOOD,
    STEPS,
    WORKOUT,
    WATER,
    ALCOHOL,
    CAFFEINE,
    SLEEP,
    BLOOD_PRESSURE,
    PULSE,
    TEMPERATURE,
    STOOLS,
}

/**
 * The symptoms, each with the facts its card shows, in the order it shows them, and [sourceRes]:
 * the guidance its questions and safety lines were written from, named behind the "?" beside a
 * safety line.
 *
 * The questions follow the NHS pages each symptom links to (nhs.uk, "Vomiting in adults",
 * "Feeling sick (nausea)", "Dizziness", "Fainting", "Heart palpitations", "Shortness of breath",
 * "Heartburn and acid reflux", "Diarrhoea and vomiting", "Fever in adults", "Tiredness and
 * fatigue"): the things those pages say a doctor will ask about, as a few words each.
 *
 * [record] is what a thread of the topic files: a symptom entry for every topic up to [OTHER], and
 * for the four after it the record of its own page — a pain, a mood, a dose taken, a new course —
 * written through that page's own door ([TopicScript.wants], [ChatFiling]).
 */
enum class Symptom(
    val labelRes: Int,
    val facts: List<SymptomFact>,
    val sourceRes: Int,
    /** A feeling rather than something in the body: asked about in the chat's other voice, and checked for safety. */
    val mind: Boolean = false,
    /** What a thread of this topic files ([TopicScript]). */
    val record: ChatRecord = ChatRecord.SYMPTOM,
) {
    VOMITING(
        R.string.symptom_vomiting,
        listOf(SymptomFact.LAST_MEAL, SymptomFact.FOOD, SymptomFact.ALCOHOL),
        R.string.symptom_source_vomiting,
    ),
    NAUSEA(
        R.string.symptom_nausea,
        listOf(SymptomFact.LAST_MEAL, SymptomFact.FOOD, SymptomFact.ALCOHOL),
        R.string.symptom_source_nausea,
    ),

    /**
     * Set beside how much the day moved, the last workout, how long since food, what was drunk, the
     * last pressure and the night's sleep: the things NHS "Dizziness" and "Fainting" name around
     * feeling faint — getting up quickly, not eating or drinking enough, alcohol, low blood
     * pressure, exertion.
     */
    DIZZINESS(
        R.string.symptom_dizziness,
        listOf(
            SymptomFact.STEPS,
            SymptomFact.WORKOUT,
            SymptomFact.LAST_MEAL,
            SymptomFact.WATER,
            SymptomFact.ALCOHOL,
            SymptomFact.CAFFEINE,
            SymptomFact.BLOOD_PRESSURE,
            SymptomFact.SLEEP,
        ),
        R.string.symptom_source_dizziness,
    ),

    /**
     * The same figures as a dizzy spell, which is so often how a faint begins. Written out again
     * rather than shared through a file-level list: an enum entry's arguments that reach into this
     * file's other top-level values would start their initialisation, and [SYMPTOM_ASKS] would be
     * built while this enum is still half made.
     */
    FAINTING(
        R.string.symptom_fainting,
        listOf(
            SymptomFact.STEPS,
            SymptomFact.WORKOUT,
            SymptomFact.LAST_MEAL,
            SymptomFact.WATER,
            SymptomFact.ALCOHOL,
            SymptomFact.CAFFEINE,
            SymptomFact.BLOOD_PRESSURE,
            SymptomFact.SLEEP,
        ),
        R.string.symptom_source_fainting,
    ),
    PALPITATIONS(
        R.string.symptom_palpitations,
        listOf(SymptomFact.CAFFEINE, SymptomFact.ALCOHOL, SymptomFact.WORKOUT, SymptomFact.SLEEP, SymptomFact.PULSE),
        R.string.symptom_source_palpitations,
    ),
    BREATHLESSNESS(
        R.string.symptom_breathlessness,
        listOf(SymptomFact.WORKOUT, SymptomFact.STEPS),
        R.string.symptom_source_breathlessness,
    ),
    HEARTBURN(
        R.string.symptom_heartburn,
        listOf(SymptomFact.LAST_MEAL, SymptomFact.ALCOHOL, SymptomFact.CAFFEINE),
        R.string.symptom_source_heartburn,
    ),
    DIARRHOEA(
        R.string.symptom_diarrhoea,
        listOf(SymptomFact.LAST_MEAL, SymptomFact.FOOD, SymptomFact.ALCOHOL, SymptomFact.STOOLS),
        R.string.symptom_source_diarrhoea,
    ),
    FEVER(R.string.symptom_fever, listOf(SymptomFact.TEMPERATURE), R.string.symptom_source_fever),
    TIREDNESS(
        R.string.symptom_tiredness,
        listOf(SymptomFact.SLEEP, SymptomFact.STEPS, SymptomFact.WORKOUT, SymptomFact.FOOD),
        R.string.symptom_source_tiredness,
    ),

    // The chat's topics, appended for the payload's sake as the answers are.

    CHEST_PAIN(
        R.string.symptom_chest_pain,
        listOf(SymptomFact.WORKOUT, SymptomFact.STEPS, SymptomFact.BLOOD_PRESSURE, SymptomFact.PULSE),
        R.string.symptom_source_chest_pain,
    ),
    WORRY(
        R.string.symptom_worry,
        listOf(SymptomFact.SLEEP, SymptomFact.CAFFEINE, SymptomFact.ALCOHOL, SymptomFact.STEPS),
        R.string.symptom_source_worry,
        mind = true,
    ),
    LOW_MOOD(
        R.string.symptom_option_low_mood,
        listOf(SymptomFact.SLEEP, SymptomFact.STEPS, SymptomFact.ALCOHOL),
        R.string.symptom_source_low_mood,
        mind = true,
    ),
    STRESS(
        R.string.symptom_stress,
        listOf(SymptomFact.SLEEP, SymptomFact.CAFFEINE, SymptomFact.ALCOHOL, SymptomFact.STEPS),
        R.string.symptom_source_stress,
        mind = true,
    ),

    /** Trouble sleeping, named without a cause: whether worry is part of it is asked after. */
    SLEEP_WORRY(
        R.string.symptom_sleep_worry,
        listOf(SymptomFact.SLEEP, SymptomFact.CAFFEINE, SymptomFact.ALCOHOL),
        R.string.symptom_source_sleep_worry,
        mind = true,
    ),

    /** What none of the others names; the entry is titled with the person's own words. */
    OTHER(
        R.string.symptom_other,
        listOf(SymptomFact.SLEEP, SymptomFact.STEPS, SymptomFact.LAST_MEAL, SymptomFact.WATER),
        R.string.symptom_source_other,
    ),

    // The records a chat files of their own kinds. Appended, never inserted. None is a
    // feeling: a good mood must never be met with the chat's other voice, its pinned line or its
    // crisis question.

    PAIN(R.string.symptom_topic_pain, emptyList(), R.string.symptom_source_pain, record = ChatRecord.PAIN),
    MOOD(R.string.symptom_topic_mood, emptyList(), R.string.symptom_source_low_mood, record = ChatRecord.MOOD),
    DOSE(R.string.symptom_topic_dose, emptyList(), R.string.symptom_source_medicines, record = ChatRecord.DOSE),
    COURSE(R.string.symptom_topic_course, emptyList(), R.string.symptom_source_medicines, record = ChatRecord.COURSE),
}

/** What a topic of the chat files: a symptom entry, or the record of its own page. */
enum class ChatRecord { SYMPTOM, PAIN, MOOD, DOSE, COURSE }

private val YES_NO = listOf(SymptomOption.YES, SymptomOption.NO)
private val HOW_MANY = listOf(SymptomOption.ONCE, SymptomOption.TWICE, SymptomOption.THREE_PLUS)
private val DOING = listOf(
    SymptomOption.STANDING_UP,
    SymptomOption.EXERCISING,
    SymptomOption.AFTER_EXERCISE,
    SymptomOption.SITTING_LYING,
    SymptomOption.TURNING_HEAD,
    SymptomOption.WALKING,
)

/** What may have brought sickness on: the causes NHS "Vomiting in adults" lists, in a few words. */
private val SICK_CAUSES = listOf(
    SymptomOption.ATE_TOO_MUCH,
    SymptomOption.FOOD_OFF,
    SymptomOption.ALCOHOL,
    SymptomOption.MOTION,
    SymptomOption.MEDICINE,
    SymptomOption.STOMACH_BUG,
)

private fun ask(step: SymptomStep, vararg options: SymptomOption) = SymptomAsk(step, options.toList())

/**
 * Each symptom's own questions, between "which symptom" and "how bad". [SymptomOption.PREGNANCY] is
 * among the causes of sickness here and taken off for a profile that is not female ([symptomAsks]).
 *
 * "I made myself sick" is offered beside the other causes, in the same plain words, because making
 * oneself sick is the first SCOFF question (Morgan, Reid and Lacey, BMJ 1999;319:1467) and an
 * eating disorder is easier to talk about when the record already holds it; it is answered with one
 * kind line, not an alarm ([SymptomAlert.SELF_INDUCED]).
 */
private val SYMPTOM_ASKS: Map<Symptom, List<SymptomAsk>> = mapOf(
    Symptom.VOMITING to listOf(
        SymptomAsk(SymptomStep.AFTER_MEAL, YES_NO),
        SymptomAsk(
            SymptomStep.CAUSE,
            SICK_CAUSES + SymptomOption.SELF_INDUCED + SymptomOption.PREGNANCY + SymptomOption.NOT_SURE,
        ),
        SymptomAsk(SymptomStep.TIMES, HOW_MANY),
        SymptomAsk(SymptomStep.BLOOD, listOf(SymptomOption.NO, SymptomOption.YES)),
    ),
    Symptom.NAUSEA to listOf(
        SymptomAsk(SymptomStep.AFTER_MEAL, YES_NO),
        SymptomAsk(
            SymptomStep.CAUSE,
            SICK_CAUSES + SymptomOption.STRESS + SymptomOption.PREGNANCY + SymptomOption.NOT_SURE,
        ),
    ),
    Symptom.DIZZINESS to listOf(
        SymptomAsk(SymptomStep.DOING, DOING),
        ask(SymptomStep.FEEL, SymptomOption.LIGHT_HEADED, SymptomOption.SPINNING, SymptomOption.UNSTEADY),
        SymptomAsk(SymptomStep.FAINTED, listOf(SymptomOption.NO, SymptomOption.YES)),
        ask(
            SymptomStep.WITH,
            SymptomOption.CHEST_PAIN,
            SymptomOption.RACING_HEART,
            SymptomOption.ONE_SIDE_WEAK,
            SymptomOption.SPEECH,
            SymptomOption.NONE,
        ),
    ),
    Symptom.FAINTING to listOf(
        SymptomAsk(SymptomStep.DOING, DOING),
        SymptomAsk(SymptomStep.INJURED, listOf(SymptomOption.NO, SymptomOption.YES)),
        ask(
            SymptomStep.WITH,
            SymptomOption.CHEST_PAIN,
            SymptomOption.RACING_HEART,
            SymptomOption.BREATHLESS,
            SymptomOption.DURING_EXERCISE,
            SymptomOption.NONE,
        ),
    ),
    Symptom.PALPITATIONS to listOf(
        ask(
            SymptomStep.WHEN,
            SymptomOption.AT_REST,
            SymptomOption.EXERCISING,
            SymptomOption.AFTER_EXERCISE,
            SymptomOption.AT_NIGHT,
        ),
        ask(
            SymptomStep.FEEL,
            SymptomOption.RACING,
            SymptomOption.POUNDING,
            SymptomOption.IRREGULAR,
            SymptomOption.FLUTTERING,
        ),
        ask(
            SymptomStep.WITH,
            SymptomOption.CHEST_PAIN,
            SymptomOption.BREATHLESS,
            SymptomOption.DIZZY_FAINT,
            SymptomOption.NONE,
        ),
    ),
    Symptom.BREATHLESSNESS to listOf(
        ask(
            SymptomStep.WHEN,
            SymptomOption.AT_REST,
            SymptomOption.WALKING_STAIRS,
            SymptomOption.HARD_EXERCISE,
            SymptomOption.LYING_NIGHT,
        ),
        ask(
            SymptomStep.WITH,
            SymptomOption.CHEST_PAIN,
            SymptomOption.WHEEZING,
            SymptomOption.COUGH,
            SymptomOption.BLUE_LIPS,
            SymptomOption.NONE,
        ),
    ),
    Symptom.HEARTBURN to listOf(
        ask(
            SymptomStep.WHEN,
            SymptomOption.AFTER_EATING,
            SymptomOption.LYING_BENDING,
            SymptomOption.AT_NIGHT,
            SymptomOption.EMPTY_STOMACH,
        ),
        ask(
            SymptomStep.WITH,
            SymptomOption.SWALLOWING,
            SymptomOption.VOMITING,
            SymptomOption.BLACK_STOOLS,
            SymptomOption.WEIGHT_LOSS,
            SymptomOption.NONE,
        ),
    ),
    Symptom.DIARRHOEA to listOf(
        SymptomAsk(SymptomStep.TIMES, HOW_MANY),
        ask(
            SymptomStep.CAUSE,
            SymptomOption.FOOD_OFF,
            SymptomOption.MEDICINE,
            SymptomOption.STOMACH_BUG,
            SymptomOption.TRAVEL,
            SymptomOption.NOT_SURE,
        ),
        ask(
            SymptomStep.WITH,
            SymptomOption.BLOOD,
            SymptomOption.BLACK_STOOLS,
            SymptomOption.DEHYDRATION,
            SymptomOption.FEVER,
            SymptomOption.NONE,
        ),
    ),
    Symptom.FEVER to listOf(
        ask(
            SymptomStep.WITH,
            SymptomOption.CHILLS,
            SymptomOption.SWEATING,
            SymptomOption.SORE_THROAT,
            SymptomOption.COUGH,
            SymptomOption.RASH,
            SymptomOption.STIFF_NECK,
            SymptomOption.NONE,
        ),
    ),
    Symptom.TIREDNESS to listOf(
        ask(SymptomStep.SINCE, SymptomOption.TODAY, SymptomOption.FEW_DAYS, SymptomOption.OVER_TWO_WEEKS),
        ask(
            SymptomStep.WITH,
            SymptomOption.POOR_SLEEP,
            SymptomOption.LOW_MOOD,
            SymptomOption.BREATHLESS,
            SymptomOption.WEIGHT_LOSS,
            SymptomOption.NONE,
        ),
    ),
)

/** The questions [symptom] puts between "which" and "how bad", for a profile that is [female] or not. */
fun symptomAsks(symptom: Symptom, female: Boolean): List<SymptomAsk> =
    SYMPTOM_ASKS[symptom].orEmpty().map { ask ->
        if (female) ask else ask.copy(options = ask.options - SymptomOption.PREGNANCY)
    }

/** Every step asked for [symptom], in order; only the first while none is chosen. */
fun symptomSteps(symptom: Symptom?): List<SymptomStep> = if (symptom == null) {
    listOf(SymptomStep.SYMPTOM)
} else {
    listOf(SymptomStep.SYMPTOM) + SYMPTOM_ASKS[symptom].orEmpty().map { it.step } + SymptomStep.SEVERITY
}

/**
 * The answers after a tap on [option] in a question that takes several: on and off again, with
 * [EXCLUSIVE_OPTIONS] standing alone, in the order the options are offered.
 */
fun toggledOption(chosen: List<SymptomOption>, option: SymptomOption): List<SymptomOption> = when {
    option in chosen -> chosen - option
    option in EXCLUSIVE_OPTIONS -> listOf(option)
    else -> (chosen - EXCLUSIVE_OPTIONS + option).sortedBy { it.ordinal }
}

/**
 * What the journal held at the moment a symptom was told, or on the day it was filed against.
 *
 * [sameDay] when the entry was told on the day it happened, so "how long before" is a real gap:
 * only then are the last meal, the minutes since a workout ended and the latest readings kept. An
 * entry filed for a day gone by keeps that day's totals and nothing measured from a moment, since
 * its moment is only the midday [dayInstant] gives it.
 *
 * Every figure is null where there was nothing logged, which is not the same as none: a day with no
 * water written down is a day nobody wrote water down. The one exception is [noWorkout], which
 * says in so many words that no workout was logged that day — the answer a dizzy spell is asked for.
 */
data class SymptomContext(
    val sameDay: Boolean,
    val mealTitle: String? = null,
    val mealKcal: Int? = null,
    val mealMinutesBefore: Int? = null,
    val foodKcal: Int? = null,
    val foodTargetKcal: Int? = null,
    val steps: Long? = null,
    val workoutCode: String? = null,
    val workoutTitle: String? = null,
    val workoutMinutes: Int? = null,
    val workoutEndedBefore: Int? = null,
    val noWorkout: Boolean = false,
    val waterMl: Int? = null,
    val waterTargetMl: Int? = null,
    val standardDrinks: Double? = null,
    val caffeineMg: Int? = null,
    val sleepMinutes: Int? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val pressureBefore: Int? = null,
    val pulseBpm: Int? = null,
    val pulseBefore: Int? = null,
    val temperatureC: Double? = null,
    val temperatureBefore: Int? = null,
    val stools: Int? = null,
    val looseStools: Int? = null,
) {
    /**
     * Which of [facts] have a figure here, in the order given: the lines a card prints. The entry
     * keeps the whole snapshot and the card picks its symptom's lines from it here, so an entry
     * changed to another symptom still has that one's figures from the same moment.
     */
    fun present(facts: List<SymptomFact>): List<SymptomFact> = facts.filter { has(it) }

    private fun has(fact: SymptomFact): Boolean = if (fact == SymptomFact.WORKOUT) {
        workoutTitle != null || workoutCode != null || noWorkout
    } else {
        figures(fact).all { it != null }
    }

    /**
     * The figures a fact's line is printed from, every one of which it needs. The workout's is
     * decided apart in [has]: "no workout logged" is a line of its own, with no figure behind it.
     */
    private fun figures(fact: SymptomFact): List<Any?> = when (fact) {
        SymptomFact.LAST_MEAL -> listOf(mealTitle)
        SymptomFact.FOOD -> listOf(foodKcal, foodTargetKcal)
        SymptomFact.STEPS -> listOf(steps)
        SymptomFact.WORKOUT -> listOf(workoutTitle, workoutCode)
        SymptomFact.WATER -> listOf(waterMl, waterTargetMl)
        SymptomFact.ALCOHOL -> listOf(standardDrinks)
        SymptomFact.CAFFEINE -> listOf(caffeineMg)
        SymptomFact.SLEEP -> listOf(sleepMinutes)
        SymptomFact.BLOOD_PRESSURE -> listOf(systolic, diastolic, pressureBefore)
        SymptomFact.PULSE -> listOf(pulseBpm, pulseBefore)
        SymptomFact.TEMPERATURE -> listOf(temperatureC, temperatureBefore)
        SymptomFact.STOOLS -> listOf(stools)
    }
}

/** The last meal, the last workout and the latest readings are looked for this far back from the report. */
private const val SYMPTOM_LOOKBACK_MS = 24 * 60 * 60_000L
private const val SYMPTOM_MINUTE_MS = 60_000L

/**
 * The snapshot a new entry carries, from [rows] — the report day's rows and the day before's, which
 * is where a last meal at 23:00 is for a sickness at 07:00 — and [steps], the day's count as the
 * home screen reads it.
 *
 * Each figure is read the way its own screen reads it, so the card can never disagree with the
 * screen the person checks it against: the food and its target as the home card puts them (meals'
 * calories against [dailyFoodTarget]: [energyBudget]'s target plus the day's movement as
 * [movedCalories] counts it, steps included), the water against [waterTargetMl], alcohol in WHO
 * standard drinks ([STANDARD_DRINK_G]) as the habits screen counts them, caffeine as the drink dialog
 * adds it up (the cup rows' milligrams in servingG, and caffeineMg on any other row), the night as
 * [nightEndingOn] joins it, the readings through [vitalSeries] — whose gates already leave out a
 * workout's pulse and a pressure with its halves the wrong way round — and the bathroom visits as
 * the bathroom screen tells a loose one.
 *
 * For [day] other than [today] only the day's totals are kept; see [SymptomContext.sameDay].
 */
fun symptomContext(
    rows: List<JournalEntry>,
    day: LocalDate,
    today: LocalDate,
    now: Long,
    steps: Long?,
    profile: UserProfile,
): SymptomContext {
    val sameDay = day == today
    // Told today, the day so far: a row stamped after the moment of telling was not there to see.
    val onDay = rows.filter { it.day == day.toString() && (!sameDay || it.occurredAt <= now) }
    val totals = dayTotals(onDay, day, steps, profile)
    if (!sameDay) return totals.copy(sameDay = false)
    val recent = rows.filter { it.occurredAt <= now && now - it.occurredAt <= SYMPTOM_LOOKBACK_MS }
    val meal = recent.filter { it.kind == KIND_EAT }.maxByOrNull { it.occurredAt }
    val workout = lastWorkout(onDay, now)
    return totals.copy(
        mealTitle = meal?.title,
        mealKcal = meal?.calories?.toInt(),
        mealMinutesBefore = meal?.let { minutesBetween(it.occurredAt, now) },
        workoutCode = workout?.first?.activityCode,
        workoutTitle = workout?.first?.title,
        workoutMinutes = workout?.first?.durationMinutes?.toInt(),
        workoutEndedBefore = workout?.second?.let { minutesBetween(it, now) },
        noWorkout = workout == null,
    ).withReadings(recent, now, profile)
}

/** What a day adds up to, with nothing measured from a moment: the whole of an earlier day's card. */
private fun dayTotals(onDay: List<JournalEntry>, day: LocalDate, steps: Long?, profile: UserProfile): SymptomContext {
    val meals = onDay.filter { it.kind == KIND_EAT }
    val water = onDay.filter { it.kind == KIND_WATER }.sumOf { it.servingG }
    val alcohol = onDay.filter { it.kind == KIND_ALCOHOL }.sumOf { it.servingG }
    val caffeine = onDay.sumOf { if (it.kind == KIND_CAFFEINE) it.servingG else it.caffeineMg ?: 0.0 }
    val stools = onDay.filter { it.kind == KIND_STOOL }
    val activities = onDay.filter { it.kind == KIND_ACTIVITY }
    val workout = activities.maxByOrNull { it.occurredAt }
    val moved = movedCalories(activities, stepCalories(steps ?: 0L, profile.weightKg, profile.heightCm))
    val target = dailyFoodTarget(energyBudget(profile, day.year).targetKcal, moved)
    return SymptomContext(
        sameDay = true,
        foodKcal = meals.takeIf { it.isNotEmpty() }?.sumOf { it.calories }?.wholeKcalOrNull(),
        foodTargetKcal = target.wholeKcalOrNull(),
        steps = steps?.takeIf { it > 0 },
        workoutCode = workout?.activityCode,
        workoutTitle = workout?.title,
        workoutMinutes = workout?.durationMinutes?.toInt(),
        noWorkout = workout == null,
        waterMl = water.takeIf { it > 0.0 }?.toInt(),
        waterTargetMl = waterTargetMl(profile.weightKg),
        standardDrinks = (alcohol / STANDARD_DRINK_G).takeIf { it > 0.0 },
        caffeineMg = caffeine.takeIf { it > 0.0 }?.toInt(),
        sleepMinutes = nightEndingOn(onDay)[day],
        stools = stools.size.takeIf { it > 0 },
        looseStools = stools.count { it.subtitle == StoolKind.LOOSE.name }.takeIf { stools.isNotEmpty() },
    )
}

/**
 * The day's last workout that had ended by [now], with the moment it ended — the moment it was
 * written down, or its start plus its length for the sessions that are logged as they begin, as
 * [sessionInterval] reads either.
 */
private fun lastWorkout(onDay: List<JournalEntry>, now: Long): Pair<JournalEntry, Long>? = onDay
    .filter { it.kind == KIND_ACTIVITY }
    .map { it to (it.sessionInterval(Instant.ofEpochMilli(now))?.second?.toEpochMilli() ?: it.occurredAt) }
    .filter { (_, end) -> end <= now }
    .maxByOrNull { (_, end) -> end }

/** The latest pressure, pulse and temperature in [recent], each with how long before [now] it was taken. */
private fun SymptomContext.withReadings(recent: List<JournalEntry>, now: Long, profile: UserProfile): SymptomContext {
    val pressure = vitalSeries(recent, Vital.BLOOD_PRESSURE, profile).lastOrNull()
    val pulse = vitalSeries(recent, Vital.HEART_RATE, profile).lastOrNull()
    val temperature = vitalSeries(recent, Vital.BODY_TEMPERATURE, profile).lastOrNull()
    return copy(
        systolic = pressure?.value?.toInt(),
        diastolic = pressure?.secondary?.toInt(),
        pressureBefore = pressure?.let { minutesBetween(it.occurredAt, now) },
        pulseBpm = pulse?.value?.toInt(),
        pulseBefore = pulse?.let { minutesBetween(it.occurredAt, now) },
        temperatureC = temperature?.value,
        temperatureBefore = temperature?.let { minutesBetween(it.occurredAt, now) },
    )
}

/** Rounded as the home card's [wholeKcal] rounds it; a figure that is not a number is left out. */
private fun Double.wholeKcalOrNull(): Int? = takeIf { it.isFinite() }?.roundToInt()

private fun minutesBetween(from: Long, to: Long): Int = ((to - from).coerceAtLeast(0L) / SYMPTOM_MINUTE_MS).toInt()

/**
 * One entry: the symptom, the answers to its questions, how bad, and the snapshot it was told
 * beside. A null [symptom] or [severity] is a question not answered yet; the payload never stores
 * "not answered".
 *
 * [chat] is the conversation it was told in; an entry saved without a chat (schema 1) has none.
 * [tag] is the chat's own sorting of it ([classifySymptomChat]), which nothing on screen reads.
 *
 * In a thread of a chat that files another record ([ChatThread]), [values] holds the answers that
 * are not options — areas as region codes, a 1–10, a number of minutes — one string per step; [meta]
 * the facts a topic froze when a step was answered, keyed `"STEP.suffix"` (the earlier pain a "same
 * pain?" question found), never an answer; and [strokes] the painting sent from the in-chat body map,
 * kept as the pain page keeps its own.
 */
data class SymptomReport(
    val symptom: Symptom? = null,
    val answers: Map<SymptomStep, List<SymptomOption>> = emptyMap(),
    val severity: SymptomSeverity? = null,
    val context: SymptomContext? = null,
    val chat: SymptomChat? = null,
    val symptomTag: SymptomTag? = null,
    val values: Map<SymptomStep, String> = emptyMap(),
    val meta: Map<String, String> = emptyMap(),
    val strokes: List<PainStroke> = emptyList(),
)

/**
 * What is saved: the answers to the questions asked, and the snapshot whole. An answer to a
 * question the symptom no longer asks — it was changed on the way — does not stand; the snapshot
 * does, since it is the moment, not the symptom, and an entry changed to another symptom later
 * still needs that one's figures ([SymptomContext.present]).
 *
 * For a chat, "asked" is a question with its turn in the chat: ticks on a question still being
 * answered are not an answer yet, and how bad stands once its own question was taken. The same for
 * a value, for a frozen fact (by its step's name before the dot) and for the painting, which stands
 * only once the step that holds it was taken. A fact of the thread itself rather than of a step —
 * its key begins [THREAD_META_PREFIX], worked out at its close — stands as long as the thread does.
 */
fun SymptomReport.forSave(): SymptomReport {
    val told = chat ?: return copy(answers = answers.filterKeys { it in symptomSteps(symptom) })
    val asked = told.askedSteps()
    val names = asked.mapTo(HashSet()) { it.name }
    return copy(
        answers = answers.filterKeys { it in asked },
        severity = severity.takeIf { SymptomStep.SEVERITY in asked },
        values = values.filterKeys { it in asked },
        meta = meta.filterKeys { it.startsWith(THREAD_META_PREFIX) || it.substringBefore('.') in names },
        strokes = strokes.takeIf { asked.any { it.kind.holds == StepHolds.PAINTING } }.orEmpty(),
    )
}

/**
 * The chat as its rules and its questions read it: [forSave], and no topic until the topic
 * question has its turn — a topic taken back with the pencil is shown chosen, but asks nothing.
 */
fun SymptomReport.asTold(): SymptomReport {
    val told = chat ?: return this
    val saved = forSave()
    return if (SymptomStep.SYMPTOM in told.askedSteps()) saved else saved.copy(symptom = null)
}

/**
 * The payload of an entry saved without a chat; a chat's is [SYMPTOM_CHAT_SCHEMA]. Written first so a
 * reader knows which.
 */
private const val SYMPTOM_SCHEMA = 1

/** The payload that carries a chat and its tag. */
private const val SYMPTOM_CHAT_SCHEMA = 2

/** A symptom row a chat of several threads filed: the chat named by its key, its words kept in the chat. */
private const val SYMPTOM_THREAD_SCHEMA = 3

/**
 * The payload, written by hand in one fixed order, as the pain row's is and for its reason: the
 * payload is part of a row's identity in the backup, and org.json's own writer orders keys by a hash
 * map on the JVM and by insertion on the device. Answers are written in step order and each one's
 * options in the order they are offered; optional keys only when set.
 *
 * The free texts — a meal's and a workout's title, and what the person wrote in a chat — go through
 * [JSONObject.quote]; everything else is an enum name, a number or a boolean. The keys come in the
 * order schema, symptom, severity, answers, values, meta, strokes, chat, context, tag; a report with
 * no chat is written byte for byte as a stored schema-1 row holds it.
 *
 * With a [chatKey] it is the row a thread of a stored chat files ([ChatRow]): schema 3, the chat
 * named by its key where the chat stood, then the moment of the earlier chat it continues; no turns,
 * no values, no frozen facts and no painting, which are the chat's to keep.
 */
fun encodeSymptomPayload(report: SymptomReport, chatKey: String? = null): String {
    val schema = when {
        chatKey != null -> SYMPTOM_THREAD_SCHEMA
        report.chat != null || report.symptomTag != null -> SYMPTOM_CHAT_SCHEMA
        else -> SYMPTOM_SCHEMA
    }
    val fields = mutableListOf("\"schema\":$schema")
    report.symptom?.let { fields += "\"symptom\":\"${it.name}\"" }
    report.severity?.let { fields += "\"severity\":\"${it.name}\"" }
    val answers = SymptomStep.entries.mapNotNull { step ->
        report.answers[step]?.distinct()?.sortedBy { it.ordinal }?.takeIf { it.isNotEmpty() }
            ?.let { options -> "\"${step.name}\":" + options.joinToString(",", "[", "]") { "\"${it.name}\"" } }
    }
    if (answers.isNotEmpty()) fields += "\"answers\":" + answers.joinToString(",", "{", "}")
    fields += if (chatKey == null) threadParts(report) + chatPart(report) else namedChat(report, chatKey)
    report.context?.let { fields += "\"context\":" + encodeContext(it) }
    report.symptomTag?.let { fields += "\"tag\":" + encodeSymptomTag(it) }
    return fields.joinToString(",", "{", "}")
}

/** The chat carried in the payload, when there is one. */
private fun chatPart(report: SymptomReport): List<String> =
    listOfNotNull(report.chat?.let { "\"chat\":" + encodeChat(it) })

/** The stored chat a thread's row names in place of carrying it, and the moment of the earlier chat it continues. */
private fun namedChat(report: SymptomReport, chatKey: String): List<String> =
    listOfNotNull("\"chat\":" + JSONObject.quote(chatKey), report.chat?.sameAs?.let { "\"same\":$it" })

/**
 * A thread's answers that are not options, its frozen facts and its painting, each only when there
 * is one: values in step order, facts by key, the strokes as the pain payload stores them.
 */
private fun threadParts(report: SymptomReport): List<String> {
    val fields = mutableListOf<String>()
    val values = SymptomStep.entries.mapNotNull { step ->
        report.values[step]?.takeIf { it.isNotEmpty() }?.let { "\"${step.name}\":" + JSONObject.quote(it) }
    }
    if (values.isNotEmpty()) fields += "\"values\":" + values.joinToString(",", "{", "}")
    val meta = report.meta.entries.filter { it.key.isNotEmpty() }.sortedBy { it.key }
        .map { (key, value) -> JSONObject.quote(key) + ":" + JSONObject.quote(value) }
    if (meta.isNotEmpty()) fields += "\"meta\":" + meta.joinToString(",", "{", "}")
    painStrokesJson(report.strokes)?.let { fields += "\"strokes\":$it" }
    return fields
}

/**
 * The chat: its turns in order, then the earlier chat it continues and whether it ended, each only
 * when set. A turn is `{"q":step,"alert":…,"rule":…,"routed":true,"read":{…}}`, `{"text":…,"read":{…}}`,
 * `{"said":…,"lang":…}` or `{"narrow":"BODY"}`; `routed` marks a question the chat answered itself,
 * with no bubble, and `read` what the chat took from its model's reading of a message or of words
 * typed as an answer ([encodeChatRead]).
 */
private fun encodeChat(chat: SymptomChat): String {
    val turns = chat.turns.joinToString(",", "[", "]") { turn ->
        when (turn) {
            is ChatTurn.Asked -> listOfNotNull(
                "\"q\":\"${turn.step.name}\"",
                turn.alert?.let { "\"alert\":\"${it.name}\"" },
                turn.rule?.let { "\"rule\":" + JSONObject.quote(it) },
                "\"routed\":true".takeIf { turn.routed },
                turn.read?.let { "\"read\":" + encodeChatRead(it) },
            ).joinToString(",", "{", "}")

            is ChatTurn.Wrote -> "{\"text\":" + JSONObject.quote(turn.text) +
                turn.read?.let { ",\"read\":" + encodeChatRead(it) }.orEmpty() + "}"

            is ChatTurn.Said -> "{\"said\":" + JSONObject.quote(turn.text) + ",\"lang\":" +
                JSONObject.quote(turn.language) + "}"

            is ChatTurn.Narrowed -> "{\"narrow\":\"${turn.narrow.name}\"}"
        }
    }
    val fields = mutableListOf("\"turns\":$turns")
    chat.sameAs?.let { fields += "\"same\":$it" }
    if (chat.ended) fields += "\"ended\":true"
    return fields.joinToString(",", "{", "}")
}

/**
 * The chat back: a turn of a shape this build does not know, or one asking a question it does not
 * know, is dropped by itself and nothing else with it.
 */
private fun decodeChat(json: JSONObject): SymptomChat {
    val array = json.optJSONArray("turns")
    val turns = (0 until (array?.length() ?: 0)).mapNotNull { index ->
        val turn = array?.optJSONObject(index) ?: return@mapNotNull null
        when {
            turn.has("q") -> enumNamed<SymptomStep>(turn.optString("q"))?.let { step ->
                ChatTurn.Asked(
                    step,
                    enumNamed<SymptomAlert>(turn.optString("alert")),
                    (turn.opt("rule") as? String)?.takeIf { it.isNotEmpty() },
                    routed = turn.optBoolean("routed"),
                    read = turn.optJSONObject("read")?.let(::decodeChatRead),
                )
            }

            turn.has("text") -> (turn.opt("text") as? String)?.let {
                ChatTurn.Wrote(it, turn.optJSONObject("read")?.let(::decodeChatRead))
            }

            turn.has("said") -> (turn.opt("said") as? String)?.let {
                ChatTurn.Said(it, turn.optString("lang"))
            }

            turn.has("narrow") -> enumNamed<ChatNarrow>(turn.optString("narrow"))?.let { ChatTurn.Narrowed(it) }

            else -> null
        }
    }
    return SymptomChat(
        turns = turns,
        sameAs = (json.opt("same") as? Number)?.toLong(),
        ended = json.optBoolean("ended"),
    )
}

/**
 * The snapshot's keys. None is a key a vital or an exporter reads — "systolicMmhg", "pulseRateBpm",
 * "temperatureCelsius", "type" — since those are looked for by pattern anywhere in a payload, and
 * a symptom row would otherwise join the vitals series as a second copy of the reading it quotes.
 */
private fun encodeContext(context: SymptomContext): String {
    val fields = mutableListOf("\"today\":${context.sameDay}")
    fun put(key: String, value: Any?) {
        when (value) {
            null -> Unit

            is String -> fields += "\"$key\":" + JSONObject.quote(value)

            // JSON has no Infinity or NaN: a figure that is not a number is left out, as [wholeKcalOrNull] leaves it.
            is Double -> if (value.isFinite()) fields += "\"$key\":$value"

            else -> fields += "\"$key\":$value"
        }
    }
    put("mealTitle", context.mealTitle)
    put("mealKcal", context.mealKcal)
    put("mealAgo", context.mealMinutesBefore)
    put("foodKcal", context.foodKcal)
    put("foodTarget", context.foodTargetKcal)
    put("steps", context.steps)
    put("workoutCode", context.workoutCode)
    put("workoutTitle", context.workoutTitle)
    put("workoutMin", context.workoutMinutes)
    put("workoutAgo", context.workoutEndedBefore)
    if (context.noWorkout) put("noWorkout", true)
    put("waterMl", context.waterMl)
    put("waterTarget", context.waterTargetMl)
    put("drinks", context.standardDrinks)
    put("caffeine", context.caffeineMg)
    put("sleepMin", context.sleepMinutes)
    put("bpHigh", context.systolic)
    put("bpLow", context.diastolic)
    put("bpAgo", context.pressureBefore)
    put("pulse", context.pulseBpm)
    put("pulseAgo", context.pulseBefore)
    put("tempC", context.temperatureC)
    put("tempAgo", context.temperatureBefore)
    put("stools", context.stools)
    put("looseStools", context.looseStools)
    return fields.joinToString(",", "{", "}")
}

/**
 * The entry back out of a payload. Names this build does not know — a symptom, a question, an
 * answer, a key a later version added — are skipped rather than guessed at; a payload that is not
 * JSON at all reads as an empty report.
 */
fun decodeSymptomPayload(payload: String?): SymptomReport {
    val json = payload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return SymptomReport()
    return decodeSymptomReport(json)
}

/**
 * [decodeSymptomPayload] over a parsed object: a chat's thread is one ([decodeChatLog]). A value of
 * a step this build does not know, a frozen fact of one, and a stroke that is not a stroke are
 * skipped like the rest. A schema-3 row's `"chat"` is the chat's key, a string, so it reads as no chat
 * here; [chatKeyOf] reads the key.
 */
internal fun decodeSymptomReport(json: JSONObject): SymptomReport {
    val answers = json.optJSONObject("answers")
    val values = json.optJSONObject("values")
    val meta = json.optJSONObject("meta")
    val steps = SymptomStep.entries.mapTo(HashSet()) { it.name }
    return SymptomReport(
        symptom = enumNamed<Symptom>(json.optString("symptom")),
        severity = enumNamed<SymptomSeverity>(json.optString("severity")),
        answers = SymptomStep.entries.mapNotNull { step ->
            answers?.optJSONArray(step.name).names()
                .mapNotNull { enumNamed<SymptomOption>(it) }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { step to it }
        }.toMap(),
        context = json.optJSONObject("context")?.let(::decodeContext),
        chat = json.optJSONObject("chat")?.let(::decodeChat),
        symptomTag = json.optJSONObject("tag")?.let(::decodeSymptomTag),
        values = SymptomStep.entries.mapNotNull { step ->
            (values?.opt(step.name) as? String)?.takeIf { it.isNotEmpty() }?.let { step to it }
        }.toMap(),
        meta = meta?.keys()?.asSequence().orEmpty()
            .filter { it.startsWith(THREAD_META_PREFIX) || it.substringBefore('.') in steps }
            .mapNotNull { key -> (meta?.opt(key) as? String)?.let { key to it } }
            .toMap(),
        strokes = painStrokesFrom(json.optJSONArray("strokes")),
    )
}

/** The key of the stored chat a schema-3 symptom row was told in, or null for any other row. */
internal fun chatKeyOf(row: JournalEntry): String? {
    if (row.kind != KIND_SYMPTOM) return null
    val json = row.measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    return (json.opt("chat") as? String)?.takeIf { it.isNotEmpty() }
}

private inline fun <reified T : Enum<T>> enumNamed(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

private fun JSONArray?.names(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
}

private fun decodeContext(json: JSONObject): SymptomContext {
    fun int(key: String): Int? = (json.opt(key) as? Number)?.toInt()
    fun text(key: String): String? = (json.opt(key) as? String)?.takeIf { it.isNotEmpty() }
    return SymptomContext(
        sameDay = json.optBoolean("today"),
        mealTitle = text("mealTitle"),
        mealKcal = int("mealKcal"),
        mealMinutesBefore = int("mealAgo"),
        foodKcal = int("foodKcal"),
        foodTargetKcal = int("foodTarget"),
        steps = (json.opt("steps") as? Number)?.toLong(),
        workoutCode = text("workoutCode"),
        workoutTitle = text("workoutTitle"),
        workoutMinutes = int("workoutMin"),
        workoutEndedBefore = int("workoutAgo"),
        noWorkout = json.optBoolean("noWorkout"),
        waterMl = int("waterMl"),
        waterTargetMl = int("waterTarget"),
        standardDrinks = (json.opt("drinks") as? Number)?.toDouble(),
        caffeineMg = int("caffeine"),
        sleepMinutes = int("sleepMin"),
        systolic = int("bpHigh"),
        diastolic = int("bpLow"),
        pressureBefore = int("bpAgo"),
        pulseBpm = int("pulse"),
        pulseBefore = int("pulseAgo"),
        temperatureC = (json.opt("tempC") as? Number)?.toDouble(),
        temperatureBefore = int("tempAgo"),
        stools = int("stools"),
        looseStools = int("looseStools"),
    )
}

/**
 * The journal row for an entry.
 *
 * [now] is the moment it was told; an entry filed against an earlier [day] has no real moment of its
 * own and takes [dayInstant]'s midday of that day. Correcting keeps [previous]'s id, day and moment.
 * The title is the symptom's name in the language showing now, like every row's; the day list's
 * line under it reads the payload ([symptomSummaryLine]), so how bad is said in whatever language is
 * showing then, and the name too where the title is in another.
 *
 * [chatKey] names the stored chat a thread files this row from: the payload is then schema 3, the
 * chat named instead of carried ([encodeSymptomPayload]), and the chat's note is the chat's.
 */
internal fun symptomEntry(
    title: String,
    report: SymptomReport,
    day: LocalDate,
    today: LocalDate,
    now: Long,
    note: String = "",
    previous: JournalEntry? = null,
    chatKey: String? = null,
): JournalEntry = JournalEntry(
    id = previous?.id ?: 0,
    day = previous?.day ?: day.toString(),
    occurredAt = previous?.occurredAt ?: if (day == today) now else dayInstant(day.toString()),
    kind = KIND_SYMPTOM,
    title = title,
    calories = 0.0,
    measurementPayloadJson = encodeSymptomPayload(report, chatKey),
    note = note.trim(),
)

/**
 * The day list's line under the row's [title]: how bad, from the enum name, in the language showing
 * now. The title already names the symptom, so the name is printed again only when the title is in
 * another language than the one showing — "Vomiting" over "Рвота · Умеренно" — rather than twice
 * in the same words. "Something else" is never printed under the person's own words for it.
 */
fun symptomSummaryLine(
    report: SymptomReport,
    title: String,
    name: (Symptom) -> String,
    severity: (SymptomSeverity) -> String,
): String = listOfNotNull(
    report.symptom?.takeIf { it != Symptom.OTHER }?.let(name)?.takeIf { it != title },
    report.severity?.let(severity),
).joinToString(" · ")
