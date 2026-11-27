package com.pwnall.health

import java.text.Normalizer

// The words of suicide, of an emergency and of too much of a medicine in the app's fourteen languages
// besides English and Russian. Without them, a person
// writing «nie mogę oddychać», «死にたい» or «لا أستطيع التنفس» would be met with "found nothing" and the menu.
// Each key only brings the question English and Russian words of the same sign bring, first: whether
// they can keep themselves safe right now, whether it is very hard to breathe now, whether a face
// drooped, whether the pain in the chest is there now, whether more of a medicine was taken than it
// says. A key never answers, never files, never names a topic, never starts a thread — a key of the
// chest brings the chest's question and nothing after it — and never counts as a word read
// ([WordsFound.language]): the line to call for an emergency stays beside every reply. A false alarm
// costs one gentle question; a phrase missed here is a person the chat goes on asking about their sleep.
//
// AWAITING NATIVE-SPEAKER REVIEW. These keys are written as natural phrasing — the
// standard forms, the common colloquial ones, typing without the accents, and Hindi in Latin letters
// too — and are to be checked by a native speaker of each language and by a safety consultant before
// they are relied on; until then the chat makes no promise about these languages. `SafetyWordsTest`
// holds every one of them, the phrases people type that they must read, and the idioms and everyday
// words that must stay no key («笑死了», «웃겨 죽겠다», «ich lach mich tot», «gülmekten öldüm», «me muero de risa»).
// The app is free and open source, so a speaker's correction is a change to these lists and to that
// test's sentences. Until one comes the test keeps the languages level: the same plain sentence of
// every sign is read in each of them («숨이 막혀요», «意識がありません» and «he vomitado sangre» among
// them), and no list is left with a handful of keys.
//
// How a key matches, one clause at a time — a comma, a full stop, a question mark or a line break ends
// one ([CLAUSE_CUTS]), and nothing is matched across it:
// - the clause's words ([normalisedWords]) are folded ([foldedForKeys]): the accents of Latin letters,
//   «ı», «ß», «ł», the hamza and the forms of the Arabic alif, the Hindi nukta and nasal dots, and "nahin"
//   and "nhi" of Hindi in Latin letters — so «nie chce zyc» is «nie chcę żyć»; a German key is matched
//   with its «ä», «ö», «ü» typed as "ae", "oe", "ue" too;
// - a key in a script with spaces is words in a row, every word whole but the last, which is the start
//   of one unless the key ends in a space. An Arabic word is tried without the «و» or «ف», the «ب», «ل»
//   or «ك» and the «ال» stuck to its front too, so «وأفكر بالانتحار» reads «انتحار». A key of suicide or
//   of breathing lets two words in all stand between its own: «ich will nur noch sterben»;
// - a key in Chinese or Japanese is found anywhere in the clause, its spaces taken out;
// - a key in Korean too, but a space of the message may only fall where the key has one: «죽고싶다» and
//   «죽고 싶다» are a key, «여자 살 빼기» — «자살» across two words — is none;
// - a phrase of [SafetyKeys.not] holds its words from every key, never across a space in Chinese,
//   Japanese and Korean: «想死你了» is "I miss you", «我想死，你能帮我吗» and «好想死 你懂吗» are not.

/** The app's languages besides English and Russian, whose words of suicide and emergency are read here. */
internal enum class SafetyLanguage { AR, CS, DE, ES, FR, HI, ID, IT, JA, KO, PL, PT, TR, ZH }

/**
 * One language's keys, by the sign each one brings the question of. [chest] is the chest or the heart
 * as a place and [chestPain] a pain, a pressure or a tightness: the chest's question is asked for both
 * of one language in one message, as [chestPainIn] asks it for English and Russian, or for a heart
 * attack or a chest's pain named in one word ([heartAttack]). [overdose] is too much of a medicine;
 * its words that name an overdose by itself are words of suicide too, as "overdose" and
 * «передозировка» are. Someone who is not breathing — «no respira», «息をしていない» — stands in
 * [unresponsive] and in [breathing] both, as English and Russian ask both questions of it
 * ([NOT_BREATHING_KEYS]). [not] are phrases that keep every key out of their words: dying of laughter,
 * a chicken's breast, and an app, a phone or a treatment that does not respond.
 */
internal class SafetyKeys(
    val crisis: List<String>,
    val unresponsive: List<String>,
    val breathing: List<String>,
    val stroke: List<String>,
    val blood: List<String>,
    val chest: List<String>,
    val chestPain: List<String>,
    val heartAttack: List<String>,
    val overdose: List<String>,
    val not: List<String> = emptyList(),
) {
    /** The keys by the sign each brings; null for the phrases of [not]. */
    fun bySign(): List<Pair<SafetySign?, List<String>>> = listOf(
        SafetySign.CRISIS to crisis,
        SafetySign.UNRESPONSIVE to unresponsive,
        SafetySign.BREATHING to breathing,
        SafetySign.STROKE to stroke,
        SafetySign.BLOOD to blood,
        SafetySign.CHEST to chest,
        SafetySign.CHEST_PAIN to chestPain,
        SafetySign.HEART_ATTACK to heartAttack,
        SafetySign.OVERDOSE to overdose,
        null to not,
    )
}

/** What a key of [SafetyKeys] brings the question of. */
internal enum class SafetySign {
    CRISIS,
    UNRESPONSIVE,
    BREATHING,
    STROKE,
    BLOOD,
    CHEST,
    CHEST_PAIN,
    HEART_ATTACK,
    OVERDOSE,
}

