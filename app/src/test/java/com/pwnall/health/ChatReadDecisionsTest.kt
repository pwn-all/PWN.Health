package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * From a reading to the decisions the chat keeps: the flags over
 * their thresholds as their steps, in the script's order, whatever else the reading says; the topics
 * worth a chip, the likeliest first, and those sure enough to add; a mood's kind as its topics; unwell
 * only of oneself; what the spans tell, by the calibration's own certainties and the chat's day.
 */
class ChatReadDecisionsTest {
    private val day = CHAT_TODAY // a Monday

    @Test
    fun theFlagsAddTheirStepsInTheScriptsOrderWhateverElse() {
        val all = readingOf(flags = ReaderSafety.entries.toSet(), topics = mapOf(Symptom.FEVER to 0.9))
        assertEquals(READ_SAFETY_STEPS, all.decided(day, past = false).safety)
        // A reading that abstains keeps its flags, and nothing else.
        val abstained = readingOf(
            flags = setOf(ReaderSafety.CRISIS),
            oos = 0.9,
            topics = mapOf(Symptom.FEVER to 0.9),
        ).decided(day, false)
        assertEquals(
            ChatRead(TEST_MODEL, why = ChatReadOutcome.ABSTAIN, safety = listOf(SymptomStep.CRISIS_NOW)),
            abstained,
        )
        assertTrue(readingOf().decided(day, false).safety.isEmpty())
    }

    @Test
    fun theTopicsComeLikeliestFirstAndOnlySureOnesAreAdded() {
        val read = readingOf(
            topics = mapOf(Symptom.FEVER to 0.6, Symptom.PAIN to 0.95, Symptom.NAUSEA to 0.3),
            added = setOf(Symptom.PAIN, Symptom.NAUSEA),
        ).decided(day, false)
        assertEquals(listOf(Symptom.PAIN, Symptom.FEVER), read.topics)
        assertEquals(listOf(Symptom.PAIN), read.add)
        val bad = readingOf(
            topics = mapOf(Symptom.TIREDNESS to 0.7),
            mood = ReaderLabel("BAD", 0.8),
        ).decided(day, false)
        assertEquals(listOf(Symptom.TIREDNESS, Symptom.MOOD, Symptom.LOW_MOOD), bad.topics)
        val good = readingOf(
            topics = mapOf(Symptom.TIREDNESS to 0.7),
            mood = ReaderLabel("GOOD", 0.8),
        ).decided(day, false)
        assertEquals(listOf(Symptom.TIREDNESS, Symptom.MOOD), good.topics)
    }

    @Test
    fun unwellCountsOnlyOfOneselfAndNeverBesideTheChest() {
        assertTrue(readingOf(unwell = 0.9).decided(day, false).unwell)
        assertFalse(readingOf(unwell = 0.9, about = "CHILD").decided(day, false).unwell)
        assertEquals(ReadAbout.CHILD, readingOf(unwell = 0.9, about = "CHILD").decided(day, false).about)
        assertFalse(readingOf(unwell = 0.9, flags = setOf(ReaderSafety.CHEST)).decided(day, false).unwell)
        assertNull(readingOf(unwell = 0.9).decided(day, false).about)
    }

