package com.pwnall.health

import android.content.res.Resources
import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// The DOSE thread: which medicine — one of the person's courses, picked, or a name typed —
// which of the day's doses, or one taken outside the schedule; for that one when, how much and,
// before anything is recorded, whether it was more than the packet says; then what it was for. One
// KIND_MED row: the Medicine tab's own mark of a dose ([medTakenEntry]), or a dose of no course
// ([oneOffDoseEntry]). Everything here is pure over the state; the moment of an answer comes from
// [MedicineClock] once, as it is frozen.

/** The steps whose answer is a value, each taken on the tap. */
internal val DOSE_STEP_KINDS: Map<SymptomStep, StepKind> = listOf(
    SymptomStep.MED_WHICH,
    SymptomStep.MED_NAME,
    SymptomStep.MED_IS_COURSE,
    SymptomStep.MED_SLOT,
    SymptomStep.MED_WHEN,
    SymptomStep.MED_WHEN_AGAIN,
    SymptomStep.MED_AMOUNT,
).associateWith { StepKind(StepHolds.VALUE, AnswerKind.TAP) }

/** A dose's slot of yesterday, still inside its grace after midnight: "Y1320", "YWAKE". */
private const val YESTERDAY = "Y"

/** The slot values that are no dose of the schedule: one outside it, a dose missed, nothing to add. */
private const val SLOT_EXTRA = "X"
private const val SLOT_MISSED = "MISSED"
private const val SLOT_NOTHING = "NOTHING"

/**
 * When it was taken: just now, at its time, or not remembered (a chat of an earlier day) — or, for a dose
 * of no course whose words say it was missed, not at all ([missedChip]).
 */
private const val WHEN_NOW = "NOW"
private const val WHEN_SLOT = "SLOT"
private const val WHEN_UNKNOWN = "UNKNOWN"
private const val WHEN_MISSED = "MISSED"

/**
 * The amounts the chips give: one or two tablets, one or two of the course's doses. Never what a
 * person types — "1" typed is the person's own words, kept as written and asked "more than it says?".
 */
internal const val AMOUNT_ONE = "A1"
internal const val AMOUNT_TWO = "A2"
internal const val AMOUNT_DOSE_ONE = "D1"
internal const val AMOUNT_DOSE_TWO = "D2"

/** The chips' amounts: anything else in the answer was typed. */
private val AMOUNT_CHIPS = setOf(AMOUNT_ONE, AMOUNT_TWO, AMOUNT_DOSE_ONE, AMOUNT_DOSE_TWO)

/**
 * How long before a dose's time the pick's chip may carry it, answering which medicine and which dose
 * in one tap: the hour this thread already reads a dose's time as gone by ([LATE_MS]). Later doses of
 * the day are chosen on "which of today's doses", beside "an extra one".
 */
private const val ONE_TAP_EARLY_MINUTES = 60

/** How long ago a dose of the same medicine makes "is this another dose?" worth asking: half an hour. */
private const val SAME_AGAIN_MS = 30 * 60 * 1_000L

/** How long a dose's time may be gone before "when did you take it?" is asked: an hour. */
private const val LATE_MS = 60 * 60 * 1_000L

private const val MINUTES_A_DAY = 24 * 60
private const val MIDDAY_MINUTE = 12 * 60
private const val FIVE_MINUTES = 5

/** The most names the composer suggests above it. */
private const val NAME_SUGGESTIONS = 4

/** Past this many courses the pick shows the first [PICK_FIRST] and "All my medicines". */
internal const val PICK_ALL_AT = 8
internal const val PICK_FIRST = 7

/** The record's roles: its own, the one a medicine named anew takes ([doseRole]), the pain it changed. */
internal const val DOSE_ROLE = ""
internal const val DOSE_ROLE_RENAMED = "named"
internal const val DOSE_PAIN_ROLE = "pain"

/** What a dose thread is, once its medicine is named. */
private enum class DosePath { UNDECIDED, SCHEDULED, ONE_OFF, MISSED, NOTHING }

/**
 * What a dose thread reads, worked out once per state: the day its doses are of, the courses that
 * can be picked, its medicine as named, and what its answers decide.
 */
private class DoseView(val state: ChatState, val zone: ZoneId) {
    val report: SymptomReport = state.report
    val values: Map<SymptomStep, String> = report.values
    val asked: Set<SymptomStep> = state.chat.askedSteps()
    val env: ChatEnv = state.thread.env
    val past: Boolean = state.pastDay
    val today: LocalDate = state.thread.today ?: LocalDate.now(zone)

    /**
     * The day the doses are of: the chat's own for an earlier day, else the real date — as it stood when
     * the dose of the schedule was chosen ([chosenDay]). A thread carried on past midnight, or a record
     * filed again then, marks the dose that was asked about: under the new date it would mark the coming
     * day's dose taken, and that one's reminder would never come.
     */
    val day: LocalDate = if (past) state.thread.day ?: today else chosenDay(report, zone) ?: today

    /**
     * The courses a dose can be of: active that day, and written down by then — and, past midnight, one
     * that ended yesterday while a dose of its last day is still inside its grace, as its notification
     * still offers it ([yesterdayInGrace]).
     */
    val pickable: List<Medication> by lazy {
        env.courses.filter { pickableOn(it, day, zone) || (!past && endedInGrace(this, it)) }
    }

    /** The messages the thread reads ([threadMessages]), newest first: read once for all a reply proposes. */
    val messages: List<ChatTurn.Wrote> by lazy { threadMessages(state) }

    /** Their words one after another: what orders and proposes the chips. */
    val words: String by lazy { messages.joinToString("\n") { it.text } }

    /** What the model's readings of them told ([toldIn]). */
    val told: ReadTold by lazy { toldIn(messages) }

    /** Explicit date and clock words require confirmation, including both halves of a twelve-hour time. */
    val timing: DoseTiming by lazy { doseTimingIn(words, day) }

    /**
     * Whether the words name a time, or a day other than the doses' own. "Today" names the day every
     * question here already takes for granted: no reason to ask when, nor to take "just now" away.
     */
    val timeSaid: Boolean get() = timing.minutes.isNotEmpty() || (timing.day ?: day) != day

    val asksWhen: Boolean get() = late || timeSaid || told.clocks.isNotEmpty()

    /**
     * The journal of the chat's day, the two days before it and the one after ([ChatEnv.dayJournal]) —
     * all of the 24 hours round a dose of that day or of the day before — less this thread's own records.
     */
    val journal: List<JournalEntry> by lazy {
        val own = state.thread.links.mapTo(HashSet()) { it.at }
        env.dayJournal.filter { it.occurredAt !in own }
    }

    /** The links of the dose itself, under either of its roles. */
    val mains: List<FiledLink> = state.thread.links.filter { it.role == DOSE_ROLE || it.role == DOSE_ROLE_RENAMED }

    fun answer(step: SymptomStep): SymptomOption? = report.answers[step]?.firstOrNull()

    /** The step the medicine was named at, once it is: the pick, a typed name, which of the matches. */
    val namingStep: SymptomStep? by lazy {
        when {
            SymptomStep.MED_IS_COURSE in asked -> SymptomStep.MED_IS_COURSE
            SymptomStep.MED_NAME in asked -> SymptomStep.MED_NAME.takeIf { nameMatches.isEmpty() }
            SymptomStep.MED_WHICH in asked && values[SymptomStep.MED_WHICH] != MED_OTHER -> SymptomStep.MED_WHICH
            else -> null
        }
    }

    /** The medicine, as frozen when it was named — or, a fact missing, as the answers name it now. */
    val medicine: NamedMedicine? by lazy {
        namingStep?.let { step -> frozenMedicine(report, step) ?: namedNow(this, step) }
    }

    /** The person's courses the typed name matched, by createdAt. */
    val nameMatches: List<Long> by lazy {
        report.meta["MED_NAME.match"]?.split(',')?.mapNotNull { it.toLongOrNull() }
            ?: values[SymptomStep.MED_NAME]?.let { typed -> matchingCourses(this, typed).map { it.createdAt } }
            ?: emptyList()
    }

    /**
     * The course the dose is of, as it stands now; null when it is no course, or is gone. One that ended
     * yesterday is it while its last dose can be picked, and for that dose once it was.
     */
    val course: Medication? by lazy {
        val yesterdays = slot?.startsWith(YESTERDAY) == true
        fun active(candidate: Medication): Boolean = candidate.activeOn(day) || candidate in pickable ||
            (yesterdays && candidate.activeOn(day.minusDays(1)))
        medicine?.course?.let { at -> env.courses.firstOrNull { it.createdAt == at && active(it) } }
    }

    /**
     * Whether the medicine named is a course the person no longer has, or no longer takes that day —
     * read only until the dose is filed: one marked stays a dose of its course.
     */
    val courseGone: Boolean get() = medicine?.course != null && course == null && mains.isEmpty()

    /** The slot answered — by the pick's one-tap chip, or by "which of today's doses" — if any. */
    val slot: String? by lazy {
        values[SymptomStep.MED_WHICH]?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
            ?: values[SymptomStep.MED_SLOT]
    }

    /** Whether the pick's own chip carried the dose, so "which of today's doses" is not asked. */
    val slotChosen: Boolean get() = values[SymptomStep.MED_WHICH]?.contains('@') == true

    val path: DosePath by lazy {
        when {
            medicine == null -> DosePath.UNDECIDED
            medicine?.course == null || courseGone -> DosePath.ONE_OFF
            slot == null -> DosePath.UNDECIDED
            slot == SLOT_MISSED -> DosePath.MISSED
            slot == SLOT_NOTHING -> DosePath.NOTHING
            slot == SLOT_EXTRA -> DosePath.ONE_OFF
            else -> DosePath.SCHEDULED
        }
    }

    /** An extra dose of one of the person's courses ("An extra one"). */
    val extra: Boolean get() = slot == SLOT_EXTRA && medicine?.course != null && !courseGone

    /** "I missed a dose", said where a dose of no course was asked when it was taken: nothing is recorded. */
    val notTaken: Boolean get() = values[SymptomStep.MED_WHEN] == WHEN_MISSED

    /** Whether the dose's time was an hour gone, or the chat is for an earlier day: when it was taken is asked. */
    val late: Boolean by lazy {
        val frozen = report.meta["MED_SLOT.late"] ?: report.meta["MED_WHICH.late"]
        frozen?.let { it == "1" } ?: lateNow(this, MedicineClock.now())
    }

    fun future(step: SymptomStep): Boolean = report.meta["${step.name}.future"] == "1"

    /** The moment of a dose of the same medicine half an hour before, if one stood then. */
    val sameAgainAt: Long?
        get() = (report.meta["MED_SLOT.same"] ?: namingStep?.let { report.meta["${it.name}.same"] })?.toLongOrNull()

    /**
     * The doses of the same medicine in the 24 hours round this one ([journal]), as frozen with the amount.
     */
    val earlier: List<Pair<Long, String>>
        get() = report.meta["MED_AMOUNT.earlier"].orEmpty().split(';').mapNotNull { row ->
            row.substringBefore('|').toLongOrNull()?.let { it to row.substringAfter('|', "") }
        }

    /** The "now" signs an earlier "more than it says?" left unasked for this medicine, frozen when it was named. */
    val carried: String? get() = namingStep?.let { report.meta["${it.name}.signs"] }
}

/** The date a dose of the schedule was chosen on: that of the moment frozen with the pick's chip or the slot. */
private fun chosenDay(report: SymptomReport, zone: ZoneId): LocalDate? =
    (report.meta["MED_SLOT.at"] ?: report.meta["MED_WHICH.at"])?.toLongOrNull()
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

/** Whether [course] can be picked for [day]: active then, and written down by then. */
private fun pickableOn(course: Medication, day: LocalDate, zone: ZoneId): Boolean =
    course.activeOn(day) && Instant.ofEpochMilli(course.createdAt).atZone(zone).toLocalDate() <= day

/** Whether [course] ended the day before the doses' day with a dose of that day still inside its grace. */
private fun endedInGrace(view: DoseView, course: Medication): Boolean =
    !course.activeOn(view.day) && yesterdayInGrace(view, course, MedicineClock.now()).isNotEmpty()

