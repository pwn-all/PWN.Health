package com.pwnall.health

import java.text.Normalizer
import java.util.Locale

// What the chat looks for in the person's own words, in English and Russian. It never answers a
// question from them: the topics it finds are offered as chips for the person to tap, and the signs
// of an emergency or of suicide only make the chat ask about them first. The topics are read in
// English and Russian only, and other languages find none, which the chat says rather than
// pretending to have read them (ChatLine.REMARK); the signs of suicide, of an emergency and of too
// much of a medicine are read in the app's fourteen other languages too ([SafetyWords]), and only
// ever add their question.
//
// A key matches at the start of a word ("тошн" finds «тошнит» and «тошноту»); a key ending in a
// space matches a whole word only ("acid " is not "acidic"). Negation is not read on purpose: "no
// nausea" offers Nausea and the person taps something else. A false alarm on suicide asks gently
// whether they are safe, which is the error to make.

/**
 * [text] as the keys are matched against it ([loweredText]), every character that is no part of a
 * word ([wordChars]) — an apostrophe too, so "can’t" and "can't" both read "can t" — a space, and
 * padded with one at either end.
 */
internal fun normalisedWords(text: String): String {
    val lowered = loweredText(text)
    val word = wordChars(lowered)
    val spaced = lowered.mapIndexed { at, char -> if (word[at]) char else ' ' }.joinToString("")
    return " " + spaced.split(' ').filter(String::isNotEmpty).joinToString(" ") + " "
}

/**
 * [text] as its words are read: composed first (NFC), so a «й» typed as «и» and its breve is one
 * letter again; lower case, «ё» as «е»; and without the marks that never tell two words apart — the
 * Arabic short vowels and the tatweel, so «جداً» reads «جدا», and the dot the root locale's lower case
 * leaves above a Turkish «İ», so «İntihar» reads «intihar».
 */
internal fun loweredText(text: String): String {
    val lowered = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('ё', 'е')
    val kept = StringBuilder(lowered.length)
    lowered.forEach { char ->
        val dropped = char in ARABIC_VOWELS || char == ARABIC_TATWEEL || (char == DOT_ABOVE && kept.lastOrNull() == 'i')
        if (!dropped) kept.append(char)
    }
    return kept.toString()
}

/**
 * Which characters of [lowered] stand in a word: letters and digits, and the marks that combine with
 * the letter before them — a Hindi vowel sign, a virama, a nukta — which would otherwise cut the
 * word, so «सिर में दर्द» would be read as «स र म दर द». A mark after a space or an emoji, and an emoji's variation
 * selector or keycap, start no word and continue none.
 */
internal fun wordChars(lowered: String): BooleanArray {
    val word = BooleanArray(lowered.length)
    lowered.forEachIndexed { at, char ->
        word[at] = char.isLetterOrDigit() || (at > 0 && word[at - 1] && combines(char))
    }
    return word
}

/** A mark that is part of the letter before it; never an emoji's variation selector or keycap. */
private fun combines(char: Char): Boolean = char.category in COMBINING_MARKS && char !in EMOJI_MARKS

private val COMBINING_MARKS = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)

/** The variation selectors and the keycap: they dress an emoji, never a letter. */
private val EMOJI_MARKS: Set<Char> = ('\uFE00'..'\uFE0F').toSet() + '\u20E3'

/** The Arabic short vowels, the tanween, the shadda, the sukun and the dagger alif (U+064B–U+065F, U+0670). */
private val ARABIC_VOWELS: Set<Char> = ('\u064B'..'\u065F').toSet() + '\u0670'

private const val ARABIC_TATWEEL = '\u0640'

/** The combining dot above, which `"İ".lowercase(Locale.ROOT)` leaves after an «i». */
private const val DOT_ABOVE = '\u0307'

private fun String.hasAny(keys: List<String>): Boolean = keys.any { contains(" $it") }

