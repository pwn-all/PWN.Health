package com.pwnall.health

import android.content.res.Resources
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The mood thread: the Mood page's own dial in conversation, filing one
// KIND_MOOD row through the page's own factory and its door's four hours. The face is only ever the
// one the person moved the dial to — never words, a low mood's weight or any other answer — and the
// dial rests where it starts, lighting nothing, until it is touched. A mood noted a moment ago is
// offered to be updated rather than noted twice; a write the door refuses because one landed in the
// meantime asks the same question after the dial, never silently. What the mood is about is asked in
// the words of the face it was given and kept in the row; the person's own words stay in the chat.
// A good mood "for no clear reason" is noted and never treated as a problem: the one check a good
// mood can bring — two questions on sleep and energy — comes only with words of needing little
// sleep, or of the other signs NHS "Bipolar disorder" names while a course the built-in list reads as
// an antidepressant is active.

/** The role of the record a mood thread changes when it updates a mood already noted; its card says so. */
internal const val MOOD_UPDATE_ROLE = "update"

/** The mood "update it?" is about, frozen when it is answered: its moment, its face and its tension. */
private const val UPDATE_FACTS = "MOOD_UPDATE."
private const val UPDATE_AT = UPDATE_FACTS + "at"
private const val UPDATE_SCORE = UPDATE_FACTS + "score"
private const val UPDATE_ENERGY = UPDATE_FACTS + "energy"

/**
 * The name, as the person keeps it, of the course read as an antidepressant when the sleep-and-energy
 * check was answered: what the high mood's line names, and what its rule reads (a rule reads the report only).
 */
private const val HIGH_COURSE = "MOOD_HIGH_ASK.ad"

/** The payload's own version: what it was about, and nothing else ever. */
private const val MOOD_PAYLOAD_SCHEMA = 1

/** The faces a mood is low at, and the one in the middle; above it, a good mood. */
private const val LOW_MAX = 2
private const val MIDDLE_SCORE = 3

/** How many of the other signs of a high mood make it "the signs" when needing little sleep is not one. */
private const val OTHER_SIGNS_NEEDED = 2

private enum class MoodBand { LOW, MIDDLE, GOOD }

private val Mood.band: MoodBand
    get() = when {
        score <= LOW_MAX -> MoodBand.LOW
        score == MIDDLE_SCORE -> MoodBand.MIDDLE
        else -> MoodBand.GOOD
    }

/** The face the dial was taken at, once it was: only ever the person's own. */
private fun SymptomReport.dialMood(): Mood? = Mood.fromScore(values[SymptomStep.MOOD_DIAL]?.toIntOrNull())

/** The Mood page's tension answers, in the page's order. */
private val ENERGY_ANSWERS: Map<SymptomOption, Energy> = mapOf(
    SymptomOption.ENERGY_WOUND_UP to Energy.WOUND_UP,
    SymptomOption.ENERGY_RESTLESS to Energy.RESTLESS,
    SymptomOption.ENERGY_STEADY to Energy.STEADY,
    SymptomOption.ENERGY_CALM to Energy.CALM,
    SymptomOption.ENERGY_SETTLED to Energy.SETTLED,
)

/** The tension answered, if one was: "Not sure" is none. */
private fun SymptomReport.energy(): Energy? = answers[SymptomStep.MOOD_ENERGY]?.firstOrNull()?.let(ENERGY_ANSWERS::get)

/** What a low mood may be about: NHS "Low mood, sadness and depression"'s own causes, and sleep. */
private val LOW_REASONS = listOf(
    SymptomOption.WORK_STUDY,
    SymptomOption.MONEY,
    SymptomOption.FAMILY,
    SymptomOption.HEALTH,
    SymptomOption.LONELINESS,
    SymptomOption.LOSS,
    SymptomOption.FUTURE,
    SymptomOption.POOR_SLEEP,
    SymptomOption.NO_CLEAR_REASON,
    SymptomOption.SOMETHING_ELSE,
)

private val MIDDLE_REASONS = listOf(
    SymptomOption.WORK_STUDY,
    SymptomOption.FAMILY,
    SymptomOption.HEALTH,
    SymptomOption.POOR_SLEEP,
    SymptomOption.PEOPLE,
    SymptomOption.NATURE_WEATHER,
    SymptomOption.NO_CLEAR_REASON,
    SymptomOption.SOMETHING_ELSE,
)

/** What lifts a good mood: the person's answer, never the chat's explanation. */
private val GOOD_REASONS = listOf(
    SymptomOption.PEOPLE,
    SymptomOption.REST,
    SymptomOption.MOVEMENT,
    SymptomOption.WENT_WELL,
    SymptomOption.NATURE_WEATHER,
    SymptomOption.GOOD_NEWS,
    SymptomOption.NO_CLEAR_REASON,
    SymptomOption.SOMETHING_ELSE,
)

private fun reasonsFor(mood: Mood?): List<SymptomOption> = when (mood?.band) {
    MoodBand.GOOD -> GOOD_REASONS
    MoodBand.MIDDLE -> MIDDLE_REASONS
    MoodBand.LOW, null -> LOW_REASONS
}

/** The other signs of a high mood; needing much less sleep counts by itself. */
private val OTHER_HIGH_SIGNS = setOf(SymptomOption.RACING_MIND, SymptomOption.UNUSUAL_RISKS, SymptomOption.TALKING_MORE)

private val MOOD_OPTIONS: Map<SymptomStep, List<SymptomOption>> = mapOf(
    SymptomStep.MOOD_UPDATE to listOf(SymptomOption.UPDATE_IT, SymptomOption.LEAVE_IT),
    SymptomStep.MOOD_ENERGY to ENERGY_ANSWERS.keys.toList() + SymptomOption.NOT_SURE,
    SymptomStep.MOOD_SWINGS to listOf(SymptomOption.YES, SymptomOption.NO, SymptomOption.NOT_SURE),
    SymptomStep.MOOD_HIGH_ASK to listOf(SymptomOption.ASK_ME, SymptomOption.NO_THANKS),
    SymptomStep.MOOD_HIGH_SIGNS to listOf(SymptomOption.LESS_SLEEP_NEED) + OTHER_HIGH_SIGNS + SymptomOption.NONE,
    SymptomStep.MOOD_HIGH_LONG to listOf(
        SymptomOption.TODAY,
        SymptomOption.FEW_DAYS,
        SymptomOption.ABOUT_A_WEEK,
        SymptomOption.TWO_WEEKS_PLUS,
    ),
    SymptomStep.MOOD_HIGH_LIFE to listOf(SymptomOption.YES, SymptomOption.NO),
)

/**
 * The dial is a value the clock takes three seconds after the last touch, as the page's own picker
 * is; the words of "something else" are typed, or "Not now" tapped, and taken at once.
 */
private val MOOD_STEP_KINDS: Map<SymptomStep, StepKind> = mapOf(
    SymptomStep.MOOD_DIAL to StepKind(StepHolds.VALUE, AnswerKind.SETTLE),
    SymptomStep.MOOD_ELSE to StepKind(StepHolds.VALUE, AnswerKind.TAP),
)

/** "Not now", the one chip above the composer when "something else" is asked. */
private val NOT_NOW_CHIP = ValueChip(SymptomOption.NOT_NOW.name, ChatText(SymptomOption.NOT_NOW.labelRes))

/** "Talk about how I feel": a low mood's talk after a low mood noted; it takes up a low mood the words named. */
private val TALK_FOLLOW_UP =
    TopicChoice(Symptom.LOW_MOOD, TopicMention(Symptom.LOW_MOOD), R.string.chat_mood_offer_talk)

/** "Note my mood" after a talk about a low mood, a worry or stress; it takes up a mood the words named. */
private val NOTE_FOLLOW_UP = TopicChoice(Symptom.MOOD, TopicMention(Symptom.MOOD), R.string.symptom_topic_mood)

/** "Talk about it", the second of the pair words of a bad mood bring. */
private val TALK_CHOICE =
    TopicChoice(Symptom.LOW_MOOD, TopicMention(Symptom.LOW_MOOD), R.string.symptom_chat_talk_about_it)

private val TALK_TOPICS = setOf(Symptom.LOW_MOOD, Symptom.WORRY, Symptom.STRESS)

