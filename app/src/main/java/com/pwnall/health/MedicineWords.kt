package com.pwnall.health

import java.text.Normalizer
import java.util.Locale

// The medicines the chat knows by name — the commonest painkillers and antidepressants, in English
// and Russian, with a brand only where its make-up is stable — and the words of taking one, being
// prescribed one, missing, stopping or taking too much of one. Pure. The list orders the chips,
// matches a name across scripts and brands, and picks which sourced question applies; the chat never
// says what a medicine is or what it holds, and never reads a dose from it. The keys are written two
// ways. The names, the classes, the words for a medicine and the words of taking one are written as
// SymptomWords.kt writes its keys (symptomWordsKey): a key matches at the start of a word ("нурофен" finds
// «нурофена»), a phrase word by word with its last word the start of one, and a key ending in a space a
// whole word only. Every other table is in the lexicons' notation (keyTable): each word whole unless it
// ends in `*` — «горсть*» finds «горсти», "handful" only that word. Either way the longest key holds
// its words ("nurofen plus" over "nurofen").

/** The groups a medicine's NHS pages speak of: painkillers first, then the antidepressants. */
internal enum class MedGroup {
    PARACETAMOL,
    NSAID,
    METAMIZOLE,
    OPIOID,
    TRIPTAN,
    SSRI,
    SNRI,
    TCA,
    NASSA,
    SARI,
    NDRI,
    MAOI,
    MELATONERGIC,
    MULTIMODAL,
    ST_JOHNS_WORT,
}

/** What a medicine's NHS page says about alcohol: nothing, best avoided, best kept low, more sleepy. */
internal enum class MedAlcohol { NONE, AVOID, LIMIT, SLEEPY }

/** What an antidepressant's NHS page says about St John's wort: nothing, named among others, not to use. */
internal enum class SjwRule { NONE, LISTED, DO_NOT_USE }

/**
 * One medicine of the built-in list. [inn] is its stable name, the frozen value a chat keeps — empty
 * for a brand whose make-up varies from one country to another, which is a painkiller and nothing
 * more is said of it. [dualUse]: prescribed for pain or sleep as well as for mood, so the chat asks
 * what it was prescribed for before it speaks of mood.
 */
internal data class KnownMedicine(
    val inn: String,
    val groups: Set<MedGroup>,
    val keys: List<String>,
    val alcohol: MedAlcohol = MedAlcohol.NONE,
    val sjw: SjwRule = SjwRule.NONE,
    val dualUse: Boolean = false,
) {
    /** What tells two entries apart: the inn, or for a brand whose make-up varies its own first key. */
    val id: String get() = inn.ifEmpty { keys.first() }
}

/** The painkillers' groups; every other group is of the antidepressant class. */
internal val PAINKILLER_GROUPS = setOf(
    MedGroup.PARACETAMOL,
    MedGroup.NSAID,
    MedGroup.METAMIZOLE,
    MedGroup.OPIOID,
    MedGroup.TRIPTAN,
)

/** Whether the medicine is a painkiller: one of the painkillers' groups, or a brand whose make-up varies. */
internal val KnownMedicine.painkiller: Boolean get() = inn.isEmpty() || groups.any { it in PAINKILLER_GROUPS }

/** Whether the medicine is of the antidepressant class (St John's wort counted with them). */
internal val KnownMedicine.antidepressant: Boolean get() = groups.any { it !in PAINKILLER_GROUPS }

/**
 * Whether the medicine is an antidepressant a doctor prescribes, whose pages speak of its first weeks,
 * of the thoughts it can bring and of stopping it: the class less St John's wort, a herbal remedy whose
 * NHS pages say none of that — it stays in the class for what may not mix with it.
 */
internal val KnownMedicine.prescribedAntidepressant: Boolean
    get() = antidepressant && MedGroup.ST_JOHNS_WORT !in groups

private fun painkiller(
    inn: String,
    vararg groups: MedGroup,
    keys: List<String>,
    alcohol: MedAlcohol = MedAlcohol.NONE,
) =
    KnownMedicine(inn, groups.toSet(), keys, alcohol)

private fun antidepressant(
    inn: String,
    group: MedGroup,
    keys: List<String>,
    alcohol: MedAlcohol,
    sjw: SjwRule,
    dualUse: Boolean = false,
) = KnownMedicine(inn, setOf(group), keys, alcohol, sjw, dualUse)

/**
 * The built-in list. Left out on purpose: «миг», «опра», «торин» (each finds an everyday word
 * or a name), cold remedies (their make-up differs from one form to another), the names of heart
 * aspirin brands («Кардиомагнил», «Тромбо АСС», Kardégic: a heart course, not a painkiller), «бад».
 * A brand that says aspirin — "Aspirin Cardio", «Аспирин Кардио» — is read as the aspirin it holds,
 * a painkiller's marks and chip order with it: the notes on mixing aspirin with another NSAID or an
 * antidepressant hold for the heart's dose as well. A medicine named in the words is shown and saved
 * by the key it was named by, with a capital ([baseName]); a key that is no name by itself — a stem,
 * one case of a name — has the name it stands for in [KEY_NAMES].
 */
