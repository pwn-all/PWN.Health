package com.pwnall.health

import java.util.Locale

// The body's words: where a pain is, how it is put, and what the pain thread first offers for it.
// An area word becomes a pain only beside a word of pain, of its kind or of an injury, in its own
// clause or in a list without verbs that one began («болит спина, колено»); an area word alone is
// no mention. The words name places on the body map by their names, one list for both maps: the
// second map's names when they all exist, else the first's. A side binds to the area word nearest it
// in its clause, three words away at most; "right" in English only straight before one, so "right
// now" and "I left work" bind nothing. The side is always the person's own.
//
// A message names one pain per body family — two knees are the knee, a shin and a calf the leg — and
// the first widget of its thread follows from it ([painProposal]): chips for a place, the map framed
// on the family for a word as wide as "leg", the head's chips for the head, the chest's own
// questions for the chest. The values beside it — a number out of ten, the kind of pain, when it
// started — only mark a chip or rest the ruler; the person's own tap is the answer.

/**
 * A pain the words named: the [family] of the body it is in (a family name of the pain thread's,
 * "KNEE"; none for a pain named nowhere, or all over), the [candidates] its words name, what its
 * thread first offers ([proposal]); and what the words said of it — a rest for the ruler ([nrs]),
 * the kinds of pain ([types]), when it started and when it was noticed ([onset], [noticed]), whether
 * it is still there ([still]: null when the words say both or neither), whether it is the same as
 * before ([same]), and whether it is told as severe, sudden or mild. [signs] are the red flags the
 * words point at; [hits] each area word it was read from, newest message first; [said] everything its
 * words said, as it was said, which a merge with another mention combines again.
 */
internal data class PainWordMention(
    override val at: Int,
    override val mention: TopicMention,
    val family: String?,
    val candidates: List<RegionSide>,
    val proposal: PainProposal,
    val nrs: Int?,
    val types: Set<PainType>,
    val onset: SymptomOption?,
    val noticed: SymptomOption?,
    val still: Boolean?,
    val same: Boolean?,
    val severe: Boolean,
    val sudden: Boolean,
    val mild: Boolean,
    val signs: Set<SymptomOption> = emptySet(),
    val hits: List<PainHit> = emptyList(),
    val said: PainValues = PainValues(),
) : WordMention {
    /** This mention and [other] of the same key as one: every area word of both, every value either said. */
    fun merged(other: PainWordMention): PainWordMention =
        painMention(at, family, hits + other.hits, listOf(said, other.said))
}

/**
 * One area word of a pain: the lexicon's [entry] it was read as, [at] where it stands in its
 * message, the [areas] it names, the sides its words [named], and [map] when it names a part of the
 * body too wide for chips — the map, framed there. [view] is the plate it is seen on; [listOnly]: its
 * places are chosen from the list only (the jaw, the hand, the fingers, the sole), so no "Show on the
 * body" is offered.
 */
internal data class PainHit(
    val entry: String,
    val family: String?,
    val at: Int,
    val areas: List<RegionSide>,
    val named: Set<BodySide>,
    val map: Boolean,
    val view: PainView,
    val listOnly: Boolean,
)

/**
 * What a pain thread's first widget offers: [Chips] for a place, with where "Show on the
 * body" opens the map (none for places the list alone holds); the map, framed ([Body]); the head's
 * chips, the named first and none ticked ([Head]); or the chest, whose own questions come first
 * ([Chest]), and then the map framed on it.
 */
internal sealed interface PainProposal {
    data class Chips(val choices: List<AreaChoice>, val frame: PainFrame?) : PainProposal

    data class Body(val frame: PainFrame) : PainProposal

    data class Head(val chips: List<RegionSide>) : PainProposal

    data object Chest : PainProposal
}

/** [this] proposal as the reply to the area question [step]. */
internal fun PainProposal.reply(step: SymptomStep): ChatReply = when (this) {
    is PainProposal.Chips -> ChatReply.AreaChips(step, choices, frame)
    is PainProposal.Body -> ChatReply.BodyMap(step, frame, head = false, headChips = emptyList())
    is PainProposal.Head -> ChatReply.BodyMap(step, PainFrame(PainView.FRONT, FAMILY_HEAD), head = true, chips)
    PainProposal.Chest -> ChatReply.BodyMap(step, PainFrame(PainView.FRONT, FAMILY_CHEST), head = false, emptyList())
}

internal const val FAMILY_HEAD = "HEAD"
internal const val FAMILY_CHEST = "CHEST"

/** The body's lexicon: area words, words of pain and of injury, and what is said beside them. */
internal object BodyLexicon : WordLexicon {
    override val priority: Int = WordPriority.AREA

    override fun matches(words: String, raw: String, setting: ChatSetting): List<WordMatch> =
        BODY_TABLE.all(MessageWords(words)).map { hit ->
            WordMatch(
                span = hit.span,
                kind = MATCH_BODY,
                payload = hit.value,
                priority = if (hit.value.exclusion) WordPriority.EXCLUSION else WordPriority.AREA,
                verb = hit.value.verb,
            )
        }

    override fun mentions(kept: List<WordMatch>, clauses: List<Clause>, setting: ChatSetting): List<WordMention> =
        painMentionsIn(clauses, setting)
}

/** A body key's kept match ([BodyKey]). */
internal const val MATCH_BODY = "body"

/**
 * What one key of the body's words is, all its meanings at once: the [areas] it names, whether it is
 * a word of pain ([pain]; [weak] for «заболел», which names a pain only beside an area word), of an
 * injury, of a kind of pain — each of which lets an area word in its clause be a pain — and whether
 * it only keeps a word out ([exclusion]). [legOnly]: «судорога» lets only a leg or a foot be a pain.
 */
internal class BodyKey(
    val areas: List<Area>,
    val pain: Boolean,
    val weak: Boolean,
    val injury: Boolean,
    val type: Boolean,
    val exclusion: Boolean,
    val legOnly: Boolean,
) {
    val verb: Boolean get() = pain || weak || injury

    val licence: Licence
        get() = when {
            pain -> Licence.FULL
            weak || type || (injury && !legOnly) -> Licence.AREA
            legOnly -> Licence.LEG_FOOT
            else -> Licence.NONE
        }
}

/**
 * What a clause's words let its area words be: none; only a leg or a foot («судорога»); any area
 * (a word of injury or of a kind of pain); any area, and a pain of its own when it names none.
 */
internal enum class Licence { NONE, LEG_FOOT, AREA, FULL }

/** How an entry's places are worked out from the words around it and their qualifiers. */
internal enum class Resolver { PLAIN, HEAD, BELLY, BACK, THIGH, KNEE, ELBOW, ARM, FINGERS, MAP, WHOLE, CHEST }

/**
 * One row of the lexicon: its keys and the keys that keep a word out of it, the places it names on
 * the first map ([v1]) and the second ([v2], none: the same), its [family], how its places are
 * worked out ([resolver]), the plate it is seen on, and whether only the list holds its places.
 * [painKeys] are its keys that are words of pain in themselves ("headache", «мигрень»); [needs], a
 * word that must stand in its clause too («чашечка» only beside the knee).
 */
internal data class Area(
    val id: String,
    val family: String?,
    val keys: List<String>,
    val not: List<String> = emptyList(),
    val v1: List<String> = emptyList(),
    val v2: List<String>? = null,
    val resolver: Resolver = Resolver.PLAIN,
    val view: PainView = PainView.FRONT,
    val listOnly: Boolean = false,
    val painKeys: List<String> = emptyList(),
    val needs: String? = null,
)

/** Words of a pain, one list: what lets an area word be a pain, and what `painIn` reads. */
internal val PAIN_WORD_KEYS: List<String> = listOf(
    "болит", "болят", "болел*", "заболел*", "разболел*", "побаливает", "побаливают", "боль", "боли", "болью", "болями",
    "болевой", "больно", "ноет", "ноют", "ноющ*", "ломит", "ломят", "ломота", "колет", "колют", "кольнуло", "стреляет",
    "простреливает", "прострел*", "тянет", "тянут", "тянущ*", "жжет", "печет", "давит", "давящ*", "сдавлив*",
    "пульсир*", "дергает", "крутит", "сводит", "свело", "режет", "резь", "рези", "саднит", "pain", "pains", "painful*",
    "hurts", "hurting", "hurt", "ache", "aches", "aching", "achy", "headache*", "backache*", "stomachache*", "sore",
    "tender", "throbbing", "stabbing", "shooting", "cramp*", "twinge*", "killing me",
)

/** «Заболел» names a pain only beside an area word: «я заболела» alone is an illness. */
private const val WEAK_PAIN_KEY = "заболел*"

/** Words of an injury: they let an area word be a pain without a word of pain («ушиб колено»). */
private val INJURY_KEYS: List<String> = listOf(
    "ушиб*", "ударил*", "травм*", "упал* на", "подвернул*", "растянул*", "потянул*", "вывих*", "перелом*", "сломал*",
    "порвал*", "надорвал*", "опух*", "отек*", "отеч*", "синяк*", "хруст*", "щелка*", "клинит", "заклинил*",
    "не сгибается", "не разгибается", "скован*", "немеет", "немеют", "онемел*", "покалыв*", "мурашк*", "bruise*",
    "bumped", "banged", "fell on", "injur*", "sprain*", "strain*", "twisted", "pulled", "broke", "broken", "fracture*",
    "swollen", "swelling", "stiff", "locked", "locking", "clicking", "numb", "numbness", "tingl*", "pins and needles",
)

/** «Судорога» is a cramp of a leg or a foot, and lets nothing else be a pain. */
private const val LEG_ONLY_KEY = "судорог*"

/** The kinds of pain, for the TYPES chips; a key with no kind keeps a word out ("тупой бот" is no dull pain). */
private val TYPE_KEYS: List<Pair<PainType?, List<String>>> = listOf(
    PainType.ACHING to listOf(
        "ноет", "ноют", "ноющ*", "тупая", "тупой", "ломит", "ломота", "саднит", "aching", "ache", "achy", "dull",
        "sore",
    ),
    PainType.STIFF to listOf(
        "тянет", "тянущ*", "давит", "давящ*", "сдавлив*", "распирает", "скован*", "tight", "stiff", "pressing",
        "pressure", "tension",
    ),
    PainType.THROBBING to listOf("пульсир*", "дергает", "стучит", "бьет", "throbbing", "pulsing", "pounding"),
    PainType.SHARP to listOf(
        "острая", "остро", "колет", "колющ*", "кольнуло", "стреляет", "стреляющ*", "простреливает", "прострел*",
        "режет", "режущ*", "резкая", "кинжальн*", "sharp", "stabbing", "shooting", "stab", "cutting", "like a knife",
    ),
    PainType.BURNING to listOf(
        "жжет", "жжение", "жгуч*", "печет", "покалыв*", "мурашк*", "немеет", "немеют", "онемел*", "онемение",
        "как иголк*", "burning", "tingling", "tingle", "pins and needles", "numb", "numbness",
    ),
    PainType.CRAMPING to listOf(
        "схватк*", "спазм*", "сводит", "свело", "крутит", "cramp", "cramps", "cramping", "spasm*",
    ),
    null to listOf("тупой бот", "тупой чат", "blood pressure"),
)

