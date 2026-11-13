package com.aria.ariacast

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/**
 * A random per-install id. This device's own receiver advertises it (mDNS TXT `device_id`
 * and the UDP discovery reply) so the sender side can hide its own receiver from the
 * speaker list - casting to yourself would just loop the audio.
 */
object DeviceIdentity {
    private const val KEY = "device_identity"
    @Volatile private var cached: String? = null

    fun id(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
        }.also { cached = it }
    }

    /** A friendly default name for this device as a receiver, e.g. "Pixel 8" or the user-set device name. */
    fun defaultName(context: Context): String {
        val userName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return userName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "AriaCast"
    }
}