/** Each symptom topic's keys; the words engine reads them too, where they hold their words against an area's. */
internal val TOPIC_KEYS: Map<Symptom, List<String>> = mapOf(
    Symptom.VOMITING to listOf(
        "vomit", "throw up", "throwing up", "threw up", "thrown up", "being sick", "been sick", "was sick",
        "puke", "puking", "рвот", "рвет", "рвало", "вырвал", "вырвет", "стошнил", "блев", "блюю", "блюет",
    ),
    Symptom.NAUSEA to listOf(
        "nause",
        "queasy",
        "feel sick",
        "feeling sick",
        "felt sick",
        "тошн",
        "мутит",
        "подташнив",
    ),
    Symptom.DIARRHOEA to listOf(
        "diarrh", "loose stool", "loose poo", "runny poo", "the runs ", "диаре", "понос", "жидкий стул",
        "расстройство желудка",
    ),
    Symptom.HEARTBURN to listOf(
        "heartburn", "reflux", "acid ", "indigestion", "burning in my chest", "изжог", "рефлюкс", "кислот",
        "жжет за грудиной", "жжет в груди", "жжение в груди",
    ),
    Symptom.DIZZINESS to listOf(
        "dizz", "vertigo", "light headed", "lightheaded", "spinning", "unsteady", "off balance", "головокруж",
        "кружится", "кружит", "шатает", "ведет в сторону", "предобморо",
    ),
    Symptom.FAINTING to listOf(
        "faint", "passed out", "blacked out", "black out", "collapsed", "lost consciousness", "обморок",
        "потерял сознание", "потеряла сознание", "отключил", "вырубил",
    ),
    Symptom.PALPITATIONS to listOf(
        "palpit", "heart racing", "racing heart", "heart is racing", "heart pounding", "pounding heart",
        "fluttering", "skipped beat", "skipping beats", "irregular heartbeat", "heart beating fast",
        "сердцебиен", "сердце колотится", "сердце бьется", "сердце стучит", "сердце скачет", "перебои",
        "тахикард", "аритм",
    ),
    Symptom.CHEST_PAIN to listOf(
        "chest pain", "pain in my chest", "chest hurts", "chest is tight", "tight chest", "chest feels tight",
        "pressure in my chest", "burning in my chest", "heart hurts", "pain in my heart", "heart pain",
        "heart attack", "боль в груди", "болит в груди", "болит грудь", "грудь болит", "давит в груди",
        "сжимает в груди", "сжимает грудь", "болит сердце", "сердце болит", "колет в сердце", "жжет в груди",
        "жжение в груди", "жжет за грудиной", "давление в груди", "инфаркт", "сердечный приступ",
        "сердечного приступ", "сердечным приступ", "сердечном приступ", "сердечному приступ",
        // The heart unwell with no word of a pain: «мне плохо с сердцем» is no
        // "physically or emotionally?", and "something is wrong with my heart" no feeling.
        "плохо с сердцем", "с сердцем плохо", "что то с сердцем", "wrong with my heart",
    ),
    Symptom.BREATHLESSNESS to listOf(
        "breathless", "short of breath", "shortness of breath", "out of breath", "can t breathe", "cant breathe",
        "cannot breathe", "hard to breathe", "struggling to breathe", "одышк", "задыха", "не хватает воздуха",
        "трудно дышать", "тяжело дышать", "не могу дышать", "нечем дышать",
        // The floor's: read as breathlessness too, beside their question first.
        "can t catch my breath", "cant catch my breath", "cannot catch my breath", "can t get enough air",
        "cant get enough air", "cannot get enough air", "breathing is hard", "не могу вдохнуть", "не могу вздохнуть",
        "воздуха не хватает", "не могу отдышаться", "дышать тяжело", "дышать трудно", "ne mogu dyshat",
        // "Breathe" typed without its "e", as it often is: "I can't breath".
        "can t breath ", "cant breath ", "cannot breath ", "hard to breath ", "struggling to breath ",
    ),
    Symptom.FEVER to listOf(
        "fever", "temperature", "high temp", "chills", "shivery", "температур", "жар ", "в жар", "лихорад",
        "озноб", "знобит",
    ),
    Symptom.TIREDNESS to listOf(
        "tired", "fatigue", "exhausted", "no energy", "worn out", "drained", "устал", "усталост", "утомл",
        "нет сил", "без сил", "вымотан", "слабост", "сонлив", "с ног валюсь", "ноги не держат",
    ),
    Symptom.WORRY to listOf(
        "anxi", "worry", "worried", "worries", "worrying", "nervous", "panic", "on edge", "scared", "afraid",
        "тревог", "тревож", "волнуюсь", "волнение", "пережива", "паник", "нервнич", "страшно", "боюсь",
    ),
    // "Sad" a whole word, and its own forms: as the start of one it would be a cyclist's "saddle sore" too.
    Symptom.LOW_MOOD to listOf(
        "sad ", "sadness", "sadden", "sadder", "saddest", "low mood", "feeling low", "feel low", "feeling down",
        "feel down", "depress", "hopeless", "crying",
        "unhappy", "miserable", "no joy", "грустн", "грущу", "тоск", "подавлен", "депресс", "плохое настроение",
        "настроение плохое", "апати", "плачу", "безнадеж", "ничего не радует", "ничто не радует", "уныни",
    ),
    Symptom.STRESS to listOf(
        "stress", "overwhelm", "under pressure", "burnout", "burned out", "burnt out", "стресс", "напряж",
        "выгора", "перегруз", "загнан", "не справляюсь",
    ),
    Symptom.SLEEP_WORRY to listOf(
        "can t sleep", "cant sleep", "cannot sleep", "insomnia", "trouble sleeping", "awake at night", "lie awake",
        "not sleeping", "sleepless", "can t fall asleep", "не могу уснуть", "не могу заснуть", "не сплю",
        "не спится", "бессонниц", "плохо сплю", "не засыпаю", "просыпаюсь ночью",
    ),
    // Not places on the body map: a sore throat is not neck pain, and teeth are not the jaw (whose
    // safety line is about the joint). "Something else" is offered for them instead.
    Symptom.OTHER to listOf(
        "горло ", "горла ", "горле ", "горлу ", "горлом ", "ухо ", "уши ", "ушах ", "ушей ", "глаз", "нос ",
        "носу ", "носа ", "носом ", "кожа ", "кожи ", "кожу ", "коже ", "кожей ", "сыпь ", "сыпи ", "зуд",
        "кашель ", "кашля ", "кашлем ", "насморк ", "насморка ", "зуб ", "зуба ", "зубы ", "зубов ", "зубн",
        "десн", "throat ", "ear ", "ears ", "eye ", "eyes ", "nose ", "skin ", "rash ", "itch", "cough ",
        "runny nose ", "tooth ", "teeth ", "toothache ", "gum ", "gums ",
    ),
)