/** Every language's keys. AWAITING NATIVE-SPEAKER REVIEW (see the head of this file). */
internal val SAFETY_WORDS: Map<SafetyLanguage, SafetyKeys> = mapOf(
    SafetyLanguage.AR to SafetyKeys(
        crisis = listOf(
            "انتحار", "انتحر", "اريد ان اموت", "أريد أن أموت", "اريد الموت", "أتمنى الموت", "اتمنى اموت", "بدي موت",
            "بدي اموت", "نفسي اموت", "عايز اموت", "عايزة اموت", "ابغى اموت", "لا أريد أن أعيش", "لا اريد العيش",
            "لا أريد الحياة", "مش عايز اعيش", "مش عايزة اعيش", "ما بدي عيش", "ما ابغى اعيش", "سئمت الحياة",
            "تعبت من الحياة", "اقتل نفسي", "أقتل نفسي", "قتل نفسي", "أنهي حياتي", "انهاء حياتي", "إنهاء حياتي",
            "أؤذي نفسي", "إيذاء نفسي", "اذية نفسي", "اجرح نفسي", "أجرح نفسي", "اقطع شراييني", "ارمي نفسي",
            "الكل أحسن بدوني", "احسن بدوني", "أريد أن أختفي", "اريد اختفي", "نفسي اختفي",
            "جرعة زائدة",
        ),
        unresponsive = listOf(
            "فاقد الوعي", "فاقدة الوعي", "فقد الوعي", "فقدت الوعي", "مغمى عليه", "مغمى عليها", "أغمي عليه",
            "أغمي عليها", "لا يستيقظ", "لا تستيقظ", "ما يصحى", "مش بيصحى", "لا يستجيب", "لا تستجيب", "لا يتنفس",
            "لا تتنفس", "ما يتنفس", "مش بيتنفس", "توقف عن التنفس", "توقفت عن التنفس",
        ),
        breathing = listOf(
            "لا أستطيع التنفس", "لا اقدر اتنفس", "ما اقدر اتنفس", "ما اقدر أتنفس", "مش قادر اتنفس", "مش قادرة اتنفس",
            "مو قادر اتنفس", "ما اكدر اتنفس", "ما بقدر اتنفس", "مابقدر اتنفس", "ضيق تنفس", "ضيق التنفس",
            "ضيق في التنفس", "ضيق نفس", "ضيقة نفس", "صعوبة في التنفس", "صعوبة التنفس", "أختنق", "اختناق",
            "نفسي مقطوع", "مخنوق", "لا يتنفس", "لا تتنفس", "ما يتنفس", "مش بيتنفس", "توقف عن التنفس", "توقفت عن التنفس",
        ),
        stroke = listOf(
            "جلطة دماغية", "جلطة في الدماغ", "جلطة بالدماغ", "سكتة دماغية", "وجهي مايل", "وجهه مايل", "وجهها مايل",
            "فمي مائل", "فمه مائل", "تنميل في يدي", "تنميل في ذراعي", "تنميل بيدي", "خدر في يدي", "خدر في ذراعي",
            "ضعف في يدي", "ضعف في ذراعي", "لا أستطيع رفع يدي", "ما اقدر ارفع يدي", "ثقل في اللسان", "لساني ثقيل",
            "كلامي ثقيل", "لا أستطيع الكلام", "مش قادر اتكلم",
        ),
        blood = listOf(
            "أتقيأ دم", "اتقيأ دم", "تقيأت دم", "تقيؤ دم", "استفرغ دم", "استفرغت دم", "ترجيع دم", "رجعت دم", "قيء دموي",
            "قيء بدم", "تفل القهوة", "مثل تفل القهوة",
        ),
        chest = listOf("صدر", "صدري", "الصدر", "بصدري", "بالصدر", "في صدري", "قلب ", "قلبي "),
        chestPain = listOf(
            "ألم ", "الألم ", "بألم ", "وجع", "يوجع", "توجع", "يؤلم", "تؤلم", "ضيق ", "ضغط ", "ثقل ", "حرقة ",
        ),
        heartAttack = listOf("نوبة قلبية", "أزمة قلبية", "جلطة قلبية", "جلطة في القلب", "سكتة قلبية"),
        overdose = listOf(
            "جرعة زائدة", "حبوب كثيرة", "أخذت حبوب كثير", "اخذت حبوب كثيرة", "علبة دواء كاملة", "علبة كاملة من الدواء",
            "كل الحبوب", "كل الدواء", "شريط كامل", "كمية كبيرة من الدواء", "كمية كبيرة من الحبوب",
        ),
        not = listOf(
            "اموت من الضحك", "موت من الضحك", "ميت من الضحك", "صدر دجاج", "صدر الدجاج", "التطبيق لا يستجيب",
            "الهاتف لا يستجيب", "الجوال لا يستجيب", "الشاشة لا تستجيب", "لا يستجيب للعلاج", "لا تستجيب للعلاج",
            "لا يستجيب للدواء",
        ),
    ),
    SafetyLanguage.CS to SafetyKeys(
        crisis = listOf(
            "sebevražd", "sebevražedn", "sebevrah", "chci umřít", "chtěl bych umřít", "chtěla bych umřít", "nechci žít",
            "nechci už žít", "nechci dál žít", "nechci už dál žít", "už nechci žít", "zabít se", "chci se zabít",
            "zabiju se", "vzít si život", "skončit se životem", "skoncovat se životem", "chci si ublížit",
            "ubližuju si", "řežu se", "řezat se", "pořezala jsem se schválně", "pořezal jsem se schválně", "sebepoškoz",
            "skočit z okna", "skočit z mostu", "všem bude líp beze mě", "všem bude lépe beze mě", "líp beze mě",
            "chci zmizet", "už se neprobudit", "nechci se probudit", "předávkov",
        ),
        // «Neodpovídá» is far more often "does not match" («váha neodpovídá») than someone not answering.
        unresponsive = listOf(
            "bezvědom", "v bezvědomí", "ztratil vědomí", "ztratila vědomí", "neprobouzí se", "nejde probudit",
            "nereaguje", "omdlel", "omdlela", "nemůžu ho probudit", "nemůžu ji probudit", "nedá se probudit",
            "nedýchá ", "přestal dýchat", "přestala dýchat",
        ),
        breathing = listOf(
            "nemůžu dýchat", "nemůže dýchat", "nemohu dýchat", "nejde mi dýchat", "nemůžu se nadechnout",
            "nemohu se nadechnout", "popadnout dech", "lapám po dech", "lapat po dech", "chytit dech", "dusím se",
            "dusí se", "nedostatek vzduchu", "chybí mi vzduch", "těžko se mi dýchá", "špatně se mi dýchá", "dušnost",
            "nedýchá ", "přestal dýchat", "přestala dýchat",
        ),
        stroke = listOf(
            "mrtvic", "mozková příhoda", "mozkovou příhodu", "pokleslý koutek", "povislý koutek", "pokleslá tvář",
            "ochrnul", "ochrnut", "necítím ruku", "necítím nohu", "slabá ruka", "nemůžu zvednout ruku", "zmatená řeč",
            "nezřetelná řeč", "nemůžu mluvit", "nejde mi mluvit", "plete se mi jazyk",
        ),
        blood = listOf(
            "zvracím krev", "zvracel krev", "zvracela krev", "zvracení krve", "krvavé zvracení", "kávová sedlina",
            "jako kávová sedlina", "zvracel jsem krev", "zvracela jsem krev", "zvrací krev", "krev ve zvratcích",
        ),
        chest = listOf("hrud", "na prsou", "v prsou", "srdc"),
        chestPain = listOf("bolest", "bolí", "bodá", "píchá", "tlak", "tlačí", "svírá", "pálí"),
        heartAttack = listOf("infarkt", "srdeční záchvat", "zástava srdce"),
        overdose = listOf(
            "předávkov", "moc prášků", "příliš mnoho prášků", "příliš mnoho léků", "spoustu prášků", "všechny prášky",
            "celé balení", "celou krabičku", "hrst prášků",
        ),
        not = listOf(
            "umírám smíchy", "umřu smíchy", "aplikace nereaguje", "telefon nereaguje", "mobil nereaguje",
            "displej nereaguje", "obrazovka nereaguje", "nereaguje na léky", "nereaguje na léčbu",
        ),
    ),
    SafetyLanguage.DE to SafetyKeys(
        crisis = listOf(
            "selbstmord", "suizid", "mich umbringen", "mich umzubringen", "bring mich um", "bringe mich um",
            "mich töten", "mich zu töten", "mich selbst töten", "will sterben", "möchte sterben", "sterben will",
            "sterben möchte",
            "will nicht mehr leben", "möchte nicht mehr leben", "will nicht leben", "nicht mehr leben will",
            "nicht mehr leben möchte", "keine lust mehr zu leben", "kein bock mehr zu leben", "lebensmüde",
            "mir das leben nehmen", "das leben nehmen", "will tot sein", "wäre lieber tot", "wäre besser tot",
            "lieber tot sein", "mir etwas antun", "mir was antun", "mich ritzen", "ritze mich", "ich ritze",
            "selbstverletz", "will mir wehtun", "will mir weh tun", "mir selbst wehtun", "mir selbst weh tun",
            "aus dem fenster springen", "von der brücke springen", "vor den zug werfen", "vor einen zug werfen",
            "vor den zug springen", "wären besser dran ohne mich", "besser ohne mich", "will verschwinden",
            "nicht mehr aufwachen", "nie mehr aufwachen", "überdosis",
        ),
        unresponsive = listOf(
            "bewusstlos", "ohnmächtig", "wacht nicht auf", "wacht nicht mehr auf", "reagiert nicht",
            "nicht ansprechbar", "kommt nicht zu sich", "atmet nicht", "aufgehört zu atmen",
        ),
        breathing = listOf(
            "kann nicht atmen", "kann nicht mehr atmen", "kann kaum atmen", "bekomme keine luft", "bekomm keine luft",
            "bekommt keine luft", "kriege keine luft", "krieg keine luft", "kriegt keine luft", "keine luft mehr",
            "kaum luft", "kaum noch luft", "schlecht luft", "wenig luft", "schwer atmen", "atmen fällt schwer",
            "atemnot", "luftnot", "ersticke", "erstickt", "ringe nach luft", "schnappe nach luft", "japse nach luft",
            "atmet nicht", "aufgehört zu atmen",
        ),
        stroke = listOf(
            "schlaganfall", "hirnschlag", "gesicht hängt", "gesicht ist schief", "mundwinkel hängt",
            "hängender mundwinkel", "arm ist taub", "arm taub", "taubes gefühl im arm", "arm ist schwach",
            "kann den arm nicht heben", "kann meinen arm nicht heben", "arm gelähmt", "lähmung", "verwaschene sprache",
            "sprache ist verwaschen", "kann nicht richtig sprechen", "finde keine worte", "lalle",
        ),
        blood = listOf(
            "blut erbrochen", "erbreche blut", "blut gekotzt", "kotze blut", "blut spucken", "blut gespuckt",
            "bluterbrechen", "blutiges erbrechen", "kaffeesatz", "spucke blut", "erbricht blut", "blut im erbrochenen",
        ),
        chest = listOf("brust ", "brustkorb", "brustbein", "herz "),
        chestPain = listOf(
            "schmerz", "tut weh", "weh", "druck", "drückt", "enge", "eng ", "stechen", "sticht", "brennt", "beklemm",
        ),
        heartAttack = listOf(
            "herzinfarkt",
            "herzanfall",
            "infarkt",
            "brustschmerz",
            "brustenge",
            "herzstechen",
            "herzschmerzen",
            "herzstillstand",
        ),
        overdose = listOf(
            "überdosis", "zu viele tabletten", "zu viel tabletten", "zu viele pillen", "alle tabletten",
            "alle meine tabletten", "ganze packung", "eine packung tabletten", "handvoll tabletten",
            "hand voll tabletten", "zu viele schlaftabletten", "mehr als verschrieben", "doppelte dosis",
        ),
        not = listOf(
            "lach mich tot", "totlachen", "lache mich tot", "lachen mich tot", "app reagiert nicht",
            "handy reagiert nicht", "telefon reagiert nicht", "bildschirm reagiert nicht", "display reagiert nicht",
            "uhr reagiert nicht",
        ),
    ),
    SafetyLanguage.ES to SafetyKeys(
        crisis = listOf(
            "suicid", "quiero morir", "me quiero morir", "quisiera morir", "deseo morir", "ganas de morir",
            "no quiero vivir", "no quiero seguir vivien", "no tengo ganas de vivir", "sin ganas de vivir", "matarme",
            "me voy a matar", "me quiero matar", "quitarme la vida", "acabar con mi vida", "terminar con mi vida",
            "acabar con todo", "quiero hacerme daño", "ganas de hacerme daño", "hacerme daño a propósito",
            "me hago daño a propósito", "me hago daño a mí mism", "quiero lastimarme", "me lastimo a propósito",
            "me corto a propósito", "me corté a propósito", "cortarme a propósito", "me hago cortes",
            "cortarme las venas", "cortarme los brazos", "me corto las venas", "me corto los brazos", "autolesion",
            "tirarme por la ventana", "tirarme de un puente", "estarían mejor sin mí", "estarán mejor sin mí",
            "están mejor sin mí", "estaría mejor sin mí", "prefiero estar muert", "preferiría estar muert",
            "quiero estar muert", "quisiera estar muert", "quiero desaparecer", "no despertar nunca",
            "no volver a despertar", "dormir y no despertar", "dormir para siempre", "sobredosis",
        ),
        unresponsive = listOf(
            "inconsciente", "sin conocimiento", "perdió el conocimiento", "perdió la conciencia", "no despierta",
            "no reacciona", "no responde", "desmayado", "desmayada", "no se despierta", "no respira ",
            "dejó de respirar", "dejado de respirar",
        ),
        breathing = listOf(
            "no puedo respirar", "no puede respirar", "no puedo ni respirar", "me ahogo", "se ahoga",
            "me falta el aire", "me falta aire", "falta de aire", "no me entra aire", "no me entra el aire",
            "no me llega el aire", "dificultad para respirar", "cuesta respirar", "me asfixio", "asfixia",
            "no respira ", "dejó de respirar", "dejado de respirar",
        ),
        stroke = listOf(
            "ictus", "derrame cerebral", "infarto cerebral", "embolia cerebral", "accidente cerebrovascular",
            "cara caída", "se me cae la cara", "boca torcida", "boca de lado", "no siento el brazo", "brazo entumecido",
            "no puedo mover el brazo", "no puedo levantar el brazo", "debilidad en el brazo", "brazo sin fuerza",
            "no puedo hablar bien", "habla arrastrada", "arrastro las palabras", "se me traba la lengua",
        ),
        blood = listOf(
            "vómito sangre", "vomito sangre", "vomité sangre", "vomitando sangre", "vomitar sangre",
            "vómitos con sangre", "vómito con sangre", "posos de café", "vomitado sangre", "vomita sangre",
            "sangre en el vómito",
        ),
        chest = listOf("pecho", "tórax", "torácico", "corazón"),
        chestPain = listOf(
            "dolor", "duele", "presión", "opresión", "aprieta", "pesadez", "ardor", "punzada", "pinchazo",
        ),
        heartAttack = listOf("infarto", "ataque al corazón", "ataque cardíaco", "ataque cardiaco", "paro cardíaco"),
        overdose = listOf(
            "sobredosis", "demasiadas pastillas", "me tomé muchas pastillas", "me tome muchas pastillas",
            "me tomé todas las pastillas", "todas mis pastillas", "un montón de pastillas", "una caja de pastillas",
            "la caja entera", "toda la caja", "un puñado de pastillas", "demasiados medicamentos",
            "demasiadas píldoras", "más de lo recetado", "doble dosis",
        ),
        not = listOf(
            "me muero de risa", "morirme de risa", "morir de risa", "matarme de risa", "me muero de ganas",
            "dolor no responde", "no responde al tratamiento", "no responde a los analgésicos",
            "no responde a los medicamentos", "no responde a la medicación", "no responde al teléfono",
            "app no responde", "aplicación no responde", "teléfono no responde", "móvil no responde",
            "pantalla no responde", "falta de aire acondicionado",
        ),
    ),
    SafetyLanguage.FR to SafetyKeys(
        crisis = listOf(
            "suicid", "je veux mourir", "j ai envie de mourir", "envie de mourir", "envie de crever", "je veux crever",
            "me tuer", "me suicider", "veux en finir", "envie d en finir", "en finir avec la vie",
            "plus envie de vivre", "ne veux plus vivre", "veux plus vivre", "pas envie de vivre", "marre de vivre",
            "mettre fin à mes jours", "mettre fin à ma vie", "envie de me faire du mal", "veux me faire du mal",
            "me fais du mal",
            "me scarifier", "je me scarifie", "scarification", "me mutiler", "automutil", "me couper les veines",
            "m ouvrir les veines", "sauter par la fenêtre", "sauter du pont", "me jeter sous un train",
            "mieux sans moi", "veux disparaître", "envie de disparaître", "ne plus me réveiller",
            "dormir pour toujours", "idées noires", "surdose",
        ),
        unresponsive = listOf(
            "inconscient", "sans connaissance", "perdu connaissance", "ne se réveille pas", "ne répond pas",
            "ne réagit pas", "évanoui", "ne respire plus", "ne respire pas", "arrêté de respirer",
        ),
        breathing = listOf(
            "n arrive pas à respirer", "n arrive plus à respirer", "arrive pas à respirer", "arrive plus à respirer",
            "peux pas respirer", "peux plus respirer", "j étouffe", "il étouffe", "elle étouffe", "manque d air ",
            "plus d air ", "mal à respirer", "du mal à respirer", "souffle court", "je suffoque",
            "n arrive pas à reprendre mon souffle", "ne respire plus", "ne respire pas", "arrêté de respirer",
        ),
        stroke = listOf(
            "avc ", "attaque cérébrale", "accident vasculaire", "visage paralysé", "visage tombe", "bouche de travers",
            "bouche tordue", "bras paralysé", "bras engourdi", "ne sens plus mon bras", "plus de force dans le bras",
            "mal à parler", "du mal à parler", "trouve plus mes mots", "trouve pas mes mots", "difficulté à parler",
            "articule mal",
        ),
        blood = listOf(
            "vomi du sang",
            "vomis du sang",
            "vomir du sang",
            "vomissement de sang",
            "vomissements de sang",
            "marc de café",
            "vomit du sang",
            "sang dans le vomi",
        ),
        chest = listOf("poitrine", "thorax", "thoracique"),
        // A weight is the chest's only on it («un poids sur la poitrine»): «perdre du poids» is no pain.
        chestPain = listOf(
            "douleur", "mal", "serre", "serrement", "oppress", "brûle", "brûlure", "pique", "pression", "écrase",
            "crampe",
        ),
        heartAttack = listOf(
            "crise cardiaque",
            "infarctus",
            "arrêt cardiaque",
            "poids sur la poitrine",
            "poids sur le thorax",
        ),
        overdose = listOf(
            "surdose", "trop de médicaments", "trop de cachets", "trop de pilules", "trop de comprimés",
            "tous mes médicaments", "tous mes cachets", "toute la boîte", "une boîte entière", "plaquette entière",
            "boîte de somnifères", "trop de somnifères",
        ),
        not = listOf(
            "mort de rire", "morte de rire", "morts de rire", "ne répond pas au traitement",
            "ne répond pas aux médicaments", "ne répond pas au téléphone", "appli ne répond pas",
            "application ne répond pas", "téléphone ne répond pas", "écran ne répond pas", "plus d air conditionné",
            "manque d air conditionné",
        ),
    ),
    SafetyLanguage.HI to SafetyKeys(
        crisis = listOf(
            "आत्महत्या", "खुदकुशी", "ख़ुदकुशी", "मरना चाह", "मर जाना चाह", "मर जाऊं", "मर जाऊँ", "मुझे मरना है",
            "जीना नहीं चाह", "जीने का मन नहीं", "जीने की इच्छा नहीं", "खुद को मार", "ख़ुद को मार", "अपनी जान ले",
            "जान दे दूं", "जान दे दूँ", "खुद को नुकसान", "खुद को चोट", "हाथ की नस काट", "सब मेरे बिना बेहतर",
            "गायब हो जाना चाह", "ओवरडोज़", "marna chah", "mar jana chah", "mujhe marna hai", "mujhe mar jana hai",
            "jeena nahi chah", "jina nahi chah", "jeene ka mann nahi", "jeene ka man nahi", "khud ko maar",
            "apni jaan le", "khudkushi", "khudkhushi",
        ),
        unresponsive = listOf(
            "बेहोश", "होश में नहीं", "होश नहीं आ रहा", "नहीं उठ रहा", "नहीं उठ रही", "behosh", "hosh nahi",
            "सांस नहीं ले रहा", "सांस नहीं ले रही", "सांस नहीं चल रही", "सांस बंद ", "saans nahi le raha",
            "saans nahi le rahi", "saans nahi chal rahi", "saans band ",
        ),
        breathing = listOf(
            "सांस नहीं ले पा", "साँस नहीं ले पा", "सांस नहीं आ", "सांस लेने में तकलीफ", "सांस लेने में दिक्कत",
            "सांस फूल", "दम घुट", "सांस रुक", "saans nahi le pa", "sans nahi le pa", "saas nahi le pa",
            "saans nahi aa", "sans nahi aa", "saas nahi aa", "saans lene mein takleef", "saans lene me takleef",
            "saans lene mein dikkat", "saans lene me dikkat", "dum ghut", "dam ghut", "saans phool", "saans ruk",
            "सांस नहीं ले रहा", "सांस नहीं ले रही", "सांस नहीं चल रही", "सांस बंद ", "saans nahi le raha",
            "saans nahi le rahi", "saans nahi chal rahi", "saans band ",
        ),
        stroke = listOf(
            "लकवा", "स्ट्रोक", "ब्रेन स्ट्रोक", "चेहरा टेढ़ा", "मुंह टेढ़ा", "मुँह टेढ़ा", "हाथ सुन्न", "बांह सुन्न",
            "हाथ में कमजोरी", "हाथ में कमज़ोरी", "हाथ नहीं उठ रहा", "बोलने में दिक्कत", "बोलने में तकलीफ",
            "जुबान लड़खड़ा", "ज़ुबान लड़खड़ा", "आवाज़ लड़खड़ा", "lakwa", "laqwa", "haath sunn", "hath sunn",
            "chehra tedha", "muh tedha", "munh tedha", "bolne mein dikkat", "bolne me dikkat", "zubaan ladkhada",
            "zuban ladkhada",
        ),
        blood = listOf(
            "खून की उल्टी",
            "उल्टी में खून",
            "खून उल्टी",
            "khoon ki ulti",
            "ulti mein khoon",
            "ulti me khoon",
            "khoon ulti",
        ),
        // Hindi typed in Latin letters says "chest" in English as often as «seena».
        chest = listOf("सीने", "सीना", "छाती", "दिल ", "seene", "seena", "sina ", "chhati", "chati ", "chest ", "dil "),
        chestPain = listOf(
            "दर्द", "जकड़न", "दबाव", "भारीपन", "जलन", "चुभन", "dard", "dabav", "dabaav", "jakdan", "bharipan", "jalan",
        ),
        heartAttack = listOf("दिल का दौरा", "हार्ट अटैक", "dil ka daura", "dil ka dora"),
        overdose = listOf(
            "ओवरडोज़", "बहुत सारी गोलियां", "बहुत सारी गोलियाँ", "बहुत गोलियां खा", "बहुत गोलियाँ खा", "ज्यादा गोलियां",
            "ज़्यादा गोलियां", "पूरा पत्ता", "सारी गोलियां खा", "सारी गोलियाँ खा", "bahut saari goliyan",
            "bahut sari goliyan", "bahut goliyan kha", "saari goliyan kha", "sari goliyan kha", "zyada goliyan",
            "jyada goliyan", "pura patta",
        ),
        not = listOf("हंसते हंसते मर", "हँसते हँसते मर", "hanste hanste mar"),
    ),
    SafetyLanguage.ID to SafetyKeys(
        crisis = listOf(
            "bunuh diri", "ingin mati", "pengen mati", "pingin mati", "aku mau mati", "saya mau mati", "mau mati aja",
            "mau mati saja", "tidak ingin hidup", "tidak mau hidup", "gak mau hidup", "ga mau hidup", "nggak mau hidup",
            "enggak mau hidup", "tak ingin hidup", "malas hidup", "capek hidup", "lelah hidup", "mengakhiri hidup",
            "akhiri hidup", "menyakiti diri", "melukai diri", "menyayat tangan", "sayat tangan", "loncat dari jendela",
            "lompat dari gedung", "lompat dari jembatan", "lebih baik tanpa aku", "lebih baik tanpa saya",
            "ingin menghilang", "pengen menghilang", "tidak bangun lagi", "gak bangun lagi", "overdosis",
        ),
        unresponsive = listOf(
            "tidak sadarkan diri", "tak sadarkan diri", "pingsan", "hilang kesadaran", "kehilangan kesadaran",
            "tidak bangun bangun", "gak bangun bangun", "tidak merespon", "tidak merespons", "tidak sadar diri",
            "gak sadar diri", "nggak sadar diri", "tidak bernapas", "tidak bernafas", "berhenti bernapas",
            "berhenti bernafas",
        ),
        breathing = listOf(
            "tidak bisa bernapas", "tidak bisa bernafas", "tidak bisa napas", "tidak bisa nafas", "gak bisa napas",
            "gak bisa nafas", "ga bisa napas", "ga bisa nafas", "nggak bisa napas", "nggak bisa nafas",
            "susah bernapas", "susah bernafas", "susah napas", "susah nafas", "sesak napas", "sesak nafas",
            "kehabisan napas", "kehabisan nafas", "tercekik", "tidak bernapas", "tidak bernafas", "berhenti bernapas",
            "berhenti bernafas",
        ),
        // A leg numb from sitting is no stroke («отсидела ногу»): only an arm, a hand, a face, a side.
        stroke = listOf(
            "kena struk", "terkena struk", "wajah mencong", "mulut mencong", "muka mencong", "wajah turun sebelah",
            "lumpuh sebelah", "tangan mati rasa", "tangan saya mati rasa", "tanganku mati rasa", "lengan mati rasa",
            "lengan saya mati rasa", "lenganku mati rasa", "wajah mati rasa", "wajah saya mati rasa",
            "wajahku mati rasa", "muka mati rasa", "separuh badan mati rasa", "sebelah badan mati rasa", "tangan lemas",
            "lengan lemas", "bicara pelo", "ngomong pelo", "susah bicara", "tidak bisa bicara", "wajahnya mencong",
            "mulutnya mencong", "mukanya mencong", "bibir mencong", "bibirnya mencong",
        ),
        blood = listOf(
            "muntah darah", "muntah berdarah", "ampas kopi", "muntahkan darah", "memuntahkan darah",
            "muntah bercampur darah", "muntah campur darah", "muntah ada darah", "muntahnya ada darah",
            "darah di muntah", "darah dalam muntah",
        ),
        // «Dada» whole: «dadakan» is "sudden".
        chest = listOf("dada ", "dadaku", "dadanya", "dadamu", "jantung"),
        chestPain = listOf("sakit", "nyeri", "sesak", "berat", "tertekan", "panas", "terbakar", "ditekan"),
        heartAttack = listOf("serangan jantung", "henti jantung"),
        overdose = listOf(
            "overdosis",
            "kebanyakan obat",
            "kebanyakan minum obat",
            "minum banyak obat",
            "minum semua obat",
            "terlalu banyak obat",
            "obat terlalu banyak",
            "sebungkus obat",
            "satu strip obat",
            "segenggam obat",
        ),
        not = listOf(
            "baterai mau mati", "hp mau mati", "lampu mau mati", "dada ayam", "berat badan", "aplikasi tidak merespon",
            "hp tidak merespon", "ponsel tidak merespon", "layar tidak merespon",
        ),
    ),
    SafetyLanguage.IT to SafetyKeys(
        crisis = listOf(
            "suicid", "voglio morire", "vorrei morire", "voglia di morire", "non voglio più vivere",
            "non voglio vivere", "non ho più voglia di vivere", "ammazzarmi", "voglio ammazzar", "mi ammazzo",
            "uccidermi", "voglio uccider", "mi uccido", "togliermi la vita", "farla finita", "voglio farmi del male",
            "mi faccio del male", "farmi del male di proposito", "tagliarmi le vene", "tagliarmi i polsi", "autolesion",
            "buttarmi dalla finestra", "buttarmi da un ponte", "buttarmi sotto un treno",
            "sarebbero meglio senza di me", "meglio senza di me", "voglio sparire", "non svegliarmi più",
            "sovradosaggio",
        ),
        unresponsive = listOf(
            "privo di sensi", "priva di sensi", "incosciente", "svenuto", "svenuta", "non si sveglia", "non risponde",
            "non reagisce", "perso conoscenza", "perso i sensi", "non respira ", "smesso di respirare",
        ),
        breathing = listOf(
            "non riesco a respirare", "non riesce a respirare", "non respiro", "mi manca l aria", "manca l aria",
            "manca il respiro", "soffoco", "sto soffocando", "fatica a respirare", "non posso respirare",
            "difficoltà a respirare", "difficoltà respiratori", "sento soffocare", "sta soffocando", "manca il fiato",
            "fame d aria", "non respira ", "smesso di respirare",
        ),
        stroke = listOf(
            "ictus", "colpo apoplettico", "faccia storta", "bocca storta", "viso storto", "braccio intorpidito",
            "non sento il braccio", "braccio debole", "non riesco ad alzare il braccio", "parola impastata",
            "non riesco a parlare", "difficoltà a parlare", "biascico",
        ),
        blood = listOf(
            "vomito sangue", "vomitato sangue", "vomitando sangue", "vomitare sangue", "fondi di caffè",
            "vomita sangue", "vomitava sangue", "sangue nel vomito", "vomito con sangue", "posa di caffè",
        ),
        chest = listOf("petto", "torace", "toracico", "cuore"),
        // A weight is the chest's only on it («un peso sul petto»): «perdere peso» is no pain.
        chestPain = listOf("dolore", "male", "oppressione", "stretta", "stringe", "bruciore", "fitta", "pressione"),
        heartAttack = listOf(
            "infarto",
            "attacco di cuore",
            "attacco cardiaco",
            "arresto cardiaco",
            "peso sul petto",
            "peso al petto",
        ),
        overdose = listOf(
            "sovradosaggio", "troppe pillole", "troppe pastiglie", "troppe compresse", "troppi farmaci",
            "tutte le pillole", "tutte le pastiglie", "una scatola di pillole", "una scatola intera",
            "una manciata di pillole", "una manciata di pastiglie",
        ),
        // «Farla finita con le sigarette» is done with a habit; «farla finita con la vita» stays a key.
        not = listOf(
            "morire dal ridere", "muoio dal ridere", "morto dal ridere", "petto di pollo", "petto d anatra",
            "farla finita con le", "farla finita con il", "farla finita con i ", "farla finita con gli",
            "farla finita con lo ", "farla finita con l ", "non risponde alla terapia", "non risponde ai farmaci",
            "non risponde al telefono", "app non risponde", "telefono non risponde", "cellulare non risponde",
            "schermo non risponde", "manca l aria condizionata", "mi manca l aria condizionata",
        ),
    ),
    SafetyLanguage.JA to SafetyKeys(
        // «早く楽になりたい» is "I want to feel better soon": only «もう楽になりたい» is the wish to be gone.
        crisis = listOf(
            "死にたい", "しにたい", "死にたく", "死んでしまいたい", "死んじゃいたい", "死のう", "自殺", "自死", "消えたい",
            "きえたい", "消えてしまいたい", "消えちゃいたい", "生きていたくない", "生きたくない", "生きるのがつら",
            "生きるのが辛", "生きるのがしんど", "生きるのが嫌", "生きてる意味がない", "生きる意味がない", "死んだ方がまし",
            "死んだほうがまし", "死んだ方がいい", "死んだほうがいい", "リスカ", "リストカット", "自傷", "手首を切",
            "首を吊", "首吊り", "飛び降りたい", "もう楽になりたい", "死んで楽になりたい", "いなくなりたい",
            "目が覚めなければ", "オーバードーズ",
        ),
        unresponsive = listOf(
            "意識がない", "意識が無い", "意識不明", "意識を失", "気を失", "目を覚まさない", "反応がない",
            "意識がありません", "意識がなく", "意識ない", "反応がありません", "目を覚ましません", "息をしていない",
            "息をしていません", "息してない", "呼吸をしていない", "呼吸をしていません", "呼吸していない",
            "呼吸が止ま", "反応しない", "反応しません",
        ),
        breathing = listOf(
            "息ができない", "息が出来ない", "息できない", "息が吸えない", "息が苦しい", "息苦しい", "息がしづらい",
            "息しづらい", "息がしにくい", "呼吸ができない", "呼吸が苦しい", "呼吸がしづらい", "呼吸しづらい",
            "呼吸がしにくい", "呼吸困難", "窒息", "息をしていない", "息をしていません", "息してない",
            "呼吸をしていない", "呼吸をしていません", "呼吸していない", "呼吸が止ま",
        ),
        stroke = listOf(
            "脳卒中", "脳梗塞", "脳出血", "顔が歪", "顔がゆがん", "口が曲が", "腕がしびれ", "腕が痺れ", "手がしびれ",
            "手が痺れ", "腕に力が入らない", "手に力が入らない", "ろれつが回らない", "呂律が回らない", "言葉が出ない",
            "うまく話せない",
        ),
        blood = listOf("血を吐", "吐血", "吐いたら血", "嘔吐に血", "コーヒーかす", "コーヒー残渣"),
        chest = listOf("胸", "心臓"),
        chestPain = listOf("痛", "苦し", "締め付け", "しめつけ", "圧迫", "重い"),
        heartAttack = listOf("心筋梗塞", "心臓発作", "狭心症", "心停止"),
        overdose = listOf(
            "オーバードーズ", "過剰摂取", "薬を大量に", "大量に飲んだ", "薬を全部飲", "睡眠薬を全部", "薬を飲みすぎ",
            "錠剤を飲みすぎ", "薬をたくさん飲んだ", "薬をたくさん飲みました", "薬をいっぱい飲んだ", "睡眠薬をたくさん",
        ),
        // Heartburn, a foreboding and nerve hold the character of the chest too; an app, a phone or a
        // screen that does not respond is no one unconscious, nor is a pain that does not respond to a medicine.
        not = listOf(
            "胸肉", "胸焼け", "胸やけ", "胸騒ぎ", "度胸", "アプリが反応しない", "アプリが反応しません", "スマホが反応しない",
            "スマホが反応しません", "携帯が反応しない", "携帯が反応しません", "画面が反応しない", "画面が反応しません",
            "ボタンが反応しない", "ボタンが反応しません", "薬に反応しない", "薬に反応しません",
        ),
    ),
    SafetyLanguage.KO to SafetyKeys(
        crisis = listOf(
            "죽고 싶", "자살", "살고 싶지 않", "살기 싫", "그만 살고 싶", "목숨을 끊", "스스로 목숨", "극단적 선택",
            "자해", "손목을 긋", "손목 긋", "사라지고 싶", "없어지고 싶", "죽어버리고 싶", "뛰어내리고 싶",
            "약 먹고 죽", "깨어나고 싶지 않", "과다복용", "죽었으면 좋겠", "죽는 게 낫", "죽는 것이 낫", "죽어야겠",
            "죽어버릴", "죽을 생각", "죽고만 싶", "손목을 그었", "손목 그었", "살고 싶지가 않", "살기가 싫",
            "삶을 끝내고 싶", "삶을 포기하고 싶", "생을 마감", "내가 없는 게 낫", "내가 없어지면",
            "눈을 뜨고 싶지 않", "영원히 잠들고 싶",
        ),
        unresponsive = listOf(
            "의식이 없", "의식을 잃", "정신을 잃", "기절", "깨어나지 않", "안 깨어나", "반응이 없", "의식 없어",
            "의식 없습니다", "깨워도 안 일어나", "숨을 안 쉬", "숨을 쉬지 않", "호흡이 없", "호흡이 멈",
        ),
        breathing = listOf(
            "숨을 못 쉬", "숨을 쉴 수가 없", "숨을 쉴 수 없", "숨이 안 쉬", "숨쉬기 힘들", "숨쉬기가 힘들", "숨이 막히",
            "숨막혀", "호흡곤란", "호흡 곤란", "숨이 막혀", "숨 막혀", "숨 막히", "숨 못 쉬", "숨 쉬기 힘들",
            "숨 쉬기가 힘들", "숨 쉬기 어렵", "숨 쉬기가 어렵", "숨이 가쁘", "숨이 가빠", "질식", "숨을 안 쉬",
            "숨을 쉬지 않", "호흡이 없", "호흡이 멈",
        ),
        stroke = listOf(
            "뇌졸중", "중풍", "뇌경색", "뇌출혈", "입이 돌아가", "입이 돌아갔", "얼굴이 한쪽으로", "얼굴 한쪽이 처",
            "얼굴이 처졌", "팔에 힘이 없", "팔에 힘이 안 들어가", "팔이 저리", "팔이 마비", "다리가 마비", "얼굴이 마비",
            "말이 어눌", "발음이 이상", "혀가 꼬", "말이 안 나와",
        ),
        blood = listOf(
            "피를 토",
            "토혈",
            "토할 때 피",
            "구토에 피",
            "커피 찌꺼기",
            "커피찌꺼기",
            "토했는데 피",
            "토에 피가",
        ),
        chest = listOf("가슴", "심장"),
        chestPain = listOf("아파", "아프", "아픈", "통증", "답답", "조여", "짓누르", "쥐어짜", "찌르"),
        heartAttack = listOf("심장마비", "심근경색", "협심증", "심정지"),
        // «약을 다 먹었는데» is a course finished: only all of it at once is too much.
        overdose = listOf(
            "과다복용", "약을 많이 먹", "약을 너무 많이", "약 너무 많이", "수면제를 많이", "수면제를 너무 많이",
            "약을 한 움큼", "약을 한꺼번에", "한꺼번에 다 먹", "한 통 다 먹",
        ),
        // «커피를 토했어요» is coffee thrown up: the key «피를 토» stands inside it.
        not = listOf(
            "자살골", "가슴살", "커피를 토", "앱이 반응이 없", "앱 반응이 없", "화면이 반응이 없", "화면 반응이 없",
            "터치 반응이 없", "폰이 반응이 없", "약에 반응이 없",
        ),
    ),
    SafetyLanguage.PL to SafetyKeys(
        crisis = listOf(
            "samobój", "chcę umrzeć", "chciałbym umrzeć", "chciałabym umrzeć", "nie chcę żyć", "nie chcę już żyć",
            "już nie chcę żyć", "nie chcę dalej żyć", "nie chce mi się żyć", "odechciało mi się żyć",
            "nie mam po co żyć", "odebrać sobie życie", "chcę się zabić", "zabiję się", "się zabić", "skończyć ze sobą",
            "skończę ze sobą", "tnę się", "ciąć się", "pocięłam się", "pociąłem się", "samookalecz",
            "zrobić sobie krzywdę", "zrobię sobie krzywdę", "skoczyć z okna", "skoczyć z mostu",
            "wszystkim będzie lepiej beze mnie", "lepiej beze mnie", "chcę zniknąć", "nie obudzić się", "przedawkow",
        ),
        // «Nie odpowiada» is far more often "does not suit" («to mi nie odpowiada») than someone not answering.
        unresponsive = listOf(
            "nieprzytomn", "stracił przytomność", "straciła przytomność", "nie budzi się", "nie reaguje", "zemdlał",
            "zemdlała", "nie mogę go obudzić", "nie mogę jej obudzić", "nie oddycha ", "przestał oddychać",
            "przestała oddychać",
        ),
        breathing = listOf(
            "nie mogę oddychać", "nie może oddychać", "nie mogę złapać oddechu", "złapać tchu", "brak tchu",
            "brakuje mi tchu", "brakuje mi powietrza", "brak powietrza", "duszę się", "dusi się", "ciężko mi oddychać",
            "trudno mi oddychać", "duszność", "nie oddycha ", "przestał oddychać", "przestała oddychać",
        ),
        stroke = listOf(
            "udar ", "udaru ", "udarem ", "wylew ", "wylewu ", "opadnięty kącik", "opadł kącik", "twarz opadła",
            "krzywa twarz", "krzywe usta", "drętwieje ręka", "zdrętwiała ręka", "nie czuję ręki", "słaba ręka",
            "nie mogę podnieść ręki", "bełkocze", "bełkot", "niewyraźna mowa", "nie mogę mówić", "opadł mu kącik",
            "opadł jej kącik", "opada kącik", "opadający kącik",
        ),
        blood = listOf(
            "wymiotuję krwią", "wymiotował krwią", "wymiotowała krwią", "wymioty krwią", "krwawe wymioty",
            "fusy od kawy", "fusy kawy", "zwymiotowałem krwią", "zwymiotowałam krwią", "krew w wymiocinach",
        ),
        // The breastbone as the pain stands behind it («za mostkiem»); «mostek» is the bridge exercise too.
        chest = listOf(
            "klatce piersiow", "klatka piersiow", "klatki piersiow", "klatkę piersiow", "klatka ", "klatce ", "klatkę ",
            "mostkiem", "mostka", "mostku", "w piersiach", "w piersi", "serc",
        ),
        chestPain = listOf(
            "ból", "boli", "bolą", "kłuje", "piecze", "pieczenie", "ucisk", "uciska", "ściska", "gniecie", "ciężar",
        ),
        // «Zawał» whole: «zawaliłem dietę», «jestem zawalony» are no heart.
        heartAttack = listOf("zawał ", "zawału ", "zawałem ", "atak serca", "zatrzymanie akcji serca"),
        overdose = listOf(
            "przedawkow", "za dużo tabletek", "za dużo leków", "całe opakowanie", "wszystkie tabletki",
            "garść tabletek", "garść leków", "za dużo proszków", "całą paczkę tabletek", "wszystkie leki",
            "garść proszków",
        ),
        not = listOf(
            "umieram ze śmiechu",
            "umrę ze śmiechu",
            "aplikacja nie reaguje",
            "telefon nie reaguje",
            "ekran nie reaguje",
            "zegarek nie reaguje",
            "nie reaguje na leki",
            "nie reaguje na leczenie",
        ),
    ),
    SafetyLanguage.PT to SafetyKeys(
        crisis = listOf(
            "suicíd", "suicid", "quero morrer", "vontade de morrer", "não quero viver", "não quero mais viver",
            "não aguento mais viver", "me matar", "matar me", "tirar minha vida", "tirar a minha vida",
            "acabar com a minha vida", "acabar com tudo", "quero me machucar", "quero me cortar", "vou me cortar",
            "autolesão", "automutil",
            "pular da janela", "me jogar da ponte", "ficariam melhor sem mim", "melhor sem mim", "quero sumir",
            "quero desaparecer", "não acordar mais", "dormir para sempre", "superdosagem",
        ),
        unresponsive = listOf(
            "inconsciente", "desmaiado", "desmaiada", "desacordado", "desacordada", "não acorda", "não responde",
            "não reage", "perdeu a consciência", "perdeu os sentidos", "não respira ", "parou de respirar",
        ),
        // «Ar» whole: «falta de arroz» is no air.
        breathing = listOf(
            "não consigo respirar", "não consegue respirar", "falta de ar ", "me falta o ar ", "sem ar ", "sufocando",
            "estou sufocando", "dificuldade para respirar", "dificuldade de respirar", "não posso respirar",
            "dificuldade em respirar", "dificuldade respiratória", "difícil respirar", "custa respirar", "a sufocar",
            "falta ar ", "não respira ", "parou de respirar",
        ),
        stroke = listOf(
            "avc ", "derrame", "acidente vascular", "rosto caído", "boca torta", "rosto torto", "braço dormente",
            "não sinto o braço", "braço fraco", "fraqueza no braço", "não consigo levantar o braço", "fala enrolada",
            "fala arrastada", "enrolando a língua", "dificuldade para falar", "boca está torta", "boca ficou torta",
            "boca entortou",
        ),
        blood = listOf(
            "vomitei sangue", "vomitando sangue", "vômito com sangue", "vomitar sangue", "borra de café",
            "vomitou sangue", "vomita sangue", "sangue no vômito", "vômito de sangue", "vômitos com sangue",
        ),
        chest = listOf("peito", "tórax", "torácica", "coração"),
        // A weight is the chest's only on it («peso no peito»): «perder peso» is no pain.
        chestPain = listOf("dor ", "dores ", "dói ", "doendo", "aperto", "pressão", "queimação", "pontada"),
        heartAttack = listOf(
            "infarto",
            "enfarte",
            "ataque cardíaco",
            "ataque do coração",
            "parada cardíaca",
            "peso no peito",
            "peso sobre o peito",
        ),
        overdose = listOf(
            "superdosagem", "tomei muitos comprimidos", "tomei muitos remédios", "tomei vários comprimidos",
            "tomei vários remédios", "comprimidos de uma vez", "remédios de uma vez", "comprimidos demais",
            "remédios demais", "todos os comprimidos", "todos os remédios", "uma cartela inteira", "a cartela toda",
            "a caixa toda", "um punhado de comprimidos",
        ),
        not = listOf(
            "morrendo de rir", "morri de rir", "morrer de rir", "peito de frango", "peito de peru",
            "não responde ao tratamento", "não responde aos remédios", "não responde aos medicamentos",
            "não responde ao telefone", "app não responde", "aplicativo não responde", "celular não responde",
            "tela não responde", "falta ar condicionado", "falta de ar condicionado", "sem ar condicionado",
        ),
    ),
    SafetyLanguage.TR to SafetyKeys(
        crisis = listOf(
            "intihar", "ölmek isti", "ölmek istedim", "keşke ölsem", "ölsem keşke", "yaşamak istemi", "kendimi öldür",
            "canıma kıy", "hayatıma son ver", "kendime zarar ver", "kendimi kesiyor", "kendimi kestim",
            "kendimi keseceğ", "kendimi kesmek", "kendimi kesmeyi", "bileğimi kes", "kendimi pencereden at",
            "köprüden atla", "bensiz daha iyi", "hayattan bıkt", "yaşamaktan bıkt", "hayattan yoruldum",
            "yaşamaktan yoruldum", "yok olmak istiyorum", "kaybolmak istiyorum", "uyanmak istemiyorum", "aşırı doz",
        ),
        // «Bayıldım» is "I loved it" as often as "I fainted": only someone else, «bayıldı», «bayılmış».
        unresponsive = listOf(
            "bilinci kapalı", "bilincini kaybetti", "baygın", "bayıldı ", "bayılmış ", "uyanmıyor", "tepki vermiyor",
            "cevap vermiyor", "bilinci yok", "bilinci yerinde değil", "uyandıramıyorum", "nefes almıyor",
            "nefesi durdu",
        ),
        breathing = listOf(
            "nefes alam", "nefes alamıyorum", "nefes almakta zorlan", "nefes almakta güçlük", "nefes almak zor",
            "nefesim daralıyor", "nefesim daraldı", "nefes darlığı", "boğuluyorum", "nefesim kesiliyor",
            "nefes alıp veremiyorum", "nefes almıyor", "nefesi durdu",
        ),
        // «İnme» alone is going down too («merdiven inme»): the stroke had, «inme geçirdi», «inme oldu».
        stroke = listOf(
            "felç", "inme geçir", "inme oldu", "inme indi", "beyin kanaması", "yüzüm kaydı", "yüzü kaydı",
            "ağzım kaydı", "ağzı kaydı", "kolum uyuştu", "kolumu kaldıramıyorum", "kolumda güçsüzlük", "konuşamıyorum",
            "dilim dolaşıyor", "dili dolaşıyor",
        ),
        blood = listOf("kan kusuyorum", "kan kustum", "kan kustu", "kan kusma", "kanlı kusma", "kahve telvesi"),
        chest = listOf("göğüs", "göğsü", "göğsüm", "kalb", "kalp "),
        // «Acı» whole («acil», «acıktım»), and no «ağırlık», which is the gym's weights too.
        chestPain = listOf("ağrı", "acı ", "acısı", "acıyor", "sıkışma", "sıkışıyor", "baskı", "yanma", "batma"),
        heartAttack = listOf("kalp krizi", "enfarktüs", "kalp durması", "göğsümde ağırlık", "göğüste ağırlık"),
        overdose = listOf(
            "aşırı doz", "çok fazla ilaç", "çok fazla hap", "fazla ilaç içtim", "bütün ilaçlarımı", "tüm ilaçlarımı",
            "bir kutu ilaç", "kutu dolusu", "avuç dolusu", "bir sürü ilaç içtim", "bir sürü hap içtim",
            "bir sürü ilaç aldım", "bir sürü hap yuttum",
        ),
        not = listOf(
            "gülmekten öldüm", "gülmekten ölüyorum", "gülmekten öleceğim", "tavuk göğsü", "telefona cevap vermiyor",
            "telefonuma cevap vermiyor", "mesajlara cevap vermiyor", "tedaviye cevap vermiyor", "ilaca cevap vermiyor",
            "uygulama cevap vermiyor", "uygulama tepki vermiyor",
        ),
    ),
    SafetyLanguage.ZH to SafetyKeys(
        crisis = listOf(
            "不想活", "活不下去", "想死", "想去死", "自杀", "自殺", "轻生", "輕生", "割腕", "自残", "自殘", "自伤", "伤害自己",
            "傷害自己", "结束生命", "結束生命", "结束自己的生命", "了结自己", "死了算了", "不如死了", "活着没意思",
            "活著沒意思", "活着没有意义", "想跳楼", "要跳楼", "想消失", "不想醒来", "过量服药", "药物过量",
        ),
        unresponsive = listOf(
            "昏迷", "失去意识", "失去意識", "不省人事", "叫不醒", "没有反应", "沒有反應", "没反应", "昏倒", "晕倒", "暈倒",
            "没有呼吸", "沒有呼吸", "没呼吸", "不呼吸了", "呼吸停止", "停止呼吸",
        ),
        breathing = listOf(
            "喘不过气", "喘不過氣", "喘不上气", "无法呼吸", "無法呼吸", "不能呼吸", "呼吸不了", "呼吸困难", "呼吸困難",
            "透不过气", "透不過氣", "上不来气", "窒息", "没有呼吸", "沒有呼吸", "没呼吸", "不呼吸了", "呼吸停止",
            "停止呼吸",
        ),
        stroke = listOf(
            "中风", "中風", "脑梗", "腦梗", "脑卒中", "脑溢血", "脑出血", "嘴歪", "口眼歪斜", "脸歪", "臉歪", "半边脸",
            "半邊臉", "手臂发麻", "手臂發麻", "手发麻", "胳膊发麻", "手臂无力", "胳膊没力气", "说话不清", "說話不清",
            "口齿不清", "口齒不清", "说不出话", "說不出話",
        ),
        blood = listOf("吐血", "呕血", "嘔血", "吐出血", "呕吐物有血", "咖啡渣"),
        chest = listOf("胸", "心口", "心脏", "心臟"),
        chestPain = listOf("痛", "疼", "闷", "悶", "压", "壓", "紧", "緊", "沉重"),
        heartAttack = listOf("心脏病发作", "心臟病發作", "心梗", "心肌梗塞", "心肌梗死", "心绞痛", "心絞痛", "心脏骤停"),
        overdose = listOf(
            "过量服药", "過量服藥", "药物过量", "吃了很多药", "吃了很多藥", "吃了太多药", "吃太多药", "吃了好多药",
            "药吃多了", "吃多了药", "吃了一整瓶", "一整瓶药", "一把药", "吞了很多药", "吃了很多安眠药", "太多安眠药",
            "好多安眠药", "一整盒药",
        ),
        // "I miss you" is «想死你了» — never «我想死，你…»; nerves, a stress and a blood pressure hold «紧» and «压».
        not = listOf(
            "想死你了", "想死你啦", "想死你啊", "想死你呀", "想死你们了", "想死你们啦", "想死你们啊", "想死你们呀", "想死我了",
            "想死我啦", "鸡胸", "雞胸", "紧张", "緊張", "压力", "壓力", "血压", "血壓", "手机没反应", "手机没有反应",
            "手機沒有反應", "屏幕没反应", "屏幕没有反应", "应用没反应", "应用没有反应", "软件没反应", "药没反应",
            "药没有反应",
        ),
    ),
)

