package com.aria.ariacast.airplay

import android.util.Log
import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.PacketType

/**
 * Sends the protocol clients' log to logcat and to the in-app packet log, so a log a
 * user exports shows how far an AirPlay or Cast session got (pairing, SETUP replies,
 * timing) instead of only "streaming".
 */
object PacketLogSink : ProtoLog.Sink {
    /** Per-packet chatter that would push the handshake lines out of the 500-entry log. */
    private val noisyDebugTags = setOf("RtpAudioSender", "DacpServer")

    override fun log(level: Int, tag: String, message: String, error: Throwable?) {
        when (level) {
            ProtoLog.DEBUG -> Log.d(tag, message, error)
            ProtoLog.INFO -> Log.i(tag, message, error)
            ProtoLog.WARN -> Log.w(tag, message, error)
            else -> Log.e(tag, message, error)
        }
        if (level == ProtoLog.DEBUG && tag in noisyDebugTags) return
        val text = "[$tag] $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, text)
    }
}
