package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.random.Random

/**
 * A template is only worth saving if starting it produces the same records as typing the session in
 * by hand — otherwise the two paths drift and the journal stops meaning one thing.
 */
class RoutineTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")
    private val squat = RoutineExercise(routineId = 1, position = 0, activityCode = "SQUATS", durationMinutes = 10.0)
    private val press = squat.copy(activityCode = "BENCH_PRESS")
    private val pull = squat.copy(activityCode = "PULLUPS")
    private val plank = squat.copy(activityCode = "PLANK")

    /**
     * A row typed into the form is costed by the pass every save sets off ([recalculatedActivityTimeline]),
     * so a session's rows have to come out of [sessionRows] at the figure that pass gives them: twenty
     * minutes of bench press, and push-ups in a 20 kg vest, which the pass costs by the load.
     */
    @Test
    fun aTemplatedExerciseCostsWhatTheSameExerciseTypedInCosts() {
        val vest = pull.copy(
            activityCode = "PUSHUPS",
            setsJson = routineSetsJson(List(2) { RoutineSet(reps = 10, loadKg = 20.0, restSeconds = 60) }),
        )
        val outcomes = listOf(plannedOutcome(press.copy(durationMinutes = 20.0)), plannedOutcome(vest))

        val rows = sessionRows(NamedResources(), profile, outcomes, LocalDate.of(2026, 11, 14), 1_794_000_000_000L)
        val typed = recalculatedActivityTimeline(rows, baselineWeightKg = profile.weightKg, profile = profile)

        assertEquals(listOf("BENCH_PRESS", "PUSHUPS"), rows.map { it.activityCode })
        assertEquals(20.0, rows.first().durationMinutes ?: 0.0, 1e-9)
        assertEquals(20.0, rows.last().loadKg ?: 0.0, 1e-9)
        assertTrue(rows.all { it.calories > 0.0 })
        assertEquals(typed.map { it.calories }, rows.map { it.calories })
    }

    @Test
    fun aSupersetAndTheRestsBetweenStepsSurviveTheRows() {
        val blocks = listOf(
            RoutineBlock(listOf(squat), restSeconds = 90),
            RoutineBlock(listOf(press, pull), superset = true, restSeconds = 60),
            // The last step's rest comes before nothing, and is not kept.
            RoutineBlock(listOf(plank), restSeconds = 30),
        )

        val rows = routineRows(blocks)

        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
        assertEquals(listOf(null, 1, 1, null), rows.map { it.superset })
        assertEquals(listOf(90, 0, 60, 0), rows.map { it.restSeconds })
        val back = routineBlocks(rows.shuffled(Random(7)))
        assertEquals(
            listOf(listOf("SQUATS"), listOf("BENCH_PRESS", "PULLUPS"), listOf("PLANK")),
            back.map { block -> block.exercises.map { it.activityCode } },
        )
        assertEquals(listOf(false, true, false), back.map { it.superset })
        assertEquals(listOf(90, 60, 0), back.map { it.restSeconds })
    }

    @Test
    fun twoSupersetsSideBySideStayTwoAndAnEmptyOneIsDropped() {
        val blocks = listOf(
            RoutineBlock(listOf(squat, plank), superset = true),
            RoutineBlock(emptyList(), superset = true),
            RoutineBlock(listOf(press, pull), superset = true),
        )

        val back = routineBlocks(routineRows(blocks))

        assertEquals(2, back.size)
        assertTrue(back.all { it.superset && it.exercises.size == 2 })
    }

    @Test
    fun anExerciseGoesWhereItWasAskedForAndLeavesTheSameWay() {
        val blocks = listOf(RoutineBlock(listOf(squat)), RoutineBlock(listOf(press), superset = true))

        assertEquals(3, blocks.placing(NEW_STEP, -1, plank).size)
        assertEquals(listOf(press, pull), blocks.placing(1, -1, pull)[1].exercises)
        assertEquals(listOf(plank), blocks.placing(0, 0, plank)[0].exercises)
        // A step of one exercise goes with it; a superset stays, empty, to be filled or binned.
        assertEquals(listOf(listOf(press)), blocks.removing(0, 0).map { it.exercises })
        assertEquals(listOf(true), blocks.removing(1, 0).drop(1).map { it.superset && it.exercises.isEmpty() })
    }

    @Test
    fun theDaysAreSaidInTheWeekOfThePhonesLocale() {
        val mask = DayOfWeek.MONDAY.routineBit() or DayOfWeek.SUNDAY.routineBit()

        assertEquals("Sun · Mon", routineDaysText(mask, Locale.US))
        assertEquals("Mon · Sun", routineDaysText(mask, Locale.UK))
        assertEquals(ALL_ROUTINE_DAYS, DayOfWeek.entries.sumOf { it.routineBit() })
    }

    @Test
    fun aSharedTemplateComesBackFromItsFileAndNothingElseDoes() {
        val routine = Routine(
            id = 7,
            name = "Push day",
            createdAt = 1L,
            description = "Heavy week",
            days = DayOfWeek.MONDAY.routineBit(),
        )
        val rows = routineRows(
            listOf(RoutineBlock(listOf(squat), restSeconds = 90), RoutineBlock(listOf(press, pull), superset = true)),
        )

        val read = checkNotNull(readRoutineFile(routineFileText(routine, rows, byteArrayOf(1, 2, 3))))

        assertEquals("Push day", read.name)
        assertEquals("Heavy week", read.description)
        assertEquals(DayOfWeek.MONDAY.routineBit(), read.days)
        assertEquals(listOf("SQUATS", "BENCH_PRESS", "PULLUPS"), read.rows.map { it.activityCode })
        assertEquals(listOf(null, 1, 1), read.rows.map { it.superset })
        assertEquals(listOf(90, 0, 0), read.rows.map { it.restSeconds })
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(read.jpeg))
        assertNull(readRoutineFile("not a file"))
        assertNull(readRoutineFile("""{"name":"Push day","exercises":[]}"""))
    }

    @Test
    fun aTemplateFileFromSomebodyElseIsHeldToTheFormsOwnBounds() {
        val routine = Routine(name = "Wild", createdAt = 1L)
        val wild = squat.copy(durationMinutes = 1e9, sets = -3, reps = 50_000, loadKg = 1e6, restSeconds = 1_000_000)

        val held = checkNotNull(readRoutineFile(routineFileText(routine, listOf(wild), null))).rows.single()

        assertEquals(600.0, held.durationMinutes, 0.0)
        assertNull(held.sets)
        assertNull(held.reps)
        assertNull(held.loadKg)
        assertEquals(99 * 60 + 59, held.restSeconds)
        // An exercise this catalogue does not have is left out, and a file with nothing left is none.
        assertNull(readRoutineFile(routineFileText(routine, listOf(squat.copy(activityCode = "TELEPORT")), null)))
    }

    @Test
    fun aSessionRunsAReadyCountThenEachExerciseWithTheRestsBetweenSteps() {
        val blocks = listOf(
            RoutineBlock(listOf(squat.copy(durationMinutes = 2.0)), restSeconds = 90),
            RoutineBlock(
                listOf(press.copy(durationMinutes = 1.5), pull.copy(durationMinutes = 1.0)),
                superset = true,
                restSeconds = 60,
            ),
            RoutineBlock(listOf(plank.copy(durationMinutes = 0.5)), restSeconds = 30),
        )

        val phases = routinePhases(blocks)

        assertEquals(
            listOf(
                PhaseKind.READY,
                PhaseKind.EXERCISE,
                PhaseKind.REST,
                PhaseKind.EXERCISE,
                PhaseKind.EXERCISE,
                PhaseKind.REST,
                PhaseKind.EXERCISE,
            ),
            phases.map { it.kind },
        )
        // No rest inside the superset, and none after the last step.
        assertEquals(listOf(5, 120, 90, 90, 60, 60, 30), phases.map { it.seconds })
        // Nothing spent on any stretch: every exercise is there, in its order, and none was done.
        val none =
            routineOutcomes(phases, IntArray(phases.size), BooleanArray(phases.size), IntArray(phases.size) { -1 })
        assertEquals(listOf("SQUATS", "BENCH_PRESS", "PULLUPS", "PLANK"), none.map { it.exercise.activityCode })
        assertTrue(none.all { it.done.isEmpty() })
    }

    @Test
    fun aPlannedWorkoutIsRemindedOnItsMorningOnceAndOnlyWhenPlannedFromATemplate() {
        val template = Routine(id = 3, name = "Push day", createdAt = 1L)
        val mine = planEntry(template, emptyList(), LocalDate.of(2026, 10, 14))
        val foreign = mine.copy(title = "Another app's plan", measurementPayloadJson = null)
        val routines = setOf(template.id)

        assertEquals(
            listOf("Push day"),
            plannedToRemind(listOf(mine, foreign, mine), routines, LocalTime.of(9, 5), false),
        )
        assertTrue(plannedToRemind(listOf(mine), routines, LocalTime.of(7, 0), reminded = false).isEmpty())
        assertTrue(plannedToRemind(listOf(mine), routines, LocalTime.of(10, 0), reminded = true).isEmpty())
        // The template deleted, its plan names nothing anybody can start.
        assertTrue(plannedToRemind(listOf(mine), emptySet(), LocalTime.of(9, 5), false).isEmpty())
    }

    @Test
    fun setsShareOutTheMinutesAndASupersetGoesRoundASetAtATime() {
        val a = press.copy(durationMinutes = 3.0, sets = 3)
        val b = pull.copy(durationMinutes = 2.0, sets = 2)

        val round = routinePhases(listOf(RoutineBlock(listOf(a, b), superset = true)))

        assertEquals(
            listOf(null, "BENCH_PRESS", "PULLUPS", "BENCH_PRESS", "PULLUPS", "BENCH_PRESS"),
            round.map { it.exercise?.activityCode },
        )
        assertEquals(listOf(0, 1, 1, 2, 2, 3), round.map { it.set })
        assertEquals(listOf(5, 60, 60, 60, 60, 60), round.map { it.seconds })
        // Each exercise is one outcome, however its sets were interleaved.
        val ran =
            routineOutcomes(
                round,
                IntArray(round.size) {
                    round[it].seconds
                },
                BooleanArray(round.size),
                IntArray(round.size) { -1 },
            )
        assertEquals(listOf("BENCH_PRESS", "PULLUPS"), ran.map { it.exercise.activityCode })
        assertEquals(listOf(3, 2), ran.map { it.done.size })
        // What the division leaves goes to the last set; a count past what a session holds is one stretch.
        val odd = routinePhases(listOf(RoutineBlock(listOf(squat.copy(durationMinutes = 10.0 / 60, sets = 3)))))
        assertEquals(listOf(3, 3, 4), odd.drop(1).map { it.seconds })
        assertEquals(2, routinePhases(listOf(RoutineBlock(listOf(squat.copy(sets = 500))))).size)
    }

    @Test
    fun aLiftPlannedSetBySetKeepsEachSetsRepsAndWeightAndRestsBetweenThem() {
        val sets =
            listOf(RoutineSet(10, 20.0, restSeconds = 60), RoutineSet(10, 25.0, restSeconds = 60), RoutineSet(7, 30.0))
        val curl = press.copy(durationMinutes = routineSetsMinutes(sets), setsJson = routineSetsJson(sets))

        assertEquals(sets, routineSets(curl))
        // A lift with no sets written out reads as its one figure, set after set.
        assertEquals(
            List(3) { RoutineSet(12, 60.0, restSeconds = 60) },
            routineSets(press.copy(sets = 3, reps = 12, loadKg = 60.0)),
        )

        // 27 reps at three seconds each, and a minute's rest between each two of the three sets.
        assertEquals((27 * 3 + 2 * 60) / 60.0, curl.durationMinutes, 1e-9)
        val phases = routinePhases(listOf(RoutineBlock(listOf(curl))))
        assertEquals(listOf(5, 30, 60, 30, 60, 21), phases.map { it.seconds })
        assertEquals(listOf(0, 1, 0, 2, 0, 3), phases.map { it.set })
        // Each set waits for its "Complete set" and each rest after one for the next set; only the
        // getting ready runs on the clock.
        assertEquals(listOf(false, true, true, true, true, true), phases.map { it.manual })
        assertEquals(curl.durationMinutes * 60, phases.drop(1).sumOf { it.seconds }.toDouble(), 1e-9)

        // From somebody else's file: each set held to the form's bounds, and none on a timed exercise.
        val wild = routineSetsJson(listOf(RoutineSet(10, 1e6), RoutineSet(0, 20.0)) + List(20) { RoutineSet(5) })
        val file = routineFileText(
            Routine(name = "Wild", createdAt = 1L),
            listOf(press.copy(setsJson = wild), plank.copy(setsJson = wild)),
            null,
        )
        val held = checkNotNull(readRoutineFile(file)).rows
        assertEquals(listOf(RoutineSet(10)) + List(8) { RoutineSet(5) }, routineSets(held[0]))
        assertEquals("", held[1].setsJson)
    }

    @Test
    fun aTimedExerciseIsHeldSetBySetWithItsPausesOnTheClock() {
        // A plank: 5:30, then 3:00, then 1:30, half a minute's pause between them.
        val holds = listOf(
            RoutineSet(seconds = 330, restSeconds = 30),
            RoutineSet(seconds = 180, restSeconds = 30),
            RoutineSet(seconds = 90, restSeconds = 30),
        )
        val timed = plank.copy(durationMinutes = routineSetsMinutes(holds), sets = 3, setsJson = routineSetsJson(holds))

        assertEquals(holds, routineSets(timed))
        assertEquals((600 + 60) / 60.0, timed.durationMinutes, 1e-9)
        val phases = routinePhases(listOf(RoutineBlock(listOf(timed))))
        assertEquals(listOf(5, 330, 30, 180, 30, 90), phases.map { it.seconds })
        assertTrue(phases.none { it.manual })
        // Its minutes hold the pauses too, so they are never shared out as "3 × 3 min 40 sec".
        assertNull(routineSetSeconds(timed))
        // A plank with no sets written out reads as its holds, and ten minutes' walk as one set of ten minutes.
        assertEquals(
            List(3) {
                RoutineSet(seconds = 45, restSeconds = 30)
            },
            routineSets(plank.copy(durationMinutes = 2.25, sets = 3)),
        )
        assertEquals(listOf(RoutineSet(seconds = 600, restSeconds = 30)), routineSets(plank))
    }

    @Test
    fun aSessionKeepsWhatWasDoneRatherThanWhatWasPlanned() {
        val holds = listOf(RoutineSet(seconds = 300, restSeconds = 30), RoutineSet(seconds = 180))
        val plankRow = plank.copy(durationMinutes = routineSetsMinutes(holds), setsJson = routineSetsJson(holds))
        val curls =
            listOf(RoutineSet(10, 20.0, restSeconds = 60), RoutineSet(10, 25.0, restSeconds = 60), RoutineSet(7, 30.0))
        val curlRow = press.copy(durationMinutes = routineSetsMinutes(curls), setsJson = routineSetsJson(curls))
        val phases = routinePhases(
            listOf(
                RoutineBlock(listOf(plankRow)),
                RoutineBlock(listOf(curlRow)),
                RoutineBlock(listOf(squat.copy(durationMinutes = 5.0))),
            ),
        )
        // Ready, the two holds with their pause, three sets of reps with their two rests, the squats.
        assertEquals(10, phases.size)
        val spent = IntArray(phases.size)
        val held = BooleanArray(phases.size)
        // The first hold ran its five minutes and its pause; the second was cut at one of three.
        spent[1] = 300
        spent[2] = 30
        spent[3] = 60
        // The first set of reps was said done after 40 s and rested 75; the second was skipped after
        // 5 s with "No — 6 reps"; the third was never reached.
        spent[4] = 40
        held[4] = true
        spent[5] = 75
        spent[6] = 5
        held[6] = true
        val doneReps = IntArray(phases.size) { -1 }.also { it[6] = 6 }

        val outcomes = routineOutcomes(phases, spent, held, doneReps)

        assertEquals(listOf(SetDone(holds[0], 300, 30), SetDone(holds[1], 60, 0)), outcomes[0].done)
        assertEquals(2 to 480, outcomes[0].plannedSets to outcomes[0].plannedSeconds)
        assertEquals(listOf(SetDone(curls[0], 40, 75), SetDone(curls[1], 5, 0, reps = 6)), outcomes[1].done)
        assertEquals(3, outcomes[1].plannedSets)
        // 10 and 6 of the planned 10 + 10 + 7: the goal was not reached.
        assertEquals(16 to 27, outcomes[1].done.sumOf { it.repsDone } to outcomes[1].plannedReps)
        // The squats were never begun: an exercise not done.
        assertTrue(outcomes[2].done.isEmpty())
        // Log writes a session already done: every set in full, its minutes the plan's.
        val asPlanned = plannedOutcome(curlRow)
        assertEquals(3, asPlanned.done.size)
        assertEquals(curlRow.durationMinutes * 60, asPlanned.done.sumOf { it.seconds + it.restAfter }.toDouble(), 1e-9)
    }

    /**
     * What the phone counted lands where reps said by hand do: a minute of rope keeps its jumps, and
     * seven pull-ups counted on a plan of five are seven, with no goal missed.
     */
    @Test
    fun aSessionKeepsWhatThePhoneCounted() {
        val minute = listOf(RoutineSet(seconds = 60))
        val rope = squat.copy(
            activityCode = "JUMP_ROPE",
            durationMinutes = routineSetsMinutes(minute),
            setsJson = routineSetsJson(minute),
        )
        val five = listOf(RoutineSet(5))
        val pullRow = pull.copy(durationMinutes = routineSetsMinutes(five), setsJson = routineSetsJson(five))
        val phases = routinePhases(listOf(RoutineBlock(listOf(rope)), RoutineBlock(listOf(pullRow))))
        val (ropeAt, pullAt) = phases.indices.filter { phases[it].kind == PhaseKind.EXERCISE }
        val spent = IntArray(phases.size).also {
            it[ropeAt] = 60
            it[pullAt] = 30
        }
        val held = BooleanArray(phases.size).also { it[pullAt] = true }
        val doneReps = IntArray(phases.size) { -1 }.also {
            it[ropeAt] = 112
            it[pullAt] = 7
        }

        val outcomes = routineOutcomes(phases, spent, held, doneReps)
        val summary = sessionSummary(outcomes)

        assertEquals(112 to 60, summary[0].sets.single().let { it.reps to it.seconds })
        assertEquals(7, summary[1].sets.single().reps)
        assertTrue(outcomes[1].done.sumOf { it.repsDone } >= outcomes[1].plannedReps)
    }

    @Test
    fun theEndPageCountsTheWeightLiftedTheTimeTrainedAndEveryRestTaken() {
        val curls = listOf(RoutineSet(10, 20.0, restSeconds = 60), RoutineSet(8, 25.0))
        val curlRow = press.copy(durationMinutes = routineSetsMinutes(curls), setsJson = routineSetsJson(curls))
        val phases = routinePhases(
            listOf(
                RoutineBlock(listOf(curlRow), restSeconds = 90),
                RoutineBlock(listOf(plank.copy(durationMinutes = 1.0))),
            ),
        )
        // Ready, two sets of reps with the rest between them, the rest between the steps, the plank.
        assertEquals(
            listOf(
                PhaseKind.READY,
                PhaseKind.EXERCISE,
                PhaseKind.REST,
                PhaseKind.EXERCISE,
                PhaseKind.REST,
                PhaseKind.EXERCISE,
            ),
            phases.map { it.kind },
        )
        val spent = intArrayOf(5, 40, 75, 30, 100, 60)

        val outcomes = routineOutcomes(phases, spent, BooleanArray(phases.size) { true }, IntArray(phases.size) { -1 })

        // The rest between the steps is the curls', shown between their card and the plank's.
        assertEquals(100, outcomes[0].restAfter)
        val summary = sessionSummary(outcomes)
        val totals = sessionTotals(summary)
        // 10 × 20 kg and 8 × 25 kg; the plank lifts nothing.
        assertEquals(400.0, totals.liftedKg, 1e-9)
        assertEquals(40 + 30 + 60, totals.workSeconds)
        assertEquals(75 + 100, totals.restSeconds)
        // Kept as text through a turn of the phone, and read back the same.
        val saved = with(DoneExercisesSaver) { SaverScope { true }.save(summary) }
        assertEquals(summary, DoneExercisesSaver.restore(checkNotNull(saved)))
        // A Freestyle set with no load after a loaded one lifts nothing, not the first set's weight.
        val free = freestyleOutcomes(
            listOf(FreeSet("PUSHUPS", 30, reps = 10, loadKg = 10.0, rest = 60), FreeSet("PUSHUPS", 30, reps = 12)),
        )
        assertEquals(100.0, sessionTotals(sessionSummary(free)).liftedKg, 1e-9)
    }

    /**
     * An exercise come back to — push-ups, a plank, push-ups again — is on the end page once a run. The
     * record is listed once and the badge goes to the set that broke it, in its own run: marking every
     * run by the last run's sets would look for a second set in a run of one, and close the app.
     */
    @Test
    fun anExerciseComeBackToMarksTheSetThatBrokeTheRecordInItsOwnRun() {
        val none = emptySet<Int>()
        val before = mapOf(
            "PUSHUPS" to ExerciseRecords(null, null, mostReps = StrengthSet(0.0, 20, 0L), bestVolumeKg = 0.0),
        )
        val circuit = sessionSummary(
            freestyleOutcomes(
                listOf(
                    FreeSet("PUSHUPS", 30, reps = 15),
                    FreeSet("PLANK", 60),
                    FreeSet("PUSHUPS", 30, reps = 18),
                    FreeSet("PUSHUPS", 30, reps = 25),
                ),
            ),
        )
        assertEquals(listOf("PUSHUPS", "PLANK", "PUSHUPS"), circuit.map { it.code })

        assertEquals(listOf(none, none, setOf(1)), recordBadges(circuit, before))
        assertEquals(listOf(BrokenRecord("PUSHUPS", RecordKind.REPS, 25.0, 20.0)), sessionRecords(circuit, before))
        // Until the journal's records have been read, nothing is marked.
        assertEquals(listOf(none, none, none), recordBadges(circuit, null))
        assertTrue(sessionRecords(circuit, null).isEmpty())
    }

    /**
     * An exercise's sets of reps are measured across its runs, as the journal's records are: of two runs
     * past the old best only the better set is marked, at its place in its own list, which a timed set
     * also sits in. The weight lifted is one journal row's, and each run is a row — so the larger run's
     * is the record, not the two together.
     */
    @Test
    fun aRecordIsMarkedOnceAcrossRunsAndTheWeightLiftedIsOneRunsOwn() {
        val none = emptySet<Int>()
        val best = StrengthSet(100.0, 5, 0L)
        val before = mapOf("BENCH_PRESS" to ExerciseRecords(best, best, mostReps = null, bestVolumeKg = 1_000.0))
        val session = listOf(
            DoneExercise(
                "BENCH_PRESS",
                listOf(RoutineSet(seconds = 30), RoutineSet(5, 110.0), RoutineSet(5, 100.0)),
                0,
            ),
            DoneExercise("PLANK", listOf(RoutineSet(seconds = 60)), 0),
            DoneExercise("BENCH_PRESS", listOf(RoutineSet(5, 105.0), RoutineSet(5, 100.0)), 0),
        )

        assertEquals(listOf(setOf(1), none, none), recordBadges(session, before))
        val broken = sessionRecords(session, before)
        assertEquals(listOf(RecordKind.E1RM, RecordKind.HEAVIEST, RecordKind.VOLUME), broken.map { it.kind })
        // 5 × 110 kg and 5 × 100 kg in the first run; the second's 1,025 kg is past the old 1,000 too.
        assertEquals(listOf(epleyE1rm(110.0, 5), 110.0, 1_050.0), broken.map { it.value })
    }

    @Test
    fun aPlankInSetsReadsAsItsSetsOfSecondsAndALiftKeepsItsWeight() {
        // 2.25 minutes in three sets: "3 × 45 sec", as exercise references write a plank.
        assertEquals(3 to 45, routineSetSeconds(plank.copy(durationMinutes = 2.25, sets = 3)))
        // Reps or a load are the lift's own figures, and a count the player won't split is no span.
        assertNull(routineSetSeconds(squat.copy(sets = 3, reps = 12)))
        assertNull(routineSetSeconds(press.copy(sets = 3, loadKg = 60.0)))
        assertNull(routineSetSeconds(plank.copy(sets = 500)))
        assertNull(routineSetSeconds(plank))
    }

    @Test
    fun aTemplatesTotalsCountItsRestsAndCostWhatLoggingItWould() {
        val blocks = listOf(
            RoutineBlock(listOf(squat.copy(durationMinutes = 20.0)), restSeconds = 120),
            RoutineBlock(listOf(press.copy(durationMinutes = 10.0)), restSeconds = 600),
        )

        val totals = routineTotals(blocks, restingKcalPerHour = 80.0, bodyKg = 80.0)

        // The last step's rest is not part of the session.
        assertEquals(32, totals.minutes)
        val met = { code: String -> ACTIVITY_CATALOG.first { it.code == code }.met }
        val logged =
            netActivityCalories(met("SQUATS"), 80.0, 20.0) + netActivityCalories(met("BENCH_PRESS"), 80.0, 10.0)
        assertEquals(Math.round(logged).toInt(), totals.kcal)
        // Kilograms on a body-weight move are costed as the journal will cost them.
        val vest = pull.copy(
            activityCode = "PUSHUPS",
            setsJson = routineSetsJson(List(2) { RoutineSet(reps = 10, loadKg = 20.0) }),
        )
        assertEquals(
            Math.round(netActivityCalories(loadedMet("PUSHUPS", met("PUSHUPS"), 80.0, 20.0), 80.0, 10.0)).toInt(),
            routineTotals(listOf(RoutineBlock(listOf(vest))), restingKcalPerHour = 80.0, bodyKg = 80.0).kcal,
        )
        // Squats twice outweigh one bench press; ties keep the body's own order.
        assertEquals(listOf(Muscle.CORE, Muscle.GLUTES, Muscle.LEGS), routineMuscles(listOf(squat, squat, press)))
    }

    @Test
    fun aFreestyleSessionIsItsRunsOfSetsInOrderEachDoneInFull() {
        val sets = listOf(
            FreeSet("PLANK", seconds = 45, rest = 30),
            FreeSet("PLANK", seconds = 50, rest = 90),
            FreeSet("BENCH_PRESS", seconds = 40, reps = 10, loadKg = 60.0, rest = 60),
            FreeSet("BENCH_PRESS", seconds = 35, reps = 8, loadKg = 60.0, rest = 120),
            // Back to the plank after the press: a row of its own, as a session's groups are.
            FreeSet("PLANK", seconds = 30),
        )

        val outcomes = freestyleOutcomes(sets)

        assertEquals(listOf("PLANK", "BENCH_PRESS", "PLANK"), outcomes.map { it.exercise.activityCode })
        // The rests taken between sets are the exercise's minutes, as a session writes them.
        assertEquals((45 + 30 + 50 + 90) / 60.0, outcomes[0].exercise.durationMinutes, 1e-9)
        assertEquals(listOf(10, 8), routineSets(outcomes[1].exercise).map { it.reps })
        assertEquals(60.0, outcomes[1].exercise.loadKg)
        // Nothing was planned, so nothing falls short: every set counts and every rep is the plan's.
        outcomes.forEach { outcome ->
            assertEquals(outcome.plannedSets, outcome.done.size)
            assertEquals(outcome.plannedReps, outcome.done.sumOf { it.repsDone })
            assertEquals(outcome.plannedSeconds, outcome.done.sumOf { it.seconds })
        }
        assertTrue(freestyleOutcomes(emptyList()).isEmpty())
    }

    @Test
    fun aFreestyleSessionBeginsWithItsFirstSetAndKeepsEveryExercisesSetsAndRests() {
        // Choosing, and choosing again, only looks: no clock runs until the first set begins.
        val chosen = FreestyleState().choosing("PUSHUPS").choosing("PLANK")
        assertFalse(chosen.started)
        val plank = chosen.beginning(5_000).stopping(50_000, counted = false, unitFactor = 1.0)
        assertEquals(5_000L, plank.sessionStart)
        assertEquals(listOf(FreeSet("PLANK", seconds = 45)), plank.sets)
        // "+" to push-ups after a 30-second rest: the plank's sets stay, its rest is recorded.
        val push = plank.choosing("PUSHUPS").beginning(80_000)
        assertEquals(5_000L, push.sessionStart)
        assertEquals(30, push.sets.single().rest)
        // A set of reps waits for its reps, then joins the session's sets.
        val waiting = push.stopping(110_000, counted = true, unitFactor = 1.0)
        assertEquals(30, waiting.entering)
        val done = waiting.copy(entryReps = "12").entered(unitFactor = 1.0)
        assertEquals(listOf("PLANK", "PUSHUPS"), done.sets.map { it.code })
        assertEquals(12, done.sets.last().reps)
        assertEquals(1, done.doneOfCode)
        // Kept between visits as text, and read back the same.
        assertEquals(done, freestyleState(done.json()))
        assertEquals(FreestyleState(), freestyleState("not json"))
        // Ending on a set of reps nobody counted leaves it out; a timed set under way counts.
        assertEquals(2, done.beginning(120_000).finalSets(130_000, counted = true, unitFactor = 1.0).size)
        assertEquals(
            10,
            done.choosing("PLANK").beginning(120_000).finalSets(130_000, false, 1.0).last().seconds,
        )
        // Finish ends the last rest, as the next set ends every other: the session lasts until it is ended.
        assertEquals(90, done.finalSets(200_000, counted = true, unitFactor = 1.0).last().rest)
    }

    @Test
    fun aSessionIsOneWorkoutWithANumberOfItsOwn() {
        assertEquals(1, nextSessionNumber(emptyList(), "Freestyle"))
        val taken = listOf("Freestyle #3", "Freestyle #12", "Push day #40", "Freestyle #x")
        assertEquals(13, nextSessionNumber(taken, "Freestyle"))
        // A template named with a number of its own is still told apart from its sessions' numbers.
        assertEquals(2, nextSessionNumber(listOf("Day #2 #1"), "Day #2"))

        fun row(id: Long, at: Long, subtitle: String, code: String = "PULLUPS", kind: String = "ACTIVITY") =
            JournalEntry(
                id = id,
                day = "2026-10-16",
                occurredAt = at,
                kind = kind,
                title = "row $id",
                subtitle = subtitle,
                calories = 0.0,
                activityCode = code,
            )
        val day = listOf(
            row(1, 3_000, "Freestyle #12"),
            row(2, 1_000, "Freestyle #12"),
            row(3, 2_000, ""),
            row(4, 4_000, "Freestyle #13"),
            row(5, 2_500, "Freestyle #12", kind = KIND_NOT_DONE),
            // A synced session's subtitle is its measured figures, and never a session.
            row(6, 5_000, "5.2 km · 140 W", code = "HEALTH_CONNECT_RUNNING"),
            row(7, 6_000, "Push day #1", code = "HEALTH_CONNECT_OTHER"),
        )
        assertEquals("Freestyle #12", day.first().session)
        assertNull(day.last().session)
        assertEquals(
            listOf(listOf(3L), listOf(6L), listOf(7L), listOf(2L, 5L, 1L), listOf(4L)),
            day.foldedSessions().map { session -> session.map { it.id } },
        )
    }

    @Test
    fun aWorkoutLoggedFromTheChatIsItsStretchOrItsSetsWithMinutes() {
        // "Я бегал 25 минут": one stretch, nothing invented around it.
        assertEquals(listOf(FreeSet("RUN", 1_500)), loggedSets("RUN", 1_500, 0, 0, null))
        // "3 подхода по 10 с 20кг" and no time: a set of reps takes the estimate, rests between, none after.
        val lifts = loggedSets("SQUATS", 0, 3, 10, 20.0)
        assertEquals(listOf(30, 30, 30), lifts.map { it.seconds })
        assertEquals(listOf(DEFAULT_REST_SECONDS, DEFAULT_REST_SECONDS, 0), lifts.map { it.rest })
        assertTrue(lifts.all { it.reps == 10 && it.loadKg == 20.0 })
        assertTrue(freestyleOutcomes(lifts).single().exercise.durationMinutes > 0.0)
        // "…за 15 мин": the stated time is the sets', split evenly, and the rests are inside it.
        assertEquals(listOf(300, 300, 300), loggedSets("SQUATS", 900, 3, 10, null).map { it.seconds + it.rest })
    }

    /**
     * After a turn of the phone the templates and their exercises are read again, one after the other,
     * and the page is rebuilt in between. "Not read yet" has to be told from "none": a session drawn
     * over no exercises is Ready and nothing after it, so it would end itself with nothing done and
     * close.
     */
    @Test
    fun exercisesNotYetReadAreNotATemplateWithNone() {
        val rows = listOf(squat, press.copy(routineId = 2), plank)

        assertNull(exercisesOf(null, 1))
        assertEquals(listOf(squat, plank), exercisesOf(rows, 1))
        assertEquals(emptyList<RoutineExercise>(), exercisesOf(rows, 3))
        val bare = routinePhases(routineBlocks(emptyList()))
        assertEquals(listOf(PhaseKind.READY), bare.map { it.kind })
        assertTrue(routineOutcomes(bare, IntArray(7), BooleanArray(7), IntArray(7) { -1 }).isEmpty())
    }

    /**
     * Ten minutes of squats and ten of bench press ran one after the other and ended at the finish.
     * A row runs back from its stamp ([sessionInterval]), so both stamped at the finish, a second
     * apart, would each claim the session's last ten minutes, the second ending a second after
     * Finish. The plank between them, never begun, keeps its place and takes a second.
     */
    @Test
    fun aSessionsExercisesRunOneAfterAnotherAndEndAtTheFinish() {
        val finish = 1_794_000_000_000L
        val outcomes = listOf(
            plannedOutcome(squat),
            plannedOutcome(plank).copy(done = emptyList()),
            plannedOutcome(press),
        )

        val rows = sessionRows(NamedResources(), profile, outcomes, LocalDate.of(2026, 11, 14), finish, "Push day #3")

        assertEquals(listOf("SQUATS", "PLANK", "BENCH_PRESS"), rows.map { it.activityCode })
        assertEquals(listOf("ACTIVITY", KIND_NOT_DONE, "ACTIVITY"), rows.map { it.kind })
        assertEquals(listOf(finish - 601_000L, finish - 600_000L, finish), rows.map { it.occurredAt })
        val (squats, bench) = rows.mapNotNull { it.sessionInterval(Instant.MAX) }
        assertEquals(Instant.ofEpochMilli(finish), bench.second)
        assertTrue(squats.second <= bench.first)
        assertEquals(600_000L, squats.second.toEpochMilli() - squats.first.toEpochMilli())
        // One session of one day wherever the stamps fall: the calendar folds the rows by both.
        assertTrue(rows.all { it.day == "2026-11-14" && it.subtitle == "Push day #3" })
    }

    /**
     * A page rebuilt while its session was being written has the summary and never hears the count. It
     * counts by the rule the writer keeps: an exercise done is one, one never begun is a row that says
     * so, and one the catalogue has lost is no row at all.
     */
    @Test
    fun aPageThatNeverHeardTheCountCountsWhatTheJournalTook() {
        val outcomes = listOf(
            plannedOutcome(squat),
            plannedOutcome(plank).copy(done = emptyList()),
            plannedOutcome(squat.copy(activityCode = "GONE_FROM_THE_CATALOGUE")),
            plannedOutcome(press),
        )

        val written = sessionRows(NamedResources(), profile, outcomes, LocalDate.of(2026, 11, 14), 1_794_000_000_000L)

        assertEquals(listOf("SQUATS", "PLANK", "BENCH_PRESS"), written.map { it.activityCode })
        assertEquals(2, written.count { it.kind == "ACTIVITY" })
        assertEquals(2, loggedCount(sessionSummary(outcomes)))
        assertEquals(0, loggedCount(emptyList()))
    }

    private val console =
        FitnessMachineMeasurement(FitnessMachineType.TREADMILL, speedKph = 10.0, totalEnergyKcal = 310)

    /** Half an hour on a treadmill, as the workouts page takes it from the console's last [frame]. */
    private fun taken(frame: FitnessMachineMeasurement) = machineEntry(
        NamedResources(),
        profile,
        MachineResult(
            minutes = 30.0,
            kcal = frame.totalEnergyKcal?.takeIf { it > 0 },
            note = "",
            payloadJson = fitnessPayload(frame, "Bluetooth SIG FTMS"),
            type = frame.type,
            kph = frame.speedKph,
        ),
    )

    /**
     * A treadmill's run is filed as the catalogue's run with the console's 310 kcal, and only its frame
     * says the figure was measured. A console that counted no energy leaves the MET estimate, which
     * still follows the weight as any estimate does.
     */
    @Test
    fun aConsolesOwnEnergyIsToldFromAnEstimateByItsFrame() {
        val run = taken(console)

        assertEquals("RUN", run.activityCode)
        assertEquals(310.0, run.calories, 1e-9)
        assertTrue(run.hasMachineEnergy())
        assertFalse(run.copy(measurementPayloadJson = null).hasMachineEnergy())
        assertFalse(taken(console.copy(totalEnergyKcal = 0)).hasMachineEnergy())
        val estimated = taken(console.copy(totalEnergyKcal = null))
        assertFalse(estimated.hasMachineEnergy())
        val heavier = recalculatedActivityTimeline(listOf(estimated), baselineWeightKg = 95.0, profile = profile)
        assertTrue(heavier.single().calories > estimated.calories)
    }

    /**
     * Saving a workout sets off the pass that works every later estimate out again, the row just saved
     * among them. The console's 310 kcal has to come out of it as it went in, not as the MET figure
     * for half an hour's run.
     */
    @Test
    fun aConsolesOwnEnergyOutlastsTheRecalculationItsSaveSetsOff() {
        val run = taken(console)

        val recalculated = recalculatedActivityTimeline(listOf(run), baselineWeightKg = 80.0, profile = profile)

        assertEquals(310.0, recalculated.single().calories, 1e-9)
    }
}