/** The person's courses a typed name is: the same name, or the same entry of the list. */
private fun matchingCourses(view: DoseView, typed: String): List<Medication> {
    if (typed == MED_UNKNOWN) return emptyList()
    val name = cleanName(typed)
    return view.pickable.filter { sameMedicine(it.name, name) }
}

/** The person's course a typed name is that starts after the chat's day: the dose is noted on its own. */
private fun futureCourse(view: DoseView, typed: String): Medication? {
    if (typed == MED_UNKNOWN) return null
    val name = cleanName(typed)
    return view.env.courses.firstOrNull { course ->
        course.startDay.toLocalDateOrNull()?.let { it > view.day } == true &&
            sameMedicine(course.name, name)
    }
}

/**
 * A typed name with no words of harm or of too much in it: the name as typed, or — words of too much
 * typed in its place — the medicine those words name, if any, in the person's own letters. A dose's
 * title is never a sentence.
 */
internal fun cleanName(typed: String): String {
    val text = typed.trim()
    if (!crisisIn(text) && !overdoseIn(text)) return text
    return firstMedicineAsTyped(text).orEmpty()
}

/** The medicine the answer to [step] names, from the courses as they are now. */
private fun namedNow(view: DoseView, step: SymptomStep): NamedMedicine? = when (step) {
    SymptomStep.MED_WHICH -> pickedMedicine(view, view.values[SymptomStep.MED_WHICH])

    SymptomStep.MED_NAME -> view.values[SymptomStep.MED_NAME]?.let(::typedMedicine)

    SymptomStep.MED_IS_COURSE -> when (val value = view.values[SymptomStep.MED_IS_COURSE]) {
        null -> null
        MED_OTHER -> view.values[SymptomStep.MED_NAME]?.let(::typedMedicine)
        else -> pickedMedicine(view, value)
    }

    else -> null
}

/** A name typed, as the list knows it; none known for "I do not know its name". */
private fun typedMedicine(typed: String): NamedMedicine =
    if (typed == MED_UNKNOWN) NamedMedicine("") else namedMedicine(cleanName(typed))

/** The medicine a pick's value names: "C<createdAt>[@slot]" a course, "N:<name>" a name the words gave. */
private fun pickedMedicine(view: DoseView, value: String?): NamedMedicine? = when {
    value == null -> null

    value.startsWith(MED_COURSE_PREFIX) -> value.removePrefix(MED_COURSE_PREFIX).substringBefore('@').toLongOrNull()
        ?.let { at -> view.env.courses.firstOrNull { it.createdAt == at } }
        ?.let { namedCourse(it, view.day) }

    value.startsWith("$MED_OTHER:") -> namedMedicine(value.removePrefix("$MED_OTHER:"))

    else -> null
}

/** The confirmed clock's date, yesterday for one of yesterday's slots, or the thread's day. */
private fun doseDay(view: DoseView): LocalDate {
    val step = if (SymptomStep.MED_WHEN_AGAIN in view.asked) SymptomStep.MED_WHEN_AGAIN else SymptomStep.MED_WHEN
    val explicit = doseClockDay(view.values[step]?.takeIf { step in view.asked })
    return explicit ?: if (view.slot?.startsWith(YESTERDAY) == true) view.day.minusDays(1) else view.day
}

/**
 * The day the dose's slot is of, which its mark is filed under: yesterday for one of yesterday's
 * slots, whatever date its clock was confirmed on — the dose of yesterday's 23:00 taken twenty past
 * midnight is yesterday's still, and filed under today it would mark tonight's dose taken.
 */
private fun slotDay(view: DoseView): LocalDate =
    if (view.slot?.startsWith(YESTERDAY) == true) view.day.minusDays(1) else doseDay(view)

/**
 * The date a time said with none stands on. For one of yesterday's slots it is the one that puts the
 * time nearer the slot: 00:20 for a dose of 23:00 is twenty past midnight today, 22:30 yesterday
 * evening — a day back, the first would stand a day before it was taken and before the slot itself.
 */
private fun clockDay(view: DoseView, minute: Int): LocalDate {
    if (view.slot?.startsWith(YESTERDAY) != true) return doseDay(view)
    val slot = slotMinute(view) ?: return doseDay(view)
    return if (minute < slot - MINUTES_A_DAY / 2) view.day else view.day.minusDays(1)
}

/** The minute of the day the dose's slot stands at: its own, or for a dose on waking the night's end. */
private fun slotMinute(view: DoseView): Int? {
    val slot = view.slot?.removePrefix(YESTERDAY) ?: return null
    if (slot == MED_SLOT_WAKE) return wakeMinuteOf(view.env.dayJournal, slotDay(view), view.zone)
    return slot.toIntOrNull()
}

/** [minute] of [day] as a moment in [zone]. */
private fun momentOf(day: LocalDate, minute: Int, zone: ZoneId): Long =
    doseClockMoment(day, minute, zone)

