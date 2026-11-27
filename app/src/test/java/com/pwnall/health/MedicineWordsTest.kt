package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in list of medicines and the words around them: names read in their Russian
 * cases, the longest name first, a word that only looks like one read as nothing; a dose taken, a
 * prescription and too much told apart from the words that only resemble them.
 */
class MedicineWordsTest {
    private fun inns(text: String) = medicinesIn(text).map { it.second.id }

    @Test
    fun namesAreReadInTheirCasesAndTheLongestFirst() {
        assertEquals(listOf("ibuprofen"), inns("выпила нурофена"))
        assertEquals(listOf("sertraline"), inns("лечусь сертралином"))
        assertEquals(listOf("ibuprofen+codeine"), inns("took nurofen plus at lunch"))
        assertEquals(listOf("ibuprofen+codeine"), inns("нурофен плюс"))
        assertEquals(listOf("escitalopram"), inns("эсциталопрам"))
        assertEquals("escitalopram", knownMedicineOf("Эсциталопрам")?.id)
        assertEquals("citalopram", knownMedicineOf("Ципрамил")?.id)
        assertEquals(emptyList<String>(), inns("опять мигрень"))
        assertEquals(listOf("paracetamol", "ibuprofen"), inns("paracetamol, then ibuprofen, then paracetamol again"))
    }

    @Test
    fun classesOrderOnlyTheChips() {
        assertEquals(MedicineClass.ANTIDEPRESSANT, medicineClassOf("nortriptyline"))
        assertEquals(MedicineClass.ANTIDEPRESSANT, medicineClassOf("Аурорикс"))
        assertEquals(MedicineClass.PAINKILLER, medicineClassOf("пенталгин"))
        assertEquals(MedicineClass.OTHER, medicineClassOf("Vitamin D"))
        assertTrue(sameMedicine("Золофт", "sertraline"))
        assertFalse(sameMedicine("escitalopram", "citalopram"))
        assertNull(knownMedicineOf("Microgynon"))
        // A heart aspirin that says aspirin is aspirin, its notes on mixing with it; one that does not, off the list.
        assertEquals("aspirin", knownMedicineOf("Aspirin Cardio")?.id)
        assertNull(knownMedicineOf("Кардиомагнил"))
    }

    @Test
    fun aDoseAPrescriptionAndTooMuchAreToldApartFromWordsThatLookLikeThem() {
        assertTrue(doseWordsIn("took a paracetamol"))
        assertTrue(doseWordsIn("выпил таблетку"))
        assertFalse(doseWordsIn("paracetamol is in the cupboard"))
        assertTrue(courseWordsIn("врач назначил сертралин"))
        assertTrue(courseWordsIn("got a prescription for sertraline"))
        assertFalse(courseWordsIn("рецепт пирога"))
        assertTrue(missedWordsIn("forgot my sertraline"))
        assertTrue(stopWordsIn("бросила пить сертралин"))
        assertTrue(regularWordsIn("I am on sertraline"))
        assertFalse(overdoseIn("I ate too much"))
        assertTrue(overdoseIn("took too many paracetamol"))
        assertTrue(overdoseIn("выпил 4 таблетки парацетамола"))
        assertFalse(overdoseIn("I take it twice a day"))
        assertTrue(overdoseIn("I took my sertraline twice"))
        assertFalse(overdoseIn("took 2 tablets of paracetamol"))
        assertTrue(alcoholWordsIn("пару бокалов вина и пиво"))
        assertTrue(topicalWordsIn("sprayed it twice"))
    }

