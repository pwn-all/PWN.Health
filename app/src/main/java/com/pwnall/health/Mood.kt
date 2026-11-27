package com.pwnall.health

import java.time.LocalDate
import kotlin.math.abs

const val KIND_MOOD = "MOOD"

/**
 * How pleasant, on five points. The stored value is [score], so the labels can change freely.
 *
 * This is one of two questions, not one question about everything: tension is [Energy]'s. A second
 * point on this ladder labelled "nervous" — a word about tension sitting inside an order about
 * pleasantness — would score an energetic anxious day and a flat grey one the same, and any
 * arithmetic over the scale would average two dimensions that move in opposite directions. A stored
 * 2 means near the unpleasant end.
 */
enum class Mood(
    val score: Int,
    val titleRes: Int,
    /** One line under the title on the picker: what this option actually feels like. */
    val descriptionRes: Int,
    /** The widget's one-glyph face. The picker draws its vector face ([MorphingMoodFace]). */
    val emoji: String,
) {
    BAD(1, R.string.mood_bad, R.string.mood_desc_bad, "😞"),
    LOW(2, R.string.mood_low, R.string.mood_desc_low, "🙁"),
    NEUTRAL(3, R.string.mood_neutral, R.string.mood_desc_neutral, "😐"),
    GOOD(4, R.string.mood_good, R.string.mood_desc_good, "🙂"),
    FUN(5, R.string.mood_fun, R.string.mood_desc_fun, "😄"),
    ;

    companion object {
        fun fromScore(score: Int?): Mood? = entries.firstOrNull { it.score == score }
    }
}

/**
 * How wound up or how settled, on the same five points, asked separately and optionally.
 *
 * The axis psychology calls calmness, and the reason it is here: Olfermann et al. 2026 (IJBNPA
 * 23:70, n=65, three one-week bursts) found physical activity raised energetic arousal (β 4.88) and
 * pleasantness (β 1.27) while *lowering* calmness (β −1.86). One ladder cannot hold both, and an
 * app that tries reports the average of two things going opposite ways.
 *
 * Optional on purpose. A scale somebody has to answer twice is a scale they stop answering.
 */
enum class Energy(
    val score: Int,
    val titleRes: Int,
    val descriptionRes: Int,
    val emoji: String,
) {
    WOUND_UP(1, R.string.energy_wound_up, R.string.energy_desc_wound_up, "😰"),
    RESTLESS(2, R.string.energy_restless, R.string.energy_desc_restless, "😬"),
    STEADY(3, R.string.energy_steady, R.string.energy_desc_steady, "🙂"),
    CALM(4, R.string.energy_calm, R.string.energy_desc_calm, "😌"),
    SETTLED(5, R.string.energy_settled, R.string.energy_desc_settled, "😴"),
    ;

    companion object {
        fun fromScore(score: Int?): Energy? = entries.firstOrNull { it.score == score }
    }
}

enum class MoodState { NO_DATA, LOW, TENSE, POOR, BALANCED, ELEVATED }

/** Below this there is no week to read, only a moment. */
private const val MIN_ENTRIES_FOR_STATE = 3
private const val MIN_DAYS_FOR_STATE = 2

/** The share of a week's energy answers at the wound-up end before the word is used at all. */
internal const val TENSE_SHARE = 0.4

/**
 * The bands below are written on their cut-offs, but the rescaling reaches those cut-offs through a
 * division: a week whose wellbeing is exactly 28 comes back as 28.000000000000004, so `<= 28` would
 * hand the person the band above the one the guidance names — and which band, for identical days,
 * would depend on the order the rows were summed in. The window is far narrower than anything the
 * scale can produce: with at most a handful of entries a day, neighbouring values are thousandths
 * of a point apart, so only noise is caught.
 */
private const val BAND_EPSILON = 1e-9

/**
 * Mood does not change every minute, and a scale you can tap a thousand times an hour records
 * tapping, not mood. Four hours gives roughly a morning, an afternoon and an evening reading.
 */
const val MOOD_COOLDOWN_MS = 4 * 60 * 60 * 1_000L

