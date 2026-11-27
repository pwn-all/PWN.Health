package com.pwnall.health

// A chat driven by hand, the way the screen drives it: the next step, the turn that asks it, the
// person's answer, and the chat marked ended once nothing is left — shared by the chat's tests.

internal fun newChat() = SymptomReport(chat = SymptomChat())

internal fun chatState(
    report: SymptomReport,
    female: Boolean = false,
    earlier: EarlierChat? = null,
    adult: Boolean = true,
) = ChatState(report.asTold(), female, earlier, adult = adult)

internal fun SymptomReport.nextStep(earlier: EarlierChat? = null): SymptomStep? = nextChatStep(
    chatState(this, earlier = earlier),
)

internal fun SymptomReport.turn(earlier: EarlierChat? = null): BotTurn {
    val state = chatState(this, earlier = earlier)
    return turnBefore(state, nextChatStep(state))
}

private fun SymptomReport.settle(earlier: EarlierChat?, female: Boolean = false) =
    settled(chatState(this, female, earlier), this)

internal fun SymptomReport.pick(topic: Symptom, earlier: EarlierChat? = null): SymptomReport =
    chose(topic).settle(earlier)

/** One answer to [step]: the options ticked for a question of several, the one tapped otherwise. */
internal fun SymptomReport.say(
    step: SymptomStep,
    vararg options: SymptomOption,
    earlier: EarlierChat? = null,
): SymptomReport {
    val next = if (step.multi) {
        copy(answers = answers + (step to options.toList())).answered(step)
    } else {
        tapped(step, options.single(), earlier)
    }
    return next.settle(earlier)
}

internal fun SymptomReport.rate(severity: SymptomSeverity, earlier: EarlierChat? = null): SymptomReport =
    rated(severity).settle(earlier)

internal fun SymptomReport.write(text: String, earlier: EarlierChat? = null): SymptomReport = wrote(
    text,
).settle(earlier)

/** The calm answer: none of these, no, else the first offered. */
internal fun calm(options: List<SymptomOption>): List<SymptomOption> = listOf(
    when {
        SymptomOption.NONE in options -> SymptomOption.NONE
        SymptomOption.NO in options -> SymptomOption.NO
        else -> options.first()
    },
)

/**
 * [topic]'s chat driven to its end: every question answered by [choose], how bad by [severity].
 * The steps asked, in order, and the report it ended on. Fails past [limit] answers.
 */
internal fun driveChat(
    topic: Symptom,
    female: Boolean = false,
    earlier: EarlierChat? = null,
    severity: SymptomSeverity = SymptomSeverity.MILD,
    limit: Int = 30,
    choose: (SymptomStep, List<SymptomOption>) -> List<SymptomOption> = { _, options -> calm(options) },
): Pair<List<SymptomStep>, SymptomReport> {
    var report = newChat()
    val asked = mutableListOf<SymptomStep>()
    repeat(limit) {
        val state = chatState(report, female, earlier)
        val step = nextChatStep(state) ?: return asked to report
        asked += step
        report = answer(report, state, step, turnBefore(state, step).reply, topic, severity, earlier, choose)
        report = settled(chatState(report, female, earlier), report)
    }
    error("$topic did not end within $limit answers: $asked")
}

/** A fixture dab on the body map, where the knee is on the front plate. */
private val DRIVER_DAB = PainStroke(PainView.FRONT, 0.03f, listOf(androidx.compose.ui.geometry.Offset(0.6f, 0.62f)))

