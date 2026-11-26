package com.pwnall.health

/**
 * What an answer calls for, most urgent first: the four levels the NHS pages use — call 999, get
 * advice today (an urgent GP appointment or NHS 111), see a GP, and look after yourself.
 */
enum class SymptomUrgency { EMERGENCY, SAME_DAY, GP, SELF_CARE }

/**
 * The lines an answer can call for. They say what to do, never what it is; [SELF_INDUCED] is not a
 * warning at all but the one kind line "I made myself sick" is met with. Appended, never inserted:
 * a chat stores the line it showed by name.
 *
 * The feelings' lines are their own words for the same four levels. [SAFETY_NET] answers "I would
 * rather not say" to the question about suicide with the same ways to help as [URGENT_MIND], at
 * the same level: declining to answer can be how such thoughts are kept hidden (Samaritans, "AI
 * chatbots and suicide and self-harm", 2026), and NICE NG225 1.6 warns against reading any answer
 * as low risk.
 */
enum class SymptomAlert(val textRes: Int, val urgency: SymptomUrgency) {
    EMERGENCY(R.string.symptom_alert_emergency, SymptomUrgency.EMERGENCY),
    URGENT(R.string.symptom_alert_urgent, SymptomUrgency.SAME_DAY),
    DOCTOR(R.string.symptom_alert_doctor, SymptomUrgency.GP),
    SELF_INDUCED(R.string.symptom_self_induced, SymptomUrgency.SELF_CARE),
    EMERGENCY_MIND(R.string.symptom_alert_emergency_mind, SymptomUrgency.EMERGENCY),
    URGENT_MIND(R.string.symptom_alert_urgent_mind, SymptomUrgency.SAME_DAY),
    DOCTOR_MIND(R.string.symptom_alert_doctor_mind, SymptomUrgency.GP),
    SAFETY_NET(R.string.symptom_alert_safety_net, SymptomUrgency.SAME_DAY),

    // The chest triage and the pain thread.

    /** NHS "Go to A&E": to be seen at an emergency department now. Ends the chat as a 999 line does. */
    IN_PERSON_NOW(R.string.pain_chat_alert_in_person, SymptomUrgency.EMERGENCY),

    /** NHS "Ask for an urgent GP appointment or get help from NHS 111 now": advice now, not today. */
    URGENT_NOW(R.string.symptom_alert_urgent_now, SymptomUrgency.SAME_DAY),

    // ── The mood thread: append here, before the medicine block. ──

    /** A high mood with the signs NHS "Bipolar disorder" names, a few days: worth telling a doctor. A plain card. */
    HIGH_MOOD_NET(R.string.chat_mood_high_net, SymptomUrgency.SELF_CARE),

    /** Changes of mood the person is not sure are very big: when they would be worth a doctor. A plain card. */
    MOOD_SWINGS_NET(R.string.chat_mood_swings_net, SymptomUrgency.SELF_CARE),

    /**
     * A high mood with those signs while a course the built-in list reads as an antidepressant is
     * active: advice today, as those medicines' NHS pages list mania among the serious side effects.
     * Its words name the person's medicine ([TopicScript.alertText]), never its class.
     */
    HIGH_ON_ANTIDEPRESSANT(R.string.chat_mood_alert_high_antidepressant, SymptomUrgency.SAME_DAY),

    // ── The medicine threads: append here, last. ──

    /** More of a medicine than its packet or prescription says: NHS "get help from 111" for every one checked. */
    DOSE_TOO_MUCH(R.string.med_chat_alert_too_much, SymptomUrgency.SAME_DAY),

    /** An antidepressant's serious side effects its NHS page sends to 111 now. */
    MED_SERIOUS_SIDE_EFFECT(R.string.med_chat_alert_serious, SymptomUrgency.SAME_DAY),

    /** A course stopped by the person's own decision: talk to the doctor before stopping. */
    STOP_TALK(R.string.med_chat_alert_stop, SymptomUrgency.GP),

    /** Side effects that bother: a pharmacist or the doctor; keep taking it unless told to stop. */
    SIDE_EFFECTS(R.string.med_chat_alert_side_effects, SymptomUrgency.GP),
}

