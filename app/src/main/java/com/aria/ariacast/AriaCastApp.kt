package com.aria.ariacast

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AriaCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.aria.ariacast.airplay.ProtoLog.sink = com.aria.ariacast.airplay.PacketLogSink
        com.aria.ariacast.quirks.KnownSpeakersStore.init(this)
        // A newer list of known speakers, at most once a day and only if the user keeps this on.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { com.aria.ariacast.quirks.SpeakerListSync.syncIfDue(this@AriaCastApp) }
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.applyTheme(themeMode)
        // The few View-based dialogs (update prompt) follow Material You like the Compose UI.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        if (com.aria.ariacast.webremote.WebRemote.isEnabled(this)) com.aria.ariacast.webremote.WebRemote.start(this)
        // Sendspin follows Receive and the Live input switch from here on, so Music Assistant
        // can reach this device as a Live Input even while it only sends.
        com.aria.ariacast.sendspin.SendspinHub.get(this).refresh()
    }
}
