package com.aria.ariacast.companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.PacketDirection
import com.aria.ariacast.PacketLogger
import com.aria.ariacast.PacketType
import com.aria.ariacast.pipeline.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the app knows about the AriaCompanion board. */
sealed interface CompanionStatus {
    data object Absent : CompanionStatus
    data object NeedsPermission : CompanionStatus
    data class Connected(val info: CompanionInfo, val speaker: Boolean, val streaming: Boolean) : CompanionStatus
    data object Switching : CompanionStatus
    data object Bootloader : CompanionStatus
    data class Error(val message: String) : CompanionStatus
}

/**
 * Process-wide owner of the AriaCompanion board (docs/ariacompanion-2.md §6).
 *
 * Reconciles the board's mode with what the app wants: SPEAKER only while a companion cast
 * is running and no call is in progress, PRIVATE otherwise. While in SPEAKER mode it sends
 * a heartbeat every second; if the app dies the board falls back to PRIVATE by itself, so
 * phone audio can never disappear into a board nobody is reading.
 */
class CompanionHub private constructor(private val app: Context) {
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val prefs = app.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<CompanionStatus>(CompanionStatus.Absent)
    val status: StateFlow<CompanionStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow<CompanionStats?>(null)
    val stats: StateFlow<CompanionStats?> = _stats.asStateFlow()

    private val _speakerActive = MutableStateFlow(false)
    /** True while Android sees the board as a speaker; used to pin AriaCast's own playback to the phone. */
    val speakerActive: StateFlow<Boolean> = _speakerActive.asStateFlow()

    val gate = GateMonitor(app) { prefs.getBoolean(KEY_INCLUDE_GAMES, false) }

    @Volatile private var usb: CompanionUsb? = null
    private var heartbeatJob: Job? = null
    private var statsJob: Job? = null
    @Volatile private var castingWanted = false
    @Volatile private var inCall = false
    @Volatile private var lastModeSwitchAt = 0L

    /** True right after we asked for a mode switch: the next detach/attach is the board re-enumerating, not a plug event. */
    fun isReenumeration(): Boolean = System.currentTimeMillis() - lastModeSwitchAt < 5000

    /** True while a cast through the board is running. */
    val isCasting: Boolean get() = castingWanted
    private val parser = ChunkParser()

    /** Parsed events for the running [CompanionSource]; replaced on every new source. */
    @Volatile internal var events: Channel<CompanionEvent>? = null

