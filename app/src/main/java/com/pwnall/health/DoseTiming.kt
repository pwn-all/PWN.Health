package com.pwnall.health

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Clock proposals from the person's words: a bare twelve-hour time retains both interpretations. */
internal data class DoseTiming(
    val minutes: List<Int> = emptyList(),
    val day: LocalDate? = null,
    val ambiguous: Boolean = false,
)

private const val CLOCK_CUES = "at|в|во|um|a las|alle|à|às|as|saat|o|v|ve|في|jam"
private const val MORNING = "a[.]?m[.]?|утра|утром|morning|in the morning|vormittags|morgens|du matin|" +
    "de la mañana|da manhã|di mattina|rano|ráno|sabah|pagi|صباحا|صباحًا|सुबह|上午|早上|午前|오전"
private const val EVENING = "p[.]?m[.]?|вечера|вечером|дня|днем|днём|afternoon|evening|in the evening|" +
    "in the afternoon|nachmittags|abends|du soir|de la tarde|de la noche|da tarde|da noite|" +
    "di sera|di pomeriggio|wieczorem|odpoledne|večer|akşam|sore|malam|مساء|مساءً|शाम|दोपहर|下午|晚上|午後|오후"
private val CLOCK_CUE = Regex("(?:^|[^\\p{L}])(?:$CLOCK_CUES)$SPACE*$", RegexOption.IGNORE_CASE)
private val CLOCK_VALUE = Regex("""(?<![0-9.,:])([01]?[0-9]|2[0-3])(?:[.:]([0-5][0-9]))?(?![0-9]|[.,:][0-9])""")

/**
 * What makes the number before it an amount or a count, no time: «в 2 раза больше», «в 3 приёма», "as 2 doses".
 * Every unit the dose reader takes an amount by ([doseTextsIn]) is one here too, with the pills and «шт» it does
 * not read and a part "of a tablet": with minutes behind its point a number is a time unless a unit says
 * otherwise, or "2.50 IU" would be ten to three, "0.25 of a tablet" a quarter past midnight. «ед» stands alone, so
 * «в 8.30 еду» keeps its time.
 */
