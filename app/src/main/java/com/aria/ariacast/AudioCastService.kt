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
import com.aria.ariacast.airplay2.AirPlay2Client
import com.aria.ariacast.airplay2.NeedsPinException
import com.aria.ariacast.raop.AudioResampler
import com.aria.ariacast.raop.RaopCrypto
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
    // (rather than ConcurrentHashMap) because raopSessions legitimately stores null values.
    private val controlSessions = java.util.Collections.synchronizedMap(mutableMapOf<String, DefaultClientWebSocketSession>())
    private val raopSockets = java.util.Collections.synchronizedMap(mutableMapOf<String, Socket>())
    private val raopCSeqs = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val raopSessions = java.util.Collections.synchronizedMap(mutableMapOf<String, String?>())
    private val airplaySessionIds = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val ap2Clients = java.util.Collections.synchronizedMap(mutableMapOf<String, AirPlay2Client>())
    private val lastSentMetadata = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val unsupportedSetProperty = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    
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

    private val _pairingPinRequest = MutableStateFlow<String?>(null)
    val pairingPinRequest: StateFlow<String?> = _pairingPinRequest.asStateFlow()

    @Volatile private var activePinDeferred: CompletableDeferred<String?>? = null

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
        if (destinations.any { it.platform in listOf("AirPlay", "AirPlay2", "AriaCast", "DLNA") }) {
            startVolumeSession()
        }

        // MediaProjection token is single-use on Android 14+; obtain it once before any retries.
        val projection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken)
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null")
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "MediaProjection permission was not granted")
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

            if (destinations.any { it.platform == "DLNA" || it.platform == "Google Cast" || it.platform == "AirPlay" }) {
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
                    "AirPlay" -> launch { startAirPlaySession(dest) }
                    "AirPlay2" -> launch { startAirPlay2Session(dest) }
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

    private suspend fun startGoogleCastSession(dest: CastDestination) {
        val myIp = getLocalIpAddress() ?: return
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"
        
        try {
            _state.value = CastState.CONNECTING
            
            // Log available apps to help debugging 404s
            try {
                val appsResponse = client.get("http://${dest.host}:8008/apps")
                Log.d(TAG, "Apps list on ${dest.name}: ${appsResponse.bodyAsText()}")
            } catch (e: Exception) {
                Log.d(TAG, "Could not fetch apps list: ${e.message}")
            }

            // Try common App IDs/Names for DIAL
            val appIds = listOf("DefaultMediaPlayer", "YouTube", "ChromeCast", "CC1AD845")
            var launched = false
            val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")

            for (appId in appIds) {
                try {
                    val launchUrl = "http://${dest.host}:8008/apps/$appId"
                    
                    // Try both 'url' and 'v' parameters
                    val payloads = listOf("url=$encodedUrl", "v=$encodedUrl")
                    
                    for (payload in payloads) {
                        val response = client.post(launchUrl) {
                            setBody(payload)
                            contentType(ContentType.Application.FormUrlEncoded)
                            timeout { requestTimeoutMillis = 5000 }
                        }
                        
                        if (response.status.value in 200..299 || response.status.value == 201) {
                            launched = true
                            Log.d(TAG, "Successfully launched Google Cast app: $appId with payload: $payload")
                            break
                        }
                    }
                    if (launched) break
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to launch $appId: ${e.message}")
                }
            }
            
            // Special fallback for own-tone style mirroring if standard fails
            if (!launched) {
                try {
                    val mirroringUrl = "http://${dest.host}:8008/apps/0F5096C8"
                    val resp = client.post(mirroringUrl) {
                        setBody("url=$encodedUrl")
                        contentType(ContentType.Application.FormUrlEncoded)
                    }
                    if (resp.status.value in 200..299) launched = true
                } catch (e: Exception) {}
            }
            
            if (launched) {
                _state.value = CastState.CASTING
                updateNotification()
            } else {
                Log.e(TAG, "Google Cast launch failed for all attempted apps on ${dest.name}")
                _state.value = CastState.ERROR
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Cast session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    private suspend fun startAirPlaySession(dest: CastDestination) {
        val myIp = getLocalIpAddress() ?: return
        try {
            _state.value = CastState.CONNECTING

            // For audio-only casting, RAOP (RTSP) is much more reliable and standard.
            // We force RAOP handshake here.
            performRaopHandshake(dest, myIp)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    private suspend fun startAirPlay2Session(dest: CastDestination) = withContext(Dispatchers.IO) {
        try {
            _state.value = CastState.CONNECTING

            val extra = dest.extra ?: ""
            // TXT pk is a 64-char hex string representing the 32-byte Ed25519 public key
            val pkHex = ExtraFields.get(extra, "pk")
            val txtPk = if (pkHex?.length == 64) {
                try { ByteArray(32) { i -> pkHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                catch (e: Exception) { null }
            } else null

            // Load any PIN previously saved for this device
            var pin: String? = securePreferences.getString("airplay2_pin_${dest.host}", null)
            var clearPinOnFailure = pin != null  // stale saved pin should be wiped if connect() fails

            while (true) {
                val ap2Client = AirPlay2Client(
                    host = dest.host,
                    port = if (dest.port > 0) dest.port else 7000,
                    deviceId = airplayDeviceId,
                    dacpId = dacpId,
                    activeRemote = activeRemote,
                    sampleRate = SAMPLE_RATE,
                    channels = 2,
                    frameSize = AP2_ALAC_FRAME_SIZE,
                    password = pin,
                    txtPk = txtPk
                )

                ap2Client.eventListener = object : AirPlay2Client.EventListener {
                    override fun onRemoteCommand(command: String) {
                        val cmd = MediaCommand.entries.find { it.name.equals(command, ignoreCase = true) }
                        if (cmd != null) scope.launch { _controlCommands.emit(cmd) }
                    }
                }

                val connected = try {
                    ap2Client.connect()
                } catch (e: NeedsPinException) {
                    ap2Client.close()
                    val deferred = CompletableDeferred<String?>()
                    activePinDeferred = deferred
                    _pairingPinRequest.value = e.host
                    pin = deferred.await()
                    _pairingPinRequest.value = null
                    activePinDeferred = null
                    if (pin == null) {
                        _state.value = CastState.ERROR
                        return@withContext
                    }
                    clearPinOnFailure = false
                    continue
                }

                if (!connected) {
                    if (clearPinOnFailure) {
                        // Saved PIN was rejected — clear it and retry (NeedsPinException will prompt the user)
                        securePreferences.edit().remove("airplay2_pin_${dest.host}").apply()
                        pin = null
                        clearPinOnFailure = false
                        ap2Client.close()
                        continue
                    }
                    _state.value = CastState.ERROR
                    ap2Client.close()
                    return@withContext
                }

                // Persist PIN so the user won't be asked again next time
                if (pin != null) {
                    securePreferences.edit().putString("airplay2_pin_${dest.host}", pin).apply()
                }

                _state.value = CastState.CASTING
                updateNotification()

                ap2Clients[dest.host] = ap2Client
                _metadata.value?.let { ap2Client.sendMetadata(it.title, it.artist, it.album, currentArtworkBytes) }

                val alacFrameBytes = AP2_ALAC_FRAME_SIZE * 2 * 2  // 352 samples * 2 ch * 2 bytes
                val buf = ByteArrayOutputStream()

                try {
                    audioBufferFlow.collect { chunk ->
                        buf.write(chunk)
                        while (buf.size() >= alacFrameBytes) {
                            val arr = buf.toByteArray()
                            val frame = arr.copyOfRange(0, alacFrameBytes)
                            buf.reset()
                            if (arr.size > alacFrameBytes) buf.write(arr, alacFrameBytes, arr.size - alacFrameBytes)
                            ap2Client.sendAudioFrame(frame)
                        }
                    }
                } finally {
                    ap2Clients.remove(dest.host)
                    // This runs on cancellation too (e.g. the user hit Stop), where an
                    // ordinary suspend call would immediately throw - NonCancellable lets
                    // the TEARDOWN request actually reach the receiver instead of the
                    // connection just being dropped. teardown() is a blocking call, not a
                    // suspend one, so it isn't preemptible here; it's bounded by the
                    // client's own socket timeout rather than by a wrapping withTimeout.
                    withContext(NonCancellable) {
                        ap2Client.teardown()
                        ap2Client.close()
                    }
                }
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay 2 session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
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

    private suspend fun performRaopHandshake(dest: CastDestination, myIp: String) = withContext(Dispatchers.IO) {
        // UDP sockets for audio, control, and timing
        val audioUdp = DatagramSocket(0)
        val controlUdp = DatagramSocket(0)
        val timingUdp = DatagramSocket(0)

        try {
            val advertisedPort = if (dest.port > 0) dest.port else 5000
            val portsToTry = if (advertisedPort == 7000) listOf(7000, 5000) else listOf(advertisedPort)

            var socket: Socket? = null
            for (port in portsToTry) {
                try {
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress(dest.host, port), 5000)
                    socket = s
                    break
                } catch (e: Exception) {
                    if (port == portsToTry.last()) throw e
                    Log.d(TAG, "RAOP connect to ${dest.host}:$port failed, trying next port")
                }
            }
            socket!!
            val targetPort = socket.port

            socket.soTimeout = 10000
            socket.tcpNoDelay = true
            raopSockets[dest.host] = socket

            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            var cseq = 0
            raopCSeqs[dest.host] = cseq

            val userAgent = "AirPlay/550.10"
            val sessionGuid = UUID.randomUUID().toString()
            val rtpSessionId = (10000000..99999999).random()

            val encryptionTypeBits = ExtraFields.get(dest.extra, "et")?.toIntOrNull() ?: 0
            val wantsEncryption = (encryptionTypeBits and 1) != 0
            var raopCrypto: RaopCrypto? = null
            val cryptoSdp = if (wantsEncryption) {
                try {
                    val aesKey = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val aesIv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val encryptedKey = RaopCrypto.encryptAesKey(aesKey)
                    raopCrypto = RaopCrypto().apply { initAes(aesKey, aesIv) }
                    val keyB64 = android.util.Base64.encodeToString(encryptedKey, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val ivB64 = android.util.Base64.encodeToString(aesIv, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    "a=rsaaeskey:$keyB64\r\na=aesiv:$ivB64\r\n"
                } catch (e: Exception) {
                    Log.w(TAG, "RAOP encryption setup failed for ${dest.host}, falling back to unencrypted: ${e.message}")
                    raopCrypto = null
                    ""
                }
            } else ""

            val sdp = "v=0\r\n" +
                    "o=iTunes $rtpSessionId 0 IN IP4 $myIp\r\n" +
                    "s=iTunes\r\n" +
                    "c=IN IP4 ${dest.host}\r\n" +
                    "t=0 0\r\n" +
                    "m=audio 0 RTP/AVP 96\r\n" +
                    "a=rtpmap:96 L16/44100/2\r\n" +
                    "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
                    cryptoSdp +
                    "a=control:rtp\r\n"

            val commonHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "X-Apple-Session-ID" to sessionGuid,
                "X-Apple-Device-ID" to "0x${airplayDeviceId.replace(":", "")}",
                "Client-Instance" to dacpId.ifEmpty { "0000000000000000" }
            )
            if (dacpId.isNotEmpty()) commonHeaders["DACP-ID"] = dacpId
            if (activeRemote.isNotEmpty()) commonHeaders["Active-Remote"] = activeRemote

            // 1. ANNOUNCE
            sendRtspRequest(output, "ANNOUNCE", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Content-Type" to "application/sdp",
                "Content-Length" to sdp.length.toString()
            ), sdp)
            readRtspResponse(input)

            // 2. SETUP — advertise our UDP ports
            sendRtspRequest(output, "SETUP", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlUdp.localPort};timing_port=${timingUdp.localPort}"
            ))
            val setupResp = readRtspResponse(input)
            val session = setupResp.find { it.startsWith("Session:", true) }?.substringAfter(":")?.substringBefore(";")?.trim()
            raopSessions[dest.host] = session

            // Parse server's UDP ports from SETUP response Transport header
            var serverAudioPort = 0
            var serverControlPort = 0
            var serverTimingPort = 0
            val transportHeader = setupResp.find { it.startsWith("Transport:", true) }?.substringAfter(":") ?: ""
            transportHeader.split(";").forEach { part ->
                when {
                    part.trim().startsWith("server_port=") -> serverAudioPort = part.substringAfter("=").toIntOrNull() ?: 0
                    part.trim().startsWith("control_port=") -> serverControlPort = part.substringAfter("=").toIntOrNull() ?: 0
                    part.trim().startsWith("timing_port=") -> serverTimingPort = part.substringAfter("=").toIntOrNull() ?: 0
                }
            }
            Log.d(TAG, "RAOP SETUP: server audio=$serverAudioPort control=$serverControlPort timing=$serverTimingPort")

            // 3. RECORD
            sendRtspRequest(output, "RECORD", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Session" to (session ?: ""),
                "Range" to "npt=0-",
                "RTP-Info" to "seq=0;rtptime=$LATENCY"
            ))
            readRtspResponse(input)

            // 4. Set initial volume to 0 dB (full) — AirPlay range is -30 (silent) to 0 (max)
            val volBody = "volume: 0.000000\r\n"
            sendRtspRequest(output, "SET_PARAMETER", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Session" to (session ?: ""),
                "Content-Type" to "text/parameters",
                "Content-Length" to volBody.length.toString()
            ), volBody)
            readRtspResponse(input)

            raopCSeqs[dest.host] = cseq

            _state.value = CastState.CASTING
            updateNotification()

            // Initial metadata
            _metadata.value?.let { updateRaopMetadata(dest.host, it) }

            var sequence = 0
            var timestamp = LATENCY
            val serverAddr = InetAddress.getByName(dest.host)
            // Anchor: wall-clock time when timestamp == LATENCY
            val startTimeMs = System.currentTimeMillis()
            val startTimestamp = LATENCY

            // UDP timing thread — receives timing requests from server and replies
            // In Classic AirPlay, the SERVER sends timing requests to the CLIENT
            val timingJob = launch(Dispatchers.IO) {
                try {
                    timingUdp.soTimeout = 5000 // 5s timeout for debugging
                    val buf = ByteArray(256)
                    Log.d(TAG, "RAOP timing thread started, listening on port ${timingUdp.localPort}")
                    while (isActive) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            timingUdp.receive(pkt)
                            val data = pkt.data
                            Log.d(TAG, "RAOP timing: received ${pkt.length} bytes from ${pkt.address}:${pkt.port}, type=0x${String.format("%02X", data[1])}")

                            if (pkt.length >= 32) {
                                val payloadType = data[1].toInt() and 0x7F
                                if (payloadType == 0x52) { // Timing request (PT=82)
                                    val reqSendSec = ByteBuffer.wrap(data, 24, 4).int
                                    val reqSendFrac = ByteBuffer.wrap(data, 28, 4).int

                                    val now = System.currentTimeMillis()
                                    val ntpSec = (now / 1000) + 0x83AA7E80
                                    val ntpFrac = ((now % 1000) * 0x100000000L / 1000).toInt()

                                    val reply = ByteBuffer.allocate(32).apply {
                                        put(0x80.toByte())
                                        put(0xD3.toByte()) // Timing reply (PT=83 | marker)
                                        putShort(7.toShort())
                                        putInt(0) // padding
                                        putInt(reqSendSec) // reference time (T1 from request)
                                        putInt(reqSendFrac)
                                        putInt(ntpSec.toInt()) // receive time (T2)
                                        putInt(ntpFrac)
                                        putInt(ntpSec.toInt()) // send time (T3)
                                        putInt(ntpFrac)
                                    }.array()

                                    val replyPkt = DatagramPacket(reply, reply.size, pkt.address, pkt.port)
                                    timingUdp.send(replyPkt)
                                    Log.d(TAG, "RAOP timing: sent reply to ${pkt.address}:${pkt.port}")
                                }
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            Log.d(TAG, "RAOP timing: no packet received in 5s (port ${timingUdp.localPort})")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.d(TAG, "RAOP timing thread ended: ${e.message}")
                }
            }

            // UDP sync thread — send periodic sync packets to keep the receiver's
            // RTP-to-NTP mapping from drifting. AudioPlaybackCapture delivers audio
            // in bursts, so the audio thread's timestamp can run ahead of wall clock.
            // Without periodic re-anchoring, drift accumulates and audio stops.
            val syncJob = launch(Dispatchers.IO) {
                // Wait for the first NTP timing exchange to complete
                delay(3000)

                var isFirstSync = true
                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    val ntpSec = (nowMs / 1000) + 0x83AA7E80
                    val ntpFrac = ((nowMs % 1000) * 0x100000000L / 1000).toInt()

                    val elapsedMs = nowMs - startTimeMs
                    val rtpNow = startTimestamp + (elapsedMs * 44100 / 1000).toInt()

                    val syncPacket = ByteBuffer.allocate(20).apply {
                        put((if (isFirstSync) 0x90 else 0x80).toByte())
                        put(0xD4.toByte())
                        putShort(7.toShort())
                        putInt(rtpNow - LATENCY)
                        putInt(ntpSec.toInt())
                        putInt(ntpFrac)
                        putInt(rtpNow)
                    }.array()

                    try {
                        val pkt = DatagramPacket(syncPacket, syncPacket.size, serverAddr, serverControlPort)
                        controlUdp.send(pkt)
                    } catch (e: Exception) { break }
                    isFirstSync = false
                    delay(30_000) // re-anchor every 30 seconds
                }
            }

            // Audio send loop — send RTP packets over UDP with ALAC uncompressed framing
            // Accumulate exactly 1408 bytes (352 stereo samples) per ALAC frame
            val FRAME_BYTES = 1408 // 352 samples × 2 channels × 2 bytes
            try {
                var isFirstPacket = true
                val resampler = AudioResampler(channels = 2)
                val accumulator = ByteArrayOutputStream(FRAME_BYTES * 2)

                audioBufferFlow.collect { rawBuffer ->
                    val buffer = resampler.resample(rawBuffer) // 48000 → 44100 Hz
                    accumulator.write(buffer)

                    val accBytes = accumulator.toByteArray()
                    var consumed = 0
                    while (consumed + FRAME_BYTES <= accBytes.size) {
                        val chunk = accBytes.copyOfRange(consumed, consumed + FRAME_BYTES)
                        consumed += FRAME_BYTES

                        var alacFrame = alacEncodeUncompressedRaop(chunk)
                        raopCrypto?.let { alacFrame = it.encryptAudio(alacFrame) }

                        val rtpHeader = ByteBuffer.allocate(12).apply {
                            put(0x80.toByte())
                            put((if (isFirstPacket) 0xE0 else 0x60).toByte())
                            putShort(sequence++.toShort())
                            putInt(timestamp)
                            putInt(rtpSessionId)
                        }
                        timestamp += FRAME_BYTES / 4 // 352 samples
                        isFirstPacket = false

                        val payload = rtpHeader.array() + alacFrame
                        val pkt = DatagramPacket(payload, payload.size, serverAddr, serverAudioPort)
                        audioUdp.send(pkt)
                    }

                    // Keep unconsumed remainder for next round
                    accumulator.reset()
                    if (consumed < accBytes.size) {
                        accumulator.write(accBytes, consumed, accBytes.size - consumed)
                    }
                }
            } finally {
                syncJob.cancel()
                timingJob.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "RAOP failed for ${dest.host}: ${e.message}")
            withContext(NonCancellable) {
                try {
                    val socket = raopSockets[dest.host]
                    val teardownOutput = if (socket != null && !socket.isClosed) socket.getOutputStream() else null
                    if (teardownOutput != null) {
                        synchronized(teardownOutput) {
                            sendRtspRequest(teardownOutput, "TEARDOWN", dest.host, dest.port, raopCSeqs[dest.host] ?: 1, mapOf(
                                "Session" to (raopSessions[dest.host] ?: ""),
                                "User-Agent" to "AirPlay/366.0"
                            ))
                        }
                    }
                } catch (teardownError: Exception) {}
            }
            raopSockets.remove(dest.host)?.close()
            raopSessions.remove(dest.host)
            raopCSeqs.remove(dest.host)
        } finally {
            audioUdp.close()
            controlUdp.close()
            timingUdp.close()
        }
    }

    private fun sendRtspRequest(output: OutputStream, method: String, host: String, port: Int, cseq: Int, headers: Map<String, String>, body: String? = null) {
        val request = StringBuilder()
        request.append("$method rtsp://$host:$port/AriaCast RTSP/1.0\r\n")
        request.append("CSeq: $cseq\r\n")
        headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
        request.append("\r\n")
        if (body != null) request.append(body)
        output.write(request.toString().toByteArray())
        output.flush()
    }

    private fun readLineManual(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            val c = b.toChar()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }

    private fun readRtspResponse(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        var line: String?
        try {
            while (readLineManual(input).also { line = it } != null && line!!.isNotEmpty()) {
                lines.add(line!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading RTSP response: ${e.message}")
        }
        if (lines.isNotEmpty()) {
            val status = lines[0]
            if (!status.contains("200 OK") && !status.contains("100 Continue")) {
                Log.w(TAG, "RTSP Non-OK Response: $status")
            }
        }
        return lines
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

    fun sendVolumeCommand(direction: String) {
        scope.launch {
            // Snapshot under the map's intrinsic lock (required for safe iteration over a
            // synchronizedMap/Set view), then iterate the copy so the blocking network
            // calls below don't hold that lock and stall concurrent map access elsewhere.
            val controlSessionsSnapshot = synchronized(controlSessions) { controlSessions.values.toList() }
            controlSessionsSnapshot.forEach { session ->
                try {
                    val command = JSONObject().apply {
                        put("command", "volume")
                        put("direction", direction)
                    }.toString()
                    session.send(Frame.Text(command))
                } catch (e: Exception) {}
            }

            val raopSocketsSnapshot = synchronized(raopSockets) { raopSockets.toMap() }
            raopSocketsSnapshot.forEach { (host, socket) ->
                try {
                    val output = socket.getOutputStream()
                    val session = raopSessions[host] ?: ""
                    
                    val volStr = "volume: ${if(direction == "up") -10.0 else -30.0}\r\n"
                    synchronized(output) {
                        val cseq = raopCSeqs[host] ?: 1
                        sendRtspRequest(output, "SET_PARAMETER", host, socket.port, cseq, mapOf(
                            "Session" to session,
                            "Content-Type" to "text/parameters",
                            "Content-Length" to volStr.length.toString()
                        ), volStr)
                        raopCSeqs[host] = cseq + 1
                    }
                } catch (e: Exception) {}
            }

            _activeDestinations.value.forEach { dest ->
                try {
                    when (dest.platform) {
                        "DLNA" -> {
                            val (_, rcUrl) = getDlnaControlUrls(dest.extra)
                            if (rcUrl != null) adjustDlnaVolume(rcUrl, direction)
                        }
                        "Google Cast" -> {
                            // DIAL protocol used for Google Cast here does not support volume control.
                            // This would require implementing the full CastV2 protocol (port 8009).
                        }
                        "AirPlay2" -> {
                            ap2Clients[dest.host]?.setVolume(if (direction == "up") -10.0 else -30.0)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    /** Send an absolute volume in dB to all active receivers.
     *  AirPlay range: -30.0 (silent) to 0.0 (max). */
    private fun sendVolumeDb(dB: Double) {
        Log.d(TAG, "Setting receiver volume to $dB dB")
        scope.launch {
            // AirPlay 1 (RAOP)
            val raopSnapshot = synchronized(raopSockets) { raopSockets.toMap() }
            raopSnapshot.forEach { (host, socket) ->
                try {
                    val output = socket.getOutputStream()
                    val session = raopSessions[host] ?: ""
                    val volStr = "volume: ${String.format(Locale.US, "%.6f", dB)}\r\n"
                    synchronized(output) {
                        val cseq = raopCSeqs[host] ?: 1
                        sendRtspRequest(output, "SET_PARAMETER", host, socket.port, cseq, mapOf(
                            "Session" to session,
                            "Content-Type" to "text/parameters",
                            "Content-Length" to volStr.length.toString()
                        ), volStr)
                        raopCSeqs[host] = cseq + 1
                    }
                } catch (_: Exception) {}
            }

            // AirPlay 2
            val ap2Snapshot = synchronized(ap2Clients) { ap2Clients.toMap() }
            ap2Snapshot.forEach { (_, client) ->
                try { client.setVolume(dB) } catch (_: Exception) {}
            }

            // AriaCast native
            val controlSnapshot = synchronized(controlSessions) { controlSessions.values.toList() }
            controlSnapshot.forEach { session ->
                try {
                    session.send(Frame.Text(JSONObject().apply {
                        put("command", "volume_set")
                        put("level", ((dB + 30) / 30 * 100).toInt().coerceIn(0, 100))
                    }.toString()))
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun adjustDlnaVolume(rcUrl: String, direction: String) {
        try {
            val getVolBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume></s:Body></s:Envelope>"""
            val resp = client.post(rcUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(getVolBody)
            }
            val volText = resp.bodyAsText()
            val currentVol = volText.substringAfter("<CurrentVolume>", "").substringBefore("</CurrentVolume>").toIntOrNull() ?: 50
            val newVol = if (direction == "up") (currentVol + 5).coerceAtMost(100) else (currentVol - 5).coerceAtLeast(0)
            
            val setVolBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$newVol</DesiredVolume></u:SetVolume></s:Body></s:Envelope>"""
            client.post(rcUrl) {
                header("SoapAction", "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
                contentType(ContentType.parse("text/xml; charset=utf-8"))
                setBody(setVolBody)
            }
        } catch (e: Exception) {}
    }

    fun setDelay(host: String, delayMs: Int) {
        val current = _activeDestinations.value.toMutableList()
        val index = current.indexOfFirst { it.host == host }
        if (index != -1) {
            current[index] = current[index].copy(delayMs = delayMs)
            _activeDestinations.value = current
        }
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
                } else if (dest.platform == "AirPlay") {
                    updateRaopMetadata(dest.host, finalMetadata)
                    if (dest.port != 5000) updateAirPlay2Metadata(dest.host, finalMetadata)
                } else if (dest.platform == "AirPlay2") {
                    val ap2 = ap2Clients[dest.host]
                    if (ap2 != null) {
                        ap2.sendMetadata(finalMetadata.title, finalMetadata.artist, finalMetadata.album, currentArtworkBytes)
                        val duration = finalMetadata.durationMs
                        val position = finalMetadata.positionMs
                        if (duration != null && position != null) {
                            ap2.sendProgress(position, duration)
                        }
                        PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata sent to ${dest.name} (AirPlay 2)")
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

    private fun updateRaopMetadata(host: String, metadata: TrackMetadata) {
        val socket = raopSockets[host] ?: return
        val output = socket.getOutputStream()
        val session = raopSessions[host] ?: ""

        val dmap = encodeDmapMetadata(metadata)
        if (dmap.isEmpty()) return

        try {
            synchronized(output) {
                val cseq = raopCSeqs[host] ?: 1
                sendRtspRequest(output, "SET_PARAMETER", host, socket.port, cseq, mapOf(
                    "Session" to session,
                    "Content-Type" to "application/x-dmap-tagged",
                    "Content-Length" to dmap.size.toString()
                ))
                output.write(dmap)
                output.flush()
                raopCSeqs[host] = cseq + 1
            }
        } catch (e: Exception) {}
    }

    private suspend fun updateAirPlay2Metadata(host: String, metadata: TrackMetadata) {
        if (unsupportedSetProperty.contains(host)) return
        val sessionId = airplaySessionIds[host] ?: return
        val targetPort = 7000
        
        // Use DMAP-tagged metadata as seen in pyatv
        val dmapData = encodeDmapMetadata(metadata)
        val mlitContainer = ByteArrayOutputStream().apply {
            write("mlit".toByteArray())
            write(ByteBuffer.allocate(4).putInt(dmapData.size).array())
            write(dmapData)
        }.toByteArray()

        try {
            val response = client.post("http://$host:$targetPort/setProperty") {
                header("X-Apple-Session-ID", sessionId)
                header("Content-Type", "application/x-dmap-tagged")
                header("User-Agent", "AirPlay/550.10")
                setBody(mlitContainer)
            }
            if (response.status.value == 404 || response.status.value == 405) {
                // Fallback to legacy plist metadata if DMAP fails
                val xmlBody = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Metadata</key>
                        <dict>
                            <key>title</key>
                            <string>${escapeXml(metadata.title ?: "AriaCast")}</string>
                            <key>artist</key>
                            <string>${escapeXml(metadata.artist ?: "AriaCast")}</string>
                        </dict>
                    </dict>
                    </plist>
                """.trimIndent()
                client.put("http://$host:$targetPort/setProperty") {
                    header("X-Apple-Session-ID", sessionId)
                    header("Content-Type", "application/x-apple-binary-plist")
                    setBody(xmlBody)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay 2 metadata update failed: ${e.message}")
        }
    }

    private fun encodeDmapMetadata(metadata: TrackMetadata): ByteArray {
        val out = ByteArrayOutputStream()
        
        fun writeTag(tag: String, value: String?) {
            if (value == null) return
            val valBytes = value.toByteArray(Charsets.UTF_8)
            out.write(tag.toByteArray())
            val len = ByteBuffer.allocate(4).putInt(valBytes.size).array()
            out.write(len)
            out.write(valBytes)
        }

        writeTag("minm", metadata.title)
        writeTag("asar", metadata.artist)
        writeTag("asal", metadata.album)

        return out.toByteArray()
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
        val raopSocketsSnapshot = synchronized(raopSockets) { raopSockets.values.toList() }
        val ap2ClientsSnapshot = synchronized(ap2Clients) { ap2Clients.values.toList() }
        raopSockets.clear()
        raopSessions.clear()
        raopCSeqs.clear()
        ap2Clients.clear()
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(2000) { teardownJobs.joinAll() }
            raopSocketsSnapshot.forEach { try { it.close() } catch (e: Exception) {} }
            ap2ClientsSnapshot.forEach { try { it.close() } catch (e: Exception) {} }
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

        // AirPlay volume: -30 dB (silent) to 0 dB (max), 30 steps of 1 dB
        val maxSteps = 30
        session.setPlaybackToRemote(object : VolumeProvider(
            VOLUME_CONTROL_ABSOLUTE, maxSteps, maxSteps
        ) {
            override fun onSetVolumeTo(volume: Int) {
                setCurrentVolume(volume)
                val dB = volume - maxSteps
                sendVolumeDb(dB.toDouble())
            }

            override fun onAdjustVolume(direction: Int) {
                val newVol = (currentVolume + direction).coerceIn(0, maxSteps)
                setCurrentVolume(newVol)
                val dB = newVol - maxSteps
                sendVolumeDb(dB.toDouble())
            }
        })

        session.isActive = true
        mediaSession = session
        Log.d(TAG, "Volume session started — hardware keys route to receiver")
    }

    private fun stopVolumeSession() {
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
                        "AirPlay2" -> {
                            ap2Clients[dest.host]?.teardown()
                        }
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
                        "Google Cast" -> {
                            client.delete("http://${dest.host}:8008/apps/DefaultMediaPlayer")
                        }
                        "AirPlay" -> {
                            if (dest.port == 5000 || dest.name.contains("@")) {
                                val socket = raopSockets[dest.host]
                                val output = socket?.getOutputStream()
                                if (output != null) {
                                    synchronized(output) {
                                        val cseq = raopCSeqs[dest.host] ?: 1
                                        sendRtspRequest(output, "TEARDOWN", dest.host, dest.port, cseq, mapOf(
                                            "Session" to (raopSessions[dest.host] ?: ""),
                                            "User-Agent" to "AirPlay/366.0"
                                        ))
                                    }
                                }
                            } else {
                                val sessionId = airplaySessionIds[dest.host]
                                if (sessionId != null) {
                                    client.post("http://${dest.host}:${if(dest.port > 0) dest.port else 7000}/stop") {
                                        header("X-Apple-Session-ID", sessionId)
                                        header("X-Apple-Device-ID", "0x${airplayDeviceId.replace(":", "").lowercase()}")
                                        header("User-Agent", "AirPlay/366.0")
                                    }
                                }
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
        const val LATENCY = 66150
        // REST API port on the AriaCompanion WiFi sender board (renamed from
        // the old raw-TCP COMPANION_STREAM_PORT=7001 now that the board
        // streams straight to a chosen AriaCast Receiver instead of to a
        // bespoke TCP port on the phone).
        const val COMPANION_API_PORT = 8081
        private const val AP2_ALAC_FRAME_SIZE = 352  // ALAC frame size in samples for AirPlay 2
        
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
    /** Encode little-endian PCM into an ALAC uncompressed frame.
     *  Writes the 23-bit ALAC header, byte-swaps each stereo sample pair
     *  to big-endian, and appends the 3-bit end tag. */
    private fun alacEncodeUncompressedRaop(pcm: ByteArray): ByteArray {
        val out = ByteArray(3 + pcm.size + 1) // header + samples + trailer padding
        var p = 0
        var bpos = 0

        fun writeBits(v: Int, blen: Int) {
            val lb = 8 - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and 0xFF
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) { p++; bpos = 0 } else bpos += blen
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and 0xFF)).toByte()
                p++
                out[p] = ((v shl (8 + rb)) and 0xFF).toByte()
                bpos = -rb
            }
        }

        // ALAC uncompressed frame header: 3 bits tag(1) + 4 bits unused + 8 bits unused
        //                                 + 4 bits unused + 1 bit "has size" + 2 bits unused + 1 bit "not compressed"
        writeBits(1, 3); writeBits(0, 4); writeBits(0, 8)
        writeBits(0, 4); writeBits(0, 1); writeBits(0, 2); writeBits(1, 1)

        // Byte-swap little-endian stereo samples to big-endian
        var i = 0
        while (i < pcm.size) {
            writeBits(pcm[i + 1].toInt() and 0xFF, 8)  // L high
            writeBits(pcm[i + 0].toInt() and 0xFF, 8)  // L low
            writeBits(pcm[i + 3].toInt() and 0xFF, 8)  // R high
            writeBits(pcm[i + 2].toInt() and 0xFF, 8)  // R low
            i += 4
        }
        // End tag
        writeBits(7, 3)
        return out
    }

}
