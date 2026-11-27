package com.pwnall.health

/**
 * A complete, explicit reply to the choices currently shown. Extra words remain a message for the
 * usual reader: "no, I cannot breathe" must never dismiss a question about urgent signs.
 */
internal fun ChatReply.Choose.answerIn(text: String): SymptomOption? {
    val words = loweredText(text).trim().trimEnd('.', '!').trim().replace(CHOICE_SPACE, " ")
    val option = when {
        words in NEGATIVE_CHOICE_WORDS -> when {
            SymptomOption.NONE in options -> SymptomOption.NONE
            SymptomOption.NO in options -> SymptomOption.NO
            else -> null
        }

        words in AFFIRMATIVE_CHOICE_WORDS -> SymptomOption.YES.takeIf { it in options }

        else -> null
    }
    return option
}

private val CHOICE_SPACE = Regex("$SPACE+")
private val NEGATIVE_CHOICE_WORDS = setOf(
    "no",
    "nope",
    "none",
    "none of these",
    "none of those",
    "нет",
    "ничего из этого",
)
private val AFFIRMATIVE_CHOICE_WORDS = setOf("yes", "да")

/**
 * Words sent from the composer can answer a choice question just as its button does. Only the live
 * question's offered options count; opening messages, amounts, maps and ambiguous answers are left
 * to their usual path. Keep the person's wording with the answer for its transcript and reopening.
 */
internal fun ChatLog.typedChoiceLast(text: String, setting: ChatSetting): ChatLog? {
    if (held != null) return null
    val question = chatTurn(told(), setting).reply as? ChatReply.Choose ?: return null
    if (question.step.kind.holds != StepHolds.OPTIONS) return null
    val option = question.answerIn(text) ?: return null
    return answeredLast(setting) {
        it.copy(answers = it.answers + (question.step to listOf(option)))
            .valued(question.step, text.trim().take(SYMPTOM_TEXT_MAX))
            .answered(question.step)
    }
}
