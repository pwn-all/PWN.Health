package com.pwnall.health

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

// What the chat reads in a message, as the router asks for it: words only propose — the topic chips
// and the queue, the places a pain thread offers first, a mark on a chip, a number the pain ruler rests
// on, a safety question brought forward — and never answer, arm a clock or file anything. The router
// reads every message through [MentionReader], the seam the model on the phone sits behind: the rules
// here, and what its readings kept with the messages add to them (ChatUnion.kt); the words of suicide
// and of an emergency are read by the script whatever reader is behind it, and a reading's flags only
// add their questions beside them (ChatRead.kt).
//
// Each message is read in two forms: its words, lower case and without punctuation, which every key
// is matched against; and its raw text, punctuation kept, which is cut into clauses and holds the
// numbers. A key matches at the start of a word: "колен*" finds «колено» and «коленке», "спина" only
// that word, and in a phrase each word as it is written. Where keys of different meanings cover the
// same words, the longest holds them — «кружится голова» is dizziness, never the head — and of two
// as long, the stronger kind: suicide, then the keys that keep a word out, the mood's phrases, the
// medicines, the symptom topics, then the body's areas. The values a key gives — a side, a time, a
// kind of pain — are read beside the areas and never take their words. A lexicon's phrase stands
// within one clause: «выпил нурофен, плюс болит горло» names no «Нурофен Плюс», "I'm on my period.
// Pain in my knee" no period pain. The script's own words — of suicide, of a topic, of feeling
// unwell — are read across a cut, as they are across two messages.
//
// Negation is not read, on purpose: "no chest pain" asks about the chest, and «колено больше не
// болит» proposes the knee. Three readings only add or take away a mark (the pain thread's): "not too
// bad" takes the mark off a severe sign, "still" and "gone" mark whether it still hurts. Russian and
// English are read in any app language; in every other language only the words of suicide, of an
// emergency and of too much of a medicine are read ([SafetyWords]), which bring their question and
// never count as a word read, so the line to call for an emergency stays in the reply. In an app in
// another language an English key alone is no sign the message was read either: one that is a word of
// that language too — "no", "pain", "Hand", "Stress", "gut" ([HOMOGRAPH_KEYS]) — never counts, and any
// other only beside a second one or as a phrase ([wordsRead]).

/**
 * What one message's words hold. [crisis] and [emergencies] are the script's own reading, in every
 * language a key is written in; [language] is whether English or Russian words were read ([wordsRead])
 * — a medicine's name, a number, an emoji or a key of another language never counts — and with none
 * the line to call for an emergency stays in the reply. [mentions] are in the order they first
 * appear, equal ones merged. [question], [smallTalk], [someoneElse] and [child] say what else the
 * message is; [extraLines] are a lexicon's own lines. [unwell]: it says the person feels unwell and
 * nothing of what — «мне плохо», "I feel unwell" ([UNWELL_KEYS]) — of themselves, with no chest or
 * heart beside it ([chestOrHeartIn]), which the router meets with "physically or emotionally?" when
 * it names no topic: «мужу плохо» and «мне плохо, сердце» are met as any other words are.
 */
internal data class WordsFound(
    val crisis: Boolean,
    val emergencies: List<EmergencyWords>,
    val language: Boolean,
    val mentions: List<WordMention>,
    val question: Boolean = false,
    val smallTalk: SmallTalk? = null,
    val someoneElse: Boolean = false,
    val child: Boolean = false,
    val extraLines: List<BotPiece> = emptyList(),
    val unwell: Boolean = false,
)

/** What a message is when it is not about a topic: a greeting, thanks, "how are you?", or upset with the chat. */
internal enum class SmallTalk { GREETING, THANKS, HOW_ARE_YOU, UPSET }

/**
 * Something a message named that a thread could take up, at [at] in its text. Each lexicon has its
 * own subtype, in its own file of this package: the symptom topics' here, the body's, the mood's and
 * the medicine's with theirs.
 */
internal sealed interface WordMention {
    val at: Int
    val mention: TopicMention
}

/** A topic named by its words ("I feel sick") or by an emoji, with nothing else to it. */
internal data class SymptomWordMention(override val at: Int, override val mention: TopicMention) : WordMention

/**
 * How strongly a match holds its words against another of the same length over them: the lower
 * wins. Suicide first, then the keys that keep a word out ("за спиной"), the mood's phrases, the
 * medicines, the symptom topics, then the body's areas and its pain words, and last the words of
 * feeling unwell, which name no topic: "feeling off" gives way to "off balance".
 */
internal object WordPriority {
    const val CRISIS = 0
    const val EXCLUSION = 1
    const val MOOD = 2
    const val MEDICINE = 3
    const val TOPIC = 4
    const val AREA = 5
    const val UNWELL = 6
}

/**
 * One key a lexicon found: [span] the characters it covers in the message's words, [kind] what it
 * names in the lexicon's own terms, [payload] the lexicon's own data. [verb]: a word of pain, of an
 * injury, of taking or prescribing a medicine, of a mood — a clause holding one never takes its
 * licence from the clause before it. [language]: an English or Russian word, which a medicine's name
 * is not.
 */
internal data class WordMatch(
    val span: IntRange,
    val kind: String,
    val payload: Any? = null,
    val priority: Int = WordPriority.AREA,
    val verb: Boolean = false,
    val language: Boolean = true,
)

/**
 * A kept match inside a clause: [from] and [to] its first and last word there, by index. A lexicon's
 * key stands whole in its clause ([matchesIn]); the script's own words, read across a cut, are placed
 * where they begin and end there with the clause, so [to] is always a word of it.
 */
internal data class PlacedMatch(val match: WordMatch, val from: Int, val to: Int)