    private val callGuard = CallGuard(app) { busy ->
        inCall = busy
        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, if (busy) "AriaCompanion: call detected, keeping audio on the phone" else "AriaCompanion: call ended")
        reconcile()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDeviceAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDeviceDetached(device)
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openDevice(device)
                    else _status.value = CompanionStatus.NeedsPermission
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // Exported: the attach/detach broadcasts come from the system USB service.
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        rescan()
    }

    /** Looks for an already-attached board, e.g. when the app starts with it plugged in. */
    fun rescan() {
        val devices = usbManager.deviceList.values
        devices.firstOrNull { CompanionUsb.isBootloader(it) }?.let { _status.value = CompanionStatus.Bootloader }
        devices.firstOrNull { CompanionUsb.isCompanion(it) }?.let(::onDeviceAttached)
    }

    fun onDeviceAttached(device: UsbDevice) {
        if (CompanionUsb.isBootloader(device)) {
            _status.value = CompanionStatus.Bootloader
            return
        }
        if (!CompanionUsb.isCompanion(device)) return
        if (usb?.device?.deviceName == device.deviceName) return
        if (usbManager.hasPermission(device)) openDevice(device) else requestPermission(device)
    }

    private fun onDeviceDetached(device: UsbDevice) {
        if (CompanionUsb.isBootloader(device) && _status.value == CompanionStatus.Bootloader) {
            _status.value = CompanionStatus.Absent
            return
        }
        val current = usb ?: return
        if (current.device.deviceName != device.deviceName) return
        closeDevice()
        // A mode switch re-enumerates the board: it disappears and comes back within ~1 s.
        if (!isReenumeration()) {
            _status.value = CompanionStatus.Absent
            events?.close() // unplugged: end the running cast
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun requestPermission(device: UsbDevice) {
        _status.value = CompanionStatus.NeedsPermission
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_USB_PERMISSION).setPackage(app.packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun openDevice(device: UsbDevice) {
        scope.launch {
            val opened = CompanionUsb.open(usbManager, device)
            if (opened == null) {
                _status.value = CompanionStatus.Error("AriaCompanion could not be opened")
                return@launch
            }
            val info = opened.getInfo()
            if (info == null || info.protocolVersion != CompanionProtocol.PROTOCOL_VERSION) {
                opened.close()
                _status.value = CompanionStatus.Error(
                    if (info == null) "AriaCompanion did not answer" else "AriaCompanion firmware speaks protocol ${info.protocolVersion}; update it from this screen"
                )
                return@launch
            }
            usb = opened
            parser.reset()
            val speaker = info.mode == CompanionProtocol.MODE_SPEAKER
            _speakerActive.value = speaker
            _status.value = CompanionStatus.Connected(info, speaker, streaming = false)
            PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, "AriaCompanion ${info.firmwareVersionString} connected (${if (speaker) "speaker" else "private"} mode)")
            opened.startReading(
                onBytes = { bytes, n -> onBulkBytes(bytes, n) },
                onError = { msg -> PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AriaCompanion read stopped: $msg") },
            )
            startStatsPolling()
            reconcile()
        }
    }

    private fun closeDevice() {
        heartbeatJob?.cancel(); heartbeatJob = null
        statsJob?.cancel(); statsJob = null
        usb?.close()
        usb = null
        _speakerActive.value = false
    }

    private fun onBulkBytes(bytes: ByteArray, n: Int) {
        for (event in parser.feed(bytes, 0, n)) {
            when (event) {
                is CompanionEvent.Mode -> {
                    val speaker = event.mode == CompanionProtocol.MODE_SPEAKER
                    _speakerActive.value = speaker
                    updateConnected { it.copy(speaker = speaker) }
                }
                is CompanionEvent.Stream -> updateConnected { it.copy(streaming = event.active) }
                is CompanionEvent.Overrun -> PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AriaCompanion overrun: ${event.droppedBytes} bytes dropped")
                else -> {}
            }
            events?.trySend(event)
        }
    }

    private inline fun updateConnected(f: (CompanionStatus.Connected) -> CompanionStatus.Connected) {
        val s = _status.value
        if (s is CompanionStatus.Connected) _status.value = f(s)
    }

    /** Brings the board's mode in line with what the app wants right now. */
    private fun reconcile() {
        val device = usb ?: return
        val wantSpeaker = castingWanted && !inCall
        val isSpeaker = _speakerActive.value
        if (wantSpeaker) startHeartbeat() else { heartbeatJob?.cancel(); heartbeatJob = null }
        if (wantSpeaker == isSpeaker) return
        lastModeSwitchAt = System.currentTimeMillis()
        scope.launch {
            _status.value = CompanionStatus.Switching
            val ok = device.setMode(if (wantSpeaker) CompanionProtocol.MODE_SPEAKER else CompanionProtocol.MODE_PRIVATE)
            PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "AriaCompanion -> ${if (wantSpeaker) "speaker" else "private"} mode${if (ok) "" else " (failed)"}")
            if (!ok) _status.value = CompanionStatus.Error("AriaCompanion did not accept the mode change")
            // The board now re-enumerates; the detach/attach broadcasts reopen it.
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                usb?.heartbeat(HEARTBEAT_TIMEOUT_S)
                delay(1000)
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                _stats.value = usb?.getStats()
                delay(500)
            }
        }
    }

    /** Called by [CompanionSource] when a cast through the board starts or stops. */
    internal fun setCasting(wanted: Boolean) {
        castingWanted = wanted
        if (wanted) { gate.start(); callGuard.start() } else { gate.stop(); callGuard.stop() }
        reconcile()
    }

    fun createSource(): AudioSource = CompanionSource(this)

    val isAttached: Boolean get() = usb != null

    fun rebootToBootloader(): Boolean = usb?.rebootToBootloader() ?: false

    fun resetStats() { scope.launch { usb?.resetStats() } }

    companion object {
        const val ACTION_USB_PERMISSION = "com.aria.ariacast.companion.USB_PERMISSION"
        const val KEY_INCLUDE_GAMES = "companion_include_games"
        const val KEY_AUTO_CAST = "companion_auto_cast"
        private const val HEARTBEAT_TIMEOUT_S = 3

        @Volatile private var instance: CompanionHub? = null
        fun get(context: Context): CompanionHub =
            instance ?: synchronized(this) {
                instance ?: CompanionHub(context.applicationContext).also { instance = it }
            }
    }
}

@Suppress("DEPRECATION")
internal fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