/**
 * Phrases that hold a topic's key out of their own words, as [EMERGENCY_NOT] does for the emergencies':
 * a spinning class is a bike in an app of workouts, and offered dizziness. "The room is spinning" and
 * "feels like spinning" stay keys. Matched from the start of a word to the end of the phrase's last.
 */
internal val TOPIC_NOT: List<String> = listOf(
    "spinning class", "spinning session", "spinning workout", "spinning bike", "spinning lesson",
    "spinning instructor", "spinning studio", "went to spinning", "go to spinning", "going to spinning",
    "did spinning", "do spinning", "doing spinning", "minutes of spinning", "min of spinning", "hour of spinning",
    "hours of spinning",
)

private val TOPIC_NOT_AT: Regex by lazy {
    Regex(TOPIC_NOT.joinToString("|", prefix = " (?:", postfix = ")[^ ]*") { Regex.escape(it) })
}

/**
 * The topics [text] names, in the order the chips stand in ([TOPIC_ORDER]); none is the usual
 * answer to any language but English and Russian. A pain anywhere in words about the chest or the
 * heart names chest pain, however it is put ([chestPainIn]); the throat, an ear, an eye, the skin or
 * a tooth names "something else".
 */
internal fun symptomTopicsIn(text: String): List<Symptom> {
    val words = normalisedWords(text).replace(TOPIC_NOT_AT, " .")
    return TOPIC_ORDER.filter {
        words.hasAny(TOPIC_KEYS[it].orEmpty()) || (it == Symptom.CHEST_PAIN && chestPainIn(text))
    }
}

/** Where a pain is in the chest or the heart, the chest-pain topic's and not the pain map's. */
internal val CHEST_WORDS = listOf("chest", "heart", "груд", "сердц", "сердечн")

/**
 * Whether the chest or the heart is named in [words] (as [normalisedWords] gives them): «мне плохо,
 * сердце» is no unwell "and nothing of what" ([WordsFound.unwell]).
 */
internal fun chestOrHeartIn(words: String): Boolean = words.hasAny(CHEST_WORDS)

/**
 * Words of a pain somewhere: the body lexicon's one list of them ([PAIN_WORD_KEYS]), "headache" among
 * them. Whole words for «боль» and its forms, which as a prefix would find «больше», «больница» and
 * «болезнь». Read here whatever else the words hold: what the chest's question asks about is any
 * pain written beside the chest.
 */
internal fun painIn(text: String): Boolean = painWordIn(MessageWords(normalisedWords(text)))

/**
 * How a pain of the chest is told besides the words of pain — NHS "Chest pain": "pain or discomfort …
 * squeezing or pressure" — and the past of the pain words: read only within [QUALITY_REACH] words of the
 * chest or the heart ([QUALITY_PLACES]), as "tight" and «кололо» alone are no pain. «Давление» is left
 * out (it is a blood pressure too), and stands in [TOPIC_KEYS] as «давление в груди».
 */
internal val CHEST_QUALITY_KEYS = listOf(
    "tight", "squeez", "discomfort", "constrict", "прихват", "сдави", "давил", "жмет", "жмут", "стеснени", "кололо",
    "жгло", "пекло", "ныло",
)

/**
 * The ways of telling it that say grief as often beside the heart — "my heart is heavy", "crushed my
 * heart", «на сердце тяжело», «сердце сжимается», «щемит сердце» — read beside the chest alone:
 * "heaviness in my chest", «тяжесть в груди», «сжимает грудь». A weight lying on it too: «в груди как
 * кирпич лежит», «как плита на груди», "an elephant sitting on my chest", "my chest is in a vice" — a
 * stone on the heart («камень на сердце») stays grief, and "weights" on a chest day are no key at all.
 */
internal val CHEST_ONLY_QUALITY_KEYS = listOf(
    "heavy", "heavi", "crush", "тяжест", "тяжел", "сжима", "сжал", "щем", "кирпич", "плита", "плитой", "плиту",
    "плиты", "камень", "камнем", "elephant", "brick", "vice", "vise",
)