/**
 * One red flag: [alert] once [holds] is true of the answers. [id] is stable — it is what a chat
 * stores beside the line it showed, and what its tag counts. [topics] empty is a rule for every
 * topic. [shownAt] is the question it is printed under for an entry saved without a chat, the later
 * of the ones it reads; a chat prints it under whichever answer first makes it true, as its
 * questions come in their own order. [sourceRes] null is the topic's own source. [reads] are the
 * other questions whose answers — or the facts frozen with them — [holds] reads: with [shownAt],
 * what no model may skip or prefill ([OWED_STEPS]); `RuleReadsTest` holds every rule to what it reads.
 */
internal class AlertRule(
    val id: String,
    val topics: Set<Symptom>,
    val shownAt: SymptomStep,
    val alert: SymptomAlert,
    val sourceRes: Int? = null,
    /**
     * Where the line comes from when the answers decide which page says it (a pain's body family); else [sourceRes].
     */
    val sourceOf: ((SymptomReport) -> Int?)? = null,
    val reads: Set<SymptomStep> = emptySet(),
    val holds: (SymptomReport) -> Boolean,
)

/** Whether [step]'s answer includes any of [options]. */
internal fun SymptomReport.has(step: SymptomStep, vararg options: SymptomOption): Boolean =
    hasAny(step, options.asList())

/** [has] for a set kept apart, such as "a week or longer". */
internal fun SymptomReport.hasAny(step: SymptomStep, options: Collection<SymptomOption>): Boolean =
    answers[step].orEmpty().any { it in options }

/** Whether [step] holds anything other than [SymptomOption.NOT_REALLY], "nothing much". */
private fun SymptomReport.affectsSomething(): Boolean =
    answers[SymptomStep.AFFECTS].orEmpty().any { it != SymptomOption.NOT_REALLY }

/** "About a week ago" or longer: the start NHS "for more than 7 days" and "a few weeks" are read from. */
private val SINCE_A_WEEK = setOf(
    SymptomOption.STARTED_A_WEEK,
    SymptomOption.STARTED_A_MONTH,
    SymptomOption.STARTED_LONGER,
)

/** "2–3 days ago" or longer, for "more than 2 days" and "a few days": counted in, to be cautious. */
private val SINCE_DAYS = SINCE_A_WEEK + SymptomOption.STARTED_2_3_DAYS

/** About a month or longer: "for 3 weeks or more", "for a few weeks". */
private val SINCE_A_MONTH = setOf(SymptomOption.STARTED_A_MONTH, SymptomOption.STARTED_LONGER)

// The symptom topics only: a pain, a mood, a dose or a course is never "told as severe", and its
// own rules are its package's.
private val BODY_TOPICS = Symptom.entries.filter { !it.mind && it.record == ChatRecord.SYMPTOM }.toSet()
private val MIND_TOPICS = Symptom.entries.filter { it.mind && it.record == ChatRecord.SYMPTOM }.toSet()

internal fun rule(
    id: String,
    topic: Symptom?,
    shownAt: SymptomStep,
    alert: SymptomAlert,
    sourceRes: Int? = null,
    reads: Set<SymptomStep> = emptySet(),
    holds: (SymptomReport) -> Boolean,
) = AlertRule(id, setOfNotNull(topic), shownAt, alert, sourceRes, reads = reads, holds = holds)

/** A rule that holds when [shownAt]'s answer includes any of [options]. */
internal fun ticked(
    id: String,
    topic: Symptom?,
    shownAt: SymptomStep,
    alert: SymptomAlert,
    vararg options: SymptomOption,
    sourceRes: Int? = null,
) = rule(id, topic, shownAt, alert, sourceRes) { it.hasAny(shownAt, options.asList()) }