private val TYPE_WORDS: KeyTable<PainType?> = keyTableOf(TYPE_KEYS)

/** «Судорога» as a kind of pain: cramping, beside a leg or a foot. */
private val CRAMP_WORD = wordKey(LEG_ONLY_KEY)

/** The lexicon's rows, head to foot. */
@Suppress("LongMethod") // One row per place of the body: the table is the lexicon; split, it would only scatter.
private fun areaRows(): List<Area> = listOf(
    Area(
        "HEAD",
        FAMILY_HEAD,
        keys = listOf(
            "голова", "головы", "голове", "голову", "головой", "головою", "головн*", "головушк*", "башка", "башки",
            "башке", "башку", "башкой", "мигрен*", "head", "headache*", "migraine*",
        ),
        not = listOf(
            "кружится голова", "голова кружится", "закружил* голов*", "голова кругом", "голова не варит", "из головы",
            "в голову", "в голове", "с головой", "ломаю голову", "голову ломаю", "головокруж*", "головк*", "head of",
            "head office", "head to", "go ahead", "head start", "light headed",
        ),
        resolver = Resolver.HEAD,
        painKeys = listOf("мигрен*", "migraine*"),
    ),
    Area(
        "FOREHEAD",
        FAMILY_HEAD,
        keys = listOf(
            "лоб", "лба", "лбу", "лбом", "лбе", "лобн*", "во лбу", "forehead*", "brow", "front of my head",
            "front of the head",
        ),
        v1 = listOf("HEAD_FRONT"),
        resolver = Resolver.HEAD,
    ),
    Area(
        "TEMPLE",
        FAMILY_HEAD,
        keys = listOf("висок", "виск*", "височн*", "temple", "temples"),
        not = listOf(
            "выпил* виски", "пью виски", "стакан* виски", "бутылк* виски", "виски с кол*", "виски со льд*",
            "виски с льд*", "виски с содов*", "виски с тоник*",
        ),
        v1 = listOf("TEMPLE"),
        resolver = Resolver.HEAD,
    ),
    Area(
        "HEAD_TOP",
        FAMILY_HEAD,
        keys = listOf(
            "макушк*", "темечк*", "темя", "темени", "теменн*", "верх головы", "top of my head", "top of the head",
            "crown of my head",
        ),
        v1 = listOf("HEAD_TOP"),
        resolver = Resolver.HEAD,
    ),
    Area(
        "HEAD_BACK",
        FAMILY_HEAD,
        keys = listOf("затыл*", "back of my head", "back of the head", "occipital"),
        v1 = listOf("HEAD_BACK"),
        resolver = Resolver.HEAD,
    ),
    Area(
        "JAW",
        "JAW",
        keys = listOf(
            "челюст*", "скула", "скулы", "скуле", "скулу", "скулой", "больно жевать", "жевать больно", "jaw*",
        ),
        not = listOf("скулит", "скулить"),
        v1 = listOf("JAW"),
        listOnly = true,
        painKeys = listOf("больно жевать", "жевать больно"),
    ),
    Area(
        "NECK",
        "NECK",
        keys = listOf(
            "шея", "шеи", "шее", "шею", "шеей", "шеею", "шейн*", "загривок", "загривк*", "холк*", "neck", "necks",
            "nape",
        ),
        not = listOf("шейк*", "сидит на шее", "сесть на шею", "на шею"),
        v1 = listOf("NECK"),
    ),
    Area(
        "NECK_BACK",
        "NECK",
        keys = listOf(
            "back of my neck",
            "back of the neck",
        ),
        v1 = listOf("NECK"),
        view = PainView.BACK,
    ),
    Area(
        "TRAPEZIUS",
        "SHOULDER",
        keys = listOf(
            "трапеци*",
            "надплеч*",
            "между шеей и плечом",
            "от шеи к плечу",
            "trapezius",
            "between my neck and shoulder",
            "between my neck and my shoulder",
        ),
        v1 = listOf("SHOULDER"),
        v2 = listOf("TRAPEZIUS"),
    ),
    Area(
        "SHOULDER",
        "SHOULDER",
        keys = listOf("плеч*", "shoulder", "shoulders", "rotator cuff"),
        not = listOf("плечом к плечу", "за плечами", "на плечах", "с плеч", "пожал* плечами", "пожим* плечами"),
        v1 = listOf("SHOULDER"),
    ),
    Area(
        "SHOULDER_BLADE",
        "BACK",
        keys = listOf("лопатк*", "лопаток", "под лопатк*", "shoulder blade", "shoulder blades", "scapula"),
        v1 = listOf("UPPER_BACK"),
        v2 = listOf("SHOULDER_BLADE"),
        view = PainView.BACK,
    ),
    Area(
        "UPPER_BACK",
        "BACK",
        keys = listOf(
            "верх спины", "верхн* част* спины", "грудн* отдел*", "между лопатк*", "межлопаточн*", "upper back",
            "top of my back", "between my shoulder blades", "between the shoulder blades", "thoracic",
        ),
        v1 = listOf("UPPER_BACK"),
        resolver = Resolver.BACK,
        view = PainView.BACK,
    ),
    Area(
        "BACK",
        "BACK",
        keys = listOf(
            "спина", "спины", "спине", "спину", "спиной", "спиною", "спинка", "спинки", "спинке", "спинку", "спинкой",
            "спинн*", "позвоночн*", "позвон*", "хребет", "хребт*", "back", "backache", "back pain", "spine", "spinal",
        ),
        // "My knee pain is back" is the knee's pain again; "back pain", longer, holds its words against "is back".
        not = listOf(
            "спинк* стул*", "спинк* кроват*", "спинк* диван*", "спинк* кресл*", "за спиной", "за спину",
            "спина к спине", "спиннинг*", "спиннер*", "позвонить", "позвоню", "позвонишь", "позвонил*", "позвонит*",
            "позвоним", "позвонят", "позвонив*", "позвони", "come back", "came back", "coming back", "go back",
            "went back", "get back", "be back", "is back", "are back", "s back", "back home", "back to", "back then",
            "back in", "call back", "back and forth",
        ),
        v1 = listOf("UPPER_BACK", "LOWER_BACK"),
        resolver = Resolver.BACK,
        view = PainView.BACK,
        painKeys = listOf("back pain"),
    ),
    Area(
        "LOWER_BACK",
        "BACK",
        keys = listOf(
            "поясниц*", "пояснич*", "низ спины", "внизу спины", "нижн* част* спины", "прострел*", "радикулит*",
            "lower back", "low back", "low back pain", "bottom of my back", "lumbar", "lumbago",
        ),
        v1 = listOf("LOWER_BACK"),
        resolver = Resolver.BACK,
        view = PainView.BACK,
        painKeys = listOf("радикулит*", "lumbago", "low back pain"),
    ),
    Area(
        "LOIN",
        "BACK",
        keys = listOf("почк*", "почек", "почечн*", "kidney", "kidneys", "loin", "loins", "flank", "side of my back"),
        v1 = listOf("LOWER_BACK"),
        v2 = listOf("FLANK"),
        view = PainView.BACK,
    ),
    Area(
        "SACRUM",
        "BACK",
        keys = listOf(
            "крестец", "крестц*", "основани* позвоночник*", "в самом низу спины", "sacrum", "sacral",
            "base of my spine", "bottom of my spine", "si joint", "sacroiliac",
        ),
        v1 = listOf("LOWER_BACK"),
        v2 = listOf("SACRUM"),
        view = PainView.BACK,
    ),
    Area(
        "TAILBONE",
        "BACK",
        keys = listOf("копчик*", "tailbone", "tail bone", "coccyx"),
        v2 = listOf("TAILBONE"),
        resolver = Resolver.MAP,
        view = PainView.BACK,
    ),
    Area(
        "BUTTOCK",
        "BACK",
        keys = listOf(
            "ягодиц*", "ягодичн*", "попа", "попу", "попе", "попой", "попка", "попки", "попку", "попке", "попкой",
            "задниц*", "зад", "седалищ*", "пятая точка", "пятой точке", "buttock", "buttocks", "bum", "butt", "bottom",
            "glute", "glutes", "backside", "sciatic*",
        ),
        not = listOf("bottom right", "bottom left"),
        v1 = listOf("GLUTE"),
        view = PainView.BACK,
    ),
    Area(
        "CHEST",
        FAMILY_CHEST,
        keys = listOf(
            "груд*", "ребр*", "ребер", "молочн* желез*", "сосок", "соски", "сосках", "chest", "rib", "ribs", "ribcage",
            "rib cage", "breast", "breasts", "nipple*",
        ),
        not = listOf(
            "грудн* ребен*", "грудничок", "грудничк*", "кормлю грудью", "кормит грудью", "грудн* вскармлив*",
            "грудн* отдел*", "грудин*", "breastfeed*",
        ),
        v1 = listOf("CHEST"),
        v2 = listOf("CHEST", "STERNUM"),
        resolver = Resolver.CHEST,
    ),
    Area(
        "BREASTBONE",
        FAMILY_CHEST,
        keys = listOf(
            "грудин*", "за грудиной", "середин* груди", "посередине груди", "в центре груди", "центр* груди",
            "солнечн* сплетен*", "breastbone", "sternum", "middle of my chest", "centre of my chest",
            "center of my chest",
        ),
        v1 = listOf("CHEST"),
        v2 = listOf("STERNUM"),
        resolver = Resolver.CHEST,
    ),
    Area(
        "BELLY",
        "BELLY",
        keys = listOf(
            "живот*", "пузо", "пузе", "пузу", "пузом", "пузик*", "брюх*", "брюшн*", "кишк*", "кишечн*", "stomach",
            "stomachache", "tummy", "tummyache", "belly", "bellyache", "abdomen", "abdominal", "gut", "guts",
        ),
        not = listOf("животн*", "животрепещ*", "can t stomach", "gut feeling", "gut instinct"),
        v1 = listOf("ABDOMEN"),
        resolver = Resolver.BELLY,
        painKeys = listOf("tummyache", "bellyache"),
    ),
    Area(
        "UPPER_BELLY",
        "BELLY",
        keys = listOf(
            "под ложечк*", "подложечн*", "верх живота", "вверху живота", "верхн* част* живота", "желуд*", "эпигастр*",
            "upper stomach", "upper tummy", "upper belly", "upper abdomen", "pit of my stomach", "top of my stomach",
            "epigastr*",
        ),
        v1 = listOf("ABDOMEN"),
        v2 = listOf("UPPER_BELLY"),
        resolver = Resolver.BELLY,
    ),
    Area(
        "UNDER_RIBS",
        "BELLY",
        keys = listOf(
            "под ребр*",
            "под ребром",
            "подребер*",
            "under my ribs",
            "below my ribs",
            "under the ribs",
            "beneath my ribs",
        ),
        v1 = listOf("ABDOMEN"),
        v2 = listOf("UNDER_RIBS"),
    ),
    Area(
        "LOWER_BELLY",
        "BELLY",
        keys = listOf(
            "низ живота", "внизу живота", "нижн* част* живота", "пупок", "пупк*", "над лобком", "лобк*",
            "мочев* пузыр*", "lower stomach", "lower tummy", "lower belly", "lower abdomen", "belly button", "navel",
            "bladder", "pelvic", "bottom of my stomach", "bottom of my tummy", "bottom of my belly",
        ),
        v1 = listOf("ABDOMEN"),
        v2 = listOf("LOWER_BELLY"),
        resolver = Resolver.BELLY,
    ),
    Area(
        "PERIOD",
        "BELLY",
        keys = listOf("месячн*", "менструац*", "period pain", "period cramps"),
        v1 = listOf("ABDOMEN"),
        v2 = listOf("LOWER_BELLY", "LOWER_SIDE"),
        painKeys = listOf("period pain", "period cramps"),
    ),
    Area("LOWER_SIDE", "BELLY", keys = listOf("подвздош*"), v1 = listOf("ABDOMEN"), v2 = listOf("LOWER_SIDE")),
    Area(
        "SIDE",
        "BELLY",
        keys = listOf(
            "бок", "бока", "боку", "боком", "боке", "бочок", "my side", "in my side", "side hurts", "stitch in my side",
            "on my right side", "on my left side",
        ),
        not = listOf("бок о бок", "side effect*", "side by side", "other side", "either side", "on the side"),
        resolver = Resolver.MAP,
        painKeys = listOf("side hurts", "stitch in my side"),
    ),
    Area(
        "WHOLE",
        null,
        keys = listOf(
            "все тело", "по всему телу", "все кости", "все суставы", "суставы", "мышцы", "ломота", "ломит все",
            "whole body", "all over", "everywhere", "every joint", "my joints", "my muscles",
        ),
        resolver = Resolver.WHOLE,
        painKeys = listOf("ломит все"),
    ),
    Area(
        "ARM",
        "ARM",
        keys = listOf(
            "рука", "руки", "руке", "руку", "рукой", "рукою", "рук", "рукам", "руками", "руках", "arm", "arms",
        ),
        not = listOf(
            "под рукой", "на руку", "руки опускаются", "из рук вон", "в руки", "в руках", "взять себя в руки",
            "по рукам", "рука об руку", "под руку", "arm in arm", "up in arms", "twist my arm", "an arm and a leg",
        ),
        resolver = Resolver.ARM,
    ),
    Area(
        "UPPER_ARM",
        "ARM",
        keys = listOf(
            "бицепс*",
            "выше локтя",
            "плечев* кост*",
            "upper arm",
            "upper arms",
            "bicep",
            "biceps",
            "humerus",
        ),
        v1 = listOf("UPPER_ARM"),
    ),
    Area(
        "BACK_OF_UPPER_ARM",
        "ARM",
        keys = listOf(
            "трицепс*",
            "задн* поверхност* плеча",
            "сзади руки выше локтя",
            "tricep",
            "triceps",
            "back of my upper arm",
            "back of my arm",
        ),
        v1 = listOf("UPPER_ARM"),
        v2 = listOf("BACK_OF_UPPER_ARM"),
        view = PainView.BACK,
    ),
    Area(
        "ELBOW",
        "ARM",
        keys = listOf("локот*", "локт*", "локоток", "локотк*", "elbow", "elbows", "tennis elbow"),
        v1 = listOf("ELBOW"),
        resolver = Resolver.ELBOW,
    ),
    Area(
        "INNER_ELBOW",
        "ARM",
        keys = listOf(
            "локтев* сгиб*", "сгиб* локтя", "внутренн* сторон* локтя", "локоть гольфиста", "inner elbow",
            "inside of my elbow", "crook of my elbow", "golfer s elbow", "golfers elbow", "funny bone",
        ),
        v1 = listOf("ELBOW"),
        v2 = listOf("ELBOW_INNER"),
    ),
    Area("FOREARM", "ARM", keys = listOf("предплеч*", "forearm", "forearms"), v1 = listOf("FOREARM")),
    Area(
        "BACK_OF_FOREARM",
        "ARM",
        keys = listOf("тыльн* сторон* предплечья", "back of my forearm"),
        v1 = listOf("FOREARM"),
        v2 = listOf("BACK_OF_FOREARM"),
        view = PainView.BACK,
    ),
    Area("WRIST", "HAND", keys = listOf("запяст*", "кистев*", "wrist", "wrists", "carpal"), v1 = listOf("WRIST_HAND")),
    Area(
        "HAND",
        "HAND",
        keys = listOf(
            "кисть", "кисти", "кистью", "кистям", "кистях", "ладон*", "тыльн* сторон* ладони", "hand", "hands", "palm",
            "palms", "back of my hand",
        ),
        not = listOf(
            "киста", "кисты", "кисту", "кистой", "кистозн*", "on the other hand", "other hand", "on hand", "hand in",
            "hand over", "hands on", "second hand", "a hand",
        ),
        v1 = listOf("WRIST_HAND"),
        listOnly = true,
    ),
    Area(
        "FINGERS",
        "HAND",
        keys = listOf("палец", "пальц*", "пальчик*", "finger", "fingers", "thumb", "thumbs", "knuckle", "knuckles"),
        v1 = listOf("WRIST_HAND"),
        v2 = listOf("FINGERS"),
        resolver = Resolver.FINGERS,
        listOnly = true,
    ),
    Area(
        "LEG",
        "LEG",
        keys = listOf(
            "нога", "ноги", "ноге", "ногу", "ногой", "ногою", "ног", "ногам", "ногами", "ногах", "leg", "legs",
        ),
        not = listOf(
            "с ног валюсь", "ноги не держат", "встать на ноги", "без задних ног", "ни ногой", "в ногах правды нет",
            "break a leg", "pull my leg", "pulling my leg", "an arm and a leg", "last legs", "leg of the",
        ),
        resolver = Resolver.MAP,
    ),
    Area(
        "HIP",
        "HIP",
        keys = listOf(
            "тазобедр*", "таз", "таза", "тазу", "тазом", "тазе", "тазов*", "сбоку бедра", "шейк* бедра", "hip", "hips",
            "hip joint", "side of my hip", "outer hip", "trochanter*",
        ),
        v1 = listOf("HIP"),
    ),
    Area(
        "GROIN",
        "HIP",
        keys = listOf("пах", "паха", "паху", "пахом", "пахе", "пахов*", "groin", "groins", "inguinal"),
        v1 = listOf("HIP"),
        v2 = listOf("GROIN"),
    ),
    Area("THIGH", "LEG", keys = listOf("бедр*", "бедер", "thigh", "thighs"), resolver = Resolver.THIGH),
    Area(
        "FRONT_OF_THIGH",
        "LEG",
        keys = listOf(
            "бедро спереди",
            "спереди бедра",
            "передн* поверхност* бедра",
            "квадрицепс*",
            "front of my thigh",
            "quad",
            "quads",
            "quadricep*",
        ),
        v1 = listOf("THIGH_FRONT"),
    ),
    Area(
        "INNER_THIGH",
        "LEG",
        keys = listOf(
            "внутренн* сторон* бедра",
            "внутренн* поверхност* бедра",
            "приводящ*",
            "inner thigh",
            "inside of my thigh",
            "adductor*",
            "groin strain",
        ),
        v1 = listOf("THIGH_FRONT"),
        v2 = listOf("INNER_THIGH"),
        painKeys = listOf("groin strain"),
    ),
    Area(
        "BACK_OF_THIGH",
        "LEG",
        keys = listOf(
            "бедро сзади",
            "сзади бедра",
            "задн* поверхност* бедра",
            "бицепс* бедра",
            "hamstring",
            "hamstrings",
            "back of my thigh",
            "back of the thigh",
        ),
        v1 = listOf("THIGH_BACK"),
        view = PainView.BACK,
    ),
    Area(
        "KNEE",
        "KNEE",
        keys = listOf("колен*", "надколенн*", "мениск*", "knee*", "patella", "meniscus", "acl"),
        not = listOf("коленвал*", "коленчат*", "по колено"),
        v1 = listOf("KNEE"),
        resolver = Resolver.KNEE,
    ),
    Area("KNEECAP", "KNEE", keys = listOf("чашечк*"), v1 = listOf("KNEE"), needs = "KNEE"),
    Area(
        "BACK_OF_KNEE",
        "KNEE",
        keys = listOf(
            "подколен*", "под коленом", "под коленкой", "под коленями", "сзади колена", "сзади коленки",
            "задн* част* колена", "киста бейкера", "back of my knee", "back of the knee", "behind my knee",
            "behind the knee", "baker s cyst", "bakers cyst",
        ),
        v1 = listOf("KNEE"),
        v2 = listOf("BACK_OF_KNEE"),
        view = PainView.BACK,
    ),
    Area(
        "SHIN",
        "LEG",
        keys = listOf("голен*", "берцов*", "shin", "shins", "shin splints", "tibia"),
        not = listOf("голенищ*"),
        v1 = listOf("SHIN"),
    ),
    Area(
        "CALF",
        "LEG",
        keys = listOf("икр*", "икронож*", "calf", "calves", "calf muscle"),
        not = listOf("красн* икр*", "черн* икр*", "кабачков* икр*", "баклажан* икр*", "икр* рыб*", "икр* лосос*"),
        v1 = listOf("CALF"),
        view = PainView.BACK,
    ),
    Area(
        "FOOT",
        "FOOT",
        keys = listOf(
            "стопа", "стопы", "стопе", "стопу", "стопой", "стопам", "стопами", "стопах", "ступн*", "foot", "feet",
        ),
        not = listOf("foot the bill", "on foot"),
        v1 = listOf("ANKLE_FOOT"),
        v2 = emptyList(),
        resolver = Resolver.MAP,
        needs = NOT_AFTER_A_NUMBER,
    ),
    Area(
        "ANKLE",
        "FOOT",
        keys = listOf(
            "голеностоп*", "щиколот*", "лодыжк*", "лодыжек", "подвернул* ногу", "подвернул* стопу", "ankle", "ankles",
            "twisted my ankle", "sprained my ankle", "rolled my ankle",
        ),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("ANKLE"),
        painKeys = listOf(
            "подвернул* ногу",
            "подвернул* стопу",
            "twisted my ankle",
            "sprained my ankle",
            "rolled my ankle",
        ),
    ),
    Area(
        "ACHILLES",
        "FOOT",
        keys = listOf("ахилл*", "пяточн* сухожил*", "achilles"),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("ACHILLES"),
        view = PainView.BACK,
    ),
    Area(
        "TOP_OF_FOOT",
        "FOOT",
        keys = listOf(
            "подъем* стопы",
            "верх* стопы",
            "сверху стопы",
            "тыльн* сторон* стопы",
            "top of my foot",
            "top of the foot",
            "instep",
        ),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("TOP_OF_FOOT"),
    ),
    Area(
        "TOES",
        "FOOT",
        keys = listOf(
            "пальц* ног*", "палец на ноге", "пальцы на ноге", "больш* палец ноги", "косточк* на ноге",
            "косточк* на стопе", "toe", "toes", "big toe", "bunion",
        ),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("TOES"),
    ),
    Area(
        "HEEL",
        "FOOT",
        keys = listOf("пятк*", "пяток", "пяточ*", "пяточн* шпор*", "heel", "heels", "heel spur"),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("HEEL"),
        view = PainView.BACK,
    ),
    Area(
        "SOLE",
        "FOOT",
        keys = listOf(
            "подошв*", "свод* стопы", "низ* стопы", "стопа снизу", "плоскостоп*", "sole", "soles", "bottom of my foot",
            "arch of my foot", "ball of my foot", "plantar",
        ),
        not = listOf("подошв* обуви", "подошв* ботин*", "подошв* кроссов*"),
        v1 = listOf("ANKLE_FOOT"),
        v2 = listOf("SOLE"),
        listOnly = true,
    ),
)

