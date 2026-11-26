package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The topics a reading proposes, beside the rules': chips where the
 * rules named none, in the reading's order; beside the rules' own, only those it is sure of; never a
 * thread it starts itself; someone else and feeling unwell where it says so; and the queue, within its
 * ranks, in the reading's order — the chest first and a body's sign before a feeling all the same.
 */
class ChatTopicUnionTest {
    private val setting = chatSetting()

    private fun chips(log: ChatLog): List<String> =
        (chatTurn(log, setting).reply as ChatReply.Topics).choices.map { it.mention?.key ?: it.topic.name }

    @Test
    fun whereTheRulesNameNoneTheReadingsTopicsAreTheChips() {
        val read = chatRead { copy(topics = listOf(Symptom.FEVER, Symptom.TIREDNESS)) }
        assertTrue((chatTurn(ChatLog().wroteRead("hmm", null), setting).reply as ChatReply.Topics).choices.isEmpty())
        val log = ChatLog().wroteRead("hmm", read)
        assertEquals(listOf("FEVER", "TIREDNESS"), chips(log))
        // The words read as no English or Russian word: the line to call for an emergency stands after the chips' line.
        assertEquals(listOf(ChatLine.MATCH, ChatLine.SAFETY), chatTurn(log, setting).lines())
        // Proposals, never answers: no thread has a topic until a chip is tapped.
        assertNull(log.threads.single().topic)
    }

    @Test
    fun besideTheRulesOwnOnlyTheSureOnesStand() {
        val unsure = chatRead { copy(topics = listOf(Symptom.TIREDNESS)) }
        val sure = chatRead { copy(topics = listOf(Symptom.TIREDNESS), add = listOf(Symptom.TIREDNESS)) }
        val words = "меня тошнит"
        assertEquals(listOf("NAUSEA"), chips(ChatLog().wroteRead(words, null)))
        assertEquals(chips(ChatLog().wroteRead(words, null)), chips(ChatLog().wroteRead(words, unsure)))
        val both = chips(ChatLog().wroteRead(words, sure))
        assertEquals(chips(ChatLog().wroteRead(words, null)), both - "TIREDNESS")
        assertTrue("TIREDNESS" in both)
        // A topic the rules named is the rules' own, never the reading's again.
        val again = chatRead { copy(topics = listOf(Symptom.NAUSEA), add = listOf(Symptom.NAUSEA)) }
        assertEquals(chips(ChatLog().wroteRead(words, null)), chips(ChatLog().wroteRead(words, again)))
    }

    /**
     * A thread the rules' words alone start: the reading's one record is a chip, as a chest it alone read
     * is its question.
     */
    @Test
    fun theReadingStartsNoThread() {
        val ruled = ChatLog().wroteRead("выпил нурофен", null)
        assertEquals(Symptom.DOSE, ruled.threads.single().topic)
        val read = chatRead {
            copy(
                topics = listOf(Symptom.DOSE),
                told = ReadTold(medicines = listOf(ReadMedicine("нурофен", listOf(ReadRole.TAKEN)))),
            )
        }
        val proposed = ChatLog().wroteRead("hmm", read)
        assertNull(proposed.threads.single().topic)
        assertEquals(listOf("DOSE:нурофен"), chips(proposed))
        val chest = ChatLog().wroteRead("hmm", chatRead { copy(safety = listOf(SymptomStep.CHEST_NOW)) })
        assertEquals(SymptomStep.CHEST_NOW, chest.asked())
        val answered = chest.tapOn(SymptomOption.NO)
        assertNull(answered.threads.single().topic)
        assertEquals(SymptomStep.SYMPTOM, answered.asked())
    }

