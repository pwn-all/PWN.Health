package com.pwnall.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// The Pain screen's cards: where and how often it hurt, pain after training, the steps before a pain
// started, tense days beside head and neck pain, and work days beside pain. Every number is read off
// [PainDiary]; the cards only put it into words.

/** "Where and how often" reads the last four weeks — the window a pain report is usually read over. */
internal const val PAIN_WHERE_DAYS = 28L

/** And says nothing about them until half of the days were answered. */
internal const val PAIN_WHERE_MIN_ANSWERED = 14

/** Under this share of the four weeks answered, the record is marked thin. */
internal const val PAIN_WHERE_THIN_SHARE = 0.6

/**
 * The patterns read the last ninety days: long enough for ten days a group to come about, short
 * enough to be about how things are now.
 */
internal const val PAIN_PATTERN_DAYS = 90L

/** How many of the latest pains after a workout "After training" lists. */
internal const val PAIN_RECENT_AFTER_TRAINING = 4

/**
 * The fixed questions, asked of these areas and no others, so the cards never search every area
 * against every exposure and show whichever came out: steps before lower-back and neck pain, tense
 * days beside head and neck pain, and work days beside the three areas the work research reports on.
 */
internal val MOVEMENT_GROUPS = listOf(PainGroup.LOWER_BACK, PainGroup.NECK_SHOULDERS)
internal val TENSION_GROUPS = listOf(PainGroup.HEAD, PainGroup.NECK_SHOULDERS)
internal val WORK_PAIN_GROUPS = listOf(PainGroup.NECK_SHOULDERS, PainGroup.LOWER_BACK, PainGroup.HEAD)

/** The steps read before the pattern window: its first starts look three days back from their onset. */
private const val PAIN_STEPS_EXTRA_DAYS = 7L

/**
 * A pain after a workout, as "After training" lists it: the [episode] in [group]. [trained] when the
 * entry that opened it marked an area of the group over a muscle the workout worked — the areas the
 * soreness rule is about — rather than only other areas the same entry marked.
 */
internal data class WorkoutPain(val group: PainGroup, val episode: PainEpisode) {
    val trained: Boolean
        get() {
            val worked = episode.session?.workedMuscles().orEmpty()
            return decodePainPayload(episode.opening.measurementPayloadJson).regions.any {
                it.region.group == group && it.region.muscle in worked
            }
        }
}

/**
 * What the Pain screen's cards show, worked out in one pass off the main thread.
 *
 * [summaries] holds the groups with pain in the last four weeks, in the groups' order. [afterWorkouts]
 * are the latest episodes that began in the window after a logged workout, newest first, one per
 * opening entry however many groups it marked: the one in a group the workout trained, since an
 * entry that marked a thigh and the lower back after a leg day is soreness in the thigh or not, and
 * the lower back's own days say nothing about that. [trained] holds the muscle groups with a session
 * in the window. [work] is null when the work timer was never used, and the card is not shown.
 */
internal data class PainInsights(
    val answeredDays: Int,
    val summaries: List<PainGroupSummary>,
    val afterWorkouts: List<WorkoutPain>,
    val trained: List<TrainedAreaPattern>,
    val movement: List<Pair<PainGroup, MovementBeforeStarts>>,
    val tension: List<Pair<PainGroup, TensionAndPain>>,
    val work: List<Pair<PainGroup, WorkAndPain>>?,
) {
    val enoughAnswered: Boolean get() = answeredDays >= PAIN_WHERE_MIN_ANSWERED

    /** Fewer than six in ten of the four weeks answered. */
    val thin: Boolean get() = answeredDays < PAIN_WHERE_THIN_SHARE * PAIN_WHERE_DAYS
}

internal fun painInsights(
    journal: List<JournalEntry>,
    steps: List<DaySteps>,
    today: LocalDate,
    longDayMinutes: Int,
    zone: ZoneId,
): PainInsights {
    val diary = PainDiary(journal, zone)
    val recent = today.minusDays(PAIN_WHERE_DAYS - 1)..today
    val window = today.minusDays(PAIN_PATTERN_DAYS - 1)..today
    return PainInsights(
        answeredDays = diary.answered.count { it in recent },
        summaries = PainGroup.entries.map { diary.summary(it, recent) }.filter { it.painDays > 0 },
        afterWorkouts = PainGroup.entries
            .flatMap { group -> diary.episodes(group.areas, window).map { WorkoutPain(group, it) } }
            .filter { it.episode.session != null }
            .groupBy { it.episode.opening }
            .values.map { same -> same.firstOrNull { it.trained } ?: same.first() }
            .sortedByDescending { it.episode.first }
            .take(PAIN_RECENT_AFTER_TRAINING),
        trained = Muscle.entries.map { diary.trainedArea(it, window) }.filter { it.sessions > 0 },
        movement = MOVEMENT_GROUPS.map { it to diary.movementBeforeStarts(it.areas, window, steps) },
        tension = TENSION_GROUPS.map { it to diary.tensionAndPain(it.areas, window) },
        work = WORK_PAIN_GROUPS.map { it to diary.workAndPain(it.areas, window, longDayMinutes) }
            .takeIf { journal.any { row -> row.kind == KIND_WORK } },
    )
}