/** How far apart, in words, a way of telling a chest's pain and its place may stand: three words between. */
private const val QUALITY_REACH = 4

/**
 * The chest (true) and the heart as those ways of telling a pain need them, each a word of its own:
 * "chestnut", a chicken's «грудка», «грудной ребёнок», "heartburn" and «сердцебиение» are no place of
 * a pain — heartburn and palpitations are asked about as themselves.
 */
private val QUALITY_PLACES: KeyTable<Boolean> by lazy {
    KeyTable(
        listOf(
            true to listOf(
                "chest ",
                "chests ",
                "грудь ",
                "груди ",
                "грудин",
                "грудной клет",
                "грудная клет",
                "грудную клет",
            ),
            false to listOf("heart ", "hearts ", "сердце ", "сердца ", "сердцу ", "сердцем ", "сердечк", "сердечн"),
        ).flatMap { (chest, keys) -> keys.map { symptomWordsKey(it) to chest } },
    )
}

/**
 * Every key of the chest's words and of the emergencies', for `ChatWordsTest.everyKeyIsNormalised`: a key
 * written with «ё» or a capital here would never match, and the question it asks would not come.
 */
internal fun emergencyKeyTexts(): List<String> = EmergencyWords.entries.flatMap { it.keys } + CHEST_WORDS +
    CHEST_QUALITY_KEYS + CHEST_ONLY_QUALITY_KEYS + QUALITY_PLACES.keyTexts()

/** The ways of telling a chest's pain: true for those read beside the chest alone. */
private val QUALITY_TABLE: KeyTable<Boolean> by lazy {
    KeyTable(
        CHEST_QUALITY_KEYS.map { symptomWordsKey(it) to false } +
            CHEST_ONLY_QUALITY_KEYS.map { symptomWordsKey(it) to true },
    )
}

/** A heart that is no place of a pain: "heart rate", «на сердце» — a weight on one's mind. */
private fun heartOfSpeech(words: MessageWords, place: KeyHit<Boolean>): Boolean = !place.value &&
    (words.tokens.getOrNull(place.to + 1) in setOf("rate", "rates") || words.tokens.getOrNull(place.from - 1) == "на")

/**
 * Whether a way of telling a chest's pain stands within [QUALITY_REACH] words of the chest, or of the
 * heart for one that says no grief there ([CHEST_ONLY_QUALITY_KEYS]): «тяжесть в груди» and «сердце
 * прихватило», never "my heart is heavy", "heart rate was high, legs feel heavy" or «сердце колотится,
 * тяжело дышать».
 */
private fun chestQualityIn(words: MessageWords): Boolean {
    val places = QUALITY_PLACES.longest(words).filterNot { heartOfSpeech(words, it) }
    return QUALITY_TABLE.longest(words).any { quality ->
        places.any { place ->
            (place.value || !quality.value) && maxOf(place.from - quality.to, quality.from - place.to) <= QUALITY_REACH
        }
    }
}

/**
 * Whether [text] puts a pain in the chest or the heart — a word of pain beside it, a way a chest's pain
 * is told near it ([chestQualityIn]), or a heart attack named: a reason to ask about it, never the map.
 * A false alarm asks whether it is there now, the safe error.
 */
internal fun chestPainIn(text: String): Boolean {
    val words = normalisedWords(text)
    return words.hasAny(TOPIC_KEYS[Symptom.CHEST_PAIN].orEmpty()) ||
        (words.hasAny(CHEST_WORDS) && painIn(text)) ||
        chestQualityIn(MessageWords(words))
}

/**
 * Words of suicide or self-harm. Fuller than any list of topics on purpose: a phrase missed here is
 * a person the chat goes on asking about their sleep. "dont" for the "don’t" typed without its
 * apostrophe; «самоуби» for «самоубийство» and «самоубиться» both; «повешусь» and «повеситься»
 * rather than «повес», which is «повеселиться» too. Russian puts «больше» anywhere in a sentence, so
 * each place it goes is a key of its own. Stems where every form is wanted — «поконч» for «покончу»
 * and «покончила», «сдохн» for «сдохнуть» and «сдохну» — at the price of «покончить с курением»:
 * a false alarm asks whether the person is safe and offers "I am not having thoughts like that".
 * «Отравилась» is no key: it is a food poisoning far more often than not, while «отравлюсь» and «хочу
 * отравиться» are keys; a jump only out of a window («выпрыгнуть из окна»), never out of bed.
 */