/**
 * One answer to [reply], the same every time: the topic, how bad, [choose]'s options, the first area,
 * chip, choice or suggestion, a dab on the map, a middle value on a ruler or the dial.
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList") // One branch per kind of reply: the driver answers them all.
internal fun answer(
    report: SymptomReport,
    state: ChatState,
    step: SymptomStep,
    reply: ChatReply,
    topic: Symptom,
    severity: SymptomSeverity = SymptomSeverity.MILD,
    earlier: EarlierChat? = null,
    choose: (SymptomStep, List<SymptomOption>) -> List<SymptomOption> = { _, options -> calm(options) },
): SymptomReport = when (reply) {
    is ChatReply.Topics -> report.chose(topic)

    ChatReply.Severity -> report.rated(severity)

    is ChatReply.Choose -> {
        val chosen = choose(step, reply.options)
        if (step.multi) {
            report.copy(answers = report.answers + (step to chosen)).answered(step)
        } else {
            report.tapped(step, chosen.first(), earlier)
        }
    }

    is ChatReply.AreaChips -> report.valued(
        step,
        reply.choices.first().areas.joinToString(",") {
            it.code
        },
    ).answered(step)

    is ChatReply.BodyMap -> report.painted(step, listOf(DRIVER_DAB.copy(view = reply.frame?.view ?: PainView.FRONT)))
        .valued(step, reply.headChips.firstOrNull()?.code ?: "KNEE:R").sent(step)

    is ChatReply.PainScale -> report.valued(step, "5").answered(step)

    is ChatReply.PainLasted -> report.valued(step, "60").answered(step)

    is ChatReply.MoodDial -> report.valued(step, "3").answered(step)

    is ChatReply.MedicinePick -> report.valued(step, "C${reply.choices.first().createdAt}").answered(step)

    is ChatReply.Values -> report.valued(step, reply.choices.first().value).answered(step)

    is ChatReply.TextAsk -> report.typed(step, reply.suggestions.firstOrNull()?.value ?: "x")

    is ChatReply.TimesPick -> report.valued(step, "480").answered(step)

    is ChatReply.ReminderGates -> report.tapped(step, stepScript(state, step).options(state, step).first(), earlier)

    ChatReply.End -> error("asked $step with nothing to answer")
}

/** What a whole chat is driven with: the chip tapped at each topic question, in turn, then "That is all for now". */
internal data class ThreadPlan(val topic: Symptom, val severity: SymptomSeverity = SymptomSeverity.MILD)

/** A setting for the router's tests: today, an adult, nothing in the journal. */
internal fun chatSetting(
    past: Boolean = false,
    female: Boolean = false,
    adult: Boolean = true,
    env: ChatEnv = ChatEnv(),
    sittingFrom: Int = 0,
) = ChatSetting(
    female = female,
    adult = adult,
    day = if (past) CHAT_TODAY.minusDays(3) else CHAT_TODAY,
    past = past,
    now = CHAT_NOW,
    chatMoment = CHAT_NOW,
    snapshot = null,
    env = env,
    zone = CHAT_ZONE,
    sittingFrom = sittingFrom,
    language = ChatLanguage.EN,
)

/** [log] after one commit, as the page makes it: through the router's settle. */
internal fun ChatLog.settle(setting: ChatSetting = chatSetting()): ChatLog = settledLog(this, setting)

/**
 * A whole chat driven through the router: at each topic question the plan's next topic, every
 * other question answered calmly, and "That is all for now" once the plan is done. The log it
 * ended on. Fails past [limit] commits.
 */
internal fun driveLog(plan: List<ThreadPlan>, setting: ChatSetting = chatSetting(), limit: Int = 200): ChatLog {
    var log = ChatLog(past = setting.past)
    var left = plan
    repeat(limit) {
        val move = chatMove(log, setting)
        log = when {
            move is ChatMove.Over -> return log

            move == ChatMove.Opening || move == ChatMove.Prompt -> left.firstOrNull()?.let { next ->
                left = left.drop(1)
                log.choseTopic(TopicChoice(next.topic), setting)
            } ?: log.endedByPerson()

            else -> {
                val ask = move as ChatMove.Ask
                val state = log.threadState(log.threads.lastIndex, setting)
                val reply = chatTurn(log, setting).reply
                val topic = log.threads.last().topic ?: left.first().topic
                log.answeredLast(setting) {
                    answer(
                        it,
                        state,
                        ask.step,
                        reply,
                        topic,
                        plan.firstOrNull()?.severity ?: SymptomSeverity.MILD,
                    )
                }
            }
        }.settle(setting)
    }
    error("the chat did not end within $limit commits")
}

internal fun BotTurn.lines(): List<ChatLine> = pieces.filterIsInstance<BotPiece.Line>().map { it.line }

internal fun BotTurn.alerts(): List<SymptomAlert> = pieces.filterIsInstance<BotPiece.Alert>().map { it.alert }

internal fun BotTurn.offers(): List<ChatOffer> = pieces.filterIsInstance<BotPiece.Offer>().map { it.offer }

internal fun BotTurn.asked(): SymptomStep? = pieces.filterIsInstance<BotPiece.Ask>().singleOrNull()?.step
