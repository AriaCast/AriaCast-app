package com.aria.ariacast

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.pipeline.SourceKind
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.AriaApp
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.theme.AriaTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The app's single main screen host (Compose). Owns discovery, the service binding, the
 * screen-capture consent flow, deep links and plugins; the UI itself lives in ui/.
 */
class MainActivity : ComponentActivity(), AppController, PluginHost {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences
    private lateinit var pluginManager: PluginManager
    private lateinit var updateManager: UpdateManager
    override lateinit var discoveryManager: DiscoveryManager

    private var isBound = false
    private var pendingServers: List<Server> = emptyList()

    private val _service = MutableStateFlow<AudioCastService?>(null)
    override val service: StateFlow<AudioCastService?> = _service.asStateFlow()
    override val audioCastServiceFlow: StateFlow<AudioCastService?> get() = service
    override val hostScope: CoroutineScope get() = lifecycleScope

    /** Discovered speakers plus, when a Sendspin server offers it, its input as a pseudo-speaker. */
    override val servers: StateFlow<List<Server>> by lazy {
        kotlinx.coroutines.flow.combine(discoveryManager.servers, com.aria.ariacast.sendspin.SendspinHub.get(this).sourceTarget) { list, input ->
            if (input == null) list else list + input
        }.stateIn(lifecycleScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }
    override val discoveryState: StateFlow<DiscoveryState> get() = discoveryManager.state

    private val _groups = MutableStateFlow<List<CastGroup>>(emptyList())
    override val groups: StateFlow<List<CastGroup>> = _groups.asStateFlow()

    private val _notificationAccess = MutableStateFlow(true)
    override val notificationAccess: StateFlow<Boolean> = _notificationAccess.asStateFlow()

    private val _multiroom = MutableStateFlow(false)
    override val multiroomEnabled: StateFlow<Boolean> = _multiroom.asStateFlow()

    private val _screen = MutableStateFlow(Screen.HOME)
    override val screen: StateFlow<Screen> = _screen.asStateFlow()

    override val isTv: Boolean by lazy {
        getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val s = (binder as AudioCastService.AudioCastBinder).getService()
            _service.value = s
            isBound = true
            pluginManager.runEnabledPlugins(this@MainActivity, s)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            isBound = false
        }
    }

