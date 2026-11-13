package com.aria.ariacast.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.receiver.ReceiverHub

/**
 * Broadcast actions for automation apps. Off unless the user enables "Allow automation apps":
 *  - com.aria.ariacast.action.STOP            stop casting
 *  - com.aria.ariacast.action.RECEIVER_START  start receiving
 *  - com.aria.ariacast.action.RECEIVER_STOP   stop receiving
 * Starting a cast needs an activity (Android's screen-capture prompt): use
 * com.aria.ariacast.action.CAST on CastShortcutActivity instead.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CastShortcutActivity.KEY_AUTOMATION, false)) return
        when (intent.action) {
            ACTION_STOP -> context.startService(Intent(context, AudioCastService::class.java).setAction(AudioCastService.ACTION_STOP))
            ACTION_RECEIVER_START -> ReceiverHub.get(context).start()
            ACTION_RECEIVER_STOP -> ReceiverHub.get(context).stop()
        }
    }

    companion object {
        const val ACTION_STOP = "com.aria.ariacast.action.STOP"
        const val ACTION_RECEIVER_START = "com.aria.ariacast.action.RECEIVER_START"
        const val ACTION_RECEIVER_STOP = "com.aria.ariacast.action.RECEIVER_STOP"
    }
}
