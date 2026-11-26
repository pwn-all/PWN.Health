package com.pwnall.health

import kotlin.math.roundToInt

/**
 * What the verdict line under a comparison on a pattern card says — pure, so which words a verdict
 * gets has a test of its own. The words themselves are [VerdictLine]'s.
 */
internal enum class ContrastLine {
    /** A group is under the floor: the counts and the floor, nothing else. */
    NOT_ENOUGH,

    /** "No clear difference yet" — never "no effect". */
    NO_CLEAR_DIFFERENCE,

    /** Every gate passed, worded for how the two groups were drawn and in no order. */
    NOTABLE,

    /** A lagged comparison passed, and its reverse was weighed and found quiet: "on the days after". */
    NOTABLE_DAYS_AFTER,

    /** A lagged comparison passed, and its reverse showed it too: no order can be told. */
    NOTABLE_NO_ORDER,

    /** A lagged comparison passed, and its reverse had too few days to be weighed: no order can be told yet. */
    NOTABLE_REVERSE_UNCHECKED,
}

/** The line for a comparison of two groups of days on the same days, or of days drawn some other way. */
internal fun contrastLine(contrast: DayContrast): ContrastLine = when (contrast.verdict) {
    ContrastVerdict.NOT_ENOUGH_DAYS -> ContrastLine.NOT_ENOUGH
    ContrastVerdict.NO_CLEAR_DIFFERENCE -> ContrastLine.NO_CLEAR_DIFFERENCE
    ContrastVerdict.NOTABLE -> ContrastLine.NOTABLE
}

/**
 * The line for a lagged comparison. "On the days after" only when [LaggedContrast.directed] allows
 * it; a forward pairing that passed while its reverse did not stay quiet is a difference in no
 * order the record can show — said apart for a reverse that showed it too and one that was never
 * weighed, since a control that was not run found nothing either way.
 */
internal fun contrastLine(lagged: LaggedContrast<*>): ContrastLine = when {
    lagged.directed -> ContrastLine.NOTABLE_DAYS_AFTER
    !lagged.forward.notable -> contrastLine(lagged.forward)
    lagged.reverse.verdict == ContrastVerdict.NOT_ENOUGH_DAYS -> ContrastLine.NOTABLE_REVERSE_UNCHECKED
    else -> ContrastLine.NOTABLE_NO_ORDER
}

/** How many days each group held, for "Not enough days yet: 4 and 31". */
internal val DayContrast.groupSizes: Pair<Int, Int>
    get() = when (this) {
        is DayShareContrast -> exposed.of to other.of
        is DayMeanContrast -> exposed.days to other.days
    }

/** A card's share line in whole points, as the app's own rule states it: fifteen, not 0.15. */
internal val CARD_POINTS: Int = (CARD_SHARE_POINTS * 100).roundToInt()