internal val KNOWN_MEDICINES: List<KnownMedicine> = listOf(
    painkiller(
        "paracetamol",
        MedGroup.PARACETAMOL,
        keys = listOf(
            "paracetamol", "acetaminophen", "panadol", "tylenol", "calpol", "парацетамол", "панадол", "эффералган",
            "калпол", "тайленол", "цефекон", "перфалган", "далерон",
        ),
    ),
    painkiller(
        "ibuprofen",
        MedGroup.NSAID,
        keys = listOf(
            "ibuprofen", "nurofen", "advil", "brufen", "motrin", "calprofen", "fenbid", "ибупрофен", "нурофен",
            "бруфен", "ибупром", "фаспик", "долгит", "адвил",
        ),
        alcohol = MedAlcohol.LIMIT,
    ),
    painkiller(
        "ibuprofen+codeine",
        MedGroup.NSAID,
        MedGroup.OPIOID,
        keys = listOf("nurofen plus", "нурофен плюс"),
        alcohol = MedAlcohol.AVOID,
    ),
    painkiller(
        "ibuprofen+paracetamol",
        MedGroup.NSAID,
        MedGroup.PARACETAMOL,
        keys = listOf("nuromol", "combogesic", "ибуклин", "некст ", "некста ", "нексту ", "некстом "),
        alcohol = MedAlcohol.LIMIT,
    ),
    painkiller(
        "naproxen",
        MedGroup.NSAID,
        keys = listOf("naproxen", "naprosyn", "aleve", "feminax ultra", "напроксен", "налгезин", "апранакс"),
        alcohol = MedAlcohol.LIMIT,
    ),
    painkiller(
        "diclofenac",
        MedGroup.NSAID,
        keys = listOf(
            "diclofenac", "voltarol", "voltaren", "диклофенак", "вольтарен", "ортофен", "диклак", "наклофен", "раптен",
        ),
    ),
    painkiller(
        "ketoprofen",
        MedGroup.NSAID,
        keys = listOf("ketoprofen", "кетопрофен", "кетонал", "фламакс", "артрозилен"),
    ),
    painkiller("ketorolac", MedGroup.NSAID, keys = listOf("ketorolac", "toradol", "кеторолак", "кеторол", "кетанов")),
    painkiller(
        "nimesulide",
        MedGroup.NSAID,
        keys = listOf("nimesulide", "нимесулид", "найз", "нимесил", "нимика", "апонил"),
    ),
    painkiller(
        "aspirin",
        MedGroup.NSAID,
        keys = listOf("aspirin", "disprin", "аспирин", "ацетилсалицил", "упсарин"),
        alcohol = MedAlcohol.LIMIT,
    ),
    painkiller(
        "aspirin+paracetamol",
        MedGroup.NSAID,
        MedGroup.PARACETAMOL,
        keys = listOf("цитрамон", "аскофен"),
        alcohol = MedAlcohol.LIMIT,
    ),
    painkiller(
        "metamizole",
        MedGroup.METAMIZOLE,
        keys = listOf("metamizole", "dipyrone", "метамизол", "анальгин", "баралгин", "темпалгин"),
    ),
    painkiller(
        "codeine",
        MedGroup.OPIOID,
        keys = listOf("codeine", "solpadeine", "кодеин"),
        alcohol = MedAlcohol.AVOID,
    ),
    painkiller(
        "co-codamol",
        MedGroup.OPIOID,
        MedGroup.PARACETAMOL,
        keys = listOf("co codamol", "cocodamol", "kapake", "zapain", "tylex"),
        alcohol = MedAlcohol.AVOID,
    ),
    painkiller(
        "tramadol",
        MedGroup.OPIOID,
        keys = listOf("tramadol", "zydol", "tramal", "zamadol", "трамадол", "трамал"),
        alcohol = MedAlcohol.SLEEPY,
    ),
    painkiller(
        "tramadol+paracetamol",
        MedGroup.OPIOID,
        MedGroup.PARACETAMOL,
        keys = listOf("tramacet", "залдиар"),
        alcohol = MedAlcohol.SLEEPY,
    ),
    painkiller(
        "sumatriptan",
        MedGroup.TRIPTAN,
        keys = listOf("sumatriptan", "imigran", "суматриптан", "имигран", "амигренин", "сумамигрен"),
    ),
    // Brands whose make-up differs from one country to another, some of them with codeine: a
    // painkiller, and the "now" signs are always asked after too much of one.
    painkiller("", keys = listOf("пенталгин")),
    painkiller("", keys = listOf("солпадеин")),
    painkiller("", keys = listOf("седалгин")),
    painkiller("", keys = listOf("каффетин")),
    antidepressant(
        "sertraline",
        MedGroup.SSRI,
        listOf("sertraline", "lustral", "zoloft", "сертралин", "золофт", "асентра", "стимулотон", "серлифт", "алевал"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "escitalopram",
        MedGroup.SSRI,
        listOf("escitalopram", "cipralex", "lexapro", "эсциталопрам", "ципралекс", "селектра", "ленуксин", "элицея"),
        MedAlcohol.AVOID,
        SjwRule.LISTED,
    ),
    antidepressant(
        "citalopram",
        MedGroup.SSRI,
        listOf("citalopram", "cipramil", "celexa", "циталопрам", "ципрамил"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "fluoxetine",
        MedGroup.SSRI,
        listOf("fluoxetine", "prozac", "флуоксетин", "прозак", "профлузак"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "paroxetine",
        MedGroup.SSRI,
        listOf("paroxetine", "seroxat", "paxil", "пароксетин", "паксил", "рексетин", "адепресс"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "fluvoxamine",
        MedGroup.SSRI,
        listOf("fluvoxamine", "faverin", "luvox", "флувоксамин", "феварин"),
        MedAlcohol.NONE,
        SjwRule.LISTED,
    ),
    antidepressant(
        "venlafaxine",
        MedGroup.SNRI,
        listOf("venlafaxine", "efexor", "effexor", "венлафаксин", "велаксин", "велафакс", "венлаксор"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "duloxetine",
        MedGroup.SNRI,
        listOf("duloxetine", "cymbalta", "yentreve", "дулоксетин", "симбалт"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
        dualUse = true,
    ),
    antidepressant(
        "mirtazapine",
        MedGroup.NASSA,
        listOf("mirtazapine", "zispin", "remeron", "миртазапин", "ремерон", "каликста", "мирзатен"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "amitriptyline",
        MedGroup.TCA,
        listOf("amitriptyline", "амитриптилин"),
        MedAlcohol.SLEEPY,
        SjwRule.DO_NOT_USE,
        dualUse = true,
    ),
    antidepressant(
        "nortriptyline",
        MedGroup.TCA,
        listOf("nortriptyline", "allegron", "нортриптилин"),
        MedAlcohol.SLEEPY,
        SjwRule.NONE,
        dualUse = true,
    ),
    antidepressant(
        "moclobemide",
        MedGroup.MAOI,
        listOf("moclobemide", "manerix", "моклобемид", "аурорикс"),
        MedAlcohol.NONE,
        SjwRule.NONE,
    ),
    antidepressant(
        "bupropion",
        MedGroup.NDRI,
        listOf("bupropion", "wellbutrin", "zyban", "бупропион", "велбутрин", "зибан"),
        MedAlcohol.NONE,
        SjwRule.LISTED,
    ),
    antidepressant(
        "trazodone",
        MedGroup.SARI,
        listOf("trazodone", "molipaxin", "trittico", "desyrel", "тразодон", "триттико"),
        MedAlcohol.AVOID,
        SjwRule.DO_NOT_USE,
    ),
    antidepressant(
        "agomelatine",
        MedGroup.MELATONERGIC,
        listOf("agomelatine", "valdoxan", "агомелатин", "вальдоксан"),
        MedAlcohol.NONE,
        SjwRule.LISTED,
    ),
    antidepressant(
        "vortioxetine",
        MedGroup.MULTIMODAL,
        listOf("vortioxetine", "brintellix", "trintellix", "вортиоксетин", "бринтелликс"),
        MedAlcohol.NONE,
        SjwRule.LISTED,
    ),
    antidepressant(
        "st-johns-wort",
        MedGroup.ST_JOHNS_WORT,
        listOf(
            "st john s wort",
            "st johns wort",
            "john s wort",
            "johns wort",
            "hypericum",
            "зверобо",
            "деприм",
            "негрустин",
        ),
        MedAlcohol.NONE,
        SjwRule.NONE,
    ),
)

/**
 * The inns whose NHS pages put taking too much at an urgent medical helpline or a pharmacist
 * and show no card to call emergency services: after "more than it says" the "now" signs are not
 * asked for them. Every other medicine — one the list does not know, a brand whose make-up varies,
 * an entry whose page was not checked — has them asked.
 */
internal val CHECKED_OVERDOSE_111: Set<String> = setOf(
    "paracetamol",
    "ibuprofen",
    "ibuprofen+paracetamol",
    "naproxen",
    "aspirin",
    "aspirin+paracetamol",
    "sumatriptan",
    "sertraline",
    "citalopram",
    "escitalopram",
    "fluoxetine",
    "paroxetine",
    "venlafaxine",
    "duloxetine",
    "mirtazapine",
    "trazodone",
)

/** A name as the folded keys read it: composed, lower case, «е» for «ё», marks gone, split into words. */
private fun foldedName(text: String): MessageWords = MessageWords(normalisedWords(foldForSearch(text)))

/** A key folded as [foldedName] folds a name, its whole-word space kept. */
private fun foldedKey(key: String): String = foldForSearch(key) + if (key.endsWith(" ")) " " else ""

/** Every key of the list, over a message's words. */
internal val MEDICINE_NAMES: KeyTable<KnownMedicine> by lazy {
    KeyTable(KNOWN_MEDICINES.flatMap { medicine -> medicine.keys.map { symptomWordsKey(it) to medicine } })
}

/** The same keys folded, for a name a person typed or gave a course: «наиз» finds «Найз». */
private val FOLDED_NAMES: KeyTable<KnownMedicine> by lazy {
    KeyTable(KNOWN_MEDICINES.flatMap { medicine -> medicine.keys.map { symptomWordsKey(foldedKey(it)) to medicine } })
}

/**
 * The medicines of the list [text] names, each once, with the words it was named by, in the order of
 * the text: (as written, entry).
 */
internal fun medicinesIn(text: String): List<Pair<String, KnownMedicine>> =
    medicineHits(MessageWords(normalisedWords(text)))

/** [medicinesIn] over words already split. */
internal fun medicineHits(words: MessageWords): List<Pair<String, KnownMedicine>> = MEDICINE_NAMES.longest(words)
    .map { hit -> words.tokens.subList(hit.from, hit.to + 1).joinToString(" ") to hit.value }
    .distinctBy { it.second.id }

/**
 * The keys whose own words are not the name to show — a stem, one case of a name, a name whose hyphen
 * or apostrophe the keys leave out, a brand of two words — and the name each stands for, in the key's
 * script. Every other key is shown as its own words with a capital ([baseName]).
 */
private val KEY_NAMES: Map<String, String> = mapOf(
    "ацетилсалицил" to "Ацетилсалициловая кислота",
    "зверобо" to "Зверобой",
    "симбалт" to "Симбалта",
    "некста" to "Некст",
    "нексту" to "Некст",
    "некстом" to "Некст",
    "нурофен плюс" to "Нурофен Плюс",
    "nurofen plus" to "Nurofen Plus",
    "feminax ultra" to "Feminax Ultra",
    "co codamol" to "Co-codamol",
    "cocodamol" to "Co-codamol",
    "st john s wort" to "St John's wort",
    "st johns wort" to "St John's wort",
    "john s wort" to "St John's wort",
    "johns wort" to "St John's wort",
)

/**
 * Every key of the list with the name it is shown by, in [MEDICINE_NAMES]'s order: the words a name was
 * read by find here the very key that found them there.
 */
private val BASE_NAMES: KeyTable<String> by lazy {
    KeyTable(
        KNOWN_MEDICINES.flatMap { medicine ->
            medicine.keys.map { key -> symptomWordsKey(key) to (KEY_NAMES[key.trim()] ?: displayName(key.trim())) }
        },
    )
}

/**
 * The base form of the name of the list [written] is — the words a message named a medicine by, as
 * [medicinesIn] gives them: the key they are, so the brand or the generic the person wrote and in its
 * script, capitalised as a name — «парацетамола» is «Парацетамол», «нурофена» «Нурофен», "nurofen"
 * "Nurofen". Null unless the words are one name of the list and nothing more: "aspirin cardio", a
 * course of the person's, is not "Aspirin".
 */
internal fun baseName(written: String): String? {
    val words = MessageWords(normalisedWords(written))
    val hit = BASE_NAMES.longest(words).singleOrNull() ?: return null
    return hit.value.takeIf { hit.from == 0 && hit.to == words.size - 1 }
}

/**
 * The first medicine of the list [text] names, as the person typed it — "Co-Codamol", where the keys
 * read "co codamol" — or, where lower case splits its words otherwise (an «İ» is two characters
 * then), as the keys read it; null when it names none.
 */
internal fun firstMedicineAsTyped(text: String): String? {
    val words = MessageWords(normalisedWords(text))
    val hit = MEDICINE_NAMES.longest(words).firstOrNull() ?: return null
    val typed = Normalizer.normalize(text, Normalizer.Form.NFC)
    val spans = wordSpans(typed)
    return if (spans.size == words.size) {
        typed.substring(spans[hit.from].first, spans[hit.to].last + 1)
    } else {
        words.tokens.subList(hit.from, hit.to + 1).joinToString(" ")
    }
}

/**
 * Where each word of [text] stands: the runs of letters and digits, and of the marks that combine with
 * them ([wordChars]), that [normalisedWords] keeps as its words.
 */
private fun wordSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    val word = wordChars(text)
    var start = -1
    for (at in 0..text.length) {
        val letter = at < text.length && word[at]
        if (letter && start < 0) start = at
        if (!letter && start >= 0) {
            spans += start until at
            start = -1
        }
    }
    return spans
}

/** The entry of the list a name — a course's, or one the person typed — is, by its first key; null for none. */
internal fun knownMedicineOf(name: String): KnownMedicine? =
    FOLDED_NAMES.longest(foldedName(name)).firstOrNull()?.value

/** Whether two names are the same medicine: the same entry of the list, or the same name folded. */
internal fun sameMedicine(a: String, b: String): Boolean {
    val one = foldedName(a).tokens
    val other = foldedName(b).tokens
    if (one.isEmpty() || other.isEmpty()) return false
    val known = knownMedicineOf(a)
    return one == other || (known != null && known.id == knownMedicineOf(b)?.id)
}

/** What a word for a class of medicine names ("painkiller", «антидепрессант»); only the chips' order follows it. */
internal val MEDICINE_CLASS_WORDS: KeyTable<MedicineClass> = KeyTable(
    listOf(
        "painkiller" to MedicineClass.PAINKILLER,
        "pain killer" to MedicineClass.PAINKILLER,
        "pain relief" to MedicineClass.PAINKILLER,
        "analgesic" to MedicineClass.PAINKILLER,
        "обезбол" to MedicineClass.PAINKILLER,
        "болеутоля" to MedicineClass.PAINKILLER,
        "анальгетик" to MedicineClass.PAINKILLER,
        "antidepressant" to MedicineClass.ANTIDEPRESSANT,
        "антидепрессант" to MedicineClass.ANTIDEPRESSANT,
    ).map { (key, value) -> symptomWordsKey(key) to value },
)

/** Words for a medicine of some kind: a pill, a dose, «лекарство», a sleeping pill or the drops of «корвалол». */
internal val MEDICINE_CUES: KeyTable<Unit> = KeyTable(
    listOf(
        "pill ", "pills ", "tablet", "capsule", "medicine", "medication", "meds ", "dose", "vitamin", "таблет",
        "лекарств", "капсул", "препарат", "пилюл", "доза ", "дозы ", "дозу ", "дозой ", "дозе ", "доз ", "дозировк",
        "витамин", "снотворн", "корвалол", "валокордин",
    ).map { symptomWordsKey(it) to Unit },
)

/** The kinds of word around a medicine the chat reads; [EXCLUDED] is "can I take…?", which takes nothing. */
internal enum class MedVerb { TAKEN, PRESCRIBED, RECIPE, REGULAR, MISSED, STOPPED, EXCLUDED }

/**
 * The words of taking, being prescribed, taking regularly, missing and stopping. «рецепт» counts only
 * beside a medicine's word (it is a recipe too), and "can I take…?" takes no medicine.
 */
internal val MEDICINE_VERBS: KeyTable<MedVerb> = KeyTable(
    listOf(
        MedVerb.TAKEN to listOf(
            "took", "taken", "have taken", "swallowed", "popped", "had a ", "had an ", "выпил", "выпила", "выпили",
            "принял", "приняла", "приняли", "закинул", "проглотил",
        ),
        MedVerb.PRESCRIBED to listOf(
            "prescribed", "prescription", "put me on", "started me on", "new medicine", "new tablets", "doctor gave me",
            "назначил", "назначила", "назначили", "назначение", "выписал", "выписала", "выписали", "прописал",
            "прописала", "прописали", "новое лекарство", "новые таблетки",
        ),
        MedVerb.RECIPE to listOf("рецепт"),
        MedVerb.REGULAR to listOf(
            "i am on ",
            "i m on ",
            "i take ",
            "i have been taking",
            "я принимаю",
            "принимаю",
            "пью ",
            "я пью ",
        ),
        MedVerb.MISSED to listOf(
            "forgot", "forget", "missed", "didn t take", "did not take", "skipped", "забыл", "забыла", "пропустил",
            "пропустила", "не выпил", "не выпила", "не принял", "не приняла", "пропуск",
        ),
        MedVerb.STOPPED to listOf(
            "stopped", "stop taking", "came off", "coming off", "quit ", "quitting", "бросил", "бросила", "перестал",
            "перестала", "прекратил", "прекратила", "отменил", "отменила", "отменили", "больше не пью",
            "больше не принимаю",
        ),
        MedVerb.EXCLUDED to listOf(
            "can i take", "should i take", "could i take", "may i take", "what can i take", "do i take",
            "how do i take", "how much should i take", "how many should i take",
        ),
    ).flatMap { (verb, keys) -> keys.map { symptomWordsKey(it) to verb } },
)

/**
 * Taking too much: "too many", «всю пачку», "more than the box says" — about a medicine when a medicine,
 * a word for one or a word of taking stands beside them. A bare "more than" is left out: "more than an hour".
 */
private val EXCESS_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "too many", "too much", "more than the dose", "more than it says", "more than it said",
        "more than i should", "more than i was told", "extra dose", "double dose", "whole pack", "whole packet",
        "whole box", "whole bottle", "whole strip", "all my pills", "all my tablets", "all the pills",
        "all the tablets", "all of my pills", "all of my tablets", "handful", "слишком много", "много таблеток",
        "больше нормы", "больше положенного", "больше чем положено", "больше чем надо", "больше чем написано",
        "всю пачку", "всю упаковку", "всю банку", "целую пачку", "целую упаковку", "целую банку", "целый блистер",
        "весь блистер", "все таблетки", "горсть*", "вместо одн*", "превысил*", "all my meds", "all of my meds",
        "all my medication", "all my medicine", "все свои таблетки", "все мои таблетки", "все свои лекарства",
        "все мои лекарства",
    ),
)

/**
 * Words of too much only beside a medicine: «лишняя», «двойная», "an extra" — a double espresso and a
 * Panadol Extra are no dose.
 */
private val EXCESS_NEAR_MEDICINE: KeyTable<Unit> = keyTable(
    Unit to listOf("лишн*", "двойн*", "an extra", "one extra", "extra tablet*", "extra pill*", "extra capsule*"),
)

/** A packet of a medicine taken: «выпила пачку таблеток», "took a box of paracetamol" — never one bought. */
private val PACK_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "a pack of",
        "a packet of",
        "a box of",
        "a bottle of",
        "a strip of",
        "пачку",
        "упаковку",
        "блистер",
    ),
)

/**
 * Words of having had a medicine besides those of taking it, which a count before a medicine's name is
 * read beside: "I've had 16 nurofen", «съела 5 нурофена» — never a dose taken by themselves.
 */
private val COUNT_VERBS: KeyTable<Unit> = keyTable(Unit to listOf("had", "ate", "eaten", "used", "съел*", "съесть"))

/**
 * Words that are about too much of a medicine by themselves: "overdose", «передозировка», «наглоталась»,
 * "more than the box says", «больше, чем назначено». The words of suicide hold the first three too
 * ([CRISIS_KEYS]): the question whether the person is safe comes first.
 */
private val OVERDOSE_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "overdos*", "передоз*", "наглот*", "more than prescribed", "more than the box says",
        "more than the packet says", "more than the pack says", "more than the leaflet says",
        "more than the label says", "больше чем назначено", "больше чем в инструкции",
        // The floor's: a pile of pills with no number, "I took a bunch of pills".
        "bunch of pill*", "bunch of tablet*", "bunch of my pill*", "bunch of my tablet*", "bunch of med*",
        "bunch of sleeping pill*", "bunch of sleeping tablet*", "bunch of painkiller*", "whole lot of pill*",
        "whole lot of tablet*", "whole lot of med*", "whole lot of sleeping pill*", "whole lot of sleeping tablet*",
        "whole lot of painkiller*", "handful of sleeping pill*",
    ),
)

