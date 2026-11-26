package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys the home screen's bell remembers its advice by — [adviceNoticeKeys] — and the line round
 * the card in front, [progressLineStart]. Neither starts over for a reason that is not its own: a key
 * that carried the advice's place in the list would make every advice under a nutrition line that
 * leaves the top unread again, and the line does not go back to nothing whenever the figure under it
 * moves.
 */
class HomeNoticeKeysTest {
    private fun advice(topic: AdviceTopic, id: String) = Advice(
        topic = topic,
        level = AdviceLevel.HABIT,
        titleRes = 0,
        bodyRes = 0,
        sourceRes = 0,
        windowDays = 7,
        id = id,
    )

    @Test
    fun anAdviceKeepsItsKeyWhenTheOneAboveItLeaves() {
        val nutrition = advice(AdviceTopic.ENERGY, "nutrition-energy")
        val sleep = advice(AdviceTopic.SLEEP, "sleep-short")
        val labs = advice(AdviceTopic.LABS, "labs-sugar")

        val morning = adviceNoticeKeys(listOf(nutrition, sleep, labs))
        val afterLunch = adviceNoticeKeys(listOf(sleep, labs))

        assertEquals(morning.drop(1), afterLunch)
    }

    @Test
    fun twoAdvicesNeverShareAKey() {
        val context = advice(AdviceTopic.LABS, "labs-context-alt")

        val keys = adviceNoticeKeys(listOf(context, advice(AdviceTopic.LABS, "labs-sugar"), context))

        assertEquals(
            listOf("advice:LABS:labs-context-alt", "advice:LABS:labs-sugar", "advice:LABS:labs-context-alt:2"),
            keys,
        )
    }

    @Test
    fun theProgressLineStartsFromNothingOnlyOnTheSwipeThatLandsOnItsCard() {
        assertEquals(0f, progressLineStart(landed = false, drawn = 0.5f), 0f)
        assertEquals(0.5f, progressLineStart(landed = true, drawn = 0.5f), 0f)
    }
}
