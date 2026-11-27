package com.pwnall.health

import android.content.res.Resources
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// The COURSE thread: a medicine a doctor prescribed, written down in today's chat with the
// questions the course form asks — its name, what it is for when that decides a safety line, its dose,
// how often and at what times, from when and for how long, and today's doses already gone by. The
// course is made the moment those answers are in, through the form's own builder ([medicationOf]),
// with today's taken doses marked; how it goes with food and who prescribed it rewrite the same
// course after its card, and its reminders' switches are asked last, each only when it is off.

/** The steps whose answer is a value: taken on the tap, or the times ticked, by the clock. */
internal val COURSE_STEP_KINDS: Map<SymptomStep, StepKind> =
    listOf(
        SymptomStep.COURSE_NAME,
        SymptomStep.COURSE_DOSE,
        SymptomStep.COURSE_START,
        SymptomStep.COURSE_LENGTH,
        SymptomStep.COURSE_WHO,
    ).associateWith { StepKind(StepHolds.VALUE, AnswerKind.TAP) } +
        listOf(SymptomStep.COURSE_TIMES, SymptomStep.COURSE_TODAY_SLOTS)
            .associateWith { StepKind(StepHolds.VALUE, AnswerKind.SETTLE) }

/** The reminders' switches as they stood when the course was made: "G1=0;G2=1;G3=1;G4=1". */
internal const val THREAD_GATES = THREAD_META_PREFIX + "gates"

/**
 * The plan the course was made from, as [planHash] of it — frozen when it was made, and gone with any
 * answer taken back with the pencil, as every fact of the thread goes. Frozen while the course stood
 * undone, it carries that link's fingerprint ("<plan>:<sig>"): the plan was given again after Undo,
 * and the course is filed again ([refiling]).
 */
internal const val THREAD_PLAN = THREAD_META_PREFIX + "plan"

/** How long a course runs: days, months, no end; any other value is its last day. */
private const val LENGTH_NONE = "NONE"
private const val LENGTH_DAYS = 'D'
private const val LENGTH_MONTHS = 'M'
private val LENGTHS = listOf("D7", "D14", "D30", "M3", "M6")

/** Today's doses gone by: none taken yet, or the course starts tomorrow instead. */
private const val TODAY_NONE = "NONE"
private const val TODAY_TOMORROW = "TOMORROW"

/** Who prescribed it, from the chips; anything else is the person's own words. */
private const val WHO_GP = "GP"
private const val WHO_PSYCHIATRIST = "PSYCHIATRIST"
private const val WHO_OTHER = "OTHER_DOCTOR"

/** The most names the composer suggests above it. */
private const val COURSE_NAME_SUGGESTIONS = 4

/** How long a note's "who prescribed it" may be. */
private const val WHO_MAX = 100

/** Where the "until another date" picker opens: a month after the start. */
private const val UNTIL_DEFAULT_DAYS = 30L

/**
 * What a course thread reads, once per state: the medicine as named, the course of it the person has
 * already, and the plan its answers make.
 */
private class CourseView(val state: ChatState, val zone: ZoneId) {
    val report: SymptomReport = state.report
    val values: Map<SymptomStep, String> = report.values
    val asked: Set<SymptomStep> = state.chat.askedSteps()
    val env: ChatEnv = state.thread.env
    val today: LocalDate = state.thread.today ?: LocalDate.now(zone)

    fun answer(step: SymptomStep): SymptomOption? = report.answers[step]?.firstOrNull()

    /** The messages the thread reads ([threadMessages]), newest first: read once for all a reply proposes. */
    val messages: List<ChatTurn.Wrote> by lazy { threadMessages(state) }

    /** Their words one after another, as the names, doses and times proposed are read from. */
    val words: String by lazy { messages.joinToString("\n") { it.text } }

    /** What the model's readings of them told ([toldIn]). */
    val told: ReadTold by lazy { toldIn(messages) }

    /** The medicine as frozen with its name; none for "I do not know the name yet". */
    val medicine: NamedMedicine? by lazy {
        frozenMedicine(report, SymptomStep.COURSE_NAME)
            ?: values[SymptomStep.COURSE_NAME]?.takeIf { it != MED_UNKNOWN }?.let { namedMedicine(cleanName(it)) }
    }

    /** Whether the name was not known, or was no name at all. */
    val unnamed: Boolean get() = SymptomStep.COURSE_NAME in asked && medicine?.name.isNullOrEmpty()

    /** The person's course of the same medicine, active or still to start, as frozen with the name. */
    val sameAt: Long? get() = report.meta["COURSE_NAME.same"]?.toLongOrNull()

    val same: Medication? get() = sameAt?.let { at -> env.courses.firstOrNull { it.createdAt == at } }

    /** Whether the answers make a course of their own: no course of it yet, or "a different one". */
    val anew: Boolean get() = sameAt == null || answer(SymptomStep.COURSE_SAME) == SymptomOption.ANOTHER_COURSE

    /**
     * Whether the words this thread reads say the medicine named was stopped — «бросила пить
     * сертралин», and no new prescription — while the person has no course of it: nothing is added,
     * only who said to stop it is asked, and what stopping can bring is said ([stopSteps]).
     */
    val stopOnly: Boolean
        get() = sameAt == null && !unnamed && medicine?.let { stopToldOf(state, it.name, env.courses) } == true

    val wake: Boolean get() = answer(SymptomStep.COURSE_OFTEN) == SymptomOption.ON_WAKING

    val times: List<Int>
        get() = values[SymptomStep.COURSE_TIMES].orEmpty().split(',').mapNotNull {
            it.toIntOrNull()
        }.distinct().sorted()

    /** "Start from tomorrow instead", said of today's doses gone by. */
    val tomorrowInstead: Boolean
        get() = answer(SymptomStep.COURSE_TODAY) == SymptomOption.START_TOMORROW ||
            values[SymptomStep.COURSE_TODAY_SLOTS] == TODAY_TOMORROW

    /** Whether the person said the course began on a day gone by: "start from tomorrow instead" is no answer then. */
    val begunBefore: Boolean
        get() = values[SymptomStep.COURSE_START]?.let { it.toLocalDateOrNull() }
            ?.let { it < today } == true

    /**
     * The day "today's doses" were answered about, frozen with that answer ([courseFreeze]). Food and who
     * prescribed it rewrite the course after its card, and in a chat left open past midnight today is by
     * then another day: "from tomorrow instead" counted from it would move the start and the end a day on.
     */
    val answeredDay: LocalDate
        get() = (report.meta["COURSE_TODAY.day"] ?: report.meta["COURSE_TODAY_SLOTS.day"]).toLocalDateOrNull() ?: today

    val start: LocalDate?
        get() = if (tomorrowInstead) {
            answeredDay.plusDays(1)
        } else {
            values[SymptomStep.COURSE_START]?.let { it.toLocalDateOrNull() }
        }

    /** Its last day, counted from the start: never before it. */
    val end: LocalDate? get() = start?.let { first ->
        endOf(values[SymptomStep.COURSE_LENGTH], first)?.let { maxOf(it, first) }
    }

    /** Today's slots gone by when the length was answered, for a course starting today: "480", "WAKE". */
    val pastSlots: List<String> get() = report.meta["COURSE_LENGTH.past"].orEmpty().split(',').filter {
        it.isNotEmpty()
    }

