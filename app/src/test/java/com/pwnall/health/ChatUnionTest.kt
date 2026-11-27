package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The union never loses a rule's step: every message the chat's own tests write, read by the
 * tiny fixture forced on — random weights, so it reads anything into anything, flags raised and
 * topics proposed at random — gets at least everything the rules give it: every question its words
 * bring, in their order and before any a reading brings; every topic chip of the topic question;
 * every safety piece of the turn that meets it, even once the reading's question about suicide is
 * answered "yes, I can keep myself safe", which closes a thread of no topic; and, the chat driven
 * calmly to its end, every question the rules' chat asks, every safety line and card it shows, and at every
 * question both chats ask, every mark the words put on a chip, the ruler's rest on their number and
 * the answer they stand ticked with — and every safety piece of the turn that meets the message and
 * of the first topic question, turn for turn. Each message is sent as the page sends it, three ways:
 * with the whole reading; with its topics and what it told alone, the flags off — the likeliest
 * reading, of words with nothing dangerous in them, which the fixture's flags at its low thresholds
 * would otherwise hide; and with its flags alone — as the first words of a chat, and once more, whole,
 * written during a question, where the rules meet it with a remark. What a reading adds comes on top,
 * and at random it adds plenty.
 */
class ChatUnionTest {
    private val setting = chatSetting()

    /** Every message the chat's tests write: their string literals that read as one. */
    private val messages: List<String> by lazy {
        val dir = listOf("src/test/java/com/pwnall/health", "app/src/test/java/com/pwnall/health").map(::File)
            .first(File::isDirectory)
        messagesOf(dir, SOURCES)
    }

