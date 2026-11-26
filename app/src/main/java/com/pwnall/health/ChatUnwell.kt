package com.pwnall.health

// Feeling unwell, and nothing of what: «мне плохо»,
// «я чувствую себя плохо», "I feel unwell", "I'm not feeling well". No topic of the chat is "unwell
// in general", so where these words name
// no topic offered here, the router asks whether it is more physical or emotional, with three chips
// that only narrow the menu: a tap answers nothing and files nothing ([ChatTurn.Narrowed]), and the
// line to call for an emergency stands beside the question, as «мне плохо» is sometimes acute. Words
// that name a topic as well — «мне плохо, болит голова» — are met as any other words are, this reading unused; so
// are words about someone else («мужу плохо») and beside the chest or the heart («мне плохо с
// сердцем» asks about the chest first) ([WordsFound.unwell]). The chat never answers for the person:
// which topic it is stays their tap.

/** A key of [UNWELL_KEYS] kept in a message ([unwellMatches]); it has no payload. */
internal const val MATCH_UNWELL = "unwell"

/**
 * The words of feeling unwell and nothing of what, in Russian, Russian typed in Latin letters, and
 * English, in the lexicons' notation ([wordKey]). Each key with one word of a few between two of its
 * words is a key too ([intensified]): «мне очень плохо», «мне стало плохо», «мне всё ещё плохо», "I
 * feel so bad", "I'm feeling very unwell". "I'm sick" is none: in British English it is often being
 * sick. Nor is the past ("I felt bad yesterday but now I am fine"): this reads how one feels now.
 * Longer keys hold their words against shorter ones of other meanings, as every key does: «самочувствие
 * так себе» is no so-so mood, «я заболела» no weak word of a pain, "not feeling good" no good mood;
 * "feel sick" stays nausea, «на душе плохо» a bad mood, «плохо сплю» sleep.
 *
 * Each form, and each of [UNWELL_NOT_KEYS], is a test of `ChatUnwellTest`. Limitation: the lists are
 * drawn from 227 phrases people use for it and have not been checked by native speakers.
 */
internal val UNWELL_KEYS: List<String> = listOf(
    "чувствую себя плохо", "плохо себя чувствую", "себя плохо чувствую", "себя чувствую плохо",
    "чувствую себя неважно", "неважно себя чувствую", "чувствую себя ужасно", "ужасно себя чувствую",
    "чувствую себя отвратительно", "чувствую себя паршиво", "чувствую себя хреново", "чувствую себя погано",
    "чувствую себя не очень", "чувствую себя так себе", "мне плохо", "плохо мне", "мне нехорошо", "нехорошо мне",
    "мне хреново", "хреново мне", "мне херово", "мне паршиво", "мне фигово", "мне погано", "мне худо", "мне дурно",
    "мне нездоровится", "нездоровится", "самочувствие плохое", "самочувствие ужасное", "самочувствие так себе",
    "самочувствие неважное", "самочувствие не очень", "самочувствие хреновое", "плохое самочувствие",
    "ужасное самочувствие", "я заболел*", "приболел*", "заболеваю", "расклеил*", "не по себе",
    "мне не очень",
)

/** Russian as it is typed in Latin letters, «мне плохо» as "mne ploho". */
internal val UNWELL_LATIN_RUSSIAN_KEYS: List<String> = listOf(
    "mne ploho", "mne ploxo", "mne plokho", "mne hrenovo", "mne khrenovo", "mne nehorosho", "mne nekhorosho",
    "ploho sebya chuvstvuyu", "chuvstvuyu sebya ploho", "ya zabolel*",
)

/** The English ones. */
internal val UNWELL_ENGLISH_KEYS: List<String> = listOf(
    "feel bad", "feeling bad", "feel unwell", "feeling unwell", "i m unwell", "im unwell", "i am unwell",
    "i m not well", "im not well", "i am not well", "not feeling well", "not feeling good", "not feeling great",
    "not feeling 100", "don t feel well", "dont feel well", "do not feel well", "don t feel good", "dont feel good",
    "do not feel good", "don t feel great", "dont feel great", "feel awful", "feeling awful", "feel terrible",
    "feeling terrible", "feel horrible", "feeling horrible", "feel rough", "feeling rough", "feel off",
    "feeling off", "feel lousy", "feeling lousy", "feel like crap", "feel crap", "feeling crap", "feel crappy",
    "feeling crappy", "feel shitty", "feeling shitty", "feel like shit", "feel rubbish", "feeling rubbish",
    "feel poorly", "feeling poorly", "i m poorly", "im poorly", "i am poorly", "i m ill", "im ill", "i am ill",
    "feel ill", "feeling ill", "feel like death", "feeling like death", "under the weather",
    "coming down with something", "not myself today", "out of sorts",
)

/**
 * What keeps the words of feeling unwell out: a guilt or a regret ("I feel bad about it", "I feel
 * terrible for her"), feeling no such thing ("I don't feel bad", "I'm not feeling bad"), a sight, a
 * hearing, a sleep, a subject hard to learn («мне плохо видно», «мне плохо даётся математика»), «мне
 * не очень нравится» — longer, and with the same words between ([intensified]).
 */