    /** Too much told without a name, by a count, or with the phrases people use: each one asks "more than it says?". */
    @Test
    fun tooMuchIsReadWhereverItIsSaid() {
        // Words that are about a medicine by themselves, and a word of too much beside a word of taking.
        assertTrue(overdoseIn("I accidentally took an overdose"))
        assertTrue(overdoseIn("overdosed last night"))
        assertTrue(overdoseIn("наглоталась таблеток"))
        assertTrue(overdoseIn("кажется, передозировка"))
        assertTrue(overdoseIn("I took too many"))
        assertTrue(overdoseIn("выпила слишком много"))
        // A count of a medicine, of the person's pills, past "of my" and a strength.
        assertTrue(overdoseIn("I took 20 paracetamol"))
        assertTrue(overdoseIn("took 6 of my pills"))
        assertTrue(overdoseIn("took 10 of my sertraline tablets"))
        assertTrue(overdoseIn("выпила 10 сертралина"))
        assertTrue(overdoseIn("took 3 x 500 mg paracetamol"))
        // The phrases of a whole packet.
        listOf(
            "took the whole bottle of pills",
            "I took all my pills",
            "выпила целую пачку таблеток",
            "выпила все таблетки",
            "it was more than the box says",
            "took more than the packet says",
            // Phrases easy to miss: a count after "had", a packet taken, all of them, one
            // extra of a course.
            "I've had 16 nurofen",
            "съела 5 таблеток парацетамола",
            "выпила пачку таблеток",
            "took a box of paracetamol",
            "I took all my meds",
            "выпила все свои таблетки",
            "took an extra amitriptyline",
        ).forEach { assertTrue(it, overdoseIn(it)) }
        // A strength, a time, a course's count a day, a double coffee, a dose a person is on: none of them.
        listOf(
            "I take 50 sertraline",
            "took 50 mg sertraline",
            "I take 3 tablets a day",
            "at 8:30 I took paracetamol",
            "I took paracetamol at 8",
            "вчера приняла парацетамол в 8",
            "выпила парацетамол в 8 вчера парацетамол",
            "I took paracetamol yesterday at 8 PM paracetamol",
            "выпил двойной эспрессо",
            "took 2 tablets of paracetamol",
            "took paracetamol 3 hours ago",
            "I had 2 paracetamol",
            "bought a box of paracetamol",
            "выпила банку пива",
            "took a Panadol Extra",
            "I had 3 coffees",
        ).forEach { assertFalse(it, overdoseIn(it)) }
    }

    /**
     * A count written as a word is a count past twelve too: "took twenty paracetamol" asks as "took 20"
     * does. Minutes are still no tablets, and a figure a person is on still a strength.
     */
    @Test
    fun aCountInWordsIsReadPastTwelve() {
        listOf(
            "I took twenty paracetamol",
            "выпила двадцать таблеток парацетамола",
            "took a dozen pills",
        ).forEach { assertTrue(it, overdoseIn(it)) }
        listOf("twenty minutes ago I took paracetamol", "I take fifty sertraline").forEach {
            assertFalse(it, overdoseIn(it))
        }
    }

    /**
     * Russian puts a count after «около» or «больше» in the genitive, which is a count all the same: «выпила
     * около двадцати таблеток». The hour is told in that case too, so such a count is one only before
     * what stands in the genitive itself — the tablets, right after it or a word on, or a medicine's name
     * right after it; a count a day is still how a course is told. A count said after its tablets, which
     * is how Russian says "about", is read beside a word of taking.
     */
    @Test
    fun aRussianCountInTheGenitiveIsReadBeforeTablets() {
        listOf(
            "выпила около двадцати таблеток",
            "съел больше десяти таблеток парацетамола",
            "проглотил не меньше пяти капсул",
            "выпила десяток таблеток",
            "выпила около двадцати белых таблеток",
            "около двадцати парацетамола",
            "выпила около двадцати парацетамола",
            "выпила таблеток двадцать",
            "выпила таблеток 20 штук",
        ).forEach { assertTrue(it, overdoseIn(it)) }
        listOf(
            "после шести выпила таблетку парацетамола",
            "около пяти приняла парацетамол",
            "около пяти парацетамол выпила",
            "принимаю до трех таблеток в день",
            "выпила таблеток двадцать минут назад",
            "не пила таблеток 3 месяца",
            "в упаковке таблеток 20 штук",
        ).forEach { assertFalse(it, overdoseIn(it)) }
    }

