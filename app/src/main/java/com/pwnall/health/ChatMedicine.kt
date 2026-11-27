package com.pwnall.health

import android.content.res.Resources
import androidx.annotation.VisibleForTesting
import java.time.LocalDate
import java.time.ZoneId

// The medicine threads: a dose taken — one of the person's courses, marked as
// the Medicine tab marks it, or one taken outside any course — and a course a doctor prescribed,
// written down at once with its reminders. The safety questions come before anything is recorded:
// "more than the packet says?", then the signs to call for help now. The bot names a medicine as the
// person does, never its class or what it holds, and never says a dose is safe, how much to take,
// when the next one is, or that two medicines are fine together. The DOSE thread's steps are in
// ChatDose.kt, the COURSE thread's in ChatCourse.kt; what both share is here.

/**
 * The clock the medicine threads read where the moment of an answer matters — "just now", whether
 * a dose's time is an hour gone, whether a time chosen has come yet — and which is frozen with that
 * answer, so the chat reads the same when it is drawn again. A seam for the tests.
 */
internal object MedicineClock {
    @Volatile
    @set:VisibleForTesting
    var now: () -> Long = System::currentTimeMillis

    @Volatile
    @set:VisibleForTesting
    var zone: () -> ZoneId = ZoneId::systemDefault
}

/** The answer a typed question takes for "I do not know" or "I would rather not say". */
internal const val MED_UNKNOWN = "-"

/** A dose of a course: its value begins so, with the course's createdAt ("C1790000000000"). */
internal const val MED_COURSE_PREFIX = "C"

/** Not one of the person's courses: another medicine, or a name the words gave ("N:нурофен"). */
internal const val MED_OTHER = "N"

/** Days, for the windows the threads look back over. */
internal const val MED_DAY_MS = 24 * 60 * 60 * 1_000L

/** A note about the same medicines is said again once this many days have gone by. */
private const val NOTE_AGAIN_DAYS = 30L

/**
 * The medicine a thread is about, as it was named. [name] as the person has it ("" when they did not
 * know it); [course] the createdAt of the person's course it is, with [dose], [schedule], [times],
 * [food] and [startDay] as that course stood then; the list's entry ([inn], [groups], [dualUse]); and
 * [days], how long that course had run.
 */
internal data class NamedMedicine(
    val name: String,
    val course: Long? = null,
    val dose: String = "",
    val schedule: String = "",
    val times: String = "",
    val food: String = "",
    val startDay: String = "",
    val inn: String? = null,
    val groups: Set<MedGroup> = emptySet(),
    val days: Long? = null,
    val dualUse: Boolean = false,
) {
    val known: KnownMedicine? get() = inn?.let { id -> KNOWN_MEDICINES.firstOrNull { it.id == id } }
    val tca: Boolean get() = MedGroup.TCA in groups
    val opioid: Boolean get() = MedGroup.OPIOID in groups
    val antidepressant: Boolean get() = known?.antidepressant == true

    /** An antidepressant a doctor prescribes ([KnownMedicine.prescribedAntidepressant]): never St John's wort. */
    val prescribedAntidepressant: Boolean get() = known?.prescribedAntidepressant == true
    val painkiller: Boolean get() = known?.painkiller == true
}

/** [name] as the list knows it, with no course of the person's behind it. */
internal fun namedMedicine(name: String): NamedMedicine {
    val known = knownMedicineOf(name)
    return NamedMedicine(
        name.trim(),
        inn = known?.id,
        groups = known?.groups.orEmpty(),
        dualUse =
        known?.dualUse == true,
    )
}

/** [course] as a thread names it on [day]: the course as it stands, and how long it has run. */
internal fun namedCourse(course: Medication, day: LocalDate): NamedMedicine {
    val known = knownMedicineOf(course.name)
    val start = course.startDay.toLocalDateOrNull()
    return NamedMedicine(
        name = course.name,
        course = course.createdAt,
        dose = course.dose,
        schedule = course.schedule,
        times = course.times,
        food = course.foodRelation,
        startDay = course.startDay,
        inn = known?.id,
        groups = known?.groups.orEmpty(),
        days = start?.let { java.time.temporal.ChronoUnit.DAYS.between(it, day) },
        dualUse = known?.dualUse == true,
    )
}

/** The facts [medicine] is frozen as, under [step]'s name: what the thread reads of it from then on. */
internal fun NamedMedicine.facts(step: SymptomStep): Map<String, String> = buildMap {
    val at = step.name + "."
    put(at + "n", name)
    course?.let { put(at + "c", it.toString()) }
    if (course !=
        null
    ) {
        put(at + "k", listOf(dose, schedule, times, food, startDay).joinToString("|") { it.replace("|", "") })
    }
    inn?.let { put(at + "inn", it) }
    if (groups.isNotEmpty()) put(at + "groups", groups.joinToString(",") { it.name })
    days?.let { put(at + "days", it.toString()) }
    if (dualUse) put(at + "dual", "1")
}

/** The medicine frozen under [step]'s name, or null when nothing was frozen there. */
internal fun frozenMedicine(report: SymptomReport, step: SymptomStep): NamedMedicine? {
    val at = step.name + "."
    val name = report.meta[at + "n"] ?: return null
    val snapshot = report.meta[at + "k"]?.split("|").orEmpty()
    return NamedMedicine(
        name = name,
        course = report.meta[at + "c"]?.toLongOrNull(),
        dose = snapshot.getOrNull(0).orEmpty(),
        schedule = snapshot.getOrNull(1).orEmpty(),
        times = snapshot.getOrNull(2).orEmpty(),
        food = snapshot.getOrNull(3).orEmpty(),
        startDay = snapshot.getOrNull(4).orEmpty(),
        inn = report.meta[at + "inn"],
        groups = report.meta[at + "groups"].orEmpty().split(',').mapNotNull { group ->
            MedGroup.entries.firstOrNull { it.name == group }
        }.toSet(),
        days = report.meta[at + "days"]?.toLongOrNull(),
        dualUse = report.meta[at + "dual"] == "1",
    )
}

/** The steps a thread's medicine is named at, the latest first. */
internal val NAMING_STEPS = listOf(
    SymptomStep.MED_IS_COURSE,
    SymptomStep.MED_NAME,
    SymptomStep.MED_WHICH,
    SymptomStep.COURSE_NAME,
)

/** The medicine a thread names, as the latest of its naming steps froze it. */
internal fun threadMedicine(report: SymptomReport): NamedMedicine? =
    NAMING_STEPS.firstNotNullOfOrNull { step -> frozenMedicine(report, step) }