internal val UNWELL_NOT_KEYS: List<String> = listOf(
    "feel bad about", "feel bad for", "feel bad that", "feeling bad about", "feeling bad for", "feeling bad that",
    "don t feel bad", "dont feel bad", "do not feel bad", "didn t feel bad", "never feel bad", "not feeling bad",
    "not feel bad", "feel terrible about", "feel terrible for", "feel terrible that", "feeling terrible about",
    "feel awful about", "feel awful for", "feel awful that", "feeling awful about", "feel horrible about",
    "feel horrible for", "feel off about", "not feeling good about", "not feeling great about", "мне плохо видно",
    "мне плохо слышно",
    "мне плохо спится", "мне плохо дает*", "мне плохо удает*", "мне плохо понятн*", "плохо мне видно",
    "плохо мне слышно", "плохо мне спится", "плохо мне дает*", "плохо себя вел*",
    "мне не очень нрав*", "мне не очень понрав*", "мне не очень понятн*", "мне не очень хочет*",
    "мне не очень интересн*", "мне не очень удобн*", "мне не очень подход*",
)

/**
 * One word, or two, that may stand between two words of a key: «мне очень плохо», «мне стало плохо»,
 * «мне вдруг стало плохо», «мне всё ещё плохо», "I feel so bad".
 */
private val RUSSIAN_INTENSIFIERS = listOf(
    "очень", "так", "совсем", "что то", "как то", "сегодня", "реально", "прям", "прямо", "вообще", "опять", "снова",
    "ужасно", "жутко", "уже", "немного", "слегка", "чуть", "чуть чуть", "сильно", "стало", "становится", "вдруг",
    "вдруг стало", "опять стало", "снова стало", "резко стало", "внезапно стало", "стало очень", "стало так",
    "стало совсем", "все еще", "все равно", "по прежнему", "до сих пор",
)

private val LATIN_RUSSIAN_INTENSIFIERS = listOf("ochen", "tak", "sovsem", "chto to", "segodnya", "realno")

private val ENGLISH_INTENSIFIERS = listOf(
    "so", "really", "very", "pretty", "quite", "rather", "a bit", "a little", "a little bit", "kind of", "kinda",
    "just", "still", "too", "super", "extremely", "totally",
)

/** [key], and [key] with each of [between] between each two of its words. */
private fun intensified(key: String, between: List<String>): List<String> {
    val words = key.split(' ')
    return listOf(key) + (1 until words.size).flatMap { gap ->
        between.map { word -> (words.take(gap) + word + words.drop(gap)).joinToString(" ") }
    }
}

/** Every form of [russian], [latin] and [english] keys, each with its own language's words between. */
private fun unwellForms(russian: List<String>, latin: List<String>, english: List<String>): List<String> =
    russian.flatMap { intensified(it, RUSSIAN_INTENSIFIERS) } +
        latin.flatMap { intensified(it, LATIN_RUSSIAN_INTENSIFIERS) } +
        english.flatMap { intensified(it, ENGLISH_INTENSIFIERS) }

private val UNWELL_TABLE: KeyTable<Unit> by lazy {
    KeyTable(unwellForms(UNWELL_KEYS, UNWELL_LATIN_RUSSIAN_KEYS, UNWELL_ENGLISH_KEYS).map { wordKey(it) to Unit })
}

private val UNWELL_NOT_TABLE: KeyTable<Unit> by lazy {
    val russian = UNWELL_NOT_KEYS.filter { key -> key.first() in 'а'..'я' }
    KeyTable(unwellForms(russian, emptyList(), UNWELL_NOT_KEYS - russian.toSet()).map { wordKey(it) to Unit })
}

/**
 * The words of feeling unwell [words] holds, each a match of its own that names nothing — the router
 * reads them ([WordsFound.unwell]) — and never one a key of [UNWELL_NOT_KEYS] covers. They give way to
 * every other key of their length ([WordPriority.UNWELL]): "feeling off balance" is dizziness.
 */
internal fun unwellMatches(words: MessageWords): List<WordMatch> {
    val out = UNWELL_NOT_TABLE.all(words).map { it.span }
    return UNWELL_TABLE.all(words)
        .filter { hit -> out.none { hit.span.first >= it.first && hit.span.last <= it.last } }
        .map { WordMatch(it.span, MATCH_UNWELL, priority = WordPriority.UNWELL, verb = true) }
}

/** Every key of the unwell tables as written, for `ChatUnwellTest`'s normalisation check. */
internal fun unwellKeyTexts(): List<String> = UNWELL_TABLE.keyTexts() + UNWELL_NOT_TABLE.keyTexts()

/**
 * The router's reply to words of feeling unwell that named no topic offered here: the
 * question whether it is more physical or emotional — or, for a question put to the chat, the line
 * that it is not a doctor; or, the second message in a row of nothing else, that a chip may be
 * easier — always with the line to call for an emergency, as these words set [WordsFound.language],
 * and the three chips that narrow the menu, "Show all topics" beside them. Null for other words,
 * those about someone else and those beside the chest or the heart among them ([WordsFound.unwell]).
 */