/** The foot's "feet" after a number ("6 feet") is a length, not a foot. */
private const val NOT_AFTER_A_NUMBER = "NUMBER"

internal val AREAS: List<Area> by lazy { areaRows() }

/**
 * Every key of the body's words with all it means, merged when two rows write the same key
 * («грудной отдел» is the upper back and keeps the chest out).
 */
private val BODY_TABLE: KeyTable<BodyKey> by lazy {
    val areaKeys = AREAS.flatMap { area -> area.keys.map { it to area } }.groupBy({ it.first }, { it.second })
    val exclusions = AREAS.flatMapTo(HashSet()) { it.not }
    val pains = PAIN_WORD_KEYS.toSet() + AREAS.flatMap { it.painKeys }
    val injuries = INJURY_KEYS.toSet() + LEG_ONLY_KEY
    val types = TYPE_KEYS.filter { it.first != null }.flatMapTo(HashSet()) { it.second }
    val all = areaKeys.keys + exclusions + pains + injuries + types
    KeyTable(
        all.map { key ->
            wordKey(key) to BodyKey(
                areas = areaKeys[key].orEmpty(),
                pain = key in pains && key != WEAK_PAIN_KEY,
                weak = key == WEAK_PAIN_KEY,
                injury = key in injuries && key != LEG_ONLY_KEY,
                type = key in types,
                exclusion = key in exclusions,
                legOnly = key == LEG_ONLY_KEY,
            )
        },
    )
}