/**
 * A piece of a message between two cuts: a full stop, a comma, a question mark, a dash between words,
 * a line break, or a word of contrast ("но", "but"), which begins the next. [words] are its own words
 * as the keys read them, [raw] its text as written (lower case, the punctuation inside kept: "6/10",
 * "2,5"), [offsets] where each of its words starts in the message, [kept] the message's kept matches
 * that stand in it. [verb]: it holds a verb of any lexicon. [joined]: it was cut from the one before
 * only by a comma, a dash or a word of contrast, so a list without verbs goes on in it («болит спина,
 * колено»). [question]: it ends at a question mark.
 */
internal data class Clause(
    val words: String,
    val raw: String,
    val offsets: List<Int>,
    val kept: List<PlacedMatch>,
    val verb: Boolean,
    val joined: Boolean,
    val question: Boolean,
)

/**
 * A list of keys and what they name, for one kind of meaning. [matches] gives every key that stands in
 * a message; the engine takes those no cut parts ([matchesIn]) and keeps the longest where they meet
 * ([WordPriority]). [mentions] turns the kept matches into what a thread could take up, reading each
 * clause; [extraLines] are lines of its own the router adds to a reply (a pharmacist's for a question
 * about a medicine).
 */
internal interface WordLexicon {
    val priority: Int

    fun matches(words: String, raw: String, setting: ChatSetting): List<WordMatch>

    fun mentions(kept: List<WordMatch>, clauses: List<Clause>, setting: ChatSetting): List<WordMention>

    fun extraLines(kept: List<WordMatch>, setting: ChatSetting): List<BotPiece> = emptyList()
}

/** The lexicons every message is read with: the body's here, the mood's and the medicine's in their packages. */
internal val CHAT_LEXICONS: List<WordLexicon> = listOf(BodyLexicon, MoodLexicon, MedicineLexicon)

/**
 * The seam: messages' words read. The rules read the messages one after
 * another, as one text ([SymptomChat.words]); a model's readings are kept message by message, so the
 * seam takes the messages themselves.
 */
internal interface MentionReader {
    fun read(messages: List<ChatTurn.Wrote>, setting: ChatSetting): WordsFound
}

/** The scripted reader: [readWords] of the messages one after another. */
internal object ScriptedMentionReader : MentionReader {
    override fun read(messages: List<ChatTurn.Wrote>, setting: ChatSetting): WordsFound =
        readWords(messages.joinToString("\n") { it.text }, setting)
}

/** The rules, and what the model's stored readings add to them ([withReadings], ChatUnion.kt). */
internal object UnionMentionReader : MentionReader {
    override fun read(messages: List<ChatTurn.Wrote>, setting: ChatSetting): WordsFound =
        ScriptedMentionReader.read(messages, setting).withReadings(messages)
}

/** The reader the chat uses: the rules, and what the model's stored readings add ([UnionMentionReader]). */
internal val chatReader: MentionReader = UnionMentionReader

/** The messages of [chat], oldest first. */
internal fun messagesOf(chat: SymptomChat?): List<ChatTurn.Wrote> =
    chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>()

/**
 * [text]'s words: the topics, places and medicines it names, in the order it names them; the words
 * of suicide and of an emergency; whether it asks the chat a question, is small talk, or is about
 * someone else. Pure: the same text and setting read the same every time. A weekday ("since Monday")
 * is counted back from the chat's day; a chat for an earlier day reads no time word at all.
 */
internal fun readWords(text: String, setting: ChatSetting): WordsFound {
    val message = Message.of(text)
    val all = crisisMatches(message.index) + topicMatches(message.index) + unwellMatches(message.index) +
        CHAT_LEXICONS.flatMap { it.matchesIn(message, setting) }
    val kept = keptMatches(all)
    val clauses = message.clauses(kept)
    val symptoms = symptomMentions(text, message)
    val lexicons = CHAT_LEXICONS.flatMap { it.mentions(kept, clauses, setting) }
    val mentions = mergedMentions(symptoms + lexicons + emojiMentions(message.raw))
    val talk = smallTalkIn(message.index)
    val others = OTHERS_WORDS.longest(message.index)
    // As [crisisIn] and [emergencyIn] read them, the English and Russian keys apart: only they are words read.
    // So is a whole everyday drink's or cigarette's statement, every word of it ([ordinaryConsumptionIn]):
    // the messages of a thread come one to a line.
    val ownCrisis = crisisKeysIn(message.words)
    val ownEmergencies = emergencyKeysIn(text)
    val otherEmergencies = otherLanguageEmergencies(text)
    return WordsFound(
        crisis = ownCrisis || otherLanguageCrisisIn(text),
        emergencies = EmergencyWords.entries.filter { it in ownEmergencies || it in otherEmergencies },
        language = ownCrisis || ownEmergencies.isNotEmpty() || wordsRead(message, kept, symptoms, setting) ||
            text.lines().any(::ordinaryConsumptionIn),
        mentions = mentions,
        question = questionIn(message, clauses),
        smallTalk = talk,
        someoneElse = others.isNotEmpty(),
        child = others.any { it.value },
        extraLines = CHAT_LEXICONS.flatMap { it.extraLines(kept, setting) },
        unwell = kept.any { it.kind == MATCH_UNWELL } && others.isEmpty() && !chestOrHeartIn(message.words),
    )
}

/**
 * Whether English or Russian words were read in [message]: the keys a lexicon kept, a symptom topic's
 * — the chest's own way of telling a pain among them — and the engine's own tables' ([wordKeyTexts]).
 * Any of them where the app speaks English or Russian. In an app in another language, where an
 * English key may be a word of that language — "no" in Spanish, "pain" in French, "Hand", "Stress" and
 * "gut" in German, "vomit" of the Italian «vomito» — never one of [HOMOGRAPH_KEYS], and any other only
 * as a phrase, in Cyrillic letters, or beside a second one: «j'ai acheté du pain», «Mir geht es nicht
 * gut, ich habe Atemnot» and «ho vomitato sangue» keep the line to call for an emergency; "my knee
 * hurts" and «болит колено» are read.
 */
