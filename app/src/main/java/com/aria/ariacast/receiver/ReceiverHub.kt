package com.aria.ariacast.receiver

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.DeviceIdentity
import com.aria.ariacast.MediaCommand
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.pipeline.AudioSource
import com.aria.ariacast.pipeline.FgsTypes
import com.aria.ariacast.pipeline.FrameChunker
import com.aria.ariacast.pipeline.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Process-wide state of this device's receiver role, shared by [ReceiverService], the UI
 * and the bridge source. Receiving and sending are independent: both can run at once.
 */
class ReceiverHub private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _sender = MutableStateFlow<String?>(null)
    /** Address of the sender currently streaming to us, or null. */
    val sender: StateFlow<String?> = _sender.asStateFlow()

    private val _metadata = MutableStateFlow(ReceiverMetadata())
    val metadata: StateFlow<ReceiverMetadata> = _metadata.asStateFlow()

    private val _artwork = MutableStateFlow<ByteArray?>(null)
    val artwork: StateFlow<ByteArray?> = _artwork.asStateFlow()

    private val _pcm = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Received audio, for the bridge source. */
    val pcm: SharedFlow<ByteArray> = _pcm.asSharedFlow()

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    private val _bridging = MutableStateFlow(false)
    /** True while received audio is being forwarded to other speakers. */
    val bridging: StateFlow<Boolean> = _bridging.asStateFlow()

    private var lastArtworkUrl: String? = null
    internal var server: ReceiverServer? = null

    var name: String
        get() = prefs.getString(KEY_NAME, null) ?: DeviceIdentity.defaultName(app)
        set(value) { prefs.edit().putString(KEY_NAME, value.trim().ifEmpty { DeviceIdentity.defaultName(app) }).apply() }

    var playLocally: Boolean
        get() = prefs.getBoolean(KEY_PLAY_LOCALLY, true)
        set(value) { prefs.edit().putBoolean(KEY_PLAY_LOCALLY, value).apply() }

    var startAtBoot: Boolean
        get() = prefs.getBoolean(KEY_START_AT_BOOT, false)
        set(value) { prefs.edit().putBoolean(KEY_START_AT_BOOT, value).apply() }

    val player = LocalPlayer(app)

    init {
        // Keep received audio on the phone's speaker while AriaCompanion is the default output.
        scope.launch { CompanionHub.get(app).speakerActive.collect { player.pinToBuiltInSpeaker(it) } }
    }

    fun start() {
        ContextCompat.startForegroundService(app, Intent(app, ReceiverService::class.java).setAction(ReceiverService.ACTION_START))
    }

    fun stop() {
        app.startService(Intent(app, ReceiverService::class.java).setAction(ReceiverService.ACTION_STOP))
    }

    /** Sends play/pause/next/previous back to whoever is streaming to us. */
    fun sendCommand(command: MediaCommand) {
        val action = when (command) {
            MediaCommand.PLAY -> "play"
            MediaCommand.PAUSE -> "pause"
            MediaCommand.TOGGLE -> "play_pause"
            MediaCommand.NEXT -> "next"
            MediaCommand.PREVIOUS -> "previous"
            MediaCommand.STOP -> "stop"
        }
        server?.sendCommandToSenders(action)
    }

    fun createBridgeSource(): AudioSource = BridgeSource()

    // --- called by ReceiverService ---

    internal fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) {
            _sender.value = null
            player.stop()
        }
    }

    internal fun onSenderConnected(remote: String) { _sender.value = remote }

    /** Another protocol (e.g. Sendspin) is playing on this device: show it on the Receive screen. */
    fun onExternalSender(name: String) { _sender.value = name }

    fun onExternalSenderGone(name: String) {
        if (_sender.value == name) _sender.value = null
        _metadata.value = _metadata.value.copy(isPlaying = false)
    }

    internal fun onSenderDisconnected(remote: String) {
        if (_sender.value == remote) _sender.value = null
        player.stop()
        _metadata.value = _metadata.value.copy(isPlaying = false)
    }

    internal fun onPcm(data: ByteArray) {
        if (playLocally) player.enqueue(data)
        _pcm.tryEmit(data)
    }

    fun onMetadata(update: MetadataUpdate) {
        val (merged, songChanged) = _metadata.value.merge(update)
        _metadata.value = merged
        server?.broadcastMetadata(merged)
        val url = merged.artworkUrl
        if (url != null && url.startsWith("http") && (url != lastArtworkUrl || songChanged)) {
            lastArtworkUrl = url
            scope.launch { _artwork.value = downloadImage(url) ?: _artwork.value }
        }
    }

    internal fun onCommand(action: String) {
        when (action) {
            "play" -> onMetadata(MetadataUpdate(isPlaying = true))
            "pause" -> onMetadata(MetadataUpdate(isPlaying = false))
            "play_pause" -> onMetadata(MetadataUpdate(isPlaying = !_metadata.value.isPlaying))
            "stop" -> { player.stop(); onMetadata(MetadataUpdate(isPlaying = false)) }
        }
        _commands.tryEmit(action)
    }

    private fun downloadImage(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        val bytes = if (c.responseCode == HttpURLConnection.HTTP_OK) c.inputStream.use { readBounded(it) } else null
        c.disconnect()
        // Only keep it if it actually decodes as an image.
        bytes?.takeIf { isImage(it) }
    } catch (_: Exception) { null }

    private fun isImage(bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ARTWORK_BYTES) return null
        }
        return out.toByteArray()
    }

    /** Forwards received audio to the cast pipeline (bridge mode). No screen capture needed. */
    private inner class BridgeSource : AudioSource {
        override val kind = SourceKind.RECEIVER
        override val muteLocalPlayback = false
        override val foregroundServiceType = FgsTypes.MEDIA_PLAYBACK

        override suspend fun open(): String? =
            if (_running.value) null else "This device is not receiving - turn on Receive first"

        override suspend fun run(emit: suspend (ByteArray) -> Unit) {
            _bridging.value = true
            val chunker = FrameChunker()
            try {
                pcm.collect { data ->
                    val frames = ArrayList<ByteArray>(2)
                    chunker.push(data) { frames.add(it) }
                    for (f in frames) emit(f)
                }
            } finally {
                _bridging.value = false
            }
        }

        override fun release() { _bridging.value = false }
    }

    companion object {
        const val KEY_NAME = "receiver_name"
        const val KEY_PLAY_LOCALLY = "receiver_play_locally"
        const val KEY_START_AT_BOOT = "receiver_start_at_boot"
        private const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024

        @Volatile private var instance: ReceiverHub? = null
        fun get(context: Context): ReceiverHub =
            instance ?: synchronized(this) {
                instance ?: ReceiverHub(context.applicationContext).also { instance = it }
            }
    }
}
