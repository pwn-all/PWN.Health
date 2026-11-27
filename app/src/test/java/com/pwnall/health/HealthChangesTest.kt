package com.pwnall.health

import android.app.ActivityManager
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The decisions behind reading only what changed: which types take a token, where a token is filed,
 * which changed rows the window still admits, which row a deletion names, and how a feed's pages
 * are followed.
 */
class HealthChangesTest {
    private val recordId = "3f2a1c9e-7b4d-4e0a-9c1f-5d6e7f8a9b0c"
    private val start = Instant.parse("2025-09-05T10:00:00Z")
    private val end = Instant.parse("2026-09-05T10:00:00Z")

    /** A fresh install, a cleared store, a type only just allowed: all read the year once. */
    @Test
    fun aTypeWithoutATokenReadsTheWindowAndAsksForOne() {
        assertEquals(ImportPlan.Full(seedToken = true), importPlan(WeightRecord::class, false, null))
        assertEquals(ImportPlan.Full(seedToken = true), importPlan(WeightRecord::class, false, ""))
    }

    @Test
    fun aKeptTokenMakesTheNextReadIncremental() {
        assertEquals(ImportPlan.Incremental("t1"), importPlan(WeightRecord::class, false, "t1"))
    }

    /** The longest sleep of a night and the latest rate of a day are chosen among a whole window. */
    @Test
    fun anAggregatedTypeReadsWholeAndNeverTakesAToken() {
        assertEquals(ImportPlan.Full(seedToken = false), importPlan(SleepSessionRecord::class, false, null))
        assertEquals(ImportPlan.Full(seedToken = false), importPlan(HeartRateRecord::class, false, "t1"))
    }

    /** Own rows only is a narrower question than a token for the type would answer. */
    @Test
    fun aReadNarrowedToAnOriginReadsWholeWithoutAToken() {
        assertEquals(ImportPlan.Full(seedToken = false), importPlan(WeightRecord::class, true, "t1"))
    }

    /** Health Connect is asked for the window edge to edge, as the full read is. */
    @Test
    fun theWindowIsAskedForAsItsFilter() {
        assertEquals(TimeRangeFilter.between(start, end), ImportWindow(start, end).filter())
    }

    /** A source app rewriting years of history cannot pour it all into the journal in one exchange. */
    @Test
    fun aChangedRowDatedBeforeTheWindowIsHistoryLeftBehind() {
        val window = ImportWindow(start, end)

        assertTrue(window.leftBehind(start.toEpochMilli() - 1))
        assertFalse(window.leftBehind(start.toEpochMilli()))
        assertFalse(window.leftBehind(end.toEpochMilli() - 1))
    }

    /** The feed has moved past a record written while the exchange ran; time brings the window to it. */
    @Test
    fun aChangedRowDatedAfterTheWindowIsKeptForTimeToReach() {
        val window = ImportWindow(start, end)

        assertFalse(window.leftBehind(end.toEpochMilli()))
        assertFalse(window.leftBehind(end.plusSeconds(1).toEpochMilli()))
    }

    @Test
    fun eachTypeFilesItsTokenUnderItsOwnKey() {
        assertEquals("changesToken:android.permission.health.READ_WEIGHT", changesTokenKey(WeightRecord::class))
        assertNotEquals(changesTokenKey(WeightRecord::class), changesTokenKey(BloodPressureRecord::class))
    }

    @Test
    fun aMarkerNamesItsRecordWhateverThePackageOrSuffix() {
        assertEquals(recordId, markedRecordId("Health Connect:com.example.scale:$recordId"))
        assertEquals(recordId, markedRecordId("Health Connect:com.example.cycle:$recordId#end"))
    }

    /** Another record and a day's rate are none of this deletion's; an adopted row and a bare id name none. */
    @Test
    fun aDeletionNamesNothingElse() {
        assertNotEquals(recordId, markedRecordId("Health Connect:com.example.scale:another-id"))
        assertNotEquals(recordId, markedRecordId("Health Connect:heart:2026-09-05"))
        assertNull(markedRecordId(null))
        assertNull(markedRecordId(recordId))
        assertNull(markedRecordId("Health Connect:com.example.scale:"))
    }

    private data class Scripted(val name: String) : Change