private fun wordsRead(
    message: Message,
    kept: List<WordMatch>,
    symptoms: List<WordMention>,
    setting: ChatSetting,
): Boolean {
    if (symptoms.any { it.mention.topic == Symptom.CHEST_PAIN }) return true
    val keys = kept.filter { it.language }.map { message.words.slice(it.span) } +
        TOPIC_TABLE.all(message.index).map { message.words.slice(it.span) } + wordKeyTexts(message.index)
    if (setting.language != ChatLanguage.OTHER) return keys.isNotEmpty()
    val plain = keys.filter { it !in HOMOGRAPH_KEYS }.distinct()
    return plain.size > 1 || plain.any { key -> ' ' in key || key.any { it in '\u0400'..'\u04FF' } }
}

/**
 * English keys that are words of another of the app's languages as well, by the words they cover:
 * "no" (es, it, pt), "ok", "pain" (fr), "Hand", "Arm" and "Finger" (de), "gut" (de: good), "Stress"
 * (de, fr, it), "fatigue" and the "cramp" of «crampe» (fr), "hi" (Hindi's «ही», "just", in Latin
 * letters), "sore" (id: the evening), "son" (es, fr) — and the medicines' and the body's words that are
 * the same word there ("dose", "migraine", "insomnia", "Burnout"), read rightly but no sign the chat
 * read the message.
 */
internal val HOMOGRAPH_KEYS = setOf(
    "no", "ok", "okay", "cool", "hey", "hi", "pain", "pains", "hand", "arm", "arms", "finger", "fingers", "palm",
    "temple", "sternum", "gut", "guts", "stress", "burnout", "fatigue", "cramp", "cramps", "sore", "son", "sons",
    "baby", "kid", "kids", "top", "pop", "mild", "tension", "dose", "vitamin", "tablet", "capsule", "migraine",
    "insomnia", "depress", "nause", "reflux", "diarrh", "palpit", "vertigo", "panic", "anxi",
)

/**
 * A message as it is read: [raw] its text as [loweredText] gives it — composed (NFC), in lower case
 * with «ё» as «е», without the marks that never tell two words apart; [index] its words, the tokens of
 * [raw] that are letters and digits and the marks that combine with them ([wordChars]); [rawStarts]
 * and [rawEnds] where each of them stands in [raw].
 */
internal class Message private constructor(
    val raw: String,
    val index: MessageWords,
    private val rawStarts: List<Int>,
    private val rawEnds: List<Int>,
) {
    /** The message's words as [normalisedWords] gives them: " a bb ccc ". */
    val words: String get() = index.words

    /** Where word [token] starts in [raw]. */
    fun rawStart(token: Int): Int = rawStarts[token]

    /** The clauses of the message, each with the kept matches that stand in it. */
    fun clauses(kept: List<WordMatch>): List<Clause> {
        if (index.size == 0) return emptyList()
        val clauses = mutableListOf<Clause>()
        var start = 0
        var joined = false
        for (token in 1..index.size) {
            val cut = if (token == index.size) Cut.END else cutBefore(token)
            if (cut == Cut.NONE) continue
            clauses += clause(start until token, kept, joined, cut == Cut.QUESTION || endsInQuestion(token))
            start = token
            joined = cut == Cut.SOFT
        }
        return clauses
    }

    private fun clause(tokens: IntRange, kept: List<WordMatch>, joined: Boolean, question: Boolean): Clause {
        val span = index.starts[tokens.first]..(index.starts[tokens.last] + index.tokens[tokens.last].length - 1)
        val placed = kept.filter { it.span.first in span }.map { match ->
            val from = index.tokenAt(match.span.first) - tokens.first
            // The script's own words may go on past the cut: here they end with the clause ([PlacedMatch]).
            PlacedMatch(match, from, minOf(index.tokenAt(match.span.last), tokens.last) - tokens.first)
        }
        return Clause(
            words = " " + tokens.joinToString(" ") { index.tokens[it] } + " ",
            raw = raw.substring(rawStarts[tokens.first], rawEnds[tokens.last]),
            offsets = tokens.map { rawStarts[it] },
            kept = placed,
            verb = placed.any { it.match.verb },
            joined = joined,
            question = question,
        )
    }

    /** Whether the words [span] covers stand in one clause, no cut between two of them ([clauses]). */
    fun inOneClause(span: IntRange): Boolean =
        (index.tokenAt(span.first) + 1..index.tokenAt(span.last)).none { cutBefore(it) != Cut.NONE }

    /** What stands between word [token] and the one before it. */
    private fun cutBefore(token: Int): Cut {
        val gap = raw.substring(rawEnds[token - 1], rawStarts[token])
        val digits = raw[rawEnds[token - 1] - 1].isDigit() && raw[rawStarts[token]].isDigit()
        val number = digits && gap.length == 1 && gap[0] in NUMBER_MARKS
        return when {
            number -> Cut.NONE
            '?' in gap -> Cut.QUESTION
            gap.any { it in HARD_CUTS } -> Cut.HARD
            ',' in gap || gap.any { it in DASHES } || (gap.trim() == "-" && gap.any { it.isWhitespace() }) -> Cut.SOFT
            index.tokens[token] in CONTRAST_WORDS -> Cut.SOFT
            else -> Cut.NONE
        }
    }

    /** Whether the message's text after word [token] − 1 holds a question mark before the next word. */
    private fun endsInQuestion(token: Int): Boolean = token == index.size && '?' in raw.substring(rawEnds[token - 1])

    private enum class Cut { NONE, SOFT, HARD, QUESTION, END }

    companion object {
        fun of(text: String): Message {
            val raw = loweredText(text)
            val word = wordChars(raw)
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            var at = 0
            while (at < raw.length) {
                if (word[at]) {
                    starts += at
                    while (at < raw.length && word[at]) at++
                    ends += at
                } else {
                    at++
                }
            }
            val words = " " + starts.indices.joinToString(" ") { raw.substring(starts[it], ends[it]) } + " "
            return Message(raw, MessageWords(words), starts, ends)
        }
    }
}