internal val CRISIS_KEYS = listOf(
    "suicid", "kill myself", "killing myself", "kill me ", "end my life", "end it all", "end myself",
    "take my life", "want to die", "wanna die", "better off dead", "wish i was dead", "wish i were dead",
    "want to be dead", "wanna be dead", "self harm", "hurt myself", "harm myself", "cut myself",
    "cutting myself", "hang myself", "overdose", "unalive", "no reason to live", "no point living",
    "no point in living", "not want to live", "don t want to live", "dont want to live",
    "do not want to live", "not want to be alive", "don t want to be alive", "dont want to be alive",
    "not want to be here", "don t want to be here", "dont want to be here", "can t go on", "cant go on",
    "cannot go on", "not wake up", "never wake up", "don t want to wake up", "dont want to wake up",
    "not want to wake up", "hope i don t wake up", "hope i dont wake up", "суицид", "самоуби", "поконч",
    "убить себя", "убью себя", "выпрыгнуть из окна", "выпрыгну из окна", "выброситься из окна",
    "выброшусь из окна", "хочу отравиться", "отравлюсь", "отравить себя", "не хочу жить", "жить не хочу",
    "не хочу больше жить", "больше не хочу жить", "жить больше не хочу",
    "не хочется жить", "жить не хочется", "не хочется больше жить", "больше не хочется жить",
    "жить надоело", "надоело жить", "не хочу быть живым", "не хочу быть живой", "незачем жить",
    "нет смысла жить", "смысла жить нет", "устал жить", "устала жить", "хочу умереть", "хочется умереть",
    "умереть хочу", "умереть хочется", "лучше умереть", "лучше бы меня не было", "лучше бы я умер",
    "сдохн", "лучше бы я сдох", "навредить себе", "причинить себе вред", "режу себя", "порезать себя", "вскрыть вены",
    "вскрою вены", "повешусь", "повеситься", "выпилиться", "выпилюсь", "не хочу просыпаться",
    "передозиров", "наглот", "свести счеты",
    // Beside them, for the safety floor: the wish to vanish or never wake, others better off
    // without one, harm done to oneself on purpose — «сделала себе больно» is no pain — and the idiom
    // with the image of it, «хоть вешайся», which costs one gentle question; then typed in Latin letters.
    "хочу исчезнуть", "хочется исчезнуть", "исчезнуть навсегда", "всем будет лучше без меня",
    "всем станет лучше без меня", "всем будет легче без меня", "всем без меня будет лучше",
    "без меня всем будет лучше", "лучше бы я не просыпал", "лучше бы мне не просыпаться", "лучше бы я не родил",
    "хочу уснуть и не просну", "уснуть и не просну", "заснуть и не просну", "уснуть навсегда", "заснуть навсегда",
    "порезался специально", "порезалась специально", "специально порезал", "порезал себя", "порезала себя",
    "режу руки", "режу себе руки", "режу вены", "резала руки", "резал руки", "резала себя", "резал себя",
    "селфхарм", "селф харм", "сделал себе больно", "сделала себе больно", "делаю себе больно",
    "причиняю себе боль", "причинил себе боль", "причинила себе боль", "причинить себе боль", "хоть вешайся",
    "хоть в петлю", "в петлю лезть", "в петлю полез", "ne hochu zhit", "ne khochu zhit", "ne hochu bolshe zhit",
    "zhit ne hochu", "hochu umeret", "khochu umeret", "hochu sdohnut", "pokonchit s soboi", "pokonchit s soboy",
    "ubit sebya", "want to disappear", "wanna disappear", "want to vanish", "wish i could disappear",
    "wish i could just disappear", "better off without me", "better off if i wasn t here",
    "better off if i wasnt here", "better off if i was gone", "better off if i were gone",
    "не вижу смысла жить", "не вижу смысла в жизни", "не вижу смысла дальше жить", "не вижу больше смысла жить",
    "больше не вижу смысла жить", "смысла жить не вижу", "жизнь не имеет смысла", "в жизни нет смысла",
    "нет смысла в жизни", "wish i could sleep forever", "want to sleep forever", "don t see the point anymore",
    "dont see the point anymore", "don t see the point any more", "dont see the point any more",
    "do not see the point anymore", "want it all to stop", "want it all to end", "want everything to stop",
    "want everything to end", "tired of living", "sick of living", "tired of being alive", "done with life",
    "done with living", "wish i was never born", "wish i had never been born", "wish i d never been born",
    // Forms the keys above miss. Every word of a key but its last is matched whole and in a row,
    // so a word put between them ("take my OWN life"), the "-ing" of the first ("ending my life") and
    // «себя» before its verb each need a key of their own; then two idioms and the clinic's word.
    "take my own life", "taking my life", "taking my own life", "end my own life", "ending my life",
    "ending my own life", "ending it all", "hurting myself", "harming myself", "hanging myself",
    "don t wanna live", "dont wanna live", "not worth living", "isn t worth living", "isnt worth living",
    "nothing to live for", "себя убить", "себя убью", "убил бы себя", "убила бы себя", "наложить на себя руки",
    "наложу на себя руки", "уйти из жизни", "уйду из жизни", "самоповрежд",
)

