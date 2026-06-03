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
import com.aria.ariacast.airplay2.AirPlay2Client
import com.aria.ariacast.airplay2.NeedsPinException
import com.aria.ariacast.raop.RaopClient

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
    private var mediaProjectionToken: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    
    private val _activeDestinations = MutableStateFlow<List<CastDestination>>(emptyList())
    val activeDestinations: StateFlow<List<CastDestination>> = _activeDestinations.asStateFlow()

    private val _pairingPinRequest = MutableStateFlow<String?>(null)
    val pairingPinRequest: StateFlow<String?> = _pairingPinRequest.asStateFlow()
    
    private val controlSessions = mutableMapOf<String, DefaultClientWebSocketSession>()
    private val raopClients = mutableMapOf<String, RaopClient>()
    private val airPlay2Clients = mutableMapOf<String, AirPlay2Client>()
    private val airPlay2VolumeDb = mutableMapOf<String, Double>()
    
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

    private var currentSampleRate = SAMPLE_RATE
    private var currentFrameSizeBytes = FRAME_SIZE
    private var currentFrameDurationMs = 20.0

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
        scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(ARTWORK_PORT)
                while (isActive) {
                    val clientSocket = try { serverSocket.accept() } catch (e: Exception) { null } ?: continue
                    launch {
                        try {
                            clientSocket.getInputStream().bufferedReader().readLine()
                            val output = clientSocket.getOutputStream()
                            val bytes = currentArtworkBytes
                            if (bytes != null) {
                                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                                output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
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
                    this.mediaProjectionToken = mediaProjectionToken
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

        val hasAirPlay = destinations.any { it.platform == "AirPlay" }
        val hasAirPlay2 = destinations.any { it.platform == "AirPlay2" }
        val hasNonAirPlay = destinations.any { it.platform != "AirPlay" && it.platform != "AirPlay2" }
        if ((hasAirPlay || hasAirPlay2) && hasNonAirPlay) {
            Log.e(TAG, "AirPlay cannot be mixed with other targets")
            _state.value = CastState.ERROR
            return
        }

        if (hasAirPlay && destinations.size > 1) {
            Log.e(TAG, "AirPlay multi-room is not supported")
            _state.value = CastState.ERROR
            return
        }

        if (hasAirPlay || hasAirPlay2) {
            currentSampleRate = RAOP_SAMPLE_RATE
            currentFrameSizeBytes = RAOP_FRAME_BYTES
        } else {
            currentSampleRate = SAMPLE_RATE
            currentFrameSizeBytes = FRAME_SIZE
        }
        currentFrameDurationMs = (currentFrameSizeBytes / 4.0) / currentSampleRate * 1000.0

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

        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken)
        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        try {
            val minBufSize = AudioRecord.getMinBufferSize(currentSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = (currentFrameSizeBytes * 4).coerceAtLeast(minBufSize) 
            
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(currentSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized.")
                _state.value = CastState.ERROR
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            _state.value = CastState.ERROR
            return
        }

        val videoEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_VIDEO_ENABLED, false)

        if (destinations.any { it.platform == "DLNA" || it.platform == "Google Cast" }) {
            startDlnaHttpServer()
        }

        sessionScope.launch {
            launch { 
                try {
                    audioRecord?.startRecording()
                    val audioBuffer = ByteBuffer.allocate(currentFrameSizeBytes)
                    while (isActive) {
                        val readResult = audioRecord?.read(audioBuffer.array(), 0, currentFrameSizeBytes) ?: 0
                        if (readResult == currentFrameSizeBytes) {
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
                    "AirPlay2" -> launch { startAirPlay2Session(dest) }
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
        }
        
        sessionScope.startMetadataRefreshLoop()
        _metadata.value?.let { sendMetadata(it) }
    }

    private var dlnaHttpServerJob: Job? = null
    private fun startDlnaHttpServer() {
        dlnaHttpServerJob?.cancel()
        dlnaHttpServerJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(STREAM_PORT)
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
                            val isHead = requestLine.startsWith("HEAD")
                            
                            // Basic header parsing to consume the request
                            var line: String?
                            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                                // consume headers
                            }

                            val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: audio/x-wav\r\n" +
                                    "Server: AriaCast/1.0\r\n" +
                                    "Connection: close\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "transferMode.dlna.org: Streaming\r\n" +
                                    "contentFeatures.dlna.org: DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                                    "ICY-NAME: AriaCast Stream\r\n" +
                                    "\r\n"
                            output.write(responseHeaders.toByteArray())

                            if (isHead) {
                                output.flush()
                                return@launch
                            }

                            val header = ByteBuffer.allocate(44).apply {
                                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                put("RIFF".toByteArray())
                                putInt(-1) 
                                put("WAVE".toByteArray())
                                put("fmt ".toByteArray())
                                putInt(16)
                                putShort(1) 
                                putShort(2) 
                                putInt(currentSampleRate)
                                putInt(currentSampleRate * 4) 
                                putShort(4) 
                                putShort(16) 
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
        val controlUrl = dest.extra ?: return
        val myIp = getLocalIpAddress() ?: return
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"

        try {
            try {
                val stopBody = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                        <s:Body>
                            <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                <InstanceID>0</InstanceID>
                            </u:Stop>
                        </s:Body>
                    </s:Envelope>
                """.trimIndent()
                client.post(controlUrl) {
                    header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                    contentType(ContentType.Text.Xml)
                    setBody(stopBody)
                }
            } catch (e: Exception) {}

            val metadata = """
                <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                    <item id="0" parentID="-1" restricted="1">
                        <dc:title>AriaCast Live Stream</dc:title>
                        <upnp:artist>AriaCast</upnp:artist>
                        <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                        <res protocolInfo="http-get:*:audio/wav:*">${streamUrl.replace("&", "&amp;")}</res>
                    </item>
                </DIDL-Lite>
            """.trimIndent().replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

            val setUriBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <CurrentURI>$streamUrl</CurrentURI>
                            <CurrentURIMetaData>$metadata</CurrentURIMetaData>
                        </u:SetAVTransportURI>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            client.post(controlUrl) {
                header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                contentType(ContentType.Text.Xml)
                setBody(setUriBody)
            }

            val playBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <Speed>1</Speed>
                        </u:Play>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            client.post(controlUrl) {
                header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                contentType(ContentType.Text.Xml)
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
            val launchUrl = "http://${dest.host}:8008/apps/DefaultMediaPlayer"
            val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
            
            client.post(launchUrl) {
                setBody("url=$encodedUrl")
                contentType(ContentType.Application.FormUrlEncoded)
            }
            
            _state.value = CastState.CASTING
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Google Cast session failed for ${dest.name}: ${e.message}")
        }
    }

    private suspend fun startAirPlaySession(dest: CastDestination) {
        val targetPort = if (dest.port > 0) dest.port else 5000

        try {
            val raop = RaopClient(
                host = dest.host,
                port = targetPort,
                deviceId = airplayDeviceId,
                dacpId = dacpId,
                activeRemote = activeRemote,
                sampleRate = currentSampleRate,
                channels = 2,
                frameSize = RAOP_FRAME_SAMPLES,
                sharedSecret = null
            )
            if (!raop.connect()) {
                _state.value = CastState.ERROR
                return
            }
            raopClients[dest.host] = raop
            _state.value = CastState.CASTING
            updateNotification()

            audioBufferFlow.collect { buffer ->
                raop.sendAudioFrame(buffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    private fun parsePkFromExtra(extra: String?): ByteArray? {
        if (extra == null) return null
        val parts = extra.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("pk=")) {
                val b64 = trimmed.substring(3)
                return try {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode pk from extra", e)
                    null
                }
            }
        }
        return null
    }

    private suspend fun startAirPlay2Session(dest: CastDestination) {
        val targetPort = if (dest.port > 0) dest.port else 7000
        val savedPin = sharedPreferences.getString("airplay2_pin_${dest.host}", null)
        try {
            val ap2 = AirPlay2Client(
                host = dest.host,
                port = targetPort,
                deviceId = airplayDeviceId,
                dacpId = dacpId,
                activeRemote = activeRemote,
                sampleRate = currentSampleRate,
                channels = 2,
                frameSize = RAOP_FRAME_SAMPLES,
                password = savedPin,
                txtPk = parsePkFromExtra(dest.extra)
            )
            if (!ap2.connect()) {
                _state.value = CastState.ERROR
                return
            }
            airPlay2Clients[dest.host] = ap2
            airPlay2VolumeDb[dest.host] = 0.0
            _state.value = CastState.CASTING
            updateNotification()

            audioBufferFlow.collect { buffer ->
                ap2.sendAudioFrame(buffer)
            }
        } catch (e: NeedsPinException) {
            Log.i(TAG, "AirPlay 2 needs PIN for ${dest.host}")
            _pairingPinRequest.value = dest.host
            _state.value = CastState.OFF
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay 2 session failed for ${dest.name}: ${e.message}")
            _state.value = CastState.ERROR
        }
    }

    fun submitPairingPin(host: String, pin: String) {
        sharedPreferences.edit().putString("airplay2_pin_$host", pin).apply()
        _pairingPinRequest.value = null
        
        val currentDestinations = _activeDestinations.value
        val token = mediaProjectionToken
        if (token != null && currentDestinations.isNotEmpty()) {
            startCasting(token, currentDestinations)
        }
    }

    fun resetPairingPinRequest() {
        _pairingPinRequest.value = null
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
                        val requiredFrames = (currentDelay / currentFrameDurationMs).toInt()
                        
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
                            val bps = delta * currentFrameSizeBytes * 8
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
            
                    raopClients.forEach { (_, client) ->
                        try {
                            val db = if (direction == "up") -10.0f else -30.0f
                            client.setVolumeDb(db)
                        } catch (e: Exception) {}
                    }
                    airPlay2Clients.forEach { (host, client) ->
                        try {
                            val current = airPlay2VolumeDb[host] ?: 0.0
                            val next = if (direction == "up") (current + 3.0).coerceAtMost(0.0)
                                       else (current - 3.0).coerceAtLeast(-30.0)
                            airPlay2VolumeDb[host] = next
                            client.setVolume(next)
                        } catch (e: Exception) {}
                    }
                }
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
                    raopClients[dest.host]?.sendMetadata(finalMetadata.title, finalMetadata.artist, finalMetadata.album)
                } else if (dest.platform == "AirPlay2") {
                    airPlay2Clients[dest.host]?.sendMetadata(
                        finalMetadata.title, finalMetadata.artist, finalMetadata.album,
                        currentArtworkBytes
                    )
                    val pos = finalMetadata.positionMs
                    val dur = finalMetadata.durationMs
                    if (pos != null && dur != null && dur > 0) {
                        airPlay2Clients[dest.host]?.sendProgress(pos, dur)
                    }
                }
            } catch (e: Exception) {}
        }
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
            stopCasting()
        }
    }

    private fun stopCasting() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        mediaProjection?.stop()
        mediaProjection = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseVideoCodec()
        dlnaHttpServerJob?.cancel()
        dlnaHttpServerJob = null
        controlSessions.clear()
        raopClients.values.forEach { try { it.close() } catch (e: Exception) {} }
        raopClients.clear()
        airPlay2Clients.values.forEach { try { it.close() } catch (e: Exception) {} }
        airPlay2Clients.clear()
        airPlay2VolumeDb.clear()
        _activeDestinations.value = emptyList()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = CastState.OFF
        _stats.value = CastingStats()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
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

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 3840 

        const val RAOP_SAMPLE_RATE = 44100
        const val RAOP_FRAME_SAMPLES = 352
        const val RAOP_FRAME_BYTES = RAOP_FRAME_SAMPLES * 4
        
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