private val CLOCK_UNIT =
    Regex(
        """^$SPACE*(?:of$SPACE+(?:an?$SPACE+)?)?""" +
            """(?:mg|мг|mcg|мкг|µg|ml|мл|g|г|iu|ме|units?|ед|tablets?|таблетк\p{L}*|""" +
            """capsules?|капсул\p{L}*|pills?|шт(?:ук\p{L}*)?|drops?|капл\p{L}*|капел\p{L}*|""" +
            """times|doses|раз\p{L}*|при[её]м\p{L}*)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
private val CLOCK_SUFFIX = Regex("""^$SPACE*(?:点|點|時|时|시|बजे)""")
private val AM_AFTER = Regex("^$SPACE*(?:$MORNING)(?!\\p{L})", RegexOption.IGNORE_CASE)
private val PM_AFTER = Regex("^$SPACE*(?:$EVENING)(?!\\p{L})", RegexOption.IGNORE_CASE)

/**
 * A period's word before the time is a word of its own: the «дня» that ends «сегодня» and the "am" that ends
 * "citalopram" are none. Only a Latin or a Cyrillic letter is barred before it: 昨天晚上8点 is written without spaces.
 */
private const val WORD_START = "(?<![A-Za-zÀ-ſА-яЁё])"

/** «дня» follows its hour, «в 3 дня». Before one it ends a count of days: «пью 3 дня в 8» says no half of the day. */
private const val NO_DAYS = "(?!дня)"
private val AM_BEFORE = Regex("$WORD_START(?:$MORNING)$SPACE*(?:(?:$CLOCK_CUES)$SPACE*)?$", RegexOption.IGNORE_CASE)
private val PM_BEFORE =
    Regex("$WORD_START$NO_DAYS(?:$EVENING)$SPACE*(?:(?:$CLOCK_CUES)$SPACE*)?$", RegexOption.IGNORE_CASE)

/**
 * Russian «дня» and «днём» are the daytime, not the afternoon: «в 11 дня» is 11:00 and «в 2 дня» 14:00. They stay
 * among the [EVENING] words, and an hour from seven to eleven said with one is the morning's.
 */
private const val DAYTIME = "дня|днем|днём"
private val DAYTIME_AFTER = Regex("^$SPACE*(?:$DAYTIME)(?!\\p{L})", RegexOption.IGNORE_CASE)
private val DAYTIME_BEFORE =
    Regex("$WORD_START$NO_DAYS(?:$DAYTIME)$SPACE*(?:(?:$CLOCK_CUES)$SPACE*)?$", RegexOption.IGNORE_CASE)
private val DAYTIME_MORNING_HOURS = 7..11

/**
 * «3 дня» is three days as well as three in the afternoon, and «пью нурофен 3 дня» read as 15:00 would date a dose
 * nobody timed. After two, three and four «дня» is the form a count of days takes, so there it names a time only
 * behind a word a count cannot follow: the clock's cue, «в 3 дня», or «около» and «к», after which days are «дней»
 * and «дням». Any other hour's «дня» is the daytime, as above: five days are «5 дней».
 */
private val DAY_COUNT_HOURS = 2..4
private val DAYS_AFTER = Regex("""^$SPACE*дня(?!\p{L})""", RegexOption.IGNORE_CASE)
private val TIME_ONLY_BEFORE = Regex("""(?<!\p{L})(?:около|к)$SPACE*$""", RegexOption.IGNORE_CASE)
private const val YESTERDAY_WORDS = "yesterday|вчера|gestern|ayer|hier|ontem|ieri|wczoraj|včera|dün|" +
    "kemarin|أمس|बीता कल|昨天|昨日|어제"
private const val TODAY_WORDS = "today|сегодня|heute|hoy|aujourd’hui|aujourd'hui|hoje|oggi|dzisiaj|dnes|" +
    "bugün|hari ini|اليوم|आज|今天|今日|오늘"
private val RELATIVE_DAY = Regex(
    "(?:^|[^\\p{L}])($YESTERDAY_WORDS|$TODAY_WORDS)(?!\\p{L})|昨天|今天|昨日|今日|어제|오늘",
    RegexOption.IGNORE_CASE,
)
private val YESTERDAY_WORD = Regex("(?:$YESTERDAY_WORDS)", RegexOption.IGNORE_CASE)
private const val MINUTES_PER_HOUR = 60
private const val HALF_DAY_HOURS = 12
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/** [bare] is for a model span already classified as CLOCK, never for arbitrary numbers in a message. */
internal fun doseTimingIn(said: String, referenceDay: LocalDate, bare: Boolean = false): DoseTiming {
    // «في ٨ مساءً» is eight in the evening: the clock is read in ASCII digits, as the tests read it.
    val text = said.asciiDigits()
    val clocks = CLOCK_VALUE.findAll(text).mapNotNull { match -> clockChoices(text, match, bare) }.toList()
    val relative = RELATIVE_DAY.find(text)?.let { it.groupValues[1].ifEmpty { it.value } }
    val day = relative?.let { referenceDay.minusDays(if (YESTERDAY_WORD.matches(it)) 1 else 0) }
    return DoseTiming(clocks.flatten().distinct(), day, clocks.any { it.size > 1 })
}

private enum class ClockPeriod { AM, PM, UNSPECIFIED }

private fun clockChoices(text: String, match: MatchResult, bare: Boolean): List<Int>? {
    val before = text.substring(0, match.range.first)
    val after = text.substring(match.range.last + 1)
    val hourText = match.groupValues[1]
    val minuteText = match.groupValues[2]
    val hour = hourText.toInt()
    // A count of days: its «дня» names no clock, so only what stands before the number, or its minutes, can.
    val days = hour in DAY_COUNT_HOURS && DAYS_AFTER.containsMatchIn(after) && !TIME_ONLY_BEFORE.containsMatchIn(before)
    if (!isClockWord(before, if (days) "" else after, minuteText.isNotEmpty(), bare)) return null
    val minute = minuteText.toIntOrNull() ?: 0
    return when (clockPeriod(before, after)) {
        ClockPeriod.AM -> if (hour in
            1..HALF_DAY_HOURS
        ) {
            listOf((hour % HALF_DAY_HOURS) * MINUTES_PER_HOUR + minute)
        } else {
            null
        }

        ClockPeriod.PM -> if (hour in 1..HALF_DAY_HOURS) {
            val daytime = DAYTIME_AFTER.containsMatchIn(after) || DAYTIME_BEFORE.containsMatchIn(before)
            val half = if (daytime && hour in DAYTIME_MORNING_HOURS) 0 else HALF_DAY_HOURS
            listOf((hour % HALF_DAY_HOURS + half) * MINUTES_PER_HOUR + minute)
        } else {
            null
        }

        ClockPeriod.UNSPECIFIED -> clockHalves(hour, minute, hourText.startsWith('0'))
    }
}

private fun isClockWord(before: String, after: String, hasMinutes: Boolean, bare: Boolean): Boolean {
    if (CLOCK_UNIT.containsMatchIn(after)) return false
    val prefix =
        CLOCK_CUE.containsMatchIn(before) || AM_BEFORE.containsMatchIn(before) || PM_BEFORE.containsMatchIn(before)
    val suffix =
        CLOCK_SUFFIX.containsMatchIn(after) || AM_AFTER.containsMatchIn(after) || PM_AFTER.containsMatchIn(after)
    val attachedBefore = before.lastOrNull()?.isLetter() == true && !prefix
    val attachedAfter = after.firstOrNull()?.isLetter() == true && !suffix
    val named = listOf(bare, prefix, suffix, hasMinutes).any { it }
    return named && !attachedBefore && !attachedAfter
}

private fun clockPeriod(before: String, after: String): ClockPeriod {
    val am = AM_AFTER.containsMatchIn(after) || AM_BEFORE.containsMatchIn(before)
    val pm = PM_AFTER.containsMatchIn(after) || PM_BEFORE.containsMatchIn(before)
    return when {
        am && !pm -> ClockPeriod.AM
        pm && !am -> ClockPeriod.PM
        else -> ClockPeriod.UNSPECIFIED
    }
}

private fun clockHalves(hour: Int, minute: Int, leadingZero: Boolean): List<Int> =
    if (hour == 0 || hour > HALF_DAY_HOURS || leadingZero) {
        listOf(hour * MINUTES_PER_HOUR + minute)
    } else {
        listOf(
            (hour % HALF_DAY_HOURS) * MINUTES_PER_HOUR + minute,
            (hour % HALF_DAY_HOURS + HALF_DAY_HOURS) * MINUTES_PER_HOUR + minute,
        )
    }

/** A date is stored with a confirmed clock choice; older minute-only answers remain valid. */
internal fun doseClockValue(day: LocalDate?, minute: Int): String = day?.let { "$it@$minute" } ?: minute.toString()

internal fun doseClockDay(value: String?): LocalDate? = value?.removePrefix("PICK:")?.takeIf { '@' in it }
    ?.substringBefore('@')
    ?.let { it.toLocalDateOrNull() }

internal fun doseClockMinute(value: String?): Int? = value?.removePrefix("PICK:")?.substringAfter('@')?.toIntOrNull()
    ?.takeIf { it in 0 until MINUTES_PER_DAY }

/** The clock picker changes the time while retaining the proposal's explicit day. */
internal fun pickedDoseClock(value: String, minute: Int): String = doseClockValue(doseClockDay(value), minute)

/** A wall-clock time on the confirmed day, including days on which the UTC offset changes. */
internal fun doseClockMoment(day: LocalDate, minute: Int, zone: ZoneId): Long =
    day.atTime(LocalTime.MIDNIGHT.plusMinutes(minute.toLong())).atZone(zone).toInstant().toEpochMilli()
