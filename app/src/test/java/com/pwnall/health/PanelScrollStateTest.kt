package com.pwnall.health

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelScrollStateTest {
    @Test
    fun livePositionsIncludeRestoredAndProgrammaticScrollsWithoutDeltaHistory() {
        val panel = PanelScrollState()
        val list = TestScroll(canScrollBackward = true)
        panel.register(list)
        assertFalse(panel.canStartClose)
        list.canScrollBackward = false
        assertTrue(panel.canStartClose)
        list.canScrollBackward = true
        assertFalse(panel.canStartClose)
    }

    @Test
    fun aNewTouchAtTopCanDismissEvenWhileThePreviousFlingIsSettling() {
        val panel = PanelScrollState()
        val list = TestScroll(isScrollInProgress = true)
        panel.register(list)
        assertTrue(panel.canStartClose)
        list.canScrollBackward = true
        assertFalse(panel.canStartClose)
        list.canScrollBackward = false
        list.isScrollInProgress = false
        assertTrue(panel.canStartClose)
    }

    @Test
    fun removingAPageRemovesItsScrollPositionAndNestedPanelsStayIndependent() {
        val outer = PanelScrollState()
        val inner = PanelScrollState()
        val oldPage = TestScroll(canScrollBackward = true)
        outer.register(oldPage)
        assertFalse(outer.canStartClose)
        assertTrue(inner.canStartClose)
        outer.unregister(oldPage)
        assertTrue(outer.canStartClose)
    }

    @Test
    fun closingAChildOnlyReleasesItsOwnParent() {
        val report = PanelScrollState()
        val doctorPicker = PanelScrollState()
        val nestedPicker = PanelScrollState()
        report.registerChild(doctorPicker)
        doctorPicker.registerChild(nestedPicker)

        assertTrue(report.canStartClose)
        assertTrue(report.hasOpenChild)
        assertTrue(doctorPicker.hasOpenChild)
        assertFalse(nestedPicker.hasOpenChild)

        doctorPicker.unregisterChild(nestedPicker)
        assertTrue(report.hasOpenChild)
        assertFalse(doctorPicker.hasOpenChild)

        report.unregisterChild(doctorPicker)
        assertFalse(report.hasOpenChild)
    }

    @Test
    fun removingOneChildDoesNotUnlockAPanelWithAnotherChild() {
        val report = PanelScrollState()
        val first = PanelScrollState()
        val second = PanelScrollState()
        report.registerChild(first)
        report.registerChild(second)
        report.unregisterChild(first)
        assertTrue(report.hasOpenChild)
        report.unregisterChild(second)
        assertFalse(report.hasOpenChild)
    }

    @Test
    fun everyScrollableBodyMustBeAtTheTop() {
        val panel = PanelScrollState()
        panel.register(TestScroll())
        val lowerList = TestScroll(canScrollBackward = true)
        panel.register(lowerList)
        assertFalse(panel.canStartClose)
        lowerList.canScrollBackward = false
        assertTrue(panel.canStartClose)
    }

    /**
     * Two pages holding one panel open against the pull: it stays
     * held until the last lets go; a key that holds nothing is let go of harmlessly.
     */
    @Test
    fun holdsNestAndAnUnknownReleaseIsHarmless() {
        val panel = PanelScrollState()
        val chat = Any()
        val page = Any()
        panel.hold(chat)
        panel.hold(page)
        panel.release(chat)
        assertTrue(panel.swipeCloseHeld)
        assertFalse(panel.closesBySwipe)
        panel.release(Any())
        assertTrue(panel.swipeCloseHeld)
        panel.release(page)
        assertFalse(panel.swipeCloseHeld)
        assertTrue(panel.closesBySwipe)
        // The same key twice is one hold: one release lets it go.
        panel.hold(chat)
        panel.hold(chat)
        panel.release(chat)
        assertTrue(panel.closesBySwipe)
    }

    /** A panel no page holds closes by the pull on its own rule: only while no panel stands open over it. */
    @Test
    fun aPanelWithNoHoldClosesBySwipe() {
        val panel = PanelScrollState()
        assertFalse(panel.swipeCloseHeld)
        assertTrue(panel.closesBySwipe)
        assertTrue(panel.canStartClose)
        val child = PanelScrollState()
        panel.registerChild(child)
        assertFalse(panel.closesBySwipe)
        panel.unregisterChild(child)
        assertTrue(panel.closesBySwipe)
        // A hold is its own panel's: the panel under it closes, or not, by its own rule.
        child.hold(Any())
        assertTrue(panel.closesBySwipe)
        assertFalse(child.closesBySwipe)
        // A hold does not touch where the scrolls stand.
        panel.hold(Any())
        assertTrue(panel.canStartClose)
    }

    private class TestScroll(
        override var canScrollBackward: Boolean = false,
        override var isScrollInProgress: Boolean = false,
    ) : ScrollableState {
        override fun dispatchRawDelta(delta: Float): Float = 0f

        override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) = Unit
    }
}
