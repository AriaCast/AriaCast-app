package com.aria.ariacast.companion

import com.aria.ariacast.pipeline.AudioSource
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
        // The board re-enumerates as a speaker; give it a few seconds.
        val ok = withTimeoutOrNull(8000) { hub.speakerActive.first { it } }
        return if (ok == null) "AriaCompanion did not switch to speaker mode" else null
    }

    override suspend fun run(emit: suspend (ByteArray) -> Unit) {
        val chunker = FrameChunker()
        val gaps = SofGapFiller(PcmFormat.SAMPLE_RATE, PcmFormat.BYTES_PER_FRAME)
        val envelope = GateEnvelope(PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS, delayMs = DELAY_FRAMES * 20L)
        val delayLine = ArrayDeque<ByteArray>(DELAY_FRAMES + 1)
        var formatOk = true

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

        for (event in channel) {
            when (event) {
                is CompanionEvent.Format -> {
                    formatOk = event.sampleRate == PcmFormat.SAMPLE_RATE && event.bits == 16 &&
                        event.subslotBytes == 2 && event.channels == 2
                    gaps.reset(); chunker.reset()
                }
                is CompanionEvent.Stream -> if (!event.active) { gaps.reset(); chunker.reset() }
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
                else -> {}
            }
        }
    }

    override fun release() {
        if (hub.events === channel) hub.events = null
        channel.close()
        hub.setCasting(false)
    }

    private companion object {
        /** 5 × 20 ms = the gate's 100 ms look-back. */
        const val DELAY_FRAMES = 5
    }
}