/**
 * The cards, in the order a person reads the page: what the record holds, then what it went with.
 * Each says what it has even when that is not enough to compare: a card that appeared only when
 * something turned up would make coincidence look like a finding by never showing the misses.
 */
@Composable
internal fun PainInsightsCards(journal: List<JournalEntry>) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { StepsDb.get(app) }
    val today = rememberToday()
    val zone = rememberZone()
    val steps by remember(today) {
        val from = today.minusDays(PAIN_PATTERN_DAYS + PAIN_STEPS_EXTRA_DAYS)
        db.dao().daysBetween(from.toString(), today.toString())
    }.collectAsStateWithLifecycle<List<DaySteps>?>(null)
    val clock by remember(app) {
        WorkClock.flow(app)
    }.collectAsStateWithLifecycle(remember(app) { WorkClock.snapshot(app) })
    val longDay = clock.longDayMinutes
    val insights by produceState<PainInsights?>(null, journal, steps, longDay, today) {
        val counted = steps ?: return@produceState
        value = withContext(Dispatchers.Default) { painInsights(journal, counted, today, longDay, zone) }
    }
    val shown = insights ?: return
    val locale = LocalLocale.current.platformLocale
    WhereAndHowOftenCard(shown, locale)
    AfterTrainingCard(shown, locale, zone)
    MovementCard(shown.movement, locale)
    TensionCard(shown.tension)
    shown.work?.let { WorkAndPainCard(it, longDay) }
}

// On every card below, the figures, the verdicts and why something cannot be said yet stay on the
// card; how the counts were drawn, the app's own rule, the research and the line that a correlation
// is not a cause are behind the HelpDot beside the title, as the work cards keep theirs.

@Composable
private fun WhereAndHowOftenCard(insights: PainInsights, locale: Locale) {
    PatternCard(
        stringResource(R.string.insight_pain_where_title),
        help = {
            // How episodes and areas are counted, only where there are counts to read it against.
            if (insights.enoughAnswered) PatternNote(stringResource(R.string.insight_pain_where_note))
            PatternNote(stringResource(R.string.insight_pain_answered_rule))
            PatternResearch(
                stringResource(R.string.insight_pain_rating_research),
                stringResource(R.string.insight_pain_rating_source),
            )
        },
    ) {
        PatternLine(
            stringResource(R.string.insight_pain_where_answered, insights.answeredDays, PAIN_WHERE_DAYS.toInt()),
        )
        if (!insights.enoughAnswered) {
            PatternNote(stringResource(R.string.insight_pain_where_need, PAIN_WHERE_MIN_ANSWERED))
        } else {
            if (insights.thin) PatternNote(stringResource(R.string.insight_pain_where_thin))
            if (insights.summaries.isEmpty()) PatternLine(stringResource(R.string.insight_pain_where_none))
            insights.summaries.forEach { GroupSummaryLines(it, locale) }
        }
    }
}

@Composable
private fun GroupSummaryLines(summary: PainGroupSummary, locale: Locale) {
    val resources = LocalResources.current
    PatternHeading(stringResource(summary.group.labelRes()))
    PatternLine(stringResource(R.string.insight_pain_days, summary.painDays, summary.answeredDays))
    val median = summary.medianNrs
    val highest = summary.highestNrs
    if (median != null && highest != null) {
        PatternLine(stringResource(R.string.insight_pain_scores, oneDecimal(median, locale), highest))
    }
    val words = PainType.entries.mapNotNull { type ->
        summary.types[type]?.let {
            resources.getString(R.string.insight_pain_word_count, resources.getString(type.shortRes), it)
        }
    }
    val comma = stringResource(R.string.list_comma)
    if (words.isNotEmpty()) PatternLine(stringResource(R.string.insight_pain_words, words.joinToString(comma)))
    PatternLine(stringResource(R.string.insight_pain_episodes, summary.episodes, summary.painkillerDays))
    if (summary.sorenessEpisodes > 0) {
        PatternLine(
            stringResource(R.string.insight_pain_soreness_apart, summary.sorenessEpisodes, summary.sorenessDays),
        )
    }
    if (summary.recordedOverThreeMonths) PatternNote(stringResource(R.string.insight_pain_span))
}

