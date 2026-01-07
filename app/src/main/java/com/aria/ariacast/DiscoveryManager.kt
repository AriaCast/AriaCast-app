package com.aria.ariacast

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.math.pow

class DiscoveryManager(private val context: Context) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.IDLE)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val discoveredServers = mutableMapOf<String, Server>()

    private val nsdListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            _state.value = DiscoveryState.SCANNING
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsdManager.resolveService(serviceInfo, createResolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            discoveredServers.remove(serviceInfo.serviceName)
            _servers.value = discoveredServers.values.toList()
            if (discoveredServers.isEmpty()) {
                _state.value = DiscoveryState.NONE
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            _state.value = DiscoveryState.IDLE
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery start failed: $errorCode")
            _state.value = DiscoveryState.ERROR
            startUdpDiscoveryWithRetries()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery stop failed: $errorCode")
        }
    }

    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolve failed: $errorCode")
                _state.value = DiscoveryState.ERROR
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port

                val server = Server(
                    name = name,
                    host = host,
                    port = port,
                    version = serviceInfo.attributes["version"]?.toString(Charsets.UTF_8) ?: "",
                    codecs = serviceInfo.attributes["codecs"]?.toString(Charsets.UTF_8)?.split(",") ?: emptyList(),
                    sampleRate = serviceInfo.attributes["samplerate"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0,
                    channels = serviceInfo.attributes["channels"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0,
                    platform = serviceInfo.attributes["platform"]?.toString(Charsets.UTF_8)
                )
                synchronized(this) {
                    if (!discoveredServers.containsKey(name)) {
                        discoveredServers[name] = server
                        _servers.value = discoveredServers.values.toList()
                    }
                }
                _state.value = DiscoveryState.FOUND
            }
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        _servers.value = emptyList()
        discoveredServers.clear()
        _state.value = DiscoveryState.SCANNING
        try {
            nsdManager.discoverServices("_audiocast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD discovery could not be started", e)
            startUdpDiscoveryWithRetries()
        }
    }

    fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(nsdListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD discovery", e)
        }
    }

    private fun startUdpDiscoveryWithRetries() {
        scope.launch {
            for (attempt in 0 until UDP_MAX_RETRIES) {
                if (performUdpDiscovery()) {
                    return@launch
                } else {
                    val delayTime = (UDP_INITIAL_BACKOFF * 2.0.pow(attempt.toDouble())).toLong()
                    Log.d(TAG, "UDP discovery failed, retrying in ${delayTime}ms...")
                    delay(delayTime)
                }
            }
            Log.e(TAG, "UDP discovery failed after all retries.")
            _state.value = DiscoveryState.ERROR
        }
    }

    private suspend fun performUdpDiscovery(): Boolean = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = UDP_TIMEOUT
                val message = "DISCOVER_AUDIOCAST".toByteArray()
                val packet = DatagramPacket(message, message.size, getBroadcastAddress(), UDP_PORT)
                socket.send(packet)

                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                val jsonResponse = String(responsePacket.data, 0, responsePacket.length)
                val json = JSONObject(jsonResponse)

                val server = Server(
                    name = json.getString("server_name"),
                    host = json.getString("ip"),
                    port = json.getInt("port"),
                    version = json.getString("version"),
                    codecs = json.getJSONArray("codecs").let { 0.until(it.length()).map { i -> it.getString(i) } },
                    sampleRate = json.getInt("samplerate"),
                    channels = json.getInt("channels")
                )

                synchronized(this) {
                    if (!discoveredServers.containsKey(server.name)) {
                        discoveredServers[server.name] = server
                        _servers.value = discoveredServers.values.toList()
                    }
                }
                _state.value = DiscoveryState.FOUND
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP discovery attempt failed", e)
            return@withContext false
        }
    }

    private fun getBroadcastAddress(): InetAddress {
        val activeNetwork = connectivityManager.activeNetwork ?: return InetAddress.getByName("255.255.255.255")
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return InetAddress.getByName("255.255.255.255")

        for (linkAddress in linkProperties.linkAddresses) {
            if (linkAddress.address is Inet4Address) {
                val ipAddress = linkAddress.address as Inet4Address
                val prefixLength = linkAddress.prefixLength

                val ipBytes = ipAddress.address
                val ipInt = (ipBytes[0].toInt() and 0xFF shl 24) or
                            (ipBytes[1].toInt() and 0xFF shl 16) or
                            (ipBytes[2].toInt() and 0xFF shl 8) or
                            (ipBytes[3].toInt() and 0xFF)

                val netmaskInt = -1 shl (32 - prefixLength)
                val broadcastInt = ipInt or netmaskInt.inv()

                val broadcastBytes = byteArrayOf(
                    (broadcastInt shr 24).toByte(),
                    (broadcastInt shr 16).toByte(),
                    (broadcastInt shr 8).toByte(),
                    broadcastInt.toByte()
                )
                try {
                    return InetAddress.getByAddress(broadcastBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not create broadcast address", e)
                }
            }
        }
        return InetAddress.getByName("255.255.255.255")
    }

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val UDP_PORT = 12888
        private const val UDP_TIMEOUT = 3000
        private const val UDP_MAX_RETRIES = 3
        private const val UDP_INITIAL_BACKOFF = 1000L
    }
}

enum class DiscoveryState {
    IDLE,
    SCANNING,
    FOUND,
    NONE,
    ERROR
}