    @Test
    fun thePagesOfAFeedAreFollowedInOrderToTheLastToken() = runBlocking {
        val first = ChangesPage(listOf(Scripted("a"), Scripted("b")), nextToken = "t2", hasMore = true, expired = false)
        val last = ChangesPage(listOf(Scripted("c")), nextToken = "t3", hasMore = false, expired = false)
        val pages = mapOf("t1" to first, "t2" to last)
        val asked = mutableListOf<String>()

        val feed = readChangeFeed("t1") { token ->
            asked += token
            pages.getValue(token)
        }

        assertEquals(ChangeFeed.Changes(listOf(Scripted("a"), Scripted("b"), Scripted("c")), "t3"), feed)
        assertEquals(listOf("t1", "t2"), asked)
    }

    /** Nothing changed is still an answer, and the token moves with it. */
    @Test
    fun anEmptyFeedStillMovesTheToken() = runBlocking {
        val feed = readChangeFeed("t1") { ChangesPage(emptyList(), nextToken = "t2", hasMore = false, expired = false) }

        assertEquals(ChangeFeed.Changes(emptyList(), "t2"), feed)
    }

    @Test
    fun aForgottenTokenSendsTheTypeBackToTheWindow() = runBlocking {
        val feed = readChangeFeed("stale") { ChangesPage(emptyList(), nextToken = "", hasMore = false, expired = true) }

        assertEquals(ChangeFeed.Expired, feed)
    }

    @Test
    fun aWithdrawnGrantKeepsTheTokenForLater() = runBlocking {
        val feed = readChangeFeed("t1") { throw SecurityException("READ_WEIGHT") }

        assertEquals(ChangeFeed.Denied, feed)
    }

    // An update reads every type whole once, and a whole read never learns what was deleted.

    private val weightKey = changesTokenKey(WeightRecord::class)

    /** The token an update takes out of use is the only thing that can still name those deletions. */
    @Test
    fun anUpdateRetiresTheTokensItStopsReadingWith() {
        val stored = mapOf(weightKey to "t1", "adoptedAtMs" to 5L, "changesTokensVersion" to 41L)

        assertEquals(mapOf(retiredTokenKey(weightKey) to "t1"), retiredTokens(stored))
    }

    /** One still waiting from an earlier update reaches further back than the one taken since. */
    @Test
    fun aTokenAlreadyRetiredIsNotReplacedByAYoungerOne() {
        val stored = mapOf(weightKey to "t2", retiredTokenKey(weightKey) to "t1")

        assertTrue(retiredTokens(stored).isEmpty())
    }

    /** Or the next update would file it away a second time, under a key nothing reads. */
    @Test
    fun aRetiredTokenIsNotTakenForALiveOne() {
        assertTrue(retiredTokens(mapOf(retiredTokenKey(weightKey) to "t1")).isEmpty())
    }

    // What only a phone shows is pinned in the source, as the restore's wiring is.

    private val source by lazy { File("src/main/java/com/pwnall/health/HealthSync.kt").readText() }

    /** The text of the member or function that begins [signature], to its closing brace at [indent]. */
    private fun body(signature: String, indent: String = ""): String {
        assertTrue(signature, signature in source)
        return source.substringAfter(signature).substringBefore("\n$indent}\n")
    }

    /** The whole read asks the retired token for the deletions, and lets it go with the fresh one kept. */
    @Test
    fun theWholeReadAfterAnUpdateForgetsWhatWasDeletedMeanwhile() {
        val reading = body("fun <T : Record> readWindow(", indent = "    ")
        assertTrue(reading, "if (seedToken) forgetDeletedSinceRetired(tokenKey)" in reading)
        assertTrue(reading, "remove(retiredTokenKey(tokenKey))" in reading)
        val forgetting = body("fun forgetDeletedSinceRetired(", indent = "    ")
        assertTrue(forgetting, "filterIsInstance<DeletionChange>().map { it.recordId }" in forgetting)
        assertTrue(forgetting, "forgetDeleted(deleted)" in forgetting)
    }

    /**
     * The nights' deletions come this way on every exchange, and each in a commit of its own would
     * mean one re-read of every Flow over the journal for each night another app had withdrawn.
     */
    @Test
    fun theDeletionsARetiredTokenReportsAreForgottenInOneTransaction() {
        val forgetting = body("fun forgetDeletedSinceRetired(", indent = "    ")
        val committed = forgetting.indexOf("db.withTransaction {")

        assertTrue(forgetting, committed in 0 until forgetting.indexOf("forgetDeleted(deleted)"))
    }

