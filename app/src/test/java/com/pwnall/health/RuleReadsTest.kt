package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the alert rules read, and what no model may skip or prefill: every question a rule's
 * [AlertRule.holds] reads — its answers,
 * its values, the facts frozen with it — is the rule's own question or one of its declared
 * [AlertRule.reads], and all of them are owed ([OWED_STEPS]). `WORRY_MOST` is shown at "what does it
 * affect?" and reads "how often?" as well, so that question is owed too.
 *
 * A rule's lambda cannot be read, so each is run over reports that record every question asked of
 * them ([probedReads]). How bad is a field, not a map: it is read where taking it away changes what
 * the rule says. The topic is no question to read — every thread asks it first.
 */
class RuleReadsTest {
    @Test
    fun everyQuestionARuleReadsIsDeclared() {
        val undeclared = SYMPTOM_RULES.associate { rule ->
            rule.id to (PROBED.getValue(rule.id) - rule.reads - rule.shownAt)
        }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, Set<SymptomStep>>(), undeclared)
    }

    /** Nothing declared that the rule does not read: a declaration is a fact about the rule, not a guess. */
    @Test
    fun nothingIsDeclaredThatARuleDoesNotRead() {
        val unread = SYMPTOM_RULES.associate { rule -> rule.id to (rule.reads - PROBED.getValue(rule.id)) }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, Set<SymptomStep>>(), unread)
    }

    /** The probe sees what a rule reads behind its "and"s: the worry's "how often?" behind "what does it affect?". */
    @Test
    fun theProbeSeesBehindAnAnd() {
        assertTrue(SymptomStep.MIND_OFTEN in PROBED.getValue("WORRY_MOST"))
        assertTrue(SymptomStep.PAIN_TYPES in PROBED.getValue("PAIN_LEG_CRAMP"))
        assertTrue(SymptomStep.ONSET in PROBED.getValue("PAIN_RIBS_LONG"))
        assertFalse(SymptomStep.MIND_OFTEN in PROBED.getValue("SLEEP_MONTHS"))
    }

    /** The closure: every question any rule is shown at or reads is owed, in every topic it holds for. */
    @Test
    fun everyQuestionARuleReadsIsOwed() {
        SYMPTOM_RULES.forEach { rule ->
            val read = rule.reads + rule.shownAt
            assertTrue(rule.id, OWED_STEPS.containsAll(read))
            val topics = rule.topics.ifEmpty { Symptom.entries.toSet() }
            topics.forEach { topic -> assertTrue("${rule.id} in $topic", owedSteps(topic).containsAll(read)) }
        }
        // For example, the worry's "how often?" is read by WORRY_MOST.
        val worryMost = SYMPTOM_RULES.single { it.id == "WORRY_MOST" }
        assertEquals(SymptomStep.AFFECTS, worryMost.shownAt)
        assertTrue(SymptomStep.MIND_OFTEN in worryMost.reads)
        assertTrue(SymptomStep.MIND_OFTEN in owedSteps(Symptom.WORRY))
        assertTrue(SymptomStep.MIND_OFTEN in OWED_STEPS)
    }

    /**
     * The decider prefills only what no rule of the thread's topic reads: a pain's start, its kinds and
     * "the same as before?" are read by the pain's rules, so only when it was noticed is a pain's to
     * prefill; a dose's time and amount and a course's dose and times are no rule's.
     */
    @Test
    fun nothingOwedIsPrefilled() {
        Symptom.entries.forEach { topic ->
            PREFILL_STEPS.forEach { step ->
                if (step in owedSteps(topic)) assertFalse("$topic $step", prefillable(topic, step))
            }
        }
        assertFalse(prefillable(Symptom.PAIN, SymptomStep.ONSET))
        assertFalse(prefillable(Symptom.PAIN, SymptomStep.PAIN_TYPES))
        assertFalse(prefillable(Symptom.PAIN, SymptomStep.PAIN_SAME))
        assertTrue(prefillable(Symptom.PAIN, SymptomStep.PAIN_NOTICED))
        assertTrue(prefillable(Symptom.DOSE, SymptomStep.MED_WHEN))
        assertTrue(prefillable(Symptom.DOSE, SymptomStep.MED_AMOUNT))
        assertTrue(prefillable(Symptom.COURSE, SymptomStep.COURSE_DOSE))
        assertTrue(prefillable(Symptom.COURSE, SymptomStep.COURSE_TIMES))
        assertFalse(prefillable(null, SymptomStep.PAIN_NOTICED))
        assertFalse(prefillable(Symptom.PAIN, SymptomStep.PAIN_AREA))
    }
}

/** What each rule was seen to read, by its id: worked out once for the whole class. */
private val PROBED: Map<String, Set<SymptomStep>> by lazy { SYMPTOM_RULES.associate { it.id to probedReads(it) } }

private const val SAMPLES = 1500

/** Every area on every side, so that every family's rules read on. */
private val ALL_AREAS: String = RegionSide.ALL.joinToString(",") { it.code }

