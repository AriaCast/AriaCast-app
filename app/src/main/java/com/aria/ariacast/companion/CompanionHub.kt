package com.aria.ariacast.companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
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
    data class Connected(val info: CompanionInfo, val speaker: Boolean, val streaming: Boolean, val bluetooth: Boolean = false) : CompanionStatus
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

    /** The board in use: USB when one is plugged in, otherwise the chosen Bluetooth board. */
    @Volatile private var link: CompanionLink? = null
    private var bluetoothJob: Job? = null
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
        startBluetooth()
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
        if ((link as? CompanionUsb)?.device?.deviceName == device.deviceName) return
        if (usbManager.hasPermission(device)) openDevice(device) else requestPermission(device)
    }

    private fun onDeviceDetached(device: UsbDevice) {
        if (CompanionUsb.isBootloader(device) && _status.value == CompanionStatus.Bootloader) {
            _status.value = CompanionStatus.Absent
            return
        }
        val current = link as? CompanionUsb ?: return
        if (current.device.deviceName != device.deviceName) return
        closeDevice()
        // A mode switch re-enumerates the board: it disappears and comes back within ~1 s.
        if (!isReenumeration()) {
            _status.value = CompanionStatus.Absent
            events?.close() // unplugged: end the running cast
            startBluetooth() // a paired Bluetooth board can take over
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
            // A plugged-in board always wins over the Bluetooth one.
            if (link is CompanionBt) closeDevice()
            attachLink(opened)
        }
    }

    /** Starts using [opened]: reads its stream, checks it speaks our protocol, then applies the wanted mode. */
    private fun attachLink(opened: CompanionLink): Boolean {
        val bluetooth = opened.kind == CompanionLink.Kind.BLUETOOTH
        parser.reset()
        link = opened
        // Read first: the Bluetooth board answers GET_INFO inside the stream.
        opened.startReading(
            onBytes = { bytes, n -> onBulkBytes(opened, bytes, n) },
            onError = { msg ->
                PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AriaCompanion read stopped: $msg")
                if (bluetooth) scope.launch { onBluetoothLost(opened) }
            },
        )
        val info = opened.getInfo()
        val expected = if (bluetooth) CompanionProtocol.BT_PROTOCOL_VERSION else CompanionProtocol.PROTOCOL_VERSION
        if (info == null || info.protocolVersion != expected) {
            link = null
            opened.close()
            _status.value = CompanionStatus.Error(
                if (info == null) "AriaCompanion did not answer" else "AriaCompanion firmware speaks protocol ${info.protocolVersion}; update it"
            )
            return false
        }
        val speaker = info.mode == CompanionProtocol.MODE_SPEAKER
        _speakerActive.value = speaker
        _status.value = CompanionStatus.Connected(info, speaker, streaming = false, bluetooth = bluetooth)
        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE,
            "AriaCompanion ${info.firmwareVersionString} connected over ${if (bluetooth) "Bluetooth" else "USB"} (${if (speaker) "speaker" else "private"} mode)")
        startStatsPolling()
        reconcile()
        return true
    }

    /** The Bluetooth link dropped (board off, out of range): end a cast through it and try again later. */
    private fun onBluetoothLost(lost: CompanionLink) {
        if (link !== lost) return
        closeDevice()
        _status.value = CompanionStatus.Absent
        events?.close()
        startBluetooth()
    }

    private fun closeDevice() {
        heartbeatJob?.cancel(); heartbeatJob = null
        statsJob?.cancel(); statsJob = null
        link?.close()
        link = null
        _speakerActive.value = false
    }

    private fun onBulkBytes(from: CompanionLink, bytes: ByteArray, n: Int) {
        if (link !== from) return
        for (event in parser.feed(bytes, 0, n)) {
            when (event) {
                is CompanionEvent.Info, is CompanionEvent.Stats -> { from.onReply(event); continue }
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
        val device = link ?: return
        val wantSpeaker = castingWanted && !inCall
        val isSpeaker = _speakerActive.value
        if (wantSpeaker) startHeartbeat() else { heartbeatJob?.cancel(); heartbeatJob = null }
        if (wantSpeaker == isSpeaker) return
        val usbBoard = device.kind == CompanionLink.Kind.USB
        if (usbBoard) lastModeSwitchAt = System.currentTimeMillis()
        scope.launch {
            // The USB board re-enumerates (it drops off the bus and comes back), so it is
            // "switching" until it is reopened. The Bluetooth board stays linked and just
            // answers with a MODE chunk, which updates the Connected status in place.
            if (usbBoard) _status.value = CompanionStatus.Switching
            val ok = device.setMode(if (wantSpeaker) CompanionProtocol.MODE_SPEAKER else CompanionProtocol.MODE_PRIVATE)
            PacketLogger.log(PacketDirection.OUT, PacketType.HANDSHAKE, "AriaCompanion -> ${if (wantSpeaker) "speaker" else "private"} mode${if (ok) "" else " (failed)"}")
            if (!ok) _status.value = CompanionStatus.Error("AriaCompanion did not accept the mode change")
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                link?.heartbeat(HEARTBEAT_TIMEOUT_S)
                delay(1000)
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                _stats.value = link?.getStats()
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

    /** A board is linked, over USB or Bluetooth. */
    val isAttached: Boolean get() = link != null

    /** A board is plugged in over USB: then every cast can go through it without screen capture. */
    val isUsbAttached: Boolean get() = link is CompanionUsb

    fun rebootToBootloader(): Boolean = (link as? CompanionUsb)?.rebootToBootloader() ?: false

    fun resetStats() { scope.launch { link?.resetStats() } }

    // ------------------------------------------------------------------ Bluetooth board

    /** Address of the Bluetooth board chosen on the AriaCompanion screen, or null. */
    val bluetoothAddress: String? get() = prefs.getString(KEY_BT_ADDRESS, null)

    private val _bluetoothConnecting = MutableStateFlow(false)
    /** True while the app is trying to reach the chosen Bluetooth board. */
    val bluetoothConnecting: StateFlow<Boolean> = _bluetoothConnecting.asStateFlow()

    fun setBluetoothDevice(address: String?) {
        prefs.edit().putString(KEY_BT_ADDRESS, address).apply()
        val current = link
        if (current is CompanionBt && current.id != address) {
            scope.launch { onBluetoothLost(current) }
        }
        bluetoothJob?.cancel(); bluetoothJob = null
        startBluetooth()
    }

    fun canUseBluetooth(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Keeps trying to link the chosen Bluetooth board while no board is linked, backing off
     * from 3 s to 30 s. Safe to call repeatedly (after the permission is granted, after unplugging).
     */
    @SuppressLint("MissingPermission")
    fun startBluetooth() {
        if (bluetoothJob?.isActive == true) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        bluetoothJob = scope.launch {
            var backoff = 3_000L
            while (isActive) {
                val address = bluetoothAddress ?: break
                if (!canUseBluetooth()) break
                if (link == null && adapter.isEnabled) {
                    _bluetoothConnecting.value = true
                    val opened = runCatching { CompanionBt.open(adapter.getRemoteDevice(address)) }.getOrNull()
                    _bluetoothConnecting.value = false
                    if (opened != null && link == null && attachLink(opened)) backoff = 3_000L
                    else { opened?.takeIf { link !== it }?.close(); backoff = (backoff * 2).coerceAtMost(30_000L) }
                }
                delay(if (link != null) 5_000L else backoff)
            }
            _bluetoothConnecting.value = false
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.aria.ariacast.companion.USB_PERMISSION"
        const val KEY_INCLUDE_GAMES = "companion_include_games"
        const val KEY_AUTO_CAST = "companion_auto_cast"
        const val KEY_BT_ADDRESS = "companion_bt_address"
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
