package com.aria.ariacast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import com.aria.ariacast.airplay.AirPlayAuthRequiredException
import com.aria.ariacast.airplay.AirPlayBusyException
import com.aria.ariacast.airplay.AirPlaySenderIdentity
import com.aria.ariacast.airplay.AirPlaySessionListener
import com.aria.ariacast.airplay.AirPlayStreamer
import com.aria.ariacast.airplay.AirPlayTimingUnsupportedException
import com.aria.ariacast.airplay.AirPlayVolume
import com.aria.ariacast.airplay.DacpServer
import com.aria.ariacast.airplay.PtpMaster
import com.aria.ariacast.airplay2.AirPlay2Client
import com.aria.ariacast.airplay2.HapCredentials
import com.aria.ariacast.cast.CastClient
import com.aria.ariacast.cast.CastListener
import com.aria.ariacast.cast.CastMetadata
import com.aria.ariacast.raop.AudioResampler
import com.aria.ariacast.raop.RaopClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.pow

data class CastDestination(
    val name: String,
    val host: String,
    val port: Int,
    val platform: String? = null,
    var delayMs: Int = 0,
    val extra: String? = null
)

class AudioCastService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var sessionJob: Job? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var securePreferences: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    
    private val _activeDestinations = MutableStateFlow<List<CastDestination>>(emptyList())
    val activeDestinations: StateFlow<List<CastDestination>> = _activeDestinations.asStateFlow()
    
    // These are all mutated from independent Dispatchers.IO coroutines (one per cast
    // destination, plus the metadata refresh timer and volume commands running
    // concurrently), so a plain HashMap/HashSet here is a real ConcurrentModificationException
    // risk - e.g. hitting Stop while a metadata push is in flight. synchronizedMap/Set
    // (rather than ConcurrentHashMap) for consistency with the iteration snapshots below.
    private val controlSessions = java.util.Collections.synchronizedMap(mutableMapOf<String, DefaultClientWebSocketSession>())
    // Connected AirPlay 1/2 sessions and Google Cast sessions, keyed by receiver host.
    private val airplayStreamers = java.util.Collections.synchronizedMap(mutableMapOf<String, AirPlayStreamer>())
    private val castClients = java.util.Collections.synchronizedMap(mutableMapOf<String, CastClient>())
    private val lastSentMetadata = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val lastSentArtwork = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
    @Volatile private var ptpMaster: PtpMaster? = null
    private var dacpServer: DacpServer? = null
    private var dacpRegistration: NsdManager.RegistrationListener? = null
    private var volumeProvider: VolumeProvider? = null
    private var volumeBeforeMute = -1
    @Volatile private var lastPlayingState: Boolean? = null
    
    private val dacpId by lazy { 
        sharedPreferences.getString("dacp_id", null) ?: run {
            val id = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
            sharedPreferences.edit().putString("dacp_id", id).apply()
            id
        }
    }
    private val activeRemote by lazy {
        sharedPreferences.getString("active_remote", null) ?: run {
            val id = (10000000..99999999).random().toString()
            sharedPreferences.edit().putString("active_remote", id).apply()
            id
        }
    }
    private val airplayDeviceId by lazy {
        sharedPreferences.getString("airplay_device_id", null) ?: run {
            val id = (1..6).joinToString(":") { "%02X".format((0..255).random()) }
            sharedPreferences.edit().putString("airplay_device_id", id).apply()
            id
        }
    }

    // The long-term identity AirPlay receivers pair with. The Ed25519 seed is a secret
    // (whoever holds it can impersonate this phone to receivers paired with it), so it
    // lives in the non-backed-up secure prefs alongside receiver passwords/pairings.
    private val airPlayIdentity by lazy {
        val seedHex = securePreferences.getString(KEY_PAIRING_SEED, null)?.takeIf { it.length == 64 } ?: run {
            val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val hex = seed.joinToString("") { "%02x".format(it) }
            securePreferences.edit().putString(KEY_PAIRING_SEED, hex).apply()
            hex
        }
        val pairingId = securePreferences.getString(KEY_PAIRING_ID, null) ?: run {
            val id = UUID.randomUUID().toString().uppercase()
            securePreferences.edit().putString(KEY_PAIRING_ID, id).apply()
            id
        }
        AirPlaySenderIdentity(
            deviceId = airplayDeviceId,
            dacpId = dacpId,
            activeRemote = activeRemote,
            name = getString(R.string.app_name),
            model = Build.MODEL ?: "Android",
            pairingSeed = ByteArray(32) { seedHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
            pairingId = pairingId
        )
    }

    val serverHost: String? get() = _activeDestinations.value.firstOrNull()?.host
    val serverName: String? get() = if (_activeDestinations.value.size > 1) "Multiroom Group" else _activeDestinations.value.firstOrNull()?.name
    val serverPort: Int get() = _activeDestinations.value.firstOrNull()?.port ?: 0
    val serverPlatform: String? get() = _activeDestinations.value.firstOrNull()?.platform

    private var currentArtworkBytes: ByteArray? = null
    private var lastBitrateTime = 0L
    private var lastSentFramesForBitrate = 0L
    private var currentBitrateString = "0 kbps"

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    val dispatcher = okhttp3.Dispatcher()
                    dispatcher.maxRequests = 64
                    dispatcher.maxRequestsPerHost = 16
                    dispatcher(dispatcher)
                }
            }
            install(WebSockets) {
                pingInterval = 5000
                maxFrameSize = Long.MAX_VALUE
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
                connectTimeoutMillis = 10000
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.i(TAG, "Ktor: $message")
                    }
                }
                level = LogLevel.INFO
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    private val _state = MutableStateFlow<CastState>(CastState.OFF)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(CastingStats())
    val stats: StateFlow<CastingStats> = _stats.asStateFlow()

    private val _metadata = MutableStateFlow<TrackMetadata?>(null)
    val metadata: StateFlow<TrackMetadata?> = _metadata.asStateFlow()

    /** A receiver needs a PIN (shown on its screen) or a password from the user. */
    data class PairingRequest(val host: String, val name: String, val kind: AirPlayAuthRequiredException.Kind)

    private val _pairingPinRequest = MutableStateFlow<PairingRequest?>(null)
    val pairingPinRequest: StateFlow<PairingRequest?> = _pairingPinRequest.asStateFlow()

    @Volatile private var activePinDeferred: CompletableDeferred<String?>? = null
    // Several receivers in a group may each need a code; ask for them one at a time.
    private val pairingPromptMutex = Mutex()

    private val _controlCommands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 10)
    val controlCommands: SharedFlow<MediaCommand> = _controlCommands.asSharedFlow()

    private val _audioBufferFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioBufferFlow: SharedFlow<ByteArray> = _audioBufferFlow.asSharedFlow()

    private val metadataChannel = Channel<TrackMetadata>(Channel.CONFLATED)

    private val binder = AudioCastBinder()

    inner class AudioCastBinder : Binder() {
        fun getService(): AudioCastService = this@AudioCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        securePreferences = getSharedPreferences(SECURE_PREFS_NAME, MODE_PRIVATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        startMetadataWorker()
        startArtworkServer()
    }

    private fun startMetadataWorker() {
        scope.launch {
            for (metadata in metadataChannel) {
                _metadata.value = metadata
                performMetadataUpdate(metadata)
            }
        }
    }

    private fun CoroutineScope.startMetadataRefreshLoop() {
        launch {
            while (isActive) {
                delay(10000)
                _metadata.value?.let { currentMetadata ->
                    performMetadataUpdate(currentMetadata)
                }
            }
        }
    }

    private fun startArtworkServer() {
        artworkServerJob?.cancel()
        try { artworkServerSocket?.close() } catch (e: Exception) {}
        artworkServerJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(java.net.InetSocketAddress(ARTWORK_PORT))
                artworkServerSocket = serverSocket
                while (isActive) {
                    val clientSocket = try { serverSocket.accept() } catch (e: Exception) { null } ?: continue
                    launch {
                        try {
                            clientSocket.soTimeout = 10000
                            val request = clientSocket.getInputStream().bufferedReader().readLine()
                            Log.d(TAG, "Artwork request: $request")
                            val output = clientSocket.getOutputStream()
                            val bytes = currentArtworkBytes
                            if (bytes != null) {
                                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                                output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                                output.write("Cache-Control: no-cache\r\n".toByteArray())
                                output.write("Connection: close\r\n\r\n".toByteArray())
                                output.write(bytes)
                            } else {
                                output.write("HTTP/1.1 404 Not Found\r\n".toByteArray())
                                output.write("Connection: close\r\n\r\n".toByteArray())
                            }
                            output.flush()
                        } catch (e: Exception) {
                        } finally {
                            try { clientSocket.close() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start artwork server: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COMPANION -> {
                cleanupSession()
                val destinations = parseDestinations(intent)
                if (destinations.isNotEmpty()) startCastingWithCompanion(destinations)
                return START_STICKY
            }
            ACTION_START -> {
                cleanupSession()

                val mediaProjectionToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_TOKEN, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_TOKEN)
                }

                val destinations = parseDestinations(intent)
                if (mediaProjectionToken != null && destinations.isNotEmpty()) {
                    startCasting(mediaProjectionToken, destinations)
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopCasting()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun parseDestinations(intent: Intent): List<CastDestination> {
        val destinations = mutableListOf<CastDestination>()
        val serversJson = intent.getStringExtra(EXTRA_SERVERS_JSON)
        if (serversJson != null) {
            val array = JSONArray(serversJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                destinations.add(CastDestination(
                    name = obj.getString("name"),
                    host = obj.getString("host"),
                    port = obj.getInt("port"),
                    platform = obj.optString("platform", null),
                    extra = obj.optString("extra", null)
                ))
            }
        } else {
            val host = intent.getStringExtra(EXTRA_SERVER_HOST)
            val port = intent.getIntExtra(EXTRA_SERVER_PORT, 0)
            val name = intent.getStringExtra(EXTRA_SERVER_NAME)
            val platform = intent.getStringExtra(EXTRA_SERVER_PLATFORM)
            val extra = intent.getStringExtra(EXTRA_SERVER_EXTRA)
            if (host != null && port != 0 && name != null) {
                destinations.add(CastDestination(name, host, port, platform, extra = extra))
            } else if (platform == "DLNA" && host != null && name != null) {
                destinations.add(CastDestination(name, host, 0, platform, extra = extra))
            }
        }
        return destinations
    }

    // AriaCompanion streams straight from the ESP32 WiFi sender board to a
    // real AriaCast Receiver (native protocol only — that's all the board
    // speaks); the phone is never in the audio path. This service's only
    // job is to point the board at the chosen receiver via its REST API.
    @SuppressLint("MissingPermission")
    private fun startCastingWithCompanion(destinations: List<CastDestination>) {
        val receiver = destinations.firstOrNull()
        if (receiver == null) {
            Log.e(TAG, "No AriaCast receiver selected for AriaCompanion")
            _state.value = CastState.ERROR
            return
        }
        if (destinations.size > 1) {
            Log.w(TAG, "AriaCompanion streams to a single receiver; ignoring ${destinations.size - 1} extra destination(s)")
        }

        sessionJob?.cancel()
        _activeDestinations.value = listOf(receiver)
        _state.value = CastState.CONNECTING
        sessionJob = SupervisorJob()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob!!)

        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        with(sharedPreferences.edit()) {
            putString(KEY_LAST_SERVER_HOST, receiver.host)
            putInt(KEY_LAST_SERVER_PORT, receiver.port)
            putString(KEY_LAST_SERVER_NAME, receiver.name)
            putString(KEY_LAST_SERVER_PLATFORM, receiver.platform)
            apply()
        }

        try {
            val notification = createNotification()
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            if (e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground service start not allowed", e)
                _state.value = CastState.ERROR
                return
            } else {
                throw e
            }
        }

        acquireWakeLock()
        startVolumeSession()

        val companionIp = sharedPreferences.getString(AriaCompanionActivity.KEY_COMPANION_IP, null)
        val companionPort = sharedPreferences.getInt(AriaCompanionActivity.KEY_COMPANION_PORT, COMPANION_API_PORT)

        if (companionIp.isNullOrEmpty()) {
            Log.e(TAG, "No companion IP configured")
            _state.value = CastState.ERROR
            return
        }

        // Set before launching so performMetadataUpdate already routes
        // through the board for the initial send below and the refresh loop.
        companionApiHost = companionIp
        companionApiPort = companionPort

        sessionScope.launch {
            startMetadataRefreshLoop()
            _metadata.value?.let { sendMetadata(it) }
            startCompanionRelay(companionIp, companionPort, receiver)
        }
    }

    private var companionApiHost: String? = null
    private var companionApiPort: Int = 0

    private suspend fun startCompanionRelay(apiHost: String, apiPort: Int, receiver: CastDestination) {
        val pointed = withContext(Dispatchers.IO) {
            setCompanionReceiverTarget(apiHost, apiPort, receiver.host, receiver.port)
        }
        if (!pointed) {
            Log.e(TAG, "Companion: failed to reach AriaCompanion REST API at $apiHost:$apiPort")
            _state.value = CastState.ERROR
            return
        }

        Log.i(TAG, "Companion: told $apiHost:$apiPort to stream to ${receiver.host}:${receiver.port}")

        // The board now streams directly to the receiver on its own; poll its
        // status just to reflect the real connection state in the app instead
        // of optimistically showing CASTING the instant the REST call succeeds.
        var consecutiveFailures = 0
        var lastLoggedState: String? = null
        while (currentCoroutineContext().isActive) {
            val boardState = withContext(Dispatchers.IO) { fetchCompanionReceiverState(apiHost, apiPort) }
            if (boardState != null) {
                if (consecutiveFailures > 0) {
                    PacketLogger.log(PacketDirection.IN, PacketType.STATS, "AriaCompanion contact restored ($apiHost:$apiPort)")
                }
                consecutiveFailures = 0
                if (boardState != lastLoggedState) {
                    PacketLogger.log(PacketDirection.IN, PacketType.STATS, "AriaCompanion state: $boardState")
                    lastLoggedState = boardState
                }
                _state.value = if (boardState == "streaming") CastState.CASTING else CastState.CONNECTING
                updateNotification()
            } else {
                consecutiveFailures++
                if (consecutiveFailures == 3) {
                    Log.e(TAG, "Companion: lost contact with $apiHost:$apiPort")
                    PacketLogger.log(PacketDirection.IN, PacketType.STATS, "Lost contact with AriaCompanion ($apiHost:$apiPort)")
                    _state.value = CastState.ERROR
                    updateNotification()
                }
            }
            delay(3000)
        }
    }

    private fun fetchCompanionReceiverState(apiHost: String, apiPort: Int): String? {
        val url = URL("http://$apiHost:$apiPort/api/status")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONObject("receiver")?.optString("state")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun setCompanionReceiverTarget(apiHost: String, apiPort: Int, receiverHost: String, receiverPort: Int): Boolean {
        return try {
            val url = URL("http://$apiHost:$apiPort/api/receiver")
            val body = JSONObject().apply {
                put("host", receiverHost)
                put("port", receiverPort)
            }.toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val responseCode = conn.responseCode
            conn.disconnect()
            val ok = responseCode == 200
            if (ok) {
                PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "AriaCompanion ($apiHost:$apiPort) told to stream to $receiverHost:$receiverPort")
            } else {
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "AriaCompanion setReceiver returned HTTP $responseCode")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Companion: setReceiver failed: ${e.message}")
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "AriaCompanion setReceiver failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun clearCompanionReceiverTarget(apiHost: String, apiPort: Int) {
        try {
            val url = URL("http://$apiHost:$apiPort/api/receiver")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 3000
                readTimeout = 3000
            }
            conn.responseCode
            conn.disconnect()
            PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "AriaCompanion ($apiHost:$apiPort) receiver cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Companion: clearReceiver failed: ${e.message}")
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "AriaCompanion clearReceiver failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }


    @SuppressLint("MissingPermission")
    private fun startCasting(mediaProjectionToken: Intent, destinations: List<CastDestination>) {
        sessionJob?.cancel()
        
        _activeDestinations.value = destinations
        _state.value = CastState.CONNECTING
        sessionJob = SupervisorJob()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob!!)

        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        if (destinations.size == 1) {
            with(sharedPreferences.edit()) {
                putString(KEY_LAST_SERVER_HOST, destinations[0].host)
                putInt(KEY_LAST_SERVER_PORT, destinations[0].port)
                putString(KEY_LAST_SERVER_NAME, destinations[0].name)
                putString(KEY_LAST_SERVER_PLATFORM, destinations[0].platform)
                apply()
            }
        }

        try {
            val notification = createNotification()
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else 0
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            if (e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground service start not allowed", e)
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Foreground service start not allowed: ${e.message}")
                _state.value = CastState.ERROR
                return
            } else {
                throw e
            }
        }

        acquireWakeLock()
        // Only intercept volume keys for protocols with remote volume control
        if (destinations.any { it.platform in listOf("AirPlay", "AirPlay2", "AriaCast", "DLNA", "Google Cast") }) {
            startVolumeSession()
        }
        if (destinations.any { it.platform == "AirPlay" || it.platform == "AirPlay2" }) {
            startDacpServer()
        }
        if (destinations.any { it.platform == "AirPlay2" }) {
            // Only succeeds where the privileged PTP ports can be bound (rooted devices);
            // otherwise AirPlay 2 uses NTP timing, falling back to AirPlay 1.
            ptpMaster = PtpMaster.tryCreate()
        }

        // MediaProjection token is single-use on Android 14+; obtain it once before any retries.
        val projection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken)
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null")
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "MediaProjection permission was not granted")
            stopVolumeSession()
            releaseWakeLock()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            sessionJob?.cancel()
            sessionJob = null
            _state.value = CastState.ERROR
            return
        }
        mediaProjection = projection
        projection.registerCallback(mediaProjectionCallback, null)

        sessionScope.launch {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            var attempt = 0
            var initialized = false

            while (attempt < 3 && !initialized && isActive) {
                if (attempt > 0) delay(400)
                attempt++

                try {
                    // Always capture at SAMPLE_RATE (48 kHz).  AirPlay 1 (RAOP) needs
                    // 44100 Hz, but that resampling is done per-destination inside
                    // performRaopHandshake so other receivers in a multiroom group
                    // are not affected.
                    val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    val bufferSize = (FRAME_SIZE * 4).coerceAtLeast(minBufSize)

                    val recorder = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build()
                        )
                        .setAudioPlaybackCaptureConfig(config)
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = recorder
                        initialized = true
                    } else {
                        Log.e(TAG, "AudioRecord not initialized, attempt $attempt")
                        PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord not initialized (attempt $attempt)")
                        recorder.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord initialization attempt $attempt failed: ${e.message}")
                    PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord init failed (attempt $attempt): ${e.message}")
                }
            }

            if (!initialized) {
                Log.e(TAG, "Failed to initialize AudioRecord after $attempt attempts")
                PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord failed to initialize after $attempt attempts — check Microphone permission for AriaCast")
                _state.value = CastState.ERROR
                return@launch
            }

            val videoEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_VIDEO_ENABLED, false)

            if (destinations.any { it.platform == "DLNA" || it.platform == "Google Cast" }) {
                startDlnaHttpServer()
                startArtworkServer()
            }

            launch {
                try {
                    audioRecord?.startRecording()
                    PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord capture started")
                    val audioBuffer = ByteBuffer.allocate(FRAME_SIZE)
                    var lastFrameWasSilent: Boolean? = null
                    while (isActive) {
                        val readResult = audioRecord?.read(audioBuffer.array(), 0, FRAME_SIZE) ?: 0
                        if (readResult == FRAME_SIZE) {
                            val frame = audioBuffer.array().copyOf()
                            val isSilent = frame.all { it == 0.toByte() }
                            if (isSilent != lastFrameWasSilent) {
                                lastFrameWasSilent = isSilent
                                PacketLogger.log(
                                    PacketDirection.IN, PacketType.AUDIO,
                                    if (isSilent) "Capture is silent — no audio signal detected (check DRM/FLAG_SECURE source, or that something is actually playing)"
                                    else "Capture is producing a real audio signal"
                                )
                            }
                            _audioBufferFlow.emit(frame)
                        } else if (readResult < 0) {
                            Log.e(TAG, "AudioRecord read error: $readResult")
                            PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord read error: $readResult")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio recording loop failed", e)
                    PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "Audio recording loop crashed: ${e.message}")
                }
            }

            destinations.forEach { dest ->
                when (dest.platform) {
                    "DLNA" -> launch { startDlnaSession(dest) }
                    "Google Cast" -> launch { startGoogleCastSession(dest) }
                    "AirPlay" -> launch { startAirPlaySession(dest, airplay2 = false) }
                    "AirPlay2" -> launch { startAirPlaySession(dest, airplay2 = true) }
                    "Snapcast" -> launch { startSnapcastSession(dest) }
                    else -> {
                        launch { startControlSession(dest) }
                        if (videoEnabled && destinations.size == 1) { 
                            launch { startVideoSession(dest) }
                        }
                        launch { startAudioSession(dest) }
                        launch { startStatsSession(dest) }
                    }
                }
            }
            
            startMetadataRefreshLoop()
            _metadata.value?.let { sendMetadata(it) }
        }
    }

    private var dlnaHttpServerJob: Job? = null
    private var artworkServerJob: Job? = null
    private var streamServerSocket: ServerSocket? = null
    private var artworkServerSocket: ServerSocket? = null

    private fun startDlnaHttpServer() {
        dlnaHttpServerJob?.cancel()
        try { streamServerSocket?.close() } catch (e: Exception) {}
        dlnaHttpServerJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(java.net.InetSocketAddress(STREAM_PORT))
                streamServerSocket = serverSocket
                Log.i(TAG, "Stream server started on port $STREAM_PORT")
                while (isActive) {
                    val clientSocket = try { serverSocket.accept() } catch (e: Exception) { null } ?: continue
                    launch {
                        try {
                            clientSocket.setTcpNoDelay(true)
                            clientSocket.soTimeout = 30000
                            val input = clientSocket.getInputStream().bufferedReader()
                            val output = clientSocket.getOutputStream()
                            
                            val requestLine = input.readLine() ?: return@launch
                            Log.d(TAG, "Stream request: $requestLine")
                            
                            val headers = mutableMapOf<String, String>()
                            var line: String?
                            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                                Log.d(TAG, "  Header: $line")
                                val parts = line!!.split(": ", limit = 2)
                                if (parts.size == 2) {
                                    headers[parts[0].lowercase()] = parts[1]
                                }
                            }

                            val ua = headers["user-agent"] ?: ""
                            val isApple = ua.contains("Apple", ignoreCase = true) || 
                                           ua.contains("Darwin", ignoreCase = true) ||
                                           ua.contains("libmpv", ignoreCase = true)
                            
                            val contentType = if (isApple) "audio/x-wav" else "audio/wav"

                            val responseHeaders = StringBuilder()
                            responseHeaders.append("HTTP/1.1 200 OK\r\n")
                            responseHeaders.append("Content-Type: $contentType\r\n")
                            responseHeaders.append("Server: AirTunes/220.68\r\n")
                            responseHeaders.append("Connection: close\r\n")
                            responseHeaders.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                            responseHeaders.append("Pragma: no-cache\r\n")
                            responseHeaders.append("Expires: 0\r\n")
                            responseHeaders.append("ICY-NAME: AriaCast Stream\r\n")
                            responseHeaders.append("ICY-METADATA: 0\r\n")
                            responseHeaders.append("Access-Control-Allow-Origin: *\r\n")
                            responseHeaders.append("\r\n")
                            
                            output.write(responseHeaders.toString().toByteArray())

                            if (requestLine.startsWith("HEAD")) {
                                output.flush()
                                return@launch
                            }

                            // Send WAV header once at the start of the 200 OK response
                            val header = ByteBuffer.allocate(44).apply {
                                order(ByteOrder.LITTLE_ENDIAN)
                                put("RIFF".toByteArray())
                                putInt(-1) 
                                put("WAVE".toByteArray())
                                put("fmt ".toByteArray())
                                putInt(16)
                                putShort(1.toShort())
                                putShort(2.toShort()) 
                                putInt(SAMPLE_RATE)
                                putInt(SAMPLE_RATE * 4) 
                                putShort(4.toShort()) 
                                putShort(16.toShort()) 
                                put("data".toByteArray())
                                putInt(-1)
                            }
                            output.write(header.array())

                            audioBufferFlow.collect { buffer ->
                                try {
                                    output.write(buffer)
                                    output.flush()
                                } catch (e: Exception) {
                                    throw CancellationException("Client disconnected")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Streaming session ended: ${e.message}")
                        } finally {
                            try { clientSocket.close() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming Server error: ${e.message}")
            } finally {
                Log.i(TAG, "Stream server stopped")
            }
        }
    }

    private suspend fun startDlnaSession(dest: CastDestination) {
        val (controlUrl, _) = getDlnaControlUrls(dest.extra)
        if (controlUrl == null) return
        val myIp = getLocalIpAddress() ?: return
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"

        try {
            try {
                val stopBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                        <s:Body>
                            <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                <InstanceID>0</InstanceID>
                            </u:Stop>
                        </s:Body>
                    </s:Envelope>""".trimIndent()
                client.post(controlUrl) {
                    header("SoapAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                    contentType(ContentType.parse("text/xml; charset=utf-8"))
                    setBody(stopBody)
                }
            } catch (e: Exception) {}

            val metadataXml = createDidlMetadata(streamUrl, _metadata.value)

            val setUriBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>${escapeXml(streamUrl)}</CurrentURI><CurrentURIMetaData>${escapeXml(metadataXml)}</CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>"""

            client.post(controlUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(setUriBody)
            }

            val playBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>"""

            client.post(controlUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(playBody)
            }

            _state.value = CastState.CASTING
            updateNotification()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DLNA session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    /**
     * Google Cast via the CastV2 protocol: launches the Default Media Receiver and
     * has it play the live WAV stream from our HTTP server. Volume and play/pause
     * changes made on the device (Home app, speaker buttons) are reflected back.
     */
    private suspend fun startGoogleCastSession(dest: CastDestination) = withContext(Dispatchers.IO) {
        val myIp = getLocalIpAddress() ?: run {
            _state.value = CastState.ERROR
            return@withContext
        }
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"
        var failures = 0
        while (isActive) {
            val ended = CompletableDeferred<String>()
            val client = CastClient(dest.host, if (dest.port > 0) dest.port else 8009, object : CastListener {
                override fun onVolume(level: Double, muted: Boolean) {
                    reflectReceiverVolume(if (muted) 0 else (level * 100).toInt())
                }
                override fun onRemoteCommand(command: String) = dispatchRemoteCommand(command)
                override fun onDisconnected(reason: String) { ended.complete(reason) }
            })
            try {
                _state.value = CastState.CONNECTING
                client.connect()
                client.launch()
                client.load(streamUrl, "audio/wav", castMetadata(_metadata.value))
                PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "Google Cast: ${dest.name} playing $streamUrl")
            } catch (e: CancellationException) {
                client.close()
                throw e
            } catch (e: Exception) {
                client.close()
                Log.e(TAG, "Google Cast session failed for ${dest.name}: ${e.message}")
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Google Cast ${dest.name} failed: ${e.message ?: e.javaClass.simpleName}")
                if (++failures >= 3) {
                    _state.value = CastState.ERROR
                    return@withContext
                }
                delay(RECONNECT_INITIAL_BACKOFF * 2.0.pow(failures.toDouble()).toLong())
                continue
            }
            failures = 0
            castClients[dest.host] = client
            lastSentMetadata.remove(dest.host)
            _state.value = CastState.CASTING
            updateNotification()
            try {
                val reason = ended.await()
                Log.w(TAG, "Google Cast ${dest.name} ended: $reason")
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Google Cast ${dest.name} ended: $reason")
                if (reason == "receiver app stopped" || reason == "receiver app closed") {
                    // The user switched the device to something else - don't fight them for it.
                    _state.value = CastState.ERROR
                    return@withContext
                }
            } finally {
                castClients.remove(dest.host, client)
                withContext(NonCancellable) {
                    client.stop()
                    client.close()
                }
            }
            delay(RECONNECT_INITIAL_BACKOFF * 2)
        }
    }

    private fun castMetadata(metadata: TrackMetadata?): CastMetadata {
        val myIp = getLocalIpAddress()
        val art = currentArtworkBytes
        val imageUrl = when {
            art != null && myIp != null -> "http://$myIp:$ARTWORK_PORT/artwork.jpg?v=${art.contentHashCode().toUInt()}"
            else -> metadata?.artworkUrl?.takeIf { it.startsWith("http") }
        }
        return CastMetadata(metadata?.title, metadata?.artist, metadata?.album, imageUrl)
    }

    /**
     * AirPlay 1 (RAOP) and AirPlay 2 sessions: connects (prompting for a PIN or
     * password when the receiver asks), then streams the capture resampled to
     * 44.1 kHz until stopped, reconnecting if the receiver drops us.
     */
    private suspend fun startAirPlaySession(dest: CastDestination, airplay2: Boolean) = withContext(Dispatchers.IO) {
        val txt = ExtraFields.parse(dest.extra)
        var failures = 0
        while (isActive) {
            val ended = CompletableDeferred<String>()
            val listener = object : AirPlaySessionListener {
                override fun onRemoteVolume(db: Double) = reflectReceiverVolume(AirPlayVolume.dbToPercent(db))
                override fun onRemoteCommand(command: String) = dispatchRemoteCommand(command)
                override fun onDisconnected(reason: String) { ended.complete(reason) }
            }
            _state.value = CastState.CONNECTING
            val streamer = try {
                connectAirPlay(dest, airplay2, txt, listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val label = if (airplay2) "AirPlay 2" else "AirPlay"
                Log.e(TAG, "$label session failed for ${dest.name}: ${e.message}")
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "$label ${dest.name} failed: ${e.message ?: e.javaClass.simpleName}")
                if (e is AirPlayBusyException || e is AirPlayAuthRequiredException || ++failures >= 3) {
                    _state.value = CastState.ERROR
                    return@withContext
                }
                delay(RECONNECT_INITIAL_BACKOFF * 2.0.pow(failures.toDouble()).toLong())
                continue
            }
            if (streamer == null) { // the user dismissed the PIN/password prompt
                _state.value = CastState.ERROR
                return@withContext
            }
            failures = 0
            streamer.extraDelayMs = _activeDestinations.value.find { it.host == dest.host }?.delayMs ?: dest.delayMs
            airplayStreamers[dest.host] = streamer
            lastSentMetadata.remove(dest.host)
            lastSentArtwork.remove(dest.host)
            PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "${dest.name}: streaming (${streamer.javaClass.simpleName})")
            _state.value = CastState.CASTING
            updateNotification()
            _metadata.value?.let { pushAirPlayMetadata(dest.host, streamer, it) }

            val resampler = AudioResampler(channels = 2) // 48 kHz capture -> 44.1 kHz AirPlay
            try {
                val reason = coroutineScope {
                    val feeder = launch {
                        audioBufferFlow.collect { chunk -> streamer.writePcm(resampler.resample(chunk)) }
                    }
                    val r = ended.await()
                    feeder.cancel()
                    r
                }
                Log.w(TAG, "AirPlay ${dest.name} dropped: $reason (${streamer.statsSummary()})")
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "${dest.name} dropped the session: $reason - reconnecting")
            } finally {
                airplayStreamers.remove(dest.host, streamer)
                withContext(NonCancellable) {
                    streamer.teardown()
                    streamer.close()
                }
            }
            delay(RECONNECT_INITIAL_BACKOFF * 2)
        }
    }

    /** Returns a streaming session, or null if the user cancelled a PIN/password prompt. */
    private suspend fun connectAirPlay(
        dest: CastDestination,
        airplay2: Boolean,
        txt: Map<String, String>,
        listener: AirPlaySessionListener
    ): AirPlayStreamer? {
        val volumeDb = AirPlayVolume.percentToDb(receiverVolumePercent())
        val identity = airPlayIdentity
        var password = securePreferences.getString(KEY_AIRPLAY_PASSWORD_PREFIX + dest.host, null)
        var pin: String? = null
        val pairingKey = KEY_HAP_PAIRING_PREFIX + (txt["id"] ?: dest.host)

        repeat(4) {
            try {
                if (airplay2) {
                    val client = AirPlay2Client(
                        host = dest.host,
                        port = if (dest.port > 0) dest.port else 7000,
                        identity = identity,
                        txt = txt,
                        credentials = HapCredentials.deserialize(securePreferences.getString(pairingKey, null)),
                        pin = pin,
                        password = password,
                        initialVolumeDb = volumeDb,
                        ptpMaster = ptpMaster,
                        listener = listener,
                        onPaired = { creds -> securePreferences.edit().putString(pairingKey, creds.serialize()).apply() },
                        onCredentialsRejected = { securePreferences.edit().remove(pairingKey).apply() }
                    )
                    try {
                        client.connect()
                        return client
                    } catch (e: AirPlayTimingUnsupportedException) {
                        // e.g. shairport-sync in AirPlay 2 mode, which needs PTP (privileged ports
                        // on Android). Every AirPlay 2 receiver also speaks AirPlay 1 on the same port.
                        Log.i(TAG, "${dest.name}: ${e.message}; falling back to AirPlay 1")
                        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "${dest.name}: AirPlay 2 timing unsupported, using AirPlay 1")
                        val raop = RaopClient(
                            host = dest.host,
                            port = if (dest.port > 0) dest.port else 7000,
                            identity = identity,
                            txt = mapOf("md" to "0,1,2", "et" to "0,1", "cn" to "0,1"),
                            password = password,
                            initialVolumeDb = volumeDb,
                            listener = listener
                        )
                        raop.connect()
                        return raop
                    }
                } else {
                    val client = RaopClient(
                        host = dest.host,
                        port = dest.port,
                        identity = identity,
                        txt = txt,
                        receiverMac = txt["id"],
                        password = password,
                        legacyPaired = securePreferences.getBoolean(KEY_RAOP_LEGACY_PREFIX + dest.host, false),
                        initialVolumeDb = volumeDb,
                        listener = listener
                    )
                    client.connect()
                    return client
                }
            } catch (e: AirPlayAuthRequiredException) {
                Log.i(TAG, "${dest.name} needs ${e.kind}: ${e.message}")
                if (e.kind == AirPlayAuthRequiredException.Kind.PIN && !airplay2) {
                    securePreferences.edit().remove(KEY_RAOP_LEGACY_PREFIX + dest.host).apply()
                    try {
                        RaopClient(dest.host, dest.port, identity).requestPinDisplay()
                    } catch (e2: Exception) {
                        Log.w(TAG, "pair-pin-start failed: ${e2.message}")
                    }
                }
                val secret = requestSecret(dest, e.kind) ?: return null
                when (e.kind) {
                    AirPlayAuthRequiredException.Kind.PASSWORD -> {
                        password = secret
                        securePreferences.edit().putString(KEY_AIRPLAY_PASSWORD_PREFIX + dest.host, secret).apply()
                    }
                    AirPlayAuthRequiredException.Kind.PIN -> if (airplay2) {
                        pin = secret
                    } else {
                        RaopClient(dest.host, dest.port, identity).pairWithPin(secret)
                        securePreferences.edit().putBoolean(KEY_RAOP_LEGACY_PREFIX + dest.host, true).apply()
                    }
                }
            }
        }
        throw AirPlayAuthRequiredException(AirPlayAuthRequiredException.Kind.PASSWORD, dest.host, "authentication kept failing")
    }

    /** Shows the PIN/password dialog and waits for the user's answer (null = cancelled). */
    private suspend fun requestSecret(dest: CastDestination, kind: AirPlayAuthRequiredException.Kind): String? =
        pairingPromptMutex.withLock {
            val deferred = CompletableDeferred<String?>()
            activePinDeferred = deferred
            _pairingPinRequest.value = PairingRequest(dest.host, dest.name, kind)
            try {
                withTimeoutOrNull(PAIRING_PROMPT_TIMEOUT_MS) { deferred.await() }?.takeIf { it.isNotEmpty() }
            } finally {
                _pairingPinRequest.value = null
                activePinDeferred = null
            }
        }

    private fun pushAirPlayMetadata(host: String, streamer: AirPlayStreamer, metadata: TrackMetadata) {
        val key = "${metadata.title}|${metadata.artist}|${metadata.album}"
        val art = currentArtworkBytes
        val artHash = art?.contentHashCode() ?: 0
        if (lastSentMetadata[host] != key) {
            lastSentMetadata[host] = key
            streamer.setMetadata(metadata.title, metadata.artist, metadata.album, metadata.durationMs)
            lastSentArtwork.remove(host) // receivers tie artwork to the current item
        }
        if (art != null && art.isNotEmpty() && lastSentArtwork[host] != artHash) {
            lastSentArtwork[host] = artHash
            streamer.setArtwork(art, if (art.size > 3 && art[0] == 0x89.toByte() && art[1] == 'P'.code.toByte()) "image/png" else "image/jpeg")
        }
        val duration = metadata.durationMs
        val position = metadata.positionMs
        if (duration != null && position != null && duration > 0) streamer.setProgress(position, duration)
    }

    /** Maps remote-control command names (DACP, AirPlay 2 events, Cast) to the phone's player. */
    private fun dispatchRemoteCommand(command: String) {
        val cmd = when (command.lowercase(Locale.US)) {
            "play", "playresume" -> MediaCommand.PLAY
            "pause" -> MediaCommand.PAUSE
            "playpause", "toggleplaypause", "toggle" -> MediaCommand.TOGGLE
            "nextitem", "nexttrack", "next" -> MediaCommand.NEXT
            "previtem", "previoustrack", "previous" -> MediaCommand.PREVIOUS
            "stop" -> MediaCommand.STOP
            else -> MediaCommand.entries.find { it.name.equals(command, ignoreCase = true) }
        } ?: return
        PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, "Remote command from receiver: $command")
        scope.launch { _controlCommands.emit(cmd) }
    }

    private fun startDacpServer() {
        if (dacpServer != null) return
        val server = DacpServer(activeRemote) { cmd ->
            when (cmd) {
                DacpServer.Command.Play -> dispatchRemoteCommand("play")
                DacpServer.Command.Pause -> dispatchRemoteCommand("pause")
                DacpServer.Command.PlayPause -> dispatchRemoteCommand("playpause")
                DacpServer.Command.Stop -> dispatchRemoteCommand("stop")
                DacpServer.Command.Next -> dispatchRemoteCommand("nextitem")
                DacpServer.Command.Previous -> dispatchRemoteCommand("previtem")
                DacpServer.Command.VolumeUp -> adjustReceiverVolumeSteps(+1)
                DacpServer.Command.VolumeDown -> adjustReceiverVolumeSteps(-1)
                DacpServer.Command.MuteToggle -> toggleReceiverMute()
                is DacpServer.Command.SetVolume -> {
                    val percent = if (cmd.isPercent) cmd.value.toInt() else AirPlayVolume.dbToPercent(cmd.value)
                    setReceiverVolumePercent(percent.coerceIn(0, 100))
                }
                DacpServer.Command.ShuffleToggle, DacpServer.Command.RepeatAdvance -> {}
            }
        }
        val port = try { server.start() } catch (e: Exception) {
            Log.w(TAG, "DACP server failed to start: ${e.message}")
            return
        }
        dacpServer = server
        try {
            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = DacpServer.serviceName(dacpId)
                serviceType = DacpServer.SERVICE_TYPE
                setPort(port)
                DacpServer.txtRecords(dacpId).forEach { (k, v) -> setAttribute(k, v) }
            }
            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) { Log.d(TAG, "DACP advertised as ${si.serviceName}") }
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "DACP registration failed: $errorCode") }
                override fun onServiceUnregistered(si: NsdServiceInfo) {}
                override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            dacpRegistration = registration
        } catch (e: Exception) {
            Log.w(TAG, "DACP mDNS registration failed: ${e.message}")
        }
    }

    private fun stopDacpServer() {
        dacpRegistration?.let { reg ->
            try { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(reg) } catch (_: Exception) {}
        }
        dacpRegistration = null
        dacpServer?.stop()
        dacpServer = null
    }

    private suspend fun startSnapcastSession(dest: CastDestination) = withContext(Dispatchers.IO) {
        var streamId: String? = null
        var rpcSocket: Socket? = null
        var audioSocket: Socket? = null
        var previousGroupStreams: Map<String, String>? = null // groupId → previous streamId
        var rpcOut: java.io.OutputStream? = null
        var rpcIn: java.io.BufferedReader? = null
        var rpcSeq = 0

        fun nextId() = ++rpcSeq

        suspend fun rpcCall(method: String, params: JSONObject? = null): JSONObject {
            val reqId = nextId()
            val req = JSONObject().apply {
                put("id", reqId)
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            }.toString() + "\n"
            rpcOut!!.write(req.toByteArray())
            rpcOut!!.flush()
            // Read lines until we get a response matching our request ID
            // (snapserver sends async notifications on the same socket)
            while (true) {
                val line = rpcIn!!.readLine() ?: throw Exception("No response from snapserver")
                val json = JSONObject(line)
                // Match by request ID. Snapserver sends:
                // - responses with "id" matching our request
                // - error responses sometimes with "id": null
                // - async notifications with "method" (no "id")
                val respId = json.opt("id")
                if (respId is Int && respId == reqId) {
                    return json
                }
                // Accept null-id error responses as ours (they follow our request)
                if (json.has("error") && (respId == null || respId == JSONObject.NULL)) {
                    return json
                }
                Log.d(TAG, "Snapcast: skipped message: ${line.take(100)}")
            }
        }

        try {
            _state.value = CastState.CONNECTING

            // 1. Connect to Snapcast JSON-RPC control API (discovered via _snapcast-ctrl._tcp)
            rpcSocket = Socket()
            rpcSocket.connect(java.net.InetSocketAddress(dest.host, dest.port), 5000)
            rpcSocket.tcpNoDelay = true
            rpcOut = rpcSocket.getOutputStream()
            rpcIn = rpcSocket.getInputStream().bufferedReader()

            // 2. Create a TCP source stream
            // Request an explicit port (starting at 4953) so the URI includes it.
            // Retry with incremented port and name suffix on collision.
            val baseName = android.os.Build.MODEL ?: "Android"
            var audioPort = 4953
            var nameAttempt = 0
            var portAttempt = 0

            while (true) {
                val name = if (nameAttempt == 0) baseName else "$baseName (${nameAttempt + 1})"
                val port = audioPort + portAttempt

                val resp = rpcCall("Stream.AddStream", JSONObject().apply {
                    put("streamUri", "tcp://0.0.0.0:$port?name=$name&sampleformat=48000:16:2&mode=server")
                })

                if (!resp.has("error")) {
                    streamId = resp.getJSONObject("result").optString("id", name)
                    audioPort = port
                    break
                }

                val errData = resp.optJSONObject("error")?.optString("data", "") ?: ""
                if (errData.contains("already exists", ignoreCase = true)) {
                    nameAttempt++
                    Log.d(TAG, "Snapcast: name '$name' taken, trying next name")
                } else if (errData.contains("Address already in use", ignoreCase = true) || errData.contains("bind", ignoreCase = true)) {
                    portAttempt++
                    Log.d(TAG, "Snapcast: port $port in use, trying next port")
                } else {
                    nameAttempt++
                    portAttempt++
                    Log.d(TAG, "Snapcast: '$name' on port $port failed ($errData), trying next")
                }

                if (nameAttempt >= 5 || portAttempt >= 5) {
                    throw Exception("Snapcast: failed to create stream: $errData")
                }
            }

            Log.d(TAG, "Snapcast stream '$streamId' on port $audioPort")

            // Give snapserver time to bind the TCP source port
            delay(500)

            // 4. Connect to the TCP audio source port
            audioSocket = Socket()
            audioSocket.connect(java.net.InetSocketAddress(dest.host, audioPort), 5000)
            audioSocket.tcpNoDelay = true
            val audioOut = audioSocket.getOutputStream()

            _state.value = CastState.CASTING
            updateNotification()
            Log.d(TAG, "Snapcast streaming to ${dest.host}:$audioPort (stream: $streamId)")

            // 4. Save current group→stream assignments, then assign all groups to our stream
            try {
                val status = rpcCall("Server.GetStatus")
                val groups = status.getJSONObject("result")
                    .getJSONObject("server")
                    .getJSONArray("groups")

                val saved = mutableMapOf<String, String>()
                for (i in 0 until groups.length()) {
                    val group = groups.getJSONObject(i)
                    val groupId = group.getString("id")
                    saved[groupId] = group.getString("stream_id")

                    rpcCall("Group.SetStream", JSONObject().apply {
                        put("id", groupId)
                        put("stream_id", streamId)
                    })
                }
                previousGroupStreams = saved
                Log.d(TAG, "Snapcast: assigned ${groups.length()} group(s), saved previous assignments")
            } catch (e: Exception) {
                Log.w(TAG, "Snapcast: failed to assign groups: ${e.message}")
            }

            // 5. Stream audio
            try {
                audioBufferFlow.collect { buffer ->
                    audioOut.write(buffer)
                }
            } finally {
                try { audioSocket.close() } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Snapcast session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        } finally {
            // 6. Restore previous group assignments and remove our stream
            if (rpcSocket != null && !rpcSocket.isClosed) {
                try {
                    // Restore groups to their previous streams
                    previousGroupStreams?.forEach { (groupId, prevStreamId) ->
                        rpcCall("Group.SetStream", JSONObject().apply {
                            put("id", groupId)
                            put("stream_id", prevStreamId)
                        })
                    }
                    if (previousGroupStreams != null) {
                        Log.d(TAG, "Snapcast: restored previous group assignments")
                    }

                    // Remove our dynamic stream
                    if (streamId != null) {
                        rpcCall("Stream.RemoveStream", JSONObject().apply { put("id", streamId) })
                        Log.d(TAG, "Snapcast stream '$streamId' removed")
                    }
                } catch (_: Exception) {}
            }
            try { rpcSocket?.close() } catch (_: Exception) {}
            try { audioSocket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun startAudioSession(dest: CastDestination) {
        var sentFramesCount = 0L
        var reconnectAttempts = 0

        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = dest.host, port = dest.port, path = "/audio") audioSocket@{
                    reconnectAttempts = 0
                    PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "Audio socket connected to ${dest.name} (${dest.host}:${dest.port})")

                    if (dest.platform != "AriaCast") {
                        val handshakeFrame = try {
                            withTimeout(3000L) { incoming.receive() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio handshake timed out for ${dest.host}: ${e.message}")
                            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Handshake timed out for ${dest.name}: ${e.message}")
                            return@audioSocket
                        }

                        if (handshakeFrame !is Frame.Text) {
                            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Unexpected handshake frame type from ${dest.name}")
                            return@audioSocket
                        }
                    }

                    _state.value = CastState.CASTING
                    updateNotification()

                    val delayQueue = java.util.LinkedList<ByteArray>()
                    
                    audioBufferFlow.collect { buffer: ByteArray ->
                        delayQueue.add(buffer)
                        
                        val currentDelay = _activeDestinations.value.find { it.host == dest.host }?.delayMs ?: 0
                        val requiredFrames = currentDelay / 20 
                        
                        var sendCount = 0
                        while (delayQueue.size > requiredFrames && sendCount < 2) {
                            val frame = delayQueue.removeFirst()
                            sendAudioFrame(this@audioSocket, frame)
                            sentFramesCount++
                            sendCount++
                            
                            if (delayQueue.size == requiredFrames) break
                        }
                        
                        val now = System.currentTimeMillis()
                        if (now - lastBitrateTime >= 1000) {
                            val delta = sentFramesCount - lastSentFramesForBitrate
                            val bps = delta * FRAME_SIZE * 8
                            currentBitrateString = "${bps / 1000} kbps"
                            lastBitrateTime = now
                            lastSentFramesForBitrate = sentFramesCount
                        }
                        _stats.value = _stats.value.copy(sentFrames = sentFramesCount)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Audio WS connection to ${dest.host}:${dest.port} failed: ${e.message}", e)
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Audio connection to ${dest.name} failed: ${e.message ?: e.javaClass.simpleName}")
                reconnectAttempts++
                val delayTime = (RECONNECT_INITIAL_BACKOFF * 2.0.pow(reconnectAttempts.toDouble().coerceAtMost(5.0))).toLong()
                delay(delayTime)
            }
        }
    }

    private fun sendAudioFrame(session: DefaultClientWebSocketSession, buffer: ByteArray) {
        val sent = session.outgoing.trySendBlocking(Frame.Binary(true, buffer))
        if (sent.isSuccess) {
            PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Audio frame sent", buffer.size)
        } else {
            _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + 1)
            Log.w(TAG, "Audio frame send failed (dropped)")
            PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Audio frame dropped (send failed)", buffer.size)
        }
    }

    private suspend fun startVideoSession(dest: CastDestination) {
        var reconnectAttempts = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = dest.host, port = dest.port, path = "/video") videoSocket@{
                    setupVideoCodec()
                    val bufferInfo = MediaCodec.BufferInfo()
                    while (isActive) {
                        val outputBufferId = videoCodec?.dequeueOutputBuffer(bufferInfo, 10000L) ?: -1
                        if (outputBufferId >= 0) {
                            val outputBuffer = videoCodec?.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null) {
                                val outData = ByteArray(bufferInfo.size)
                                outputBuffer.get(outData)
                                outgoing.trySendBlocking(Frame.Binary(true, outData))
                            }
                            videoCodec?.releaseOutputBuffer(outputBufferId, false)
                        }
                    }
                    releaseVideoCodec()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                releaseVideoCodec()
                reconnectAttempts++
                delay((RECONNECT_INITIAL_BACKOFF * 2.0.pow(reconnectAttempts.toDouble().coerceAtMost(5.0))).toLong())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupVideoCodec() {
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                metrics.densityDpi = windowMetrics.bounds.width()
            } else {
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = videoCodec?.createInputSurface()
            videoCodec?.start()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AriaCastVideo", VIDEO_WIDTH, VIDEO_HEIGHT, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        } catch (e: Exception) {}
    }

    private fun releaseVideoCodec() {
        virtualDisplay?.release()
        virtualDisplay = null
        try { videoCodec?.stop(); videoCodec?.release() } catch (e: Exception) {}
        videoCodec = null
    }

    /** Volume up/down from plugins/UI: one step on every receiver. */
    fun sendVolumeCommand(direction: String) {
        adjustReceiverVolumeSteps(if (direction == "up") +1 else -1)
    }

    /** Current receiver volume 0-100 (shared by every receiver in the session). */
    private fun receiverVolumePercent(): Int =
        sharedPreferences.getInt(KEY_RECEIVER_VOLUME, DEFAULT_RECEIVER_VOLUME).coerceIn(0, 100)

    private fun stepsToPercent(steps: Int) = (steps * 100 + VOLUME_STEPS / 2) / VOLUME_STEPS
    private fun percentToSteps(percent: Int) = (percent * VOLUME_STEPS + 50) / 100

    private fun adjustReceiverVolumeSteps(delta: Int) {
        val steps = (percentToSteps(receiverVolumePercent()) + delta).coerceIn(0, VOLUME_STEPS)
        volumeProvider?.currentVolume = steps
        setReceiverVolumePercent(stepsToPercent(steps))
    }

    private fun toggleReceiverMute() {
        val current = receiverVolumePercent()
        if (current > 0) {
            volumeBeforeMute = current
            setReceiverVolumePercent(0)
        } else {
            setReceiverVolumePercent(if (volumeBeforeMute > 0) volumeBeforeMute else DEFAULT_RECEIVER_VOLUME)
        }
        volumeProvider?.currentVolume = percentToSteps(receiverVolumePercent())
    }

    /**
     * Sets every receiver to [percent] (0 = mute). AirPlay maps it linearly onto
     * -30..0 dB like iTunes does, Google Cast takes 0..1, DLNA and AriaCast 0..100.
     */
    private fun setReceiverVolumePercent(percent: Int) {
        val p = percent.coerceIn(0, 100)
        sharedPreferences.edit().putInt(KEY_RECEIVER_VOLUME, p).apply()
        val dB = AirPlayVolume.percentToDb(p)
        Log.d(TAG, "Setting receiver volume to $p% ($dB dB)")
        scope.launch {
            val airplay = synchronized(airplayStreamers) { airplayStreamers.values.toList() }
            airplay.forEach { try { it.setVolume(dB) } catch (_: Exception) {} }

            val casts = synchronized(castClients) { castClients.values.toList() }
            casts.forEach { c ->
                try {
                    if (p == 0) c.setMuted(true) else { if (c.muted) c.setMuted(false); c.setVolume(p / 100.0) }
                } catch (_: Exception) {}
            }

            val controlSnapshot = synchronized(controlSessions) { controlSessions.values.toList() }
            controlSnapshot.forEach { session ->
                try {
                    session.send(Frame.Text(JSONObject().apply {
                        put("command", "volume_set")
                        put("level", p)
                    }.toString()))
                } catch (_: Exception) {}
            }

            _activeDestinations.value.filter { it.platform == "DLNA" }.forEach { dest ->
                val (_, rcUrl) = getDlnaControlUrls(dest.extra)
                if (rcUrl != null) setDlnaVolume(rcUrl, p)
            }
        }
    }

    /**
     * A receiver changed its own volume (its remote, buttons, Home app): mirror it
     * on the phone's volume slider without echoing it back to that receiver.
     */
    private fun reflectReceiverVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        if (p == receiverVolumePercent()) return
        sharedPreferences.edit().putInt(KEY_RECEIVER_VOLUME, p).apply()
        volumeProvider?.currentVolume = percentToSteps(p)
        PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, "Receiver volume changed to $p%")
    }

    private suspend fun setDlnaVolume(rcUrl: String, percent: Int) {
        try {
            val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$percent</DesiredVolume></u:SetVolume></s:Body></s:Envelope>"""
            client.post(rcUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(body)
            }
        } catch (_: Exception) {}
    }

    fun setDelay(host: String, delayMs: Int) {
        val current = _activeDestinations.value.toMutableList()
        val index = current.indexOfFirst { it.host == host }
        if (index != -1) {
            current[index] = current[index].copy(delayMs = delayMs)
            _activeDestinations.value = current
        }
        airplayStreamers[host]?.extraDelayMs = delayMs
    }

    fun getActiveDestinationsJson(): String {
        val array = JSONArray()
        _activeDestinations.value.forEach { dest ->
            array.put(JSONObject().apply {
                put("name", dest.name)
                put("host", dest.host)
                put("delayMs", dest.delayMs)
            })
        }
        return array.toString()
    }

    fun setArtwork(bytes: ByteArray?) {
        currentArtworkBytes = bytes
    }

    fun sendMetadata(metadata: TrackMetadata) {
        metadataChannel.trySend(metadata)
    }

    fun submitPairingPin(host: String, pin: String) {
        activePinDeferred?.complete(pin)
        activePinDeferred = null
    }

    fun resetPairingPinRequest() {
        activePinDeferred?.complete(null)
        activePinDeferred = null
        _pairingPinRequest.value = null
    }

    private suspend fun performMetadataUpdate(metadata: TrackMetadata) {
        val destinations = _activeDestinations.value
        if (destinations.isEmpty()) return

        var finalMetadata = metadata
        if (currentArtworkBytes != null) {
            val myIp = getLocalIpAddress()
            if (myIp != null) {
                finalMetadata = metadata.copy(artworkUrl = "http://$myIp:$ARTWORK_PORT/artwork.jpg")
            }
        }

        val companionHost = companionApiHost
        if (companionHost != null) {
            // AriaCompanion: the ESP32 board owns the connection to the
            // receiver, so relay metadata through its REST API instead of
            // posting straight to the receiver like the branches below do.
            try {
                client.post {
                    url {
                        protocol = URLProtocol.HTTP
                        host = companionHost
                        port = companionApiPort
                        path("api", "metadata")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("data" to finalMetadata))
                    timeout { requestTimeoutMillis = 5000 }
                }
                PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata relayed via AriaCompanion ($companionHost:$companionApiPort)")
            } catch (e: Exception) {
                Log.w(TAG, "Companion: metadata relay failed: ${e.message}")
                PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata relay to AriaCompanion failed: ${e.message ?: e.javaClass.simpleName}")
            }
            return
        }

        val metadataKey = "${finalMetadata.title}-${finalMetadata.artist}"

        // Paused on the phone: AirPlay receivers would otherwise keep playing the ~2 s
        // they have buffered. FLUSH drops it so the pause takes effect immediately.
        val wasPlaying = lastPlayingState
        lastPlayingState = metadata.isPlaying
        if (wasPlaying == true && !metadata.isPlaying) {
            val streamers = synchronized(airplayStreamers) { airplayStreamers.values.toList() }
            streamers.forEach { try { it.flush() } catch (_: Exception) {} }
        }

        destinations.forEach { dest ->
            try {
                if (dest.platform != "DLNA" && dest.platform != "Google Cast" && dest.platform != "AirPlay" && dest.platform != "AirPlay2") {
                    client.post {
                        url {
                            protocol = URLProtocol.HTTP
                            host = dest.host
                            port = dest.port
                            path("metadata")
                        }
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("data" to finalMetadata))
                        timeout { requestTimeoutMillis = 5000 }
                    }
                    PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata sent to ${dest.name}")
                } else if (dest.platform == "AirPlay" || dest.platform == "AirPlay2") {
                    airplayStreamers[dest.host]?.let { streamer ->
                        pushAirPlayMetadata(dest.host, streamer, finalMetadata)
                        PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata sent to ${dest.name} (AirPlay)")
                    }
                } else if (dest.platform == "Google Cast") {
                    val cast = castClients[dest.host]
                    if (cast != null && lastSentMetadata[dest.host] != metadataKey + currentArtworkBytes?.contentHashCode()) {
                        lastSentMetadata[dest.host] = metadataKey + currentArtworkBytes?.contentHashCode()
                        cast.updateMetadata(castMetadata(metadata))
                        PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata sent to ${dest.name} (Google Cast)")
                    }
                } else if (dest.platform == "DLNA") {
                    if (lastSentMetadata[dest.host] != metadataKey) {
                        updateDlnaMetadata(dest, finalMetadata)
                        lastSentMetadata[dest.host] = metadataKey
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun updateDlnaMetadata(dest: CastDestination, metadata: TrackMetadata) {
        val (avUrl, _) = getDlnaControlUrls(dest.extra)
        if (avUrl == null) return

        val myIp = getLocalIpAddress() ?: return
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"
        val metadataXml = createDidlMetadata(streamUrl, metadata)

        try {
            val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>${escapeXml(streamUrl)}</CurrentURI><CurrentURIMetaData>${escapeXml(metadataXml)}</CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>"""
            client.post(avUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update DLNA metadata: ${e.message}")
        }
    }

    private fun getDlnaControlUrls(extra: String?): Pair<String?, String?> {
        if (extra == null) return null to null
        if (extra.startsWith("http")) return extra to null

        val fields = ExtraFields.parse(extra)
        return fields["av_control"] to fields["rc_control"]
    }

    private fun createDidlMetadata(streamUrl: String, metadata: TrackMetadata?): String {
        val title = metadata?.title ?: "AriaCast Live Stream"
        val artist = metadata?.artist ?: "AriaCast"
        val album = metadata?.album ?: ""
        val artwork = metadata?.artworkUrl ?: ""

        val artworkTag = if (artwork.isNotEmpty()) "<upnp:albumArtURI>${escapeXml(artwork)}</upnp:albumArtURI>" else ""
        
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:artist>${escapeXml(artist)}</upnp:artist><upnp:album>${escapeXml(album)}</upnp:album><upnp:class>object.item.audioItem.musicTrack</upnp:class>$artworkTag<res protocolInfo="http-get:*:audio/x-wav:*">${escapeXml(streamUrl)}</res></item></DIDL-Lite>"""
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val list = mutableListOf<InetAddress>()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val name = networkInterface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("tap") || name.contains("docker")) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address) {
                        list.add(address)
                    }
                }
            }
            return list.find { it.hostAddress.startsWith("192.168.") }?.hostAddress
                ?: list.find { it.hostAddress.startsWith("10.") }?.hostAddress
                ?: list.find { it.hostAddress.startsWith("172.") }?.hostAddress
                ?: list.firstOrNull()?.hostAddress
        } catch (e: Exception) {}
        return null
    }

    private suspend fun startControlSession(dest: CastDestination) {
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = dest.host, port = dest.port, path = "/control") {
                    controlSessions[dest.host] = this
                    PacketLogger.log(PacketDirection.OUT, PacketType.CONTROL, "Control socket connected to ${dest.name}")
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val json = JSONObject(text)
                                val action = json.getString("action")
                                val command = MediaCommand.entries.find { it.name.equals(action, ignoreCase = true) }
                                if (command != null) {
                                    _controlCommands.emit(command)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Control WS connection to ${dest.host}:${dest.port} failed: ${e.message}", e)
                PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, "Control connection to ${dest.name} failed: ${e.message ?: e.javaClass.simpleName}")
                controlSessions.remove(dest.host)
                delay(RECONNECT_INITIAL_BACKOFF)
            }
        }
    }

    private suspend fun startStatsSession(dest: CastDestination) {
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = dest.host, port = dest.port, path = "/stats") statsSocket@{
                    PacketLogger.log(PacketDirection.OUT, PacketType.STATS, "Stats socket connected to ${dest.name}")
                    while(isActive) {
                        val frame = withTimeoutOrNull(STATS_TIMEOUT) { incoming.receive() }
                        if (frame is Frame.Text) {
                            val json = JSONObject(frame.readText())
                            _stats.value = _stats.value.copy(
                                bufferedFrames = json.optInt("bufferedFrames"),
                                receivedFrames = json.optInt("receivedFrames")
                            )
                            updateNotification()
                        } else if (frame == null) {
                            return@statsSocket
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Stats WS connection to ${dest.host}:${dest.port} failed: ${e.message}", e)
                PacketLogger.log(PacketDirection.IN, PacketType.STATS, "Stats connection to ${dest.name} failed: ${e.message ?: e.javaClass.simpleName}")
                delay(RECONNECT_INITIAL_BACKOFF)
            }
        }
    }

    fun getStatsJson(): String {
        val s = _stats.value
        return JSONObject().apply {
            put("buffered_level", s.bufferedFrames)
            put("dropped_frames", s.droppedFrames)
            put("received_frames", s.receivedFrames)
            put("sent_frames", s.sentFrames)
            put("bitrate", currentBitrateString)
            put("state", _state.value.name)
            put("destinations_count", _activeDestinations.value.size)
            val streamers = synchronized(airplayStreamers) { airplayStreamers.toMap() }
            if (streamers.isNotEmpty()) {
                put("airplay", JSONObject().apply { streamers.forEach { (host, st) -> put(host, st.statsSummary()) } })
            }
        }.toString()
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Only call stop if it's not us stopping it
            if (mediaProjection != null) {
                stopCasting()
            }
        }
    }

    private fun stopCasting() {
        cleanupSession()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun cleanupSession() {
        val destinations = _activeDestinations.value.toList()
        val teardownJobs = stopRemoteSessions(destinations)
        releaseWakeLock()
        stopVolumeSession()

        // Restore volume only if the user didn't change it during casting
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {}
        mediaProjection = null

        releaseVideoCodec()
        
        dlnaHttpServerJob?.cancel()
        dlnaHttpServerJob = null
        artworkServerJob?.cancel()
        artworkServerJob = null
        
        try { streamServerSocket?.close() } catch (e: Exception) {}
        streamServerSocket = null
        try { artworkServerSocket?.close() } catch (e: Exception) {}
        artworkServerSocket = null

        companionApiHost?.let { host ->
            val port = companionApiPort
            scope.launch(Dispatchers.IO) { clearCompanionReceiverTarget(host, port) }
        }
        companionApiHost = null

        controlSessions.clear()
        // Close the sockets only after giving the TEARDOWN/stop sends launched by
        // stopRemoteSessions() above a bounded window to actually reach the receiver -
        // closing them immediately, as before, meant that async send almost always lost
        // the race and the receiver was left holding the session open until its own
        // timeout. Capture the raw socket/client references now (before this coroutine
        // runs) so a fast reconnect that reuses these maps in the meantime isn't affected.
        val streamersSnapshot = synchronized(airplayStreamers) { airplayStreamers.values.toList() }
        val castSnapshot = synchronized(castClients) { castClients.values.toList() }
        airplayStreamers.clear()
        castClients.clear()
        lastSentMetadata.clear()
        lastSentArtwork.clear()
        stopDacpServer()
        val ptp = ptpMaster
        ptpMaster = null
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(3000) { teardownJobs.joinAll() }
            streamersSnapshot.forEach { try { it.close() } catch (e: Exception) {} }
            castSnapshot.forEach { try { it.close() } catch (e: Exception) {} }
            ptp?.stop()
        }
        _activeDestinations.value = emptyList()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = CastState.OFF
        _stats.value = CastingStats()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AriaCast::Casting")
        }
        wakeLock?.acquire()
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /** Start a MediaSession with a remote VolumeProvider so the phone's
     *  hardware volume buttons control the AirPlay receiver volume. */
    private fun startVolumeSession() {
        val session = MediaSession(this, "AriaCast")

        // Claim media button priority so volume keys route to us, not the music app
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        session.setPlaybackState(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .build())

        session.setCallback(object : MediaSession.Callback() {
            // Empty callback — needed to claim media button routing
        })

        // Receiver volume in VOLUME_STEPS steps (AirPlay: 1 dB per step over -30..0 dB,
        // step 0 = mute), starting from the level used last time.
        val provider = object : VolumeProvider(
            VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, percentToSteps(receiverVolumePercent())
        ) {
            override fun onSetVolumeTo(volume: Int) {
                val v = volume.coerceIn(0, VOLUME_STEPS)
                setCurrentVolume(v)
                setReceiverVolumePercent(stepsToPercent(v))
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction == AudioManager.ADJUST_TOGGLE_MUTE) { toggleReceiverMute(); return }
                val v = (currentVolume + direction).coerceIn(0, VOLUME_STEPS)
                setCurrentVolume(v)
                setReceiverVolumePercent(stepsToPercent(v))
            }
        }
        volumeProvider = provider
        session.setPlaybackToRemote(provider)

        session.isActive = true
        mediaSession = session
        Log.d(TAG, "Volume session started — hardware keys route to receiver")
    }

    private fun stopVolumeSession() {
        volumeProvider = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "Volume session stopped")
    }

    private fun stopRemoteSessions(destinations: List<CastDestination>): List<Job> {
        return destinations.map { dest ->
            scope.launch {
                try {
                    when (dest.platform) {
                        "AirPlay", "AirPlay2" -> airplayStreamers[dest.host]?.teardown()
                        "Google Cast" -> castClients[dest.host]?.stop()
                        "DLNA" -> {
                            val (controlUrl, _) = getDlnaControlUrls(dest.extra)
                            if (controlUrl == null) return@launch
                            val stopBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID></u:Stop></s:Body></s:Envelope>"""
                            client.post(controlUrl) {
                                header("SoapAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                                contentType(ContentType.parse("text/xml; charset=utf-8"))
                                setBody(stopBody)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): android.app.Notification {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val remoteViews = RemoteViews(packageName, R.layout.notification_casting)
        remoteViews.setTextViewText(R.id.notification_title, getString(R.string.app_name))

        val destinations = _activeDestinations.value
        val statusText = when(state.value) {
            CastState.CASTING -> {
                if (destinations.size > 1) "Casting to ${destinations.size} devices"
                else getString(R.string.casting_to, destinations.firstOrNull()?.name ?: "Unknown", "")
            }
            CastState.CONNECTING -> "Connecting..."
            CastState.ERROR -> "Connection Error"
            else -> getString(R.string.not_casting)
        }
        remoteViews.setTextViewText(R.id.notification_text, statusText)

        val stopIntent = Intent(this, AudioCastService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        remoteViews.setOnClickPendingIntent(R.id.notification_stop_button, stopPendingIntent)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setCustomContentView(remoteViews)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "AudioCastService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "AudioCastChannel"
        const val ACTION_START = "com.aria.ariacast.ACTION_START"
        const val ACTION_START_COMPANION = "com.aria.ariacast.ACTION_START_COMPANION"
        const val ACTION_STOP = "com.aria.ariacast.ACTION_STOP"
        const val EXTRA_MEDIA_PROJECTION_TOKEN = "com.aria.ariacast.EXTRA_MEDIA_PROJECTION_TOKEN"
        const val EXTRA_SERVER_HOST = "com.aria.ariacast.EXTRA_SERVER_HOST"
        const val EXTRA_SERVER_PORT = "com.aria.ariacast.EXTRA_SERVER_PORT"
        const val EXTRA_SERVER_NAME = "com.aria.ariacast.EXTRA_SERVER_NAME"
        const val EXTRA_SERVER_PLATFORM = "com.aria.ariacast.EXTRA_SERVER_PLATFORM"
        const val EXTRA_SERVER_EXTRA = "com.aria.ariacast.EXTRA_SERVER_EXTRA"
        const val EXTRA_SERVERS_JSON = "com.aria.ariacast.EXTRA_SERVERS_JSON"

        const val PREFS_NAME = "AriaCastPrefs"
        // AirPlay 2 pairing PINs live in a separate prefs file, excluded from Android
        // backup/device-transfer (see data_extraction_rules.xml / backup_rules.xml) -
        // they shouldn't end up in a cloud backup or a new device's transfer alongside
        // the rest of the app's ordinary settings.
        const val SECURE_PREFS_NAME = "AriaCastSecurePrefs"
        const val KEY_LAST_SERVER_HOST = "last_server_host"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"
        const val KEY_LAST_SERVER_PLATFORM = "last_server_platform"

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 3840
        // REST API port on the AriaCompanion WiFi sender board (renamed from
        // the old raw-TCP COMPANION_STREAM_PORT=7001 now that the board
        // streams straight to a chosen AriaCast Receiver instead of to a
        // bespoke TCP port on the phone).
        const val COMPANION_API_PORT = 8081

        // Secure-prefs keys for AirPlay secrets (excluded from backup, see SECURE_PREFS_NAME).
        const val KEY_AIRPLAY_PASSWORD_PREFIX = "airplay_password_"
        const val KEY_HAP_PAIRING_PREFIX = "airplay2_pairing_"
        const val KEY_RAOP_LEGACY_PREFIX = "airplay_legacy_paired_"
        private const val KEY_PAIRING_SEED = "airplay_pairing_seed"
        private const val KEY_PAIRING_ID = "airplay_pairing_id"
        private const val KEY_RECEIVER_VOLUME = "receiver_volume_percent"
        private const val DEFAULT_RECEIVER_VOLUME = 50
        private const val VOLUME_STEPS = 30
        private const val PAIRING_PROMPT_TIMEOUT_MS = 180_000L
        
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_BITRATE = 3000000 
        const val VIDEO_FPS = 30
        const val VIDEO_IFRAME_INTERVAL = 2

        private const val RECONNECT_INITIAL_BACKOFF = 1000L
        private const val STATS_TIMEOUT = 10000L 
        private const val ARTWORK_PORT = 8090
        private const val STREAM_PORT = 8091
    }
}