    /** A place with its side, at the calibration's certainty for a side; a negated one is none; a side said apart. */
    @Test
    fun thePlacesComeWithTheirSides() {
        fun placeSpan(area: String, side: String?, negated: Double = 0.0) = spanOf(
            ReaderSpanKind.PLACE,
            "колено",
            "place_area" to sure(area),
            "place_family" to sure("KNEE"),
            "side" to (side?.let { mapOf(it to 0.9, "NONE" to 0.1) } ?: sure("NONE")),
            "negated" to mapOf("NEGATED" to negated),
        )
        val pain = mapOf(Symptom.PAIN to 0.9)
        val right = readingOf(topics = pain, spans = listOf(placeSpan("KNEE", "R"))).decided(day, false)
        assertEquals(listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)), right.told.places)
        val unsure = placeSpan("KNEE", null).copy(
            attributes =
            placeSpan("KNEE", null).attributes + ("side" to mapOf("R" to 0.6, "NONE" to 0.4)),
        )
        assertEquals(
            listOf(ReadPlace("KNEE", "KNEE", null)),
            readingOf(topics = pain, spans = listOf(unsure)).decided(day, false).told.places,
        )
        val negated = readingOf(
            topics = pain,
            spans = listOf(placeSpan("KNEE", "R", negated = 0.9)),
        ).decided(day, false)
        assertTrue(negated.told.places.isEmpty())
        val apart = spanOf(ReaderSpanKind.SIDE, "слева", "side" to mapOf("L" to 0.95, "NONE" to 0.05))
        val left = readingOf(topics = pain, spans = listOf(placeSpan("KNEE", null), apart)).decided(day, false)
        assertEquals(listOf(ReadPlace("KNEE", "KNEE", SIDE_LEFT)), left.told.places)
        // An area of no paired place takes no side; one the current map lacks is the family's alone.
        val neck = spanOf(ReaderSpanKind.PLACE, "шея", "place_area" to sure("NECK"), "side" to mapOf("R" to 0.95))
        assertEquals(
            listOf(ReadPlace("NECK", "NECK", null)),
            readingOf(topics = pain, spans = listOf(neck)).decided(day, false).told.places,
        )
        val old =
            spanOf(ReaderSpanKind.PLACE, "живот", "place_area" to sure("ABDOMEN"), "place_family" to sure("BELLY"))
        assertEquals(
            listOf(ReadPlace("BELLY", null, null)),
            readingOf(topics = pain, spans = listOf(old)).decided(day, false).told.places,
        )
    }

    /**
     * One number out of ten, sure of itself and of being one; one start, counted from the chat's day;
     * none for an earlier day.
     */
    @Test
    fun numbersAndTimesAreCountedAsTheRulesCountThem() {
        // A reading of a pain: one that abstains keeps its flags alone, and tells nothing.
        fun toldBy(spans: List<ReaderSpan>, past: Boolean = false) =
            readingOf(topics = mapOf(Symptom.PAIN to 0.9), spans = spans).decided(day, past).told
        fun number(value: String, nrs: Double = 0.95, kind: String = "NRS") = spanOf(
            ReaderSpanKind.NUMBER,
            value,
            "number_class" to mapOf(kind to 0.9, "OTHER" to 0.1),
            "nrs" to mapOf(value to nrs, "NONE" to 1 - nrs),
        )
        assertEquals(7, toldBy(listOf(number("7"))).nrs)
        assertNull(toldBy(listOf(number("7", nrs = 0.8))).nrs)
        assertNull(toldBy(listOf(number("7"), number("8"))).nrs)
        assertNull(toldBy(listOf(number("38", kind = "TEMPERATURE"))).nrs)
        fun time(expr: String, count: String? = null, weekday: String? = null) = spanOf(
            ReaderSpanKind.TIME,
            "вчера",
            *listOfNotNull(
                "time_expr" to sure(expr),
                count?.let { "time_count" to sure(it) },
                weekday?.let { "weekday" to sure(it) },
            ).toTypedArray(),
        )
        mapOf(
            time("YESTERDAY") to SymptomOption.STARTED_YESTERDAY,
            time("DAYS", "3") to SymptomOption.STARTED_2_3_DAYS,
            time("DAYS", "4") to null,
            time("WEEKS", "1") to SymptomOption.STARTED_A_WEEK,
            time("MONTHS", "6") to SymptomOption.STARTED_LONGER,
            time("WEEKDAY", weekday = "SAT") to SymptomOption.STARTED_2_3_DAYS,
            time("WEEKDAY", weekday = "SUN") to SymptomOption.STARTED_YESTERDAY,
            time("WEEKEND") to null,
        ).forEach { (span, onset) -> assertEquals(span.attributes.toString(), onset, toldBy(listOf(span)).onset) }
        assertNull(toldBy(listOf(time("YESTERDAY")), past = true).onset)
        assertNull(toldBy(listOf(time("YESTERDAY"), time("NOW"))).onset)
        val clock = spanOf(ReaderSpanKind.TIME, "в 8:30", "time_expr" to sure("CLOCK"))
        assertEquals(listOf(510, 1230), toldBy(listOf(clock)).clocks)
        assertEquals(listOf(510, 1230), toldBy(listOf(clock), past = true).clocks)
    }

    @Test
    fun medicinesComeAsWrittenWithWhatWasSaidOfThem() {
        val taken = spanOf(
            ReaderSpanKind.MEDICINE,
            "нурофен ",
            "medicine_role" to mapOf("TAKEN" to 0.9, "MISSED" to 0.2),
            "negated" to mapOf("NEGATED" to 0.0),
        )
        val not = spanOf(ReaderSpanKind.MEDICINE, "аспирин", "negated" to mapOf("NEGATED" to 0.9))
        val amount = spanOf(ReaderSpanKind.AMOUNT, "2 таблетки")
        val read = readingOf(
            topics = mapOf(Symptom.DOSE to 0.9),
            spans = listOf(taken, not, amount),
        ).decided(day, false)
        assertEquals(listOf(ReadMedicine("нурофен", listOf(ReadRole.TAKEN))), read.told.medicines)
        assertEquals(listOf("2 таблетки"), read.told.amounts)
    }

    /**
     * A medicine named twice keeps every role said of it: "X every morning… today I
     * missed X" is X taken regularly and missed, so the dose's "I missed a dose" is marked.
     */
    @Test
    fun aMedicineNamedTwiceKeepsEveryRole() {
        fun named(text: String, role: String) =
            spanOf(ReaderSpanKind.MEDICINE, text, "medicine_role" to mapOf(role to 0.9))
        val read = readingOf(
            topics = mapOf(Symptom.DOSE to 0.9),
            spans = listOf(named("Сертралин", "REGULAR"), named("сертралин", "MISSED")),
        ).decided(day, false)
        assertEquals(listOf(ReadMedicine("Сертралин", listOf(ReadRole.REGULAR, ReadRole.MISSED))), read.told.medicines)
        // Across messages as well: what the thread's readings told, every message's together.
        val first = chatRead { copy(told = ReadTold(medicines = listOf(ReadMedicine("X", listOf(ReadRole.REGULAR))))) }
        val second = chatRead { copy(told = ReadTold(medicines = listOf(ReadMedicine("x", listOf(ReadRole.MISSED))))) }
        assertEquals(
            listOf(ReadMedicine("X", listOf(ReadRole.REGULAR, ReadRole.MISSED))),
            toldIn(listOf(ChatTurn.Wrote("a", first), ChatTurn.Wrote("b", second))).medicines,
        )
    }

    /**
     * A chest pain the reading proposes, or a pain it places in the chest, asks the chest's question
     * as the rules' words of one do, flag or no flag — and then is no "unwell"; a pain
     * elsewhere asks nothing of the chest.
     */
    @Test
    fun aChestTheReadingProposesAsksTheChestsQuestion() {
        val topic = readingOf(topics = mapOf(Symptom.CHEST_PAIN to 0.7), unwell = 0.9).decided(day, false)
        assertEquals(listOf(SymptomStep.CHEST_NOW), topic.safety)
        assertFalse(topic.unwell)
        fun place(family: String) = spanOf(ReaderSpanKind.PLACE, "грудь", "place_family" to sure(family))
        val placed = readingOf(topics = mapOf(Symptom.PAIN to 0.9), spans = listOf(place("CHEST"))).decided(day, false)
        assertEquals(listOf(SymptomStep.CHEST_NOW), placed.safety)
        val knee = readingOf(topics = mapOf(Symptom.PAIN to 0.9), spans = listOf(place("KNEE"))).decided(day, false)
        assertTrue(knee.safety.isEmpty())
        // Beside other flags, in the script's order.
        val both =
            readingOf(flags = setOf(ReaderSafety.BLOOD, ReaderSafety.CRISIS), topics = mapOf(Symptom.CHEST_PAIN to 0.7))
        assertEquals(
            listOf(SymptomStep.CRISIS_NOW, SymptomStep.CHEST_NOW, SymptomStep.BLOOD),
            both.decided(day, false).safety,
        )
    }

    /**
     * A message in a language the model has not passed its exam on keeps its flags alone, as one it
     * abstains on: no topic, place, medicine or anything
     * else it told counts there — so a model shipped before its exam asks its safety questions and
     * routes nothing else.
     */
    @Test
    fun aLanguageNotExaminedKeepsOnlyTheFlags() {
        val reading = readingOf(
            flags = setOf(ReaderSafety.BREATHING),
            topics = mapOf(Symptom.FEVER to 0.9),
            unwell = 0.9,
            spans = listOf(spanOf(ReaderSpanKind.MEDICINE, "aspirin")),
        )
        assertEquals(listOf(Symptom.FEVER), reading.decided(day, false).topics)
        val unexamined = reading.copy(examined = false).decided(day, false)
        assertEquals(
            ChatRead(TEST_MODEL, why = ChatReadOutcome.UNEXAMINED, safety = listOf(SymptomStep.BREATH_NOW)),
            unexamined,
        )
        // The language passed is the model's own, by its package; none named, none passed.
        val manifest = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        assertTrue(manifest.languages.isEmpty())
        val named = JSONObject(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
            .put("languages", JSONArray(listOf("ru", "en", "", 7)))
        assertEquals(setOf("ru", "en"), ChatModelManifest.parse(named.toString()).languages)
    }

    /**
     * A reading that leaves the exam unsaid is one no exam vouches for: its flags alone count, as a
     * language not examined — what an engine of another make reads, unless
     * it says otherwise.
     */
    @Test
    fun aReadingNoExamVouchesForAddsItsFlagsAlone() {
        val sure = readingOf(flags = setOf(ReaderSafety.BREATHING), topics = mapOf(Symptom.FEVER to 0.9))
        val unsaid = with(sure) {
            ChatReading(
                model = model,
                lang = lang,
                topics = topics,
                unwell = unwell,
                safety = safety,
                about = about,
                question = question,
                smallTalk = smallTalk,
                mood = mood,
                oos = oos,
                abstain = abstain,
                spans = spans,
                heads = heads,
                rules = rules,
                tokens = tokens,
                windows = windows,
            )
        }
        assertEquals(
            ChatRead(TEST_MODEL, why = ChatReadOutcome.UNEXAMINED, safety = listOf(SymptomStep.BREATH_NOW)),
            unsaid.decided(day, false),
        )
        assertEquals(listOf(Symptom.FEVER), sure.decided(day, false).topics)
    }

    @Test
    fun thePainHeadsMarkTheirOptions() {
        val read = readingOf(
            topics = mapOf(Symptom.PAIN to 0.9),
            heads = mapOf(
                "pain_types" to mapOf("SHARP" to 0.9, "ACHING" to 0.7, "BURNING" to 0.6, "STIFF" to 0.1),
                "pain_noticed" to sure("NOTICED_ON_WAKING"),
                "pain_status" to mapOf("GONE" to 0.8, "NONE" to 0.2),
                "pain_same" to mapOf("NONE" to 0.9, "PAIN_NEW" to 0.1),
                "pain_signs" to mapOf("SUDDEN_SEVERE" to 0.7, "CRACK" to 0.2),
                "painkiller" to mapOf("PAINKILLER_TODAY" to 0.8),
            ),
        ).decided(day, false)
        assertEquals(listOf(PainType.SHARP, PainType.ACHING), read.told.types)
        assertEquals(SymptomOption.NOTICED_ON_WAKING, read.told.noticed)
        assertEquals(SymptomOption.GONE, read.told.status)
        assertNull(read.told.same)
        assertEquals(listOf(SymptomOption.SUDDEN_SEVERE), read.told.signs)
        assertTrue(read.told.painkiller)
    }
}
