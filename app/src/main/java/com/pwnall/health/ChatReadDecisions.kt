package com.pwnall.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

// From a reading to the decisions the chat keeps with the message (the
// adapter in Kotlin): the flags over their thresholds, the topics worth a chip and worth adding, and
// what the spans and the pain heads tell, by the calibration's fixed rules and the chat's day.
// Everything the router does with them afterwards reads only these, never the model again.

/**
 * A label at even odds or better: what a mark asks for (a mark is cheap, a wrong one costs a
 * glance). Sides, numbers out of ten and times have rules of their own in the calibration.
 */
private const val MARK = 0.5

/** At most two kinds of pain, as the pain page takes. */
private const val TYPES_MAX = PAIN_TYPES_MAX

/** The span attributes and message heads the decisions read, by their names in reader-schema.json. */
private const val PLACE_FAMILY = "place_family"
private const val PLACE_AREA = "place_area"
private const val SIDE = "side"
private const val NEGATED = "negated"
private const val NUMBER_CLASS = "number_class"
private const val NRS = "nrs"
private const val MEDICINE_ROLE = "medicine_role"
private const val TIME_EXPR = "time_expr"
private const val TIME_COUNT = "time_count"
private const val WEEKDAY = "weekday"
private const val INTENSITY = "intensity"
private const val PAIN_TYPES = "pain_types"
private const val PAIN_NOTICED = "pain_noticed"
private const val PAIN_STATUS = "pain_status"
private const val PAIN_SAME = "pain_same"
private const val PAIN_SIGNS = "pain_signs"
private const val PAINKILLER = "painkiller"
private const val CLOCK = "CLOCK"
private const val NRS_CLASS = "NRS"
private const val MORE = "MORE"
private const val SELF = "SELF"

/**
 * The decisions [this] reading comes to for a message written in a chat of [day], for an earlier
 * day when [past]. The flags over their thresholds add their questions whatever else it
 * says — a reading that abstains keeps them and nothing else, and so does one of a message in
 * a language not enabled for routing ([ChatReading.examined]), including an uncertain language.
 * Complete beverage/cigarette statements recognised in [text] by [ordinaryConsumptionIn], and the
 * few complete conversational messages [conversationIn] knows — a greeting, "I am sad", a short
 * reply to a joke — instead reject the model's dose/crisis/overdose confusion and its topics, and
 * retain its other emergency flags. Words outside those bounded lists keep the entire usual
 * reading; the router still reads its rules.
 * An enabled reading that abstains is kept as such, which the chat's words about how it read the
 * message go by ([ChatRead.examined]). Its journal proposals are kept beside any of these, never in
 * the shadow: the reading holds them only in a language enabled for them ([ChatReading.journal]),
 * whether or not routing is.
 * The topics worth a chip come likeliest first, with a mood's kind as the mood and a bad one as the
 * low mood too; a chest pain among them, or a pain placed in the chest, asks the chest's
 * question as the rules' words of one do; unwell counts only of the person themselves, the
 * chest not asked about; whom it is about only when it is someone else; what a pain was told to be
 * only of a message about one. [shadow]: kept to compare the model with the rules, never routed by.
 */
internal fun ChatReading.decided(
    day: LocalDate,
    past: Boolean,
    shadow: Boolean = false,
    text: String? = null,
    context: ChatReaderContext = ChatReaderContext(),
): ChatRead = if (text != null && plainConversation(text, context)) {
    consumptionDecision(shadow)
} else {
    usualDecision(day, past, shadow)
}

private fun plainConversation(text: String, context: ChatReaderContext): Boolean {
    val recent = context.recentMessages.takeIf { context.topic == null }.orEmpty()
    return ordinaryConsumptionIn(text) || conversationIn(text, recent) != null
}

private fun ChatReading.consumptionDecision(shadow: Boolean): ChatRead = ChatRead(
    model,
    why = if (examined) ChatReadOutcome.ABSTAIN else ChatReadOutcome.UNEXAMINED,
    shadow = shadow,
    safety = raised.map { it.step }.filter { it != SymptomStep.CRISIS_NOW && it != SymptomStep.MED_MAX_WORDS },
    journal = journal.takeIf { !shadow }.orEmpty(),
)

