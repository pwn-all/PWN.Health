package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Undo on a dose's cards holds: what is said after it, and the next run of the app that reads the chat
 * back from the database, file nothing again — neither the dose of no course nor the change "a painkiller
 * was taken" it made to this chat's pain. Only the person's own change of an answer would.
 */
class DoseUndoFlowTest {
    private val store = MemoryChatStore()

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    private fun MedicineChat.context() =
        FilingContext(FilingClock(now, CHAT_TODAY, CHAT_ZONE), CHAT_TODAY, false, "k", log, env, medicineStrings)

    /** Undo on the card of the last thread's record [role], its links taken back into the log. */
    private fun MedicineChat.undo(role: String) {
        val filing = ChatFiling(store, "k")
        log = log.withLinks(runBlocking { checkNotNull(filing.undo(log.threads.last().id, role, context())) })
    }

    /** The app closed and opened again: the chat as the database holds it, a message sent, filed by a new filing. */
    private fun MedicineChat.afterRestart(): MedicineChat = apply {
        log = stored(store, "k")
        write("thanks")
        file(ChatFiling(store, "k"), "k")
    }

    private fun doses(): List<JournalEntry> = store.db.journal.values.filter { it.kind == KIND_MED }

    @Test
    fun aDoseUndoneStaysUndoneThroughAMessageAndTheNextRunOfTheApp() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен", SymptomStep.MED_NAME).value("NOW").value(AMOUNT_ONE)
            .tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY)
        chat.file(ChatFiling(store, "k"), "k")
        assertEquals(1, doses().size)
        chat.undo(DOSE_ROLE)
        assertTrue(doses().isEmpty())
        chat.afterRestart()
        assertTrue(doses().isEmpty())
        val thread = chat.log.threads.first { it.topic == Symptom.DOSE }
        val link = thread.filed.single { it.role == DOSE_ROLE }
        assertEquals(LinkState.UNDONE, link.state)
        // What this run makes of the answers read back is the fingerprint stored with the link.
        assertEquals(link.sig, doseSig(thread, DOSE_ROLE))
    }

    @Test
    fun aPainkillerNotedOnThePainAndUndoneStaysUndoneThroughTheNextRunOfTheApp() {
        val chat = MedicineChat().start(Symptom.PAIN)
        chat.log = chat.log.calmToTheEnd(chat.setting)
        chat.file(ChatFiling(store, "k"), "k")
        fun painkiller(): Boolean = decodePainPayload(store.painRows().single().measurementPayloadJson).painkiller
        assertFalse(painkiller())
        chat.start(Symptom.DOSE)
        chat.type("нурофен", SymptomStep.MED_NAME).value("NOW").value(AMOUNT_ONE)
            .tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY).tap(SymptomOption.CHANGE_IT, SymptomStep.MED_PAIN_LINK)
        chat.file(ChatFiling(store, "k"), "k")
        assertTrue(painkiller())
        chat.undo(DOSE_PAIN_ROLE)
        assertFalse(painkiller())
        chat.afterRestart()
        assertFalse(painkiller())
        // The dose itself was not undone and stands.
        assertEquals(1, doses().size)
        val thread = chat.log.threads.first { it.topic == Symptom.DOSE }
        assertEquals(LinkState.UNDONE, thread.filed.single { it.role == DOSE_PAIN_ROLE }.state)
    }
}
