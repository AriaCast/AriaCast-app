package com.aria.ariacast

import android.app.Application
import android.content.Context

class AriaCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.applyTheme(themeMode)
        // The few View-based dialogs (update prompt) follow Material You like the Compose UI.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        if (com.aria.ariacast.webremote.WebRemote.isEnabled(this)) com.aria.ariacast.webremote.WebRemote.start(this)
    }
}
