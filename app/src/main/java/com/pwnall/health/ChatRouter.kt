package com.pwnall.health

import java.time.Instant

// The router: what the chat says around its threads. The opening and its grouped menu, the reply to
// words written before any topic — a topic chosen for the person only where the words leave no
// doubt, and always with a way out — the queue of what the words named for later, the prompt
// between threads, the chest triage the chat starts itself, and the end. Everything here is pure:
// what is said is worked out again from the log on every draw, so a chat reads the same each time it
// is opened.

/** A chest pain thread the chat started because the person wrote about their chest. */
internal const val ROUTE_WORDS = "WORDS"

/** A chest pain thread started from a pain thread whose marks took in the chest: `HANDOVER:<id>`. */
internal const val ROUTE_HANDOVER = "HANDOVER:"

/** The pain thread taken up again once that chest pain thread is over: `RESUME:<id>`. */
internal const val ROUTE_RESUME = "RESUME:"

/** A thread started from a follow-up chip: `FOLLOW:<mention key>`. */
internal const val ROUTE_FOLLOW = "FOLLOW:"

/**
 * How long the page may stand left — the app stopped, a record opened over it — before coming back to
 * it starts a new sitting, whose chest is asked about again: an answer to "is it there now?" given
 * before a gap this long is never carried into another thread. The app's convention, not the NHS's.
 */
internal const val SITTING_GAP_MS = 15 * 60 * 1_000L

/**
 * The first thread of the sitting a page brought back stands in: a new sitting from [threads] when it
 * was left at [awaySince] a quarter of an hour or more before [now]; else the sitting it was in, [from].
 */
internal fun sittingOnReturn(from: Int, threads: Int, awaySince: Long?, now: Long): Int =
    if (awaySince != null && now - awaySince >= SITTING_GAP_MS) threads else from

/** The most chips the queue puts in front of the person at once. */
private const val QUEUE_MAX = 4

/** Where the chat stands: no thread yet, a question of the last thread, between threads, or over. */
internal sealed interface ChatMove {
    data object Opening : ChatMove

    /** The last thread, [thread] by its id, asks [step]. */
    data class Ask(val thread: Int, val step: SymptomStep) : ChatMove

    /** The last thread is over and the chat is not: the prompt. */
    data object Prompt : ChatMove

    data class Over(val end: ChatEnd) : ChatMove
}

internal fun chatMove(log: ChatLog, setting: ChatSetting): ChatMove {
    val end = log.end
    val last = log.threads.lastOrNull()
    val step = last?.takeIf {
        end == null && !it.ended
    }?.let { nextChatStep(log.threadState(log.threads.lastIndex, setting)) }
    return when {
        end != null -> ChatMove.Over(end)
        last == null -> ChatMove.Opening
        step == null -> ChatMove.Prompt
        else -> ChatMove.Ask(last.id, step)
    }
}

/**
 * The thread at [index] as its scripts read it: its report as told, the earlier chat on its topic —
 * looked for back from the chat's moment, leaving out what this chat filed itself — and where it
 * stands in the chat ([ThreadContext]).
 */
internal fun ChatLog.threadState(index: Int, setting: ChatSetting): ChatState {
    val thread = threads[index]
    val report = thread.report.asTold()
    val moments = threads.flatMapTo(HashSet()) { other -> other.filed.map { it.at } }
    val rows = setting.env.recentSymptoms.filter { it.occurredAt !in moments }
    val earlier = earlierChatFor(report.symptom, rows, except = null, now = setting.chatMoment, zone = setting.zone)
    val context = ThreadContext(
        index = index,
        id = thread.id,
        from = thread.from,
        before = threads.take(index).mapNotNull { it.topic },
        chestAsked = chestAsked(index, setting),
        chestSpreadAreas = chestSpreadAreas(index, setting),
        resumed = sourceOf(thread)?.report?.forSave(),
        links = thread.filed,
        others = threads.take(index).map { OtherThread(it.id, it.topic, it.report.forSave(), it.filed) },
        filedMoments = moments,
        crisisInChat = crisisInChat(),
        day = setting.day,
        today = Instant.ofEpochMilli(setting.now).atZone(setting.zone).toLocalDate(),
        env = setting.env,
        now = setting.now,
        readLanguage = setting.language != ChatLanguage.OTHER,
        journal = setting.journal,
    )
    return ChatState(report, setting.female, earlier, pastDay = past, adult = setting.adult, thread = context)
}

/** Whether any thread of the chat had an answer about suicide other than "no". */
internal fun ChatLog.crisisInChat(): Boolean = threads.any { crisisAnswered(it.report.asTold()) }

/** Whether an EMERGENCY-urgency line fired in [thread]: nothing more is asked after it. */
private fun stopped(thread: ChatThread): Boolean =
    firedRules(thread.report.asTold()).any { it.alert.urgency == SymptomUrgency.EMERGENCY }

/** Whether a chest pain thread of this sitting, before [index], closed without an emergency. */
private fun ChatLog.chestAsked(index: Int, setting: ChatSetting): Boolean =
    threads.withIndex().any { (at, thread) ->
        at in setting.sittingFrom until index && thread.topic == Symptom.CHEST_PAIN && thread.ended && !stopped(thread)
    }

/** The area codes this sitting's "does it spread from the chest?" questions named, up to and with [index]. */
private fun ChatLog.chestSpreadAreas(index: Int, setting: ChatSetting): Set<String> =
    threads.withIndex()
        .filter { (at, _) -> at >= setting.sittingFrom && at <= index }
        .flatMapTo(HashSet()) { (_, thread) ->
            thread.report.meta[CHEST_SPREAD_AREAS].orEmpty().split(',').filter { it.isNotEmpty() }
        }

/** The frozen codes a chest's "does it spread?" question named ([TopicScript.freeze]). */
internal const val CHEST_SPREAD_AREAS = "CHEST_SPREAD.areas"

/** The thread a `HANDOVER:` or `RESUME:` thread was started from. */
private fun ChatLog.sourceOf(thread: ChatThread): ChatThread? {
    val from = thread.from ?: return null
    val id = when {
        from.startsWith(ROUTE_HANDOVER) -> from.removePrefix(ROUTE_HANDOVER)
        from.startsWith(ROUTE_RESUME) -> from.removePrefix(ROUTE_RESUME)
        else -> return null
    }.toIntOrNull()
    return threads.firstOrNull { it.id == id }
}

/**
 * The log after a commit, in this order: the last thread marked ended once nothing is left to ask; an
 * emergency in it ends the chat; a thread of words with no topic yet takes the one its words leave no
 * doubt about; a thread over brings the thread the chat starts itself, if one is due — the chest
 * triage, the pain taken up after it, the chest asked about after words. The rev is raised, so an
 * older copy never overwrites this one ([ChatFiling.persist]).
 *
 * A message the bot's turn waits for the reading of ([ChatHeld.Message]) keeps its thread of words
 * without a topic for now: kept at once and routed only with its reading,
 * so a crisis the reading flags holds the route back as the words of one do — whatever else is
 * committed meanwhile, another message's reading among it — and the commit that keeps the reading, or
 * lets the message go without one, routes it.
 */
internal fun settledLog(log: ChatLog, setting: ChatSetting): ChatLog {
    var settled = log.settleLast(setting)
    val stoppedLast = settled.threads.lastOrNull()?.let { it.ended && stopped(it) } == true
    if (settled.end == null && stoppedLast) settled = settled.copy(end = ChatEnd.STOP)
    if (settled.end == null && settled.held !is ChatHeld.Message) settled = settled.routeLast(setting)
    if (settled.end == null) settled = settled.appendDue(setting, closedNow = log.threads.lastOrNull()?.ended != true)
    return settled.copy(rev = log.rev + 1)
}

private fun ChatLog.settleLast(setting: ChatSetting): ChatLog {
    val last = threads.lastOrNull() ?: return this
    val state = threadState(threads.lastIndex, setting)
    return updateLast { settled(state, last.report) }
}

/**
 * A thread begun with words and no topic yet, once the questions the words asked first are answered:
 * closed after an answer about suicide other than "no" — the prompt offers to talk about it — and
 * otherwise given the topic its words leave no doubt about: chest pain for any pain the words put in
 * the chest ([chestWordsIn]: the ribs, a breast, the breastbone too), else the one record the words
 * named and nothing else. The topic turn is the chat's ([ChatTurn.Asked.routed]):
 * no bubble, no pencil, and "Another topic" leaves it ([leftRoute]).
 *
 * A friendly last message ([conversationLines]) is met with its own line and no record is taken up
 * for the person — plain sadness starts no mood thread — but it never holds the chest's thread back:
 * that one has no way out, and a greeting or a glass of water written while "is it there now?" stood
 * would otherwise leave the rest of the chest's questions as a chip to tap or not.
 */
