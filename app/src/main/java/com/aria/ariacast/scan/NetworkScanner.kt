package com.aria.ariacast.scan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.aria.ariacast.quirks.SpeakerListSync
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One look at the local network for devices AriaCast cannot play to, when the user asks. It
 * sends a UPnP search and listens for the mDNS service types in [ScanParsing.MDNS_TYPES]; it reads
 * a device's description only from the address that answered, and keeps nothing afterwards.
 */
class NetworkScanner(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun scan(durationMs: Long = SCAN_MS, usableNames: Collection<String> = emptyList()): List<OtherDevice> = coroutineScope {
        val mdns = CopyOnWriteArrayList<MdnsFound>()
        val listeners = startMdns(mdns)
        try {
            val ssdp = async(Dispatchers.IO) { searchSsdp(durationMs) }
            delay(durationMs)
            ScanParsing.otherDevices(ssdp.await(), mdns.toList(), usableNames)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    private fun startMdns(found: MutableList<MdnsFound>): List<NsdManager.DiscoveryListener> =
        ScanParsing.MDNS_TYPES.keys.mapNotNull { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { found += MdnsFound(info.serviceName.orEmpty(), type) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.d(TAG, "mDNS $serviceType: error $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listener
            } catch (e: Exception) {
                Log.d(TAG, "mDNS $type: ${e.message}")
                null
            }
        }

    private suspend fun searchSsdp(durationMs: Long): List<SsdpDevice> = withContext(Dispatchers.IO) {
        val replies = linkedMapOf<String, Pair<String, SsdpReply>>() // location -> (answering address, reply)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900))
                val end = System.currentTimeMillis() + durationMs - 1500
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < end && replies.size < MAX_DEVICES) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                    val host = packet.address.hostAddress ?: continue
                    ScanParsing.ssdpReply(String(packet.data, 0, packet.length))?.let { replies.putIfAbsent(it.location, host to it) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSDP: ${e.message}")
        }
        replies.values.map { (host, reply) -> async { describe(host, reply) } }.awaitAll().filterNotNull()
    }

    /** The description a device points to, but only if it is on the address that answered. */
    private fun describe(host: String, reply: SsdpReply): SsdpDevice? = try {
        val uri = URI(reply.location)
        if ((uri.scheme != "http") || uri.host != host) {
            null
        } else {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > ScanParsing.MAX_DESCRIPTION_BYTES) {
                    null
                } else {
                    // Throws if the description is longer than allowed, which ends up as "not a device".
                    val xml = connection.inputStream.use { SpeakerListSync.readBounded(it, ScanParsing.MAX_DESCRIPTION_BYTES) }
                    ScanParsing.description(xml, host, reply.server)
                }
            } finally {
                connection.disconnect()
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "NetworkScanner"
        const val SCAN_MS = 8_000L
        private const val MAX_DEVICES = 60
    }
}
