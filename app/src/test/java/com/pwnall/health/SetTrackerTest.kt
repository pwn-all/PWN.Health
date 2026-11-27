package com.pwnall.health

import android.hardware.Sensor
import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.ShadowPowerManager

/**
 * What a set of a counted exercise asks of the framework — see [SetTracker]: the sensor that counts
 * it while it runs, and a wake lock, so a phone in a pocket goes on delivering. Both are let go at
 * Stop: a lock left held keeps the processor awake for the two hours a set may last. Robolectric's
 * sensor and power managers stand in for the phone's, so this runs in every `check`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SetTrackerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sensors = shadowOf(context.getSystemService(SensorManager::class.java))

    /** Nothing on the phone can count a rope: the set is asked about by hand and keeps nothing awake. */
    @Test
    fun aPhoneWithoutTheSensorIsBlindAndHoldsNoLock() {
        val tracker = SetTracker("JUMP_ROPE", context)

        tracker.start()

        assertTrue(tracker.blind)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun aSetHoldsItsSensorAndTheLockUntilItStops() {
        sensors.addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val tracker = SetTracker("JUMP_ROPE", context)

        tracker.start()

        assertFalse(tracker.blind)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, sensors.listeners.size)

        tracker.stop()

        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(sensors.listeners.isEmpty())
        assertFalse(tracker.interrupted)
    }

    /**
     * A template's session paused: the set lets its sensor and the lock go for the pause rather than
     * counting on and keeping the processor awake, and Continue takes both up again.
     */
    @Test
    fun aPauseLetsTheSensorAndTheLockGoAndContinueTakesThemBack() {
        sensors.addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val tracker = SetTracker("JUMP_ROPE", context)
        tracker.start()

        tracker.stop()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(sensors.listeners.isEmpty())

        tracker.resume()
        assertFalse(tracker.blind)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, sensors.listeners.size)
        tracker.stop()
    }
}