/** "TCA", "OPIOID", "ANY" or "-": which "now" signs follow "more than it says" for a medicine. */
internal const val SIGNS_TCA = "TCA"
internal const val SIGNS_OPIOID = "OPIOID"
internal const val SIGNS_ANY = "ANY"
internal const val SIGNS_NONE = "-"

/**
 * Which "now" signs follow "more than it says" for [medicines]: a tricyclic's, an opioid's, all of
 * them for both or for a medicine nobody knows; none only when every one named is on
 * [CHECKED_OVERDOSE_111].
 */
internal fun signsFor(medicines: List<NamedMedicine>): String {
    val tca = medicines.any { it.tca }
    val opioid = medicines.any { it.opioid }
    return when {
        tca && opioid -> SIGNS_ANY
        tca -> SIGNS_TCA
        opioid -> SIGNS_OPIOID
        medicines.isNotEmpty() && medicines.all { it.inn in CHECKED_OVERDOSE_111 } -> SIGNS_NONE
        else -> SIGNS_ANY
    }
}

/**
 * The questions "more than it says?": a dose's own ([SymptomStep.MED_MAX]), and the one the person's
 * words or a typed answer bring, asked once and once more after a "no" to it.
 */
internal val MAX_STEPS = listOf(SymptomStep.MED_MAX, SymptomStep.MED_MAX_WORDS, SymptomStep.MED_MAX_AGAIN)

/** The words' own "more than it says?" ([medicineWordSteps]). */
internal val WORD_MAX_STEPS = setOf(SymptomStep.MED_MAX_WORDS, SymptomStep.MED_MAX_AGAIN)

/** Every question words of too much bring, and what follows "more" or "not sure". */
internal val MED_WORD_STEPS = WORD_MAX_STEPS + setOf(SymptomStep.MED_SIGNS, SymptomStep.MED_PURPOSE)

/** Whether any of [steps]' "more than it says?" was answered "more" or "not sure". */
internal fun tooMuchSaid(report: SymptomReport, steps: Collection<SymptomStep> = MAX_STEPS): Boolean =
    steps.any { report.has(it, SymptomOption.MORE_THAN_IT_SAYS, SymptomOption.NOT_SURE) }

/**
 * The signs [report]'s "more than it says?" answers were frozen with: those asked, or the ones to ask
 * for every "more" or "not sure" and for a medicine named after one that did not cover it, together.
 */
internal fun frozenSigns(report: SymptomReport): String = report.meta["MED_SIGNS.v"] ?: combinedSigns(
    NAMING_STEPS.mapNotNull { report.meta["${it.name}.signs"] } +
        MAX_STEPS.filter { tooMuchSaid(report, listOf(it)) }.mapNotNull { report.meta["${it.name}.signs"] },
)

/** The signs that cover all of [signs]: a tricyclic's and an opioid's together are all; none only when none is. */
internal fun combinedSigns(signs: List<String>): String = when {
    signs.isEmpty() || SIGNS_ANY in signs || (SIGNS_TCA in signs && SIGNS_OPIOID in signs) -> SIGNS_ANY
    SIGNS_TCA in signs -> SIGNS_TCA
    SIGNS_OPIOID in signs -> SIGNS_OPIOID
    else -> SIGNS_NONE
}

/**
 * The answers typed in place of a name or of who prescribed it, read for words of too much as the
 * thread's messages are — never an amount, which its own "more than it says?" always follows, nor the
 * dose a prescription says ("3 tablets" is no dose taken).
 */
internal val TYPED_READ_STEPS = setOf(SymptomStep.MED_NAME, SymptomStep.COURSE_NAME, SymptomStep.COURSE_WHO)

/** The words the thread's answers were typed in ([TYPED_READ_STEPS]), one line each. */
internal fun typedWords(report: SymptomReport): String = typedTold(report).joinToString("\n") { it.second }

/** The answers typed among [steps] in [report]'s thread, each with the turn it was given at. */
internal fun typedTold(report: SymptomReport, steps: Set<SymptomStep> = TYPED_READ_STEPS): List<Pair<Int, String>> =
    report.chat?.turns.orEmpty().withIndex().mapNotNull { (at, turn) ->
        val step = (turn as? ChatTurn.Asked)?.step?.takeIf { it in steps } ?: return@mapNotNull null
        report.values[step]?.takeIf { it != MED_UNKNOWN }?.let { at to it }
    }

/**
 * The messages of [report]'s thread that [words] holds, each with its turn: every one — or, where the
 * transcript asks what the earlier messages alone brought, the first of them.
 */
private fun messagesTold(words: String, report: SymptomReport): List<Pair<Int, String>> {
    val wrote = report.chat?.turns.orEmpty().withIndex().mapNotNull { (at, turn) ->
        (turn as? ChatTurn.Wrote)?.let { at to it.text }
    }
    val held = (wrote.size downTo 0).firstOrNull { count ->
        wrote.take(count).joinToString("\n") { it.second } == words
    }
    return wrote.take(held ?: wrote.size)
}

/**
 * Whether [text], told in [report]'s thread, tells of too much of a medicine: in a dose's or a
 * course's thread its medicine is the one meant, and its name counts as the list's names do.
 */
internal fun toldTooMuch(text: String, report: SymptomReport, courses: List<Medication> = emptyList()): Boolean =
    overdoseIn(
        text,
        courses,
        about = report.symptom == Symptom.DOSE || report.symptom == Symptom.COURSE,
        names = listOfNotNull(threadMedicine(report)?.name?.takeIf { it.isNotEmpty() }),
    )

/**
 * The words' own questions due in [report]'s thread for what [told] — each a turn and its words, read
 * together — says of too much: "have you taken more than it says?" once, and once more for words
 * told after a "no" to it; after "more" or "not sure" to either, the signs to call for help now,
 * unless every medicine named is one whose page has none, and whether it was by accident, which no
 * earlier answer — a "no" about suicide included — ever skips. [kept] are the ones asked already that
 * stay asked whatever the words read as now ([medicineWordSteps]).
 */
internal fun wordsQuestions(
    report: SymptomReport,
    told: List<Pair<Int, String>>,
    courses: List<Medication> = emptyList(),
    kept: Set<SymptomStep> = emptySet(),
): List<SymptomStep> {
    // The rules' words of too much, or a reading's flag on one of the messages.
    fun tooMuch(part: List<Pair<Int, String>>) = part.isNotEmpty() &&
        (
            toldTooMuch(part.joinToString("\n") { it.second }, report, courses) ||
                part.any { (at, _) -> report.readRaises(at, SymptomStep.MED_MAX_WORDS) }
            )
    if (SymptomStep.MED_MAX_WORDS !in kept && !tooMuch(told)) return emptyList()
    val asked = report.chat?.turns.orEmpty()
        .indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.MED_MAX_WORDS }
    val again = SymptomStep.MED_MAX_AGAIN in kept ||
        (report.has(SymptomStep.MED_MAX_WORDS, SymptomOption.NO) && tooMuch(told.filter { it.first > asked }))
    val steps = mutableListOf(SymptomStep.MED_MAX_WORDS)
    if (again) steps += SymptomStep.MED_MAX_AGAIN
    if (tooMuchSaid(report, WORD_MAX_STEPS)) {
        if (frozenSigns(report) != SIGNS_NONE) steps += SymptomStep.MED_SIGNS
        steps += SymptomStep.MED_PURPOSE
    }
    return steps
}