/**
 * The red flags, each from the NHS page its topic follows (nhs.uk, as named on [Symptom]) and in the
 * order a tag lists them. On every page the call-999 list comes first, and so the chat asks those
 * questions first ([chatSteps]).
 *
 * - Vomiting: blood or "coffee grounds"; green vomit, a sudden severe headache or tummy ache,
 *   confusion, a stiff neck with pain looking at bright lights, blue, grey, pale or blotchy skin,
 *   lips or tongue, severe difficulty breathing — 999; not keeping fluids down, or being sick for
 *   more than 2 days — 111 ("Vomiting in adults", "Diarrhoea and vomiting").
 * - Nausea with chest pain, pain that spreads to the arms, back, neck or jaw, or shortness of
 *   breath — 999; nausea that keeps coming back or does not ease in a few days — GP.
 * - Dizziness with chest pain, a racing heart, one-sided weakness or trouble speaking — 999; a
 *   faint, or dizziness that will not go away or keeps coming back — GP.
 * - Any faint — GP; with chest pain, palpitations or during exercise, or a bad injury — 999; with
 *   new breathlessness, an urgent heart assessment (NICE CG109 1.1.4.2).
 * - Palpitations happening now that do not stop, or with chest pain, shortness of breath or
 *   feeling faint — 999; that keep coming back, last longer than a few minutes, or come with a
 *   heart condition — GP.
 * - Chest pain that does not go away, spreads, feels tight or heavy, or comes with sweating,
 *   sickness or breathlessness — 999 ("Chest pain"); one that comes and goes — GP.
 * - Shortness of breath: never left at self-care ([chatUrgency]); severe, with chest pain or
 *   tightness, blue lips or confusion — 999; at rest, severe, with coughed-up blood, one swollen
 *   leg or palpitations — urgent; worse lying down, or a cough for weeks — GP.
 * - Heartburn with pain spreading to the arms, neck or jaw, or with sweating and breathlessness —
 *   999, as a heart attack can feel like indigestion ("Heart attack"); black stools — urgent;
 *   trouble swallowing, being sick, weight loss, or heartburn most days — GP.
 * - Diarrhoea with the call-999 signs its page shares with vomiting — 999; blood, black stools or
 *   dehydration, or diarrhoea past a week — 111.
 * - A fever with a rash that does not fade, a stiff neck, confusion, a very bad headache, very fast
 *   breathing, or blue or blotchy skin — each one 999 by itself, since NHS "Meningitis" warns that
 *   not every sign shows ("Sepsis" for the breathing and the skin); a fever getting worse or not
 *   getting better — urgent.
 * - Tiredness with breathlessness, weight loss or low mood, for weeks, or getting in the way of
 *   daily life — GP.
 * - The signs of a stroke now or in the last 24 hours, someone not responding, very hard
 *   breathing — 999, whatever the topic.
 * - Feelings: low mood past two weeks, struggling to cope, what is tried not helping, worry most
 *   days that gets in the way, months of poor sleep — GP; breathlessness or a faint with them — GP,
 *   since the chat names no cause; thoughts of suicide — help today, or 999 when the person may not
 *   be safe or has hurt themselves ("Help for suicidal thoughts").
 * - Anything told as severe — GP, the "if you're worried" every page ends with.
 */