internal fun unwellReply(found: WordsFound, again: Boolean, end: Boolean): Pair<List<BotPiece>, ChatReply>? {
    if (!found.unwell) return null
    val lines = when {
        found.question -> listOf(ChatLine.QUESTION, ChatLine.SAFETY)
        again -> listOf(ChatLine.NO_MATCH_AGAIN, ChatLine.SAFETY)
        else -> listOf(ChatLine.UNWELL_ASK, ChatLine.SAFETY)
    }
    val extra = if (found.question) found.extraLines else emptyList()
    return lines.map { BotPiece.Line(it) } + extra to
        ChatReply.Topics(emptyList(), all = false, end = end, narrow = ChatNarrow.entries)
}

/**
 * The menu [menu] holds narrowed by [narrow]: the body's topics, then a pain
 * and something else; the feelings, then the mood where it is offered; or both, the body first. A
 * topic the menu leaves out — the mood for an earlier day or after an answer about suicide, a topic
 * asked once a chat already — stays out.
 */
internal fun narrowedChoices(menu: List<TopicChoice>, narrow: ChatNarrow): List<TopicChoice> {
    val offered = menu.mapTo(HashSet()) { it.topic }
    val body = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_body }.second
    val mind = MENU_GROUPS.first { it.first == R.string.symptom_chat_group_mind }.second
    val order = when (narrow) {
        ChatNarrow.BODY -> body + Symptom.PAIN + Symptom.OTHER
        ChatNarrow.MIND -> mind + Symptom.MOOD
        ChatNarrow.BOTH -> body + mind + listOf(Symptom.PAIN, Symptom.MOOD, Symptom.OTHER)
    }
    return order.filter { it in offered }.map { TopicChoice(it) }
}

/** The narrowing chip [chat]'s last turn is, if it is one: the menu narrowed by it comes next. */
internal fun lastNarrowed(chat: SymptomChat?): ChatNarrow? = (chat?.turns?.lastOrNull() as? ChatTurn.Narrowed)?.narrow

/**
 * The router's reply to a narrowing chip or a written answer [narrow]: the menu [menu] holds narrowed
 * by it ([narrowedChoices]) under "Which of these is closest?", with "Show all topics". It selects
 * no topic and records no symptom; null without a narrowing answer.
 */
internal fun narrowedReply(narrow: ChatNarrow?, menu: ChatReply.Topics): Pair<List<BotPiece>, ChatReply>? =
    narrow?.let {
        listOf(BotPiece.Line(ChatLine.UNWELL_PICK)) to
            ChatReply.Topics(narrowedChoices(menu.choices, narrow), all = false, end = menu.end)
    }

/** A complete short answer to the physical/emotional question; only read while that question stands. */
internal fun unwellAnswerIn(text: String): ChatNarrow? = when (normalisedWords(text).trim()) {
    "на душе", "эмоционально", "душевно", "психологически", "морально", "emotionally", "mentally" -> ChatNarrow.MIND
    "физически", "телесно", "в теле", "physically" -> ChatNarrow.BODY
    "и физически и эмоционально", "физически и эмоционально", "и то и другое", "оба", "both" -> ChatNarrow.BOTH
    else -> null
}

/**
 * [narrow] tapped under the unwell question: a turn of its own in the last thread — only while the
 * reply asked now offers that chip, so a tap on a chip of a turn gone writes nothing.
 */
internal fun ChatLog.narrowed(narrow: ChatNarrow, setting: ChatSetting): ChatLog {
    val offered = (chatTurn(this, setting).reply as? ChatReply.Topics)?.narrow.orEmpty()
    if (narrow !in offered) return this
    return updateLast { report ->
        val told = report.chat ?: SymptomChat()
        report.copy(chat = told.copy(turns = told.turns + ChatTurn.Narrowed(narrow)))
    }
}

/**
 * Whether [state]'s thread was asked whether feeling unwell is more physical or emotional before its
 * topic was taken — the question that says the sympathy ([ChatLine.UNWELL_ASK]): a message there was
 * read as feeling unwell with no topic offered beside it and no question to the chat. Then the body's
 * first question does not say it again. A narrowing chip is no sign by itself: under the
 * line that the chat is no doctor ("мне плохо, что делать?") no sympathy was said, and the body's
 * first question says it.
 */
internal fun unwellAsked(state: ChatState): Boolean {
    val turns = state.chat.turns
    val topicAt = turns.indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM }
    val before = if (topicAt < 0) turns else turns.take(topicAt)
    val setting = state.wordsSetting()
    val wrote = before.filterIsInstance<ChatTurn.Wrote>()
    return wrote.indices.any { last ->
        val found = chatReader.read(wrote.take(last + 1), setting)
        found.unwell && !found.question && found.mentions.none { it.mention.topic.script.offered(setting) }
    }
}