    /** The ones of them said taken. */
    val takenSlots: List<String>
        get() = when {
            tomorrowInstead -> emptyList()
            answer(SymptomStep.COURSE_TODAY) == SymptomOption.TOOK_TODAY -> pastSlots.take(1)
            else -> values[SymptomStep.COURSE_TODAY_SLOTS].orEmpty().split(',').filter { it in pastSlots }
        }

    /** The reminders' switches as frozen when the course was made; null when they were not known. */
    val gates: ReminderGateState? get() = report.meta[THREAD_GATES]?.let(::decodeGates)
}

/** A length's last day, inclusive: seven days from the 5th end on the 11th; six months from 5 Oct on 4 Apr. */
internal fun endOf(length: String?, start: LocalDate): LocalDate? = when {
    length == null || length == LENGTH_NONE -> null
    length.first() == LENGTH_DAYS -> length.drop(1).toLongOrNull()?.let { start.plusDays(it - 1) }
    length.first() == LENGTH_MONTHS -> length.drop(1).toLongOrNull()?.let { start.plusMonths(it).minusDays(1) }
    else -> length.toLocalDateOrNull()
}

/**
 * The course thread's questions, as far as its answers reach: the name; for one the person has
 * already what changed, and for one stopped who said so; then the plan to its last question, and
 * after the course is made food, who prescribed it, and the reminders' switches.
 */
internal fun courseSteps(state: ChatState): List<SymptomStep> {
    val view = CourseView(state, MedicineClock.zone())
    val list = StepList(view.asked)
    list.ask(SymptomStep.COURSE_NAME)
    typedWordsSteps(state, SymptomStep.COURSE_NAME, list)
    if (view.unnamed) list.stop()
    if (view.sameAt != null) sameSteps(view, list)
    if (view.stopOnly) stopSteps(view, list)
    planSteps(view, list)
    afterCreate(view, list)
    return list.steps
}

/**
 * A medicine the words say was stopped, with no course of it in the person's medicines: who said to
 * stop it, for one whose page says not to stop it on one's own ([stopAsked]); nothing to plan or add.
 */
private fun stopSteps(view: CourseView, list: StepList) {
    if (stopAsked(view)) list.ask(SymptomStep.COURSE_STOP_WHO)
    list.stop()
}

/**
 * Whether the messages [state]'s thread reads say the medicine [name] itself was stopped ([stoppedIn]):
 * a word of stopping told with that name, and no word of taking, being on or prescribing told with it —
 * «бросила курить, пью сертралин» stops nothing, «врач отменил флуоксетин, теперь сертралин» fluoxetine
 * alone, not every medicine the message names.
 */
private fun stopToldOf(state: ChatState, name: String, courses: List<Medication>): Boolean =
    stoppedIn(threadTexts(state), name, courses)

/** The medicine a course thread was taken up for by name — the mention it started from — or null. */
private fun mentionedName(state: ChatState): String? = state.thread.from?.removePrefix(ROUTE_FOLLOW)
    ?.let(::mentionOfKey)?.takeIf { it.topic == Symptom.COURSE }?.detail?.takeIf { it.isNotBlank() }

/** "Already in your medicines: what has changed?": on as a new course only for "a different one". */
private fun sameSteps(view: CourseView, list: StepList) {
    list.ask(SymptomStep.COURSE_SAME)
    val answer = view.answer(SymptomStep.COURSE_SAME)
    if (answer == SymptomOption.COURSE_STOPPED && stopAsked(view)) list.ask(SymptomStep.COURSE_STOP_WHO)
    if (answer != SymptomOption.ANOTHER_COURSE) list.stop()
}

/**
 * Whether who stopped it is asked: an antidepressant a doctor prescribes, codeine or tramadol, whose
 * pages say not to stop on one's own — never St John's wort, whose page says nothing of stopping.
 */
private fun stopAsked(view: CourseView): Boolean =
    view.medicine?.let { it.prescribedAntidepressant || it.opioid } == true

/** The plan, to the answer that makes the course. */
private fun planSteps(view: CourseView, list: StepList) {
    if (view.medicine?.dualUse == true) list.ask(SymptomStep.COURSE_FOR)
    list.ask(SymptomStep.COURSE_DOSE)
    list.ask(SymptomStep.COURSE_OFTEN)
    oftenSteps(view, list)
    list.ask(SymptomStep.COURSE_START)
    list.ask(SymptomStep.COURSE_LENGTH)
    when (view.pastSlots.size) {
        0 -> Unit
        1 -> list.ask(SymptomStep.COURSE_TODAY)
        else -> list.ask(SymptomStep.COURSE_TODAY_SLOTS)
    }
}

/** How often, to a schedule: times, on waking, or a daily course for one taken when needed. */
private fun oftenSteps(view: CourseView, list: StepList) {
    when (view.answer(SymptomStep.COURSE_OFTEN)) {
        SymptomOption.ON_WAKING -> Unit

        SymptomOption.WHEN_NEEDED -> prnSteps(view, list)

        SymptomOption.OTHER_OFTEN -> {
            list.ask(SymptomStep.COURSE_OFTEN_OTHER)
            if (view.answer(SymptomStep.COURSE_OFTEN_OTHER) == SymptomOption.LESS_THAN_DAILY) {
                prnSteps(view, list)
            } else {
                list.ask(SymptomStep.COURSE_TIMES)
            }
        }

        else -> list.ask(SymptomStep.COURSE_TIMES)
    }
}

/** Taken when needed or less than daily: a daily course after all, or each dose noted when it is taken. */
private fun prnSteps(view: CourseView, list: StepList) {
    list.ask(SymptomStep.COURSE_PRN)
    if (view.answer(SymptomStep.COURSE_PRN) == SymptomOption.DOSE_NOW) list.stop()
    list.ask(SymptomStep.COURSE_TIMES)
}

/**
 * After the card: food, who prescribed it, and each switch the reminders need that is off — the rest once reminders are
 * on.
 */
private fun afterCreate(view: CourseView, list: StepList) {
    list.ask(SymptomStep.COURSE_FOOD)
    list.ask(SymptomStep.COURSE_WHO)
    typedWordsSteps(view.state, SymptomStep.COURSE_WHO, list)
    val gates = view.gates ?: return
    if (!gates.remindersOn) {
        list.ask(SymptomStep.COURSE_REMIND_SWITCH)
        if (view.answer(SymptomStep.COURSE_REMIND_SWITCH) != SymptomOption.TURN_ON) list.stop()
    }
    if (!gates.notificationsOn) {
        list.ask(SymptomStep.COURSE_REMIND_NOTIFY)
        if (view.answer(SymptomStep.COURSE_REMIND_NOTIFY) != SymptomOption.ALLOW) list.stop()
    }
    if (!gates.exactAlarms || !gates.fullScreen) list.ask(SymptomStep.COURSE_REMIND_ALARM)
}

/** Whether the course is made: its plan complete, and no line to call emergency services. */
private fun courseReady(state: ChatState): Boolean =
    SymptomStep.COURSE_FOOD in courseSteps(state) && CourseView(state, MedicineClock.zone()).anew &&
        !emergencyFired(state.report)

