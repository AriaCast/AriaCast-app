package com.aria.ariacast.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.pipeline.FgsTypes
import com.aria.ariacast.receiver.ReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A video being played on a TV, plus the phone-side server that feeds it. */
object VideoSession {
    sealed interface State {
        data object Idle : State
        data class Loading(val target: String) : State
        data class Playing(val target: String, val title: String, val paused: Boolean, val position: Double, val duration: Double) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var server: MediaServer? = null
    private var target: VideoTarget? = null
    private var poll: Job? = null

    /** Plays a local [uri] (served by the phone) or a remote http(s) [uri] on [destination]. */
    fun start(context: Context, destination: Server, uri: Uri, mime: String, title: String, subtitles: String?) {
        stop(context)
        val app = context.applicationContext
        _state.value = State.Loading(destination.name)
        ContextCompat.startForegroundService(app, Intent(app, VideoCastService::class.java))
        scope.launch {
            try {
                val t = VideoTarget.forServer(destination)
                val ip = ReceiverService.localIpv4() ?: error("Not connected to a network")
                val isRemote = uri.scheme == "http" || uri.scheme == "https"
                var url = uri.toString()
                var subUrl: String? = null
                if (!isRemote || subtitles != null) {
                    val srv = server ?: MediaServer(app.contentResolver).also { it.start(); server = it }
                    if (!isRemote) url = "http://$ip:${srv.port}" + srv.publish(uri, mime)
                    subtitles?.let { subUrl = "http://$ip:${srv.port}" + srv.publishSubtitles(it) }
                }
                t.load(url, mime, title, subUrl)
                target = t
                _state.value = State.Playing(destination.name, title, false, 0.0, 0.0)
                poll = scope.launch {
                    while (isActive) {
                        delay(1000)
                        val p = runCatching { t.progress() }.getOrNull() ?: continue
                        (_state.value as? State.Playing)?.let { _state.value = it.copy(position = p.first, duration = p.second) }
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                server?.stop(); server = null
                app.stopService(Intent(app, VideoCastService::class.java))
            }
        }
    }

    fun togglePause() {
        val s = _state.value as? State.Playing ?: return
        val t = target ?: return
        scope.launch { runCatching { if (s.paused) t.play() else t.pause() } }
        _state.value = s.copy(paused = !s.paused)
    }

    fun seek(seconds: Double) {
        val t = target ?: return
        scope.launch { runCatching { t.seek(seconds) } }
        (_state.value as? State.Playing)?.let { _state.value = it.copy(position = seconds) }
    }

    fun stop(context: Context) {
        poll?.cancel(); poll = null
        target?.let { t -> scope.launch { runCatching { t.stop() } } }
        target = null
        server?.stop(); server = null
        if (_state.value !is State.Failed) _state.value = State.Idle
        val app = context.applicationContext
        app.stopService(Intent(app, VideoCastService::class.java))
    }
}

/** Keeps the process (and its media server) alive while a TV plays a video from this phone. */
class VideoCastService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VideoSession.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), FgsTypes.MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.video_cast_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, VideoCastActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VideoCastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setContentTitle(getString(R.string.video_cast_title))
            .setContentText(getString(R.string.video_notification))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "VideoCastChannel"
        private const val NOTIFICATION_ID = 3
        const val ACTION_STOP = "com.aria.ariacast.video.STOP"
    }
}