private fun ChatReading.usualDecision(day: LocalDate, past: Boolean, shadow: Boolean): ChatRead {
    val raisedSteps = raised.map { it.step }
    if (abstain || !examined) {
        val why = if (examined) ChatReadOutcome.ABSTAIN else ChatReadOutcome.UNEXAMINED
        return ChatRead(
            model,
            why = why,
            shadow = shadow,
            safety = raisedSteps,
            journal = journal.takeIf { !shadow }.orEmpty(),
        )
    }
    val buttons = topics.filter { it.button }.sortedByDescending { it.p }.map { it.symptom }
    val proposed = (buttons + moodTopics()).distinct()
    val someone = about.takeIf { it.label != SELF && it.p >= MARK }
        ?.let { other -> ReadAbout.entries.firstOrNull { it.name == other.label } }
    val told = told(day, past)
    val pain = Symptom.PAIN in proposed || told.places.isNotEmpty()
    val steps = safetySteps(raisedSteps, proposed, told)
    return ChatRead(
        model = model,
        shadow = shadow,
        safety = steps,
        topics = proposed,
        add = topics.filter { it.add && it.symptom in proposed }.map { it.symptom },
        unwell = unwell.button && someone == null && SymptomStep.CHEST_NOW !in steps,
        about = someone,
        told = if (pain) told else told.withoutPain(),
        journal = journal.takeIf { !shadow }.orEmpty(),
    )
}

/**
 * The safety questions a reading adds, in the script's order: its flags', and the chest's for a chest
 * pain among the [proposed] topics or a pain [told] in the chest, as the rules' words of one ask it.
 */
private fun safetySteps(raised: List<SymptomStep>, proposed: List<Symptom>, told: ReadTold): List<SymptomStep> {
    val chest = Symptom.CHEST_PAIN in proposed ||
        (Symptom.PAIN in proposed && told.places.any { it.family == FAMILY_CHEST })
    return READ_SAFETY_STEPS.filter { it in raised || (chest && it == SymptomStep.CHEST_NOW) }
}

/** [this] without what is told of a pain: the pain heads read something into every message. */
private fun ReadTold.withoutPain(): ReadTold = copy(
    types = emptyList(),
    noticed = null,
    status = null,
    same = null,
    signs = emptyList(),
    intensity = emptyList(),
    nrs = null,
    painkiller = false,
)

/** The topics a mood's kind names: the mood, and for a bad one the low mood's talk too. */
private fun ChatReading.moodTopics(): List<Symptom> {
    val kind = mood?.takeIf { it.p >= MARK } ?: return emptyList()
    return if (kind.label == MoodKind.BAD.name) listOf(Symptom.MOOD, Symptom.LOW_MOOD) else listOf(Symptom.MOOD)
}

/**
 * What the spans and the pain heads tell: the places named, not negated, each with its side or, with
 * one place and a side said apart from it, that side; one time and one number out of ten, none when
 * two disagree; the pain's kinds, its moment, whether it is still there and the same, its marks;
 * the medicines named, not negated, with what was said of them; amounts and clock times as written.
 */
private fun ChatReading.told(day: LocalDate, past: Boolean): ReadTold {
    val kept = spans.filter { (it.attributes[NEGATED]?.values?.firstOrNull() ?: 0.0) < MARK }
    val loneSide = kept.filter { it.kind == ReaderSpanKind.SIDE }.mapNotNull { it.said(SIDE, rules.side) }.distinct()
    val placeSpans = kept.filter { it.kind == ReaderSpanKind.PLACE }
    val places = placeSpans.mapNotNull { span ->
        place(span, loneSide.singleOrNull()?.takeIf { placeSpans.size == 1 })
    }.distinct()
    val times = kept.filter { it.kind == ReaderSpanKind.TIME }
    val onsets = if (past) emptyList() else times.mapNotNull { onsetOf(it, day) }.distinct()
    val numbers = kept.filter { it.kind == ReaderSpanKind.NUMBER }
    val nrs = numbers.filter { it.said(NUMBER_CLASS, MARK) == NRS_CLASS }
        .mapNotNull { it.said(NRS, rules.nrs)?.toIntOrNull()?.takeIf { n -> n in PAIN_NRS_RANGE } }.distinct()
    val clockSpans = times.filter { it.said(TIME_EXPR, rules.onset) == CLOCK } +
        numbers.filter { it.said(NUMBER_CLASS, MARK) == CLOCK }
    return painTold().copy(
        places = places,
        onset = onsets.singleOrNull(),
        intensity = kept.filter { it.kind == ReaderSpanKind.INTENSITY }.flatMap { span ->
            ReadIntensity.entries.filter { (span.attributes[INTENSITY]?.get(it.name) ?: 0.0) >= MARK }
        }.distinct(),
        nrs = nrs.singleOrNull(),
        medicines = kept.filter { it.kind == ReaderSpanKind.MEDICINE && it.text.isNotBlank() }.map { span ->
            val roles = span.attributes[MEDICINE_ROLE].orEmpty()
            ReadMedicine(span.text.trim(), ReadRole.entries.filter { (roles[it.name] ?: 0.0) >= MARK })
        }.mergedByName(),
        amounts = kept.filter { it.kind == ReaderSpanKind.AMOUNT }.map { it.text.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() },
        clocks = clockSpans.flatMap { doseTimingIn(it.text, day, bare = true).minutes }.distinct(),
    )
}

