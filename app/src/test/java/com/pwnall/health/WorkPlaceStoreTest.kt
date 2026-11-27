package com.pwnall.health

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The workplace checks' memory where a check and a person meet: a check decides on the memory it
 * read, and in the half minute its fix takes a tap, an Undo or the switch can write their own. What
 * is decided is WorkPlaceTest's; this is [WorkPlaceStore] over the platform's SharedPreferences,
 * which Robolectric runs on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkPlaceStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val start = LocalDateTime.of(2026, 10, 5, 8, 0).atZone(zone).toInstant().toEpochMilli()
    private val office = WorkPlace(lat = 50.0870, lon = 14.4210, radiusM = 150, accuracyM = 12.0, savedAt = 0L)

    /** What the check that started the session at eight remembers of it. */
    private val own = WorkAutoMemory(ownStart = start, lastInside = start)

    @Before
    fun switchOn() {
        WorkPlaceStore.forget(context)
        WorkPlaceStore.savePlace(context, office)
        WorkPlaceStore.setEnabled(context, true)
        WorkPlaceStore.saveMemory(context, own)
    }

    @Test
    fun aCheckWritesWhatItDecidedWhileNothingElseWroteMeanwhile() {
        val next = own.copy(lastInside = start + WORK_CHECK_EVERY_MS)
        assertTrue(WorkPlaceStore.saveMemoryIf(context, own, next))
        assertEquals(next, WorkPlaceStore.memory(context))
    }

    /** A tap during the fix made the day the person's: the check's stop is not written, nor acted on. */
    @Test
    fun aTapDuringTheFixWinsOverTheCheck() {
        val tapAt = start + 2 * WORK_CHECK_EVERY_MS
        WorkPlaceStore.tapped(context, tapAt)
        val stopped = own.copy(ownStart = 0L, lastInside = 0L, stoppedStart = start, stoppedAt = tapAt)

        assertFalse(WorkPlaceStore.saveMemoryIf(context, own, stopped))
        assertEquals(quietMemory(LocalDate.of(2026, 10, 5)), WorkPlaceStore.memory(context))
    }

    /** A check finishing after the switch went off, or after Remove, writes nothing back. */
    @Test
    fun noMemoryIsWrittenOnceTheFeatureIsOffOrThePointGone() {
        WorkPlaceStore.setEnabled(context, false)
        WorkPlaceStore.saveMemory(context, own)
        assertEquals(WorkAutoMemory(), WorkPlaceStore.memory(context))

        WorkPlaceStore.forget(context)
        WorkPlaceStore.setEnabled(context, true)
        WorkPlaceStore.saveMemory(context, own)
        assertEquals(WorkAutoMemory(), WorkPlaceStore.memory(context))
    }
}
