package com.rozin.compass.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Observable, persisted user preferences. Reads are snapshot state so the UI recomposes on change. */
@Stable
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var _trueNorth by mutableStateOf(prefs.getBoolean(KEY_TRUE_NORTH, false))
    var trueNorth: Boolean
        get() = _trueNorth
        set(value) { _trueNorth = value; prefs.edit { putBoolean(KEY_TRUE_NORTH, value) } }

    private var _haptics by mutableStateOf(prefs.getBoolean(KEY_HAPTICS, true))
    var haptics: Boolean
        get() = _haptics
        set(value) { _haptics = value; prefs.edit { putBoolean(KEY_HAPTICS, value) } }

    private var _keepScreenOn by mutableStateOf(prefs.getBoolean(KEY_SCREEN_ON, true))
    var keepScreenOn: Boolean
        get() = _keepScreenOn
        set(value) { _keepScreenOn = value; prefs.edit { putBoolean(KEY_SCREEN_ON, value) } }

    private var _theme by mutableStateOf(
        ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM }
    )
    var theme: ThemeMode
        get() = _theme
        set(value) { _theme = value; prefs.edit { putInt(KEY_THEME, value.ordinal) } }

    var askedLocation: Boolean
        get() = prefs.getBoolean(KEY_ASKED_LOCATION, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_LOCATION, value) }

    private companion object {
        const val KEY_TRUE_NORTH = "true_north"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_THEME = "theme"
        const val KEY_ASKED_LOCATION = "asked_location"
    }
}