/** Whether a tricyclic is the thread's medicine. */
private fun tricyclicThread(report: SymptomReport): Boolean = threadMedicine(report)?.tca == true

/** A tricyclic's harm line: any answer but "no" to the medicine's crisis question, or thoughts of harm ticked. */
private fun tricyclicHarm(report: SymptomReport): Boolean = tricyclicThread(report) &&
    (
        report.answers[SymptomStep.CRISIS].orEmpty().any { it != SymptomOption.NO } ||
            report.has(SymptomStep.MED_SERIOUS, SymptomOption.SERIOUS_HARM)
        )

/**
 * What the "now" signs' rules read besides their own question: the medicine named — the signs were
 * frozen with it ([frozenSigns]) — and every "more than it says?" answered "more" or "not sure".
 */
private val SIGNS_READS: Set<SymptomStep> = NAMING_STEPS.toSet() + MAX_STEPS

/**
 * The medicine threads' rules, read through [SYMPTOM_RULES]. Too much of a medicine, and the
 * questions after it, belong to no topic: they are asked wherever words of too much were written.
 */
internal val MEDICINE_RULES: List<AlertRule> = listOf(
    rule("MED_TOO_MUCH", null, SymptomStep.MED_MAX, SymptomAlert.DOSE_TOO_MUCH, R.string.med_chat_source_too_much) {
        tooMuchSaid(it, listOf(SymptomStep.MED_MAX))
    },
    rule(
        "MED_TOO_MUCH_WORDS",
        null,
        SymptomStep.MED_MAX_WORDS,
        SymptomAlert.DOSE_TOO_MUCH,
        R.string.med_chat_source_too_much,
    ) { tooMuchSaid(it, listOf(SymptomStep.MED_MAX_WORDS)) },
    rule(
        "MED_TOO_MUCH_AGAIN",
        null,
        SymptomStep.MED_MAX_AGAIN,
        SymptomAlert.DOSE_TOO_MUCH,
        R.string.med_chat_source_too_much,
    ) { tooMuchSaid(it, listOf(SymptomStep.MED_MAX_AGAIN)) },
    rule(
        "MED_SIGNS_TCA",
        null,
        SymptomStep.MED_SIGNS,
        SymptomAlert.EMERGENCY,
        R.string.med_chat_source_tca_overdose,
        reads = SIGNS_READS,
    ) { it.has(SymptomStep.MED_SIGNS, SymptomOption.YES) && frozenSigns(it) == SIGNS_TCA },
    rule(
        "MED_SIGNS_OPIOID",
        null,
        SymptomStep.MED_SIGNS,
        SymptomAlert.EMERGENCY,
        R.string.med_chat_source_opioid_overdose,
        reads = SIGNS_READS,
    ) { it.has(SymptomStep.MED_SIGNS, SymptomOption.YES) && frozenSigns(it) == SIGNS_OPIOID },
    rule(
        "MED_SIGNS_ANY",
        null,
        SymptomStep.MED_SIGNS,
        SymptomAlert.EMERGENCY,
        R.string.med_chat_source_overdose_signs,
        reads = SIGNS_READS,
    ) { it.has(SymptomStep.MED_SIGNS, SymptomOption.YES) && frozenSigns(it) !in setOf(SIGNS_TCA, SIGNS_OPIOID) },
    ticked(
        "MED_ON_PURPOSE",
        null,
        SymptomStep.MED_PURPOSE,
        SymptomAlert.EMERGENCY_MIND,
        SymptomOption.ON_PURPOSE,
        sourceRes = R.string.med_chat_source_on_purpose,
    ),
    ticked(
        "MED_PURPOSE_SKIP",
        null,
        SymptomStep.MED_PURPOSE,
        SymptomAlert.SAFETY_NET,
        SymptomOption.RATHER_NOT_SAY,
        sourceRes = R.string.symptom_source_crisis,
    ),
    ticked(
        "MED_FEEL_WORSE",
        Symptom.DOSE,
        SymptomStep.MED_FEEL,
        SymptomAlert.DOCTOR_MIND,
        SymptomOption.FEEL_WORSE,
        sourceRes = R.string.med_chat_source_antidepressants,
    ),
    AlertRule(
        "MED_TCA_HARM",
        setOf(Symptom.DOSE, Symptom.COURSE),
        SymptomStep.CRISIS,
        SymptomAlert.EMERGENCY_MIND,
        R.string.med_chat_source_tca_harm,
        reads = NAMING_STEPS.toSet() + SymptomStep.MED_SERIOUS,
    ) { tricyclicHarm(it) },
    rule(
        "MED_TCA_CRISIS",
        null,
        SymptomStep.CRISIS,
        SymptomAlert.EMERGENCY_MIND,
        R.string.med_chat_source_tca_harm,
        reads = NAMING_STEPS.toSet() + SymptomStep.CRISIS_NOW,
    ) { report ->
        // A tricyclic among the courses of the day, or the very medicine the thread is about.
        val tca = report.meta["CRISIS.tca"] == "1" || report.meta["CRISIS_NOW.tca"] == "1" || tricyclicThread(report)
        tca && (
            report.answers[SymptomStep.CRISIS].orEmpty().any { it != SymptomOption.NO } ||
                report.answers[SymptomStep.CRISIS_NOW].orEmpty().any { it != SymptomOption.NO_SUCH_THOUGHTS }
            )
    },
    ticked(
        "MED_SERIOUS_E",
        Symptom.DOSE,
        SymptomStep.MED_SERIOUS,
        SymptomAlert.EMERGENCY,
        SymptomOption.SERIOUS_THROAT,
        SymptomOption.SERIOUS_COLLAPSE,
        sourceRes = R.string.med_chat_source_serious,
    ),
    ticked(
        "MED_SERIOUS_D",
        Symptom.DOSE,
        SymptomStep.MED_SERIOUS,
        SymptomAlert.MED_SERIOUS_SIDE_EFFECT,
        SymptomOption.SERIOUS_HIGH,
        SymptomOption.SERIOUS_HEART,
        SymptomOption.SERIOUS_BLEEDING,
        sourceRes = R.string.med_chat_source_serious,
    ),
    rule(
        "MED_SIDE_EFFECTS",
        Symptom.DOSE,
        SymptomStep.MED_SERIOUS,
        SymptomAlert.SIDE_EFFECTS,
        R.string.med_chat_source_side_effects,
    ) { it.answers[SymptomStep.MED_SERIOUS] == listOf(SymptomOption.NONE) },
    ticked(
        "MED_STOP_OWN",
        Symptom.COURSE,
        SymptomStep.COURSE_STOP_WHO,
        SymptomAlert.STOP_TALK,
        SymptomOption.OWN_DECISION,
        sourceRes = R.string.med_chat_source_stop,
    ),
)

