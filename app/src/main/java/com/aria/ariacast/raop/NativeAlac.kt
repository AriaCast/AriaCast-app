package com.aria.ariacast.raop

class NativeAlac {
    external fun createEncoder(sampleRate: Int, channels: Int, frameSize: Int): Long
    external fun encode(handle: Long, pcmBytes: ByteArray, pcmLen: Int, outBuffer: ByteArray): Int
    external fun destroyEncoder(handle: Long)

    companion object {
        init {
            System.loadLibrary("ariacast_raop")
        }
    }
}