/** The minute of the day [at] stands at in [zone]. */
private fun minuteOf(at: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

/** Whether the dose's time is an hour or more before [now], or the chat is for an earlier day. */
private fun lateNow(view: DoseView, now: Long): Boolean {
    if (view.past) return true
    val minute = slotMinute(view) ?: return false
    return now - momentOf(slotDay(view), minute, view.zone) >= LATE_MS
}

/**
 * A thread's steps in order, as far as its answers reach: a step is listed while every one before it
 * is answered, and once one is not — or the answers end the thread ([stop]) — none after it is.
 */
internal class StepList(private val asked: Set<SymptomStep>) {
    val steps = mutableListOf<SymptomStep>()

    /** Whether every step listed so far is answered, so the next one is listed. */
    var open: Boolean = true
        private set

    /** [step] listed while the list is open: true when it is answered too. */
    fun ask(step: SymptomStep): Boolean {
        if (open) {
            steps += step
            open = step in asked
        }
        return open
    }

    /** No step after this: the answers so far end the thread. */
    fun stop() {
        open = false
    }
}

/**
 * The dose thread's questions, in order, as far as its answers reach: the medicine; for one of the
 * person's courses which dose, and when when its time is an hour gone, then for a medicine in its
 * first weeks how it is going; for a dose outside the schedule when, how much, "more than it says?"
 * and what follows it, and what it was for.
 */
internal fun doseSteps(state: ChatState): List<SymptomStep> {
    val view = DoseView(state, MedicineClock.zone())
    val list = StepList(view.asked)
    earlierWordsSteps(view, list)
    namingSteps(view, list)
    when (view.path) {
        DosePath.SCHEDULED -> scheduledSteps(view, list)
        DosePath.ONE_OFF -> oneOffSteps(view, list)
        else -> Unit
    }
    return list.steps
}

/**
 * The medicine — and right after a name typed with words of too much in it, the words' own questions —
 * the "now" signs it was left without, and for one of the person's courses which dose.
 */
private fun namingSteps(view: DoseView, list: StepList) {
    val pick = SymptomStep.MED_WHICH in view.asked ||
        (view.pickable.isNotEmpty() && SymptomStep.MED_NAME !in view.asked)
    if (pick) list.ask(SymptomStep.MED_WHICH)
    if (!pick || view.values[SymptomStep.MED_WHICH] == MED_OTHER) {
        list.ask(SymptomStep.MED_NAME)
        typedWordsSteps(view.state, SymptomStep.MED_NAME, list)
        if (view.nameMatches.isNotEmpty()) list.ask(SymptomStep.MED_IS_COURSE)
    }
    if (view.carried != null) list.ask(SymptomStep.MED_SIGNS)
    if (view.medicine?.course != null && !view.courseGone && !view.slotChosen) list.ask(SymptomStep.MED_SLOT)
}

/**
 * The words' own questions, first of all, for what an earlier thread's messages told of too much that
 * no question there asked about — read here with the person's own courses among the medicines it
 * knows, as the questions words bring in a thread cannot be: "I took 5 propranolol" names a course the
 * list does not know. Never twice: where this thread's own words bring them, they are asked as theirs.
 */
private fun earlierWordsSteps(view: DoseView, list: StepList) {
    if (SymptomStep.MED_MAX_WORDS in wordSteps(view.state.chat, view.report, view.env.courses)) return
    // A thread that asked it asked about every message before it too.
    val untold = view.state.thread.others.fold(emptyList<String>()) { told, other ->
        val chat = other.report.chat
        if (chat?.askedSteps().orEmpty().any { it in WORD_MAX_STEPS }) {
            emptyList()
        } else {
            told + listOfNotNull(chat?.words()?.takeIf { overdoseIn(it, view.env.courses) })
        }
    }
    if (untold.isEmpty()) return
    wordsQuestions(view.report, untold.map { NO_TURN to it }, view.env.courses).forEach(list::ask)
}

/** The turn of words told in another thread: before every turn of this one. */
private const val NO_TURN = -1

/**
 * The words' own questions for words of too much typed as the answer to [step] — a name, who
 * prescribed it — in a thread with no message: the core reads typed answers with the messages, and a
 * thread with none only here, the person's own courses among the names it knows.
 */
internal fun typedWordsSteps(state: ChatState, step: SymptomStep, list: StepList) {
    if (state.chat.words().isNotBlank()) return
    wordsQuestions(state.report, typedTold(state.report, setOf(step)), state.thread.env.courses).forEach(list::ask)
}

private fun scheduledSteps(view: DoseView, list: StepList) {
    if (view.asksWhen) whenSteps(view, list)
    if (feelDue(view)) list.ask(SymptomStep.MED_FEEL)
    when (view.answer(SymptomStep.MED_FEEL)) {
        SymptomOption.FEEL_WORSE -> crisisStep(view, list)

        SymptomOption.SIDE_EFFECTS -> {
            list.ask(SymptomStep.MED_SERIOUS)
            if (view.report.has(SymptomStep.MED_SERIOUS, SymptomOption.SERIOUS_HARM)) crisisStep(view, list)
        }

        else -> Unit
    }
}

/** "When did you take it?", and once more after a time that has not come yet. */
private fun whenSteps(view: DoseView, list: StepList) {
    list.ask(SymptomStep.MED_WHEN)
    if (view.future(SymptomStep.MED_WHEN)) list.ask(SymptomStep.MED_WHEN_AGAIN)
}

/** The crisis question in the medicine's words — unless words of suicide already asked whether the person is safe. */
private fun crisisStep(view: DoseView, list: StepList) {
    if (SymptomStep.CRISIS_NOW !in view.asked) list.ask(SymptomStep.CRISIS)
}

private fun oneOffSteps(view: DoseView, list: StepList) {
    if (view.sameAgainAt != null) {
        list.ask(SymptomStep.MED_SAME_AGAIN)
        if (view.answer(SymptomStep.MED_SAME_AGAIN) == SymptomOption.SAME_DOSE) list.stop()
    }
    whenSteps(view, list)
    // It was not taken after all: no amount, no reason, no record.
    if (view.notTaken) list.stop()
    list.ask(SymptomStep.MED_AMOUNT)
    if (maxDue(view)) maxSteps(view, list)
    list.ask(SymptomStep.MED_WHY)
    if (view.report.meta["MED_WHY.pain"] != null) list.ask(SymptomStep.MED_PAIN_LINK)
}

/**
 * "More than it says?", and after "more" or "not sure" the signs to call for help now and whether it
 * was by accident.
 */
private fun maxSteps(view: DoseView, list: StepList) {
    list.ask(SymptomStep.MED_MAX)
    if (tooMuchSaid(view.report, listOf(SymptomStep.MED_MAX))) {
        if (frozenSigns(view.report) != SIGNS_NONE) list.ask(SymptomStep.MED_SIGNS)
        if (!purposeAnswered(view)) list.ask(SymptomStep.MED_PURPOSE)
    }
}

/**
 * Whether "more than it says?" is asked of a dose outside the schedule: more than one unit, an amount
 * typed, an extra dose of a course, or another dose of the same medicine in the last 24 hours — unless
 * the words' own question was asked already of this very medicine and the amount is a chip's. An
 * amount the person typed is always asked about: it can say anything.
 */
private fun maxDue(view: DoseView): Boolean {
    val amount = view.values[SymptomStep.MED_AMOUNT]
    val typed = amount != null && amount !in AMOUNT_CHIPS
    val due = view.extra || view.earlier.isNotEmpty() || (amount != AMOUNT_ONE && amount != AMOUNT_DOSE_ONE)
    return due && (typed || !wordsAskedFor(view))
}

/**
 * Whether the words' own "more than it says?" was asked of this thread's medicine: in an earlier
 * thread whose words named it, or in this one — whose words, naming no medicine of the list, are
 * about its own. The question of words about another medicine leaves this dose's own to be asked.
 */
private fun wordsAskedFor(view: DoseView): Boolean {
    val name = view.medicine?.name?.takeIf { it.isNotEmpty() } ?: return false
    fun asked(report: SymptomReport): Boolean = report.chat?.askedSteps().orEmpty().any { it in WORD_MAX_STEPS }
    fun same(names: List<String>): Boolean = names.any { written -> sameMedicine(written, name) }
    // Asked here of this thread's own words — not of an earlier thread's, which were about another dose.
    val own = (listOf(view.state.chat.words()) + typedTold(view.report).map { it.second }).joinToString("\n")
    val here = asked(view.report) && toldTooMuch(own, view.report) &&
        wordsNamed(view.report).let { it.isEmpty() || same(it) }
    return here || view.state.thread.others.any { other -> asked(other.report) && same(wordsNamed(other.report)) }
}

/**
 * The medicines of the list the words of too much in [report]'s thread named: its messages', and those
 * of an answer typed with such words in it — never the name a dose's thread was simply given.
 */
private fun wordsNamed(report: SymptomReport): List<String> {
    val typed = typedTold(report).map { it.second }.filter { toldTooMuch(it, report) }
    return medicinesIn((listOf(report.chat?.words().orEmpty()) + typed).joinToString("\n")).map { it.first }
}

/** Whether "did it happen by accident?" was answered for this medicine earlier in the chat. */
private fun purposeAnswered(view: DoseView): Boolean {
    val name = view.medicine?.name?.takeIf { it.isNotEmpty() } ?: return false
    return view.state.thread.others.any { other ->
        val named = listOfNotNull(threadMedicine(other.report)?.name) +
            medicinesIn(other.report.chat?.words().orEmpty()).map { it.first }
        !other.report.answers[SymptomStep.MED_PURPOSE].isNullOrEmpty() && named.any { sameMedicine(it, name) }
    }
}

/**
 * Whether "how have you been feeling since you started it?" is asked: as frozen when the medicine was named
 * ([feelNow]).
 */
private fun feelDue(view: DoseView): Boolean =
    view.report.meta["${view.namingStep?.name}.feel"]?.let { it == "1" } ?: feelNow(view)

/**
 * A course of an antidepressant a doctor prescribes — one taken for pain or sleep as well only when
 * its course was told "mood or nerves"; never St John's wort, whose page says nothing of its first
 * weeks — in its first [MED_START_WINDOW_DAYS] days, in a chat for today, not asked of it earlier in
 * this chat. The app knows no age here, and NHS "Antidepressants" says those aged 24 or under may be
 * most at risk, so no week between two such questions is skipped.
 */
private fun feelNow(view: DoseView): Boolean {
    val medicine = view.medicine ?: return false
    val days = medicine.days ?: return false
    val mood = !medicine.dualUse || toldMood(view, medicine)
    val asked = view.state.thread.others.any { other ->
        SymptomStep.MED_FEEL in other.report.chat?.askedSteps().orEmpty() &&
            threadMedicine(other.report)?.course == medicine.course
    }
    return medicine.prescribedAntidepressant && mood && days in 0..MED_START_WINDOW_DAYS && !view.past && !asked
}

/**
 * Whether the course of [medicine] was said to be for mood or nerves: by the chat that added it, or —
 * that chat older than the chats kept to read — carried on by a dose of it noted since, which keeps
 * the answer with the course's name ([MOOD_FACT]).
 */
private fun toldMood(view: DoseView, medicine: NamedMedicine): Boolean {
    val course = medicine.course ?: return false
    val stored = view.env.recentChats.mapNotNull { it.log }.flatMap { log ->
        log.threads.map { it.report to it.filed }
    }
    return (stored + view.state.thread.others.map { it.report to it.links }).any { (report, links) ->
        val added = report.has(SymptomStep.COURSE_FOR, SymptomOption.FOR_MOOD) &&
            links.any { it.kind == LINK_COURSE && it.at == course }
        added || NAMING_STEPS.any { step ->
            report.meta["${step.name}.c"] == course.toString() && report.meta["${step.name}.$MOOD_FACT"] == "1"
        }
    }
}

/** The fact a dose of a course for mood or nerves keeps it by, under the step that named it. */
private const val MOOD_FACT = "mood"

/**
 * Whether the record's answers are in: for a course's dose its slot and time, for one outside it what
 * it was for — and no question about too much waiting: the signs to call for help now, whether it was
 * by accident, the words' own "more than it says?" come before anything is recorded.
 */
private fun recordReady(view: DoseView): Boolean {
    val ready = when (view.path) {
        DosePath.SCHEDULED -> !view.asksWhen || timeAnswered(view)
        DosePath.ONE_OFF -> SymptomStep.MED_WHY in view.asked
        else -> false
    }
    return ready && !emergencyFired(view.report) && !safetyWaiting(view)
}

/** Whether a question about too much is still to be answered in the thread, its own or one its words brought. */
private fun safetyWaiting(view: DoseView): Boolean {
    val due = wordSteps(view.state.chat, view.report, view.env.courses) + doseSteps(view.state)
    return due.any { it in MED_WORD_STEPS && it !in view.asked }
}

private fun timeAnswered(view: DoseView): Boolean = SymptomStep.MED_WHEN in view.asked &&
    (!view.future(SymptomStep.MED_WHEN) || SymptomStep.MED_WHEN_AGAIN in view.asked)

/** Whether the answers say nothing is to be recorded: a missed dose, nothing to add, the same dose again. */
private fun noRecord(view: DoseView): Boolean = view.path == DosePath.MISSED || view.path == DosePath.NOTHING ||
    view.notTaken || view.answer(SymptomStep.MED_SAME_AGAIN) == SymptomOption.SAME_DOSE

/**
 * The step whose answer completed the record: where its card stands. Frozen with that answer
 * ([RECORD_FACT]) — a question about too much it waited for may have been the one — else, for a chat
 * of an earlier build, the record's own last step.
 */
private fun recordStep(view: DoseView): SymptomStep? =
    view.asked.firstOrNull { view.report.meta["${it.name}.$RECORD_FACT"] == "1" } ?: when {
        !recordReady(view) -> null

        view.path == DosePath.ONE_OFF -> SymptomStep.MED_WHY

        else -> listOf(SymptomStep.MED_WHEN_AGAIN, SymptomStep.MED_WHEN, SymptomStep.MED_SLOT, SymptomStep.MED_WHICH)
            .firstOrNull { it in view.asked }
    }

/** The fact frozen with the answer that made the record ready, under that step's name. */
private const val RECORD_FACT = "record"

/** The record fact for [step]'s answer in [after], the state with that answer's other facts in. */
private fun recordFact(after: ChatState, step: SymptomStep, zone: ZoneId): Map<String, String> {
    val made = after.report.meta.keys.any { it.endsWith(".$RECORD_FACT") }
    return if (!made && recordReady(DoseView(after, zone))) mapOf("${step.name}.$RECORD_FACT" to "1") else emptyMap()
}

/**
 * What tells the record apart from another the thread could make: a course's dose by its course, one
 * outside the schedule by its course when it is an extra one, and its name. A record whose medicine
 * an answer changed is another record, filed in place of the first under the other role.
 */
private fun doseIdentity(view: DoseView): String = if (view.path == DosePath.SCHEDULED) {
    MED_COURSE_PREFIX + view.medicine?.course
} else {
    SLOT_EXTRA + (view.medicine?.course?.takeIf { view.extra }?.toString().orEmpty()) + ":" +
        normalisedWords(view.medicine?.name.orEmpty()).trim()
}

/** The role the record is filed under: the one its link has while the medicine is the same, else the other. */
private fun doseRole(view: DoseView): String {
    val current = view.mains.firstOrNull { it.state == LinkState.FILED } ?: view.mains.firstOrNull() ?: return DOSE_ROLE
    return when {
        current.slot == doseIdentity(view) -> current.role
        current.role == DOSE_ROLE -> DOSE_ROLE_RENAMED
        else -> DOSE_ROLE
    }
}

/** A thread as the filer hands it in, read as a state: what the record is made from. */
private fun filingState(thread: ChatThread, ctx: FilingContext): ChatState = ChatState(
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
        others = ctx.log.threads.takeWhile { it.id != thread.id }
            .map { OtherThread(it.id, it.topic, it.report.forSave(), it.filed) },
    ),
)

/**
 * What the thread files: its dose once the answers that make it are in — kept as it stands while one
 * of them is being changed, or when its slot was found marked already, and taken back when the
 * answers say there is none — and the change to this chat's pain when the person said the painkiller
 * was taken after all.
 */
internal fun doseWants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
    val view = DoseView(filingState(thread, ctx), ctx.clock.zone)
    val wants = LinkedHashMap<String, RecordWant>()
    val role = doseRole(view)
    val link = view.mains.firstOrNull { it.role == role }
    val draft = if (recordReady(view)) doseDraft(view, ctx) else null
    when {
        // Refused for a slot marked already: Save again would only be refused again.
        draft != null && link?.state == LinkState.REFUSED && link.sig == doseSig(thread, role) ->
            wants[role] = RecordWant.Keep

        draft != null -> wants[role] = RecordWant.File(draft)

        emergencyFired(view.report) || !noRecord(view) -> view.mains.forEach { wants[it.role] = RecordWant.Keep }
    }
    painWant(view)?.let { wants[DOSE_PAIN_ROLE] = it }
    return wants
}

/** The dose's row as its page writes it: the tab's own mark for a course's dose, else one of no course. */
private fun doseDraft(view: DoseView, ctx: FilingContext): RecordDraft.Journal? =
    if (view.path == DosePath.SCHEDULED) scheduledDraft(view, ctx) else oneOffDraft(view, ctx)

/** A dose outside the schedule: its name, its amount in the language showing, what it was for. */
private fun oneOffDraft(view: DoseView, ctx: FilingContext): RecordDraft.Journal {
    val name = view.medicine?.name.orEmpty().ifEmpty { ctx.strings(R.string.med_chat_unnamed) }
    val amount = amountText(view, ctx.strings)
    val reason = takenForRow(view.answer(SymptomStep.MED_WHY))
    val noTime = timeUnsaid(view)
    val course = view.medicine?.course?.takeIf { view.extra }
    return RecordDraft.Journal(JournalDoor.DOSE, doseMoment(view, ctx.clock.now), doseIdentity(view), course) {
            shell,
            _,
        ->
        val day = if (doseClockDay(view.values[SymptomStep.MED_WHEN_AGAIN]) != null ||
            doseClockDay(view.values[SymptomStep.MED_WHEN]) != null
        ) {
            doseDay(view)
        } else if (view.past) {
            view.day
        } else {
            Instant.ofEpochMilli(
                shell.occurredAt,
            ).atZone(ctx.clock.zone).toLocalDate()
        }
        oneOffDoseEntry(name, amount, day, shell.occurredAt, reason, noTime)
    }
}

/** A dose of the schedule, marked as the Medicine tab marks it; none when its course is gone. */
private fun scheduledDraft(view: DoseView, ctx: FilingContext): RecordDraft.Journal? {
    val course = view.course ?: return null
    val slot = view.slot?.removePrefix(YESTERDAY) ?: return null
    val planned = PlannedDose(
        medicationId = course.id,
        name = course.name,
        dose = course.dose,
        food = course.foodRelation(),
        slot = slot,
        minuteOfDay = slotMinute(view) ?: 0,
        onWaking = slot == MED_SLOT_WAKE,
        contents = course.contents,
    )
    val day = slotDay(view)
    val noTime = timeUnsaid(view)
    return RecordDraft.Journal(
        JournalDoor.DOSE,
        doseMoment(view, ctx.clock.now),
        doseIdentity(view),
        course.createdAt,
    ) {
            shell,
            _,
        ->
        medTakenEntry(planned, day, shell.occurredAt, noTime)
    }
}

/** What MED_WHY's answer is in the row: the reason it was taken for. */
private fun takenForRow(answer: SymptomOption?): MedTakenFor? = when (answer) {
    SymptomOption.FOR_PAIN -> MedTakenFor.PAIN
    SymptomOption.FOR_TEMPERATURE -> MedTakenFor.TEMPERATURE
    SymptomOption.FOR_MOOD -> MedTakenFor.MOOD
    SymptomOption.FOR_SLEEP -> MedTakenFor.SLEEP
    SymptomOption.SOMETHING_ELSE -> MedTakenFor.OTHER
    else -> null
}