/** The ids of the medicine rules that answer harm to oneself: kept out of the hidden tag. */
internal val MEDICINE_CRISIS_RULE_IDS: Set<String> =
    setOf("MED_ON_PURPOSE", "MED_PURPOSE_SKIP", "MED_TCA_HARM", "MED_TCA_CRISIS")

/** Whether a line to call emergency services fired in [report]'s thread: nothing more is asked, filed or offered. */
internal fun emergencyFired(report: SymptomReport): Boolean =
    firedRules(report).any { it.alert.urgency == SymptomUrgency.EMERGENCY }

/**
 * The questions words of too much bring before anything else, as words of an emergency do
 * ([wordSteps]): the words' own "more than the packet says?" ([wordsQuestions]), never a dose's own,
 * whose answer about another amount they would take. The words are the messages [words] holds — read
 * in a dose's or a course's thread as about its medicine, and with the person's [courses] among the
 * medicines the list knows — and the names and the prescriber typed there up to the last of them.
 *
 * In a thread of any other topic only words bring these questions, so the ones it [asked] stay the
 * words' own: a course the words named, deleted or renamed since, takes back neither a question nor
 * the row its answer is kept in ([wordAnswers]), and the transcript draws it after the message still.
 */
internal fun medicineWordSteps(
    words: String,
    report: SymptomReport,
    courses: List<Medication>,
    asked: Set<SymptomStep> = emptySet(),
): List<SymptomStep> {
    val messages = messagesTold(words, report)
    val total = report.chat?.turns.orEmpty().count { it is ChatTurn.Wrote }
    // Asked what the earlier messages alone brought, the answers typed after them are not read either.
    val until = report.chat?.turns.orEmpty().withIndex().filter { it.value is ChatTurn.Wrote }
        .getOrNull(messages.size)?.index?.takeIf { messages.size < total } ?: Int.MAX_VALUE
    val kept = asked.takeUnless { report.symptom == Symptom.DOSE || report.symptom == Symptom.COURSE }.orEmpty()
    return wordsQuestions(report, messages + typedTold(report).filter { (at, _) -> at < until }, courses, kept)
}

/** Whether a medicine's question was answered in a way that is about harm to oneself ([crisisAnswered]). */
internal fun medicineCrisisAnswered(report: SymptomReport): Boolean =
    report.has(SymptomStep.MED_PURPOSE, SymptomOption.ON_PURPOSE, SymptomOption.RATHER_NOT_SAY) ||
        ((report.symptom == Symptom.DOSE || report.symptom == Symptom.COURSE) && tricyclicHarm(report))

/**
 * Whether such a question was asked — the medicine's crisis question, or thoughts of harm ticked: the line to call
 * stays pinned.
 */
internal fun medicineCrisisAsked(report: SymptomReport): Boolean {
    val asked = report.chat?.askedSteps().orEmpty()
    return SymptomStep.MED_PURPOSE in asked ||
        (report.symptom == Symptom.DOSE && SymptomStep.CRISIS in asked) ||
        report.has(SymptomStep.MED_SERIOUS, SymptomOption.SERIOUS_HARM)
}

/** What a medicine of this name is, from the built-in list; [MedicineClass.OTHER] for one the list does not know. */
internal fun medicineClassOf(name: String): MedicineClass? {
    val known = knownMedicineOf(name) ?: return MedicineClass.OTHER
    return when {
        known.painkiller -> MedicineClass.PAINKILLER
        known.antidepressant -> MedicineClass.ANTIDEPRESSANT
        else -> MedicineClass.OTHER
    }
}

/**
 * Whether a dose taken for the pain was noted in this chat: a dose thread that filed a dose taken
 * "for pain", or a dose of a painkiller marked here. It marks a pain's painkiller answer, never answers it.
 */
internal fun painkillerFiledInChat(log: ChatLog): Boolean = log.threads.any { thread ->
    val report = thread.report.forSave()
    val filed = thread.filed.any { it.kind == KIND_MED && it.state == LinkState.FILED }
    val painkiller = threadMedicine(report)?.painkiller == true
    filed && (report.has(SymptomStep.MED_WHY, SymptomOption.FOR_PAIN) || painkiller)
}

/**
 * Whether [words] name a painkiller — an entry of the built-in list of that class, or a word for the
 * class: it marks a pain's painkiller answer.
 */
internal fun painkillerIn(words: String): Boolean = painkillerWordsIn(words)

/**
 * The facts about the person's medicines a crisis answer is read with, frozen into the answer's
 * thread when CRISIS or CRISIS_NOW is answered, under that step's name: `{"tca":"1"}` when a course
 * active on [today] is a tricyclic. None known while [today] is null.
 */
internal fun medicineCrisisFacts(env: ChatEnv, today: LocalDate?): Map<String, String> {
    today ?: return emptyMap()
    val tricyclic = env.courses.any { course ->
        course.activeOn(today) && knownMedicineOf(course.name)?.groups?.contains(MedGroup.TCA) == true
    }
    return if (tricyclic) mapOf("tca" to "1") else emptyMap()
}

/** A medicine the words named, as a dose taken ("DOSE:нурофен") or a course prescribed ("COURSE:сертралин"). */
internal data class MedicineWordMention(
    override val at: Int,
    override val mention: TopicMention,
    val medicine: KnownMedicine?,
) : WordMention

/** A medicine's word as a match: its entry, the person's course it is, and whether it stands in a question. */
internal data class MedicineHit(val medicine: KnownMedicine?, val course: Medication?, val question: Boolean)

internal const val MATCH_MED_NAME = "med_name"
internal const val MATCH_MED_OWN = "med_own"
internal const val MATCH_MED_CLASS = "med_class"
internal const val MATCH_MED_CUE = "med_cue"
internal const val MATCH_MED_VERB = "med_verb"

/** The mention detail of a class with no name ("took a painkiller"), and the follow-up's class hint. */
internal const val DETAIL_PAINKILLER = "PAINKILLER"
internal const val DETAIL_ANTIDEPRESSANT = "ANTIDEPRESSANT"

