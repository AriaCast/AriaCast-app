package com.aria.ariacast

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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.pow

class AudioCastService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = 0
    private var webSocketSession: DefaultClientWebSocketSession? = null
    private var controlSocketSession: DefaultClientWebSocketSession? = null

    private var serverName: String? = null
    var serverHost: String? = null
        private set
    var serverPort: Int = 0
        private set
    var serverPlatform: String? = null
        private set

    private val client by lazy {
        HttpClient(OkHttp) {
            install(WebSockets) {
                pingInterval = 5000
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

    private val binder = AudioCastBinder()

    inner class AudioCastBinder : Binder() {
        fun getService(): AudioCastService = this@AudioCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    private fun startCasting(mediaProjectionToken: Intent, serverHost: String, serverPort: Int) {
        _state.value = CastState.CONNECTING

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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(FRAME_SIZE * 2)
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

        audioRecord?.startRecording()

        scope.launch {
            launch { startControlSession(serverHost, serverPort) }
            startAudioSession(serverHost, serverPort)
        }
    }

    private suspend fun startAudioSession(serverHost: String, serverPort: Int) {
        val audioBuffer = ByteBuffer.allocate(FRAME_SIZE)
        var droppedFrames = 0
        var reconnectAttempts = 0

        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = serverHost, port = serverPort, path = "/audio") audioSocket@{
                    webSocketSession = this
                    reconnectAttempts = 0
                    Log.i(TAG, "Connected to /audio. Awaiting handshake...")

                    val handshakeFrame = try {
                        withTimeout(5000L) { incoming.receive() }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(TAG, "Handshake timeout. Server did not send response.")
                        _state.value = CastState.ERROR
                        return@audioSocket
                    }

                    if (handshakeFrame !is Frame.Text) {
                        Log.e(TAG, "Invalid handshake frame type: ${handshakeFrame.frameType.name}")
                        _state.value = CastState.ERROR
                        return@audioSocket
                    }

                    val handshakeText = handshakeFrame.readText()
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

                    val statsJob = launch { startStatsSession(serverHost, serverPort, droppedFrames) }

                    while (isActive) {
                        val readResult = audioRecord?.read(audioBuffer.array(), 0, FRAME_SIZE) ?: 0
                        when {
                            readResult == FRAME_SIZE -> {
                                val sent = outgoing.trySendBlocking(Frame.Binary(true, audioBuffer.array().copyOf()))
                                if (!sent.isSuccess) {
                                    Log.w(TAG, "Backpressure: Dropping frame")
                                    droppedFrames++
                                    _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + 1)
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
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "WebSocket error: ${e.localizedMessage}")
                reconnectAttempts++
                val delayTime = (RECONNECT_INITIAL_BACKOFF * 2.0.pow(reconnectAttempts.toDouble())).toLong()
                _state.value = CastState.CONNECTING
                updateNotification()
                Log.d(TAG, "Reconnecting in ${delayTime}ms...")
                delay(delayTime)
            }
        }
    }

    fun sendVolumeCommand(direction: String) {
        scope.launch {
            if (controlSocketSession == null) {
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
                Log.i(TAG, "Sent volume command: $command")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to send volume command", e)
            }
        }
    }

    fun sendMetadata(metadata: TrackMetadata) {
        scope.launch {
            if (serverHost == null) {
                Log.e(TAG, "Cannot send metadata, server details not found.")
                return@launch
            }
            try {
                client.post {
                    url {
                        protocol = URLProtocol.HTTP
                        host = serverHost!!
                        port = serverPort
                        path("metadata")
                    }
                    contentType(ContentType.Application.Json)
                    // Server expects metadata wrapped in a "data" key
                    setBody(mapOf("data" to metadata))
                }
                _metadata.value = metadata
                Log.d(TAG, "Sent metadata update: ${metadata.title}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to send metadata update", e)
            }
        }
    }

    private suspend fun startControlSession(serverHost: String, serverPort: Int) {
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = serverHost, port = serverPort, path = "/control") {
                    controlSocketSession = this
                    Log.i(TAG, "Control session established.")
                    for (frame in incoming) {
                        // Handle incoming control messages if needed in the future
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Control WebSocket error: ${e.localizedMessage}")
                controlSocketSession = null
                delay(RECONNECT_INITIAL_BACKOFF)
            }
        }
    }

    private suspend fun startStatsSession(serverHost: String, serverPort: Int, initialDroppedFrames: Int) {
        var droppedFrames = initialDroppedFrames
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(host = serverHost, port = serverPort, path = "/stats") statsSocket@{
                    while(isActive) {
                        withTimeoutOrNull(STATS_TIMEOUT) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                val json = JSONObject(frame.readText())
                                _stats.value = CastingStats(
                                    bufferedFrames = json.optInt("bufferedFrames"),
                                    droppedFrames = json.optInt("droppedFrames") + droppedFrames,
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
        
        webSocketSession?.cancel()
        webSocketSession = null

        controlSocketSession?.cancel()
        controlSocketSession = null

        serverHost = null
        serverPort = 0
        serverName = null
        serverPlatform = null

        job.cancelChildren()
        _state.value = CastState.OFF
        _stats.value = CastingStats()
        _metadata.value = null

        stopForeground(true)
        stopSelf()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): android.app.Notification {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "AriaCast", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val remoteViews = RemoteViews(packageName, R.layout.notification_casting)
        remoteViews.setTextViewText(R.id.notification_title, "AriaCast")

        val statusText = when(state.value) {
            CastState.CASTING -> "Casting to ${serverName ?: "Unknown"}"
            CastState.CONNECTING -> "Connecting to ${serverName ?: "Unknown"}"
            else -> "Not Casting"
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
        private const val RECONNECT_INITIAL_BACKOFF = 1000L
        private const val STATS_TIMEOUT = 10000L // 10 seconds
    }
}

enum class CastState {
    OFF,
    DISCOVERING,
    CONNECTING,
    CASTING,
    ERROR
}
