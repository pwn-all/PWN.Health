package com.pwnall.health

/**
 * The five medals, in steps walked since the 1st of January.
 *
 * One ladder rather than two. A "40 000 in a day" badge answers a different question from a million
 * all-time, and a profile showing both would show eight discs where the only one anybody looks at is
 * the far end of the long climb.
 *
 * The climb restarts every New Year. A lifetime total only ever grows, so a lifetime ladder is
 * finished the day it is finished and the profile has nothing left to say; a year that empties on
 * the 1st of January is a race again. It also means the far rungs are meant to be hard — five
 * million inside one year is a shade under fourteen thousand steps every single day of it.
 */
val STEP_MEDALS = listOf(100_000L, 500_000L, 1_000_000L, 3_000_000L, 5_000_000L)

data class Achievement(
    val threshold: Long,
    val earned: Boolean,
    /** 0f..1f towards [threshold]; 1f once earned. */
    val progress: Float,
    /** The day the running total tipped over [threshold], or null while it has not. */
    val earnedOnDay: String? = null,
)

/**
 * Badges are derived, never stored: the day rows are the single source of truth, so an edited or
 * deleted day cannot leave a badge behind that the steps no longer support — and a year that has
 * turned takes its medals with it without anything having to go and clear them.
 */
fun stepAchievements(days: List<DaySteps>, year: Int): List<Achievement> {
    // The year is passed in rather than read from the clock, so this stays a pure function the
    // tests can walk through a New Year without waiting for one.
    val prefix = "$year-"
    val ordered = days.filter { it.day.startsWith(prefix) }.sortedBy(DaySteps::day)
    val walked = ordered.sumOf(DaySteps::steps)

    // The day a medal was earned is the day the running total crossed it, which is why the rows
    // are walked in date order rather than simply summed: a backup restored out of order would
    // otherwise date every medal to whichever row happened to be last in the list.
    var running = 0L
    val crossedOn = mutableMapOf<Long, String>()
    ordered.forEach { row ->
        val before = running
        running += row.steps
        STEP_MEDALS.forEach { threshold ->
            if (before < threshold && running >= threshold) crossedOn[threshold] = row.day
        }
    }

    return STEP_MEDALS.map { threshold ->
        Achievement(
            threshold = threshold,
            earned = walked >= threshold,
            progress = progressTowards(walked, threshold),
            earnedOnDay = crossedOn[threshold],
        )
    }
}

private fun progressTowards(value: Long, threshold: Long): Float =
    if (threshold <= 0L) 1f else (value.toDouble() / threshold).coerceIn(0.0, 1.0).toFloat()

/** "100k", "1M", "5M" — the badge threshold as a short label. */
fun badgeLabel(threshold: Long): String = when {
    threshold >= 1_000_000L && threshold % 1_000_000L == 0L -> "${threshold / 1_000_000L}M"
    threshold >= 1_000L && threshold % 1_000L == 0L -> "${threshold / 1_000L}k"
    else -> threshold.toString()
}
