package com.aria.ariacast.raop

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.aria.ariacast.airplay.AirPlayTxt
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class RaopDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** The receiver's MAC, from the "AABBCCDDEEFF@Name" service name. */
    val mac: String?,
    /** The whole TXT record (et, cn, md, pw, sf, am, tp, ...). */
    val txt: Map<String, String>
) {
    val model: String? get() = txt["am"]
    val sampleRate: Int get() = txt["sr"]?.toIntOrNull() ?: 44100
    val encryptionTypes: Set<Int> get() = AirPlayTxt.intList(txt["et"])

    /** We can stream unencrypted (0) or with RSA/AES (1); FairPlay-only receivers are out of reach. */
    fun isSupported(): Boolean = encryptionTypes.isEmpty() || 0 in encryptionTypes || 1 in encryptionTypes
}

/** Browses `_raop._tcp` (AirPlay 1 audio) with JmDNS, which exposes full TXT records. */
class RaopDiscovery(private val context: Context) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(onDeviceFound: (RaopDevice) -> Unit, onDeviceLost: (String) -> Unit = {}) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("RaopDiscoveryLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val localAddr = RaopUtils.getLocalIpAddress() ?: run {
            Log.e(TAG, "Could not get local IP address")
            return
        }

        Thread {
            try {
                jmdns = JmDNS.create(localAddr)
                jmdns?.addServiceListener("_raop._tcp.local.", object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns?.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Log.d(TAG, "Service removed: ${event.name}")
                        onDeviceLost(event.name.substringAfter("@"))
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val host = info.inet4Addresses.firstOrNull()?.hostAddress
                            ?: info.inetAddresses.firstOrNull()?.hostAddress ?: return
                        val txt = LinkedHashMap<String, String>()
                        val names = info.propertyNames
                        while (names.hasMoreElements()) {
                            val key = names.nextElement()
                            info.getPropertyString(key)?.let { txt[key] = it }
                        }
                        // "MAC@Device Name"
                        val mac = info.name.substringBefore("@", "").takeIf { it.length == 12 }
                        val name = info.name.substringAfter("@")
                        val device = RaopDevice(name, host, info.port, mac, txt)
                        Log.d(TAG, "Found RAOP device: $name at $host:${info.port} txt=$txt")
                        if (device.isSupported()) {
                            onDeviceFound(device)
                        } else {
                            Log.w(TAG, "Device $name only offers encryption types ${device.encryptionTypes}, skipping")
                        }
                    }
                })
                Log.i(TAG, "JmDNS discovery started on $localAddr")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start JmDNS", e)
            }
        }.start()
    }

    fun stop() {
        Thread {
            try {
                jmdns?.close()
                jmdns = null
                multicastLock?.release()
                multicastLock = null
                Log.i(TAG, "JmDNS discovery stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping JmDNS", e)
            }
        }.start()
    }

    companion object {
        private const val TAG = "RaopDiscovery"
    }
}