private fun ChatLog.routeLast(setting: ChatSetting): ChatLog {
    val last = threads.lastOrNull()?.takeIf { it.topic == null && !it.ended && !it.noRoute } ?: return this
    val state = threadState(threads.lastIndex, setting)
    if (nextChatStep(state) != SymptomStep.SYMPTOM) return this
    val words = last.report.chat?.words().orEmpty()
    return when {
        crisisAnswered(state.report) -> updateLast { it.copy(chat = it.chat?.copy(ended = true)) }

        chestWordsIn(words) -> routed(Symptom.CHEST_PAIN, ROUTE_WORDS, setting)

        conversationLines(this, last) != null -> this

        else -> routedMention(messagesOf(last.report.chat), state, setting)?.let { routed(it.topic, it.key, setting) }
            ?: this
    }
}

/**
 * The one record the words named, with no symptom topic beside it and no topic's own first reply for
 * them, and never from words of suicide until the person said they have no such thoughts. The rules'
 * words alone start a thread: what a model read proposes chips and the queue, never a start of its
 * own, so a chest the model alone read asks its question, as another language's chest does
 * ([wordSteps]), and starts no thread of the chest. A crisis a reading flagged holds the route back
 * as the words of one do — its question comes first, and the route only once it is answered — since
 * a message waiting for its reading is not routed before it ([settledLog]).
 */
private fun ChatLog.routedMention(
    messages: List<ChatTurn.Wrote>,
    state: ChatState,
    setting: ChatSetting,
): TopicMention? {
    val found = ScriptedMentionReader.read(messages, setting)
    if (found.crisis && !saidNoSuchThoughts(state.report)) return null
    val offered = found.mentions.filter { offered(it.mention.topic, setting) }
    val records = offered.filter { it.mention.topic.record != ChatRecord.SYMPTOM }.distinctBy { it.mention.key }
    val symptoms = offered.filter { it.mention.topic.record == ChatRecord.SYMPTOM }
    val ownReply = found.mentions.any { it.mention.topic.script.firstReply(it, found, setting) != null }
    return records.singleOrNull()?.mention?.takeIf { symptoms.isEmpty() && !ownReply }
}

private fun saidNoSuchThoughts(report: SymptomReport): Boolean =
    report.has(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS)

/** The last thread given [topic] by the chat, started from [from]; a symptom topic takes the snapshot. */
private fun ChatLog.routed(topic: Symptom, from: String, setting: ChatSetting): ChatLog {
    val snapshot = setting.snapshot.takeIf { topic.record == ChatRecord.SYMPTOM }
    val routed = updateLast { it.copy(context = it.context ?: snapshot).routedTo(topic) }
    return routed.copy(threads = routed.threads.dropLast(1) + routed.threads.last().copy(from = from))
}

/** [topic] taken as the chat's own choice: the topic turn, with what it calls for, drawn as nobody's answer. */
internal fun SymptomReport.routedTo(topic: Symptom): SymptomReport = copy(symptom = topic)
    .answered(SymptomStep.SYMPTOM)
    .lastTurnRouted()

private fun SymptomReport.lastTurnRouted(): SymptomReport {
    val told = chat ?: return this
    val last = told.turns.lastOrNull() as? ChatTurn.Asked ?: return this
    return copy(chat = told.copy(turns = told.turns.dropLast(1) + last.copy(routed = true)))
}

/**
 * The thread the chat starts itself once the last one is over, if one is due — at most one a
 * commit, the next settle bringing the next: the chest triage a pain thread hands over to; the pain
 * thread taken up again after that triage closed calm; the chest asked about after a thread whose
 * words named it, at the commit that closed it ([closedNow]). A thread closed in a sitting that had
 * asked about the chest is past it: a later sitting's commit that closes nothing — a card ticked, a
 * photo kept — asks it no chest of its old words.
 */
private fun ChatLog.appendDue(setting: ChatSetting, closedNow: Boolean): ChatLog {
    val index = threads.lastIndex
    val last = threads.getOrNull(index)?.takeIf { it.ended } ?: return this
    val state = threadState(index, setting)
    val handover = last.topic?.script?.handover(state)
    val from = last.from.orEmpty()
    val chestWords = last.topic != Symptom.CHEST_PAIN && chestWordsIn(last.report.chat?.words().orEmpty())
    return when {
        handover != null -> appendRouted(handover, ROUTE_HANDOVER + last.id, setting)

        from.startsWith(ROUTE_HANDOVER) && !stopped(last) ->
            appendRouted(Symptom.PAIN, ROUTE_RESUME + from.removePrefix(ROUTE_HANDOVER), setting)

        chestWords && closedNow && !state.thread.chestAsked -> appendRouted(Symptom.CHEST_PAIN, ROUTE_WORDS, setting)

        else -> this
    }
}

/**
 * A new thread the chat starts itself on [topic] from [from], with the sitting's answer to "is it
 * there now?" carried into a chest thread; never a second thread from the same start.
 */
private fun ChatLog.appendRouted(topic: Symptom, from: String, setting: ChatSetting): ChatLog {
    if (from != ROUTE_WORDS && threads.any { it.from == from }) return this
    val snapshot = setting.snapshot.takeIf { topic.record == ChatRecord.SYMPTOM }
    var report = SymptomReport(context = snapshot, chat = SymptomChat()).routedTo(topic)
    if (topic == Symptom.CHEST_PAIN) sittingChestNow(setting)?.let { report = report.seeded(SymptomStep.CHEST_NOW, it) }
    return copy(threads = threads + ChatThread(id = next, report = report, from = from), next = next + 1)
}

/**
 * The newest answer to "is the chest pain there now?" of this sitting, to carry into a chest thread:
 * one asked for the chest the rules read — the words, the topic, a chest pain ticked — never one only
 * a reading's flag brought, whose "no" may be about another pain and would keep the chest thread from
 * asking it (a reading's question takes no question of the rules' away).
 */
private fun ChatLog.sittingChestNow(setting: ChatSetting): SymptomOption? =
    threads.withIndex().drop(setting.sittingFrom).reversed().firstNotNullOfOrNull { (index, thread) ->
        thread.report.forSave().answers[SymptomStep.CHEST_NOW]?.firstOrNull()
            ?.takeIf { rulesAskedChest(index, setting) }
    }

/** Whether the rules alone ask "is the chest pain there now?" in the thread at [index]: not a reading alone. */
private fun ChatLog.rulesAskedChest(index: Int, setting: ChatSetting): Boolean {
    val chat = threads[index].report.chat ?: return true
    if (SymptomStep.CHEST_NOW !in chat.readSteps()) return true
    val state = threadState(index, setting).withoutReadings()
    return SymptomStep.CHEST_NOW in wordSteps(state.chat, state.report, setting.env.courses) ||
        SymptomStep.CHEST_NOW in chatSteps(state)
}

/**
 * [option] carried in as the answer to [step]: asked by nobody, so no question, no bubble, no pencil — its rules fire.
 */
private fun SymptomReport.seeded(step: SymptomStep, option: SymptomOption): SymptomReport =
    copy(answers = answers + (step to listOf(option))).answered(step).lastTurnRouted()

/**
 * Whether [topic] is offered in this chat now: its script's own say, with the chat's crisis answers
 * known to it (the mood is never offered after one).
 */
internal fun ChatLog.offered(topic: Symptom, setting: ChatSetting): Boolean =
    topic.script.offered(setting.copy(crisisInChat = crisisInChat()))

/** The menu's groups, in order: what to note down, the body, feelings, and "something else" under no heading. */
internal val MENU_GROUPS: List<Pair<Int?, List<Symptom>>> = listOf(
    R.string.symptom_chat_group_records to listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE),
    R.string.symptom_chat_group_body to listOf(
        Symptom.VOMITING,
        Symptom.NAUSEA,
        Symptom.DIARRHOEA,
        Symptom.HEARTBURN,
        Symptom.DIZZINESS,
        Symptom.FAINTING,
        Symptom.PALPITATIONS,
        Symptom.CHEST_PAIN,
        Symptom.BREATHLESSNESS,
        Symptom.FEVER,
        Symptom.TIREDNESS,
    ),
    R.string.symptom_chat_group_mind to listOf(Symptom.WORRY, Symptom.LOW_MOOD, Symptom.STRESS, Symptom.SLEEP_WORRY),
    null to listOf(Symptom.OTHER),
)

/**
 * The whole menu: every topic offered now, in [TOPIC_ORDER], less a topic asked once a chat whose
 * thread has run.
 */
internal fun chatMenu(log: ChatLog, setting: ChatSetting): List<TopicChoice> = TOPIC_ORDER
    .filter { topic ->
        log.offered(topic, setting) && !(topic.asksOncePerChat && log.threads.any { it.topic == topic })
    }
    .map { TopicChoice(it) }

