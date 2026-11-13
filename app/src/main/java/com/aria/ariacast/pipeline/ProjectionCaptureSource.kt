package com.aria.ariacast.pipeline

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.PacketType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Captures other apps' audio through Android's playback-capture API (MediaProjection).
 *
 * Android only allows three usages to be captured: media, game and unknown. 1.x captured
 * media only, which silently dropped games and the many apps that never set a usage.
 * Games are optional because some users don't want game sounds in the house.
 */
class ProjectionCaptureSource(
    private val projection: MediaProjection,
    private val includeGames: Boolean,
) : AudioSource {

    override val kind = SourceKind.SYSTEM_CAPTURE
    override val muteLocalPlayback = true
    override val foregroundServiceType = FgsTypes.MEDIA_PROJECTION

    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before casting
    override suspend fun open(): String? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .apply { if (includeGames) addMatchingUsage(AudioAttributes.USAGE_GAME) }
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (PcmFormat.FRAME_BYTES * 4).coerceAtLeast(minBuf)

        // AudioRecord sometimes fails right after the projection is granted; retry briefly.
        repeat(3) { attempt ->
            if (attempt > 0) delay(400)
            try {
                val r = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(PcmFormat.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = r
                    return null
                }
                PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord not initialized (attempt ${attempt + 1})")
                r.release()
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord init attempt ${attempt + 1} failed: ${e.message}")
                PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord init failed (attempt ${attempt + 1}): ${e.message}")
            }
        }
        return "Audio capture could not start - check the Microphone permission for AriaCast"
    }

    override suspend fun run(emit: suspend (ByteArray) -> Unit) {
        val r = recorder ?: return
        r.startRecording()
        PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "Playback capture started (games: $includeGames)")
        while (currentCoroutineContext().isActive) {
            val frame = ByteArray(PcmFormat.FRAME_BYTES)
            val read = r.read(frame, 0, frame.size)
            when {
                read == frame.size -> emit(frame)
                read < 0 -> {
                    PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord read error: $read")
                    return
                }
            }
        }
    }

    override fun release() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private companion object {
        const val TAG = "ProjectionCapture"
    }
}
