package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.time.LocalDate

// A medicine thread driven through the router the way the page drives it — every answer committed
// with its frozen facts, the medicine clock held at a moment the test names — and filed through the
// real doors over the in-memory database.

/** [minute] of [CHAT_TODAY] in [CHAT_ZONE]; noon is [CHAT_NOW]. */
internal fun todayAt(minute: Int, day: LocalDate = CHAT_TODAY): Long =
    day.atStartOfDay(CHAT_ZONE).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

/**
 * A course as the Medicine tab writes one: [times] in minutes, written down [createdDaysAgo] days before [CHAT_TODAY].
 */
internal fun course(
    name: String,
    id: Long = 1,
    times: String = "540",
    dose: String = "50 mg",
    start: LocalDate = CHAT_TODAY.minusDays(10),
    end: LocalDate? = null,
    createdDaysAgo: Long = 10,
    schedule: MedSchedule = MedSchedule.CLOCK,
) = Medication(
    id = id,
    name = name,
    dose = dose,
    schedule = schedule.name,
    times = times,
    startDay = start.toString(),
    endDay = end?.toString(),
    createdAt = todayAt(9 * 60, CHAT_TODAY.minusDays(createdDaysAgo)) + id,
)

/** One medicine chat, answered step by step; [now] is the medicine clock's moment for the next answer. */
internal class MedicineChat(var env: ChatEnv = ChatEnv(), val past: Boolean = false) {
    var now: Long = CHAT_NOW
    val setting: ChatSetting get() = chatSetting(past = past, env = env).copy(now = now)
    var log = ChatLog(past = past)

    init {
        MedicineClock.now = { now }
        MedicineClock.zone = { CHAT_ZONE }
    }

    fun start(topic: Symptom, from: TopicMention? = null): MedicineChat = apply {
        log = log.choseTopic(TopicChoice(topic, from), setting).settle(setting)
    }

    /** What the last thread asks now, or null when its questions are over. */
    val step: SymptomStep? get() = (chatMove(log, setting) as? ChatMove.Ask)?.step

    val turn: BotTurn get() = chatTurn(log, setting)
    val reply: ChatReply get() = turn.reply
    val state: ChatState get() = log.threadState(log.threads.lastIndex, setting)
    val report: SymptomReport get() = log.threads.last().report

    private fun asked(expected: SymptomStep?): SymptomStep {
        val step = checkNotNull(step) { "nothing is asked; expected $expected" }
        expected?.let { assertEquals(it, step) }
        return step
    }

    fun tap(option: SymptomOption, expected: SymptomStep? = null): MedicineChat = apply {
        val step = asked(expected)
        log = log.answeredLast(setting) { it.tapped(step, option, null) }.settle(setting)
    }

    fun tick(vararg options: SymptomOption, expected: SymptomStep? = null): MedicineChat = apply {
        val step = asked(expected)
        log = log.answeredLast(setting) { it.copy(answers = it.answers + (step to options.toList())).answered(step) }
            .settle(setting)
    }

    fun value(value: String, expected: SymptomStep? = null): MedicineChat = apply {
        val step = asked(expected)
        log = if (step.kind.answer == AnswerKind.TAP) {
            log.answeredLast(setting) { it.valued(step, value).answered(step) }
        } else {
            log.updateLast { it.valued(step, value) }.takenLast(step, setting)
        }.settle(setting)
    }

    fun type(text: String, expected: SymptomStep? = null): MedicineChat = apply {
        val step = asked(expected)
        log = log.typedLast(step, text, setting).settle(setting)
    }

    fun write(text: String): MedicineChat = apply { log = log.wrote(text).settle(setting) }

    /** The steps the last thread asked, in order. */
    val steps: List<SymptomStep>
        get() = report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>().map { it.step } - SymptomStep.SYMPTOM

    /** Every bot piece of the transcript so far, for the last thread. */
    val closing: List<BotPiece> get() = turn.pieces

    /** The chat filed as the page files it, its links taken back into the log; [today] is the date of that act. */
    fun file(filing: ChatFiling, key: String, today: LocalDate = CHAT_TODAY): FilingResult {
        val ctx = FilingContext(
            FilingClock(now, today, CHAT_ZONE),
            setting.day,
            past,
            key,
            log,
            env,
            medicineStrings,
        )
        val result = runBlocking { checkNotNull(filing.persist(log, ctx)) }
        log = log.withLinks(result)
        return result
    }
}

/** The strings a row is written with, in English. */
internal val medicineStrings: (Int) -> String = { res ->
    when (res) {
        R.string.med_chat_amount_one_tablet -> "1 tablet"
        R.string.med_chat_amount_two_tablets -> "2 tablets"
        R.string.med_chat_amount_two_of -> "2 × %1\$s"
        R.string.med_chat_unnamed -> "Medicine"
        R.string.med_chat_note_prescribed -> "Prescribed by: %1\$s"
        R.string.med_chat_who_gp -> "A GP"
        else -> chatStrings()(res)
    }
}
