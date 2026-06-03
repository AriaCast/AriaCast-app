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
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
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
    
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    
    private val _activeDestinations = MutableStateFlow<List<CastDestination>>(emptyList())
    val activeDestinations: StateFlow<List<CastDestination>> = _activeDestinations.asStateFlow()
    
    private val controlSessions = mutableMapOf<String, DefaultClientWebSocketSession>()
    private val raopSockets = mutableMapOf<String, Socket>()
    private val raopCSeqs = mutableMapOf<String, Int>()
    private val raopSessions = mutableMapOf<String, String?>()
    private val airplaySessionIds = mutableMapOf<String, String>()
    private val lastSentMetadata = mutableMapOf<String, String>()
    private val unsupportedSetProperty = mutableSetOf<String>()
    
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
            ACTION_START -> {
                cleanupSession()
                
                val mediaProjectionToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_TOKEN, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_TOKEN)
                }

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
                _state.value = CastState.ERROR
                return
            } else {
                throw e
            }
        }

        // Use a small delay and multiple attempts for AudioRecord/MediaProjection initialization
        // This helps if the system hasn't fully released resources from a previous session
        sessionScope.launch {
            var attempt = 0
            var initialized = false
            
            while (attempt < 3 && !initialized && isActive) {
                if (attempt > 0) delay(400)
                attempt++
                
                try {
                    val projection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken)
                    if (projection == null) {
                        Log.e(TAG, "MediaProjection is null, attempt $attempt")
                        continue
                    }
                    mediaProjection = projection
                    projection.registerCallback(mediaProjectionCallback, null)

                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .build()

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
                        recorder.release()
                        projection.stop()
                        mediaProjection = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Initialization attempt $attempt failed: ${e.message}")
                }
            }

            if (!initialized) {
                Log.e(TAG, "Failed to initialize audio capture after $attempt attempts")
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
                    val audioBuffer = ByteBuffer.allocate(FRAME_SIZE)
                    while (isActive) {
                        val readResult = audioRecord?.read(audioBuffer.array(), 0, FRAME_SIZE) ?: 0
                        if (readResult == FRAME_SIZE) {
                            _audioBufferFlow.emit(audioBuffer.array().copyOf())
                        } else if (readResult < 0) {
                            Log.e(TAG, "AudioRecord read error: $readResult")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio recording loop failed", e)
                }
            }

            destinations.forEach { dest ->
                when (dest.platform) {
                    "DLNA" -> launch { startDlnaSession(dest) }
                    "Google Cast" -> launch { startGoogleCastSession(dest) }
                    "AirPlay" -> launch { startAirPlaySession(dest) }
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
            
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    private suspend fun performRaopHandshake(dest: CastDestination, myIp: String) = withContext(Dispatchers.IO) {
        try {
            val targetPort = if (dest.port > 0) {
                if (dest.port == 7000) 5000 else dest.port
            } else 5000
            
            val socket = Socket()
            try {
                socket.connect(java.net.InetSocketAddress(dest.host, targetPort), 5000)
            } catch (e: Exception) {
                if (targetPort == 5000 && (dest.port == 7000 || dest.port == 0)) {
                    socket.connect(java.net.InetSocketAddress(dest.host, 7000), 5000)
                } else throw e
            }
            
            socket.soTimeout = 10000
            socket.tcpNoDelay = true
            raopSockets[dest.host] = socket
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            var cseq = 0
            raopCSeqs[dest.host] = cseq

            val userAgent = "AirPlay/550.10"
            val sessionGuid = UUID.randomUUID().toString()
            val rtpSessionId = (10000000..99999999).random() // Unique SSRC/Session ID for RTP
            
            val sdp = "v=0\r\n" +
                    "o=iTunes $rtpSessionId 0 IN IP4 $myIp\r\n" +
                    "s=iTunes\r\n" +
                    "c=IN IP4 ${dest.host}\r\n" +
                    "t=0 0\r\n" +
                    "m=audio 0 RTP/AVP 96\r\n" +
                    "a=rtpmap:96 L16/44100/2\r\n" +
                    "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
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

            // 2. SETUP
            sendRtspRequest(output, "SETUP", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
            ))
            val setupResp = readRtspResponse(input)
            val session = setupResp.find { it.startsWith("Session:", true) }?.substringAfter(":")?.substringBefore(";")?.trim()
            raopSessions[dest.host] = session

            // 3. RECORD
            sendRtspRequest(output, "RECORD", dest.host, targetPort, cseq++, commonHeaders + mapOf(
                "Session" to (session ?: ""),
                "Range" to "npt=0-",
                "RTP-Info" to "seq=0;rtptime=$LATENCY"
            ))
            readRtspResponse(input)
            
            raopCSeqs[dest.host] = cseq

            _state.value = CastState.CASTING
            updateNotification()

            // Initial metadata
            _metadata.value?.let { updateRaopMetadata(dest.host, it) }

            var sequence = 0
            var timestamp = LATENCY
            
            // Interleaved receiver loop (Timing replies)
            val receiverJob = launch {
                try {
                    while (isActive) {
                        val dollar = input.read()
                        if (dollar == -1) break
                        if (dollar == 0x24) { // '$'
                            val channel = input.read()
                            val length = (input.read() shl 8) or input.read()
                            val data = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val r = input.read(data, read, length - read)
                                if (r == -1) break
                                read += r
                            }
                            
                            // Check for Timing request (0x53) on Channel 1 (RTCP)
                            if (channel == 1 && data.size >= 32) {
                                val type = data[1].toInt() and 0x7F
                                if (type == 0x53) {
                                    // Extract sendtime (T1) from the end of request (offset 24-31)
                                    val reqSendSec = ByteBuffer.wrap(data, 24, 4).int
                                    val reqSendFrac = ByteBuffer.wrap(data, 28, 4).int
                                    
                                    val now = System.currentTimeMillis()
                                    val ntpSec = (now / 1000) + 0x83AA7E80
                                    val ntpFrac = ((now % 1000) * 0x100000000L / 1000).toInt()
                                    
                                    val reply = ByteBuffer.allocate(32).apply {
                                        put(0x80.toByte())
                                        put(0xD3.toByte()) // Timing reply
                                        putShort(7.toShort())
                                        putInt(0) // padding
                                        putInt(reqSendSec) // reftime (T1)
                                        putInt(reqSendFrac)
                                        putInt(ntpSec.toInt()) // recvtime (T2)
                                        putInt(ntpFrac)
                                        putInt(ntpSec.toInt()) // sendtime (T3)
                                        putInt(ntpFrac)
                                    }.array()
                                    
                                    synchronized(output) {
                                        output.write(0x24)
                                        output.write(0x01)
                                        output.write((reply.size shr 8) and 0xFF)
                                        output.write(reply.size and 0xFF)
                                        output.write(reply)
                                        output.flush()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "RAOP receiver loop ended: ${e.message}")
                }
            }

            // Sync task
            val syncJob = launch {
                var isFirstSync = true
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val ntpSec = (now / 1000) + 0x83AA7E80
                    val ntpFrac = ((now % 1000) * 0x100000000L / 1000).toInt()
                    
                    val syncPacket = ByteBuffer.allocate(20).apply {
                        put((if (isFirstSync) 0x90 else 0x80).toByte())
                        put(0xD4.toByte()) // Sync
                        putShort(7.toShort())
                        putInt(timestamp - LATENCY) // now_without_latency
                        putInt(ntpSec.toInt())
                        putInt(ntpFrac)
                        putInt(timestamp) // now
                    }.array()
                    
                    try {
                        synchronized(output) {
                            output.write(0x24) // '$'
                            output.write(0x01) // channel 1
                            output.write((syncPacket.size shr 8) and 0xFF)
                            output.write(syncPacket.size and 0xFF)
                            output.write(syncPacket)
                            output.flush()
                        }
                    } catch (e: Exception) { break }
                    isFirstSync = false
                    delay(1000)
                }
            }

            try {
                var isFirstPacket = true
                audioBufferFlow.collect { buffer ->
                    for (offset in 0 until buffer.size step 1408) {
                        val size = minOf(1408, buffer.size - offset)
                        val chunk = buffer.copyOfRange(offset, offset + size)
                        
                        val bigEndianBuffer = ByteArray(chunk.size)
                        for (i in 0 until chunk.size step 2) {
                            if (i + 1 < chunk.size) {
                                bigEndianBuffer[i] = chunk[i+1]
                                bigEndianBuffer[i+1] = chunk[i]
                            }
                        }

                        val rtpHeader = ByteBuffer.allocate(12).apply {
                            put(0x80.toByte())
                            put((if (isFirstPacket) 0xE0 else 0x60).toByte())
                            putShort(sequence++.toShort())
                            putInt(timestamp)
                            putInt(rtpSessionId) // SSRC MUST match SDP o= line
                        }
                        timestamp += chunk.size / 4
                        isFirstPacket = false

                        val payload = rtpHeader.array() + bigEndianBuffer
                        
                        try {
                            synchronized(output) {
                                output.write(0x24) // '$'
                                output.write(0x00) // channel 0
                                output.write((payload.size shr 8) and 0xFF)
                                output.write(payload.size and 0xFF)
                                output.write(payload)
                                output.flush()
                            }
                        } catch (e: Exception) {
                            throw CancellationException("RAOP stream closed")
                        }
                    }
                }
            } finally {
                syncJob.cancel()
                receiverJob.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RAOP failed for ${dest.host}: ${e.message}")
            raopSockets.remove(dest.host)?.close()
            raopSessions.remove(dest.host)
            raopCSeqs.remove(dest.host)
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
                    
                    val handshakeFrame = try {
                        withTimeout(3000L) { incoming.receive() }
                    } catch (e: Exception) {
                        return@audioSocket
                    }

                    if (handshakeFrame !is Frame.Text) return@audioSocket

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
                reconnectAttempts++
                val delayTime = (RECONNECT_INITIAL_BACKOFF * 2.0.pow(reconnectAttempts.toDouble().coerceAtMost(5.0))).toLong()
                delay(delayTime)
            }
        }
    }

    private fun sendAudioFrame(session: DefaultClientWebSocketSession, buffer: ByteArray) {
        val sent = session.outgoing.trySendBlocking(Frame.Binary(true, buffer))
        if (!sent.isSuccess) {
            _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + 1)
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
            controlSessions.values.forEach { session ->
                try {
                    val command = JSONObject().apply {
                        put("command", "volume")
                        put("direction", direction)
                    }.toString()
                    session.send(Frame.Text(command))
                } catch (e: Exception) {}
            }
            
            raopSockets.forEach { (host, socket) ->
                try {
                    val output = socket.getOutputStream()
                    val cseq = raopCSeqs[host] ?: 1
                    val session = raopSessions[host] ?: ""
                    
                    val volStr = "volume: ${if(direction == "up") -10.0 else -30.0}\r\n"
                    sendRtspRequest(output, "SET_PARAMETER", host, socket.port, cseq, mapOf(
                        "Session" to session,
                        "Content-Type" to "text/parameters",
                        "Content-Length" to volStr.length.toString()
                    ), volStr)
                    raopCSeqs[host] = cseq + 1
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
                    }
                } catch (e: Exception) {}
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
        _pairingPinRequest.value = null
    }

    fun resetPairingPinRequest() {
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
        
        val metadataKey = "${finalMetadata.title}-${finalMetadata.artist}"

        destinations.forEach { dest ->
            try {
                if (dest.platform != "DLNA" && dest.platform != "Google Cast" && dest.platform != "AirPlay") {
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
                } else if (dest.platform == "AirPlay") {
                    updateRaopMetadata(dest.host, finalMetadata)
                    if (dest.port != 5000) updateAirPlay2Metadata(dest.host, finalMetadata)
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
        
        val parts = extra.split(";")
        val av = parts.find { it.startsWith("av_control=") }?.substringAfter("=")
        val rc = parts.find { it.startsWith("rc_control=") }?.substringAfter("=")
        return av to rc
    }

    private fun updateRaopMetadata(host: String, metadata: TrackMetadata) {
        val socket = raopSockets[host] ?: return
        val output = socket.getOutputStream()
        val cseq = raopCSeqs[host] ?: 1
        val session = raopSessions[host] ?: ""

        val dmap = encodeDmapMetadata(metadata)
        if (dmap.isEmpty()) return

        try {
            sendRtspRequest(output, "SET_PARAMETER", host, socket.port, cseq, mapOf(
                "Session" to session,
                "Content-Type" to "application/x-dmap-tagged",
                "Content-Length" to dmap.size.toString()
            ))
            output.write(dmap)
            output.flush()
            raopCSeqs[host] = cseq + 1
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
                controlSessions.remove(dest.host)
                delay(RECONNECT_INITIAL_BACKOFF)
            }
        }
    }

    private suspend fun startStatsSession(dest: CastDestination) {
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = dest.host, port = dest.port, path = "/stats") statsSocket@{
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
        stopRemoteSessions(destinations)
        
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        
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
        
        controlSessions.clear()
        raopSockets.values.forEach { try { it.close() } catch (e: Exception) {} }
        raopSockets.clear()
        raopSessions.clear()
        raopCSeqs.clear()
        _activeDestinations.value = emptyList()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = CastState.OFF
        _stats.value = CastingStats()
    }

    private fun stopRemoteSessions(destinations: List<CastDestination>) {
        destinations.forEach { dest ->
            scope.launch {
                try {
                    when (dest.platform) {
                        "DLNA" -> {
                            val controlUrl = dest.extra ?: return@launch
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
                                    val cseq = raopCSeqs[dest.host] ?: 1
                                    sendRtspRequest(output, "TEARDOWN", dest.host, dest.port, cseq, mapOf(
                                        "Session" to (raopSessions[dest.host] ?: ""),
                                        "User-Agent" to "AirPlay/366.0"
                                    ))
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
        const val ACTION_STOP = "com.aria.ariacast.ACTION_STOP"
        const val EXTRA_MEDIA_PROJECTION_TOKEN = "com.aria.ariacast.EXTRA_MEDIA_PROJECTION_TOKEN"
        const val EXTRA_SERVER_HOST = "com.aria.ariacast.EXTRA_SERVER_HOST"
        const val EXTRA_SERVER_PORT = "com.aria.ariacast.EXTRA_SERVER_PORT"
        const val EXTRA_SERVER_NAME = "com.aria.ariacast.EXTRA_SERVER_NAME"
        const val EXTRA_SERVER_PLATFORM = "com.aria.ariacast.EXTRA_SERVER_PLATFORM"
        const val EXTRA_SERVER_EXTRA = "com.aria.ariacast.EXTRA_SERVER_EXTRA"
        const val EXTRA_SERVERS_JSON = "com.aria.ariacast.EXTRA_SERVERS_JSON"

        const val PREFS_NAME = "AriaCastPrefs"
        const val KEY_LAST_SERVER_HOST = "last_server_host"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"

        const val SAMPLE_RATE = 44100
        const val FRAME_SIZE = 3528
        const val LATENCY = 66150
        
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