/**
 * What the chat comes back to: the follow-ups of the thread that just closed first, then every
 * mention of every thread's words — the chest first, then the body in the menu's order, the
 * feelings, a pain, the mood, a dose, a course, each rank in the order they were written ([queueOrder])
 * — less what a thread already serves, what is not offered, a pain in the chest until the chest has
 * been asked about, and a mention twice; at most [QUEUE_MAX].
 */
internal fun chatQueue(log: ChatLog, setting: ChatSetting): List<TopicChoice> {
    val all = queueChoices(log, setting)
    if (!log.routesByReadings()) return all.take(QUEUE_MAX)
    // What the readings add only adds: every chip of the rules' own queue stays, and the readings' fill
    // what room is left, all of them in the readings' order.
    val rules = queueChoices(log.withoutReadings(), setting).take(QUEUE_MAX).mapTo(HashSet(), ::queueKey)
    val added = all.filter { queueKey(it) !in rules }.take(QUEUE_MAX - rules.size).mapTo(HashSet(), ::queueKey)
    return all.filter { queueKey(it) in rules || queueKey(it) in added }
}

/** Every chip the queue may take, in its order ([chatQueue]): its cap not applied yet. */
private fun queueChoices(log: ChatLog, setting: ChatSetting): List<TopicChoice> {
    val follow = log.threads.lastOrNull()?.takeIf { it.ended }?.let { last ->
        last.topic?.script?.followUps(log.threadState(log.threads.lastIndex, setting))
    }.orEmpty()
    val chestDone = log.chestAsked(log.threads.size, setting)
    val mentions = log.threads.flatMap { thread -> mentionsOf(thread, setting) }
        .withIndex()
        .sortedWith(queueOrder(log))
        .map { TopicChoice(it.value.topic, it.value) }
    return (follow + mentions)
        .filter { choice -> choice.mention?.let { log.serves(it) } != true && log.offered(choice.topic, setting) }
        .filter { choice -> chestDone || choice.mention?.inChest() != true }
        .distinctBy(::queueKey)
}

/** What makes a chip of the queue the one it is: its mention, or its topic for a follow-up of none. */
private fun queueKey(choice: TopicChoice): String = choice.mention?.key ?: choice.topic.name

/**
 * The mentions of [thread]'s words; no record from words of suicide unless the person said they have
 * no such thoughts.
 */
private fun mentionsOf(thread: ChatThread, setting: ChatSetting): List<TopicMention> {
    val words = thread.report.chat?.words().orEmpty()
    if (words.isBlank()) return emptyList()
    val found = chatReader.read(messagesOf(thread.report.chat), setting)
    val records = !found.crisis || saidNoSuchThoughts(thread.report.forSave())
    return found.mentions.map { it.mention }.filter { records || it.topic.record == ChatRecord.SYMPTOM }
}

/**
 * The order of the queue: by [queueRank], each rank in the order its mentions were written — and, once
 * a message of the chat was read by the model, the next-step decider's within a rank:
 * a topic with red flags of its own never after one without, then the likelier in the
 * readings first. The ranks themselves never move, so the chest stays first and a body's sign before
 * a feeling; with no reading, the order is the rules' alone.
 */
private fun queueOrder(log: ChatLog): Comparator<IndexedValue<TopicMention>> {
    val byRank = compareBy<IndexedValue<TopicMention>> { queueRank(it.value) }
    if (!log.routesByReadings()) return byRank.thenBy { it.index }
    val read = log.threads.flatMap { messagesOf(it.report.chat) }.mapNotNull { it.routingRead }
        .flatMap { it.topics }.distinct()
    return byRank.thenBy { if (it.value.topic in ALERTED_TOPICS) 0 else 1 }
        .thenBy { read.indexOf(it.value.topic).takeIf { at -> at >= 0 } ?: read.size }
        .thenBy { it.index }
}

/** The topics with red flags of their own ([AlertRule.topics]): never queued after a topic without. */
private val ALERTED_TOPICS: Set<Symptom> by lazy { SYMPTOM_RULES.flatMapTo(HashSet()) { it.topics } }

/** The chest first, the body in the menu's order, feelings, then the records: the order the queue asks them in. */
private fun queueRank(mention: TopicMention): Int {
    val topic = mention.topic
    return when {
        topic == Symptom.CHEST_PAIN -> 0
        topic.record == ChatRecord.SYMPTOM && !topic.mind -> 1 + TOPIC_ORDER.indexOf(topic)
        topic.record == ChatRecord.SYMPTOM -> 1 + TOPIC_ORDER.size
        else -> 2 + TOPIC_ORDER.size + topic.record.ordinal
    }
}

/** Whether a thread of this chat already takes [mention] up: started from it, its script serving it, or once a chat. */
private fun ChatLog.serves(mention: TopicMention): Boolean = threads.any { thread ->
    thread.from == mention.key || thread.from == ROUTE_FOLLOW + mention.key ||
        thread.topic?.script?.serves(thread, mention) == true ||
        (mention.topic.asksOncePerChat && thread.topic == mention.topic)
}

/** A pain in the chest, which waits until the chest has been asked about. */
private fun TopicMention.inChest(): Boolean =
    topic == Symptom.PAIN && (detail.startsWith("CHEST") || detail.startsWith("STERNUM"))

/**
 * [choice] taken up: a follow-up chip of the thread that just closed starts a thread routed from it
 * (`FOLLOW:<key>`), any other chip the thread it names ([chose]).
 */
internal fun ChatLog.choseTopic(choice: TopicChoice, setting: ChatSetting): ChatLog {
    val last = threads.lastOrNull()
    val follow = last?.takeIf { it.ended }?.topic?.script?.followUps(threadState(threads.lastIndex, setting))
    val mention = choice.mention
    if (mention == null || follow?.any { it.mention?.key == mention.key } != true) return chose(choice, setting)
    val snapshot = setting.snapshot.takeIf { choice.topic.record == ChatRecord.SYMPTOM }
    val report = SymptomReport(context = snapshot, chat = SymptomChat()).routedTo(choice.topic)
    val thread = ChatThread(id = next, report = report, from = ROUTE_FOLLOW + mention.key)
    return if (end != null) this else copy(threads = threads + thread, next = next + 1)
}

/**
 * "Another topic": the start the chat chose from the person's words left — its topic turn taken back,
 * its words kept, and never chosen for that thread again; the whole menu follows.
 */
internal fun ChatLog.leftRoute(threadId: Int): ChatLog {
    val thread = threads.firstOrNull { it.id == threadId } ?: return this
    val turns = thread.report.chat?.turns.orEmpty()
    val at = turns.indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM && it.routed }
    val back = if (at >= 0) rewoundLog(threadId, at) else this
    return back.copy(threads = back.threads.map { if (it.id == threadId) it.copy(noRoute = true, from = null) else it })
}

/**
 * The live turn: the opening; the last thread's question — the router's own reply to words at a
 * topic question; the prompt between threads; or the end.
 */
internal fun chatTurn(log: ChatLog, setting: ChatSetting): BotTurn = when (val move = chatMove(log, setting)) {
    ChatMove.Opening -> openingTurn(log, setting)
    is ChatMove.Ask -> askTurn(log, setting, move.step)
    ChatMove.Prompt -> promptTurn(log, setting)
    is ChatMove.Over -> overTurn(log, setting, move.end)
}

/**
 * The live turn as the page shows it: a thread's own question asked through [responder] — the seam a
 * model could word a question behind, held to the script by [SafetyGuard] — and everything
 * around the threads the router's own.
 */
internal suspend fun liveTurn(
    log: ChatLog,
    setting: ChatSetting,
    responder: SymptomResponder = symptomResponder,
): BotTurn {
    val move = chatMove(log, setting)
    if (move !is ChatMove.Ask || move.step == SymptomStep.SYMPTOM) return chatTurn(log, setting)
    val inner = responder.next(log.threadState(log.threads.lastIndex, setting))
    return askTurn(log, setting, move.step, inner)
}

/** "Hello", the day a chat of an earlier day is filed under, the question, and the grouped menu. */
internal fun openingTurn(log: ChatLog, setting: ChatSetting): BotTurn {
    val pieces = listOfNotNull(
        BotPiece.Line(ChatLine.OPEN),
        BotPiece.ForDay(setting.day).takeIf { log.past },
        BotPiece.Line(ChatLine.OPENING),
        BotPiece.Line(ChatLine.ADULTS).takeUnless { setting.adult },
    )
    return BotTurn(pieces, ChatReply.Topics(chatMenu(log, setting), all = true, resume = setting.resumeKey))
}

/**
 * The last thread's question. At a thread's topic question the reply is the router's ([topicTurn]);
 * a thread the chat started itself opens with the close of the thread before it; a start chosen from
 * the person's words keeps "Another topic" until the person has answered in it.
 */
