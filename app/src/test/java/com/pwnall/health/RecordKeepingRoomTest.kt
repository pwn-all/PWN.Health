package com.pwnall.health

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Rules Data.kt keeps in its SQL and its transactions, run on Room over SQLite: the weight and the
 * height the profile counts with, what a deleted line of the medical history leaves behind, what
 * the recalculation of the activities reads, and what a save answers once it has committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class RecordKeepingRoomTest {
    private lateinit var db: StepsDb
    private val profile = UserProfile(sex = "MALE", weightKg = 95.0, heightCm = 178, birthDate = "1990-04-17")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun row(kind: String, at: Long, weightKg: Double? = null, heightCm: Int? = null) = JournalEntry(
        day = "2026-10-03",
        occurredAt = at,
        kind = kind,
        title = "",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementHeightCm = heightCm,
    )

    /**
     * The profile counts with the newest weighing, as the activity timeline does. A row the editor
     * moved to another kind keeps the figures it carried, and must not stand as the weight in effect
     * while every activity after it is costed at the weighing before.
     */
    @Test
    fun aFigureLeftOnARowOfAnotherKindIsNotTheWeightInEffect() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_MEASUREMENT, at = 1_000L, weightKg = 80.0, heightCm = 180),
                row(KIND_EAT, at = 2_000L, weightKg = 70.0, heightCm = 150),
            ),
        )

        assertEquals(80.0, db.dao().latestMeasuredWeightKg().first() ?: Double.NaN, 0.0)
        assertEquals(180, db.dao().latestMeasuredHeightCm().first())
    }

    /**
     * A connected scale keeps a reading down to 2 kg, so the newest weighing cannot stand as the
     * profile's whatever it says: a child weighed after the owner would become the weight every
     * formula counts from.
     */
    @Test
    fun aWeighingBelowAnyAdultsIsNotTheWeightInEffect() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_MEASUREMENT, at = 1_000L, weightKg = 72.0),
                row(KIND_MEASUREMENT, at = 2_000L, weightKg = 9.0),
            ),
        )

        assertEquals(72.0, db.dao().latestMeasuredWeightKg().first() ?: Double.NaN, 0.0)
    }

    /**
     * The energy widget counts from the profile the food page counts from: the stored row under the
     * newest weighing. Handed the stored row, it would count from onboarding's 95 kg after a weighing
     * of 85, and its allowance would disagree with the page's from the first trip to the scales.
     */
    @Test
    fun theEnergyWidgetCountsFromTheWeighingAsThePageDoes() = runBlocking {
        db.dao().saveProfile(profile)
        db.dao().insertJournalEntries(listOf(row(KIND_MEASUREMENT, at = 1_000L, weightKg = 85.0)))

        val inEffect = db.dao().profileInEffect()

        assertEquals(profile.copy(weightKg = 85.0), inEffect)
        assertNotEquals(
            energyDay(profile, emptyList(), steps = 8_000L, year = 2026),
            energyDay(checkNotNull(inEffect), emptyList(), steps = 8_000L, year = 2026),
        )
        val widget = File("src/main/java/com/pwnall/health/FigureWidgets.kt").readText()
            .substringAfter("class EnergyWidget ", "")
            .substringBefore("WidgetFrame(")
        assertTrue(widget, "dao.profileInEffect()" in widget)
    }

    /** A line deleted here is gone, and its tombstone is what a backup restored afterwards meets. */
    @Test
    fun aDeletedHistoryLineLeavesItsTombstoneBehind() = runBlocking {
        val line = MedicalHistoryRow(key = "6f1d0c2a", kind = "CONDITION", title = "Asthma", updatedAt = 1L)
        db.dao().saveMedicalHistoryRow(line)

        db.deleteMedicalHistory(line.key)

        assertEquals(emptyList<MedicalHistoryRow>(), db.dao().allMedicalHistory())
        val tombstoned = db.dao().allTombstones().mapTo(HashSet()) { it.recordKey }
        assertEquals(emptyList<MedicalHistoryRow>(), withoutDeletedHistory(listOf(line), tombstoned))
    }

    /**
     * The recalculation reads the weighings and the activities and leaves the meals, drinks and
     * nights of an imported history where they are; what it makes of the two kinds is what the
     * timeline makes of the whole journal, the weighing before the workout included.
     */
    @Test
    fun theRecalculationCostsAnActivityAtTheWeighingBeforeIt() = runBlocking {
        db.dao().saveProfile(profile)
        val workout = row(KIND_ACTIVITY, at = 3_000L).copy(activityCode = "WALKING", met = 3.5, durationMinutes = 60.0)
        val journal = listOf(row(KIND_MEASUREMENT, at = 1_000L, weightKg = 80.0), row(KIND_EAT, at = 2_000L), workout)
        db.dao().insertJournalEntries(journal)

        db.recalculateJournal()

        val costed = db.dao().allJournal().single { it.kind == KIND_ACTIVITY }.calories
        val timeline = recalculatedActivityTimeline(journal, profile.weightKg, profile)
        assertTrue("the workout was not costed", costed > 0.0)
        assertEquals(timeline.single { it.kind == KIND_ACTIVITY }.calories, costed, 1e-9)
        val unweighed = recalculatedActivityTimeline(listOf(workout), profile.weightKg, profile).single()
        assertNotEquals("costed at the profile's weight, not the weighing's", unweighed.calories, costed, 1e-9)
    }

    /**
     * A meal is in once its transaction has committed, whatever the dose check after it does. Here
     * the check is handed an application that was never attached, so every call it makes throws; the
     * save must not throw with it, or the page would read a meal on file as one that was not saved.
     */
    @Test
    fun aDoseCheckThatFailsAfterTheCommitIsNotAFailedSave() = runBlocking {
        db.reminderApp = object : Application() {}

        val id = db.saveJournalEvent(row(KIND_EAT, at = 1_000L))

        assertEquals(listOf(id), db.dao().allJournal().map { it.id })
    }

    /**
     * Back, or a turn of the phone, cancels the page's scope a save or a delete was launched in; the
     * tap landed, so the write lands too. Here the caller is cancelled before the write so much as
     * begins: the row is still saved, and the delete still takes it and leaves its tombstone.
     */
    @Test
    fun aWriteAskedForByAPageSinceLeftStillLands() = runBlocking {
        launch {
            cancel()
            db.saveJournalEvent(row(KIND_EAT, at = 1_000L))
        }.join()
        val saved = db.dao().allJournal().single()

        launch {
            cancel()
            db.deleteJournalEvent(saved.id)
        }.join()

        assertEquals(emptyList<JournalEntry>(), db.dao().allJournal())
        assertEquals(journalTombstoneKeys(saved.id, null), db.dao().allTombstones().map { it.recordKey })
    }
}
