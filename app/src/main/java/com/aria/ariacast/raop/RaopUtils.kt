package com.aria.ariacast.raop

import java.net.Inet4Address
import java.net.NetworkInterface

object RaopConstants {
    const val AUDIO_FRAME_SIZE = 1408 // L16/44100/2 chunk size (approx 16ms)
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val RTP_PAYLOAD_TYPE = 96
}

object RaopUtils {
    fun getLocalIpAddress(): java.net.InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val name = iface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("tap")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    fun generateDeviceId(): String {
        val random = java.util.Random()
        val mac = ByteArray(6)
        random.nextBytes(mac)
        // Ensure unicast and locally administered
        mac[0] = (mac[0].toInt() and 0xFE or 0x02).toByte()
        return mac.joinToString(":") { "%02X".format(it) }
    }

    fun encodeDmapMetadata(title: String?, artist: String?, album: String?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        
        fun writeTag(tag: String, value: String?) {
            if (value == null) return
            val valBytes = value.toByteArray(Charsets.UTF_8)
            out.write(tag.toByteArray())
            val len = java.nio.ByteBuffer.allocate(4).putInt(valBytes.size).array()
            out.write(len)
            out.write(valBytes)
        }

        writeTag("minm", title)
        writeTag("asar", artist)
        writeTag("asal", album)

        return out.toByteArray()
    }
}
