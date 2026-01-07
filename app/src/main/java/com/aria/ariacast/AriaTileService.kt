package com.aria.ariacast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.aria.ariacast.AudioCastService.Companion.ACTION_STOP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class AriaTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private var audioCastService: AudioCastService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            audioCastService = binder.getService()
            isBound = true
            // Once bound, start collecting state updates
            scope.launch {
                audioCastService?.state?.collectLatest { state ->
                    updateTile(state)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            // Service has disconnected, update tile to inactive state
            audioCastService = null
            isBound = false
            updateTile(CastState.OFF)
        }
    }

    // This is called when the tile is added to the Quick Settings panel
    override fun onStartListening() {
        super.onStartListening()
        // Bind to the service to get real-time state updates
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    // This is called when the tile is removed from the Quick Settings panel
    override fun onStopListening() {
        super.onStopListening()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // Called when the user taps the tile
    override fun onClick() {
        super.onClick()
        when (qsTile.state) {
            // If the tile is inactive, casting is off. Tapping should start it.
            Tile.STATE_INACTIVE -> {
                // Launching MainActivity is the correct way to request MediaProjection permission.
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // This will show the activity over the QS panel, user grants permission,
                // and the activity will start the foreground service.
                startActivityAndCollapse(intent)
            }
            // If the tile is active, casting is on. Tapping should stop it.
            Tile.STATE_ACTIVE -> {
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(serviceIntent)
            }
        }
    }

    /**
     * Updates the tile's appearance based on the casting state.
     */
    private fun updateTile(state: CastState) {
        qsTile.state = when (state) {
            CastState.CASTING -> Tile.STATE_ACTIVE
            CastState.OFF -> Tile.STATE_INACTIVE
            // Any other state (CONNECTING, ERROR, etc.) is considered unavailable.
            // This prevents the user from trying to interact while the service is busy.
            else -> Tile.STATE_UNAVAILABLE
        }

        // Update the subtitle for more context
        qsTile.subtitle = when (state) {
            CastState.OFF -> "Tap to cast"
            CastState.DISCOVERING -> "Discovering..."
            CastState.CONNECTING -> "Connecting..."
            CastState.CASTING -> "Casting to device"
            CastState.ERROR -> "Error occurred"
        }

        // Apply the changes to the tile
        qsTile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