/**
 * A packet, a bottle or a handful — «пачку», «полфлакона», «горсть» — of a sleeping pill or of the
 * drops of «корвалол»: too much of one whatever the verb, «купила пачку снотворного» too: these are
 * what is taken all at once.
 */
private val HEAP_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "пачк*", "полпачк*", "флакон*", "полфлакон*", "пузырек", "пузырьк*", "горст*", "упаковк*", "полупаковк*",
        "блистер*", "бутылк*", "бутылочк*", "полбутылк*",
    ),
)

/** Sleeping pills and the drops of «корвалол» and «валокордин», a heap of which is too much ([HEAP_WORDS]). */
private val SEDATIVE_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("снотворн*", "корвалол*", "валокордин*", "феназепам*", "sleeping pill*", "sleeping tablet*"),
)

/** "Twice", which counts beside a taking verb and never before "a day", how a prescription is told. */
private val TWICE_WORDS: KeyTable<Unit> = keyTable(Unit to listOf("twice", "дважды", "два раза", "две дозы"))

private val A_DAY_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("a day", "a week", "per day", "daily", "в день", "в сутки", "в неделю", "ежедневно"),
)

/**
 * A count of three or more, as a word: every number from three to twenty, the tens, a hundred, a dozen
 * and "several" — "took twenty paracetamol" is no less than "took 20". A number of two words is read
 * by its first: "twenty five tablets"; «десяток» is ten of them.
 */
