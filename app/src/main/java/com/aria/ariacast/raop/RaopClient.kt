package com.aria.ariacast.raop

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow

class RaopClient(
    private val device: RaopDevice,
    private val localIp: String,
    private val deviceId: String
) {
    private val TAG = "RaopClient"
    private var rtspSocket: Socket? = null
    private var rtspWriter: BufferedWriter? = null
    private var rtspReader: BufferedReader? = null
    private var cseq = 1
    private var sessionId: String? = null
    
    private val aesKey = ByteArray(16).apply { Random().nextBytes(this) }
    private val aesIv = ByteArray(16).apply { Random().nextBytes(this) }
    private val crypto = RaopCrypto().apply { initAes(aesKey, aesIv) }
    
    private var serverDataPort = 0
    private var serverControlPort = 0
    private var serverTimingPort = 0
    
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    
    private var rtpSequence = Random().nextInt(0xFFFF)
    private var rtpTimestamp = Random().nextInt().toLong() and 0xFFFFFFFFL
    private var syncSequence = 0
    private var firstSyncSent = false
    
    private var isStreaming = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            rtspSocket = Socket(device.host, device.port).apply {
                tcpNoDelay = true
                soTimeout = 5000
            }
            rtspWriter = rtspSocket!!.getOutputStream().bufferedWriter()
            rtspReader = rtspSocket!!.getInputStream().bufferedReader()
            
            if (!options()) return@withContext false
            if (!announce()) return@withContext false
            if (!setup()) return@withContext false
            if (!record()) return@withContext false
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "RAOP connection failed for ${device.name}: ${e.message}")
            false
        }
    }

    private fun sendRequest(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Map<String, String>? {
        val writer = rtspWriter ?: return null
        val reader = rtspReader ?: return null
        
        val request = StringBuilder().apply {
            append("$method $url RTSP/1.0\r\n")
            append("CSeq: ${cseq++}\r\n")
            append("User-Agent: AirPlay/353.5\r\n")
            append("Client-Instance: ${deviceId.replace(":", "")}\r\n")
            append("DACP-ID: ${deviceId.replace(":", "")}\r\n")
            append("Active-Remote: 1\r\n")
            sessionId?.let { append("Session: $it\r\n") }
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
            body?.let { append(it) }
        }.toString()
        
        try {
            writer.write(request)
            writer.flush()
            
            val statusLine = reader.readLine() ?: return null
            if (!statusLine.contains("200 OK")) {
                Log.w(TAG, "RTSP Request failed: $statusLine")
            }
            
            val responseHeaders = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && !line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) {
                    responseHeaders[parts[0]] = parts[1]
                }
            }
            
            val contentLength = responseHeaders["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf)
            }
            
            if (responseHeaders.containsKey("Session")) {
                sessionId = responseHeaders["Session"]!!.substringBefore(";")
            }
            
            return responseHeaders
        } catch (e: Exception) {
            Log.e(TAG, "RTSP Send failed: ${e.message}")
            return null
        }
    }

    private fun options(): Boolean = sendRequest("OPTIONS", "*") != null

    private fun announce(): Boolean {
        val rsaAesKey = RaopCrypto.encryptAesKey(aesKey)
        val encryptedKeyB64 = Base64.encodeToString(rsaAesKey, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(aesIv, Base64.NO_WRAP)

        val sdp = StringBuilder().apply {
            append("v=0\r\n")
            append("o=iTunes 0 0 IN IP4 $localIp\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 ${device.host}\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 L16/44100/2\r\n")
            append("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n")
            append("a=rsaaeskey:$encryptedKeyB64\r\n")
            append("a=aesiv:$ivB64\r\n")
            append("a=control:rtp\r\n")
        }.toString()

        return sendRequest("ANNOUNCE", "rtsp://${device.host}/iTunes", mapOf(
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.length.toString()
        ), sdp) != null
    }

    private fun setup(): Boolean {
        dataSocket = DatagramSocket()
        controlSocket = DatagramSocket()
        timingSocket = DatagramSocket()
        
        val transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlSocket!!.localPort};timing_port=${timingSocket!!.localPort}"
        val resp = sendRequest("SETUP", "rtsp://${device.host}/iTunes", mapOf(
            "Transport" to transport
        )) ?: return false
        
        val transportResp = resp["Transport"] ?: return false
        serverDataPort = transportResp.substringAfter("server_port=").substringBefore(";").toIntOrNull() ?: 0
        serverControlPort = transportResp.substringAfter("control_port=").substringBefore(";").toIntOrNull() ?: 0
        serverTimingPort = transportResp.substringAfter("timing_port=").substringBefore(";").toIntOrNull() ?: 0
        
        return serverDataPort != 0
    }

    private fun record(): Boolean = sendRequest("RECORD", "rtsp://${device.host}/iTunes", mapOf(
        "Range" to "npt=0-",
        "RTP-Info" to "seq=$rtpSequence;rtptime=$rtpTimestamp"
    )) != null

    fun startStreaming(audioFlow: SharedFlow<ByteArray>) {
        isStreaming = true
        scope.launch { timingLoop() }
        scope.launch { syncLoop() }
        scope.launch { audioLoop(audioFlow) }
    }

    fun stop() {
        isStreaming = false
        scope.cancel()
        sendRequest("TEARDOWN", "rtsp://${device.host}/iTunes")
        try { rtspSocket?.close() } catch (e: Exception) {}
        try { dataSocket?.close() } catch (e: Exception) {}
        try { controlSocket?.close() } catch (e: Exception) {}
        try { timingSocket?.close() } catch (e: Exception) {}
    }

    private suspend fun audioLoop(audioFlow: SharedFlow<ByteArray>) {
        withContext(Dispatchers.IO) {
            val dest = InetAddress.getByName(device.host)
            audioFlow.collect { rawData ->
                if (!isStreaming) return@collect
                
                for (offset in 0 until rawData.size step RaopConstants.AUDIO_FRAME_SIZE) {
                    val size = minOf(RaopConstants.AUDIO_FRAME_SIZE, rawData.size - offset)
                    val chunk = rawData.copyOfRange(offset, offset + size)
                    
                    val beChunk = ByteArray(chunk.size)
                    for (i in 0 until chunk.size step 2) {
                        if (i + 1 < chunk.size) {
                            beChunk[i] = chunk[i + 1]
                            beChunk[i + 1] = chunk[i]
                        }
                    }
                    
                    val encrypted = crypto.encryptAudio(beChunk)
                    
                    val packet = ByteBuffer.allocate(12 + encrypted.size).apply {
                        put(0x80.toByte())
                        put(0x60.toByte()) // PT 96
                        putShort((rtpSequence++ and 0xFFFF).toShort())
                        putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                        putInt(0x11223344) // SSRC
                        put(encrypted)
                    }.array()
                    
                    val datagram = DatagramPacket(packet, packet.size, dest, serverDataPort)
                    dataSocket?.send(datagram)
                    
                    rtpTimestamp += size / 4
                }
            }
        }
    }

    private suspend fun syncLoop() {
        withContext(Dispatchers.IO) {
            val dest = InetAddress.getByName(device.host)
            while (isStreaming) {
                val now = System.currentTimeMillis()
                val ntpSec = (now / 1000) + 0x83AA7E80
                val ntpFrac = ((now % 1000) * 0x100000000L / 1000)
                
                val packet = ByteBuffer.allocate(20).apply {
                    put((if (!firstSyncSent) 0x90 else 0x80).toByte())
                    put(0xD4.toByte()) // PT 84 with Marker
                    putShort((syncSequence++ and 0xFFFF).toShort())
                    putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                    putInt(ntpSec.toInt())
                    putInt(ntpFrac.toInt())
                    putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                }.array()
                
                firstSyncSent = true
                val datagram = DatagramPacket(packet, packet.size, dest, serverControlPort)
                controlSocket?.send(datagram)
                
                delay(2000)
            }
        }
    }

    private suspend fun timingLoop() {
        withContext(Dispatchers.IO) {
            val dest = InetAddress.getByName(device.host)
            while (isStreaming) {
                val t1 = System.currentTimeMillis()
                val ntpSec = (t1 / 1000) + 0x83AA7E80
                val ntpFrac = ((t1 % 1000) * 0x100000000L / 1000)
                
                val request = ByteBuffer.allocate(32).apply {
                    put(0x80.toByte())
                    put(0xD2.toByte()) // PT 82 with Marker
                    putShort(0) // Seq
                    putInt(0) // Unused
                    putLong(0) // Origin
                    putLong(0) // Receive
                    putInt(ntpSec.toInt())
                    putInt(ntpFrac.toInt())
                }.array()
                
                val datagram = DatagramPacket(request, request.size, dest, serverTimingPort)
                timingSocket?.send(datagram)
                
                delay(2000)
            }
        }
    }

    fun setVolume(volume: Float) {
        val volumeDb = if (volume <= 0.0f) -144.0f else if (volume >= 1.0f) 0.0f else (20.0 * Math.log10(volume.toDouble())).toFloat()
        val body = "volume: %.2f\r\n".format(volumeDb)
        scope.launch {
            sendRequest("SET_PARAMETER", "rtsp://${device.host}/iTunes", mapOf(
                "Content-Type" to "text/parameters",
                "Content-Length" to body.length.toString()
            ), body)
        }
    }

    fun updateMetadata(title: String?, artist: String?, album: String?) {
        val dmap = RaopUtils.encodeDmapMetadata(title, artist, album)
        if (dmap.isEmpty()) return
        
        scope.launch {
            val writer = rtspWriter ?: return@launch
            val request = StringBuilder().apply {
                append("SET_PARAMETER rtsp://${device.host}/iTunes RTSP/1.0\r\n")
                append("CSeq: ${cseq++}\r\n")
                append("Content-Type: application/x-dmap-tagged\r\n")
                append("Content-Length: ${dmap.size}\r\n")
                sessionId?.let { append("Session: $it\r\n") }
                append("\r\n")
            }.toString()
            
            try {
                writer.write(request)
                writer.flush()
                rtspSocket!!.getOutputStream().write(dmap)
                rtspSocket!!.getOutputStream().flush()
                
                // Read response to clear buffer
                rtspReader?.readLine() 
                var line: String?
                while (rtspReader?.readLine().also { line = it } != null && !line.isNullOrEmpty()) { }
            } catch (e: Exception) {}
        }
    }
}