/** The pain heads: kinds at even odds or better, two at most; the moment, the state, "the same?", the marks. */
private fun ChatReading.painTold(): ReadTold {
    fun likeliest(head: String): String? =
        heads[head]?.maxByOrNull { it.value }?.takeIf { it.value >= MARK && it.key != "NONE" }?.key
    fun over(head: String): List<String> =
        heads[head].orEmpty().filter { it.value >= MARK }.entries.sortedByDescending { it.value }.map { it.key }
    return ReadTold(
        types = over(PAIN_TYPES).mapNotNull { name -> PainType.entries.firstOrNull { it.name == name } }
            .take(TYPES_MAX),
        noticed = likeliest(PAIN_NOTICED)?.let(::optionNamed),
        status = likeliest(PAIN_STATUS)?.let(::optionNamed),
        same = likeliest(PAIN_SAME)?.let(::optionNamed),
        signs = over(PAIN_SIGNS).mapNotNull(::optionNamed),
        painkiller = (heads[PAINKILLER]?.values?.firstOrNull() ?: 0.0) >= MARK,
    )
}

private fun optionNamed(name: String): SymptomOption? = SymptomOption.entries.firstOrNull { it.name == name }

/**
 * A place span as a place: its area when the current map has one by the name the model gave, the
 * family of that area — or the family the model named, with no area — and its side, at the
 * calibration's certainty, or [apart], a side said beside the only place of the message.
 */
private fun ChatReading.place(span: ReaderSpan, apart: String?): ReadPlace? {
    val area = span.said(PLACE_AREA, MARK)?.takeIf { name -> CURRENT_AREAS.any { it.name == name } }
    val region = area?.let { name -> PainRegion.entries.first { it.name == name } }
    val family = region?.let { bodyFamilies(it).firstOrNull() } ?: span.said(PLACE_FAMILY, MARK)
    val side = (span.said(SIDE, rules.side) ?: apart)?.takeIf { it in READ_SIDES && region?.paired != false }
    return if (family == null && area == null) null else ReadPlace(family, area, side)
}

/** The areas a new pain is painted on: the current map's, its 44 areas. */
private val CURRENT_AREAS: Set<PainRegion> by lazy {
    painAreasOffered(PainMapVersion.CURRENT).mapTo(HashSet()) { it.region }
}

/**
 * When a pain started, as an onset chip counted from the chat's [day] (`onsetsIn`'s reckoning):
 * now, today, yesterday; a day back is yesterday, two or three the 2–3 days, five to ten about a
 * week; a week, three or four weeks a month, a month, then longer; a weekday counted back as the
 * rules count it; "a long time" longer. Anything else — a weekend, a clock time, four days, two
 * weeks — marks nothing, as the rules' words mark nothing there.
 */
