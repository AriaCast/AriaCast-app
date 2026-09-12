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
            val baseName = if (serviceInfo.serviceType.contains("_raop") && serviceInfo.serviceName.contains("@")) {
                serviceInfo.serviceName.substringAfter("@")
            } else {
                serviceInfo.serviceName
            }
            val platform = when {
                serviceInfo.serviceType.contains("_raop") -> "AirPlay"
                serviceInfo.serviceType.contains("_airplay") -> "AirPlay2"
                else -> null
            }
            val nameToRemove = if (platform != null) "$baseName::$platform" else baseName
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
            attrString("st")?.let { extraParts.add(ExtraFields.encode("st", it)) }
            attrString("ca")?.let { extraParts.add(ExtraFields.encode("ca", it)) }
            attrString("ve")?.let { extraParts.add(ExtraFields.encode("ve", it)) }
        } else if (serviceInfo.serviceType.contains("_audiocast")) {
            platform = "AriaCast"
        } else if (serviceInfo.serviceType.contains("_snapcast")) {
            platform = "Snapcast"
        }
        // Ensure name is never empty
        if (name.trim().isEmpty()) {
            name = originalName
        }

        if (model != null) extraParts.add(ExtraFields.encode("model", model))
        if (deviceId != null) extraParts.add(ExtraFields.encode("id", deviceId))
        if (features != null) extraParts.add(ExtraFields.encode("features", features))
        if (pk != null) extraParts.add(ExtraFields.encode("pk", pk))

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
            // A different device advertising the same friendly name must not silently
            // take over an already-discovered entry's host - that's exactly how mDNS
            // name spoofing would redirect a user who trusts a familiar name into
            // casting to an attacker's box instead. Key it separately and disambiguate
            // the display name so both stay visible rather than one clobbering the other.
            val existingSameName = discoveredServers[name]
            val spoofedName = existingSameName != null && existingSameName.host != hostAddress
            val baseKey = if (spoofedName) "$name@$hostAddress" else name
            val key = if (platform == "AirPlay" || platform == "AirPlay2") "$baseKey::$platform" else baseKey
            val candidate = if (spoofedName) server.copy(name = "$name ($hostAddress)") else server

            val existing = discoveredServers[key]
            if (existing != null && existing.platform == platform) {
                if (platform == "AirPlay" || platform == "AirPlay2") {
                    val isRaop = serviceInfo.serviceType.contains("_raop")
                    discoveredServers[key] = if (isRaop) {
                        candidate.copy(extra = mergeExtras(existing.extra, candidate.extra))
                    } else {
                        existing.copy(
                            extra = mergeExtras(existing.extra, candidate.extra),
                            version = if (candidate.version != "1.0") candidate.version else existing.version
                        )
                    }
                } else {
                    discoveredServers[key] = candidate
                }
            } else {
                discoveredServers[key] = candidate
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
        val merged = ExtraFields.parse(old) + ExtraFields.parse(new)
        return ExtraFields.join(merged.toList())
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
        val airplay2Enabled = prefs.getBoolean("airplay2_enabled", false)
        if (airplay2Enabled) {
            services.add("_airplay._tcp")
        }
        if (airplayEnabled) {
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
        if (prefs.getBoolean("snapcast_enabled", false)) {
            services.add("_snapcast-ctrl._tcp")
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

    /**
     * Adds a server that was entered manually (e.g. via the "Manual Server Entry" plugin)
     * rather than discovered through mDNS/SSDP/UDP. Returns false if the host is not a
     * valid IP address, true once the server has been merged into [servers].
     */
    fun addManualServer(host: String, port: Int, name: String): Boolean {
        if (!Patterns.IP_ADDRESS.matcher(host).matches()) {
            Log.w(TAG, "addManualServer: invalid host '$host'")
            return false
        }
        val server = Server(
            name = name,
            host = host,
            port = port,
            version = "manual",
            codecs = emptyList(),
            sampleRate = 48000,
            channels = 2,
            platform = "Manual"
        )
        synchronized(discoveredServers) {
            discoveredServers[name] = server
            _servers.value = discoveredServers.values.toList()
        }
        Log.d(TAG, "Manually added server: $name @ $host:$port")
        return true
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
            val locationUri = java.net.URI(location)
            val scheme = locationUri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return@withContext
            // The LOCATION header is attacker-controllable by anything on the LAN that can
            // send an SSDP reply. Requiring it to point back at the address the UDP reply
            // actually came from stops it being used to make this device fetch an arbitrary
            // internal URL (SSRF) instead of the description of the device that just replied.
            if (locationUri.host == null || locationUri.host != hostAddress) return@withContext

            val connection = locationUri.toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val xml = connection.inputStream.use { readBounded(it, MAX_DEVICE_DESCRIPTION_BYTES) }
            val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>")
            
            if (friendlyName.isNotEmpty()) {
                var avTransportUrl = ""
                if (xml.contains("urn:schemas-upnp-org:service:AVTransport:1")) {
                    val raw = xml.substringAfter("urn:schemas-upnp-org:service:AVTransport:1").substringAfter("<controlURL>", "").substringBefore("</controlURL>")
                    avTransportUrl = resolveTrustedControlUrl(raw, locationUri, hostAddress) ?: ""
                }

                var renderingControlUrl = ""
                if (xml.contains("urn:schemas-upnp-org:service:RenderingControl:1")) {
                    val raw = xml.substringAfter("urn:schemas-upnp-org:service:RenderingControl:1").substringAfter("<controlURL>", "").substringBefore("</controlURL>")
                    renderingControlUrl = resolveTrustedControlUrl(raw, locationUri, hostAddress) ?: ""
                }

                val extraParts = mutableListOf<String>()
                if (avTransportUrl.isNotEmpty()) extraParts.add(ExtraFields.encode("av_control", avTransportUrl))
                if (renderingControlUrl.isNotEmpty()) extraParts.add(ExtraFields.encode("rc_control", renderingControlUrl))

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
                    val existing = discoveredServers[friendlyName]
                    if (existing != null && existing.host != hostAddress) {
                        discoveredServers["$friendlyName@$hostAddress"] = server.copy(name = "$friendlyName ($hostAddress)")
                    } else {
                        discoveredServers[friendlyName] = server
                    }
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
                        } catch (e: Exception) {
                            // Timeout (expected once the window elapses) or a socket-level
                            // error - either way there's nothing more to receive right now.
                            break
                        }
                        try {
                            val json = JSONObject(String(resp.data, 0, resp.length))
                            val server = Server(name = json.optString("server_name"), host = resp.address.hostAddress ?: "", port = json.optInt("port"), version = "1.0", codecs = listOf("pcm"), sampleRate = 48000, channels = 2, platform = "AriaCast")
                            synchronized(discoveredServers) {
                                discoveredServers[server.name] = server
                                _servers.value = discoveredServers.values.toList()
                                if (_state.value != DiscoveryState.SCANNING) {
                                    _state.value = DiscoveryState.FOUND
                                }
                            }
                        } catch (e: Exception) {
                            // Malformed reply from one sender - ignore just this packet and
                            // keep listening for the rest of the window instead of ending
                            // the whole scan early on a single bad/spoofed reply.
                        }
                    }
                }
            } catch (e: Exception) {}
            delay(5000)
        }
    }


    /** Reads at most [maxBytes] from [input] as UTF-8 text, so a malicious/misbehaving
     *  device can't hang discovery by streaming an unbounded response body. */
    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buffer.size() < maxBytes) {
            val toRead = minOf(chunk.size, maxBytes - buffer.size())
            val read = input.read(chunk, 0, toRead)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val MAX_DEVICE_DESCRIPTION_BYTES = 262_144

        /**
         * Resolves a UPnP <controlURL> value against the (already host-validated) device
         * description location. A relative path is joined onto that location as usual. An
         * absolute URL is only accepted if it points back at the same host that answered
         * the SSDP query - otherwise the device description itself could redirect SOAP
         * control requests to an arbitrary third-party host/port (confused-deputy SSRF),
         * so it's dropped instead of trusted.
         */
        internal fun resolveTrustedControlUrl(raw: String, locationUri: java.net.URI, expectedHost: String): String? {
            if (raw.isEmpty()) return null
            if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
                val portPart = if (locationUri.port == -1) "" else ":${locationUri.port}"
                return "${locationUri.scheme}://${locationUri.host}${portPart}${if (raw.startsWith("/")) "" else "/"}$raw"
            }
            val uri = try { java.net.URI(raw) } catch (e: Exception) { return null }
            return if (uri.host == expectedHost) raw else null
        }
    }
}

enum class DiscoveryState { IDLE, SCANNING, FOUND, NONE }