/** The answer that made the course: the plan's last question. */
private fun createStep(state: ChatState): SymptomStep? {
    val steps = courseSteps(state)
    return steps.getOrNull(steps.indexOf(SymptomStep.COURSE_FOOD) - 1)
}

/** The course the answers make, as the form would: name, dose, schedule, times, food, days, and who prescribed it. */
private fun planOf(view: CourseView, strings: (Int) -> String): CoursePlan? {
    val name = view.medicine?.name?.takeIf { it.isNotEmpty() } ?: return null
    val start = view.start ?: return null
    return CoursePlan(
        name = name,
        dose = view.values[SymptomStep.COURSE_DOSE]?.takeIf { it != MED_UNKNOWN }.orEmpty(),
        schedule = if (view.wake) MedSchedule.WAKE else MedSchedule.CLOCK,
        times = if (view.wake) emptyList() else view.times,
        food = when (view.answer(SymptomStep.COURSE_FOOD)) {
            SymptomOption.FOOD_BEFORE -> MedFood.BEFORE
            SymptomOption.FOOD_WITH -> MedFood.WITH
            SymptomOption.FOOD_AFTER -> MedFood.AFTER
            else -> MedFood.ANY
        },
        start = start,
        end = view.end,
        note = whoOf(view.values[SymptomStep.COURSE_WHO], strings)
            ?.let { String.format(Locale.ROOT, strings(R.string.med_chat_note_prescribed), it) }
            .orEmpty(),
    )
}

/** Who prescribed it, in the language showing: a chip's words, or the person's own; none for "rather not say". */
private fun whoOf(value: String?, strings: (Int) -> String): String? = when (value) {
    null, MED_UNKNOWN -> null
    WHO_GP -> strings(R.string.med_chat_who_gp)
    WHO_PSYCHIATRIST -> strings(R.string.med_chat_who_psychiatrist)
    WHO_OTHER -> strings(R.string.med_chat_who_other)
    else -> value.trim().takeIf { it.isNotEmpty() }
}

/** A thread as the filer hands it in, read as a state. */
private fun courseState(thread: ChatThread, ctx: FilingContext): ChatState = ChatState(
    report = thread.report.asTold(),
    female = false,
    earlier = null,
    pastDay = ctx.past,
    thread = ThreadContext(
        id = thread.id,
        from = thread.from,
        links = thread.filed,
        day = ctx.chatDay,
        today = ctx.clock.today,
        env = ctx.env,
    ),
)

/**
 * What the thread files: the course once its plan is complete, with a mark for each of today's doses
 * said taken — the course first, so each mark is built with its id. While an answer of the plan is
 * being changed what stands is kept; answers that make no course take it back.
 */
internal fun courseWants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
    val state = courseState(thread, ctx)
    val view = CourseView(state, ctx.clock.zone)
    val plan = planOf(view, ctx.strings)
    val unmade = !courseReady(state) || plan == null
    val keep = emergencyFired(view.report) ||
        (
            !view.unnamed && view.anew && view.answer(SymptomStep.COURSE_PRN) != SymptomOption.DOSE_NOW &&
                !view.stopOnly
            )
    // A course undone with today's marks stays so through the answers after it ([courseSig]); Save again
    // brings it back with those marks in the same act ([ChatFiling.saveAgain]), as an answer changed
    // with the pencil does.
    return when {
        unmade && !keep -> emptyMap()
        unmade -> thread.filed.associate { it.role to RecordWant.Keep }
        else -> madeWants(thread, view, checkNotNull(plan), ctx)
    }
}

/** The course its plan makes, and a mark for each of today's doses said taken, the course first. */
private fun madeWants(
    thread: ChatThread,
    view: CourseView,
    plan: CoursePlan,
    ctx: FilingContext,
): Map<String, RecordWant> {
    val wants =
        linkedMapOf<String, RecordWant>(
            "" to RecordWant.File(
                RecordDraft.Course { createdAt ->
                    medicationOf(plan, createdAt)
                },
            ),
        )
    val course = thread.filed.firstOrNull { it.role.isEmpty() }?.at
    // The day the doses were said taken on, not the day of this act: a mark filed again past midnight stays that day's.
    val day = view.answeredDay
    view.takenSlots.forEach { slot ->
        wants[DOSE_ROLE_PREFIX + slot] = RecordWant.File(
            RecordDraft.Journal(JournalDoor.DOSE, null, slot, course) { shell, ids ->
                // The course is written first. One not written — undone, refused — has no marks: none is built.
                val dose = PlannedDose(
                    medicationId = ids("") ?: return@Journal null,
                    name = plan.name.trim().take(MED_NAME_MAX),
                    dose = plan.dose.trim().take(MED_NAME_MAX),
                    food = plan.food,
                    slot = slot,
                    minuteOfDay = slot.toIntOrNull() ?: wakeMinuteOf(ctx.env.dayJournal, day, ctx.clock.zone),
                    onWaking = slot == MED_SLOT_WAKE,
                )
                medTakenEntry(dose, day, shell.occurredAt)
            },
        )
    }
    return wants
}

/**
 * The answers each record is made of: the course's plan, or a mark's slot and day. A course undone
 * keeps the fingerprint it was undone with — food or who prescribed it, answered after its card,
 * never brings it back — until its plan is given again with the pencil ([refiling]); in that act the
 * course and its marks change theirs, so both are filed again, whatever the answers.
 */
internal fun courseSig(thread: ChatThread, role: String): Int {
    val report = thread.report.forSave()
    val refiling = refiling(thread)
    val undone = courseLink(thread)?.takeIf { stillUndone(thread) }
    return when {
        role.startsWith(DOSE_ROLE_PREFIX) -> listOf(
            role,
            report.values[SymptomStep.COURSE_START],
            report.optionNames(SymptomStep.COURSE_TODAY),
            refiling,
        ).hashCode()

        undone != null -> undone.sig

        else -> listOf(planSig(report), refiling).hashCode()
    }
}

/** The fingerprint of every answer the course row is written from, its options by their names ([optionNames]). */
private fun planSig(report: SymptomReport): Int =
    COURSE_PLAN_STEPS.map { report.values[it] to report.optionNames(it) }.hashCode()

/** The thread's link to the course it made. */
private fun courseLink(thread: ChatThread): FiledLink? =
    thread.filed.firstOrNull { it.role.isEmpty() && it.kind == LINK_COURSE }

/**
 * Whether the course stands undone and its plan was given again since, with the pencil: [THREAD_PLAN]
 * was frozen while this very undone link stood, and the plan is still that one.
 */
private fun refiling(thread: ChatThread): Boolean {
    val link = courseLink(thread)?.takeIf { it.state == LinkState.UNDONE } ?: return false
    return thread.report.forSave().meta[THREAD_PLAN] == "${planHash(thread.report.forSave())}:${link.sig}"
}

/** Whether the course stands undone and stays so: nothing of its plan was given again since. */
private fun stillUndone(thread: ChatThread): Boolean =
    courseLink(thread)?.state == LinkState.UNDONE && !refiling(thread)

/**
 * The fingerprint of the answers that decide what the course is: all but food and who prescribed it.
 * It is frozen with the thread ([THREAD_PLAN]) and read in a later run of the app, so its options go
 * by their names ([optionNames]).
 */