/**
 * The moment the dose stands at: the time chosen, its slot's time, midday of an earlier day when it is
 * not remembered, or the moment "just now" or the slot was said — never later than [now].
 */
private fun doseMoment(view: DoseView, now: Long): Long {
    val (step, said) = whenSaid(view)
    val moment = said?.let { saidMoment(view, step, it) }
        ?: (view.report.meta["MED_SLOT.at"] ?: view.report.meta["MED_WHICH.at"])?.toLongOrNull()
        ?: now
    return if (view.past) moment else minOf(moment, now)
}

/** The step "when did you take it?" was asked at last, and its answer once that has had its turn. */
private fun whenSaid(view: DoseView): Pair<SymptomStep, String?> {
    val step = if (SymptomStep.MED_WHEN_AGAIN in view.asked) SymptomStep.MED_WHEN_AGAIN else SymptomStep.MED_WHEN
    return step to view.values[step]?.takeIf { step in view.asked }
}

/**
 * Whether the dose's time was not said — "I do not remember", of the chat's day or of a day named — which
 * its row says in so many words ([statesNoTime]): its midday is also where a time chosen as 12:00 stands.
 */
private fun timeUnsaid(view: DoseView): Boolean =
    whenSaid(view).second.let { it == WHEN_UNKNOWN || it?.endsWith("@$WHEN_UNKNOWN") == true }

/**
 * The moment "when did you take it?" was answered with: "just now" as it was said, its slot's time, a time, midday
 * unknown.
 */
private fun saidMoment(view: DoseView, step: SymptomStep, said: String): Long? {
    val day = doseDay(view)
    return when (said) {
        WHEN_NOW -> view.report.meta["${step.name}.at"]?.toLongOrNull()

        WHEN_SLOT -> slotMinute(view)?.let { momentOf(day, it, view.zone) }

        WHEN_UNKNOWN -> middayOf(day, view.zone)

        else -> if (said.endsWith("@$WHEN_UNKNOWN")) {
            middayOf(day, view.zone)
        } else {
            // The date frozen as the time was said ([saidClockDay]): the thread may be filed on another.
            val frozen = view.report.meta["${step.name}.day"]?.toLocalDateOrNull()
            doseClockMinute(said)?.let { momentOf(doseClockDay(said) ?: frozen ?: clockDay(view, it), it, view.zone) }
        }
    }
}

/** The amount in words, in the language showing as the row is written: "2 tablets", "50 mg", "2 × 50 mg". */
private fun amountText(view: DoseView, strings: (Int) -> String): String {
    val dose = view.medicine?.dose.orEmpty()
    return when (val amount = view.values[SymptomStep.MED_AMOUNT]) {
        null -> ""

        AMOUNT_ONE -> strings(R.string.med_chat_amount_one_tablet)

        AMOUNT_TWO -> strings(R.string.med_chat_amount_two_tablets)

        AMOUNT_DOSE_ONE -> dose.ifEmpty { strings(R.string.med_chat_amount_dose_one_bare) }

        AMOUNT_DOSE_TWO -> if (dose.isEmpty()) {
            strings(R.string.med_chat_amount_dose_two)
        } else {
            String.format(Locale.ROOT, strings(R.string.med_chat_amount_two_of), dose)
        }

        else -> amount
    }
}

/** The change to this chat's pain once the person said yes: the painkiller was taken. */
private fun painWant(view: DoseView): RecordWant? {
    if (view.answer(SymptomStep.MED_PAIN_LINK) != SymptomOption.CHANGE_IT) return null
    val at = view.report.meta["MED_WHY.pain"]?.toLongOrNull() ?: return null
    return RecordWant.File(
        RecordDraft.Update(KIND_PAIN, at, change = { row ->
            val pain = decodePainPayload(row.measurementPayloadJson)
            row.copy(measurementPayloadJson = encodePainPayload(pain.copy(painkiller = true)))
        }),
    )
}

/** The answers each record is made of: changed only by the person changing one. */
internal fun doseSig(thread: ChatThread, role: String): Int {
    val report = thread.report.forSave()
    if (role == DOSE_PAIN_ROLE) {
        return listOf(report.meta["MED_WHY.pain"], report.optionNames(SymptomStep.MED_PAIN_LINK)).hashCode()
    }
    val steps = DOSE_STEP_KINDS.keys + setOf(SymptomStep.MED_WHY, SymptomStep.MED_SAME_AGAIN)
    val moments = listOf("MED_WHICH.at", "MED_SLOT.at", "MED_WHEN.at", "MED_WHEN_AGAIN.at").map { report.meta[it] }
    return (steps.map { report.values[it] to report.optionNames(it) } + moments).hashCode()
}

/**
 * The options [step] was answered with, by their names and in one order, for a fingerprint stored with
 * the chat ([FiledLink.sig]) and compared in the next run of the app: an option's own hash is made anew
 * in every run, and the chat's codec keeps neither the order they were ticked in nor an answer of none.
 */
internal fun SymptomReport.optionNames(step: SymptomStep): List<String> =
    answers[step].orEmpty().map { it.name }.sorted()

/**
 * The facts frozen with an answer: the medicine as named, whether its dose's time was an hour gone,
 * the moment "just now" or the slot was said and whether a time chosen has not come yet, the doses of
 * the same medicine round it, the signs "more than it says" calls for, the pain it may change — and,
 * once the thread's questions are over, the notes its close says.
 */
internal fun doseFreeze(state: ChatState, step: SymptomStep): Map<String, String> {
    val view = DoseView(state, MedicineClock.zone())
    val own = stepFacts(view, step, MedicineClock.now())
    return if (state.report.symptom == Symptom.DOSE) own + doseThreadFacts(state, step, own, view.zone) else own
}

/** The facts [step]'s answer freezes of itself. */
private fun stepFacts(view: DoseView, step: SymptomStep, now: Long): Map<String, String> {
    val state = view.state
    return when (step) {
        SymptomStep.MED_WHICH, SymptomStep.MED_NAME, SymptomStep.MED_IS_COURSE -> namingFacts(view, step, now)
        SymptomStep.MED_SLOT -> slotFacts(view, now)
        SymptomStep.MED_WHEN, SymptomStep.MED_WHEN_AGAIN -> whenFacts(view, step, now)
        SymptomStep.MED_AMOUNT -> earlierFacts(view, now)
        in MAX_STEPS -> mapOf("${step.name}.signs" to signsNow(view))
        SymptomStep.MED_SIGNS -> mapOf("MED_SIGNS.v" to frozenSigns(state.report))
        SymptomStep.MED_WHY -> painLinkFacts(view)
        else -> emptyMap()
    }
}

/**
 * The facts of a dose's own thread frozen with [step]'s answer, [own] its step's facts: that it
 * completed the record, and the notes of the close once the questions are over.
 */
private fun doseThreadFacts(
    state: ChatState,
    step: SymptomStep,
    own: Map<String, String>,
    zone: ZoneId,
): Map<String, String> {
    val record = recordFact(state.copy(report = state.report.copy(meta = state.report.meta + own)), step, zone)
    if (THREAD_NOTES in state.report.meta) return record
    val after = state.copy(report = state.report.copy(meta = state.report.meta + own + record))
    val over = nextChatStep(after) == null && notesShown(after)
    return if (over) record + notesFact(dosesNotes(DoseView(after, zone))) else record
}

/**
 * The medicine [step] names, and what its naming decides: a dose of it just before, its first weeks, the signs left
 * unasked.
 */
private fun namingFacts(view: DoseView, step: SymptomStep, now: Long): Map<String, String> {
    val typed = view.values[SymptomStep.MED_NAME].orEmpty().takeIf { step == SymptomStep.MED_NAME }
    val matches = typed?.let { matchingCourses(view, it) }.orEmpty()
    val future = typed?.let { futureCourse(view, it) }?.let { mapOf("MED_NAME.future" to it.startDay) }.orEmpty()
    val medicine = namedNow(view, step)
    return when {
        matches.isNotEmpty() -> mapOf("MED_NAME.match" to matches.joinToString(",") { it.createdAt.toString() })
        medicine == null -> future
        else -> future + medicineFacts(view, step, medicine, now)
    }
}

/** The medicine named at [step] and what its naming decides. */
private fun medicineFacts(view: DoseView, step: SymptomStep, medicine: NamedMedicine, now: Long): Map<String, String> {
    val facts = medicine.facts(step).toMutableMap()
    val named = DoseView(view.state.copy(report = view.report.copy(meta = view.report.meta + facts)), view.zone)
    if (named.slotChosen) {
        facts +=
            mapOf("MED_WHICH.late" to flag(lateNow(named, now)), "MED_WHICH.at" to now.toString())
    }
    if (medicine.course == null) sameAgain(named, medicine, now)?.let { facts["${step.name}.same"] = it.toString() }
    if (medicine.course != null) facts["${step.name}.feel"] = flag(feelNow(named))
    // A course for mood or nerves keeps that answer with each dose of it, for the chats after the one that added it.
    if (medicine.dualUse && toldMood(named, medicine)) facts["${step.name}.$MOOD_FACT"] = "1"
    carriedSigns(named, medicine)?.let { facts["${step.name}.signs"] = it }
    return facts
}

private fun flag(on: Boolean): String = if (on) "1" else "0"

/**
 * The "now" signs of a tricyclic or an opioid, when a "more than it says?" of this chat was answered
 * "more" or "not sure" and the signs then asked did not cover this medicine's — none asked, or the
 * other kind's.
 */
private fun carriedSigns(view: DoseView, medicine: NamedMedicine): String? {
    if ((!medicine.tca && !medicine.opioid) || SymptomStep.MED_SIGNS in view.asked) return null
    val mine = signsFor(listOf(medicine))
    val uncovered = (view.state.thread.others.map { it.report } + view.report).any { report ->
        val signs = if (SymptomStep.MED_SIGNS in
            report.chat?.askedSteps().orEmpty()
        ) {
            frozenSigns(report)
        } else {
            SIGNS_NONE
        }
        tooMuchSaid(report) && signs != SIGNS_ANY && signs != mine
    }
    return mine.takeIf { uncovered }
}

/**
 * Which dose: whether its time was an hour gone and the moment it was said; for an extra one, a dose
 * just before; for a missed one, the one dose then gone by, if just one was.
 */
private fun slotFacts(view: DoseView, now: Long): Map<String, String> {
    val medicine = view.medicine ?: return emptyMap()
    return when {
        view.path == DosePath.SCHEDULED -> mapOf(
            "MED_SLOT.late" to flag(lateNow(view, now)),
            "MED_SLOT.at" to now.toString(),
        )

        view.path == DosePath.MISSED -> mapOf("MED_SLOT.missed" to goneDoses(view, now).singleOrNull()?.slot.orEmpty())

        view.extra -> sameAgain(view, medicine, now)?.let { mapOf("MED_SLOT.same" to it.toString()) }.orEmpty()

        else -> emptyMap()
    }
}

/**
 * "Just now" frozen as the moment it was said; a time said with no date, the date it stands on as it is
 * said ([saidClockDay]) — last night's for one still to come given again ([lastNight]); a time chosen
 * today that has not come yet, said so.
 */
private fun whenFacts(view: DoseView, step: SymptomStep, now: Long): Map<String, String> {
    val said = view.values[step] ?: return emptyMap()
    if (said == WHEN_NOW) return mapOf("${step.name}.at" to now.toString())
    val minute = doseClockMinute(said) ?: return emptyMap()
    val dated = doseClockDay(said)
    val given = dated ?: saidClockDay(view, minute, now)
    val day = if (lastNight(view, step, minute, given, now)) given.minusDays(1) else given
    val future = !view.past && momentOf(day, minute, view.zone) > now
    return listOfNotNull(
        ("${step.name}.day" to day.toString()).takeIf { dated == null },
        ("${step.name}.future" to "1").takeIf { future },
    ).toMap()
}

