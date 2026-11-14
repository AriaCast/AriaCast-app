package com.aria.ariacast.companion

import com.aria.ariacast.pipeline.AudioSource
import com.aria.ariacast.raop.AudioResampler
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.aria.ariacast.pipeline.FgsTypes
import com.aria.ariacast.pipeline.FrameChunker
import com.aria.ariacast.pipeline.PcmFormat
import com.aria.ariacast.pipeline.SourceKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Audio from the AriaCompanion USB loopback (docs/ariacompanion-2.md §6.1).
 *
 * PCM chunks from the board are re-chunked into 20 ms frames, silence is inserted where the
 * USB timeline shows gaps, frames pass through a 100 ms delay line and then the media-only
 * gate, and finally go onto the audio bus.
 */
class CompanionSource internal constructor(private val hub: CompanionHub) : AudioSource {
    override val kind = SourceKind.COMPANION_USB
    // Never mute: the phone's media volume scales exactly the audio the board loops back.
    override val muteLocalPlayback = false
    override val foregroundServiceType = FgsTypes.CONNECTED_DEVICE

    private val channel = Channel<CompanionEvent>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun open(): String? {
        // Right after plugging in, the board may still be opening.
        if (withTimeoutOrNull(5000) { hub.status.first { it is CompanionStatus.Connected } } == null) {
            return "AriaCompanion is not plugged in"
        }
        hub.events = channel
        hub.setCasting(true)
        // The USB board re-enumerates as a speaker; the Bluetooth one connects A2DP. Both take a few seconds.
        val ok = withTimeoutOrNull(12_000) { hub.speakerActive.first { it } }
        return if (ok == null) "AriaCompanion did not switch to speaker mode" else null
    }

    override suspend fun run(emit: suspend (ByteArray) -> Unit) {
        val chunker = FrameChunker()
        val gaps = SofGapFiller(PcmFormat.SAMPLE_RATE, PcmFormat.BYTES_PER_FRAME)
        val envelope = GateEnvelope(PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS, delayMs = DELAY_FRAMES * 20L)
        val delayLine = ArrayDeque<ByteArray>(DELAY_FRAMES + 1)
        var formatOk = true
        // Bluetooth board: SBC frames, decoded here and brought to 48 kHz stereo.
        val sbc = SbcDecoder()
        var resampler: AudioResampler? = null
        var resamplerRate = 0

        suspend fun onFrame(frame: ByteArray) {
            delayLine.addLast(frame)
            if (delayLine.size > DELAY_FRAMES) {
                val out = delayLine.removeFirst()
                val now = System.currentTimeMillis()
                envelope.setBlocked(hub.gate.state.value.blocked, now)
                envelope.process(out, now)
                emit(out)
            }
        }

        // Bluetooth board only: frames go through a jitter buffer played on a fixed 20 ms clock.
        val jitter = JitterBuffer(PcmFormat.FRAME_BYTES)
        var pacer: Job? = null
        var lastArrival = 0L
        var maxGapMs = 0L
        var lastReport = System.currentTimeMillis()

        coroutineScope {
        fun startPacer() {
            if (pacer != null) return
            pacer = launch {
                val frameNs = 20_000_000L
                var next = System.nanoTime()
                while (isActive) {
                    jitter.next()?.let { onFrame(it) }
                    next += frameNs
                    val wait = next - System.nanoTime()
                    if (wait > 0) delay(wait / 1_000_000L) else if (wait < -200_000_000L) next = System.nanoTime() // fell far behind: resync
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 10_000) {
                        Log.d(TAG, "BT link: max gap ${maxGapMs} ms, queue ${jitter.size}, underruns ${jitter.underruns}, drops ${jitter.drops}")
                        maxGapMs = 0; lastReport = now
                    }
                }
            }
        }

        try { for (event in channel) {
            when (event) {
                is CompanionEvent.Format -> {
                    formatOk = event.sampleRate == PcmFormat.SAMPLE_RATE && event.bits == 16 &&
                        event.subslotBytes == 2 && event.channels == 2
                    gaps.reset(); chunker.reset()
                }
                is CompanionEvent.Stream -> if (!event.active) { gaps.reset(); chunker.reset(); jitter.clear() }
                is CompanionEvent.Audio -> if (formatOk) {
                    val silence = gaps.silenceBefore(event.sofFrame, event.pcm.size)
                    if (silence > 0) {
                        val zeros = ByteArray(silence)
                        val frames = ArrayList<ByteArray>()
                        chunker.push(zeros) { frames.add(it) }
                        for (f in frames) onFrame(f)
                    }
                    val frames = ArrayList<ByteArray>(2)
                    chunker.push(event.pcm) { frames.add(it) }
                    for (f in frames) onFrame(f)
                }
                is CompanionEvent.SbcConfig -> { chunker.reset(); resampler = null; resamplerRate = 0 }
                is CompanionEvent.SbcAudio -> {
                    var pcm = sbc.decode(event.data, event.frames)
                    if (pcm.isEmpty()) continue
                    if (sbc.channels == 1) pcm = monoToStereo(pcm)
                    val rate = sbc.sampleRate
                    if (rate != PcmFormat.SAMPLE_RATE) {
                        if (resamplerRate != rate) {
                            resampler = if (rate == 44_100) AudioResampler(l = 160, m = 147, channels = 2) else null
                            resamplerRate = rate
                        }
                        pcm = resampler?.resample(pcm) ?: continue // only 44.1 and 48 kHz are negotiated
                    }
                    val now = System.currentTimeMillis()
                    if (lastArrival != 0L) maxGapMs = maxOf(maxGapMs, now - lastArrival)
                    lastArrival = now
                    chunker.push(pcm) { jitter.push(it) }
                    startPacer()
                }
                else -> {}
            }
        } } finally {
            pacer?.cancel()
            sbc.close()
        }
        }
    }

    /** Duplicates each 16-bit sample into left and right. */
    private fun monoToStereo(mono: ByteArray): ByteArray {
        val out = ByteArray(mono.size * 2)
        var o = 0
        var i = 0
        while (i + 1 < mono.size) {
            out[o] = mono[i]; out[o + 1] = mono[i + 1]; out[o + 2] = mono[i]; out[o + 3] = mono[i + 1]
            o += 4; i += 2
        }
        return out
    }

    override fun release() {
        if (hub.events === channel) hub.events = null
        channel.close()
        hub.setCasting(false)
    }

    private companion object {
        const val TAG = "CompanionSource"
        /** 5 × 20 ms = the gate's 100 ms look-back. */
        const val DELAY_FRAMES = 5
    }
}