internal fun planHash(report: SymptomReport): Int =
    (COURSE_PLAN_STEPS - AFTER_CARD_STEPS).map { report.values[it] to report.optionNames(it) }.hashCode()

/** The steps a course is made from. */
private val COURSE_PLAN_STEPS = listOf(
    SymptomStep.COURSE_NAME,
    SymptomStep.COURSE_SAME,
    SymptomStep.COURSE_FOR,
    SymptomStep.COURSE_DOSE,
    SymptomStep.COURSE_OFTEN,
    SymptomStep.COURSE_OFTEN_OTHER,
    SymptomStep.COURSE_PRN,
    SymptomStep.COURSE_TIMES,
    SymptomStep.COURSE_START,
    SymptomStep.COURSE_LENGTH,
    SymptomStep.COURSE_TODAY,
    SymptomStep.COURSE_TODAY_SLOTS,
    SymptomStep.COURSE_FOOD,
    SymptomStep.COURSE_WHO,
)

/** The steps asked after the course's card, which rewrite the course it is. */
private val AFTER_CARD_STEPS = setOf(SymptomStep.COURSE_FOOD, SymptomStep.COURSE_WHO)

/**
 * The facts frozen with an answer: the medicine and the course of it the person has, or the last day
 * of one that ended; today's doses gone by; with the answer about them the day that "today" was, and
 * whether a dose not yet taken still has its reminder to come; the reminders' switches once the course
 * is made; the notes of its close once it is over.
 */
internal fun courseFreeze(state: ChatState, step: SymptomStep): Map<String, String> {
    val view = CourseView(state, MedicineClock.zone())
    val now = MedicineClock.now()
    val facts = when (step) {
        SymptomStep.COURSE_NAME -> nameFacts(view)

        // A chat goes on past midnight on the day it was begun: the start's "today" is the date it was said on.
        SymptomStep.COURSE_START -> mapOf("COURSE_START.day" to view.today.toString())

        SymptomStep.COURSE_LENGTH -> mapOf("COURSE_LENGTH.past" to pastSlotsNow(view, now).joinToString(","))

        SymptomStep.COURSE_TODAY, SymptomStep.COURSE_TODAY_SLOTS -> mapOf(
            "${step.name}.soon" to soonNow(view, now),
            "${step.name}.day" to view.today.toString(),
        )

        in MED_WORD_STEPS -> doseFreeze(state, step)

        else -> emptyMap()
    }
    return if (state.report.symptom == Symptom.COURSE) facts + courseThreadFacts(state, facts, view.zone) else facts
}

/**
 * The thread's own facts frozen with an answer that [own] are the step's facts of: the reminders'
 * switches and the plan once the course is made, and the notes of its close once its questions are over.
 */
private fun courseThreadFacts(state: ChatState, own: Map<String, String>, zone: ZoneId): Map<String, String> {
    val facts = mutableMapOf<String, String>()
    val after = state.copy(report = state.report.copy(meta = state.report.meta + own))
    val gates = state.thread.env.gates
    val ready = courseReady(after)
    if (THREAD_GATES !in state.report.meta && gates != null && ready) facts[THREAD_GATES] = encodeGates(gates)
    // The plan the course was made from; given again with the pencil while the course stands undone,
    // the undone link's fingerprint goes with it, and that files the course again ([refiling]).
    val undone = state.thread.links.firstOrNull { it.role.isEmpty() && it.state == LinkState.UNDONE }
    if (THREAD_PLAN !in state.report.meta && ready) {
        facts[THREAD_PLAN] = planHash(after.report).toString() + undone?.let { ":${it.sig}" }.orEmpty()
    }
    val closed = state.copy(report = state.report.copy(meta = state.report.meta + own + facts))
    if (THREAD_NOTES !in state.report.meta && nextChatStep(closed) == null && notesShown(closed)) {
        facts += notesFact(courseNotes(CourseView(closed, zone)))
    }
    return facts
}

/**
 * The medicine named, the person's course of it active or to come, or the last day of one that ended
 * — never the course this thread made itself, named again with the pencil.
 */
private fun nameFacts(view: CourseView): Map<String, String> {
    val typed = view.values[SymptomStep.COURSE_NAME]?.takeIf { it != MED_UNKNOWN } ?: return emptyMap()
    val medicine = namedMedicine(cleanName(typed)).takeIf { it.name.isNotEmpty() } ?: return emptyMap()
    val own = view.state.thread.links.filter { it.kind == LINK_COURSE }.mapTo(HashSet()) { it.at }
    val mine = view.env.courses.filter { it.createdAt !in own && sameMedicine(it.name, medicine.name) }
    val current = mine.filter { course ->
        course.endDay?.let { it >= view.today.toString() } ?: true
    }.maxByOrNull { it.createdAt }
    val ended = mine.mapNotNull { it.endDay }.filter { it < view.today.toString() }.maxOrNull()
    return medicine.facts(SymptomStep.COURSE_NAME) + listOfNotNull(
        current?.let { "COURSE_NAME.same" to it.createdAt.toString() },
        ended?.takeIf { current == null }?.let { "COURSE_NAME.ended" to it },
    )
}

/**
 * Today's slots whose time has gone by, for a course that runs today: one starting today, or one the
 * person says began on a day gone by — a course counts from the day it is written down, so today's
 * doses before now count as missed unless they are marked.
 */
private fun pastSlotsNow(view: CourseView, now: Long): List<String> {
    val start = view.values[SymptomStep.COURSE_START]?.let { it.toLocalDateOrNull() }
    val runs = start != null && start <= view.today && view.end?.let { it < view.today } != true
    val minute = minuteOfDay(now, view.zone)
    return when {
        !runs -> emptyList()

        view.wake -> listOf(MED_SLOT_WAKE).takeIf { wakeMinuteOf(view.env.dayJournal, view.today, view.zone) <= minute }
            .orEmpty()

        else -> view.times.filter { it <= minute }.map { it.toString() }
    }
}

/**
 * Whether a dose of today's not taken is still inside its four hours, so its reminder comes as soon as the course is
 * made.
 */
private fun soonNow(view: CourseView, now: Long): String {
    val minute = minuteOfDay(now, view.zone)
    val wake = wakeMinuteOf(view.env.dayJournal, view.today, view.zone)
    val soon = (view.pastSlots - view.takenSlots.toSet()).any { slot ->
        minute - (slot.toIntOrNull() ?: wake) <= MED_GRACE_MINUTES
    }
    return if (soon && !view.tomorrowInstead) "1" else "0"
}

/** The minute of the day [at] stands at in [zone]. */
private fun minuteOfDay(at: Long, zone: ZoneId): Int =
    java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

/** The switches as a fact: "G1=0;G2=1;G3=1;G4=1". */
internal fun encodeGates(gates: ReminderGateState): String = listOf(
    gates.remindersOn,
    gates.notificationsOn,
    gates.exactAlarms,
    gates.fullScreen,
).mapIndexed { index, on -> "G${index + 1}=${if (on) 1 else 0}" }.joinToString(";")

internal fun decodeGates(fact: String): ReminderGateState? {
    val on = fact.split(';').associate { it.substringBefore('=') to (it.substringAfter('=') == "1") }
    if (on.size != GATE_COUNT) return null
    return ReminderGateState(on["G1"] == true, on["G2"] == true, on["G3"] == true, on["G4"] == true)
}