/**
 * Whether [text] holds words of suicide or self-harm: the English and Russian keys ([crisisKeysIn]),
 * or those of the app's other languages ([SafetyWords]), which bring the same question and never count
 * as a word read.
 */
internal fun crisisIn(text: String): Boolean = crisisKeysIn(normalisedWords(text)) || otherLanguageCrisisIn(text)

/**
 * Whether the English and Russian words of suicide stand in [words] (as [normalisedWords] gives them):
 * a key of [CRISIS_KEYS], or "kms" where it is no kilometres ([killMyselfShortAt]).
 */
internal fun crisisKeysIn(words: String): Boolean = words.hasAny(CRISIS_KEYS) || killMyselfShortIn(words)

private fun killMyselfShortIn(words: String): Boolean {
    val tokens = words.split(' ').filter(String::isNotEmpty)
    return tokens.indices.any { at -> tokens[at] == "kms" && killMyselfShortAt(tokens, at) }
}

/**
 * Whether "kms" at [at] of [tokens] is "kill myself", in an app that counts kilometres: after "to",
 * "gonna", "lol" and their like always ("I want to kms today"); never after a number, a count or a
 * word that has kilometres ("I walked 5 kms", "I logged my kms", "distance in kms"), nor before a word
 * of distance ("kms walked today"); and otherwise only alone or at either end of the message ("kms
 * lol", "honestly kms"), where "total kms this week" is no such end.
 */
private fun killMyselfShortAt(tokens: List<String>, at: Int): Boolean {
    val before = tokens.getOrNull(at - 1)
    val after = tokens.getOrNull(at + 1)
    return when {
        before in KMS_CRISIS_BEFORE -> true
        before != null && (before.all(Char::isDigit) || before in KMS_DISTANCE_BEFORE) -> false
        after in KMS_DISTANCE_AFTER -> false
        else -> before == null || after == null
    }
}

/** Words after which "kms" is "kill myself": "I want to kms", "gonna kms", "lol kms". */
private val KMS_CRISIS_BEFORE = setOf(
    "to", "gonna", "wanna", "gotta", "finna", "imma", "ima", "i", "ll", "d", "will", "would", "could", "should",
    "might", "must", "just", "literally", "lol", "lmao", "honestly", "seriously", "fr", "istg", "ugh", "omg",
)

/** Words before "kms" that have kilometres: a count ("a few kms", "how many kms"), "my", "total", "in". */
private val KMS_DISTANCE_BEFORE = setOf(
    "few", "couple", "several", "many", "more", "some", "extra", "one", "two", "three", "four", "five", "six",
    "seven", "eight", "nine", "ten", "eleven", "twelve", "fifteen", "twenty", "thirty", "forty", "fifty", "hundred",
    "my", "the", "your", "our", "their", "his", "her", "these", "those", "all", "total", "in", "of", "per", "km",
    "miles", "and", "or", "than", "into",
)

/** Words after "kms" that have kilometres: "kms walked today", "kms per week", "kms this month". */
private val KMS_DISTANCE_AFTER = setOf(
    "walked", "walking", "walk", "run", "ran", "running", "cycled", "biked", "hiked", "covered", "logged", "done",
    "left", "away", "today", "yesterday", "per", "a", "an", "each", "every", "this", "last", "of", "on", "in", "so",
    "total", "week", "month", "day",
)

/**
 * Someone who is not breathing: "he is not breathing", «ребёнок не дышит», «перестал дышать». Both
 * questions are asked of it, someone not responding first — who does not breathe does not answer, and
 * that question names not breathing too — and then whether it is very hard to breathe, for the one who
 * breathes but barely («муж почти не дышит»). «Не дышит» a whole word: «не дышится» is a stuffy room.
 * Who writes it of themselves is asked about breathing alone ([OWN_BREATHING]).
 */
internal val NOT_BREATHING_KEYS = listOf(
    "not breathing", "isn t breathing", "isnt breathing", "stopped breathing", "не дышит ", "перестал дышать",
    "перестала дышать", "нет дыхания", "дыхания нет",
)

/**
 * "I'm not breathing well", "I am not breathing properly": who writes that they themselves are not
 * breathing is answering. Theirs is the question about breathing; whether someone is unconscious was
 * asked of them first, and of nobody.
 */
private val OWN_BREATHING = Regex(" (?:i|im)(?: m| am| ve| have)? (?:not breathing|stopped breathing)")

/**
 * A nose said blocked, and the clause after it that begins with the bare words: «нос заложен, не дышит»
 * is one blocked nose written with a comma, and asked both questions as someone not breathing. Only a
 * nose said blocked holds the words, only in the clause right after it, and only where they have no
 * subject of their own: «разбил нос, не дышит» names no blocked nose and «нос заложен, он не дышит»
 * names a person — both are asked about. The nose is what its clause is about, «нос» or «носик» as it
 * stands: «ребёнок с заложенным носом, не дышит» and "the baby has a stuffy nose, not breathing" are
 * about who has it, and a question too many costs less than that one missed.
 */