/** The marks that end a clause outright; a comma, a dash or a word of contrast cuts it softly. */
private const val HARD_CUTS = ".;:!…\n\r"

private const val DASHES = "—–"

/** A mark between two digits that is part of a number: "2,5", "38.5", "8:10", not a cut. */
private const val NUMBER_MARKS = ".,:"

private val CONTRAST_WORDS = setOf("но", "а", "зато", "хотя", "but", "though", "although", "however")

/**
 * A message's words split into the tokens the keys are matched against: [words] as [normalisedWords]
 * gives it (" a bb ccc "), [tokens] its words, [starts] where each one starts in [words].
 */
internal class MessageWords(val words: String) {
    val tokens: List<String> = words.split(' ').filter { it.isNotEmpty() }

    val starts: IntArray = IntArray(tokens.size).also { starts ->
        var at = 0
        tokens.forEachIndexed { index, token ->
            at = words.indexOf(token, at)
            starts[index] = at
            at += token.length
        }
    }

    val size: Int get() = tokens.size

    /** The word that holds character [char] of [words]. */
    fun tokenAt(char: Int): Int {
        val found = starts.binarySearch(char)
        return if (found >= 0) found else -found - 2
    }
}

/**
 * A key as the lexicons write it ([wordKey]): words split by one space, each a whole word or, ending in
 * `*`, the start of one. [text] is the key without its marks, which `ChatWordsTest.everyKeyIsNormalised`
 * holds to lower case, «е» for «ё» and no punctuation.
 */
internal class WordKey(private val stems: List<String>, private val prefixes: List<Boolean>) {
    val text: String get() = stems.joinToString(" ")

    /** The first letter of the first word: what a table is indexed by. */
    val first: Char get() = stems.first().first()

    /** The characters of [words] the key covers when it begins at word [token]; null when it does not stand there. */
    fun at(words: MessageWords, token: Int): IntRange? {
        if (token + stems.size > words.size) return null
        stems.forEachIndexed { part, stem ->
            val word = words.tokens[token + part]
            if (if (prefixes[part]) !word.startsWith(stem) else word != stem) return null
        }
        val last = token + stems.size - 1
        val end = words.starts[last] + if (prefixes.last()) stems.last().length else words.tokens[last].length
        return words.starts[token] until end
    }

    /** How many words the key is. */
    val size: Int get() = stems.size
}

/** A key in the lexicons' notation: "колен*" the start of a word, "спина" the whole word, "за спиной" a phrase. */
internal fun wordKey(notation: String): WordKey {
    val parts = notation.split(' ')
    return WordKey(parts.map { it.removeSuffix("*") }, parts.map { it.endsWith("*") })
}

/**
 * A key as `SymptomWords.kt` writes it, matched there as `" $key"` inside the message's words: its
 * last word the start of one unless the key ends in a space, every earlier word whole.
 */
internal fun symptomWordsKey(key: String): WordKey {
    val parts = key.trimEnd().split(' ')
    return WordKey(parts, parts.indices.map { it == parts.lastIndex && !key.endsWith(" ") })
}

/** Where a key of a table stands: [span] in the words it was matched in, [from] and [to] its first and last word. */
internal data class KeyHit<T>(val span: IntRange, val value: T, val from: Int, val to: Int)

/**
 * Keys and what each names, looked up by the first letter of their first word. [longest] keeps, where
 * keys meet over the same words, the one that covers more; the rest give way.
 */
internal class KeyTable<T>(rows: List<Pair<WordKey, T>>) {
    private val byFirst: Map<Char, List<Pair<WordKey, T>>> = rows.groupBy { it.first.first }

    /** Every key of the table that stands in [words], in the order of the words. */
    fun all(words: MessageWords): List<KeyHit<T>> = words.tokens.indices.flatMap { token ->
        byFirst[words.tokens[token].first()].orEmpty().mapNotNull { (key, value) ->
            key.at(words, token)?.let { KeyHit(it, value, token, token + key.size - 1) }
        }
    }

    /** The keys that stand in [words], the longest kept where two meet, in the order of the words. */
    fun longest(words: MessageWords): List<KeyHit<T>> {
        val kept = mutableListOf<KeyHit<T>>()
        all(words).sortedWith(compareByDescending<KeyHit<T>> { it.span.last - it.span.first }.thenBy { it.span.first })
            .forEach { hit -> if (kept.none { it.span.overlaps(hit.span) }) kept += hit }
        return kept.sortedBy { it.span.first }
    }

    /** Every key of the table as written, without its marks. */
    fun keyTexts(): List<String> = byFirst.values.flatten().map { it.first.text }

    /** The words of [words] each key of the table that stands there covers: "stress" of «stressato». */
    fun texts(words: MessageWords): List<String> = all(words).map { words.words.slice(it.span) }

    /** Whether any key of the table stands in [words]. */
    fun any(words: MessageWords): Boolean = words.tokens.indices.any { token ->
        byFirst[words.tokens[token].first()].orEmpty().any { (key, _) -> key.at(words, token) != null }
    }
}

