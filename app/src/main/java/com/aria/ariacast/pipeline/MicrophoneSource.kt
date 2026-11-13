package com.aria.ariacast.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.PacketType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Microphone and line-in source: intercom-style announcements, or a turntable / mixer
 * plugged in through a USB audio interface.
 *
 * [preferredInputId] selects a specific input device (an `AudioDeviceInfo.id`, typically
 * a USB interface); null uses the built-in microphone. For line-in the UNPROCESSED source
 * is used when available, so Android doesn't apply voice processing (noise suppression,
 * AGC) to music.
 */
class MicrophoneSource(
    private val context: Context,
    private val preferredInputId: Int?,
) : AudioSource {

    override val kind = SourceKind.MICROPHONE
    override val muteLocalPlayback = false
    override val foregroundServiceType = FgsTypes.MICROPHONE

    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI before starting
    override suspend fun open(): String? {
        val am = context.getSystemService(AudioManager::class.java)
        val device = preferredInputId?.let { id ->
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
        }
        val isLineIn = device != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
        val unprocessedSupported =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val audioSource = if (isLineIn && unprocessedSupported) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(
            PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return try {
            val r = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmFormat.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes((PcmFormat.FRAME_BYTES * 4).coerceAtLeast(minBuf))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return "The microphone could not be opened"
            }
            if (device != null) r.setPreferredDevice(device)
            recorder = r
            PacketLogger.log(
                PacketDirection.IN, PacketType.AUDIO,
                "Input opened: ${device?.productName ?: "built-in microphone"}" +
                    if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) " (unprocessed)" else ""
            )
            null
        } catch (e: Exception) {
            "The microphone could not be opened: ${e.message}"
        }
    }

    override suspend fun run(emit: suspend (ByteArray) -> Unit) {
        val r = recorder ?: return
        r.startRecording()
        while (currentCoroutineContext().isActive) {
            val frame = ByteArray(PcmFormat.FRAME_BYTES)
            val read = r.read(frame, 0, frame.size)
            if (read == frame.size) emit(frame) else if (read < 0) return
        }
    }

    override fun release() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    companion object {
        /** Input devices worth offering in the UI: built-in mic plus USB and wired inputs. */
        fun availableInputs(context: Context): List<AudioDeviceInfo> {
            val am = context.getSystemService(AudioManager::class.java)
            val wanted = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
            )
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in wanted }
        }
    }
}