/**
 * [words] (as [normalisedWords] gives them) as the other languages' keys are matched against them: the
 * accents of Latin letters taken off and «ı», «ß», «ł», «ø», «đ», «æ», «œ» spelled plain; the hamza,
 * the madda and the forms of the alif, «ى» and «ة» of Arabic as they are typed without them; the
 * Hindi nukta and nasal dots taken off, so «नही» typed without its dot is «नहीं»; and Hindi's "nahin",
 * "nhi" and "nai" in Latin letters as "nahi". Cyrillic, Chinese, Japanese and Korean stay as they are.
 */
internal fun foldedForKeys(words: String): String {
    val decomposed = Normalizer.normalize(words, Normalizer.Form.NFD)
    val folded = StringBuilder(decomposed.length)
    decomposed.forEach { char ->
        val afterLatin = folded.lastOrNull()?.let { it in 'a'..'z' } == true
        when {
            char in LATIN_ACCENTS && afterLatin -> Unit
            char in ARABIC_MARKS || char in DEVANAGARI_MARKS -> Unit
            else -> FOLDS[char]?.let { folded.append(it) } ?: folded.append(char)
        }
    }
    return Normalizer.normalize(folded, Normalizer.Form.NFC).split(' ').joinToString(" ") { SPELLINGS[it] ?: it }
}

