package com.pwnall.health

const val KIND_BREATHING = "BREATHING"

/**
 * The practice's four phases. Their lengths all derive from one count — the tempo the settings
 * choose — via [breathPhaseSeconds]: 4-2-6-2 on the default count, and a
 * long exhale is the one part every description of relaxed breathing agrees on.
 */
enum class BreathPhase(val titleRes: Int) {
    INHALE(R.string.breathing_inhale),
    HOLD_IN(R.string.breathing_pause),
    EXHALE(R.string.breathing_exhale),
    HOLD_OUT(R.string.breathing_pause),
}

/** The inhale count, in seconds, until something else is chosen. */
const val BREATH_TEMPO_DEFAULT = 4

/** The counts the settings offer. Under three seconds it stops being slow breathing. */
val BREATH_TEMPO_CHOICES = listOf(3, 4, 5, 6, 8)

/** Session lengths on offer, in minutes. */
val BREATH_DURATION_CHOICES = listOf(1, 2, 3, 5, 10, 15)

const val BREATH_DURATION_DEFAULT = 5

/**
 * A stored setting held to the [choices] the settings offer, or [default] for one they do not: a
 * length of 0 would end every session the moment its countdown did, and a value the wheel does not
 * hold would run the practice at a figure the settings row showed and the wheel, opened, did not.
 */
fun breathSetting(stored: Int, choices: List<Int>, default: Int): Int = stored.takeIf { it in choices } ?: default

/** The countdown drawn before the first inhale, so the session never starts mid-fumble. */
const val BREATH_PREPARE_SECONDS = 3

/** Inhale on the count, hold for half of it, exhale half as long again — rounded, never zero. */
fun breathPhaseSeconds(phase: BreathPhase, tempoSeconds: Int): Int {
    val tempo = tempoSeconds.coerceAtLeast(1)
    return when (phase) {
        BreathPhase.INHALE -> tempo
        BreathPhase.EXHALE -> (tempo * 3 + 1) / 2
        else -> ((tempo + 1) / 2).coerceAtLeast(1)
    }
}

fun breathCycleSeconds(tempoSeconds: Int): Int =
    BreathPhase.entries.sumOf { breathPhaseSeconds(it, tempoSeconds) }

/** The countdown still to run, and the seconds of practice already breathed. */
data class BreathProgress(val countdown: Int, val elapsed: Int)

/**
 * Where a session stands [sinceStartMs] after the start button: the preparation countdown first,
 * then the practice, and never a second more than the session was set to.
 *
 * Read off the clock rather than counted a tick at a time, because a screen that goes dark stops
 * recomposing while a timer loop keeps running — counted, the difference is banked as practice
 * nobody breathed.
 */
fun breathProgressAt(sinceStartMs: Long, totalSeconds: Int): BreathProgress {
    val seconds = (sinceStartMs.coerceAtLeast(0L) / 1_000L).toInt()
    return BreathProgress(
        countdown = (BREATH_PREPARE_SECONDS - seconds).coerceAtLeast(0),
        elapsed = (seconds - BREATH_PREPARE_SECONDS).coerceIn(0, totalSeconds.coerceAtLeast(0)),
    )
}

data class BreathTick(
    val phase: BreathPhase,
    val secondsLeft: Int,
    val completedCycles: Int,
)

/** Maps seconds since the start of a session onto the phase the person should be in. */
fun breathTickAt(elapsedSeconds: Int, tempoSeconds: Int = BREATH_TEMPO_DEFAULT): BreathTick {
    val cycle = breathCycleSeconds(tempoSeconds)
    val elapsed = elapsedSeconds.coerceAtLeast(0)
    var within = elapsed % cycle
    var phase = BreathPhase.HOLD_OUT
    for (candidate in BreathPhase.entries) {
        val seconds = breathPhaseSeconds(candidate, tempoSeconds)
        if (within < seconds) {
            phase = candidate
            break
        }
        within -= seconds
    }
    return BreathTick(
        phase = phase,
        secondsLeft = breathPhaseSeconds(phase, tempoSeconds) - within,
        completedCycles = elapsed / cycle,
    )
}

/** What became of a session when it ended, however it ended. */
enum class BreathOutcome {
    /** Stopped before one whole cycle was breathed: there is nothing to record. */
    DISCARDED,

    /** Stopped part of the way through; what was breathed is recorded. */
    ENDED_EARLY,

    /** Ran its whole length. */
    COMPLETE,
}

/**
 * Whether a session that ended [seconds] in, with [cycles] whole cycles behind it, is recorded, and
 * as what.
 *
 * Every ending comes through here — the button, leaving the screen, the app going to the
 * background, the clock running out — and not every one may save a row and say "Practice
 * complete": a tap on the button five seconds in would be a completed session of no cycles, counted
 * with the rest and sent to Health Connect as mindfulness. One whole cycle is the least that is
 * practice at all, and only the full length is complete.
 */
fun breathOutcome(cycles: Int, seconds: Int, totalSeconds: Int): BreathOutcome = when {
    cycles < 1 -> BreathOutcome.DISCARDED
    seconds < totalSeconds -> BreathOutcome.ENDED_EARLY
    else -> BreathOutcome.COMPLETE
}

/** How one cue of the practice — a count, a phase turning over, the end — is given. */
enum class BreathCue { TONE, HAPTIC, NONE }

/**
 * The tones play on the notification stream, and a phone on vibrate, on silent or in Do Not Disturb
 * plays that stream at nothing: with the cues switched on, a practice done with the eyes shut would
 * have no phases and no end. Where the tone would not be heard the cue is a tap of the motor
 * instead; switched off, it is nothing at all.
 */
fun breathCue(soundOn: Boolean, toneAudible: Boolean): BreathCue = when {
    !soundOn -> BreathCue.NONE
    toneAudible -> BreathCue.TONE
    else -> BreathCue.HAPTIC
}

data class BreathingStats(
    val sessions: Int,
    val cycles: Int,
    val minutes: Double,
)

fun breathingStats(entries: List<JournalEntry>): BreathingStats {
    val sessions = entries.filter { it.kind == KIND_BREATHING }
    return BreathingStats(
        sessions = sessions.size,
        cycles = sessions.sumOf { it.breathCycles ?: 0 },
        minutes = sessions.sumOf { it.durationMinutes ?: 0.0 },
    )
}
