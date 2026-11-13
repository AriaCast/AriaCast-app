package com.aria.ariacast.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GateState(val blocked: Boolean, val reason: String? = null)

/**
 * Watches what Android is playing and decides whether the AriaCompanion gate must close.
 * Re-evaluates on playback changes, ringer-mode changes and volume changes.
 */
class GateMonitor(
    private val context: Context,
    private val includeGames: () -> Boolean,
) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(GateState(false))
    val state: StateFlow<GateState> = _state.asStateFlow()
    private var running = false

    private val env = object : AudioEnv {
        override fun streamVolume(stream: Int): Int = try { am.getStreamVolume(stream) } catch (_: Exception) { 1 }
        override val ringerMode: Int get() = am.ringerMode
        override val companionDeviceId: Int?
            get() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE && it.productName?.contains("AriaCompanion") == true }
                ?.id
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = evaluate(configs)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = evaluate(am.activePlaybackConfigurations)
    }

    fun start() {
        if (running) return
        running = true
        am.registerAudioPlaybackCallback(playbackCallback, main)
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        evaluate(am.activePlaybackConfigurations)
    }

    fun stop() {
        if (!running) return
        running = false
        am.unregisterAudioPlaybackCallback(playbackCallback)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        _state.value = GateState(false)
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val players = configs.map { cfg ->
            val attrs = cfg.audioAttributes
            PlayerInfo(
                usage = attrs.usage,
                volumeStream = attrs.volumeControlStream,
                deviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cfg.audioDeviceInfo?.id else null,
            )
        }
        val blocker = MediaOnlyRules.blockingPlayer(players, env, includeGames())
        _state.value = GateState(blocker != null, blocker?.let { MediaOnlyRules.describe(it.usage) })
    }
}
