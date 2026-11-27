package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset

/**
 * [removeStalePwnFitRecords] run whole, against the sync's own preferences and a Health Connect
 * that answers from a script. The choice itself is [staleRecordIds]'s and HealthConnectRestoreTest
 * tables it; this is the reading and the deleting around it, which decide whether the choice is
 * ever acted on — and the writing it pairs with, which leaves alone what has not changed.
 *
 * The library builds its records' metadata and its read answers itself and keeps those
 * constructors to itself, so the script reaches them by reflection: a test that called them
 * outright would be one the library's lint refuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class HealthSweepTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
    private val now: Instant = Instant.parse("2026-11-01T12:00:00Z")
    private val adoptedAt: Instant = Instant.parse("2026-10-01T12:00:00Z")

    // No type the restore reads back starts so, so the sweep reads one range and judges by the adoption.
    private val prefix = "pwnfit-test-route:"

    /** A Health Connect that hands out [pages] in order and writes down what it is asked to write and delete. */
    private class ScriptedHealthConnect(private val pages: List<Any>) {
        var reads = 0
        val inserted = mutableListOf<Any?>()
        val deleted = mutableListOf<String>()
        val client = Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "readRecords" -> pages[reads++]

                "insertRecords" -> {
                    inserted.addAll(args[0] as List<*>)
                    null
                }

                "deleteRecords" -> {
                    (args[1] as List<*>).mapTo(deleted) { it.toString() }
                    Unit
                }

                else -> throw AssertionError("unexpected call: ${method.name}")
            }
        } as HealthConnectClient
    }

    private fun page(records: List<WeightRecord>, token: String?): Any =
        ReadRecordsResponse::class.java.constructors.single { it.parameterCount == 2 }.newInstance(records, token)

    private fun weighIn(id: String, clientRecordId: String, writtenAt: Instant): WeightRecord {
        val metadata = Metadata::class.java.constructors.single { it.parameterCount == 7 }
            .newInstance(0, id, DataOrigin(context.packageName), writtenAt, clientRecordId, 0L, null) as Metadata
        return WeightRecord(
            time = writtenAt.minusSeconds(3_600),
            zoneOffset = ZoneOffset.UTC,
            weight = Mass.kilograms(70.0),
            metadata = metadata,
        )
    }

    @Before
    fun startEmpty() {
        prefs.edit(commit = true) { clear() }
        publishedRecords.clear()
    }

    /**
     * A database quarantined or set aside starts over inside the same install, and the preferences
     * that described the lost journal go with it: left behind, they would make the next exchange read
     * the empty journal as every row deleted and withdraw the history from the one store that still
     * holds it.
     */
    @Test
    fun aDatabaseStartingOverWithdrawsNothingAtItsFirstExchange() = runBlocking {
        prefs.edit(commit = true) {
            putBoolean("restoreDone", true)
            putLong("adoptedAtMs", adoptedAt.toEpochMilli())
            putString(changesTokenKey(WeightRecord::class), "t1")
        }

        forgetHealthSyncState(context)
        val health = ScriptedHealthConnect(emptyList())
        removeStalePwnFitRecords(context, health.client, now, WeightRecord::class, prefix, desiredIds = emptySet())

        assertTrue(prefs.all.toString(), prefs.all.isEmpty())
        assertEquals(0, health.reads)
        assertTrue(health.deleted.isEmpty())
    }

    /**
     * Health Connect's own guide says the last page may carry "" rather than null. A sweep waiting
     * for null would ask for the same page for ever, with the exchange's lock held. It reads once, and
     * withdraws only the record this install wrote and no row claims.
     */
    @Test
    fun aLastPageMarkedWithAnEmptyTokenEndsTheSweep() = runBlocking {
        prefs.edit(commit = true) {
            putBoolean("restoreDone", true)
            putLong("adoptedAtMs", adoptedAt.toEpochMilli())
        }
        val records = listOf(
            weighIn("gone", "${prefix}1", adoptedAt.plusSeconds(60)),
            weighIn("claimed", "${prefix}2", adoptedAt.plusSeconds(60)),
            weighIn("history", "${prefix}3", adoptedAt.minusSeconds(60)),
        )
        val health = ScriptedHealthConnect(listOf(page(records, token = "")))

        removeStalePwnFitRecords(context, health.client, now, WeightRecord::class, prefix, setOf("${prefix}2"))

        assertEquals(1, health.reads)
        assertEquals(listOf("gone"), health.deleted)
    }

    /**
     * The body, the cycle, a session's figures and the day's energy are not written whole at every
     * exchange, which would move the version and nothing else. A record goes once, and again when it
     * changes.
     */
    @Test
    fun aRecordIsWrittenOnceAndAgainOnlyWhenWhatItSaysHasChanged() = runBlocking {
        val health = ScriptedHealthConnect(emptyList())
        val scale = JournalEntry(
            id = 7,
            day = "2026-11-01",
            occurredAt = now.toEpochMilli(),
            kind = KIND_MEASUREMENT,
            title = "Scale",
            calories = 0.0,
            measurementPayloadJson = """{"bodyWaterMassKg":40.5}""",
        )
        suspend fun export(row: JournalEntry, version: Long) =
            exportBodyRecords(context, health.client, now, listOf(row), profile = null, ZoneOffset.UTC, version)

        export(scale, version = 1)
        export(scale, version = 2)
        assertEquals(1, health.inserted.size)

        export(scale.copy(measurementPayloadJson = """{"bodyWaterMassKg":41.0}"""), version = 3)
        assertEquals(2, health.inserted.size)
    }

    /** A period withdrawn and marked again on the same days says what it said before, and is written. */
    @Test
    fun aRecordTheSweepWithdrawsIsWrittenAgainWhenItsRowReturns() = runBlocking {
        prefs.edit(commit = true) {
            putBoolean("restoreDone", true)
            putLong("adoptedAtMs", adoptedAt.toEpochMilli())
        }
        publishedRecords["${prefix}1"] = "one"
        publishedRecords["${prefix}2"] = "two"
        val records = listOf(
            weighIn("gone", "${prefix}1", adoptedAt.plusSeconds(60)),
            weighIn("claimed", "${prefix}2", adoptedAt.plusSeconds(60)),
        )
        val health = ScriptedHealthConnect(listOf(page(records, token = null)))

        removeStalePwnFitRecords(context, health.client, now, WeightRecord::class, prefix, setOf("${prefix}2"))

        assertEquals(listOf("gone"), health.deleted)
        assertEquals(setOf("${prefix}2"), publishedRecords.keys)
    }
}