private val NOSE_BLOCKED = Regex(
    "(?:^| )(?:нос(?:ик)? (?:залож|забит)[^${SPACE_CHARS}]*|(?:залож|забит)[^${SPACE_CHARS}]* нос(?:ик)?|" +
        "nose (?:is |s )?(?:blocked|stuffy|stuffed|congested))(?: |$)",
)
private val NOSE_STILL = Regex(
    "^(?:(?:и|совсем|вообще|почти|опять|and|it s|its|it is) )*" +
        "(?:не дышит|not breathing|isn t breathing|isnt breathing)(?= |$)",
)

/** [clauses] without the words of not breathing that are a blocked nose's ([NOSE_BLOCKED]). */
private fun withoutBlockedNose(clauses: List<String>): List<String> = clauses.mapIndexed { at, clause ->
    if (at > 0 && NOSE_BLOCKED.containsMatchIn(clauses[at - 1])) clause.replace(NOSE_STILL, ".") else clause
}

// Limitation: a phrase is matched word for word in one clause, so «нос вторую неделю не дышит» still asks
// its two questions. A word or two let between a phrase's own, as SafetyWords.kt lets its keys, would
// need care: a gap must not swallow «сломал нос муж не дышит».

/**
 * Phrases that hold the emergencies' keys out of their own words, as [SafetyKeys.not] does for the other
 * languages. This is an app of rowing machines and swimming, where a stroke has a rate and a count; what
 * does not respond is a phone or the app as often as a person, and a pain that does not respond to its
 * tablets is no one unconscious; a nose that does not breathe is blocked. Matched as a key is: from the
 * start of a word, the last word the start of one, and never across the end of a clause — "he dropped
 * his phone, not responding" and «разбил нос, не дышит» are a person. "Heat stroke", "not responding to
 * my voice" and «не реагирует на боль» stay keys, and so does «не реагирует на касания»: a touch is how
 * one finds a person does not respond.
 */
internal val EMERGENCY_NOT: List<String> = listOf(
    "stroke rate", "stroke count", "stroke length", "stroke volume", "strokes per", "per stroke", "breast stroke",
    "back stroke", "butterfly stroke", "freestyle stroke", "swim stroke", "swimming stroke", "rowing stroke",
    "not responding to treatment", "not responding to the treatment", "not responding to medication",
    "not responding to the medication", "not responding to painkillers", "app is unresponsive",
    "phone is unresponsive", "screen is unresponsive",
    "не реагирует на лечение", "не реагирует на таблетки", "не реагирует на лекарств",
    "нос не дышит", "нос совсем не дышит", "нос вообще не дышит", "нос почти не дышит", "нос опять не дышит",
    "нос заложен не дышит", "нос заложен и не дышит", "носик не дышит", "носом не дышит", "ноздря не дышит",
    "не дышит нос", "не дышит ноздря", "кожа не дышит", "ткань не дышит",
    "nose is not breathing", "nose isn t breathing", "nose isnt breathing", "nose not breathing",
    "nostril is not breathing", "nostril isn t breathing", "nostril isnt breathing", "nostril not breathing",
    "app is not responding", "app not responding", "phone is not responding", "phone not responding",
    "screen is not responding", "screen not responding", "watch is not responding", "watch not responding",
    "tracker is not responding", "tracker not responding", "button is not responding", "button not responding",
    "sensor is not responding", "sensor not responding", "приложение не реагирует", "телефон не реагирует",
    "экран не реагирует", "бот не реагирует", "чат не реагирует", "браслет не реагирует", "трекер не реагирует",
    "датчик не реагирует", "кнопка не реагирует",
)

/**
 * A rower's or a swimmer's count, which no list of phrases can hold: "500 strokes on the rower". Ten and
 * more: nobody has had that many, and "he has had two strokes" is still asked about.
 */
private const val STROKE_COUNT = """[0-9]{2,} strokes"""

/** [EMERGENCY_NOT] and [STROKE_COUNT] as one pattern over a clause's words: each to the end of its last word. */
private val EMERGENCY_NOT_AT: Regex by lazy {
    Regex(EMERGENCY_NOT.joinToString("|", prefix = " (?:$STROKE_COUNT|", postfix = ")[^ ]*") { Regex.escape(it) })
}

/**
 * The emergencies a few words can name, each with the one question that says whether it is
 * happening: a "yes" there is the line to call emergency services. In the order they are asked —
 * someone not responding first.
 */