private val COUNT_WORDS = setOf(
    "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
    "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty",
    "seventy", "eighty", "ninety", "hundred", "dozen", "dozens", "several", "три", "четыре", "пять", "шесть", "семь",
    "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
    "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать", "двадцать", "тридцать", "сорок", "пятьдесят",
    "шестьдесят", "семьдесят", "восемьдесят", "девяносто", "сто", "дюжина", "дюжину", "дюжины", "несколько",
    "десяток", "десятка", "десятков",
)

// Limitation: the instrumental («двадцатью таблетками») stays unread, and a genitive count reaches no
// further than [genitiveCounts] lets it.

/**
 * The Russian counts in the genitive, the case «около», «больше» and «не меньше» put them in: «выпила
 * около двадцати таблеток». The hour is told in it as well — «после шести выпила таблетку», «около пяти»
 * — so one of these counts only what is in the genitive after it ([genitiveCounts], [clockCount]).
 */
private val GENITIVE_COUNTS = setOf(
    "трех", "четырех", "пяти", "шести", "семи", "восьми", "девяти", "десяти", "одиннадцати", "двенадцати",
    "тринадцати", "четырнадцати", "пятнадцати", "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати",
    "двадцати", "тридцати", "сорока", "пятидесяти", "ста", "нескольких",
)

/** Tablets, capsules, pills and doses in the genitive plural: what a genitive count counts, and one said after them. */
private val GENITIVE_UNITS = setOf("таблеток", "капсул", "пилюль", "доз")

/** How a Russian name of a medicine ends in the genitive: «парацетамола», «нурофена», «но-шпы». */
private const val GENITIVE_ENDS = "аяыи"

/**
 * Whether the genitive count at [at] counts what stands after it, and is no hour, though it stands not
 * right before the tablets: tablets in the genitive plural one word on — «около двадцати белых таблеток»,
 * where «после шести выпила таблетку» is one tablet after six — or a medicine's name of [names] right
 * after it, in the genitive too: «около двадцати парацетамола», never «около пяти парацетамол».
 */
private fun genitiveCounts(words: MessageWords, at: Int, names: Set<Int>): Boolean =
    words.tokens.getOrNull(at + 2) in GENITIVE_UNITS ||
        (at + 1 in names && words.tokens[at + 1].last() in GENITIVE_ENDS)

/** What makes the word after a count said after its tablets a stretch of time: «таблеток двадцать минут назад». */
private val TIME_STEMS = listOf("минут", "час", "секунд", "дн", "сут", "недел", "месяц", "год", "лет", "раз")