    // ---------------------------------------------------------------- consent flow

    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && it.data != null && pendingServers.isNotEmpty()) {
            val intent = Intent(this, AudioCastService::class.java)
                .setAction(AudioCastService.ACTION_START)
                .putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, it.data)
                .putExtra(AudioCastService.EXTRA_SERVERS_JSON, serversJson(pendingServers))
            ContextCompat.startForegroundService(this, intent)
        } else if (it.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, getString(R.string.media_projection_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingSource: Pair<SourceKind, Int?>? = null

    private val requestCastPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val recordAudio = grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        val source = pendingSource
        pendingSource = null
        when {
            recordAudio && source != null -> startSourceCast(source.first, source.second)
            recordAudio -> startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> openAppSettingsForPermission()
            else -> Toast.makeText(this, getString(R.string.record_audio_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private var wifiAddNetworkContinuation: CancellableContinuation<Boolean>? = null
    private val requestAddWifiNetwork = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val saved = WifiJoinManager.interpretResult(result.resultCode, result.data)
        wifiAddNetworkContinuation?.let { if (it.isActive) it.resume(saved) }
        wifiAddNetworkContinuation = null
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettingsForPermission() {
        Toast.makeText(this, getString(R.string.record_audio_permission_permanently_denied), Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun neededPermissions(source: SourceKind): List<String> = buildList {
        // Playback capture and the microphone both need RECORD_AUDIO; USB loopback and
        // forwarding received audio don't.
        if ((source == SourceKind.SYSTEM_CAPTURE || source == SourceKind.MICROPHONE) && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun castTo(servers: List<Server>, source: SourceKind, inputDeviceId: Int?) {
        if (servers.isEmpty()) return
        pendingServers = servers
        // With an AriaCompanion board in speaker-capable state, screen capture is never needed.
        val effective = if (source == SourceKind.SYSTEM_CAPTURE && CompanionHub.get(this).isUsbAttached) SourceKind.COMPANION_USB else source
        val missing = neededPermissions(effective)
        if (missing.isNotEmpty()) {
            pendingSource = if (effective == SourceKind.SYSTEM_CAPTURE) null else effective to inputDeviceId
            requestCastPermissions.launch(missing.toTypedArray())
            return
        }
        if (effective == SourceKind.SYSTEM_CAPTURE) startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        else startSourceCast(effective, inputDeviceId)
    }

    private fun startSourceCast(kind: SourceKind, inputDeviceId: Int?) {
        val intent = Intent(this, AudioCastService::class.java)
            .setAction(AudioCastService.ACTION_START_SOURCE)
            .putExtra(AudioCastService.EXTRA_SOURCE, kind.id)
            .putExtra(AudioCastService.EXTRA_SERVERS_JSON, serversJson(pendingServers))
        if (inputDeviceId != null) intent.putExtra(AudioCastService.EXTRA_INPUT_DEVICE_ID, inputDeviceId)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun stopCasting() {
        startService(Intent(this, AudioCastService::class.java).setAction(AudioCastService.ACTION_STOP))
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = DiscoveryManager(this)
        pluginManager = PluginManager(this)
        updateManager = UpdateManager(this)
        loadGroups()

        // First run: onboarding. A TV opens on the full-screen receiver, a phone that is
        // already receiving on the Receive destination, everything else on Send.
        _screen.value = when {
            !prefs.getBoolean(KEY_ONBOARDED, false) -> Screen.ONBOARDING
            isTv -> Screen.RECEIVER_DISPLAY
            ReceiverHub.get(this).running.value -> Screen.RECEIVE
            else -> Screen.HOME
        }
        screenFromIntent(intent)?.let { _screen.value = it }

        setContent {
            AriaTheme { AriaApp(this) }
        }

        lifecycleScope.launch { updateManager.checkForUpdates(manual = false) }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenFromIntent(intent)?.let { _screen.value = it }
        handleDeepLink(intent)
    }

    private fun screenFromIntent(intent: Intent?): Screen? =
        intent?.getStringExtra(EXTRA_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?.takeIf { _screen.value != Screen.ONBOARDING }

    override fun setMultiroomEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsActivity.KEY_MULTIROOM_ENABLED, enabled).apply()
        _multiroom.value = enabled
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AudioCastService::class.java), connection, Context.BIND_AUTO_CREATE)
        discoveryManager.startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        _notificationAccess.value = MediaNotificationListener.isEnabled(this)
        _multiroom.value = prefs.getBoolean(SettingsActivity.KEY_MULTIROOM_ENABLED, false)
        loadGroups()
        pluginManager.runEnabledPlugins(this, _service.value)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
            _service.value = null
        }
        discoveryManager.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        pluginManager.shutdown()
    }

    // ---------------------------------------------------------------- AppController

    override fun navigate(screen: Screen) { _screen.value = screen }

    override fun refreshDiscovery() = discoveryManager.startDiscovery()

    override fun addManualServer(host: String, port: Int, name: String, platform: String, extra: String?): Boolean =
        discoveryManager.addManualServer(host, port, name, platform, extra)

    override fun removeServer(server: Server) = discoveryManager.removeServer(server)

    private fun loadGroups() {
        val array = JSONArray(prefs.getString(KEY_GROUPS, "[]") ?: "[]")
        _groups.value = (0 until array.length()).map { CastGroup.fromJson(array.getString(it)) }
    }

    private fun storeGroups(groups: List<CastGroup>) {
        val array = JSONArray()
        groups.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_GROUPS, array.toString()).apply()
        _groups.value = groups
    }

    override fun saveGroup(name: String, hosts: List<String>) {
        if (name.isBlank() || hosts.isEmpty()) return
        storeGroups(_groups.value.filter { it.name != name } + CastGroup(name.trim(), hosts))
    }

    override fun deleteGroup(group: CastGroup) = storeGroups(_groups.value.filter { it.name != group.name })

    override fun requestNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun openSettings() = navigate(Screen.SETTINGS)

    override fun openActivity(cls: Class<*>) = startActivity(Intent(this, cls))

    override fun finishOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _screen.value = when {
            isTv -> Screen.RECEIVER_DISPLAY
            ReceiverHub.get(this).running.value -> Screen.RECEIVE
            else -> Screen.HOME
        }
    }

    // ---------------------------------------------------------------- deep links

    /**
     * ariacast://<host>[:<port>][?type=<type>&name=<name>][&ssid=<ssid>&pass=<pass>] links
     * (NFC tags, the receiver's "Scan to cast here" QR code). Tapping the same link while
     * already casting to that host stops casting. See WifiJoinManager for ssid/pass.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme?.equals("ariacast", ignoreCase = true) != true) return
        intent.data = null // don't replay on config changes

        val host = uri.host
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.deep_link_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val requestedPort = uri.port
        val platform = platformForDeepLinkType(uri.getQueryParameter("type"))
        val name = uri.getQueryParameter("name")
        val label = name ?: host
        val ssid = uri.getQueryParameter("ssid")

        lifecycleScope.launch {
            val s = waitForServiceConnection()
            val activeHost = s?.activeDestinations?.value?.firstOrNull()?.host
            if (s?.state?.value == CastState.CASTING && activeHost == host) {
                Toast.makeText(this@MainActivity, getString(R.string.deep_link_stopping, label), Toast.LENGTH_SHORT).show()
                stopCasting()
                return@launch
            }
            if (!ssid.isNullOrEmpty() && !joinDeepLinkWifi(ssid, uri.getQueryParameter("pass") ?: "")) {
                Toast.makeText(this@MainActivity, getString(R.string.deep_link_wifi_failed, ssid), Toast.LENGTH_LONG).show()
                return@launch
            }
            val target = resolveDeepLinkTarget(host, requestedPort, platform, name, skipDiscovery = !ssid.isNullOrEmpty())
            if (target == null) {
                Toast.makeText(this@MainActivity, getString(R.string.deep_link_not_found, label), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!ssid.isNullOrEmpty() && !WifiJoinManager.waitForReachable(target.host, target.port)) {
                Toast.makeText(this@MainActivity, getString(R.string.deep_link_wifi_unreachable, ssid), Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@MainActivity, getString(R.string.deep_link_casting, target.name, target.host), Toast.LENGTH_SHORT).show()
            castTo(listOf(target))
        }
    }

    private suspend fun waitForServiceConnection(timeoutMs: Long = 3000): AudioCastService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (_service.value == null && System.currentTimeMillis() < deadline) delay(100)
        return _service.value
    }

    private suspend fun joinDeepLinkWifi(ssid: String, password: String): Boolean =
        suspendCancellableCoroutine { cont ->
            wifiAddNetworkContinuation = cont
            cont.invokeOnCancellation { wifiAddNetworkContinuation = null }
            requestAddWifiNetwork.launch(WifiJoinManager.buildAddNetworkIntent(ssid, password))
        }

    /** Prefers an already-discovered device (it carries metadata a blind connection can't), else connects directly. */
    private suspend fun resolveDeepLinkTarget(host: String, requestedPort: Int, platform: String, name: String?, skipDiscovery: Boolean): Server? {
        fun directConnect(): Server? {
            val port = if (requestedPort > 0) requestedPort else defaultPortForDeepLinkPlatform(platform)
            if (port <= 0) return null
            return Server(name ?: host, host, port, "1.0", listOf("pcm"), 48000, 2, platform)
        }
        if (skipDiscovery) return directConnect()
        fun findMatch(): Server? {
            val list = discoveryManager.servers.value
            if (!name.isNullOrEmpty()) list.find { it.name.equals(name, ignoreCase = true) && it.platform == platform }?.let { return it }
            return list.find { it.host == host && it.platform == platform }
        }
        findMatch()?.let { return it }
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            findMatch()?.let { return it }
        }
        return directConnect()
    }

    companion object {
        const val KEY_ONBOARDED = "onboarding_done_v2"
        const val EXTRA_SCREEN = "com.aria.ariacast.EXTRA_SCREEN"
        const val KEY_GROUPS = "saved_groups"

        fun platformForDeepLinkType(type: String?): String = when (type?.lowercase()) {
            "airplay" -> "AirPlay"
            "airplay2" -> "AirPlay2"
            "dlna" -> "DLNA"
            "googlecast", "google_cast", "google-cast" -> "Google Cast"
            "sendspin" -> "Sendspin"
            "vban" -> "VBAN"
            else -> "AriaCast"
        }

        fun defaultPortForDeepLinkPlatform(platform: String): Int = when (platform) {
            "AriaCast" -> 12889
            "AirPlay" -> 5000
            "AirPlay2" -> 7000
            "Google Cast" -> 8009
            "Sendspin" -> 8927
            "VBAN" -> 6980
            else -> 0 // DLNA needs SSDP-sourced control URLs
        }

        fun serversJson(servers: List<Server>): String = JSONArray().apply {
            servers.forEach { s ->
                put(JSONObject().put("name", s.name).put("host", s.host).put("port", s.port).put("platform", s.platform).put("extra", s.extra))
            }
        }.toString()
    }
}