@Composable
private fun AfterTrainingCard(insights: PainInsights, locale: Locale, zone: ZoneId) {
    PatternCard(
        stringResource(R.string.insight_pain_training_title),
        help = {
            if (insights.trained.isNotEmpty()) {
                PatternNote(stringResource(R.string.insight_pain_trained_note))
                PatternRule()
            }
            PatternResearch(
                stringResource(R.string.pain_soreness_heuristic),
                stringResource(R.string.pain_soreness_source),
            )
            PatternDisclaimer()
        },
    ) {
        if (insights.trained.isEmpty() && insights.afterWorkouts.isEmpty()) {
            PatternLine(stringResource(R.string.insight_pain_training_no_sessions))
        } else {
            PatternHeading(stringResource(R.string.insight_pain_training_recent))
            if (insights.afterWorkouts.isEmpty()) {
                PatternNote(stringResource(R.string.insight_pain_training_no_episodes))
            }
            insights.afterWorkouts.forEach { SorenessEpisodeLines(it, locale, zone) }
        }
        // A pain early in the window can follow a session just before it, so the counts by group can
        // be empty while a pain after a workout is still listed above.
        if (insights.trained.isNotEmpty()) {
            PatternHeading(stringResource(R.string.insight_pain_trained_title))
            insights.trained.forEach { TrainedAreaLines(it) }
        }
        // Static, and on this card whatever the record holds: never triggered by anything logged.
        // Out on the card rather than behind the dot, since it asks to be seen promptly.
        PatternResearch(stringResource(R.string.pain_urine), stringResource(R.string.pain_urine_source))
    }
}

/**
 * One pain after a workout: the areas its entry marked in the group it is listed under, the days
 * that group's episode ran, and how it compares with soreness after training.
 */
@Composable
private fun SorenessEpisodeLines(listed: WorkoutPain, locale: Locale, zone: ZoneId) {
    val resources = LocalResources.current
    val episode = listed.episode
    val session = episode.session ?: return
    val areas = remember(episode.opening, listed.group) {
        val areas = listed.group.areas
        decodePainPayload(episode.opening.measurementPayloadJson).regions.filter { it.region in areas }
    }.joinToString(resources.getString(R.string.list_comma)) { resources.painAreaShort(it) }
    PatternLine(
        stringResource(R.string.insight_pain_episode_head, areas, dayRangeText(episode.first, episode.last, locale)),
    )
    val name = session.shownTitle(resources)
    // When the session ended, as the entry's own soreness card says it: most starts are answered as
    // a day rather than a moment, and an exact gap in hours would claim one.
    val ended = remember(session.occurredAt, locale, zone) {
        skeletonDateText(PAIN_MOMENT_SKELETON, locale, session.occurredAt, zone)
    }
    if (episode.fitsSoreness) {
        PatternLine(stringResource(R.string.pain_soreness_fits, name, ended))
    } else {
        PatternLine(stringResource(R.string.pain_soreness_not_typical, name, ended))
        SorenessReason.entries.filter { it in episode.reasons }.forEach {
            PatternNote("• " + stringResource(it.labelRes))
        }
    }
}

/**
 * A trained group: its sessions, the pain starts in its areas inside days 0–2 after one, and the other
 * answered days inside such windows — the two groups the verdict under them compares, so the counts
 * printed are the counts weighed. Below [TRAINED_MIN_SESSIONS] sessions there is nothing to compare;
 * past it, the floor that decides is the comparison's own, in starts.
 */
@Composable
private fun TrainedAreaLines(pattern: TrainedAreaPattern) {
    val starts = pattern.startsInWindows
    PatternLine(
        stringResource(
            R.string.insight_pain_trained_sessions,
            stringResource(pattern.muscle.labelRes()),
            pattern.sessions,
        ),
    )
    PatternLine(stringResource(R.string.insight_pain_trained_starts, starts.exposed.days, starts.exposed.of))
    PatternLine(stringResource(R.string.insight_pain_trained_base, starts.other.days, starts.other.of))
    if (pattern.sorenessStarts > 0) {
        PatternLine(stringResource(R.string.insight_pain_trained_soreness, pattern.sorenessStarts))
    }
    if (pattern.sessions >= TRAINED_MIN_SESSIONS) {
        VerdictLine(starts, contrastLine(starts), floor = R.string.insight_not_enough_starts)
    } else {
        PatternNote(stringResource(R.string.insight_pain_trained_need, TRAINED_MIN_SESSIONS))
    }
}

