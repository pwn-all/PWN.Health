package com.pwnall.health

/**
 * Permission is sampled once at touch-down, never earned by reaching the top mid-gesture — and not
 * by a touch that lands just as the page gets there either. Flicking a long page back to its top is
 * a run of throws, and the throw that carries it the last stretch ends a moment before the next one
 * lands: that next one is still the scrolling, not a pull at a page seen to be resting. The page
 * has to have stood still for [settleMs] before a touch may start closing it, and a touch that lands
 * sooner starts the wait over — a thumb still throwing at a page already at its top is still
 * scrolling, however many throws it takes to stop.
 */
internal class SwipeCloseGesture(private val settleMs: Long = PANEL_SETTLE_MS) {
    private var startedAtTop = false
    private var scrolled = false

    /** When the content last moved or was last thrown at too soon, on [begin]'s clock. Never, to begin with. */
    private var busyAt: Long? = null

    val canClose: Boolean get() = startedAtTop && !scrolled

    fun begin(atTop: Boolean, now: Long) {
        val settled = busyAt.let { it == null || now - it >= settleMs }
        startedAtTop = atTop && settled
        if (!settled) busyAt = now
        scrolled = false
    }

    /** Includes inertial scroll and movement that happens while another finger stops a fling. */
    fun contentMoved(consumed: Float, now: Long) {
        if (consumed != 0f) {
            scrolled = true
            busyAt = now
        }
    }
}

/**
 * How long a page has to rest at its top before a pull may shut its panel. Longer than the gap
 * between two throws of a thumb flicking a page back up, shorter than looking at the top and then
 * deciding to pull.
 */
internal const val PANEL_SETTLE_MS = 500L