/**
 * The date a time said with none stands on, as it is said at [now]: frozen with the answer, since the
 * thread may be finished on another date. «В 8» said of a dose of no course at ten to midnight and
 * filed after it would otherwise stand on the new day, where eight in the evening has not come, and
 * the dose would be dated the minute it was filed.
 *
 * It is [clockDay], but for a thread carried past midnight before the time is said: a time that has
 * already come on the new date is that date's. 00:03 chosen at five past midnight for a dose picked
 * the evening before would otherwise go on the evening's date, a day before it was taken; the dose
 * itself stays that evening's ([slotDay]).
 */
private fun saidClockDay(view: DoseView, minute: Int, now: Long): LocalDate {
    val day = clockDay(view, minute)
    val real = Instant.ofEpochMilli(now).atZone(view.zone).toLocalDate()
    // Against the day the dose was chosen on, not [day]: one of yesterday's slots stands a day back with no midnight.
    return if (!view.past && real > view.day && momentOf(real, minute, view.zone) <= now) real else day
}

/**
 * Whether a dose outside the schedule given a time still to come on [day] once more, after "that time has
 * not come yet today", was taken the evening before: 23:40 chosen twice at twenty past midnight, which the
 * clock's picker has no other way to say. Read otherwise, it would stand at the minute it was said, a
 * time nobody gave. A date the words named stays theirs.
 */
private fun lastNight(view: DoseView, step: SymptomStep, minute: Int, day: LocalDate, now: Long): Boolean =
    step == SymptomStep.MED_WHEN_AGAIN && view.path == DosePath.ONE_OFF && !view.past &&
        listOf(SymptomStep.MED_WHEN, step).none { doseClockDay(view.values[it]) != null } &&
        momentOf(day, minute, view.zone) > now

/** A dose of the same medicine outside the schedule in the half hour before [now]: "is this another dose?". */
private fun sameAgain(view: DoseView, medicine: NamedMedicine, now: Long): Long? {
    if (view.past || medicine.name.isEmpty()) return null
    return view.journal.filter { row ->
        isOneOffDose(row) && row.occurredAt in (now - SAME_AGAIN_MS)..now && sameMedicine(row.title, medicine.name)
    }.maxOfOrNull { it.occurredAt }
}

/** The doses of the same medicine in the 24 hours round this one: what "more than it says?" is asked beside. */
private fun earlierFacts(view: DoseView, now: Long): Map<String, String> {
    val name = view.medicine?.name?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    val moment = doseMoment(view, now)
    val rows = view.journal.filter { row ->
        row.kind == KIND_MED && kotlin.math.abs(row.occurredAt - moment) <= MED_DAY_MS && sameMedicine(row.title, name)
    }.sortedBy { it.occurredAt }
    if (rows.isEmpty()) return emptyMap()
    val joined = rows.joinToString(";") { row -> "${row.occurredAt}|${row.subtitle.filter { it != ';' && it != '|' }}" }
    return mapOf("MED_AMOUNT.earlier" to joined)
}

/**
 * The signs "more than it says" calls for: those of the thread's medicine — one whose name is not
 * known calls for all of them — and of every medicine its words and typed answers name, together;
 * all of them when none is named at all.
 */
private fun signsNow(view: DoseView): String {
    val own = view.medicine ?: threadMedicine(view.report)
    val words = view.state.chat.words() + "\n" + typedWords(view.report)
    val named = medicinesIn(words).map { (written, _) -> namedMedicine(written) }
    return signsFor(listOfNotNull(own) + named)
}

/** The pain this chat noted with "no painkiller", when the dose was taken for pain: its moment. */
private fun painLinkFacts(view: DoseView): Map<String, String> {
    if (view.answer(SymptomStep.MED_WHY) != SymptomOption.FOR_PAIN) return emptyMap()
    val pain = view.state.thread.others.lastOrNull { other ->
        other.topic == Symptom.PAIN && other.report.has(SymptomStep.PAIN_PAINKILLER, SymptomOption.NO)
    } ?: return emptyMap()
    val link = pain.links.firstOrNull { it.kind == KIND_PAIN && it.role.isEmpty() && it.state == LinkState.FILED }
    return link?.let { mapOf("MED_WHY.pain" to it.at.toString()) }.orEmpty()
}

/** The options of each of the dose thread's own questions, and the core's for one the words brought. */
internal fun doseOptions(state: ChatState, step: SymptomStep): List<SymptomOption> = when (step) {
    SymptomStep.MED_SAME_AGAIN -> listOf(SymptomOption.ANOTHER_DOSE, SymptomOption.SAME_DOSE)

    in MAX_STEPS -> listOf(SymptomOption.NO, SymptomOption.MORE_THAN_IT_SAYS, SymptomOption.NOT_SURE)

    SymptomStep.MED_SIGNS -> listOf(SymptomOption.YES, SymptomOption.NO)

    SymptomStep.MED_PURPOSE -> listOf(SymptomOption.BY_ACCIDENT, SymptomOption.ON_PURPOSE, SymptomOption.RATHER_NOT_SAY)

    SymptomStep.MED_WHY -> listOf(
        SymptomOption.FOR_PAIN,
        SymptomOption.FOR_TEMPERATURE,
        SymptomOption.FOR_MOOD,
        SymptomOption.FOR_SLEEP,
        SymptomOption.SOMETHING_ELSE,
    )

    SymptomStep.MED_PAIN_LINK -> listOf(SymptomOption.CHANGE_IT, SymptomOption.LEAVE_IT)

    SymptomStep.MED_FEEL -> listOf(
        SymptomOption.FEEL_BETTER,
        SymptomOption.FEEL_SAME,
        SymptomOption.FEEL_WORSE,
        SymptomOption.SIDE_EFFECTS,
        SymptomOption.RATHER_NOT_SAY,
    )

    SymptomStep.MED_SERIOUS -> listOfNotNull(
        SymptomOption.SERIOUS_HARM,
        SymptomOption.SERIOUS_HIGH,
        SymptomOption.SERIOUS_HEART,
        SymptomOption.SERIOUS_BLEEDING,
        SymptomOption.SERIOUS_THROAT,
        SymptomOption.SERIOUS_COLLAPSE.takeIf { threadMedicine(state.report)?.tca == true },
        SymptomOption.NONE,
    )

    else -> chatOptions(null, step, state.female)
}

/** What each question waits for: the pick of the person's courses, chips of values, the composer, the options. */
internal fun doseReply(state: ChatState, step: SymptomStep): ChatReply {
    val view = DoseView(state, MedicineClock.zone())
    return when (step) {
        SymptomStep.MED_WHICH -> whichReply(view)

        SymptomStep.MED_NAME -> nameReply(view)

        SymptomStep.MED_IS_COURSE -> isCourseReply(view)

        SymptomStep.MED_SLOT -> slotReply(view)

        SymptomStep.MED_WHEN, SymptomStep.MED_WHEN_AGAIN -> whenReply(view, step)

        SymptomStep.MED_AMOUNT -> amountReply(view)

        SymptomStep.MED_SERIOUS -> ChatReply.Choose(
            step,
            doseOptions(state, step),
            labels = if (threadMedicine(state.report)?.tca == true) {
                mapOf(SymptomOption.SERIOUS_HEART to R.string.med_chat_serious_heart_tca)
            } else {
                emptyMap()
            },
        )

        else -> ChatReply.Choose(step, doseOptions(state, step))
    }
}

/** The words of the thread, and of the message that named its mention: what orders and proposes the chips. */
private fun threadWords(view: DoseView): String = view.words

/**
 * What the model's readings of those messages told ([DoseView.told]): their medicines, amounts and
 * times join the words' own as the same proposals, a tap each.
 */
private fun threadToldOf(view: DoseView): ReadTold = view.told

/** The mention the thread was started from ("DOSE:нурофен", or a follow-up's "DOSE:PAINKILLER"), if any. */
private fun startedFrom(view: DoseView): TopicMention? =
    view.state.thread.from?.removePrefix(ROUTE_FOLLOW)?.let(::mentionOfKey)?.takeIf { it.topic == Symptom.DOSE }

/**
 * The person's courses for the day, ordered: named in the words or by a reading of them, the same
 * medicine, the same class, the rest.
 */
private fun whichReply(view: DoseView): ChatReply.MedicinePick {
    val found = MedicineWords(threadWords(view), view.env.courses)
    val hint = startedFrom(view)?.detail.orEmpty()
    val classHint = hint == DETAIL_PAINKILLER || hint == DETAIL_ANTIDEPRESSANT
    val named = found.named.map { it.first } + listOf(hint).filter { it.isNotEmpty() && !classHint } +
        threadToldOf(view).medicineNames()
    val classes = found.classes + listOfNotNull(
        MedicineClass.PAINKILLER.takeIf { hint == DETAIL_PAINKILLER },
        MedicineClass.ANTIDEPRESSANT.takeIf { hint == DETAIL_ANTIDEPRESSANT },
    )
    fun rank(course: Medication): Int = when {
        found.own.any { it.createdAt == course.createdAt } -> 0
        named.any { sameMedicine(it, course.name) } -> 1
        medicineClassOf(course.name) in classes -> 2
        else -> 3
    }
    val open = view.pickable.associateWith { openDoses(view, it) }
    val ordered = view.pickable.sortedWith(
        compareBy<Medication>({
            rank(it)
        }, { open[it]?.firstOrNull()?.minuteOfDay ?: MINUTES_A_DAY }, { it.name.lowercase() }),
    )
    val now = MedicineClock.now()
    // Words of a dose missed never take the one tap that marks it taken: "which dose" offers "I missed a dose".
    val missed = missedSaid(view)
    val choices = ordered.map { course ->
        val single = open[course]?.singleOrNull()?.takeIf { !missed && oneTap(view, course, it, now) }
        MedicineChoice(course.createdAt, course.name, course.dose, course.photo, single?.slot, single?.let(::slotLabel))
    }
    // A name the words gave is answered with the name its chip shows, the list's base form ([wordsName]),
    // as the dose is then called.
    val names = (found.named.map { it.first } + threadToldOf(view).medicineNames()).distinctBy { normalisedWords(it) }
    val proposals =
        names.filter { name -> view.pickable.none { sameMedicine(it.name, name) } }
            .map { written ->
                val shown = wordsName(written)
                ValueChip(
                    "$MED_OTHER:$shown",
                    ChatText(R.string.med_chat_pick_not_listed, listOf(PieceArg.Text(shown))),
                )
            } + ValueChip(MED_OTHER, ChatText(R.string.med_chat_pick_other))
    return ChatReply.MedicinePick(SymptomStep.MED_WHICH, choices, proposals, more = choices.size > PICK_ALL_AT)
}

/**
 * Whether the pick's chip may carry [course]'s one open [dose] of the day: never while one of
 * yesterday's is still inside its grace — that is asked about first — nor a dose whose time is more
 * than [ONE_TAP_EARLY_MINUTES] away, which a pill taken now more likely is not: "which of today's
 * doses" then offers "an extra one" beside it. Any one dose of a day gone by.
 */
private fun oneTap(view: DoseView, course: Medication, dose: PlannedDose, now: Long): Boolean {
    if (view.past) return true
    val near = dose.minuteOfDay <= minuteOf(now, view.zone) + ONE_TAP_EARLY_MINUTES
    return near && yesterdayInGrace(view, course, now).isEmpty()
}

/** A dose as a chip says it: its time, or "on waking". */
private fun slotLabel(dose: PlannedDose): ChatText =
    if (dose.onWaking) ChatText(R.string.med_dose_on_waking) else plainClock(dose.minuteOfDay)

/** [course]'s doses of [day] not yet marked, in time order. */
private fun openDoses(view: DoseView, course: Medication, day: LocalDate = view.day): List<PlannedDose> =
    plannedDoses(listOf(course), view.journal, day, wakeMinuteOf(view.journal, day, view.zone)).filter { !it.taken }

/**
 * The composer for the name, with the names the words gave or a reading of them told — the list's
 * base forms ([wordsName]) — the ones noted lately as they were saved, and "I do not know its name".
 */
private fun nameReply(view: DoseView): ChatReply.TextAsk {
    val words = (medicinesIn(threadWords(view)).map { it.first } + threadToldOf(view).medicineNames()).map(::wordsName)
    val recent = view.env.recentChats.asSequence().mapNotNull { it.log }.flatMap { it.threads }
        .flatMap { it.filed }.filter { it.kind == KIND_MED && it.slot?.startsWith(SLOT_EXTRA) == true }
        .map { it.title }.toList()
    val names = (words + recent).filter { it.isNotBlank() }.distinctBy { normalisedWords(it) }.take(NAME_SUGGESTIONS)
    val suggestions = names.map(::nameChip) + ValueChip(MED_UNKNOWN, ChatText(R.string.med_chat_name_unknown))
    return ChatReply.TextAsk(SymptomStep.MED_NAME, R.string.med_chat_name_hint, MED_NAME_MAX, suggestions)
}

