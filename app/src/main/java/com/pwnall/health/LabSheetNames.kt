@file:Suppress("MatchingDeclarationName") // The file holds the name dictionary and its lookup functions.

package com.pwnall.health

import java.text.Normalizer
import kotlin.math.abs

/**
 * What a row's name says it is: the marker it holds on a blood sheet and on a urine one.
 *
 * Null on both sides is a name the reader knows and the app does not keep — MCH, BUN, direct
 * bilirubin, a ratio. Those are here for what they stop: "Mean Cell Hemoglobin" starts with no
 * haemoglobin of its own, and a row the dictionary did not know would let its figure drift to the
 * nearest name it did.
 */
internal class SheetName(val blood: LabMarker?, val urine: LabMarker?, val mcv: Boolean = false)

/** The dictionary's key for a name: lower case, accents folded, punctuation gone, "/" kept apart. */
internal fun nameTokens(text: String): List<String> =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .map { FOLDED[it] ?: it.toString() }
        .joinToString("")
        .replace(NOT_NAME, " ")
        .replace("/", " / ")
        .trim()
        .split(SPACES)
        .filter(String::isNotEmpty)

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NOT_NAME = Regex("[^\\p{L}\\p{N}/]+")
private val SPACES = Regex("$SPACE+")

/** Letters NFD leaves whole: the Polish l, the Turkish dotless i, the German sharp s. */
private val FOLDED = mapOf('ł' to "l", 'ı' to "i", 'ß' to "ss", 'ø' to "o", 'æ' to "ae")

/**
 * The longest name at the head of [tokens], and how many tokens it took.
 *
 * Words in scripts that write none of their own spaces — Chinese, Japanese — are matched from the
 * start of the token, because a sheet glues a suffix onto them ("血红蛋白测定" is the haemoglobin
 * determination): the longest name the token begins with wins, so "血小板压积", the plateletcrit,
 * beats "血小板".
 */
internal fun nameAt(tokens: List<String>, from: Int): Pair<SheetName, Int>? {
    for (length in minOf(SHEET_NAME_TOKENS, tokens.size - from) downTo 1) {
        SHEET_NAMES[tokens.subList(from, from + length)]?.let { return it to length }
    }
    val head = tokens.getOrNull(from)?.takeIf { token -> token.any(::unspaced) } ?: return null
    return UNSPACED_NAMES.filter { (name, _) -> head.startsWith(name) }
        .maxByOrNull { (name, _) -> name.length }
        ?.let { (_, sheetName) -> sheetName to 1 }
}

/**
 * [nameAt], or failing it the one name an OCR slip away: a letter misread ("HAEMDGLOBIN") or a word
 * split by a stray space ("HAEMOGLOBI N"). Only names of eight letters and more, where one wrong
 * letter still leaves one name it can be; and only when the slip points at a single marker.
 */
internal fun nameNear(tokens: List<String>, from: Int): Pair<SheetName, Int>? {
    nameAt(tokens, from)?.let { return it }
    for (length in minOf(FUZZY_TOKENS, tokens.size - from) downTo 1) {
        val joined = tokens.subList(from, from + length).joinToString("")
        if (joined.length < FUZZY_LENGTH || joined.any(Char::isDigit)) continue
        val near = FUZZY_NAMES.filter { (name, _) ->
            abs(name.length - joined.length) <= 1 &&
                withinOneEdit(name, joined)
        }
        val names = near.map { it.second }.distinct()
        if (names.size == 1) return names.single() to length
    }
    return null
}

private const val FUZZY_TOKENS = 3
private const val FUZZY_LENGTH = 8

/** Whether one substitution, insertion or deletion turns [a] into [b]. */
private fun withinOneEdit(a: String, b: String): Boolean {
    if (a == b) return true
    val (short, long) = if (a.length <= b.length) a to b else b to a
    var i = 0
    while (i < short.length && short[i] == long[i]) i++
    return if (short.length == long.length) {
        short.substring(i + 1) == long.substring(i + 1)
    } else {
        short.substring(i) == long.substring(i + 1)
    }
}