/** A table of [rows], each a value and its keys in the lexicons' notation ([wordKey]). */
internal fun <T> keyTable(vararg rows: Pair<T, List<String>>): KeyTable<T> = keyTableOf(rows.asList())

/** [keyTable] of a list. */
internal fun <T> keyTableOf(rows: List<Pair<T, List<String>>>): KeyTable<T> =
    KeyTable(rows.flatMap { (value, keys) -> keys.map { wordKey(it) to value } })

internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

/**
 * This lexicon's keys in [message], each within one clause ([Message.inOneClause]). The keys are
 * matched against the words alone, which hold a phrase where the message says none: «выпил нурофен,
 * плюс болит горло» is no «Нурофен Плюс», "I'm on my period. Pain in my knee" no period pain, nor are
 * the last word of one message and the first of the next, which come one to a line. A lexicon reads
 * its kept matches clause by clause, word by word ([WordLexicon.mentions]), so such a key would name
 * the wrong thing and keep its words from the keys that do stand there.
 */
private fun WordLexicon.matchesIn(message: Message, setting: ChatSetting): List<WordMatch> =
    matches(message.words, message.raw, setting).filter { message.inOneClause(it.span) }

/** The kept matches: the longest where two meet, then the stronger kind ([WordPriority]); in the order of the words. */
internal fun keptMatches(all: List<WordMatch>): List<WordMatch> {
    val kept = mutableListOf<WordMatch>()
    all.sortedWith(
        compareByDescending<WordMatch> { it.span.last - it.span.first }.thenBy { it.priority }.thenBy { it.span.first },
    ).forEach { match -> if (kept.none { it.span.overlaps(match.span) }) kept += match }
    return kept.sortedBy { it.span.first }
}

private val CRISIS_TABLE: KeyTable<Unit> by lazy { KeyTable(CRISIS_KEYS.map { symptomWordsKey(it) to Unit }) }

private val TOPIC_TABLE: KeyTable<Symptom> by lazy {
    KeyTable(TOPIC_KEYS.flatMap { (topic, keys) -> keys.map { symptomWordsKey(it) to topic } })
}

private val CHEST_TABLE: KeyTable<Unit> by lazy { KeyTable(CHEST_WORDS.map { symptomWordsKey(it) to Unit }) }

/** The words of suicide, which hold their words against any other key ("hurt myself" is no pain word). */
private fun crisisMatches(words: MessageWords): List<WordMatch> =
    CRISIS_TABLE.all(words).map { WordMatch(it.span, MATCH_CRISIS, priority = WordPriority.CRISIS) }

/** The symptom topics' keys, which hold their words against the body's («сердце болит» is no pain mention). */
private fun topicMatches(words: MessageWords): List<WordMatch> =
    TOPIC_TABLE.all(words).map { WordMatch(it.span, MATCH_TOPIC, it.value, priority = WordPriority.TOPIC) }

private const val MATCH_CRISIS = "crisis"

/** A symptom topic's key ([TOPIC_KEYS]); the payload is its [Symptom]. */
internal const val MATCH_TOPIC = "topic"

/**
 * The symptom topics [text] names ([symptomTopicsIn], as the one-topic chat found them), each where
 * its first key stands — chest pain, which any pain beside the chest names, where the chest's word does.
 */
private fun symptomMentions(text: String, message: Message): List<WordMention> {
    val topics = symptomTopicsIn(text)
    if (topics.isEmpty()) return emptyList()
    val hits = TOPIC_TABLE.all(message.index)
    val chest = CHEST_TABLE.all(message.index).firstOrNull()?.from
    return topics.map { topic ->
        val token = hits.firstOrNull { it.value == topic }?.from ?: chest ?: 0
        SymptomWordMention(if (message.index.size == 0) 0 else message.rawStart(token), TopicMention(topic))
    }
}

/**
 * Mentions in the order they first appear, those of one key merged into the first: two mentions of
 * the knee are one, with everything either said.
 */
private fun mergedMentions(mentions: List<WordMention>): List<WordMention> {
    val byKey = LinkedHashMap<String, WordMention>()
    mentions.sortedBy { it.at }.forEach { mention ->
        val key = mention.mention.key
        val before = byKey[key]
        byKey[key] = when {
            before == null -> mention
            before is PainWordMention && mention is PainWordMention -> before.merged(mention)
            else -> before
        }
    }
    return byKey.values.toList()
}

/**
 * The topics an emoji proposes: language-neutral, so they reach the languages no key reads; never a
 * word read, so the line to call for an emergency stays beside them.
 */
private val EMOJI: List<Pair<String, List<Symptom>>> = listOf(
    "😢😭😞😔☹🙁😟😥💔" to listOf(Symptom.MOOD, Symptom.LOW_MOOD),
    "🙂😊😀😃😄😁🥰😍🤩🥳😌☺😐😑😕😡😠🤬" to listOf(Symptom.MOOD),
    "😰😨😱" to listOf(Symptom.WORRY),
    "🤢" to listOf(Symptom.NAUSEA),
    "🤮" to listOf(Symptom.VOMITING),
    "🤒" to listOf(Symptom.FEVER),
    "🤕" to listOf(Symptom.PAIN),
    "😴🥱" to listOf(Symptom.TIREDNESS),
    "💊" to listOf(Symptom.DOSE),
)

/** Each emoji's topics, by its code point (a variation selector after it changes nothing). */
private val EMOJI_TOPICS: Map<Int, List<Symptom>> by lazy {
    EMOJI.flatMap { (points, topics) -> points.codePoints().toArray().map { it to topics } }.toMap()
}