internal enum class EmergencyWords(val step: SymptomStep, val keys: List<String>) {
    UNRESPONSIVE(
        SymptomStep.UNRESPONSIVE,
        listOf(
            "unconscious",
            "unresponsive",
            "not responding",
            "won t wake up",
            "wont wake up",
            "без сознания",
            "не приходит в себя",
            "не реагирует",
        ) + NOT_BREATHING_KEYS,
    ),
    BREATHING(
        SymptomStep.BREATH_NOW,
        listOf(
            "can t breathe", "cant breathe", "cannot breathe", "can not breathe", "struggling to breathe", "gasping",
            "choking", "не могу дышать", "нечем дышать", "задыхаюсь", "задыхается",
            // The floor's: «не могу отдышаться» after a run asks too — one question is cheaper.
            "can t catch my breath", "cant catch my breath", "cannot catch my breath", "can not catch my breath",
            "can t get my breath", "cant get my breath", "can t get enough air", "cant get enough air",
            "cannot get enough air", "hard to breathe", "breathing is hard", "не могу вдохнуть", "не могу вздохнуть",
            "не могу сделать вдох", "воздуха не хватает", "не хватает воздуха", "не могу отдышаться", "дышать тяжело",
            "тяжело дышать", "дышать трудно", "трудно дышать", "ne mogu dyshat", "ne mogu vdohnut", "nechem dyshat",
            "zadyhayus", "zadykhayus",
            // "Breathe" typed without its "e", as it often is: "I can't breath".
            "can t breath ", "cant breath ", "cannot breath ", "can not breath ", "hard to breath ",
            "struggling to breath ",
        ) + NOT_BREATHING_KEYS,
    ),
    CHEST(SymptomStep.CHEST_NOW, emptyList()),
    STROKE(
        SymptomStep.STROKE_SIGNS,
        listOf(
            "stroke", "face droop", "face is droop", "face drooping", "face has dropped", "arm is numb",
            "arm numb", "numb arm", "arm went numb", "arm is weak", "slurred", "slurring", "инсульт",
            "перекосил", "лицо опустилось", "онемел", "немеет рука", "рука не поднимается", "невнятн",
            "язык заплетается", "заплетается язык",
            // The floor's: an arm that went dead and a face that slid are no less a stroke's
            // («отсидела ногу», «рука затекла» stay no key, nor "I can't feel my legs" after a run).
            "отнялась", "отнялись", "отнялся", "отнимается", "отнимаются", "лицо поехал", "рот поехал",
            "язык не слушается", "не могу подобрать слов", "otnyalas", "face feels droop", "face looks droop",
            "droopy face", "face is droopy", "side of my face dropped", "side of my face droop",
            "face went numb", "face is numb", "numb face", "arm went dead", "arm feels dead", "arm feels weak",
            "arm feels numb", "arm went weak", "can t lift my arm", "cant lift my arm", "cannot lift my arm",
            "can t raise my arm", "cant raise my arm", "can t feel my arm", "cant feel my arm", "cannot feel my arm",
            "can t feel my face", "cant feel my face", "cannot feel my face", "can t get my words out",
            "cant get my words out", "cannot get my words out",
        ),
    ),
    BLOOD(
        SymptomStep.BLOOD,
        listOf(
            "vomiting blood", "vomited blood", "throwing up blood", "threw up blood", "sick blood",
            "coffee grounds", "рвота кровью", "рвет кровью", "рвало кровью", "кровавая рвота", "кровь в рвоте",
            "кофейной гущ", "кофейная гуща", "рвота с кровью", "рвет с кровью", "рвало с кровью", "вырвало кровью",
            "вырвало с кровью", "blood in my vomit", "blood in the vomit", "blood in vomit",
        ),
    ),
}

/**
 * The emergencies [text] names, in [EmergencyWords] order: by the English and Russian keys
 * ([emergencyKeysIn]) or those of the app's other languages ([SafetyWords]). The chest's question is
 * asked for any pain the words put in the chest, the ribs and the breastbone among it ([chestWordsIn]).
 */
internal fun emergencyIn(text: String): List<EmergencyWords> {
    val own = emergencyKeysIn(text)
    val other = otherLanguageEmergencies(text)
    return EmergencyWords.entries.filter { it in own || it in other }
}

/**
 * The emergencies the English and Russian keys name in [text]: words of the script's languages read,
 * which a key of another language never is. A phrase of [EMERGENCY_NOT] keeps its words from them, in
 * the clause it stands in ([CLAUSE_CUTS]); the keys themselves are read across the clauses.
 */
internal fun emergencyKeysIn(text: String): List<EmergencyWords> {
    // A full stop stands where a phrase was taken out: no key holds one, so none is read across the gap.
    val clauses = text.split(CLAUSE_CUTS).map { normalisedWords(it).replace(EMERGENCY_NOT_AT, " .").trim() }
        .filter(String::isNotEmpty)
    val words = withoutBlockedNose(clauses).joinToString(" ", prefix = " ", postfix = " ")
    return EmergencyWords.entries.filter { sign ->
        when (sign) {
            EmergencyWords.CHEST -> chestPainIn(text) || chestAreaPainIn(text)
            EmergencyWords.UNRESPONSIVE -> words.replace(OWN_BREATHING, " .").hasAny(sign.keys)
            else -> words.hasAny(sign.keys)
        }
    }
}
