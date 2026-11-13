package com.aria.ariacast.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.pipeline.SourceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Invisible activity that Android starts when an AriaCompanion board is plugged in. Being
 * started this way grants USB permission automatically once the user ticked "always open".
 * If auto-cast is on and a previous destination is known, casting starts with no taps -
 * the USB loopback needs no screen-capture consent.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hub = CompanionHub.get(this)
        intent.usbDevice()?.let(hub::onDeviceAttached)

        val prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
        // Mode switches re-enumerate the board, which makes Android start this activity
        // again; only a real plug-in may start a cast.
        val freshPlug = !hub.isReenumeration() && !hub.isCasting
        if (freshPlug && prefs.getBoolean(CompanionHub.KEY_AUTO_CAST, true) && host != null) {
            val dest = JSONObject()
                .put("name", prefs.getString(AudioCastService.KEY_LAST_SERVER_NAME, host))
                .put("host", host)
                .put("port", prefs.getInt(AudioCastService.KEY_LAST_SERVER_PORT, 0))
                .put("platform", prefs.getString(AudioCastService.KEY_LAST_SERVER_PLATFORM, "AriaCast"))
            val start = Intent(this, AudioCastService::class.java)
                .setAction(AudioCastService.ACTION_START_SOURCE)
                .putExtra(AudioCastService.EXTRA_SOURCE, SourceKind.COMPANION_USB.id)
                .putExtra(AudioCastService.EXTRA_SERVERS_JSON, JSONArray().put(dest).toString())
            ContextCompat.startForegroundService(this, start)
        }
        finish()
    }
}