/** The emoji of [raw] as mentions, each where it stands; a head bandaged is a pain in the head. */
private fun emojiMentions(raw: String): List<WordMention> {
    val found = mutableListOf<WordMention>()
    var at = 0
    while (at < raw.length) {
        val point = raw.codePointAt(at)
        EMOJI_TOPICS[point]?.forEach { topic ->
            found += if (topic == Symptom.PAIN) headEmojiMention(at) else SymptomWordMention(at, TopicMention(topic))
        }
        at += Character.charCount(point)
    }
    return found
}

/**
 * A question put to the chat: a phrase of one ("что мне делать", "is it serious"), or a question mark
 * after a word that asks ("что", "how") — never «почему» or "why" without one: «сам не знаю почему» is
 * a reason, not a question. "How are you?" is small talk, not a question.
 */
private fun questionIn(message: Message, clauses: List<Clause>): Boolean {
    if (QUESTION_PHRASES.any(message.index)) return true
    return clauses.any { clause ->
        val words = MessageWords(clause.words)
        val talk = HOW_ARE_YOU_WORDS.longest(words).flatMapTo(HashSet()) { it.from..it.to }
        clause.question && words.tokens.withIndex().any { (at, word) -> word in QUESTION_WORDS && at !in talk }
    }
}

private val QUESTION_PHRASES: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "что мне делать", "что делать", "как быть", "это опасно", "это нормально", "это серьезно", "это страшно",
        "что со мной", "можно ли", "нужно ли", "стоит ли", "как лечить", "чем лечить", "что принять", "что выпить",
        "какую таблетку", "какое лекарство", "what should i do", "what do i do", "is it serious", "is this serious",
        "is it dangerous", "is this normal", "is it normal", "what is wrong with me", "can i take", "should i take",
        "what can i take", "how do i treat",
    ),
)

private val QUESTION_WORDS = setOf(
    "что", "как", "почему", "зачем", "где", "когда", "сколько", "можно", "нужно", "what", "why", "how", "when", "where",
    "is", "are", "can", "should", "do",
)

private val GREETING_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "привет", "здравствуй*", "добрый день", "доброе утро", "добрый вечер", "хай", "алло", "hi", "hello", "hey",
        "good morning", "good evening",
    ),
)

private val THANKS_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "спасибо", "благодарю", "ок", "окей", "хорошо", "понятно", "ясно", "ладно", "thanks", "thank you", "ok", "okay",
        "got it", "cool",
    ),
)

private val HOW_ARE_YOU_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("как дела", "how are you", "how r u"),
)

private val UPSET_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "тупой бот", "тупой чат", "дурацкий чат", "бесполезн* чат", "бесполезн* бот", "ты не понимаешь", "stupid bot",
        "useless bot", "useless app", "you don t understand",
    ),
)

/** Words that may stand beside a greeting or thanks and leave it only that: "thank you very much", «спасибо вам». */
private val TALK_FILLERS = setOf(
    "большое", "огромное", "вам", "тебе", "всем", "снова", "еще", "чат", "бот", "there", "again", "very", "much", "so",
    "a", "lot", "you", "all", "chat", "bot",
)

/**
 * The small talk a message is: upset with the chat, wherever it is said; "how are you?"; and thanks or
 * a greeting only when the message is nothing else — «чувствую себя хорошо» is not thanks.
 */
private fun smallTalkIn(words: MessageWords): SmallTalk? {
    val greeting = GREETING_WORDS.longest(words)
    val thanks = THANKS_WORDS.longest(words)
    fun only(vararg hits: List<KeyHit<Unit>>): Boolean {
        val covered = hits.flatMap { found -> found.flatMap { it.from..it.to } }.toSet()
        return words.tokens.withIndex().all { (at, word) -> at in covered || word in TALK_FILLERS }
    }
    return when {
        UPSET_WORDS.any(words) -> SmallTalk.UPSET
        HOW_ARE_YOU_WORDS.any(words) || words.tokens == listOf("как", "ты") -> SmallTalk.HOW_ARE_YOU
        thanks.isNotEmpty() && only(thanks, greeting) -> SmallTalk.THANKS
        greeting.isNotEmpty() && only(greeting) -> SmallTalk.GREETING
        else -> null
    }
}

/** Words about someone else; true for a child's («у ребёнка», "my son"), whom the chat's questions are not for. */
private val OTHERS_WORDS: KeyTable<Boolean> = keyTable(
    false to listOf(
        "у мамы", "у папы", "у мужа", "у жены", "у друга", "у подруги", "у него", "у нее", "my mum", "my mom", "my dad",
        "my husband", "my wife", "my partner", "my friend", "he has", "she has",
    ),
    true to listOf(
        "у сына", "у дочери", "у ребенка", "ребен*", "сын", "сына", "сыну", "сыном", "сыне", "сынок", "сыночк*", "дочь",
        "дочери", "дочерью", "дочк*", "доченьк*", "малыш*", "my son", "my daughter", "my child", "my kid", "my baby",
        "child", "children", "kid", "kids", "baby", "babies", "son", "sons", "daughter", "daughters",
    ),
)

/** Yes and no, which are words read though they name nothing. */
private val YES_NO_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("да", "ага", "угу", "конечно", "нет", "неа", "yes", "yeah", "yep", "sure", "no", "nope"),
)

/** The words each English or Russian word key of the engine's own tables covers in [words], key by key. */
private fun wordKeyTexts(words: MessageWords): List<String> = listOf(
    GREETING_WORDS,
    THANKS_WORDS,
    HOW_ARE_YOU_WORDS,
    UPSET_WORDS,
    QUESTION_PHRASES,
    YES_NO_WORDS,
    ONSET_WORDS,
    WEEKDAY_WORDS,
    OTHERS_WORDS,
).flatMap { it.texts(words) } + bodyWordKeyTexts(words)

