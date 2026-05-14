package com.aria.ariacast

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class VideoReceiver(private val surface: Surface) {

    private var socket: DatagramSocket? = null
    private var codec: MediaCodec? = null
    private var isRunning = false

    suspend fun start(port: Int) = withContext(Dispatchers.IO) {
        isRunning = true
        socket = DatagramSocket(port)
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(format, surface, null, 0)
        codec?.start()

        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)

        try {
            while (isRunning) {
                socket?.receive(packet)
                decode(packet.data, packet.length)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stop()
        }
    }

    private fun decode(data: ByteArray, length: Int) {
        val codec = codec ?: return
        val inputBufferIndex = codec.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data, 0, length)
            codec.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime() / 1000, 0)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            codec.releaseOutputBuffer(outputBufferIndex, true)
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        codec?.stop()
        codec?.release()
        codec = null
    }
}