private const val GATE_COUNT = 4

/**
 * What the guidance says of the new course with the person's other courses: a card each, once a chat
 * and for the same pair once in thirty days.
 */
private fun courseNotes(view: CourseView): List<MedNote> {
    val medicine = view.medicine ?: return emptyList()
    if (!courseReady(view.state)) return emptyList()
    val own = view.state.thread.links.filter { it.kind == LINK_COURSE }.map { it.at }.toSet()
    val day = view.start ?: view.today
    val others = view.env.courses.filter { it.createdAt !in own && (it.activeOn(day) || it.activeOn(view.today)) }
    return freshNotes(others.mapNotNull { mixNote(medicine, namedCourse(it, view.today)) }, view.state)
}

/** The line a new course of an antidepressant starts with — for a tricyclic its own, whatever it was for. */
private fun startSafety(view: CourseView): MedNote? {
    val medicine = view.medicine ?: return null
    val name = listOf(displayName(medicine.name))
    val antidepressant = medicine.prescribedAntidepressant &&
        (!medicine.dualUse || view.answer(SymptomStep.COURSE_FOR) == SymptomOption.FOR_MOOD)
    return when {
        medicine.tca -> MedNote(NoteKind.START_SAFETY_TCA, medicine.inn.orEmpty(), name)
        antidepressant -> MedNote(NoteKind.START_SAFETY, medicine.inn.orEmpty(), name)
        else -> null
    }
}

/** The options of each of the course thread's questions. */
internal fun courseOptions(state: ChatState, step: SymptomStep): List<SymptomOption> = when (step) {
    SymptomStep.COURSE_SAME -> listOf(
        SymptomOption.SAME_COURSE,
        SymptomOption.COURSE_CHANGED,
        SymptomOption.COURSE_STOPPED,
        SymptomOption.ANOTHER_COURSE,
    )

    SymptomStep.COURSE_STOP_WHO -> listOf(SymptomOption.DOCTOR_SAID, SymptomOption.OWN_DECISION)

    SymptomStep.COURSE_FOR -> listOf(
        SymptomOption.FOR_MOOD,
        SymptomOption.FOR_PAIN,
        SymptomOption.FOR_SLEEP,
        SymptomOption.SOMETHING_ELSE,
    )

    SymptomStep.COURSE_OFTEN -> listOf(
        SymptomOption.ONCE_A_DAY,
        SymptomOption.TWICE_A_DAY,
        SymptomOption.THREE_A_DAY,
        SymptomOption.ON_WAKING,
        SymptomOption.WHEN_NEEDED,
        SymptomOption.OTHER_OFTEN,
    )

    SymptomStep.COURSE_OFTEN_OTHER -> listOf(SymptomOption.MORE_THAN_THREE, SymptomOption.LESS_THAN_DAILY)

    SymptomStep.COURSE_PRN -> listOf(SymptomOption.AS_DAILY_COURSE, SymptomOption.DOSE_NOW)

    SymptomStep.COURSE_TODAY -> listOfNotNull(
        SymptomOption.TOOK_TODAY,
        SymptomOption.NOT_YET,
        SymptomOption.START_TOMORROW.takeUnless { CourseView(state, MedicineClock.zone()).begunBefore },
    )

    in MED_WORD_STEPS -> doseOptions(state, step)

    SymptomStep.COURSE_FOOD -> listOf(
        SymptomOption.FOOD_ANY,
        SymptomOption.FOOD_BEFORE,
        SymptomOption.FOOD_WITH,
        SymptomOption.FOOD_AFTER,
        SymptomOption.NOT_SURE,
    )

    SymptomStep.COURSE_REMIND_SWITCH -> listOf(SymptomOption.TURN_ON, SymptomOption.LEAVE_OFF)

    SymptomStep.COURSE_REMIND_NOTIFY -> listOf(SymptomOption.ALLOW, SymptomOption.NOT_NOW_MED)

    SymptomStep.COURSE_REMIND_ALARM -> listOf(SymptomOption.DONE, SymptomOption.NOT_NOW_MED)

    else -> chatOptions(null, step, state.female)
}

/** What each question waits for: the composer, chips of values, the times, a switch, the options. */
internal fun courseReply(state: ChatState, step: SymptomStep): ChatReply {
    val view = CourseView(state, MedicineClock.zone())
    return when (step) {
        SymptomStep.COURSE_NAME -> courseNameReply(view)

        SymptomStep.COURSE_DOSE -> courseDoseReply(view)

        SymptomStep.COURSE_WHO -> whoReply(view)

        SymptomStep.COURSE_TIMES -> ChatReply.TimesPick(step, timeProposals(view))

        SymptomStep.COURSE_START -> startReply(view)

        SymptomStep.COURSE_LENGTH -> lengthReply(view)

        SymptomStep.COURSE_TODAY_SLOTS -> todaySlotsReply(view)

        SymptomStep.COURSE_REMIND_SWITCH -> ChatReply.ReminderGates(step, ReminderGate.SWITCH)

        SymptomStep.COURSE_REMIND_NOTIFY -> ChatReply.ReminderGates(step, ReminderGate.NOTIFY)

        SymptomStep.COURSE_REMIND_ALARM -> ChatReply.ReminderGates(step, ReminderGate.ALARM)

        SymptomStep.COURSE_SAME, SymptomStep.COURSE_STOP_WHO -> ChatReply.Choose(
            step,
            courseOptions(state, step),
            openRef = view.same?.let { ChatRecordRef(LINK_COURSE, it.createdAt, it.id) },
        )

        else -> ChatReply.Choose(step, courseOptions(state, step))
    }
}

/**
 * The composer for the name: the names the words gave or a reading of them told, and the started-from
 * mention's — the list's base forms ([wordsName]) — ended courses' as they are named, and "I do not
 * know the name yet".
 */
private fun courseNameReply(view: CourseView): ChatReply.TextAsk {
    val mention = mentionedName(view.state)?.let(::wordsName).orEmpty()
    val ended = view.env.courses.filter { course ->
        course.endDay?.let { it < view.today.toString() } == true
    }.map { it.name }
    val told = view.told.medicineNames()
    val words = (medicinesIn(view.words).map { it.first } + told).map(::wordsName)
    val names = (listOf(mention) + words + ended).filter {
        it.isNotBlank()
    }.distinctBy { normalisedWords(it) }.take(COURSE_NAME_SUGGESTIONS)
    val suggestions = names.map(::nameChip) + ValueChip(MED_UNKNOWN, ChatText(R.string.med_chat_course_name_unknown))
    return ChatReply.TextAsk(SymptomStep.COURSE_NAME, R.string.med_chat_course_name_hint, MED_NAME_MAX, suggestions)
}

/** The composer for the dose, the doses the words named or a reading of them told, and "Not known yet". */
private fun courseDoseReply(view: CourseView): ChatReply.TextAsk {
    val doses = doseTextsIn(view.words) + view.told.amounts
    val said = doses.distinctBy { it.lowercase() }.map { ValueChip(it, medText(it), accent = true) }
    val unknown = ValueChip(MED_UNKNOWN, ChatText(R.string.med_chat_course_dose_unknown))
    return ChatReply.TextAsk(SymptomStep.COURSE_DOSE, R.string.med_dose_label, MED_NAME_MAX, said + unknown)
}