/**
 * A count said after what it counts, which is how Russian says "about": «выпила таблеток двадцать». Only
 * after the tablets' genitive plural ([GENITIVE_UNITS]), and never a strength, a stretch of time or a
 * count a day: «таблеток 500 мг», «таблеток двадцать минут назад», «таблеток пять в день».
 */
private fun countedAfterUnit(words: MessageWords): Boolean {
    val days = A_DAY_WORDS.longest(words).map { it.from }.toSet()
    return words.tokens.indices.any { at ->
        val next = words.tokens.getOrNull(at + 1)
        words.tokens.getOrNull(at - 1) in GENITIVE_UNITS && isCount(words.tokens[at]) && next !in STRENGTH_UNITS &&
            at + 1 !in days && TIME_STEMS.none { next?.startsWith(it) == true }
    }
}

/** What a count of too many is counted in: tablets, capsules, pills, doses. */
private val UNIT_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("tablet*", "capsule*", "pill*", "dose*", "таблет*", "капсул*", "пилюл*", "доз*"),
)

/** The words a count reaches past to what it counts: "6 of my pills", «3 из моих таблеток», "3 x 500 mg". */
private val COUNT_FILLERS = setOf(
    "of", "my", "the", "these", "those", "his", "her", "your", "x", "х", "по", "из", "моих", "мои", "своих", "свои",
    "этих", "штук", "штуки", "шт",
)

/** The units of a strength, which make the figure before them a strength and no count: "500 mg", «50 мг». */
private val STRENGTH_UNITS = setOf("mg", "мг", "g", "г", "mcg", "мкг", "ml", "мл", "iu", "ме", "units", "unit", "ед")

/** A strength written as one word: "500mg", «50мг». */
private val STRENGTH_WORD = Regex("""[0-9]+(?:mg|мг|g|г|mcg|мкг|ml|мл)""")

/** How many words after a count it may reach to what it counts. */
private const val COUNT_REACH = 4

/** Words of drink, for the alcohol note. «Вина» is left out: it is guilt as often as wine. */
private val ALCOHOL_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "alcohol*", "beer*", "wine", "wines", "vodka*", "whisk*", "drunk*", "hangover*", "pint*", "cocktail*",
        "алкогол*", "пиво", "пива", "пиву", "пивом", "вино", "водк*", "коньяк*", "виски", "похмел*", "спиртн*",
        "выпивк*", "шампанск*",
    ),
)

/** A spray, a cream or drops: their amount is "one dose", never a number of tablets. */
private val TOPICAL_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf(
        "sprayed", "spray*", "applied", "drops", "cream*", "ointment*", "gel", "намазал*", "пшикнул*", "закапал*",
        "спрей*", "капли", "мазь*", "мази", "гель", "крем*",
    ),
)

/**
 * What one message says about medicines, read once: which are named, and the words around them.
 * [about]: the words are said inside a thread about a medicine — a dose or a course — so "too many"
 * there is too much of that medicine with no name beside it. [names] are the medicines the thread
 * already names (its course, the name typed), read as the list's names are where a count stands
 * before one. [written] is the message as the person typed it, where the caller has it: the colon of
 * a time and the comma of an amount are gone from [words], and an everyday drink is told with them.
 */
internal class MedicineWords(
    val words: MessageWords,
    private val courses: List<Medication>,
    private val about: Boolean = false,
    private val names: List<String> = emptyList(),
    private val written: String? = null,
) {
    constructor(
        text: String,
        courses: List<Medication>,
        about: Boolean = false,
        names: List<String> = emptyList(),
    ) : this(MessageWords(normalisedWords(text)), courses, about, names, text)

    /** The medicines of the list it names, in its order. */
    val named: List<Pair<String, KnownMedicine>> = medicineHits(words)

    /** The person's own courses it names. */
    val own: List<Medication> = ownCoursesIn(words, courses)

    /** The classes of medicine its words name. */
    val classes: Set<MedicineClass> = MEDICINE_CLASS_WORDS.longest(words).mapTo(HashSet()) { it.value }

    private val cues = MEDICINE_CUES.any(words)
    private val verbs: List<KeyHit<MedVerb>> = MEDICINE_VERBS.longest(words)

    /** Where a medicine is named: one of the list, one of the person's courses, one the thread names. */
    private val nameStarts: Set<Int> by lazy {
        MEDICINE_NAMES.longest(words).mapTo(HashSet()) { it.from } +
            namedAt(words, courses.map { it.name } + names)
    }

    /** A medicine, a class of one, a word for one, or one of the person's own. */
    val medicine: Boolean = named.isNotEmpty() || own.isNotEmpty() || classes.isNotEmpty() || cues

    private fun says(verb: MedVerb) = verbs.any { it.value == verb }

    val taken: Boolean get() = says(MedVerb.TAKEN) && medicine

    val prescribed: Boolean get() =
        medicine && (says(MedVerb.PRESCRIBED) || (says(MedVerb.RECIPE) && (cues || named.isNotEmpty())))

    val regular: Boolean get() = says(MedVerb.REGULAR) && medicine

    val missed: Boolean get() = says(MedVerb.MISSED) && medicine

    val stopped: Boolean get() = says(MedVerb.STOPPED) && medicine

    /**
     * Too much of a medicine: "overdose" or «наглоталась» by itself; a word of too much beside a
     * medicine's word or a word of taking ("I took too many", "too many paracetamol"); «двойная» or
     * "an extra" beside a medicine's; a packet of one taken («выпила пачку таблеток»); "twice" beside a
     * taking verb and not before "a day"; a count of three or more of a medicine, tablets or pills,
     * taken at once or not ("took 20 paracetamol", "I've had 16 nurofen", "20 paracetamol", "6 of my
     * pills", «выпил 4 таблетки»), but not a strength ("50 mg", "I take 50 sertraline", "on 50
     * sertraline") nor a count a day ("3 tablets a day"); or more whole tablets of a tricyclic or of
     * codeine than the person's course of it says ([pastPrescribedTablets]: «выпила две таблетки
     * амитриптилина» on a course of «1 таблетка»).
     */
    val overdose: Boolean get() {
        if (everydayDrink()) return false
        if (saysOverdose()) return true
        val taking = verbs.filter { it.value == MedVerb.TAKEN }
        val medicinal = medicine || about || nameStarts.isNotEmpty()
        if (EXCESS_WORDS.any(words) && (medicinal || taking.isNotEmpty())) return true
        if (EXCESS_NEAR_MEDICINE.any(words) && medicinal) return true
        if (PACK_WORDS.any(words) && medicinal && taking.isNotEmpty()) return true
        if (pastPrescribedTablets(taking.isNotEmpty())) return true
        val days = A_DAY_WORDS.longest(words)
        val twice = TWICE_WORDS.longest(words).any { hit ->
            days.none { it.from == hit.to + 1 } &&
                taking.any { it.to >= hit.from - TWICE_REACH && it.from <= hit.to + TWICE_REACH }
        }
        return twice || ((medicinal || taking.isNotEmpty()) && counted(taking.isNotEmpty()))
    }

    /**
     * A whole statement of an everyday drink or a cigarette ([ordinaryConsumptionIn]), which "the whole
     * bottle" of is no medicine: read in the words, and in the message as it was typed too — «выпила всю
     * банку колы в 20:00» and "1,5 л" keep their colon and comma only there, and would be asked about.
     */
    private fun everydayDrink(): Boolean =
        ordinaryConsumptionIn(words.words) || written?.let(::ordinaryConsumptionIn) == true

    /**
     * Words that are about too much of a medicine by themselves: [OVERDOSE_WORDS], a heap of a sleeping
     * pill ([HEAP_WORDS]), or those of another of the app's languages ([otherLanguageOverdoseIn]).
     */
    private fun saysOverdose(): Boolean = OVERDOSE_WORDS.any(words) ||
        (HEAP_WORDS.any(words) && SEDATIVE_WORDS.any(words)) || otherLanguageOverdoseIn(words.words)

    /**
     * A count of three or more ([countedTooMany]), a medicine's name counted beside a word of taking
     * ([taking]) or of having had — or with no word at all of how a medicine is taken, "20 paracetamol",
     * where a word before the figure still makes it a strength ([STRENGTH_LEADS]): "on 50 sertraline",
     * "taking 50 sertraline", "up to 100 sertraline".
     */
    private fun counted(taking: Boolean): Boolean {
        val counting = taking || COUNT_VERBS.any(words)
        val bare = !counting && verbs.isEmpty()
        val ahead = countedTooMany(
            words,
            nameStarts.takeIf { counting || bare }.orEmpty(),
            strengthLeads = if (bare) STRENGTH_LEADS else emptySet(),
        )
        // A count said after its tablets only beside a word of taking: «в упаковке таблеток 20 штук» is a packet.
        return ahead || (counting && countedAfterUnit(words))
    }

    /**
     * More whole tablets taken than the person's course of the medicine says, for one whose NHS page
     * puts any dose past the prescribed one at 111 — a tricyclic ("more than your prescribed dose of
     * amitriptyline, even if you do not have any symptoms") or codeine: «выпила две таблетки
     * амитриптилина» on a course of «1 таблетка». Read only where the words say they were taken or had
     * ([taking]) — «теперь две таблетки» is a course's new dose — and never against a course whose dose
     * names no tablets ("10 mg"). The course is the one the words name, or the thread's medicine's in a
     * thread about one ([names]). Co-codamol's page calls one or two extra tablets unlikely to harm, and
     * tramadol's asks for two extra doses or more, which a count of three already brings.
     */
    private fun pastPrescribedTablets(taking: Boolean): Boolean {
        if (!taking && !COUNT_VERBS.any(words)) return false
        val told = tabletsTold(words) ?: return false
        val meant = courses.filter { course ->
            val known = knownMedicineOf(course.name) ?: return@filter false
            val strict = MedGroup.TCA in known.groups || known.inn == "codeine"
            strict && (
                course in own || named.any { (_, entry) -> entry.id == known.id } ||
                    (about && names.any { sameMedicine(it, course.name) })
                )
        }
        return meant.any { course ->
            tabletsTold(MessageWords(normalisedWords(course.dose)))?.let { told > it } == true
        }
    }
}