/**
 * The medicines' words: a dose taken, a course prescribed, each medicine named a mention of
 * its own — a dose for words of taking, missing or taking too much of it, a course for words of a
 * prescription or of stopping it, both for words of taking it regularly — and the pharmacist's line
 * beside a question about one ([WordLexicon.extraLines]). A medicine's name is no word of a
 * language ([WordMatch.language]): with nothing else read, the line to call for an emergency stays.
 */
internal object MedicineLexicon : WordLexicon {
    override val priority: Int = WordPriority.MEDICINE

    override fun matches(words: String, raw: String, setting: ChatSetting): List<WordMatch> {
        val index = MessageWords(words)
        val question = '?' in raw || QUESTION_WORDS.any(index)
        val names = MEDICINE_NAMES.all(index).map { hit ->
            WordMatch(
                hit.span,
                MATCH_MED_NAME,
                MedicineHit(hit.value, null, question),
                WordPriority.MEDICINE,
                language = false,
            )
        }
        val own = ownCourseKeys(setting.env.courses).all(index).map { hit ->
            WordMatch(
                hit.span,
                MATCH_MED_OWN,
                MedicineHit(knownMedicineOf(hit.value.name), hit.value, question),
                WordPriority.MEDICINE,
                language = false,
            )
        }
        val classes = MEDICINE_CLASS_WORDS.all(index).map { hit ->
            WordMatch(hit.span, MATCH_MED_CLASS, hit.value, WordPriority.MEDICINE)
        }
        val cues = MEDICINE_CUES.all(index).map { hit ->
            WordMatch(hit.span, MATCH_MED_CUE, null, WordPriority.MEDICINE)
        }
        val verbs = MEDICINE_VERBS.all(index).map { hit ->
            WordMatch(hit.span, MATCH_MED_VERB, hit.value, WordPriority.MEDICINE, verb = hit.value != MedVerb.EXCLUDED)
        }
        return names + own + classes + cues + verbs
    }

    override fun mentions(kept: List<WordMatch>, clauses: List<Clause>, setting: ChatSetting): List<WordMention> =
        medicineMentions(kept, clauses, setting)

    override fun extraLines(kept: List<WordMatch>, setting: ChatSetting): List<BotPiece> =
        if (kept.any { (it.payload as? MedicineHit)?.question == true }) {
            listOf(BotPiece.Note(ChatText(R.string.med_chat_pharmacist)))
        } else {
            emptyList()
        }
}

/** Words that make a message about a medicine a question about one, besides a question mark. */
private val QUESTION_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "можно ли", "нужно ли", "стоит ли", "как принимать", "что принять", "что выпить", "сколько можно",
        "can i", "should i", "is it safe", "is it ok", "how much", "how many", "how do i take",
    ),
)

/** The person's courses as keys of their own: the whole name, and its first word of four letters or more. */
private fun ownCourseKeys(courses: List<Medication>): KeyTable<Medication> = KeyTable(
    courses.flatMap { course ->
        val name = MessageWords(normalisedWords(course.name)).tokens
        val first = name.firstOrNull()?.takeIf { it.length >= OWN_COURSE_WORD_MIN && name.size > 1 }
        listOfNotNull(
            name.takeIf {
                it.isNotEmpty()
            }?.joinToString(" "),
            first,
        ).distinct().map { symptomWordsKey(it) to course }
    },
)

/** How long a course name's first word must be to be read alone. */
private const val OWN_COURSE_WORD_MIN = 4

/**
 * The mentions of one message, from the matches the engine kept: one a medicine named, and with no
 * name a dose of the class or kind the words named.
 */
private fun medicineMentions(kept: List<WordMatch>, clauses: List<Clause>, setting: ChatSetting): List<WordMention> {
    val placed = clauses.flatMap { clause -> clause.kept.map { it to clause.offsets.getOrElse(it.from) { 0 } } }
    val message = MessageWords(" " + clauses.joinToString(" ") { it.words.trim() } + " ")
    val read = MedicineReading(placed, message, setting.env.courses)
    return when {
        read.mine.isEmpty() && kept.none { it.kind.startsWith("med_") } -> emptyList()

        read.names.isEmpty() -> read.unnamed()

        else -> read.names.distinctBy { (match, _) -> nameKey(match) }.flatMap { (match, at) ->
            val hit = match.match.payload as? MedicineHit
            val written = writtenName(match, clauses)
            listOfNotNull(
                MedicineWordMention(at, TopicMention(Symptom.DOSE, written, read.missed), hit?.medicine)
                    .takeIf { read.dose },
                MedicineWordMention(at, TopicMention(Symptom.COURSE, written), hit?.medicine).takeIf { read.course },
            )
        }
    }
}

/** What one message's kept matches say of medicines: the names, classes, cues and verbs, and what they come to. */
private class MedicineReading(placed: List<Pair<PlacedMatch, Int>>, message: MessageWords, courses: List<Medication>) {
    val mine = placed.filter { (match, _) -> match.match.kind.startsWith("med_") }
    val names = mine.filter { (match, _) -> match.match.kind == MATCH_MED_NAME || match.match.kind == MATCH_MED_OWN }
    private val verbs = mine.mapNotNull { (match, _) -> match.match.payload as? MedVerb }.toSet()
    private val classes = mine.mapNotNull { (match, _) -> match.match.payload as? MedicineClass }
    private val cue = mine.any { (match, _) -> match.match.kind == MATCH_MED_CUE }
    private val medicine = names.isNotEmpty() || classes.isNotEmpty() || cue
    private val overdose by lazy { MedicineWords(message, courses).overdose }

    private fun says(verb: MedVerb) = medicine && verb in verbs

    val taken: Boolean get() = says(MedVerb.TAKEN) || says(MedVerb.MISSED) || (medicine && overdose)

    /**
     * A dose the words say was not taken, and nothing of one taken or of too much: «не выпила нурофен»,
     * which its chip then says ([TopicMention.missed]). «Забыла утром, выпила в обед» is a dose taken.
     */
    val missed: Boolean get() = says(MedVerb.MISSED) && !says(MedVerb.TAKEN) && !overdose
    val prescribed: Boolean get() = says(MedVerb.PRESCRIBED) || (says(MedVerb.RECIPE) && (cue || names.isNotEmpty()))
    val dose: Boolean get() = taken || says(MedVerb.REGULAR)
    val course: Boolean get() = prescribed || says(MedVerb.STOPPED) || says(MedVerb.REGULAR)

