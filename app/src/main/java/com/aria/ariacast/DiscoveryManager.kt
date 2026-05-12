package com.aria.ariacast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Patterns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

class DiscoveryManager(private val context: Context) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.IDLE)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    
    private val discoveredServers = mutableMapOf<String, Server>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    private val nsdListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (_state.value != DiscoveryState.FOUND) {
                _state.value = DiscoveryState.SCANNING
            }
            Log.d(TAG, "Discovery started: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            resolveQueue.add(serviceInfo)
            processResolveQueue()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            val nameToRemove = if (serviceInfo.serviceType.contains("_raop") && serviceInfo.serviceName.contains("@")) {
                serviceInfo.serviceName.substringAfter("@")
            } else {
                serviceInfo.serviceName
            }
            synchronized(discoveredServers) {
                discoveredServers.remove(nameToRemove)
                _servers.value = discoveredServers.values.toList()
                if (discoveredServers.isEmpty()) {
                    _state.value = DiscoveryState.NONE
                }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery stop failed: $errorCode")
        }
    }

    private fun processResolveQueue() {
        if (isResolving || resolveQueue.isEmpty()) return
        
        isResolving = true
        val serviceInfo = resolveQueue.poll() ?: return
        
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode for ${si.serviceName}")
                    isResolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        scope.launch {
                            delay(500)
                            resolveQueue.add(si)
                            processResolveQueue()
                        }
                    } else {
                        processResolveQueue()
                    }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    isResolving = false
                    handleResolvedService(si)
                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            isResolving = false
            processResolveQueue()
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        var name = serviceInfo.serviceName ?: return
        val hostAddress = serviceInfo.host?.hostAddress ?: return
        
        if (hostAddress == "127.0.0.1" || hostAddress == "::1" || hostAddress.contains("localhost")) return

        val port = serviceInfo.port
        val attr = serviceInfo.attributes
        
        var platform = attr["platform"]?.toString(Charsets.UTF_8)
        var model = attr["model"]?.toString(Charsets.UTF_8) ?: attr["am"]?.toString(Charsets.UTF_8)
        var deviceId = attr["deviceid"]?.toString(Charsets.UTF_8)
        val features = attr["features"]?.toString(Charsets.UTF_8)
        val pk = attr["pk"]?.toString(Charsets.UTF_8)
        
        val sampleRate = attr["sr"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 
                         attr["samplerate"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 48000
        val channels = attr["ch"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?:
                       attr["channels"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 2
        
        if (serviceInfo.serviceType.contains("_raop")) {
            platform = "AirPlay"
            if (name.contains("@")) {
                if (deviceId == null) deviceId = name.substringBefore("@")
                name = name.substringAfter("@")
            }
        } else if (serviceInfo.serviceType.contains("_airplay")) {
            platform = "AirPlay"
        } else if (serviceInfo.serviceType.contains("_googlecast")) {
            platform = "Google Cast"
            name = attr["fn"]?.toString(Charsets.UTF_8) ?: name
            model = attr["md"]?.toString(Charsets.UTF_8)
        } else if (serviceInfo.serviceType.contains("_audiocast")) {
            platform = "AriaCast"
        }

        val extraParts = mutableListOf<String>()
        if (model != null) extraParts.add("model=$model")
        if (deviceId != null) extraParts.add("id=$deviceId")
        if (features != null) extraParts.add("features=$features")
        if (pk != null) extraParts.add("pk=$pk")

        val server = Server(
            name = name,
            host = hostAddress,
            port = port,
            version = attr["version"]?.toString(Charsets.UTF_8) ?: attr["srcvers"]?.toString(Charsets.UTF_8) ?: "1.0",
            codecs = attr["codecs"]?.toString(Charsets.UTF_8)?.split(",") ?: listOf("pcm"),
            sampleRate = sampleRate,
            channels = channels,
            platform = platform,
            extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
        )
        
        synchronized(discoveredServers) {
            val existing = discoveredServers[name]
            if (existing != null && existing.platform == platform) {
                if (platform == "AirPlay") {
                    val isRaop = serviceInfo.serviceType.contains("_raop")
                    discoveredServers[name] = if (isRaop) {
                        server.copy(extra = mergeExtras(existing.extra, server.extra))
                    } else {
                        existing.copy(
                            extra = mergeExtras(existing.extra, server.extra),
                            version = if (server.version != "1.0") server.version else existing.version
                        )
                    }
                } else {
                    discoveredServers[name] = server
                }
            } else {
                discoveredServers[name] = server
            }
            _servers.value = discoveredServers.values.toList()
            _state.value = DiscoveryState.FOUND
        }
    }

    private fun mergeExtras(old: String?, new: String?): String? {
        if (old == null) return new
        if (new == null) return old
        val oldMap = old.split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val newMap = new.split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }
        return (oldMap + newMap).map { "${it.key}=${it.value}" }.joinToString(";")
    }

    fun startDiscovery() {
        stopDiscovery()
        
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("AriaCastDiscoveryLock")
            }
            multicastLock?.acquire()
        } catch (e: Exception) {}

        _state.value = DiscoveryState.SCANNING
        
        val prefs = context.getSharedPreferences("AriaCastPrefs", Context.MODE_PRIVATE)

        val services = mutableListOf("_audiocast._tcp")
        if (prefs.getBoolean("airplay_enabled", false)) {
            services.add("_raop._tcp")
            services.add("_airplay._tcp")
        }
        if (prefs.getBoolean("google_cast_enabled", false)) {
            services.add("_googlecast._tcp")
        }

        services.forEach { type ->
            try {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, nsdListener)
            } catch (e: Exception) {
                Log.e(TAG, "Discovery failed for $type: ${e.message}")
            }
        }
        
        startUdpDiscoveryWithRetries()
        if (prefs.getBoolean("dlna_enabled", false)) startSsdpDiscovery()
    }

    fun stopDiscovery() {
        try { nsdManager.stopServiceDiscovery(nsdListener) } catch (e: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (e: Exception) {}
        resolveQueue.clear()
        isResolving = false
    }

    fun removeServer(name: String) {
        synchronized(discoveredServers) {
            discoveredServers.remove(name)
            _servers.value = discoveredServers.values.toList()
        }
    }

    private fun startSsdpDiscovery() {
        scope.launch {
            try {
                val ssdpAddress = InetAddress.getByName("239.255.255.250")
                val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                DatagramSocket().use { socket ->
                    socket.soTimeout = 3000
                    val packet = DatagramPacket(query.toByteArray(), query.length, ssdpAddress, 1900)
                    socket.send(packet)

                    val buffer = ByteArray(2048)
                    while (isActive) {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(responsePacket)
                            val response = String(responsePacket.data, 0, responsePacket.length)
                            val host = responsePacket.address.hostAddress ?: continue
                            val location = response.split("\r\n").find { it.startsWith("LOCATION:", true) }?.substring(9)?.trim() ?: continue
                            launch { resolveDlnaDevice(location, host) }
                        } catch (e: Exception) { break }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun resolveDlnaDevice(location: String, hostAddress: String) = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(location).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>")
            
            if (friendlyName.isNotEmpty()) {
                var controlUrl = ""
                if (xml.contains("urn:schemas-upnp-org:service:AVTransport:1")) {
                    controlUrl = xml.substringAfter("urn:schemas-upnp-org:service:AVTransport:1").substringAfter("<controlURL>", "").substringBefore("</controlURL>")
                    if (controlUrl.isNotEmpty() && !controlUrl.startsWith("http")) {
                        val uri = java.net.URI(location)
                        controlUrl = "${uri.scheme}://${uri.host}:${uri.port}${if(controlUrl.startsWith("/")) "" else "/"}$controlUrl"
                    }
                }
                
                val server = Server(name = friendlyName, host = hostAddress, port = 0, version = "1.0", codecs = listOf("pcm"), sampleRate = 48000, channels = 2, platform = "DLNA", extra = controlUrl)
                synchronized(discoveredServers) {
                    discoveredServers[friendlyName] = server
                    _servers.value = discoveredServers.values.toList()
                    _state.value = DiscoveryState.FOUND
                }
            }
        } catch (e: Exception) {}
    }

    private fun startUdpDiscoveryWithRetries() {
        scope.launch {
            repeat(3) {
                performUdpDiscovery()
                delay(3000)
            }
        }
    }

    private suspend fun performUdpDiscovery() = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 2000
                val packet = DatagramPacket("DISCOVER_AUDIOCAST".toByteArray(), 18, InetAddress.getByName("255.255.255.255"), 12888)
                socket.send(packet)
                val buffer = ByteArray(1024)
                while (isActive) {
                    val resp = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(resp)
                        val json = JSONObject(String(resp.data, 0, resp.length))
                        val server = Server(name = json.optString("server_name"), host = resp.address.hostAddress ?: "", port = json.optInt("port"), version = "1.0", codecs = listOf("pcm"), sampleRate = 48000, channels = 2, platform = "AriaCast")
                        synchronized(discoveredServers) {
                            discoveredServers[server.name] = server
                            _servers.value = discoveredServers.values.toList()
                            _state.value = DiscoveryState.FOUND
                        }
                    } catch (e: Exception) { break }
                }
            }
        } catch (e: Exception) {}
    }

    companion object { private const val TAG = "DiscoveryManager" }
}

enum class DiscoveryState { IDLE, SCANNING, FOUND, NONE }