/**
 * Before a figure, words that make it a strength when nothing says how the medicine is taken: "on 50
 * sertraline", "taking 50 sertraline", "started 50 sertraline", "upped to 100 sertraline".
 */
private val STRENGTH_LEADS = setOf("on", "to", "at", "take", "taking", "started", "starting", "на", "до")

/** Whole tablets, capsules and pills, as a dose and a course's dose count them. */
private val TABLET_WORDS: KeyTable<Unit> = keyTable(
    Unit to listOf("tablet*", "capsule*", "pill*", "tab", "tabs", "таблет*", "капсул*", "пилюл*"),
)

/** Counts written as words, for whole tablets: "two tablets", «две таблетки». */
private val NUMBER_WORDS: Map<String, Int> = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
    "nine" to 9, "ten" to 10, "один" to 1, "одна" to 1, "одну" to 1, "одной" to 1, "два" to 2, "две" to 2,
    "пару" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
    "десять" to 10,
)

/**
 * The most whole tablets, capsules or pills [words] count — "2 tablets", «две таблетки», "1 tab", "2
 * codeine tablets", "2 of my tablets", "2 x 25 mg tablets" — not a count of them a day ("2 tablets a
 * day" is how a course is told), or null when none is counted.
 */
private fun tabletsTold(words: MessageWords): Int? {
    val days = A_DAY_WORDS.longest(words).map { it.from }.toSet()
    return TABLET_WORDS.longest(words).filter { it.to + 1 !in days }.mapNotNull { countBefore(words, it.from) }
        .maxOrNull()
}

/**
 * The count before word [at], within [COUNT_REACH] words: past fillers ("of my", "x"), strengths ("25
 * mg") and at most one other word ("codeine", «белые»); null when none stands there.
 */
private fun countBefore(words: MessageWords, at: Int): Int? {
    var others = 0
    var count: Int? = null
    var back = at - 1
    while (count == null && others <= 1 && back >= maxOf(0, at - COUNT_REACH)) {
        val word = words.tokens[back]
        val figure = word.toIntOrNull() ?: NUMBER_WORDS[word]
        val strength = STRENGTH_WORD.matches(word) || word in STRENGTH_UNITS ||
            (figure != null && words.tokens.getOrNull(back + 1) in STRENGTH_UNITS)
        when {
            strength -> Unit
            figure != null -> count = figure
            word !in COUNT_FILLERS -> others++
        }
        back--
    }
    return count
}

/** How far, in words, "twice" may stand from its taking verb. */
private const val TWICE_REACH = 3

/** The fewest tablets a count names for the chat to ask whether it was more than the packet says. */
private const val TOO_MANY_COUNT = 3

/** Whether [word] is a count of three or more: "4", "twenty", «пять», «двадцати», "several" ([COUNT_WORDS]). */
private fun isCount(word: String): Boolean =
    (word.toIntOrNull() ?: 0) >= TOO_MANY_COUNT || word in COUNT_WORDS || word in GENITIVE_COUNTS

/**
 * A count of three or more of something taken: right before tablets, capsules or pills, or with one
 * word between («4 белые таблетки»); or reaching past "of my", "x" and a strength to a unit or to a
 * medicine's name in [names] — "took 20 paracetamol", "6 of my pills", "3 x 500 mg paracetamol" (the
 * caller gives the names only where they count: "I take 50 sertraline" is a strength). A figure after
 * one of [strengthLeads] counts tablets but no name: "on 50 sertraline" is a strength too. A figure
 * with a strength's unit after it is a strength, the second figure of "8:30" is a time, and a count
 * "a day" is how a course is told.
 */
private fun countedTooMany(words: MessageWords, names: Set<Int>, strengthLeads: Set<String> = emptySet()): Boolean {
    val units = UNIT_WORDS.longest(words).map { it.from }.toSet()
    val days = A_DAY_WORDS.longest(words).map { it.from }.toSet()
    return words.tokens.indices.any { at ->
        val before = words.tokens.getOrNull(at - 1)
        val targets = if (before in strengthLeads) units else units + names
        val time = clockCount(words, at, units, targets - units)
        isCount(words.tokens[at]) && !time && words.tokens.getOrNull(at + 1) !in STRENGTH_UNITS &&
            countedAt(words, at + 1, targets)?.let { it + 1 !in days } == true
    }
}

private val CLOCK_COUNT_LEADS = setOf("at", "в", "во")
private val CLOCK_COUNT_PERIODS = setOf("am", "pm", "утра", "утром", "вечера", "вечером", "дня", "днем")

/**
 * A clock hour cannot count pills; an explicit tablet unit still counts even beside a clock cue. A
 * Russian count in the genitive is such a cue by itself ([GENITIVE_COUNTS]), and counts what is in the
 * genitive after it all the same ([genitiveCounts], [names] the medicines' names a count may count).
 */
private fun clockCount(words: MessageWords, at: Int, units: Set<Int>, names: Set<Int>): Boolean {
    val before = words.tokens.getOrNull(at - 1)
    val after = words.tokens.getOrNull(at + 1)
    val genitive = words.tokens[at] in GENITIVE_COUNTS
    val clock = before in CLOCK_COUNT_LEADS || after in CLOCK_COUNT_PERIODS || genitive
    val counts = at + 1 in units || (genitive && genitiveCounts(words, at, names))
    return before?.all { it.isDigit() } == true || (clock && !counts)
}

