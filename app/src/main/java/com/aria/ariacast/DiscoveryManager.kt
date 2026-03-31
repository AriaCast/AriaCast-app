package com.aria.ariacast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.util.Patterns
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
import java.net.InetAddress

class DiscoveryManager(private val context: Context) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.IDLE)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredServers = mutableMapOf<String, Server>()

    private val nsdListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (_state.value != DiscoveryState.FOUND) {
                _state.value = DiscoveryState.SCANNING
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsdManager.resolveService(serviceInfo, createResolveListener())
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(discoveredServers) {
                discoveredServers.remove(serviceInfo.serviceName)
                _servers.value = discoveredServers.values.toList()
                if (discoveredServers.isEmpty()) {
                    _state.value = DiscoveryState.NONE
                }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            if (discoveredServers.isEmpty()) {
                _state.value = DiscoveryState.IDLE
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery stop failed: $errorCode")
        }
    }

    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                val hostAddress = serviceInfo.host?.hostAddress ?: return
                
                if (hostAddress == "127.0.0.1" || hostAddress == "::1" || hostAddress.contains("localhost")) {
                    Log.w(TAG, "NSD resolved to loopback address ($hostAddress) for $name, ignoring.")
                    return
                }

                val port = serviceInfo.port

                val server = Server(
                    name = name,
                    host = hostAddress,
                    port = port,
                    version = serviceInfo.attributes["version"]?.toString(Charsets.UTF_8) ?: "",
                    codecs = serviceInfo.attributes["codecs"]?.toString(Charsets.UTF_8)?.split(",") ?: emptyList(),
                    sampleRate = serviceInfo.attributes["samplerate"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0,
                    channels = serviceInfo.attributes["channels"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0,
                    platform = serviceInfo.attributes["platform"]?.toString(Charsets.UTF_8)
                )
                synchronized(discoveredServers) {
                    if (!discoveredServers.containsKey(name)) {
                        discoveredServers[name] = server
                        _servers.value = discoveredServers.values.toList()
                        _state.value = DiscoveryState.FOUND
                    }
                }
            }
        }
    }

    fun addManualServer(host: String, port: Int, name: String = "Manual Server"): Boolean {
        if (!Patterns.IP_ADDRESS.matcher(host).matches()) {
            Log.e(TAG, "Invalid IP address provided: $host")
            return false
        }

        val server = Server(
            name = name,
            host = host,
            port = port,
            version = "1.0",
            codecs = listOf("pcm"),
            sampleRate = 48000,
            channels = 2,
            platform = "Manual"
        )
        synchronized(discoveredServers) {
            discoveredServers[name] = server
            _servers.value = discoveredServers.values.toList()
            _state.value = DiscoveryState.FOUND
        }
        return true
    }

    fun removeServer(name: String) {
        synchronized(discoveredServers) {
            discoveredServers.remove(name)
            _servers.value = discoveredServers.values.toList()
            if (discoveredServers.isEmpty()) {
                _state.value = DiscoveryState.IDLE
            }
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        synchronized(discoveredServers) {
            _servers.value = discoveredServers.values.toList() // Keep manual servers
        }
        _state.value = DiscoveryState.SCANNING
        
        try {
            nsdManager.discoverServices("_audiocast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD discovery could not be started", e)
        }
        
        startUdpDiscoveryWithRetries()
    }

    fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(nsdListener)
        } catch (e: Exception) {
        }
    }

    private fun startUdpDiscoveryWithRetries() {
        scope.launch {
            for (attempt in 0 until UDP_MAX_RETRIES) {
                performUdpDiscovery()
                delay(UDP_RETRY_DELAY)
            }
        }
    }

    private suspend fun performUdpDiscovery() = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = UDP_TIMEOUT
                val message = "DISCOVER_AUDIOCAST"
                val packet = DatagramPacket(
                    message.toByteArray(), 
                    message.length, 
                    InetAddress.getByName("255.255.255.255"), 
                    UDP_PORT
                )
                socket.send(packet)

                val buffer = ByteArray(2048)
                while (true) {
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(responsePacket)
                        val jsonResponse = String(responsePacket.data, 0, responsePacket.length)
                        val json = JSONObject(jsonResponse)
                        
                        val responseIp = responsePacket.address.hostAddress ?: "127.0.0.1"
                        
                        if (responseIp == "127.0.0.1" || responseIp == "::1") return@withContext

                        val server = Server(
                            name = json.optString("server_name", "Unknown AriaCast"),
                            host = responseIp,
                            port = json.optInt("port", 12889),
                            version = json.optString("version", "1.0"),
                            codecs = listOf("pcm"),
                            sampleRate = json.optInt("samplerate", 48000),
                            channels = json.optInt("channels", 2)
                        )

                        synchronized(discoveredServers) {
                            if (!discoveredServers.containsKey(server.name)) {
                                discoveredServers[server.name] = server
                                _servers.value = discoveredServers.values.toList()
                                _state.value = DiscoveryState.FOUND
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP discovery error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val UDP_PORT = 12888
        private const val UDP_TIMEOUT = 2000
        private const val UDP_MAX_RETRIES = 5
        private const val UDP_RETRY_DELAY = 3000L
    }
}

enum class DiscoveryState {
    IDLE,
    SCANNING,
    FOUND,
    NONE
}
