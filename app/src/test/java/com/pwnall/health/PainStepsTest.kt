package com.pwnall.health

import com.pwnall.health.PainStep.INTENSITY
import com.pwnall.health.PainStep.LASTED
import com.pwnall.health.PainStep.NOTICED
import com.pwnall.health.PainStep.ONSET
import com.pwnall.health.PainStep.PAINKILLER
import com.pwnall.health.PainStep.STATUS
import com.pwnall.health.PainStep.TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pain page's questions, one at a time: which is put, which stand as answers, and when Save
 * opens. Walked through the way a person answers them, since the order is the feature.
 */
class PainStepsTest {
    private val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)

    private fun asks(
        editing: PainReport? = null,
        marked: Boolean = true,
        rowsLoaded: Boolean = true,
        earlier: PainReport? = null,
        same: Boolean? = null,
        changed: Boolean? = null,
    ) = painQuestions(editing, marked, rowsLoaded, earlier, same, changed)

    private val newPain = asks()

    /** An earlier entry with words and a first-noticed answer, both there to carry over. */
    private val earlier = PainReport(
        regions = listOf(knee),
        types = listOf(PainType.ACHING),
        nrs = 4,
        noticed = PainNoticed.AFTER_WORKOUT,
    )

    /** A new pain, answered from the top: each answer puts the next question, and Save opens last. */
    @Test
    fun aNewPainIsAskedInOrder() {
        var flow = PainFlow()
        fun now(gone: Boolean = false) = painProgress(newPain, flow, gone)

        assertEquals(PainProgress(TYPES, listOf(TYPES), canSave = false), now())
        flow = flow.typesChosen(count = 1)
        assertEquals(TYPES, now().asking)
        flow = flow.answered(TYPES)
        assertEquals(PainProgress(ONSET, listOf(TYPES, ONSET), canSave = false), now())
        flow = flow.answered(ONSET)
        assertEquals(INTENSITY, now().asking)
        // The ruler moved: the question stays until the clock takes it.
        flow = flow.stay(INTENSITY)
        assertEquals(INTENSITY, now().asking)
        flow = flow.answered(INTENSITY)
        assertEquals(NOTICED, now().asking)
        flow = flow.answered(NOTICED)
        assertEquals(STATUS, now().asking)
        flow = flow.status(gone = false)
        assertEquals(
            PainProgress(PAINKILLER, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER), canSave = false),
            now(),
        )
        flow = flow.answered(PAINKILLER)
        assertEquals(
            PainProgress(null, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER), canSave = true),
            now(),
        )
    }

    /**
     * The words are ticked in a list that stays open: every change, the second word too, holds the
     * question open for the clock, and only the clock moves on; taking off the last reopens it.
     */
    @Test
    fun theWordsAdvanceOnlyOnTheClock() {
        val one = PainFlow().typesChosen(count = 1)
        assertEquals(PainFlow(open = TYPES), one)
        val two = one.typesChosen(count = 2)
        assertEquals(PainFlow(open = TYPES), two)
        assertEquals(TYPES, painProgress(newPain, two, gone = false).asking)
        val twoThenOne = PainFlow(setOf(TYPES)).stay(TYPES).typesChosen(count = 1)
        assertEquals(PainFlow(setOf(TYPES), open = TYPES), twoThenOne)
        assertEquals(PainFlow(open = TYPES), twoThenOne.typesChosen(count = 0))
        assertEquals(ONSET, painProgress(newPain, two.answered(TYPES), gone = false).asking)
    }

    /**
     * A row tapped is put again in its place, the other answers staying rows around it; answering it
     * closes it and the page goes on with the first question still unanswered.
     */
    @Test
    fun aRowTappedIsReopenedInPlace() {
        val upToStatus = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED))
        val reopened = upToStatus.stay(ONSET)
        assertEquals(
            PainProgress(ONSET, listOf(TYPES, ONSET, INTENSITY, NOTICED), canSave = false),
            painProgress(newPain, reopened, gone = false),
        )
        assertEquals(STATUS, painProgress(newPain, reopened.answered(ONSET), gone = false).asking)

        val all = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER))
        val again = painProgress(newPain, all.stay(INTENSITY), gone = false)
        assertEquals(INTENSITY, again.asking)
        // Every answer is still in while one is being changed.
        assertTrue(again.canSave)
        assertNull(painProgress(newPain, all.stay(INTENSITY).answered(INTENSITY), gone = false).asking)
    }

    /** "Gone" puts how long it lasted next; "still hurts" takes that answer back, and "gone" asks it again. */
    @Test
    fun goneAsksHowLongItLasted() {
        val beforeStatus = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED))
        val gone = beforeStatus.status(gone = true)
        assertEquals(LASTED, painProgress(newPain, gone, gone = true).asking)
        assertEquals(LASTED, painProgress(newPain, gone.stay(LASTED), gone = true).asking)
        val lasted = gone.answered(LASTED)
        assertEquals(PAINKILLER, painProgress(newPain, lasted, gone = true).asking)
        val done = lasted.answered(PAINKILLER)
        assertEquals(
            PainProgress(null, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, LASTED, PAINKILLER), canSave = true),
            painProgress(newPain, done, gone = true),
        )
        // Still there after all: no duration shown, none asked.
        val stillThere = done.stay(STATUS).status(gone = false)
        assertEquals(
            PainProgress(null, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER), canSave = true),
            painProgress(newPain, stillThere, gone = false),
        )
        val goneAgain = stillThere.stay(STATUS).status(gone = true)
        assertEquals(LASTED, painProgress(newPain, goneAgain, gone = true).asking)
        assertFalse(painProgress(newPain, goneAgain, gone = true).canSave)
        // Gone but not yet said how long: Save waits for the duration.
        val noDuration = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER))
        assertFalse(painProgress(newPain, noDuration, gone = true).canSave)
    }

    /**
     * First-noticed waits for its tap: Save waits for it and names it, a tap answers it and the page
     * goes on, and the chosen answer tapped again takes it back — the question held open, unanswered,
     * and Save waiting for it again.
     */
    @Test
    fun firstNoticedWaitsForItsTap() {
        val upToNoticed = PainFlow(setOf(TYPES, ONSET, INTENSITY))
        assertEquals(NOTICED, painProgress(newPain, upToNoticed, gone = false).asking)
        assertEquals(
            PainSaveWait(PainWait.ANSWER, NOTICED),
            painSaveWait(true, null, newPain, upToNoticed, gone = false),
        )
        val tapped = upToNoticed.answered(NOTICED)
        assertEquals(STATUS, painProgress(newPain, tapped, gone = false).asking)
        val all = tapped.status(gone = false).answered(PAINKILLER)
        assertTrue(painProgress(newPain, all, gone = false).canSave)

        val takenBack = all.stay(NOTICED).takenBack(NOTICED)
        assertEquals(PainFlow(setOf(TYPES, ONSET, INTENSITY, STATUS, PAINKILLER), open = NOTICED), takenBack)
        assertEquals(NOTICED, painProgress(newPain, takenBack, gone = false).asking)
        assertFalse(painProgress(newPain, takenBack, gone = false).canSave)
        assertEquals(PainSaveWait(PainWait.ANSWER, NOTICED), painSaveWait(true, null, newPain, takenBack, gone = false))
        // Every other answer in, first-noticed never tapped: it is put, and nothing passes it over.
        val passedOver = PainFlow(setOf(TYPES, ONSET, INTENSITY, STATUS, PAINKILLER))
        assertEquals(NOTICED, painProgress(newPain, passedOver, gone = false).asking)
    }

    /** Nothing is asked over an empty body, nor before the month it is looked up in is read. */
    @Test
    fun nothingIsAskedBeforeAnAreaIsMarked() {
        val none = PainProgress(null, emptyList(), canSave = false)
        assertEquals(none, painProgress(asks(marked = false), PainFlow(), gone = false))
        assertEquals(none, painProgress(asks(rowsLoaded = false), PainFlow(), gone = false))
        assertEquals(PainStep.entries, newPain.steps)
        // An answer left from an earlier match that is gone no longer counts.
        assertEquals(newPain, asks(same = true, changed = false))
    }

    /** An area logged before: nothing until "the same pain?" and, for the same one, "does it feel different?". */
    @Test
    fun theSamePainIsAskedAboutFirst() {
        assertFalse(asks(earlier = earlier).asked)
        assertFalse(asks(earlier = earlier, same = true).asked)
        assertEquals(newPain, asks(earlier = earlier, same = false))
    }

    /**
     * The same pain feeling the same: its words and first-noticed stand as carried answers above the
     * questions, never reopened — first-noticed is not left under the ruler being asked — when it
     * started is never asked, and the rating is put first.
     */
    @Test
    fun theSamePainFeelingTheSameCarriesItsAnswers() {
        val questions = asks(earlier = earlier, same = true, changed = false)
        assertEquals(listOf(INTENSITY, STATUS, LASTED, PAINKILLER), questions.steps)
        assertEquals(setOf(TYPES, NOTICED), questions.carried)
        val start = painProgress(questions, PainFlow(), gone = false)
        assertEquals(PainProgress(INTENSITY, listOf(TYPES, NOTICED, INTENSITY), canSave = false), start)
        // A carried answer cannot be held open: the page puts the first unanswered question instead.
        assertEquals(INTENSITY, painProgress(questions, PainFlow().stay(TYPES), gone = false).asking)
        val answered = PainFlow().answered(INTENSITY).status(gone = false).answered(PAINKILLER)
        assertEquals(
            PainProgress(null, listOf(TYPES, NOTICED, INTENSITY, STATUS, PAINKILLER), canSave = true),
            painProgress(questions, answered, gone = false),
        )
    }

    /** The same pain that has changed puts its words again, first; first-noticed is still carried, above them. */
    @Test
    fun theSamePainThatHasChangedAsksItsWords() {
        val questions = asks(earlier = earlier, same = true, changed = true)
        assertEquals(listOf(TYPES, INTENSITY, STATUS, LASTED, PAINKILLER), questions.steps)
        assertEquals(setOf(NOTICED), questions.carried)
        assertEquals(
            PainProgress(TYPES, listOf(NOTICED, TYPES), canSave = false),
            painProgress(questions, PainFlow(), gone = false),
        )
    }

    /** An earlier entry with nothing to carry: its words are asked, and first-noticed left out altogether. */
    @Test
    fun theSamePainWithNothingToCarryAsksTheWords() {
        val bare = PainReport(regions = listOf(knee), nrs = 4)
        val questions = asks(earlier = bare, same = true, changed = false)
        assertEquals(listOf(TYPES, INTENSITY, STATUS, LASTED, PAINKILLER), questions.steps)
        assertEquals(emptySet<PainStep>(), questions.carried)
    }

    /** "The same pain" fills in the rating and the words: they are put again rather than taken as given. */
    @Test
    fun forgottenAnswersAreAskedAgain() {
        val given = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED), open = STATUS)
        assertEquals(PainFlow(setOf(ONSET, NOTICED)), given.forget(TYPES, INTENSITY))
        assertEquals(TYPES, painProgress(newPain, given.forget(TYPES, INTENSITY), gone = false).asking)
    }

    /** An entry opened again: every answer a row, Save open — and the start of a follow-up not shown. */
    @Test
    fun anEntryOpenedAgainHasEveryAnswerAsARow() {
        val entry = PainReport(
            regions = listOf(knee),
            types = listOf(PainType.SHARP),
            nrs = 6,
            onset = PainOnset.YESTERDAY,
        )
        val flow = painFlowFor(entry, gone = false)
        val stillThere = painProgress(asks(editing = entry, rowsLoaded = false), flow, gone = false)
        assertEquals(PainProgress(null, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER), true), stillThere)
        val gone = painProgress(asks(editing = entry), painFlowFor(entry, gone = true), gone = true)
        assertEquals(PainProgress(null, PainStep.entries, canSave = true), gone)

        val followUp = entry.copy(onset = null, same = 1L)
        assertEquals(
            PainProgress(null, listOf(TYPES, INTENSITY, NOTICED, STATUS, PAINKILLER), canSave = true),
            painProgress(asks(editing = followUp), painFlowFor(followUp, gone = false), gone = false),
        )
        // Every area struck off: nothing to save.
        assertFalse(painProgress(asks(editing = entry, marked = false), flow, gone = false).canSave)
    }

    /**
     * An entry with no words, no start and no rating: it opens like any other, each blank a row and
     * Save open, so an area or the note is corrected without making an answer up; the blank row is
     * reopened by a tap like the rest.
     */
    @Test
    fun anOlderEntryMissingAnAnswerOpensWithSaveOpen() {
        val bare = PainReport(regions = listOf(knee))
        val flow = painFlowFor(bare, gone = false)
        assertEquals(
            PainProgress(null, listOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER), canSave = true),
            painProgress(asks(editing = bare), flow, gone = false),
        )
        val rating = painProgress(asks(editing = bare), flow.stay(INTENSITY), gone = false)
        assertEquals(INTENSITY, rating.asking)
        assertTrue(rating.canSave)
        assertEquals(PainFlow(), painFlowFor(null, gone = true))
    }

    /**
     * "No pain today" opened again has no answers to show: nothing is asked over its empty body, and
     * once an area is marked on it every question is put from the first, as for a new pain, with Save
     * waiting for them — never a still-there or a painkiller it was not asked about.
     */
    @Test
    fun noPainTodayOpenedAgainIsAskedAsANewPain() {
        val none = PainReport(none = true)
        val flow = painFlowFor(none, gone = false)
        assertEquals(PainFlow(), flow)
        assertEquals(
            PainProgress(null, emptyList(), canSave = false),
            painProgress(asks(editing = none, marked = false), flow, gone = false),
        )
        assertEquals(
            PainProgress(TYPES, listOf(TYPES), canSave = false),
            painProgress(asks(editing = none, rowsLoaded = false), flow, gone = false),
        )
        assertEquals(PainStep.entries, asks(editing = none).steps)
    }

    /**
     * The step logic of the whole page with no "Next" and no "Skip", in the order a person walks
     * it: each question taken on its tap or by its clock, the clock never running under a required
     * question nobody touched, and the line under Save naming what is still to answer at every step
     * until Save opens. The touches and takes are this test's own stand-ins for the page's: it pins
     * the rules, not the page's wiring of them.
     */
    @Test
    fun theWholeFlowCarriesItselfAndSaveSaysWhatItWaitsFor() {
        var flow = PainFlow()
        var touched: PainStep? = null
        var gone = false
        fun asking() = painProgress(newPain, flow, gone).asking
        fun waits() = painSaveWait(marked = true, card = null, questions = newPain, flow = flow, gone = gone)
        fun armed(step: PainStep, holds: Boolean) =
            autoTakeArmed(step.answerKind, holds, touched = touched == step, answered = step in flow.done)
        fun take(step: PainStep) {
            touched = null
            flow = flow.answered(step)
        }

        // Nothing marked yet: Save waits for an area, and nothing is asked.
        assertEquals(PainSaveWait(PainWait.AREA), painSaveWait(false, null, asks(marked = false), flow, gone))

        // One word chosen: the clock runs from the tap and takes it three seconds on.
        assertEquals(PainSaveWait(PainWait.ANSWER, TYPES), waits())
        assertFalse(armed(TYPES, holds = false))
        touched = TYPES
        flow = flow.typesChosen(count = 1)
        assertTrue(armed(TYPES, holds = true))
        take(TYPES)

        // The start is chosen from the selector: the choice is the answer, no clock.
        assertEquals(ONSET, asking())
        assertFalse(armed(ONSET, holds = true))
        assertEquals(PainSaveWait(PainWait.ANSWER, ONSET), waits())
        take(ONSET)

        // The ruler untouched rests on 5, which is not an answer: no clock, and Save says so.
        assertEquals(INTENSITY, asking())
        assertFalse(armed(INTENSITY, holds = false))
        assertEquals(PainSaveWait(PainWait.ANSWER, INTENSITY), waits())
        touched = INTENSITY
        flow = flow.stay(INTENSITY)
        assertTrue(armed(INTENSITY, holds = true))
        take(INTENSITY)

        // First-noticed is one tap, and no clock passes it over: Save names it until it is tapped.
        assertEquals(NOTICED, asking())
        assertFalse(armed(NOTICED, holds = false))
        assertFalse(armed(NOTICED, holds = true))
        assertEquals(PainSaveWait(PainWait.ANSWER, NOTICED), waits())
        take(NOTICED)

        // Still there or gone is one tap; gone asks how long it lasted.
        assertEquals(STATUS, asking())
        assertFalse(armed(STATUS, holds = true))
        gone = true
        flow = flow.status(gone = true)

        // The duration ruler starts on an hour; untouched it is never taken, a tap on it is.
        assertEquals(LASTED, asking())
        assertFalse(armed(LASTED, holds = true))
        assertEquals(PainSaveWait(PainWait.ANSWER, LASTED), waits())
        touched = LASTED
        assertTrue(armed(LASTED, holds = true))
        take(LASTED)

        // The painkiller is one tap, and with it Save opens and the line goes.
        assertEquals(PAINKILLER, asking())
        assertEquals(PainSaveWait(PainWait.ANSWER, PAINKILLER), waits())
        take(PAINKILLER)
        assertNull(asking())
        assertTrue(painProgress(newPain, flow, gone).canSave)
        assertNull(waits())
    }

    /**
     * An answered question reopened from its row runs its clock untouched — it holds the person's
     * own answer — and a required one cleared back to nothing stops it.
     */
    @Test
    fun aReopenedAnswerIsTakenAgainUntouched() {
        val all = PainFlow(setOf(TYPES, ONSET, INTENSITY, NOTICED, STATUS, PAINKILLER))
        val reopened = all.stay(INTENSITY)
        assertTrue(
            autoTakeArmed(
                INTENSITY.answerKind,
                holds = true,
                touched = false,
                answered =
                INTENSITY in reopened.done,
            ),
        )
        val cleared = all.stay(TYPES).typesChosen(count = 0)
        // Cleared, the words are no longer an answer given: even with words chosen again before
        // the next tap lands, nothing earlier arms the clock.
        assertFalse(autoTakeArmed(TYPES.answerKind, holds = true, touched = false, answered = TYPES in cleared.done))
        assertFalse(autoTakeArmed(TYPES.answerKind, holds = false, touched = true, answered = TYPES in cleared.done))
        // Every answer is still in while one is reopened: Save stays open and nothing is said under it.
        assertNull(painSaveWait(true, null, newPain, reopened, gone = false))
    }

    /** An earlier pain found: Save waits first for "the same pain?", then for "does it feel different?". */
    @Test
    fun saveWaitsForTheEarlierPainCardFirst() {
        assertEquals(PainWait.SAME, painCardWait(found = true, same = null, changed = null))
        assertEquals(PainWait.CHANGED, painCardWait(found = true, same = true, changed = null))
        assertNull(painCardWait(found = true, same = false, changed = null))
        assertNull(painCardWait(found = true, same = true, changed = false))
        assertNull(painCardWait(found = false, same = null, changed = null))
        val waiting = asks(earlier = earlier, same = null)
        assertEquals(
            PainSaveWait(PainWait.SAME),
            painSaveWait(true, painCardWait(true, null, null), waiting, PainFlow(), gone = false),
        )
        // The month not yet read: nothing to ask, and nothing said.
        assertNull(painSaveWait(true, null, asks(rowsLoaded = false), PainFlow(), gone = false))
        // A pain the same as the earlier one, unchanged, waits for the questions it still asks.
        val carried = asks(earlier = earlier, same = true, changed = false)
        assertEquals(
            PainSaveWait(PainWait.ANSWER, INTENSITY),
            painSaveWait(true, painCardWait(true, true, false), carried, PainFlow(), gone = false),
        )
    }

    /** At two words the other four are off, and the two ticked can still be taken away. */
    @Test
    fun twoWordsTurnTheOtherFourOff() {
        val two = listOf(PainType.ACHING.name, PainType.SHARP.name)
        val offered = PainType.entries.filter { painTypeOffered(two, it.name) }
        assertEquals(listOf(PainType.ACHING, PainType.SHARP), offered.sortedBy { it.ordinal })
        val one = listOf(PainType.ACHING.name)
        assertTrue(PainType.entries.all { painTypeOffered(one, it.name) })
        assertTrue(PainType.entries.all { painTypeOffered(emptyList(), it.name) })
    }

    /** Closing the list keeps carried words only while they are untouched and not yet taken. */
    @Test
    fun closingTheListKeepsOnlyUntouchedCarriedWords() {
        val carried = listOf(PainType.ACHING.name)
        assertTrue(painTypesKeepOffered(carried, touched = false, done = emptySet()))
        assertFalse(painTypesKeepOffered(carried, touched = true, done = emptySet()))
        assertFalse(painTypesKeepOffered(carried, touched = false, done = setOf(TYPES)))
        // Nothing carried, nothing to keep: the question waits for a tick.
        assertFalse(painTypesKeepOffered(emptyList(), touched = false, done = emptySet()))
    }
}
