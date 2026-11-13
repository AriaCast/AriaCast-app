package com.aria.ariacast.pipeline

import android.content.pm.ServiceInfo

/**
 * Where the audio that AriaCast streams comes from.
 *
 * Every source produces the same format: 48 kHz, stereo, signed 16-bit little-endian PCM,
 * delivered in frames of exactly [PcmFormat.FRAME_BYTES] bytes (20 ms). All sinks
 * (AirPlay, Cast, DLNA, Snapcast, Sendspin, AriaCast) consume that one format from the
 * service's audio bus, so any source can feed any combination of sinks.
 */
interface AudioSource {
    /** Stable identifier, also used in intents and settings. */
    val kind: SourceKind

    /**
     * True if the phone's own media volume should be muted while this source runs, so the
     * audio is not heard twice (phone speaker + receivers). Only screen-capture audio wants
     * this: capture happens before the volume is applied. A USB loopback source must never
     * mute, because muting would silence the very stream it reads.
     */
    val muteLocalPlayback: Boolean

    /** The foreground-service type this source needs (see `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`). */
    val foregroundServiceType: Int

    /**
     * Prepare the source. Returns null on success, or a human-readable reason on failure.
     * Called once, after the service is in the foreground.
     */
    suspend fun open(): String?

    /**
     * Produce audio until the calling coroutine is cancelled or the source ends.
     * [emit] receives frames of exactly [PcmFormat.FRAME_BYTES] bytes; the array is owned by
     * the receiver (sources must not reuse it).
     */
    suspend fun run(emit: suspend (ByteArray) -> Unit)

    /** Release every resource. Safe to call more than once. */
    fun release()
}

enum class SourceKind(val id: String) {
    SYSTEM_CAPTURE("capture"),
    MICROPHONE("microphone"),
    COMPANION_USB("companion"),
    RECEIVER("receiver");

    companion object {
        fun fromId(id: String?): SourceKind? = entries.firstOrNull { it.id == id }
    }
}

object PcmFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE // one sample on every channel
    /** 20 ms of audio. Must match AudioCastService.FRAME_SIZE. */
    const val FRAME_BYTES = SAMPLE_RATE / 50 * BYTES_PER_FRAME

    fun bytesForMillis(ms: Int): Int = SAMPLE_RATE / 1000 * ms * BYTES_PER_FRAME
}

/** Foreground-service types, in one place so sources don't each need the SDK check. */
object FgsTypes {
    const val MEDIA_PROJECTION = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    const val MEDIA_PLAYBACK = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
}