@Composable
private fun MovementCard(movement: List<Pair<PainGroup, MovementBeforeStarts>>, locale: Locale) {
    PatternCard(
        stringResource(R.string.insight_pain_movement_title),
        help = {
            PatternNote(stringResource(R.string.insight_pain_movement_method))
            PatternRule()
            PatternResearch(
                stringResource(R.string.insight_pain_movement_research),
                stringResource(R.string.insight_pain_movement_source),
            )
            PatternDisclaimer()
        },
    ) {
        movement.forEach { (group, found) ->
            PatternHeading(stringResource(group.labelRes()))
            PatternLine(stringResource(R.string.insight_pain_movement_counts, found.starts, found.controls))
            if (!found.enough) {
                PatternNote(
                    stringResource(R.string.insight_pain_movement_need, MOVEMENT_MIN_STARTS, MOVEMENT_MIN_CONTROLS),
                )
                return@forEach
            }
            val before = found.startSteps
            val others = found.controlSteps
            if (before != null && others != null) {
                PatternLine(
                    stringResource(
                        R.string.insight_pain_movement_means,
                        wholeNumber(before, locale),
                        wholeNumber(others, locale),
                    ),
                )
            }
            found.lowestThirdUpTo?.let { low ->
                ThirdLines(
                    R.string.insight_pain_movement_low,
                    wholeNumber(low, locale),
                    found.afterLowest,
                    found.controlShareAfterLowest,
                )
            }
            found.highestThirdFrom?.let { high ->
                ThirdLines(
                    R.string.insight_pain_movement_high,
                    wholeNumber(high, locale),
                    found.afterHighest,
                    found.controlShareAfterHighest,
                )
            }
        }
    }
}

/**
 * Starts after a stretch in one third of the person's own step range, against the other days — and
 * [base], the other days' share weighed month by month as the starts fell, which the verdict also has
 * to clear: the plain share alone would leave a "No clear difference yet" under two far-apart counts
 * with no reason on the card.
 */
@Composable
private fun ThirdLines(line: Int, bound: String, contrast: DayShareContrast, base: Double?) {
    PatternLine(
        stringResource(line, bound, contrast.exposed.days, contrast.exposed.of, contrast.other.days, contrast.other.of),
    )
    base?.let { PatternNote(stringResource(R.string.insight_pain_movement_base, (it * 100).roundToInt())) }
    VerdictLine(contrast, contrastLine(contrast), floor = R.string.insight_not_enough_starts)
}

@Composable
private fun TensionCard(tension: List<Pair<PainGroup, TensionAndPain>>) {
    PatternCard(
        stringResource(R.string.insight_pain_tension_title),
        help = {
            PatternNote(stringResource(R.string.insight_pain_tension_method))
            PatternRule()
            PatternResearch(
                stringResource(R.string.insight_pain_tension_research),
                stringResource(R.string.insight_pain_tension_source),
            )
            PatternDisclaimer()
        },
    ) {
        tension.forEach { (group, found) ->
            PatternHeading(stringResource(group.labelRes()))
            val same = found.sameDay
            PatternLine(
                stringResource(
                    R.string.insight_pain_tension_same,
                    same.exposed.days,
                    same.exposed.of,
                    same.other.days,
                    same.other.of,
                ),
            )
            // Symmetric: an evening's answer can follow the day's headache as easily as come before it.
            VerdictLine(same, contrastLine(same), notable = R.string.insight_notable_same_days)
            val next = found.nextDay.forward
            PatternLine(
                stringResource(
                    R.string.insight_pain_tension_next,
                    next.exposed.days,
                    next.exposed.of,
                    next.other.days,
                    next.other.of,
                ),
            )
            VerdictLine(next, contrastLine(found.nextDay))
        }
    }
}

@Composable
private fun WorkAndPainCard(work: List<Pair<PainGroup, WorkAndPain>>, longDayMinutes: Int) {
    PatternCard(
        stringResource(R.string.insight_pain_work_title),
        help = {
            PatternNote(
                stringResource(
                    R.string.insight_pain_work_method,
                    stringResource(R.string.work_long_day_value, longDayMinutes / MINUTES_AN_HOUR),
                ),
            )
            PatternRule()
            PatternResearch(
                stringResource(R.string.insight_pain_work_research),
                stringResource(R.string.insight_pain_work_source),
            )
            PatternDisclaimer()
        },
    ) {
        work.forEach { (group, found) ->
            PatternHeading(stringResource(group.labelRes()))
            val long = found.longVsOther
            PatternLine(
                stringResource(
                    R.string.insight_pain_work_line,
                    long.exposed.days,
                    long.exposed.of,
                    long.other.days,
                    long.other.of,
                ),
            )
            PatternNote(stringResource(R.string.insight_pain_work_days_off, found.daysOff.days, found.daysOff.of))
            // Same day, and symmetric: a pain can shorten a working day as easily as follow a long one.
            VerdictLine(long, contrastLine(long), notable = R.string.insight_notable_same_days)
        }
    }
}

private const val MINUTES_AN_HOUR = 60