/** What words of a bad mood name: the mood, and the low mood's talk. */
private val PAIR_TOPICS = setOf(Symptom.MOOD, Symptom.LOW_MOOD)

/** The body signs a tension is never put down to: with one of them ticked, no breathing exercise is offered. */
private val BODY_SIGNS = setOf(SymptomOption.BREATHLESS, SymptomOption.CHEST_PAIN, SymptomOption.RACING_HEART)

/** The topics of those signs: a thread of one, or words naming one, keep the breathing exercise back too. */
private val BODY_TOPICS = setOf(Symptom.BREATHLESSNESS, Symptom.PALPITATIONS, Symptom.CHEST_PAIN)

/** The questions words of the chest or of not breathing bring: asked anywhere in the chat, they keep it back. */
private val BODY_NOW_STEPS = setOf(SymptomStep.CHEST_NOW, SymptomStep.BREATH_NOW)

/** The mood topic's script ([Symptom.MOOD]). */
internal object MoodScript : TopicScript {
    override fun steps(state: ChatState): List<SymptomStep> = moodSteps(state)

    /** Each step's answers; the reason by the face given; a question the person's words brought is the chat's own. */
    override fun options(state: ChatState, step: SymptomStep): List<SymptomOption> =
        if (step == SymptomStep.MOOD_ABOUT) {
            reasonsFor(state.report.dialMood())
        } else {
            MOOD_OPTIONS[step] ?: SymptomScript.options(state, step)
        }

    /**
     * The dial, resting on the face of the mood being updated or else on the middle one; "something
     * else" in the person's words; the reason with what a low mood talk in this chat said marked; chips.
     */
    override fun reply(state: ChatState, step: SymptomStep): ChatReply = when (step) {
        SymptomStep.MOOD_DIAL -> ChatReply.MoodDial(step, dialStart(state))

        SymptomStep.MOOD_ELSE ->
            ChatReply.TextAsk(step, R.string.symptom_chat_hint, SYMPTOM_TEXT_MAX, listOf(NOT_NOW_CHIP))

        SymptomStep.MOOD_ABOUT -> options(state, step).let { options ->
            ChatReply.Choose(step, options, chatMarked = lowMoodReasonMarks(state, options))
        }

        else -> ChatReply.Choose(step, options(state, step))
    }

    override fun question(state: ChatState, step: SymptomStep): Int =
        moodQuestion(state, step) ?: SymptomScript.question(state, step)

    /** "A mood is noted at 09:00: Low…", or after a refused write "A mood was noted at 09:00, a moment ago…". */
    override fun questionText(state: ChatState, step: SymptomStep): ChatText? =
        if (step == SymptomStep.MOOD_UPDATE) updateQuestion(state) else null

    override fun help(state: ChatState, step: SymptomStep): ChatText? =
        if (step == SymptomStep.MOOD_DIAL) dialHelp(state) else null

    /** No start line: the dial's own question is the first thing said. */
    override fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> =
        last?.let { moodLead(state, it, step) }.orEmpty()

    override fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece> = moodClose(state, alert)

    /** The card stands right after the dial, so a screen reader hears it with the next question. */
    override fun savedAfter(state: ChatState, step: SymptomStep): List<String> = moodSavedAfter(state, step)

    /**
     * "Mood · Low · Wound up · 14:20", and "· updated" for a mood this thread updated — never for an
     * update undone, which put the earlier mood back, nor for one refused.
     */
    override fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState,
    ): String = moodSummary(resources, row, role.takeIf { state !in UPDATE_NOT_SAVED } ?: "", ZoneId.systemDefault())

    /**
     * A mood the door refused, the person then keeping the one in its way ("Leave it as it is"): the
     * card says, as the close does, that this one is not saved and which mood stays, with its time.
     */
    override fun stateLine(thread: ChatThread, link: FiledLink): ChatText? = keptStateLine(thread, link)

    /** What it was about, from the row as it stands. */
    override fun savedDetail(resources: Resources, row: JournalEntry?, course: Medication?, role: String): String? =
        decodeMoodAbout(row?.measurementPayloadJson)
            .joinToString(resources.getString(R.string.list_comma)) { resources.getString(it.labelRes) }
            .ifEmpty { null }

    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> = moodWants(thread, ctx)

    override fun recordSig(thread: ChatThread, role: String): Int = moodSig(thread, role)

    /**
     * The mood "update it?" was about; the course a high mood's line names. With every answer from
     * "update it?" on, that mood is kept for the thread too ([threadCopy]): the question is drawn again
     * from it in the history, where it reads the chat as it stood before its own answer.
     */
    override fun freeze(state: ChatState, step: SymptomStep): Map<String, String> {
        val own = when (step) {
            SymptomStep.MOOD_UPDATE -> moodUpdateTarget(state)?.facts().orEmpty()
            SymptomStep.MOOD_HIGH_ASK -> antidepressantOf(state)?.let { mapOf(HIGH_COURSE to it) }.orEmpty()
            else -> emptyMap()
        }
        return own + threadCopy(state.report.meta + own)
    }

    override val stepKinds: Map<SymptomStep, StepKind> get() = MOOD_STEP_KINDS

    /** After a low mood noted: the talk, unless one ran in this chat. */
    override fun followUps(state: ChatState): List<TopicChoice> =
        listOfNotNull(TALK_FOLLOW_UP.takeIf { talkOffered(state) })

    override fun answerText(state: ChatState, step: SymptomStep): ChatText? = when (step) {
        SymptomStep.MOOD_DIAL -> state.report.dialMood()?.let { ChatText(it.titleRes) }
        SymptomStep.MOOD_ELSE -> state.report.values[step]?.let(::elseWords)
        else -> null
    }

    override fun title(resources: Resources, thread: ChatThread): String = resources.getString(R.string.mood_title)

    /** A mood thread takes up every mood the words named: noted once, it is not offered again from them. */
    override fun serves(thread: ChatThread, mention: TopicMention): Boolean = mention.topic == Symptom.MOOD

    override fun tagFacts(thread: ChatThread): TagFacts = thread.report.forSave().let { report ->
        TagFacts(score = report.dialMood()?.score, course = report.answers[SymptomStep.MOOD_HIGH_LONG]?.firstOrNull())
    }

    /**
     * Words of a bad mood and nothing else name the mood and the low mood's talk at once: one line and
     * the two as actions, "Note my mood" and "Talk about it". Anything else named
     * beside them — a fever, a pain — is the router's to offer, its own questions first. A
     * mood an emoji alone named is a proposal, never a start: no word was read, so the line to call
     * for an emergency stays beside the chip. Only where the mood is offered.
     */
    override fun firstReply(mention: WordMention, words: WordsFound, setting: ChatSetting): FirstReply? {
        if (!offered(setting)) return null
        val topics = words.mentions.map { it.mention.topic }.filter { it.script.offered(setting) }
        return when {
            topics.all { it in PAIR_TOPICS } && Symptom.LOW_MOOD in topics -> FirstReply(
                listOf(BotPiece.Note(ChatText(R.string.chat_mood_both))),
                ChatReply.Topics(
                    listOf(TopicChoice(Symptom.MOOD, mention.mention, R.string.symptom_topic_mood), TALK_CHOICE),
                    all = false,
                    exit = true,
                ),
            )

            !words.language && topics.all { it == Symptom.MOOD } -> FirstReply(
                listOf(BotPiece.Line(ChatLine.MATCH)),
                ChatReply.Topics(listOf(TopicChoice(Symptom.MOOD, mention.mention)), all = false, exit = true),
            )

            else -> null
        }
    }

    override fun notOfferedLine(setting: ChatSetting): ChatText? =
        ChatText(R.string.chat_mood_today_only).takeIf { setting.past }

    /** The dial and "update it?" carry the record; the high mood's two questions carry its red flags. */
    override val owedSteps: Set<SymptomStep> = setOf(
        SymptomStep.MOOD_DIAL,
        SymptomStep.MOOD_UPDATE,
        SymptomStep.MOOD_HIGH_ASK,
        SymptomStep.MOOD_HIGH_SIGNS,
    )

    /** "Mood · added" for a new mood, "Saved" for one updated; "Put back as it was" when an update is undone. */
    override fun flashText(resources: Resources, link: FiledLink, event: FlashEvent): String = when {
        event == FlashEvent.UNDONE && !link.made -> resources.getString(R.string.chat_mood_restored)
        event == FlashEvent.UNDONE -> resources.getString(R.string.work_flash_deleted)
        event == FlashEvent.REWRITTEN || !link.made -> resources.getString(R.string.work_flash_saved)
        else -> resources.getString(R.string.quick_log_added_named, resources.getString(R.string.mood_title))
    }

    /** The high mood's line on an antidepressant names the person's own medicine, never its class. */
    override fun alertText(state: ChatState, alert: SymptomAlert): ChatText? =
        state.report.meta[HIGH_COURSE]?.takeIf { alert == SymptomAlert.HIGH_ON_ANTIDEPRESSANT }
            ?.let { ChatText(alert.textRes, listOf(PieceArg.Text(it))) }

    /** The face given, drawn small in the person's bubble ([MoodAnswerBubble]). */
    override val bubbleSteps: Set<SymptomStep> = setOf(SymptomStep.MOOD_DIAL)

    /** Only for today, and never after an answer about suicide other than "no". */
    override fun offered(setting: ChatSetting): Boolean = !setting.past && !setting.crisisInChat

    /** A second mood thread updates the first one's reading. */
    override val oncePerChat: Boolean = false

    override val flashAt: FlashAt = FlashAt.FILE
}