/**
 * Milliseconds until the next entry is allowed; zero means now.
 *
 * The window is counted on both sides of the entry, as [isDuplicateTap] counts its own. An entry
 * stamped a little later than `nowMs` is still an entry of this window: the chat files a mood under
 * the moment its question was asked, and the widget may have been tapped since. One stamped a whole
 * window or more ahead is not — it was written while the clock ran ahead, or restored from a phone
 * whose clock did — and counted as it stands, it would hold the scale shut, without a word on the
 * screen, until the clock caught up with it and for four hours after that: a day and more for a
 * clock that had been a day out.
 *
 * So the figure never reads longer than the window, and no stamp shuts the scale for longer than
 * two of them: one that lies ahead answers the whole window from four hours before its moment, and
 * counts the four hours down after it.
 *
 * A stamp further ahead than that is not the probes' to find at all ([lastMoodNear]). Asked for the
 * newest entry on file, such a stamp, being the newest, would hide the entries before it: the pause a
 * real entry had started would not be kept until the clock came within a window of the stray.
 */
fun moodCooldownLeftMs(lastEntryAtMs: Long?, nowMs: Long): Long {
    val last = lastEntryAtMs ?: return 0L
    if (last - nowMs >= MOOD_COOLDOWN_MS) return 0L
    return (last + MOOD_COOLDOWN_MS - nowMs).coerceIn(0L, MOOD_COOLDOWN_MS)
}

/** The pause's minutes as the mood page prints them: rounded up, so its last minute reads as one. */
internal fun moodPauseMinutes(leftMs: Long): Long = (leftMs + MINUTE_MS - 1) / MINUTE_MS

/**
 * How long the mood page's countdown may sleep: until the minute it prints next turns over, which is
 * the only change on the screen. Waking every second would redraw the page for nothing, four hours
 * long. With no pause running it looks again within the minute, for an entry stamped ahead of
 * the clock that the clock has come within a pause of.
 */
internal fun moodCountdownWakeMs(leftMs: Long): Long =
    if (leftMs <= 0L) MINUTE_MS else leftMs - (moodPauseMinutes(leftMs) - 1) * MINUTE_MS

private const val MINUTE_MS = 60_000L

/** The moment the pause [lastEntryAtMs] started is over, or null where no pause is running at [nowMs]. */
internal fun moodPauseEndsAt(lastEntryAtMs: Long?, nowMs: Long): Long? =
    lastEntryAtMs?.plus(MOOD_COOLDOWN_MS)?.takeIf { moodCooldownLeftMs(lastEntryAtMs, nowMs) > 0L }

/**
 * The newest mood within one pause either side of [atMs], which is every entry
 * [moodCooldownLeftMs] can answer for at that moment. The one question the scale's door, the mood
 * page and the widget ask, so that the three cannot come to ask it differently.
 */
internal suspend fun StepsDao.lastMoodNear(atMs: Long): Long? =
    lastEntryBetweenMs(KIND_MOOD, atMs - MOOD_COOLDOWN_MS, atMs + MOOD_COOLDOWN_MS)

/**
 * [lastMoodNear]'s window, asked by the chat for the row itself: by its page ([ChatEnv.lastMood]) and,
 * through the port, by its door. Asked with no far side, a mood stamped a day ahead would be the
 * answer, which [moodCooldownLeftMs] lets through, while the mood of an hour ago went unasked.
 */
internal suspend fun StepsDao.moodNear(atMs: Long): JournalEntry? =
    latestEntryBetween(KIND_MOOD, atMs - MOOD_COOLDOWN_MS, atMs + MOOD_COOLDOWN_MS)

/** [StepsDao.moodNear] through the port the chat's doors are written against. */
internal suspend fun ChatDbPort.moodNear(atMs: Long): JournalEntry? =
    latestEntryBetween(KIND_MOOD, atMs - MOOD_COOLDOWN_MS, atMs + MOOD_COOLDOWN_MS)

private fun percentOf(average: Double) = (average - 1.0) / 4.0 * 100.0

data class MoodSummary(
    val entries: Int,
    /** Mean score on the 1..5 scale. */
    val average: Double,
    /** Mean rescaled to 0..100, the shape WHO-5 wellbeing scores are reported in. */
    val wellbeingPercent: Double,
    /**
     * Mean of the daily shares of entries at the wound-up end of [Energy] — a day, not a tap.
     *
     * Zero where the question was never answered, which is not the same as calm. [tenseDays] says
     * which of the two it is, and nothing is called tense on the strength of an unasked question.
     */
    val tenseShare: Double,
    /** How many days carry an energy answer at all. */
    val tenseDays: Int,
    val state: MoodState,
    val suggestBreathing: Boolean,
)

