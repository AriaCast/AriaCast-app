package com.aria.ariacast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Patterns
import com.aria.ariacast.raop.RaopDiscovery
import com.aria.ariacast.raop.RaopDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var activeResolves = 0
    private val MAX_CONCURRENT_RESOLVES = 3
    private var discoveryJob: Job? = null
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var raopDiscovery: RaopDiscovery? = null

    private fun createNsdListener() = object : NsdManager.DiscoveryListener {
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
        if (activeResolves >= MAX_CONCURRENT_RESOLVES || resolveQueue.isEmpty()) return
        
        val serviceInfo = resolveQueue.poll() ?: return
        activeResolves++
        
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode for ${si.serviceName}")
                    activeResolves--
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        scope.launch {
                            delay(1000)
                            resolveQueue.add(si)
                            processResolveQueue()
                        }
                    } else {
                        processResolveQueue()
                    }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    activeResolves--
                    handleResolvedService(si)
                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            activeResolves--
            processResolveQueue()
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val originalName = serviceInfo.serviceName ?: return
        var name = originalName
        val hostAddress = serviceInfo.host?.hostAddress ?: return
        
        if (hostAddress == "127.0.0.1" || hostAddress == "::1" || hostAddress.contains("localhost")) return

        val port = serviceInfo.port
        val attr = serviceInfo.attributes
        
        fun attrString(key: String): String? {
            val bytes = attr[key] ?: return null
            val s = String(bytes, Charsets.UTF_8).trim()
            return if (s.isEmpty()) null else s
        }

        var platform = attrString("platform")
        var model = attrString("model") ?: attrString("am")
        var deviceId = attrString("deviceid")
        val features = attrString("features")
        val pk = attrString("pk")
        
        val sampleRate = attrString("sr")?.toIntOrNull() ?: 
                         attrString("samplerate")?.toIntOrNull() ?: 48000
        val channels = attrString("ch")?.toIntOrNull() ?:
                       attrString("channels")?.toIntOrNull() ?: 2
        
        val extraParts = mutableListOf<String>()

        if (serviceInfo.serviceType.contains("_raop")) {
            platform = "AirPlay"
            if (name.contains("@")) {
                val cleaned = name.substringAfter("@")
                if (cleaned.isNotEmpty()) {
                    name = cleaned
                }
                if (deviceId == null) deviceId = originalName.substringBefore("@")
            }
        } else if (serviceInfo.serviceType.contains("_airplay")) {
            platform = "AirPlay2"
        } else if (serviceInfo.serviceType.contains("_googlecast")) {
            platform = "Google Cast"
            name = attrString("fn") ?: name
            model = attrString("md")
            attrString("st")?.let { extraParts.add("st=$it") }
            attrString("ca")?.let { extraParts.add("ca=$it") }
            attrString("ve")?.let { extraParts.add("ve=$it") }
        } else if (serviceInfo.serviceType.contains("_audiocast")) {
            platform = "AriaCast"
        }

        // Ensure name is never empty
        if (name.trim().isEmpty()) {
            name = originalName
        }

        if (model != null) extraParts.add("model=$model")
        if (deviceId != null) extraParts.add("id=$deviceId")
        if (features != null) extraParts.add("features=$features")
        if (pk != null) extraParts.add("pk=$pk")

        val server = Server(
            name = name,
            host = hostAddress,
            port = port,
            version = attrString("version") ?: attrString("srcvers") ?: "1.0",
            codecs = attrString("codecs")?.split(",") ?: listOf("pcm"),
            sampleRate = sampleRate,
            channels = channels,
            platform = platform,
            extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
        )
        
        synchronized(discoveredServers) {
            val existing = discoveredServers[name]
            if (existing != null && existing.platform == platform) {
                if (platform == "AirPlay" || platform == "AirPlay2") {
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
            if (_state.value != DiscoveryState.SCANNING) {
                _state.value = DiscoveryState.FOUND
            }
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
        val airplayEnabled = prefs.getBoolean("airplay_enabled", false)
        if (airplayEnabled) {
            services.add("_airplay._tcp")
            
            raopDiscovery = RaopDiscovery(context)
            raopDiscovery?.start { device ->
                val server = Server(
                    name = device.name,
                    host = device.host,
                    port = device.port,
                    version = device.model ?: "1.0",
                    codecs = listOf("PCM"),
                    sampleRate = device.sampleRate,
                    channels = 2,
                    platform = "AirPlay",
                    extra = "et=${device.encryptionType};sr=${device.sampleRate};cn=${device.codec}"
                )
                scope.launch {
                    discoveredServers[device.host] = server
                    _servers.value = discoveredServers.values.toList()
                    _state.value = DiscoveryState.FOUND
                }
            }
        }
        if (prefs.getBoolean("google_cast_enabled", false)) {
            services.add("_googlecast._tcp")
        }

        discoveryJob = scope.launch {
            // Initial burst
            launch {
                services.forEach { type ->
                    try {
                        val listener = createNsdListener()
                        synchronized(activeListeners) { activeListeners.add(listener) }
                        nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (e: Exception) { Log.e(TAG, "Discovery failed for $type: ${e.message}") }
                }
            }

            launch { startUdpDiscoveryLoop() }
            if (prefs.getBoolean("dlna_enabled", false)) {
                launch { startSsdpDiscoveryLoop() }
            }

            // Keep "SCANNING" state for at least 15 seconds
            delay(15000)
            if (_state.value == DiscoveryState.SCANNING) {
                _state.value = if (discoveredServers.isEmpty()) DiscoveryState.NONE else DiscoveryState.FOUND
            }
        }
    }

    fun stopDiscovery() {
        raopDiscovery?.stop()
        raopDiscovery = null

        discoveryJob?.cancel()
        discoveryJob = null
        
        synchronized(activeListeners) {
            activeListeners.forEach { listener ->
                try { nsdManager.stopServiceDiscovery(listener) } catch (e: Exception) {}
            }
            activeListeners.clear()
        }

        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (e: Exception) {}
        resolveQueue.clear()
        activeResolves = 0
    }

    fun removeServer(name: String) {
        synchronized(discoveredServers) {
            discoveredServers.remove(name)
            _servers.value = discoveredServers.values.toList()
        }
    }

    private suspend fun startSsdpDiscoveryLoop() = withContext(Dispatchers.IO) {
        val ssdpAddress = InetAddress.getByName("239.255.255.250")
        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all",
            "upnp:rootdevice"
        )

        while (isActive) {
            searchTargets.forEach { st ->
                try {
                    val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\n\r\n"
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 4000
                        val packet = DatagramPacket(query.toByteArray(), query.length, ssdpAddress, 1900)
                        socket.send(packet)

                        val buffer = ByteArray(2048)
                        val endTime = System.currentTimeMillis() + 4000
                        while (System.currentTimeMillis() < endTime && isActive) {
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
                } catch (e: Exception) { Log.e(TAG, "SSDP error for $st: ${e.message}") }
                delay(1000)
            }
            delay(5000) // Repeat full cycle
        }
    }

    private suspend fun resolveDlnaDevice(location: String, hostAddress: String) = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(location).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>")
            
            if (friendlyName.isNotEmpty()) {
                var avTransportUrl = ""
                if (xml.contains("urn:schemas-upnp-org:service:AVTransport:1")) {
                    avTransportUrl = xml.substringAfter("urn:schemas-upnp-org:service:AVTransport:1").substringAfter("<controlURL>", "").substringBefore("</controlURL>")
                    if (avTransportUrl.isNotEmpty() && !avTransportUrl.startsWith("http")) {
                        val uri = java.net.URI(location)
                        avTransportUrl = "${uri.scheme}://${uri.host}:${uri.port}${if(avTransportUrl.startsWith("/")) "" else "/"}$avTransportUrl"
                    }
                }

                var renderingControlUrl = ""
                if (xml.contains("urn:schemas-upnp-org:service:RenderingControl:1")) {
                    renderingControlUrl = xml.substringAfter("urn:schemas-upnp-org:service:RenderingControl:1").substringAfter("<controlURL>", "").substringBefore("</controlURL>")
                    if (renderingControlUrl.isNotEmpty() && !renderingControlUrl.startsWith("http")) {
                        val uri = java.net.URI(location)
                        renderingControlUrl = "${uri.scheme}://${uri.host}:${uri.port}${if(renderingControlUrl.startsWith("/")) "" else "/"}$renderingControlUrl"
                    }
                }
                
                val extraParts = mutableListOf<String>()
                if (avTransportUrl.isNotEmpty()) extraParts.add("av_control=$avTransportUrl")
                if (renderingControlUrl.isNotEmpty()) extraParts.add("rc_control=$renderingControlUrl")

                val server = Server(
                    name = friendlyName,
                    host = hostAddress,
                    port = 0,
                    version = "1.0",
                    codecs = listOf("pcm"),
                    sampleRate = 48000,
                    channels = 2,
                    platform = "DLNA",
                    extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
                )
                synchronized(discoveredServers) {
                    discoveredServers[friendlyName] = server
                    _servers.value = discoveredServers.values.toList()
                    if (_state.value != DiscoveryState.SCANNING) {
                        _state.value = DiscoveryState.FOUND
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun startUdpDiscoveryLoop() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 3000
                    val packet = DatagramPacket("DISCOVER_AUDIOCAST".toByteArray(), 18, InetAddress.getByName("255.255.255.255"), 12888)
                    socket.send(packet)
                    val buffer = ByteArray(1024)
                    val endTime = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < endTime && isActive) {
                        val resp = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(resp)
                            val json = JSONObject(String(resp.data, 0, resp.length))
                            val server = Server(name = json.optString("server_name"), host = resp.address.hostAddress ?: "", port = json.optInt("port"), version = "1.0", codecs = listOf("pcm"), sampleRate = 48000, channels = 2, platform = "AriaCast")
                            synchronized(discoveredServers) {
                                discoveredServers[server.name] = server
                                _servers.value = discoveredServers.values.toList()
                                if (_state.value != DiscoveryState.SCANNING) {
                                    _state.value = DiscoveryState.FOUND
                                }
                            }
                        } catch (e: Exception) { break }
                    }
                }
            } catch (e: Exception) {}
            delay(5000)
        }
    }

    companion object { private const val TAG = "DiscoveryManager" }
}

enum class DiscoveryState { IDLE, SCANNING, FOUND, NONE }