/**
 * The composer for who prescribed it: a GP, a psychiatrist for an antidepressant — one prescribed for
 * pain or sleep as well only when it was said to be for mood or nerves, so the chip never names a
 * class for a medicine prescribed for pain — another doctor, or rather not say.
 */
private fun whoReply(view: CourseView): ChatReply.TextAsk {
    val medicine = view.medicine
    val mood = medicine?.prescribedAntidepressant == true &&
        (!medicine.dualUse || view.answer(SymptomStep.COURSE_FOR) == SymptomOption.FOR_MOOD)
    val chips = listOfNotNull(
        ValueChip(WHO_GP, ChatText(R.string.med_chat_who_gp)),
        ValueChip(WHO_PSYCHIATRIST, ChatText(R.string.med_chat_who_psychiatrist)).takeIf { mood },
        ValueChip(WHO_OTHER, ChatText(R.string.med_chat_who_other)),
        ValueChip(MED_UNKNOWN, ChatText(R.string.symptom_option_rather_not_say)),
    )
    return ChatReply.TextAsk(SymptomStep.COURSE_WHO, R.string.med_chat_who_hint, WHO_MAX, chips)
}

/**
 * The times proposed, unticked: the words' own and a reading's of them, else nine for once a day,
 * eight and eight for twice, and so on. The words are read as a dose's time is ([doseTimingIn]):
 * «в 9 вечера» is 21:00, and a bare "at 9" proposes both halves of the day.
 */
private fun timeProposals(view: CourseView): List<Int> {
    val words = doseTimingIn(view.words, view.today).minutes
    val said = (words + view.told.clocks).distinct().sorted()
    if (said.isNotEmpty()) return said
    return when (view.answer(SymptomStep.COURSE_OFTEN)) {
        SymptomOption.TWICE_A_DAY -> listOf(8 * 60, 20 * 60)

        SymptomOption.THREE_A_DAY -> listOf(8 * 60, 14 * 60, 20 * 60)

        SymptomOption.OTHER_OFTEN -> emptyList<Int>().takeIf { view.answer(SymptomStep.COURSE_PRN) == null }
            ?: listOf(MED_NEW_TIME_MINUTE)

        else -> listOf(MED_NEW_TIME_MINUTE)
    }
}

private fun startReply(view: CourseView): ChatReply.Values = ChatReply.Values(
    SymptomStep.COURSE_START,
    listOf(
        ValueChip(view.today.toString(), ChatText(R.string.med_chat_start_today)),
        ValueChip(view.today.plusDays(1).toString(), ChatText(R.string.med_chat_start_tomorrow)),
        ValueChip(view.today.plusDays(2).toString(), ChatText(R.string.med_chat_start_other), picker = ValuePicker.DAY),
    ),
)

private fun lengthReply(view: CourseView): ChatReply.Values {
    val start = view.start ?: view.today
    val chips = LENGTHS.map { ValueChip(it, ChatText(R.string.med_chat_plain, listOf(lengthArg(it)))) } +
        ValueChip(LENGTH_NONE, ChatText(R.string.med_end_open)) +
        ValueChip(
            start.plusDays(UNTIL_DEFAULT_DAYS).toString(),
            ChatText(R.string.med_chat_length_until),
            picker = ValuePicker.DAY,
        )
    return ChatReply.Values(SymptomStep.COURSE_LENGTH, chips)
}

/** "7 days", "3 months". */
private fun lengthArg(code: String): PieceArg {
    val count = code.drop(1).toIntOrNull() ?: 0
    return PieceArg.Plural(
        if (code.first() ==
            LENGTH_DAYS
        ) {
            R.plurals.med_chat_length_days
        } else {
            R.plurals.med_chat_length_months
        },
        count,
    )
}

/** Ticks for today's doses gone by, "None yet", and for a course starting today "Start from tomorrow instead". */
private fun todaySlotsReply(view: CourseView): ChatReply.Values = ChatReply.Values(
    SymptomStep.COURSE_TODAY_SLOTS,
    view.pastSlots.map { ValueChip(it, slotChip(it)) } + listOfNotNull(
        ValueChip(TODAY_NONE, ChatText(R.string.med_chat_today_none)),
        ValueChip(TODAY_TOMORROW, ChatText(R.string.med_chat_today_tomorrow)).takeUnless { view.begunBefore },
    ),
    multi = true,
    exclusive = setOf(TODAY_NONE, TODAY_TOMORROW),
)

private fun slotChip(slot: String): ChatText =
    slot.toIntOrNull()?.let(::plainClock) ?: ChatText(R.string.med_dose_on_waking)

private fun slotArg(slot: String): PieceArg =
    slot.toIntOrNull()?.let { PieceArg.Clock(it) } ?: PieceArg.Res(R.string.med_dose_on_waking)

private fun slotsArg(slots: List<String>): PieceArg = PieceArg.Joined(slots.map(::slotArg), R.string.list_comma)

/** Each question's words. */
internal fun courseQuestion(state: ChatState, step: SymptomStep): Int = when (step) {
    SymptomStep.COURSE_NAME -> R.string.med_chat_ask_course_name
    SymptomStep.COURSE_DOSE -> R.string.med_chat_ask_course_dose
    SymptomStep.COURSE_WHO -> R.string.med_chat_ask_who
    SymptomStep.COURSE_TODAY -> R.string.med_chat_ask_today_wake
    SymptomStep.COURSE_TODAY_SLOTS -> R.string.med_chat_ask_today_slots
    SymptomStep.COURSE_SAME -> R.string.med_chat_ask_course_same
    SymptomStep.COURSE_REMIND_SWITCH -> R.string.med_chat_ask_remind_switch
    SymptomStep.COURSE_REMIND_NOTIFY -> R.string.med_chat_ask_remind_notify
    SymptomStep.COURSE_REMIND_ALARM -> R.string.med_chat_ask_remind_alarm
    in COURSE_OWN_STEPS -> step.labelRes
    else -> doseQuestion(state, step)
}

/** The steps whose label is their question. */
private val COURSE_OWN_STEPS = setOf(
    SymptomStep.COURSE_STOP_WHO,
    SymptomStep.COURSE_FOR,
    SymptomStep.COURSE_OFTEN,
    SymptomStep.COURSE_OFTEN_OTHER,
    SymptomStep.COURSE_PRN,
    SymptomStep.COURSE_TIMES,
    SymptomStep.COURSE_START,
    SymptomStep.COURSE_LENGTH,
    SymptomStep.COURSE_FOOD,
)

/** The questions that need the medicine's name, its course or a time. */
internal fun courseQuestionText(state: ChatState, step: SymptomStep): ChatText? {
    val view = CourseView(state, MedicineClock.zone())
    return when (step) {
        SymptomStep.COURSE_SAME -> ChatText(
            R.string.med_chat_ask_course_same,
            listOf(PieceArg.Text(displayName(view.medicine?.name.orEmpty())), courseDescription(view.same)),
        )

        SymptomStep.COURSE_TODAY -> view.pastSlots.singleOrNull()?.toIntOrNull()
            ?.let { ChatText(R.string.med_chat_ask_today, listOf(PieceArg.Clock(it))) }

        SymptomStep.COURSE_TODAY_SLOTS -> ChatText(R.string.med_chat_ask_today_slots, listOf(slotsArg(view.pastSlots)))

        else -> doseQuestionText(state, step)
    }
}