    /**
     * A source that is gone stops the test with its name: the list is by name, so a file renamed or
     * split would leave it silently, and a floor on the count alone lets a whole source go —
     * SafetyWordsTest, with every message about a crisis and an overdose, could go and the union
     * still be called whole.
     */
    @Test
    fun aSourceThatIsGoneOrGivesNothingIsNamed() {
        val dir = Files.createTempDirectory("union").toFile()
        try {
            File(dir, "Here.kt").writeText("val said = \"болит колено\"")
            File(dir, "Mute.kt").writeText("val count = 3")
            assertEquals(listOf("болит колено"), messagesOf(dir, listOf("Here")))
            val gone = runCatching { messagesOf(dir, listOf("Here", "Gone")) }.exceptionOrNull()
            assertTrue("$gone", gone is IllegalStateException && "Gone.kt" in gone.message.orEmpty())
            val mute = runCatching { messagesOf(dir, listOf("Here", "Mute")) }.exceptionOrNull()
            assertTrue("$mute", mute is IllegalStateException && "Mute.kt" in mute.message.orEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theUnionNeverLosesARuleStep() = runBlocking {
        assertTrue("only ${messages.size} messages", messages.size > MIN_MESSAGES)
        var added = 0
        var flagged = 0
        var proposed = 0
        for (text in messages) {
            val read = ChatLog().readBy(READER, text, setting)
            checkNotNull(read) { "the fixture did not read «$text»" }
            if (read.safety.isNotEmpty()) flagged++
            if (read.topics.isNotEmpty()) proposed++
            val rules = ChatLog().wrote(text).settle(setting)
            val ways = listOf(
                read,
                read.copy(safety = emptyList()),
                read.copy(topics = emptyList(), add = emptyList(), unwell = false, about = null, told = ReadTold()),
            )
            for (way in ways) {
                if (compare(text, way, rules, ChatLog().sentRead(text, way, setting))) added++
            }
            // The reading's question about suicide answered "yes, I can keep myself safe", which closes a
            // thread with no topic: every safety piece the rules met the message with was shown first.
            if (SymptomStep.CRISIS_NOW in read.safety) safeNowKeeps(text, read, rules, ChatLog().sentRead(text, read))
            // Written during the knee's area question: the rules' remark, or the question its words bring.
            val during = KNEE.wrote(text).settle(setting)
            if (compare(text, read, during, KNEE.sentRead(text, read, setting), duringAQuestion = true)) added++
        }
        assertTrue("the fixture flagged only $flagged of ${messages.size}", flagged > messages.size / 4)
        assertTrue("the fixture proposed topics in only $proposed of ${messages.size}", proposed > messages.size / 10)
        assertTrue("the readings added nothing anywhere", added > messages.size / 4)
    }

    /**
     * [union] — [text] sent with [read] — holds everything [rules] does; whether it added anything.
     * [duringAQuestion]: the message was written during a question, so the turn that meets it — a
     * remark, a question its words or a flag bring — holds every safety piece of the rules' one.
     * Assertions say the message and the reading, so a failure names what lost what.
     */
    private fun compare(
        text: String,
        read: ChatRead,
        rules: ChatLog,
        union: ChatLog,
        duringAQuestion: Boolean = false,
    ): Boolean {
        val where = "«$text» read as $read${if (duringAQuestion) " during a question" else ""}"
        val ruleSteps = wordStepsOf(rules)
        val unionSteps = wordStepsOf(union)
        // The rules' own questions first, as they ask them: a reading's only after.
        assertEquals("$where: $ruleSteps not first in $unionSteps", ruleSteps, unionSteps.take(ruleSteps.size))
        val ruleTurn = chatTurn(rules, setting)
        val unionTurn = chatTurn(union, setting)
        if (duringAQuestion) assertSafetyKept("$where: the turn after it", ruleTurn, unionTurn)
        // The same chat both ways: the rules' chip taken, every question the rules asked answered as
        // they were, the reading's own answered calmly.
        val ruleDrive = Drive(rules, null)
        val unionDrive = Drive(union, ruleDrive)
        // The topics the rules offered as chips at the topic question are offered with the reading.
        val ruleChips = ruleDrive.chips?.takeIf { !it.all }?.choices.orEmpty().map { it.topic }
        val unionChips = unionDrive.chips?.choices.orEmpty().map { it.topic }
        assertTrue("$where: chips $ruleChips not among $unionChips", unionChips.containsAll(ruleChips))
        val ruleTopic = ruleDrive.topicTurn
        val unionTopic = unionDrive.topicTurn
        if (ruleTopic != null && unionTopic != null) {
            assertSafetyKept("$where: the topic question", ruleTopic, unionTopic)
        }
        // The threads the rules started are the union's: a reading starts none of its own.
        val ruleThreads = ruleDrive.log.threads.map { it.topic to it.from }
        val unionThreads = unionDrive.log.threads.map { it.topic to it.from }
        assertTrue("$where: $ruleThreads not kept in $unionThreads", isSubsequence(ruleThreads, unionThreads))
        val ruleAsked = askedIn(ruleDrive.log)
        val unionAsked = askedIn(unionDrive.log)
        // Every question the rules asked is asked as often: the reading's "no such thoughts" stands for no
        // question of the topic's own about suicide.
        ruleAsked.forEach { (step, count) ->
            val kept = unionAsked[step] ?: 0
            assertTrue("$where: $step asked $count times by the rules, $kept with the reading", kept >= count)
        }
        val ruleSafety = safetyPieces(ruleDrive.log)
        val unionSafety = safetyPieces(unionDrive.log)
        assertTrue("$where: the rules' ${ruleSafety - unionSafety} are lost", unionSafety.containsAll(ruleSafety))
        // At every question both asked, what the words proposed stands as they proposed it.
        ruleDrive.replies.forEach { (step, reply) ->
            unionDrive.replies[step]?.let { kept -> assertProposalsKept("$where at $step", reply, kept) }
        }
        // A line said in a model's words for a message a model read ([noMatchLine], [longRemark]) adds nothing.
        return unionSteps.size > ruleSteps.size || unionAsked.values.sum() > ruleAsked.values.sum() ||
            unionTurn.inRulesWords() != ruleTurn
    }

    /** [this] with the lines a model's reading words in its own way said as the chat says them without one. */
    private fun BotTurn.inRulesWords(): BotTurn = copy(
        pieces = pieces.map { piece ->
            if (piece !is BotPiece.Line) return@map piece
            when (piece.line) {
                ChatLine.NO_MATCH_MODEL -> piece.copy(line = ChatLine.NO_MATCH)
                ChatLine.REMARK_MODEL -> piece.copy(line = ChatLine.REMARK)
                else -> piece
            }
        },
    )

    /**
     * [union], whose reading of [text] asks about suicide, driven with that question answered "yes, I
     * can keep myself safe" — the answer that closes a thread of words with no topic, so the router's
     * reply to them never comes: every safety piece of the rules' turn meeting the message is shown in
     * the turns before.
     */
    private fun safeNowKeeps(text: String, read: ChatRead, rules: ChatLog, union: ChatLog) {
        val drive = Drive(union, Drive(rules, null), crisisNow = SymptomOption.SAFE_NOW)
        val shown = safetyPieces(drive.log)
        val lost = chatTurn(rules, setting).pieces.filter { it.isSafety() && it !is BotPiece.Alert } - shown
        assertTrue("«$text» read as $read, its question answered SAFE_NOW, lost $lost", lost.isEmpty())
    }

    /**
     * Every safety line and note of [rules] — the line to call, where help is, someone else's, a
     * medicine's notes — in [union]. An answer's alert stands in the turn after that answer, which a
     * question a reading added comes between: the whole transcript holds it ([safetyPieces]).
     */
    private fun assertSafetyKept(where: String, rules: BotTurn, union: BotTurn) {
        fun said(turn: BotTurn) = turn.pieces.filter { it.isSafety() && it !is BotPiece.Alert }
        val lost = said(rules) - said(union).toSet()
        assertTrue("$where lost $lost: ${union.pieces}", lost.isEmpty())
    }

    /** The marks of [rules]' reply on [union]'s, its ruler's rest and the answer it stands ticked with. */
    private fun assertProposalsKept(where: String, rules: ChatReply, union: ChatReply) {
        when {
            rules is ChatReply.Choose && union is ChatReply.Choose -> {
                val marks = "$where: marks ${rules.marked} not in ${union.marked}"
                assertTrue(marks, union.marked.containsAll(rules.marked))
                val marked = union.marked + union.chatMarked
                assertTrue("$where: ${rules.chatMarked} not marked in $marked", marked.containsAll(rules.chatMarked))
                // A reading never ticks an answer: what stands ticked is the rules' alone.
                assertEquals("$where: the ticked answer", rules.preset, union.preset)
            }

            rules is ChatReply.PainScale && union is ChatReply.PainScale -> {
                if (rules.rest != null) assertEquals("$where: the ruler's rest", rules.rest, union.rest)
                assertEquals("$where: the ruler's start", rules.start, union.start)
            }

            rules is ChatReply.TextAsk && union is ChatReply.TextAsk -> assertTrue(
                "$where: ${rules.suggestions} not among ${union.suggestions}",
                union.suggestions.map { it.value }.containsAll(rules.suggestions.map { it.value }),
            )
        }
    }

    /** The questions the words of the last thread call for. */
    private fun wordStepsOf(log: ChatLog): List<SymptomStep> {
        val thread = log.threads.lastOrNull() ?: return emptyList()
        val chat = thread.report.chat ?: return emptyList()
        return wordSteps(chat, thread.report.asTold(), setting.env.courses)
    }

    /** Every answer [log] holds, by its question: what a second drive gives the same questions. */
    private class Answers(
        val options: Map<SymptomStep, List<SymptomOption>>,
        val values: Map<SymptomStep, String>,
        val severity: SymptomSeverity?,
    )

    private fun answersOf(log: ChatLog): Answers {
        val saved = log.threads.map { it.report.forSave() }
        return Answers(
            options = saved.flatMap { it.answers.entries }.associate { it.toPair() },
            values = saved.flatMap { it.values.entries }.associate { it.toPair() },
            severity = saved.firstNotNullOfOrNull { it.severity },
        )
    }

    /**
     * [start] driven to its end as the page drives it: every question the [replay]'s chat answered,
     * answered so, every other calmly — "no", "none" or the first answer, how bad as mild; at the first
     * topic question the chip the replay took there — the first of the chips it offered, for the rules'
     * own drive — when it stands, else "That is all for now"; a question about suicide the replay has no
     * answer to with [crisisNow], when given. What it ended on, the chips of its first topic question
     * and the one it took, and the reply of each question the first time it was asked.
     */
    private inner class Drive(start: ChatLog, replay: Drive?, crisisNow: SymptomOption? = null) {
        var log: ChatLog = start
        var chips: ChatReply.Topics? = null
        var topicTurn: BotTurn? = null
        var taken: TopicChoice? = null
        val replies = mutableMapOf<SymptomStep, ChatReply>()

        init {
            val answers = replay?.let { answersOf(it.log) }
            var steps = 0
            while (true) {
                check(steps++ < DRIVE_LIMIT) { "the chat did not end within $DRIVE_LIMIT commits" }
                val move = chatMove(log, setting)
                if (move is ChatMove.Over) break
                log = when {
                    move !is ChatMove.Ask -> log.endedByPerson().settle(setting)

                    move.step == SymptomStep.SYMPTOM -> topicAnswered(replay)

                    else -> {
                        replies.putIfAbsent(move.step, chatTurn(log, setting).reply)
                        val replayed = answers?.let { log.replayed(move.step, it) }
                        val own = crisisNow?.takeIf { move.step == SymptomStep.CRISIS_NOW }?.let { option ->
                            log.answeredLast(setting) { it.tapped(move.step, option, null) }
                        }
                        (replayed ?: own ?: log.calm(move.step)).settle(setting)
                    }
                }
            }
        }

        /** The topic question answered: the first time with the chip, after it with "That is all for now". */
        private fun topicAnswered(replay: Drive?): ChatLog {
            val turn = chatTurn(log, setting)
            val offered = turn.reply as? ChatReply.Topics
            val first = chips == null
            if (first) {
                chips = offered
                topicTurn = turn
            }
            val choices = offered?.choices.orEmpty()
            val wanted = replay?.taken
            val chip = when {
                !first -> null
                replay == null -> choices.firstOrNull().takeIf { offered?.all == false }
                wanted == null -> null
                else -> choices.firstOrNull { it == wanted } ?: choices.firstOrNull { it.topic == wanted.topic }
            }
            taken = chip ?: taken
            val next = if (chip == null) log.endedByPerson() else log.choseTopic(chip, setting)
            return next.settle(setting)
        }
    }

    /** [step] answered as [answers] answered it, or null when they hold no answer to it. */
    private fun ChatLog.replayed(step: SymptomStep, answers: Answers): ChatLog? {
        val severity = answers.severity
        val value = answers.values[step]
        val options = answers.options[step]
        return when {
            step == SymptomStep.SEVERITY -> severity?.let { answeredLast(setting) { report -> report.rated(it) } }

            value != null -> answeredLast(setting) { it.valued(step, value).answered(step) }

            options != null -> answeredLast(setting) {
                it.copy(answers = it.answers + (step to options)).answered(step)
            }

            else -> null
        }
    }

    /** [step] answered calmly, by the shared driver. */
    private fun ChatLog.calm(step: SymptomStep): ChatLog {
        val state = threadState(threads.lastIndex, setting)
        val reply = chatTurn(this, setting).reply
        val topic = threads.last().topic ?: Symptom.OTHER
        return answeredLast(setting) { answer(it, state, step, reply, topic) }
    }

    /** How many times each question was asked in [log] by the person's answer or the chat's own. */
    private fun askedIn(log: ChatLog): Map<SymptomStep, Int> = log.threads
        .flatMap { thread -> thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>().map { it.step } }
        .groupingBy { it }.eachCount()

    /**
     * The safety pieces shown anywhere in [log]'s transcript and under its note: the line to call,
     * where help is, someone else's, the alerts, the notes of a medicine's, each card under the note.
     */
    private fun safetyPieces(log: ChatLog): Set<Any> = chatTranscript(log, setting, live = false)
        .filterIsInstance<ChatItem.Bot>()
        .flatMap { item -> item.turn.pieces.filter { it.isSafety() } }
        .toSet() + noteSafety(log.note, log.noteRead).toSet()

    companion object {
        /** The tiny fixture, loaded once for the class. */
        private val READER: ChatModelReader by lazy { tinyReader().apply { prepare() } }

        @JvmStatic
        @AfterClass
        fun closeReader() = READER.close()

        /** Under the 1,650 or so the sources hold today, and over what is left of them with a large one emptied. */
        private const val MIN_MESSAGES = 1_400

        /** A chat asking where the knee hurts: what a message is written during. */
        private val KNEE: ChatLog by lazy { ChatLog().wrote("болит колено").settle(chatSetting()) }
        private const val DRIVE_LIMIT = 120

        /** The tests whose messages are read: every test of what the chat reads in words. */
        private val SOURCES = listOf(
            "ChatChestTest", "ChatRouterTest", "ChatSafetyFloorTest", "ChatThreadsTest", "ChatUnwellTest",
            "ChatWordsTest", "ChatMedicineTest", "ChatMedicineNameTest", "ChatNowLineTest", "ChatPainTest",
            "ChatTagTest", "SymptomChatFlowTest", "SymptomWordsTest", "SafetyWordsTest", "MedicineWordsTest",
            "MedicineWordsFlowTest", "PainWordsTest", "PainMarksTest", "PainOnsetTest", "PainChatFlowTest",
            "MoodWordsTest", "MoodChatFlowTest", "MoodChatEdgeTest", "DoseThreadFlowTest", "CourseThreadFlowTest",
        )

        private val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        private val LITERAL = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

        /**
         * The messages of the tests [names] in [dir], each once. A file that is not there, or one
         * no message is read from, is an error that names it and never a shorter list.
         */
        fun messagesOf(dir: File, names: List<String>): List<String> = names.flatMap { name ->
            val file = File(dir, "$name.kt")
            check(file.isFile) { "no source ${file.name}: renamed or split, it has to be named in SOURCES again" }
            literalsOf(file.readText()).also { check(it.isNotEmpty()) { "no message read from ${file.name}" } }
        }.distinct()

        /**
         * The string literals of [source] that read as a message: no template, a letter of the person's
         * own (lower case, or a script with no case), and no more than a message holds.
         */
        fun literalsOf(source: String): List<String> = LITERAL.findAll(source.replace(RAW_STRING, " "))
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\") }
            .filter { text ->
                '$' !in text && text.length in 2..SYMPTOM_TEXT_MAX && text.any { it.isLetter() && !it.isUpperCase() }
            }
            .toList()

        /** Whether [part] stands in [whole] in its own order, each once. */
        fun <T> isSubsequence(part: List<T>, whole: List<T>): Boolean {
            var at = 0
            for (item in whole) if (at < part.size && part[at] == item) at++
            return at == part.size
        }
    }
}