private val VALUES: List<String?> =
    listOf(null, "1", "2", "3", "4", "5", "7", "8", "10", "30", "600", ALL_AREAS, "KNEE:R", "UNDER_RIBS:R", "C1")
private val FACTS: List<String?> = listOf(null, "1", SIGNS_TCA, SIGNS_OPIOID, SIGNS_ANY, SIGNS_NONE, "30")
private val SEVERITIES: List<SymptomSeverity?> = SymptomSeverity.entries + null

/**
 * The questions [rule] was seen to read: with every question answered by every option, or by each
 * option alone, the areas of every family marked; then over [SAMPLES] reports mixed at random — the
 * same each run — each question unanswered, answered by every option or by one, its value none, a
 * number or areas, so that a rule's every "and" is reached by some of them.
 */
private fun probedReads(rule: AlertRule): Set<SymptomStep> {
    val seen = HashSet<SymptomStep>()
    val random = Random(rule.id.hashCode())
    val topics: List<Symptom?> = rule.topics.toList().ifEmpty { Symptom.entries + null }
    val swept: List<Symptom?> = rule.topics.toList().ifEmpty { listOf(null, Symptom.DOSE, Symptom.COURSE) }
    val whole = listOf(SymptomOption.entries) + SymptomOption.entries.map { listOf(it) }
    for (topic in swept) {
        for (options in whole) probe(rule, RuleProbing(topic, null, { options }, { ALL_AREAS }, { "1" }), seen)
    }
    repeat(SAMPLES) {
        val answers = SymptomStep.entries.associateWith { answerAtRandom(random) }
        val values = SymptomStep.entries.associateWith { VALUES.random(random) }
        val fact = FACTS.random(random)
        probe(
            rule,
            RuleProbing(topics.random(random), SEVERITIES.random(random), answers::get, values::get, {
                fact
            }),
            seen,
        )
    }
    return seen
}

private fun answerAtRandom(random: Random): List<SymptomOption>? = when (random.nextInt(3)) {
    0 -> null
    1 -> SymptomOption.entries
    else -> listOf(SymptomOption.entries.random(random))
}

/** One report to probe a rule with: its topic, how bad, and each question's answer, value and facts. */
private class RuleProbing(
    val topic: Symptom?,
    val severity: SymptomSeverity?,
    val answers: (SymptomStep) -> List<SymptomOption>?,
    val values: (SymptomStep) -> String?,
    val facts: (String) -> String?,
)

private fun probe(rule: AlertRule, probing: RuleProbing, seen: MutableSet<SymptomStep>) {
    val report = SymptomReport(
        symptom = probing.topic,
        answers = RuleProbe(seen, probing.answers),
        severity = probing.severity,
        values = RuleProbe(seen, probing.values),
        meta = RuleFactProbe(seen, probing.facts),
    )
    val held = runCatching { rule.holds(report) }.getOrNull()
    if (probing.severity == null) return
    // How bad is no map's: it is read when taking it away changes what the rule says.
    val without = runCatching { rule.holds(report.copy(severity = null)) }.getOrNull()
    if (held != without) seen += SymptomStep.SEVERITY
}

/** A rule's questions as the report it reads hands them over: every one asked for is recorded. */
private class RuleProbe<V : Any>(
    val seen: MutableSet<SymptomStep>,
    val value: (SymptomStep) -> V?,
) : Map<SymptomStep, V> {
    private fun all(): Map<SymptomStep, V> = SymptomStep.entries.mapNotNull { step ->
        value(step)?.let { step to it }
    }.toMap()

    override val entries: Set<Map.Entry<SymptomStep, V>> get() = all().also { seen += it.keys }.entries
    override val keys: Set<SymptomStep> get() = all().keys.also { seen += it }
    override val size: Int get() = all().size
    override val values: Collection<V> get() = all().also { seen += it.keys }.values

    override fun isEmpty(): Boolean = all().isEmpty()

    override fun containsKey(key: SymptomStep): Boolean {
        seen += key
        return value(key) != null
    }

    override fun containsValue(value: V): Boolean = all().also { seen += it.keys }.containsValue(value)

    override fun get(key: SymptomStep): V? {
        seen += key
        return value(key)
    }
}

/** The facts frozen with a question ("CRISIS.tca", "MED_SIGNS.v"): reading one reads its question. */
private class RuleFactProbe(val seen: MutableSet<SymptomStep>, val value: (String) -> String?) : Map<String, String> {
    private fun note(key: String) {
        SymptomStep.entries.firstOrNull { it.name == key.substringBefore('.') }?.let(seen::add)
    }

    override val entries: Set<Map.Entry<String, String>> get() = emptyMap<String, String>().entries
    override val keys: Set<String> get() = emptySet()
    override val size: Int get() = 0
    override val values: Collection<String> get() = emptyList()

    override fun isEmpty(): Boolean = true

    override fun containsKey(key: String): Boolean {
        note(key)
        return value(key) != null
    }

    override fun containsValue(value: String): Boolean = false

    override fun get(key: String): String? {
        note(key)
        return value(key)
    }
}