private val LATIN_ACCENTS: CharRange = '\u0300'..'\u036F'

/** The Arabic marks the decomposition leaves: the vowels, the hamza above and below, the madda. */
private val ARABIC_MARKS: CharRange = '\u064B'..'\u065F'

/** The Hindi nukta, chandrabindu and anusvara: «ज़» is «ज», «नहीं» is «नही». */
private val DEVANAGARI_MARKS: Set<Char> = setOf('\u093C', '\u0901', '\u0902')

private val FOLDS: Map<Char, String> = mapOf(
    'ı' to "i", 'ß' to "ss", 'ł' to "l", 'ø' to "o", 'đ' to "d", 'æ' to "ae", 'œ' to "oe",
    '\u0649' to "\u064A", '\u0629' to "\u0647", '\u0671' to "\u0627", '\u06CC' to "\u064A", '\u06A9' to "\u0643",
)

/** Words of Hindi in Latin letters spelled more ways than one, as their keys spell them. */
private val SPELLINGS: Map<String, String> = mapOf(
    "nahin" to "nahi",
    "nhi" to "nahi",
    "nhin" to "nahi",
    "nahee" to "nahi",
    "nai" to "nahi",
)

/** A German key with «ä», «ö», «ü» spelled "ae", "oe", "ue" too, as they are typed without the letters. */
private fun spellingsOf(written: String, language: SafetyLanguage): List<String> {
    if (language != SafetyLanguage.DE || written.none { it in UMLAUTS }) return listOf(written)
    return listOf(written, written.map { UMLAUTS[it] ?: it.toString() }.joinToString(""))
}

