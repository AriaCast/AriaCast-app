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
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var mediaSessionManager: MediaSessionManager

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val boundService = binder.getService()
            audioCastService = boundService
            isBound = true
            Log.d(TAG, "Bound to AudioCastService")
            
            // Start collecting commands once service is bound
            startCommandCollection(boundService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            isBound = false
            commandCollectionJob?.cancel()
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
        if (sbn?.notification?.category != "transport") return
        handleMediaSessionsChanged()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.notification?.category != "transport") return
        handleMediaSessionsChanged()
    }

    private fun handleMediaSessionsChanged() {
        val mediaControllers = getActiveMediaControllers()
        val newMediaController = mediaControllers.firstOrNull()

        if (newMediaController?.packageName != activeMediaController?.packageName) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = newMediaController
            activeMediaController?.registerCallback(mediaControllerCallback)
            mediaControllerCallback.onMetadataChanged(newMediaController?.metadata)
            mediaControllerCallback.onPlaybackStateChanged(newMediaController?.playbackState)
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
            if (metadata == null) {
                val clearTrack = TrackMetadata(
                    title = null, artist = null, album = null, artworkUrl = null,
                    durationMs = null, positionMs = null, isPlaying = false
                )
                audioCastService?.sendMetadata(clearTrack)
                Log.d(TAG, "Sent clear metadata command on metadata change.")
                return
            }
            val track = TrackMetadata(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                artworkUrl = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION) },
                positionMs = activeMediaController?.playbackState?.position,
                isPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            )
            audioCastService?.sendMetadata(track)
            Log.d(TAG, "Sent metadata on change: ${track.title}")
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state == null) {
                val clearTrack = TrackMetadata(
                    title = null, artist = null, album = null, artworkUrl = null,
                    durationMs = null, positionMs = null, isPlaying = false
                )
                audioCastService?.sendMetadata(clearTrack)
                positionUpdateJob?.cancel()
                Log.d(TAG, "Sent clear metadata command on playback state change.")
                return
            }
            val isPlaying = state.state == PlaybackState.STATE_PLAYING

            if (isPlaying) {
                startPositionUpdates(state)
            } else {
                positionUpdateJob?.cancel()
            }

            val track = audioCastService?.metadata?.value?.copy(
                isPlaying = isPlaying,
                positionMs = state.position
            )
            if (track != null) {
                audioCastService?.sendMetadata(track)
                Log.d(TAG, "Sent playback state change: isPlaying=$isPlaying")
            }
        }
    }

    private fun startPositionUpdates(initialPlaybackState: PlaybackState) {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var lastPosition = initialPlaybackState.position
            while (isActive) {
                val currentTrack = audioCastService?.metadata?.value
                if (currentTrack != null && currentTrack.isPlaying) {
                    val newPosition = lastPosition + (initialPlaybackState.playbackSpeed * 1000).toLong()
                    if (newPosition != currentTrack.positionMs) {
                        val updatedTrack = currentTrack.copy(positionMs = newPosition)
                        audioCastService?.sendMetadata(updatedTrack)
                    }
                    lastPosition = newPosition
                }
                delay(1000)
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
