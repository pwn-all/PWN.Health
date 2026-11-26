package com.pwnall.health

import androidx.compose.ui.geometry.Offset

// A pain thread driven through the router as the page drives it — a chip tapped, ticks taken, a
// value settled, the map sent — shared by the pain thread's tests.

/** The question the live turn asks. */
internal fun ChatLog.asked(setting: ChatSetting = chatSetting()): SymptomStep =
    (chatMove(this, setting) as? ChatMove.Ask)?.step ?: error("nothing asked: ${chatMove(this, setting)}")

/** The live question answered with one tap on [option]. */
internal fun ChatLog.tapOn(option: SymptomOption, setting: ChatSetting = chatSetting()): ChatLog {
    val step = asked(setting)
    return answeredLast(setting) { it.tapped(step, option, null) }.settle(setting)
}

/** The live question of several answers given [options], taken as the clock takes them. */
internal fun ChatLog.tick(vararg options: SymptomOption, setting: ChatSetting = chatSetting()): ChatLog {
    val step = asked(setting)
    return answeredLast(setting) { it.copy(answers = it.answers + (step to options.toList())).answered(step) }
        .settle(setting)
}

/** The live question answered with [value]: a ruler settled, a chip of areas tapped. */
internal fun ChatLog.give(value: String, setting: ChatSetting = chatSetting()): ChatLog {
    val step = asked(setting)
    return answeredLast(setting) { it.valued(step, value).answered(step) }.settle(setting)
}

/** The area question answered on the map: [strokes] sent, recording what they and the lists mark. */
internal fun ChatLog.paint(
    strokes: List<PainStroke>,
    added: List<String> = emptyList(),
    removed: List<String> = emptyList(),
    setting: ChatSetting = chatSetting(),
): ChatLog {
    check(asked(setting) == SymptomStep.PAIN_AREA)
    val codes = chatMapCodes(strokes, added, removed)
    return answeredLast(setting) {
        it.painted(SymptomStep.PAIN_AREA, strokes).valued(SymptomStep.PAIN_AREA, codes).sent(SymptomStep.PAIN_AREA)
    }.settle(setting)
}

/** A tap on [view] at ([x], [y]) as the map keeps it: a dab of the areas under the finger. */
internal fun dab(view: PainView, x: Float, y: Float): PainStroke =
    checkNotNull(chatStroke(PainStroke(view, 0.036f, listOf(Offset(x, y))))) { "off the body at $x, $y" }

/** The centre of grid cell [cell], counted row by row from the top left. */
internal fun gridCellCentre(cell: Int): Offset =
    Offset((cell % GRID_COLUMNS + 0.5f) / GRID_COLUMNS, (cell / GRID_COLUMNS + 0.5f) / GRID_ROWS)

/** A dab on the first area of [code] found on [view]: the centre of one of its cells. */
internal fun dabOn(code: String, view: PainView = PainView.FRONT): PainStroke {
    val area = checkNotNull(RegionSide.parse(code))
    return (0 until GRID_ROWS * GRID_COLUMNS).asSequence()
        .map(::gridCellCentre)
        .filter { areaAt(view, it) == area }
        .mapNotNull { chatStroke(PainStroke(view, 0.036f, listOf(it))) }
        .firstOrNull { chatMarkedAreas(listOf(it)) == setOf(area) }
        ?: error("no dab marks $code alone on $view")
}

/**
 * Every question of the live thread answered calmly — no, none, the first answer, a 3 (or [nrs]), the
 * area the map opens with or a knee — to the end of that thread; a thread the chat starts after it is
 * left asking.
 */
internal fun ChatLog.calmToTheEnd(setting: ChatSetting = chatSetting(), limit: Int = 20, nrs: Int = 3): ChatLog {
    val thread = (chatMove(this, setting) as? ChatMove.Ask)?.thread ?: return this
    var log = this
    repeat(limit) {
        val move = chatMove(log, setting) as? ChatMove.Ask
        if (move == null || move.thread != thread) return log
        log = log.calmAnswer(move, setting, nrs)
    }
    error("the thread did not end within $limit answers")
}

/** [move]'s question answered as [calmToTheEnd] answers it. */
private fun ChatLog.calmAnswer(move: ChatMove.Ask, setting: ChatSetting, nrs: Int): ChatLog =
    when (val reply = chatTurn(this, setting).reply) {
        is ChatReply.AreaChips -> give(reply.choices.first().areas.joinToString(",") { it.code }, setting)

        is ChatReply.BodyMap -> {
            val start = mapStart(threads.last(), threadState(threads.lastIndex, setting), reply)
            if (start.strokes.isEmpty() && start.added.isEmpty()) {
                paint(listOf(dabOn("KNEE:L")), setting = setting)
            } else {
                paint(start.strokes, start.added, start.removed, setting)
            }
        }

        is ChatReply.Choose -> if (move.step.multi) {
            tick(*calm(reply.options).toTypedArray(), setting = setting)
        } else {
            tapOn(calm(reply.options).single(), setting)
        }

        is ChatReply.PainScale -> give("$nrs", setting)

        is ChatReply.PainLasted -> give("${reply.start}", setting)

        is ChatReply.Severity -> answeredLast(setting) { it.rated(SymptomSeverity.MILD) }.settle(setting)

        else -> error("${move.step} waits for $reply")
    }

/** The pain rows a filing store holds. */
internal fun MemoryChatStore.painRows(): List<JournalEntry> = db.pains()