private fun askTurn(log: ChatLog, setting: ChatSetting, step: SymptomStep, inner: BotTurn? = null): BotTurn {
    val index = log.threads.lastIndex
    val state = log.threadState(index, setting)
    val turn = if (step == SymptomStep.SYMPTOM) {
        topicTurn(log, index, setting)
    } else {
        inner ?: turnBefore(state, step)
    }
    val carried = if (log.appended(index) &&
        !ownTurns(log.threads[index])
    ) {
        closeOf(log, index - 1, setting)
    } else {
        emptyList()
    }
    val position = log.threads[index].report.chat?.turns?.size ?: 0
    val asked = if (step == SymptomStep.SYMPTOM) turn else log.withRoutedStart(index, position, setting, turn)
    val reply = if (exitOffered(log.threads[index])) turn.reply.withExit() else turn.reply
    return log.withRulesFloor(index, position, setting, BotTurn(carried + asked.pieces, reply))
}

/**
 * [turn] — the bot's turn before turn [position] of the thread at [index] — with every safety piece
 * the rules alone put there (a reading only adds): the turn of the chat
 * without its readings at the same place, asking what the rules would ask there ([plainTurnAt]). What
 * of it [turn] lacks — the line to call for an emergency, where help is, someone else's, a medicine's
 * notes — comes first, after the safety pieces [turn] opens with, in the rules' order: a question a
 * flag brings in place of the rules' reply to the words takes none of that reply's safety away, nor
 * holds it back to a later turn that an answer may close the thread before. Itself with no reading.
 */
private fun ChatLog.withRulesFloor(index: Int, position: Int, setting: ChatSetting, turn: BotTurn): BotTurn {
    if (!routesByReadings()) return turn
    val bare = withoutReadings()
    val step = nextChatStep(prefixState(bare, index, position, setting))
    val rules = plainTurnAt(bare, index, position, step, setting).pieces
    return turn.withFloor(if (turn.pieces.any { it is BotPiece.Journal }) rules - SAFETY_LINE else rules)
}

/**
 * The line to call emergency services the rules put under words they read nothing in. The one safety
 * piece a reading takes away, and only where it answers the words with a record card: for words a
 * model read as the person's own record and raised no flag in — every other safety piece, and this
 * line beside anything else, stays the rules'.
 */
internal val SAFETY_LINE = BotPiece.Line(ChatLine.SAFETY)

/** [this] with the safety pieces of [rules] it lacks, in their order, after the safety pieces it opens with. */
private fun BotTurn.withFloor(rules: List<BotPiece>): BotTurn {
    val missing = rules.filter { it.isSafety() && it !in pieces }.distinct()
    if (missing.isEmpty()) return this
    val at = pieces.indexOfFirst { !it.isSafety() }.takeIf { it >= 0 } ?: pieces.size
    return copy(pieces = pieces.take(at) + missing + pieces.drop(at))
}

/**
 * [turn], the bot's turn before turn [position] of the thread at [index], with what a start the chat
 * chose from the person's words owes when it is that start's first question — right after the topic
 * turn the chat took — as the router's own reply to those words would have said it: first,
 * for words about someone else, whose journal this is and to call for them, and for a child's that the
 * chat is for adults; and before the question, with no English or Russian word read — an emoji alone,
 * another language — the line to call for an emergency.
 */
private fun ChatLog.withRoutedStart(index: Int, position: Int, setting: ChatSetting, turn: BotTurn): BotTurn {
    val chat = threads[index].report.chat ?: return turn
    val topicTurn = chat.turns.getOrNull(position - 1) as? ChatTurn.Asked ?: return turn
    if (topicTurn.step != SymptomStep.SYMPTOM || !topicTurn.routed) return turn
    val before = chat.copy(turns = chat.turns.take(position))
    if (before.words().isBlank()) return turn
    val found = chatReader.read(messagesOf(before), setting)
    // The routing message's records the topic's own questions do not ask about, as its topic turn would
    // have carried them, before the first question: the same gate, and no generic line beside them.
    val records = !found.crisis || saidNoSuchThoughts(threads[index].report.forSave())
    val cards = if (records && !crisisInChat()) {
        journalCards(messagesOf(before).lastOrNull(), threads[index].id, messagesOf(before).lastIndex, setting.journal)
            .filter { it.event.type !in CHAT_OWN_JOURNAL }
    } else {
        emptyList()
    }
    val safety = SAFETY_LINE.takeIf { !found.language && cards.isEmpty() && it !in turn.pieces }
    val ask = turn.pieces.indexOfFirst { it is BotPiece.Ask }.takeIf { it >= 0 } ?: turn.pieces.size
    val pieces = turn.pieces.take(ask) + listOfNotNull(safety) + cards + turn.pieces.drop(ask)
    return turn.copy(pieces = othersLines(found) + pieces)
}

/** The close of the thread at [index]: its last answer's line and its topic's close. */
internal fun closeOf(log: ChatLog, index: Int, setting: ChatSetting): List<BotPiece> =
    turnBefore(log.threadState(index, setting), null).pieces

/** Whether the thread at [index] was started by the chat after the one before it, with no prompt between. */
private fun ChatLog.appended(index: Int): Boolean {
    val thread = threads.getOrNull(index) ?: return false
    val from = thread.from ?: return false
    val topicTurn = thread.report.chat?.turns?.firstOrNull() as? ChatTurn.Asked
    val chatStarted = from == ROUTE_WORDS || from.startsWith(ROUTE_HANDOVER) || from.startsWith(ROUTE_RESUME)
    return index > 0 && chatStarted && topicTurn?.step == SymptomStep.SYMPTOM && topicTurn.routed
}

/** Whether the person has said or answered anything in [thread] yet. */
private fun ownTurns(thread: ChatThread): Boolean =
    thread.report.chat?.turns.orEmpty().any { it !is ChatTurn.Asked || !it.routed }

/**
 * Whether the thread's question keeps "Another topic": its topic was chosen by the chat from a
 * mention in the person's words, and nothing has been answered since.
 */
private fun exitOffered(thread: ChatThread): Boolean {
    val from = thread.from ?: return false
    val chatStarted = from == ROUTE_WORDS || from.startsWith(ROUTE_HANDOVER) || from.startsWith(ROUTE_RESUME) ||
        from.startsWith(ROUTE_FOLLOW)
    val turns = thread.report.chat?.turns.orEmpty()
    val topicAt = turns.indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM }
    val topicTurn = turns.getOrNull(topicAt) as? ChatTurn.Asked ?: return false
    val answered = turns.drop(topicAt + 1).any { it is ChatTurn.Asked && !it.routed }
    return !chatStarted && topicTurn.routed && !answered
}

/** [this] with "Another topic" beside it, where the reply has one. */
internal fun ChatReply.withExit(): ChatReply = when (this) {
    is ChatReply.Topics -> copy(exit = true)

    is ChatReply.Choose -> copy(exit = true)

    is ChatReply.AreaChips -> copy(exit = true)

    is ChatReply.BodyMap -> copy(exit = true)

    is ChatReply.MoodDial -> copy(exit = true)

    is ChatReply.MedicinePick -> copy(exit = true)

    is ChatReply.Values -> copy(exit = true)

    is ChatReply.TextAsk -> copy(exit = true)

    ChatReply.Severity, ChatReply.End, is ChatReply.PainScale, is ChatReply.PainLasted, is ChatReply.TimesPick,
    is ChatReply.ReminderGates,
    -> this
}

/** Whether [this] reply carries "Another topic". */
internal val ChatReply.exitShown: Boolean
    get() = when (this) {
        is ChatReply.Topics -> exit
        is ChatReply.Choose -> exit
        is ChatReply.AreaChips -> exit
        is ChatReply.BodyMap -> exit
        is ChatReply.MoodDial -> exit
        is ChatReply.MedicinePick -> exit
        is ChatReply.Values -> exit
        is ChatReply.TextAsk -> exit
        else -> false
    }

/**
 * Between threads: the close of the thread that just ended, then the router's line and chips — only
 * the menu and the end after a "now" line, which already says what to do; the offer to talk after an
 * answer about suicide; what the words named, if anything is left; else "anything else?".
 */
internal fun promptTurn(log: ChatLog, setting: ChatSetting): BotTurn {
    val close = closeOf(log, log.threads.lastIndex, setting)
    val queue = chatQueue(log, setting)
    val (line, reply) = when {
        // Every thread's close ends on not waiting after a line to get advice now ([NOW_ALERTS]).
        close.any { it is BotPiece.Line && it.line == ChatLine.CLOSE_NOW } ->
            null to ChatReply.Topics(emptyList(), all = false, end = true)

        log.crisisInChat() ->
            ChatLine.CRISIS_TALK to ChatReply.Topics(listOfNotNull(talkChoice(log)), all = false, end = true)

        queue.isNotEmpty() -> queueLine(log, queue, setting) to ChatReply.Topics(queue, all = false, end = true)

        else -> ChatLine.ANYTHING_ELSE to ChatReply.Topics(chatMenu(log, setting), all = true, end = true)
    }
    return BotTurn(close + listOfNotNull(line?.let { BotPiece.Line(it) }), reply)
}

