package com.aria.ariacast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aria.ariacast.DeviceIdentity
import com.aria.ariacast.R
import com.aria.ariacast.pipeline.FgsTypes
import com.aria.ariacast.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Runs this device's receiver: the AriaCast server, mDNS advertisement through NsdManager,
 * and the UDP discovery responder. Independent of AudioCastService, so a device can
 * receive and send at the same time.
 *
 * Uses the connectedDevice foreground type: it is allowed when started at boot (mediaPlayback
 * is not, from Android 15), and the app holds CHANGE_WIFI_MULTICAST_STATE, which qualifies.
 */
class ReceiverService : Service() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var hub: ReceiverHub
    private var server: ReceiverServer? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var udpSocket: DatagramSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hub = ReceiverHub.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (server == null) startReceiver()
        }
        return START_STICKY
    }

    private fun startReceiver() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(null), FgsTypes.CONNECTED_DEVICE)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground: ${e.message}")
            stopSelf()
            return
        }
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val srv = ReceiverServer(PORT, scope, object : ReceiverServer.Listener {
            override fun onSenderConnected(remote: String) { hub.onSenderConnected(remote); acquireWifiLock() }
            override fun onSenderDisconnected(remote: String) { hub.onSenderDisconnected(remote); releaseWifiLock() }
            override fun onPcm(data: ByteArray) = hub.onPcm(data)
            override fun onMetadata(update: MetadataUpdate) = hub.onMetadata(update)
            override fun onCommand(action: String) = hub.onCommand(action)
            override fun currentMetadata() = hub.metadata.value
            override fun currentArtwork() = hub.artwork.value
        })
        try {
            srv.start()
        } catch (e: Exception) {
            Log.e(TAG, "Receiver server failed to start: ${e.message}")
            stopSelf()
            return
        }
        server = srv
        hub.server = srv
        hub.setRunning(true)
        registerNsd()
        startUdpResponder()
        scope.launch {
            combine(hub.sender, hub.metadata) { s, m -> s to m }.collect { (s, m) ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(if (s == null) null else listOfNotNull(m.title, m.artist).joinToString(" · ").ifEmpty { s }))
            }
        }
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = hub.name
            serviceType = "_audiocast._tcp"
            port = PORT
            setAttribute("version", "1.0")
            setAttribute("samplerate", "48000")
            setAttribute("channels", "2")
            setAttribute("platform", "android")
            setAttribute("device_id", DeviceIdentity.id(this@ReceiverService))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "Advertised as ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS registration failed: $errorCode") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdRegistration = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration error: ${e.message}")
        }
    }

    private fun startUdpResponder() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wm.createMulticastLock("AriaCast-receiver").apply { setReferenceCounted(false); acquire() }
        scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                udpSocket = socket
                val buf = ByteArray(1024)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: Exception) { if (!isActive) break else continue }
                    if (String(packet.data, 0, packet.length).trim() != "DISCOVER_AUDIOCAST") continue
                    val reply = JSONObject()
                        .put("server_name", hub.name)
                        .put("ip", localIpv4() ?: "")
                        .put("port", PORT)
                        .put("samplerate", 48000)
                        .put("channels", 2)
                        .put("device_id", DeviceIdentity.id(this@ReceiverService))
                        .toString().toByteArray()
                    runCatching { socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP discovery responder stopped: ${e.message}")
            }
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AriaCast-receiver").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun shutdown() {
        nsdRegistration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        nsdRegistration = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        server?.stop()
        server = null
        hub.server = null
        hub.setRunning(false)
        releaseWifiLock()
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (server != null) shutdown()
        super.onDestroy()
    }

    private fun buildNotification(nowPlaying: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.receiver_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setContentTitle(getString(R.string.receiver_notification_title, hub.name))
            .setContentText(nowPlaying ?: getString(R.string.receiver_waiting))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_START = "com.aria.ariacast.receiver.START"
        const val ACTION_STOP = "com.aria.ariacast.receiver.STOP"
        const val PORT = 12889
        const val DISCOVERY_PORT = 12888
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "ReceiverChannel"

        fun localIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }?.hostAddress
        } catch (_: Exception) { null }
    }
}

/** Starts the receiver after boot when the user asked for it (dedicated speaker tablets and TVs). */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        if (ReceiverHub.get(context).startAtBoot) ReceiverHub.get(context).start()
    }
}
