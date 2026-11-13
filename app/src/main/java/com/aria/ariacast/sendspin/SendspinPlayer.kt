package com.aria.ariacast.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Plays a Sendspin player stream in sync with the server (roles/player/v1.md, "Playback
 * Synchronization"): each chunk has a local play time from the time filter. The writer
 * compares where the output will actually be (AudioTrack timestamps) with where it should
 * be, then snaps on large errors and soft-corrects small ones by dropping or duplicating a
 * single frame per chunk (the spec's suggested strategy).
 */
class SendspinPlayer(private val context: Context) {
    private class Chunk(val playUs: Long, val pcm: ByteArray)

    private val queue = LinkedBlockingDeque<Chunk>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var resync = true
    private var thread: Thread? = null
    private var format: PcmFormat? = null
    private var framesWritten = 0L
    private val ts = AudioTimestamp()
    @Volatile var errorUs: Long = 0; private set

    @Synchronized
    fun start(f: PcmFormat) {
        if (running && f == format) return
        stop()
        format = f
        val channelMask = if (f.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(f.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(f.sampleRate).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(maxOf(minBuf, f.sampleRate / 10 * f.channels * 2)) // ~100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        applyVolume()
        framesWritten = 0
        resync = true
        running = true
        t.play()
        thread = Thread(::loop, "Sendspin-player").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun enqueue(localPlayUs: Long, pcm: ByteArray) {
        if (!running) return
        queue.offer(Chunk(localPlayUs, pcm))
    }

    /** stream/clear: drop buffered chunks (all, or those at/after [fromLocalUs]). */
    fun clear(fromLocalUs: Long?) {
        if (fromLocalUs == null) {
            queue.clear()
            track?.let { it.pause(); it.flush(); it.play() }
            framesWritten = 0
            resync = true
        } else {
            queue.removeIf { it.playUs >= fromLocalUs }
        }
    }

    var volume = 100
        set(v) { field = v.coerceIn(0, 100); applyVolume() }
    var muted = false
        set(v) { field = v; applyVolume() }

    /** Perceived-loudness curve from the spec: amplitude = (volume/100)^1.5. */
    private fun applyVolume() {
        track?.setVolume(if (muted) 0f else (volume / 100.0).pow(1.5).toFloat())
    }

    fun pinToBuiltInSpeaker(pin: Boolean) {
        val t = track ?: return
        t.preferredDevice = if (!pin) null else context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun nowUs() = System.nanoTime() / 1000

    /** Local time at which the next frame we write will leave the speaker. */
    private fun nextFramePresentUs(t: AudioTrack, rate: Int): Long {
        return if (t.getTimestamp(ts) && ts.framePosition > 0) {
            ts.nanoTime / 1000 + (framesWritten - ts.framePosition) * 1_000_000 / rate
        } else {
            // No timestamp yet (startup): assume the buffer is the whole latency.
            nowUs() + (framesWritten.coerceAtMost(t.bufferSizeInFrames.toLong())) * 1_000_000 / rate
        }
    }

    private fun loop() {
        while (running) {
            val chunk = try { queue.poll(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val t = track ?: break
            val f = format ?: break
            val frameBytes = f.channels * 2
            val rate = f.sampleRate
            var pcm = chunk.pcm
            if (resync && framesWritten == 0L) {
                // Startup: wait until the chunk is close enough to its time to start in one go.
                val lead = chunk.playUs - nowUs() - t.bufferSizeInFrames * 1_000_000L / rate
                if (lead > 0) { try { Thread.sleep(lead / 1000, ((lead % 1000) * 1000).toInt()) } catch (_: InterruptedException) { break } }
            }
            val error = nextFramePresentUs(t, rate) - chunk.playUs // > 0: we are late
            errorUs = error
            if (resync || abs(error) > 20_000) {
                if (error > 0) {
                    val drop = (error * rate / 1_000_000).toInt() * frameBytes
                    if (drop >= pcm.size) continue // the whole chunk is late: skip it
                    pcm = pcm.copyOfRange(drop, pcm.size)
                } else if (error < 0) {
                    val silence = ((-error) * rate / 1_000_000).toInt() * frameBytes
                    write(t, ByteArray(silence), frameBytes)
                }
                resync = false
            } else if (abs(error) > 100) {
                val frames = pcm.size / frameBytes
                if (frames > 200) { // the spec's ±0.5% cap: one frame per chunk of at least 200 frames
                    pcm = if (error > 0) pcm.copyOfRange(frameBytes, pcm.size)               // drop one frame
                          else pcm.copyOfRange(0, frameBytes) + pcm                           // duplicate one frame
                }
            }
            write(t, pcm, frameBytes)
            if (queue.isEmpty() && running) {
                // Underrun ahead: the next chunk will need a one-shot resync.
                resync = true
            }
        }
    }

    private fun write(t: AudioTrack, data: ByteArray, frameBytes: Int) {
        var off = 0
        while (off < data.size && running) {
            val n = t.write(data, off, data.size - off)
            if (n < 0) { Log.w(TAG, "AudioTrack write error $n"); return }
            off += n
        }
        framesWritten += data.size / frameBytes
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
        format = null
    }

    private companion object { const val TAG = "SendspinPlayer" }
}