/**
 * The line over the queue: "you also wrote about this" where a chip of it names what the person's
 * words named; over follow-ups alone that no words named — the talk offered after a mood noted from
 * the menu — "anything else?", since nothing was written about them.
 */
private fun queueLine(log: ChatLog, queue: List<TopicChoice>, setting: ChatSetting): ChatLine {
    val written = log.threads.flatMapTo(HashSet()) { thread -> mentionsOf(thread, setting).map { it.key } }
    return if (queue.any { it.mention?.key in written }) ChatLine.ALSO_MENTIONED else ChatLine.ANYTHING_ELSE
}

/** "Talk about it": a low mood talk, unless one ran after the answer that brought the offer. */
private fun talkChoice(log: ChatLog): TopicChoice? {
    val first = log.threads.indexOfFirst { crisisAnswered(it.report.asTold()) }
    val talked = log.threads.drop(first.coerceAtLeast(0)).any { it.topic == Symptom.LOW_MOOD }
    return TopicChoice(Symptom.LOW_MOOD, labelRes = R.string.symptom_chat_talk_about_it).takeUnless { talked }
}

/**
 * The end: after "That is all for now" thanks — or, after any answer about suicide other than "no",
 * what to do if it gets worse, never a goodbye; after an emergency the stopping thread's close.
 */
private fun overTurn(log: ChatLog, setting: ChatSetting, end: ChatEnd): BotTurn = when (end) {
    ChatEnd.ALL -> BotTurn(
        listOf(BotPiece.Line(if (log.crisisInChat()) ChatLine.CLOSE_CRISIS else ChatLine.THANKS)),
        ChatReply.End,
    )

    ChatEnd.STOP -> BotTurn(closeOf(log, log.threads.lastIndex, setting), ChatReply.End)
}

/**
 * The topic question of the thread at [index], which has words and no topic: the router's reply to
 * them. The safety lines the words already called for come first; then, by what the words held, the
 * topics they named as chips with "Another topic" beside them, the reply to a question, a greeting or
 * thanks, the topics not offered here, or the menu. With no English or Russian word read, the line to
 * call for an emergency always stands.
 *
 * What the model's readings add never takes away what the rules say: a
 * topic or feeling unwell a reading proposed may change which reply this is — its chips, the unwell
 * question — and every safety piece of the rules' own reply to the same words stays beside it: the
 * line to call for an emergency of "found none", the notes of a medicine stopped on an earlier day
 * ([withRulesFloor], as for every turn of a thread).
 */
private fun topicTurn(log: ChatLog, index: Int, setting: ChatSetting): BotTurn {
    val thread = log.threads[index]
    val state = log.threadState(index, setting)
    val last = state.chat.turns.lastOrNull()
    val alert = turnBefore(state, SymptomStep.SYMPTOM).pieces.filterIsInstance<BotPiece.Alert>()
    // Where help is, before anything, for words of suicide — the rules', or a reading's flag.
    val crisis = last is ChatTurn.Wrote && (crisisIn(last.text) || last.read?.raises(SymptomStep.CRISIS_NOW) == true)
    val support = BotPiece.Line(ChatLine.CRISIS_SUPPORT).takeIf { crisis }
    val head = alert + listOfNotNull(support)
    val menu = ChatReply.Topics(chatMenu(log, setting), all = true, end = index > 0)
    val found = chatReader.read(messagesOf(thread.report.chat), setting)
    // "Another topic" left the chat's own start: the whole menu, with what the words still call for.
    if (thread.noRoute) {
        val safety = BotPiece.Line(ChatLine.SAFETY).takeUnless { found.language }
        return BotTurn(
            head + othersLines(found) + BotPiece.Line(ChatLine.ASK_TOPIC_AGAIN) + listOfNotNull(safety),
            menu,
        )
    }
    val narrow = lastNarrowed(thread.report.chat) ?: writtenNarrow(log, index, setting)
    // The records the message could become, as cards of the answer — never beside where help is, nor
    // in a chat a crisis answer has turned.
    val records = !found.crisis || saidNoSuchThoughts(thread.report.forSave())
    val cards = if (head.isEmpty() && records && !log.crisisInChat()) {
        journalCards(last, thread.id, messagesOf(thread.report.chat).lastIndex, setting.journal)
    } else {
        emptyList()
    }
    val (lead, reply) = narrowedReply(narrow, menu) ?: firstReply(log, thread, found, setting, menu, cards)
    val carded = lead.any { it is BotPiece.Journal }
    val safety = SAFETY_LINE.takeIf { !found.language && !carded && it !in head && it !in lead }
    return BotTurn(head + lead + listOfNotNull(safety), reply.menuOnce(menu, last, lead))
}

/**
 * The whole menu is offered once, at the opening: after the person's own words
 * a reply that would show all of it again shows only "Show all topics" — what the words named still
 * comes as its own chips. A greeting is met as the opening is, with the menu its words point to.
 */
private fun ChatReply.menuOnce(menu: ChatReply.Topics, last: ChatTurn?, lead: List<BotPiece>): ChatReply =
    if (this == menu && last is ChatTurn.Wrote && BotPiece.Line(ChatLine.OPENING) !in lead) {
        ChatReply.Topics(emptyList(), all = false, end = menu.end)
    } else {
        this
    }

/** A written answer narrows only the menu actually offered immediately before it, just as its chip does. */
private fun writtenNarrow(log: ChatLog, index: Int, setting: ChatSetting): ChatNarrow? {
    val turns = log.threads[index].report.chat?.turns.orEmpty()
    val narrow = (turns.lastOrNull() as? ChatTurn.Wrote)?.text?.let(::unwellAnswerIn) ?: return null
    val before = prefixLog(log, index, turns.lastIndex)
    val offered = (chatTurn(before, setting).reply as? ChatReply.Topics)?.narrow.orEmpty()
    return narrow.takeIf { it in offered }
}

/**
 * The first row of the router's table that holds for [found] ([topicTurn]) — after the menu a
 * narrowing chip of the unwell question narrowed ([narrowedReply]): a topic's own reply; the topics
 * the words named; then, for words that named none offered here, the unwell question or the lines
 * of a question, small talk or nothing found ([unnamedReply]). The [cards] of the records the message
 * could become stand in for "got it" of an everyday message and for "found none"; beside a topic the
 * chat takes up, only those its threads do not ask about themselves.
 */
private fun firstReply(
    log: ChatLog,
    thread: ChatThread,
    found: WordsFound,
    setting: ChatSetting,
    menu: ChatReply.Topics,
    cards: List<BotPiece.Journal>,
): Pair<List<BotPiece>, ChatReply> {
    val records = !found.crisis || saidNoSuchThoughts(thread.report.forSave())
    val named = found.mentions.filter { records || it.mention.topic.record == ChatRecord.SYMPTOM }
    val offered = named.filter { log.offered(it.mention.topic, setting) && !log.serves(it.mention) }
    val chips = offered.map { TopicChoice(it.mention.topic, it.mention) }.distinctBy { it.mention?.key }
        .sortedBy { queueRank(checkNotNull(it.mention)) }
    // A friendly line meets the words with the topics they named, not the whole menu again (the
    // menu stands once, at the opening).
    conversationLines(log, thread)?.let { return it.gotItOr(cards) to chips.namedOr(menu) }
    val others = othersLines(found)
    // A script's own reply reads only what is offered here and not taken up yet: never the mood after a
    // crisis answer, never a second talk about a low mood that already had one.
    val known = setting.copy(crisisInChat = log.crisisInChat())
    val open = found.copy(mentions = offered)
    val own = offered.firstNotNullOfOrNull { it.mention.topic.script.firstReply(it, open, known) }
    val line = when {
        chips.isEmpty() || found.question -> null
        chips.size == 1 || chips.all { it.topic.record == ChatRecord.SYMPTOM } -> ChatLine.MATCH
        else -> ChatLine.MATCH_SEVERAL
    }
    val beside = cards.filter { it.event.type !in CHAT_OWN_JOURNAL }
    return when {
        own != null -> (others + own.lead).withCards(beside) to own.reply

        line != null ->
            (others + BotPiece.Line(line)).withCards(beside) to ChatReply.Topics(chips, all = false, exit = true)

        else -> unnamedReply(log, thread, found, named, offered, chips, setting, menu, cards).let { (lines, reply) ->
            others + lines to reply
        }
    }
}

/** [this], the topics the words named, as their chips; [menu] where they named none. */
private fun List<TopicChoice>.namedOr(menu: ChatReply.Topics): ChatReply =
    if (isEmpty()) menu else ChatReply.Topics(this, all = false, exit = true)