/** Whether any word of a pain stands in [words]: what the chest's question reads ([painIn]). */
internal fun painWordIn(words: MessageWords): Boolean = PAIN_TABLE.any(words)

private val PAIN_TABLE: KeyTable<Unit> by lazy { KeyTable(PAIN_WORD_KEYS.map { wordKey(it) to Unit }) }

/** The kinds of places a side or a qualifier narrows a family to. */
internal enum class Qualifier { UPPER, LOWER, MIDDLE, FRONT, BACK, INNER, OUTER }

/** A word of where on a part: its level or face, and the side it says with it ("lower right"). */
internal data class Qualify(val qualifier: Qualifier?, val side: BodySide? = null)

private val QUALIFIER_WORDS: KeyTable<Qualify> = keyTable(
    Qualify(Qualifier.UPPER) to listOf("верх", "вверху", "сверху", "наверху", "верхн*", "upper", "top"),
    Qualify(Qualifier.LOWER) to listOf("низ", "внизу", "снизу", "нижн*", "lower", "bottom"),
    Qualify(Qualifier.MIDDLE) to listOf(
        "посередине",
        "посредине",
        "в центре",
        "центр*",
        "середин*",
        "middle",
        "centre",
        "center",
    ),
    Qualify(Qualifier.FRONT) to listOf("спереди", "передн*", "front"),
    Qualify(Qualifier.BACK) to listOf("сзади", "задн*", "back of", "behind"),
    Qualify(Qualifier.INNER) to listOf("внутренн*", "inner", "inside"),
    Qualify(Qualifier.OUTER) to listOf("наружн*", "внешн*", "outer", "сбоку"),
    Qualify(Qualifier.LOWER, BodySide.RIGHT) to listOf("lower right", "bottom right", "right lower", "прав* нижн*"),
    Qualify(Qualifier.LOWER, BodySide.LEFT) to listOf("lower left", "bottom left", "left lower", "лев* нижн*"),
)

/**
 * A side word: the [sides] it says (both for "both"), and where it may stand to say it: [before], only
 * straight before an area word (English "right"); [adjacent], only next to one («левые» is slang for
 * fake on its own: «левые таблетки»). A word with no sides keeps a side out ("right now").
 */
internal data class SideWord(val sides: Set<BodySide>, val before: Boolean = false, val adjacent: Boolean = false)

private val RIGHT = setOf(BodySide.RIGHT)
private val LEFT = setOf(BodySide.LEFT)
private val BOTH = setOf(BodySide.LEFT, BodySide.RIGHT)

private val SIDE_WORDS: KeyTable<SideWord> = keyTable(
    SideWord(RIGHT) to listOf(
        "правое", "правая", "правой", "правом", "правую", "правый", "правого", "правому", "правых", "правым", "правыми",
        "справа", "правее", "с правой стороны", "по правой стороне", "on the right", "on my right", "right side",
        "right hand",
    ),
    SideWord(LEFT) to listOf(
        "левое", "левая", "левой", "левом", "левую", "левый", "левого", "левому", "левых", "левым", "левыми", "слева",
        "левее", "с левой стороны", "по левой стороне", "on the left", "on my left", "left side", "left hand",
    ),
    SideWord(RIGHT, adjacent = true) to listOf("правые"),
    SideWord(LEFT, adjacent = true) to listOf("левые"),
    SideWord(RIGHT, before = true) to listOf("right"),
    SideWord(LEFT, before = true) to listOf("left"),
    SideWord(BOTH) to listOf(
        "оба", "обе", "обоих", "обеих", "обоими", "обеими", "с обеих сторон", "с двух сторон", "по обе стороны", "both",
        "both sides", "either side", "each side",
    ),
    SideWord(emptySet()) to listOf(
        "right now", "all right", "right away", "right after", "that s right", "you re right", "right there", "i left",
        "left work", "left early", "left home", "what s left", "left over", "left me",
    ),
)

/** An area word in its plural, which names both sides («колени», "knees"). */
private val PLURAL_AREA_WORDS = setOf(
    "колени", "коленях", "коленями", "коленках", "плечи", "плечах", "плечами", "ногах", "руках", "виски", "висках",
    "пятках", "лодыжках", "бедрах", "локти", "локтях", "knees", "shoulders", "elbows", "wrists", "hands", "ankles",
    "feet", "heels", "hips", "legs", "arms", "thighs", "calves", "shins", "temples", "buttocks", "glutes", "soles",
    "palms", "shoulder blades",
)

/**
 * A plural spelt as its singular's genitive: «болят руки» is both arms, «палец правой руки» one hand.
 * Both sides only when no side is said with it.
 */
private val PLURAL_OR_GENITIVE = setOf(
    "коленки", "ноги", "руки", "пятки", "лодыжки", "бедра", "запястья", "ступни", "голени", "икры", "ягодицы",
    "лопатки", "ладони", "щиколотки",
)

/** When it was first noticed, by the NOTICED chips; marks only. */
private val NOTICED_WORDS: KeyTable<SymptomOption> = keyTable(
    SymptomOption.NOTICED_DURING_WORKOUT to listOf(
        "во время тренировки", "на тренировке", "во время бега", "на пробежке", "когда бегал*", "в зале", "на футболе",
        "во время игры", "during a workout", "during training", "while running", "during my run", "at the gym",
        "while playing",
    ),
    SymptomOption.NOTICED_AFTER_WORKOUT to listOf(
        "после тренировки", "после бега", "после пробежки", "после зала", "после футбола", "после спорта",
        "после нагрузки", "после йоги", "крепатур*", "after a workout", "after training", "after running",
        "after my run", "after the gym", "after exercise", "after football",
    ),
    SymptomOption.NOTICED_WORK_OR_SITTING to listOf(
        "на работе", "после работы", "за компьютером", "после долгого сидения", "долго сидел*", "в офисе", "at work",
        "after work", "at my desk", "sitting all day", "after sitting", "at the computer",
    ),
    SymptomOption.NOTICED_ON_WAKING to listOf(
        "проснул* с", "при пробуждении", "после сна", "как встал*", "утром проснул*", "woke up with", "when i woke up",
        "on waking", "after sleeping",
    ),
    SymptomOption.NOTICED_AFTER_LIFTING to listOf(
        "поднял*", "поднимал*", "подъем* тяжест*", "надорвал*", "перенапряг*", "таскал*", "носил* тяжел*",
        "сорвал* спину", "lifting", "lifted", "after lifting", "carrying", "strained",
    ),
    SymptomOption.NOTICED_NO_CLEAR_MOMENT to listOf(
        "ни с того ни с сего", "непонятно с чего", "само по себе", "не знаю от чего", "без причины", "out of nowhere",
        "for no reason", "no reason", "not sure why", "don t know why",
    ),
)

/**
 * Still there (true) or gone (false): «прошла» only as a verb — «с прошлой недели» is a time —
 * and "не прошла", longer, holds its words against it.
 */
private val STATUS_WORDS: KeyTable<Boolean> = keyTable(
    true to listOf(
        "до сих пор", "все еще", "не проходит", "не прошла", "не прошло", "не прошел", "не прошли", "продолжает",
        "still", "won t go", "not going away",
    ),
    false to listOf(
        "прошла", "прошло", "прошел", "прошли", "перестал* болеть", "уже не болит", "больше не болит", "отпустил*",
        "gone", "went away", "stopped hurting", "no longer hurts",
    ),
)

/** The same pain as before (true) or a new one (false). */
private val SAME_WORDS: KeyTable<Boolean> = keyTable(
    true to listOf("опять", "снова", "как в прошлый раз", "again", "same pain"),
    false to listOf("впервые", "first time", "new pain"),
)

/** Words of a pain that came on suddenly and badly: they mark "it came on suddenly and is very bad". */
private val SUDDEN_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "как никогда", "никогда так", "никогда такого", "впервые такая", "самая сильная", "сильнейш*", "худш*",
        "невыносим*", "раскалывается", "внезапно", "резко", "вдруг", "как удар*", "worst headache", "worst ever",
        "like never before", "never had", "sudden*", "out of nowhere", "thunderclap", "excruciating", "unbearable",
        "splitting", "exploding",
    ),
)

/** Severe (true) or not severe (false): "not too bad" holds its words against "bad", «не сильно» too. */
private val SEVERITY_WORDS: KeyTable<Boolean> = keyTable(
    true to listOf(
        "очень сильно", "сильно", "сильная", "нестерпим*", "адск*", "дикая", "жуткая", "ужасная", "severe",
        "really bad", "agony", "worst",
    ),
    false to listOf(
        "не сильно", "несильно", "не очень сильно", "не так сильно", "слегка", "чуть чуть", "немного", "терпимо",
        "not too bad", "not that bad", "not severe", "a bit", "slightly", "mild",
    ),
)