    /** A dose of the class the words named, or of none, and a course with no name. */
    fun unnamed(): List<WordMention> {
        val at = mine.firstOrNull()?.second ?: 0
        val detail = when {
            MedicineClass.PAINKILLER in classes -> DETAIL_PAINKILLER
            MedicineClass.ANTIDEPRESSANT in classes -> DETAIL_ANTIDEPRESSANT
            else -> ""
        }
        return listOfNotNull(
            MedicineWordMention(at, TopicMention(Symptom.DOSE, detail), null).takeIf { taken },
            MedicineWordMention(at, TopicMention(Symptom.COURSE, ""), null).takeIf { prescribed },
        )
    }
}

/** What tells two named medicines of one message apart: the list's entry, or the person's course. */
private fun nameKey(match: PlacedMatch): String {
    val hit = match.match.payload as? MedicineHit
    return hit?.medicine?.id ?: hit?.course?.createdAt?.toString() ?: match.match.span.toString()
}

/** A named medicine's words as the message has them, lower case: "нурофен плюс". */
private fun writtenName(match: PlacedMatch, clauses: List<Clause>): String {
    val clause = clauses.firstOrNull { match in it.kept } ?: return ""
    return MessageWords(clause.words).tokens.subList(match.from, match.to + 1).joinToString(" ")
}

/** [name] as a chip shows it: its first letter capital. */
internal fun displayName(name: String): String = name.replaceFirstChar { it.titlecase() }

/**
 * A medicine's name as the words gave it ([medicinesIn], a mention's), as the chat shows and saves it:
 * one of the list in its base form ([baseName]) — «Парацетамол», never the «парацетамола» of «выпила 2
 * таблетки парацетамола» — and any other, a course of the person's named in the words, as written with
 * a capital.
 */
internal fun wordsName(written: String): String = baseName(written) ?: displayName(written)

/**
 * A name proposed above the composer, answered with the very words it shows ([displayName]): the
 * dose or the course a tap on it makes is called "Ibuprofen", as the chip said, never the "ibuprofen"
 * of the words it was read from.
 */
internal fun nameChip(name: String): ValueChip = displayName(name).let { shown -> ValueChip(shown, medText(shown)) }

/** A dose taken ([Symptom.DOSE]): ChatDose.kt. */
internal object DoseScript : TopicScript {
    override fun steps(state: ChatState): List<SymptomStep> = doseSteps(state)

    override fun options(state: ChatState, step: SymptomStep): List<SymptomOption> = doseOptions(state, step)

    override fun reply(state: ChatState, step: SymptomStep): ChatReply = doseReply(state, step)

    override fun question(state: ChatState, step: SymptomStep): Int = doseQuestion(state, step)

    override fun questionText(state: ChatState, step: SymptomStep): ChatText? = doseQuestionText(state, step)

    override fun help(state: ChatState, step: SymptomStep): ChatText? = doseHelp(state, step)

    override fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> =
        doseLead(state, last, step)

    override fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece> = doseClose(state, alert)

    override fun savedAfter(state: ChatState, step: SymptomStep): List<String> = doseSavedAfter(state, step)

    override fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState,
    ): String = doseSummary(resources, row, role)

    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> = doseWants(thread, ctx)

    override fun recordSig(thread: ChatThread, role: String): Int = doseSig(thread, role)

    override fun freeze(state: ChatState, step: SymptomStep): Map<String, String> = doseFreeze(state, step)

    override val stepKinds: Map<SymptomStep, StepKind> = DOSE_STEP_KINDS

    /** The questions about too much and a medicine's first weeks, and the naming that decides them. */
    override val owedSteps: Set<SymptomStep> = setOf(
        SymptomStep.MED_MAX,
        SymptomStep.MED_MAX_WORDS,
        SymptomStep.MED_MAX_AGAIN,
        SymptomStep.MED_SIGNS,
        SymptomStep.MED_PURPOSE,
        SymptomStep.MED_FEEL,
        SymptomStep.MED_SERIOUS,
        SymptomStep.MED_WHICH,
        SymptomStep.MED_NAME,
    )

    /**
     * The words' "more than the packet says?" and what follows it, asked wherever words of too much
     * were written; and a dose's own, worded here wherever its thread stands.
     */
    override val globalSteps: Set<SymptomStep> = MED_WORD_STEPS + SymptomStep.MED_MAX

    override fun followUps(state: ChatState): List<TopicChoice> = doseFollowUps(state)

    override fun mentionLabel(mention: TopicMention): ChatText? = mention.detail
        .takeIf { it.isNotEmpty() && it != DETAIL_PAINKILLER && it != DETAIL_ANTIDEPRESSANT }
        ?.let { name ->
            val res = if (mention.missed) R.string.med_chat_mention_missed else R.string.med_chat_mention_dose
            ChatText(res, listOf(PieceArg.Text(wordsName(name))))
        }

    override fun answerText(state: ChatState, step: SymptomStep): ChatText? = doseAnswerText(state, step)

    override fun title(resources: Resources, thread: ChatThread): String =
        threadMedicine(thread.report)?.name?.takeIf { it.isNotEmpty() }?.let(::displayName)
            ?: resources.getString(R.string.symptom_topic_dose)

    override fun serves(thread: ChatThread, mention: TopicMention): Boolean = doseServes(thread, mention)

    override fun tagFacts(thread: ChatThread): TagFacts = TagFacts(alsoTopics = takenFor(thread.report))

    override fun flashText(resources: Resources, link: FiledLink, event: FlashEvent): String? = doseFlash(
        resources,
        link,
        event,
    )

    override fun offered(setting: ChatSetting): Boolean = true

    override val oncePerChat: Boolean = false

    override val flashAt: FlashAt = FlashAt.FILE
}

/** A course a doctor prescribed ([Symptom.COURSE]): ChatCourse.kt. */
internal object CourseScript : TopicScript {
    override fun steps(state: ChatState): List<SymptomStep> = courseSteps(state)

    override fun options(state: ChatState, step: SymptomStep): List<SymptomOption> = courseOptions(state, step)

    override fun reply(state: ChatState, step: SymptomStep): ChatReply = courseReply(state, step)

    override fun question(state: ChatState, step: SymptomStep): Int = courseQuestion(state, step)

    override fun questionText(state: ChatState, step: SymptomStep): ChatText? = courseQuestionText(state, step)

    override fun help(state: ChatState, step: SymptomStep): ChatText? = courseHelp(state, step)

    override fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> =
        courseLead(state, last, step)

    override fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece> = courseClose(state, alert)

    override fun savedAfter(state: ChatState, step: SymptomStep): List<String> = courseSavedAfter(state, step)

