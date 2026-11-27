package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ThemePreferences.save] over the platform's SharedPreferences, which Robolectric runs on the JVM.
 * Reading a palette back from a map is ThemePaletteTest's; this is the writing: a colour taken back
 * to its default leaves no key behind, and the light and dark palettes are saved one at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ThemePreferencesTest {
    private val preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("theme_test", Context.MODE_PRIVATE)
    private val icon = 0xFF336699.toInt()
    private val night = 0xFF101010.toInt()

    @Before
    fun clear() {
        preferences.edit(commit = true) { clear() }
    }

    @Test
    fun aColourTakenBackToItsDefaultLeavesNoKeyAndTheOtherPaletteIsKept() {
        val store = ThemePreferences(preferences)
        store.save(true, ThemePalette(mapOf(ThemeColorRole.BACKGROUND to night)))
        store.save(false, ThemePalette(mapOf(ThemeColorRole.ICON to icon)))
        assertEquals(icon, store.palette(false).overrides[ThemeColorRole.ICON])

        store.save(false, ThemePalette())

        assertFalse(preferences.contains(themePreferenceKey(false, ThemeColorRole.ICON)))
        assertEquals(setOf(themePreferenceKey(true, ThemeColorRole.BACKGROUND)), preferences.all.keys)
        assertEquals(ThemePalette(), store.palette(false))
        assertEquals(ThemePalette(mapOf(ThemeColorRole.BACKGROUND to night)), store.palette(true))
        // A store opened afterwards reads the same.
        assertEquals(ThemePalette(mapOf(ThemeColorRole.BACKGROUND to night)), ThemePreferences(preferences).dark)
    }
}