/**
 * When a pain started, by the onset chips: words of time, read in every chat for today and in none
 * for an earlier day, where "yesterday" could be the day before either. "Now", «с вечера», "last
 * night", «4 дня» and «по утрам» mark nothing: they say when it is, or fall between two chips. How
 * often — «3 раза в неделю», "twice a week" — is no start either: those keys (null) hold their words
 * against «неделю» and "a week", and mark nothing.
 */
internal val ONSET_WORDS: KeyTable<SymptomOption?> = keyTable(
    SymptomOption.STARTED_NOW to listOf(
        "только что",
        "минуту назад",
        "пару минут назад",
        "несколько минут назад",
        "just now",
        "a minute ago",
        "just started",
        "a few minutes ago",
    ),
    SymptomOption.STARTED_TODAY to listOf(
        "с утра", "утром", "сегодня", "днем", "с обеда", "час назад", "пару часов", "несколько часов", "this morning",
        "since this morning", "today", "since lunch", "an hour ago", "a few hours ago",
    ),
    SymptomOption.STARTED_YESTERDAY to listOf(
        "вчера",
        "со вчера",
        "со вчерашнего",
        "вчерашн*",
        "yesterday",
        "since yesterday",
    ),
    SymptomOption.STARTED_2_3_DAYS to listOf(
        "позавчера", "два дня", "2 дня", "три дня", "3 дня", "пару дней", "второй день", "третий день", "2 3 дня",
        "two days", "2 days", "three days", "3 days", "a couple of days", "day before yesterday",
    ),
    SymptomOption.STARTED_A_WEEK to listOf(
        "неделю",
        "неделя",
        "около недели",
        "с прошлой недели",
        "a week",
        "for a week",
        "last week",
        "since last week",
    ) + (5..10).flatMap { listOf("$it дней", "$it days") },
    SymptomOption.STARTED_A_MONTH to listOf(
        "месяц", "около месяца", "с прошлого месяца", "3 недели", "три недели", "4 недели", "a month", "last month",
        "three weeks", "3 weeks", "4 weeks",
    ),
    SymptomOption.STARTED_LONGER to listOf(
        "больше месяца", "два месяца", "несколько месяцев", "полгода", "год", "годами", "давно", "всю жизнь", "всегда",
        "over a month", "for months", "months", "a year", "years", "ages", "a long time", "always",
    ),
    null to listOf(
        "в неделю", "каждую неделю", "once a week", "twice a week", "times a week", "в месяц", "каждый месяц",
        "once a month", "twice a month", "times a month", "в год", "каждый год", "once a year", "twice a year",
        "times a year",
    ),
)

/** "Since Monday", counted back from the chat's day. */
private val WEEKDAY_WORDS: KeyTable<DayOfWeek> = keyTable(
    DayOfWeek.MONDAY to listOf("с понедельника", "since monday"),
    DayOfWeek.TUESDAY to listOf("со вторника", "с вторника", "since tuesday"),
    DayOfWeek.WEDNESDAY to listOf("со среды", "с среды", "since wednesday"),
    DayOfWeek.THURSDAY to listOf("с четверга", "since thursday"),
    DayOfWeek.FRIDAY to listOf("с пятницы", "since friday"),
    DayOfWeek.SATURDAY to listOf("с субботы", "since saturday"),
    DayOfWeek.SUNDAY to listOf("с воскресенья", "since sunday"),
)

/**
 * The onset chips [words] (a clause's or a message's) point at; none in a chat for an earlier day.
 * A weekday counts back from the chat's day: one day is yesterday, two or three the 2–3 days, six
 * about a week; the chat's own weekday, four or five days mark nothing.
 */
internal fun onsetsIn(words: String, setting: ChatSetting): Set<SymptomOption> {
    if (setting.past) return emptySet()
    val index = MessageWords(words)
    val days = WEEKDAY_WORDS.longest(index).mapNotNull { hit ->
        val back = (setting.day.dayOfWeek.value - hit.value.value + DAYS_A_WEEK) % DAYS_A_WEEK
        when {
            setting.day == UNKNOWN_DAY -> null
            back == 1 -> SymptomOption.STARTED_YESTERDAY
            back in 2..3 -> SymptomOption.STARTED_2_3_DAYS
            back == DAYS_A_WEEK - 1 -> SymptomOption.STARTED_A_WEEK
            else -> null
        }
    }
    return (ONSET_WORDS.longest(index).mapNotNull { it.value } + days).toSet()
}

private const val DAYS_A_WEEK = 7

/** The day of a setting made from a thread's state that does not know its chat's day: no weekday is read then. */
private val UNKNOWN_DAY: LocalDate = LocalDate.MIN

/**
 * A setting to read a thread's words with, made from [this] state: its chat's day, whether it is for
 * an earlier day, the person, what the chat knows — what the lexicons read — and whether the app
 * speaks a language the chat reads, what says whether the words were read ([wordsRead]). The rest is
 * never read by them.
 */
internal fun ChatState.wordsSetting(): ChatSetting = ChatSetting(
    female = female,
    adult = adult,
    day = thread.day ?: UNKNOWN_DAY,
    past = pastDay,
    now = 0L,
    chatMoment = 0L,
    snapshot = null,
    env = thread.env,
    zone = ZoneOffset.UTC,
    sittingFrom = 0,
    language = if (thread.readLanguage) ChatLanguage.EN else ChatLanguage.OTHER,
    crisisInChat = thread.crisisInChat,
)

/**
 * The messages a thread reads, newest first: its own, and — for a thread started from a mention an
 * earlier message named, a chip of the queue or a follow-up — the message of an earlier thread that
 * named it, unless one of its own names it too. Each with the reading kept with it, if any: the
 * thread's words are the rules' and the readings' one input.
 */