/**
 * Where, from word [from] on, what a count counts stands — one of [targets] within [COUNT_REACH]
 * words, past fillers and strengths and at most one other word — or null. Another figure that is no
 * strength ends the reach: "at 8 30" is a time.
 */
private fun countedAt(words: MessageWords, from: Int, targets: Set<Int>): Int? {
    var at = from
    var others = 0
    while (at < words.size && at - from < COUNT_REACH && at !in targets) {
        val past = pastWords(words, at)
        if (past == OTHER_WORD) others++
        if (past == FIGURE || others > 1) break
        at += maxOf(past, 1)
    }
    return at.takeIf { it in targets && it - from < COUNT_REACH }
}

/** A word a count may reach past only once: neither a filler nor a strength. */
private const val OTHER_WORD = -1

/** A figure that is no strength: it ends a count's reach. */
private const val FIGURE = 0

/** How many words a count reaches past at word [at] — a filler, a strength ("500 mg") — or [FIGURE], [OTHER_WORD]. */
private fun pastWords(words: MessageWords, at: Int): Int {
    val word = words.tokens[at]
    val figure = word.all { it.isDigit() }
    return when {
        word in COUNT_FILLERS || STRENGTH_WORD.matches(word) -> 1
        figure && words.tokens.getOrNull(at + 1) in STRENGTH_UNITS -> 2
        figure -> FIGURE
        else -> OTHER_WORD
    }
}

/**
 * The words where one of [names] — a course's, a medicine a thread names — begins in [words]: the whole
 * name, or its first word of four letters or more unless that is a word for any medicine ("Vitamin D").
 */
private fun namedAt(words: MessageWords, names: List<String>): Set<Int> = names.flatMapTo(HashSet()) { name ->
    val parts = MessageWords(normalisedWords(name)).tokens
    if (parts.isEmpty()) return@flatMapTo emptyList()
    val first = parts.first().takeIf { it.length >= OWN_NAME_MIN && !MEDICINE_CUES.any(MessageWords(" $it ")) }
    words.tokens.indices.filter { at ->
        words.tokens.subList(at, minOf(words.size, at + parts.size)) == parts ||
            (first != null && words.tokens[at].startsWith(first))
    }
}

/** How long a course name's first word must be to be read alone: four letters. */
private const val OWN_NAME_MIN = 4

/** The person's courses whose name [words] holds: the whole name folded, or its first word of four letters or more. */
private fun ownCoursesIn(words: MessageWords, courses: List<Medication>): List<Medication> {
    if (courses.isEmpty()) return emptyList()
    val folded = foldedName(words.words)
    return courses.filter { course ->
        val name = foldedName(course.name).tokens
        val first = name.firstOrNull()?.takeIf { it.length >= OWN_NAME_MIN }
        name.isNotEmpty() && (folded.tokens.windowed(name.size).any { it == name } || startsAWord(folded, first))
    }
}

/** Whether a word of [words] starts with [stem]. */
private fun startsAWord(words: MessageWords, stem: String?): Boolean =
    stem != null && words.tokens.any { it.startsWith(stem) }

/** Whether [text] names a medicine with a word of taking it. */
internal fun doseWordsIn(text: String, courses: List<Medication> = emptyList()): Boolean =
    MedicineWords(text, courses).taken

/** Whether [text] says a medicine was prescribed: "prescribed", «назначили», «рецепт» beside a medicine. */
internal fun courseWordsIn(text: String, courses: List<Medication> = emptyList()): Boolean =
    MedicineWords(text, courses).prescribed

/** Whether [text] says a medicine is taken regularly: "I am on", «принимаю», «пью». */
internal fun regularWordsIn(text: String, courses: List<Medication> = emptyList()): Boolean =
    MedicineWords(text, courses).regular

/** Whether [text] says a dose was missed: "forgot", «пропустила», «не выпил». */
internal fun missedWordsIn(text: String, courses: List<Medication> = emptyList()): Boolean =
    MedicineWords(text, courses).missed

/** Whether [text] says a medicine was stopped: "stopped", «бросила», «больше не принимаю». */
internal fun stopWordsIn(text: String, courses: List<Medication> = emptyList()): Boolean =
    MedicineWords(text, courses).stopped

/**
 * Whether [texts] say the medicine [name] was stopped, read for that medicine alone: a word of stopping
 * told with it in one of them ([tiedVerbs]), and no word of taking it, being on it or its being
 * prescribed told with it in any — «бросила пить сертралин», never «бросила курить, пью сертралин»;
 * in "my doctor stopped sertraline and prescribed mirtazapine", sertraline and not mirtazapine.
 */
internal fun stoppedIn(texts: List<String>, name: String, courses: List<Medication> = emptyList()): Boolean {
    val tied = texts.flatMapTo(HashSet()) { tiedVerbs(it, name, courses) }
    return MedVerb.STOPPED in tied && tied.none { it in GOING_ON }
}

/** The words of going on with a medicine: taking it, being on it, its being prescribed. */
private val GOING_ON = setOf(MedVerb.TAKEN, MedVerb.REGULAR, MedVerb.PRESCRIBED, MedVerb.RECIPE)

/** How many words may stand between a medicine's name and the word of taking or stopping it is told with. */
private const val TIE_REACH = 3

/**
 * Words that turn to something else, which no word of taking or stopping reaches past: "now", "started",
 * "after", «теперь», «перешла».
 */
private val TURN_WORDS = setOf(
    "now", "then", "instead", "after", "started", "starting", "began", "switched", "restarted", "back", "again",
    "теперь", "сейчас", "потом", "затем", "вместо", "после", "начала", "начал", "начинаю", "перешла", "перешел",
    "перевели", "снова", "опять",
)

/** Words that put two names under one word of taking or stopping: "stopped sertraline and fluoxetine". */
private val JOINING_WORDS = setOf("and", "or", "и", "или")

/**
 * The kinds of word [text] tells the medicine [name] with. In each clause that names it — a comma, a
 * full stop or a word of contrast ends one ([Message.clauses]) — the nearest word of taking, being
 * prescribed, being on, missing or stopping, before the name or after it, with at most [TIE_REACH]
 * words between and none of them another medicine's name, "and" or a word that turns ("now",
 * «теперь»); with none of its own, the word of a name it is joined to by "and" or «и» ("stopped
 * sertraline and fluoxetine"). «Бросила курить, пью сертралин» tells sertraline with «пью»; «врач
 * отменил флуоксетин, теперь сертралин» and "I stopped smoking and started mirtazapine" tell the
 * medicine after the turn with nothing.
 */
internal fun tiedVerbs(text: String, name: String, courses: List<Medication> = emptyList()): Set<MedVerb> =
    Message.of(text).clauses(emptyList()).flatMapTo(HashSet()) { clause ->
        ClauseTies(MessageWords(clause.words), name, courses).tied()
    }

/** One clause's names of medicines and words of taking or stopping, for [tiedVerbs]. */
private class ClauseTies(private val words: MessageWords, name: String, courses: List<Medication>) {
    private val verbs = MEDICINE_VERBS.longest(words)

    private val listed = MEDICINE_NAMES.longest(words)

    /** Where [name] stands: the list's entry it is, or its own words as written. */
    private val targets: List<IntRange> = run {
        val parts = MessageWords(normalisedWords(name)).tokens
        val typed = if (parts.isEmpty()) {
            emptyList()
        } else {
            words.tokens.indices.filter { at -> words.tokens.subList(at, minOf(words.size, at + parts.size)) == parts }
                .map { it until it + parts.size }
        }
        val same = listed.filter { hit ->
            sameMedicine(words.tokens.subList(hit.from, hit.to + 1).joinToString(" "), name)
        }
        (same.map { it.from..it.to } + typed).distinct()
    }