/** Words that point at red flags of the pain thread's lists. */
private val SIGN_WORDS: KeyTable<Set<SymptomOption>> = keyTable(
    setOf(SymptomOption.BOTH_LEGS) to listOf("обе ноги", "обеих ногах", "обеих ног", "both legs"),
    setOf(SymptomOption.TINGLE_NUMB, SymptomOption.NUMB_WEAK) to listOf(
        "немеет", "немеют", "онемел*", "онемение", "покалыв*", "мурашк*", "numb", "numbness", "tingl*",
        "pins and needles",
    ),
    setOf(SymptomOption.CRACK) to listOf("хруст*", "щелк*", "треск*", "crack*", "snap*", "pop", "popped"),
    setOf(SymptomOption.SWELL_BRUISE, SymptomOption.FAST_SWELL, SymptomOption.HOT_RED_SWOLLEN) to listOf(
        "опух*",
        "отек*",
        "отеч*",
        "распух*",
        "swollen",
        "swelling",
        "swell*",
    ),
    setOf(SymptomOption.FEVERISH, SymptomOption.HIGH_TEMP) to listOf(
        "температур*",
        "жар",
        "озноб",
        "знобит",
        "fever*",
        "temperature",
        "shivery",
    ),
)

/** The words each of the body's keys of side, place, time of noticing, kind, state or sign covers in [words]. */
internal fun bodyWordKeyTexts(words: MessageWords): List<String> = BODY_TABLE.texts(words) + listOf(
    QUALIFIER_WORDS, SIDE_WORDS, TYPE_WORDS, NOTICED_WORDS, STATUS_WORDS, SAME_WORDS, SUDDEN_WORDS, SEVERITY_WORDS,
    SIGN_WORDS,
).flatMap { it.texts(words) }

/** Every key of the body's words, for `ChatWordsTest.everyKeyIsNormalised`. */
internal fun bodyKeyTexts(): List<String> = BODY_TABLE.keyTexts() + listOf(
    QUALIFIER_WORDS, SIDE_WORDS, TYPE_WORDS, NOTICED_WORDS, STATUS_WORDS, SAME_WORDS, SUDDEN_WORDS, SEVERITY_WORDS,
    SIGN_WORDS,
).flatMap { it.keyTexts() } + PLURAL_AREA_WORDS + PLURAL_OR_GENITIVE

/**
 * Whether the second map's names all exist in [names] — the enum's on this build — so the lexicon
 * proposes them; else the first map's (one table, v2 when it is there).
 */
internal fun secondMapNamesIn(names: Set<String>): Boolean = V2_NAMES.all { it in names }

/** Every name of the second map the lexicon and its narrowing words propose. */
private val V2_NAMES: Set<String> by lazy {
    AREAS.flatMapTo(HashSet()) { it.v2.orEmpty() } + setOf(
        "SHOULDER_BLADE", "FLANK", "UPPER_BELLY", "UNDER_RIBS", "LOWER_BELLY", "LOWER_SIDE", "BACK_OF_KNEE",
        "ELBOW_INNER", "INNER_THIGH", "FINGERS", "TOES", "TAILBONE",
    )
}

private val ON_SECOND_MAP: Boolean by lazy { secondMapNamesIn(PainRegion.entries.mapTo(HashSet()) { it.name }) }

/**
 * The pain mentions of a message's [clauses]: one per body family it names, in the order they first
 * appear; and when it names no area but says a pain — «всё болит», "I'm in pain" — one with no
 * family, unless the pain is the throat's, an ear's or a tooth's ("something else").
 */
internal fun painMentionsIn(
    clauses: List<Clause>,
    setting: ChatSetting,
    secondMap: Boolean = ON_SECOND_MAP,
): List<PainWordMention> {
    val licences = licences(clauses)
    val hits = clauses.flatMapIndexed { index, clause -> clauseHits(index, clause, licences[index], secondMap) }
    val values = clauses.map { clauseValues(it, setting) }
    val standalone = hits.isEmpty() && clauses.indices.any { licences[it] == Licence.FULL && !organIn(clauses[it]) }
    if (hits.isEmpty() && !standalone) return emptyList()
    val families: Map<String?, List<Indexed>> =
        if (standalone) mapOf(null to emptyList()) else hits.groupBy { it.hit.family }
    val single = families.size == 1
    return families.map { (family, own) ->
        val holding = own.mapTo(HashSet()) { it.clause }
        val bound = values.filterIndexed { index, _ ->
            index in holding || (single && hits.none { it.clause == index })
        }
        val at = own.firstOrNull()?.hit?.at ?: clauses.firstOrNull()?.offsets?.firstOrNull() ?: 0
        painMention(at, family, own.map { it.hit }, bound)
    }
}

/** An area word read in clause [clause] of its message. */
private data class Indexed(val clause: Int, val hit: PainHit)

/**
 * What each clause lets its area words be: its own words of pain, injury or kind; or, cut from the
 * clause before only by a comma, a dash or a word of contrast and holding no verb of its own, that
 * clause's — forward only («выпил виски, болит голова» lets the head be a pain, not the whisky).
 */
private fun licences(clauses: List<Clause>): List<Licence> {
    val licences = mutableListOf<Licence>()
    clauses.forEachIndexed { index, clause ->
        val own = clause.bodyKeys().maxOfOrNull { it.licence } ?: Licence.NONE
        val inherited = if (index > 0 && clause.joined && !clause.verb) licences[index - 1] else Licence.NONE
        licences += maxOf(own, inherited)
    }
    return licences
}

private fun Clause.bodyKeys(): List<BodyKey> = kept.mapNotNull { it.match.payload as? BodyKey }

/** Whether a word of the throat, an ear, an eye, the skin or a tooth stands in [clause]: "something else". */
private fun organIn(clause: Clause): Boolean =
    clause.kept.any { it.match.kind == MATCH_TOPIC && it.match.payload == Symptom.OTHER }

/** The area words of [clause] (the [index]th of its message) that [licence] lets be a pain, with what binds to each. */
private fun clauseHits(index: Int, clause: Clause, licence: Licence, secondMap: Boolean): List<Indexed> {
    if (licence == Licence.NONE) return emptyList()
    val words = MessageWords(clause.words)
    val placed = clause.kept.filter { (it.match.payload as? BodyKey)?.areas?.isNotEmpty() == true }
    val areaAt = placed.map { it.from..it.to }
    val sides = bindings(SIDE_WORDS.longest(words), areaAt) { hit, at -> sideFits(hit, at, areaAt) }
    val qualifiers = bindings(QUALIFIER_WORDS.longest(words), areaAt) { _, _ -> true }
    val limb = limbOf(placed)
    val said = sides.map { near -> near.flatMapTo(HashSet()) { it.sides } }
    // The side said with a finger's limb is the finger's («палец правой руки»): the limb's word is absorbed.
    val limbSides = placed.indices
        .filter { at -> (placed[at].match.payload as BodyKey).areas.any { it.id in limbWords(limb) } }
        .flatMapTo(HashSet()) { said[it] }
    return placed.flatMapIndexed { at, place ->
        (place.match.payload as BodyKey).areas.mapNotNull { area ->
            val own = if (area.id == FINGERS) said[at].ifEmpty { limbSides } else said[at]
            val levels = qualifiers[at].mapNotNullTo(HashSet()) { it.side }
            val bound = Bound(
                sides = own + pluralSides(words, place, own + levels),
                qualifiers = qualifiers[at].mapNotNullTo(HashSet()) { it.qualifier },
                levelSides = levels,
                limb = limb,
            )
            hitOf(area, clause, place, bound, licence, words, secondMap)?.let { Indexed(index, it) }
        }
    }.absorbingLimbs(limb)
}

/** What binds to one area word: the sides and qualifiers said beside it, and for a finger, whose limb. */
private data class Bound(
    val sides: Set<BodySide>,
    val qualifiers: Set<Qualifier>,
    val levelSides: Set<BodySide>,
    val limb: String?,
)

/**
 * For each area word, the values of [hits] (sides, qualifiers) that bind to it: each value to the
 * area word nearest it in the clause, three words away at most, the one after it on a tie; [fits]
 * says where a value may stand at all.
 */
private fun <T> bindings(
    hits: List<KeyHit<T>>,
    areas: List<IntRange>,
    fits: (KeyHit<T>, Int) -> Boolean,
): List<List<T>> {
    val bound = List(areas.size) { mutableListOf<T>() }
    hits.forEach { hit ->
        val near = areas.indices
            .map { it to distance(hit.from..hit.to, areas[it]) }
            .filter { (index, gap) -> gap <= SIDE_REACH && fits(hit, index) }
            .minWithOrNull(compareBy({ it.second }, { if (areas[it.first].first > hit.to) 0 else 1 }))
        near?.let { bound[it.first] += hit.value }
    }
    return bound
}

/** How many words stand between two spans (0 when they touch or overlap). */
private fun distance(a: IntRange, b: IntRange): Int = when {
    a.overlaps(b) -> 0
    a.last < b.first -> b.first - a.last - 1
    else -> a.first - b.last - 1
}

/** A side reaches an area word three words away at most. */
private const val SIDE_REACH = 3

/** Whether side word [hit] may bind to the area word at [index]: "right" only straight before it. */
private fun sideFits(hit: KeyHit<SideWord>, index: Int, areas: List<IntRange>): Boolean {
    val area = areas[index]
    return when {
        hit.value.sides.isEmpty() -> false
        hit.value.before -> area.first == hit.to + 1
        hit.value.adjacent -> area.first == hit.to + 1 || area.last == hit.from - 1
        else -> true
    }
}

/**
 * Both sides, when the area word itself is a plural («колени», "knees"); one that is also a singular's
 * genitive ([PLURAL_OR_GENITIVE]) only when no side is [said] with it.
 */
private fun pluralSides(words: MessageWords, place: PlacedMatch, said: Set<BodySide>): Set<BodySide> {
    val forms = setOf((place.from..place.to).joinToString(" ") { words.tokens[it] }, words.tokens[place.to])
    val plural = forms.any { it in PLURAL_AREA_WORDS || (said.isEmpty() && it in PLURAL_OR_GENITIVE) }
    return if (plural) BOTH else emptySet()
}

/** The limb a finger word is on: a hand's or an arm's word in its clause, a foot's or a leg's. */
private fun limbOf(placed: List<PlacedMatch>): String? {
    val ids = placed.flatMapTo(HashSet()) { place -> (place.match.payload as BodyKey).areas.map { it.id } }
    return when {
        FINGERS !in ids -> null
        ids.any { it in HAND_WORDS } -> LIMB_HAND
        ids.any { it in FOOT_WORDS } -> LIMB_FOOT
        else -> null
    }
}