/**
 * The share of the days somebody answered the second dial on that came back wound up.
 *
 * Tension comes off [Energy], and only from the days it was actually asked. Read off the second rung
 * of the pleasantness ladder, as if it said "nervous", a flat grey day and a keyed-up one would be
 * the same entry, and a week of neither could be called tense because somebody had had two mildly
 * bad afternoons. Day-weighted like the mean beside it: three wound-up
 * taps on one hard day are one wound-up day, not three sevenths of a week.
 */
private fun tenseShareOf(energyDays: Map<String, List<JournalEntry>>): Double {
    if (energyDays.isEmpty()) return 0.0
    return energyDays.values
        .map { day -> day.count { (it.energyScore ?: 0) <= Energy.RESTLESS.score }.toDouble() / day.size }
        .average()
}

/**
 * Summarises self-reported mood over the entries handed in (the UI passes the last seven days).
 *
 * The 1..5 mean is rescaled to 0..100 because that is the range the WHO-5 Well-Being Index is
 * reported in, which is where the two cut-offs come from: under 50 marks poor wellbeing and 28 is
 * the point at which WHO-5 guidance suggests looking further. This is *not* a WHO-5 questionnaire —
 * it is a single-item scale borrowing its bands, so the result is a hint for the person, never a
 * diagnosis. The UI must say so.
 */
fun moodSummary(entries: List<JournalEntry>): MoodSummary {
    val scored = entries.filter { (it.moodScore ?: 0) in 1..5 }
    val scores = scored.mapNotNull { it.moodScore }
    val byDay = scored.groupBy { it.day }

    // A week is a week, not a tally of taps: five entries on a bad Tuesday must not outvote the
    // four ordinary days around it, so each day contributes one number.
    val dayMeans = byDay.values.map { day -> day.mapNotNull { it.moodScore }.average() }

    // One tap is not a trend. Naming a state from a single entry would produce "persistently low" from
    // one bad moment, which is both wrong and the kind of wrong that a person takes to heart.
    if (scores.size < MIN_ENTRIES_FOR_STATE || dayMeans.size < MIN_DAYS_FOR_STATE) {
        val mean = if (dayMeans.isEmpty()) 0.0 else dayMeans.average()
        return MoodSummary(
            entries = scores.size,
            average = mean,
            wellbeingPercent = if (dayMeans.isEmpty()) 0.0 else percentOf(mean),
            tenseShare = 0.0,
            tenseDays = 0,
            state = MoodState.NO_DATA,
            suggestBreathing = false,
        )
    }

    val average = dayMeans.average()
    val percent = percentOf(average)
    val energyDays = entries.filter { (it.energyScore ?: 0) in 1..5 }.groupBy { it.day }
    val tenseShare = tenseShareOf(energyDays)
    val band = wellbeingBand(percent)
    val state = when {
        band == MoodState.LOW -> band
        energyDays.size >= MIN_DAYS_FOR_STATE && tenseShare >= TENSE_SHARE -> MoodState.TENSE
        else -> band
    }
    return MoodSummary(
        entries = scores.size,
        average = average,
        wellbeingPercent = percent,
        tenseShare = tenseShare,
        tenseDays = energyDays.size,
        state = state,
        suggestBreathing = state in setOf(MoodState.LOW, MoodState.TENSE, MoodState.POOR),
    )
}

/**
 * The band the wellbeing figure alone falls under, on the cut-offs [moodSummary] explains, before
 * the second dial is asked about. The doctor's sheet prints this beside the average and gives
 * tension a row of its own, where the card folds both into one word.
 *
 * The lower band takes its line in and the one above it does not. WHO-5 calls a raw score under 13
 * of 25 poor wellbeing, which is 48 and under: its scale has no 50 to rule on. This one has, and it
 * is the answer "Neutral" — the middle of the dial — so with that line taken in, a week of nothing
 * else would be told it was below average, in the colour of a warning, and offered breathing for
 * entries that "have been low".
 */
internal fun wellbeingBand(percent: Double): MoodState = when {
    percent <= 28.0 + BAND_EPSILON -> MoodState.LOW
    percent < 50.0 - BAND_EPSILON -> MoodState.POOR
    percent >= 75.0 - BAND_EPSILON -> MoodState.ELEVATED
    else -> MoodState.BALANCED
}

/**
 * The line on the card that opens the breathing practice. A tense week is told it was tense: it
 * does not share the low weeks' sentence, which would say the entries "have been low" of a week whose
 * mood read 70% and whose second dial was the one that had spoken.
 */
fun MoodSummary.breathingHintRes(): Int = when {
    !suggestBreathing -> R.string.breathing_body
    state == MoodState.TENSE -> R.string.breathing_suggestion_tense
    else -> R.string.breathing_suggestion
}