/** A course as "already in your medicines" says it: its dose and its times. */
private fun courseDescription(course: Medication?): PieceArg {
    course ?: return PieceArg.Text("")
    val times = if (course.scheduleKind() == MedSchedule.WAKE) {
        listOf(PieceArg.Res(R.string.med_schedule_wake))
    } else {
        course.doseMinutes().map { PieceArg.Clock(it) }
    }
    return PieceArg.Joined(
        listOfNotNull(
            course.dose.takeIf {
                it.isNotEmpty()
            }?.let { PieceArg.Text(it) },
        ) + times,
        R.string.list_comma,
    )
}

/** What stands behind a question's "?": why a medicine taken when needed becomes a daily course, food, the note. */
internal fun courseHelp(state: ChatState, step: SymptomStep): ChatText? = when (step) {
    SymptomStep.COURSE_PRN -> if (state.report.has(SymptomStep.COURSE_OFTEN, SymptomOption.WHEN_NEEDED)) {
        ChatText(R.string.med_chat_help_prn)
    } else {
        ChatText(R.string.med_chat_help_less_daily)
    }

    SymptomStep.COURSE_FOOD -> ChatText(
        R.string.med_chat_food_help,
        MedFood.entries.flatMap { listOf(PieceArg.Res(it.titleRes), PieceArg.Res(it.hintRes)) },
    )

    SymptomStep.COURSE_WHO -> ChatText(R.string.med_chat_help_who)

    else -> doseHelp(state, step)
}

/**
 * What the bot says between an answer and the next question: the start, that the medicine was in
 * the person's list before, and right after the answer that made the course its card, today's doses
 * marked or still to come, when it will remind, and for an antidepressant the line a course of one
 * starts with.
 */
internal fun courseLead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> {
    last ?: return emptyList()
    val view = CourseView(state, MedicineClock.zone())
    val pieces = mutableListOf<BotPiece>()
    if (last.step == SymptomStep.SYMPTOM) {
        // Words of the medicine stopped are no prescription: nothing is added for it ([CourseView.stopOnly]).
        // The medicine is the one the thread was taken up for, or with none any the words name.
        val names = mentionedName(state)?.let(::listOf)
            ?: threadTexts(state).flatMap { text -> medicinesIn(text).map { it.first } }
        val stopped = names.any { stopToldOf(state, it, view.env.courses) }
        pieces += startLine(state, if (stopped) R.string.med_chat_now_the_medicine else R.string.med_chat_start_course)
    }
    val endedShown = last.step == SymptomStep.COURSE_NAME && !view.stopOnly
    view.report.meta["COURSE_NAME.ended"]?.takeIf { endedShown }?.let { ended ->
        pieces += BotPiece.Note(
            ChatText(
                R.string.med_chat_ended_before,
                listOf(PieceArg.Text(displayName(view.medicine?.name.orEmpty())), PieceArg.Day(ended)),
            ),
        )
    }
    if (courseReady(state) && createStep(state) == last.step) pieces += createdPieces(view)
    if (step == SymptomStep.CRISIS) pieces += BotPiece.Line(ChatLine.CRISIS_LEAD)
    if (wordsCheckDue(state, step)) pieces += BotPiece.Line(ChatLine.WORDS_CHECK)
    return pieces
}

/** The course's card and what goes with it the moment it is made. */
private fun createdPieces(view: CourseView): List<BotPiece> {
    val taken = view.takenSlots
    val waiting = view.pastSlots - taken.toSet()
    val gates = view.gates
    val reminds = gates?.remindersOn == true && gates.notificationsOn
    val soon = view.report.meta["COURSE_TODAY.soon"] ?: view.report.meta["COURSE_TODAY_SLOTS.soon"]
    val all = if (view.wake) listOf(MED_SLOT_WAKE) else view.times.map { it.toString() }
    return listOfNotNull(
        BotPiece.Saved(view.state.thread.id, ""),
        taken.takeIf { it.isNotEmpty() }?.let { slots ->
            val line = if (slots.size == 1) {
                R.string.med_chat_course_today_marked
            } else {
                R.string.med_chat_course_today_marked_several
            }
            BotPiece.Note(ChatText(line, listOf(slotsArg(slots))))
        },
        waiting.takeIf { it.isNotEmpty() && !view.tomorrowInstead }?.let { slots ->
            val line = if (slots.size == 1) R.string.med_chat_missed_today else R.string.med_chat_missed_today_several
            if (soon == "1" && reminds) {
                BotPiece.Note(ChatText(R.string.med_chat_reminder_soon))
            } else {
                BotPiece.Note(ChatText(line, listOf(slotsArg(slots))))
            }
        },
        when {
            !reminds -> null
            view.wake -> BotPiece.Note(ChatText(R.string.med_chat_course_reminds_wake))
            else -> BotPiece.Note(ChatText(R.string.med_chat_course_reminds, listOf(slotsArg(all))))
        },
        startSafety(view)?.let(::notePiece),
    )
}

/** The card stands after the answer that made the course. */
internal fun courseSavedAfter(state: ChatState, step: SymptomStep): List<String> =
    listOf("").takeIf { courseReady(state) && createStep(state) == step }.orEmpty()

/**
 * The close: after a line to call emergency services what to do and nothing else; a medicine with no
 * name yet; nothing changed, a change made on its page, a course ended on its page with what
 * stopping can bring; reminders left off or a switch still off; the notes about medicines that may
 * not mix.
 */
internal fun courseClose(state: ChatState, alert: List<BotPiece>): List<BotPiece> {
    val report = state.report
    if (emergencyFired(report)) {
        return alert +
            closeLines(SymptomUrgency.EMERGENCY, crisisAnswered(report), Symptom.COURSE).map { BotPiece.Line(it) }
    }
    val view = CourseView(state, MedicineClock.zone())
    return alert + outcome(view) + gateLines(view) + closeTail(state) { courseNotes(view) }
}

/** What the answers came to when no course was made. */
private fun outcome(view: CourseView): List<BotPiece> {
    val stop = view.answer(SymptomStep.COURSE_SAME) == SymptomOption.COURSE_STOPPED
    val withdrawal =
        stop && view.medicine?.prescribedAntidepressant == true && SymptomStep.COURSE_STOP_WHO in view.asked
    return when {
        view.unnamed -> listOf(BotPiece.Note(ChatText(R.string.med_chat_course_needs_name)))

        // Stopped, with no course of it: nothing is noted; for an antidepressant what stopping can bring.
        view.stopOnly -> listOf(
            if (view.medicine?.prescribedAntidepressant == true && SymptomStep.COURSE_STOP_WHO in view.asked) {
                BotPiece.Note(ChatText(R.string.med_chat_withdrawal_net), sourceRes = R.string.med_chat_source_stop)
            } else {
                BotPiece.Note(ChatText(R.string.med_chat_nothing_new))
            },
        )

        view.answer(
            SymptomStep.COURSE_SAME,
        ) == SymptomOption.SAME_COURSE -> listOf(BotPiece.Note(ChatText(R.string.med_chat_course_nothing_changed)))

        view.answer(
            SymptomStep.COURSE_SAME,
        ) == SymptomOption.COURSE_CHANGED -> listOf(BotPiece.Note(ChatText(R.string.med_chat_change_on_page)))

        stop -> listOfNotNull(
            BotPiece.Note(ChatText(R.string.med_chat_end_on_page)),
            BotPiece.Note(
                ChatText(R.string.med_chat_withdrawal_net),
                sourceRes = R.string.med_chat_source_stop,
            ).takeIf {
                withdrawal
            },
        )

        else -> emptyList()
    }
}