    override fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState,
    ): String = courseSummary(resources, course)

    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> = courseWants(thread, ctx)

    override fun recordSig(thread: ChatThread, role: String): Int = courseSig(thread, role)

    override fun freeze(state: ChatState, step: SymptomStep): Map<String, String> = courseFreeze(state, step)

    override val stepKinds: Map<SymptomStep, StepKind> = COURSE_STEP_KINDS

    override val owedSteps: Set<SymptomStep> = setOf(SymptomStep.COURSE_STOP_WHO)

    override fun followUps(state: ChatState): List<TopicChoice> = courseFollowUps(state)

    override fun mentionLabel(mention: TopicMention): ChatText? = mention.detail.takeIf { it.isNotEmpty() }
        ?.let { ChatText(R.string.med_chat_mention_course, listOf(PieceArg.Text(wordsName(it)))) }

    override fun answerText(state: ChatState, step: SymptomStep): ChatText? = courseAnswerText(state, step)

    override fun title(resources: Resources, thread: ChatThread): String =
        threadMedicine(thread.report)?.name?.takeIf { it.isNotEmpty() }?.let(::displayName)
            ?: resources.getString(R.string.symptom_topic_course)

    override fun serves(thread: ChatThread, mention: TopicMention): Boolean = courseServes(thread, mention)

    override fun tagFacts(thread: ChatThread): TagFacts = TagFacts(alsoTopics = prescribedFor(thread.report))

    /**
     * A course's own marks of today are said by the course's flash, never by one of their own; and
     * none after a line to get advice now in the chat.
     */
    override fun flashes(log: ChatLog, thread: ChatThread, link: FiledLink): Boolean =
        !link.role.startsWith(DOSE_ROLE_PREFIX) && !nowLineInChat(log)

    /** Words of a prescription in a chat for an earlier day: a course is added in today's chat. */
    override fun notOfferedLine(setting: ChatSetting): ChatText? =
        ChatText(R.string.med_chat_course_today_only).takeIf { setting.past }

    /**
     * Words of a medicine stopped, in a chat for an earlier day where no course is asked about: for one
     * whose page says not to stop it on one's own — an antidepressant a doctor prescribes, codeine,
     * tramadol — not to stop it suddenly or without the doctor, and for an antidepressant what stopping
     * can bring, as today's chat says them ([courseClose]); a course is added in today's chat otherwise.
     * The words stop the medicine the mention names only where they tell it with stopping ([stoppedIn]):
     * «бросила курить, сертралин помогает» stops no sertraline.
     */
    override fun notOfferedPieces(mention: WordMention, words: String, setting: ChatSetting): List<BotPiece> {
        val medicine = namedMedicine(mention.mention.detail)
        val stop = medicine.prescribedAntidepressant || medicine.opioid
        if (!setting.past || !stop || !stoppedIn(listOf(words), mention.mention.detail, setting.env.courses)) {
            return super.notOfferedPieces(mention, words, setting)
        }
        return listOfNotNull(
            BotPiece.Note(ChatText(R.string.med_chat_alert_stop), sourceRes = R.string.med_chat_source_stop),
            BotPiece.Note(ChatText(R.string.med_chat_withdrawal_net), sourceRes = R.string.med_chat_source_stop)
                .takeIf { medicine.prescribedAntidepressant },
        )
    }

    override fun offered(setting: ChatSetting): Boolean = !setting.past

    override val oncePerChat: Boolean = false

    override val flashAt: FlashAt = FlashAt.FILE
}

/** What a medicine was taken or prescribed for, as the topic whose area the hidden sorting names. */
internal fun forTopics(answer: SymptomOption?, antidepressant: Boolean): Set<Symptom> = setOfNotNull(
    when (answer) {
        SymptomOption.FOR_PAIN -> Symptom.PAIN
        SymptomOption.FOR_TEMPERATURE -> Symptom.FEVER
        SymptomOption.FOR_MOOD -> Symptom.LOW_MOOD
        SymptomOption.FOR_SLEEP -> Symptom.SLEEP_WORRY
        else -> null
    },
    Symptom.LOW_MOOD.takeIf { antidepressant && answer != SymptomOption.FOR_PAIN && answer != SymptomOption.FOR_SLEEP },
)

private fun takenFor(report: SymptomReport): Set<Symptom> =
    forTopics(report.answers[SymptomStep.MED_WHY]?.firstOrNull(), threadMedicine(report)?.antidepressant == true)

private fun prescribedFor(report: SymptomReport): Set<Symptom> =
    forTopics(report.answers[SymptomStep.COURSE_FOR]?.firstOrNull(), threadMedicine(report)?.antidepressant == true)

/** The notes a medicine thread's close said, frozen with its last answer so the close reads the same ([MedNote]). */
internal const val THREAD_NOTES = THREAD_META_PREFIX + "notes"

/**
 * A note at a medicine thread's close: its [kind], the medicines of the list it is about ([key]), and
 * their names as the person has them, which its words say — never what a product contains.
 */
internal data class MedNote(val kind: NoteKind, val key: String, val names: List<String>) {
    /** What tells it from another: said once a chat, and for the same medicines once in thirty days. */
    val code: String get() = "${kind.name}|$key"
}

/**
 * The notes' words, the number of [names] they take and where they come from; [always] a note said
 * with every new course, whatever was said before.
 */
internal enum class NoteKind(val textRes: Int, val sourceRes: Int, val names: Int, val always: Boolean = false) {
    ALCOHOL_AVOID(R.string.med_chat_alcohol_avoid, R.string.med_chat_source_alcohol, 1),
    ALCOHOL_LIMIT(R.string.med_chat_alcohol_limit, R.string.med_chat_source_alcohol, 1),
    ALCOHOL_SLEEPY(R.string.med_chat_alcohol_sleepy, R.string.med_chat_source_alcohol, 1),
    MIX(R.string.med_chat_mix, R.string.med_chat_source_mix, 2),
    MIX_SJW(R.string.med_chat_mix_sjw, R.string.med_chat_source_mix, 1),
    MIX_NSAIDS(R.string.med_chat_mix_nsaids, R.string.med_chat_source_painkiller_mix, 2),
    MIX_PARACETAMOL(R.string.med_chat_mix_paracetamol, R.string.med_chat_source_painkiller_mix, 2),
    MIX_OPIOIDS(R.string.med_chat_mix_opioids, R.string.med_chat_source_painkiller_mix, 2),
    MIX_TCA_OPIOID(R.string.med_chat_mix_tca_opioid, R.string.med_chat_source_tca_opioid, 2),
    MIX_NOT_TOGETHER(R.string.med_chat_mix_not_together, R.string.med_chat_source_tramadol_maoi, 2),
    MIX_ANY_COURSE(R.string.med_chat_mix_any_course, R.string.med_chat_source_mix, 1),
    START_SAFETY(R.string.med_chat_start_safety, R.string.med_chat_source_antidepressants, 1, always = true),
    START_SAFETY_TCA(R.string.med_chat_start_safety_tca, R.string.med_chat_source_tca_harm, 1, always = true),
}