internal fun threadMessages(state: ChatState): List<ChatTurn.Wrote> {
    val own = messagesOf(state.report.chat).reversed()
    val from = state.thread.from.orEmpty()
    val source = when {
        from.startsWith(ROUTE_RESUME) -> {
            val id = from.removePrefix(ROUTE_RESUME).toIntOrNull()
            state.thread.others.firstOrNull { it.id == id }?.let { messagesOf(it.report.chat).reversed() }.orEmpty()
        }

        from.isEmpty() || from == ROUTE_WORDS || from.startsWith(ROUTE_HANDOVER) -> emptyList()

        else -> namingMessage(state, own, from.removePrefix(ROUTE_FOLLOW))
    }
    return own + source
}

/** The texts of [threadMessages], newest first: what the rules read in a thread. */
internal fun threadTexts(state: ChatState): List<String> = threadMessages(state).map { it.text }

/**
 * What the readings of the messages [state]'s thread reads told ([toldIn]); nothing, without reading
 * a message again, when no message of the chat's threads has a reading the chat routes by.
 */
internal fun threadTold(state: ChatState): ReadTold =
    if (state.readsInThread()) toldIn(threadMessages(state)) else ReadTold()

/**
 * The earlier thread's message that named the mention of [key] — by its words or by the reading kept
 * with it — unless one of the thread's [own] names it.
 */
private fun namingMessage(state: ChatState, own: List<ChatTurn.Wrote>, key: String): List<ChatTurn.Wrote> {
    val setting = state.wordsSetting()
    fun names(message: ChatTurn.Wrote) =
        chatReader.read(listOf(message), setting).mentions.any { it.mention.key == key }
    if (own.any(::names)) return emptyList()
    return listOfNotNull(state.thread.others.flatMap { messagesOf(it.report.chat) }.firstOrNull(::names))
}

/**
 * The onset chip a thread's words mark, among [options]: the one time its messages name — or, where
 * their words name none, the one their readings told — none when they name two, or in a chat for an
 * earlier day. A reading never takes the words' own mark away. Never
 * selected, never armed.
 */
internal fun onsetMarks(state: ChatState, options: List<SymptomOption>): Set<SymptomOption> {
    val setting = state.wordsSetting()
    val words = threadTexts(state).flatMapTo(HashSet()) { onsetsIn(Message.of(it).words, setting) }
    val onsets = words.ifEmpty { setOfNotNull(threadTold(state).onset?.takeUnless { setting.past }) }
    return onsets.singleOrNull()?.takeIf { it in options }?.let { setOf(it) }.orEmpty()
}

/**
 * A setting to read words with when nothing is known of the chat they were written in: no day, so no
 * time word is read, nobody's profile, nothing loaded.
 */
private val UNKNOWN_CHAT = ChatSetting(
    female = false,
    adult = true,
    day = UNKNOWN_DAY,
    past = true,
    now = 0L,
    chatMoment = 0L,
    snapshot = null,
    env = ChatEnv(),
    zone = ZoneOffset.UTC,
    sittingFrom = 0,
    language = ChatLanguage.OTHER,
)

/**
 * The topics [text]'s words name, as the hidden tag keeps them: every lexicon's, read as a chat for
 * today with nothing known about it would, in [TOPIC_ORDER].
 */
internal fun mentionedTopics(text: String): List<Symptom> {
    if (text.isBlank()) return emptyList()
    val topics = readWords(text, UNKNOWN_CHAT).mentions.mapTo(HashSet()) { it.mention.topic } + symptomTopicsIn(text)
    return TOPIC_ORDER.filter { it in topics }
}

/**
 * Whether [text] puts a pain in the chest, which the chest's own questions come before: a
 * pain beside the chest or the heart ([chestPainIn]), or one the body's words place in the chest — the
 * ribs, a breast, the breastbone, "pressure on my chest", «покалывает в груди» ([chestAreaPainIn]).
 * The one reading wherever the chat asks whether words were about the chest: the start it routes,
 * the question it brings into a thread, the thread it adds after one. English and Russian only: the
 * chest of another language ([SafetyWords]) brings the chest's question ([emergencyIn]) and nothing
 * after it — «Sakit kepala dadakan» or «hice pecho en el gimnasio» never start a thread of the chest.
 */
internal fun chestWordsIn(text: String): Boolean = chestPainIn(text) || chestAreaPainIn(text)

/**
 * Whether the body's words in [text] name a pain of the chest's family, read as every message is —
 * the longer key holding its words, «кормлю грудью» no chest — with nothing known of the chat, and
 * with none of the readings that ask this themselves ([emergencyIn]).
 */
internal fun chestAreaPainIn(text: String): Boolean {
    if (text.isBlank()) return false
    val message = Message.of(text)
    val all = crisisMatches(message.index) + topicMatches(message.index) + BodyLexicon.matchesIn(message, UNKNOWN_CHAT)
    val clauses = message.clauses(keptMatches(all))
    return painMentionsIn(clauses, UNKNOWN_CHAT).any { it.family == FAMILY_CHEST }
}

/** Every key of the engine's own tables, for `ChatWordsTest.everyKeyIsNormalised`. */
internal fun chatWordKeyTexts(): List<String> = listOf(
    QUESTION_PHRASES, GREETING_WORDS, THANKS_WORDS, HOW_ARE_YOU_WORDS, UPSET_WORDS, OTHERS_WORDS, YES_NO_WORDS,
    ONSET_WORDS, WEEKDAY_WORDS,
).flatMap { it.keyTexts() } + QUESTION_WORDS + TALK_FILLERS + CONTRAST_WORDS + bodyKeyTexts()