    /** Where any medicine stands: [targets], the list's, the person's courses'. */
    private val spans: List<IntRange> =
        (targets + listed.map { it.from..it.to } + namedAt(words, courses.map { it.name }).map { it..it }).distinct()

    /** The words [name] is told with here. */
    fun tied(): Set<MedVerb> = targets.mapNotNullTo(HashSet()) { tied(it, JOIN_BOTH) }

    /** The word [span] is told with: its own, else a joined name's, looking only the way [join] allows. */
    private fun tied(span: IntRange, join: Int): MedVerb? = own(span)
        ?: joined(span, back = true)?.takeIf { join <= JOIN_BOTH }?.let { tied(it, JOIN_BACK) }
        ?: joined(span, back = false)?.takeIf { join >= JOIN_BOTH }?.let { tied(it, JOIN_ON) }

    /** The nearest word of taking or stopping before [span] or after it, with clear words between. */
    private fun own(span: IntRange): MedVerb? {
        val before = verbs.lastOrNull { it.to < span.first }?.takeIf { clear(it.to + 1, span.first - 1, span) }
        val after = verbs.firstOrNull { it.from > span.last }?.takeIf { clear(span.last + 1, it.from - 1, span) }
        return when {
            before == null -> after?.value
            after == null -> before.value
            span.first - before.to <= after.from - span.last -> before.value
            else -> after.value
        }
    }

    /**
     * Whether words [from]..[to] are few enough, and none of them another medicine's name than [span],
     * "and", or a word that turns.
     */
    private fun clear(from: Int, to: Int, span: IntRange): Boolean = to - from + 1 <= TIE_REACH &&
        (from..to).none { at ->
            val word = words.tokens[at]
            word in TURN_WORDS || word in JOINING_WORDS || spans.any { it != span && at in it }
        }

    /** The name [span] is joined to by "and" or «и», before it or after it. */
    private fun joined(span: IntRange, back: Boolean): IntRange? {
        val joiner = if (back) span.first - 1 else span.last + 1
        if (words.tokens.getOrNull(joiner) !in JOINING_WORDS) return null
        return spans.firstOrNull { if (back) it.last == joiner - 1 else it.first == joiner + 1 }
    }

    private companion object {
        const val JOIN_BACK = -1
        const val JOIN_BOTH = 0
        const val JOIN_ON = 1
    }
}

/**
 * Whether [text] says too much of a medicine was taken ([MedicineWords.overdose]); [about] and [names]
 * as there, for words said in a thread about a medicine. A false alarm costs one tap.
 */
internal fun overdoseIn(
    text: String,
    courses: List<Medication> = emptyList(),
    about: Boolean = false,
    names: List<String> = emptyList(),
): Boolean = MedicineWords(text, courses, about, names).overdose

/** Whether [text] speaks of drink: alcohol, beer, «водка», «похмелье». */
internal fun alcoholWordsIn(text: String): Boolean = ALCOHOL_WORDS.any(MessageWords(normalisedWords(text)))

/** Whether [text] speaks of a spray, a cream or drops, which are dosed as "one dose", never as tablets. */
internal fun topicalWordsIn(text: String): Boolean = TOPICAL_WORDS.any(MessageWords(normalisedWords(text)))

/** Whether [text] names a painkiller: one of the list's, or a word for the class. */
internal fun painkillerWordsIn(text: String): Boolean {
    val words = MessageWords(normalisedWords(text))
    return medicineHits(words).any { it.second.painkiller } ||
        MEDICINE_CLASS_WORDS.longest(words).any { it.value == MedicineClass.PAINKILLER }
}

/**
 * A clock time, "8:30" or «20.00» — not part of a longer figure, nor one with a unit after it ("2.50 mg").
 * The set lists the dot first: Android's regex engine (ICU) reads a set that opens with a colon as a
 * POSIX property and throws when the file is first loaded — see [IcuRegexTest].
 */
private val CLOCK_PATTERN = Regex(
    """(?<![0-9.,:])([01]?[0-9]|2[0-3])[.:]([0-5][0-9])(?![0-9:]|[.,][0-9])""" +
        """(?!$SPACE?(?:mg|мг|mcg|мкг|ml|мл|g|г)(?!\p{L}))""",
)

private const val MINUTES_AN_HOUR = 60

/** The clock times [text] names — "8:30", «20.00» — as minutes of the day, each once, in order; proposals only. */
internal fun clockMinutesIn(text: String): List<Int> = CLOCK_PATTERN.findAll(text.asciiDigits().lowercase(Locale.ROOT))
    .map { it.groupValues[1].toInt() * MINUTES_AN_HOUR + it.groupValues[2].toInt() }
    .distinct()
    .toList()

/**
 * A dose as written: a figure and a unit, "50 mg", «0,5 г», «1 таблетка»; a fraction or a range with both
 * its figures, "1/2 tablet" and «1-2 таблетки», and never the second alone — «2 таблетки» offered as the
 * person's own words for «1/2 таблетки» is four times the dose. After a figure and a space, three digits
 * may be that figure's thousands («1 000 мг») and a fraction the rest of a mixed number ("1 1/2
 * tablets"), so neither is read there; any other figure is ("at 8:30 2 tablets").
 */
private val DOSE_TEXT_PATTERN = Regex(
    """(?<![\p{L}0-9.,])(?<![0-9][/–-])(?:(?<![0-9][ \xA0\x{202F}])|(?![0-9]{3}(?![0-9])|[0-9]+/))""" +
        """[0-9]+(?:[.,/–-][0-9]+)?$SPACE?""" +
        """(?:mg|мг|mcg|мкг|µg|g|г|ml|мл|iu|ме|units?|ед|tablets?|таблетк\p{L}*|capsules?|капсул\p{L}*|""" +
        """drops?|капл\p{L}*|капел\p{L}*)(?![\p{L}0-9])""",
    RegexOption.IGNORE_CASE,
)

/** The doses [text] names as written — "50 mg", «0,5 г», «1 таблетка» — each once, in order; proposals only. */
internal fun doseTextsIn(text: String): List<String> =
    // Found in ASCII digits and handed back in the person's own: «٥٠ mg» is offered as typed.
    DOSE_TEXT_PATTERN.findAll(text.asciiDigits()).map { text.substring(it.range).trim() }
        .distinctBy { it.lowercase(Locale.ROOT) }.toList()

/** Every key of the medicine words, for the rule that keys are written as the words are read. */
internal fun medicineKeyTexts(): List<String> = listOf(
    MEDICINE_NAMES.keyTexts(),
    MEDICINE_VERBS.keyTexts(),
    MEDICINE_CUES.keyTexts(),
    MEDICINE_CLASS_WORDS.keyTexts(),
    EXCESS_WORDS.keyTexts(),
    EXCESS_NEAR_MEDICINE.keyTexts(),
    PACK_WORDS.keyTexts(),
    COUNT_VERBS.keyTexts(),
    OVERDOSE_WORDS.keyTexts(),
    TWICE_WORDS.keyTexts(),
    A_DAY_WORDS.keyTexts(),
    UNIT_WORDS.keyTexts(),
    TABLET_WORDS.keyTexts(),
    ALCOHOL_WORDS.keyTexts(),
    TOPICAL_WORDS.keyTexts(),
    HEAP_WORDS.keyTexts(),
    SEDATIVE_WORDS.keyTexts(),
).flatten() + COUNT_WORDS + GENITIVE_COUNTS + NUMBER_WORDS.keys + STRENGTH_LEADS + TURN_WORDS + JOINING_WORDS