/** [notes] as the thread's frozen fact: "ALCOHOL_AVOID|sertraline|Золофт;…". */
internal fun notesFact(notes: List<MedNote>): Map<String, String> = mapOf(
    THREAD_NOTES to notes.joinToString(";") { note ->
        (listOf(note.kind.name, note.key) + note.names.map { name -> name.filter { it != ';' && it != '|' } })
            .joinToString("|")
    },
)

/** The notes a thread's frozen fact holds. */
internal fun decodeNotes(fact: String): List<MedNote> = fact.split(';').mapNotNull { entry ->
    val parts = entry.split('|')
    // A note of another build, or one whose names do not fit its words, is not drawn at all.
    NoteKind.entries.firstOrNull { it.name == parts.first() && it.names == parts.size - 2 }
        ?.let { MedNote(it, parts[1], parts.drop(2)) }
}

/** A note as the close draws it: a card with its source behind the "?". */
internal fun notePiece(note: MedNote): BotPiece.Note =
    BotPiece.Note(ChatText(note.kind.textRes, note.names.map { PieceArg.Text(it) }), sourceRes = note.kind.sourceRes)

/**
 * [notes] less the ones said already: in this chat's earlier threads, and — about the same medicines —
 * in the chats begun in the last thirty days, unless the note is said with every new course. The chat
 * knows the chats of a medicine's first weeks ([ChatEnv.recentChats]); the older of them do not count,
 * nor does its own stored copy, which may still hold this thread's notes from before a rewind.
 */
internal fun freshNotes(notes: List<MedNote>, state: ChatState): List<MedNote> {
    val here = noteCodes(state.thread.others.map { it.report })
    val since = state.thread.now?.let { it - NOTE_AGAIN_DAYS * MED_DAY_MS } ?: Long.MIN_VALUE
    val recent =
        noteCodes(
            state.thread.env.recentChats.filter { !it.current && it.startedAt >= since }
                .mapNotNull { it.log }
                .flatMap { log -> log.threads.map { it.report } },
        )
    return notes.distinctBy { it.code }.filter { note ->
        note.code !in here &&
            (note.kind.always || note.code !in recent)
    }
}

private fun noteCodes(reports: List<SymptomReport>): Set<String> =
    reports.flatMap { report -> report.meta[THREAD_NOTES]?.let(::decodeNotes).orEmpty() }.mapTo(HashSet()) { it.code }

/** Everything the person wrote in this chat so far: this thread's words and the earlier threads'. */
internal fun chatWords(state: ChatState): String =
    (threadTexts(state) + state.thread.others.map { it.report.chat?.words().orEmpty() }).joinToString("\n")

/**
 * What the guidance for [medicine] says about alcohol, when it says to avoid it, keep it low, or that it makes one
 * sleepier.
 */
internal fun alcoholNote(medicine: NamedMedicine): MedNote? {
    val known = medicine.known ?: return null
    val kind = when (known.alcohol) {
        MedAlcohol.AVOID -> NoteKind.ALCOHOL_AVOID
        MedAlcohol.LIMIT -> NoteKind.ALCOHOL_LIMIT
        MedAlcohol.SLEEPY -> NoteKind.ALCOHOL_SLEEPY
        MedAlcohol.NONE -> return null
    }
    return MedNote(kind, known.id, listOf(displayName(medicine.name)))
}

/**
 * What the guidance says of two different medicines taken together, the most specific first:
 * tramadol with moclobemide, a tricyclic with an opioid, St John's wort with an antidepressant, a
 * triptan with citalopram, a painkiller of the NSAID or opioid kind with an antidepressant, and two
 * NSAIDs, two with paracetamol or two opioids. Null for a pair it says nothing of. Attributed to the
 * guidance, never "X contains Y".
 */
internal fun mixNote(a: NamedMedicine, b: NamedMedicine): MedNote? {
    val one = a.known ?: return null
    val two = b.known ?: return null
    if (one.id == two.id) return null
    val key = listOf(one.id, two.id).sorted().joinToString("+")
    val pair = MedPair(a, b)
    val (kind, named) = pair.special() ?: pair.painkillers() ?: return null
    return MedNote(kind, key, named.map { displayName(it.name) })
}

/** Two medicines read for [mixNote]. */
private class MedPair(val a: NamedMedicine, val b: NamedMedicine) {
    private val both = listOf(a, b)

    private fun find(test: (NamedMedicine) -> Boolean): NamedMedicine? = both.firstOrNull(test)

    private fun other(than: NamedMedicine): NamedMedicine = if (than === a) b else a

    /**
     * The pairs a page names by medicine: tramadol and moclobemide, a tricyclic and an opioid, St John's wort, a
     * triptan and citalopram.
     */
    fun special(): Pair<NoteKind, List<NamedMedicine>>? {
        val tramadol = find { it.inn?.startsWith("tramadol") == true }
        val tca = find { it.tca }
        val sjw = find { MedGroup.ST_JOHNS_WORT in it.groups }
        val triptan = find { MedGroup.TRIPTAN in it.groups }
        return when {
            tramadol != null && MedGroup.MAOI in other(tramadol).groups -> NoteKind.MIX_NOT_TOGETHER to both
            tca != null && other(tca).opioid -> NoteKind.MIX_TCA_OPIOID to listOf(tca, other(tca))
            sjw != null && other(sjw).antidepressant -> sjwNote(other(sjw))
            triptan != null && other(triptan).inn == "citalopram" -> NoteKind.MIX to both
            else -> null
        }
    }

    /** St John's wort beside an antidepressant whose guidance says not to use it, or lists it. */
    private fun sjwNote(antidepressant: NamedMedicine): Pair<NoteKind, List<NamedMedicine>>? =
        when (antidepressant.known?.sjw) {
            SjwRule.DO_NOT_USE -> NoteKind.MIX_SJW to listOf(antidepressant)
            SjwRule.LISTED -> NoteKind.MIX to both
            else -> null
        }

    /**
     * A painkiller of the NSAID or opioid kind with an antidepressant; two NSAIDs, two with paracetamol, two opioids.
     */
    fun painkillers(): Pair<NoteKind, List<NamedMedicine>>? {
        val strong = find { MedGroup.NSAID in it.groups || it.opioid }
        return when {
            strong != null && other(strong).antidepressant -> NoteKind.MIX to both
            MedGroup.NSAID in a.groups && MedGroup.NSAID in b.groups -> NoteKind.MIX_NSAIDS to both
            MedGroup.PARACETAMOL in a.groups && MedGroup.PARACETAMOL in b.groups -> NoteKind.MIX_PARACETAMOL to both
            a.opioid && b.opioid -> NoteKind.MIX_OPIOIDS to both
            else -> null
        }
    }
}