    /**
     * A count before a medicine's name with no word of how it was taken — "20 paracetamol" — is too much
     * as "took 20 paracetamol" is; a figure a person is on, prescribed or asks about is a strength, and so
     * is one after "on" or "to".
     */
    @Test
    fun aBareCountBeforeANameIsTooMuchAndAStrengthIsNot() {
        listOf(
            "20 paracetamol",
            "20 paracetamol and half a bottle of vodka",
            "about 30 co-codamol",
            "20 парацетамола",
            "16 нурофенов и бутылка вина",
        ).forEach { assertTrue(it, overdoseIn(it)) }
        listOf(
            "I take 50 sertraline",
            "on 50 sertraline",
            "been on 100 sertraline for 3 weeks",
            "the doctor upped it to 100 sertraline",
            "prescribed 100 sertraline",
            "50 sertraline daily",
            "can I take 3 paracetamol?",
            "я на 50 сертралина",
            "taking 50 sertraline and feeling sick",
            "started 50 sertraline yesterday",
        ).forEach { assertFalse(it, overdoseIn(it)) }
    }

    /**
     * More whole tablets than the person's course of a tricyclic or of codeine says is "more than it
     * says?" though the count is under three — NHS: "more than your prescribed dose of amitriptyline,
     * even if you do not have any symptoms" (so «две таблетки» of a tricyclic course are never filed
     * as one dose); never against a course whose dose names no tablets, nor for a medicine whose page
     * allows an extra tablet or two.
     */
    @Test
    fun moreTabletsThanATricyclicsOrCodeinesCourseSaysIsTooMuch() {
        val amitriptyline = course("Amitriptyline", dose = "1 tablet")
        val mine = listOf(amitriptyline)
        assertTrue(overdoseIn("выпила две таблетки амитриптилина", mine))
        assertTrue(overdoseIn("took 2 amitriptyline tablets", mine))
        assertTrue(overdoseIn("I've had 2 tablets", mine, about = true, names = listOf("Amitriptyline")))
        assertTrue(overdoseIn("took 2 codeine tablets", listOf(course("Codeine", dose = "1 tablet"))))
        val russian = listOf(course("Амитриптилин", dose = "1 таблетка"))
        assertTrue(overdoseIn("выпила 2 таблетки", russian, about = true, names = listOf("Амитриптилин")))
        // As prescribed, a course's new dose told, a course told a day, one whose dose is a strength,
        // another medicine's course.
        assertFalse(overdoseIn("took 1 amitriptyline tablet", mine))
        assertFalse(overdoseIn("теперь две таблетки", mine, about = true, names = listOf("Amitriptyline")))
        val two = listOf(course("Amitriptyline", dose = "2 tablets"))
        assertFalse(overdoseIn("выпила две таблетки амитриптилина", two))
        assertFalse(overdoseIn("I take 2 amitriptyline tablets a day", mine))
        assertFalse(overdoseIn("took 2 amitriptyline tablets", listOf(course("Amitriptyline", dose = "10 mg"))))
        assertFalse(overdoseIn("took 2 sertraline tablets", listOf(course("Sertraline", dose = "1 tablet"))))
        assertFalse(overdoseIn("took 2 co-codamol tablets", listOf(course("Co-codamol", dose = "1 tablet"))))
        assertFalse(overdoseIn("took 2 tablets of paracetamol", mine))
    }

