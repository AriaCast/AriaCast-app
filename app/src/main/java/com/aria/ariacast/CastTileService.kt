package com.aria.ariacast

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CastTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var audioCastService: AudioCastService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            audioCastService = binder.getService()
            isBound = true

            scope.launch {
                audioCastService?.state?.collectLatest { state ->
                    updateTileState(state)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            isBound = false
            updateTileState(CastState.OFF)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        job.cancel()
    }

    override fun onClick() {
        super.onClick()
        if (qsTile.state == Tile.STATE_ACTIVE) {
            val intent = Intent(this, AudioCastService::class.java).apply {
                action = AudioCastService.ACTION_STOP
            }
            startService(intent)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        }
    }

    private fun updateTileState(state: CastState) {
        qsTile.state = when (state) {
            CastState.CASTING, CastState.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        qsTile.updateTile()
    }
}