/** "Got it" of an everyday message, [this], gives way to the [cards] of what it could be noted as. */
private fun List<BotPiece>.gotItOr(cards: List<BotPiece.Journal>): List<BotPiece> =
    if (cards.isNotEmpty() && this == listOf(BotPiece.Line(ChatLine.EVERYDAY_HEARD))) cards else this

/**
 * "Found none" of [this] reply gives way to the [cards] of what a reading found to note in the words,
 * and the line to call for an emergency goes with it ([SAFETY_LINE]).
 */
private fun List<BotPiece>.foundNoneOr(cards: List<BotPiece.Journal>): List<BotPiece> =
    if (cards.isNotEmpty() && firstOrNull() in FOUND_NONE) cards + drop(1) - SAFETY_LINE else this

private val FOUND_NONE = listOf(ChatLine.NO_MATCH, ChatLine.NO_MATCH_MODEL, ChatLine.NO_MATCH_AGAIN)
    .map { BotPiece.Line(it) }

/** [this] with [cards] after the safety pieces it opens with: where help is comes first, a question after the cards. */
private fun List<BotPiece>.withCards(cards: List<BotPiece>): List<BotPiece> {
    val at = indexOfFirst { !it.isSafety() }.takeIf { it >= 0 } ?: size
    return take(at) + cards + drop(at)
}

/**
 * The reply to words that named no topic offered here, or only beside a question to the chat: for
 * words of feeling unwell that named none at all, the unwell question ([unwellReply]); else the lines
 * of [talkLines], with the [chips] a question named beside them, or the [menu].
 */
private fun unnamedReply(
    log: ChatLog,
    thread: ChatThread,
    found: WordsFound,
    named: List<WordMention>,
    offered: List<WordMention>,
    chips: List<TopicChoice>,
    setting: ChatSetting,
    menu: ChatReply.Topics,
    cards: List<BotPiece.Journal>,
): Pair<List<BotPiece>, ChatReply> {
    if (chips.isEmpty()) unwellReply(found, unwellBefore(thread, setting), menu.end)?.let { return it }
    val asked = ChatReply.Topics(chips, all = false, exit = true).takeIf { found.question && chips.isNotEmpty() }
    return talkLines(log, thread, found, named, offered, setting).foundNoneOr(cards) to (asked ?: menu)
}

/**
 * Whether the message before the last one in [thread] was read as feeling unwell and nothing else:
 * the unwell question was asked, and the second time it is "a chip may be easier".
 */
private fun unwellBefore(thread: ChatThread, setting: ChatSetting): Boolean {
    val wrote = thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>()
    val before = wrote.dropLast(1).lastOrNull() ?: return false
    val found = chatReader.read(listOf(before), setting)
    return found.unwell && found.mentions.isEmpty()
}

/** Words about someone else: whose journal this is, and to call for them; a child, that the chat is for adults. */
private fun othersLines(found: WordsFound): List<BotPiece> = if (found.someoneElse) {
    listOfNotNull(BotPiece.Line(ChatLine.SOMEONE_ELSE), BotPiece.Line(ChatLine.ADULTS).takeIf { found.child })
} else {
    emptyList()
}

/**
 * The lines of a message that named no topic offered here: the reply to a question, a greeting,
 * thanks or "how are you?", being upset with the chat, the lines of topics not offered for this day,
 * or that nothing was found — the second time in a row, more plainly; the first time in a model's
 * words when a model read the message ([noMatchLine]).
 */
private fun talkLines(
    log: ChatLog,
    thread: ChatThread,
    found: WordsFound,
    named: List<WordMention>,
    offered: List<WordMention>,
    setting: ChatSetting,
): List<BotPiece> {
    val written = thread.report.chat?.words().orEmpty()
    val notOffered = named.filter { it !in offered }
        .flatMap { it.mention.topic.script.notOfferedPieces(it, written, setting) }
    val lines: List<ChatLine> = when {
        found.question -> listOf(ChatLine.QUESTION, ChatLine.SAFETY)

        found.smallTalk == SmallTalk.GREETING -> listOf(ChatLine.OPEN, ChatLine.OPENING)

        found.smallTalk == SmallTalk.THANKS -> listOf(ChatLine.WELCOME)

        found.smallTalk == SmallTalk.HOW_ARE_YOU -> listOf(ChatLine.HOW_ARE_YOU)

        found.smallTalk == SmallTalk.UPSET ->
            listOf(if (log.saidSorry(setting)) ChatLine.NO_MATCH_AGAIN else ChatLine.SORRY)

        notOffered.isNotEmpty() -> return notOffered.distinct() + BotPiece.Line(ChatLine.SAFETY)

        unmatchedBefore(thread, setting, log.crisisInChat()) -> listOf(ChatLine.NO_MATCH_AGAIN, ChatLine.SAFETY)

        else -> listOf(noMatchLine(lastMessage(thread)), ChatLine.SAFETY)
    }
    return lines.map { BotPiece.Line(it) } + found.extraLines
}

/** The last message written in [thread]: the one the router's reply answers. */
private fun lastMessage(thread: ChatThread): ChatTurn.Wrote? =
    thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>().lastOrNull()

/**
 * Whether "I am sorry this is not helping" was said once already in this chat: a message before the
 * last one, written where no topic was asked yet, was upset with the chat.
 */
private fun ChatLog.saidSorry(setting: ChatSetting): Boolean = threads
    .filter { it.topic == null }
    .flatMap { thread -> thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>() }
    .dropLast(1)
    .any { chatReader.read(listOf(it), setting).smallTalk == SmallTalk.UPSET }

/**
 * Whether the message before the last one in [thread] matched nothing either: "no match" twice in a
 * row. One a record card answered was not "no match" — and in a chat a crisis answer has turned, where
 * no card is drawn ([crisis]), it was.
 */
private fun unmatchedBefore(thread: ChatThread, setting: ChatSetting, crisis: Boolean): Boolean {
    val wrote = thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>()
    val before = wrote.dropLast(1).lastOrNull() ?: return false
    return chatReader.read(listOf(before), setting).mentions.isEmpty() &&
        (crisis || journalProposals(before, setting.journal).isEmpty())
}

/**
 * The chat as it was said, from its stored threads. Before each of the person's turns the bot's turn
 * that asked for it: the opening before the first thread; the prompt of the thread before for one
 * the person began; nothing of its own for a thread the chat began itself, whose first question
 * carries the close of the thread before. Within a thread, the turn worked out with the step that was
 * stored, so a chat reads the same in a later build. A turn the chat answered itself has no bubble.
 * Then the last item: the end, the emergency's close, the live turn, what a chat left mid-question
 * called for and that it ended early, or the close it was left at.
 *
 * Append-only across threads: a new thread only adds items, a rewind only takes the last thread's
 * away, so an item keeps its place in the list.
 */
internal fun chatTranscript(log: ChatLog, setting: ChatSetting, live: Boolean): List<ChatItem> {
    if (log.threads.isEmpty()) return listOf(ChatItem.Bot(openingTurn(log, setting)))
    val items = mutableListOf<ChatItem>()
    log.threads.indices.forEach { index ->
        val before = when {
            index == 0 -> openingTurn(log.copy(threads = emptyList()), setting)
            log.appended(index) -> null
            else -> promptTurn(log.copy(threads = log.threads.take(index), end = null), setting)
        }
        val carried = if (log.appended(index)) closeOf(log, index - 1, setting) else emptyList()
        threadItems(log, index, setting, before, carried, items)
    }
    // A close still carried by a thread the chat began and nobody has answered in yet is the last
    // thread's, and its turn says it ([askTurn]).
    return items + lastItems(log, setting, live)
}

/** The items of the thread at [index], into [items]; [carried] goes in front of its first bot turn. */
private fun threadItems(
    log: ChatLog,
    index: Int,
    setting: ChatSetting,
    before: BotTurn?,
    carried: List<BotPiece>,
    items: MutableList<ChatItem>,
) {
    val thread = log.threads[index]
    val turns = thread.report.chat?.turns.orEmpty()
    var pending = carried
    fun bot(turn: BotTurn) {
        items += ChatItem.Bot(BotTurn(pending + turn.pieces, turn.reply), thread.id)
        pending = emptyList()
    }
    turns.forEachIndexed { position, turn ->
        val first = position == 0
        when (turn) {
            is ChatTurn.Said -> bot(BotTurn(listOf(BotPiece.Said(turn.text)), ChatReply.End))

            is ChatTurn.Asked -> {
                val said = turns.getOrNull(position - 1) is ChatTurn.Said
                when {
                    first -> before?.let(::bot)
                    !turn.routed && !said -> bot(turnAt(log, index, position, turn.step, setting))
                }
                if (!turn.routed) items += ChatItem.Person(position, turn.step, null, thread.id)
            }

            is ChatTurn.Wrote, is ChatTurn.Narrowed -> {
                if (first) {
                    before?.let(::bot)
                } else {
                    val step =
                        pendingAt(thread.report, position, setting.env.courses)
                            ?: nextChatStep(prefixState(log, index, position, setting))
                    bot(turnAt(log, index, position, step, setting))
                }
                val text = (turn as? ChatTurn.Wrote)?.text
                val narrowed = (turn as? ChatTurn.Narrowed)?.narrow
                items += ChatItem.Person(position, null, text, thread.id, narrowed = narrowed)
            }
        }
    }
}

