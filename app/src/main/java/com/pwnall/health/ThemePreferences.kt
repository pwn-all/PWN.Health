package com.pwnall.health

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

internal val LocalThemePreferences = staticCompositionLocalOf<ThemePreferences> {
    error("Theme preferences must be provided by PwnFitTheme")
}

internal val LocalThemePalette = staticCompositionLocalOf { ThemePalette() }

internal class ThemePreferences(private val preferences: SharedPreferences) {
    var light by mutableStateOf(read(false))
        private set
    var dark by mutableStateOf(read(true))
        private set

    fun palette(darkTheme: Boolean): ThemePalette = if (darkTheme) dark else light

    fun save(darkTheme: Boolean, palette: ThemePalette) {
        preferences.edit {
            ThemeColorRole.entries.forEach { role ->
                val key = themePreferenceKey(darkTheme, role)
                val color = palette.overrides[role]
                if (color == null) remove(key) else putString(key, themeHex(color))
            }
        }
        reload()
    }

    fun reload() {
        light = read(false)
        dark = read(true)
    }

    private fun read(darkTheme: Boolean): ThemePalette = themePaletteFromPreferences(preferences.all, darkTheme)
}

internal fun themePreferenceKey(dark: Boolean, role: ThemeColorRole): String =
    "${if (dark) "dark" else "light"}.${role.name}"

internal fun themePaletteFromPreferences(values: Map<String, *>, dark: Boolean): ThemePalette = ThemePalette(
    ThemeColorRole.entries.mapNotNull { role ->
        val value = values[themePreferenceKey(dark, role)] as? String
        value?.let(::parseThemeHex)?.let { role to it }
    }.toMap(),
)

@Composable
internal fun rememberThemePreferences(): ThemePreferences {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("theme_colors", Context.MODE_PRIVATE) }
    val store = remember(preferences) { ThemePreferences(preferences) }
    DisposableEffect(preferences, store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> store.reload() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return store
}
