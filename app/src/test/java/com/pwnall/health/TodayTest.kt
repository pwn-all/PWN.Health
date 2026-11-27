package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The wait a screen puts itself to sleep for before re-reading the date.
 *
 * Measured between two `LocalDateTime`s — wall clock to wall clock — it would be an hour off on the
 * two nights a year the clocks move, since `delay` waits out real time.
 */
class TodayTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun hours(millis: Long) = millis / 3_600_000.0

    @Test
    fun aNightWhoseClocksGoForwardIsAnHourShorterThanItReads() {
        // 00:30 on the morning the clocks jump 02:00 → 03:00. The wall clock says 23.5 hours are
        // left of the day; 22.5 actually are, and sleeping the wall-clock figure would leave the
        // screen showing yesterday for an hour after midnight.
        val at = ZonedDateTime.of(2027, 3, 28, 0, 30, 0, 0, berlin)
        assertEquals(22.5, hours(millisUntilNextDay(at)), 0.0)
    }

    @Test
    fun aNightWhoseClocksGoBackIsAnHourLonger() {
        val at = ZonedDateTime.of(2027, 10, 31, 0, 30, 0, 0, berlin)
        assertEquals(24.5, hours(millisUntilNextDay(at)), 0.0)
    }

    /**
     * Santiago's September Sunday has no midnight in it — the clocks go from 23:59 to 01:00 — and
     * 01:00 is when the date changes there. Twelve hours from noon the day before, not thirteen:
     * the hour the zone skips is the same hour the offset gains back.
     */
    @Test
    fun aDayThatHasNoMidnightStartsWhenItActuallyStarts() {
        val at = ZonedDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneId.of("America/Santiago"))
        assertEquals(12.0, hours(millisUntilNextDay(at)), 0.0)
    }

    @Test
    fun anOrdinaryDayEndsAtMidnight() {
        val at = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        assertEquals(12.0, hours(millisUntilNextDay(at)), 0.0)
    }

    /** A wait of zero would spin: the tick would fire before the date it waited for had moved. */
    @Test
    fun theLastMillisecondOfADayStillWaits() {
        val at = ZonedDateTime.of(2026, 10, 17, 23, 59, 59, 999_999_999, berlin)
        assertEquals(1L, millisUntilNextDay(at))
    }

    /**
     * Health Connect is built from the stamp and not from the day column, so an answer given today
     * about an earlier day has to carry that day. Stamped with the wall clock it would leave the
     * phone dated today, and the importer rebuilds the day from the record's time — so a restore
     * would move the answers onto the day they happened to be typed.
     */
    @Test
    fun aRowFiledAgainstAnEarlierDayIsStampedInsideThatDay() {
        val day = LocalDate.now().minusDays(3)
        val stamped = java.time.Instant.ofEpochMilli(dayInstant(day.toString()))
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        assertEquals(day, stamped)
    }

    /**
     * Today keeps the moment: that is every write but a back-dated one. The date is read once and
     * handed over. Read here and again inside, a run across midnight would ask about a day the
     * function no longer took for today, and get its noon back.
     */
    @Test
    fun aRowFiledAgainstTodayKeepsTheWallClock() {
        val today = LocalDate.now()
        val before = System.currentTimeMillis()
        val stamp = dayInstant(today.toString(), today)
        assertTrue(stamp in before..System.currentTimeMillis())
    }

    /** Whose today it is decides: the same day is the wall clock to one caller and its own noon to the next. */
    @Test
    fun theCallersTodayIsTheOneADayIsHeldAgainst() {
        val day = LocalDate.of(2026, 9, 17)
        val noon = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(noon, dayInstant(day.toString(), today = day.plusDays(1)))
        assertEquals(noon, dayInstant(day.toString(), today = day.minusDays(1)))
        val before = System.currentTimeMillis()
        assertTrue(dayInstant(day.toString(), today = day) >= before)
    }

    /** A day column that cannot be parsed is no reason to lose the row. */
    @Test
    fun anUnreadableDayFallsBackToNow() {
        val before = System.currentTimeMillis()
        // Taken before the range is built, as the test above does. `x in a..b` is `(a..b).contains(x)`,
        // so the upper bound would be read first and the call measured after it: the parse failure
        // loads its exception and formats its message the first time it is hit, which is milliseconds,
        // and the stamp would then land past the end of a range closed before it was taken.
        val stamp = dayInstant("not a date")
        assertTrue(stamp in before..System.currentTimeMillis())
    }

    /**
     * The date read inside a plain `remember` is the date the screen opened on for as long as it
     * stays open: a lab form would file a result typed after midnight under yesterday, and the
     * doctor's sheet would end its window a day short. [rememberToday] is the one place that may hold
     * the date, because it reads it again. `rememberSaveable` is left out on purpose: the day a form
     * was opened for is a fact about that form, and the entry editor keeps one.
     */
    @Test
    fun noScreenKeepsTheDateItOpenedOn() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // A scan that read no file finds nothing either.
        assertTrue(sources.size > 50)
        val opening = Regex("""\bremember\s*(?:\([^{}]*\))?\s*\{""")
        val found = sources.filter { it.name != "Today.kt" }.flatMap { file ->
            val text = file.readText()
            opening.findAll(text).mapNotNull { block ->
                var depth = 1
                var end = block.range.last + 1
                while (depth > 0 && end < text.length) {
                    when (text[end]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    end++
                }
                val line = text.substring(0, block.range.first).count { it == '\n' } + 1
                "${file.name}:$line".takeIf { "LocalDate.now()" in text.substring(block.range.last + 1, end) }
            }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }
}