/**
 * The bot's turn before turn [position] of the thread at [index], asking [step] (null: its close); the
 * first question of a start the chat chose from words with what that start owes ([withRoutedStart]);
 * with the rules' safety pieces of that place beside what the readings added, as the live turn had
 * them ([withRulesFloor]).
 */
private fun turnAt(log: ChatLog, index: Int, position: Int, step: SymptomStep?, setting: ChatSetting): BotTurn =
    log.withRulesFloor(index, position, setting, plainTurnAt(log, index, position, step, setting))

/** [turnAt] as [log] has it, without the rules' floor. */
private fun plainTurnAt(log: ChatLog, index: Int, position: Int, step: SymptomStep?, setting: ChatSetting): BotTurn {
    val prefix = prefixLog(log, index, position)
    if (step == SymptomStep.SYMPTOM && prefix.threads[index].topic == null) return topicTurn(prefix, index, setting)
    val turn = turnBefore(prefix.threadState(index, setting), step)
    return if (step == null) turn else log.withRoutedStart(index, position, setting, turn)
}

/** The log as it stood before turn [position] of the thread at [index]: the threads before it, that thread so far. */
private fun prefixLog(log: ChatLog, index: Int, position: Int): ChatLog {
    val thread = log.threads[index]
    val chat = thread.report.chat ?: SymptomChat()
    val told = thread.report.copy(chat = chat.copy(turns = chat.turns.take(position), ended = false))
    return log.copy(threads = log.threads.take(index) + thread.copy(report = told), end = null)
}

private fun prefixState(log: ChatLog, index: Int, position: Int, setting: ChatSetting): ChatState =
    prefixLog(log, index, position).threadState(index, setting)

/**
 * The question that was waiting when the message at [position] was written: the next one answered
 * after it, unless that is one the message itself — or a later one — brought on by its words.
 */
private fun pendingAt(report: SymptomReport, position: Int, courses: List<Medication>): SymptomStep? {
    val chat = report.chat ?: return null
    val before = wordSteps(chat.copy(turns = chat.turns.take(position)), report, courses).toSet()
    val brought = wordSteps(chat, report, courses).toSet() - before
    return chat.turns.drop(position).firstNotNullOfOrNull { turn ->
        (turn as? ChatTurn.Asked)?.step?.takeIf { it !in brought && !turn.routed }
    }
}

/**
 * The transcript's last items: after "That is all for now" the prompt it answered, the person's
 * bubble and the end; after an emergency its thread's close; the live turn; for a chat left
 * mid-question what it called for and that it ended early; or the close it was left at.
 */
private fun lastItems(log: ChatLog, setting: ChatSetting, live: Boolean): List<ChatItem> {
    val last = log.threads.last()
    return when {
        log.end == ChatEnd.ALL -> listOf(
            ChatItem.Bot(promptTurn(log.copy(end = null), setting)),
            ChatItem.Person(last.report.chat?.turns?.size ?: 0, null, null, last.id, end = true),
            ChatItem.Bot(chatTurn(log, setting)),
        )

        log.end == ChatEnd.STOP -> listOf(ChatItem.Bot(chatTurn(log, setting), last.id))

        live -> listOf(ChatItem.Bot(chatTurn(log, setting), last.id.takeUnless { last.ended } ?: -1))

        !last.ended -> listOf(ChatItem.Bot(leftEarly(chatTurn(log, setting)), last.id))

        else -> listOf(ChatItem.Bot(BotTurn(closeOf(log, log.threads.lastIndex, setting), ChatReply.End), last.id))
    }
}

/**
 * The threads whose answers carry the pencil, by id: the last thread's, and while the chest thread
 * a pain thread handed over to has no answer of its own, that pain thread's too — "I marked the
 * chest by mistake" is one pencil away. Never a thread with a record changed on its own page, nor
 * one that changed a record already there and can no longer put it back ([cannotPutBack]).
 */
internal fun pencilThreads(log: ChatLog): Set<Int> {
    val last = log.threads.lastOrNull() ?: return emptySet()
    val handedFrom = last.from?.takeIf { it.startsWith(ROUTE_HANDOVER) && !ownTurns(last) }
        ?.removePrefix(ROUTE_HANDOVER)?.toIntOrNull()
    return setOfNotNull(last.id, handedFrom)
        .filter { id ->
            log.threads.first { it.id == id }.filed.none { it.state == LinkState.CHANGED || cannotPutBack(it) }
        }
        .toSet()
}

/**
 * Whether [link] is a change the chat made to a record already there — a mood updated, a pain's
 * painkiller answer — whose earlier state is gone, the chat having been sealed: taking its answers
 * back would drop the link and leave the record changed, so neither the pencil nor Undo is offered.
 */
private fun cannotPutBack(link: FiledLink): Boolean = link.state == LinkState.FILED && !link.made && link.prior == null

/**
 * [change] on the last thread — a tap, ticks taken, a value, Send, typed words — and, for the answer
 * it took, the facts frozen into the thread then ([frozenFacts]): what its script reads later about
 * that moment, and the medicines an answer about suicide is read with. A rule that reads those facts
 * fires on that very answer, its line under it.
 */
internal fun ChatLog.answeredLast(
    setting: ChatSetting,
    freeze: (ChatState, SymptomStep) -> Map<String, String> = ::frozenFacts,
    change: (SymptomReport) -> SymptomReport,
): ChatLog {
    val last = threads.lastOrNull() ?: return this
    val before = last.report.chat?.turns?.size ?: 0
    val changed = updateLast(change)
    val turn = changed.threads.last().report.chat?.turns?.getOrNull(before) as? ChatTurn.Asked
    val prefix = turn?.step?.name + "."
    val facts = turn?.let { freeze(changed.threadState(changed.threads.lastIndex, setting), it.step) }.orEmpty()
        .filterKeys { it.startsWith(prefix) || it.startsWith(THREAD_META_PREFIX) }
    return if (facts.isEmpty()) {
        changed
    } else {
        changed.updateLast { report ->
            report.copy(meta = report.meta.filterKeys { !it.startsWith(prefix) } + facts).realerted(last.report)
        }
    }
}

/** What is frozen when [step] is answered: its script's facts, and for a crisis question the medicines' ones. */
internal fun frozenFacts(state: ChatState, step: SymptomStep): Map<String, String> {
    val own = stepScript(state, step).freeze(state, step)
    if (step != SymptomStep.CRISIS && step != SymptomStep.CRISIS_NOW) return own
    val medicines = medicineCrisisFacts(state.thread.env, state.thread.today)
    return own + medicines.mapKeys { (key, _) -> step.name + "." + key }
}

/**
 * Ticks on [step], waiting for the clock — or, when [option] stands alone and nothing else is ticked,
 * taken on the tap: "None of these" waits for nothing. Second: whether it was taken.
 */
internal fun ChatLog.tickedLast(
    step: SymptomStep,
    option: SymptomOption,
    setting: ChatSetting,
): Pair<ChatLog, Boolean> {
    val chosen = threads.lastOrNull()?.report?.answers?.get(step).orEmpty()
    val ticked = toggledOption(chosen, option)
    val ticking = updateLast {
        it.copy(
            answers = it.answers + (step to ticked),
            values = if (step.kind.holds == StepHolds.OPTIONS) it.values - step else it.values,
        )
    }
    if (chosen.isNotEmpty() || option !in EXCLUSIVE_OPTIONS || ticked != listOf(option)) return ticking to false
    return ticking.answeredLast(setting) { it.answered(step) } to true
}

/**
 * Words typed as the answer to [step]. Words of suicide or of an emergency are not taken: they are a
 * message, and their question comes first, the answer asked for again after it. So are words of too
 * much of a medicine ([toldTooMuch]) — "I took all my pills" as what a low mood is about — wherever
 * their own reading never reaches: never in an amount or a course's dose, which are amounts and
 * which their "more than it says?" follows, nor in a name or who prescribed it, whose words the
 * medicine's questions read as typed ([TYPED_READ_STEPS]). Anything else is the answer, kept as typed.
 *
 * [read] is what the model's reading of the words decided, made as they were sent,
 * and only a flag whose question is still to come in the thread counts ([flagDue]): the
 * same words typed again, read the same, are then the answer, never a message for ever. A flag makes
 * the words a message just as the rules' words do, kept with the reading, whose question then comes
 * first — except too much of a medicine where the rules read the typed words for it themselves: in a
 * name or who prescribed it the answer is taken with its reading kept on it ([ChatTurn.Asked.read]),
 * which the medicine's questions read beside the words ([wordsQuestions]); in an amount, whose own
 * "more than it says?" follows, not at all.
 */
