package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the editor's composables do with the decisions its other tests pin, read from the source: a
 * JVM test cannot turn a phone or tap a tab.
 */
class EditorWiringTest {
    private fun function(name: String): String {
        val text = File("src/main/java/com/pwnall/health/EntryEditorScreen.kt").readText()
        assertTrue("fun $name(", "fun $name(" in text)
        return text.substringAfter("fun $name(").substringBefore("\n}\n")
    }

    /**
     * A phone turned in the middle of a set recreates the activity, and the card comes back counting:
     * back at nought with Start showing, it would never reach Stop, which writes the count into the form.
     */
    @Test
    fun theRepCounterCrossesARecreation() {
        val card = function("RepCounterCard")
        listOf("running", "reps", "seconds", "startedAt").forEach {
            assertTrue(it, "var $it by rememberSaveable(spec.code)" in card)
        }
        // The samples do not cross, so the recount goes on top of what they had counted.
        assertTrue(card, "reps = carried + count.reps" in card)
        assertTrue(card, "onStop(reps, seconds)" in card)
    }

    /** The rest timer, likewise: a turn does not put it back at nought, stopped, in the middle of a rest. */
    @Test
    fun theRestTimerCrossesARecreation() {
        val timer = function("ExerciseTimer")
        assertTrue(timer, "var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }" in timer)
        assertTrue(timer, "var typed by rememberSaveable { mutableIntStateOf(0) }" in timer)
    }

    /**
     * The day and the time a new row is stamped with come off one reading of the clock. Read twice, a
     * Save on the stroke of midnight would take the old day and the new day's 00:00: a whole day back.
     */
    @Test
    fun aSaveReadsTheClockOnce() {
        val editor = function("EntryEditorScreen")
        assertTrue("recordingDay = initialDay?.let(LocalDate::parse) ?: clock.toLocalDate()," in editor)
        assertTrue("now = clock.toLocalTime()," in editor)
        assertFalse("LocalTime.now()" in editor)
    }

    /** Save and the bin both hand [writeThenMirror] the page's answer to a write that threw. */
    @Test
    fun aWriteThatThrowsLightsSaveAgain() {
        val editor = function("EntryEditorScreen")
        assertEquals(2, Regex("failed = saveRefused,").findAll(editor).count())
        assertTrue("val saveRefused: () -> Unit = {\n        saving = false\n        saveFailed = true" in editor)
    }

    /** No strip without a way to change tabs, and a saved row is handed none — [measurementTabsOffered]. */
    @Test
    fun aSavedMeasurementDrawsNoTabStrip() {
        assertTrue("if (onMode != null) {\n        MeasurementTabs(mode, onMode)" in function("MeasurementFields"))
        assertTrue("onMode = if (measurementTabsOffered(entryId)) {" in function("EntryEditorScreen"))
    }

    /** Save and the bin: both through [writeThenMirror], and no mirror left standing before the way back. */
    @Test
    fun saveAndDeleteLetThePageGoBeforeTheMirror() {
        val editor = function("EntryEditorScreen")
        assertEquals(2, Regex("""writeThenMirror\(""").findAll(editor).count())
        assertFalse("writeLocalHealthIfAvailable(context, db)\n" in editor)
        // And both by the one way out, under the one flag: a bin tapped twice would otherwise leave twice.
        assertEquals(2, Regex("release = leaveSaved,").findAll(editor).count())
        assertTrue("if (!saving) {\n                                saving = true" in editor)
    }

    /**
     * A phone turned between Save and the way back brings the form back filled in over a row
     * already written, where the next Save would write it again. The flag crosses the recreation with the
     * fields, the page that comes back with it set waits for how the write ended and takes the way
     * back or says it was refused, and the write has begun before the tap returns — a launch still
     * queued when the page goes never runs.
     */
    @Test
    fun aSaveCrossesARecreation() {
        val editor = function("EntryEditorScreen")
        assertTrue("var saving by rememberSaveable(entryId) { mutableStateOf(false) }" in editor)
        assertTrue(
            "val ended = editorWrite.first { it != EditorWrite.RUNNING }\n" +
                "            if (ended == EditorWrite.REFUSED) saveRefused() else leaveSaved()" in editor,
        )
        assertTrue("scope.launch(start = CoroutineStart.UNDISPATCHED) {\n            writeThenMirror(" in editor)
        assertTrue("release = leaveSaved," in editor)
    }

    /**
     * A pulse measured on behind a locked screen would be answered from part of a recording, or from
     * the hand on the power button, and put in the picker unheard. The activity stopping ends the pass as
     * Cancel does, at once: a stopped activity recomposes nothing that would end it by its key.
     */
    @Test
    fun aPulseMeasurementEndsWhenTheActivityStops() {
        val card = function("PulseMeasureCard")
        assertTrue(card, "pass = coroutineContext.job" in card)
        assertTrue(card, "onStopOrDispose {\n            pass?.cancel()\n            run = 0" in card)
    }

    /** Save is dark, and a tap that still lands does nothing, until the row is here — [savedRowLoaded]. */
    @Test
    fun saveWaitsForTheRowBeingCorrected() {
        val editor = function("EntryEditorScreen")
        assertTrue("enabled = valid && !saving && savedRowLoaded(entryId, original)," in editor)
        assertTrue("if (saving || !savedRowLoaded(entryId, original)) return" in editor)
    }

    /** The typed fields stand where [workoutTyped] says, and the session nowhere it does. */
    @Test
    fun aWorkoutThatIsOverIsGivenTheTypedFields() {
        val editor = function("EntryEditorScreen")
        assertTrue("reported = typedWorkout," in editor)
        assertTrue("!spec.countedForm(workoutSetsJson) && !typedWorkout" in editor)
        assertTrue("typedWorkout = typedWorkout," in editor)
    }

    /** Which saved rows the measurement form opens on is asked of [measurementFormShows], the tape's among them. */
    @Test
    fun theMeasurementFormIsGivenTheRowsItShows() {
        assertTrue("kind == KIND_MEASUREMENT && measurementFormShows(original)" in function("EntryEditorScreen"))
    }

    /** The exercise button asks what it may offer while a session stands — [mainExerciseChoices]. */
    @Test
    fun theExerciseButtonOffersWhatASessionAllows() {
        val fields = function("ActivityFields")
        assertTrue("catalogue = mainExerciseChoices(draftChanged, workoutSetsJson, partner)" in fields)
    }
}
