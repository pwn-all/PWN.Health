package com.pwnall.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.concurrent.thread

/**
 * The decisions the Health Connect exchange makes without asking Health Connect: whose record an id
 * names, which rows are written again, when a failure is retried, what is withdrawn. Each is held
 * apart from the exchange, where only a phone could show it going wrong.
 */
class HealthExchangeDecisionsTest {
    private val weight = "pwnfit-journal-weight:"
    private val minute = 60_000L

    private fun row(id: Long, occurredAt: Long = 1_000, title: String = "Water") = JournalEntry(
        id = id,
        day = "2026-08-01",
        occurredAt = occurredAt,
        kind = KIND_WATER,
        title = title,
        calories = 0.0,
    )

    /**
     * Room numbers a fresh journal from 1 again, so an id of the prefix and the number alone would
     * put the new install's first weigh-in over the old install's first weigh-in.
     */
    @Test
    fun theSameRowNumberInAnotherJournalIsAnotherRecord() {
        assertEquals("pwnfit-journal-weight:a1b2c3d4:1", publishedClientId(weight, "a1b2c3d4", 1))
        assertNotEquals(publishedClientId(weight, "a1b2c3d4", 1), publishedClientId(weight, "0f9e8d7c", 1))
    }

    @Test
    fun aRowNumberIsReadOnlyUnderThisJournalsOwnTag() {
        val published = publishedClientId(weight, "a1b2c3d4", 7)

        assertEquals(7L, publishedRowId(published, "a1b2c3d4"))
        // The previous install's record 7 says nothing about this journal's row 7 …
        assertNull(publishedRowId(published, "0f9e8d7c"))
        // … and neither does a record whose id carries no tag at all.
        assertNull(publishedRowId("pwnfit-journal-weight:7", "a1b2c3d4"))
        // So the tombstone this journal left for its own row 7 is no grave for either of them.
        assertNull(publishedRecordTombstoneKey(published, "0f9e8d7c", "MEASUREMENT", "2026-08-01"))
    }

    /**
     * A finished route's distance, climb and speeds are not written again at every return to the app,
     * for every route there is. Only a route this process has not published as it stands is owed.
     */
    @Test
    fun onlyTheRoutesThatChangedAreWrittenAgain() {
        val unchanged = TrackEntity(id = 1, startedAt = 1_000, endedAt = 2_000, distanceMeters = 800.0)
        val renamed = TrackEntity(id = 2, startedAt = 3_000, endedAt = 4_000, name = "Evening")
        val new = TrackEntity(id = 3, startedAt = 5_000, endedAt = 6_000)
        val published = mapOf(1L to unchanged.hashCode(), 2L to renamed.copy(name = "").hashCode())

        val owed = routesOwedToHealthConnect(listOf(unchanged, renamed, new), published)

        assertEquals(listOf(2L, 3L), owed.map(TrackEntity::id))
        assertEquals(3, routesOwedToHealthConnect(listOf(unchanged, renamed, new), emptyMap()).size)
    }

    /** Two acts logged against one earlier day stand on the same midday and say the same thing. */
    @Test
    fun aMomentTheOldJournalPublishedTwiceComesHomeAsTwoRows() {
        // The first record finds nothing standing; the second finds the row the first became.
        assertTrue(restoresAnotherRow(standing = 0, seenFromItsJournal = 0, restoring = true))
        assertTrue(restoresAnotherRow(standing = 1, seenFromItsJournal = 1, restoring = true))
        // Read again by an exchange that failed half-way, both find their rows and add none.
        assertFalse(restoresAnotherRow(standing = 2, seenFromItsJournal = 0, restoring = true))
        assertFalse(restoresAnotherRow(standing = 2, seenFromItsJournal = 1, restoring = true))
    }

    @Test
    fun theCopiesTwoInstallsPublishedOfOneEventAreStillOneEvent() {
        // The first journal's record was restored; the second journal's copy of it is counted apart.
        assertFalse(restoresAnotherRow(standing = 1, seenFromItsJournal = 0, restoring = true))
    }

    @Test
    fun onceTheRestoreIsOverAnyStandingRowSettlesIt() {
        // A second record on a standing row's moment is the copy of a row deleted here.
        assertFalse(restoresAnotherRow(standing = 1, seenFromItsJournal = 1, restoring = false))
        assertTrue(restoresAnotherRow(standing = 0, seenFromItsJournal = 3, restoring = false))
    }

