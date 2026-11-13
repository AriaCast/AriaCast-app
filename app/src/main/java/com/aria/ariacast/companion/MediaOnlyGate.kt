package com.aria.ariacast.companion

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * "Media only" rules for the AriaCompanion source (docs/ariacompanion-2.md §6.2).
 *
 * Android mixes every sound headed for the board into one stream, so the app mutes what it
 * forwards whenever an audible non-media sound is playing. This file holds the pure parts:
 * which players block ([MediaOnlyRules]) and the fade timing ([GateEnvelope]).
 */
data class PlayerInfo(
    val usage: Int,
    /** Legacy stream controlling this player's volume (AudioAttributes.getVolumeControlStream). */
    val volumeStream: Int,
    /** Output device id if Android reports it (API 31+), else null. */
    val deviceId: Int?,
)

interface AudioEnv {
    fun streamVolume(stream: Int): Int
    val ringerMode: Int
    /** The board's AudioDeviceInfo id while it is a speaker, else null. */
    val companionDeviceId: Int?
}

object MediaOnlyRules {
    private val PASS = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN)
    private val NOTIFICATION_OR_RING = setOf(
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        @Suppress("DEPRECATION") AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        @Suppress("DEPRECATION") AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        @Suppress("DEPRECATION") AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
    )

    fun isBlockingUsage(usage: Int, includeGames: Boolean): Boolean = when {
        usage in PASS -> false
        usage == AudioAttributes.USAGE_GAME -> !includeGames
        else -> true
    }

    fun isNotificationOrRing(usage: Int) = usage in NOTIFICATION_OR_RING

    /** The player that closes the gate, or null if the gate may stay open. */
    fun blockingPlayer(players: List<PlayerInfo>, env: AudioEnv, includeGames: Boolean): PlayerInfo? =
        players.firstOrNull { p ->
            isBlockingUsage(p.usage, includeGames) &&
                env.streamVolume(p.volumeStream) > 0 &&
                !(isNotificationOrRing(p.usage) && env.ringerMode != AudioManager.RINGER_MODE_NORMAL) &&
                (p.deviceId == null || env.companionDeviceId == null || p.deviceId == env.companionDeviceId)
        }

    fun describe(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT -> "notification sound"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
        AudioAttributes.USAGE_ALARM -> "alarm"
        AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "call"
        AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "assistant"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "system sound"
        AudioAttributes.USAGE_GAME -> "game"
        else -> "non-media sound"
    }
}

/**
 * Gain envelope for the gate. The audio passing through has already been delayed by
 * [delayMs]; closing therefore takes effect at once (covering sounds that reached the board
 * up to [delayMs] before the callback), while opening waits [delayMs] + [holdMs] so the
 * delayed tail of the blocked sound drains first. Gain ramps linearly over [fadeMs].
 */
class GateEnvelope(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val delayMs: Long = 100,
    private val fadeMs: Long = 20,
    private val holdMs: Long = 200,
) {
    private var gain = 1f
    private var blocked = false
    private var openAtMs = 0L
    private val step = 1f / (sampleRate * fadeMs / 1000f)

    val isClosed: Boolean get() = blocked || gain < 1f

    fun setBlocked(isBlocked: Boolean, nowMs: Long) {
        if (isBlocked == blocked) return
        blocked = isBlocked
        if (!isBlocked) openAtMs = nowMs + delayMs + holdMs
    }

    /** Applies the gain to 16-bit little-endian interleaved PCM in place. */
    fun process(pcm: ByteArray, nowMs: Long) {
        val target = if (blocked || nowMs < openAtMs) 0f else 1f
        if (target == 1f && gain == 1f) return
        if (target == 0f && gain == 0f) {
            pcm.fill(0)
            return
        }
        var i = 0
        while (i + 1 < pcm.size) {
            gain = if (target > gain) minOf(1f, gain + step) else maxOf(0f, gain - step)
            // Snap the last float rounding step so an open gate is bit-exact again.
            if (gain > 0.9999f) gain = 1f else if (gain < 0.0001f) gain = 0f
            for (c in 0 until channels) {
                if (i + 1 >= pcm.size) break
                val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                val v = (s * gain).toInt()
                pcm[i] = v.toByte()
                pcm[i + 1] = (v shr 8).toByte()
                i += 2
            }
        }
    }
}
