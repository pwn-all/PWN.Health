package com.pwnall.health

/**
 * Every id the app posts a notification under.
 *
 * Android keeps one notification per id and tag: a feature that takes an id already in use is not
 * refused, it replaces the other's notice in the shade without a word. Literals spread over several
 * files, kept apart by comments that each list "the others", collide as soon as one list misses a
 * number. A new one is added here, beside all the rest, and NotificationIdsTest reads
 * this object and fails on a number given out twice.
 *
 * Only the numbers live here. Each feature keeps its own channel, importance, sound and permission
 * check, because those differ: an alarm that rings, a quiet nudge and a foreground service's notice
 * have nothing to share but the fact of being posted.
 */
internal object NotificationIds {
    /** The step service's foreground notification, which is the route's too while one records. */
    const val STEPS = 1

    /** The hourly worker's nudges take this plus [ReminderKind.ordinal], 100 to 104: see [reminder]. */
    const val REMINDER_BASE = 100

    /** A piece of health advice from the hourly worker. */
    const val ADVICE = 200

    /** A dose that is due. One notification per dose: the dose's key is the tag beside this id. */
    const val MEDICINE_DOSE = 300

    /** Tomorrow's visit to a doctor. */
    const val VISIT = 301

    /** The workout planned for today, on its morning. */
    const val PLANNED_WORKOUT = 302

    /** Nothing eaten for four hours, and nothing drunk for two. */
    const val MEAL = 400
    const val WATER = 401

    /** The work timer left running: the question whether to stop it. */
    const val WORK_OVERRUN = 500

    /** The automatic start's and stop's notice at the workplace: one at a time, a stop replacing its start. */
    const val WORK_PLACE_NOTICE = 501

    /** The workplace check's own foreground notification, gone with the check. */
    const val WORK_PLACE_CHECK = 502

    /** A long sitting in a feed: rest the eyes, and put the phone down. */
    const val FEED_EYES = 600
    const val FEED_BRAIN = 601

    /** The id one of the hourly worker's nudges is posted under. */
    fun reminder(kind: ReminderKind): Int = REMINDER_BASE + kind.ordinal
}