private val UMLAUTS: Map<Char, String> = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue")

/**
 * The marks that end a clause, which no key and no phrase of [SafetyKeys.not] is matched across, nor one
 * of the English and Russian [EMERGENCY_NOT].
 */
internal val CLAUSE_CUTS = Regex("[,.!?;:…\\n\\r，。！？、；：،؛؟।॥¿¡]")

/** How a key's words are found: as words, as characters anywhere (Chinese, Japanese), or as Korean. */
private enum class KeyScript { WORDS, CHARS, HANGUL }

private fun scriptOf(char: Char): KeyScript = when (Character.UnicodeScript.of(char.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> KeyScript.CHARS
    Character.UnicodeScript.HANGUL -> KeyScript.HANGUL
    else -> KeyScript.WORDS
}

/** The signs whose keys let a word or two stand between their own: «ich will nur noch sterben». */
private val GAPPED = setOf(SafetySign.CRISIS, SafetySign.BREATHING)

/** How many words in all may stand between the words of a key of [GAPPED]. */
private const val MAX_GAP = 2

/**
 * One clause as the keys are matched against it: [words] folded ([foldedForKeys]); [stems] each word
 * as it is, and an Arabic one also without what stands stuck to its front ([arabicStems]); [joined] the
 * words without the spaces between them, for the keys of Chinese, Japanese and Korean, and [chunk] the
 * word each of its characters belongs to.
 */
private class ClauseWords(normalised: String) {
    val words: List<String> = foldedForKeys(normalised).split(' ').filter(String::isNotEmpty)
    val stems: List<List<String>> = words.map(::arabicStems)
    val joined: String = words.joinToString("")
    val chunk: IntArray = words.flatMapIndexed { index, word -> List(word.length) { index } }.toIntArray()
}

/**
 * [word], and for an Arabic one the word without the conjunction «و» or «ف», the preposition «ب», «ل»
 * or «ك» and the article «ال» before it — «بالانتحار» is «انتحار», «ولا» is «لا» — as long as two
 * letters are left.
 */
internal fun arabicStems(word: String): List<String> {
    if (word.isEmpty() || Character.UnicodeScript.of(word[0].code) != Character.UnicodeScript.ARABIC) {
        return listOf(word)
    }
    val stems = ARABIC_PROCLITICS.filter { word.startsWith(it) && word.length - it.length >= 2 }
        .map { word.substring(it.length) }
    return (listOf(word) + stems).distinct()
}

/** What stands stuck to the front of an Arabic word: «و», «ف», then «ب», «ل», «ك», then «ال»; «لل» for «ل» + «ال». */
private val ARABIC_PROCLITICS: List<String> = listOf("", "\u0648", "\u0641").flatMap { and ->
    listOf("", "\u0628", "\u0644", "\u0643").flatMap { to -> listOf(and + to, and + to + "\u0627\u0644") } +
        (and + "\u0644\u0644")
}.filter { it.isNotEmpty() }.distinct()

/**
 * One key as it is matched: [words] its words folded ([foldedForKeys]), [text] them without their
 * spaces and `breaks` where in [text] a space stood, of [language] and [sign] (null for a phrase of
 * [SafetyKeys.not]); [whole] its last word whole, not the start of one.
 */
private class SafetyKey(written: String, val language: SafetyLanguage, val sign: SafetySign?) {
    private val folded = foldedForKeys(normalisedWords(written)).trim()
    val words: List<String> = folded.split(' ')
    val text: String = folded.replace(" ", "")

    init {
        require(text.isNotEmpty()) { "a blank key: «$written»" }
    }

    val script: KeyScript = scriptOf(text.first())
    val whole: Boolean = written.endsWith(" ")
    private val breaks: Set<Int> = words.runningFold(0) { at, word -> at + word.length }.drop(1).dropLast(1).toSet()
    private val gap: Int = if (sign in GAPPED && script == KeyScript.WORDS) MAX_GAP else 0

    /**
     * The words of [clause] this key covers when it begins at word [at], or null: each of its words in
     * turn, with no more than [gap] other words between them in all.
     */
    fun wordsAt(clause: ClauseWords, at: Int): IntRange? {
        if (!fits(0, clause.stems[at])) return null
        var end = at
        var spare = gap
        for (part in 1 until words.size) {
            var next = end + 1
            while (next < clause.words.size && !fits(part, clause.stems[next]) && next - end - 1 < spare) next++
            if (next >= clause.words.size || !fits(part, clause.stems[next])) return null
            spare -= next - end - 1
            end = next
        }
        return at..end
    }

    private fun fits(part: Int, stems: List<String>): Boolean {
        val word = words[part]
        val start = part == words.lastIndex && !whole
        return stems.any { if (start) it.startsWith(word) else it == word }
    }

    /**
     * The characters of [clause]'s joined words this key covers when it begins at [at], or null: a
     * space of the message falls inside it only where the key has one, or anywhere for a key of Chinese
     * or Japanese — never inside a phrase of [SafetyKeys.not].
     */
    fun charsAt(clause: ClauseWords, at: Int): IntRange? {
        if (!clause.joined.startsWith(text, at)) return null
        val anywhere = script == KeyScript.CHARS && sign != null
        val split = (1 until text.length).any { clause.chunk[at + it] != clause.chunk[at + it - 1] && it !in breaks }
        return (at until at + text.length).takeIf { anywhere || !split }
    }
}

/** Every key, each German one spelled with "ae", "oe", "ue" too ([spellingsOf]). */
private val SAFETY_KEYS: List<SafetyKey> by lazy {
    SAFETY_WORDS.flatMap { (language, keys) ->
        keys.bySign().flatMap { (sign, written) ->
            written.flatMap { spellingsOf(it, language) }.map { SafetyKey(it, language, sign) }
        }
    }
}

/** The keys matched as words, by the first character of their first word. */
private val WORDS_INDEX: Map<Char, List<SafetyKey>> by lazy {
    SAFETY_KEYS.filter { it.script == KeyScript.WORDS }.groupBy { it.text.first() }
}

/** The keys of Chinese, Japanese and Korean, matched in the words without their spaces, by their first character. */
private val JOINED_INDEX: Map<Char, List<SafetyKey>> by lazy {
    SAFETY_KEYS.filter { it.script != KeyScript.WORDS }.groupBy { it.text.first() }
}

/** A key found in a clause: what it is, and the words — or the joined characters — it covers there. */
private class SafetyHit(val key: SafetyKey, val range: IntRange) {
    /** Whether this phrase of [SafetyKeys.not] covers [hit], counted in the same words. */
    fun holds(hit: SafetyHit): Boolean = (key.script == KeyScript.WORDS) == (hit.key.script == KeyScript.WORDS) &&
        range.first <= hit.range.first && hit.range.last <= range.last
}

/**
 * The language and sign of every key of [SAFETY_WORDS] that stands in [text], clause by clause, less
 * those a phrase of a language's [SafetyKeys.not] holds there.
 */
internal fun safetySignsIn(text: String): Set<Pair<SafetyLanguage, SafetySign>> =
    text.split(CLAUSE_CUTS).flatMapTo(HashSet()) { signsInClause(normalisedWords(it)) }

/** The signs of [normalised] words read as one clause: words whose punctuation is gone already. */
private fun signsInClause(normalised: String): List<Pair<SafetyLanguage, SafetySign>> {
    val clause = ClauseWords(normalised)
    val words = clause.words.indices.flatMap { at ->
        clause.stems[at].flatMap { WORDS_INDEX[it.first()].orEmpty() }.distinct()
            .mapNotNull { key -> key.wordsAt(clause, at)?.let { SafetyHit(key, it) } }
    }
    val chars = clause.joined.indices.flatMap { at ->
        JOINED_INDEX[clause.joined[at]].orEmpty()
            .mapNotNull { key -> key.charsAt(clause, at)?.let { SafetyHit(key, it) } }
    }
    val (nots, keys) = (words + chars).partition { it.key.sign == null }
    return keys.filter { hit -> nots.none { it.holds(hit) } }
        .mapNotNull { hit -> hit.key.sign?.let { hit.key.language to it } }
}

/** Every key and phrase of [SAFETY_WORDS] as it is matched, for `SafetyWordsTest`. */
internal fun safetyKeyTexts(): List<String> = SAFETY_KEYS.map { it.words.joinToString(" ") }

/** Whether words of suicide or self-harm of another of the app's languages stand in [text]. */
internal fun otherLanguageCrisisIn(text: String): Boolean = safetySignsIn(text).any { it.second == SafetySign.CRISIS }

/**
 * The emergencies the other languages' keys name in [text]: someone not responding, breathing, the
 * chest — a word of its pain beside it in the same language, or a heart attack named — a stroke, and
 * vomiting blood. Their questions only: none of them is a reason for the chest's own thread
 * ([chestWordsIn]), which reads English and Russian alone.
 */
internal fun otherLanguageEmergencies(text: String): Set<EmergencyWords> {
    val signs = safetySignsIn(text)
    val named = signs.mapTo(HashSet()) { it.second }
    return setOfNotNull(
        EmergencyWords.UNRESPONSIVE.takeIf { SafetySign.UNRESPONSIVE in named },
        EmergencyWords.BREATHING.takeIf { SafetySign.BREATHING in named },
        EmergencyWords.CHEST.takeIf { chestOf(signs) },
        EmergencyWords.STROKE.takeIf { SafetySign.STROKE in named },
        EmergencyWords.BLOOD.takeIf { SafetySign.BLOOD in named },
    )
}

private fun chestOf(signs: Set<Pair<SafetyLanguage, SafetySign>>): Boolean = signs.any { (language, sign) ->
    sign == SafetySign.HEART_ATTACK ||
        (sign == SafetySign.CHEST && (language to SafetySign.CHEST_PAIN) in signs)
}

/**
 * Whether words of too much of a medicine of another of the app's languages stand in [normalised]
 * words, read as one clause: their punctuation is gone already.
 */
internal fun otherLanguageOverdoseIn(normalised: String): Boolean =
    signsInClause(normalised).any { it.second == SafetySign.OVERDOSE }
