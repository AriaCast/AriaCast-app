package com.aria.ariacast

import android.app.Application
import android.content.Context

class AriaCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.aria.ariacast.airplay.ProtoLog.sink = com.aria.ariacast.airplay.PacketLogSink
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.applyTheme(themeMode)
        // The few View-based dialogs (update prompt) follow Material You like the Compose UI.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        if (com.aria.ariacast.webremote.WebRemote.isEnabled(this)) com.aria.ariacast.webremote.WebRemote.start(this)
        // Sendspin runs on its own switch, not only while the receiver is on, so Music Assistant
        // can reach this device to use it as a Live Input.
        com.aria.ariacast.sendspin.SendspinHub.get(this).let { if (it.enabled) it.start(com.aria.ariacast.receiver.ReceiverHub.get(this).name) }
    }
}
