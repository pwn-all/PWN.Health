package com.pwnall.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.pwnall.health.ScreenShots.Themed
import com.pwnall.health.ScreenShots.shoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Personal records where a person meets them: the form's "best" line, the summary after a session of
 * one exercise, and the end page of a template session. A history of two earlier bench sessions and
 * one of pull-ups is written to the real database first. Runs only with -Pscreens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
class StrengthShotsTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private val bench = ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }

    /**
     * Written to the process's one StepsDb, which outlives each test's application, so it is emptied
     * first, or every shot after the first would read the history once more for each shot before it.
     */
    private fun history() = runBlocking {
        val db = StepsDb.get(RuntimeEnvironment.getApplication())
        // Off the test thread, which Robolectric makes the main one and Room refuses a blocking call on.
        withContext(Dispatchers.IO) { db.clearAllTables() }
        val dao = db.dao()
        fun row(at: Long, code: String, vararg sets: Pair<Int, Double>) = JournalEntry(
            day = "2026-10-20",
            occurredAt = at,
            kind = "ACTIVITY",
            title = code,
            calories = 0.0,
            activityCode = code,
            measurementPayloadJson = sets.map { (reps, kg) ->
                PlannedSet(reps = "$reps", load = if (kg > 0) "$kg" else "", completed = true)
            }.encodeToJson(),
        )
        dao.saveJournalEntry(row(1_000L, "BENCH_PRESS", 5 to 95.0, 5 to 95.0, 5 to 95.0))
        dao.saveJournalEntry(row(2_000L, "BENCH_PRESS", 5 to 100.0, 5 to 100.0, 4 to 100.0))
        dao.saveJournalEntry(row(3_000L, "PULLUPS", 10 to 0.0, 9 to 0.0, 8 to 0.0))
        assertEquals("the history is the three sessions and no more", 3, dao.allJournal().size)
    }

    private fun shots(dark: Boolean, prefix: String) {
        val dir = ScreenShots.shotsDir()
        history()
        val plan = listOf(
            PlannedSet(reps = "5", load = "100", completed = true, actualSeconds = 31, actualRestSeconds = 120),
            PlannedSet(reps = "5", load = "105", completed = true, actualSeconds = 34, actualRestSeconds = 150),
            PlannedSet(reps = "3", load = "105", completed = true, actualSeconds = 22),
        )
        compose.setContent {
            Themed(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WorkoutSummaryScreen(
                        modifier = Modifier,
                        spec = bench,
                        partner = null,
                        calories = 64.0,
                        duration = "8",
                        sets = "3",
                        reps = "13",
                        load = "103",
                        loadUnitRes = R.string.unit_kg,
                        plannedSets = plan,
                        note = "",
                        onNote = {},
                        onDone = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("Record", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.shoot(dir, "$prefix-records-summary")
    }

    private fun sessionDone(dark: Boolean, prefix: String) {
        val dir = ScreenShots.shotsDir()
        history()
        val before = runBlocking {
            recordsByExercise(StepsDb.get(RuntimeEnvironment.getApplication()).dao(), listOf("BENCH_PRESS", "PULLUPS"))
        }
        val exercises = listOf(
            DoneExercise(
                "BENCH_PRESS",
                listOf(
                    RoutineSet(reps = 5, loadKg = 100.0, seconds = 30, restSeconds = 120),
                    RoutineSet(reps = 6, loadKg = 100.0, seconds = 36, restSeconds = 120),
                    RoutineSet(reps = 5, loadKg = 100.0, seconds = 31),
                ),
                90,
            ),
            DoneExercise(
                "PULLUPS",
                listOf(
                    RoutineSet(reps = 12, seconds = 40, restSeconds = 90),
                    RoutineSet(reps = 9, seconds = 33),
                ),
                0,
            ),
        )
        compose.setContent {
            Themed(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SessionDone(Modifier, logged = 2, exercises = exercises, imperial = false, onClose = {
                    }, before = before)
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.shoot(dir, "$prefix-records-session")
    }

    private fun formLine(dark: Boolean, prefix: String) {
        val dir = ScreenShots.shotsDir()
        history()
        compose.setContent {
            Themed(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp)) {
                        ExerciseRecordLine("BENCH_PRESS", imperial = false, excludeId = null)
                        ExerciseRecordLine("PULLUPS", imperial = false, excludeId = null)
                    }
                }
            }
        }
        // Room answers on its own thread, which the compose clock does not wait for.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("Best:", substring = true)).fetchSemanticsNodes().size == 2
        }
        compose.shoot(dir, "$prefix-records-form")
    }

    @Test fun summaryLight() = shots(dark = false, prefix = "light")

    @Test fun summaryDark() = shots(dark = true, prefix = "dark")

    @Test fun sessionLight() = sessionDone(dark = false, prefix = "light")

    @Test
    @Config(qualifiers = "+ru")
    fun sessionRussian() = sessionDone(dark = false, prefix = "ru")

    @Test fun form() = formLine(dark = false, prefix = "light")
}