private fun unspaced(char: Char): Boolean = Character.UnicodeScript.of(char.code) in UNSPACED_SCRIPTS

private val UNSPACED_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
)

private fun blood(marker: LabMarker?, vararg names: String) = SheetName(marker, null) to names.toList()

private fun urine(marker: LabMarker, vararg names: String) = SheetName(marker, marker) to names.toList()

/** A name that means one thing over blood and another over urine: creatinine, albumin, protein. */
private fun both(blood: LabMarker?, urine: LabMarker?, vararg names: String) = SheetName(blood, urine) to names.toList()

private fun known(vararg names: String) = SheetName(null, null) to names.toList()

/**
 * The names, as sheets print them, in the languages the app speaks and the abbreviations every
 * laboratory shares: the app's own marker titles in its sixteen locales, and what laboratories
 * actually print: SGPT and GPT for ALT, "Blutzucker", "Hb", "T BILI".
 *
 * A hand list, not LOINC's linguistic variants — those need a licence acceptance and a
 * 100 MB table for the thirty markers the app keeps. A sheet whose name is missing here is left for
 * the person to type, never guessed at; add the name when one turns up.
 */
private val NAME_LISTS: List<Pair<SheetName, List<String>>> = listOf(
    blood(
        LabMarker.GLUCOSE,
        "glucose", "glucose fasting", "fasting glucose", "glucose serum", "glucose plasma", "plasma glucose",
        "fasting plasma glucose", "glucose random", "blood glucose", "blood sugar", "sugar", "fasting blood sugar",
        "glu", "glc", "fbs", "fpg", "glukose", "glucose nuchtern", "blutzucker", "blutzucker nuchtern",
        "blutzucker nu", "glucose a jeun", "glycemie", "glycemie a jeun", "glucosa", "glucosa basal", "glicemia",
        "glicose", "glicemia de jejum", "glucosio", "glukoza", "glukoza na czczo", "glukoz", "aclik kan sekeri",
        "aclik glukoz", "kan sekeri", "glukosa", "glukosa puasa", "глюкоза", "глюкоза крови", "глюкоза в крови",
        "глюкоза плазмы", "глюкоза натощак", "сахар крови", "глюкоза крові", "глюкоза натщесерце", "цукор крові", "血糖",
        "葡萄糖", "空腹血糖", "グルコース", "혈당", "포도당",
        "공복혈당", "الجلوكوز", "الغلوكوز", "ग्लूकोज़", "ग्लूकोज",
    ),
    blood(
        LabMarker.HBA1C,
        "hba1c", "hb a1c", "a1c", "hemoglobin a1c", "haemoglobin a1c", "hgb a1c", "hemoglobin a1c / total",
        "hemoglobin a1c / total hemoglobin", "hba1c ifcc", "hba1c ngsp", "hba1c dcct",
        "hemoglobin alc", "haemoglobin alc", "hgb alc", "hbalc", "hb alc", "glycated hemoglobin",
        "glycated haemoglobin", "glycosylated hemoglobin", "glycohemoglobin", "glycohaemoglobin",
        "гликированный гемоглобин", "гликозилированный гемоглобин", "гликогемоглобин", "глікований гемоглобін",
        "глікозильований гемоглобін", "hamoglobin a1c",
        "glykiertes hamoglobin", "hemoglobine glyquee", "hemoglobina glicosilada", "hemoglobina glicada",
        "hemoglobina glucosilada", "emoglobina glicata", "hemoglobina glikowana", "glykovany hemoglobin",
        "glikozile hemoglobin", "糖化血红蛋白", "당화혈색소",
    ),
    blood(
        LabMarker.INSULIN,
        "insulin", "insulin fasting", "fasting insulin", "insulin serum", "инсулин", "інсулін", "insuline", "insulina",
        "inzulin", "insulin nuchtern", "胰岛素", "インスリン", "인슐린",
    ),
    blood(
        LabMarker.CHOLESTEROL,
        "cholesterol", "total cholesterol", "cholesterol total", "cholesterol serum", "cholesterol total serum",
        "chol", "t chol", "tc", "холестерин", "холестерин общий", "общий холестерин", "холестерин загальний",
        "загальний холестерин", "cholesterin",
        "gesamtcholesterin", "cholesterin gesamt", "celkovy cholesterol", "cholesterol celkovy",
        "cholesterol calkowity", "colesterol", "colesterol total", "colesterolo", "colesterolo totale", "kolesterol",
        "total kolesterol", "kolesterol total", "总胆固醇", "胆固醇", "総コレステロール", "총콜레스테롤",
    ),
    blood(
        LabMarker.LDL,
        "ldl", "ldl c", "ldl chol", "ldl chol calc", "ldl chol calc nih", "ldl cholesterol", "ldl cholesterol calc",
        "ldl cholesterol calculated", "ldl cholesterol direct", "ldl calculated", "ldl direct", "direct ldl",
        "cholesterol ldl", "ldl cholesterin", "лпнп", "холестерин лпнп", "лпнп холестерин", "лпнщ", "холестерин лпнщ",
        "лпнщ холестерин", "colesterol ldl",
        "colesterolo ldl", "ldl kolesterol", "kolesterol ldl", "低密度脂蛋白胆固醇", "低密度脂蛋白", "ldlコレステロール",
        "ldl 콜레스테롤", "ldl콜레스테롤",
    ),
    blood(
        LabMarker.HDL,
        "hdl", "hdl c", "hdl chol", "hdl cholesterol", "hdl cholesterol direct", "hdl direct", "cholesterol hdl",
        "hdl cholesterin", "лпвп", "холестерин лпвп", "лпвп холестерин", "лпвщ", "холестерин лпвщ", "лпвщ холестерин",
        "colesterol hdl", "colesterolo hdl",
        "hdl kolesterol", "kolesterol hdl", "高密度脂蛋白胆固醇", "高密度脂蛋白", "hdlコレステロール", "hdl 콜레스테롤",
        "hdl콜레스테롤",
    ),
    blood(
        LabMarker.TRIGLYCERIDES,
        "triglycerides", "triglyceride", "triglycerides serum", "trig", "trigs", "tg", "триглицериды", "тригліцериди",
        "триглицерид", "triglyceriden", "trigliceridos", "trigliceridi", "triglicerideos", "triglicerides",
        "triglicerydy", "triglyceridy", "triacylglyceroly", "trigliserit", "trigliserid", "trigliserida", "甘油三酯",
        "中性脂肪", "トリグリセリド", "중성지방",
    ),
    blood(
        LabMarker.LP_A,
        "lp a", "lpa", "lipoprotein a", "lipoprotein little a", "липопротеин а", "липопротеин a", "ліпопротеїн а",
        "ліпопротеїн a", "lipoproteina a",
        "lipoproteine a", "脂蛋白 a", "지단백 a",
    ),
    both(
        LabMarker.CREATININE,
        LabMarker.URINE_CREATININE,
        "creatinine", "creat", "crea", "krea", "creatinine serum", "creatinine blood", "creatinine enzymatic",
        "kreatinin", "креатинин", "креатинін", "creatinina", "kreatynina", "肌酐", "クレアチニン", "크레아티닌",
    ),
    blood(
        LabMarker.UREA,
        "urea", "urea serum", "мочевина", "сечовина", "harnstoff", "uree", "ureia", "mocznik", "mocovina", "ure",
        "ureum", "尿素",
        "요소",
    ),
    blood(
        LabMarker.URIC_ACID,
        "uric acid", "urate", "uric acid serum", "мочевая кислота", "сечова кислота", "harnsaure", "acide urique",
        "acido urico",
        "kwas moczowy", "kyselina mocova", "urik asit", "asam urat", "尿酸", "요산",
    ),
    blood(
        LabMarker.ALT,
        "alt", "alat", "sgpt", "gpt", "tgp", "alt / sgpt", "alt / gpt", "alt sgpt", "alt gpt", "sgpt alt",
        "alanine aminotransferase", "alanine transaminase", "alanine transferase", "alanine amino transferase",
        "alt alanine aminotransferase", "алт", "алат", "аланинаминотрансфераза", "аланиновая аминотрансфераза",
        "аланінамінотрансфераза",
        "alaninaminotransferase", "alanina aminotransferasa", "alanina aminotransferasi", "alanina aminotransferase",
        "aminotransferaza alaninowa", "alaninaminotransferaza", "丙氨酸氨基转移酶", "谷丙转氨酶",
    ),
    blood(
        LabMarker.AST,
        "ast", "asat", "sgot", "got", "tgo", "ast / sgot", "ast / got", "ast sgot", "ast got", "sgot ast",
        "aspartate aminotransferase", "aspartate transaminase", "aspartic transaminase", "aspartat aminotransferase",
        "aspartate amino transferase", "ast aspartate aminotransferase", "аст", "асат", "аспартатаминотрансфераза",
        "аспартатамінотрансфераза",
        "аспарагиновая аминотрансфераза", "aspartataminotransferase", "aspartato aminotransferasa",
        "aspartato aminotransferasi", "aspartato aminotransferase", "aminotransferaza asparaginianowa",
        "天门冬氨酸氨基转移酶", "谷草转氨酶",
    ),
    blood(
        LabMarker.ALP,
        "alp", "alkaline phosphatase", "alk phosphatase", "alk phos", "alkp", "alk phosphat",
        "serum alkaline phosphatase", "alkalische phosphatase", "phosphatase alcaline", "phosphatases alcalines",
        "fosfatasa alcalina", "fosfatasi alcalina", "fosfatase alcalina", "fosfataza alkaliczna",
        "alkalicka fosfataza", "alkalen fosfataz", "alkali fosfatase", "щелочная фосфатаза", "лужна фосфатаза", "碱性磷酸酶",
        "アルカリホスファターゼ", "알칼리성 인산분해효소",
    ),
    blood(
        LabMarker.GGT,
        "ggt", "ggtp", "gamma gt", "gamma gtp", "γ gt", "γ gtp", "gamma glutamyl transferase",
        "gamma glutamyltransferase", "gamma glutamyl transpeptidase", "gamma glutamyltranspeptidase", "ггт", "ггтп",
        "гамма гт", "гамма глутамилтрансфераза", "гамма глутамилтранспептидаза", "гамма глутамілтрансфераза", "谷氨酰转肽酶",
        "γ 谷氨酰转移酶",
    ),
    blood(
        LabMarker.BILIRUBIN,
        "bilirubin", "bilirubin total", "total bilirubin", "t bili", "t bil", "tbil", "tbili", "bil t", "bilirubin t",
        "serum bilirubin total", "general bilirubin", "билирубин", "билирубин общий", "общий билирубин", "білірубін",
        "білірубін загальний", "загальний білірубін",
        "gesamtbilirubin", "bilirubin gesamt", "bilirubine totale", "bilirrubina total", "bilirubina totale",
        "bilirubina calkowita", "celkovy bilirubin", "bilirubin celkovy", "总胆红素", "総ビリルビン", "총빌리루빈",
    ),
    blood(
        LabMarker.PLATELETS,
        "platelets", "platelet", "platelet count", "platelets count", "plt", "plt count", "thrombocytes",
        "thrombocyte count", "thrombozyten", "thro", "thrombo", "тромбоциты", "тромбоцити", "plaquettes", "plaquetas",
        "piastrine",
        "plytki krwi", "trombocyty", "trombosit", "trombositler", "trombocyten", "血小板", "血小板计数", "血小板数",
        "혈소판",
    ),
    blood(
        LabMarker.HEMOGLOBIN,
        "hemoglobin", "haemoglobin", "hgb", "hb", "hemoglobin hb", "haemoglobin hb", "hemoglobin estimation",
        "haemoglobin estimation", "hemoglobin total", "гемоглобин", "гемоглобін", "hamoglobin", "hemoglobine",
        "hemoglobina",
        "emoglobina", "血红蛋白", "ヘモグロビン", "血色素量", "혈색소", "헤모글로빈",
    ),
    blood(
        LabMarker.FERRITIN,
        "ferritin", "serum ferritin", "ferritin serum", "ferritine", "ferritina", "ferrytyna", "feritin", "феритин",
        "ферритин", "铁蛋白", "フェリチン", "페리틴",
    ),
    blood(
        LabMarker.TSAT,
        "transferrin saturation", "tsat", "saturation of transferrin", "transferrin sat", "iron saturation",
        "насыщение трансферрина", "коэффициент насыщения трансферрина", "насичення трансферину", "transferrinsattigung",
        "coefficient de saturation de la transferrine", "saturation de la transferrine", "saturacion de transferrina",
        "indice de saturacion de transferrina", "saturazione della transferrina", "saturacao da transferrina",
        "wysycenie transferyny", "saturace transferinu", "transferrin saturasyonu", "转铁蛋白饱和度",
        "トランスフェリン飽和度", "트랜스페린 포화도",
    ),
    blood(
        LabMarker.CRP,
        "crp", "c reactive protein", "hs crp", "hscrp", "crp hs", "crp high sensitivity", "high sensitivity crp",
        "c reactive protein cardiac", "cardio crp", "c reaktives protein", "с реактивный белок", "срб",
        "с реактивний білок",
        "proteine c reactive", "proteina c reactiva", "proteina c reattiva", "proteina c reativa",
        "bialko c reaktywne", "c reaktivni protein", "c reaktif protein", "c反应蛋白", "c反応性たんぱく",
        "c反応性蛋白", "c반응성단백",
    ),
    blood(
        LabMarker.TSH,
        "tsh", "thyrotropin", "thyroid stimulating hormone", "tsh third generation", "tsh 3rd generation",
        "tsh ultrasensitive", "ттг", "тиреотропный гормон", "тиреотропин", "тиреотропний гормон", "thyreotropin",
        "tirotropina",
        "tireotropina", "tyreotropina", "tyreotropin", "tirotropin", "促甲状腺激素", "甲状腺刺激ホルモン",
        "갑상선자극호르몬",
    ),
    blood(
        LabMarker.FT4,
        "ft4", "free t4", "t4 free", "free thyroxine", "thyroxine free", "t4 libre", "t4 libera", "t4 livre",
        "wolna tyroksyna", "volny t4", "serbest t4", "t4 bebas", "т4 свободный", "свободный т4",
        "тироксин свободный", "свободный тироксин", "т4 вільний", "вільний т4", "тироксин вільний", "вільний тироксин",
        "freies t4", "freies thyroxin", "游离甲状腺素", "游离t4", "遊離t4",
        "遊離サイロキシン", "유리 t4", "유리t4",
    ),
    blood(
        LabMarker.VITAMIN_D,
        "vitamin d", "vitamin d 25 oh", "vitamin d 25 oh total", "25 oh vitamin d", "25 oh d", "25 oh d3",
        "25 hydroxy vitamin d", "25 hydroxyvitamin d", "vitamin d 25 hydroxy", "vitamin d 25 hydrox",
        "vitamin d total", "vitamin d3", "vitamin d 25 oh total ia", "calcidiol", "витамин d", "витамин д", "вітамін d",
        "вітамін д",
        "25 oh витамин d", "vitamine d", "vitamina d", "witamina d", "d vitamini", "维生素d", "维生素 d",
        "ビタミンd", "비타민 d", "비타민d",
    ),
    blood(
        LabMarker.B12,
        "vitamin b12", "b12", "vit b12", "vitamin b 12", "vitamin b12 level", "cobalamin", "cyanocobalamin",
        "витамин b12", "витамин в12", "цианокобаламин", "кобаламин", "вітамін b12", "вітамін в12", "ціанокобаламін",
        "vitamine b12", "vitamina b12", "witamina b12",
        "b12 vitamini", "维生素b12", "ビタミンb12", "비타민 b12",
    ),
    blood(
        LabMarker.FOLATE,
        "folate", "folate serum", "serum folate", "folate lvl", "folic acid", "folic acid serum", "folat",
        "folsaure", "folates", "acide folique", "folato", "acido folico", "kwas foliowy", "foliany",
        "kyselina listova", "folik asit", "фолиевая кислота", "фолаты", "фолат", "фолієва кислота", "叶酸", "葉酸", "엽산",
    ),
    blood(
        LabMarker.MAGNESIUM,
        "magnesium", "magnesium serum", "serum magnesium", "магний", "магній", "magnesio", "magnez", "horcik",
        "magnezyum",
        "镁", "マグネシウム", "마그네슘",
    ),
    urine(
        LabMarker.URINE_ACR,
        "albumin / creatinine ratio", "albumin / creatinine", "albumin creatinine ratio", "microalbumin / creatinine",
        "microalbumin / creatinine ratio", "microalbumin creatinine ratio", "urine albumin / creatinine ratio",
        "uacr", "acr", "alb / creat", "alb / creat ratio", "albumin / kreatinin quotient", "albumin / kreatinin",
        "альбумин / креатинин", "отношение альбумин / креатинин", "rapport albumine / creatinine",
        "cociente albumina / creatinina", "rapporto albumina / creatinina", "relacao albumina / creatinina",
        "albumin / kreatinin orani",
    ),
    urine(
        LabMarker.URINE_ALBUMIN,
        "microalbumin", "urine albumin", "albumin urine", "microalbumin urine", "u albumin", "микроальбумин",
        "мікроальбумін", "альбумін в сечі",
        "альбумин в моче", "mikroalbumin", "microalbumine", "albumin im urin",
    ),
    urine(
        LabMarker.URINE_CREATININE,
        "urine creatinine",
        "creatinine urine",
        "creatinine random urine",
        "u creatinine",
        "креатинин в моче",
        "креатинин мочи",
        "креатинін в сечі",
        "креатинін сечі",
        "kreatinin im urin",
    ),
    urine(
        LabMarker.URINE_PROTEIN,
        "urine protein",
        "protein urine",
        "total protein urine",
        "белок в моче",
        "білок в сечі",
        "eiweiss im urin",
    ),
    urine(
        LabMarker.URINE_SG,
        "specific gravity", "sp gravity", "sp gr", "specific gravity urine", "относительная плотность",
        "удельный вес", "spezifisches gewicht", "відносна щільність", "питома вага", "densite urinaire",
        "densidad urinaria", "peso specifico",
        "ciezar wlasciwy", "specificka hmotnost", "specificka hustota", "dansite", "berat jenis", "比重", "비중",
    ),
    both(null, LabMarker.URINE_PH, "ph", "ph wert", "рн", "реакция"),
    both(
        null,
        LabMarker.URINE_PROTEIN,
        "protein", "proteins", "белок", "eiweiss", "білок", "proteine", "proteinas", "bialko",
    ),
    both(null, LabMarker.URINE_ALBUMIN, "albumin", "альбумин", "альбумін", "albumine", "albumina"),
    SheetName(null, null, mcv = true) to listOf("mcv", "mean cell volume", "mean corpuscular volume"),
    known(
        // The blood count around the two figures it gives the app.
        "wbc", "white blood cells", "white blood cell", "white blood cell count", "white cell count",
        "total white cell count", "total wbc", "wbc count", "leukocytes", "leucocytes", "total leucocyte count",
        "tlc", "wcc", "rbc", "rbc count", "red blood cells", "red blood cell", "red blood cell count", "red cell count",
        "red blood cell rbc count", "total erythrocyte count", "erythrocytes", "hct", "hematocrit", "haematocrit",
        "packed cell volume", "pcv", "mch", "mchc", "mean cell hemoglobin", "mean cell haemoglobin",
        "mean cell hb", "mean cell hb concn", "mean corpuscular hemoglobin", "mean corpuscular hb",
        "mean corpusc", "mean corpuscular hemoglobin concentration", "rdw", "rdw cv", "rdw sd", "rdwc", "rdws",
        "rbc distribution width", "red cell distribution width", "red blood cell distribution",
        "red blood cell distribut", "erythrocyte medium volume", "mpv", "mean platelet volume", "pdw", "pdwc", "pdws",
        "pct", "plateletcrit", "platelet distribution width", "platelet comment", "platelets increase",
        "reticulocytes", "reticulocyte", "reticulocyte hemoglobin", "reticulocyte haemoglobin", "nrbc", "nrbcs",
        "nucleated rbc", "nucleated red blood cell", "immature granulocytes", "neutrophils", "neutrophil",
        "lymphocytes", "lymphocyte", "monocytes", "monocyte", "eosinophils", "eosinophil", "basophils", "basophil",
        "esr", "hemoglobin a2", "hemoglobin f",
        // A chemistry panel's other rows, and the blood figures that share a word with one the app keeps.
        "bun", "urea nitrogen", "blood urea nitrogen", "urea nitrogen bun", "bun / creatinine", "азот мочевины",
        "尿素窒素", "尿素氮", "sodium", "potassium", "chloride", "co2", "carbon dioxide", "bicarbonate", "anion gap",
        "calcium", "phosphorus", "phosphate", "iron", "tibc", "uibc", "transferrin", "total protein",
        "protein total", "general protein", "globulin", "globulins", "albumin / globulin", "albumin globulin ratio",
        "a / g ratio", "bilirubin direct", "direct bilirubin", "bilirubin indirect", "indirect bilirubin",
        "serum bilirubin direct", "d bil", "dbil", "билирубин прямой", "прямой билирубин", "непрямой билирубин",
        "білірубін прямий", "прямий білірубін", "білірубін непрямий", "непрямий білірубін", "загальний білок",
        "bilirubin direkt", "creatine kinase", "ck", "cpk", "ldh", "amylase", "lipase", "egfr", "gfr",
        "estimated gfr", "estimated glomerular filtration rate", "mdrd", "mdrd gfr", "скф", "non hdl",
        "non hdl cholesterol", "non hdl c", "vldl", "vldl cholesterol", "ldl / hdl", "chol / hdl",
        "chol / hdlc ratio", "eag", "eavg glucose", "estimated average glucose", "glucose tolerance",
        "glucose urine", "urine glucose", "c peptide", "insulin like growth factor", "igf", "t3", "ft3", "free t3",
        "t4", "t4 total", "total t4", "thyroxine", "anti tpo", "rbc folate", "folate rbc", "red cell folate",
        "apolipoprotein a1", "apolipoprotein b", "apob", "homocysteine", "creatinine clearance",
        "active b12", "holotranscobalamin", "1 25 dihydroxy vitamin d", "calcitriol", "magnesium rbc",
    ),
)

/** Every name by its tokens, and how long the longest one is. */
private val SHEET_NAMES: Map<List<String>, SheetName> =
    NAME_LISTS.flatMap { (name, spellings) -> spellings.map { nameTokens(it) to name } }.toMap()

private val SHEET_NAME_TOKENS = SHEET_NAMES.keys.maxOf { it.size }

/** Every name of eight letters and more with its spaces taken out, for [nameNear]. */
private val FUZZY_NAMES: List<Pair<String, SheetName>> = SHEET_NAMES.mapNotNull { (key, name) ->
    key.filter { it != "/" }.joinToString("").takeIf { it.length >= FUZZY_LENGTH }?.let { it to name }
}

/** The one-token names written without spaces, matched as a token's start. */
private val UNSPACED_NAMES: List<Pair<String, SheetName>> =
    SHEET_NAMES.filterKeys { it.size == 1 && it[0].any(::unspaced) }.map { (key, name) -> key[0] to name }