    /**
     * A deletion names no origin, so its rows cannot be looked up by the marker's index, and a query
     * for each would read the journal through: a feed withdrawing a month of another app's readings
     * would read it once for each. The deletions side by side are one step, matched together against
     * one read ([importedRowsOf]); the step stays where they stood among the upserts.
     */
    @Test
    fun aFeedsDeletionsAreForgottenTogetherOutOfOnePass() {
        val forgetting = body("fun forgetDeleted(", indent = "    ")
        assertTrue(forgetting, "db.dao().importedRowsOf(recordIds.toSet())" in forgetting)
        val applying = body("fun <T : Record> applyChanges(", indent = "    ")
        assertTrue(applying, "forgetDeleted(run)" in applying)
        assertTrue(applying, "deletions = null" in applying.substringAfter("is UpsertionChange ->"))
        assertFalse(applying, "forgetDeleted(change.recordId)" in applying)
    }

    /**
     * Every exchange republishes the journal, and the feed hands each record of it back as changed.
     * Placed again, it would cost three lookups a record on every return to the app, and a merge that
     * put a body value back into the measurement it had just been removed from.
     */
    @Test
    fun aRecordOfOursComingBackThroughTheFeedIsNotPlacedAgain() {
        val applying = body("fun <T : Record> applyChanges(", indent = "    ")

        assertTrue(applying, "dataOrigin.packageName == ownPackageName" in applying)
        assertTrue(applying, "if (!ownExport) {\n" in applying)
        assertTrue(applying, "steps.add(placeChanged(" in applying)
    }

    /**
     * A feed is mapped first and placed in one transaction, as a page of a whole read is. Placed a
     * change at a time, each in a commit of its own, it would have every Flow over the journal read
     * again once for every record another app had touched.
     */
    @Test
    fun aFeedIsMappedFirstAndPlacedInOneTransaction() {
        val applying = body("fun <T : Record> applyChanges(", indent = "    ")
        val placing = body("fun <T : Record> placeChanged(", indent = "    ")

        assertTrue(applying, "db.withTransaction { steps.sumOf { it() } }" in applying)
        assertTrue(placing, "val rows = toEntries(record, markerFor(record))" in placing)
        assertTrue(placing, "return { rows.count { place(record, it) } }" in placing)
    }

    // Health Connect answers an app that has left the foreground with its own records and no error.

    @Test
    fun aVisibleAppOrOneCountingStepsIsStillReadEveryAppsRecords() {
        assertTrue(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertTrue(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
    }

    @Test
    fun anAppThatHasLeftTheForegroundIsNot() {
        assertFalse(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
        assertFalse(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertFalse(readsEveryApp(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
    }

    /**
     * A step read refused halfway through an exchange is no answer at all: taken for "nobody else
     * counted a thing", it would clear the window's thirty days of a watch's totals while the
     * exchange reported a read. The refusal ends the step stage before the tables are touched, and says so.
     */
    @Test
    fun aRefusedStepReadClearsNothingAndIsNotTakenForAnAnswer() {
        val steps = body("fun syncStepDays(")
        val refused = steps.indexOf("?: return StepSyncResult(0, 0, 0, readable = false)")
        assertTrue(steps, refused in 0 until steps.indexOf("db.withTransaction {"))
        val totals = body("fun externalStepTotals(")
        assertTrue(totals, totals.substringAfter("} catch (denied: SecurityException) {").trimEnd().endsWith("null"))
    }

    /** Neither the step tables nor a change token are written from a read that may have been narrowed. */
    @Test
    fun anExchangeThatLeftTheForegroundStopsBeforeItActsOnWhatItRead() {
        val steps = body("fun syncStepDays(")
        assertTrue(
            steps,
            steps.indexOf("requireEveryAppReadable()") in 0 until steps.indexOf("db.withTransaction {"),
        )
        val reading = body("fun <T : Record> readWindow(", indent = "    ")
        assertTrue(
            reading,
            reading.indexOf("requireEveryAppReadable()") in 0 until reading.indexOf("putString(tokenKey, token)"),
        )
    }
}