/**
 * How many days among [entries] carry at least one answer at the wound-up end of [Energy] — the
 * same two rungs [tenseShareOf] counts. A count rather than a share, so a sheet can print it beside
 * the days the dial was answered on, and never a percentage without the two figures behind it.
 */
internal fun woundUpDays(entries: List<JournalEntry>): Int = entries
    .filter { (it.energyScore ?: 0) in 1..Energy.RESTLESS.score }
    .map(JournalEntry::day)
    .distinct()
    .size

fun MoodState.titleRes(): Int = when (this) {
    MoodState.NO_DATA -> R.string.mood_state_no_data
    MoodState.LOW -> R.string.mood_state_low
    MoodState.TENSE -> R.string.mood_state_tense
    MoodState.POOR -> R.string.mood_state_poor
    MoodState.BALANCED -> R.string.mood_state_balanced
    MoodState.ELEVATED -> R.string.mood_state_elevated
}

/**
 * A day's pressure is a day's weather from an hour of readings on. The step service keeps one
 * every ten minutes, and a mean over a handful of them is where the phone happened to be.
 */
private const val MIN_PRESSURE_SAMPLES = 6L

/**
 * The air pressure a day sat under against how that day felt, measured as every other "these went
 * together" in the app is: by [insightLink], behind [INSIGHT_GATES], over [INSIGHT_WINDOW_DAYS].
 *
 * A Pearson coefficient over a month, called a link from ten paired days at 0.3, is not enough: two
 * series that have nothing to do with each other clear that bar in about one month out of three, and
 * a direction and a coefficient printed from it are what the engine's own notes say is no fact at
 * such sizes. So ninety paired days come first, and what is said is the mood on the days of highest
 * pressure against the days of lowest, when the range around the difference leaves zero out.
 *
 * Both series have their slow drift taken out ([residualise]): a season, and for the pressure a
 * move to another height, which a phone's barometer cannot tell from weather. A few days in the
 * mountains still stand in it as low pressure, and the card's note says so.
 */
internal fun pressureMoodReading(days: List<BarometerDay>, moods: List<JournalEntry>, today: LocalDate): InsightLink {
    val window = today.minusDays(INSIGHT_WINDOW_DAYS - 1L)..today
    val pressure = days
        .filter { it.pressureSamples >= MIN_PRESSURE_SAMPLES }
        .mapNotNull { row -> row.day.toLocalDateOrNull()?.let { it to row.pressureSumHpa / row.pressureSamples } }
        .filter { (day, _) -> day in window }
        .toMap()
    val rated = DailySeries(moodSeries(moods).byDay.filterKeys { it in window })
    return insightLink(
        exposure = residualise(DailySeries(pressure)),
        outcome = residualise(rated),
        lag = InsightLag.SAME_DAY,
        windowDays = INSIGHT_WINDOW_DAYS,
        seed = insightSeed("pressure", "mood", today),
        shown = rated,
    )
}

/** What the weather card has to say of a [pressureMoodReading]. */
enum class PressureMoodLine { NEED_DAYS, NONE, HIGH_BETTER, HIGH_WORSE }

/**
 * A count of the days still missing until there are enough to measure; after that a direction only
 * for a difference the engine would make a card of — its range clear of zero and the gap at least
 * [InsightGates.minEffect] — and "no link" for everything else, which is the ordinary answer.
 *
 * It is one of the comparisons with mood the app makes, and answers to the correction the others
 * are filtered by ([standsAmongAllComparisons]): judged alone, a sixth comparison at one in twenty
 * is where "something always stands out" comes from.
 */
internal fun pressureMoodLine(link: InsightLink): PressureMoodLine {
    // Enough days and still nothing measured — a mood that never moved, weather that never did — is
    // a finished answer and not a wait.
    val enoughDays = link.pairedDays >= INSIGHT_GATES.minPairedDays
    val contrast = link.contrast
        ?: return if (enoughDays) PressureMoodLine.NONE else PressureMoodLine.NEED_DAYS
    return when {
        !contrast.excludesZero || abs(contrast.difference) < INSIGHT_GATES.minEffect -> PressureMoodLine.NONE
        !standsAmongAllComparisons(contrast.chance) -> PressureMoodLine.NONE
        contrast.difference > 0.0 -> PressureMoodLine.HIGH_BETTER
        else -> PressureMoodLine.HIGH_WORSE
    }
}
