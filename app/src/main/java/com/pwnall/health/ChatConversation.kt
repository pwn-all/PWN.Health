package com.pwnall.health

/**
 * A few complete conversational messages; extra words in the same message always go through the usual
 * health reader. What the thread's earlier messages named is held to less: a friendly message after
 * them is met with its own line and their topics as chips, with no record taken up for the person —
 * and never holds the chest's thread back ([settledLog]).
 */
internal enum class ChatConversation { HELLO, HOW_ARE_YOU, JOKE, SAD, REASON, ANOTHER_JOKE, CAPABILITY }

internal fun conversationIn(text: String, recent: List<String> = emptyList()): ChatConversation? {
    val words = conversationText(text)
    val previous = recent.fold<String, ChatConversation?>(null) { before, message ->
        val said = conversationText(message)
        CONVERSATION_WORDS[said] ?: followUpIn(said, before)
    }
    return CONVERSATION_WORDS[words] ?: followUpIn(words, previous)
}

private fun conversationText(text: String): String = loweredText(text)
    .replace(CONVERSATION_PUNCTUATION, " ").trim().replace(CONVERSATION_SPACE, " ")

private val CONVERSATION_PUNCTUATION = Regex("[.,!?…'’\\-]")
private val CONVERSATION_SPACE = Regex("$SPACE+")

private fun followUpIn(words: String, before: ChatConversation?): ChatConversation? = when {
    before == ChatConversation.SAD && words in SAD_REPLIES -> ChatConversation.REASON
    before in JOKE_TALKS && words in JOKE_REPLIES -> ChatConversation.ANOTHER_JOKE
    else -> null
}

private val CONVERSATION_WORDS = buildMap {
    listOf("привет", "приветик", "здравствуй", "здравствуйте", "hello", "hi", "hey").forEach {
        put(it, ChatConversation.HELLO)
    }
    listOf("как дела", "как ты", "how are you", "how are you doing").forEach {
        put(it, ChatConversation.HOW_ARE_YOU)
    }
    listOf("расскажи шутку", "расскажи анекдот", "пошути", "tell me a joke", "tell a joke").forEach {
        put(it, ChatConversation.JOKE)
    }
    listOf("грустно", "грусно", "мне грустно", "мне грусно", "я грущу", "i am sad", "i m sad", "i feel sad").forEach {
        put(it, ChatConversation.SAD)
    }
    listOf(
        "что ты умеешь", "что умеешь", "ты кто", "как пользоваться", "что это за приложение",
        "как записать еду", "как добавить лекарство", "what can you do", "who are you",
        "how do i log food", "what is this app",
    ).forEach { put(it, ChatConversation.CAPABILITY) }
}
private val SAD_REPLIES =
    setOf("не знаю", "не знаю почему", "просто", "из за работы", "из за учебы", "i don t know", "work")
private val JOKE_REPLIES = setOf("да", "давай", "еще", "ещё", "еще шутку", "yes", "another", "another one", "go on")
private val JOKE_TALKS = setOf(
    ChatConversation.JOKE,
    ChatConversation.SAD,
    ChatConversation.REASON,
    ChatConversation.ANOTHER_JOKE,
)

/** Derived from persisted turns, so conversational memory survives reopening without a second store. */
internal fun ChatLog.recentMessages(): List<String> = threads.asSequence()
    .flatMap { it.report.chat?.turns.orEmpty().asSequence() }
    .filterIsInstance<ChatTurn.Wrote>()
    .map { it.text }
    .toList()
    .takeLast(3)

/** Replies use only the last few messages, and do not change any confirmed health answer. */
internal fun conversationLines(log: ChatLog, thread: ChatThread): List<BotPiece>? {
    val last = thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>().lastOrNull()
        ?.takeUnless { log.crisisInChat() } ?: return null
    val recent = log.recentMessages().dropLast(1)
    val talk = conversationIn(last.text, recent)
    val joke = if (log.messageCount() % 2 == 1) ChatLine.JOKE_ONE else ChatLine.JOKE_TWO
    return friendlyLines(log, talk, last.text, joke)?.map { BotPiece.Line(it) }
}

private fun friendlyLines(log: ChatLog, talk: ChatConversation?, text: String, joke: ChatLine): List<ChatLine>? {
    val moodWasHandled = log.threads.any { it.topic == Symptom.MOOD || it.topic == Symptom.LOW_MOOD }
    if (talk == ChatConversation.SAD && moodWasHandled) return null
    val lines = when (talk) {
        ChatConversation.HELLO -> listOf(ChatLine.CHAT_HELLO)
        ChatConversation.HOW_ARE_YOU -> listOf(ChatLine.HOW_ARE_YOU)
        ChatConversation.CAPABILITY -> listOf(ChatLine.CHAT_CAPABILITY)
        ChatConversation.JOKE, ChatConversation.ANOTHER_JOKE -> listOf(joke)
        ChatConversation.SAD -> listOf(ChatLine.SAD_COMPANY, joke, ChatLine.SAD_REASON)
        ChatConversation.REASON -> listOf(ChatLine.REASON_HEARD)
        null -> if (ordinaryConsumptionIn(text)) listOf(ChatLine.EVERYDAY_HEARD) else null
    }
    return lines
}
