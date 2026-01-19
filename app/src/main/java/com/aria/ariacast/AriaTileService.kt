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
            val boundService = binder.getService()
            audioCastService = boundService
            isBound = true
            // Once bound, start collecting state updates
            scope.launch {
                boundService.state.collectLatest { state ->
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
        val tile = qsTile ?: return
        when (tile.state) {
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
                    action = AudioCastService.ACTION_STOP
                }
                startService(serviceIntent)
            }
        }
    }

    /**
     * Updates the tile's appearance based on the casting state.
     */
    private fun updateTile(state: CastState) {
        val tile = qsTile ?: return
        
        tile.state = when (state) {
            CastState.CASTING -> Tile.STATE_ACTIVE
            CastState.OFF -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }

        // Update the subtitle for more context
        tile.subtitle = when (state) {
            CastState.OFF -> "Tap to cast"
            CastState.DISCOVERING -> "Discovering..."
            CastState.CONNECTING -> "Connecting..."
            CastState.CASTING -> "Casting..."
            CastState.ERROR -> "Error occurred"
        }

        // Apply the changes to the tile
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