private const val FINGERS = "FINGERS"
private const val LIMB_HAND = "HAND"
private const val LIMB_FOOT = "FOOT"
private val HAND_WORDS = setOf("ARM", "HAND", "WRIST")
private val FOOT_WORDS = setOf("LEG", "FOOT")

/** The areas a finger's [limb] is told by: a hand's or an arm's, a foot's or a leg's; none without one. */
private fun limbWords(limb: String?): Set<String> = when (limb) {
    LIMB_HAND -> HAND_WORDS
    LIMB_FOOT -> FOOT_WORDS
    else -> emptySet()
}

/** A finger told with its limb ("палец на руке") is the finger's: the limb's word is no area of its own there. */
private fun List<Indexed>.absorbingLimbs(limb: String?): List<Indexed> = filterNot { it.hit.entry in limbWords(limb) }

/** [area] as a pain in [clause], with what [bound] it; null when its clause lacks a word it needs. */
private fun hitOf(
    area: Area,
    clause: Clause,
    place: PlacedMatch,
    bound: Bound,
    licence: Licence,
    words: MessageWords,
    secondMap: Boolean,
): PainHit? {
    if (licence == Licence.LEG_FOOT && area.family != "LEG" && area.family != "FOOT") return null
    if (!needsHold(area, clause, place, words)) return null
    val resolved = resolve(area, bound, secondMap)
    return PainHit(
        entry = area.id,
        family = resolved.family ?: area.family,
        at = clause.offsets[place.from],
        areas = resolved.areas,
        named = bound.sides + bound.levelSides,
        map = resolved.map,
        view = resolved.view ?: area.view,
        listOnly = area.listOnly && !resolved.map,
    )
}

/** Whether the words [area] needs beside it stand in [clause]: «чашечка» beside the knee; "feet" not after a number. */
private fun needsHold(area: Area, clause: Clause, place: PlacedMatch, words: MessageWords): Boolean =
    when (area.needs) {
        null -> true
        NOT_AFTER_A_NUMBER -> place.from == 0 || !words.tokens[place.from - 1].all { it.isDigit() }
        else -> clause.bodyKeys().any { key -> key.areas.any { it.id == area.needs } }
    }

/**
 * The places an area word names, worked out from its row and what binds to it; [map]: too wide for
 * chips. [family] and [view], when narrowing moved them: the outside of the thigh is the hip, the back
 * of the knee is seen from behind.
 */
private data class Places(
    val areas: List<RegionSide>,
    val map: Boolean = false,
    val family: String? = null,
    val view: PainView? = null,
)

/** [area]'s places by its resolver: on the second map when [secondMap], else the first's names, nothing narrowed. */
private fun resolve(area: Area, bound: Bound, secondMap: Boolean): Places {
    val names = if (secondMap) area.v2 ?: area.v1 else area.v1
    if (!secondMap) return firstMap(area, names, bound)
    val sides = bound.sides + bound.levelSides
    return when (area.resolver) {
        Resolver.PLAIN, Resolver.CHEST -> Places(sided(names, sides))
        Resolver.HEAD -> Places(headNamed(area, sides))
        Resolver.MAP -> Places(sided(names, sides), map = true)
        Resolver.WHOLE -> Places(emptyList(), map = true)
        else -> narrowedPlaces(area, bound, sides)
    }
}

/** The places of a word a qualifier or a side narrows on the second map: the belly, the back, the thigh, the knee… */
private fun narrowedPlaces(area: Area, bound: Bound, sides: Set<BodySide>): Places = when (area.resolver) {
    Resolver.BELLY -> belly(area, bound.qualifiers, side(sides))
    Resolver.BACK -> back(area, bound.qualifiers, side(sides))
    Resolver.THIGH -> thigh(bound.qualifiers, sides)
    Resolver.KNEE -> behind(Places(sided(listOf(narrowed(bound, Qualifier.BACK, "BACK_OF_KNEE", "KNEE")), sides)))
    Resolver.ELBOW -> Places(sided(listOf(narrowed(bound, Qualifier.INNER, "ELBOW_INNER", "ELBOW")), sides))
    Resolver.ARM -> side(sides)?.let { Places(sided(ARM_PARTS, setOf(it))) } ?: Places(emptyList(), map = true)
    else -> fingers(bound.limb, sides)
}

/**
 * On the first map: its own names, nothing narrowed; the head's chips; the wide words as the map; a
 * finger as the wrist and hand, a toe as the ankle and foot, either one as both.
 */
private fun firstMap(area: Area, names: List<String>, bound: Bound): Places {
    val sides = bound.sides + bound.levelSides
    return when (area.resolver) {
        Resolver.HEAD -> Places(headNamed(area, sides))

        Resolver.MAP, Resolver.WHOLE -> Places(sided(names, sides), map = names.isEmpty())

        Resolver.ARM -> side(sides)?.let { Places(sided(ARM_PARTS, setOf(it))) } ?: Places(emptyList(), map = true)

        Resolver.THIGH -> Places(emptyList(), map = true)

        Resolver.FINGERS -> when (bound.limb) {
            LIMB_HAND -> Places(sided(listOf("WRIST_HAND"), sides))
            LIMB_FOOT -> Places(sided(listOf("ANKLE_FOOT"), sides), family = "FOOT")
            else -> Places(sided(listOf("WRIST_HAND", "ANKLE_FOOT"), sides))
        }

        else -> Places(sided(names, sides))
    }
}

private val ARM_PARTS = listOf("UPPER_ARM", "ELBOW", "FOREARM", "WRIST_HAND")

/** [narrow] when [qualifier] binds to the area word, else [wide]: the back of the knee, the inside of the elbow. */
private fun narrowed(bound: Bound, qualifier: Qualifier, narrow: String, wide: String): String =
    if (qualifier in bound.qualifiers) narrow else wide

/** The one side named, when exactly one is. */
private fun side(sides: Set<BodySide>): BodySide? = sides.singleOrNull()

/** [names] on the sides named: a paired place on each side named, on both when none is; else in the centre. */
private fun sided(names: List<String>, sides: Set<BodySide>): List<RegionSide> = names.flatMap { name ->
    val fixed = RegionSide.parse(name)
    val region = PainRegion.entries.firstOrNull { it.name == name.substringBefore(':') }
    when {
        fixed != null -> listOf(fixed)
        region == null -> emptyList()
        !region.paired -> listOf(RegionSide(region, BodySide.CENTRE))
        sides.isEmpty() -> listOf(RegionSide(region, BodySide.LEFT), RegionSide(region, BodySide.RIGHT))
        else -> sides.filter { it != BodySide.CENTRE }.map { RegionSide(region, it) }
    }
}.sortedWith(REGION_SIDE_ORDER)

/** The head's places a head word names: its own, on the side said; the whole head none, or with a side the temple. */
private fun headNamed(area: Area, sides: Set<BodySide>): List<RegionSide> = when {
    area.v1.isNotEmpty() -> sided(area.v1, sides)
    sides.isNotEmpty() -> sided(listOf("TEMPLE"), sides)
    else -> emptyList()
}

/**
 * The belly, narrowed by what is said with it: low and a side, the lower
 * side; low, the lower belly and both lower sides; high and a side, under the ribs; high, the upper
 * belly and under both sets of ribs; a side alone, under the ribs and the lower side on it; the
 * middle, the upper and the lower belly; nothing, the map framed on the belly. The places named
 * outright keep theirs, a side moving them to the side: the lower belly said with "right" is the
 * lower side on the right.
 */
private fun belly(area: Area, qualifiers: Set<Qualifier>, side: BodySide?): Places {
    val named = BELLY_LEVELS.entries.firstOrNull { it.value.centre == area.id }?.key
    val level = named ?: listOf(Qualifier.LOWER, Qualifier.UPPER, Qualifier.MIDDLE).firstOrNull { it in qualifiers }
    val row = BELLY_LEVELS[level]
    val one = setOfNotNull(side)
    val names = when {
        row != null && side != null -> sided(listOf(row.sided), one)
        row != null && named != null -> sided(listOf(row.centre), one)
        row != null -> sided(listOf(row.centre, row.sided), emptySet())
        level == Qualifier.MIDDLE -> sided(listOf("UPPER_BELLY", "LOWER_BELLY"), emptySet())
        side != null -> sided(listOf("UNDER_RIBS", "LOWER_SIDE"), one)
        else -> emptyList()
    }
    return Places(names, map = names.isEmpty())
}

/** A level of the belly: its place in the middle, and the one on each side of it. */
private class BellyLevel(val centre: String, val sided: String)

private val BELLY_LEVELS = mapOf(
    Qualifier.LOWER to BellyLevel(centre = "LOWER_BELLY", sided = "LOWER_SIDE"),
    Qualifier.UPPER to BellyLevel(centre = "UPPER_BELLY", sided = "UNDER_RIBS"),
)

/**
 * The back, narrowed: high, the upper back, and with a side the shoulder blade; low, the lower back,
 * and with a side the loin; a side alone, the shoulder blade and the loin on it; nothing, the map
 * framed on the back. The upper and the lower back named outright are each their level.
 */
private fun back(area: Area, qualifiers: Set<Qualifier>, side: BodySide?): Places {
    val level = when (area.id) {
        "UPPER_BACK" -> Qualifier.UPPER
        "LOWER_BACK" -> Qualifier.LOWER
        else -> listOf(Qualifier.UPPER, Qualifier.LOWER).firstOrNull { it in qualifiers }
    }
    val one = side?.let { setOf(it) }.orEmpty()
    val names = when {
        level == Qualifier.UPPER && side != null -> sided(listOf("SHOULDER_BLADE"), one)
        level == Qualifier.UPPER -> sided(listOf("UPPER_BACK"), one)
        level == Qualifier.LOWER && side != null -> sided(listOf("FLANK"), one)
        level == Qualifier.LOWER -> sided(listOf("LOWER_BACK"), one)
        side != null -> sided(listOf("SHOULDER_BLADE", "FLANK"), one)
        else -> emptyList()
    }
    return Places(names, map = names.isEmpty())
}

/** The thigh, narrowed: the front, the back, the inside; the outside is the hip; nothing, the map on the legs. */
private fun thigh(qualifiers: Set<Qualifier>, sides: Set<BodySide>): Places {
    val name = when {
        Qualifier.FRONT in qualifiers -> "THIGH_FRONT"
        Qualifier.BACK in qualifiers -> "THIGH_BACK"
        Qualifier.INNER in qualifiers -> "INNER_THIGH"
        Qualifier.OUTER in qualifiers -> "HIP"
        else -> return Places(emptyList(), map = true)
    }
    return behind(Places(sided(listOf(name), sides), family = if (name == "HIP") "HIP" else null))
}

/** [places] seen from behind when they are a back's: the back of the knee, the back of the thigh. */
private fun behind(places: Places): Places =
    if (places.areas.any { it.region in SEEN_FROM_BEHIND }) places.copy(view = PainView.BACK) else places

private val SEEN_FROM_BEHIND = setOf(PainRegion.BACK_OF_KNEE, PainRegion.THIGH_BACK)

