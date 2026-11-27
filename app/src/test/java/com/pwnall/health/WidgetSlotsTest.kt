package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class WidgetSlotsTest {
    @Test
    fun unreadableConfigurationFallsBackToThreeDefaults() {
        assertEquals(3, DEFAULT_WIDGET_SLOTS.size)
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf(null))
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf("UNKNOWN"))
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf(""))
    }

    @Test
    fun everySupportedCountSurvivesRestoreWithoutPadding() {
        (1..6).forEach { count ->
            val chosen = WidgetSlot.entries.take(count)
            assertEquals(chosen, widgetSlotsOf(chosen.joinToString(",") { it.name }))
        }
    }

    @Test
    fun migrationKeepsOrderAndDuplicatesAndDropsUnknownNames() {
        assertEquals(listOf(WidgetSlot.WATER, WidgetSlot.WATER), widgetSlotsOf("WATER,WATER"))
        assertEquals(listOf(WidgetSlot.CAFFEINE, WidgetSlot.MOOD), widgetSlotsOf("CAFFEINE,FUTURE,MOOD"))
        assertEquals(List(6) { WidgetSlot.WATER }, widgetSlotsOf(List(8) { "WATER" }.joinToString(",")))
    }

    @Test
    fun allShortcutsHaveIconsAndEntryDestinations() {
        assertTrue(WidgetSlot.entries.all { it.labelRes != 0 && it.iconRes != 0 && it.route.isNotEmpty() })
        assertEquals(WidgetRoute.WATER, WidgetSlot.WATER.route)
        assertEquals(WidgetRoute.CAFFEINE, WidgetSlot.CAFFEINE.route)
        assertEquals(WidgetRoute.SMOKING, WidgetSlot.CIGARETTE.route)
        assertEquals(WidgetRoute.BATHROOM, WidgetSlot.URINATION.route)
    }

    @Test
    fun restoredWidgetsRespectTheCurrentProfile() {
        val today = LocalDate.of(2026, 9, 11)
        val child = UserProfile(birthDate = "2020-01-01", sex = "MALE", weightKg = 25.0, heightCm = 120)
        val offered = offeredWidgetSlots(child, today)
        assertFalse(WidgetSlot.CIGARETTE in offered)
        assertFalse(WidgetSlot.ALCOHOL in offered)
        assertFalse(WidgetSlot.INTIMATE in offered)
        assertFalse(WidgetSlot.CYCLE in offered)
        assertTrue(WidgetSlot.CAFFEINE in offered)
        assertTrue(WidgetSlot.CYCLE in offeredWidgetSlots(child.copy(sex = "FEMALE"), today))
        val adult = offeredWidgetSlots(child.copy(birthDate = "1990-01-01"), today)
        assertTrue(WidgetSlot.CIGARETTE in adult)
        assertTrue(WidgetSlot.ALCOHOL in adult)
        assertTrue(WidgetSlot.INTIMATE in adult)
        assertFalse(WidgetSlot.CYCLE in adult)
    }

    /** A row placed by an adult, read back after a restored backup made the profile a child's. */
    @Test
    fun aRowTheProfileNoLongerAllowsLosesThoseSlotsAndFallsBackWhenNoneAreLeft() {
        val child = offeredWidgetSlots(
            UserProfile(birthDate = "2020-01-01", sex = "MALE", weightKg = 25.0, heightCm = 120),
            LocalDate.of(2026, 9, 11),
        )
        assertEquals(listOf(WidgetSlot.WATER, WidgetSlot.MOOD), widgetSlotsOf("WATER,CIGARETTE,MOOD,ALCOHOL", child))
        assertEquals(DEFAULT_WIDGET_SLOTS, widgetSlotsOf("CIGARETTE,ALCOHOL,INTIMATE", child))
    }

    /**
     * The tiles on offer, the step goal, the units and the energy budget are all read from the
     * profile, so the saves that change them redraw the widgets: onboarding's, a restored backup's and
     * the Profile page's. Saved through the DAO alone, a child's restored profile would leave the multi
     * widget offering the tobacco tile until its half-hourly redraw. Pinned in the source, because
     * nothing on the JVM draws a widget.
     */
    @Test
    fun aSavedProfileRedrawsTheWidgets() {
        fun source(name: String) = File("src/main/java/com/pwnall/health/$name").readText()
        val door = source("Data.kt").substringAfter("suspend fun StepsDb.saveProfile(").substringBefore("\n}\n")

        assertTrue(door, "refreshWidgetsAfterWrite()" in door)
        assertTrue("StepsDb.get(context).saveProfile(it)" in source("MainActivity.kt"))
        assertTrue("db.saveProfile(profile)" in source("Backup.kt"))
        val settings = Regex("""db\.saveProfile\(\s+baseProfile\.copy\(\s+unitSystem = unitSystem,""")
        assertTrue(settings.containsMatchIn(source("ProfileScreen.kt")))
    }
}
