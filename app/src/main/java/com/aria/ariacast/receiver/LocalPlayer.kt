package com.aria.ariacast.receiver

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays received PCM on this device.
 *
 * Two loop guards (docs/ROADMAP-2.0.md, "One service"):
 * - The track is not capturable (ALLOW_CAPTURE_BY_NONE), so if this device is also casting
 *   its own apps, what it receives never loops back into that capture.
 * - [pinToBuiltInSpeaker] sends the track to the phone's speaker even while an AriaCompanion
 *   board is the default output; otherwise received audio would go out over USB and come
 *   straight back in.
 */
class LocalPlayer(private val context: Context) {
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_FRAMES)
    @Volatile private var playing = false
    private var thread: Thread? = null

    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setVolume(field)
        }

    fun enqueue(frame: ByteArray) {
        if (!playing) start()
        if (queue.remainingCapacity() == 0) queue.poll() // stay low-latency: drop the oldest
        queue.offer(frame)
    }

    @Synchronized
    fun start() {
        if (playing) return
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(ReceiverServer.FRAME_BYTES * 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(volume)
            track = t
            playing = true
            t.play()
            thread = Thread({
                while (playing) {
                    try {
                        val f = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        track?.write(f, 0, f.size)
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Playback error: ${e.message}")
                    }
                }
            }, "AriaCast-receiver-playback").apply { priority = Thread.MAX_PRIORITY; start() }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack failed: ${e.message}")
        }
    }

    /** Routes playback to the built-in speaker (true) or back to Android's default output (false). */
    fun pinToBuiltInSpeaker(pin: Boolean) {
        val t = track ?: return
        if (!pin) { t.preferredDevice = null; return }
        val am = context.getSystemService(AudioManager::class.java)
        t.preferredDevice = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    fun clear() = queue.clear()

    @Synchronized
    fun stop() {
        playing = false
        thread?.interrupt()
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        queue.clear()
    }

    private companion object {
        const val TAG = "LocalPlayer"
        const val SAMPLE_RATE = 48_000
        /** 25 frames × 20 ms = 500 ms of slack before dropping. */
        const val QUEUE_FRAMES = 25
    }
}