internal fun ChatLog.typedLast(step: SymptomStep, text: String, setting: ChatSetting, read: ChatRead? = null): ChatLog {
    val report = threads.lastOrNull()?.report
    val readsTooMuch = step !in TYPED_AMOUNT_STEPS && step !in TYPED_READ_STEPS
    val tooMuch = report != null && readsTooMuch && toldTooMuch(text, report, setting.env.courses)
    val due = read?.takeIf { it.routes }?.safety.orEmpty().filter { report?.flagDue(it) != false }
    val flagged = due.any { it != SymptomStep.MED_MAX_WORDS || readsTooMuch }
    val urgent = crisisIn(text) || emergencyIn(text).isNotEmpty()
    return if (urgent || tooMuch || flagged) {
        wrote(text, read)
    } else {
        val kept = read?.takeIf { step in TYPED_READ_STEPS && SymptomStep.MED_MAX_WORDS in due }
        answeredLast(setting) { it.typed(step, text).withAnswerRead(step, kept) }
    }
}

/**
 * Whether a reading's flag of [step] still brings its question in [this] thread: one not asked yet,
 * or for too much of a medicine, "more than it says?" answered "no" with its second asking still to
 * come ([wordsQuestions]).
 */
private fun SymptomReport.flagDue(step: SymptomStep): Boolean {
    val asked = chat?.askedSteps().orEmpty()
    return step !in asked ||
        (
            step == SymptomStep.MED_MAX_WORDS && has(SymptomStep.MED_MAX_WORDS, SymptomOption.NO) &&
                SymptomStep.MED_MAX_AGAIN !in asked
            )
}

/** [this] with [read] kept on the turn that just answered [step] — the words typed as its answer. */
private fun SymptomReport.withAnswerRead(step: SymptomStep, read: ChatRead?): SymptomReport {
    val told = chat ?: return this
    val last = told.turns.lastOrNull() as? ChatTurn.Asked
    if (read == null || last?.step != step) return this
    return copy(chat = told.copy(turns = told.turns.dropLast(1) + last.copy(read = read)))
}

/** The typed answers that are amounts: a dose taken, the dose a prescription says. */
private val TYPED_AMOUNT_STEPS = setOf(SymptomStep.MED_AMOUNT, SymptomStep.COURSE_DOSE)

/** The question of several answers, or the value, the clock waits on in the live turn: none for a tap. */
internal fun waitingStep(log: ChatLog, setting: ChatSetting): SymptomStep? =
    (chatMove(log, setting) as? ChatMove.Ask)?.step?.takeIf { it.kind.answer == AnswerKind.SETTLE }

/** What the clock takes: the ticks or the value on [step], only while it is still the question asked. */
internal fun ChatLog.takenLast(step: SymptomStep, setting: ChatSetting): ChatLog {
    if (threads.isEmpty()) return this
    val state = threadState(threads.lastIndex, setting)
    return answeredLast(setting) { it.ticksTaken(step, state) }
}

/** [draft] as the last thread's painting not yet sent, kept with the chat as the page stops. */
internal fun ChatLog.withDraft(draft: MapDraft): ChatLog {
    val last = threads.lastOrNull() ?: return this
    return copy(threads = threads.dropLast(1) + last.copy(draft = draft))
}

/** The thread whose area question is the live one, by its id: a pain thread's map is open for it. */
internal fun areaQuestionThread(log: ChatLog, setting: ChatSetting): Int? =
    (chatMove(log, setting) as? ChatMove.Ask)?.takeIf { it.step == SymptomStep.PAIN_AREA }?.thread

/**
 * The map of thread [thread] as painted so far — its [strokes], and the areas they and the lists mark
 * — waiting for Send in that thread and kept as the page stops; null, nothing written, unless the area
 * question of [thread] is the one asked now. A map still folding away after its Send writes nothing:
 * neither into the answer it sent nor into the chest's thread the chest handed over to.
 */
internal fun ChatLog.mapPainted(
    thread: Int?,
    strokes: List<PainStroke>,
    added: List<String>,
    removed: List<String>,
    setting: ChatSetting,
): ChatLog? {
    if (thread == null || areaQuestionThread(told(), setting) != thread) return null
    val step = SymptomStep.PAIN_AREA
    return updateLast { it.painted(step, strokes).valued(step, chatMapCodes(strokes, added, removed)) }
        .withDraft(MapDraft(strokes, added, removed))
}

/**
 * The map's Send: the area question of thread [thread] answered with what the map holds — once, while
 * it is the one asked; null after, so a second tap on Send asks it no second time.
 */
internal fun ChatLog.mapSent(thread: Int?, setting: ChatSetting): ChatLog? =
    if (thread != null && areaQuestionThread(told(), setting) == thread) {
        answeredLast(setting) { it.sent(SymptomStep.PAIN_AREA) }
    } else {
        null
    }

/**
 * The log as its turns tell it: no tick, value or painting still waiting, no unsent drawing — what
 * the bot's words are worked out from, so a tick or a stroke never makes the bot speak again.
 */
internal fun ChatLog.told(): ChatLog = copy(
    threads = threads.map {
        it.copy(report = it.report.forSave(), draft = null)
    },
)

/**
 * The key of the list item that holds the live turn's reply: its thread and question, or the router's
 * prompt counted by the threads before it; none once the chat is over. The same question asked again
 * keeps its key — a message written during it, the dots after it — so its chips stand as they were.
 */
internal fun replyKey(log: ChatLog, setting: ChatSetting): String? = when (val move = chatMove(log, setting)) {
    is ChatMove.Ask -> "reply:${move.thread}:${move.step.name}"
    ChatMove.Opening, ChatMove.Prompt -> "reply:prompt:${log.threads.size}"
    is ChatMove.Over -> null
}

/**
 * Whether [this] reply folds away once its question is taken, as the sent map does, rather than leave
 * at once: a tall widget — the mood's dial.
 */
internal val ChatReply.foldsAway: Boolean get() = this is ChatReply.MoodDial

/**
 * Whether [reply] draws anything in the reply's own item: never the end, nor the body map — its own
 * item further down — or a typed answer with no chip to tap, unless "Another topic" stands under it.
 */
internal fun replyDrawn(reply: ChatReply): Boolean = when (reply) {
    ChatReply.End -> false
    is ChatReply.BodyMap -> reply.exitShown
    is ChatReply.TextAsk -> reply.suggestions.isNotEmpty() || reply.exitShown
    else -> true
}

/**
 * Whether a record's centre flash may stand now: never over a line to call, where help is or to get
 * advice now — the turn [shown], or the one [log] asks next — never once any answer about suicide in
 * the chat was other than "no", and never after an emergency, where it would cover what to do and read
 * as "done".
 */
internal fun recordFlashAllowed(log: ChatLog, setting: ChatSetting, shown: BotTurn?): Boolean =
    !log.crisisInChat() && log.end != ChatEnd.STOP && log.threads.none(::stopped) &&
        shown?.holdsFlashBack() != true && !chatTurn(log, setting).holdsFlashBack()

/**
 * As above, for [link]'s record in [thread] — its first write, a rewrite, an Undo or a Save again on
 * its card: never the row the filer keeps the person's answers to the words' own questions in
 * ([WORDS_ROLE]) — no record the person asked for, titled with their own first words, and filed as
 * a "now" or a crisis question is answered — and only where its topic says it with the flash at all
 * ([TopicScript.flashes]): no record after a line to get advice now anywhere in the chat, where "added"
 * would read as "all is well".
 */
internal fun recordFlashAllowed(
    log: ChatLog,
    setting: ChatSetting,
    shown: BotTurn?,
    thread: ChatThread,
    link: FiledLink,
): Boolean = link.role != WORDS_ROLE && recordFlashAllowed(log, setting, shown) &&
    thread.topic?.script?.flashes(log, thread, link) != false

/**
 * Whether a line that says to get advice now ([NOW_ALERTS]) fired anywhere in the chat: a record's
 * "added" flash after it would read as "all is well", in whichever thread the record is filed.
 */
internal fun nowLineInChat(log: ChatLog): Boolean = log.threads.any { nowLineFired(it.report.asTold()) }

/**
 * A turn no flash stands over: one that is urgent ([urgent]), or one that says to get advice now and
 * not wait — a "now" alert ([NOW_ALERTS]), or the close after it.
 */
private fun BotTurn.holdsFlashBack(): Boolean = urgent() || pieces.any {
    (it is BotPiece.Alert && it.alert in NOW_ALERTS) ||
        (it is BotPiece.Line && it.line == ChatLine.CLOSE_NOW)
}