private val BASE_SYMPTOM_RULES: List<AlertRule> = listOf(
    ticked(
        "VOMIT_BLOOD",
        null,
        SymptomStep.BLOOD,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_vomiting,
    ),
    ticked("SELF_INDUCED", Symptom.VOMITING, SymptomStep.CAUSE, SymptomAlert.SELF_INDUCED, SymptomOption.SELF_INDUCED),
    ticked(
        "VOMIT_999",
        Symptom.VOMITING,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.GREEN_VOMIT,
        SymptomOption.SEVERE_TUMMY_PAIN,
        SymptomOption.SUDDEN_HEADACHE,
        SymptomOption.CONFUSED,
        SymptomOption.STIFF_NECK,
        SymptomOption.BLUE_SKIN,
        SymptomOption.SEVERE_BREATHING,
        sourceRes = R.string.symptom_source_d_and_v,
    ),
    ticked(
        "VOMIT_FLUIDS",
        Symptom.VOMITING,
        SymptomStep.WITH,
        SymptomAlert.URGENT,
        SymptomOption.CANT_KEEP_FLUIDS,
        sourceRes = R.string.symptom_source_d_and_v,
    ),
    rule(
        "VOMIT_2_DAYS",
        Symptom.VOMITING,
        SymptomStep.TIMES,
        SymptomAlert.URGENT,
        R.string.symptom_source_d_and_v,
        reads = setOf(SymptomStep.ONSET),
    ) {
        it.hasAny(SymptomStep.ONSET, SINCE_DAYS) &&
            it.has(SymptomStep.TIMES, SymptomOption.TWICE, SymptomOption.THREE_PLUS)
    },
    ticked(
        "NAUSEA_999",
        Symptom.NAUSEA,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.CHEST_PAIN,
        SymptomOption.PAIN_SPREADING,
        SymptomOption.BREATHLESS,
    ),
    ticked("NAUSEA_RETURNS", Symptom.NAUSEA, SymptomStep.PATTERN, SymptomAlert.DOCTOR, SymptomOption.COMES_AND_GOES),
    rule("NAUSEA_DAYS", Symptom.NAUSEA, SymptomStep.PATTERN, SymptomAlert.DOCTOR, reads = setOf(SymptomStep.ONSET)) {
        it.hasAny(SymptomStep.ONSET, SINCE_DAYS) && it.has(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME)
    },
    ticked(
        "DIZZY_999",
        Symptom.DIZZINESS,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.CHEST_PAIN,
        SymptomOption.RACING_HEART,
        SymptomOption.ONE_SIDE_WEAK,
        SymptomOption.SPEECH,
    ),
    ticked("DIZZY_FAINTED", Symptom.DIZZINESS, SymptomStep.FAINTED, SymptomAlert.DOCTOR, SymptomOption.YES),
    ticked("DIZZY_RETURNS", Symptom.DIZZINESS, SymptomStep.PATTERN, SymptomAlert.DOCTOR, SymptomOption.COMES_AND_GOES),
    rule("DIZZY_STAYS", Symptom.DIZZINESS, SymptomStep.PATTERN, SymptomAlert.DOCTOR, reads = setOf(SymptomStep.ONSET)) {
        it.has(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME) && it.hasAny(SymptomStep.ONSET, SINCE_A_WEEK)
    },
    rule("FAINT_ANY", Symptom.FAINTING, SymptomStep.SYMPTOM, SymptomAlert.DOCTOR) { true },
    ticked("FAINT_INJURED", Symptom.FAINTING, SymptomStep.INJURED, SymptomAlert.EMERGENCY, SymptomOption.YES),
    ticked(
        "FAINT_999",
        Symptom.FAINTING,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.CHEST_PAIN,
        SymptomOption.RACING_HEART,
        SymptomOption.DURING_EXERCISE,
    ),
    ticked("FAINT_BREATHLESS", Symptom.FAINTING, SymptomStep.WITH, SymptomAlert.URGENT, SymptomOption.BREATHLESS),
    ticked(
        "PALP_NOW",
        null,
        SymptomStep.PALP_NOW,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_palpitations,
    ),
    ticked(
        "PALP_999",
        Symptom.PALPITATIONS,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.CHEST_PAIN,
        SymptomOption.BREATHLESS,
        SymptomOption.DIZZY_FAINT,
    ),
    ticked(
        "PALP_RETURNS",
        Symptom.PALPITATIONS,
        SymptomStep.PATTERN,
        SymptomAlert.DOCTOR,
        SymptomOption.COMES_AND_GOES,
        SymptomOption.ALL_THE_TIME,
    ),
    ticked(
        "PALP_LONG",
        Symptom.PALPITATIONS,
        SymptomStep.DURATION,
        SymptomAlert.DOCTOR,
        SymptomOption.LONGER_MINUTES,
        SymptomOption.HOURS,
    ),
    ticked("PALP_HEART", Symptom.PALPITATIONS, SymptomStep.WITH, SymptomAlert.DOCTOR, SymptomOption.HEART_CONDITION),
    ticked(
        "CHEST_NOW",
        null,
        SymptomStep.CHEST_NOW,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_chest_pain,
    ),
    ticked(
        "CHEST_999",
        Symptom.CHEST_PAIN,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.PAIN_SPREADING,
        SymptomOption.TIGHT_HEAVY,
        SymptomOption.SWEATY_SICK,
    ),
    ticked(
        "CHEST_GP",
        null,
        SymptomStep.CHEST_NOW,
        SymptomAlert.DOCTOR,
        SymptomOption.NO,
        sourceRes = R.string.symptom_source_chest_pain,
    ),
    ticked(
        "BREATH_NOW",
        null,
        SymptomStep.BREATH_NOW,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_breathlessness,
    ),
    ticked(
        "BREATH_999",
        Symptom.BREATHLESSNESS,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.CHEST_PAIN,
        SymptomOption.BLUE_LIPS,
    ),
    ticked(
        "BREATH_SEVERE",
        Symptom.BREATHLESSNESS,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.SEVERE_BREATHING,
        SymptomOption.CONFUSED,
        SymptomOption.TIGHT_HEAVY,
        SymptomOption.PAIN_SPREADING,
    ),
    ticked(
        "BREATH_URGENT",
        Symptom.BREATHLESSNESS,
        SymptomStep.WITH,
        SymptomAlert.URGENT,
        SymptomOption.COUGHING_BLOOD,
        SymptomOption.ONE_LEG_SWELLING,
        SymptomOption.RACING_HEART,
    ),
    ticked("BREATH_REST", Symptom.BREATHLESSNESS, SymptomStep.WHEN, SymptomAlert.URGENT, SymptomOption.AT_REST),
    rule("BREATH_SEVERITY", Symptom.BREATHLESSNESS, SymptomStep.SEVERITY, SymptomAlert.URGENT) {
        it.severity == SymptomSeverity.SEVERE
    },
    ticked("BREATH_LYING", Symptom.BREATHLESSNESS, SymptomStep.WHEN, SymptomAlert.DOCTOR, SymptomOption.LYING_NIGHT),
    rule(
        "BREATH_COUGH",
        Symptom.BREATHLESSNESS,
        SymptomStep.WITH,
        SymptomAlert.DOCTOR,
        reads = setOf(SymptomStep.ONSET),
    ) {
        it.has(SymptomStep.WITH, SymptomOption.COUGH) && it.hasAny(SymptomStep.ONSET, SINCE_A_MONTH)
    },
    ticked(
        "HEARTBURN_999",
        Symptom.HEARTBURN,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.PAIN_SPREADING,
        SymptomOption.SWEATY_SICK,
        sourceRes = R.string.symptom_source_heart_attack,
    ),
    ticked("HEARTBURN_BLACK", Symptom.HEARTBURN, SymptomStep.WITH, SymptomAlert.URGENT, SymptomOption.BLACK_STOOLS),
    ticked(
        "HEARTBURN_GP",
        Symptom.HEARTBURN,
        SymptomStep.WITH,
        SymptomAlert.DOCTOR,
        SymptomOption.SWALLOWING,
        SymptomOption.VOMITING,
        SymptomOption.WEIGHT_LOSS,
    ),
    rule(
        "HEARTBURN_DAILY",
        Symptom.HEARTBURN,
        SymptomStep.HOW_OFTEN,
        SymptomAlert.DOCTOR,
        reads = setOf(SymptomStep.PATTERN),
    ) {
        it.has(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME) ||
            it.has(SymptomStep.HOW_OFTEN, SymptomOption.SEVERAL_A_DAY, SymptomOption.DAILY)
    },
    ticked(
        "DIARR_999",
        Symptom.DIARRHOEA,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.SEVERE_TUMMY_PAIN,
        SymptomOption.CONFUSED,
        SymptomOption.STIFF_NECK,
        SymptomOption.BLUE_SKIN,
        SymptomOption.SEVERE_BREATHING,
        sourceRes = R.string.symptom_source_d_and_v,
    ),
    ticked(
        "DIARR_URGENT",
        Symptom.DIARRHOEA,
        SymptomStep.WITH,
        SymptomAlert.URGENT,
        SymptomOption.BLACK_STOOLS,
        SymptomOption.BLOOD,
        SymptomOption.DEHYDRATION,
    ),
    rule("DIARR_7_DAYS", Symptom.DIARRHOEA, SymptomStep.ONSET, SymptomAlert.URGENT, R.string.symptom_source_d_and_v) {
        it.hasAny(SymptomStep.ONSET, SINCE_A_WEEK)
    },
    ticked(
        "FEVER_999",
        Symptom.FEVER,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.NON_BLANCHING_RASH,
        SymptomOption.STIFF_NECK,
        SymptomOption.CONFUSED,
        SymptomOption.SUDDEN_HEADACHE,
        SymptomOption.SEVERE_BREATHING,
        SymptomOption.BLUE_SKIN,
        sourceRes = R.string.symptom_source_fever_999,
    ),
    ticked("FEVER_WORSE", Symptom.FEVER, SymptomStep.TREND, SymptomAlert.URGENT, SymptomOption.GETTING_WORSE),
    rule("FEVER_STAYS", Symptom.FEVER, SymptomStep.TREND, SymptomAlert.URGENT, reads = setOf(SymptomStep.ONSET)) {
        it.has(SymptomStep.TREND, SymptomOption.NO_CHANGE) && it.hasAny(SymptomStep.ONSET, SINCE_DAYS)
    },
    ticked(
        "TIRED_GP",
        Symptom.TIREDNESS,
        SymptomStep.WITH,
        SymptomAlert.DOCTOR,
        SymptomOption.BREATHLESS,
        SymptomOption.WEIGHT_LOSS,
    ),
    rule(
        "TIRED_MOOD",
        Symptom.TIREDNESS,
        SymptomStep.WITH,
        SymptomAlert.DOCTOR,
        reads = setOf(SymptomStep.SINCE, SymptomStep.ONSET),
    ) {
        it.has(SymptomStep.WITH, SymptomOption.LOW_MOOD) &&
            (it.has(SymptomStep.SINCE, SymptomOption.OVER_TWO_WEEKS) || it.hasAny(SymptomStep.ONSET, SINCE_A_MONTH))
    },
    rule("TIRED_WEEKS", Symptom.TIREDNESS, SymptomStep.ONSET, SymptomAlert.DOCTOR) {
        it.hasAny(SymptomStep.ONSET, SINCE_A_MONTH)
    },
    rule("TIRED_LIFE", Symptom.TIREDNESS, SymptomStep.AFFECTS, SymptomAlert.DOCTOR) { it.affectsSomething() },
    ticked(
        "STROKE",
        null,
        SymptomStep.STROKE_SIGNS,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_stroke,
    ),
    ticked(
        "UNRESPONSIVE",
        null,
        SymptomStep.UNRESPONSIVE,
        SymptomAlert.EMERGENCY,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_call_999,
    ),
    ticked(
        "OTHER_999",
        Symptom.OTHER,
        SymptomStep.WITH,
        SymptomAlert.EMERGENCY,
        SymptomOption.SEVERE_BREATHING,
        SymptomOption.ONE_SIDE_WEAK,
        SymptomOption.SPEECH,
        SymptomOption.CONFUSED,
    ),
    AlertRule(
        "MIND_BREATHLESS",
        MIND_TOPICS,
        SymptomStep.WITH,
        SymptomAlert.DOCTOR,
        R.string.symptom_source_breathlessness,
    ) { it.has(SymptomStep.WITH, SymptomOption.BREATHLESS) },
    AlertRule("MIND_FAINT", MIND_TOPICS, SymptomStep.WITH, SymptomAlert.DOCTOR, R.string.symptom_source_fainting) {
        it.has(SymptomStep.WITH, SymptomOption.DIZZY_FAINT)
    },
    ticked(
        "MOOD_2_WEEKS",
        Symptom.LOW_MOOD,
        SymptomStep.HOW_LONG,
        SymptomAlert.DOCTOR_MIND,
        SymptomOption.TWO_WEEKS_PLUS,
        SymptomOption.MONTHS,
    ),
    AlertRule("MIND_COPING", MIND_TOPICS, SymptomStep.COPING, SymptomAlert.DOCTOR_MIND) {
        it.has(SymptomStep.COPING, SymptomOption.STRUGGLING, SymptomOption.NOT_COPING, SymptomOption.TRIED_NOT_HELPING)
    },
    rule(
        "WORRY_MOST",
        Symptom.WORRY,
        SymptomStep.AFFECTS,
        SymptomAlert.DOCTOR_MIND,
        reads = setOf(SymptomStep.MIND_OFTEN),
    ) {
        it.has(SymptomStep.MIND_OFTEN, SymptomOption.MOST_DAYS, SymptomOption.ALL_THE_TIME) && it.affectsSomething()
    },
    ticked("SLEEP_MONTHS", Symptom.SLEEP_WORRY, SymptomStep.HOW_LONG, SymptomAlert.DOCTOR_MIND, SymptomOption.MONTHS),
    rule(
        "CRISIS_SAFE",
        null,
        SymptomStep.CRISIS,
        SymptomAlert.URGENT_MIND,
        R.string.symptom_source_crisis,
        reads = setOf(SymptomStep.CRISIS_NOW),
    ) {
        it.has(SymptomStep.CRISIS, SymptomOption.CRISIS_SAFE) || it.has(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
    },
    rule(
        "CRISIS_UNSAFE",
        null,
        SymptomStep.CRISIS,
        SymptomAlert.EMERGENCY_MIND,
        R.string.symptom_source_crisis,
        reads = setOf(SymptomStep.CRISIS_NOW),
    ) {
        val unsafe = setOf(SymptomOption.CRISIS_UNSAFE, SymptomOption.CRISIS_HARMED)
        it.hasAny(SymptomStep.CRISIS, unsafe) || it.hasAny(SymptomStep.CRISIS_NOW, unsafe)
    },
    rule(
        "CRISIS_SKIP",
        null,
        SymptomStep.CRISIS,
        SymptomAlert.SAFETY_NET,
        R.string.symptom_source_crisis,
        reads = setOf(SymptomStep.CRISIS_NOW),
    ) {
        it.has(SymptomStep.CRISIS, SymptomOption.RATHER_NOT_SAY) ||
            it.has(SymptomStep.CRISIS_NOW, SymptomOption.RATHER_NOT_SAY)
    },
    AlertRule("SEVERE", BODY_TOPICS, SymptomStep.SEVERITY, SymptomAlert.DOCTOR) {
        it.severity == SymptomSeverity.SEVERE
    },
    AlertRule("SEVERE_MIND", MIND_TOPICS, SymptomStep.SEVERITY, SymptomAlert.DOCTOR_MIND) {
        it.severity == SymptomSeverity.SEVERE
    },
)

/**
 * Every rule the chat reads: the symptom topics' and those for every topic above, then each record
 * package's ([PAIN_RULES], [MOOD_RULES], [MEDICINE_RULES]), in that order — the order a tag lists
 * them. Joined on first use rather than as this file is loaded, since the packages' lists are built
 * with this file's [rule] and [ticked].
 */
internal val SYMPTOM_RULES: List<AlertRule> by lazy { BASE_SYMPTOM_RULES + PAIN_RULES + MOOD_RULES + MEDICINE_RULES }

/** The ids of the rules that answer suicide and self-harm: kept out of the chat's hidden sorting of itself. */
internal val CRISIS_RULE_IDS: Set<String> by lazy {
    setOf("CRISIS_SAFE", "CRISIS_UNSAFE", "CRISIS_SKIP") + MEDICINE_CRISIS_RULE_IDS
}

/**
 * The most urgent of [alerts]: by urgency first, then by the order they are listed in — but for the
 * line to get advice now ([SymptomAlert.URGENT_NOW]), which goes before the others of its urgency:
 * "now" is sooner than "today".
 */
internal fun mostUrgent(alerts: Collection<SymptomAlert>): SymptomAlert? =
    alerts.minWithOrNull(compareBy({ it.urgency }, { if (it == SymptomAlert.URGENT_NOW) -1 else it.ordinal }))

/**
 * A medicine's lines that say to get medical advice now without ending the chat: too much of it, its
 * serious side effect — 111 or A&E, never a visit to plan. The words' "more than it says?" is asked in
 * a thread of any topic, so whichever thread one fires in, its close offers no visit.
 */
internal val MEDICINE_NOW_ALERTS = setOf(SymptomAlert.DOSE_TOO_MUCH, SymptomAlert.MED_SERIOUS_SIDE_EFFECT)

/**
 * The lines that say to get medical advice now without ending the chat: a red flag's "now" and a
 * medicine's ([MEDICINE_NOW_ALERTS]). Whichever thread one fires in, its close says not to wait, the
 * router pushes nothing after it, and no record's "added" flash follows it
 * anywhere in the chat; a pain's own "now" still offers the visit its NHS line asks for.
 */
internal val NOW_ALERTS = setOf(SymptomAlert.URGENT_NOW) + MEDICINE_NOW_ALERTS

/** Whether a line that says to get advice now fired in [report]'s thread ([NOW_ALERTS]). */
internal fun nowLineFired(report: SymptomReport): Boolean = firedRules(report).any { it.alert in NOW_ALERTS }

/** Whether a medicine's line to get advice now fired in [report]'s thread ([MEDICINE_NOW_ALERTS]). */
internal fun medicineNowFired(report: SymptomReport): Boolean =
    firedRules(report).any { it.alert in MEDICINE_NOW_ALERTS }

/**
 * Every rule [report]'s answers make true, in table order. A rule of one topic needs that topic;
 * a rule for every topic does not need one at all, so a sign told before any topic is chosen —
 * chest pain in the person's first words, then "yes, now" — is answered at once.
 */
internal fun firedRules(report: SymptomReport): List<AlertRule> = SYMPTOM_RULES.filter { rule ->
    (rule.topics.isEmpty() || report.symptom in rule.topics) && rule.holds(report)
}

/** The rule [id] names, for the source behind a line a chat stored. */
internal fun alertRule(id: String?): AlertRule? = SYMPTOM_RULES.firstOrNull { it.id == id }

/**
 * The least a topic's chat ends on whatever was answered: shortness of breath is always worth a
 * GP — NHS "Shortness of breath": "Do not try to self-diagnose the cause of shortness of breath –
 * always see a GP".
 */
private val TOPIC_FLOOR = mapOf(Symptom.BREATHLESSNESS to SymptomUrgency.GP)

/** How urgent the whole chat is: its most urgent rule, never below its topic's floor. */
internal fun chatUrgency(report: SymptomReport): SymptomUrgency {
    val fired = firedRules(report).map { it.alert.urgency }
    return (fired + listOfNotNull(report.symptom?.let(TOPIC_FLOOR::get))).minOrNull() ?: SymptomUrgency.SELF_CARE
}

/**
 * Whether [report]'s urgency is its topic's floor and not any rule's: no red flag's line said it,
 * so the closing line is the advice by itself and carries the topic's source behind its "?".
 */
internal fun floorSetsUrgency(report: SymptomReport): Boolean {
    val floor = report.symptom?.let(TOPIC_FLOOR::get) ?: return false
    return firedRules(report).none { it.alert.urgency <= floor }
}

/**
 * The lines [answers] call for in an entry saved without a chat, each under the question it is
 * printed beside — the most urgent one where two land on the same question. A rule that reads two
 * questions is printed under the later. Rules for every topic read questions only a chat asks.
 */
fun symptomAlerts(
    symptom: Symptom,
    answers: Map<SymptomStep, List<SymptomOption>>,
    severity: SymptomSeverity? = null,
): Map<SymptomStep, SymptomAlert> =
    firedRules(SymptomReport(symptom, answers, severity))
        .groupBy({ it.shownAt }, { it.alert })
        .mapValues { (_, alerts) -> mostUrgent(alerts) ?: alerts.first() }
