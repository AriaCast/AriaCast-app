package com.aria.ariacast

import android.app.Application
import android.content.Context

class AriaCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.applyTheme(themeMode)
    }
}