/** "Is it X from your medicines?": yes or no for one match, a pick of them for several. */
private fun isCourseReply(view: DoseView): ChatReply {
    val matches = view.nameMatches.mapNotNull { at -> view.pickable.firstOrNull { it.createdAt == at } }
    val other = ValueChip(MED_OTHER, ChatText(R.string.med_chat_option_different))
    val single = matches.singleOrNull() ?: return ChatReply.MedicinePick(
        SymptomStep.MED_IS_COURSE,
        matches.map { MedicineChoice(it.createdAt, it.name, it.dose, it.photo) },
        listOf(other),
        more = false,
    )
    val yes = ValueChip(MED_COURSE_PREFIX + single.createdAt, ChatText(R.string.med_chat_option_that_one))
    return ChatReply.Values(SymptomStep.MED_IS_COURSE, listOf(yes, other))
}

/**
 * The course's doses of the day not yet marked, in time order — and after midnight yesterday's still
 * inside their grace — "An extra one", and "I missed a dose" when the words say so, or a reading told
 * a dose missed, or a dose is past its time; with every dose marked, only an extra one or nothing to
 * add. Never one chosen for the person.
 */
private fun slotReply(view: DoseView): ChatReply.Values {
    val course = view.course
    val now = MedicineClock.now()
    val open = course?.let { openDoses(view, it) }.orEmpty()
    val yesterday = course?.takeIf { !view.past }?.let { yesterdayInGrace(view, it, now) }.orEmpty()
    val extra = ValueChip(SLOT_EXTRA, ChatText(R.string.med_chat_slot_extra))
    if (open.isEmpty() && yesterday.isEmpty()) {
        val nothing = ValueChip(SLOT_NOTHING, ChatText(R.string.med_chat_slot_nothing))
        return ChatReply.Values(SymptomStep.MED_SLOT, listOf(extra, nothing))
    }
    val missedWords = missedSaid(view)
    val pastTime = view.past || yesterday.isNotEmpty() || open.any { it.minuteOfDay < minuteOf(now, view.zone) }
    val missed = ValueChip(SLOT_MISSED, ChatText(R.string.med_chat_slot_missed), accent = missedWords)
    val chips = open.map { ValueChip(it.slot, slotLabel(it)) } + yesterday.map { dose ->
        val time = if (dose.onWaking) PieceArg.Res(R.string.med_dose_on_waking) else PieceArg.Clock(dose.minuteOfDay)
        ValueChip(YESTERDAY + dose.slot, ChatText(R.string.med_chat_slot_yesterday, listOf(time)))
    } + listOfNotNull(extra, missed.takeIf { missedWords || pastTime })
    return ChatReply.Values(SymptomStep.MED_SLOT, chips)
}

/** Whether the words the thread reads, or a reading of them, say a dose was missed: "forgot", «не выпила». */
private fun missedSaid(view: DoseView): Boolean = missedWordsIn(threadWords(view), view.env.courses) ||
    threadToldOf(view).medicines.any { ReadRole.MISSED in it.roles }

/**
 * "I missed a dose" where a dose of no course is asked when it was taken, once its words — or a reading of
 * them — say it was not. «Не выпила нурофен» starts a dose's thread as «выпила нурофен» does, and without
 * this chip such a thread could only note the dose as taken.
 */
private fun missedChip(view: DoseView, step: SymptomStep): ValueChip? {
    val onItsOwn = view.path == DosePath.ONE_OFF && !view.extra
    return ValueChip(WHEN_MISSED, ChatText(R.string.med_chat_slot_missed), accent = true)
        .takeIf { step == SymptomStep.MED_WHEN && onItsOwn && missedSaid(view) }
}

/** Yesterday's doses not marked whose grace runs past midnight into [now], as the notification still offers them. */
private fun yesterdayInGrace(view: DoseView, course: Medication, now: Long): List<PlannedDose> {
    val minute = minuteOf(now, view.zone)
    val yesterday = view.day.minusDays(1)
    if (minute >= MED_GRACE_MINUTES || !course.activeOn(yesterday)) return emptyList()
    return openDoses(view, course, yesterday).filter { minute + MINUTES_A_DAY - it.minuteOfDay <= MED_GRACE_MINUTES }
}

/** The doses of the thread's course whose time has gone by, not marked: the one a missed dose is, if just one. */
private fun goneDoses(view: DoseView, now: Long): List<PlannedDose> {
    val course = view.course ?: return emptyList()
    return openDoses(view, course).filter { view.past || it.minuteOfDay < minuteOf(now, view.zone) }
}

/**
 * "When did you take it?": just now, or earlier today with the time — at its own time for a dose of
 * the schedule — and for an earlier day the time, its slot's, or not remembered. Once the words name
 * a time or a day other than the doses' own ([DoseView.timeSaid]), or a reading of them told a time,
 * only those times and the clock are offered, with "not remembered" for an earlier day
 * ([mentionedClockReply]): never "just now", nor the slot's time. A time is chosen on the app's own
 * clock. Last, "I missed a dose" where the words say so of a dose of no course ([missedChip]).
 */
private fun whenReply(view: DoseView, step: SymptomStep): ChatReply.Values {
    val proposals = saidClockChips(view)
    if (view.timeSaid || proposals.isNotEmpty()) return mentionedClockReply(view, step, proposals)
    val minute = slotMinute(view)?.takeIf { view.path == DosePath.SCHEDULED }
    val atSlot = minute?.let { ValueChip(WHEN_SLOT, ChatText(R.string.med_chat_when_slot, listOf(PieceArg.Clock(it)))) }
    val now = ValueChip(WHEN_NOW, ChatText(R.string.med_chat_when_now))
    val chips = when {
        view.past -> listOfNotNull(
            atSlot,
            ValueChip(
                (minute ?: MIDDAY_MINUTE).toString(),
                ChatText(R.string.med_chat_when_choose),
                picker = ValuePicker.CLOCK,
            ),
            ValueChip(WHEN_UNKNOWN, ChatText(R.string.med_chat_when_unknown)),
        )

        atSlot != null -> listOf(
            now,
            atSlot,
            ValueChip(minute.toString(), ChatText(R.string.med_chat_when_other), picker = ValuePicker.CLOCK),
        )

        else -> {
            val nowMinute = minuteOf(MedicineClock.now(), view.zone)
            val earlier = ValueChip(
                (nowMinute - nowMinute % FIVE_MINUTES).toString(),
                ChatText(R.string.med_chat_when_earlier),
                picker = ValuePicker.CLOCK,
            )
            listOf(now, earlier) + proposals
        }
    }
    return ChatReply.Values(step, chips + listOfNotNull(missedChip(view, step)))
}

private fun mentionedClockReply(view: DoseView, step: SymptomStep, proposals: List<ValueChip>): ChatReply.Values {
    val day = view.timing.day
    val label = day?.let { ChatText(R.string.med_chat_when_day_choose, listOf(PieceArg.Day(it.toString()))) }
        ?: ChatText(R.string.med_chat_when_choose)
    val choose = ValueChip("PICK:" + doseClockValue(day, MIDDAY_MINUTE), label, picker = ValuePicker.CLOCK)
    val unknown = day?.let {
        ValueChip("$it@$WHEN_UNKNOWN", dayUnknownText(it))
    } ?: ValueChip(WHEN_UNKNOWN, ChatText(R.string.med_chat_when_unknown))
    val choices = proposals + choose + listOfNotNull(unknown.takeIf { day?.isBefore(view.today) == true || view.past })
    return ChatReply.Values(step, choices + listOfNotNull(missedChip(view, step)))
}

/** Clock words take precedence over span proposals so an explicit AM/PM is never contradicted. */
private fun saidClockChips(view: DoseView): List<ValueChip> {
    val timing = view.timing
    val minutes = timing.minutes.ifEmpty { view.told.clocks }
    return minutes.distinct().map { minute ->
        ValueChip(doseClockValue(timing.day, minute), dayClockText(timing.day, minute), accent = true)
    }
}

private fun dayClockText(day: LocalDate?, minute: Int): ChatText = if (day == null) {
    ChatText(R.string.med_chat_when_at, listOf(PieceArg.Clock(minute)))
} else {
    ChatText(R.string.med_chat_when_day_at, listOf(PieceArg.Day(day.toString()), PieceArg.Clock(minute)))
}

private fun dayUnknownText(day: LocalDate): ChatText =
    ChatText(R.string.med_chat_when_day_unknown, listOf(PieceArg.Day(day.toString())))

/**
 * "How much did you take?": for an extra dose of a course one dose or two of it, for a spray, a cream
 * or drops one dose, else one tablet or two — and the composer for any other amount, the amounts the
 * words named or a reading of them told suggested beside it.
 */
private fun amountReply(view: DoseView): ChatReply.TextAsk {
    val dose = view.medicine?.dose.orEmpty()
    val words = threadWords(view)
    val doseOne = if (dose.isEmpty()) {
        ChatText(R.string.med_chat_amount_dose_one_bare)
    } else {
        ChatText(R.string.med_chat_amount_dose_one, listOf(PieceArg.Text(dose)))
    }
    val chips = when {
        view.extra -> listOf(
            ValueChip(AMOUNT_DOSE_ONE, doseOne),
            ValueChip(AMOUNT_DOSE_TWO, ChatText(R.string.med_chat_amount_dose_two)),
        )

        topicalWordsIn(words) -> listOf(ValueChip(AMOUNT_DOSE_ONE, ChatText(R.string.med_chat_amount_dose_one_bare)))

        else -> listOf(
            ValueChip(AMOUNT_ONE, ChatText(R.string.med_chat_amount_one_tablet)),
            ValueChip(AMOUNT_TWO, ChatText(R.string.med_chat_amount_two_tablets)),
        )
    }
    val said = (doseTextsIn(words) + threadToldOf(view).amounts).distinctBy { it.lowercase() }
        .map { ValueChip(it, medText(it), accent = true) }
    return ChatReply.TextAsk(SymptomStep.MED_AMOUNT, R.string.med_chat_amount_hint, MED_NAME_MAX, chips + said)
}

/** Each question in its words: by the day for "when", by how it was asked for "more than it says". */
internal fun doseQuestion(state: ChatState, step: SymptomStep): Int = when (step) {
    SymptomStep.MED_WHEN -> when {
        DoseView(state, MedicineClock.zone()).timing.ambiguous -> R.string.med_chat_ask_when_am_pm
        state.pastDay -> R.string.med_chat_ask_when_day
        else -> R.string.med_chat_ask_when
    }

    SymptomStep.MED_WHEN_AGAIN -> R.string.med_chat_when_future

    SymptomStep.MED_MAX -> maxQuestion(state)

    SymptomStep.MED_MAX_WORDS, SymptomStep.MED_MAX_AGAIN -> R.string.med_chat_ask_max_words

    SymptomStep.MED_SIGNS -> signsQuestion(frozenSigns(state.report))

    SymptomStep.MED_PAIN_LINK -> R.string.med_chat_ask_pain_link

    SymptomStep.MED_SERIOUS -> R.string.med_chat_ask_serious

    SymptomStep.MED_SAME_AGAIN -> R.string.med_chat_option_another_dose

    in DOSE_OWN_STEPS -> step.labelRes

    else -> chatQuestionRes(state.report.symptom, step, state.report, state.pastDay)
}

/** The steps whose label is their question. */
private val DOSE_OWN_STEPS = setOf(
    SymptomStep.MED_WHICH,
    SymptomStep.MED_NAME,
    SymptomStep.MED_IS_COURSE,
    SymptomStep.MED_SLOT,
    SymptomStep.MED_AMOUNT,
    SymptomStep.MED_PURPOSE,
    SymptomStep.MED_WHY,
    SymptomStep.MED_FEEL,
)

/** A dose's own "more than the packet says?"; "than your prescription" of an extra dose of a course. */
private fun maxQuestion(state: ChatState): Int =
    if (DoseView(state, MedicineClock.zone()).extra) R.string.med_chat_ask_max_course else R.string.med_chat_ask_max