/** "Not now", or the person's words as they typed them. */
private fun elseWords(value: String): ChatText = if (value == SymptomOption.NOT_NOW.name) {
    ChatText(SymptomOption.NOT_NOW.labelRes)
} else {
    ChatText(R.string.chat_mood_words, listOf(PieceArg.Text(value)))
}

/**
 * The mood thread's questions after its topic, in order. "Update it?" comes first when a mood
 * of the last four hours that is not this thread's stands, and after the dial when the door refused
 * its write because one landed meanwhile; "Leave it as it is" before the dial asks nothing more. The
 * tension is asked where the chat already holds some, or where the mood being updated had an answer;
 * "something else" in the person's words; the changes, and the check on sleep and energy, only where
 * the words call for them. Words written later can bring a question late, asked next. After an answer
 * about suicide other than "no" nothing more is asked: the chat's own answer to it comes next.
 */
internal fun moodSteps(state: ChatState): List<SymptomStep> {
    val report = state.report
    val asked = state.chat.turns.filterIsInstance<ChatTurn.Asked>().map { it.step }
    val first = updateComesFirst(state, asked)
    val left = !report.holdsAnswer(SymptomStep.MOOD_DIAL) && report.has(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
    return when {
        crisisAnswered(report) -> emptyList()

        left -> listOf(SymptomStep.MOOD_UPDATE)

        else -> listOfNotNull(
            SymptomStep.MOOD_UPDATE.takeIf { first },
            SymptomStep.MOOD_DIAL,
            SymptomStep.MOOD_UPDATE.takeIf { !first && (SymptomStep.MOOD_UPDATE in asked || refusedAtTheDial(state)) },
        ) + readingSteps(state, asked)
    }
}

/**
 * Whether "update it?" stands before the dial: asked there already, or due there — a mood of the last
 * four hours to update and no face given yet.
 */
private fun updateComesFirst(state: ChatState, asked: List<SymptomStep>): Boolean {
    val update = asked.indexOf(SymptomStep.MOOD_UPDATE)
    val dial = asked.indexOf(SymptomStep.MOOD_DIAL)
    val askedFirst = update >= 0 && (dial < 0 || update < dial)
    return askedFirst || (!state.report.holdsAnswer(SymptomStep.MOOD_DIAL) && moodUpdateTarget(state) != null)
}

/** The questions after the dial, each where it is called for — and once asked, asked. */
private fun readingSteps(state: ChatState, asked: List<SymptomStep>): List<SymptomStep> {
    val report = state.report
    val signs = report.answers[SymptomStep.MOOD_HIGH_SIGNS].orEmpty().any { it != SymptomOption.NONE }
    return listOfNotNull(
        SymptomStep.MOOD_ENERGY.takeIf { energyCalledFor(state) },
        SymptomStep.MOOD_ABOUT,
        SymptomStep.MOOD_ELSE.takeIf { report.has(SymptomStep.MOOD_ABOUT, SymptomOption.SOMETHING_ELSE) },
        SymptomStep.MOOD_SWINGS.takeIf { moodChangesIn(threadWords(state)) },
        SymptomStep.MOOD_HIGH_ASK.takeIf { SymptomStep.MOOD_HIGH_ASK in asked || highCheckCalledFor(state) },
        SymptomStep.MOOD_HIGH_SIGNS.takeIf { report.has(SymptomStep.MOOD_HIGH_ASK, SymptomOption.ASK_ME) },
        SymptomStep.MOOD_HIGH_LONG.takeIf { signs },
        SymptomStep.MOOD_HIGH_LIFE.takeIf { signs },
    )
}

/** A mood a thread may update: its moment, its face and its tension. */
private data class MoodTarget(val at: Long, val score: Int?, val energy: Int?) {
    fun facts(): Map<String, String> = listOfNotNull(
        UPDATE_AT to at.toString(),
        score?.let { UPDATE_SCORE to it.toString() },
        energy?.let { UPDATE_ENERGY to it.toString() },
    ).toMap()
}

/**
 * The mood "update it?" is about: the one frozen when it was answered; the row this thread's update
 * changed, as its link and what puts it back say (the question read again later, before its answer);
 * else the newest mood of the last four hours ([ChatEnv.lastMood]) — of an earlier thread of this chat
 * too — while this thread has noted none of its own and it is not this thread's. A write the door
 * refused is no mood of the thread's.
 */
private fun moodUpdateTarget(state: ChatState): MoodTarget? =
    frozenTarget(state.report.meta) ?: updatedTarget(state) ?: recentTarget(state)

/** The mood frozen at "update it?" — or, with [prefix], the thread's copy of it ([threadCopy]). */
private fun frozenTarget(meta: Map<String, String>, prefix: String = ""): MoodTarget? =
    meta[prefix + UPDATE_AT]?.toLongOrNull()?.let { at ->
        MoodTarget(at, meta[prefix + UPDATE_SCORE]?.toIntOrNull(), meta[prefix + UPDATE_ENERGY]?.toIntOrNull())
    }

/**
 * The facts "update it?" froze, kept under the thread's own prefix: the step's facts are read only
 * once that step was taken, so its question drawn again in the history — from the chat as it stood
 * before the answer — would otherwise read the moods there are now. Any pencil drops the copy, and
 * the next answer makes it again.
 */
private fun threadCopy(meta: Map<String, String>): Map<String, String> =
    meta.filterKeys { it.startsWith(UPDATE_FACTS) }.mapKeys { (key, _) -> THREAD_META_PREFIX + key }

private fun updatedTarget(state: ChatState): MoodTarget? =
    state.thread.links.firstOrNull { it.kind == KIND_MOOD && it.role == MOOD_UPDATE_ROLE }?.let { link ->
        val prior = link.prior?.let { runCatching { JSONObject(it) }.getOrNull() }
        MoodTarget(link.at, (prior?.opt("mood") as? Number)?.toInt(), (prior?.opt("energy") as? Number)?.toInt())
    }

/**
 * The newest mood of the four hours before the chat's moment. One noted after that moment — the
 * widget tapped while the dial is open — is the door's to find when the dial is taken, which then asks
 * about it after the dial; it never takes the dial away before it.
 */
private fun recentTarget(state: ChatState): MoodTarget? {
    val last = state.thread.env.lastMood ?: return null
    val now = state.thread.now
    val inside = now == null || (last.occurredAt <= now && moodCooldownLeftMs(last.occurredAt, now) > 0L)
    val own = state.thread.links.any {
        it.kind == KIND_MOOD && (it.state != LinkState.REFUSED || it.at == last.occurredAt)
    }
    return MoodTarget(last.occurredAt, last.moodScore, last.energyScore).takeIf { inside && !own }
}

/** Whether the door refused this thread's mood: one landed inside the four hours while the dial was open. */
private fun refusedAtTheDial(state: ChatState): Boolean =
    state.thread.links.any { it.kind == KIND_MOOD && it.role.isEmpty() && it.state == LinkState.REFUSED }

/** The mood "Update it" updates: the one frozen when it was answered, if any was. */
private fun updatingTarget(report: SymptomReport): MoodTarget? =
    frozenTarget(report.meta).takeIf { report.has(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT) }

/** Where the dial rests: the face of the mood being updated, else none — the middle one. */
private fun dialStart(state: ChatState): Int? = updatingTarget(state.report)?.score

/**
 * The role a thread's mood is filed under: [MOOD_UPDATE_ROLE] once "Update it" was answered with a
 * mood to update — unless that mood was not there any more at the first write ([LinkState.REFUSED]),
 * or the thread's own mood already stands in its place, which it then keeps — else the main role,
 * a mood of its own. So a face given is never lost to a mood deleted meanwhile.
 */
private fun moodRole(report: SymptomReport, links: List<FiledLink>): String {
    val gone = links.any { it.role == MOOD_UPDATE_ROLE && it.state == LinkState.REFUSED }
    val own = links.any { it.role.isEmpty() && it.state != LinkState.REFUSED }
    return if (updatingTarget(report) != null && !gone && !own) MOOD_UPDATE_ROLE else ""
}

/** The thread's own words, newest first, with the message that named its mood (the core's [threadTexts]). */
private fun threadWords(state: ChatState): String = threadTexts(state).joinToString("\n")

/** Every word of the chat so far: the threads before this one, and this thread's own. */
private fun moodChatWords(state: ChatState): String =
    (state.thread.others.map { it.report.chat?.words().orEmpty() } + threadTexts(state)).joinToString("\n")

/**
 * The tension is asked where the chat already holds some — words of worry or stress, or a worry or
 * stress thread — and where the mood being updated had an answer, so an update never keeps a stale one.
 */
private fun energyCalledFor(state: ChatState): Boolean {
    val tense = setOf(Symptom.WORRY, Symptom.STRESS)
    val words = symptomTopicsIn(moodChatWords(state)).any { it in tense }
    val threads = state.thread.others.any { it.topic in tense }
    val update = updatingTarget(state.report)?.energy != null
    return words || threads || update
}

/**
 * The check on sleep and energy, for a good mood only and never once declined in this chat: words of
 * needing little sleep bring it for anyone (a combination, never a good mood alone);
 * the other signs' words only with an active course read as an antidepressant. The words
 * are the chat's — written before this thread too, as a mood chosen from the menu after them.
 */
private fun highCheckCalledFor(state: ChatState): Boolean {
    val declined = state.thread.others.any {
        it.topic == Symptom.MOOD && it.report.has(SymptomStep.MOOD_HIGH_ASK, SymptomOption.NO_THANKS)
    }
    val words = moodChatWords(state)
    val called = sleepNeedIn(words) || (highSignIn(words) && antidepressantOf(state) != null)
    return state.report.goodMood() && !declined && called
}

/**
 * The first course by name, active on the day the chat is about, that the built-in list reads as an
 * antidepressant: an earlier day's, or today in one being had now — gone on with past midnight, its own
 * day is yesterday.
 */
private fun antidepressantOf(state: ChatState): String? {
    val day = (if (state.pastDay) state.thread.day else state.thread.today) ?: return null
    return state.thread.env.courses
        .filter { it.activeOn(day) && medicineClassOf(it.name) == MedicineClass.ANTIDEPRESSANT }
        .minByOrNull { it.name }
        ?.name
}

/** Each mood step's question; what it is about, by the face given. */
private val MOOD_QUESTIONS: Map<SymptomStep, Int> = mapOf(
    SymptomStep.MOOD_UPDATE to R.string.chat_mood_step_update,
    SymptomStep.MOOD_DIAL to R.string.mood_question,
    SymptomStep.MOOD_ENERGY to R.string.energy_title,
    SymptomStep.MOOD_ELSE to R.string.chat_mood_ask_else,
    SymptomStep.MOOD_SWINGS to R.string.chat_mood_ask_swings,
    SymptomStep.MOOD_HIGH_ASK to R.string.chat_mood_ask_high,
    SymptomStep.MOOD_HIGH_SIGNS to R.string.chat_mood_ask_high_signs,
    SymptomStep.MOOD_HIGH_LONG to R.string.symptom_ask_how_long,
    SymptomStep.MOOD_HIGH_LIFE to R.string.chat_mood_ask_life,
)

private fun moodQuestion(state: ChatState, step: SymptomStep): Int? = if (step == SymptomStep.MOOD_ABOUT) {
    when (state.report.dialMood()?.band) {
        MoodBand.GOOD -> R.string.chat_mood_ask_about_good
        MoodBand.MIDDLE -> R.string.chat_mood_ask_about_neutral
        MoodBand.LOW, null -> R.string.symptom_ask_about
    }
} else {
    MOOD_QUESTIONS[step]
}

/**
 * "Update it?" with its time and face; after a refused write, with its time and "a moment ago". Drawn
 * again in the history, it names the mood it named then — the thread's copy of what its answer froze —
 * never one noted since.
 */
private fun updateQuestion(state: ChatState): ChatText? {
    val meta = state.report.meta
    val target = frozenTarget(meta) ?: frozenTarget(meta, THREAD_META_PREFIX) ?: moodUpdateTarget(state) ?: return null
    val face = Mood.fromScore(target.score)
    return when {
        state.report.holdsAnswer(SymptomStep.MOOD_DIAL) ->
            ChatText(R.string.chat_mood_ask_clash, listOf(PieceArg.Moment(target.at)))

        face != null ->
            ChatText(R.string.chat_mood_ask_update, listOf(PieceArg.Moment(target.at), PieceArg.Res(face.titleRes)))

        else -> null
    }
}

/**
 * The dial's "?": after "Update it", that it rests on the mood as it was noted; with words of a
 * changing mood, that this one is how it is now and when the next can be noted.
 */
private fun dialHelp(state: ChatState): ChatText? = when {
    updatingTarget(state.report) != null -> ChatText(R.string.chat_mood_help_update)

    moodChangesIn(threadWords(state)) -> nextMoodAt(state)?.let {
        ChatText(R.string.chat_mood_help_changes, listOf(PieceArg.Moment(it)))
    }

    else -> null
}

/** When the next mood can be noted: four hours after this one — after its moment once it is noted. */
private fun nextMoodAt(state: ChatState): Long? {
    val noted = state.thread.links.firstOrNull { it.kind == KIND_MOOD && it.made && it.state == LinkState.FILED }?.at
    return (noted ?: state.thread.now)?.plus(MOOD_COOLDOWN_MS)
}

/** Between an answer and the next question: its acknowledgement, the card after the dial, and the words' check. */
private fun moodLead(state: ChatState, last: ChatTurn.Asked, step: SymptomStep): List<BotPiece> =
    afterAnswer(state, last) +
        moodSavedAfter(state, last.step).map { BotPiece.Saved(state.thread.id, it) } +
        listOfNotNull(BotPiece.Line(ChatLine.WORDS_CHECK).takeIf { askedForWords(state, step) })

/**
 * What an answer is met with: after the dial a thanks for a low mood, "good to hear" for a good one,
 * nothing for the middle; after "no clear reason" that a mood needs none; after "No, thank you" a
 * plain "All right". Never anything after a safety line, which is the lead itself.
 */
private fun afterAnswer(state: ChatState, last: ChatTurn.Asked): List<BotPiece> {
    if (last.alert != null) return emptyList()
    val report = state.report
    val band = report.dialMood()?.band
    val line = when (last.step) {
        SymptomStep.MOOD_DIAL -> when (band) {
            MoodBand.LOW -> BotPiece.Line(ChatLine.ACK_MIND)
            MoodBand.GOOD -> BotPiece.Note(ChatText(R.string.chat_mood_ack_good))
            MoodBand.MIDDLE, null -> null
        }

        SymptomStep.MOOD_ABOUT -> when {
            !report.has(SymptomStep.MOOD_ABOUT, SymptomOption.NO_CLEAR_REASON) -> null
            band == MoodBand.LOW -> BotPiece.Note(ChatText(R.string.chat_mood_no_reason_low))
            band == MoodBand.GOOD -> BotPiece.Note(ChatText(R.string.chat_mood_no_reason_good))
            else -> null
        }

        SymptomStep.MOOD_HIGH_ASK ->
            BotPiece.Line(ChatLine.ACK_3).takeIf { report.has(SymptomStep.MOOD_HIGH_ASK, SymptomOption.NO_THANKS) }

        else -> null
    }
    return listOfNotNull(line)
}

/**
 * The record whose card stands after [step]: the mood the dial noted — or the one it updated — and,
 * after "Update it" answered once the dial was taken, the one it updated. A mood of its own that
 * stands in place of one found gone keeps its card after the dial.
 */
private fun moodSavedAfter(state: ChatState, step: SymptomStep): List<String> {
    val role = moodRole(state.report, state.thread.links)
    val update = state.report.has(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
    return when {
        step == SymptomStep.MOOD_DIAL -> listOf(role)

        step == SymptomStep.MOOD_UPDATE && update && state.report.holdsAnswer(SymptomStep.MOOD_DIAL) ->
            listOf(role).filter { it == MOOD_UPDATE_ROLE }

        else -> emptyList()
    }
}

/**
 * Whether an answer about suicide other than "no" was given in this thread or one before it — never
 * one after it, so a close drawn again once a later thread had one says what it said.
 */
private fun crisisSoFar(state: ChatState): Boolean =
    crisisAnswered(state.report) || state.thread.others.any { crisisAnswered(it.report) }

/**
 * The thread's close: the last answer's line; after an emergency what to do now; after an
 * answer about suicide other than "no" nothing more, as the card already stood and the chat's own
 * answer to it follows; else the last answer's acknowledgement and the close of how the mood ended.
 * After a line to get advice now — too much of a medicine told in this thread — either ends on not
 * waiting with it, as every thread's close does.
 */
private fun moodClose(state: ChatState, alert: List<BotPiece>): List<BotPiece> {
    val report = state.report
    val urgency = chatUrgency(report)
    val crisis = crisisSoFar(state)
    val last = state.chat.turns.lastOrNull() as? ChatTurn.Asked
    val now = BotPiece.Line(ChatLine.CLOSE_NOW).takeIf { nowLineFired(report) }
    return when {
        urgency == SymptomUrgency.EMERGENCY ->
            alert + closeLines(urgency, crisis, report.symptom).map { BotPiece.Line(it) }

        crisis -> alert + listOfNotNull(now)

        else -> {
            val outcome = outcomeLines(state)
            val acknowledged = last?.let { afterAnswer(state, it) }.orEmpty()
            alert + acknowledged + outcome + listOfNotNull(now?.takeIf { it !in outcome })
        }
    }
}

/**
 * "It stays as it is"; "this one is not saved", and then what the face given is met with all the
 * same — it is the person's mood, saved or not; or the close of a mood noted.
 */
private fun outcomeLines(state: ChatState): List<BotPiece> {
    val report = state.report
    val mood = report.dialMood()
    val target = moodUpdateTarget(state)
    val left = report.has(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
    return when {
        mood == null -> listOfNotNull(
            target?.takeIf { left }?.let { momentNote(R.string.chat_mood_kept, it.at + MOOD_COOLDOWN_MS) },
        )

        left -> listOfNotNull(target?.let { momentNote(R.string.chat_mood_not_saved, it.at) }) +
            readingLines(state, mood, saved = false)

        else -> readingLines(state, mood, saved = !refused(state))
    }
}

/** Whether the door refused the mood this thread files: a mood of its own, standing in place of one found gone. */
private fun refused(state: ChatState): Boolean {
    val role = moodRole(state.report, state.thread.links)
    return state.thread.links.any { it.kind == KIND_MOOD && it.role == role && it.state == LinkState.REFUSED }
}

private fun momentNote(res: Int, at: Long) = BotPiece.Note(ChatText(res, listOf(PieceArg.Moment(at))))

/**
 * A mood given: a doctor today or soon where its lines said so, with a visit to plan — but after a
 * line to get advice now only not to wait with it; else by its face — for a low one the low mood
 * page's line, the talk and, with tension and no body sign, the breathing exercise; for the middle
 * one a thanks; for a good one a warm close and nothing about problems, or a plain thanks after a line
 * about its sleep and energy or its changes. A mood not [saved] is never thanked as noted.
 */
private fun readingLines(state: ChatState, mood: Mood, saved: Boolean): List<BotPiece> {
    val thread = state.thread.id
    val breathing = BotPiece.Offer(ChatOffer.BREATHING, thread).takeIf { tense(state) }
    val noted = BotPiece.Note(ChatText(R.string.chat_mood_close_neutral)).takeIf { saved }
    return when (chatUrgency(state.report)) {
        // Said in the close of its own: what to do now ([moodClose]).
        SymptomUrgency.EMERGENCY -> emptyList()

        SymptomUrgency.SAME_DAY -> if (nowLineFired(state.report)) {
            listOf(BotPiece.Line(ChatLine.CLOSE_NOW))
        } else {
            listOf(BotPiece.Line(ChatLine.CLOSE_TODAY), BotPiece.Offer(ChatOffer.BOOK_VISIT, thread))
        }

        SymptomUrgency.GP -> listOf(BotPiece.Line(ChatLine.CLOSE_GP), BotPiece.Offer(ChatOffer.BOOK_VISIT, thread))

        SymptomUrgency.SELF_CARE -> when (mood.band) {
            MoodBand.LOW -> listOfNotNull(
                BotPiece.Line(ChatLine.CLOSE_MIND, R.string.symptom_source_low_mood),
                BotPiece.Note(ChatText(R.string.chat_mood_talk_lead)).takeIf { talkOffered(state) },
                breathing,
            )

            MoodBand.MIDDLE -> listOfNotNull(noted, breathing)

            MoodBand.GOOD -> if (firedRules(state.report).any { it.alert in NET_ALERTS }) {
                listOfNotNull(noted)
            } else {
                listOf(BotPiece.Note(ChatText(R.string.chat_mood_close_good)))
            }
        }
    }
}

/** The plain cards a good mood's own questions can bring: its close is then a plain thanks. */
private val NET_ALERTS = setOf(SymptomAlert.HIGH_MOOD_NET, SymptomAlert.MOOD_SWINGS_NET)

/** The low mood's talk after a low face given, as its close offers it ([talkAfter]). */
private fun talkOffered(state: ChatState): Boolean = talkAfter(state.report, state.thread.others)

/**
 * Whether the close of a mood thread with [report] offers the low mood's talk: a low face given, a
 * doctor's line or none, no answer about suicide other than "no" in it or a thread [before] it, and
 * no low mood thread before it. Whether the mood was saved does not matter: the face is the person's.
 */
private fun talkAfter(report: SymptomReport, before: List<OtherThread>): Boolean {
    val mood = report.dialMood() ?: return false
    val crisis = crisisAnswered(report) || before.any { crisisAnswered(it.report) }
    return mood.band == MoodBand.LOW && !crisis &&
        chatUrgency(report) in setOf(SymptomUrgency.GP, SymptomUrgency.SELF_CARE) &&
        before.none { it.topic == Symptom.LOW_MOOD }
}

/**
 * Wound up or restless, with nothing in the chat a tension is never put down to: no breathlessness,
 * chest pain or racing heart ticked, no thread about one, no words of one, and no question about the
 * chest or breathing that words brought.
 */
private fun tense(state: ChatState): Boolean {
    val wound = state.report.energy() in setOf(Energy.WOUND_UP, Energy.RESTLESS)
    val reports = listOf(state.report) + state.thread.others.map { it.report }
    val ticked = reports.any { report -> report.answers.values.any { answers -> answers.any { it in BODY_SIGNS } } }
    val asked = reports.any { report -> report.chat?.askedSteps().orEmpty().any { it in BODY_NOW_STEPS } }
    val threads = state.thread.others.any { it.topic in BODY_TOPICS }
    val words = moodChatWords(state)
    val named = chestPainIn(words) || symptomTopicsIn(words).any { it in BODY_TOPICS }
    return wound && !ticked && !asked && !threads && !named
}

/** The states of an update's link in which it is no update any more: Undo put the mood back, or it was refused. */
private val UPDATE_NOT_SAVED = setOf(LinkState.UNDONE, LinkState.REFUSED)

/**
 * The card's line for [link], a mood of [thread] the door refused and the person then left as the
 * other was ("Leave it as it is"): this one is not saved, and the mood the question named stays —
 * as frozen with its answer, the thread's copy of it once a pencil came by. Null for any other.
 */
private fun keptStateLine(thread: ChatThread, link: FiledLink): ChatText? {
    val report = thread.report.asTold()
    val kept = link.kind == KIND_MOOD && link.state == LinkState.REFUSED &&
        report.has(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
    val target = frozenTarget(report.meta) ?: frozenTarget(report.meta, THREAD_META_PREFIX)
    return target?.takeIf { kept }?.let { ChatText(R.string.chat_mood_not_saved, listOf(PieceArg.Moment(it.at))) }
}

/** "Mood · Low · Wound up · 14:20": the title, the face, the tension when there is one, the time; "updated". */
private fun moodSummary(resources: Resources, row: JournalEntry?, role: String, zone: ZoneId): String = listOfNotNull(
    resources.getString(R.string.mood_title),
    Mood.fromScore(row?.moodScore)?.let { resources.getString(it.titleRes) },
    Energy.fromScore(row?.energyScore)?.let { resources.getString(it.titleRes) },
    row?.let { Instant.ofEpochMilli(it.occurredAt).atZone(zone).let { at -> clockText(at.hour * 60 + at.minute) } },
    resources.getString(R.string.chat_mood_updated).takeIf { role == MOOD_UPDATE_ROLE },
).joinToString(" · ")

/**
 * What a mood thread wants filed. Nothing before the dial, or after "Leave it as it is" there;
 * the mood the dial noted, written through as the tension and the reason are answered; the mood
 * already there, changed, after "Update it" — or, that mood not there any more, a mood of the
 * thread's own in its place ([moodRole]); and, the door having refused the write, the refusal kept as
 * it stands while "should this one replace it?" waits and once it was left: a Save again would only
 * meet the same four hours. A face, a tension or a reason taken back with the pencil keeps the record
 * as it stands until it is answered again; so does an update whose mood was deleted elsewhere, which
 * nothing could save again.
 */
private fun moodWants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
    val report = thread.report.forSave()
    val role = moodRole(report, thread.filed)
    // The update refused for want of its mood stays as the mark that this thread's own mood stands in its place.
    val gone = role.isEmpty() && report.has(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT) &&
        thread.filed.any { it.role == MOOD_UPDATE_ROLE && it.state == LinkState.REFUSED }
    return listOfNotNull(
        roleWant(thread, report, role, ctx)?.let { role to it },
        (MOOD_UPDATE_ROLE to RecordWant.Keep).takeIf { gone },
    ).toMap()
}

/** What the thread wants of its mood under [role] ([moodWants]); [report] is the thread's as saved. */
private fun roleWant(thread: ChatThread, report: SymptomReport, role: String, ctx: FilingContext): RecordWant? {
    val mood = report.dialMood()
    val linked = thread.filed.firstOrNull { it.role == role }?.state
    return when {
        report.has(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT) -> RecordWant.Keep.takeIf { mood != null }
        mood == null -> RecordWant.Keep.takeIf { linked != null }
        linked == LinkState.FILED && reopened(thread) -> RecordWant.Keep
        linked == LinkState.REFUSED && role.isEmpty() -> RecordWant.Keep
        linked == LinkState.GONE && role == MOOD_UPDATE_ROLE -> RecordWant.Keep
        role == MOOD_UPDATE_ROLE -> updateWant(report, mood, ctx)
        else -> RecordWant.File(newMoodDraft(report, mood, ctx))
    }
}

/**
 * Whether the tension or the reason was taken back with the pencil and not answered again: its answer
 * still shown on its chips, its turn gone. Read from the thread as it stands, not as it is saved.
 */
private fun reopened(thread: ChatThread): Boolean {
    val asked = thread.report.chat?.askedSteps().orEmpty()
    return RECORD_STEPS.any { step -> step !in asked && !thread.report.answers[step].isNullOrEmpty() }
}

/** The answers after the dial the mood's row is built from. */
private val RECORD_STEPS = listOf(SymptomStep.MOOD_ENERGY, SymptomStep.MOOD_ABOUT)

/** A new mood, through the mood page's factory and its door, at the moment the dial is taken. */
private fun newMoodDraft(report: SymptomReport, mood: Mood, ctx: FilingContext): RecordDraft.Journal =
    RecordDraft.Journal(JournalDoor.MOOD, wanted = null) { shell, _ ->
        moodEntry(
            ctx.strings,
            mood,
            report.energy(),
            LocalDate.parse(shell.day),
            shell.occurredAt,
            encodeMoodPayload(report.answers[SymptomStep.MOOD_ABOUT].orEmpty()),
        )
    }

/**
 * The mood "Update it" was about, changed to this thread's: the face, the tension (none clears an old
 * one), the note in the page's words, what it was about — under its own id, day, moment and title.
 */
private fun updateWant(report: SymptomReport, mood: Mood, ctx: FilingContext): RecordWant {
    val at = checkNotNull(frozenTarget(report.meta)).at
    val payload = encodeMoodPayload(report.answers[SymptomStep.MOOD_ABOUT].orEmpty())
    val change: (JournalEntry) -> JournalEntry = { row ->
        val fresh = moodEntry(ctx.strings, mood, report.energy(), ctx.chatDay, row.occurredAt, payload)
        row.copy(
            moodScore = fresh.moodScore,
            energyScore = fresh.energyScore,
            note = fresh.note,
            measurementPayloadJson = fresh.measurementPayloadJson,
        )
    }
    return RecordWant.File(RecordDraft.Update(KIND_MOOD, at, change, prior = ::moodPrior))
}

/**
 * The answers the mood's record is built from — the face, the tension, the reason, "update it?" and
 * the mood it was about — by their names, so a fingerprint stored with the chat reads the same in
 * the next run of the app. Its questions about the person alone (the changes, sleep and energy, the
 * person's own words) change no record. A record the person undid keeps the fingerprint it was undone
 * with: the card stands right after the dial, so the questions after it come after the Undo too, and
 * an answer to them must never file again what the person took back — only Save again does.
 */
private fun moodSig(thread: ChatThread, role: String): Int {
    thread.filed.firstOrNull { it.role == role && it.state == LinkState.UNDONE }?.let { return it.sig }
    val report = thread.report.forSave()
    fun names(step: SymptomStep) = report.answers[step].orEmpty().map { it.name }.sorted()
    return listOf(
        role,
        report.values[SymptomStep.MOOD_DIAL].orEmpty(),
        names(SymptomStep.MOOD_ENERGY),
        names(SymptomStep.MOOD_ABOUT),
        names(SymptomStep.MOOD_UPDATE),
        report.meta[UPDATE_AT].orEmpty(),
    ).toString().hashCode()
}

/**
 * The mood row's payload: what the mood was about, `{"schema":1,"about":["WORK_STUDY","FAMILY"]}`,
 * the options sorted by their order, null when nothing was picked. No other key, ever — no vital's,
 * no chat's, none of the person's words. A face later corrected in the editor keeps its reasons: the
 * correction rebuilds the row keeping its payload ([rebuiltFrom]), since what a mood was about is the
 * person's own answer, not a fact of the face.
 */
internal fun encodeMoodPayload(about: Collection<SymptomOption>): String? {
    val picked = about.distinct().sortedBy { it.ordinal }.ifEmpty { return null }
    return picked.joinToString(",", "{\"schema\":$MOOD_PAYLOAD_SCHEMA,\"about\":[", "]}") { "\"${it.name}\"" }
}

/** What a mood was about, from its payload: names this build does not know dropped, anything else read as none. */
internal fun decodeMoodAbout(json: String?): List<SymptomOption> {
    val about = json?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONArray("about") ?: return emptyList()
    return (0 until about.length())
        .mapNotNull { index -> SymptomOption.entries.firstOrNull { it.name == about.optString(index) } }
        .distinct()
}

/**
 * What puts back a mood this chat updated: its face, its tension, its note and its payload, written
 * by hand in one order — `{"mood":2,"energy":3,"note":"Low, Steady","payload":"…"}`, each but the note
 * only when set. The face is under "mood", the key the chat's own putting back reads ([restoredRow]),
 * so Undo puts the face back with the rest.
 */
internal fun moodPrior(row: JournalEntry): String {
    val fields = mutableListOf<String>()
    row.moodScore?.let { fields += "\"mood\":$it" }
    row.energyScore?.let { fields += "\"energy\":$it" }
    fields += "\"note\":" + JSONObject.quote(row.note)
    row.measurementPayloadJson?.let { fields += "\"payload\":" + JSONObject.quote(it) }
    return fields.joinToString(",", "{", "}")
}

/** Whether the high mood has its signs: much less need for sleep, or at least two of the others. */
private fun SymptomReport.highSigns(): Boolean {
    val signs = answers[SymptomStep.MOOD_HIGH_SIGNS].orEmpty()
    return SymptomOption.LESS_SLEEP_NEED in signs || signs.count { it in OTHER_HIGH_SIGNS } >= OTHER_SIGNS_NEEDED
}

private fun SymptomReport.goodMood(): Boolean = dialMood()?.band == MoodBand.GOOD

/**
 * The mood thread's own rules, read through [SYMPTOM_RULES]. Changes very big and long, or in
 * the way of everyday life: a doctor (NHS "Bipolar disorder"); not sure: when they would be. A good
 * mood with the signs: a few days, worth telling a doctor; a week or more, a doctor — "a long time"
 * read as a week or more is the app's convention, not the NHS's; in the way of everyday life at any
 * length, a doctor; with any sign while a course read as an antidepressant is active, advice today.
 */
internal val MOOD_RULES: List<AlertRule> = listOf(
    ticked(
        "MOOD_SWINGS",
        Symptom.MOOD,
        SymptomStep.MOOD_SWINGS,
        SymptomAlert.DOCTOR_MIND,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_bipolar,
    ),
    ticked(
        "MOOD_SWINGS_UNSURE",
        Symptom.MOOD,
        SymptomStep.MOOD_SWINGS,
        SymptomAlert.MOOD_SWINGS_NET,
        SymptomOption.NOT_SURE,
        sourceRes = R.string.symptom_source_bipolar,
    ),
    rule(
        "MOOD_HIGH_ON_ANTIDEPRESSANT",
        Symptom.MOOD,
        SymptomStep.MOOD_HIGH_SIGNS,
        SymptomAlert.HIGH_ON_ANTIDEPRESSANT,
        R.string.symptom_source_antidepressant_mania,
        reads = setOf(SymptomStep.MOOD_DIAL, SymptomStep.MOOD_HIGH_ASK),
    ) { report ->
        report.goodMood() && report.meta[HIGH_COURSE] != null &&
            report.answers[SymptomStep.MOOD_HIGH_SIGNS].orEmpty().any { it != SymptomOption.NONE }
    },
    rule(
        "MOOD_HIGH_DAYS",
        Symptom.MOOD,
        SymptomStep.MOOD_HIGH_LONG,
        SymptomAlert.HIGH_MOOD_NET,
        R.string.symptom_source_bipolar,
        reads = setOf(SymptomStep.MOOD_DIAL, SymptomStep.MOOD_HIGH_SIGNS),
    ) { report ->
        report.goodMood() && report.highSigns() && report.has(SymptomStep.MOOD_HIGH_LONG, SymptomOption.FEW_DAYS)
    },
    rule(
        "MOOD_HIGH_LONG_GP",
        Symptom.MOOD,
        SymptomStep.MOOD_HIGH_LONG,
        SymptomAlert.DOCTOR_MIND,
        R.string.symptom_source_bipolar,
        reads = setOf(SymptomStep.MOOD_DIAL, SymptomStep.MOOD_HIGH_SIGNS),
    ) { report ->
        report.goodMood() && report.highSigns() &&
            report.has(SymptomStep.MOOD_HIGH_LONG, SymptomOption.ABOUT_A_WEEK, SymptomOption.TWO_WEEKS_PLUS)
    },
    ticked(
        "MOOD_HIGH_LIFE",
        Symptom.MOOD,
        SymptomStep.MOOD_HIGH_LIFE,
        SymptomAlert.DOCTOR_MIND,
        SymptomOption.YES,
        sourceRes = R.string.symptom_source_bipolar,
    ),
)

/**
 * A low mood talk's "what is it about?" marked where a mood noted earlier in this chat said, for
 * [SymptomScript] to draw: the newest mood thread's reasons that the question offers, never ticked,
 * never armed. Only from a mood of the low or the middle face: what lifts a good mood
 * is no answer to what a low one is about.
 */
internal fun moodReasonMarks(state: ChatState, step: SymptomStep, options: List<SymptomOption>): Set<SymptomOption> {
    if (step != SymptomStep.ABOUT || state.report.symptom != Symptom.LOW_MOOD) return emptySet()
    val earlier = state.thread.others.asReversed().firstOrNull { other ->
        other.topic == Symptom.MOOD && other.report.dialMood()?.band.let { it != null && it != MoodBand.GOOD } &&
            !other.report.answers[SymptomStep.MOOD_ABOUT].isNullOrEmpty()
    }
    return earlier?.report?.answers?.get(SymptomStep.MOOD_ABOUT).orEmpty().filterTo(LinkedHashSet()) { it in options }
}

/** The mood's reason marked where a low mood talk earlier in this chat said, for a low or middle face only. */
private fun lowMoodReasonMarks(state: ChatState, options: List<SymptomOption>): Set<SymptomOption> {
    if (state.report.dialMood()?.band == MoodBand.GOOD) return emptySet()
    val earlier = state.thread.others.asReversed().firstOrNull { other ->
        other.topic == Symptom.LOW_MOOD && !other.report.answers[SymptomStep.ABOUT].isNullOrEmpty()
    }
    return earlier?.report?.answers?.get(SymptomStep.ABOUT).orEmpty().filterTo(LinkedHashSet()) { it in options }
}

/**
 * The mood's follow-up after a symptom thread, for [SymptomScript] to offer: "Note my mood" after a low
 * mood, worry or stress talk — never after an answer about suicide other than "no" or an
 * emergency, never in a chat for an earlier day, and not once a mood was noted in this chat; it takes
 * up a mood the words named. After the chest pain thread the chat began straight after a low mood
 * noted, the talk that mood's close offered ([carriedTalk]).
 */
internal fun moodAfterTalk(state: ChatState): TopicChoice? {
    carriedTalk(state)?.let { return it }
    val talk = state.report.symptom in TALK_TOPICS
    val noted = state.thread.others.any { it.topic == Symptom.MOOD }
    return NOTE_FOLLOW_UP.takeIf { talk && calm(state) && !noted && !state.pastDay }
}

/**
 * The talk a low mood's close offered, carried past the chest pain thread the chat began straight
 * after it because its words named the chest: the router offers the follow-ups of the thread that
 * closed last, and the mood's close already said the talk would come.
 */
private fun carriedTalk(state: ChatState): TopicChoice? {
    val mood = state.thread.others.lastOrNull()?.takeIf { it.topic == Symptom.MOOD } ?: return null
    val first = state.chat.turns.firstOrNull() as? ChatTurn.Asked
    val begun = state.thread.from == ROUTE_WORDS && first?.step == SymptomStep.SYMPTOM && first.routed
    return TALK_FOLLOW_UP.takeIf { begun && calm(state) && talkAfter(mood.report, state.thread.others.dropLast(1)) }
}

/** No answer about suicide other than "no" in the chat so far, and no emergency in this thread. */
private fun calm(state: ChatState): Boolean =
    !crisisSoFar(state) && firedRules(state.report).none { it.alert.urgency == SymptomUrgency.EMERGENCY }

// ── The mood's words ────────────────────────────────────────────────────────────────────

/** What a mood key names: a mood of some kind, a change of mood, little need for sleep, or another high sign. */
internal enum class MoodKind { GENERIC, GOOD, SO_SO, CHANGES, BAD, SLEEP_NEED, HIGH_SIGN }

/** A mood key's kept match ([MoodLexicon]); the payload is its [MoodKind]. */
internal const val MATCH_MOOD = "mood"

/**
 * The mood's keys, one list, in the lexicons' notation ([wordKey]): "настроени*" the
 * start of a word, "glad" the whole word. Not keys, on purpose: a bare "upset" (an upset stomach) and
 * «расстро» (an upset stomach in Russian), «перепад» alone (the weather's), «нормально», "not bad",
 * «средне»; «невесело» is no «весел*», and "unhappy" is a low mood's. Two words of change name no
 * mood by themselves ([THREAD_ONLY_CHANGES]): a blood sugar is up and down, a pain all over the place.
 */
internal val MOOD_KEYS: List<Pair<MoodKind, List<String>>> = listOf(
    MoodKind.GENERIC to listOf("mood", "moods", "moody", "настроени*"),
    MoodKind.GOOD to listOf(
        "happy", "good mood*", "great mood*", "feel great", "feeling great", "feel good", "feeling good",
        "feel amazing", "feeling amazing", "feel wonderful", "feeling wonderful", "cheerful", "joyful", "elated",
        "excited", "glad", "delighted", "thrilled", "over the moon", "on top of the world", "in high spirits",
        "upbeat", "buzzing", "euphori*", "хорошее настроени*", "отличное настроени*", "прекрасное настроени*",
        "настроение хорошее", "настроение отличное", "настроение супер", "счастлив*", "радост*", "радуюсь",
        "весел*", "воодушевл*", "окрылен*", "эйфори*", "на подъеме", "в приподнятом", "хорошо на душе",
        "на душе хорошо", "легко на душе", "на душе легко", "чувствую себя отлично", "чувствую себя прекрасно",
        "все отлично", "все супер", "кайфую",
    ),
    MoodKind.SO_SO to listOf(
        "so so", "meh", "okay ish", "ok ish", "nothing special", "neither good nor bad", "average day", "так себе",
        "ничего особенного", "ни то ни се", "ни хорошо ни плохо", "ни плохо ни хорошо", "серединка на половинку",
    ),
    MoodKind.CHANGES to CHANGE_KEYS - THREAD_ONLY_CHANGES,
    MoodKind.BAD to listOf(
        "bad mood*", "feel upset", "feeling upset", "i m upset", "so upset", "feeling blue", "feel blue", "gloomy",
        "heartbroken", "lonely", "down in the dumps", "плохое настроени*", "нет настроени*", "настроение на нуле",
        "настроение ниже плинтуса", "не в духе", "расстроен*", "расстроил*", "хандр*", "тяжело на душе",
        "на душе тяжело", "на душе плохо", "болит душа", "душа болит", "одиноко", "грусть*", "печал*",
        "хочется плакать", "плакать хочется", "руки опускаются",
    ),
    MoodKind.SLEEP_NEED to SLEEP_NEED_KEYS,
    MoodKind.HIGH_SIGN to HIGH_SIGN_KEYS,
)

/** Words of a changing mood: they bring "are these changes very big?" ([moodChangesIn]). */
private val CHANGE_KEYS: List<String>
    get() = listOf(
        "mood swing*", "up and down", "ups and downs", "mood keeps changing", "mood changes", "mood changing",
        "rollercoaster", "roller coaster", "all over the place", "перепад* настроени*", "настроение скачет",
        "настроение прыгает", "настроение меняется", "то хорошо то плохо", "то плохо то хорошо",
        "эмоциональные качели", "качели",
    )

/**
 * Words of change that are a mood's only inside a mood thread, where they bring "are these changes
 * very big?" ([moodChangesIn]): written anywhere else they name nothing, as "my blood sugar is up and
 * down" or "aching all over the place" — which the body's words then read as a pain all over.
 */
private val THREAD_ONLY_CHANGES: Set<String>
    get() = setOf("up and down", "all over the place")

/** Words of needing little sleep: read only inside a mood thread, they bring a good mood's check ([sleepNeedIn]). */
private val SLEEP_NEED_KEYS: List<String>
    get() = listOf(
        "don t need sleep", "dont need sleep", "don t need much sleep", "no need for sleep", "need less sleep",
        "need little sleep", "barely sleep*", "barely slept", "hardly sleep*", "hardly slept", "haven t slept",
        "up all night", "не хочется спать", "спать не хочется", "не нужно спать", "не надо спать", "почти не сплю",
        "совсем не сплю", "не сомкнул*",
    )

/** The other high signs' words: they bring the check only with an antidepressant course ([highSignIn]). */
private val HIGH_SIGN_KEYS: List<String>
    get() = listOf(
        "racing thoughts", "thoughts racing", "mind racing", "can t stop talking", "talking fast", "spending spree",
        "spent all my", "so much energy", "full of energy", "boundless energy", "мысли скачут", "мысли несутся",
        "голова кипит", "не могу перестать говорить", "тараторю", "говорю без остановки", "много трачу",
        "накупил*", "полно энергии", "энергии через край", "бьет ключом",
    )

/** Every mood key, and every key of a low mood's talk as words of a bad mood: they name the two at once. */
private val MOOD_TABLE: KeyTable<MoodKind> by lazy {
    KeyTable(
        MOOD_KEYS.flatMap { (kind, keys) -> keys.map { wordKey(it) to kind } } +
            TOPIC_KEYS.getValue(Symptom.LOW_MOOD).map { symptomWordsKey(it) to MoodKind.BAD },
    )
}

private val CHANGE_TABLE: KeyTable<MoodKind> by lazy { keyTable(MoodKind.CHANGES to CHANGE_KEYS) }

private val SLEEP_NEED_TABLE: KeyTable<MoodKind> by lazy { keyTable(MoodKind.SLEEP_NEED to SLEEP_NEED_KEYS) }

private val HIGH_SIGN_TABLE: KeyTable<MoodKind> by lazy { keyTable(MoodKind.HIGH_SIGN to HIGH_SIGN_KEYS) }

private fun KeyTable<MoodKind>.standsIn(text: String): Boolean = any(MessageWords(normalisedWords(text)))

/** Whether [words] tell of a mood that keeps changing. */
internal fun moodChangesIn(words: String): Boolean = CHANGE_TABLE.standsIn(words)

/** Whether [words] tell of needing little sleep. */
internal fun sleepNeedIn(words: String): Boolean = SLEEP_NEED_TABLE.standsIn(words)

/** Whether [words] tell of racing thoughts, talking without stopping, spending, or energy without end. */
internal fun highSignIn(words: String): Boolean = HIGH_SIGN_TABLE.standsIn(words)

/** Every mood key as written, those read only inside a mood thread too, for `MoodWordsTest.everyKeyIsNormalised`. */
internal fun moodKeyTexts(): List<String> = MOOD_KEYS.flatMap { (_, keys) -> keys } + THREAD_ONLY_CHANGES

/**
 * The mood's words: good, so-so, changing and bad moods, each a mention of the mood — a bad
 * one of the low mood's talk too — read in every message beside the body's ([CHAT_LEXICONS]). Its
 * phrases hold their words against the body's («болит душа» is no pain, «голова кипит» no head); the
 * words of needing little sleep and the other high signs hold theirs too, and name nothing: only a
 * mood thread reads them. A mood is never read to a face: the dial always starts at rest.
 */
internal object MoodLexicon : WordLexicon {
    override val priority: Int = WordPriority.MOOD

    override fun matches(words: String, raw: String, setting: ChatSetting): List<WordMatch> =
        MOOD_TABLE.all(MessageWords(words)).map { hit ->
            WordMatch(hit.span, MATCH_MOOD, hit.value, priority, verb = true)
        }

    /**
     * Each mood key that stands in a clause where its words were kept for it — or kept out of the
     * body's only, as «руки опускаются» is: a key that keeps a word from a place takes nothing from a mood.
     */
    override fun mentions(kept: List<WordMatch>, clauses: List<Clause>, setting: ChatSetting): List<WordMention> =
        clauses.flatMap { clause ->
            val held = clause.kept
                .filter { it.match.kind == MATCH_MOOD || it.match.priority == WordPriority.EXCLUSION }
                .mapTo(HashSet()) { it.from to it.to }
            MOOD_TABLE.longest(MessageWords(clause.words))
                .filter { (it.from to it.to) in held }
                .flatMap { hit -> moodMentions(hit.value, clause.offsets[hit.from]) }
        }
}

/** What a mood key names at [at]: the mood, a bad one the low mood's talk too; little sleep and high signs nothing. */
private fun moodMentions(kind: MoodKind, at: Int): List<WordMention> = when (kind) {
    MoodKind.SLEEP_NEED, MoodKind.HIGH_SIGN -> emptyList()

    MoodKind.BAD -> listOf(
        SymptomWordMention(at, TopicMention(Symptom.MOOD)),
        SymptomWordMention(at, TopicMention(Symptom.LOW_MOOD)),
    )

    MoodKind.GENERIC, MoodKind.GOOD, MoodKind.SO_SO, MoodKind.CHANGES ->
        listOf(SymptomWordMention(at, TopicMention(Symptom.MOOD)))
}
