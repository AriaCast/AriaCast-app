package com.aria.ariacast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaNotificationListener : NotificationListenerService() {

    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var activeMediaController: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var commandCollectionJob: Job? = null
    private var stateObservationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var mediaSessionManager: MediaSessionManager

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val boundService = binder.getService()
            audioCastService = boundService
            isBound = true
            Log.d(TAG, "Bound to AudioCastService")
            
            handleMediaSessionsChanged()
            startCommandCollection(boundService)
            startStateObservation(boundService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            isBound = false
            commandCollectionJob?.cancel()
            stateObservationJob?.cancel()
            Log.d(TAG, "Unbound from AudioCastService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun startStateObservation(service: AudioCastService) {
        stateObservationJob?.cancel()
        stateObservationJob = scope.launch {
            service.state.collectLatest { state ->
                if (state == CastState.CASTING) {
                    Log.d(TAG, "Service state is CASTING, forcing metadata sync.")
                    syncMetadata()
                }
            }
        }
    }

    private fun startCommandCollection(service: AudioCastService) {
        commandCollectionJob?.cancel()
        commandCollectionJob = scope.launch {
            service.controlCommands.collectLatest { command: MediaCommand ->
                handleIncomingCommand(command)
            }
        }
    }

    private fun handleIncomingCommand(command: MediaCommand) {
        val controller = activeMediaController ?: run {
            Log.w(TAG, "Received command $command but no active media controller found.")
            return
        }

        val transportControls = controller.transportControls
        when (command) {
            MediaCommand.PLAY -> transportControls.play()
            MediaCommand.PAUSE -> transportControls.pause()
            MediaCommand.TOGGLE -> {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    transportControls.pause()
                } else {
                    transportControls.play()
                }
            }
            MediaCommand.NEXT -> transportControls.skipToNext()
            MediaCommand.PREVIOUS -> transportControls.skipToPrevious()
            MediaCommand.STOP -> transportControls.stop()
        }
        Log.d(TAG, "Executed command: $command on ${controller.packageName}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.notification?.category == "transport") {
            handleMediaSessionsChanged()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.notification?.category == "transport") {
            handleMediaSessionsChanged()
        }
    }

    private fun handleMediaSessionsChanged() {
        val mediaControllers = getActiveMediaControllers()
        // Prioritize the one that is currently playing
        val newMediaController = mediaControllers.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: mediaControllers.firstOrNull()

        if (newMediaController?.packageName != activeMediaController?.packageName) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = newMediaController
            activeMediaController?.registerCallback(mediaControllerCallback)
            Log.d(TAG, "Switched media controller to: ${newMediaController?.packageName}")
            syncMetadata()
            
            if (activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                startPositionUpdates()
            } else {
                positionUpdateJob?.cancel()
            }
        } else if (newMediaController == null && activeMediaController != null) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = null
            Log.d(TAG, "Cleared media controller")
            syncMetadata()
            positionUpdateJob?.cancel()
        }
    }

    private fun getActiveMediaControllers(): List<MediaController> {
        val componentName = ComponentName(this, MediaNotificationListener::class.java)
        return try {
            mediaSessionManager.getActiveSessions(componentName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active media sessions", e)
            emptyList()
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "onMetadataChanged: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
            syncMetadata()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "onPlaybackStateChanged: ${state?.state}")
            syncMetadata()
            if (state?.state == PlaybackState.STATE_PLAYING) {
                startPositionUpdates()
            } else {
                positionUpdateJob?.cancel()
            }
        }
    }

    private fun syncMetadata() {
        val controller = activeMediaController
        val service = audioCastService ?: return

        if (controller == null) {
            service.sendMetadata(TrackMetadata(null, null, null, null, null, null, false))
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val position = if (isPlaying && playbackState != null) {
            val timeDiff = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
            playbackState.position + (timeDiff * playbackState.playbackSpeed).toLong()
        } else {
            playbackState?.position
        }

        val track = TrackMetadata(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artworkUrl = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = position,
            isPlaying = isPlaying
        )
        
        Log.d(TAG, "Syncing metadata: ${track.title} - isPlaying=$isPlaying @ $position")
        service.sendMetadata(track)
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    syncMetadata()
                } else {
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
        }
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        positionUpdateJob?.cancel()
        commandCollectionJob?.cancel()
        stateObservationJob?.cancel()
    }

    companion object {
        const val TAG = "MediaNotificationListener"

        fun isEnabled(context: Context): Boolean {
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            val componentName = ComponentName(context, MediaNotificationListener::class.java).flattenToString()
            return enabledListeners?.contains(componentName) == true
        }
    }
}