/** A finger: the fingers with a hand's word beside it, the toes with a foot's, else both — each a family of its own. */
private fun fingers(limb: String?, sides: Set<BodySide>): Places = when (limb) {
    LIMB_HAND -> Places(sided(listOf("FINGERS"), sides))
    LIMB_FOOT -> Places(sided(listOf("TOES"), sides), family = "FOOT")
    else -> Places(sided(listOf("FINGERS", "TOES"), sides))
}

/** What is said in one clause about a pain in it, as [painMention] combines it. */
internal data class PainValues(
    val types: Set<PainType> = emptySet(),
    val onsets: Set<SymptomOption> = emptySet(),
    val noticed: Set<SymptomOption> = emptySet(),
    val still: Boolean = false,
    val gone: Boolean = false,
    val same: Boolean = false,
    val new: Boolean = false,
    val sudden: Boolean = false,
    val severe: Boolean = false,
    val mild: Boolean = false,
    val signs: Set<SymptomOption> = emptySet(),
    val numbers: Set<Int> = emptySet(),
)

/** What [clause] says of a pain: its kinds, times, state, how bad, its signs and its number out of ten. */
private fun clauseValues(clause: Clause, setting: ChatSetting): PainValues {
    val words = MessageWords(clause.words)
    val status = STATUS_WORDS.longest(words).map { it.value }
    val same = SAME_WORDS.longest(words).map { it.value }
    val severity = SEVERITY_WORDS.longest(words).map { it.value }
    val legOrFoot = clause.bodyKeys().any { key -> key.areas.any { it.family == "LEG" || it.family == "FOOT" } }
    val cramp = legOrFoot && words.tokens.indices.any { CRAMP_WORD.at(words, it) != null }
    return PainValues(
        types = TYPE_WORDS.longest(words).mapNotNullTo(HashSet()) { it.value } +
            setOfNotNull(PainType.CRAMPING.takeIf { cramp }),
        onsets = onsetsIn(clause.words, setting),
        noticed = NOTICED_WORDS.longest(words).mapTo(HashSet()) { it.value },
        still = true in status,
        gone = false in status,
        same = true in same,
        new = false in same,
        sudden = SUDDEN_WORDS.any(words),
        severe = true in severity,
        mild = false in severity,
        signs = SIGN_WORDS.longest(words).flatMapTo(HashSet()) { it.value },
        numbers = nrsIn(clause.raw, painSaid = clause.bodyKeys().any { it.pain }),
    )
}

/**
 * The pain of [family] read from [hits], with every value of [values] combined: one number, one
 * onset, one moment of noticing — none when the words name two — still or gone only when they say
 * one of them, and the red flags their words of suddenness, severity and the rest point at.
 */
internal fun painMention(at: Int, family: String?, hits: List<PainHit>, values: List<PainValues>): PainWordMention {
    fun <T> one(of: (PainValues) -> Set<T>): T? = values.flatMapTo(HashSet(), of).singleOrNull()
    val said = PainValues(
        types = values.flatMapTo(HashSet()) { it.types },
        onsets = values.flatMapTo(HashSet()) { it.onsets },
        noticed = values.flatMapTo(HashSet()) { it.noticed },
        still = values.any { it.still },
        gone = values.any { it.gone },
        same = values.any { it.same },
        new = values.any { it.new },
        sudden = values.any { it.sudden },
        severe = values.any { it.severe },
        mild = values.any { it.mild },
        signs = values.flatMapTo(HashSet()) { it.signs },
        numbers = values.flatMapTo(HashSet()) { it.numbers },
    )
    val still = values.any { it.still }
    val gone = values.any { it.gone }
    val same = values.any { it.same }
    val new = values.any { it.new }
    val sudden = values.any { it.sudden }
    val severe = values.any { it.severe }
    val mild = values.any { it.mild }
    val signs = values.flatMapTo(HashSet()) { it.signs } +
        setOfNotNull(SymptomOption.SUDDEN_SEVERE.takeIf { sudden }) +
        (if (severe && !mild) setOf(SymptomOption.SUDDEN_OR_SEVERE, SymptomOption.VERY_PAINFUL) else emptySet())
    return PainWordMention(
        at = at,
        mention = TopicMention(Symptom.PAIN, family.orEmpty()),
        family = family,
        candidates = hits.flatMap { it.areas }.distinct().sortedWith(REGION_SIDE_ORDER),
        proposal = painProposal(family, hits),
        nrs = one { it.numbers },
        types = values.flatMapTo(HashSet()) { it.types },
        onset = one { it.onsets },
        noticed = one { it.noticed },
        still = if (still != gone) still else null,
        same = if (same != new) same else null,
        severe = severe,
        sudden = sudden,
        mild = mild,
        signs = signs,
        hits = hits,
        said = said,
    )
}

/**
 * The first widget for a pain told by [hits]: the chest's own questions for the chest; the
 * head's chips, the named first; the map framed on the family for a word too wide for chips, or for
 * several places of one family; else chips — one place on the side named, on both sides "both" first,
 * on no side right, left and both; several places, a chip each. No area word at all: the whole body.
 */
internal fun painProposal(family: String?, hits: List<PainHit>): PainProposal {
    if (family == FAMILY_CHEST) return PainProposal.Chest
    if (family == FAMILY_HEAD) return PainProposal.Head(headChips(hits))
    val first = hits.firstOrNull() ?: return PainProposal.Body(PainFrame(PainView.FRONT, null))
    val frame = PainFrame(first.view, family)
    val regions = hits.flatMap { it.areas }.map { it.region }.distinct()
    return when {
        hits.any { it.map } -> PainProposal.Body(if (family == null) PainFrame(PainView.FRONT, null) else frame)

        regions.size > 1 && hits.size > 1 -> PainProposal.Body(frame)

        regions.size == 1 && regions.single().paired -> {
            val chips = sidedChips(regions.single(), hits.flatMapTo(HashSet()) { it.named })
            PainProposal.Chips(chips, frame.takeUnless { first.listOnly })
        }

        else -> PainProposal.Chips(
            first.areas.rightFirst().map { AreaChoice(listOf(it)) },
            frame.takeUnless { first.listOnly },
        )
    }
}

/** A paired place's chips: the side named; "both" first when both are named; else right, left and both. */
private fun sidedChips(region: PainRegion, named: Set<BodySide>): List<AreaChoice> {
    val right = AreaChoice(listOf(RegionSide(region, BodySide.RIGHT)))
    val left = AreaChoice(listOf(RegionSide(region, BodySide.LEFT)))
    val both = AreaChoice(listOf(RegionSide(region, BodySide.LEFT), RegionSide(region, BodySide.RIGHT)), both = true)
    val sides = named - BodySide.CENTRE
    return when (sides) {
        RIGHT -> listOf(right)
        LEFT -> listOf(left)
        BOTH -> listOf(both, right, left)
        else -> listOf(right, left, both)
    }
}

/** Head to foot, and on each place right before left: the order the chips stand in. */
private fun List<RegionSide>.rightFirst(): List<RegionSide> =
    sortedWith(compareBy({ it.region.ordinal }, { if (it.side == BodySide.RIGHT) 0 else 1 }))

/** The head's chips in their order: the forehead, the temples, the top, the back, the jaw. */
internal val HEAD_CHIPS: List<RegionSide> = listOf(
    RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE),
    RegionSide(PainRegion.TEMPLE, BodySide.RIGHT),
    RegionSide(PainRegion.TEMPLE, BodySide.LEFT),
    RegionSide(PainRegion.HEAD_TOP, BodySide.CENTRE),
    RegionSide(PainRegion.HEAD_BACK, BodySide.CENTRE),
    RegionSide(PainRegion.JAW, BodySide.RIGHT),
    RegionSide(PainRegion.JAW, BodySide.LEFT),
)

/** The head's chips with the places [hits] name first, in the order they were named. */
private fun headChips(hits: List<PainHit>): List<RegionSide> {
    val named = hits.flatMap { hit -> hit.areas.sortedBy { HEAD_CHIPS.indexOf(it) } }.distinct()
    return named + (HEAD_CHIPS - named.toSet())
}

/** A head bandaged (🤕): a pain of the head, nothing named on it. */
internal fun headEmojiMention(at: Int): PainWordMention = painMention(at, FAMILY_HEAD, emptyList(), emptyList())

/**
 * The numbers out of ten in [raw] (a clause): "6/10", «6 из 10», «шесть из десяти», "6 out of 10",
 * «6 баллов» beside a word of pain ([painSaid]); never 0 or more than ten, a decimal ("6,5/10"), a
 * range ("6-7/10"), a date ("6/10/2026"), nor after «с», «от», «до», "since", "from", "until".
 */
internal fun nrsIn(said: String, painSaid: Boolean): Set<Int> {
    // "٦/١٠" is six out of ten as well: the patterns read ASCII digits, as the tests do.
    val raw = said.asciiDigits()
    val found = HashSet<Int>()
    NRS_PATTERNS.forEach { pattern ->
        pattern.findAll(raw).forEach { match ->
            val value = numberOf(match.groupValues[1])
            val before = raw.substring(0, match.range.first)
            val cut = before.trimEnd().substringAfterLast(' ').lowercase(Locale.ROOT)
            val joined = before.lastOrNull()?.let { it in JOINING_MARKS } == true
            val score = value?.takeIf { it in PAIN_NRS_RANGE }
            if (score != null && cut !in NOT_A_SCORE_AFTER && !joined) found += score
        }
    }
    if (painSaid) {
        POINTS.findAll(raw).forEach { match ->
            numberOf(match.groupValues[1])?.takeIf { it in PAIN_NRS_RANGE }?.let(found::add)
        }
    }
    return found
}

private const val NUMBER = "([0-9]{1,2}|один|одна|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|" +
    "one|two|three|four|five|six|seven|eight|nine|ten)"

private val NRS_PATTERNS = listOf(
    // No digit after the ten, nor a date's slash or a decimal's tail: "6/100" is no 6 out of 10, "6/10." is.
    Regex("(?<![\\p{L}0-9])$NUMBER$SPACE*/$SPACE*10(?![0-9/]|[.,][0-9])"),
    Regex("(?<![\\p{L}0-9])$NUMBER$SPACE+из$SPACE+(?:10|десяти)(?![0-9])"),
    Regex("(?<![\\p{L}0-9])$NUMBER$SPACE+(?:out$SPACE+)?of$SPACE+(?:10|ten)(?![0-9])"),
)

private val POINTS = Regex("(?<![\\p{L}0-9.,-])([0-9]{1,2})$SPACE*балл(?:а|ов)?(?!\\p{L})")

private val NOT_A_SCORE_AFTER = setOf("с", "со", "от", "до", "since", "from", "until")

/** A mark straight before a number that makes it part of another: "6,5", "6-7", "1/6/10". */
private const val JOINING_MARKS = "-,./"