    /**
     * A word of stopping stops the medicine it is told with, and none the person goes on with: not
     * every medicine the message names, and not undone by a word of prescribing elsewhere in it.
     */
    @Test
    fun aWordOfStoppingStopsOnlyTheMedicineItIsToldWith() {
        listOf(
            "бросила пить сертралин" to "сертралин",
            "I stopped taking ibuprofen" to "ibuprofen",
            "сертралин больше не пью" to "Сертралин",
            "врач отменил флуоксетин, теперь сертралин" to "флуоксетин",
            "My doctor stopped sertraline and prescribed mirtazapine" to "sertraline",
            "I'm on sertraline but stopped fluoxetine" to "fluoxetine",
            "stopped taking sertraline and fluoxetine" to "fluoxetine",
            "сертралин и флуоксетин бросила" to "сертралин",
            "came off my Microgynon" to "Microgynon",
        ).forEach { (text, name) -> assertTrue("$text: $name", stoppedIn(listOf(text), name)) }
        listOf(
            "бросила курить, пью сертралин" to "сертралин",
            "бросила курить пью сертралин" to "сертралин",
            "I quit drinking, I'm on sertraline now" to "sertraline",
            "врач отменил флуоксетин, теперь сертралин" to "сертралин",
            "врач отменил флуоксетин теперь сертралин" to "сертралин",
            "My doctor stopped sertraline and prescribed mirtazapine" to "mirtazapine",
            "I'm on sertraline but stopped fluoxetine" to "sertraline",
            "I stopped smoking and started mirtazapine" to "mirtazapine",
            "I quit drinking and sertraline helps" to "sertraline",
            "бросила курить теперь сертралин" to "сертралин",
            "бросила курить, сертралин помогает" to "сертралин",
        ).forEach { (text, name) -> assertFalse("$text: $name", stoppedIn(listOf(text), name)) }
        // A message of going on with it holds it, wherever the thread read it.
        assertFalse(stoppedIn(listOf("бросила пить сертралин", "снова пью сертралин"), "сертралин"))
    }

    /** In a dose's or a course's thread its medicine is the one meant; the person's own courses are medicines too. */
    @Test
    fun tooMuchOfTheThreadsOwnMedicineOrOfAnOwnCourse() {
        assertFalse(overdoseIn("слишком много"))
        assertTrue(overdoseIn("слишком много", about = true))
        val propranolol = course("Propranolol")
        assertFalse(overdoseIn("too many propranolol"))
        assertTrue(overdoseIn("too many propranolol", listOf(propranolol)))
        assertTrue(overdoseIn("took 5 propranolol", listOf(propranolol)))
        assertTrue(overdoseIn("took 5 propranolol", names = listOf("Propranolol")))
        // A course named by a word for any medicine is no name a count stands before.
        assertFalse(overdoseIn("took my 3 vitamins", listOf(course("Vitamin D"))))
    }

    /**
     * Every key of the medicine tables is written as words are read: lower case, «е» for «ё», one space between words.
     */
    @Test
    fun everyKeyIsWrittenAsWordsAreRead() {
        val keys = medicineKeyTexts()
        assertTrue(keys.size > 300)
        keys.forEach { key -> assertEquals(key, key.trim(), normalisedWords(key).trim()) }
    }

    @Test
    fun timesAndDosesAreProposedAsWritten() {
        assertEquals(listOf(8 * 60 + 30, 20 * 60), clockMinutesIn("at 8:30 and 20.00, 2.50 mg"))
        assertEquals(listOf("50 mg", "0,5 г", "1 таблетка"), doseTextsIn("50 mg утром, 0,5 г вечером и 1 таблетка"))
        assertEquals("парацетамол", cleanName("  парацетамол "))
        assertEquals("парацетамол", cleanName("выпил слишком много парацетамол"))
        assertEquals("", cleanName("I want to die"))
    }

    /**
     * A fraction or a range is proposed with both its figures or not at all: «1/2 таблетки» is never the
     * chip «2 таблетки», four times the dose in the person's own words, nor «1 000 мг» «000 мг».
     * A figure after another that can be no part of it is still a dose.
     */
    @Test
    fun aDoseIsNeverTheTailOfAFigure() {
        assertEquals(listOf("1/2 tablet"), doseTextsIn("took 1/2 tablet of bisoprolol"))
        assertEquals(listOf("1/4 таблетки"), doseTextsIn("принимать по 1/4 таблетки"))
        assertEquals(listOf("1-2 таблетки"), doseTextsIn("по 1-2 таблетки"))
        listOf("1 000 мг", "10 000 IU", "1 1/2 tablets", "0,5-1 таблетки").forEach {
            assertEquals(it, emptyList<String>(), doseTextsIn(it))
        }
        assertEquals(listOf("2 таблетки"), doseTextsIn("в 8:30 2 таблетки"))
        // Two messages come one to a line: the figure a line ends with is no thousands of the next line's.
        assertEquals(listOf("500 мг"), doseTextsIn("давление 120\n500 мг парацетамола"))
    }
}
