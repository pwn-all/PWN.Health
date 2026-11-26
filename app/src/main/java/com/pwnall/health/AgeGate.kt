package com.pwnall.health

import java.time.LocalDate
import java.time.Period

/**
 * How old the person using the app said they are, and what that means the app may show them.
 *
 * The age comes from the date turned to on the onboarding age screen, the first question the app
 * asks, whose wheels open on a blank row: it is a question, not a form with the answer already in
 * it. What it decides is whether three parts of the app exist at all — not whether they are locked,
 * greyed out or explained away. A door that is closed but visible tells a child exactly which lie
 * to tell; the doors below are not rendered, their screens are unreachable, and their widget is
 * switched off in the launcher.
 *
 * Two thresholds, and neither is arbitrary:
 *
 *  - [SEX_LOG_MIN_AGE] is where the app stops guessing about sexual activity: below it the
 *    intimate screen is gone rather than showing a sentence about being sixteen.
 *  - [HABITS_MIN_AGE] is alcohol and tobacco, held at the age at which either is lawful to buy
 *    across most of where this app ships. Neither is something to hand a twelve-year-old a
 *    one-tap button for, and the home-screen widget is exactly that.
 *
 * Everything else — steps, food, sleep, mood, the cycle, the labs, the medicine cabinet — is the
 * same app at every age, which is the point. This file exists so that the app can honestly be
 * offered to everyone rather than declared adults-only.
 */
const val SEX_LOG_MIN_AGE = 16

/** Alcohol and tobacco: the age at which buying either is lawful across most of this app's shelf. */
const val HABITS_MIN_AGE = 18

/**
 * Whole years old on [day], from an ISO birth date.
 *
 * Whole years, not a subtraction of years: somebody born in December is twelve until December,
 * whatever the calendar says about the year. Anything unreadable — a truncated import, a date in
 * the future — comes back as zero, so a gate built on this closes rather than opens when it cannot
 * tell.
 */
fun ageOn(day: String, birthDate: String): Int {
    val born = birthDate.toLocalDateOrNull() ?: return 0
    val on = day.toLocalDateOrNull() ?: return 0
    return Period.between(born, on).years.coerceIn(0, 120)
}

/** Whether the app offers to record sexual activity at all. */
fun sexLogOffered(birthDate: String?, today: LocalDate): Boolean =
    birthDate != null && ageOn(today.toString(), birthDate) >= SEX_LOG_MIN_AGE

/** Whether the app offers alcohol and tobacco — the screen, the add-sheet door and the widget. */
fun habitsOffered(birthDate: String?, today: LocalDate): Boolean =
    birthDate != null && ageOn(today.toString(), birthDate) >= HABITS_MIN_AGE
