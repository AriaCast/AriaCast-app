package com.aria.ariacast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Patterns
import com.aria.ariacast.raop.RaopDiscovery
import com.aria.ariacast.raop.RaopDevice
import com.aria.ariacast.airplay.AirPlayTxt
import com.aria.ariacast.vban.VbanProtocol
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    /**
     * Servers added by address. Kept apart from [discoveredServers] (and guarded by its lock)
     * so a new scan doesn't drop them, and saved so they survive a restart; only an explicit
     * [removeServer] deletes one.
     */
    private val manualServers = loadManualServers()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var activeResolves = 0
    private val MAX_CONCURRENT_RESOLVES = 3
    private var discoveryJob: Job? = null
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var raopDiscovery: RaopDiscovery? = null
    private val fetchedLocations = mutableSetOf<String>()

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
                publishServers()
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
            // flags (status), pw, pi (pairing id), srcvers... drive the AirPlay 2 client's
            // pairing choice; features/pk/id are added below.
            listOf("flags", "pw", "pi", "srcvers", "protovers", "acl", "gid", "psi").forEach { key ->
                attrString(key)?.let { extraParts.add(ExtraFields.encode(key, it)) }
            }
        } else if (serviceInfo.serviceType.contains("_googlecast")) {
            platform = "Google Cast"
            name = attrString("fn") ?: name
            model = attrString("md")
            listOf("id", "st", "ca", "ve", "rs", "bs").forEach { key ->
                attrString(key)?.let { extraParts.add(ExtraFields.encode(if (key == "id") "cast_id" else key, it)) }
            }
        } else if (serviceInfo.serviceType.contains("_audiocast")) {
            platform = "AriaCast"
            // Our own receiver: never offer casting to ourselves.
            if (attrString("device_id") == DeviceIdentity.id(context)) return
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
            publishServers()
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
        synchronized(discoveredServers) {
            discoveredServers.clear()
            publishServers()
        }
        synchronized(fetchedLocations) { fetchedLocations.clear() }
        
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
            raopDiscovery?.start(onDeviceFound = { device ->
                // Hand the receiver's whole TXT record to the AirPlay 1 client: it picks
                // encryption (et), codec (cn), metadata types (md) and auth (pw/sf) from it.
                val fields = device.txt.filterKeys { it in RAOP_TXT_KEYS }.toMutableMap()
                device.mac?.let { fields["id"] = it }
                val server = Server(
                    name = device.name,
                    host = device.host,
                    port = device.port,
                    version = device.txt["vs"] ?: device.model ?: "1.0",
                    codecs = listOf(if (AirPlayTxt.intList(device.txt["cn"]).let { it.isEmpty() || 1 in it }) "ALAC" else "PCM"),
                    sampleRate = device.sampleRate,
                    channels = device.txt["ch"]?.toIntOrNull() ?: 2,
                    platform = "AirPlay",
                    extra = ExtraFields.join(fields.toList() + listOfNotNull(device.model?.let { "model" to it }))
                )
                synchronized(discoveredServers) {
                    discoveredServers["${device.name}::AirPlay"] = server
                    publishServers()
                    _state.value = DiscoveryState.FOUND
                }
            }, onDeviceLost = { name ->
                synchronized(discoveredServers) {
                    if (discoveredServers.remove("$name::AirPlay") != null) {
                        publishServers()
                    }
                }
            })
        }
        if (prefs.getBoolean("google_cast_enabled", false)) {
            services.add("_googlecast._tcp")
        }
        if (prefs.getBoolean("snapcast_enabled", false)) {
            services.add("_snapcast-ctrl._tcp")
            services.add("_snapcast-jsonrpc._tcp")
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
            if (prefs.getBoolean("vban_enabled", false)) {
                launch { startVbanDiscoveryLoop() }
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
            publishServers()
        }
    }

    /** Removes [server] whatever key it is stored under (DLNA and AirPlay entries aren't keyed by name). */
    fun removeServer(server: Server) {
        synchronized(discoveredServers) {
            discoveredServers.entries.removeAll { it.value == server }
            if (manualServers.entries.removeAll { it.value == server }) saveManualServers()
            publishServers()
        }
    }

    /** Call with the [discoveredServers] lock held. A manual entry hides the scan's entry for the same device and protocol. */
    private fun publishServers() {
        val manual = manualServers.values.toList()
        _servers.value = discoveredServers.values.filter { d -> manual.none { it.host == d.host && it.platform == d.platform } } + manual
    }

    private fun loadManualServers(): MutableMap<String, Server> {
        val result = LinkedHashMap<String, Server>()
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MANUAL_SERVERS, null) ?: return result
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val server = Server(
                    name = o.getString("name"), host = o.getString("host"), port = o.getInt("port"),
                    version = "manual", codecs = emptyList(), sampleRate = 48000, channels = 2,
                    platform = o.optString("platform").ifEmpty { null },
                    extra = o.optString("extra").ifEmpty { null }
                )
                result[manualKey(server.platform, server.host, server.port)] = server
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable saved servers: ${e.message}")
        }
        return result
    }

    /** Call with the [discoveredServers] lock held. */
    private fun saveManualServers() {
        val array = JSONArray()
        manualServers.values.forEach { s ->
            array.put(JSONObject().put("name", s.name).put("host", s.host).put("port", s.port)
                .put("platform", s.platform ?: "").put("extra", s.extra ?: ""))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_MANUAL_SERVERS, array.toString()).apply()
    }

    private fun manualKey(platform: String?, host: String, port: Int) = "manual|$platform|$host|$port"

    /**
     * Adds a server that was entered manually (e.g. via the "Manual Server Entry" plugin)
     * rather than discovered through mDNS/SSDP/UDP. Returns false if the host is not a
     * valid IP address, true once the server has been merged into [servers].
     *
     * [platform] picks the protocol AudioCastService speaks to it ("AriaCast", "AirPlay2",
     * "AirPlay", "Google Cast", "VBAN"); "Manual" (plugins) is treated as AriaCast.
     * [extra] carries protocol settings, e.g. the VBAN stream name.
     */
    @JvmOverloads // plugins call the three-argument form through Rhino
    fun addManualServer(host: String, port: Int, name: String, platform: String = "Manual", extra: String? = null): Boolean {
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
            platform = platform,
            // Without mDNS there is no TXT record: give the AirPlay 1 client the same
            // conservative capabilities it uses when falling back from AirPlay 2.
            extra = if (platform == "AirPlay") ExtraFields.join(MANUAL_RAOP_TXT) else extra
        )
        synchronized(discoveredServers) {
            manualServers[manualKey(platform, host, port)] = server
            saveManualServers()
            publishServers()
        }
        Log.d(TAG, "Manually added server: $name @ $host:$port ($platform)")
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
                                // Each device answers every search target, every cycle: fetch its description once per scan.
                                if (synchronized(fetchedLocations) { fetchedLocations.add(location) }) launch { resolveDlnaDevice(location, host) }
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
            // Only devices that can actually play: routers, NAS media servers and repeaters
            // answer SSDP too, but without AVTransport there is nothing to cast to.
            val desc = com.aria.ariacast.dlna.Dlna.parseRemoteDescription(xml) ?: return@withContext
            // Our own DLNA renderer: never offer casting to ourselves.
            if (desc.udn == com.aria.ariacast.dlna.DlnaRenderer.get(context).udn) return@withContext
            val avTransportUrl = resolveTrustedControlUrl(desc.avControl, locationUri, hostAddress) ?: return@withContext
            val renderingControlUrl = desc.rcControl?.let { resolveTrustedControlUrl(it, locationUri, hostAddress) }

            val extraParts = mutableListOf(ExtraFields.encode("av_control", avTransportUrl))
            if (renderingControlUrl != null) extraParts.add(ExtraFields.encode("rc_control", renderingControlUrl))

            val server = Server(
                name = desc.friendlyName,
                host = hostAddress,
                port = 0,
                version = "1.0",
                codecs = listOf("pcm"),
                sampleRate = 48000,
                channels = 2,
                platform = "DLNA",
                extra = extraParts.joinToString(";")
            )
            synchronized(discoveredServers) {
                // One entry per device: a renderer answers several SSDP search targets (and
                // some publish more than one description), so key by its UDN, not its name.
                val key = "dlna:" + (desc.udn ?: "$hostAddress|${desc.friendlyName}")
                // Same name on another host and another device: keep both visible and tell
                // them apart rather than letting a spoofed name pass as the familiar speaker.
                val clash = discoveredServers.any { (k, v) -> k != key && v.name == desc.friendlyName && v.host != hostAddress }
                discoveredServers[key] = if (clash) server.copy(name = "${desc.friendlyName} ($hostAddress)") else server
                publishServers()
                if (_state.value != DiscoveryState.SCANNING) {
                    _state.value = DiscoveryState.FOUND
                }
            }
        } catch (e: Exception) {
            // Unreachable or slow right now: let the next SSDP reply try again.
            synchronized(fetchedLocations) { fetchedLocations.remove(location) }
        }
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
                            if (json.optString("device_id") == DeviceIdentity.id(context)) continue
                            val server = Server(name = json.optString("server_name"), host = resp.address.hostAddress ?: "", port = json.optInt("port"), version = "1.0", codecs = listOf("pcm"), sampleRate = 48000, channels = 2, platform = "AriaCast")
                            synchronized(discoveredServers) {
                                discoveredServers[server.name] = server
                                publishServers()
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

    /**
     * VBAN has no mDNS: broadcast a SERVICE/PING0 request and list whatever answers as an
     * audio receiver. The spec marks the service part as preliminary and not every VBAN
     * app replies, so adding a receiver by address stays the dependable way.
     * Replies may go to the sender's port or to the VBAN port, so listen on 6980 if free.
     */
    private suspend fun startVbanDiscoveryLoop() = withContext(Dispatchers.IO) {
        var frame = 0
        val deviceName = android.os.Build.MODEL ?: "Android"
        while (isActive) {
            try {
                openVbanSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 3000
                    val ownAddresses = localAddresses()
                    val request = VbanProtocol.pingRequest(++frame, deviceName, "AriaCast", deviceName)
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), VbanProtocol.DEFAULT_PORT))
                    val buffer = ByteArray(2048)
                    val endTime = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < endTime && isActive) {
                        val resp = DatagramPacket(buffer, buffer.size)
                        try { socket.receive(resp) } catch (e: Exception) { break }
                        val reply = VbanProtocol.parsePingReply(resp.data, resp.length) ?: continue
                        val host = resp.address.hostAddress ?: continue
                        if (!reply.receivesAudio || host in ownAddresses) continue
                        val server = Server(
                            name = reply.displayName, host = host, port = VbanProtocol.DEFAULT_PORT,
                            version = "VBAN", codecs = listOf("pcm"), sampleRate = 48000, channels = 2,
                            platform = VbanProtocol.PLATFORM,
                            extra = reply.application.takeIf { it.isNotBlank() }?.let { ExtraFields.encode("model", it) }
                        )
                        synchronized(discoveredServers) {
                            discoveredServers["$host::VBAN"] = server
                            publishServers()
                            if (_state.value != DiscoveryState.SCANNING) _state.value = DiscoveryState.FOUND
                        }
                    }
                }
            } catch (e: Exception) { Log.d(TAG, "VBAN ping failed: ${e.message}") }
            delay(5000)
        }
    }

    private fun openVbanSocket(): DatagramSocket = try {
        DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(VbanProtocol.DEFAULT_PORT)) }
    } catch (e: Exception) {
        DatagramSocket()
    }

    private fun localAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    } catch (e: Exception) { emptySet() }

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
        private const val PREFS_NAME = "AriaCastPrefs"
        private const val KEY_MANUAL_SERVERS = "manual_servers"
        private const val MAX_DEVICE_DESCRIPTION_BYTES = 262_144

        /** `_raop._tcp` TXT keys the AirPlay 1 client uses. */
        private val MANUAL_RAOP_TXT = listOf("md" to "0,1,2", "et" to "0,1", "cn" to "0,1")
        private val RAOP_TXT_KEYS = setOf("et", "cn", "md", "pw", "sf", "ft", "am", "tp", "vn", "vs", "ss", "sr", "ch", "da", "sv", "ek", "pk")

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
