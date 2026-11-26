package com.pwnall.health

// The rules and the model's stored readings, read together: what the router
// reads of a message through [MentionReader], and what a pain thread reads of its words. The rules'
// own reading comes first and whole; a reading adds after it — its topics where the rules named none,
// or the ones it is sure of beside theirs; someone else where it says so; feeling unwell of oneself —
// and the pain it read where the rules read none, or what it told of the same pain the rules read. It
// never takes anything away, and with no reading kept it adds nothing at all.

/**
 * [this] — the rules' reading of [messages] — with what their stored readings add: the
 * topics they propose, after the rules' own and in the readings' order, where the rules named no topic,
 * or beside the rules' only those a reading is sure of ([ChatRead.add]) and only of a topic the rules
 * did not name; someone else, a child, where a reading says so; and feeling unwell where a reading of
 * the person themselves says so with nobody else in the words. The rules' safety reading and whether
 * any word was read ([WordsFound.language]) stay theirs alone.
 */
internal fun WordsFound.withReadings(messages: List<ChatTurn.Wrote>): WordsFound {
    var start = 0
    val reads = messages.mapNotNull { message ->
        val at = start
        start += message.text.length + 1
        message.read?.takeIf { it.routes }?.let { at to it }
    }
    if (reads.isEmpty()) return this
    val ruled = mentions.mapTo(HashSet()) { it.mention.topic }
    val keys = mentions.mapTo(HashSet()) { it.mention.key }
    val added = reads.flatMap { (at, read) ->
        read.mentions(at).filter { mention ->
            val topic = mention.mention.topic
            topic !in ruled && (ruled.isEmpty() || topic in read.add)
        }
    }.filter { keys.add(it.mention.key) }
    val others = reads.mapNotNull { it.second.about }
    return copy(
        mentions = mentions + added,
        someoneElse = someoneElse || others.isNotEmpty(),
        child = child || ReadAbout.CHILD in others,
        unwell = unwell || (reads.any { it.second.unwell } && !someoneElse && others.isEmpty()),
    )
}

/**
 * The topics [this] reading proposes, as the mentions the rules would make of them, at [at]: a pain
 * with the places it told ([toldPain]), a dose or a course of the medicine it named with the role
 * that fits, any other topic by itself.
 */
private fun ChatRead.mentions(at: Int): List<WordMention> = topics.map { topic ->
    when (topic) {
        Symptom.PAIN -> toldPain(at) ?: SymptomWordMention(at, TopicMention(topic))
        Symptom.DOSE, Symptom.COURSE -> medicineMention(at, topic)
        else -> SymptomWordMention(at, TopicMention(topic))
    }
}

/** What makes a medicine named a dose taken, and what a course. */
private val DOSE_ROLES = setOf(ReadRole.TAKEN, ReadRole.MISSED, ReadRole.REGULAR)
private val COURSE_ROLES = setOf(ReadRole.PRESCRIBED, ReadRole.RECIPE, ReadRole.STOPPED, ReadRole.REGULAR)

/** A dose or a course of the first medicine [this] reading named in that role, keyed as the rules key it. */
private fun ChatRead.medicineMention(at: Int, topic: Symptom): WordMention {
    val roles = if (topic == Symptom.DOSE) DOSE_ROLES else COURSE_ROLES
    val named = told.medicines.firstOrNull { medicine -> medicine.roles.any { it in roles } }
    val written = named?.let { MessageWords(normalisedWords(it.name)).tokens.joinToString(" ") }.orEmpty()
    val known = written.takeIf { it.isNotEmpty() }?.let(::knownMedicineOf)
    // A dose told missed and not taken is worded so on its chip, as the rules word theirs ([TopicMention.missed]).
    val missed = topic == Symptom.DOSE && written.isNotEmpty() &&
        named?.roles.orEmpty().let { ReadRole.MISSED in it && ReadRole.TAKEN !in it }
    return MedicineWordMention(at, TopicMention(topic, written, missed), known)
}

/**
 * The pain [this] reading read, as a mention of the first family it named (the rules make one a
 * family): its places of that family with their sides — chips for a place, the map framed on a family
 * named without one, the head's chips, the chest's questions first ([painProposal]) — and everything
 * it told of the pain, as marks and the ruler's rest. Null unless it proposes a pain.
 */
internal fun ChatRead.toldPain(at: Int): PainWordMention? {
    if (!routes || Symptom.PAIN !in topics) return null
    val hits = told.places.mapNotNull { it.hit(at) }
    val family = hits.firstOrNull()?.family
    return painMention(at, family, hits.filter { it.family == family }, listOf(told.painValues()))
}

/** What a reading told of a pain, as the rules' words of one clause give it ([PainValues]). */
private fun ReadTold.painValues(): PainValues = PainValues(
    types = types.toSet(),
    onsets = setOfNotNull(onset),
    noticed = setOfNotNull(noticed),
    still = status == SymptomOption.STILL_HURTS,
    gone = status == SymptomOption.GONE,
    same = same == SymptomOption.PAIN_SAME_YES,
    new = same == SymptomOption.PAIN_NEW,
    sudden = ReadIntensity.SUDDEN in intensity,
    severe = ReadIntensity.SEVERE in intensity,
    mild = ReadIntensity.MILD in intensity,
    signs = signs.toSet(),
    numbers = setOfNotNull(nrs),
)

/** The entry a place a reading told is kept under, never one of the body lexicon's. */
private const val READ_ENTRY = "read"

/**
 * A place a reading told, as the rules' area word of it would be ([PainHit]): its area on the side
 * said — both sides, none said, for a paired one — seen on the plate that shows it, or only in the
 * list where no plate does; a family named without an area is the map framed there.
 */
private fun ReadPlace.hit(at: Int): PainHit? {
    val region = area?.let { name -> PainRegion.entries.firstOrNull { it.name == name } }
    val bodyFamily = region?.let { bodyFamilies(it).firstOrNull() } ?: family ?: return null
    val named = sidesNamed()
    val plate = region?.let(PLATES::get)
    return PainHit(
        entry = READ_ENTRY,
        family = bodyFamily,
        at = at,
        areas = region?.let { areasOn(it, named) }.orEmpty(),
        named = named,
        map = region == null,
        view = plate ?: if (bodyFamily == FAMILY_BACK) PainView.BACK else PainView.FRONT,
        listOnly = region != null && plate == null,
    )
}

/** The sides [this] place was told on: one, both, or none said. */
private fun ReadPlace.sidesNamed(): Set<BodySide> = when (side) {
    SIDE_LEFT -> setOf(BodySide.LEFT)
    SIDE_RIGHT -> setOf(BodySide.RIGHT)
    SIDE_BOTH -> setOf(BodySide.LEFT, BodySide.RIGHT)
    else -> emptySet()
}

/** [region] on the sides [named]: its centre when it has no sides, both of them when none was said. */
private fun areasOn(region: PainRegion, named: Set<BodySide>): List<RegionSide> = when {
    !region.paired -> listOf(RegionSide(region, BodySide.CENTRE))
    named.isEmpty() -> listOf(RegionSide(region, BodySide.LEFT), RegionSide(region, BodySide.RIGHT))
    else -> named.map { RegionSide(region, it) }
}

private const val FAMILY_BACK = "BACK"

/**
 * The plate each area is drawn on in the current map — the front where both draw it; an area on
 * neither, the list alone holds.
 */
private val PLATES: Map<PainRegion, PainView> by lazy {
    PainView.entries.reversed().flatMap { view ->
        sidePatches(view, PainMapVersion.CURRENT).map { it.area.region to view }
    }.toMap()
}
