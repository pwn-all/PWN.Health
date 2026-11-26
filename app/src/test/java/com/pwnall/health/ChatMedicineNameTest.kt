package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * A medicine's name as the chat writes it down: a chip's own words — "Ibuprofen" and "Sertraline", as
 * the chips show them (never "ibuprofen · 2 tablets" on the dose card, nor in the Medicine tab
 * "sertraline", the words of the message they were read from), and for a medicine of the list
 * the base form of the name the person wrote, in its script («Парацетамол», never the «Парацетамола»
 * of «выпила 2 таблетки парацетамола») — and a name typed as typed, trimmed, the medicine of words of
 * too much typed in its place in the person's own letters too. A name still finds the person's course
 * whatever its letters and its case, and a course they have keeps its name.
 */
class ChatMedicineNameTest {
    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    /** The words [chip] shows. */
    private fun shown(chip: ValueChip): String = (chip.label.args.single() as PieceArg.Text).s

    /** The first chip proposed above the composer, tapped as the page taps it: the words it showed. */
    private fun MedicineChat.tapNameChip(step: SymptomStep): String {
        val chip = (reply as ChatReply.TextAsk).suggestions.first()
        value(chip.value, step)
        return shown(chip)
    }

    /** A dose named: taken just now, two tablets, no more than it says, for pain; the row it files. */
    private fun MedicineChat.doseFiled(): JournalEntry {
        value("NOW", SymptomStep.MED_WHEN).value(AMOUNT_TWO, SymptomStep.MED_AMOUNT)
            .tap(SymptomOption.NO, SymptomStep.MED_MAX).tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY)
        val store = MemoryChatStore()
        file(ChatFiling(store, "k"), "k")
        return store.db.journal.values.single()
    }

    /** A course named: 50 mg once a day at 09:00 from tomorrow for six months; the course it makes. */
    private fun MedicineChat.courseFiled(): Medication {
        type("50 mg", SymptomStep.COURSE_DOSE).tap(SymptomOption.ONCE_A_DAY, SymptomStep.COURSE_OFTEN)
            .value("540", SymptomStep.COURSE_TIMES)
            .value(CHAT_TODAY.plusDays(1).toString(), SymptomStep.COURSE_START)
            .value("M6", SymptomStep.COURSE_LENGTH)
        assertEquals(SymptomStep.COURSE_FOOD, step)
        val store = MemoryChatStore()
        file(ChatFiling(store, "k"), "k")
        return store.db.courses.values.single()
    }

    @Test
    fun aDoseNamedWithAChipIsSavedAsTheChipShowedIt() {
        val chat = MedicineChat().write("I took ibuprofen")
        assertEquals("Ibuprofen", chat.tapNameChip(SymptomStep.MED_NAME))
        val row = chat.doseFiled()
        assertEquals("Ibuprofen", row.title)
        assertEquals("2 tablets", row.subtitle)
        // «Нурофен · нет в вашем списке» beside the person's courses: the dose is «Нурофен» too.
        val pick = MedicineChat(ChatEnv(courses = listOf(course("Melatonin")))).write("выпила нурофен")
        val chip = (pick.reply as ChatReply.MedicinePick).proposals.first()
        assertEquals("Нурофен", shown(chip))
        pick.value(chip.value, SymptomStep.MED_WHICH)
        assertEquals("Нурофен", pick.doseFiled().title)
    }

    @Test
    fun aCourseNamedWithAChipIsAddedAsTheChipShowedIt() {
        val chat = MedicineChat().write("my doctor prescribed sertraline 50 mg")
        assertEquals("Sertraline", chat.tapNameChip(SymptomStep.COURSE_NAME))
        assertEquals("Sertraline", chat.courseFiled().name)
    }

    @Test
    fun aMedicineOfTheListWrittenInACaseIsShownAndSavedInItsBaseForm() {
        // A generic and a brand in the case the message has them: the chip and the dose say the base form.
        val generic = MedicineChat().write("выпила 2 таблетки парацетамола")
        assertEquals("Парацетамол", generic.tapNameChip(SymptomStep.MED_NAME))
        assertEquals("Парацетамол", generic.doseFiled().title)
        val brand = MedicineChat().write("принял нурофена")
        assertEquals("Нурофен", brand.tapNameChip(SymptomStep.MED_NAME))
        assertEquals("Нурофен", brand.doseFiled().title)
        // Beside the person's courses: «Нурофен · нет в вашем списке», and the dose is «Нурофен».
        val pick = MedicineChat(ChatEnv(courses = listOf(course("Melatonin")))).write("выпила нурофена")
        val chip = (pick.reply as ChatReply.MedicinePick).proposals.first()
        assertEquals("Нурофен", shown(chip))
        pick.value(chip.value, SymptomStep.MED_WHICH)
        assertEquals("Нурофен", pick.doseFiled().title)
        // A course: the generic or the brand the person named, never the other one.
        val prescribed = MedicineChat().write("назначили сертралина")
        assertEquals("Сертралин", prescribed.tapNameChip(SymptomStep.COURSE_NAME))
        assertEquals("Сертралин", prescribed.courseFiled().name)
        val prescribedBrand = MedicineChat().write("врач назначил курс золофта")
        assertEquals("Золофт", prescribedBrand.tapNameChip(SymptomStep.COURSE_NAME))
        assertEquals("Золофт", prescribedBrand.courseFiled().name)
        // The topic's own chip names it so too.
        assertEquals(
            ChatText(R.string.med_chat_mention_dose, listOf(PieceArg.Text("Парацетамол"))),
            DoseScript.mentionLabel(TopicMention(Symptom.DOSE, "парацетамола")),
        )
        assertEquals(
            ChatText(R.string.med_chat_mention_course, listOf(PieceArg.Text("Золофт"))),
            CourseScript.mentionLabel(TopicMention(Symptom.COURSE, "золофта")),
        )
    }

    /**
     * Words of a dose not taken name it so on the topic's chip: «не выпила нурофен» is never offered as "Took:
     * Nurofen", the mention carrying no word of it. Its key keeps the word, so the thread begun from the chip
     * words the person's bubble the same way and still starts from the medicine named.
     */
    @Test
    fun aDoseNotTakenIsNamedSoOnItsChip() {
        fun dose(text: String) =
            readWords(text, chatSetting()).mentions.map { it.mention }.single { it.topic == Symptom.DOSE }
        val missed = dose("не выпила нурофен")
        assertEquals(TopicMention(Symptom.DOSE, "нурофен", missed = true), missed)
        assertEquals(
            ChatText(R.string.med_chat_mention_missed, listOf(PieceArg.Text("Нурофен"))),
            DoseScript.mentionLabel(missed),
        )
        assertEquals("DOSE:нурофен:MISSED", missed.key)
        assertEquals(missed, mentionOfKey(missed.key))
        assertEquals(TopicMention(Symptom.DOSE, "нурофен"), dose("выпила нурофен"))
        assertEquals(TopicMention(Symptom.DOSE, "нурофен"), mentionOfKey("DOSE:нурофен"))
        assertEquals(TopicMention(Symptom.PAIN, "KNEE"), mentionOfKey("PAIN:KNEE"))
    }

    @Test
    fun aLatinNameStaysLatin() {
        val dose = MedicineChat().write("took two paracetamols")
        assertEquals("Paracetamol", dose.tapNameChip(SymptomStep.MED_NAME))
        assertEquals("Paracetamol", dose.doseFiled().title)
        // The brand in the letters it was written in, in a Russian message: neither «Нурофен» nor "Ibuprofen".
        val brand = MedicineChat().write("выпила nurofen")
        assertEquals("Nurofen", brand.tapNameChip(SymptomStep.MED_NAME))
        assertEquals("Nurofen", brand.doseFiled().title)
        val course = MedicineChat().write("my doctor prescribed zoloft")
        assertEquals("Zoloft", course.tapNameChip(SymptomStep.COURSE_NAME))
        assertEquals("Zoloft", course.courseFiled().name)
    }

    @Test
    fun theBaseFormIsTheKeyNamedWrittenAsAName() {
        assertEquals("Парацетамол", baseName("парацетамолом"))
        assertEquals("Панадол", baseName("панадола"))
        assertEquals("Paracetamol", baseName("paracetamols"))
        assertEquals("Кеторол", baseName("кеторола"))
        assertEquals("Кеторолак", baseName("кеторолака"))
        // A stem, one case of a name, a hyphen or an apostrophe the keys leave out, a brand of two words.
        assertEquals("Зверобой", baseName("зверобоя"))
        assertEquals("Ацетилсалициловая кислота", baseName("ацетилсалициловой"))
        assertEquals("Симбалта", baseName("симбалты"))
        assertEquals("Некст", baseName("некстом"))
        assertEquals("Co-codamol", baseName("co codamol"))
        assertEquals("St John's wort", baseName("st john s wort"))
        assertEquals("Нурофен Плюс", baseName("нурофен плюс"))
        // No name of the list — the person's own course named in the words — is shown as written.
        assertNull(baseName("melatonin"))
        assertNull(baseName("аспирин кардио"))
        assertEquals("Аспирин кардио", wordsName("аспирин кардио"))
        assertEquals("Melatonin", wordsName("melatonin"))
    }

    @Test
    fun everyKeyOfTheListHasABaseFormOfItsOwnMedicineInItsOwnScript() {
        fun cyrillic(text: String) = text.any { it.lowercaseChar() in 'а'..'я' }
        KNOWN_MEDICINES.forEach { medicine ->
            medicine.keys.forEach { key ->
                val base = baseName(key)
                assertEquals(key, medicine.id, base?.let(::knownMedicineOf)?.id)
                assertEquals(key, cyrillic(key), cyrillic(base.orEmpty()))
                assertTrue(key, base.orEmpty().first().isUpperCase())
            }
        }
    }

    @Test
    fun aNameTypedIsSavedAsTypedTrimmed() {
        val dose = MedicineChat().start(Symptom.DOSE).type("  nurofen Express ", SymptomStep.MED_NAME)
        assertEquals("nurofen Express", dose.doseFiled().title)
        val course = MedicineChat().start(Symptom.COURSE).type(" sertraline Accord  ", SymptomStep.COURSE_NAME)
        assertEquals("sertraline Accord", course.courseFiled().name)
        // A name of the list typed in a case is the person's own: never put in its base form.
        val inflected = MedicineChat().start(Symptom.DOSE).type("парацетамола ", SymptomStep.MED_NAME)
        assertEquals("парацетамола", inflected.doseFiled().title)
        val inflectedCourse = MedicineChat().start(Symptom.COURSE).type("Сертралина", SymptomStep.COURSE_NAME)
        assertEquals("Сертралина", inflectedCourse.courseFiled().name)
        // Words of too much typed in place of the name: the medicine they name, as the person typed it.
        val tooMuch = MedicineChat().start(Symptom.DOSE).type("Co-Codamol, took the whole pack", SymptomStep.MED_NAME)
        assertEquals(SymptomStep.MED_MAX_WORDS, tooMuch.step)
        assertEquals("Co-Codamol", threadMedicine(tooMuch.report)?.name)
    }

    @Test
    fun theMedicineInWordsOfTooMuchIsNamedInThePersonsOwnLetters() {
        assertEquals("Нурофена", firstMedicineAsTyped("выпила всю пачку Нурофена"))
        assertEquals("Co-Codamol", firstMedicineAsTyped("took 20 Co-Codamol!"))
        // «İ» is one letter in lower case too — the dot its lower case leaves is taken off — so the
        // words split alike, and the name is the person's own letters even beside it.
        assertEquals("Nurofen", firstMedicineAsTyped("İİ took 10 Nurofen"))
        assertEquals("Nurofen", firstMedicineAsTyped("मैंने 20 Nurofen लिया"))
        assertNull(firstMedicineAsTyped("took the whole pack"))
    }

    @Test
    fun aNameFindsTheCourseWhateverItsLettersAndTheCourseKeepsItsOwn() {
        val sertraline = course("sertraline", start = CHAT_TODAY.minusDays(4))
        val store = MemoryChatStore().apply { db.courses[sertraline.id] = sertraline }
        val dose = MedicineChat(ChatEnv(courses = listOf(sertraline))).start(Symptom.DOSE)
        dose.value(MED_OTHER, SymptomStep.MED_WHICH).type("SERTRALINE", SymptomStep.MED_NAME)
        dose.value(MED_COURSE_PREFIX + sertraline.createdAt, SymptomStep.MED_IS_COURSE)
            .value("540", SymptomStep.MED_SLOT).value("SLOT", SymptomStep.MED_WHEN)
            .tap(SymptomOption.FEEL_BETTER, SymptomStep.MED_FEEL)
        dose.file(ChatFiling(store, "k"), "k")
        assertEquals("sertraline", store.db.journal.values.single().title)
        val prescribed = MedicineChat(ChatEnv(courses = listOf(sertraline))).write("my doctor prescribed sertraline")
        assertEquals("Sertraline", prescribed.tapNameChip(SymptomStep.COURSE_NAME))
        prescribed.tap(SymptomOption.SAME_COURSE, SymptomStep.COURSE_SAME)
        prescribed.file(ChatFiling(store, "k2"), "k2")
        // «Сертралин» from «назначили сертралина» is the same course too, in another script and case.
        val russian = MedicineChat(ChatEnv(courses = listOf(sertraline))).write("назначили сертралина")
        assertEquals("Сертралин", russian.tapNameChip(SymptomStep.COURSE_NAME))
        russian.tap(SymptomOption.SAME_COURSE, SymptomStep.COURSE_SAME)
        russian.file(ChatFiling(store, "k3"), "k3")
        assertEquals(listOf("sertraline"), store.db.courses.values.map { it.name })
        assertEquals(1, store.db.journal.size)
    }
}
