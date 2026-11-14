package com.aria.ariacast.companion

/**
 * SBC → 16-bit PCM, through BlueZ libsbc (app/src/main/cpp/sbc). Decodes the frames the
 * AriaCompanion BT board forwards; each frame describes itself, so no setup is needed.
 * Not thread-safe: one decoder per stream.
 */
class SbcDecoder : AutoCloseable {
    private var handle = nativeCreate()
    private var out = ByteArray(16 * 1024)

    /** Interleaved little-endian PCM for every frame in [data]; [channels]/[sampleRate] describe it. */
    fun decode(data: ByteArray, frames: Int): ByteArray {
        if (handle == 0L || data.isEmpty()) return ByteArray(0)
        // One SBC frame decodes to at most 16 blocks × 8 subbands × 2 channels × 2 bytes.
        val need = (maxOf(frames, 1) + 4) * MAX_FRAME_PCM
        if (out.size < need) out = ByteArray(need)
        val n = nativeDecode(handle, data, 0, data.size, out)
        return out.copyOf(n)
    }

    val channels: Int get() = if (handle == 0L) 2 else nativeChannels(handle)
    val sampleRate: Int get() = if (handle == 0L) 44_100 else nativeSampleRate(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private companion object {
        const val MAX_FRAME_PCM = 16 * 8 * 2 * 2

        init { System.loadLibrary("ariacast_sbc") }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDecode(handle: Long, input: ByteArray, offset: Int, length: Int, output: ByteArray): Int
        @JvmStatic external fun nativeChannels(handle: Long): Int
        @JvmStatic external fun nativeSampleRate(handle: Long): Int
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
