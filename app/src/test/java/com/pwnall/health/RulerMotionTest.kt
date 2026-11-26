package com.pwnall.health

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RulerMotionTest {
    @Test
    fun zeroTerminalVelocityUsesRecentMovementButNotAHold() {
        val velocity = RulerDragVelocity()
        velocity.reset(1_000)
        velocity.record(-200f, 1_040)
        velocity.record(-200f, 1_080)
        assertEquals(-5_000f, velocity.released(0f, 1_090), 0.1f)
        assertEquals(0f, velocity.released(0f, 1_300), 0f)
        assertEquals(300f, velocity.released(300f, 1_090), 0f)
        velocity.reset(2_000)
        assertEquals(0f, velocity.released(0f, 2_000), 0f)
    }

    @Test
    fun strongThrowsKeepTheirDirectionAndCoastUntilStopped() {
        assertTrue(rulerCoastSpeed(60f, 30f, true) > 0f)
        assertTrue(rulerCoastSpeed(-60f, 30f, true) < 0f)
        assertTrue(rulerCoastSpeed(120f, 2f, true) > rulerCoastSpeed(30f, 2f, true))
        assertTrue(abs(rulerCoastSpeed(20f, 8f, false)) < 0.5f)
        assertTrue(rulerCoastSpeed(10_000f, 0f, true) <= 180f)
    }

    @Test
    fun flingAdvancesAcrossFramesAndTouchStopsIt() = runBlocking {
        val clock = BroadcastFrameClock()
        val picked = mutableListOf<Int>()
        val motion = RulerMotion(50, 500, CoroutineScope(coroutineContext + clock), picked::add)
        motion.fling(60f, true)
        yield()
        repeat(100) { frame ->
            clock.sendFrame(frame * 16_000_000L)
            yield()
        }
        assertTrue(motion.position > 65f)
        assertTrue(motion.moving)
        motion.stop()
        yield()
        val stopped = motion.position
        repeat(50) { frame ->
            clock.sendFrame((100 + frame) * 16_000_000L)
            yield()
        }
        assertEquals(stopped, motion.position, 0f)
        assertTrue(!motion.moving)
    }

    @Test
    fun fractionalDragReportsOnlyTheVisibleSelectionAndStopsAtEitherEnd() {
        val picked = mutableListOf<Int>()
        val motion = RulerMotion(5, 10, CoroutineScope(Dispatchers.Unconfined), picked::add)
        motion.stop()
        motion.drag(0.3f)
        assertTrue(picked.isEmpty())
        motion.drag(0.3f)
        assertEquals(listOf(6), picked)
        motion.drag(100f)
        motion.drag(100f)
        motion.drag(-200f)
        assertEquals(listOf(6, 10, 0), picked)
        assertEquals(0f, motion.position, 0f)
    }

    /**
     * A coasting ruler passes a value a frame. Whether sound is on is a call into the system server,
     * and it is asked once a gap rather than once a value — on a silenced phone as well, where no
     * tick ever sounds for the gap to be counted from.
     */
    @Test
    fun theTickAsksWhetherSoundIsOnOnceAGapAndNotOnceAValue() {
        for (on in listOf(true, false)) {
            var asked = 0
            val gate = RulerTickGate {
                asked++
                on
            }
            val ticks = (10_000L until 10_100L).count { gate.opens(it) }
            assertEquals(4, asked)
            assertEquals(if (on) 4 else 0, ticks)
        }
    }

    @Test
    fun externalValueCanSyncAtRestButCannotResetAnOngoingDrag() {
        val motion = RulerMotion(5, 10, CoroutineScope(Dispatchers.Unconfined)) { }
        motion.sync(7)
        assertEquals(7f, motion.position, 0f)
        motion.stop()
        motion.drag(0.25f)
        motion.sync(7)
        assertEquals(7.25f, motion.position, 0f)
        motion.fling(0f, false)
        motion.sync(8)
        assertEquals(8f, motion.position, 0f)
    }

    /**
     * A page that stops being shown halts its ruler with no finger on it. Halted the way a touch
     * stops it, the ruler would stay held for a drag nobody was making, and after Next and a quick
     * Back the steps page's suggestion would change the figure and leave the strip where it was.
     */
    @Test
    fun aRulerHaltedWithNoFingerOnItStillTakesTheNextValue() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = RulerMotion(5, 500, CoroutineScope(coroutineContext + clock)) { }
        motion.fling(60f, true)
        yield()
        repeat(10) { frame ->
            clock.sendFrame(frame * 16_000_000L)
            yield()
        }
        motion.halt()
        yield()
        motion.sync(7)
        assertEquals(7f, motion.position, 0f)
        assertTrue(!motion.moving)
    }
}