    @Test
    fun onlyTheRowsThatChangedAreWrittenAgain() {
        val unchanged = row(1)
        val changed = row(2, title = "Tea")
        val new = row(3)
        val published = mapOf(1L to unchanged.hashCode(), 2L to row(2).hashCode())

        val owed = rowsOwedToHealthConnect(listOf(unchanged, changed, new), published, edited = emptySet())

        assertEquals(listOf(2L, 3L), owed.map(JournalEntry::id))
        // A process that has published nothing yet owes the whole journal.
        assertEquals(3, rowsOwedToHealthConnect(listOf(unchanged, changed, new), emptyMap(), emptySet()).size)
    }

    @Test
    fun aRowTheMirrorWasToldOfIsWrittenEvenWhenItReadsAsItDidBefore() {
        // Changed and changed back: the state in between may be the one Health Connect holds.
        val row = row(1)

        val owed = rowsOwedToHealthConnect(listOf(row), mapOf(1L to row.hashCode()), edited = setOf(1L))

        assertEquals(listOf(1L), owed.map(JournalEntry::id))
    }

    /**
     * A record built from more than a row — a period, a day's energy, a resting rate — is written
     * again only when what it says has changed. Its version moves at every exchange and says nothing.
     */
    @Test
    fun aRecordIsOwedOnlyWhenWhatItSaysHasChanged() {
        val id = "pwnfit-journal-period:2026-08-01"
        fun period(lastDay: String, version: Long) = MenstruationPeriodRecord(
            startTime = Instant.parse("2026-08-01T00:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("${lastDay}T00:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(id, version),
        )
        fun burned(kcal: Double, version: Long) = ActiveCaloriesBurnedRecord(
            startTime = Instant.parse("2026-08-01T07:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-08-01T08:00:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            energy = Energy.kilocalories(kcal),
            metadata = Metadata.manualEntry("pwnfit-journal-activekcal:a1b2c3d4:5", version),
        )
        val published = mapOf(
            id to period("2026-08-05", version = 1).recordContent(),
            "pwnfit-journal-activekcal:a1b2c3d4:5" to burned(300.0, version = 1).recordContent(),
        )

        assertTrue(
            recordsOwedToHealthConnect(listOf<Record>(period("2026-08-05", 2), burned(300.0, 2)), published).isEmpty(),
        )
        assertEquals(1, recordsOwedToHealthConnect(listOf(period("2026-08-06", 2)), published).size)
        assertEquals(1, recordsOwedToHealthConnect(listOf(burned(320.0, 2)), published).size)
        // A process that has written nothing yet owes everything.
        assertEquals(1, recordsOwedToHealthConnect(listOf(period("2026-08-05", 2)), emptyMap()).size)
    }

    @Test
    fun differentTitlesWithTheSameHashAreStillOwed() {
        val id = "plan:1"
        fun plan(title: String, version: Long) = PlannedExerciseSessionRecord(
            metadata = Metadata.manualEntry(id, version),
            startDate = LocalDate.of(2026, 8, 1),
            duration = Duration.ofMinutes(30),
            blocks = emptyList(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            title = title,
        )
        val before = plan("Plan Aa", 1)
        val after = plan("Plan BB", 2)
        assertEquals(before.recordContent().hashCode(), after.recordContent().hashCode())

        val published = mapOf(id to before.recordContent())
        assertEquals(emptyList<Record>(), recordsOwedToHealthConnect(listOf(plan("Plan Aa", 2)), published))
        assertEquals(listOf(after), recordsOwedToHealthConnect(listOf(after), published))
    }

    /** The four exports beside the journal's own write through insertChanged; a route keeps its own reckoning. */
    @Test
    fun theExportsBesideTheJournalsOwnWriteOnlyWhatHasChanged() {
        listOf("ActivityHealth.kt", "BodyHealth.kt", "CycleHealth.kt", "SessionHealth.kt").forEach { name ->
            val source = java.io.File("src/main/java/com/pwnall/health/$name").readLines()
            val unfiltered = source.filter { "insertIfAllowed(" in it }
                .filterNot { "ofRoutes(owedRoutes)" in it || "insertIfAllowed(trackSpeedRecords)" in it }
            assertTrue(name + unfiltered, unfiltered.isEmpty())
            assertTrue(name, source.any { "client.insertChanged(" in it })
        }
    }

    @Test
    fun aRowThatReachesPastNowIsNotTakenAsPublished() {
        val now = Instant.ofEpochMilli(100 * minute)

        assertTrue(settledRow(row(1, occurredAt = 100 * minute), now))
        // A session that began ten minutes ago and is filed as running for thirty is cut off at now.
        val running = row(2, occurredAt = 90 * minute).copy(kind = "ACTIVITY", durationMinutes = 30.0)
        assertFalse(settledRow(running, now))
        assertTrue(settledRow(running.copy(durationMinutes = 10.0), now))
        // A night that ends later than now is not built yet.
        assertFalse(settledRow(row(3, occurredAt = 0).copy(sleepStartAt = 0, sleepEndAt = 101 * minute), now))
    }

    @Test
    fun aFailedStepCostsNeitherTheStepsAfterItNorTheFailure() = runTest {
        val ran = mutableListOf<String>()
        val steps = ExchangeSteps()

        steps.attempt { ran += "weight" }
        steps.attempt { throw IllegalArgumentException("a record the platform would not take") }
        steps.attempt { ran += "sweep" }
        steps.attempt { throw IllegalStateException("a second failure") }

        assertEquals(listOf("weight", "sweep"), ran)
        // The exchange is still reported as failed, by the failure that came first.
        assertTrue(runCatching { steps.rethrowFirst() }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aSpentQuotaEndsTheStepsThatFollow() = runTest {
        val ran = mutableListOf<String>()
        val steps = ExchangeSteps()

        steps.attempt { throw IOException("Rate limited request quota has been exceeded.") }
        steps.attempt { ran += "next family" }

        assertTrue(ran.isEmpty())
        assertTrue(runCatching { steps.rethrowFirst() }.exceptionOrNull() is IOException)
        assertFalse(spentQuota(IOException("binder died")))
    }

    @Test
    fun aCancelledStepIsNotAFailureToCarryOnFrom() = runTest {
        val steps = ExchangeSteps()

        val thrown = runCatching { steps.attempt { throw CancellationException("the scope went") } }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
    }

    @Test
    fun anExchangeThatFinishedWellThrowsNothing() = runTest {
        val steps = ExchangeSteps()

        steps.attempt { }

        assertNull(runCatching { steps.rethrowFirst() }.exceptionOrNull())
    }

    /** App launch starts two exchanges, so the flag and the count move together, never a statement apart. */
    @Test
    fun theRunningFlagIsDownOnceEveryExchangeHasEnded() {
        var running = true
        val flag = InFlightFlag { running = it }

        val exchanges = List(2) {
            thread {
                repeat(10_000) {
                    flag.move(1)
                    flag.move(-1)
                }
            }
        }
        exchanges.forEach(Thread::join)

        assertFalse(running)
    }

    @Test
    fun theFlagStaysUpWhileOneOfTwoExchangesIsStillRunning() {
        val seen = mutableListOf<Boolean>()
        val flag = InFlightFlag { seen += it }

        flag.move(1)
        flag.move(1)
        flag.move(-1)
        flag.move(-1)

        assertEquals(listOf(true, true, true, false), seen)
    }

    @Test
    fun anExchangeRefusedForQuotaWaitsTheQuotaOut() {
        val now = 1_000 * minute
        val longAgo = now - 60 * minute

        // Every other failure is retried at the next return to the app …
        assertTrue(healthSyncDue(force = false, grantsChanged = false, lastSuccessMs = longAgo, now = now))
        // … but one refused for quota a minute ago would only be refused again.
        assertFalse(healthSyncDue(false, false, lastSuccessMs = longAgo, now = now, rateLimitedAtMs = now - minute))
        assertTrue(healthSyncDue(false, false, lastSuccessMs = longAgo, now = now, rateLimitedAtMs = now - 15 * minute))
        // "Try again" and a newly allowed type do not wait.
        assertTrue(healthSyncDue(true, false, lastSuccessMs = longAgo, now = now, rateLimitedAtMs = now - minute))
        assertTrue(healthSyncDue(false, true, lastSuccessMs = longAgo, now = now, rateLimitedAtMs = now - minute))
        // A stamp from a clock since set back holds nothing up.
        assertTrue(healthSyncDue(false, false, lastSuccessMs = longAgo, now = now, rateLimitedAtMs = now + minute))
    }

    @Test
    fun onlyADayThisAppPublishedIsWithdrawn() {
        val today = LocalDate.parse("2026-08-10")
        val covered = listOf("2026-08-07", "2026-08-08", "2026-08-09", "2026-08-10").map(LocalDate::parse)
        val published = setOf("2026-08-08", "2026-08-10").mapTo(mutableSetOf(), LocalDate::parse)

        // Not the 7th or the 9th, which have no record to delete; not today, which is still counted.
        assertEquals(listOf(LocalDate.parse("2026-08-08")), stepDaysToWithdraw(covered, published, today))
        assertTrue(stepDaysToWithdraw(covered, emptySet(), today).isEmpty())
    }

    @Test
    fun sessionDetailsAreReadOnlyWhenASessionMayBeStandingWithoutThem() {
        assertFalse(owesSessionDetails(sessionsReadWhole = false, importedSessions = 0, owedBefore = false))
        assertTrue(owesSessionDetails(sessionsReadWhole = false, importedSessions = 1, owedBefore = false))
        assertTrue(owesSessionDetails(sessionsReadWhole = true, importedSessions = 0, owedBefore = false))
        // An exchange that failed after importing the session left the read to this one.
        assertTrue(owesSessionDetails(sessionsReadWhole = false, importedSessions = 0, owedBefore = true))
    }

    /** Dinner at eight in the evening in Los Angeles, read by a phone that has since flown to Moscow. */
    @Test
    fun anImportedRecordIsFiledUnderTheDayItWasRecordedOn() {
        val dinner = Instant.parse("2026-01-06T04:00:00Z")
        val moscow = ZoneId.of("Europe/Moscow")

        assertEquals(LocalDate.parse("2026-01-05"), importedDay(dinner, ZoneOffset.ofHours(-8), moscow))
        // A record that states no offset has only the phone's zone to go by.
        assertEquals(LocalDate.parse("2026-01-06"), importedDay(dinner, null, moscow))
    }

    /**
     * The other half: the dinner goes back out with the hour it was eaten at. With the offset of the
     * zone the phone is in at the exchange, a full publication after a move would rewrite the hour of
     * every record, and a restore would then file the dinner under the next day.
     */
    @Test
    fun aRowIsPublishedWithTheOffsetItWasWrittenOn() {
        val dinner = Instant.parse("2026-01-06T04:00:00Z")
        val moscow = ZoneId.of("Europe/Moscow")
        val eaten = row(1, occurredAt = dinner.toEpochMilli()).copy(zoneOffsetSeconds = -8 * 3_600)

        assertEquals(ZoneOffset.ofHours(-8), eaten.offsetAt(dinner, moscow))
        assertEquals(LocalDate.parse("2026-01-05"), importedDay(dinner, eaten.offsetAt(dinner, moscow), moscow))
        // A row that holds none — a file's, a meter's — has only the phone's zone to go by.
        assertEquals(ZoneOffset.ofHours(3), eaten.copy(zoneOffsetSeconds = null).offsetAt(dinner, moscow))
    }

    /**
     * A walk's distance, climb and speeds go out on the route's own clock: on the one the phone is on
     * at the exchange, a process started after a flight would rewrite the hour of every route.
     */
    @Test
    fun aRouteIsPublishedWithTheOffsetItWasRecordedOn() {
        val walk = Instant.parse("2026-01-06T04:00:00Z")
        val moscow = ZoneId.of("Europe/Moscow")
        val route = TrackEntity(id = 1, startedAt = walk.toEpochMilli(), zoneOffsetSeconds = -8 * 3_600)

        assertEquals(ZoneOffset.ofHours(-8), route.offsetAt(walk, moscow))
        // A route no clock was kept for has only the phone's zone to go by.
        assertEquals(ZoneOffset.ofHours(3), route.copy(zoneOffsetSeconds = null).offsetAt(walk, moscow))
        val recording = java.io.File("src/main/java/com/pwnall/health/StepService.kt").readText()
        assertTrue("zoneOffsetSeconds = ZoneId.systemDefault().rules.getOffset(" in recording)
    }

    /**
     * Every row the cycle's and the sessions' imports build keeps the offset its record states, as
     * [measurementEntry] does. Filed with none, a cycle mark, a breathing practice or a plan adopted
     * after a reinstall would go back out on the clock the phone happened to be on.
     */
    @Test
    fun everyRowTheCycleAndSessionImportsBuildKeepsItsRecordsOffset() {
        fun body(file: String, signature: String) = java.io.File("src/main/java/com/pwnall/health/$file").readText()
            .substringAfter(signature).substringBefore("\n}\n")
        listOf(
            body("CycleHealth.kt", "internal suspend fun importCycleRecords("),
            body("SessionHealth.kt", "internal suspend fun importSessionRecords("),
        ).forEach { import ->
            val rows = Regex("healthConnectRecordKey = ").findAll(import).count()
            assertTrue(import, rows > 0)
            assertEquals(import, rows, Regex("zoneOffsetSeconds = ").findAll(import).count())
        }
    }

    /**
     * The write door stamps the clock a row is written on, and keeps it while the row's moment stays:
     * a title corrected in Moscow does not move a dinner eaten in Los Angeles. A moment changed is
     * typed on the clock the phone shows now, and a row no clock was kept for still says so.
     */
    @Test
    fun theWriteDoorKeepsTheClockARowWasWrittenOn() {
        val moscow = ZoneId.of("Europe/Moscow")
        val eaten = row(1, occurredAt = 1_767_672_000_000).copy(zoneOffsetSeconds = -8 * 3_600)

        val corrected = eaten.copy(title = "Supper", zoneOffsetSeconds = null)

        assertEquals(3 * 3_600, filedOffsetSeconds(previous = null, eaten.copy(id = 0), moscow))
        assertEquals(-8 * 3_600, filedOffsetSeconds(eaten, corrected, moscow))
        assertEquals(3 * 3_600, filedOffsetSeconds(eaten, eaten.copy(occurredAt = eaten.occurredAt + minute), moscow))
        assertNull(filedOffsetSeconds(eaten.copy(zoneOffsetSeconds = null), eaten, moscow))
    }

    /**
     * An exchange cut off after it had imported rows — the process ended, no failure written down —
     * leaves the next one nothing new to count, and the recalculation would be skipped until some
     * later import moved a row. It is owed from before the first row can land until it has run.
     */
    @Test
    fun aRecalculationAnExchangeNeverReachedIsOwedByTheNext() {
        assertTrue(owesRecalculation(journalMoved = false, owedBefore = true))
        assertTrue(owesRecalculation(journalMoved = true, owedBefore = false))
        assertFalse(owesRecalculation(journalMoved = false, owedBefore = false))
        val exchange = java.io.File("src/main/java/com/pwnall/health/HealthSync.kt").readText()
            .substringAfter("private suspend fun syncHealthConnectLocked(").substringBefore("\n}\n")
        val owed = exchange.indexOf("putBoolean(KEY_RECALCULATION_OWED, true)")
        val settled = exchange.indexOf("remove(KEY_RECALCULATION_OWED)")
        assertTrue(owed in 0 until exchange.indexOf("imports.read("))
        assertTrue(settled > exchange.indexOf("db.recalculateJournal()"))
    }

    private val sync by lazy { java.io.File("src/main/java/com/pwnall/health/HealthSync.kt").readText() }

    private fun function(signature: String) = sync.substringAfter(signature).substringBefore("\n}\n")

    /**
     * The restore is written down as done once the import has run, not once the exchange has: else
     * the first exchange of a reinstall would publish every restored row under this journal's tag and
     * sweep nothing, and Health Connect would hold each record twice until the next one.
     */
    @Test
    fun theFirstExchangeOfAReinstallSweepsTheCopiesItHasJustRestored() {
        val exchange = function("private suspend fun syncHealthConnectLocked(")
        val restored = exchange.indexOf("markRestoreDone(syncState, now)")

        assertTrue(restored > exchange.indexOf("importSessionRecords("))
        assertTrue(restored > exchange.indexOf("db.recalculateJournal()"))
        assertTrue(restored in 0 until exchange.indexOf("exportLocalHealth("))
    }

    /**
     * App launch starts two callers. Both could find the same new grant before either had written it
     * down, and each force a whole exchange of its own, so the grant is compared and written under
     * the lock.
     */
    @Test
    fun aNewGrantIsActedOnOnceByTheTwoCallersAppLaunchStarts() {
        val asking = function("internal suspend fun autoSyncHealthConnectIfDue(")
        val running = function("private suspend fun recordedHealthSync(")

        assertFalse(asking, "putString(KEY_LAST_SYNC_GRANTS" in asking)
        assertTrue(running, "if (!syncDue(prefs, startedAt, force, grants)) return@withLock" in running)
        assertTrue(running, "putString(KEY_LAST_SYNC_GRANTS, grants)" in running)
    }

    /**
     * A provider that takes a call and never answers would hold the lock every exchange and mirror
     * queue for until the process died. Only a phone shows one, so the limits are pinned in the
     * source; the exchange then ends as a Health Connect that did not answer, which is what the card
     * says.
     */
    @Test
    fun aHealthConnectThatNeverAnswersGivesTheLockBack() {
        val exchange = function("private suspend fun syncHealthConnect(")
        val mirror = function("internal suspend fun writeLocalHealthIfAvailable(")
        val locked = mirror.substringAfter("healthSyncMutex.withLock {")

        assertTrue(exchange, "withTimeoutOrNull(EXCHANGE_TIMEOUT.toMillis())" in exchange)
        assertTrue(exchange, "return checkNotNull(finished)" in exchange)
        assertTrue(mirror, locked.trimStart().startsWith("withTimeout(MIRROR_TIMEOUT.toMillis())"))
        assertEquals(SyncFailureKind.UNAVAILABLE, syncFailureKind(IllegalStateException("no answer in 10 minutes")))
    }

    /**
     * A lab sheet saved during an exchange does not stand with its button dead until the exchange lets
     * the lock go, its row already in Room. The mirror queues for the lock in the sync's own scope
     * and the caller returns; the rows are forgotten as owed only once they have gone through.
     */
    @Test
    fun aSaveMadeDuringAnExchangeDoesNotWaitForIt() {
        val mirror = function("internal suspend fun writeLocalHealthIfAvailable(")
        val launched = mirror.indexOf("healthSyncScope.launch {")

        assertTrue(mirror, launched in 0 until mirror.indexOf("healthSyncMutex.withLock {"))
        assertTrue(mirror, mirror.indexOf(".onSuccess { forgetEditedJournalRows(edited) }") > launched)
        assertFalse(mirror, "forgetEditedJournalRows(" in mirror.substring(0, launched))
    }

    /** Health Connect keeps the copy with the higher version, and answers a lower one with a success. */
    @Test
    fun aClockSetBackStillPublishesAboveTheLastVersionWritten() {
        assertEquals(5_000L, recordVersion(now = 5_000, last = 4_000))
        assertEquals(4_001L, recordVersion(now = 1_000, last = 4_000))
        assertEquals(4_001L, recordVersion(now = 4_000, last = 4_000))
    }

    /**
     * A record says how it was made rather than "entered by hand" for all: a scale's weight is
     * recorded automatically, and the night guessed from the screen is known as a guess again — or it
     * would come back from a reinstall as a night typed in, which no measured night could then replace.
     */
    @Test
    fun aRecordSaysWhetherItWasTypedMeasuredOrGuessedAndTheGuessIsKnownAgain() {
        val typed = publishedMetadata(row(1).copy(measurementSource = "MANUAL"), "id", 7)
        val weighed = publishedMetadata(row(2).copy(measurementSource = "BLUETOOTH_SCALE"), "id", 7)
        val guessed = publishedMetadata(row(3).copy(measurementSource = PHONE_ESTIMATE), "id", 7)

        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, typed.recordingMethod)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, publishedMetadata(row(4), "id", 7).recordingMethod)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weighed.recordingMethod)
        val sensed = publishedMetadata(row(5).copy(measurementSource = SOURCE_PHONE), "id", 7)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, sensed.recordingMethod)
        assertFalse(sensed.isPhoneSleepEstimate())
        assertEquals(7L, guessed.clientRecordVersion)
        assertTrue(guessed.isPhoneSleepEstimate())
        assertFalse(weighed.isPhoneSleepEstimate())
        assertFalse(typed.isPhoneSleepEstimate())
    }

    /** One figure past Health Connect's range is left out, and the rest of the meal is written. */
    @Test
    fun aNutrientHealthConnectWouldRefuseIsLeftUnsaidRatherThanTheMeal() {
        assertEquals(4.5, 4.5.nutrientFigure())
        assertEquals(0.0, 0.0.nutrientFigure())
        assertNull((-1.0).nutrientFigure())
        assertNull(250_000.0.nutrientFigure())
        assertNull(Double.NaN.nutrientFigure())
    }
}