private fun ChatReading.onsetOf(span: ReaderSpan, day: LocalDate): SymptomOption? {
    val expr = span.said(TIME_EXPR, rules.onset) ?: return null
    val count = span.said(TIME_COUNT, MARK)
    return when (expr) {
        "NOW" -> SymptomOption.STARTED_NOW
        "TODAY" -> SymptomOption.STARTED_TODAY
        "YESTERDAY" -> SymptomOption.STARTED_YESTERDAY
        "DAYS" -> daysOnset(count?.toIntOrNull())
        "WEEKS" -> if (count == MORE) SymptomOption.STARTED_LONGER else weeksOnset(count?.toIntOrNull())
        "MONTHS" -> monthsOnset(count)
        "WEEKDAY" -> span.said(WEEKDAY, MARK)?.let { weekdayOnset(it, day) }
        "LONG" -> SymptomOption.STARTED_LONGER
        else -> null
    }
}

private fun daysOnset(count: Int?): SymptomOption? = when (count) {
    1 -> SymptomOption.STARTED_YESTERDAY
    2, 3 -> SymptomOption.STARTED_2_3_DAYS
    in 5..10 -> SymptomOption.STARTED_A_WEEK
    else -> null
}

private fun weeksOnset(count: Int?): SymptomOption? = when (count) {
    1 -> SymptomOption.STARTED_A_WEEK
    3, 4 -> SymptomOption.STARTED_A_MONTH
    else -> null
}

/** A month is a month; two or more of them, or more than twelve, longer; months of no count, nothing. */
private fun monthsOnset(count: String?): SymptomOption? = when {
    count == "1" -> SymptomOption.STARTED_A_MONTH
    count == MORE || (count?.toIntOrNull() ?: 0) > 1 -> SymptomOption.STARTED_LONGER
    else -> null
}

/** A weekday ("since Monday") counted back from [day]: one day yesterday, two or three the 2–3 days, six a week. */
private fun weekdayOnset(name: String, day: LocalDate): SymptomOption? {
    val weekday = WEEKDAYS[name] ?: return null
    return when ((day.dayOfWeek.value - weekday.value + DAYS_A_WEEK) % DAYS_A_WEEK) {
        1 -> SymptomOption.STARTED_YESTERDAY
        2, 3 -> SymptomOption.STARTED_2_3_DAYS
        DAYS_A_WEEK - 1 -> SymptomOption.STARTED_A_WEEK
        else -> null
    }
}

private const val DAYS_A_WEEK = 7

private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
    "MON" to DayOfWeek.MONDAY,
    "TUE" to DayOfWeek.TUESDAY,
    "WED" to DayOfWeek.WEDNESDAY,
    "THU" to DayOfWeek.THURSDAY,
    "FRI" to DayOfWeek.FRIDAY,
    "SAT" to DayOfWeek.SATURDAY,
    "SUN" to DayOfWeek.SUNDAY,
)

/**
 * Why there was no reading, kept with the message: a model that ships failed to load or run —
 * never a slow phone, which keeps its model. With no model at all, its switch off, or a
 * smoke-test model — the chat behaving as it does without one — nothing is kept.
 */
private val KEPT_UNAVAILABLE = setOf(
    ChatModelUnavailable.MISMATCH,
    ChatModelUnavailable.LOAD_FAILED,
    ChatModelUnavailable.OUT_OF_MEMORY,
    ChatModelUnavailable.RUN_FAILED,
)

/**
 * [text], read once as it is sent, as the decisions the chat keeps with it: what the
 * reading came to, or why there was none in time — or null, nothing to keep, when the chat is as it is
 * without a model. [routes]: the chat routes by the readings, rather than keeping them in the shadow.
 * Off the caller's thread throughout: the decisions as the reading. The words are read trimmed, as the
 * chat keeps them ([ChatLog.wrote], [ChatLog.noted]): the spans a reading finds count from the kept
 * text's first character.
 */
internal suspend fun ChatModelReader.decide(
    text: String,
    context: ChatReaderContext,
    day: LocalDate,
    past: Boolean,
    routes: Boolean,
    limitMillis: Long = ChatModelReader.READ_LIMIT_MILLIS,
): ChatRead? {
    val kept = text.trim()
    val outcome = read(kept, context, limitMillis)
    return withContext(Dispatchers.Default) {
        when (outcome) {
            is ChatReadOutcome.Read -> outcome.reading.decided(
                day,
                past,
                shadow = !routes,
                text = kept,
                context = context,
            )

            ChatReadOutcome.TimedOut -> ChatRead(why = outcome.reason, shadow = !routes)

            is ChatReadOutcome.Unavailable ->
                ChatRead(why = outcome.reason, shadow = !routes).takeIf { outcome.why in KEPT_UNAVAILABLE }
        }
    }
}