private val NUMBER_WORDS = listOf(
    listOf("один", "одна", "one"), listOf("два", "две", "two"), listOf("три", "three"), listOf("четыре", "four"),
    listOf("пять", "five"), listOf("шесть", "six"), listOf("семь", "seven"), listOf("восемь", "eight"),
    listOf("девять", "nine"), listOf("десять", "ten"),
)

private fun numberOf(text: String): Int? =
    text.toIntOrNull() ?: NUMBER_WORDS.indexOfFirst { text in it }.takeIf { it >= 0 }?.plus(1)

/** The body family of each place, by its name and then by its root's: a pain thread's marks', and a mention's. */
internal fun bodyFamilies(region: PainRegion): Set<String> =
    FAMILY_BY_NAME[region.name] ?: FAMILY_BY_NAME[region.root.name].orEmpty()

/** By name: a later map's areas need a row, not code. */
private val FAMILY_BY_NAME: Map<String, Set<String>> = buildMap {
    listOf("HEAD_FRONT", "TEMPLE", "HEAD_TOP", "HEAD_BACK").forEach { put(it, setOf(FAMILY_HEAD)) }
    put("JAW", setOf("JAW"))
    put("NECK", setOf("NECK"))
    put("SHOULDER", setOf("SHOULDER"))
    put("CHEST", setOf(FAMILY_CHEST))
    listOf("UPPER_BACK", "LOWER_BACK", "GLUTE").forEach { put(it, setOf("BACK")) }
    put("ABDOMEN", setOf("BELLY"))
    listOf("UPPER_ARM", "ELBOW", "FOREARM").forEach { put(it, setOf("ARM")) }
    put("WRIST_HAND", setOf("HAND"))
    put("HIP", setOf("HIP"))
    put("KNEE", setOf("KNEE"))
    listOf("THIGH_FRONT", "THIGH_BACK", "SHIN", "CALF").forEach { put(it, setOf("LEG")) }
    put("ANKLE_FOOT", setOf("FOOT"))
    put("BACK_OF_KNEE", setOf("KNEE", "LEG"))
}

/**
 * The chip's words for a pain [mention]: "Pain: Knee", by the family's own name on the map — the
 * family's root area where it has one, the body's part where it has none. Null for a pain named
 * nowhere: the topic's own name.
 */
internal fun painMentionLabel(mention: TopicMention): ChatText? {
    val name = FAMILY_NAMES[mention.detail] ?: return null
    return ChatText(R.string.pain_chat_topic_area, listOf(PieceArg.Res(name)))
}

private val FAMILY_NAMES: Map<String, Int> = mapOf(
    FAMILY_HEAD to R.string.pain_group_head,
    "JAW" to R.string.pain_region_jaw,
    "NECK" to R.string.pain_region_neck,
    "SHOULDER" to R.string.pain_region_shoulder,
    FAMILY_CHEST to R.string.pain_region_chest,
    "BACK" to R.string.muscle_back,
    "BELLY" to R.string.pain_region_abdomen,
    "ARM" to R.string.muscle_arms,
    "HAND" to R.string.pain_region_wrist_hand,
    "HIP" to R.string.pain_region_hip,
    "KNEE" to R.string.pain_region_knee,
    "LEG" to R.string.muscle_legs,
    "FOOT" to R.string.pain_region_ankle_foot,
)

/**
 * Whether a pain [thread] already takes up [mention]: it marked an area of the mention's
 * family, or — for a pain named nowhere — any area at all.
 */
internal fun painThreadServes(thread: ChatThread, mention: TopicMention): Boolean {
    if (mention.topic != Symptom.PAIN) return false
    val codes = thread.report.forSave().values[SymptomStep.PAIN_AREA].orEmpty()
    val marked = codes.split(',').mapNotNull { RegionSide.parse(it) }
    if (mention.detail.isEmpty()) return marked.isNotEmpty()
    return marked.any { mention.detail in bodyFamilies(it.region) }
}

/**
 * The pain a pain thread takes up, read from every message it reads, newest first ([threadMessages]):
 * the pain of the family its start named ("PAIN:KNEE"), or for a thread begun from the menu the first
 * its own words name. Null when its words name no pain.
 *
 * The rules' words and what the model's readings kept with the messages told are one input: a pain
 * the rules read takes in what the readings told of the same family or of none ([toldPain],
 * [withTold]) — their places beside the rules' ones, and of each value theirs only where the rules
 * said none — and with none the rules read, the readings' pain stands alone, so "bolit koleno" the
 * model read offers the knee's chips as «болит колено» does. Without [readings], the pain of the
 * rules' words alone.
 */
internal fun threadPainMention(state: ChatState, readings: Boolean = true): PainWordMention? {
    val setting = state.wordsSetting()
    val messages = threadMessages(state)
    val words = messages.flatMap { readWords(it.text, setting).mentions.filterIsInstance<PainWordMention>() }
    val ruled = startedPain(state, words)
    val read = if (readings) messages.mapNotNull { it.read?.toldPain(0) } else emptyList()
    return if (ruled == null) {
        startedPain(state, read)
    } else {
        ruled.withTold(read.filter { it.family == null || it.family == ruled.family })
    }
}

/**
 * [this] pain the rules read with what the readings' pains [told] add, never take away:
 * their places beside the rules' ones — a side the two read apart offers both — their kinds
 * and red flags' marks beside the rules'; and a start, a moment, a number out of ten, still or gone,
 * the same or new only where the rules' words said none, so a mark of theirs and the ruler's rest on
 * their number stand whatever a reading told. A reading's "mild" never takes the rules' "very bad" off.
 */
internal fun PainWordMention.withTold(told: List<PainWordMention>): PainWordMention {
    val read = told.reduceOrNull { newest, older -> newest.merged(older) } ?: return this
    val rules = said
    val other = read.said
    val status = rules.still || rules.gone
    val sameness = rules.same || rules.new
    val values = PainValues(
        types = rules.types + other.types,
        onsets = rules.onsets.ifEmpty { other.onsets },
        noticed = rules.noticed.ifEmpty { other.noticed },
        still = if (status) rules.still else other.still,
        gone = if (status) rules.gone else other.gone,
        same = if (sameness) rules.same else other.same,
        new = if (sameness) rules.new else other.new,
        sudden = rules.sudden || other.sudden,
        severe = rules.severe || other.severe,
        mild = rules.mild || (other.mild && !rules.severe),
        signs = rules.signs + other.signs,
        numbers = rules.numbers.ifEmpty { other.numbers },
    )
    val joined = painMention(at, family, hits + read.hits, listOf(values))
    // The places the readings told add chips; they never narrow the rules' chips to a side only a
    // reading named, nor turn them into the map for another area of the family.
    return if (joined.proposal.keeps(proposal)) joined else joined.copy(proposal = proposal)
}

/** Whether [this] proposal offers every place [rules] does, the way it does: the same, or its chips and more. */
private fun PainProposal.keeps(rules: PainProposal): Boolean = when {
    this == rules -> true
    this is PainProposal.Chips && rules is PainProposal.Chips -> choices.containsAll(rules.choices)
    this is PainProposal.Head && rules is PainProposal.Head -> chips.containsAll(rules.chips)
    else -> this is PainProposal.Body && rules is PainProposal.Body
}

/**
 * The pain the model's readings alone told in [state]'s thread, picked as [threadPainMention] picks
 * one: what the next-step decider prefills an ordinary step from, where the rules agree.
 */
internal fun readPainMention(state: ChatState): PainWordMention? =
    if (state.readsInThread()) startedPain(state, threadMessages(state).mapNotNull { it.read?.toldPain(0) }) else null

/**
 * Of [mentions], newest first, the pain of the family [state]'s start named, or for a thread begun from
 * the menu the first one's, all of its key merged.
 */
private fun startedPain(state: ChatState, mentions: List<PainWordMention>): PainWordMention? {
    val key = state.thread.from?.removePrefix(ROUTE_FOLLOW)?.takeIf { it.startsWith(Symptom.PAIN.name) }
    val family = key?.removePrefix(Symptom.PAIN.name)?.removePrefix(":")
    val own = mentions.filter { family == null || it.family.orEmpty() == family }
    val chosen = own.ifEmpty { if (family == null) mentions else emptyList() }
    val kept = chosen.filter { it.mention.key == chosen.first().mention.key }
    return kept.reduceOrNull { newest, older -> newest.merged(older) }
}

/**
 * The first widget of a pain thread: what its words propose; for a start that named a family the
 * words no longer hold — the chest's "Mark it on the pain map" after a chest pain thread — the map
 * framed on that family (the chest's, [PainProposal.Chest]); else the whole body.
 */
internal fun painProposalFor(state: ChatState): PainProposal {
    threadPainMention(state)?.let { return it.proposal }
    val family = state.thread.from?.removePrefix(ROUTE_FOLLOW)?.takeIf { it.startsWith(Symptom.PAIN.name + ":") }
        ?.substringAfter(':')
    return when (family) {
        null -> PainProposal.Body(PainFrame(PainView.FRONT, null))
        FAMILY_CHEST -> PainProposal.Chest
        else -> PainProposal.Body(PainFrame(if (family == "BACK") PainView.BACK else PainView.FRONT, family))
    }
}

/**
 * The marks a pain's words put on [step]'s chips among [options]: when it started, when it
 * was noticed, its kinds, whether it is still there, whether it is the same, the red flags its words
 * point at. Never on a question about suicide or an emergency, which no table here names. Never
 * selected, never armed: the person's tap is the answer.
 */
internal fun painMarks(step: SymptomStep, mention: PainWordMention?, options: List<SymptomOption>): Set<SymptomOption> {
    mention ?: return emptySet()
    val marks = when (step) {
        SymptomStep.ONSET -> setOfNotNull(mention.onset)
        SymptomStep.PAIN_NOTICED -> setOfNotNull(mention.noticed)
        SymptomStep.PAIN_TYPES -> mention.types.mapTo(HashSet()) { TYPE_OPTIONS.getValue(it) }
        SymptomStep.PAIN_STATUS -> setOfNotNull(mention.still?.let { STATUS_OPTIONS.getValue(it) })
        SymptomStep.PAIN_SAME -> setOfNotNull(mention.same?.let { SAME_OPTIONS.getValue(it) })
        SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE -> mention.signs
        else -> emptySet()
    }
    return marks.filterTo(LinkedHashSet()) { it in options }
}

private val STATUS_OPTIONS = mapOf(true to SymptomOption.STILL_HURTS, false to SymptomOption.GONE)

private val SAME_OPTIONS = mapOf(true to SymptomOption.PAIN_SAME_YES, false to SymptomOption.PAIN_NEW)

private val TYPE_OPTIONS: Map<PainType, SymptomOption> = mapOf(
    PainType.ACHING to SymptomOption.PAIN_ACHING,
    PainType.STIFF to SymptomOption.PAIN_STIFF,
    PainType.THROBBING to SymptomOption.PAIN_THROBBING,
    PainType.SHARP to SymptomOption.PAIN_SHARP,
    PainType.BURNING to SymptomOption.PAIN_BURNING,
    PainType.CRAMPING to SymptomOption.PAIN_CRAMPING,
)
