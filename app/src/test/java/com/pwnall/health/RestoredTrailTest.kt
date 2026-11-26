package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trail of screens a window rebuilt after a rotation, a theme switch or a process death reads
 * back ([restoredTrail]): back walks it, so a trail lost or misread here is a back press that lands
 * somewhere the person never was — or a crash on a name a later build dropped.
 */
class RestoredTrailTest {
    @Test
    fun aSavedTrailComesBackInItsOrder() {
        val saved = listOf(AppScreen.HOME, AppScreen.PROFILE, AppScreen.IMPORT).map(AppScreen::name)

        assertEquals(listOf(AppScreen.HOME, AppScreen.PROFILE, AppScreen.IMPORT), restoredTrail(saved))
    }

    @Test
    fun aScreenThisBuildNoLongerHasIsPassedOverNotThrownOn() {
        val saved = listOf(AppScreen.HOME.name, "SYNC", AppScreen.CALENDAR.name)

        assertEquals(listOf(AppScreen.HOME, AppScreen.CALENDAR), restoredTrail(saved))
    }

    @Test
    fun aTrailWithNothingLeftStandsOnTheHomeScreen() {
        assertEquals(listOf(AppScreen.HOME), restoredTrail(emptyList()))
        assertEquals(listOf(AppScreen.HOME), restoredTrail(listOf("SYNC")))
    }
}