/** The "now" signs in a tricyclic's, an opioid's or every medicine's words. */
internal fun signsQuestion(signs: String): Int = when (signs) {
    SIGNS_TCA -> R.string.med_chat_ask_signs_tca
    SIGNS_OPIOID -> R.string.med_chat_ask_signs_opioid
    else -> R.string.med_chat_ask_signs_any
}

/** The questions that need the medicine's name, a day or a time. */
internal fun doseQuestionText(state: ChatState, step: SymptomStep): ChatText? {
    val view = DoseView(state, MedicineClock.zone())
    val name = PieceArg.Text(view.medicine?.name?.let(::displayName).orEmpty())
    return when (step) {
        SymptomStep.MED_IS_COURSE -> view.nameMatches.singleOrNull()
            ?.let { at -> view.pickable.firstOrNull { it.createdAt == at } }
            ?.let { ChatText(R.string.med_chat_ask_is_course, listOf(PieceArg.Text(it.name))) }

        SymptomStep.MED_SLOT -> ChatText(
            R.string.med_chat_ask_slot_day,
            listOf(PieceArg.Day(view.day.toString())),
        ).takeIf {
            view.past
        }

        SymptomStep.MED_SAME_AGAIN -> view.sameAgainAt?.let {
            ChatText(R.string.med_chat_ask_same_again, listOf(name, PieceArg.Moment(it)))
        }

        SymptomStep.MED_FEEL -> ChatText(R.string.med_chat_ask_feel, listOf(name))

        SymptomStep.CRISIS -> ChatText(R.string.med_chat_ask_crisis_start, listOf(name)).takeIf {
            view.report.symptom ==
                Symptom.DOSE
        }

        else -> null
    }
}

/**
 * What stands behind a question's "?": the app's own limits for "more than it says", the source of the crisis question.
 */
internal fun doseHelp(state: ChatState, step: SymptomStep): ChatText? = when (step) {
    in MAX_STEPS -> ChatText(R.string.med_disclaimer)

    SymptomStep.CRISIS -> ChatText(R.string.med_chat_source_antidepressants).takeIf {
        state.report.symptom ==
            Symptom.DOSE
    }

    else -> null
}

/**
 * What the bot says between an answer and the next question: the start of the thread, a line about a
 * course that starts later or is gone, the saved card right after the answer that completed it, a
 * short thanks after a calm answer, every dose already marked, the doses noted round this one before
 * "more than it says?", the lead to the crisis question.
 */
internal fun doseLead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> {
    last ?: return emptyList()
    val view = DoseView(state, MedicineClock.zone())
    val name = PieceArg.Text(view.medicine?.name?.let(::displayName).orEmpty())
    val pieces = mutableListOf<BotPiece>()
    if (last.step == SymptomStep.SYMPTOM) pieces += startLine(state, R.string.med_chat_start_dose)
    state.report.meta["MED_NAME.future"]?.takeIf { last.step == SymptomStep.MED_NAME }?.let { start ->
        pieces += BotPiece.Note(ChatText(R.string.med_chat_future_course, listOf(name, PieceArg.Day(start))))
    }
    if (view.courseGone && last.step in GONE_AFTER && step !in GONE_AFTER) {
        pieces += BotPiece.Note(ChatText(R.string.med_chat_course_gone, listOf(name)))
    }
    if (recordStep(view) == last.step) pieces += BotPiece.Saved(state.thread.id, doseRole(view))
    if (last.alert == null && calmAnswer(view, last.step)) pieces += BotPiece.Line(ChatLine.ACK_1)
    pieces += beforeQuestion(view, step)
    if (wordsCheckDue(state, step)) pieces += BotPiece.Line(ChatLine.WORDS_CHECK)
    return pieces
}

/**
 * Whether "one question about what you wrote" comes before [step]: the first question words brought —
 * a typed answer's "more than it says?" among them — and never again before what follows that one.
 */
internal fun wordsCheckDue(state: ChatState, step: SymptomStep): Boolean =
    step in WORD_MAX_STEPS || (askedForWords(state, step) && step !in MED_WORD_STEPS)

/** The steps after which a course found gone is said, before the questions of a dose on its own. */
private val GONE_AFTER =
    setOf(SymptomStep.MED_WHICH, SymptomStep.MED_NAME, SymptomStep.MED_IS_COURSE, SymptomStep.MED_SIGNS)

/** "No" to "more than it says?", and a calm answer about the first weeks: a short thanks, never "that is fine". */
private fun calmAnswer(view: DoseView, step: SymptomStep): Boolean = when (step) {
    in MAX_STEPS -> view.answer(step) == SymptomOption.NO

    SymptomStep.MED_FEEL -> view.answer(step) in
        setOf(SymptomOption.FEEL_BETTER, SymptomOption.FEEL_SAME, SymptomOption.RATHER_NOT_SAY)

    else -> false
}

/** What comes right before [step]'s question. */
private fun beforeQuestion(view: DoseView, step: SymptomStep): List<BotPiece> = when {
    step == SymptomStep.MED_MAX && view.earlier.isNotEmpty() && view.report.symptom == Symptom.DOSE ->
        listOf(BotPiece.Note(ChatText(R.string.med_chat_earlier_title))) + view.earlier.map { (at, amount) ->
            BotPiece.Note(ChatText(R.string.med_chat_earlier_row, listOf(PieceArg.Moment(at), PieceArg.Text(amount))))
        }

    step == SymptomStep.CRISIS -> listOf(BotPiece.Line(ChatLine.CRISIS_LEAD))

    step == SymptomStep.MED_SLOT -> allMarked(view)

    else -> emptyList()
}

/** "Today’s doses of X are already marked: 09:00, 21:00", when nothing is left to mark. */
private fun allMarked(view: DoseView): List<BotPiece> {
    val course = view.course ?: return emptyList()
    val left = openDoses(view, course).isNotEmpty() ||
        (!view.past && yesterdayInGrace(view, course, MedicineClock.now()).isNotEmpty())
    val marked = plannedDoses(listOf(course), view.journal, view.day, wakeMinuteOf(view.journal, view.day, view.zone))
    if (left || marked.isEmpty()) return emptyList()
    val times = PieceArg.Joined(
        marked.map { if (it.onWaking) PieceArg.Res(R.string.med_dose_on_waking) else PieceArg.Clock(it.minuteOfDay) },
        R.string.list_comma,
    )
    return listOf(
        BotPiece.Note(ChatText(R.string.med_chat_all_marked, listOf(PieceArg.Text(displayName(course.name)), times))),
    )
}

/** The thread's start: "Let us note it down" for the chat's first medicine thread, "Now the medicine" after one. */
internal fun startLine(state: ChatState, first: Int): BotPiece.Note {
    val earlier = state.thread.before.any { it == Symptom.DOSE || it == Symptom.COURSE }
    return BotPiece.Note(ChatText(if (earlier) R.string.med_chat_now_the_medicine else first))
}

/**
 * Roles whose card stands after [step]'s answer: the dose's after the answer that completed it, the pain's after
 * "change it".
 */
internal fun doseSavedAfter(state: ChatState, step: SymptomStep): List<String> {
    val view = DoseView(state, MedicineClock.zone())
    return listOfNotNull(
        doseRole(view).takeIf { recordStep(view) == step },
        DOSE_PAIN_ROLE.takeIf { step == SymptomStep.MED_PAIN_LINK && view.answer(step) == SymptomOption.CHANGE_IT },
    )
}

/**
 * The close: the last answer's line and the cards it completed; after a line to call emergency
 * services what to do and nothing else; a missed dose's line and that nothing is noted; that a dose
 * was marked already; after a line to get advice now, that it should not wait; else, unless an
 * answer was about harm to oneself, the notes about alcohol and medicines that may not mix.
 */
internal fun doseClose(state: ChatState, alert: List<BotPiece>): List<BotPiece> {
    val report = state.report
    if (emergencyFired(report)) {
        return alert +
            closeLines(SymptomUrgency.EMERGENCY, crisisAnswered(report), Symptom.DOSE).map { BotPiece.Line(it) }
    }
    val view = DoseView(state, MedicineClock.zone())
    val lastStep = (state.chat.turns.lastOrNull { it is ChatTurn.Asked } as? ChatTurn.Asked)?.step
    val saved = lastStep?.let { step ->
        doseSavedAfter(state, step).map { BotPiece.Saved(state.thread.id, it) }
    }.orEmpty()
    // An antidepressant noted on its own while the person has no course of it: the chip to add it follows.
    val addCourse = BotPiece.Note(ChatText(R.string.med_chat_ask_add_course))
        .takeIf { notesShown(state) && addCourseName(view) != null }
    return alert + saved + outcomeNotes(view) + closeTail(state) { dosesNotes(view) } + listOfNotNull(addCourse)
}

/** The end of a close: that getting advice should not wait, or the notes — the frozen ones, else [notes]. */
internal fun closeTail(state: ChatState, notes: () -> List<MedNote>): List<BotPiece> = when {
    nowLineFired(state.report) -> listOf(BotPiece.Line(ChatLine.CLOSE_NOW))
    !notesShown(state) -> emptyList()
    else -> (state.report.meta[THREAD_NOTES]?.let(::decodeNotes) ?: notes()).map(::notePiece)
}

/**
 * Whether a thread's close says its notes: not after a line to get advice now, nor after an answer about harm to
 * oneself.
 */
internal fun notesShown(state: ChatState): Boolean = !emergencyFired(state.report) && !nowLineFired(state.report) &&
    !crisisAnswered(state.report) && !state.thread.crisisInChat

/** What the answers came to when nothing is recorded: a missed dose, nothing new, a dose marked already. */
private fun outcomeNotes(view: DoseView): List<BotPiece> = when {
    view.path == DosePath.MISSED || view.notTaken -> missedNotes(view)

    view.path == DosePath.NOTHING || view.answer(SymptomStep.MED_SAME_AGAIN) == SymptomOption.SAME_DOSE ->
        listOf(BotPiece.Note(ChatText(R.string.med_chat_nothing_new)))

    view.mains.any { it.state == LinkState.REFUSED } -> listOf(
        BotPiece.Note(
            alreadyMarkedAt(view)?.let { ChatText(R.string.med_chat_already_marked, listOf(PieceArg.Moment(it))) }
                ?: ChatText(R.string.med_chat_nothing_new),
        ),
    )

    else -> emptyList()
}

/**
 * When the dose the chat could not mark was marked, read as the door that refused it reads the day
 * ([ChatDoors.write]): the row under its own course and slot, else the one the Medicine tab shows its
 * slot taken by ([plannedDoses]) — one an edit of the course's times left over. Null when neither
 * stands: the slot is none the course asks for that day — its times changed, or it ended, on its page
 * meanwhile — and nothing marks it.
 */
private fun alreadyMarkedAt(view: DoseView): Long? {
    val slot = view.slot?.removePrefix(YESTERDAY) ?: return null
    val course = view.medicine?.course?.let { at -> view.env.courses.firstOrNull { it.createdAt == at } }
        ?: return null
    val day = slotDay(view)
    val own = view.journal.firstOrNull { it.day == day.toString() && medSlotOf(it) == (course.id to slot) }
    val settled = plannedDoses(listOf(course), view.journal, day).firstOrNull { it.slot == slot }?.takenAtMs
    return own?.occurredAt ?: settled
}

/**
 * A missed dose: "do not take two doses to make up for it" for a medicine whose pages say so — the
 * antidepressants, codeine, naproxen, aspirin, tramadol — and the leaflet and a pharmacist for any
 * other; then that nothing is noted, naming the one dose that had gone by.
 */
private fun missedNotes(view: DoseView): List<BotPiece> {
    val medicine = view.medicine
    val twoDoses =
        medicine != null && (medicine.groups.any { it in TWO_DOSES_GROUPS } || medicine.inn in TWO_DOSES_INNS)
    val line = if (twoDoses) R.string.med_chat_missed else R.string.med_chat_missed_leaflet
    val gone = view.report.meta["MED_SLOT.missed"].orEmpty()
    val nothing = when {
        gone == MED_SLOT_WAKE -> ChatText(R.string.med_chat_nothing_missed_wake)
        gone.toIntOrNull() != null -> ChatText(R.string.med_chat_nothing_missed, listOf(PieceArg.Clock(gone.toInt())))
        else -> ChatText(R.string.med_chat_nothing_new)
    }
    return listOf(BotPiece.Note(ChatText(line), sourceRes = R.string.med_chat_source_missed), BotPiece.Note(nothing))
}