/**
 * Where [this] chat stands for a message written now: the opening, between threads,
 * a question of the last thread — its topic's, or the topic question itself, which is the opening's
 * or the prompt's — or the note after the end.
 */
internal fun ChatLog.readerContext(setting: ChatSetting): ChatReaderContext =
    positionContext(setting).copy(recentMessages = recentMessages())

private fun ChatLog.positionContext(setting: ChatSetting): ChatReaderContext =
    when (val move = chatMove(this, setting)) {
        ChatMove.Opening -> ChatReaderContext(ChatReaderWhere.START)

        ChatMove.Prompt -> ChatReaderContext(ChatReaderWhere.BETWEEN)

        is ChatMove.Over -> ChatReaderContext(ChatReaderWhere.NOTE)

        is ChatMove.Ask -> if (move.step == SymptomStep.SYMPTOM) {
            ChatReaderContext(if (threads.size <= 1) ChatReaderWhere.START else ChatReaderWhere.BETWEEN)
        } else {
            ChatReaderContext(ChatReaderWhere.QUESTION, move.step, threads.lastOrNull()?.topic)
        }
    }

/** How the page has words it sends read by its model. */
internal enum class ReadPlan {
    /** Not at all: no model reads in this chat. */
    NONE,

    /** At once the turn, the reading after it, joining the chat when done: urgent words, the shadow. */
    AFTER,

    /** The turn waits for the reading, the dots standing, a second at most. */
    HOLD,
}

/**
 * How [text] sent where [log] stands is read: not at all with no [reader] there; after the turn for
 * words the rules find urgent ([urgentByRules]) — their turn never waits — and, for a message rather
 * than words [typed] as an answer, for one whose turn asks a question the thread's words bring
 * ([asksWordsNow]); for every message of a chat whose readings are kept in the shadow, which nothing
 * waits on since nothing uses them; else the turn waits for the reading.
 */
internal fun readPlan(
    text: String,
    log: ChatLog,
    setting: ChatSetting,
    reader: Boolean,
    routes: Boolean,
    typed: Boolean = false,
): ReadPlan {
    val open = log.threads.lastOrNull()?.takeIf { !it.ended }?.report
    return when {
        !reader -> ReadPlan.NONE
        !routes || urgentByRules(text, open, setting.env.courses) -> ReadPlan.AFTER
        !typed && asksWordsNow(text, log, setting) -> ReadPlan.AFTER
        else -> ReadPlan.HOLD
    }
}

/**
 * Whether the turn [log] comes to with the message [text] kept asks a question the words of its thread
 * bring — the safety one, an emergency's, the medicine's "more than it says?" — which never waits for a
 * model: read over the whole thread as the rules read it, so "больше не хочу" and then
 * "жить", "I took 10 tablets" and then "of paracetamol", and any word written while "is it very hard
 * to breathe right now?" is still to be answered, are met at once, the reading joining after.
 */
internal fun asksWordsNow(text: String, log: ChatLog, setting: ChatSetting): Boolean {
    val written = log.wrote(text).takeIf { it != log } ?: return false
    val settled = settledLog(written, setting)
    val move = chatMove(settled, setting) as? ChatMove.Ask ?: return false
    val state = settled.threadState(settled.threads.lastIndex, setting)
    return move.step in wordSteps(state.chat, state.report, setting.env.courses)
}

/**
 * Whether the rules find in [text], written in [report]'s thread, what never waits for a model:
 * words of suicide, of an emergency, or of too much of a medicine ([toldTooMuch]),
 * whose turn — where help is, the line to call, "more than it says?" — comes at once. The model reads
 * them all the same, after the turn, and what its reading adds joins it ([SentWords]).
 */
internal fun urgentByRules(
    text: String,
    report: SymptomReport? = null,
    courses: List<Medication> = emptyList(),
): Boolean = crisisIn(text) || emergencyIn(text).isNotEmpty() ||
    (report?.let { toldTooMuch(text, it, courses) } ?: overdoseIn(text, courses))
