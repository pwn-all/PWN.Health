package com.pwnall.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
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
import java.time.Duration
import java.time.Instant

/**
 * [importActivityDetails] against a real journal and a Health Connect that answers its aggregate
 * from a script: which sessions are asked about, of whom, and what the answer does to the row. That
 * the aggregate counts a source's total and its minute-by-minute records once between them is the
 * platform's to keep, and only a phone shows it.
 *
 * The library keeps the answer's constructor and the request's fields to itself, so the script
 * reaches them by reflection, as HealthSweepTest does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ActivityDetailsImportTest {
    private lateinit var db: StepsDb
    private val now: Instant = Instant.parse("2026-11-01T12:00:00Z")
    private val asked = mutableListOf<AggregateRequest>()
    private var refused = false

    private val client = Proxy.newProxyInstance(
        HealthConnectClient::class.java.classLoader,
        arrayOf(HealthConnectClient::class.java),
    ) { _, method, args ->
        when (method.name) {
            "aggregate" -> {
                if (refused) throw SecurityException("READ_ACTIVE_CALORIES_BURNED")
                asked += args[0] as AggregateRequest
                answer(245.0)
            }

            else -> throw AssertionError("unexpected call: ${method.name}")
        }
    } as HealthConnectClient

    private fun answer(kcal: Double): Any {
        val key = AggregateMetric::class.java.getMethod("getMetricKey")
            .invoke(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) as String
        return AggregationResult::class.java.constructors.single()
            .newInstance(emptyMap<String, Long>(), mapOf(key to kcal), emptySet<DataOrigin>())
    }

    private fun AggregateRequest.origins(): Any? =
        javaClass.getDeclaredField("dataOriginFilter").apply { isAccessible = true }.get(this)

    private fun session(id: Long, endedAgo: Duration, code: String = "HEALTH_CONNECT") = JournalEntry(
        id = id,
        day = "2026-10-20",
        occurredAt = now.minus(endedAgo).minus(Duration.ofMinutes(30)).toEpochMilli(),
        kind = KIND_ACTIVITY,
        title = "Run",
        calories = 150.0,
        activityCode = code,
        durationMinutes = 30.0,
        measurementSource = "HEALTH_CONNECT",
        healthConnectRecordKey = "Health Connect:com.example.watch:session-$id",
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun aSessionIsAskedOfItsOwnSourceOnceAndTakesWhatItMeasured() = runBlocking {
        db.dao().insertJournalEntries(listOf(session(1, endedAgo = Duration.ofDays(10))))

        importActivityDetails(db, client, now)
        importActivityDetails(db, client, now)

        assertEquals(1, asked.size)
        assertEquals(setOf(DataOrigin("com.example.watch")), asked.single().origins())
        assertEquals(245.0, db.dao().journalEntry(1)!!.calories, 0.0)
    }

    /** A figure a person corrected is theirs; a session of this app's own carries its own energy. */
    @Test
    fun aCorrectedSessionAndOneOfOurOwnAreNotAskedAbout() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                session(1, endedAgo = Duration.ofDays(10)).copy(editedAt = now.toEpochMilli()),
                session(2, endedAgo = Duration.ofDays(10), code = "RUN").copy(healthConnectRecordKey = null),
            ),
        )

        importActivityDetails(db, client, now)

        assertEquals(0, asked.size)
        assertEquals(150.0, db.dao().journalEntry(1)!!.calories, 0.0)
    }

    /** Energy the person did not allow reading answers nothing, and the session stays owed. */
    @Test
    fun aRefusedReadLeavesTheSessionToBeAskedAgain() = runBlocking {
        db.dao().insertJournalEntries(listOf(session(1, endedAgo = Duration.ofDays(10))))
        refused = true

        importActivityDetails(db, client, now)
        refused = false
        importActivityDetails(db, client, now)

        assertEquals(1, asked.size)
        assertEquals(245.0, db.dao().journalEntry(1)!!.calories, 0.0)
    }
}
