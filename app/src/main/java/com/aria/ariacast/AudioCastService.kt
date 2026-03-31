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
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.pow

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
    private var webSocketSession: DefaultClientWebSocketSession? = null
    private var videoWebSocketSession: DefaultClientWebSocketSession? = null
    private var controlSocketSession: DefaultClientWebSocketSession? = null

    var serverName: String? = null
        private set
    var serverHost: String? = null
        private set
    var serverPort: Int = 0
        private set
    var serverPlatform: String? = null
        private set

    // Artwork hosting state
    private var currentArtworkBytes: ByteArray? = null

    // Bitrate tracking
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

    private val _state = MutableStateFlow(CastState.OFF)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(CastingStats())
    val stats: StateFlow<CastingStats> = _stats.asStateFlow()

    private val _metadata = MutableStateFlow<TrackMetadata?>(null)
    val metadata: StateFlow<TrackMetadata?> = _metadata.asStateFlow()

    private val _controlCommands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 10)
    val controlCommands: SharedFlow<MediaCommand> = _controlCommands.asSharedFlow()

    private val _audioBufferFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 5)
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
                    Log.d(TAG, "Periodic metadata refresh: ${currentMetadata.title}")
                    performMetadataUpdate(currentMetadata)
                }
            }
        }
    }

    private fun startArtworkServer() {
        scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(ARTWORK_PORT)
                Log.i(TAG, "Artwork server started on port $ARTWORK_PORT")
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
                            Log.e(TAG, "Artwork server error: ${e.message}")
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

                val intentServerHost = intent.getStringExtra(EXTRA_SERVER_HOST)
                val intentServerPort = intent.getIntExtra(EXTRA_SERVER_PORT, 0)
                serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
                serverPlatform = intent.getStringExtra(EXTRA_SERVER_PLATFORM)

                if (mediaProjectionToken != null && intentServerHost != null && intentServerPort != 0 && serverName != null) {
                    serverHost = intentServerHost
                    serverPort = intentServerPort
                    startCasting(mediaProjectionToken, intentServerHost, intentServerPort)
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
    private fun startCasting(mediaProjectionToken: Intent, serverHost: String, serverPort: Int) {
        sessionJob?.cancel()
        
        _state.value = CastState.CONNECTING
        sessionJob = SupervisorJob()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob!!)

        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        with(sharedPreferences.edit()) {
            putString(KEY_LAST_SERVER_HOST, serverHost)
            putInt(KEY_LAST_SERVER_PORT, serverPort)
            putString(KEY_LAST_SERVER_NAME, serverName)
            apply()
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
            launch { startControlSession(serverHost, serverPort) }
            if (videoEnabled) {
                launch { startVideoSession(serverHost, serverPort) }
            }
            startAudioSession(serverHost, serverPort)
        }
        
        sessionScope.startMetadataRefreshLoop()
        
        _metadata.value?.let { sendMetadata(it) }
    }

    private suspend fun startAudioSession(serverHost: String, serverPort: Int) {
        val audioBuffer = ByteBuffer.allocate(FRAME_SIZE)
        var droppedFramesCount = 0
        var sentFramesCount = 0L
        var reconnectAttempts = 0

        while (currentCoroutineContext().isActive) {
            try {
                PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "Connecting to ws://$serverHost:$serverPort/audio")
                client.webSocket(host = serverHost, port = serverPort, path = "/audio") audioSocket@{
                    webSocketSession = this
                    reconnectAttempts = 0
                    Log.i(TAG, "Connected to /audio. Awaiting handshake...")

                    val handshakeFrame = try {
                        withTimeout(3000L) { incoming.receive() }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(TAG, "Handshake timeout. Server did not send response.")
                        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Handshake timeout")
                        _state.value = CastState.ERROR
                        return@audioSocket
                    }

                    if (handshakeFrame !is Frame.Text) {
                        Log.e(TAG, "Invalid handshake frame type: ${handshakeFrame.frameType.name}")
                        _state.value = CastState.ERROR
                        return@audioSocket
                    }

                    val handshakeText = handshakeFrame.readText()
                    PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, handshakeText)
                    try {
                        val handshakeJson = JSONObject(handshakeText)
                        val handshakeType = handshakeJson.optString("type")
                        if (handshakeType != "handshake" && handshakeJson.optString("status") != "READY") {
                            Log.e(TAG, "Handshake failed, invalid response: $handshakeText")
                            _state.value = CastState.ERROR
                            return@audioSocket
                        }
                        Log.i(TAG, "Server is READY. Starting stream.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse handshake JSON: $handshakeText", e)
                        _state.value = CastState.ERROR
                        return@audioSocket
                    }

                    _state.value = CastState.CASTING
                    updateNotification()

                    audioRecord?.startRecording()
                    PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Started Recording & Streaming")
                    
                    val statsJob = launch { startStatsSession(serverHost, serverPort, droppedFramesCount) }

                    while (isActive) {
                        val readResult = audioRecord?.read(audioBuffer.array(), 0, FRAME_SIZE) ?: 0
                        when {
                            readResult == FRAME_SIZE -> {
                                val bufferCopy = audioBuffer.array().copyOf()
                                _audioBufferFlow.emit(bufferCopy)
                                
                                val sent = outgoing.trySendBlocking(Frame.Binary(true, bufferCopy))
                                if (sent.isSuccess) {
                                    sentFramesCount++
                                    
                                    // Update bitrate every second
                                    val now = System.currentTimeMillis()
                                    if (now - lastBitrateTime >= 1000) {
                                        val delta = sentFramesCount - lastSentFramesForBitrate
                                        val bps = delta * FRAME_SIZE * 8
                                        currentBitrateString = "${bps / 1000} kbps"
                                        lastBitrateTime = now
                                        lastSentFramesForBitrate = sentFramesCount
                                    }

                                    _stats.value = _stats.value.copy(sentFrames = sentFramesCount)
                                } else {
                                    Log.w(TAG, "Backpressure: Dropping frame to maintain low latency")
                                    droppedFramesCount++
                                    _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + 1)
                                    PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Dropped Frame (Backpressure)")
                                }
                            }
                            readResult < 0 -> {
                                Log.e(TAG, "AudioRecord read error: $readResult")
                                _state.value = CastState.ERROR
                                return@audioSocket
                            }
                        }
                    }
                    statsJob.cancelAndJoin()
                    audioRecord?.stop()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "WebSocket error: ${e.localizedMessage}")
                PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "Audio Socket Error: ${e.localizedMessage}")
                audioRecord?.stop()
                reconnectAttempts++
                val delayTime = (RECONNECT_INITIAL_BACKOFF * 2.0.pow(reconnectAttempts.toDouble().coerceAtMost(5.0))).toLong()
                _state.value = CastState.CONNECTING
                updateNotification()
                Log.d(TAG, "Reconnecting in ${delayTime}ms...")
                delay(delayTime)
            }
        }
    }

    private suspend fun startVideoSession(serverHost: String, serverPort: Int) {
        var reconnectAttempts = 0
        while (currentCoroutineContext().isActive) {
            try {
                PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "Connecting to ws://$serverHost:$serverPort/video")
                client.webSocket(host = serverHost, port = serverPort, path = "/video") videoSocket@{
                    videoWebSocketSession = this
                    reconnectAttempts = 0
                    Log.i(TAG, "Connected to /video.")

                    setupVideoCodec()
                    
                    val bufferInfo = MediaCodec.BufferInfo()
                    while (isActive) {
                        val outputBufferId = videoCodec?.dequeueOutputBuffer(bufferInfo, 10000L) ?: -1
                        if (outputBufferId >= 0) {
                            val outputBuffer = videoCodec?.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null) {
                                val outData = ByteArray(bufferInfo.size)
                                outputBuffer.get(outData)
                                
                                val sent = outgoing.trySendBlocking(Frame.Binary(true, outData))
                                if (!sent.isSuccess) {
                                    Log.w(TAG, "Video Backpressure: Dropping frame")
                                }
                            }
                            videoCodec?.releaseOutputBuffer(outputBufferId, false)
                        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.d(TAG, "Video format changed: ${videoCodec?.outputFormat}")
                        }
                    }
                    releaseVideoCodec()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Video WebSocket error: ${e.localizedMessage}")
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
            
            val width = VIDEO_WIDTH
            val height = VIDEO_HEIGHT
            val dpi = metrics.densityDpi

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = videoCodec?.createInputSurface()
            videoCodec?.start()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AriaCastVideo",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            Log.i(TAG, "Video codec and VirtualDisplay set up.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup video codec", e)
        }
    }

    private fun releaseVideoCodec() {
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (e: Exception) {}
        videoCodec = null
        videoWebSocketSession = null
    }

    fun sendVolumeCommand(direction: String) {
        scope.launch {
            val host = serverHost
            if (controlSocketSession == null || host == null) {
                Log.e(TAG, "Cannot send volume command, no active control session.")
                return@launch
            }
            if (serverPlatform?.contains("Music", ignoreCase = true) == true) {
                Log.w(TAG, "Volume control disabled for Music platform.")
                return@launch
            }
            try {
                val command = JSONObject().apply {
                    put("command", "volume")
                    put("direction", direction)
                }.toString()
                controlSocketSession?.send(Frame.Text(command))
                PacketLogger.log(PacketDirection.OUT, PacketType.CONTROL, "Volume $direction")
                Log.i(TAG, "Sent volume command: $command")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to send volume command", e)
            }
        }
    }

    fun setArtwork(bytes: ByteArray?) {
        currentArtworkBytes = bytes
    }

    fun sendMetadata(metadata: TrackMetadata) {
        metadataChannel.trySend(metadata)
    }

    private suspend fun performMetadataUpdate(metadata: TrackMetadata) {
        val host = serverHost
        val port = serverPort
        
        if (host == null) {
            Log.w(TAG, "Metadata update deferred: serverHost is null for track: ${metadata.title}")
            return
        }

        // If metadata has a URL but it's not from us, we replace it with our local host URL if we have bytes
        var finalMetadata = metadata
        if (currentArtworkBytes != null) {
            val myIp = getLocalIpAddress()
            if (myIp != null) {
                finalMetadata = metadata.copy(artworkUrl = "http://$myIp:$ARTWORK_PORT/artwork.jpg")
            }
        }
        
        try {
            Log.d(TAG, "Attempting to send metadata to http://$host:$port/metadata")
            val response = client.post {
                url {
                    protocol = URLProtocol.HTTP
                    this.host = host
                    this.port = port
                    path("metadata")
                }
                contentType(ContentType.Application.Json)
                setBody(mapOf("data" to finalMetadata))
                timeout {
                    requestTimeoutMillis = 5000
                }
            }
            
            if (response.status.isSuccess()) {
                PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata Sent: ${finalMetadata.title}")
                Log.d(TAG, "Successfully sent metadata update: ${finalMetadata.title}")
            } else {
                Log.e(TAG, "Server rejected metadata update. Status: ${response.status}")
                PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata Failed: ${response.status}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "HTTP Exception sending metadata update: ${e.localizedMessage}")
            PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata Error: ${e.localizedMessage}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }
        return null
    }

    private suspend fun startControlSession(serverHost: String, serverPort: Int) {
        while (currentCoroutineContext().isActive) {
            try {
                PacketLogger.log(PacketDirection.OUT, PacketType.CONTROL, "Connecting to ws://$serverHost:$serverPort/control")
                client.webSocket(host = serverHost, port = serverPort, path = "/control") {
                    controlSocketSession = this
                    Log.i(TAG, "Control session established.")
                    PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, "Control Connected")
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, text)
                            try {
                                val json = JSONObject(text)
                                val action = json.getString("action")
                                val command = MediaCommand.entries.find { it.name.equals(action, ignoreCase = true) }
                                if (command != null) {
                                    _controlCommands.emit(command)
                                    Log.d(TAG, "Received control command: $command")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse control message: $text", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Control WebSocket error: ${e.localizedMessage}")
                PacketLogger.log(PacketDirection.IN, PacketType.CONTROL, "Control Socket Error: ${e.localizedMessage}")
                controlSocketSession = null
                delay(RECONNECT_INITIAL_BACKOFF)
            }
        }
    }

    private suspend fun startStatsSession(serverHost: String, serverPort: Int, initialDroppedFrames: Int) {
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = serverHost, port = serverPort, path = "/stats") statsSocket@{
                    while(isActive) {
                        withTimeoutOrNull(STATS_TIMEOUT) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val json = JSONObject(text)
                                _stats.value = _stats.value.copy(
                                    bufferedFrames = json.optInt("bufferedFrames"),
                                    droppedFrames = json.optInt("droppedFrames") + initialDroppedFrames,
                                    receivedFrames = json.optInt("receivedFrames")
                                )
                                updateNotification()
                            }
                        } ?: run {
                            Log.w(TAG, "Stats timeout, reconnecting...")
                            return@statsSocket
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Stats WebSocket error: ${e.localizedMessage}")
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
        }.toString()
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection revoked.")
            _state.value = CastState.ERROR
            stopCasting()
        }
    }

    private fun stopCasting() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        releaseVideoCodec()

        webSocketSession?.cancel()
        webSocketSession = null

        controlSocketSession?.cancel()
        controlSocketSession = null

        serverHost = null
        serverPort = 0
        serverName = null
        serverPlatform = null

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

        val videoEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_VIDEO_ENABLED, false)
        val mode = if (videoEnabled) getString(R.string.audio_video) else getString(R.string.audio_only)

        val statusText = when(state.value) {
            CastState.CASTING -> getString(R.string.casting_to, serverName ?: getString(R.string.unknown), mode)
            CastState.CONNECTING -> getString(R.string.connecting_to, serverName ?: getString(R.string.unknown))
            else -> getString(R.string.not_casting)
        }
        remoteViews.setTextViewText(R.id.notification_text, statusText)

        val stopIntent = Intent(this, AudioCastService::class.java).apply {
            action = ACTION_STOP
        }
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

        const val PREFS_NAME = "AriaCastPrefs"
        const val KEY_LAST_SERVER_HOST = "last_server_host"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 3840 // 20ms of 16-bit stereo audio
        
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_BITRATE = 3000000 // 3 Mbps
        const val VIDEO_FPS = 30
        const val VIDEO_IFRAME_INTERVAL = 2

        private const val RECONNECT_INITIAL_BACKOFF = 1000L
        private const val STATS_TIMEOUT = 10000L // 10 seconds
        
        private const val ARTWORK_PORT = 8090
    }
}

enum class CastState {
    OFF,
    DISCOVERING,
    CONNECTING,
    CASTING,
    ERROR
}

enum class MediaCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    STOP
}
