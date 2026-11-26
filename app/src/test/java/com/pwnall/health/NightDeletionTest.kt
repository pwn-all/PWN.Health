package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * The night's own road to a deletion at its source, run whole: [HealthImport.readNightlySleep]
 * against a real journal and a Health Connect that answers from a script. Sleep is never read by a
 * change token, so the road keeps one for the deletions alone: without it, a night deleted in the
 * watch's app would stand in the journal for good.
 *
 * The library keeps its answers' constructors to itself, so the script reaches them by reflection,
 * as HealthSweepTest does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NightDeletionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
    private val retiredKey = retiredTokenKey(changesTokenKey(SleepSessionRecord::class))
    private val now: Instant = Instant.parse("2026-11-01T12:00:00Z")
    private lateinit var db: StepsDb

    private var issued = 0
    private val feedsAsked = mutableListOf<String>()
    private var deleted: List<String> = emptyList()

    private val client = Proxy.newProxyInstance(
        HealthConnectClient::class.java.classLoader,
        arrayOf(HealthConnectClient::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getChangesToken" -> "issued-${++issued}"

            "getChanges" -> {
                feedsAsked += args[0] as String
                val changes = deleted.map { DeletionChange::class.java.constructors.single().newInstance(it) }
                ChangesResponse::class.java.constructors.single { it.parameterCount == 4 }
                    .newInstance(changes, "after-${feedsAsked.size}", false, false)
            }

            "readRecords" -> ReadRecordsResponse::class.java.constructors.single { it.parameterCount == 2 }
                .newInstance(emptyList<SleepSessionRecord>(), null)

            else -> throw AssertionError("unexpected call: ${method.name}")
        }
    } as HealthConnectClient

    private fun night(id: Long, marker: String?) = JournalEntry(
        id = id,
        day = "2026-10-30",
        occurredAt = Instant.parse("2026-10-30T06:00:00Z").toEpochMilli(),
        kind = KIND_MEASUREMENT,
        title = "Sleep",
        calories = 0.0,
        measurementSource = if (marker == null) "MANUAL" else "HEALTH_CONNECT",
        healthConnectRecordKey = marker,
        sleepStartAt = Instant.parse("2026-10-29T22:30:00Z").toEpochMilli(),
        sleepEndAt = Instant.parse("2026-10-30T06:00:00Z").toEpochMilli(),
    )

    @Before
    fun open() {
        prefs.edit(commit = true) { clear() }
        db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    private suspend fun exchange() = HealthImport(context, db, client, now).readNightlySleep()

    /** The first exchange keeps a token for the deletions alone, and asks it, not a new one, after that. */
    @Test
    fun theNightKeepsOneTokenForItsDeletionsAndFollowsIt() = runBlocking {
        exchange()
        exchange()

        assertEquals(1, issued)
        assertEquals(listOf("issued-1", "after-1"), feedsAsked)
        assertEquals("after-2", prefs.getString(retiredKey, null))
    }

    /** The night its watch deleted goes; one corrected by hand, one typed in and another night stay. */
    @Test
    fun aNightDeletedAtItsSourceLeavesTheJournalAndNothingElseDoes() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                night(1, "Health Connect:com.example.watch:night-1"),
                night(2, "Health Connect:com.example.watch:night-2"),
                night(3, "Health Connect:com.example.watch:night-3").copy(editedAt = now.toEpochMilli()),
                night(4, marker = null),
            ),
        )
        deleted = listOf("night-1", "night-3")

        exchange()

        assertEquals(listOf(2L, 3L, 4L), db.dao().allJournal().map(JournalEntry::id).sorted())
    }
}