    @Test
    fun someoneElseAndUnwellAreSaidWhereTheReadingSaysSo() {
        val child = chatRead { copy(topics = listOf(Symptom.FEVER), about = ReadAbout.CHILD) }
        val lines = chatTurn(ChatLog().wroteRead("hmm", child), setting).lines()
        assertTrue(lines.toString(), ChatLine.SOMEONE_ELSE in lines && ChatLine.ADULTS in lines)
        assertFalse(ChatLine.SOMEONE_ELSE in chatTurn(ChatLog().wroteRead("hmm", null), setting).lines())
        val unwell = chatRead { copy(unwell = true) }
        val turn = chatTurn(ChatLog().wroteRead("je me sens mal", unwell), setting)
        assertEquals(ChatNarrow.entries, (turn.reply as ChatReply.Topics).narrow)
        assertTrue(ChatLine.UNWELL_ASK in turn.lines())
        // Feeling unwell of someone else is no question of the person's own.
        val theirs = chatRead { copy(unwell = true, about = ReadAbout.ADULT_OTHER) }
        assertTrue((chatTurn(ChatLog().wroteRead("hmm", theirs), setting).reply as ChatReply.Topics).narrow.isEmpty())
    }

    /**
     * The queue within its ranks in the reading's order: the feelings, one rank, come as
     * the reading had them likeliest, not as the words had them first; a body's sign the reading was
     * sure of comes before every feeling all the same.
     */
    @Test
    fun theQueueFollowsTheReadingWithinItsRanks() {
        val words = "тревожно, стресс и не могу уснуть"
        fun queued(read: ChatRead?): List<Symptom> {
            val log = ChatLog().wroteRead(words, read).choseTopic(TopicChoice(Symptom.OTHER), setting).settle()
                .calmToTheEnd()
            assertEquals(ChatMove.Prompt, chatMove(log, setting))
            return chatQueue(log, setting).map { it.topic }
        }
        assertEquals(listOf(Symptom.WORRY, Symptom.STRESS, Symptom.SLEEP_WORRY), queued(null))
        val likeliest = chatRead { copy(topics = listOf(Symptom.SLEEP_WORRY, Symptom.STRESS, Symptom.WORRY)) }
        assertEquals(listOf(Symptom.SLEEP_WORRY, Symptom.STRESS, Symptom.WORRY), queued(likeliest))
        val fever = likeliest.copy(topics = likeliest.topics + Symptom.FEVER, add = listOf(Symptom.FEVER))
        assertEquals(listOf(Symptom.FEVER, Symptom.SLEEP_WORRY, Symptom.STRESS, Symptom.WORRY), queued(fever))
        // A reading the chat does not route by orders nothing.
        assertEquals(queued(null), queued(likeliest.copy(shadow = true)))
    }

    /**
     * The queue keeps every chip of the rules' own: a topic a reading is sure of fills only the room
     * the rules leave in its four, whatever its rank — never pushes one of theirs out (a
     * reading only adds).
     */
    @Test
    fun aReadingsTopicNeverPushesTheRulesOutOfTheQueue() {
        val words = "тошнит, понос, изжога, кружится голова, температура, устал"

        // The first thread on the nausea, its chip tapped, to its end: the queue is what stands then.
        fun nausea(log: ChatLog): ChatLog {
            val chip = (chatTurn(log, setting).reply as ChatReply.Topics).choices.first { it.topic == Symptom.NAUSEA }
            return log.choseTopic(chip, setting).settle().calmToTheEnd()
        }
        fun queue(read: ChatRead?): List<Symptom> {
            val log = nausea(ChatLog().wroteRead(words, read))
            assertEquals(ChatMove.Prompt, chatMove(log, setting))
            return chatQueue(log, setting).map { it.topic }
        }
        val rules = queue(null)
        assertEquals(listOf(Symptom.DIARRHOEA, Symptom.HEARTBURN, Symptom.DIZZINESS, Symptom.FEVER), rules)
        val sure = chatRead { copy(topics = listOf(Symptom.VOMITING), add = listOf(Symptom.VOMITING)) }
        assertEquals(rules, queue(sure))
        // With room left, the reading's own fills it, in its rank.
        val done = nausea(ChatLog().wroteRead("тошнит, понос, изжога", sure))
        assertEquals(
            listOf(Symptom.VOMITING, Symptom.DIARRHOEA, Symptom.HEARTBURN),
            chatQueue(done, setting).map { it.topic },
        )
    }
}