/** Reminders left off, or a switch the person was taken to and is still off as the chat stands now. */
private fun gateLines(view: CourseView): List<BotPiece> {
    val live = view.env.gates
    val notifyOff = view.answer(SymptomStep.COURSE_REMIND_NOTIFY) == SymptomOption.NOT_NOW_MED ||
        (SymptomStep.COURSE_REMIND_NOTIFY in view.asked && live?.notificationsOn == false)
    val exactOff = view.answer(SymptomStep.COURSE_REMIND_ALARM) == SymptomOption.NOT_NOW_MED ||
        (SymptomStep.COURSE_REMIND_ALARM in view.asked && live?.exactAlarms == false)
    return listOfNotNull(
        ChatText(R.string.med_chat_remind_left_off).takeIf {
            view.answer(SymptomStep.COURSE_REMIND_SWITCH) ==
                SymptomOption.LEAVE_OFF
        },
        ChatText(R.string.med_chat_notify_still_off).takeIf { notifyOff },
        ChatText(R.string.med_chat_exact_still_off).takeIf { exactOff && !notifyOff },
    ).map { BotPiece.Note(it) }
}

/** A medicine taken only when needed, to be noted dose by dose: "Record a dose now". */
internal fun courseFollowUps(state: ChatState): List<TopicChoice> {
    if (!notesShown(state) || !state.report.has(SymptomStep.COURSE_PRN, SymptomOption.DOSE_NOW)) return emptyList()
    val name = CourseView(state, MedicineClock.zone()).medicine?.name?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return listOf(
        TopicChoice(
            Symptom.DOSE,
            TopicMention(Symptom.DOSE, normalisedWords(name).trim()),
            R.string.med_chat_offer_dose_now,
        ),
    )
}

/** The person's answer to a value step in words. */
internal fun courseAnswerText(state: ChatState, step: SymptomStep): ChatText? {
    val view = CourseView(state, MedicineClock.zone())
    val value = view.values[step] ?: return null
    return when (step) {
        SymptomStep.COURSE_NAME -> if (value ==
            MED_UNKNOWN
        ) {
            ChatText(R.string.med_chat_course_name_unknown)
        } else {
            medText(displayName(value))
        }

        SymptomStep.COURSE_DOSE -> if (value ==
            MED_UNKNOWN
        ) {
            ChatText(R.string.med_chat_course_dose_unknown)
        } else {
            medText(value)
        }

        SymptomStep.COURSE_TIMES -> ChatText(
            R.string.med_chat_plain,
            listOf(
                slotsArg(
                    view.times.map {
                        it.toString()
                    },
                ),
            ),
        )

        SymptomStep.COURSE_START -> startText(view, value)

        SymptomStep.COURSE_LENGTH -> lengthText(view, value)

        SymptomStep.COURSE_TODAY_SLOTS -> todayText(value)

        SymptomStep.COURSE_WHO -> whoText(value)

        else -> doseAnswerText(state, step)
    }
}

/** The start as it was said: "today" and "tomorrow" of the date it was answered on, frozen with it ([courseFreeze]). */
private fun startText(view: CourseView, value: String): ChatText {
    val day = view.report.meta["COURSE_START.day"].toLocalDateOrNull() ?: view.today
    return when (value) {
        day.toString() -> ChatText(R.string.med_chat_start_today)
        day.plusDays(1).toString() -> ChatText(R.string.med_chat_start_tomorrow)
        else -> ChatText(R.string.med_chat_start_that_day, listOf(PieceArg.Day(value)))
    }
}

private fun lengthText(view: CourseView, value: String): ChatText {
    val end = view.end?.toString()
    return when {
        value == LENGTH_NONE -> ChatText(R.string.med_end_open)

        value in LENGTHS && end != null -> ChatText(
            R.string.med_chat_length_answer,
            listOf(lengthArg(value), PieceArg.Day(end)),
        )

        value in LENGTHS -> ChatText(R.string.med_chat_plain, listOf(lengthArg(value)))

        else -> ChatText(R.string.med_chat_plain, listOf(PieceArg.Day(end ?: value)))
    }
}

private fun todayText(value: String): ChatText = when (value) {
    TODAY_NONE -> ChatText(R.string.med_chat_today_none)
    TODAY_TOMORROW -> ChatText(R.string.med_chat_today_tomorrow)
    else -> ChatText(R.string.med_chat_plain, listOf(slotsArg(value.split(',').filter { it.isNotEmpty() })))
}

private fun whoText(value: String): ChatText = when (value) {
    WHO_GP -> ChatText(R.string.med_chat_who_gp)
    WHO_PSYCHIATRIST -> ChatText(R.string.med_chat_who_psychiatrist)
    WHO_OTHER -> ChatText(R.string.med_chat_who_other)
    MED_UNKNOWN -> ChatText(R.string.symptom_option_rather_not_say)
    else -> medText(value)
}

/** The card's line, from the course as it is now: "Sertraline · 50 mg · 09:00 · With food · 5 Oct – 4 Apr 2027". */
internal fun courseSummary(resources: Resources, course: Medication?): String {
    course ?: return ""
    val locale = resources.configuration.locales[0]
    val times = if (course.scheduleKind() ==
        MedSchedule.WAKE
    ) {
        resources.getString(R.string.med_schedule_wake)
    } else {
        course.timesText(resources.getString(R.string.list_comma))
    }
    val food = course.foodRelation().takeIf { it != MedFood.ANY }?.let { resources.getString(it.titleRes) }
    val start = course.startDay.toLocalDateOrNull()
    val end = course.endDay?.let { it.toLocalDateOrNull() }
    val days = when {
        start == null -> null

        end == null -> resources.getString(R.string.med_chat_course_from, painDayText("dMMM", locale, start))

        else -> resources.getString(
            R.string.med_chat_course_range,
            painDayText("dMMM", locale, start),
            painDayText("dMMMy", locale, end),
        )
    }
    return listOfNotNull(
        course.name,
        course.dose.takeIf {
            it.isNotEmpty()
        },
        times.takeIf { it.isNotEmpty() },
        food,
        days,
    )
        .joinToString(" · ")
}

/** Whether [thread] already takes up [mention]: a course of the same medicine, or one with no name named. */
internal fun courseServes(thread: ChatThread, mention: TopicMention): Boolean {
    if (mention.topic != Symptom.COURSE) return false
    val medicine = threadMedicine(thread.report.forSave()) ?: return mention.detail.isEmpty()
    return mention.detail.isEmpty() || sameMedicine(mention.detail, medicine.name)
}