/** The groups and the medicines whose NHS pages say not to take two doses to make up for a missed one. */
private val TWO_DOSES_GROUPS = setOf(MedGroup.SSRI, MedGroup.SNRI, MedGroup.NASSA, MedGroup.SARI, MedGroup.TCA)
private val TWO_DOSES_INNS = setOf("codeine", "naproxen", "aspirin", "tramadol")

/**
 * The topics a calm dose thread offers to go on with, by what it was taken for: the pain, the
 * temperature, the mood and a talk about it, sleep — and an antidepressant noted on its own, to be
 * added to the person's medicines.
 */
internal fun doseFollowUps(state: ChatState): List<TopicChoice> {
    if (!notesShown(state)) return emptyList()
    val view = DoseView(state, MedicineClock.zone())
    fun offer(topic: Symptom, label: Int) = TopicChoice(topic, TopicMention(topic), label)
    val choices = when (view.answer(SymptomStep.MED_WHY)) {
        SymptomOption.FOR_PAIN -> listOf(offer(Symptom.PAIN, R.string.med_chat_offer_pain))
            .filter { state.thread.others.none { it.topic == Symptom.PAIN } }

        SymptomOption.FOR_TEMPERATURE -> listOf(offer(Symptom.FEVER, R.string.med_chat_offer_fever))

        SymptomOption.FOR_MOOD -> listOfNotNull(
            offer(Symptom.MOOD, R.string.symptom_topic_mood).takeIf { !state.pastDay },
            offer(Symptom.LOW_MOOD, R.string.symptom_chat_talk_about_it),
        )

        SymptomOption.FOR_SLEEP -> listOf(offer(Symptom.SLEEP_WORRY, R.string.med_chat_offer_sleep))

        else -> emptyList()
    }
    val add = addCourseName(view)?.let { name ->
        TopicChoice(
            Symptom.COURSE,
            TopicMention(Symptom.COURSE, normalisedWords(name).trim()),
            R.string.med_chat_offer_add_course,
        )
    }
    return choices + listOfNotNull(add)
}

/**
 * The name of an antidepressant noted on its own — or said missed there — while the person has no course of it: one to
 * add to their medicines.
 */
private fun addCourseName(view: DoseView): String? {
    val medicine = view.medicine ?: return null
    val onItsOwn = view.path == DosePath.ONE_OFF && !view.extra && !view.past
    if (!onItsOwn || !medicine.antidepressant || medicine.name.isEmpty()) return null
    return medicine.name.takeIf { view.env.courses.none { sameMedicine(it.name, medicine.name) } }
}

/** The person's answer to a value step in words. */
internal fun doseAnswerText(state: ChatState, step: SymptomStep): ChatText? {
    val view = DoseView(state, MedicineClock.zone())
    val value = view.values[step] ?: return null
    return when (step) {
        SymptomStep.MED_WHICH, SymptomStep.MED_IS_COURSE -> pickedText(view, step, value)

        SymptomStep.MED_NAME -> if (value ==
            MED_UNKNOWN
        ) {
            ChatText(R.string.med_chat_name_unknown)
        } else {
            medText(displayName(value))
        }

        SymptomStep.MED_SLOT -> slotText(value)

        SymptomStep.MED_WHEN, SymptomStep.MED_WHEN_AGAIN -> whenText(view, value)

        SymptomStep.MED_AMOUNT -> amountAnswer(view, value)

        else -> null
    }
}

/** A bubble of plain words. */
internal fun medText(text: String): ChatText = ChatText(R.string.med_chat_plain, listOf(PieceArg.Text(text)))

/** A time alone, in the 24 h or 12 h the phone shows. */
internal fun plainClock(minute: Int): ChatText = ChatText(R.string.med_chat_plain, listOf(PieceArg.Clock(minute)))

/**
 * The pick in words: the course's name and its dose, or its dose's time for the one-tap chip; a name the words gave;
 * another.
 */
private fun pickedText(view: DoseView, step: SymptomStep, value: String): ChatText = when {
    value == MED_OTHER -> ChatText(
        if (step ==
            SymptomStep.MED_WHICH
        ) {
            R.string.med_chat_pick_other
        } else {
            R.string.med_chat_option_different
        },
    )

    value.startsWith("$MED_OTHER:") -> medText(displayName(value.removePrefix("$MED_OTHER:")))

    else -> {
        val medicine = frozenMedicine(view.report, step) ?: pickedMedicine(view, value)
        val slot = value.substringAfter('@', "")
        val detail = when {
            slot == MED_SLOT_WAKE -> null
            slot.isNotEmpty() -> slot.toIntOrNull()?.let(::clockText)
            else -> medicine?.dose?.takeIf { it.isNotEmpty() }
        }
        medText(listOfNotNull(medicine?.name?.let(::displayName), detail).joinToString(" · "))
    }
}

private fun slotText(value: String): ChatText = when {
    value == SLOT_EXTRA -> ChatText(R.string.med_chat_slot_extra)

    value == SLOT_MISSED -> ChatText(R.string.med_chat_slot_missed)

    value == SLOT_NOTHING -> ChatText(R.string.med_chat_slot_nothing)

    value == MED_SLOT_WAKE -> ChatText(R.string.med_dose_on_waking)

    value.startsWith(YESTERDAY) -> {
        val minute = value.removePrefix(YESTERDAY).toIntOrNull()
        val time = minute?.let { PieceArg.Clock(it) } ?: PieceArg.Res(R.string.med_dose_on_waking)
        ChatText(R.string.med_chat_slot_yesterday, listOf(time))
    }

    else -> value.toIntOrNull()?.let(::plainClock) ?: medText(value)
}

private fun whenText(view: DoseView, value: String): ChatText = when (value) {
    WHEN_NOW -> ChatText(R.string.med_chat_when_now)

    WHEN_SLOT -> ChatText(R.string.med_chat_when_slot, listOf(PieceArg.Clock(slotMinute(view) ?: 0)))

    WHEN_UNKNOWN -> ChatText(R.string.med_chat_when_unknown)

    WHEN_MISSED -> ChatText(R.string.med_chat_slot_missed)

    else -> if (value.endsWith("@$WHEN_UNKNOWN")) {
        doseClockDay(value)?.let(::dayUnknownText) ?: medText(value)
    } else {
        doseClockMinute(value)?.let { dayClockText(doseClockDay(value), it) } ?: medText(value)
    }
}

private fun amountAnswer(view: DoseView, value: String): ChatText {
    val dose = view.medicine?.dose.orEmpty()
    return when (value) {
        AMOUNT_ONE -> ChatText(R.string.med_chat_amount_one_tablet)

        AMOUNT_TWO -> ChatText(R.string.med_chat_amount_two_tablets)

        AMOUNT_DOSE_ONE -> if (dose.isEmpty()) ChatText(R.string.med_chat_amount_dose_one_bare) else medText(dose)

        AMOUNT_DOSE_TWO -> if (dose.isEmpty()) {
            ChatText(R.string.med_chat_amount_dose_two)
        } else {
            ChatText(R.string.med_chat_amount_two_of, listOf(PieceArg.Text(dose)))
        }

        else -> medText(value)
    }
}

/**
 * The card's line, from the row as it is now: a dose of the schedule with its slot, or one outside it with its amount.
 */
internal fun doseSummary(resources: Resources, row: JournalEntry?, role: String): String {
    row ?: return ""
    if (role == DOSE_PAIN_ROLE) return PainScript.savedSummary(resources, row, null, "")
    val at = Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault())
    val unsaid = statesNoTime(row)
    // A time not said is no 12:00: the day alone.
    val stamp = if (unsaid) {
        painDayText("dMMMM", resources.configuration.locales[0], at.toLocalDate())
    } else {
        clockText(at.hour * 60 + at.minute)
    }
    val slot = medSlotOf(row)?.second?.takeUnless { isOneOffDose(row) }
    val line = doseLineRes(slot, unsaid)
    return when {
        slot == MED_SLOT_WAKE -> resources.getString(line, row.title, stamp)
        slot != null -> resources.getString(line, row.title, slot.toIntOrNull()?.let(::clockText).orEmpty(), stamp)
        else -> resources.getString(line, row.title, row.subtitle, stamp)
    }
}

/**
 * The card's line for a dose of [slot] — on waking, at a time of the schedule, or none, outside it — with
 * the time it was taken, or its day alone when that time was not said ([statesNoTime]).
 */
@StringRes
internal fun doseLineRes(slot: String?, unsaid: Boolean): Int = when {
    slot == MED_SLOT_WAKE -> {
        if (unsaid) R.string.med_chat_saved_scheduled_wake_day else R.string.med_chat_saved_scheduled_wake
    }

    slot != null -> if (unsaid) R.string.med_chat_saved_scheduled_day else R.string.med_chat_saved_scheduled

    else -> if (unsaid) R.string.med_chat_saved_one_off_day else R.string.med_chat_saved_one_off
}

/**
 * The flash of a dose of the schedule as the tab says it, "Marked at 09:00"; the core's for one outside
 * it; and "Saved" for the pain this chat noted, changed to say a painkiller was taken — nothing was added.
 */
internal fun doseFlash(resources: Resources, link: FiledLink, event: FlashEvent): String? {
    if (link.role == DOSE_PAIN_ROLE && event == FlashEvent.FILED) return resources.getString(R.string.work_flash_saved)
    if (event != FlashEvent.FILED || link.kind != KIND_MED ||
        link.slot?.startsWith(MED_COURSE_PREFIX) != true
    ) {
        return null
    }
    val at = Instant.ofEpochMilli(link.at).atZone(ZoneId.systemDefault())
    return resources.getString(R.string.med_dose_taken_at, clockText(at.hour * 60 + at.minute))
}

/** Whether [thread] already takes up [mention]: a dose of the same medicine, or of the class a word named. */
internal fun doseServes(thread: ChatThread, mention: TopicMention): Boolean {
    if (mention.topic != Symptom.DOSE) return false
    val medicine = threadMedicine(thread.report.forSave()) ?: return false
    return when (mention.detail) {
        "" -> true
        DETAIL_PAINKILLER -> medicine.painkiller
        DETAIL_ANTIDEPRESSANT -> medicine.antidepressant
        else -> sameMedicine(mention.detail, medicine.name)
    }
}

/**
 * The notes a recorded dose's close says ([MedNote]): what its guidance says about alcohol when the
 * day's journal or the chat speaks of drink; what it says of this medicine with the person's other
 * courses and with the other medicines noted in the 24 hours round it; and, for a painkiller of the
 * NSAID or opioid kind taken outside the schedule beside a course with nothing else to say, that a
 * pharmacist can check it goes with them.
 */
private fun dosesNotes(view: DoseView): List<MedNote> {
    val medicine = view.medicine ?: return emptyList()
    if (!recordReady(view)) return emptyList()
    val day = doseDay(view).toString()
    val drink =
        alcoholWordsIn(chatWords(view.state)) || view.env.dayJournal.any { it.kind == KIND_ALCOHOL && it.day == day }
    val moment = doseMoment(view, MedicineClock.now())
    val courses = view.pickable.filter { it.createdAt != medicine.course }
    val noted = view.journal.filter { it.kind == KIND_MED && kotlin.math.abs(it.occurredAt - moment) <= MED_DAY_MS }
    val mixes = (courses.map { namedCourse(it, view.day) } + noted.map { namedMedicine(it.title) })
        .mapNotNull { mixNote(medicine, it) }
    val strong =
        MedGroup.NSAID in medicine.groups || medicine.opioid || (medicine.painkiller && medicine.groups.isEmpty())
    val anyCourse = MedNote(NoteKind.MIX_ANY_COURSE, medicine.inn.orEmpty(), listOf(displayName(medicine.name)))
        .takeIf { view.path == DosePath.ONE_OFF && strong && courses.isNotEmpty() && mixes.isEmpty() }
    return freshNotes(
        listOfNotNull(
            alcoholNote(medicine)?.takeIf {
                drink
            },
        ) + mixes + listOfNotNull(anyCourse),
        view.state,
    )
}
