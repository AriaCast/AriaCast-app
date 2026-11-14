package com.aria.ariacast

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light / dark / follow-system. Color itself comes from Material You (the wallpaper), so
 * this is the only appearance choice left. The mode is mirrored into AppCompat for the few
 * View-based surfaces (dialogs from services) and exposed as a flow for Compose.
 */
object ThemeUtils {
    const val MODE_NIGHT_NO = AppCompatDelegate.MODE_NIGHT_NO
    const val MODE_NIGHT_YES = AppCompatDelegate.MODE_NIGHT_YES
    const val MODE_NIGHT_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    private val _mode = MutableStateFlow(MODE_NIGHT_FOLLOW_SYSTEM)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    fun setMode(context: Context, themeMode: Int) {
        context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(SettingsActivity.KEY_THEME, themeMode).apply()
        applyTheme(themeMode)
    }

    fun applyTheme(themeMode: Int) {
        _mode.value = themeMode
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }
}
