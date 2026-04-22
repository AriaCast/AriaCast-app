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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.pow

data class CastDestination(
    val name: String,
    val host: String,
    val port: Int,
    val platform: String? = null,
    var delayMs: Int = 0
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

    // Compatibility properties
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
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.SECONDS)
                    writeTimeout(10, TimeUnit.SECONDS)
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
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 5000
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

    private val _audioBufferFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10)
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
                    val client = serverSocket.accept()
                    launch {
                        try {
                            client.getInputStream().bufferedReader().readLine()
                            val output = client.getOutputStream()
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
                            client.close()
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
                            platform = obj.optString("platform", null)
                        ))
                    }
                } else {
                    val host = intent.getStringExtra(EXTRA_SERVER_HOST)
                    val port = intent.getIntExtra(EXTRA_SERVER_PORT, 0)
                    val name = intent.getStringExtra(EXTRA_SERVER_NAME)
                    val platform = intent.getStringExtra(EXTRA_SERVER_PLATFORM)
                    if (host != null && port != 0 && name != null) {
                        destinations.add(CastDestination(name, host, port, platform))
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

        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken)
        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = (FRAME_SIZE * 2).coerceAtLeast(minBufSize)
            
            audioRecord = AudioRecord.Builder()
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

        sessionScope.launch {
            launch { 
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
            }

            destinations.forEach { dest ->
                launch { startControlSession(dest) }
                if (videoEnabled && destinations.size == 1) { 
                    launch { startVideoSession(dest) }
                }
                launch { startAudioSession(dest) }
                launch { startStatsSession(dest) }
            }
        }
        
        sessionScope.startMetadataRefreshLoop()
        _metadata.value?.let { sendMetadata(it) }
    }

    private suspend fun startAudioSession(dest: CastDestination) {
        var sentFramesCount = 0L
        var reconnectAttempts = 0

        while (currentCoroutineContext().isActive) {
            try {
                PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "Connecting to ws://${dest.host}:${dest.port}/audio")
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
                        
                        // Real-time latency lookup
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
            } catch (e: Exception) {}
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
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
                        withTimeoutOrNull(STATS_TIMEOUT) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                val json = JSONObject(frame.readText())
                                _stats.value = _stats.value.copy(
                                    bufferedFrames = json.optInt("bufferedFrames"),
                                    receivedFrames = json.optInt("receivedFrames")
                                )
                                updateNotification()
                            }
                        } ?: return@statsSocket
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
        controlSessions.clear()
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
        const val EXTRA_SERVERS_JSON = "com.aria.ariacast.EXTRA_SERVERS_JSON"

        const val PREFS_NAME = "AriaCastPrefs"
        const val KEY_LAST_SERVER_HOST = "last_server_host"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 3840 
        
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_BITRATE = 3000000 
        const val VIDEO_FPS = 30
        const val VIDEO_IFRAME_INTERVAL = 2

        private const val RECONNECT_INITIAL_BACKOFF = 1000L
        private const val STATS_TIMEOUT = 10000L 
        private const val ARTWORK_PORT = 8090
    }
}
